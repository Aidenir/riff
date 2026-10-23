#!/usr/bin/env python3
"""Convert gettext .po translations into macOS Localizable.strings files.

The macOS client looks strings up by their English text, the same msgid the
GTK app uses, so every translated msgid carries over unchanged. Plural forms
and untranslated entries are skipped.

Usage: po-to-strings.py <po-dir> <resources-dir>
"""

import sys
from pathlib import Path


def unquote(line: str) -> str:
    value = line[line.index('"') + 1 : line.rindex('"')]
    return bytes(value, "utf-8").decode("unicode_escape").encode("latin-1").decode("utf-8")


def parse_po(path: Path) -> dict:
    entries = {}
    msgid = msgstr = None
    target = None
    fuzzy = False

    def flush():
        if msgid and msgstr and not fuzzy:
            entries[msgid] = msgstr

    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if line.startswith("#,") and "fuzzy" in line:
            fuzzy = True
        elif line.startswith("msgid "):
            flush()
            msgid, msgstr, target, fuzzy = unquote(line), None, "id", fuzzy
        elif line.startswith("msgid_plural") or line.startswith("msgstr["):
            target = None
            msgstr = None
        elif line.startswith("msgstr "):
            msgstr, target = unquote(line), "str"
        elif line.startswith('"') and target == "id":
            msgid += unquote(line)
        elif line.startswith('"') and target == "str":
            msgstr += unquote(line)
        elif not line:
            flush()
            msgid = msgstr = target = None
            fuzzy = False
    flush()
    return entries


def escape(value: str) -> str:
    return value.replace("\\", "\\\\").replace('"', '\\"').replace("\n", "\\n")


def locale_name(stem: str) -> str:
    if "-" in stem:
        lang, region = stem.split("-", 1)
        return f"{lang}-{region.upper()}"
    return stem


def main() -> None:
    po_dir, out_dir = Path(sys.argv[1]), Path(sys.argv[2])
    count = 0
    for old in out_dir.glob("*.lproj"):
        for f in old.iterdir():
            f.unlink()
        old.rmdir()
    for po in sorted(po_dir.glob("*.po")):
        # en.po renames a few GTK labels; the macOS client uses its English
        # source strings as they are.
        if po.stem == "en":
            continue
        entries = parse_po(po)
        if not entries:
            continue
        lproj = out_dir / f"{locale_name(po.stem)}.lproj"
        lproj.mkdir(parents=True, exist_ok=True)
        lines = [f'"{escape(k)}" = "{escape(v)}";' for k, v in sorted(entries.items())]
        (lproj / "Localizable.strings").write_text("\n".join(lines) + "\n", encoding="utf-8")
        count += 1
    print(f"  {count} languages")


if __name__ == "__main__":
    main()
