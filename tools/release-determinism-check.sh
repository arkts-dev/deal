#!/bin/bash
# tools/release-determinism-check.sh — the release determinism gate
# (deterministic-diagnostics D3, release phase R4; whole-script step).
#
# Release gate only — no dev gate (run_tests.sh / coverage.sh) invokes
# this script. Self-locates via its own path, so it runs with no
# arguments from any cwd; edits no gate file; records nothing into any
# evidence document (E8 owns the gates.determinism.detail record).
#
# Two double-run byte-comparison legs, every compile in its own fresh
# JVM invocation against one production class build:
#
#   leg (a) diagnostics — (1) the conformance compile-error member set:
#   every .deal file under test/conformance/ whose // @expected: value
#   starts with "compile-error " (harness dispatch mirror:
#   "known-fail compile-error ..." values never match, companions are
#   never entry-compiled), minus the closed pinned exclusion list below;
#   each member compiles twice through deal.Main compile in a scratch
#   project with the pinned v1.2 manifest, its classification headers
#   stripped byte-for-byte (ConformanceHarnessMetadata), and its own
#   @expected code must appear in both runs of both surfaces (anti-hollow
#   check); (2) the committed corpus table (test/release/
#   diagnostics-corpus/multi-phase → src/z.deal), compiled twice at the
#   committed in-tree paths. Both runs' full stderr bytes and
#   --diagnostics-json document bytes must be byte-identical per member;
#   the two digests are recorded as diagnosticsStderrSha256 and
#   diagnosticsJsonSha256.
#
#   leg (b) artifacts — the committed fixed project
#   test/release/artifact-project/ compiles twice into two fresh output
#   roots; the two artifact trees must be byte-identical per file
#   (same sorted relative-path set, equal bytes), and the canonical
#   (run-1) tree digest is recorded as artifactTreeDigest.
#
# Success: exit 0 and exactly three stdout lines —
# diagnosticsStderrSha256=<64hex>, diagnosticsJsonSha256=<64hex>,
# artifactTreeDigest=<64hex> (lowercase hex, one per line); all progress
# output goes to stderr. Any byte difference, a missing expected code, a
# stale exclusion entry, an empty scan, a malformed header, or a missing
# committed fixture/project/entry prints RELEASE_DETERMINISM_FAILED on
# stderr naming the first differing fixture/member and surface with the
# first differing line (leg a) or the first differing file (leg b) and
# exits nonzero. Post-state: the fixed scratch root
# .release-determinism-scratch is trap-removed on success and failure;
# nothing else is modified (no golden expected bytes are committed — the
# property is run-to-run byte identity).
set -euo pipefail

cd "$(dirname "$0")/.."

# Byte-stable collation for the member scan, the companion scan, and
# every find/sort order feeding a digest; pinned so the digests never
# depend on the caller's environment.
LC_ALL=C
export LC_ALL

SHA256SUM=/usr/bin/sha256sum

# One fixed scratch root under the repo root: removed and recreated
# fresh at start; trap-removed on every exit (success, failure, signal).
# Every scratch artifact (classes dir, per-fixture projects,
# stderr/stdout/JSON captures, artifact output roots, digest inputs)
# lives under it, so both runs of every pair share identical absolute
# paths — byte-identical inputs to both runs — and the emitted digests
# are reproducible across script invocations.
SCRATCH=".release-determinism-scratch"

fail() {
    printf 'RELEASE_DETERMINISM_FAILED: %s\n' "$*" >&2
    exit 1
}

rm -rf "$SCRATCH"
mkdir -p "$SCRATCH"
trap 'rm -rf "$SCRATCH"' EXIT
trap 'rm -rf "$SCRATCH"; exit 130' HUP INT TERM

# ----------------------------------------------------------------------
# Compiler acquisition: one production class build for the whole script.
# The source set is every .java file under deal/ except files under
# deal/test/ and files named *Test.java (the engine gate's production
# set). A production reference to an excluded class fails this compile
# loudly under set -e.
# ----------------------------------------------------------------------
PROD_SOURCES=()
while IFS= read -r -d '' f; do
    PROD_SOURCES+=("$f")
