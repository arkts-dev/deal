package deal.test.conformance;

import deal.test.ConformanceHarnessMetadata;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Lua lane tests (ISSUE-0354 Verification): the LuaJIT lane of the v1.2
 * differential gate executed through the gate core's dispatch/verdict
 * path (the G7 worker pool + the StructuredExpectationComparator), over
 * real corpus fixtures with their authored sidecars plus scratch
 * controlled-experiment fixtures.
 *
 * <p>The suite pins the Shared Lane Contract surfaces:</p>
 * <ul>
 *   <li>Real fixtures (runtime-ok/runtime-error, transcripts, exit
 *       codes, error snapshots, companion-throw {@code sourceFile}, async
 *       export completion, host triplets) produce matching verdicts.</li>
 *   <li>Invocation contract: declaration-order auto-invocation of the
 *       non-{@code $} zero-arity exports; return values discarded;
 *       {@code main} runs exactly once (the backend entry contract).</li>
 *   <li>Framing (G4.6): the canonical snapshot built by the shared
 *       {@link ErrorSnapshot} serializer; the sidecar is the
 *       authoritative field set — unpinned optionals are suppressed and
 *       a pinned field the captured error does not carry is never
 *       fabricated (the case fails honestly).</li>
 *   <li>Infrastructure: {@code ARTIFACT_MISSING} on a deleted generated
 *       artifact, {@code TOOL_MISSING} on a failed tool probe, an
 *       unmappable captured {@code file} emitted verbatim, compile
 *       failures as lane compile failures, and raw (non-DEAL) subprocess
 *       errors as process failures.</li>
 * </ul>
 *
 * <p><b>TEST DOUBLES:</b> the scratch {@link LuaLane} subclasses in this
 * file are controlled-experiment doubles (the task's Verification 4/5
 * scratch variants) — the production {@code LuaLane} executes every real
 * fixture. Each double is flagged as such in the test code.</p>
 */
public class LuaLaneTest {

    private static int passed = 0;
    private static int failed = 0;

    /** The scratch fixtures are header-free, so raw == stripped coordinates. */
    private static final Path CORPUS_ROOT = Path.of("test", "conformance");

