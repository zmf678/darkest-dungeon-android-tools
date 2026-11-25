package dev.torcharchive.game;

import android.content.Context;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Complete lossless binary DSON save editor covering estate wallet, heroes (nested DSON),
 * trinket inventory, town building upgrades, dungeon progression, and raw DSON tree editing.
 */
public final class SaveEditor {
    public static final class SlotSummary {
        public final String slot;
        public final String estateName;
        public final String gameMode;
        public final boolean inRaid;
        public final String dateTime;
        public final int gold;
        public final int heroCount;
        public final int trinketCount;

        public SlotSummary(String slot, String estateName, String gameMode, boolean inRaid,
                           String dateTime, int gold, int heroCount, int trinketCount) {
            this.slot = slot;
            this.estateName = estateName;
            this.gameMode = gameMode;
            this.inRaid = inRaid;
            this.dateTime = dateTime;
            this.gold = gold;
            this.heroCount = heroCount;
            this.trinketCount = trinketCount;
        }
    }

    public static final class EstateState {
        public final String slot;
        public final String estateName;
        public final String gameMode;
        public final Map<String, Integer> wallet;
        public final int endlessHighscore;

        public EstateState(String slot, String estateName, String gameMode,
                           Map<String, Integer> wallet, int endlessHighscore) {
            this.slot = slot;
            this.estateName = estateName;
            this.gameMode = gameMode;
            this.wallet = wallet;
            this.endlessHighscore = endlessHighscore;
        }
    }

    public static final class QuirkEntry {
        public final String id;
        public final boolean locked;
        public final boolean isNew;

        public QuirkEntry(String id, boolean locked, boolean isNew) {
            this.id = id;
            this.locked = locked;
            this.isNew = isNew;
        }
    }

    public static final class HeroState {
        public final String guid;
        public final String name;
        public final String heroClass;
        public final int status; // 0=idle, 1=in_raid, 2=activity, 3=dead
        public final float hpFraction;
        public final float stress;
        public final int resolveXp;
        public final int weaponRank;
        public final int armourRank;
        public final String affliction;
        public final String virtue;
        public final List<QuirkEntry> quirks;

        public HeroState(String guid, String name, String heroClass, int status, float hpFraction,
                         float stress, int resolveXp, int weaponRank, int armourRank,
                         String affliction, String virtue, List<QuirkEntry> quirks) {
            this.guid = guid;
            this.name = name;
            this.heroClass = heroClass;
            this.status = status;
            this.hpFraction = hpFraction;
            this.stress = stress;
            this.resolveXp = resolveXp;
            this.weaponRank = weaponRank;
            this.armourRank = armourRank;
            this.affliction = affliction;
            this.virtue = virtue;
            this.quirks = quirks;
        }

        public String statusLabel() {
            return switch (status) {
                case 0 -> "空闲";
                case 1 -> "地牢探险中";
                case 2 -> "城镇设施中";
                case 3 -> "已阵亡";
                default -> "状态" + status;
            };
        }
    }

    public static final class TrinketEntry {
        public final int index;
        public final String id;
        public final int amount;

        public TrinketEntry(int index, String id, int amount) {
            this.index = index;
            this.id = id;
            this.amount = amount;
        }
    }

    public static final class RawEntry {
        public final int index;
        public final String key;
        public final boolean object;
        public final int childCount;
        public final String typeTag; // "obj", "bool", "int", "float", "string", "dson", "bytes"
        public final String displayValue;
        public final boolean editable;

        public RawEntry(int index, String key, boolean object, int childCount,
                        String typeTag, String displayValue, boolean editable) {
            this.index = index;
            this.key = key;
            this.object = object;
            this.childCount = childCount;
            this.typeTag = typeTag;
            this.displayValue = displayValue;
            this.editable = editable;
        }
    }

    private SaveEditor() {}

    // ---- Android Context-aware entrypoints (stop :nx + forced SnapshotStore.backup + atomic write) ----

    public static List<SlotSummary> listSlots(Context context) throws IOException {
        return listSlots(GameArchive.savesDir(context));
    }

    public static EstateState readEstate(Context context, String slot) throws IOException {
        return readEstate(profileDir(context, slot));
    }

    public static void writeEstate(Context context, String slot, String estateName, String gameMode,
                                   Map<String, Integer> walletUpdates, Integer endlessHighscore) throws IOException {
        runProtectedWrite(context, slot, profile ->
                writeEstate(profile, estateName, gameMode, walletUpdates, endlessHighscore));
    }

    public static List<HeroState> listHeroes(Context context, String slot) throws IOException {
        return listHeroes(profileDir(context, slot));
    }

