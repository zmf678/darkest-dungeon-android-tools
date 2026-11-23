package dev.torcharchive.dd;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

final class ArchiveStore {
    static final String SAVE = "save", MOD = "mod";
    private static final String DIR = DocumentsContract.Document.MIME_TYPE_DIR;
    private static final String MOD_PARENT = "primary:Android/media/com.darkestdungeon";
    private static final String LIVE_MOD_PARENT = "primary:Android/media/" + LiveArchive.GAME;
    private static final long MAX_BYTES = 1024L * 1024 * 1024;
    private static final int MAX_FILES = 24000, MAX_DEPTH = 18;
    private static final Pattern SLOT = Pattern.compile("profile_[0-8]");
    private static final Pattern TITLE = Pattern.compile("(?is)<(?:title|mod_title)>\\s*(?:<!\\[CDATA\\[)?([^<]+)");
    private static final Set<String> LEGACY = Set.of("heroes", "hero", "trinkets", "localization", "dungeons",
            "campaign", "monsters", "raid", "quirks", "effects", "buffs", "overlays", "panels", "upgrades",
            "audio", "maps", "shared", "dlc", "scripts", "inventory", "curios", "fonts", "ui");
    private static final String[] DOCUMENT_COLUMNS = {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_LAST_MODIFIED
    };

    record Entry(String id, String kind, String title, String source, long date, long bytes, int count) {}
    record Doc(Uri uri, String id, String name, boolean directory, long modified) {}
    record Installed(Doc folder, String title, String format) {}
    record ModFormat(int roots, boolean legacy, String title) {}
    record Slot(String name, int files, String change) {}

    private final File library, cache;
    private final ContentResolver resolver;
    private final LiveArchive live;

    ArchiveStore(Context context) {
        library = new File(context.getFilesDir(), "archives");
        cache = new File(context.getCacheDir(), "archive-work");
        resolver = context.getContentResolver();
        live = new LiveArchive(context);
    }

    List<Entry> list(String kind) {
        List<Entry> entries = new ArrayList<>();
        for (File folder : files(library)) {
            if (!folder.isDirectory() || folder.getName().startsWith(".")) continue;
            try {
                Entry entry = read(folder);
                if (entry.kind.equals(kind) && new File(folder, "payload.zip").isFile()) entries.add(entry);
            } catch (Exception ignored) {
                // An interrupted or damaged entry must not hide intact archives.
            }
        }
        entries.sort(Comparator.comparingLong(Entry::date).reversed());
        return entries;
    }

    Entry importSave(Uri uri, boolean tree) throws Exception {
        File job = temporary();
        try {
            File input = new File(job, "input");
            mkdir(input);
            if (tree) copyTree(asTree(uri), input, new Budget(), 0);
            else unzip(openIn(uri), input);
            List<File> slots = findSlots(input, 0);
            if (slots.isEmpty()) throw new IOException("找不到有效存档。请选择包含 persist.game.json 的档位，或游戏导出的存档目录。");
            return publish(SAVE, "备份 " + stamp("MM-dd HH:mm:ss"), displayName(uri, tree), slots.size(), out -> {
                try (ZipOutputStream zip = new ZipOutputStream(out)) {
                    for (File slot : slots) {
                        String name = SLOT.matcher(slot.getName()).matches() ? slot.getName() : "profile_0";
                        zipFolder(zip, slot, name);
                    }
                }
            });
        } finally {
            deleteTree(job);
        }
    }

    Entry importMod(Uri uri) throws Exception {
        File job = temporary();
        try {
            File archive = new File(job, "source.zip");
            try (InputStream in = openIn(uri); OutputStream out = new FileOutputStream(archive)) {
                copy(in, out, new Budget());
            }
            File input = new File(job, "input");
            mkdir(input);
            unzip(new FileInputStream(archive), input);
            String source = displayName(uri, false);
            ModFormat format = inspectMod(input, 0);
            if (format.roots == 0) throw new IOException("ZIP 里找不到 project.xml 或游戏支持的旧版模组目录。");
            return publish(MOD, format.roots == 1 && !format.title.trim().isEmpty() ? format.title : stripZip(source),
                    source + (format.legacy ? " · 含旧版目录" : ""), format.roots, out -> {
                        try (InputStream in = new FileInputStream(archive)) { copy(in, out, null); }
                    });
        } finally {
            deleteTree(job);
        }
    }

