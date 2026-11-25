package dev.torcharchive.game;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Curated Darkest Dungeon hero, quirk, virtue, affliction, trinket, and building catalogs
 * for the offline in-app SaveEditor & ContentModGenerator.
 */
public final class CatalogData {
    public static final class NamedEntry {
        public final String id;
        public final String label;
        public final String category;

        public NamedEntry(String id, String label, String category) {
            this.id = id;
            this.label = label;
            this.category = category;
        }
    }

    public static final class BuildingTree {
        public final String treeId;
        public final String[] reqCodes;

        public BuildingTree(String treeId, String... reqCodes) {
            this.treeId = treeId;
            this.reqCodes = reqCodes;
        }
    }

    public static final Map<String, String> HERO_CLASSES = new LinkedHashMap<>();
    public static final Map<String, String> VIRTUES = new LinkedHashMap<>();
    public static final Map<String, String> AFFLICTIONS = new LinkedHashMap<>();
    public static final Map<String, String> CURRENCIES = new LinkedHashMap<>();
    public static final Map<String, String> DUNGEONS = new LinkedHashMap<>();

    public static final List<NamedEntry> POPULAR_POSITIVE_QUIRKS = Arrays.asList(
            new NamedEntry("tough", "坚韧 (+10% 最大生命)", "正面"),
            new NamedEntry("hard_skinned", "硬皮 (+10% 防御)", "正面"),
            new NamedEntry("quick_reflexes", "敏锐反射 (+2 速度)", "正面"),
            new NamedEntry("quickdraw", "先发制人 (首回合+4速度)", "正面"),
            new NamedEntry("natural_swing", "天生挥击 (+5 精准)", "正面"),
            new NamedEntry("natural_eye", "天生锐眼 (+5 远程精准)", "正面"),
            new NamedEntry("evasive", "闪避 (+5 闪避)", "正面"),
            new NamedEntry("slugger", "重击手 (+10% 近战伤害)", "正面"),
            new NamedEntry("unerring", "百发百中 (+10% 远程伤害)", "正面"),
            new NamedEntry("deadly", "致命 (+2% 暴击)", "正面"),
            new NamedEntry("precision_striker", "精准打击 (+5% 近战暴击)", "正面"),
            new NamedEntry("eagle_eye", "鹰眼 (+5% 远程暴击)", "正面"),
            new NamedEntry("luminous", "闪耀 (+2 速度 / +5 闪避)", "正面"),
            new NamedEntry("prismatic_solidity", "棱光坚固 (+10% 全抗)", "正面"),
            new NamedEntry("prismatic_speed", "棱光疾速 (+3 速度)", "正面"),
            new NamedEntry("prismatic_precision", "棱光精准 (+4% 暴击)", "正面"),
            new NamedEntry("corvid_eye", "鸦眼 (+8 精准 / +8% 侦察)", "正面"),
            new NamedEntry("corvid_grace", "鸦姿 (+6 闪避 / +25% 位移抗)", "正面"),
            new NamedEntry("early_riser", "早起鸟 (高火把+2速度)", "正面"),
            new NamedEntry("photomania", "嗜光狂 (高火把-20%压力)", "正面"),
            new NamedEntry("unyielding", "不屈 (+10% 死门抗性)", "正面"),
            new NamedEntry("hippocratic", "希波克拉底 (+20% 治疗技能)", "正面")
    );

