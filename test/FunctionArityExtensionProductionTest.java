package deal.test;

import deal.checker.BuiltinErrorDeclaration;
import deal.codegen.Backend;
import deal.codegen.jvm.JvmAsyncExportInvoker;
import deal.codegen.jvm.JvmNames;
import deal.codegen.lua.AsyncExportInvocationRequest;
import deal.codegen.lua.LuaJitAsyncExportInvoker;
import deal.module.CompilationOrchestrator;
import deal.semantic.CheckedModuleInput;
import deal.semantic.CheckedProjectBuildResult;
import deal.semantic.ClassRegistrationSeeds;
import deal.semantic.CompilerInvocation;
import deal.semantic.ContainerClaimingSeam;
import deal.semantic.CompilerProfileProvider;
import deal.semantic.RequirementManifestResult;
import deal.semantic.ReleaseConfiguration;
import deal.semantic.SemanticRequirementManifest;
import deal.semantic.SemanticLowerer;
import deal.semantic.ir.AdaptSourceRef;
import deal.semantic.ir.BoundaryKind;
import deal.semantic.ir.CaptureMode;
import deal.semantic.ir.FunctionExecutionBinding;
import deal.semantic.ir.KindPayload;
import deal.semantic.ir.FailurePolicyId;
import deal.semantic.ir.LoweredModuleUnit;
import deal.semantic.ir.ParameterBoundaryMode;
import deal.semantic.ir.SemanticIdAllocator;
import deal.semantic.ir.SemanticOp;
import deal.semantic.ir.SemanticOpKind;
import deal.test.conformance.JvmCompilation;
import deal.test.conformance.SidecarExpectations;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * The arity-extension position regression (ISSUE-0877): a compatible
 * shorter function value is adapted at every statically typed
 * function-value position, so the position's own boundary validates the
 * adapted value's exact target signature instead of rejecting a
 * source-compatible assignment (docs/spec-v1.5.md Function type
 * compatibility; Arity extension adapter).
 *
 * <p>The three published artifacts run the corpus fixtures' own exact
 * oracles on the real lanes: the sync position corpus under
 * {@code luajit}/{@code java}/{@code node}, the async position corpus
 * through the production async-export invokers on LuaJIT and the JVM and
 * through the artifact's own export surface under {@code node}. The
 * rejection controls pin the exact failure tuples — a table-produced
 * (dynamic) function value whose carried signature is shorter than a
 * declared parameter keeps the exact typed-boundary check, with the
 * E8010 code, message, expected/actual signature texts and the
 * declared-parameter origin identical on every lane, and the sync
 * terminal's own transcript on each lane.</p>
 */
public final class FunctionArityExtensionProductionTest {

    private static final Path CORPUS = Path.of("test", "conformance",
        "backend-runtime", "functions");

    private static final String ENTRY_MODULE = "main";

    /** The sync position corpus: all assertions inside the entry export. */
    private static final String POSITIONS =
        "arity-extension-positions";

    /** The async position corpus: a zero-arity async assertion export. */
    private static final String ASYNC_POSITIONS =
        "arity-extension-async-positions";

    /**
     * The boundary corpus: a later adapted RETURN than the function's first,
     * supplied-field adapters in local and cross-module construction, and
     * cataloged stdlib callable sources.
     */
    private static final String BOUNDARIES =
        "arity-extension-boundaries";

    /** The dynamic-source rejection control (the sync terminal). */
    private static final String DYNAMIC_SOURCE =
        "arity-extension-dynamic-source-e8010";

    /**
     * The dynamic-source rejection control through the async-export lane
     * (the exact failure tuple on LuaJIT and the JVM).
     */
    private static final String DYNAMIC_SOURCE_ASYNC =
        "arity-extension-dynamic-source-async-e8010";

    /**
     * The adapter-valued-source corpus: a function value whose declared
     * type is already narrower than a further position adapts there like
     * any other compatible function value (runtime provenance as an adapter
     * is not part of the type), through the binding, field, array element,
     * argument, return and assignment positions.
     */
    private static final String ADAPTER_VALUED_SOURCE =
        "arity-extension-adapter-valued-source";

    /**
     * The conversion-shadow corpus: a function-typed {@code int},
     * {@code number} or {@code bytes} shadow is the adapted operand at the
     * parameter, return, class supplied-field, class field-write, array
     * literal and array write positions, using ordinary lexical bindings
     * with no compiler intrinsic seeds (docs/spec-v1.5.md §Name resolution).
     */
    private static final String CONVERSION_SHADOW =
        "arity-extension-conversion-shadow";

    /**
     * The assignment-expression-source corpus: an assignment expression
     * yields the original RHS identity and type, independently of the
     * adapter committed to storage. An enclosing typed position therefore
     * adapts the original shorter callable again; each assignment snapshots
     * its evaluated RHS once, including nested assignment chains.
     */
    private static final String ASSIGNMENT_SOURCE =
        "arity-extension-assignment-source";

    /**
     * The assignment-expression-source corpus's adapter count: one adapter
     * per adapting position, including distinct storage and enclosing-value
     * adaptations over the original RHS. The nine enclosing exact-storage
     * positions still need their own adapters: assignment does not yield the
     * stored adapter. Runtime probes independently pin once-only evaluation.
     */
    private static final int ASSIGNMENT_SOURCE_ADAPTERS = 32;

    /**
     * The three conversion shadows' position count: three names across the
     * parameter, return, class supplied-field, class field-write, array
     * literal and array write positions.
     */
    private static final int CONVERSION_SHADOW_ADAPTERS = 18;

    /**
     * The async adapter-valued-source corpus: an async function value whose
     * declared type is itself an adapter's adapts at a wider awaited call
     * position whose source is an assigned binding's cell (the
     * {@code SHARED_CELL} adapter chain), so the invocation descends each
     * level's published source with that level's leading-M projection and the
     * async marker and ignored target parameters are preserved.
     */
    private static final String ASYNC_ADAPTER_SOURCE =
        "arity-extension-async-adapter-source";

    /** The async adapter-valued-source export and its completion value. */
    private static final String ASYNC_ADAPTER_SOURCE_EXPORT =
        "test_arity_extension_async_adapter_source";
    private static final String ASYNC_ADAPTER_SOURCE_COMPLETION = "123";

    /** The host declaration's external specifier. */
    private static final String HOST_SPECIFIER = "host/arity_extension_host";

    /** The host declaration file the project's externals entry names. */
    private static final String HOST_DECLARATION =
        "arity_extension_host.d.deal";

    /**
     * The host-declared parameter subject's declaration: a shorter
     * host-origin function value and the one-to-two and one-to-three
     * declared function parameter positions.
     */
    private static final String HOST_DECLARATION_SOURCE = """
        export function one(x: number): number;

        export function usePair(f: (x: number, y: number) => number): number;

        export function useTriple(f: (x: number, y: number, z: number) => number): number;

        export function useParse(f: (text: string, extra: number) => Record<string, unknown>): Record<string, unknown>;

        export function useIntTable(f: (x: number, y: Record<string, unknown>) => number): number;
        """;

    /** The subject's LuaJIT host implementation. */
    private static final String HOST_LUA_SOURCE = """
        return {
          one = function(x)
            return x
          end,
          usePair = function(f)
            return f(5, 9)
          end,
          useTriple = function(f)
            return f(5, 9, 13)
          end,
          useParse = function(f)
            return f("{}", 9)
          end,
          useIntTable = function(f)
            return f(5, "bad")
          end,
        }
        """;

    /** The subject's JS host implementation (the DEAL wrapper's .$f). */
    private static final String HOST_JS_SOURCE = """
        "use strict";

        module.exports = {
          one: function (x) {
            return x;
          },
          usePair: function (f) {
            return f.$f(5, 9);
          },
          useTriple: function (f) {
            return f.$f(5, 9, 13);
          },
          useParse: function (f) {
            return f.$f("{}", 9);
          },
          useIntTable: function (f) {
            return f.$f(5, "bad");
          },
        };
        """;

    /** The subject's JVM host implementation (the declared wrapper shapes). */
    private static final String HOST_JAVA_SOURCE = """
        final class HostArity_extension_host {
          public static Object one(double x) {
            return Double.valueOf(x);
          }

          public static Object usePair($DealRt.Fn2_N_N_R_N f) {
            return Double.valueOf(f.invoke(5, 9));
          }

          public static Object useTriple($DealRt.Fn3_N_N_N_R_N f) {
            return Double.valueOf(f.invoke(5, 9, 13));
          }

          public static Object useParse($DealRt.Fn2_S_N_R_$record f) {
            return f.invoke("{}", 9);
          }

          public static Object useIntTable($DealRt.Fn2_N_$record_R_N f) {
            return Double.valueOf(f.invoke(5, "bad"));
          }
        }
        """;

    /**
     * The host-declared parameter dynamic-source control: a table-produced
     * function value's carried shorter signature is validated exactly by
     * the host parameter cell, never silently adapted.
     */
    private static final String HOST_DYNAMIC_SOURCE = """
        import * as host from "host/arity_extension_host";
        import * as mutation from "host/signature_mutation";

        function one(x: number): number { return x; }

        export function main(): null {
          let source: (x: number, y: number) => number = function(x: number, y: number): number { return x + y; };
          if (!mutation.replaceSignature({value: source}, "(number)->number")) { throw {code: "TEST_FAIL", message: "signature mutation failed"}; }
          let r: number = host.usePair(source);
          return null;
        }
        """;

    /**
     * The emission-fact entry: the entry module imports the fixture library
     * so the library's module walk is the one under test.
     */
    private static final String HOST_PARAMETER_FACT_ENTRY = """
        import * as lib from "./lib";

        export function main(): null { return null; }
        """;

    /**
     * The emission-fact subject: a host-declared parameter position beside a
     * supplied-field position, in a library module exported by nothing and
     * calling no function-typed parameter — the per-module project walk this
     * test drives then needs no callee module facts.
     */
    private static final String HOST_PARAMETER_FACT_SOURCE = """
        import * as host from "host/arity_extension_host";

        function one(x: number): number { return x; }

        class Holder {
          pair: (x: number, y: number) => number;
          n: number;
        }
        function make(): Holder {
          return {pair: one, n: host.usePair(one)};
        }
        """;

    /**
     * The host-invoked adapter's target-signature contract: the adapter
     * wrapper admits every declared target parameter, so the host that
     * invokes the published adapter value with a wrong-typed ignored
     * (trailing) argument fails closed with the typed-boundary row instead
     * of silently dropping the argument (docs/spec-v1.5.md Arity extension
     * adapter: the wrapper checks the parameter it ignores). The declared
     * parameter is {@code table} so the JVM host ABI's declared function
     * wrapper can hand a non-table value to the trailing position.
     */
    private static final String HOST_TARGET_PARAMETER_SOURCE = """
        import * as host from "host/arity_extension_host";

        function oneInt(x: number): number { return x; }

        export function main(): null {
          let r: number = host.useIntTable(oneInt);
          if (r !== 5) {
            throw { code: "TEST_FAIL", message: "the adapted host parameter ran" };
          }
          return null;
        }
        """;

    /**
     * The host-declared parameter subject: a statically typed host
     * parameter adapts a compatible shorter DEAL or host-origin function
     * value before the exact host cell, and an exact value stores directly.
     */
    private static final String HOST_PARAMETER_SOURCE = """
        import * as console from "std/console";
        import * as host from "host/arity_extension_host";

        function check(condition: boolean, message: string): null {
          if (!condition) {
            throw { code: "TEST_FAIL", message: message };
          }
          return null;
        }

        function one(x: number): number { return x; }

        function needPair(f: (x: number, y: number) => number): number { return f(3, 4); }

        export function main(): null {
          check(needPair(host.one) === 3,
            "a host-origin shorter function value adapts at a DEAL parameter");
          check(host.usePair(one) === 5,
            "a DEAL shorter function value adapts at a host parameter");
          check(host.usePair(host.one) === 5,
            "a host-origin shorter function value adapts at a host parameter");
          check(host.useTriple(one) === 5,
            "a one-to-three host parameter adapts and ignores the target parameters");
          let pair: (x: number, y: number) => number = one;
          check(host.usePair(pair) === 5,
            "an exact adapted value stores directly at a host parameter");
          check(host.useTriple(pair) === 5,
            "an adapter-valued source adapts at a host parameter");

          console.log("OK");
          return null;
        }
        """;