    public static void updateHero(Context context, String slot, String guid, String newName,
                                  Float hpFraction, Float stress, Integer resolveXp,
                                  Integer weaponRank, Integer armourRank,
                                  String affliction, String virtue, boolean revive) throws IOException {
        runProtectedWrite(context, slot, profile ->
                updateHero(profile, guid, newName, hpFraction, stress, resolveXp,
                        weaponRank, armourRank, affliction, virtue, revive));
    }

    public static void updateHeroQuirks(Context context, String slot, String guid,
                                        List<QuirkEntry> quirks) throws IOException {
        runProtectedWrite(context, slot, profile -> updateHeroQuirks(profile, guid, quirks));
    }

    public static void batchImproveAllHeroes(Context context, String slot, boolean maxLevelAndGear,
                                             boolean healAndPurify) throws IOException {
        runProtectedWrite(context, slot, profile ->
                batchImproveAllHeroes(profile, maxLevelAndGear, healAndPurify));
    }

    public static List<TrinketEntry> listTrinkets(Context context, String slot) throws IOException {
        return listTrinkets(profileDir(context, slot));
    }

    public static void addTrinket(Context context, String slot, String trinketId, int amount) throws IOException {
        runProtectedWrite(context, slot, profile -> addTrinket(profile, trinketId, amount));
    }

    public static void removeTrinket(Context context, String slot, int index) throws IOException {
        runProtectedWrite(context, slot, profile -> removeTrinket(profile, index));
    }

    public static int unlockAllBuildingUpgrades(Context context, String slot) throws IOException {
        final int[] added = {0};
        runProtectedWrite(context, slot, profile -> added[0] = unlockAllBuildingUpgrades(profile));
        return added[0];
    }

    public static Map<String, Integer> readDungeonXp(Context context, String slot) throws IOException {
        return readDungeonXp(profileDir(context, slot));
    }

    public static void writeDungeonXp(Context context, String slot, Map<String, Integer> dungeonXp) throws IOException {
        runProtectedWrite(context, slot, profile -> writeDungeonXp(profile, dungeonXp));
    }

    public static List<String> listDsonFiles(Context context, String slot) throws IOException {
        return listDsonFiles(profileDir(context, slot));
    }

    public static List<RawEntry> inspectRawPath(Context context, String slot, String fileName,
                                                List<String> path) throws IOException {
        return inspectRawPath(profileDir(context, slot), fileName, path);
    }

    public static void editRawScalar(Context context, String slot, String fileName, List<String> path,
                                     String valueType, String newValue) throws IOException {
        runProtectedWrite(context, slot, profile -> editRawScalar(profile, fileName, path, valueType, newValue));
    }

    @FunctionalInterface
    private interface ProfileMutator {
        void mutate(Path profile) throws IOException;
    }

    private static void runProtectedWrite(Context context, String slot, ProfileMutator mutator) throws IOException {
        if (slot == null || !slot.matches("profile_[0-8]")) {
            throw new IOException("无效档位: " + slot);
        }
        NxProcessControl.stopGameProcess(context);
        Path saves = GameArchive.savesDir(context);
        Path profile = saves.resolve(slot);
        if (!SnapshotStore.validProfile(profile)) {
            throw new IOException("该档位不存在有效存档");
        }
        synchronized (GameArchive.class) {
            SnapshotStore store = GameArchive.getStore(context);
            store.backup(profile);
            mutator.mutate(profile);
            store.baseline(saves);
        }
        context.getContentResolver().notifyChange(GameArchive.VERSIONS, null);
    }

    private static Path profileDir(Context context, String slot) throws IOException {
        if (slot == null || !slot.matches("profile_[0-8]")) throw new IOException("无效档位: " + slot);
        Path dir = GameArchive.savesDir(context).resolve(slot);
        if (!SnapshotStore.validProfile(dir)) throw new IOException("该档位不存在有效存档");
        return dir;
    }

    // ---- Pure Path-based engine methods (directly testable on host JVM) ----

