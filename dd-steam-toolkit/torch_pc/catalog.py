"""Curated Chinese catalogs ported from APK CatalogData.java + dynamic Steam base & DLC indexing."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Dict, List, Optional, Tuple

RESOLVE_THRESHOLDS = [0, 2, 8, 14, 24, 36, 48]
DUNGEON_THRESHOLDS = [0, 4, 12, 24, 42, 66, 96, 132]

HERO_CLASSES: Dict[str, str] = {
    "crusader": "十字军",
    "highwayman": "强盗",
    "plague_doctor": "瘟疫医生",
    "vestal": "修女",
    "hellion": "恶女",
    "bounty_hunter": "赏金猎人",
    "leper": "麻风剑客",
    "occultist": "神秘学者",
    "grave_robber": "盗墓贼",
    "jester": "小丑",
    "man_at_arms": "老兵",
    "houndmaster": "训犬师",
    "arbalest": "弩手",
    "abomination": "憎恶",
    "antiquarian": "古董商人",
    "flagellant": "苦修者",
    "shieldbreaker": "破盾者",
    "musketeer": "火枪手",
    "runaway": "逃亡者",
    "duelist": "决斗家",
}

VIRTUES: List[Dict[str, str]] = [
    {"id": "", "label": "无美德"},
    {"id": "stalwart", "label": "坚毅 (Stalwart)"},
    {"id": "courageous", "label": "无畏 (Courageous)"},
    {"id": "focused", "label": "专注 (Focused)"},
    {"id": "powerful", "label": "强力 (Powerful)"},
    {"id": "vigorous", "label": "振奋 (Vigorous)"},
]

AFFLICTIONS: List[Dict[str, str]] = [
    {"id": "", "label": "无折磨"},
    {"id": "paranoid", "label": "偏执"},
    {"id": "selfish", "label": "自私"},
    {"id": "irrational", "label": "狂乱"},
    {"id": "fearful", "label": "恐惧"},
    {"id": "hopeless", "label": "绝望"},
    {"id": "abusive", "label": "暴躁"},
    {"id": "masochistic", "label": "受虐"},
    {"id": "refractory", "label": "折光 (星晶)"},
    {"id": "rapturous", "label": "狂喜 (苦修)"},
]

CURRENCIES: List[Dict[str, str]] = [
    {"id": "gold", "label": "金币 (Gold)"},
    {"id": "bust", "label": "半身像 (Bust)"},
    {"id": "portrait", "label": "肖像画 (Portrait)"},
    {"id": "deed", "label": "地契 (Deed)"},
    {"id": "crest", "label": "纹章 (Crest)"},
    {"id": "shard", "label": "彗星碎片 (Shard)"},
    {"id": "blueprint", "label": "建筑蓝图 (Blueprint)"},
]

DUNGEONS: List[Dict[str, str]] = [
    {"id": "crypts", "label": "遗迹 (Ruins)"},
    {"id": "weald", "label": "荒野 (Weald)"},
    {"id": "warrens", "label": "战壕 (Warrens)"},
    {"id": "cove", "label": "海湾 (Cove)"},
    {"id": "courtyard", "label": "庭院 (Courtyard)"},
    {"id": "farmstead", "label": "农庄 (Farmstead)"},
]

POPULAR_POSITIVE_QUIRKS: List[Dict[str, str]] = [
    {"id": "tough", "label": "坚韧 (+10% 最大生命)", "category": "正面"},
    {"id": "hard_skinned", "label": "硬皮 (+10% 防御)", "category": "正面"},
    {"id": "quick_reflexes", "label": "敏锐反射 (+2 速度)", "category": "正面"},
    {"id": "quickdraw", "label": "先发制人 (首回合+4速度)", "category": "正面"},
    {"id": "natural_swing", "label": "天生挥击 (+5 精准)", "category": "正面"},
    {"id": "natural_eye", "label": "天生锐眼 (+5 远程精准)", "category": "正面"},
    {"id": "evasive", "label": "闪避 (+5 闪避)", "category": "正面"},
    {"id": "slugger", "label": "重击手 (+10% 近战伤害)", "category": "正面"},
    {"id": "unerring", "label": "百发百中 (+10% 远程伤害)", "category": "正面"},
    {"id": "deadly", "label": "致命 (+2% 暴击)", "category": "正面"},
    {"id": "precision_striker", "label": "精准打击 (+5% 近战暴击)", "category": "正面"},
    {"id": "eagle_eye", "label": "鹰眼 (+5% 远程暴击)", "category": "正面"},
    {"id": "luminous", "label": "闪耀 (+2 速度 / +5 闪避)", "category": "正面"},
    {"id": "prismatic_solidity", "label": "棱光坚固 (+10% 全抗)", "category": "正面"},
    {"id": "prismatic_speed", "label": "棱光疾速 (+3 速度)", "category": "正面"},
    {"id": "prismatic_precision", "label": "棱光精准 (+4% 暴击)", "category": "正面"},
    {"id": "corvid_eye", "label": "鸦眼 (+8 精准 / +8% 侦察)", "category": "正面"},
    {"id": "corvid_grace", "label": "鸦姿 (+6 闪避 / +25% 位移抗)", "category": "正面"},
    {"id": "early_riser", "label": "早起鸟 (高火把+2速度)", "category": "正面"},
    {"id": "photomania", "label": "嗜光狂 (高火把-20%压力)", "category": "正面"},
    {"id": "unyielding", "label": "不屈 (+10% 死门抗性)", "category": "正面"},
    {"id": "hippocratic", "label": "希波克拉底 (+20% 治疗技能)", "category": "正面"},
]

DISEASES: List[Dict[str, str]] = [
    {"id": "the_fits", "label": "癫痫", "category": "疾病"},
    {"id": "rabies", "label": "狂犬病", "category": "疾病"},
    {"id": "hemophilia", "label": "血友病", "category": "疾病"},
    {"id": "the_runs", "label": "腹泻", "category": "疾病"},
    {"id": "wasting_sickness", "label": "消耗症", "category": "疾病"},
    {"id": "tetanus", "label": "破伤风", "category": "疾病"},
    {"id": "syphilis", "label": "梅毒", "category": "疾病"},
    {"id": "bad_humours", "label": "体液失调", "category": "疾病"},
    {"id": "creeping_cough", "label": "百日咳", "category": "疾病"},
    {"id": "stomach_cramp", "label": "胃痉挛", "category": "疾病"},
    {"id": "bulimic", "label": "暴食症", "category": "疾病"},
    {"id": "lethargy", "label": "嗜睡症", "category": "疾病"},
    {"id": "spotted_fever", "label": "斑疹热", "category": "疾病"},
    {"id": "vertigo", "label": "眩晕症", "category": "疾病"},
    {"id": "vampiric_spirits", "label": "嗜血躁动", "category": "疾病"},
    {"id": "ennui", "label": "倦怠症", "category": "疾病"},
    {"id": "the_ague", "label": "疟疾", "category": "疾病"},
    {"id": "hysterical_blindness", "label": "歇斯底里失明", "category": "疾病"},
    {"id": "scurvy", "label": "坏血病", "category": "疾病"},
    {"id": "the_worries", "label": "焦虑症", "category": "疾病"},
    {"id": "tapeworm", "label": "绦虫病", "category": "疾病"},
    {"id": "the_red_plague", "label": "猩红瘟疫", "category": "疾病"},
    {"id": "the_black_plague", "label": "黑死病", "category": "疾病"},
]

DISEASE_IDS = {d["id"] for d in DISEASES}

CURATED_TRINKETS: List[Dict[str, str]] = [
    {"id": "ancestors_map", "label": "祖先的地图", "category": "先祖"},
    {"id": "ancestors_candle", "label": "祖先的蜡烛", "category": "先祖"},
    {"id": "ancestors_pen", "label": "祖先的钢笔", "category": "先祖"},
    {"id": "ancestors_portrait", "label": "祖先的肖像", "category": "先祖"},
    {"id": "ancestors_lantern", "label": "祖先的提灯", "category": "先祖"},
    {"id": "ancestors_coat", "label": "祖先的外套", "category": "先祖"},
    {"id": "ancestors_musket_ball", "label": "祖先的火枪弹", "category": "先祖"},
    {"id": "ancestors_pistol", "label": "祖先的手枪", "category": "先祖"},
    {"id": "ancestors_handkerchief", "label": "祖先的手帕", "category": "先祖"},
    {"id": "ancestors_moustache_cream", "label": "祖先的胡须膏", "category": "先祖"},
    {"id": "ancestors_tentacle_idol", "label": "祖先的触手神像", "category": "先祖"},
    {"id": "ancestors_bottle", "label": "祖先的酒瓶", "category": "先祖"},
    {"id": "ancestors_scroll", "label": "祖先的卷轴", "category": "先祖"},
    {"id": "ancestors_signet_ring", "label": "祖先的印戒", "category": "先祖"},
    {"id": "focus_ring", "label": "专注戒指", "category": "极稀有"},
    {"id": "sun_ring", "label": "太阳戒指", "category": "稀有"},
    {"id": "moon_ring", "label": "月亮戒指", "category": "稀有"},
    {"id": "tough_ring", "label": "坚韧戒指", "category": "极稀有"},
    {"id": "legendary_bracer", "label": "传奇护腕", "category": "极稀有"},
    {"id": "cleansing_crystal", "label": "净化水晶", "category": "极稀有"},
    {"id": "sacred_scroll", "label": "神圣卷轴", "category": "极稀有"},
    {"id": "fortifying_garlic", "label": "强化大蒜", "category": "极稀有"},
    {"id": "martyrs_seal", "label": "殉道者印记", "category": "极稀有"},
    {"id": "feather_crystal", "label": "羽饰水晶", "category": "稀有"},
    {"id": "berserk_charm", "label": "狂暴护符", "category": "稀有"},
    {"id": "quick_draw_charm", "label": "快拔护符", "category": "稀有"},
    {"id": "dismas_head", "label": "迪斯马的头颅", "category": "收藏家"},
    {"id": "barristans_head", "label": "巴里斯坦的头颅", "category": "收藏家"},
    {"id": "junias_head", "label": "朱妮亚的头颅", "category": "收藏家"},
    {"id": "crescendo_box", "label": "渐强八音盒", "category": "狂人"},
    {"id": "aria_box", "label": "咏叹八音盒", "category": "狂人"},
    {"id": "overture_box", "label": "序曲八音盒", "category": "狂人"},
    {"id": "crow_wingfeather", "label": "乌鸦羽翼", "category": "鸦翼"},
    {"id": "crow_tailfeather", "label": "乌鸦尾羽", "category": "鸦翼"},
    {"id": "crow_talon", "label": "乌鸦利爪", "category": "鸦翼"},
    {"id": "crow_eye", "label": "乌鸦之眼", "category": "鸦翼"},
    {"id": "tempting_goblet", "label": "诱惑酒杯", "category": "稀世"},
    {"id": "dd_trinket", "label": "火焰护符 (极暗地牢)", "category": "极暗"},
    {"id": "boss_necromancer", "label": "死灵法师之领", "category": "奖杯"},
    {"id": "boss_hag", "label": "巫婆的汤勺", "category": "奖杯"},
    {"id": "boss_wilbur", "label": "威尔伯的旗帜", "category": "奖杯"},
    {"id": "boss_siren", "label": "塞壬的螺壳", "category": "奖杯"},
    {"id": "boss_cannon", "label": "火柴人的打火匣", "category": "奖杯"},
    {"id": "boss_prophet", "label": "先知的眼珠", "category": "奖杯"},
    {"id": "boss_flesh", "label": "肉块的心脏", "category": "奖杯"},
    {"id": "boss_crew", "label": "船员的铃铛", "category": "奖杯"},
    {"id": "crystallized_talon", "label": "晶化利爪 (星晶)", "category": "疯狂"},
    {"id": "lens_of_the_comet", "label": "彗星透镜 (星晶)", "category": "疯狂"},
    {"id": "mirror_shield", "label": "镜面盾牌 (星晶)", "category": "疯狂"},
    {"id": "crystalline_gunpowder", "label": "晶化火药 (星晶)", "category": "疯狂"},
    {"id": "millers_pipe", "label": "磨坊主的烟斗", "category": "疯狂"},
    {"id": "mildreds_locket", "label": "米尔德丽德的吊坠", "category": "疯狂"},
]

BUILDING_TREES: List[Tuple[str, List[str]]] = [
    ("abbey.meditation", ["a", "b", "c", "d", "e", "f"]),
    ("abbey.prayer", ["a", "b", "c", "d", "e", "f"]),
    ("abbey.flagellation", ["a", "b", "c", "d", "e", "f"]),
    ("blacksmith.weapon", ["a", "b", "c", "d"]),
    ("blacksmith.armour", ["a", "b", "c", "d"]),
    ("blacksmith.cost", ["a", "b", "c", "d", "e"]),
    ("camping_trainer.cost", ["a", "b", "c", "d", "e"]),
    ("guild.skill_levels", ["a", "b", "c", "d"]),
    ("guild.cost", ["a", "b", "c", "d", "e"]),
    ("nomad_wagon.numitems", ["a", "b", "c", "d"]),
    ("nomad_wagon.cost", ["a", "b", "c", "d", "e"]),
    ("sanitarium.cost", ["a", "b", "c", "d", "e"]),
    ("sanitarium.disease_quirk_cost", ["a", "b", "c", "d", "e"]),
    ("sanitarium.slots", ["a", "b", "c", "d"]),
    ("stage_coach.numrecruits", ["a", "b", "c", "d", "e"]),
    ("stage_coach.rostersize", ["a", "b", "c", "d", "e"]),
    ("stage_coach.upgraded_recruits", ["a", "b", "c"]),
    ("tavern.bar", ["a", "b", "c", "d", "e", "f"]),
    ("tavern.gambling", ["a", "b", "c", "d", "e", "f"]),
    ("tavern.brothel", ["a", "b", "c", "d", "e", "f"]),
]

BUILDING_GROUPS = [
    {"prefix": "blacksmith.", "label": "铁匠铺"},
    {"prefix": "guild.", "label": "公会"},
    {"prefix": "stage_coach.", "label": "马车"},
    {"prefix": "sanitarium.", "label": "疗养院"},
    {"prefix": "tavern.", "label": "酒馆"},
    {"prefix": "abbey.", "label": "修道院"},
    {"prefix": "nomad_wagon.", "label": "篷车"},
    {"prefix": "camping_trainer.", "label": "露营导师"},
]

RARITY_CATEGORY_MAP = {
    "ancestral": "先祖",
    "ancestral_shambler": "先祖",
    "very_rare": "极稀有",
    "rare": "稀有",
    "uncommon": "罕见",
    "common": "普通",
    "very_common": "常见",
    "trophy": "奖杯",
    "darkest_dungeon": "极暗",
    "collector": "收藏家",
    "madman": "狂人",
    "crow": "鸦翼",
    "crimson_court": "庭院",
    "comet": "疯狂",
    "mildred": "疯狂",
    "kickstarter": "典藏",
}

_TRINKET_BY_ID = {t["id"]: t for t in CURATED_TRINKETS}
_QUIRK_BY_ID = {q["id"]: q for q in POPULAR_POSITIVE_QUIRKS}
_DISEASE_BY_ID = {d["id"]: d for d in DISEASES}


def hero_class_label(cls: str) -> str:
    return HERO_CLASSES.get(cls, cls)


def resolve_level_for_xp(xp: int) -> int:
    lv = 0
    for i, threshold in enumerate(RESOLVE_THRESHOLDS):
        if xp >= threshold:
            lv = i
    return lv


def dungeon_level_for_xp(xp: int) -> int:
    lv = 0
    for i, threshold in enumerate(DUNGEON_THRESHOLDS):
        if xp >= threshold:
            lv = i
    return lv


def is_disease(quirk_id: str, extra_diseases: Optional[set[str]] = None) -> bool:
    if quirk_id in DISEASE_IDS:
        return True
    return bool(extra_diseases and quirk_id in extra_diseases)


def quirk_label(quirk_id: str) -> str:
    if quirk_id in _QUIRK_BY_ID:
        return _QUIRK_BY_ID[quirk_id]["label"]
    if quirk_id in _DISEASE_BY_ID:
        return "[病] " + _DISEASE_BY_ID[quirk_id]["label"]
    return quirk_id


def trinket_label(trinket_id: str) -> str:
    entry = _TRINKET_BY_ID.get(trinket_id)
    return entry["label"] if entry else trinket_id


def trinket_category(trinket_id: str) -> str:
    entry = _TRINKET_BY_ID.get(trinket_id)
    return entry["category"] if entry else "Steam/自定义"


def build_catalog_payload(steam_game_dir: Optional[Path] = None) -> Dict[str, object]:
    """Return curated Chinese catalog merged with all trinkets & quirks discovered in Steam game_dir."""
    trinkets: List[Dict[str, str]] = list(CURATED_TRINKETS)
    seen_t = {t["id"] for t in trinkets}

    pos_quirks: List[Dict[str, str]] = list(POPULAR_POSITIVE_QUIRKS)
    seen_q = {q["id"] for q in pos_quirks}

    diseases: List[Dict[str, str]] = list(DISEASES)
    seen_d = {d["id"] for d in diseases}

    if steam_game_dir and steam_game_dir.is_dir():
        trinket_files: List[Path] = []
        base_t = steam_game_dir / "trinkets" / "base.entries.trinkets.json"
        if base_t.is_file():
            trinket_files.append(base_t)
        dlc_dir = steam_game_dir / "dlc"
        if dlc_dir.is_dir():
            trinket_files.extend(sorted(dlc_dir.rglob("*.entries.trinkets.json")))

        for tf in trinket_files:
            try:
                data = json.loads(tf.read_text(encoding="utf-8", errors="replace"))
                for entry in data.get("entries", []):
                    tid = entry.get("id")
                    if not tid or tid in seen_t:
                        continue
                    seen_t.add(tid)
                    rarity = str(entry.get("rarity", "")).lower()
                    cat = RARITY_CATEGORY_MAP.get(rarity, "Steam图鉴")
                    trinkets.append({"id": tid, "label": tid.replace("_", " "), "category": cat})
            except Exception:
                continue

        ql_files: List[Path] = []
        base_q = steam_game_dir / "shared" / "quirk" / "quirk_library.json"
        if base_q.is_file():
            ql_files.append(base_q)
        if dlc_dir.is_dir():
            ql_files.extend(sorted(dlc_dir.rglob("quirk_library.json")))

        for qf in ql_files:
            try:
                data = json.loads(qf.read_text(encoding="utf-8", errors="replace"))
                for q in data.get("quirks", []):
                    qid = q.get("id")
                    if not qid:
                        continue
                    if q.get("is_disease"):
                        if qid not in seen_d:
                            seen_d.add(qid)
                            diseases.append({"id": qid, "label": qid.replace("_", " "), "category": "疾病"})
                    elif q.get("is_positive"):
                        if qid not in seen_q:
                            seen_q.add(qid)
                            pos_quirks.append({"id": qid, "label": qid.replace("_", " "), "category": "Steam正面"})
            except Exception:
                continue

    return {
        "hero_classes": HERO_CLASSES,
        "virtues": VIRTUES,
        "afflictions": AFFLICTIONS,
        "currencies": CURRENCIES,
        "dungeons": DUNGEONS,
        "resolve_thresholds": RESOLVE_THRESHOLDS,
        "positive_quirks": pos_quirks,
        "diseases": diseases,
        "trinkets": trinkets,
        "building_groups": BUILDING_GROUPS,
    }
