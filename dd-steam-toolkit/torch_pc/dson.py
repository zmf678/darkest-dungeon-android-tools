"""Lossless binary DSON parser and serializer matching Red Hook's persist.*.json format."""

from __future__ import annotations

from dataclasses import dataclass, field
import struct
from typing import Any, Dict, List, Optional, Tuple

MAGIC = 0x0000B101
HEADER_SIZE = 64


def dd_hash(name: str | bytes) -> int:
    """Compute Red Hook signed-byte hash (h = h * 53 + (int8)b)."""
    raw = name.encode("utf-8") if isinstance(name, str) else name
    h = 0
    for b in raw:
        sb = b - 256 if b >= 128 else b
        h = (h * 53 + sb) & 0xFFFFFFFF
    return h - 0x100000000 if h >= 0x80000000 else h


@dataclass
class DsonNode:
    name: str
    is_object: bool
    extra_flags: int = 0
    children: List["DsonNode"] = field(default_factory=list)
    single_byte: bool = False
    payload: bytes = b""

    def child(self, key: str) -> Optional["DsonNode"]:
        if not self.is_object:
            return None
        for c in self.children:
            if c.name == key:
                return c
        return None

    def set_child(self, node: "DsonNode") -> None:
        if not self.is_object:
            raise TypeError(f"Cannot set child on scalar node: {self.name}")
        for idx, c in enumerate(self.children):
            if c.name == node.name:
                self.children[idx] = node
                return
        self.children.append(node)

    def remove_child(self, key: str) -> bool:
        if not self.is_object:
            return False
        for idx, c in enumerate(self.children):
            if c.name == key:
                del self.children[idx]
                return True
        return False

    def ensure_object(self, key: str) -> "DsonNode":
        existing = self.child(key)
        if existing is not None and existing.is_object:
            return existing
        created = DsonNode(name=key, is_object=True)
        self.set_child(created)
        return created

    def as_int(self) -> int:
        if self.is_object or len(self.payload) < 4:
            return 0
        return struct.unpack_from("<i", self.payload, 0)[0]

    def as_float(self) -> float:
        if self.is_object or len(self.payload) < 4:
            return 0.0
        return struct.unpack_from("<f", self.payload, 0)[0]

    def as_byte(self) -> int:
        if self.is_object or not self.payload:
            return 0
        return self.payload[0]

    def as_bool(self) -> bool:
        return self.as_byte() != 0

    def is_string_payload(self) -> bool:
        if self.is_object or len(self.payload) < 5:
            return False
        slen = struct.unpack_from("<I", self.payload, 0)[0]
        return slen > 0 and 4 + slen == len(self.payload) and self.payload[-1] == 0

    def as_string(self) -> str:
        if self.is_object or len(self.payload) < 5:
            return ""
        slen = struct.unpack_from("<I", self.payload, 0)[0]
        if slen <= 0 or 4 + slen > len(self.payload):
            return ""
        end = 4 + slen
        if self.payload[end - 1] == 0:
            end -= 1
        return self.payload[4:end].decode("utf-8", errors="replace")

    def as_embedded_dson(self) -> Optional[bytes]:
        if self.is_object or len(self.payload) < 4 + HEADER_SIZE:
            return None
        inner_len = struct.unpack_from("<I", self.payload, 0)[0]
        if inner_len < HEADER_SIZE or 4 + inner_len > len(self.payload):
            return None
        inner = self.payload[4 : 4 + inner_len]
        return inner if is_dson(inner) else None

    def set_int(self, val: int) -> None:
        self.is_object = False
        self.single_byte = False
        self.payload = struct.pack("<i", int(val))

    def set_float(self, val: float) -> None:
        self.is_object = False
        self.single_byte = False
        self.payload = struct.pack("<f", float(val))

    def set_byte(self, val: int) -> None:
        self.is_object = False
        self.single_byte = True
        self.payload = bytes([int(val) & 0xFF])

    def set_bool(self, val: bool) -> None:
        self.set_byte(1 if val else 0)

    def set_string(self, val: str) -> None:
        raw = (val or "").encode("utf-8") + b"\x00"
        self.is_object = False
        self.single_byte = False
        self.payload = struct.pack("<I", len(raw)) + raw

    def set_embedded_dson(self, dson_bytes: bytes) -> None:
        self.is_object = False
        self.single_byte = False
        self.payload = struct.pack("<I", len(dson_bytes)) + dson_bytes


def int_node(name: str, val: int) -> DsonNode:
    n = DsonNode(name=name, is_object=False)
    n.set_int(val)
    return n


def float_node(name: str, val: float) -> DsonNode:
    n = DsonNode(name=name, is_object=False)
    n.set_float(val)
    return n


def byte_node(name: str, val: int) -> DsonNode:
    n = DsonNode(name=name, is_object=False, single_byte=True)
    n.set_byte(val)
    return n


def bool_node(name: str, val: bool) -> DsonNode:
    return byte_node(name, 1 if val else 0)


