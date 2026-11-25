package dev.torcharchive.game;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.Process;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

public final class ArchiveProvider extends ContentProvider {
    private static final String PERMISSION = "dev.torcharchive.ddgame.permission.ARCHIVE";
    private static final String MOD_UPLOAD_SEGMENT = "mod-upload";

    @Override public boolean onCreate() { return true; }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                  String[] selectionArgs, String sortOrder) {
        requireArchive();
        if (!GameArchive.VERSIONS.equals(uri)) throw new IllegalArgumentException("Unknown archive URI");
        MatrixCursor cursor = new MatrixCursor(new String[]{"id", "date", "slot", "bytes"});
        try {
            synchronized (GameArchive.class) {
                SnapshotStore store = GameArchive.getStore(getContext());
                for (String id : store.list()) {
                    cursor.addRow(new Object[]{id, Long.parseLong(id.substring(20, 33)),
                            SnapshotStore.slotOf(id), store.archive(id).toFile().length()});
                }
            }
            cursor.setNotificationUri(getContext().getContentResolver(), GameArchive.VERSIONS);
            return cursor;
        } catch (IOException failure) {
            cursor.close();
            throw new IllegalStateException("Cannot read save history", failure);
        }
    }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        requireArchive();
        Context context = getContext();
        Bundle reply = new Bundle();
        try {
            switch (method) {
                case "restore":
                    GameArchive.queue(context, arg);
                    appendArchiveState(context, reply);
                    return reply;
                case "status":
                    appendArchiveState(context, reply);
                    return reply;
                case "get_display":
                    return DisplayConfig.describe(context);
                case "set_display": {
                    if (extras == null) throw new IOException("缺少分辨率参数");
                    int w = extras.getInt("width", 1920);
                    int h = extras.getInt("height", 1080);
                    boolean fill = extras.getBoolean("fill_screen", false);
                    return DisplayConfig.apply(context, w, h, fill);
                }
                case "list_mods":
                    reply.putString("mods", ModBridge.listModsJson(context));
                    return reply;
                case "install_staged_mod": {
                    File staged = new File(context.getCacheDir(), "mod-upload.zip");
                    reply.putString("result", ModBridge.installStagedZip(context, staged.toPath(), arg));
                    return reply;
                }
                case "delete_mod":
                    ModBridge.deleteMod(context, arg);
                    reply.putBoolean("deleted", true);
                    return reply;
                case "list_slot_mods":
                    reply.putString("slots", ModBridge.listSlotModsJson(context));
                    return reply;
                case "set_slot_mods": {
                    String modsJson = extras == null ? "[]" : extras.getString("mods", "[]");
                    ModBridge.setSlotMods(context, arg, modsJson);
                    reply.putBoolean("saved", true);
                    return reply;
                }
                default:
                    throw new IllegalArgumentException("Unknown archive method: " + method);
            }
        } catch (IOException | RuntimeException failure) {
            reply.putString("error", failure.getMessage() == null ? failure.toString() : failure.getMessage());
            if ("restore".equals(method) || "status".equals(method)) {
                appendArchiveState(context, reply);
            }
            return reply;
        }
    }

    private static void appendArchiveState(Context context, Bundle reply) {
        SharedPreferences state = GameArchive.state(context);
        for (String key : new String[]{"capture", "capture_message", "restore", "restore_id", "restore_message"}) {
            reply.putString(key, state.getString(key, ""));
        }
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        requireArchive();
        if ("w".equals(mode) && uri.getPathSegments().size() == 1
                && MOD_UPLOAD_SEGMENT.equals(uri.getPathSegments().get(0))) {
            File staged = new File(getContext().getCacheDir(), "mod-upload.zip");
            return ParcelFileDescriptor.open(staged,
                    ParcelFileDescriptor.MODE_WRITE_ONLY | ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_TRUNCATE);
        }
        if (!"r".equals(mode) || uri.getPathSegments().size() != 2 ||
                !"versions".equals(uri.getPathSegments().get(0))) throw new FileNotFoundException();
        try {
            synchronized (GameArchive.class) {
                return ParcelFileDescriptor.open(GameArchive.getStore(getContext())
                        .archive(uri.getLastPathSegment()).toFile(), ParcelFileDescriptor.MODE_READ_ONLY);
            }
        } catch (IOException failure) {
            throw new FileNotFoundException("Cannot open snapshot");
        }
    }

    private void requireArchive() {
        if (Binder.getCallingUid() == Process.myUid()) return;
        getContext().enforceCallingPermission(PERMISSION, "Archive certificate required");
        String caller = getCallingPackage();
        if (!"dev.torcharchive.dd".equals(caller) && !"dev.torcharchive.dd.lab".equals(caller)) {
            throw new SecurityException("Caller is not the archive app");
        }
    }

    @Override public String getType(Uri uri) {
        return GameArchive.VERSIONS.equals(uri) ? "vnd.android.cursor.dir/vnd.torcharchive.version" : "application/zip";
    }

    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String where, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String where, String[] args) { throw new UnsupportedOperationException(); }
}
