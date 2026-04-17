import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
VEC_DIR = ROOT / "test-vectors"


def hx(s: str) -> bytes:
    s = s.strip()
    return bytes.fromhex(s)


def xor_bytes(key: bytes, offset: int, plain: bytes) -> bytes:
    if offset < 0 or offset >= len(key):
        raise ValueError("offset out of range")
    if offset + len(plain) > len(key):
        raise ValueError("offset+plainLen exceeds keyLen (no wrap)")
    out = bytearray(len(plain))
    for i, b in enumerate(plain):
        out[i] = b ^ key[offset + i]
    return bytes(out)

def decode_header(frame: bytes) -> tuple[int, int, int, int]:
    if len(frame) < 8:
        raise ValueError("invalid frame")
    length = int.from_bytes(frame[0:4], "big", signed=False)
    magic = int.from_bytes(frame[4:6], "big", signed=False)
    version = frame[6]
    flags = frame[7]
    return length, magic, version, flags


def main() -> int:
    vectors = sorted(VEC_DIR.glob("xor_vector_*.json"))
    if not vectors:
        raise SystemExit("no xor_vector_*.json found")
    ok = 0
    for p in vectors:
        v = json.loads(p.read_text(encoding="utf-8"))
        key = hx(v["keyfile_bytes_hex"])
        offset = int(v["offset"])
        plain = hx(v["plain_hex"])
        expected = v["cipher_hex"].lower()
        got = xor_bytes(key, offset, plain).hex()
        if got != expected:
            raise SystemExit(f"{p.name}: mismatch: got={got} expected={expected}")
        ok += 1

    frame_vectors = sorted(VEC_DIR.glob("frame_vector_*.json"))
    for p in frame_vectors:
        v = json.loads(p.read_text(encoding="utf-8"))
        ws = hx(v["ws_binary_payload_hex"])
        length, magic, version, flags = decode_header(ws)
        h = v["header"]
        if length != int(h["length_u32"]) or magic != int(h["magic_u16"]) or version != int(h["version_u8"]) or flags != int(h["flags_u8"]):
            raise SystemExit(f"{p.name}: header mismatch")
        cipher = ws[8:].hex()
        expected_cipher = str(v["cipher_payload_hex"]).lower()
        if cipher != expected_cipher:
            raise SystemExit(f"{p.name}: payload mismatch: got={cipher} expected={expected_cipher}")
        ok += 1

    print(f"ok={ok}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
