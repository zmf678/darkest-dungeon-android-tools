package dev.torcharchive.game;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.FileOutputStream;
import java.io.FilterOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

final class SnapshotStore {
    private static final Pattern ID = Pattern.compile("[0-9]{19}-[0-9]{13}-profile_[0-8]-[0-9a-f]{64}");
    private static final String MAIN = "persist.game.json";
    private static final long MAX_BYTES = 1024L * 1024 * 1024;
    private static final OutputStream DISCARD = new OutputStream() {
        @Override public void write(int value) {}
        @Override public void write(byte[] bytes, int offset, int length) {}
    };
    private final Path history;
    private final Map<String, String> latest = new HashMap<>();
    private long sequence;

    SnapshotStore(Path history) throws IOException {
        this.history = Files.createDirectories(history);
        for (String id : list()) {
            sequence = Math.max(sequence, Long.parseLong(id.substring(0, 19)));
            latest.putIfAbsent(slotOf(id), digestOf(id));
        }
    }

    List<String> list() throws IOException {
        try (Stream<Path> files = Files.list(history)) {
            return files.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".zip"))
                    .map(name -> name.substring(0, name.length() - 4))
                    .filter(id -> ID.matcher(id).matches())
                    .sorted(Comparator.reverseOrder())
                    .collect(Collectors.toList());
        }
    }

    Path archive(String id) throws IOException {
        if (id == null || !ID.matcher(id).matches()) throw new IOException("Invalid snapshot ID");
        Path archive = history.resolve(id + ".zip");
        if (!Files.isRegularFile(archive, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Snapshot missing");
        return archive;
    }

    static String slotOf(String id) {
        return id.substring(34, 43);
    }

    private static String digestOf(String id) {
        return id.substring(44);
    }

    void baseline(Path saves) throws IOException {
        for (int i = 0; i <= 8; i++) {
            String slot = "profile_" + i;
            Path profile = saves.resolve(slot);
            if (validProfile(profile)) {
                latest.put(slot, writeZip(profile, DISCARD));
            } else {
                latest.remove(slot);
            }
        }
    }

    int capture(Path saves) throws IOException {
        int captured = 0;
        for (int i = 0; i <= 8; i++) {
            String slot = "profile_" + i;
            Path profile = saves.resolve(slot);
            if (validProfile(profile)) {
                if (publish(profile, false) != null) captured++;
            } else {
                latest.remove(slot);
            }
        }
        return captured;
    }

    String backup(Path profile) throws IOException {
        if (!validProfile(profile)) throw new IOException("Target profile is invalid");
        return publish(profile, true);
    }

    private String publish(Path profile, boolean force) throws IOException {
        String slot = profile.getFileName().toString();
        Path temp = Files.createTempFile(history, ".capture-", ".zip");
        try {
            String digest;
            try (FileOutputStream out = new FileOutputStream(temp.toFile())) {
                digest = writeZip(profile, out);
                out.getFD().sync();
            }
            if (!force && digest.equals(latest.get(slot))) return null;
            String id = String.format(Locale.ROOT, "%019d-%013d-%s-%s",
                    ++sequence, System.currentTimeMillis(), slot, digest);
            Files.move(temp, history.resolve(id + ".zip"), StandardCopyOption.ATOMIC_MOVE);
            latest.put(slot, digest);
            return id;
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static String writeZip(Path profile, OutputStream out) throws IOException {
        MessageDigest digest = sha256();
        try (ZipOutputStream zip = new ZipOutputStream(new DigestOutputStream(new FilterOutputStream(out) {
            @Override public void close() {}
            @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                out.write(bytes, offset, length);
            }
        }, digest));
             Stream<Path> tree = Files.walk(profile)) {
            long size = 0;
            for (Path file : tree.sorted().collect(Collectors.toList())) {
                if (Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) continue;
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid save file");
                String name = file.getFileName().toString();
                if (name.endsWith(".old") || name.endsWith(".tmp")) continue;
                size += Files.size(file);
                if (size > MAX_BYTES) throw new IOException("Save profile is too large");
                String relative = profile.getParent().relativize(file).toString().replace('\\', '/');
                ZipEntry entry = new ZipEntry(relative);
                entry.setTime(315532800000L);
                zip.putNextEntry(entry);
                Files.copy(file, zip);
                zip.closeEntry();
            }
        }
        return hex(digest.digest());
    }

    void restore(Path saves, String id) throws IOException {
        Path zip = archive(id);
        if (!digestOf(id).equals(fileDigest(zip))) throw new IOException("Snapshot checksum mismatch");
        String slot = slotOf(id);
        Path target = saves.resolve(slot);
        Path transaction = saves.resolve(".torcharchive-restore");
        Files.createDirectory(transaction);
        Path staged = transaction.resolve("staged");
        Path previous = transaction.resolve("previous");
        try {
            try (FileOutputStream out = new FileOutputStream(transaction.resolve("slot").toFile())) {
                out.write(slot.getBytes(StandardCharsets.US_ASCII));
                out.getFD().sync();
            }
            Files.createDirectory(staged);
            unpack(zip, slot, staged);
            if (!validProfile(staged)) throw new IOException("Snapshot has no main save file");
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                if (!validProfile(target)) throw new IOException("Target profile is invalid");
                publish(target, true);
                Files.move(target, previous, StandardCopyOption.ATOMIC_MOVE);
            }
            Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            recover(saves);
        }
    }

    static void recover(Path saves) throws IOException {
        Path transaction = saves.resolve(".torcharchive-restore");
        if (!Files.isDirectory(transaction)) return;
        Path previous = transaction.resolve("previous");
        if (Files.exists(previous, LinkOption.NOFOLLOW_LINKS)) {
            String slot = new String(Files.readAllBytes(transaction.resolve("slot")), StandardCharsets.US_ASCII);
            if (!slot.matches("profile_[0-8]")) throw new IOException("Invalid restore slot");
            Path target = saves.resolve(slot);
            if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                Files.move(previous, target, StandardCopyOption.ATOMIC_MOVE);
            } else if (!validProfile(target)) {
                throw new IOException("Restore interrupted. Previous profile retained at " + previous);
            }
        }
        try (Stream<Path> tree = Files.walk(transaction)) {
            for (Path path : tree.sorted(Comparator.reverseOrder()).collect(Collectors.toList())) {
                Files.delete(path);
            }
        }
    }

    private static void unpack(Path archive, String slot, Path staged) throws IOException {
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            long size = 0;
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory() || !name.startsWith(slot + "/") || name.contains("\\")) {
                    throw new IOException("Invalid snapshot entry");
                }
                String relative = name.substring(slot.length() + 1);
                Path file = staged.resolve(relative).normalize();
                if (file.equals(staged) || !file.startsWith(staged)
                        || !staged.relativize(file).toString().replace('\\', '/').equals(relative)
                        || entry.getSize() < 0 || entry.getSize() > MAX_BYTES - size) {
                    throw new IOException("Invalid snapshot entry");
                }
                size += entry.getSize();
                Files.createDirectories(file.getParent());
                try (InputStream in = zip.getInputStream(entry)) {
                    if (Files.copy(in, file) != entry.getSize()) throw new IOException("Truncated snapshot entry");
                }
            }
        }
    }

    private static boolean validProfile(Path profile) {
        return Files.isDirectory(profile, LinkOption.NOFOLLOW_LINKS)
                && Files.isRegularFile(profile.resolve(MAIN), LinkOption.NOFOLLOW_LINKS);
    }

    private static String fileDigest(Path file) throws IOException {
        MessageDigest digest = sha256();
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            for (int read; (read = in.read(buffer)) >= 0;) digest.update(buffer, 0, read);
        }
        return hex(digest.digest());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(Character.forDigit((value >>> 4) & 15, 16));
            result.append(Character.forDigit(value & 15, 16));
        }
        return result.toString();
    }
}
