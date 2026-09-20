"""P2PClient SDK 单元测试。"""
from __future__ import annotations

import struct
import pytest

from p2p_ws_sdk.client import (
    P2PClient,
    P2PClientConfig,
    CMD_HAND,
    CMD_LOGIN,
    CMD_RPC_UNARY,
    CMD_STD_ERROR,
    CMD_STD_OK,
    CRYPTO_MODE_CLIENT_RANDOM,
    CRYPTO_MODE_PLAIN,
    DEFAULT_MAGIC,
    build_login_sig_payload,
    build_request_sig_payload_v2,
    decode_handshake_response,
    decode_login_response,
    derive_xor_key,
    encode_handshake_request,
    encode_login_request,
    sha256_rsa_sign,
    sha256_rsa_verify,
)
from p2p_ws_sdk.frame import DEFAULT_MAGIC as FRAME_DEFAULT_MAGIC
from p2p_ws_sdk.frame import decode_frame, encode_frame, WireHeader
from p2p_ws_sdk.wrapper import decode_wrapper, encode_wrapper, P2PWrapper
from p2p_ws_sdk.xor import xor_repeat


# ──────────────────── 配置与常量 ────────────────────

def test_config_defaults():
    cfg = P2PClientConfig(ws_url="ws://127.0.0.1:6060/p2p")
    assert cfg.magic == DEFAULT_MAGIC
    assert cfg.crypto_mode == CRYPTO_MODE_PLAIN
    assert cfg.xor_key_length == 4096
    assert cfg.request_timeout == 60.0
    assert cfg.handshake_timeout == 15.0
    assert cfg.login_timeout == 15.0


def test_default_magic_matches_java():
    """p2p-core P2PWrapperEncoder.MAGIC = 0xF0F00F0F。"""
    assert DEFAULT_MAGIC == 0xF0F00F0F
    assert FRAME_DEFAULT_MAGIC == 0xF0F00F0F


def test_command_constants():
    assert CMD_HAND == 4
    assert CMD_LOGIN == 2
    assert CMD_RPC_UNARY == 30000
    assert CMD_STD_OK == 6
    assert CMD_STD_ERROR == -1


# ──────────────────── 帧头编解码 ────────────────────

def test_frame_header_encode_decode():
    """8B 头：length:u32 + magic:u32 (big-endian)。"""
    payload = b"some-bytes-here"
    frame = encode_frame(WireHeader(length=len(payload), magic=0xF0F00F0F), payload)
    assert len(frame) == 8 + len(payload)
    wf = decode_frame(frame)
    assert wf.header.length == len(payload)
    assert wf.header.magic == 0xF0F00F0F
    assert wf.cipher_payload == payload


def test_frame_header_magic_4bytes():
    """Java ByteBuffer.writeInt 写入 4 字节 big-endian。"""
    raw = struct.pack(">II", 16, 0xF0F00F0F)
    assert len(raw) == 8
    length, magic = struct.unpack(">II", raw)
    assert length == 16
    assert magic == 0xF0F00F0F


def test_frame_empty_payload():
    frame = encode_frame(WireHeader(0, 0xF0F00F0F), b"")
    assert frame == b"\x00\x00\x00\x00\xf0\xf0\x0f\x0f"
    wf = decode_frame(frame)
    assert wf.header.length == 0
    assert wf.cipher_payload == b""


# ──────────────────── XOR ────────────────────

def test_xor_round_trip():
    key = bytes(range(16))
    plain = bytes([0xFF, 0xEE, 0xDD, 0xCC, 0xBB, 0xAA, 0x99, 0x88,
                   0x77, 0x66, 0x55, 0x44, 0x33, 0x22, 0x11, 0x00])
    cipher = xor_repeat(plain, key)
    # XOR 是对称的
    assert xor_repeat(cipher, key) == plain


def test_xor_empty_raises():
    with pytest.raises(ValueError):
        xor_repeat(b"data", b"")


# ──────────────────── 密钥派生 ────────────────────

def test_derive_xor_key_length():
    seed = b"a" * 32
    out = derive_xor_key(seed=seed, user_id="alice", nonce=b"b" * 16, out_len=4096)
    assert len(out) == 4096


