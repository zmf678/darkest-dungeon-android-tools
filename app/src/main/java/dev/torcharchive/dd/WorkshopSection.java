package dev.torcharchive.dd;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static dev.torcharchive.dd.MainActivity.*;

final class WorkshopSection {
    private static final String[] SORT = {"trend", "toprated", "mostrecent"};
    private static final String[] SORT_LABELS = {"近期热门", "评分最高", "最新发布"};
    private final MainActivity host;
    private final ArchiveStore store;
    private final Typeface display;
    private final WorkshopClient client = new WorkshopClient();
    private final ExecutorService requests = Executors.newFixedThreadPool(2);
    private final ExecutorService covers = Executors.newFixedThreadPool(3);
    private final ExecutorService downloads = Executors.newSingleThreadExecutor();
    private final LruCache<String, Bitmap> images = new LruCache<>(18 * 1024 * 1024) {
        @Override protected int sizeOf(String address, Bitmap image) { return image.getAllocationByteCount(); }
    };
    private final Map<String, List<WeakReference<ImageView>>> imageRequests = new HashMap<>();

    private int sort, number = 1, listScroll, pageRequestId;
    private boolean wide, directMode, closed, loading;
    private String query = "", draft = "", failure = "", detailError = "";
    private WorkshopClient.Page results;
    private WorkshopClient.Item selected;
    private WorkshopClient.Detail detail;
    private Future<?> request, detailRequest;
    private LinearLayout body, detailActions, detailHeading;
    private TextView downloadStatus, downloadAction;
    private ProgressBar downloadBar;
    private ImageView downloadSeal;
    private View downloadGlyph;
    private LinearLayout downloadPanel;
    private Download active;
    private ArchiveStore.Entry lastDownload;
    private String downloadMessage = "";
    private boolean downloadFailed;

    private static final class Download {
        final WorkshopClient.Package zip;
        volatile boolean cancelled;
        volatile boolean validating;
        long received, total;

        Download(WorkshopClient.Package zip) { this.zip = zip; }

        synchronized boolean beginValidation() {
            if (cancelled) return false;
            validating = true;
            return true;
        }

        synchronized void cancel() {
            if (!validating) cancelled = true;
        }
    }

    WorkshopSection(MainActivity host, ArchiveStore store, Typeface display) {
        this.host = host;
        this.store = store;
        this.display = display;
    }

    boolean hasDetail() { return selected != null; }
    boolean isDirectMode() { return directMode; }
    void selectMode(boolean direct) { directMode = direct; }

    void back() {
        if (detailRequest != null) detailRequest.cancel(true);
        selected = null;
        detail = null;
        host.showWorkshopPage(listScroll);
    }

    void close() {
        closed = true;
        requests.shutdownNow();
        covers.shutdownNow();
        downloads.shutdownNow();
    }

    View build(boolean wide) {
        this.wide = wide;
        LinearLayout root = host.vertical();
        if (selected != null) {
            root.addView(host.button("‹ 返回工坊", false, this::back), new LinearLayout.LayoutParams(-2, -2));
            host.add(root, downloadPanel(), 12);
            detailHeading = host.vertical();
            detailHeading.addView(detailHeader(detail == null ? selected : detail.item()));
            host.add(root, detailHeading, 12);
            body = host.vertical();
            host.add(root, body, 14);
            renderDetail();
            return root;
        }
        LinearLayout.LayoutParams downloadParams = new LinearLayout.LayoutParams(-1, -2);
        downloadParams.bottomMargin = host.dp(10);
        root.addView(downloadPanel(), downloadParams);
        if (!directMode) host.add(root, searchControls(), 0);
        body = host.vertical();
        host.add(root, body, directMode ? 0 : 14);
        if (!directMode) {
            if (results == null && !loading && failure.isEmpty()) loadPage(number, false);
            else renderResults();
        } else {
            renderDirect();
        }
        return root;
    }

