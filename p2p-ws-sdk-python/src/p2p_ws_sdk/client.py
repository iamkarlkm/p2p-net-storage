"""novel-writer 业务协议的 P2P WebSocket 客户端。

对齐 p2p-core 的实际帧格式：
  - 8B 头: length:u32 + magic:u32 (big-endian)
  - payload: Protostuff 序列化的 P2PWrapper（wire format 兼容 protobuf）

支持的握手加密模式（crypto_mode）：
  0 = CLIENT_RANDOM（客户端生成 XOR key，用服务端公钥加密）
  1 = SERVER_RANDOM（服务端生成 XOR key 并加密后返回；本端解密）
  2 = KEYFILE（双方共享 keyfile，按服务端返回的 offset 开始）
  3 = PLAIN（不加密）
"""
from __future__ import annotations

import asyncio
import logging
import secrets
import time
from dataclasses import dataclass
from typing import Any, Awaitable, Callable, Optional

import websockets

from p2p_ws_sdk.frame import (
    DEFAULT_MAGIC,
    HEADER_LEN,
    WireHeader,
    decode_frame,
    encode_frame,
)
from p2p_ws_sdk.handshake import (
    rsa_oaep_sha256_decrypt,
    rsa_oaep_sha256_encrypt,
)


def rsa_pkcs1v15_encrypt(public_key_pem: bytes, plain: bytes) -> bytes:
    """RSA/ECB/PKCS1Padding 加密单块（对齐 Java RSAUtils.encryptByPublicKey 单 block 加密）。

    注意：RSA 单块明文最大 = keySize 字节 - 11（PKCS1v15 padding overhead）。
    超长数据请用 rsa_pkcs1v15_encrypt_large。
    """
    from cryptography.hazmat.primitives import serialization
    from cryptography.hazmat.primitives.asymmetric import padding
    key = serialization.load_pem_public_key(public_key_pem)
    return key.encrypt(plain, padding.PKCS1v15())


def rsa_pkcs1v15_encrypt_large(public_key_pem: bytes, plain: bytes) -> bytes:
    """RSA/ECB/PKCS1Padding 大块加密（对齐 Java AuthCrypto.rsaEncryptLargeWithPublic）。

    用于 xorKey 等可能超过单块 245 字节（2048-bit key）的数据。
    """
    from cryptography.hazmat.primitives import serialization
    from cryptography.hazmat.primitives.asymmetric import padding
    pub = serialization.load_pem_public_key(public_key_pem)
    key_size_bytes = (pub.key_size + 7) // 8
    max_plain = key_size_bytes - 11  # PKCS1v15 overhead
    chunks = []
    for off in range(0, len(plain), max_plain):
        block = plain[off:off + max_plain]
        chunks.append(pub.encrypt(block, padding.PKCS1v15()))
    return b"".join(chunks)


def rsa_pkcs1v15_decrypt(private_key_pem: bytes, cipher: bytes) -> bytes:
    """RSA/ECB/PKCS1Padding 解密。"""
    from cryptography.hazmat.primitives import serialization
    from cryptography.hazmat.primitives.asymmetric import padding
    key = serialization.load_pem_private_key(private_key_pem, password=None)
    return key.decrypt(cipher, padding.PKCS1v15())
from p2p_ws_sdk.wrapper import P2PWrapper, decode_wrapper, encode_wrapper
from p2p_ws_sdk.xor import xor_repeat

log = logging.getLogger("p2p-ws-sdk.client")


# ============== 配置 ==============

@dataclass
class P2PClientConfig:
    """P2P 客户端配置。"""

    ws_url: str                          # ws://host:port/path
    magic: int = DEFAULT_MAGIC            # 协议魔数（默认 0xF0F00F0F）
    user_id: str = ""                    # 客户端 userId（用于握手/登录）
    secret_key_pem: bytes = b""          # 客户端 RSA 私钥（PKCS8 PEM 字节）
    server_public_key_pem: bytes = b""   # 服务端 RSA 公钥（PEM 字节）
    crypto_mode: int = 3                  # 默认 PLAIN（3）；生产用 CLIENT_RANDOM（0）
    xor_key_length: int = 4096
    request_timeout: float = 60.0
    max_frame_payload: int = 4 * 1024 * 1024
    handshake_timeout: float = 15.0
    login_timeout: float = 15.0


# ============== 命令号常量（与 p2p-core P2PCommand 枚举一致）==============

