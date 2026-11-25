package dev.torcharchive.dd;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import dev.torcharchive.game.CatalogData;
import dev.torcharchive.game.ContentModGenerator;
import dev.torcharchive.game.SaveEditor;

/**
 * Complete on-device Save Editor & Game Rules Modifier section for the unified launcher.
 */
final class ModifierSection {
    private static final int PANEL = 0xff211918, RAISED = 0xff2b201e;
    private static final int INK = 0xfff5e6d4, MUTED = 0xffc1a897, GOLD = 0xffe1ab71, LINE = 0xff543c32;
    private static final int RED = 0xff733029, GREEN = 0xffc5cb9e;

    interface Host {
        void runTask(String progress, WorkTask task, String successMessage);
        void showMessage(String msg, boolean isError);
        void requestRedraw();
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
    private int saveSubTab = 0; // 0=庄园货币, 1=全英雄, 2=饰品库, 3=建筑进度, 4=原始DSON树

    private List<SaveEditor.SlotSummary> slots = List.of();
    private String selectedSlot = "";
    private SaveEditor.EstateState estate;
    private List<SaveEditor.HeroState> heroes = List.of();
    private List<SaveEditor.TrinketEntry> trinkets = List.of();
    private Map<String, Integer> dungeonXp = new LinkedHashMap<>();

    // Raw DSON inspector state
    private List<String> dsonFiles = List.of();
    private String selectedDsonFile = "persist.game.json";
    private final List<String> rawPath = new ArrayList<>();
    private List<SaveEditor.RawEntry> rawEntries = List.of();

    // Content modifier state
    private ContentModGenerator.Config modConfig = new ContentModGenerator.Config();
    private boolean modInstalled = false;

    ModifierSection(Context context, Host host) {
        this.context = context;
        this.host = host;
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
        LinearLayout root = vertical();
        LinearLayout header = horizontal();
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = host.makeType("完整修改器", 22, INK, display);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        header.addView(host.makeButton("刷新读取", false, () ->
                host.runTask("正在读取存档与修改器配置…", this::refreshSync, "修改器数据已刷新")));
        root.addView(header);

        // Mode switcher: Save Editor vs Game Content Rules
        LinearLayout modeRow = horizontal();
        modeRow.setPadding(0, host.dp(10), 0, host.dp(6));
        View btnSaveMode = host.makeButton("存档修改器 (全数据)", mode == 0, () -> { mode = 0; host.requestRedraw(); });
        View btnRuleMode = host.makeButton("游戏规则修改器 (Mod)", mode == 1, () -> { mode = 1; host.requestRedraw(); });
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0, -2, 1);
        lp1.setMarginEnd(host.dp(8));
        modeRow.addView(btnSaveMode, lp1);
        modeRow.addView(btnRuleMode, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(modeRow);

        // Safety banner
        TextView note = host.makeType(
                "安全机制：每次写档前均会自动结束游戏进程并强制拍摄一条历史快照，随时可在「存档」页一键回档。",
                12, MUTED, Typeface.DEFAULT);
        note.setPadding(host.dp(12), host.dp(8), host.dp(12), host.dp(8));
        note.setBackground(host.makeSurface(RAISED, LINE, 10));
        addTop(root, note, 6);

        if (mode == 0) {
            addTop(root, buildSaveEditor(wide), 12);
        } else {
            addTop(root, buildRulesModifier(wide), 12);
        }
        return root;
    }