    /**
     * The host-invoked cataloged stdlib callable: the host parameter carries
     * the arity-extension adapter over {@code json.parse}, and the host
     * calls the adapter through the declared function shape, so the D15
     * invocation dispatches the stdlib row on LuaJIT and the JVM.
     */
    private static final String HOST_STDLIB_SOURCE = """
        import * as console from "std/console";
        import * as host from "host/arity_extension_host";
        import * as json from "std/json";

        export function main(): null {
          let parsed: Record<string, unknown> = host.useParse(json.parse);
          if ((json.stringify(parsed) as string) !== "{}") {
            throw { code: "TEST_FAIL", message: "host stdlib adapter failed" };
          }
          console.log("OK");
          return null;
        }
        """;

    // =========================================================================
    // The host-invoked adapter's target parameters
    // =========================================================================

    /** The host-invoked adapter's target-parameter project. */
    private static Project writeHostTargetParameterProject(Path root) throws Exception {
        return writeProjectSource(root, HOST_TARGET_PARAMETER_SOURCE,
            Map.of(HOST_SPECIFIER, "bindings/" + HOST_DECLARATION));
    }

    /**
     * The LuaJIT host-invoked adapter: the declared function wrapper and the
     * adapter's own target cells both admit the ignored trailing parameter,
     * so the wrong-typed second argument raises the typed-boundary failure
     * instead of being dropped.
     */
    private static void testLuaJitHostTargetParameter(Path root) throws Exception {
        System.out.println("-- the LuaJIT host-invoked adapter's target "
            + "parameters --");
        Project project = writeHostTargetParameterProject(root);
        assertProductionEmission(productionCompile(project, Backend.LUAJIT,
            Map.of(HOST_SPECIFIER, "bindings/" + HOST_DECLARATION)),
            "luajit:host-target-parameter");
        writeFile(project.out(), "host/arity_extension_host.lua",
            HOST_LUA_SOURCE);
        ProcessOutcome run = runProcess(
            List.of("luajit", ENTRY_MODULE + ".lua"), project.out());
        checkEq(1, run.exitCode(), "luajit: the host-invoked adapter's "
            + "wrong-typed ignored argument exits one: " + run.stdout()
            + run.stderr());
        checkEq("DEAL_ERROR_CODE: E8001\n", run.stdout(), "luajit: the "
            + "host-invoked adapter raises the typed-boundary row");
        checkEq("", run.stderr(), "luajit: the target-parameter control's stderr");
    }

    /**
     * The JVM host-invoked adapter: the host ABI's declared function wrapper
     * reaches {@code JvmRuntime.invokeAdapter}, whose target cells admit the
     * ignored trailing parameter.
     */
    private static void testJvmHostTargetParameter(Path root) throws Exception {
        System.out.println("-- the JVM host-invoked adapter's target "
            + "parameters --");
        Project project = writeHostTargetParameterProject(root);
        Path hostSource = project.root().resolve("HostArity_extension_host.java");
        writeFile(project.root(), "HostArity_extension_host.java",
            HOST_JAVA_SOURCE);
        JvmArtifacts artifacts = compileJvmProject(project,
            "jvm:host-target-parameter", List.of(hostSource),
            Map.of(HOST_SPECIFIER, "bindings/" + HOST_DECLARATION));
        ProcessOutcome run = runProcess(List.of("java", "-cp",
            artifacts.classes() + File.pathSeparator + absoluteClasspath(),
            artifacts.className()), artifacts.project().out());
        checkEq(1, run.exitCode(), "jvm: the host-invoked adapter's "
            + "wrong-typed ignored argument exits one: " + run.stdout()
            + run.stderr());
        checkEq("DEAL_ERROR_CODE: E8001\n", run.stdout(), "jvm: the "
            + "host-invoked adapter raises the typed-boundary row");
        checkEq("", run.stderr(), "jvm: the target-parameter control's stderr");
    }

    /**
     * The JS host-invoked adapter: the host calls the published adapter's
     * own {@code $f}, which is the only enforcement point for the declared
     * target signature (no call-site cell exists), so the emitted wrapper's
     * target checks must fire.
     */
    private static void testJsHostTargetParameter(Path root) throws Exception {
        System.out.println("-- the JS host-invoked adapter's target "
            + "parameters --");
        Project project = writeHostTargetParameterProject(root);
        assertProductionEmission(productionCompile(project, Backend.JS,
            Map.of(HOST_SPECIFIER, "bindings/" + HOST_DECLARATION)),
            "js:host-target-parameter");
        writeFile(project.out(), "host/arity_extension_host.js", HOST_JS_SOURCE);
        ProcessOutcome run = runProcess(List.of("node", ENTRY_MODULE + ".js"),
            project.out());
        checkEq(1, run.exitCode(), "js: the host-invoked adapter's "
            + "wrong-typed ignored argument exits one: " + run.stdout()
            + run.stderr());
        checkEq("", run.stdout(), "js: the target-parameter control's stdout");
        checkEq("DEAL_ERROR_CODE: E8001 expected table\n", run.stderr(),
            "js: the emitted adapter wrapper admits the ignored trailing "
                + "parameter with the typed-boundary row");
    }

    /**
     * The host-declared parameter position's emission fact: the per-module
     * production walk records one {@link SemanticLowerer.AdapterEmission} per
     * emitted {@code FUNCTION_ADAPT} op — the host parameter cell included —
     * so the shape-map fact surface stays complete and no position leaves a
     * pending entry behind.
     */
    private static void testHostParameterEmissionFact(Path root) throws Exception {
        System.out.println("-- the host-declared parameter position's emission "
            + "fact --");
        Project project = writeProjectSource(root, HOST_PARAMETER_FACT_ENTRY,
            Map.of(HOST_SPECIFIER, "bindings/" + HOST_DECLARATION));
        writeFile(root, "src/lib.deal", HOST_PARAMETER_FACT_SOURCE);
        CompilationOrchestrator orchestrator = productionCompile(project,
            Backend.LUAJIT, Map.of(HOST_SPECIFIER, "bindings/" + HOST_DECLARATION));
        deal.semantic.EmissionFactWalk.Walk walk =
            deal.semantic.EmissionFactWalk.walk(orchestrator, "lib");
        check(walk.succeeded(), "the fixture library's per-module walk produces "
            + "the unit: " + walk.diagnostics());
        if (!walk.succeeded()) {
            return;
        }
        List<SemanticOp> adapters = new ArrayList<>();
        for (SemanticOp op : walk.unit().ops()) {
            if (op.kind() == SemanticOpKind.FUNCTION_ADAPT) {
                adapters.add(op);
            }
        }
        List<SemanticLowerer.AdapterEmission> emissions = walk.facts().emissions();
        checkEq(adapters.size(), emissions.size(), "the shape-map fact surface "
            + "carries exactly one record per emitted FUNCTION_ADAPT op (the "
            + "host-declared parameter position included)");
        java.util.Map<BoundaryKind, Integer> wiredKinds = new java.util.LinkedHashMap<>();
        for (SemanticLowerer.AdapterEmission emission : emissions) {
            SemanticOp wiring = null;
            for (SemanticOp op : walk.unit().ops()) {
                if (op.opId().equals(emission.wiringTargetOpId())) {
                    wiring = op;
                }
            }
            check(wiring != null, "the emission records a wiring target that "
                + "exists in the produced unit");
            if (wiring == null) {
                continue;
            }
            if (wiring.kind() == SemanticOpKind.BOUNDARY
                    && wiring.payload()
                        instanceof KindPayload.BoundaryPayload boundary) {
                checkEq(emission.adapterIdentity(), boundary.input(), "the "
                    + "emission's wiring target admits the adapter identity");
                checkEq(emission.wiringBoundaryKind(), boundary.kind(), "the "
                    + "emission's recorded boundary kind is the wiring cell's kind");
                if (boundary.kind() == BoundaryKind.DEAL_TO_HOST) {
                    checkEq(FailurePolicyId.HOST_PARAMETER, wiring.failurePolicy(),
                        "the host-declared parameter emission wires the "
                            + "DEAL_TO_HOST cell under the HOST_PARAMETER policy");
                }
                wiredKinds.merge(boundary.kind(), 1, Integer::sum);
                continue;
            }
            check(false, "every structural position adapter wires a boundary");
        }
        checkEq(1, wiredKinds.getOrDefault(BoundaryKind.DEAL_TO_HOST, 0),
            "the host-declared parameter position owns one recorded emission");
        checkEq(1, wiredKinds.getOrDefault(BoundaryKind.CLASS_LITERAL_FIELD, 0),
            "the supplied class field position keeps its recorded emission");
    }

    /** The completion the async position corpus proves. */
    private static final String ASYNC_COMPLETION = "320";

    /** The rejection control's exact tuple. */
    private static final String E8010_MESSAGE =
        "function signature mismatch: expected (number,number)->number, got (number)->number";
    private static final String E8010_EXPECTED = "(number,number)->number";
    private static final String E8010_ACTUAL = "(number)->number";

    private static int passed = 0;
    private static int failed = 0;

    private static void check(boolean condition, String message) {
        if (condition) {
            passed++;
        } else {
            failed++;
            System.err.println("FAIL: " + message);
        }
    }

    private static void checkEq(Object expected, Object actual, String message) {
        check(Objects.equals(expected, actual), message + " (expected "
            + expected + ", got " + actual + ")");
    }

    // =========================================================================
    // The corpus fixtures and their pinned lanes
    // =========================================================================

    private static String fixtureSource(String fixture) throws Exception {
        String corpusPath = "backend-runtime/functions/" + fixture + ".deal";
        return ConformanceHarnessMetadata.productionCorpusSource(
            Files.readString(CORPUS.resolve(fixture + ".deal"),
                StandardCharsets.UTF_8), corpusPath);
    }

    /**
     * The fixture's classified support modules (the corpus companion
     * convention: a sibling {@code <fixture>-<name>.deal}), each with its
     * classification headers stripped.
     */
    private static Map<String, String> companionSources(String fixture)
            throws Exception {
        Map<String, String> companions = new LinkedHashMap<>();
        try (Stream<Path> siblings = Files.list(CORPUS)) {
            for (Path sibling : siblings.sorted().toList()) {
                String name = sibling.getFileName().toString();
                if (!name.endsWith(".deal") || !name.startsWith(fixture + "-")) {
                    continue;
                }
                companions.put(name,
                    ConformanceHarnessMetadata.productionCorpusSource(
                        Files.readString(sibling, StandardCharsets.UTF_8),
                        "backend-runtime/functions/" + name));
            }
        }
        return companions;
    }

    private static SidecarExpectations.StructuredExpectationSidecar sidecar(
            String fixture) throws Exception {
        Path file = CORPUS.resolve(fixture + ".expect.json");
        check(Files.isRegularFile(file), fixture + ": the fixture carries its "
            + "structured expectation sidecar: " + file);
        return SidecarExpectations.StructuredExpectationSidecar.parse(
            Files.readString(file, StandardCharsets.UTF_8));
    }

    private record ExpectedExecution(byte[] stdout, byte[] stderr, int exitCode) {
    }

    /** The host-declared parameter subject's three-lane pinned execution. */
    private static Map<String, ExpectedExecution> hostParameterExpected() {
        Map<String, ExpectedExecution> expected = new LinkedHashMap<>();
        for (String lane : List.of("luajit", "jvm", "js")) {
            expected.put(lane, new ExpectedExecution(
                "OK\n".getBytes(StandardCharsets.UTF_8), new byte[0], 0));
        }
        return expected;
    }

    private static Map<String, ExpectedExecution> expectedByBackend(String fixture)
            throws Exception {
        SidecarExpectations.StructuredExpectationSidecar pinned = sidecar(fixture);
        Map<String, ExpectedExecution> expected = new LinkedHashMap<>();
        for (String backend : List.of("luajit", "jvm", "js")) {
            SidecarExpectations.RuntimeExpectation expectation =
                pinned.expectationFor(backend);
            check(expectation instanceof
                    SidecarExpectations.RuntimeExpectation.Executed,
                fixture + ": the sidecar pins the " + backend + " lane");
            if (expectation instanceof
                    SidecarExpectations.RuntimeExpectation.Executed executed) {
                expected.put(backend, new ExpectedExecution(executed.stdout(),
                    executed.stderr(), executed.exitCode()));
            }
        }
        return expected;
    }

    // =========================================================================
    // The production invocation
    // =========================================================================

    private record Project(Path root, Path src, Path entry, Path out) {
    }

