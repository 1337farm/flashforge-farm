#!/usr/bin/env python3.14
"""Line-by-line AST inventory of the settings UI code.

Parses the settings fragments with tree-sitter (real Java AST, not regex)
and emits:
  - scripts/settings-inventory.json  (machine-readable)
  - doc/SETTINGS_INVENTORY.md        (human-readable audit ledger)

Covers: every addSection(title, keys...), every options.get("key"),
every ENUM-vs-text dialog branch, every Log.w diagnostic, and the
bridge type-mapping function. Re-run after any settings change and diff.
"""
import json
import subprocess
import sys
from pathlib import Path

import tree_sitter_java as tsj
from tree_sitter import Language, Parser

REPO = Path(__file__).resolve().parents[1]
FILES = [
    "app/src/main/java/com/flashforge/farm/fragment/ProfilePrintSettingsBuilder.java",
    "app/src/main/java/com/flashforge/farm/fragment/FilamentConfigFragment.java",
    "app/src/main/java/com/flashforge/farm/fragment/PrinterConfigFragment.java",
    "app/src/main/java/com/flashforge/farm/fragment/ProfileListFragment.java",
    "app/src/main/java/com/flashforge/farm/slic3r/EnumLabelResolver.java",
    "app/src/main/java/com/flashforge/farm/slic3r/SectionPruner.java",
    "app/src/main/jni/farm/farm_native.cpp",
]

LANG = Language(tsj.language())


def parse(path):
    parser = Parser(LANG)
    src = Path(path).read_bytes()
    return parser.parse(src), src


def text(node, src):
    return src[node.start_byte:node.end_byte].decode("utf-8", "replace")


def walk(node, type_name):
    if node.type == type_name:
        yield node
    for child in node.children:
        yield from walk(child, type_name)


def lit_value(lit, src):
    """Unquote a string_literal node."""
    t = text(lit, src).strip()
    if len(t) >= 2 and t[0] == '"' and t[-1] == '"':
        return t[1:-1]
    return t


def string_args(node, src, limit=12):
    """String literal args of a method invocation node."""
    out = []
    for arg in node.children:
        if arg.type == "argument_list":
            for lit in walk(arg, "string_literal"):
                out.append(lit_value(lit, src))
                if len(out) >= limit:
                    return out
    return out


def method_name(node, src):
    found = ""
    for child in node.children:
        if child.type in ("identifier", "field_access", "method_reference"):
            found = text(child, src).split(".")[-1]
    return found


def audit_java(path):
    tree, src = parse(path)
    root = tree.root_node
    methods = []
    for m in walk(root, "method_declaration"):
        name = ""
        for c in m.children:
            if c.type == "identifier":
                name = text(c, src)
                break
        methods.append({"name": name, "line": m.start_point[0] + 1})
    sections = []
    option_gets = []
    enum_branches = []
    log_ws = []
    for inv in walk(root, "method_invocation"):
        name = method_name(inv, src)
        line = inv.start_point[0] + 1
        if name == "addSection":
            args = string_args(inv, src)
            sections.append({"line": line, "title": args[0] if args else "", "keys": args[1:]})
        elif name == "get":
            args = string_args(inv, src, limit=1)
            # options.get("key") — receiver check is best-effort via text
            if args and "options" in text(inv, src).split("(")[0][-30:]:
                option_gets.append({"line": line, "key": args[0]})
        elif name == "w":
            args = string_args(inv, src)
            if args:
                log_ws.append({"line": line, "msg": args[0][:100]})
    # ENUM-vs-text branch conditions: binary_expressions mentioning ENUM
    seen_conds = set()
    for b in walk(root, "binary_expression"):
        t = text(b, src)
        if "ENUM" in t and len(t) < 200:
            key = (b.start_point[0] + 1, " ".join(t.split())[:160])
            if key not in seen_conds:
                seen_conds.add(key)
                enum_branches.append({"line": key[0], "cond": key[1]})
    return {"methods": methods, "sections": sections,
            "option_gets": option_gets, "enum_branches": enum_branches,
            "log_ws": log_ws}


