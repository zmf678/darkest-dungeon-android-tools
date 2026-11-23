package dev.torcharchive.dd;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.database.ContentObserver;
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
import android.text.InputFilter;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.EditText;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int BG = 0xff151111, PANEL = 0xff211918, RAISED = 0xff2b201e;
    private static final int INK = 0xfff5e6d4, MUTED = 0xffc1a897, GOLD = 0xffe1ab71, LINE = 0xff543c32;
    private static final int RED = 0xff733029, ERROR = 0xffffb3a3, GREEN = 0xffc5cb9e;
    private static final int SAVE_TREE = 11, SAVE_ZIP = 12, MOD_ZIP = 13, MOD_TREE = 14, ZIP_OUT = 15, RESTORE_TREE = 16;
    private static final String GAME = "com.darkestdungeon";
    private static final String[] TABS = {"存档", "模组", "指南"};
    private static final String[] NAV_ICONS = {"book", "box", "help"};
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final int READ = Intent.FLAG_GRANT_READ_URI_PERMISSION;
    private static final int WRITE = Intent.FLAG_GRANT_WRITE_URI_PERMISSION;

    private ArchiveStore store;
    private LiveArchive live;
    private SharedPreferences prefs;
    private FrameLayout outer;
    private Typeface display;
    private int tab, scrollY;
    private boolean busy, noteError, noteRestore;
    private String note = "";
    private String pendingId, pendingSlot;
    private String modFolderError = "";
    private List<ArchiveStore.Entry> saves = Collections.emptyList(), mods = Collections.emptyList();
    private List<ArchiveStore.Entry> automatic = Collections.emptyList();
    private String automaticStatus = "";
    private boolean observing;
    private final ContentObserver captureObserver = new ContentObserver(new Handler(Looper.getMainLooper())) {
        @Override public void onChange(boolean selfChange) { refreshAutomatic(); }
    };
    private List<ArchiveStore.Installed> installed = Collections.emptyList();
    private ScrollView scroll;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().setDecorFitsSystemWindows(false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarContrastEnforced(false);
        store = new ArchiveStore(this);
        live = new LiveArchive(this);
        prefs = getSharedPreferences("folders", MODE_PRIVATE);
        display = Typeface.createFromAsset(getAssets(), "display.ttf");
        if (saved != null) {
            tab = saved.getInt("tab");
            pendingId = saved.getString("pending");
            pendingSlot = saved.getString("slot");
        }
        outer = new FrameLayout(this);
        outer.setBackgroundColor(BG);
        outer.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets safe = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
            return insets;
        });
        setContentView(outer);
        reload();
    }

    @Override protected void onStart() {
        super.onStart();
        if (live.available()) {
            try {
                getContentResolver().registerContentObserver(LiveArchive.URL, false, captureObserver);
                observing = true;
            } catch (SecurityException ignored) {
                // The history query reports a certificate mismatch.
            }
        }
        refreshAutomatic();
    }

    @Override protected void onStop() {
        if (observing) {
            getContentResolver().unregisterContentObserver(captureObserver);
            observing = false;
        }
        super.onStop();
    }

    private void refreshAutomatic() {
        IO.execute(() -> {
            LiveArchive.History history = live.history();
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                automatic = history.entries();
                automaticStatus = history.status();
                updateLists();
                redraw();
            });
        });
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putInt("tab", tab);
        state.putString("pending", pendingId);
        state.putString("slot", pendingSlot);
        super.onSaveInstanceState(state);
    }

    @Override public void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config);
        redraw();
    }

    private void reload() {
        updateLists();
        redraw();
        if (prefs.contains("mods")) refreshMods(false);
    }

    private void selectTab(int value) {
        if (tab == value) return;
        tab = value;
        scrollY = 0;
        note = "";
        noteRestore = false;
        draw();
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
        int pad = dp(wide ? 32 : 20);
        page.setPadding(pad, dp(wide ? 24 : 16), pad, dp(28));
        int available = getResources().getDisplayMetrics().widthPixels - (wide ? dp(96) : 0);
        centered.addView(page, new FrameLayout.LayoutParams(Math.min(available, dp(1060)), -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        page.addView(hero(wide), new LinearLayout.LayoutParams(-1, dp(wide ? 224 : 200)));
        if (busy || !note.isEmpty()) add(page, feedback(), 14);

        if (tab == 2) {
            add(page, guide(), 24);
        } else {
            add(page, actions(), 14);
            if (wide) {
                LinearLayout columns = horizontal();
                columns.setBaselineAligned(false);
                add(page, columns, 24);
                columns.addView(tab == 0 ? history() : modLibrary(), new LinearLayout.LayoutParams(0, -2, 7));
                LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(0, -2, 4);
                right.setMarginStart(dp(24));
                columns.addView(tab == 0 ? restoreGuide() : installedMods(), right);
            } else {
                add(page, tab == 0 ? history() : modLibrary(), 24);
                if (tab == 1) add(page, installedMods(), 24);
                else add(page, restoreGuide(), 20);
            }
        }
        if (!wide) {
            shell.addView(rule());
            shell.addView(navigation(false), new LinearLayout.LayoutParams(-1, -2));
        }
        if (scrollY > 0) {
            ScrollView current = scroll;
            current.post(() -> current.scrollTo(0, scrollY));
        }
    }

    private View toolbar() {
        LinearLayout top = horizontal();
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(18), 0, dp(16), 0);
        ImageView mark = new ImageView(this);
        mark.setImageResource(R.drawable.archive_mark);
        top.addView(mark, new LinearLayout.LayoutParams(dp(38), dp(38)));
        TextView name = type("余烬档案馆", 21, INK, display);
        LinearLayout.LayoutParams nameWidth = new LinearLayout.LayoutParams(0, -2, 1);
        nameWidth.setMarginStart(dp(10));
        top.addView(name, nameWidth);
        top.addView(button(live.available() ? "打开实验版" : "打开游戏", false, this::openGame));
        return top;
    }

    private View hero(boolean wide) {
        FrameLayout frame = new FrameLayout(this);
        frame.setBackground(surface(PANEL, LINE, 14));
        frame.setForeground(surface(Color.TRANSPARENT, LINE, 14));
        frame.setClipToOutline(true);

        ImageView art = new ImageView(this);
        art.setImageResource(R.drawable.archive_illustration);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        frame.addView(art, new FrameLayout.LayoutParams(-1, -1));

        View shade = new View(this);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xf5191110, 0xe0191110, 0x30191110, 0x00191110}));
        frame.addView(shade, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout words = vertical();
        words.setPadding(dp(wide ? 28 : 20), dp(22), dp(18), dp(22));
        words.setGravity(Gravity.CENTER_VERTICAL);
        String title = tab == 0 ? "存档书库" : tab == 1 ? "模组工坊" : "使用指南";
        String detail = tab == 0 ? "为每次远征留一份检查点" : tab == 1 ? "收录模组，整理游戏目录" : "把进度和模组带回游戏";
        TextView heading = type(title, wide ? 39 : 34, INK, display);
        heading.setAccessibilityHeading(true);
        words.addView(heading);
        View accent = new View(this);
        accent.setBackgroundColor(GOLD);
        LinearLayout.LayoutParams accentParams = new LinearLayout.LayoutParams(dp(34), dp(2));
        accentParams.topMargin = dp(9);
        accentParams.bottomMargin = dp(11);
        words.addView(accent, accentParams);
        words.addView(type(detail, 15, MUTED, Typeface.DEFAULT));
        int width = dp(wide ? 430 : 280);
        frame.addView(words, new FrameLayout.LayoutParams(width, -1, Gravity.START | Gravity.CENTER_VERTICAL));
        return frame;
    }

    private View navigation(boolean rail) {
        LinearLayout nav = rail ? vertical() : horizontal();
        nav.setBackgroundColor(0xff1b1514);
        nav.setPadding(dp(rail ? 10 : 12), dp(rail ? 18 : 6), dp(rail ? 10 : 12), dp(rail ? 8 : 4));
        for (int i = 0; i < TABS.length; i++) {
            final int destination = i;
            boolean active = tab == i;
            LinearLayout item = vertical();
            item.setGravity(Gravity.CENTER);
            item.setPadding(dp(3), dp(6), dp(3), dp(6));
            item.setMinimumHeight(dp(62));
            item.setBackground(pressable(active ? 0xff39231e : Color.TRANSPARENT, 0, 13));
            item.setContentDescription(TABS[i]);
            item.setSelected(active);
            item.setFocusable(true);
            item.setOnClickListener(view -> selectTab(destination));
            item.addView(new Glyph(NAV_ICONS[i], active ? GOLD : MUTED), new LinearLayout.LayoutParams(dp(22), dp(22)));
            TextView caption = type(TABS[i], 13, active ? GOLD : MUTED, Typeface.DEFAULT_BOLD);
            caption.setGravity(Gravity.CENTER);
            add(item, caption, 2);
            LinearLayout.LayoutParams lp = rail ? new LinearLayout.LayoutParams(-1, -2)
                    : new LinearLayout.LayoutParams(0, -2, 1);
            if (rail) lp.bottomMargin = dp(8);
            else if (i < TABS.length - 1) lp.setMarginEnd(dp(8));
            nav.addView(item, lp);
        }
        return nav;
    }

    private View actions() {
        LinearLayout row = horizontal();
        if (tab == 0) {
            weighted(row, button("建立存档版本", true, this::chooseSaveInput), true);
            weighted(row, button("怎样备份", false, () -> selectTab(2)), false);
        } else {
            weighted(row, button("导入 Mod ZIP", true, () -> pickFile(MOD_ZIP)), true);
            weighted(row, button(prefs.contains("mods") ? "刷新游戏目录" : "连接游戏目录", false,
                    () -> { if (prefs.contains("mods")) refreshMods(true); else pickTree(MOD_TREE); }), false);
        }
        return row;
    }

    private View history() {
        LinearLayout block = vertical();
        heading(block, "版本历史", saves.size() + " 个版本");
        if (!automaticStatus.isEmpty()) add(block, type(automaticStatus, 13, MUTED, Typeface.DEFAULT), 8);
        if (saves.isEmpty()) {
            String hint = live.available() ? "游戏产生存档后，自动版本将显示在这里。"
                    : "可以在游戏里导出档位，再把导出的文件夹导入这里。";
            add(block, empty("book", "还没有存档版本", hint), 10);
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

    private View modLibrary() {
        LinearLayout block = vertical();
        heading(block, "本地模组", mods.size() + " 个压缩包");
        if (mods.isEmpty()) {
            add(block, empty("box", "还没有收录模组", "选择一个 ZIP。支持带 project.xml 的模组和旧版目录结构。"), 10);
            return block;
        }
        LinearLayout list = vertical();
        list.setBackground(surface(PANEL, LINE, 13));
        for (int i = 0; i < mods.size(); i++) {
            ArchiveStore.Entry entry = mods.get(i);
            String second = size(entry.bytes()) + "  ·  " + entry.count() + " 个模组";
            list.addView(record("box", entry.title(), second, "", () -> modDetails(entry)));
            if (i < mods.size() - 1) list.addView(rule());
        }
        add(block, list, 10);
        return block;
    }

    private View installedMods() {
        LinearLayout block = vertical();
        heading(block, "已安装模组", prefs.contains("mods") ? installed.size() + " 个" : "未连接");
        if (!modFolderError.isEmpty()) {
            add(block, empty("box", "读取失败", modFolderError), 10);
            add(block, button("重新连接", false, () -> pickTree(MOD_TREE)), 10);
        } else if (installed.isEmpty()) {
            String detail = prefs.contains("mods") ? "连接的目录里还没有已安装模组。"
                    : "授权 Android/media 下的游戏目录，就能查看并备份已安装的 Mod。";
            add(block, empty("box", "游戏目录", detail), 10);
        } else {
            LinearLayout list = vertical();
            list.setBackground(surface(PANEL, LINE, 13));
            for (int i = 0; i < installed.size(); i++) {
                ArchiveStore.Installed mod = installed.get(i);
                list.addView(record("box", mod.title(), mod.format(), "备份", () -> confirmArchiveMod(mod)));
                if (i < installed.size() - 1) list.addView(rule());
            }
            add(block, list, 10);
        }
        if (prefs.contains("mods") && !installed.isEmpty()) {
            add(block, type("启用状态和加载顺序在游戏的存档槽位中调整。", 13, MUTED, Typeface.DEFAULT), 10);
        }
        return block;
    }

    private View restoreGuide() {
        LinearLayout block = vertical();
        heading(block, "回档", "");
        add(block, empty("restore", "选择一个历史版本", live.available()
                ? "自动版本会在实验版游戏完全重启时恢复。手动导入的版本可以导出给游戏导入。"
                : "可恢复整个版本，也可只提取一个档位。到游戏的存档菜单导入，选定目标档位后才会生效。"), 10);
        return block;
    }

    private View guide() {
        LinearLayout block = vertical();
        heading(block, "存档与回档", "");
        if (live.available()) {
            add(block, guideItem("commit", "实验版自动存档", "正常游玩时，游戏完成保存后会记录变化的档位。打开档案馆的版本历史即可查看。"), 10);
            add(block, guideItem("restore", "恢复自动版本", "选择版本并确认回档，完全退出实验版游戏后再打开。档案馆会显示游戏确认的结果。"), 8);
        }
        add(block, guideItem("export", "从游戏导出", "游戏 → 存档与模组 → 存档 → 导出。选择一个目录，游戏会创建 DarkestDungeon saves 文件夹。"), 10);
        add(block, guideItem("commit", "建立版本", "返回档案馆，在存档页导入刚导出的文件夹或存档 ZIP。每次导入都会保留为独立版本。"), 8);
        add(block, guideItem("restore", "回到旧进度", "点开历史版本，选全部或单个档位。准备好回档目录后，在游戏的存档菜单导入该目录，再选要恢复的档位。"), 8);
        add(block, type("模组管理", 23, INK, display), 24);
        add(block, guideItem("box", "收录并导入游戏", "在模组页收录 ZIP。用“导出 ZIP”保存到设备，然后从游戏的模组菜单导入。游戏会转换部分旧版素材。"), 10);
        add(block, guideItem("link", "连接已安装目录",
                "从系统文件选择器选对应游戏在 Android/media 下的 mods 目录。各档位的启用和顺序在游戏内设置。"), 8);
        return block;
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
        row.setMinimumHeight(dp(66));
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
        TextView name = type(title, 16, INK, Typeface.DEFAULT_BOLD);
        name.setMaxLines(2);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        words.addView(name);
        add(words, type(detail, 13, MUTED, Typeface.DEFAULT), 3);
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
        TextView message = type(note.isEmpty() ? "正在准备文件…" : note, 14, noteError ? ERROR : INK, Typeface.DEFAULT);
        message.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
        lp.setMarginStart(dp(10));
        row.addView(message, lp);
        if (noteRestore) row.addView(button("打开游戏", false, this::openGame));
        return row;
    }

    private void heading(LinearLayout parent, String title, String status) {
        LinearLayout line = horizontal();
        line.setGravity(Gravity.CENTER_VERTICAL);
        TextView caption = type(title, 22, INK, display);
        caption.setAccessibilityHeading(true);
        line.addView(caption, new LinearLayout.LayoutParams(0, -2, 1));
        if (!status.isEmpty()) line.addView(type(status, 13, MUTED, Typeface.DEFAULT));
        parent.addView(line);
    }

    private void chooseSaveInput() {
        if (busy) return;
        new AlertDialog.Builder(this).setTitle("建立存档版本")
                .setItems(new String[]{"选择游戏导出的文件夹", "选择存档 ZIP"}, (dialog, choice) -> {
                    if (choice == 0) pickTree(SAVE_TREE);
                    else pickFile(SAVE_ZIP);
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
                    .setPositiveButton("回档到此版本", (dialog, button) -> chooseRecovery(entry, slots))
                    .setNeutralButton("更多操作", (dialog, button) -> entryActions(entry))
                    .setNegativeButton("关闭", null).show();
        });
    }

    private void chooseRecovery(ArchiveStore.Entry entry, List<ArchiveStore.Slot> slots) {
        if (LiveArchive.isAutomatic(entry)) {
            new AlertDialog.Builder(this).setTitle("准备回档")
                    .setMessage("将恢复" + slotLabel(LiveArchive.slot(entry))
                            + "。实验版游戏下次完全重启时会先保留当前进度，然后应用这个版本。")
                    .setPositiveButton("确认回档", (dialog, button) -> perform("正在提交回档请求…", () -> {
                        live.restore(entry);
                        return entry;
                    }, accepted -> {
                        message("已准备回档。请完全退出实验版游戏后重新打开。");
                        refreshAutomatic();
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
        new AlertDialog.Builder(this).setTitle("准备回档")
                .setMessage("将导出" + scope + "供游戏导入。若想保留现在的进度，请先将当前存档另存为一个版本。")
                .setPositiveButton("准备回档", (dialog, button) -> {
                    pendingId = entry.id();
                    pendingSlot = slot;
                    String folder = prefs.getString("restore", "");
                    if (folder.isEmpty()) pickTree(RESTORE_TREE);
                    else stageRecovery(entry, Uri.parse(folder), slot);
                }).setNegativeButton("取消", null).show();
    }

    private void stageRecovery(ArchiveStore.Entry entry, Uri output, String slot) {
        perform("正在准备回档文件…", () -> {
            try {
                return store.exportSaves(entry, output, slot);
            } catch (Exception failure) {
                prefs.edit().remove("restore").apply();
                throw failure;
            }
        }, folder -> {
            noteRestore = true;
            message("已准备回档。在游戏的存档菜单中导入文件夹「" + folder + "」。");
        });
    }

    private void modDetails(ArchiveStore.Entry entry) {
        new AlertDialog.Builder(this).setTitle(entry.title())
                .setMessage(entry.source() + "\n" + size(entry.bytes()) + "  ·  " + entry.count() + " 个模组\n\n导出 ZIP 后，在游戏的模组菜单中导入。")
                .setPositiveButton("导出 ZIP", (d,b) -> chooseZipOut(entry))
                .setNeutralButton("更多操作", (d,b) -> entryActions(entry))
                .setNegativeButton("关闭", null).show();
    }

    private void entryActions(ArchiveStore.Entry entry) {
        if (LiveArchive.isAutomatic(entry)) {
            chooseZipOut(entry);
            return;
        }
        String[] items = {ArchiveStore.SAVE.equals(entry.kind()) ? "导出版本 ZIP" : "导出模组 ZIP", "重命名", "删除本地副本"};
        new AlertDialog.Builder(this).setTitle(entry.title()).setItems(items, (dialog, choice) -> {
            if (choice == 0) chooseZipOut(entry);
            if (choice == 1) rename(entry);
            if (choice == 2) remove(entry);
        }).setNegativeButton("取消", null).show();
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
                .setMessage("将删除档案馆内的「" + entry.title() + "」。游戏内的存档和已导出的文件仍会保留。")
                .setPositiveButton("删除", (dialog, which) -> perform("正在删除本地副本…",
                        () -> { store.remove(entry); return entry; }, done -> { updateLists(); message("本地副本已删除"); }))
                .setNegativeButton("取消", null).show();
    }

    private void confirmArchiveMod(ArchiveStore.Installed mod) {
        new AlertDialog.Builder(this).setTitle(mod.title()).setMessage("把游戏目录里的这份 Mod 保存为本地 ZIP 副本。")
                .setPositiveButton("备份到档案馆", (d,b) -> perform("正在备份已安装模组…", () -> store.archiveInstalled(mod),
                        saved -> { updateLists(); message("模组备份已保存"); }))
                .setNegativeButton("取消", null).show();
    }

    private void pickTree(int request) {
        if (busy) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(READ | WRITE | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        if (request == MOD_TREE) intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI,
                ArchiveStore.modPickerLocation(live.available()));
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
        if (request == SAVE_TREE || request == SAVE_ZIP) {
            perform("正在建立存档版本…", () -> store.importSave(uri, request == SAVE_TREE),
                    saved -> { updateLists(); message("已建立版本 · " + saved.count() + " 个档位"); });
        } else if (request == MOD_ZIP) {
            perform("正在检查模组结构…", () -> store.importMod(uri),
                    saved -> { updateLists(); message("模组已收录 · " + saved.count() + " 个"); });
        } else if (request == MOD_TREE) {
            perform("正在读取游戏目录…", () -> {
                List<ArchiveStore.Installed> found = store.installed(uri);
                if (persist(uri, data, READ)) prefs.edit().putString("mods", uri.toString()).apply();
                return found;
            }, found -> { installed = found; modFolderError = ""; message("游戏目录已读取"); });
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

    private void refreshMods(boolean notify) {
        String stored = prefs.getString("mods", "");
        if (stored.isEmpty()) return;
        if (notify) {
            perform("正在刷新模组…", () -> store.installed(Uri.parse(stored)),
                    found -> { installed = found; modFolderError = ""; message("游戏目录已刷新"); });
        } else {
            IO.execute(() -> {
                try {
                    List<ArchiveStore.Installed> found = store.installed(Uri.parse(stored));
                    runOnUiThread(() -> { if (!isDestroyed()) { installed = found; modFolderError = ""; redraw(); } });
                } catch (Exception ex) {
                    runOnUiThread(() -> { if (!isDestroyed()) { installed = Collections.emptyList(); modFolderError = readable(ex); redraw(); } });
                }
            });
        }
    }

    private void openGame() {
        String packageName = live.available() ? LiveArchive.GAME : GAME;
        Intent intent = getPackageManager().getLaunchIntentForPackage(packageName);
        if (intent == null) { error("此设备上找不到游戏。"); return; }
        try { startActivity(intent); } catch (ActivityNotFoundException ignored) { error("无法打开游戏。"); }
    }

    private void updateLists() {
        saves = store.list(ArchiveStore.SAVE);
        saves.addAll(automatic);
        saves.sort(Comparator.comparingLong(ArchiveStore.Entry::date).reversed());
        mods = store.list(ArchiveStore.MOD);
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
        if (ex instanceof java.util.zip.ZipException) return "ZIP 读取失败。请确认压缩包完整且没有加密。";
        return "文件操作失败。请检查文件是否完整、所选位置是否可写、设备空间是否充足。";
    }

    private TextView button(String value, boolean primary, Runnable click) {
        Button button = new Button(this);
        button.setText(value);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        button.setTextColor(primary ? INK : GOLD);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setAllCaps(false);
        button.setStateListAnimator(null);
        button.setMinimumWidth(0);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(48));
        button.setPadding(dp(14), dp(8), dp(14), dp(8));
        button.setFocusable(true);
        button.setBackground(pressable(primary ? RED : RAISED, primary ? 0xffa35445 : LINE, 10));
        button.setOnClickListener(view -> { if (!busy) click.run(); });
        return button;
    }

    private TextView type(String value, float size, int color, Typeface face) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        text.setTextColor(color);
        text.setTypeface(face);
        text.setIncludeFontPadding(true);
        return text;
    }

    private void weighted(LinearLayout row, View view, boolean first) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
        if (first) lp.setMarginEnd(dp(9));
        row.addView(view, lp);
    }

    private void add(LinearLayout parent, View child, int gap) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(gap);
        parent.addView(child, lp);
    }

    private LinearLayout vertical() {
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.VERTICAL);
        return line;
    }

    private LinearLayout horizontal() { return new LinearLayout(this); }

    private View rule() {
        View rule = new View(this);
        rule.setBackgroundColor(LINE);
        rule.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(1)));
        return rule;
    }

    private GradientDrawable surface(int fill, int border, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(dp(radius));
        if (border != 0) shape.setStroke(dp(1), border);
        return shape;
    }

    private RippleDrawable pressable(int fill, int border, int radius) {
        return new RippleDrawable(ColorStateList.valueOf(0x45f6b778), surface(fill, border, radius),
                surface(Color.WHITE, 0, radius));
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private static String time(long date) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(new Date(date));
    }

    private static String size(long bytes) {
        if (bytes >= 1024 * 1024) return String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0);
        return String.format(Locale.ROOT, "%.0f KB", Math.ceil(bytes / 1024.0));
    }

    private static String slotLabel(String id) { return "档位 " + (Integer.parseInt(id.substring(8)) + 1); }

    private final class Glyph extends View {
        private final Paint pen = new Paint(Paint.ANTI_ALIAS_FLAG);
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
                case "book":
                    line(canvas, 12,5, 8,3.5f, 3,4.5f, 3,19, 8,18, 12,20, 16,18, 21,19, 21,4.5f, 16,3.5f, 12,5, 12,20);
                    line(canvas, 6,8, 9,8.7f); line(canvas, 15,8.7f, 18,8);
                    break;
                case "box":
                    line(canvas, 3,7, 12,3, 21,7, 21,17, 12,21, 3,17, 3,7, 12,11, 21,7);
                    line(canvas, 12,11, 12,21); line(canvas, 7.5f,5, 16.5f,9, 16.5f,13);
                    break;
                case "commit":
                    line(canvas, 12,2, 12,6); line(canvas, 12,18, 12,22);
                    line(canvas, 12,6, 18,12, 12,18, 6,12, 12,6);
                    canvas.drawCircle(12, 12, 1.3f, pen);
                    break;
                case "restore":
                    canvas.drawArc(4,4,21,21,207,292,false,pen);
                    line(canvas, 3,5, 3,10, 8,10); line(canvas, 12,8, 12,13, 16,15);
                    break;
                case "export":
                    line(canvas, 4,13, 4,20, 20,20, 20,13);
                    line(canvas, 12,15, 12,3, 8,7); line(canvas, 12,3, 16,7);
                    break;
                case "link":
                    line(canvas, 9,8, 13,4, 17,4, 20,7, 20,11, 16,15);
                    line(canvas, 15,16, 11,20, 7,20, 4,17, 4,13, 8,9);
                    line(canvas, 9,15, 15,9);
                    break;
                case "chevron":
                    line(canvas, 8,5, 15,12, 8,19);
                    break;
                default:
                    canvas.drawCircle(12, 12, 9, pen);
                    canvas.drawArc(8,6,16,14,190,210,false,pen);
                    line(canvas, 14,12, 12,14);
                    canvas.drawCircle(12, 17.2f, 0.75f, pen);
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