done < <(find deal -name '*.java' ! -path 'deal/test/*' ! -name '*Test.java' \
    -print0 | LC_ALL=C sort -z)

if [ "${#PROD_SOURCES[@]}" -eq 0 ]; then
    fail "production source scan found no .java files"
fi

printf 'release-determinism: compiling %d production sources\n' \
    "${#PROD_SOURCES[@]}" >&2
mkdir -p "$SCRATCH/classes" "$SCRATCH/json" "$SCRATCH/out" "$SCRATCH/err" \
    "$SCRATCH/digest"
javac --release 25 -proc:none -d "$SCRATCH/classes" "${PROD_SOURCES[@]}"

# ----------------------------------------------------------------------
# Classification-header strip: the byte-for-byte mirror of
# test/ConformanceHarnessMetadata.java stripClassificationHeaders for the
# corpus's LF/CRLF files — a full line whose trimmed form starts with one
# of the five exact prefixes is dropped together with its terminator;
# every other line (and its terminator) is copied verbatim. Real
# directive lines (// @deal-version ..., // @c-struct ...,
# // @not-a-directive, ...) are not classification headers and survive
# verbatim. The in-tree originals are never modified; the header scan and
# expected-code capture still read the original in-tree bytes.
# ----------------------------------------------------------------------
strip_classification_headers() { # $1: source file; stdout: stripped bytes
    sed -e '/^[[:space:]]*\/\/ @spec:/d' \
        -e '/^[[:space:]]*\/\/ @description:/d' \
        -e '/^[[:space:]]*\/\/ @expected:/d' \
        -e '/^[[:space:]]*\/\/ @features:/d' \
        -e '/^[[:space:]]*\/\/ @issue:/d' \
        "$1"
}

# No copied file may contain a surviving classification-header line — a
# line whose trimmed form starts with one of the five prefixes — or the
# script fails naming the file (a surviving classification line becomes
# one E1044 and masks the fixture's own expected code, which must never
# silently pass).
check_no_surviving_headers() { # $1: stripped copy, $2: original rel path
    if grep -qE '^[[:space:]]*// @(spec|description|expected|features|issue):' \
            "$1"; then
        fail "stripped copy $1 (from $2) still contains a classification-header line"
    fi
}

# The @expected value of a fixture, mirroring ConformanceTest.parseMetadata:
# the first 40 lines, last occurrence wins, values trimmed like
# String.trim() (leading/trailing spaces, tabs, and CR).
read_expected() { # $1: file; stdout: the @expected value
    local file=$1 line tline expected="" n=0
    while IFS= read -r line; do
        n=$((n + 1))
        [ "$n" -gt 40 ] && break
        tline=$line
        tline="${tline#"${tline%%[!$'\t'$'\r' ]*}"}"
        tline="${tline%"${tline##*[!$'\t'$'\r' ]}"}"
        case "$tline" in
            '// @expected:'*)
                expected="${tline#'// @expected:'}"
                expected="${expected#"${expected%%[!$'\t'$'\r' ]*}"}"
                expected="${expected%"${expected##*[!$'\t'$'\r' ]}"}"
                ;;
        esac
    done < "$file"
    printf '%s' "$expected"
}

# ----------------------------------------------------------------------
# Header scan (leg a, conformance member set): fixed deterministic member
# order — lexicographic by repo-relative path (LC_ALL=C byte order).
# Exactly one code of the shape E[0-9]{4} per member; a malformed header
# fails the script. A zero-fixture scan fails the script (an empty leg
# must not pass).
# ----------------------------------------------------------------------
MEMBERS=()          # repo-relative paths in byte order
declare -A MEMBER_CODE   # repo-relative path -> expected code
while IFS= read -r -d '' f; do
    rel="${f#./}"
    expected=$(read_expected "$f")
    case "$expected" in
        'compile-error '*)
            code="${expected#'compile-error '}"
            case "$code" in
                E[0-9][0-9][0-9][0-9]) ;;
                *) fail "malformed @expected '$expected' in $rel (a compile-error expectation needs exactly one E<4-digit> code)" ;;
            esac
            MEMBERS+=("$rel")
            MEMBER_CODE[$rel]=$code
            ;;
        *) : ;; # companion / compile-ok / runtime-* / known-fail / ... never scanned
    esac
