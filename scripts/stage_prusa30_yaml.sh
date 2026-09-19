#!/bin/bash
# scripts/stage_prusa30_yaml.sh — stage YAML headers for the 3.0 engine.
#
# Two consumers: <ryml.hpp>/<ryml_std.hpp>/<c4/...> (Preset IO via
# YamlAdapterRyml) and <yaml-cpp/exceptions.h> (dead but required include in
# SelectedPresetJson.cpp). Pins mirror upstream deps/+ryml/ryml.cmake and
# deps/+yamlCpp/yamlCpp.cmake. Header-only staging (no libs: nothing linked
# calls into compiled yaml code).
#
#   STAGE_ROOT  install prefix (default $(pwd)/engine/prusa30/jniImports)
#   WORK_DIR    scratch dir (default /tmp/stage_prusa30_yaml)
set -e
STAGE_ROOT="${STAGE_ROOT:-$(pwd)/engine/prusa30/jniImports}"
WORK_DIR="${WORK_DIR:-/tmp/stage_prusa30_yaml}"
RYML_VER="0.10.0"
RYML_SHA="54eb1050789809a26c780f80857b7668a5b3123405d6514a65d733e4292c690b"
RYML_URL="https://github.com/biojppm/rapidyaml/releases/download/v${RYML_VER}/rapidyaml-${RYML_VER}-src.tgz"
YAML_URL="https://github.com/jbeder/yaml-cpp/releases/download/yaml-cpp-0.9.0/yaml-cpp-yaml-cpp-0.9.0.tar.gz"
YAML_SHA="298593d9c440fd9034b8b193d96318b76d49bc97c6ceadb7b0836edf0b6d7539"
mkdir -p "$STAGE_ROOT" "$WORK_DIR"

fetch_verify() { # outfile url sha
    local out="$1" url="$2" sha="$3" i
    for i in 1 2 3; do
        if curl -fsSL --connect-timeout 20 --max-time 300 \
                --retry 2 --retry-all-errors -o "$out" "$url" \
           && [ -s "$out" ]; then
            break
        fi
        echo "--- [stage] yaml download attempt $i/3 failed: $url ---" >&2
        [ "$i" = "3" ] && exit 1
        sleep "$((i * 5))"
    done
    echo "$sha  $out" | sha256sum -c --status - \
        || { echo "--- [stage] yaml sha256 mismatch for $out ---" >&2; exit 1; }
}

fetch_verify "$WORK_DIR/rapidyaml.tgz" "$RYML_URL" "$RYML_SHA"
rm -rf "$WORK_DIR/ryml"; mkdir -p "$WORK_DIR/ryml"
tar xzf "$WORK_DIR/rapidyaml.tgz" -C "$WORK_DIR/ryml"
RYML_SRC="$(find "$WORK_DIR/ryml" -name ryml.hpp | head -1 | xargs dirname)"
[ -f "$RYML_SRC/ryml.hpp" ] && [ -f "$RYML_SRC/ryml_std.hpp" ] && [ -d "$RYML_SRC/c4" ] \
    || { echo "--- [stage] ERROR: ryml tree not found ---" >&2; exit 1; }
mkdir -p "$STAGE_ROOT/ryml/include"
cp "$RYML_SRC/ryml.hpp" "$RYML_SRC/ryml_std.hpp" "$STAGE_ROOT/ryml/include/"
cp -r "$RYML_SRC/c4" "$STAGE_ROOT/ryml/include/"
# c4 base headers (c4/substr.hpp etc.) ship as ryml's ext/c4core submodule,
# included in the tarball. Merge them into the same include/c4/ (ryml's own
# c4/ holds only yml/, no overlap).
C4CORE_SRC="$(find "$WORK_DIR/ryml" -path "*ext/c4core/src/c4/substr.hpp" | head -1 | xargs dirname)"
[ -n "$C4CORE_SRC" ] || { echo "--- [stage] ERROR: c4core tree not found ---" >&2; exit 1; }
cp -r "$C4CORE_SRC"/* "$STAGE_ROOT/ryml/include/c4/"

fetch_verify "$WORK_DIR/yaml-cpp.tar.gz" "$YAML_URL" "$YAML_SHA"
rm -rf "$WORK_DIR/yamlcpp"; mkdir -p "$WORK_DIR/yamlcpp"
tar xzf "$WORK_DIR/yaml-cpp.tar.gz" -C "$WORK_DIR/yamlcpp"
YAML_SRC="$(find "$WORK_DIR/yamlcpp" -maxdepth 2 -type d -name yaml-cpp | head -1 | xargs dirname)"
[ -d "$YAML_SRC/yaml-cpp" ] \
    || { echo "--- [stage] ERROR: yaml-cpp tree not found ---" >&2; exit 1; }
mkdir -p "$STAGE_ROOT/yamlcpp/include"
cp -r "$YAML_SRC/yaml-cpp" "$STAGE_ROOT/yamlcpp/include/"

[ -f "$STAGE_ROOT/ryml/include/ryml.hpp" ] \
 && [ -f "$STAGE_ROOT/ryml/include/c4/substr.hpp" ] \
 && [ -f "$STAGE_ROOT/ryml/include/c4/yml/yml.hpp" ] \
 && [ -f "$STAGE_ROOT/yamlcpp/include/yaml-cpp/exceptions.h" ] \
    || { echo "--- [stage] ERROR: staged yaml headers incomplete ---" >&2; exit 1; }
echo "ryml $RYML_VER + yaml-cpp headers staged under $STAGE_ROOT"
