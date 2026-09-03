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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

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
 *       outcome: the real JVM runtime error carries code/message plus a
 *       stack-trace file/line and no column/expected/actual/frames/cause
 *       (ISSUE-0276 owns the backend convergence), so the lane reports
 *       the incomplete capture as a process failure naming the captured
 *       code — the E8010/E8011 host-boundary fixtures carry the same
 *       codes the Lua lane pins.</li>
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
 *   <li>Pre-flip skip tolerance (G2/G8): the absorbed 47-entry
 *       {@code JvmConformanceTest} registry validates cleanly against
 *       the real corpus; a registry-tracked failing outcome is reported
 *       non-fatal with its gap id by the gate; a stale entry (missing
 *       fixture or a fixture that starts passing) fails the gate with a
 *       promotion instruction; a corpus error (missing host
 *       implementation) is never tracked.</li>
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
 * The two {@code StubLane} instances are flagged test doubles used only
 * so the gate-run registry probes focus their verdict on the jvm lane
 * outcomes. Each double is flagged as such in the test code.</p>
 */
public class JvmLaneTest {

    private static int passed = 0;
    private static int failed = 0;

    /** The real corpus root (the scratch fixtures are header-free, so
     * raw == stripped coordinates for the dispatch probes). */
    private static final Path CORPUS_ROOT = Path.of("test", "conformance");

    /** The absorbed skip-registry population (the live
     * {@code JvmConformanceTest.SKIPS} registry on the canonical
     * revision; every promotion removes an entry and updates this pin
     * together with the absorbed registry). */
    private static final int SKIP_REGISTRY_ENTRIES = 47;

    public static void main(String[] args) throws Exception {
        System.out.println("=== JVM Lane Tests (ISSUE-0355) ===\n");

        realFixtureRuntimeOkIfElse();
        realFixtureHostNullReturnOk();
        realFixtureAsyncSimpleAwait();
        realRuntimeErrorHonestFailure();
        realCompanionThrowCapture();
        hostBoundaryCodeParity();
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
        registryValidatesAgainstRealCorpus();
        laneRegistryStaleEntryProbe();
        gateTrackedNonFatalProbe();
        gateStalePassingProbe();
        gateMissingEntryProbe();
        gateInfrastructureNotToleratedProbe();

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
        // The real JVM runtime error carries code/message plus the
        // stack-trace file/line and no column: the lane reports the
        // incomplete capture as a process failure naming the captured
        // code — the non-converged backend is surfaced as a differential
        // failure reported to ISSUE-0276, never a harness workaround.
        String corpusPath = "backend-runtime/runtime-errors/type-mismatch-e8001.deal";
        JvmLane lane = new JvmLane(CORPUS_ROOT);
        LaneCase laneCase = realLaneCase(corpusPath);
        LaneExecution execution = lane.execute(laneCase);
        check(execution instanceof LaneExecution.Infrastructure infra
                && infra.clazz() == MismatchClass.PROCESS_FAILURE
                && infra.detail().contains("no complete DEALRuntimeError")
                && infra.detail().contains("code=E8001")
                && infra.detail().contains(
                    "file=Type_mismatch_e8001.java")
                && infra.detail().contains("column=null"),
            "a real JVM runtime error yields the honest incomplete-capture "
                + "process failure naming code E8001 and the stack-trace "
                + "file, got: " + execution);
        GateDispatcher.LaneOutcome outcome = dispatch(lane, laneCase);
        check(!outcome.passed() && outcome.mismatch().isPresent()
                && outcome.mismatch().get().subject().equals("jvm"),
            "the non-converged runtime-error fixture fails the verdict "
                + "naming the jvm backend, got: " + outcome.mismatch());
    }

    private static void realCompanionThrowCapture() throws Exception {
        // The throwing companion's artifact name must reach the captured
        // file (the deployment map maps it to the companion's corpus
        // path when the capture completes — corpus C2).
        String corpusPath =
            "backend-runtime/source-location/module-error-source.deal";
        JvmLane lane = new JvmLane(CORPUS_ROOT);
        LaneExecution execution = lane.execute(realLaneCase(corpusPath));
        check(execution instanceof LaneExecution.Infrastructure infra
                && infra.detail().contains("code=MODULE_SOURCE_FAIL")
                && infra.detail().contains("file=Source_module_lib.java"),
            "the throwing companion's stack frame reaches the capture "
                + "(file=Source_module_lib.java, code=MODULE_SOURCE_FAIL), "
                + "got: " + execution);
    }

