from __future__ import annotations


def xor_no_wrap(plain: bytes, keyfile: bytes | bytearray | memoryview, offset: int) -> bytes:
    if offset < 0 or offset >= len(keyfile):
        raise ValueError("offset out of range")
    if offset + len(plain) > len(keyfile):
        raise ValueError("offset+plainLen exceeds keyLen (no wrap)")
    out = bytearray(len(plain))
    for i, b in enumerate(plain):
        out[i] = b ^ keyfile[offset + i]
    return bytes(out)


def xor_repeat(data: bytes, key: bytes) -> bytes:
    if not key:
        raise ValueError("key must not be empty")
    out = bytearray(len(data))
    klen = len(key)
    for i, b in enumerate(data):
        out[i] = b ^ key[i % klen]
    return bytes(out)
