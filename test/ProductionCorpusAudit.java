package deal.test;

import deal.ast.ExportDeclaration;
import deal.ast.FunctionDeclaration;
import deal.ast.ProgramNode;
import deal.ast.StatementNode;
import deal.codegen.Backend;
import deal.codegen.jvm.JvmBackend;
import deal.diagnostics.CompilerDiagnostic;
import deal.lexer.LexResult;
import deal.lexer.Lexer;
import deal.module.CompilationOrchestrator;
import deal.parser.ParseResult;
import deal.parser.Parser;
import deal.project.ExternalEntry;
import deal.project.ProjectContext;
import deal.project.ProjectLocator;
import deal.semantic.CompilerInvocation;
import deal.semantic.CompilerProfileProvider;
import deal.semantic.ReleaseConfiguration;
import deal.semantic.SemanticLowerer;
import deal.semantic.ir.FailureContractRegistry;
import deal.semantic.ir.LoweringFailureDetail;
import deal.semantic.ir.SemanticCapability;
import deal.semantic.ir.SemanticProfile;
import deal.module.ProductionProjectEmission;
import deal.test.conformance.CorpusDiscovery;
import deal.test.conformance.CorpusFfi;
import deal.test.conformance.ErrorSnapshot;
import deal.test.conformance.GateMismatch;
import deal.test.conformance.LaneExecution;
import deal.test.conformance.SidecarExpectations;
import deal.test.conformance.SidecarSchemaValidator;
import deal.test.conformance.StructuredExpectationComparator;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * The production-corpus acceptance audit over the dispatched set
 * ({@code dispatched-corpus-production-acceptance} A1-A5 and the
 * production-corpus audit contract plus the corpus-invariant contract;
 * {@code conformance-corpus-disposition-and-profile-repin} CD2/CD3/CD5;
 * {@code conformance-cutover-guards-pins-and-production-source-removal}
 * HR4(a); {@code dispatched-corpus-production-realization} R1/R8). It is
 * driven by the retained acceptance suite
 * ({@link ProductionDispatchTest}), which reuses its existing gate
 * registration: the audit adds no corpus-wide gate registration of its own.
 *
 * <p>One drive measures the whole dispatched runtime corpus
 * through the release-owned production pipeline:</p>
 *
 * <ol>
 *   <li><b>The corpus invariants.</b> Every {@code runtime-ok}/
 *       {@code runtime-error} fixture under
 *       {@code test/conformance/backend-runtime/**} that the complete
 *       current validated CD2 discovery classifies as runtime is
 *       dispatched (the accounting is derived from that discovery, never
 *       from a fixed count lock), every fixture's sidecar validates clean
 *       against the landed schema validator and pins all three backends,
 *       and no fixture or sidecar is reshaped by this drive.</li>
 *   <li><b>The lane-equivalent materialization.</b> Every fixture is
 *       written as the entry module of a temp project with the
 *       entry-directory-relative layout the gate's lane rule uses, its
 *       transitively imported companions, the explicit-{@code .deal}
 *       alias copies, every {@code host/<name>} declaration under
 *       {@code bindings/}, the corpus C FFI declarations with the
 *       corpus {@code nativeLibrary} wiring, and one generated
 *       {@code deal.json} whose raw host keys resolve the declaration
 *       files while the compile context carries the dotted typing name
 *       first (the lanes' externals-identity convention).</li>
 *   <li><b>The compile criterion.</b> Every dispatched fixture compiles
 *       on {@link Backend#LUAJIT} and {@link Backend#JVM} under the
 *       release-owned production invocation with zero E6005
 *       {@code CONSTRUCT_UNLOWERED}/{@code RETAINED_ABI_DEFERRED}/
 *       {@code SHARED_EMITTER_COVERAGE}; the extern-C fixtures'
 *       {@code jvm} legs reproduce exactly the pinned E6006 rejection
 *       and stage no artifact. The declaration-importing group's
 *       per-fixture, per-target compile outcomes are part of the
 *       reported measurement (never an E2003 from a non-materializing
 *       probe).</li>
 *   <li><b>The capture criterion.</b> Each producible fixture's staged
 *       artifact is driven with the deferred-entry probe: the deferred
 *       module-init entry ({@code __dealMain()}/{@code dealMain()})
 *       then every remaining zero-arity export once in declaration
 *       order through the export surface, inside protected calls. The
 *       capture is projected to {@code code}, {@code message}, the span
 *       group, {@code expected}, and {@code actual}, rebased onto raw
 *       corpus coordinates by the module's stripped-header delta, and
 *       compared with the sidecar through the landed comparison
 *       surfaces ({@link ErrorSnapshot},
 *       {@link StructuredExpectationComparator}) — mandatory fields
 *       always, the span group and optionals only when pinned, nothing
 *       fabricated.</li>
 *   <li><b>The negative control.</b> A corrupted staged artifact fails
 *       the audit by name (the capture criterion is not vacuous), and a
 *       hand-built guard diagnostic is reported by the guard detector
 *       (the compile criterion is not vacuous). The acceptance policy's
 *       truth table is asserted directly, so the fatality rule itself is
 *       not vacuous. The focused subprocess controls prove the same for
 *       the owned processes: a transcript read failure and a drain that
 *       is still open at its bounded deadline are infrastructure
 *       verdicts (never substituted empty output), and every owned child
 *       runs under the whole-tree teardown — a persistent descendant that
 *       holds the inherited pipes open dies with its process group, the
 *       direct child is reaped, and no owned reader survives the call on
 *       the deadline path or the cancellation path — so the audit-owned
 *       native bootstrap and every drive are deadline- and
 *       cancellation-bounded with nothing left behind. Every owned-tree
 *       removal is fail-closed as well: the sweep attempts every path,
 *       retains each deletion or traversal failure, re-checks the root,
 *       and records a surviving artifact with its owned path and the
 *       fixture/target context as a fatal audit failure, so a leftover
 *       staged program or native library can never leave the run green.</li>
 *   <li><b>The acceptance verdicts and the pre-precondition rule.</b> The
 *       run computes the three acceptance verdicts — zero
 *       {@code CONSTRUCT_UNLOWERED}, zero {@code RETAINED_ABI_DEFERRED}/
 *       {@code SHARED_EMITTER_COVERAGE}, and byte-exact captures on the
 *       producible set — and reports each. While the dispatched corpus
 *       still carries a checker-valid {@code CONSTRUCT_UNLOWERED} deficit
 *       (the sibling-owned realization work has not landed), the audit
 *       runs in its <em>pre-precondition reporting mode</em>: every
 *       failing leg is named by fixture and target, classified, and the
 *       drive reports zero fatal failures, exactly as
 *       {@code conformance-cutover-guards-pins-and-production-source-removal}
 *       HR4(a) and {@code dispatched-corpus-production-acceptance} A5 and
 *       CD5 prescribe ("before it, the corpus run reports the residual
 *       failures per fixture and never skips"). Only three leg classes are
 *       permitted in that mode, and each is bound to its recorded
 *       evidence: the sibling-owned construct deficit (every error of the
 *       leg carries the {@code CONSTRUCT_UNLOWERED} guard), the
 *       evidence-bound projection inventory (the cell, the sidecar's
 *       pinned code and message, and the capture's whole projection must
 *       equal one recorded sibling projection deficit, with the raw streams
 *       untouched and the pinned exit code), and the evidence-bound
 *       interim behavior inventory (the cell and the capture's code and
 *       message must equal one recorded entry, with the raw streams
 *       untouched and the probe's failure status). Everything else — a
 *       failed drive, a missing or
 *       unloadable artifact, a probe defect, a {@code NON_DEAL} or
 *       normally-completing runtime-error drive, extra output on either
 *       stream, a wrong exit code, a changed message or projection on an
 *       inventory cell, a non-guard compile failure, a
 *       mixed-guard failure, a pinned-rejection mismatch, a rejection that
 *       staged output, and every unclassified behavioral failure — is
 *       fatal in both modes. A construct deficit in one cell therefore
 *       never disables enforcement of any other cell or of any other
 *       failure class. As soon as the compile criterion is met (zero
 *       {@code CONSTRUCT_UNLOWERED} legs), the audit engages its
 *       <em>strict acceptance mode</em>: every non-pin-exact capture leg,
 *       the projection and pinned-behavior deficits included, fails the
 *       audit by fixture and field — the combined-with-dependencies step.
 *       Nothing is skipped, excluded, or fabricated in either mode.</li>
 *   <li><b>The materialization fidelity.</b> The complete validated
 *       compilation set is materialized with the lanes' layout and bytes
 *       (declaration modules included), and the declaration wiring is
 *       load-bearing: a probe that omits the host/FFI declaration
 *       externals reproduces the frontend E2003 gap, while the audit's
 *       materialized drive compiles the same fixture and stages exactly
 *       the production entry artifact.</li>
 * </ol>
 *
 * <p>The drive is read-only over the corpus: every temp project is
 * deleted under the fail-closed cleanup (a surviving owned artifact is
 * reported by path and cell and fails the run), the corpus C library is
 * bootstrapped in an audit-owned temporary native directory (never in the
 * repository {@code build/} directory, whose contents the audit snapshots
 * and re-asserts), no fixture, sidecar, lane file, or repository artifact
 * is written, and the audit selects no invocation other than the
 * release-owned production one.</p>
 */
public class ProductionCorpusAudit {

    private static final Path CONFORMANCE = Path.of("test", "conformance");
    private static final Path HOST_FIXTURES = Path.of("test", "conformance",
        "host-fixtures");

    /** The three fail-closed producer guards the compile criterion forbids. */
    private static final List<String> GUARD_RULES = List.of(
        SemanticLowerer.CONSTRUCT_UNLOWERED,
        SemanticLowerer.RETAINED_ABI_DEFERRED,
        ProductionProjectEmission.SHARED_EMITTER_COVERAGE);

    /** The probe transport file name inside an execution workspace. */
    private static final String TRANSPORT_FILE = "__probe_transport.txt";

    /** The bounded per-subprocess deadline (the harness deadline convention). */
    private static final long SUBPROCESS_TIMEOUT_SECONDS = 120;

    /**
     * The bounded post-exit transcript-drain window: both readers must reach
     * EOF within it, or the capture is incomplete and the call fails closed.
     */
    private static final long TRANSCRIPT_DRAIN_TIMEOUT_SECONDS = 30;

    /** The bounded audit-owned GCC run of the corpus native library. */
    private static final long NATIVE_COMPILE_TIMEOUT_SECONDS = 120;

    /**
     * The retained-byte cap of the native compiler's merged output: the
     * stream is drained past the cap (a runaway tool never stalls the
     * child), but only this many bytes are kept for the diagnostic.
     */
    private static final int NATIVE_OUTPUT_LIMIT_BYTES = 64 * 1024;

    /**
     * The bounded post-kill drain grace of the owned-process teardown:
     * after the whole owned tree is terminated every inherited pipe writer
     * is gone, so both transcript drains reach EOF inside this window.
     */
    private static final long OWNED_DRAIN_GRACE_SECONDS = 5;

    /** The bounded reap window for the direct child after the tree kill. */
    private static final long OWNED_REAP_TIMEOUT_SECONDS = 5;

    /** The bounded wait for the containment helper of the tree kill. */
    private static final long CONTAINMENT_HELPER_TIMEOUT_SECONDS = 5;

    /**
     * The bounded wait for the audit's own worker pool to terminate after
     * its cancellation: the teardown cancels every pending task, interrupts
     * every active worker and waits this long for the pool to end, so a
     * cleanup of shared audit-owned artifacts never races a surviving
     * worker (or an owned subprocess it started).
     */
    private static final long POOL_TERMINATION_TIMEOUT_SECONDS = 120;

    private static final AtomicInteger passed = new AtomicInteger();
    private static final AtomicInteger fatalCount = new AtomicInteger();
    private static final AtomicInteger compileLegs = new AtomicInteger();
    private static final AtomicInteger rejectLegs = new AtomicInteger();
    private static final AtomicInteger captureLegs = new AtomicInteger();
    private static final List<String> fatalLines =
        Collections.synchronizedList(new ArrayList<>());
    private static final List<Leg> legs =
        Collections.synchronizedList(new ArrayList<>());

    /**
     * One measured cell's class. The class decides whether the cell's verdict
     * is fatal or reported:
     *
     * <ul>
     *   <li>{@link #INFRASTRUCTURE}, {@link #ARCHITECTURAL_GUARD} and
     *       {@link #DEFECT} are fatal in every mode: a broken tool
     *       invocation, a corrupted or unloadable artifact, a failed
     *       project load, a non-guard compile failure, a pinned-rejection
     *       mismatch, an artifact-staging rejection, and every unclassified
     *       behavioral failure are never excused by another cell's
     *       construct deficit;</li>
     *   <li>{@link #CONSTRUCT_DEFICIT} (the sibling-owned construct
     *       families), {@link #PROJECTION_DEFICIT} (the canonical
     *       failure-projection family) and {@link #BEHAVIOR_DEFICIT} (the
     *       pinned, evidence-bound interim behavior inventory below) are
     *       the permitted early-leaf deficits: reported per fixture and
     *       target while the compile criterion is unmet (HR4(a), A5, CD5),
     *       and fatal as soon as the construct criterion is met.</li>
     * </ul>
     */
    enum LegClass {
        /** The cell reproduced its pin (or its sanctioned compile-reject pin). */
        PASS,
        /** A checker-valid E6005 {@code CONSTRUCT_UNLOWERED} leg (permitted interim). */
        CONSTRUCT_DEFICIT,
        /** A recorded runtime-error projection difference (permitted interim). */
        PROJECTION_DEFICIT,
        /** A recorded interim behavior deficit (permitted interim). */
        BEHAVIOR_DEFICIT,
        /** A drive/load/toolchain failure: fatal in every mode. */
        INFRASTRUCTURE,
        /** A {@code RETAINED_ABI_DEFERRED}/{@code SHARED_EMITTER_COVERAGE} leg. */
        ARCHITECTURAL_GUARD,
        /** An unclassified failure, a rejection mismatch, or a staged rejection. */
        DEFECT
    }

    /** The measured surface of one leg: the compile cell or its capture. */
    enum LegKind {
        /** The per-fixture, per-target compile outcome. */
        COMPILE,
        /** The producible cell's driven capture outcome. */
        CAPTURE
    }

    /**
     * One measured cell: its fixture, its target, the measured surface, its
     * class, and its reported line.
     */
    record Leg(String corpusPath, Target target, LegKind kind, LegClass clazz,
               String line) {
    }

    /** True iff a leg's class is fatal under the given acceptance mode. */
    static boolean isFatal(LegClass clazz, boolean acceptanceMode) {
        return switch (clazz) {
            case PASS -> false;
            case INFRASTRUCTURE, ARCHITECTURAL_GUARD, DEFECT -> true;
            case CONSTRUCT_DEFICIT, PROJECTION_DEFICIT, BEHAVIOR_DEFICIT ->
                acceptanceMode;
        };
    }

    /**
     * One recorded interim behavior deficit (a not-yet-realized production
     * behavior family owned outside this audit). The evidence is bound: the
     * cell, the capture's code and message, the raw streams (both empty) and
     * the probe's failure status must all equal the recorded entry. A
     * different code, a changed message, extra output on either stream, or
     * another status is an unclassified failure, never this deficit, and is
     * fatal in every mode.
     */
    record BehaviorDeficit(String corpusPath, Target target,
                           String capturedCode, String capturedMessage,
                           int probeExitCode, String family) {
    }

    /**
     * The recorded interim behavior inventory (the not-yet-realized
     * production behavior families owned outside this audit). These legs
     * stay FAILS: they are reported per fixture and target and are counted
     * in every acceptance verdict; they are never skipped, excluded, or
     * converted into a pass. They become fatal as soon as the compile
     * criterion is met (the strict acceptance ratchet), and every runtime-ok
     * capture failure whose evidence does not equal one recorded entry is an
     * unclassified behavioral failure and fatal in every mode.
     */
    private static final List<BehaviorDeficit> BEHAVIOR_DEFICITS = List.of(
        new BehaviorDeficit(
            "backend-runtime/control-flow/for-of-string-unicode.deal",
            Target.LUAJIT, "TEST_FAIL", "expected 5 scalar values", 1,
            "the production LuaJIT FOR_EACH over a string iterates bytes, not "
                + "Unicode scalar values (the scalar-iteration realization)"),
        new BehaviorDeficit(
            "backend-runtime/control-flow/for-of-unicode-scalar.deal",
            Target.LUAJIT, "TEST_FAIL", "for-of unicode: scalar value mismatch",
            1,
            "the production LuaJIT FOR_EACH over a string iterates bytes, not "
                + "Unicode scalar values (the scalar-iteration realization)"),
        new BehaviorDeficit(
            "backend-runtime/defaults/plan-load-time-zero-invocations.deal",
            Target.JVM, "NON_DEAL",
            "java.lang.ClassCastException: class java.lang.Long cannot be "
                + "cast to class java.lang.Integer (java.lang.Long and "
                + "java.lang.Integer are in module java.base of loader "
                + "'bootstrap')",
            1,
            "the emitted JVM host int return projection admits a "
                + "java.lang.Long and then casts it to Integer (the host "
                + "boundary int projection realization)"),
        new BehaviorDeficit(
            "backend-runtime/defaults/plan-reexecuted-calls.deal",
            Target.JVM, "NON_DEAL",
            "java.lang.ClassCastException: class java.lang.Long cannot be "
                + "cast to class java.lang.Integer (java.lang.Long and "
                + "java.lang.Integer are in module java.base of loader "
                + "'bootstrap')",
            1,
            "the emitted JVM host int return projection admits a "
                + "java.lang.Long and then casts it to Integer (the host "
                + "boundary int projection realization)"),
        new BehaviorDeficit(
            "backend-runtime/defaults/plan-phase-order-provided-before-defaults.deal",
            Target.JVM, "NON_DEAL",
            "java.lang.ClassCastException: class java.lang.Long cannot be "
                + "cast to class java.lang.Integer (java.lang.Long and "
                + "java.lang.Integer are in module java.base of loader "
                + "'bootstrap')",
            1,
            "the emitted JVM host int return projection admits a "
                + "java.lang.Long and then casts it to Integer (the host "
                + "boundary int projection realization)"),
        new BehaviorDeficit(
            "backend-runtime/stdlib/table/keys-nonstring-exclusion.deal",
            Target.LUAJIT, "E8001", "expected table", 1,
            "the decoded-array (JSON-produced array) table-op admission is not "
                + "realized (the decoded-array surface, excluded by the bytes "
                + "realization)"),
        new BehaviorDeficit(
            "backend-runtime/stdlib/table/keys-nonstring-exclusion.deal",
            Target.JVM, "E8001", "expected table", 1,
            "the decoded-array (JSON-produced array) table-op admission is not "
                + "realized (the decoded-array surface, excluded by the bytes "
                + "realization)"));

    /** The recorded interim behavior deficit of one cell, or null. */
    static BehaviorDeficit behaviorDeficitOf(String corpusPath, Target target) {
        for (BehaviorDeficit deficit : BEHAVIOR_DEFICITS) {
            if (deficit.corpusPath().equals(corpusPath)
                    && deficit.target() == target) {
                return deficit;
            }
        }
        return null;
    }

    /**
     * The evidence match of one runtime-ok capture failure against the
     * recorded behavior inventory: the cell, the captured code and message,
     * both raw streams (empty) and the probe's failure status must all equal
     * the recorded entry.
     */
    static boolean matchesBehaviorDeficit(BehaviorDeficit deficit,
            Capture capture, Execution execution) {
        return deficit != null
            && capture != null && capture.error()
            && Objects.equals(deficit.capturedCode(), capture.code())
            && Objects.equals(deficit.capturedMessage(), capture.message())
            && execution.stdout() != null && execution.stdout().length == 0
            && execution.stderr() != null && execution.stderr().length == 0
            && execution.exitCode() == deficit.probeExitCode();
    }

    /**
     * One recorded interim projection deficit: a runtime-error leg whose
     * sidecar pins one canonical failure but whose production artifact
     * raises a different pinned arm, projects the pinned arm at another
     * position, or projects its value kind as another token (the sibling
     * canonical-failure-projection realization, owned outside this audit).
     * The evidence is bound to the whole observable projection: the entry
     * records the sidecar's pinned code and message, the exact normalized
     * capture, and the stream/status conditions. Any other mismatch is an
     * unclassified failure, never this deficit.
     */
    record ProjectionDeficit(String corpusPath, Target target,
                             String pinnedCode, String pinnedMessage,
                             String capturedCode, String capturedMessage,
                             String capturedFile, int capturedLine,
                             int capturedColumn, String capturedExpected,
                             String capturedActual, String family) {
    }

    /**
     * The recorded interim projection inventory. These legs stay FAILS: they
     * are reported per fixture and target, are counted in every acceptance
     * verdict, and become fatal as soon as the compile criterion is met.
     * Every captured value below is the evidence observed at this tree
     * state; a divergence from it (another arm, another message, another
     * position, another token, extra output or another status) is NOT this
     * deficit and is fatal in every mode.
     */
    private static final List<ProjectionDeficit> PROJECTION_DEFICITS = List.of(
        new ProjectionDeficit(
            "backend-runtime/arithmetic/int32-pow-infinity.deal",
            Target.JVM, "E8001", "expected int, got infinity",
            "E8004", "int out of safe range",
            "backend-runtime/arithmetic/int32-pow-infinity.deal", 7, 10,
            null, null,
            "the canonical failure arm: the artifact raises a different error than the " +
                "sidecar pins"),
        new ProjectionDeficit(
            "backend-runtime/arithmetic/int32-pow-infinity.deal",
            Target.LUAJIT, "E8001", "expected int, got infinity",
            "E8004", "int out of safe range",
            "backend-runtime/arithmetic/int32-pow-infinity.deal", 7, 10,
            null, null,
            "the canonical failure arm: the artifact raises a different error than the " +
                "sidecar pins"),
        new ProjectionDeficit(
            "backend-runtime/arrays/index-oob.deal",
            Target.JVM, "E8001", "expected int",
            "E8001", "expected int",
            "backend-runtime/arrays/index-oob.deal", 8, 3,
            "int", "nil",
            "the origin/span projection: the pinned arm's coordinates are projected at " +
                "another position"),
        new ProjectionDeficit(
            "backend-runtime/arrays/index-oob.deal",
            Target.LUAJIT, "E8001", "expected int",
            "E8001", "expected int",
            "backend-runtime/arrays/index-oob.deal", 8, 3,
            "int", "nil",
            "the origin/span projection: the pinned arm's coordinates are projected at " +
                "another position"),
        new ProjectionDeficit(
            "backend-runtime/bytes/bytes-dynamic-nested-first-element-e8003.deal",
            Target.JVM, "E8003", "array element 1 type mismatch",
            "E8003", "array element 1 type mismatch",
            "backend-runtime/bytes/bytes-dynamic-nested-first-element-e8003.deal", 10, 11,
            "[bytes]", "array",
            "the actual-kind token projection: the pinned arm's value kind is projected as " +
                "another token"),
        new ProjectionDeficit(
            "backend-runtime/bytes/bytes-dynamic-nested-first-element-e8003.deal",
            Target.LUAJIT, "E8003", "array element 1 type mismatch",
            "E8003", "array element 1 type mismatch",
            "backend-runtime/bytes/bytes-dynamic-nested-first-element-e8003.deal", 10, 11,
            "[bytes]", "array",
            "the actual-kind token projection: the pinned arm's value kind is projected as " +
                "another token"),
        new ProjectionDeficit(
            "backend-runtime/class-runtime-errors/dynamic-bad-class-array-element-e8001.deal",
            Target.JVM, "E8003", "array element 1 type mismatch",
            "E8001", "expected table",
            "backend-runtime/class-runtime-errors/dynamic-bad-class-array-element-e8001.deal",
            11, 32,
            "table", "array",
            "the canonical failure arm: the artifact raises a different error than the " +
                "sidecar pins"),
        new ProjectionDeficit(
            "backend-runtime/class-runtime-errors/dynamic-bad-class-array-element-e8001.deal",
            Target.LUAJIT, "E8003", "array element 1 type mismatch",
            "E8001", "expected table",
            "backend-runtime/class-runtime-errors/dynamic-bad-class-array-element-e8001.deal",
            11, 32,
            "table", "array",
            "the canonical failure arm: the artifact raises a different error than the " +
                "sidecar pins"),
        new ProjectionDeficit(
            "backend-runtime/class-runtime-errors/dynamic-bad-nullable-class-e8001.deal",
            Target.JVM, "E8001", "expected class instance",
            "E8001", "expected class instance",
            "backend-runtime/class-runtime-errors/dynamic-bad-nullable-class-e8001.deal", 12, 23,
            "class", "table",
            "the origin/span projection: the pinned arm's coordinates are projected at " +
                "another position"),
        new ProjectionDeficit(
            "backend-runtime/class-runtime-errors/dynamic-bad-nullable-class-e8001.deal",
            Target.LUAJIT, "E8001", "expected class instance",
            "E8001", "expected class instance",
            "backend-runtime/class-runtime-errors/dynamic-bad-nullable-class-e8001.deal", 12, 23,
            "class", "table",
            "the origin/span projection: the pinned arm's coordinates are projected at " +
                "another position"),
        new ProjectionDeficit(
            "backend-runtime/runtime-errors/chained-access-type-error-e8001.deal",
            Target.JVM, "E8001", "expected int",
            "E8001", "expected int",
            "backend-runtime/runtime-errors/chained-access-type-error-e8001.deal", 9, 21,
            "int", "string",
            "the origin/span projection: the pinned arm's coordinates are projected at " +
                "another position"),
        new ProjectionDeficit(
            "backend-runtime/runtime-errors/chained-access-type-error-e8001.deal",
            Target.LUAJIT, "E8001", "expected int",
            "E8001", "expected int",
            "backend-runtime/runtime-errors/chained-access-type-error-e8001.deal", 9, 21,
            "int", "string",
            "the origin/span projection: the pinned arm's coordinates are projected at " +
                "another position"),
        new ProjectionDeficit(
            "backend-runtime/runtime-errors/nested-array-oob-e8001.deal",
            Target.JVM, "E8001", "expected int",
            "E8001", "expected int",
            "backend-runtime/runtime-errors/nested-array-oob-e8001.deal", 8, 3,
            "int", "nil",
            "the origin/span projection: the pinned arm's coordinates are projected at " +
                "another position"),
        new ProjectionDeficit(
            "backend-runtime/runtime-errors/nested-array-oob-e8001.deal",
            Target.LUAJIT, "E8001", "expected int",
            "E8001", "expected int",
            "backend-runtime/runtime-errors/nested-array-oob-e8001.deal", 8, 3,
            "int", "nil",
            "the origin/span projection: the pinned arm's coordinates are projected at " +
                "another position"),
        new ProjectionDeficit(
            "backend-runtime/runtime-errors/rethrow-across-function-boundary.deal",
            Target.JVM, "CROSS_FUNCTION_RETHROW", "boom",
            "CROSS_FUNCTION_RETHROW", "boom",
            "backend-runtime/runtime-errors/rethrow-across-function-boundary.deal", 14, 5,
            null, null,
            "the origin/span projection: the pinned arm's coordinates are projected at " +
                "another position"),
        new ProjectionDeficit(
            "backend-runtime/runtime-errors/rethrow-across-function-boundary.deal",
            Target.LUAJIT, "CROSS_FUNCTION_RETHROW", "boom",
            "CROSS_FUNCTION_RETHROW", "boom",
            "backend-runtime/runtime-errors/rethrow-across-function-boundary.deal", 14, 5,
            null, null,
            "the origin/span projection: the pinned arm's coordinates are projected at " +
                "another position"),
        new ProjectionDeficit(
            "backend-runtime/runtime-errors/rethrow-preserves-code.deal",
            Target.JVM, "USER_RETHROW", "boom",
            "USER_RETHROW", "boom",
            "backend-runtime/runtime-errors/rethrow-preserves-code.deal", 10, 5,
            null, null,
            "the origin/span projection: the pinned arm's coordinates are projected at " +
                "another position"),
        new ProjectionDeficit(
            "backend-runtime/runtime-errors/rethrow-preserves-code.deal",
            Target.LUAJIT, "USER_RETHROW", "boom",
            "USER_RETHROW", "boom",
            "backend-runtime/runtime-errors/rethrow-preserves-code.deal", 10, 5,
            null, null,
            "the origin/span projection: the pinned arm's coordinates are projected at " +
                "another position"),
        new ProjectionDeficit(
            "backend-runtime/source-location-precision/stdlib-error-source.deal",
            Target.JVM, "E8001", "expected string",
            "E8001", "expected string",
            "backend-runtime/source-location-precision/stdlib-error-source.deal", 10, 21,
            "string", "int",
            "the origin/span projection: the pinned arm's coordinates are projected at " +
                "another position"),
        new ProjectionDeficit(
            "backend-runtime/source-location-precision/stdlib-error-source.deal",
            Target.LUAJIT, "E8001", "expected string",
            "E8001", "expected string",
            "backend-runtime/source-location-precision/stdlib-error-source.deal", 10, 21,
            "string", "int",
            "the origin/span projection: the pinned arm's coordinates are projected at " +
                "another position"),
        new ProjectionDeficit(
            "backend-runtime/stdlib-edge/console-log-dynamic-nonstring.deal",
            Target.JVM, "E8001", "expected string",
            "E8001", "expected string",
            "backend-runtime/stdlib-edge/console-log-dynamic-nonstring.deal", 10, 15,
            "string", "int",
            "the origin/span projection: the pinned arm's coordinates are projected at " +
                "another position"),
        new ProjectionDeficit(
            "backend-runtime/stdlib-edge/console-log-dynamic-nonstring.deal",
            Target.LUAJIT, "E8001", "expected string",
            "E8001", "expected string",
            "backend-runtime/stdlib-edge/console-log-dynamic-nonstring.deal", 10, 15,
            "string", "int",
            "the origin/span projection: the pinned arm's coordinates are projected at " +
                "another position"),
        new ProjectionDeficit(
            "backend-runtime/stdlib-edge/string-length-dynamic-nonstring.deal",
            Target.JVM, "E8001", "expected string",
            "E8001", "expected string",
            "backend-runtime/stdlib-edge/string-length-dynamic-nonstring.deal", 10, 21,
            "string", "int",
            "the origin/span projection: the pinned arm's coordinates are projected at " +
                "another position"),
        new ProjectionDeficit(
            "backend-runtime/stdlib-edge/string-length-dynamic-nonstring.deal",
            Target.LUAJIT, "E8001", "expected string",
            "E8001", "expected string",
            "backend-runtime/stdlib-edge/string-length-dynamic-nonstring.deal", 10, 21,
            "string", "int",
            "the origin/span projection: the pinned arm's coordinates are projected at " +
                "another position"),
        new ProjectionDeficit(
            "backend-runtime/stdlib-edge/table-keys-dynamic-nontable.deal",
            Target.JVM, "E8001", "expected table",
            "E8001", "expected table",
            "backend-runtime/stdlib-edge/table-keys-dynamic-nontable.deal", 10, 22,
            "table", "int",
            "the origin/span projection: the pinned arm's coordinates are projected at " +
                "another position"),
        new ProjectionDeficit(
            "backend-runtime/stdlib-edge/table-keys-dynamic-nontable.deal",
            Target.LUAJIT, "E8001", "expected table",
            "E8001", "expected table",
            "backend-runtime/stdlib-edge/table-keys-dynamic-nontable.deal", 10, 22,
            "table", "int",
            "the origin/span projection: the pinned arm's coordinates are projected at " +
                "another position"),
        new ProjectionDeficit(
            "backend-runtime/stdlib/table/keys-nontable-error.deal",
            Target.JVM, "E8001", "expected table",
            "E8001", "expected table",
            "backend-runtime/stdlib/table/keys-nontable-error.deal", 10, 22,
            "table", "string",
            "the origin/span projection: the pinned arm's coordinates are projected at " +
                "another position"),
        new ProjectionDeficit(
            "backend-runtime/stdlib/table/keys-nontable-error.deal",
            Target.LUAJIT, "E8001", "expected table",
            "E8001", "expected table",
            "backend-runtime/stdlib/table/keys-nontable-error.deal", 10, 22,
            "table", "string",
            "the origin/span projection: the pinned arm's coordinates are projected at " +
                "another position"),
        new ProjectionDeficit(
            "backend-runtime/tables/table-dynamic-read-runtime-error.deal",
            Target.JVM, "E8001", "expected int",
            "E8001", "expected int",
            "backend-runtime/tables/table-dynamic-read-runtime-error.deal", 8, 21,
            "int", "string",
            "the origin/span projection: the pinned arm's coordinates are projected at " +
                "another position"),
        new ProjectionDeficit(
            "backend-runtime/tables/table-dynamic-read-runtime-error.deal",
            Target.LUAJIT, "E8001", "expected int",
            "E8001", "expected int",
            "backend-runtime/tables/table-dynamic-read-runtime-error.deal", 8, 21,
            "int", "string",
            "the origin/span projection: the pinned arm's coordinates are projected at " +
                "another position"),
        new ProjectionDeficit(
            "backend-runtime/tables/table-missing-read-nonnullable-error.deal",
            Target.JVM, "E8001", "expected int",
            "E8001", "expected int",
            "backend-runtime/tables/table-missing-read-nonnullable-error.deal", 8, 20,
            "int", "nil",
            "the origin/span projection: the pinned arm's coordinates are projected at " +
                "another position"),
        new ProjectionDeficit(
            "backend-runtime/tables/table-missing-read-nonnullable-error.deal",
            Target.LUAJIT, "E8001", "expected int",
            "E8001", "expected int",
            "backend-runtime/tables/table-missing-read-nonnullable-error.deal", 8, 20,
            "int", "nil",
            "the origin/span projection: the pinned arm's coordinates are projected at " +
                "another position"),
        new ProjectionDeficit(
            "backend-runtime/type-system/dynamic-nonfunction-to-function-e8001.deal",
            Target.JVM, "E8001", "expected function",
            "E8001", "expected function",
            "backend-runtime/type-system/dynamic-nonfunction-to-function-e8001.deal", 8, 10,
            "function", "int",
            "the actual-kind token projection: the pinned arm's value kind is projected as " +
                "another token"),
        new ProjectionDeficit(
            "backend-runtime/type-system/dynamic-nonfunction-to-function-e8001.deal",
            Target.LUAJIT, "E8001", "expected function",
            "E8001", "expected function",
            "backend-runtime/type-system/dynamic-nonfunction-to-function-e8001.deal", 8, 10,
            "function", "int",
            "the actual-kind token projection: the pinned arm's value kind is projected as " +
                "another token"),
        new ProjectionDeficit(
            "backend-runtime/type-system/dynamic-wrong-to-nullable-e8001.deal",
            Target.JVM, "E8001", "expected int",
            "E8001", "expected int",
            "backend-runtime/type-system/dynamic-wrong-to-nullable-e8001.deal", 8, 23,
            "int", "string",
            "the origin/span projection: the pinned arm's coordinates are projected at " +
                "another position"),
        new ProjectionDeficit(
            "backend-runtime/type-system/dynamic-wrong-to-nullable-e8001.deal",
            Target.LUAJIT, "E8001", "expected int",
            "E8001", "expected int",
            "backend-runtime/type-system/dynamic-wrong-to-nullable-e8001.deal", 8, 23,
            "int", "string",
            "the origin/span projection: the pinned arm's coordinates are projected at " +
                "another position")
    );

    /** The recorded interim projection deficit of one cell, or null. */
    static ProjectionDeficit projectionDeficitOf(String corpusPath, Target target) {
        for (ProjectionDeficit deficit : PROJECTION_DEFICITS) {
            if (deficit.corpusPath().equals(corpusPath)
                    && deficit.target() == target) {
                return deficit;
            }
        }
        return null;
    }

    /**
     * The evidence match of one runtime-error leg against the recorded
     * projection inventory: the cell must carry a recorded deficit, the
     * sidecar's pinned code and message must equal the recorded pin, the
     * whole normalized capture must equal the recorded capture, and both raw
     * streams must be empty with the pinned exit code.
     */
    static boolean matchesProjectionDeficit(ProjectionDeficit deficit,
            Capture capture, Execution execution,
            SidecarExpectations.RuntimeExpectation.Executed executed) {
        return deficit != null
            && isDealErrorCapture(capture)
            && Objects.equals(deficit.pinnedCode(), executed.error().code())
            && Objects.equals(deficit.pinnedMessage(), executed.error().message())
            && capture != null
            && Objects.equals(deficit.capturedCode(), capture.code())
            && Objects.equals(deficit.capturedMessage(), capture.message())
            && Objects.equals(deficit.capturedFile(), capture.sourceFile())
            && Integer.valueOf(deficit.capturedLine()).equals(capture.line())
            && Integer.valueOf(deficit.capturedColumn()).equals(capture.column())
            && Objects.equals(deficit.capturedExpected(), capture.expected())
            && Objects.equals(deficit.capturedActual(), capture.actual())
            && execution.stdout() != null && execution.stdout().length == 0
            && execution.stderr() != null && execution.stderr().length == 0
            && execution.exitCode() == executed.exitCode();
    }

    private static void check(boolean condition, String message) {
        if (condition) {
            passed.incrementAndGet();
        } else {
            fatal(message);
        }
    }

    private static void fatal(String message) {
        fatalCount.incrementAndGet();
        fatalLines.add(message);
        System.err.println("FAIL: " + message);
    }

    /**
     * A corpus-input defect (an unresolvable declaration wiring, a missing
     * host implementation): an audit-input defect, never a construct gap, and
     * fatal in every mode (the audit contract's fail-closed rule).
     */
    static final class AuditInputException extends RuntimeException {
        AuditInputException(String message) {
            super(message);
        }
    }

    private static void checkEq(Object expected, Object actual, String message) {
        check(Objects.equals(expected, actual), message + " (expected "
            + expected + ", got " + actual + ")");
    }

    // =========================================================================
    // The targets and the materialized project
    // =========================================================================

    /** The two production targets of the audit. */
    enum Target {
        LUAJIT("luajit"), JVM("jvm");

        private final String laneName;

        Target(String laneName) {
            this.laneName = laneName;
        }

        String laneName() {
            return laneName;
        }
    }

    /** One materialized corpus module: its corpus path, source, header delta. */
    record Materialized(String corpusPath, String source, int headerLinesStripped) {
    }

    /** One lane-equivalent temp project. */
    record Project(Path root, Path srcRoot, Path entryFile, String modulePath,
                   Map<String, Materialized> modules,
                   Map<String, String> rawToDeclaration,
                   List<String> hostNames, List<String> ffiImports,
                   Path outputRoot,
                   Map<String, String> modulePathToCorpus,
                   Map<String, String> moduleDirToCorpus) {

        Materialized moduleOf(String capturedFile) {
            if (capturedFile == null) {
                return null;
            }
            Materialized direct = modules.get(capturedFile);
            if (direct != null) {
                return direct;
            }
            return modules.get(Path.of(capturedFile).toAbsolutePath()
                .normalize().toString());
        }
    }

    /** The release-owned production invocation. */
    static CompilerInvocation productionInvocation() {
        return CompilerProfileProvider.resolve(
            ReleaseConfiguration.CURRENT_RELEASE_STATE,
            ReleaseConfiguration.releaseCapabilityRegistry());
    }

    private static String moduleNameOf(String fileName) {
        if (fileName.endsWith(".d.deal")) {
            return fileName.substring(0, fileName.length() - ".d.deal".length());
        }
        if (fileName.endsWith(".deal")) {
            return fileName.substring(0, fileName.length() - ".deal".length());
        }
        return fileName;
    }

    /** The lane's entry-directory-relative module target. */
    private static Path moduleTarget(Path corpusFile, Path entryDir, Path srcRoot) {
        Path rel;
        try {
            rel = entryDir.relativize(corpusFile);
        } catch (IllegalArgumentException e) {
            return srcRoot.resolve(moduleNameOf(
                corpusFile.getFileName().toString()) + ".deal");
        }
        if (rel.startsWith("..") || rel.getNameCount() <= 1) {
            return srcRoot.resolve(moduleNameOf(
                corpusFile.getFileName().toString()) + ".deal");
        }
        return srcRoot.resolve(rel);
    }

    /** Resolve a relative import path to a .deal/.d.deal file on disk. */
    private static Path resolveCompanionPath(String importPath, Path baseDir) {
        if (!importPath.startsWith("./") && !importPath.startsWith("../")) {
            return null;
        }
        Path resolved = baseDir.resolve(importPath).normalize();
        if (Files.exists(resolved)) {
            return resolved;
        }
        Path withExt = baseDir.resolve(importPath + ".deal").normalize();
        if (Files.exists(withExt)) {
            return withExt;
        }
        Path withDeclExt = baseDir.resolve(importPath + ".d.deal").normalize();
        if (Files.exists(withDeclExt)) {
            return withDeclExt;
        }
        return null;
    }

    /** Bare {@code host/<name>} import names of one source, in order. */
    private static List<String> hostImports(String source) {
        List<String> hosts = new ArrayList<>();
        for (String path : CorpusDiscovery.ffiImportPaths(source)) {
            if (path.startsWith("host/")
                    && !CorpusFfi.isFfiImport(CONFORMANCE, path)) {
                hosts.add(path.substring("host/".length()));
            }
        }
        return hosts;
    }

    /**
     * Materializes one fixture exactly as the lanes do: the complete
     * validated compilation set (the fixture plus every transitively
     * imported corpus module, declaration modules included) sits at its
     * entry-directory-relative layout carrying the validated header-stripped
     * bytes (with the explicit-{@code .deal} alias copies), the host
     * declarations land under {@code bindings/}, the corpus FFI declarations
     * carry their corpus {@code nativeLibrary} wiring bound to the
     * audit-owned native directory, and one generated {@code deal.json}
     * wires the module root, the output, the target backend, and the
     * externals.
     *
     * @param label            the fixture label for fail-closed messages
     * @param compilationSet   the validated compilation set (the entry
     *                         module first, as {@code CorpusDiscovery}
     *                         produces it)
     * @param target           the emission target
     * @param wireDeclarations {@code false} omits the externals wiring (the
     *                         load-bearing fidelity probe)
     */
    private static Project materialize(String label,
            List<SidecarSchemaValidator.CompilationModule> compilationSet,
            Target target, boolean wireDeclarations) throws Exception {
        Path root = Files.createTempDirectory("production-corpus-audit-");
        try {
            return materializeInto(root, label, compilationSet, target,
                wireDeclarations);
        } catch (Exception | Error e) {
            // The post-state rule holds on the failure paths too: a
            // materialization that cannot resolve a declaration (the
            // fail-closed audit-input defect) removes its temp project.
            deleteRecursively(root, label);
            throw e;
        }
    }

    /** The materialization body; {@code root} is owned by the caller. */
    private static Project materializeInto(Path root, String label,
            List<SidecarSchemaValidator.CompilationModule> compilationSet,
            Target target, boolean wireDeclarations) throws Exception {
        Path srcRoot = root.resolve("src");
        Files.createDirectories(srcRoot);
        Path bindings = root.resolve("bindings");
        SidecarSchemaValidator.CompilationModule entryModule = compilationSet.get(0);
        Path entryCorpus = CONFORMANCE.resolve(entryModule.corpusPath())
            .toAbsolutePath().normalize();
        Path entryDir = entryCorpus.getParent();

        Map<String, Materialized> modules = new LinkedHashMap<>();
        Map<String, String> rawToDeclaration = new LinkedHashMap<>();
        List<String> hostNames = new ArrayList<>();
        List<String> ffiImports = new ArrayList<>();
        Map<String, String> modulePathToCorpus = new LinkedHashMap<>();
        Map<String, String> moduleDirToCorpus = new LinkedHashMap<>();

        // 1. The complete compilation set with the lane's entry-relative
        // layout: every module lands at the lane's moduleTarget path with
        // its validated header-stripped bytes, the declaration (.d.deal)
        // modules included — exactly the sources the sidecar validation
        // verified, never a private re-derivation.
        for (SidecarSchemaValidator.CompilationModule module : compilationSet) {
            Path corpusFile = CONFORMANCE.resolve(module.corpusPath())
                .toAbsolutePath().normalize();
            String raw = Files.readString(corpusFile, StandardCharsets.UTF_8);
            String stripped = ConformanceHarnessMetadata
                .stripClassificationHeaders(raw);
            if (!stripped.equals(module.source())) {
                throw new AuditInputException(label
                    + ": the corpus module " + module.corpusPath()
                    + " does not carry the validated compilation-set bytes");
            }
            int headers = raw.split("\n", -1).length
                - module.source().split("\n", -1).length;
            Path targetPath = moduleTarget(corpusFile, entryDir, srcRoot);
            Files.createDirectories(targetPath.getParent());
            Files.writeString(targetPath, module.source(), StandardCharsets.UTF_8);
            modules.put(targetPath.toAbsolutePath().normalize().toString(),
                new Materialized(module.corpusPath(), module.source(), headers));
            String modulePath = CorpusDiscovery.slash(srcRoot.relativize(targetPath));
            if (modulePath.endsWith(".deal")) {
                modulePath = modulePath.substring(0,
                    modulePath.length() - ".deal".length());
            }
            modulePathToCorpus.put(modulePath, module.corpusPath());
            modulePathToCorpus.put(modulePath.replace('/', '.'), module.corpusPath());
            int slash = modulePath.lastIndexOf('/');
            moduleDirToCorpus.putIfAbsent(slash < 0 ? ""
                : modulePath.substring(0, slash), module.corpusPath());
        }
        String entryStem = moduleNameOf(entryCorpus.getFileName().toString());

        // 1b. The explicit-.deal alias copies (the absorbed
        // copyCompanionAliasIfExplicit rule): an import spelling carrying an
        // explicit .deal extension resolves to its alias-named copy in the
        // flat project root.
        for (Materialized module : List.copyOf(modules.values())) {
            Path moduleCorpusDir = Path.of(module.corpusPath()).getParent();
            Path baseDir = moduleCorpusDir == null ? CONFORMANCE
                : CONFORMANCE.resolve(moduleCorpusDir);
            for (String importPath : CorpusDiscovery.relativeImportPaths(
                    module.source())) {
                Path companion = resolveCompanionPath(importPath, baseDir);
                if (companion == null || !importPath.endsWith(".deal")) {
                    continue;
                }
                String companionCorpus = CorpusDiscovery.slash(
                    CONFORMANCE.toAbsolutePath().normalize()
                        .relativize(companion.toAbsolutePath().normalize()));
                Materialized companionModule = null;
                for (Materialized candidate : modules.values()) {
                    if (candidate.corpusPath().equals(companionCorpus)) {
                        companionModule = candidate;
                        break;
                    }
                }
                if (companionModule == null) {
                    continue;
                }
                String aliasBase = importPath.startsWith("./")
                    ? importPath.substring(2)
                    : importPath.startsWith("../")
                        ? importPath.substring(3) : importPath;
                Path aliasTarget = srcRoot.resolve(aliasBase + ".deal");
                if (Files.exists(aliasTarget)) {
                    continue;
                }
                Files.createDirectories(aliasTarget.getParent());
                Files.writeString(aliasTarget, companionModule.source(),
                    StandardCharsets.UTF_8);
            }
        }

        // 2. The declaration wiring: host declarations under bindings/, the
        // corpus FFI declarations with their nativeLibrary.
        for (Materialized module : modules.values()) {
            for (String hostName : hostImports(module.source())) {
                if (!hostNames.contains(hostName)) {
                    hostNames.add(hostName);
                }
            }
            for (String raw : CorpusDiscovery.ffiImportPaths(module.source())) {
                if (CorpusFfi.isFfiImport(CONFORMANCE, raw)
                        && !ffiImports.contains(raw)) {
                    ffiImports.add(raw);
                }
            }
        }
        if (wireDeclarations) {
            Files.createDirectories(bindings);
            for (String hostName : hostNames) {
                Path declaration = HOST_FIXTURES.resolve(hostName + ".d.deal");
                if (!Files.isRegularFile(declaration)) {
                    throw new AuditInputException(label
                        + ": the host declaration " + declaration + " is missing");
                }
                Files.writeString(bindings.resolve(hostName + ".d.deal"),
                    ConformanceHarnessMetadata.stripClassificationHeaders(
                        Files.readString(declaration, StandardCharsets.UTF_8)),
                    StandardCharsets.UTF_8);
                rawToDeclaration.put("host/" + hostName,
                    "bindings/" + hostName + ".d.deal");
            }
            for (String raw : ffiImports) {
                CorpusFfi.Wiring wiring = CorpusFfi.wiringFor(CONFORMANCE, raw);
                if (wiring == null) {
                    throw new AuditInputException(label
                        + ": no corpus FFI wiring for " + raw);
                }
                Path declaration = CONFORMANCE.resolve(CorpusFfi.FFI_DIR)
                    .resolve(wiring.declarationCorpusPath());
                if (!Files.isRegularFile(declaration)) {
                    throw new AuditInputException(label
                        + ": the corpus FFI declaration " + declaration
                        + " is missing");
                }
                String fileName = "ffi_" + raw.replace('/', '_') + ".d.deal";
                Files.writeString(bindings.resolve(fileName),
                    ConformanceHarnessMetadata.stripClassificationHeaders(
                        Files.readString(declaration, StandardCharsets.UTF_8)),
                    StandardCharsets.UTF_8);
                rawToDeclaration.put(raw, "bindings/" + fileName);
            }
        }

        // 3. One generated deal.json (the lane's rule).
        StringBuilder dealJson = new StringBuilder();
        dealJson.append("{\n  \"languageVersion\": \"1.2\",\n");
        dealJson.append("  \"moduleRoots\": [\"src\"],\n");
        dealJson.append("  \"output\": \"out\",\n");
        dealJson.append("  \"backend\": \"")
            .append(target == Target.JVM ? "jvm" : "luajit").append("\"");
        if (!rawToDeclaration.isEmpty()) {
            dealJson.append(",\n  \"externals\": {\n");
            boolean first = true;
            for (Map.Entry<String, String> entry : rawToDeclaration.entrySet()) {
                if (!first) {
                    dealJson.append(",\n");
                }
                first = false;
                dealJson.append("    \"").append(entry.getKey())
                    .append("\": { \"declaration\": \"")
                    .append(entry.getValue()).append("\"");
                CorpusFfi.Wiring wiring = CorpusFfi.wiringFor(CONFORMANCE,
                    entry.getKey());
                if (wiring != null) {
                    dealJson.append(", \"nativeLibrary\": \"")
                        .append(auditNativeLoaderText(label, wiring))
                        .append("\"");
                }
                dealJson.append(" }");
            }
            dealJson.append("\n  }");
        }
        dealJson.append("\n}\n");
        Files.writeString(root.resolve("deal.json"), dealJson);

        return new Project(root, srcRoot, srcRoot.resolve(entryStem + ".deal"),
            entryStem, modules, rawToDeclaration, hostNames, ffiImports,
            root.resolve("out"), modulePathToCorpus, moduleDirToCorpus);
    }

    // =========================================================================
    // The audit-owned native-library bootstrap (the read-only post-state rule)
    // =========================================================================

    /**
     * The audit-owned native directory: the corpus C library is bootstrapped
     * here (once per run), never in the repository {@code build/} directory.
     * {@code CorpusFfi.nativeLibraryPath} would compile into
     * {@code <checkout>/build/libcandidate_native.so}; the audit instead
     * owns the library path it binds into its temp manifests, so the
     * repository's existing artifacts — an existing native library
     * included — stay untouched. The directory is created by the main run
     * and removed with the audit.
     */
    private static volatile Path auditNativeDirectory;

    /**
     * True while the shared audit-owned temporary artifacts (the native
     * directory above all) may be removed. Set before the dispatched-corpus
     * pool starts and re-set by the owned-pool teardown: it stays true only
     * when no audit worker is alive, so the cleanup can never race a worker
     * or an owned subprocess it started.
     */
    private static volatile boolean auditPoolStopped = true;

    /** The lazily bootstrapped audit-owned library path (once per run). */
    private static volatile String auditNativeLibraryPath;

    /**
     * The loader text the audit binds into one temp manifest: the authored
     * loader basename under the audit-owned native directory. The
     * bootstrapped corpus library is compiled there on first use; the
     * intentionally missing-library wiring binds its never-built sibling
     * path under the same directory, preserving the honest missing-library
     * behavior.
     */
    static String auditNativeLoaderText(String label, CorpusFfi.Wiring wiring) {
        Path directory = auditNativeDirectory;
        if (directory == null) {
            throw new IllegalStateException("the audit native directory is "
                + "not initialized");
        }
        String basename = Path.of(wiring.nativeLibraryLoaderText())
            .getFileName().toString();
        if (CorpusFfi.NATIVE_LIBRARY_NAME.equals(basename)) {
            return auditNativeLibrary(label);
        }
        return directory.resolve(basename).toAbsolutePath().normalize()
            .toString();
    }

    /**
     * The audit-owned compile of {@code support/native.c}: one GCC run per
     * audit, into the audit's native directory, fail closed on a missing
     * source, a missing GCC, a stalled tool, or a failed compile (an
     * audit-input defect, never a construct gap).
     */
    private static String auditNativeLibrary(String label) {
        String cached = auditNativeLibraryPath;
        if (cached != null) {
            return cached;
        }
        synchronized (ProductionCorpusAudit.class) {
            cached = auditNativeLibraryPath;
            if (cached != null) {
                return cached;
            }
            Path source = CONFORMANCE.resolve(CorpusFfi.SUPPORT_DIR)
                .resolve(CorpusFfi.NATIVE_SOURCE_NAME)
                .toAbsolutePath().normalize();
            if (!Files.isRegularFile(source)) {
                throw new AuditInputException(label
                    + ": the committed FFI C fixture is missing: " + source);
            }
            Path library = auditNativeDirectory
                .resolve(CorpusFfi.NATIVE_LIBRARY_NAME)
                .toAbsolutePath().normalize();
            try {
                bootstrapNativeLibrary(label, source, library,
                    List.of("gcc", "-shared", "-fPIC", "-O2", "-o",
                        library.toString(), source.toString()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AuditInputException(label
                    + ": the audit-owned FFI native-library bootstrap was "
                    + "interrupted: " + e);
            }
            cached = library.toString();
            auditNativeLibraryPath = cached;
            return cached;
        }
    }

    /**
     * One audit-owned native-library compile under the shared owned-process
     * discipline: the GCC run is deadline-bounded, its merged output is
     * bounded, its readers are drained and torn down on every post-start
     * exit, and a calling-worker interruption is preserved after the child
     * and its readers are closed. A deadline expiry, a drain failure, a
     * non-zero exit and a missing output library are audit-input defects,
     * never a construct gap.
     */
    static void bootstrapNativeLibrary(String label, Path source, Path library,
            List<String> gccCommand) throws InterruptedException {
        bootstrapNativeLibrary(label, source, library, gccCommand,
            NATIVE_COMPILE_TIMEOUT_SECONDS);
    }

    /**
     * The native-library compile with an explicit deadline: the timeout
     * regression drives this overload so a stalled tool is bounded without
     * waiting out the production deadline.
     */
    static void bootstrapNativeLibrary(String label, Path source, Path library,
            List<String> gccCommand, long timeoutSeconds)
            throws InterruptedException {
        ProcessOutcome compile;
        try {
            compile = runOwnedProcess(
                new ProcessBuilder(gccCommand).redirectErrorStream(true),
                timeoutSeconds, TRANSCRIPT_DRAIN_TIMEOUT_SECONDS,
                NATIVE_OUTPUT_LIMIT_BYTES);
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            throw new AuditInputException(label
                + ": the audit-owned FFI native-library bootstrap failed: "
                + e);
        }
        if (compile.exitCode() != 0 || !Files.isRegularFile(library)) {
            throw new AuditInputException(label
                + ": the audit-owned FFI native-library bootstrap failed "
                + "(gcc exit " + compile.exitCode() + "): "
                + new String(compile.stdout(), StandardCharsets.UTF_8).trim());
        }
    }

    /**
     * The repository build directory's content snapshot (relative path ->
     * size, modification time, and content digest). The audit asserts its
     * own read-only post-state against this snapshot, so a native bootstrap
     * or any other write into the shared repository build products is
     * detected even when {@code git status} cannot see it.
     */
    static Map<String, String> snapshotBuildDirectory() {
        Path build = Path.of("build").toAbsolutePath().normalize();
        Map<String, String> snapshot = new java.util.TreeMap<>();
        if (!Files.isDirectory(build)) {
            return snapshot;
        }
        try (Stream<Path> walk = Files.walk(build)) {
            for (Path path : walk.filter(Files::isRegularFile)
                    .sorted().toList()) {
                String rel = CorpusDiscovery.slash(build.relativize(path));
                String digest;
                try {
                    digest = sha256(Files.readAllBytes(path));
                } catch (Exception e) {
                    digest = "unreadable";
                }
                long modified;
                try {
                    modified = Files.getLastModifiedTime(path).toMillis();
                } catch (Exception e) {
                    modified = -1L;
                }
                snapshot.put(rel, Files.exists(path)
                    ? sizeOf(path) + ":" + modified + ":" + digest : "absent");
            }
        } catch (Exception ignored) {
            snapshot.put("<unreadable>", "<unreadable>");
        }
        return snapshot;
    }

    private static long sizeOf(Path path) {
        try {
            return Files.size(path);
        } catch (Exception e) {
            return -1L;
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        java.security.MessageDigest digest =
            java.security.MessageDigest.getInstance("SHA-256");
        byte[] hashed = digest.digest(bytes);
        StringBuilder hex = new StringBuilder(hashed.length * 2);
        for (byte b : hashed) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16))
                .append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }

    /**
     * The corpus conformance externals-identity convention (the lanes'
     * rule): a host module's class identities project through its dotted
     * typing name while import resolution keys on the raw specifier.
     */
    private static ProjectContext withDottedHostIdentities(ProjectContext context,
            Project project) {
        if (project.hostNames().isEmpty()) {
            return context;
        }
        Map<String, ExternalEntry> externals = new LinkedHashMap<>();
        for (String hostName : project.hostNames()) {
            String rawKey = "host/" + hostName;
            ExternalEntry rawEntry = context.externals().get(rawKey);
            if (rawEntry == null) {
                continue;
            }
            String dottedKey = "host." + hostName;
            externals.put(dottedKey, new ExternalEntry(dottedKey,
                rawEntry.declarationPath(), rawEntry.nativeLibrary(),
                rawEntry.sourceRange()));
            externals.put(rawKey, rawEntry);
        }
        for (Map.Entry<String, ExternalEntry> entry
                : context.externals().entrySet()) {
            externals.putIfAbsent(entry.getKey(), entry.getValue());
        }
        return new ProjectContext(context.manifestPath(), context.projectRoot(),
            context.manifestDirectory(), context.languageVersion(),
            context.configuredModuleRoots(), context.outputPath(),
            context.backend(), externals, context.stdlibVersion(),
            context.stdlibSurfacePath(), context.stdlibDeclarationFiles(),
            context.projectDeploymentIdentity());
    }

    // =========================================================================
    // Compile
    // =========================================================================

    /** One compile outcome of one fixture and target. */
    record CompileOutcome(boolean compiled, boolean located,
                          List<CompilerDiagnostic> errors,
                          List<CompilerDiagnostic> allDiagnostics,
                          String detail) {

        boolean guardFired() {
            return errors.stream().anyMatch(ProductionCorpusAudit::isGuardDiagnostic);
        }

        String firstErrorDetail() {
            if (!errors.isEmpty()) {
                CompilerDiagnostic first = errors.get(0);
                return first.code() + " at " + first.range().file() + ":"
                    + first.line() + ":" + first.column() + " "
                    + first.message();
            }
            return detail;
        }
    }

    /** True iff the diagnostic is one of the three fail-closed producer guards. */
    static boolean isGuardDiagnostic(CompilerDiagnostic diagnostic) {
        String rule = e6005ValidatorRule(diagnostic);
        return rule != null && GUARD_RULES.contains(rule);
    }

    /**
     * The exact E6005 {@code validatorRule} field of one diagnostic, or null
     * when the diagnostic is not an E6005 or does not carry the canonical
     * E6005 message shape. The message is single-sourced by
     * {@link FailureContractRegistry#instantiateMessage(LoweringFailureDetail)}
     * as {@code ... validatorRule <rule>, semanticProfile ...}, so the rule
     * is extracted between its exact delimiters. A module path, an origin
     * string, or another code's text that merely contains a guard token
     * never classifies as that guard: only a real E6005 whose own rule field
     * equals the token does.
     */
    static String e6005ValidatorRule(CompilerDiagnostic diagnostic) {
        if (!"E6005".equals(diagnostic.code())) {
            return null;
        }
        String message = diagnostic.message() == null ? ""
            : diagnostic.message();
        int start = message.indexOf("validatorRule ");
        if (start < 0) {
            return null;
        }
        start += "validatorRule ".length();
        int end = message.indexOf(", semanticProfile ", start);
        if (end < 0) {
            return null;
        }
        String rule = message.substring(start, end);
        return rule.isEmpty() ? null : rule;
    }

    private static CompileOutcome compile(Project project) throws Exception {
        ProjectLocator.LocateResult located = ProjectLocator.locate(
            project.entryFile().toString(), null);
        if (located.context() == null) {
            return new CompileOutcome(false, false, List.of(), List.of(),
                "the generated deal.json did not locate strictly: "
                    + located.e2010());
        }
        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            withDottedHostIdentities(located.context(), project),
            project.entryFile(), false, false, false, false, null,
            productionInvocation());
        boolean compiled = orchestrator.compile();
        List<CompilerDiagnostic> errors = new ArrayList<>();
        for (CompilerDiagnostic diagnostic : orchestrator.diagnostics()) {
            if ("error".equals(diagnostic.severity())) {
                errors.add(diagnostic);
            }
        }
        return new CompileOutcome(compiled, true, errors,
            orchestrator.diagnostics(), null);
    }

    /** The emitted artifact path of one compiled project and target. */
    static Path artifactOf(Project project, Target target) {
        String name = target == Target.JVM
            ? JvmBackend.classNameFor(project.modulePath()) + ".java"
            : project.modulePath().replace('.', '/') + ".lua";
        return project.outputRoot().resolve(name);
    }

    // =========================================================================
    // The ordered export surface
    // =========================================================================

    /** One ordered zero-arity export of the entry module. */
    record Export(String name, boolean async) {
    }

    /** The ordered remaining zero-arity exports (main excluded). */
    static List<Export> exportsOf(String source, String file) {
        LexResult lex = new Lexer(source, file).tokenize();
        if (lex.hasErrors()) {
            throw new IllegalStateException("entry lex errors: "
                + lex.diagnostics());
        }
        ParseResult parse = new Parser(lex.tokens(), file,
            SemanticProfile.DEAL_V1_2_INT32, lex.directiveEvents()).parse();
        if (parse.hasErrors()) {
            throw new IllegalStateException("entry parse errors: "
                + parse.diagnostics());
        }
        ProgramNode program = parse.program();
        List<Export> exports = new ArrayList<>();
        for (StatementNode statement : program.statements()) {
            if (statement instanceof ExportDeclaration exp
                    && exp.declaration() instanceof FunctionDeclaration fn
                    && fn.params().isEmpty() && !fn.name().contains("$")
                    && !fn.name().equals("main")) {
                exports.add(new Export(fn.name(), fn.isAsync()));
            }
        }
        return exports;
    }

    // =========================================================================
    // The deferred-entry drives
    // =========================================================================

    /** The projected capture of one artifact drive; {@code defect} is probe-owned. */
    record Capture(String code, String message, String sourceFile, Integer line,
                   Integer column, String expected, String actual,
                   String defect) {

        boolean error() {
            return code != null && !"PROBE_DEFECT".equals(code);
        }
    }

    /** One executed artifact's raw outcome. */
    record Execution(int exitCode, byte[] stdout, byte[] stderr, Capture capture) {
    }

    private static Execution execute(Project project, List<Export> exports,
            Target target) throws Exception {
        return target == Target.LUAJIT
            ? executeLua(project, exports)
            : executeJvm(project, exports);
    }

    private static Execution executeLua(Project project, List<Export> exports)
            throws Exception {
        Path out = project.outputRoot();
        Path artifact = artifactOf(project, Target.LUAJIT);
        if (!Files.isRegularFile(artifact)) {
            throw new IllegalStateException(project.modulePath()
                + " [luajit]: the staged production chunk " + artifact
                + " is missing");
        }
        for (String hostName : project.hostNames()) {
            Path hostLua = HOST_FIXTURES.resolve(hostName + ".lua");
            if (!Files.isRegularFile(hostLua)) {
                throw new AuditInputException(project.modulePath()
                    + " [luajit]: the host implementation " + hostLua
                    + " is missing");
            }
            Path targetFile = out.resolve("host").resolve(hostName + ".lua");
            Files.createDirectories(targetFile.getParent());
            Files.copy(hostLua, targetFile);
        }
        Path transport = out.resolve(TRANSPORT_FILE);
        Files.deleteIfExists(transport);
        Path probe = out.resolve("__audit_probe.lua");
        Files.writeString(probe, luaProbe(project, exports, artifact, transport),
            StandardCharsets.UTF_8);
        ProcessBuilder builder = new ProcessBuilder("luajit",
            probe.toAbsolutePath().toString());
        builder.directory(out.toFile());
        builder.environment().put("DEAL_DEFER_MAIN", "1");
        ProcessOutcome outcome = runProcess(builder);
        return new Execution(outcome.exitCode(), outcome.stdout(),
            outcome.stderr(), readTransport(transport));
    }

    /** The LuaJIT deferred-entry probe source. */
    private static String luaProbe(Project project, List<Export> exports,
            Path artifact, Path transport) {
        StringBuilder probe = new StringBuilder();
        probe.append("local function __escape(s)\n");
        probe.append("  s = tostring(s)\n");
        probe.append("  s = string.gsub(s, \"\\\\\", \"\\\\\\\\\")\n");
        probe.append("  s = string.gsub(s, \"\\n\", \"\\\\n\")\n");
        probe.append("  s = string.gsub(s, \"\\t\", \"\\\\t\")\n");
        probe.append("  return s\n");
        probe.append("end\n");
        probe.append("local function __transport(fields)\n");
        probe.append("  local f = io.open(\"").append(transport)
            .append("\", \"w\")\n");
        probe.append("  if not f then os.exit(3) end\n");
        probe.append("  for _, kv in ipairs(fields) do\n");
        probe.append("    f:write(kv[1], \"\\t\", __escape(kv[2] or \"\"), \"\\n\")\n");
        probe.append("  end\n");
        probe.append("  f:close()\n");
        probe.append("end\n");
        probe.append("local function __fields(err)\n");
        probe.append("  if type(err) ~= \"table\" then\n");
        probe.append("    return { {\"code\", \"NON_DEAL\"},"
            + " {\"message\", tostring(err)} }\n");
        probe.append("  end\n");
        probe.append("  local file, line, column = err.file, err.line, err.column\n");
        probe.append("  if err.o ~= nil then\n");
        probe.append("    local f, l, c = tostring(err.o)"
            + ":match(\"^(.*):(%d+):(%d+)$\")\n");
        probe.append("    file, line, column = f, l, c\n");
        probe.append("  end\n");
        probe.append("  return { {\"code\", err.code},"
            + " {\"message\", err.message or err.m},\n");
        probe.append("    {\"file\", file}, {\"line\", line}, {\"column\", column},\n");
        probe.append("    {\"expected\", err.e}, {\"actual\", err.a} }\n");
        probe.append("end\n");
        probe.append("local function __fail(err)\n");
        probe.append("  __transport(__fields(err))\n");
        probe.append("  os.exit(1)\n");
        probe.append("end\n");
        probe.append("local function __probeDefect(detail)\n");
        probe.append("  __transport({ {\"code\", \"PROBE_DEFECT\"},"
            + " {\"message\", detail} })\n");
        probe.append("  os.exit(1)\n");
        probe.append("end\n");
        probe.append("dofile(\"").append(artifact.toAbsolutePath().normalize())
            .append("\")\n");
        probe.append("local __ok, __err = __dealMain()\n");
        probe.append("if not __ok then __fail(__err) end\n");
        int index = 0;
        for (Export export : exports) {
            index++;
            if (export.async()) {
                probe.append("local __okA").append(index)
                    .append(", __errA").append(index)
                    .append(" = pcall(__asyncEntries[\"")
                    .append(project.modulePath()).append("#")
                    .append(export.name()).append("\"], \"-\", true)\n");
                probe.append("if not __okA").append(index)
                    .append(" then __fail(__errA").append(index)
                    .append(") end\n");
            } else {
                probe.append("local __v").append(index)
                    .append(" = __exportSurfaces[\"")
                    .append(project.modulePath()).append("\"][\"")
                    .append(export.name()).append("\"]\n");
                probe.append("if type(__v").append(index)
                    .append(") ~= \"table\" or type(__v")
                    .append(index)
                    .append(".f) ~= \"function\" then __probeDefect(\"the entry"
                        + " surface publishes no function for ")
                    .append(export.name()).append("\") end\n");
                probe.append("local __okS").append(index)
                    .append(", __errS").append(index)
                    .append(" = pcall(__v").append(index)
                    .append(".f)\n");
                probe.append("if not __okS").append(index)
                    .append(" then __fail(__errS").append(index)
                    .append(") end\n");
            }
        }
        probe.append("os.exit(0)\n");
        return probe.toString();
    }

    private static Execution executeJvm(Project project, List<Export> exports)
            throws Exception {
        Path out = project.outputRoot();
        Path artifact = artifactOf(project, Target.JVM);
        if (!Files.isRegularFile(artifact)) {
            throw new IllegalStateException(project.modulePath()
                + " [jvm]: the staged production class " + artifact
                + " is missing");
        }
        Path transport = out.resolve(TRANSPORT_FILE);
        Files.deleteIfExists(transport);
        String className = JvmBackend.classNameFor(project.modulePath());
        Path classes = out.resolve("classes");
        Files.createDirectories(classes);
        String classpath = absoluteClasspath();
        List<String> sources = new ArrayList<>();
        sources.add(artifact.toString());
        for (String hostName : project.hostNames()) {
            Path hostFile = HOST_FIXTURES.resolve(hostName + ".java");
            if (!Files.isRegularFile(hostFile)) {
                throw new AuditInputException(project.modulePath()
                    + " [jvm]: the host implementation " + hostFile
                    + " is missing");
            }
            String hostClass = JvmBackend.classNameFor("host/" + hostName);
            Path targetFile = out.resolve(hostClass + ".java");
            Files.copy(hostFile, targetFile);
            sources.add(targetFile.toString());
        }
        Files.writeString(out.resolve("AuditProbe.java"),
            jvmProbe(project, exports, transport), StandardCharsets.UTF_8);
        sources.add(out.resolve("AuditProbe.java").toString());
        ProcessOutcome compile = compileStaged(sources, classpath, classes);
        if (compile.exitCode() != 0) {
            throw new IllegalStateException(project.modulePath()
                + " [jvm]: the emitted production artifact does not compile"
                + " with javac --release 25 -proc:none: "
                + new String(compile.stdout(), StandardCharsets.UTF_8));
        }
        ProcessBuilder run = new ProcessBuilder("java", "-cp",
            classpath + File.pathSeparator + classes, "AuditProbe");
        run.directory(out.toFile());
        ProcessOutcome outcome = runProcess(run);
        return new Execution(outcome.exitCode(), outcome.stdout(),
            outcome.stderr(), readTransport(transport));
    }

    // =========================================================================
    // The in-process compilation of a staged JVM artifact
    // =========================================================================

    /**
     * One audit worker's javac instance and file manager. The compiler API of
     * a single instance is not safe for concurrent use, so each worker owns
     * one pair and reuses it across its cells; the compiler and the class
     * path are set up once per worker instead of once per cell. The
     * compilation options are the ones the JVM lane's javac invocation
     * carries: {@code --release 25 -proc:none}, the compiler's runtime
     * classes on the class path, and the cell's classes directory as the
     * output. The compilation happens in-process, so the drive does not pay
     * one toolchain JVM start per cell.
     */
    static final class JavacWorker {
        final javax.tools.JavaCompiler compiler;
        final javax.tools.StandardJavaFileManager fileManager;

        JavacWorker() {
            this.compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
            if (this.compiler == null) {
                throw new IllegalStateException(
                    "no system Java compiler is available on this toolchain");
            }
            this.fileManager = compiler.getStandardFileManager(null, null,
                StandardCharsets.UTF_8);
        }
    }

    private static final ThreadLocal<JavacWorker> JAVAC_WORKER =
        ThreadLocal.withInitial(JavacWorker::new);

    /** The class path entries of one javac argument, resolved to files. */
    private static List<File> classpathEntries(String classpath) {
        List<File> entries = new ArrayList<>();
        for (String entry : classpath.split(File.pathSeparator)) {
            if (!entry.isEmpty()) {
                entries.add(Path.of(entry).toAbsolutePath().normalize().toFile());
            }
        }
        return entries;
    }

    /**
     * Compiles one staged artifact set with the toolchain's own compiler and
     * the lane's options. The outcome carries the compiler's diagnostics so a
     * failure reports exactly what the {@code javac} invocation reported.
     */
    private static ProcessOutcome compileStaged(List<String> sources,
            String classpath, Path classes) throws Exception {
        JavacWorker worker = JAVAC_WORKER.get();
        javax.tools.DiagnosticCollector<javax.tools.JavaFileObject> diagnostics =
            new javax.tools.DiagnosticCollector<>();
        worker.fileManager.setLocation(javax.tools.StandardLocation.CLASS_OUTPUT,
            List.of(classes.toFile()));
        worker.fileManager.setLocation(javax.tools.StandardLocation.CLASS_PATH,
            classpathEntries(classpath));
        worker.fileManager.setLocation(javax.tools.StandardLocation.SOURCE_PATH,
            List.of());
        Iterable<? extends javax.tools.JavaFileObject> units =
            worker.fileManager.getJavaFileObjectsFromStrings(sources);
        List<String> options = List.of("-proc:none", "--release", "25");
        Boolean ok = worker.compiler.getTask(null, worker.fileManager,
            diagnostics, options, null, units).call();
        StringBuilder text = new StringBuilder();
        for (javax.tools.Diagnostic<? extends javax.tools.JavaFileObject> d
                : diagnostics.getDiagnostics()) {
            text.append(d.getKind()).append(": line ").append(d.getLineNumber())
                .append(": ").append(d.getMessage(null)).append('\n');
        }
        boolean compiled = Boolean.TRUE.equals(ok);
        return new ProcessOutcome(compiled ? 0 : 1,
            text.toString().getBytes(StandardCharsets.UTF_8), new byte[0]);
    }

    /** The JVM deferred-entry probe source. */
    private static String jvmProbe(Project project, List<Export> exports,
            Path transport) {
        String className = JvmBackend.classNameFor(project.modulePath());
        StringBuilder probe = new StringBuilder();
        probe.append("public final class AuditProbe {\n");
        probe.append("  static final String TRANSPORT = \"")
            .append(transport.toString().replace("\\", "\\\\")).append("\";\n");
        probe.append("  public static void main(String[] args) {\n");
        probe.append("    try {\n");
        probe.append("      ").append(className).append(".dealMain();\n");
        int index = 0;
        for (Export export : exports) {
            index++;
            probe.append("      {\n");
            probe.append("        Object v").append(index)
                .append(" = deal.codegen.jvm.JvmRuntime.exportSurface(\"")
                .append(project.modulePath()).append("\").read(\"")
                .append(export.name()).append("\");\n");
            probe.append("        if (!(v").append(index)
                .append(" instanceof deal.codegen.jvm.JvmRuntime.FunctionValue)) {\n");
            probe.append("          transport(new IllegalStateException("
                + "\"PROBE_DEFECT: the entry surface publishes no function "
                + "for ").append(export.name()).append("\"));\n");
            probe.append("          return;\n");
            probe.append("        }\n");
            probe.append("        ((deal.codegen.jvm.JvmRuntime.FunctionValue) v")
                .append(index).append(").fn.invoke(new Object[]{});\n");
            probe.append("      }\n");
        }
        probe.append("      System.exit(0);\n");
        probe.append("    } catch (Throwable error) {\n");
        probe.append("      transport(error);\n");
        probe.append("    }\n");
        probe.append("  }\n");
        probe.append("  static void transport(Throwable error) {\n");
        probe.append("    Throwable e = error;\n");
        probe.append("    while (e != null && !(e instanceof deal.codegen.jvm")
            .append(".JvmRuntime.DealError) && e.getCause() != null) {\n");
        probe.append("      e = e.getCause();\n");
        probe.append("    }\n");
        probe.append("    StringBuilder out = new StringBuilder();\n");
        probe.append("    if (e instanceof deal.codegen.jvm.JvmRuntime.DealError d) {\n");
        probe.append("      append(out, \"code\", d.code);\n");
        probe.append("      append(out, \"message\", d.msg);\n");
        probe.append("      append(out, \"origin\", d.origin);\n");
        probe.append("      append(out, \"expected\", d.expected);\n");
        probe.append("      append(out, \"actual\", d.actual);\n");
        probe.append("    } else {\n");
        probe.append("      append(out, \"code\", \"NON_DEAL\");\n");
        probe.append("      append(out, \"message\", String.valueOf(error));\n");
        probe.append("    }\n");
        probe.append("    try {\n");
        probe.append("      java.nio.file.Files.writeString("
            + "java.nio.file.Path.of(TRANSPORT), out.toString());\n");
        probe.append("    } catch (java.io.IOException ignored) {\n");
        probe.append("      // best effort; the drive reports the missing capture\n");
        probe.append("    }\n");
        probe.append("    System.exit(1);\n");
        probe.append("  }\n");
        probe.append("  static void append(StringBuilder out, String key,"
            + " String value) {\n");
        probe.append("    if (value == null) { return; }\n");
        probe.append("    String escaped = value.replace(\"\\\\\", \"\\\\\\\\\")"
            + ".replace(\"\\n\", \"\\\\n\").replace(\"\\t\", \"\\\\t\");\n");
        probe.append("    out.append(key).append('\\t').append(escaped)"
            + ".append('\\n');\n");
        probe.append("  }\n");
        probe.append("}\n");
        return probe.toString();
    }

    /** Reads one transport file into the projected capture. */
    private static Capture readTransport(Path transport) throws Exception {
        if (!Files.isRegularFile(transport)) {
            return null;
        }
        Map<String, String> fields = new LinkedHashMap<>();
        for (String line : Files.readString(transport, StandardCharsets.UTF_8)
                .split("\n", -1)) {
            if (line.isEmpty()) {
                continue;
            }
            int tab = line.indexOf('\t');
            if (tab < 0) {
                continue;
            }
            fields.put(line.substring(0, tab), unescape(line.substring(tab + 1)));
        }
        if (fields.isEmpty()) {
            return null;
        }
        String origin = fields.get("origin");
        String file = fields.get("file");
        Integer line = null;
        Integer column = null;
        if (origin != null) {
            int lastColon = origin.lastIndexOf(':');
            int prevColon = origin.lastIndexOf(':', lastColon - 1);
            if (prevColon > 0 && lastColon > prevColon) {
                file = origin.substring(0, prevColon);
                line = Integer.valueOf(origin.substring(prevColon + 1, lastColon));
                column = Integer.valueOf(origin.substring(lastColon + 1));
            }
        } else {
            String lineText = fields.get("line");
            String columnText = fields.get("column");
            line = lineText == null || lineText.isEmpty() ? null
                : Integer.valueOf(lineText);
            column = columnText == null || columnText.isEmpty() ? null
                : Integer.valueOf(columnText);
        }
        String code = fields.get("code");
        return new Capture(code, fields.get("message"), file, line, column,
            emptyToNull(fields.get("expected")), emptyToNull(fields.get("actual")),
            "PROBE_DEFECT".equals(code) ? fields.get("message") : null);
    }

    private static String emptyToNull(String text) {
        return text == null || text.isEmpty() ? null : text;
    }

    private static String unescape(String text) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < text.length()) {
                char next = text.charAt(++i);
                out.append(switch (next) {
                    case 'n' -> '\n';
                    case 't' -> '\t';
                    case '\\' -> '\\';
                    default -> next;
                });
            } else {
                out.append(c);
            }
        }
        return out.toString();
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

    // =========================================================================
    // Bounded subprocesses
    // =========================================================================

    record ProcessOutcome(int exitCode, byte[] stdout, byte[] stderr) {
    }

    /**
     * The per-cell verdict for a drive that failed before a comparable
     * capture existed. A broken tool invocation, a corrupted or unloadable
     * artifact, an unreadable or incomplete transcript and a deadline
     * expiry are infrastructure, never a construct or capture deficit:
     * fatal in every mode. The corpus driver and the focused subprocess
     * controls share this one verdict path.
     */
    static CellVerdict driveFailureVerdict(String label, Target target,
            Exception failure) {
        return new CellVerdict(false, LegClass.INFRASTRUCTURE, label + " ["
            + target.laneName() + "]: the drive failed: " + failure);
    }

    /**
     * The process-group tools of the owned-process core. Every audit child
     * starts through {@code setsid}, so the direct child leads its own
     * process group and the teardown can kill the whole owned tree —
     * including a descendant that outlives the direct child and is
     * reparented, which {@link Process#descendants()} can no longer see.
     * Both tools are resolved once, by absolute path, so a child
     * environment's {@code PATH} cannot redirect them; when {@code setsid}
     * is unavailable the core starts children directly and the teardown
     * degrades to descendant-then-child termination.
     */
    private static final String SETSID_BINARY =
        firstExecutable("/usr/bin/setsid", "/bin/setsid");
    private static final String KILL_BINARY =
        firstExecutable("/bin/kill", "/usr/bin/kill");

    private static String firstExecutable(String... candidates) {
        for (String candidate : candidates) {
            if (Files.isExecutable(Path.of(candidate))) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * The owned process builder: the {@code setsid} leader wrapper is
     * prepended to the caller's command (when the tool is available) and
     * every builder property is preserved, so the direct child becomes the
     * leader of its own process group without changing any other observable
     * property of the invocation.
     */
    private static ProcessBuilder ownedProcessBuilder(ProcessBuilder source) {
        List<String> argv = new ArrayList<>();
        argv.add(SETSID_BINARY);
        argv.addAll(source.command());
        ProcessBuilder owned = new ProcessBuilder(argv);
        if (source.directory() != null) {
            owned.directory(source.directory());
        }
        owned.environment().clear();
        owned.environment().putAll(source.environment());
        owned.redirectInput(source.redirectInput());
        owned.redirectOutput(source.redirectOutput());
        owned.redirectError(source.redirectError());
        owned.redirectErrorStream(source.redirectErrorStream());
        return owned;
    }

    /**
     * Runs one owned subprocess under the bounded deadline and returns its
     * outcome only when both transcript drains completed successfully. A
     * reader failure, a drain that has not completed when its bounded
     * window expires, a calling-worker interruption and the process deadline
     * each leave through the owned-process teardown; none manufactures
     * empty output or a fabricated outcome.
     */
    private static ProcessOutcome runProcess(ProcessBuilder builder)
            throws Exception {
        return runOwnedProcess(builder, SUBPROCESS_TIMEOUT_SECONDS,
            TRANSCRIPT_DRAIN_TIMEOUT_SECONDS, 0);
    }

    /**
     * The owned-subprocess core: the caller supplies the process deadline,
     * the post-exit drain deadline and the retained transcript cap ({@code 0}
     * keeps the whole transcript). The corpus drives keep the complete
     * transcript; the native bootstrap caps the compiler output while still
     * draining the stream. Every child starts as the leader of its own
     * process group (through {@code setsid} when available), and every
     * post-start exit path contains and terminates the whole owned tree,
     * ends both readers and reaps the direct child before control leaves the
     * call; a transcript that is unreadable or still open at its deadline is
     * an infrastructure failure, never substituted empty output.
     */
    private static ProcessOutcome runOwnedProcess(ProcessBuilder builder,
            long timeoutSeconds, long drainSeconds, int outputLimitBytes)
            throws Exception {
        boolean ownGroup = SETSID_BINARY != null;
        Process process = ownGroup ? ownedProcessBuilder(builder).start()
            : builder.start();
        byte[][] stdout = new byte[1][];
        byte[][] stderr = new byte[1][];
        Throwable[] stdoutFailure = new Throwable[1];
        Throwable[] stderrFailure = new Throwable[1];
        Thread outThread = new Thread(() -> stdout[0] = readAll(
            process.getInputStream(), outputLimitBytes, stdoutFailure),
            "audit-stdout");
        Thread errThread = new Thread(() -> stderr[0] = readAll(
            process.getErrorStream(), outputLimitBytes, stderrFailure),
            "audit-stderr");
        outThread.setDaemon(true);
        errThread.setDaemon(true);
        try {
            outThread.start();
            errThread.start();
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                throw new IllegalStateException("the subprocess exceeded the "
                    + timeoutSeconds + "s bounded deadline");
            }
            awaitDrain(outThread, drainSeconds, "stdout");
            awaitDrain(errThread, drainSeconds, "stderr");
            Throwable stdoutFail = stdoutFailure[0];
            Throwable stderrFail = stderrFailure[0];
            if (stdoutFail != null || stderrFail != null) {
                Throwable failure = stdoutFail != null ? stdoutFail
                    : stderrFail;
                throw new IllegalStateException("the subprocess transcript "
                    + "drain failed: " + failure);
            }
            return new ProcessOutcome(process.exitValue(),
                stdout[0] == null ? new byte[0] : stdout[0],
                stderr[0] == null ? new byte[0] : stderr[0]);
        } finally {
            // Every post-start exit path — the bounded deadline, an
            // interruption of the calling worker, a reader failure, a drain
            // deadline expiry, and the normal return — contains and
            // terminates the whole owned process tree (so no inherited pipe
            // writer survives), ends both readers and reaps the direct child
            // before control leaves the call. A cancelled audit therefore
            // leaves no live child, descendant or reader behind, and the
            // scratch/native cleanup that follows never races an ongoing
            // execution.
            closeOwnedProcess(process, ownGroup, outThread, errThread);
        }
    }

    /**
     * Waits for one transcript drain within the bounded window. A drain that
     * is still running at the deadline makes the capture incomplete and the
     * call fails closed; an interrupted caller leaves through the
     * owned-process teardown with the interruption preserved.
     */
    private static void awaitDrain(Thread reader, long drainSeconds,
            String streamName) throws InterruptedException {
        reader.join(TimeUnit.SECONDS.toMillis(drainSeconds));
        if (reader.isAlive()) {
            throw new IllegalStateException("the subprocess exited but its "
                + streamName + " drain did not complete within "
                + drainSeconds + "s; the transcript is incomplete");
        }
    }

    /**
     * The bounded teardown of one owned process tree and its readers. The
     * whole owned tree is contained: the current descendants, the child's
     * process group (the direct child leads it when {@code setsid} is
     * available, so a descendant that outlived the direct child and was
     * reparented is reached too) and the direct child itself are
     * force-killed, and the direct child is reaped. Both transcript drains
     * then reach EOF inside the bounded post-kill grace, so neither an
     * owned process nor an owned reader outlives this call and the
     * scratch/native cleanup that follows never races an ongoing execution.
     * The caller's interruption status is preserved for the propagation
     * path.
     */
    private static void closeOwnedProcess(Process process, boolean ownGroup,
            Thread... readers) {
        boolean[] interrupted = {Thread.interrupted()};
        try {
            // The tree kill and the drain join form one sweep: the group
            // kill ends every inherited pipe writer, so the drains reach
            // EOF. A second sweep covers a process created while the first
            // teardown ran, and gives the drains their remaining window.
            for (int sweep = 0; sweep < 2; sweep++) {
                terminateOwnedTree(process, ownGroup, interrupted);
                if (awaitOwnedDrains(readers, interrupted)) {
                    break;
                }
            }
            reapOwnedProcess(process, interrupted);
        } finally {
            if (interrupted[0]) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Terminates the owned tree in the canonical order — descendants first,
     * then the child's process group (when the core created one), then the
     * direct child — so nothing outlives the call.
     */
    private static void terminateOwnedTree(Process process, boolean ownGroup,
            boolean[] interrupted) {
        try {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
        } catch (RuntimeException ignored) {
            // A vanished subtree is already gone; the group kill and the
            // direct child remain the backstops.
        }
        if (ownGroup) {
            killOwnedProcessGroup(process.pid(), interrupted);
        }
        process.destroyForcibly();
    }

    /**
     * Kills every remaining member of the child's process group from outside
     * the group: the child leads its own group (it was started through
     * {@code setsid}), so its pid is the group id, and a descendant that
     * outlived the direct child — reparented, therefore invisible to
     * {@link Process#descendants()} — dies here. An already empty group
     * yields the helper's not-found status and nothing else. The helper is a
     * containment utility, not an owned toolchain child: its streams are
     * discarded and its own wait is bounded.
     */
    private static void killOwnedProcessGroup(long groupId,
            boolean[] interrupted) {
        List<String> argv = KILL_BINARY != null
            ? List.of(KILL_BINARY, "-KILL", "--", "-" + groupId)
            : List.of("bash", "-c", "kill -KILL -- -\"$1\"",
                "audit-kill-group", Long.toString(groupId));
        try {
            ProcessBuilder killerBuilder = new ProcessBuilder(argv);
            killerBuilder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            killerBuilder.redirectError(ProcessBuilder.Redirect.DISCARD);
            Process killer = killerBuilder.start();
            boolean killed = false;
            try {
                killed = killer.waitFor(CONTAINMENT_HELPER_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                interrupted[0] = true;
            }
            if (!killed) {
                killer.destroyForcibly();
            }
        } catch (Exception ignored) {
            // The direct child's destroyForcibly remains the backstop.
        }
    }

    /**
     * Joins both owned readers inside the bounded post-kill grace: once
     * every process holding the inherited pipes is gone, the drains reach
     * EOF. An interruption is recorded and the join retried, so the
     * ownership cleanup completes before an interruption propagates.
     * {@code true} means both drains ended inside the window.
     */
    private static boolean awaitOwnedDrains(Thread[] readers,
            boolean[] interrupted) {
        long deadline = System.nanoTime()
            + TimeUnit.SECONDS.toNanos(OWNED_DRAIN_GRACE_SECONDS);
        boolean complete = true;
        for (Thread reader : readers) {
            if (reader == null) {
                continue;
            }
            while (reader.isAlive()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    complete = false;
                    break;
                }
                try {
                    reader.join(Math.max(1L,
                        TimeUnit.NANOSECONDS.toMillis(remaining)));
                } catch (InterruptedException e) {
                    interrupted[0] = true;
                }
            }
        }
        return complete;
    }

    /**
     * Reaps the direct child inside the bounded window even when the waiting
     * thread is interrupted; a child that cannot be reaped inside the window
     * is SIGKILLed once more and left to the OS reaper.
     */
    private static void reapOwnedProcess(Process process,
            boolean[] interrupted) {
        long deadline = System.nanoTime()
            + TimeUnit.SECONDS.toNanos(OWNED_REAP_TIMEOUT_SECONDS);
        while (process.isAlive()) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                process.destroyForcibly();
                return;
            }
            try {
                if (process.waitFor(Math.max(1L,
                        TimeUnit.NANOSECONDS.toMillis(remaining)),
                        TimeUnit.MILLISECONDS)) {
                    return;
                }
            } catch (InterruptedException e) {
                interrupted[0] = true;
            }
        }
    }

    // =========================================================================
    // The audit's bounded worker pool
    // =========================================================================

    /**
     * Drives one batch of audit cells on the audit's own bounded pool and
     * owns the pool's whole lifetime. Every exit path — the normal
     * completion, a worker's unexpected failure, and a cancellation of the
     * calling thread — runs the owned-pool teardown: every unfinished
     * future is cancelled (a pending task never starts), every active
     * worker is interrupted, the queue is drained and the pool's termination
     * is awaited inside the bounded window. The call returns only when no
     * audit worker is alive, or fails closed when the window expires
     * (leaving the shared audit-owned artifacts in place for the surviving
     * workers); a calling-thread interruption is rethrown after the
     * teardown, so a cancelled run can never delete shared audit-owned files
     * while an owned worker still runs. The worker tasks record their own
     * cell failures; an exception that escapes one is an unexpected harness
     * defect and fails the run closed.
     */
    static void runOwnedPool(int jobs, List<? extends Runnable> tasks)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(jobs);
        List<Future<?>> futures = new ArrayList<>();
        Exception pending = null;
        try {
            for (Runnable task : tasks) {
                futures.add(pool.submit(task));
            }
            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (ExecutionException e) {
                    fatal("an audit worker failed unexpectedly: "
                        + e.getCause());
                }
            }
        } catch (Exception e) {
            pending = e;
        }
        boolean stopped = closeOwnedPool(pool, futures);
        auditPoolStopped = stopped;
        if (!stopped) {
            throw new IllegalStateException("the audit's owned worker pool "
                + "did not terminate inside the bounded window ("
                + POOL_TERMINATION_TIMEOUT_SECONDS + "s); the shared "
                + "audit-owned artifacts are retained for the surviving "
                + "workers");
        }
        if (pending != null) {
            throw pending;
        }
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException(
                "the audit pool drive was cancelled");
        }
    }

    /**
     * The owned-pool teardown: cancels every unfinished future, interrupts
     * every active worker, drains the queue and awaits the pool's
     * termination inside the bounded window. An interruption of the
     * tearing-down thread is recorded and the wait retried, so the teardown
     * completes before the interruption propagates; the interruption status
     * is restored before returning.
     *
     * @return true iff every worker terminated inside the window
     */
    static boolean closeOwnedPool(ExecutorService pool,
            List<Future<?>> futures) {
        boolean[] interrupted = {Thread.interrupted()};
        try {
            for (Future<?> future : futures) {
                future.cancel(true);
            }
            pool.shutdownNow();
            long deadline = System.nanoTime()
                + TimeUnit.SECONDS.toNanos(POOL_TERMINATION_TIMEOUT_SECONDS);
            while (!pool.isTerminated()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    break;
                }
                try {
                    pool.awaitTermination(Math.max(1L,
                        TimeUnit.NANOSECONDS.toMillis(remaining)),
                        TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    interrupted[0] = true;
                }
            }
            return pool.isTerminated();
        } finally {
            if (interrupted[0]) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * The bounded transcript read: up to {@code outputLimitBytes} bytes are
     * retained ({@code 0} keeps everything) while the rest of the stream is
     * still drained, so a runaway tool cannot stall its child; any read
     * failure is recorded in {@code failure} instead of being swallowed
     * into empty output.
     */
    static byte[] readAll(java.io.InputStream stream, int outputLimitBytes,
            Throwable[] failure) {
        try {
            java.io.ByteArrayOutputStream kept =
                new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            long total = 0;
            int read;
            while ((read = stream.read(buffer)) != -1) {
                int keep = outputLimitBytes <= 0 ? read
                    : (int) Math.max(0,
                        Math.min(read, outputLimitBytes - total));
                if (keep > 0) {
                    kept.write(buffer, 0, keep);
                    total += keep;
                }
            }
            return kept.toByteArray();
        } catch (Exception e) {
            failure[0] = e;
            return new byte[0];
        }
    }

    // =========================================================================
    // The sidecar-authoritative comparison
    // =========================================================================

    /** The normalized projection of one captured drive. */
    static Capture normalize(Project project, Capture capture) {
        if (capture == null) {
            return null;
        }
        Materialized module = project.moduleOf(capture.sourceFile());
        String sourceFile = module != null ? module.corpusPath()
            : capture.sourceFile();
        Integer line = capture.line();
        if (line != null && module != null) {
            line = line + module.headerLinesStripped();
        }
        return new Capture(capture.code(),
            canonicalIdentities(project, capture.message(), sourceFile),
            sourceFile, line, capture.column(),
            canonicalIdentities(project, capture.expected(), sourceFile),
            canonicalIdentities(project, capture.actual(), sourceFile),
            canonicalIdentities(project, capture.defect(), sourceFile));
    }

    /**
     * Rewrites the temp project's module-qualified class-descriptor texts
     * to the canonical corpus vocabulary (the lanes' rule): an
     * {@code @src/<module path>/<Class>} local identity becomes
     * {@code @conformance/<corpus path>/<Class>}; an unmappable token is
     * emitted verbatim so the comparison surfaces the defect.
     */
    static String canonicalIdentities(Project project, String text,
            String sourceFile) {
        if (text == null || text.indexOf('@') < 0) {
            return text;
        }
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c != '@') {
                out.append(c);
                i++;
                continue;
            }
            int end = i + 1;
            while (end < text.length()
                    && (Character.isLetterOrDigit(text.charAt(end))
                        || text.charAt(end) == '/' || text.charAt(end) == '_'
                        || text.charAt(end) == '$' || text.charAt(end) == '-'
                        || text.charAt(end) == '.')) {
                end++;
            }
            out.append(canonicalIdentity(project, text.substring(i, end),
                sourceFile));
            i = end;
        }
        return out.toString();
    }

    private static String canonicalIdentity(Project project, String token,
            String sourceFile) {
        String prefix = "@src/";
        if (!token.startsWith(prefix)) {
            return token;
        }
        String rest = token.substring(prefix.length());
        int slash = rest.lastIndexOf('/');
        String corpus;
        String className;
        if (slash < 0) {
            corpus = sourceFile;
            className = rest;
        } else {
            String modulePath = rest.substring(0, slash);
            corpus = project.modulePathToCorpus().get(modulePath);
            if (corpus == null) {
                corpus = project.moduleDirToCorpus().get(modulePath);
            }
            className = rest.substring(slash + 1);
        }
        if (corpus == null || className.isEmpty()) {
            return token;
        }
        return "@conformance/" + corpus + "/" + className;
    }

    /**
     * The canonical error framing of one capture under the pin's suppression
     * rule (the bytes the landed comparator consumes).
     */
    private static byte[] framedSnapshot(Capture capture,
            SidecarExpectations.ErrorExpectation pinned) {
        SidecarExpectations.ErrorExpectation snapshot =
            new SidecarExpectations.ErrorExpectation(capture.code(),
                capture.message(),
                pinned.pinsSpan() ? capture.sourceFile() : null,
                pinned.pinsSpan() ? capture.line() : null,
                pinned.pinsSpan() ? capture.column() : null,
                pinned.expected().isPresent()
                    ? Optional.ofNullable(capture.expected()) : Optional.empty(),
                pinned.actual().isPresent()
                    ? Optional.ofNullable(capture.actual()) : Optional.empty(),
                Optional.empty(), Optional.empty());
        String framing = ErrorSnapshot.CODE_LINE_PREFIX + capture.code() + "\n"
            + ErrorSnapshot.SNAPSHOT_LINE_PREFIX
            + ErrorSnapshot.canonicalJson(snapshot) + "\n";
        return framing.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * The runtime-error drive's framed stdout (the landed lane rule): the
     * canonical capture framing is appended to the executed stdout, while
     * the actual stderr and the exact exit code are preserved. Nothing is
     * fabricated and nothing observable is discarded. The comparison then
     * detects extra output on either stream and every process result other
     * than the pinned one.
     */
    static byte[] framedRuntimeErrorStdout(Execution execution, Capture capture,
            SidecarExpectations.ErrorExpectation pinned) {
        byte[] framing = framedSnapshot(capture, pinned);
        byte[] stdout = execution.stdout() == null
            ? new byte[0] : execution.stdout();
        byte[] joined = new byte[stdout.length + framing.length];
        System.arraycopy(stdout, 0, joined, 0, stdout.length);
        System.arraycopy(framing, 0, joined, stdout.length, framing.length);
        return joined;
    }

    /** The sidecar's typed expectation of one fixture and target. */
    static SidecarExpectations.RuntimeExpectation expectationOf(
            CorpusDiscovery.Fixture fixture, Target target) throws Exception {
        SidecarExpectations.StructuredExpectationSidecar sidecar =
            SidecarExpectations.StructuredExpectationSidecar.parse(
                Files.readString(fixture.sidecarPath(CONFORMANCE),
                    StandardCharsets.UTF_8));
        return sidecar.expectationFor(target.laneName());
    }

    /**
     * Compares one driven cell with its sidecar through the landed comparator
     * and returns the failure line, or empty on an exact pass.
     */
    static Optional<String> compareCell(String fixtureLabel, Target target,
            Capture capture, Execution execution,
            SidecarExpectations.RuntimeExpectation expectation) {
        if (capture != null && capture.defect() != null) {
            return Optional.of(fixtureLabel + " [" + target.laneName()
                + "]: the deferred-entry probe reported a defect: "
                + capture.defect());
        }
        LaneExecution laneExecution;
        if (expectation instanceof SidecarExpectations.RuntimeExpectation.Rejected
                rejected) {
            // The sanctioned extern-C rejection leg: the compile reject is
            // compared separately; a capture here is a defect.
            return Optional.of(fixtureLabel + " [" + target.laneName()
                + "]: the sidecar pins compile rejection ("
                + rejected.code() + ") but the reachable arm executed");
        }
        SidecarExpectations.RuntimeExpectation.Executed executed =
            (SidecarExpectations.RuntimeExpectation.Executed) expectation;
        if (executed.isRuntimeError()) {
            if (capture == null || !capture.error()) {
                return Optional.of(fixtureLabel + " [" + target.laneName()
                    + "]: the sidecar pins runtime-error " + executed.error().code()
                    + " but the drive completed normally"
                    + (capture == null ? " (no capture)" : ""));
            }
            laneExecution = new LaneExecution.Executed(
                framedRuntimeErrorStdout(execution, capture, executed.error()),
                execution.stderr(), execution.exitCode());
        } else {
            if (capture != null && capture.error()) {
                return Optional.of(fixtureLabel + " [" + target.laneName()
                    + "]: the sidecar pins runtime-ok but the drive raised "
                    + capture.code() + ": " + capture.message());
            }
            laneExecution = new LaneExecution.Executed(execution.stdout(),
                execution.stderr(), execution.exitCode());
        }
        Optional<GateMismatch> mismatch = StructuredExpectationComparator.compare(
            target.laneName(), expectation, laneExecution);
        return mismatch.map(m -> fixtureLabel + " [" + target.laneName() + "] "
            + m.clazz() + ": " + m.detail());
    }

    /** The field-level detail of one capture against its pin (reporting aid). */
    static String captureDetail(Capture capture,
            SidecarExpectations.ErrorExpectation pinned) {
        if (capture == null) {
            return "captured=null pinned=(" + pinned.code() + ", "
                + pinned.message() + ")";
        }
        return "captured=(" + capture.code() + ", " + capture.message() + ", "
            + capture.sourceFile() + ":" + capture.line() + ":" + capture.column()
            + ", " + capture.expected() + ", " + capture.actual()
            + ") pinned=(" + pinned.code() + ", " + pinned.message() + ", "
            + pinned.sourceFile() + ":" + pinned.line() + ":" + pinned.column()
            + ", " + pinned.expected().orElse(null) + ", "
            + pinned.actual().orElse(null) + ")";
    }

    // =========================================================================
    // The per-cell verdicts
    // =========================================================================

    /** One fixture/target cell's verdict, its class, and its reported line. */
    record CellVerdict(boolean pass, LegClass clazz, String line) {
    }

    /** True iff the outcome carries a RETAINED_ABI_DEFERRED/SHARED_EMITTER_COVERAGE error. */
    static boolean architecturalGuardFired(CompileOutcome outcome) {
        return outcome.errors().stream().anyMatch(diagnostic -> {
            String rule = e6005ValidatorRule(diagnostic);
            return SemanticLowerer.RETAINED_ABI_DEFERRED.equals(rule)
                || ProductionProjectEmission.SHARED_EMITTER_COVERAGE
                    .equals(rule);
        });
    }

    /** True iff the diagnostic carries the exact CONSTRUCT_UNLOWERED guard rule. */
    static boolean isConstructGuard(CompilerDiagnostic diagnostic) {
        return SemanticLowerer.CONSTRUCT_UNLOWERED
            .equals(e6005ValidatorRule(diagnostic));
    }

    /** True iff the capture is a genuine DEAL error (never a probe or non-DEAL outcome). */
    static boolean isDealErrorCapture(Capture capture) {
        return capture != null && capture.error() && capture.code() != null
            && capture.message() != null
            && !"NON_DEAL".equals(capture.code())
            && !"PROBE_DEFECT".equals(capture.code());
    }

    /**
     * The compile criterion of one cell: a compile with zero guard rules; on
     * the JVM the extern-C group's pinned rejection is the sanctioned
     * outcome. The declaration-import group is measured by this same
     * materializing drive. The sanctioned rejection contract is validated
     * before the interim construct classification: a rejection cell whose
     * compile carries the CONSTRUCT_UNLOWERED guard instead of the pinned
     * rejection (or any other code set) is a rejection mismatch and fatal in
     * both modes. A failed compile that published output is likewise fatal
     * before any interim classification — the fail-closed contract stages
     * nothing, in every mode. After that a guard leg is classed as the
     * sibling-owned construct deficit only when every error it carries is
     * the CONSTRUCT_UNLOWERED guard — a failure that mixes the guard with
     * any other diagnostic is unclassified and fatal. The two architectural
     * guards, a non-guard compile failure, and a pinned-rejection mismatch
     * are always fatal.
     */
    static CellVerdict compileVerdict(String fixtureLabel, Target target,
            CompileOutcome outcome,
            SidecarExpectations.RuntimeExpectation expectation,
            List<String> stagedArtifacts) {
        if (architecturalGuardFired(outcome)) {
            return new CellVerdict(false, LegClass.ARCHITECTURAL_GUARD,
                fixtureLabel + " [" + target.laneName()
                    + "] compile E6005 architectural guard: "
                    + outcome.firstErrorDetail());
        }
        // The sanctioned rejection pin is a pin, never an interim deficit:
        // a construct-guard (or any other) diagnostic where the rejection
        // code is required is a fatal rejection mismatch.
        if (expectation instanceof SidecarExpectations.RuntimeExpectation.Rejected
                rejected) {
            return rejectionVerdict(fixtureLabel, target, outcome.compiled(),
                outcome.errors(), rejected, stagedArtifacts);
        }
        // A failed compile must stage nothing at all, whatever its interim
        // classification: published output from a fail-closed failure is a
        // defect, fatal in every mode.
        if (!outcome.compiled() && !stagedArtifacts.isEmpty()) {
            return new CellVerdict(false, LegClass.DEFECT, fixtureLabel + " ["
                + target.laneName() + "] the failed compile published output: "
                + stagedArtifacts + " (a failed compile must stage nothing)");
        }
        if (outcome.errors().stream()
                .anyMatch(ProductionCorpusAudit::isConstructGuard)) {
            boolean constructOnly = outcome.errors().stream()
                .allMatch(ProductionCorpusAudit::isConstructGuard);
            if (!constructOnly) {
                return new CellVerdict(false, LegClass.DEFECT, fixtureLabel
                    + " [" + target.laneName() + "] the compile failure mixes "
                    + "the CONSTRUCT_UNLOWERED guard with other diagnostics ("
                    + outcome.errors().stream().map(CompilerDiagnostic::code)
                        .toList() + "): " + outcome.firstErrorDetail());
            }
            return new CellVerdict(false, LegClass.CONSTRUCT_DEFICIT,
                fixtureLabel + " [" + target.laneName()
                    + "] compile E6005 guard: " + outcome.firstErrorDetail());
        }
        if (!outcome.compiled()) {
            String rule = outcome.errors().stream().map(CompilerDiagnostic::code)
                .filter(code -> "E6005".equals(code)).findFirst().orElse(null);
            return new CellVerdict(false, LegClass.DEFECT, fixtureLabel + " ["
                + target.laneName() + "] compile FAILED"
                + (rule == null ? "" : " E6005")
                + ": " + outcome.firstErrorDetail());
        }
        return new CellVerdict(true, LegClass.PASS, fixtureLabel + " ["
            + target.laneName() + "] PASS compile");
    }

    /**
     * The sanctioned compile-reject verdict (the extern-C divergent form,
     * corpus C6). The complete diagnostic-code set must equal the pinned
     * code — an additional declaration error (E2003) or any other extra code
     * is a defect regardless of diagnostic ordering — and the failed compile
     * must have staged nothing at all. Only then is the pinned diagnostic
     * object reduced through the landed comparator.
     */
    static CellVerdict rejectionVerdict(String fixtureLabel, Target target,
            boolean compiled, List<CompilerDiagnostic> errors,
            SidecarExpectations.RuntimeExpectation.Rejected rejected,
            List<String> stagedArtifacts) {
        String label = fixtureLabel + " [" + target.laneName() + "]";
        if (compiled) {
            return new CellVerdict(false, LegClass.DEFECT, label
                + " the sidecar pins compile rejection " + rejected.code()
                + " but the production compile emitted an artifact");
        }
        List<String> codes = errors.stream().map(CompilerDiagnostic::code)
            .toList();
        if (codes.isEmpty()) {
            return new CellVerdict(false, LegClass.DEFECT, label
                + " the sidecar pins compile rejection " + rejected.code()
                + " but the compile carries no diagnostic");
        }
        Set<String> distinct = new LinkedHashSet<>(codes);
        if (!distinct.equals(Set.of(rejected.code()))) {
            return new CellVerdict(false, LegClass.DEFECT, label
                + " the sanctioned rejection pin is [" + rejected.code()
                + "] but the compile carries " + codes
                + " (the full code set is validated, never the first "
                + "diagnostic only)");
        }
        if (!stagedArtifacts.isEmpty()) {
            return new CellVerdict(false, LegClass.DEFECT, label
                + " the rejected compile staged output: " + stagedArtifacts
                + " (the rejection contract is reject-before-any-artifact)");
        }
        CompilerDiagnostic first = errors.get(0);
        Optional<GateMismatch> mismatch = StructuredExpectationComparator
            .compare(target.laneName(), rejected,
                new LaneExecution.Rejected(first.code(),
                    rejected.line().isPresent()
                        ? OptionalInt.of(first.line()) : OptionalInt.empty(),
                    rejected.column().isPresent()
                        ? OptionalInt.of(first.column()) : OptionalInt.empty()));
        if (mismatch.isPresent()) {
            return new CellVerdict(false, LegClass.DEFECT, label + " "
                + mismatch.get().clazz() + ": " + mismatch.get().detail());
        }
        return new CellVerdict(true, LegClass.PASS, label
            + " PASS compile-reject " + rejected.code());
    }

    /**
     * The capture verdict of one driven cell. A permitted interim projection
     * deficit is bound to the recorded projection inventory: the cell, the
     * sidecar's pinned code and message, the whole normalized capture, and
     * the stream/status conditions (both raw streams empty, the pinned exit
     * code) must all equal one recorded entry. A probe defect, a NON_DEAL
     * outcome, a normal completion, an unrelated code, a changed message, a
     * changed projection, extra output on either stream, or any other exit
     * code is an unclassified failure and fatal in every mode. A runtime-ok
     * failure is permitted only when the cell and the captured code and
     * message equal one recorded behavior-inventory entry with both streams
     * empty and the probe's failure status.
     */
    static CellVerdict captureVerdict(String corpusPath, Target target,
            Capture capture, Execution execution,
            SidecarExpectations.RuntimeExpectation expectation,
            Optional<String> mismatch) {
        String label = corpusPath + " [" + target.laneName() + "]";
        if (mismatch.isEmpty()) {
            return new CellVerdict(true, LegClass.PASS, label + " PASS capture");
        }
        String detail = mismatch.get();
        if (capture != null && capture.defect() != null) {
            return new CellVerdict(false, LegClass.INFRASTRUCTURE, label
                + " the deferred-entry probe reported a defect: "
                + capture.defect());
        }
        if (expectation instanceof SidecarExpectations.RuntimeExpectation.Rejected
                rejected) {
            return new CellVerdict(false, LegClass.DEFECT, label
                + " the sidecar pins compile rejection (" + rejected.code()
                + ") but the reachable arm executed");
        }
        SidecarExpectations.RuntimeExpectation.Executed executed =
            (SidecarExpectations.RuntimeExpectation.Executed) expectation;
        if (executed.isRuntimeError()) {
            ProjectionDeficit deficit = projectionDeficitOf(corpusPath, target);
            if (matchesProjectionDeficit(deficit, capture, execution, executed)) {
                return new CellVerdict(false, LegClass.PROJECTION_DEFICIT,
                    detail + " [recorded projection deficit: "
                        + deficit.family() + "] - "
                        + captureDetail(capture, executed.error()));
            }
            return new CellVerdict(false, LegClass.DEFECT, detail
                + " (the drive did not present the pinned runtime-error "
                + "outcome as a recorded projection deficit: the capture is "
                + (capture == null ? "absent" : capture.code() + ": "
                    + capture.message() + " at " + capture.sourceFile()
                    + ":" + capture.line() + ":" + capture.column())
                + ", stdout bytes=" + (execution.stdout() == null ? 0
                    : execution.stdout().length)
                + ", stderr bytes=" + (execution.stderr() == null ? 0
                    : execution.stderr().length)
                + ", exitCode=" + execution.exitCode()
                + "; pinned exitCode=" + executed.exitCode()
                + ", recorded projection deficit="
                + (deficit == null ? "none" : deficit.family()) + ")");
        }
        BehaviorDeficit deficit = behaviorDeficitOf(corpusPath, target);
        if (matchesBehaviorDeficit(deficit, capture, execution)) {
            return new CellVerdict(false, LegClass.BEHAVIOR_DEFICIT, detail
                + " [recorded interim behavior deficit: " + deficit.family()
                + "]");
        }
        return new CellVerdict(false, LegClass.DEFECT, detail
            + (deficit == null
                ? " (unclassified behavioral failure: the cell carries no "
                    + "recorded interim behavior deficit)"
                : " (the recorded interim behavior deficit expects "
                    + deficit.capturedCode() + ": "
                    + deficit.capturedMessage() + " with empty streams and "
                    + "status " + deficit.probeExitCode() + ", got "
                    + (capture == null ? "no capture"
                        : capture.code() + ": " + capture.message())
                    + " with stdout bytes=" + (execution.stdout() == null ? 0
                        : execution.stdout().length)
                    + ", stderr bytes=" + (execution.stderr() == null ? 0
                        : execution.stderr().length)
                    + ", exitCode=" + execution.exitCode() + ")"));
    }

    // =========================================================================
    // 1. The corpus invariants
    // =========================================================================

    private static void testCorpusInvariants(CorpusDiscovery.DiscoveryResult discovery)
            throws Exception {
        System.out.println("-- the corpus invariants: the current validated "
            + "discovery's dispatched set, the sidecar schema, and the "
            + "three-backend dispatch --");
        checkEq(0, discovery.failures().size(),
            "every corpus fixture classifies (no classification failure)");
        Map<String, CorpusDiscovery.Fixture> byPath = discovery.corpusByPath();
        List<CorpusDiscovery.Fixture> dispatched = dispatchedOf(discovery);
        check(!dispatched.isEmpty(),
            "the current validated discovery dispatches runtime fixtures "
                + "(the accounting is derived from the discovery itself)");
        Set<String> corpusIndex = discovery.corpusModuleIndex();
        int validated = 0;
        for (CorpusDiscovery.Fixture fixture : dispatched) {
            Path sidecarPath = fixture.sidecarPath(CONFORMANCE);
            check(Files.isRegularFile(sidecarPath),
                fixture.corpusPath() + " carries its expectation sidecar");
            List<SidecarSchemaValidator.CompilationModule> compilationSet =
                CorpusDiscovery.compilationSet(fixture, byPath, CONFORMANCE);
            Optional<SidecarSchemaValidator.ClassificationFailure> failure =
                SidecarSchemaValidator.validate(
                    new SidecarSchemaValidator.ValidationContext(
                        fixture.corpusPath(), fixture.expected(), compilationSet,
                        corpusIndex),
                    Files.readString(sidecarPath, StandardCharsets.UTF_8));
            if (failure.isEmpty()) {
                validated++;
            } else {
                fatal(fixture.corpusPath() + ": the sidecar does not validate: "
                    + failure.get().message());
            }
            SidecarExpectations.StructuredExpectationSidecar sidecar =
                SidecarExpectations.StructuredExpectationSidecar.parse(
                    Files.readString(sidecarPath, StandardCharsets.UTF_8));
            for (String backend : SidecarSchemaValidator.BACKEND_NAMES) {
                check(sidecar.expectationFor(backend) != null,
                    fixture.corpusPath() + " dispatches to backend " + backend);
            }
        }
        checkEq(dispatched.size(), validated,
            "every dispatched sidecar validates clean against schema version 1");
    }

    /** The dispatched runtime fixtures of the current validated discovery. */
    static List<CorpusDiscovery.Fixture> dispatchedOf(
            CorpusDiscovery.DiscoveryResult discovery) {
        List<CorpusDiscovery.Fixture> dispatched = new ArrayList<>();
        for (CorpusDiscovery.Fixture fixture : discovery.fixtures()) {
            if (fixture.runtimeClassified()) {
                dispatched.add(fixture);
            }
        }
        return dispatched;
    }

    // =========================================================================
    // 2/3. The compile and capture criteria, one materialized drive per cell
    // =========================================================================

    private static void testFixture(CorpusDiscovery.Fixture fixture,
            List<String> report, List<String> declarationImports,
            Map<String, CorpusDiscovery.Fixture> byPath) throws Exception {
        String label = fixture.corpusPath();
        List<Export> exports;
        try {
            exports = exportsOf(fixture.source(), fixture.corpusPath());
        } catch (RuntimeException e) {
            fatal(label + ": the entry module does not parse for the export "
                + "drive: " + e);
            return;
        }
        if (fixture.source().contains("from \"host/")
                || fixture.source().contains("candidate/")) {
            declarationImports.add(label);
        }
        List<SidecarSchemaValidator.CompilationModule> compilationSet =
            CorpusDiscovery.compilationSet(fixture, byPath, CONFORMANCE);
        for (Target target : Target.values()) {
            materializeCompileDrive(fixture, compilationSet, label, target,
                exports, report);
        }
    }

    /** One fixture/target cell: materialize, compile, and (when producible) drive. */
    private static void materializeCompileDrive(CorpusDiscovery.Fixture fixture,
            List<SidecarSchemaValidator.CompilationModule> compilationSet,
            String label, Target target, List<Export> exports,
            List<String> report) throws Exception {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException(
                "the audit worker was cancelled before the cell " + label);
        }
        Project project = materialize(label, compilationSet, target, true);
        try {
            CompileOutcome outcome = compile(project);
            List<String> staged = stagedOutputFiles(project);
            SidecarExpectations.RuntimeExpectation expectation =
                expectationOf(fixture, target);
            CellVerdict compileLeg = compileVerdict(label, target, outcome,
                expectation, staged);
            record(label, target, LegKind.COMPILE, compileLeg, report);
            if (!compileLeg.pass()) {
                return;
            }
            if (expectation
                    instanceof SidecarExpectations.RuntimeExpectation.Rejected) {
                // The sanctioned extern-C rejection leg: the compile criterion
                // owns it, and no artifact exists to drive.
                rejectLegs.incrementAndGet();
                return;
            }
            compileLegs.incrementAndGet();
            Execution execution;
            try {
                execution = execute(project, exports, target);
            } catch (AuditInputException e) {
                // A missing host implementation is an audit-input defect,
                // never a construct gap; it is fatal in every mode.
                fatal(label + " [" + target.laneName() + "] audit input: " + e);
                return;
            } catch (InterruptedException e) {
                // A cancelled audit stops the cell instead of continuing to
                // its next target: the interruption propagates (with the
                // status restored) to the owned-pool teardown, which
                // contains every worker and owned subprocess before the
                // shared audit-owned artifacts are removed.
                Thread.currentThread().interrupt();
                throw e;
            } catch (Exception e) {
                if (Thread.currentThread().isInterrupted()) {
                    // The cancellation aborted this cell's work and the
                    // worker stops here instead of continuing to its next
                    // target; the interruption propagates to the owned-pool
                    // teardown.
                    throw new InterruptedException(
                        "the audit worker was cancelled while the cell "
                            + label + " failed: " + e);
                }
                // A broken tool invocation, a corrupted artifact, a failed
                // artifact load, an unreadable or incomplete transcript, and
                // a subprocess deadline expiry are infrastructure, never a
                // construct or capture deficit: fatal in every mode.
                record(label, target, LegKind.CAPTURE,
                    driveFailureVerdict(label, target, e), report);
                return;
            }
            captureLegs.incrementAndGet();
            Capture normalized = normalize(project, execution.capture());
            Optional<String> mismatch = compareCell(label, target, normalized,
                execution, expectation);
            record(label, target, LegKind.CAPTURE, captureVerdict(label, target,
                normalized, execution, expectation, mismatch), report);
        } finally {
            deleteRecursively(project.root(),
                label + " [" + target.laneName() + "]");
        }
    }

    /**
     * Every staged file of one compiled project, relative to its output root
     * (the rejection pre-artifact check: a sanctioned rejection stages
     * nothing at all).
     */
    static List<String> stagedOutputFiles(Project project) {
        Path out = project.outputRoot();
        if (!Files.isDirectory(out)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(out)) {
            return walk.filter(Files::isRegularFile)
                .map(path -> CorpusDiscovery.slash(out.relativize(path)))
                .sorted().toList();
        } catch (Exception e) {
            return List.of("<unreadable: " + e.getMessage() + ">");
        }
    }

    /**
     * The staged module artifacts of one compiled project, relative to its
     * output root: every emitted module artifact, never the deployed
     * runtime/stdlib surface (the production arm stages exactly the entry
     * artifact, no per-module sibling). Used as the fidelity artifact-set
     * assertion.
     */
    static List<String> stagedModuleArtifacts(Project project) {
        Path out = project.outputRoot();
        if (!Files.isDirectory(out)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(out)) {
            return walk.filter(Files::isRegularFile)
                .map(path -> CorpusDiscovery.slash(out.relativize(path)))
                .filter(rel -> !rel.startsWith("deal/")
                    && !rel.startsWith("std/"))
                .filter(rel -> rel.endsWith(".lua") || rel.endsWith(".java"))
                .sorted().toList();
        } catch (Exception e) {
            return List.of("<unreadable: " + e.getMessage() + ">");
        }
    }

    /** Records one cell: the ordered report line, the pass tally, and the leg. */
    private static void record(String corpusPath, Target target, LegKind kind,
            CellVerdict verdict, List<String> report) {
        synchronized (report) {
            report.add(verdict.line());
        }
        if (verdict.pass()) {
            passed.incrementAndGet();
        }
        legs.add(new Leg(corpusPath, target, kind, verdict.clazz(),
            verdict.line()));
    }

    // =========================================================================
    // 4. The negative control
    // =========================================================================

    private static void testNegativeControl() throws Exception {
        System.out.println("-- the negative control: a corrupted staged artifact "
            + "fails the audit by name, and the driver's own verdict function "
            + "decides it --");
        String fixtureRel = "backend-runtime/control-flow/for-of-break-continue";
        CorpusDiscovery.Fixture fixture = fixtureOf(fixtureRel + ".deal");
        List<Export> exports = exportsOf(fixture.source(), fixture.corpusPath());
        Project project = materialize(fixture.corpusPath(),
            CorpusDiscovery.compilationSet(fixture,
                CorpusDiscovery.discover(CONFORMANCE).corpusByPath(),
                CONFORMANCE), Target.LUAJIT, true);
        try {
            CompileOutcome outcome = compile(project);
            check(outcome.compiled(), "the negative-control fixture compiles: "
                + outcome.firstErrorDetail());
            if (!outcome.compiled()) {
                return;
            }
            // The pristine drive reproduces the runtime-ok pin.
            Execution pristine = execute(project, exports, Target.LUAJIT);
            SidecarExpectations.RuntimeExpectation expectation =
                expectationOf(fixture, Target.LUAJIT);
            Optional<String> pristineMismatch = compareCell(
                fixture.corpusPath(), Target.LUAJIT,
                normalize(project, pristine.capture()), pristine, expectation);
            check(pristineMismatch.isEmpty(),
                "the pristine negative-control drive passes: "
                    + pristineMismatch.orElse(""));

            // Corrupt the staged chunk: the corrupted copy carries a
            // broken chunk (the production artifact's own bytes replaced by
            // a chunk that publishes no deferred entry), so the drive
            // cannot load it and the comparison fails by fixture and field.
            Path artifact = artifactOf(project, Target.LUAJIT);
            String text = Files.readString(artifact, StandardCharsets.UTF_8);
            check(!text.isEmpty(), "the staged chunk carries the production "
                + "artifact the negative control corrupts");
            Path corrupted = project.outputRoot().resolve("__corrupted.lua");
            Files.writeString(corrupted,
                text.substring(0, Math.min(64, text.length()))
                    .replace("__dealMain", "__dealMainBroken"),
                StandardCharsets.UTF_8);
            Path transport = project.outputRoot().resolve(TRANSPORT_FILE);
            Files.deleteIfExists(transport);
            Path probe = project.outputRoot().resolve("__corrupted_probe.lua");
            Files.writeString(probe, luaProbe(project, exports, corrupted,
                transport), StandardCharsets.UTF_8);
            ProcessBuilder builder = new ProcessBuilder("luajit",
                probe.toAbsolutePath().toString());
            builder.directory(project.outputRoot().toFile());
            builder.environment().put("DEAL_DEFER_MAIN", "1");
            ProcessOutcome corruptedRun = runProcess(builder);
            Capture capture = normalize(project, readTransport(transport));
            Execution corruptedExecution = new Execution(corruptedRun.exitCode(),
                corruptedRun.stdout(), corruptedRun.stderr(), readTransport(transport));
            Optional<String> corruptedMismatch = compareCell(
                fixture.corpusPath(), Target.LUAJIT, capture, corruptedExecution,
                expectation);
            check(corruptedMismatch.isPresent(),
                "the corrupted artifact fails the capture criterion");
            check(corruptedMismatch.isPresent()
                    && corruptedMismatch.get().contains(fixture.corpusPath()),
                "the corrupted-artifact failure names the fixture: "
                    + corruptedMismatch.orElse(""));

            // The corpus driver's own verdict function decides this cell, and
            // the corrupted artifact reaches a fatal class in every mode: the
            // negative control proves the criterion is not vacuous, not just
            // that a mismatch string exists.
            CellVerdict corruptedVerdict = captureVerdict(fixture.corpusPath(),
                Target.LUAJIT, capture, corruptedExecution, expectation,
                corruptedMismatch);
            check(!corruptedVerdict.pass()
                    && isFatal(corruptedVerdict.clazz(), false)
                    && isFatal(corruptedVerdict.clazz(), true),
                "the corrupted-artifact verdict is fatal in every mode: "
                    + corruptedVerdict.clazz() + " - "
                    + corruptedVerdict.line());
            System.out.println("  negative control: "
                + corruptedVerdict.line());

            // The compile criterion's guard detection is not vacuous: a
            // hand-built E6005 guard diagnostic is reported by the detector.
            CompilerDiagnostic guard = FailureContractRegistry.e6005(
                new LoweringFailureDetail("negative-control",
                    SemanticCapability.CONTAINERS_AND_STRINGS,
                    SemanticLowerer.CONSTRUCT_UNLOWERED,
                    SemanticProfile.DEAL_V1_2_INT32, "deal.semantic-ir/1",
                    "SemanticLowerer CONSTRUCT_UNLOWERED (negative control)"));
            check(isGuardDiagnostic(guard),
                "the guard detector reports a hand-built CONSTRUCT_UNLOWERED "
                    + "E6005");

            // The acceptance policy's truth table is not vacuous. The
            // permitted early-leaf deficits are reported while the compile
            // criterion is unmet and fatal once it is met; infrastructure
            // failures, architectural guards, unclassified defects and
            // rejection mismatches are fatal in both modes — no construct
            // deficit in one cell can excuse any of them.
            check(!isFatal(LegClass.CONSTRUCT_DEFICIT, false)
                    && !isFatal(LegClass.PROJECTION_DEFICIT, false)
                    && !isFatal(LegClass.BEHAVIOR_DEFICIT, false),
                "the pre-precondition reporting rule makes the construct, "
                    + "projection and pinned behavior deficits non-fatal while "
                    + "the compile criterion is unmet");
            check(isFatal(LegClass.CONSTRUCT_DEFICIT, true)
                    && isFatal(LegClass.PROJECTION_DEFICIT, true)
                    && isFatal(LegClass.BEHAVIOR_DEFICIT, true),
                "the strict acceptance mode makes every non-pass leg fatal");
            check(isFatal(LegClass.ARCHITECTURAL_GUARD, false)
                    && isFatal(LegClass.ARCHITECTURAL_GUARD, true)
                    && isFatal(LegClass.DEFECT, false)
                    && isFatal(LegClass.DEFECT, true)
                    && isFatal(LegClass.INFRASTRUCTURE, false)
                    && isFatal(LegClass.INFRASTRUCTURE, true),
                "architectural-guard, infrastructure and unclassified-defect "
                    + "legs are fatal in both modes");
            check(!isFatal(LegClass.PASS, false)
                    && !isFatal(LegClass.PASS, true),
                "a pin-exact leg is never fatal");
        } finally {
            deleteRecursively(project.root(), fixtureRel);
        }
        runtimeErrorComparisonControls();
        rejectionControls();
        guardDetectionControls();
        projectionInventoryControls();
        unclassifiedBehaviorControls();
        subprocessCancellationControl();
        auditPoolCancellationControl();
        ownedTreeTeardownControls();
        subprocessDrainControls();
        nativeBootstrapControls();
        cleanupFailureControl();
    }

    /**
     * The guard-detection controls: a producer guard is the exact
     * {@code E6005} {@code validatorRule} field, never a substring of the
     * whole diagnostic message. A non-{@code E6005} diagnostic whose text
     * merely contains the token (an unresolved declaration whose module path
     * names the guard) and an {@code E6005} whose different rule carries the
     * token only in its module and origin fields are unclassified compile
     * failures, fatal in both modes; they can never disengage strict
     * acceptance as an interim construct deficit. The exact guard rules
     * still classify: the canonical {@code CONSTRUCT_UNLOWERED} leg stays
     * the reported construct deficit and the two architectural rules stay
     * the fatal architectural guards.
     */
    private static void guardDetectionControls() {
        System.out.println("-- the guard-detection controls: a producer guard "
            + "is the exact E6005 validatorRule field --");
        SidecarExpectations.RuntimeExpectation.Executed executed =
            new SidecarExpectations.RuntimeExpectation.Executed("runtime-ok",
                new byte[0], new byte[0], 0, null);

        // (a) A non-E6005 diagnostic whose message carries the token: an
        // unresolved declaration whose module path names the construct
        // guard. This is the shape the materialization probe's E2003 gap
        // carries; it must stay a fatal unclassified compile failure.
        CompilerDiagnostic nonE6005 = CompilerDiagnostic.error("E2003",
            "Module not found: ./CONSTRUCT_UNLOWERED.d.deal",
            deal.diagnostics.DiagnosticRange.synthetic("guard-control.deal"));
        check(!isGuardDiagnostic(nonE6005),
            "a non-E6005 diagnostic carrying the token is not a producer "
                + "guard");
        check(!isConstructGuard(nonE6005),
            "a non-E6005 diagnostic carrying the token is not the construct "
                + "guard");
        check(!architecturalGuardFired(new CompileOutcome(false, true,
                List.of(nonE6005), List.of(nonE6005), "control")),
            "a non-E6005 diagnostic carrying the token is not an "
                + "architectural guard");
        assertFatalBothModes(compileVerdict("guard-control", Target.JVM,
            new CompileOutcome(false, true, List.of(nonE6005),
                List.of(nonE6005), "control"),
            executed, List.of()),
            "a non-E6005 compile failure whose message carries the token");

        // (b) A canonical E6005 whose different validatorRule carries the
        // token only in its module and origin fields.
        CompilerDiagnostic wrongRule = FailureContractRegistry.e6005(
            new LoweringFailureDetail("CONSTRUCT_UNLOWERED.deal",
                SemanticCapability.CONTAINERS_AND_STRINGS, "CAPTURE_RESOLUTION",
                SemanticProfile.DEAL_V1_2_INT32, "deal.semantic-ir/1",
                "SemanticLowerer CONSTRUCT_UNLOWERED (the module and origin "
                    + "merely carry the token)"));
        check(!isGuardDiagnostic(wrongRule),
            "an E6005 with another validatorRule whose module and origin "
                + "carry the token is not a producer guard");
        check(!isConstructGuard(wrongRule),
            "an E6005 with another validatorRule whose module and origin "
                + "carry the token is not the construct guard");
        assertFatalBothModes(compileVerdict("guard-control", Target.JVM,
            new CompileOutcome(false, true, List.of(wrongRule),
                List.of(wrongRule), "control"),
            executed, List.of()),
            "an E6005 with a different validatorRule whose module and origin "
                + "carry the token");

        // (c) The architectural guards obey the same exact-rule rule.
        CompilerDiagnostic architecturalCarrier = CompilerDiagnostic.error(
            "E2003", "Module not found: ./RETAINED_ABI_DEFERRED.d.deal",
            deal.diagnostics.DiagnosticRange.synthetic("guard-control.deal"));
        check(!architecturalGuardFired(new CompileOutcome(false, true,
                List.of(architecturalCarrier), List.of(architecturalCarrier),
                "control")),
            "a non-E6005 diagnostic carrying an architectural token is not "
                + "an architectural guard");
        CompilerDiagnostic wrongArchitecturalRule = FailureContractRegistry.e6005(
            new LoweringFailureDetail("RETAINED_ABI_DEFERRED.deal",
                SemanticCapability.MODULES, "CAPTURE_RESOLUTION",
                SemanticProfile.DEAL_V1_2_INT32, "deal.semantic-ir/1",
                "ProductionProjectEmission RETAINED_ABI_DEFERRED (the module "
                    + "and origin merely carry the token)"));
        check(!architecturalGuardFired(new CompileOutcome(false, true,
                List.of(wrongArchitecturalRule),
                List.of(wrongArchitecturalRule), "control")),
            "an E6005 with another validatorRule whose module and origin "
                + "carry an architectural token is not an architectural "
                + "guard");
        assertFatalBothModes(compileVerdict("guard-control", Target.JVM,
            new CompileOutcome(false, true, List.of(wrongArchitecturalRule),
                List.of(wrongArchitecturalRule), "control"),
            executed, List.of()),
            "an E6005 with a different validatorRule whose module and origin "
                + "carry an architectural token");

        // (d) The exact rules still classify: the canonical construct guard
        // stays the reported interim deficit, and each architectural rule
        // stays the fatal architectural guard.
        List<CompilerDiagnostic> exactConstruct = List.of(guardDiagnostic(
            SemanticLowerer.CONSTRUCT_UNLOWERED));
        check(isConstructGuard(exactConstruct.get(0)),
            "the exact canonical CONSTRUCT_UNLOWERED E6005 is the construct "
                + "guard");
        CellVerdict constructLeg = compileVerdict("guard-control", Target.JVM,
            new CompileOutcome(false, true, exactConstruct, exactConstruct,
                "control"),
            executed, List.of());
        check(constructLeg.clazz() == LegClass.CONSTRUCT_DEFICIT
                && !isFatal(constructLeg.clazz(), false)
                && isFatal(constructLeg.clazz(), true),
            "the exact construct guard stays the reported interim deficit: "
                + constructLeg.line());
        for (String rule : List.of(SemanticLowerer.RETAINED_ABI_DEFERRED,
                ProductionProjectEmission.SHARED_EMITTER_COVERAGE)) {
            List<CompilerDiagnostic> exactGuard = List.of(guardDiagnostic(rule));
            check(architecturalGuardFired(new CompileOutcome(false, true,
                    exactGuard, exactGuard, "control")),
                "the exact canonical " + rule + " E6005 is the "
                    + "architectural guard");
            assertFatalBothModes(compileVerdict("guard-control", Target.JVM,
                new CompileOutcome(false, true, exactGuard, exactGuard,
                    "control"),
                executed, List.of()),
                "the exact " + rule + " architectural guard");
        }
    }

    /**
     * The audit-pool cancellation control: the audit's own dispatched-corpus
     * pool helper is cancelled while one of its workers runs an owned child.
     * The owned-pool teardown cancels the pending task, interrupts the
     * active worker, awaits the pool's termination and reports it stopped
     * before the shared audit-owned artifacts may be removed: at the moment
     * the helper returns no worker thread, no owned child and no
     * audit-owned reader is alive, the pending task never ran, and the
     * caller observed the interruption (the caller's probe of the actual
     * pool, not only of an isolated subprocess helper).
     */
    private static void auditPoolCancellationControl() throws Exception {
        System.out.println("-- the audit-pool cancellation control: "
            + "cancelling the audit's own worker pool stops every worker "
            + "and owned child before the shared audit-owned artifacts may "
            + "be removed --");
        Path root = Files.createTempDirectory("production-corpus-audit-pool-");
        try {
            Path pidFile = root.resolve("child.pid");
            java.util.concurrent.atomic.AtomicReference<Thread> worker =
                new java.util.concurrent.atomic.AtomicReference<>();
            java.util.concurrent.atomic.AtomicBoolean interruptObserved =
                new java.util.concurrent.atomic.AtomicBoolean();
            java.util.concurrent.atomic.AtomicBoolean pendingRan =
                new java.util.concurrent.atomic.AtomicBoolean();
            java.util.concurrent.atomic.AtomicReference<String> failure =
                new java.util.concurrent.atomic.AtomicReference<>();
            List<Runnable> tasks = List.of(
                () -> {
                    worker.set(Thread.currentThread());
                    try {
                        runProcess(new ProcessBuilder("sh", "-c",
                            "echo $$ > " + pidFile + "; exec sleep 30"));
                        failure.compareAndSet(null, "the cancelled pool "
                            + "task returned normally");
                    } catch (InterruptedException e) {
                        interruptObserved.set(true);
                        Thread.currentThread().interrupt();
                    } catch (Exception e) {
                        failure.compareAndSet(null, "the cancelled pool "
                            + "task raised " + e);
                    }
                },
                () -> pendingRan.set(true));
            java.util.concurrent.atomic.AtomicBoolean callerInterrupted =
                new java.util.concurrent.atomic.AtomicBoolean();
            Thread caller = new Thread(() -> {
                try {
                    runOwnedPool(1, tasks);
                    failure.compareAndSet(null, "the cancelled pool drive "
                        + "returned normally");
                } catch (InterruptedException e) {
                    callerInterrupted.set(true);
                } catch (Exception e) {
                    failure.compareAndSet(null, "the cancelled pool drive "
                        + "raised " + e);
                }
            }, "audit-pool-cancel-control");
            caller.setDaemon(true);
            // The control probes the pool state through the helper itself:
            // the flag is cleared before the drive so the teardown's report
            // is the only thing that can set it.
            auditPoolStopped = false;
            caller.start();
            long startDeadline = System.nanoTime()
                + TimeUnit.SECONDS.toNanos(10);
            while (!Files.isRegularFile(pidFile)
                    && System.nanoTime() < startDeadline) {
                Thread.sleep(20);
            }
            check(Files.isRegularFile(pidFile),
                "the audit-pool control's owned child started");
            if (!Files.isRegularFile(pidFile)) {
                return;
            }
            caller.interrupt();
            caller.join(TimeUnit.SECONDS.toMillis(60));
            check(!caller.isAlive(),
                "the cancelled pool drive returns within the bound");
            check(callerInterrupted.get(),
                "the pool-drive caller observed the interruption: "
                    + failure.get());
            check(interruptObserved.get(),
                "the active worker was interrupted and stopped its cell: "
                    + failure.get());
            check(!pendingRan.get(),
                "the pending pool task was cancelled and never ran");
            check(auditPoolStopped,
                "the owned-pool teardown reports the pool stopped before "
                    + "the shared audit-owned artifacts are removed");
            Thread workerThread = worker.get();
            check(workerThread != null, "the active pool worker started");
            if (workerThread != null) {
                workerThread.join(TimeUnit.SECONDS.toMillis(10));
                check(!workerThread.isAlive(),
                    "no audit worker survives the pool teardown");
            }
            check(!pidAlive(pidFile),
                "the owned child is gone after the pool teardown");
            checkEq(0L, auditReaderThreads(),
                "no audit-owned reader thread survives the pool teardown");
        } finally {
            deleteRecursively(root, "audit-pool-cancellation-control");
        }
    }

    /**
     * The owned-tree teardown controls: the direct child leaves a persistent
     * descendant that holds the inherited pipes open and then waits. On the
     * bounded-deadline path and on the cancellation path alike the teardown
     * contains the whole owned tree, so after the call returns no
     * descendant, no direct child and no audit-owned reader survives —
     * nothing can race the scratch/native cleanup, and a stalled tool can
     * never keep a drain (or an audit-owned process) alive past the call.
     */
    private static void ownedTreeTeardownControls() throws Exception {
        System.out.println("-- the owned-tree teardown controls: a persistent "
            + "descendant holding the inherited pipes open dies with the "
            + "tree --");

        // (a) The bounded-deadline path: the shell starts a persistent
        // background descendant, records its pid and waits, so the direct
        // child outlives the process deadline and the descendant holds the
        // inherited pipes open until the teardown kills the whole tree.
        Path deadlineRoot = Files.createTempDirectory(
            "production-corpus-audit-tree-deadline-");
        try {
            Path pidFile = deadlineRoot.resolve("descendant.pid");
            java.util.concurrent.atomic.AtomicReference<Exception> raised =
                new java.util.concurrent.atomic.AtomicReference<>();
            java.util.concurrent.atomic.AtomicBoolean returned =
                new java.util.concurrent.atomic.AtomicBoolean();
            Thread worker = new Thread(() -> {
                try {
                    runOwnedProcess(new ProcessBuilder("sh", "-c",
                        "sleep 30 & echo $! > " + pidFile + "; wait"),
                        1, 1, 0);
                    returned.set(true);
                } catch (Exception e) {
                    raised.set(e);
                }
            }, "audit-tree-deadline-control");
            worker.setDaemon(true);
            worker.start();
            boolean started = awaitPidFile(pidFile);
            check(started,
                "the owned-tree deadline control's descendant started");
            if (!started) {
                return;
            }
            worker.join(TimeUnit.SECONDS.toMillis(30));
            check(!worker.isAlive(),
                "the owned-tree deadline control returns within the bound");
            check(!returned.get() && raised.get() != null
                    && String.valueOf(raised.get()).contains("deadline"),
                "the owned-tree deadline control fails closed by deadline: "
                    + (raised.get() == null ? "no failure" : raised.get()));
            awaitPidGone(pidFile);
            check(!pidAlive(pidFile),
                "no descendant survives the owned-tree deadline teardown");
            awaitAuditReaders();
            checkEq(0L, auditReaderThreads(),
                "no owned reader thread survives the owned-tree deadline "
                    + "teardown");
        } finally {
            deleteRecursively(deadlineRoot, "owned-tree-deadline-control");
        }

        // (b) The cancellation path: the same persistent descendant, with
        // the calling worker interrupted while the direct child waits. The
        // teardown owns the tree before the interruption propagates.
        Path cancelRoot = Files.createTempDirectory(
            "production-corpus-audit-tree-cancel-");
        try {
            Path pidFile = cancelRoot.resolve("descendant.pid");
            java.util.concurrent.atomic.AtomicBoolean interrupted =
                new java.util.concurrent.atomic.AtomicBoolean();
            java.util.concurrent.atomic.AtomicReference<String> failure =
                new java.util.concurrent.atomic.AtomicReference<>();
            Thread worker = new Thread(() -> {
                try {
                    runOwnedProcess(new ProcessBuilder("sh", "-c",
                        "sleep 30 & echo $! > " + pidFile + "; wait"),
                        30, 30, 0);
                    failure.set("the cancelled owned-tree drive returned "
                        + "normally");
                } catch (InterruptedException e) {
                    interrupted.set(true);
                } catch (Exception e) {
                    failure.set("the cancelled owned-tree drive raised " + e);
                }
            }, "audit-tree-cancel-control");
            worker.setDaemon(true);
            worker.start();
            boolean started = awaitPidFile(pidFile);
            check(started,
                "the owned-tree cancellation control's descendant started");
            if (!started) {
                return;
            }
            worker.interrupt();
            worker.join(TimeUnit.SECONDS.toMillis(30));
            check(!worker.isAlive(),
                "the owned-tree cancellation control returns within the "
                    + "bound");
            check(interrupted.get(),
                "the owned-tree cancellation control observed the "
                    + "interruption: " + failure.get());
            awaitPidGone(pidFile);
            check(!pidAlive(pidFile),
                "no descendant survives the owned-tree cancellation "
                    + "teardown");
            awaitAuditReaders();
            checkEq(0L, auditReaderThreads(),
                "no owned reader thread survives the owned-tree "
                    + "cancellation teardown");
        } finally {
            deleteRecursively(cancelRoot,
                "owned-tree-cancellation-control");
        }
    }

    /** Waits (bounded) for a control's pid file to carry its recorded pid. */
    private static boolean awaitPidFile(Path pidFile) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (Files.isRegularFile(pidFile)) {
                String text = Files.readString(pidFile, StandardCharsets.UTF_8)
                    .trim();
                if (!text.isEmpty()) {
                    return true;
                }
            }
            Thread.sleep(20);
        }
        return false;
    }

    /** Waits (bounded) for a control's recorded descendant to disappear. */
    private static void awaitPidGone(Path pidFile) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (pidAlive(pidFile) && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
    }

    /** Waits (bounded) for every audit-owned reader thread to end. */
    private static void awaitAuditReaders() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (auditReaderThreads() > 0 && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
    }

    /**
     * The subprocess-cancellation control: a worker interrupted while its
     * owned child runs returns with the interruption observed, the owned
     * child is gone, and no audit-owned reader thread survives, so the
     * scratch/native cleanup can never race an ongoing execution.
     */
    private static void subprocessCancellationControl() throws Exception {
        System.out.println("-- the subprocess-cancellation control: an "
            + "interrupted cell closes its owned child and readers --");
        Path root = Files.createTempDirectory("production-corpus-audit-cancel-");
        try {
            Path pidFile = root.resolve("child.pid");
            java.util.concurrent.atomic.AtomicBoolean interrupted =
                new java.util.concurrent.atomic.AtomicBoolean();
            java.util.concurrent.atomic.AtomicReference<String> failure =
                new java.util.concurrent.atomic.AtomicReference<>();
            Thread worker = new Thread(() -> {
                try {
                    runProcess(new ProcessBuilder("sh", "-c",
                        "echo $$ > " + pidFile + "; exec sleep 30"));
                    failure.set("the cancelled drive returned normally");
                } catch (InterruptedException e) {
                    interrupted.set(true);
                } catch (Exception e) {
                    failure.set("the cancelled drive raised " + e);
                }
            }, "audit-cancellation-control");
            worker.setDaemon(true);
            worker.start();
            long startDeadline = System.nanoTime()
                + TimeUnit.SECONDS.toNanos(10);
            while (!Files.isRegularFile(pidFile)
                    && System.nanoTime() < startDeadline) {
                Thread.sleep(20);
            }
            check(Files.isRegularFile(pidFile),
                "the cancellation control's owned child started");
            if (!Files.isRegularFile(pidFile)) {
                return;
            }
            worker.interrupt();
            worker.join(TimeUnit.SECONDS.toMillis(20));
            check(!worker.isAlive(),
                "the interrupted subprocess worker returns");
            check(interrupted.get(), "the worker observed the interruption: "
                + failure.get());
            long pid = Long.parseLong(
                Files.readString(pidFile, StandardCharsets.UTF_8).trim());
            ProcessHandle child = ProcessHandle.of(pid).orElse(null);
            check(child == null || !child.isAlive(),
                "the owned child is gone after the cancellation (pid " + pid
                    + ")");
            checkEq(0L, auditReaderThreads(),
                "no audit-owned reader thread survives the cancellation");
        } finally {
            deleteRecursively(root, "subprocess-cancellation-control");
        }
    }

    /**
     * The transcript-drain controls: a reader failure is recorded and
     * classified as infrastructure, and a transcript that is still open when
     * the bounded drain window closes fails the call instead of returning
     * substituted empty output. Both classes reach the corpus driver's own
     * fatal verdict path, so neither can leave a registered audit green.
     */
    private static void subprocessDrainControls() throws Exception {
        System.out.println("-- the transcript-drain controls: an unreadable "
            + "or incomplete transcript is an infrastructure failure, never "
            + "empty output --");
        Throwable[] failure = new Throwable[1];
        byte[] bytes = readAll(new java.io.InputStream() {
            @Override
            public int read() throws java.io.IOException {
                throw new java.io.IOException("review-read-failure");
            }
        }, 0, failure);
        check(bytes.length == 0,
            "an unreadable transcript contributes no bytes");
        check(failure[0] instanceof java.io.IOException
                && String.valueOf(failure[0].getMessage())
                    .contains("review-read-failure"),
            "the reader failure is recorded, never swallowed into empty "
                + "output: " + failure[0]);
        assertFatalBothModes(driveFailureVerdict("drain-control",
            Target.LUAJIT, new IllegalStateException("the subprocess "
                + "transcript drain failed: " + failure[0])),
            "a transcript read failure");

        Path root = Files.createTempDirectory("production-corpus-audit-drain-");
        try {
            java.util.concurrent.atomic.AtomicReference<Exception> raised =
                new java.util.concurrent.atomic.AtomicReference<>();
            java.util.concurrent.atomic.AtomicBoolean returned =
                new java.util.concurrent.atomic.AtomicBoolean();
            Thread worker = new Thread(() -> {
                try {
                    // The direct child exits after the readers have entered
                    // their drains; a descendant then holds the transcript
                    // open past the bounded drain window, so the capture is
                    // incomplete at the deadline. (An immediate child exit
                    // would race the JDK pipe reaper, which can close the
                    // read end before the descendant's later output.)
                    runOwnedProcess(new ProcessBuilder("sh", "-c",
                        "sleep 1; (sleep 2; printf late-output) & exit 0"),
                        30, 1, 0);
                    returned.set(true);
                } catch (Exception e) {
                    raised.set(e);
                }
            }, "audit-drain-control");
            worker.setDaemon(true);
            worker.start();
            worker.join(TimeUnit.SECONDS.toMillis(20));
            check(!worker.isAlive(),
                "the incomplete-drain control returns within the bound");
            check(!returned.get() && raised.get() != null
                    && String.valueOf(raised.get()).contains("incomplete"),
                "an incomplete transcript drain fails closed, never returns "
                    + "empty output: "
                    + (raised.get() == null ? "no failure" : raised.get()));
            if (raised.get() != null) {
                assertFatalBothModes(driveFailureVerdict("drain-control",
                    Target.LUAJIT, raised.get()),
                    "an incomplete transcript drain");
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (auditReaderThreads() > 0 && System.nanoTime() < deadline) {
                Thread.sleep(50);
            }
            checkEq(0L, auditReaderThreads(),
                "no audit-owned reader thread survives the incomplete drain");
        } finally {
            deleteRecursively(root, "transcript-drain-control");
        }
    }

    /**
     * The native-bootstrap controls: the audit-owned GCC run is deadline- and
     * cancellation-bounded like every other owned subprocess. A tool that
     * ignores the deadline fails the bootstrap as an audit-input defect and
     * its child is reaped; an interrupted worker leaves with the interruption
     * preserved, no live child and no surviving reader.
     */
    private static void nativeBootstrapControls() throws Exception {
        System.out.println("-- the native-bootstrap controls: GCC runs under "
            + "the bounded deadline and the owned-process teardown --");
        Path root = Files.createTempDirectory("production-corpus-audit-gcc-");
        try {
            Path pidFile = root.resolve("gcc.pid");
            Path fakeGcc = root.resolve("gcc");
            Files.writeString(fakeGcc, "#!/bin/sh\necho $$ > " + pidFile
                + "\nexec sleep 30\n", StandardCharsets.UTF_8);
            if (!fakeGcc.toFile().setExecutable(true)) {
                fatal("the native-bootstrap control cannot make its fake gcc "
                    + "executable: " + fakeGcc);
                return;
            }
            Path source = root.resolve("native.c");
            Files.writeString(source, "int candidate(void) { return 0; }\n",
                StandardCharsets.UTF_8);
            Path library = root.resolve(CorpusFfi.NATIVE_LIBRARY_NAME);
            List<String> command = List.of(fakeGcc.toString(), "-shared",
                "-fPIC", "-O2", "-o", library.toString(), source.toString());

            // (a) The deadline: a tool that ignores it fails the bootstrap
            // as an audit-input defect and its child is reaped, never a hang.
            java.util.concurrent.atomic.AtomicReference<Exception> timedOut =
                new java.util.concurrent.atomic.AtomicReference<>();
            Thread timeoutWorker = new Thread(() -> {
                try {
                    bootstrapNativeLibrary("native-timeout-control", source,
                        library, command, 2);
                    timedOut.set(new IllegalStateException(
                        "the stalled native bootstrap returned normally"));
                } catch (InterruptedException e) {
                    timedOut.set(e);
                } catch (Exception e) {
                    timedOut.set(e);
                }
            }, "audit-native-timeout-control");
            timeoutWorker.setDaemon(true);
            timeoutWorker.start();
            timeoutWorker.join(TimeUnit.SECONDS.toMillis(60));
            check(!timeoutWorker.isAlive(),
                "the native-timeout control returns within the bound");
            check(timedOut.get() instanceof AuditInputException
                    && String.valueOf(timedOut.get()).contains("deadline"),
                "a stalled native tool fails the bootstrap by deadline: "
                    + timedOut.get());
            check(!pidAlive(pidFile), "the timed-out native child is reaped");
            check(!Files.exists(library),
                "the timed-out native bootstrap stages no library");

            // (b) The interruption: the calling worker's cancellation closes
            // and reaps the owned child and both readers, and the
            // interruption is preserved.
            Files.deleteIfExists(pidFile);
            java.util.concurrent.atomic.AtomicBoolean interrupted =
                new java.util.concurrent.atomic.AtomicBoolean();
            java.util.concurrent.atomic.AtomicReference<String> failure =
                new java.util.concurrent.atomic.AtomicReference<>();
            Thread worker = new Thread(() -> {
                try {
                    bootstrapNativeLibrary("native-cancel-control", source,
                        library, command);
                    failure.set("the cancelled native bootstrap returned "
                        + "normally");
                } catch (InterruptedException e) {
                    interrupted.set(true);
                } catch (Exception e) {
                    failure.set("the cancelled native bootstrap raised " + e);
                }
            }, "audit-native-cancel-control");
            worker.setDaemon(true);
            worker.start();
            long startDeadline = System.nanoTime()
                + TimeUnit.SECONDS.toNanos(10);
            while (!Files.isRegularFile(pidFile)
                    && System.nanoTime() < startDeadline) {
                Thread.sleep(20);
            }
            check(Files.isRegularFile(pidFile),
                "the native-cancellation control's gcc child started");
            if (!Files.isRegularFile(pidFile)) {
                return;
            }
            worker.interrupt();
            worker.join(TimeUnit.SECONDS.toMillis(20));
            check(!worker.isAlive(),
                "the interrupted native-bootstrap worker returns");
            check(interrupted.get(), "the native-bootstrap worker observed "
                + "the interruption: " + failure.get());
            check(!pidAlive(pidFile),
                "the cancelled native child is reaped");
            checkEq(0L, auditReaderThreads(),
                "no audit-owned reader thread survives the native-bootstrap "
                    + "cancellation");
        } finally {
            deleteRecursively(root, "native-bootstrap-control");
        }
    }

    /**
     * The cleanup-failure control: an owned temporary tree that cannot be
     * removed (a read-only owned directory whose staged artifact survives)
     * is detected by the removal attempt, reported with the owned path and
     * the owning cell's context, and recorded through the audit's own fatal
     * verdict channel. The control drives that channel exactly as a real
     * scratch/native cleanup failure would and then withdraws its own
     * deliberate probe before returning, so an unsuccessful cleanup provably
     * cannot leave the registered audit green.
     */
    private static void cleanupFailureControl() throws Exception {
        System.out.println("-- the cleanup-failure control: an owned artifact "
            + "that survives its removal reaches the audit's fatal verdict --");
        Path root = Files.createTempDirectory("production-corpus-audit-cleanup-");
        Path artifact = root.resolve("staged-program.lua");
        Set<PosixFilePermission> originalPermissions = null;
        try {
            Files.writeString(artifact, "print('staged')\n",
                StandardCharsets.UTF_8);
            originalPermissions = Files.getPosixFilePermissions(root);
            Files.setPosixFilePermissions(root,
                PosixFilePermissions.fromString("r-x------"));
            check(!Files.isWritable(root),
                "the control's owned directory is read-only: " + root);

            // The real recording path every scratch/native cleanup uses:
            // the surviving artifact becomes one fatal audit failure naming
            // the owned path and the context.
            int fatalBefore = fatalCount.get();
            int linesBefore = fatalLines.size();
            CleanupResult recorded = deleteRecursively(root,
                "cleanup-control");
            int recordedFatals = fatalCount.get() - fatalBefore;
            String recordedLine = null;
            synchronized (fatalLines) {
                if (fatalLines.size() > linesBefore) {
                    recordedLine = fatalLines.get(linesBefore);
                    while (fatalLines.size() > linesBefore) {
                        fatalLines.remove(fatalLines.size() - 1);
                    }
                }
            }
            // Withdraw the control's own deliberate probe from the verdict
            // before asserting: it is not a corpus failure, and the captured
            // values above prove the wiring.
            fatalCount.set(fatalBefore);

            check(!recorded.cleaned(),
                "the removal attempt detects the surviving owned artifact: "
                    + recorded.description());
            check(recorded.failures().stream().anyMatch(failure ->
                    failure.contains(artifact.toString())),
                "the removal attempt retains the deletion failure with its "
                    + "path: " + recorded.description());
            check(recorded.failures().stream().anyMatch(failure ->
                    failure.contains("survived")),
                "the removal attempt verifies the root and reports the "
                    + "survivor: " + recorded.description());
            check(Files.exists(artifact),
                "the control's staged artifact really survived the removal "
                    + "attempt: " + artifact);
            checkEq(1, recordedFatals,
                "an unsuccessful cleanup is recorded as exactly one fatal "
                    + "audit failure");
            check(recordedLine != null
                    && recordedLine.contains(root.toString())
                    && recordedLine.contains("cleanup-control"),
                "the recorded cleanup failure names the owned path and the "
                    + "context: " + recordedLine);
        } finally {
            if (originalPermissions != null) {
                Files.setPosixFilePermissions(root, originalPermissions);
            }
            // The control's own tree must now be removable; a survivor is a
            // genuine leftover and is recorded fatal like any other.
            deleteRecursively(root, "cleanup-control teardown");
        }
    }

    /** The audit-owned reader threads currently alive. */
    private static long auditReaderThreads() {
        return Thread.getAllStackTraces().keySet().stream()
            .filter(Thread::isAlive)
            .filter(thread -> thread.getName().startsWith("audit-"))
            .count();
    }

    /** True while the process recorded in {@code pidFile} is alive. */
    private static boolean pidAlive(Path pidFile) throws Exception {
        if (!Files.isRegularFile(pidFile)) {
            return false;
        }
        String text = Files.readString(pidFile, StandardCharsets.UTF_8).trim();
        if (text.isEmpty()) {
            return false;
        }
        ProcessHandle handle = ProcessHandle.of(Long.parseLong(text))
            .orElse(null);
        return handle != null && handle.isAlive();
    }

    /**
     * The runtime-error comparison contract (the landed lane rule): the
     * canonical framing is appended to the executed stdout, and the actual
     * stderr and the exact exit code are preserved. A pin-exact execution
     * passes; one extra byte on either stream and the exit codes immediately
     * below and above the pinned one are detected, and every such failure is
     * an unclassified defect (fatal in every mode), never a projection
     * deficit.
     */
    private static void runtimeErrorComparisonControls() throws Exception {
        System.out.println("-- the runtime-error comparison controls: the "
            + "framing is appended, the streams and the status are preserved "
            + "--");
        String corpusPath = "backend-runtime/runtime-errors/int-div-zero-e8005.deal";
        CorpusDiscovery.Fixture fixture = fixtureOf(corpusPath);
        List<Export> exports = exportsOf(fixture.source(), corpusPath);
        Project project = materialize(corpusPath,
            CorpusDiscovery.compilationSet(fixture,
                CorpusDiscovery.discover(CONFORMANCE).corpusByPath(),
                CONFORMANCE), Target.LUAJIT, true);
        try {
            CompileOutcome outcome = compile(project);
            check(outcome.compiled(), "the runtime-error control fixture "
                + "compiles: " + outcome.firstErrorDetail());
            if (!outcome.compiled()) {
                return;
            }
            Execution pristine = execute(project, exports, Target.LUAJIT);
            Capture capture = normalize(project, pristine.capture());
            SidecarExpectations.RuntimeExpectation expectation =
                expectationOf(fixture, Target.LUAJIT);
            SidecarExpectations.RuntimeExpectation.Executed executed =
                (SidecarExpectations.RuntimeExpectation.Executed) expectation;
            check(capture != null && capture.error(),
                "the runtime-error control drive captures the DEAL error");
            checkEq(0, pristine.stdout() == null ? 0 : pristine.stdout().length,
                "the pin-exact runtime-error drive writes no raw stdout");
            checkEq(0, pristine.stderr() == null ? 0 : pristine.stderr().length,
                "the pin-exact runtime-error drive writes no raw stderr");
            Optional<String> exactMismatch = compareCell(corpusPath,
                Target.LUAJIT, capture, pristine, expectation);
            check(exactMismatch.isEmpty(),
                "the pin-exact runtime-error execution passes: "
                    + exactMismatch.orElse(""));
            check(captureVerdict(corpusPath, Target.LUAJIT, capture, pristine,
                    expectation, exactMismatch).pass(),
                "the pin-exact runtime-error cell passes the driver's own "
                    + "verdict function");

            // The pin-exact execution above has empty raw streams; the
            // comparison appends the canonical framing to the executed
            // stdout, so any extra bytes on either stream (before the
            // framing included) and any other process status must be
            // detected and classified as an unclassified defect.
            byte[] extraStdoutBytes = new byte[1];
            extraStdoutBytes[0] = 'x';
            Execution extraStdout = new Execution(executed.exitCode(),
                extraStdoutBytes, pristine.stderr(), capture);
            Optional<String> stdoutMismatch = compareCell(corpusPath,
                Target.LUAJIT, capture, extraStdout, expectation);
            check(stdoutMismatch.isPresent(),
                "one extra stdout byte on a runtime-error drive is detected");
            check(isFatal(captureVerdict(corpusPath, Target.LUAJIT, capture,
                    extraStdout, expectation, stdoutMismatch).clazz(), false)
                    && isFatal(captureVerdict(corpusPath, Target.LUAJIT, capture,
                        extraStdout, expectation, stdoutMismatch).clazz(), true),
                "extra stdout on a runtime-error cell is unclassified and fatal "
                    + "in every mode");

            Execution extraStderr = new Execution(executed.exitCode(),
                pristine.stdout(), new byte[]{'x'}, capture);
            Optional<String> stderrMismatch = compareCell(corpusPath,
                Target.LUAJIT, capture, extraStderr, expectation);
            check(stderrMismatch.isPresent(),
                "one extra stderr byte on a runtime-error drive is detected");
            check(isFatal(captureVerdict(corpusPath, Target.LUAJIT, capture,
                    extraStderr, expectation, stderrMismatch).clazz(), false),
                "extra stderr on a runtime-error cell is unclassified and fatal");

            Execution exitBelow = new Execution(executed.exitCode() - 1,
                pristine.stdout(), pristine.stderr(), capture);
            Optional<String> belowMismatch = compareCell(corpusPath,
                Target.LUAJIT, capture, exitBelow, expectation);
            check(belowMismatch.isPresent(),
                "the exit code immediately below the pinned one is detected");
            check(isFatal(captureVerdict(corpusPath, Target.LUAJIT, capture,
                    exitBelow, expectation, belowMismatch).clazz(), false),
                "an exit code below the pinned one is unclassified and fatal");

            Execution exitAbove = new Execution(executed.exitCode() + 1,
                pristine.stdout(), pristine.stderr(), capture);
            Optional<String> aboveMismatch = compareCell(corpusPath,
                Target.LUAJIT, capture, exitAbove, expectation);
            check(aboveMismatch.isPresent(),
                "the exit code immediately above the pinned one is detected");
            check(isFatal(captureVerdict(corpusPath, Target.LUAJIT, capture,
                    exitAbove, expectation, aboveMismatch).clazz(), false),
                "an exit code above the pinned one is unclassified and fatal");

            // The int-div-zero cell carries no recorded interim deficit, so
            // an unrelated code, or the pinned code with another message or
            // projection, is never permitted on it.
            check(projectionDeficitOf(corpusPath, Target.LUAJIT) == null
                    && behaviorDeficitOf(corpusPath, Target.LUAJIT) == null,
                "the int-div-zero control cell carries no recorded interim "
                    + "deficit");
            Capture unrelated = new Capture("E9999", "unrelated failure",
                null, null, null, null, null, null);
            assertFatalBothModes(captureVerdict(corpusPath, Target.LUAJIT,
                unrelated, pristine, expectation, Optional.of("control")),
                "an unrelated runtime-error code on a cell without a recorded "
                    + "projection deficit");
            Capture changedMessage = new Capture(capture.code(),
                capture.message() + " (changed)", capture.sourceFile(),
                capture.line(), capture.column(), capture.expected(),
                capture.actual(), null);
            assertFatalBothModes(captureVerdict(corpusPath, Target.LUAJIT,
                changedMessage, pristine, expectation, Optional.of("control")),
                "a changed message on a cell without a recorded projection "
                    + "deficit");
        } finally {
            deleteRecursively(project.root(), corpusPath);
        }
    }

    /** Asserts a verdict fails and is fatal in both acceptance modes. */
    private static void assertFatalBothModes(CellVerdict verdict, String what) {
        check(!verdict.pass() && isFatal(verdict.clazz(), false)
                && isFatal(verdict.clazz(), true),
            what + " is fatal in every mode: " + verdict.clazz() + " - "
                + verdict.line());
    }

    /**
     * The sanctioned compile-reject controls: the exact pinned code set with
     * nothing staged passes; no diagnostics, an extra diagnostic in either
     * order, a staged artifact beside the rejection, and a clean compile
     * against a rejection pin are defects, fatal in every mode. This is the
     * control that pins the full-code-set and pre-artifact rules the corpus
     * driver's rejection verdict applies.
     */
    private static void rejectionControls() {
        System.out.println("-- the sanctioned-rejection controls: the full code "
            + "set and the staged-artifact state decide the verdict --");
        SidecarExpectations.RuntimeExpectation.Rejected rejected =
            new SidecarExpectations.RuntimeExpectation.Rejected("compile-reject",
                "E6006", OptionalInt.empty(), OptionalInt.empty());
        List<CompilerDiagnostic> pinned = List.of(controlDiagnostic(
            deal.diagnostics.DiagnosticCode.E6006, "the pinned rejection"));
        List<CompilerDiagnostic> extraAfter = List.of(
            controlDiagnostic(deal.diagnostics.DiagnosticCode.E6006,
                "the pinned rejection"),
            controlDiagnostic(deal.diagnostics.DiagnosticCode.E2003,
                "an additional declaration error"));
        List<CompilerDiagnostic> extraBefore = List.of(
            controlDiagnostic(deal.diagnostics.DiagnosticCode.E2003,
                "an additional declaration error"),
            controlDiagnostic(deal.diagnostics.DiagnosticCode.E6006,
                "the pinned rejection"));

        CellVerdict pass = rejectionVerdict("sample", Target.JVM, false, pinned,
            rejected, List.of());
        check(pass.pass(), "the exact pinned rejection set with nothing staged "
            + "passes");
        CellVerdict none = rejectionVerdict("sample", Target.JVM, false,
            List.of(), rejected, List.of());
        check(!none.pass(), "a rejection without diagnostics is a defect");
        CellVerdict after = rejectionVerdict("sample", Target.JVM, false,
            extraAfter, rejected, List.of());
        CellVerdict before = rejectionVerdict("sample", Target.JVM, false,
            extraBefore, rejected, List.of());
        check(!after.pass() && !before.pass(),
            "an additional diagnostic in either order is a defect");
        CellVerdict staged = rejectionVerdict("sample", Target.JVM, false, pinned,
            rejected, List.of("Sample.java"));
        check(!staged.pass(),
            "a rejection accompanied by a staged artifact is a defect");
        CellVerdict compiled = rejectionVerdict("sample", Target.JVM, true,
            List.of(), rejected, List.of());
        check(!compiled.pass(),
            "a clean compile against a rejection pin is a defect");
        for (CellVerdict verdict : List.of(none, after, before, staged, compiled)) {
            check(!verdict.pass() && isFatal(verdict.clazz(), false)
                    && isFatal(verdict.clazz(), true),
                "the rejection control is fatal in every mode: "
                    + verdict.clazz() + " - " + verdict.line());
        }

        // The rejection contract is checked before the interim construct
        // classification: a construct-guard diagnostic where the pinned
        // rejection is required is a rejection mismatch, never a construct
        // deficit — with or without staged output beside it. These controls
        // go through the same compileVerdict the corpus driver applies.
        List<CompilerDiagnostic> constructGuard = List.of(guardDiagnostic(
            SemanticLowerer.CONSTRUCT_UNLOWERED));
        CellVerdict constructRejected = compileVerdict("sample", Target.JVM,
            new CompileOutcome(false, true, constructGuard, constructGuard,
                "control"),
            rejected, List.of());
        check(!constructRejected.pass()
                && constructRejected.clazz() == LegClass.DEFECT,
            "a construct guard instead of the pinned rejection is a "
                + "rejection mismatch, never a construct deficit: "
                + constructRejected.line());
        assertFatalBothModes(constructRejected,
            "a construct guard instead of the pinned rejection");
        assertFatalBothModes(compileVerdict("sample", Target.JVM,
            new CompileOutcome(false, true, constructGuard, constructGuard,
                "control"),
            rejected, List.of("Unexpected.java")),
            "a construct guard beside a staged artifact under a rejection "
                + "pin");

        // The guard-only leg stays the reported construct deficit when it
        // staged nothing, but published output beside it is fatal in every
        // mode whatever the interim classification.
        SidecarExpectations.RuntimeExpectation.Executed executed =
            new SidecarExpectations.RuntimeExpectation.Executed("runtime-ok",
                new byte[0], new byte[0], 0, null);
        CellVerdict constructExecuted = compileVerdict("sample", Target.JVM,
            new CompileOutcome(false, true, constructGuard, constructGuard,
                "control"),
            executed, List.of());
        check(!constructExecuted.pass()
                && constructExecuted.clazz() == LegClass.CONSTRUCT_DEFICIT,
            "a guard-only failed compile without staged output stays the "
                + "reported construct deficit: "
                + constructExecuted.line());
        assertFatalBothModes(compileVerdict("sample", Target.JVM,
            new CompileOutcome(false, true, constructGuard, constructGuard,
                "control"),
            executed, List.of("Unexpected.java")),
            "a failed construct-guard compile that published output");
    }

    /**
     * The recorded-projection-inventory controls: the recorded evidence —
     * the inventory entry's capture tuple, both raw streams empty and the
     * pinned exit code — classifies as the projection deficit, exercised
     * with synthetic evidence so a sibling origin realization that makes
     * the real cell sidecar-exact leaves this control green instead of
     * requiring the defect to survive. The real arrays/index-oob cell is
     * driven as well: while it diverges its observed evidence must equal
     * the recorded entry and classify as the projection deficit; once it
     * is sidecar-exact that ratchet target is reported, not forbidden.
     * Every divergence — an unrelated code, a changed message, another
     * position, another value-kind token, extra output on either stream, and
     * the exit codes immediately below and above the pinned one — is an
     * unclassified failure, fatal in both modes.
     */
    private static void projectionInventoryControls() throws Exception {
        System.out.println("-- the recorded-projection controls: only the "
            + "recorded capture of a recorded cell is permitted --");
        String corpusPath = "backend-runtime/arrays/index-oob.deal";
        CorpusDiscovery.Fixture fixture = fixtureOf(corpusPath);
        SidecarExpectations.RuntimeExpectation expectation =
            expectationOf(fixture, Target.LUAJIT);
        SidecarExpectations.RuntimeExpectation.Executed executed =
            (SidecarExpectations.RuntimeExpectation.Executed) expectation;
        ProjectionDeficit recorded = projectionDeficitOf(corpusPath,
            Target.LUAJIT);
        check(recorded != null, "the arrays/index-oob cell carries its "
            + "recorded projection deficit");

        // The historical-deficit branch with synthetic recorded evidence:
        // the exact recorded capture tuple with empty streams and the pinned
        // exit code classifies as the projection deficit. This reads no
        // production artifact, so a sibling origin correction cannot fail
        // it.
        if (recorded != null) {
            Capture synthetic = new Capture(recorded.capturedCode(),
                recorded.capturedMessage(), recorded.capturedFile(),
                recorded.capturedLine(), recorded.capturedColumn(),
                recorded.capturedExpected(), recorded.capturedActual(), null);
            Execution syntheticExecution = new Execution(executed.exitCode(),
                new byte[0], new byte[0], synthetic);
            check(matchesProjectionDeficit(recorded, synthetic,
                    syntheticExecution, executed),
                "the recorded entry matches its own synthetic evidence: "
                    + captureDetail(synthetic, executed.error()));
            CellVerdict syntheticVerdict = captureVerdict(corpusPath,
                Target.LUAJIT, synthetic, syntheticExecution, expectation,
                Optional.of("control"));
            check(syntheticVerdict.clazz() == LegClass.PROJECTION_DEFICIT,
                "the recorded evidence classifies as the projection deficit: "
                    + syntheticVerdict.line());
            check(!isFatal(syntheticVerdict.clazz(), false)
                    && isFatal(syntheticVerdict.clazz(), true),
                "the recorded projection deficit is reported while the "
                    + "compile criterion is unmet and fatal once it is met");
        }

        List<Export> exports = exportsOf(fixture.source(), corpusPath);
        Project project = materialize(corpusPath,
            CorpusDiscovery.compilationSet(fixture,
                CorpusDiscovery.discover(CONFORMANCE).corpusByPath(),
                CONFORMANCE), Target.LUAJIT, true);
        try {
            CompileOutcome outcome = compile(project);
            check(outcome.compiled(), "the projection control fixture "
                + "compiles: " + outcome.firstErrorDetail());
            if (!outcome.compiled()) {
                return;
            }
            Execution pristine = execute(project, exports, Target.LUAJIT);
            Capture capture = normalize(project, pristine.capture());
            Optional<String> mismatch = compareCell(corpusPath, Target.LUAJIT,
                capture, pristine, expectation);
            if (mismatch.isPresent()) {
                // The sibling projection realization has not landed for this
                // cell yet: the observed evidence must be exactly the
                // recorded deficit, never some other mismatch.
                check(matchesProjectionDeficit(recorded, capture, pristine,
                        executed),
                    "the recorded projection entry equals the observed "
                        + "evidence: "
                        + captureDetail(capture, executed.error()));
                CellVerdict recordedVerdict = captureVerdict(corpusPath,
                    Target.LUAJIT, capture, pristine, expectation, mismatch);
                check(recordedVerdict.clazz() == LegClass.PROJECTION_DEFICIT,
                    "the observed evidence classifies as the projection "
                        + "deficit: " + recordedVerdict.line());
                check(!isFatal(recordedVerdict.clazz(), false)
                        && isFatal(recordedVerdict.clazz(), true),
                    "the recorded projection deficit is reported while the "
                        + "compile criterion is unmet and fatal once it is "
                        + "met");
            } else {
                // The sibling projection realization landed: the capture is
                // sidecar-exact, the ratchet target. The control must not
                // require the historical defect to survive.
                System.out.println("   arrays/index-oob is sidecar-exact on "
                    + "LuaJIT: the recorded projection deficit is realized; "
                    + "the predicted ratchet target holds");
            }

            assertFatalBothModes(captureVerdict(corpusPath, Target.LUAJIT,
                new Capture("E9999", "unrelated failure", null, null, null,
                    null, null, null), pristine, expectation,
                Optional.of("control")),
                "an unrelated runtime-error code on a recorded cell");
            assertFatalBothModes(captureVerdict(corpusPath, Target.LUAJIT,
                new Capture(capture.code(), capture.message() + " (changed)",
                    capture.sourceFile(), capture.line(), capture.column(),
                    capture.expected(), capture.actual(), null),
                pristine, expectation, Optional.of("control")),
                "the recorded code with a changed message");
            assertFatalBothModes(captureVerdict(corpusPath, Target.LUAJIT,
                new Capture(capture.code(), capture.message(),
                    capture.sourceFile(), capture.line(), capture.column() + 1,
                    capture.expected(), capture.actual(), null),
                pristine, expectation, Optional.of("control")),
                "the recorded capture at another position");
            assertFatalBothModes(captureVerdict(corpusPath, Target.LUAJIT,
                new Capture(capture.code(), capture.message(),
                    capture.sourceFile(), capture.line(), capture.column(),
                    capture.expected(), "table", null),
                pristine, expectation, Optional.of("control")),
                "the recorded capture with another value-kind token");
            assertFatalBothModes(captureVerdict(corpusPath, Target.LUAJIT,
                capture, new Execution(pristine.exitCode(), new byte[]{'x'},
                    pristine.stderr(), capture), expectation,
                Optional.of("control")),
                "extra stdout on a recorded projection cell");
            assertFatalBothModes(captureVerdict(corpusPath, Target.LUAJIT,
                capture, new Execution(pristine.exitCode(), pristine.stdout(),
                    new byte[]{'x'}, capture), expectation,
                Optional.of("control")),
                "extra stderr on a recorded projection cell");
            assertFatalBothModes(captureVerdict(corpusPath, Target.LUAJIT,
                capture, new Execution(executed.exitCode() - 1,
                    pristine.stdout(), pristine.stderr(), capture),
                expectation, Optional.of("control")),
                "the exit code immediately below the pinned one on a recorded "
                    + "projection cell");
            assertFatalBothModes(captureVerdict(corpusPath, Target.LUAJIT,
                capture, new Execution(executed.exitCode() + 1,
                    pristine.stdout(), pristine.stderr(), capture),
                expectation, Optional.of("control")),
                "the exit code immediately above the pinned one on a recorded "
                    + "projection cell");
        } finally {
            deleteRecursively(project.root(), corpusPath);
        }
    }

    /**
     * The recorded-behavior-inventory controls: the recorded cell's code,
     * message, streams and probe status are permitted, while a different
     * code, a changed message, extra output on either stream, another
     * status, and a cell outside the inventory are unclassified failures,
     * fatal in both modes.
     */
    private static void unclassifiedBehaviorControls() throws Exception {
        System.out.println("-- the recorded-behavior controls: only the "
            + "recorded evidence of a recorded cell is permitted --");
        String corpusPath =
            "backend-runtime/control-flow/for-of-string-unicode.deal";
        CorpusDiscovery.Fixture fixture = fixtureOf(corpusPath);
        SidecarExpectations.RuntimeExpectation.Executed expectation =
            (SidecarExpectations.RuntimeExpectation.Executed) expectationOf(
                fixture, Target.LUAJIT);
        BehaviorDeficit recorded = behaviorDeficitOf(corpusPath, Target.LUAJIT);
        check(recorded != null, "the for-of-string-unicode LuaJIT cell carries "
            + "its recorded behavior deficit");
        Execution execution = new Execution(recorded.probeExitCode(),
            new byte[0], new byte[0], null);
        Capture pinned = new Capture(recorded.capturedCode(),
            recorded.capturedMessage(), null, null, null, null, null, null);
        Optional<String> mismatch = Optional.of("control mismatch");
        CellVerdict permitted = captureVerdict(corpusPath, Target.LUAJIT,
            pinned, execution, expectation, mismatch);
        check(permitted.clazz() == LegClass.BEHAVIOR_DEFICIT,
            "the recorded behavior evidence is permitted: " + permitted.line());
        check(!isFatal(permitted.clazz(), false),
            "the recorded behavior deficit is non-fatal while the compile "
                + "criterion is unmet");
        assertFatalBothModes(captureVerdict(corpusPath, Target.LUAJIT,
            new Capture("E8005", "a different failure", null, null, null, null,
                null, null), execution, expectation, mismatch),
            "a different captured code on a recorded behavior cell");
        assertFatalBothModes(captureVerdict(corpusPath, Target.LUAJIT,
            new Capture(recorded.capturedCode(), "unrelated failure", null,
                null, null, null, null, null), execution, expectation,
            mismatch),
            "the recorded code with a changed message");
        assertFatalBothModes(captureVerdict(corpusPath, Target.LUAJIT, pinned,
            new Execution(recorded.probeExitCode(), new byte[]{'x'},
                new byte[0], null), expectation, mismatch),
            "extra stdout on the recorded behavior cell");
        assertFatalBothModes(captureVerdict(corpusPath, Target.LUAJIT, pinned,
            new Execution(recorded.probeExitCode(), new byte[0],
                new byte[]{'x'}, null), expectation, mismatch),
            "extra stderr on the recorded behavior cell");
        assertFatalBothModes(captureVerdict(corpusPath, Target.LUAJIT, pinned,
            new Execution(7, new byte[0], new byte[0], null), expectation,
            mismatch),
            "another status on the recorded behavior cell");
        assertFatalBothModes(captureVerdict(
            "backend-runtime/control-flow/for-of-break-continue.deal",
            Target.LUAJIT, pinned, execution, expectation, mismatch),
            "a runtime-ok failure outside the recorded inventory");
    }

    /** A synthetic control diagnostic for the rejection-verdict controls. */
    private static CompilerDiagnostic controlDiagnostic(
            deal.diagnostics.DiagnosticCode code, String message) {
        return CompilerDiagnostic.error(code, message,
            deal.diagnostics.DiagnosticRange.synthetic("sample.deal"));
    }

    /**
     * A canonical E6005 producer-guard diagnostic for the control surfaces
     * (the single-sourced message shape the corpus diagnostics carry).
     */
    private static CompilerDiagnostic guardDiagnostic(String rule) {
        return FailureContractRegistry.e6005(new LoweringFailureDetail(
            "guard-control", SemanticCapability.CONTAINERS_AND_STRINGS, rule,
            SemanticProfile.DEAL_V1_2_INT32, "deal.semantic-ir/1",
            "SemanticLowerer " + rule + " (control)"));
    }

    private static CorpusDiscovery.Fixture fixtureOf(String corpusPath)
            throws Exception {
        CorpusDiscovery.DiscoveryResult discovery = CorpusDiscovery.discover(CONFORMANCE);
        for (CorpusDiscovery.Fixture fixture : discovery.fixtures()) {
            if (fixture.corpusPath().equals(corpusPath)) {
                return fixture;
            }
        }
        throw new IllegalStateException("the corpus carries no fixture "
            + corpusPath);
    }

    // =========================================================================
    // 5. The materialization fidelity
    // =========================================================================

    private static void testMaterializationFidelity() throws Exception {
        System.out.println("-- the materialization fidelity: the complete "
            + "compilation set with the lanes' layout and bytes, the "
            + "declaration wiring, the compile outcome and the production "
            + "artifact set (no declaration exemption; an omitted declaration "
            + "still reproduces the frontend E2003 gap) --");
        String[] samples = {
            "backend-runtime/host-abi/host-array-return-ok",
            "backend-runtime/ffi/012-ffi-double-roundtrip",
            "backend-runtime/modules/modid-class-identity",
            "backend-runtime/async-await/async-cross-module",
        };
        for (String fixtureRel : samples) {
            CorpusDiscovery.Fixture fixture = fixtureOf(fixtureRel + ".deal");
            List<SidecarSchemaValidator.CompilationModule> compilationSet =
                CorpusDiscovery.compilationSet(fixture,
                    CorpusDiscovery.discover(CONFORMANCE).corpusByPath(),
                    CONFORMANCE);
            Project wired = materialize(fixture.corpusPath(), compilationSet,
                Target.LUAJIT, true);
            try {
                // (a) The materialization equals the lanes': exactly the
                // validated compilation set (the declaration modules
                // included, never exempt), each at the lane's moduleTarget
                // path with byte-identical validated stripped bytes.
                Set<String> materialized = new LinkedHashSet<>(wired.modules()
                    .values().stream().map(Materialized::corpusPath).toList());
                checkEq(new LinkedHashSet<>(compilationSet.stream()
                        .map(SidecarSchemaValidator.CompilationModule::corpusPath)
                        .toList()), materialized,
                    fixtureRel + ": the materialized module set is exactly "
                        + "the validated compilation set (declaration modules "
                        + "included)");
                for (SidecarSchemaValidator.CompilationModule module
                        : compilationSet) {
                    Path expected = expectedModuleTarget(fixture,
                        module.corpusPath(), wired.srcRoot());
                    check(Files.isRegularFile(expected),
                        fixtureRel + ": the compilation-set module "
                            + module.corpusPath() + " sits at the lane's path "
                            + wired.srcRoot().relativize(expected));
                    if (Files.isRegularFile(expected)) {
                        checkEq(module.source(), Files.readString(expected),
                            fixtureRel + ": the materialized bytes of "
                                + module.corpusPath()
                                + " are the validated stripped source");
                    }
                }
                if (fixtureRel.contains("async-cross-module")) {
                    check(Files.isRegularFile(wired.srcRoot()
                            .resolve("async_lib.deal.deal")),
                        fixtureRel + ": the explicit-.deal alias copy exists at "
                            + "src/async_lib.deal.deal");
                }
                if (fixtureRel.contains("modid-class-identity")) {
                    check(Files.isRegularFile(wired.srcRoot()
                            .resolve("modid_lib_a/lib_a.deal")),
                        fixtureRel + ": the nested companion keeps its layout");
                }
                if (fixtureRel.contains("ffi/012")) {
                    check(Files.isRegularFile(wired.root().resolve("bindings")
                            .resolve("ffi_candidate_native.d.deal")),
                        fixtureRel + ": the corpus FFI declaration is wired "
                            + "under bindings/");
                    // The externals binding is the audit-owned native path,
                    // never the repository build directory's library.
                    String manifest = Files.readString(
                        wired.root().resolve("deal.json"));
                    check(manifest.contains(auditNativeDirectory
                            .toAbsolutePath().normalize().toString()),
                        fixtureRel + ": the nativeLibrary wiring binds the "
                            + "audit-owned native directory");
                }
                for (String declaration : wired.rawToDeclaration().values()) {
                    check(Files.isRegularFile(wired.root().resolve(declaration)),
                        fixtureRel + ": the wired declaration exists: "
                            + declaration);
                }
                // (b) The compile outcome and the production artifact set on
                // both production targets: the wired materialization
                // reproduces the sidecar's mode (compiled, or the sanctioned
                // pinned rejection with nothing staged), and the production
                // arm stages exactly the entry artifact (no per-module
                // sibling).
                for (Target target : Target.values()) {
                    Project compiled = materialize(fixture.corpusPath(),
                        compilationSet, target, true);
                    try {
                        CompileOutcome outcome = compile(compiled);
                        SidecarExpectations.RuntimeExpectation expectation =
                            expectationOf(fixture, target);
                        if (expectation
                                instanceof SidecarExpectations.RuntimeExpectation.Rejected
                                    rejected) {
                            CellVerdict verdict = rejectionVerdict(
                                fixture.corpusPath(), target, outcome.compiled(),
                                outcome.errors(), rejected,
                                stagedOutputFiles(compiled));
                            check(verdict.pass(), fixtureRel + " ["
                                + target.laneName() + "]: the sample "
                                + "reproduces the pinned rejection: "
                                + verdict.line());
                        } else {
                            check(outcome.compiled(), fixtureRel + " ["
                                + target.laneName() + "]: the wired "
                                + "materialization compiles: "
                                + outcome.firstErrorDetail());
                            String entryArtifact = target == Target.JVM
                                ? JvmBackend.classNameFor(
                                    compiled.modulePath()) + ".java"
                                : compiled.modulePath() + ".lua";
                            checkEq(List.of(entryArtifact),
                                stagedModuleArtifacts(compiled),
                                fixtureRel + " [" + target.laneName()
                                    + "]: the production arm stages exactly "
                                    + "the entry artifact (no per-module "
                                    + "sibling)");
                        }
                    } finally {
                        deleteRecursively(compiled.root(), fixtureRel + " ["
                            + target.laneName() + "]");
                    }
                }
            } finally {
                deleteRecursively(wired.root(), fixtureRel);
            }

            // (c) The load-bearing probe: omit the declaration wiring (host/
            // FFI) or the companion copy (relative import) and observe the
            // frontend E2003 gap the lanes' materialization prevents.
            Project unwired = materialize(fixture.corpusPath(), compilationSet,
                Target.LUAJIT,
                !(fixtureRel.contains("host-abi") || fixtureRel.contains("ffi/012")));
            try {
                if (fixtureRel.contains("modid-class-identity")) {
                    Files.deleteIfExists(unwired.srcRoot()
                        .resolve("modid_lib_a/lib_a.deal"));
                }
                if (fixtureRel.contains("async-cross-module")) {
                    Files.deleteIfExists(unwired.srcRoot()
                        .resolve("async_lib.deal.deal"));
                }
                CompileOutcome outcome = compile(unwired);
                check(!outcome.compiled(),
                    fixtureRel + ": the omitted declaration must not compile "
                        + "(the wiring is load-bearing)");
                boolean e2003 = outcome.errors().stream()
                    .anyMatch(d -> "E2003".equals(d.code()));
                check(e2003, fixtureRel + ": the omitted declaration "
                    + "reproduces the frontend E2003 gap: "
                    + outcome.firstErrorDetail());
            } finally {
                deleteRecursively(unwired.root(),
                    fixtureRel + " (non-materializing probe)");
            }
        }
    }

    /** The lane's moduleTarget path of one compilation-set module. */
    private static Path expectedModuleTarget(CorpusDiscovery.Fixture fixture,
            String corpusPath, Path srcRoot) {
        Path entryCorpus = CONFORMANCE.resolve(fixture.corpusPath())
            .toAbsolutePath().normalize();
        Path corpusFile = CONFORMANCE.resolve(corpusPath)
            .toAbsolutePath().normalize();
        return moduleTarget(corpusFile, entryCorpus.getParent(), srcRoot);
    }

    // =========================================================================
    // Utilities
    // =========================================================================

    /**
     * The outcome of one owned-tree removal attempt: the root and every
     * failure the attempt retained. {@link #cleaned()} is true iff the root
     * was removed and no deletion or traversal failure was recorded.
     */
    record CleanupResult(Path root, List<String> failures) {

        boolean cleaned() {
            return failures.isEmpty();
        }

        String description() {
            return failures.isEmpty() ? root + ": removed"
                : root + ": " + String.join("; ", failures);
        }
    }

    /**
     * Removes an audit-owned temporary tree and reports everything the
     * attempt could not remove. Every path is attempted (one deletion
     * failure never stops the sweep), each deletion and traversal failure is
     * retained with its path and cause, and the root is re-checked after the
     * sweep, so a surviving owned artifact is always surfaced to the caller
     * instead of being silently accepted.
     */
    static CleanupResult removeOwnedTree(Path dir) {
        if (dir == null) {
            return new CleanupResult(null, List.of());
        }
        if (!Files.exists(dir)) {
            return new CleanupResult(dir, List.of());
        }
        List<String> failures = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception e) {
                    failures.add("cannot delete " + path + " (" + e + ")");
                }
            }
        } catch (Exception e) {
            failures.add("cannot traverse " + dir + " (" + e + ")");
        }
        if (Files.exists(dir)) {
            failures.add("the owned root survived the sweep");
        }
        return new CleanupResult(dir, List.copyOf(failures));
    }

    /**
     * The fail-closed cleanup verdict: the audit-owned tree could not be
     * removed, so the post-state rule (all temp projects removed, no
     * repository artifact, read-only) is violated. The message names the
     * context (the fixture/target cell or the audit-owned artifact) and the
     * owned paths that remain; a clean removal yields {@code null}.
     */
    static String cleanupFailureLine(String context, CleanupResult result) {
        if (result.cleaned()) {
            return null;
        }
        return "[" + context + "] the audit-owned temporary artifact was not "
            + "removed: " + result.description();
    }

    /**
     * Removes an audit-owned temporary project or scratch/native directory
     * and records a fatal audit failure when anything survives: cleanup is
     * fail-closed, so a leftover staged program or native library can never
     * leave the registered audit green.
     */
    static CleanupResult deleteRecursively(Path dir) {
        return deleteRecursively(dir, "audit-owned temporary artifact");
    }

    /**
     * The context-naming form used by the per-fixture/target and per-control
     * call sites, so a surviving artifact is reported with the cell it came
     * from.
     */
    static CleanupResult deleteRecursively(Path dir, String context) {
        CleanupResult result = removeOwnedTree(dir);
        String failure = cleanupFailureLine(context, result);
        if (failure != null) {
            fatal(failure);
        }
        return result;
    }

    // =========================================================================
    // The drive entry point (invoked by the retained acceptance suite)
    // =========================================================================

    /** The accounting key of one fixture and target cell. */
    private static String cellKey(String corpusPath, Target target) {
        return corpusPath + " [" + target.laneName() + "]";
    }

    /**
     * Runs the corpus audit and returns its fatal-failure count; the
     * retained acceptance suite folds the count into its own verdict.
     */
    static int run() throws Exception {
        System.out.println("=== Production-Corpus Acceptance Audit "
            + "(ISSUE-0703) ===");
        // The audit's own bounded measurement pool: the retained suite runs
        // under the gate with `--jobs 1`, and the whole dispatch measured
        // serially would exceed the harness's command budget, so the audit
        // keeps a bounded internal pool (default min(8, processors); the
        // `deal.audit.jobs` property overrides it) and reports it. The
        // pool only drives the audit's own compiles and toolchain
        // subprocesses; the harness's suite-level scheduling is unchanged.
        int auditJobs = Integer.getInteger("deal.audit.jobs",
            Math.max(1, Math.min(8,
                Runtime.getRuntime().availableProcessors())));
        System.out.println("Audit worker bound: " + auditJobs
            + " (deal.audit.jobs="
            + System.getProperty("deal.audit.jobs", "<unset>") + ")");

        CorpusDiscovery.DiscoveryResult discovery =
            CorpusDiscovery.discover(CONFORMANCE);
        testCorpusInvariants(discovery);
        List<CorpusDiscovery.Fixture> dispatched = dispatchedOf(discovery);

        // The audit-owned native directory (the corpus C library is
        // bootstrapped there, never in the repository build/ directory) and
        // the repository build-directory snapshot: the audit asserts its own
        // read-only post-state against the snapshot, so a write into the
        // shared build products is detected even where git status cannot see
        // it.
        auditNativeDirectory =
            Files.createTempDirectory("production-corpus-audit-native-");
        // No audit worker exists yet, so the shared audit-owned artifacts are
        // removable until the dispatched-corpus pool starts (and again once
        // the owned-pool teardown confirms its termination).
        auditPoolStopped = true;
        Map<String, String> buildBefore = snapshotBuildDirectory();
        System.out.println("Repository build snapshot: " + buildBefore.size()
            + " file(s) before the run"
            + (buildBefore.containsKey(CorpusFfi.NATIVE_LIBRARY_NAME)
                ? " (including " + CorpusFfi.NATIVE_LIBRARY_NAME + ")" : ""));

        List<String> report = Collections.synchronizedList(new ArrayList<>());
        List<String> declarationImports =
            Collections.synchronizedList(new ArrayList<>());
        try {
            System.out.println("-- the compile and capture criteria: every "
                + "dispatched fixture compiles on both production targets "
                + "with zero producer guards, and every producible fixture's "
                + "capture reproduces its sidecar through the landed "
                + "comparison --");
            List<Runnable> cells = new ArrayList<>();
            for (CorpusDiscovery.Fixture fixture : dispatched) {
                cells.add(() -> {
                    try {
                        testFixture(fixture, report, declarationImports,
                            discovery.corpusByPath());
                    } catch (InterruptedException e) {
                        // The audit was cancelled: the worker stops the cell
                        // (it never continues to its next target) and leaves
                        // the interruption for the owned-pool teardown.
                        Thread.currentThread().interrupt();
                    } catch (Exception e) {
                        if (Thread.currentThread().isInterrupted()) {
                            // The cancellation aborted this fixture's work;
                            // the escape is not a corpus defect.
                            return;
                        }
                        // An audit-input defect (an unresolvable
                        // declaration wiring, a missing sidecar, a
                        // missing host implementation): fatal, never a
                        // construct gap.
                        fatal(fixture.corpusPath() + ": audit input: " + e);
                    }
                });
            }
            runOwnedPool(auditJobs, cells);
            System.out.println("  declaration-importing group measured: "
                + declarationImports.size() + " fixtures");

            testNegativeControl();
            testMaterializationFidelity();
        } finally {
            if (auditPoolStopped) {
                deleteRecursively(auditNativeDirectory,
                    "audit-owned native directory");
            } else {
                // The owned worker pool did not confirm termination: its
                // workers may still run owned subprocesses and bootstrap the
                // native library, so the shared audit-owned directory is
                // retained rather than deleted under them.
                System.err.println("the audit-owned native directory is "
                    + "retained for the surviving audit workers: "
                    + auditNativeDirectory);
            }
        }

        checkEq(buildBefore, snapshotBuildDirectory(),
            "the audit leaves the repository build directory unchanged "
                + "(no native-library bootstrap or any other write into the "
                + "shared build products)");

        List<String> ordered = new ArrayList<>(report);
        ordered.sort(Comparator.naturalOrder());
        System.out.println();
        System.out.println("-- per-fixture, per-target verdicts --");
        for (String line : ordered) {
            System.out.println("  " + line);
        }
        // One compile outcome per dispatched fixture and production target,
        // derived from the complete current validated discovery (never a
        // fixed count lock): an authorized corpus addition or removal moves
        // the accounting with the discovery. Each producible, non-rejected
        // cell additionally carries exactly one capture outcome.
        Map<String, Set<Target>> measured = new LinkedHashMap<>();
        long compileLegCount = 0;
        long captureLegCount = 0;
        for (Leg leg : legs) {
            if (leg.kind() == LegKind.COMPILE) {
                compileLegCount++;
                measured.computeIfAbsent(leg.corpusPath(),
                    key -> java.util.EnumSet.noneOf(Target.class))
                    .add(leg.target());
            } else {
                captureLegCount++;
            }
        }
        checkEq(dispatched.size(), measured.size(),
            "every fixture of the current validated discovery carries its "
                + "compile outcome (the accounting is the discovered set)");
        for (CorpusDiscovery.Fixture fixture : dispatched) {
            checkEq(java.util.EnumSet.allOf(Target.class),
                measured.getOrDefault(fixture.corpusPath(),
                    java.util.EnumSet.noneOf(Target.class)),
                fixture.corpusPath() + ": one compile outcome per production "
                    + "target");
        }
        checkEq((long) dispatched.size() * Target.values().length,
            compileLegCount,
            "one compile outcome per dispatched fixture and production target");
        for (Leg leg : legs) {
            check(leg.kind() == LegKind.COMPILE
                    || measured.containsKey(leg.corpusPath()),
                "the capture outcome belongs to a dispatched fixture: "
                    + leg.corpusPath());
        }
        checkEq((long) captureLegs.get(), captureLegCount,
            "the capture outcomes are the driven producible cells");
        // Every producible, non-rejected cell carries exactly one capture
        // outcome, and a sanctioned rejection carries none: the expected
        // accounting is the current validated discovery and its sidecars.
        Map<String, Integer> capturesPerCell = new LinkedHashMap<>();
        Set<String> passingCompileCells = new LinkedHashSet<>();
        for (Leg leg : legs) {
            String key = cellKey(leg.corpusPath(), leg.target());
            if (leg.kind() == LegKind.CAPTURE) {
                capturesPerCell.merge(key, 1, Integer::sum);
            } else if (leg.clazz() == LegClass.PASS) {
                passingCompileCells.add(key);
            }
        }
        for (CorpusDiscovery.Fixture fixture : dispatched) {
            for (Target target : Target.values()) {
                String key = cellKey(fixture.corpusPath(), target);
                if (!passingCompileCells.contains(key)) {
                    continue;
                }
                if (expectationOf(fixture, target)
                        instanceof SidecarExpectations.RuntimeExpectation.Rejected) {
                    checkEq(0, capturesPerCell.getOrDefault(key, 0),
                        key + ": a sanctioned rejection carries no capture "
                            + "outcome");
                    continue;
                }
                checkEq(1, capturesPerCell.getOrDefault(key, 0),
                    key + ": exactly one capture outcome for the producible "
                        + "cell");
            }
        }
        System.out.println();
        System.out.println("dispatched fixtures: " + dispatched.size()
            + " (the complete current validated discovery)");
        System.out.println("compile legs: " + compileLegs.get()
            + " pass / " + (dispatched.size() * Target.values().length
                - compileLegs.get() - rejectLegs.get()) + " fail, "
            + "compile-reject legs: " + rejectLegs.get()
            + " (the pinned extern-C E6006)");
        System.out.println("capture legs: " + captureLegs.get()
            + " driven on the producible set");
        for (Target target : Target.values()) {
            long targetPass = legs.stream().filter(leg ->
                leg.target() == target && leg.clazz() == LegClass.PASS).count();
            long targetFail = legs.stream().filter(leg ->
                leg.target() == target && leg.clazz() != LegClass.PASS).count();
            System.out.println("  " + target.laneName() + ": " + targetPass
                + " pass / " + targetFail + " fail");
        }

        // The acceptance verdicts and the pre-precondition rule (HR4(a), A5,
        // CD5): while the dispatched set still carries a checker-valid
        // CONSTRUCT_UNLOWERED deficit the corpus run reports the residual
        // failures per fixture and never skips; once the compile criterion is
        // met the strict acceptance gate engages and every non-pin-exact
        // capture leg is fatal.
        boolean acceptanceMode = legs.stream().noneMatch(leg ->
            leg.clazz() == LegClass.CONSTRUCT_DEFICIT);
        long constructDeficit = legs.stream().filter(leg ->
            leg.clazz() == LegClass.CONSTRUCT_DEFICIT).count();
        long architecturalDeficit = legs.stream().filter(leg ->
            leg.clazz() == LegClass.ARCHITECTURAL_GUARD).count();
        long projectionDeficit = legs.stream().filter(leg ->
            leg.clazz() == LegClass.PROJECTION_DEFICIT).count();
        long behaviorDeficit = legs.stream().filter(leg ->
            leg.clazz() == LegClass.BEHAVIOR_DEFICIT).count();
        long infrastructure = legs.stream().filter(leg ->
            leg.clazz() == LegClass.INFRASTRUCTURE).count();
        long defects = legs.stream().filter(leg ->
            leg.clazz() == LegClass.DEFECT).count();
        List<String> deficits = new ArrayList<>();
        for (Leg leg : legs) {
            if (leg.clazz() == LegClass.PASS) {
                continue;
            }
            if (isFatal(leg.clazz(), acceptanceMode)) {
                fatal("[" + leg.clazz() + "] " + leg.line());
            } else {
                deficits.add("[" + leg.clazz() + "] " + leg.line());
            }
        }
        System.out.println();
        System.out.println("-- the acceptance verdicts --");
        System.out.println("zero CONSTRUCT_UNLOWERED over the dispatched set: "
            + (constructDeficit == 0 ? "PASS"
                : "FAIL (" + constructDeficit + " legs)"));
        System.out.println("zero RETAINED_ABI_DEFERRED / SHARED_EMITTER_COVERAGE: "
            + (architecturalDeficit == 0 ? "PASS"
                : "FAIL (" + architecturalDeficit + " legs)"));
        System.out.println("byte-exact captures on the producible set: "
            + (projectionDeficit + behaviorDeficit == 0 ? "PASS"
                : "FAIL (" + projectionDeficit + " projection + "
                    + behaviorDeficit + " pinned-behavior legs)"));
        System.out.println("infrastructure failures (drive/load/toolchain): "
            + (infrastructure == 0 ? "none" : infrastructure + " legs"));
        System.out.println("non-guard compile failures, pinned-rejection "
            + "mismatches, staged rejections and unclassified capture "
            + "failures: " + (defects == 0 ? "none" : defects + " legs"));
        System.out.println("strict acceptance mode: " + (acceptanceMode
            ? "ENGAGED (every non-pin-exact capture leg is fatal)"
            : "DISENGAGED (the pre-precondition reporting rule: the "
                + "CONSTRUCT_UNLOWERED deficit is reported per fixture, "
                + "never skipped)"));
        if (!deficits.isEmpty()) {
            System.out.println();
            System.out.println("Acceptance deficit (reported, non-fatal under "
                + "the pre-precondition rule):");
            for (String line : deficits) {
                System.out.println("  " + line);
            }
        }
        System.out.println();
        System.out.println("passed: " + passed.get() + ", fatal: "
            + fatalCount.get() + ", reported deficit: " + deficits.size());
        if (fatalCount.get() > 0) {
            System.out.println();
            System.out.println("Fatal audit failures:");
            for (String line : fatalLines) {
                System.out.println("  " + line);
            }
        }
        return fatalCount.get();
    }
}