    private static void hostBoundaryCodeParity() throws Exception {
        // The E8010/E8011 host-boundary fixtures produce the same codes
        // the Lua lane pins, through the deployed bad_string.java /
        // missing_export.java triplets.
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
                    && infra.detail().contains("code=" + code),
                "the host-boundary fixture " + corpusPath + " captures the "
                    + "Lua-pinned code " + code + ", got: " + execution);
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

    // =========================================================================
    // Registry probes (G2/G8 pre-flip tolerance)
    // =========================================================================

    private static void registryValidatesAgainstRealCorpus() throws Exception {
        JvmLane lane = new JvmLane(CORPUS_ROOT);
        check(lane.registryDefects().isEmpty(),
            "the absorbed skip registry validates cleanly against the real "
                + "corpus (zero stale entries): " + lane.registryDefects());
        List<DifferentialGate.PreFlipSkipEntry> entries =
            lane.preFlipSkipRegistry().entries();
        check(entries.size() == SKIP_REGISTRY_ENTRIES,
            "the absorbed registry carries exactly the "
                + SKIP_REGISTRY_ENTRIES + " live entries, got "
                + entries.size());
        Set<String> gapIds = new LinkedHashSet<>();
        for (DifferentialGate.PreFlipSkipEntry entry : entries) {
            gapIds.add(entry.gapId());
        }
        check(gapIds.containsAll(Set.of("JVM-GAP-STDJSON",
                "JVM-GAP-JSONABLE-RESIDUAL", "JVM-GAP-HOST-ABI-SHAPES",
                "JVM-GAP-BYTES", "JVM-GAP-DEFAULTS-PLANS",
                "JVM-GAP-ASYNC-FNEXPR")),
            "the absorbed registry spans the six live gap families, got: "
                + gapIds);
    }

    /**
     * TEST DOUBLE — a scratch lane whose registry carries exactly one
     * injected synthetic entry (the absorbed real registry would
     * otherwise be validated against the scratch corpus by the gate).
     */
    private static final class ScratchRegistryLane extends JvmLane {
        private final Map<String, SkipEntry> entries;

        ScratchRegistryLane(Path conformanceRoot, String path,
                String gapId) {
            super(conformanceRoot);
            this.entries = Map.of(path, new SkipEntry(path,
                "scratch injected registry entry", gapId));
            validateRegistry(); // re-validate with the scratch registry
        }

        @Override
        protected Map<String, SkipEntry> skipRegistry() {
            // During the super constructor's validation this override is
            // dispatched before {@code entries} is assigned — fall back
            // to the real registry then; the re-validation below runs
            // with the scratch registry active.
            return entries != null ? entries : super.skipRegistry();
        }
    }

    private static void laneRegistryStaleEntryProbe() throws Exception {
        // An injected synthetic stale registry entry (scratch) fails with
        // a promotion instruction — the forced-promotion rules stay
        // active until the flip deletes the registry (G2).
        ScratchFixture scratch = writeScratchFixture(
            "backend-runtime/scratch/value-probe.deal", VALUE_PROBE_SOURCE);
        JvmLane lane = new ScratchRegistryLane(scratch.root(),
            "backend-runtime/scratch/does-not-exist.deal",
            "JVM-GAP-SCRATCH-STALE");
        check(!lane.registryDefects().isEmpty()
                && lane.registryDefects().stream().anyMatch(d ->
                    d.contains("does-not-exist.deal")
                        && d.contains("promotion instruction")
                        && d.contains("remove the skip-registry entry")),
            "an injected stale registry entry naming a missing fixture "
                + "yields a defect with the promotion instruction, got: "
                + lane.registryDefects());
    }

    /**
     * TEST DOUBLES — stub lanes for the non-JVM backends of the gate-run
     * registry probes. Each returns a runtime-ok execution matching the
     * scratch sidecars (empty transcript, exit 0), so the gate's verdict
     * focuses on the jvm lane outcomes under test. Flagged as test
     * doubles; the production lanes are never replaced in a real run.
     */
    private static final class StubLane implements Lane {
        private final String name;

        StubLane(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public LaneExecution execute(LaneCase laneCase) {
            return new LaneExecution.Executed(new byte[0], new byte[0], 0);
        }
    }

