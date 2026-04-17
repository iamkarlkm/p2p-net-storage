import os
import hashlib
from pathlib import Path
import sys


def main() -> int:
    if len(sys.argv) < 2:
        raise SystemExit("usage: python gen_keyfile.py <path> [size_bytes]")
    path = Path(sys.argv[1])
    size = int(sys.argv[2]) if len(sys.argv) >= 3 else 8 * 1024 * 1024
    if size <= 0:
        raise SystemExit("size_bytes must be > 0")

    path.parent.mkdir(parents=True, exist_ok=True)
    h = hashlib.sha256()
    remaining = size
    with path.open("wb") as f:
        while remaining > 0:
            n = min(1024 * 1024, remaining)
            b = os.urandom(n)
            f.write(b)
            h.update(b)
            remaining -= n

    print("sha256_hex=" + h.hexdigest())
    print("bytes=" + str(size))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

