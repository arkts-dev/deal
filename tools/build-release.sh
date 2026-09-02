#!/bin/bash
# tools/build-release.sh — the pinned DEAL 1.2 release distribution build
# (release-distribution-packaging-and-discovery D1/D2/D4/D5;
# release-distribution-build-and-verify P1-P4, P7).
#
# Release packaging gate only — no dev gate (run_tests.sh / coverage.sh)
# invokes this script. Self-locates via its own path, so it runs from
# any cwd; umask 022 is set by the script itself, so archive modes never
# depend on the caller's environment. Library-free and
# standalone-invocable: depends only on committed sources and the pinned
# tools — javac 25, /usr/bin/python3, GNU tar, /usr/bin/sha256sum.
#
# Recipe, in order (P3):
#   1. asset preflight — deal/runtime.lua, deal/runtime.js, the 18
#      committed std assets (console, string, table, json, math, time ×
#      .d.deal/.lua/.js — each checked by name, so a substituted file
#      can never keep the count green), tools/deal-launcher.sh,
#      tools/gate-manifest.sh, and deal/project/ProjectLocator.java must
#      exist, else RELEASE_PACKAGING_FAILED (stderr, nonzero exit);
#   2. version extraction — the pinned /usr/bin/python3 extracts exactly
#      one "public static final String LANGUAGE_VERSION = \"...\""
#      declaration from deal/project/ProjectLocator.java (zero or
#      multiple matches fail); the archive is
#      release/deal-<version>-linux-x86_64.tar;
#   3. build — rm -rf dist; javac --release 25 -proc:none -d dist over
#      PROD_SOURCES from tools/gate-manifest.sh (production sources
#      only, JDK-only closure, no external classpath, no post-compile
#      class filter); copy deal/runtime.lua and deal/runtime.js to
#      dist/deal/; copy the 18 std files to dist/std/; install
#      tools/deal-launcher.sh as dist/bin/deal with chmod 755;
#   4. manifest — dist/dist-manifest.json = {schemaVersion: 1, entries:
#      [{path, sha256}]}, one entry per file under dist/ except the
#      manifest itself, entries sorted by path, fixed field order,
#      digests computed in-process (hashlib) by the pinned
#      /usr/bin/python3;
#   5. archive — tar --sort=name --mtime='@0' --owner=0 --group=0
#      --numeric-owner -cf release/deal-<version>-linux-x86_64.tar dist;
#   6. self-check — unpack the archive into a trap-removed scratch dir,
#      digest every unpacked file with the pinned /usr/bin/sha256sum,
#      compare against the manifest with the pinned /usr/bin/python3,
#      and require the unpacked set to equal the manifest entries plus
#      dist-manifest.json — any mismatch is RELEASE_PACKAGING_FAILED
#      (the manifest/archive mismatch cause);
#   7. digest — the archive SHA-256 via /usr/bin/sha256sum.
# Steps 3-7 run twice (build A, then build B); the two digests must be
# byte-equal, else RELEASE_PACKAGING_FAILED (the rebuild byte-identity
# gate, D4). The final stdout line — and the only stdout line — is the
# archive SHA-256 of the final build (the expected-digest hand-off); all
# progress output goes to stderr.
#
# Visible errors: compile failure, missing committed asset,
# manifest/archive mismatch, rebuild byte-identity difference, and
# version-extraction failure each print RELEASE_PACKAGING_FAILED on
# stderr and exit nonzero. Post-state: dist/ and the archive exist;
# nothing outside them is modified; every scratch dir removed.
set -eu

cd "$(dirname "$0")/.."
umask 022

# Byte-stable collation for the sorted tar members and the self-check
# find/sort order; pinned like the umask so the archive bytes never
# depend on the caller's environment.
LC_ALL=C
export LC_ALL

PYTHON3=/usr/bin/python3
SHA256SUM=/usr/bin/sha256sum

fail() {
    printf 'RELEASE_PACKAGING_FAILED\n' >&2
    exit 1
}

# One trap-removed scratch root for both self-check unpacks; removed on
# every exit (success, failure, signal).
SCRATCH_DIR=$(mktemp -d "${TMPDIR:-/tmp}/deal-build-release.XXXXXX")
trap 'rm -rf "$SCRATCH_DIR"' EXIT
trap 'rm -rf "$SCRATCH_DIR"; exit 130' HUP INT TERM

# ----------------------------------------------------------------------
# Step 1: asset preflight (P3.1).
# ----------------------------------------------------------------------
printf 'build-release: asset preflight\n' >&2
for asset in deal/runtime.lua deal/runtime.js tools/deal-launcher.sh \
             tools/gate-manifest.sh deal/project/ProjectLocator.java; do
    if [ ! -f "$asset" ]; then
        printf 'build-release: missing committed asset: %s\n' "$asset" >&2
        fail
    fi
