#!/bin/sh
# tools/verify-release.sh — the pinned DEAL 1.2 release distribution
# verification gate (release-distribution-packaging-and-discovery D4 +
# verify-release contract; release-distribution-build-and-verify P5).
#
# Release gate only — no dev gate (run_tests.sh / coverage.sh) invokes
# this script. Self-locates via its own path, so it runs from any cwd.
# Library-free and standalone-invocable: depends only on the supplied
# archive, the committed smoke project, and the pinned tools — GNU tar,
# /usr/bin/sha256sum, /usr/bin/python3 — plus java on PATH via the
# unpacked dist/bin/deal for the smoke leg. No step-library or
# bounded-dispatch dependency is added here (the whole-script bounded
# wrapping arrives in E5).
#
# Invocation (pinned): tools/verify-release.sh <archive> <expected-sha256>
#   <archive>          the release/deal-<version>-linux-x86_64.tar
#                      produced by tools/build-release.sh
#   <expected-sha256>  the archive digest captured from build-release's
#                      final stdout line (the build-printed hand-off the
#                      R6 comparison consumes)
#
# Legs, in order (P5); a failing leg prints its named token on stderr
# and exits nonzero — no skip, no retry:
#   1. unpack — tar -xf <archive> -C <scratch>; an unpack failure
#      (corrupt/truncated archive) fails ARCHIVE_UNPACK_FAILED;
#   2. per-file — the pinned python3 parses
#      scratch/dist/dist-manifest.json; the pinned sha256sum digests
#      every unpacked file; any manifest entry whose file is missing or
#      whose digest differs fails DIGEST_MISMATCH <path>; completeness:
#      any unpacked file other than dist-manifest.json absent from the
#      manifest fails DIGEST_MISMATCH <path>; a missing
#      dist/dist-manifest.json fails DIGEST_MISMATCH
#      dist/dist-manifest.json (the reported path is the unpacked
#      member path, e.g. dist/std/console.lua);
#   3. archive digest — sha256sum over the archive must equal
#      <expected-sha256>, else ARCHIVE_DIGEST_MISMATCH <expected>
#      <actual>;
#   4. smoke — copy the committed test/release/smoke-project/ into the
#      scratch and, with the scratch smoke copy as the cwd (outside the
#      checkout, no local std/, no local deal/runtime.lua), run
#      "<scratch>/dist/bin/deal" compile --output "<scratch>/smoke-out"
#      "<scratch>/smoke/src/main.deal"; a nonzero compile exit fails
#      SMOKE_COMPILE_FAILED.
# All legs green prints "verify-release: PASS" and exits 0. The smoke
# leg's green pass requires the E2 tier-2 distribution discovery and is
# E2's completion signal; on today's tree (pre-E2) a correct archive
# passes legs 1-3, reaches leg 4, and fails SMOKE_COMPILE_FAILED — the
# epic's expected state, not a defect of this gate.
#
# All scratch traffic lives in a mktemp -d directory under
# ${TMPDIR:-/tmp}, removed by a trap on every exit — success, every
# named failure, and signal exits. The committed tree is only read (the
# smoke project is copied into scratch, never written).
set -eu

if [ "$#" -ne 2 ]; then
    printf 'usage: %s <archive> <expected-sha256>\n' "$0" >&2
    exit 1
fi

ARCHIVE=$1
EXPECTED=$2

# Self-location via the script's own path: the committed smoke project
# resolves relative to the repository root regardless of the caller's
# cwd (the tools/verify-launcher.sh precedent).
cd "$(dirname "$0")/.."

# Byte-stable collation for the find/sort digest listing, pinned like in
# tools/build-release.sh so the per-file leg's mismatch reporting is
# deterministic in every environment.
LC_ALL=C
export LC_ALL

PYTHON3=/usr/bin/python3
SHA256SUM=/usr/bin/sha256sum

fail() {
    printf '%s\n' "$1" >&2
    exit 1
}

SCRATCH_DIR=$(mktemp -d "${TMPDIR:-/tmp}/deal-verify-release.XXXXXX")
trap 'rm -rf "$SCRATCH_DIR"' EXIT
trap 'rm -rf "$SCRATCH_DIR"; exit 130' HUP INT TERM

# ----------------------------------------------------------------------
# Leg 1/4: unpack (P5.1).
# ----------------------------------------------------------------------
printf 'verify-release: leg 1/4 unpack\n' >&2
if ! tar -xf "$ARCHIVE" -C "$SCRATCH_DIR"; then
    fail ARCHIVE_UNPACK_FAILED
fi

