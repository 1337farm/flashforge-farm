#!/usr/bin/env bash
# Stage 3.0 header-only dependencies for the native engine + app builds.
#
# The vendored 2.x tree is deleted (#210) and the remaining/ported sources
# consume upstream 3.0 headers, which pull these third-party headers. All
# pins mirror scripts/build_prusa30_deps.sh (which tracks upstream
# deps/*.cmake). Idempotent per dep: reuses an existing stage.
# Only BROADLY-needed header libs live here; compiled deps (libassert,
# Boost, TBB, OCCT...) keep their own stages. No spdlog/fmt COMPILED libs:
# upstream admesh includes spdlog headers but makes zero spdlog:: calls,
# so headers suffice (verified at pin 6f51012).
#
# Usage: scripts/stage_headers.sh <dest-dir>
#   Produces <dest>/<name>/include/** for each dep below.
set -euo pipefail

DEST="${1:?usage: stage_headers.sh <dest-dir> [dep...]}"
shift
WORK="${WORK_DIR:-/tmp/stage_headers}"
# Optional dep filter (e.g. for testing one dep); default stages all.
ONLY="$*"

needs() { # name -> 0 if wanted
    [ -z "$ONLY" ] && return 0
    case " $ONLY " in *" $1 "*) return 0;; *) return 1;; esac
}

dl_verify_stage() { # name url sha archive sentinel subdirs...
    local name="$1" url="$2" sha="$3" archive="$4" sentinel="$5"; shift 5
    local dest="$DEST/$name/include"
    if [ -f "$dest/$sentinel" ]; then
        echo "headers $name already staged"
        return 0
    fi
    mkdir -p "$WORK" "$dest"
    local dl="$WORK/$archive"
    [ -f "$dl" ] || curl -sSL -o "$dl" "$url"
    echo "$sha  $dl" | sha256sum -c - >/dev/null
    local dir="$WORK/stage-$name"
    rm -rf "$dir"; mkdir -p "$dir"
    unzip -q -o "$dl" -d "$dir"
    local src found
    for src in "$@"; do
        found="$(find "$dir" -mindepth 2 -type d -path "*/$src" | sort | head -1)"
        [ -n "$found" ] || { echo "ERROR: subdir $src not found for $name" >&2; return 1; }
        cp -r "$found" "$dest/"
    done
    rm -rf "$dir"
    test -f "$dest/$sentinel"
    echo "headers $name staged"
}

needs eigen && dl_verify_stage eigen \
    "https://gitlab.com/libeigen/eigen/-/archive/3.4.0/eigen-3.4.0.zip" \
    "eba3f3d414d2f8cba2919c78ec6daab08fc71ba2ba4ae502b7e5d4d99fc02cda" \
    eigen.zip "Eigen/Dense" "Eigen" "unsupported"

needs spdlog && dl_verify_stage spdlog \
    "https://github.com/gabime/spdlog/archive/refs/tags/v1.15.3.zip" \
    "b74274c32c8be5dba70b7006c1d41b7d3e5ff0dff8390c8b6390c1189424e094" \
    spdlog.zip "spdlog/spdlog.h" "spdlog"

needs fmt && dl_verify_stage fmt \
    "https://github.com/fmtlib/fmt/releases/download/12.1.0/fmt-12.1.0.zip" \
    "695fd197fa5aff8fc67b5f2bbc110490a875cdf7a41686ac8512fb480fa8ada7" \
    fmt.zip "fmt/format.h" "fmt"

needs cereal && dl_verify_stage cereal \
    "https://github.com/USCiLab/cereal/archive/refs/tags/v1.3.2.zip" \
    "e72c3fa8fe3d531247773e346e6824a4744cc6472a25cf9b30599cd52146e2ae" \
    cereal.zip "cereal/cereal.hpp" "cereal"

needs nlohmann_json && dl_verify_stage nlohmann_json \
    "https://github.com/nlohmann/json/archive/refs/tags/v3.12.0.zip" \
    "34660b5e9a407195d55e8da705ed26cc6d175ce5a6b1fb957e701fb4d5b04022" \
    json.zip "nlohmann/json.hpp" "nlohmann"

needs sol2 && dl_verify_stage sol2 \
    "https://github.com/ThePhD/sol2/archive/refs/tags/v3.5.0.zip" \
    "b43e539415956960055f62a9d328fec3fd1ad4f272d6206631b9f022b0b12678" \
    sol2.zip "sol/sol.hpp" "sol"

needs expected && dl_verify_stage expected \
    "https://github.com/TartanLlama/expected/archive/refs/tags/v1.1.0.zip" \
    "4b2a347cf5450e99f7624247f7d78f86f3adb5e6acd33ce307094e9507615b78" \
    expected.zip "tl/expected.hpp" "tl"

needs magic_enum && dl_verify_stage magic_enum \
    "https://github.com/Neargye/magic_enum/archive/refs/tags/v0.9.7.zip" \
    "e293afdaf4d5918bc145903bccff06d28b3ed437f1ac8414ace9e8a769a9e470" \
    magic_enum.zip "magic_enum.hpp" "magic_enum"

echo "header deps staged under $DEST"
