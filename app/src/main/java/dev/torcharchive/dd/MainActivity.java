package dev.torcharchive.dd;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.database.ContentObserver;
import android.database.Cursor;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.text.InputFilter;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dev.torcharchive.game.DisplayConfig;
import dev.torcharchive.game.ModBridge;
import dev.torcharchive.game.PakMount;
import dev.torcharchive.game.SaveEditor;

public final class MainActivity extends Activity implements ModifierSection.Host {
    static final int BG = 0xff151111, PANEL = 0xff211918, RAISED = 0xff2b201e;
    static final int INK = 0xfff5e6d4, MUTED = 0xffc1a897, GOLD = 0xffe1ab71, LINE = 0xff543c32;
    static final int RED = 0xff733029, ERROR = 0xffffb3a3, GREEN = 0xffc5cb9e;
    private static final int SAVE_TREE = 11, SAVE_ZIP = 12, MOD_ZIP = 13, MOD_TREE = 14, ZIP_OUT = 15,
            RESTORE_TREE = 16, PAK_FILE = 17, MIGRATION_ZIP = 18;
    private static final String[] TABS = {"启动", "存档", "修改", "模组", "画面", "指南"};
    private static final String[] NAV_ICONS = {"flame", "book", "wrench", "box", "screen", "help"};
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final int READ = Intent.FLAG_GRANT_READ_URI_PERMISSION;
    private static final int WRITE = Intent.FLAG_GRANT_WRITE_URI_PERMISSION;

