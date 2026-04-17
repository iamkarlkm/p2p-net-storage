from __future__ import annotations

import asyncio
import hashlib
from pathlib import Path
import sys

root = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(root / "src"))

from p2p_ws_sdk.frame import decode_frame, encode_frame, WireHeader
from p2p_ws_sdk.xor import xor_no_wrap


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
        from cryptography.hazmat.primitives.asymmetric import padding  # type: ignore
        from cryptography.hazmat.primitives import hashes, serialization  # type: ignore
    except Exception:
        raise SystemExit("cryptography is required: python -m pip install cryptography")

    try:
        from p2p_ws_sdk.gen import p2p_wrapper_pb2, p2p_control_pb2  # type: ignore
    except Exception:
        raise SystemExit("pb2 is required: python p2p-ws-sdk-python/scripts/gen_proto.py")

    cfg_path = Path(sys.argv[1]) if len(sys.argv) >= 2 else (root.parent / "p2p-ws-protocol" / "examples" / "center_client.yaml")
    cfg = yaml.safe_load(cfg_path.read_text(encoding="utf-8"))

    ws_url = str(cfg.get("ws_url"))
    user_id_raw = cfg.get("user_id")
    node_id64 = int(str(user_id_raw), 10) if user_id_raw is not None else 0
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
    crypto_mode = str(cfg.get("crypto_mode", "KEYFILE_XOR_RSA_OAEP")).strip()
    reported_endpoints_cfg = cfg.get("reported_endpoints", [])
    cache_v = cfg.get("presence_cache_path", "")
    presence_cache_path = ("" if cache_v is None else str(cache_v)).strip()
    renew_seconds = int(cfg.get("renew_seconds", 0) or 0)
    renew_count = int(cfg.get("renew_count", 0) or 0)
    if renew_seconds > 0 and renew_count <= 0:
        renew_count = 3

    h = hashlib.sha256()
    with keyfile_path.open("rb") as f:
        while True:
            b = f.read(1024 * 1024)
            if not b:
                break
            h.update(b)
    key_id = h.digest()
    if expected_key_id_hex and key_id.hex() != expected_key_id_hex:
        raise SystemExit(f"key_id_sha256_hex mismatch: got={key_id.hex()} expected={expected_key_id_hex}")

    if not rsa_private_key_pem_path:
        raise SystemExit("rsa_private_key_pem_path required for center join demo")
    pem_path = Path(rsa_private_key_pem_path)
    if not pem_path.is_absolute():
        pem_path = (cfg_path.parent / pem_path).resolve()
    priv = serialization.load_pem_private_key(pem_path.read_bytes(), password=None)
    pub = priv.public_key()
    pub_der = pub.public_bytes(encoding=serialization.Encoding.DER, format=serialization.PublicFormat.SubjectPublicKeyInfo)

    node_key = hashlib.sha256(pub_der).digest()

    async with websockets.connect(ws_url) as ws:
        keyf = keyfile_path.open("rb")
        cached_observed = None
        cached_observed_key = ""
        if presence_cache_path:
            p = Path(presence_cache_path)
            if not p.is_absolute():
                p = (cfg_path.parent / p).resolve()
            try:
                j = __import__("json").loads(p.read_text(encoding="utf-8"))
                oe = j.get("observed_endpoint") if isinstance(j, dict) else None
                if isinstance(oe, dict) and oe.get("transport") and oe.get("addr"):
                    cached_observed = {"transport": str(oe["transport"]), "addr": str(oe["addr"])}
                    cached_observed_key = cached_observed["transport"] + "|" + cached_observed["addr"]
            except Exception:
                cached_observed = None

        hand = p2p_control_pb2.Hand(client_pubkey=pub_der, key_ids=[key_id], max_frame_payload=max_frame_payload, client_id=str(node_id64))
        w = p2p_wrapper_pb2.P2PWrapper(seq=1, command=-10001, data=hand.SerializeToString())
        plain = w.SerializeToString()
        await ws.send(encode_frame(WireHeader(len(plain), magic, version, flags_plain), plain))

        offset: int | None = None

        def xor_with_file(data: bytes, off: int) -> bytes:
            keyf.seek(off)
            slice_ = keyf.read(len(data))
            if len(slice_) != len(data):
                raise RuntimeError("read keyfile failed")
            return xor_no_wrap(data, slice_, 0)

        def apply_observed_ip(observed_addr: str, endpoints: list) -> list:
            ip = str(observed_addr).split(":")[0]
            if not ip:
                return endpoints
            out = []
            for e in endpoints:
                if not isinstance(e, dict):
                    continue
                t = str(e.get("transport", ""))
                addr = str(e.get("addr", ""))
                parts = addr.split(":")
                if len(parts) >= 2:
                    out.append({"transport": t, "addr": ip + ":" + ":".join(parts[1:])})
                else:
                    out.append({"transport": t, "addr": addr})
            return out

        ttl_from_ack = 0
        expect_expired = False
        queried_once = False

        async def send_hello(seq: int, reported: list) -> None:
            nonlocal offset
            if offset is None:
                return
            caps = p2p_control_pb2.NodeCaps(
                max_frame_payload=max_frame_payload,
                magic=magic,
                version=version,
                flags_plain=flags_plain,
                flags_encrypted=flags_encrypted,
            )
            body = p2p_control_pb2.CenterHelloBody(
                node_id64=node_id64,
                pubkey_spki_der=pub_der,
                reported_endpoints=reported,
                caps=caps,
                timestamp_ms=int(__import__("time").time() * 1000),
                crypto_mode=crypto_mode,
            )
            sig = priv.sign(body.SerializeToString(), padding.PKCS1v15(), hashes.SHA256())
            hello = p2p_control_pb2.CenterHello(body=body, signature=sig)
            wrap = p2p_wrapper_pb2.P2PWrapper(seq=seq, command=-11001, data=hello.SerializeToString())
            wp = wrap.SerializeToString()
            c = xor_with_file(wp, offset)
            await ws.send(encode_frame(WireHeader(len(c), magic, version, flags_encrypted), c))

        async def send_get_node(seq: int) -> None:
            nonlocal offset
            if offset is None:
                return
            req = p2p_control_pb2.GetNode(node_id64=node_id64, node_key=node_key)
            wrap = p2p_wrapper_pb2.P2PWrapper(seq=seq, command=-11010, data=req.SerializeToString())
            wp = wrap.SerializeToString()
            c = xor_with_file(wp, offset)
            await ws.send(encode_frame(WireHeader(len(c), magic, version, flags_encrypted), c))

        while True:
            msg = await ws.recv()
            if not isinstance(msg, (bytes, bytearray)):
                continue
            f = decode_frame(bytes(msg))
            payload = f.cipher_payload
            plain_payload = payload if offset is None else xor_with_file(payload, offset)
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

                caps = p2p_control_pb2.NodeCaps(
                    max_frame_payload=max_frame_payload,
                    magic=magic,
                    version=version,
                    flags_plain=flags_plain,
                    flags_encrypted=flags_encrypted,
                )
                reported = reported_endpoints_cfg if isinstance(reported_endpoints_cfg, list) else []
                if cached_observed:
                    reported = apply_observed_ip(cached_observed.get("addr", ""), list(reported))
                body = p2p_control_pb2.CenterHelloBody(
                    node_id64=node_id64,
                    pubkey_spki_der=pub_der,
                    reported_endpoints=reported,
                    caps=caps,
                    timestamp_ms=int(__import__("time").time() * 1000),
                    crypto_mode=crypto_mode,
                )
                sig = priv.sign(body.SerializeToString(), padding.PKCS1v15(), hashes.SHA256())
                hello = p2p_control_pb2.CenterHello(body=body, signature=sig)

                wrap = p2p_wrapper_pb2.P2PWrapper(seq=2, command=-11001, data=hello.SerializeToString())
                wrap_plain = wrap.SerializeToString()
                cipher = xor_with_file(wrap_plain, offset)
                await ws.send(encode_frame(WireHeader(len(cipher), magic, version, flags_encrypted), cipher))
                continue

            if back.command == -11002:
                ack = p2p_control_pb2.CenterHelloAck()
                ack.ParseFromString(back.data)
                if ack.node_key != node_key:
                    raise SystemExit("node_key mismatch in ack")
                ttl_from_ack = int(ack.ttl_seconds)
                if ack.observed_endpoint and ack.observed_endpoint.addr:
                    print("center_ack.observed_endpoint=" + str(ack.observed_endpoint.transport) + ":" + str(ack.observed_endpoint.addr))
                    cached_observed_key = str(ack.observed_endpoint.transport) + "|" + str(ack.observed_endpoint.addr)
                    cached_observed = {"transport": str(ack.observed_endpoint.transport), "addr": str(ack.observed_endpoint.addr)}
                    if presence_cache_path:
                        p = Path(presence_cache_path)
                        if not p.is_absolute():
                            p = (cfg_path.parent / p).resolve()
                        try:
                            p.parent.mkdir(parents=True, exist_ok=True)
                            p.write_text(
                                __import__("json").dumps(
                                    {"observed_endpoint": {"transport": str(ack.observed_endpoint.transport), "addr": str(ack.observed_endpoint.addr)}},
                                    indent=2,
                                ),
                                encoding="utf-8",
                            )
                        except Exception:
                            pass

                if offset is not None and ack.observed_endpoint and ack.observed_endpoint.addr:
                    oe_key = str(ack.observed_endpoint.transport) + "|" + str(ack.observed_endpoint.addr)
                    if not (cached_observed_key and cached_observed_key == oe_key):
                        caps = p2p_control_pb2.NodeCaps(
                            max_frame_payload=max_frame_payload,
                            magic=magic,
                            version=version,
                            flags_plain=flags_plain,
                            flags_encrypted=flags_encrypted,
                        )
                        base_reported = reported_endpoints_cfg if isinstance(reported_endpoints_cfg, list) else []
                        applied = apply_observed_ip(str(ack.observed_endpoint.addr), list(base_reported))
                        body2 = p2p_control_pb2.CenterHelloBody(
                            node_id64=node_id64,
                            pubkey_spki_der=pub_der,
                            reported_endpoints=applied,
                            caps=caps,
                            timestamp_ms=int(__import__("time").time() * 1000),
                            crypto_mode=crypto_mode,
                        )
                        sig2 = priv.sign(body2.SerializeToString(), padding.PKCS1v15(), hashes.SHA256())
                        hello2 = p2p_control_pb2.CenterHello(body=body2, signature=sig2)
                        wrap2 = p2p_wrapper_pb2.P2PWrapper(seq=21, command=-11001, data=hello2.SerializeToString())
                        wrap_plain2 = wrap2.SerializeToString()
                        cipher2 = xor_with_file(wrap_plain2, offset)
                        await ws.send(encode_frame(WireHeader(len(cipher2), magic, version, flags_encrypted), cipher2))

                if not queried_once:
                    queried_once = True
                    req = p2p_control_pb2.GetNode(node_id64=node_id64, node_key=ack.node_key)
                    wrap = p2p_wrapper_pb2.P2PWrapper(seq=3, command=-11010, data=req.SerializeToString())
                    wrap_plain = wrap.SerializeToString()
                    cipher = xor_with_file(wrap_plain, offset if offset is not None else 0)
                    await ws.send(encode_frame(WireHeader(len(cipher), magic, version, flags_encrypted), cipher))
                continue

            if back.command == -11011:
                r = p2p_control_pb2.GetNodeAck()
                r.ParseFromString(back.data)
                print("get_node.found=" + str(bool(r.found)).lower())
                if renew_seconds > 0 and not expect_expired:
                    expect_expired = True

                    async def run_and_expire() -> None:
                        reported0 = reported_endpoints_cfg if isinstance(reported_endpoints_cfg, list) else []
                        if cached_observed:
                            reported0 = apply_observed_ip(cached_observed.get("addr", ""), list(reported0))
                        for i in range(renew_count):
                            await asyncio.sleep(renew_seconds)
                            await send_hello(100 + i, reported0)
                        wait_s = (ttl_from_ack + 1) if ttl_from_ack > 0 else 2
                        await asyncio.sleep(wait_s)
                        await send_get_node(300)

                    asyncio.create_task(run_and_expire())
                    continue

                keyf.close()
                return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