    public static List<SlotSummary> listSlots(Path savesRoot) throws IOException {
        List<SlotSummary> list = new ArrayList<>();
        for (int i = 0; i <= 8; i++) {
            String slot = "profile_" + i;
            Path profile = savesRoot.resolve(slot);
            if (!SnapshotStore.validProfile(profile)) continue;
            DsonCodec.Document game = readDoc(profile.resolve("persist.game.json"));
            String estateName = strChild(game.root, "estatename", "未命名庄园");
            String mode = strChild(game.root, "game_mode", "darkest");
            boolean inRaid = boolChild(game.root, "inraid", false);
            String dt = strChild(game.root, "date_time", "");

            int gold = 0;
            int trinketCount = 0;
            Path estateFile = profile.resolve("persist.estate.json");
            if (Files.isRegularFile(estateFile)) {
                DsonCodec.Document est = readDoc(estateFile);
                gold = readWallet(est.root).getOrDefault("gold", 0);
                DsonCodec.Node trinkets = est.root.child("trinkets");
                DsonCodec.Node items = trinkets == null ? null : trinkets.child("items");
                if (items != null && items.object) trinketCount = items.children.size();
            }

            int heroCount = 0;
            Path rosterFile = profile.resolve("persist.roster.json");
            if (Files.isRegularFile(rosterFile)) {
                DsonCodec.Document roster = readDoc(rosterFile);
                DsonCodec.Node heroes = roster.root.child("heroes");
                if (heroes != null && heroes.object) heroCount = heroes.children.size();
            }
            list.add(new SlotSummary(slot, estateName, mode, inRaid, dt, gold, heroCount, trinketCount));
        }
        return list;
    }

    public static EstateState readEstate(Path profile) throws IOException {
        DsonCodec.Document game = readDoc(profile.resolve("persist.game.json"));
        DsonCodec.Document estate = readDoc(profile.resolve("persist.estate.json"));
        Map<String, Integer> wallet = readWallet(estate.root);
        for (String key : CatalogData.CURRENCIES.keySet()) {
            wallet.putIfAbsent(key, 0);
        }
        DsonCodec.Node hs = estate.root.child("endless_wave_highscore");
        return new EstateState(
                profile.getFileName().toString(),
                strChild(game.root, "estatename", ""),
                strChild(game.root, "game_mode", "darkest"),
                wallet,
                hs == null ? 0 : hs.asInt()
        );
    }

    public static void writeEstate(Path profile, String estateName, String gameMode,
                                   Map<String, Integer> walletUpdates, Integer endlessHighscore) throws IOException {
        if (estateName != null || gameMode != null) {
            Path gameFile = profile.resolve("persist.game.json");
            DsonCodec.Document game = readDoc(gameFile);
            if (estateName != null && !estateName.trim().isEmpty()) {
                game.root.getOrCreateString("estatename", estateName.trim()).setString(estateName.trim());
            }
            if (gameMode != null && !gameMode.trim().isEmpty()) {
                game.root.getOrCreateString("game_mode", gameMode.trim()).setString(gameMode.trim());
            }
            writeDocVerified(gameFile, game);
        }

        Path estateFile = profile.resolve("persist.estate.json");
        DsonCodec.Document estate = readDoc(estateFile);
        if (walletUpdates != null && !walletUpdates.isEmpty()) {
            DsonCodec.Node walletNode = estate.root.ensureObject("wallet");
            Map<String, DsonCodec.Node> byType = new LinkedHashMap<>();
            for (DsonCodec.Node entry : walletNode.children) {
                if (!entry.object) continue;
                DsonCodec.Node t = entry.child("type");
                if (t != null) byType.put(t.asString(), entry);
            }
            for (Map.Entry<String, Integer> upd : walletUpdates.entrySet()) {
                String type = upd.getKey();
                int amt = Math.max(0, upd.getValue());
                DsonCodec.Node existing = byType.get(type);
                if (existing != null) {
                    existing.getOrCreateInt("amount", amt).setInt(amt);
                } else if (amt > 0) {
                    String nextIdx = Integer.toString(walletNode.children.size());
                    List<DsonCodec.Node> fields = new ArrayList<>(2);
                    fields.add(DsonCodec.intNode("amount", amt));
                    fields.add(DsonCodec.stringNode("type", type));
                    DsonCodec.Node created = new DsonCodec.Node(nextIdx, 0, fields);
                    walletNode.children.add(created);
                    byType.put(type, created);
                }
            }
        }
        if (endlessHighscore != null) {
            estate.root.getOrCreateInt("endless_wave_highscore", Math.max(0, endlessHighscore))
                    .setInt(Math.max(0, endlessHighscore));
        }
        writeDocVerified(estateFile, estate);
    }

    public static List<HeroState> listHeroes(Path profile) throws IOException {
        DsonCodec.Document roster = readDoc(profile.resolve("persist.roster.json"));
        DsonCodec.Node heroes = roster.root.child("heroes");
        List<HeroState> out = new ArrayList<>();
        if (heroes == null || !heroes.object) return out;
        for (DsonCodec.Node wrapper : heroes.children) {
            DsonCodec.Node rawNode = findHeroRawNode(wrapper);
            if (rawNode == null) continue;
            byte[] innerBytes = rawNode.asEmbeddedDson();
            if (innerBytes == null) continue;
            DsonCodec.Document heroDoc = DsonCodec.parse(innerBytes);
            out.add(readHeroState(wrapper.name, heroDoc.root));
        }
        out.sort(Comparator.comparingInt(h -> {
            try { return Integer.parseInt(h.guid); } catch (NumberFormatException e) { return 999999; }
        }));
        return out;
    }