    private static final Map<String, Lane> STUB_LANES = Map.of(
        "luajit", new StubLane("luajit"),
        "js", new StubLane("js"));

    private static final String STDJSON_SCRATCH_SOURCE =
        "// @spec: Standard library declarations — std/json\n"
        + "// @description: scratch registry-tracked std/json probe\n"
        + "// @expected: runtime-ok\n"
        + "// @features: scratch\n"
        + "\n"
        + "import * as json from \"std/json\"\n"
        + "\n"
        + "export function test_json(): null {\n"
        + "  let t: {a: int} = json.parse(\"{}\");\n"
        + "  return null;\n"
        + "}\n"
        + "\n"
        + "export function main(): null {\n"
        + "  return null;\n"
        + "}\n";

    private static void gateTrackedNonFatalProbe() throws Exception {
        // A registry-tracked fixture whose jvm outcome still fails (the
        // JvmBackend raises E6000 at import std/json): the gate reports
        // it tracked non-fatal with its gap id — never a gate failure.
        Path scratch = Files.createTempDirectory("gate_scratch_");
        scratchRoots.add(scratch);
        String corpusPath = "backend-runtime/scratch/stdjson-probe.deal";
        writeScratchCorpusFixture(scratch, corpusPath, STDJSON_SCRATCH_SOURCE,
            RUNTIME_OK_SIDECAR);
        JvmLane lane = new ScratchRegistryLane(scratch, corpusPath,
            "JVM-GAP-SCRATCH-STDJSON");
        Map<String, Lane> lanes = new LinkedHashMap<>(STUB_LANES);
        lanes.put("jvm", lane);
        java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
        DifferentialGate.GateRun run = DifferentialGate.run(scratch, lanes, 2,
            Duration.ofSeconds(120), new java.io.PrintStream(captured, true,
                StandardCharsets.UTF_8),
            Map.of("jvm", lane.preFlipSkipRegistry()));
        check(run.ok(), "the registry-tracked failing fixture is non-fatal "
            + "(the gate passes), got failures: " + run.failures());
        check(run.skipRegistryTracked() == 1,
            "exactly one registry-tracked fixture is reported non-fatal, "
                + "got " + run.skipRegistryTracked());
        check(run.failures().stream().noneMatch(
                f -> f.subject().contains(corpusPath)),
            "the tracked fixture contributes no gate failure");
        String report = captured.toString(StandardCharsets.UTF_8);
        check(report.contains("SKIP-REGISTRY (tracked non-fatal, "
                + "JVM-GAP-SCRATCH-STDJSON)")
                && report.contains(corpusPath),
            "the tracked fixture is reported non-fatal with its gap id");
        check(run.skipped() == 0,
            "the pre-flip Skipped counter stays zero");
    }

    private static void gateStalePassingProbe() throws Exception {
        // A registry entry on a fixture that passes on the jvm lane:
        // the stale entry fails the gate with a promotion instruction.
        Path scratch = Files.createTempDirectory("gate_scratch_");
        scratchRoots.add(scratch);
        String corpusPath = "backend-runtime/scratch/value-probe.deal";
        String source =
            "// @spec: Control flow — Conditional\n"
            + "// @description: scratch registry-tracked passing probe\n"
            + "// @expected: runtime-ok\n"
            + "// @features: scratch\n"
            + "\n"
            + VALUE_PROBE_SOURCE;
        writeScratchCorpusFixture(scratch, corpusPath, source,
            RUNTIME_OK_SIDECAR);
        JvmLane lane = new ScratchRegistryLane(scratch, corpusPath,
            "JVM-GAP-SCRATCH-STALE-PASS");
        Map<String, Lane> lanes = new LinkedHashMap<>(STUB_LANES);
        lanes.put("jvm", lane);
        java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
        DifferentialGate.GateRun run = DifferentialGate.run(scratch, lanes, 2,
            Duration.ofSeconds(120), new java.io.PrintStream(captured, true,
                StandardCharsets.UTF_8),
            Map.of("jvm", lane.preFlipSkipRegistry()));
        check(!run.ok(), "a registry entry on a passing fixture fails the "
            + "gate");
        check(run.failures().stream().anyMatch(f ->
                f.kind().equals("stale skip-registry")
                    && f.subject().equals(corpusPath)
                    && f.detail().contains(
                        "promotion instruction: remove the skip-registry "
                            + "entry")
                    && f.detail().contains("JVM-GAP-SCRATCH-STALE-PASS")),
            "the stale passing entry carries the promotion instruction "
                + "naming the gap id, got: " + run.failures());
        check(run.skipRegistryTracked() == 0,
            "a stale passing entry is never tracked");
    }