    Entry archiveInstalled(Installed mod) throws Exception {
        File job = temporary();
        try {
            File input = new File(job, component(mod.folder.name));
            mkdir(input);
            copyTree(mod.folder, input, new Budget(), 0);
            return publish(MOD, mod.title, "游戏目录 · " + mod.folder.name, 1, out -> {
                try (ZipOutputStream zip = new ZipOutputStream(out)) { zipFolder(zip, input, input.getName()); }
            });
        } finally {
            deleteTree(job);
        }
    }

    void rename(Entry entry, String title) throws Exception {
        if (title.trim().isEmpty()) throw new IOException("请输入档案名称。");
        File folder = entryFolder(entry.id);
        if (!folder.isDirectory()) throw new IOException("找不到这份档案。");
        writeMeta(folder, new Entry(entry.id, entry.kind, title.trim(), entry.source, entry.date, entry.bytes, entry.count));
    }

    void remove(Entry entry) throws IOException {
        File folder = entryFolder(entry.id);
        if (!folder.exists()) return;
        File trash = new File(library, "." + entry.id + ".deleted");
        Files.move(folder.toPath(), trash.toPath());
        deleteTree(trash);
    }

    List<Slot> slots(Entry entry, Entry previous) throws IOException {
        Map<String, Map<String, String>> current = slotIndex(entry);
        Map<String, Map<String, String>> older = previous == null ? new HashMap<>() : slotIndex(previous);
        List<Slot> result = new ArrayList<>();
        for (Map.Entry<String, Map<String, String>> slot : current.entrySet()) {
            Map<String, String> baseline = older.get(slot.getKey());
            String change = baseline == null ? "新增" : baseline.equals(slot.getValue()) ? "无变化" : "有变化";
            result.add(new Slot(slot.getKey(), slot.getValue().size(), change));
        }
        return result;
    }

    private Map<String, Map<String, String>> slotIndex(Entry entry) throws IOException {
        if (!LiveArchive.isAutomatic(entry)) return slotIndex(payload(entry));
        File job = temporary();
        try {
            File archive = new File(job, "snapshot.zip");
            try (InputStream in = live.open(entry); OutputStream out = new FileOutputStream(archive)) {
                copy(in, out, new Budget());
            }
            return slotIndex(archive);
        } finally {
            deleteTree(job);
        }
    }

    private Map<String, Map<String, String>> slotIndex(File payload) throws IOException {
        Map<String, Map<String, String>> slots = new TreeMap<>();
        try (ZipFile zip = new ZipFile(payload)) {
            var files = zip.entries();
            while (files.hasMoreElements()) {
                ZipEntry file = files.nextElement();
                int slash = file.getName().indexOf('/');
                if (file.isDirectory() || slash < 0) continue;
                String name = file.getName().substring(0, slash);
                if (SLOT.matcher(name).matches()) {
                    slots.computeIfAbsent(name, ignored -> new HashMap<>()).put(file.getName(), file.getSize() + ":" + file.getCrc());
                }
            }
        }
        return slots;
    }

    String exportSaves(Entry entry, Uri tree, String slot) throws Exception {
        File job = temporary();
        Uri created = null;
        try {
            unzip(new FileInputStream(payload(entry)), job);
            Doc root = asTree(tree);
            String name = "DarkestDungeon saves " + stamp("yyyy-MM-dd HHmmss");
            created = create(root.uri, DIR, name);
            if (slot == null) {
                for (File child : files(job)) copyToDocument(created, child);
            } else {
                File selected = new File(job, component(slot));
                if (!SLOT.matcher(slot).matches() || !selected.isDirectory()) throw new IOException("该版本没有这个档位。");
                copyToDocument(created, selected);
            }
            return displayName(created, false);
        } catch (Exception error) {
            cleanupDocument(created);
            throw error;
        } finally {
            deleteTree(job);
        }
    }

    void exportZip(Entry entry, Uri output) throws IOException {
        try (InputStream in = LiveArchive.isAutomatic(entry) ? live.open(entry) : new FileInputStream(payload(entry));
             OutputStream out = openOut(output)) {
            copy(in, out, null);
        } catch (IOException | RuntimeException error) {
            cleanupDocument(output);
            throw error;
        }
    }

