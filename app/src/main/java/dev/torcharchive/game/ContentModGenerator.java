package dev.torcharchive.game;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Generates and manages a clean, non-destructive override mod (`torch_content_modifier`)
 * modifying inventory stacks, trinket slots, combat speed, combat/stress/virtue rules,
 * town stress heal, and stage coach capacity.
 */
public final class ContentModGenerator {
    public static final String MOD_ID = "torch_content_modifier";
    private static final String CONFIG_FILE = "modifier-config.json";

    public static final class Config {
        public int stackMultiplier = 1;          // 1, 2, 5, 10, 99
        public boolean freeSupplies = false;
        public int heroTrinketSlots = 2;         // 2, 3, 4
        public int raidInventorySlots = 16;      // 16, 20, 24
        public float combatSpeedScale = 1.0f;    // 1.0, 1.5, 2.0, 3.0
        public float playerCritMultiplier = 1.5f;// 1.5, 2.0, 2.5, 3.0
        public float alwaysHitCap = 0.95f;       // 0.95 or 1.00
        public boolean guaranteedRetreat = false;
        public int virtueChancePercent = 25;     // 25, 50, 75, 100
        public float stressScale = 1.0f;         // 1.0, 0.5, 0.25, 0.0
        public float idleStressHeal = 5.0f;      // 5, 25, 50, 100
        public boolean fastResolveXp = false;
        public int stageCoachMultiplier = 1;     // 1, 2, 3
        public int rosterCapacityBonus = 0;      // 0, 20, 50, 100

        public String toJsonString() {
            return String.format(Locale.ROOT, """
                    {
                      "stackMultiplier": %d,
                      "freeSupplies": %s,
                      "heroTrinketSlots": %d,
                      "raidInventorySlots": %d,
                      "combatSpeedScale": %.2f,
                      "playerCritMultiplier": %.2f,
                      "alwaysHitCap": %.2f,
                      "guaranteedRetreat": %s,
                      "virtueChancePercent": %d,
                      "stressScale": %.2f,
                      "idleStressHeal": %.1f,
                      "fastResolveXp": %s,
                      "stageCoachMultiplier": %d,
                      "rosterCapacityBonus": %d
                    }
                    """,
                    stackMultiplier, freeSupplies, heroTrinketSlots, raidInventorySlots,
                    combatSpeedScale, playerCritMultiplier, alwaysHitCap, guaranteedRetreat,
                    virtueChancePercent, stressScale, idleStressHeal, fastResolveXp,
                    stageCoachMultiplier, rosterCapacityBonus);
        }

        public static Config fromJson(JSONObject obj) {
            Config c = new Config();
            if (obj == null) return c;
            c.stackMultiplier = obj.optInt("stackMultiplier", 1);
            c.freeSupplies = obj.optBoolean("freeSupplies", false);
            c.heroTrinketSlots = obj.optInt("heroTrinketSlots", 2);
            c.raidInventorySlots = obj.optInt("raidInventorySlots", 16);
            c.combatSpeedScale = (float) obj.optDouble("combatSpeedScale", 1.0);
            c.playerCritMultiplier = (float) obj.optDouble("playerCritMultiplier", 1.5);
            c.alwaysHitCap = (float) obj.optDouble("alwaysHitCap", 0.95);
            c.guaranteedRetreat = obj.optBoolean("guaranteedRetreat", false);
            c.virtueChancePercent = obj.optInt("virtueChancePercent", 25);
            c.stressScale = (float) obj.optDouble("stressScale", 1.0);
            c.idleStressHeal = (float) obj.optDouble("idleStressHeal", 5.0);
            c.fastResolveXp = obj.optBoolean("fastResolveXp", false);
            c.stageCoachMultiplier = obj.optInt("stageCoachMultiplier", 1);
            c.rosterCapacityBonus = obj.optInt("rosterCapacityBonus", 0);
            return c;
        }
    }

    private ContentModGenerator() {}

    public static Config loadConfig(Context context) {
        try {
            Path cfgFile = ModBridge.modsDir(context).toPath().resolve(MOD_ID).resolve(CONFIG_FILE);
            if (Files.isRegularFile(cfgFile)) {
                return Config.fromJson(new JSONObject(new String(Files.readAllBytes(cfgFile), StandardCharsets.UTF_8)));
            }
        } catch (Exception ignored) {
        }
        return new Config();
    }

    public static boolean isInstalled(Context context) {
        try {
            return Files.isRegularFile(ModBridge.modsDir(context).toPath().resolve(MOD_ID).resolve("project.xml"));
        } catch (IOException ignored) {
            return false;
        }
    }