def engine_keys():
    files = [
        REPO / "engine/build/prusaslicer-src/src/slic3r-domain/src/Slic3r/Domain/ConfigCommon.cpp",
        REPO / "engine/build/prusaslicer-src/src/slic3r-domain/src/Slic3r/Domain/ConfigDefsFDM.cpp",
    ]
    import re
    keys = set()
    for f in files:
        keys.update(re.findall(r'defs\.add\("([a-z0-9_]+)"', f.read_text()))
    return keys


def main():
    import re
    report = {"files": {}}
    eng = engine_keys()
    report["engine_fdm_defs"] = len(eng)
    all_stale = []
    for rel in FILES:
        p = REPO / rel
        if p.suffix == ".java":
            inv = audit_java(p)
            keys = set()
            for s in inv["sections"]:
                keys.update(s["keys"])
            for g in inv["option_gets"]:
                keys.add(g["key"])
            stale = sorted(keys - eng)
            inv["key_count"] = len(keys)
            inv["stale_keys"] = stale
            all_stale += [f"{Path(rel).name}:{k}" for k in stale]
        else:
            inv = {"note": "C++ bridge: see farm_native.cpp audit below"}
            # bridge type-mapping + choices sites (regex: structural, single file)
            t = p.read_text()
            inv["type_mapping"] = sorted(set(re.findall(r'return "(ENUM|STRING|FLOAT|INT|BOOL\w*|PERCENT\w*|NONE)"', t)))
            inv["has_strcmp_fallback"] = "strcmp" in t and "typeid" in t
            inv["logs_typename"] = "t.name()" in t or "typename" in t
        report["files"][rel] = inv
    report["total_stale"] = len(all_stale)
    report["stale"] = all_stale
    (REPO / "scripts/settings-inventory.json").write_text(json.dumps(report, indent=1))

    md = ["# Settings Code Inventory (generated — do not hand-edit)",
          "",
          "> Regenerate: `python3.14 scripts/settings-inventory.py`.",
          f"> Engine FDM defs: **{len(eng)}**. Total stale UI keys: **{len(all_stale)}**.",
          ""]
    for rel, inv in report["files"].items():
        md.append(f"## `{rel}`")
        if "sections" in inv:
            md.append(f"- methods: {len(inv['methods'])}, sections: {len(inv['sections'])}, "
                      f"keys referenced: {inv['key_count']}, stale: {len(inv['stale_keys'])}")
            for s in inv["sections"]:
                bad = [k for k in s["keys"] if k in inv["stale_keys"]]
                flag = f" **STALE: {', '.join(bad)}**" if bad else ""
                md.append(f"  - L{s['line']} `{s['title']}` ({len(s['keys'])} keys){flag}")
            md.append(f"- ENUM branch conditions: {len(inv['enum_branches'])}")
            for b in inv["enum_branches"]:
                md.append(f"  - L{b['line']}: `{b['cond']}`")
            md.append(f"- Log.w diagnostics: {len(inv['log_ws'])}")
        else:
            md.append(f"- type mappings emitted: {', '.join(inv['type_mapping'])}")
            md.append(f"- strcmp fallback present: {inv['has_strcmp_fallback']}")
            md.append(f"- mangled-name logging present: {inv['logs_typename']}")
        md.append("")
    if all_stale:
        md.append("## Stale keys (UI references without engine def)")
        md += [f"- `{s}`" for s in all_stale]
    else:
        md.append("## Stale keys: none. All UI keys resolve to engine defs.")
    (REPO / "doc/SETTINGS_INVENTORY.md").write_text("\n".join(md) + "\n")
    print(f"engine={len(eng)} stale={len(all_stale)}")
    for s in all_stale:
        print("STALE:", s)
    return 1 if all_stale else 0


if __name__ == "__main__":
    sys.exit(main())
