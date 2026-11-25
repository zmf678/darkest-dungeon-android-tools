"""Fast PNG -> ASTC 4x4 (wrapped in Wicked's DDS/DXT3 header) encoder.

Wicked's libddnx.so (nxgl_CompressedTexImage2D) remaps GL_COMPRESSED_RGBA_S3TC_DXT3_EXT (0x83f2)
directly to GL_COMPRESSED_RGBA_ASTC_4x4_KHR (0x93b0), so every .dds asset in assets/nx/ uses a
128-byte DXT3 DDS header followed by 16-byte-per-block ASTC 4x4 payload.

Encoding priority:
  1. Official ARM astcenc (bundled in dd-steam-converter/bin/ or on PATH) -> reference multi-partition
     & dual-plane ASTC 4x4 (~51-55 dB PSNR, identical visual quality to original Switch assets).
  2. Vectorized NumPy fallback -> exact Void-Extent for constant blocks, CEM=8 (RGB direct, 8-bit
     endpoints, 3-bit 8-level weights) for opaque blocks, and CEM=12 (RGBA direct, 8-bit endpoints,
     2-bit 4-level weights) for alpha-varying blocks, with least-squares endpoint refinement and
     strict R1+G1+B1 >= R0+G0+B0 ordering to prevent unintended ASTC Blue Contraction.
"""

from __future__ import annotations

from functools import lru_cache
from pathlib import Path
import shutil
import struct
import subprocess
import tempfile
from typing import Optional
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

_ASTCENC_CANDIDATES = (
    "astcenc-avx2.exe",
    "astcenc-sse4.1.exe",
    "astcenc-sse2.exe",
    "astcenc.exe",
    "astcenc-avx2",
    "astcenc-sse4.1",
    "astcenc-sse2",
    "astcenc",
)


@lru_cache(maxsize=1)
def find_astcenc() -> Optional[str]:
    """Locate official ARM astcenc binary in local converter bin/ directory or system PATH."""
    local_bin = Path(__file__).resolve().parents[1] / "bin"
    if local_bin.is_dir():
        for name in _ASTCENC_CANDIDATES:
            candidate = local_bin / name
            if candidate.is_file():
                return str(candidate)
    for name in _ASTCENC_CANDIDATES:
        found = shutil.which(name)
        if found:
            return found
    return None


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


def _fit_endpoints_and_weights(
    blocks_f: np.ndarray,
    max_weight: int,
) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    """Fit linear endpoints (e0_u8, e1_u8) and integer weights in [0, max_weight].

    Guarantees (R1 + G1 + B1) >= (R0 + G0 + B0) on every block so ASTC CEM=8/CEM=12
    decoders never trigger unintended Blue Contraction (which swaps endpoints and pulls
    R/G toward B).
    """
    m = len(blocks_f)
    mean = blocks_f.mean(axis=1, keepdims=True)
    centered = blocks_f - mean

    # Dominant principal direction via 2-step power iteration from max-norm pixel
    norms2 = np.sum(centered * centered, axis=2)  # (M, 16)
    far_idx = np.argmax(norms2, axis=1)
    m_arange = np.arange(m)
    axis = centered[m_arange, far_idx].copy()
    for _ in range(2):
        proj = np.einsum("mnc,mc->mn", centered, axis)
        next_axis = np.einsum("mnc,mn->mc", centered, proj)
        ax_norm = np.linalg.norm(next_axis, axis=1, keepdims=True)
        valid = ax_norm[:, 0] > 1e-6
        axis[valid] = next_axis[valid] / ax_norm[valid]

    proj = np.einsum("mnc,mc->mn", centered, axis)
    e0 = blocks_f[m_arange, np.argmin(proj, axis=1)].copy()
    e1 = blocks_f[m_arange, np.argmax(proj, axis=1)].copy()

    # Initial projection & 1-step least-squares endpoint refinement
    d = e1 - e0
    denom = np.sum(d * d, axis=1, keepdims=True)
    denom = np.where(denom < 1e-6, 1.0, denom)
    t = np.clip(np.sum((blocks_f - e0[:, None, :]) * d[:, None, :], axis=2) / denom, 0.0, 1.0)
    wq = np.round(t * float(max_weight)) / float(max_weight)

    omw = 1.0 - wq
    a = np.sum(omw * omw, axis=1)
    b = np.sum(wq * omw, axis=1)
    c = np.sum(wq * wq, axis=1)
    det = a * c - b * b
    ok = det > 1e-4
    if np.any(ok):
        p0 = np.einsum("mn,mnc->mc", omw[ok], blocks_f[ok])
        p1 = np.einsum("mn,mnc->mc", wq[ok], blocks_f[ok])
        inv_det = (1.0 / det[ok])[:, None]
        e0[ok] = (c[ok, None] * p0 - b[ok, None] * p1) * inv_det
        e1[ok] = (a[ok, None] * p1 - b[ok, None] * p0) * inv_det

    e0_i = np.clip(np.round(e0), 0, 255).astype(np.int32)
    e1_i = np.clip(np.round(e1), 0, 255).astype(np.int32)

    # CRITICAL ASTC spec rule (CEM=8 & CEM=12): enforce R1+G1+B1 >= R0+G0+B0
    # to avoid Blue Contraction & endpoint swap in hardware ASTC decoders.
    sum0 = e0_i[:, 0] + e0_i[:, 1] + e0_i[:, 2]
    sum1 = e1_i[:, 0] + e1_i[:, 1] + e1_i[:, 2]
    swap = sum1 < sum0
    if np.any(swap):
        e0_fix = np.where(swap[:, None], e1_i, e0_i)
        e1_fix = np.where(swap[:, None], e0_i, e1_i)
        e0_i, e1_i = e0_fix, e1_fix

    e0_f = e0_i.astype(np.float32)
    e1_f = e1_i.astype(np.float32)
    d = e1_f - e0_f
    denom = np.sum(d * d, axis=1, keepdims=True)
    denom = np.where(denom < 1e-6, 1.0, denom)
    t = np.sum((blocks_f - e0_f[:, None, :]) * d[:, None, :], axis=2) / denom
    weights = np.clip(np.round(t * float(max_weight)), 0, max_weight).astype(np.uint64)

    return e0_i.astype(np.uint64), e1_i.astype(np.uint64), weights


