from __future__ import annotations

import base64
import hashlib
from pathlib import Path
import sys


def main() -> int:
    if len(sys.argv) < 2:
        raise SystemExit("usage: python derive_node_key.py <rsa_private_key_pem_path>")
    p = Path(sys.argv[1])
    try:
        from cryptography.hazmat.primitives import serialization  # type: ignore
    except Exception:
        raise SystemExit("cryptography is required: python -m pip install cryptography")

    key = serialization.load_pem_private_key(p.read_bytes(), password=None)
    pub = key.public_key()
    spki_der = pub.public_bytes(encoding=serialization.Encoding.DER, format=serialization.PublicFormat.SubjectPublicKeyInfo)
    node_key = hashlib.sha256(spki_der).digest()
    print("pubkey_spki_der_base64=" + base64.b64encode(spki_der).decode("ascii"))
    print("node_key_sha256_hex=" + node_key.hex())
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