    public static void applyToDevice(Context context, Config cfg, String enableForSlot) throws IOException {
        NxProcessControl.stopGameProcess(context);
        Path modsRoot = ModBridge.modsDir(context).toPath();
        Path modDir = modsRoot.resolve(MOD_ID);
        generateMod(modDir, cfg);
        // Clear Wicked's conversion cache so NxMods picks up fresh mod overrides on next launch
        Files.deleteIfExists(modsRoot.resolve(".port-switch").resolve(MOD_ID));
        if (enableForSlot != null && enableForSlot.matches("profile_[0-8]")) {
            ModBridge.ensureModEnabledInSlot(context, enableForSlot, MOD_ID, true, true);
        }
    }

    public static void uninstallFromDevice(Context context) throws IOException {
        NxProcessControl.stopGameProcess(context);
        Path modsRoot = ModBridge.modsDir(context).toPath();
        for (int i = 0; i <= 8; i++) {
            ModBridge.ensureModEnabledInSlot(context, "profile_" + i, MOD_ID, false, false);
        }
        if (Files.isDirectory(modsRoot.resolve(MOD_ID))) {
            ModBridge.deleteMod(context, MOD_ID);
        }
    }

    /**
     * Pure Path-based generator producing valid Darkest Dungeon override files.
     */
    public static void generateMod(Path modDir, Config cfg) throws IOException {
        ModBridge.deleteTree(modDir);
        Files.createDirectories(modDir);

        writeText(modDir.resolve("project.xml"), buildProjectXml());
        writeText(modDir.resolve(CONFIG_FILE), cfg.toJsonString());

        // 1. Inventory stack limits & free supplies
        int mult = Math.max(1, cfg.stackMultiplier);
        if (mult != 1 || cfg.freeSupplies) {
            writeText(modDir.resolve("inventory/base.currency.inventory.items.darkest"),
                    buildCurrencyDarkest(mult));
            writeText(modDir.resolve("inventory/base.gem.inventory.items.darkest"),
                    buildGemDarkest(mult));
            writeText(modDir.resolve("inventory/base.supply.inventory.items.darkest"),
                    buildSupplyDarkest(mult, cfg.freeSupplies));
        }

        // 2. System inventory slots (raid inventory & equipped trinkets)
        if (cfg.heroTrinketSlots != 2 || cfg.raidInventorySlots != 16) {
            writeText(modDir.resolve("inventory/base.inventory.system_configs.darkest"),
                    buildSystemConfigsDarkest(cfg.raidInventorySlots, cfg.heroTrinketSlots));
        }

        // 3. Combat speed
        if (Math.abs(cfg.combatSpeedScale - 1.0f) > 0.01f) {
            writeText(modDir.resolve("shared/speed.rules.json"),
                    String.format(Locale.ROOT, "{\n    \"time_scale_combat\": %.2f\n}\n", cfg.combatSpeedScale));
        }

        // 4. Shared combat/stress/virtue/retreat rules
        if (Math.abs(cfg.playerCritMultiplier - 1.5f) > 0.01f
                || Math.abs(cfg.alwaysHitCap - 0.95f) > 0.001f
                || cfg.guaranteedRetreat
                || cfg.virtueChancePercent != 25
                || Math.abs(cfg.stressScale - 1.0f) > 0.01f) {
            writeText(modDir.resolve("shared/rules.json"), buildRulesJson(cfg));
        }

        // 5. Roster resolve XP & idle town stress recovery
        if (Math.abs(cfg.idleStressHeal - 5.0f) > 0.01f || cfg.fastResolveXp) {
            writeText(modDir.resolve("campaign/roster/roster.variables.json"), buildRosterVariablesJson(cfg));
        }

        // 6. Stage coach recruits & roster cap
        if (cfg.stageCoachMultiplier != 1 || cfg.rosterCapacityBonus != 0) {
            writeText(modDir.resolve("campaign/town/buildings/stage_coach/stage_coach.building.json"),
                    buildStageCoachJson(cfg));
        }
    }

    private static String buildProjectXml() {
        return """
                <?xml version="1.0" encoding="utf-8"?>
                <project>
                    <PreviewIconFile></PreviewIconFile>
                    <ItemDescriptionShort/>
                    <ModDataPath></ModDataPath>
                    <Title>余烬完整游戏修改器 (Torch Content Modifier)</Title>
                    <Language>schinese</Language>
                    <UpdateDetails/>
                    <Visibility>private</Visibility>
                    <UploadMode>direct_upload</UploadMode>
                    <VersionMajor>2</VersionMajor>
                    <VersionMinor>0</VersionMinor>
                    <TargetBuild>0</TargetBuild>
                    <Tags>
                        <Tags>Gameplay</Tags>
                        <Tags>Tweaks</Tags>
                    </Tags>
                    <ItemDescription>由余烬启动器内置完整修改器生成的非破坏性规则覆盖 Mod。</ItemDescription>
                </project>
                """;
    }

