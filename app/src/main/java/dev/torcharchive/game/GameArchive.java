package dev.torcharchive.game;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class GameArchive {
    public static final String AUTHORITY = "dev.torcharchive.ddgame.archive";
    public static final Uri VERSIONS = Uri.parse("content://" + AUTHORITY + "/versions");
    private static final String TAG = "TorchArchive";
    private static Context context;
    private static SnapshotStore store;
    private static Path saves;
    private static boolean initialized;

    private GameArchive() {}

    public static synchronized void install(Context activity) {
        if (initialized) return;
        initialized = true;
        context = activity.getApplicationContext();
        try {
            saves = savesDir(context);
            SnapshotStore.recover(saves);
            applyQueued();
            getStore(context).baseline(saves);
            System.loadLibrary("torcharchive");
            int result = installCommitCallback();
            if (result != 0) throw new IOException("Commit callback was not installed (" + result + ")");
            int targetFps = DisplayConfig.resolveTargetFps(context);
            try {
                int fpsRes = setNativeTargetFps(targetFps);
                Log.i(TAG, "Native target FPS set to " + targetFps + " Hz (rc=" + fpsRes + ")");
            } catch (Throwable fpsErr) {
                Log.w(TAG, "Could not apply native target FPS " + targetFps, fpsErr);
            }
            setCaptureState("ready", "");
        } catch (Exception | LinkageError error) {
            captureError(error);
        }
    }

    private static native int installCommitCallback();
    private static native int setNativeTargetFps(int targetFps);

    private static synchronized void afterCommit() {
        try {
            int count = store.capture(saves);
            if (count != 0) {
                Log.i(TAG, "Published " + count + " committed profile(s)");
                context.getContentResolver().notifyChange(VERSIONS, null);
            }
            setCaptureState("ready", "");
        } catch (Exception error) {
            captureError(error);
        }
    }

    public static Path savesDir(Context caller) throws IOException {
        File external = caller.getExternalFilesDir(null);
        if (external == null) throw new IOException("游戏存档目录不可用");
        Path dir = external.toPath().resolve("nx/save");
        Files.createDirectories(dir);
        return dir;
    }

    public static synchronized SnapshotStore getStore(Context caller) throws IOException {
        if (store == null) {
            store = new SnapshotStore(caller.getApplicationContext().getFilesDir().toPath().resolve("save-history"));
        }
        return store;
    }

    public static SharedPreferences state(Context caller) {
        return caller.getApplicationContext().getSharedPreferences("torcharchive-bridge", Context.MODE_PRIVATE);
    }

    /**
     * Direct same-UID restore used by the unified launcher: stops :nx first, snapshots current slot,
     * and atomically restores the selected archive immediately without requiring a queued restart.
     */
    public static synchronized void restoreNow(Context caller, String id) throws IOException {
        Context app = caller.getApplicationContext();
        NxProcessControl.stopGameProcess(app);
        Path dir = savesDir(app);
        SnapshotStore.recover(dir);
        SnapshotStore s = getStore(app);
        s.restore(dir, id);
        s.baseline(dir);
        setRestoreState(state(app), "restored", "");
        app.getContentResolver().notifyChange(VERSIONS, null);
    }

    public static synchronized int captureNow(Context caller) throws IOException {
        Context app = caller.getApplicationContext();
        Path dir = savesDir(app);
        SnapshotStore.recover(dir);
        SnapshotStore s = getStore(app);
        int count = s.capture(dir);
        if (count > 0) {
            app.getContentResolver().notifyChange(VERSIONS, null);
        }
        return count;
    }

    public static synchronized String backupSlotNow(Context caller, String slot) throws IOException {
        if (slot == null || !slot.matches("profile_[0-8]")) {
            throw new IOException("无效档位: " + slot);
        }
        Context app = caller.getApplicationContext();
        Path dir = savesDir(app);
        Path profile = dir.resolve(slot);
        SnapshotStore s = getStore(app);
        String id = s.backup(profile);
        s.baseline(dir);
        app.getContentResolver().notifyChange(VERSIONS, null);
        return id;
    }

    public static synchronized void queue(Context caller, String id) throws IOException {
        getStore(caller).archive(id);
        SharedPreferences state = state(caller);
        if ("queued".equals(state.getString("restore", "")) || "applying".equals(state.getString("restore", ""))) {
            throw new IOException("A restore is already queued");
        }
        if (!state.edit().putString("restore", "queued").putString("restore_id", id)
                .remove("restore_message").commit()) throw new IOException("Could not queue the restore");
    }

    private static void applyQueued() throws IOException {
        SharedPreferences state = state(context);
        String status = state.getString("restore", "");
        if ("applying".equals(status)) {
            setRestoreState(state, "interrupted", "The game exited while applying the restore");
            return;
        }
        if (!"queued".equals(status)) return;
        getStore(context);
        setRestoreState(state, "applying", "");
        try {
            store.restore(saves, state.getString("restore_id", ""));
        } catch (IOException | RuntimeException failure) {
            Log.e(TAG, "Restore failed", failure);
            setRestoreState(state, "failed", failure.getMessage());
            return;
        }
        setRestoreState(state, "restored", "");
    }

    private static void setRestoreState(SharedPreferences state, String status, String detail) throws IOException {
        if (!state.edit().putString("restore", status).putString("restore_message", detail).commit()) {
            throw new IOException("Unable to persist restore status");
        }
    }

    private static void setCaptureState(String status, String detail) {
        if (context != null) {
            state(context).edit().putString("capture", status).putString("capture_message", detail).apply();
        }
    }

    private static void captureError(Throwable error) {
        Log.e(TAG, "Automatic save archive failed", error);
        setCaptureState("failed", error.getMessage());
        if (context != null) {
            context.getContentResolver().notifyChange(VERSIONS, null);
        }
    }
}
