from __future__ import annotations

from pathlib import Path
import sys


def main() -> int:
    if len(sys.argv) < 2:
        raise SystemExit("usage: python gen_rsa_private_key.py <private_key_pem_path> [bits]")
    out = Path(sys.argv[1])
    bits = int(sys.argv[2]) if len(sys.argv) >= 3 else 2048
    out.parent.mkdir(parents=True, exist_ok=True)

    try:
        from cryptography.hazmat.primitives.asymmetric import rsa  # type: ignore
        from cryptography.hazmat.primitives import serialization  # type: ignore
    except Exception:
        raise SystemExit("cryptography is required: python -m pip install cryptography")

    key = rsa.generate_private_key(public_exponent=65537, key_size=bits)
    pem = key.private_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PrivateFormat.PKCS8,
        encryption_algorithm=serialization.NoEncryption(),
    )
    out.write_bytes(pem)
    print("ok=1")
    print("path=" + str(out))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