done < <(find test/conformance -type f -name '*.deal' -print0 | LC_ALL=C sort -z)

if [ "${#MEMBERS[@]}" -eq 0 ]; then
    fail "header scan found no compile-error members (an empty leg must not pass)"
fi

# ----------------------------------------------------------------------
# Closed pinned exclusion list: scanned members whose expected code the
# PUBLIC_BUILD/LEGACY_SAFE_INT surface cannot emit. The script compiles
# every member through deal.Main compile, whose single PUBLIC_BUILD
# invocation derives from ReleaseConfiguration.CURRENT_RELEASE_STATE =
# PRE_ACTIVATION -> CompilerProfileProvider.publicProfile ->
# LEGACY_SAFE_INT (deal/semantic/ReleaseConfiguration.java,
# deal/semantic/CompilerProfileProvider.java), and deal/Main.java exposes
# no profile or purpose option. The leg's own anti-hollow check fails a
# correct pipeline for any unreachable member left in the set, so this
# list is exactly the reachability boundary of the current release
# surface. Every entry must match an actually scanned member (a stale
# entry fails the script loudly); excluded members are removed from the
# member set before compilation; the member set must remain non-empty
# after exclusion.
#
# (1) test/conformance/frontend/lexer/int32-literal-out-of-range.deal —
# its expected code E1036 is emitted only under
# SemanticProfile.DEAL_V1_2_INT32 — the v1.2 int32 parse gate in
# deal/parser/Parser.java (parsePrimary's INT_LITERAL arm raises E1036
# for values above 2147483647 only when the profile is DEAL_V1_2_INT32;
# the -2147483648 immediate-token rule is likewise profile-gated) — while
# the script compiles every member through deal.Main compile, whose
# single PUBLIC_BUILD invocation derives from
# ReleaseConfiguration.CURRENT_RELEASE_STATE = PRE_ACTIVATION ->
# CompilerProfileProvider.publicProfile -> LEGACY_SAFE_INT
# (deal/semantic/ReleaseConfiguration.java,
# deal/semantic/CompilerProfileProvider.java), and deal/Main.java exposes
# no profile or purpose option. Under the legacy parse contract (E1036
# only beyond Long.parseLong range) the fixture's literal 2147483648
# parses clean, so its own expected code can never appear and the leg's
# anti-hollow check would fail a correct pipeline. Its three sibling
# fixtures below are excluded for the same pinned reason: every one of
# their int literals (2147483648 in the parenthesized and binary forms,
# 2147483649 under unary minus) parses clean under the legacy contract.
#
# (2) The nine fixtures whose ISSUE-0106 whole-project entry wrap left
# two export function main declarations in one file: the duplicate-main
# E2002 name-resolution error gates phase 3 (typeCheckAll skips the
# checker for a module with name-resolution errors), so the fixture's own
# checker code — E3001/E5001/E5004 — can never appear through
# deal.Main compile, while the in-memory harness (never the gated
# orchestrator) reports both errors.
#
# (3) test/conformance/frontend/lexer/dollar-sign-prohibition.deal — its
# expected E2008 has NameResolver as the sole emission site, but the
# fixture's deliberately nonexistent import ("./nonexistent_lib") fails
# E2003 in phase 0, which gates before name resolution, so E2008 can
# never appear through deal.Main compile.
# ----------------------------------------------------------------------
EXCLUSIONS=(
    # (1) the E1036 v1.2-int32 profile-gated fixtures
    'test/conformance/frontend/lexer/int32-literal-out-of-range.deal'
    'test/conformance/frontend/lexer/int32-min-parenthesized-token-rejected.deal'
    'test/conformance/frontend/lexer/int32-min-token-in-binary-expression.deal'
    'test/conformance/frontend/lexer/int32-below-min-via-unary-minus.deal'
    # (2) the duplicate-main fixtures whose checker code the E2002 gate
    # keeps unreachable
    'test/conformance/frontend/async-await/async-func-not-assignable.deal'
    'test/conformance/frontend/async-await/sync-func-not-assignable.deal'
    'test/conformance/frontend/classes/class-table-invariant.deal'
    'test/conformance/frontend/classes/nominal-mismatch.deal'
    'test/conformance/frontend/functions/async-to-sync-function-type-mismatch.deal'
    'test/conformance/frontend/functions/function-type-arity-mismatch.deal'
    'test/conformance/frontend/functions/reverse-arity.deal'
    'test/conformance/frontend/functions/wrong-arg-types.deal'
    'test/conformance/frontend/type-system/function-parameter-variance-mismatch.deal'
    # (3) the E2008 fixture whose import fails E2003 in phase 0
    'test/conformance/frontend/lexer/dollar-sign-prohibition.deal'
)