CMD_STD_OK = 6
CMD_STD_ERROR = -1
CMD_LOGIN = 2
CMD_HAND = 4
CMD_RPC_UNARY = 30000
CMD_RPC_STREAM = 30001
CMD_RPC_EVENT = 30005

# 加密模式
CRYPTO_MODE_CLIENT_RANDOM = 0
CRYPTO_MODE_SERVER_RANDOM = 1
CRYPTO_MODE_KEYFILE = 2
CRYPTO_MODE_PLAIN = 3


# ============== 异常 ==============

class P2PHandshakeError(Exception):
    pass


class P2PLoginError(Exception):
    pass


class P2PConnectionError(Exception):
    pass


# ============== 密钥派生与签名 payload（对齐 p2p-core HandshakePayloads/LoginPayloads）==============

def _sha256(*chunks: bytes) -> bytes:
    import hashlib
    h = hashlib.sha256()
    for c in chunks:
        h.update(c)
    return h.digest()


def derive_xor_key(seed: bytes, user_id: str, nonce: bytes, out_len: int) -> bytes:
    """SHA-256 KDF，对齐 p2p-core AuthCrypto.deriveXorKey。

    material = seed || userId(UTF-8) || nonce
    block_i = SHA256(material || counter_le_u32)
    out = block_0 || block_1 || ... 拼接至 out_len
    """
    if out_len <= 0:
        raise ValueError("out_len must be > 0")
    user = user_id.encode("utf-8") if user_id else b""
    material = seed + user + nonce
    import hashlib
    out = bytearray(out_len)
    pos = 0
    counter = 0
    while pos < out_len:
        h = hashlib.sha256()
        h.update(material)
        h.update(counter.to_bytes(4, "little"))
        block = h.digest()
        n = min(len(block), out_len - pos)
        out[pos:pos + n] = block[:n]
        pos += n
        counter += 1
    return bytes(out)


def build_request_sig_payload_v2(
    timestamp_ms: int,
    xor_key_length: int,
    crypto_mode: int,
    user_id: str,
    nonce: bytes,
    encrypted_xor_key: bytes,
    key_file_id: str = "",
) -> bytes:
    """HandshakeRequest 签名 payload（V2，对齐 HandshakePayloads.requestSigPayloadV2）。

    格式：timestamp:be:i64 || xorKeyLength:be:i32 || cryptoMode:be:i32
           || userId(UTF8) || nonce || SHA256(encryptedXorKey) || SHA256(emptySeed) || keyFileId(UTF8)
    """
    import struct
    user = user_id.encode("utf-8") if user_id else b""
    key_hash = _sha256(encrypted_xor_key)
    seed_hash = _sha256(b"")
    kid = key_file_id.encode("utf-8") if key_file_id else b""
    return (
        struct.pack(">qii", timestamp_ms, xor_key_length, crypto_mode)
        + user + nonce + key_hash + seed_hash + kid
    )


def build_login_sig_payload(timestamp_ms: int, user_id: str) -> bytes:
    """LoginRequest 签名 payload（对齐 LoginPayloads.requestSigPayload）。"""
    import struct
    user = user_id.encode("utf-8") if user_id else b""
    return struct.pack(">q", timestamp_ms) + user


def sha256_rsa_sign(payload: bytes, private_key_pem: bytes) -> bytes:
    """SHA256withRSA + PKCS1v15 签名（对齐 Java Signature.sign）。"""
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import padding
    key = serialization.load_pem_private_key(private_key_pem, password=None)
    return key.sign(payload, padding.PKCS1v15(), hashes.SHA256())


def sha256_rsa_verify(payload: bytes, public_key_pem: bytes, signature: bytes) -> bool:
    """SHA256withRSA + PKCS1v15 验签。"""
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import padding
    key = serialization.load_pem_public_key(public_key_pem)
    try:
        key.verify(signature, payload, padding.PKCS1v15(), hashes.SHA256())
        return True
    except Exception:
        return False


# ============== HandshakeRequest / LoginRequest 编码（Protostuff 兼容 wire format）==============

def _w_varint(buf: bytearray, field_num: int, value: int) -> None:
    """写入 wire type 0 (varint) 字段。"""
    buf.append((field_num << 3) | 0)
    if value < 0:
        value += (1 << 64)
    while value > 0x7F:
        buf.append((value & 0x7F) | 0x80)
        value >>= 7
    buf.append(value & 0x7F)


