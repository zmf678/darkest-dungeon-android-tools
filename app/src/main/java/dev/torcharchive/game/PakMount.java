package dev.torcharchive.game;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * External dd_assets.pak resolver, inspector, and atomic importer for A2-2 thin-shell mode.
 * Also supports A1 bundled APK fallback seamlessly.
 */
public final class PakMount {
    private static final String TAG = "TorchArchive";
    public static final String PAK_NAME = "dd_assets.pak";
    private static final String KEY_CUSTOM_PAK = "custom_pak_path";
    private static final String EXES_ENTRY = "assets/nx/exefs/main";
    private static final String ROMFS_FILELIST = "assets/nx/romfs/filelist.csv";
    private static final String MANIFEST_ENTRY = "assets/nx/pak-manifest.json";

    public static final class PakInfo {
        public final String mode; // A1_BUNDLED, A2_EXTERNAL_PAK, UNPACKED_DIR, MISSING
        public final boolean ready;
        public final String path;
        public final long bytes;
        public final int fileCount;
        public final String converterVersion;
        public final String builtAt;
        public final List<String> dlcs;
        public final String detail;

        PakInfo(String mode, boolean ready, String path, long bytes, int fileCount,
                String converterVersion, String builtAt, List<String> dlcs, String detail) {
            this.mode = mode;
            this.ready = ready;
            this.path = path;
            this.bytes = bytes;
            this.fileCount = fileCount;
            this.converterVersion = converterVersion;
            this.builtAt = builtAt;
            this.dlcs = dlcs;
            this.detail = detail;
        }
    }

    public interface ProgressCallback {
        void onProgress(long copiedBytes, long totalBytes);
    }

    private PakMount() {}

    public static File mediaRoot(Context context) throws IOException {
        File media = null;
        try {
            @SuppressWarnings("deprecation")
            File[] dirs = context.getExternalMediaDirs();
            if (dirs != null && dirs.length > 0) media = dirs[0];
        } catch (Throwable ignored) {
        }
        if (media == null) {
            File ext = context.getExternalFilesDir(null);
            String suffix = "/Android/data/" + context.getPackageName() + "/files";
            if (ext != null && ext.getAbsolutePath().endsWith(suffix)) {
                String root = ext.getAbsolutePath();
                media = new File(root.substring(0, root.length() - suffix.length())
                        + "/Android/media/" + context.getPackageName());
            }
        }
        if (media == null) throw new IOException("无法定位 Android/media/" + context.getPackageName() + " 目录");
        if (!media.isDirectory() && !media.mkdirs()) {
            throw new IOException("无法创建数据包目录: " + media.getAbsolutePath());
        }
        return media;
    }

    public static File defaultPakFile(Context context) throws IOException {
        return new File(mediaRoot(context), PAK_NAME);
    }

    /**
     * Called directly from NxActivity.onCreate() before NxActivity.nxApk(zipPath, nxDir).
     */
    public static String resolvePakPath(Context context) {
        String apkPath = context.getApplicationInfo().sourceDir;
        if (hasBundledNx(apkPath)) {
            Log.i(TAG, "PakMount: using A1 bundled APK assets");
            return apkPath;
        }
        String custom = GameArchive.state(context).getString(KEY_CUSTOM_PAK, "");
        if (!custom.isEmpty()) {
            File f = new File(custom);
            if (isValidPakFile(f)) {
                Log.i(TAG, "PakMount: mounting custom pak " + f.getAbsolutePath());
                return f.getAbsolutePath();
            }
        }
        try {
            File def = defaultPakFile(context);
            if (isValidPakFile(def)) {
                Log.i(TAG, "PakMount: mounting external pak " + def.getAbsolutePath());
                return def.getAbsolutePath();
            }
        } catch (Throwable err) {
            Log.w(TAG, "PakMount: defaultPakFile lookup failed", err);
        }
        Log.w(TAG, "PakMount: no valid external dd_assets.pak found, falling back to APK path");
        return apkPath;
    }

    public static PakInfo inspect(Context context) {
        String apkPath = context.getApplicationInfo().sourceDir;
        if (hasBundledNx(apkPath)) {
            PakInfo bundled = inspectZip(new File(apkPath), "A1_BUNDLED");
            if (bundled.ready) return bundled;
        }
        String custom = GameArchive.state(context).getString(KEY_CUSTOM_PAK, "");
        if (!custom.isEmpty()) {
            File customFile = new File(custom);
            if (customFile.isFile()) {
                PakInfo info = inspectZip(customFile, "A2_EXTERNAL_PAK");
                if (info.ready) return info;
            }
        }
        File def;
        try {
            def = defaultPakFile(context);
        } catch (IOException err) {
            return new PakInfo("MISSING", false, "", 0, 0, "", "", Collections.emptyList(), err.getMessage());
        }
        if (def.isFile()) {
            return inspectZip(def, "A2_EXTERNAL_PAK");
        }
        File unpacked = new File(def.getParentFile(), "nx");
        if (new File(unpacked, "exefs/main").isFile() && new File(unpacked, "romfs/filelist.csv").isFile()) {
            return new PakInfo("UNPACKED_DIR", true, unpacked.getAbsolutePath(), 0, 0,
                    "unpacked", "", Collections.emptyList(), "已检测到解包目录模式 Android/media/.../nx/");
        }
        return new PakInfo("MISSING", false, def.getAbsolutePath(), 0, 0, "", "",
                Collections.emptyList(), "未找到数据包。请将 PC 生成的 dd_assets.pak 导入或推送到：" + def.getAbsolutePath());
    }