    private static String buildCurrencyDarkest(int mult) {
        return String.format(Locale.ROOT,
                "inventory_item:\t.type \"gold\"\t\t.id \"\"\t\t\t\t\t\t    .base_stack_limit %d\t.purchase_gold_value 0\t\t\t.sell_gold_value 0\n\n"
                        + "inventory_item:\t.type \"heirloom\"\t.id \"portrait\"\t\t\t\t\t.base_stack_limit %d\t\t.purchase_gold_value 0\t\t\t.sell_gold_value 0\n"
                        + "inventory_item:\t.type \"heirloom\"\t.id \"bust\"\t\t\t\t\t\t.base_stack_limit %d\t\t.purchase_gold_value 0\t\t\t.sell_gold_value 0\n"
                        + "inventory_item:\t.type \"heirloom\"\t.id \"crest\"\t\t\t\t\t\t.base_stack_limit %d\t.purchase_gold_value 0\t\t\t.sell_gold_value 0\n"
                        + "inventory_item:\t.type \"heirloom\"\t.id \"deed\"\t\t\t\t\t\t.base_stack_limit %d\t\t.purchase_gold_value 0\t\t\t.sell_gold_value 0\n"
                        + "inventory_item:\t.type \"heirloom\"\t.id \"urn\"\t\t\t\t\t\t.base_stack_limit %d\t\t.purchase_gold_value 0\t\t\t.sell_gold_value 0\n",
                1750 * mult, 3 * mult, 6 * mult, 12 * mult, 6 * mult, Math.max(1, mult));
    }

    private static String buildGemDarkest(int mult) {
        return String.format(Locale.ROOT,
                "inventory_item:\t.type \"gem\"\t\t.id \"ancient_idol\"\t\t.base_stack_limit %d     .can_modify_stack_limit true\t.purchase_gold_value 0\t\t\t.sell_gold_value 4500\n"
                        + "inventory_item:\t.type \"gem\"\t\t.id \"ruby\"\t\t\t\t.base_stack_limit %d\t    \t                            .purchase_gold_value 0\t\t\t.sell_gold_value 1250\n"
                        + "inventory_item:\t.type \"gem\"\t\t.id \"sapphire\"\t\t\t.base_stack_limit %d\t    \t                            .purchase_gold_value 0\t\t\t.sell_gold_value 1000\n"
                        + "inventory_item:\t.type \"gem\"\t\t.id \"emerald\"\t\t\t.base_stack_limit %d\t    \t                            .purchase_gold_value 0\t\t\t.sell_gold_value 750\n"
                        + "inventory_item:\t.type \"gem\"\t\t.id \"onyx\"\t\t\t\t.base_stack_limit %d\t    \t                            .purchase_gold_value 0\t\t\t.sell_gold_value 500\n"
                        + "inventory_item:\t.type \"gem\"\t\t.id \"jade\"\t\t\t\t.base_stack_limit %d\t    \t                            .purchase_gold_value 0\t\t\t.sell_gold_value 375\n"
                        + "inventory_item:\t.type \"gem\"\t\t.id \"citrine\"\t\t\t.base_stack_limit %d\t    \t                            .purchase_gold_value 0\t\t\t.sell_gold_value 250\n"
                        + "inventory_item:\t.type \"gem\"\t\t.id \"trapezohedron\"\t\t.base_stack_limit %d\t    \t\t\t\t\t\t    .purchase_gold_value 0\t\t\t.sell_gold_value 3500\n"
                        + "inventory_item:\t.type \"gem\"\t\t.id \"antiqrelicsmall\"\t.base_stack_limit %d    .can_modify_stack_limit true\t.purchase_gold_value 0\t\t\t.sell_gold_value 500\n"
                        + "inventory_item:\t.type \"gem\"\t\t.id \"antiqrelic\"\t\t.base_stack_limit %d\t    .can_modify_stack_limit true\t.purchase_gold_value 0\t\t\t.sell_gold_value 1250\n"
                        + "inventory_item:\t.type \"gem\"\t\t.id \"pewrelic\"\t\t\t.base_stack_limit %d\t    .can_modify_stack_limit true\t.purchase_gold_value 0\t\t\t.sell_gold_value 2500\n",
                mult, 5 * mult, 5 * mult, 5 * mult, 5 * mult, 5 * mult, 5 * mult, mult, 20 * mult, 5 * mult, mult);
    }