    public static void updateHero(Path profile, String guid, String newName,
                                  Float hpFraction, Float stress, Integer resolveXp,
                                  Integer weaponRank, Integer armourRank,
                                  String affliction, String virtue, boolean revive) throws IOException {
        Path rosterFile = profile.resolve("persist.roster.json");
        DsonCodec.Document roster = readDoc(rosterFile);
        DsonCodec.Node wrapper = findHeroWrapper(roster.root, guid);
        DsonCodec.Node rawNode = findHeroRawNode(wrapper);
        if (rawNode == null) throw new IOException("找不到英雄数据: " + guid);
        DsonCodec.Document heroDoc = DsonCodec.parse(rawNode.asEmbeddedDson());
        applyHeroEdits(heroDoc.root, newName, hpFraction, stress, resolveXp,
                weaponRank, armourRank, affliction, virtue, revive);
        rawNode.setEmbeddedDson(DsonCodec.serialize(heroDoc));
        writeDocVerified(rosterFile, roster);
    }

    public static void updateHeroQuirks(Path profile, String guid, List<QuirkEntry> quirks) throws IOException {
        Path rosterFile = profile.resolve("persist.roster.json");
        DsonCodec.Document roster = readDoc(rosterFile);
        DsonCodec.Node wrapper = findHeroWrapper(roster.root, guid);
        DsonCodec.Node rawNode = findHeroRawNode(wrapper);
        if (rawNode == null) throw new IOException("找不到英雄数据: " + guid);
        DsonCodec.Document heroDoc = DsonCodec.parse(rawNode.asEmbeddedDson());
        DsonCodec.Node quirksNode = heroDoc.root.ensureObject("quirks");
        Map<String, DsonCodec.Node> existingById = new HashMap<>();
        for (DsonCodec.Node q : quirksNode.children) {
            existingById.put(q.name, q);
        }
        List<DsonCodec.Node> rebuilt = new ArrayList<>(quirks.size());
        for (QuirkEntry entry : quirks) {
            String qid = entry.id == null ? "" : entry.id.trim();
            if (qid.isEmpty()) continue;
            DsonCodec.Node node = existingById.get(qid);
            if (node == null) {
                List<DsonCodec.Node> fields = new ArrayList<>();
                fields.add(DsonCodec.boolNode("is_new", entry.isNew));
                fields.add(DsonCodec.boolNode("is_locked", entry.locked));
                fields.add(DsonCodec.intNode("trinketId", 0));
                fields.add(DsonCodec.intNode("mission_count", 0));
                fields.add(DsonCodec.intNode("replaces_quirk", 0));
                fields.add(DsonCodec.boolNode("replaces_quirk_viewed", false));
                fields.add(DsonCodec.intNode("evolution_duration_remaining", 0));
                node = new DsonCodec.Node(qid, 0, fields);
            } else {
                node.getOrCreateBool("is_locked", entry.locked).setBool(entry.locked);
                node.getOrCreateBool("is_new", entry.isNew).setBool(entry.isNew);
            }
            rebuilt.add(node);
        }
        quirksNode.children.clear();
        quirksNode.children.addAll(rebuilt);
        rawNode.setEmbeddedDson(DsonCodec.serialize(heroDoc));
        writeDocVerified(rosterFile, roster);
    }

    public static void batchImproveAllHeroes(Path profile, boolean maxLevelAndGear,
                                             boolean healAndPurify) throws IOException {
        Path rosterFile = profile.resolve("persist.roster.json");
        DsonCodec.Document roster = readDoc(rosterFile);
        DsonCodec.Node heroes = roster.root.child("heroes");
        if (heroes == null || !heroes.object) return;
        for (DsonCodec.Node wrapper : heroes.children) {
            DsonCodec.Node rawNode = findHeroRawNode(wrapper);
            if (rawNode == null || rawNode.asEmbeddedDson() == null) continue;
            DsonCodec.Document heroDoc = DsonCodec.parse(rawNode.asEmbeddedDson());
            if (healAndPurify) {
                applyHeroEdits(heroDoc.root, null, 1.0f, 0f, null, null, null, "", null, true);
                DsonCodec.Node quirksNode = heroDoc.root.child("quirks");
                if (quirksNode != null && quirksNode.object) {
                    quirksNode.children.removeIf(q -> CatalogData.isDisease(q.name));
                }
            }
            if (maxLevelAndGear) {
                applyHeroEdits(heroDoc.root, null, null, null, 48, 4, 4, null, null, false);
            }
            rawNode.setEmbeddedDson(DsonCodec.serialize(heroDoc));
        }
        if (maxLevelAndGear) {
            roster.root.getOrCreateInt("highest_resolve_xp", 48).setInt(48);
        }
        writeDocVerified(rosterFile, roster);
    }

