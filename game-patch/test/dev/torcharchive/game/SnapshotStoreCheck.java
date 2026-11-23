package dev.torcharchive.game;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

public final class SnapshotStoreCheck {
    private static final byte[] FIRST = {0, 41, -57};
    private static final byte[] LAST = {1, -23, 29};
    private static final byte[] MODS = {-1, 25, 2, -8};

    public static void main(String[] args) throws Exception {
        Path work = Files.createTempDirectory("torcharchive-check-");
        try {
            Path saves = Files.createDirectories(work.resolve("save"));
            Path history = work.resolve("history");
            Path profile = Files.createDirectories(saves.resolve("profile_0"));
            Path main = profile.resolve("persist.game.json");
            Path mods = profile.resolve("mods.bin");
            Files.write(main, new byte[]{5});
            SnapshotStore store = new SnapshotStore(history);
            store.baseline(saves);
            check(store.capture(saves) == 0, "Existing save was incorrectly recorded as a new commit");

            Files.write(main, FIRST);
            Files.write(mods, MODS);
            check(store.capture(saves) == 1, "First commit was missed");
            Files.write(main, new byte[]{2, -7});
            check(store.capture(saves) == 1, "Adjacent commit was missed");
            Files.write(main, LAST);
            Files.write(mods, new byte[]{8});
            Files.write(profile.resolve("persist.game.json.tmp"), new byte[]{-1});
            check(store.capture(saves) == 1, "Third commit was missed");
            check(store.capture(saves) == 0, "Repeated commit duplicated the archive");

            store = new SnapshotStore(history);
            List<String> versions = store.list();
            check(versions.size() == 3, "History was not preserved across restart");
            check(store.capture(saves) == 0, "Reopening duplicated the latest snapshot");
            store.restore(saves, versions.get(2));
            check(Arrays.equals(FIRST, Files.readAllBytes(main)), "First profile was not restored byte-for-byte");
            check(Arrays.equals(MODS, Files.readAllBytes(mods)), "Second file was not restored byte-for-byte");
            check(!Files.exists(profile.resolve("persist.game.json.tmp")), "Temporary file leaked into restoration");

            String beforeRestore = store.list().get(0);
            store.restore(saves, beforeRestore);
            check(Arrays.equals(LAST, Files.readAllBytes(main)), "Pre-restore profile was not recoverable");
            String corrupt = versions.get(1);
            Files.write(store.archive(corrupt), new byte[]{-1});
            try {
                store.restore(saves, corrupt);
                throw new AssertionError("Corrupted snapshot was restored");
            } catch (IOException expected) {
                check(Arrays.equals(LAST, Files.readAllBytes(main)), "Failed restore altered the live save");
            }

            Path transaction = Files.createDirectory(saves.resolve(".torcharchive-restore"));
            Files.writeString(transaction.resolve("slot"), "profile_0");
            Files.move(profile, transaction.resolve("previous"));
            SnapshotStore.recover(saves);
            check(Arrays.equals(LAST, Files.readAllBytes(main)), "Interrupted replacement lost the old slot");
            check(!Files.exists(transaction), "Recovery left a finished transaction behind");
            System.out.println("Passed three rapid commits, duplicate suppression, restart, multi-file restore, "
                    + "pre-restore history, corrupted snapshot rejection, and interrupted restore.");
        } finally {
            try (Stream<Path> files = Files.walk(work)) {
                for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