    private View searchControls() {
        LinearLayout controls = panel(11);
        LinearLayout search = host.horizontal();
        search.setGravity(Gravity.CENTER_VERTICAL);
        TextView[] sortButtons = new TextView[SORT.length];
        EditText input = input("搜索模组或粘贴工坊链接", false);
        input.setBackgroundColor(Color.TRANSPARENT);
        input.setPadding(host.dp(7), host.dp(8), host.dp(10), host.dp(8));
        input.setText(draft);
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { draft = s.toString(); }
            @Override public void afterTextChanged(Editable s) { }
        });
        Runnable submit = () -> {
            String next = draft.trim();
            String id = workshopId(next);
            ((InputMethodManager) host.getSystemService(Context.INPUT_METHOD_SERVICE))
                    .hideSoftInputFromWindow(input.getWindowToken(), 0);
            input.clearFocus();
            if (!id.isEmpty()) open(new WorkshopClient.Item(id, "工坊条目 " + id, "", "", "", 0, 0, 0,
                    java.util.Collections.emptyList()));
            else {
                query = next;
                loadPage(1, true);
                for (int i = 0; i < SORT.length; i++) host.styleSegment(sortButtons[i], i == sort && query.isEmpty());
            }
        };
        input.setOnEditorActionListener((view, action, event) -> {
            if (action != EditorInfo.IME_ACTION_SEARCH) return false;
            submit.run();
            return true;
        });
        LinearLayout field = host.horizontal();
        field.setGravity(Gravity.CENTER_VERTICAL);
        field.setPadding(host.dp(10), 0, 0, 0);
        field.setBackground(host.surface(BG, LINE, 9));
        field.addView(host.glyph("search", GOLD), new LinearLayout.LayoutParams(host.dp(18), host.dp(18)));
        field.addView(input, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams fieldParams = new LinearLayout.LayoutParams(0, -2, 1);
        fieldParams.setMarginEnd(host.dp(8));
        search.addView(field, fieldParams);
        search.addView(host.button("搜索", true, submit), new LinearLayout.LayoutParams(-2, -2));

        LinearLayout sorts = host.horizontal();
        sorts.setPadding(host.dp(3), host.dp(3), host.dp(3), host.dp(3));
        sorts.setBackground(host.surface(BG, LINE, 10));
        for (int i = 0; i < SORT.length; i++) {
            int option = i;
            boolean current = i == sort && query.isEmpty();
            TextView button = host.segment(SORT_LABELS[i], current, () -> {
                sort = option;
                query = draft = "";
                loadPage(1, false);
                host.showWorkshopPage(host.workshopScroll());
            });
            sortButtons[i] = button;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
            if (i != 0) lp.setMarginStart(host.dp(3));
            sorts.addView(button, lp);
        }
        if (host.getResources().getConfiguration().screenWidthDp >= 850) {
            LinearLayout toolbar = host.horizontal();
            toolbar.setGravity(Gravity.CENTER_VERTICAL);
            toolbar.addView(search, new LinearLayout.LayoutParams(0, -2, 7));
            LinearLayout.LayoutParams filters = new LinearLayout.LayoutParams(0, -2, 6);
            filters.setMarginStart(host.dp(12));
            toolbar.addView(sorts, filters);
            controls.addView(toolbar);
        } else {
            controls.addView(search);
            host.add(controls, sorts, 8);
        }
        return controls;
    }

    private EditText input(String hint, boolean url) {
        EditText edit = new EditText(host);
        edit.setSingleLine(true);
        edit.setInputType(InputType.TYPE_CLASS_TEXT | (url ? InputType.TYPE_TEXT_VARIATION_URI : 0));
        edit.setImeOptions(url ? EditorInfo.IME_ACTION_DONE : EditorInfo.IME_ACTION_SEARCH);
        edit.setTextSize(15);
        edit.setTextColor(INK);
        edit.setHintTextColor(MUTED);
        edit.setHint(hint);
        edit.setSelectAllOnFocus(url);
        edit.setPadding(host.dp(12), host.dp(9), host.dp(12), host.dp(9));
        edit.setMinHeight(host.dp(44));
        edit.setBackground(host.surface(BG, LINE, 9));
        edit.setFilters(new InputFilter[]{new InputFilter.LengthFilter(url ? 4096 : 200)});
        return edit;
    }

    private static String workshopId(String value) {
        if (value.matches("[0-9]{5,20}")) return value;
        android.net.Uri uri = android.net.Uri.parse(value);
        if (!"steamcommunity.com".equalsIgnoreCase(uri.getHost())) return "";
        String id = uri.getQueryParameter("id");
        return id != null && id.matches("[0-9]{5,20}") ? id : "";
    }

    private void loadPage(int page, boolean jump) {
        if (request != null) request.cancel(true);
        int id = ++pageRequestId;
        number = page;
        results = null;
        failure = "";
        loading = true;
        if (!directMode && selected == null) {
            renderResults();
            if (jump) host.scrollToWorkshop(body);
        }
        String requestedSort = SORT[sort], text = query;
        request = requests.submit(() -> {
            try {
                WorkshopClient.Page data = client.browse(requestedSort, text, page);
                postUnlessInterrupted(() -> finishPage(id, data, ""));
            } catch (Exception ex) {
                postUnlessInterrupted(() -> finishPage(id, null, networkError(ex)));
            }
        });
    }

    private void finishPage(int id, WorkshopClient.Page data, String error) {
        if (id != pageRequestId) return;
        results = data;
        loading = false;
        failure = error;
        if (!directMode && selected == null) renderResults();
    }

    private void renderResults() {
        body.removeAllViews();
        if (loading) {
            body.addView(loading("正在读取 Steam 工坊…"));
            return;
        }
        if (results == null) {
            LinearLayout state = state("工坊暂时不可用", failure);
            actions(state, host.button("重试", true, () -> loadPage(number, false)),
                    host.button("打开 Steam", false, () -> host.openWorkshopPage(WorkshopClient.pageUrl(SORT[sort], query, number))));
            body.addView(state);
            return;
        }
        String title = query.isEmpty() ? "Steam 创意工坊" : "搜索结果";
        if (results.items().isEmpty()) {
            body.addView(emptyResults());
            return;
        }
        LinearLayout heading = host.horizontal();
        heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = heading(title, 22);
        heading.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        heading.addView(host.type("共 " + results.total() + " 项", 12, MUTED, Typeface.DEFAULT));
        body.addView(heading);
        List<WorkshopClient.Item> items = results.items();
        for (int i = 0; i < items.size(); i++) {
            if (!wide) host.add(body, card(items.get(i)), i == 0 ? 10 : 8);
            else {
                LinearLayout pair = host.horizontal();
                pair.setBaselineAligned(false);
                host.weighted(pair, card(items.get(i++)), true);
                host.weighted(pair, i < items.size() ? card(items.get(i)) : new View(host), false);
                host.add(body, pair, 10);
            }
        }
        if (results.pages() > 1) {
            LinearLayout pages = host.horizontal();
            pages.setGravity(Gravity.CENTER_VERTICAL);
            int current = results.number();
            TextView previous = host.button("上一页", false, () -> loadPage(current - 1, true));
            setEnabled(previous, current > 1);
            pages.addView(previous);
            TextView counter = host.type(current + " / " + results.pages(), 13, MUTED, Typeface.DEFAULT_BOLD);
            counter.setGravity(Gravity.CENTER);
            pages.addView(counter, new LinearLayout.LayoutParams(0, -2, 1));
            TextView next = host.button("下一页", true, () -> loadPage(current + 1, true));
            setEnabled(next, current < results.pages());
            pages.addView(next);
            host.add(body, pages, 14);
        }
    }

    private View card(WorkshopClient.Item item) {
        LinearLayout card = host.horizontal();
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setMinimumHeight(host.dp(108));
        card.setPadding(host.dp(8), host.dp(8), host.dp(9), host.dp(8));
        card.setBackground(host.pressable(PANEL, LINE, 13));
        card.setFocusable(true);
        card.setOnClickListener(v -> open(item));
        card.addView(art(item.image()), new LinearLayout.LayoutParams(host.dp(wide ? 106 : 98), host.dp(88)));
        LinearLayout words = host.vertical();
        LinearLayout.LayoutParams content = new LinearLayout.LayoutParams(0, -2, 1);
        content.setMarginStart(host.dp(11));
        card.addView(words, content);
        TextView title = host.type(item.title(), 15, INK, Typeface.DEFAULT_BOLD);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        words.addView(title);
        TextView byline = host.type(cardMeta(item), 12, GOLD, Typeface.DEFAULT);
        byline.setSingleLine(true);
        byline.setEllipsize(TextUtils.TruncateAt.END);
        host.add(words, byline, 3);
        if (!item.summary().isEmpty()) {
            TextView summary = host.type(item.summary(), 12, MUTED, Typeface.DEFAULT);
            summary.setMaxLines(2);
            summary.setEllipsize(TextUtils.TruncateAt.END);
            host.add(words, summary, 2);
        }
        TextView chevron = host.type("›", 23, GOLD, Typeface.DEFAULT);
        chevron.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        card.addView(chevron);
        return card;
    }

    private String cardMeta(WorkshopClient.Item item) {
        String label = item.creator();
        if (!item.tags().isEmpty()) label = join(label, item.tags().get(0));
        return label.isEmpty() ? "Steam 工坊" : label;
    }

    private void open(WorkshopClient.Item item) {
        listScroll = host.workshopScroll();
        selected = item;
        detail = null;
        detailError = "";
        host.showWorkshopPage(0);
        loadDetail();
    }

    private void loadDetail() {
        WorkshopClient.Item item = selected;
        if (detailRequest != null) detailRequest.cancel(true);
        detailError = "";
        renderDetail();
        detailRequest = requests.submit(() -> {
            try {
                WorkshopClient.Detail result = client.detail(item);
                postUnlessInterrupted(() -> {
                    if (selected != item) return;
                    detail = result;
                    if (item.image().isEmpty()) {
                        detailHeading.removeAllViews();
                        detailHeading.addView(detailHeader(result.item()));
                    }
                    renderDetail();
                });
            } catch (Exception ex) {
                postUnlessInterrupted(() -> {
                    if (selected != item) return;
                    detailError = networkError(ex);
                    renderDetail();
                });
            }
        });
    }

    private View detailHeader(WorkshopClient.Item item) {
        LinearLayout header = panel(16);
        if (!item.image().isEmpty()) header.addView(art(item.image()), new LinearLayout.LayoutParams(-1, host.dp(wide ? 264 : 205)));
        TextView title = heading(item.title(), 27);
        host.add(header, title, item.image().isEmpty() ? 0 : 13);
        host.add(header, host.type(join(item.creator(), "Steam 工坊 #" + item.id()), 13, GOLD, Typeface.DEFAULT_BOLD), 5);
        String facts = item.bytes() > 0 ? size(item.bytes()) : "";
        if (item.subscribers() > 0) facts = join(facts, item.subscribers() + " 人订阅");
        if (item.updated() > 0) facts = join(facts, "更新于 " + time(item.updated() * 1000L));
        if (!facts.isEmpty()) host.add(header, host.type(facts, 12, MUTED, Typeface.DEFAULT), 4);
        if (!item.tags().isEmpty()) host.add(header, host.type(String.join("  /  ", item.tags()), 12, MUTED, Typeface.DEFAULT), 4);
        return header;
    }

    private void renderDetail() {
        body.removeAllViews();
        detailActions = panel(14);
        body.addView(detailActions);
        renderDetailActions();
        if (detail == null) {
            if (detailError.isEmpty()) host.add(body, loading("正在读取模组说明…"), 12);
            else {
                LinearLayout notice = state("无法读取模组说明", detailError);
                host.add(notice, host.button("重试", false, this::loadDetail), 10);
                host.add(body, notice, 12);
            }
            return;
        }
        LinearLayout description = panel(16);
        description.addView(heading("内容说明", 20));
        String text = detail.description().isEmpty() ? "作者未填写说明。" : detail.description();
        TextView words = host.type(text, 15, INK, Typeface.DEFAULT);
        words.setLineSpacing(host.dp(4), 1);
        words.setTextIsSelectable(true);
        host.add(description, words, 8);
        host.add(body, description, 12);
    }

    private void renderDetailActions() {
        detailActions.removeAllViews();
        ArchiveStore.Entry entry = host.workshopArchive("Steam 工坊 #" + selected.id());
        View primary;
        if (entry != null) primary = host.button("打开已下载模组", true, () -> host.modDetails(entry));
        else if (detail != null && detail.download() != null) primary = host.button("下载到模组库", true, () -> confirmDownload(detail.download()));
        else primary = host.button("Steam 下载方法", true, this::steamInstructions);
        actions(detailActions, primary, host.button("打开 Steam 原页", false,
                () -> host.openWorkshopPage(WorkshopClient.itemUrl(selected.id()))));
        if (entry == null && detail != null && detail.download() == null) {
            host.add(detailActions, host.type("此条目需在电脑 Steam 订阅后下载，再导入启动器。", 13, MUTED, Typeface.DEFAULT), 8);
        }
    }

    private void steamInstructions() {
        WorkshopClient.Item item = selected;
        new AlertDialog.Builder(host).setTitle("通过电脑 Steam 下载")
                .setMessage("在电脑 Steam 打开该条目并订阅。下载完成后，将 Steam 库中的以下文件夹整体压缩为 ZIP："
                        + "\n\nsteamapps/workshop/content/262060/" + item.id()
                        + "\n\n把 ZIP 传到手机，在「本地模组」选择「导入 Mod ZIP」。")
                .setNegativeButton("关闭", null)
                .setNeutralButton("复制条目 ID", (d, w) -> {
                    ClipboardManager clipboard = (ClipboardManager) host.getSystemService(Context.CLIPBOARD_SERVICE);
                    clipboard.setPrimaryClip(ClipData.newPlainText("Steam 工坊条目 ID", item.id()));
                })
                .setPositiveButton("打开 Steam", (d, w) -> host.openWorkshopPage(WorkshopClient.itemUrl(item.id())))
                .show();
    }

    private void renderDirect() {
        body.removeAllViews();
        LinearLayout source = state("通过链接下载", "粘贴作者提供的 HTTPS ZIP 下载链接。下载后可在本地模组库查看和安装。");
        host.add(source, host.button("输入 ZIP 链接", true, this::promptZip), 11);
        body.addView(source);
        LinearLayout featured = panel(16);
        featured.addView(host.type("公开模组 · theCrius", 12, GOLD, Typeface.DEFAULT_BOLD));
        host.add(featured, heading("Crius 英雄调整", 23), 4);
        host.add(featured, host.type("调整多个原版英雄职业的技能与平衡。", 14, MUTED, Typeface.DEFAULT), 5);
        try {
            WorkshopClient.Package zip = WorkshopClient.featuredPackage();
            ArchiveStore.Entry saved = host.workshopArchive(zip.source());
            LinearLayout row = host.horizontal();
            host.weighted(row, saved == null
                    ? host.button("下载模组", true, () -> confirmDownload(zip))
                    : host.button("打开已下载模组", true, () -> host.modDetails(saved)), true);
            host.weighted(row, host.button("查看作者仓库", false, () -> host.openWorkshopPage(WorkshopClient.featuredPage())), false);
            host.add(featured, row, 12);
        } catch (IOException impossible) {
            host.add(featured, host.type("下载链接暂不可用。", 13, ERROR, Typeface.DEFAULT), 8);
        }
        host.add(body, featured, 12);
    }

    private void promptZip() {
        EditText edit = input("https://", true);
        LinearLayout frame = host.vertical();
        frame.setPadding(host.dp(20), host.dp(8), host.dp(20), 0);
        frame.addView(edit);
        AlertDialog dialog = new AlertDialog.Builder(host).setTitle("下载公开 ZIP")
                .setView(frame).setNegativeButton("取消", null).setPositiveButton("继续", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                WorkshopClient.Package zip = WorkshopClient.fromLink(edit.getText().toString());
                dialog.dismiss();
                confirmDownload(zip);
            } catch (IOException ex) {
                edit.setError(ex.getMessage());
            }
        }));
        dialog.show();
    }

    private void confirmDownload(WorkshopClient.Package zip) {
        if (active != null) {
            host.scrollToWorkshop(downloadPanel);
            return;
        }
        new AlertDialog.Builder(host).setTitle("下载到模组库")
                .setMessage(zip.title() + "\n来源  " + zip.url().getHost() + (zip.bytes() > 0 ? "\n大小  " + size(zip.bytes()) : ""))
                .setNegativeButton("取消", null).setPositiveButton("下载", (d, w) -> startDownload(zip)).show();
    }

    private void startDownload(WorkshopClient.Package zip) {
        if (active != null) return;
        Download job = new Download(zip);
        active = job;
        lastDownload = null;
        downloadMessage = "";
        renderDownload();
        host.scrollToWorkshop(downloadPanel);
        downloads.execute(() -> {
            ArchiveStore.Entry saved = null;
            String error = "";
            File temporary = null;
            try {
                temporary = File.createTempFile("workshop-", ".zip", host.getCacheDir());
                client.download(zip, temporary, (received, total) -> host.runOnUiThread(() -> {
                    job.received = received;
                    job.total = total;
                    if (!closed) renderDownload();
                }), () -> job.cancelled);
                if (job.beginValidation()) {
                    host.runOnUiThread(() -> {
                        if (!closed) renderDownload();
                    });
                    saved = store.importOnlineMod(temporary, zip.title(), zip.source());
                }
            } catch (Exception ex) {
                error = networkError(ex);
            } finally {
                if (temporary != null) {
                    try { Files.deleteIfExists(temporary.toPath()); } catch (IOException ignored) { }
                }
            }
            ArchiveStore.Entry result = saved;
            String problem = error;
            host.runOnUiThread(() -> finishDownload(job, result, problem));
        });
    }

    private void finishDownload(Download job, ArchiveStore.Entry saved, String error) {
        if (closed) return;
        active = null;
        lastDownload = saved;
        downloadFailed = saved == null && !job.cancelled;
        downloadMessage = saved != null ? "已下载 · " + saved.title()
                : job.cancelled ? "已取消下载" : "下载失败 · " + error;
        if (saved != null) host.refreshWorkshopLibrary();
        renderDownload();
        if (selected != null) renderDetailActions();
        else if (directMode) renderDirect();
    }

    private View downloadPanel() {
        downloadPanel = panel(14);
        LinearLayout summary = host.horizontal();
        summary.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout mark = new FrameLayout(host);
        mark.setBackground(host.surface(0xff352521, LINE, 9));
        downloadGlyph = host.glyph("box", GOLD);
        mark.addView(downloadGlyph, new FrameLayout.LayoutParams(host.dp(19), host.dp(19), Gravity.CENTER));
        downloadSeal = illustration(R.drawable.workshop_status, ImageView.ScaleType.CENTER_CROP);
        mark.addView(downloadSeal, new FrameLayout.LayoutParams(-1, -1));
        summary.addView(mark, new LinearLayout.LayoutParams(host.dp(38), host.dp(38)));
        downloadStatus = host.type("", 14, INK, Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(0, -2, 1);
        statusParams.setMarginStart(host.dp(9));
        summary.addView(downloadStatus, statusParams);
        downloadPanel.addView(summary);
        downloadBar = new ProgressBar(host, null, android.R.attr.progressBarStyleHorizontal);
        downloadBar.setProgressTintList(ColorStateList.valueOf(GOLD));
        downloadBar.setIndeterminateTintList(ColorStateList.valueOf(GOLD));
        downloadBar.setMax(100);
        host.add(downloadPanel, downloadBar, 6);
        downloadAction = host.button("", false, () -> {
            if (active != null) {
                active.cancel();
                renderDownload();
            } else if (lastDownload != null) host.modDetails(lastDownload);
        });
        if (wide) {
            LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(-2, -2);
            actionParams.setMarginStart(host.dp(10));
            summary.addView(downloadAction, actionParams);
        } else {
            host.add(downloadPanel, downloadAction, 8);
        }
        renderDownload();
        return downloadPanel;
    }

    private void renderDownload() {
        if (downloadPanel == null) return;
        Download job = active;
        downloadPanel.setVisibility(job != null || !downloadMessage.isEmpty() ? View.VISIBLE : View.GONE);
        downloadBar.setVisibility(job != null ? View.VISIBLE : View.GONE);
        downloadAction.setVisibility(job != null || lastDownload != null ? View.VISIBLE : View.GONE);
        downloadSeal.setVisibility(lastDownload != null ? View.VISIBLE : View.GONE);
        downloadGlyph.setVisibility(lastDownload != null ? View.GONE : View.VISIBLE);
        downloadStatus.setTextColor(job != null ? GOLD : downloadFailed ? ERROR : GREEN);
        if (job == null) {
            downloadStatus.setText(downloadMessage);
            downloadAction.setText("查看与安装");
            setEnabled(downloadAction, true);
            return;
        }
        boolean unknown = job.total <= 0 || job.validating;
        downloadBar.setIndeterminate(unknown);
        if (!unknown) downloadBar.setProgress((int) (job.received * 100L / job.total));
        String progress = job.received == 0 ? "" : " · " + size(job.received) + (job.total > 0 ? " / " + size(job.total) : "");
        downloadStatus.setText(job.cancelled ? "正在取消下载…" : job.validating ? "正在检查并收录 ZIP…"
                : "正在下载 · " + job.zip.title() + progress);
        downloadAction.setText("取消下载");
        setEnabled(downloadAction, !job.cancelled && !job.validating);
    }

    private View art(String address) {
        FrameLayout frame = new FrameLayout(host);
        frame.setBackground(host.surface(RAISED, LINE, 8));
        frame.setClipToOutline(true);
        ImageView cover = illustration(R.drawable.workshop_placeholder, ImageView.ScaleType.CENTER_CROP);
        frame.addView(cover, new FrameLayout.LayoutParams(-1, -1));
        if (address.isEmpty()) return frame;
        Bitmap cached = images.get(address);
        if (cached != null) {
            cover.setImageBitmap(cached);
            return frame;
        }
        List<WeakReference<ImageView>> pending = imageRequests.get(address);
        if (pending != null) pending.add(new WeakReference<>(cover));
        else {
            List<WeakReference<ImageView>> listeners = new ArrayList<>();
            listeners.add(new WeakReference<>(cover));
            imageRequests.put(address, listeners);
            covers.execute(() -> {
                Bitmap bitmap = null;
                try { bitmap = client.cover(address); } catch (IOException ignored) { }
                Bitmap result = bitmap;
                postUnlessInterrupted(() -> {
                    List<WeakReference<ImageView>> views = imageRequests.remove(address);
                    if (result == null) return;
                    images.put(address, result);
                    for (WeakReference<ImageView> reference : views) {
                        ImageView image = reference.get();
                        if (image != null) image.setImageBitmap(result);
                    }
                });
            });
        }
        return frame;
    }

    private LinearLayout panel(int padding) {
        LinearLayout panel = host.vertical();
        int inset = host.dp(padding);
        panel.setPadding(inset, inset, inset, inset);
        panel.setBackground(host.surface(PANEL, LINE, 13));
        return panel;
    }

    private LinearLayout state(String title, String detail) {
        LinearLayout panel = panel(16);
        panel.addView(heading(title, 20));
        if (!detail.isEmpty()) host.add(panel, host.type(detail, 14, MUTED, Typeface.DEFAULT), 5);
        return panel;
    }

    private View emptyResults() {
        LinearLayout panel = panel(14);
        LinearLayout row = host.horizontal();
        row.setGravity(Gravity.CENTER_VERTICAL);
        ImageView mark = illustration(R.drawable.workshop_empty, ImageView.ScaleType.CENTER_CROP);
        row.addView(mark, new LinearLayout.LayoutParams(host.dp(64), host.dp(64)));
        LinearLayout words = host.vertical();
        LinearLayout.LayoutParams text = new LinearLayout.LayoutParams(0, -2, 1);
        text.setMarginStart(host.dp(10));
        row.addView(words, text);
        words.addView(heading("没有找到模组", 20));
        host.add(words, host.type("试试其他关键词，也可以粘贴 Steam 工坊链接或条目 ID。", 14, MUTED, Typeface.DEFAULT), 5);
        panel.addView(row);
        return panel;
    }

    private ImageView illustration(int resource, ImageView.ScaleType scale) {
        ImageView image = new ImageView(host);
        image.setImageResource(resource);
        image.setScaleType(scale);
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return image;
    }

    private View loading(String label) {
        LinearLayout row = host.horizontal();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(host.dp(14), host.dp(14), host.dp(14), host.dp(14));
        row.setBackground(host.surface(PANEL, LINE, 13));
        ProgressBar progress = new ProgressBar(host);
        progress.setIndeterminateTintList(ColorStateList.valueOf(GOLD));
        row.addView(progress, new LinearLayout.LayoutParams(host.dp(20), host.dp(20)));
        TextView text = host.type(label, 14, MUTED, Typeface.DEFAULT);
        text.setPadding(host.dp(10), 0, 0, 0);
        row.addView(text);
        return row;
    }

    private TextView heading(String label, float size) {
        TextView title = host.type(label, size, INK, display);
        title.setIncludeFontPadding(false);
        if (Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        return title;
    }

    private void actions(LinearLayout panel, View primary, View secondary) {
        LinearLayout row = host.horizontal();
        host.weighted(row, primary, true);
        host.weighted(row, secondary, false);
        host.add(panel, row, panel.getChildCount() == 0 ? 0 : 12);
    }

    private void postUnlessInterrupted(Runnable action) {
        if (!Thread.currentThread().isInterrupted()) host.runOnUiThread(() -> {
            if (!closed) action.run();
        });
    }

    private static void setEnabled(View view, boolean enabled) {
        view.setEnabled(enabled);
        view.setAlpha(enabled ? 1f : 0.48f);
    }

    private static String join(String left, String right) {
        if (left.isEmpty()) return right;
        return right.isEmpty() ? left : left + " · " + right;
    }

    private static String networkError(Exception error) {
        if (error instanceof SocketTimeoutException) return "连接超时，请检查网络后重试。";
        if (error instanceof java.util.zip.ZipException) return "压缩包损坏或格式错误，请核对下载地址。";
        String message = error.getMessage();
        if (message != null && message.matches("(?s).*[\u4e00-\u9fff].*")) return message;
        return error instanceof org.json.JSONException ? "Steam 返回的数据无法读取，可打开 Steam 原页查看。"
                : "读取失败，请检查网络、文件格式和设备存储空间后重试。";
    }
}
