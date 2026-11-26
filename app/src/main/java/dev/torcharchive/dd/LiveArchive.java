package dev.torcharchive.dd;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import dev.torcharchive.game.DisplayConfig;
import dev.torcharchive.game.GameArchive;
import dev.torcharchive.game.ModBridge;
import dev.torcharchive.game.NxProcessControl;
import dev.torcharchive.game.PakMount;
import dev.torcharchive.game.SnapshotStore;

/**
 * Direct same-UID facade over the unified game engine layer (zero cross-app IPC).
 */
final class LiveArchive {
    static final String GAME = "dev.torcharchive.ddgame";
    private static final String PREFIX = "auto-";

    record History(List<ArchiveStore.Entry> entries, String status) {}
    record DisplayInfo(int width, int height, boolean fillScreen, boolean avoidCutout,
                       int screenW, int screenH, int refreshRate, int effectiveFps,
                       int maxRefreshRate, int[] supportedRates) {}
    record GameMod(String id, String title, String version, String format, long files, long bytes, boolean prepared) {}
    record SlotMod(String name, String source) {}
    record SlotMods(String slot, String label, long modified, int steamCount, List<SlotMod> mods) {}

    private final Context context;

    LiveArchive(Context context) {
        this.context = context.getApplicationContext();
    }

    boolean available() {
        return true;
    }

    PakMount.PakInfo pakInfo() {
        return PakMount.inspect(context);
    }

    boolean isGameRunning() {
        return NxProcessControl.isGameRunning(context);
    }

    boolean stopGame() {
        return NxProcessControl.stopGameProcess(context);
    }

    void launchGame() {
        NxProcessControl.launchGame(context);
    }

    void restartGame() {
        NxProcessControl.restartAndLaunchGame(context);
    }

    String resetSwitchModCache() throws IOException {
        return ModBridge.resetSwitchModCache(context);
    }

    History history() {
        List<ArchiveStore.Entry> entries = new ArrayList<>();
        try {
            SnapshotStore store = GameArchive.getStore(context);
            for (String id : store.list()) {
                String slot = SnapshotStore.slotOf(id);
                long date = SnapshotStore.timestampOf(id);
                long bytes = store.archive(id).toFile().length();
                String title = "自动留档 · 档位 " + (Integer.parseInt(slot.substring(8)) + 1);
                entries.add(new ArchiveStore.Entry(PREFIX + id, ArchiveStore.SAVE, title,
                        "游戏保存快照", date, bytes, 1));
            }
            return new History(entries, describe(GameArchive.state(context)));
        } catch (IOException | RuntimeException error) {
            Log.w("TorchArchive", "Cannot read save history", error);
            return new History(List.of(), "暂时无法读取自动存档：" + error.getMessage());
        }
    }

    void backupSlot(String slot) throws IOException {
        GameArchive.backupSlotNow(context, slot);
    }

    void restore(ArchiveStore.Entry entry) throws IOException {
        GameArchive.restoreNow(context, remoteId(entry));
    }

    InputStream open(ArchiveStore.Entry entry) throws IOException {
        File file = GameArchive.getStore(context).archive(remoteId(entry)).toFile();
        return new FileInputStream(file);
    }

    DisplayInfo display() {
        return parseDisplay(DisplayConfig.describe(context));
    }

    DisplayInfo setDisplay(int width, int height, boolean fillScreen, boolean avoidCutout, int refreshRate) throws IOException {
        return parseDisplay(DisplayConfig.apply(context, width, height, fillScreen, avoidCutout, refreshRate));
    }

    List<GameMod> listMods() throws IOException {
        String raw = ModBridge.listModsJson(context);
        List<GameMod> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(raw);
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
        String res = ModBridge.installModStream(context, zipStream, sourceName);
        try {
            JSONObject obj = new JSONObject(res);
            return obj.optInt("count", 0);
        } catch (Exception error) {
            throw new IOException("模组安装结果解析失败。", error);
        }
    }

    ModBridge.MigrationResult importSteamMigration(InputStream zipStream) throws IOException {
        return ModBridge.importSteamMigrationZip(context, zipStream);
    }

    void deleteMod(String modId) throws IOException {
        ModBridge.deleteMod(context, modId);
    }

    List<SlotMods> listSlotMods() throws IOException {
        String raw = ModBridge.listSlotModsJson(context);
        List<SlotMods> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(raw);
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
        ModBridge.setSlotMods(context, slot, arr.toString());
    }

    private static DisplayInfo parseDisplay(Bundle b) {
        int[] rates = b.getIntArray("supported_rates");
        if (rates == null || rates.length == 0) rates = new int[]{60};
        return new DisplayInfo(
                b.getInt("width", 1920),
                b.getInt("height", 1080),
                b.getBoolean("fill_screen", false),
                b.getBoolean("avoid_cutout", true),
                b.getInt("screen_w", 1920),
                b.getInt("screen_h", 1080),
                b.getInt("refresh_rate", 60),
                b.getInt("effective_fps", 60),
                b.getInt("max_refresh_rate", 60),
                rates
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

    private static String describe(SharedPreferences state) {
        if (state == null) return "等待游戏运行";
        String restore = switch (state.getString("restore", "")) {
            case "queued" -> "已准备回档。";
            case "applying" -> "正在应用回档";
            case "restored" -> "最近一次回档已成功应用";
            case "interrupted" -> "回档过程曾被中断，请核对档位。";
            case "failed" -> "回档失败：" + state.getString("restore_message", "");
            default -> "";
        };
        String capture = switch (state.getString("capture", "")) {
            case "ready" -> "同 UID 直连 · 每次保存自动留档";
            case "failed" -> "自动留档异常：" + state.getString("capture_message", "");
            default -> "同 UID 直连就绪 · 启动游戏后自动留档";
        };
        return restore.isEmpty() ? capture : restore + "\n" + capture;
    }
}
