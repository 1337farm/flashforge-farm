#!/usr/bin/env python3
"""JNI contract test: every `native` method declared in Native.java must have a
matching JNIEXPORT in the farm bridge sources, and vice versa.

Fast (<2s): pure source-text check, no build. Guards the Java<->C++ boundary
against signature drift (a mismatch compiles fine and crashes at runtime).
"""
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
JAVA = REPO / "app/src/main/java/com/flashforge/farm/slic3r/Native.java"
JNI_DIR = REPO / "app/src/main/jni/farm"


def jni_mangle(name):
    out = []
    for ch in name:
        if ch == "_":
            out.append("_1")
        elif ch.isalnum():
            out.append(ch)
        else:
            out.append("_0%04x" % ord(ch))
    return "".join(out)


def main():
    src = JAVA.read_text()
    decls = set(re.findall(r"native\s+\S+\s+(\w+)\s*\(", src))
    assert decls, "no native declarations found in Native.java"

    impls = set()
    for cpp in JNI_DIR.glob("*.cpp"):
        text = cpp.read_text()
        impls.update(re.findall(r"Java_com_flashforge_farm_slic3r_Native_(\w+)\s*\(", text))
    assert impls, "no JNI impls found in app/src/main/jni/farm"

    failures = []
    for d in sorted(decls):
        if jni_mangle(d) not in impls:
            failures.append(f"declared but not implemented: {d}")
    implemented_roots = set()
    for i in impls:
        implemented_roots.add(i)
    for i in sorted(impls):
        if i not in {jni_mangle(d) for d in decls}:
            failures.append(f"implemented but not declared: {i} (dead JNI entry?)")

    print(f"native decls: {len(decls)}, jni impls: {len(impls)}")
    if failures:
        print("MISMATCHES:")
        for f in failures:
            print(f"- {f}")
        return 1
    print("JNI contract OK.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