def string_node(name: str, val: str) -> DsonNode:
    n = DsonNode(name=name, is_object=False)
    n.set_string(val)
    return n


@dataclass
class DsonDocument:
    header_prefix: bytes  # first 16 bytes
    root: DsonNode


def is_dson(raw: bytes) -> bool:
    return len(raw) >= HEADER_SIZE and struct.unpack_from("<I", raw, 0)[0] == MAGIC


def parse_dson(raw: bytes) -> DsonDocument:
    if len(raw) < HEADER_SIZE:
        raise ValueError("Truncated DSON header")
    magic, _flags, header_len = struct.unpack_from("<III", raw, 0)
    if magic != MAGIC:
        raise ValueError(f"Invalid DSON magic: {magic:#x}")
    if header_len != HEADER_SIZE:
        raise ValueError(f"Unsupported DSON header length: {header_len}")

    meta1_size, meta1_count, meta1_offset = struct.unpack_from("<III", raw, 0x10)
    meta2_count, meta2_offset = struct.unpack_from("<II", raw, 0x2C)
    data_size, data_offset = struct.unpack_from("<II", raw, 0x38)

    if (
        meta1_count <= 0
        or meta2_count <= 0
        or meta1_size != meta1_count * 16
        or meta1_offset + meta1_size > len(raw)
        or meta2_offset + meta2_count * 12 > len(raw)
        or data_offset + data_size > len(raw)
    ):
        raise ValueError("Malformed DSON section table")

    m1_direct = [struct.unpack_from("<I", raw, meta1_offset + i * 16 + 8)[0] for i in range(meta1_count)]
    m2 = [struct.unpack_from("<iII", raw, meta2_offset + i * 12) for i in range(meta2_count)]

    cursor = [0]

    def read_node() -> DsonNode:
        idx = cursor[0]
        if idx >= meta2_count:
            raise ValueError("Unexpected end of DSON Meta2 table")
        cursor[0] += 1
        _h, off, info = m2[idx]
        is_obj = (info & 1) != 0
        extra_flags = info & 0x80000002
        name_len = (info >> 2) & 0x1FF
        next_off = m2[idx + 1][1] if idx + 1 < meta2_count else data_size
        if name_len <= 0 or off < 0 or off + name_len > data_size or next_off < off + name_len:
            raise ValueError(f"Invalid DSON field bounds at index {idx}")
        name = raw[data_offset + off : data_offset + off + name_len - 1].decode("utf-8", errors="replace")
        if is_obj:
            meta1_idx = (info >> 11) & 0xFFFFF
            if meta1_idx >= meta1_count:
                raise ValueError(f"Invalid DSON Meta1 index {meta1_idx}")
            children = [read_node() for _ in range(m1_direct[meta1_idx])]
            return DsonNode(name=name, is_object=True, extra_flags=extra_flags, children=children)
        val_start = off + name_len
        val_len = next_off - val_start
        if val_len == 1:
            return DsonNode(
                name=name,
                is_object=False,
                extra_flags=extra_flags,
                single_byte=True,
                payload=raw[data_offset + val_start : data_offset + val_start + 1],
            )
        pad = (4 - (val_start & 3)) & 3
        if pad > val_len:
            pad = 0
        payload = raw[data_offset + val_start + pad : data_offset + next_off]
        return DsonNode(name=name, is_object=False, extra_flags=extra_flags, single_byte=False, payload=payload)

    root = read_node()
    if cursor[0] != meta2_count:
        raise ValueError(f"DSON tree size mismatch: read {cursor[0]} of {meta2_count}")
    return DsonDocument(header_prefix=raw[:16], root=root)


