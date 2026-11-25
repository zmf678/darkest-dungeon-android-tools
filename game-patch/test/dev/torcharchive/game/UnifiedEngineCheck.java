package dev.torcharchive.game;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public final class UnifiedEngineCheck {
    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            throw new IllegalArgumentException("Pass path to a real Steam profile_0 folder");
        }
        Path realProfile = Path.of(args[0]);
        Path work = Files.createTempDirectory("torch-unified-check-");
        try {
            // 1. DisplayConfig universal presets check
            checkPresets(2400, 1080, "20:9 phone");
            checkPresets(3008, 1880, "Redmi K Pad 16:10");
            checkPresets(2176, 1812, "Foldable");
            checkPresets(1600, 720, "Compact 720p");

            // 2. SaveEditor full check against copy of real Steam profile
            Path savesRoot = Files.createDirectories(work.resolve("save"));
            Path profileCopy = savesRoot.resolve("profile_0");
            ModBridge.copyDirectory(realProfile, profileCopy);

            List<SaveEditor.SlotSummary> slots = SaveEditor.listSlots(savesRoot);
            if (slots.size() != 1) throw new AssertionError("Expected 1 slot");

            SaveEditor.writeEstate(profileCopy, "余烬测试庄园", "radiant",
                    Map.of("gold", 888888, "shard", 777, "blueprint", 12), 42);
            SaveEditor.EstateState est = SaveEditor.readEstate(profileCopy);
            if (!"余烬测试庄园".equals(est.estateName)
                    || est.wallet.get("gold") != 888888
                    || est.wallet.get("shard") != 777
                    || est.wallet.get("blueprint") != 12
                    || est.endlessHighscore != 42) {
                throw new AssertionError("Estate edit verification failed");
            }

            List<SaveEditor.HeroState> heroes = SaveEditor.listHeroes(profileCopy);
            if (heroes.isEmpty()) throw new AssertionError("Expected heroes in real profile");
            SaveEditor.HeroState first = heroes.get(0);
            SaveEditor.updateHero(profileCopy, first.guid, "余烬骑士", 1.0f, 0.0f, 48, 4, 4, "", "focused", true);
            List<SaveEditor.QuirkEntry> quirks = new ArrayList<>(first.quirks);
            quirks.add(new SaveEditor.QuirkEntry("luminous", true, false));
            SaveEditor.updateHeroQuirks(profileCopy, first.guid, quirks);
            SaveEditor.HeroState afterHero = SaveEditor.listHeroes(profileCopy).stream()
                    .filter(h -> h.guid.equals(first.guid)).findFirst().orElseThrow();
            if (!"余烬骑士".equals(afterHero.name) || afterHero.resolveXp != 48
                    || afterHero.weaponRank != 4 || !"focused".equals(afterHero.virtue)
                    || afterHero.quirks.stream().noneMatch(q -> "luminous".equals(q.id) && q.locked)) {
                throw new AssertionError("Hero nested DSON edit verification failed");
            }

            int beforeTrinkets = SaveEditor.listTrinkets(profileCopy).size();
            SaveEditor.addTrinket(profileCopy, "ancestors_map", 2);
            if (SaveEditor.listTrinkets(profileCopy).size() != beforeTrinkets + 2) {
                throw new AssertionError("Add trinket count mismatch");
            }
            SaveEditor.removeTrinket(profileCopy, 0);
            if (SaveEditor.listTrinkets(profileCopy).size() != beforeTrinkets + 1) {
                throw new AssertionError("Remove trinket count mismatch");
            }

            int unlocked = SaveEditor.unlockAllBuildingUpgrades(profileCopy);
            if (unlocked <= 0) throw new AssertionError("Expected building upgrades unlocked");

            SaveEditor.editRawScalar(profileCopy, "persist.game.json", List.of("totalelapsed"), "float", "1234.5");
            List<SaveEditor.RawEntry> rawGame = SaveEditor.inspectRawPath(profileCopy, "persist.game.json", List.of());
            if (rawGame.isEmpty()) throw new AssertionError("Expected raw DSON entries");

            // 3. ContentModGenerator check
            Path modDir = work.resolve("mod_out");
            ContentModGenerator.Config cfg = new ContentModGenerator.Config();
            cfg.stackMultiplier = 10;
            cfg.freeSupplies = true;
            cfg.heroTrinketSlots = 4;
            cfg.combatSpeedScale = 2.5f;
            cfg.playerCritMultiplier = 2.5f;
            cfg.alwaysHitCap = 1.0f;
            cfg.guaranteedRetreat = true;
            cfg.virtueChancePercent = 100;
            cfg.stressScale = 0.0f;
            cfg.idleStressHeal = 50f;
            cfg.fastResolveXp = true;
            cfg.stageCoachMultiplier = 3;
            cfg.rosterCapacityBonus = 50;
            ContentModGenerator.generateMod(modDir, cfg);
            for (String expected : new String[]{
                    "project.xml",
                    "modifier-config.json",
                    "inventory/base.currency.inventory.items.darkest",
                    "inventory/base.inventory.system_configs.darkest",
                    "shared/speed.rules.json",
                    "shared/rules.json",
                    "campaign/roster/roster.variables.json",
                    "campaign/town/buildings/stage_coach/stage_coach.building.json"
            }) {
                if (!Files.isRegularFile(modDir.resolve(expected))) {
                    throw new AssertionError("Missing generated mod file: " + expected);
                }
            }

            System.out.println("UnifiedEngineCheck OK: DisplayConfig + SaveEditor(estate/hero/quirk/trinket/buildings/raw) + ContentModGenerator verified.");
        } finally {
            try (Stream<Path> files = Files.walk(work)) {
                for (Path p : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
            }
        }
    }

    private static void checkPresets(int w, int h, String label) {
        List<DisplayConfig.Preset> list = DisplayConfig.presetsForDevice(w, h);
        if (list.isEmpty()) throw new AssertionError("No presets for " + label);
        for (DisplayConfig.Preset p : list) {
            if ((p.width & 1) != 0 || (p.height & 1) != 0 || !DisplayConfig.valid(p.width, p.height)) {
                throw new AssertionError("Invalid preset for " + label + ": " + p.width + "x" + p.height);
            }
        }
    }
}