    public static PakInfo importPak(Context context, InputStream in, long expectedBytes, ProgressCallback cb) throws IOException {
        File media = mediaRoot(context);
        File target = new File(media, PAK_NAME);
        File temp = new File(media, "." + PAK_NAME + ".tmp");
        try {
            byte[] buf = new byte[256 * 1024];
            long copied = 0;
            try (FileOutputStream out = new FileOutputStream(temp)) {
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    copied += n;
                    if (cb != null) cb.onProgress(copied, expectedBytes);
                }
                out.getFD().sync();
            }
            PakInfo check = inspectZip(temp, "A2_EXTERNAL_PAK");
            if (!check.ready) {
                throw new IOException("无效的数据包文件：" + check.detail);
            }
            NxProcessControl.stopGameProcess(context);
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            GameArchive.state(context).edit().remove(KEY_CUSTOM_PAK).apply();
            return inspect(context);
        } finally {
            Files.deleteIfExists(temp.toPath());
        }
    }

    private static boolean hasBundledNx(String apkPath) {
        if (apkPath == null || apkPath.isEmpty()) return false;
        File f = new File(apkPath);
        if (!f.isFile()) return false;
        try (ZipFile zip = new ZipFile(f)) {
            return zip.getEntry(EXES_ENTRY) != null && zip.getEntry(ROMFS_FILELIST) != null;
        } catch (IOException ignored) {
            return false;
        }
    }

    private static boolean isValidPakFile(File f) {
        if (f == null || !f.isFile() || f.length() < 1024 * 1024) return false;
        try (ZipFile zip = new ZipFile(f)) {
            return zip.getEntry(EXES_ENTRY) != null && zip.getEntry(ROMFS_FILELIST) != null;
        } catch (IOException ignored) {
            return false;
        }
    }

    private static PakInfo inspectZip(File file, String mode) {
        try (ZipFile zip = new ZipFile(file)) {
            ZipEntry exes = zip.getEntry(EXES_ENTRY);
            ZipEntry fl = zip.getEntry(ROMFS_FILELIST);
            if (exes == null || fl == null) {
                return new PakInfo("MISSING", false, file.getAbsolutePath(), file.length(), 0,
                        "", "", Collections.emptyList(), "缺少 assets/nx/exefs/main 或 romfs/filelist.csv");
            }
            int nxCount = 0;
            Enumeration<? extends ZipEntry> all = zip.entries();
            while (all.hasMoreElements()) {
                ZipEntry entry = all.nextElement();
                if (!entry.isDirectory() && entry.getName().startsWith("assets/nx/")) nxCount++;
            }
            String ver = "";
            String builtAt = "";
            List<String> dlcs = new ArrayList<>();
            ZipEntry manifest = zip.getEntry(MANIFEST_ENTRY);
            if (manifest != null) {
                try (InputStream is = zip.getInputStream(manifest)) {
                    java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                    byte[] tmp = new byte[4096];
                    int read;
                    while ((read = is.read(tmp)) > 0) baos.write(tmp, 0, read);
                    JSONObject obj = new JSONObject(baos.toString(StandardCharsets.UTF_8.name()));
                    ver = obj.optString("converter_version", "");
                    builtAt = obj.optString("built_at", "");
                    JSONArray dlcArr = obj.optJSONArray("dlcs");
                    if (dlcArr != null) {
                        for (int i = 0; i < dlcArr.length(); i++) {
                            dlcs.add(dlcArr.getString(i));
                        }
                    }
                } catch (Exception ignored) {
                }
            } else {
                for (String known : new String[]{
                        "dlc_the_crimson_court", "dlc_the_color_of_madness",
                        "dlc_the_shieldbreaker", "dlc_the_musketeer", "dlc_the_fire_s_edge"}) {
                    if (zip.getEntry("assets/nx/aoc/" + known + "/dlc_info.csv") != null) {
                        dlcs.add(known);
                    }
                }
            }
            String detail = "A1_BUNDLED".equals(mode)
                    ? "单文件完整模式（内置资源 " + nxCount + " 项）"
                    : "外置单包已挂载（" + nxCount + " 项资源）";
            return new PakInfo(mode, true, file.getAbsolutePath(), file.length(), nxCount, ver, builtAt, dlcs, detail);
        } catch (Exception error) {
            return new PakInfo("MISSING", false, file.getAbsolutePath(), file.length(), 0,
                    "", "", Collections.emptyList(), "数据包损坏或无法读取：" + error.getMessage());
        }
    }
}
