"""Fast vectorized PNG -> DXT3 (BC2) DDS encoder using Pillow + NumPy, with optional texconv support."""

from __future__ import annotations

from pathlib import Path
import shutil
import struct
import subprocess
import tempfile
import numpy as np
from PIL import Image

DDS_MAGIC = b"DDS "
DDSD_CAPS = 0x1
DDSD_HEIGHT = 0x2
DDSD_WIDTH = 0x4
DDSD_PIXELFORMAT = 0x1000
DDSD_LINEARSIZE = 0x80000
DDPF_FOURCC = 0x4
DDSCAPS_TEXTURE = 0x1000


def _build_dds_header(width: int, height: int, fourcc: bytes = b"DXT3") -> bytes:
    pitch = max(1, (width + 3) // 4) * max(1, (height + 3) // 4) * 16
    flags = DDSD_CAPS | DDSD_HEIGHT | DDSD_WIDTH | DDSD_PIXELFORMAT | DDSD_LINEARSIZE
    hdr = bytearray(128)
    hdr[:4] = DDS_MAGIC
    struct.pack_into(
        "<IIIIIII",
        hdr,
        4,
        124,      # dwSize
        flags,    # dwFlags
        height,   # dwHeight
        width,    # dwWidth
        pitch,    # dwPitchOrLinearSize
        0,        # dwDepth
        0,        # dwMipMapCount
    )
    # DDS_PIXELFORMAT at offset 76
    struct.pack_into("<II", hdr, 76, 32, DDPF_FOURCC)
    hdr[84:88] = fourcc
    # dwCaps at offset 108
    struct.pack_into("<I", hdr, 108, DDSCAPS_TEXTURE)
    return bytes(hdr)


def encode_rgba_to_dxt3_bytes(img: Image.Image) -> bytes:
    """Encode a PIL Image into a Switch/DirectX compatible DXT3 (BC2) .dds byte buffer."""
    rgba = img.convert("RGBA")
    w, h = rgba.size
    pad_w = ((w + 3) // 4) * 4
    pad_h = ((h + 3) // 4) * 4

    arr = np.asarray(rgba, dtype=np.uint8)
    if pad_w != w or pad_h != h:
        padded = np.zeros((pad_h, pad_w, 4), dtype=np.uint8)
        padded[:h, :w] = arr
        if pad_w > w:
            padded[:h, w:] = arr[:h, w - 1 : w]
        if pad_h > h:
            padded[h:, :] = padded[h - 1 : h, :]
        arr = padded

    bh = pad_h // 4
    bw = pad_w // 4
    # Reshape into (N_blocks, 16, 4)
    blocks = arr.reshape(bh, 4, bw, 4, 4).transpose(0, 2, 1, 3, 4).reshape(-1, 16, 4)

    # 1. Explicit 4-bit alpha per pixel -> 8 bytes (uint64 LE)
    alpha4 = (blocks[:, :, 3].astype(np.uint16) * 15 + 127) // 255  # (N, 16)
    shifts = (np.arange(16, dtype=np.uint64) * 4)[None, :]
    alpha_bits = np.bitwise_or.reduce(alpha4.astype(np.uint64) << shifts, axis=1)

    # 2. RGB bounding box per block -> c0 (max), c1 (min)
    rgb = blocks[:, :, :3].astype(np.float32)  # (N, 16, 3)
    rgb_min = rgb.min(axis=1)
    rgb_max = rgb.max(axis=1)

    # Inset slightly (1/16) as standard DXT endpoint refinement
    inset = (rgb_max - rgb_min) / 16.0
    c_max = np.clip(rgb_max - inset, 0, 255)
    c_min = np.clip(rgb_min + inset, 0, 255)

    def to_rgb565(c: np.ndarray) -> np.ndarray:
        r = np.clip(np.round(c[:, 0] * (31.0 / 255.0)), 0, 31).astype(np.uint16)
        g = np.clip(np.round(c[:, 1] * (63.0 / 255.0)), 0, 63).astype(np.uint16)
        b = np.clip(np.round(c[:, 2] * (31.0 / 255.0)), 0, 31).astype(np.uint16)
        return (r << 11) | (g << 5) | b

    def from_rgb565(v: np.ndarray) -> np.ndarray:
        r = ((v >> 11) & 31).astype(np.float32) * (255.0 / 31.0)
        g = ((v >> 5) & 63).astype(np.float32) * (255.0 / 63.0)
        b = (v & 31).astype(np.float32) * (255.0 / 31.0)
        return np.stack([r, g, b], axis=-1)

    c0 = to_rgb565(c_max)
    c1 = to_rgb565(c_min)

    # In DXT3, 4-color mode requires c0 > c1; swap if equal or reversed
    swap_mask = c0 < c1
    if np.any(swap_mask):
        tmp = c0[swap_mask].copy()
        c0[swap_mask] = c1[swap_mask]
        c1[swap_mask] = tmp

    p0 = from_rgb565(c0)
    p1 = from_rgb565(c1)
    p2 = (2.0 * p0 + p1) / 3.0
    p3 = (p0 + 2.0 * p1) / 3.0
    palette = np.stack([p0, p1, p2, p3], axis=1)  # (N, 4, 3)

    # Nearest palette index per pixel: (N, 16, 4)
    diff = rgb[:, :, None, :] - palette[:, None, :, :]
    dist2 = np.sum(diff * diff, axis=3)
    best_idx = np.argmin(dist2, axis=2).astype(np.uint32)  # (N, 16)

    # Handle degenerate constant-color blocks where c0 == c1 -> index 0
    same_mask = c0 == c1
    if np.any(same_mask):
        best_idx[same_mask] = 0

    color_shifts = (np.arange(16, dtype=np.uint32) * 2)[None, :]
    color_bits = np.bitwise_or.reduce(best_idx << color_shifts, axis=1)

    # Assemble 16-byte blocks: <Q alpha, <H c0, <H c1, <I indices
    out = np.empty(len(blocks), dtype=np.dtype([("a", "<u8"), ("c0", "<u2"), ("c1", "<u2"), ("idx", "<u4")]))
    out["a"] = alpha_bits
    out["c0"] = c0
    out["c1"] = c1
    out["idx"] = color_bits

    return _build_dds_header(w, h, b"DXT3") + out.tobytes()


def png_bytes_to_dds(png_path: Path, prefer_texconv: bool = True) -> bytes:
    if prefer_texconv:
        texconv = shutil.which("texconv")
        if texconv:
            with tempfile.TemporaryDirectory(prefix="dd-tex-") as td:
                subprocess.run(
                    [texconv, "-f", "BC2_UNORM", "-m", "1", "-nologo", "-y", "-o", td, str(png_path)],
                    check=True,
                    stdout=subprocess.DEVNULL,
                    stderr=subprocess.DEVNULL,
                )
                out_file = Path(td) / (png_path.stem + ".dds")
                if out_file.is_file():
                    return out_file.read_bytes()
    with Image.open(png_path) as img:
        return encode_rgba_to_dxt3_bytes(img)