def test_derive_xor_key_deterministic():
    seed = b"seed-bytes"
    nonce = b"nonce-bytes-1234"
    k1 = derive_xor_key(seed=seed, user_id="alice", nonce=nonce, out_len=64)
    k2 = derive_xor_key(seed=seed, user_id="alice", nonce=nonce, out_len=64)
    assert k1 == k2


def test_derive_xor_key_different_users():
    seed = b"seed-bytes"
    nonce = b"nonce-bytes-1234"
    k1 = derive_xor_key(seed=seed, user_id="alice", nonce=nonce, out_len=64)
    k2 = derive_xor_key(seed=seed, user_id="bob", nonce=nonce, out_len=64)
    assert k1 != k2


def test_derive_xor_key_different_nonces():
    seed = b"seed"
    k1 = derive_xor_key(seed=seed, user_id="u", nonce=b"n1", out_len=64)
    k2 = derive_xor_key(seed=seed, user_id="u", nonce=b"n2", out_len=64)
    assert k1 != k2


def test_derive_xor_key_invalid_length():
    with pytest.raises(ValueError):
        derive_xor_key(seed=b"", user_id="", nonce=b"", out_len=0)


# ──────────────────── P2PWrapper 编解码 ────────────────────

def test_wrapper_encode_decode_round_trip():
    seq = 42
    command = 30000
    data = b"some-payload-bytes"
    encoded = encode_wrapper(P2PWrapper(seq=seq, command=command, data=data))
    w = decode_wrapper(encoded)
    assert w.seq == seq
    assert w.command == command
    assert w.data == data


def test_wrapper_negative_command():
    encoded = encode_wrapper(P2PWrapper(seq=1, command=-1, data=b"err"))
    w = decode_wrapper(encoded)
    assert w.command == -1


def test_wrapper_large_seq():
    """P2PWrapper.seq 是 int32，最大 2^31-1。"""
    big = (1 << 31) - 1
    encoded = encode_wrapper(P2PWrapper(seq=big, command=30000, data=b""))
    w = decode_wrapper(encoded)
    assert w.seq == big


def test_wrapper_empty_data():
    encoded = encode_wrapper(P2PWrapper(seq=1, command=6, data=b""))
    w = decode_wrapper(encoded)
    assert w.data == b""


# ──────────────────── HandshakeRequest 编码 ────────────────────

def test_handshake_request_basic():
    req = encode_handshake_request(
        user_id="alice", timestamp_ms=1700000000000, nonce=b"\x01" * 16,
        xor_key_length=4096, encrypted_xor_key=b"\x02" * 256, signature=b"\x03" * 256,
        sig_version=2, crypto_mode=0,
    )
    assert isinstance(req, bytes)
    assert len(req) > 200


def test_handshake_response_decode_ok():
    # 手工构造：field 1 (ok=true) varint + field 12 (keyFileOffset=100) varint
    blob = b"\x08\x01\x60\x64"  # field 1 varint 1; field 12 varint 100
    resp = decode_handshake_response(blob)
    assert resp["ok"] is True
    assert resp["key_file_offset"] == 100


def test_handshake_response_decode_error():
    blob = b"\x08\x00\x12\x05hello"  # ok=false, error="hello"
    resp = decode_handshake_response(blob)
    assert resp["ok"] is False
    assert resp["error"] == "hello"


# ──────────────────── LoginRequest 编码 ────────────────────

def test_login_request_basic():
    req = encode_login_request(user_id="alice", timestamp_ms=1700000000000, signature=b"\x99" * 256)
    assert isinstance(req, bytes)
    assert len(req) > 200


def test_login_response_decode_ok():
    blob = b"\x08\x01"  # ok=true
    resp = decode_login_response(blob)
    assert resp["ok"] is True


def test_login_response_decode_error():
    # field 2 (error): tag=0x12, length=0x10 (16 bytes), 然后是 16 字节 UTF-8
    err_text = "permission denied"
    blob = b"\x08\x00\x12" + bytes([len(err_text)]) + err_text.encode("utf-8")
    resp = decode_login_response(blob)
    assert resp["ok"] is False
    assert resp["error"] == err_text


# ──────────────────── 签名 payload 构造 ────────────────────

