#!/bin/sh
# DEALPG4 launcher pinned build recipe.
# Runs from the repository root under SOURCE_DATE_EPOCH=0; the gcc
# invocation below is the only build path for the committed artifact.
set -eu
cd "$(dirname "$0")/.."
umask 022
SOURCE_DATE_EPOCH=0
export SOURCE_DATE_EPOCH
gcc -std=c11 -O2 -Wall -Werror -fno-ident -ffile-prefix-map=$PWD=. \
    -Wl,--build-id=none -o tools/deal-process-launcher-linux-x86_64 tools/src/*.c
# The pinned toolchain's prebuilt crtbeginS.o/crtendS.o carry a .comment
# section; -fno-ident only covers compiler-emitted idents, not prebuilt
# objects. Remove the injected section so the committed artifact has no
# .comment/.ident sections (deterministic; keeps the rebuild byte-identical).
objcopy --remove-section=.comment tools/deal-process-launcher-linux-x86_64