    private static String buildSupplyDarkest(int mult, boolean free) {
        int foodCost = free ? 0 : 75;
        int shovelCost = free ? 0 : 250;
        int medCost = free ? 0 : 150;
        int keyCost = free ? 0 : 200;
        int torchCost = free ? 0 : 75;
        int laudCost = free ? 0 : 100;
        return String.format(Locale.ROOT,
                "inventory_item:\t.type \"provision\"\t.id \"\"\t\t\t\t\t\t    .base_stack_limit %d\t.purchase_gold_value %d\t\t\t.sell_gold_value 5\n\n"
                        + "inventory_item:\t.type \"supply\"\t\t.id \"shovel\"\t\t\t\t\t.base_stack_limit %d\t\t.purchase_gold_value %d\t\t.sell_gold_value 25\n"
                        + "inventory_item:\t.type \"supply\"\t\t.id \"firewood\"\t\t\t\t\t.base_stack_limit %d\t\t.purchase_gold_value 0\t\t\t.sell_gold_value 0\n"
                        + "inventory_item:\t.type \"supply\"\t\t.id \"bandage\"\t\t\t\t\t.base_stack_limit %d\t\t.purchase_gold_value %d\t\t.sell_gold_value 15     .act_out_consume_priority 1 .replace_buffs true\n"
                        + "inventory_item:\t.type \"supply\"\t\t.id \"antivenom\"\t\t\t\t\t.base_stack_limit %d\t\t.purchase_gold_value %d\t\t.sell_gold_value 15     .act_out_consume_priority 1 .replace_buffs true\n"
                        + "inventory_item:\t.type \"supply\"\t\t.id \"skeleton_key\"\t\t\t\t.base_stack_limit %d\t\t.purchase_gold_value %d\t\t.sell_gold_value 20 \n"
                        + "inventory_item:\t.type \"supply\"\t\t.id \"medicinal_herbs\"\t\t\t.base_stack_limit %d\t\t.purchase_gold_value %d\t\t.sell_gold_value 20     .act_out_consume_priority 1 .replace_buffs true\n"
                        + "inventory_item:\t.type \"supply\"\t\t.id \"torch\"\t\t\t\t\t\t.base_stack_limit %d\t\t.purchase_gold_value %d\t\t\t.sell_gold_value 5      .act_out_consume_priority 1\n"
                        + "inventory_item:\t.type \"supply\"\t\t.id \"holy_water\"\t\t\t\t.base_stack_limit %d\t\t.purchase_gold_value %d\t\t.sell_gold_value 15     .act_out_consume_priority 1\n"
                        + "inventory_item: .type \"supply\"\t\t.id \"dog_treats\"\t\t\t\t.base_stack_limit %d\t\t.purchase_gold_value 0\t\t\t.sell_gold_value 0      .act_out_consume_priority 1\n"
                        + "inventory_item: .type \"supply\"\t\t.id \"laudanum\"\t\t\t\t    .base_stack_limit %d\t\t.purchase_gold_value %d\t\t.sell_gold_value 20     .act_out_consume_priority 1 .replace_buffs true\n",
                12 * mult, foodCost,
                4 * mult, shovelCost,
                Math.max(1, mult),
                6 * mult, medCost,
                6 * mult, medCost,
                6 * mult, keyCost,
                6 * mult, keyCost,
                8 * mult, torchCost,
                6 * mult, medCost,
                2 * mult,
                6 * mult, laudCost);
    }

    private static String buildSystemConfigsDarkest(int raidSlots, int trinketSlots) {
        return String.format(Locale.ROOT,
                "inventory_system_config:\t.type \"raid\"\t\t\t\t\t\t.max_slots %d\t\t\t\t.use_stack_limits true\n"
                        + "inventory_system_config:\t.type \"quest_rewards\"\t\t\t\t.max_slots 16\t\t\t\t.use_stack_limits false\n"
                        + "inventory_system_config:\t.type \"provision\"\t\t\t\t\t.max_slots 14\t\t\t\t.use_stack_limits false\n"
                        + "inventory_system_config:\t.type \"trinket_store\"\t\t\t\t.max_slots 16\t\t\t\t.use_stack_limits true\n"
                        + "inventory_system_config:\t.type \"trinket_storage\"\t\t\t\t.max_slots 9999\t\t\t\t.use_stack_limits true\n"
                        + "inventory_system_config:\t.type \"arena_trinket_storage\"\t\t.max_slots 999\t\t\t\t.use_stack_limits true\n"
                        + "inventory_system_config:\t.type \"loot\"\t\t\t\t\t\t.max_slots 99\t\t\t\t.use_stack_limits true\n"
                        + "inventory_system_config:\t.type \"hero_equipped_trinkets\"\t\t.max_slots %d\t\t\t\t.use_stack_limits true\n"
                        + "inventory_system_config:\t.type \"goal_starting_items\"\t\t\t.max_slots 99\t\t\t\t.use_stack_limits true\n"
                        + "inventory_system_config:\t.type \"raid_results\"\t\t\t\t.max_slots 99\t\t\t\t.use_stack_limits true\n"
                        + "inventory_system_config:    .type \"effect_use_items\"            .max_slots 99               .use_stack_limits true\n",
                Math.max(16, Math.min(32, raidSlots)),
                Math.max(2, Math.min(4, trinketSlots)));
    }

