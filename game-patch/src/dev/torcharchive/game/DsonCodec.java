package dev.torcharchive.game;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Lossless parser/serializer for Red Hook's binary DSON persist.game.json format.
 * Preserves all unrelated fields byte-for-byte while safely updating applied_ugcs_1_0.
 */
final class DsonCodec {
    static final int MAGIC = 0x0000B101;
    private static final int HEADER_SIZE = 64;

    static final class ModRef {
        final String name;
        final String source;

        ModRef(String name, String source) {
            this.name = name;
            this.source = source;
        }

        @Override public boolean equals(Object other) {
            if (!(other instanceof ModRef)) return false;
            ModRef that = (ModRef) other;
            return name.equals(that.name) && source.equals(that.source);
        }

        @Override public int hashCode() {
            return name.hashCode() * 31 + source.hashCode();
        }
    }

    private static final class Node {
        final String name;
        final boolean object;
        final int extraFlags; // preserves bit 31 and bit 1
        final List<Node> children;
        final boolean singleByte;
        final byte[] payload;

        Node(String name, int extraFlags, List<Node> children) {
            this.name = name;
            this.object = true;
            this.extraFlags = extraFlags;
            this.children = children;
            this.singleByte = false;
            this.payload = null;
        }

        Node(String name, int extraFlags, boolean singleByte, byte[] payload) {
            this.name = name;
            this.object = false;
            this.extraFlags = extraFlags;
            this.children = null;
            this.singleByte = singleByte;
            this.payload = payload;
        }

        Node child(String key) {
            if (!object) return null;
            for (Node node : children) {
                if (key.equals(node.name)) return node;
            }
            return null;
        }

