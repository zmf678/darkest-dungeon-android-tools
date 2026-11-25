"""Fast vectorized PNG -> ASTC 4x4 (wrapped in Wicked's DDS/DXT3 header) encoder using Pillow + NumPy.

Wicked's libddnx.so (nxgl_CompressedTexImage2D) remaps GL_COMPRESSED_RGBA_S3TC_DXT3_EXT (0x83f2)
directly to GL_COMPRESSED_RGBA_ASTC_4x4_KHR (0x93b0), so every .dds asset in assets/nx/ uses a
128-byte DXT3 DDS header followed by 16-byte-per-block ASTC 4x4 payload.
"""

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

# 256-entry byte bit-reversal LUT for packing ASTC weight streams from bit 127 downward
_REV_BYTE_LUT = np.array([int(f"{b:08b}"[::-1], 2) for b in range(256)], dtype=np.uint64)


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
        1,        # dwDepth
        1,        # dwMipMapCount
    )
    struct.pack_into("<II", hdr, 76, 32, DDPF_FOURCC)
    hdr[84:88] = fourcc
    struct.pack_into("<I", hdr, 108, DDSCAPS_TEXTURE)
    return bytes(hdr)


def encode_rgba_to_astc4x4_dds_bytes(img: Image.Image) -> bytes:
    """Encode a PIL Image into Wicked-compatible ASTC 4x4 wrapped with a 128-byte DXT3 DDS header."""
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
    # Reshape to (N_blocks, 16, 4)
    blocks_u8 = arr.reshape(bh, 4, bw, 4, 4).transpose(0, 2, 1, 3, 4).reshape(-1, 16, 4)
    n_blocks = len(blocks_u8)

    lo64 = np.zeros(n_blocks, dtype=np.uint64)
    hi64 = np.zeros(n_blocks, dtype=np.uint64)

    # Identify constant blocks for exact lossless ASTC Void-Extent encoding
    const_mask = np.all(blocks_u8 == blocks_u8[:, 0:1, :], axis=(1, 2))
    if np.any(const_mask):
        cpx = blocks_u8[const_mask, 0, :].astype(np.uint64) * 257
        lo64[const_mask] = np.uint64(0xFFFFFFFFFFFFFDFC)
        hi64[const_mask] = cpx[:, 0] | (cpx[:, 1] << 16) | (cpx[:, 2] << 32) | (cpx[:, 3] << 48)

    # Variable blocks -> ASTC 4x4 mode=0x042, part=0, cem=12 (0x18042), 8-bit LDR RGBA endpoints, 2-bit weights
    var_mask = ~const_mask
    if np.any(var_mask):
        vb = blocks_u8[var_mask].astype(np.float32)  # (M, 16, 4)
        mean = vb.mean(axis=1, keepdims=True)
        centered = vb - mean

        # Dominant 4D principal direction via 2-step power iteration starting from max-norm pixel
        norms2 = np.sum(centered * centered, axis=2)  # (M, 16)
        far_idx = np.argmax(norms2, axis=1)
        axis = centered[np.arange(len(vb)), far_idx]  # (M, 4)
        for _ in range(2):
            proj = np.einsum("mnc,mc->mn", centered, axis)  # (M, 16)
            next_axis = np.einsum("mnc,mn->mc", centered, proj)
            ax_norm = np.linalg.norm(next_axis, axis=1, keepdims=True)
            valid = ax_norm[:, 0] > 1e-6
            axis[valid] = next_axis[valid] / ax_norm[valid]

        proj = np.einsum("mnc,mc->mn", centered, axis)
        idx_min = np.argmin(proj, axis=1)
        idx_max = np.argmax(proj, axis=1)
        m_arange = np.arange(len(vb))
        e0 = vb[m_arange, idx_min]  # (M, 4)
        e1 = vb[m_arange, idx_max]  # (M, 4)

        # Quantize endpoints to uint8 and project pixels onto segment [e0, e1]
        e0_u8 = np.clip(np.round(e0), 0, 255).astype(np.uint64)
        e1_u8 = np.clip(np.round(e1), 0, 255).astype(np.uint64)

        d = e1 - e0  # (M, 4)
        denom = np.sum(d * d, axis=1, keepdims=True)
        denom = np.where(denom < 1e-6, 1.0, denom)
        t = np.sum((vb - e0[:, None, :]) * d[:, None, :], axis=2) / denom
        weights = np.clip(np.round(t * 3.0), 0, 3).astype(np.uint64)  # (M, 16) in {0, 1, 2, 3}

        # Pack 8 uint8 endpoints at bits 17..80: R0, R1, G0, G1, B0, B1, A0, A1
        ep_u64 = (
            e0_u8[:, 0]
            | (e1_u8[:, 0] << 8)
            | (e0_u8[:, 1] << 16)
            | (e1_u8[:, 1] << 24)
            | (e0_u8[:, 2] << 32)
            | (e1_u8[:, 2] << 40)
            | (e0_u8[:, 3] << 48)
            | (e1_u8[:, 3] << 56)
        )

        # Pack 16 2-bit weights -> bit-reverse 32-bit word -> place at bits 96..127 (hi64 bits 32..63)
        shifts = (np.arange(16, dtype=np.uint64) * 2)[None, :]
        w32 = np.bitwise_or.reduce(weights << shifts, axis=1)
        b0 = _REV_BYTE_LUT[w32 & 0xFF]
        b1 = _REV_BYTE_LUT[(w32 >> 8) & 0xFF]
        b2 = _REV_BYTE_LUT[(w32 >> 16) & 0xFF]
        b3 = _REV_BYTE_LUT[(w32 >> 24) & 0xFF]
        w32_rev = (b0 << 24) | (b1 << 16) | (b2 << 8) | b3

        mask_64 = np.uint64(0xFFFFFFFFFFFFFFFF)
        lo64[var_mask] = (np.uint64(0x18042) | (ep_u64 << np.uint64(17))) & mask_64
        hi64[var_mask] = (ep_u64 >> np.uint64(47)) | (w32_rev << np.uint64(32))

    out = np.empty(n_blocks, dtype=np.dtype([("lo", "<u8"), ("hi", "<u8")]))
    out["lo"] = lo64
    out["hi"] = hi64
    return _build_dds_header(w, h, b"DXT3") + out.tobytes()


# Backward-compatible alias
encode_rgba_to_dxt3_bytes = encode_rgba_to_astc4x4_dds_bytes


def png_bytes_to_dds(png_path: Path, prefer_texconv: bool = True) -> bytes:
    """Convert a PNG image into Wicked-compatible ASTC 4x4 DDS bytes."""
    if prefer_texconv:
        astcenc = shutil.which("astcenc")
        if astcenc:
            with Image.open(png_path) as im:
                w, h = im.size
            with tempfile.TemporaryDirectory(prefix="dd-astc-") as td:
                out_astc = Path(td) / (png_path.stem + ".astc")
                subprocess.run(
                    [astcenc, "-cl", str(png_path), str(out_astc), "4x4", "-medium", "-silent"],
                    check=True,
                    stdout=subprocess.DEVNULL,
                    stderr=subprocess.DEVNULL,
                )
                if out_astc.is_file():
                    raw_astc = out_astc.read_bytes()[16:]  # strip 16-byte .astc file header
                    return _build_dds_header(w, h, b"DXT3") + raw_astc
    with Image.open(png_path) as img:
        return encode_rgba_to_astc4x4_dds_bytes(img)
