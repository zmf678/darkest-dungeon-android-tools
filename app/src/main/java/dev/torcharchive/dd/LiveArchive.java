package dev.torcharchive.dd;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

final class LiveArchive {
    static final String GAME = "dev.torcharchive.ddgame";
    private static final String AUTHORITY = GAME + ".archive";
    static final Uri URL = Uri.parse("content://" + AUTHORITY + "/versions");
    private static final Uri MOD_UPLOAD_URL = Uri.parse("content://" + AUTHORITY + "/mod-upload");
    private static final String PREFIX = "auto-";

    record History(List<ArchiveStore.Entry> entries, String status) {}
    record DisplayInfo(int width, int height, boolean fillScreen, int screenW, int screenH) {}
    record GameMod(String id, String title, String version, String format, long files, long bytes, boolean prepared) {}
    record SlotMod(String name, String source) {}
    record SlotMods(String slot, String label, long modified, int steamCount, List<SlotMod> mods) {}

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

    DisplayInfo display() throws IOException {
        if (!available()) return null;
        Bundle result = callChecked("get_display", null, null);
        return parseDisplay(result);
    }

    DisplayInfo setDisplay(int width, int height, boolean fillScreen) throws IOException {
        Bundle args = new Bundle();
        args.putInt("width", width);
        args.putInt("height", height);
        args.putBoolean("fill_screen", fillScreen);
        return parseDisplay(callChecked("set_display", null, args));
    }

    List<GameMod> listMods() throws IOException {
        if (!available()) return List.of();
        Bundle result = callChecked("list_mods", null, null);
        List<GameMod> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(result.getString("mods", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                out.add(new GameMod(
                        o.getString("id"),
                        o.optString("title", o.getString("id")),
                        o.optString("version", ""),
                        o.optString("format", "project.xml"),
                        o.optLong("files", 0),
                        o.optLong("bytes", 0),
                        o.optBoolean("prepared", false)
                ));
            }
        } catch (Exception error) {
            throw new IOException("解析已安装模组失败：" + error.getMessage(), error);
        }
        return out;
    }

    int installMod(InputStream zipStream, String sourceName) throws IOException {
        try (OutputStream out = resolver.openOutputStream(MOD_UPLOAD_URL, "w")) {
            if (out == null) throw new IOException("无法写入游戏模组通道。请确认实验版已升级。");
            byte[] buffer = new byte[64 * 1024];
            for (int n; (n = zipStream.read(buffer)) > 0;) out.write(buffer, 0, n);
            out.flush();
        }
        Bundle result = callChecked("install_staged_mod", sourceName, null);
        try {
            JSONObject obj = new JSONObject(result.getString("result", "{}"));
            return obj.optInt("count", 0);
        } catch (Exception error) {
            throw new IOException("模组安装结果解析失败。", error);
        }
    }

    void deleteMod(String modId) throws IOException {
        callChecked("delete_mod", modId, null);
    }

    List<SlotMods> listSlotMods() throws IOException {
        if (!available()) return List.of();
        Bundle result = callChecked("list_slot_mods", null, null);
        List<SlotMods> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(result.getString("slots", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                JSONArray modArr = o.optJSONArray("mods");
                List<SlotMod> mods = new ArrayList<>();
                if (modArr != null) {
                    for (int j = 0; j < modArr.length(); j++) {
                        JSONObject m = modArr.getJSONObject(j);
                        mods.add(new SlotMod(m.getString("name"), m.optString("source", "mod")));
                    }
                }
                out.add(new SlotMods(
                        o.getString("slot"),
                        o.optString("label", o.getString("slot")),
                        o.optLong("modified", 0),
                        o.optInt("steamCount", 0),
                        mods
                ));
            }
        } catch (Exception error) {
            throw new IOException("解析档位模组配置失败：" + error.getMessage(), error);
        }
        return out;
    }

    void setSlotMods(String slot, List<SlotMod> mods) throws IOException {
        JSONArray arr = new JSONArray();
        try {
            for (SlotMod mod : mods) {
                JSONObject o = new JSONObject();
                o.put("name", mod.name());
                o.put("source", mod.source());
                arr.put(o);
            }
        } catch (Exception error) {
            throw new IOException("序列化模组列表失败。", error);
        }
        Bundle args = new Bundle();
        args.putString("mods", arr.toString());
        callChecked("set_slot_mods", slot, args);
    }

    private Bundle callChecked(String method, String arg, Bundle extras) throws IOException {
        Bundle result;
        try {
            result = resolver.call(URL, method, arg, extras);
        } catch (SecurityException error) {
            throw new IOException("游戏接口拒绝访问，请确认两个实验版使用同一签名。", error);
        } catch (IllegalArgumentException error) {
            String msg = error.getMessage() == null ? "" : error.getMessage();
            if (msg.contains("Unknown archive method")) {
                throw new IOException("当前游戏实验版版本较旧，请先安装新版游戏 APK。", error);
            }
            throw new IOException("实验版游戏未在后台运行（或被系统限制唤醒）。请先点右上角「打开实验版」进入主界面后再切回档案馆。", error);
        } catch (RuntimeException error) {
            throw new IOException("无法连接游戏接口（请先启动一次实验版游戏）。", error);
        }
        if (result == null) throw new IOException("游戏接口无响应。");
        if (result.containsKey("error")) {
            throw new IOException(result.getString("error", "游戏执行失败。"));
        }
        return result;
    }

    private static DisplayInfo parseDisplay(Bundle b) {
        return new DisplayInfo(
                b.getInt("width", 1920),
                b.getInt("height", 1080),
                b.getBoolean("fill_screen", false),
                b.getInt("screen_w", 1920),
                b.getInt("screen_h", 1080)
        );
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