    private static CompilerInvocation productionInvocation() {
        return CompilerProfileProvider.resolve(
            ReleaseConfiguration.CURRENT_RELEASE_STATE,
            ReleaseConfiguration.releaseCapabilityRegistry());
    }

    private static Project writeProject(Path root, String fixture) throws Exception {
        Map<String, String> externals = fixture.equals(DYNAMIC_SOURCE)
                || fixture.equals(DYNAMIC_SOURCE_ASYNC)
            ? Map.of("host/signature_mutation", "bindings/signature_mutation.d.deal")
            : Map.of();
        return writeProject(root, fixture, externals);
    }

    /**
     * The corpus project with one declared host/external specifier: the
     * declaration file is written under the external entry's own path, so
     * the import resolves to the release-owned host declaration surface.
     */
    private static Project writeProject(Path root, String fixture,
            Map<String, String> externals) throws Exception {
        Project project = writeProjectSource(root, fixtureSource(fixture), externals);
        for (Map.Entry<String, String> companion : companionSources(fixture)
                .entrySet()) {
            writeFile(root, "src/" + companion.getKey(), companion.getValue());
        }
        return project;
    }

    /** One project over explicit entry source text. */
    private static Project writeProjectSource(Path root, String source,
            Map<String, String> externals) throws Exception {
        if (source.contains("host/signature_mutation") && !externals.containsKey("host/signature_mutation")) {
            Map<String, String> combined = new LinkedHashMap<>(externals);
            combined.put("host/signature_mutation", "bindings/signature_mutation.d.deal");
            externals = combined;
        }
        StringBuilder json = new StringBuilder(
            "{\"languageVersion\":\"1.5\",\"moduleRoots\":[\"src\"]");
        if (!externals.isEmpty()) {
            json.append(",\"externals\":{");
            boolean first = true;
            for (Map.Entry<String, String> external : externals.entrySet()) {
                if (!first) {
                    json.append(",");
                }
                first = false;
                json.append("\"").append(external.getKey()).append("\":{\"declaration\":\"")
                    .append(external.getValue()).append("\"}");
            }
            json.append("}");
        }
        json.append("}");
        writeFile(root, "deal.json", json.toString());
        writeFile(root, "src/" + ENTRY_MODULE + ".deal", source);
        for (Map.Entry<String, String> external : externals.entrySet()) {
            // The isolated-phase constructor resolves a relative declaration
            // against the entry directory.
            boolean mutation = external.getKey().equals("host/signature_mutation");
            writeFile(root, "src/" + external.getValue(), mutation
                ? Files.readString(Path.of("test/conformance/host-fixtures/signature_mutation.d.deal"))
                    .replaceAll("(?m)^// @.*\\R", "")
                : HOST_DECLARATION_SOURCE);
            if (mutation) {
                for (String suffix : List.of("lua", "js", "java")) {
                    writeFile(root, "src/host/signature_mutation." + suffix,
                        Files.readString(Path.of("test/conformance/host-fixtures/signature_mutation." + suffix)));
                }
            }
        }
        Path src = root.resolve("src");
        return new Project(root, src, src.resolve(ENTRY_MODULE + ".deal"),
            root.resolve("out"));
    }

    /** The host-declared parameter project: one external declaration. */
    private static Project writeHostParameterProject(Path root) throws Exception {
        return writeProjectSource(root, HOST_PARAMETER_SOURCE,
            Map.of(HOST_SPECIFIER, "bindings/" + HOST_DECLARATION));
    }

    private static CompilationOrchestrator productionCompile(Project project,
            Backend backend) throws Exception {
        Map<String, String> externals = Files.exists(project.src().resolve("bindings/signature_mutation.d.deal"))
            ? Map.of("host/signature_mutation", "bindings/signature_mutation.d.deal") : Map.of();
        return productionCompile(project, backend, externals);
    }

