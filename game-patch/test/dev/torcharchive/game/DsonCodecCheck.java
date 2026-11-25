package dev.torcharchive.game;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class DsonCodecCheck {
    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            throw new IllegalArgumentException("Pass path to a real DSON persist.game.json");
        }
        byte[] original = Files.readAllBytes(Path.of(args[0]));
        List<DsonCodec.ModRef> mods = DsonCodec.readAppliedMods(original);
        if (mods.isEmpty()) throw new AssertionError("Expected mods in fixture");

        // 1. Exact byte-for-byte round-trip with unchanged applied mods
        byte[] roundTripped = DsonCodec.writeAppliedMods(original, mods);
        if (!Arrays.equals(original, roundTripped)) {
            int firstDiff = -1;
            int min = Math.min(original.length, roundTripped.length);
            for (int i = 0; i < min; i++) {
                if (original[i] != roundTripped[i]) { firstDiff = i; break; }
            }
            throw new AssertionError("Round-trip mismatch: origLen=" + original.length
                    + " newLen=" + roundTripped.length + " firstDiff=" + firstDiff);
        }

        // 2. Convert Steam -> mod and reorder + add a new mod
        List<DsonCodec.ModRef> updated = new ArrayList<>();
        updated.add(new DsonCodec.ModRef("2115544857", "mod"));
        updated.add(new DsonCodec.ModRef("1689234891", "mod"));
        for (DsonCodec.ModRef ref : mods) {
            updated.add(new DsonCodec.ModRef(ref.name, "mod"));
        }
        byte[] modified = DsonCodec.writeAppliedMods(original, updated);
        List<DsonCodec.ModRef> reread = DsonCodec.readAppliedMods(modified);
        if (!reread.equals(updated)) {
            throw new AssertionError("Re-read mod list mismatch: got " + reread.size() + " expected " + updated.size());
        }

        // 3. Clear all mods and re-read empty list
        byte[] cleared = DsonCodec.writeAppliedMods(modified, List.of());
        if (!DsonCodec.readAppliedMods(cleared).isEmpty()) {
            throw new AssertionError("Expected empty applied mods after clear");
        }

        System.out.println("DsonCodecCheck OK: exact byte-for-byte round-trip ("
                + original.length + " bytes, " + mods.size() + " mods) + edit/clear verified");
    }
}