def test_request_sig_payload_v2_format():
    payload = build_request_sig_payload_v2(
        timestamp_ms=1700000000000, xor_key_length=4096, crypto_mode=0,
        user_id="alice", nonce=b"\x01" * 16, encrypted_xor_key=b"\x02" * 32,
    )
    # 前 8 字节 timestamp (be i64) + 4 字节 xorKeyLength + 4 字节 cryptoMode
    ts, kl, cm = struct.unpack(">qii", payload[:16])
    assert ts == 1700000000000
    assert kl == 4096
    assert cm == 0


def test_login_sig_payload_format():
    payload = build_login_sig_payload(timestamp_ms=1700000000000, user_id="alice")
    ts, = struct.unpack(">q", payload[:8])
    assert ts == 1700000000000
    # 紧接着是 userId UTF-8 字节
    assert payload[8:] == b"alice"


# ──────────────────── RSA 签名往返 ────────────────────

def _generate_rsa_keypair_pem():
    """生成测试用 RSA 密钥对（PEM 字节）。"""
    from cryptography.hazmat.primitives import serialization
    from cryptography.hazmat.primitives.asymmetric import rsa
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    priv_pem = key.private_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PrivateFormat.PKCS8,
        encryption_algorithm=serialization.NoEncryption(),
    )
    pub_pem = key.public_key().public_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PublicFormat.SubjectPublicKeyInfo,
    )
    return priv_pem, pub_pem


def test_sha256_rsa_sign_verify():
    priv_pem, pub_pem = _generate_rsa_keypair_pem()
    payload = b"sign-me"
    sig = sha256_rsa_sign(payload, priv_pem)
    assert sha256_rsa_verify(payload, pub_pem, sig)
    # 篡改后验签失败
    assert not sha256_rsa_verify(payload + b"x", pub_pem, sig)


# ──────────────────── P2PClient 实例化 ────────────────────

def test_p2p_client_init():
    cfg = P2PClientConfig(ws_url="ws://127.0.0.1:6060/p2p", user_id="alice")
    c = P2PClient(cfg)
    assert c._connected is False
    assert c._xor_key is None
    assert c._xor_offset == 0
    assert c._closed is False


def test_p2p_client_alloc_seq_increments():
    cfg = P2PClientConfig(ws_url="ws://127.0.0.1:6060/p2p")
    c = P2PClient(cfg)
    assert c._alloc_seq() == 1
    assert c._alloc_seq() == 2
    assert c._alloc_seq() == 3


def test_p2p_client_encode_without_xor_key_passes_payload_through():
    """无 xor_key 时 payload 应原样写入。"""
    cfg = P2PClientConfig(ws_url="ws://127.0.0.1:6060/p2p")
    c = P2PClient(cfg)
    payload = b"hello-payload"
    frame = c._encode_frame_bytes(payload)
    assert frame == struct.pack(">II", len(payload), 0xF0F00F0F) + payload


def test_p2p_client_encode_with_xor_key_applies_xor():
    cfg = P2PClientConfig(ws_url="ws://127.0.0.1:6060/p2p")
    c = P2PClient(cfg)
    c._xor_key = b"\xAA" * 32
    payload = b"hello-payload"
    frame = c._encode_frame_bytes(payload)
    # 解析帧头
    length, magic = struct.unpack(">II", frame[:8])
    assert length == len(payload)
    assert magic == 0xF0F00F0F
    # 校验 payload 已 XOR
    encrypted_payload = frame[8:8 + length]
    from p2p_ws_sdk.xor import xor_repeat
    assert xor_repeat(encrypted_payload, c._xor_key) == payload


def test_p2p_client_decode_rejects_wrong_magic():
    cfg = P2PClientConfig(ws_url="ws://127.0.0.1:6060/p2p")
    c = P2PClient(cfg)
    bad_frame = struct.pack(">II", 4, 0xDEADBEEF) + b"data"
    with pytest.raises(ValueError, match="magic mismatch"):
        c._decode_frame_bytes(bad_frame)


def test_p2p_client_decode_rejects_truncated():
    cfg = P2PClientConfig(ws_url="ws://127.0.0.1:6060/p2p")
    c = P2PClient(cfg)
    bad_frame = struct.pack(">II", 100, 0xF0F00F0F) + b"short"
    with pytest.raises(ValueError, match="truncated"):
        c._decode_frame_bytes(bad_frame)