done
# The 18 committed std assets, checked by name: each of the six
# spec-listed modules (deal/project/ProjectLocator.java
# SPEC_STDLIB_MODULES: console, string, table, json, math, time) × its
# .d.deal/.lua/.js copy. A count-only check could pass with a
# substituted file and ship a distribution missing a spec-listed stdlib
# module, so every named asset is verified individually.
for std_module in console string table json math time; do
    for std_ext in d.deal lua js; do
        std_asset="std/${std_module}.${std_ext}"
        if [ ! -f "$std_asset" ]; then
            printf 'build-release: missing committed asset: %s\n' \
                "$std_asset" >&2
            fail
        fi
    done
done
# Extra non-spec std files would deviate from the pinned six-module
# layout; the count gate keeps preflight fail-closed on that direction
# (exactly 18 files match the three std kinds).
STD_COUNT=$(find std -maxdepth 1 -type f \
    \( -name '*.d.deal' -o -name '*.lua' -o -name '*.js' \) -print | wc -l)
if [ "$STD_COUNT" -ne 18 ]; then
    printf 'build-release: expected the 18 committed std/*.{d.deal,lua,js} files, found %s\n' \
        "$STD_COUNT" >&2
    fail
fi

# ----------------------------------------------------------------------
# Step 2: version extraction (P3.2, D5).
# ----------------------------------------------------------------------
printf 'build-release: extracting LANGUAGE_VERSION from deal/project/ProjectLocator.java\n' >&2
if ! VERSION=$("$PYTHON3" - deal/project/ProjectLocator.java <<'PY'
import re
import sys

text = open(sys.argv[1], "r").read()
matches = re.findall(
    r'public static final String LANGUAGE_VERSION\s*=\s*"([^"]*)"', text)
if len(matches) != 1 or matches[0] == "":
    sys.exit(1)
print(matches[0])
PY
); then
    printf 'build-release: LANGUAGE_VERSION extraction failed (need exactly one non-empty declaration)\n' >&2
    fail
fi
ARCHIVE="release/deal-${VERSION}-linux-x86_64.tar"
printf 'build-release: archive = %s\n' "$ARCHIVE" >&2

# The single compile/test-list authority (gate-manifest-authority M2):
# the distribution compiles PROD_SOURCES only — no test sources.
source tools/gate-manifest.sh

# ----------------------------------------------------------------------
# The pinned python3 scripts (top-level capture keeps them heredoc-free
# inside build_once; same pinned-tool discipline as verify-launcher.sh).
# ----------------------------------------------------------------------
MANIFEST_PY=$(cat <<'PY'
import hashlib
import json
import os

dist = "dist"
entries = []
for root, dirs, files in os.walk(dist):
    dirs.sort()
    for name in sorted(files):
        path = os.path.join(root, name)
        rel = os.path.relpath(path, dist).replace(os.sep, "/")
        if rel == "dist-manifest.json":
            continue
        with open(path, "rb") as f:
            digest = hashlib.sha256(f.read()).hexdigest()
        entries.append({"path": rel, "sha256": digest})
entries.sort(key=lambda entry: entry["path"])
with open(os.path.join(dist, "dist-manifest.json"), "w") as f:
    json.dump({"schemaVersion": 1, "entries": entries}, f)
    f.write("\n")
PY
)

SELFCHECK_PY=$(cat <<'PY'
import json
import sys

manifest_path = sys.argv[1]
sha_path = sys.argv[2]
try:
    with open(manifest_path, "r") as f:
        manifest = json.load(f)
except Exception:
    sys.exit(1)
if manifest.get("schemaVersion") != 1:
    sys.exit(1)
entries = manifest.get("entries")
if not isinstance(entries, list):
    sys.exit(1)

expected = {}
for entry in entries:
    path = entry.get("path")
    digest = entry.get("sha256")
    if not isinstance(path, str) or not isinstance(digest, str):
        sys.exit(1)
    expected[path] = digest

unpacked = {}
manifest_present = False
with open(sha_path, "r") as f:
    for line in f:
        line = line.rstrip("\n")
        digest, sep, path = line.partition("  ")
        if not digest or not sep or not path:
            sys.exit(1)
        if path == "dist/dist-manifest.json":
            manifest_present = True
            continue
        if not path.startswith("dist/"):
            sys.stderr.write("unexpected member outside dist/: %s\n" % path)
            sys.exit(1)
        unpacked[path[len("dist/"):]] = digest

if not manifest_present:
    sys.stderr.write("dist/dist-manifest.json missing from the unpacked archive\n")
    sys.exit(1)
if set(unpacked) != set(expected):
    for path in sorted(set(unpacked) ^ set(expected)):
        sys.stderr.write("set mismatch: %s\n" % path)
    sys.exit(1)
for path, digest in expected.items():
    if unpacked[path] != digest:
        sys.stderr.write("digest mismatch: %s\n" % path)
        sys.exit(1)
PY
)