def _w_bytes(buf: bytearray, field_num: int, data: bytes) -> None:
    """写入 wire type 2 (length-delimited) 字段。"""
    buf.append((field_num << 3) | 2)
    n = len(data)
    while n > 0x7F:
        buf.append((n & 0x7F) | 0x80)
        n >>= 7
    buf.append(n & 0x7F)
    buf += data


def encode_handshake_request(
    user_id: str, timestamp_ms: int, nonce: bytes,
    xor_key_length: int, encrypted_xor_key: bytes, signature: bytes,
    sig_version: int = 2, crypto_mode: int = 0, key_file_id: str = "",
) -> bytes:
    """HandshakeRequest 字段（对齐 p2p-core auth.model.HandshakeRequest）：

      1: userId (string), 2: timestamp (int64), 3: nonce (bytes),
      4: xorKeyLength (int32), 5: encryptedXorKey (bytes), 6: signature (bytes),
      7: sigVersion (int32), 8: cryptoMode (int32), 9: keyFileId (string)
    """
    buf = bytearray()
    user = user_id.encode("utf-8")
    _w_bytes(buf, 1, user)
    _w_varint(buf, 2, timestamp_ms)
    _w_bytes(buf, 3, nonce)
    _w_varint(buf, 4, xor_key_length)
    _w_bytes(buf, 5, encrypted_xor_key)
    _w_bytes(buf, 6, signature)
    _w_varint(buf, 7, sig_version)
    _w_varint(buf, 8, crypto_mode)
    if key_file_id:
        _w_bytes(buf, 9, key_file_id.encode("utf-8"))
    return bytes(buf)


def encode_login_request(user_id: str, timestamp_ms: int, signature: bytes) -> bytes:
    """LoginRequest 字段：1: userId, 2: timestamp, 3: signature。"""
    buf = bytearray()
    _w_bytes(buf, 1, user_id.encode("utf-8"))
    _w_varint(buf, 2, timestamp_ms)
    _w_bytes(buf, 3, signature)
    return bytes(buf)


def _r_varint(data: bytes, pos: int) -> tuple:
    result = 0
    shift = 0
    while pos < len(data):
        b = data[pos]
        pos += 1
        result |= (b & 0x7F) << shift
        if not (b & 0x80):
            if result & (1 << 63):
                result -= (1 << 64)
            return result, pos
        shift += 7
    return result, pos


def decode_handshake_response(blob: bytes) -> dict:
    """HandshakeResponse 字段：
       1: ok (bool), 2: error (string), 3: userId (string),
       4: serverTime (int64), 5: nonce (bytes), 6: xorKeyLength (int32),
       7: encryptedSeed (bytes), 8: signature (bytes), 9: sigVersion (int32),
       10: cryptoMode (int32), 11: keyFileId (string), 12: keyFileOffset (int32)
    """
    out = {"ok": False, "error": "", "user_id": "", "nonce": b"",
           "encrypted_seed": b"", "signature": b"", "key_file_id": "",
           "key_file_offset": 0}
    pos = 0
    while pos < len(blob):
        tag = blob[pos]; pos += 1
        field_num = tag >> 3
        wire_type = tag & 0x07
        if wire_type == 0:
            val, pos = _r_varint(blob, pos)
            if field_num == 1: out["ok"] = bool(val)
            elif field_num == 4: out["server_time"] = val
            elif field_num == 6: out["xor_key_length"] = val
            elif field_num == 9: out["sig_version"] = val
            elif field_num == 10: out["crypto_mode"] = val
            elif field_num == 12: out["key_file_offset"] = val
        elif wire_type == 2:
            length, pos = _r_varint(blob, pos)
            data = blob[pos:pos + length]; pos += length
            if field_num == 2: out["error"] = data.decode("utf-8", errors="replace")
            elif field_num == 3: out["user_id"] = data.decode("utf-8", errors="replace")
            elif field_num == 5: out["nonce"] = data
            elif field_num == 7: out["encrypted_seed"] = data
            elif field_num == 8: out["signature"] = data
            elif field_num == 11: out["key_file_id"] = data.decode("utf-8", errors="replace")
        else:
            break
    return out


