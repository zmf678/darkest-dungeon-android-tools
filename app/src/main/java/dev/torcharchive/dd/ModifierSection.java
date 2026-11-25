package dev.torcharchive.dd;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import dev.torcharchive.game.CatalogData;
import dev.torcharchive.game.ContentModGenerator;
import dev.torcharchive.game.SaveEditor;

/**
 * Complete on-device Save Editor & Game Rules Modifier with Gothic Ember aesthetic
 * and dedicated in-app secondary detail pages (Hero Dossier, Quirks & Diseases,
 * Trinket Codex, and Raw DSON Node Editor).
 */
final class ModifierSection {
    private static final int DEEP = 0xff191312;
    private static final int PANEL = 0xff211918;
    private static final int RAISED = 0xff2b201e;
    private static final int INK = 0xfff5e6d4;
    private static final int MUTED = 0xffc1a897;
    private static final int GOLD = 0xffe1ab71;
    private static final int GOLD_BG = 0xff3b2a1d;
    private static final int LINE = 0xff543c32;
    private static final int RED = 0xff733029;
    private static final int HP_FILL = 0xffb8473c;
    private static final int STRESS_MID = 0xffc9934c;
    private static final int STRESS_HIGH = 0xffd15349;
    private static final int CRIMSON_BG = 0xff3b1d1b;
    private static final int ERROR = 0xffffb3a3;
    private static final int GREEN = 0xffc5cb9e;
    private static final int GREEN_BG = 0xff262f23;
    private static final int ARCANE = 0xff9ec2db;
    private static final int ARCANE_BG = 0xff212b33;

    private enum SubPage { NONE, HERO_DETAIL, HERO_QUIRKS, TRINKET_CODEX, RAW_EDIT }

    interface Host {
        void runTask(String progress, WorkTask task, String successMessage);
        void showMessage(String msg, boolean isError);
        void requestRedraw();
        void scrollToTop();
        void launchOrRestartGame();
        View makeButton(String text, boolean primary, Runnable onClick);
        TextView makeType(String text, float sp, int color, Typeface face);
        GradientDrawable makeSurface(int fill, int border, int radiusDp);
        int dp(float v);
    }

    @FunctionalInterface
    interface WorkTask {
        void run() throws Exception;
    }

    private final Context context;
    private final Host host;
    private int mode = 0; // 0 = 存档修改器, 1 = 游戏规则修改器
    private int saveSubTab = 0; // 0=庄园货币, 1=花名册英雄, 2=饰品库, 3=建筑地牢, 4=原始DSON

    // Secondary page navigation
    private SubPage subPage = SubPage.NONE;

    // Save data snapshot
    private List<SaveEditor.SlotSummary> slots = List.of();
    private String selectedSlot = "";
    private SaveEditor.EstateState estate;
    private List<SaveEditor.HeroState> heroes = List.of();
    private List<SaveEditor.TrinketEntry> trinkets = List.of();
    private Map<String, Integer> dungeonXp = new LinkedHashMap<>();

    // Heroes filter/search
    private int heroFilter = 0; // 0=全部, 1=需恢复, 2=含疾病, 3=阵亡
    private String heroSearch = "";

    // Hero detail secondary page draft state
    private String activeHeroGuid = "";
    private String draftHeroName = "";
    private int draftHpPct = 100;
    private int draftStress = 0;
    private int draftXp = 0;
    private int draftWeapon = 0;
    private int draftArmour = 0;
    private String draftVirtue = "";
    private String draftAffliction = "";
    private boolean draftRevive = false;

    // Quirks secondary page draft state
    private final List<SaveEditor.QuirkEntry> draftQuirks = new ArrayList<>();
    private String quirkSearch = "";

    // Trinkets & Codex state
    private String ownedTrinketSearch = "";
    private String codexCategory = "全部";
    private String codexSearch = "";
    private int codexQuantity = 1;

    // Raw DSON inspector & editor state
    private List<String> dsonFiles = List.of();
    private String selectedDsonFile = "persist.game.json";
    private final List<String> rawPath = new ArrayList<>();
    private List<SaveEditor.RawEntry> rawEntries = List.of();
    private String rawSearch = "";
    private SaveEditor.RawEntry editingRawEntry;
    private String draftRawType = "int";
    private String draftRawValue = "";

    // Content modifier state
    private ContentModGenerator.Config modConfig = new ContentModGenerator.Config();
    private boolean modInstalled = false;

    ModifierSection(Context context, Host host) {
        this.context = context;
        this.host = host;
    }

    boolean isInSubPage() {
        return subPage != SubPage.NONE;
    }

    boolean handleBack() {
        if (subPage == SubPage.NONE) return false;
        subPage = SubPage.NONE;
        host.scrollToTop();
        host.requestRedraw();
        return true;
    }

    void refreshSync() {
        try {
            slots = SaveEditor.listSlots(context);
            if ((selectedSlot.isEmpty() || slots.stream().noneMatch(s -> s.slot.equals(selectedSlot))) && !slots.isEmpty()) {
                selectedSlot = slots.get(0).slot;
            }
            if (!selectedSlot.isEmpty()) {
                estate = SaveEditor.readEstate(context, selectedSlot);
                heroes = SaveEditor.listHeroes(context, selectedSlot);
                trinkets = SaveEditor.listTrinkets(context, selectedSlot);
                dungeonXp = SaveEditor.readDungeonXp(context, selectedSlot);
                dsonFiles = SaveEditor.listDsonFiles(context, selectedSlot);
                if (!dsonFiles.isEmpty() && !dsonFiles.contains(selectedDsonFile)) {
                    selectedDsonFile = dsonFiles.get(0);
                    rawPath.clear();
                }
                if (!dsonFiles.isEmpty()) {
                    try {
                        rawEntries = SaveEditor.inspectRawPath(context, selectedSlot, selectedDsonFile, rawPath);
                    } catch (Exception e) {
                        rawPath.clear();
                        rawEntries = SaveEditor.inspectRawPath(context, selectedSlot, selectedDsonFile, rawPath);
                    }
                }
            } else {
                estate = null;
                heroes = List.of();
                trinkets = List.of();
                dungeonXp = new LinkedHashMap<>();
                dsonFiles = List.of();
                rawEntries = List.of();
            }
        } catch (Exception ignored) {
        }
        modConfig = ContentModGenerator.loadConfig(context);
        modInstalled = ContentModGenerator.isInstalled(context);
    }

    View buildView(boolean wide, Typeface display) {
        if (subPage != SubPage.NONE) {
            return switch (subPage) {
                case HERO_DETAIL -> buildHeroDetailPage(wide, display);
                case HERO_QUIRKS -> buildHeroQuirksPage(wide, display);
                case TRINKET_CODEX -> buildTrinketCodexPage(wide, display);
                case RAW_EDIT -> buildRawEditPage(wide, display);
                default -> buildMainView(wide, display);
            };
        }
        return buildMainView(wide, display);
    }

    // ==================== MAIN MODIFIER WORKSPACE ====================

    private View buildMainView(boolean wide, Typeface display) {
        LinearLayout root = vertical();

        // Top header bar
        LinearLayout header = horizontal();
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titleCol = vertical();
        titleCol.addView(host.makeType("余烬修改工坊", 22, INK, display));
        addTop(titleCol, host.makeType("无损二进制 DSON 存档精修 · 非破坏性规则覆写 Mod", 12, MUTED, Typeface.DEFAULT), 2);
        header.addView(titleCol, new LinearLayout.LayoutParams(0, -2, 1));
        header.addView(host.makeButton("刷新数据", false, () ->
                host.runTask("正在同步存档与修改器配置…", this::refreshSync, "修改器数据已刷新")));
        root.addView(header);

        // Segmented mode switcher inside recessed pill rail
        LinearLayout modeRail = horizontal();
        modeRail.setPadding(host.dp(5), host.dp(5), host.dp(5), host.dp(5));
        modeRail.setBackground(host.makeSurface(DEEP, LINE, 12));
        View btnSaveMode = makeSegmentChip("存档精修 (全字段)", mode == 0, () -> {
            if (mode != 0) { mode = 0; host.requestRedraw(); }
        });
        View btnRuleMode = makeSegmentChip("游戏规则 Mod" + (modInstalled ? " · 已启" : ""), mode == 1, () -> {
            if (mode != 1) { mode = 1; host.requestRedraw(); }
        });
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0, -2, 1);
        lp1.setMarginEnd(host.dp(6));
        modeRail.addView(btnSaveMode, lp1);
        modeRail.addView(btnRuleMode, new LinearLayout.LayoutParams(0, -2, 1));
        addTop(root, modeRail, 12);

        // Compact safety badge strip
        LinearLayout safety = horizontal();
        safety.setGravity(Gravity.CENTER_VERTICAL);
        safety.setPadding(host.dp(12), host.dp(8), host.dp(12), host.dp(8));
        safety.setBackground(host.makeSurface(RAISED, LINE, 10));
        safety.addView(makePill("安全守护", GOLD, GOLD_BG));
        TextView note = host.makeType("写档前自动停机并先拍快照，可随时在「存档」页回退。", 12, MUTED, Typeface.DEFAULT);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(0, -2, 1);
        nlp.setMarginStart(host.dp(8));
        safety.addView(note, nlp);
        addTop(root, safety, 8);

