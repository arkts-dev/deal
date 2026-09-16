package deal.test.conformance;

import deal.ast.ProgramNode;
import deal.codegen.jvm.JvmBackend;
import deal.test.ConformanceHarnessMetadata;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * JVM lane tests (ISSUE-0355 Verification): the JVM lane of the v1.2
 * differential gate executed through the gate core's dispatch/verdict
 * path (the G7 worker pool + the StructuredExpectationComparator), over
 * real corpus fixtures with their authored sidecars plus scratch
 * controlled-experiment fixtures and gate runs.
 *
 * <p>The suite pins the Shared Lane Contract surfaces:</p>
 * <ul>
 *   <li>Real fixtures (runtime-ok transcripts byte-exact with discarded
 *       results; the host triplet {@code nullreturn_ok.java}; async
 *       exports awaited to completion) produce matching verdicts.</li>
 *   <li>Real runtime-error fixtures produce the honest non-fabricated
 *       outcome: the real JVM DealError carries code/message with no
 *       origin yet (the per-class origin literals land with the epic's
 *       class leaves), so the lane reports the span-absent capture as a
 *       process failure naming the captured code, message and the
 *       missing span — the E8010/E8011 host-boundary fixtures carry the
 *       same codes the Lua lane pins, and the sanctioned span-less time
 *       fixture passes byte-exact.</li>
 *   <li>Invocation contract: declaration-order auto-invocation of the
 *       non-{@code $} zero-arity exports; return values discarded;
 *       {@code main} runs exactly once (the backend entry contract).</li>
 *   <li>Framing (G4.6): the canonical snapshot built by the shared
 *       {@link ErrorSnapshot} serializer (imported verbatim — the
 *       snapshot-construction probes inject a complete captured error
 *       through the documented transport seam and prove the serializer
 *       reuse, the sidecar-authoritative field set, the suppression of
 *       unpinned optionals, and the {@code sourceFile} normalization
 *       from the temp project root to the corpus-relative form).</li>
 *   <li>Compile-reject path (corpus C6): the lane reports a real
 *       rejection honestly with its actual diagnostic code, so the
 *       comparator reports the exact {@code COMPILE_REJECT_MISMATCH}
 *       code delta on a differently-coded rejection (never an
 *       infrastructure failure); a clean compile under a rejection pin
 *       is refused as {@code PROCESS_FAILURE}.</li>
 *   <li>Infrastructure: {@code ARTIFACT_MISSING} on a deleted generated
 *       artifact, {@code TOOL_MISSING} on a failed tool probe, an
 *       unmappable captured {@code file} emitted verbatim, compile
 *       failures as lane compile failures, and raw (non-DEAL) subprocess
 *       errors as process failures.</li>
 * </ul>
 *
 * <p><b>TEST DOUBLES:</b> the scratch {@link JvmLane} subclasses in this
 * file are controlled-experiment doubles (the task's Verification
 * probes) — the production {@code JvmLane} executes every real fixture.
 * Each double is flagged as such in the test code.</p>
 */
public class JvmLaneTest {

    private static int passed = 0;
    private static int failed = 0;

    /** The real corpus root (the scratch fixtures are header-free, so
     * raw == stripped coordinates for the dispatch probes). */
    private static final Path CORPUS_ROOT = Path.of("test", "conformance");