# ----------------------------------------------------------------------
# Leg 2/4: per-file digests against dist-manifest.json (P5.2).
# ----------------------------------------------------------------------
printf 'verify-release: leg 2/4 per-file digests\n' >&2
(
    cd "$SCRATCH_DIR" || exit 1
    find dist -type f -print | sort | while IFS= read -r f; do
        "$SHA256SUM" "$f"
    done
) > "$SCRATCH_DIR/unpacked.sha"
# The pinned python3 owns the comparison. On any failure it prints the
# exact unpacked member path (dist/-rooted) on stdout; the shell relays
# it into the named token on stderr. Checks in the P5.2 order: manifest
# parse, manifest-driven entry checks (missing file or digest drift),
# completeness (unpacked files absent from the manifest), and the
# manifest's own presence in the unpacked set.
if ! MISMATCH=$("$PYTHON3" - "$SCRATCH_DIR/dist/dist-manifest.json" \
        "$SCRATCH_DIR/unpacked.sha" <<'PY'
import json
import sys

manifest_path = sys.argv[1]
sha_path = sys.argv[2]

# A missing or unparsable manifest is a broken content identity: the
# per-file leg cannot proceed, and the pinned token names the manifest
# itself (P5.2).
try:
    with open(manifest_path, "r") as f:
        manifest = json.load(f)
except Exception:
    sys.stdout.write("dist/dist-manifest.json\n")
    sys.exit(1)

if manifest.get("schemaVersion") != 1:
    sys.stdout.write("dist/dist-manifest.json\n")
    sys.exit(1)
entries = manifest.get("entries")
if not isinstance(entries, list):
    sys.stdout.write("dist/dist-manifest.json\n")
    sys.exit(1)

expected = {}
for entry in entries:
    path = entry.get("path")
    digest = entry.get("sha256")
    if not isinstance(path, str) or not isinstance(digest, str):
        sys.stdout.write("dist/dist-manifest.json\n")
        sys.exit(1)
    expected[path] = digest

unpacked = {}
manifest_present = False
with open(sha_path, "r") as f:
    for line in f:
        line = line.rstrip("\n")
        digest, sep, path = line.partition("  ")
        if not digest or not sep or not path:
            continue
        if path == "dist/dist-manifest.json":
            manifest_present = True
            continue
        unpacked[path] = digest

# Manifest-driven checks, deterministic order: every entry must exist
# with a matching digest.
for path in sorted(expected):
    unpacked_path = "dist/" + path
    if unpacked_path not in unpacked:
        sys.stdout.write(unpacked_path + "\n")
        sys.exit(1)
    if unpacked[unpacked_path] != expected[path]:
        sys.stdout.write(unpacked_path + "\n")
        sys.exit(1)

# Completeness: every unpacked file other than dist-manifest.json must
# be a manifest entry.
for path in sorted(unpacked):
    rel = path[len("dist/"):] if path.startswith("dist/") else path
    if rel not in expected:
        sys.stdout.write(path + "\n")
        sys.exit(1)

# The manifest itself must be present in the unpacked set.
if not manifest_present:
    sys.stdout.write("dist/dist-manifest.json\n")
    sys.exit(1)

sys.exit(0)
PY
); then
    fail "DIGEST_MISMATCH $MISMATCH"
fi

# ----------------------------------------------------------------------
# Leg 3/4: archive digest against the build-printed hand-off (P5.3).
# ----------------------------------------------------------------------
printf 'verify-release: leg 3/4 archive digest\n' >&2
ACTUAL=$("$SHA256SUM" "$ARCHIVE" | cut -d' ' -f1)
if [ "$ACTUAL" != "$EXPECTED" ]; then
    fail "ARCHIVE_DIGEST_MISMATCH $EXPECTED $ACTUAL"
fi

# ----------------------------------------------------------------------
# Leg 4/4: out-of-checkout smoke compile (P5.4, P6).
# The scratch smoke copy is the cwd — outside the checkout, with no
# local std/ and no local deal/runtime.lua — so the compile proves
# distribution discovery. A nonzero compile exit is SMOKE_COMPILE_FAILED.
# ----------------------------------------------------------------------
printf 'verify-release: leg 4/4 smoke compile\n' >&2
cp -r test/release/smoke-project "$SCRATCH_DIR/smoke"
if ! (
        cd "$SCRATCH_DIR/smoke" || exit 1
        "$SCRATCH_DIR/dist/bin/deal" compile \
            --output "$SCRATCH_DIR/smoke-out" \
            "$SCRATCH_DIR/smoke/src/main.deal"
    ) >"$SCRATCH_DIR/smoke.out" 2>"$SCRATCH_DIR/smoke.err"; then
    cat "$SCRATCH_DIR/smoke.out" >&2
    cat "$SCRATCH_DIR/smoke.err" >&2
    fail SMOKE_COMPILE_FAILED
fi

printf 'verify-release: PASS\n'
exit 0