        if (mode == 0) {
            addTop(root, buildSaveEditor(wide, display), 12);
        } else {
            addTop(root, buildRulesModifier(wide, display), 12);
        }
        return root;
    }

    private View buildSaveEditor(boolean wide, Typeface display) {
        LinearLayout box = vertical();
        if (slots.isEmpty()) {
            LinearLayout card = card();
            card.addView(host.makeType("暂未检测到游戏存档槽位", 17, INK, display));
            addTop(card, host.makeType("请先启动一次游戏创建档位，或在「启动 / 存档」页导入 Steam 迁移包后再点右上角「刷新数据」。",
                    13, MUTED, Typeface.DEFAULT), 6);
            box.addView(card);
            return box;
        }

        // Slot Selector + KPI Dashboard Card
        LinearLayout slotCard = card();
        LinearLayout slotHead = horizontal();
        slotHead.setGravity(Gravity.CENTER_VERTICAL);
        slotHead.addView(host.makeType("当前编辑档位", 13, GOLD, Typeface.DEFAULT_BOLD),
                new LinearLayout.LayoutParams(0, -2, 1));
        if (estate != null) {
            slotHead.addView(makePill(modeDisplayName(estate.gameMode), GOLD, GOLD_BG));
        }
        slotCard.addView(slotHead);

        HorizontalScrollView slotScroll = new HorizontalScrollView(context);
        slotScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout slotRow = horizontal();
        for (int i = 0; i < slots.size(); i++) {
            SaveEditor.SlotSummary s = slots.get(i);
            boolean active = s.slot.equals(selectedSlot);
            String label = "档位 " + (Integer.parseInt(s.slot.substring(8)) + 1) + " · " + s.estateName;
            View chip = makeFilterChip(label, active, () -> {
                if (!active) {
                    selectedSlot = s.slot;
                    rawPath.clear();
                    host.runTask("正在加载 " + label + "…", this::refreshSync, "已切换至 " + label);
                }
            });
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, -2);
            if (i + 1 < slots.size()) clp.setMarginEnd(host.dp(8));
            slotRow.addView(chip, clp);
        }
        slotScroll.addView(slotRow);
        addTop(slotCard, slotScroll, 8);

        if (estate != null) {
            LinearLayout kpiGrid = horizontal();
            int goldVal = estate.wallet.getOrDefault("gold", 0);
            int heirloomSum = estate.wallet.getOrDefault("bust", 0)
                    + estate.wallet.getOrDefault("portrait", 0)
                    + estate.wallet.getOrDefault("deed", 0)
                    + estate.wallet.getOrDefault("crest", 0);
            addKpiTile(kpiGrid, "金币", formatCompact(goldVal), GOLD, true);
            addKpiTile(kpiGrid, "传家宝", formatCompact(heirloomSum), INK, true);
            addKpiTile(kpiGrid, "英雄", heroes.size() + " 名", GREEN, true);
            addKpiTile(kpiGrid, "饰品", trinkets.size() + " 件", ARCANE, false);
            addTop(slotCard, kpiGrid, 10);
        }
        box.addView(slotCard);

        // Scrollable Sub-tab Bar (never squished on narrow phones)
        String[] subTitles = {
                "庄园货币",
                "花名册 (" + heroes.size() + ")",
                "饰品库 (" + trinkets.size() + ")",
                "建筑与地牢",
                "原始 DSON"
        };
        HorizontalScrollView tabScroll = new HorizontalScrollView(context);
        tabScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout subRow = horizontal();
        for (int i = 0; i < subTitles.length; i++) {
            final int idx = i;
            View tabPill = makeSubTabPill(subTitles[i], saveSubTab == idx, () -> {
                if (saveSubTab != idx) {
                    saveSubTab = idx;
                    host.requestRedraw();
                }
            });
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-2, -2);
            if (i + 1 < subTitles.length) tlp.setMarginEnd(host.dp(6));
            subRow.addView(tabPill, tlp);
        }
        tabScroll.addView(subRow);
        addTop(box, tabScroll, 12);

        switch (saveSubTab) {
            case 0 -> addTop(box, buildEstateTab(wide), 10);
            case 1 -> addTop(box, buildHeroesTab(wide), 10);
            case 2 -> addTop(box, buildTrinketsTab(wide), 10);
            case 3 -> addTop(box, buildProgressTab(wide), 10);
            default -> addTop(box, buildRawDsonTab(wide), 10);
        }
        return box;
    }

    // ==================== SAVE SUB-TAB 0: ESTATE & WALLET ====================

    private View buildEstateTab(boolean wide) {
        LinearLayout container = vertical();
        if (estate == null) return container;

        // Card 1: Estate Identity & Mode
        LinearLayout idCard = card();
        idCard.addView(host.makeType("庄园名号与游戏模式", 15, GOLD, Typeface.DEFAULT_BOLD));
        EditText nameInput = editField(estate.estateName, false);
        addLabeledField(idCard, "庄园名称", nameInput);

        addTop(idCard, host.makeType("难度模式（点击立即切换）", 12, MUTED, Typeface.DEFAULT), 10);
        LinearLayout modeRow = horizontal();
        String[][] modes = {
                {"radiant", "光辉", "轻松节奏"},
                {"darkest", "极暗", "标准体验"},
                {"new_game_plus", "血月/冥暗", "高难挑战"}
        };
        for (int i = 0; i < modes.length; i++) {
            final String code = modes[i][0];
            final String label = modes[i][1];
            boolean active = code.equals(estate.gameMode);
            LinearLayout tile = vertical();
            tile.setGravity(Gravity.CENTER);
            tile.setPadding(host.dp(8), host.dp(10), host.dp(8), host.dp(10));
            tile.setBackground(host.makeSurface(active ? RED : RAISED, active ? GOLD : LINE, 10));
            tile.setOnClickListener(v -> {
                if (active) return;
                host.runTask("正在切换游戏难度…", () -> {
                    SaveEditor.writeEstate(context, selectedSlot, null, code, null, null);
                    refreshSync();
                }, "游戏模式已切换为 " + label);
            });
            tile.addView(host.makeType(label, 14, active ? INK : MUTED, Typeface.DEFAULT_BOLD));
            addTop(tile, host.makeType(modes[i][2], 11, active ? GOLD : MUTED, Typeface.DEFAULT), 2);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
            if (i + 1 < modes.length) lp.setMarginEnd(host.dp(6));
            modeRow.addView(tile, lp);
        }
        addTop(idCard, modeRow, 6);
        container.addView(idCard);

        // Card 2: Wallet 2-column grid + quick boost bar
        LinearLayout walletCard = card();
        LinearLayout wHead = horizontal();
        wHead.setGravity(Gravity.CENTER_VERTICAL);
        wHead.addView(host.makeType("金库与传家宝", 15, GOLD, Typeface.DEFAULT_BOLD),
                new LinearLayout.LayoutParams(0, -2, 1));
        wHead.addView(makePill("共 7 项货币", MUTED, DEEP));
        walletCard.addView(wHead);

        Map<String, EditText> walletInputs = new LinkedHashMap<>();
        List<Map.Entry<String, String>> entries = new ArrayList<>(CatalogData.CURRENCIES.entrySet());
        for (Map.Entry<String, String> entry : entries) {
            int val = estate.wallet.getOrDefault(entry.getKey(), 0);
            walletInputs.put(entry.getKey(), editField(Integer.toString(val), true));
        }
        EditText hsInput = editField(Integer.toString(estate.endlessHighscore), true);

        // Quick boost chips
        LinearLayout boostRow = horizontal();
        View addGold = makeFilterChip("+10万金币", false, () -> {
            EditText g = walletInputs.get("gold");
            if (g != null) g.setText(Integer.toString(parseIntSafe(g.getText().toString(), 0) + 100000));
        });
        View addHeirloom = makeFilterChip("+100全传家宝", false, () -> {
            for (String k : new String[]{"bust", "portrait", "deed", "crest"}) {
                EditText et = walletInputs.get(k);
                if (et != null) et.setText(Integer.toString(parseIntSafe(et.getText().toString(), 0) + 100));
            }
        });
        View maxAll = makeFilterChip("一键全拉满", true, () -> {
            Map<String, Integer> full = new LinkedHashMap<>();
            full.put("gold", 999999);
            full.put("bust", 999);
            full.put("portrait", 999);
            full.put("deed", 999);
            full.put("crest", 999);
            full.put("shard", 9999);
            full.put("blueprint", 20);
            host.runTask("正在拉满全部货币与传家宝…", () -> {
                SaveEditor.writeEstate(context, selectedSlot, null, null, full, null);
                refreshSync();
            }, "全部金币、传家宝、碎片与蓝图已拉满");
        });
        LinearLayout.LayoutParams bp1 = new LinearLayout.LayoutParams(0, -2, 1);
        bp1.setMarginEnd(host.dp(6));
        LinearLayout.LayoutParams bp2 = new LinearLayout.LayoutParams(0, -2, 1);
        bp2.setMarginEnd(host.dp(6));
        boostRow.addView(addGold, bp1);
        boostRow.addView(addHeirloom, bp2);
        boostRow.addView(maxAll, new LinearLayout.LayoutParams(0, -2, 1));
        addTop(walletCard, boostRow, 10);

        // 2-column grid for currencies + endless highscore
        List<String[]> fields = new ArrayList<>();
        for (Map.Entry<String, String> entry : entries) {
            fields.add(new String[]{entry.getKey(), entry.getValue()});
        }
        fields.add(new String[]{"__highscore__", "无尽农庄最高波次"});

        for (int i = 0; i < fields.size(); i += 2) {
            LinearLayout row = horizontal();
            for (int col = 0; col < 2; col++) {
                int idx = i + col;
                LinearLayout cell = vertical();
                if (idx < fields.size()) {
                    String key = fields.get(idx)[0];
                    String label = fields.get(idx)[1];
                    EditText et = "__highscore__".equals(key) ? hsInput : walletInputs.get(key);
                    cell.addView(host.makeType(label, 12, MUTED, Typeface.DEFAULT));
                    addTop(cell, et, 4);
                }
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, -2, 1);
                if (col == 0) clp.setMarginEnd(host.dp(10));
                row.addView(cell, clp);
            }
            addTop(walletCard, row, 10);
        }

        addTop(walletCard, host.makeButton("保存庄园与全部货币修改", true, () -> {
            Map<String, Integer> updates = new LinkedHashMap<>();
            for (Map.Entry<String, EditText> e : walletInputs.entrySet()) {
                updates.put(e.getKey(), Math.max(0, parseIntSafe(e.getValue().getText().toString(), 0)));
            }
            int hs = Math.max(0, parseIntSafe(hsInput.getText().toString(), estate.endlessHighscore));
            String nextName = nameInput.getText().toString().trim();
            host.runTask("正在写入庄园与货币数据…", () -> {
                SaveEditor.writeEstate(context, selectedSlot, nextName, null, updates, hs);
                refreshSync();
            }, "庄园与货币修改已保存（已自动快照原档）");
        }), 16);

        addTop(container, walletCard, 12);
        return container;
    }

    // ==================== SAVE SUB-TAB 1: HEROES ROSTER ====================

    private View buildHeroesTab(boolean wide) {
        LinearLayout box = vertical();

        // Batch actions + search & filter card
        LinearLayout top = card();
        LinearLayout head = horizontal();
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(host.makeType("花名册英雄", 15, GOLD, Typeface.DEFAULT_BOLD),
                new LinearLayout.LayoutParams(0, -2, 1));
        int injuredCount = 0, diseasedCount = 0, deadCount = 0;
        for (SaveEditor.HeroState h : heroes) {
            if (h.status == 3) deadCount++;
            if (h.hpFraction < 0.99f || h.stress > 0.5f || !h.affliction.isEmpty() || h.status == 3) injuredCount++;
            if (hasAnyDisease(h)) diseasedCount++;
        }
        head.addView(makePill("共 " + heroes.size() + " 名", INK, DEEP));
        top.addView(head);

        LinearLayout batch = horizontal();
        View healBtn = host.makeButton("全体满血清压复活", true, () ->
                host.runTask("正在恢复全体英雄状态…", () -> {
                    SaveEditor.batchImproveAllHeroes(context, selectedSlot, false, true);
                    refreshSync();
                }, "全体英雄已满血、清零压力、清除折磨疾病并复活阵亡角色"));
        View maxBtn = host.makeButton("全体满级满装 (Lv6)", false, () ->
                host.runTask("正在提升全体英雄等级与装备…", () -> {
                    SaveEditor.batchImproveAllHeroes(context, selectedSlot, true, false);
                    refreshSync();
                }, "全体英雄已升至 Lv6 且武器/护甲满级"));
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0, -2, 1);
        lp1.setMarginEnd(host.dp(8));
        batch.addView(healBtn, lp1);
        batch.addView(maxBtn, new LinearLayout.LayoutParams(0, -2, 1));
        addTop(top, batch, 10);

        // Search input + filter pills
        LinearLayout listHolder = vertical();
        EditText searchIn = editField(heroSearch, false);
        searchIn.setHint("按英雄名或职业搜索（例如 迪斯马 / 十字军）…");
        addTop(top, searchIn, 10);

        String[] filters = {
                "全部 (" + heroes.size() + ")",
                "需恢复 (" + injuredCount + ")",
                "含疾病 (" + diseasedCount + ")",
                "已阵亡 (" + deadCount + ")"
        };
        HorizontalScrollView fScroll = new HorizontalScrollView(context);
        fScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout fRow = horizontal();
        for (int i = 0; i < filters.length; i++) {
            final int idx = i;
            View chip = makeFilterChip(filters[i], heroFilter == idx, () -> {
                heroFilter = idx;
                host.requestRedraw();
            });
            LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(-2, -2);
            if (i + 1 < filters.length) flp.setMarginEnd(host.dp(6));
            fRow.addView(chip, flp);
        }
        fScroll.addView(fRow);
        addTop(top, fScroll, 8);
        box.addView(top);

        Runnable renderList = () -> {
            listHolder.removeAllViews();
            String q = heroSearch.trim().toLowerCase(Locale.ROOT);
            int shown = 0;
            for (SaveEditor.HeroState h : heroes) {
                if (heroFilter == 1 && !(h.hpFraction < 0.99f || h.stress > 0.5f || !h.affliction.isEmpty() || h.status == 3)) continue;
                if (heroFilter == 2 && !hasAnyDisease(h)) continue;
                if (heroFilter == 3 && h.status != 3) continue;
                String clsLabel = CatalogData.heroClassLabel(h.heroClass);
                if (!q.isEmpty()
                        && !h.name.toLowerCase(Locale.ROOT).contains(q)
                        && !clsLabel.toLowerCase(Locale.ROOT).contains(q)
                        && !h.heroClass.toLowerCase(Locale.ROOT).contains(q)) {
                    continue;
                }
                addTop(listHolder, buildHeroDossierCard(h), 8);
                shown++;
            }
            if (shown == 0) {
                LinearLayout empty = card();
                empty.addView(host.makeType("没有匹配当前筛选条件的英雄", 14, MUTED, Typeface.DEFAULT));
                addTop(listHolder, empty, 8);
            }
        };
        renderList.run();
        searchIn.addTextChangedListener(new SimpleTextWatcher(s -> {
            heroSearch = s;
            renderList.run();
        }));
        addTop(box, listHolder, 2);
        return box;
    }

    private View buildHeroDossierCard(SaveEditor.HeroState h) {
        LinearLayout row = card();
        String clsLabel = CatalogData.heroClassLabel(h.heroClass);
        int lv = CatalogData.resolveLevelForXp(h.resolveXp);
        int diseaseCnt = countDiseases(h);

        // Header line: Name + Class Pill + Lv Pill + State Pill
        LinearLayout top = horizontal();
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView nameTv = host.makeType(h.name, 16, INK, Typeface.DEFAULT_BOLD);
        nameTv.setSingleLine(true);
        nameTv.setEllipsize(TextUtils.TruncateAt.END);
        top.addView(nameTv, new LinearLayout.LayoutParams(0, -2, 1));

        top.addView(makePill(clsLabel + " · Lv." + lv, GOLD, GOLD_BG));
        View stateBadge;
        if (h.status == 3) {
            stateBadge = makePill("已阵亡", ERROR, CRIMSON_BG);
        } else if (!h.virtue.isEmpty()) {
            stateBadge = makePill("美德:" + shortVirtue(h.virtue), GREEN, GREEN_BG);
        } else if (!h.affliction.isEmpty()) {
            stateBadge = makePill("折磨:" + CatalogData.AFFLICTIONS.getOrDefault(h.affliction, h.affliction), ERROR, CRIMSON_BG);
        } else {
            stateBadge = makePill(h.statusLabel(), MUTED, DEEP);
        }
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-2, -2);
        slp.setMarginStart(host.dp(6));
        top.addView(stateBadge, slp);
        row.addView(top);

        // Dual visual bars: HP & Stress side-by-side
        LinearLayout bars = horizontal();
        int hpPct = Math.max(0, Math.min(100, Math.round(h.hpFraction * 100f)));
        int stressInt = Math.max(0, Math.min(200, Math.round(h.stress)));
        View hpBar = makeMeter("生命 " + hpPct + "%", hpPct / 100f, HP_FILL);
        View stBar = makeMeter("压力 " + stressInt + "/200", stressInt / 200f,
                stressInt >= 100 ? STRESS_HIGH : STRESS_MID);
        LinearLayout.LayoutParams b1 = new LinearLayout.LayoutParams(0, -2, 1);
        b1.setMarginEnd(host.dp(10));
        bars.addView(hpBar, b1);
        bars.addView(stBar, new LinearLayout.LayoutParams(0, -2, 1));
        addTop(row, bars, 8);

        // Meta line: Weapon/Armour + Quirks + Diseases
        String meta = String.format(Locale.ROOT,
                "武器 %d 级  ·  护甲 %d 级  ·  怪癖 %d 个%s",
                h.weaponRank + 1, h.armourRank + 1, h.quirks.size(),
                diseaseCnt > 0 ? "  ·  含 " + diseaseCnt + " 项疾病" : "");
        addTop(row, host.makeType(meta, 12, diseaseCnt > 0 ? ERROR : MUTED, Typeface.DEFAULT), 7);

        // Actions
        LinearLayout btns = horizontal();
        View editBtn = host.makeButton("属性精修 →", false, () -> openHeroDetailPage(h));
        View quirkBtn = host.makeButton(diseaseCnt > 0 ? "治病 / 怪癖 →" : "怪癖与疾病 →", false, () -> openHeroQuirksPage(h));
        LinearLayout.LayoutParams a1 = new LinearLayout.LayoutParams(0, -2, 1);
        a1.setMarginEnd(host.dp(8));
        btns.addView(editBtn, a1);
        btns.addView(quirkBtn, new LinearLayout.LayoutParams(0, -2, 1));
        addTop(row, btns, 8);
        return row;
    }

    // ==================== SECONDARY PAGE 1: HERO DETAIL ====================

    private void openHeroDetailPage(SaveEditor.HeroState h) {
        activeHeroGuid = h.guid;
        draftHeroName = h.name;
        draftHpPct = Math.max(1, Math.min(100, Math.round(h.hpFraction * 100f)));
        draftStress = Math.max(0, Math.min(200, Math.round(h.stress)));
        draftXp = h.resolveXp;
        draftWeapon = Math.max(0, Math.min(4, h.weaponRank));
        draftArmour = Math.max(0, Math.min(4, h.armourRank));
        draftVirtue = h.virtue;
        draftAffliction = h.affliction;
        draftRevive = (h.status == 3);
        subPage = SubPage.HERO_DETAIL;
        host.scrollToTop();
        host.requestRedraw();
    }

    private View buildHeroDetailPage(boolean wide, Typeface display) {
        SaveEditor.HeroState h = findHero(activeHeroGuid);
        if (h == null) {
            subPage = SubPage.NONE;
            return buildMainView(wide, display);
        }
        LinearLayout root = vertical();
        root.addView(buildSecondaryHeader(
                "← 返回花名册",
                draftHeroName.isEmpty() ? h.name : draftHeroName,
                CatalogData.heroClassLabel(h.heroClass) + " · 英雄属性与心智精修",
                "怪癖疾病 →",
                display,
                () -> openHeroQuirksPage(h)
        ));

        // Live preview dossier card
        LinearLayout preview = card();
        LinearLayout pHead = horizontal();
        pHead.setGravity(Gravity.CENTER_VERTICAL);
        int lv = CatalogData.resolveLevelForXp(draftXp);
        pHead.addView(host.makeType("实时预览", 13, GOLD, Typeface.DEFAULT_BOLD),
                new LinearLayout.LayoutParams(0, -2, 1));
        pHead.addView(makePill("Lv." + lv + " · 武器" + (draftWeapon + 1) + "/护甲" + (draftArmour + 1), GOLD, GOLD_BG));
        preview.addView(pHead);

        LinearLayout pBars = horizontal();
        View hpBar = makeMeter("生命 " + draftHpPct + "%", draftHpPct / 100f, HP_FILL);
        View stBar = makeMeter("压力 " + draftStress + "/200", draftStress / 200f,
                draftStress >= 100 ? STRESS_HIGH : STRESS_MID);
        LinearLayout.LayoutParams pb1 = new LinearLayout.LayoutParams(0, -2, 1);
        pb1.setMarginEnd(host.dp(10));
        pBars.addView(hpBar, pb1);
        pBars.addView(stBar, new LinearLayout.LayoutParams(0, -2, 1));
        addTop(preview, pBars, 8);

        // Quick preset strip
        LinearLayout presets = horizontal();
        View pHeal = makeFilterChip("满血0压复活", false, () -> {
            draftHpPct = 100;
            draftStress = 0;
            draftAffliction = "";
            draftRevive = true;
            host.requestRedraw();
        });
        View pMax = makeFilterChip("满级满装 (Lv6)", false, () -> {
            draftXp = 48;
            draftWeapon = 4;
            draftArmour = 4;
            host.requestRedraw();
        });
        View pGod = makeFilterChip("完美巅峰态", true, () -> {
            draftHpPct = 100;
            draftStress = 0;
            draftXp = 48;
            draftWeapon = 4;
            draftArmour = 4;
            draftAffliction = "";
            draftVirtue = "vigorous";
            draftRevive = true;
            host.requestRedraw();
        });
        LinearLayout.LayoutParams pr1 = new LinearLayout.LayoutParams(0, -2, 1);
        pr1.setMarginEnd(host.dp(6));
        LinearLayout.LayoutParams pr2 = new LinearLayout.LayoutParams(0, -2, 1);
        pr2.setMarginEnd(host.dp(6));
        presets.addView(pHeal, pr1);
        presets.addView(pMax, pr2);
        presets.addView(pGod, new LinearLayout.LayoutParams(0, -2, 1));
        addTop(preview, presets, 10);
        addTop(root, preview, 10);

        // Card 1: Name, HP, Stress, Revive
        LinearLayout basicCard = card();
        basicCard.addView(host.makeType("基础状态与生命压力", 15, GOLD, Typeface.DEFAULT_BOLD));
        EditText nameIn = editField(draftHeroName, false);
        nameIn.addTextChangedListener(new SimpleTextWatcher(s -> draftHeroName = s));
        addLabeledField(basicCard, "英雄名称", nameIn);

        // HP % quick pills + input
        addTop(basicCard, host.makeType("生命百分比 (1–100%)", 12, MUTED, Typeface.DEFAULT), 10);
        LinearLayout hpRow = horizontal();
        for (int v : new int[]{100, 75, 50, 25}) {
            View c = makeFilterChip(v + "%", draftHpPct == v, () -> {
                draftHpPct = v;
                host.requestRedraw();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
            lp.setMarginEnd(host.dp(6));
            hpRow.addView(c, lp);
        }
        EditText hpIn = editField(Integer.toString(draftHpPct), true);
        hpIn.addTextChangedListener(new SimpleTextWatcher(s -> draftHpPct = Math.max(1, Math.min(100, parseIntSafe(s, draftHpPct)))));
        hpRow.addView(hpIn, new LinearLayout.LayoutParams(host.dp(74), -2));
        addTop(basicCard, hpRow, 4);

        // Stress quick pills + input
        addTop(basicCard, host.makeType("压力值 (0–200)", 12, MUTED, Typeface.DEFAULT), 10);
        LinearLayout stRow = horizontal();
        for (int v : new int[]{0, 50, 100, 170}) {
            View c = makeFilterChip(Integer.toString(v), draftStress == v, () -> {
                draftStress = v;
                host.requestRedraw();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
            lp.setMarginEnd(host.dp(6));
            stRow.addView(c, lp);
        }
        EditText stIn = editField(Integer.toString(draftStress), true);
        stIn.addTextChangedListener(new SimpleTextWatcher(s -> draftStress = Math.max(0, Math.min(200, parseIntSafe(s, draftStress)))));
        stRow.addView(stIn, new LinearLayout.LayoutParams(host.dp(74), -2));
        addTop(basicCard, stRow, 4);

        // Revive toggle card
        addTop(basicCard, buildToggleRow("复活英雄并清除死门 / 心梗标记",
                h.status == 3 ? "当前英雄处于阵亡状态，开启后立即重返花名册" : "清除异常状态标记并确保可出征",
                draftRevive, () -> {
                    draftRevive = !draftRevive;
                    host.requestRedraw();
                }), 10);
        addTop(root, basicCard, 10);

        // Card 2: Resolve Level & Gear Ranks
        LinearLayout gearCard = card();
        gearCard.addView(host.makeType("决心等级与铁匠锻造", 15, GOLD, Typeface.DEFAULT_BOLD));

        addTop(gearCard, host.makeType("决心等级 (点击直接对齐标准经验值，当前 XP: " + draftXp + ")", 12, MUTED, Typeface.DEFAULT), 8);
        LinearLayout lvRow = horizontal();
        for (int i = 0; i <= 6; i++) {
            final int targetXp = CatalogData.RESOLVE_THRESHOLDS[i];
            View c = makeFilterChip("Lv." + i, lv == i, () -> {
                draftXp = targetXp;
                host.requestRedraw();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
            if (i < 6) lp.setMarginEnd(host.dp(4));
            lvRow.addView(c, lp);
        }
        addTop(gearCard, lvRow, 4);

        addTop(gearCard, host.makeType("武器锻造等级", 12, MUTED, Typeface.DEFAULT), 10);
        LinearLayout wpRow = horizontal();
        for (int i = 0; i < 5; i++) {
            final int rank = i;
            View c = makeFilterChip((i + 1) + " 级", draftWeapon == rank, () -> {
                draftWeapon = rank;
                host.requestRedraw();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
            if (i < 4) lp.setMarginEnd(host.dp(5));
            wpRow.addView(c, lp);
        }
        addTop(gearCard, wpRow, 4);

        addTop(gearCard, host.makeType("护甲锻造等级", 12, MUTED, Typeface.DEFAULT), 10);
        LinearLayout arRow = horizontal();
        for (int i = 0; i < 5; i++) {
            final int rank = i;
            View c = makeFilterChip((i + 1) + " 级", draftArmour == rank, () -> {
                draftArmour = rank;
                host.requestRedraw();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
            if (i < 4) lp.setMarginEnd(host.dp(5));
            arRow.addView(c, lp);
        }
        addTop(gearCard, arRow, 4);
        addTop(root, gearCard, 10);

        // Card 3: Virtues & Afflictions chip grids
        LinearLayout mindCard = card();
        mindCard.addView(host.makeType("心智状态（美德 / 折磨二选一）", 15, GOLD, Typeface.DEFAULT_BOLD));
        addTop(mindCard, host.makeType("美德（正面爆发状态）", 12, GREEN, Typeface.DEFAULT_BOLD), 8);
        addChipGrid(mindCard, new ArrayList<>(CatalogData.VIRTUES.entrySet()), draftVirtue, 3, true, key -> {
            draftVirtue = key;
            if (!key.isEmpty()) draftAffliction = "";
            host.requestRedraw();
        });

        addTop(mindCard, host.makeType("折磨（负面崩溃状态）", 12, ERROR, Typeface.DEFAULT_BOLD), 12);
        addChipGrid(mindCard, new ArrayList<>(CatalogData.AFFLICTIONS.entrySet()), draftAffliction, 3, false, key -> {
            draftAffliction = key;
            if (!key.isEmpty()) draftVirtue = "";
            host.requestRedraw();
        });
        addTop(root, mindCard, 10);

        // Save / Cancel CTA bar
        LinearLayout actions = horizontal();
        View saveBtn = host.makeButton("保存英雄全部修改", true, () -> {
            String nm = draftHeroName.trim().isEmpty() ? h.name : draftHeroName.trim();
            float hpFrac = Math.max(1, Math.min(100, draftHpPct)) / 100f;
            float stVal = Math.max(0, Math.min(200, draftStress));
            host.runTask("正在写入英雄嵌套 DSON…", () -> {
                SaveEditor.updateHero(context, selectedSlot, h.guid, nm, hpFrac, stVal,
                        draftXp, draftWeapon, draftArmour, draftAffliction, draftVirtue, draftRevive);
                refreshSync();
                subPage = SubPage.NONE;
            }, "英雄「" + nm + "」已保存");
        });
        View cancelBtn = host.makeButton("返回", false, this::handleBack);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0, -2, 2);
        slp.setMarginEnd(host.dp(8));
        actions.addView(saveBtn, slp);
        actions.addView(cancelBtn, new LinearLayout.LayoutParams(0, -2, 1));
        addTop(root, actions, 14);
        return root;
    }

    // ==================== SECONDARY PAGE 2: HERO QUIRKS & DISEASES ====================

    private void openHeroQuirksPage(SaveEditor.HeroState h) {
        activeHeroGuid = h.guid;
        draftQuirks.clear();
        draftQuirks.addAll(h.quirks);
        quirkSearch = "";
        subPage = SubPage.HERO_QUIRKS;
        host.scrollToTop();
        host.requestRedraw();
    }

    private View buildHeroQuirksPage(boolean wide, Typeface display) {
        SaveEditor.HeroState h = findHero(activeHeroGuid);
        if (h == null) {
            subPage = SubPage.NONE;
            return buildMainView(wide, display);
        }
        LinearLayout root = vertical();
        root.addView(buildSecondaryHeader(
                "← 返回花名册",
                h.name + " · 怪癖与疾病",
                CatalogData.heroClassLabel(h.heroClass) + " · 锁定强力正面特质或一键清病",
                "属性精修 →",
                display,
                () -> openHeroDetailPage(h)
        ));

        // Card 1: Current Quirks + Batch buttons
        LinearLayout curCard = card();
        LinearLayout cHead = horizontal();
        cHead.setGravity(Gravity.CENTER_VERTICAL);
        int disCnt = 0;
        for (SaveEditor.QuirkEntry q : draftQuirks) if (CatalogData.isDisease(q.id)) disCnt++;
        cHead.addView(host.makeType("当前持有特质 (" + draftQuirks.size() + ")", 15, GOLD, Typeface.DEFAULT_BOLD),
                new LinearLayout.LayoutParams(0, -2, 1));
        if (disCnt > 0) cHead.addView(makePill("含 " + disCnt + " 项疾病", ERROR, CRIMSON_BG));
        curCard.addView(cHead);

        LinearLayout batchRow = horizontal();
        View cureBtn = makeFilterChip("一键清除全部疾病", disCnt > 0, () -> {
            draftQuirks.removeIf(q -> CatalogData.isDisease(q.id));
            host.requestRedraw();
        });
        View lockPosBtn = makeFilterChip("锁定所有非疾病项", false, () -> {
            for (int i = 0; i < draftQuirks.size(); i++) {
                SaveEditor.QuirkEntry q = draftQuirks.get(i);
                if (!CatalogData.isDisease(q.id)) {
                    draftQuirks.set(i, new SaveEditor.QuirkEntry(q.id, true, q.isNew));
                }
            }
            host.requestRedraw();
        });
        LinearLayout.LayoutParams bp1 = new LinearLayout.LayoutParams(0, -2, 1);
        bp1.setMarginEnd(host.dp(6));
        batchRow.addView(cureBtn, bp1);
        batchRow.addView(lockPosBtn, new LinearLayout.LayoutParams(0, -2, 1));
        addTop(curCard, batchRow, 8);

        if (draftQuirks.isEmpty()) {
            addTop(curCard, host.makeType("当前没有任何怪癖或疾病，可从下方精选图鉴点选添加。", 13, MUTED, Typeface.DEFAULT), 10);
        } else {
            for (int i = 0; i < draftQuirks.size(); i++) {
                final int idx = i;
                SaveEditor.QuirkEntry q = draftQuirks.get(i);
                boolean isDis = CatalogData.isDisease(q.id);
                LinearLayout row = horizontal();
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(host.dp(10), host.dp(8), host.dp(10), host.dp(8));
                row.setBackground(host.makeSurface(isDis ? CRIMSON_BG : RAISED, isDis ? RED : LINE, 9));

                LinearLayout words = vertical();
                LinearLayout titleLine = horizontal();
                titleLine.setGravity(Gravity.CENTER_VERTICAL);
                titleLine.addView(host.makeType(CatalogData.quirkLabel(q.id), 13, isDis ? ERROR : INK, Typeface.DEFAULT_BOLD));
                if (q.locked) {
                    View lp = makePill("已锁定", GOLD, GOLD_BG);
                    LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(-2, -2);
                    mlp.setMarginStart(host.dp(6));
                    titleLine.addView(lp, mlp);
                }
                words.addView(titleLine);
                addTop(words, host.makeType(q.id, 11, MUTED, Typeface.MONOSPACE), 2);
                row.addView(words, new LinearLayout.LayoutParams(0, -2, 1));

                if (!isDis) {
                    View lockBtn = makeCompactAction(q.locked ? "解锁" : "锁定", false, () -> {
                        draftQuirks.set(idx, new SaveEditor.QuirkEntry(q.id, !q.locked, q.isNew));
                        host.requestRedraw();
                    });
                    LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-2, -2);
                    llp.setMarginEnd(host.dp(6));
                    row.addView(lockBtn, llp);
                }
                row.addView(makeCompactAction("移除", false, () -> {
                    draftQuirks.remove(idx);
                    host.requestRedraw();
                }));
                addTop(curCard, row, 6);
            }
        }
        addTop(curCard, host.makeButton("保存怪癖与疾病到存档", true, () -> {
            List<SaveEditor.QuirkEntry> copy = new ArrayList<>(draftQuirks);
            host.runTask("正在写入怪癖列表…", () -> {
                SaveEditor.updateHeroQuirks(context, selectedSlot, h.guid, copy);
                refreshSync();
                subPage = SubPage.NONE;
            }, h.name + " 的怪癖与疾病已保存");
        }), 12);
        addTop(root, curCard, 10);

        // Card 2: Positive Quirks Codex with live search
        LinearLayout codexCard = card();
        codexCard.addView(host.makeType("精选强力正面怪癖图鉴（点击即添加并锁定）", 15, GOLD, Typeface.DEFAULT_BOLD));
        EditText qSearch = editField(quirkSearch, false);
        qSearch.setHint("搜索怪癖名或效果（如 速度 / 暴击 / 闪避 / luminous）…");
        addTop(codexCard, qSearch, 8);

        LinearLayout qList = vertical();
        Runnable renderCodex = () -> {
            qList.removeAllViews();
            String q = quirkSearch.trim().toLowerCase(Locale.ROOT);
            Set<String> ownedIds = new LinkedHashSet<>();
            for (SaveEditor.QuirkEntry e : draftQuirks) ownedIds.add(e.id);
            int shown = 0;
            for (CatalogData.NamedEntry entry : CatalogData.POPULAR_POSITIVE_QUIRKS) {
                if (!q.isEmpty()
                        && !entry.label.toLowerCase(Locale.ROOT).contains(q)
                        && !entry.id.toLowerCase(Locale.ROOT).contains(q)) {
                    continue;
                }
                boolean has = ownedIds.contains(entry.id);
                LinearLayout r = horizontal();
                r.setGravity(Gravity.CENTER_VERTICAL);
                r.setPadding(host.dp(10), host.dp(8), host.dp(10), host.dp(8));
                r.setBackground(host.makeSurface(has ? GREEN_BG : RAISED, has ? GREEN : LINE, 9));
                LinearLayout w = vertical();
                w.addView(host.makeType(entry.label, 13, has ? GREEN : INK, Typeface.DEFAULT_BOLD));
                addTop(w, host.makeType(entry.id, 11, MUTED, Typeface.MONOSPACE), 1);
                r.addView(w, new LinearLayout.LayoutParams(0, -2, 1));
                if (has) {
                    r.addView(makePill("已持有", GREEN, DEEP));
                } else {
                    r.addView(makeCompactAction("+ 锁定添加", true, () -> {
                        draftQuirks.add(new SaveEditor.QuirkEntry(entry.id, true, false));
                        host.requestRedraw();
                    }));
                }
                addTop(qList, r, 6);
                shown++;
            }
            if (shown == 0) {
                addTop(qList, host.makeType("未找到匹配的正面怪癖", 13, MUTED, Typeface.DEFAULT), 8);
            }
        };
        renderCodex.run();
        qSearch.addTextChangedListener(new SimpleTextWatcher(s -> {
            quirkSearch = s;
            renderCodex.run();
        }));
        addTop(codexCard, qList, 4);
        addTop(root, codexCard, 10);

        // Card 3: Custom / Mod Quirk ID
        LinearLayout customCard = card();
        customCard.addView(host.makeType("添加自定义 / Mod 怪癖 ID", 14, GOLD, Typeface.DEFAULT_BOLD));
        LinearLayout customRow = horizontal();
        customRow.setGravity(Gravity.CENTER_VERTICAL);
        EditText customIn = editField("", false);
        customIn.setHint("例如 twilight_dreamer 或 Mod 怪癖 ID");
        LinearLayout.LayoutParams cilp = new LinearLayout.LayoutParams(0, -2, 1);
        cilp.setMarginEnd(host.dp(8));
        customRow.addView(customIn, cilp);
        customRow.addView(host.makeButton("+ 添加", false, () -> {
            String id = customIn.getText().toString().trim();
            if (id.isEmpty()) return;
            if (draftQuirks.stream().noneMatch(item -> item.id.equals(id))) {
                draftQuirks.add(new SaveEditor.QuirkEntry(id, true, false));
                host.requestRedraw();
            } else {
                host.showMessage("该怪癖已在列表中：" + id, false);
            }
        }));
        addTop(customCard, customRow, 8);
        addTop(root, customCard, 10);
        return root;
    }

    // ==================== SAVE SUB-TAB 2: TRINKETS & CODEX SECONDARY PAGE ====================

    private View buildTrinketsTab(boolean wide) {
        LinearLayout box = vertical();
        LinearLayout top = card();
        LinearLayout head = horizontal();
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(host.makeType("饰品仓库", 15, GOLD, Typeface.DEFAULT_BOLD),
                new LinearLayout.LayoutParams(0, -2, 1));
        head.addView(makePill("共 " + trinkets.size() + " 件", ARCANE, ARCANE_BG));
        top.addView(head);

        addTop(top, host.makeButton("+ 打开分类饰品图鉴 / 批量添加 →", true, () -> {
            codexSearch = "";
            subPage = SubPage.TRINKET_CODEX;
            host.scrollToTop();
            host.requestRedraw();
        }), 10);

        EditText searchIn = editField(ownedTrinketSearch, false);
        searchIn.setHint("搜索仓库中已拥有的饰品（中文名或 ID）…");
        addTop(top, searchIn, 8);
        box.addView(top);

        LinearLayout listHolder = vertical();
        Runnable renderOwned = () -> {
            listHolder.removeAllViews();
            String q = ownedTrinketSearch.trim().toLowerCase(Locale.ROOT);
            int shown = 0;
            for (SaveEditor.TrinketEntry t : trinkets) {
                String label = CatalogData.trinketLabel(t.id);
                String cat = CatalogData.trinketCategory(t.id);
                if (!q.isEmpty()
                        && !label.toLowerCase(Locale.ROOT).contains(q)
                        && !t.id.toLowerCase(Locale.ROOT).contains(q)
                        && !cat.toLowerCase(Locale.ROOT).contains(q)) {
                    continue;
                }
                LinearLayout row = card();
                LinearLayout line = horizontal();
                line.setGravity(Gravity.CENTER_VERTICAL);
                LinearLayout words = vertical();
                LinearLayout titleRow = horizontal();
                titleRow.setGravity(Gravity.CENTER_VERTICAL);
                titleRow.addView(makePill(cat, categoryColor(cat), categoryBg(cat)));
                TextView lbl = host.makeType(label, 14, INK, Typeface.DEFAULT_BOLD);
                LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(0, -2, 1);
                llp.setMarginStart(host.dp(6));
                titleRow.addView(lbl, llp);
                words.addView(titleRow);
                addTop(words, host.makeType("#" + t.index + "  ·  " + t.id, 11, MUTED, Typeface.MONOSPACE), 3);
                line.addView(words, new LinearLayout.LayoutParams(0, -2, 1));

                View dupBtn = makeCompactAction("+1", false, () ->
                        host.runTask("正在复制饰品…", () -> {
                            SaveEditor.addTrinket(context, selectedSlot, t.id, 1);
                            refreshSync();
                        }, "已再添加 1 件「" + label + "」"));
                LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-2, -2);
                dlp.setMarginEnd(host.dp(6));
                line.addView(dupBtn, dlp);
                line.addView(makeCompactAction("移除", false, () ->
                        host.runTask("正在移除饰品…", () -> {
                            SaveEditor.removeTrinket(context, selectedSlot, t.index);
                            refreshSync();
                        }, "已移除饰品：" + label)));
                row.addView(line);
                addTop(listHolder, row, 6);
                shown++;
            }
            if (shown == 0) {
                LinearLayout empty = card();
                empty.addView(host.makeType(trinkets.isEmpty() ? "饰品仓库为空，点上方按钮打开图鉴即可添加。" : "没有匹配当前搜索词的饰品。",
                        13, MUTED, Typeface.DEFAULT));
                addTop(listHolder, empty, 8);
            }
        };
        renderOwned.run();
        searchIn.addTextChangedListener(new SimpleTextWatcher(s -> {
            ownedTrinketSearch = s;
            renderOwned.run();
        }));
        addTop(box, listHolder, 2);
        return box;
    }

    private View buildTrinketCodexPage(boolean wide, Typeface display) {
        LinearLayout root = vertical();
        root.addView(buildSecondaryHeader(
                "← 返回饰品库",
                "饰品分类图鉴",
                "按流派筛选或搜索，一键批量收入当前档位仓库",
                "仓库 " + trinkets.size() + " 件",
                display,
                this::handleBack
        ));

        // Count owned per id
        Map<String, Integer> ownedCounts = new LinkedHashMap<>();
        for (SaveEditor.TrinketEntry t : trinkets) {
            ownedCounts.put(t.id, ownedCounts.getOrDefault(t.id, 0) + 1);
        }

        // Controls card: search + quantity + category chips
        LinearLayout ctrl = card();
        EditText searchIn = editField(codexSearch, false);
        searchIn.setHint("搜索饰品名或英文 ID（如 祖先 / 专注 / 彗星 / ring）…");
        ctrl.addView(searchIn);

        LinearLayout qtyRow = horizontal();
        qtyRow.setGravity(Gravity.CENTER_VERTICAL);
        qtyRow.addView(host.makeType("单次添加数量：", 12, MUTED, Typeface.DEFAULT));
        for (int q : new int[]{1, 2, 4}) {
            View chip = makeFilterChip("×" + q, codexQuantity == q, () -> {
                codexQuantity = q;
                host.requestRedraw();
            });
            LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(-2, -2);
            qlp.setMarginStart(host.dp(6));
            qtyRow.addView(chip, qlp);
        }
        addTop(ctrl, qtyRow, 8);

        Set<String> cats = new LinkedHashSet<>();
        cats.add("全部");
        for (CatalogData.NamedEntry e : CatalogData.CURATED_TRINKETS) cats.add(e.category);
        HorizontalScrollView catScroll = new HorizontalScrollView(context);
        catScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout catRow = horizontal();
        for (String c : cats) {
            View chip = makeFilterChip(c, c.equals(codexCategory), () -> {
                codexCategory = c;
                host.requestRedraw();
            });
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, -2);
            clp.setMarginEnd(host.dp(6));
            catRow.addView(chip, clp);
        }
        catScroll.addView(catRow);
        addTop(ctrl, catScroll, 8);
        addTop(root, ctrl, 10);

        // Codex list
        LinearLayout listHolder = vertical();
        Runnable renderCodex = () -> {
            listHolder.removeAllViews();
            String q = codexSearch.trim().toLowerCase(Locale.ROOT);
            int shown = 0;
            for (CatalogData.NamedEntry e : CatalogData.CURATED_TRINKETS) {
                if (!"全部".equals(codexCategory) && !e.category.equals(codexCategory)) continue;
                if (!q.isEmpty()
                        && !e.label.toLowerCase(Locale.ROOT).contains(q)
                        && !e.id.toLowerCase(Locale.ROOT).contains(q)) {
                    continue;
                }
                int owned = ownedCounts.getOrDefault(e.id, 0);
                LinearLayout row = card();
                LinearLayout line = horizontal();
                line.setGravity(Gravity.CENTER_VERTICAL);
                LinearLayout words = vertical();
                LinearLayout topLine = horizontal();
                topLine.setGravity(Gravity.CENTER_VERTICAL);
                topLine.addView(makePill(e.category, categoryColor(e.category), categoryBg(e.category)));
                TextView nameTv = host.makeType(e.label, 14, INK, Typeface.DEFAULT_BOLD);
                LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(-2, -2);
                nlp.setMarginStart(host.dp(6));
                topLine.addView(nameTv, nlp);
                if (owned > 0) {
                    View ownPill = makePill("已有 ×" + owned, GREEN, GREEN_BG);
                    LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(-2, -2);
                    olp.setMarginStart(host.dp(6));
                    topLine.addView(ownPill, olp);
                }
                words.addView(topLine);
                addTop(words, host.makeType(e.id, 11, MUTED, Typeface.MONOSPACE), 3);
                line.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
                line.addView(makeCompactAction("+ 添加 ×" + codexQuantity, true, () -> {
                    final int amount = codexQuantity;
                    host.runTask("正在添加饰品…", () -> {
                        SaveEditor.addTrinket(context, selectedSlot, e.id, amount);
                        refreshSync();
                    }, "已添加「" + e.label + "」×" + amount);
                }));
                row.addView(line);
                addTop(listHolder, row, 6);
                shown++;
            }
            if (shown == 0) {
                LinearLayout empty = card();
                empty.addView(host.makeType("当前分类下没有匹配饰品，可尝试切换「全部」或在底部输入自定义 ID。", 13, MUTED, Typeface.DEFAULT));
                addTop(listHolder, empty, 8);
            }
        };
        renderCodex.run();
        searchIn.addTextChangedListener(new SimpleTextWatcher(s -> {
            codexSearch = s;
            renderCodex.run();
        }));
        addTop(root, listHolder, 2);

        // Custom / Mod Trinket ID card
        LinearLayout customCard = card();
        customCard.addView(host.makeType("添加自定义 / Mod 饰品 ID", 14, GOLD, Typeface.DEFAULT_BOLD));
        LinearLayout cRow = horizontal();
        cRow.setGravity(Gravity.CENTER_VERTICAL);
        EditText cIn = editField("", false);
        cIn.setHint("输入任意原版或 Mod 饰品 ID");
        LinearLayout.LayoutParams cilp = new LinearLayout.LayoutParams(0, -2, 1);
        cilp.setMarginEnd(host.dp(8));
        cRow.addView(cIn, cilp);
        cRow.addView(host.makeButton("+ 添加 ×" + codexQuantity, false, () -> {
            String id = cIn.getText().toString().trim();
            if (id.isEmpty()) return;
            final int amount = codexQuantity;
            host.runTask("正在添加自定义饰品…", () -> {
                SaveEditor.addTrinket(context, selectedSlot, id, amount);
                refreshSync();
            }, "已添加饰品 " + id + " ×" + amount);
        }));
        addTop(customCard, cRow, 8);
        addTop(root, customCard, 10);
        return root;
    }

    // ==================== SAVE SUB-TAB 3: TOWN & DUNGEONS ====================

    private View buildProgressTab(boolean wide) {
        LinearLayout container = vertical();

        // Card 1: Town 8 Facilities
        LinearLayout townCard = card();
        townCard.addView(host.makeType("城镇 8 座核心建筑升级", 15, GOLD, Typeface.DEFAULT_BOLD));
        addTop(townCard, host.makeType("一键激活全部建筑的满级折扣、槽位与高级招募树，安全合并至 persist.upgrades.json。",
                12, MUTED, Typeface.DEFAULT), 4);

        String[] buildings = {"铁匠铺", "公会", "马车", "疗养院", "酒馆", "修道院", "篷车", "露营导师"};
        for (int i = 0; i < buildings.length; i += 4) {
            LinearLayout bRow = horizontal();
            for (int c = 0; c < 4 && i + c < buildings.length; c++) {
                View p = makePill(buildings[i + c], INK, RAISED);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
                if (c < 3) lp.setMarginEnd(host.dp(6));
                bRow.addView(p, lp);
            }
            addTop(townCard, bRow, 6);
        }
        addTop(townCard, host.makeButton("一键解锁全部城镇建筑满级", true, () -> {
            final int[] count = {0};
            host.runTask("正在写入城镇升级表…", () -> {
                count[0] = SaveEditor.unlockAllBuildingUpgrades(context, selectedSlot);
                refreshSync();
            }, "已完成城镇建筑全解锁（新增/激活 " + count[0] + " 项升级）");
        }), 12);
        container.addView(townCard);

        // Card 2: Dungeon Regions XP (2-column grid + max preset)
        LinearLayout dCard = card();
        LinearLayout dHead = horizontal();
        dHead.setGravity(Gravity.CENTER_VERTICAL);
        dHead.addView(host.makeType("各地牢区域探索进度 (XP)", 15, GOLD, Typeface.DEFAULT_BOLD),
                new LinearLayout.LayoutParams(0, -2, 1));
        Map<String, EditText> xpInputs = new LinkedHashMap<>();
        dHead.addView(makeCompactAction("全区满级 (150 XP)", false, () -> {
            for (EditText et : xpInputs.values()) et.setText("150");
        }));
        dCard.addView(dHead);

        List<Map.Entry<String, String>> regions = new ArrayList<>(CatalogData.DUNGEONS.entrySet());
        for (Map.Entry<String, String> r : regions) {
            int cur = dungeonXp.getOrDefault(r.getKey(), 0);
            xpInputs.put(r.getKey(), editField(Integer.toString(cur), true));
        }

        for (int i = 0; i < regions.size(); i += 2) {
            LinearLayout row = horizontal();
            for (int col = 0; col < 2; col++) {
                int idx = i + col;
                LinearLayout cell = vertical();
                if (idx < regions.size()) {
                    String key = regions.get(idx).getKey();
                    String label = regions.get(idx).getValue();
                    int cur = dungeonXp.getOrDefault(key, 0);
                    int lv = CatalogData.dungeonLevelForXp(cur);
                    LinearLayout lh = horizontal();
                    lh.setGravity(Gravity.CENTER_VERTICAL);
                    lh.addView(host.makeType(label, 12, INK, Typeface.DEFAULT_BOLD),
                            new LinearLayout.LayoutParams(0, -2, 1));
                    lh.addView(makePill("Lv." + lv, GOLD, GOLD_BG));
                    cell.addView(lh);
                    addTop(cell, xpInputs.get(key), 4);
                }
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, -2, 1);
                if (col == 0) clp.setMarginEnd(host.dp(10));
                row.addView(cell, clp);
            }
            addTop(dCard, row, 10);
        }

        addTop(dCard, host.makeButton("保存地牢区域进度", false, () -> {
            Map<String, Integer> next = new LinkedHashMap<>();
            for (Map.Entry<String, EditText> e : xpInputs.entrySet()) {
                next.put(e.getKey(), Math.max(0, parseIntSafe(e.getValue().getText().toString(), 0)));
            }
            host.runTask("正在保存地牢进度…", () -> {
                SaveEditor.writeDungeonXp(context, selectedSlot, next);
                refreshSync();
            }, "地牢区域经验已更新");
        }), 14);
        addTop(container, dCard, 12);
        return container;
    }

    // ==================== SAVE SUB-TAB 4: RAW DSON BROWSER & SECONDARY EDITOR ====================

    private View buildRawDsonTab(boolean wide) {
        LinearLayout box = vertical();
        LinearLayout top = card();
        top.addView(host.makeType("全字段原始 DSON 二进制浏览器", 15, GOLD, Typeface.DEFAULT_BOLD));
        addTop(top, host.makeType("直接查看并无损修改档位内任意 persist.*.json 标量节点，写入前自动做结构往返自检。",
                12, MUTED, Typeface.DEFAULT), 4);

        // File selector horizontal pills
        HorizontalScrollView fileScroll = new HorizontalScrollView(context);
        fileScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout fileRow = horizontal();
        for (int i = 0; i < dsonFiles.size(); i++) {
            final String fname = dsonFiles.get(i);
            boolean active = fname.equals(selectedDsonFile);
            String shortName = fname.replace("persist.", "").replace(".json", "");
            View chip = makeFilterChip(shortName, active, () -> {
                if (!active) {
                    selectedDsonFile = fname;
                    rawPath.clear();
                    rawSearch = "";
                    host.runTask("正在解析 " + fname + "…", this::refreshSync, "已打开 " + fname);
                }
            });
            LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(-2, -2);
            if (i + 1 < dsonFiles.size()) flp.setMarginEnd(host.dp(6));
            fileRow.addView(chip, flp);
        }
        fileScroll.addView(fileRow);
        addTop(top, fileScroll, 8);

        // Clickable Breadcrumb Bar
        HorizontalScrollView crumbScroll = new HorizontalScrollView(context);
        crumbScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout crumbRow = horizontal();
        crumbRow.setGravity(Gravity.CENTER_VERTICAL);
        crumbRow.addView(makeFilterChip("/" + selectedDsonFile, rawPath.isEmpty(), () -> {
            if (!rawPath.isEmpty()) {
                rawPath.clear();
                rawSearch = "";
                host.runTask("返回根节点…", this::refreshSync, "");
            }
        }));
        for (int i = 0; i < rawPath.size(); i++) {
            final int depth = i + 1;
            TextView sep = host.makeType(" › ", 13, MUTED, Typeface.DEFAULT_BOLD);
            crumbRow.addView(sep);
            crumbRow.addView(makeFilterChip(rawPath.get(i), depth == rawPath.size(), () -> {
                if (depth < rawPath.size()) {
                    while (rawPath.size() > depth) rawPath.remove(rawPath.size() - 1);
                    rawSearch = "";
                    host.runTask("跳转到路径…", this::refreshSync, "");
                }
            }));
        }
        crumbScroll.addView(crumbRow);
        addTop(top, crumbScroll, 8);

        // Search box
        EditText searchIn = editField(rawSearch, false);
        searchIn.setHint("筛选当前层级的字段名或值…");
        addTop(top, searchIn, 8);
        box.addView(top);

        LinearLayout listHolder = vertical();
        Runnable renderRaw = () -> {
            listHolder.removeAllViews();
            String q = rawSearch.trim().toLowerCase(Locale.ROOT);
            int shown = 0;
            for (SaveEditor.RawEntry entry : rawEntries) {
                if (!q.isEmpty()
                        && !entry.key.toLowerCase(Locale.ROOT).contains(q)
                        && !entry.displayValue.toLowerCase(Locale.ROOT).contains(q)) {
                    continue;
                }
                LinearLayout row = card();
                LinearLayout line = horizontal();
                line.setGravity(Gravity.CENTER_VERTICAL);
                LinearLayout words = vertical();
                LinearLayout keyRow = horizontal();
                keyRow.setGravity(Gravity.CENTER_VERTICAL);
                keyRow.addView(makePill(entry.typeTag.toUpperCase(Locale.ROOT), typeTagColor(entry.typeTag), DEEP));
                TextView kTv = host.makeType(entry.key, 14, INK, Typeface.DEFAULT_BOLD);
                LinearLayout.LayoutParams klp = new LinearLayout.LayoutParams(0, -2, 1);
                klp.setMarginStart(host.dp(6));
                keyRow.addView(kTv, klp);
                words.addView(keyRow);
                TextView vTv = host.makeType(entry.displayValue, 12, MUTED, Typeface.MONOSPACE);
                vTv.setMaxLines(2);
                vTv.setEllipsize(TextUtils.TruncateAt.END);
                addTop(words, vTv, 3);
                line.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
                if (entry.object) {
                    line.addView(makeCompactAction("展开 →", false, () -> {
                        rawPath.add(entry.key);
                        rawSearch = "";
                        host.runTask("正在展开 " + entry.key + "…", this::refreshSync, "");
                    }));
                } else if (entry.editable) {
                    line.addView(makeCompactAction("编辑 →", true, () -> {
                        editingRawEntry = entry;
                        draftRawType = entry.typeTag;
                        draftRawValue = "string".equals(entry.typeTag) || "bool".equals(entry.typeTag)
                                ? entry.displayValue : extractPrimaryNumeric(entry.displayValue);
                        subPage = SubPage.RAW_EDIT;
                        host.scrollToTop();
                        host.requestRedraw();
                    }));
                }
                row.addView(line);
                addTop(listHolder, row, 6);
                shown++;
            }
            if (shown == 0) {
                LinearLayout empty = card();
                empty.addView(host.makeType("当前层级没有匹配字段。", 13, MUTED, Typeface.DEFAULT));
                addTop(listHolder, empty, 8);
            }
        };
        renderRaw.run();
        searchIn.addTextChangedListener(new SimpleTextWatcher(s -> {
            rawSearch = s;
            renderRaw.run();
        }));
        addTop(box, listHolder, 2);
        return box;
    }

    private View buildRawEditPage(boolean wide, Typeface display) {
        if (editingRawEntry == null) {
            subPage = SubPage.NONE;
            return buildMainView(wide, display);
        }
        SaveEditor.RawEntry entry = editingRawEntry;
        LinearLayout root = vertical();
        root.addView(buildSecondaryHeader(
                "← 返回 DSON 浏览",
                "编辑节点：" + entry.key,
                selectedDsonFile + "  ·  /" + String.join("/", rawPath),
                entry.typeTag.toUpperCase(Locale.ROOT),
                display,
                this::handleBack
        ));

        LinearLayout infoCard = card();
        infoCard.addView(host.makeType("当前二进制节点信息", 14, GOLD, Typeface.DEFAULT_BOLD));
        addTop(infoCard, host.makeType("完整路径：/" + (rawPath.isEmpty() ? entry.key : String.join("/", rawPath) + "/" + entry.key),
                12, INK, Typeface.MONOSPACE), 6);
        addTop(infoCard, host.makeType("原始读值：" + entry.displayValue, 12, MUTED, Typeface.MONOSPACE), 4);
        addTop(root, infoCard, 10);

        LinearLayout editCard = card();
        editCard.addView(host.makeType("写入新值", 14, GOLD, Typeface.DEFAULT_BOLD));

        if ("int".equals(entry.typeTag) || "float".equals(entry.typeTag)) {
            addTop(editCard, host.makeType("数值编码类型（4 字节标量请按游戏字段实际类型选择）", 12, MUTED, Typeface.DEFAULT), 8);
            LinearLayout tRow = horizontal();
            View bInt = makeFilterChip("32位整数 (int32)", "int".equals(draftRawType), () -> {
                draftRawType = "int";
                host.requestRedraw();
            });
            View bFlt = makeFilterChip("32位浮点 (float32)", "float".equals(draftRawType), () -> {
                draftRawType = "float";
                host.requestRedraw();
            });
            LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0, -2, 1);
            lp1.setMarginEnd(host.dp(8));
            tRow.addView(bInt, lp1);
            tRow.addView(bFlt, new LinearLayout.LayoutParams(0, -2, 1));
            addTop(editCard, tRow, 6);
        }

        if ("bool".equals(draftRawType)) {
            LinearLayout bRow = horizontal();
            View bTrue = makeFilterChip("true (开启 / 1)", "true".equalsIgnoreCase(draftRawValue), () -> {
                draftRawValue = "true";
                host.requestRedraw();
            });
            View bFalse = makeFilterChip("false (关闭 / 0)", !"true".equalsIgnoreCase(draftRawValue), () -> {
                draftRawValue = "false";
                host.requestRedraw();
            });
            LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0, -2, 1);
            lp1.setMarginEnd(host.dp(8));
            bRow.addView(bTrue, lp1);
            bRow.addView(bFalse, new LinearLayout.LayoutParams(0, -2, 1));
            addTop(editCard, bRow, 10);
        } else {
            EditText valIn = editField(draftRawValue, "int".equals(draftRawType) || "float".equals(draftRawType));
            valIn.addTextChangedListener(new SimpleTextWatcher(s -> draftRawValue = s));
            addLabeledField(editCard, "新值 (" + draftRawType + ")", valIn);
        }

        LinearLayout actions = horizontal();
        View saveBtn = host.makeButton("验证并写入存档", true, () -> {
            List<String> fullPath = new ArrayList<>(rawPath);
            fullPath.add(entry.key);
            final String typeToWrite = draftRawType;
            final String valToWrite = draftRawValue;
            host.runTask("正在写入 DSON 节点并校验…", () -> {
                SaveEditor.editRawScalar(context, selectedSlot, selectedDsonFile, fullPath, typeToWrite, valToWrite);
                refreshSync();
                subPage = SubPage.NONE;
            }, "字段「" + entry.key + "」已更新并通过无损自检");
        });
        View cancelBtn = host.makeButton("取消", false, this::handleBack);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0, -2, 2);
        slp.setMarginEnd(host.dp(8));
        actions.addView(saveBtn, slp);
        actions.addView(cancelBtn, new LinearLayout.LayoutParams(0, -2, 1));
        addTop(editCard, actions, 14);
        addTop(root, editCard, 10);
        return root;
    }

    // ==================== GAME RULES MODIFIER (GROUPED + PRESETS) ====================

    private View buildRulesModifier(boolean wide, Typeface display) {
        LinearLayout root = vertical();

        // Status + One-tap Preset Schemes Card
        LinearLayout headCard = card();
        LinearLayout top = horizontal();
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(host.makeType("非破坏性规则修改器", 16, INK, display),
                new LinearLayout.LayoutParams(0, -2, 1));
        top.addView(makePill(modInstalled ? "已安装 Mod" : "未生成", modInstalled ? GREEN : MUTED, modInstalled ? GREEN_BG : DEEP));
        headCard.addView(top);
        addTop(headCard, host.makeType(
                modInstalled
                        ? "独立覆写包 " + ContentModGenerator.MOD_ID + " 已就绪，调整参数后重新生成即可热更新，随时可一键还原原版。"
                        : "通过标准 Mod 覆盖游戏规则，零修改原始 dd_assets.pak，安全可逆。",
                12, MUTED, Typeface.DEFAULT), 6);

        addTop(headCard, host.makeType("一键套用平衡预设", 12, GOLD, Typeface.DEFAULT_BOLD), 10);
        LinearLayout presetRow = horizontal();
        View pVanilla = makeFilterChip("还原原版", false, () -> { applyRulePreset(0); host.requestRedraw(); });
        View pChill = makeFilterChip("轻度舒适", false, () -> { applyRulePreset(1); host.requestRedraw(); });
        View pFun = makeFilterChip("爽快开荒", false, () -> { applyRulePreset(2); host.requestRedraw(); });
        View pGod = makeFilterChip("终极无双", false, () -> { applyRulePreset(3); host.requestRedraw(); });
        View[] pBtns = {pVanilla, pChill, pFun, pGod};
        for (int i = 0; i < pBtns.length; i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
            if (i < 3) lp.setMarginEnd(host.dp(5));
            presetRow.addView(pBtns[i], lp);
        }
        addTop(headCard, presetRow, 6);
        root.addView(headCard);

        // Group 1: Inventory & Supplies
        LinearLayout g1 = card();
        g1.addView(host.makeType("一 · 背包、饰品槽与补给", 15, GOLD, Typeface.DEFAULT_BOLD));
        addSelectorRow(g1, "金币 / 传家宝 / 补给叠加上限", new String[]{"1x", "2x", "5x", "10x", "99x"},
                indexForInt(modConfig.stackMultiplier, new int[]{1, 2, 5, 10, 99}),
                idx -> { modConfig.stackMultiplier = new int[]{1, 2, 5, 10, 99}[idx]; host.requestRedraw(); });
        addSelectorRow(g1, "英雄饰品装备槽位", new String[]{"原版 2 格", "3 格", "4 格"},
                indexForInt(modConfig.heroTrinketSlots, new int[]{2, 3, 4}),
                idx -> { modConfig.heroTrinketSlots = new int[]{2, 3, 4}[idx]; host.requestRedraw(); });
        addTop(g1, buildToggleRow("出征补给品全部免费", "食物、火把、铲子、绷带、解毒剂等购买价格归零",
                modConfig.freeSupplies, () -> { modConfig.freeSupplies = !modConfig.freeSupplies; host.requestRedraw(); }), 10);
        addTop(root, g1, 10);

        // Group 2: Combat & Speed
        LinearLayout g2 = card();
        g2.addView(host.makeType("二 · 战斗节奏与输出体验", 15, GOLD, Typeface.DEFAULT_BOLD));
        addSelectorRow(g2, "战斗动画速度倍率", new String[]{"1.0x", "1.5x", "2.0x", "3.0x"},
                indexForFloat(modConfig.combatSpeedScale, new float[]{1.0f, 1.5f, 2.0f, 3.0f}),
                idx -> { modConfig.combatSpeedScale = new float[]{1.0f, 1.5f, 2.0f, 3.0f}[idx]; host.requestRedraw(); });
        addSelectorRow(g2, "我方暴击伤害倍率", new String[]{"1.5x", "2.0x", "2.5x", "3.0x"},
                indexForFloat(modConfig.playerCritMultiplier, new float[]{1.5f, 2.0f, 2.5f, 3.0f}),
                idx -> { modConfig.playerCritMultiplier = new float[]{1.5f, 2.0f, 2.5f, 3.0f}[idx]; host.requestRedraw(); });
        addTop(g2, buildToggleRow("解除 95% 命中封顶", "精准达标时真正 100% 命中，告别必中空挥",
                modConfig.alwaysHitCap >= 0.999f, () -> {
                    modConfig.alwaysHitCap = modConfig.alwaysHitCap >= 0.999f ? 0.95f : 1.0f;
                    host.requestRedraw();
                }), 10);
        addTop(g2, buildToggleRow("100% 无损撤退", "战斗撤退必定成功且不额外增加压力值",
                modConfig.guaranteedRetreat, () -> { modConfig.guaranteedRetreat = !modConfig.guaranteedRetreat; host.requestRedraw(); }), 8);
        addTop(root, g2, 10);

        // Group 3: Stress & Virtue
        LinearLayout g3 = card();
        g3.addView(host.makeType("三 · 压力管理与美德触发", 15, GOLD, Typeface.DEFAULT_BOLD));
        addSelectorRow(g3, "100 压力时美德触发概率", new String[]{"25%", "50%", "75%", "必出 100%"},
                indexForInt(modConfig.virtueChancePercent, new int[]{25, 50, 75, 100}),
                idx -> { modConfig.virtueChancePercent = new int[]{25, 50, 75, 100}[idx]; host.requestRedraw(); });
        addSelectorRow(g3, "我方承受压力倍率", new String[]{"100%", "50%", "25%", "零压 0%"},
                indexForFloat(modConfig.stressScale, new float[]{1.0f, 0.5f, 0.25f, 0.0f}),
                idx -> { modConfig.stressScale = new float[]{1.0f, 0.5f, 0.25f, 0.0f}[idx]; host.requestRedraw(); });
        addSelectorRow(g3, "城镇闲置每周自动回压", new String[]{"原版 5", "25", "50", "全清 100"},
                indexForFloat(modConfig.idleStressHeal, new float[]{5f, 25f, 50f, 100f}),
                idx -> { modConfig.idleStressHeal = new float[]{5f, 25f, 50f, 100f}[idx]; host.requestRedraw(); });
        addTop(root, g3, 10);

        // Group 4: Town & Growth
        LinearLayout g4 = card();
        g4.addView(host.makeType("四 · 马车招募与养成速度", 15, GOLD, Typeface.DEFAULT_BOLD));
        addSelectorRow(g4, "马车招募与花名册扩容", new String[]{"原版", "2倍 + 20格", "3倍 + 50格"},
                modConfig.stageCoachMultiplier >= 3 ? 2 : (modConfig.stageCoachMultiplier == 2 ? 1 : 0),
                idx -> {
                    modConfig.stageCoachMultiplier = new int[]{1, 2, 3}[idx];
                    modConfig.rosterCapacityBonus = new int[]{0, 20, 50}[idx];
                    host.requestRedraw();
                });
        addTop(g4, buildToggleRow("极速决心升级", "大幅降低英雄升至 Lv.6 所需远征经验",
                modConfig.fastResolveXp, () -> { modConfig.fastResolveXp = !modConfig.fastResolveXp; host.requestRedraw(); }), 10);
        addTop(root, g4, 10);

        // Action CTA Card
        LinearLayout cta = card();
        LinearLayout btns = horizontal();
        View applyBtn = host.makeButton("生成并启用到当前档位", true, () ->
                host.runTask("正在生成规则修改器 Mod 并应用…", () -> {
                    ContentModGenerator.applyToDevice(context, modConfig, selectedSlot);
                    refreshSync();
                }, "规则修改器 Mod 已生成" + (selectedSlot.isEmpty() ? "" : "并置顶启用到 " + selectedSlot)));
        View launchBtn = host.makeButton("立即启动游戏", false, host::launchOrRestartGame);
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0, -2, 1);
        lp1.setMarginEnd(host.dp(8));
        btns.addView(applyBtn, lp1);
        btns.addView(launchBtn, new LinearLayout.LayoutParams(0, -2, 1));
        cta.addView(btns);

        if (modInstalled) {
            addTop(cta, host.makeButton("卸载规则修改器 Mod 并恢复原版", false, () ->
                    host.runTask("正在卸载规则修改器…", () -> {
                        ContentModGenerator.uninstallFromDevice(context);
                        refreshSync();
                    }, "已卸载规则修改器并恢复原版")), 8);
        }
        addTop(root, cta, 10);
        return root;
    }

    private void applyRulePreset(int level) {
        ContentModGenerator.Config c = new ContentModGenerator.Config();
        if (level == 1) { // 轻度舒适
            c.stackMultiplier = 5;
            c.freeSupplies = true;
            c.combatSpeedScale = 1.5f;
            c.stressScale = 0.5f;
            c.idleStressHeal = 25f;
            c.alwaysHitCap = 1.0f;
            c.stageCoachMultiplier = 2;
            c.rosterCapacityBonus = 20;
        } else if (level == 2) { // 爽快开荒
            c.stackMultiplier = 10;
            c.freeSupplies = true;
            c.heroTrinketSlots = 3;
            c.combatSpeedScale = 2.0f;
            c.playerCritMultiplier = 2.0f;
            c.alwaysHitCap = 1.0f;
            c.guaranteedRetreat = true;
            c.virtueChancePercent = 75;
            c.stressScale = 0.5f;
            c.idleStressHeal = 50f;
            c.fastResolveXp = true;
            c.stageCoachMultiplier = 2;
            c.rosterCapacityBonus = 20;
        } else if (level == 3) { // 终极无双
            c.stackMultiplier = 99;
            c.freeSupplies = true;
            c.heroTrinketSlots = 4;
            c.combatSpeedScale = 3.0f;
            c.playerCritMultiplier = 3.0f;
            c.alwaysHitCap = 1.0f;
            c.guaranteedRetreat = true;
            c.virtueChancePercent = 100;
            c.stressScale = 0.0f;
            c.idleStressHeal = 100f;
            c.fastResolveXp = true;
            c.stageCoachMultiplier = 3;
            c.rosterCapacityBonus = 50;
        }
        modConfig = c;
    }

    // ==================== GOTHIC UI BUILDING BLOCKS ====================

    private View buildSecondaryHeader(String backText, String title, String subtitle,
                                      String rightActionText, Typeface display, Runnable rightAction) {
        LinearLayout box = card();
        LinearLayout top = horizontal();
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(makeCompactAction(backText, false, this::handleBack));
        View spacer = new View(context);
        top.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1));
        if (rightActionText != null && !rightActionText.isEmpty() && rightAction != null) {
            top.addView(makeCompactAction(rightActionText, false, rightAction));
        }
        box.addView(top);

        TextView heading = host.makeType(title, 22, INK, display);
        addTop(box, heading, 10);
        View goldRule = new View(context);
        goldRule.setBackgroundColor(GOLD);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(host.dp(36), host.dp(2));
        rlp.topMargin = host.dp(6);
        box.addView(goldRule, rlp);
        addTop(box, host.makeType(subtitle, 12, MUTED, Typeface.DEFAULT), 6);
        return box;
    }

    private View buildToggleRow(String title, String subtitle, boolean checked, Runnable onToggle) {
        LinearLayout row = horizontal();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(host.dp(12), host.dp(10), host.dp(12), host.dp(10));
        row.setBackground(host.makeSurface(checked ? GOLD_BG : RAISED, checked ? GOLD : LINE, 10));
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(v -> onToggle.run());

        LinearLayout words = vertical();
        words.addView(host.makeType(title, 13, checked ? INK : MUTED, Typeface.DEFAULT_BOLD));
        addTop(words, host.makeType(subtitle, 11, MUTED, Typeface.DEFAULT), 2);
        row.addView(words, new LinearLayout.LayoutParams(0, -2, 1));

        View badge = makePill(checked ? "已开启" : "已关闭", checked ? GREEN : MUTED, checked ? GREEN_BG : DEEP);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-2, -2);
        blp.setMarginStart(host.dp(10));
        row.addView(badge, blp);
        return row;
    }

    private View makeMeter(String label, float fraction, int fillColor) {
        LinearLayout wrap = vertical();
        wrap.addView(host.makeType(label, 11, MUTED, Typeface.DEFAULT_BOLD));
        FrameLayout track = new FrameLayout(context);
        track.setBackground(host.makeSurface(DEEP, LINE, 5));
        float clamped = Math.max(0f, Math.min(1f, fraction));
        LinearLayout barHolder = horizontal();
        View filled = new View(context);
        GradientDrawable fillBg = new GradientDrawable();
        fillBg.setColor(fillColor);
        fillBg.setCornerRadius(host.dp(4));
        filled.setBackground(fillBg);
        View empty = new View(context);
        barHolder.addView(filled, new LinearLayout.LayoutParams(0, -1, Math.max(0.001f, clamped)));
        barHolder.addView(empty, new LinearLayout.LayoutParams(0, -1, Math.max(0.001f, 1f - clamped)));
        track.addView(barHolder, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, host.dp(7));
        tlp.topMargin = host.dp(4);
        wrap.addView(track, tlp);
        return wrap;
    }

    private void addKpiTile(LinearLayout row, String label, String value, int valueColor, boolean marginEnd) {
        LinearLayout tile = vertical();
        tile.setGravity(Gravity.CENTER);
        tile.setPadding(host.dp(6), host.dp(8), host.dp(6), host.dp(8));
        tile.setBackground(host.makeSurface(DEEP, LINE, 9));
        tile.addView(host.makeType(value, 14, valueColor, Typeface.DEFAULT_BOLD));
        addTop(tile, host.makeType(label, 11, MUTED, Typeface.DEFAULT), 2);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
        if (marginEnd) lp.setMarginEnd(host.dp(6));
        row.addView(tile, lp);
    }

    private void addChipGrid(LinearLayout parent, List<Map.Entry<String, String>> entries,
                             String activeKey, int cols, boolean positiveTheme,
                             java.util.function.Consumer<String> onPick) {
        for (int i = 0; i < entries.size(); i += cols) {
            LinearLayout row = horizontal();
            for (int c = 0; c < cols; c++) {
                int idx = i + c;
                if (idx < entries.size()) {
                    String key = entries.get(idx).getKey();
                    String label = shortParen(entries.get(idx).getValue());
                    boolean active = key.equals(activeKey);
                    int fill = active ? (positiveTheme ? GREEN_BG : CRIMSON_BG) : RAISED;
                    int border = active ? (positiveTheme ? GREEN : ERROR) : LINE;
                    int textCol = active ? (positiveTheme ? GREEN : ERROR) : MUTED;
                    TextView tv = host.makeType(label, 12, textCol, active ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
                    tv.setGravity(Gravity.CENTER);
                    tv.setPadding(host.dp(6), host.dp(8), host.dp(6), host.dp(8));
                    tv.setBackground(host.makeSurface(fill, border, 8));
                    tv.setOnClickListener(v -> onPick.accept(key));
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
                    if (c + 1 < cols) lp.setMarginEnd(host.dp(6));
                    row.addView(tv, lp);
                } else {
                    View spacer = new View(context);
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, 1, 1);
                    if (c + 1 < cols) lp.setMarginEnd(host.dp(6));
                    row.addView(spacer, lp);
                }
            }
            addTop(parent, row, 6);
        }
    }

    private View makeSegmentChip(String text, boolean active, Runnable onClick) {
        TextView tv = host.makeType(text, 13, active ? INK : MUTED, Typeface.DEFAULT_BOLD);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(host.dp(10), host.dp(10), host.dp(10), host.dp(10));
        tv.setBackground(host.makeSurface(active ? RED : Color.TRANSPARENT, active ? GOLD : Color.TRANSPARENT, 9));
        tv.setOnClickListener(v -> onClick.run());
        return tv;
    }

    private View makeSubTabPill(String text, boolean active, Runnable onClick) {
        TextView tv = host.makeType(text, 13, active ? GOLD : MUTED, active ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(host.dp(14), host.dp(9), host.dp(14), host.dp(9));
        tv.setBackground(host.makeSurface(active ? GOLD_BG : PANEL, active ? GOLD : LINE, 10));
        tv.setOnClickListener(v -> onClick.run());
        return tv;
    }

    private View makeFilterChip(String text, boolean active, Runnable onClick) {
        TextView tv = host.makeType(text, 12, active ? INK : MUTED, active ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(host.dp(10), host.dp(7), host.dp(10), host.dp(7));
        tv.setBackground(host.makeSurface(active ? RED : RAISED, active ? GOLD : LINE, 8));
        tv.setOnClickListener(v -> onClick.run());
        return tv;
    }

    private View makeCompactAction(String text, boolean primary, Runnable onClick) {
        TextView tv = host.makeType(text, 12, primary ? INK : GOLD, Typeface.DEFAULT_BOLD);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(host.dp(10), host.dp(7), host.dp(10), host.dp(7));
        tv.setBackground(host.makeSurface(primary ? RED : RAISED, primary ? GOLD : LINE, 8));
        tv.setOnClickListener(v -> onClick.run());
        return tv;
    }

    private View makePill(String text, int color, int bg) {
        TextView tv = host.makeType(text, 11, color, Typeface.DEFAULT_BOLD);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(host.dp(8), host.dp(3), host.dp(8), host.dp(3));
        tv.setBackground(host.makeSurface(bg, LINE, 6));
        return tv;
    }

    @FunctionalInterface
    private interface IndexSelect { void onSelect(int idx); }

    private void addSelectorRow(LinearLayout parent, String title, String[] labels, int activeIdx, IndexSelect onSelect) {
        addTop(parent, host.makeType(title, 12, MUTED, Typeface.DEFAULT_BOLD), 10);
        LinearLayout row = horizontal();
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            View b = makeFilterChip(labels[i], i == activeIdx, () -> onSelect.onSelect(idx));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
            if (i + 1 < labels.length) lp.setMarginEnd(host.dp(4));
            row.addView(b, lp);
        }
        addTop(parent, row, 5);
    }

    private SaveEditor.HeroState findHero(String guid) {
        for (SaveEditor.HeroState h : heroes) {
            if (h.guid.equals(guid)) return h;
        }
        return null;
    }

    private static boolean hasAnyDisease(SaveEditor.HeroState h) {
        for (SaveEditor.QuirkEntry q : h.quirks) if (CatalogData.isDisease(q.id)) return true;
        return false;
    }

    private static int countDiseases(SaveEditor.HeroState h) {
        int c = 0;
        for (SaveEditor.QuirkEntry q : h.quirks) if (CatalogData.isDisease(q.id)) c++;
        return c;
    }

    private static String modeDisplayName(String code) {
        if ("radiant".equals(code)) return "光辉模式";
        if ("new_game_plus".equals(code)) return "血月/冥暗";
        return "极暗模式";
    }

    private static String shortVirtue(String key) {
        String full = CatalogData.VIRTUES.getOrDefault(key, key);
        return shortParen(full);
    }

    private static String shortParen(String s) {
        int idx = s.indexOf(" (");
        return idx > 0 ? s.substring(0, idx) : s;
    }

    private static String formatCompact(int v) {
        if (v >= 100000) return String.format(Locale.ROOT, "%.1f万", v / 10000.0);
        return Integer.toString(v);
    }

    private static String extractPrimaryNumeric(String raw) {
        if (raw == null) return "";
        int sp = raw.indexOf(' ');
        return sp > 0 ? raw.substring(0, sp).trim() : raw.trim();
    }

    private static int categoryColor(String cat) {
        return switch (cat) {
            case "先祖", "极暗" -> ERROR;
            case "极稀有", "奖杯", "稀世" -> GOLD;
            case "疯狂", "鸦翼" -> ARCANE;
            case "收藏家", "狂人", "稀有" -> GREEN;
            default -> MUTED;
        };
    }

    private static int categoryBg(String cat) {
        return switch (cat) {
            case "先祖", "极暗" -> CRIMSON_BG;
            case "极稀有", "奖杯", "稀世" -> GOLD_BG;
            case "疯狂", "鸦翼" -> ARCANE_BG;
            case "收藏家", "狂人", "稀有" -> GREEN_BG;
            default -> DEEP;
        };
    }

    private static int typeTagColor(String tag) {
        return switch (tag) {
            case "object" -> GOLD;
            case "int", "float" -> ARCANE;
            case "bool" -> GREEN;
            default -> MUTED;
        };
    }

    private static int indexForInt(int val, int[] choices) {
        for (int i = 0; i < choices.length; i++) if (choices[i] == val) return i;
        return 0;
    }

    private static int indexForFloat(float val, float[] choices) {
        for (int i = 0; i < choices.length; i++) if (Math.abs(choices[i] - val) < 0.03f) return i;
        return 0;
    }

    private LinearLayout card() {
        LinearLayout l = vertical();
        l.setPadding(host.dp(15), host.dp(14), host.dp(15), host.dp(14));
        l.setBackground(host.makeSurface(PANEL, LINE, 13));
        return l;
    }

    private EditText editField(String value, boolean numeric) {
        EditText et = new EditText(context);
        et.setText(value);
        et.setTextColor(INK);
        et.setHintTextColor(MUTED);
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        et.setSingleLine(true);
        if (numeric) et.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
        et.setPadding(host.dp(10), host.dp(8), host.dp(10), host.dp(8));
        et.setBackground(host.makeSurface(DEEP, LINE, 8));
        return et;
    }

    private void addLabeledField(LinearLayout parent, String label, EditText field) {
        addTop(parent, host.makeType(label, 12, MUTED, Typeface.DEFAULT), 8);
        addTop(parent, field, 4);
    }

    private LinearLayout vertical() {
        LinearLayout l = new LinearLayout(context);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private LinearLayout horizontal() {
        return new LinearLayout(context);
    }

    private void addTop(LinearLayout parent, View child, int gapDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = host.dp(gapDp);
        parent.addView(child, lp);
    }

    private static int parseIntSafe(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }

    private static final class SimpleTextWatcher implements TextWatcher {
        private final java.util.function.Consumer<String> onChange;
        SimpleTextWatcher(java.util.function.Consumer<String> onChange) { this.onChange = onChange; }
        @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
        @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
        @Override public void afterTextChanged(Editable s) { onChange.accept(s == null ? "" : s.toString()); }
    }
}