    public static List<TrinketEntry> listTrinkets(Path profile) throws IOException {
        DsonCodec.Document estate = readDoc(profile.resolve("persist.estate.json"));
        DsonCodec.Node trinkets = estate.root.child("trinkets");
        DsonCodec.Node items = trinkets == null ? null : trinkets.child("items");
        List<TrinketEntry> out = new ArrayList<>();
        if (items == null || !items.object) return out;
        for (int i = 0; i < items.children.size(); i++) {
            DsonCodec.Node item = items.children.get(i);
            if (!item.object) continue;
            String id = strChild(item, "id", "");
            int amt = intChild(item, "amount", 1);
            if (!id.isEmpty()) out.add(new TrinketEntry(i, id, amt));
        }
        return out;
    }

    public static void addTrinket(Path profile, String trinketId, int amount) throws IOException {
        String id = trinketId == null ? "" : trinketId.trim();
        if (id.isEmpty()) throw new IOException("饰品 ID 不能为空");
        Path estateFile = profile.resolve("persist.estate.json");
        DsonCodec.Document estate = readDoc(estateFile);
        DsonCodec.Node items = estate.root.ensureObject("trinkets").ensureObject("items");
        int count = Math.max(1, amount);
        for (int c = 0; c < count; c++) {
            List<DsonCodec.Node> fields = new ArrayList<>(8);
            fields.add(DsonCodec.stringNode("id", id));
            fields.add(DsonCodec.stringNode("type", "trinket"));
            fields.add(DsonCodec.intNode("amount", 1));
            fields.add(DsonCodec.intNode("added_buffs", 0));
            fields.add(DsonCodec.stringNode("hero_name", ""));
            fields.add(DsonCodec.stringNode("previous_trinket_id", ""));
            fields.add(DsonCodec.boolNode("did_transform", false));
            fields.add(DsonCodec.intNode("trinkets_gained_count", 0));
            items.children.add(new DsonCodec.Node(Integer.toString(items.children.size()), 0, fields));
        }
        reindexChildren(items);
        writeDocVerified(estateFile, estate);
    }

    public static void removeTrinket(Path profile, int index) throws IOException {
        Path estateFile = profile.resolve("persist.estate.json");
        DsonCodec.Document estate = readDoc(estateFile);
        DsonCodec.Node items = estate.root.ensureObject("trinkets").ensureObject("items");
        if (index < 0 || index >= items.children.size()) {
            throw new IOException("饰品索引越界");
        }
        items.children.remove(index);
        reindexChildren(items);
        writeDocVerified(estateFile, estate);
    }

    public static int unlockAllBuildingUpgrades(Path profile) throws IOException {
        Path upFile = profile.resolve("persist.upgrades.json");
        DsonCodec.Document doc = readDoc(upFile);
        DsonCodec.Node purchases = doc.root.ensureObject("purchases");

        Map<String, DsonCodec.Node> existingKeys = new HashMap<>();
        for (DsonCodec.Node p : purchases.children) {
            if (!p.object) continue;
            int inst = intChild(p, "instance_number", 0);
            int tid = intChild(p, "tree_id", 0);
            DsonCodec.Node req = p.child("requirement_code");
            byte code = req == null ? 0 : req.asByte();
            existingKeys.put(inst + ":" + tid + ":" + code, p);
        }

        int changed = 0;
        for (CatalogData.BuildingTree tree : CatalogData.BUILDING_TREES) {
            int hash = DsonCodec.hash(tree.treeId);
            for (String codeStr : tree.reqCodes) {
                byte code = (byte) codeStr.charAt(0);
                String key = "0:" + hash + ":" + code;
                DsonCodec.Node existing = existingKeys.get(key);
                if (existing != null) {
                    DsonCodec.Node bought = existing.child("is_purchased");
                    if (bought == null || !bought.asBool()) {
                        existing.getOrCreateBool("is_purchased", true).setBool(true);
                        changed++;
                    }
                } else {
                    List<DsonCodec.Node> fields = new ArrayList<>(4);
                    fields.add(DsonCodec.intNode("instance_number", 0));
                    fields.add(DsonCodec.intNode("tree_id", hash));
                    fields.add(DsonCodec.byteNode("requirement_code", code));
                    fields.add(DsonCodec.boolNode("is_purchased", true));
                    DsonCodec.Node node = new DsonCodec.Node(Integer.toString(purchases.children.size()), 0, fields);
                    purchases.children.add(node);
                    existingKeys.put(key, node);
                    changed++;
                }
            }
        }
        reindexChildren(purchases);
        writeDocVerified(upFile, doc);
        return changed;
    }

