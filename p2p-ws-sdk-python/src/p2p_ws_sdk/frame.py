from __future__ import annotations

from dataclasses import dataclass


HEADER_LEN = 8


@dataclass(frozen=True)
class WireHeader:
    length: int
    magic: int
    version: int
    flags: int


@dataclass(frozen=True)
class WireFrame:
    header: WireHeader
    cipher_payload: bytes


def decode_frame(ws_binary_payload: bytes) -> WireFrame:
    if ws_binary_payload is None or len(ws_binary_payload) < HEADER_LEN:
        raise ValueError("invalid frame")
    length = int.from_bytes(ws_binary_payload[0:4], "big", signed=False)
    magic = int.from_bytes(ws_binary_payload[4:6], "big", signed=False)
    version = ws_binary_payload[6]
    flags = ws_binary_payload[7]
    cipher_payload = ws_binary_payload[8:]
    return WireFrame(WireHeader(length, magic, version, flags), cipher_payload)


def encode_frame(header: WireHeader, cipher_payload: bytes) -> bytes:
    if cipher_payload is None:
        cipher_payload = b""
    b = bytearray()
    b += int(header.length).to_bytes(4, "big", signed=False)
    b += int(header.magic).to_bytes(2, "big", signed=False)
    b += bytes([int(header.version) & 0xFF])
    b += bytes([int(header.flags) & 0xFF])
    b += cipher_payload
    return bytes(b)