def _bit_reverse_32(w32: np.ndarray) -> np.ndarray:
    b0 = _REV_BYTE_LUT[w32 & np.uint64(0xFF)]
    b1 = _REV_BYTE_LUT[(w32 >> np.uint64(8)) & np.uint64(0xFF)]
    b2 = _REV_BYTE_LUT[(w32 >> np.uint64(16)) & np.uint64(0xFF)]
    b3 = _REV_BYTE_LUT[(w32 >> np.uint64(24)) & np.uint64(0xFF)]
    return (b0 << np.uint64(24)) | (b1 << np.uint64(16)) | (b2 << np.uint64(8)) | b3


def _bit_reverse_48(w48: np.ndarray) -> np.ndarray:
    b0 = _REV_BYTE_LUT[w48 & np.uint64(0xFF)]
    b1 = _REV_BYTE_LUT[(w48 >> np.uint64(8)) & np.uint64(0xFF)]
    b2 = _REV_BYTE_LUT[(w48 >> np.uint64(16)) & np.uint64(0xFF)]
    b3 = _REV_BYTE_LUT[(w48 >> np.uint64(24)) & np.uint64(0xFF)]
    b4 = _REV_BYTE_LUT[(w48 >> np.uint64(32)) & np.uint64(0xFF)]
    b5 = _REV_BYTE_LUT[(w48 >> np.uint64(40)) & np.uint64(0xFF)]
    return (
        (b0 << np.uint64(40))
        | (b1 << np.uint64(32))
        | (b2 << np.uint64(24))
        | (b3 << np.uint64(16))
        | (b4 << np.uint64(8))
        | b5
    )


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
    blocks_u8 = arr.reshape(bh, 4, bw, 4, 4).transpose(0, 2, 1, 3, 4).reshape(-1, 16, 4)
    n_blocks = len(blocks_u8)

    lo64 = np.zeros(n_blocks, dtype=np.uint64)
    hi64 = np.zeros(n_blocks, dtype=np.uint64)
    mask_64 = np.uint64(0xFFFFFFFFFFFFFFFF)

    # 1. Constant blocks -> exact lossless ASTC Void-Extent
    const_mask = np.all(blocks_u8 == blocks_u8[:, 0:1, :], axis=(1, 2))
    if np.any(const_mask):
        cpx = blocks_u8[const_mask, 0, :].astype(np.uint64) * np.uint64(257)
        lo64[const_mask] = np.uint64(0xFFFFFFFFFFFFFDFC)
        hi64[const_mask] = (
            cpx[:, 0]
            | (cpx[:, 1] << np.uint64(16))
            | (cpx[:, 2] << np.uint64(32))
            | (cpx[:, 3] << np.uint64(48))
        )

    var_mask = ~const_mask

    # 2. Opaque variable blocks (all A == 255) -> CEM=8 (FMT_RGB_DIRECT), mode=0x053 (3-bit 8-level weights)
    opaque_var_mask = var_mask & np.all(blocks_u8[:, :, 3] == 255, axis=1)
    if np.any(opaque_var_mask):
        vb_rgb = blocks_u8[opaque_var_mask, :, :3].astype(np.float32)
        e0_u8, e1_u8, weights = _fit_endpoints_and_weights(vb_rgb, max_weight=7)
        ep_u64 = (
            e0_u8[:, 0]
            | (e1_u8[:, 0] << np.uint64(8))
            | (e0_u8[:, 1] << np.uint64(16))
            | (e1_u8[:, 1] << np.uint64(24))
            | (e0_u8[:, 2] << np.uint64(32))
            | (e1_u8[:, 2] << np.uint64(40))
        )
        shifts = (np.arange(16, dtype=np.uint64) * np.uint64(3))[None, :]
        w48 = np.bitwise_or.reduce(weights << shifts, axis=1)
        w48_rev = _bit_reverse_48(w48)
        lo64[opaque_var_mask] = (np.uint64(0x10053) | (ep_u64 << np.uint64(17))) & mask_64
        hi64[opaque_var_mask] = (ep_u64 >> np.uint64(47)) | (w48_rev << np.uint64(16))

    # 3. Alpha-varying blocks -> CEM=12 (FMT_RGBA_DIRECT), mode=0x042 (2-bit 4-level weights)
    alpha_var_mask = var_mask & (~opaque_var_mask)
    if np.any(alpha_var_mask):
        vb_rgba = blocks_u8[alpha_var_mask].astype(np.float32)
        e0_u8, e1_u8, weights = _fit_endpoints_and_weights(vb_rgba, max_weight=3)
        ep_u64 = (
            e0_u8[:, 0]
            | (e1_u8[:, 0] << np.uint64(8))
            | (e0_u8[:, 1] << np.uint64(16))
            | (e1_u8[:, 1] << np.uint64(24))
            | (e0_u8[:, 2] << np.uint64(32))
            | (e1_u8[:, 2] << np.uint64(40))
            | (e0_u8[:, 3] << np.uint64(48))
            | (e1_u8[:, 3] << np.uint64(56))
        )
        shifts = (np.arange(16, dtype=np.uint64) * np.uint64(2))[None, :]
        w32 = np.bitwise_or.reduce(weights << shifts, axis=1)
        w32_rev = _bit_reverse_32(w32)
        lo64[alpha_var_mask] = (np.uint64(0x18042) | (ep_u64 << np.uint64(17))) & mask_64
        hi64[alpha_var_mask] = (ep_u64 >> np.uint64(47)) | (w32_rev << np.uint64(32))

    out = np.empty(n_blocks, dtype=np.dtype([("lo", "<u8"), ("hi", "<u8")]))
    out["lo"] = lo64
    out["hi"] = hi64
    return _build_dds_header(w, h, b"DXT3") + out.tobytes()