    List<Installed> installed(Uri tree) throws Exception {
        Doc root = asTree(tree);
        if (!"com.android.externalstorage.documents".equals(tree.getAuthority())) {
            throw new IOException("请选择内部存储里的游戏模组目录。");
        }
        if (MOD_PARENT.equals(root.id) || LIVE_MOD_PARENT.equals(root.id)) {
            root = children(root).stream().filter(doc -> doc.directory && "mods".equals(doc.name)).findFirst()
                    .orElseThrow(() -> new IOException("还没有 mods 文件夹。请先打开一次游戏再连接。"));
        }
        if (!root.id.equals(MOD_PARENT + "/mods") && !root.id.equals(LIVE_MOD_PARENT + "/mods")) {
            throw new IOException("请选择原版或实验版游戏在 Android/media 下的 mods 目录。");
        }
        List<Installed> installed = new ArrayList<>();
        for (Doc folder : children(root)) {
            if (!folder.directory || folder.name.startsWith(".")) continue;
            List<Doc> contents = children(folder);
            Doc manifest = contents.stream().filter(doc -> !doc.directory && "project.xml".equalsIgnoreCase(doc.name))
                    .findFirst().orElse(null);
            if (manifest == null && contents.stream().noneMatch(doc -> doc.directory && LEGACY.contains(doc.name))) continue;
            String title = folder.name;
            if (manifest != null) {
                try (InputStream in = openIn(manifest.uri)) {
                    String parsed = modTitle(in);
                    if (!parsed.trim().isEmpty()) title = parsed;
                }
            }
            installed.add(new Installed(folder, title, manifest == null ? "旧版目录" : "project.xml"));
        }
        installed.sort(Comparator.comparing(Installed::title, String.CASE_INSENSITIVE_ORDER));
        return installed;
    }

    String zipName(Entry entry) {
        String clean = entry.title.replaceAll("[^\\p{L}\\p{N} ._()-]", "-").trim();
        if (clean.isEmpty()) clean = entry.kind.equals(SAVE) ? "DarkestDungeon saves" : "DarkestDungeon mod";
        return clean + (entry.kind.equals(SAVE) ? " " + stamp("yyyyMMdd-HHmmss") : "") + ".zip";
    }

    static Uri modPickerLocation(boolean experimental) {
        return DocumentsContract.buildDocumentUri("com.android.externalstorage.documents",
                (experimental ? LIVE_MOD_PARENT : MOD_PARENT) + "/mods");
    }

    private Entry publish(String kind, String title, String source, int count, PayloadWriter writer) throws Exception {
        mkdir(library);
        String id = UUID.randomUUID().toString();
        File staging = new File(library, "." + id);
        mkdir(staging);
        try {
            File zip = new File(staging, "payload.zip");
            try (OutputStream out = new BufferedOutputStream(new FileOutputStream(zip))) { writer.write(out); }
            Entry entry = new Entry(id, kind, title, source, System.currentTimeMillis(), zip.length(), count);
            writeMeta(staging, entry);
            Files.move(staging.toPath(), entryFolder(id).toPath(), StandardCopyOption.ATOMIC_MOVE);
            return entry;
        } finally {
            deleteTree(staging);
        }
    }

    private interface PayloadWriter { void write(OutputStream out) throws IOException; }

    private Entry read(File folder) throws Exception {
        JSONObject json = new JSONObject(new String(Files.readAllBytes(new File(folder, "entry.json").toPath()), StandardCharsets.UTF_8));
        return new Entry(folder.getName(), json.getString("kind"), json.getString("title"),
                json.getString("source"), json.getLong("date"), json.getLong("bytes"), json.getInt("count"));
    }

