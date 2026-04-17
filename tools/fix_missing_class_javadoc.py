import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]

TYPE_RE = re.compile(
    r"(?m)^[ \t]*(?:public|protected|private)?[ \t]*"
    r"(?:abstract[ \t]+|final[ \t]+|static[ \t]+|sealed[ \t]+|non-sealed[ \t]+)*"
    r"(class|interface|enum|record|@interface)[ \t]+([A-Za-z_]\w*)\b"
)


def has_class_level_javadoc_before(text: str, decl_start: int) -> bool:
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


def find_insertion_point(text: str, decl_start: int) -> int:
    prefix = text[:decl_start]
    lines = prefix.splitlines(True)
    i = len(lines) - 1
    while i >= 0:
        s = lines[i].strip()
        if not s:
            i -= 1
            continue
        if s.startswith("@"):
            i -= 1
            continue
        break
    return len("".join(lines[: i + 1]).rstrip()) + 1


def build_javadoc(type_name: str) -> str:
    return f"/**\n * {type_name}。\n */\n"


def main() -> int:
    changed = 0
    for p in sorted(ROOT.glob("**/src/main/java/**/*.java")):
        if "p2p-db" in p.parts:
            continue
        text = p.read_text(encoding="utf-8", errors="ignore")
        m = TYPE_RE.search(text)
        if m is None:
            continue
        if has_class_level_javadoc_before(text, m.start()):
            continue
        type_name = m.group(2)
        insert_at = find_insertion_point(text, m.start())
        new_text = text[:insert_at] + build_javadoc(type_name) + text[insert_at:]
        if new_text != text:
            p.write_text(new_text, encoding="utf-8")
            changed += 1
    print(f"changed={changed}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