    public static Map<String, Integer> readDungeonXp(Path profile) throws IOException {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (String key : CatalogData.DUNGEONS.keySet()) out.put(key, 0);
        Path file = profile.resolve("persist.progression.json");
        if (!Files.isRegularFile(file)) return out;
        DsonCodec.Document doc = readDoc(file);
        DsonCodec.Node dungeon = doc.root.child("dungeon");
        if (dungeon != null && dungeon.object) {
            for (DsonCodec.Node child : dungeon.children) {
                if (child.object) {
                    out.put(child.name, intChild(child, "xp", 0));
                }
            }
        }
        return out;
    }

    public static void writeDungeonXp(Path profile, Map<String, Integer> dungeonXp) throws IOException {
        Path file = profile.resolve("persist.progression.json");
        DsonCodec.Document doc = readDoc(file);
        DsonCodec.Node dungeon = doc.root.ensureObject("dungeon");
        for (Map.Entry<String, Integer> entry : dungeonXp.entrySet()) {
            DsonCodec.Node area = dungeon.ensureObject(entry.getKey());
            area.getOrCreateInt("xp", Math.max(0, entry.getValue())).setInt(Math.max(0, entry.getValue()));
        }
        writeDocVerified(file, doc);
    }

    public static List<String> listDsonFiles(Path profile) throws IOException {
        List<String> files = new ArrayList<>();
        try (Stream<Path> list = Files.list(profile)) {
            for (Path p : list.sorted().collect(Collectors.toList())) {
                if (!Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) continue;
                String name = p.getFileName().toString();
                if (!name.endsWith(".json")) continue;
                byte[] header = new byte[64];
                if (Files.size(p) >= 64) {
                    try (var in = Files.newInputStream(p)) {
                        if (in.read(header) == 64 && DsonCodec.isDson(header)) {
                            files.add(name);
                        }
                    }
                }
            }
        }
        return files;
    }

    public static List<RawEntry> inspectRawPath(Path profile, String fileName, List<String> path) throws IOException {
        DsonCodec.Document doc = readDoc(profile.resolve(fileName));
        DsonCodec.Node current = doc.root;
        if (path != null) {
            for (String segment : path) {
                if (current == null) break;
                if (!current.object && current.asEmbeddedDson() != null) {
                    current = DsonCodec.parse(current.asEmbeddedDson()).root;
                }
                current = current.child(segment);
            }
        }
        if (current == null) throw new IOException("路径不存在");
        if (!current.object && current.asEmbeddedDson() != null) {
            current = DsonCodec.parse(current.asEmbeddedDson()).root;
        }
        if (!current.object) throw new IOException("目标不是对象节点");

        List<RawEntry> out = new ArrayList<>(current.children.size());
        for (int i = 0; i < current.children.size(); i++) {
            DsonCodec.Node c = current.children.get(i);
            if (c.object) {
                out.add(new RawEntry(i, c.name, true, c.children.size(), "obj", "{" + c.children.size() + " 项}", false));
            } else if (c.singleByte) {
                int v = c.asByte() & 0xFF;
                out.add(new RawEntry(i, c.name, false, 0, "bool", v <= 1 ? (v == 1 ? "true (1)" : "false (0)") : Integer.toString(v), true));
            } else if (c.asEmbeddedDson() != null) {
                DsonCodec.Document inner = DsonCodec.parse(c.asEmbeddedDson());
                out.add(new RawEntry(i, c.name, true, inner.root.children.size(), "dson", "嵌套 DSON (" + inner.root.children.size() + " 项)", false));
            } else if (c.isStringPayload()) {
                out.add(new RawEntry(i, c.name, false, 0, "string", c.asString(), true));
            } else if (c.payload != null && c.payload.length == 4) {
                int iv = c.asInt();
                float fv = c.asFloat();
                boolean looksFloat = !Float.isNaN(fv) && !Float.isInfinite(fv) && Math.abs(iv) > 100000 && Math.abs(fv) < 1e7f && Math.abs(fv) >= 1e-4f;
                String disp = String.format(Locale.ROOT, "int=%d | float=%.4g", iv, fv);
                out.add(new RawEntry(i, c.name, false, 0, looksFloat ? "float" : "int", disp, true));
            } else {
                int len = c.payload == null ? 0 : c.payload.length;
                out.add(new RawEntry(i, c.name, false, 0, "bytes", "[" + len + " bytes]", false));
            }
        }
        return out;
    }