    private void writeMeta(File folder, Entry entry) throws Exception {
        JSONObject json = new JSONObject().put("kind", entry.kind).put("title", entry.title).put("source", entry.source)
                .put("date", entry.date).put("bytes", entry.bytes).put("count", entry.count);
        File staging = new File(folder, "entry.new");
        Files.write(staging.toPath(), json.toString().getBytes(StandardCharsets.UTF_8));
        Files.move(staging.toPath(), new File(folder, "entry.json").toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private File payload(Entry entry) throws IOException {
        File zip = new File(entryFolder(entry.id), "payload.zip");
        if (!zip.isFile()) throw new IOException("找不到这份档案的数据。");
        return zip;
    }

    private File entryFolder(String id) {
        return new File(library, UUID.fromString(id).toString());
    }

    private File temporary() throws IOException {
        mkdir(cache);
        return Files.createTempDirectory(cache.toPath(), "job-").toFile();
    }

    private static List<File> findSlots(File dir, int depth) {
        if (new File(dir, "persist.game.json").isFile()) return List.of(dir);
        if (depth > 3) return List.of();
        List<File> result = new ArrayList<>();
        for (File child : files(dir)) {
            if (child.isDirectory() && SLOT.matcher(child.getName()).matches()
                    && new File(child, "persist.game.json").isFile()) result.add(child);
        }
        if (!result.isEmpty()) return result;
        for (File child : files(dir)) {
            if (!child.isDirectory() || child.getName().equals("replaced") || child.getName().startsWith(".")) continue;
            List<File> next = findSlots(child, depth + 1);
            if (!next.isEmpty()) return next;
        }
        return result;
    }

    private static ModFormat inspectMod(File dir, int depth) throws IOException {
        if (depth > 4) return new ModFormat(0, false, "");
        File[] children = files(dir);
        for (File child : children) {
            if (child.isFile() && child.getName().equalsIgnoreCase("project.xml")) {
                try (InputStream in = new FileInputStream(child)) {
                    return new ModFormat(1, false, modTitle(in));
                }
            }
        }
        for (File child : children) {
            if (child.isDirectory() && LEGACY.contains(child.getName().toLowerCase(Locale.ROOT))) {
                return new ModFormat(1, true, "");
            }
        }
        int count = 0;
        boolean legacy = false;
        String title = "";
        for (File child : children) {
            if (!child.isDirectory() || child.getName().startsWith(".") || child.getName().equals("__MACOSX")) continue;
            ModFormat found = inspectMod(child, depth + 1);
            if (found.roots > 0) title = found.title;
            count += found.roots;
            legacy |= found.legacy;
        }
        return new ModFormat(count, legacy, title);
    }

    private static String modTitle(InputStream in) throws IOException {
        byte[] buffer = new byte[32768];
        int length = 0, amount;
        while (length < buffer.length && (amount = in.read(buffer, length, buffer.length - length)) > 0) length += amount;
        boolean utf16 = length >= 2 && (((buffer[0] & 255) == 255 && (buffer[1] & 255) == 254)
                || ((buffer[0] & 255) == 254 && (buffer[1] & 255) == 255));
        String text = new String(buffer, 0, length, utf16 ? StandardCharsets.UTF_16 : StandardCharsets.UTF_8);
        Matcher match = TITLE.matcher(text);
        if (!match.find()) return "";
        String name = match.group(1).replace("]]>", "").replace("&amp;", "&").replace("&quot;", "\"").trim();
        return name.substring(0, Math.min(name.length(), 120));
    }

    private static String stripZip(String name) {
        if (name.toLowerCase(Locale.ROOT).endsWith(".zip")) return name.substring(0, name.length() - 4);
        return name;
    }

    private InputStream openIn(Uri uri) throws IOException {
        InputStream in = resolver.openInputStream(uri);
        if (in == null) throw new IOException("无法读取所选文件。请重新选择。");
        return in;
    }

    private OutputStream openOut(Uri uri) throws IOException {
        OutputStream out = resolver.openOutputStream(uri, "wt");
        if (out == null) throw new IOException("无法写入所选位置。");
        return out;
    }

    private String displayName(Uri uri, boolean tree) {
        Uri doc = tree ? asTree(uri).uri : uri;
        try (Cursor cursor = resolver.query(doc, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && cursor.getString(0) != null) return cursor.getString(0);
        }
        return "所选文件";
    }

    private static Doc asTree(Uri uri) {
        String id = DocumentsContract.getTreeDocumentId(uri);
        return new Doc(DocumentsContract.buildDocumentUriUsingTree(uri, id), id, id, true, 0);
    }

    private List<Doc> children(Doc root) throws IOException {
        Uri query = DocumentsContract.buildChildDocumentsUriUsingTree(root.uri, root.id);
        List<Doc> result = new ArrayList<>();
        try (Cursor cursor = resolver.query(query, DOCUMENT_COLUMNS, null, null, null)) {
            if (cursor == null) throw new IOException("目录无法读取。请重新授权该文件夹。");
            while (cursor.moveToNext()) {
                String id = cursor.getString(0);
                result.add(new Doc(DocumentsContract.buildDocumentUriUsingTree(root.uri, id), id,
                        cursor.getString(1), DIR.equals(cursor.getString(2)), cursor.getLong(3)));
                if (result.size() > MAX_FILES) throw new IOException("文件数量超过支持范围。");
            }
        }
        return result;
    }

    private void copyTree(Doc doc, File target, Budget budget, int depth) throws IOException {
        if (depth > MAX_DEPTH) throw new IOException("文件夹嵌套层级过深。");
        for (Doc child : children(doc)) {
            budget.addEntry();
            File output = new File(target, component(child.name));
            if (output.exists()) throw new IOException("目录包含重名文件。");
            if (child.directory) {
                mkdir(output);
                copyTree(child, output, budget, depth + 1);
            } else try (InputStream in = openIn(child.uri); OutputStream out = new FileOutputStream(output)) {
                copy(in, out, budget);
            }
        }
    }

    private Uri create(Uri parent, String mime, String name) throws IOException {
        Uri created = DocumentsContract.createDocument(resolver, parent, mime, name);
        if (created == null) throw new IOException("无法在所选位置创建文件。请检查写入权限。");
        return created;
    }

    private void copyToDocument(Uri parent, File file) throws IOException {
        String name = file.getName();
        Uri item = create(parent, file.isDirectory() ? DIR : fileMime(name), name);
        if (!name.equals(displayName(item, false))) throw new IOException("所选位置改写了存档文件名。请选择内部存储里的普通文件夹。");
        if (file.isDirectory()) {
            for (File child : files(file)) copyToDocument(item, child);
        } else try (InputStream in = new FileInputStream(file); OutputStream out = openOut(item)) {
            copy(in, out, null);
        }
    }

    private static String fileMime(String name) {
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        String type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        return type == null ? "application/octet-stream" : type;
    }

    private void cleanupDocument(Uri doc) {
        if (doc == null) return;
        try { DocumentsContract.deleteDocument(resolver, doc); } catch (Exception ignored) {}
    }

    private static void unzip(InputStream source, File folder) throws IOException {
        Budget budget = new Budget();
        Set<String> seen = new HashSet<>();
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(source))) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; zip.closeEntry()) {
                budget.addEntry();
                String name = entry.isDirectory() ? entry.getName().replaceFirst("/$", "") : entry.getName();
                String[] parts = name.split("/", -1);
                if (parts.length > MAX_DEPTH || !seen.add(name)) throw new IOException("ZIP 有重名文件或嵌套层级过深。");
                File target = folder;
                for (String part : parts) target = new File(target, component(part));
                if (entry.isDirectory()) {
                    mkdir(target);
                } else {
                    if (target.exists()) throw new IOException("ZIP 包含冲突的文件名。");
                    mkdir(target.getParentFile());
                    try (OutputStream out = new BufferedOutputStream(new FileOutputStream(target))) { copy(zip, out, budget); }
                }
            }
        }
        if (seen.isEmpty()) throw new IOException("所选文件不是可读取的 ZIP，或压缩包为空。");
    }

    private static String component(String name) throws IOException {
        if (name == null || name.trim().isEmpty() || name.equals(".") || name.equals("..") || name.indexOf('/') >= 0
                || name.indexOf('\\') >= 0 || name.indexOf(':') >= 0 || name.indexOf(0) >= 0) {
            throw new IOException("发现不安全的文件路径，已取消导入。");
        }
        return name;
    }

    private static void zipFolder(ZipOutputStream zip, File folder, String path) throws IOException {
        for (File child : files(folder)) {
            String name = path + "/" + child.getName();
            if (child.isDirectory()) {
                zip.putNextEntry(new ZipEntry(name + "/"));
                zip.closeEntry();
                zipFolder(zip, child, name);
            } else {
                zip.putNextEntry(new ZipEntry(name));
                try (InputStream in = new FileInputStream(child)) { copy(in, zip, null); }
                zip.closeEntry();
            }
        }
    }

    private static void copy(InputStream in, OutputStream out, Budget budget) throws IOException {
        byte[] buffer = new byte[65536];
        for (int count; (count = in.read(buffer)) != -1;) {
            if (budget != null) budget.addBytes(count);
            out.write(buffer, 0, count);
        }
    }

    private static File[] files(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return new File[0];
        Arrays.sort(files, Comparator.comparing(File::getName));
        return files;
    }

    private static void mkdir(File dir) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("无法创建档案目录。");
    }

    private static void deleteTree(File dir) {
        if (dir.isDirectory()) for (File file : files(dir)) deleteTree(file);
        dir.delete();
    }

    private static String stamp(String format) {
        return new SimpleDateFormat(format, Locale.ROOT).format(new Date());
    }

    private static final class Budget {
        private long bytes;
        private int count;

        void addEntry() throws IOException {
            if (++count > MAX_FILES) throw new IOException("文件数量超过支持范围。");
        }

        void addBytes(int size) throws IOException {
            bytes += size;
            if (bytes > MAX_BYTES) throw new IOException("文件总大小超过 1 GB。");
        }
    }
}
