package dev.torcharchive.dd;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class WorkshopClient {
    private static final long MAX_ZIP = 1024L * 1024 * 1024;
    private static final int MAX_PAGE = 4 * 1024 * 1024, MAX_IMAGE = 4 * 1024 * 1024;
    private static final String BROWSE = "https://steamcommunity.com/workshop/browse/";
    private static final String DETAILS = "https://api.steampowered.com/ISteamRemoteStorage/GetPublishedFileDetails/v1/";
    private static final String ITEM = "https://steamcommunity.com/sharedfiles/filedetails/";
    private static final String APP = "262060";
    private static final Pattern SSR = Pattern.compile("(?s)<script\\b[^>]*\\bid=\"valve-ssr-data\"[^>]*>(.*?)</script>");
    private static final Pattern ID = Pattern.compile("[0-9]{1,20}");
    private static final Pattern ZIP_LINK = Pattern.compile("(?i)https://[^\\s\\[\\]<>\"']+");
    private static final Pattern MEDIA = Pattern.compile("(?is)\\[(img|previewyoutube)(?:=[^\\]]*)?\\].*?\\[/\\1\\]");
    private static final Pattern FORMATTING = Pattern.compile("(?i)\\[/?(?:h[123]|b|i|u|strike|spoiler|noparse|list|olist|quote|code|table|tr|td|th|url|hr)(?:=[^\\]]*)?\\]");
    private static final String PROJECT = "https://github.com/theCrius/crius-custom-heroes";
    private static final String REVISION = "6b4692a4f3cc3203d5dca196f4ea71bbc2be3089";

    record Item(String id, String title, String creator, String image, String summary, long bytes,
                long updated, long subscribers, List<String> tags) {}
    record Page(List<Item> items, int number, int pages, int total) {}
    record Package(String title, String source, URL url, long bytes) {}
    record Detail(Item item, String description, Package download) {}

    interface Progress { void update(long received, long total); }

    static String pageUrl(String sort, String query, int page) {
        return Uri.parse(BROWSE).buildUpon().appendQueryParameter("appid", APP)
                .appendQueryParameter("section", "readytouseitems").appendQueryParameter("l", "schinese")
                .appendQueryParameter("browsesort", query.isEmpty() ? sort : "textsearch")
                .appendQueryParameter("days", "7").appendQueryParameter("p", Integer.toString(page))
                .appendQueryParameter("searchtext", query).build().toString();
    }

    static String itemUrl(String id) {
        return ITEM + "?id=" + id + "&l=schinese";
    }

    static String featuredPage() {
        return PROJECT;
    }

    static Package featuredPackage() throws IOException {
        return new Package("Crius 英雄调整", "GitHub · theCrius/crius-custom-heroes · " + REVISION.substring(0, 7),
                https("https://codeload.github.com/theCrius/crius-custom-heroes/zip/" + REVISION), 0);
    }

    static Package fromLink(String link) throws IOException {
        URL url = https(link.trim());
        String path = url.getPath();
        String name = Uri.decode(path.substring(path.lastIndexOf('/') + 1)).replaceAll("[\\p{Cntrl}/\\\\]", "").trim();
        name = name.replaceFirst("(?i)\\.zip$", "");
        if (name.isEmpty() || name.length() > 96) name = "在线模组";
        return new Package(name, "公开 ZIP · " + url.getHost(), url, 0);
    }

    Page browse(String sort, String query, int page) throws IOException, JSONException {
        byte[] response = read(https(pageUrl(sort, query, page)), null, MAX_PAGE);
        return parsePage(new String(response, StandardCharsets.UTF_8), page);
    }

    static Page parsePage(String html, int requestedPage) throws IOException, JSONException {
        Matcher ssr = SSR.matcher(html);
        if (!ssr.find()) throw new IOException("Steam 未返回工坊目录，可重试或打开 Steam 原页。");
        JSONObject root = new JSONObject(ssr.group(1));
        JSONObject queryData = new JSONObject(root.getJSONObject("renderContext").getString("queryData"));
        JSONArray queries = queryData.getJSONArray("queries");
        for (int i = 0; i < queries.length(); i++) {
            JSONObject query = queries.getJSONObject(i);
            JSONArray key = query.optJSONArray("queryKey");
            if (key == null || !"workshop_browse".equals(key.optString(0))) continue;
            JSONObject filters = key.optJSONObject(1);
            if (filters == null || filters.optInt("appid") != Integer.parseInt(APP)
                    || filters.optInt("page") != requestedPage) continue;
            JSONObject data = query.getJSONObject("state").getJSONObject("data");
            int total = data.getInt("total_count");
            if (data.optInt("eresult", 1) != 1) throw new IOException("Steam 工坊查询失败，请重试。");
            JSONArray results = data.optJSONArray("results");
            if (results == null && total != 0) throw new IOException("Steam 工坊目录暂不可用。");
            Map<String, String> creators = creators(data.optJSONArray("creator_player_link_details"));
            List<Item> items = new ArrayList<>();
            for (int n = 0; results != null && n < results.length(); n++) {
                JSONObject item = results.getJSONObject(n);
                String id = value(item, "publishedfileid");
                if (!ID.matcher(id).matches() || item.optInt("consumer_appid") != Integer.parseInt(APP)) continue;
                String creator = creators.get(value(item, "creator"));
                String title = value(item, "title");
                String summary = ZIP_LINK.matcher(value(item, "short_description")).replaceAll("").trim();
                items.add(new Item(id, title.isEmpty() ? "工坊条目 " + id : title, creator == null ? "" : creator,
                        value(item, "preview_url"), summary, item.optLong("file_size"),
                        item.optLong("time_updated"), item.optLong("subscriptions"), tags(item)));
            }
            return new Page(items, requestedPage, data.getInt("total_pages"), total);
        }
        throw new IOException("Steam 未返回工坊结果，可重试或打开 Steam 原页。");
    }

    private static Map<String, String> creators(JSONArray players) {
        Map<String, String> names = new HashMap<>();
        for (int i = 0; players != null && i < players.length(); i++) {
            JSONObject player = players.optJSONObject(i);
            JSONObject profile = player == null ? null : player.optJSONObject("public_data");
            if (profile != null) names.put(value(profile, "steamid"), value(profile, "persona_name"));
        }
        return names;
    }

    private static List<String> tags(JSONObject item) {
        List<String> tags = new ArrayList<>();
        JSONArray raw = item.optJSONArray("tags");
        for (int i = 0; raw != null && i < raw.length(); i++) {
            JSONObject tag = raw.optJSONObject(i);
            if (tag == null) continue;
            String label = value(tag, "display_name");
            if (label.isEmpty()) label = value(tag, "tag");
            if (!label.isEmpty()) tags.add(label);
        }
        return tags;
    }

    Detail detail(Item item) throws IOException, JSONException {
        String body = "itemcount=1&publishedfileids%5B0%5D=" + item.id();
        JSONObject root = new JSONObject(new String(read(https(DETAILS), body, MAX_PAGE), StandardCharsets.UTF_8));
        JSONObject data = root.getJSONObject("response").getJSONArray("publishedfiledetails").getJSONObject(0);
        if (data.optInt("result") != 1 || data.optInt("consumer_app_id") != Integer.parseInt(APP)
                || !item.id().equals(value(data, "publishedfileid")) || data.optBoolean("banned")
                || data.optInt("banned") == 1) {
            throw new IOException("此条目已下架、设为私密或不属于《暗黑地牢》。");
        }
        String description = value(data, "description");
        URL file = optionalHttps(value(data, "file_url"));
        long bytes = file == null ? 0 : data.optLong("file_size");
        if (file == null) {
            Matcher links = ZIP_LINK.matcher(MEDIA.matcher(description).replaceAll(""));
            while (links.find()) {
                URL link = optionalHttps(links.group().replaceFirst("[).,;]+$", ""));
                if (link != null && link.getPath().toLowerCase(Locale.ROOT).endsWith(".zip")) {
                    file = link;
                    break;
                }
            }
        }
        String formatted = MEDIA.matcher(description).replaceAll("");
        formatted = FORMATTING.matcher(formatted.replace("[*]", "• ")).replaceAll("");
        formatted = formatted.replace("\r", "").replaceAll("\\n[\\t ]*\\n(?:[\\t ]*\\n)+", "\n\n").trim();
        String title = value(data, "title"), preview = value(data, "preview_url");
        Item metadata = new Item(item.id(), title.isEmpty() ? item.title() : title, item.creator(),
                item.image().isEmpty() ? preview : item.image(), item.summary(), data.optLong("file_size", item.bytes()),
                data.optLong("time_updated", item.updated()), data.optLong("subscriptions", item.subscribers()),
                item.tags().isEmpty() ? tags(data) : item.tags());
        Package zip = file == null ? null : new Package(metadata.title(), "Steam 工坊 #" + item.id(), file, bytes);
        return new Detail(metadata, formatted, zip);
    }

    Bitmap cover(String address) throws IOException {
        URL url = optionalHttps(address);
        if (url == null || !isSteamImage(url)) return null;
        byte[] data = read(new URL(url, url.getPath() + "?imw=600"), null, MAX_IMAGE);
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, options);
        if (options.outWidth <= 0 || options.outHeight <= 0) return null;
        options.inSampleSize = 1;
        while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > 900) options.inSampleSize *= 2;
        options.inJustDecodeBounds = false;
        return BitmapFactory.decodeByteArray(data, 0, data.length, options);
    }

    void download(Package zip, File target, Progress progress, BooleanSupplier cancelled) throws IOException {
        if (zip.bytes() > MAX_ZIP) throw new IOException("压缩包超过 1 GB，无法收录。");
        HttpURLConnection connection = connect(zip.url(), null);
        try {
            long total = connection.getContentLengthLong();
            if (total > MAX_ZIP) throw new IOException("压缩包超过 1 GB，无法收录。");
            try (InputStream in = connection.getInputStream(); FileOutputStream out = new FileOutputStream(target)) {
                long count = copy(in, out, MAX_ZIP, total, progress, cancelled);
                if (total > 0 && count != total) throw new IOException("下载未完成，请重试。");
            }
        } finally {
            connection.disconnect();
        }
    }

    private static byte[] read(URL url, String body, int limit) throws IOException {
        HttpURLConnection connection = connect(url, body);
        try (InputStream in = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            copy(in, out, limit, -1, null, () -> false);
            return out.toByteArray();
        } finally {
            connection.disconnect();
        }
    }

    private static HttpURLConnection connect(URL url, String body) throws IOException {
        for (int redirects = 0; redirects <= 5; redirects++) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            boolean handedOff = false;
            try {
                connection.setConnectTimeout(12000);
                connection.setReadTimeout(18000);
                connection.setInstanceFollowRedirects(false);
                connection.setRequestProperty("User-Agent", "TorchArchive/2.3 (Android)");
                if (body != null) {
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    connection.setRequestMethod("POST");
                    connection.setDoOutput(true);
                    connection.setFixedLengthStreamingMode(bytes.length);
                    connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                    try (OutputStream out = connection.getOutputStream()) { out.write(bytes); }
                }
                int status = connection.getResponseCode();
                if (status == 200) {
                    handedOff = true;
                    return connection;
                }
                String location = connection.getHeaderField("Location");
                if (body != null || status < 300 || status > 399 || location == null) {
                    throw new IOException("服务器返回 HTTP " + status + "，请重试或查看来源页面。");
                }
                url = https(new URL(url, location).toExternalForm());
            } finally {
                if (!handedOff) connection.disconnect();
            }
        }
        throw new IOException("服务器重定向过多。");
    }

    private static long copy(InputStream in, OutputStream out, long limit, long total, Progress progress,
                             BooleanSupplier cancelled) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long copied = 0, lastUpdate = 0;
        for (int count; (count = in.read(buffer)) != -1; ) {
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
            copied += count;
            if (copied > limit) throw new IOException("文件超过工坊支持的大小。");
            out.write(buffer, 0, count);
            long now = System.nanoTime();
            if (progress != null && now - lastUpdate >= 200_000_000L) {
                progress.update(copied, total);
                lastUpdate = now;
            }
        }
        return copied;
    }

    private static URL https(String address) throws IOException {
        URL url = optionalHttps(address);
        if (url == null) throw new IOException("请输入有效的 HTTPS 下载链接。");
        return url;
    }

    private static URL optionalHttps(String address) {
        if (address.isEmpty()) return null;
        try {
            URL url = new URL(address);
            if ("https".equalsIgnoreCase(url.getProtocol()) && !url.getHost().isEmpty() && url.getUserInfo() == null
                    && (url.getPort() == -1 || url.getPort() == 443)) return url;
        } catch (MalformedURLException ignored) { }
        return null;
    }

    private static boolean isSteamImage(URL url) {
        String host = url.getHost().toLowerCase(Locale.ROOT);
        return host.endsWith(".steamusercontent.com") || host.endsWith(".steamstatic.com");
    }

    private static String value(JSONObject item, String key) {
        return item.isNull(key) ? "" : item.optString(key, "").trim();
    }
}