def serialize_dson(doc: DsonDocument) -> bytes:
    meta1: List[List[int]] = []
    meta2: List[Tuple[int, int, int]] = []
    data = bytearray()

    def write_node(node: DsonNode, parent_meta1: int) -> int:
        name_bytes = node.name.encode("utf-8")
        name_len = len(name_bytes) + 1
        if name_len > 0x1FF:
            raise ValueError(f"DSON key too long: {node.name}")
        h = dd_hash(name_bytes)
        offset = len(data)
        meta2_idx = len(meta2)

        data.extend(name_bytes)
        data.append(0)

        if node.is_object:
            meta1_idx = len(meta1)
            entry = [parent_meta1, meta2_idx, len(node.children), 0]
            meta1.append(entry)
            info = 1 | (name_len << 2) | (meta1_idx << 11) | (node.extra_flags & 0xFFFFFFFF)
            meta2.append((h, offset, info & 0xFFFFFFFF))
            descendants = 0
            for child in node.children:
                descendants += 1 + write_node(child, meta1_idx)
            entry[3] = descendants
            return descendants
        info = (name_len << 2) | (node.extra_flags & 0xFFFFFFFF)
        meta2.append((h, offset, info & 0xFFFFFFFF))
        if node.single_byte:
            data.append(node.payload[0])
        else:
            pad = (4 - (len(data) & 3)) & 3
            if pad:
                data.extend(b"\x00" * pad)
            data.extend(node.payload)
        return 0

    write_node(doc.root, -1)

    meta1_size = len(meta1) * 16
    meta1_offset = HEADER_SIZE
    meta2_offset = meta1_offset + meta1_size
    data_offset = meta2_offset + len(meta2) * 12

    out = bytearray(data_offset + len(data))
    out[:16] = doc.header_prefix[:16]
    struct.pack_into(
        "<IIIIIIIIIIIIII",
        out,
        0x08,
        HEADER_SIZE,
        0,
        meta1_size,
        len(meta1),
        meta1_offset,
        0,
        0,
        0,
        0,
        len(meta2),
        meta2_offset,
        0,
        len(data),
        data_offset,
    )
    for idx, (parent, m2_idx, direct_cnt, all_desc) in enumerate(meta1):
        struct.pack_into("<iiii", out, meta1_offset + idx * 16, parent, m2_idx, direct_cnt, all_desc)
    for idx, (h, off, info) in enumerate(meta2):
        struct.pack_into("<iII", out, meta2_offset + idx * 12, h, off, info)
    out[data_offset:] = data
    return bytes(out)


def _extract_applied(applied_node: Optional[DsonNode]) -> List[Tuple[str, str]]:
    out: List[Tuple[str, str]] = []
    if applied_node and applied_node.is_object:
        for item in applied_node.children:
            if not item.is_object:
                continue
            n = item.child("name")
            s = item.child("source")
            if n:
                name = n.as_string()
                src = s.as_string() if s else ""
                if name:
                    out.append((name, src))
    return out


def _build_mod_items(mods: List[Tuple[str, str]]) -> List[DsonNode]:
    return [
        DsonNode(
            name=str(idx),
            is_object=True,
            children=[string_node("name", name), string_node("source", src)],
        )
        for idx, (name, src) in enumerate(mods)
    ]


def read_applied_mods(raw: bytes) -> List[Tuple[str, str]]:
    doc = parse_dson(raw)
    return _extract_applied(doc.root.child("applied_ugcs_1_0"))


def write_applied_mods(raw: bytes, applied: List[Tuple[str, str]]) -> bytes:
    """Update applied_ugcs_1_0 and merge into persistent_ugcs preserving each mod's source ('Steam' or 'mod')."""
    if not is_dson(raw):
        raise ValueError("Unsupported save format (expected binary DSON)")
    doc = parse_dson(raw)
    root = doc.root

    existing = root.child("applied_ugcs_1_0")
    flags = existing.extra_flags if existing is not None else 0
    replacement = DsonNode("applied_ugcs_1_0", True, flags, children=_build_mod_items(applied))
    if existing is not None:
        idx = root.children.index(existing)
        root.children[idx] = replacement
    else:
        insert_at = len(root.children)
        for i, ch in enumerate(root.children):
            if ch.name in ("persistent_ugcs", "presented_dlc"):
                insert_at = i
                break
        root.children.insert(insert_at, replacement)

    if applied:
        persistent = root.child("persistent_ugcs")
        if persistent is None or not persistent.is_object:
            persistent = DsonNode("persistent_ugcs", True)
            insert_at = len(root.children)
            for i, ch in enumerate(root.children):
                if ch.name == "presented_dlc":
                    insert_at = i
                    break
            root.children.insert(insert_at, persistent)

        p_existing = persistent.child("applied_ugcs_1_0")
        merged = list(_extract_applied(p_existing))
        seen = set(merged)
        for pair in applied:
            if pair not in seen:
                seen.add(pair)
                merged.append(pair)
        p_flags = p_existing.extra_flags if p_existing is not None else 0
        p_rep = DsonNode("applied_ugcs_1_0", True, p_flags, children=_build_mod_items(merged))
        if p_existing is not None:
            persistent.children[persistent.children.index(p_existing)] = p_rep
        else:
            persistent.children.append(p_rep)

    return serialize_dson(doc)


def to_editable_dict(node: DsonNode) -> Any:
    """Convert a DSON tree (including nested embedded hero DSONs) to a JSON-serializable structure."""
    if node.is_object:
        return {c.name: to_editable_dict(c) for c in node.children}
    if node.single_byte:
        return bool(node.payload[0]) if node.payload[0] in (0, 1) else {"_u8": node.payload[0]}
    emb = node.as_embedded_dson()
    if emb is not None:
        return {"_embedded_dson": to_editable_dict(parse_dson(emb).root)}
    if node.is_string_payload():
        return node.as_string()
    if len(node.payload) == 4:
        iv = node.as_int()
        fv = node.as_float()
        if abs(iv) > 100000 and not (fv != fv) and abs(fv) < 1e7 and abs(fv) >= 1e-4:
            return round(fv, 6)
        return iv
    return {"_hex": node.payload.hex()}
