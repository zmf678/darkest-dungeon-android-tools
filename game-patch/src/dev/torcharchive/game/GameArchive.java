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
    static final String AUTHORITY = "dev.torcharchive.ddgame.archive";
    static final Uri VERSIONS = Uri.parse("content://" + AUTHORITY + "/versions");
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
            File external = activity.getExternalFilesDir(null);
            if (external == null) throw new IOException("Game storage is unavailable");
            saves = external.toPath().resolve("nx/save");
            Files.createDirectories(saves);
            SnapshotStore.recover(saves);
            applyQueued();
            getStore(activity).baseline(saves);
            System.loadLibrary("torcharchive");
            int result = installCommitCallback();
            if (result != 0) throw new IOException("Commit callback was not installed (" + result + ")");
            setCaptureState("ready", "");
        } catch (Exception | LinkageError error) {
            captureError(error);
        }
    }

    private static native int installCommitCallback();

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

    static synchronized SnapshotStore getStore(Context caller) throws IOException {
        if (store == null) store = new SnapshotStore(caller.getFilesDir().toPath().resolve("save-history"));
        return store;
    }

    static SharedPreferences state(Context caller) {
        return caller.getSharedPreferences("torcharchive-bridge", Context.MODE_PRIVATE);
    }

    static synchronized void queue(Context caller, String id) throws IOException {
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
        state(context).edit().putString("capture", status).putString("capture_message", detail).apply();
    }

    private static void captureError(Throwable error) {
        Log.e(TAG, "Automatic save archive failed", error);
        setCaptureState("failed", error.getMessage());
        context.getContentResolver().notifyChange(VERSIONS, null);
    }
}
