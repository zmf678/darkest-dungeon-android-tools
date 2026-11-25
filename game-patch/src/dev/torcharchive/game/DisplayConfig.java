package dev.torcharchive.game;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Tablet resolution and aspect-ratio controller injected into NxActivity.
 */
public final class DisplayConfig {
    private static final String TAG = "TorchArchive";
    private static final String KEY_W = "display_w";
    private static final String KEY_H = "display_h";
    private static final String KEY_FILL = "fill_screen";
    private static final int MIN_W = 640, MAX_W = 4096;
    private static final int MIN_H = 360, MAX_H = 2560;
    private static final int DEFAULT_W = 1920, DEFAULT_H = 1080;

    private DisplayConfig() {}

    public static int[] resolveResolution(Context context) {
        int[] res = {DEFAULT_W, DEFAULT_H};
        boolean loaded = false;
        try {
            File file = optionsFile(context);
            if (file != null && file.isFile()) {
                String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
                JSONObject root = new JSONObject(text);
                JSONObject data = root.optJSONObject("data");
                JSONObject values = data == null ? null : data.optJSONObject("values");
                JSONArray arr = values == null ? null : values.optJSONArray("resolution");
                if (arr != null && arr.length() == 2) {
                    int w = arr.getInt(0) & -2;
                    int h = arr.getInt(1) & -2;
                    if (valid(w, h)) {
                        res[0] = w;
                        res[1] = h;
                        loaded = true;
                    }
                }
            }
        } catch (Throwable error) {
            Log.w(TAG, "Could not read persist.options.json resolution", error);
        }
        if (!loaded) {
            SharedPreferences prefs = GameArchive.state(context);
            int pw = prefs.getInt(KEY_W, 0) & -2;
            int ph = prefs.getInt(KEY_H, 0) & -2;
            if (valid(pw, ph)) {
                res[0] = pw;
                res[1] = ph;
            }
        }
        Log.i("dd", "nx: resolution " + res[0] + "x" + res[1] + (isFillScreen(context) ? " (fill)" : " (fit)"));
        return res;
    }

    public static int[] measure(Context context, int widthSpec, int heightSpec, int layerW, int layerH) {
        int availW = View.MeasureSpec.getSize(widthSpec);
        int availH = View.MeasureSpec.getSize(heightSpec);
        if (availW <= 0 || availH <= 0 || layerW <= 0 || layerH <= 0) {
            return new int[]{Math.max(availW, 1), Math.max(availH, 1)};
        }
        if (isFillScreen(context)) {
            return new int[]{availW, availH};
        }
        if (((long) availW) * ((long) layerH) > ((long) availH) * ((long) layerW)) {
            availW = (int) (((long) layerW * availH) / layerH);
        } else {
            availH = (int) (((long) layerH * availW) / layerW);
        }
        return new int[]{availW, availH};
    }

    static boolean isFillScreen(Context context) {
        return GameArchive.state(context).getBoolean(KEY_FILL, false);
    }

    static Bundle describe(Context context) {
        int[] current = resolveResolution(context);
        int[] screen = screenSize(context);
        Bundle reply = new Bundle();
        reply.putInt("width", current[0]);
        reply.putInt("height", current[1]);
        reply.putBoolean("fill_screen", isFillScreen(context));
        reply.putInt("screen_w", screen[0]);
        reply.putInt("screen_h", screen[1]);
        return reply;
    }

    static Bundle apply(Context context, int width, int height, boolean fillScreen) throws IOException {
        int w = width & -2;
        int h = height & -2;
        if (!valid(w, h)) {
            throw new IOException("不支持的分辨率: " + width + "x" + height);
        }
        File file = optionsFile(context);
        if (file == null) throw new IOException("游戏目录不可用");
        Path target = file.toPath();
        Files.createDirectories(target.getParent());
        JSONObject root;
        if (Files.isRegularFile(target)) {
            try {
                root = new JSONObject(new String(Files.readAllBytes(target), StandardCharsets.UTF_8));
            } catch (Exception ignored) {
                root = new JSONObject();
            }
        } else {
            root = new JSONObject();
        }
        try {
            JSONObject data = root.optJSONObject("data");
            if (data == null) {
                data = new JSONObject();
                root.put("data", data);
            }
            JSONObject values = data.optJSONObject("values");
            if (values == null) {
                values = new JSONObject();
                data.put("values", values);
            }
            JSONArray arr = new JSONArray();
            arr.put(w);
            arr.put(h);
            values.put("resolution", arr);
            byte[] encoded = root.toString(4).getBytes(StandardCharsets.UTF_8);
            Path temp = target.getParent().resolve(".persist.options.json.tmp");
            try (FileOutputStream out = new FileOutputStream(temp.toFile())) {
                out.write(encoded);
                out.getFD().sync();
            }
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException error) {
            throw error;
        } catch (Exception error) {
            throw new IOException("写入画面配置失败: " + error.getMessage(), error);
        }
        if (!GameArchive.state(context).edit()
                .putInt(KEY_W, w)
                .putInt(KEY_H, h)
                .putBoolean(KEY_FILL, fillScreen)
                .commit()) {
            throw new IOException("保存显示模式失败");
        }
        return describe(context);
    }

    private static boolean valid(int w, int h) {
        return w >= MIN_W && w <= MAX_W && h >= MIN_H && h <= MAX_H;
    }

    private static File optionsFile(Context context) {
        File ext = context.getExternalFilesDir(null);
        if (ext == null) return null;
        return new File(new File(ext, "nx/save"), "persist.options.json");
    }

    @SuppressWarnings("deprecation")
    private static int[] screenSize(Context context) {
        int w = DEFAULT_W;
        int h = DEFAULT_H;
        try {
            WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            if (wm != null) {
                if (Build.VERSION.SDK_INT >= 30) {
                    Rect bounds = wm.getMaximumWindowMetrics().getBounds();
                    w = bounds.width();
                    h = bounds.height();
                } else {
                    Point p = new Point();
                    wm.getDefaultDisplay().getRealSize(p);
                    w = p.x;
                    h = p.y;
                }
            }
        } catch (Throwable ignored) {
        }
        return new int[]{Math.max(w, h), Math.min(w, h)};
    }
}
