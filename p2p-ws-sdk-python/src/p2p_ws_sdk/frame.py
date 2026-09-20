from __future__ import annotations

from dataclasses import dataclass


HEADER_LEN = 8
DEFAULT_MAGIC = 0xF0F00F0F  # 与 p2p-core P2PWrapperEncoder.MAGIC 一致


@dataclass(frozen=True)
class WireHeader:
    length: int   # payload 字节数（不含 8B 头）
    magic: int    # 协议魔数（默认 0xF0F00F0F）


@dataclass(frozen=True)
class WireFrame:
    header: WireHeader
    cipher_payload: bytes


def decode_frame(ws_binary_payload: bytes) -> WireFrame:
    """解码 8B 帧头：length:u32(be) + magic:u32(be)。"""
    if ws_binary_payload is None or len(ws_binary_payload) < HEADER_LEN:
        raise ValueError(f"invalid frame: len={len(ws_binary_payload) if ws_binary_payload else 0}")
    length = int.from_bytes(ws_binary_payload[0:4], "big", signed=False)
    magic = int.from_bytes(ws_binary_payload[4:8], "big", signed=False)
    cipher_payload = ws_binary_payload[8:8 + length]
    return WireFrame(WireHeader(length, magic), cipher_payload)


def encode_frame(header: WireHeader, cipher_payload: bytes) -> bytes:
    """编码 8B 帧头 + payload。"""
    if cipher_payload is None:
        cipher_payload = b""
    b = bytearray()
    b += int(header.length).to_bytes(4, "big", signed=False)
    b += int(header.magic).to_bytes(4, "big", signed=False)
    b += cipher_payload
    return bytes(b)