declare -A EXCLUDED=()
for rel in "${EXCLUSIONS[@]}"; do
    if [ -n "${MEMBER_CODE[$rel]:-}" ]; then
        EXCLUDED[$rel]=1
    else
        fail "stale exclusion entry: $rel is not a scanned compile-error member"
    fi
done

remaining=0
for rel in "${MEMBERS[@]}"; do
    if [ -z "${EXCLUDED[$rel]:-}" ]; then
        remaining=$((remaining + 1))
    fi
done
if [ "$remaining" -eq 0 ]; then
    fail "every scanned member is excluded (the member set must remain non-empty)"
fi
printf 'release-determinism: leg (a) scanned %d compile-error members; %d excluded; compiling %d\n' \
    "${#MEMBERS[@]}" "${#EXCLUDED[@]}" "$remaining" >&2

# ----------------------------------------------------------------------
# Digest inputs: over the fixed deterministic member order (sorted
# non-excluded conformance fixtures, then the corpus-table order), each
# member contributes its compiled-entry repo-relative path + \n + its
# run-1 stderr bytes + \n (the two runs are already byte-verified equal);
# the same framing over the run-1 JSON documents.
# ----------------------------------------------------------------------
STDERR_DIGEST_IN="$SCRATCH/digest/stderr.bin"
JSON_DIGEST_IN="$SCRATCH/digest/json.bin"
: > "$STDERR_DIGEST_IN"
: > "$JSON_DIGEST_IN"

append_digest_member() { # $1: path line, $2: run-1 stderr file, $3: run-1 json file
    printf '%s\n' "$1" >> "$STDERR_DIGEST_IN"
    cat "$2" >> "$STDERR_DIGEST_IN"
    printf '\n' >> "$STDERR_DIGEST_IN"
    printf '%s\n' "$1" >> "$JSON_DIGEST_IN"
    cat "$3" >> "$JSON_DIGEST_IN"
    printf '\n' >> "$JSON_DIGEST_IN"
}

first_diff_line() { # $1: file a, $2: file b; stdout: first differing line
    diff --old-line-format='run1: %L' --new-line-format='run2: %L' \
        --unchanged-line-format='' "$1" "$2" | head -n 1 || true
}

# One double-run pair through the real CLI: two fresh JVM invocations,
# the JSON document and the full stderr/stdout captured per run under the
# scratch (each CLI invocation's stdout never reaches the script's own
# stdout; both runs share identical absolute paths).
run_compile_pair() { # $1: entry, $2: capture key
    local entry=$1 key=$2
    set +e
    java -cp "$SCRATCH/classes" deal.Main compile "$entry" \
        --diagnostics-json "$SCRATCH/json/$key-1.json" \
        > "$SCRATCH/out/$key-1.out" 2> "$SCRATCH/err/$key-1.err"
    set -e
    set +e
    java -cp "$SCRATCH/classes" deal.Main compile "$entry" \
        --diagnostics-json "$SCRATCH/json/$key-2.json" \
        > "$SCRATCH/out/$key-2.out" 2> "$SCRATCH/err/$key-2.err"
    set -e
}

