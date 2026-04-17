from __future__ import annotations

import asyncio
from pathlib import Path
import sys

root = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(root / "src"))

from p2p_ws_sdk.frame import decode_frame, encode_frame, WireHeader
from p2p_ws_sdk.xor import xor_no_wrap
from p2p_ws_sdk.keyid import sha256_bytes


async def main() -> int:
    try:
        import websockets  # type: ignore
    except Exception:
        raise SystemExit("websockets is required: python -m pip install websockets")

    try:
        import yaml  # type: ignore
    except Exception:
        raise SystemExit("PyYAML is required: python -m pip install PyYAML")

    try:
        from p2p_ws_sdk.gen import p2p_wrapper_pb2, p2p_control_pb2  # type: ignore
    except Exception:
        raise SystemExit("pb2 is required: python p2p-ws-sdk-python/scripts/gen_proto.py")

    try:
        from cryptography.hazmat.primitives.asymmetric import rsa, padding  # type: ignore
        from cryptography.hazmat.primitives import hashes, serialization  # type: ignore
    except Exception:
        raise SystemExit("cryptography is required: python -m pip install cryptography")

    cfg_path = Path(sys.argv[1]) if len(sys.argv) >= 2 else (root.parent / "p2p-ws-protocol" / "examples" / "client.yaml")
    cfg = yaml.safe_load(cfg_path.read_text(encoding="utf-8"))
    url = str(cfg.get("ws_url"))
    user_id = str(cfg.get("user_id"))
    keyfile_path = Path(str(cfg.get("keyfile_path")))
    if not keyfile_path.is_absolute():
        keyfile_path = (cfg_path.parent / keyfile_path).resolve()
    kid_v = cfg.get("key_id_sha256_hex", "")
    expected_key_id_hex = ("" if kid_v is None else str(kid_v)).strip().lower()
    pem_v = cfg.get("rsa_private_key_pem_path", "")
    rsa_private_key_pem_path = ("" if pem_v is None else str(pem_v)).strip()
    magic = int(str(cfg.get("magic", "0x1234")), 0) if isinstance(cfg.get("magic", None), str) else int(cfg.get("magic", 0x1234))
    version = int(cfg.get("version", 1))
    flags_plain = int(cfg.get("flags_plain", 4))
    flags_encrypted = int(cfg.get("flags_encrypted", 5))
    max_frame_payload = int(cfg.get("max_frame_payload", 4 * 1024 * 1024))

    h = __import__("hashlib").sha256()
    with keyfile_path.open("rb") as f:
        while True:
            b = f.read(1024 * 1024)
            if not b:
                break
            h.update(b)
    key_id = h.digest()
    if expected_key_id_hex and key_id.hex() != expected_key_id_hex:
        raise SystemExit(f"key_id_sha256_hex mismatch: got={key_id.hex()} expected={expected_key_id_hex}")

    if rsa_private_key_pem_path:
        p = Path(rsa_private_key_pem_path)
        if not p.is_absolute():
            p = (cfg_path.parent / p).resolve()
        priv = serialization.load_pem_private_key(p.read_bytes(), password=None)
    else:
        priv = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    pub = priv.public_key()
    pub_der = pub.public_bytes(encoding=serialization.Encoding.DER, format=serialization.PublicFormat.SubjectPublicKeyInfo)

    async with websockets.connect(url) as ws:
        keyf = keyfile_path.open("rb")
        hand = p2p_control_pb2.Hand(client_pubkey=pub_der, key_ids=[key_id], max_frame_payload=max_frame_payload, client_id=user_id)
        w = p2p_wrapper_pb2.P2PWrapper(seq=1, command=-10001, data=hand.SerializeToString())
        plain = w.SerializeToString()
        await ws.send(encode_frame(WireHeader(len(plain), magic, version, flags_plain), plain))

        offset = None
        while True:
            msg = await ws.recv()
            if not isinstance(msg, (bytes, bytearray)):
                continue
            f = decode_frame(bytes(msg))
            payload = f.cipher_payload
            if offset is None:
                plain_payload = payload
            else:
                keyf.seek(offset)
                slice_ = keyf.read(len(payload))
                plain_payload = xor_no_wrap(payload, slice_, 0)
            back = p2p_wrapper_pb2.P2PWrapper()
            back.ParseFromString(plain_payload)

            if back.command == -10002:
                decrypted = priv.decrypt(
                    back.data,
                    padding.OAEP(mgf=padding.MGF1(algorithm=hashes.SHA256()), algorithm=hashes.SHA256(), label=None),
                )
                ack = p2p_control_pb2.HandAckPlain()
                ack.ParseFromString(decrypted)
                offset = int(ack.offset)

                echo = p2p_wrapper_pb2.P2PWrapper(seq=2, command=1, data=b"hello")
                echo_plain = echo.SerializeToString()
                keyf.seek(offset)
                slice_ = keyf.read(len(echo_plain))
                echo_cipher = xor_no_wrap(echo_plain, slice_, 0)
                await ws.send(encode_frame(WireHeader(len(echo_cipher), magic, version, flags_encrypted), echo_cipher))
                continue

            if back.command == -10010:
                cu = p2p_control_pb2.CryptUpdate()
                cu.ParseFromString(back.data)
                offset = int(cu.offset)
                continue

            if back.command == 1 and back.seq == 2:
                print("echo=" + back.data.decode("utf-8"))
                keyf.close()
                return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
