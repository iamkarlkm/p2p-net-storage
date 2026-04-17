import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]

INJECTED_RE = re.compile(r"(?s)/\*\*\s*\r?\n\s*\*\s*[^\r\n]*?。\s*\r?\n\s*\*/\s*\r?\n?")
PACKAGE_RE = re.compile(r"(?m)^\s*package\s+[\w.]+\s*;\s*$")


def main() -> int:
    changed = 0
    for p in sorted(ROOT.glob("**/src/main/java/**/*.java")):
        if "p2p-db" in p.parts:
            continue
        text = p.read_text(encoding="utf-8", errors="ignore")
        m = PACKAGE_RE.search(text)
        if not m:
            continue
        head = text[: m.start()]
        tail = text[m.start() :]
        new_head = INJECTED_RE.sub("", head)
        if new_head != head:
            p.write_text(new_head + tail, encoding="utf-8")
            changed += 1
    print(f"changed={changed}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