# Byte-identical both surfaces across the two runs; failure names the
# first differing member and surface (stderr/json) with the first
# differing line.
check_pair_bytes() { # $1: capture key, $2: member path for the message
    local key=$1 member=$2
    if ! cmp -s "$SCRATCH/err/$key-1.err" "$SCRATCH/err/$key-2.err"; then
        fail "member $member surface stderr differs between run 1 and run 2; first differing line: '$(first_diff_line "$SCRATCH/err/$key-1.err" "$SCRATCH/err/$key-2.err")'"
    fi
    if ! cmp -s "$SCRATCH/json/$key-1.json" "$SCRATCH/json/$key-2.json"; then
        fail "member $member surface json differs between run 1 and run 2; first differing line: '$(first_diff_line "$SCRATCH/json/$key-1.json" "$SCRATCH/json/$key-2.json")'"
    fi
}

# Anti-hollow: the fixture's own @expected code must appear in both runs'
# stderr bytes (the formatter's "ERROR <CODE>:" token) and in both JSON
# documents ("\"code\": \"<CODE>\""). A fixture whose expected code never
# appears fails the script naming the fixture and the expected code — an
# E2010-only or E1044-only leg cannot pass.
check_expected_code() { # $1: capture key, $2: member path, $3: expected code
    local key=$1 member=$2 code=$3 n1 n2 nj1 nj2
    n1=$(grep -F -c "ERROR $code:" "$SCRATCH/err/$key-1.err" || true)
    n2=$(grep -F -c "ERROR $code:" "$SCRATCH/err/$key-2.err" || true)
    nj1=$(grep -F -c "\"code\": \"$code\"" "$SCRATCH/json/$key-1.json" || true)
    nj2=$(grep -F -c "\"code\": \"$code\"" "$SCRATCH/json/$key-2.json" || true)
    if [ "$n1" -lt 1 ] || [ "$n2" -lt 1 ] || [ "$nj1" -lt 1 ] || [ "$nj2" -lt 1 ]; then
        fail "fixture $member: expected diagnostic code $code never appears (stderr run1=$n1 run2=$n2, json run1=$nj1 run2=$nj2)"
    fi
}

# ----------------------------------------------------------------------
# Leg (a) conformance members: per non-excluded fixture a scratch project
# <scratch>/conformance/<key>/ (key = repo-relative path with path
# separators replaced by _) with the generated pinned manifest
# {"languageVersion":"1.2","backend":"luajit","moduleRoots":["src"]} (the
# strict v1.2 validator accepts exactly luajit|jvm; the lua spelling is
# E2010 and would kill the leg). The fixture (original file name) and
# every @expected: companion file from the fixture's own in-tree
# directory are copied into <scratch>/conformance/<key>/src/ so relative
# imports (./shadow_lib etc.) resolve and so the moduleRoots:["src"] pin
# gives fixture classes a public module identity (otherwise the
# identity-less E2010 gate fires for class-bearing fixtures and masks
# their own diagnostics); companions from subdirectories keep their
# relative path so subdirectory imports (./support/int_parameter_lib)
# resolve too. The copies are classification-header-stripped before
# compilation.
# ----------------------------------------------------------------------
for rel in "${MEMBERS[@]}"; do
    if [ -n "${EXCLUDED[$rel]:-}" ]; then
        continue
    fi
    code=${MEMBER_CODE[$rel]}
    name=$(basename "$rel")
    key="${rel//\//_}"
    dir=$(dirname "$rel")
    proj="$SCRATCH/conformance/$key"
    mkdir -p "$proj/src"
    printf '%s' '{"languageVersion":"1.2","backend":"luajit","moduleRoots":["src"]}' \
        > "$proj/deal.json"
    entry="$PWD/$proj/src/$name"
    strip_classification_headers "$rel" > "$proj/src/$name"
    check_no_surviving_headers "$proj/src/$name" "$rel"
    while IFS= read -r -d '' comp; do
        comprel="${comp#"$dir"/}"
        mkdir -p "$proj/src/$(dirname "$comprel")"
        strip_classification_headers "$comp" > "$proj/src/$comprel"
        check_no_surviving_headers "$proj/src/$comprel" "$comp"
    done < <(find "$dir" -type f -name '*.deal' -print0 | LC_ALL=C sort -z \
        | while IFS= read -r -d '' c; do
              [ "$c" = "$rel" ] && continue
              if [ "$(read_expected "$c")" = 'companion' ]; then
                  printf '%s\0' "$c"
              fi
          done)
    run_compile_pair "$entry" "$key"
    check_pair_bytes "$key" "$rel"
    check_expected_code "$key" "$rel" "$code"
    append_digest_member "$SCRATCH/conformance/$key/src/$name" \
        "$SCRATCH/err/$key-1.err" "$SCRATCH/json/$key-1.json"