    public static void main(String[] args) throws Exception {
        System.out.println("=== Lua Lane Tests (ISSUE-0354) ===\n");

        realFixtureRuntimeOk();
        realFixtureConsoleTranscript();
        realFixtureRuntimeErrorTypeMismatch();
        realFixtureRuntimeErrorIntDivZero();
        realFixtureTimeNowMillisSpanless();
        spanPinnedHonestFailureProbe();
        companionThrowSourceFile();
        hostTripletRuntimeOk();
        asyncExportCompletion();
        invocationDeclarationOrderProbe();
        asyncScratchCompletionProbe();
        discardedReturnValuesProbe();
        printReturnValueDivergenceProbe();
        invokeMainTwiceDivergenceProbe();
        framesSuppressionProbe();
        framesPinnedHonestFailureProbe();
        perturbedSnapshotFieldProbe();
        artifactPresenceProbe();
        codegenFailureProbe();
        laneDeadlineProbe();
        unmappableFileProbe();
        toolMissingProbe();
        compileFailureProbe();
        nonDealErrorProbe();

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
        return new LaneCase(fixturePath, "luajit", fixtureFile,
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
            Map.of("luajit", lane), 2, Duration.ofSeconds(120));
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
        return new LaneCase(corpusPath, "luajit", real.fixture().file(),
            CorpusDiscovery.compilationSet(real.fixture(),
                indexOf(CORPUS_ROOT), CORPUS_ROOT.toAbsolutePath().normalize()),
            real.sidecar().expectationFor("luajit"));
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

    // =========================================================================
    // Real fixtures through the gate core + lane
    // =========================================================================

    private static void realFixtureRuntimeOk() throws Exception {
        String corpusPath = "backend-runtime/control-flow/for-of-string.deal";
        assertPassed("real runtime-ok (for-of-string)", new LuaLane(CORPUS_ROOT),
            realLaneCase(corpusPath));
    }

    private static void realFixtureConsoleTranscript() throws Exception {
        String corpusPath = "backend-runtime/stdlib/console/import-log.deal";
        assertPassed("real runtime-ok console transcript (import-log, "
            + "\"hello\\n\")", new LuaLane(CORPUS_ROOT),
            realLaneCase(corpusPath));
    }

    private static void realFixtureRuntimeErrorTypeMismatch() throws Exception {
        String corpusPath =
            "backend-runtime/runtime-errors/type-mismatch-e8001.deal";
        assertPassed("real runtime-error (type-mismatch-e8001 with "
            + "expected/actual pins)", new LuaLane(CORPUS_ROOT),
            realLaneCase(corpusPath));
    }

    private static void realFixtureRuntimeErrorIntDivZero() throws Exception {
        String corpusPath = "backend-runtime/arithmetic/int-div-zero.deal";
        assertPassed("real runtime-error (int-div-zero, no optional pins)",
            new LuaLane(CORPUS_ROOT), realLaneCase(corpusPath));
    }

    /**
     * The one sanctioned span-less runtime-error shape: the retained
     * {@code std/time.nowMillis} wrapper raises E8004 with no
     * file/line/column (the E8004 carries the route's existing shape —
     * {@code luajit-time-selector-disposition}, Failure and operations),
     * and the fixture's sidecar omits the whole span group. The lane
     * emits the span-less snapshot exactly as captured, the comparator
     * accepts it, and the verdict passes — the producible oracle behind
     * the deferred differential-gate dispatch of this fixture.
     */
    private static void realFixtureTimeNowMillisSpanless() throws Exception {
        String corpusPath =
            "backend-runtime/stdlib-edge/time-now-millis-positive.deal";
        LuaLane lane = new LuaLane(CORPUS_ROOT);
        LaneCase laneCase = realLaneCase(corpusPath);
        assertPassed("real span-less runtime-error (time-now-millis-positive)",
            lane, laneCase);
        LaneExecution execution = lane.execute(laneCase);
        check(execution instanceof LaneExecution.Executed,
            "the time-fixture run is a real execution, got: " + execution);
        if (execution instanceof LaneExecution.Executed executed) {
            String stdout = new String(executed.stdout(),
                StandardCharsets.UTF_8);
            check(stdout.endsWith("DEAL_ERROR_CODE: E8004\n"
                    + "DEAL_ERROR_SNAPSHOT: {\"code\":\"E8004\","
                    + "\"message\":\"int out of safe range\"}\n"),
                "the span-less snapshot carries exactly code and message "
                    + "with no fabricated sourceFile/line/column, got: "
                    + stdout.replace("\n", "\\n"));
            check(executed.exitCode() == 1,
                "the time-fixture run exits 1");
        }
    }

    /**
     * The never-fabricate guard: pairing the real time fixture (whose
     * captured E8004 carries no span) with a sidecar that pins a
     * call-site span must be an honest infrastructure failure
     * (PROCESS_FAILURE naming the absent span) — the lane never
     * fabricates a pinned field the captured error does not carry.
     */
    private static void spanPinnedHonestFailureProbe() throws Exception {
        String corpusPath =
            "backend-runtime/stdlib-edge/time-now-millis-positive.deal";
        RealCase real = realCase(corpusPath);
        SidecarExpectations.ErrorExpectation fabricated = error("E8004",
            "int out of safe range", corpusPath, 9, 18);
        LaneCase laneCase = new LaneCase(corpusPath, "luajit",
            real.fixture().file(),
            CorpusDiscovery.compilationSet(real.fixture(),
                indexOf(CORPUS_ROOT),
                CORPUS_ROOT.toAbsolutePath().normalize()),
            runtimeError(fabricated));
        LuaLane lane = new LuaLane(CORPUS_ROOT);
        LaneExecution execution = lane.execute(laneCase);
        check(execution instanceof LaneExecution.Infrastructure infra
                && infra.clazz() == MismatchClass.PROCESS_FAILURE
                && infra.detail().contains("never")
                && infra.detail().contains("fabricat")
                && infra.detail().contains("no span"),
            "a span-pinning sidecar against the span-less captured error "
                + "is an honest PROCESS_FAILURE (never fabricated), got: "
                + execution);
    }

    private static void companionThrowSourceFile() throws Exception {
        // The throwing companion's corpus-relative path must reach the
        // snapshot through the deployment map (corpus C2).
        String corpusPath =
            "backend-runtime/module-failures/imported-function-explicit-error.deal";
        LuaLane lane = new LuaLane(CORPUS_ROOT);
        LaneCase laneCase = realLaneCase(corpusPath);
        assertPassed("companion-throw (imported-function-explicit-error)",
            lane, laneCase);
        LaneExecution execution = lane.execute(laneCase);
        check(execution instanceof LaneExecution.Executed,
            "the companion-throw run is a real execution");
        if (execution instanceof LaneExecution.Executed executed) {
            String stdout = new String(executed.stdout(),
                StandardCharsets.UTF_8);
            check(stdout.contains("\"sourceFile\":"
                    + "\"backend-runtime/module-failures/"
                    + "explicit_fail_lib.deal\""),
                "the snapshot's sourceFile names the throwing companion "
                    + "corpus path, got: " + stdout);
            check(stdout.contains("DEAL_ERROR_CODE: MODULE_EXPLICIT_FAIL\n")
                    && executed.exitCode() == 1,
                "the companion-throw framing carries the code and exit 1");
        }
    }

    private static void hostTripletRuntimeOk() throws Exception {
        String corpusPath =
            "backend-runtime/host-abi/host-extra-export-ignored.deal";
        assertPassed("host triplet runtime-ok (host-extra-export-ignored "
            + "through host/extra_export.lua)", new LuaLane(CORPUS_ROOT),
            realLaneCase(corpusPath));
    }

    private static void asyncExportCompletion() throws Exception {
        assertPassed("async export awaited (async-simple-await)",
            new LuaLane(CORPUS_ROOT),
            realLaneCase("backend-runtime/async-await/async-simple-await.deal"));
        assertPassed("async import await (async-import-await)",
            new LuaLane(CORPUS_ROOT),
            realLaneCase("backend-runtime/async-await/async-import-await.deal"));
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
        // the G4.4 contract replacing the absorbed runner's unordered
        // `pairs` auto-invocation.
        String corpusPath = "backend-runtime/scratch/order-probe.deal";
        ScratchFixture scratch = writeScratchFixture(corpusPath,
            ORDER_PROBE_SOURCE);
        LaneCase laneCase = laneCase(corpusPath, scratch.file(),
            scratch.strippedSource(), runtimeOk("first\nsecond\n"));
        assertPassed("declaration-order invocation (first then second)",
            new LuaLane(scratch.root()), laneCase);
    }

    private static void asyncScratchCompletionProbe() throws Exception {
        // An async export that logs after its await: the invocation must
        // drive the operation to completion before the verdict.
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
            new LuaLane(scratch.root()), laneCase);
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
            new LuaLane(scratch.root()), laneCase);
    }

    /**
     * TEST DOUBLE — a scratch lane whose runner prints every invocation's
     * return value (the G4.4 divergence the comparator must catch).
     */
    private static final class PrintReturnValueLane extends LuaLane {
        PrintReturnValueLane(Path conformanceRoot) {
            super(conformanceRoot);
        }

        @Override
        protected String buildRunner(String generatedLua,
                List<String> orderedZeroArityExports) {
            StringBuilder exports = new StringBuilder("{");
            for (String name : orderedZeroArityExports) {
                exports.append(' ').append(luaStringLiteral(name)).append(',');
            }
            exports.append(" }");
            return "package.path = './?.lua;./std/?.lua;' .. package.path\n"
                + "local __lane_exports = " + exports + "\n"
                + "local __ok, __err = xpcall(function()\n"
                + "  local __mod = (function()\n"
                + generatedLua + "\n"
                + "  end)()\n"
                + "  if type(__mod) == 'table' then\n"
                + "    for __i = 1, #__lane_exports do\n"
                + "      local __v = __mod[__lane_exports[__i]]\n"
                + "      if type(__v) == 'table' and __v.__kind == 'function'"
                + " then\n"
                + "        print(__v.f())\n"
                + "      end\n"
                + "    end\n"
                + "  end\n"
                + "end, function(__err) end)\n"
                + "if not __ok then os.exit(1) end\n";
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
                && outcome.mismatch().get().subject().equals("luajit")
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
        // The production lane: main() runs exactly once (the backend entry
        // contract at chunk end), so the pinned "main\n" passes.
        String corpusPath = "backend-runtime/scratch/main-probe.deal";
        ScratchFixture scratch = writeScratchFixture(corpusPath,
            MAIN_PROBE_SOURCE);
        LaneCase laneCase = laneCase(corpusPath, scratch.file(),
            scratch.strippedSource(), runtimeOk("main\n"));
        assertPassed("main() executes exactly once (production lane)",
            new LuaLane(scratch.root()), laneCase);

        // TEST DOUBLE: a scratch lane that re-invokes main after the loop.
        LuaLane doubleMainLane = new LuaLane(scratch.root()) {
            @Override
            protected String buildRunner(String generatedLua,
                    List<String> orderedZeroArityExports) {
                StringBuilder exports = new StringBuilder("{");
                for (String name : orderedZeroArityExports) {
                    exports.append(' ').append(luaStringLiteral(name))
                        .append(',');
                }
                exports.append(" }");
                return "package.path = './?.lua;./std/?.lua;' .. package.path\n"
                    + "local __lane_exports = " + exports + "\n"
                    + "local __ok, __err = xpcall(function()\n"
                    + "  local __mod = (function()\n"
                    + generatedLua + "\n"
                    + "  end)()\n"
                    + "  if type(__mod) == 'table' then\n"
                    + "    for __i = 1, #__lane_exports do\n"
                    + "      local __v = __mod[__lane_exports[__i]]\n"
                    + "      if type(__v) == 'table' and __v.__kind"
                    + " == 'function' then\n"
                    + "        __v.f()\n"
                    + "      end\n"
                    + "    end\n"
                    + "    local __m = __mod['main']\n"
                    + "    if type(__m) == 'table' and __m.__kind"
                    + " == 'function' then\n"
                    + "      __m.f()\n"
                    + "    end\n"
                    + "  end\n"
                    + "end, function(__err) end)\n"
                    + "if not __ok then os.exit(1) end\n";
            }
        };
        GateDispatcher.LaneOutcome outcome = dispatch(doubleMainLane,
            laneCase);
        check(!outcome.passed(), "a lane invoking main() twice fails the "
            + "case (the pinned transcript carries one \"main\\n\")");
        check(outcome.mismatch().isPresent()
                && outcome.mismatch().get().clazz()
                    == MismatchClass.TRANSCRIPT_MISMATCH
                && outcome.mismatch().get().subject().equals("luajit")
                && outcome.mismatch().get().detail()
                    .contains("stdout differs"),
            "the double-main divergence is TRANSCRIPT_MISMATCH naming the "
                + "backend, got: " + outcome.mismatch());
    }

    // =========================================================================
    // Framing probes (Verification 5)
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
     * TEST DOUBLE — a scratch lane whose runner attaches a synthetic
     * {@code frames = 2} field to the captured error before transport
     * (simulating a runtime whose captured error carries frames).
     */
    private static final class FramesCarryingLane extends LuaLane {
        FramesCarryingLane(Path conformanceRoot) {
            super(conformanceRoot);
        }

        @Override
        protected String buildRunner(String generatedLua,
                List<String> orderedZeroArityExports) {
            StringBuilder exports = new StringBuilder("{");
            for (String name : orderedZeroArityExports) {
                exports.append(' ').append(luaStringLiteral(name)).append(',');
            }
            exports.append(" }");
            return "package.path = './?.lua;./std/?.lua;' .. package.path\n"
                + transportWriterLua()
                + "local __lane_exports = " + exports + "\n"
                + "local __ok, __err = xpcall(function()\n"
                + "  local __mod = (function()\n"
                + generatedLua + "\n"
                + "  end)()\n"
                + "  if type(__mod) == 'table' then\n"
                + "    for __i = 1, #__lane_exports do\n"
                + "      local __v = __mod[__lane_exports[__i]]\n"
                + "      if type(__v) == 'table' and __v.__kind"
                + " == 'function' then\n"
                + "        __v.f()\n"
                + "      end\n"
                + "    end\n"
                + "  end\n"
                + "end, function(__err)\n"
                + "  if type(__err) == 'table' and __err.code ~= nil then\n"
                + "    __err.frames = 2\n"
                + "    __lane_write_transport(__err)\n"
                + "  end\n"
                + "end)\n"
                + "if not __ok then os.exit(1) end\n";
        }
    }

    private static void framesSuppressionProbe() throws Exception {
        // The sidecar omits `frames`; the captured error carries
        // frames = 2. The lane must suppress the unpinned field (the
        // sidecar is the authoritative field set) — the verdict passes.
        String corpusPath = "backend-runtime/scratch/frame-probe.deal";
        ScratchFixture scratch = writeScratchFixture(corpusPath,
            FRAME_PROBE_SOURCE);
        SidecarExpectations.ErrorExpectation pinned = error("FRAME_PROBE",
            "frame probe", corpusPath, 2, 3);
        LaneCase laneCase = laneCase(corpusPath, scratch.file(),
            scratch.strippedSource(), runtimeError(pinned));
        Lane lane = new FramesCarryingLane(scratch.root());
        assertPassed("the lane suppresses the unpinned optional field "
            + "'frames' (the sidecar is the authoritative field set)",
            lane, laneCase);
        LaneExecution execution = lane.execute(laneCase);
        check(execution instanceof LaneExecution.Executed,
            "the frames-suppression run is a real execution");
        if (execution instanceof LaneExecution.Executed executed) {
            String stdout = new String(executed.stdout(),
                StandardCharsets.UTF_8);
            check(!stdout.contains("frames"),
                "the emitted snapshot carries no frames field, got: "
                    + stdout);
        }
    }

    private static void framesPinnedHonestFailureProbe() throws Exception {
        // The sidecar pins `frames: 2` but the captured error carries
        // none: the lane never fabricates the field, so the snapshot
        // omits it and the case fails honestly.
        String corpusPath = "backend-runtime/scratch/frame-probe.deal";
        ScratchFixture scratch = writeScratchFixture(corpusPath,
            FRAME_PROBE_SOURCE);
        SidecarExpectations.ErrorExpectation pinned =
            new SidecarExpectations.ErrorExpectation("FRAME_PROBE",
                "frame probe", corpusPath, 2, 3, Optional.empty(),
                Optional.empty(), Optional.of(2), Optional.empty());
        LaneCase laneCase = laneCase(corpusPath, scratch.file(),
            scratch.strippedSource(), runtimeError(pinned));
        Lane lane = new LuaLane(scratch.root());
        GateDispatcher.LaneOutcome outcome = dispatch(lane, laneCase);
        check(!outcome.passed(),
            "a sidecar pinning frames for a fixture whose captured error "
                + "carries none fails honestly (the lane never fabricates)");
        check(outcome.mismatch().isPresent()
                && outcome.mismatch().get().subject().equals("luajit")
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
                "the lane never fabricates the unpinned-carried frames "
                    + "field, got: " + stdout);
        }
    }

    /**
     * TEST DOUBLE — a scratch lane whose deployment lookup rebases the
     * captured line by one extra header line (perturbing exactly the
     * snapshot's line field).
     */
    private static final class LinePerturbingLane extends LuaLane {
        LinePerturbingLane(Path conformanceRoot) {
            super(conformanceRoot);
        }

        @Override
        protected DeploymentEntry deploymentEntryFor(String capturedFile,
                LuaCompilation compilation) {
            DeploymentEntry entry =
                super.deploymentEntryFor(capturedFile, compilation);
            if (entry == null) {
                return null;
            }
            return new DeploymentEntry(entry.corpusPath(),
                entry.headerLinesStripped() + 1);
        }
    }

    private static void perturbedSnapshotFieldProbe() throws Exception {
        // A scratch lane perturbing one snapshot field (line) fails the
        // comparison naming the backend and the first differing byte;
        // the emitted snapshot differs in exactly that field.
        String corpusPath = "backend-runtime/scratch/frame-probe.deal";
        ScratchFixture scratch = writeScratchFixture(corpusPath,
            FRAME_PROBE_SOURCE);
        SidecarExpectations.ErrorExpectation pinned = error("FRAME_PROBE",
            "frame probe", corpusPath, 2, 3);
        LaneCase laneCase = laneCase(corpusPath, scratch.file(),
            scratch.strippedSource(), runtimeError(pinned));
        Lane lane = new LinePerturbingLane(scratch.root());
        GateDispatcher.LaneOutcome outcome = dispatch(lane, laneCase);
        check(!outcome.passed(),
            "a lane perturbing one snapshot field fails the case");
        check(outcome.mismatch().isPresent()
                && outcome.mismatch().get().subject().equals("luajit")
                && outcome.mismatch().get().clazz()
                    == MismatchClass.TRANSCRIPT_MISMATCH
                && outcome.mismatch().get().detail()
                    .contains("stdout differs at byte"),
            "the perturbation fails the byte comparison naming the backend "
                + "and the first differing byte, got: " + outcome.mismatch());
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
                check(fields.line() == 3 && fields.code().equals("FRAME_PROBE")
                        && fields.message().equals("frame probe")
                        && fields.sourceFile().equals(corpusPath)
                        && fields.column() == 3,
                    "exactly the line field is perturbed (3 vs pinned 2), "
                        + "every other field matches, got: " + fields);
            }
        }
    }

    // =========================================================================
    // Infrastructure probes
    // =========================================================================

    private static void artifactPresenceProbe() throws Exception {
        // TEST DOUBLE: a scratch lane that deletes the deployed runner
        // before the presence assertion — the lane must report
        // ARTIFACT_MISSING instead of fabricating a result.
        String corpusPath = "backend-runtime/scratch/order-probe.deal";
        ScratchFixture scratch = writeScratchFixture(corpusPath,
            ORDER_PROBE_SOURCE);
        LaneCase laneCase = laneCase(corpusPath, scratch.file(),
            scratch.strippedSource(), runtimeOk("first\nsecond\n"));
        LuaLane lane = new LuaLane(scratch.root()) {
            @Override
            protected void beforeExecution(Path workspaceDirectory) {
                try {
                    Files.deleteIfExists(
                        workspaceDirectory.resolve(RUNNER_FILE_NAME));
                } catch (IOException ignored) {
                    // The probe deletes the artifact; the lane must see
                    // it missing.
                }
            }
        };
        LaneExecution execution = lane.execute(laneCase);
        check(execution instanceof LaneExecution.Infrastructure infra
                && infra.clazz() == MismatchClass.ARTIFACT_MISSING
                && infra.detail().contains("test_main.lua"),
            "deleting a generated artifact before execution yields "
                + "ARTIFACT_MISSING naming the artifact, got: " + execution);
    }

    private static void codegenFailureProbe() throws Exception {
        // An entry module without main(): the real backend emits E6004
        // and no valid artifact — the lane reports ARTIFACT_MISSING,
        // never a fabricated runner (G4.2).
        String source =
            "export function test_nothing(): null {\n"
            + "  return null;\n"
            + "}\n";
        String corpusPath = "backend-runtime/scratch/no-main.deal";
        ScratchFixture scratch = writeScratchFixture(corpusPath, source);
        LaneCase laneCase = laneCase(corpusPath, scratch.file(),
            scratch.strippedSource(), runtimeOk(""));
        LaneExecution execution = new LuaLane(scratch.root()).execute(laneCase);
        check(execution instanceof LaneExecution.Infrastructure infra
                && infra.clazz() == MismatchClass.ARTIFACT_MISSING
                && infra.detail().contains("E6004")
                && infra.detail().contains("generated Lua artifact"),
            "a backend codegen failure (E6004) yields ARTIFACT_MISSING "
                + "naming the missing artifact, got: " + execution);
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
            Map.of("luajit", new LuaLane(scratch.root())), 2,
            Duration.ofMillis(800));
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
        GateDispatcher.LaneOutcome outcome = verdicts.get(0).outcomes().get(0);
        check(outcome.mismatch().isPresent()
                && outcome.mismatch().get().clazz()
                    == MismatchClass.LANE_TIMEOUT
                && outcome.mismatch().get().infrastructure()
                && outcome.mismatch().get().subject().equals("luajit"),
            "a hung lane subprocess is LANE_TIMEOUT naming the backend, "
                + "got: " + outcome.mismatch());
        check(elapsedMillis < 30_000,
            "the harness-owned deadline releases the lane instead of "
                + "blocking on the hung luajit process (elapsed "
                + elapsedMillis + " ms)");
        check(!outcome.passed(),
            "the infrastructure outcome never satisfies the case");
    }

    private static void unmappableFileProbe() throws Exception {
        // TEST DOUBLE: a scratch lane with the deployment map stripped —
        // the captured file is emitted verbatim so the byte comparison
        // fails and surfaces the defect.
        String corpusPath = "backend-runtime/scratch/frame-probe.deal";
        ScratchFixture scratch = writeScratchFixture(corpusPath,
            FRAME_PROBE_SOURCE);
        SidecarExpectations.ErrorExpectation pinned = error("FRAME_PROBE",
            "frame probe", corpusPath, 2, 3);
        LaneCase laneCase = laneCase(corpusPath, scratch.file(),
            scratch.strippedSource(), runtimeError(pinned));
        LuaLane lane = new LuaLane(scratch.root()) {
            @Override
            protected DeploymentEntry deploymentEntryFor(
                    String capturedFile, LuaCompilation compilation) {
                return null; // the deployment map is stripped
            }
        };
        LaneExecution execution = lane.execute(laneCase);
        check(execution instanceof LaneExecution.Executed,
            "the unmappable-file run is a real execution");
        String absolute = scratch.file().toAbsolutePath().normalize()
            .toString();
        if (execution instanceof LaneExecution.Executed executed) {
            String stdout = new String(executed.stdout(),
                StandardCharsets.UTF_8);
            check(stdout.contains(absolute),
                "an unmappable captured file is emitted verbatim (the "
                    + "absolute path reaches the snapshot), got: " + stdout);
        }
        GateDispatcher.LaneOutcome outcome = dispatch(lane, laneCase);
        check(!outcome.passed()
                && outcome.mismatch().isPresent()
                && outcome.mismatch().get().subject().equals("luajit"),
            "the verbatim-file snapshot fails the comparison naming the "
                + "backend, got: " + outcome.mismatch());
    }

    private static void toolMissingProbe() throws Exception {
        // TEST DOUBLE: a scratch lane whose tool probe fails — the lane
        // must report TOOL_MISSING, never a skip (G3).
        String corpusPath = "backend-runtime/scratch/order-probe.deal";
        ScratchFixture scratch = writeScratchFixture(corpusPath,
            ORDER_PROBE_SOURCE);
        LaneCase laneCase = laneCase(corpusPath, scratch.file(),
            scratch.strippedSource(), runtimeOk("first\nsecond\n"));
        LuaLane lane = new LuaLane(scratch.root()) {
            @Override
            protected boolean luajitAvailable() {
                return false;
            }
        };
        LaneExecution execution = lane.execute(laneCase);
        check(execution instanceof LaneExecution.Infrastructure infra
                && infra.clazz() == MismatchClass.TOOL_MISSING
                && infra.detail().contains("luajit"),
            "a failed tool probe yields TOOL_MISSING naming the tool, got: "
                + execution);
    }

    private static void compileFailureProbe() throws Exception {
        // A fixture that fails the real frontend: the lane reports a lane
        // compile failure (an infrastructure outcome with the diagnostic
        // codes), never an execution outcome.
        String source =
            "export function test_bad(): int {\n"
            + "  let x: int = \"not-int\";\n"
            + "  return x;\n"
            + "}\n"
            + "\n"
            + "export function main(): null {\n"
            + "  return null;\n"
            + "}\n";
        String corpusPath = "backend-runtime/scratch/compile-fail.deal";
        ScratchFixture scratch = writeScratchFixture(corpusPath, source);
        LaneCase laneCase = laneCase(corpusPath, scratch.file(),
            scratch.strippedSource(), runtimeOk(""));
        LaneExecution execution = new LuaLane(scratch.root()).execute(laneCase);
        check(execution instanceof LaneExecution.Infrastructure infra
                && infra.clazz() == MismatchClass.PROCESS_FAILURE
                && infra.detail().contains("the lane compilation failed")
                && infra.detail().contains("E3001"),
            "a frontend compile failure is a lane compile failure naming "
                + "the diagnostic code, got: " + execution);
    }

    private static void nonDealErrorProbe() throws Exception {
        // A host triplet implementation raising a raw Lua error: the
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
        Files.writeString(hostDir.resolve("boom.lua"),
            "return {\n"
            + "  ping = function()\n"
            + "    error(\"raw boom\")\n"
            + "  end,\n"
            + "}\n");
        LaneCase laneCase = laneCase(scratch.fixturePath(), scratch.file(),
            scratch.strippedSource(), runtimeOk(""));
        LaneExecution execution = new LuaLane(scratch.root()).execute(laneCase);
        check(execution instanceof LaneExecution.Infrastructure infra
                && infra.clazz() == MismatchClass.PROCESS_FAILURE
                && infra.detail().contains(
                    "outside the DEAL outcome surface")
                && infra.detail().contains("no DEAL error payload"),
            "a raw (non-DEAL) subprocess error is a process failure with "
                + "the bounded detail, got: " + execution);
    }

    /** A Lua double-quoted string literal with minimal escapes. */
    private static String luaStringLiteral(String value) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.append('"').toString();
    }
}