    private View buildSaveEditor(boolean wide) {
        LinearLayout box = vertical();
        if (slots.isEmpty()) {
            LinearLayout card = card();
            card.addView(host.makeType("暂未检测到存档槽位", 16, INK, Typeface.DEFAULT_BOLD));
            addTop(card, host.makeType("请先启动一次游戏创建档位，或在「启动/存档」页导入 Steam 存档与迁移包后再刷新读取。", 13, MUTED, Typeface.DEFAULT), 6);
            box.addView(card);
            return box;
        }

        // Slot selector bar
        LinearLayout slotBar = card();
        slotBar.addView(host.makeType("当前编辑档位", 14, GOLD, Typeface.DEFAULT_BOLD));
        LinearLayout slotBtns = horizontal();
        for (int i = 0; i < slots.size(); i++) {
            SaveEditor.SlotSummary s = slots.get(i);
            boolean active = s.slot.equals(selectedSlot);
            String label = "档位 " + (Integer.parseInt(s.slot.substring(8)) + 1) + " · " + s.estateName;
            View b = host.makeButton(label, active, () -> {
                selectedSlot = s.slot;
                rawPath.clear();
                host.runTask("正在加载 " + label + "…", this::refreshSync, "已切换到 " + label);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
            if (i + 1 < slots.size()) lp.setMarginEnd(host.dp(6));
            slotBtns.addView(b, lp);
        }
        addTop(slotBar, slotBtns, 8);
        if (estate != null) {
            addTop(slotBar, host.makeType(
                    String.format(Locale.ROOT, "模式：%s  ·  金币：%d  ·  英雄：%d 名  ·  饰品：%d 件",
                            estate.gameMode, estate.wallet.getOrDefault("gold", 0), heroes.size(), trinkets.size()),
                    12, MUTED, Typeface.DEFAULT), 8);
        }
        box.addView(slotBar);

        // Sub-tabs inside Save Editor
        String[] subTitles = {"庄园货币", "全英雄", "饰品库", "建筑/地牢", "原始DSON"};
        LinearLayout subRow = horizontal();
        for (int i = 0; i < subTitles.length; i++) {
            final int idx = i;
            View b = host.makeButton(subTitles[i], saveSubTab == idx, () -> { saveSubTab = idx; host.requestRedraw(); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
            if (i + 1 < subTitles.length) lp.setMarginEnd(host.dp(4));
            subRow.addView(b, lp);
        }
        addTop(box, subRow, 10);

        switch (saveSubTab) {
            case 0 -> addTop(box, buildEstateTab(), 10);
            case 1 -> addTop(box, buildHeroesTab(), 10);
            case 2 -> addTop(box, buildTrinketsTab(), 10);
            case 3 -> addTop(box, buildProgressTab(), 10);
            default -> addTop(box, buildRawDsonTab(), 10);
        }
        return box;
    }

    private View buildEstateTab() {
        LinearLayout card = card();
        if (estate == null) return card;
        card.addView(host.makeType("庄园基础信息与全货币编辑", 16, INK, Typeface.DEFAULT_BOLD));

        EditText nameInput = editField(estate.estateName, false);
        addLabeledField(card, "庄园名称", nameInput);

        final String[] modeSel = {estate.gameMode};
        LinearLayout modeRow = horizontal();
        for (String[] pair : new String[][]{{"radiant", "光辉 (简单)"}, {"darkest", "极暗 (标准)"}, {"new_game_plus", "血月/冥暗"}}) {
            View b = host.makeButton(pair[1], pair[0].equals(modeSel[0]), () -> {
                modeSel[0] = pair[0];
                host.runTask("正在修改游戏难度…", () -> {
                    SaveEditor.writeEstate(context, selectedSlot, null, pair[0], null, null);
                    refreshSync();
                }, "游戏模式已切换为 " + pair[1]);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
            lp.setMarginEnd(host.dp(6));
            modeRow.addView(b, lp);
        }
        addTop(card, host.makeType("游戏模式", 13, MUTED, Typeface.DEFAULT), 10);
        addTop(card, modeRow, 6);

        Map<String, EditText> walletInputs = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : CatalogData.CURRENCIES.entrySet()) {
            int val = estate.wallet.getOrDefault(entry.getKey(), 0);
            EditText input = editField(Integer.toString(val), true);
            walletInputs.put(entry.getKey(), input);
            addLabeledField(card, entry.getValue(), input);
        }

        EditText hsInput = editField(Integer.toString(estate.endlessHighscore), true);
        addLabeledField(card, "无尽农庄最高波次", hsInput);

        LinearLayout actions = horizontal();
        View saveBtn = host.makeButton("保存庄园与货币", true, () -> {
            Map<String, Integer> updates = new LinkedHashMap<>();
            for (Map.Entry<String, EditText> e : walletInputs.entrySet()) {
                updates.put(e.getKey(), parseIntSafe(e.getValue().getText().toString(), 0));
            }
            int hs = parseIntSafe(hsInput.getText().toString(), estate.endlessHighscore);
            String nextName = nameInput.getText().toString().trim();
            host.runTask("正在写入庄园与货币数据…", () -> {
                SaveEditor.writeEstate(context, selectedSlot, nextName, null, updates, hs);
                refreshSync();
            }, "庄园与货币修改已保存（已自动快照原档）");
        });
        View maxBtn = host.makeButton("一键资源拉满", false, () -> {
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
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0, -2, 1);
        lp1.setMarginEnd(host.dp(8));
        actions.addView(saveBtn, lp1);
        actions.addView(maxBtn, new LinearLayout.LayoutParams(0, -2, 1));
        addTop(card, actions, 14);
        return card;
    }

    private View buildHeroesTab() {
        LinearLayout box = vertical();
        LinearLayout top = card();
        top.addView(host.makeType("花名册英雄 (" + heroes.size() + " 名)", 16, INK, Typeface.DEFAULT_BOLD));
        LinearLayout batch = horizontal();
        View healBtn = host.makeButton("全体满血清压去病/复活", true, () ->
                host.runTask("正在恢复全体英雄状态…", () -> {
                    SaveEditor.batchImproveAllHeroes(context, selectedSlot, false, true);
                    refreshSync();
                }, "全体英雄已满血、清零压力、清除折磨与疾病并复活阵亡角色"));
        View maxBtn = host.makeButton("全体满级满装 (Lv6/4/4)", false, () ->
                host.runTask("正在提升全体英雄等级与装备…", () -> {
                    SaveEditor.batchImproveAllHeroes(context, selectedSlot, true, false);
                    refreshSync();
                }, "全体英雄已升至 Lv6 且武器/护甲满级"));
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0, -2, 1);
        lp1.setMarginEnd(host.dp(8));
        batch.addView(healBtn, lp1);
        batch.addView(maxBtn, new LinearLayout.LayoutParams(0, -2, 1));
        addTop(top, batch, 10);
        box.addView(top);

        for (SaveEditor.HeroState h : heroes) {
            LinearLayout row = card();
            String clsLabel = CatalogData.heroClassLabel(h.heroClass);
            int lv = CatalogData.resolveLevelForXp(h.resolveXp);
            String title = String.format(Locale.ROOT, "%s · %s (Lv.%d)", h.name, clsLabel, lv);
            String stateTag = !h.virtue.isEmpty() ? "美德:" + h.virtue : (!h.affliction.isEmpty() ? "折磨:" + h.affliction : h.statusLabel());
            String detail = String.format(Locale.ROOT,
                    "HP %.0f%%  ·  压力 %.0f  ·  武器%d/护甲%d  ·  怪癖 %d 个  ·  %s",
                    h.hpFraction * 100f, h.stress, h.weaponRank + 1, h.armourRank + 1, h.quirks.size(), stateTag);
            row.addView(host.makeType(title, 15, INK, Typeface.DEFAULT_BOLD));
            addTop(row, host.makeType(detail, 12, MUTED, Typeface.DEFAULT), 4);
            LinearLayout btns = horizontal();
            View editBtn = host.makeButton("编辑属性/状态", false, () -> openHeroDialog(h));
            View quirkBtn = host.makeButton("怪癖与疾病", false, () -> openQuirksDialog(h));
            LinearLayout.LayoutParams b1 = new LinearLayout.LayoutParams(0, -2, 1);
            b1.setMarginEnd(host.dp(8));
            btns.addView(editBtn, b1);
            btns.addView(quirkBtn, new LinearLayout.LayoutParams(0, -2, 1));
            addTop(row, btns, 8);
            addTop(box, row, 8);
        }
        return box;
    }

    private void openHeroDialog(SaveEditor.HeroState h) {
        ScrollView sv = new ScrollView(context);
        LinearLayout form = vertical();
        form.setPadding(host.dp(20), host.dp(12), host.dp(20), host.dp(8));
        sv.addView(form);

        EditText nameIn = editField(h.name, false);
        EditText hpIn = editField(String.format(Locale.ROOT, "%.0f", h.hpFraction * 100f), true);
        EditText stressIn = editField(String.format(Locale.ROOT, "%.0f", h.stress), true);
        EditText xpIn = editField(Integer.toString(h.resolveXp), true);
        EditText wpIn = editField(Integer.toString(h.weaponRank), true);
        EditText arIn = editField(Integer.toString(h.armourRank), true);

        addLabeledField(form, "英雄名称", nameIn);
        addLabeledField(form, "生命百分比 (1–100)", hpIn);
        addLabeledField(form, "压力值 (0–200)", stressIn);
        addLabeledField(form, "决心经验 (0/2/8/14/24/36/48 对应 Lv0–6)", xpIn);
        addLabeledField(form, "武器等级 (0–4 对应游戏中 1–5 级)", wpIn);
        addLabeledField(form, "护甲等级 (0–4 对应游戏中 1–5 级)", arIn);

        final String[] virtue = {h.virtue};
        final String[] affliction = {h.affliction};
        CheckBox reviveBox = new CheckBox(context);
        reviveBox.setText("复活英雄 / 清除心梗与死门标记");
        reviveBox.setChecked(h.status == 3);
        addTop(form, reviveBox, 8);

        TextView vLabel = host.makeType("美德状态：" + CatalogData.VIRTUES.getOrDefault(virtue[0], virtue[0]), 13, GOLD, Typeface.DEFAULT);
        addTop(form, vLabel, 8);
        View pickVirtue = host.makeButton("切换美德 (无/坚毅/无畏/专注/强力/振奋)", false, () -> {
            String[] keys = CatalogData.VIRTUES.keySet().toArray(new String[0]);
            String[] labels = CatalogData.VIRTUES.values().toArray(new String[0]);
            new AlertDialog.Builder(context).setTitle("选择美德").setItems(labels, (d, which) -> {
                virtue[0] = keys[which];
                if (!virtue[0].isEmpty()) affliction[0] = "";
                vLabel.setText("美德状态：" + labels[which]);
            }).show();
        });
        addTop(form, pickVirtue, 6);

        View clearAff = host.makeButton("清除折磨 (" + (affliction[0].isEmpty() ? "当前无" : affliction[0]) + ")", false, () -> {
            affliction[0] = "";
            host.showMessage("已标记清除折磨，点击保存生效", false);
        });
        addTop(form, clearAff, 6);

        new AlertDialog.Builder(context)
                .setTitle(h.name + " · " + CatalogData.heroClassLabel(h.heroClass))
                .setView(sv)
                .setPositiveButton("保存修改", (d, w) -> {
                    float hp = Math.max(1f, Math.min(100f, parseFloatSafe(hpIn.getText().toString(), 100f))) / 100f;
                    float st = parseFloatSafe(stressIn.getText().toString(), h.stress);
                    int xp = parseIntSafe(xpIn.getText().toString(), h.resolveXp);
                    int wp = parseIntSafe(wpIn.getText().toString(), h.weaponRank);
                    int ar = parseIntSafe(arIn.getText().toString(), h.armourRank);
                    String nm = nameIn.getText().toString().trim();
                    host.runTask("正在保存英雄数据…", () -> {
                        SaveEditor.updateHero(context, selectedSlot, h.guid, nm, hp, st, xp, wp, ar,
                                affliction[0], virtue[0], reviveBox.isChecked());
                        refreshSync();
                    }, "英雄「" + nm + "」已保存");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void openQuirksDialog(SaveEditor.HeroState h) {
        List<SaveEditor.QuirkEntry> list = new ArrayList<>(h.quirks);
        ScrollView sv = new ScrollView(context);
        LinearLayout body = vertical();
        body.setPadding(host.dp(18), host.dp(12), host.dp(18), host.dp(12));
        sv.addView(body);

        Runnable[] rebuild = new Runnable[1];
        rebuild[0] = () -> {
            body.removeAllViews();
            LinearLayout top = horizontal();
            View addPos = host.makeButton("+ 添加强力正面怪癖", true, () -> {
                String[] ids = new String[CatalogData.POPULAR_POSITIVE_QUIRKS.size()];
                String[] names = new String[ids.length];
                for (int i = 0; i < ids.length; i++) {
                    ids[i] = CatalogData.POPULAR_POSITIVE_QUIRKS.get(i).id;
                    names[i] = CatalogData.POPULAR_POSITIVE_QUIRKS.get(i).label;
                }
                new AlertDialog.Builder(context).setTitle("选择正面怪癖").setItems(names, (d, which) -> {
                    String qid = ids[which];
                    if (list.stream().noneMatch(q -> q.id.equals(qid))) {
                        list.add(new SaveEditor.QuirkEntry(qid, true, false));
                        rebuild[0].run();
                    }
                }).show();
            });
            View cureAll = host.makeButton("一键清除疾病", false, () -> {
                list.removeIf(q -> CatalogData.isDisease(q.id));
                rebuild[0].run();
            });
            LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0, -2, 1);
            lp1.setMarginEnd(host.dp(6));
            top.addView(addPos, lp1);
            top.addView(cureAll, new LinearLayout.LayoutParams(0, -2, 1));
            body.addView(top);

            for (int i = 0; i < list.size(); i++) {
                final int idx = i;
                SaveEditor.QuirkEntry q = list.get(i);
                LinearLayout row = horizontal();
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(host.dp(10), host.dp(6), host.dp(10), host.dp(6));
                row.setBackground(host.makeSurface(RAISED, LINE, 8));
                TextView lbl = host.makeType(CatalogData.quirkLabel(q.id) + (q.locked ? " [已锁定]" : ""),
                        13, INK, Typeface.DEFAULT);
                row.addView(lbl, new LinearLayout.LayoutParams(0, -2, 1));
                View lockBtn = host.makeButton(q.locked ? "解锁" : "锁定", false, () -> {
                    list.set(idx, new SaveEditor.QuirkEntry(q.id, !q.locked, q.isNew));
                    rebuild[0].run();
                });
                View delBtn = host.makeButton("删", false, () -> {
                    list.remove(idx);
                    rebuild[0].run();
                });
                row.addView(lockBtn);
                row.addView(delBtn);
                addTop(body, row, 6);
            }
        };
        rebuild[0].run();

        new AlertDialog.Builder(context)
                .setTitle(h.name + " · 怪癖与疾病编辑")
                .setView(sv)
                .setPositiveButton("保存怪癖", (d, w) ->
                        host.runTask("正在写入怪癖列表…", () -> {
                            SaveEditor.updateHeroQuirks(context, selectedSlot, h.guid, list);
                            refreshSync();
                        }, h.name + " 的怪癖与疾病已更新"))
                .setNegativeButton("取消", null)
                .show();
    }

    private View buildTrinketsTab() {
        LinearLayout box = vertical();
        LinearLayout top = card();
        top.addView(host.makeType("饰品仓库 (" + trinkets.size() + " 件)", 16, INK, Typeface.DEFAULT_BOLD));
        LinearLayout actions = horizontal();
        View addCatalog = host.makeButton("+ 从图鉴添加饰品", true, () -> {
            String[] labels = new String[CatalogData.CURATED_TRINKETS.size()];
            for (int i = 0; i < labels.length; i++) {
                CatalogData.NamedEntry e = CatalogData.CURATED_TRINKETS.get(i);
                labels[i] = "[" + e.category + "] " + e.label + " (" + e.id + ")";
            }
            new AlertDialog.Builder(context).setTitle("选择要添加的饰品")
                    .setItems(labels, (d, which) -> {
                        String id = CatalogData.CURATED_TRINKETS.get(which).id;
                        host.runTask("正在添加饰品…", () -> {
                            SaveEditor.addTrinket(context, selectedSlot, id, 1);
                            refreshSync();
                        }, "已添加饰品：" + CatalogData.CURATED_TRINKETS.get(which).label);
                    }).setNegativeButton("取消", null).show();
        });
        View addCustom = host.makeButton("自定义 ID 添加", false, () -> {
            EditText input = editField("", false);
            input.setHint("例如 ancestors_map 或 Mod 饰品 ID");
            new AlertDialog.Builder(context).setTitle("输入饰品 ID").setView(input)
                    .setPositiveButton("添加", (d, w) -> {
                        String id = input.getText().toString().trim();
                        if (!id.isEmpty()) {
                            host.runTask("正在添加饰品…", () -> {
                                SaveEditor.addTrinket(context, selectedSlot, id, 1);
                                refreshSync();
                            }, "已添加饰品：" + id);
                        }
                    }).setNegativeButton("取消", null).show();
        });
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0, -2, 1);
        lp1.setMarginEnd(host.dp(8));
        actions.addView(addCatalog, lp1);
        actions.addView(addCustom, new LinearLayout.LayoutParams(0, -2, 1));
        addTop(top, actions, 10);
        box.addView(top);

        for (SaveEditor.TrinketEntry t : trinkets) {
            LinearLayout row = card();
            LinearLayout line = horizontal();
            line.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout words = vertical();
            words.addView(host.makeType(CatalogData.trinketLabel(t.id), 14, INK, Typeface.DEFAULT_BOLD));
            addTop(words, host.makeType("ID: " + t.id + "  ·  #" + t.index, 12, MUTED, Typeface.DEFAULT), 2);
            line.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
            line.addView(host.makeButton("移除", false, () ->
                    host.runTask("正在移除饰品…", () -> {
                        SaveEditor.removeTrinket(context, selectedSlot, t.index);
                        refreshSync();
                    }, "已移除饰品：" + t.id)));
            row.addView(line);
            addTop(box, row, 6);
        }
        return box;
    }

    private View buildProgressTab() {
        LinearLayout card = card();
        card.addView(host.makeType("城镇建筑与地牢区域进度", 16, INK, Typeface.DEFAULT_BOLD));
        addTop(card, host.makeType("一键解锁全部 8 座城镇建筑升级（铁匠铺/公会/马车/疗养院/酒馆/修道院/篷车/露营）。", 13, MUTED, Typeface.DEFAULT), 6);
        addTop(card, host.makeButton("一键解锁全部城镇建筑满级", true, () -> {
            final int[] count = {0};
            host.runTask("正在写入城镇升级表…", () -> {
                count[0] = SaveEditor.unlockAllBuildingUpgrades(context, selectedSlot);
                refreshSync();
            }, "已完成城镇建筑全解锁（新增/激活 " + count[0] + " 项升级）");
        }), 10);

        addTop(card, host.makeType("各地牢区域探索经验 (XP)", 15, GOLD, Typeface.DEFAULT_BOLD), 16);
        Map<String, EditText> xpInputs = new LinkedHashMap<>();
        for (Map.Entry<String, String> d : CatalogData.DUNGEONS.entrySet()) {
            int cur = dungeonXp.getOrDefault(d.getKey(), 0);
            EditText in = editField(Integer.toString(cur), true);
            xpInputs.put(d.getKey(), in);
            addLabeledField(card, d.getValue(), in);
        }
        addTop(card, host.makeButton("保存地牢区域进度", false, () -> {
            Map<String, Integer> next = new LinkedHashMap<>();
            for (Map.Entry<String, EditText> e : xpInputs.entrySet()) {
                next.put(e.getKey(), parseIntSafe(e.getValue().getText().toString(), 0));
            }
            host.runTask("正在保存地牢进度…", () -> {
                SaveEditor.writeDungeonXp(context, selectedSlot, next);
                refreshSync();
            }, "地牢区域经验已更新");
        }), 12);
        return card;
    }

    private View buildRawDsonTab() {
        LinearLayout box = vertical();
        LinearLayout top = card();
        top.addView(host.makeType("全字段原始 DSON 二进制浏览器 (100% 存档覆盖)", 15, INK, Typeface.DEFAULT_BOLD));
        LinearLayout nav = horizontal();
        View pickFile = host.makeButton("文件：" + selectedDsonFile, false, () -> {
            if (dsonFiles.isEmpty()) return;
            new AlertDialog.Builder(context).setTitle("选择存档文件")
                    .setItems(dsonFiles.toArray(new String[0]), (d, which) -> {
                        selectedDsonFile = dsonFiles.get(which);
                        rawPath.clear();
                        host.runTask("正在解析 " + selectedDsonFile + "…", this::refreshSync, "已打开 " + selectedDsonFile);
                    }).show();
        });
        View upBtn = host.makeButton("返回上级", false, () -> {
            if (!rawPath.isEmpty()) {
                rawPath.remove(rawPath.size() - 1);
                host.runTask("正在返回上级节点…", this::refreshSync, "");
            }
        });
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0, -2, 1);
        lp1.setMarginEnd(host.dp(8));
        nav.addView(pickFile, lp1);
        nav.addView(upBtn);
        addTop(top, nav, 8);
        addTop(top, host.makeType("当前路径：/" + String.join("/", rawPath), 12, GOLD, Typeface.MONOSPACE), 6);
        box.addView(top);

        for (SaveEditor.RawEntry entry : rawEntries) {
            LinearLayout row = card();
            LinearLayout line = horizontal();
            line.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout words = vertical();
            words.addView(host.makeType(entry.key, 14, INK, Typeface.DEFAULT_BOLD));
            addTop(words, host.makeType("[" + entry.typeTag + "] " + entry.displayValue, 12, MUTED, Typeface.DEFAULT), 2);
            line.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
            if (entry.object) {
                line.addView(host.makeButton("展开", false, () -> {
                    rawPath.add(entry.key);
                    host.runTask("正在展开 " + entry.key + "…", this::refreshSync, "");
                }));
            } else if (entry.editable) {
                line.addView(host.makeButton("编辑", false, () -> openRawEditDialog(entry)));
            }
            row.addView(line);
            addTop(box, row, 6);
        }
        return box;
    }

    private void openRawEditDialog(SaveEditor.RawEntry entry) {
        LinearLayout form = vertical();
        form.setPadding(host.dp(20), host.dp(10), host.dp(20), host.dp(10));
        final String[] editType = {entry.typeTag};
        EditText valIn = editField(
                "string".equals(entry.typeTag) ? entry.displayValue : "", false);
        form.addView(host.makeType("当前值：" + entry.displayValue, 13, MUTED, Typeface.DEFAULT));
        if ("int".equals(entry.typeTag) || "float".equals(entry.typeTag)) {
            LinearLayout tRow = horizontal();
            View bInt = host.makeButton("按整数(int32)", "int".equals(editType[0]), () -> editType[0] = "int");
            View bFlt = host.makeButton("按浮点(float32)", "float".equals(editType[0]), () -> editType[0] = "float");
            LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0, -2, 1);
            lp1.setMarginEnd(host.dp(8));
            tRow.addView(bInt, lp1);
            tRow.addView(bFlt, new LinearLayout.LayoutParams(0, -2, 1));
            addTop(form, tRow, 8);
        }
        addLabeledField(form, "新值 (" + editType[0] + ")", valIn);
        new AlertDialog.Builder(context).setTitle("修改字段：" + entry.key)
                .setView(form)
                .setPositiveButton("验证并写入", (d, w) -> {
                    List<String> fullPath = new ArrayList<>(rawPath);
                    fullPath.add(entry.key);
                    String newVal = valIn.getText().toString();
                    host.runTask("正在写入 DSON 字段…", () -> {
                        SaveEditor.editRawScalar(context, selectedSlot, selectedDsonFile, fullPath, editType[0], newVal);
                        refreshSync();
                    }, "字段「" + entry.key + "」已更新并通过 DSON 自检");
                })
                .setNegativeButton("取消", null).show();
    }

    private View buildRulesModifier(boolean wide) {
        LinearLayout card = card();
        card.addView(host.makeType("非破坏性游戏规则修改器 (生成内置 Mod)", 16, INK, Typeface.DEFAULT_BOLD));
        addTop(card, host.makeType(
                modInstalled ? "状态：已安装 " + ContentModGenerator.MOD_ID + "（修改参数后重新生成即可热更新）"
                        : "状态：尚未生成规则修改器 Mod（不会修改原始 pak 资源，可随时一键卸载还原）",
                13, modInstalled ? GREEN : MUTED, Typeface.DEFAULT), 6);

        addSelectorRow(card, "背包与货币叠加上限倍率", new String[]{"原版 1x", "2x", "5x", "10x", "99x"},
                indexForInt(modConfig.stackMultiplier, new int[]{1, 2, 5, 10, 99}),
                idx -> { modConfig.stackMultiplier = new int[]{1, 2, 5, 10, 99}[idx]; host.requestRedraw(); });

        addSelectorRow(card, "英雄饰品装备槽位", new String[]{"原版 2 格", "3 格", "4 格"},
                indexForInt(modConfig.heroTrinketSlots, new int[]{2, 3, 4}),
                idx -> { modConfig.heroTrinketSlots = new int[]{2, 3, 4}[idx]; host.requestRedraw(); });

        addSelectorRow(card, "战斗动画速度倍率", new String[]{"1.0x", "1.5x", "2.0x", "3.0x"},
                indexForFloat(modConfig.combatSpeedScale, new float[]{1.0f, 1.5f, 2.0f, 3.0f}),
                idx -> { modConfig.combatSpeedScale = new float[]{1.0f, 1.5f, 2.0f, 3.0f}[idx]; host.requestRedraw(); });

        addSelectorRow(card, "我方暴击伤害倍率", new String[]{"原版 1.5x", "2.0x", "2.5x", "3.0x"},
                indexForFloat(modConfig.playerCritMultiplier, new float[]{1.5f, 2.0f, 2.5f, 3.0f}),
                idx -> { modConfig.playerCritMultiplier = new float[]{1.5f, 2.0f, 2.5f, 3.0f}[idx]; host.requestRedraw(); });

        addSelectorRow(card, "美德触发概率 (100 压力时)", new String[]{"原版 25%", "50%", "75%", "必出 100%"},
                indexForInt(modConfig.virtueChancePercent, new int[]{25, 50, 75, 100}),
                idx -> { modConfig.virtueChancePercent = new int[]{25, 50, 75, 100}[idx]; host.requestRedraw(); });

        addSelectorRow(card, "受到压力伤害倍率", new String[]{"原版 100%", "减半 50%", "四分之一", "零压力 0%"},
                indexForFloat(modConfig.stressScale, new float[]{1.0f, 0.5f, 0.25f, 0.0f}),
                idx -> { modConfig.stressScale = new float[]{1.0f, 0.5f, 0.25f, 0.0f}[idx]; host.requestRedraw(); });

        addSelectorRow(card, "城镇每周自动回压", new String[]{"原版 5", "25", "50", "全清 100"},
                indexForFloat(modConfig.idleStressHeal, new float[]{5f, 25f, 50f, 100f}),
                idx -> { modConfig.idleStressHeal = new float[]{5f, 25f, 50f, 100f}[idx]; host.requestRedraw(); });

        addSelectorRow(card, "马车招募与花名册扩容", new String[]{"原版", "2倍招募+20名额", "3倍招募+50名额"},
                modConfig.stageCoachMultiplier >= 3 ? 2 : (modConfig.stageCoachMultiplier == 2 ? 1 : 0),
                idx -> {
                    modConfig.stageCoachMultiplier = new int[]{1, 2, 3}[idx];
                    modConfig.rosterCapacityBonus = new int[]{0, 20, 50}[idx];
                    host.requestRedraw();
                });

        CheckBox freeSupply = new CheckBox(context);
        freeSupply.setText("出征补给免费 (食物/火把/绷带等购买 0 金币)");
        freeSupply.setTextColor(INK);
        freeSupply.setChecked(modConfig.freeSupplies);
        freeSupply.setOnCheckedChangeListener((b, c) -> modConfig.freeSupplies = c);
        addTop(card, freeSupply, 10);

        CheckBox alwaysHit = new CheckBox(context);
        alwaysHit.setText("解锁 100% 绝对命中上限 (取消 95% 命中封顶)");
        alwaysHit.setTextColor(INK);
        alwaysHit.setChecked(modConfig.alwaysHitCap >= 0.999f);
        alwaysHit.setOnCheckedChangeListener((b, c) -> modConfig.alwaysHitCap = c ? 1.0f : 0.95f);
        addTop(card, alwaysHit, 4);

        CheckBox retreat = new CheckBox(context);
        retreat.setText("战斗 100% 无损撤退 (必成功且撤退不加压)");
        retreat.setTextColor(INK);
        retreat.setChecked(modConfig.guaranteedRetreat);
        retreat.setOnCheckedChangeListener((b, c) -> modConfig.guaranteedRetreat = c);
        addTop(card, retreat, 4);

        CheckBox fastXp = new CheckBox(context);
        fastXp.setText("极速升级 (决心经验需求大幅降低)");
        fastXp.setTextColor(INK);
        fastXp.setChecked(modConfig.fastResolveXp);
        fastXp.setOnCheckedChangeListener((b, c) -> modConfig.fastResolveXp = c);
        addTop(card, fastXp, 4);

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
        addTop(card, btns, 14);

        if (modInstalled) {
            addTop(card, host.makeButton("卸载修改器 Mod 并还原原版规则", false, () ->
                    host.runTask("正在卸载规则修改器…", () -> {
                        ContentModGenerator.uninstallFromDevice(context);
                        refreshSync();
                    }, "已卸载规则修改器并恢复原版")), 8);
        }
        return card;
    }

    @FunctionalInterface
    private interface IndexSelect { void onSelect(int idx); }

    private void addSelectorRow(LinearLayout parent, String title, String[] labels, int activeIdx, IndexSelect onSelect) {
        addTop(parent, host.makeType(title, 13, GOLD, Typeface.DEFAULT_BOLD), 10);
        LinearLayout row = horizontal();
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            View b = host.makeButton(labels[i], i == activeIdx, () -> onSelect.onSelect(idx));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
            if (i + 1 < labels.length) lp.setMarginEnd(host.dp(4));
            row.addView(b, lp);
        }
        addTop(parent, row, 4);
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
        et.setBackground(host.makeSurface(RAISED, LINE, 8));
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

    private static float parseFloatSafe(String s, float def) {
        try { return Float.parseFloat(s.trim()); } catch (Exception e) { return def; }
    }
}
