package dev.torcharchive.game;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Display;
import android.view.Surface;
import android.view.View;
import android.view.Window;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Universal device resolution detector, high-refresh-rate controller (60/90/120Hz/Auto),
 * and aspect-ratio adapter injected into NxActivity.
 */
public final class DisplayConfig {
    private static final String TAG = "TorchArchive";
    private static final String KEY_W = "display_w";
    private static final String KEY_H = "display_h";
    private static final String KEY_FILL = "fill_screen";
    private static final String KEY_CUTOUT = "avoid_cutout";
    private static final String KEY_FPS = "refresh_rate"; // 0=Auto(Max), 60, 90, 120
    private static final int MIN_W = 640, MAX_W = 4096;
    private static final int MIN_H = 360, MAX_H = 2560;
    private static final int DEFAULT_W = 1920, DEFAULT_H = 1080;

    public static final class Preset {
        public final int width;
        public final int height;
        public final String title;
        public final String badge;

        public Preset(int width, int height, String title, String badge) {
            this.width = width;
            this.height = height;
            this.title = title;
            this.badge = badge;
        }
    }

    private DisplayConfig() {}

    public static int[] resolveResolution(Context context) {
        int[] screen = screenSize(context);
        int[] res = defaultResolutionForScreen(screen[0], screen[1]);
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

    public static boolean isFillScreen(Context context) {
        return GameArchive.state(context).getBoolean(KEY_FILL, false);
    }

    public static boolean isAvoidCutout(Context context) {
        return GameArchive.state(context).getBoolean(KEY_CUTOUT, true);
    }

    /**
     * Configured refresh rate preference: 0 = Auto (device max), 60, 90, or 120.
     */
    public static int getPreferredRefreshRate(Context context) {
        int val = GameArchive.state(context).getInt(KEY_FPS, 60);
        return sanitizeRefreshRate(val);
    }

    public static int sanitizeRefreshRate(int val) {
        if (val == 0 || val == 60 || val == 90 || val == 120) return val;
        if (val >= 110) return 120;
        if (val >= 80) return 90;
        return 60;
    }

    /**
     * Resolves the concrete target FPS (60..144) to feed into Window mode, Surface.setFrameRate,
     * and native libddnx.so frame pacing.
     */
    public static int resolveTargetFps(Context context) {
        int pref = getPreferredRefreshRate(context);
        int maxHz = maxSupportedRefreshRate(context);
        if (pref == 0) {
            return Math.max(60, Math.min(165, maxHz));
        }
        return Math.max(60, Math.min(165, pref));
    }

    @SuppressWarnings("deprecation")
    public static int[] supportedRefreshRates(Context context) {
        Set<Integer> rates = new TreeSet<>();
        try {
            Display display = resolveDisplay(context);
            if (display != null) {
                Display.Mode cur = display.getMode();
                Display.Mode[] modes = display.getSupportedModes();
                if (modes != null) {
                    for (Display.Mode m : modes) {
                        if (cur != null
                                && m.getPhysicalWidth() == cur.getPhysicalWidth()
                                && m.getPhysicalHeight() == cur.getPhysicalHeight()) {
                            int hz = Math.round(m.getRefreshRate());
                            if (hz >= 59 && hz <= 240) rates.add(hz);
                        }
                    }
                }
                if (rates.isEmpty() && cur != null) {
                    rates.add(Math.max(60, Math.round(cur.getRefreshRate())));
                }
            }
        } catch (Throwable ignored) {
        }
        if (rates.isEmpty()) rates.add(60);
        int[] out = new int[rates.size()];
        int i = 0;
        for (int r : rates) out[i++] = r;
        return out;
    }

    public static int maxSupportedRefreshRate(Context context) {
        int[] rates = supportedRefreshRates(context);
        return rates.length == 0 ? 60 : rates[rates.length - 1];
    }

    /**
     * Injected into NxActivity.panelAt60() to honor user-selected 60/90/120Hz/Auto refresh rate
     * instead of forcing high-refresh panels down to 60 Hz.
     */
    @SuppressWarnings("deprecation")
    public static void applyRefreshRate(Activity activity) {
        if (activity == null) return;
        try {
            int targetHz = resolveTargetFps(activity);
            Display display = resolveDisplay(activity);
            if (display == null) return;
            Display.Mode current = display.getMode();
            Display.Mode[] modes = display.getSupportedModes();
            Display.Mode chosen = null;
            float bestDiff = Float.MAX_VALUE;
            if (modes != null && current != null) {
                for (Display.Mode m : modes) {
                    if (m.getPhysicalWidth() != current.getPhysicalWidth()
                            || m.getPhysicalHeight() != current.getPhysicalHeight()) {
                        continue;
                    }
                    float hz = m.getRefreshRate();
                    float diff = Math.abs(hz - targetHz);
                    // Prefer modes that don't exceed targetHz when equidistant
                    if (diff < bestDiff - 0.05f
                            || (Math.abs(diff - bestDiff) <= 0.05f && chosen != null && hz <= targetHz + 1.5f && chosen.getRefreshRate() > targetHz + 1.5f)) {
                        bestDiff = diff;
                        chosen = m;
                    }
                }
            }
            Window window = activity.getWindow();
            if (window == null) return;
            WindowManager.LayoutParams lp = window.getAttributes();
            boolean changed = false;
            if (chosen != null) {
                if (lp.preferredDisplayModeId != chosen.getModeId()) {
                    lp.preferredDisplayModeId = chosen.getModeId();
                    changed = true;
                }
                if (Math.abs(lp.preferredRefreshRate - chosen.getRefreshRate()) > 0.5f) {
                    lp.preferredRefreshRate = chosen.getRefreshRate();
                    changed = true;
                }
            } else if (Math.abs(lp.preferredRefreshRate - targetHz) > 0.5f) {
                lp.preferredRefreshRate = targetHz;
                changed = true;
            }
            if (Build.VERSION.SDK_INT >= 28) {
                int cutoutMode = isAvoidCutout(activity)
                        ? WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER
                        : WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                if (lp.layoutInDisplayCutoutMode != cutoutMode) {
                    lp.layoutInDisplayCutoutMode = cutoutMode;
                    changed = true;
                }
            }
            if (changed) {
                window.setAttributes(lp);
            }
            Log.i("dd", String.format(Locale.ROOT,
                    "nx: display %.1f Hz -> mode %d at %.1f Hz (target=%d Hz)",
                    current != null ? current.getRefreshRate() : 60f,
                    chosen != null ? chosen.getModeId() : -1,
                    chosen != null ? chosen.getRefreshRate() : (float) targetHz,
                    targetHz));
        } catch (Throwable error) {
            Log.w("dd", "nx: failed to apply display refresh rate", error);
        }
    }

    /**
     * Injected into NxActivity.surfaceChanged() on Android 11+ (API 30+) to notify SurfaceFlinger
     * and LTPO panels of the chosen refresh rate.
     */
    public static void applySurfaceFrameRate(Context context, Surface surface) {
        if (context == null || surface == null || !surface.isValid()) return;
        if (Build.VERSION.SDK_INT < 30) return;
        try {
            int targetHz = resolveTargetFps(context);
            if (Build.VERSION.SDK_INT >= 31) {
                surface.setFrameRate((float) targetHz, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                        Surface.CHANGE_FRAME_RATE_ALWAYS);
            } else {
                surface.setFrameRate((float) targetHz, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE);
            }
            Log.i("dd", "nx: surface setFrameRate=" + targetHz + " Hz");
        } catch (Throwable error) {
            Log.w("dd", "nx: surface setFrameRate failed", error);
        }
    }

    @SuppressWarnings("deprecation")
    private static Display resolveDisplay(Context context) {
        try {
            if (Build.VERSION.SDK_INT >= 30 && context instanceof Activity) {
                Display d = context.getDisplay();
                if (d != null) return d;
            }
            WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            if (wm != null) return wm.getDefaultDisplay();
        } catch (Throwable ignored) {
        }
        return null;
    }

    public static Bundle describe(Context context) {
        int[] current = resolveResolution(context);
        int[] screen = screenSize(context);
        int[] rates = supportedRefreshRates(context);
        Bundle reply = new Bundle();
        reply.putInt("width", current[0]);
        reply.putInt("height", current[1]);
        reply.putBoolean("fill_screen", isFillScreen(context));
        reply.putBoolean("avoid_cutout", isAvoidCutout(context));
        reply.putInt("screen_w", screen[0]);
        reply.putInt("screen_h", screen[1]);
        reply.putInt("refresh_rate", getPreferredRefreshRate(context));
        reply.putInt("effective_fps", resolveTargetFps(context));
        reply.putInt("max_refresh_rate", rates.length == 0 ? 60 : rates[rates.length - 1]);
        reply.putIntArray("supported_rates", rates);
        return reply;
    }

    public static Bundle apply(Context context, int width, int height, boolean fillScreen) throws IOException {
        return apply(context, width, height, fillScreen, isAvoidCutout(context), getPreferredRefreshRate(context));
    }

    public static Bundle apply(Context context, int width, int height, boolean fillScreen, boolean avoidCutout) throws IOException {
        return apply(context, width, height, fillScreen, avoidCutout, getPreferredRefreshRate(context));
    }

    public static Bundle apply(Context context, int width, int height, boolean fillScreen, boolean avoidCutout, int refreshRate) throws IOException {
        int w = width & -2;
        int h = height & -2;
        int fps = sanitizeRefreshRate(refreshRate);
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
                .putBoolean(KEY_CUTOUT, avoidCutout)
                .putInt(KEY_FPS, fps)
                .commit()) {
            throw new IOException("保存显示模式失败");
        }
        return describe(context);
    }

