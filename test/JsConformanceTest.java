package deal.test;

import deal.ast.*;
import deal.checker.*;
import deal.codegen.Backend;
import deal.diagnostics.CompilerDiagnostic;
import deal.lexer.*;
import deal.module.CompilationOrchestrator;
import deal.module.ExportExtractor;
import deal.module.ModuleShapeValidator;
import deal.module.StdlibModuleResolver;
import deal.parser.*;
import deal.semantic.CompilerInvocation;
import deal.semantic.CompilerProfileProvider;
import deal.semantic.ReleaseConfiguration;
import deal.semantic.ir.ReleaseState;
import deal.semantic.ir.SemanticProfile;
import deal.types.Type;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * JavaScript corpus conformance gate (ISSUE-0278,
 * js-v12-completion-architecture D5).
 *
 * <p>Runs every existing backend-runtime conformance test
 * ({@code test/conformance/backend-runtime/}) against the JavaScript
 * backend with a deterministic classification policy and a real
 * whole-project pipeline: module discovery → parsing → signature
 * extraction → dependency ordering → name resolution → type checking →
 * per-module {@link deal.codegen.js.JsBackend} codegen (via
 * {@link CompilationOrchestrator} with {@link Backend#JS}) →
 * {@code node} execution of the emitted artifacts. A bypassed
 * parser/checker/module-discovery yields no orchestrator success; a
 * bypassed codegen leaves no {@code .js} artifact (asserted before node
 * runs); a bypassed node execution produces no output and no exit code
 * (asserted against the captured subprocess output).
 *
 * <h2>The lane-wide activated invocation (ISSUE-0536 remediation)</h2>
 *
 * <p>Every on-disk backend-runtime fixture compiles through the single
 * lane-wide activated invocation {@link #LANE_INVOCATION} — the
 * explicit {@code COMMON_SHADOW + DEAL_V1_2_INT32} invocation the
 * {@link LegacyProfileRegressionCatalog} A5 seam resolves for every
 * uncatalogued fixture (the invocation the LuaJIT lane resolves for
 * the shared stdlib-edge time fixture) — passed through the
 * {@link CompilationOrchestrator} constructor in
 * {@link #runOrchestrator}. Under that invocation the emitted entry
 * module calls {@code $rt.setInt32Mode(true)} immediately after the
 * runtime {@code $require}, so {@code deal/runtime.js} gates
 * {@code checkInt} at the signed-32 boundary and the retained
 * {@code std/time.nowMillis} {@code ()->int} route raises E8004 for
 * contemporary epoch milliseconds: the flipped shared fixture
 * {@code backend-runtime/stdlib-edge/time-now-millis-positive.deal}
 * passes as {@code runtime-error E8004} on this gate, and the gate
 * validity condition {@code expectation(fixture) == landed
 * std/time.js behavior} (js-v12-completion-architecture D5) holds by
 * construction. The gate is launched by {@code run_tests.sh} on every
 * gate run; the legacy safe-int default mode of the retained JS
 * runtime stays the unselected direct-caller mode
 * ({@code test_stdlib_js.js} keeps running the legacy range and stays
 * green unchanged).</p>
 *
 * <h2>Classification policy (deterministic, documented)</h2>
 *
 * <ol>
 *   <li><b>Frontend-classified files</b> ({@code compile-ok} /
 *       {@code compile-error CODE} anywhere under the corpus): run the
 *       shared frontend pipeline (lexer → parser → name resolver → type
 *       checker) and must pass 100% — they are backend-neutral and never
 *       node-executed and never counted in the node-executed runtime
 *       denominator (the same classification the LuaJIT harness
 *       applies).</li>
 *   <li><b>Companions</b> ({@code @expected: companion}): classified
 *       support modules (the {@code *_lib} fixtures plus the two
 *       {@code .d.deal} declaration companions) that carry no entry
 *       {@code main} and are never node-executed standalone. Their
 *       standalone compilation is enforced here through the shared
 *       frontend/declaration gate (the {@code deal.test.ConformanceTest}
 *       companion path: lexer/parser plus the declaration-file pipeline
 *       {@link ExportExtractor} for {@code .d.deal} companions, the
 *       module shape gate plus name resolution and type checking for
 *       {@code .deal} companions); a companion failure fails the run.
 *       Each companion compiles as part of the transitive module graph
 *       of every classified fixture that imports it, through that
 *       importer's real frontend → CompilationOrchestrator → JsBackend
 *       temp-project run, and is loaded by node at the importer's
 *       execution — the runner materializes the transitive companion
 *       closure into every importing fixture's temp project and the
 *       report gates the materialized set against the on-disk import
 *       graph. Companions are accounted separately: the printed
 *       companion count must equal the on-disk
 *       {@code @expected: companion} count, and a companion classified
 *       as anything else fails the run.</li>
 *   <li><b>Known-fail</b> ({@code @expected: known-fail MODE}): the
 *       intentionally unsupported v1.2 cases tracked by their
 *       {@code @issue} (ISSUE-0111 signed int32 / bytes). The runner
 *       executes the underlying runtime mode through the real JS
 *       pipeline every run and records a non-fatal tracked KNOWN-FAIL
 *       while the case still fails; when it starts passing, the gate
 *       FAILS with a promotion instruction naming the fixture (drop the
 *       marker and set the real {@code @expected}) — promotion is
 *       forced.</li>
 *   <li><b>Backend-runtime tests</b> ({@code runtime-ok} /
 *       {@code runtime-error CODE}): JS-applicable and must pass through
 *       the whole pipeline. There is no skip registry and no fallback
 *       skip branch: an {@code @expected} value the classifier cannot
 *       place is a classification failure, and {@code SKIPPED} is
 *       unclassifiable by construction.</li>
 * </ol>
 *
 * <h2>The HOST_JS harness (js-v12-host-abi-completion D6)</h2>
 *
 * <p>One CommonJS implementation per host fixture name (derived from the
 * raw module path — {@code host/bad_string} → artifact
 * {@code host/bad_string.js}), each implementing the declared surface
 * with the JS-mapped carriers (JS Arrays, DEAL wrapper objects for
 * function parameters, validated host-class instances) and the pinned
 * outputs ({@code split} → {@code ["a","b","c"]}, {@code join} →
 * concatenation, {@code apply} → {@code f.$f(x, ...)},
 * {@code describe} → {@code endpoint.path}/{@code port} host-side
 * reads). The runner validates the {@link #HOST_JS} map against the
 * on-disk {@code test/conformance/host-fixtures/} subtree — a missing
 * or wrong-shaped implementation is a harness failure, never a pass —
 * and deploys each implementation to
 * {@code <outputRoot>/<raw specifier>.js} with the generated
 * {@code deal.json} externals wiring. Each map entry is the byte-exact
 * mirror of the authoritative on-disk
 * {@code test/conformance/host-fixtures/<name>.js} per-backend
 * implementation landed by ISSUE-0352; startup validation requires the
 * declaration/{@code .lua}/{@code .java}/{@code .js} name sets and the
 * map/on-disk contents to match, so harness/corpus drift fails the run
 * as a harness failure, never a pass.</p>
 *
 * <h2>Gates</h2>
 * <ul>
 *   <li>frontend-classified files: 100% pass (zero failed);</li>
 *   <li>backend-runtime on node: zero applicable failures AND 100% of
 *       the node-executed runtime denominator (every on-disk
 *       runtime-ok/runtime-error test plus every known-fail probe)
 *       executed through node;</li>
 *   <li>zero skipped (by construction — the classifier has no skip
 *       registry and no fallback skip branch);</li>
 *   <li>zero stale known-fail markers (promotion is forced and the
 *       promotion instruction names the fixture);</li>
 *   <li>every companion standalone-compiles, the classified companion
 *       count equals the on-disk {@code @expected: companion} count, no
 *       companion is dead (every companion is imported by at least one
 *       classified fixture), and the companions materialized into
 *       importing fixtures' temp projects equal the companions
 *       reachable from runtime-classified fixtures;</li>
 *   <li>zero probe runner exceptions (a probe crash is never silent
 *       evidence);</li>
 *   <li>the classified total — FRONTEND + APPLICABLE + KNOWN_FAIL +
 *       COMPANION — equals the on-disk backend-runtime denominator, and
 *       the classified runtime total equals the on-disk runtime
 *       denominator;</li>
 *   <li>node absence is a hard run failure, never a skip;</li>
 *   <li>the runner exits non-zero when any gate fails.</li>
 * </ul>
 */
public class JsConformanceTest {

    // =========================================================================
    // The lane-wide activated invocation (ISSUE-0536 remediation)
    // =========================================================================

    /**
     * The single lane-wide activated invocation: every on-disk
     * backend-runtime fixture compiles through this exact invocation
     * via the {@link CompilationOrchestrator} constructor in
     * {@link #runOrchestrator}. The {@code COMMON_SHADOW +
     * DEAL_V1_2_INT32} invocation is the invocation the
     * {@link LegacyProfileRegressionCatalog} A5 seam resolves for
     * every uncatalogued fixture — the activated profile the LuaJIT
     * lane resolves for the shared stdlib-edge time fixture — so the
     * flipped fixture passes as {@code runtime-error E8004} on this
     * gate and the gate validity condition
     * {@code expectation(fixture) == landed std/time.js behavior}
     * (js-v12-completion-architecture D5) holds by construction. The
     * unselected direct-caller default mode of the retained JS
     * runtime stays the legacy range ({@code test_stdlib_js.js} runs
     * unselected and stays green unchanged).
     */
    private static final CompilerInvocation LANE_INVOCATION =
        CompilerProfileProvider.resolveCommonShadow(
            SemanticProfile.DEAL_V1_2_INT32, ReleaseState.PRE_ACTIVATION,
            ReleaseConfiguration.releaseCapabilityRegistry());

    /** The lane-wide activated invocation, exposed for pin tests to
     * assert field-exactly. */
    static CompilerInvocation laneInvocation() {
        return LANE_INVOCATION;
    }

    // =========================================================================
    // Host harness: one CommonJS implementation per host fixture
    // =========================================================================

    /**
     * The complete JS host implementation map: host fixture name (the
     * raw module path minus the {@code host/} prefix, e.g.
     * {@code bad_string} for {@code host/bad_string}) → CommonJS source.
     * Every entry must name an on-disk
     * {@code test/conformance/host-fixtures/<name>.d.deal} declaration
     * (validated at startup; a dead entry or a missing implementation is
     * a harness failure, never a pass). Each entry is the byte-exact
     * mirror of the authoritative on-disk
     * {@code test/conformance/host-fixtures/<name>.js} triplet
     * implementation landed by ISSUE-0352 — startup validation fails
     * the run on any drift between the map and the corpus. Each
     * implementation realizes the
     * declared surface with the JS-mapped carriers and the pinned
     * outputs (js-v12-host-abi-completion D6): JS Arrays for array
     * parameters/returns, DEAL wrapper objects invoked through
     * {@code .$f} for function parameters, validated host-class
     * instances read field-wise host-side, the DEAL {@code null} as the
     * null result.
     */
    private static final Map<String, String> HOST_JS = Map.ofEntries(
        Map.entry("array_return", """
"use strict";

// Host fixture implementation for the host-array-return-ok conformance
// test: a plain JS Array of strings satisfies the declared string[]
// return (the boundary wrapper checks the carrier and its elements).
module.exports = {
  split: function (s) {
    return ["a", "b", "c"];
  },
};
"""),
        Map.entry("async_bad", """
"use strict";

// Host fixture implementation for the host-async-bad conformance test:
// the returned value is a valid backend async operation (a thenable),
// but its completion value 42 violates the declared async return type
// string — the await-site completion check must raise E8001.
module.exports = {
  fetchValue: function () {
    return Promise.resolve(42);
  },
};
"""),
        Map.entry("async_ok", """
"use strict";

// Host fixture implementation for the host-async-ok conformance test:
// a real backend async operation (a thenable) whose completion value
// satisfies the declared async return type string.
module.exports = {
  fetchValue: function () {
    return Promise.resolve("fetched");
  },
};
"""),
        Map.entry("async_shape_bad", """
"use strict";

// Host fixture implementation for the host-async-shape-bad conformance
// test: the declared export is an async function, so the host must
// return a backend async operation (a thenable); the plain number below
// must be rejected at the call site with E8010 (async-operation shape),
// before the await-site completion check ever runs.
module.exports = {
  fetchValue: function () {
    return 42;
  },
};
"""),
        Map.entry("bad_return", """
"use strict";

// Host fixture implementation for the host-bad-return conformance test:
// a junk return value for the declared int return type — the wrapper
// must raise E8010 at the call site.
module.exports = {
  getNumber: function () {
    return "not a number";
  },
};
"""),
        Map.entry("bad_string", """
"use strict";

// Host-module fixture (host-module-abi D6): a host surface whose
// returned strings violate DEAL v1.2 string encoding rules. JS strings
// are UTF-16, so lone surrogate code units are the JS analog of the Lua
// host's malformed UTF-8 bytes — the declared (string) boundary must
// reject both returns with E8010.
module.exports = {
  badString: function () {
    return "a\\uD800b";
  },

  surrogateString: function () {
    return "\\uD800";
  },
};
"""),
        Map.entry("boundary_apply", """
"use strict";

// Host fixture implementation for the host-boundary-apply-function
// conformance test. The function-typed parameter arrives as the DEAL
// wrapper ({ $kind: "function", $sig, $f }); the host invokes it
// through .$f (DEAL→host adaptation, js-v12-host-abi-completion D2/D6).
module.exports = {
  apply: function (f, v) {
    return f.$f(v) + 100;
  },
};
"""),
        Map.entry("cfg", """
"use strict";

// Host fixture implementation for the host-class-export conformance
// test (host-module-abi D6 + runtime-class-identity D1-D2). Each
// declared class export carries its canonical externals identity META and
// a <C>_defaults table (construction depends on both). Absent optional
// fields are marked MISSING by the loader from the declared field
// metadata, so the defaults tables carry only the defaulted values.
module.exports = {
  Endpoint: {
    $kind: "class",
    $classname: "@$external/host/cfg/Endpoint",
  },

  Endpoint_defaults: {
    path: "/",
  },

  ServerConfig: {
    $kind: "class",
    $classname: "@$external/host/cfg/ServerConfig",
  },

  ServerConfig_defaults: {
    port: 8080,
  },

  describe: function (s) {
    return s.endpoint.path + ":" + s.port;
  },
};
"""),
        Map.entry("empty_return", """
"use strict";

// Host fixture implementation for the host-empty-return-bad conformance
// test: zero results (undefined) for the declared int return — the
// wrapper's presence rule must raise E8010 on every call path,
// including the discard path.
module.exports = {
  ping: function () {
  },
};
"""),
        Map.entry("extra_export", """
"use strict";

// Host fixture implementation for the host-extra-export-ignored
// conformance test (host-module-abi D6). The implementation supplies
// more exports than the declared surface (ping): extra and helper are
// dropped structurally by the loader and the load must succeed (R4).
module.exports = {
  ping: function () {
    return "pong";
  },

  extra: 42,

  helper: function () {
    return "unreachable";
  },
};
"""),
        Map.entry("missing_export", """
"use strict";

// Host fixture implementation for the host-missing-export conformance
// test. The declared surface names "missing", which this implementation
// omits: the loader must raise E8011 at load time, before any exported
// function auto-invocation (R3).
module.exports = {
  ping: function () {
    return "pong";
  },
};
"""),
        Map.entry("nullable_fn", """
"use strict";

// Host fixture implementation for the host-nullable-function-param and
// host-nullable-function-param-bad conformance tests. The declared
// parameter is ?((x: int) => int): the wrapper accepts the DEAL null
// (JS null here) passed through unadapted and matching-sig DEAL
// functions (delivered as their { $kind: "function", $sig, $f }
// wrappers, invoked through .$f), and raises E8010 for anything else.
module.exports = {
  register: function (cb) {
    if (cb === null || cb === undefined) {
      return 0;
    }
    return cb.$f(41);
  },
};
"""),
        Map.entry("nullable_fn_return", """
"use strict";

// Host fixture implementation for the host-nullable-function-return
// conformance tests. The declared return descriptor is ?((x: int) =>
// int): JS null is the null result, and a raw JS function value fails
// the function-typed return check — function-typed return wrapping is
// excluded (assumption c), so the wrapper must raise E8010 for the
// "bad" path.
module.exports = {
  getCallback: function (mode) {
    if (mode === "bad") {
      return function (x) {
        return x;
      };
    }
    return null;
  },
};
"""),
        Map.entry("nullable_return", """
"use strict";

// Host fixture implementation for the host-nullable-return-ok/bad
// conformance tests. JS null is the null result; an int return
// exercises the wrong-representation E8010 path.
module.exports = {
  find: function (s) {
    if (s === "__NULL__") {
      return null;
    }
    if (s === "__BAD__") {
      return 42;
    }
    return s;
  },
};
"""),
        Map.entry("nullreturn_bad", """
"use strict";

// Host fixture implementation for the host-null-return-bad conformance
// test: a junk return for the sync ->null declared return — the
// wrapper's sync-null check must raise E8010, including on the discard
// call path.
module.exports = {
  ping: function () {
    return "junk";
  },
};
"""),
        Map.entry("nullreturn_ok", """
"use strict";

// Host fixture implementation for the host-null-return-ok conformance
// test. JS null is the DEAL null sentinel for a sync function declared
// ->null — the wrapper's sync-null check accepts it and rejects any
// non-sentinel value (plain undefined included).
module.exports = {
  ping: function () {
    return null;
  },
};
"""),
        Map.entry("planprobe", """
"use strict";

// Host fixture implementation for the ISSUE-0340 default-plan fixtures
// (Node lane). nextValue() increments a module counter and returns the
// count * 10; valueCount() reads it — the Node host-slice mirror of the
// Lua host fixture's mutable state.
let n = 0;

module.exports = {
  nextValue: function () {
    n = n + 1;
    return n * 10;
  },
  valueCount: function () {
    return n;
  },
};
"""),
        Map.entry("presence", """
"use strict";

// Host fixture implementation for the host-export-presence conformance
// test (host-module-abi D6). The declared class export carries the
// canonical externals identity descriptor (@$external/host/presence/Config) and a
// <C>_defaults table — runtime construction through the synthesized
// class symbol depends on both. Absent optional fields are marked
// MISSING by the loader from the declared field metadata.
module.exports = {
  ping: function () {
    return "pong";
  },

  Config: {
    $kind: "class",
    $classname: "@$external/host/presence/Config",
  },

  Config_defaults: {
    port: 8080,
  },
};
"""),
        Map.entry("prewrapped_bad", """
"use strict";

// Host fixture implementation for the host-prewrapped-bad conformance
// test. The pre-wrapped sig-table form is a LuaJIT host-loader
// mechanism; the JS host realizes the declared-shape contract (the
// declared descriptor is the only trusted metadata): ping returns junk
// for the declared ->null return, so the wrapper's sync-null check must
// raise E8010, including the discard call path.
module.exports = {
  ping: function () {
    return "junk";
  },
};
"""),
        Map.entry("prewrapped_ok", """
"use strict";

// Host fixture implementation for the host-prewrapped-ok conformance
// test. The pre-wrapped sig-table form is a LuaJIT host-loader
// mechanism; the JS host realizes the declared-shape contract (the
// declared descriptor is the only trusted metadata): greet returns the
// declared string and ping's sync ->null return is the DEAL null, both
// enforced through the declared-descriptor wrapper.
module.exports = {
  greet: function (name) {
    return "hello " + name;
  },

  ping: function () {
    return null;
  },
};
"""),
        Map.entry("rest_join", """
"use strict";

// Host fixture implementation for the host-rest-ok/host-rest-bad
// conformance tests: the v1.2 fixed-array parameter form (DEAL v1.2
// removed rest parameters — the join export takes a fixed array
// parameter, never JS rest syntax). The boundary wrapper checks the
// array against the declared string[] element type.
module.exports = {
  join: function (sep, parts) {
    return parts.join(sep);
  },
};
""")
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
        /** runtime-ok / runtime-error — JS-applicable backend test. */
        APPLICABLE,
        /** known-fail MODE — tracked follow-up issue; probed through the
         *  real pipeline every run + stale-gated. */
        KNOWN_FAIL,
        /** @expected: companion — classified support module;
         *  standalone-compiled, never node-executed standalone. */
        COMPANION
    }

    private record Classified(TestFile test, Kind kind, String expectedCode) {}

    private record Outcome(TestFile test, Classified classified,
            boolean pass, String message) {}

    private record OrchestratorRun(boolean success,
                                   List<CompilerDiagnostic> diagnostics,
                                   String capturedOutput) {}

    private record NodeResult(int exitCode, String stdout, String stderr) {}

    /** A harness-defect failure (missing host declaration/implementation,
     * a manifest the harness generated that does not load): never a
     * fixture pass, never an applicable failure. */
    private static final class HarnessFailure extends RuntimeException {
        HarnessFailure(String message) {
            super(message);
        }
    }

    private static final AtomicInteger frontendTotal = new AtomicInteger();
    private static final AtomicInteger frontendPassed = new AtomicInteger();
    private static final AtomicInteger frontendFailed = new AtomicInteger();
    private static final AtomicInteger applicableTotal = new AtomicInteger();
    private static final AtomicInteger applicablePassed = new AtomicInteger();
    private static final AtomicInteger applicableFailed = new AtomicInteger();
    private static final AtomicInteger knownFailTotal = new AtomicInteger();
    private static final AtomicInteger knownFailTracked = new AtomicInteger();
    private static final AtomicInteger knownFailStale = new AtomicInteger();
    private static final AtomicInteger companionTotal = new AtomicInteger();
    private static final AtomicInteger companionPassed = new AtomicInteger();
    private static final AtomicInteger companionFailed = new AtomicInteger();
    private static final AtomicInteger probeHarnessFailed =
        new AtomicInteger();
    private static final AtomicInteger nodeExecuted = new AtomicInteger();

    private static final List<Outcome> outcomes =
        Collections.synchronizedList(new ArrayList<>());
    private static final Map<String, Integer> knownFailByIssue =
        Collections.synchronizedMap(new LinkedHashMap<>());
    /** Corpus-relative paths of every file materialized into an
     * importing fixture's temp project (companions included). */
    private static final Set<String> materializedCorpusFiles =
        Collections.synchronizedSet(new LinkedHashSet<>());

    private static final ThreadLocal<StringBuilder> WORKER_OUTPUT =
        new ThreadLocal<>();
    private static final Object CONSOLE_LOCK = new Object();

    private static Path conformanceRoot = Path.of("test/conformance/")
        .toAbsolutePath().normalize();
    private static Path hostFixturesRoot =
        conformanceRoot.resolve("host-fixtures");
    private static final Path REPO_ROOT = Path.of("")
        .toAbsolutePath().normalize();

    private static final long SUBPROCESS_TIMEOUT_SECONDS = 120;

    /** Companion accounting expectations, computed before the pool runs:
     * every companion corpus-relative path, the companions reachable
     * (transitively) from runtime-classified fixtures, and the
     * companions reachable from any classified fixture. */
    private static Set<String> companionPaths = Set.of();
    private static Set<String> reachableFromRuntime = Set.of();
    private static Set<String> importedByAny = Set.of();
    private static int onDiskTotal;
    private static int onDiskCompanionCount;
    private static int onDiskRuntimeDenominator;

    private static final Pattern EXPORTED_MAIN = Pattern.compile(
        "export\\s+function\\s+main\\s*\\(");

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
            System.out.println("No conformance root found at "
                + conformanceRoot);
            System.exit(2);
        }

        // Node absence is a hard run failure, never a skip: the
        // node-executed denominator can only be satisfied by real node
        // subprocess executions.
        if (!probeNode()) {
            System.out.println("GATE FAILURE: node is not available — the "
                + "JS corpus gate requires the environment's Node.js; a "
                + "bypassed node execution is never a pass");
            System.exit(1);
        }

        try {
            // Harness integrity first: the HOST_JS map must match the
            // on-disk host-fixtures subtree exactly (a missing or
            // wrong-shaped implementation is a harness failure, never a
            // pass).
            validateHostHarness();

            List<TestFile> tests = discoverTests();
            List<Classified> classified = classifyAll(tests);
            computeCompanionAccounting(tests, classified);

            int frontend = (int) classified.stream()
                .filter(c -> c.kind() == Kind.FRONTEND).count();
            int applicable = (int) classified.stream()
                .filter(c -> c.kind() == Kind.APPLICABLE).count();
            int knownFail = (int) classified.stream()
                .filter(c -> c.kind() == Kind.KNOWN_FAIL).count();
            int companions = (int) classified.stream()
                .filter(c -> c.kind() == Kind.COMPANION).count();
            System.out.println("=== DEAL v1.2 JavaScript Corpus "
                + "Conformance (ISSUE-0278 — JsConformanceTest) ===");
            System.out.println("Root: " + conformanceRoot);
            System.out.println("Node: available (a missing node is a hard "
                + "run failure, never a skip)");
            System.out.println("Discovered " + tests.size()
                + " backend-runtime conformance test(s): " + frontend
                + " frontend-classified, " + applicable
                + " JS-applicable backend-runtime, " + knownFail
                + " known-fail (tracked), " + companions
                + " companions (classified support modules)");
            System.out.println();

            // Deterministic execution order: sorted by corpus-relative
            // path.
            List<Classified> ordered = new ArrayList<>(classified);
            ordered.sort(Comparator.comparing(
                c -> c.test().relativePath()));

            int workers = Math.max(1,
                Math.min(Runtime.getRuntime().availableProcessors(),
                    ordered.size()));
            ExecutorService pool = Executors.newFixedThreadPool(workers);
            try {
                List<Future<?>> futures = new ArrayList<>();
                for (Classified item : ordered) {
                    futures.add(pool.submit(() -> runOne(item)));
                }
                for (Future<?> future : futures) {
                    future.get();
                }
            } finally {
                pool.shutdownNow();
            }

            List<Outcome> sorted = new ArrayList<>(outcomes);
            sorted.sort(Comparator.comparing(
                o -> o.test().relativePath()));
            printReport(sorted);
        } catch (RuntimeException e) {
            // Classification failures (unknown @expected, a missing
            // @expected, a mis-classified companion, a dead HOST_JS
            // entry) fail the run — there is no skip branch.
            System.out.println("FAIL (classification failure): "
                + e.getMessage());
            System.exit(1);
        }
    }

    /** Probes that {@code node} is invocable and functional (the same
     * hard gate the JS e2e runner uses). */
    private static boolean probeNode() {
        try {
            Process probe = new ProcessBuilder("node", "--version")
                .redirectErrorStream(true).start();
            return probe.waitFor(30, TimeUnit.SECONDS)
                && probe.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    // =========================================================================
    // Host harness validation (js-v12-host-abi-completion D6)
    // =========================================================================

    /**
     * Validates the {@link #HOST_JS} map against the on-disk
     * {@code host-fixtures} subtree (the ISSUE-0352 per-backend
     * triplet layout): the declaration names ({@code *.d.deal}) and
     * the per-backend implementation names ({@code *.lua},
     * {@code *.java}, {@code *.js}) must name the same set, every
     * on-disk host fixture must have a HOST_JS implementation, every
     * HOST_JS entry must name an on-disk declaration (no dead
     * entries), and every implementation must be a well-shaped
     * CommonJS module ({@code module.exports}) that is byte-identical
     * to the authoritative on-disk {@code <name>.js} host
     * implementation (the map is the runner's mirror of the corpus
     * implementation; drift is a wrong-shaped harness). Any violation
     * is a harness failure that fails the run — never a skip and
     * never a pass.
     */
    private static void validateHostHarness() throws IOException {
        if (!Files.isDirectory(hostFixturesRoot)) {
            System.out.println("GATE FAILURE: host-fixtures directory "
                + "missing at " + hostFixturesRoot + " — the HOST_JS "
                + "harness cannot be validated");
            System.exit(1);
        }
        List<String> declNames = new ArrayList<>();
        List<String> luaNames = new ArrayList<>();
        List<String> javaNames = new ArrayList<>();
        List<String> jsNames = new ArrayList<>();
        try (var stream = Files.list(hostFixturesRoot)) {
            for (Path p : (Iterable<Path>) stream.sorted()::iterator) {
                String name = p.getFileName().toString();
                if (name.endsWith(".d.deal")) {
                    declNames.add(name.substring(0,
                        name.length() - ".d.deal".length()));
                } else if (name.endsWith(".lua")) {
                    luaNames.add(name.substring(0,
                        name.length() - ".lua".length()));
                } else if (name.endsWith(".java")) {
                    javaNames.add(name.substring(0,
                        name.length() - ".java".length()));
                } else if (name.endsWith(".js")) {
                    jsNames.add(name.substring(0,
                        name.length() - ".js".length()));
                }
            }
        }
        Collections.sort(declNames);
        Collections.sort(luaNames);
        Collections.sort(javaNames);
        Collections.sort(jsNames);

        boolean ok = true;
        // Triplet layout completeness: the declarations and all three
        // per-backend implementation sets must name the same fixtures.
        if (!declNames.equals(luaNames) || !declNames.equals(javaNames)
                || !declNames.equals(jsNames)) {
            System.out.println("GATE FAILURE: host-fixtures triplet "
                + "layout mismatch — .d.deal names " + declNames
                + " vs .lua names " + luaNames + " vs .java names "
                + javaNames + " vs .js names " + jsNames
                + " (harness failure; a gap is never a pass)");
            ok = false;
        }
        for (String name : declNames) {
            String source = HOST_JS.get(name);
            if (source == null || source.isBlank()
                    || !source.contains("module.exports")) {
                System.out.println("GATE FAILURE: the HOST_JS "
                    + "implementation for host fixture '" + name
                    + "' is missing or not a well-shaped CommonJS module"
                    + " (module.exports) — a missing or wrong-shaped "
                    + "implementation is a harness failure, never a pass");
                ok = false;
                continue;
            }
            Path onDiskJs = hostFixturesRoot.resolve(name + ".js");
            if (!Files.isRegularFile(onDiskJs)) {
                System.out.println("GATE FAILURE: the on-disk host "
                    + "implementation " + onDiskJs + " is missing — the "
                    + "HOST_JS map must mirror the on-disk "
                    + "host-fixtures subtree (harness failure, never a "
                    + "pass)");
                ok = false;
                continue;
            }
            String onDisk = Files.readString(onDiskJs);
            if (!onDisk.contains("module.exports")) {
                System.out.println("GATE FAILURE: the on-disk host "
                    + "implementation " + onDiskJs + " is not a "
                    + "well-shaped CommonJS module (missing "
                    + "module.exports) — a wrong-shaped implementation "
                    + "is a harness failure, never a pass");
                ok = false;
                continue;
            }
            if (!source.equals(onDisk)) {
                System.out.println("GATE FAILURE: the HOST_JS "
                    + "implementation for '" + name + "' differs from "
                    + "the on-disk host implementation " + onDiskJs
                    + " — the map is the runner's byte-exact mirror of "
                    + "the corpus implementation; drift is a "
                    + "wrong-shaped harness (never a pass)");
                ok = false;
            }
        }
        List<String> mapNames = new ArrayList<>(HOST_JS.keySet());
        Collections.sort(mapNames);
        for (String name : mapNames) {
            if (!declNames.contains(name)) {
                System.out.println("GATE FAILURE: HOST_JS entry '" + name
                    + "' names no on-disk host-fixtures declaration — a "
                    + "dead harness entry fails the run");
                ok = false;
            }
        }
        if (!ok) {
            System.exit(1);
        }
        System.out.println("Host harness: " + declNames.size()
            + " host-fixture declaration(s) validated against "
            + declNames.size() + " HOST_JS implementation(s) (byte-exact"
            + " mirrors of the on-disk .js implementations)");
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

            Path normalized = file.toAbsolutePath().normalize();
            Path rel = conformanceRoot.relativize(normalized);
            String relPath = rel.toString().replace('\\', '/');
            return new TestFile(normalized, relPath, spec, description,
                expected, features, issue);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
    }

    /**
     * The deterministic classification policy. See the class javadoc.
     * There is no skip registry and no fallback skip branch: an unknown
     * {@code @expected} value, a missing {@code @expected}, a
     * non-runtime known-fail mode, a known-fail without its
     * {@code @issue}, or a companion that exports {@code main} is a
     * classification failure that fails the run. {@code SKIPPED} is
     * unclassifiable by construction.
     */
    private static List<Classified> classifyAll(List<TestFile> tests) {
        List<Classified> result = new ArrayList<>();
        for (TestFile test : tests) {
            String expected = test.expected();
            if (expected.equals("companion")) {
                // Classified support modules carry no entry main and are
                // never node-executed standalone.
                if (exportsMain(test.path())) {
                    throw new IllegalStateException("companion "
                        + test.relativePath() + " exports main — "
                        + "companions are classified support modules that "
                        + "are never node-executed standalone");
                }
                result.add(new Classified(test, Kind.COMPANION, ""));
            } else if (expected.startsWith("known-fail ")) {
                String mode = expected.substring("known-fail ".length())
                    .trim();
                if (!mode.startsWith("runtime-")) {
                    throw new IllegalStateException("backend-runtime "
                        + "known-fail '" + expected + "' in "
                        + test.relativePath() + " is not runtime-classified"
                        + " — the node-executed gate only tracks runtime "
                        + "modes");
                }
                String code = mode.startsWith("runtime-error ")
                    ? mode.substring("runtime-error ".length()).trim() : "";
                if (test.issue().isEmpty()) {
                    throw new IllegalStateException("known-fail without "
                        + "@issue in " + test.relativePath());
                }
                result.add(new Classified(test, Kind.KNOWN_FAIL, code));
            } else if (expected.startsWith("compile-ok")) {
                result.add(new Classified(test, Kind.FRONTEND, ""));
            } else if (expected.startsWith("compile-error ")) {
                result.add(new Classified(test, Kind.FRONTEND,
                    expected.substring("compile-error ".length()).trim()));
            } else if (expected.startsWith("runtime-ok")) {
                result.add(new Classified(test, Kind.APPLICABLE, ""));
            } else if (expected.startsWith("runtime-error ")) {
                result.add(new Classified(test, Kind.APPLICABLE,
                    expected.substring("runtime-error ".length()).trim()));
            } else {
                // Unknown @expected is a harness failure, never a skip.
                throw new IllegalStateException("unknown @expected '"
                    + expected + "' in " + test.relativePath());
            }
        }
        return result;
    }

    /** True when the file declares an exported {@code main} function. */
    private static boolean exportsMain(Path file) {
        try {
            String source = Files.readString(file);
            return EXPORTED_MAIN.matcher(source).find();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
    }

    /**
     * Computes the companion accounting expectations over the on-disk
     * corpus before the worker pool runs: the denominator totals, the
     * companion path set, the companions reachable (transitively) from
     * runtime-classified fixtures (APPLICABLE + KNOWN_FAIL), and the
     * companions reachable from any classified non-companion fixture
     * (the dead-companion check).
     */
    private static void computeCompanionAccounting(List<TestFile> tests,
            List<Classified> classified) {
        onDiskTotal = tests.size();
        onDiskCompanionCount = (int) tests.stream()
            .filter(t -> t.expected().equals("companion")).count();
        onDiskRuntimeDenominator = (int) tests.stream()
            .filter(t -> t.expected().startsWith("runtime-ok")
                || t.expected().startsWith("runtime-error ")
                || t.expected().startsWith("known-fail runtime"))
            .count();

        Map<String, TestFile> byPath = new LinkedHashMap<>();
        for (TestFile test : tests) {
            byPath.put(test.path().toString(), test);
        }

        Set<String> companions = new LinkedHashSet<>();
        for (Classified c : classified) {
            if (c.kind() == Kind.COMPANION) {
                companions.add(c.test().relativePath());
            }
        }
        companionPaths = Set.copyOf(companions);

        Set<String> fromRuntime = new LinkedHashSet<>();
        Set<String> fromAny = new LinkedHashSet<>();
        for (Classified c : classified) {
            if (c.kind() == Kind.COMPANION) continue;
            Set<String> reach = transitivelyImportedCompanions(
                c.test().path(), byPath);
            fromAny.addAll(reach);
            if (c.kind() == Kind.APPLICABLE
                    || c.kind() == Kind.KNOWN_FAIL) {
                fromRuntime.addAll(reach);
            }
        }
        reachableFromRuntime = Set.copyOf(fromRuntime);
        importedByAny = Set.copyOf(fromAny);
    }

    /** The set of companion corpus-relative paths transitively reachable
     * from a fixture through its on-disk relative imports. */
    private static Set<String> transitivelyImportedCompanions(Path file,
            Map<String, TestFile> byPath) {
        Set<String> result = new LinkedHashSet<>();
        Deque<Path> stack = new ArrayDeque<>();
        Set<Path> seen = new HashSet<>();
        stack.push(file.toAbsolutePath().normalize());
        while (!stack.isEmpty()) {
            Path current = stack.pop();
            if (!seen.add(current)) continue;
            try {
                for (String importPath : relativeImports(current)) {
                    Path resolved = resolveCompanionPath(importPath,
                        current.getParent());
                    if (resolved == null) continue;
                    Path normalized = resolved.toAbsolutePath().normalize();
                    TestFile target = byPath.get(normalized.toString());
                    if (target != null
                            && target.expected().equals("companion")) {
                        result.add(target.relativePath());
                    }
                    stack.push(normalized);
                }
            } catch (IOException ignored) {
                // Unreadable file: the importer's run reports the failure.
            }
        }
        return result;
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
                case COMPANION -> runCompanion(classified);
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
            } else if (classified.kind() == Kind.COMPANION) {
                companionFailed.incrementAndGet();
                outcomes.add(new Outcome(classified.test(), classified,
                    false, "runner exception: " + e.getMessage()));
            } else {
                // A runner exception escaping a KNOWN_FAIL probe is an
                // explicit harness failure — never an applicable failure
                // and never a tracked known-fail.
                probeHarnessFailed.incrementAndGet();
                log("  [" + classified.test().relativePath()
                    + "] FAIL (runner exception during known-fail probe — "
                    + "harness failure): " + e.getMessage());
                outcomes.add(new Outcome(classified.test(), classified,
                    false, "runner exception during known-fail probe — "
                    + "harness failure: " + e.getMessage()));
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
        List<CompilerDiagnostic> diags = frontendDiagnostics(test.path());
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
    private static List<CompilerDiagnostic> frontendDiagnostics(Path file) {
        List<CompilerDiagnostic> all = new ArrayList<>();
        try {
            String source = Files.readString(file);
            String filename = file.toString();

            // ISSUE-0272 D8 item 2a: in-memory seam site —
            // classification headers are stripped before the lexer
            // (the same seam ConformanceTest.compileAndGetDiagnostics
            // applies), so the production directive gate never sees
            // an @spec/@description/@expected/@features header line.
            source = ConformanceHarnessMetadata
                .stripClassificationHeaders(source);

            LexResult lex = new Lexer(source, filename).tokenize();
            all.addAll(lex.diagnostics());
            if (lex.hasErrors()) return all;

            Parser parser = new Parser(lex.tokens(), filename, lex.directiveEvents());
            ParseResult parseResult = parser.parse();
            all.addAll(parseResult.diagnostics());
            if (parseResult.hasErrors()) return all;

            FrontendModuleResolver resolver = new FrontendModuleResolver(file);
            NameResolver nr = new NameResolver(filename, resolver);
            SymbolTable symTable;
            try {
                symTable = nr.resolve(parseResult.program());
            } catch (Exception e) {
                // E9999 is the test-only pseudo code for an unexpected
                // NameResolver exception: the deprecated synthetic factory
                // carries the canonical synthetic range plus an anchor
                // note naming the fixture file.
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

        FrontendModuleResolver(Path testFile) {
            this.testFileDir = testFile.toAbsolutePath().getParent();
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
                    // the inner companion read strips classification
                    // headers before the lexer.
                    String source = ConformanceHarnessMetadata
                        .stripClassificationHeaders(
                            Files.readString(resolved));
                    boolean isDecl = resolved.toString().endsWith(".d.deal");
                    LexResult lex = new Lexer(source, resolved.toString())
                        .tokenize();
                    if (lex.hasErrors()) {
                        throw new ModuleNotFoundException("Lex errors in "
                            + resolved);
                    }
                    Parser parser = new Parser(lex.tokens(),
                        resolved.toString());
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
        public Type resolveTypeNodeInModule(TypeNode typeNode,
                String modulePath, String importingModule)
                throws ModuleNotFoundException {
            if (modulePath == null || modulePath.isEmpty()) return null;
            Path resolved = resolveRelativePath(modulePath);
            if (resolved == null || !Files.exists(resolved)) return null;
            try {
                // ISSUE-0272 D8 item 2a: in-memory seam site —
                // classification headers are stripped before the
                // lexer.
                String source = ConformanceHarnessMetadata
                    .stripClassificationHeaders(
                        Files.readString(resolved));
                LexResult lex = new Lexer(source, resolved.toString())
                    .tokenize();
                if (lex.hasErrors()) return null;
                Parser parser = new Parser(lex.tokens(),
                    resolved.toString());
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
                // classification headers are stripped before the
                // lexer.
                String source = ConformanceHarnessMetadata
                    .stripClassificationHeaders(Files.readString(file));
                LexResult lex = new Lexer(source, file.toString()).tokenize();
                if (lex.hasErrors()) return symbols;
                Parser parser = new Parser(lex.tokens(), file.toString(), lex.directiveEvents());
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
    // Companions: standalone compilation through the shared frontend /
    // declaration gate
    // =========================================================================

    private static Outcome runCompanion(Classified classified) {
        TestFile test = classified.test();
        companionTotal.incrementAndGet();
        List<CompilerDiagnostic> diags =
            compileCompanionStandalone(test.path());
        boolean hasErrors = diags.stream()
            .anyMatch(d -> "error".equals(d.severity()));
        if (hasErrors) {
            companionFailed.incrementAndGet();
            StringBuilder sb = new StringBuilder();
            for (CompilerDiagnostic d : diags) {
                if ("error".equals(d.severity())) {
                    sb.append("    ").append(d).append('\n');
                }
            }
            log("  [" + test.relativePath()
                + "] FAIL (companion support module does not compile)\n"
                + sb);
            return new Outcome(test, classified, false,
                "companion does not compile");
        }
        companionPassed.incrementAndGet();
        log("  [" + test.relativePath()
            + "] COMPANION (classified support module; compiles)");
        return new Outcome(test, classified, true, "companion compiles");
    }

    /**
     * Compiles a classified companion support module standalone. Regular
     * {@code .deal} companions run the shared frontend pipeline
     * (lexer/parser, the v1.2 module shape gate, name resolution, type
     * checking). {@code .d.deal} declaration companions run the
     * declaration-file pipeline the harness itself uses when the parent
     * fixture imports them (lexer/parser plus {@link ExportExtractor}),
     * because external function declarations have no body to type-check.
     * The {@code deal.test.ConformanceTest} companion path, realized
     * here so this runner fails on any companion failure.
     */
    @SuppressWarnings("deprecation")
    private static List<CompilerDiagnostic> compileCompanionStandalone(
            Path file) {
        try {
            String source = Files.readString(file);
            String filename = file.toString();

            // ISSUE-0272 D8 item 2a: in-memory seam site —
            // classification headers are stripped before the lexer
            // (the same seam ConformanceTest.compileAndGetDiagnostics
            // applies).
            source = ConformanceHarnessMetadata
                .stripClassificationHeaders(source);

            LexResult lex = new Lexer(source, filename).tokenize();
            if (lex.hasErrors()) {
                return lex.diagnostics();
            }

            Parser parser = new Parser(lex.tokens(), filename, lex.directiveEvents());
            ParseResult parseResult = parser.parse();
            if (parseResult.hasErrors()) {
                return parseResult.diagnostics();
            }

            if (filename.endsWith(".d.deal")) {
                ExportExtractor extractor =
                    new ExportExtractor(filename, true);
                extractor.extract(parseResult.program());
                return extractor.diagnostics();
            }

            List<CompilerDiagnostic> shapeDiags =
                ModuleShapeValidator.validate(
                    parseResult.program(), filename, false);
            if (shapeDiags.stream().anyMatch(
                    d -> "error".equals(d.severity()))) {
                return new ArrayList<>(shapeDiags);
            }

            List<CompilerDiagnostic> allDiags = new ArrayList<>();
            FrontendModuleResolver resolver =
                new FrontendModuleResolver(file);
            NameResolver nr = new NameResolver(filename, resolver);
            SymbolTable symTable;
            try {
                symTable = nr.resolve(parseResult.program());
            } catch (Exception e) {
                allDiags.add(CompilerDiagnostic.synthetic("E9999", "error",
                    e.getMessage(), filename,
                    "missing anchor: companion source '" + filename + "'"));
                return allDiags;
            }
            allDiags.addAll(nr.diagnostics());
            CheckResult result = TypeChecker.check(filename, symTable, nr,
                parseResult.program());
            allDiags.addAll(result.diagnostics());
            return allDiags;
        } catch (IOException e) {
            String filename = file.toString();
            return List.of(CompilerDiagnostic.synthetic("E9999", "error",
                "cannot read: " + e.getMessage(), filename,
                "missing anchor: companion source '" + filename + "'"));
        }
    }

    // =========================================================================
    // Known-fail cases (tracked follow-up issues)
    // =========================================================================

    /**
     * Executes the underlying runtime mode of a known-fail case through
     * the real JS pipeline. While the case still fails, it is recorded
     * as a non-fatal tracked KNOWN-FAIL; when it starts passing, the
     * gate FAILS with a promotion instruction naming the fixture (drop
     * the marker and set the real {@code @expected}).
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
                + "by " + test.issue() + " now passes on Node — promote "
                + "the fixture: set '@expected: " + mode + "' and drop "
                + "the @issue tag)");
            return new Outcome(test, classified, false,
                "stale known-fail; promote fixture");
        }
        knownFailTracked.incrementAndGet();
        knownFailByIssue.merge(test.issue(), 1, Integer::sum);
        log("  [" + test.relativePath() + "] KNOWN-FAIL (" + mode
            + " not yet satisfied on Node; tracked by " + test.issue()
            + ") — " + probe.message());
        return null;
    }

    // =========================================================================
    // Backend-runtime execution: orchestrator → JsBackend → node
    // =========================================================================

    private static Outcome runApplicable(Classified classified) {
        return runApplicable(classified, false);
    }

    private static Outcome runApplicable(Classified classified,
            boolean knownFailProbe) {
        TestFile test = classified.test();
        if (!knownFailProbe) {
            applicableTotal.incrementAndGet();
        }

        Path projectRoot = null;
        try {
            projectRoot = Files.createTempDirectory("deal_js_conf_");

            // 1. Materialize the test file and its transitive companion
            // closure into the temp project root (flat stem namespace;
            // the corpus has no duplicate stems). Relative imports
            // resolve on disk from the corpus directories.
            Map<String, Path> written = new LinkedHashMap<>();
            writeModuleFiles(projectRoot, test.path(), written);
            String entryRel = corpusStem(test.path()) + ".deal";
            Path entryFile = projectRoot.resolve(entryRel);
            Path outputRoot = projectRoot.resolve("out");

            // 2. The production manifest: languageVersion "1.2" plus the
            // externals entries wiring every bare host/ import to its
            // host-fixtures declaration (the JvmConformanceTest
            // externals-wiring pattern).
            Set<String> hostNames = hostImports(written.values());
            Map<String, String> externals =
                writeProjectManifest(projectRoot, hostNames);

            // 3. The real whole-project pipeline: module discovery,
            // signature extraction, dependency ordering, name resolution,
            // type checking, per-module JsBackend codegen — the
            // isolated-phase Backend.JS path with the externals wiring
            // (the JS backend stays outside the strict backend set until
            // the skeleton epic extends the schema).
            OrchestratorRun run = runOrchestrator(projectRoot, entryFile,
                outputRoot, externals);
            if (!run.success()) {
                if (!knownFailProbe) {
                    applicableFailed.incrementAndGet();
                    log("  [" + test.relativePath()
                        + "] FAIL (orchestrator compile failed): "
                        + run.diagnostics() + "\n" + run.capturedOutput());
                }
                return new Outcome(test, classified, false,
                    "orchestrator compile failed: " + run.diagnostics());
            }

            // 4. Codegen was real: the entry artifact and the deployed
            // runtime must exist before node runs — a bypassed codegen
            // or deployment is never a pass.
            Path entryArtifact = outputRoot.resolve(
                corpusStem(test.path()) + ".js");
            if (!Files.exists(entryArtifact)) {
                if (!knownFailProbe) {
                    applicableFailed.incrementAndGet();
                    log("  [" + test.relativePath()
                        + "] FAIL (JS codegen produced no '"
                        + entryArtifact.getFileName() + "' artifact)");
                }
                return new Outcome(test, classified, false,
                    "no .js artifact produced");
            }
            Path runtimeArtifact = outputRoot.resolve("deal/runtime.js");
            if (!Files.exists(runtimeArtifact)) {
                if (!knownFailProbe) {
                    applicableFailed.incrementAndGet();
                    log("  [" + test.relativePath()
                        + "] FAIL (deal/runtime.js not deployed — a "
                        + "bypassed runtime deployment is not a pass)");
                }
                return new Outcome(test, classified, false,
                    "deal/runtime.js not deployed");
            }

            // 5. Host deployment (js-v12-host-abi-completion D6): every
            // host implementation lands at
            // <outputRoot>/<raw specifier>.js, the file the emitted
            // relative require resolves.
            for (String hostName : hostNames) {
                String hostSource = HOST_JS.get(hostName);
                if (hostSource == null) {
                    throw new HarnessFailure("no HOST_JS implementation "
                        + "for host/" + hostName + " — a missing host "
                        + "implementation is a harness failure, never a "
                        + "pass");
                }
                Path dest = outputRoot.resolve("host").resolve(
                    hostName + ".js");
                Files.createDirectories(dest.getParent());
                Files.writeString(dest, hostSource);
            }

            // 6. Runner: auto-invokes the entry module's zero-arity
            // exports, driven by the real parser's export list.
            ProgramNode entryProgram = parseEntryProgram(entryFile);
            Files.writeString(outputRoot.resolve("JsConformanceRunner.js"),
                buildJsCorpusRunner(entryProgram,
                    corpusStem(test.path())));

            // 7. Execute the deployed artifact set with a real node
            // subprocess.
            NodeResult result = runNode(outputRoot);

            boolean runtimeOkMode = test.expected().startsWith("runtime-ok")
                || test.expected().startsWith("known-fail runtime-ok");
            if (runtimeOkMode) {
                if (result.exitCode != 0) {
                    if (!knownFailProbe) {
                        applicableFailed.incrementAndGet();
                        log("  [" + test.relativePath()
                            + "] FAIL (runtime-ok test exited "
                            + result.exitCode + "): stdout: "
                            + result.stdout + "\nstderr: " + result.stderr);
                    }
                    return new Outcome(test, classified, false,
                        "exited " + result.exitCode + ": " + result.stderr);
                }
                if (!knownFailProbe) {
                    applicablePassed.incrementAndGet();
                    log("  [" + test.relativePath() + "] OK");
                }
                return new Outcome(test, classified, true, "runtime ok");
            }

            String needle = "DEAL_ERROR_CODE: "
                + classified.expectedCode();
            if (result.exitCode == 1 && result.stderr.contains(needle)) {
                if (!knownFailProbe) {
                    applicablePassed.incrementAndGet();
                    log("  [" + test.relativePath() + "] OK (found "
                        + needle + ")");
                }
                return new Outcome(test, classified, true,
                    "found " + needle);
            }
            if (!knownFailProbe) {
                applicableFailed.incrementAndGet();
                log("  [" + test.relativePath() + "] FAIL (expected exit 1"
                    + " and '" + needle + "' on stderr, got exit "
                    + result.exitCode + ", stdout: " + result.stdout
                    + ", stderr: " + result.stderr + ")");
            }
            return new Outcome(test, classified, false,
                "expected exit 1 and '" + needle + "' on stderr, got exit "
                    + result.exitCode);
        } catch (HarnessFailure e) {
            // A harness defect is never a fixture pass and never a
            // tracked known-fail.
            probeHarnessFailed.incrementAndGet();
            log("  [" + test.relativePath()
                + "] FAIL (harness failure — never a pass): "
                + e.getMessage());
            return new Outcome(test, classified, false,
                "harness failure: " + e.getMessage());
        } catch (Exception e) {
            if (!knownFailProbe) {
                applicableFailed.incrementAndGet();
            }
            log("  [" + test.relativePath()
                + "] FAIL (execution exception): " + e.getMessage());
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

    /**
     * Runs the real {@link CompilationOrchestrator} with
     * {@link Backend#JS} over the temp project: module discovery,
     * signature extraction, dependency ordering, name resolution, type
     * checking, and per-module JsBackend codegen into
     * {@code outputRoot}, with the repository root as the stdlib
     * directory (the JsE2eTest production-pipeline pattern). The
     * explicit {@link #LANE_INVOCATION}
     * ({@code COMMON_SHADOW + DEAL_V1_2_INT32}) drives the
     * compilation, so the emitted artifacts carry the
     * {@code $rt.setInt32Mode(true)} selector and the shared
     * stdlib-edge time fixture raises E8004 at the retained
     * {@code nowMillis} exit check. Stdout/stderr is captured so
     * per-test output stays clean.
     */
    private static OrchestratorRun runOrchestrator(Path projectRoot,
            Path entryFile, Path outputRoot,
            Map<String, String> externalsDeclarations) {
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
                        entryFile.toAbsolutePath().normalize(),
                        outputRoot.toAbsolutePath().normalize(),
                        false, false, false, false, Backend.JS,
                        externalsDeclarations,
                        List.of(projectRoot.toAbsolutePath().normalize()),
                        REPO_ROOT, null, LANE_INVOCATION);
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

    /**
     * Writes the production project manifest ({@code deal.json}) into
     * the temp project: {@code languageVersion "1.2"} always, plus the
     * externals map wiring every bare {@code host/<name>} import to the
     * copied {@code bindings/<name>.d.deal} declaration. A missing host
     * declaration or a manifest the harness generated that does not
     * load is a harness failure.
     */
    /**
     * Writes the injected project manifest (exact-v1.2 shape:
     * languageVersion "1.2", moduleRoots ["."], output "out", backend
     * "js", plus the externals map wiring every bare {@code host/<name>}
     * import to the copied {@code bindings/<name>.d.deal} declaration)
     * and returns the externals wiring map (raw specifier → absolute
     * declaration path) the isolated-phase Backend.JS orchestrator
     * consumes. The JS backend stays outside the strict backend set
     * ({@code "js"} is E2010 until the skeleton epic extends the
     * schema), so the harness drives {@link Backend#JS} through the
     * test-only isolated-phase constructor; the injected manifest keeps
     * the harness-project configuration exact-v1.2-shaped (ISSUE-0269,
     * parent D12).
     */
    private static Map<String, String> writeProjectManifest(
            Path projectRoot, Set<String> hostNames) throws IOException {
        StringBuilder dealJson = new StringBuilder();
        dealJson.append("{\n  \"languageVersion\": \"1.2\",\n");
        dealJson.append("  \"moduleRoots\": [\".\"],\n");
        dealJson.append("  \"output\": \"out\",\n");
        dealJson.append("  \"backend\": \"js\"");
        Map<String, String> externals = new LinkedHashMap<>();
        if (!hostNames.isEmpty()) {
            dealJson.append(",\n  \"externals\": {\n");
            boolean first = true;
            for (String hostName : hostNames) {
                Path decl = hostFixturesRoot.resolve(hostName + ".d.deal");
                if (!Files.isRegularFile(decl)) {
                    throw new HarnessFailure("host declaration missing for"
                        + " host/" + hostName + " — the HOST_JS harness "
                        + "cannot wire the externals entry");
                }
                if (!first) {
                    dealJson.append(",\n");
                }
                first = false;
                String declRel = "bindings/" + hostName + ".d.deal";
                Files.createDirectories(projectRoot.resolve("bindings"));
                Path declTarget = projectRoot.resolve(declRel);
                // ISSUE-0272 D8 item 2b: producer-side seam — the
                // host declaration copy is written
                // classification-header free, so the production
                // orchestrator never lexes a header line.
                Files.writeString(declTarget,
                    ConformanceHarnessMetadata.stripClassificationHeaders(
                        Files.readString(decl)));
                dealJson.append("    \"host/").append(hostName)
                    .append("\": { \"declaration\": \"")
                    .append(declRel).append("\" }");
                externals.put("host/" + hostName,
                    declTarget.toAbsolutePath().normalize().toString());
            }
            dealJson.append("\n  }");
        }
        dealJson.append("\n}\n");
        Files.writeString(projectRoot.resolve("deal.json"), dealJson);
        return externals;
    }

    /** The corpus-relative file stem (name without {@code .deal}). */
    private static String corpusStem(Path file) {
        String name = file.getFileName().toString();
        if (name.endsWith(".d.deal")) {
            return name.substring(0, name.length() - ".d.deal".length());
        }
        return name.substring(0, name.length() - ".deal".length());
    }

    /** The corpus-relative path of a file (slash-separated). */
    private static String corpusRelOf(Path file) {
        Path normalized = file.toAbsolutePath().normalize();
        return conformanceRoot.relativize(normalized).toString()
            .replace('\\', '/');
    }

    /**
     * Copies the entry file and its transitive companion closure into
     * the flat temp project root. Relative imports resolve against each
     * file's own corpus directory (the same resolution the LuaJIT
     * conformance harness uses); stdlib and bare host imports stop the
     * walk (stdlib resolves through the production resolver, host
     * modules through deal.json externals). Every copied corpus file is
     * recorded so the companion-participation gate can verify that each
     * companion materialized into at least one importing fixture's temp
     * project.
     */
    private static void writeModuleFiles(Path projectRoot, Path entry,
            Map<String, Path> written) throws IOException {
        copyTransitively(entry,
            entry.toAbsolutePath().normalize().getParent(),
            projectRoot, written);
    }

    private static void copyTransitively(Path file, Path entryDir,
            Path projectRoot, Map<String, Path> written)
            throws IOException {
        Path normalized = file.toAbsolutePath().normalize();
        if (written.containsKey(normalized.toString())) return;
        // Companions inside the entry's own corpus directory keep their
        // subdirectory layout (the v1.2 identity carriage: two files in
        // one directory share the module identity, so nested companions
        // need their directories preserved to stay nominally distinct);
        // every other companion keeps the flat stem layout.
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
        // ISSUE-0272 D8 item 2b: producer-side seam — the entry
        // fixture and every transitive companion are written
        // classification-header free, so the production orchestrator
        // never lexes a header line (the E1044 directive rejection).
        Files.writeString(target, ConformanceHarnessMetadata
            .stripClassificationHeaders(Files.readString(normalized)));
        written.put(normalized.toString(), target);
        materializedCorpusFiles.add(corpusRelOf(normalized));
        for (String importPath : relativeImports(normalized)) {
            Path resolved = resolveCompanionPath(importPath,
                normalized.getParent());
            if (resolved == null) continue;
            // An import spelling carrying an explicit .deal extension
            // resolves to its alias-named copy in the flat project root
            // (CompilationOrchestrator searches basePath + ".deal"
            // first), so materialize the companion under that name
            // before the recursive walk.
            copyCompanionAliasIfExplicit(resolved, importPath, projectRoot);
            copyTransitively(resolved, entryDir, projectRoot, written);
        }
    }

    /**
     * Materializes a resolved companion under the production-resolved
     * alias name when the import spelling carries an explicit
     * {@code .deal} extension ({@code ./async_lib.deal} →
     * {@code async_lib.deal.deal}): {@link CompilationOrchestrator}
     * searches {@code basePath + ".deal"} first, and its resolution rule
     * resolves the spelling to exactly that alias file. The stem-named
     * copy is still written by the recursive walk; stem-only spellings
     * need no alias and are untouched. The copy is idempotent across
     * repeated explicit spellings of the same companion.
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
        // ISSUE-0272 D8 item 2b: producer-side seam — the
        // explicit-.deal alias copy is written classification-header
        // free; the written-map dedup/alias semantics are unchanged.
        Files.writeString(aliasTarget, ConformanceHarnessMetadata
            .stripClassificationHeaders(Files.readString(
                resolved.toAbsolutePath().normalize())));
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

    /** Bare host import names ({@code host/<name>}) appearing in any of
     * the materialized files' sources, in order (drives deal.json
     * externals generation and the HOST_JS deployment). */
    private static Set<String> hostImports(Collection<Path> files)
            throws IOException {
        Set<String> hosts = new LinkedHashSet<>();
        for (Path file : files) {
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
     * Parses the entry module with the real lexer + parser for the
     * runner (its export list drives auto-invocation). Type checking is
     * NOT re-run here — the orchestrator already checked every module —
     * so this parse cannot act as a checker bypass.
     */
    private static ProgramNode parseEntryProgram(Path entryFile)
            throws IOException {
        String source = Files.readString(entryFile);
        LexResult lex = new Lexer(source, entryFile.toString()).tokenize();
        if (lex.hasErrors()) {
            throw new IllegalStateException("entry module lex errors: "
                + lex.diagnostics());
        }
        ParseResult parse = new Parser(lex.tokens(),
            entryFile.toString()).parse();
        if (parse.hasErrors()) {
            throw new IllegalStateException("entry module parse errors: "
                + parse.diagnostics());
        }
        return parse.program();
    }

    /**
     * Builds the JS corpus runner source for a fixture: requires the
     * entry artifact and the runtime, iterates the module's own export
     * keys in declaration order (the {@code $rt.setProp} insertion
     * order), SKIPS {@code $}-named exports (compiler-generated helper
     * exports such as the hidden {@code C$new} closures), auto-invokes
     * the remaining zero-arity exported wrappers (the zero-arity set
     * derived from the real parser's export list) in declaration order,
     * prints each non-null result, and AWAITS thenable results before
     * moving on (async exports resolve before printing/rejection). A
     * thrown error or a rejected Promise — including a module-load error
     * inside the required entry artifact — routes through
     * {@code $rt.reportUncaught} into the shared
     * {@code DEAL_ERROR_CODE: <code> <message>} stderr + exit-1
     * contract.
     */
    private static String buildJsCorpusRunner(ProgramNode program,
            String entryModule) {
        List<String> zeroAry = new ArrayList<>();
        for (StatementNode stmt : program.statements()) {
            if (stmt instanceof ExportDeclaration ed
                    && ed.declaration() instanceof FunctionDeclaration fd
                    && fd.params().isEmpty()
                    && !fd.name().contains("$")) {
                zeroAry.add(fd.name());
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("// Generated by deal.test.JsConformanceTest — JS corpus runner.\n");
        sb.append("// Requires the entry artifact, skips $-named exports, auto-invokes\n");
        sb.append("// zero-arity exported wrappers in declaration order, prints non-null\n");
        sb.append("// results, awaits thenable results, and maps uncaught errors into\n");
        sb.append("// the DEAL_ERROR_CODE stderr + exit-1 contract.\n");
        sb.append("\"use strict\";\n");
        sb.append("const $rt = require(\"./deal/runtime\");\n");
        sb.append("const $zeroAry = [");
        for (int i = 0; i < zeroAry.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append("\"").append(zeroAry.get(i)).append("\"");
        }
        sb.append("];\n");
        // The entry require sits inside the async body so a load-time
        // failure (a host loadHost E8011, a module-load error) routes
        // through the shared catch — the DEAL_ERROR_CODE stderr + exit-1
        // contract — never a raw uncaught stack trace.
        sb.append("(async () => {\n");
        sb.append("  const $entry = require(\"./").append(entryModule)
            .append("\");\n");
        sb.append("  for (const $k of Object.keys($entry)) {\n");
        sb.append("    if ($k.indexOf(\"$\") !== -1) { continue; }\n");
        sb.append("    const $v = $entry[$k];\n");
        sb.append("    if ($v && $v.$kind === \"function\" "
            + "&& $zeroAry.indexOf($k) !== -1) {\n");
        sb.append("      const $r = await $v.$f();\n");
        sb.append("      if ($r !== null && $r !== $rt.undefined) {\n");
        sb.append("        console.log($r);\n");
        sb.append("      }\n");
        sb.append("    }\n");
        sb.append("  }\n");
        sb.append("})().catch((e) => {\n");
        sb.append("  const $err = $rt.reifyError(e);\n");
        sb.append("  $rt.reportUncaught($err.file !== $rt.undefined\n");
        sb.append("    ? $rt.errorValue($err.code, $err.message + \" at \" "
            + "+ $err.file\n");
        sb.append("        + \":\" + $err.line + \":\" + $err.column, "
            + "$err.file,\n");
        sb.append("        $err.line, $err.column)\n");
        sb.append("    : $err);\n");
        sb.append("});\n");
        return sb.toString();
    }

    /** One bounded node subprocess run over the deployed artifact set
     * with captured streams. */
    private static NodeResult runNode(Path outputRoot)
            throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder("node",
            "JsConformanceRunner.js");
        pb.directory(outputRoot.toFile());
        pb.redirectErrorStream(false);
        Process p = pb.start();
        nodeExecuted.incrementAndGet();
        boolean finished = p.waitFor(SUBPROCESS_TIMEOUT_SECONDS,
            TimeUnit.SECONDS);
        if (!finished) {
            p.destroyForcibly();
            p.waitFor(5, TimeUnit.SECONDS);
            return new NodeResult(-1, "",
                "node subprocess timed out");
        }
        String stdout = readAll(p.getInputStream());
        String stderr = readAll(p.getErrorStream());
        return new NodeResult(p.exitValue(), stdout, stderr);
    }

    private static String readAll(InputStream stream) throws IOException {
        try (stream) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    // =========================================================================
    // Report
    // =========================================================================

    private static void printReport(List<Outcome> sorted) {
        System.out.println();
        System.out.println("=== DEAL v1.2 JavaScript Corpus Conformance "
            + "Summary (ISSUE-0278 — JsConformanceTest) ===");

        int ft = frontendTotal.get();
        int fp = frontendPassed.get();
        int ff = frontendFailed.get();
        System.out.printf("Frontend (backend-neutral compile-ok/"
            + "compile-error): total %d, passed %d, failed %d%n",
            ft, fp, ff);

        int at = applicableTotal.get();
        int ap = applicablePassed.get();
        int af = applicableFailed.get();
        int kt = knownFailTotal.get();
        int kf = knownFailTracked.get();
        int denominator = onDiskRuntimeDenominator;
        double pct = denominator == 0 ? 0.0
            : (ap + kf) * 100.0 / denominator;
        System.out.printf("Backend-runtime on Node: denominator %d "
            + "(every on-disk runtime-ok/runtime-error test plus every "
            + "known-fail probe), passed %d, failed %d, skipped 0 (no "
            + "skip registry — zero skips by construction), known-fail %d "
            + "(tracked), node subprocess runs %d — pass rate %.1f%%%n",
            denominator, ap, af, kf, nodeExecuted.get(), pct);
        System.out.println();

        int ct = companionTotal.get();
        int cp = companionPassed.get();
        int cf = companionFailed.get();
        Set<String> materializedCompanions = new LinkedHashSet<>(
            materializedCorpusFiles);
        materializedCompanions.retainAll(companionPaths);
        System.out.printf("Companions (classified support modules; "
            + "standalone-compiled through the shared frontend/"
            + "declaration gate, materialized transitively into every "
            + "importing fixture's temp project): classified %d (on-disk "
            + "@expected: companion %d), passed %d, failed %d%n",
            ct, onDiskCompanionCount, cp, cf);
        System.out.printf("Companion importer participation: %d "
            + "companion(s) reachable from runtime-classified fixtures, "
            + "%d materialized into importing fixtures' temp projects%n",
            reachableFromRuntime.size(), materializedCompanions.size());
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
        if (probeHarnessFailed.get() > 0) {
            System.out.println("GATE FAILURE: probeHarnessFailed = "
                + probeHarnessFailed.get() + " — a runner exception or "
                + "harness defect escaped a known-fail probe or the host "
                + "harness (a probe crash is never silent evidence and "
                + "never a tracked known-fail)");
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
        if (pct < 100.0) {
            System.out.println("GATE FAILURE: node-executed pass rate "
                + String.format(java.util.Locale.ROOT, "%.1f", pct)
                + "% below the 100% threshold (denominator "
                + denominator + ")");
            ok = false;
        }
        if (cf > 0) {
            System.out.println("GATE FAILURE: " + cf
                + " companion(s) failed standalone compilation — every "
                + "@expected: companion module must keep compiling "
                + "through the shared frontend/declaration gate");
            ok = false;
        }
        if (ct != onDiskCompanionCount) {
            System.out.println("GATE FAILURE: classified companion total "
                + ct + " differs from the on-disk @expected: companion "
                + "count " + onDiskCompanionCount);
            ok = false;
        }
        Set<String> deadCompanions = new LinkedHashSet<>(companionPaths);
        deadCompanions.removeAll(importedByAny);
        if (!deadCompanions.isEmpty()) {
            System.out.println("GATE FAILURE: dead companion(s) — no "
                + "classified fixture imports them: " + deadCompanions);
            ok = false;
        }
        Set<String> unmaterialized = new LinkedHashSet<>(
            reachableFromRuntime);
        unmaterialized.removeAll(materializedCompanions);
        Set<String> overMaterialized = new LinkedHashSet<>(
            materializedCompanions);
        overMaterialized.removeAll(reachableFromRuntime);
        if (!unmaterialized.isEmpty() || !overMaterialized.isEmpty()) {
            System.out.println("GATE FAILURE: companion importer "
                + "participation mismatch — reachable from "
                + "runtime-classified fixtures but never materialized: "
                + unmaterialized + "; materialized but not reachable: "
                + overMaterialized);
            ok = false;
        }
        int classifiedRuntimeTotal = at + kt;
        if (classifiedRuntimeTotal != denominator) {
            System.out.println("GATE FAILURE: classified runtime total "
                + classifiedRuntimeTotal + " differs from the on-disk "
                + "runtime denominator " + denominator);
            ok = false;
        }
        int classifiedTotal = ft + at + kt + ct;
        if (classifiedTotal != onDiskTotal) {
            System.out.println("GATE FAILURE: classified total (FRONTEND "
                + "+ APPLICABLE + KNOWN_FAIL + COMPANION) "
                + classifiedTotal + " differs from the on-disk "
                + "backend-runtime denominator " + onDiskTotal);
            ok = false;
        }
        if (!ok) {
            System.exit(1);
        }
        System.out.println("Gates PASSED: frontend 100%; backend-runtime "
            + "on node zero applicable failures AND 100% of the "
            + "node-executed " + denominator + "-test denominator; zero "
            + "skipped (no skip registry); zero stale known-fail markers; "
            + "companion counts equal the on-disk corpus and every "
            + "companion standalone-compiles and participates in its "
            + "importers' temp projects; zero probe runner exceptions.");
    }
}