    private ArchiveStore store;
    private LiveArchive live;
    private ModifierSection modifierSection;
    private WorkshopSection workshop;
    private LauncherUpdates updates;
    private boolean workshopOpen;
    private SharedPreferences prefs;
    private FrameLayout outer;
    private Typeface display;
    private int tab, scrollY;
    private boolean busy, noteError, noteRestore;
    private String note = "";
    private String pendingId, pendingSlot;
    private String liveModError = "";
    private String liveDisplayError = "";
    private List<ArchiveStore.Entry> saves = Collections.emptyList(), mods = Collections.emptyList();
    private List<ArchiveStore.Entry> automatic = Collections.emptyList();
    private List<ArchiveStore.ModPreset> presets = Collections.emptyList();
    private List<LiveArchive.GameMod> gameMods = Collections.emptyList();
    private List<LiveArchive.SlotMods> slotMods = Collections.emptyList();
    private List<SaveEditor.SlotSummary> slotSummaries = Collections.emptyList();
    private LiveArchive.DisplayInfo displayInfo;
    private PakMount.PakInfo pakInfo;
    private boolean gameRunning;
    private String automaticStatus = "";
    private boolean observing;
    private final ContentObserver captureObserver = new ContentObserver(new Handler(Looper.getMainLooper())) {
        @Override public void onChange(boolean selfChange) { refreshLive(false); }
    };
    private ScrollView scroll;
    private Object backCallback;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            getWindow().setNavigationBarContrastEnforced(false);
        } else if (android.os.Build.VERSION.SDK_INT >= 29) {
            getWindow().setNavigationBarContrastEnforced(false);
        }
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        store = new ArchiveStore(this);
        live = new LiveArchive(this);
        modifierSection = new ModifierSection(this, this);
        prefs = getSharedPreferences("folders", MODE_PRIVATE);
        display = Typeface.createFromAsset(getAssets(), "display.ttf");
        workshop = new WorkshopSection(this, store, display);
        updates = new LauncherUpdates(this);
        if (saved != null) {
            tab = saved.getInt("tab");
            workshopOpen = saved.getBoolean("workshopOpen");
            workshop.selectMode(saved.getBoolean("workshopDirect"));
            pendingId = saved.getString("pending");
            pendingSlot = saved.getString("slot");
        }
        outer = new FrameLayout(this);
        outer.setBackgroundColor(BG);
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            outer.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets safe = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
                return insets;
            });
        }
        setContentView(outer);
        reload();
    }

    @Override protected void onStart() {
        super.onStart();
        updates.checkOnStart();
        try {
            getContentResolver().registerContentObserver(dev.torcharchive.game.GameArchive.VERSIONS, false, captureObserver);
            observing = true;
        } catch (SecurityException ignored) {
        }
        refreshLive(false);
    }

    @Override protected void onStop() {
        if (observing) {
            getContentResolver().unregisterContentObserver(captureObserver);
            observing = false;
        }
        super.onStop();
    }

    private void refreshLive(boolean notify) {
        if (notify) {
            perform("正在同步数据包、存档与模组…", this::fetchLiveSnapshot, snap -> {
                applyLiveSnapshot(snap);
                message("全部数据已同步");
            });
            return;
        }
        IO.execute(() -> {
            LiveSnapshot snap = fetchLiveSnapshot();
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                applyLiveSnapshot(snap);
                redraw();
            });
        });
    }

    private record LiveSnapshot(PakMount.PakInfo pak, boolean running,
                                LiveArchive.History history, List<LiveArchive.GameMod> mods,
                                List<LiveArchive.SlotMods> slots, List<SaveEditor.SlotSummary> summaries,
                                LiveArchive.DisplayInfo display,
                                String modErr, String dispErr) {}

    private LiveSnapshot fetchLiveSnapshot() {
        PakMount.PakInfo pak = live.pakInfo();
        boolean running = live.isGameRunning();
        LiveArchive.History history = live.history();
        List<LiveArchive.GameMod> fetchedMods = Collections.emptyList();
        List<LiveArchive.SlotMods> fetchedSlots = Collections.emptyList();
        List<SaveEditor.SlotSummary> fetchedSummaries = Collections.emptyList();
        LiveArchive.DisplayInfo fetchedDisplay = null;
        String modErr = "";
        String dispErr = "";
        try {
            fetchedMods = live.listMods();
            fetchedSlots = live.listSlotMods();
            fetchedSummaries = SaveEditor.listSlots(this);
        } catch (Exception ex) {
            modErr = readable(ex);
        }
        try {
            fetchedDisplay = live.display();
        } catch (Exception ex) {
            dispErr = readable(ex);
        }
        modifierSection.refreshSync();
        return new LiveSnapshot(pak, running, history, fetchedMods, fetchedSlots, fetchedSummaries, fetchedDisplay, modErr, dispErr);
    }

    private void applyLiveSnapshot(LiveSnapshot snap) {
        pakInfo = snap.pak;
        gameRunning = snap.running;
        automatic = snap.history.entries();
        automaticStatus = snap.history.status();
        gameMods = snap.mods;
        slotMods = snap.slots;
        slotSummaries = snap.summaries;
        displayInfo = snap.display;
        liveModError = snap.modErr;
        liveDisplayError = snap.dispErr;
        updateLists();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("workshopOpen", workshopOpen);
        state.putBoolean("workshopDirect", workshop.isDirectMode());
        state.putInt("tab", tab);
        state.putString("pending", pendingId);
        state.putString("slot", pendingSlot);
        super.onSaveInstanceState(state);
    }

    @Override public void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config);
        redraw();
    }

    @Override protected void onDestroy() {
        workshop.close();
        updates.close();
        if (android.os.Build.VERSION.SDK_INT >= 33 && backCallback != null) {
            try {
                getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(
                        (android.window.OnBackInvokedCallback) backCallback);
            } catch (Throwable ignored) {
            }
            backCallback = null;
        }
        super.onDestroy();
    }

    private void syncBackCallback() {
        if (android.os.Build.VERSION.SDK_INT < 33) return;
        boolean need = (tab == 2 && modifierSection.isInSubPage())
                || (tab == 3 && workshopOpen && workshop.hasDetail());
        if (need && backCallback == null) {
            android.window.OnBackInvokedCallback cb = () -> {
                if (handleSectionBack()) return;
                syncBackCallback();
            };
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb);
            backCallback = cb;
        } else if (!need && backCallback != null) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(
                    (android.window.OnBackInvokedCallback) backCallback);
            backCallback = null;
        }
    }

    // Legacy callback for Android 12 and earlier. Android 13+ uses the dispatcher above.
    @Override @SuppressWarnings("deprecation") @android.annotation.SuppressLint("GestureBackNavigation")
    public void onBackPressed() {
        if (android.os.Build.VERSION.SDK_INT >= 33 || !handleSectionBack()) super.onBackPressed();
    }

    private boolean handleSectionBack() {
        if (tab == 3 && workshopOpen && workshop.hasDetail()) {
            workshop.back();
            return true;
        }
        return tab == 2 && modifierSection.handleBack();
    }

    private void reload() {
        updateLists();
        draw();
    }

    private void selectTab(int value) {
        if (tab == value) return;
        tab = value;
        scrollY = 0;
        note = "";
        noteRestore = false;
        draw();
        refreshLive(false);
    }

    private void redraw() {
        if (scroll != null) scrollY = scroll.getScrollY();
        draw();
    }

    private void draw() {
        outer.removeAllViews();
        boolean wide = getResources().getConfiguration().screenWidthDp >= 720;
        LinearLayout shell = vertical();
        outer.addView(shell, new FrameLayout.LayoutParams(-1, -1));
        shell.addView(toolbar(), new LinearLayout.LayoutParams(-1, dp(64)));
        shell.addView(rule());
        LinearLayout main = horizontal();
        shell.addView(main, new LinearLayout.LayoutParams(-1, 0, 1));
        if (wide) {
            main.addView(navigation(true), new LinearLayout.LayoutParams(dp(96), -1));
            View edge = new View(this);
            edge.setBackgroundColor(LINE);
            main.addView(edge, new LinearLayout.LayoutParams(dp(1), -1));
        }
        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        main.addView(scroll, new LinearLayout.LayoutParams(0, -1, 1));
        FrameLayout centered = new FrameLayout(this);
        scroll.addView(centered, new ScrollView.LayoutParams(-1, -2));
        LinearLayout page = vertical();
        int pad = dp(wide ? 32 : 18);
        page.setPadding(pad, dp(wide && tab != 3 ? 24 : 14), pad, dp(28));
        int available = getResources().getDisplayMetrics().widthPixels - (wide ? dp(96) : 0);
        centered.addView(page, new FrameLayout.LayoutParams(Math.min(available, dp(1080)), -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        boolean inModifierSub = (tab == 2 && modifierSection.isInSubPage());
        boolean inWorkshopDetail = tab == 3 && workshopOpen && workshop.hasDetail();
        if (!inModifierSub && !inWorkshopDetail) {
            boolean compact = tab == 3;
            int extraTextSpace = Math.round(Math.max(0f, getResources().getConfiguration().fontScale - 1f) * 72);
            int heroHeight = compact ? (wide ? 92 : 112) + extraTextSpace : (wide ? 210 : 178);
            page.addView(hero(wide, compact), new LinearLayout.LayoutParams(-1, dp(heroHeight)));
        }
        if (busy || !note.isEmpty()) add(page, feedback(), inModifierSub ? 0 : 14);

        switch (tab) {
            case 0 -> add(page, homePage(wide), 18);
            case 1 -> {
                add(page, actions(), 14);
                if (wide) {
                    LinearLayout columns = horizontal();
                    columns.setBaselineAligned(false);
                    add(page, columns, 22);
                    columns.addView(history(), new LinearLayout.LayoutParams(0, -2, 7));
                    LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(0, -2, 5);
                    right.setMarginStart(dp(24));
                    columns.addView(restoreGuide(), right);
                } else {
                    add(page, history(), 22);
                    add(page, restoreGuide(), 18);
                }
            }
            case 2 -> add(page, modifierSection.buildView(wide, display), inModifierSub ? (busy || !note.isEmpty() ? 10 : 0) : 18);
            case 3 -> {
                if (!inWorkshopDetail) add(page, modNavigation(), 10);
                if (workshopOpen) {
                    add(page, workshop.build(wide), inWorkshopDetail ? 0 : 10);
                    break;
                }
                add(page, actions(), 14);
                if (wide) {
                    LinearLayout columns = horizontal();
                    columns.setBaselineAligned(false);
                    add(page, columns, 22);
                    columns.addView(modsLeftColumn(), new LinearLayout.LayoutParams(0, -2, 7));
                    LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(0, -2, 5);
                    right.setMarginStart(dp(24));
                    columns.addView(modsRightColumn(), right);
                } else {
                    add(page, modsLeftColumn(), 22);
                    add(page, modsRightColumn(), 22);
                }
            }
            case 4 -> add(page, displayPage(wide), 20);
            default -> add(page, guide(), 22);
        }
        if (!wide) {
            shell.addView(rule());
            shell.addView(navigation(false), new LinearLayout.LayoutParams(-1, -2));
        }
        if (scrollY > 0) {
            ScrollView current = scroll;
            current.post(() -> current.scrollTo(0, scrollY));
        }
        syncBackCallback();
    }

    private View toolbar() {
        LinearLayout top = horizontal();
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(18), 0, dp(16), 0);
        ImageView mark = new ImageView(this);
        mark.setImageResource(R.drawable.archive_mark);
        top.addView(mark, new LinearLayout.LayoutParams(dp(38), dp(38)));
        TextView name = type("暗黑地牢 · 余烬启动器", 18, INK, display);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams nameWidth = new LinearLayout.LayoutParams(0, -2, 1);
        nameWidth.setMarginStart(dp(10));
        nameWidth.setMarginEnd(dp(8));
        top.addView(name, nameWidth);
        if (gameRunning) {
            View stopBtn = button("结束游戏进程", false, () ->
                    perform("正在安全结束游戏进程…", () -> {
                        live.stopGame();
                        return fetchLiveSnapshot();
                    }, snap -> {
                        applyLiveSnapshot(snap);
                        message("游戏 :nx 进程已结束");
                    }));
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-2, -2);
            sp.setMarginEnd(dp(8));
            top.addView(stopBtn, sp);
        }
        top.addView(button(gameRunning ? "切换回游戏" : "开始游戏", true, this::openGame));
        return top;
    }

    private View hero(boolean wide, boolean compact) {
        FrameLayout frame = new FrameLayout(this);
        frame.setBackground(surface(PANEL, LINE, 14));
        frame.setForeground(surface(Color.TRANSPARENT, LINE, 14));
        frame.setClipToOutline(true);

        ImageView art = new ImageView(this);
        boolean direct = compact && workshopOpen && workshop.isDirectMode();
        art.setImageResource(direct ? R.drawable.workshop_dispatch
                : compact ? R.drawable.workshop_header : R.drawable.archive_illustration);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        frame.addView(art, new FrameLayout.LayoutParams(-1, -1));

        View shade = new View(this);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xf5191110, 0xe0191110, 0x30191110, 0x00191110}));
        frame.addView(shade, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout words = vertical();
        words.setPadding(dp(wide ? 28 : 20), dp(compact ? 9 : 18), dp(18), dp(compact ? 9 : 18));
        words.setGravity(Gravity.CENTER_VERTICAL);
        String title = switch (tab) {
            case 0 -> "点亮火把";
            case 1 -> "存档书库";
            case 2 -> "完整修改器";
            case 3 -> direct ? "公开 ZIP" : workshopOpen ? "创意工坊" : "模组库";
            case 4 -> "全机型分辨率与高刷";
            default -> "使用指南";
        };
        String detail = switch (tab) {
            case 0 -> "一体化薄壳启动器 · 外置数据包直读与 Steam 迁移";
            case 1 -> "同 UID 直连免拦截 · 自动留档与一键回档";
            case 2 -> "全字段二进制存档精修 · 二级详情页与规则 Mod 工坊";
            case 3 -> direct ? "下载作者公开发布的模组"
                    : workshopOpen ? "浏览 Steam 社区的《暗黑地牢》模组" : "管理已安装模组与档位加载顺序";
            case 4 -> "动态探测任意设备分辨率 · 支持 60 / 90 / 120 Hz 高刷适配";
            default -> "Steam 转安卓步骤 · 数据包路径与高级工具";
        };
        TextView heading = type(title, compact ? (wide ? 30 : 26) : (wide ? 36 : 30), INK, display);
        if (android.os.Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
        if (compact) heading.setIncludeFontPadding(false);
        words.addView(heading);
        if (!compact) {
            View accent = new View(this);
            accent.setBackgroundColor(GOLD);
            LinearLayout.LayoutParams accentParams = new LinearLayout.LayoutParams(dp(34), dp(2));
            accentParams.topMargin = dp(8);
            accentParams.bottomMargin = dp(10);
            words.addView(accent, accentParams);
        }
        add(words, type(detail, 14, MUTED, Typeface.DEFAULT), compact ? 5 : 0);
        int width = dp(wide ? 480 : 285);
        frame.addView(words, new FrameLayout.LayoutParams(width, -1, Gravity.START | Gravity.CENTER_VERTICAL));
        return frame;
    }

    private View navigation(boolean rail) {
        LinearLayout nav = rail ? vertical() : horizontal();
        nav.setBackgroundColor(0xff1b1514);
        nav.setPadding(dp(rail ? 8 : 8), dp(rail ? 14 : 6), dp(rail ? 8 : 8), dp(rail ? 8 : 4));
        for (int i = 0; i < TABS.length; i++) {
            final int destination = i;
            boolean active = tab == i;
            LinearLayout item = vertical();
            item.setGravity(Gravity.CENTER);
            item.setPadding(dp(2), dp(6), dp(2), dp(6));
            item.setMinimumHeight(dp(58));
            item.setBackground(pressable(active ? 0xff39231e : Color.TRANSPARENT, 0, 12));
            item.setContentDescription(TABS[i]);
            item.setSelected(active);
            item.setFocusable(true);
            item.setOnClickListener(view -> selectTab(destination));
            item.addView(new Glyph(NAV_ICONS[i], active ? GOLD : MUTED), new LinearLayout.LayoutParams(dp(20), dp(20)));
            TextView caption = type(TABS[i], 12, active ? GOLD : MUTED, Typeface.DEFAULT_BOLD);
            caption.setGravity(Gravity.CENTER);
            add(item, caption, 2);
            LinearLayout.LayoutParams lp = rail ? new LinearLayout.LayoutParams(-1, -2)
                    : new LinearLayout.LayoutParams(0, -2, 1);
            if (rail) lp.bottomMargin = dp(6);
            else if (i < TABS.length - 1) lp.setMarginEnd(dp(4));
            nav.addView(item, lp);
        }
        return nav;
    }

    private View homePage(boolean wide) {
        LinearLayout root = vertical();
        PakMount.PakInfo pak = pakInfo != null ? pakInfo : live.pakInfo();

        // Main launch CTA card
        LinearLayout launchCard = vertical();
        launchCard.setPadding(dp(18), dp(16), dp(18), dp(16));
        launchCard.setBackground(surface(PANEL, LINE, 14));
        launchCard.addView(type(pak.ready ? "点亮火把 · 准备就绪" : "需要游戏数据包 (dd_assets.pak)", 19, INK, display));
        add(launchCard, type(pak.ready
                        ? pak.detail + (pak.bytes > 0 ? "  ·  " + size(pak.bytes) : "")
                        : pak.detail,
                13, pak.ready ? GREEN : ERROR, Typeface.DEFAULT), 6);

        if (!pak.dlcs.isEmpty()) {
            add(launchCard, type("已含 DLC：" + String.join(" / ", pak.dlcs), 12, GOLD, Typeface.DEFAULT), 6);
        }

        LinearLayout primaryRow = horizontal();
        weighted(primaryRow, button(pak.ready ? "点亮火把 · 开始游戏" : "先导入 dd_assets.pak", true, () -> {
            if (pak.ready) openGame();
            else pickFile(PAK_FILE);
        }), true);
        weighted(primaryRow, button("导入 Steam 迁移包", false, () -> pickFile(MIGRATION_ZIP)), false);
        add(launchCard, primaryRow, 12);

        LinearLayout secondRow = horizontal();
        weighted(secondRow, button("选择导入 dd_assets.pak", false, () -> pickFile(PAK_FILE)), true);
        weighted(secondRow, button("刷新状态检测", false, () -> refreshLive(true)), false);
        add(launchCard, secondRow, 8);
        root.addView(launchCard);
        add(root, updates.build(), 14);

        // Quick overview grid
        LinearLayout overview = vertical();
        overview.setPadding(dp(16), dp(14), dp(16), dp(14));
        overview.setBackground(surface(PANEL, LINE, 13));
        overview.addView(type("当前环境概览", 16, INK, Typeface.DEFAULT_BOLD));
        String slotText = slotSummaries.isEmpty() ? "尚无存档槽位（可先开始游戏或导入 Steam 迁移包）"
                : "共 " + slotSummaries.size() + " 个有效档位 · 主力档位「" + slotSummaries.get(0).estateName + "」(" + slotSummaries.get(0).heroCount + " 名英雄)";
        add(overview, type("存档：" + slotText, 13, MUTED, Typeface.DEFAULT), 6);
        add(overview, type("模组：已安装 " + gameMods.size() + " 个 Mod  ·  快照库共 " + saves.size() + " 个历史版本", 13, MUTED, Typeface.DEFAULT), 4);
        if (displayInfo != null) {
            add(overview, type("画面：" + displayInfo.width() + " × " + displayInfo.height()
                    + (displayInfo.fillScreen() ? "（全屏铺满）" : "（等比清晰）")
                    + "  ·  " + refreshLabel(displayInfo.refreshRate(), displayInfo.effectiveFps())
                    + "  ·  屏幕 " + displayInfo.screenW() + "×" + displayInfo.screenH(), 13, GOLD, Typeface.DEFAULT), 4);
        }
        add(overview, type("数据包标准目录：Android/media/" + getPackageName() + "/dd_assets.pak（可用 ADB 或文件管理器直拷）",
                12, MUTED, Typeface.DEFAULT), 6);
        add(root, overview, 14);
        return root;
    }

    private View actions() {
        LinearLayout row = horizontal();
        if (tab == 1) {
            weighted(row, button("导入存档 / 文件夹", true, this::chooseSaveInput), true);
            weighted(row, button("立刻备份档位", false, this::promptBackupSlot), false);
        } else {
            weighted(row, button("导入 Mod ZIP", true, () -> pickFile(MOD_ZIP)), true);
            weighted(row, button("导入 Steam 迁移包", false, () -> pickFile(MIGRATION_ZIP)), false);
        }
        return row;
    }

    private View modNavigation() {
        LinearLayout row = horizontal();
        row.setPadding(dp(4), dp(4), dp(4), dp(4));
        row.setBackground(surface(PANEL, LINE, 12));
        TextView local = segment("本地模组", !workshopOpen, () -> switchWorkshop(false, false));
        weighted(row, local, true);
        boolean steam = workshopOpen && !workshop.isDirectMode();
        TextView online = segment("在线工坊", steam, () -> switchWorkshop(true, false));
        weighted(row, online, true);
        boolean zip = workshopOpen && workshop.isDirectMode();
        TextView direct = segment("公开 ZIP", zip, () -> switchWorkshop(true, true));
        weighted(row, direct, false);
        return row;
    }

    private void switchWorkshop(boolean online, boolean direct) {
        if (workshopOpen == online && (!online || workshop.isDirectMode() == direct)) return;
        workshop.selectMode(direct);
        workshopOpen = online;
        scrollY = 0;
        draw();
    }

    int workshopScroll() {
        return scroll == null ? 0 : scroll.getScrollY();
    }

    void showWorkshopPage(int offset) {
        if (tab != 3 || !workshopOpen) return;
        scrollY = offset;
        draw();
    }

    void scrollToWorkshop(View view) {
        ScrollView current = scroll;
        if (current == null) return;
        current.post(() -> {
            if (current != scroll || !view.isAttachedToWindow()) return;
            int[] viewLocation = new int[2], scrollLocation = new int[2];
            view.getLocationInWindow(viewLocation);
            current.getLocationInWindow(scrollLocation);
            current.scrollTo(0, current.getScrollY() + viewLocation[1] - scrollLocation[1] - dp(10));
        });
    }

    ArchiveStore.Entry workshopArchive(String source) {
        for (ArchiveStore.Entry entry : mods) {
            if (entry.source().equals(source) || entry.source().equals(source + " · 含旧版目录")) return entry;
        }
        return null;
    }

    void refreshWorkshopLibrary() {
        updateLists();
        if (tab == 3 && !workshopOpen) redraw();
    }

    void openExternalPage(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException ex) {
            error("找不到可以打开来源页面的浏览器。");
        }
    }

    private void promptBackupSlot() {
        if (slotSummaries.isEmpty()) {
            error("暂无可备份的存档槽位。");
            return;
        }
        String[] labels = new String[slotSummaries.size()];
        for (int i = 0; i < slotSummaries.size(); i++) {
            SaveEditor.SlotSummary s = slotSummaries.get(i);
            labels[i] = slotLabel(s.slot) + " · " + s.estateName + " (" + s.gold + " 金币)";
        }
        new AlertDialog.Builder(this).setTitle("选择要立刻拍摄快照的档位")
                .setItems(labels, (d, which) -> {
                    String slot = slotSummaries.get(which).slot;
                    perform("正在为 " + slotLabel(slot) + " 拍摄快照…", () -> {
                        live.backupSlot(slot);
                        return fetchLiveSnapshot();
                    }, snap -> {
                        applyLiveSnapshot(snap);
                        message(slotLabel(slot) + " 已生成新快照");
                    });
                }).setNegativeButton("取消", null).show();
    }

    private View history() {
        LinearLayout block = vertical();
        heading(block, "版本历史", saves.size() + " 个版本");
        if (!automaticStatus.isEmpty()) add(block, type(automaticStatus, 13, MUTED, Typeface.DEFAULT), 8);
        if (saves.isEmpty()) {
            add(block, empty("book", "还没有存档版本", "开始游戏保存后将自动生成版本，也可手动导入 Steam/导出的存档。"), 10);
            return block;
        }
        LinearLayout list = vertical();
        list.setBackground(surface(PANEL, LINE, 13));
        for (int i = 0; i < saves.size(); i++) {
            ArchiveStore.Entry entry = saves.get(i);
            String second = time(entry.date()) + "  ·  " + entry.count() + " 个档位";
            list.addView(record("commit", entry.title(), second, "#" + LiveArchive.displayId(entry), () -> saveDetails(entry)));
            if (i < saves.size() - 1) list.addView(rule());
        }
        add(block, list, 10);
        return block;
    }

    private View modsLeftColumn() {
        LinearLayout col = vertical();
        col.addView(slotModsSection());
        add(col, presetSection(), 22);
        add(col, modLibrary(), 22);
        return col;
    }

    private View modsRightColumn() {
        return liveInstalledMods();
    }

    private View slotModsSection() {
        LinearLayout block = vertical();
        heading(block, "档位模组配置", slotMods.size() + " 个存档位");
        if (!liveModError.isEmpty()) {
            add(block, empty("box", "读取档位模组失败", liveModError), 10);
            return block;
        }
        if (slotMods.isEmpty()) {
            add(block, empty("book", "暂无存档槽位", "建立游戏存档或导入 Steam 迁移包后，可在此勾选启用模组与优先级顺序。"), 10);
            return block;
        }
        LinearLayout list = vertical();
        list.setBackground(surface(PANEL, LINE, 13));
        for (int i = 0; i < slotMods.size(); i++) {
            LiveArchive.SlotMods slot = slotMods.get(i);
            String detail = "已启用 " + slot.mods().size() + " 个模组"
                    + (slot.steamCount() > 0 ? "  ·  含 " + slot.steamCount() + " 个 Steam 项待转本地" : "")
                    + (slot.modified() > 0 ? "  ·  " + time(slot.modified()) : "");
            list.addView(record("commit", slot.label(), detail, "配置", () -> openSlotModEditor(slot)));
            if (i < slotMods.size() - 1) list.addView(rule());
        }
        add(block, list, 10);
        add(block, type("修改档位模组会自动停止 :nx 并先备份一份档位快照，随后可直接启动游戏。", 13, MUTED, Typeface.DEFAULT), 8);
        return block;
    }

    private View presetSection() {
        LinearLayout block = vertical();
        heading(block, "模组预设方案", presets.size() + " 套");
        if (presets.isEmpty()) {
            add(block, empty("box", "还没有保存预设", "点开上方任意档位配置并「存为预设」，即可一键复用整套模组与顺序。"), 10);
            return block;
        }
        LinearLayout list = vertical();
        list.setBackground(surface(PANEL, LINE, 13));
        for (int i = 0; i < presets.size(); i++) {
            ArchiveStore.ModPreset preset = presets.get(i);
            String detail = preset.modIds().size() + " 个模组  ·  " + time(preset.date());
            list.addView(record("box", preset.name(), detail, "应用", () -> presetActions(preset)));
            if (i < presets.size() - 1) list.addView(rule());
        }
        add(block, list, 10);
        return block;
    }

    private View liveInstalledMods() {
        LinearLayout block = vertical();
        heading(block, "已安装模组", gameMods.size() + " 个");
        if (gameMods.isEmpty()) {
            add(block, empty("box", "游戏目录为空", "可导入 Mod ZIP、Steam 迁移包，或在「修改」页生成内置规则修改器 Mod。"), 10);
            return block;
        }
        LinearLayout list = vertical();
        list.setBackground(surface(PANEL, LINE, 13));
        for (int i = 0; i < gameMods.size(); i++) {
            LiveArchive.GameMod mod = gameMods.get(i);
            String ver = mod.version().isEmpty() ? "" : "v" + mod.version() + "  ·  ";
            String detail = ver + mod.id() + "  ·  " + size(mod.bytes());
            String tag = mod.prepared() ? "已就绪" : "待启动转换";
            list.addView(record("box", mod.title(), detail, tag, () -> gameModActions(mod)));
            if (i < gameMods.size() - 1) list.addView(rule());
        }
        add(block, list, 10);
        return block;
    }

    private View modLibrary() {
        LinearLayout block = vertical();
        heading(block, "本地模组压缩库", mods.size() + " 个压缩包");
        if (mods.isEmpty()) {
            add(block, empty("box", "还没有收录模组", "点击「导入 Mod ZIP」收纳模组包，随后可一键部署到游戏目录。"), 10);
            return block;
        }
        LinearLayout list = vertical();
        list.setBackground(surface(PANEL, LINE, 13));
        for (int i = 0; i < mods.size(); i++) {
            ArchiveStore.Entry entry = mods.get(i);
            String second = size(entry.bytes()) + "  ·  " + entry.count() + " 个模组";
            list.addView(record("box", entry.title(), second, "安装", () -> modDetails(entry)));
            if (i < mods.size() - 1) list.addView(rule());
        }
        add(block, list, 10);
        return block;
    }

    private View displayPage(boolean wide) {
        LinearLayout block = vertical();
        if (!liveDisplayError.isEmpty() || displayInfo == null) {
            add(block, empty("screen", "正在探测屏幕参数", liveDisplayError), 0);
            add(block, button("刷新画面参数", true, () -> refreshLive(true)), 12);
            return block;
        }

        LiveArchive.DisplayInfo info = displayInfo;
        String screenRatio = DisplayConfig.aspectTag(info.screenW(), info.screenH());
        String curRatio = DisplayConfig.aspectTag(info.width(), info.height());
        String modeLabel = info.fillScreen() ? "全屏铺满（去黑边）" : "等比清晰（原比例）";
        String supportedStr = formatSupportedRates(info.supportedRates());

        // Top hardware + active state card
        LinearLayout summary = vertical();
        summary.setPadding(dp(18), dp(16), dp(18), dp(16));
        summary.setBackground(surface(PANEL, LINE, 13));
        summary.addView(type("全机型分辨率与高刷适配", 17, INK, Typeface.DEFAULT_BOLD));
        add(summary, type("物理屏幕：" + info.screenW() + " × " + info.screenH() + "（" + screenRatio + "）  ·  面板支持：" + supportedStr,
                13, MUTED, Typeface.DEFAULT), 6);
        add(summary, type("当前输出：" + info.width() + " × " + info.height() + "（" + curRatio + "）  ·  "
                + refreshLabel(info.refreshRate(), info.effectiveFps()) + "  ·  " + modeLabel,
                14, GOLD, Typeface.DEFAULT_BOLD), 5);
        add(summary, type("修改分辨率或刷新率后，点击底部「保存并重启游戏」即可立即拉起新配置的 :nx 进程。",
                12, MUTED, Typeface.DEFAULT), 6);
        block.addView(summary);

        // High Refresh Rate Card (60 / 90 / 120 Hz / Auto Max)
        LinearLayout fpsCard = vertical();
        fpsCard.setPadding(dp(16), dp(14), dp(16), dp(14));
        fpsCard.setBackground(surface(PANEL, LINE, 13));
        LinearLayout fpsHead = horizontal();
        fpsHead.setGravity(Gravity.CENTER_VERTICAL);
        fpsHead.addView(type("高刷帧率适配（60 / 90 / 120 Hz）", 16, INK, Typeface.DEFAULT_BOLD),
                new LinearLayout.LayoutParams(0, -2, 1));
        TextView maxBadge = type("本机最高 " + info.maxRefreshRate() + " Hz", 11, GREEN, Typeface.DEFAULT_BOLD);
        maxBadge.setPadding(dp(8), dp(3), dp(8), dp(3));
        maxBadge.setBackground(surface(0xff262f23, LINE, 6));
        fpsHead.addView(maxBadge);
        fpsCard.addView(fpsHead);

        add(fpsCard, type("同步接管窗口 Display.Mode、Surface.setFrameRate 与 libddnx.so 原生控帧上限，解除原包 60 Hz 锁帧。",
                12, MUTED, Typeface.DEFAULT), 5);

        int[] fpsValues = {60, 90, 120, 0};
        String[] fpsTitles = {"60 Hz", "90 Hz", "120 Hz", "自动最高"};
        String[] fpsSubs = {
                "原版稳帧 · 低功耗",
                "高刷流畅 · 均衡",
                "极致丝滑 · 旗舰",
                "匹配本机 " + info.maxRefreshRate() + " Hz"
        };
        LinearLayout fpsGrid = horizontal();
        for (int i = 0; i < fpsValues.length; i++) {
            final int target = fpsValues[i];
            boolean active = info.refreshRate() == target;
            boolean supported = (target == 0) || (target <= info.maxRefreshRate() + 2);
            LinearLayout tile = vertical();
            tile.setGravity(Gravity.CENTER);
            tile.setPadding(dp(6), dp(10), dp(6), dp(10));
            tile.setBackground(pressable(active ? RED : RAISED, active ? GOLD : LINE, 10));
            tile.setOnClickListener(v -> applyDisplay(info.width(), info.height(), info.fillScreen(), info.avoidCutout(), target));
            tile.addView(type(fpsTitles[i], 14, active ? INK : (supported ? GOLD : MUTED), Typeface.DEFAULT_BOLD));
            TextView sub = type(fpsSubs[i], 10, active ? INK : MUTED, Typeface.DEFAULT);
            sub.setGravity(Gravity.CENTER);
            add(tile, sub, 3);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
            if (i + 1 < fpsValues.length) lp.setMarginEnd(dp(6));
            fpsGrid.addView(tile, lp);
        }
        add(fpsCard, fpsGrid, 10);

        if (info.refreshRate() > info.maxRefreshRate() + 2) {
            add(fpsCard, type("提示：所选 " + info.refreshRate() + " Hz 高于当前探测到的屏幕上限（"
                    + info.maxRefreshRate() + " Hz），系统将自动回落至最接近的可用刷新率；若系统锁帧请在手机「显示与亮度」中开启高刷新率。",
                    12, GOLD, Typeface.DEFAULT), 8);
        }
        add(block, fpsCard, 12);

        // Scaling mode & cutout toggle
        LinearLayout modeRow = horizontal();
        weighted(modeRow, button(info.fillScreen() ? "等比清晰（原比例）" : "✓ 等比清晰（原比例）", !info.fillScreen(),
                () -> applyDisplay(info.width(), info.height(), false, info.avoidCutout(), info.refreshRate())), true);
        weighted(modeRow, button(info.fillScreen() ? "✓ 全屏铺满（去黑边）" : "全屏铺满（去黑边）", info.fillScreen(),
                () -> applyDisplay(info.width(), info.height(), true, info.avoidCutout(), info.refreshRate())), false);
        add(block, modeRow, 12);

        CheckBox cutout = new CheckBox(this);
        cutout.setText("避让挖孔 / 刘海安全区（推荐在全面屏手机开启）");
        cutout.setTextColor(INK);
        cutout.setChecked(info.avoidCutout());
        cutout.setOnCheckedChangeListener((b, checked) ->
                applyDisplay(info.width(), info.height(), info.fillScreen(), checked, info.refreshRate()));
        add(block, cutout, 8);

        LinearLayout resBlock = vertical();
        heading(resBlock, "本机推荐清晰档位", "运行时自动计算");
        List<DisplayConfig.Preset> presetsList = DisplayConfig.presetsForDevice(info.screenW(), info.screenH());
        LinearLayout list = vertical();
        list.setBackground(surface(PANEL, LINE, 13));
        for (int i = 0; i < presetsList.size(); i++) {
            DisplayConfig.Preset p = presetsList.get(i);
            boolean selected = p.width == info.width() && p.height == info.height();
            list.addView(record("screen", p.width + " × " + p.height + " · " + p.title, p.badge,
                    selected ? "当前" : "切换",
                    () -> applyDisplay(p.width, p.height, info.fillScreen(), info.avoidCutout(), info.refreshRate())));
            if (i < presetsList.size() - 1) list.addView(rule());
        }
        add(resBlock, list, 10);
        add(resBlock, button("保存并重启游戏", true, () -> {
            live.restartGame();
        }), 12);
        add(block, resBlock, 18);
        return block;
    }

    private static String refreshLabel(int pref, int effective) {
        if (pref == 0) return "自动高刷 (" + effective + " Hz)";
        return pref + " Hz" + (pref >= 90 ? " 高刷" : "");
    }

    private static String formatSupportedRates(int[] rates) {
        if (rates == null || rates.length == 0) return "60 Hz";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rates.length; i++) {
            if (i > 0) sb.append(" / ");
            sb.append(rates[i]);
        }
        return sb.append(" Hz").toString();
    }

    private void applyDisplay(int width, int height, boolean fillScreen, boolean avoidCutout, int refreshRate) {
        perform("正在写入画面与刷新率配置…", () -> live.setDisplay(width, height, fillScreen, avoidCutout, refreshRate), updated -> {
            displayInfo = updated;
            noteRestore = true;
            message("已设为 " + updated.width() + "×" + updated.height()
                    + " · " + refreshLabel(updated.refreshRate(), updated.effectiveFps())
                    + (updated.fillScreen() ? "（全屏铺满）" : "（等比清晰）") + "。点击「保存并重启游戏」立即生效。");
        });
    }

    private View restoreGuide() {
        LinearLayout block = vertical();
        heading(block, "一键回档", "");
        add(block, empty("restore", "直连原子回档",
                "主进程与 :nx 游戏进程共用同一 UID。点开任意历史版本选「回档到此版本」，启动器会自动停止游戏进程、先留存当前进度快照再完成原子恢复。"), 10);
        return block;
    }

    private View guide() {
        LinearLayout block = vertical();
        heading(block, "Steam 转安卓与一体化使用指南", "");
        add(block, guideItem("flame", "1. 准备外置数据包 (dd_assets.pak)",
                "在 PC 上运行 dd-steam-converter，指向你的正版 Steam《暗黑地牢》目录与基础运行壳 APK，生成单文件 dd_assets.pak。"), 10);
        add(block, guideItem("box", "2. 推送或导入数据包",
                "通过 USB/文件管理器放到 Android/media/" + getPackageName() + "/dd_assets.pak，或在「启动」页选择导入（也支持同工具生成的 steam-migration.zip）。"), 8);
        add(block, guideItem("wrench", "3. 完整修改器安全规范",
                "「修改」页提供全维度存档编辑（货币/全英雄/怪癖疾病/饰品/建筑满级/原始 DSON 树）与非破坏性规则修改器。所有写档均强制先生成备份快照，不怕误改。"), 8);
        add(block, guideItem("screen", "4. 原版内置应急工具",
                "如需手动触发原版 Wicked 内置的 Mod 贴图预转换调试页，可从下方按钮进入。"), 8);
        add(block, button("打开原版内置工具页 (ru.darkest.port.MainActivity)", false, () -> {
            try {
                live.launchLegacyTools();
            } catch (Exception err) {
                error("无法打开原版工具页：" + err.getMessage());
            }
        }), 12);
        return block;
    }

    private void openSlotModEditor(LiveArchive.SlotMods slot) {
        if (busy) return;
        Map<String, LiveArchive.GameMod> installedMap = new HashMap<>();
        for (LiveArchive.GameMod gm : gameMods) installedMap.put(gm.id(), gm);

        List<String> enabledOrder = new ArrayList<>();
        Set<String> enabledSet = new HashSet<>();
        for (LiveArchive.SlotMod sm : slot.mods()) {
            if (enabledSet.add(sm.name())) enabledOrder.add(sm.name());
        }
        List<String> allIds = new ArrayList<>(enabledOrder);
        for (LiveArchive.GameMod gm : gameMods) {
            if (!enabledSet.contains(gm.id())) allIds.add(gm.id());
        }

        LinearLayout container = vertical();
        container.setPadding(dp(16), dp(10), dp(16), dp(6));

        LinearLayout tools = horizontal();
        Button btnAll = (Button) button("全选本地", false, () -> {});
        Button btnSavePreset = (Button) button("存为预设", false, () -> {});
        Button btnApplyPreset = (Button) button("套用预设", false, () -> {});
        LinearLayout.LayoutParams tp1 = new LinearLayout.LayoutParams(0, -2, 1);
        tp1.setMarginEnd(dp(6));
        LinearLayout.LayoutParams tp2 = new LinearLayout.LayoutParams(0, -2, 1);
        tp2.setMarginEnd(dp(6));
        tools.addView(btnAll, tp1);
        tools.addView(btnSavePreset, tp2);
        tools.addView(btnApplyPreset, new LinearLayout.LayoutParams(0, -2, 1));
        container.addView(tools);

        TextView hint = type("排在越靠上的模组优先级越高（扩展包与修改器应排在主包上方）。保存时自动转为本地 mod 标识并先备份当前档位。", 12, MUTED, Typeface.DEFAULT);
        add(container, hint, 8);

        ScrollView listScroll = new ScrollView(this);
        LinearLayout listLayout = vertical();
        listScroll.addView(listLayout);
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(-1, dp(340));
        scrollLp.topMargin = dp(8);
        container.addView(listScroll, scrollLp);

        Runnable[] renderRef = new Runnable[1];
        renderRef[0] = () -> {
            listLayout.removeAllViews();
            if (allIds.isEmpty()) {
                listLayout.addView(type("当前没有已安装的模组。", 14, MUTED, Typeface.DEFAULT));
                return;
            }
            for (int i = 0; i < allIds.size(); i++) {
                final int idx = i;
                final String modId = allIds.get(i);
                final boolean checked = enabledSet.contains(modId);
                LiveArchive.GameMod meta = installedMap.get(modId);
                String title = meta != null ? meta.title() : (modId + "（未安装）");

                LinearLayout row = horizontal();
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(4), dp(6), dp(4), dp(6));

                CheckBox cb = new CheckBox(this);
                cb.setChecked(checked);
                cb.setText(title);
                cb.setTextColor(meta != null ? INK : ERROR);
                cb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                cb.setOnCheckedChangeListener((btn, isChecked) -> {
                    if (isChecked) enabledSet.add(modId);
                    else enabledSet.remove(modId);
                });
                row.addView(cb, new LinearLayout.LayoutParams(0, -2, 1));

                Button up = smallBtn("↑", idx > 0, () -> {
                    Collections.swap(allIds, idx, idx - 1);
                    renderRef[0].run();
                });
                Button down = smallBtn("↓", idx < allIds.size() - 1, () -> {
                    Collections.swap(allIds, idx, idx + 1);
                    renderRef[0].run();
                });
                LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(40), dp(36));
                blp.setMarginStart(dp(4));
                row.addView(up, blp);
                row.addView(down, blp);

                listLayout.addView(row);
                if (i < allIds.size() - 1) listLayout.addView(rule());
            }
        };
        renderRef[0].run();

        btnAll.setOnClickListener(v -> {
            for (LiveArchive.GameMod gm : gameMods) {
                if (!allIds.contains(gm.id())) allIds.add(gm.id());
                enabledSet.add(gm.id());
            }
            renderRef[0].run();
        });

        btnSavePreset.setOnClickListener(v -> {
            List<String> chosen = new ArrayList<>();
            for (String id : allIds) if (enabledSet.contains(id)) chosen.add(id);
            promptSavePreset(chosen);
        });

        btnApplyPreset.setOnClickListener(v -> {
            if (presets.isEmpty()) {
                error("还没有已保存的模组预设。");
                return;
            }
            String[] names = new String[presets.size()];
            for (int i = 0; i < presets.size(); i++) {
                names[i] = presets.get(i).name() + "（" + presets.get(i).modIds().size() + " 个）";
            }
            new AlertDialog.Builder(this).setTitle("选择要套用的预设")
                    .setItems(names, (d, which) -> {
                        ArchiveStore.ModPreset preset = presets.get(which);
                        enabledSet.clear();
                        allIds.clear();
                        for (String id : preset.modIds()) {
                            if (enabledSet.add(id)) allIds.add(id);
                        }
                        for (LiveArchive.GameMod gm : gameMods) {
                            if (!enabledSet.contains(gm.id())) allIds.add(gm.id());
                        }
                        renderRef[0].run();
                    }).setNegativeButton("取消", null).show();
        });

        new AlertDialog.Builder(this)
                .setTitle(slot.label() + " · 模组启用与排序")
                .setView(container)
                .setPositiveButton("保存并启动游戏", (dialog, which) -> saveSlotMods(slot, allIds, enabledSet, true))
                .setNeutralButton("仅保存配置", (dialog, which) -> saveSlotMods(slot, allIds, enabledSet, false))
                .setNegativeButton("取消", null)
                .show();
    }

    private void saveSlotMods(LiveArchive.SlotMods slot, List<String> allIds, Set<String> enabledSet, boolean launch) {
        List<LiveArchive.SlotMod> result = new ArrayList<>();
        for (String id : allIds) {
            if (enabledSet.contains(id)) result.add(new LiveArchive.SlotMod(id, "mod"));
        }
        perform("正在备份档位并写入模组配置…", () -> {
            live.setSlotMods(slot.slot(), result);
            return fetchLiveSnapshot();
        }, snap -> {
            applyLiveSnapshot(snap);
            if (launch) {
                live.launchGame();
            } else {
                noteRestore = true;
                message("已更新 " + slot.label() + "（启用 " + result.size() + " 个模组，原档已快照）。");
            }
        });
    }

    private Button smallBtn(String label, boolean enabled, Runnable click) {
        Button b = new Button(this);
        b.setText(label);
        b.setEnabled(enabled);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setTextColor(enabled ? GOLD : MUTED);
        b.setPadding(0, 0, 0, 0);
        b.setMinimumWidth(0);
        b.setMinimumHeight(0);
        b.setBackground(pressable(RAISED, LINE, 8));
        b.setOnClickListener(v -> click.run());
        return b;
    }

    private void promptSavePreset(List<String> modIds) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("例如：日出群岛+全皮肤");
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(60)});
        FrameLayout box = new FrameLayout(this);
        box.setPadding(dp(24), dp(8), dp(24), 0);
        box.addView(input, new FrameLayout.LayoutParams(-1, -2));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("保存为模组预设（" + modIds.size() + " 个）")
                .setView(box).setPositiveButton("保存", null).setNegativeButton("取消", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String name = input.getText().toString().trim();
            if (name.isEmpty()) { input.setError("请输入预设名称"); return; }
            dialog.dismiss();
            perform("正在保存预设…", () -> store.savePreset(name, modIds), saved -> {
                updateLists();
                message("预设「" + saved.name() + "」已保存");
            });
        }));
        dialog.show();
    }

    private void presetActions(ArchiveStore.ModPreset preset) {
        List<String> actions = new ArrayList<>();
        for (LiveArchive.SlotMods slot : slotMods) actions.add("套用到 " + slot.label());
        actions.add("删除此预设");
        new AlertDialog.Builder(this).setTitle(preset.name() + "（" + preset.modIds().size() + " 个模组）")
                .setItems(actions.toArray(new String[0]), (d, choice) -> {
                    if (choice < slotMods.size()) {
                        LiveArchive.SlotMods target = slotMods.get(choice);
                        List<LiveArchive.SlotMod> payload = new ArrayList<>();
                        for (String id : preset.modIds()) payload.add(new LiveArchive.SlotMod(id, "mod"));
                        perform("正在套用预设到 " + target.label() + "…", () -> {
                            live.setSlotMods(target.slot(), payload);
                            return fetchLiveSnapshot();
                        }, snap -> {
                            applyLiveSnapshot(snap);
                            noteRestore = true;
                            message("已将「" + preset.name() + "」套用到 " + target.label() + "。");
                        });
                    } else {
                        perform("正在删除预设…", () -> { store.deletePreset(preset.id()); return preset; },
                                done -> { updateLists(); message("预设已删除"); });
                    }
                }).setNegativeButton("取消", null).show();
    }

    private void gameModActions(LiveArchive.GameMod mod) {
        String info = "目录 ID：" + mod.id()
                + (mod.version().isEmpty() ? "" : "\n版本：" + mod.version())
                + "\n格式：" + mod.format()
                + "\n文件：" + mod.files() + " 个  ·  " + size(mod.bytes())
                + "\n移植转换：" + (mod.prepared() ? "已完成（.port-switch）" : "待游戏下次启动自动转换");
        new AlertDialog.Builder(this).setTitle(mod.title())
                .setMessage(info)
                .setPositiveButton("关闭", null)
                .setNegativeButton("从游戏卸载", (d, w) -> confirmDeleteGameMod(mod))
                .show();
    }

    private void confirmDeleteGameMod(LiveArchive.GameMod mod) {
        new AlertDialog.Builder(this).setTitle("卸载模组？")
                .setMessage("将从游戏 mods 目录删除「" + mod.title() + "」（" + mod.id() + "）。此操作不会删除本地压缩库备份。")
                .setPositiveButton("确认卸载", (d, w) -> perform("正在卸载模组…", () -> {
                    live.stopGame();
                    live.deleteMod(mod.id());
                    return fetchLiveSnapshot();
                }, snap -> {
                    applyLiveSnapshot(snap);
                    message("已卸载「" + mod.title() + "」");
                }))
                .setNegativeButton("取消", null).show();
    }

    private View guideItem(String icon, String title, String detail) {
        LinearLayout panel = horizontal();
        panel.setPadding(dp(15), dp(14), dp(16), dp(14));
        panel.setBackground(surface(PANEL, LINE, 12));
        panel.addView(new Glyph(icon, GOLD), new LinearLayout.LayoutParams(dp(24), dp(24)));
        LinearLayout words = vertical();
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
        lp.setMarginStart(dp(13));
        panel.addView(words, lp);
        words.addView(type(title, 16, INK, Typeface.DEFAULT_BOLD));
        add(words, type(detail, 14, MUTED, Typeface.DEFAULT), 5);
        return panel;
    }

    private View empty(String icon, String title, String detail) {
        LinearLayout row = horizontal();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(15), dp(15), dp(15), dp(15));
        row.setBackground(surface(PANEL, LINE, 13));
        FrameLayout medallion = new FrameLayout(this);
        medallion.setBackground(surface(0xff37241f, LINE, 11));
        medallion.addView(new Glyph(icon, GOLD), new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER));
        row.addView(medallion, new LinearLayout.LayoutParams(dp(46), dp(46)));
        LinearLayout words = vertical();
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
        lp.setMarginStart(dp(13));
        row.addView(words, lp);
        words.addView(type(title, 16, INK, Typeface.DEFAULT_BOLD));
        TextView body = type(detail, 14, MUTED, Typeface.DEFAULT);
        body.setLineSpacing(dp(2), 1);
        add(words, body, 4);
        return row;
    }

    private View record(String icon, String title, String detail, String aside, Runnable action) {
        LinearLayout row = horizontal();
        row.setPadding(dp(14), dp(11), dp(14), dp(11));
        row.setMinimumHeight(dp(64));
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(pressable(Color.TRANSPARENT, 0, 12));
        row.setFocusable(true);
        row.setOnClickListener(view -> action.run());
        row.addView(new Glyph(icon, GOLD), new LinearLayout.LayoutParams(dp(22), dp(22)));
        LinearLayout words = vertical();
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
        lp.setMarginStart(dp(12));
        lp.setMarginEnd(dp(8));
        row.addView(words, lp);
        TextView name = type(title, 15, INK, Typeface.DEFAULT_BOLD);
        name.setMaxLines(2);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        words.addView(name);
        add(words, type(detail, 12, MUTED, Typeface.DEFAULT), 3);
        if (!aside.isEmpty()) {
            TextView label = type(aside, 12, GOLD, aside.startsWith("#") ? Typeface.MONOSPACE : Typeface.DEFAULT);
            row.addView(label, new LinearLayout.LayoutParams(-2, -2));
        }
        LinearLayout.LayoutParams chevron = new LinearLayout.LayoutParams(dp(16), dp(16));
        chevron.setMarginStart(dp(7));
        row.addView(new Glyph("chevron", GOLD), chevron);
        return row;
    }

    private View feedback() {
        LinearLayout row = horizontal();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(8), dp(12), dp(8));
        row.setBackground(surface(noteError ? 0xff422721 : RAISED, LINE, 12));
        if (busy) {
            ProgressBar progress = new ProgressBar(this);
            progress.setIndeterminateTintList(ColorStateList.valueOf(GOLD));
            row.addView(progress, new LinearLayout.LayoutParams(dp(20), dp(20)));
        } else row.addView(new Glyph(noteError ? "help" : "commit", noteError ? ERROR : GREEN),
                new LinearLayout.LayoutParams(dp(19), dp(19)));
        TextView message = type(note.isEmpty() ? "正在处理…" : note, 14, noteError ? ERROR : INK, Typeface.DEFAULT);
        message.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
        lp.setMarginStart(dp(10));
        row.addView(message, lp);
        if (noteRestore) row.addView(button("启动游戏", false, this::openGame));
        return row;
    }

    private void heading(LinearLayout parent, String title, String status) {
        LinearLayout line = horizontal();
        line.setGravity(Gravity.CENTER_VERTICAL);
        TextView caption = type(title, 21, INK, display);
        if (android.os.Build.VERSION.SDK_INT >= 28) caption.setAccessibilityHeading(true);
        line.addView(caption, new LinearLayout.LayoutParams(0, -2, 1));
        if (!status.isEmpty()) line.addView(type(status, 13, MUTED, Typeface.DEFAULT));
        parent.addView(line);
    }

    private void chooseSaveInput() {
        if (busy) return;
        new AlertDialog.Builder(this).setTitle("导入 / 备份存档")
                .setItems(new String[]{"选择存档文件夹 (含 Steam profile_N)", "选择存档 ZIP", "导入 Steam 一站式迁移包 (steam-migration.zip)"}, (dialog, choice) -> {
                    if (choice == 0) pickTree(SAVE_TREE);
                    else if (choice == 1) pickFile(SAVE_ZIP);
                    else pickFile(MIGRATION_ZIP);
                }).setNegativeButton("取消", null).show();
    }

    private void saveDetails(ArchiveStore.Entry entry) {
        if (busy) return;
        int index = saves.indexOf(entry);
        ArchiveStore.Entry previous = index < 0 ? null : saves.subList(index + 1, saves.size()).stream()
                .filter(other -> LiveArchive.isAutomatic(other) == LiveArchive.isAutomatic(entry))
                .filter(other -> !LiveArchive.isAutomatic(entry) || LiveArchive.slot(other).equals(LiveArchive.slot(entry)))
                .findFirst().orElse(null);
        perform("正在读取版本…", () -> store.slots(entry, previous), slots -> {
            StringBuilder summary = new StringBuilder(time(entry.date())).append("  ·  #")
                    .append(LiveArchive.displayId(entry)).append("\n来源  ").append(entry.source()).append("\n");
            if (previous != null) summary.append("相对前一版本  ").append(previous.title()).append('\n');
            for (ArchiveStore.Slot slot : slots) {
                summary.append("\n").append(slotLabel(slot.name())).append("  ·  ").append(slot.files()).append(" 个文件");
                if (previous != null) summary.append("  ·  ").append(slot.change());
            }
            new AlertDialog.Builder(this).setTitle(entry.title()).setMessage(summary)
                    .setPositiveButton("一键回档到此版本", (dialog, button) -> chooseRecovery(entry, slots))
                    .setNeutralButton("更多操作", (dialog, button) -> entryActions(entry))
                    .setNegativeButton("关闭", null).show();
        });
    }

    private void chooseRecovery(ArchiveStore.Entry entry, List<ArchiveStore.Slot> slots) {
        if (LiveArchive.isAutomatic(entry)) {
            new AlertDialog.Builder(this).setTitle("确认立即回档")
                    .setMessage("将恢复 " + slotLabel(LiveArchive.slot(entry))
                            + "。启动器会自动停止当前游戏进程、先拍摄当前档位快照，然后原子恢复选定版本。")
                    .setPositiveButton("立即回档", (dialog, button) -> perform("正在直连原子回档…", () -> {
                        live.restore(entry);
                        return fetchLiveSnapshot();
                    }, snap -> {
                        applyLiveSnapshot(snap);
                        noteRestore = true;
                        message("已完成回档！点击「启动游戏」即可载入恢复后的进度。");
                    }))
                    .setNegativeButton("取消", null).show();
            return;
        }
        List<String> choices = new ArrayList<>();
        choices.add("恢复此版本的全部档位");
        for (ArchiveStore.Slot slot : slots) choices.add("只恢复 " + slotLabel(slot.name()));
        new AlertDialog.Builder(this).setTitle("选择要回档的档位")
                .setItems(choices.toArray(new String[0]), (dialog, choice) -> confirmRecovery(entry,
                        choice == 0 ? null : slots.get(choice - 1).name()))
                .setNegativeButton("取消", null).show();
    }

    private void confirmRecovery(ArchiveStore.Entry entry, String slot) {
        String scope = slot == null ? "这个版本的全部档位" : slotLabel(slot);
        new AlertDialog.Builder(this).setTitle("准备导出恢复")
                .setMessage("将导出" + scope + "。若想保留当前进度，请先在「存档」页点「立刻备份档位」。")
                .setPositiveButton("继续", (dialog, button) -> {
                    pendingId = entry.id();
                    pendingSlot = slot;
                    String folder = prefs.getString("restore", "");
                    if (folder.isEmpty()) pickTree(RESTORE_TREE);
                    else stageRecovery(entry, Uri.parse(folder), slot);
                }).setNegativeButton("取消", null).show();
    }

    private void stageRecovery(ArchiveStore.Entry entry, Uri output, String slot) {
        perform("正在导出存档…", () -> {
            try {
                return store.exportSaves(entry, output, slot);
            } catch (Exception failure) {
                prefs.edit().remove("restore").apply();
                throw failure;
            }
        }, folder -> {
            noteRestore = true;
            message("已导出到「" + folder + "」。");
        });
    }

    void modDetails(ArchiveStore.Entry entry) {
        new AlertDialog.Builder(this).setTitle(entry.title())
                .setMessage(entry.source() + "\n" + size(entry.bytes()) + "  ·  " + entry.count() + " 个模组\n\n可直接安装到游戏 mods 目录，启动游戏自动转换生效。")
                .setPositiveButton("直接安装到游戏", (d, b) -> perform("正在解压并安装模组…", () -> {
                    int count;
                    try (InputStream in = store.openPayload(entry)) {
                        count = live.installMod(in, store.zipName(entry));
                    }
                    return new Object[]{count, fetchLiveSnapshot()};
                }, res -> {
                    int count = (Integer) res[0];
                    applyLiveSnapshot((LiveSnapshot) res[1]);
                    noteRestore = true;
                    message("已安装 " + count + " 个模组。请在「本地模组 → 档位模组配置」中勾选启用。");
                }))
                .setNeutralButton("更多操作", (d, b) -> entryActions(entry))
                .setNegativeButton("关闭", null)
                .show();
    }

    private void entryActions(ArchiveStore.Entry entry) {
        if (LiveArchive.isAutomatic(entry)) {
            chooseZipOut(entry);
            return;
        }
        boolean mod = ArchiveStore.MOD.equals(entry.kind());
        String[] items = mod ? new String[]{"导出模组 ZIP", "重命名", "删除本地副本", "查看包内文件"}
                : new String[]{"导出版本 ZIP", "重命名", "删除本地副本"};
        new AlertDialog.Builder(this).setTitle(entry.title()).setItems(items, (dialog, choice) -> {
            if (choice == 0) chooseZipOut(entry);
            if (choice == 1) rename(entry);
            if (choice == 2) remove(entry);
            if (choice == 3) showModFiles(entry);
        }).setNegativeButton("取消", null).show();
    }

    private void showModFiles(ArchiveStore.Entry entry) {
        perform("正在读取压缩包目录…", () -> store.modFiles(entry), files -> {
            android.widget.ListView list = new android.widget.ListView(this);
            list.setDivider(new android.graphics.drawable.ColorDrawable(LINE));
            list.setDividerHeight(dp(1));
            list.setAdapter(new android.widget.ArrayAdapter<ArchiveStore.ModFile>(
                    this, android.R.layout.simple_list_item_1, files) {
                @Override public View getView(int position, View recycled, android.view.ViewGroup parent) {
                    TextView line = recycled instanceof TextView ? (TextView) recycled
                            : type("", 14, INK, Typeface.DEFAULT);
                    ArchiveStore.ModFile file = getItem(position);
                    line.setText(file.path() + "  ·  " + (file.bytes() < 1024 ? file.bytes() + " B" : size(file.bytes())));
                    line.setPadding(dp(20), dp(11), dp(20), dp(11));
                    return line;
                }
            });
            new AlertDialog.Builder(this).setTitle("包内文件 · " + files.size() + " 个").setView(list)
                    .setNegativeButton("关闭", null).show();
        });
    }

    private void rename(ArchiveStore.Entry entry) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(entry.title());
        input.setSelectAllOnFocus(true);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(90)});
        FrameLayout container = new FrameLayout(this);
        container.setPadding(dp(24), dp(8), dp(24), 0);
        container.addView(input, new FrameLayout.LayoutParams(-1, -2));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("重命名").setView(container)
                .setPositiveButton("保存", null).setNegativeButton("取消", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String label = input.getText().toString().trim();
            if (label.isEmpty()) { input.setError("请输入名称"); return; }
            dialog.dismiss();
            perform("正在保存名称…", () -> { store.rename(entry, label); return label; },
                    done -> { updateLists(); message("名称已保存"); });
        }));
        dialog.show();
    }

    private void remove(ArchiveStore.Entry entry) {
        new AlertDialog.Builder(this).setTitle("删除本地副本？")
                .setMessage("将删除档案馆内的「" + entry.title() + "」。游戏内的存档仍会保留。")
                .setPositiveButton("删除", (dialog, which) -> perform("正在删除本地副本…",
                        () -> { store.remove(entry); return entry; }, done -> { updateLists(); message("本地副本已删除"); }))
                .setNegativeButton("取消", null).show();
    }

    private void pickTree(int request) {
        if (busy) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(READ | WRITE | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        launchPicker(intent, request);
    }

    private void pickFile(int request) {
        if (busy) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*").addFlags(READ);
        launchPicker(intent, request);
    }

    private void chooseZipOut(ArchiveStore.Entry entry) {
        pendingId = entry.id();
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/zip").putExtra(Intent.EXTRA_TITLE, store.zipName(entry))
                .addFlags(READ | WRITE);
        launchPicker(intent, ZIP_OUT);
    }

    private void launchPicker(Intent intent, int request) {
        try { startActivityForResult(intent, request); }
        catch (ActivityNotFoundException ignored) { error("找不到系统文件选择器。"); }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (request == PAK_FILE) {
            long expected = queryUriSize(uri);
            perform("正在流式导入并校验 dd_assets.pak（约需 10–30 秒）…", () -> {
                try (InputStream in = getContentResolver().openInputStream(uri)) {
                    if (in == null) throw new java.io.IOException("无法打开选中的文件");
                    PakMount.importPak(this, in, expected, null);
                }
                return fetchLiveSnapshot();
            }, snap -> {
                applyLiveSnapshot(snap);
                message("dd_assets.pak 导入并校验成功！现在可以点击「点亮火把 · 开始游戏」。");
            });
        } else if (request == MIGRATION_ZIP) {
            perform("正在导入 Steam 迁移包（存档 + Mod）…", () -> {
                ModBridge.MigrationResult res;
                try (InputStream in = getContentResolver().openInputStream(uri)) {
                    if (in == null) throw new java.io.IOException("无法打开迁移包文件");
                    res = live.importSteamMigration(in);
                }
                return new Object[]{res, fetchLiveSnapshot()};
            }, pair -> {
                ModBridge.MigrationResult res = (ModBridge.MigrationResult) pair[0];
                applyLiveSnapshot((LiveSnapshot) pair[1]);
                noteRestore = true;
                message("Steam 迁移完成：已导入 " + res.slotsImported + " 个存档位、" + res.modsInstalled + " 个模组（已自动转为本地 mod 标识）。");
            });
        } else if (request == SAVE_TREE || request == SAVE_ZIP) {
            perform("正在建立存档版本…", () -> store.importSave(uri, request == SAVE_TREE),
                    saved -> { updateLists(); message("已收录存档版本 · " + saved.count() + " 个档位"); });
        } else if (request == MOD_ZIP) {
            perform("正在检查模组结构…", () -> store.importMod(uri),
                    saved -> { updateLists(); message("模组已收录 · " + saved.count() + " 个（点开条目可直接安装到游戏）"); });
        } else if (request == ZIP_OUT || request == RESTORE_TREE) {
            ArchiveStore.Entry entry = findEntry(pendingId);
            if (entry == null) { error("找不到要导出的档案。"); return; }
            if (request == ZIP_OUT) {
                perform("正在导出 ZIP…", () -> { store.exportZip(entry, uri); return entry; },
                        saved -> message("ZIP 已保存到你选择的位置"));
            } else {
                if (persist(uri, data, READ | WRITE)) prefs.edit().putString("restore", uri.toString()).apply();
                stageRecovery(entry, uri, pendingSlot);
            }
        }
    }

    private long queryUriSize(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getLong(0);
        } catch (Exception ignored) {
        }
        return -1L;
    }

    private boolean persist(Uri uri, Intent data, int desired) {
        if ((data.getFlags() & desired) != desired) return false;
        try {
            if (desired == READ) getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            else getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            return true;
        } catch (SecurityException ignored) { return false; }
    }

    private ArchiveStore.Entry findEntry(String id) {
        for (ArchiveStore.Entry entry : saves) if (entry.id().equals(id)) return entry;
        for (ArchiveStore.Entry entry : mods) if (entry.id().equals(id)) return entry;
        return null;
    }

    private void openGame() {
        PakMount.PakInfo pak = pakInfo != null ? pakInfo : live.pakInfo();
        if (!pak.ready) {
            error("尚未检测到游戏数据包 (dd_assets.pak)。请先在「启动」页导入数据包，或通过 ADB 推送到 Android/media/" + getPackageName() + "/dd_assets.pak。");
            return;
        }
        try {
            live.launchGame();
        } catch (Exception err) {
            error("无法拉起游戏进程（请确认是否已执行 thin-shell 合包构建）：" + err.getMessage());
        }
    }

    private void updateLists() {
        saves = store.list(ArchiveStore.SAVE);
        saves.addAll(automatic);
        saves.sort(Comparator.comparingLong(ArchiveStore.Entry::date).reversed());
        mods = store.list(ArchiveStore.MOD);
        presets = store.listPresets();
    }

    // ---- ModifierSection.Host callbacks ----

    @Override public void runTask(String progress, ModifierSection.WorkTask task, String successMessage) {
        perform(progress, () -> {
            task.run();
            return fetchLiveSnapshot();
        }, snap -> {
            applyLiveSnapshot(snap);
            if (successMessage != null && !successMessage.isEmpty()) {
                noteRestore = true;
                message(successMessage);
            }
        });
    }

    @Override public void showMessage(String msg, boolean isError) {
        if (isError) error(msg);
        else message(msg);
    }

    @Override public void requestRedraw() {
        redraw();
    }

    @Override public void scrollToTop() {
        scrollY = 0;
        if (scroll != null) scroll.scrollTo(0, 0);
    }

    @Override public void launchOrRestartGame() {
        if (gameRunning) live.restartGame();
        else openGame();
    }

    @Override public View makeButton(String text, boolean primary, Runnable onClick) {
        return button(text, primary, onClick);
    }

    @Override public TextView makeType(String text, float sp, int color, Typeface face) {
        return type(text, sp, color, face);
    }

    @Override public GradientDrawable makeSurface(int fill, int border, int radiusDp) {
        return surface(fill, border, radiusDp);
    }

    private interface Work<T> { T call() throws Exception; }
    private interface Done<T> { void accept(T item); }

    private <T> void perform(String progress, Work<T> work, Done<T> done) {
        if (busy) return;
        busy = true;
        note = progress;
        noteError = false;
        noteRestore = false;
        redraw();
        IO.execute(() -> {
            try {
                T result = work.call();
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    busy = false;
                    note = "";
                    redraw();
                    done.accept(result);
                });
            } catch (Exception ex) {
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    busy = false;
                    error(readable(ex));
                });
            }
        });
    }

    private void message(String text) {
        note = text;
        noteError = false;
        redraw();
    }

    private void error(String text) {
        noteRestore = false;
        note = text;
        noteError = true;
        redraw();
    }

    private String readable(Exception ex) {
        if (ex instanceof SecurityException) return "无法访问该位置。请在文件选择器重新授权。";
        String detail = ex.getMessage();
        if (detail != null && !detail.isEmpty() && detail.codePoints().anyMatch(ch -> ch >= 0x4e00 && ch <= 0x9fff)) return detail;
        if (ex instanceof java.util.zip.ZipException) return "ZIP/PAK 读取失败。请确认文件完整且未损坏。";
        return detail == null || detail.isEmpty() ? "操作失败，请检查文件是否完整或存储空间是否充足。" : detail;
    }

    TextView button(String value, boolean primary, Runnable click) {
        Button button = new Button(this);
        button.setText(value);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        button.setTextColor(primary ? INK : GOLD);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setAllCaps(false);
        button.setStateListAnimator(null);
        button.setMinimumWidth(0);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(44));
        button.setPadding(dp(12), dp(7), dp(12), dp(7));
        button.setFocusable(true);
        button.setBackground(pressable(primary ? RED : RAISED, primary ? 0xffa35445 : LINE, 10));
        button.setOnClickListener(view -> { if (!busy) click.run(); });
        return button;
    }

    TextView segment(String label, boolean selected, Runnable click) {
        TextView view = button(label, false, click);
        view.setPadding(dp(6), dp(6), dp(6), dp(6));
        styleSegment(view, selected);
        return view;
    }

    void styleSegment(TextView view, boolean selected) {
        view.setSelected(selected);
        view.setTextColor(selected ? INK : MUTED);
        view.setBackground(pressable(selected ? 0xff392923 : Color.TRANSPARENT,
                selected ? 0xff92684d : 0, 8));
    }

    View glyph(String kind, int color) {
        return new Glyph(kind, color);
    }

    TextView type(String value, float size, int color, Typeface face) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        text.setTextColor(color);
        text.setTypeface(face);
        text.setIncludeFontPadding(true);
        return text;
    }

    void weighted(LinearLayout row, View view, boolean first) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
        if (first) lp.setMarginEnd(dp(9));
        row.addView(view, lp);
    }

    void add(LinearLayout parent, View child, int gap) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(gap);
        parent.addView(child, lp);
    }

    LinearLayout vertical() {
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.VERTICAL);
        return line;
    }

    LinearLayout horizontal() { return new LinearLayout(this); }

    private View rule() {
        View rule = new View(this);
        rule.setBackgroundColor(LINE);
        rule.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(1)));
        return rule;
    }

    GradientDrawable surface(int fill, int border, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(dp(radius));
        if (border != 0) shape.setStroke(dp(1), border);
        return shape;
    }

    RippleDrawable pressable(int fill, int border, int radius) {
        return new RippleDrawable(ColorStateList.valueOf(0x45f6b778), surface(fill, border, radius),
                surface(Color.WHITE, 0, radius));
    }

    @Override public int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    static String time(long date) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(new Date(date));
    }

    static String size(long bytes) {
        if (bytes >= 1024L * 1024 * 1024) return String.format(Locale.ROOT, "%.2f GB", bytes / 1073741824.0);
        if (bytes >= 1024 * 1024) return String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0);
        return String.format(Locale.ROOT, "%.0f KB", Math.ceil(bytes / 1024.0));
    }

    private static String slotLabel(String id) { return "档位 " + (Integer.parseInt(id.substring(8)) + 1); }

    private final class Glyph extends View {
        private final Paint pen = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path reusablePath = new Path();
        private final String kind;

        Glyph(String kind, int tint) {
            super(MainActivity.this);
            this.kind = kind;
            pen.setColor(tint);
            pen.setStyle(Paint.Style.STROKE);
            pen.setStrokeWidth(1.6f);
            pen.setStrokeCap(Paint.Cap.ROUND);
            pen.setStrokeJoin(Paint.Join.ROUND);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.save();
            canvas.scale(getWidth() / 24f, getHeight() / 24f);
            switch (kind) {
                case "flame" -> {
                    reusablePath.reset();
                    reusablePath.moveTo(12, 3);
                    reusablePath.cubicTo(16, 7.5f, 18.5f, 11, 18.5f, 14.5f);
                    reusablePath.cubicTo(18.5f, 18.2f, 15.6f, 21, 12, 21);
                    reusablePath.cubicTo(8.4f, 21, 5.5f, 18.2f, 5.5f, 14.5f);
                    reusablePath.cubicTo(5.5f, 11, 8.2f, 8.2f, 12, 3);
                    canvas.drawPath(reusablePath, pen);
                    canvas.drawCircle(12, 15.5f, 2.2f, pen);
                }
                case "wrench" -> {
                    line(canvas, 14.7f, 6.3f, 17.7f, 3.3f, 20.7f, 6.3f, 17.7f, 9.3f);
                    line(canvas, 15.5f, 8.5f, 6.5f, 17.5f, 4.5f, 19.5f, 6.5f, 17.5f);
                    canvas.drawCircle(6, 18, 2.2f, pen);
                }
                case "book" -> {
                    line(canvas, 12,5, 8,3.5f, 3,4.5f, 3,19, 8,18, 12,20, 16,18, 21,19, 21,4.5f, 16,3.5f, 12,5, 12,20);
                    line(canvas, 6,8, 9,8.7f); line(canvas, 15,8.7f, 18,8);
                }
                case "box" -> {
                    line(canvas, 3,7, 12,3, 21,7, 21,17, 12,21, 3,17, 3,7, 12,11, 21,7);
                    line(canvas, 12,11, 12,21); line(canvas, 7.5f,5, 16.5f,9, 16.5f,13);
                }
                case "screen" -> {
                    line(canvas, 3,5, 21,5, 21,16, 3,16, 3,5);
                    line(canvas, 9,20, 15,20);
                    line(canvas, 12,16, 12,20);
                }
                case "commit" -> {
                    line(canvas, 12,2, 12,6); line(canvas, 12,18, 12,22);
                    line(canvas, 12,6, 18,12, 12,18, 6,12, 12,6);
                    canvas.drawCircle(12, 12, 1.3f, pen);
                }
                case "restore" -> {
                    canvas.drawArc(4,4,21,21,207,292,false,pen);
                    line(canvas, 3,5, 3,10, 8,10); line(canvas, 12,8, 12,13, 16,15);
                }
                case "export" -> {
                    line(canvas, 4,13, 4,20, 20,20, 20,13);
                    line(canvas, 12,15, 12,3, 8,7); line(canvas, 12,3, 16,7);
                }
                case "chevron" -> line(canvas, 8,5, 15,12, 8,19);
                case "search" -> {
                    canvas.drawCircle(10, 10, 5.5f, pen);
                    line(canvas, 14,14, 20,20);
                }
                default -> {
                    canvas.drawCircle(12, 12, 9, pen);
                    canvas.drawArc(8,6,16,14,190,210,false,pen);
                    line(canvas, 14,12, 12,14);
                    canvas.drawCircle(12, 17.2f, 0.75f, pen);
                }
            }
            canvas.restore();
        }

        private void line(Canvas canvas, float... points) {
            Path path = new Path();
            path.moveTo(points[0], points[1]);
            for (int i = 2; i < points.length; i += 2) path.lineTo(points[i], points[i + 1]);
            canvas.drawPath(path, pen);
        }
    }
}