    private static String buildRosterVariablesJson(Config cfg) {
        String thresholds = cfg.fastResolveXp ? "[0, 1, 2, 4, 6, 9, 12]" : "[0, 2, 8, 14, 24, 36, 48]";
        return String.format(Locale.ROOT, """
                {
                    "name_id_format": "hero_name_%%d",
                    "resolve_level_thresholds": %s,
                    "town_visit_town_progression": {
                        "idle_hero_stress_heal": %.1f
                    },
                    "town_visit_non_town_progression": {
                        "idle_hero_stress_heal": 0.0
                    }
                }
                """, thresholds, cfg.idleStressHeal);
    }

    private static String buildStageCoachJson(Config cfg) {
        int mult = Math.max(1, cfg.stageCoachMultiplier);
        int bonus = Math.max(0, cfg.rosterCapacityBonus);
        return String.format(Locale.ROOT, """
                {
                    "on_start_town_visit_priority": 0,
                    "requirements": {
                        "number_of_quests_finished": 0,
                        "highest_dungeon_level": 0
                    },
                    "data": {
                        "stores": [
                            {
                                "id": "hero_recruit",
                                "data": {
                                    "first_hero_classes": ["plague_doctor", "vestal"],
                                    "number_of_recruits_upgrades": [
                                        { "amount": %d },
                                        { "amount": %d, "upgrade_tree_id": "stage_coach.numrecruits", "upgrade_requirement_code": "a" },
                                        { "amount": %d, "upgrade_tree_id": "stage_coach.numrecruits", "upgrade_requirement_code": "b" },
                                        { "amount": %d, "upgrade_tree_id": "stage_coach.numrecruits", "upgrade_requirement_code": "c" },
                                        { "amount": %d, "upgrade_tree_id": "stage_coach.numrecruits", "upgrade_requirement_code": "d" },
                                        { "amount": %d, "upgrade_tree_id": "stage_coach.numrecruits", "upgrade_requirement_code": "e" }
                                    ],
                                    "roster_size_upgrades": [
                                        { "amount": %d },
                                        { "amount": %d, "upgrade_tree_id": "stage_coach.rostersize", "upgrade_requirement_code": "a" },
                                        { "amount": %d, "upgrade_tree_id": "stage_coach.rostersize", "upgrade_requirement_code": "b" },
                                        { "amount": %d, "upgrade_tree_id": "stage_coach.rostersize", "upgrade_requirement_code": "c" },
                                        { "amount": %d, "upgrade_tree_id": "stage_coach.rostersize", "upgrade_requirement_code": "d" },
                                        { "amount": %d, "upgrade_tree_id": "stage_coach.rostersize", "upgrade_requirement_code": "e" }
                                    ],
                                    "upgraded_recruits_upgrades": [
                                        { "level": 1, "chance": 0.1875, "number_of_extra_positive_quirks": 0, "number_of_extra_negative_quirks": 0, "number_of_extra_combat_skills": 0, "number_of_extra_camping_skills": 1, "guaranteed_previous_raid_dead_hero_levels": [3], "upgrade_tree_id": "stage_coach.upgraded_recruits", "upgrade_requirement_code": "a" },
                                        { "level": 2, "chance": 0.125, "number_of_extra_positive_quirks": 1, "number_of_extra_negative_quirks": 1, "number_of_extra_combat_skills": 1, "number_of_extra_camping_skills": 1, "guaranteed_previous_raid_dead_hero_levels": [4], "upgrade_tree_id": "stage_coach.upgraded_recruits", "upgrade_requirement_code": "b" },
                                        { "level": 3, "chance": 0.0625, "number_of_extra_positive_quirks": 2, "number_of_extra_negative_quirks": 2, "number_of_extra_combat_skills": 2, "number_of_extra_camping_skills": 2, "guaranteed_previous_raid_dead_hero_levels": [5, 6], "upgrade_tree_id": "stage_coach.upgraded_recruits", "upgrade_requirement_code": "c" }
                                    ]
                                }
                            }
                        ]
                    }
                }
                """,
                2 * mult, 3 * mult, 4 * mult, 5 * mult, 6 * mult, 7 * mult,
                9 + bonus, 12 + bonus, 16 + bonus, 20 + bonus, 24 + bonus, 28 + bonus);
    }

