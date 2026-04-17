from __future__ import annotations


def rsa_oaep_sha256_decrypt(private_key_pem: bytes, cipher: bytes) -> bytes:
    try:
        from cryptography.hazmat.primitives.asymmetric import padding
        from cryptography.hazmat.primitives import hashes, serialization
    except Exception as e:
        raise RuntimeError("cryptography is required for RSA-OAEP") from e

    key = serialization.load_pem_private_key(private_key_pem, password=None)
    return key.decrypt(
        cipher,
        padding.OAEP(mgf=padding.MGF1(algorithm=hashes.SHA256()), algorithm=hashes.SHA256(), label=None),
    )


def rsa_oaep_sha256_encrypt(public_key_pem: bytes, plain: bytes) -> bytes:
    try:
        from cryptography.hazmat.primitives.asymmetric import padding
        from cryptography.hazmat.primitives import hashes, serialization
    except Exception as e:
        raise RuntimeError("cryptography is required for RSA-OAEP") from e

    key = serialization.load_pem_public_key(public_key_pem)
    return key.encrypt(
        plain,
        padding.OAEP(mgf=padding.MGF1(algorithm=hashes.SHA256()), algorithm=hashes.SHA256(), label=None),
    )

