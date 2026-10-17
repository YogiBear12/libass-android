#!/usr/bin/env bash
# Generates the autotools build files the native build needs. Run once after cloning.
# Fontconfig's autogen script rejects distro-suffixed libtool versions, so every project gets
# autoreconf directly; none of them is configured in-tree, which would break the CMake build.
set -euo pipefail
root=$(cd "$(dirname "$0")" && pwd)
src=$root/lib_ass/src/main/cpp/libass-cmake/src
git -C "$root" submodule update --init --recursive
for dir in unibreak fribidi fontconfig expat/expat ass; do
    (cd "$src/$dir" && autoreconf -fi)
done
