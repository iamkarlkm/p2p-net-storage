import json
from pathlib import Path
import sys

root = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(root / "src"))

from p2p_ws_sdk.xor import xor_no_wrap
from p2p_ws_sdk.frame import decode_frame


def hx(s: str) -> bytes:
    return bytes.fromhex(s.strip())


def main() -> int:
    repo = root.parent
    vec = repo / "p2p-ws-protocol" / "test-vectors" / "xor_vector_001.json"
    v = json.loads(vec.read_text(encoding="utf-8"))
    key = hx(v["keyfile_bytes_hex"])
    plain = hx(v["plain_hex"])
    got = xor_no_wrap(plain, key, int(v["offset"])).hex()
    expected = str(v["cipher_hex"]).lower()
    if got != expected:
        raise SystemExit(f"mismatch: got={got} expected={expected}")
    print("ok=1")

    frame_vec = repo / "p2p-ws-protocol" / "test-vectors" / "frame_vector_001.json"
    fv = json.loads(frame_vec.read_text(encoding="utf-8"))
    ws = hx(fv["ws_binary_payload_hex"])
    f = decode_frame(ws)
    h = fv["header"]
    if f.header.length != int(h["length_u32"]):
        raise SystemExit("frame length mismatch")
    if f.header.magic != int(h["magic_u16"]):
        raise SystemExit("frame magic mismatch")
    if f.header.version != int(h["version_u8"]):
        raise SystemExit("frame version mismatch")
    if f.header.flags != int(h["flags_u8"]):
        raise SystemExit("frame flags mismatch")
    if f.cipher_payload.hex() != str(fv["cipher_payload_hex"]).lower():
        raise SystemExit("frame payload mismatch")
    print("ok=2")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
