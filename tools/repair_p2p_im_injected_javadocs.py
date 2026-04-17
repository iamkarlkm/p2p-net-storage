import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
IM_SRC = ROOT / "p2p-im" / "src" / "main" / "java"

INJECTED_ANYWHERE_RE = re.compile(
    r"(?s)/\*\*\s*\r?\n\s*\*\s*[A-Za-z_]\w*。\s*\r?\n\s*\*/\s*\r?\n?"
)


def main() -> int:
    changed = 0
    for p in sorted(IM_SRC.glob("**/*.java")):
        text = p.read_text(encoding="utf-8", errors="ignore")
        new_text, n = INJECTED_ANYWHERE_RE.subn("", text)
        if n:
            p.write_text(new_text, encoding="utf-8")
            changed += 1
    print(f"changed_files={changed}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