    public static final List<NamedEntry> DISEASES = Arrays.asList(
            new NamedEntry("the_fits", "癫痫", "疾病"),
            new NamedEntry("rabies", "狂犬病", "疾病"),
            new NamedEntry("hemophilia", "血友病", "疾病"),
            new NamedEntry("the_runs", "腹泻", "疾病"),
            new NamedEntry("wasting_sickness", "消耗症", "疾病"),
            new NamedEntry("tetanus", "破伤风", "疾病"),
            new NamedEntry("syphilis", "梅毒", "疾病"),
            new NamedEntry("bad_humours", "体液失调", "疾病"),
            new NamedEntry("creeping_cough", "百日咳", "疾病"),
            new NamedEntry("stomach_cramp", "胃痉挛", "疾病"),
            new NamedEntry("bulimic", "暴食症", "疾病"),
            new NamedEntry("lethargy", "嗜睡症", "疾病"),
            new NamedEntry("spotted_fever", "斑疹热", "疾病"),
            new NamedEntry("vertigo", "眩晕症", "疾病"),
            new NamedEntry("vampiric_spirits", "嗜血躁动", "疾病"),
            new NamedEntry("ennui", "倦怠症", "疾病"),
            new NamedEntry("the_ague", "疟疾", "疾病"),
            new NamedEntry("hysterical_blindness", "歇斯底里失明", "疾病"),
            new NamedEntry("scurvy", "坏血病", "疾病"),
            new NamedEntry("the_worries", "焦虑症", "疾病"),
            new NamedEntry("tapeworm", "绦虫病", "疾病"),
            new NamedEntry("the_red_plague", "猩红瘟疫", "疾病"),
            new NamedEntry("the_black_plague", "黑死病", "疾病")
    );

    public static final List<NamedEntry> CURATED_TRINKETS = Arrays.asList(
            new NamedEntry("ancestors_map", "祖先的地图", "先祖"),
            new NamedEntry("ancestors_candle", "祖先的蜡烛", "先祖"),
            new NamedEntry("ancestors_pen", "祖先的钢笔", "先祖"),
            new NamedEntry("ancestors_portrait", "祖先的肖像", "先祖"),
            new NamedEntry("ancestors_lantern", "祖先的提灯", "先祖"),
            new NamedEntry("ancestors_coat", "祖先的外套", "先祖"),
            new NamedEntry("ancestors_musket_ball", "祖先的火枪弹", "先祖"),
            new NamedEntry("ancestors_pistol", "祖先的手枪", "先祖"),
            new NamedEntry("ancestors_handkerchief", "祖先的手帕", "先祖"),
            new NamedEntry("ancestors_moustache_cream", "祖先的胡须膏", "先祖"),
            new NamedEntry("ancestors_tentacle_idol", "祖先的触手神像", "先祖"),
            new NamedEntry("ancestors_bottle", "祖先的酒瓶", "先祖"),
            new NamedEntry("ancestors_scroll", "祖先的卷轴", "先祖"),
            new NamedEntry("ancestors_signet_ring", "祖先的印戒", "先祖"),
            new NamedEntry("focus_ring", "专注戒指", "极稀有"),
            new NamedEntry("sun_ring", "太阳戒指", "稀有"),
            new NamedEntry("moon_ring", "月亮戒指", "稀有"),
            new NamedEntry("tough_ring", "坚韧戒指", "极稀有"),
            new NamedEntry("legendary_bracer", "传奇护腕", "极稀有"),
            new NamedEntry("cleansing_crystal", "净化水晶", "极稀有"),
            new NamedEntry("sacred_scroll", "神圣卷轴", "极稀有"),
            new NamedEntry("fortifying_garlic", "强化大蒜", "极稀有"),
            new NamedEntry("martyrs_seal", "殉道者印记", "极稀有"),
            new NamedEntry("feather_crystal", "羽饰水晶", "稀有"),
            new NamedEntry("berserk_charm", "狂暴护符", "稀有"),
            new NamedEntry("quick_draw_charm", "快拔护符", "稀有"),
            new NamedEntry("dismas_head", "迪斯马的头颅", "收藏家"),
            new NamedEntry("barristans_head", "巴里斯坦的头颅", "收藏家"),
            new NamedEntry("junias_head", "朱妮亚的头颅", "收藏家"),
            new NamedEntry("crescendo_box", "渐强八音盒", "狂人"),
            new NamedEntry("aria_box", "咏叹八音盒", "狂人"),
            new NamedEntry("overture_box", "序曲八音盒", "狂人"),
            new NamedEntry("crow_wingfeather", "乌鸦羽翼", "鸦翼"),
            new NamedEntry("crow_tailfeather", "乌鸦尾羽", "鸦翼"),
            new NamedEntry("crow_talon", "乌鸦利爪", "鸦翼"),
            new NamedEntry("crow_eye", "乌鸦之眼", "鸦翼"),
            new NamedEntry("tempting_goblet", "诱惑酒杯", "稀世"),
            new NamedEntry("dd_trinket", "火焰护符 (极暗地牢)", "极暗"),
            new NamedEntry("boss_necromancer", "死灵法师之领", "奖杯"),
            new NamedEntry("boss_hag", "巫婆的汤勺", "奖杯"),
            new NamedEntry("boss_wilbur", "威尔伯的旗帜", "奖杯"),
            new NamedEntry("boss_siren", "塞壬的螺壳", "奖杯"),
            new NamedEntry("boss_cannon", "火柴人的打火匣", "奖杯"),
            new NamedEntry("boss_prophet", "先知的眼珠", "奖杯"),
            new NamedEntry("boss_flesh", "肉块的心脏", "奖杯"),
            new NamedEntry("boss_crew", "船员的铃铛", "奖杯"),
            new NamedEntry("crystallized_talon", "晶化利爪 (星晶)", "疯狂"),
            new NamedEntry("lens_of_the_comet", "彗星透镜 (星晶)", "疯狂"),
            new NamedEntry("mirror_shield", "镜面盾牌 (星晶)", "疯狂"),
            new NamedEntry("crystalline_gunpowder", "晶化火药 (星晶)", "疯狂"),
            new NamedEntry("millers_pipe", "磨坊主的烟斗", "疯狂"),
            new NamedEntry("mildreds_locket", "米尔德丽德的吊坠", "疯狂")
    );