    /**
     * Generates 4-6 deduplicated optimal resolution presets for any Android phone, tablet, or foldable.
     */
    public static List<Preset> presetsForDevice(int rawW, int rawH) {
        int w = Math.min(MAX_W, Math.max(MIN_W, Math.max(rawW, rawH) & -2));
        int h = Math.min(MAX_H, Math.max(MIN_H, Math.min(rawW, rawH) & -2));
        Map<String, Preset> ordered = new LinkedHashMap<>();

        // Fit 16:9 within device physical bounds
        int w169 = Math.min(w, ((int) Math.round(h * 16.0 / 9.0)) & -2);
        int h169 = ((int) Math.round(w169 * 9.0 / 16.0)) & -2;
        boolean ultrawide = h > 0 && ((double) w / h) > 1.85;

        if (ultrawide && (w169 != w || h169 != h)) {
            addPreset(ordered, w169, h169, "本机等比 16:9 清晰档（推荐）", "16:9 · 黄金比例无拉伸");
            addPreset(ordered, w, h, "本机原生全屏（宽屏拉伸）", aspectTag(w, h) + " · 铺满但会横向拉扁");
        } else {
            addPreset(ordered, w, h, "本机原生全屏", aspectTag(w, h) + " · 无裁剪");
            if (w169 != w || h169 != h) {
                addPreset(ordered, w169, h169, "本机等比 16:9 清晰档", "16:9 · 黄金比例无拉伸");
            }
        }

        // 75% high-DPI balanced preset (use 16:9 base on ultrawide phones so UI stays undistorted)
        int baseW = ultrawide ? w169 : w;
        int baseH = ultrawide ? h169 : h;
        int w75 = ((int) Math.round(baseW * 0.75)) & -2;
        int h75 = ((int) Math.round(baseH * 0.75)) & -2;
        if (w75 >= 1280 && h75 >= 720 && Math.abs(baseW - w75) >= 160) {
            addPreset(ordered, w75, h75, "均衡省电 75% 渲染", aspectTag(w75, h75) + " · 流畅兼顾");
        }

        // 16:10 2560x1600 for large tablets >= 2800px wide
        if (w >= 2800 && h >= 1700) {
            addPreset(ordered, 2560, 1600, "2.5K 高清 16:10", "16:10 · 高清护眼");
        }

        // Standard 1080p if fits or device >= 1920x1080
        if (w >= 1920 && h >= 1080) {
            addPreset(ordered, 1920, 1080, "标准 1080p", "16:9 · 通用清晰");
        }

        // 720p power saver
        addPreset(ordered, 1280, 720, "省电 720p", "16:9 · 低负载长续航");

        return new ArrayList<>(ordered.values());
    }

