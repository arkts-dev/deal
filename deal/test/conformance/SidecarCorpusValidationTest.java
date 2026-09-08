package deal.test.conformance;

import deal.ast.ImportDeclaration;
import deal.ast.StatementNode;
import deal.test.ConformanceHarnessMetadata;
import deal.checker.CheckResult;
import deal.checker.ModuleResolver;
import deal.checker.ModuleResolver.ModuleNotFoundException;
import deal.checker.NameResolver;
import deal.checker.Symbol;
import deal.checker.SymbolTable;
import deal.checker.TypeChecker;
import deal.diagnostics.CompilerDiagnostic;
import deal.lexer.Lexer;
import deal.lexer.LexResult;
import deal.module.ModuleShapeValidator;
import deal.parser.ParseResult;
import deal.parser.Parser;
import deal.semantic.ir.CanonicalJson;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * ISSUE-0349 (T2) + ISSUE-0350 (T3) corpus-sidecar validation: the
 * complete sidecar population authored for the corpus is schema-v1 valid
 * and complete.
 *
 * <p>Pins, per the tasks' completeness obligations (the Zero-Skip
 * Classification Contract makes each sidecar set a single completeness
 * obligation, so the whole runtime-ok set and the whole runtime-error set
 * each land in one change):</p>
 * <ol>
 *   <li>Every {@code @expected: runtime-ok} fixture under
 *       {@code test/conformance/backend-runtime/} carries exactly one
 *       sibling {@code <fixture>.expect.json} that validates clean
 *       against {@link SidecarSchemaValidator} schema version 1: exactly
 *       the three backends, mode {@code runtime-ok}, exit code 0, no
 *       {@code error}/{@code sourceFile} field anywhere.</li>
 *   <li>Transcripts follow the C4 authoring rules: stderr is empty
 *       everywhere and stdout is empty except the single
 *       {@code std/console} fixture, whose stdout is exactly
 *       {@code "hello\n"} (the {@code console.log("hello")} string
 *       literal plus the canonical trailing newline of all three
 *       backends).</li>
 *   <li>Every {@code @expected: runtime-error CODE} fixture under
 *       {@code test/conformance/backend-runtime/} carries exactly one
 *       sibling sidecar that validates clean against schema version 1:
 *       exactly the three backends, mode {@code runtime-error}, exit
 *       code 1, empty stderr, and the exact G4.6 lane error framing on
 *       stdout ({@code DEAL_ERROR_CODE: <code>} then
 *       {@code DEAL_ERROR_SNAPSHOT: <canonical JSON>}). The Error
 *       Expectation is the authoritative field set: mandatory

 *       {@code code}/{@code message} plus the span group
 *       {@code sourceFile}/{@code line}/{@code column} (the code equals
 *       the fixture's {@code @expected} code; the sourceFile names the
 *       module that threw — the fixture itself or a corpus module of
 *       its transitive import closure — in canonical corpus-relative
 *       form). The span group is mandatory for every runtime-error
 *       fixture except the one sanctioned span-less shape
 *       ({@code stdlib-edge/time-now-millis-positive.deal}: the locked
 *       time selector's retained {@code nowMillis} wrapper raises E8004
 *       with no file/line/column at all —
 *       {@code luajit-time-selector-disposition}, Failure and
 *       operations — so its sidecar omits the whole group and pinning
 *       any of the three is a classification failure), optional
 *       {@code expected}/ {@code actual} pinned as the {@code _err}
 *       pair where the spec diagnostic carries them, and no
 *       {@code frames}/{@code cause} pins (today's runtime error values
 *       carry no structured frames; the cause chain is pinned nowhere).
 *       The snapshot JSON of the framing must byte-equal the canonical
 *       snapshot serialization recomputed from the error object: fixed
 *       key order {@code code, message, sourceFile, line, column,
 *       expected, actual, frames, cause}, minimal RFC 8259 §7 escaping,
 *       raw UTF-8, canonical decimal integers, no whitespace between
 *       tokens.</li>
 *   <li>The backend-runtime known-fail population is empty. ISSUE-0378
 *       restored {@code arithmetic/int-add-overflow.deal} byte-exactly
 *       with its canonical known-fail header and its three-backend
 *       runtime-error sidecar; the gate-closure promotion then dropped
 *       the known-fail marker, leaving the fixture as a real
 *       runtime-error fixture whose uniform E8004 sidecar stays. The
 *       two-backend slice re-home
 *       ({@code jvm-int32-slice.json#int32-add-overflow}) stays.
 *       ISSUE-0339 promoted the last previously tracked fixture
 *       ({@code bytes-buffer-ops.deal}, which received its runtime-ok
 *       sidecar in the same change that dropped its known-fail
 *       marker). ISSUE-0502 (the gap-suite runtime population
 *       landing) adds the gap bytes fixture
 *       {@code backend-runtime/bytes/bytes-boundary-order.deal}
 *       <b>promoted</b> — the issue's retained-known-fail candidate
 *       ({@code known-fail runtime-ok}, {@code @issue: ISSUE-0111})
 *       was probed on the real lanes this run and passes on both
 *       (ISSUE-0158 lifted the E3019 bytes-equality gate with the
 *       BYTES_EQ/NE comparison row, so bytes compare by reference
 *       identity today), and the zero-skip promotion gate forces the
 *       stale marker off — it counts in the runtime-ok population
 *       with its empty-transcript three-backend runtime-ok sidecar
 *       (the presence rule, ISSUE-0353). The backend-runtime
 *       known-fail population therefore stays empty.</li>

 *   <li>All other fixtures ({@code compile-ok}, {@code compile-error},
 *       {@code companion}, frontend fixtures) carry no sidecar except
 *       the Diagnostics-bullet fixtures.</li>
 *   <li>The four Diagnostics-bullet fixtures
 *       ({@code frontend/diagnostics/assignment-mismatch.deal},
 *       {@code frontend/diagnostics/return-mismatch.deal},
 *       {@code frontend/diagnostics/039-diagnostic-exact-span.deal},
 *       {@code frontend/diagnostics/041-diagnostic-assignment-location.deal})
 *       carry a
 *       Compile Expectation Sidecar that validates clean, and its pin
 *       matches the real frontend diagnostic set field-exact: exactly one
 *       error diagnostic, equal {@code code}/{@code line}/{@code column}/
 *       {@code message} (the Compile Diagnostic comparison of the gate,
 *       run here against the authored pins).</li>
 * </ol>
 *
 * <p>The test runs from the repository root (the {@code run_tests.sh}
 * contract, like {@code ConformanceTest}); it performs read-only I/O on
 * {@code test/conformance/} and mutates nothing.</p>
 */
public class SidecarCorpusValidationTest {

    private static int passed = 0;
    private static int failed = 0;

    /** The corpus root, relative to the repository root. */
    private static final Path CORPUS_ROOT = Path.of("test", "conformance");

    /** The Diagnostics-bullet fixtures carrying Compile Expectation Sidecars. */
    private static final String DIAG_ASSIGNMENT =
        "frontend/diagnostics/assignment-mismatch.deal";
    private static final String DIAG_RETURN =
        "frontend/diagnostics/return-mismatch.deal";
    private static final String DIAG_EXACT_SPAN =
        "frontend/diagnostics/039-diagnostic-exact-span.deal";
    private static final String DIAG_ASSIGNMENT_LOCATION =
        "frontend/diagnostics/041-diagnostic-assignment-location.deal";

    /** The only runtime-ok fixture whose transcript carries stdout bytes. */
    private static final String CONSOLE_FIXTURE =
        "backend-runtime/stdlib/console/import-log.deal";
    private static final String CONSOLE_STDOUT = "hello\n";

    /** The exact runtime-ok population (ISSUE-0349 completeness, plus
     * the stdlib/math int-minmax-extremes fixture ISSUE-0337 added with
     * its sidecar in the same change; plus the two async fixtures
     * ISSUE-0335 added with their sidecars in the same change:
     * direct-await-completion-values and async-cross-module-chain; plus
     * the four canonical-boundary runtime-ok fixtures ISSUE-0336 added
     * with their sidecars in the same change; plus the six bytes
     * fixtures ISSUE-0339 added/promoted with their sidecars in the
     * same change: the promoted bytes-buffer-ops and the new
     * bytes-length, bytes-descriptor-boundary,
     * bytes-class-field-descriptor, bytes-write-single-evaluation, and
     * bytes-write-validation-order fixtures; plus the two stdlib/json
     * D5 fixtures ISSUE-0342 added with their sidecars in the same
     * change: int32-boundary-parse and json-stringify-roundtrip; plus
     * the ISSUE-0397 I6 arithmetic/int32-mod-min-neg-one fixture — the
     * truncated remainder -2147483648 % -1 == 0 on all three lanes —
     * added with its uniform sidecar in the same change; plus the
     * host-class-default-isolation fixture ISSUE-0334 added with its
     * sidecar in the same change; plus the three stdlib/table keys
     * fixtures ISSUE-0341 added with their sidecars in the same
     * change: keys-string-inclusion, keys-nonstring-exclusion, and
     * keys-order-pin; plus the six ISSUE-0340 default-plan runtime-ok
     * fixtures — plan-fresh-literals, plan-reexecuted-calls,
     * plan-load-time-zero-invocations, plan-imported-provider-scope,
     * plan-phase-order-provided-before-defaults, and
     * plan-host-discriminator — each added with its sidecar in the
     * same change; plus the jsonable-fromjson-nested-depth3 fixture
     * the ISSUE-0340 review fix added with its sidecar in the same
     * change, pinning the depth >= 3 nested compiler-class fromJson

         * decode). ISSUE-0380 (the disposition-application unit) flips the
     * shared time fixture
     * {@code stdlib-edge/time-now-millis-positive.deal} to its canonical
     * {@code runtime-error E8004} header, moving it out of this
     * population: 219 -> 218. ISSUE-0547 (the ISSUE-0160 container
     * step) adds the two bytes-container fixtures —
     * {@code backend-runtime/bytes/bytes-array-container-ops.deal} and
     * {@code backend-runtime/bytes/bytes-nested-arrays.deal} — with
     * their three-backend runtime-ok sidecars in the same change:
     * 218 -> 220. ISSUE-0502 (the gap-suite runtime population
     * landing) adds twenty-nine runtime-ok fixtures with their
     * empty-transcript three-backend sidecars in the same change:
     * gap runtime/ +5 (reference-identity-composite,
     * int32-div-rem-boundaries — promoted, await-effects-and-catch-order,
     * cross-module-shared-state-closure, and the re-authored
     * fromjson-failure-no-partial-object; the sixth, aggregate-call-order,
     * lands with the destination-resolution epic EA), data-model +10
     * (the three promoted bytes fixtures bytes-class-default,
     * bytes-write-zero, bytes-zero-length; the two classes fixtures;
     * the three jsonable fixtures; the two tables fixtures),
     * lexical-syntax +8 (ls-008/009/017/025/029/030/031/032), and
     * runtime-control +6 (rtc-004, rtc-008, rtc-015, rtc-033,
     * rtc-036, rtc-038): 220 -> 249; plus the promoted gap
     * bytes-boundary-order fixture — the issue's retained-known-fail
     * candidate passes both lanes after ISSUE-0158 lifted the E3019
     * bytes-equality gate, so the zero-skip promotion gate forces
     * the marker off and the fixture lands runtime-ok with its
     * sidecar: 220 -> 250. ISSUE-0504 (the host ABI conversion leaf
     * of v12-gap-suite-integration, D9/D5) lands the eight converted
     * host-boundary runtime-ok fixtures with their uniform
     * three-backend sidecars in the same change:
     * host-boundary-repeat-call, host-boundary-null-narrowing,
     * host-boundary-int-minimum-param, host-boundary-number-roundtrip,
     * host-boundary-boolean-roundtrip,
     * host-boundary-unicode-string-roundtrip,
     * host-boundary-nullable-int-null-roundtrip, and
     * host-boundary-nullable-int-value-roundtrip — the host +8 delta:
     * 250 -> 258. ISSUE-0160 (the recursive bytes-bearing closure)
     * adds the three closure fixtures with their three-backend
     * runtime-ok sidecars: 258 -> 261. */

    private static final int RUNTIME_OK_COUNT = 261;

    /**
     * The exact runtime-error population (ISSUE-0350 completeness, plus
     * the int32 E8004 fixtures ISSUE-0332 promoted/added: the
     * restored int-add-overflow — ISSUE-0378 restored the
     * backend-runtime fixture byte-exactly with its canonical
     * known-fail header and its three-backend runtime-error sidecar,
     * and the gate-closure promotion dropped the known-fail marker,
     * so the fixture now counts in this population as a real
     * runtime-error fixture — and the new int-sub-overflow,
     * int-mul-overflow,
     * int-conversion-out-of-range, and source-location/int32-overflow-source
     * fixtures each land their sidecar in the same change as their
     * expectation; plus the stdlib/math int-abs-min-overflow E8004
     * fixture ISSUE-0337 added with its sidecar in the same change; plus
     * the host-async-shape-value E8010 fixture ISSUE-0335 added with its
     * sidecar in the same change; plus the canonical signature-mismatch
     * E8010 fixture ISSUE-0336 added with its sidecar in the same change;
     * plus the six int-neg/bytes error fixtures ISSUE-0339 added with
     * their sidecars in the same change: int-neg-min, bytes-index-bounds,
     * bytes-write-range, source-location/int-neg-min-source,
     * source-location/bytes-index-bounds-source, and
     * source-location/bytes-write-range-source; plus the
     * stdlib/json/json-stringify-bytes-error E8001 fixture ISSUE-0342
     * added with its sidecar in the same change; plus the two
     * ISSUE-0397 I6 arithmetic pow-band fixtures — int32-pow-overflow
     * (2 ** 62 → E8004, the finite band) and int32-pow-infinity
     * (2 ** 1024 → E8001 infinity, the NaN/infinity-first band) — each
     * added with its uniform three-backend sidecar in the same change;
     * plus the host-class-extra-field E8007 fixture ISSUE-0334 added
     * with its sidecar in the same change; plus the stdlib/table
     * keys-nontable-error E8001 fixture ISSUE-0341 added with its


         * sidecar in the same change). ISSUE-0380 (the
     * disposition-application unit) adds two members to this population
     * with their sidecars: the flipped time fixture
     * {@code stdlib-edge/time-now-millis-positive.deal} (E8004 at the
     * declared int boundary, sidecar re-authored in the same change) and
     * the promoted {@code arithmetic/int-add-overflow.deal} (marker
     * dropped, E8004 sidecar unchanged): 81 -> 83.
     *
     * ISSUE-0502 (the gap-suite runtime population landing) adds ten
     * runtime-error fixtures with their field-exact canonical-framing
     * sidecars in the same change: data-model +6 (the five promoted
     * bytes fixtures at the verified emitted codes E8012 x4 —
     * bytes-negative-length-error, bytes-negative-read-error,
     * bytes-read-at-length-error, bytes-write-at-length-error — and
     * E8013 x1 — bytes-write-negative-error — plus
     * table-missing-read-nonnullable-error E8001), runtime-control +1
     * (rtc-035-remainder-zero-error re-pinned to the emitted E8005),
     * and source-location +3 (036-runtime-source-array-oob re-pinned
     * to the emitted E8001, 037-runtime-source-div-zero E8005,
     * 038-runtime-source-throw PINNED_THROW): 83 -> 93.
 */
    private static final int RUNTIME_ERROR_COUNT = 93;



    /**
     * ISSUE-0504 count-pin amendment record (the MR-0305 record shape;
     * review cycle 1 of this MR). The issue's written criterion pins
     * this child's count movement as
     * {@code RUNTIME_OK_COUNT 249 -> 257} with
     * {@code RUNTIME_ERROR_COUNT} staying at 91 and the known-fail
     * population staying
     * {@code {backend-runtime/bytes/bytes-boundary-order.deal}} — a
     * pre-state (T3's 248 runtime-ok plus EA's aggregate-call-order)
     * and a final state authored against the ISSUE-0468 integration
     * tree. Those absolute values are unattainable in this tree: the
     * canonical revision (c9f7804b, the required rebase target)
     * landed the sibling T3 runtime population as ISSUE-0502 with its
     * own amendment record (above) — the population landed at 250
     * runtime-ok / 93 runtime-error with the tracked known-fail
     * population empty: ISSUE-0547 had moved the merge-base to 220/83,
     * ISSUE-0502 added +29 runtime-ok and +10 runtime-error, and the
     * bytes-boundary-order retained-known-fail candidate was promoted
     * to runtime-ok (ISSUE-0158 lifted the E3019 bytes-equality gate,
     * so the zero-skip promotion gate forces the stale marker off).
     * The EA aggregate-call-order fixture is still absent — it lands
     * with the destination-resolution epic EA, per the ISSUE-0502
     * record. The written pre-state population (249 runtime-ok / 91
     * runtime-error / known-fail {@code {bytes-boundary-order.deal}})
     * therefore does not exist in this tree; the criterion's written
     * deltas are the binding criterion and this child applies them
     * exactly: +8 runtime-ok (the eight converted host-boundary
     * fixtures), runtime-error unchanged, known-fail population
     * unchanged, landing the pins at 250 + 8 = 258 runtime-ok / 93
     * runtime-error / empty known-fail — the design's own Corpus
     * Count-Pin Amendment rule (the final absolute pins equal the
     * verified on-disk population after the change, and an amendment
     * record states the deltas). Pins at the written 257/91 with a
     * non-empty known-fail set would deterministically fail the
     * completeness checks (the corpus carries 258/93 with the
     * known-fail population empty), and re-marking the promoted
     * bytes-boundary-order fixture as known-fail would
     * deterministically fail the zero-skip promotion gate on both
     * lanes (ISSUE-0502 record). The issue-tracker update to the
     * amended wording is flagged on this MR for the issue authority
     * (the implementer's tooling cannot amend the issue record) — the
     * MR-0305 precedent.
     */
    /**
     * ISSUE-0397 count-pin criterion record (MR-0305 review cycles 1
     * and 2, the count-pin finding). The amended criterion — the
     * count-pin amendment prescribed by the MR-0305 review and applied
     * by this tree — pins the count movement as
     * {@code RUNTIME_OK_COUNT 207 -> 208} and
     * {@code RUNTIME_ERROR_COUNT 77 -> 79} with the known-fail
     * population unchanged at the empty set: the written delta (+1
     * runtime-ok, +2 runtime-error, known-fail unchanged) applied to
     * the evolved merge-base pins. The written absolute values
     * ({@code 192 -> 193}, {@code 63 -> 65}, known-fail
     * {@code {backend-runtime/bytes/bytes-buffer-ops.deal}}) were
     * authored against an earlier tree and are unattainable in this
     * one: at the MR's merge base (52a262da, the canonical revision;
     * content-identical to its ancestor 6c4fac5d — and 113c048 already
     * carried the same 207/77 pins with an empty known-fail
     * population) the corpus had grown through the
     * merged sibling issues ISSUE-0332 (signed32 gate), ISSUE-0335
     * (async), ISSUE-0336 (boundaries), ISSUE-0337 (math), ISSUE-0339
     * (bytes — which promoted
     * {@code backend-runtime/bytes/bytes-buffer-ops.deal} to a
     * runtime-ok fixture in commit 8d6a78f), and ISSUE-0342 (json).
     * This record states the amended criterion the tree implements and
     * verifies; the issue-tracker update to the amended wording is
     * flagged on MR-0305 for the issue authority (the implementer's
     * tooling cannot amend the issue record) — the MR-0146 precedent
     * resolved its equivalent criteria conflict on the same basis. The
     * amended values are the only pin set that keeps the corpus honest
     * and every gate green: the corpus carries 208/79, so pins at the
     * written 193/65 would deterministically fail the completeness
     * checks, and reverting the corpus to the written population would
     * revert unrelated merged sibling-issue work (out of scope).
     *
     * ISSUE-0334 lands on the amended 208/79 corpus and adds exactly
     * two host-class fixtures with their sidecars in the same change:
     * the runtime-ok host-class-default-isolation fixture (pins move
     * 208 -> 209) and the runtime-error E8007 host-class-extra-field
     * fixture (pins move 79 -> 80). The known-fail population stays
     * empty.
     *
     * ISSUE-0341 then lands on the 209/80 corpus and adds the three
     * stdlib/table keys runtime-ok fixtures (keys-string-inclusion,
     * keys-nonstring-exclusion, keys-order-pin) and the
     * keys-nontable-error runtime-error fixture with their sidecars in
     * the same change, moving the pins to 212/81. ISSUE-0340 then
     * lands on the 212/81 corpus and adds exactly six default-plan
     * runtime-ok fixtures with their sidecars in the same change —
     * plan-fresh-literals, plan-reexecuted-calls,
     * plan-load-time-zero-invocations, plan-imported-provider-scope,
     * plan-phase-order-provided-before-defaults, and
     * plan-host-discriminator (pins move 212 -> 218); the ISSUE-0340
     * review fix then adds the jsonable-fromjson-nested-depth3
     * runtime-ok fixture with its sidecar in the same change (pins
     * move 218 -> 219). The known-fail population stays empty. The
     * pins below carry those ISSUE-0334, ISSUE-0341, and ISSUE-0340
     * deltas.
     *
     * ISSUE-0502 count-pin amendment record (the MR-0305 record
     * shape): the issue criterion pins the movement as
     * {@code RUNTIME_OK_COUNT 219 -> 248} and
     * {@code RUNTIME_ERROR_COUNT 81 -> 91} with the tracked known-fail
     * pin moving from the empty set to
     * {@code {backend-runtime/bytes/bytes-boundary-order.deal}}. Those
     * absolute values were authored against the earlier design
     * baseline (workdir HEAD b23e66f, pins 219/81). This tree's
     * canonical revision (92f8af83, the required rebase target;
     * between the 8c97bc44 base and this revision the canonical
     * landed the zero-skip flip precondition record, the fail-closed
     * release gates with node preflight, the LuaJIT backend-runtime
     * gate closure, the JS corpus completion gate closure, the JVM
     * bytes typed-position/container layers, and the ISSUE-0477
     * promotion of the FFI-manifest known-fail pin) carries the
     * evolved post-unit corpus — ISSUE-0380 flipped
     * {@code stdlib-edge/time-now-millis-positive.deal} to its
     * canonical {@code runtime-error E8004} header and promoted the
     * restored {@code arithmetic/int-add-overflow.deal} (ISSUE-0378
     * D3), and ISSUE-0547 added the two bytes-container runtime-ok
     * fixtures, moving the merge-base pins to 220/83 — so the
     * written baseline values are unattainable here. The written
     * deltas are the binding criterion and this tree applies them
     * exactly: +29 runtime-ok (gap runtime/ +5 — the sixth,
     * aggregate-call-order, lands with the destination-resolution
     * epic EA; data-model +10; lexical-syntax +8; runtime-control +6)
     * plus the promoted bytes-boundary-order fixture, landing the
     * pins at 220 + 29 + 1 = 250, and +10 runtime-error (data-model
     * +6; runtime-control +1; source-location +3) landing the pins at
     * 83 + 10 = 93. The issue's known-fail pin
     * {@code {} -> {backend-runtime/bytes/bytes-boundary-order.deal}}
     * is unattainable in this tree and the pin therefore stays at
     * the empty set: the criterion's premise (both-lane E3019
     * probe-fail) no longer holds — ISSUE-0158 lifted the E3019
     * bytes-equality gate (the closed BYTES_EQ/BYTES_NE comparison
     * row admits bytes by reference identity, and
     * {@code deal/checker/TypeChecker.java} no longer rejects bytes
     * EQ/NEQ), the fixture passes both real lanes (verified this
     * run), and the zero-skip promotion gate fails a retained marker
     * deterministically (both lanes emit the promotion instruction).
     * The fixture therefore lands promoted as {@code runtime-ok}
     * with its empty-transcript three-backend sidecar, which makes
     * the landed absolute runtime-ok pin (250) the evolved-base
     * application of the written delta. The issue-tracker update to
     * the amended wording is flagged on this MR for the issue
     * authority (the implementer's tooling cannot amend the issue
     * record). The amended values are the only pin set that keeps
     * the corpus honest and every gate green: the landed corpus
     * carries 250/93 with the known-fail population empty, so pins
     * at the written 248/91 with a non-empty known-fail set would
     * deterministically fail the completeness checks.
     */

    /** The G4.6 lane error framing prefixes. */
    private static final String DEAL_ERROR_CODE_LINE = "DEAL_ERROR_CODE: ";
    private static final String DEAL_ERROR_SNAPSHOT_LINE = "DEAL_ERROR_SNAPSHOT: ";

    // =========================================================================
    // Assertion helpers
    // =========================================================================

    private static void check(boolean condition, String message) {
        if (condition) { passed++; }
        else { failed++; System.err.println("FAIL: " + message); }
    }

    private static void fail(String message) {
        failed++;
        System.err.println("FAIL: " + message);
    }

    // =========================================================================
    // Corpus discovery and classification
    // =========================================================================

    /**
     * One discovered corpus fixture: its corpus-relative slash path, its
     * source text, and its exact {@code @expected} tag.
     */
    private record Fixture(String corpusPath, String source,
                            String expectedTag, int headerLinesStripped) {}

    private static List<Fixture> discover() throws Exception {
        List<Fixture> fixtures = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(CORPUS_ROOT)) {
            List<Path> files = stream
                .filter(p -> p.toString().endsWith(".deal"))
                .filter(p -> {
                    Path rel = CORPUS_ROOT.relativize(p);
                    return rel.getNameCount() == 0
                        || !"host-fixtures".equals(rel.getName(0).toString());
                })
                .sorted()
                .toList();
            for (Path file : files) {
                String corpusPath = slash(CORPUS_ROOT.relativize(file));
                String raw = Files.readString(file);
                String expectedTag = expectedTagOf(raw, corpusPath);
                // ISSUE-0273: every compiler-facing surface of this
                // validation consumes header-free source (the shared
                // harness metadata seam); the @expected tag still reads
                // the raw bytes. The stripped header-line count rebases
                // sidecar diagnostic line pins (authored in raw-file
                // coordinates) onto the header-free compile coordinates.
                String source = ConformanceHarnessMetadata
                    .stripClassificationHeaders(raw);
                int headerCount = raw.split("\n", -1).length
                    - source.split("\n", -1).length;
                fixtures.add(new Fixture(corpusPath, source, expectedTag,
                    headerCount));
            }
        }
        return fixtures;
    }

    /** The exact {@code @expected} tag from the metadata header block. */
    private static String expectedTagOf(String source, String corpusPath) {
        String[] lines = source.split("\n", -1);
        int linesToScan = Math.min(lines.length, 40);
        for (int i = 0; i < linesToScan; i++) {
            String line = lines[i].trim();
            if (line.startsWith("// @expected:")) {
                return line.substring("// @expected:".length()).trim();
            }
        }
        fail(corpusPath + ": no @expected tag — the v1.2 gate has no "
            + "unclassified skips");
        return "";
    }

    private static String slash(Path path) {
        return path.toString().replace(java.io.File.separatorChar, '/');
    }

    // =========================================================================
    // Sidecar validation
    // =========================================================================

    private static Optional<SidecarSchemaValidator.ClassificationFailure>
            validateSidecar(Fixture fixture, String sidecarText,
                            List<SidecarSchemaValidator.CompilationModule> compilationSet,
                            Set<String> corpusIndex) {
        SidecarSchemaValidator.ValidationContext context =
            new SidecarSchemaValidator.ValidationContext(
                fixture.corpusPath(), fixture.expectedTag(),
                compilationSet, corpusIndex);
        return SidecarSchemaValidator.validate(context, sidecarText);
    }

    private static Optional<SidecarSchemaValidator.ClassificationFailure>
            validateSidecar(Fixture fixture, String sidecarText,
                            Set<String> corpusIndex) {
        return validateSidecar(fixture, sidecarText,
            List.of(new SidecarSchemaValidator.CompilationModule(
                fixture.corpusPath(), fixture.source())),
            corpusIndex);
    }

    /** No object key may be {@code error} or {@code sourceFile} anywhere. */
    private static void checkNoErrorField(CanonicalJson.Value value, String path,
                                          String fixture) {
        if (value instanceof CanonicalJson.Obj obj) {
            for (CanonicalJson.Entry entry : obj.entries()) {
                String key = entry.key();
                if (key.equals("error") || key.equals("sourceFile")) {
                    fail(fixture + ": a runtime-ok sidecar must not carry a "
                        + "\"" + key + "\" field (found at " + path + ")");
                }
                checkNoErrorField(entry.value(), path + "." + key, fixture);
            }
        } else if (value instanceof CanonicalJson.Arr arr) {
            int i = 0;
            for (CanonicalJson.Value item : arr.items()) {
                checkNoErrorField(item, path + "[" + i + "]", fixture);
                i++;
            }
        }
    }

    // =========================================================================
    // Checks per classification
    // =========================================================================

    private static void validateRuntimeOkFixture(Fixture fixture,
            Path sidecarPath, Set<String> corpusIndex) throws Exception {
        if (!Files.exists(sidecarPath)) {
            fail(fixture.corpusPath() + ": runtime-ok fixture is missing its "
                + "Structured Expectation Sidecar " + sidecarPath.getFileName());
            return;
        }
        String sidecarText = Files.readString(sidecarPath);
        Optional<SidecarSchemaValidator.ClassificationFailure> failure =
            validateSidecar(fixture, sidecarText, corpusIndex);
        if (failure.isPresent()) {
            fail(failure.get().message());
            return;
        }
        check(true, fixture.corpusPath()
            + ": runtime-ok sidecar validates clean (schema v1)");

        // Transcript authoring pins (C4): stderr always empty; stdout empty
        // except the single std/console fixture.
        CanonicalJson.Value root = CanonicalJson.parse(sidecarText);
        checkNoErrorField(root, "<root>", fixture.corpusPath());
        CanonicalJson.Obj expected = (CanonicalJson.Obj)
            ((CanonicalJson.Obj) root).entries().stream()
                .filter(e -> e.key().equals("expected"))
                .findFirst().orElseThrow().value();
        CanonicalJson.Obj transcript = (CanonicalJson.Obj)
            expected.entries().stream()
                .filter(e -> e.key().equals("transcript"))
                .findFirst().orElseThrow().value();
        String stdout = stringField(transcript, "stdout");
        String stderr = stringField(transcript, "stderr");
        if (fixture.corpusPath().equals(CONSOLE_FIXTURE)) {
            check(stdout.equals(CONSOLE_STDOUT), CONSOLE_FIXTURE
                + ": stdout must be exactly " + describe(CONSOLE_STDOUT)
                + " (the console.log(\"hello\") literal plus the canonical "
                + "trailing newline), got " + describe(stdout));
        } else {
            check(stdout.isEmpty(), fixture.corpusPath()
                + ": runtime-ok transcripts are authored empty — the fixture "
                + "performs no std/console write, got stdout "
                + describe(stdout));
        }
        check(stderr.isEmpty(), fixture.corpusPath()
            + ": stderr must be empty on runtime-ok, got " + describe(stderr));
    }

    private static void validateRuntimeErrorFixture(Fixture fixture,
            Path sidecarPath, Set<String> corpusIndex,
            Map<String, Fixture> corpusByPath) throws Exception {
        if (!Files.exists(sidecarPath)) {
            fail(fixture.corpusPath() + ": runtime-error fixture is missing "
                + "its Structured Expectation Sidecar "
                + sidecarPath.getFileName());
            return;
        }
        String sidecarText = Files.readString(sidecarPath);
        List<SidecarSchemaValidator.CompilationModule> compilationSet =
            compilationSetFor(fixture, corpusByPath);
        Optional<SidecarSchemaValidator.ClassificationFailure> failure =
            validateSidecar(fixture, sidecarText, compilationSet, corpusIndex);
        if (failure.isPresent()) {
            fail(failure.get().message());
            return;
        }
        check(true, fixture.corpusPath()
            + ": runtime-error sidecar validates clean (schema v1)");

        CanonicalJson.Value root = CanonicalJson.parse(sidecarText);
        CanonicalJson.Obj expected = (CanonicalJson.Obj)
            ((CanonicalJson.Obj) root).entries().stream()
                .filter(e -> e.key().equals("expected"))
                .findFirst().orElseThrow().value();
        CanonicalJson.Obj error = (CanonicalJson.Obj)
            expected.entries().stream()
                .filter(e -> e.key().equals("error"))
                .findFirst().orElseThrow().value();

        // Error Expectation field audits (sidecar-authoritative field set).
        String code = stringField(error, "code");
        String pinnedCode = fixture.expectedTag().startsWith("runtime-error ")
            ? fixture.expectedTag().substring("runtime-error ".length()).trim()
            : "";
        check(!pinnedCode.isEmpty() && code.equals(pinnedCode),
            fixture.corpusPath() + ": error.code must equal the fixture's "
                + "@expected code " + describe(pinnedCode) + ", got "
                + describe(code));
        String message = stringField(error, "message");
        check(!message.isEmpty(), fixture.corpusPath()
            + ": error.message must be the exact canonical template "
            + "instantiation, not an empty placeholder");
        int line = optionalIntField(error, "line");
        int column = optionalIntField(error, "column");
        boolean spanless = SidecarSchemaValidator.SANCTIONED_SPANLESS_FIXTURE
            .equals(fixture.corpusPath());
        if (spanless) {
            check(!hasErrorField(error, "sourceFile")
                    && !hasErrorField(error, "line")
                    && !hasErrorField(error, "column"),
                fixture.corpusPath() + ": the sanctioned span-less shape "
                    + "must omit the whole span group (sourceFile, line, "
                    + "column) — the retained nowMillis wrapper raises "
                    + "E8004 with no span and a sidecar never pins "
                    + "fabricated values");
        } else {
            check(line >= 1, fixture.corpusPath()
                + ": error.line must trace to the throwing site (a positive "
                + "line), got " + line);
            check(column >= 1, fixture.corpusPath()
                + ": error.column must trace to the throwing site (a positive "
                + "column), got " + column);
        }
        check(!hasErrorField(error, "frames"), fixture.corpusPath()
            + ": no runtime-error sidecar pins frames — today's runtime "
            + "error values carry no structured frames");
        check(!hasErrorField(error, "cause"), fixture.corpusPath()
            + ": no runtime-error sidecar pins cause — the cause chain is "
            + "deterministic nowhere in the authored population");
        boolean hasExpected = hasErrorField(error, "expected");
        boolean hasActual = hasErrorField(error, "actual");
        check(hasExpected == hasActual, fixture.corpusPath()
            + ": error.expected/error.actual are pinned as the _err pair "
            + "(the spec diagnostic carries both or neither), got expected="
            + hasExpected + ", actual=" + hasActual);
        if (hasExpected) {
            check(!stringField(error, "expected").isEmpty(),
                fixture.corpusPath() + ": error.expected must be the exact "
                    + "spec type name, not an empty placeholder");
            check(!stringField(error, "actual").isEmpty(),
                fixture.corpusPath() + ": error.actual must be the exact "
                    + "runtime kind, not an empty placeholder");
        }

        // Transcript framing audit (G4.6): stdout must be exactly the two
        // framing lines — no runtime-error fixture console-writes before
        // its error today (a future console-writing fixture updates this
        // pin, the CONSOLE_FIXTURE pattern); stderr must be empty.
        CanonicalJson.Obj transcript = (CanonicalJson.Obj)
            expected.entries().stream()
                .filter(e -> e.key().equals("transcript"))
                .findFirst().orElseThrow().value();
        String stdout = stringField(transcript, "stdout");
        String stderr = stringField(transcript, "stderr");
        check(stderr.isEmpty(), fixture.corpusPath()
            + ": stderr must be empty on runtime-error, got " + describe(stderr));
        String canonicalSnapshot = canonicalSnapshotOf(error, fixture.corpusPath());
        String expectedStdout = DEAL_ERROR_CODE_LINE + code + "\n"
            + DEAL_ERROR_SNAPSHOT_LINE + canonicalSnapshot + "\n";
        check(stdout.equals(expectedStdout), fixture.corpusPath()
            + ": stdout must be exactly the two G4.6 lane framing lines with "
            + "the canonical snapshot serialization (fixed key order, minimal "
            + "escaping, no whitespace between tokens), got "
            + describe(stdout));
    }

    /**
     * The canonical snapshot serialization of the error object (corpus
     * C2): fixed key order {@code code, message, sourceFile, line, column,
     * expected, actual, frames, cause}; only pinned fields emitted; no
     * whitespace between tokens; strings with minimal RFC 8259 §7 escaping
     * (only {@code "} → {@code \"}, {@code \\} → {@code \\\\}, and
     * U+0000–U+001F as the named escapes or {@code \\u00XX} with uppercase
     * hex; non-ASCII raw UTF-8, {@code /} never escaped); integers as
     * canonical decimal.
     */
    private static String canonicalSnapshotOf(CanonicalJson.Obj error,
                                              String fixturePath) {
        String[] order = { "code", "message", "sourceFile", "line", "column",
            "expected", "actual", "frames", "cause" };
        Map<String, CanonicalJson.Value> fields = fieldsOf(error);
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        boolean first = true;
        for (String key : order) {
            CanonicalJson.Value value = fields.get(key);
            if (value == null) {
                continue;
            }
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(escapeString(key)).append(':');
            if (value instanceof CanonicalJson.Str str) {
                sb.append(escapeString(str.value()));
            } else if (value instanceof CanonicalJson.Int integer) {
                sb.append(integer.value());
            } else {
                fail(fixturePath + ": error field " + key
                    + " must be a string or an integer for the canonical "
                    + "snapshot serialization");
                sb.append("null");
            }
        }
        sb.append('}');
        return sb.toString();
    }

    /** Minimal RFC 8259 §7 string escaping (C2 canonical snapshot rules). */
    private static String escapeString(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 2);
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\t' -> sb.append("\\t");
                case '\n' -> sb.append("\\n");
                case '\f' -> sb.append("\\f");
                case '\r' -> sb.append("\\r");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04X", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    private static Map<String, CanonicalJson.Value> fieldsOf(CanonicalJson.Obj obj) {
        Map<String, CanonicalJson.Value> fields = new HashMap<>();
        for (CanonicalJson.Entry entry : obj.entries()) {
            fields.put(entry.key(), entry.value());
        }
        return fields;
    }

    private static boolean hasErrorField(CanonicalJson.Obj error, String key) {
        for (CanonicalJson.Entry entry : error.entries()) {
            if (entry.key().equals(key)) {
                return true;
            }
        }
        return false;
    }

    private static String stringField(CanonicalJson.Obj obj, String key) {
        CanonicalJson.Value value = obj.entries().stream()
            .filter(e -> e.key().equals(key))
            .findFirst().orElseThrow().value();
        return ((CanonicalJson.Str) value).value();
    }

    private static int intField(CanonicalJson.Obj obj, String key) {
        CanonicalJson.Value value = obj.entries().stream()
            .filter(e -> e.key().equals(key))
            .findFirst().orElseThrow().value();
        return ((CanonicalJson.Int) value).value();
    }

    /**
     * The absent-tolerant integer accessor for the span group: returns 0
     * when the field is absent — the sanctioned span-less error shape
     * omits {@code line}/{@code column} entirely, and the audit rules
     * branch on the fixture path before reading them.
     */
    private static int optionalIntField(CanonicalJson.Obj obj, String key) {
        CanonicalJson.Value value = null;
        for (CanonicalJson.Entry entry : obj.entries()) {
            if (entry.key().equals(key)) {
                value = entry.value();
                break;
            }
        }
        if (!(value instanceof CanonicalJson.Int integer)) {
            return 0;
        }
        return integer.value();
    }

    private static String describe(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\n", "\\n")
            .replace("\r", "\\r").replace("\t", "\\t") + "\"";
    }

    // =========================================================================
    // Compilation-set resolution (transitive corpus import closure)
    // =========================================================================

    /**
     * The fixture's compilation set for the sidecar {@code sourceFile}
     * check: the fixture itself plus every transitively imported corpus
     * module, each with its corpus-relative path and source text. Relative
     * imports ({@code ./}, {@code ../}) resolve against the importing
     * module's corpus directory with the {@code .deal}/{@code .d.deal}
     * fallback; stdlib and host imports resolve outside the corpus and
     * contribute no module (the validator's {@code sourceFile} rule never
     * names them).
     */
    private static List<SidecarSchemaValidator.CompilationModule>
            compilationSetFor(Fixture fixture, Map<String, Fixture> corpusByPath) {
        List<SidecarSchemaValidator.CompilationModule> set = new ArrayList<>();
        collectCompilationModules(fixture.corpusPath(), fixture.source(),
            corpusByPath, set, new HashSet<>());
        return set;
    }

    private static void collectCompilationModules(String corpusPath,
            String source, Map<String, Fixture> corpusByPath,
            List<SidecarSchemaValidator.CompilationModule> set,
            Set<String> inProgress) {
        if (!inProgress.add(corpusPath)) {
            return; // cycle guard — the module is already emitted once
        }
        set.add(new SidecarSchemaValidator.CompilationModule(corpusPath, source));
        for (String importPath : relativeImportPaths(source)) {
            String resolved = resolveRelativeImport(corpusPath, importPath);
            if (resolved == null) {
                continue;
            }
            Fixture dependency = corpusByPath.get(resolved);
            if (dependency != null) {
                collectCompilationModules(dependency.corpusPath(),
                    dependency.source(), corpusByPath, set, inProgress);
            }
        }
    }

    /** Every relative-import module path of the source, via the real parser. */
    private static List<String> relativeImportPaths(String source) {
        List<String> paths = new ArrayList<>();
        LexResult lex = new Lexer(source, "<sidecar-compilation-set>").tokenize();
        if (lex.hasErrors()) {
            return paths;
        }
        ParseResult result = new Parser(lex.tokens(), "<sidecar-compilation-set>", lex.directiveEvents())
            .parse();
        if (result.hasErrors()) {
            return paths;
        }
        for (StatementNode statement : result.program().statements()) {
            if (statement instanceof ImportDeclaration decl) {
                if (decl.modulePath().startsWith("./")
                        || decl.modulePath().startsWith("../")) {
                    paths.add(decl.modulePath());
                }
            }
        }
        return paths;
    }

    /**
     * Resolves a relative import against the importing module's corpus
     * directory, mirroring {@code ConformanceTest.resolveCompanionPath}:
     * the raw path, then the {@code .deal} and {@code .d.deal} extensions.
     * Returns the corpus-relative slash path, or {@code null} when the
     * resolution leaves the corpus or no file exists.
     */
    private static String resolveRelativeImport(String importer, String importPath) {
        Path parent = Path.of(importer).getParent();
        Path base = parent == null ? CORPUS_ROOT : CORPUS_ROOT.resolve(parent);
        for (Path candidate : new Path[] {
                base.resolve(importPath).normalize(),
                base.resolve(importPath + ".deal").normalize(),
                base.resolve(importPath + ".d.deal").normalize() }) {
            if (!candidate.startsWith(CORPUS_ROOT)) {
                continue;
            }
            if (Files.exists(candidate)) {
                return slash(CORPUS_ROOT.relativize(candidate));
            }
        }
        return null;
    }

    private static void validateCompileSidecarFixture(Fixture fixture,
            Path sidecarPath, Set<String> corpusIndex) throws Exception {
        if (!Files.exists(sidecarPath)) {
            fail(fixture.corpusPath() + ": the Diagnostics-bullet fixture is "
                + "missing its Compile Expectation Sidecar "
                + sidecarPath.getFileName());
            return;
        }
        String sidecarText = Files.readString(sidecarPath);
        Optional<SidecarSchemaValidator.ClassificationFailure> failure =
            validateSidecar(fixture, sidecarText, corpusIndex);
        if (failure.isPresent()) {
            fail(failure.get().message());
            return;
        }
        check(true, fixture.corpusPath()
            + ": Compile Expectation Sidecar validates clean (schema v1)");

        // Compile Diagnostic comparison: the fixture must emit exactly one
        // error diagnostic equal to the pin field-exact (code, line, column,
        // message — all four are pinned on the Diagnostics-bullet fixtures).
        CanonicalJson.Value root = CanonicalJson.parse(sidecarText);
        CanonicalJson.Obj diagnostic = (CanonicalJson.Obj)
            ((CanonicalJson.Obj) root).entries().stream()
                .filter(e -> e.key().equals("diagnostic"))
                .findFirst().orElseThrow().value();
        String pinCode = stringField(diagnostic, "code");
        int pinLine = intField(diagnostic, "line");
        int pinColumn = intField(diagnostic, "column");
        String pinMessage = stringField(diagnostic, "message");

        List<CompilerDiagnostic> errors = frontendErrors(fixture);
        if (errors.size() != 1) {
            fail(fixture.corpusPath() + ": COMPILE_DIAGNOSTIC_MISMATCH — the "
                + "fixture must emit exactly one error diagnostic, got "
                + errors.size());
            return;
        }
        CompilerDiagnostic d = errors.get(0);
        compareDiagnosticField(fixture.corpusPath(), "code",
            pinCode, d.code());
        // The pinned line was authored in raw corpus-file coordinates;
        // the stripped compile shifts lines up by the header count.
        compareDiagnosticField(fixture.corpusPath(), "line",
            String.valueOf(pinLine),
            String.valueOf(d.line() + fixture.headerLinesStripped()));
        compareDiagnosticField(fixture.corpusPath(), "column",
            String.valueOf(pinColumn), String.valueOf(d.column()));
        compareDiagnosticField(fixture.corpusPath(), "message",
            pinMessage, d.message());
    }

    private static void compareDiagnosticField(String fixture, String field,
            String expected, String actual) {
        check(expected.equals(actual), fixture
            + ": COMPILE_DIAGNOSTIC_MISMATCH — diagnostic." + field
            + " must be " + describe(expected) + ", got " + describe(actual));
    }

    // =========================================================================
    // Real frontend compilation (the Compile Diagnostic comparison source)
    // =========================================================================

    /**
     * Runs the backend-neutral frontend pipeline (lexer → parser → module
     * shape gate → name resolution → type checker) and returns every error
     * diagnostic, mirroring {@code ConformanceTest.compileAndGetDiagnostics}
     * with a resolver that rejects every module (the Diagnostics-bullet
     * fixtures import nothing).
     */
    @SuppressWarnings("deprecation")
    private static List<CompilerDiagnostic> frontendErrors(Fixture fixture) {
        String filename = fixture.corpusPath();
        String source = fixture.source();
        List<CompilerDiagnostic> errors = new ArrayList<>();

        LexResult lex = new Lexer(source, filename).tokenize();
        for (CompilerDiagnostic d : lex.diagnostics()) {
            if ("error".equals(d.severity())) { errors.add(d); }
        }
        if (lex.hasErrors()) { return errors; }

        Parser parser = new Parser(lex.tokens(), filename, lex.directiveEvents());
        ParseResult parseResult = parser.parse();
        for (CompilerDiagnostic d : parseResult.diagnostics()) {
            if ("error".equals(d.severity())) { errors.add(d); }
        }
        if (parseResult.hasErrors()) { return errors; }

        for (CompilerDiagnostic d : ModuleShapeValidator.validate(
                parseResult.program(), filename, false)) {
            if ("error".equals(d.severity())) { errors.add(d); }
        }
        if (!errors.isEmpty()) { return errors; }

        ModuleResolver resolver = new ModuleResolver() {
            @Override
            public java.util.Map<String, deal.types.Type> resolveModule(
                    String modulePath, String importingModule,
                    Set<String> modulesInProgress) throws ModuleNotFoundException {
                throw new ModuleNotFoundException(
                    "Module not found: " + modulePath);
            }

            @Override
            public Symbol.ClassSymbol resolveClassSymbol(String className,
                    String modulePath, String importingModule)
                    throws ModuleNotFoundException {
                return null;
            }
        };
        NameResolver nr = new NameResolver(filename, resolver);
        SymbolTable symTable;
        try {
            symTable = nr.resolve(parseResult.program());
        } catch (Exception e) {
            errors.add(CompilerDiagnostic.synthetic("E9999", "error",
                e.getMessage(), filename,
                "missing anchor: fixture source '" + filename + "'"));
            return errors;
        }
        for (CompilerDiagnostic d : nr.diagnostics()) {
            if ("error".equals(d.severity())) { errors.add(d); }
        }
        CheckResult result = TypeChecker.check(filename, symTable, nr,
            parseResult.program());
        for (CompilerDiagnostic d : result.diagnostics()) {
            if ("error".equals(d.severity())) { errors.add(d); }
        }
        return errors;
    }

    // =========================================================================
    // Main
    // =========================================================================

    public static void main(String[] args) throws Exception {
        System.out.println("=== Sidecar Corpus Validation Test "
            + "(ISSUE-0349 + ISSUE-0350) ===\n");

        List<Fixture> fixtures = discover();

        // Corpus module index: every discovered corpus-relative module path
        // (the sourceFile check resolves against it).
        Set<String> corpusIndex = new LinkedHashSet<>();
        Map<String, Fixture> corpusByPath = new HashMap<>();
        for (Fixture fixture : fixtures) {
            corpusIndex.add(fixture.corpusPath());
            corpusByPath.put(fixture.corpusPath(), fixture);
        }

        // The exact sidecar paths the corpus may carry: one per runtime-ok
        // fixture, one per runtime-error fixture, one per known-fail
        // runtime fixture (the differential gate's presence rule,
        // ISSUE-0353 — none is tracked today: the gate-closure
        // promotion dropped the restored int-add-overflow marker and
        // the ISSUE-0502 retained candidate bytes-boundary-order.deal
        // was promoted after ISSUE-0158 lifted the E3019 gate, so
        // every runtime-classified fixture is a real runtime-ok or
        // runtime-error fixture), plus the Diagnostics-bullet
        // Compile Expectation Sidecars (the two ISSUE-0501 gap
        // fixtures extend the closed set to four).
        Set<String> allowedSidecars = new HashSet<>();
        int runtimeOk = 0;
        int runtimeError = 0;
        for (Fixture fixture : fixtures) {
            boolean isRuntimeOk = "runtime-ok".equals(fixture.expectedTag());
            boolean isRuntimeError =
                fixture.expectedTag().startsWith("runtime-error ");
            boolean isDiagnosticsPin = fixture.corpusPath().equals(DIAG_ASSIGNMENT)
                || fixture.corpusPath().equals(DIAG_RETURN)
                || fixture.corpusPath().equals(DIAG_EXACT_SPAN)
                || fixture.corpusPath().equals(DIAG_ASSIGNMENT_LOCATION);
            Path sidecarPath = sidecarFor(fixture.corpusPath());

            if (isRuntimeOk) {
                runtimeOk++;
                allowedSidecars.add(sidecarPath.toString());
                validateRuntimeOkFixture(fixture, sidecarPath, corpusIndex);
            } else if (isRuntimeError) {
                runtimeError++;
                allowedSidecars.add(sidecarPath.toString());
                validateRuntimeErrorFixture(fixture, sidecarPath, corpusIndex,
                    corpusByPath);
            } else if (isDiagnosticsPin) {
                allowedSidecars.add(sidecarPath.toString());
                validateCompileSidecarFixture(fixture, sidecarPath, corpusIndex);
            } else if (fixture.expectedTag().startsWith("known-fail ")
                    && fixture.expectedTag().substring(
                        "known-fail ".length()).startsWith("runtime")) {
                // A known-fail whose underlying mode is a runtime mode
                // A runtime-mode known-fail fixture (none is tracked
                // today — the ISSUE-0502 retained candidate
                // bytes-boundary-order.deal passed both lanes after
                // ISSUE-0158 lifted the E3019 gate and was promoted)
                // would carry its three-backend sidecar: the
                // differential gate's presence rule (ISSUE-0353)
                // requires a valid sidecar for every runtime-classified
                // fixture — including a known-fail whose underlying
                // mode is a runtime mode — and the gate has no
                // silent default.
                allowedSidecars.add(sidecarPath.toString());
                check(Files.exists(sidecarPath), fixture.corpusPath()
                    + ": the known-fail runtime fixture must carry its "
                    + "three-backend sidecar (the differential gate's "
                    + "presence rule), missing "
                    + sidecarPath.getFileName());
            } else {
                check(!Files.exists(sidecarPath), fixture.corpusPath()
                    + ": no sidecar is authored for this classification "
                    + "(" + fixture.expectedTag() + " — compile/"
                    + "companion/frontend fixtures carry none except the "
                    + "Diagnostics-bullet fixtures; a known-fail compile "
                    + "fixture carries none), found "
                    + sidecarPath.getFileName());
            }
        }

        // Completeness: the runtime-ok population and the runtime-error
        // population are each their task's whole set.
        check(runtimeOk == RUNTIME_OK_COUNT, "the corpus must carry exactly "
            + RUNTIME_OK_COUNT + " runtime-ok fixtures with sidecars, found "
            + runtimeOk);
        check(runtimeError == RUNTIME_ERROR_COUNT, "the corpus must carry "
            + "exactly " + RUNTIME_ERROR_COUNT + " runtime-error fixtures "
            + "with sidecars, found " + runtimeError);


        // The tracked backend-runtime known-fail population is
        // empty (ISSUE-0502, the gap-suite runtime population
        // landing): the issue's retained-known-fail candidate
        // backend-runtime/bytes/bytes-boundary-order.deal passes both
        // real lanes in this tree — ISSUE-0158 lifted the E3019
        // bytes-equality gate (bytes EQ/NEQ admitted by reference
        // identity), so the criterion's both-lane probe-fail premise
        // no longer holds and the zero-skip promotion gate forces
        // the stale marker off. The fixture lands promoted as
        // runtime-ok with its empty-transcript three-backend sidecar
        // (the differential gate's presence rule, ISSUE-0353). The
        // ISSUE-0380 promotion of the restored int-add-overflow
        // fixture and the ISSUE-0477 promotion of the FFI-manifest
        // frontend pin leave the backend-runtime known-fail
        // population empty.
        Set<String> knownFail = new TreeSet<>();
        for (Fixture fixture : fixtures) {
            if (fixture.corpusPath().startsWith("backend-runtime/")
                    && fixture.expectedTag().startsWith("known-fail ")) {
                knownFail.add(fixture.corpusPath());
            }
        }
        Set<String> expectedKnownFail = new TreeSet<>();
        check(knownFail.equals(expectedKnownFail),

            "the tracked backend-runtime known-fail population must be "
                + "empty post-unit, got " + knownFail);


        // No stray sidecar anywhere in the corpus.
        try (Stream<Path> stream = Files.walk(CORPUS_ROOT)) {
            for (Path sidecar : stream
                    .filter(p -> p.toString().endsWith(".expect.json"))
                    .sorted()
                    .toList()) {
                check(allowedSidecars.contains(sidecar.toString()),
                    "stray sidecar file " + CORPUS_ROOT.relativize(sidecar)
                        + " — a sidecar may exist only next to a runtime-ok "
                        + "fixture, a runtime-error fixture, a known-fail "
                        + "runtime fixture, or the Diagnostics-bullet "
                        + "fixtures");
            }
        }

        System.out.println("\nPassed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    /** The sibling sidecar path for a corpus-relative fixture path. */
    private static Path sidecarFor(String corpusPath) {
        String stem = corpusPath.endsWith(".deal")
            ? corpusPath.substring(0, corpusPath.length() - ".deal".length())
            : corpusPath;
        return CORPUS_ROOT.resolve(stem + ".expect.json");
    }
}