    public static final List<BuildingTree> BUILDING_TREES = Arrays.asList(
            new BuildingTree("abbey.meditation", "a", "b", "c", "d", "e", "f"),
            new BuildingTree("abbey.prayer", "a", "b", "c", "d", "e", "f"),
            new BuildingTree("abbey.flagellation", "a", "b", "c", "d", "e", "f"),
            new BuildingTree("blacksmith.weapon", "a", "b", "c", "d"),
            new BuildingTree("blacksmith.armour", "a", "b", "c", "d"),
            new BuildingTree("blacksmith.cost", "a", "b", "c", "d", "e"),
            new BuildingTree("camping_trainer.cost", "a", "b", "c", "d", "e"),
            new BuildingTree("guild.skill_levels", "a", "b", "c", "d"),
            new BuildingTree("guild.cost", "a", "b", "c", "d", "e"),
            new BuildingTree("nomad_wagon.numitems", "a", "b", "c", "d"),
            new BuildingTree("nomad_wagon.cost", "a", "b", "c", "d", "e"),
            new BuildingTree("sanitarium.cost", "a", "b", "c", "d", "e"),
            new BuildingTree("sanitarium.disease_quirk_cost", "a", "b", "c", "d", "e"),
            new BuildingTree("sanitarium.slots", "a", "b", "c", "d"),
            new BuildingTree("stage_coach.numrecruits", "a", "b", "c", "d", "e"),
            new BuildingTree("stage_coach.rostersize", "a", "b", "c", "d", "e"),
            new BuildingTree("stage_coach.upgraded_recruits", "a", "b", "c"),
            new BuildingTree("tavern.bar", "a", "b", "c", "d", "e", "f"),
            new BuildingTree("tavern.gambling", "a", "b", "c", "d", "e", "f"),
            new BuildingTree("tavern.brothel", "a", "b", "c", "d", "e", "f")
    );

    public static final int[] RESOLVE_THRESHOLDS = {0, 2, 8, 14, 24, 36, 48};

