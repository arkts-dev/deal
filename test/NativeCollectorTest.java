package deal.test;

import deal.toolchain.NativeRuntime;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The C11 precise non-moving incremental collector contract (ISSUE-0906,
 * {@code llvm-native-memory-management} M1-M8).
 *
 * <p>Every case compiles and links the <em>published</em> runtime tree produced
 * by {@link NativeCompiler#deployDeploymentSet(Path)} — the closed D6 set
 * resolved through the production resolution order — with the pinned strict
 * C11 flag list at {@code -O0} and {@code -O2}. The module side is the committed
 * fixture stand-in for the emitted project module
 * ({@code test/native/module_stub.c}) plus the count-zero registry sentinel, so
 * every collection, barrier, root-source scan and reclamation observation runs
 * inside the published tree rather than against a hand-assembled directory.</p>
 *
 * <p>Covered criteria:</p>
 *
 * <ol>
 *   <li>the M7 observation surface: exact live-byte, live-object, cumulative
 *       allocation, completed-cycle and swept-object accounting, and a fully
 *       live full collection that reclaims nothing;</li>
 *   <li>cycle reclamation with exact object/byte/sweep deltas, address and
 *       identity stability of a surviving copy, and reclamation of a
 *       self-referencing node once its root is dropped;</li>
 *   <li>the M2 insertion barrier store path: referents allocated while an
 *       incremental cycle is in flight are stored into an already marked cell,
 *       array element slot and record value slot and survive with their
 *       payload, and give way to a number, to {@code null} and to
 *       {@code undefined} without the collector reading a scalar or a nullish
 *       payload as a heap address;</li>
 *   <li>the M2 insertion barrier shade path, isolated: referents that exist
 *       before the cycle starts and are reachable only from the driver's C
 *       locals are stored into an already marked and already traced cell, array
 *       element slot and record value slot while the cycle is marking, and
 *       survive with exact object/byte/sweep deltas that are one object per
 *       referent too large when {@code deal_store_barrier} or
 *       {@code deal_store_barrier_data} is reduced to a no-op;</li>
 *   <li>the allocation-time mark of M1, isolated: the internal growth trigger
 *       is filled exactly with dropped objects, an explicit safepoint starts
 *       the cycle and passes its atomic phase at once, and the node allocated
 *       next — while the cycle is already sweeping — is written into a root
 *       slot only afterwards, so nothing but the allocation-time mark keeps it;
 *       without that mark the exact deltas are one object and one payload lower
 *       and one swept object higher;</li>
 *   <li>the internal growth policy: a cycle starts, advances by bounded slices
 *       and completes without an explicit collection call, so no
 *       non-collecting production mode survives; {@code DEAL_GC_STRESS=1}
 *       collects at every safepoint and changes collection timing only, because
 *       the same driver produces byte-identical output in both modes at both
 *       levels;</li>
 *   <li>the M5 root sources: generated root frames, scoped runtime roots, a
 *       registered source (the shape the handle table and the open-operation
 *       table register) enumerated exactly once by the cycle-start walk and
 *       once by the atomic-phase rescan of one complete cycle and by no idle
 *       period, the static root table, the crossing value table for exactly its
 *       crossing's lifetime, and the Error record graph with its cause
 *       slot;</li>
 *   <li>Error reclamation releases the whole record, not only its managed
 *       payload: the copied code, message and origin text, the optional
 *       expected and actual text, the frames array and one duplicated label
 *       per frame are platform storage the sweep releases through the layout's
 *       registered reclamation hook
 *       ({@code deal_layout_release_register}), counted exactly through the
 *       runtime's only system-allocator calls, so a reclaimed Error strands
 *       nothing, a live one keeps its text, and a record allocated over
 *       reclaimed storage gets fresh storage and is released in turn;</li>
 *   <li>root-state restoration after every exit, including a repeated
 *       caught-Error loop that must not grow the root stack and a finished
 *       crossing that must leave no scope and no root source behind;</li>
 *   <li>queued completion frames: producers on other threads post while the
 *       mutator allocates and collects, a collection with frames queued
 *       reclaims nothing, delivery stays on the mutator, every copied payload
 *       is byte-exact and delivered exactly once, every frame is released
 *       exactly once, and the delivered bytes materialize a managed value that
 *       survives the next collection;</li>
 *   <li>the deterministic invariant faults of the collector's own entries: one
 *       {@code DEAL_RUNTIME_FAULT: <reason>} line on stderr, no stdout and
 *       exit 2, never a language-visible Error and never a partial sweep;</li>
 *   <li>the supplementary ThreadSanitizer probe: instrumented posters run
 *       against the collector with no data-race report and the same exact
 *       delivery.</li>
 * </ol>
 */
public final class NativeCollectorTest {

    private static final int[] LEVELS = {0, 2};

    private static final String FAULT_PREFIX = "DEAL_RUNTIME_FAULT: ";

    private static final String ACCEPTANCE_LINE = "gc collector ok\n";

    private static int passed;
    private static int failed;

    private static Path scratch;
    private static Path published;

    public static void main(String[] args) throws Exception {
        System.out.println("=== C11 Incremental Collector / Barriers / Stress Tests "
            + "(ISSUE-0906) ===\n");
        scratch = NativeCompiler.executableScratch("native-collector");
        published = Files.createDirectories(scratch.resolve("published"));
        try {
            int deployed = NativeCompiler.deployDeploymentSet(published);
            checkEq(NativeRuntime.FILES.size(), deployed,
                "the published runtime tree carries the closed deployment set");
            for (String relativeName : NativeRuntime.FILES) {
                NativeCompiler.requireSameBytes(
                    Files.readAllBytes(NativeCompiler.repository()
                        .resolve(relativeName)),
                    Files.readAllBytes(published.resolve(relativeName)),
                    "the published file equals its source: " + relativeName);
            }
            System.out.println("native compiler: " + NativeCompiler.compiler().path()
                + " (major " + NativeCompiler.compiler().major() + ")");
            testCollectorAcceptance();
            testErrorReclamation();
            testCollectorInvariantFaults();
            testPosterRaceProbe();
        } finally {
            deleteRecursively(scratch);
        }
        System.out.println();
        System.out.println("Passed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
        System.out.println("=== C11 Incremental Collector / Barriers / Stress "
            + "Tests Passed ===");
    }

    // =========================================================================
    // 1. The collector acceptance at both levels, normally and under stress
    // =========================================================================
    private static void testCollectorAcceptance() throws IOException {
        System.out.println("-- collection, barriers, roots and completions at "
            + "-O0/-O2, normally and under DEAL_GC_STRESS=1 --");
        for (int level : LEVELS) {
            Path product = scratch.resolve("gc-driver-o" + level);
            NativeCompiler.Result link = NativeCompiler.link(published, level,
                sourcesOf("gc_driver"), List.of(), product);
            check(link.succeeded(), "the collector driver builds at -O" + level
                + ": " + link.describe());
            checkEq("", link.stderr(),
                "the collector driver builds without diagnostics at -O" + level);
            if (!link.succeeded()) {
                continue;
            }
            NativeCompiler.Result normal = NativeCompiler.execute(product,
                scratch, Map.of());
            checkEq(0, normal.exitCode(), "the collector driver exits zero at "
                + "-O" + level + ": " + normal.describe());
            checkEq(ACCEPTANCE_LINE, normal.stdout(), "the collector driver "
                + "reports its exact success line at -O" + level);
            checkEq("", normal.stderr(), "the collector driver keeps stderr "
                + "empty at -O" + level);

            NativeCompiler.Result stressed = NativeCompiler.execute(product,
                scratch, Map.of("DEAL_GC_STRESS", "1"));
            checkEq(0, stressed.exitCode(), "the stressed collector driver "
                + "exits zero at -O" + level + ": " + stressed.describe());
            checkEq(normal.stdout(), stressed.stdout(),
                "DEAL_GC_STRESS changes collection timing only: the same driver "
                    + "produces byte-identical stdout at -O" + level);
            checkEq(normal.stderr(), stressed.stderr(),
                "DEAL_GC_STRESS produces no diagnostic at -O" + level);
        }
    }

    // =========================================================================
    // 2. The platform half of Error reclamation
    // =========================================================================

    /**
     * The reclamation hook ({@code deal_layout_release_register}) of the Error
     * layout: a reclaimed record must release the platform storage only it can
     * reach — the copied code, message and origin text, the optional expected
     * and actual text, the frames array and one duplicated label per frame —
     * because {@code deal_gc_statistics} counts managed payload only.
     *
     * <p>{@code platform.c} is the runtime's only caller of the system
     * allocator, so the link wraps {@code malloc}/{@code free} and counts
     * exactly the platform storage the record owns. No published runtime symbol
     * is replaced and the published tree stays byte-identical. The fixture fails
     * when the hook does not run (the count never returns to its baseline),
     * when it runs for a live record (the read-backs would be released
     * storage), and when a record allocated over reclaimed storage inherits a
     * released pointer.</p>
     */
    private static void testErrorReclamation() throws IOException {
        System.out.println("-- Error reclamation releases its platform storage "
            + "at -O0/-O2, normally and under DEAL_GC_STRESS=1 --");
        for (int level : LEVELS) {
            Path product = scratch.resolve("error-reclaim-o" + level);
            NativeCompiler.Result link = NativeCompiler.link(published, level,
                sourcesOf("error_reclaim_driver"),
                PLATFORM_ALLOCATION_COUNT_FLAGS, product);
            check(link.succeeded(), "the Error reclamation driver builds at "
                + "-O" + level + ": " + link.describe());
            checkEq("", link.stderr(), "the Error reclamation driver builds "
                + "without diagnostics at -O" + level);
            if (!link.succeeded()) {
                continue;
            }
            NativeCompiler.Result normal = NativeCompiler.execute(product,
                scratch, Map.of());
            checkEq(0, normal.exitCode(), "the Error reclamation driver exits "
                + "zero at -O" + level + ": " + normal.describe());
            checkEq(ERROR_RECLAIM_LINE, normal.stdout(), "the Error "
                + "reclamation driver reports its exact success line at -O"
                + level);
            checkEq("", normal.stderr(), "the Error reclamation driver keeps "
                + "stderr empty at -O" + level);

            NativeCompiler.Result stressed = NativeCompiler.execute(product,
                scratch, Map.of("DEAL_GC_STRESS", "1"));
            checkEq(0, stressed.exitCode(), "the stressed Error reclamation "
                + "driver exits zero at -O" + level + ": " + stressed.describe());
            checkEq(normal.stdout(), stressed.stdout(),
                "DEAL_GC_STRESS changes collection timing only for Error "
                    + "reclamation: the same driver produces byte-identical "
                    + "stdout at -O" + level);
            checkEq("", stressed.stderr(), "the stressed Error reclamation "
                + "driver produces no diagnostic at -O" + level);
        }
    }

    /**
     * The link-time interposition that makes the platform half of Error
     * reclamation observable. Every allocation and release of the published
     * runtime goes through {@code deal_platform_allocate} /
     * {@code deal_platform_release}, whose only system-allocator calls are the
     * wrapped {@code malloc}/{@code free}; the count is therefore exactly the
     * platform storage the managed records own. The builtin spellings are
     * disabled so no optimization can inline the counted calls away at either
     * level.
     */
    private static final List<String> PLATFORM_ALLOCATION_COUNT_FLAGS =
        List.of("-Wl,--wrap=malloc", "-Wl,--wrap=free",
            "-fno-builtin-malloc", "-fno-builtin-free");

    /** The Error reclamation driver's exact success line. */
    private static final String ERROR_RECLAIM_LINE = "error reclaim ok\n";

    // =========================================================================
    // 3. The deterministic invariant faults of the collector entries
    // =========================================================================
    private static void testCollectorInvariantFaults() throws IOException {
        System.out.println("-- the deterministic collector invariant faults --");
        record Fault(int gcCase, String reason) { }

        List<Fault> faults = List.of(
            new Fault(1, "gc statistics: null out-parameter"),
            new Fault(2, "layout tracer: null trace"),
            new Fault(3, "layout tracer: identity traced twice"),
            new Fault(4, "gc: nested full collection"),
            new Fault(5, "layout release: null release"),
            new Fault(6, "layout release: identity released twice"));

        for (Fault fault : faults) {
            for (int level : LEVELS) {
                Path product = scratch.resolve("gc-case" + fault.gcCase()
                    + "-o" + level);
                NativeCompiler.Result link = NativeCompiler.link(published, level,
                    sourcesOf("gc_driver"),
                    List.of("-DDEAL_GC_CASE=" + fault.gcCase()), product);
                check(link.succeeded(), "the " + fault.reason() + " case builds "
                    + "at -O" + level + ": " + link.describe());
                if (!link.succeeded()) {
                    continue;
                }
                NativeCompiler.Result run = NativeCompiler.execute(product,
                    scratch, Map.of());
                checkEq(2, run.exitCode(), "the " + fault.reason()
                    + " case exits 2 at -O" + level + ": " + run.describe());
                checkEq("", run.stdout(), "the " + fault.reason()
                    + " case writes no stdout at -O" + level);
                checkEq(FAULT_PREFIX + fault.reason() + "\n", run.stderr(),
                    "the " + fault.reason() + " case writes exactly one "
                        + "deterministic stderr line at -O" + level);
            }
        }
    }

    // =========================================================================
    // 4. The supplementary instrumented poster/collector probe
    // =========================================================================

    /**
     * The ThreadSanitizer half of the concurrent-poster criterion: the published
     * runtime and {@code gc_posters_probe.c} are compiled into one
     * sanitizer-instrumented product at {@code -O2} with
     * {@code -fsanitize=thread -g}, and posters run against the collector with
     * no data-race report and the accepted output.
     *
     * <p>The probe translation unit refuses to compile without the sanitizer, so
     * an uninstrumented build cannot pass as this evidence. A host whose
     * address-space-randomization entropy puts the sanitizer's shadow mapping
     * out of reach cannot start any instrumented process, so the same
     * instrumented product is relaunched with randomization disabled for that
     * child only; every acceptance assertion is still applied to the run.</p>
     */
    private static void testPosterRaceProbe() throws IOException {
        System.out.println("-- the instrumented poster/collector probe --");
        Path product = scratch.resolve("gc-posters-probe");
        NativeCompiler.Result link = NativeCompiler.link(published, 2,
            sourcesOf("gc_posters_probe"), List.of("-fsanitize=thread", "-g"),
            product);
        check(link.succeeded(), "the supplementary poster probe builds the "
            + "published runtime with -fsanitize=thread -g: " + link.describe());
        if (!link.succeeded()) {
            return;
        }
        Map<String, String> environment = Map.of(
            "TSAN_OPTIONS", "halt_on_error=1:exitcode=66");
        NativeCompiler.Result run = NativeCompiler.run(
            List.of(product.toAbsolutePath().toString()), scratch, environment);
        boolean relaunched = false;
        if (sanitizerStartupFailure(run)) {
            List<String> withoutRandomization = disableAslrCommand(product);
            if (withoutRandomization != null) {
                for (int attempt = 0;
                        attempt < SANITIZER_RELAUNCH_ATTEMPTS; attempt += 1) {
                    run = NativeCompiler.run(withoutRandomization, scratch,
                        environment);
                    relaunched = true;
                    if (!sanitizerStartupFailure(run)) {
                        break;
                    }
                }
                if (relaunched) {
                    System.out.println("   (the sanitizer's shadow mapping "
                        + "needs address-space randomization disabled on this "
                        + "host; the same instrumented product was relaunched)");
                }
            }
        }
        String where = relaunched
            ? " (relaunched with address-space randomization disabled for the "
                + "child only)" : "";
        checkEq(0, run.exitCode(), "the instrumented poster/collector workload "
            + "exits zero" + where + ": " + run.describe());
        checkEq("gc posters ok\n", run.stdout(), "the instrumented "
            + "poster/collector workload delivers every copied payload exactly "
            + "once" + where);
        checkEq("", run.stderr(), "the instrumented poster/collector workload "
            + "produces no ThreadSanitizer report" + where);
    }

    /** The bounded relaunches of one instrumented product on this host. */
    private static final int SANITIZER_RELAUNCH_ATTEMPTS = 3;

    /**
     * True when the process could not start the sanitizer runtime at all, as
     * opposed to a sanitizer finding about the code under test. The shadow
     * mapping is out of reach of this host's address-space layout, so the
     * instrumented process dies before its workload, either with the named
     * runtime message or with an early fault the runtime never reports. A
     * genuine report ({@code WARNING: ThreadSanitizer}) and a probe that
     * reached its own assertions (non-empty stdout) are never mistaken for
     * this and never retried, so no finding is retried away.
     */
    private static boolean sanitizerStartupFailure(NativeCompiler.Result run) {
        if (run.exitCode() == 0) {
            return false;
        }
        if (run.stderr().contains("WARNING: ThreadSanitizer")) {
            return false;
        }
        return run.stdout().isEmpty();
    }

    /**
     * The instrumented product relaunched with {@code ADDR_NO_RANDOMIZE} for
     * that child only (through {@code setarch -R}), or null when the host has
     * no {@code setarch}.
     */
    private static List<String> disableAslrCommand(Path product) {
        String path = System.getenv("PATH");
        if (path != null) {
            for (String directory : path.split(java.io.File.pathSeparator)) {
                if (directory.isEmpty()) {
                    continue;
                }
                Path candidate = Path.of(directory, "setarch");
                if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                    return List.of(candidate.toString(), "-R",
                        product.toAbsolutePath().toString());
                }
            }
        }
        return null;
    }

    // =========================================================================
    // Shared driver harness
    // =========================================================================

    /**
     * One committed driver in the driver build shape (D4): the module fixture,
     * the count-zero registry, every published runtime translation unit except
     * the process entry, and the driver's own unit.
     */
    private static List<Path> sourcesOf(String driver) {
        return NativeCompiler.driverSources(published,
            List.of(fixture("module_stub.c"), fixture("registry_empty.c")),
            fixture(driver + ".c"));
    }

    private static Path fixture(String name) {
        return NativeCompiler.fixtures().resolve(name);
    }

    private static void check(boolean condition, String what) {
        if (condition) {
            passed += 1;
        } else {
            failed += 1;
            System.out.println("FAIL: " + what);
        }
    }

    private static void checkEq(Object expected, Object actual, String what) {
        if (expected.equals(actual)) {
            passed += 1;
        } else {
            failed += 1;
            System.out.println("FAIL: " + what + " (expected <" + expected
                + ">, got <" + actual + ">)");
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