    private static void addPreset(Map<String, Preset> map, int w, int h, String title, String badge) {
        w &= -2;
        h &= -2;
        if (!valid(w, h)) return;
        String key = w + "x" + h;
        map.putIfAbsent(key, new Preset(w, h, title, badge));
    }

    private static int[] defaultResolutionForScreen(int rawW, int rawH) {
        int w = Math.max(rawW, rawH) & -2;
        int h = Math.min(rawW, rawH) & -2;
        if (!valid(w, h)) return new int[]{DEFAULT_W, DEFAULT_H};
        // Switch Darkest Dungeon renders a fixed 16:9 canvas stretched to LAYER_W x LAYER_H.
        // On ultrawide phones (> 1.85:1), default to native-height 16:9 to avoid squashing.
        if (h > 0 && ((double) w / h) > 1.85) {
            int w169 = Math.min(w, ((int) Math.round(h * 16.0 / 9.0)) & -2);
            int h169 = ((int) Math.round(w169 * 9.0 / 16.0)) & -2;
            if (valid(w169, h169)) return new int[]{w169, h169};
        }
        return new int[]{w, h};
    }

    public static String aspectTag(int w, int h) {
        if (h <= 0) return "";
        double r = (double) w / h;
        if (Math.abs(r - 16.0 / 9.0) < 0.03) return "16:9";
        if (Math.abs(r - 16.0 / 10.0) < 0.03) return "16:10";
        if (Math.abs(r - 4.0 / 3.0) < 0.03) return "4:3";
        if (Math.abs(r - 20.0 / 9.0) < 0.04) return "20:9";
        if (Math.abs(r - 21.0 / 9.0) < 0.05) return "21:9";
        return String.format(Locale.ROOT, "%.2f:1", r);
    }

    public static boolean valid(int w, int h) {
        return w >= MIN_W && w <= MAX_W && h >= MIN_H && h <= MAX_H;
    }

    private static File optionsFile(Context context) {
        File ext = context.getExternalFilesDir(null);
        if (ext == null) return null;
        return new File(new File(ext, "nx/save"), "persist.options.json");
    }

    @SuppressWarnings("deprecation")
    public static int[] screenSize(Context context) {
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