# ----------------------------------------------------------------------
# build_once <label>: steps 3-7 for one build (P3.3-P3.7). Prints the
# archive SHA-256 as its only stdout line; all progress goes to stderr.
# ----------------------------------------------------------------------
build_once() {
    LABEL=$1
    SELFCHECK_DIR="$SCRATCH_DIR/selfcheck-$LABEL"

    rm -rf dist
    rm -f "$ARCHIVE"

    printf 'build-release: %s: compiling PROD_SOURCES (javac --release 25 -proc:none, JDK-only)\n' \
        "$LABEL" >&2
    # PROD_SOURCES is expanded unquoted so the manifest's quoted globs
    # expand here exactly as they do in run_tests.sh's compile line;
    # javac receives the identical expanded production file list.
    if ! javac --release 25 -proc:none -d dist ${PROD_SOURCES[@]}; then
        printf 'build-release: %s: compile failed\n' "$LABEL" >&2
        fail
    fi

    printf 'build-release: %s: copying runtime and stdlib assets, installing bin/deal\n' \
        "$LABEL" >&2
    if ! mkdir -p dist/std dist/bin; then
        printf 'build-release: %s: asset staging failed\n' "$LABEL" >&2
        fail
    fi
    if ! cp deal/runtime.lua deal/runtime.js dist/deal/; then
        printf 'build-release: %s: runtime copy failed\n' "$LABEL" >&2
        fail
    fi
    if ! cp std/*.d.deal std/*.lua std/*.js dist/std/; then
        printf 'build-release: %s: stdlib copy failed\n' "$LABEL" >&2
        fail
    fi
    if ! cp tools/deal-launcher.sh dist/bin/deal; then
        printf 'build-release: %s: launcher install failed\n' "$LABEL" >&2
        fail
    fi
    chmod 755 dist/bin/deal

    printf 'build-release: %s: writing dist-manifest.json\n' "$LABEL" >&2
    if ! printf '%s\n' "$MANIFEST_PY" | "$PYTHON3" -; then
        printf 'build-release: %s: manifest generation failed\n' "$LABEL" >&2
        fail
    fi

    printf 'build-release: %s: archiving with the pinned GNU tar flags\n' "$LABEL" >&2
    if ! mkdir -p release; then
        printf 'build-release: %s: release dir creation failed\n' "$LABEL" >&2
        fail
    fi
    if ! tar --sort=name --mtime='@0' --owner=0 --group=0 --numeric-owner \
            -cf "$ARCHIVE" dist; then
        printf 'build-release: %s: archive failed\n' "$LABEL" >&2
        fail
    fi

    printf 'build-release: %s: self-check (unpack + digest + manifest comparison)\n' \
        "$LABEL" >&2
    rm -rf "$SELFCHECK_DIR"
    if ! mkdir -p "$SELFCHECK_DIR"; then
        printf 'build-release: %s: scratch creation failed\n' "$LABEL" >&2
        fail
    fi
    if ! tar -xf "$ARCHIVE" -C "$SELFCHECK_DIR"; then
        printf 'build-release: %s: self-check unpack failed\n' "$LABEL" >&2
        fail
    fi
    (
        cd "$SELFCHECK_DIR" || exit 1
        find dist -type f -print | sort | while IFS= read -r f; do
            "$SHA256SUM" "$f"
        done
    ) > "$SELFCHECK_DIR/unpacked.sha"
    if ! printf '%s\n' "$SELFCHECK_PY" | "$PYTHON3" - \
            "$SELFCHECK_DIR/dist/dist-manifest.json" \
            "$SELFCHECK_DIR/unpacked.sha"; then
        printf 'build-release: %s: manifest/archive mismatch\n' "$LABEL" >&2
        fail
    fi

    "$SHA256SUM" "$ARCHIVE" | cut -d' ' -f1
}

# ----------------------------------------------------------------------
# The double build (P3): two complete builds, byte-identical digests.
# ----------------------------------------------------------------------
printf 'build-release: build A\n' >&2
DIGEST_A=$(build_once A) || exit 1
printf 'build-release: build B\n' >&2
DIGEST_B=$(build_once B) || exit 1

if [ "$DIGEST_A" != "$DIGEST_B" ]; then
    printf 'build-release: rebuild byte-identity failed: %s != %s\n' \
        "$DIGEST_A" "$DIGEST_B" >&2
    fail
fi
printf 'build-release: byte-identical rebuilds (%s)\n' "$DIGEST_B" >&2

# The expected-digest hand-off: the final stdout line — and the only
# stdout line — is the archive SHA-256 of the final build.
printf '%s\n' "$DIGEST_B"
