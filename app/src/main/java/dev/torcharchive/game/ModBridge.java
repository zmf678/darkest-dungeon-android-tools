package dev.torcharchive.game;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Direct mod directory, Steam migration, and save-slot mod-list management.
 */
public final class ModBridge {
    private static final long MAX_ZIP_BYTES = 2L * 1024 * 1024 * 1024;
    private static final int MAX_ZIP_FILES = 40000;
    private static final Pattern TITLE = Pattern.compile("(?is)<(?:title|mod_title)>\\s*(?:<!\\[CDATA\\[)?([^<]+)");
    private static final Pattern VER_MAJOR = Pattern.compile("(?is)<VersionMajor>\\s*(\\d+)");
    private static final Pattern VER_MINOR = Pattern.compile("(?is)<VersionMinor>\\s*(\\d+)");
    private static final Set<String> LEGACY_DIRS = Set.of(
            "activity_log", "audio", "campaign", "colours", "curios", "cursors", "dungeons",
            "effects", "fe_flow", "fonts", "fx", "game_over", "heroes", "hero", "inventory",
            "loading_screen", "localization", "loot", "maps", "modes", "monsters", "overlays",
            "panels", "pin", "props", "quirks", "raid", "raid_results", "scripts", "scrolls",
            "shaders", "shared", "trinkets", "ui", "upgrades", "user_information", "video");

    public static final class MigrationResult {
        public final int modsInstalled;
        public final int slotsImported;
        public final List<String> slots;

        MigrationResult(int modsInstalled, int slotsImported, List<String> slots) {
            this.modsInstalled = modsInstalled;
            this.slotsImported = slotsImported;
            this.slots = slots;
        }
    }

    private ModBridge() {}

    public static File modsDir(Context context) throws IOException {
        File media = PakMount.mediaRoot(context);
        File mods = new File(media, "mods");
        if (!mods.isDirectory() && !mods.mkdirs()) {
            throw new IOException("无法创建游戏 mods 目录");
        }
        return mods;
    }

    public static Path savesDir(Context context) throws IOException {
        return GameArchive.savesDir(context);
    }

