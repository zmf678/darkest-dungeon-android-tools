package dev.torcharchive.dd;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static dev.torcharchive.dd.MainActivity.*;

final class LauncherUpdates {
    private static final String REPO = "zmf678/darkest-dungeon-android-tools";
    private static final String RELEASES = "https://github.com/" + REPO + "/releases";
    private static final String API = "https://api.github.com/repos/" + REPO + "/releases?per_page=30";
    private static final long CHECK_INTERVAL = 24L * 60 * 60 * 1000;
    private static final Pattern VERSION = Pattern.compile("v?(\\d{1,5})\\.(\\d{1,5})(?:\\.(\\d{1,5}))?");
    private static final Pattern APK_NAME = Pattern.compile("TorchLauncher-v([0-9.]+)(?:-arm64(?:-v8a)?)?\\.apk");

    private final MainActivity host;
    private final SharedPreferences prefs;
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final String installedName;
    private final Version installed;
    private LinearLayout card;
    private Release release;
    private boolean automaticAttempted, checking, closed;
    private String failure = "";

    record Version(int major, int minor, int patch) implements Comparable<Version> {
        static Version parse(String value) {
            Matcher matcher = VERSION.matcher(value);
            if (!matcher.matches()) return null;
            return new Version(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)),
                    matcher.group(3) == null ? 0 : Integer.parseInt(matcher.group(3)));
        }

        @Override public int compareTo(Version other) {
            int result = Integer.compare(major, other.major);
            if (result != 0) return result;
            result = Integer.compare(minor, other.minor);
            return result != 0 ? result : Integer.compare(patch, other.patch);
        }
    }

    record Release(String tag, Version version, String filename, long bytes) {
        String downloadUrl() { return RELEASES + "/download/" + tag + "/" + filename; }
        String notesUrl() { return RELEASES + "/tag/" + tag; }
    }

    LauncherUpdates(MainActivity host) {
        this.host = host;
        prefs = host.getSharedPreferences("launcher-updates", Context.MODE_PRIVATE);
        installedName = installedVersion(host);
        installed = Version.parse(installedName);
        if (installedName.equals(prefs.getString("installed", ""))) {
            release = candidate(prefs.getString("tag", ""), prefs.getString("file", ""), prefs.getLong("bytes", 0));
        }
    }

    private static String installedVersion(Context context) {
        try {
            String name = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
            return name == null ? "" : name;
        } catch (PackageManager.NameNotFoundException ignored) {
            return "";
        }
    }

    void checkOnStart() {
        if (automaticAttempted) return;
        automaticAttempted = true;
        long age = System.currentTimeMillis() - prefs.getLong("checked", 0);
        if (release == null || age >= CHECK_INTERVAL || age < 0) check();
    }

    void close() {
        closed = true;
        network.shutdownNow();
    }

    View build() {
        card = host.vertical();
        card.setPadding(host.dp(15), host.dp(13), host.dp(15), host.dp(13));
        card.setBackground(host.surface(PANEL, LINE, 13));
        render();
        return card;
    }

    private void render() {
        if (card == null) return;
        card.removeAllViews();
        boolean newer = installed != null && release != null && release.version.compareTo(installed) > 0;
        LinearLayout row = host.horizontal();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(host.glyph("export", GOLD), new LinearLayout.LayoutParams(host.dp(22), host.dp(22)));
        LinearLayout words = host.vertical();
        LinearLayout.LayoutParams text = new LinearLayout.LayoutParams(0, -2, 1);
        text.setMarginStart(host.dp(10));
        text.setMarginEnd(host.dp(8));
        row.addView(words, text);
        words.addView(host.type("启动器更新", 16, INK, Typeface.DEFAULT_BOLD));
        String current = installedName.isEmpty() ? "当前版本未知" : "当前 v" + installedName;
        if (newer) current += " · 新版 " + release.tag;
        else if (installed != null && release != null && !checking && failure.isEmpty()) current += " · 已是最新版";
        host.add(words, host.type(current, 13, newer ? GOLD : MUTED, Typeface.DEFAULT), 3);
        TextView check = host.button(checking ? "检查中…" : "检查更新", false, this::check);
        check.setEnabled(!checking);
        check.setAlpha(checking ? .6f : 1f);
        row.addView(check, new LinearLayout.LayoutParams(-2, -2));
        card.addView(row);

        if (newer) {
            Release available = release;
            LinearLayout actions = host.horizontal();
            host.weighted(actions, host.button("下载 " + available.tag + " · " + size(available.bytes), true,
                    () -> host.openExternalPage(available.downloadUrl())), true);
            host.weighted(actions, host.button("版本说明", false, () -> host.openExternalPage(available.notesUrl())), false);
            host.add(card, actions, 10);
            host.add(card, host.type("用浏览器下载 APK，下载完成后按系统提示覆盖安装。", 12, MUTED, Typeface.DEFAULT), 6);
        }
        if (!failure.isEmpty()) {
            TextView error = host.type(failure, 13, ERROR, Typeface.DEFAULT);
            error.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            host.add(card, error, 7);
            host.add(card, host.button("查看 GitHub 发行版", false, () -> host.openExternalPage(RELEASES)), 8);
        }
    }

    private void check() {
        if (checking || closed) return;
        checking = true;
        failure = "";
        render();
        network.execute(() -> {
            try {
                Release latest = fetch();
                host.runOnUiThread(() -> finish(latest, ""));
            } catch (Exception error) {
                String message = error instanceof SocketTimeoutException ? "连接 GitHub 超时，稍后可重试。"
                        : error instanceof IOException && error.getMessage() != null ? error.getMessage()
                        : "暂时无法读取 GitHub 发行信息，请重试。";
                host.runOnUiThread(() -> finish(null, message));
            }
        });
    }

    private void finish(Release latest, String error) {
        if (closed) return;
        checking = false;
        failure = error;
        if (latest != null) {
            release = latest;
            prefs.edit().putString("installed", installedName).putString("tag", latest.tag)
                    .putString("file", latest.filename).putLong("bytes", latest.bytes)
                    .putLong("checked", System.currentTimeMillis()).apply();
        }
        render();
    }

    private static Release fetch() throws IOException, JSONException {
        HttpURLConnection connection = (HttpURLConnection) new URL(API).openConnection();
        try {
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(15000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", "TorchArchive-Android");
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setRequestProperty("X-GitHub-Api-Version", "2026-03-10");
            int status = connection.getResponseCode();
            if (status == 403 || status == 429) throw new IOException("GitHub 暂时限制查询，稍后可重试。");
            if (status != 200) throw new IOException("GitHub 查询失败（HTTP " + status + "）。");
            try (InputStream in = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[16 * 1024];
                for (int count; (count = in.read(buffer)) != -1; ) {
                    if (out.size() + count > 2 * 1024 * 1024) throw new IOException("GitHub 发行信息过大。");
                    out.write(buffer, 0, count);
                }
                Release latest = latestAndroid(new JSONArray(new String(out.toByteArray(), StandardCharsets.UTF_8)));
                if (latest == null) throw new IOException("暂未找到可用的 Android 发行版。");
                return latest;
            }
        } finally {
            connection.disconnect();
        }
    }

    static Release latestAndroid(JSONArray releases) {
        Release latest = null;
        for (int i = 0; i < releases.length(); i++) {
            JSONObject item = releases.optJSONObject(i);
            if (item == null || item.optBoolean("draft") || item.optBoolean("prerelease")) continue;
            JSONArray assets = item.optJSONArray("assets");
            for (int j = 0; assets != null && j < assets.length(); j++) {
                JSONObject asset = assets.optJSONObject(j);
                if (asset == null || !"uploaded".equals(asset.optString("state"))) continue;
                Release option = candidate(item.optString("tag_name"), asset.optString("name"), asset.optLong("size"));
                if (option == null || !option.downloadUrl().equals(asset.optString("browser_download_url"))) continue;
                if (latest == null || option.version.compareTo(latest.version) > 0) latest = option;
            }
        }
        return latest;
    }

    private static Release candidate(String tag, String filename, long bytes) {
        Version version = Version.parse(tag);
        Matcher apk = APK_NAME.matcher(filename);
        if (version == null || bytes <= 0 || !apk.matches() || !version.equals(Version.parse(apk.group(1)))) return null;
        return new Release(tag, version, filename, bytes);
    }
}
