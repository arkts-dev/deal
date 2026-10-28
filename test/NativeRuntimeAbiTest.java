package deal.test;

import deal.toolchain.NativeRuntime;
import deal.test.conformance.ErrorSnapshot;
import deal.test.conformance.SidecarExpectations;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The C11 runtime ABI, platform, transport, terminal and symbol-ownership
 * contract (ISSUE-0902).
 *
 * <p>Every case compiles and links the <em>published</em> runtime tree produced
 * by {@link NativeCompiler#deployDeploymentSet(Path)} — the closed D6 set
 * resolved through the production resolution order — with the pinned strict
 * C11 flag list at {@code -O0} and {@code -O2}. The module side is a committed
 * fixture stand-in for the emitted project module
 * ({@code test/native/module_stub.c}); it defines the six export-surface
 * functions and {@code deal_project_arm_table} and nothing of the runtime, so a
 * link proves the single-definition split of C2/R9/H6.</p>
 *
 * <p>Covered criteria:</p>
 *
 * <ol>
 *   <li>every published translation unit compiles with zero diagnostics under
 *       {@code -std=c11 -Wall -Werror -Wextra -pedantic};</li>
 *   <li>H2 record conformance, enum discriminants (including the v1.5
 *       appended undefined carrier and completion discriminants), field order
 *       and the count-zero registry sentinel from a consumer that includes only
 *       the published headers, plus the pinned C signature of every declared
 *       entry in a compile-only consumer (including the entries this task
 *       declares without implementing);</li>
 *   <li>the C3 v1.5 nullish representation authority: `null` and `undefined`
 *       are distinct immutable singletons and neither is C `NULL`;</li>
 *   <li>type opacity: pointer use compiles, {@code sizeof(deal_module)} and a
 *       {@code deal_export_entry} layout use do not;</li>
 *   <li>the failure-arm-table transport: accepted and installed before user
 *       code, and every structural violation terminating with one deterministic
 *       stderr line and exit 2;</li>
 *   <li>origin transport, the accessors, the terminal framing lines and
 *       {@code DEAL_DEFER_MAIN};</li>
 *   <li>the call-frame and root-frame invariants, including their deterministic
 *       fault paths and the zero-slot root frame of the emitted skeleton, plus
 *       its supplementary ASan/UBSan build and the same instrumented accessor
 *       consumer;</li>
 *   <li>the completion queue: concurrent producers, single-consumer exactly-once
 *       delivery, copied payloads, the bounded wait, a post burst larger than
 *       the wake descriptor's capacity that never blocks its poster, a queued
 *       frame that the wait never sleeps past, the ownership and payload-growth
 *       faults, plus the supplementary ThreadSanitizer-instrumented probe of
 *       the same workload and its release/acquire publication;</li>
 *   <li>symbol ownership in both build shapes and the duplicate/missing
 *       definition negatives.</li>
 * </ol>
 */
public final class NativeRuntimeAbiTest {

    private static final int[] LEVELS = {0, 2};

    private static final String RUNTIME_FAULT_PREFIX = "DEAL_RUNTIME_FAULT: ";

    private static int passed;
    private static int failed;

    private static Path scratch;
    private static Path published;

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--compiler-probe")) {
            compilerProbeChild();
            return;
        }
        System.out.println("=== C11 Runtime ABI / Platform / Terminal Tests "
            + "(ISSUE-0902) ===\n");
        scratch = NativeCompiler.executableScratch("native-abi");
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
            testToolchainPin();
            testPublishedUnitsCompileCleanly();
            testAbiRecordConformance();
            testNullishRepresentation();
            testTypeOpacity();
            testFailureArmTableTransport();
            testOriginTerminalAndAccessors();
            testFrameAndRootInvariants();
            testCompletionQueue();
            testCompletionQueueRaceProbe();
            testSymbolOwnership();
        } finally {
            deleteRecursively(scratch);
        }
        System.out.println();
        System.out.println("Passed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
        System.out.println("=== C11 Runtime ABI / Platform / Terminal Tests Passed ===");
    }

    /**
     * The child half of the toolchain preflight: resolve the pinned compiler
     * and report the named failure, or the accepted major.
     */
    private static void compilerProbeChild() {
        try {
            NativeCompiler.Compiler resolved = NativeCompiler.compiler();
            System.out.println("accepted major " + resolved.major());
            System.exit(0);
        } catch (IllegalStateException expected) {
            System.out.println(expected.getMessage());
            System.exit(0);
        }
    }

    // =========================================================================
    // 0. The pinned toolchain window
    // =========================================================================

    /**
     * The toolchain preflight evidence: typed-pointer textual IR of the pinned
     * LLVM era compiles at both optimization levels, and a compiler that is
     * absent or outside the accepted major window fails with one named reason
     * instead of skipping the suite.
     */
    private static void testToolchainPin() throws IOException {
        System.out.println("-- the pinned typed-pointer toolchain window --");
        for (int level : LEVELS) {
            Path product = scratch.resolve("typed-pointer-probe-o" + level);
            List<String> command = new ArrayList<>();
            command.add(NativeCompiler.compiler().path());
            for (String flag : NativeCompiler.flags(published, level, true)) {
                if (flag.equals("-std=c11") || flag.startsWith("-W")
                        || flag.startsWith("-I")) {
                    continue;   // C-language-only flags: the probe is textual IR
                }
                command.add(flag);
            }
            command.add(fixture("typed_pointer_probe.ll").toString());
            command.add("-o");
            command.add(product.toString());
            NativeCompiler.Result build = NativeCompiler.run(command, scratch);
            check(build.succeeded(), "typed-pointer textual IR compiles at -O"
                + level + ": " + build.describe());
            if (build.succeeded()) {
                NativeCompiler.Result run = NativeCompiler.execute(product, scratch,
                    Map.of());
                checkEq(0, run.exitCode(),
                    "the typed-pointer probe runs at -O" + level);
            }
        }

        // An absent compiler and an out-of-window major are named failures.
        Path stubPath = Files.createDirectories(scratch.resolve("no-clang-path"));
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        Files.createSymbolicLink(stubPath.resolve("java"), java);
        NativeCompiler.Result missing = compilerProbe(stubPath);
        checkEq(0, missing.exitCode(),
            "the compiler probe runs with a clang-free PATH: " + missing.describe());
        check(missing.stdout().contains("TOOL_MISSING")
                && missing.stdout().contains("clang")
                && missing.stdout().contains("14-16"),
            "an absent clang is one named failure: " + missing.stdout());

        Path wrongMajor = Files.createDirectories(scratch.resolve("wrong-major-path"));
        Files.createSymbolicLink(wrongMajor.resolve("java"), java);
        Path fake = wrongMajor.resolve("clang");
        Files.writeString(fake,
            "#!/bin/sh\necho 'clang version 13.0.0'\n",
            StandardCharsets.UTF_8);
        fake.toFile().setExecutable(true);
        NativeCompiler.Result unsupported = compilerProbe(wrongMajor);
        checkEq(0, unsupported.exitCode(),
            "the compiler probe runs with an unsupported major: "
                + unsupported.describe());
        check(unsupported.stdout().contains("major 13"),
            "an out-of-window major is one named failure: "
                + unsupported.stdout());
    }

    /** Runs the child compiler probe with exactly the given PATH. */
    private static NativeCompiler.Result compilerProbe(Path path) {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java")
            .toString());
        command.add("-cp");
        List<String> entries = new ArrayList<>();
        for (String entry : System.getProperty("java.class.path")
                .split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
            if (!entry.isEmpty()) {
                entries.add(Path.of(entry).toAbsolutePath().normalize().toString());
            }
        }
        command.add(String.join(java.io.File.pathSeparator, entries));
        command.add(NativeRuntimeAbiTest.class.getName());
        command.add("--compiler-probe");
        return NativeCompiler.run(command, scratch, Map.of("PATH", path.toString()));
    }

    // =========================================================================
    // 1. Every published translation unit compiles cleanly
    // =========================================================================

    private static void testPublishedUnitsCompileCleanly() {
        System.out.println("-- every published translation unit compiles with "
            + "zero diagnostics --");
        List<Path> units = NativeCompiler.runtimeTranslationUnits(published);
        checkEq(NativeRuntime.TRANSLATION_UNITS.size(), units.size(),
            "the build set is the complete published translation-unit list");
        for (int level : LEVELS) {
            NativeCompiler.Result result =
                NativeCompiler.syntaxOnly(published, level, units);
            check(result.succeeded(),
                "all published units pass -std=c11 -Wall -Werror -Wextra "
                    + "-pedantic at -O" + level + ": " + result.describe());
            checkEq("", result.stdout(),
                "the compile reports nothing on stdout at -O" + level);
            checkEq("", result.stderr(),
                "the compile reports nothing on stderr at -O" + level);
        }
    }

    // =========================================================================
    // 2. H2 record conformance
    // =========================================================================

    private static void testAbiRecordConformance() throws IOException {
        System.out.println("-- H2 record conformance of the published headers --");
        for (int level : LEVELS) {
            Path product = scratch.resolve("abi-probe-o" + level);
            NativeCompiler.Result link = NativeCompiler.link(published, level,
                List.of(fixture("abi_probe.c")), product);
            check(link.succeeded(),
                "the ABI consumer builds from the published headers alone at -O"
                    + level + ": " + link.describe());
            if (!link.succeeded()) {
                continue;
            }
            NativeCompiler.Result run = NativeCompiler.execute(product, scratch,
                Map.of());
            checkEq(0, run.exitCode(),
                "the ABI consumer runs at -O" + level + ": " + run.describe());
            checkEq("abi ok\n", run.stdout(),
                "every record and completion arm conforms at -O" + level);
            checkEq("", run.stderr(),
                "the ABI consumer keeps stderr empty at -O" + level);

            // The declared-entry signatures of the whole published surface,
            // including the entries this task declares without implementing:
            // a compile-only consumer names each through a function pointer of
            // its pinned C type, so a changed parameter list or return type no
            // longer compiles.
            NativeCompiler.Result signatures = NativeCompiler.compileOnly(published,
                level, fixture("abi_signatures.c"),
                scratch.resolve("abi-signatures-o" + level + ".o"));
            check(signatures.succeeded(),
                "every declared entry keeps its pinned C signature at -O" + level
                    + ": " + signatures.describe());
            checkEq("", signatures.stdout(),
                "the signature consumer reports nothing on stdout at -O" + level);
            checkEq("", signatures.stderr(),
                "the signature consumer reports nothing on stderr at -O" + level);
        }
    }

    // =========================================================================
    // 3. The C3 v1.5 nullish representation authority
    // =========================================================================

    private static void testNullishRepresentation() throws IOException {
        System.out.println("-- null and undefined are distinct singletons --");
        for (int level : LEVELS) {
            Path product = scratch.resolve("nullish-o" + level);
            NativeCompiler.Result link = NativeCompiler.link(published, level,
                NativeCompiler.driverSources(published,
                    List.of(fixture("module_stub.c"), fixture("registry_empty.c")),
                    fixture("nullish_probe.c")), product);
            check(link.succeeded(), "the nullish consumer builds at -O" + level
                + ": " + link.describe());
            if (!link.succeeded()) {
                continue;
            }
            NativeCompiler.Result run = NativeCompiler.execute(product, scratch,
                Map.of());
            checkEq(0, run.exitCode(), "the nullish consumer exits zero at -O"
                + level + ": " + run.describe());
            checkEq("nullish ok\n", run.stdout(),
                "null and undefined keep distinct singletons and their own "
                    + "tests at -O" + level);
            checkEq("", run.stderr(),
                "the nullish consumer keeps stderr empty at -O" + level);
        }
    }

    // =========================================================================
    // 4. Type opacity
    // =========================================================================
    private static void testTypeOpacity() throws IOException {
        System.out.println("-- deal_module/deal_export_entry layouts are opaque --");
        for (int level : LEVELS) {
            Path product = scratch.resolve("opacity-o" + level);
            NativeCompiler.Result link = NativeCompiler.link(published, level,
                NativeCompiler.driverSources(published,
                    List.of(fixture("module_stub.c"), fixture("registry_empty.c")),
                    fixture("opacity_pointer.c")), product);
            check(link.succeeded(),
                "a consumer may name both opaque types through pointers at -O"
                    + level + ": " + link.describe());
            if (link.succeeded()) {
                NativeCompiler.Result run = NativeCompiler.execute(product, scratch,
                    Map.of());
                checkEq("opacity ok\n", run.stdout(),
                    "the pointer-only consumer runs at -O" + level);
                checkEq(0, run.exitCode(),
                    "the pointer-only consumer exits zero at -O" + level);
            }
            NativeCompiler.Result sizeof = NativeCompiler.compileOnly(published,
                level, fixture("opacity_sizeof.c"),
                scratch.resolve("opacity-sizeof-o" + level + ".o"));
            check(!sizeof.succeeded(),
                "sizeof(deal_module) does not compile at -O" + level
                    + ": " + sizeof.describe());
            check(sizeof.stderr().contains("incomplete type")
                    && sizeof.stderr().contains("deal_module"),
                "the sizeof negative names the incomplete type at -O" + level
                    + ": " + sizeof.stderr());
            NativeCompiler.Result fill = NativeCompiler.compileOnly(published,
                level, fixture("opacity_allocate.c"),
                scratch.resolve("opacity-allocate-o" + level + ".o"));
            check(!fill.succeeded(),
                "allocating or filling a deal_export_entry does not compile at -O"
                    + level + ": " + fill.describe());
            check(fill.stderr().contains("deal_export_entry"),
                "the layout negative names deal_export_entry at -O" + level
                    + ": " + fill.stderr());
        }
        for (int level : LEVELS) {
            NativeCompiler.Result declaration = NativeCompiler.compileOnly(
                published, level, fixture("host_load_declaration.c"),
                scratch.resolve("host-load-declaration-o" + level + ".o"));
            check(declaration.succeeded(),
                "the published header names both host load records and lets a "
                    + "consumer take the entry's address at -O" + level + ": "
                    + declaration.describe());
            checkEq("", declaration.stderr(),
                "the host load declaration consumer compiles cleanly at -O"
                    + level);
        }
        for (String relativeName : NativeRuntime.FILES) {
            if (!relativeName.endsWith(".h")) {
                continue;
            }
            String text = Files.readString(published.resolve(relativeName),
                StandardCharsets.UTF_8);
            check(!text.contains("struct deal_module {")
                    && !text.contains("struct deal_export_entry {"),
                "no published header declares a module or export-entry layout: "
                    + relativeName);
        }
    }

    // =========================================================================
    // 4. The failure-arm-table transport
    // =========================================================================

    private static void testFailureArmTableTransport() throws IOException {
        System.out.println("-- the failure-arm-table transport --");
        for (int level : LEVELS) {
            Path product = scratch.resolve("arm-probe-o" + level);
            NativeCompiler.Result link = NativeCompiler.link(published, level,
                NativeCompiler.driverSources(published,
                    List.of(fixture("module_stub.c"), fixture("registry_empty.c")),
                    fixture("arm_probe.c")), List.of("-DDEAL_STUB_CASE=0"),
                product);
            check(link.succeeded(),
                "the module defines and installs its table at -O" + level + ": "
                    + link.describe());
            if (link.succeeded()) {
                NativeCompiler.Result run = NativeCompiler.execute(product, scratch,
                    Map.of());
                checkEq("arm ok\n", run.stdout(),
                    "the accepted table is installed with every record field "
                        + "unchanged at -O" + level + ": " + run.describe());
                checkEq(0, run.exitCode(),
                    "the accepted table run exits zero at -O" + level);
            }
        }

        // The five structural violations are deterministic exits, one line,
        // before any user code runs.
        String[][] fatal = {
            {"1", "arm table: unsupported version"},
            {"2", "arm table: count and records disagree"},
            {"3", "arm table: null record text"},
            {"4", "arm table: render scope outside the closed set"},
            {"5", "arm table: duplicate arm id"},
        };
        for (String[] entry : fatal) {
            for (int level : LEVELS) {
                Path product = scratch.resolve("arm-fatal-" + entry[0] + "-o" + level);
                NativeCompiler.Result link = NativeCompiler.link(published, level,
                    NativeCompiler.productSources(published,
                        List.of(fixture("module_stub.c"),
                            fixture("registry_empty.c"))),
                    List.of("-DDEAL_STUB_CASE=" + entry[0]), product);
                check(link.succeeded(),
                    "the fatal arm-table case builds at -O" + level + ": "
                        + link.describe());
                if (!link.succeeded()) {
                    continue;
                }
                NativeCompiler.Result run = NativeCompiler.execute(product, scratch,
                    Map.of());
                checkEq(2, run.exitCode(),
                    "arm-table case " + entry[0] + " exits 2 at -O" + level
                        + ": " + run.describe());
                checkEq("", run.stdout(),
                    "arm-table case " + entry[0] + " writes no stdout at -O"
                        + level);
                checkEq(RUNTIME_FAULT_PREFIX + entry[1] + "\n", run.stderr(),
                    "arm-table case " + entry[0] + " writes exactly one "
                        + "deterministic stderr line at -O" + level);
            }
        }

        // Exactly one definition of the module's table.
        Path duplicate = scratch.resolve("arm-duplicate");
        NativeCompiler.Result duplicateLink = NativeCompiler.link(published, 0,
            NativeCompiler.productSources(published,
                List.of(fixture("module_stub.c"), fixture("module_stub.c"),
                    fixture("registry_empty.c"))), duplicate);
        check(!duplicateLink.succeeded(),
            "two definitions of the module table fail the link: "
                + duplicateLink.describe());
        check(duplicateLink.stderr().contains("multiple definition")
                && duplicateLink.stderr().contains("deal_project_arm_table"),
            "the duplicate-definition failure names deal_project_arm_table: "
                + duplicateLink.stderr());
        check(!Files.exists(duplicate),
            "the duplicate-definition link leaves no product");
    }

    // =========================================================================
    // 5. Origin transport, accessors and the terminal
    // =========================================================================

    /** The message bytes of the carrying terminal fixture (module_stub case 7). */
    private static final String FIXTURE_MESSAGE =
        "bad \"quoted\" text\\ with\ttab \u0001unit and \u00e9 scalar";

    private static void testOriginTerminalAndAccessors() throws IOException {
        System.out.println("-- origin transport, accessors and the terminal --");
        for (int level : LEVELS) {
            accessorsCarryEveryField(level);
            terminalSuccessIsSilent(level);
            terminalWithholdsOnlyTheEntryInvoke(level);
            terminalProjectsACarriedEntryError(level);
            terminalProjectsACarriedStartError(level);
        }
    }

    private static void accessorsCarryEveryField(int level) throws IOException {
        Path product = scratch.resolve("accessors-o" + level);
        NativeCompiler.Result link = NativeCompiler.link(published, level,
            NativeCompiler.driverSources(published,
                List.of(fixture("module_stub.c"), fixture("registry_empty.c")),
                fixture("accessor_driver.c")), List.of("-DDEAL_STUB_CASE=0"),
            product);
        check(link.succeeded(), "the accessor consumer builds at -O" + level
            + ": " + link.describe());
        if (!link.succeeded()) {
            return;
        }
        NativeCompiler.Result run = NativeCompiler.execute(product, scratch, Map.of());
        checkEq(0, run.exitCode(), "the accessor consumer exits zero at -O" + level
            + ": " + run.describe());
        checkEq("accessors ok\n", run.stdout(),
            "every accessor reports presence, value, absence and frame order at "
                + "-O" + level);
        checkEq("", run.stderr(),
            "the accessor consumer keeps stderr empty at -O" + level);

        // Physical memory safety: the same accessor/terminal consumer under
        // AddressSanitizer and UndefinedBehaviorSanitizer.
        Path sanitizedProduct = scratch.resolve("accessors-asan-o" + level);
        NativeCompiler.Result sanitizedLink = NativeCompiler.link(published,
            level, NativeCompiler.driverSources(published,
                List.of(fixture("module_stub.c"), fixture("registry_empty.c")),
                fixture("accessor_driver.c")),
            concat(List.of("-DDEAL_STUB_CASE=0"),
                NativeCompiler.SANITIZER_FLAGS), sanitizedProduct);
        check(sanitizedLink.succeeded(), "the accessor consumer builds with "
            + "ASan/UBSan at -O" + level + ": " + sanitizedLink.describe());
        if (!sanitizedLink.succeeded()) {
            return;
        }
        NativeCompiler.SanitizedRun sanitized = NativeCompiler.runSanitized(
            sanitizedProduct, scratch, NativeCompiler.sanitizerEnvironment());
        checkEq(0, sanitized.result().exitCode(), "the accessor consumer exits "
            + "zero under ASan/UBSan at -O" + level + ": "
            + sanitized.result().describe());
        checkEq("accessors ok\n", sanitized.result().stdout(),
            "every accessor reports presence, value, absence and frame order "
                + "under ASan/UBSan at -O" + level);
        checkEq("", sanitized.result().stderr(), "the accessor consumer reports "
            + "no sanitizer finding at -O" + level + ": "
            + sanitized.result().describe());
    }

    /** One flag list followed by another. */
    private static List<String> concat(List<String> first, List<String> second) {
        List<String> combined = new ArrayList<>(first);
        combined.addAll(second);
        return combined;
    }

    private static void terminalSuccessIsSilent(int level) throws IOException {
        Path product = buildProduct(level, "0", "terminal-success-o" + level);
        if (product == null) {
            return;
        }
        NativeCompiler.Result run = NativeCompiler.execute(product, scratch, Map.of());
        checkEq(0, run.exitCode(), "a successful entry exits zero at -O" + level
            + ": " + run.describe());
        checkEq("", run.stdout(),
            "a successful entry writes no terminal output at -O" + level);
        checkEq("", run.stderr(),
            "a successful entry writes no stderr at -O" + level);
    }

    private static void terminalWithholdsOnlyTheEntryInvoke(int level)
            throws IOException {
        Path product = buildProduct(level, "0", "terminal-defer-o" + level);
        if (product == null) {
            return;
        }
        NativeCompiler.Result invoked = NativeCompiler.execute(product, scratch,
            Map.of("DEAL_STUB_PRINT_MAIN", "1"));
        checkEq("MAIN_RAN\n", invoked.stdout(),
            "the entry invoke runs once at -O" + level + ": " + invoked.describe());
        checkEq(0, invoked.exitCode(),
            "the entry invoke exits zero at -O" + level);
        NativeCompiler.Result deferred = NativeCompiler.execute(product, scratch,
            Map.of("DEAL_STUB_PRINT_MAIN", "1", "DEAL_DEFER_MAIN", "1"));
        checkEq("", deferred.stdout(),
            "DEAL_DEFER_MAIN=1 withholds the entry invoke at -O" + level
                + ": " + deferred.describe());
        checkEq(0, deferred.exitCode(),
            "DEAL_DEFER_MAIN=1 still exits zero at -O" + level);
        checkEq("", deferred.stderr(),
            "DEAL_DEFER_MAIN=1 writes no stderr at -O" + level);
    }

    private static void terminalProjectsACarriedEntryError(int level)
            throws IOException {
        SidecarExpectations.ErrorExpectation fields =
            new SidecarExpectations.ErrorExpectation(
                "E8010", FIXTURE_MESSAGE, "native/terminal-fixture.deal", 7, 3,
                Optional.of("string"), Optional.of("number"), Optional.of(3),
                Optional.of("E9002"));
        String expected = "DEAL_ERROR_CODE: E8010\n"
            + "DEAL_ERROR_SNAPSHOT: " + ErrorSnapshot.canonicalJson(fields) + "\n";
        Path product = buildProduct(level, "7", "terminal-entry-error-o" + level);
        if (product == null) {
            return;
        }
        NativeCompiler.Result run = NativeCompiler.execute(product, scratch, Map.of());
        checkEq(1, run.exitCode(),
            "an uncaught Error exits 1 at -O" + level + ": " + run.describe());
        checkEq(expected, run.stdout(),
            "the terminal writes the pinned framing lines with the byte-canonical "
                + "snapshot at -O" + level);
        checkEq("", run.stderr(),
            "the terminal error path writes no stderr at -O" + level);

        // The driver shape projects the same carried failure through the
        // accessors, so the accessor surface alone reproduces those bytes.
        Path driver = scratch.resolve("terminal-driver-o" + level);
        NativeCompiler.Result link = NativeCompiler.link(published, level,
            NativeCompiler.driverSources(published,
                List.of(fixture("module_stub.c"), fixture("registry_empty.c")),
                fixture("driver_main.c")), List.of("-DDEAL_STUB_CASE=7"), driver);
        check(link.succeeded(), "the driver shape builds at -O" + level + ": "
            + link.describe());
        if (!link.succeeded()) {
            return;
        }
        NativeCompiler.Result driven = NativeCompiler.execute(driver, scratch,
            Map.of());
        checkEq(1, driven.exitCode(),
            "the driver projects the carried failure with exit 1 at -O" + level);
        checkEq("load:start\nload:done\nmain:start\n" + expected, driven.stdout(),
            "the driver receipts precede the accessor projection, and the "
                + "accessor projection equals the runtime snapshot bytes at -O"
                + level + ": " + driven.describe());
        checkEq("", driven.stderr(),
            "the driver projection writes no stderr at -O" + level);
    }

    private static void terminalProjectsACarriedStartError(int level)
            throws IOException {
        SidecarExpectations.ErrorExpectation fields =
            new SidecarExpectations.ErrorExpectation(
                "E8011", "module initialization failed",
                "native/terminal-fixture.deal", 7, 3, Optional.empty(),
                Optional.empty(), Optional.of(0), Optional.empty());
        String expected = "DEAL_ERROR_CODE: E8011\n"
            + "DEAL_ERROR_SNAPSHOT: " + ErrorSnapshot.canonicalJson(fields) + "\n";
        Path product = buildProduct(level, "6", "terminal-start-error-o" + level);
        if (product == null) {
            return;
        }
        NativeCompiler.Result run = NativeCompiler.execute(product, scratch, Map.of());
        checkEq(1, run.exitCode(),
            "a failed module start exits 1 at -O" + level + ": " + run.describe());
        checkEq(expected, run.stdout(),
            "the start failure is projected through the same terminal at -O"
                + level);
        checkEq("", run.stderr(),
            "the start failure writes no stderr at -O" + level);
    }

    // =========================================================================
    // 6. Frame and root invariants
    // =========================================================================

    private static void testFrameAndRootInvariants() throws IOException {
        System.out.println("-- call-frame and root-frame invariants --");
        for (int level : LEVELS) {
            Path product = scratch.resolve("frames-o" + level);
            NativeCompiler.Result link = NativeCompiler.link(published, level,
                NativeCompiler.driverSources(published,
                    List.of(fixture("module_stub.c"), fixture("registry_empty.c")),
                    fixture("frames_driver.c")),
                List.of("-DDEAL_STUB_CASE=0", "-DDEAL_FRAME_CASE=0"), product);
            check(link.succeeded(), "the frame consumer builds at -O" + level
                + ": " + link.describe());
            if (link.succeeded()) {
                NativeCompiler.Result run = NativeCompiler.execute(product, scratch,
                    Map.of());
                checkEq(0, run.exitCode(), "the frame nesting exits zero at -O"
                    + level + ": " + run.describe());
                checkEq("frames ok\n", run.stdout(),
                    "nested call frames keep innermost-first order and the root "
                        + "depth is restored at -O" + level);
            }
            // The zero-slot shape is the first root frame the emitted skeleton
            // pushes. Its pre-guard spelling formed a zero offset from a null
            // table base and passed null to a nonnull memset while restoring
            // exactly the same depths, so the plain build above cannot observe
            // it. The supplementary UBSan build of the same published sources
            // makes the exact trigger fail: any undefined-behavior report
            // reaches stderr, and -fno-sanitize-recover turns one into a
            // nonzero exit.
            Path sanitizedProduct = scratch.resolve("frames-ubsan-o" + level);
            NativeCompiler.Result sanitizedLink = NativeCompiler.link(published,
                level, NativeCompiler.driverSources(published,
                    List.of(fixture("module_stub.c"),
                        fixture("registry_empty.c")),
                    fixture("frames_driver.c")),
                concat(List.of("-DDEAL_STUB_CASE=0", "-DDEAL_FRAME_CASE=0"),
                    NativeCompiler.SANITIZER_FLAGS),
                sanitizedProduct);
            check(sanitizedLink.succeeded(),
                "the frame consumer builds with ASan/UBSan at -O" + level + ": "
                    + sanitizedLink.describe());
            if (sanitizedLink.succeeded()) {
                NativeCompiler.SanitizedRun sanitized =
                    NativeCompiler.runSanitized(sanitizedProduct, scratch,
                        NativeCompiler.sanitizerEnvironment());
                checkEq(0, sanitized.result().exitCode(),
                    "the sanitized frame nesting exits zero at -O" + level
                        + ": " + sanitized.result().describe());
                checkEq("frames ok\n", sanitized.result().stdout(),
                    "the sanitized frame nesting completes the zero-slot and "
                        + "nested shapes at -O" + level);
                checkEq("", sanitized.result().stderr(),
                    "the zero-slot root-frame enter and leave perform no "
                        + "undefined behavior at -O" + level + ": "
                        + sanitized.result().describe());
            }
            String[][] fatal = {
                {"1", "root frame: leave with no active frame"},
                {"2", "root frame: slot write outside the active frame"},
                {"3", "root frame: slot write with no active frame"},
                {"4", "call frame: pop with no active frame"},
                {"5", "root frame: slot write outside the active frame"},
            };
            for (String[] entry : fatal) {
                Path fatalProduct = scratch.resolve(
                    "frames-fatal-" + entry[0] + "-o" + level);
                NativeCompiler.Result fatalLink = NativeCompiler.link(published,
                    level, NativeCompiler.driverSources(published,
                        List.of(fixture("module_stub.c"),
                            fixture("registry_empty.c")),
                        fixture("frames_driver.c")),
                    List.of("-DDEAL_STUB_CASE=0",
                        "-DDEAL_FRAME_CASE=" + entry[0]), fatalProduct);
                check(fatalLink.succeeded(),
                    "the frame fault case builds at -O" + level + ": "
                        + fatalLink.describe());
                if (!fatalLink.succeeded()) {
                    continue;
                }
                NativeCompiler.Result run = NativeCompiler.execute(fatalProduct,
                    scratch, Map.of());
                checkEq(2, run.exitCode(),
                    "frame case " + entry[0] + " exits 2 at -O" + level + ": "
                        + run.describe());
                checkEq("", run.stdout(),
                    "frame case " + entry[0] + " writes no stdout at -O" + level);
                checkEq(RUNTIME_FAULT_PREFIX + entry[1] + "\n", run.stderr(),
                    "frame case " + entry[0] + " writes exactly one "
                        + "deterministic stderr line at -O" + level);
            }
        }
    }

    // =========================================================================
    // 7. The completion queue
    // =========================================================================

    private static void testCompletionQueue() throws IOException {
        System.out.println("-- the completion queue --");
        String platformHeader = Files.readString(
            published.resolve("deal/native/platform.h"), StandardCharsets.UTF_8);
        check(platformHeader.contains(
                "typedef struct deal_completion_frame deal_completion_frame;"),
            "the published frame type is opaque");
        check(!platformHeader.contains("struct deal_completion_frame {"),
            "no published header declares the frame layout");
        check(!platformHeader.contains("deal_value")
                && !platformHeader.contains("deal_error_token")
                && !platformHeader.contains("deal_error "),
            "the queue surface cannot name a managed carrier or an error token");
        check(platformHeader.contains(
                "unsigned char *deal_platform_frame_payload(deal_completion_frame "
                    + "*frame, size_t size);"),
            "the frame payload surface is byte-typed");

        for (int level : LEVELS) {
            Path product = scratch.resolve("queue-o" + level);
            NativeCompiler.Result link = NativeCompiler.link(published, level,
                NativeCompiler.driverSources(published,
                    List.of(fixture("module_stub.c"), fixture("registry_empty.c")),
                    fixture("queue_probe.c")),
                List.of("-DDEAL_STUB_CASE=0", "-DDEAL_QUEUE_CASE=0"), product);
            check(link.succeeded(), "the queue consumer builds at -O" + level
                + ": " + link.describe());
            if (link.succeeded()) {
                NativeCompiler.Result run = NativeCompiler.execute(product, scratch,
                    Map.of());
                checkEq(0, run.exitCode(),
                    "the concurrent queue run exits zero at -O" + level + ": "
                        + run.describe());
                checkEq("queue ok\n", run.stdout(),
                    "concurrent producers deliver once each, payloads survive "
                        + "their inputs and the bounded wait returns at -O"
                        + level);
                checkEq("", run.stderr(),
                    "the queue run keeps stderr empty at -O" + level);
            }
            String[][] fatal = {
                {"1", "completion take from a thread that is not the waiting thread"},
                {"2", "completion frame released while queued"},
                {"3", "size arithmetic: addition overflow"},
            };
            for (String[] entry : fatal) {
                Path fatalProduct = scratch.resolve(
                    "queue-fatal-" + entry[0] + "-o" + level);
                NativeCompiler.Result fatalLink = NativeCompiler.link(published,
                    level, NativeCompiler.driverSources(published,
                        List.of(fixture("module_stub.c"),
                            fixture("registry_empty.c")),
                        fixture("queue_probe.c")),
                    List.of("-DDEAL_STUB_CASE=0",
                        "-DDEAL_QUEUE_CASE=" + entry[0]), fatalProduct);
                check(fatalLink.succeeded(),
                    "the queue fault case builds at -O" + level + ": "
                        + fatalLink.describe());
                if (!fatalLink.succeeded()) {
                    continue;
                }
                NativeCompiler.Result run = NativeCompiler.execute(fatalProduct,
                    scratch, Map.of());
                checkEq(2, run.exitCode(),
                    "queue case " + entry[0] + " exits 2 at -O" + level + ": "
                        + run.describe());
                checkEq("", run.stdout(),
                    "queue case " + entry[0] + " writes no stdout at -O" + level);
                checkEq(RUNTIME_FAULT_PREFIX + entry[1] + "\n", run.stderr(),
                    "queue case " + entry[0] + " writes exactly one "
                        + "deterministic stderr line at -O" + level);
            }
        }
    }

    // =========================================================================
    // 7b. The supplementary instrumented queue probe
    // =========================================================================

    /**
     * The ThreadSanitizer half of R1 Verification 9: the published
     * {@code deal/native/platform.c} and the concurrent queue driver
     * ({@code queue_race_probe.c}) are compiled into one sanitizer-instrumented
     * product at {@code -O2} with {@code -fsanitize=thread -g}, and the same
     * producer/post/take/free workload must exit zero with the accepted output
     * and no ThreadSanitizer report. The production builds of this suite keep
     * the pinned strict-C11 flag list unchanged; only this supplementary probe
     * carries the instrumented variant.
     *
     * <p>The probe translation unit refuses to compile without the sanitizer
     * ({@code #error} on a missing {@code __SANITIZE_THREAD__}), and a
     * successful link proves the sanitizer runtime resolved, so an
     * uninstrumented or partially instrumented build cannot pass as this
     * evidence.</p>
     *
     * <p>Two host properties are accommodated without weakening an assertion:
     * the probe's producer threads are POSIX threads, because glibc's
     * {@code thrd_create} enters pthread creation through the internal alias
     * the sanitizer does not intercept (a C11-created thread would run without
     * a registered shadow stack and abort inside the thread start); and a host
     * whose address-space-randomization entropy puts the sanitizer's shadow
     * mapping out of reach cannot start any instrumented process, so the same
     * instrumented product is relaunched with randomization disabled for that
     * child only. Both are properties of the sanitizer runtime, not of the
     * queue, and every acceptance assertion is still applied to the run.</p>
     */
    private static void testCompletionQueueRaceProbe() throws IOException {
        System.out.println("-- the instrumented completion-queue probe --");
        Path product = scratch.resolve("queue-race-probe");
        NativeCompiler.Result link = NativeCompiler.link(published, 2,
            List.of(published.resolve("deal/native/platform.c"),
                fixture("queue_race_probe.c")),
            List.of("-fsanitize=thread", "-g"), product);
        check(link.succeeded(), "the supplementary queue probe builds the "
            + "published platform unit with -fsanitize=thread -g: "
            + link.describe());
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
                + "address-space randomization disabled on this host; "
                + "the same instrumented product was relaunched)");
        }
        String where = relaunched
            ? " (relaunched with address-space randomization disabled for the "
                + "child only)" : "";
        checkEq(0, run.exitCode(), "the instrumented concurrent queue workload "
            + "exits zero" + where + ": " + run.describe());
        checkEq("queue ok\n", run.stdout(), "the instrumented concurrent queue "
            + "workload delivers every copied payload exactly once" + where);
        checkEq("", run.stderr(), "the instrumented concurrent queue workload "
            + "produces no ThreadSanitizer report" + where);
    }

    // =========================================================================
    // 8. Symbol ownership in both build shapes
    // =========================================================================

    private static void testSymbolOwnership() throws IOException {
        System.out.println("-- entry-surface symbol ownership --");
        List<Path> module = List.of(fixture("module_stub.c"),
            fixture("registry_empty.c"));

        // The product shape: the process entry is main.c's.
        Path product = scratch.resolve("ownership-product");
        NativeCompiler.Result productLink = NativeCompiler.link(published, 2,
            NativeCompiler.productSources(published, module), product);
        check(productLink.succeeded(), "the product shape links with exactly one "
            + "process entry: " + productLink.describe());
        if (productLink.succeeded()) {
            NativeCompiler.Result run = NativeCompiler.execute(product, scratch,
                Map.of());
            checkEq(0, run.exitCode(), "the product build runs: " + run.describe());
            checkEq("", run.stdout(), "the product build produces no output");
        }

        // The driver shape: the process entry is the driver unit's.
        Path driver = scratch.resolve("ownership-driver");
        NativeCompiler.Result driverLink = NativeCompiler.link(published, 2,
            NativeCompiler.driverSources(published, module,
                fixture("driver_main.c")), driver);
        check(driverLink.succeeded(), "the driver shape links with exactly one "
            + "process entry: " + driverLink.describe());
        if (driverLink.succeeded()) {
            NativeCompiler.Result run = NativeCompiler.execute(driver, scratch,
                Map.of());
            checkEq(0, run.exitCode(), "the driver build runs: " + run.describe());
            checkEq("load:start\nload:done\nmain:start\nmain:done\n", run.stdout(),
                "the driver writes load:start before the module walk and "
                    + "load:done before the probes");
        }

        // Two process entries never link.
        Path twoEntries = scratch.resolve("ownership-two-entries");
        NativeCompiler.Result twoEntryLink = NativeCompiler.link(published, 2,
            withExtra(NativeCompiler.productSources(published, module),
                fixture("driver_main.c")), twoEntries);
        check(!twoEntryLink.succeeded(),
            "two process entries fail the link: " + twoEntryLink.describe());
        check(twoEntryLink.stderr().contains("multiple definition")
                && twoEntryLink.stderr().contains("main"),
            "the two-entry failure names the process entry: "
                + twoEntryLink.stderr());

        // The runtime owners are single: entry.c (terminal), error.c
        // (accessors and frames), memory.c (root frames).
        String[][] owned = {
            {"deal/native/entry.c", "deal_project_main"},
            {"deal/native/error.c", "deal_call_frame_push"},
            {"deal/native/memory.c", "deal_root_frame_enter"},
        };
        for (String[] entry : owned) {
            Path duplicate = scratch.resolve("ownership-duplicate-"
                + Path.of(entry[0]).getFileName());
            NativeCompiler.Result duplicateLink = NativeCompiler.link(published, 2,
                withExtra(NativeCompiler.productSources(published, module),
                    published.resolve(entry[0])), duplicate);
            check(!duplicateLink.succeeded(),
                "a second definition of " + entry[0] + " fails the link: "
                    + duplicateLink.describe());
            check(duplicateLink.stderr().contains("multiple definition")
                    && duplicateLink.stderr().contains(entry[1]),
                "the duplicate owner failure names " + entry[1] + ": "
                    + duplicateLink.stderr());
        }

        // A driver that drives the whole export surface links against the
        // complete module stub ...
        Path exports = scratch.resolve("ownership-exports");
        NativeCompiler.Result exportsLink = NativeCompiler.link(published, 2,
            NativeCompiler.driverSources(published, module,
                fixture("export_probe.c")), exports);
        check(exportsLink.succeeded(),
            "a driver may call all six export-surface functions: "
                + exportsLink.describe());
        if (exportsLink.succeeded()) {
            NativeCompiler.Result run = NativeCompiler.execute(exports, scratch,
                Map.of());
            checkEq(0, run.exitCode(), "the export-surface driver runs: "
                + run.describe());
            checkEq("exports ok\n", run.stdout(),
                "every export-surface function is callable once");
        }

        // ... and a module that omits five of them fails the link, naming each.
        Path partial = scratch.resolve("ownership-partial-module");
        NativeCompiler.Result partialLink = NativeCompiler.link(published, 2,
            NativeCompiler.driverSources(published,
                List.of(fixture("module_stub_partial.c"),
                    fixture("registry_empty.c")),
                fixture("export_probe.c")), partial);
        check(!partialLink.succeeded(),
            "a module that omits export-surface definitions fails the link: "
                + partialLink.describe());
        for (String symbol : List.of("deal_project_run_main", "deal_project_exports",
                "deal_export_lookup", "deal_export_invoke",
                "deal_async_export_invoke")) {
            check(partialLink.stderr().contains(symbol),
                "the missing-definition failure names " + symbol + ": "
                    + partialLink.stderr());
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /** Builds the product shape for one module-stub case; null on failure. */
    private static Path buildProduct(int level, String stubCase, String name)
            throws IOException {
        Path product = scratch.resolve(name);
        NativeCompiler.Result link = NativeCompiler.link(published, level,
            NativeCompiler.productSources(published,
                List.of(fixture("module_stub.c"), fixture("registry_empty.c"))),
            List.of("-DDEAL_STUB_CASE=" + stubCase), product);
        check(link.succeeded(),
            "the product shape builds (module case " + stubCase + ") at -O"
                + level + ": " + link.describe());
        return link.succeeded() ? product : null;
    }

    private static List<Path> withExtra(List<Path> sources, Path extra) {
        List<Path> result = new ArrayList<>(sources);
        result.add(extra);
        return result;
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