done

# ----------------------------------------------------------------------
# Leg (a) corpus table: the fixed corpus table lists each committed
# corpus project with its entry; every listed project compiles twice in
# fresh JVMs at the committed in-tree paths with --diagnostics-json
# (capture files under the scratch); per-project stderr and JSON
# documents byte-compare across the two runs. A corpus-table entry whose
# project or entry is missing fails the script loudly. The corpus lives
# outside test/conformance/, so the conformance runners ignore it.
# ----------------------------------------------------------------------
CORPUS_TABLE=(
    'test/release/diagnostics-corpus/multi-phase|src/z.deal'
)
for record in "${CORPUS_TABLE[@]}"; do
    project="${record%%|*}"
    entry_rel="${record#*|}"
    if [ ! -f "$project/deal.json" ]; then
        fail "corpus-table entry $project has no committed deal.json"
    fi
    if [ ! -f "$project/$entry_rel" ]; then
        fail "corpus-table entry $project is missing its pinned entry $entry_rel"
    fi
    key="corpus-$(basename "$project")"
    run_compile_pair "$PWD/$project/$entry_rel" "$key"
    check_pair_bytes "$key" "$project/$entry_rel"
    append_digest_member "$project/$entry_rel" \
        "$SCRATCH/err/$key-1.err" "$SCRATCH/json/$key-1.json"
done

DIAG_STDERR_SHA=$("$SHA256SUM" "$STDERR_DIGEST_IN" | cut -d' ' -f1)
DIAG_JSON_SHA=$("$SHA256SUM" "$JSON_DIGEST_IN" | cut -d' ' -f1)

# ----------------------------------------------------------------------
# Leg (b) artifacts: the committed fixed artifact project
# test/release/artifact-project/ (multi-module, luajit backend, non-empty
# Lua artifact tree) compiles twice in fresh JVMs into two fresh output
# roots (--output <scratch>/artifacts/run1 and run2; a leading-/ CLI
# output is a valid ABSOLUTE_PATH override). Both compiles must exit 0
# with no diagnostics on stderr, and each CLI invocation's stdout is
# captured to scratch (the "Compilation successful" line must never reach
# the script's stdout). The two artifact trees must be byte-identical
# per file: the same sorted relative-path set and every file's bytes
# equal. The canonical (run-1) tree digest — SHA-256 over every file in
# sorted relative-path order with "<relative-path>\n" then file content
# appended to the digest input — is recorded as artifactTreeDigest.
#
# The project's entry exports a valid main(): null and constructs an
# imported class so the generated main.lua carries the
# findImportAliasForClass-selected reference: src/lib.deal exports class C
# with no required fields and is imported under two aliases pinned so
# String.hashCode's HashMap bucket order inverts definition order on
# every JDK — "import * as b from \"./lib\"" (hash 98 -> bucket 2 in a
# default-capacity-16 HashMap) declared before
# "import * as aa from \"./lib\"" (hash 3104 -> bucket 0) — and main
# constructs "let v: b.C = { };". With the LinkedHashMap storage field
# (deterministic-diagnostics D2) the earliest-defined alias b wins the
# emitted reference; with a HashMap-backed field the bucket-first winner
# would be aa. The script's own double-run byte comparison cannot detect
# a single-JDK bucket-order drift; the emitted-reference check over the
# generated main.lua is what makes a missing storage pin fail the
# artifact leg's verification (b.C_plan present, aa.C_plan absent in the
# current class-plan emission surface).
# ----------------------------------------------------------------------
ARTIFACT_PROJECT='test/release/artifact-project'
ARTIFACT_ENTRY='src/main.deal'
if [ ! -f "$ARTIFACT_PROJECT/deal.json" ]; then
    fail "committed artifact project $ARTIFACT_PROJECT/deal.json missing"