    /** The production compile with the project's external declarations. */
    private static CompilationOrchestrator productionCompile(Project project,
            Backend backend, Map<String, String> externals) throws Exception {
        if (Files.exists(project.src().resolve("bindings/signature_mutation.d.deal"))) {
            Map<String, String> combined = new LinkedHashMap<>(externals);
            combined.put("host/signature_mutation", "bindings/signature_mutation.d.deal");
            externals = combined;
        }
        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            project.entry().toAbsolutePath(), project.out(), false, false, false,
            false, backend, externals, List.of(project.src().toAbsolutePath()),
            Path.of("").toAbsolutePath(), null, productionInvocation());
        orchestrator.compile();
        if (Files.exists(project.src().resolve("bindings/signature_mutation.d.deal"))
                && Files.isDirectory(project.out()) && backend != Backend.JVM) {
            String suffix = backend == Backend.JS ? "js" : "lua";
            writeFile(project.out(), "host/signature_mutation." + suffix,
                Files.readString(project.src().resolve("host/signature_mutation." + suffix)));
        }
        return orchestrator;
    }

    private static void assertProductionEmission(
            CompilationOrchestrator orchestrator, String backend) {
        check(orchestrator.diagnostics().isEmpty(), backend + ": the production "
            + "compile reports zero diagnostics: " + orchestrator.diagnostics());
        checkEq(1, orchestrator.semanticEmissionCount(), backend + ": the "
            + "production arm emits exactly one project artifact");
    }

    private record ProcessOutcome(int exitCode, String stdout, String stderr,
                                  boolean stdoutTruncated, boolean stderrTruncated) {
    }

    private static ProcessOutcome runProcess(List<String> command, Path workDir)
            throws Exception {
        if (command.get(0).equals("javac")) {
            JvmCompilation.Result compiled =
                JvmCompilation.compile(command, workDir);
            return new ProcessOutcome(compiled.exitCode(), compiled.stdout(),
                compiled.stderr(), false, false);
        }
        BoundedProcess.BoundedResult run =
            BoundedProcess.runCaptured(command, workDir);
        return new ProcessOutcome(run.exitCode(), run.stdout(), run.stderr(),
            run.stdoutTruncated(), run.stderrTruncated());
    }

    private static void assertExecution(String backend, ProcessOutcome run,
            ExpectedExecution expected) {
        check(!run.stdoutTruncated() && !run.stderrTruncated(),
            backend + ": the artifact's streams are complete");
        checkEq(expected.exitCode(), run.exitCode(), backend + ": the artifact "
            + "exit code: " + run.stdout() + run.stderr());
        checkEq(new String(expected.stdout(), StandardCharsets.UTF_8),
            run.stdout(), backend + ": the artifact stdout");
        checkEq(new String(expected.stderr(), StandardCharsets.UTF_8),
            run.stderr(), backend + ": the artifact stderr");
    }

    private static String absoluteClasspath() {
        StringBuilder resolved = new StringBuilder();
        for (String entry : System.getProperty("java.class.path", "")
                .split(File.pathSeparator)) {
            if (entry.isEmpty()) {
                continue;
            }
            if (resolved.length() > 0) {
                resolved.append(File.pathSeparator);
            }
            resolved.append(Path.of(entry).toAbsolutePath().normalize());
        }
        return resolved.toString();
    }

    private static void writeFile(Path root, String relative, String content)
            throws Exception {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private static void deleteRecursively(Path dir) {
        try {
            if (dir == null || !Files.exists(dir)) {
                return;
            }
            try (Stream<Path> walk = Files.walk(dir)) {
                for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        } catch (Exception ignored) {
            // Best-effort temp cleanup only; never part of a test result.
        }
    }

    // =========================================================================
    // The LuaJIT lane
    // =========================================================================

    private static void testLuaJitPositions(Path root,
            ExpectedExecution expected) throws Exception {
        System.out.println("-- the LuaJIT position corpus and its real luajit run --");
        Project project = writeProject(root, POSITIONS);
        CompilationOrchestrator orchestrator = productionCompile(project,
            Backend.LUAJIT);
        assertProductionEmission(orchestrator, "luajit:" + POSITIONS);
        Path artifact = project.out().resolve(ENTRY_MODULE + ".lua");
        check(Files.isRegularFile(artifact), "luajit: the production compile "
            + "stages the entry chunk " + artifact);
        if (!Files.isRegularFile(artifact)) {
            return;
        }
        String chunk = Files.readString(artifact, StandardCharsets.UTF_8);
        check(chunk.contains("\"FUNCTION_ADAPT\""), "luajit: the emitted chunk "
            + "carries the arity-extension adapters");
        check(chunk.contains("__adaptInvoke"), "luajit: the emitted adapters run "
            + "the D15 invocation protocol");
        assertExecution("luajit",
            runProcess(List.of("luajit", ENTRY_MODULE + ".lua"), project.out()),
            expected);
    }

    private static void testLuaJitBoundaries(Path root,
            ExpectedExecution expected) throws Exception {
        System.out.println("-- the LuaJIT boundary corpus and its real luajit run --");
        Project project = writeProject(root, BOUNDARIES);
        assertProductionEmission(productionCompile(project, Backend.LUAJIT),
            "luajit:" + BOUNDARIES);
        assertExecution("luajit", runProcess(
            List.of("luajit", ENTRY_MODULE + ".lua"), project.out()), expected);
    }

    private static void testLuaJitAsyncPositions(Path root) throws Exception {
        System.out.println("-- the LuaJIT async position corpus through the "
            + "production async-export invoker --");
        Project project = writeProject(root, ASYNC_POSITIONS);
        CompilationOrchestrator orchestrator = productionCompile(project,
            Backend.LUAJIT);
        assertProductionEmission(orchestrator, "luajit:" + ASYNC_POSITIONS);
        Path artifact = project.out().resolve(ENTRY_MODULE + ".lua");
        check(Files.isRegularFile(artifact), "luajit: the async corpus stages "
            + "the entry chunk " + artifact);
        if (!Files.isRegularFile(artifact)) {
            return;
        }
        assertAsyncTemporariesAreBodyLocals(
            Files.readString(artifact, StandardCharsets.UTF_8));
        assertAsyncCompletion("luajit", new LuaJitAsyncExportInvoker().invoke(
            new AsyncExportInvocationRequest(artifact,
                "test_arity_extension_async", "number")));
    }

    /**
     * The async adapter arm's temporaries are body locals: the chunk declares
     * every hoisted scratch temp and every emitted body re-declares the same
     * names, so the arm's projection count belongs to the activation's own
     * body and never to the chunk's shared global table (a {@code __dynN}
     * assigned as a global would leak one activation's count into another
     * chunk's coroutine).
     */
    private static void assertAsyncTemporariesAreBodyLocals(String chunk) {
        int declarations = 0;
        for (String line : chunk.split("\n", -1)) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("local __chk, __rvT, ")) {
                continue;
            }
            declarations++;
            check(trimmed.contains(", __dynN,"), "luajit: the async adapter "
                + "arm's projection temp is a body local: " + trimmed);
        }
        check(declarations > 1, "luajit: the async corpus declares the hoisted "
            + "scratch temps in the chunk and in its bodies; got " + declarations);
    }

    private static void assertAsyncCompletion(String lane,
            LuaJitAsyncExportInvoker.Result result) {
        check(result instanceof LuaJitAsyncExportInvoker.Result.Value,
            lane + ": the adapted async positions complete a value: " + result);
        if (result instanceof LuaJitAsyncExportInvoker.Result.Value value) {
            checkEq(Double.parseDouble(ASYNC_COMPLETION), Double.parseDouble(value.valueJson()), lane
                + ": the adapted async completion (one-to-two 101, zero-to-one 7, "
                + "awaited 102, sync 4 and the ignored-argument 106)");
        }
    }

    private static void testLuaJitDynamicSource(Path root) throws Exception {
        System.out.println("-- the LuaJIT dynamic-source control's own terminal --");
        Project project = writeProject(root, DYNAMIC_SOURCE);
        assertProductionEmission(productionCompile(project, Backend.LUAJIT),
            "luajit:" + DYNAMIC_SOURCE);
        ProcessOutcome run = runProcess(
            List.of("luajit", ENTRY_MODULE + ".lua"), project.out());
        checkEq(1, run.exitCode(), "luajit: the dynamic-source control exits one: "
            + run.stdout() + run.stderr());
        checkEq("DEAL_ERROR_CODE: E8010\n", run.stdout(), "luajit: the "
            + "dynamic-source control's own terminal transcript");
        checkEq("", run.stderr(), "luajit: the dynamic-source control's stderr");
    }

    private static void testLuaJitDynamicSourceAsync(Path root) throws Exception {
        System.out.println("-- the LuaJIT dynamic-source failure tuple through the "
            + "async-export lane --");
        Project project = writeProject(root, DYNAMIC_SOURCE_ASYNC);
        assertProductionEmission(productionCompile(project, Backend.LUAJIT),
            "luajit:" + DYNAMIC_SOURCE_ASYNC);
        Path artifact = project.out().resolve(ENTRY_MODULE + ".lua");
        LuaJitAsyncExportInvoker.Result result = new LuaJitAsyncExportInvoker()
            .invoke(new AsyncExportInvocationRequest(artifact,
                "test_arity_extension_dynamic_source_async_e8010", "number"));
        assertDealError("luajit", result instanceof LuaJitAsyncExportInvoker.Result.DealError
                ? (LuaJitAsyncExportInvoker.Result.DealError) result : null,
            project.entry().toAbsolutePath().toString(),
            "the adapted async positions' dynamic-source control");
    }

    // =========================================================================
    // The JVM lane
    // =========================================================================

    private record JvmArtifacts(Project project, Path classes, String className) {
    }

    private static JvmArtifacts compileJvm(Path root, String fixture)
            throws Exception {
        return compileJvmProject(writeProject(root, fixture),
            "jvm:" + fixture, List.of());
    }

    /** One JVM artifact set over an explicit project and extra host sources. */
    private static JvmArtifacts compileJvmProject(Project project, String label,
            List<Path> extraSources) throws Exception {
        return compileJvmProject(project, label, extraSources, Map.of());
    }

    private static JvmArtifacts compileJvmProject(Project project, String label,
            List<Path> extraSources, Map<String, String> externals)
            throws Exception {
        Map<String, String> effectiveExternals = new LinkedHashMap<>(externals);
        boolean mutation = Files.exists(project.src().resolve("bindings/signature_mutation.d.deal"));
        if (mutation) effectiveExternals.put("host/signature_mutation", "bindings/signature_mutation.d.deal");
        assertProductionEmission(productionCompile(project, Backend.JVM,
            effectiveExternals), label);
        String className = JvmNames.classNameFor(ENTRY_MODULE);
        Path artifact = project.out().resolve(className + ".java");
        check(Files.isRegularFile(artifact), "jvm: the production compile stages "
            + "the entry class " + artifact);
        Path classes = project.root().resolve("classes");
        Files.createDirectories(classes);
        List<String> command = new java.util.ArrayList<>(List.of("javac",
            "--release", "25", "-proc:none", "-cp", absoluteClasspath(), "-d",
            classes.toString(), artifact.toAbsolutePath().toString()));
        for (Path extra : extraSources) {
            command.add(extra.toAbsolutePath().toString());
        }
        if (mutation) command.add(project.src().resolve("host/signature_mutation.java").toAbsolutePath().toString());
        ProcessOutcome javac = runProcess(command, project.out());
        checkEq(0, javac.exitCode(), "jvm: the emitted artifact compiles with "
            + "javac --release 25 -proc:none: " + javac.stdout() + javac.stderr());
        return new JvmArtifacts(project, classes, className);
    }

    private static void testJvmPositions(Path root, ExpectedExecution expected)
            throws Exception {
        System.out.println("-- the JVM position corpus under javac and java --");
        JvmArtifacts artifacts = compileJvm(root, POSITIONS);
        assertExecution("jvm", runProcess(List.of("java", "-cp",
            artifacts.classes() + File.pathSeparator + absoluteClasspath(),
            artifacts.className()), artifacts.project().out()), expected);
    }

    private static void testJvmBoundaries(Path root, ExpectedExecution expected)
            throws Exception {
        System.out.println("-- the JVM boundary corpus under javac and java --");
        JvmArtifacts artifacts = compileJvm(root, BOUNDARIES);
        assertExecution("jvm", runProcess(List.of("java", "-cp",
            artifacts.classes() + File.pathSeparator + absoluteClasspath(),
            artifacts.className()), artifacts.project().out()), expected);
    }

    private static void testJvmAsyncPositions(Path root) throws Exception {
        System.out.println("-- the JVM async position corpus through the "
            + "production async-export invoker --");
        JvmArtifacts artifacts = compileJvm(root, ASYNC_POSITIONS);
        Path entryClass = artifacts.classes().resolve(
            artifacts.className() + ".class");
        JvmAsyncExportInvoker.Result result = new JvmAsyncExportInvoker().invoke(
            new AsyncExportInvocationRequest(entryClass,
                "test_arity_extension_async", "number"));
        check(result instanceof JvmAsyncExportInvoker.Result.Value,
            "jvm: the adapted async positions complete a value: " + result);
        if (result instanceof JvmAsyncExportInvoker.Result.Value value) {
            checkEq(Double.parseDouble(ASYNC_COMPLETION), Double.parseDouble(value.valueJson()), "jvm: the adapted async "
                + "completion (one-to-two 101, zero-to-one 7, awaited 102, sync 4 "
                + "and the ignored-argument 106)");
        }
    }

    private static void testJvmDynamicSource(Path root) throws Exception {
        System.out.println("-- the JVM dynamic-source control's own terminal --");
        JvmArtifacts artifacts = compileJvm(root, DYNAMIC_SOURCE);
        ProcessOutcome run = runProcess(List.of("java", "-cp",
            artifacts.classes() + File.pathSeparator + absoluteClasspath(),
            artifacts.className()), artifacts.project().out());
        checkEq(1, run.exitCode(), "jvm: the dynamic-source control exits one: "
            + run.stdout() + run.stderr());
        checkEq("DEAL_ERROR_CODE: E8010\n", run.stdout(), "jvm: the "
            + "dynamic-source control's own terminal transcript");
        checkEq("", run.stderr(), "jvm: the dynamic-source control's stderr");
    }

    private static void testJvmDynamicSourceAsync(Path root) throws Exception {
        System.out.println("-- the JVM dynamic-source failure tuple through the "
            + "async-export lane --");
        JvmArtifacts artifacts = compileJvm(root, DYNAMIC_SOURCE_ASYNC);
        Path entryClass = artifacts.classes().resolve(
            artifacts.className() + ".class");
        JvmAsyncExportInvoker.Result result = new JvmAsyncExportInvoker().invoke(
            new AsyncExportInvocationRequest(entryClass,
                "test_arity_extension_dynamic_source_async_e8010", "number"));
        assertDealError("jvm", result instanceof JvmAsyncExportInvoker.Result.DealError
                ? (JvmAsyncExportInvoker.Result.DealError) result : null,
            artifacts.project().entry().toAbsolutePath().toString(),
            "the adapted async positions' dynamic-source control");
    }

    // =========================================================================
    // The JS lane
    // =========================================================================

    private static void testJsPositions(Path root, ExpectedExecution expected)
            throws Exception {
        System.out.println("-- the JS position corpus under node --");
        Project project = writeProject(root, POSITIONS);
        assertProductionEmission(productionCompile(project, Backend.JS), "js:"
            + POSITIONS);
        assertExecution("js",
            runProcess(List.of("node", ENTRY_MODULE + ".js"), project.out()),
            expected);
    }

    private static void testJsBoundaries(Path root, ExpectedExecution expected)
            throws Exception {
        System.out.println("-- the JS boundary corpus under node --");
        Project project = writeProject(root, BOUNDARIES);
        assertProductionEmission(productionCompile(project, Backend.JS), "js:"
            + BOUNDARIES);
        assertExecution("js", runProcess(List.of("node", ENTRY_MODULE + ".js"),
            project.out()), expected);
    }

    /**
     * The async corpus under {@code node}: the artifact's own export
     * surface is awaited exactly like the shared lane contract does, so
     * the completion value and the fixture's own transcript are observed
     * once.
     */
    private static void testJsAsyncPositions(Path root) throws Exception {
        System.out.println("-- the JS async position corpus under node --");
        Project project = writeProject(root, ASYNC_POSITIONS);
        assertProductionEmission(productionCompile(project, Backend.JS), "js:"
            + ASYNC_POSITIONS);
        writeFile(project.out(), "AsyncDriver.js", """
            "use strict";
            const $entry = require("./main.js");
            (async () => {
              const $target = $entry["test_arity_extension_async"];
              const $value = await $target.$f();
              process.stdout.write("VALUE " + $value + "\\n");
            })().catch(($e) => {
              process.stdout.write("ERROR " + ($e && $e.message) + "\\n");
              process.exitCode = 1;
            });
            """);
        ProcessOutcome run = runProcess(List.of("node", "AsyncDriver.js"),
            project.out());
        checkEq(0, run.exitCode(), "js: the async corpus driver exits zero: "
            + run.stdout() + run.stderr());
        checkEq("ASYNC OK\nVALUE " + ASYNC_COMPLETION + "\n", run.stdout(),
            "js: the adapted async completion and the fixture's own transcript");
    }

    private static void testJsDynamicSource(Path root) throws Exception {
        System.out.println("-- the JS dynamic-source control's own terminal --");
        Project project = writeProject(root, DYNAMIC_SOURCE);
        assertProductionEmission(productionCompile(project, Backend.JS), "js:"
            + DYNAMIC_SOURCE);
        ProcessOutcome run = runProcess(List.of("node", ENTRY_MODULE + ".js"),
            project.out());
        checkEq(1, run.exitCode(), "js: the dynamic-source control exits one: "
            + run.stdout() + run.stderr());
        checkEq("", run.stdout(), "js: the dynamic-source control's stdout");
        checkEq("DEAL_ERROR_CODE: E8010 " + E8010_MESSAGE + " at "
                + project.entry().toAbsolutePath() + ":5:22\n", run.stderr(),
            "js: the dynamic-source control's own terminal transcript");
    }

    private static void testJsDynamicSourceAsync(Path root) throws Exception {
        System.out.println("-- the JS dynamic-source failure tuple through the "
            + "artifact's export surface --");
        Project project = writeProject(root, DYNAMIC_SOURCE_ASYNC);
        assertProductionEmission(productionCompile(project, Backend.JS), "js:"
            + DYNAMIC_SOURCE_ASYNC);
        writeFile(project.out(), "ErrorDriver.js", """
            "use strict";
            const $entry = require("./main.js");
            (async () => {
              const $target = $entry["test_arity_extension_dynamic_source_async_e8010"];
              try {
                await $target.$f();
                process.stdout.write("NO_ERROR\\n");
                process.exitCode = 1;
              } catch ($e) {
                const $tuple = ["code", "message", "expected", "actual", "file",
                  "line", "column"].map(($k) => $k + "=" + String($e[$k]));
                process.stdout.write("TUPLE " + $tuple.join(" ") + "\\n");
              }
            })();
            """);
        ProcessOutcome run = runProcess(List.of("node", "ErrorDriver.js"),
            project.out());
        checkEq(0, run.exitCode(), "js: the error driver observes the tuple: "
            + run.stdout() + run.stderr());
        checkEq("TUPLE code=E8010 message=" + E8010_MESSAGE + " expected="
                + E8010_EXPECTED + " actual=" + E8010_ACTUAL + " file="
                + project.entry().toAbsolutePath() + " line=5 column=22\n",
            run.stdout(), "js: the dynamic-source control's exact failure tuple");
    }

    private static void testLuaJitAdapterValuedSource(Path root,
            ExpectedExecution expected) throws Exception {
        System.out.println("-- the LuaJIT adapter-valued-source corpus --");
        Project project = writeProject(root, ADAPTER_VALUED_SOURCE);
        assertProductionEmission(productionCompile(project, Backend.LUAJIT),
            "luajit:" + ADAPTER_VALUED_SOURCE);
        assertExecution("luajit", runProcess(
            List.of("luajit", ENTRY_MODULE + ".lua"), project.out()), expected);
    }

    private static void testJvmAdapterValuedSource(Path root,
            ExpectedExecution expected) throws Exception {
        System.out.println("-- the JVM adapter-valued-source corpus --");
        JvmArtifacts artifacts = compileJvm(root, ADAPTER_VALUED_SOURCE);
        assertExecution("jvm", runProcess(List.of("java", "-cp",
            artifacts.classes() + File.pathSeparator + absoluteClasspath(),
            artifacts.className()), artifacts.project().out()), expected);
    }

    private static void testJsAdapterValuedSource(Path root,
            ExpectedExecution expected) throws Exception {
        System.out.println("-- the JS adapter-valued-source corpus --");
        Project project = writeProject(root, ADAPTER_VALUED_SOURCE);
        assertProductionEmission(productionCompile(project, Backend.JS), "js:"
            + ADAPTER_VALUED_SOURCE);
        assertExecution("js", runProcess(List.of("node", ENTRY_MODULE + ".js"),
            project.out()), expected);
    }

    /**
     * The async adapter-valued-source corpus on LuaJIT: the assigned
     * adapter-valued binding is the wider awaited position's source, so the
     * adapter-over-async task resolves the cell's adapter value and the
     * dynamic source descent runs the innermost async body with the outer
     * level's leading-M projection. The completion is the innermost source's
     * own result, so a rejected or mis-projecting chain cannot pass.
     */
    private static void testLuaJitAsyncAdapterSource(Path root) throws Exception {
        System.out.println("-- the LuaJIT async adapter-valued-source corpus --");
        Project project = writeProject(root, ASYNC_ADAPTER_SOURCE);
        assertProductionEmission(productionCompile(project, Backend.LUAJIT),
            "luajit:" + ASYNC_ADAPTER_SOURCE);
        Path artifact = project.out().resolve(ENTRY_MODULE + ".lua");
        check(Files.isRegularFile(artifact), "luajit: the async adapter-source "
            + "corpus stages the entry chunk " + artifact);
        if (!Files.isRegularFile(artifact)) {
            return;
        }
        LuaJitAsyncExportInvoker.Result result = new LuaJitAsyncExportInvoker()
            .invoke(new AsyncExportInvocationRequest(artifact,
                ASYNC_ADAPTER_SOURCE_EXPORT, "number"));
        assertAsyncAdapterSourceCompletion("luajit",
            result instanceof LuaJitAsyncExportInvoker.Result.Value value
                ? value.valueJson() : null, result);
    }

    private static void testJvmAsyncAdapterSource(Path root) throws Exception {
        System.out.println("-- the JVM async adapter-valued-source corpus --");
        JvmArtifacts artifacts = compileJvm(root, ASYNC_ADAPTER_SOURCE);
        Path entryClass = artifacts.classes().resolve(
            artifacts.className() + ".class");
        JvmAsyncExportInvoker.Result result = new JvmAsyncExportInvoker().invoke(
            new AsyncExportInvocationRequest(entryClass,
                ASYNC_ADAPTER_SOURCE_EXPORT, "number"));
        assertAsyncAdapterSourceCompletion("jvm",
            result instanceof JvmAsyncExportInvoker.Result.Value value
                ? value.valueJson() : null, result);
    }

    /**
     * The async adapter-valued-source corpus under {@code node}: the
     * artifact's own export surface is awaited exactly like the shared lane
     * contract does, so the fixture's own transcript and the completion value
     * are observed once.
     */
    private static void testJsAsyncAdapterSource(Path root) throws Exception {
        System.out.println("-- the JS async adapter-valued-source corpus under "
            + "node --");
        Project project = writeProject(root, ASYNC_ADAPTER_SOURCE);
        assertProductionEmission(productionCompile(project, Backend.JS), "js:"
            + ASYNC_ADAPTER_SOURCE);
        writeFile(project.out(), "AsyncDriver.js", """
            "use strict";
            const $entry = require("./main.js");
            (async () => {
              const $target = $entry["%s"];
              const $value = await $target.$f();
              process.stdout.write("VALUE " + $value + "\\n");
            })().catch(($e) => {
              process.stdout.write("ERROR " + ($e && $e.message) + "\\n");
              process.exitCode = 1;
            });
            """.formatted(ASYNC_ADAPTER_SOURCE_EXPORT));
        ProcessOutcome run = runProcess(List.of("node", "AsyncDriver.js"),
            project.out());
        checkEq(0, run.exitCode(), "js: the async adapter-source driver exits "
            + "zero: " + run.stdout() + run.stderr());
        checkEq("ASYNC ADAPTER SOURCE OK\nVALUE " + ASYNC_ADAPTER_SOURCE_COMPLETION
            + "\n", run.stdout(), "js: the adapter chain's own transcript and "
            + "completion");
    }

    private static void assertAsyncAdapterSourceCompletion(String lane,
            String valueJson, Object result) {
        checkEq(Double.parseDouble(ASYNC_ADAPTER_SOURCE_COMPLETION), Double.parseDouble(valueJson), lane + ": the async "
            + "adapter-valued-source completion (the assigned one-to-two "
            + "chain, the exact control and the awaited zero-to-one adapter "
            + "value all resolve the innermost source's 41): " + result);
    }

    /**
     * The LuaJIT conversion-shadow corpus: every position's adapter is built
     * over the shadowing binding the position resolves, so no adapter of the
     * corpus carries the seeded intrinsic identity, and the run's own exact
     * values prove the shadow — never the conversion — ran at each position.
     */
    private static void testLuaJitConversionShadow(Path root,
            ExpectedExecution expected) throws Exception {
        System.out.println("-- the LuaJIT conversion-shadow corpus and its real "
            + "luajit run --");
        Project project = writeProject(root, CONVERSION_SHADOW);
        assertProductionEmission(productionCompile(project, Backend.LUAJIT),
            "luajit:" + CONVERSION_SHADOW);
        Path artifact = project.out().resolve(ENTRY_MODULE + ".lua");
        check(Files.isRegularFile(artifact), "luajit: the conversion-shadow "
            + "compile stages the entry chunk " + artifact);
        if (Files.isRegularFile(artifact)) {
            String chunk = Files.readString(artifact, StandardCharsets.UTF_8);
            checkEq(CONVERSION_SHADOW_ADAPTERS,
                countOccurrences(chunk, "[__dealCarrierKeys.mode] = "),
                "luajit: the corpus emits one adapter per shadowed position");
            check(!chunk.contains(", __value = __intrinsicFn("), "luajit: no "
                + "conversion-shadow adapter is built over the seeded intrinsic "
                + "identity");
        }
        assertExecution("luajit", runProcess(
            List.of("luajit", ENTRY_MODULE + ".lua"), project.out()), expected);
    }

    /**
     * The JVM conversion-shadow corpus: the emitted artifact's adapters take
     * the shadowing binding's carrier (never {@code JvmRuntime.intrinsic}),
     * and the run's exact values prove the shadow ran at each position.
     */
    private static void testJvmConversionShadow(Path root,
            ExpectedExecution expected) throws Exception {
        System.out.println("-- the JVM conversion-shadow corpus --");
        JvmArtifacts artifacts = compileJvm(root, CONVERSION_SHADOW);
        Path emitted = artifacts.project().out().resolve(
            artifacts.className() + ".java");
        if (Files.isRegularFile(emitted)) {
            String source = Files.readString(emitted, StandardCharsets.UTF_8);
            String intrinsicOperand = null;
            for (String line : source.split("\n", -1)) {
                if (line.contains("__iav_") && line.contains(" = JvmRuntime.intrinsic(")) {
                    intrinsicOperand = line.trim();
                    break;
                }
            }
            check(intrinsicOperand == null, "jvm: no conversion-shadow adapter is "
                + "built over the seeded intrinsic identity: " + intrinsicOperand);
        }
        assertExecution("jvm", runProcess(List.of("java", "-cp",
            artifacts.classes() + File.pathSeparator + absoluteClasspath(),
            artifacts.className()), artifacts.project().out()), expected);
    }

    /**
     * The JS conversion-shadow corpus: the emitted wrapper's source is the
     * position's own binding value, and the run's exact values prove the
     * shadow ran at each position.
     */
    private static void testJsConversionShadow(Path root,
            ExpectedExecution expected) throws Exception {
        System.out.println("-- the JS conversion-shadow corpus --");
        Project project = writeProject(root, CONVERSION_SHADOW);
        assertProductionEmission(productionCompile(project, Backend.JS),
            "js:" + CONVERSION_SHADOW);
        assertExecution("js", runProcess(List.of("node", ENTRY_MODULE + ".js"),
            project.out()), expected);
    }

    /**
     * The LuaJIT assignment-expression-source corpus: an assignment
     * expression used as a value produces the target-typed value its own
     * assignment position committed, so the corpus carries exactly one
     * adapter per adapting position — never a second adapter over an already
     * target-typed produced value — and the run's own exact values prove the
     * produced value, not the shorter right-hand side, reached each position.
     */
    private static void testLuaJitAssignmentSource(Path root,
            ExpectedExecution expected) throws Exception {
        System.out.println("-- the LuaJIT assignment-expression-source corpus and "
            + "its real luajit run --");
        Project project = writeProject(root, ASSIGNMENT_SOURCE);
        assertProductionEmission(productionCompile(project, Backend.LUAJIT),
            "luajit:" + ASSIGNMENT_SOURCE);
        Path artifact = project.out().resolve(ENTRY_MODULE + ".lua");
        check(Files.isRegularFile(artifact), "luajit: the assignment-source compile "
            + "stages the entry chunk " + artifact);
        if (Files.isRegularFile(artifact)) {
            String chunk = Files.readString(artifact, StandardCharsets.UTF_8);
            checkEq(ASSIGNMENT_SOURCE_ADAPTERS,
                countOccurrences(chunk, "[__dealCarrierKeys.mode] = "),
                "luajit: the corpus emits exactly one adapter per adapting "
                    + "position (no second adapter over a produced value)");
        }
        assertExecution("luajit", runProcess(
            List.of("luajit", ENTRY_MODULE + ".lua"), project.out()), expected);
    }

    /**
     * The JVM assignment-expression-source corpus: the emitted artifact
     * carries exactly one adapter per adapting position, and the run's own
     * exact values prove the produced value reached each position.
     */
    private static void testJvmAssignmentSource(Path root,
            ExpectedExecution expected) throws Exception {
        System.out.println("-- the JVM assignment-expression-source corpus --");
        JvmArtifacts artifacts = compileJvm(root, ASSIGNMENT_SOURCE);
        Path emitted = artifacts.project().out().resolve(
            artifacts.className() + ".java");
        if (Files.isRegularFile(emitted)) {
            String source = Files.readString(emitted, StandardCharsets.UTF_8);
            checkEq(ASSIGNMENT_SOURCE_ADAPTERS,
                countOccurrences(source, "new JvmRuntime.AdapterValue("),
                "jvm: the corpus emits exactly one adapter per adapting position "
                    + "(no second adapter over a produced value)");
        }
        assertExecution("jvm", runProcess(List.of("java", "-cp",
            artifacts.classes() + File.pathSeparator + absoluteClasspath(),
            artifacts.className()), artifacts.project().out()), expected);
    }

    /**
     * The JS assignment-expression-source corpus: the emitted wrappers are
     * exactly one per adapting position, and the run's own exact values
     * prove the produced value reached each position.
     */
    private static void testJsAssignmentSource(Path root,
            ExpectedExecution expected) throws Exception {
        System.out.println("-- the JS assignment-expression-source corpus --");
        Project project = writeProject(root, ASSIGNMENT_SOURCE);
        assertProductionEmission(productionCompile(project, Backend.JS),
            "js:" + ASSIGNMENT_SOURCE);
        Path artifact = project.out().resolve(ENTRY_MODULE + ".js");
        if (Files.isRegularFile(artifact)) {
            String chunk = Files.readString(artifact, StandardCharsets.UTF_8);
            checkEq(ASSIGNMENT_SOURCE_ADAPTERS,
                countOccurrences(chunk, ".$src = "),
                "js: the corpus emits exactly one adapter per adapting position "
                    + "(no second adapter over a produced value)");
        }
        assertExecution("js", runProcess(List.of("node", ENTRY_MODULE + ".js"),
            project.out()), expected);
    }

    /**
     * The occurrences of one fixed text in an emitted artifact.
     */
    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    private static void testLuaJitHostParameter(Path root,
            ExpectedExecution expected) throws Exception {
        System.out.println("-- the LuaJIT host-declared parameter corpus --");
        Project project = writeHostParameterProject(root);
        assertProductionEmission(productionCompile(project, Backend.LUAJIT,
            Map.of(HOST_SPECIFIER, "bindings/" + HOST_DECLARATION)),
            "luajit:host-parameter");
        writeFile(project.out(), "host/arity_extension_host.lua",
            HOST_LUA_SOURCE);
        assertExecution("luajit", runProcess(
            List.of("luajit", ENTRY_MODULE + ".lua"), project.out()), expected);
    }

    private static void testJvmHostParameter(Path root,
            ExpectedExecution expected) throws Exception {
        System.out.println("-- the JVM host-declared parameter corpus --");
        Project project = writeHostParameterProject(root);
        Path hostSource = project.root().resolve("HostArity_extension_host.java");
        writeFile(project.root(), "HostArity_extension_host.java",
            HOST_JAVA_SOURCE);
        JvmArtifacts artifacts = compileJvmProject(project,
            "jvm:host-parameter", List.of(hostSource),
            Map.of(HOST_SPECIFIER, "bindings/" + HOST_DECLARATION));
        assertExecution("jvm", runProcess(List.of("java", "-cp",
            artifacts.classes() + File.pathSeparator + absoluteClasspath(),
            artifacts.className()), artifacts.project().out()), expected);
    }

    private static void testJsHostParameter(Path root,
            ExpectedExecution expected) throws Exception {
        System.out.println("-- the JS host-declared parameter corpus --");
        Project project = writeHostParameterProject(root);
        assertProductionEmission(productionCompile(project, Backend.JS,
            Map.of(HOST_SPECIFIER, "bindings/" + HOST_DECLARATION)), "js:host-parameter");
        writeFile(project.out(), "host/arity_extension_host.js",
            HOST_JS_SOURCE);
        assertExecution("js", runProcess(List.of("node", ENTRY_MODULE + ".js"),
            project.out()), expected);
    }

    private static void testLuaJitHostStdlib(Path root,
            ExpectedExecution expected) throws Exception {
        System.out.println("-- the LuaJIT host-invoked cataloged stdlib adapter --");
        Project project = writeProjectSource(root, HOST_STDLIB_SOURCE,
            Map.of(HOST_SPECIFIER, "bindings/" + HOST_DECLARATION));
        assertProductionEmission(productionCompile(project, Backend.LUAJIT,
            Map.of(HOST_SPECIFIER, "bindings/" + HOST_DECLARATION)),
            "luajit:host-stdlib");
        writeFile(project.out(), "host/arity_extension_host.lua",
            HOST_LUA_SOURCE);
        assertExecution("luajit", runProcess(
            List.of("luajit", ENTRY_MODULE + ".lua"), project.out()), expected);
    }

    private static void testJvmHostStdlib(Path root,
            ExpectedExecution expected) throws Exception {
        System.out.println("-- the JVM host-invoked cataloged stdlib adapter --");
        Project project = writeProjectSource(root, HOST_STDLIB_SOURCE,
            Map.of(HOST_SPECIFIER, "bindings/" + HOST_DECLARATION));
        Path hostSource = project.root().resolve("HostArity_extension_host.java");
        writeFile(project.root(), "HostArity_extension_host.java",
            HOST_JAVA_SOURCE);
        JvmArtifacts artifacts = compileJvmProject(project,
            "jvm:host-stdlib", List.of(hostSource),
            Map.of(HOST_SPECIFIER, "bindings/" + HOST_DECLARATION));
        assertExecution("jvm", runProcess(List.of("java", "-cp",
            artifacts.classes() + File.pathSeparator + absoluteClasspath(),
            artifacts.className()), artifacts.project().out()), expected);
    }

    private static void testJsHostStdlib(Path root,
            ExpectedExecution expected) throws Exception {
        System.out.println("-- the JS host-invoked cataloged stdlib adapter --");
        Project project = writeProjectSource(root, HOST_STDLIB_SOURCE,
            Map.of(HOST_SPECIFIER, "bindings/" + HOST_DECLARATION));
        assertProductionEmission(productionCompile(project, Backend.JS,
            Map.of(HOST_SPECIFIER, "bindings/" + HOST_DECLARATION)),
            "js:host-stdlib");
        writeFile(project.out(), "host/arity_extension_host.js",
            HOST_JS_SOURCE);
        assertExecution("js", runProcess(List.of("node", ENTRY_MODULE + ".js"),
            project.out()), expected);
    }

    private static void testLuaJitHostDynamicSource(Path root) throws Exception {
        System.out.println("-- the LuaJIT host-parameter dynamic-source control --");
        Project project = writeProjectSource(root, HOST_DYNAMIC_SOURCE,
            Map.of(HOST_SPECIFIER, "bindings/" + HOST_DECLARATION));
        assertProductionEmission(productionCompile(project, Backend.LUAJIT,
            Map.of(HOST_SPECIFIER, "bindings/" + HOST_DECLARATION)),
            "luajit:host-dynamic-source");
        writeFile(project.out(), "host/arity_extension_host.lua",
            HOST_LUA_SOURCE);
        ProcessOutcome run = runProcess(
            List.of("luajit", ENTRY_MODULE + ".lua"), project.out());
        checkEq(1, run.exitCode(), "luajit: the host dynamic-source control "
            + "exits one: " + run.stdout() + run.stderr());
        checkEq("DEAL_ERROR_CODE: E8010\n", run.stdout(), "luajit: the host "
            + "dynamic-source control's terminal transcript");
        checkEq("", run.stderr(), "luajit: the host dynamic-source "
            + "control's stderr");
    }

    private static void testJvmHostDynamicSource(Path root) throws Exception {
        System.out.println("-- the JVM host-parameter dynamic-source control --");
        Project project = writeProjectSource(root, HOST_DYNAMIC_SOURCE,
            Map.of(HOST_SPECIFIER, "bindings/" + HOST_DECLARATION));
        Path hostSource = project.root().resolve("HostArity_extension_host.java");
        writeFile(project.root(), "HostArity_extension_host.java",
            HOST_JAVA_SOURCE);
        JvmArtifacts artifacts = compileJvmProject(project,
            "jvm:host-dynamic-source", List.of(hostSource),
            Map.of(HOST_SPECIFIER, "bindings/" + HOST_DECLARATION));
        ProcessOutcome run = runProcess(List.of("java", "-cp",
            artifacts.classes() + File.pathSeparator + absoluteClasspath(),
            artifacts.className()), artifacts.project().out());
        checkEq(1, run.exitCode(), "jvm: the host dynamic-source control "
            + "exits one: " + run.stdout() + run.stderr());
        checkEq("DEAL_ERROR_CODE: E8010\n", run.stdout(), "jvm: the host "
            + "dynamic-source control's terminal transcript");
        checkEq("", run.stderr(), "jvm: the host dynamic-source "
            + "control's stderr");
    }

    private static void testJsHostDynamicSource(Path root) throws Exception {
        System.out.println("-- the JS host-parameter dynamic-source control --");
        Project project = writeProjectSource(root, HOST_DYNAMIC_SOURCE,
            Map.of(HOST_SPECIFIER, "bindings/" + HOST_DECLARATION));
        assertProductionEmission(productionCompile(project, Backend.JS,
            Map.of(HOST_SPECIFIER, "bindings/" + HOST_DECLARATION)),
            "js:host-dynamic-source");
        writeFile(project.out(), "host/arity_extension_host.js",
            HOST_JS_SOURCE);
        ProcessOutcome run = runProcess(List.of("node", ENTRY_MODULE + ".js"),
            project.out());
        checkEq(1, run.exitCode(), "js: the host dynamic-source control "
            + "exits one: " + run.stdout() + run.stderr());
        checkEq("", run.stdout(), "js: the host dynamic-source control's stdout");
        check(run.stderr().startsWith("DEAL_ERROR_CODE: E8010 parameter 1 type "
                + "mismatch: function signature mismatch: expected "
                + "(number,number)->number, got (number)->number at "),
            "js: the host dynamic-source control's exact failure tuple: "
                + run.stderr());
    }

    // =========================================================================
    // The callback return position
    // =========================================================================

    /**
     * The callback-entry return source: the exported callback returns a
     * compatible shorter function value, so its {@code DEAL_TO_HOST} return
     * cell is an adapted position exactly like a same-module
     * {@code FUNCTION_RETURN}.
     */
    private static final String CALLBACK_RETURN_SOURCE = """
        function one(x: number): number { return x; }

        export function cb(): (x: number, y: number) => number {
          return one;
        }

        export function main(): null {
          return null;
        }
        """;

    /**
     * Lowers one single-module project through the production frontend and
     * the one project lowering with the scenario's callback export set (the
     * production orchestrator's own emission names no callback export).
     */
    private static SemanticLowerer.ProjectLoweringResult lowerProjectSource(
            Path root, String source, java.util.Set<String> callbacks)
            throws Exception {
        writeFile(root, "deal.json",
            "{\"languageVersion\":\"1.5\",\"moduleRoots\":[\"src\"]}");
        writeFile(root, "src/" + ENTRY_MODULE + ".deal", source);
        Path src = root.resolve("src");
        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            src.resolve(ENTRY_MODULE + ".deal").toAbsolutePath(),
            root.resolve("out"), false, false, false, false, Backend.LUAJIT,
            Map.of(), List.of(src.toAbsolutePath()),
            Path.of("").toAbsolutePath(), null, productionInvocation());
        orchestrator.compile();
        CheckedProjectBuildResult built = orchestrator.checkedProject();
        RequirementManifestResult manifests = orchestrator.requirementManifests();
        check(built != null && !built.hasErrors() && built.input() != null
                && built.index() != null, "callback return: the production "
                + "frontend admits the project: "
                + (built == null ? "null" : built.diagnostics()));
        if (built == null || built.hasErrors() || built.input() == null
                || built.index() == null || manifests == null
                || manifests.hasErrors() || manifests.manifests() == null) {
            return null;
        }
        return SemanticLowerer.lowerProject(productionInvocation(),
            built.input(), built.index(), manifests.manifests(),
            orchestrator.hostDeclarationSurface(), Map.of(),
            BuiltinErrorDeclaration.synthesized(
                built.input().modules().get(0).ast().span()), callbacks);
    }

    /**
     * The callback return position's acceptance: a callback export that
     * returns a compatible shorter function value lowers clean, emits
     * exactly one {@code FUNCTION_ADAPT} over {@code one}, and the
     * callback's {@code DEAL_TO_HOST} return cell is that adapter's direct
     * consumer — the same RETURN-value agreement a {@code FUNCTION_RETURN}
     * cell carries.
     */
    private static void testCallbackReturnPosition(Path root) throws Exception {
        System.out.println("-- the callback export's return position --");
        SemanticLowerer.ProjectLoweringResult lowering = lowerProjectSource(
            root, CALLBACK_RETURN_SOURCE, java.util.Set.of("cb"));
        check(lowering != null && !lowering.hasErrors()
            && lowering.project() != null, "callback return: the project "
            + "lowering admits the callback return adapter: "
            + (lowering == null ? "null" : lowering.diagnostics()));
        if (lowering == null || lowering.hasErrors() || lowering.project() == null) {
            return;
        }
        int adapters = 0;
        boolean returnCell = false;
        boolean returnOp = false;
        for (LoweredModuleUnit unit : lowering.project().modules().values()) {
            Map<deal.semantic.ir.OpId, SemanticOp> byId = new LinkedHashMap<>();
            for (SemanticOp op : unit.ops()) {
                byId.put(op.opId(), op);
            }
            for (SemanticOp op : unit.ops()) {
                if (op.kind() != SemanticOpKind.FUNCTION_ADAPT
                        || !(op.payload()
                            instanceof KindPayload.FunctionAdaptPayload adapt)
                        || !(op.result() instanceof deal.semantic.ir.ValueId adapter)) {
                    continue;
                }
                adapters++;
                checkEq("(number)->number", adapt.sourceSignature().canonicalSpecText(),
                    "callback return: the adapter's source signature");
                checkEq("(number,number)->number", adapt.targetSignature().canonicalSpecText(),
                    "callback return: the adapter's target signature");
                for (SemanticOp cell : unit.ops()) {
                    if (cell.kind() != SemanticOpKind.BOUNDARY
                            || !(cell.payload()
                                instanceof KindPayload.BoundaryPayload boundary)
                            || boundary.kind() != BoundaryKind.DEAL_TO_HOST
                            || !adapter.equals(boundary.input())) {
                        continue;
                    }
                    returnCell = true;
                    deal.semantic.ir.OpId parentId = cell.origin().parentOpId();
                    SemanticOp parent = parentId == null ? null : byId.get(parentId);
                    if (parent != null && parent.kind() == SemanticOpKind.RETURN
                            && parent.payload()
                                instanceof KindPayload.ReturnPayload returned
                            && adapter.equals(returned.value())
                            && cell.opId().equals(returned.returnBoundaryOpId())) {
                        returnOp = true;
                    }
                }
            }
        }
        checkEq(1, adapters, "callback return: exactly one adapter is emitted "
            + "for the callback return position");
        check(returnCell, "callback return: the DEAL_TO_HOST return cell is the "
            + "adapter's direct consumer");
        check(returnOp, "callback return: the RETURN op returns the adapter "
            + "through its recorded return cell");
        testCallbackReturnExecution(root.resolve("callback-probe"), lowering);
    }

    /**
     * The callback return adapter on the emitted artifacts: the lowered
     * callback unit runs through the differential harness's three
     * consumers — the semantic oracle, the emitted LuaJIT artifact under
     * real luajit and the emitted JVM artifact under real java/javac — and
     * each returns the same working function value (the first observed
     * heap allocation).
     */
    private static void testCallbackReturnExecution(Path root,
            SemanticLowerer.ProjectLoweringResult lowering) throws Exception {
        System.out.println("-- the callback return adapter on the emitted "
            + "LuaJIT and JVM artifacts --");
        LoweredModuleUnit unit = lowering.project().modules().values().iterator().next();
        deal.semantic.ir.ValueId functionValue = null;
        for (SemanticOp op : unit.ops()) {
            if (op.payload() instanceof KindPayload.CallbackInvokePayload callback) {
                functionValue = callback.function();
            }
        }
        check(functionValue != null, "callback return: the emitted unit "
            + "records the CALLBACK_INVOKE entry");
        if (functionValue == null) {
            return;
        }
        Path workspace = Files.createTempDirectory(root.getFileName().toString());
        try {
            deal.test.SemanticDifferentialHarness.Verdict verdict =
                deal.test.SemanticDifferentialHarness.runCallback(unit,
                    unit.bodyTable(), functionValue, List.of(),
                    deal.test.SemanticDifferentialHarness.Expectation.success(
                        "the callback return position", List.of(), "ref:1"),
                    workspace);
            checkEq(3, verdict.runs().size(), "callback return: the emitted "
                + "artifacts produce the three consumers: " + verdict.failures());
            check(verdict.pass(), "callback return: the three-consumer verdict "
                + "passes: " + verdict.failures() + "\n" + verdict.report());
            for (deal.semantic.SemanticRuntimeModel.ConsumerRun run
                    : verdict.runs()) {
                check(run.terminal() instanceof
                        deal.semantic.SemanticRuntimeModel.Terminal.Success success
                        && "ref:1".equals(success.resultAtom()),
                    run.consumer() + ": the callback return publishes the one "
                        + "adapted function value: " + run.terminal());
            }
        } finally {
            deleteRecursively(workspace);
        }
    }

    /**
     * The one project lowering over an already-written corpus project: the
     * production frontend admits it and the one project lowering produces
     * the executable closure the consumers run.
     */
    private static SemanticLowerer.ProjectLoweringResult lowerCorpusProject(
            Project project) throws Exception {
        CompilationOrchestrator orchestrator = productionCompile(project,
            Backend.LUAJIT);
        CheckedProjectBuildResult built = orchestrator.checkedProject();
        RequirementManifestResult manifests = orchestrator.requirementManifests();
        check(built != null && !built.hasErrors() && built.input() != null
                && built.index() != null, "the parity project's frontend admits "
                + "it: " + (built == null ? "null" : built.diagnostics()));
        if (built == null || built.hasErrors() || built.input() == null
                || built.index() == null || manifests == null
                || manifests.hasErrors() || manifests.manifests() == null) {
            return null;
        }
        return SemanticLowerer.lowerProject(productionInvocation(), built.input(),
            built.index(), manifests.manifests(),
            orchestrator.hostDeclarationSurface(), Map.of(),
            BuiltinErrorDeclaration.synthesized(
                built.input().modules().get(0).ast().span()), java.util.Set.of());
    }

    /**
     * The adapter-valued-source chain on the oracle-driven parity case: the
     * corpus fixture is checked valid at every position, so the semantic
     * oracle must execute it exactly as the emitted artifacts do — no
     * "outside the statically-resolved slice" rejection, the same
     * event-for-event trace and the same success terminal. The oracle
     * descends each nested adapter level's own published source with its own
     * leading-M projection, admits the invoked value's target parameters and
     * reads the cell each adapter captured at creation. The fixture makes
     * that cell escape its creating invocation: a parameter cell assigned
     * after the adaptation (the {@code SHARED_CELL} source mode) is read by
     * the returned adapter and by a static adapter callee inside the escaped
     * closure, so the case covers the creation-identity resolution a static
     * callee needs — not only the value-carried arms that pass a loaded
     * adapter value.
     */
    private static void testNestedAdapterOracleParity(Path root) throws Exception {
        System.out.println("-- the adapter-valued-source corpus on the "
            + "oracle-driven parity case --");
        Project project = writeProject(root, ADAPTER_VALUED_SOURCE);
        SemanticLowerer.ProjectLoweringResult lowering =
            lowerCorpusProject(project);
        check(lowering != null && !lowering.hasErrors()
            && lowering.project() != null, "nested adapter: the corpus lowers "
            + "with zero diagnostics: "
            + (lowering == null ? "null" : lowering.diagnostics()));
        if (lowering == null || lowering.hasErrors() || lowering.project() == null) {
            return;
        }
        int sharedCellAdapters = 0;
        int staticAdapterCallees = 0;
        for (LoweredModuleUnit unit : lowering.project().modules().values()) {
            for (SemanticOp op : unit.ops()) {
                if (op.payload() instanceof KindPayload.FunctionAdaptPayload adapt
                        && adapt.mode() == CaptureMode.SHARED_CELL
                        && adapt.source() instanceof AdaptSourceRef.SharedCell) {
                    sharedCellAdapters++;
                }
                if (op.kind() == SemanticOpKind.CALL
                        && op.payload() instanceof KindPayload.CallPayload call
                        && call.callee()
                            instanceof KindPayload.CallCallee.Static staticCallee
                        && staticCallee.binding()
                            instanceof FunctionExecutionBinding.AdapterBinding) {
                    staticAdapterCallees++;
                }
            }
        }
        check(sharedCellAdapters >= 1, "nested adapter: the corpus carries a "
            + "SHARED_CELL adapter over the assigned parameter cell (the "
            + "escaping capture); got " + sharedCellAdapters);
        check(staticAdapterCallees >= 1, "nested adapter: the corpus carries a "
            + "static adapter callee inside the escaped closure (the "
            + "creation-identity resolution); got " + staticAdapterCallees);
        Path workspace = Files.createTempDirectory(root.getFileName().toString());
        try {
            deal.test.SemanticDifferentialHarness.Verdict verdict =
                deal.test.SemanticDifferentialHarness.runProject(
                    lowering.project(), lowering.tables(), lowering.registries(),
                    deal.test.SemanticDifferentialHarness.Expectation.success(
                        "the adapter-valued-source corpus",
                        List.of("OK"), "null"),
                    workspace);
            checkEq(3, verdict.runs().size(), "nested adapter: the parity case "
                + "produces the three consumers: " + verdict.failures());
            check(verdict.pass(), "nested adapter: the oracle and both shared "
                + "artifacts agree event-for-event: " + verdict.failures()
                + "\n" + verdict.report());
            for (deal.semantic.SemanticRuntimeModel.ConsumerRun run
                    : verdict.runs()) {
                check(run.terminal() instanceof
                        deal.semantic.SemanticRuntimeModel.Terminal.Success success
                        && "null".equals(success.resultAtom()),
                    run.consumer() + ": the chain completes the entry export "
                        + "with null: " + run.terminal());
            }
        } finally {
            deleteRecursively(workspace);
        }
    }

    /**
     * The async adapter-valued-source corpus on the oracle-driven async-entry
     * parity case: the adapted wider awaited position's source is the assigned
     * binding's cell, whose value is itself an adapter, so the lowered project
     * carries a runtime-resolved nested source start (the closed Dynamic async
     * arm) whose runtime class descent the oracle must execute exactly as the
     * emitted LuaJIT and JVM artifacts do — the same events, the same
     * transcript and the same completed {@code int} value.
     */
    private static void testAsyncAdapterSourceOracleParity(Path root)
            throws Exception {
        System.out.println("-- the async adapter-valued-source corpus on the "
            + "oracle-driven async-entry parity case --");
        Project project = writeProject(root, ASYNC_ADAPTER_SOURCE);
        SemanticLowerer.ProjectLoweringResult lowering =
            lowerCorpusProject(project);
        check(lowering != null && !lowering.hasErrors()
            && lowering.project() != null, "async adapter source: the corpus "
            + "lowers with zero diagnostics: "
            + (lowering == null ? "null" : lowering.diagnostics()));
        if (lowering == null || lowering.hasErrors()
                || lowering.project() == null) {
            return;
        }
        int sharedCellAdapters = 0;
        int dynamicNestedStarts = 0;
        for (LoweredModuleUnit unit : lowering.project().modules().values()) {
            for (SemanticOp op : unit.ops()) {
                if (op.payload() instanceof KindPayload.FunctionAdaptPayload adapt
                        && adapt.mode() == CaptureMode.SHARED_CELL
                        && adapt.source() instanceof AdaptSourceRef.SharedCell) {
                    sharedCellAdapters++;
                }
                if (op.kind() == SemanticOpKind.ASYNC_START
                        && op.payload()
                            instanceof KindPayload.AsyncStartPayload start
                        && start.parameterBoundaryMode()
                            == ParameterBoundaryMode.ELIDED_BY_ADAPTER
                        && start.callee()
                            instanceof KindPayload.CallCallee.Dynamic) {
                    dynamicNestedStarts++;
                }
            }
        }
        check(sharedCellAdapters >= 1, "async adapter source: the corpus "
            + "carries a SHARED_CELL adapter over the assigned binding cell; "
            + "got " + sharedCellAdapters);
        check(dynamicNestedStarts >= 1, "async adapter source: the wider "
            + "awaited position records the runtime-resolved nested source "
            + "start; got " + dynamicNestedStarts);
        Path workspace = Files.createTempDirectory(root.getFileName().toString());
        try {
            deal.test.SemanticDifferentialHarness.Verdict verdict =
                deal.test.SemanticDifferentialHarness.runAsyncEntry(
                    lowering.project(), lowering.tables(),
                    ASYNC_ADAPTER_SOURCE_EXPORT, List.of(),
                    deal.test.SemanticDifferentialHarness.Expectation.success(
                        "the async adapter-valued-source corpus",
                        List.of("ASYNC ADAPTER SOURCE OK"),
                        "num:" + Double.toHexString(Double.parseDouble(ASYNC_ADAPTER_SOURCE_COMPLETION))),
                    workspace, null);
            checkEq(3, verdict.runs().size(), "async adapter source: the parity "
                + "case produces the three consumers: " + verdict.failures());
            check(verdict.pass(), "async adapter source: the oracle and both "
                + "shared artifacts agree event-for-event: " + verdict.failures()
                + "\n" + verdict.report());
            for (deal.semantic.SemanticRuntimeModel.ConsumerRun run
                    : verdict.runs()) {
                check(run.terminal() instanceof
                        deal.semantic.SemanticRuntimeModel.Terminal.Success success
                        && ("num:" + Double.toHexString(Double.parseDouble(ASYNC_ADAPTER_SOURCE_COMPLETION)))
                            .equals(success.resultAtom()),
                    run.consumer() + ": the adapter chain completes the async "
                        + "export with the innermost source's 41: "
                        + run.terminal());
            }
        } finally {
            deleteRecursively(workspace);
        }
    }

    /**
     * The assignment-expression-source oracle-parity subject: the same
     * produced-value shapes in a compact project — a call argument over an
     * assignment expression (exact and widened), a return position and an
     * escaping adapter over the assignment expression that produced it, so
     * the oracle must descend the creation-time captured source after the
     * creating invocation returns.
     */
    private static final String ASSIGNMENT_SOURCE_ORACLE_SOURCE = """
        function one(x: number): number { return x; }

        function pairExact(x: number, y: number): number { return x + y; }

        function needPair(f: (x: number, y: number) => number): number { return f(5, 6); }

        function needTriple(f: (x: number, y: number, z: number) => number): number {
          return f(5, 6, 7);
        }

        function onceReturned(): (x: number, y: number) => number {
          let f: (x: number, y: number) => number = pairExact;
          return (f = one);
        }

        function returnedWide(): (x: number, y: number, z: number) => number {
          let f: (x: number, y: number) => number = pairExact;
          return (f = one);
        }

        export function main(): null {
          let f: (x: number, y: number) => number = pairExact;
          let argument: number = needPair((f = one));
          let widenedArgument: number = needTriple((f = one));
          let escaped: (x: number, y: number) => number = onceReturned();
          let escapingArgument: number = escaped(7, 8);
          let returned: number = returnedWide()(5, 6, 7);
          if (argument !== 5 || widenedArgument !== 5 || escapingArgument !== 7
              || returned !== 5) {
            throw { code: "TEST_FAIL", message: "assignment-source value" };
          }
          return null;
        }
        """;

    /**
     * The assignment-expression-source corpus on the oracle-driven parity
     * case: the produced target-typed value an assignment position commits is
     * the position's source, so the semantic oracle executes exactly the
     * values the emitted artifacts do — the assignment's own effect once at a
     * value position, the wider position's single adaptation of the produced
     * value and the post-state the produced value carries.
     */
    private static void testAssignmentSourceOracleParity(Path root) throws Exception {
        System.out.println("-- the assignment-expression-source shapes on the "
            + "oracle-driven parity case --");
        SemanticLowerer.ProjectLoweringResult lowering = lowerProjectSource(root,
            ASSIGNMENT_SOURCE_ORACLE_SOURCE, java.util.Set.of());
        check(lowering != null && !lowering.hasErrors()
            && lowering.project() != null, "assignment source: the parity subject "
            + "lowers with zero diagnostics: "
            + (lowering == null ? "null" : lowering.diagnostics()));
        if (lowering == null || lowering.hasErrors() || lowering.project() == null) {
            return;
        }
        Path workspace = Files.createTempDirectory(root.getFileName().toString());
        try {
            deal.test.SemanticDifferentialHarness.Verdict verdict =
                deal.test.SemanticDifferentialHarness.runProject(
                    lowering.project(), lowering.tables(), lowering.registries(),
                    deal.test.SemanticDifferentialHarness.Expectation.success(
                        "the assignment-expression-source shapes", List.of(), "null"),
                    workspace);
            checkEq(3, verdict.runs().size(), "assignment source: the parity case "
                + "produces the three consumers: " + verdict.failures());
            check(verdict.pass(), "assignment source: the oracle and both shared "
                + "artifacts agree event-for-event: " + verdict.failures()
                + "\n" + verdict.report());
            for (deal.semantic.SemanticRuntimeModel.ConsumerRun run
                    : verdict.runs()) {
                check(run.terminal() instanceof
                        deal.semantic.SemanticRuntimeModel.Terminal.Success success
                        && "null".equals(success.resultAtom()),
                    run.consumer() + ": the assignment-source shapes complete the "
                        + "entry export with null: " + run.terminal());
            }
        } finally {
            deleteRecursively(workspace);
        }
    }

    // =========================================================================
    // The shared failure tuple
    // =========================================================================

    private static void assertDealError(String lane,
            LuaJitAsyncExportInvoker.Result.DealError error, String expectedFile,
            String what) {
        check(error != null, lane + ": " + what + " raises the pinned DEAL error");
        if (error == null) {
            return;
        }
        checkEq("E8010", error.code(), lane + ": the failure code");
        checkEq(E8010_MESSAGE, error.message(), lane + ": the failure message");
        checkEq(E8010_EXPECTED, error.expected(), lane + ": the expected token");
        checkEq(E8010_ACTUAL, error.actual(), lane + ": the actual token");
        checkEq(expectedFile, error.file(), lane + ": the failure's source file");
        checkEq(5, error.line(), lane + ": the declared parameter's line");
        checkEq(22, error.column(), lane + ": the declared parameter's column");
    }

    private static void assertDealError(String lane,
            JvmAsyncExportInvoker.Result.DealError error, String expectedFile,
            String what) {
        check(error != null, lane + ": " + what + " raises the pinned DEAL error");
        if (error == null) {
            return;
        }
        checkEq("E8010", error.code(), lane + ": the failure code");
        checkEq(E8010_MESSAGE, error.message(), lane + ": the failure message");
        checkEq(E8010_EXPECTED, error.expected(), lane + ": the expected token");
        checkEq(E8010_ACTUAL, error.actual(), lane + ": the actual token");
        checkEq(expectedFile, error.file(), lane + ": the failure's source file");
        checkEq(5, error.line(), lane + ": the declared parameter's line");
        checkEq(22, error.column(), lane + ": the declared parameter's column");
    }

    // =========================================================================
    // Main
    // =========================================================================

    public static void main(String[] args) throws Exception {
        System.out.println("=== Function Arity Extension Position Production Test "
            + "(ISSUE-0877) ===\n");
        Map<String, ExpectedExecution> positions = expectedByBackend(POSITIONS);
        Map<String, ExpectedExecution> asyncPositions =
            expectedByBackend(ASYNC_POSITIONS);
        Map<String, ExpectedExecution> boundaries =
            expectedByBackend(BOUNDARIES);
        Map<String, ExpectedExecution> dynamicSource =
            expectedByBackend(DYNAMIC_SOURCE);
        Map<String, ExpectedExecution> adapterValued =
            expectedByBackend(ADAPTER_VALUED_SOURCE);
        Map<String, ExpectedExecution> conversionShadow =
            expectedByBackend(CONVERSION_SHADOW);
        Map<String, ExpectedExecution> assignmentSource =
            expectedByBackend(ASSIGNMENT_SOURCE);
        Map<String, ExpectedExecution> asyncAdapterSource =
            expectedByBackend(ASYNC_ADAPTER_SOURCE);
        Map<String, ExpectedExecution> hostParameter = hostParameterExpected();
        expectedByBackend(DYNAMIC_SOURCE_ASYNC);
        for (Map<String, ExpectedExecution> expected : List.of(positions,
                asyncPositions, boundaries, dynamicSource, adapterValued,
                conversionShadow, assignmentSource, asyncAdapterSource,
                hostParameter)) {
            for (ExpectedExecution execution : expected.values()) {
                checkEq("", new String(execution.stderr(), StandardCharsets.UTF_8),
                    "every pinned lane carries an empty stderr");
            }
        }
        checkEq("OK\n", new String(positions.get("luajit").stdout(),
            StandardCharsets.UTF_8), "the position corpus pins its marker");
        checkEq(0, positions.get("luajit").exitCode(),
            "the position corpus pins its exit code");
        checkEq("ASYNC OK\n", new String(asyncPositions.get("js").stdout(),
            StandardCharsets.UTF_8), "the async corpus pins its marker");
        checkEq("ASYNC ADAPTER SOURCE OK\n", new String(
            asyncAdapterSource.get("js").stdout(), StandardCharsets.UTF_8),
            "the async adapter-valued-source corpus pins its marker");
        checkEq(1, dynamicSource.get("luajit").exitCode(),
            "the dynamic-source control pins its nonzero exit code");
        for (String fixture : List.of(DYNAMIC_SOURCE, DYNAMIC_SOURCE_ASYNC)) {
            for (String lane : List.of("luajit", "jvm", "js")) {
                SidecarExpectations.RuntimeExpectation pinned =
                    sidecar(fixture).expectationFor(lane);
                check(pinned instanceof
                        SidecarExpectations.RuntimeExpectation.Executed,
                    fixture + ": the sidecar pins the " + lane + " lane");
                if (!(pinned instanceof
                        SidecarExpectations.RuntimeExpectation.Executed executed)) {
                    continue;
                }
                checkEq("runtime-error", executed.mode(), fixture
                    + ": the sidecar pins the runtime-error mode");
                checkEq(1, executed.exitCode(), fixture + ": the sidecar pins "
                    + "the nonzero exit code");
                checkEq("", new String(executed.stderr(), StandardCharsets.UTF_8),
                    fixture + ": the sidecar pins an empty stderr");
                var error = executed.error();
                check(error != null, fixture + ": the sidecar pins the error");
                if (error == null) {
                    continue;
                }
                checkEq("E8010", error.code(), fixture
                    + ": the sidecar pins the failure code");
                String message = E8010_MESSAGE;
                String expectedToken = E8010_EXPECTED;
                String actualToken = E8010_ACTUAL;
                int column = 22;
                checkEq(message, error.message(), fixture
                    + ": the sidecar pins the failure message");
                checkEq(expectedToken, error.expected().orElse(null), fixture
                    + ": the sidecar pins the expected token");
                checkEq(actualToken, error.actual().orElse(null), fixture
                    + ": the sidecar pins the actual token");
                checkEq(9, error.line(), fixture
                    + ": the sidecar pins the corpus line");
                checkEq(column, error.column(), fixture
                    + ": the sidecar pins the corpus column");
            }
        }

        Path root = Files.createTempDirectory("deal-arity-extension-");
        try {
            testLuaJitPositions(root.resolve("luajit-positions"),
                positions.get("luajit"));
            testLuaJitBoundaries(root.resolve("luajit-boundaries"),
                boundaries.get("luajit"));
            testLuaJitAsyncPositions(root.resolve("luajit-async"));
            testLuaJitDynamicSource(root.resolve("luajit-dynamic"));
            testLuaJitDynamicSourceAsync(root.resolve("luajit-dynamic-async"));
            testJvmPositions(root.resolve("jvm-positions"), positions.get("jvm"));
            testJvmBoundaries(root.resolve("jvm-boundaries"),
                boundaries.get("jvm"));
            testJvmAsyncPositions(root.resolve("jvm-async"));
            testJvmDynamicSource(root.resolve("jvm-dynamic"));
            testJvmDynamicSourceAsync(root.resolve("jvm-dynamic-async"));
            testJsPositions(root.resolve("js-positions"), positions.get("js"));
            testJsBoundaries(root.resolve("js-boundaries"),
                boundaries.get("js"));
            testJsAsyncPositions(root.resolve("js-async"));
            testJsDynamicSource(root.resolve("js-dynamic"));
            testJsDynamicSourceAsync(root.resolve("js-dynamic-async"));
            testLuaJitAdapterValuedSource(root.resolve("luajit-adapter-valued"),
                adapterValued.get("luajit"));
            testJvmAdapterValuedSource(root.resolve("jvm-adapter-valued"),
                adapterValued.get("jvm"));
            testJsAdapterValuedSource(root.resolve("js-adapter-valued"),
                adapterValued.get("js"));
            testLuaJitAsyncAdapterSource(
                root.resolve("luajit-async-adapter-source"));
            testJvmAsyncAdapterSource(root.resolve("jvm-async-adapter-source"));
            testJsAsyncAdapterSource(root.resolve("js-async-adapter-source"));
            testAsyncAdapterSourceOracleParity(
                root.resolve("async-adapter-source-parity"));
            testLuaJitConversionShadow(root.resolve("luajit-conversion-shadow"),
                conversionShadow.get("luajit"));
            testJvmConversionShadow(root.resolve("jvm-conversion-shadow"),
                conversionShadow.get("jvm"));
            testJsConversionShadow(root.resolve("js-conversion-shadow"),
                conversionShadow.get("js"));
            testLuaJitAssignmentSource(root.resolve("luajit-assignment-source"),
                assignmentSource.get("luajit"));
            testJvmAssignmentSource(root.resolve("jvm-assignment-source"),
                assignmentSource.get("jvm"));
            testJsAssignmentSource(root.resolve("js-assignment-source"),
                assignmentSource.get("js"));
            testLuaJitHostParameter(root.resolve("luajit-host-parameter"),
                hostParameter.get("luajit"));
            testJvmHostParameter(root.resolve("jvm-host-parameter"),
                hostParameter.get("jvm"));
            testJsHostParameter(root.resolve("js-host-parameter"),
                hostParameter.get("js"));
            testLuaJitHostStdlib(root.resolve("luajit-host-stdlib"),
                hostParameter.get("luajit"));
            testJvmHostStdlib(root.resolve("jvm-host-stdlib"),
                hostParameter.get("jvm"));
            testJsHostStdlib(root.resolve("js-host-stdlib"),
                hostParameter.get("js"));
            testLuaJitHostTargetParameter(root.resolve("luajit-host-target-parameter"));
            testJvmHostTargetParameter(root.resolve("jvm-host-target-parameter"));
            testJsHostTargetParameter(root.resolve("js-host-target-parameter"));
            testHostParameterEmissionFact(root.resolve("host-parameter-facts"));
            testCallbackReturnPosition(root.resolve("callback-return"));
            testNestedAdapterOracleParity(root.resolve("nested-adapter-parity"));
            testAssignmentSourceOracleParity(
                root.resolve("assignment-source-parity"));
            testLuaJitHostDynamicSource(root.resolve("luajit-host-dynamic"));
            testJvmHostDynamicSource(root.resolve("jvm-host-dynamic"));
            testJsHostDynamicSource(root.resolve("js-host-dynamic"));
        } finally {
            deleteRecursively(root);
        }
        System.out.println();
        System.out.println("Passed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
        System.out.println("=== Function Arity Extension Position Production Test "
            + "Passed ===");
    }
}
