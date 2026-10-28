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
 *   <li>the insertion barrier on a member deletion: while the collector is
 *       marking, deleting the head and a range around the traced boundary of an
 *       insertion-ordered record republishes the surviving neighbours' links,
 *       and every surviving member, key and value stays reachable through them
 *       with exact reclamation deltas — a rewiring store that skips the barrier
 *       strands the surviving prefix and its keys;</li>
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
 *   <li>allocation-footprint pressure (M8): a hundred thousand zero-payload
 *       allocations still reach the collection trigger and are all swept, while
 *       the payload statistics stay at zero — the growth policy charges the
 *       header and capacity of every allocation, not its payload alone;</li>
 *   <li>resumable, reference-bounded marking (M3): no safepoint may make more
 *       than the published reference budget of marking work, one complete cycle
 *       visits every slot of a wide object exactly once, the cycle therefore
 *       needs at least one safepoint per budget, and every referent survives;</li>
 *   <li>capacity-aware reuse and arena release (M1, M8): a freed slot serves a
 *       smaller request and then a larger one that still fits the slot's original
 *       capacity, and a churn across every size class returns the arena pages so
 *       the resident set does not grow with the churn volume;</li>
 *   <li>randomized incremental mutation and deletion (M1, M2, M3): a seeded
 *       workload mutates, links and clears a rooted reference graph across
 *       safepoints and complete collections, and after every collection the
 *       collector's live object count and every reachable payload match the
 *       model;</li>
 *   <li>physical memory safety: the same driver compiled with
 *       AddressSanitizer/UndefinedBehaviorSanitizer at both levels and under
 *       {@code DEAL_GC_STRESS=1} reports no finding, and the committed
 *       {@code gc_poison_probe.c} pins that a reclaimed payload really is
 *       poisoned (a stale read is a reported use-after-poison) instead of
 *       silently readable inside the still-mapped arena;</li>
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
            testSanitizerMemorySafety();
            testPoisonProbe();
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
    // 2. Physical memory safety: the same driver under ASan/UBSan
    // =========================================================================

    /**
     * The physical-memory-safety leg of the collector suite: the <em>same</em>
     * committed driver and the <em>same</em> published runtime tree, compiled
     * with AddressSanitizer and UndefinedBehaviorSanitizer instead of plain C11,
     * at both levels and normally and under {@code DEAL_GC_STRESS=1}.
     *
     * <p>The expected observation is the same exact acceptance line as the plain
     * leg, plus an empty stderr: a finding would be a report, so no assertion
     * here is derived from the instrumented run. The instrumented build also
     * poisons every reclaimed payload (M1), so the collector's own churn, the
     * free-list reuse and the arena release run with a stale-access detector
     * inside the still-mapped arena — the case ordinary AddressSanitizer cannot
     * see and that {@link #testPoisonProbe()} pins as a negative control.</p>
     */
    private static void testSanitizerMemorySafety() throws IOException {
        System.out.println("-- the published collector under ASan/UBSan at "
            + "-O0/-O2, normally and under DEAL_GC_STRESS=1 --");
        for (int level : LEVELS) {
            Path product = scratch.resolve("gc-driver-asan-o" + level);
            NativeCompiler.Result link = NativeCompiler.link(published, level,
                sourcesOf("gc_driver"), NativeCompiler.SANITIZER_FLAGS, product);
            check(link.succeeded(), "the collector driver builds with ASan/UBSan "
                + "at -O" + level + ": " + link.describe());
            checkEq("", link.stderr(), "the collector driver builds with "
                + "ASan/UBSan without diagnostics at -O" + level);
            if (!link.succeeded()) {
                continue;
            }
            NativeCompiler.SanitizedRun normal = NativeCompiler.runSanitized(
                product, scratch, NativeCompiler.sanitizerEnvironment());
            String where = normal.relaunched()
                ? " (relaunched with address-space randomization disabled for "
                    + "the child only)" : "";
            checkEq(0, normal.result().exitCode(), "the ASan/UBSan collector "
                + "driver exits zero at -O" + level + where + ": "
                + normal.result().describe());
            checkEq(ACCEPTANCE_LINE, normal.result().stdout(),
                "the ASan/UBSan collector driver reports its exact success line "
                    + "at -O" + level);
            checkEq("", normal.result().stderr(), "the ASan/UBSan collector "
                + "driver reports no finding at -O" + level + ": "
                + normal.result().describe());

            java.util.Map<String, String> stressedEnvironment =
                new java.util.LinkedHashMap<>(
                    NativeCompiler.sanitizerEnvironment());
            stressedEnvironment.put("DEAL_GC_STRESS", "1");
            NativeCompiler.SanitizedRun stressed = NativeCompiler.runSanitized(
                product, scratch, stressedEnvironment);
            checkEq(0, stressed.result().exitCode(), "the stressed ASan/UBSan "
                + "collector driver exits zero at -O" + level + ": "
                + stressed.result().describe());
            checkEq(normal.result().stdout(), stressed.result().stdout(),
                "DEAL_GC_STRESS changes collection timing only under ASan/UBSan "
                    + "at -O" + level);
            checkEq("", stressed.result().stderr(), "the stressed ASan/UBSan "
                + "collector driver reports no finding at -O" + level + ": "
                + stressed.result().describe());
        }
    }

    /**
     * The negative control of the collector's sanitizer poison (M1): the
     * committed {@code gc_poison_probe.c} allocates one object, drops it, has the
     * statistics confirm that the sweep reclaimed it, and then reads the payload
     * through the dropped pointer. In an AddressSanitizer build the collector
     * poisoned that payload, so the read is the reported {@code use-after-poison}
     * and the probe prints nothing; without the poison the same probe would print
     * the stale bytes and exit zero, which is what makes this a control rather
     * than a restatement of the acceptance line. The fixture refuses to compile
     * without the sanitizer, so the case asserts both halves: the plain build
     * fails and the instrumented build reports the finding.
     */
    private static void testPoisonProbe() throws IOException {
        System.out.println("-- the reclaimed-payload poison negative control --");
        Path plain = scratch.resolve("gc-poison-probe-plain");
        NativeCompiler.Result plainLink = NativeCompiler.link(published, 2,
            sourcesOf("gc_poison_probe"), List.of(), plain);
        check(!plainLink.succeeded(), "the poison probe refuses to build without "
            + "AddressSanitizer: " + plainLink.describe());
        check(plainLink.stderr().contains("AddressSanitizer-only fixture"),
            "the poison probe's refusal names the sanitizer requirement");

        Path product = scratch.resolve("gc-poison-probe");
        NativeCompiler.Result link = NativeCompiler.link(published, 2,
            sourcesOf("gc_poison_probe"), NativeCompiler.SANITIZER_FLAGS,
            product);
        check(link.succeeded(), "the poison probe builds with ASan: "
            + link.describe());
        checkEq("", link.stderr(), "the poison probe builds without diagnostics");
        if (!link.succeeded()) {
            return;
        }
        NativeCompiler.SanitizedRun run = NativeCompiler.runSanitized(product,
            scratch, NativeCompiler.sanitizerEnvironment());
        check(run.result().exitCode() != 0, "the stale read of a reclaimed "
            + "payload is a sanitizer finding, so the probe does not exit zero: "
            + run.result().describe());
        check(run.result().stderr().contains("use-after-poison"),
            "the finding is the reported use-after-poison of the reclaimed "
                + "payload: " + run.result().describe());
        check(!run.result().stdout().contains("poison probe reached"),
            "the probe never reaches a live read of the reclaimed payload: "
                + run.result().describe());
    }

    // =========================================================================
    // 3. The platform half of Error reclamation
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
    // 4. The deterministic invariant faults of the collector entries
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
            new Fault(6, "layout release: identity released twice"),
            new Fault(7, "alloc: size exceeds the largest size class"));

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
    // 5. The supplementary instrumented poster/collector probe
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
        NativeCompiler.SanitizedRun sanitized = NativeCompiler.runSanitized(
            product, scratch, environment);
        NativeCompiler.Result run = sanitized.result();
        boolean relaunched = sanitized.relaunched();
        if (relaunched) {
            System.out.println("   (the sanitizer's shadow mapping needs "
                + "address-space randomization disabled on this host; the same "
                + "instrumented product was relaunched)");
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
