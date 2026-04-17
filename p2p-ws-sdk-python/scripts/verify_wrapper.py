from __future__ import annotations

from pathlib import Path
import sys

root = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(root / "src"))

from p2p_ws_sdk.wrapper import P2PWrapper, decode_wrapper, encode_wrapper


def main() -> int:
    w = P2PWrapper(seq=1, command=123, data=b"\x01\x02\x03")
    data = encode_wrapper(w)
    back = decode_wrapper(data)
    if back.seq != 1:
        raise SystemExit("seq mismatch")
    if back.command != 123:
        raise SystemExit("command mismatch")
    if back.data != b"\x01\x02\x03":
        raise SystemExit("data mismatch")
    print("ok=1")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