    static {
        HERO_CLASSES.put("crusader", "十字军");
        HERO_CLASSES.put("highwayman", "强盗");
        HERO_CLASSES.put("plague_doctor", "瘟疫医生");
        HERO_CLASSES.put("vestal", "修女");
        HERO_CLASSES.put("hellion", "恶女");
        HERO_CLASSES.put("bounty_hunter", "赏金猎人");
        HERO_CLASSES.put("leper", "麻风剑客");
        HERO_CLASSES.put("occultist", "神秘学者");
        HERO_CLASSES.put("grave_robber", "盗墓贼");
        HERO_CLASSES.put("jester", "小丑");
        HERO_CLASSES.put("man_at_arms", "老兵");
        HERO_CLASSES.put("houndmaster", "训犬师");
        HERO_CLASSES.put("arbalest", "弩手");
        HERO_CLASSES.put("abomination", "憎恶");
        HERO_CLASSES.put("antiquarian", "古董商人");
        HERO_CLASSES.put("flagellant", "苦修者");
        HERO_CLASSES.put("shieldbreaker", "破盾者");
        HERO_CLASSES.put("musketeer", "火枪手");
        HERO_CLASSES.put("runaway", "逃亡者");
        HERO_CLASSES.put("duelist", "决斗家");

        VIRTUES.put("", "无美德");
        VIRTUES.put("stalwart", "坚毅 (Stalwart)");
        VIRTUES.put("courageous", "无畏 (Courageous)");
        VIRTUES.put("focused", "专注 (Focused)");
        VIRTUES.put("powerful", "强力 (Powerful)");
        VIRTUES.put("vigorous", "振奋 (Vigorous)");

        AFFLICTIONS.put("", "无折磨");
        AFFLICTIONS.put("paranoid", "偏执");
        AFFLICTIONS.put("selfish", "自私");
        AFFLICTIONS.put("irrational", "狂乱");
        AFFLICTIONS.put("fearful", "恐惧");
        AFFLICTIONS.put("hopeless", "绝望");
        AFFLICTIONS.put("abusive", "暴躁");
        AFFLICTIONS.put("masochistic", "受虐");
        AFFLICTIONS.put("refractory", "折光 (星晶)");
        AFFLICTIONS.put("rapturous", "狂喜 (苦修)");

        CURRENCIES.put("gold", "金币 (Gold)");
        CURRENCIES.put("bust", "半身像 (Bust)");
        CURRENCIES.put("portrait", "肖像画 (Portrait)");
        CURRENCIES.put("deed", "地契 (Deed)");
        CURRENCIES.put("crest", "纹章 (Crest)");
        CURRENCIES.put("shard", "彗星碎片 (Shard)");
        CURRENCIES.put("blueprint", "建筑蓝图 (Blueprint)");

        DUNGEONS.put("crypts", "遗迹 (Ruins)");
        DUNGEONS.put("weald", "荒野 (Weald)");
        DUNGEONS.put("warrens", "战壕 (Warrens)");
        DUNGEONS.put("cove", "海湾 (Cove)");
        DUNGEONS.put("courtyard", "庭院 (Courtyard)");
        DUNGEONS.put("farmstead", "农庄 (Farmstead)");
    }

    private CatalogData() {}

    public static String heroClassLabel(String cls) {
        return HERO_CLASSES.getOrDefault(cls, cls);
    }

    public static int resolveLevelForXp(int xp) {
        int lv = 0;
        for (int i = 0; i < RESOLVE_THRESHOLDS.length; i++) {
            if (xp >= RESOLVE_THRESHOLDS[i]) lv = i;
        }
        return lv;
    }

    public static String trinketLabel(String id) {
        for (NamedEntry entry : CURATED_TRINKETS) {
            if (entry.id.equals(id)) return entry.label;
        }
        return id;
    }

    public static String trinketCategory(String id) {
        for (NamedEntry entry : CURATED_TRINKETS) {
            if (entry.id.equals(id)) return entry.category;
        }
        return "自定义";
    }

    public static int dungeonLevelForXp(int xp) {
        int[] thresholds = {0, 4, 12, 24, 42, 66, 96, 132};
        int lv = 0;
        for (int i = 0; i < thresholds.length; i++) {
            if (xp >= thresholds[i]) lv = i;
        }
        return lv;
    }

    public static boolean isDisease(String quirkId) {
        for (NamedEntry d : DISEASES) {
            if (d.id.equals(quirkId)) return true;
        }
        return false;
    }

    public static String quirkLabel(String quirkId) {
        for (NamedEntry q : POPULAR_POSITIVE_QUIRKS) {
            if (q.id.equals(quirkId)) return q.label;
        }
        for (NamedEntry d : DISEASES) {
            if (d.id.equals(quirkId)) return "[病] " + d.label;
        }
        return quirkId;
    }

    public static List<NamedEntry> positiveQuirks() {
        return Collections.unmodifiableList(POPULAR_POSITIVE_QUIRKS);
    }
}
