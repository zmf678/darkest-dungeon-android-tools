package dev.torcharchive.dd;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

final class LiveArchive {
    static final String GAME = "dev.torcharchive.ddgame";
    private static final String AUTHORITY = GAME + ".archive";
    static final Uri URL = Uri.parse("content://" + AUTHORITY + "/versions");
    private static final String PREFIX = "auto-";

    record History(List<ArchiveStore.Entry> entries, String status) {}

    private final Context context;
    private final ContentResolver resolver;

    LiveArchive(Context context) {
        this.context = context;
        resolver = context.getContentResolver();
    }

    boolean available() {
        return context.getPackageManager().resolveContentProvider(AUTHORITY, 0) != null;
    }

    History history() {
        if (!available()) return new History(List.of(), "");
        List<ArchiveStore.Entry> entries = new ArrayList<>();
        try (Cursor cursor = resolver.query(URL, null, null, null, null)) {
            if (cursor == null) throw new IllegalStateException("存档接口返回空结果");
            while (cursor.moveToNext()) {
                String id = cursor.getString(0);
                String slot = cursor.getString(2);
                String title = "自动存档 · 档位 " + (Integer.parseInt(slot.substring(8)) + 1);
                entries.add(new ArchiveStore.Entry(PREFIX + id, ArchiveStore.SAVE, title,
                        "游戏保存完成", cursor.getLong(1), cursor.getLong(3), 1));
            }
            return new History(entries, describe(resolver.call(URL, "status", null, null)));
        } catch (SecurityException error) {
            return new History(List.of(), "存档接口拒绝访问。请使用签名匹配的游戏与档案馆。");
        } catch (RuntimeException error) {
            Log.w("TorchArchive", "Cannot query automatic save history", error);
            return new History(List.of(), "暂时无法读取自动存档。");
        }
    }

    void restore(ArchiveStore.Entry entry) throws IOException {
        Bundle result = resolver.call(URL, "restore", remoteId(entry), null);
        if (result == null || result.containsKey("error") || !"queued".equals(result.getString("restore"))) {
            throw new IOException(result == null ? "游戏未确认回档请求。" : result.getString("error", "游戏未确认回档请求。"));
        }
    }

    InputStream open(ArchiveStore.Entry entry) throws IOException {
        InputStream input = resolver.openInputStream(URL.buildUpon().appendPath(remoteId(entry)).build());
        if (input == null) throw new IOException("无法读取自动存档。");
        return input;
    }

    static boolean isAutomatic(ArchiveStore.Entry entry) {
        return entry.id().startsWith(PREFIX);
    }

    static String displayId(ArchiveStore.Entry entry) {
        return isAutomatic(entry) ? Long.toString(Long.parseLong(remoteId(entry).substring(0, 19)))
                : entry.id().substring(0, 7);
    }

    static String slot(ArchiveStore.Entry entry) {
        return remoteId(entry).substring(34, 43);
    }

    private static String remoteId(ArchiveStore.Entry entry) {
        return entry.id().substring(PREFIX.length());
    }

    private static String describe(Bundle result) {
        if (result == null) return "等待游戏报告自动存档状态";
        String restore = switch (result.getString("restore", "")) {
            case "queued" -> "已准备回档。完全退出实验版游戏后重新打开。";
            case "applying" -> "游戏正在回档";
            case "restored" -> "游戏已确认回档";
            case "interrupted" -> "回档过程被中断。请在游戏中核对档位。";
            case "failed" -> "游戏回档失败 " + result.getString("restore_message", "");
            default -> "";
        };
        String capture = switch (result.getString("capture", "")) {
            case "ready" -> "自动存档已启用";
            case "failed" -> "自动存档失败 " + result.getString("capture_message", "");
            default -> "等待实验版游戏首次启动";
        };
        return restore.isEmpty() ? capture : restore + "\n" + capture;
    }
}
