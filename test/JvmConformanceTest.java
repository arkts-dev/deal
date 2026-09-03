package deal.test;

import deal.ast.*;
import deal.checker.*;
import deal.codegen.Backend;
import deal.diagnostics.CompilerDiagnostic;
import deal.codegen.jvm.JvmBackend;
import deal.lexer.*;
import deal.module.CompilationOrchestrator;
import deal.module.ExportExtractor;
import deal.semantic.CapabilityRegistry;
import deal.semantic.CompilerInvocation;
import deal.semantic.CompilerProfileProvider;
import deal.semantic.ir.ReleaseState;
import deal.semantic.ir.SemanticProfile;
import deal.project.ProjectContext;
import deal.project.ProjectLocator;
import deal.module.StdlibModuleResolver;
import deal.parser.*;
import deal.types.Type;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * JVM conformance promotion gate (ISSUE-0102), running the activated
 * DEAL v1.2 signed-int32 route (ISSUE-0378 D1).
 *
 * <p>Runs every existing backend-runtime conformance test
 * ({@code test/conformance/backend-runtime/}) against the JVM backend
 * with a deterministic applicability policy and a real whole-project
 * pipeline: module discovery → parsing → signature extraction →
 * dependency ordering → name resolution → type checking → per-module
 * {@link JvmBackend} codegen (via {@link CompilationOrchestrator} with
 * {@link Backend#JVM}) → {@code javac} over every emitted artifact →
 * {@code java} execution of the emitted classes. A bypassed
 * parser/checker/module-discovery yields no orchestrator success; a
 * bypassed codegen leaves no {@code .java} artifact (asserted before
 * javac); a bypassed JVM execution produces no output and no exit code
 * (asserted against the captured subprocess output).
 *
 * <h2>The lane-wide activated invocation (ISSUE-0378 D1)</h2>
 *
 * <p>Every on-disk backend-runtime fixture compiles through the single
 * lane-wide activated invocation {@link #LANE_INVOCATION} — the
 * explicit
 * {@code CompilerProfileProvider.resolve(ReleaseState.V1_2_ACTIVE,
 * CapabilityRegistry.releaseRegistry())} invocation (PUBLIC_BUILD +
 * {@code DEAL_V1_2_INT32}, release state {@code V1_2_ACTIVE}) —
 * passed through the full {@link CompilationOrchestrator} constructor.
 * The lane carries zero per-fixture catalog-seam call sites and
 * zero legacy-authority labeling: the profile-authority accounting is
 * pinned at 0 legacy-authority fixtures, and the per-fixture catalog
 * seam stays where it belongs — the untouched legacy harness
 * {@code deal.test.BackendConformanceTest} (this lane only validates
 * the catalog at startup; it never routes a fixture through it).
 *
 * <h2>Sanctioned pinned staged state (ISSUE-0378 D2/D3)</h2>
 *
 * <p>On the unmerged tree the lane fails in exactly the pinned ways and
 * nothing else — the activation-liveness proof, never papered:
 * <ul>
 *   <li>the unflipped stdlib-edge epoch-millisecond fixture raises
 *       E8004 at the declared {@code int} boundary against its
 *       {@code runtime-ok} expectation — one applicable failure;
 *       the expectation flip is ISSUE-0372's single edit, and this
 *       lane never names or skips the fixture;</li>
 *   <li>the restored corpus known-fail fixture
 *       {@code backend-runtime/arithmetic/int-add-overflow.deal} passes
 *       its {@code runtime-error E8004} probe under the activated
 *       {@code intAdd} and fires the stale-known-fail gate with the
 *       promotion instruction (set {@code @expected: runtime-error
 *       E8004}, drop the {@code @issue} tag) — that promotion is
 *       jvm-v12-int32-bytes D6's later sanctioned edit
 *       (ISSUE-0381).</li>
 * </ul>
 * No skip entry, legacy fallback, or lane-side papering hides either
 * failure; both are real gate failures and the runner exits 1. The
 * anti-hollow evidence owner is {@code test/JvmLaneStatePinTest.java}:
 * it runs this lane and the real LuaJIT lane as subprocesses and
 * asserts the captured failure sets field-exactly on every gate run
 * ({@code run_tests.sh} substitutes it for the raw lane launches).</p>
 *
 * <h2>Classification policy (deterministic, documented)</h2>
 *
 * <ol>
 *   <li><b>Frontend-classified files</b> ({@code compile-ok} /
 *       {@code compile-error CODE} anywhere under the corpus): run the
 *       shared frontend pipeline (lexer → parser → name resolver → type
 *       checker) and must pass 100% — they are backend-neutral and never
 *       counted in the JVM backend-runtime denominator (the same
 *       classification the LuaJIT harness applies).</li>
 *   <li><b>Companions</b> ({@code @expected: companion}): classified
 *       support modules. They are compiled as part of the transitive
 *       module graph of every test that imports them (never counted
 *       separately); their standalone compilation stays enforced by the
 *       shared {@code deal.test.ConformanceTest} gate.</li>
 *   <li><b>Known-fail</b> ({@code @expected: known-fail MODE}): the
 *       intentionally unsupported v1.2 cases tracked by their
 *       {@code @issue} (ISSUE-0111 signed int32 / bytes). The runner
 *       executes the underlying mode through the real JVM pipeline and
 *       records a non-fatal tracked KNOWN-FAIL while the case still
 *       fails; when it starts passing, the gate FAILS with a promotion
 *       instruction (drop the marker) — promotion is forced. The
 *       restored {@code arithmetic/int-add-overflow.deal} now passes
 *       its probe under the activated invocation, so its
 *       stale-known-fail gate fires with the pinned promotion
 *       instruction — the sanctioned staged failure; the promotion
 *       itself is ISSUE-0381's later sanctioned edit.</li>
 *   <li><b>Backend-runtime tests</b> ({@code runtime-ok} /
 *       {@code runtime-error CODE}): JVM-applicable and must pass
 *       through the whole pipeline UNLESS the explicit skip registry
 *       (below) classifies them. Every skip carries a documented reason
 *       and a gap id; the registry is validated against the
 *       on-disk corpus (a stale entry naming a missing file fails the
 *       run, and there is no fallback skip branch — zero unclassified
 *       skips by construction).</li>
 * </ol>
 *
 * <h2>The skip registry (ISSUE-0102)</h2>
 *
 * <ul>
 *   <li><b>JVM-GAP-STDJSON</b> (13 entries) — std/json JVM boundary:
 *       {@code JvmBackend} E6000 at {@code import std/json}
 *       (json.parse/stringify require table values the JVM slice does
 *       not support).</li>
 *   <li><b>JVM-GAP-JSONABLE-RESIDUAL</b> (4 entries) — residual
 *       @jsonable JVM defects: nested-array {@code fromJson} javac
 *       collision; table-field nested arrays E8001; the fromJson
 *       top-level input gate (ISSUE-0101 promotion).</li>
 *   <li><b>JVM-GAP-HOST-ABI-SHAPES</b> (16 entries) — JVM host ABI
 *       unsupported declared shapes: host class exports,
 *       array/function-typed parameters and returns (E6000), the
 *       Lua pre-wrapped export form, and the host async export used as
 *       a function value (relabeled from JVM-GAP-XMOD-FNVALUE with the
 *       shared-carrier lane).</li>
 *   <li><b>JVM-GAP-XMOD-FNVALUE</b> — RETIRED with the shared runtime
 *       value surface (ISSUE-0301): the four cross-module function-value
 *       fixtures pass the real pipeline on the shared $DealRt wrapper
 *       carriers, and the stale-skip gate forced the registry entries
 *       out.</li>
 *   <li><b>JVM-GAP-XMOD-ARRAY</b> — RETIRED with the shared runtime
 *       value surface (ISSUE-0301): await-returning-array-indexed
 *       passes the real pipeline on the shared $DealRt array carriers,
 *       and the stale-skip gate forced the registry entry out.</li>
 *   <li><b>JVM-GAP-ASYNC-FNEXPR</b> — RETIRED (ISSUE-0304): async
 *       function expressions and block-level async function
 *       declarations emit through the sync closure machinery with
 *       async descriptors, so async-fn-expr.deal and
 *       async-await-statement.deal pass the real pipeline and the
 *       stale-skip gate forced the registry entries out.</li>
 *   <li><b>JVM-GAP-DESCRIPTORS</b> — RETIRED with the canonical matcher
 *       realization (ISSUE-0301): the emitted $check function row raises
 *       E8010 on a carried-descriptor delta, so
 *       canonical-sig-mismatch-e8010 passes and the stale-skip gate
 *       forced the entry out.</li>
 *   <li><b>JVM-GAP-DEFAULTS-PLANS</b> (2 entries) — the v1.2
 *       default-plan lane (ISSUE-0340, LuaJIT-owned): imported
 *       non-literal defaults evaluate in the declaring module's scope
 *       under LuaJIT (E6000 on the JVM imported-class slice) and the
 *       phase-order Error-catch probe is outside the JVM slice.</li>
 *   <li><b>JVM-GAP-INT32</b> — the signed-int32 runtime gate: the
 *       retained v1.2 JVM route (the ISSUE-0375 carrier switch plus the
 *       profile-selected helper bodies) now raises E8004 for every
 *       arithmetic/conversion/negation/absInt case, so the skip entries
 *       were removed with their promotions (the A5 seam promotions and
 *       the ISSUE-0397 I6 int32-math-abs-min absInt long-magnitude
 *       arm); the gap keeps no entries.</li>
 *   <li><b>JVM-GAP-BYTES</b> (1 entry) — the bytes runtime lane
 *       landed with ISSUE-0158 (zero-fill allocation, E8012/E8013,
 *       single-evaluation writes, class fields all pass on JVM); the
 *       one remaining fixture pins the recursive bytes-bearing
 *       array/nullable/function wrapper closure, which stays E6000
 *       until ISSUE-0160.</li>
 * </ul>
 *
 * <h2>Gates</h2>
 * <ul>
 *   <li>frontend-classified files: 100% pass (zero failed);</li>
 *   <li>backend-runtime: zero applicable failures AND at least 80% of
 *       the on-disk backend-runtime tests (the per-run
 *       {@code runtimeDenominator()} count — 301 with the restored
 *       known-fail fixture) pass through the frontend →
 *       CompilationOrchestrator → JVM codegen → javac → JVM
 *       pipeline — on the unmerged tree the sanctioned pinned staged
 *       state (one applicable failure plus one stale known-fail
 *       marker) fails exactly these two gates and nothing else, and
 *       {@code test/JvmLaneStatePinTest.java} asserts that captured
 *       set field-exactly on every gate run;</li>
 *   <li>zero unclassified skips (by construction — the classifier has
 *       no fallback skip branch, and the registry is validated);</li>
 *   <li>zero stale skips and zero stale known-fail markers (promotion
 *       is forced);</li>
 *   <li>zero probe runner exceptions (a probe crash is never silent
 *       evidence);</li>
 *   <li>the classified runtime total equals the on-disk
 *       denominator;</li>
 *   <li>the runner exits non-zero when any gate fails.</li>
 * </ul>
 */
public class JvmConformanceTest {

    private static final int DEFAULT_JOBS = 1;

    // =========================================================================
    // The lane-wide activated invocation (ISSUE-0378 D1)
    // =========================================================================

    /**
     * The single lane-wide activated invocation: every on-disk
     * backend-runtime fixture compiles through this exact invocation
     * via the full {@link CompilationOrchestrator} constructor. The
     * public release-state derivation (PUBLIC_BUILD +
     * {@code DEAL_V1_2_INT32}, release state {@code V1_2_ACTIVE}) is
     * the activated backend's sanctioned harness surface until the
     * release-owned public cutover; no per-fixture catalog seam and no
     * legacy-authority routing exist in this lane (the untouched
     * {@code deal.test.BackendConformanceTest} owns that seam).
     */
    private static final CompilerInvocation LANE_INVOCATION =
        CompilerProfileProvider.resolve(ReleaseState.V1_2_ACTIVE,
            CapabilityRegistry.releaseRegistry());

    /** The lane-wide activated invocation, exposed for the ISSUE-0378
     * pin test ({@code JvmLaneStatePinTest}) to assert field-exactly. */
    static CompilerInvocation laneInvocation() {
        return LANE_INVOCATION;
    }

    // =========================================================================
    // Applicability policy: the explicit skip registry
    // =========================================================================

    /** One skip-registry entry: corpus-relative path, documented reason,
     * and gap id. */
    private record SkipEntry(String path, String reason, String gapId) {}

    /** The complete skip registry. Every entry must name an on-disk
     * runtime-classified corpus test; the runner validates the registry
     * against the corpus so a stale entry fails the run. */
    private static final Map<String, SkipEntry> SKIPS = new LinkedHashMap<>();
    static {
        // ---- JVM-GAP-STDJSON: the std/json JVM boundary ----
        skip("backend-runtime/class-runtime-errors/dynamic-bad-class-array-element-e8001.deal",
            "json.parse builds the dynamic array value.", "JVM-GAP-STDJSON");
        skip("backend-runtime/class-runtime-errors/dynamic-bad-class-param-e8001.deal",
            "json.parse builds the dynamic class value.", "JVM-GAP-STDJSON");
        skip("backend-runtime/class-runtime-errors/dynamic-bad-class-return-e8001.deal",
            "json.parse builds the dynamic class value.", "JVM-GAP-STDJSON");
        skip("backend-runtime/class-runtime-errors/dynamic-bad-imported-class-param-e8001.deal",
            "json.parse builds the dynamic imported-class value.",
            "JVM-GAP-STDJSON");
        skip("backend-runtime/class-runtime-errors/dynamic-bad-nullable-class-e8001.deal",
            "json.parse builds the dynamic nullable-class value.",
            "JVM-GAP-STDJSON");
        skip("backend-runtime/runtime-errors/json-stringify-function-e8001.deal",
            "json.stringify of a function-holding table.",
            "JVM-GAP-STDJSON");
        skip("backend-runtime/source-location/json-error-source.deal",
            "json.stringify of a function-holding table (E8001) requires "
                + "the std/json JVM boundary.", "JVM-GAP-STDJSON");
        skip("backend-runtime/source-location-precision/class-param-error-source.deal",
            "json.parse builds the dynamic class value.", "JVM-GAP-STDJSON");
        skip("backend-runtime/type-system/dynamic-array-element-e8003.deal",
            "json.parse of a mixed array.", "JVM-GAP-STDJSON");
        skip("backend-runtime/stdlib/json/int32-boundary-parse.deal",
            "json.parse int32 number mapping (2147483647/2147483648/"
                + "-2147483648/-2147483649/-0) and stringify output.",
            "JVM-GAP-STDJSON");
        skip("backend-runtime/stdlib/json/json-stringify-roundtrip.deal",
            "json.parse/stringify int-number document round-trips.",
            "JVM-GAP-STDJSON");
        skip("backend-runtime/stdlib/json/json-stringify-bytes-error.deal",
            "json.stringify of a bytes-holding table (E8001).",
            "JVM-GAP-STDJSON");
        skip("backend-runtime/stdlib/table/keys-nonstring-exclusion.deal",
            "json.parse builds the integer-keyed array table whose "
                + "non-string keys the fixture pins excluded from "
                + "std/table.keys.", "JVM-GAP-STDJSON");

        // ---- JVM-GAP-DESCRIPTORS: retired with the canonical matcher
        // realization (ISSUE-0301) ----
        // The emitted $check function row (jvm-v12-runtime-value-surface
        // D5) raises E8010 "function signature mismatch: expected {D},
        // got {actual}" on any carried-descriptor delta, so
        // canonical-sig-mismatch-e8010 passes the real pipeline and the
        // stale-skip gate forced the entry's removal.

        // ---- JVM-GAP-INT32: the signed-int32 runtime gate ----
        // The v1.2 corpus pins int as [-2147483648, 2147483647] with E8004
        // on every out-of-range arithmetic result and conversion. The
        // profile-selected JVM int32 helper bodies landed (ISSUE-0394),
        // so the activated lane-wide invocation raises E8004 for the
        // arithmetic/conversion/negation cases — those entries became
        // stale under the A5 seam and were removed with the promotions.
        // The corpus known-fail fixture arithmetic/int-add-overflow.deal
        // is restored (ISSUE-0378 D3): its runtime-error E8004 probe now
        // passes under the activated intAdd, so the stale-known-fail
        // gate fires with the promotion instruction (set '@expected:
        // runtime-error E8004', drop the '@issue' tag) — the
        // sanctioned pinned staged failure, never a skip entry. The
        // final entry — the std/math.absInt(-2147483648) residual —
        // was resolved by ISSUE-0397 I6: the emitted int32 absInt arm
        // now promotes the int-carrier operand to long before
        // java.lang.Math.abs, so the MIN_VALUE magnitude (2147483648)
        // reaches the int32 checkInt gate and raises E8004
        // (int32-math-abs-min pins the promoted case on both retained
        // routes), and the stale skip was removed.
        // ---- JVM-GAP-BYTES: the bytes runtime lane (ISSUE-0158) ----
        // The direct bytes lane landed with ISSUE-0158: bytes(n)
        // allocation (zero-filled byte[]), b.length, unsigned reads,
        // E8012 index bounds, E8013 value range, single-evaluation
        // writes, reference aliasing, and bytes-typed class fields all
        // pass the real pipeline — their skip entries became stale and
        // the stale-skip gate forced them out with the promotion.
        // The one remaining fixture pins the RECURSIVE bytes-bearing
        // container/function closure (bytes[] / ?bytes /
        // (bytes)->bytes array+function carriers), which the shared
        // wrapper machinery rejects with E6000 until ISSUE-0160 lands.
        skip("backend-runtime/bytes/bytes-descriptor-boundary.deal",
            "canonical [bytes]/?(bytes)/(bytes)->bytes descriptors "
                + "require the recursive bytes-bearing array/nullable/"
                + "function wrapper carriers (ISSUE-0160); JvmBackend "
                + "raises E6000 at those sites.", "JVM-GAP-BYTES");

        // ---- JVM-GAP-JSONABLE-RESIDUAL: residual @jsonable JVM defects ----
        // The two error-typed member-access/NEQ entries retired with
        // the orchestrator's cross-module checked-fact resolution
        // (ISSUE-0326): their skip entries were stale and the gate
        // forced the removal.
        skip("backend-runtime/jsonable/jsonable-fromjson-top-level-scalar.deal",
            "requires @jsonable code generation and the std/json boundary.",
            "JVM-GAP-JSONABLE-RESIDUAL");
        skip("backend-runtime/jsonable/jsonable-optional-nullable-nested-class.deal",
            "E6000: NEQ over error/null/int and member access as a value.",
            "JVM-GAP-JSONABLE-RESIDUAL");
        skip("backend-runtime/jsonable/nested-array-roundtrip.deal",
            "emitted $fromJsonValue redeclares locals (l0/a0/i0/e0); "
                + "javac rejects the artifact.", "JVM-GAP-JSONABLE-RESIDUAL");
        skip("backend-runtime/jsonable/jsonable-table-field-nested-arrays.deal",
            "runtime E8001 \"value is not JSON-shaped\": toJson of a "
                + "table field holding nested arrays.",
            "JVM-GAP-JSONABLE-RESIDUAL");
        // jsonable-tojson-rejects-cyclic-table.deal is deliberately NOT
        // registered: it passes on JVM — the ISSUE-0168 JVM slice's own
        // cycle detection raises E8001 — so a skip entry would be stale
        // and fail the stale-skip gate deterministically. Verified JVM
        // promotion, recorded per ISSUE-0187.

        // ---- JVM-GAP-HOST-ABI-SHAPES: unsupported declared host shapes ----
        skip("backend-runtime/host-abi/host-array-return-ok.deal",
            "E6000: declared array-typed host return (the JVM host ABI "
                + "slice supports primitive/string/nullable returns "
                + "only).", "JVM-GAP-HOST-ABI-SHAPES");
        skip("backend-runtime/host-abi/host-rest-ok.deal",
            "E6000: declared array-typed host parameter (v1.2 fixed-array "
                + "host form).", "JVM-GAP-HOST-ABI-SHAPES");
        skip("backend-runtime/host-abi/host-rest-bad.deal",
            "E6000: declared array-typed host parameter (v1.2 fixed-array "
                + "host form).", "JVM-GAP-HOST-ABI-SHAPES");
        skip("backend-runtime/host-abi/host-boundary-apply-function.deal",
            "E6000: declared function-typed host parameter.",
            "JVM-GAP-HOST-ABI-SHAPES");
        skip("backend-runtime/host-abi/host-nullable-function-param.deal",
            "E6000: declared function | null host parameter.",
            "JVM-GAP-HOST-ABI-SHAPES");
        skip("backend-runtime/host-abi/host-nullable-function-param-bad.deal",
            "E6000: declared function | null host parameter.",
            "JVM-GAP-HOST-ABI-SHAPES");
        skip("backend-runtime/host-abi/host-nullable-function-return-ok.deal",
            "E6000: declared function | null host return.",
            "JVM-GAP-HOST-ABI-SHAPES");
        skip("backend-runtime/host-abi/host-nullable-function-return-bad.deal",
            "E6000: declared function | null host return.",
            "JVM-GAP-HOST-ABI-SHAPES");
        skip("backend-runtime/host-abi/host-class-export.deal",
            "host class exports unsupported on JVM: E3004 \"Unknown "
                + "class 'ServerConfig'\" (the JVM externals path "
                + "synthesizes no host class symbols and the backend "
                + "rejects host class exports).", "JVM-GAP-HOST-ABI-SHAPES");
        skip("backend-runtime/host-abi/host-export-presence.deal",
            "host class exports unsupported on JVM: E3004 \"Unknown "
                + "class 'Config'\" (same root cause).",
            "JVM-GAP-HOST-ABI-SHAPES");
        skip("backend-runtime/host-abi/host-class-default-isolation.deal",
            "host class exports unsupported on JVM: E3004 \"Unknown "
                + "class 'ServerConfig'\" (same root cause as "
                + "host-class-export).", "JVM-GAP-HOST-ABI-SHAPES");
        skip("backend-runtime/host-abi/host-class-extra-field.deal",
            "host class exports unsupported on JVM: E3004 \"Unknown "
                + "class 'Config'\" (same root cause as "
                + "host-export-presence).", "JVM-GAP-HOST-ABI-SHAPES");
        skip("backend-runtime/host-abi/host-prewrapped-ok.deal",
            "the Lua pre-wrapped export form (sig-annotated tables) is a "
                + "LuaJIT host-loader mechanism with no JVM analog (no "
                + "Java host implementation can express it).",
            "JVM-GAP-HOST-ABI-SHAPES");
        skip("backend-runtime/host-abi/host-prewrapped-bad.deal",
            "the Lua pre-wrapped export form; no JVM analog.",
            "JVM-GAP-HOST-ABI-SHAPES");
        skip("backend-runtime/defaults/plan-host-discriminator.deal",
            "host class exports unsupported on JVM: E3004 \"Unknown "
                + "class 'ServerConfig'\" (the JVM externals path "
                + "synthesizes no host class symbols and the backend "
                + "rejects host class exports — same root cause as "
                + "host-class-export).", "JVM-GAP-HOST-ABI-SHAPES");

        // ---- JVM-GAP-DEFAULTS-PLANS: the v1.2 default-plan lane
        // (ISSUE-0340, LuaJIT-owned) ----
        // The defaults corpus pins per-attempt default plans: imported
        // non-literal defaults run in the declaring module's scope under
        // LuaJIT (the provider's module-local default function executes
        // through the imported plan), and the phase-order fixture probes
        // provided-value evaluation before defaults with a caught E8002
        // (an Error | null local with a catch-block assignment). The JVM
        // backend evaluates defaults inline per call and rejects
        // non-literal defaults on imported classes with E6000
        // (JvmBackend's declared scope), and its slice rejects the
        // Error-typed nullable local plus the catch-assignment pattern
        // of the phase-order probe. Both fixtures stay LuaJIT/JS-lane
        // pins until JVM default plans land (ISSUE-0277).
        skip("backend-runtime/defaults/plan-imported-provider-scope.deal",
            "E6000: non-literal default expression on an imported class "
                + "(JVM defaults evaluate in the declaring module's "
                + "scope under LuaJIT; the JVM imported-class slice "
                + "rejects them).", "JVM-GAP-DEFAULTS-PLANS");
        skip("backend-runtime/defaults/plan-phase-order-provided-before-defaults.deal",
            "E6000: the Error | null catch-probe local and the "
                + "catch-block assignment are outside the JVM slice "
                + "(class-typed local values are local-module-class "
                + "only and catch assignments reject forward "
                + "references), so the caught-E8002 phase-order probe "
                + "cannot compile.", "JVM-GAP-DEFAULTS-PLANS");

        // ---- JVM-GAP-XMOD-FNVALUE: retired with the shared runtime
        // value surface (ISSUE-0301) ----
        // The four cross-module function-value fixtures
        // (closure-returned-from-module, imported-closure-factory,
        // imported-recursive-callback, imported-async-function-value)
        // pass the real pipeline on the shared $DealRt wrapper carriers
        // and were removed with their promotion; the stale-skip gate
        // forced the removals. host-async-shape-value (a host export
        // used as a first-class function value) stays with the host ABI
        // shapes lane below — the host wrapper surface is
        // jvm-v12-host-abi-completion's.
        skip("backend-runtime/host-abi/host-async-shape-value.deal",
            "E6000: module aliases used as values (host async export "
                + "as a function value).", "JVM-GAP-HOST-ABI-SHAPES");

        // ---- JVM-GAP-XMOD-ARRAY: retired with the shared runtime value
        // surface (ISSUE-0301) ----
        // await-returning-array-indexed passes the real pipeline on the
        // shared $DealRt array carriers and was removed with its
        // promotion; the stale-skip gate forced the removal.

        // ---- JVM-GAP-ASYNC-FNEXPR: RETIRED (ISSUE-0304) ----
        // async-fn-expr.deal and async-await-statement.deal pass the
        // real pipeline — async function expressions emit through the
        // sync closure machinery with the async descriptor marker and
        // blocking bodies, block-level async functions declare through
        // the cell + anonymous-wrapper path, and the discard-position
        // await statement evaluates exactly once with the completion
        // check — so the registry entries were removed and the
        // stale-skip gate forced the removal.
    }

    private static void skip(String path, String reason, String gapId) {
        SKIPS.put(path, new SkipEntry(path, reason, gapId));
    }

    /** Gap id → human-readable lane description, for the summary's
     * skip-group report. */
    private static final Map<String, String> FOLLOW_UP_GAPS = Map.of(
        "JVM-GAP-STDJSON", "std/json JVM boundary — JvmBackend E6000 at "
            + "import std/json (json.parse/stringify require table "
            + "values the JVM slice does not support)",
        "JVM-GAP-JSONABLE-RESIDUAL", "residual @jsonable JVM defects — "
            + "nested-array fromJson javac collision; table-field "
            + "nested arrays E8001; the fromJson top-level input gate "
            + "(ISSUE-0101 promotion)",
        "JVM-GAP-HOST-ABI-SHAPES", "JVM host ABI unsupported declared "
            + "shapes — host class exports, array/function-typed "
            + "parameters and returns (E6000), the Lua pre-wrapped "
            + "export form",
        "JVM-GAP-BYTES", "bytes runtime lane — the direct bytes "
            + "surface (allocation, length, indexing, mutation, class "
            + "fields) landed with ISSUE-0158; the remaining fixture "
            + "pins the recursive bytes-bearing array/nullable/"
            + "function wrapper closure (bytes[] / ?bytes / "
            + "(bytes)->bytes), which stays E6000 until ISSUE-0160",
        "JVM-GAP-DEFAULTS-PLANS", "v1.2 default-plan lane — imported "
            + "non-literal defaults evaluate in the declaring module's "
            + "scope under LuaJIT (E6000 on the JVM imported-class "
            + "slice) and the phase-order Error-catch probe is outside "
            + "the JVM slice (ISSUE-0340 is the LuaJIT emitter cutover)"
    );

    // =========================================================================
    // Host modules for the JVM-applicable host-ABI corpus tests
    // =========================================================================

    /**
     * Java host implementations for the host-ABI corpus tests whose
     * declared surfaces the landed JVM host ABI slice supports. Each
     * class name derives from the raw module path with the same
     * {@code classNameFor} rule every emitted module uses
     * (host/bad_return → HostBad_return); methods take the JVM-mapped
     * parameter types and return Object (or a CompletableFuture for
     * async exports), exactly like the jvm-host-abi-slice.json hosts.
     */
    private static final Map<String, String> HOST_JAVA = Map.ofEntries(
        Map.entry("async_bad",
            "import java.util.concurrent.CompletableFuture;\n"
                + "public final class HostAsync_bad {\n"
                + "  public static Object fetchValue() {\n"
                + "    return CompletableFuture.completedFuture(Long.valueOf(42L));\n"
                + "  }\n"
                + "}\n"),
        Map.entry("async_ok",
            "import java.util.concurrent.CompletableFuture;\n"
                + "public final class HostAsync_ok {\n"
                + "  public static Object fetchValue() {\n"
                + "    return CompletableFuture.completedFuture(\"fetched\");\n"
                + "  }\n"
                + "}\n"),
        Map.entry("async_shape_bad",
            "public final class HostAsync_shape_bad {\n"
                + "  public static Object fetchValue() {\n"
                + "    return Long.valueOf(42L);\n"
                + "  }\n"
                + "}\n"),
        Map.entry("bad_return",
            "public final class HostBad_return {\n"
                + "  public static Object getNumber() {\n"
                + "    return \"not a number\";\n"
                + "  }\n"
                + "}\n"),
        Map.entry("empty_return",
            "public final class HostEmpty_return {\n"
                + "  public static Object ping() {\n"
                + "    return null;\n"
                + "  }\n"
                + "}\n"),
        Map.entry("extra_export",
            "public final class HostExtra_export {\n"
                + "  public static Object ping() {\n"
                + "    return \"pong\";\n"
                + "  }\n"
                + "  public static Object extra() {\n"
                + "    return \"undeclared\";\n"
                + "  }\n"
                + "}\n"),
        Map.entry("missing_export",
            "public final class HostMissing_export {\n"
                + "  public static Object ping() {\n"
                + "    return \"pong\";\n"
                + "  }\n"
                + "}\n"),
        Map.entry("bad_string",
            "public final class HostBad_string {\n"
                + "  public static Object badString() {\n"
                + "    return \"a\\uD800b\";\n"
                + "  }\n"
                + "  public static Object surrogateString() {\n"
                + "    return \"\\uD800\";\n"
                + "  }\n"
                + "}\n"),
        Map.entry("nullreturn_bad",
            "public final class HostNullreturn_bad {\n"
                + "  public static Object ping() {\n"
                + "    return \"junk\";\n"
                + "  }\n"
                + "}\n"),
        Map.entry("nullreturn_ok",
            "public final class HostNullreturn_ok {\n"
                + "  public static Object ping() {\n"
                + "    return null;\n"
                + "  }\n"
                + "}\n"),
        Map.entry("nullable_return",
            "public final class HostNullable_return {\n"
                + "  public static Object find(String s) {\n"
                + "    if (\"__BAD__\".equals(s)) return Long.valueOf(42L);\n"
                + "    if (\"__NULL__\".equals(s)) return null;\n"
                + "    return s;\n"
                + "  }\n"
                + "}\n"),
        Map.entry("planprobe",
            "final class HostPlanprobe {\n"
                + "  private static long n = 0L;\n"
                + "  public static Object nextValue() {\n"
                + "    n = n + 1;\n"
                + "    return Long.valueOf(n * 10L);\n"
                + "  }\n"
                + "  public static Object valueCount() {\n"
                + "    return Long.valueOf(n);\n"
                + "  }\n"
                + "}\n")
    );


    // =========================================================================
    // Data types and counters
    // =========================================================================

    private record TestFile(Path path, String relativePath, String spec,
            String description, String expected, String features,
            String issue) {}

    private enum Kind {
        /** compile-ok / compile-error — backend-neutral frontend gate. */
        FRONTEND,
        /** runtime-ok / runtime-error — JVM-applicable backend test. */
        APPLICABLE,
        /** runtime test skipped under a catalog gap id — its
         *  underlying runtime mode is probed through the real
         *  pipeline each run (a passing probe is a stale registry
         *  entry that fails the gate). */
        SKIPPED,
        /** known-fail MODE — tracked follow-up issue; run + stale-checked. */
        KNOWN_FAIL
    }

    private record Classified(TestFile test, Kind kind, String expectedCode,
            String skipReason, String skipGapId) {}

    private record Outcome(TestFile test, Classified classified,
            boolean pass, String message) {}

    private static final AtomicInteger frontendTotal = new AtomicInteger();
    private static final AtomicInteger frontendPassed = new AtomicInteger();
    private static final AtomicInteger frontendFailed = new AtomicInteger();
    private static final AtomicInteger applicableTotal = new AtomicInteger();
    private static final AtomicInteger applicablePassed = new AtomicInteger();

    /** Profile-authority accounting (A4/A5), pinned at zero by
     * ISSUE-0378 D1: this lane applies the single activated invocation
     * to every fixture, so no fixture earns a legacy-authority label
     * (the untouched {@code deal.test.BackendConformanceTest} owns the
     * catalog seam and keeps its own accounting). */
    private static final AtomicInteger legacyAuthorityResults =
        new AtomicInteger();
    private static final AtomicInteger legacyAuthorityPassed =
        new AtomicInteger();
    private static final AtomicInteger legacyAuthorityFailed =
        new AtomicInteger();
    private static final AtomicInteger applicableFailed = new AtomicInteger();
    private static final AtomicInteger applicableSkipped = new AtomicInteger();
    private static final AtomicInteger knownFailTotal = new AtomicInteger();
    private static final AtomicInteger knownFailTracked = new AtomicInteger();
    private static final AtomicInteger knownFailStale = new AtomicInteger();
    private static final AtomicInteger staleSkip = new AtomicInteger();
    private static final AtomicInteger probeHarnessFailed =
        new AtomicInteger();

    private static final List<Outcome> outcomes =
        Collections.synchronizedList(new ArrayList<>());
    private static final Map<String, Integer> skipGroupCounts =
        Collections.synchronizedMap(new LinkedHashMap<>());
    private static final Map<String, Integer> knownFailByIssue =
        Collections.synchronizedMap(new LinkedHashMap<>());

    private static final ThreadLocal<StringBuilder> WORKER_OUTPUT =
        new ThreadLocal<>();
    private static final Object CONSOLE_LOCK = new Object();

    private static boolean jvmAvailable;
    private static Path conformanceRoot = Path.of("test/conformance/")
        .toAbsolutePath().normalize();
    private static Path hostFixturesRoot =
        conformanceRoot.resolve("host-fixtures");

    private static void log(String line) {
        StringBuilder buffer = WORKER_OUTPUT.get();
        if (buffer == null) {
            System.out.println(line);
        } else {
            buffer.append(line).append('\n');
        }
    }

    // =========================================================================
    // Main
    // =========================================================================

    public static void main(String[] args) throws Exception {
        if (args.length > 0) {
            conformanceRoot = Path.of(args[0]).toAbsolutePath().normalize();
            hostFixturesRoot = conformanceRoot.resolve("host-fixtures");
        }
        if (!Files.isDirectory(conformanceRoot)) {
            System.out.println("No conformance root found at " + conformanceRoot);
            System.exit(2);
        }

        jvmAvailable = probeJvm();

        System.out.println("=== DEAL v1.2 JVM Conformance Suite (ISSUE-0102 origin — ISSUE-0168 capability accounting) ===");
        System.out.println("Root: " + conformanceRoot);
        System.out.println("JVM (javac + java): " + (jvmAvailable ? "available"
            : "NOT available (backend-runtime tests will fail — a bypassed "
                + "JVM execution is not a pass)"));
        System.out.println();

        List<TestFile> discovered = discoverTests();
        // LegacyProfileRegressionCatalog validation (A4): rows resolve,
        // the closed completeness scan finds no uncatalogued
        // legacy-dependent assertion, and the mechanism self-probes pass
        // before any fixture executes.
        LegacyProfileRegressionCatalog.validateRows();
        LegacyProfileRegressionCatalog.validateReplacementRows();
        LegacyProfileRegressionCatalog.runSelfProbes();
        for (TestFile test : discovered) {
            LegacyProfileRegressionCatalog.scanDealSource(
                test.relativePath(), ConformanceHarnessMetadata
                    .stripClassificationHeaders(
                        Files.readString(test.path())),
                test.expected());
        }
        List<String> catalogViolations =
            LegacyProfileRegressionCatalog.drainViolations();
        if (!catalogViolations.isEmpty()) {
            System.out.println("CATALOG FAILURE: "
                + "LegacyProfileRegressionCatalog validation failed:");
            for (String violation : catalogViolations) {
                System.out.println("  " + violation);
            }
            System.exit(1);
        }
        List<Classified> tests = classifyAll(discovered);
        int frontend = (int) tests.stream()
            .filter(c -> c.kind() == Kind.FRONTEND).count();
        int applicable = (int) tests.stream()
            .filter(c -> c.kind() == Kind.APPLICABLE).count();
        int skipped = (int) tests.stream()
            .filter(c -> c.kind() == Kind.SKIPPED).count();
        int knownFail = (int) tests.stream()
            .filter(c -> c.kind() == Kind.KNOWN_FAIL).count();
        System.out.println("Discovered " + tests.size() + " conformance "
            + "test(s): " + frontend + " frontend-classified, "
            + applicable + " JVM-applicable backend-runtime, "
            + skipped + " skipped (classified), " + knownFail
            + " known-fail (tracked)");
        System.out.println();

        // Deterministic execution order: sorted by corpus-relative path.
        List<Classified> ordered = new ArrayList<>(tests);
        ordered.sort(Comparator.comparing(c -> c.test().relativePath()));

        int workers = Math.max(1, Math.min(
            Integer.getInteger("deal.test.jobs", DEFAULT_JOBS),
            ordered.size()));
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (Classified classified : ordered) {
                futures.add(pool.submit(() -> runOne(classified)));
            }
            for (Future<?> future : futures) {
                future.get();
            }
        } finally {
            pool.shutdownNow();
        }

        List<Outcome> sorted = new ArrayList<>(outcomes);
        sorted.sort(Comparator.comparing(o -> o.test().relativePath()));
        printReport(sorted, applicable + skipped + knownFail);
    }

    /** Probes that both {@code javac} and {@code java} are invocable and
     * functional — the same gate BackendConformanceTest uses. */
    private static boolean probeJvm() {
        try {
            Process javac = new ProcessBuilder("javac", "-version")
                .redirectErrorStream(true).start();
            if (javac.waitFor() != 0) return false;
            Process java = new ProcessBuilder("java", "-version")
                .redirectErrorStream(true).start();
            return java.waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    // =========================================================================
    // Discovery and classification
    // =========================================================================

    private static List<TestFile> discoverTests() throws IOException {
        Path backendRoot = conformanceRoot.resolve("backend-runtime");
        List<TestFile> result = new ArrayList<>();
        try (var stream = Files.walk(backendRoot)) {
            stream.filter(p -> p.toString().endsWith(".deal"))
                  .sorted()
                  .forEach(p -> {
                      TestFile tf = parseMetadata(p);
                      if (tf != null) {
                          result.add(tf);
                      }
                  });
        }
        return result;
    }

    private static TestFile parseMetadata(Path file) {
        try {
            List<String> lines = Files.readAllLines(file);
            String spec = "";
            String description = "";
            String expected = "";
            String features = "";
            String issue = "";

            int linesToScan = Math.min(lines.size(), 40);
            for (int i = 0; i < linesToScan; i++) {
                String line = lines.get(i).trim();
                if (line.startsWith("// @spec:")) {
                    spec = line.substring("// @spec:".length()).trim();
                } else if (line.startsWith("// @description:")) {
                    description = line.substring(
                        "// @description:".length()).trim();
                } else if (line.startsWith("// @expected:")) {
                    expected = line.substring("// @expected:".length()).trim();
                } else if (line.startsWith("// @features:")) {
                    features = line.substring(
                        "// @features:".length()).trim();
                } else if (line.startsWith("// @issue:")) {
                    issue = line.substring("// @issue:".length()).trim();
                }
            }

            if (expected.isEmpty()) {
                throw new IllegalStateException("no @expected tag in "
                    + file + " — the v1.2 gate has no unclassified files");
            }

            Path rel = conformanceRoot.relativize(file);
            String relPath = rel.toString().replace('\\', '/');
            return new TestFile(file, relPath, spec, description, expected,
                features, issue);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
    }

    /**
     * The deterministic applicability policy. See the class javadoc.
     * The {@link Kind#SKIPPED} branch is only ever reached through a
     * registry entry with a reason and a gap id — there is no
     * unclassified fallback skip branch, and every registry entry
     * must be reached by classification (a dead entry fails
     * validation).
     */
    private static List<Classified> classifyAll(List<TestFile> tests) {
        List<Classified> result = new ArrayList<>();
        for (TestFile test : tests) {
            String expected = test.expected();
            if (expected.equals("companion")) {
                continue; // classified support module; compiled transitively
            }
            if (expected.startsWith("known-fail ")) {
                String mode = expected.substring("known-fail ".length())
                    .trim();
                String code = mode.startsWith("runtime-error ")
                    ? mode.substring("runtime-error ".length()).trim() : "";
                if (test.issue().isEmpty()) {
                    throw new IllegalStateException("known-fail without "
                        + "@issue in " + test.relativePath());
                }
                result.add(new Classified(test, Kind.KNOWN_FAIL, code, null,
                    test.issue()));
            } else if (expected.startsWith("compile-ok")) {
                result.add(new Classified(test, Kind.FRONTEND, "", null,
                    null));
            } else if (expected.startsWith("compile-error ")) {
                result.add(new Classified(test, Kind.FRONTEND,
                    expected.substring("compile-error ".length()).trim(),
                    null, null));
            } else if (expected.startsWith("runtime-ok")
                    || expected.startsWith("runtime-error ")) {
                String code = expected.startsWith("runtime-error ")
                    ? expected.substring("runtime-error ".length()).trim()
                    : "";
                SkipEntry entry = SKIPS.get(test.relativePath());
                if (entry != null) {
                    result.add(new Classified(test, Kind.SKIPPED, code,
                        entry.reason(), entry.gapId()));
                } else {
                    result.add(new Classified(test, Kind.APPLICABLE, code,
                        null, null));
                }
            } else {
                // Unknown @expected is a harness failure, never a skip.
                throw new IllegalStateException("unknown @expected '"
                    + expected + "' in " + test.relativePath());
            }
        }
        validateRegistry(tests, result);
        return result;
    }

    /**
     * Validates the explicit skip registry against the on-disk corpus.
     * Every entry must name an existing, discovered, runtime-classified
     * (runtime-ok / runtime-error) backend-runtime test, carry a reason
     * and a catalog gap id, and be reached by classification — no skip
     * without a reason or gap id, no unknown gap id, and no dead entry
     * (a missing, frontend-classified, companion, or known-fail file
     * fails the run naming the entry).
     */
    private static void validateRegistry(List<TestFile> discovered,
            List<Classified> classified) {
        Map<String, TestFile> byPath = new LinkedHashMap<>();
        for (TestFile test : discovered) {
            byPath.put(test.relativePath(), test);
        }
        Set<String> skippedPaths = new HashSet<>();
        for (Classified c : classified) {
            if (c.kind() == Kind.SKIPPED) {
                skippedPaths.add(c.test().relativePath());
            }
        }
        for (SkipEntry entry : SKIPS.values()) {
            Path file = conformanceRoot.resolve(entry.path());
            if (!Files.isRegularFile(file)) {
                throw new IllegalStateException("skip registry entry "
                    + entry.path() + " does not name an on-disk corpus "
                    + "test — the registry must stay current");
            }
            if (entry.reason() == null || entry.reason().isEmpty()
                    || entry.gapId() == null || entry.gapId().isEmpty()) {
                throw new IllegalStateException("skip registry entry "
                    + entry.path() + " lacks a reason or gap id");
            }
            if (!FOLLOW_UP_GAPS.containsKey(entry.gapId())) {
                throw new IllegalStateException("skip registry entry "
                    + entry.path() + " cites unknown gap id '"
                    + entry.gapId() + "' — the gap id must be a "
                    + "FOLLOW_UP_GAPS catalog key");
            }
            TestFile named = byPath.get(entry.path());
            if (named == null) {
                throw new IllegalStateException("skip registry entry "
                    + entry.path() + " does not name a discovered "
                    + "backend-runtime corpus test — dead entry");
            }
            if (!named.expected().startsWith("runtime-ok")
                    && !named.expected().startsWith("runtime-error ")) {
                throw new IllegalStateException("skip registry entry "
                    + entry.path() + " names a non-runtime-classified "
                    + "file (@expected: '" + named.expected() + "') — "
                    + "dead entry; the registry may only name "
                    + "runtime-ok / runtime-error tests");
            }
            if (!skippedPaths.contains(entry.path())) {
                throw new IllegalStateException("skip registry entry "
                    + entry.path() + " is not reached by classification "
                    + "— dead entry");
            }
        }
    }

    // =========================================================================
    // Execution dispatch
    // =========================================================================

    private static void runOne(Classified classified) {
        StringBuilder buffer = new StringBuilder();
        WORKER_OUTPUT.set(buffer);
        try {
            Outcome outcome = switch (classified.kind()) {
                case FRONTEND -> runFrontend(classified);
                case APPLICABLE -> runApplicable(classified);
                case KNOWN_FAIL -> runKnownFail(classified);
                case SKIPPED -> runSkippedProbe(classified);
            };
            if (outcome != null) {
                outcomes.add(outcome);
            }
        } catch (Throwable e) {
            log("  [" + classified.test().relativePath()
                + "] FAIL: runner exception: " + e.getMessage());
            if (classified.kind() == Kind.FRONTEND) {
                frontendFailed.incrementAndGet();
                outcomes.add(new Outcome(classified.test(), classified,
                    false, "runner exception: " + e.getMessage()));
            } else if (classified.kind() == Kind.APPLICABLE) {
                applicableFailed.incrementAndGet();
                outcomes.add(new Outcome(classified.test(), classified,
                    false, "runner exception: " + e.getMessage()));
            } else {
                // A runner exception (an Error, or anything thrown
                // before runApplicable's internal Exception catch)
                // escaping a SKIPPED or KNOWN_FAIL probe is an explicit
                // harness failure — never an applicable failure, never
                // a tracked skip.
                probeHarnessFailed.incrementAndGet();
                log("  [" + classified.test().relativePath()
                    + "] FAIL (runner exception during skip/known-fail "
                    + "probe — harness failure): " + e.getMessage());
                outcomes.add(new Outcome(classified.test(), classified,
                    false, "runner exception during skip/known-fail "
                    + "probe — harness failure: " + e.getMessage()));
            }
        } finally {
            WORKER_OUTPUT.remove();
            synchronized (CONSOLE_LOCK) {
                System.out.print(buffer);
            }
        }
    }

    // =========================================================================
    // Frontend gate (backend-neutral compile-ok / compile-error)
    // =========================================================================

    private static Outcome runFrontend(Classified classified) {
        TestFile test = classified.test();
        frontendTotal.incrementAndGet();
        List<CompilerDiagnostic> diags = frontendDiagnostics(test.path(),
            LegacyProfileRegressionCatalog.frontendInvocation()
                .semanticProfile());
        boolean hasErrors = diags.stream()
            .anyMatch(d -> "error".equals(d.severity()));
        String expected = test.expected();
        if (expected.startsWith("compile-ok")) {
            if (hasErrors) {
                frontendFailed.incrementAndGet();
                StringBuilder sb = new StringBuilder();
                for (CompilerDiagnostic d : diags) {
                    if ("error".equals(d.severity())) {
                        sb.append("    ").append(d).append('\n');
                    }
                }
                log("  [" + test.relativePath()
                    + "] FAIL (unexpected compile errors)\n" + sb);
                return new Outcome(test, classified, false,
                    "unexpected compile errors");
            }
            frontendPassed.incrementAndGet();
            log("  [" + test.relativePath() + "] OK (frontend)");
            return new Outcome(test, classified, true, "compile ok");
        }
        String code = expected.substring("compile-error ".length()).trim();
        boolean found = diags.stream().anyMatch(
            d -> "error".equals(d.severity()) && code.equals(d.code()));
        if (found) {
            frontendPassed.incrementAndGet();
            log("  [" + test.relativePath() + "] OK (found " + code + ")");
            return new Outcome(test, classified, true, "found " + code);
        }
        List<String> gotCodes = diags.stream()
            .filter(d -> "error".equals(d.severity()))
            .map(CompilerDiagnostic::code)
            .toList();
        frontendFailed.incrementAndGet();
        log("  [" + test.relativePath() + "] FAIL (expected " + code
            + ", got: " + gotCodes + ")");
        return new Outcome(test, classified, false,
            "expected " + code + ", got: " + gotCodes);
    }

    /**
     * The shared frontend pipeline — lexer → parser → name resolver →
     * type checker — with the same stdlib/relative-import resolution the
     * LuaJIT conformance harness uses (stdlib exports from the
     * spec-listed .d.deal declarations, relative imports via
     * {@link ExportExtractor}). A bypassed parser or checker yields no
     * diagnostics and the compile-error tests fail.
     */
    @SuppressWarnings("deprecation")
    private static List<CompilerDiagnostic> frontendDiagnostics(Path file,
            SemanticProfile profile) {
        List<CompilerDiagnostic> all = new ArrayList<>();
        try {
            // ISSUE-0272 D8 item 2a: in-memory seam site — classification
            // headers are stripped before the lexer; parseMetadata keeps
            // reading the raw fixture bytes.
            String source = ConformanceHarnessMetadata
                .stripClassificationHeaders(Files.readString(file));
            String filename = file.toString();

            LexResult lex = new Lexer(source, filename).tokenize();
            all.addAll(lex.diagnostics());
            if (lex.hasErrors()) return all;

            Parser parser = new Parser(lex.tokens(),
                    filename, profile,
                    lex.directiveEvents());
            ParseResult parseResult = parser.parse();
            all.addAll(parseResult.diagnostics());
            if (parseResult.hasErrors()) return all;

            FrontendModuleResolver resolver =
                new FrontendModuleResolver(file, profile);
            NameResolver nr = new NameResolver(filename, resolver);
            SymbolTable symTable;
            try {
                symTable = nr.resolve(parseResult.program());
            } catch (Exception e) {
                // E9999 is the test-only pseudo code for an unexpected
                // NameResolver exception (D5/verification 6): the
                // deprecated synthetic factory carries the canonical
                // synthetic range plus an anchor note naming the fixture
                // file.
                all.add(CompilerDiagnostic.synthetic("E9999", "error",
                    e.getMessage(), filename,
                    "missing anchor: fixture source '" + filename + "'"));
                return all;
            }
            all.addAll(nr.diagnostics());

            CheckResult result = TypeChecker.check(filename, symTable, nr,
                parseResult.program());
            all.addAll(result.diagnostics());
            return all;
        } catch (IOException e) {
            String filename = file.toString();
            all.add(CompilerDiagnostic.synthetic("E9999", "error",
                "cannot read " + file, filename,
                "missing anchor: fixture source '" + filename + "'"));
            return all;
        }
    }

    /** Module resolver for the frontend gate: spec-listed stdlib exports
     * and relative {@code ./} / {@code ../} imports resolved through
     * {@link ExportExtractor} with synthesized class symbols — the same
     * resolution surface the LuaJIT conformance harness uses for
     * compile-stage tests. */
    private static final class FrontendModuleResolver
            implements ModuleResolver {
        private final Path testFileDir;
        private final Map<String, Map<String, Type>> stdlibExports;
        private final SemanticProfile profile;

        FrontendModuleResolver(Path testFile, SemanticProfile profile) {
            this.testFileDir = testFile.toAbsolutePath().getParent();
            this.profile = java.util.Objects.requireNonNull(profile,
                "profile must not be null");
            // ISSUE-0269: the resolved distribution surface (the
            // CWD-relative no-arg read is retired).
            this.stdlibExports = StdlibModuleResolver.stdlibExports(
                Path.of("std").toAbsolutePath().normalize().toString());
        }

        @Override
        public Map<String, Type> resolveModule(String modulePath,
                String importingModule, Set<String> modulesInProgress)
                throws ModuleNotFoundException {
            if (stdlibExports.containsKey(modulePath)) {
                return stdlibExports.get(modulePath);
            }
            if (modulePath.startsWith("std/") && !modulePath.startsWith("./")
                    && !modulePath.startsWith("../")) {
                throw new ModuleNotFoundException(
                    "Module not found: '" + modulePath
                    + "' is not a spec-listed stdlib module");
            }
            Path resolved = resolveRelativePath(modulePath);
            if (resolved != null && Files.exists(resolved)) {
                try {
                    // ISSUE-0272 D8 item 2a: in-memory seam site —
                    // classification headers are stripped before the lexer.
                    String source = ConformanceHarnessMetadata
                        .stripClassificationHeaders(Files.readString(resolved));
                    boolean isDecl = resolved.toString().endsWith(".d.deal");
                    LexResult lex = new Lexer(source, resolved.toString())
                        .tokenize();
                    if (lex.hasErrors()) {
                        throw new ModuleNotFoundException("Lex errors in "
                            + resolved);
                    }
                    Parser parser = new Parser(lex.tokens(),
                    resolved.toString(), profile,
                    lex.directiveEvents());
                    ParseResult parseResult = parser.parse();
                    if (parseResult.hasErrors()) {
                        throw new ModuleNotFoundException("Parse errors in "
                            + resolved);
                    }
                    ExportExtractor extractor = new ExportExtractor(
                        resolved.toString(), isDecl);
                    return extractor.extract(parseResult.program());
                } catch (IOException e) {
                    throw new ModuleNotFoundException("Cannot read: "
                        + resolved);
                }
            }
            throw new ModuleNotFoundException("Module not found: "
                + modulePath);
        }

        @Override
        public Symbol.ClassSymbol resolveClassSymbol(String className,
                String modulePath, String importingModule)
                throws ModuleNotFoundException {
            if (modulePath == null || modulePath.isEmpty()) return null;
            Path resolved = resolveRelativePath(modulePath);
            if (resolved == null || !Files.exists(resolved)) return null;
            return classSymbolsOf(resolved).get(className);
        }

        @Override
        public Symbol.ClassSymbol resolveClassSymbol(String className,
                deal.identity.CanonicalModuleIdentity declaringModule,
                String importingModule)
                throws ModuleNotFoundException {
            // v1.2 identity carriage: route the carried companion
            // identity (the standalone ProjectModule(dotted) convention
            // this frontend resolver synthesizes) back to the
            // synthesized symbols.
            for (java.nio.file.Path candidate
                    : companionPaths(declaringModule)) {
                Symbol.ClassSymbol sym = classSymbolsOf(candidate)
                    .get(className);
                if (sym != null) {
                    return sym;
                }
            }
            return null;
        }

        /** The companion files whose synthesized identity equals the
         * carried module identity (frontend-gate routing only). */
        private java.util.List<java.nio.file.Path> companionPaths(
                deal.identity.CanonicalModuleIdentity declaringModule) {
            java.util.List<java.nio.file.Path> result =
                new java.util.ArrayList<>();
            try (java.util.stream.Stream<java.nio.file.Path> stream =
                    java.nio.file.Files.list(testFileDir)) {
                for (java.nio.file.Path file : stream.toList()) {
                    String name = file.getFileName().toString();
                    if (!name.endsWith(".deal") && !name.endsWith(".d.deal")) {
                        continue;
                    }
                    String stem = name.endsWith(".d.deal")
                        ? name.substring(0, name.length() - ".d.deal".length())
                        : name.substring(0, name.length() - ".deal".length());
                    if (declaringModule.equals(
                            IdentityTestFixtures.moduleIdentityOf(stem))) {
                        result.add(file);
                    }
                }
            } catch (IOException ignored) {
                // frontend-gate best effort
            }
            return result;
        }

        @Override
        public Type resolveTypeNodeInModule(TypeNode typeNode,
                String modulePath, String importingModule)
                throws ModuleNotFoundException {
            if (modulePath == null || modulePath.isEmpty()) return null;
            Path resolved = resolveRelativePath(modulePath);
            if (resolved == null || !Files.exists(resolved)) return null;
            try {
                // ISSUE-0272 D8 item 2a: in-memory seam site —
                // classification headers are stripped before the lexer.
                String source = ConformanceHarnessMetadata
                    .stripClassificationHeaders(Files.readString(resolved));
                LexResult lex = new Lexer(source, resolved.toString())
                    .tokenize();
                if (lex.hasErrors()) return null;
                Parser parser = new Parser(lex.tokens(),
                    resolved.toString(), profile,
                    lex.directiveEvents());
                ParseResult parseResult = parser.parse();
                if (parseResult.hasErrors()) return null;
                NameResolver nr = new NameResolver(resolved.toString(), this);
                nr.resolve(parseResult.program());
                return nr.resolveTypeNode(typeNode);
            } catch (Exception e) {
                return null;
            }
        }

        /** Parses a relative companion and synthesizes its class symbols
         * (the dotted module path view the checker consumes). */
        private Map<String, Symbol.ClassSymbol> classSymbolsOf(Path file) {
            Map<String, Symbol.ClassSymbol> symbols = new LinkedHashMap<>();
            try {
                // ISSUE-0272 D8 item 2a: in-memory seam site —
                // classification headers are stripped before the lexer.
                String source = ConformanceHarnessMetadata
                    .stripClassificationHeaders(Files.readString(file));
                LexResult lex = new Lexer(source, file.toString()).tokenize();
                if (lex.hasErrors()) return symbols;
                Parser parser = new Parser(lex.tokens(),
                    file.toString(), profile,
                    lex.directiveEvents());
                ParseResult parseResult = parser.parse();
                if (parseResult.hasErrors()) return symbols;
                String dotted = modulePathOf(file);
                for (StatementNode stmt : parseResult.program().statements()) {
                    if (stmt instanceof ClassDeclaration cd) {
                        symbols.put(cd.name(), new Symbol.ClassSymbol(
                            cd.name(), cd.fields(), dotted,
                            IdentityTestFixtures.identityOf(dotted,
                                cd.name())));
                    } else if (stmt instanceof ExportDeclaration exp
                            && exp.declaration() instanceof ClassDeclaration cd) {
                        symbols.put(cd.name(), new Symbol.ClassSymbol(
                            cd.name(), cd.fields(), dotted,
                            IdentityTestFixtures.identityOf(dotted,
                                cd.name())));
                    }
                }
            } catch (IOException ignored) { }
            return symbols;
        }

        private String modulePathOf(Path file) {
            String name = file.getFileName().toString();
            if (name.endsWith(".d.deal")) {
                return name.substring(0, name.length() - ".d.deal".length());
            }
            return name.substring(0, name.length() - ".deal".length());
        }

        private Path resolveRelativePath(String importPath) {
            if (!importPath.startsWith("./") && !importPath.startsWith("../")) {
                return null;
            }
            Path resolved = testFileDir.resolve(importPath).normalize();
            if (Files.exists(resolved)) return resolved;
            Path withExt = testFileDir.resolve(importPath + ".deal").normalize();
            if (Files.exists(withExt)) return withExt;
            Path withDeclExt = testFileDir.resolve(
                importPath + ".d.deal").normalize();
            if (Files.exists(withDeclExt)) return withDeclExt;
            return null;
        }
    }

    // =========================================================================
    // Known-fail cases (tracked follow-up issues)
    // =========================================================================

    /**
     * Executes the underlying runtime mode of a known-fail case through
     * the real JVM pipeline. While the case still fails, it is recorded
     * as a non-fatal tracked KNOWN-FAIL; when it starts passing, the
     * gate FAILS with a promotion instruction (drop the marker).
     */
    private static Outcome runKnownFail(Classified classified) {
        TestFile test = classified.test();
        knownFailTotal.incrementAndGet();
        String mode = test.expected().substring(
            "known-fail ".length()).trim();
        Outcome probe = runApplicable(classified, true);
        if (probe.pass()) {
            knownFailStale.incrementAndGet();
            log("  [" + test.relativePath()
                + "] FAIL (STALE known-fail: the v1.2 requirement tracked "
                + "by " + test.issue() + " now passes on JVM — promote the "
                + "fixture: set '@expected: " + mode + "' and drop the "
                + "@issue tag)");
            return new Outcome(test, classified, false,
                "stale known-fail; promote fixture");
        }
        knownFailTracked.incrementAndGet();
        knownFailByIssue.merge(test.issue(), 1, Integer::sum);
        log("  [" + test.relativePath() + "] KNOWN-FAIL (" + mode
            + " not yet satisfied on JVM; tracked by " + test.issue()
            + ") — " + probe.message());
        return null;
    }

    // =========================================================================
    // Skip probes (D1): every SKIPPED entry runs its underlying mode
    // through the real pipeline; a passing probe is a stale entry
    // =========================================================================

    /**
     * Executes a SKIPPED entry's underlying runtime mode through the
     * real pipeline (orchestrator → JvmBackend codegen → javac → java)
     * with {@code knownFailProbe=true}, so no branch touches the
     * applicable counters. A failing probe is tracked evidence: one
     * {@code applicableSkipped} plus the per-gap count, and the skip
     * line carries the live probe message. A passing probe means the
     * registry entry is stale: {@code staleSkip} increments, the
     * promotion instruction is logged, a failed Outcome is recorded,
     * and the gate fails.
     */
    private static Outcome runSkippedProbe(Classified classified) {
        TestFile test = classified.test();
        Outcome probe = runApplicable(classified, true);
        if (probe.pass()) {
            staleSkip.incrementAndGet();
            log("  [" + test.relativePath()
                + "] FAIL (STALE skip: " + test.relativePath()
                + " now passes on JVM — remove the skip-registry entry)");
            return new Outcome(test, classified, false,
                "stale skip; remove the skip-registry entry");
        }
        applicableSkipped.incrementAndGet();
        skipGroupCounts.merge(classified.skipGapId(), 1, Integer::sum);
        log("  [" + test.relativePath() + "] SKIP ("
            + classified.skipGapId() + "): " + classified.skipReason()
            + " — probe: " + probe.message());
        return null;
    }

    // =========================================================================
    // Backend-runtime execution: orchestrator → javac → java
    // =========================================================================

    private static Outcome runApplicable(Classified classified) {
        return runApplicable(classified, false);
    }

    private static Outcome runApplicable(Classified classified,
            boolean knownFailProbe) {
        // ISSUE-0378 D1: every fixture — applicable, known-fail probe,
        // or skip probe — compiles through the single lane-wide
        // activated invocation; no legacy-authority labeling exists in
        // this lane, so the profile-authority accounting stays at zero.
        return runApplicableImpl(classified, knownFailProbe);
    }

    private static Outcome runApplicableImpl(Classified classified,
            boolean knownFailProbe) {
        TestFile test = classified.test();
        if (!knownFailProbe) {
            applicableTotal.incrementAndGet();
        }
        if (!jvmAvailable) {
            if (!knownFailProbe) {
                applicableFailed.incrementAndGet();
                log("  [" + test.relativePath()
                    + "] FAIL (javac/java unavailable — the JVM "
                    + "execution stage cannot be bypassed)");
            }
            return new Outcome(test, classified, false,
                "javac/java unavailable");
        }

        Path projectRoot = null;
        try {
            projectRoot = Files.createTempDirectory("deal_jvm_conf_");

            // 1. Materialize the test file and its transitive companion
            // closure under the configured root "src" of the temp
            // project (flat stem namespace; the corpus has no duplicate
            // stems). Relative imports resolve on disk from the corpus
            // directories.
            Map<String, Path> written = new LinkedHashMap<>();
            writeModuleFiles(projectRoot.resolve("src"), test.path(), written);
            String entryRel = "src/" + corpusStem(test.path()) + ".deal";
            Path entryFile = projectRoot.resolve(entryRel);
            Path outputRoot = projectRoot.resolve("out");

            // 2. Every harness project receives an injected exact-v1.2
            // deal.json and routes through production ProjectLocator
            // (ISSUE-0269, parent D12): moduleRoots ["src"] (the
            // representable configured root), output "out", backend
            // "jvm" — host-ABI fixtures additionally carry the
            // externals map wiring every raw host import path to its
            // declaration under the project root (bindings/).
            Set<String> hostNames = hostImports(test.path());
            StringBuilder dealJson = new StringBuilder();
            dealJson.append("{\n  \"languageVersion\": \"1.2\",\n");
            dealJson.append("  \"moduleRoots\": [\"src\"],\n");
            dealJson.append("  \"output\": \"out\",\n");
            dealJson.append("  \"backend\": \"jvm\"");
            if (!hostNames.isEmpty()) {
                // ISSUE-0272 D8 item 2b: producer-side seam — the host
                // declaration materialization strips classification
                // headers before the bytes reach the orchestrator, and
                // each raw host import path is wired to its declaration
                // under the project root (bindings/).
                copyHostBindings(projectRoot, hostFixturesRoot, hostNames);
                dealJson.append(",\n  \"externals\": {\n");
                boolean first = true;
                for (String hostName : hostNames) {
                    if (!first) dealJson.append(",\n");
                    first = false;
                    String declRel = "bindings/" + hostName + ".d.deal";
                    dealJson.append("    \"host/").append(hostName)
                        .append("\": { \"declaration\": \"")
                        .append(declRel).append("\" }");
                }
                dealJson.append("\n  }");
            }
            dealJson.append("\n}\n");
            Files.writeString(projectRoot.resolve("deal.json"), dealJson);
            ProjectLocator.LocateResult located =
                ProjectLocator.locate(entryFile.toString(), null);
            if (located.context() == null) {
                throw new IllegalStateException(
                    "generated deal.json did not locate strictly: "
                        + located.e2010());
            }

            // 3. The real whole-project pipeline: module discovery,
            // signature extraction, dependency ordering, name resolution,
            // type checking, per-module JvmBackend codegen — driven by
            // the published immutable ProjectContext. Every on-disk
            // backend-runtime fixture compiles through the single
            // lane-wide activated invocation (ISSUE-0378 D1): the
            // explicit CompilerProfileProvider.resolve(V1_2_ACTIVE,
            // releaseRegistry()) PUBLIC_BUILD + DEAL_V1_2_INT32 record;
            // no per-fixture catalog seam and no legacy-authority
            // routing exist in this lane.
            CompilerInvocation invocation = LANE_INVOCATION;
            OrchestratorRun run = runOrchestrator(entryFile,
                located.context(), invocation);
            if (!run.success()) {
                if (knownFailProbe) {
                    return new Outcome(test, classified, false,
                        "orchestrator compile failed: " + run.diagnostics());
                }
                applicableFailed.incrementAndGet();
                log("  [" + test.relativePath()
                    + "] FAIL (orchestrator compile failed): "
                    + run.diagnostics() + "\n" + run.capturedOutput());
                return new Outcome(test, classified, false,
                    "orchestrator compile failed: " + run.diagnostics());
            }

            // 4. Codegen was real: the entry artifact must exist before
            // javac runs.
            String entryClass = entryClassName(entryRel);
            Path entryJava = outputRoot.resolve(entryClass + ".java");
            if (!Files.exists(entryJava)) {
                if (!knownFailProbe) applicableFailed.incrementAndGet();
                log("  [" + test.relativePath()
                    + "] FAIL (JVM codegen produced no '"
                    + entryJava.getFileName() + "' artifact)");
                return new Outcome(test, classified, false,
                    "no .java artifact produced");
            }

            // 5. Host implementation classes compile together with the
            // emitted artifacts (the harness analog of the user placing
            // the host implementation on the compile/runtime classpath).
            for (String hostName : hostNames) {
                String javaSrc = HOST_JAVA.get(hostName);
                if (javaSrc == null) {
                    throw new IllegalStateException("no JVM host "
                        + "implementation for " + hostName);
                }
                String hostClass = JvmBackend.classNameFor(
                    "host/" + hostName);
                Files.writeString(outputRoot.resolve(hostClass + ".java"),
                    javaSrc);
            }

            // 6. Runner: auto-invokes the entry module's zero-arity
            // exports, driven by the real parser's export list.
            ProgramNode entryProgram = parseEntryProgram(entryFile,
                invocation.semanticProfile());
            Path runnerFile = outputRoot.resolve("JvmConformanceRunner.java");
            Files.writeString(runnerFile,
                BackendConformanceTest.buildJvmRunner(entryProgram,
                    entryClass));

            // 7. javac over every emitted .java artifact plus the runner
            // and the host classes (in-process javax.tools — the
            // identical parse/enter/analyze/generate passes the javac
            // binary runs, the same documented frontend the canonical
            // BackendConformanceTest uses for its JVM fixtures).
            List<String> javaFiles = new ArrayList<>();
            try (var stream = Files.list(outputRoot)) {
                stream.filter(p -> p.toString().endsWith(".java"))
                      .sorted()
                      .forEach(p -> javaFiles.add(p.getFileName().toString()));
            }
            StringBuilder javacErr = new StringBuilder();
            boolean javacOk = BackendConformanceTest.compileWithJavac(
                outputRoot, javaFiles, javacErr);
            if (!javacOk) {
                if (knownFailProbe) {
                    return new Outcome(test, classified, false,
                        "javac failed: " + javacErr);
                }
                applicableFailed.incrementAndGet();
                log("  [" + test.relativePath() + "] FAIL (javac failed):\n"
                    + javacErr);
                return new Outcome(test, classified, false,
                    "javac failed: " + javacErr);
            }
            if (!Files.exists(outputRoot.resolve(entryClass + ".class"))
                    || !Files.exists(outputRoot.resolve(
                        "JvmConformanceRunner.class"))) {
                if (!knownFailProbe) applicableFailed.incrementAndGet();
                log("  [" + test.relativePath()
                    + "] FAIL (javac exited 0 but no .class artifacts "
                    + "were produced — JVM compilation bypassed)");
                return new Outcome(test, classified, false,
                    "no .class artifacts produced");
            }

            // 8. Execute the emitted artifacts with a real java subprocess.
            ProcessBuilder pb = new ProcessBuilder("java", "-cp",
                outputRoot.toString(), "JvmConformanceRunner");
            pb.directory(outputRoot.toFile());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String output = new String(
                p.getInputStream().readAllBytes()).trim();
            int exitCode = p.waitFor();

            if (test.expected().startsWith("runtime-ok")
                    || test.expected().startsWith("known-fail runtime-ok")) {
                if (exitCode != 0) {
                    if (!knownFailProbe) {
                        applicableFailed.incrementAndGet();
                        log("  [" + test.relativePath()
                            + "] FAIL (runtime-ok test exited " + exitCode
                            + "): " + output);
                    }
                    return new Outcome(test, classified, false,
                        "exited " + exitCode + ": " + output);
                }
                if (!knownFailProbe) applicablePassed.incrementAndGet();
                log("  [" + test.relativePath() + "] OK");
                return new Outcome(test, classified, true, "runtime ok");
            }

            String needle = "DEAL_ERROR_CODE: "
                + classified.expectedCode();
            if (output.contains(needle)) {
                if (!knownFailProbe) applicablePassed.incrementAndGet();
                log("  [" + test.relativePath() + "] OK (found " + needle
                    + ")");
                return new Outcome(test, classified, true,
                    "found " + needle);
            }
            if (!knownFailProbe) {
                applicableFailed.incrementAndGet();
                log("  [" + test.relativePath() + "] FAIL (expected "
                    + needle + ", got: "
                    + output.replace("\n", "\\n") + ")");
            }
            return new Outcome(test, classified, false,
                "expected " + needle + ", got: " + output);
        } catch (Exception e) {
            if (!knownFailProbe) applicableFailed.incrementAndGet();
            log("  [" + test.relativePath() + "] FAIL (execution exception): "
                + e.getMessage());
            return new Outcome(test, classified, false,
                "execution exception: " + e.getMessage());
        } finally {
            if (projectRoot != null) {
                try {
                    Files.walk(projectRoot).sorted(Comparator.reverseOrder())
                        .forEach(f -> { try { Files.deleteIfExists(f); }
                            catch (IOException ignored) { } });
                } catch (IOException ignored) { }
            }
        }
    }

    private record OrchestratorRun(boolean success,
                                   List<CompilerDiagnostic> diagnostics,
                                   String capturedOutput) {}

    /**
     * Runs the real {@link CompilationOrchestrator} with the
     * context-driven production constructor over the temp project
     * (ISSUE-0269): the published immutable {@link ProjectContext}
     * supplies the backend, the output root, the module roots, the
     * externals declarations, and the stdlib surface. Module discovery,
     * signature extraction, dependency ordering, name resolution, type
     * checking, and per-module JvmBackend codegen run unchanged.
     * Stdout/stderr is captured so per-test output stays clean.
     */
    private static OrchestratorRun runOrchestrator(Path entryFile,
            ProjectContext context, CompilerInvocation invocation) {
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        synchronized (CONSOLE_LOCK) {
            PrintStream originalOut = System.out;
            PrintStream originalErr = System.err;
            try {
                System.setOut(new PrintStream(captured, true,
                    StandardCharsets.UTF_8));
                System.setErr(new PrintStream(captured, true,
                    StandardCharsets.UTF_8));
                CompilationOrchestrator orchestrator =
                    new CompilationOrchestrator(
                        context,
                        entryFile.toAbsolutePath().normalize(),
                        false, false, false, false, null, invocation);
                boolean success = orchestrator.compile();
                return new OrchestratorRun(success,
                    orchestrator.diagnostics(),
                    captured.toString(StandardCharsets.UTF_8));
            } catch (IOException e) {
                return new OrchestratorRun(false, List.of(),
                    "orchestrator I/O failure: " + e + "\n"
                        + captured.toString(StandardCharsets.UTF_8));
            } finally {
                System.out.flush();
                System.err.flush();
                System.setOut(originalOut);
                System.setErr(originalErr);
            }
        }
    }

    /** The corpus-relative file stem (name without {@code .deal}). */
    private static String corpusStem(Path file) {
        String name = file.getFileName().toString();
        if (name.endsWith(".d.deal")) {
            return name.substring(0, name.length() - ".d.deal".length());
        }
        return name.substring(0, name.length() - ".deal".length());
    }

    /**
     * Copies the entry file and its transitive companion closure into
     * the flat temp project root. Relative imports resolve against each
     * file's own corpus directory (the same resolution the LuaJIT
     * conformance harness uses); stdlib and bare host imports stop the
     * walk (stdlib resolves through the production resolver, host
     * modules through deal.json externals).
     */
    private static void writeModuleFiles(Path projectRoot, Path entry,
            Map<String, Path> written) throws IOException {
        Files.createDirectories(projectRoot);
        copyTransitively(entry,
            entry.toAbsolutePath().normalize().getParent(),
            projectRoot, written);
    }

    /**
     * Copies the host-ABI binding declarations to
     * {@code bindings/<hostName>.d.deal} under the temp project root.
     * ISSUE-0272 D8 item 2b: producer-side seam — every binding is
     * written classification-header free (18 of 20
     * {@code test/conformance/host-fixtures/*.d.deal} carry
     * {@code // @expected: host-fixture} / {@code // @description:}
     * headers), so the production orchestrator never lexes a header
     * line. A missing host declaration fails loudly, exactly as the
     * inline copy this replaces did.
     */
    private static void copyHostBindings(Path projectRoot,
            Path hostFixturesRoot, Set<String> hostNames) throws IOException {
        for (String hostName : hostNames) {
            Path decl = hostFixturesRoot.resolve(hostName + ".d.deal");
            if (!Files.isRegularFile(decl)) {
                throw new IllegalStateException("host declaration "
                    + "missing for " + hostName);
            }
            String declRel = "bindings/" + hostName + ".d.deal";
            Files.createDirectories(projectRoot.resolve("bindings"));
            Files.writeString(projectRoot.resolve(declRel),
                ConformanceHarnessMetadata.stripClassificationHeaders(
                    Files.readString(decl)));
        }
    }

    /**
     * Test-only materialization pin support
     * ({@code ConformanceHarnessMetadataTest}): performs the exact
     * temp-project materialization {@link #runApplicable} performs for a
     * backend-runtime fixture — {@link #writeModuleFiles} (entry plus
     * transitive companions, including explicit-{@code .deal} alias
     * copies) and {@link #copyHostBindings} — and returns the project
     * root so the pin can assert that the three producer-side copy sites
     * left the orchestrator header-free sources. The caller owns the
     * returned tree.
     */
    static Path materializeProject(Path conformanceRoot, Path entry)
            throws IOException {
        Path projectRoot = Files.createTempDirectory("deal_jvm_seam_");
        boolean ok = false;
        try {
            Map<String, Path> written = new LinkedHashMap<>();
            writeModuleFiles(projectRoot, entry, written);
            Set<String> hostNames = hostImports(entry);
            if (!hostNames.isEmpty()) {
                copyHostBindings(projectRoot,
                    conformanceRoot.resolve("host-fixtures"), hostNames);
            }
            ok = true;
            return projectRoot;
        } finally {
            if (!ok) {
                try {
                    Files.walk(projectRoot).sorted(Comparator.reverseOrder())
                        .forEach(f -> { try { Files.deleteIfExists(f); }
                            catch (IOException ignored) { } });
                } catch (IOException ignored) { }
            }
        }
    }

    private static void copyTransitively(Path file, Path entryDir,
            Path projectRoot, Map<String, Path> written)
            throws IOException {
        Path normalized = file.toAbsolutePath().normalize();
        if (written.containsKey(normalized.toString())) return;
        // Companions inside the entry's own corpus directory keep their
        // subdirectory layout (the v1.2 identity carriage: two files in
        // one directory share the module identity, so nested
        // companions — like the modid isolation pair — need their
        // directories preserved to stay nominally distinct); every
        // other companion keeps the flat stem layout.
        Path target;
        try {
            Path rel = entryDir.relativize(normalized);
            if (rel.startsWith("..") || rel.getNameCount() <= 1) {
                target = projectRoot.resolve(corpusStem(normalized)
                    + ".deal");
            } else {
                target = projectRoot.resolve(rel);
            }
        } catch (IllegalArgumentException e) {
            target = projectRoot.resolve(corpusStem(normalized) + ".deal");
        }
        if (target.getParent() != null) {
            Files.createDirectories(target.getParent());
        }
        // ISSUE-0272 D8 item 2b: producer-side seam — the entry fixture
        // and every transitive companion are written classification-header
        // free, so the production orchestrator never lexes a header line.
        Files.writeString(target, ConformanceHarnessMetadata
            .stripClassificationHeaders(Files.readString(normalized)));
        written.put(normalized.toString(), target);
        for (String importPath : relativeImports(normalized)) {
            Path resolved = resolveCompanionPath(importPath,
                normalized.getParent());
            if (resolved == null) continue;
            // D4 (ISSUE-0176): an import spelling carrying an explicit
            // .deal extension resolves to its alias-named copy in the
            // flat project root, so materialize the companion under
            // that name before the recursive walk (the written-map
            // early return below must not suppress it).
            copyCompanionAliasIfExplicit(resolved, importPath, projectRoot);
            copyTransitively(resolved, entryDir, projectRoot, written);
        }
    }

    /**
     * Materializes a resolved companion under the production-resolved
     * alias name when the import spelling carries an explicit
     * {@code .deal} extension ({@code ./async_lib.deal} →
     * {@code async_lib.deal.deal}): {@link CompilationOrchestrator}
     * searches {@code basePath + ".deal"} first, and its
     * {@code isMatch} rule resolves the spelling to exactly that alias
     * file. The stem-named copy is still written by the recursive
     * walk; stem-only spellings need no alias and are untouched. The
     * copy is idempotent across repeated explicit spellings of the
     * same companion (the alias target already materialized is
     * skipped).
     */
    private static void copyCompanionAliasIfExplicit(Path resolved,
            String importPath, Path projectRoot) throws IOException {
        if (!importPath.endsWith(".deal")) return;
        String aliasBase = importPath;
        if (aliasBase.startsWith("./")) {
            aliasBase = aliasBase.substring("./".length());
        } else if (aliasBase.startsWith("../")) {
            aliasBase = aliasBase.substring("../".length());
        }
        Path aliasTarget = projectRoot.resolve(aliasBase + ".deal");
        if (Files.exists(aliasTarget)) return;
        // ISSUE-0272 D8 item 2b: producer-side seam — the explicit-.deal
        // alias copy is written classification-header free; the
        // written-map dedup/alias semantics are unchanged.
        Files.writeString(aliasTarget, ConformanceHarnessMetadata
            .stripClassificationHeaders(
                Files.readString(resolved.toAbsolutePath().normalize())));
    }

    /** Relative import paths ({@code ./} / {@code ../}) appearing in the
     * file's source, in order. */
    private static List<String> relativeImports(Path file)
            throws IOException {
        List<String> paths = new ArrayList<>();
        String source = Files.readString(file);
        for (String line : source.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("import ")) continue;
            int from = trimmed.indexOf(" from \"");
            if (from < 0) continue;
            int end = trimmed.indexOf('"', from + 7);
            if (end < 0) continue;
            String path = trimmed.substring(from + 7, end);
            if (path.startsWith("./") || path.startsWith("../")) {
                paths.add(path);
            }
        }
        return paths;
    }

    /** Bare host import names ({@code host/<name>}) appearing in the
     * file's source, in order (drives deal.json externals generation). */
    private static Set<String> hostImports(Path file) throws IOException {
        Set<String> hosts = new LinkedHashSet<>();
        String source = Files.readString(file);
        for (String line : source.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("import ")) continue;
            int from = trimmed.indexOf(" from \"");
            if (from < 0) continue;
            int end = trimmed.indexOf('"', from + 7);
            if (end < 0) continue;
            String path = trimmed.substring(from + 7, end);
            if (path.startsWith("host/")) {
                hosts.add(path.substring("host/".length()));
            }
        }
        return hosts;
    }

    /** Resolve a relative import path to a .deal/.d.deal file on disk
     * (the same resolution the LuaJIT conformance harness uses). */
    private static Path resolveCompanionPath(String importPath,
            Path baseDir) {
        if (!importPath.startsWith("./") && !importPath.startsWith("../")) {
            return null;
        }
        Path resolved = baseDir.resolve(importPath).normalize();
        if (Files.exists(resolved)) return resolved;
        Path withExt = baseDir.resolve(importPath + ".deal").normalize();
        if (Files.exists(withExt)) return withExt;
        Path withDeclExt = baseDir.resolve(importPath + ".d.deal").normalize();
        if (Files.exists(withDeclExt)) return withDeclExt;
        return null;
    }

    /**
     * Class name of the entry module's emitted artifact: the temp
     * project root is the single module root, so the entry's module
     * path is its file stem and {@link JvmBackend#classNameFor} derives
     * the class name the orchestrator used.
     */
    private static String entryClassName(String entryRel) {
        String path = entryRel;
        // ISSUE-0269: the entry key carries the configured-root prefix
        // (src/...); the orchestrator's module name is the root-relative
        // path, so the prefix is stripped before deriving the class name.
        if (path.startsWith("src/")) {
            path = path.substring("src/".length());
        }
        if (path.endsWith(".deal")) {
            path = path.substring(0, path.length() - ".deal".length());
        }
        return JvmBackend.classNameFor(
            path.replace('/', '.').replace('\\', '.'));
    }

    /**
     * Parses the entry module with the real lexer + parser for the runner
     * (its export list drives auto-invocation). Type checking is NOT
     * re-run here — the orchestrator already checked every module — so
     * this parse cannot act as a checker bypass.
     *
     * <p>ISSUE-0273 D8 item 2c/3: this runner parse is NOT an in-memory
     * seam site — its lexer reads the already-stripped temp copy produced
     * by the materialization seam, and its single parser construction
     * keeps the no-events parser form: the orchestrator already validated
     * the same bytes with full directive evaluation, and re-running
     * file-directive evaluation and binding here would duplicate that
     * work.</p>
     */
    private static ProgramNode parseEntryProgram(Path entryFile,
            SemanticProfile profile) throws IOException {
        String source = Files.readString(entryFile);
        LexResult lex = new Lexer(source, entryFile.toString()).tokenize();
        if (lex.hasErrors()) {
            throw new IllegalStateException("entry module lex errors: "
                + lex.diagnostics());
        }
        ParseResult parse = new Parser(lex.tokens(),
            entryFile.toString(), profile).parse();
        if (parse.hasErrors()) {
            throw new IllegalStateException("entry module parse errors: "
                + parse.diagnostics());
        }
        return parse.program();
    }

    // =========================================================================
    // Report
    // =========================================================================

    /** The on-disk backend-runtime denominator: every runtime-ok /
     * runtime-error (including known-fail) corpus test. The gate
     * measures the pass rate against this UNCHANGED denominator — skips
     * never shrink it. */
    private static int runtimeDenominator() throws IOException {
        int n = 0;
        try (var stream = Files.walk(
                conformanceRoot.resolve("backend-runtime"))) {
            for (Path p : (Iterable<Path>) stream
                    .filter(p -> p.toString().endsWith(".deal"))::iterator) {
                TestFile tf = parseMetadata(p);
                if (tf == null) continue;
                if (tf.expected().startsWith("runtime-ok")
                        || tf.expected().startsWith("runtime-error ")
                        || tf.expected().startsWith("known-fail runtime")) {
                    n++;
                }
            }
        }
        return n;
    }

    private static void printReport(List<Outcome> sorted, int runtimeTotal)
            throws IOException {
        System.out.println();
        System.out.println("=== JVM Conformance Summary (ISSUE-0102 origin — ISSUE-0168 capability accounting) ===");

        int ft = frontendTotal.get();
        int fp = frontendPassed.get();
        int ff = frontendFailed.get();
        System.out.printf("Frontend (backend-neutral compile-ok/"
            + "compile-error): total %d, passed %d, failed %d%n",
            ft, fp, ff);

        int at = applicableTotal.get();
        int ap = applicablePassed.get();
        int af = applicableFailed.get();
        int as = applicableSkipped.get();
        int kf = knownFailTracked.get();
        int denominator = runtimeDenominator();
        double pct = denominator == 0 ? 0.0
            : (ap * 100.0 / denominator);
        System.out.printf("Backend-runtime on JVM: denominator %d "
            + "(every on-disk runtime test, unchanged), passed %d, "
            + "failed %d, skipped %d (classified), known-fail %d "
            + "(tracked) — pass rate %.1f%%%n",
            denominator, ap, af, as, kf, pct);
        System.out.println("Profile-authority accounting: "
            + legacyAuthorityResults.get()
            + " legacy-authority fixture(s) (LEGACY_REGRESSION + "
            + "LEGACY_SAFE_INT — zero v1.2/promotion credit; "
            + legacyAuthorityPassed.get() + " passed, "
            + legacyAuthorityFailed.get() + " failed)");
        System.out.println();

        System.out.println("Skipped backend-runtime groups (every skip "
            + "carries a reason and a gap id):");
        List<String> gaps = new ArrayList<>(skipGroupCounts.keySet());
        Collections.sort(gaps);
        for (String gapId : gaps) {
            System.out.printf("  %-30s %-60s %d test(s)%n",
                gapId, FOLLOW_UP_GAPS.getOrDefault(gapId, ""),
                skipGroupCounts.get(gapId));
        }
        System.out.println();

        System.out.println("Known-fail groups (tracked follow-up issues):");
        List<String> kfIssues = new ArrayList<>(knownFailByIssue.keySet());
        Collections.sort(kfIssues);
        for (String issue : kfIssues) {
            System.out.printf("  %-11s %d test(s)%n", issue,
                knownFailByIssue.get(issue));
        }
        System.out.println();

        // Largest failing feature groups (review evidence).
        Map<String, Integer> failingGroups = new LinkedHashMap<>();
        for (Outcome o : sorted) {
            if (o.classified().kind() != Kind.APPLICABLE || o.pass()) {
                continue;
            }
            String[] segs = o.test().relativePath().split("/");
            String key = segs.length > 1
                && "backend-runtime".equals(segs[0]) ? segs[1] : segs[0];
            failingGroups.merge(key, 1, Integer::sum);
        }
        if (!failingGroups.isEmpty()) {
            System.out.println("Largest remaining failing feature groups:");
            List<Map.Entry<String, Integer>> entries =
                new ArrayList<>(failingGroups.entrySet());
            entries.sort((a, b) -> Integer.compare(b.getValue(),
                a.getValue()));
            for (Map.Entry<String, Integer> e : entries) {
                System.out.printf("  %-35s %d failing test(s)%n",
                    e.getKey(), e.getValue());
            }
            System.out.println();
        }

        // Gates.
        boolean ok = true;
        if (knownFailStale.get() > 0) {
            System.out.println("GATE FAILURE: " + knownFailStale.get()
                + " stale known-fail marker(s) — promote the fixture(s)");
            ok = false;
        }
        if (staleSkip.get() > 0) {
            System.out.println("GATE FAILURE: " + staleSkip.get()
                + " stale skip-registry entry (or entries) now pass on "
                + "JVM — promotion instruction: remove the "
                + "skip-registry entry (or entries)");
            ok = false;
        }
        if (probeHarnessFailed.get() > 0) {
            System.out.println("GATE FAILURE: probeHarnessFailed = "
                + probeHarnessFailed.get() + " — a runner exception "
                + "escaped a skip/known-fail probe (harness failure; a "
                + "probe crash is never silent evidence and never a "
                + "tracked skip)");
            ok = false;
        }
        if (ff > 0) {
            System.out.println("GATE FAILURE: " + ff
                + " frontend test(s) failed — 100% required");
            ok = false;
        }
        if (af > 0) {
            System.out.println("GATE FAILURE: " + af
                + " applicable backend-runtime test(s) failed — zero "
                + "applicable failures required");
            ok = false;
        }
        if (pct < 80.0) {
            System.out.println("GATE FAILURE: backend-runtime pass rate "
                + pct + "% below the 80% threshold (denominator "
                + denominator + ")");
            ok = false;
        }
        if (runtimeTotal != denominator) {
            System.out.println("GATE FAILURE: classified runtime total "
                + runtimeTotal + " differs from the on-disk denominator "
                + denominator);
            ok = false;
        }
        if (!ok) {
            System.exit(1);
        }
        System.out.println("Gates PASSED: frontend 100%; backend-runtime "
            + "zero applicable failures AND >= 80% pass rate over the "
            + "unchanged " + denominator
            + "-test denominator; zero unclassified skips; zero stale "
            + "skips; zero stale known-fail markers; zero probe runner "
            + "exceptions.");
    }
}
