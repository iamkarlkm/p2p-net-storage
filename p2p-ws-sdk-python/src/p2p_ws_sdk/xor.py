from __future__ import annotations


def xor_no_wrap(plain: bytes, keyfile: bytes, offset: int) -> bytes:
    if offset < 0 or offset >= len(keyfile):
        raise ValueError("offset out of range")
    if offset + len(plain) > len(keyfile):
        raise ValueError("offset+plainLen exceeds keyLen (no wrap)")
    out = bytearray(len(plain))
    for i, b in enumerate(plain):
        out[i] = b ^ keyfile[offset + i]
    return bytes(out)