        String asString() {
            if (object || payload == null || payload.length < 5) return "";
            int len = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).getInt(0);
            if (len <= 0 || 4 + len > payload.length) return "";
            int end = 4 + len;
            if (payload[end - 1] == 0) end--;
            return new String(payload, 4, end - 4, StandardCharsets.UTF_8);
        }
    }

    private static final class Document {
        final byte[] headerPrefix; // first 16 bytes (magic + flags + header_len + reserved)
        final Node root;

        Document(byte[] headerPrefix, Node root) {
            this.headerPrefix = headerPrefix;
            this.root = root;
        }
    }

    private DsonCodec() {}

    static boolean isDson(byte[] raw) {
        if (raw == null || raw.length < HEADER_SIZE) return false;
        return ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).getInt(0) == MAGIC;
    }

    static List<ModRef> readAppliedMods(byte[] raw) throws IOException {
        if (!isDson(raw)) throw new IOException("Unsupported save format (expected binary DSON)");
        Document doc = parse(raw);
        return extractApplied(doc.root.child("applied_ugcs_1_0"));
    }

    static byte[] writeAppliedMods(byte[] raw, List<ModRef> applied) throws IOException {
        if (!isDson(raw)) throw new IOException("Unsupported save format (expected binary DSON)");
        Document doc = parse(raw);
        replaceApplied(doc.root, applied);
        mergePersistent(doc.root, applied);
        return serialize(doc);
    }

    private static List<ModRef> extractApplied(Node appliedNode) {
        List<ModRef> list = new ArrayList<>();
        if (appliedNode == null || !appliedNode.object) return list;
        for (Node item : appliedNode.children) {
            if (!item.object) continue;
            Node nameNode = item.child("name");
            Node sourceNode = item.child("source");
            if (nameNode == null) continue;
            String name = nameNode.asString();
            String source = sourceNode == null ? "" : sourceNode.asString();
            if (!name.isEmpty()) list.add(new ModRef(name, source));
        }
        return list;
    }

    private static void replaceApplied(Node baseRoot, List<ModRef> applied) {
        Node existing = baseRoot.child("applied_ugcs_1_0");
        int flags = existing != null ? existing.extraFlags : 0;
        List<Node> items = buildModItems(applied);
        Node replacement = new Node("applied_ugcs_1_0", flags, items);
        if (existing != null) {
            int idx = baseRoot.children.indexOf(existing);
            baseRoot.children.set(idx, replacement);
        } else {
            int insertAt = baseRoot.children.size();
            for (int i = 0; i < baseRoot.children.size(); i++) {
                String key = baseRoot.children.get(i).name;
                if ("persistent_ugcs".equals(key) || "presented_dlc".equals(key)) {
                    insertAt = i;
                    break;
                }
            }
            baseRoot.children.add(insertAt, replacement);
        }
    }

    private static void mergePersistent(Node baseRoot, List<ModRef> applied) {
        if (applied.isEmpty()) return;
        Node persistent = baseRoot.child("persistent_ugcs");
        if (persistent == null || !persistent.object) {
            persistent = new Node("persistent_ugcs", 0, new ArrayList<>());
            int insertAt = baseRoot.children.size();
            for (int i = 0; i < baseRoot.children.size(); i++) {
                if ("presented_dlc".equals(baseRoot.children.get(i).name)) {
                    insertAt = i;
                    break;
                }
            }
            baseRoot.children.add(insertAt, persistent);
        }
        Node existing = persistent.child("applied_ugcs_1_0");
        List<ModRef> merged = new ArrayList<>(extractApplied(existing));
        Set<ModRef> seen = new HashSet<>(merged);
        for (ModRef ref : applied) {
            if (seen.add(ref)) merged.add(ref);
        }
        int flags = existing != null ? existing.extraFlags : 0;
        Node replacement = new Node("applied_ugcs_1_0", flags, buildModItems(merged));
        if (existing != null) {
            persistent.children.set(persistent.children.indexOf(existing), replacement);
        } else {
            persistent.children.add(replacement);
        }
    }

    private static List<Node> buildModItems(List<ModRef> mods) {
        List<Node> items = new ArrayList<>(mods.size());
        for (int i = 0; i < mods.size(); i++) {
            ModRef ref = mods.get(i);
            List<Node> fields = new ArrayList<>(2);
            fields.add(stringNode("name", ref.name));
            fields.add(stringNode("source", ref.source));
            items.add(new Node(Integer.toString(i), 0, fields));
        }
        return items;
    }

    private static Node stringNode(String key, String value) {
        byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
        int len = utf8.length + 1;
        byte[] payload = new byte[4 + len];
        ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).putInt(0, len);
        System.arraycopy(utf8, 0, payload, 4, utf8.length);
        payload[4 + utf8.length] = 0;
        return new Node(key, 0, false, payload);
    }

    private static Document parse(byte[] raw) throws IOException {
        if (raw.length < HEADER_SIZE) throw new IOException("Truncated DSON header");
        ByteBuffer buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        if (buf.getInt(0) != MAGIC) throw new IOException("Invalid DSON magic");
        int headerLen = buf.getInt(0x08);
        if (headerLen != HEADER_SIZE) throw new IOException("Unsupported DSON header length: " + headerLen);
        int meta1Size = buf.getInt(0x10);
        int meta1Count = buf.getInt(0x14);
        int meta1Offset = buf.getInt(0x18);
        int meta2Count = buf.getInt(0x2c);
        int meta2Offset = buf.getInt(0x30);
        int dataSize = buf.getInt(0x38);
        int dataOffset = buf.getInt(0x3c);

        if (meta1Count <= 0 || meta2Count <= 0
                || meta1Size != meta1Count * 16
                || meta1Offset + meta1Size > raw.length
                || meta2Offset + meta2Count * 12 > raw.length
                || dataOffset + dataSize > raw.length) {
            throw new IOException("Malformed DSON section table");
        }

        int[] m1Direct = new int[meta1Count];
        int[] m1Meta2 = new int[meta1Count];
        for (int i = 0; i < meta1Count; i++) {
            int pos = meta1Offset + i * 16;
            m1Meta2[i] = buf.getInt(pos + 4);
            m1Direct[i] = buf.getInt(pos + 8);
        }

        int[] m2Offset = new int[meta2Count];
        int[] m2Info = new int[meta2Count];
        for (int i = 0; i < meta2Count; i++) {
            int pos = meta2Offset + i * 12;
            m2Offset[i] = buf.getInt(pos + 4);
            m2Info[i] = buf.getInt(pos + 8);
        }

        int[] cursor = {0};
        Node root = readNode(raw, dataOffset, dataSize, m1Direct, m2Offset, m2Info, cursor);
        if (cursor[0] != meta2Count) {
            throw new IOException("DSON tree size mismatch: read " + cursor[0] + " of " + meta2Count);
        }
        return new Document(Arrays.copyOf(raw, 16), root);
    }

    private static Node readNode(byte[] raw, int dataOffset, int dataSize,
                                 int[] m1Direct, int[] m2Offset, int[] m2Info, int[] cursor) throws IOException {
        int idx = cursor[0]++;
        if (idx >= m2Info.length) throw new IOException("Unexpected end of DSON Meta2 table");
        int info = m2Info[idx];
        boolean isObj = (info & 1) != 0;
        int extraFlags = info & 0x80000002;
        int nameLen = (info >>> 2) & 0x1FF;
        int off = m2Offset[idx];
        int nextOff = (idx + 1 < m2Offset.length) ? m2Offset[idx + 1] : dataSize;
        if (nameLen <= 0 || off < 0 || off + nameLen > dataSize || nextOff < off + nameLen || nextOff > dataSize) {
            throw new IOException("Invalid DSON field bounds at index " + idx);
        }
        String name = new String(raw, dataOffset + off, nameLen - 1, StandardCharsets.UTF_8);
        if (isObj) {
            int meta1Idx = (info >>> 11) & 0xFFFFF;
            if (meta1Idx < 0 || meta1Idx >= m1Direct.length) {
                throw new IOException("Invalid DSON Meta1 index " + meta1Idx);
            }
            int directCount = m1Direct[meta1Idx];
            List<Node> children = new ArrayList<>(directCount);
            for (int c = 0; c < directCount; c++) {
                children.add(readNode(raw, dataOffset, dataSize, m1Direct, m2Offset, m2Info, cursor));
            }
            return new Node(name, extraFlags, children);
        } else {
            int valStart = off + nameLen;
            int valLen = nextOff - valStart;
            if (valLen == 1) {
                return new Node(name, extraFlags, true, new byte[]{raw[dataOffset + valStart]});
            }
            int pad = (4 - (valStart & 3)) & 3;
            if (pad > valLen) pad = 0;
            byte[] payload = Arrays.copyOfRange(raw, dataOffset + valStart + pad, dataOffset + nextOff);
            return new Node(name, extraFlags, false, payload);
        }
    }

    private static final class Meta1Entry {
        final int parent;
        final int meta2Idx;
        final int directCount;
        int allDescendants;

        Meta1Entry(int parent, int meta2Idx, int directCount) {
            this.parent = parent;
            this.meta2Idx = meta2Idx;
            this.directCount = directCount;
        }
    }

    private static final class Meta2Entry {
        final int hash;
        final int offset;
        final int info;

        Meta2Entry(int hash, int offset, int info) {
            this.hash = hash;
            this.offset = offset;
            this.info = info;
        }
    }

    private static byte[] serialize(Document doc) throws IOException {
        List<Meta1Entry> meta1 = new ArrayList<>();
        List<Meta2Entry> meta2 = new ArrayList<>();
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        writeNode(doc.root, -1, meta1, meta2, data);

        byte[] dataBytes = data.toByteArray();
        int meta1Size = meta1.size() * 16;
        int meta1Offset = HEADER_SIZE;
        int meta2Offset = meta1Offset + meta1Size;
        int dataOffset = meta2Offset + meta2.size() * 12;

        ByteBuffer out = ByteBuffer.allocate(dataOffset + dataBytes.length).order(ByteOrder.LITTLE_ENDIAN);
        out.put(doc.headerPrefix, 0, 16);
        out.putInt(0x08, HEADER_SIZE);
        out.putInt(0x10, meta1Size);
        out.putInt(0x14, meta1.size());
        out.putInt(0x18, meta1Offset);
        out.putInt(0x1c, 0);
        out.putInt(0x20, 0);
        out.putInt(0x24, 0);
        out.putInt(0x28, 0);
        out.putInt(0x2c, meta2.size());
        out.putInt(0x30, meta2Offset);
        out.putInt(0x34, 0);
        out.putInt(0x38, dataBytes.length);
        out.putInt(0x3c, dataOffset);

        out.position(meta1Offset);
        for (Meta1Entry m : meta1) {
            out.putInt(m.parent);
            out.putInt(m.meta2Idx);
            out.putInt(m.directCount);
            out.putInt(m.allDescendants);
        }
        out.position(meta2Offset);
        for (Meta2Entry m : meta2) {
            out.putInt(m.hash);
            out.putInt(m.offset);
            out.putInt(m.info);
        }
        out.position(dataOffset);
        out.put(dataBytes);
        return out.array();
    }

    private static int writeNode(Node node, int parentMeta1, List<Meta1Entry> meta1,
                                 List<Meta2Entry> meta2, ByteArrayOutputStream data) throws IOException {
        byte[] nameBytes = node.name.getBytes(StandardCharsets.UTF_8);
        int nameLen = nameBytes.length + 1;
        if (nameLen > 0x1FF) throw new IOException("DSON key too long: " + node.name);
        int hash = hash(nameBytes);
        int offset = data.size();
        int meta2Idx = meta2.size();

        data.write(nameBytes);
        data.write(0);

        if (node.object) {
            int meta1Idx = meta1.size();
            Meta1Entry m1 = new Meta1Entry(parentMeta1, meta2Idx, node.children.size());
            meta1.add(m1);
            int info = 1 | (nameLen << 2) | (meta1Idx << 11) | node.extraFlags;
            meta2.add(new Meta2Entry(hash, offset, info));
            int descendants = 0;
            for (Node child : node.children) {
                descendants += 1 + writeNode(child, meta1Idx, meta1, meta2, data);
            }
            m1.allDescendants = descendants;
            return descendants;
        } else {
            int info = (nameLen << 2) | node.extraFlags;
            meta2.add(new Meta2Entry(hash, offset, info));
            if (node.singleByte) {
                data.write(node.payload[0]);
            } else {
                int pad = (4 - (data.size() & 3)) & 3;
                for (int i = 0; i < pad; i++) data.write(0);
                data.write(node.payload);
            }
            return 0;
        }
    }

    static int hash(byte[] bytes) {
        int h = 0;
        for (byte b : bytes) {
            h = h * 53 + b;
        }
        return h;
    }
}
