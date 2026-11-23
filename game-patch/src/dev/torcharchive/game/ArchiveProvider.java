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

import java.io.FileNotFoundException;
import java.io.IOException;

public final class ArchiveProvider extends ContentProvider {
    private static final String PERMISSION = "dev.torcharchive.ddgame.permission.ARCHIVE";

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

    @Override public Bundle call(String method, String id, Bundle extras) {
        requireArchive();
        Bundle reply = new Bundle();
        Context context = getContext();
        if ("restore".equals(method)) {
            try {
                GameArchive.queue(context, id);
            } catch (IOException failure) {
                reply.putString("error", failure.getMessage());
            }
        } else if (!"status".equals(method)) {
            throw new IllegalArgumentException("Unknown archive method");
        }
        SharedPreferences state = GameArchive.state(context);
        for (String key : new String[]{"capture", "capture_message", "restore", "restore_id", "restore_message"}) {
            reply.putString(key, state.getString(key, ""));
        }
        return reply;
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        requireArchive();
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