    private static String buildRulesJson(Config cfg) {
        float hitCap = cfg.alwaysHitCap;
        float critMult = cfg.playerCritMultiplier;
        float retreat = cfg.guaranteedRetreat ? 1.0f : 0.70f;
        float retreatStress = cfg.guaranteedRetreat ? 0.0f : 20.0f * cfg.stressScale;
        float sScale = Math.max(0f, cfg.stressScale);
        double affOnset = Math.max(0.0, Math.min(0.95, 1.0 - (cfg.virtueChancePercent / 100.0)));
        double critSucc = Math.max(0.05, Math.min(1.0, cfg.virtueChancePercent / 100.0));
        return String.format(Locale.ROOT, """
                {
                    "cutoffAlwaysMiss": 0.00,
                    "cutoffAlwaysHit": %.2f,
                    "excessCritMult": 0.0,
                    "excessCritMax": 0.20,
                    "player_crit_multiplier_table": [{ "chance": 1, "value": %.2f }],
                    "monster_crit_multiplier_table": [{ "chance": 1, "value": 1.5 }],
                    "monster_crit": { "stress_chance": %.2f, "stress_damage": %d, "companion_stress_chance": %.2f, "companion_stress_damage": %d },
                    "player_crit": { "relief_chance": 1.0, "stress_relief": 3, "companion_relief_chance": 0.25, "companion_stress_relief": 3 },
                    "playerCritBarkChance": 0.75,
                    "combat_retreat_chance": %.2f,
                    "combat_retreat_bonus_chance_per_attempt": 0.05,
                    "combat_retreat_stress": %.1f,
                    "passed_turn_stress_dmg": %.1f,
                    "deaths_door_self_stress_chance": 1.0,
                    "deaths_door_companion_stress_chance": 0.25,
                    "deaths_door_self_stress_dmg": %.1f,
                    "deaths_door_companion_stress_dmg": %.1f,
                    "deaths_door_self_stress_chance_repeat": 1.0,
                    "deaths_door_companion_stress_chance_repeat": 0.25,
                    "deaths_door_self_stress_dmg_repeat": %.1f,
                    "deaths_door_companion_stress_dmg_repeat": %.1f,
                    "stress_dmg_buff_min": 0.2,
                    "hallway_stress": {
                        "hallway_per_tile_stress_damage_fwd": %.1f,
                        "hallway_per_tile_stress_damage_chance_fwd": %.2f,
                        "hallway_per_tile_stress_damage_backing_up": %.1f,
                        "hallway_per_tile_stress_damage_chance_backing_up": %.2f
                    },
                    "killing_blow_relief_chance": 0.5,
                    "killing_blow_stress_relief": 3.0,
                    "effectiveDifficultyMin": 0.0,
                    "effectiveDifficultyMax": 6.0,
                    "effectiveDifficultyDungeonStartingStress": [0.0, %.1f, %.1f, %.1f, %.1f, %.1f, %.1f],
                    "effectiveDifficultyStressDmgModifiers": [0.0, %.2f, %.2f, %.2f, %.2f, %.2f, %.2f],
                    "effectiveDifficultyBarkStress": %.1f,
                    "base_affliction_history_WF": 4,
                    "affliction_history_increment": [0, 2, 3, 4],
                    "base_virtue_history_WF": 4,
                    "affliction_base_onset_chance": %.2f,
                    "affliction_critical_success_chance": %.2f,
                    "affliction_min_chance": 0.0,
                    "affliction_max_chance": %.2f,
                    "stress_affliction_resisted": 45.0,
                    "stress_affliction_critically_resisted": 45.0,
                    "stress_affliction_deactivated": 0.0,
                    "effectiveDifficultyAfflictionOnsetModifiers": [0.0, 0.05, 0.10, 0.15, 0.20, 0.25, 0.33],
                    "affliction_severity_table": [{ "chance": 50, "value": 1 }],
                    "deactivation_chance_small": 0.1,
                    "deactivation_chance_med": 0.1,
                    "masterBarkChance": 0.50,
                    "curioTriggerChance_low": 0.15,
                    "curioTriggerChance_med": 0.25,
                    "curioTriggerChance_high": 0.50,
                    "quirks_fated_miss2hitconvertchance": 0.10,
                    "quirks_max_positive": 5,
                    "quirks_max_negative": 5,
                    "quirks_max_diseases": 3,
                    "quirks_max_locked_positive": 3,
                    "quirks_max_locked_negative": 3,
                    "quirk_negative_locked_after_turn_count": 2,
                    "quirk_chance_to_lock_negative": 0.25,
                    "negStress0Success": 0.30,
                    "negStress100Success": 0.50,
                    "posStress0Success": 0.50,
                    "posStress100Success": 0.40,
                    "negStress0Failure": 0.40,
                    "negStress100Failure": 0.70,
                    "posStress0Failure": 0.40,
                    "posStress100Failure": 0.25,
                    "disease_after_quest_min_resolve_level": 2,
                    "disease_after_quest_min_chance": 0.05,
                    "disease_max_chance": 0.32,
                    "disease_hero_disease_resist_weight": 0.33,
                    "effect_crit_roll_mod": -0.2,
                    "effect_crit_disease_resist_roll_mod": 0.2,
                    "dotHallwayStepRatio": 0.50,
                    "tile_light_loss": [
                        { "key": "clear", "data": 1.0 },
                        { "key": "dim", "data": 6.0 },
                        { "key": "dark", "data": 6.0 }
                    ],
                    "corridor_return_content": [
                        { "key": "ac_battle", "data": { "base_chance": 0.05, "base_dark_mod_table": { "lowest_excluded": true, "range_table": [{ "range": { "lower": -3.402823e38, "upper": 3.402823e38 }, "value": 0.0 }] } } },
                        { "key": "ac_hunger", "data": { "base_chance": 0.075, "base_dark_mod_table": { "lowest_excluded": true, "range_table": [{ "range": { "lower": -3.402823e38, "upper": 3.402823e38 }, "value": 0.0 }] } } },
                        { "key": "ac_trap", "data": { "base_chance": 0.05, "base_dark_mod_table": { "lowest_excluded": true, "range_table": [{ "range": { "lower": -3.402823e38, "upper": 3.402823e38 }, "value": 0.0 }] } } }
                    ],
                    "darkness": {
                        "lowest_excluded": true,
                        "range_table": [
                            { "range": { "lower": 75.0, "upper": 3.402823e38 }, "value": { "stress_chance_increase": 0, "stress_damage_increase": 0, "monster_attack_increase": 0, "monster_damage_increase": 0, "monster_crit_increase": 0, "loot_increase_gold": 0, "loot_increase_treasure_draws": 0, "player_crit_increase": 0, "player_def_increase": 4.0, "player_scouting_increase": 15.0, "monsters_surprised_increase": 25.0, "heroes_surprised_increase": 0.0 } },
                            { "range": { "lower": 50, "upper": 75 }, "value": { "stress_chance_increase": 5.0, "stress_damage_increase": 10.0, "monster_attack_increase": 0.0, "monster_damage_increase": 0.0, "monster_crit_increase": 1.0, "loot_increase_gold": 0.0, "loot_increase_treasure_draws": 0.0, "player_crit_increase": 0.0, "player_def_increase": 2.5, "player_scouting_increase": 7.5, "monsters_surprised_increase": 15.0, "heroes_surprised_increase": 0.0 } },
                            { "range": { "lower": 25, "upper": 50 }, "value": { "stress_chance_increase": 10.0, "stress_damage_increase": 20.0, "monster_attack_increase": 5.0, "monster_damage_increase": 10.0, "monster_crit_increase": 2.0, "loot_increase_gold": 10.0, "loot_increase_treasure_draws": 4.0, "player_crit_increase": 1.0, "player_def_increase": 0.0, "player_scouting_increase": 0.0, "monsters_surprised_increase": 10.0, "heroes_surprised_increase": 15.0 } },
                            { "range": { "lower": 0, "upper": 25 }, "value": { "stress_chance_increase": 25.0, "stress_damage_increase": 30.0, "monster_attack_increase": 10.0, "monster_damage_increase": 15.0, "monster_crit_increase": 3.0, "loot_increase_gold": 15.0, "loot_increase_treasure_draws": 8.0, "player_crit_increase": 2.0, "player_def_increase": 0.0, "player_scouting_increase": 0.0, "monsters_surprised_increase": 5.0, "heroes_surprised_increase": 25.0 } },
                            { "range": { "lower": -3.402823e38, "upper": 0 }, "value": { "stress_chance_increase": 35.0, "stress_damage_increase": 40.0, "monster_attack_increase": 12.5, "monster_damage_increase": 25.0, "monster_crit_increase": 5.0, "loot_increase_gold": 30.0, "loot_increase_treasure_draws": 12.0, "player_crit_increase": 3.0, "player_def_increase": 0.0, "player_scouting_increase": 0.0, "monsters_surprised_increase": 0.0, "heroes_surprised_increase": 40.0 } }
                        ]
                    },
                    "provision_hp_heal": 0.05,
                    "hallway_hunger_HPrestore": 0.05,
                    "hallway_hunger_starve_HPdmg": 0.20,
                    "meals_table": [
                        { "type": "none", "rations_per": 0, "healing": -0.20, "stress": 15 },
                        { "type": "half", "rations_per": 0.5, "healing": 0, "stress": 0 },
                        { "type": "full", "rations_per": 1.0, "healing": 0.10, "stress": 0 },
                        { "type": "feast", "rations_per": 2.0, "healing": 0.25, "stress": -10 }
                    ],
                    "camp_restore_torch": 100,
                    "ambush_torch_reduction": -100,
                    "camp_relieve_stress": 0,
                    "max_number_of_camping_skills": 3,
                    "camp_start_camping_points": 12,
                    "camp_rest_point_threshold": 6,
                    "camp_rest_number_of_hero_threshold": 1,
                    "scouting_enter_dungeon_scout_chance": 0.0,
                    "scouting_enter_dungeon_quest_item_scout_chance": 0.0,
                    "scouting_chance_scout_treasure": 0.0,
                    "scouting_crit_success": 0.50,
                    "scouting_chance_base": 0.25,
                    "death_party_stress_chance": %.2f,
                    "death_party_stress_damage": %d,
                    "deathblow_chance": 1.0,
                    "m_ForwardAcceleration": 1600.0,
                    "m_ReverseAcceleration": 800.0,
                    "m_MaxForwardSpeed": 400.0,
                    "m_MaxReverseSpeed": 200.0,
                    "m_Deceleration": 1600.0,
                    "m_InteractionAreaPixelWidth": 470.0,
                    "m_InteractionAreaPixelHeight": 400.0,
                    "max_provisions_before_full": 4,
                    "surprise_corridor_party_base_chance": 0.10,
                    "surprise_corridor_monsters_base_chance": 0.10,
                    "surprise_room_party_base_chance": 0.10,
                    "surprise_room_monsters_base_chance": 0.10,
                    "surprise_known_corridor_party_base_chance": -1.0,
                    "surprise_known_corridor_monsters_base_chance": 0.25,
                    "surprise_known_room_party_base_chance": -1.0,
                    "surprise_known_room_monsters_base_chance": 0.25,
                    "surprise_ambush_party_base_chance": 1.0,
                    "surprise_ambush_monsters_base_chance": 0.0,
                    "surprise_max_party_surprised_chance": 0.65,
                    "surprise_max_monsters_surprised_chance": 0.65,
                    "ambush_camping_base_chance": 0.33,
                    "trap_scout_disarm_bonus": 0.40,
                    "stall_party_size_threshold": 3,
                    "stall_enemy_size_threshold": 2,
                    "stall_number_of_invalidating_skills_threshold": 2,
                    "stall_round_stress_effect_threshold": 3,
                    "stall_round_summon_effect_threshold": 4,
                    "stall_round_reset_threshold": 4,
                    "stall_extra_round_size_threshold": 2,
                    "stall_extra_round_size_amount": 1,
                    "stall_stress_effect_name": "stall_stress",
                    "stall_round_accelerated_only_consecutive_increment": 5,
                    "stall_round_accelerated_only_increment": 1,
                    "consecutive_miss_reduction_acc_buff": 0.04,
                    "min_hero_count_for_escape": 3,
                    "dismissed_hero_stress_penalties": [
                        { "upper_level": 4, "stress_penalty": 5.0 },
                        { "upper_level": 12, "stress_penalty": 10.0 },
                        { "upper_level": 1000000, "stress_penalty": 20.0 }
                    ],
                    "new_game_plus_week_limit": 91,
                    "new_game_plus_hero_death_limit": 13,
                    "new_game_plus_monster_max_hp_modifier": 0.15,
                    "new_game_plus_monster_crit_modifier": 0.025,
                    "min_available_activity_slots_for_crier": 12,
                    "difficulty_trap_base": [0.0, 0.0, 0.0, 0.20, 0.20, 0.40, 0.50],
                    "trinkets_equipped_warning_min_percent": 0.5,
                    "trinkets_equipped_warning_dungeon_min_difficulty": 3,
                    "the_blood_item_health_damage_percent": 0.33,
                    "health_damage_blocks_max": 2,
                    "heal_buff_percent_min": 0.0,
                    "heal_buff_percent_max": 2.0,
                    "stun_onset_buffs": [],
                    "stun_onset_buff_consecutive_applies": false,
                    "stun_recovery_buffs": ["STUNRECOVERYBUFF"],
                    "stun_recovery_buff_consecutive_applies": true,
                    "stun_ends_turn": true,
                    "initiative_base": 8
                }
                """,
                hitCap, critMult,
                sScale > 0 ? 1.0f : 0.0f, Math.round(10 * sScale),
                sScale > 0 ? 0.50f : 0.0f, Math.round(5 * sScale),
                retreat, retreatStress,
                5.0f * sScale,
                8.0f * sScale, 4.0f * sScale,
                4.0f * sScale, 2.0f * sScale,
                2.0f * sScale, sScale > 0 ? 0.30f : 0.0f,
                5.0f * sScale, sScale > 0 ? 0.55f : 0.0f,
                20.0f * sScale, 30.0f * sScale, 40.0f * sScale, 50.0f * sScale, 60.0f * sScale, 70.0f * sScale,
                0.25f * sScale, 0.50f * sScale, 0.75f * sScale, 1.00f * sScale, 1.25f * sScale, 1.50f * sScale,
                2.0f * sScale,
                affOnset, critSucc, cfg.virtueChancePercent >= 100 ? 0.0 : 0.99,
                sScale > 0 ? 0.50f : 0.0f, Math.round(12 * sScale));
    }

    private static void writeText(Path target, String content) throws IOException {
        Files.createDirectories(target.getParent());
        Files.write(target, content.getBytes(StandardCharsets.UTF_8));
    }
}