    private static void gateMissingEntryProbe() throws Exception {
        // A registry entry naming a fixture absent from the corpus: the
        // gate fails with the promotion instruction (stale entry —
        // fixture missing).
        Path scratch = Files.createTempDirectory("gate_scratch_");
        scratchRoots.add(scratch);
        String corpusPath = "backend-runtime/scratch/value-probe.deal";
        String source =
            "// @spec: Control flow — Conditional\n"
            + "// @description: scratch value probe\n"
            + "// @expected: runtime-ok\n"
            + "// @features: scratch\n"
            + "\n"
            + VALUE_PROBE_SOURCE;
        writeScratchCorpusFixture(scratch, corpusPath, source,
            RUNTIME_OK_SIDECAR);
        JvmLane lane = new ScratchRegistryLane(scratch,
            "backend-runtime/scratch/does-not-exist.deal",
            "JVM-GAP-SCRATCH-STALE-MISSING");
        Map<String, Lane> lanes = new LinkedHashMap<>(STUB_LANES);
        lanes.put("jvm", lane);
        java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
        DifferentialGate.GateRun run = DifferentialGate.run(scratch, lanes, 2,
            Duration.ofSeconds(120), new java.io.PrintStream(captured, true,
                StandardCharsets.UTF_8),
            Map.of("jvm", lane.preFlipSkipRegistry()));
        check(!run.ok(), "a registry entry naming a missing fixture fails "
            + "the gate");
        check(run.failures().stream().anyMatch(f ->
                f.kind().equals("stale skip-registry")
                    && f.detail().contains("missing from the corpus")
                    && f.detail().contains(
                        "promotion instruction: remove the skip-registry "
                            + "entry")),
            "the missing-fixture entry carries the promotion instruction, "
                + "got: " + run.failures());
    }

    private static void gateInfrastructureNotToleratedProbe()
            throws Exception {
        // A registry-tracked fixture whose jvm outcome is a corpus error
        // (missing host implementation): infrastructure outcomes are
        // never tracked — the gate fails (G6).
        Path scratch = Files.createTempDirectory("gate_scratch_");
        scratchRoots.add(scratch);
        String corpusPath = "backend-runtime/scratch/ghost-host.deal";
        String source =
            "// @spec: Modules, declarations, standard library, and host ABI\n"
            + "// @description: scratch host probe\n"
            + "// @expected: runtime-ok\n"
            + "// @features: scratch\n"
            + "\n"
            + "import * as host from \"host/ghost\"\n"
            + "\n"
            + "export function test_ghost(): null {\n"
            + "  host.ping();\n"
            + "  return null;\n"
            + "}\n"
            + "\n"
            + "export function main(): null {\n"
            + "  return null;\n"
            + "}\n";
        writeScratchCorpusFixture(scratch, corpusPath, source,
            RUNTIME_OK_SIDECAR);
        Path hostDir = scratch.resolve("host-fixtures");
        Files.createDirectories(hostDir);
        Files.writeString(hostDir.resolve("ghost.d.deal"),
            "export function ping(): string;\n");
        JvmLane lane = new ScratchRegistryLane(scratch, corpusPath,
            "JVM-GAP-SCRATCH-HOST");
        Map<String, Lane> lanes = new LinkedHashMap<>(STUB_LANES);
        lanes.put("jvm", lane);
        java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
        DifferentialGate.GateRun run = DifferentialGate.run(scratch, lanes, 2,
            Duration.ofSeconds(120), new java.io.PrintStream(captured, true,
                StandardCharsets.UTF_8),
            Map.of("jvm", lane.preFlipSkipRegistry()));
        check(!run.ok(), "a registry-tracked corpus error stays gate-fatal "
            + "(infrastructure is never tracked)");
        check(run.skipRegistryTracked() == 0,
            "the corpus-error outcome is never tracked non-fatal");
        check(run.failures().stream().anyMatch(f ->
                f.kind().equals("infrastructure")
                    && f.detail().contains("corpus error")
                    && f.detail().contains("ghost.java")),
            "the missing-host corpus error is a gate failure naming the "
                + "missing file, got: " + run.failures());
    }
}