# Backward-compatible alias
encode_rgba_to_dxt3_bytes = encode_rgba_to_astc4x4_dds_bytes


def _run_astcenc(astcenc: str, input_png: Path, out_astc: Path, quality: str = "-fast") -> bool:
    proc = subprocess.run(
        [astcenc, "-cl", str(input_png), str(out_astc), "4x4", quality, "-j", "1", "-silent"],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    return proc.returncode == 0 and out_astc.is_file()


def png_bytes_to_dds(png_path: Path, prefer_texconv: bool = True, quality: str = "-fast") -> bytes:
    """Convert a PNG image into Wicked-compatible ASTC 4x4 DDS bytes."""
    if prefer_texconv:
        astcenc = find_astcenc()
        if astcenc:
            with Image.open(png_path) as im:
                w, h = im.size
                needs_normalize = im.mode not in ("RGB", "RGBA")
            expected_payload = max(1, (w + 3) // 4) * max(1, (h + 3) // 4) * 16
            with tempfile.TemporaryDirectory(prefix="dd-astc-") as td:
                td_path = Path(td)
                out_astc = td_path / "out.astc"
                src_for_cli = png_path
                if needs_normalize:
                    src_for_cli = td_path / "norm.png"
                    with Image.open(png_path) as im:
                        im.convert("RGBA").save(src_for_cli, format="PNG")
                ok = _run_astcenc(astcenc, src_for_cli, out_astc, quality=quality)
                if not ok and not needs_normalize:
                    # Retry once after normalizing exotic/malformed PNG chunks via Pillow
                    norm_png = td_path / "norm.png"
                    with Image.open(png_path) as im:
                        im.convert("RGBA").save(norm_png, format="PNG")
                    ok = _run_astcenc(astcenc, norm_png, out_astc, quality=quality)
                if ok:
                    raw = out_astc.read_bytes()
                    if len(raw) == 16 + expected_payload:
                        return _build_dds_header(w, h, b"DXT3") + raw[16:]
    with Image.open(png_path) as img:
        return encode_rgba_to_astc4x4_dds_bytes(img)