def decode_login_response(blob: bytes) -> dict:
    """LoginResponse 字段：1: ok, 2: error, 3: userId, 4: serverTime, 5: signature。"""
    out = {"ok": False, "error": "", "user_id": "", "server_time": 0, "signature": b""}
    pos = 0
    while pos < len(blob):
        tag = blob[pos]; pos += 1
        field_num = tag >> 3
        wire_type = tag & 0x07
        if wire_type == 0:
            val, pos = _r_varint(blob, pos)
            if field_num == 1: out["ok"] = bool(val)
            elif field_num == 4: out["server_time"] = val
        elif wire_type == 2:
            length, pos = _r_varint(blob, pos)
            data = blob[pos:pos + length]; pos += length
            if field_num == 2: out["error"] = data.decode("utf-8", errors="replace")
            elif field_num == 3: out["user_id"] = data.decode("utf-8", errors="replace")
            elif field_num == 5: out["signature"] = data
        else:
            break
    return out


# ============== P2PClient 主体 ==============

class P2PClient:
    """novel-writer P2P WebSocket 客户端。

    用法：
        client = P2PClient(P2PClientConfig(
            ws_url="ws://127.0.0.1:6060/p2p",
            user_id="alice",
            secret_key_pem=open("client.pem", "rb").read(),
            server_public_key_pem=open("server.pub", "rb").read(),
            crypto_mode=CRYPTO_MODE_CLIENT_RANDOM,
        ))
        await client.connect()
        resp = await client.request("CREATE_PROJECT", {"title": "..."})
        await client.close()
    """

    def __init__(self, config: P2PClientConfig):
        self.config = config
        self._ws = None
        self._connected = False
        self._xor_key: Optional[bytes] = None
        self._xor_offset: int = 0
        self._seq_counter = 0
        self._pending: dict = {}            # seq → asyncio.Future
        self._event_handlers: list = []
        self._recv_task: Optional[asyncio.Task] = None
        self._closed = False

    # ---------- 序列号 ----------

    def _alloc_seq(self) -> int:
        self._seq_counter += 1
        return self._seq_counter

    # ---------- 帧编解码（先 XOR 再写头）----------

    def _encode_frame_bytes(self, plain_payload: bytes) -> bytes:
        payload = plain_payload
        if self._xor_key:
            payload = xor_repeat(payload, self._xor_key)
        return encode_frame(WireHeader(len(payload), self.config.magic), payload)

    def _decode_frame_bytes(self, frame: bytes) -> bytes:
        wf = decode_frame(frame)
        if wf.header.magic != self.config.magic:
            raise ValueError(f"magic mismatch: got {wf.header.magic}, expected {self.config.magic}")
        if wf.header.length > len(wf.cipher_payload):
            raise ValueError(
                f"frame truncated: declared={wf.header.length}, have={len(wf.cipher_payload)}"
            )
        payload = wf.cipher_payload[:wf.header.length]
        if self._xor_key:
            payload = xor_repeat(payload, self._xor_key)
        return payload

    # ---------- 收发 ----------

    async def _send_wrapper(self, command: int, data: bytes, seq: Optional[int] = None) -> int:
        """编码 + 发一帧，返回使用的 seq。"""
        if seq is None:
            seq = self._alloc_seq()
        wrapper_bytes = encode_wrapper(P2PWrapper(seq=seq, command=command, data=data))
        frame = self._encode_frame_bytes(wrapper_bytes)
        await self._ws.send(frame)
        return seq

    async def _send_and_wait(
        self, command: int, data: bytes, timeout: Optional[float] = None,
    ) -> bytes:
        """发一帧，等响应（按 seq 配对）。返回响应 data 字段的字节。"""
        seq = self._alloc_seq()
        fut: asyncio.Future = asyncio.get_event_loop().create_future()
        self._pending[seq] = fut
        try:
            await self._send_wrapper(command, data, seq=seq)
            resp_data = await asyncio.wait_for(
                fut, timeout=timeout or self.config.request_timeout
            )
            return resp_data
        finally:
            self._pending.pop(seq, None)

    async def _recv_loop(self) -> None:
        try:
            async for raw in self._ws:
                if not raw:
                    continue
                try:
                    payload = self._decode_frame_bytes(raw)
                    wrapper = decode_wrapper(payload)
                except Exception as e:
                    log.warning(f"[P2PClient] 解析消息失败: {e}")
                    continue

                if wrapper.command == CMD_RPC_EVENT:
                    for h in list(self._event_handlers):
                        try:
                            await h(wrapper.data)
                        except Exception as ex:
                            log.warning(f"[P2PClient] event handler 异常: {ex}")
                else:
                    fut = self._pending.pop(wrapper.seq, None)
                    if fut and not fut.done():
                        if wrapper.command == CMD_STD_ERROR:
                            fut.set_exception(P2PConnectionError(
                                wrapper.data.decode("utf-8", errors="replace") or "STD_ERROR"
                            ))
                        else:
                            fut.set_result(wrapper.data)
        except asyncio.CancelledError:
            raise
        except Exception as e:
            log.error(f"[P2PClient] 接收循环异常: {e}")
            self._connected = False

    # ---------- 连接 ----------

    async def connect(self) -> None:
        """建立 WebSocket + 完整握手（HAND + LOGIN）。"""
        if self._connected:
            return
        log.info(f"[P2PClient] 连接 {self.config.ws_url}")
        self._ws = await websockets.connect(
            self.config.ws_url,
            max_size=self.config.max_frame_payload,
        )
        self._connected = True
        self._recv_task = asyncio.create_task(self._recv_loop())

        if self.config.crypto_mode != CRYPTO_MODE_PLAIN:
            await self._do_handshake()
            await self._do_login()

    async def close(self) -> None:
        if self._closed:
            return
        self._closed = True
        self._connected = False
        if self._recv_task:
            self._recv_task.cancel()
            try:
                await self._recv_task
            except (asyncio.CancelledError, Exception):
                pass
        if self._ws:
            try:
                await self._ws.close()
            except Exception:
                pass

    # ---------- 握手 ----------

    async def _do_handshake(self) -> None:
        """HAND：生成 xor_key，用服务端公钥加密；服务端返回 encryptedSeed/keyFileOffset。"""
        timestamp_ms = int(time.time() * 1000)
        nonce = secrets.token_bytes(16)
        xor_key = secrets.token_bytes(self.config.xor_key_length)

        if self.config.crypto_mode == CRYPTO_MODE_CLIENT_RANDOM:
            # 对齐 p2p-core AuthCrypto.rsaDecryptLargeWithPublic：xorKey 可能超过单 RSA block，
            # 服务端走分块解密；客户端必须用 rsa_pkcs1v15_encrypt_large 分块加密
            encrypted_xor_key = rsa_pkcs1v15_encrypt_large(
                self.config.server_public_key_pem, xor_key
            )
        else:
            # 其他模式加密字段可为空；服务端按模式分发
            encrypted_xor_key = b""

        # 关键：握手请求**始终以明文发送**（服务端收到请求前不知道 xor_key，
        # 无法解密请求）。xor_key 在收到响应后才用于收发其他命令。
        # p2p-core HandServerHandler 也只对响应做加密（如果启用了对应 cryptoMode）。

        sig_payload = build_request_sig_payload_v2(
            timestamp_ms=timestamp_ms,
            xor_key_length=self.config.xor_key_length,
            crypto_mode=self.config.crypto_mode,
            user_id=self.config.user_id,
            nonce=nonce,
            encrypted_xor_key=encrypted_xor_key,
        )
        signature = sha256_rsa_sign(sig_payload, self.config.secret_key_pem)

        req_bytes = encode_handshake_request(
            user_id=self.config.user_id,
            timestamp_ms=timestamp_ms,
            nonce=nonce,
            xor_key_length=self.config.xor_key_length,
            encrypted_xor_key=encrypted_xor_key,
            signature=signature,
            sig_version=2,
            crypto_mode=self.config.crypto_mode,
        )
        # 握手请求明文发送（绕过 _xor_key 加密），因为服务端收到请求前没有 xor_key
        # 重要：CLIENT_RANDOM 模式下，xor_key 在响应到达前**必须已设置**，
        # 因为服务端会用此 xor_key 加密响应（p2p-core HandServerHandler 行为）
        if self.config.crypto_mode == CRYPTO_MODE_CLIENT_RANDOM:
            self._xor_key = xor_key

        seq = self._alloc_seq()
        from p2p_ws_sdk.wrapper import encode_wrapper, P2PWrapper
        wrapper_bytes = encode_wrapper(P2PWrapper(seq=seq, command=CMD_HAND, data=req_bytes))
        plain_frame = encode_frame(WireHeader(len(wrapper_bytes), self.config.magic), wrapper_bytes)
        fut = asyncio.get_event_loop().create_future()
        self._pending[seq] = fut
        try:
            await self._ws.send(plain_frame)
            resp_bytes = await asyncio.wait_for(fut, timeout=self.config.handshake_timeout)
        finally:
            self._pending.pop(seq, None)

        resp = decode_handshake_response(resp_bytes)
        if not resp.get("ok"):
            raise P2PHandshakeError(resp.get("error") or "handshake failed")

        # SERVER_RANDOM 模式：xorKey 由服务端生成，在响应中带回
        if self.config.crypto_mode == CRYPTO_MODE_SERVER_RANDOM:
            from cryptography.hazmat.primitives import serialization
            from cryptography.hazmat.primitives.asymmetric import padding
            priv = serialization.load_pem_private_key(self.config.secret_key_pem, password=None)
            pub_key = priv.public_key()
            key_size_bytes = (pub_key.key_size + 7) // 8
            chunks = []
            for off in range(0, len(resp["encrypted_seed"]), key_size_bytes):
                chunks.append(priv.decrypt(
                    resp["encrypted_seed"][off:off + key_size_bytes],
                    padding.PKCS1v15(),
                ))
            self._xor_key = b"".join(chunks)
        elif self.config.crypto_mode not in (CRYPTO_MODE_CLIENT_RANDOM,):
            self._xor_key = None

        self._xor_offset = int(resp.get("key_file_offset", 0))
        log.info(f"[P2PClient] 握手成功 (mode={self.config.crypto_mode}, keyLen={self.config.xor_key_length})")

    async def _do_login(self) -> None:
        """LOGIN：签名 userId+timestamp，验证服务端响应。"""
        timestamp_ms = int(time.time() * 1000)
        sig_payload = build_login_sig_payload(timestamp_ms, self.config.user_id)
        signature = sha256_rsa_sign(sig_payload, self.config.secret_key_pem)

        req_bytes = encode_login_request(
            user_id=self.config.user_id, timestamp_ms=timestamp_ms, signature=signature
        )
        resp_bytes = await self._send_and_wait(CMD_LOGIN, req_bytes, self.config.login_timeout)
        resp = decode_login_response(resp_bytes)
        if not resp.get("ok"):
            raise P2PLoginError(resp.get("error") or "login failed")

        # 验签（可选）
        if resp.get("signature") and self.config.server_public_key_pem:
            import struct
            expected = struct.pack(">q", int(resp.get("server_time", 0))) + self.config.user_id.encode("utf-8")
            if not sha256_rsa_verify(expected, self.config.server_public_key_pem, resp["signature"]):
                raise P2PHandshakeError("server signature invalid")

        log.info(f"[P2PClient] 登录成功 (user={self.config.user_id})")

    # ---------- 业务调用 ----------

    async def request(
        self,
        command_id: str,
        args: Optional[dict] = None,
        project_id: str = "",
        chapter_id: str = "",
        timeout: Optional[float] = None,
    ) -> bytes:
        """RPC_UNARY 调用，返回响应 data 字节（业务方自行解析 NovelWriterCommandResponse）。

        注意：本方法只负责协议层收发；protobuf 解析由调用方负责（避免循环依赖）。
        """
        from p2p_ws_sdk.gen.novel_writer_pb2 import KeyValue, NovelWriterCommandRequest

        req = NovelWriterCommandRequest(
            command_id=command_id,
            project_id=project_id,
            chapter_id=chapter_id,
        )
        if args:
            for k, v in args.items():
                req.args.append(KeyValue(key=k, value=str(v)))
        return await self._send_and_wait(
            CMD_RPC_UNARY, req.SerializeToString(), timeout
        )

    def on_event(self, handler: Callable[[bytes], Awaitable[None]]) -> None:
        """注册 RPC_EVENT 推送处理器（接收 NovelWriterEvent protobuf 字节）。"""
        self._event_handlers.append(handler)

    async def __aenter__(self) -> "P2PClient":
        await self.connect()
        return self

    async def __aexit__(self, exc_type, exc, tb) -> None:
        await self.close()


# ============== 便捷工厂 ==============

async def connect(
    ws_url: str,
    user_id: str = "",
    secret_key_pem: bytes = b"",
    server_public_key_pem: bytes = b"",
    crypto_mode: int = CRYPTO_MODE_PLAIN,
    xor_key_length: int = 4096,
) -> P2PClient:
    """便捷连接工厂（PLAIN 模式默认）。"""
    cfg = P2PClientConfig(
        ws_url=ws_url,
        user_id=user_id,
        secret_key_pem=secret_key_pem,
        server_public_key_pem=server_public_key_pem,
        crypto_mode=crypto_mode,
        xor_key_length=xor_key_length,
    )
    client = P2PClient(cfg)
    await client.connect()
    return client