    public static void editRawScalar(Path profile, String fileName, List<String> path,
                                     String valueType, String newValue) throws IOException {
        if (path == null || path.isEmpty()) throw new IOException("路径不能为空");
        Path file = profile.resolve(fileName);
        DsonCodec.Document rootDoc = readDoc(file);

        // Locate any embedded DSON boundary along path
        DsonCodec.Node embeddedCarrier = null;
        DsonCodec.Document embeddedDoc = null;
        int embeddedBoundary = -1;

        DsonCodec.Node cursor = rootDoc.root;
        for (int i = 0; i < path.size(); i++) {
            String seg = path.get(i);
            if (!cursor.object && cursor.asEmbeddedDson() != null) {
                embeddedCarrier = cursor;
                embeddedDoc = DsonCodec.parse(cursor.asEmbeddedDson());
                embeddedBoundary = i;
                cursor = embeddedDoc.root;
            }
            cursor = cursor.child(seg);
            if (cursor == null) throw new IOException("找不到字段: " + seg);
        }
        if (cursor.object) throw new IOException("仅支持编辑标量字段");

        switch (valueType) {
            case "bool" -> {
                String v = newValue.trim().toLowerCase(Locale.ROOT);
                if ("true".equals(v) || "1".equals(v)) cursor.setByte((byte) 1);
                else if ("false".equals(v) || "0".equals(v)) cursor.setByte((byte) 0);
                else cursor.setByte((byte) Integer.parseInt(v));
            }
            case "int" -> cursor.setInt(Integer.parseInt(newValue.trim()));
            case "float" -> cursor.setFloat(Float.parseFloat(newValue.trim()));
            case "string" -> cursor.setString(newValue);
            default -> throw new IOException("不支持编辑该类型: " + valueType);
        }

        if (embeddedCarrier != null && embeddedDoc != null && embeddedBoundary >= 0) {
            embeddedCarrier.setEmbeddedDson(DsonCodec.serialize(embeddedDoc));
        }
        writeDocVerified(file, rootDoc);
    }

    // ---- Internal helpers ----

    private static Map<String, Integer> readWallet(DsonCodec.Node estateRoot) {
        Map<String, Integer> wallet = new LinkedHashMap<>();
        DsonCodec.Node walletNode = estateRoot.child("wallet");
        if (walletNode != null && walletNode.object) {
            for (DsonCodec.Node item : walletNode.children) {
                if (!item.object) continue;
                String type = strChild(item, "type", "");
                int amount = intChild(item, "amount", 0);
                if (!type.isEmpty()) wallet.put(type, amount);
            }
        }
        return wallet;
    }

    private static DsonCodec.Node findHeroWrapper(DsonCodec.Node rosterRoot, String guid) throws IOException {
        DsonCodec.Node heroes = rosterRoot.child("heroes");
        if (heroes == null || !heroes.object) throw new IOException("花名册为空");
        DsonCodec.Node wrapper = heroes.child(guid);
        if (wrapper == null) throw new IOException("找不到英雄 GUID: " + guid);
        return wrapper;
    }

    private static DsonCodec.Node findHeroRawNode(DsonCodec.Node wrapper) {
        if (wrapper == null || !wrapper.object) return null;
        DsonCodec.Node fileData = wrapper.child("hero_file_data");
        if (fileData == null || !fileData.object) return null;
        return fileData.child("raw_data");
    }

    private static HeroState readHeroState(String guid, DsonCodec.Node heroRoot) {
        DsonCodec.Node actor = heroRoot.child("actor");
        String name = actor == null ? "英雄#" + guid : strChild(actor, "name", "英雄#" + guid);
        float hp = actor == null ? 1f : floatChild(actor, "current_hp", 1f);
        String cls = strChild(heroRoot, "heroClass", "");
        int status = intChild(heroRoot, "roster.status", 0);
        float stress = floatChild(heroRoot, "m_Stress", 0f);
        int xp = intChild(heroRoot, "resolveXp", 0);
        int weapon = intChild(heroRoot, "weapon_rank", 0);
        int armour = intChild(heroRoot, "armour_rank", 0);
        String aff = strChild(heroRoot, "affliction_type_id", "");
        String virt = strChild(heroRoot, "virtue_type_id", "");

        List<QuirkEntry> quirks = new ArrayList<>();
        DsonCodec.Node quirksNode = heroRoot.child("quirks");
        if (quirksNode != null && quirksNode.object) {
            for (DsonCodec.Node q : quirksNode.children) {
                if (!q.object) continue;
                quirks.add(new QuirkEntry(
                        q.name,
                        boolChild(q, "is_locked", false),
                        boolChild(q, "is_new", false)
                ));
            }
        }
        return new HeroState(guid, name, cls, status, hp, stress, xp, weapon, armour, aff, virt, quirks);
    }

