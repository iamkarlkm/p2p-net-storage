import re
from collections import defaultdict
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]

TYPE_RE = re.compile(
    r"(?m)^[ \t]*(?:public|protected|private)?[ \t]*"
    r"(?:abstract[ \t]+|final[ \t]+|static[ \t]+|sealed[ \t]+|non-sealed[ \t]+)*"
    r"(class|interface|enum|record|@interface)[ \t]+([A-Za-z_]\w*)\b"
)


def _strip_non_javadoc_block_comments(text: str) -> str:
    def repl(m: re.Match) -> str:
        s = m.group(0)
        return s if s.startswith("/**") else ""

    return re.sub(r"/\*[\s\S]*?\*/", repl, text)


def _has_class_level_javadoc_before(text: str, decl_start: int) -> bool:
    prefix = text[:decl_start].rstrip()
    if not prefix:
        return False

    lines = prefix.splitlines(True)
    i = len(lines) - 1
    while i >= 0:
        line = lines[i].strip()
        if not line:
            i -= 1
            continue
        if line.startswith("@"):
            i -= 1
            continue
        break

    prefix2 = "".join(lines[: i + 1]).rstrip()
    end = prefix2.rfind("*/")
    if end < 0:
        return False
    start = prefix2.rfind("/**", 0, end)
    if start < 0:
        return False
    tail = prefix2[start : end + 2]
    return tail.startswith("/**") and tail.endswith("*/")


def _module_name(path: Path) -> str:
    rel = path.relative_to(ROOT)
    return rel.parts[0] if rel.parts else str(rel)


def main() -> int:
    missing_by_module: dict[str, list[str]] = defaultdict(list)

    for p in ROOT.glob("**/src/main/java/**/*.java"):
        if "p2p-db" in p.parts:
            continue
        try:
            text = p.read_text(encoding="utf-8", errors="ignore")
        except Exception:
            continue

        text = _strip_non_javadoc_block_comments(text)
        m = TYPE_RE.search(text)
        if not m:
            continue

        if not _has_class_level_javadoc_before(text, m.start()):
            missing_by_module[_module_name(p)].append(str(p.relative_to(ROOT)).replace("\\", "/"))

    total = 0
    for mod in sorted(missing_by_module):
        files = sorted(missing_by_module[mod])
        total += len(files)
        print(f"[{mod}] missing={len(files)}")
        for f in files:
            print(f"  - {f}")
    print(f"TOTAL missing={total}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

