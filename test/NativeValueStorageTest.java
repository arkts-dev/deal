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
 * The C11 value storage, root, cell, text and crossing-value-table contract
 * (ISSUE-0905).
 *
 * <p>Every case compiles and links the <em>published</em> runtime tree produced
 * by {@link NativeCompiler#deployDeploymentSet(Path)} — the closed D6 set
 * resolved through the production resolution order — with the pinned strict
 * C11 flag list at {@code -O0} and {@code -O2}. The module side is the
 * committed fixture stand-in for the emitted project module
 * ({@code test/native/module_stub.c}) plus the count-zero registry sentinel, so
 * a link proves the delivered entries of the {@code value}, {@code memory},
 * {@code text} and {@code container} groups compile, link and run inside the
 * published tree rather than against a hand-assembled directory.</p>
 *
 * <p>Covered criteria:</p>
 *
 * <ol>
 *   <li>the C3 value states: distinct {@code null}/{@code undefined} data
 *       values, absence as presence metadata, the independent scalar-nullish
 *       tags, the persistent tagged payload of an {@code unknown} position
 *       (never a crossing handle), the data {@code ===} relation and the
 *       identity of arrays, records, class instances, function values and
 *       Errors;</li>
 *   <li>record storage: absent versus present-undefined versus present-null
 *       versus present-value, {@code has}, deletion, the read projection to
 *       undefined, first-insertion key order and observable reference
 *       identity;</li>
 *   <li>array storage: exact element counts, the recorded element layout
 *       across empty arrays and aliases, append-at-length, the checked
 *       discrete-position conversion (finiteness, integrality, bounds,
 *       negative zero) and unchanged storage after a rejected write;</li>
 *   <li>the M1 object model: emitted layout install and lookup, zero-initialized
 *       allocation, header layout/size facts and the precise reference-map walk
 *       with its tag-aware treatment of the nullish singletons;</li>
 *   <li>the M4/M5/M6 rooting surface: tag-aware root-frame writes, nested root
 *       frames, scoped push/hold/release/pop roots, registered root sources,
 *       the static root table, captured-binding cells with their generations,
 *       and the root depth restored after every exit;</li>
 *   <li>the text group: strict Unicode scalar-sequence validation (malformed
 *       carriers never become admitted data), immutable copied storage, exact
 *       byte and scalar counts, the cached content hash and the one
 *       scalar-value sequence comparison over ASCII, BMP and astral
 *       scalars — including the scalar-versus-UTF-16 ordering case;</li>
 *   <li>the crossing value table: issuance, resolution, foreign rejection of
 *       another or of a finished crossing, generation safety across a reused
 *       slot, supplemental rooting, crossing-scoped release and the crossing
 *       scope as a root source that roots exactly its managed referents;</li>
 *   <li>the deterministic invariant faults of those entries — including the
 *       shared overflow-checked size arithmetic ({@code R10}): an allocation
 *       whose header-and-payload total cannot be aligned and a page round that
 *       overflows the address space are that fault before any storage exists,
 *       never a wrapped short region — one {@code DEAL_RUNTIME_FAULT: <reason>}
 *       line on stderr, no stdout and exit 2, never a language-visible Error
 *       and never a partial publication.</li>
 * </ol>
 */
public final class NativeValueStorageTest {

    private static final int[] LEVELS = {0, 2};

    private static final String FAULT_PREFIX = "DEAL_RUNTIME_FAULT: ";

    private static int passed;
    private static int failed;

    private static Path scratch;
    private static Path published;

    public static void main(String[] args) throws Exception {
        System.out.println("=== C11 Value Storage / Roots / Cells / Text / Crossing "
            + "Tests (ISSUE-0905) ===\n");
        scratch = NativeCompiler.executableScratch("native-value");
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
            testValueAndContainerStorage();
            testMemoryStorageAndRoots();
            testTextStorage();
            testCrossingValueTable();
            testStorageInvariantFaults();
        } finally {
            deleteRecursively(scratch);
        }
        System.out.println();
        System.out.println("Passed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
        System.out.println("=== C11 Value Storage / Roots / Cells / Text / Crossing "
            + "Tests Passed ===");
    }

    // =========================================================================
    // 1. Value and container storage (R2, C3)
    // =========================================================================
    private static void testValueAndContainerStorage() throws IOException {
        System.out.println("-- value states, records and typed arrays (R2, C3) --");
        runPositive("value_storage_driver", "value storage ok\n",
            "the distinct data states, record presence/order and typed array "
                + "storage", List.of());
    }

    // =========================================================================
    // 2. Layouts, allocation, root frames, scopes, sources and cells (M1-M6)
    // =========================================================================
    private static void testMemoryStorageAndRoots() throws IOException {
        System.out.println("-- layouts, allocation, roots and cells (M1-M6) --");
        runPositive("memory_storage_driver", "memory storage ok\n",
            "the layout registry, allocation entry, precise reference map, root "
                + "frames, scoped roots, static roots and binding cells",
            List.of("-DDEAL_MEMORY_CASE=0"));
    }

    // =========================================================================
    // 3. Text storage and the scalar-sequence comparison (R2, R8)
    // =========================================================================
    private static void testTextStorage() throws IOException {
        System.out.println("-- UTF-8 storage, scalar counts, hash and comparison --");
        runPositive("text_storage_driver", "text storage ok\n",
            "the scalar-sequence validation, immutable storage and Unicode "
                + "scalar ordering", List.of("-DDEAL_TEXT_CASE=0"));
    }

    // =========================================================================
    // 4. The crossing value table (R3, R11, M5)
    // =========================================================================
    private static void testCrossingValueTable() throws IOException {
        System.out.println("-- crossing slot/generation lifetime and rooting --");
        runPositive("crossing_storage_driver", "crossing storage ok\n",
            "the crossing value table's issuance, resolution, foreign rejection "
                + "and crossing-scoped rooting", List.of("-DDEAL_CROSSING_CASE=0"));
    }

    // =========================================================================
    // 5. The deterministic invariant faults
    // =========================================================================
    private static void testStorageInvariantFaults() throws IOException {
        System.out.println("-- the deterministic storage invariant faults --");
        record Fault(String driver, String define, String reason) { }

        List<Fault> faults = List.of(
            new Fault("memory_storage_driver", "-DDEAL_MEMORY_CASE=1",
                "alloc: size below the layout payload size"),
            new Fault("memory_storage_driver", "-DDEAL_MEMORY_CASE=2",
                "alloc: unknown layout"),
            new Fault("memory_storage_driver", "-DDEAL_MEMORY_CASE=3",
                "layout: reference map is not ascending"),
            new Fault("memory_storage_driver", "-DDEAL_MEMORY_CASE=4",
                "layout: identity outside its reserved range"),
            new Fault("memory_storage_driver", "-DDEAL_MEMORY_CASE=5",
                "layout: duplicate layout id"),
            new Fault("memory_storage_driver", "-DDEAL_MEMORY_CASE=6",
                "layout: reference slot outside the payload"),
            new Fault("memory_storage_driver", "-DDEAL_MEMORY_CASE=7",
                "object: not a managed object"),
            new Fault("memory_storage_driver", "-DDEAL_MEMORY_CASE=8",
                "root scope: no active scope"),
            new Fault("memory_storage_driver", "-DDEAL_MEMORY_CASE=9",
                "object: not a managed object"),
            new Fault("memory_storage_driver", "-DDEAL_MEMORY_CASE=10",
                "layout: identity defined twice"),
            new Fault("memory_storage_driver", "-DDEAL_MEMORY_CASE=11",
                "size arithmetic: addition overflow"),
            new Fault("memory_storage_driver", "-DDEAL_MEMORY_CASE=12",
                "size arithmetic: addition overflow"),
            new Fault("text_storage_driver", "-DDEAL_TEXT_CASE=1",
                "text: bytes are not a Unicode scalar sequence"),
            new Fault("crossing_storage_driver", "-DDEAL_CROSSING_CASE=1",
                "crossing value table: null out-parameter"),
            new Fault("crossing_storage_driver", "-DDEAL_CROSSING_CASE=2",
                "crossing value table: end of a non-active crossing"),
            new Fault("crossing_storage_driver", "-DDEAL_CROSSING_CASE=3",
                "crossing value table: end of a non-active crossing"));

        for (Fault fault : faults) {
            String name = fault.driver() + "-" + fault.define()
                .replace("=", "").replace("-D", "");
            for (int level : LEVELS) {
                Path product = scratch.resolve(name + "-o" + level);
                NativeCompiler.Result link = NativeCompiler.link(published, level,
                    sourcesOf(fault.driver()), List.of(fault.define()),
                    product);
                check(link.succeeded(), "the " + fault.reason() + " case builds "
                    + "at -O" + level + ": " + link.describe());
                if (!link.succeeded()) {
                    continue;
                }
                NativeCompiler.Result run = NativeCompiler.execute(product, scratch,
                    Map.of());
                checkEq(2, run.exitCode(), "the " + fault.reason()
                    + " case exits 2 at -O" + level + ": " + run.describe());
                checkEq("", run.stdout(), "the " + fault.reason()
                    + " case writes no stdout at -O" + level);
                checkEq(FAULT_PREFIX + fault.reason() + "\n", run.stderr(),
                    "the " + fault.reason()
                        + " case writes exactly one deterministic stderr line at "
                        + "-O" + level);
            }
        }
    }

    // =========================================================================
    // Shared driver harness
    // =========================================================================

    /**
     * Builds one committed driver against the published tree at both levels,
     * runs it and asserts its exact successful observation.
     */
    private static void runPositive(String driver, String expectedStdout,
                                    String what, List<String> extraFlags)
            throws IOException {
        for (int level : LEVELS) {
            Path product = scratch.resolve(driver + "-o" + level);
            NativeCompiler.Result link = NativeCompiler.link(published, level,
                sourcesOf(driver), extraFlags, product);
            check(link.succeeded(), what + " builds at -O" + level + ": "
                + link.describe());
            checkEq("", link.stderr(), what + " builds without diagnostics at -O"
                + level);
            if (!link.succeeded()) {
                continue;
            }
            NativeCompiler.Result run = NativeCompiler.execute(product, scratch,
                Map.of());
            checkEq(0, run.exitCode(), what + " exits zero at -O" + level + ": "
                + run.describe());
            checkEq(expectedStdout, run.stdout(), what + " reports its exact "
                + "success line at -O" + level);
            checkEq("", run.stderr(), what + " keeps stderr empty at -O" + level);
        }
    }

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