    private static void applyHeroEdits(DsonCodec.Node heroRoot, String newName,
                                       Float hpFraction, Float stress, Integer resolveXp,
                                       Integer weaponRank, Integer armourRank,
                                       String affliction, String virtue, boolean revive) {
        DsonCodec.Node actor = heroRoot.ensureObject("actor");
        if (newName != null && !newName.trim().isEmpty()) {
            actor.getOrCreateString("name", newName.trim()).setString(newName.trim());
        }
        if (hpFraction != null) {
            float clamped = Math.max(0.01f, Math.min(1.0f, hpFraction));
            actor.getOrCreateFloat("current_hp", clamped).setFloat(clamped);
        }
        if (stress != null) {
            float clamped = Math.max(0f, Math.min(200f, stress));
            heroRoot.getOrCreateFloat("m_Stress", clamped).setFloat(clamped);
        }
        if (resolveXp != null) {
            heroRoot.getOrCreateInt("resolveXp", Math.max(0, resolveXp)).setInt(Math.max(0, resolveXp));
        }
        if (weaponRank != null) {
            int r = Math.max(0, Math.min(4, weaponRank));
            heroRoot.getOrCreateInt("weapon_rank", r).setInt(r);
        }
        if (armourRank != null) {
            int r = Math.max(0, Math.min(4, armourRank));
            heroRoot.getOrCreateInt("armour_rank", r).setInt(r);
        }
        if (affliction != null) {
            heroRoot.getOrCreateString("affliction_type_id", affliction).setString(affliction);
            heroRoot.getOrCreateInt("affliction_severity", affliction.isEmpty() ? 0 : 1)
                    .setInt(affliction.isEmpty() ? 0 : 1);
        }
        if (virtue != null) {
            heroRoot.getOrCreateString("virtue_type_id", virtue).setString(virtue);
            if (!virtue.isEmpty()) {
                heroRoot.getOrCreateString("affliction_type_id", "").setString("");
                heroRoot.getOrCreateInt("affliction_severity", 0).setInt(0);
            }
        }
        if (revive) {
            int status = intChild(heroRoot, "roster.status", 0);
            if (status == 3) {
                heroRoot.getOrCreateInt("roster.status", 0).setInt(0);
                heroRoot.getOrCreateInt("roster.before_on_start_town_visit_status", 0).setInt(0);
            }
            heroRoot.getOrCreateBool("is_death_heart_attack_completed", false).setBool(false);
            heroRoot.getOrCreateBool("has_had_heart_attack", false).setBool(false);
            heroRoot.getOrCreateInt("visited_deaths_door", 0).setInt(0);
        }
    }

    private static void reindexChildren(DsonCodec.Node parent) {
        for (int i = 0; i < parent.children.size(); i++) {
            DsonCodec.Node child = parent.children.get(i);
            String expected = Integer.toString(i);
            if (!expected.equals(child.name)) {
                parent.children.set(i, new DsonCodec.Node(expected, child.extraFlags, child.children));
            }
        }
    }

    private static DsonCodec.Document readDoc(Path file) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("存档文件缺失: " + file.getFileName());
        }
        byte[] raw = Files.readAllBytes(file);
        return DsonCodec.parse(raw);
    }

    private static void writeDocVerified(Path file, DsonCodec.Document doc) throws IOException {
        byte[] encoded = DsonCodec.serialize(doc);
        // Round-trip structural verification before touching disk
        DsonCodec.Document check = DsonCodec.parse(encoded);
        if (!check.root.name.equals(doc.root.name) || check.root.children.size() != doc.root.children.size()) {
            throw new IOException("DSON 序列化自检失败，已中止写入");
        }
        Path temp = file.getParent().resolve("." + file.getFileName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp.toFile())) {
            out.write(encoded);
            out.getFD().sync();
        }
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicUnsupported) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String strChild(DsonCodec.Node parent, String key, String def) {
        if (parent == null) return def;
        DsonCodec.Node child = parent.child(key);
        return child == null ? def : child.asString();
    }

    private static int intChild(DsonCodec.Node parent, String key, int def) {
        if (parent == null) return def;
        DsonCodec.Node child = parent.child(key);
        return child == null ? def : child.asInt();
    }

    private static float floatChild(DsonCodec.Node parent, String key, float def) {
        if (parent == null) return def;
        DsonCodec.Node child = parent.child(key);
        return child == null ? def : child.asFloat();
    }

    private static boolean boolChild(DsonCodec.Node parent, String key, boolean def) {
        if (parent == null) return def;
        DsonCodec.Node child = parent.child(key);
        return child == null ? def : child.asBool();
    }
}