    public static void main(String[] args) throws Exception {
        System.out.println("=== JVM Lane Tests (ISSUE-0355) ===\n");

        realFixtureRuntimeOkIfElse();
        realFixtureHostNullReturnOk();
        realFixtureAsyncSimpleAwait();
        realRuntimeErrorHonestFailure();
        realCompanionThrowCapture();
        hostBoundaryCodeParity();
        realTimeFixtureSpanlessConvergence();
        realJsonFixtureConvergence();
        invocationDeclarationOrderProbe();
        discardedReturnValuesProbe();
        printReturnValueDivergenceProbe();
        invokeMainTwiceDivergenceProbe();
        asyncScratchCompletionProbe();
        snapshotNormalizationAndFieldSetProbe();
        unpinnedOptionalSuppressionProbe();
        pinnedOptionalHonestFailureProbe();
        perturbedSnapshotFieldProbe();
        serializerRegressionProbe();
        unmappableFileProbe();
        artifactPresenceProbe();
        codegenFailureProbe();
        compileRejectPathProbe();
        compileRejectDivergentCodeProbe();
        compileRejectCleanCompileProbe();
        hostJavaMissingProbe();
        toolMissingProbe();
        nonDealErrorProbe();
        laneDeadlineProbe();

        deleteScratchRoots();

        System.out.println("\nPassed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void check(boolean condition, String message) {
        if (condition) {
            passed++;
        } else {
            failed++;
            System.err.println("FAIL: " + message);
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /** One scratch corpus fixture written to a fresh temp root. */
    private record ScratchFixture(Path root, Path file, String fixturePath,
                                  String strippedSource) { }

    private static final List<Path> scratchRoots = new ArrayList<>();

    private static ScratchFixture writeScratchFixture(String corpusPath,
            String source) throws IOException {
        Path root = Files.createTempDirectory("lane_scratch_");
        scratchRoots.add(root);
        Path file = root.resolve(corpusPath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
        String stripped = ConformanceHarnessMetadata
            .stripClassificationHeaders(source);
        return new ScratchFixture(root, file, corpusPath, stripped);
    }

    /**
     * One scratch fixture plus its authored sidecar (for the gate-run
     * registry probes — the gate validates sidecars at load time).
     */
    private static ScratchFixture writeScratchCorpusFixture(Path root,
            String corpusPath, String source, String sidecarJson)
            throws IOException {
        Path file = root.resolve(corpusPath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
        String stem = corpusPath.endsWith(".deal")
            ? corpusPath.substring(0, corpusPath.length() - ".deal".length())
            : corpusPath;
        Files.writeString(root.resolve(stem + ".expect.json"), sidecarJson);
        return new ScratchFixture(root, file, corpusPath,
            ConformanceHarnessMetadata.stripClassificationHeaders(source));
    }

    private static final String RUNTIME_OK_SIDECAR =
        "{\n"
        + "  \"version\": 1,\n"
        + "  \"backends\": [\"luajit\", \"jvm\", \"js\"],\n"
        + "  \"expected\": {\n"
        + "    \"mode\": \"runtime-ok\",\n"
        + "    \"transcript\": {\"stdout\": \"\", \"stderr\": \"\"},\n"
        + "    \"exitCode\": 0\n"
        + "  }\n"
        + "}\n";

    /** Deletes the scratch corpus roots created by the probes. */
    private static void deleteScratchRoots() {
        for (Path root : scratchRoots) {
            try (var stream = Files.walk(root)) {
                stream.sorted(java.util.Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {
                            // Scratch-root cleanup; never a verdict input.
                        }
                    });
            } catch (IOException ignored) {
                // Scratch-root cleanup; never a verdict input.
            }
        }
    }

    private static LaneCase laneCase(String fixturePath, Path fixtureFile,
            String strippedSource,
            SidecarExpectations.RuntimeExpectation expectation) {
        return new LaneCase(fixturePath, "jvm", fixtureFile,
            List.of(new SidecarSchemaValidator.CompilationModule(fixturePath,
                strippedSource)),
            expectation);
    }

    private static SidecarExpectations.RuntimeExpectation runtimeOk(
            String stdout) {
        return new SidecarExpectations.RuntimeExpectation.Executed(
            "runtime-ok", bytes(stdout), bytes(""), 0, null);
    }

    private static SidecarExpectations.RuntimeExpectation runtimeError(
            SidecarExpectations.ErrorExpectation error) {
        String framing = ErrorSnapshot.CODE_LINE_PREFIX + error.code() + "\n"
            + ErrorSnapshot.SNAPSHOT_LINE_PREFIX
            + ErrorSnapshot.canonicalJson(error) + "\n";
        return new SidecarExpectations.RuntimeExpectation.Executed(
            "runtime-error", bytes(framing), bytes(""), 1, error);
    }

    private static SidecarExpectations.ErrorExpectation error(String code,
            String message, String sourceFile, int line, int column) {
        return new SidecarExpectations.ErrorExpectation(code, message,
            sourceFile, line, column, Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty());
    }

    /** Dispatches one lane case through the gate's worker pool + comparator. */
    private static GateDispatcher.LaneOutcome dispatch(Lane lane,
            LaneCase laneCase) {
        List<GateDispatcher.CaseVerdict> verdicts = GateDispatcher.run(
            List.of(new GateDispatcher.CaseInput(laneCase.fixturePath(),
                List.of(laneCase))),
            Map.of("jvm", lane), 2, Duration.ofSeconds(120));
        return verdicts.get(0).outcomes().get(0);
    }

    private static void assertPassed(String probe, Lane lane,
            LaneCase laneCase) {
        GateDispatcher.LaneOutcome outcome = dispatch(lane, laneCase);
        check(outcome.passed(), probe + ": the lane verdict passes, got "
            + outcome.mismatch().map(m -> m.clazz() + ": " + m.detail())
                .orElse("<pass>"));
    }

    /** The real corpus index (discovery + sidecar loading) for one fixture. */
    private record RealCase(CorpusDiscovery.Fixture fixture,
            SidecarExpectations.StructuredExpectationSidecar sidecar) { }

    private static RealCase realCase(String corpusPath) throws IOException {
        Path root = CORPUS_ROOT.toAbsolutePath().normalize();
        CorpusDiscovery.DiscoveryResult discovery =
            CorpusDiscovery.discover(root);
        Map<String, CorpusDiscovery.Fixture> byPath =
            discovery.corpusByPath();
        CorpusDiscovery.Fixture fixture = null;
        for (CorpusDiscovery.Fixture candidate : discovery.fixtures()) {
            if (candidate.corpusPath().equals(corpusPath)) {
                fixture = candidate;
                break;
            }
        }
        if (fixture == null) {
            throw new IllegalStateException("fixture not discovered: "
                + corpusPath);
        }
        SidecarGateLoader.LoadResult load = SidecarGateLoader.load(fixture,
            byPath, discovery.corpusModuleIndex(), root);
        if (load.failed() || load.runtime().isEmpty()) {
            throw new IllegalStateException("sidecar failed to load for "
                + corpusPath + ": " + load.failure());
        }
        return new RealCase(fixture, load.runtime().get());
    }

    private static LaneCase realLaneCase(String corpusPath) throws IOException {
        RealCase real = realCase(corpusPath);
        return new LaneCase(corpusPath, "jvm", real.fixture().file(),
            CorpusDiscovery.compilationSet(real.fixture(),
                indexOf(CORPUS_ROOT), CORPUS_ROOT.toAbsolutePath().normalize()),
            real.sidecar().expectationFor("jvm"));
    }

    private static Map<String, CorpusDiscovery.Fixture> indexOf(Path root)
            throws IOException {
        Map<String, CorpusDiscovery.Fixture> index = new HashMap<>();
        for (CorpusDiscovery.Fixture fixture
                : CorpusDiscovery.discover(root.toAbsolutePath().normalize())
                    .fixtures()) {
            index.put(fixture.corpusPath(), fixture);
        }
        return index;
    }

    /** The emitted artifact class name of a scratch entry module (the
     * orchestrator's naming over the flat temp project root). */
    private static String artifactNameFor(String stem) {
        return JvmBackend.classNameFor(stem) + ".java";
    }

    // =========================================================================
    // Real fixtures through the gate core + lane
    // =========================================================================

    private static void realFixtureRuntimeOkIfElse() throws Exception {
        String corpusPath = "backend-runtime/control-flow/if-else.deal";
        assertPassed("real runtime-ok (if-else — the returned int is "
            + "discarded, the transcript stays byte-exact empty)",
            new JvmLane(CORPUS_ROOT), realLaneCase(corpusPath));
    }

    private static void realFixtureHostNullReturnOk() throws Exception {
        String corpusPath =
            "backend-runtime/host-abi/host-null-return-ok.deal";
        assertPassed("host triplet runtime-ok (host-null-return-ok through "
            + "host-fixtures/nullreturn_ok.java)", new JvmLane(CORPUS_ROOT),
            realLaneCase(corpusPath));
    }

    private static void realFixtureAsyncSimpleAwait() throws Exception {
        // The JVM backend emits async function declarations as blocking
        // methods, so the export invocation drives the await to
        // completion before the verdict (G4.4).
        String corpusPath = "backend-runtime/async-await/async-simple-await.deal";
        assertPassed("async export awaited (async-simple-await)",
            new JvmLane(CORPUS_ROOT), realLaneCase(corpusPath));
    }

    private static void realRuntimeErrorHonestFailure() throws Exception {
        // The real JVM DealError carries code/message and no span: the
        // lane reports the span-absent capture as a process failure
        // naming the captured code, message and the missing span — never
        // a stack-frame file/line (the removed fallback fabricated a
        // generated-Java location the raise site never carried).
        String corpusPath = "backend-runtime/runtime-errors/type-mismatch-e8001.deal";
        JvmLane lane = new JvmLane(CORPUS_ROOT);
        LaneCase laneCase = realLaneCase(corpusPath);
        LaneExecution execution = lane.execute(laneCase);
        check(execution instanceof LaneExecution.Infrastructure infra
                && infra.clazz() == MismatchClass.PROCESS_FAILURE
                && infra.detail().startsWith("the captured DEAL error "
                    + "carries no span (file, line, column) where one is "
                    + "required")
                && infra.detail().contains("code=E8001")
                && infra.detail().contains("message=expected int")
                && infra.detail().contains("file=null")
                && !infra.detail().contains("Type_mismatch_e8001.java"),
            "a real JVM runtime error yields the honest span-absent "
                + "process failure naming code E8001 and message "
                + "'expected int' (never a fabricated stack-frame file), "
                + "got: " + execution);
        GateDispatcher.LaneOutcome outcome = dispatch(lane, laneCase);
        check(!outcome.passed() && outcome.mismatch().isPresent()
                && outcome.mismatch().get().subject().equals("jvm"),
            "the non-converged runtime-error fixture fails the verdict "
                + "naming the jvm backend, got: " + outcome.mismatch());
    }

    private static void realCompanionThrowCapture() throws Exception {
        // The throwing companion's error field surface reaches the
        // capture (code MODULE_SOURCE_FAIL, message 'module fail'); no
        // origin exists yet, so the companion's stack-frame file is
        // never transported (the removed fallback fabricated it).
        String corpusPath =
            "backend-runtime/source-location/module-error-source.deal";
        JvmLane lane = new JvmLane(CORPUS_ROOT);
        LaneExecution execution = lane.execute(realLaneCase(corpusPath));
        check(execution instanceof LaneExecution.Infrastructure infra
                && infra.clazz() == MismatchClass.PROCESS_FAILURE
                && infra.detail().contains("no span (file, line, column) "
                    + "where one is required")
                && infra.detail().contains("code=MODULE_SOURCE_FAIL")
                && infra.detail().contains("message=module fail")
                && infra.detail().contains("file=null")
                && !infra.detail().contains("Source_module_lib.java"),
            "the throwing companion's DealError fields reach the capture "
                + "(code=MODULE_SOURCE_FAIL, message=module fail, "
                + "file=null) with no fabricated stack-frame file, got: "
                + execution);
    }

    private static void hostBoundaryCodeParity() throws Exception {
        // The E8010/E8011 host-boundary fixtures produce the same codes
        // the Lua lane pins, through the deployed bad_string.java /
        // missing_export.java triplets; the span stays absent until the
        // epic's host-boundary origin leaf lands.
        String[] cases = {
            "backend-runtime/host-abi/host-invalid-utf8-e8010.deal:E8010",
            "backend-runtime/host-abi/host-surrogate-utf8-e8010.deal:E8010",
            "backend-runtime/host-abi/host-missing-export.deal:E8011"
        };
        JvmLane lane = new JvmLane(CORPUS_ROOT);
        for (String entry : cases) {
            String corpusPath = entry.substring(0, entry.indexOf(':'));
            String code = entry.substring(entry.indexOf(':') + 1);
            LaneExecution execution = lane.execute(realLaneCase(corpusPath));
            check(execution instanceof LaneExecution.Infrastructure infra
                    && infra.detail().contains("no span (file, line, column) "
                        + "where one is required")
                    && infra.detail().contains("code=" + code)
                    && !infra.detail().contains(".java"),
                "the host-boundary fixture " + corpusPath + " captures the "
                    + "Lua-pinned code " + code + " with no fabricated "
                    + "stack-frame file, got: " + execution);
        }
    }

    private static void realTimeFixtureSpanlessConvergence()
            throws Exception {
        // The one sanctioned span-less runtime-error fixture passes the
        // production lane byte-exact: the locked time selector's
        // declared-int boundary raise turns out span-less (E8004,
        // "int out of safe range" — today's emission unchanged) and the
        // sidecar pins no span group, so the shared lane contract
        // (code + message mandatory, span group all-or-nothing) emits
        // the exact pinned framing.
        String corpusPath =
            "backend-runtime/stdlib-edge/time-now-millis-positive.deal";
        JvmLane lane = new JvmLane(CORPUS_ROOT);
        LaneCase laneCase = realLaneCase(corpusPath);
        LaneExecution execution = lane.execute(laneCase);
        String expectedFraming = "DEAL_ERROR_CODE: E8004\n"
            + "DEAL_ERROR_SNAPSHOT: {\"code\":\"E8004\","
            + "\"message\":\"int out of safe range\"}\n";
        check(execution instanceof LaneExecution.Executed executed
                && executed.exitCode() == 1
                && new String(executed.stdout(), StandardCharsets.UTF_8)
                    .equals(expectedFraming)
                && executed.stderr().length == 0,
            "the real time fixture emits the exact span-less framing "
                + "(E8004 + today's message, no span) and exits 1, got: "
                + execution);
        assertPassed("the sanctioned span-less time fixture passes the "
            + "differential verdict byte-exact", lane, laneCase);
    }

    private static void realJsonFixtureConvergence() throws Exception {
        // The std/json + @jsonable encode/decode origin leaf (ISSUE-0608):
        // every JSON rejection fixture passes the production lane
        // byte-exact against its sidecar — the json.stringify/json.parse
        // call-expression origin threads unchanged through the recursive
        // encode walk (the nested fixture's pinned 25:10 proves the
        // depth-independence), the unsupported-type raises carry the
        // closed expected/actual projection, and the cyclic-table
        // rejection carries neither optional field (never fabricated).
        String[] cases = {
            "backend-runtime/runtime-errors/json-stringify-function-e8001.deal",
            "backend-runtime/source-location/json-error-source.deal",
            "backend-runtime/stdlib/json/json-stringify-bytes-error.deal",
            "backend-runtime/stdlib/json/json-stringify-nested-bytes-error.deal",
            "backend-runtime/jsonable/jsonable-tojson-rejects-cyclic-table.deal"
        };
        JvmLane lane = new JvmLane(CORPUS_ROOT);
        for (String corpusPath : cases) {
            assertPassed("the JSON rejection fixture " + corpusPath
                + " passes the production lane byte-exact against its "
                + "sidecar", lane, realLaneCase(corpusPath));
        }
    }

    // =========================================================================
    // Lane-contract probes (Verification 4)
    // =========================================================================

    private static final String ORDER_PROBE_SOURCE =
        "import * as console from \"std/console\"\n"
        + "\n"
        + "export function first_export(): null {\n"
        + "  console.log(\"first\");\n"
        + "  return null;\n"
        + "}\n"
        + "\n"
        + "export function second_export(): null {\n"
        + "  console.log(\"second\");\n"
        + "  return null;\n"
        + "}\n"
        + "\n"
        + "export function main(): null {\n"
        + "  return null;\n"
        + "}\n";

    private static void invocationDeclarationOrderProbe() throws Exception {
        // Two zero-arity exports log their own invocation order; the
        // transcript must match declaration order (first, then second) —
        // the G4.4 contract replacing the absorbed runner's declaration
        // list (which it applied to result-printing).
        String corpusPath = "backend-runtime/scratch/order-probe.deal";
        ScratchFixture scratch = writeScratchFixture(corpusPath,
            ORDER_PROBE_SOURCE);
        LaneCase laneCase = laneCase(corpusPath, scratch.file(),
            scratch.strippedSource(), runtimeOk("first\nsecond\n"));
        assertPassed("declaration-order invocation (first then second)",
            new JvmLane(scratch.root()), laneCase);
    }

    private static final String VALUE_PROBE_SOURCE =
        "export function test_value(): int {\n"
        + "  return 42;\n"
        + "}\n"
        + "\n"
        + "export function main(): null {\n"
        + "  return null;\n"
        + "}\n";

    private static void discardedReturnValuesProbe() throws Exception {
        // The production lane discards return values: the pinned
        // transcript stays empty even though test_value returns 42.
        String corpusPath = "backend-runtime/scratch/value-probe.deal";
        ScratchFixture scratch = writeScratchFixture(corpusPath,
            VALUE_PROBE_SOURCE);
        LaneCase laneCase = laneCase(corpusPath, scratch.file(),
            scratch.strippedSource(), runtimeOk(""));
        assertPassed("return values discarded (no lane prints results)",
            new JvmLane(scratch.root()), laneCase);
    }

    /**
     * TEST DOUBLE — a scratch lane whose runner prints every invocation's
     * return value (the G4.4 divergence the comparator must catch).
     */
    private static final class PrintReturnValueLane extends JvmLane {
        PrintReturnValueLane(Path conformanceRoot) {
            super(conformanceRoot);
        }

        @Override
        protected String buildRunner(ProgramNode program,
                String entryClass) {
            StringBuilder sb = new StringBuilder();
            sb.append("public final class ").append(RUNNER_CLASS_NAME)
                .append(" {\n");
            sb.append("    public static void main(String[] args) {\n");
            sb.append("        try {\n");
            sb.append("            System.out.println(").append(entryClass)
                .append('.').append(JvmBackend.javaName("test_value"))
                .append("());\n");
            sb.append("            ").append(entryClass).append(".main();\n");
            sb.append("        } catch (Throwable e) {\n");
            sb.append("            System.exit(1);\n");
            sb.append("        }\n");
            sb.append("    }\n");
            sb.append("}\n");
            return sb.toString();
        }
    }

    private static void printReturnValueDivergenceProbe() throws Exception {
        String corpusPath = "backend-runtime/scratch/value-probe.deal";
        ScratchFixture scratch = writeScratchFixture(corpusPath,
            VALUE_PROBE_SOURCE);
        LaneCase laneCase = laneCase(corpusPath, scratch.file(),
            scratch.strippedSource(), runtimeOk(""));
        GateDispatcher.LaneOutcome outcome = dispatch(
            new PrintReturnValueLane(scratch.root()), laneCase);
        check(!outcome.passed(), "a lane printing a return value fails the "
            + "case (the pinned transcript stays empty)");
        check(outcome.mismatch().isPresent()
                && outcome.mismatch().get().clazz()
                    == MismatchClass.TRANSCRIPT_MISMATCH
                && outcome.mismatch().get().subject().equals("jvm")
                && outcome.mismatch().get().detail()
                    .contains("stdout differs at byte 0"),
            "the divergence is TRANSCRIPT_MISMATCH naming the backend and "
                + "the first differing byte, got: " + outcome.mismatch());
    }

    private static final String MAIN_PROBE_SOURCE =
        "import * as console from \"std/console\"\n"
        + "\n"
        + "export function test_nothing(): null {\n"
        + "  return null;\n"
        + "}\n"
        + "\n"
        + "export function main(): null {\n"
        + "  console.log(\"main\");\n"
        + "  return null;\n"
        + "}\n";

    private static void invokeMainTwiceDivergenceProbe() throws Exception {
        // The production lane: main() runs exactly once (the lane runner
        // invokes it once; the emitted main(String[]) entry point is
        // never called), so the pinned "main\n" passes.
        String corpusPath = "backend-runtime/scratch/main-probe.deal";
        ScratchFixture scratch = writeScratchFixture(corpusPath,
            MAIN_PROBE_SOURCE);
        LaneCase laneCase = laneCase(corpusPath, scratch.file(),
            scratch.strippedSource(), runtimeOk("main\n"));
        assertPassed("main() executes exactly once (production lane)",
            new JvmLane(scratch.root()), laneCase);

        // TEST DOUBLE: a scratch lane that re-invokes main after the
        // export loop.
        JvmLane doubleMainLane = new JvmLane(scratch.root()) {
            @Override
            protected String buildRunner(ProgramNode program,
                    String entryClass) {
                StringBuilder sb = new StringBuilder();
                sb.append("public final class ").append(RUNNER_CLASS_NAME)
                    .append(" {\n");
                sb.append("    public static void main(String[] args) {\n");
                sb.append("        try {\n");
                sb.append("            ").append(entryClass).append(".main();\n");
                sb.append("            ").append(entryClass)
                    .append('.').append(JvmBackend.javaName("test_nothing"))
                    .append("();\n");
                sb.append("            ").append(entryClass).append(".main();\n");
                sb.append("        } catch (Throwable e) {\n");
                sb.append("            System.exit(1);\n");
                sb.append("        }\n");
                sb.append("    }\n");
                sb.append("}\n");
                return sb.toString();
            }
        };
        GateDispatcher.LaneOutcome outcome = dispatch(doubleMainLane,
            laneCase);
        check(!outcome.passed(), "a lane invoking main() twice fails the "
            + "case (the pinned transcript carries one \"main\\n\")");
        check(outcome.mismatch().isPresent()
                && outcome.mismatch().get().clazz()
                    == MismatchClass.TRANSCRIPT_MISMATCH
                && outcome.mismatch().get().subject().equals("jvm"),
            "the double-main divergence is TRANSCRIPT_MISMATCH naming the "
                + "backend, got: " + outcome.mismatch());
    }

    private static void asyncScratchCompletionProbe() throws Exception {
        // An async export that logs after its await: the blocking JVM
        // invocation drives the operation to completion before the
        // verdict.
        String source =
            "import * as console from \"std/console\"\n"
            + "\n"
            + "async function inner(): int {\n"
            + "  return 7;\n"
            + "}\n"
            + "\n"
            + "export async function async_export(): int {\n"
            + "  let x: int = await inner();\n"
            + "  if (x !== 7) {\n"
            + "    throw { code: \"TEST_FAIL\", message: \"async value\" };\n"
            + "  }\n"
            + "  console.log(\"done\");\n"
            + "  return x;\n"
            + "}\n"
            + "\n"
            + "export function main(): null {\n"
            + "  return null;\n"
            + "}\n";
        String corpusPath = "backend-runtime/scratch/async-complete.deal";
        ScratchFixture scratch = writeScratchFixture(corpusPath, source);
        LaneCase laneCase = laneCase(corpusPath, scratch.file(),
            scratch.strippedSource(), runtimeOk("done\n"));
        assertPassed("async export awaited to completion (logs after await)",
            new JvmLane(scratch.root()), laneCase);
    }

    // =========================================================================
    // Snapshot probes (Verification 5 — through T7's shared serializer)
    // =========================================================================

    private static final String FRAME_PROBE_SOURCE =
        "export function test_frame_probe(): null {\n"
        + "  throw { code: \"FRAME_PROBE\", message: \"frame probe\" };\n"
        + "}\n"
        + "\n"
        + "export function main(): null {\n"
        + "  return null;\n"
        + "}\n";

    /**
     * TEST DOUBLE — a scratch lane whose transport read returns an
     * injected complete captured error (simulating a converged backend
     * whose runtime error carries the full DEALRuntimeError field set),
     * so the snapshot construction, the shared serializer, and the
     * deployment-map normalization are exercised end-to-end through the
     * gate's comparator.
     */
    private static class InjectedTransportLane extends JvmLane {
        private final CapturedError injected;

        InjectedTransportLane(Path conformanceRoot, CapturedError injected) {
            super(conformanceRoot);
            this.injected = injected;
        }

        @Override
        protected CapturedError readTransport(Path outputRoot) {
            return injected;
        }
    }

    private static void snapshotNormalizationAndFieldSetProbe()
            throws Exception {
        // The injected complete capture carries the temp-root artifact
        // name as its file; the lane must normalize it to the corpus
        // path through the deployment map and serialize the snapshot
        // through the shared ErrorSnapshot serializer — the verdict
        // passes field-exact and byte-exact against the pinned sidecar.
        String corpusPath = "backend-runtime/scratch/frame-probe.deal";
        ScratchFixture scratch = writeScratchFixture(corpusPath,
            FRAME_PROBE_SOURCE);
        SidecarExpectations.ErrorExpectation pinned = error("FRAME_PROBE",
            "frame probe", corpusPath, 7, 11);
        LaneCase laneCase = laneCase(corpusPath, scratch.file(),
            scratch.strippedSource(), runtimeError(pinned));
        Lane lane = new InjectedTransportLane(scratch.root(),
            new JvmLane.CapturedError("FRAME_PROBE", "frame probe",
                artifactNameFor("frame-probe"), 7, 11,
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty()));
        assertPassed("the complete capture serializes through the shared "
            + "serializer with the temp-root file normalized to the "
            + "corpus path (field-exact and byte-exact)", lane, laneCase);
        LaneExecution execution = lane.execute(laneCase);
        check(execution instanceof LaneExecution.Executed,
            "the normalized-snapshot run is a real execution");
        if (execution instanceof LaneExecution.Executed executed) {
            String stdout = new String(executed.stdout(),
                StandardCharsets.UTF_8);
            check(stdout.contains("\"sourceFile\":"
                    + "\"backend-runtime/scratch/frame-probe.deal\""),
                "the snapshot's sourceFile is the corpus-relative path "
                    + "(normalized from the temp-root artifact name), got: "
                    + stdout);
            check(!stdout.contains("Frame_probe.java"),
                "the temp-root artifact name never leaks into the "
                    + "snapshot, got: " + stdout);
            Optional<ErrorSnapshot.Framed> framed =
                ErrorSnapshot.parseFraming(executed.stdout());
            check(framed.isPresent(),
                "the emitted snapshot is canonical (shared serializer)");
        }
    }

    private static void unpinnedOptionalSuppressionProbe() throws Exception {
        // The sidecar omits frames/expected/actual; the captured error
        // carries them. The lane must suppress every unpinned optional
        // (the sidecar is the authoritative field set) — the verdict
        // passes.
        String corpusPath = "backend-runtime/scratch/frame-probe.deal";
        ScratchFixture scratch = writeScratchFixture(corpusPath,
            FRAME_PROBE_SOURCE);
        SidecarExpectations.ErrorExpectation pinned = error("FRAME_PROBE",
            "frame probe", corpusPath, 7, 11);
        LaneCase laneCase = laneCase(corpusPath, scratch.file(),
            scratch.strippedSource(), runtimeError(pinned));
        Lane lane = new InjectedTransportLane(scratch.root(),
            new JvmLane.CapturedError("FRAME_PROBE", "frame probe",
                artifactNameFor("frame-probe"), 7, 11,
                Optional.of("null"), Optional.of("string"),
                Optional.of(2), Optional.empty()));
        assertPassed("the lane suppresses the unpinned optional fields "
            + "(frames/expected/actual — the sidecar is the authoritative "
            + "field set)", lane, laneCase);
        LaneExecution execution = lane.execute(laneCase);
        check(execution instanceof LaneExecution.Executed,
            "the suppression run is a real execution");
        if (execution instanceof LaneExecution.Executed executed) {
            String stdout = new String(executed.stdout(),
                StandardCharsets.UTF_8);
            check(!stdout.contains("frames") && !stdout.contains("expected")
                    && !stdout.contains("actual"),
                "the emitted snapshot carries no unpinned optional field, "
                    + "got: " + stdout);
        }
    }

    private static void pinnedOptionalHonestFailureProbe() throws Exception {
        // The sidecar pins frames: 2 but the captured error carries
        // none: the lane never fabricates the field, so the snapshot
        // omits it and the case fails honestly.
        String corpusPath = "backend-runtime/scratch/frame-probe.deal";
        ScratchFixture scratch = writeScratchFixture(corpusPath,
            FRAME_PROBE_SOURCE);
        SidecarExpectations.ErrorExpectation pinned =
            new SidecarExpectations.ErrorExpectation("FRAME_PROBE",
                "frame probe", corpusPath, 7, 11, Optional.empty(),
                Optional.empty(), Optional.of(2), Optional.empty());
        LaneCase laneCase = laneCase(corpusPath, scratch.file(),
            scratch.strippedSource(), runtimeError(pinned));
        Lane lane = new InjectedTransportLane(scratch.root(),
            new JvmLane.CapturedError("FRAME_PROBE", "frame probe",
                artifactNameFor("frame-probe"), 7, 11,
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty()));
        GateDispatcher.LaneOutcome outcome = dispatch(lane, laneCase);
        check(!outcome.passed(),
            "a sidecar pinning frames for a fixture whose captured error "
                + "carries none fails honestly (the lane never fabricates)");
        check(outcome.mismatch().isPresent()
                && outcome.mismatch().get().subject().equals("jvm")
                && outcome.mismatch().get().detail()
                    .contains("stdout differs"),
            "the frames-pinned probe fails the snapshot comparison naming "
                + "the backend, got: " + outcome.mismatch());
        LaneExecution execution = lane.execute(laneCase);
        check(execution instanceof LaneExecution.Executed,
            "the frames-pinned run is a real execution");
        if (execution instanceof LaneExecution.Executed executed) {
            String stdout = new String(executed.stdout(),
                StandardCharsets.UTF_8);
            check(!stdout.contains("frames"),
                "the lane never fabricates the pinned-but-uncarried frames "
                    + "field, got: " + stdout);
        }
    }

    private static void perturbedSnapshotFieldProbe() throws Exception {
        // An injected capture perturbing exactly one snapshot field
        // (line) fails the byte comparison naming the backend and the
        // first differing byte; the emitted snapshot differs in exactly
        // that field.
        String corpusPath = "backend-runtime/scratch/frame-probe.deal";
        ScratchFixture scratch = writeScratchFixture(corpusPath,
            FRAME_PROBE_SOURCE);
        SidecarExpectations.ErrorExpectation pinned = error("FRAME_PROBE",
            "frame probe", corpusPath, 7, 11);
        LaneCase laneCase = laneCase(corpusPath, scratch.file(),
            scratch.strippedSource(), runtimeError(pinned));
        Lane lane = new InjectedTransportLane(scratch.root(),
            new JvmLane.CapturedError("FRAME_PROBE", "frame probe",
                artifactNameFor("frame-probe"), 8, 11,
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty()));
        GateDispatcher.LaneOutcome outcome = dispatch(lane, laneCase);
        check(!outcome.passed()
                && outcome.mismatch().isPresent()
                && outcome.mismatch().get().subject().equals("jvm")
                && outcome.mismatch().get().clazz()
                    == MismatchClass.TRANSCRIPT_MISMATCH
                && outcome.mismatch().get().detail()
                    .contains("stdout differs at byte"),
            "the perturbed snapshot field fails the byte comparison naming "
                + "the backend and the first differing byte, got: "
                + outcome.mismatch());
        LaneExecution execution = lane.execute(laneCase);
        check(execution instanceof LaneExecution.Executed,
            "the perturbed run is a real execution");
        if (execution instanceof LaneExecution.Executed executed) {
            Optional<ErrorSnapshot.Framed> framed =
                ErrorSnapshot.parseFraming(executed.stdout());
            check(framed.isPresent(),
                "the perturbed snapshot stays canonical");
            if (framed.isPresent()) {
                SidecarExpectations.ErrorExpectation fields =
                    framed.get().fields();
                check(fields.line() == 8 && fields.code().equals("FRAME_PROBE")
                        && fields.message().equals("frame probe")
                        && fields.sourceFile().equals(corpusPath)
                        && fields.column() == 11,
                    "exactly the line field is perturbed (8 vs pinned 7), "
                        + "every other field matches, got: " + fields);
            }
        }
    }

    private static void serializerRegressionProbe() throws Exception {
        // A snapshot violating the canonical serialization fails with
        // ERROR_SNAPSHOT_MISMATCH naming the backend (the comparator
        // validates the framing through the shared serializer).
        String corpusPath = "backend-runtime/scratch/frame-probe.deal";
        ScratchFixture scratch = writeScratchFixture(corpusPath,
            FRAME_PROBE_SOURCE);
        SidecarExpectations.ErrorExpectation pinned = error("FRAME_PROBE",
            "frame probe", corpusPath, 7, 11);
        LaneCase laneCase = laneCase(corpusPath, scratch.file(),
            scratch.strippedSource(), runtimeError(pinned));
        Lane lane = new InjectedTransportLane(scratch.root(),
            new JvmLane.CapturedError("FRAME_PROBE", "frame probe",
                artifactNameFor("frame-probe"), 7, 11,
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty())) {
            @Override
            protected String framing(
                    SidecarExpectations.ErrorExpectation snapshot) {
                return ErrorSnapshot.CODE_LINE_PREFIX + snapshot.code()
                    + "\n" + ErrorSnapshot.SNAPSHOT_LINE_PREFIX
                    + "{\"line\":" + snapshot.line() + ",\"code\":\""
                    + snapshot.code() + "\"}\n";
            }
        };
        GateDispatcher.LaneOutcome outcome = dispatch(lane, laneCase);
        check(!outcome.passed()
                && outcome.mismatch().isPresent()
                && outcome.mismatch().get().clazz()
                    == MismatchClass.TRANSCRIPT_MISMATCH
                && outcome.mismatch().get().subject().equals("jvm")
                && outcome.mismatch().get().detail()
                    .contains("stdout differs at byte"),
            "a snapshot violating the canonical serialization fails the "
                + "byte comparison naming the backend and the first "
                + "differing byte, got: " + outcome.mismatch());
        LaneExecution execution = lane.execute(laneCase);
        check(execution instanceof LaneExecution.Executed,
            "the non-canonical-snapshot run is a real execution");
        if (execution instanceof LaneExecution.Executed executed) {
            check(ErrorSnapshot.parseFraming(executed.stdout()).isEmpty(),
                "the shared framing validator rejects the wrong-key-order "
                    + "snapshot (the comparator would report "
                    + "ERROR_SNAPSHOT_MISMATCH for a byte-matching "
                    + "non-canonical framing)");
        }
    }

    private static void unmappableFileProbe() throws Exception {
        // TEST DOUBLE: a scratch lane with the deployment map stripped —
        // the captured file is emitted verbatim so the byte comparison
        // fails and surfaces the defect.
        String corpusPath = "backend-runtime/scratch/frame-probe.deal";
        ScratchFixture scratch = writeScratchFixture(corpusPath,
            FRAME_PROBE_SOURCE);
        SidecarExpectations.ErrorExpectation pinned = error("FRAME_PROBE",
            "frame probe", corpusPath, 7, 11);
        LaneCase laneCase = laneCase(corpusPath, scratch.file(),
            scratch.strippedSource(), runtimeError(pinned));
        Lane lane = new InjectedTransportLane(scratch.root(),
            new JvmLane.CapturedError("FRAME_PROBE", "frame probe",
                artifactNameFor("frame-probe"), 7, 11,
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty())) {
            @Override
            protected DeploymentEntry deploymentEntryFor(
                    String capturedFile, JvmCompilation compilation) {
                return null; // the deployment map is stripped
            }
        };
        LaneExecution execution = lane.execute(laneCase);
        check(execution instanceof LaneExecution.Executed,
            "the unmappable-file run is a real execution");
        if (execution instanceof LaneExecution.Executed executed) {
            String stdout = new String(executed.stdout(),
                StandardCharsets.UTF_8);
            check(stdout.contains("\"sourceFile\":\"Frame_probe.java\""),
                "an unmappable captured file is emitted verbatim (the "
                    + "artifact name reaches the snapshot), got: " + stdout);
        }
        GateDispatcher.LaneOutcome outcome = dispatch(lane, laneCase);
        check(!outcome.passed()
                && outcome.mismatch().isPresent()
                && outcome.mismatch().get().subject().equals("jvm"),
            "the verbatim-file snapshot fails the comparison naming the "
                + "backend, got: " + outcome.mismatch());
    }

    // =========================================================================
    // Infrastructure probes
    // =========================================================================

    private static void artifactPresenceProbe() throws Exception {
        // TEST DOUBLE: a scratch lane that deletes the emitted entry
        // .class before the presence assertion — the lane must report
        // ARTIFACT_MISSING instead of fabricating a result.
        String corpusPath = "backend-runtime/scratch/order-probe.deal";
        ScratchFixture scratch = writeScratchFixture(corpusPath,
            ORDER_PROBE_SOURCE);
        LaneCase laneCase = laneCase(corpusPath, scratch.file(),
            scratch.strippedSource(), runtimeOk("first\nsecond\n"));
        JvmLane lane = new JvmLane(scratch.root()) {
            @Override
            protected void beforeExecution(Path projectRoot,
                    Path outputRoot) {
                try {
                    Files.deleteIfExists(outputRoot.resolve(
                        JvmBackend.classNameFor("order-probe") + ".class"));
                } catch (IOException ignored) {
                    // The probe deletes the artifact; the lane must see
                    // it missing.
                }
            }
        };
        LaneExecution execution = lane.execute(laneCase);
        check(execution instanceof LaneExecution.Infrastructure infra
                && infra.clazz() == MismatchClass.ARTIFACT_MISSING
                && infra.detail().contains("Order_probe.class"),
            "deleting a generated artifact before execution yields "
                + "ARTIFACT_MISSING naming the artifact, got: " + execution);
    }

    private static void codegenFailureProbe() throws Exception {
        // An entry module without main(): the real backend emits E6004
        // and the orchestrator fails — the lane reports a lane compile
        // failure naming the diagnostic code, never a fabricated runner.
        String source =
            "export function test_nothing(): null {\n"
            + "  return null;\n"
            + "}\n";
        String corpusPath = "backend-runtime/scratch/no-main.deal";
        ScratchFixture scratch = writeScratchFixture(corpusPath, source);
        LaneCase laneCase = laneCase(corpusPath, scratch.file(),
            scratch.strippedSource(), runtimeOk(""));
        LaneExecution execution = new JvmLane(scratch.root()).execute(laneCase);
        check(execution instanceof LaneExecution.Infrastructure infra
                && infra.clazz() == MismatchClass.PROCESS_FAILURE
                && infra.detail().contains("the lane compilation failed")
                && infra.detail().contains("E2012")
                && infra.detail().contains(
                    "Entry module must export 'main'"),
            "an entry without main() fails the module shape gate (E2012) "
                + "and the lane reports the compile failure naming the "
                + "diagnostic code, got: " + execution);
    }

    private static void compileRejectPathProbe() throws Exception {
        // The compile-reject path (corpus C6): a rejection expectation
        // plus a compile failure with the pinned diagnostic code and no
        // emitted artifact yields the exact rejection object. The
        // divergent E6006 pin itself lands with the backend epics; this
        // probe exercises the lane's rejection plumbing mechanically
        // with the shape gate's E2012 (a dispatch-level probe — the
        // schema's non-FFI divergent-form closure is a gate-side
        // concern, not a lane concern).
        String source =
            "export function test_nothing(): null {\n"
            + "  return null;\n"
            + "}\n";
        String corpusPath = "backend-runtime/scratch/no-main-reject.deal";
        ScratchFixture scratch = writeScratchFixture(corpusPath, source);
        SidecarExpectations.RuntimeExpectation.Rejected rejected =
            new SidecarExpectations.RuntimeExpectation.Rejected(
                "compile-reject", "E2012", OptionalInt.empty(),
                OptionalInt.empty());
        LaneCase laneCase = new LaneCase(corpusPath, "jvm", scratch.file(),
            List.of(new SidecarSchemaValidator.CompilationModule(corpusPath,
                scratch.strippedSource())),
            rejected);
        LaneExecution execution = new JvmLane(scratch.root()).execute(laneCase);
        check(execution instanceof LaneExecution.Rejected r
                && r.code().equals("E2012") && r.line().isEmpty()
                && r.column().isEmpty(),
            "a compile failure with the pinned diagnostic code and no "
                + "artifact yields the exact rejection object (the pinned "
                + "line/column only when the sidecar pins them), got: "
                + execution);
        GateDispatcher.LaneOutcome outcome = dispatch(
            new JvmLane(scratch.root()), laneCase);
        check(outcome.passed(),
            "the rejection verdict matches the pinned diagnostic object, "
                + "got: " + outcome.mismatch());
    }

    private static void compileRejectDivergentCodeProbe()
            throws Exception {
        // A differently-coded rejection (G6 closes "expected or
        // unexpected rejection"): the lane reports the real rejection
        // honestly with its actual code, and the comparator reports the
        // exact COMPILE_REJECT_MISMATCH delta — never an infrastructure
        // failure. The probe pins the C6 FFI code E6006 against a
        // scratch fixture whose shape gate rejects with E2012 (the real
        // divergent FFI case lands with the backend epics' E6006
        // registration; this dispatch-level probe exercises the lane's
        // rejection plumbing mechanically).
        String source =
            "export function test_nothing(): null {\n"
            + "  return null;\n"
            + "}\n";
        String corpusPath =
            "backend-runtime/scratch/no-main-reject-divergent.deal";
        ScratchFixture scratch = writeScratchFixture(corpusPath, source);
        SidecarExpectations.RuntimeExpectation.Rejected rejected =
            new SidecarExpectations.RuntimeExpectation.Rejected(
                "compile-reject", "E6006", OptionalInt.empty(),
                OptionalInt.empty());
        LaneCase laneCase = new LaneCase(corpusPath, "jvm", scratch.file(),
            List.of(new SidecarSchemaValidator.CompilationModule(corpusPath,
                scratch.strippedSource())),
            rejected);
        LaneExecution execution = new JvmLane(scratch.root()).execute(laneCase);
        check(execution instanceof LaneExecution.Rejected r
                && r.code().equals("E2012") && r.line().isEmpty()
                && r.column().isEmpty(),
            "a differently-coded rejection is reported honestly as a "
                + "Rejected outcome with the actual code (E2012), got: "
                + execution);
        GateDispatcher.LaneOutcome outcome = dispatch(
            new JvmLane(scratch.root()), laneCase);
        check(!outcome.passed() && outcome.mismatch().isPresent()
                && outcome.mismatch().get().clazz()
                    == MismatchClass.COMPILE_REJECT_MISMATCH
                && outcome.mismatch().get().subject().equals("jvm")
                && outcome.mismatch().get().detail().contains(
                    "diagnostic.code must be E6006")
                && outcome.mismatch().get().detail().contains("got E2012"),
            "the comparator reports the exact COMPILE_REJECT_MISMATCH "
                + "code delta (diagnostic.code must be E6006, got E2012), "
                + "got: " + outcome.mismatch());
    }

    private static void compileRejectCleanCompileProbe() throws Exception {
        // A clean compile under a rejection pin: the lane refuses to
        // execute a module whose sidecar pins rejection and reports the
        // missing rejection as PROCESS_FAILURE — never a fabricated
        // rejection and never an execution.
        String source =
            "export function main(): null {\n"
            + "  return null;\n"
            + "}\n";
        String corpusPath = "backend-runtime/scratch/clean-reject-pin.deal";
        ScratchFixture scratch = writeScratchFixture(corpusPath, source);
        SidecarExpectations.RuntimeExpectation.Rejected rejected =
            new SidecarExpectations.RuntimeExpectation.Rejected(
                "compile-reject", "E6006", OptionalInt.empty(),
                OptionalInt.empty());
        LaneCase laneCase = new LaneCase(corpusPath, "jvm", scratch.file(),
            List.of(new SidecarSchemaValidator.CompilationModule(corpusPath,
                scratch.strippedSource())),
            rejected);
        LaneExecution execution = new JvmLane(scratch.root()).execute(laneCase);
        check(execution instanceof LaneExecution.Infrastructure infra
                && infra.clazz() == MismatchClass.PROCESS_FAILURE
                && infra.detail().contains("compiled cleanly")
                && infra.detail().contains("never fabricates"),
            "a clean compile under a rejection pin is refused as "
                + "PROCESS_FAILURE naming the missing rejection, got: "
                + execution);
        GateDispatcher.LaneOutcome outcome = dispatch(
            new JvmLane(scratch.root()), laneCase);
        check(!outcome.passed() && outcome.mismatch().isPresent()
                && outcome.mismatch().get().clazz()
                    == MismatchClass.PROCESS_FAILURE
                && outcome.mismatch().get().subject().equals("jvm"),
            "the gate passes the clean-compile refusal through as an "
                + "infrastructure PROCESS_FAILURE outcome, got: "
                + outcome.mismatch());
    }

    private static void hostJavaMissingProbe() throws Exception {
        // A fixture importing a host module whose .java implementation
        // is missing: the lane reports a corpus error naming the missing
        // file — never a skip (corpus C5).
        ScratchFixture scratch = writeScratchFixture(
            "backend-runtime/scratch/ghost-host.deal",
            "import * as host from \"host/ghost\"\n"
            + "\n"
            + "export function test_ghost(): null {\n"
            + "  host.ping();\n"
            + "  return null;\n"
            + "}\n"
            + "\n"
            + "export function main(): null {\n"
            + "  return null;\n"
            + "}\n");
        Path hostDir = scratch.root().resolve("host-fixtures");
        Files.createDirectories(hostDir);
        Files.writeString(hostDir.resolve("ghost.d.deal"),
            "export function ping(): string;\n");
        LaneCase laneCase = laneCase(scratch.fixturePath(), scratch.file(),
            scratch.strippedSource(), runtimeOk(""));
        LaneExecution execution = new JvmLane(scratch.root()).execute(laneCase);
        check(execution instanceof LaneExecution.Infrastructure infra
                && infra.clazz() == MismatchClass.HARNESS_DEFECT
                && infra.detail().contains("corpus error")
                && infra.detail().contains("host-fixtures/ghost.java"),
            "a missing host .java implementation is a corpus error naming "
                + "the file, never a skip, got: " + execution);
    }

    private static void toolMissingProbe() throws Exception {
        // TEST DOUBLE: a scratch lane whose tool probe fails — the lane
        // must report TOOL_MISSING, never a skip (G3).
        String corpusPath = "backend-runtime/scratch/order-probe.deal";
        ScratchFixture scratch = writeScratchFixture(corpusPath,
            ORDER_PROBE_SOURCE);
        LaneCase laneCase = laneCase(corpusPath, scratch.file(),
            scratch.strippedSource(), runtimeOk("first\nsecond\n"));
        JvmLane lane = new JvmLane(scratch.root()) {
            @Override
            protected boolean jvmAvailable() {
                return false;
            }
        };
        LaneExecution execution = lane.execute(laneCase);
        check(execution instanceof LaneExecution.Infrastructure infra
                && infra.clazz() == MismatchClass.TOOL_MISSING
                && infra.detail().contains("javac/java"),
            "a failed tool probe yields TOOL_MISSING naming the tools, got: "
                + execution);
    }

    private static void nonDealErrorProbe() throws Exception {
        // A host triplet implementation raising a raw Java exception: the
        // subprocess fails outside the DEAL outcome surface (no DEAL
        // error payload) and the lane reports a process failure.
        ScratchFixture scratch = writeScratchFixture(
            "backend-runtime/scratch/raw-host-error.deal",
            "import * as host from \"host/boom\"\n"
            + "\n"
            + "export function test_raw_error(): null {\n"
            + "  host.ping();\n"
            + "  return null;\n"
            + "}\n"
            + "\n"
            + "export function main(): null {\n"
            + "  return null;\n"
            + "}\n");
        Path hostDir = scratch.root().resolve("host-fixtures");
        Files.createDirectories(hostDir);
        Files.writeString(hostDir.resolve("boom.d.deal"),
            "export function ping(): string;\n");
        Files.writeString(hostDir.resolve("boom.java"),
            "final class HostBoom {\n"
            + "  public static Object ping() {\n"
            + "    throw new java.lang.RuntimeException(\"raw boom\");\n"
            + "  }\n"
            + "}\n");
        LaneCase laneCase = laneCase(scratch.fixturePath(), scratch.file(),
            scratch.strippedSource(), runtimeOk(""));
        LaneExecution execution = new JvmLane(scratch.root()).execute(laneCase);
        check(execution instanceof LaneExecution.Infrastructure infra
                && infra.clazz() == MismatchClass.PROCESS_FAILURE
                && infra.detail().contains(
                    "outside the DEAL outcome surface")
                && infra.detail().contains("no DEAL error payload"),
            "a raw (non-DEAL) subprocess error is a process failure with "
                + "the bounded detail, got: " + execution);
    }

    private static void laneDeadlineProbe() throws Exception {
        // A scratch fixture that never returns: the harness-owned
        // deadline cancels the lane execution, the lane terminates its
        // subprocess (the Lane contract requirement), and the outcome is
        // LANE_TIMEOUT with no retry.
        String source =
            "export function test_hang(): null {\n"
            + "  while (true) {\n"
            + "  }\n"
            + "  return null;\n"
            + "}\n"
            + "\n"
            + "export function main(): null {\n"
            + "  return null;\n"
            + "}\n";
        String corpusPath = "backend-runtime/scratch/hang-probe.deal";
        ScratchFixture scratch = writeScratchFixture(corpusPath, source);
        LaneCase laneCase = laneCase(corpusPath, scratch.file(),
            scratch.strippedSource(), runtimeOk(""));
        long start = System.nanoTime();
        List<GateDispatcher.CaseVerdict> verdicts = GateDispatcher.run(
            List.of(new GateDispatcher.CaseInput(corpusPath,
                List.of(laneCase))),
            Map.of("jvm", new JvmLane(scratch.root())), 2,
            Duration.ofMillis(10_000));
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
        GateDispatcher.LaneOutcome outcome = verdicts.get(0).outcomes().get(0);
        check(outcome.mismatch().isPresent()
                && outcome.mismatch().get().clazz()
                    == MismatchClass.LANE_TIMEOUT
                && outcome.mismatch().get().infrastructure()
                && outcome.mismatch().get().subject().equals("jvm"),
            "a hung lane subprocess is LANE_TIMEOUT naming the backend, "
                + "got: " + outcome.mismatch());
        check(elapsedMillis < 60_000,
            "the harness-owned deadline releases the lane instead of "
                + "blocking on the hung java process (elapsed "
                + elapsedMillis + " ms)");
        check(!outcome.passed(),
            "the infrastructure outcome never satisfies the case");
    }


}
