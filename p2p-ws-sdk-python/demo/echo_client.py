from __future__ import annotations

import asyncio
from pathlib import Path
import sys

root = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(root / "src"))

from p2p_ws_sdk.client import P2PClient, P2PClientConfig


async def main() -> int:
    try:
        import yaml  # type: ignore
    except Exception:
        raise SystemExit("PyYAML is required: python -m pip install PyYAML")

    try:
        from cryptography.hazmat.primitives.asymmetric import rsa  # type: ignore
        from cryptography.hazmat.primitives import hashes, serialization  # type: ignore
    except Exception:
        raise SystemExit("cryptography is required: python -m pip install cryptography")

    cfg_path = Path(sys.argv[1]) if len(sys.argv) >= 2 else (root.parent / "p2p-ws-protocol" / "examples" / "client.yaml")
    cfg = yaml.safe_load(cfg_path.read_text(encoding="utf-8"))
    user_id = str(cfg.get("user_id") or "").strip()
    if not user_id:
        raise SystemExit("user_id is required")

    ws_urls_cfg = cfg.get("ws_urls", None)
    if isinstance(ws_urls_cfg, list) and ws_urls_cfg:
        ws_urls = [str(x).strip() for x in ws_urls_cfg if str(x).strip()]
    else:
        ws_url = str(cfg.get("ws_url") or "").strip()
        if not ws_url:
            raise SystemExit("ws_url or ws_urls is required")
        ws_urls = [ws_url]

    magic = int(str(cfg.get("magic", "0x1234")), 0) if isinstance(cfg.get("magic", None), str) else int(cfg.get("magic", 0x1234))
    version = int(cfg.get("version", 1))
    flags_plain = int(cfg.get("flags_plain", 4))
    flags_encrypted = int(cfg.get("flags_encrypted", 5))
    max_frame_payload = int(cfg.get("max_frame_payload", 4 * 1024 * 1024))

    encryption_enabled = bool(cfg.get("encryption_enabled", True))
    encryption_mode = str(cfg.get("encryption_mode", "keyfile") or "keyfile").strip()
    crypto_mode = cfg.get("crypto_mode", None)
    crypto_mode = None if crypto_mode is None else str(crypto_mode).strip()
    random_key_bytes = int(cfg.get("random_key_bytes", 32) or 32)

    keyfile_path = None
    keyfile_path_raw = str(cfg.get("keyfile_path") or "").strip()
    if keyfile_path_raw:
        p = Path(keyfile_path_raw)
        if not p.is_absolute():
            p = (cfg_path.parent / p).resolve()
        keyfile_path = str(p)

    key_id_sha256_hex = None
    kid_v = cfg.get("key_id_sha256_hex", None)
    if kid_v is not None:
        key_id_sha256_hex = str(kid_v).strip().lower()

    rsa_private_key_pem_path = None
    pem_v = cfg.get("rsa_private_key_pem_path", None)
    if pem_v is not None:
        rsa_private_key_pem_path = str(pem_v).strip()

    if rsa_private_key_pem_path:
        p = Path(rsa_private_key_pem_path)
        if not p.is_absolute():
            p = (cfg_path.parent / p).resolve()
        private_key_pem = p.read_bytes()
        priv = serialization.load_pem_private_key(private_key_pem, password=None)
    else:
        priv = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        private_key_pem = priv.private_bytes(
            encoding=serialization.Encoding.PEM,
            format=serialization.PrivateFormat.PKCS8,
            encryption_algorithm=serialization.NoEncryption(),
        )
    pub = priv.public_key()
    pub_der = pub.public_bytes(encoding=serialization.Encoding.DER, format=serialization.PublicFormat.SubjectPublicKeyInfo)

    c = P2PClient(
        config=P2PClientConfig(
            user_id=user_id,
            ws_urls=ws_urls,
            magic=magic,
            version=version,
            flags_plain=flags_plain,
            flags_encrypted=flags_encrypted,
            max_frame_payload=max_frame_payload,
            encryption_enabled=encryption_enabled,
            encryption_mode=encryption_mode,
            crypto_mode=crypto_mode,
            random_key_bytes=random_key_bytes,
            keyfile_path=keyfile_path,
            key_id_sha256_hex=key_id_sha256_hex,
        ),
        private_key_pem=private_key_pem,
        client_pubkey_der=pub_der,
    )
    await c.connect()
    try:
        w = await c.request(command=1, data=b"hello", expected_command=1)
        print("echo=" + w.data.decode("utf-8"))
        return 0
    finally:
        await c.close()


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