fi
if [ ! -f "$ARTIFACT_PROJECT/$ARTIFACT_ENTRY" ]; then
    fail "committed artifact project entry $ARTIFACT_PROJECT/$ARTIFACT_ENTRY missing"
fi

mkdir -p "$SCRATCH/artifacts"
for run in run1 run2; do
    set +e
    java -cp "$SCRATCH/classes" deal.Main compile \
        "$PWD/$ARTIFACT_PROJECT/$ARTIFACT_ENTRY" \
        --output "$PWD/$SCRATCH/artifacts/$run" \
        > "$SCRATCH/out/artifact-$run.out" 2> "$SCRATCH/err/artifact-$run.err"
    rc=$?
    set -e
    if [ "$rc" -ne 0 ]; then
        fail "artifact project compile $run exited $rc"
    fi
    if [ -s "$SCRATCH/err/artifact-$run.err" ]; then
        fail "artifact project compile $run produced stderr diagnostics: $(head -n 1 "$SCRATCH/err/artifact-$run.err")"
    fi
done

list_tree() { # $1: output root; stdout: sorted relative paths (LC_ALL=C)
    ( cd "$1" && find . -type f -printf '%P\n' ) | LC_ALL=C sort
}

list_tree "$SCRATCH/artifacts/run1" > "$SCRATCH/artifacts/run1.files"
list_tree "$SCRATCH/artifacts/run2" > "$SCRATCH/artifacts/run2.files"
if ! cmp -s "$SCRATCH/artifacts/run1.files" "$SCRATCH/artifacts/run2.files"; then
    fail "artifact trees differ in their file sets; first differing file: '$(first_diff_line "$SCRATCH/artifacts/run1.files" "$SCRATCH/artifacts/run2.files")'"
fi
while IFS= read -r rel; do
    if ! cmp -s "$SCRATCH/artifacts/run1/$rel" "$SCRATCH/artifacts/run2/$rel"; then
        fail "artifact file differs between run 1 and run 2; first differing file: $rel"
    fi
done < "$SCRATCH/artifacts/run1.files"

ARTIFACT_DIGEST_IN="$SCRATCH/artifacts/digest.bin"
: > "$ARTIFACT_DIGEST_IN"
while IFS= read -r rel; do
    printf '%s\n' "$rel" >> "$ARTIFACT_DIGEST_IN"
    cat "$SCRATCH/artifacts/run1/$rel" >> "$ARTIFACT_DIGEST_IN"
done < "$SCRATCH/artifacts/run1.files"
ARTIFACT_DIGEST=$("$SHA256SUM" "$ARTIFACT_DIGEST_IN" | cut -d' ' -f1)

# ----------------------------------------------------------------------
# Success output: exit 0 and exactly three lines on stdout — one per
# digest, lowercase 64-hex, nothing else on stdout (E8 owns parsing and
# recording into the evidence gates.determinism.detail record; this
# script records nothing into any evidence document).
# ----------------------------------------------------------------------
printf 'diagnosticsStderrSha256=%s\n' "$DIAG_STDERR_SHA"
printf 'diagnosticsJsonSha256=%s\n' "$DIAG_JSON_SHA"
printf 'artifactTreeDigest=%s\n' "$ARTIFACT_DIGEST"