    public static String listModsJson(Context context) throws IOException {
        File dir = modsDir(context);
        File switchMarks = new File(dir, ".port-switch");
        File[] children = dir.listFiles();
        JSONArray array = new JSONArray();
        if (children != null) {
            Arrays.sort(children, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
            for (File folder : children) {
                String id = folder.getName();
                if (!folder.isDirectory() || id.startsWith(".")) continue;
                File manifest = new File(folder, "project.xml");
                boolean hasProject = manifest.isFile();
                if (!hasProject && !hasLegacyDir(folder)) continue;
                String title = id;
                String version = "";
                if (hasProject) {
                    String xml = readXml(manifest);
                    String parsedTitle = matchGroup(TITLE, xml);
                    if (!parsedTitle.isEmpty()) title = parsedTitle;
                    String maj = matchGroup(VER_MAJOR, xml);
                    String min = matchGroup(VER_MINOR, xml);
                    if (!maj.isEmpty()) version = min.isEmpty() ? maj : (maj + "." + min);
                }
                long[] stats = folderStats(folder.toPath());
                try {
                    JSONObject item = new JSONObject();
                    item.put("id", id);
                    item.put("title", title);
                    item.put("version", version);
                    item.put("format", hasProject ? "project.xml" : "旧版目录");
                    item.put("files", stats[0]);
                    item.put("bytes", stats[1]);
                    item.put("prepared", new File(switchMarks, id).isFile());
                    array.put(item);
                } catch (Exception ignored) {
                }
            }
        }
        return array.toString();
    }

    public static String installModStream(Context context, InputStream zipStream, String sourceLabel) throws IOException {
        Path staged = Files.createTempFile(context.getCacheDir().toPath(), "mod-stream-", ".zip");
        try {
            byte[] buf = new byte[64 * 1024];
            try (FileOutputStream out = new FileOutputStream(staged.toFile())) {
                int n;
                while ((n = zipStream.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
                out.getFD().sync();
            }
            return installStagedZip(context, staged, sourceLabel);
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    public static String installStagedZip(Context context, Path stagedZip, String sourceLabel) throws IOException {
        if (!Files.isRegularFile(stagedZip)) throw new IOException("找不到待安装的 Mod ZIP");
        File modsRoot = modsDir(context);
        Path work = Files.createTempDirectory(context.getCacheDir().toPath(), "mod-install-");
        try {
            unzipSafe(stagedZip, work);
            List<Path> roots = new ArrayList<>();
            findModRoots(work, 0, roots);
            if (roots.isEmpty()) {
                throw new IOException("ZIP 中找不到 project.xml 或游戏支持的旧版 Mod 目录");
            }
            JSONArray installed = new JSONArray();
            String fallbackName = sanitizeModId(stripZip(sourceLabel));
            for (Path root : roots) {
                String rawName = root.equals(work) ? fallbackName : root.getFileName().toString();
                String modId = sanitizeModId(rawName);
                if (modId.isEmpty()) modId = "mod-" + System.currentTimeMillis();
                installModFolder(modsRoot.toPath(), root, modId);
                installed.put(modId);
            }
            JSONObject result = new JSONObject();
            result.put("count", installed.length());
            result.put("installed", installed);
            return result.toString();
        } catch (IOException error) {
            throw error;
        } catch (Exception error) {
            throw new IOException("安装 Mod 失败: " + error.getMessage(), error);
        } finally {
            Files.deleteIfExists(stagedZip);
            deleteTree(work);
        }
    }

    private static void installModFolder(Path modsRoot, Path sourceRoot, String modId) throws IOException {
        Path target = modsRoot.resolve(modId);
        Path incoming = modsRoot.resolve(".incoming-" + modId + "-" + System.currentTimeMillis());
        copyDirectory(sourceRoot, incoming);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            Path old = modsRoot.resolve(".old-" + modId + "-" + System.currentTimeMillis());
            Files.move(target, old, StandardCopyOption.ATOMIC_MOVE);
            deleteTree(old);
        }
        Files.move(incoming, target, StandardCopyOption.ATOMIC_MOVE);
        Files.deleteIfExists(modsRoot.resolve(".port-switch").resolve(modId));
    }

    public static MigrationResult importSteamMigrationZip(Context context, InputStream zipStream) throws IOException {
        Path staged = Files.createTempFile(context.getCacheDir().toPath(), "steam-migration-", ".zip");
        Path work = Files.createTempDirectory(context.getCacheDir().toPath(), "steam-mig-work-");
        try {
            byte[] buf = new byte[64 * 1024];
            try (FileOutputStream out = new FileOutputStream(staged.toFile())) {
                int n;
                while ((n = zipStream.read(buf)) > 0) out.write(buf, 0, n);
                out.getFD().sync();
            }
            unzipSafe(staged, work);
            NxProcessControl.stopGameProcess(context);

            int modsInstalled = 0;
            Path modsRoot = modsDir(context).toPath();
            Path bundledMods = work.resolve("mods");
            if (Files.isDirectory(bundledMods)) {
                try (Stream<Path> children = Files.list(bundledMods)) {
                    for (Path child : children.sorted().collect(Collectors.toList())) {
                        if (!Files.isDirectory(child)) continue;
                        String modId = sanitizeModId(child.getFileName().toString());
                        if (modId.isEmpty()) continue;
                        installModFolder(modsRoot, child, modId);
                        modsInstalled++;
                    }
                }
            }

            List<String> importedSlots = new ArrayList<>();
            Path savesRoot = savesDir(context);
            Path bundledSaves = work.resolve("saves");
            Path searchSaves = Files.isDirectory(bundledSaves) ? bundledSaves : work;
            synchronized (GameArchive.class) {
                SnapshotStore store = GameArchive.getStore(context);
                for (int i = 0; i <= 8; i++) {
                    String slot = "profile_" + i;
                    Path srcSlot = searchSaves.resolve(slot);
                    if (!Files.isRegularFile(srcSlot.resolve("persist.game.json"))) continue;
                    Path dstSlot = savesRoot.resolve(slot);
                    if (SnapshotStore.validProfile(dstSlot)) {
                        store.backup(dstSlot);
                    }
                    // Ensure Steam source mods are normalized to "mod"
                    Path gameJson = srcSlot.resolve("persist.game.json");
                    byte[] raw = Files.readAllBytes(gameJson);
                    if (DsonCodec.isDson(raw)) {
                        List<DsonCodec.ModRef> refs = DsonCodec.readAppliedMods(raw);
                        List<DsonCodec.ModRef> normalized = new ArrayList<>(refs.size());
                        for (DsonCodec.ModRef ref : refs) {
                            normalized.add(new DsonCodec.ModRef(ref.name, "mod"));
                        }
                        byte[] fixed = DsonCodec.writeAppliedMods(raw, normalized);
                        Files.write(gameJson, fixed);
                    }
                    Path incoming = savesRoot.resolve(".incoming-" + slot + "-" + System.currentTimeMillis());
                    copyDirectory(srcSlot, incoming);
                    if (Files.exists(dstSlot, LinkOption.NOFOLLOW_LINKS)) {
                        Path old = savesRoot.resolve(".old-" + slot + "-" + System.currentTimeMillis());
                        Files.move(dstSlot, old, StandardCopyOption.ATOMIC_MOVE);
                        deleteTree(old);
                    }
                    Files.move(incoming, dstSlot, StandardCopyOption.ATOMIC_MOVE);
                    importedSlots.add(slot);
                }
                if (modsInstalled == 0 && importedSlots.isEmpty()) {
                    throw new IOException("迁移包内未找到有效的 mods/ 或 profile_0..8 存档目录");
                }
                store.baseline(savesRoot);
            }
            context.getContentResolver().notifyChange(GameArchive.VERSIONS, null);
            return new MigrationResult(modsInstalled, importedSlots.size(), importedSlots);
        } finally {
            Files.deleteIfExists(staged);
            deleteTree(work);
        }
    }

    public static void deleteMod(Context context, String modId) throws IOException {
        validateModId(modId);
        Path mods = modsDir(context).toPath();
        Path target = mods.resolve(modId).normalize();
        if (!target.getParent().equals(mods) || !Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("找不到已安装 Mod: " + modId);
        }
        deleteTree(target);
        Files.deleteIfExists(mods.resolve(".port-switch").resolve(modId));
        Path orig = mods.resolve(".port-nx-original").resolve(modId);
        if (Files.exists(orig, LinkOption.NOFOLLOW_LINKS)) deleteTree(orig);
    }

    public static String listSlotModsJson(Context context) throws IOException {
        Path saves = savesDir(context);
        JSONArray array = new JSONArray();
        for (int i = 0; i <= 8; i++) {
            String slot = "profile_" + i;
            Path gameFile = saves.resolve(slot).resolve("persist.game.json");
            if (!Files.isRegularFile(gameFile, LinkOption.NOFOLLOW_LINKS)) continue;
            byte[] raw = Files.readAllBytes(gameFile);
            if (!DsonCodec.isDson(raw)) continue;
            List<DsonCodec.ModRef> mods = DsonCodec.readAppliedMods(raw);
            int steamCount = 0;
            JSONArray modArr = new JSONArray();
            for (DsonCodec.ModRef ref : mods) {
                if ("Steam".equalsIgnoreCase(ref.source)) steamCount++;
                try {
                    JSONObject obj = new JSONObject();
                    obj.put("name", ref.name);
                    obj.put("source", ref.source);
                    modArr.put(obj);
                } catch (Exception ignored) {
                }
            }
            try {
                JSONObject slotObj = new JSONObject();
                slotObj.put("slot", slot);
                slotObj.put("label", "档位 " + (i + 1));
                slotObj.put("modified", Files.getLastModifiedTime(gameFile).toMillis());
                slotObj.put("steamCount", steamCount);
                slotObj.put("mods", modArr);
                array.put(slotObj);
            } catch (Exception ignored) {
            }
        }
        return array.toString();
    }

    public static void setSlotMods(Context context, String slot, String modsJson) throws IOException {
        if (slot == null || !slot.matches("profile_[0-8]")) {
            throw new IOException("无效档位: " + slot);
        }
        List<DsonCodec.ModRef> next = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(modsJson == null ? "[]" : modsJson);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject item = arr.getJSONObject(i);
                String name = item.getString("name").trim();
                String source = item.optString("source", "mod").trim();
                if (name.isEmpty()) continue;
                if (source.isEmpty()) source = "mod";
                next.add(new DsonCodec.ModRef(name, source));
            }
        } catch (Exception error) {
            throw new IOException("模组配置格式错误: " + error.getMessage(), error);
        }
        setSlotModRefs(context, slot, next);
    }

    public static void setSlotModRefs(Context context, String slot, List<DsonCodec.ModRef> next) throws IOException {
        if (slot == null || !slot.matches("profile_[0-8]")) {
            throw new IOException("无效档位: " + slot);
        }
        NxProcessControl.stopGameProcess(context);
        Path saves = savesDir(context);
        Path profile = saves.resolve(slot);
        Path gameFile = profile.resolve("persist.game.json");
        if (!Files.isRegularFile(gameFile, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("该档位不存在有效存档");
        }
        synchronized (GameArchive.class) {
            SnapshotStore store = GameArchive.getStore(context);
            store.backup(profile);

            byte[] original = Files.readAllBytes(gameFile);
            byte[] updated = DsonCodec.writeAppliedMods(original, next);
            List<DsonCodec.ModRef> verified = DsonCodec.readAppliedMods(updated);
            if (!verified.equals(next)) {
                throw new IOException("DSON 校验失败，已中止写入");
            }
            Path temp = profile.resolve(".persist.game.json.tmp");
            try (FileOutputStream out = new FileOutputStream(temp.toFile())) {
                out.write(updated);
                out.getFD().sync();
            }
            Files.move(temp, gameFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            store.baseline(saves);
        }
        context.getContentResolver().notifyChange(GameArchive.VERSIONS, null);
    }

    public static void ensureModEnabledInSlot(Context context, String slot, String modId,
                                              boolean enable, boolean topPriority) throws IOException {
        Path gameFile = savesDir(context).resolve(slot).resolve("persist.game.json");
        if (!Files.isRegularFile(gameFile, LinkOption.NOFOLLOW_LINKS)) return;
        byte[] raw = Files.readAllBytes(gameFile);
        if (!DsonCodec.isDson(raw)) return;
        List<DsonCodec.ModRef> current = DsonCodec.readAppliedMods(raw);
        List<DsonCodec.ModRef> next = new ArrayList<>();
        for (DsonCodec.ModRef ref : current) {
            if (!modId.equals(ref.name)) {
                next.add(new DsonCodec.ModRef(ref.name, "mod"));
            }
        }
        if (enable) {
            DsonCodec.ModRef target = new DsonCodec.ModRef(modId, "mod");
            if (topPriority) next.add(0, target);
            else next.add(target);
        }
        if (!next.equals(current)) {
            setSlotModRefs(context, slot, next);
        }
    }

    private static void validateModId(String modId) throws IOException {
        if (modId == null || modId.isEmpty() || modId.startsWith(".")
                || modId.contains("/") || modId.contains("\\") || modId.contains("..")) {
            throw new IOException("非法的 Mod 目录名");
        }
    }

    private static String sanitizeModId(String raw) {
        if (raw == null) return "";
        String clean = raw.replaceAll("[^\\p{L}\\p{N}._ -]", "_").trim();
        while (clean.startsWith(".")) clean = clean.substring(1).trim();
        if (clean.equals("..") || clean.isEmpty()) return "";
        return clean;
    }

    private static String stripZip(String name) {
        if (name == null) return "mod";
        return name.toLowerCase(Locale.ROOT).endsWith(".zip") ? name.substring(0, name.length() - 4) : name;
    }

    private static boolean hasLegacyDir(File folder) {
        File[] children = folder.listFiles();
        if (children == null) return false;
        for (File child : children) {
            if (child.isDirectory() && LEGACY_DIRS.contains(child.getName().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static void findModRoots(Path dir, int depth, List<Path> out) throws IOException {
        if (depth > 4) return;
        File folder = dir.toFile();
        if (new File(folder, "project.xml").isFile() || hasLegacyDir(folder)) {
            out.add(dir);
            return;
        }
        File[] children = folder.listFiles();
        if (children == null) return;
        Arrays.sort(children, Comparator.comparing(File::getName));
        for (File child : children) {
            if (child.isDirectory() && !child.getName().startsWith(".") && !"__MACOSX".equals(child.getName())) {
                findModRoots(child.toPath(), depth + 1, out);
            }
        }
    }

    private static void unzipSafe(Path zipPath, Path dest) throws IOException {
        long totalBytes = 0;
        int totalFiles = 0;
        byte[] buffer = new byte[64 * 1024];
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(zipPath.toFile())))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String name = entry.getName().replace('\\', '/');
                if (name.startsWith("/") || name.contains("..")) {
                    throw new IOException("ZIP 包含非法路径: " + name);
                }
                Path target = dest.resolve(name).normalize();
                if (!target.startsWith(dest)) throw new IOException("ZIP 路径越界");
                if (++totalFiles > MAX_ZIP_FILES) throw new IOException("ZIP 文件数量超过上限");
                Files.createDirectories(target.getParent());
                try (FileOutputStream out = new FileOutputStream(target.toFile())) {
                    int n;
                    while ((n = zis.read(buffer)) > 0) {
                        totalBytes += n;
                        if (totalBytes > MAX_ZIP_BYTES) throw new IOException("ZIP 解压体积超过上限");
                        out.write(buffer, 0, n);
                    }
                }
            }
        }
    }

    public static void copyDirectory(Path src, Path dst) throws IOException {
        try (Stream<Path> tree = Files.walk(src)) {
            for (Path path : tree.collect(Collectors.toList())) {
                Path rel = src.relativize(path);
                Path target = dst.resolve(rel);
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                } else if (Files.isRegularFile(path)) {
                    Files.createDirectories(target.getParent());
                    Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static long[] folderStats(Path dir) {
        long files = 0;
        long bytes = 0;
        try (Stream<Path> tree = Files.walk(dir)) {
            for (Path path : tree.collect(Collectors.toList())) {
                if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    files++;
                    bytes += Files.size(path);
                }
            }
        } catch (IOException ignored) {
        }
        return new long[]{files, bytes};
    }

    private static String readXml(File file) {
        try {
            byte[] all = Files.readAllBytes(file.toPath());
            if (all.length >= 2 && (all[0] & 0xFF) == 0xFF && (all[1] & 0xFF) == 0xFE) {
                return new String(all, 2, all.length - 2, StandardCharsets.UTF_16LE);
            }
            if (all.length >= 2 && (all[0] & 0xFF) == 0xFE && (all[1] & 0xFF) == 0xFF) {
                return new String(all, 2, all.length - 2, StandardCharsets.UTF_16BE);
            }
            return new String(all, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            return "";
        }
    }

    private static String matchGroup(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        return m.find() ? m.group(1).trim() : "";
    }

    public static void deleteTree(Path root) {
        if (root == null || !Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        try (Stream<Path> tree = Files.walk(root)) {
            for (Path path : tree.sorted(Comparator.reverseOrder()).collect(Collectors.toList())) {
                Files.deleteIfExists(path);
            }
        } catch (IOException ignored) {
        }
    }
}
