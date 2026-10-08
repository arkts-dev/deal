package deal.test;

import deal.checker.BuiltinErrorDeclaration;
import deal.codegen.Backend;
import deal.codegen.jvm.JvmBackend;
import deal.codegen.jvm.JvmSemanticEmitter;
import deal.codegen.lua.LuaSemanticEmitter;
import deal.diagnostics.CompilerDiagnostic;
import deal.distribution.DistributionHome;
import deal.module.CompilationOrchestrator;
import deal.module.ProductionProjectEmission;
import deal.project.ProjectContext;
import deal.project.ProjectLocator;
import deal.publication.PublicationStager;
import deal.semantic.CheckedModuleInput;
import deal.semantic.CheckedProjectBuildResult;
import deal.semantic.CheckedProjectInput;
import deal.semantic.CompilerInvocation;
import deal.semantic.CompilerProfileProvider;
import deal.semantic.HostDeclarationSurface;
import deal.semantic.ReleaseConfiguration;
import deal.semantic.RequirementManifestResult;
import deal.semantic.SemanticLowerer;
import deal.semantic.SemanticOracle;
import deal.semantic.SemanticRuntimeModel;
import deal.semantic.ir.BlockId;
import deal.semantic.ir.CallMode;
import deal.semantic.ir.ExecutableLoweredProject;
import deal.semantic.ir.ExportInterface;
import deal.semantic.ir.FunctionAllocationIdentity;
import deal.semantic.ir.FunctionExecutionBinding;
import deal.semantic.ir.FunctionId;
import deal.semantic.ir.OpId;
import deal.semantic.ir.IntrinsicKind;
import deal.semantic.ir.KindPayload;
import deal.semantic.ir.LoweredFunction;
import deal.semantic.ir.LoweredModuleUnit;
import deal.semantic.ir.ModuleId;
import deal.semantic.ir.SemanticOp;
import deal.semantic.ir.SemanticOpKind;
import deal.semantic.ir.StructuredBodyTable;
import deal.semantic.ir.ValueId;
import deal.test.conformance.ErrorSnapshot;
import deal.test.conformance.SidecarExpectations;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * ISSUE-0698: the {@code @jsonable} helper family's production drive
 * ({@code dispatched-corpus-production-realization} R3 and the helper
 * contract; ISSUE-0698's criteria).
 *
 * <p>One drive compiles every fixture of the family through the
 * release-owned production invocation on LuaJIT and JVM and executes each
 * published artifact under its real toolchain: the 30 sidecar fixtures of
 * {@code backend-runtime/jsonable/**}, the two structural fixtures
 * ({@code lua-abi/jsonable-helper-name-near-collision},
 * {@code lua-abi-structural/jsonable-helper-export-keys}), and the
 * family's pinned compile-error fixture
 * ({@code jsonable-minimal-table-nested-array-access}, E3007 at the
 * frontend — no sidecar). The {@code runtime-ok} fixtures must publish
 * exit 0 with empty transcripts; the pinned {@code runtime-error} fixture
 * ({@code jsonable-tojson-rejects-cyclic-table}) must reproduce its
 * sidecar's {@code code}, {@code message}, span group, and
 * {@code expected}/{@code actual} byte-exact, with the materialized line
 * rebased to the raw corpus coordinate.</p>
 *
 * <p>The drive also pins the helper surface in the produced unit: exactly
 * one closure identity and one {@code LoweredBody} binding per class
 * helper, one {@code EXPORT_PUBLISH} and one recorded
 * {@code EXTERNAL_ENTRY} per exported helper, the same-module call that
 * resolves the declaration's generated closure identity through the
 * landed {@code LoweredBody} call shape, and the cross-module call bound
 * through {@code EXPORT_READ} plus the closed {@code ExternalFunction}
 * shape to the callee's recorded entry. A declared class that is not
 * {@code @jsonable} gains no helper, and a user identifier near a helper
 * name changes no outcome.</p>
 *
 * <p>The three-consumer regressions close the walk's remaining arms: a
 * function-parameter invocation of a helper ({@code f(w)} inside the
 * callee body) must render the pinned JSON failure at the {@code f(w)}
 * call expression in the oracle, the LuaJIT artifact, and the JVM
 * artifact alike; a class instance inside an array nested in a table
 * field must be rejected by all three consumers (the table-content walk
 * stays JSON-shaped); a nested class's table field must spell its keys in
 * first-insertion order and its int/number leaves by their own variant
 * (the exact text compared on all three consumers); the recursive
 * nested-class walk must stop at the shared JSON depth bound (511 chain
 * links succeed, 512 links fail through the walk arm with the exceeding
 * container's token and path); the oracle's array executor view must stay
 * live behind a class field's in-place element replacement, append, and
 * deletion (exact text for replacement and append, outcome parity for the
 * deleted missing slot); a null element in {@code (int | null)[]} must
 * roundtrip on all three consumers while the same document stays rejected
 * by {@code int[]}; a read-derived numeric variant carrier must
 * serialize at a declared {@code number} field and array element with its
 * own variant's spelling, and the same carrier produced by an omitted
 * nested-class default must conform at its declared {@code number},
 * {@code number | null}, and {@code number[]} positions (with its
 * explicit-value control). The pinned-failure leg's comparison is itself
 * covered by negative controls: unexpected process stdout, an incorrect
 * process exit status, or wrong stderr is rejected.</p>
 *
 * <p>The combined dependency step runs the oracle over every fixture of
 * the family through the same one-lowering closure (the runtime-ok
 * success or the pinned error tuple at the raw corpus coordinates), and
 * captures the cross-module helper call and the pinned helper JSON
 * failure from the lowered project through {@link SemanticOracle}, so the
 * comparison fails if the canonical failure projection authority is
 * broken. A fixture's {@code main} runs through the oracle's main leg (the
 * fixture itself lowered as the entry, so its own entry delegation invokes
 * {@code main} exactly once — the artifact probes' sequence) before the
 * export leg's ordered non-main export loop, and a main-only control
 * proves the leg captures a deliberate main failure. The inconsistent-fact
 * negatives cover the missing generated helper export and the missing
 * recorded callee entry: the hand-built checked-project fact fails the
 * real production arm closed with one E6005 through the producer guard and
 * stages nothing on both targets, a checker-valid cross-module call to an
 * entry-delegation-owned export fails the arm closed the same way, and the
 * same helper facts at the lowered-IR level (a removed
 * {@code EXPORT_PUBLISH} publication and a removed recorded
 * {@code EXTERNAL_ENTRY}) are rejected by both production emitters with
 * the named producer defect and stage nothing.</p>
 */
public class JsonableHelperProductionDriveTest {

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
        check(java.util.Objects.equals(expected, actual),
            message + " (expected " + expected + ", got " + actual + ")");
    }

    private static void fail(String message) {
        failed++;
        System.err.println("FAIL: " + message);
    }

    // =========================================================================
    // The family
    // =========================================================================

    private static final Path CONFORMANCE = Path.of("test", "conformance");
    private static final String FAMILY_DIR = "backend-runtime/jsonable";
    private static final String NEAR_COLLISION =
        "backend-runtime/lua-abi/jsonable-helper-name-near-collision";
    private static final String EXPORT_KEYS =
        "backend-runtime/lua-abi-structural/jsonable-helper-export-keys";
    private static final String COMPILE_REJECT =
        FAMILY_DIR + "/jsonable-minimal-table-nested-array-access";

    /** The pinned 30-fixture sidecar family (this revision). */
    private static final List<String> FAMILY_FIXTURES = List.of(
        "fromjson-extra-key-runtime",
        "fromjson-failure-no-partial-object",
        "jsonable-complex-roundtrip",
        "jsonable-cross-module",
        "jsonable-cross-module-nested-class-array",
        "jsonable-empty-array-accepted",
        "jsonable-fromjson",
        "jsonable-fromjson-extra-keys",
        "jsonable-fromjson-nested-depth3",
        "jsonable-fromjson-null",
        "jsonable-fromjson-top-level-scalar",
        "jsonable-helper-exports",
        "jsonable-local-nested-class-array",
        "jsonable-malformed-input",
        "jsonable-minimal-nested-class-array-access",
        "jsonable-nested",
        "jsonable-nested-array-extra-key",
        "jsonable-nested-array-malformed-element",
        "jsonable-null-field-accepts-null",
        "jsonable-null-field-rejects-value",
        "jsonable-optional-nullable",
        "jsonable-optional-nullable-nested-class",
        "jsonable-roundtrip",
        "jsonable-table-field-nested-arrays",
        "jsonable-tojson",
        "jsonable-tojson-omits-missing",
        "jsonable-tojson-rejects-cyclic-table",
        "nested-array-roundtrip",
        "optional-nullable-three-state-roundtrip",
        "table-field-roundtrip");

    /** The family's companions (relative imports; no sidecar of their own). */
    private static final List<String> FAMILY_COMPANIONS = List.of(
        "jsonable_batch2_lib", "jsonable_complex_lib", "jsonable_lib");

    /** The pinned runtime-error fixture (the toJson call origin pin). */
    private static final String CYCLIC_FIXTURE =
        FAMILY_DIR + "/jsonable-tojson-rejects-cyclic-table";

    /** The cross-module helper call fixture. */
    private static final String CROSS_MODULE_FIXTURE = FAMILY_DIR + "/jsonable-cross-module";

    private enum Target {
        LUAJIT("luajit"),
        JVM("jvm");

        private final String laneName;

        Target(String laneName) {
            this.laneName = laneName;
        }

        String laneName() {
            return laneName;
        }
    }

    private static CompilerInvocation productionInvocation() {
        return CompilerProfileProvider.resolve(
            ReleaseConfiguration.CURRENT_RELEASE_STATE,
            ReleaseConfiguration.releaseCapabilityRegistry());
    }

    // =========================================================================
    // 1. The corpus inventory
    // =========================================================================

    private static void testCorpusInventory() throws Exception {
        System.out.println("-- the @jsonable helper family inventory: the pinned "
            + "30-fixture slate, the companions, and the structural fixtures --");
        List<String> fixtures = new ArrayList<>();
        List<String> sidecars = new ArrayList<>();
        try (Stream<Path> entries = Files.list(CONFORMANCE.resolve(FAMILY_DIR))) {
            for (Path file : entries.sorted().toList()) {
                String name = file.getFileName().toString();
                if (name.endsWith(".deal")) {
                    fixtures.add(name.substring(0, name.length() - ".deal".length()));
                } else if (name.endsWith(".expect.json")) {
                    sidecars.add(name.substring(0,
                        name.length() - ".expect.json".length()));
                }
            }
        }
        List<String> pinnedFixtures = new ArrayList<>(FAMILY_FIXTURES);
        pinnedFixtures.addAll(FAMILY_COMPANIONS);
        pinnedFixtures.add("jsonable-minimal-table-nested-array-access");
        pinnedFixtures.sort(String::compareTo);
        fixtures.sort(String::compareTo);
        checkEq(pinnedFixtures, fixtures,
            "the jsonable directory carries exactly the pinned fixture inventory");
        List<String> pinnedSidecars = new ArrayList<>(FAMILY_FIXTURES);
        pinnedSidecars.sort(String::compareTo);
        sidecars.sort(String::compareTo);
        checkEq(pinnedSidecars, sidecars,
            "the jsonable directory carries exactly one sidecar per driven fixture");
        checkEq(30, FAMILY_FIXTURES.size(), "the family is the pinned 30-fixture slate");
        for (String fixture : FAMILY_FIXTURES) {
            String path = FAMILY_DIR + "/" + fixture;
            check(Files.isRegularFile(CONFORMANCE.resolve(path + ".deal")),
                path + " is a corpus fixture");
            check(Files.isRegularFile(CONFORMANCE.resolve(path + ".expect.json")),
                path + " carries its sidecar");
        }
        check(Files.isRegularFile(CONFORMANCE.resolve(NEAR_COLLISION + ".deal")),
            "the near-collision structural fixture exists");
        check(Files.isRegularFile(CONFORMANCE.resolve(EXPORT_KEYS + ".deal")),
            "the helper-export-keys structural fixture exists");
    }

    // =========================================================================
    // 2. The lane-equivalent materialization
    // =========================================================================

    private record Materialized(String corpusPath, String source,
                                int headerLinesStripped) {
    }

    private record Project(Path root, Path srcRoot, Path entryFile, String modulePath,
                           Map<String, Materialized> modules, Path outputRoot) {

        Materialized moduleOf(String capturedFile) {
            if (capturedFile == null) {
                return null;
            }
            Materialized direct = modules.get(capturedFile);
            if (direct != null) {
                return direct;
            }
            return modules.get(Path.of(capturedFile).toAbsolutePath().normalize()
                .toString());
        }
    }

    /**
     * Materializes one fixture as the lane does: the fixture is the entry
     * module, its transitively imported relative companions sit at the
     * entry-directory-relative layout, the classification headers are
     * stripped (the deployment map's rebase delta), and one generated
     * {@code deal.json} wires the module root, the output, and the target
     * backend. The family imports no host or extern-C declarations, so the
     * externals surface is empty.
     */
    private static Project materialize(String fixtureRel, Target target)
            throws Exception {
        Path root = Files.createTempDirectory("jsonable-prod-drive-");
        Path srcRoot = root.resolve("src");
        Files.createDirectories(srcRoot);
        String corpusDir = fixtureRel.substring(0,
            fixtureRel.lastIndexOf('/') + 1);
        Path entryCorpus = CONFORMANCE.resolve(fixtureRel + ".deal")
            .toAbsolutePath().normalize();
        Path entryDir = entryCorpus.getParent();
        Map<String, Materialized> modules = new LinkedHashMap<>();
        for (String corpusPath : relativeClosureOf(corpusDir, fixtureRel)) {
            Path corpusFile = CONFORMANCE.resolve(corpusPath);
            String raw = Files.readString(corpusFile, StandardCharsets.UTF_8);
            String stripped = ConformanceHarnessMetadata
                .stripClassificationHeaders(raw);
            int headers = raw.split("\n", -1).length - stripped.split("\n", -1).length;
            String fileName = corpusFile.getFileName().toString();
            Path targetPath = srcRoot.resolve(fileName);
            Files.writeString(targetPath, stripped, StandardCharsets.UTF_8);
            modules.put(targetPath.toAbsolutePath().normalize().toString(),
                new Materialized(corpusPath, stripped, headers));
        }
        String entryStem = moduleNameOf(entryCorpus.getFileName().toString());
        String dealJson = "{\n  \"languageVersion\": \"1.2\",\n"
            + "  \"moduleRoots\": [\"src\"],\n"
            + "  \"output\": \"out\",\n"
            + "  \"backend\": \"" + (target == Target.JVM ? "jvm" : "luajit")
            + "\"\n}\n";
        Files.writeString(root.resolve("deal.json"), dealJson);
        return new Project(root, srcRoot, srcRoot.resolve(entryStem + ".deal"),
            entryStem, modules, root.resolve("out"));
    }

    /**
     * The fixture's transitively imported relative companion closure: the
     * corpus-relative {@code .deal} paths, dependency-first.
     */
    private static List<String> relativeClosureOf(String corpusDir, String rootCorpusPath)
            throws Exception {
        List<String> ordered = new ArrayList<>();
        collectRelativeClosure(corpusDir, rootCorpusPath + ".deal", ordered,
            new LinkedHashSet<>());
        return ordered;
    }

    private static void collectRelativeClosure(String corpusDir, String corpusPath,
            List<String> ordered, Set<String> seen) throws Exception {
        if (!seen.add(corpusPath)) {
            return;
        }
        String stripped = ConformanceHarnessMetadata.stripClassificationHeaders(
            Files.readString(CONFORMANCE.resolve(corpusPath), StandardCharsets.UTF_8));
        for (String importPath : relativeImportPaths(stripped)) {
            String candidate = corpusDir + importPath + ".deal";
            if (Files.isRegularFile(CONFORMANCE.resolve(candidate))) {
                collectRelativeClosure(corpusDir, candidate, ordered, seen);
            }
        }
        ordered.add(corpusPath);
    }

    private static List<String> relativeImportPaths(String source) {
        List<String> paths = new ArrayList<>();
        Matcher matcher = Pattern
            .compile("^import \\* as \\w+ from \"\\./([^\"]+)\"$", Pattern.MULTILINE)
            .matcher(source);
        while (matcher.find()) {
            paths.add(matcher.group(1));
        }
        return paths;
    }

    private static String moduleNameOf(String fileName) {
        if (fileName.endsWith(".deal")) {
            return fileName.substring(0, fileName.length() - ".deal".length());
        }
        return fileName;
    }

    // =========================================================================
    // 3. The production compile and the deferred-entry drive
    // =========================================================================

    private static boolean compile(Project project, String fixtureRel, Target target,
            List<String> diagnosticsOut) throws Exception {
        ProjectLocator.LocateResult located = ProjectLocator.locate(
            project.entryFile().toString(), null);
        if (located.context() == null) {
            check(false, fixtureRel + " [" + target.laneName()
                + "]: the generated deal.json locates strictly");
            return false;
        }
        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            located.context(), project.entryFile(), false, false, false, false,
            null, productionInvocation());
        boolean compiled = orchestrator.compile();
        for (CompilerDiagnostic diagnostic : orchestrator.diagnostics()) {
            if ("error".equals(diagnostic.severity())) {
                diagnosticsOut.add(diagnostic.code() + " " + diagnostic.message());
            }
        }
        return compiled;
    }

    private static Path artifactOf(Project project, Target target) {
        String name = target == Target.JVM
            ? JvmBackend.classNameFor(project.modulePath()) + ".java"
            : project.modulePath() + ".lua";
        return project.outputRoot().resolve(name);
    }

    private record Execution(int exitCode, String stdout, String stderr,
                             Capture capture, boolean probeDefect) {
    }

    // =========================================================================
    // 3b. The bounded real-toolchain runner (the release-pipeline contract)
    // =========================================================================

    /**
     * The bounded-subprocess contract of the release pipeline (the
     * verified bounded-subprocess pages): every gate-executed real-toolchain
     * invocation drains both streams concurrently with a cap, runs under one
     * monotonic deadline, owns its whole process tree on every exit path,
     * and reports an exceeded deadline as the named hard
     * {@link BoundedProcessTimeoutException}. The runner starts each child
     * through {@code setsid} (when available) so the whole owned tree can be
     * killed even after the direct child exits and a descendant is
     * reparented.
     */
    private static final int STREAM_CAP_BYTES = 1 << 20;

    private static final String TRUNCATION_MARKER = "\n[STREAM TRUNCATED at 1 MiB]\n";

    private static final long BOUNDED_PROCESS_BUDGET_MS = 300_000L;

    private static final long POST_KILL_DRAIN_BUDGET_MS = 5_000L;

    private static final long REAP_BUDGET_MS = 5_000L;

    /** A hard gate failure, never a skip: an {@link AssertionError} so no
     * probe-style {@code catch (Exception)} guard reads a hung toolchain as
     * absent. */
    private static final class BoundedProcessTimeoutException extends AssertionError {
        private static final long serialVersionUID = 1L;

        BoundedProcessTimeoutException(String message) {
            super(message);
        }
    }

    /** One stream's capped, concurrent drain: the first
     * {@link #STREAM_CAP_BYTES} bytes are retained and the read continues to
     * EOF, so a child that saturates a pipe can never deadlock the harness. */
    private static final class CappedDrain implements Runnable {

        private final java.io.InputStream stream;
        private final java.io.ByteArrayOutputStream retained =
            new java.io.ByteArrayOutputStream();
        private volatile boolean truncated;

        CappedDrain(java.io.InputStream stream) {
            this.stream = stream;
        }

        @Override
        public void run() {
            byte[] buffer = new byte[8192];
            try (java.io.InputStream in = stream) {
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    int kept = Math.min(read, STREAM_CAP_BYTES - retained.size());
                    if (kept > 0) {
                        retained.write(buffer, 0, kept);
                    }
                    if (kept < read) {
                        truncated = true;
                    }
                }
            } catch (java.io.IOException ignored) {
                // The stream ends when the child and its descendants are gone.
            }
        }

        String text() {
            String drained = new String(retained.toByteArray(), StandardCharsets.UTF_8);
            return truncated ? drained + TRUNCATION_MARKER : drained;
        }
    }

    private record ProcessOutcome(int exitCode, String stdout, String stderr) {
    }

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
     * The bounded real-toolchain runner: separate stdout/stderr
     * transcripts, both streams drained concurrently with the canonical
     * 1 MiB cap, the canonical 300000 ms deadline, and on timeout
     * descendants-then-group-then-child forcible termination plus a drain
     * to EOF and a reap, reported as the named hard
     * {@link BoundedProcessTimeoutException}. One monotonic deadline covers
     * the direct child and the drainage of both streams. The child is owned
     * for the whole call: every path out of the wait — the child's
     * completion, the deadline, an interruption of the waiting thread, or
     * any other failure — terminates the whole owned tree, drains both
     * streams to EOF, and reaps the direct child before the outcome returns
     * or propagates.
     */
    private static ProcessOutcome runProcess(Path directory, Map<String, String> env,
            long budgetMs, String... command) throws Exception {
        boolean ownGroup = SETSID_BINARY != null;
        List<String> argv = new ArrayList<>();
        if (ownGroup) {
            argv.add(SETSID_BINARY);
        }
        argv.addAll(java.util.Arrays.asList(command));
        ProcessBuilder builder = new ProcessBuilder(argv);
        builder.directory(directory.toFile());
        builder.redirectErrorStream(false);
        builder.environment().putAll(env);
        long deadlineNanos = System.nanoTime()
            + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(budgetMs);
        Process process = builder.start();
        CappedDrain stdout = new CappedDrain(process.getInputStream());
        CappedDrain stderr = new CappedDrain(process.getErrorStream());
        Thread stdoutThread = new Thread(stdout, "jsonable-stdout-" + command[0]);
        Thread stderrThread = new Thread(stderr, "jsonable-stderr-" + command[0]);
        stdoutThread.setDaemon(true);
        stderrThread.setDaemon(true);
        stdoutThread.start();
        stderrThread.start();
        boolean[] interrupted = {false};
        boolean finished = false;
        boolean drained = false;
        String timeout = null;
        try {
            finished = waitForChild(process, deadlineNanos, interrupted);
            // The same deadline bounds the child and its stream drainage: a
            // reparented descendant that holds the inherited pipes ends the
            // invocation with the named timeout, never with a delay.
            drained = awaitDrains(stdoutThread, stderrThread, deadlineNanos, interrupted);
            if (!finished) {
                timeout = "BOUNDED_PROCESS_TIMEOUT " + command[0];
            } else if (!drained) {
                timeout = "BOUNDED_PROCESS_TIMEOUT " + command[0]
                    + " (the stream drainage exceeded the child deadline)";
            }
        } finally {
            // The finally owns the whole tree before any outcome propagates.
            terminateTree(process, ownGroup, interrupted);
            awaitDrains(stdoutThread, stderrThread, System.nanoTime()
                + java.util.concurrent.TimeUnit.MILLISECONDS
                    .toNanos(POST_KILL_DRAIN_BUDGET_MS), interrupted);
            reap(process, interrupted);
        }
        if (interrupted[0]) {
            Thread.interrupted();
            throw new InterruptedException("interrupted while waiting for " + command[0]);
        }
        if (timeout != null) {
            throw new BoundedProcessTimeoutException(timeout);
        }
        return new ProcessOutcome(process.exitValue(), stdout.text(), stderr.text());
    }

    /** Waits for the direct child inside the one deadline; {@code false}
     * means the deadline expired. */
    private static boolean waitForChild(Process process, long deadlineNanos,
            boolean[] interrupted) throws InterruptedException {
        long remaining = deadlineNanos - System.nanoTime();
        if (remaining <= 0) {
            return false;
        }
        try {
            return process.waitFor(remaining, java.util.concurrent.TimeUnit.NANOSECONDS);
        } catch (InterruptedException interruptedWait) {
            interrupted[0] = true;
            throw interruptedWait;
        }
    }

    /** Terminates the owned tree: descendants first, then the child's process
     * group (when the runner created one), then the direct child. */
    private static void terminateTree(Process process, boolean ownGroup,
            boolean[] interrupted) {
        try {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
        } catch (RuntimeException ignored) {
            // A vanished subtree is already gone; the direct child is next.
        }
        if (ownGroup) {
            killProcessGroup(process.pid(), interrupted);
        }
        process.destroyForcibly();
    }

    /** Kills every remaining member of the child's own process group from
     * outside the group; the helper's own wait is bounded. */
    private static void killProcessGroup(long groupId, boolean[] interrupted) {
        List<String> argv = KILL_BINARY != null
            ? List.of(KILL_BINARY, "-KILL", "--", "-" + groupId)
            : List.of("bash", "-c", "kill -KILL -- -\"$1\"", "jsonable-kill-group",
                Long.toString(groupId));
        try {
            ProcessBuilder killerBuilder = new ProcessBuilder(argv);
            killerBuilder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            killerBuilder.redirectError(ProcessBuilder.Redirect.DISCARD);
            Process killer = killerBuilder.start();
            boolean killed = false;
            try {
                killed = killer.waitFor(POST_KILL_DRAIN_BUDGET_MS,
                    java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException interruptedKill) {
                interrupted[0] = true;
            }
            if (!killed) {
                killer.destroyForcibly();
            }
        } catch (Exception ignored) {
            // The direct child's destroyForcibly remains the backstop.
        }
    }

    /** Joins both capped drains to EOF inside the deadline; {@code false}
     * means a drain was still alive at the deadline. */
    private static boolean awaitDrains(Thread stdoutThread, Thread stderrThread,
            long deadlineNanos, boolean[] interrupted) {
        boolean drained = true;
        for (Thread thread : new Thread[] {stdoutThread, stderrThread}) {
            while (thread.isAlive()) {
                long remaining = deadlineNanos - System.nanoTime();
                if (remaining <= 0) {
                    drained = false;
                    break;
                }
                try {
                    thread.join(Math.max(1L,
                        java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(remaining)));
                } catch (InterruptedException interruptedJoin) {
                    interrupted[0] = true;
                }
            }
        }
        return drained;
    }

    /** Reaps the direct child inside a bounded window even when the waiting
     * thread is interrupted. */
    private static void reap(Process process, boolean[] interrupted) {
        long deadlineNanos = System.nanoTime()
            + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(REAP_BUDGET_MS);
        while (process.isAlive()) {
            long remaining = deadlineNanos - System.nanoTime();
            if (remaining <= 0) {
                process.destroyForcibly();
                return;
            }
            try {
                if (process.waitFor(Math.max(1L,
                        java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(remaining)),
                        java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    return;
                }
            } catch (InterruptedException interruptedReap) {
                interrupted[0] = true;
            }
        }
    }

    private record Export(String name, int index) {
    }

    private record Capture(String code, String message, String sourceFile,
                           Integer line, Integer column, String expected, String actual) {
    }

    /** The ordered zero-arity exports of the entry module. */
    private static List<Export> exportsOf(String source) {
        List<Export> exports = new ArrayList<>();
        Matcher matcher = Pattern
            .compile("^export function (\\w+)\\(\\)", Pattern.MULTILINE)
            .matcher(source);
        int index = 0;
        while (matcher.find()) {
            String name = matcher.group(1);
            if (!name.contains("$")) {
                exports.add(new Export(name, index++));
            }
        }
        return exports;
    }

    private static final String TRANSPORT_FILE = "__probe_transport.txt";

    private static Execution executeLua(Project project, List<Export> exports)
            throws Exception {
        Path out = project.outputRoot();
        Path artifact = artifactOf(project, Target.LUAJIT);
        Path transport = out.resolve(TRANSPORT_FILE);
        Files.deleteIfExists(transport);
        StringBuilder probe = new StringBuilder();
        probe.append("local function __esc(s)\n");
        probe.append("  s = tostring(s)\n");
        probe.append("  s = string.gsub(s, \"\\\\\", \"\\\\\\\\\")\n");
        probe.append("  s = string.gsub(s, \"\\n\", \"\\\\n\")\n");
        probe.append("  return s\nend\n");
        probe.append("local function __transport(fields)\n");
        probe.append("  local f = io.open(\"").append(transport).append("\", \"w\")\n");
        probe.append("  if not f then os.exit(3) end\n");
        probe.append("  for _, kv in ipairs(fields) do\n");
        probe.append("    f:write(kv[1], \"\\t\", __esc(kv[2] or \"\"), \"\\n\")\n");
        probe.append("  end\n  f:close()\nend\n");
        probe.append("local function __fail(err)\n");
        probe.append("  if type(err) ~= \"table\" then\n");
        probe.append("    __transport({ {\"code\", \"NON_DEAL\"},"
            + " {\"message\", tostring(err)} })\n  else\n");
        probe.append("    local file, line, column = err.file, err.line, err.column\n");
        probe.append("    if err.o ~= nil then\n");
        probe.append("      file, line, column = tostring(err.o)"
            + ":match(\"^(.*):(%d+):(%d+)$\")\n    end\n");
        probe.append("    __transport({ {\"code\", err.code},"
            + " {\"message\", err.message or err.m}, {\"file\", file},"
            + " {\"line\", line}, {\"column\", column}, {\"expected\", err.e},"
            + " {\"actual\", err.a} })\n  end\n");
        probe.append("  os.exit(1)\nend\n");
        probe.append("local function __defect(detail)\n");
        probe.append("  __transport({ {\"code\", \"PROBE_DEFECT\"},"
            + " {\"message\", detail} })\n  os.exit(1)\nend\n");
        probe.append("dofile(\"").append(artifact.toAbsolutePath().normalize())
            .append("\")\n");
        probe.append("local __ok, __err = __dealMain()\n");
        probe.append("if not __ok then __fail(__err) end\n");
        for (Export export : exports) {
            if ("main".equals(export.name())) {
                continue;
            }
            probe.append("do local __v = __exportSurfaces[\"")
                .append(project.modulePath()).append("\"][\"")
                .append(export.name()).append("\"]\n");
            probe.append("if type(__v) ~= \"table\" or type(__v.f) ~= \"function\" then "
                + "__defect(\"the entry surface publishes no function for ")
                .append(export.name()).append("\") end\n");
            probe.append("local __okS, __errS = pcall(__v.f)\n");
            probe.append("if not __okS then __fail(__errS) end end\n");
        }
        probe.append("os.exit(0)\n");
        Path probeFile = out.resolve("__probe.lua");
        Files.writeString(probeFile, probe.toString(), StandardCharsets.UTF_8);
        ProcessOutcome outcome = runProcess(out, Map.of("DEAL_DEFER_MAIN", "1"),
            BOUNDED_PROCESS_BUDGET_MS, "luajit", probeFile.toString());
        Capture capture = readTransport(transport);
        boolean defect = capture != null && "PROBE_DEFECT".equals(capture.code());
        return new Execution(outcome.exitCode(), outcome.stdout(), outcome.stderr(),
            capture, defect);
    }

    private static Execution executeJvm(Project project, List<Export> exports)
            throws Exception {
        Path out = project.outputRoot();
        Path artifact = artifactOf(project, Target.JVM);
        Path transport = out.resolve(TRANSPORT_FILE);
        Files.deleteIfExists(transport);
        String className = JvmBackend.classNameFor(project.modulePath());
        Path classes = out.resolve("classes");
        Files.createDirectories(classes);
        String classpath = absoluteClasspath();
        StringBuilder probe = new StringBuilder();
        probe.append("public final class Probe {\n");
        probe.append("  static final String TRANSPORT = \"")
            .append(transport.toString().replace("\\", "\\\\")).append("\";\n");
        probe.append("  public static void main(String[] args) {\n");
        probe.append("    try {\n");
        probe.append("      ").append(className).append(".dealMain();\n");
        for (Export export : exports) {
            if ("main".equals(export.name())) {
                continue;
            }
            probe.append("      {\n");
            probe.append("        Object v = deal.codegen.jvm.JvmRuntime")
                .append(".exportSurface(\"").append(project.modulePath())
                .append("\").read(\"").append(export.name()).append("\");\n");
            probe.append("        if (!(v instanceof deal.codegen.jvm")
                .append(".JvmRuntime.FunctionValue)) {\n");
            probe.append("          transport(new IllegalStateException(\"PROBE_DEFECT: "
                + "the entry surface publishes no function for ")
                .append(export.name()).append("\"));\n");
            probe.append("          return;\n        }\n");
            probe.append("        ((deal.codegen.jvm.JvmRuntime.FunctionValue) v)")
                .append(".fn.invoke(new Object[]{});\n      }\n");
        }
        probe.append("      System.exit(0);\n");
        probe.append("    } catch (Throwable error) {\n      transport(error);\n    }\n");
        probe.append("  }\n");
        probe.append("  static void transport(Throwable error) {\n");
        probe.append("    Throwable e = error;\n");
        probe.append("    while (e != null && !(e instanceof deal.codegen.jvm")
            .append(".JvmRuntime.DealError) && e.getCause() != null) {\n");
        probe.append("      e = e.getCause();\n    }\n");
        probe.append("    StringBuilder out = new StringBuilder();\n");
        probe.append("    if (e instanceof deal.codegen.jvm.JvmRuntime.DealError d) {\n");
        probe.append("      append(out, \"code\", d.code);\n");
        probe.append("      append(out, \"message\", d.msg);\n");
        probe.append("      append(out, \"origin\", d.origin);\n");
        probe.append("      append(out, \"expected\", d.expected);\n");
        probe.append("      append(out, \"actual\", d.actual);\n");
        probe.append("    } else {\n");
        probe.append("      append(out, \"code\", \"NON_DEAL\");\n");
        probe.append("      append(out, \"message\", String.valueOf(error));\n    }\n");
        probe.append("    try {\n");
        probe.append("      java.nio.file.Files.writeString(java.nio.file.Path.of("
            + "TRANSPORT), out.toString());\n");
        probe.append("    } catch (java.io.IOException ignored) { }\n");
        probe.append("    System.exit(1);\n  }\n");
        probe.append("  static void append(StringBuilder out, String key, String value) {\n");
        probe.append("    if (value == null) { return; }\n");
        probe.append("    String escaped = value.replace(\"\\\\\", \"\\\\\\\\\")"
            + ".replace(\"\\n\", \"\\\\n\").replace(\"\\t\", \"\\\\t\");\n");
        probe.append("    out.append(key).append('\\t').append(escaped).append('\\n');\n");
        probe.append("  }\n}\n");
        Path probeFile = out.resolve("Probe.java");
        Files.writeString(probeFile, probe.toString(), StandardCharsets.UTF_8);
        ProcessOutcome compile = runProcess(out, Map.of(), BOUNDED_PROCESS_BUDGET_MS,
            "javac", "--release", "25", "-proc:none", "-cp", classpath, "-d",
            classes.toString(), artifact.toString(), probeFile.toString());
        String compileOut = compile.stdout() + compile.stderr();
        int compileExit = compile.exitCode();
        checkEq(0, compileExit, project.modulePath() + " [jvm]: the emitted "
            + "production artifact compiles with javac --release 25 -proc:none: "
            + compileOut);
        if (compileExit != 0) {
            return new Execution(-1, "", compileOut, null, false);
        }
        ProcessOutcome outcome = runProcess(out, Map.of(), BOUNDED_PROCESS_BUDGET_MS,
            "java", "-cp", classpath + File.pathSeparator + classes, "Probe");
        Capture capture = readTransport(transport);
        boolean defect = capture != null && "PROBE_DEFECT".equals(capture.code());
        return new Execution(outcome.exitCode(), outcome.stdout(), outcome.stderr(),
            capture, defect);
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

    private static Capture readTransport(Path transport) throws Exception {
        if (!Files.isRegularFile(transport)) {
            return null;
        }
        Map<String, String> fields = new LinkedHashMap<>();
        for (String line : Files.readAllLines(transport, StandardCharsets.UTF_8)) {
            int tab = line.indexOf('\t');
            if (tab < 0) {
                continue;
            }
            fields.put(line.substring(0, tab), unescape(line.substring(tab + 1)));
        }
        String file = null;
        String origin = fields.get("origin");
        if (origin != null && origin.contains(":")) {
            int lastColon = origin.lastIndexOf(':');
            int prevColon = origin.lastIndexOf(':', lastColon - 1);
            if (prevColon > 0) {
                file = origin.substring(0, prevColon);
                fields.putIfAbsent("line", origin.substring(prevColon + 1, lastColon));
                fields.putIfAbsent("column", origin.substring(lastColon + 1));
            }
        } else {
            file = fields.get("file");
        }
        return new Capture(fields.get("code"), fields.get("message"), file,
            parseInt(fields.get("line")), parseInt(fields.get("column")),
            emptyToNull(fields.get("expected")), emptyToNull(fields.get("actual")));
    }

    private static Integer parseInt(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        try {
            return Integer.valueOf(text);
        } catch (NumberFormatException e) {
            return null;
        }
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

    // =========================================================================
    // 4. The measured record and the sidecar comparison
    // =========================================================================

    private static final Map<String, Map<Target, String>> RECORD =
        new LinkedHashMap<>();

    private static void testProductionDrive() throws Exception {
        System.out.println("-- the @jsonable helper family production drive: "
            + FAMILY_FIXTURES.size() + " fixtures on both targets --");
        List<String> driven = new ArrayList<>(FAMILY_FIXTURES);
        driven.add(NEAR_COLLISION);
        driven.add(EXPORT_KEYS);
        for (String fixture : driven) {
            String fixtureRel = fixture.contains("/") ? fixture
                : FAMILY_DIR + "/" + fixture;
            Project luaProject = materialize(fixtureRel, Target.LUAJIT);
            Project jvmProject = materialize(fixtureRel, Target.JVM);
            try {
                Map<Target, String> legs = new LinkedHashMap<>();
                legs.put(Target.LUAJIT, driveLeg(fixtureRel, luaProject, Target.LUAJIT));
                legs.put(Target.JVM, driveLeg(fixtureRel, jvmProject, Target.JVM));
                RECORD.put(fixtureRel, legs);
            } finally {
                deleteRecursively(luaProject.root());
                deleteRecursively(jvmProject.root());
            }
        }
        // The family's pinned compile-error fixture: the frontend rejects
        // the dynamic table index with E3007 on both targets and nothing
        // publishes (the sidecar-free disposition of the corpus).
        Project rejectProject = materialize(COMPILE_REJECT, Target.LUAJIT);
        try {
            List<String> diagnostics = new ArrayList<>();
            boolean compiled = compile(rejectProject, COMPILE_REJECT, Target.LUAJIT,
                diagnostics);
            check(!compiled, COMPILE_REJECT + ": the frontend rejects the dynamic "
                + "table index");
            check(diagnostics.stream().anyMatch(d -> d.startsWith("E3007 ")),
                COMPILE_REJECT + ": the pinned E3007 diagnostic: " + diagnostics);
            check(!Files.exists(artifactOf(rejectProject, Target.LUAJIT)),
                COMPILE_REJECT + ": no artifact publishes for the compile reject");
        } finally {
            deleteRecursively(rejectProject.root());
        }
    }

    private static String driveLeg(String fixtureRel, Project project, Target target)
            throws Exception {
        List<String> diagnostics = new ArrayList<>();
        boolean compiled = compile(project, fixtureRel, target, diagnostics);
        for (String diagnostic : diagnostics) {
            check(!diagnostic.startsWith("E6005 "), fixtureRel + " ["
                + target.laneName() + "]: zero E6005 over the in-scope fixture: "
                + diagnostic);
        }
        check(compiled, fixtureRel + " [" + target.laneName()
            + "]: the fixture compiles through the release-owned production "
            + "invocation: " + diagnostics);
        Path artifact = artifactOf(project, target);
        check(Files.isRegularFile(artifact), fixtureRel + " ["
            + target.laneName() + "]: the artifact publishes at " + artifact);
        if (!compiled || !Files.isRegularFile(artifact)) {
            return "compile-failure";
        }
        String entrySource = Files.readString(project.entryFile(), StandardCharsets.UTF_8);
        List<Export> exports = exportsOf(entrySource);
        Execution execution = target == Target.LUAJIT
            ? executeLua(project, exports) : executeJvm(project, exports);
        check(!execution.probeDefect(), fixtureRel + " [" + target.laneName()
            + "]: the artifact drive reaches every export: "
            + (execution.capture() == null ? "" : execution.capture().message()));
        Capture capture = normalize(project, execution.capture());
        String sidecarPath = fixtureRel.contains("/")
            ? fixtureRel : FAMILY_DIR + "/" + fixtureRel;
        Optional<SidecarExpectations.RuntimeExpectation.Executed> pinned =
            pinnedOf(sidecarPath);
        if (pinned.isEmpty()) {
            checkEq("", execution.stderr(), fixtureRel + " [" + target.laneName()
                + "]: the artifact run emits no stderr");
            if (capture == null) {
                checkEq(0, execution.exitCode(), fixtureRel + " ["
                    + target.laneName() + "]: the runtime-ok artifact run exits 0");
            }
            return capture == null ? "ok" : "capture:" + capture;
        }
        SidecarExpectations.RuntimeExpectation.Executed expectation = pinned.get();
        if (!expectation.isRuntimeError()) {
            checkEq(0, execution.exitCode(), fixtureRel + " [" + target.laneName()
                + "]: the runtime-ok artifact run exits 0");
            check(capture == null, fixtureRel + " [" + target.laneName()
                + "]: the runtime-ok artifact raises nothing (capture " + capture + ")");
            checkEq(new String(expectation.stdout(), StandardCharsets.UTF_8),
                execution.stdout(), fixtureRel + " [" + target.laneName()
                    + "]: the pinned stdout reproduces byte-exact");
            checkEq(new String(expectation.stderr(), StandardCharsets.UTF_8),
                execution.stderr(), fixtureRel + " [" + target.laneName()
                    + "]: the pinned stderr reproduces byte-exact");
            checkEq(expectation.exitCode(), execution.exitCode(), fixtureRel + " ["
                + target.laneName() + "]: the pinned exit code reproduces");
            return "ok";
        }
        SidecarExpectations.ErrorExpectation row = expectation.error();
        List<String> mismatches = runtimeErrorSidecarMismatches(expectation, row,
            execution.exitCode(), execution.stdout(), execution.stderr(), capture);
        for (String mismatch : mismatches) {
            check(false, fixtureRel + " [" + target.laneName() + "]: " + mismatch);
        }
        return mismatches.isEmpty() ? "pin-exact" : "pin-mismatch";
    }

    /**
     * The pinned-failure leg's sidecar comparison: the ordered mismatch
     * descriptions (empty exactly when the leg reproduces the sidecar's
     * byte-exact transcript — the normalized capture fields, the complete
     * stdout transcript, the process exit code, and the process stderr). The
     * sidecar's stdout, exit code, and stderr are part of the byte-exact
     * transcript contract, so a matching capture accompanied by unexpected
     * stdout, an incorrect process status, or wrong stderr is a mismatch,
     * never a pass. The stdout transcript is the captured process stdout
     * concatenated with the framing regenerated from the normalized capture
     * — the landed lane's assembly — so arbitrary extra output cannot hide
     * behind an otherwise matching capture.
     */
    private static List<String> runtimeErrorSidecarMismatches(
            SidecarExpectations.RuntimeExpectation.Executed expectation,
            SidecarExpectations.ErrorExpectation row, int exitCode, String stdout,
            String stderr, Capture capture) {
        List<String> mismatches = new ArrayList<>();
        if (capture == null || capture.code() == null) {
            mismatches.add("the pinned failure is not captured (exit " + exitCode
                + "; stderr " + stderr + ")");
            return mismatches;
        }
        addMismatch(mismatches, "code", row.code(), capture.code());
        addMismatch(mismatches, "message", row.message(), capture.message());
        addMismatch(mismatches, "span file (raw corpus coordinates)",
            row.sourceFile(), capture.sourceFile());
        addMismatch(mismatches, "raw corpus line", row.line(), capture.line());
        addMismatch(mismatches, "column", row.column(), capture.column());
        addMismatch(mismatches, "expected field", row.expected().orElse(null),
            capture.expected());
        addMismatch(mismatches, "actual field", row.actual().orElse(null),
            capture.actual());
        addMismatch(mismatches, "exit code", expectation.exitCode(), exitCode);
        addMismatch(mismatches, "stderr",
            new String(expectation.stderr(), StandardCharsets.UTF_8), stderr);
        String framed = ErrorSnapshot.CODE_LINE_PREFIX + capture.code() + "\n"
            + ErrorSnapshot.SNAPSHOT_LINE_PREFIX
            + ErrorSnapshot.canonicalJson(new SidecarExpectations.ErrorExpectation(
                capture.code(), capture.message(),
                row.pinsSpan() ? capture.sourceFile() : null,
                row.pinsSpan() ? capture.line() : null,
                row.pinsSpan() ? capture.column() : null,
                row.expected().isPresent() ? Optional.ofNullable(capture.expected())
                    : Optional.empty(),
                row.actual().isPresent() ? Optional.ofNullable(capture.actual())
                    : Optional.empty(),
                Optional.empty(), Optional.empty()))
            + "\n";
        addMismatch(mismatches, "framed stdout",
            new String(expectation.stdout(), StandardCharsets.UTF_8),
            stdout + framed);
        return mismatches;
    }

    private static void addMismatch(List<String> mismatches, String field,
            Object expected, Object actual) {
        if (!java.util.Objects.equals(expected, actual)) {
            mismatches.add("the " + field + " mismatch (expected " + expected
                + ", got " + actual + ")");
        }
    }

    private static Optional<SidecarExpectations.RuntimeExpectation.Executed> pinnedOf(
            String fixtureRel) throws Exception {
        Path sidecar = CONFORMANCE.resolve(fixtureRel + ".expect.json");
        if (!Files.isRegularFile(sidecar)) {
            return Optional.empty();
        }
        SidecarExpectations.StructuredExpectationSidecar parsed =
            SidecarExpectations.StructuredExpectationSidecar.parse(
                Files.readString(sidecar, StandardCharsets.UTF_8));
        SidecarExpectations.RuntimeExpectation expectation =
            parsed.expectationFor("luajit");
        if (expectation instanceof SidecarExpectations.RuntimeExpectation.Executed
                executed) {
            return Optional.of(executed);
        }
        return Optional.empty();
    }

    private static Capture normalize(Project project, Capture capture) {
        if (capture == null) {
            return null;
        }
        Materialized module = project.moduleOf(capture.sourceFile());
        return new Capture(capture.code(), capture.message(),
            module == null ? capture.sourceFile() : module.corpusPath(),
            capture.line() == null || module == null ? capture.line()
                : capture.line() + module.headerLinesStripped(),
            capture.column(), capture.expected(), capture.actual());
    }

    // =========================================================================
    // 5. The oracle leg (the combined dependency step)
    // =========================================================================

    private record Lowered(ExecutableLoweredProject project,
                           Map<ModuleId, StructuredBodyTable> tables,
                           Map<ModuleId, deal.semantic.ir.ClassFactoryRegistry> registries,
                           ModuleId entryModule) {
    }

    private static Lowered lower(Project project, String label) throws Exception {
        return lower(project, project.entryFile(), label);
    }

    private static Lowered lower(Project project, Path entryFile, String label)
            throws Exception {
        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            entryFile.toAbsolutePath(), project.root().resolve("out-oracle"),
            false, false, false, false, Backend.LUAJIT, Map.of(),
            List.of(project.srcRoot()), Path.of("std").toAbsolutePath().normalize(),
            null, productionInvocation());
        boolean compiled = orchestrator.compile();
        CheckedProjectBuildResult built = orchestrator.checkedProject();
        RequirementManifestResult manifests = orchestrator.requirementManifests();
        HostDeclarationSurface surface = orchestrator.hostDeclarationSurface();
        check(compiled && built != null && built.input() != null && built.index() != null
                && !built.hasErrors() && manifests != null
                && manifests.manifests() != null && surface != null,
            label + ": the oracle closure compiles: "
                + (built == null ? "no checked project" : built.diagnostics()));
        if (!compiled || built == null || built.input() == null || built.index() == null
                || built.hasErrors() || manifests == null
                || manifests.manifests() == null || surface == null) {
            return null;
        }
        SemanticLowerer.ProjectLoweringResult result = SemanticLowerer.lowerProject(
            productionInvocation(), built.input(), built.index(),
            manifests.manifests(), surface, Map.of(), Map.of(),
            BuiltinErrorDeclaration.synthesized(
                built.input().modules().get(0).ast().span()),
            List.of(IntrinsicKind.INT_CONVERT, IntrinsicKind.NUMBER_CONVERT),
            Set.of());
        check(result.project() != null, label + ": the oracle closure lowers with "
            + "zero diagnostics: " + result.diagnostics());
        if (result.project() == null) {
            return null;
        }
        return new Lowered(result.project(), result.tables(), result.registries(),
            result.project().entryModule());
    }

    /**
     * The oracle driver entry for the ordered zero-arity export loop: the
     * fixture is a dependency here, and its non-{@code main} exports run
     * exactly as the artifact probes' ordered loop runs them. The fixture's
     * {@code main} is not called here (a cross-module {@code main} call is
     * no valid invocation shape: the declaring module records no
     * {@code EXTERNAL_ENTRY} for it — the entry delegation owns its single
     * invocation); the main leg below lowers the fixture itself as the
     * entry so the entry delegation runs it exactly once.
     */
    private static String oracleDriver(Project project, List<Export> exports) {
        String fixtureStem = moduleNameOf(
            project.entryFile().getFileName().toString());
        StringBuilder source = new StringBuilder();
        source.append("import * as fx from \"./").append(fixtureStem)
            .append("\"\n\n");
        source.append("export function main(): null {\n");
        for (Export export : exports) {
            if ("main".equals(export.name())) {
                continue;
            }
            source.append("  fx.").append(export.name()).append("()\n");
        }
        return source.append("  return null\n}\n").toString();
    }

    /**
     * The oracle's main leg: the fixture itself is the entry module of the
     * one-lowering closure, so its own entry delegation invokes its declared
     * {@code main} exactly once — the exact sequence the artifact probes run
     * first. Returns the failure, or {@code null} on success.
     */
    private static SemanticRuntimeModel.ErrorSnapshot oracleMainFailure(
            Project project, String label) throws Exception {
        Lowered lowered = lower(project, label);
        if (lowered == null) {
            return null;
        }
        SemanticRuntimeModel.ConsumerRun run = SemanticOracle.executeProjectInits(
            lowered.project(), lowered.tables(), lowered.registries(),
            new SemanticOracle.HostResponder() { });
        if (run.terminal() instanceof SemanticRuntimeModel.Terminal.Success) {
            return null;
        }
        return ((SemanticRuntimeModel.Terminal.DealFailure) run.terminal()).error();
    }

    private static String oracleOutcome(Project project,
            SemanticRuntimeModel.ConsumerRun run) {
        if (run.terminal() instanceof SemanticRuntimeModel.Terminal.Success) {
            return "ok";
        }
        return oracleOutcomeOfFailure(project,
            ((SemanticRuntimeModel.Terminal.DealFailure) run.terminal()).error());
    }

    /** The one outcome spelling of one oracle failure snapshot. */
    private static String oracleOutcomeOfFailure(Project project,
            SemanticRuntimeModel.ErrorSnapshot error) {
        String file = null;
        Integer line = null;
        Integer column = null;
        if (error.origin() != null && error.origin().contains(":")) {
            int lastColon = error.origin().lastIndexOf(':');
            int prevColon = error.origin().lastIndexOf(':', lastColon - 1);
            if (prevColon > 0) {
                file = error.origin().substring(0, prevColon);
                line = Integer.valueOf(error.origin()
                    .substring(prevColon + 1, lastColon));
                column = Integer.valueOf(error.origin().substring(lastColon + 1));
            }
        }
        Materialized module = project.moduleOf(file);
        return "code=" + error.code() + "|message=" + error.message() + "|file="
            + (module == null ? file : module.corpusPath()) + "|line="
            + (line == null || module == null ? line
                : line + module.headerLinesStripped())
            + "|column=" + column + "|expected=" + error.expected()
            + "|actual=" + error.actual();
    }

    /**
     * The dependency step: the oracle executes every fixture of the family
     * through the same one-lowering closure the production legs used, and
     * reproduces the sidecar-pinned outcome (the runtime-ok success or the
     * pinned error tuple at the raw corpus coordinates). The two named
     * dependency fixtures keep their explicit assertions: the cross-module
     * helper call succeeds, and the pinned helper JSON failure (the
     * canonical failure projection authority's cycle arm) renders the
     * pinned tuple at the invoking call expression.
     */
    private static void testOracleAgreement() throws Exception {
        System.out.println("-- the combined dependency step: the oracle over every "
            + "family fixture (each fixture's main through its own entry "
            + "delegation, the cross-module helper call, and the pinned helper "
            + "JSON failure included) --");
        List<String> driven = new ArrayList<>(FAMILY_FIXTURES);
        driven.add(NEAR_COLLISION);
        driven.add(EXPORT_KEYS);
        for (String fixture : driven) {
            String fixtureRel = fixture.contains("/") ? fixture
                : FAMILY_DIR + "/" + fixture;
            Project project = materialize(fixtureRel, Target.JVM);
            try {
                List<Export> exports = exportsOf(
                    Files.readString(project.entryFile(), StandardCharsets.UTF_8));
                // The main leg: the fixture is the entry module of its own
                // one-lowering closure, so the entry delegation invokes its
                // declared main exactly once — the artifact probes' first
                // step. A main-leg failure dominates (the probes never reach
                // the export loop after a failing main).
                SemanticRuntimeModel.ErrorSnapshot mainFailure =
                    oracleMainFailure(project, fixtureRel + " (oracle main)");
                String mainOutcome = mainFailure == null ? "ok"
                    : oracleOutcomeOfFailure(project, mainFailure);
                Path driver = project.srcRoot().resolve("__oracle_drive.deal");
                Files.writeString(driver, oracleDriver(project, exports),
                    StandardCharsets.UTF_8);
                Lowered lowered = lower(project, driver,
                    fixtureRel + " (oracle exports)");
                if (lowered == null) {
                    continue;
                }
                String exportOutcome = oracleOutcome(project,
                    SemanticOracle.executeProjectInits(lowered.project(),
                        lowered.tables(), lowered.registries(),
                        new SemanticOracle.HostResponder() { }));
                String outcome = "ok".equals(mainOutcome) ? exportOutcome
                    : mainOutcome;
                System.out.println("   oracle " + fixtureRel + ": main "
                    + mainOutcome + "; exports " + exportOutcome);
                Optional<SidecarExpectations.RuntimeExpectation.Executed> pinned =
                    pinnedOf(fixtureRel);
                if (pinned.isPresent()) {
                    if (pinned.get().isRuntimeError()) {
                        checkEq(pinnedOracleOutcome(pinned.get().error()), outcome,
                            fixtureRel + ": the oracle reproduces the pinned tuple at "
                                + "the raw corpus coordinates");
                    } else {
                        checkEq("ok", outcome, fixtureRel + ": the oracle executes "
                            + "the fixture with the pinned runtime-ok outcome");
                    }
                }
                if (CYCLIC_FIXTURE.equals(fixtureRel)) {
                    check(outcome.contains("message=cyclic value cannot be encoded as JSON"),
                        "the oracle's helper JSON failure renders the pinned cycle "
                            + "text: " + outcome);
                    check(outcome.contains("code=E8001"),
                        "the oracle's helper JSON failure carries E8001: " + outcome);
                    check(outcome.contains("expected=null") && outcome.contains(
                        "actual=null"),
                        "the cycle arm carries no expected/actual: " + outcome);
                    String captured = RECORD.get(fixtureRel).get(Target.LUAJIT);
                    check(captured != null && captured.startsWith("pin-exact"),
                        "the LuaJIT leg reproduces the pinned tuple: " + captured);
                } else if (CROSS_MODULE_FIXTURE.equals(fixtureRel)) {
                    checkEq("ok", outcome,
                        "the cross-module helper call executes through the oracle: "
                            + outcome);
                }
            } finally {
                deleteRecursively(project.root());
            }
        }
    }

    /** The pinned sidecar error row in {@link #oracleOutcome}'s spelling. */
    private static String pinnedOracleOutcome(
            SidecarExpectations.ErrorExpectation row) {
        return "code=" + row.code() + "|message=" + row.message()
            + "|file=" + row.sourceFile() + "|line=" + row.line()
            + "|column=" + row.column() + "|expected=" + row.expected().orElse(null)
            + "|actual=" + row.actual().orElse(null);
    }

    // =========================================================================
    // 6. The helper surface in the produced unit
    // =========================================================================

    private static void testHelperSurface() throws Exception {
        System.out.println("-- the helper surface: one identity and one entry per "
            + "helper export, the same-module and cross-module call shapes --");
        Project project = materialize(CROSS_MODULE_FIXTURE, Target.JVM);
        try {
            Lowered lowered = lower(project, "helper surface");
            if (lowered == null) {
                return;
            }
            ExecutableLoweredProject unit = lowered.project();
            ModuleId entry = lowered.entryModule();
            ModuleId lib = new ModuleId("jsonable_lib");
            LoweredModuleUnit entryUnit = unit.modules().get(entry);
            LoweredModuleUnit libUnit = unit.modules().get(lib);
            check(entryUnit != null && libUnit != null,
                "the cross-module closure carries both units");
            if (entryUnit == null || libUnit == null) {
                return;
            }
            // The declaring unit: exactly one CLOSURE_NEW per generated
            // helper signature, exactly one LoweredBody binding per closure
            // identity, and exactly one publication and entry per helper
            // export.
            Map<String, ValueId> closuresByHelper = new LinkedHashMap<>();
            for (SemanticOp op : libUnit.ops()) {
                if (op.kind() != SemanticOpKind.CLOSURE_NEW) {
                    continue;
                }
                KindPayload.ClosureNewPayload closure =
                    (KindPayload.ClosureNewPayload) op.payload();
                String text = closure.signature().canonicalSpecText();
                String helper = text.contains("?@") ? "Widget$fromJson" : "Widget$toJson";
                check(closuresByHelper.put(helper, (ValueId) op.result()) == null,
                    "exactly one " + helper + " closure identity per class");
                check(libUnit.functions().containsKey(closure.function()),
                    "the " + helper + " closure carries its LoweredFunction record");
                check(libUnit.functionBindings().get(new FunctionAllocationIdentity(
                        ((ValueId) op.result()).id()))
                        instanceof FunctionExecutionBinding.LoweredBody binding
                        && binding.functionId().equals(closure.function())
                        && binding.blockId().equals(closure.binding().blockId()),
                    "the " + helper + " closure identity registers exactly one "
                        + "LoweredBody binding (the landed call shape)");
            }
            checkEq(2, closuresByHelper.size(),
                "the declaring unit carries exactly one closure per class helper");
            int publications = 0;
            int entries = 0;
            Map<String, OpId> entryOf = new LinkedHashMap<>();
            Map<String, FunctionId> entryFunctionOf = new LinkedHashMap<>();
            for (SemanticOp op : libUnit.ops()) {
                if (op.kind() == SemanticOpKind.EXPORT_PUBLISH
                        && op.payload() instanceof KindPayload.ExportPublishPayload p
                        && (p.name().equals("Widget$fromJson")
                            || p.name().equals("Widget$toJson"))) {
                    publications++;
                }
                if (op.kind() == SemanticOpKind.EXTERNAL_ENTRY
                        && op.payload() instanceof KindPayload.ExternalEntryPayload p
                        && (p.exportName().equals("Widget$fromJson")
                            || p.exportName().equals("Widget$toJson"))) {
                    entries++;
                    entryOf.put(p.exportName(), op.opId());
                    entryFunctionOf.put(p.exportName(), p.function());
                }
            }
            checkEq(2, publications, "exactly one EXPORT_PUBLISH per helper export");
            checkEq(2, entries, "exactly one EXTERNAL_ENTRY per helper export");
            check(libUnit.moduleInit() != null, "the declaring unit carries its "
                + "module-init walk (the publication site)");
            for (Map.Entry<String, OpId> entryRow : entryOf.entrySet()) {
                FunctionId function = entryFunctionOf.get(entryRow.getKey());
                check(libUnit.functions().containsKey(function),
                    "the recorded entry of " + entryRow.getKey()
                        + " resolves the helper's own lowered body");
                check(libUnit.functions().get(function).descriptor()
                        .canonicalSpecText().contains("Widget"),
                    "the recorded entry of " + entryRow.getKey()
                        + " names the class's generated body");
            }
            // The cross-module call: one EXPORT_READ over the callee module
            // and one CALL(EXTERNAL) over the closed ExternalFunction
            // SHARED_BODY binding whose recorded entry is the callee's and
            // whose entry function is the helper's own lowered body.
            boolean exportRead = false;
            boolean externalCall = false;
            for (SemanticOp op : entryUnit.ops()) {
                if (op.kind() == SemanticOpKind.EXPORT_READ
                        && op.payload() instanceof KindPayload.ExportReadPayload p
                        && p.module().equals(lib)
                        && (p.name().equals("Widget$fromJson")
                            || p.name().equals("Widget$toJson"))) {
                    exportRead = true;
                }
                if (op.kind() == SemanticOpKind.CALL
                        && op.payload() instanceof KindPayload.CallPayload call
                        && call.callee() instanceof KindPayload.CallCallee.Static s
                        && s.binding() instanceof FunctionExecutionBinding.ExternalFunction
                            external
                        && external.moduleId().equals(lib)
                        && external.executionOwner()
                            == deal.semantic.ir.ExternalExecutionOwner.SHARED_BODY) {
                    externalCall = true;
                    OpId recorded = entryOf.get(external.exportName());
                    check(recorded != null, "the cross-module call names a recorded "
                        + "helper entry");
                    check(call.externalEntryRef() != null && recorded != null
                            && call.externalEntryRef().equals(recorded),
                        "the cross-module call binds the callee's recorded entry");
                }
            }
            check(exportRead, "the cross-module helper read lowers as an EXPORT_READ");
            check(externalCall, "the cross-module helper call binds the closed "
                + "ExternalFunction(SHARED_BODY) shape to the callee's recorded entry");
        } finally {
            deleteRecursively(project.root());
        }
        // The same-module call shape (R3(b)): a same-module reference
        // resolves the class's generated closure identity (the
        // declaration-position CLOSURE_NEW result) and calls it through the
        // landed LoweredBody call shape — one recorded EXTERNAL_ENTRY per
        // helper export remains for the cross-module callers, and no
        // same-module call resolves the ExternalFunction shape.
        Project same = materialize(FAMILY_DIR + "/jsonable-roundtrip", Target.JVM);
        try {
            Lowered sameLowered = lower(same, "same-module helper surface");
            if (sameLowered == null) {
                return;
            }
            LoweredModuleUnit roundtrip = sameLowered.project().modules()
                .get(sameLowered.entryModule());
            // The E7 terminals (the recorded entries) emit after the walk,
            // so the entries are collected first and the call sites checked
            // after.
            Map<String, FunctionId> entryFunctionOf = new LinkedHashMap<>();
            Map<FunctionId, OpId> entryReturnBoundaryByFunction = new LinkedHashMap<>();
            Map<FunctionId, ValueId> closureIdentityOf = new LinkedHashMap<>();
            for (SemanticOp op : roundtrip.ops()) {
                if (op.kind() == SemanticOpKind.EXTERNAL_ENTRY
                        && op.payload() instanceof KindPayload.ExternalEntryPayload p
                        && p.exportName().startsWith("Person$")) {
                    entryFunctionOf.put(p.exportName(), p.function());
                    entryReturnBoundaryByFunction.put(p.function(),
                        p.returnBoundaryOpId());
                }
                if (op.kind() == SemanticOpKind.CLOSURE_NEW
                        && op.payload() instanceof KindPayload.ClosureNewPayload closure
                        && op.result() instanceof ValueId identity) {
                    closureIdentityOf.put(closure.function(), identity);
                }
            }
            java.util.Set<ValueId> loadedIdentities = new LinkedHashSet<>();
            for (SemanticOp op : roundtrip.ops()) {
                if (op.kind() == SemanticOpKind.BINDING_LOAD
                        && op.result() instanceof ValueId loaded) {
                    loadedIdentities.add(loaded);
                }
            }
            int directHelperCalls = 0;
            int sameModuleExternalCalls = 0;
            for (SemanticOp op : roundtrip.ops()) {
                if (op.kind() != SemanticOpKind.CALL
                        || !(op.payload() instanceof KindPayload.CallPayload call)
                        || !(call.callee() instanceof KindPayload.CallCallee.Static s)) {
                    continue;
                }
                if (s.binding() instanceof FunctionExecutionBinding.LoweredBody body
                        && entryFunctionOf.containsValue(body.functionId())) {
                    directHelperCalls++;
                    LoweredFunction helper = roundtrip.functions().get(body.functionId());
                    check(helper != null, "the same-module call resolves the class's "
                        + "generated lowered body");
                    checkEq(CallMode.DIRECT, call.mode(),
                        "the same-module helper call is a direct body call");
                    check(helper != null && helper.body().equals(body.blockId())
                            && helper.body().equals(call.bodyBlock()),
                        "the same-module call names the declaration's body block");
                    ValueId identity = closureIdentityOf.get(body.functionId());
                    check(identity != null && loadedIdentities.contains(identity),
                        "the same-module callee load publishes the declaration's "
                            + "generated closure identity " + identity);
                    check(helper != null && helper.descriptor().canonicalSpecText()
                            .contains("Person"),
                        "the same-module helper body is the class's generated body");
                    checkEq(entryReturnBoundaryByFunction.get(body.functionId()),
                        call.returnBoundaryOpId(),
                        "the same-module call shares the helper's single return cell");
                }
                if (s.binding() instanceof FunctionExecutionBinding.ExternalFunction external
                        && external.moduleId().equals(sameLowered.entryModule())
                        && external.executionOwner()
                            == deal.semantic.ir.ExternalExecutionOwner.SHARED_BODY) {
                    sameModuleExternalCalls++;
                }
            }
            checkEq(2, entryFunctionOf.size(),
                "exactly one recorded entry per same-module helper export");
            checkEq(2, directHelperCalls, "the same-module helper calls are direct "
                + "LoweredBody calls to the declaration's closure (got "
                + directHelperCalls + ")");
            checkEq(0, sameModuleExternalCalls, "no same-module call resolves the "
                + "ExternalFunction shape (the entry stays the cross-module record)");
        } finally {
            deleteRecursively(same.root());
        }
    }

    // =========================================================================
    // 7. The negatives
    // =========================================================================

    private static void testNegatives() throws Exception {
        System.out.println("-- the negatives: the near-collision identifier, the "
            + "non-@jsonable class, and the E2004 frontend missing-helper "
            + "reference --");
        // The near-collision fixture: a local identifier `User_fromJson`
        // next to the helper `User$fromJson` changes no outcome (its leg
        // already reproduced the pinned runtime-ok sidecar byte-exact).
        Project near = materialize(NEAR_COLLISION, Target.LUAJIT);
        try {
            String leg = RECORD.get(NEAR_COLLISION).get(Target.LUAJIT);
            checkEq("ok", leg, "the near-collision identifier changes no outcome");
        } finally {
            deleteRecursively(near.root());
        }
        // A declared class that is not @jsonable gains no helper: the
        // non-jsonable fixture lowers no JSON closure and no JSON op.
        Path root = Files.createTempDirectory("jsonable-negative-");
        try {
            Path src = root.resolve("src");
            Files.createDirectories(src);
            Files.writeString(src.resolve("plain.deal"),
                "export class Plain {\n  name: string = \"\";\n}\n\n"
                + "export function main(): null {\n  return null;\n}\n",
                StandardCharsets.UTF_8);
            Files.writeString(root.resolve("deal.json"),
                "{\n  \"languageVersion\": \"1.2\",\n  \"moduleRoots\": [\"src\"],\n"
                    + "  \"output\": \"out\",\n  \"backend\": \"luajit\"\n}\n",
                StandardCharsets.UTF_8);
            Project project = new Project(root, src, src.resolve("plain.deal"), "plain",
                Map.of(), root.resolve("out"));
            Lowered lowered = lower(project, "non-jsonable negative");
            if (lowered != null) {
                for (LoweredModuleUnit moduleUnit : lowered.project().modules().values()) {
                    for (SemanticOp op : moduleUnit.ops()) {
                        check(op.kind() != SemanticOpKind.JSON_FROM_CLASS
                                && op.kind() != SemanticOpKind.JSON_TO_CLASS,
                            "a non-@jsonable class lowers no JSON helper op (got "
                                + op.kind() + ")");
                    }
                }
            }
        } finally {
            deleteRecursively(root);
        }
        // A helper reference on a non-@jsonable imported class fails closed
        // and stages nothing: the checker's declared-export fact is the
        // resolution authority, so no lowering arm fabricates a helper.
        Path missing = Files.createTempDirectory("jsonable-missing-export-");
        try {
            Path src = missing.resolve("src");
            Files.createDirectories(src);
            Files.writeString(src.resolve("plain_lib.deal"),
                "export class Plain {\n  name: string = \"\";\n}\n",
                StandardCharsets.UTF_8);
            Files.writeString(src.resolve("app.deal"),
                "import * as Lib from \"./plain_lib\"\n\n"
                    + "export function test_missing_helper(): null {\n"
                    + "  let s: string = Lib.Plain$toJson({ name: \"x\" });\n"
                    + "  return null;\n}\n\n"
                    + "export function main(): null {\n  return null;\n}\n",
                StandardCharsets.UTF_8);
            Files.writeString(missing.resolve("deal.json"),
                "{\n  \"languageVersion\": \"1.2\",\n  \"moduleRoots\": [\"src\"],\n"
                    + "  \"output\": \"out\",\n  \"backend\": \"luajit\"\n}\n",
                StandardCharsets.UTF_8);
            Project project = new Project(missing, src, src.resolve("app.deal"), "app",
                Map.of(), missing.resolve("out"));
            List<String> diagnostics = new ArrayList<>();
            boolean compiled = compile(project, "missing-helper-export", Target.LUAJIT,
                diagnostics);
            check(!compiled, "a helper reference on a non-@jsonable class fails closed: "
                + diagnostics);
            check(diagnostics.stream().anyMatch(d -> d.startsWith("E2004 ")),
                "the missing-helper reference fails closed with the checker's "
                    + "missing-export rule (E2004): " + diagnostics);
            check(!Files.exists(artifactOf(project, Target.LUAJIT)),
                "nothing stages for the missing-helper reference");
        } finally {
            deleteRecursively(missing);
        }
    }

    /**
     * The declaring module's checked export list without one export name:
     * the hand-built inconsistent "missing helper export" fact (no
     * checker-valid program produces it — the checker's synthetic export
     * is the generated helper's authority).
     */
    private static CheckedProjectInput withoutExport(CheckedProjectInput project,
            ModuleId module, String export) {
        List<CheckedModuleInput> modules = new ArrayList<>();
        for (CheckedModuleInput entry : project.modules()) {
            if (!entry.moduleId().equals(module)) {
                modules.add(entry);
                continue;
            }
            List<ExportInterface> exports = new ArrayList<>();
            for (ExportInterface candidate : entry.exports()) {
                if (!candidate.name().equals(export)) {
                    exports.add(candidate);
                }
            }
            modules.add(new CheckedModuleInput(entry.moduleId(), entry.sourceId(),
                entry.sourcePath(), entry.ast(), entry.checks(), entry.imports(),
                exports, entry.kind()));
        }
        return new CheckedProjectInput(project.invocation(), project.entryModule(),
            modules, project.releaseStateHash());
    }

    /**
     * The lowered project with one module's matching ops removed from the
     * produced-op list <em>and</em> from its block-membership table: the
     * hand-built inconsistent fact an "otherwise valid generated helper"
     * with a missing export or a missing recorded callee entry is (no
     * checker-valid program produces it, and the removal is complete — the
     * block walk never resolves a removed id).
     */
    private static Lowered withoutUnitOps(Lowered lowered, ModuleId module,
            java.util.function.Predicate<SemanticOp> remove) {
        LoweredModuleUnit unit = lowered.project().modules().get(module);
        List<SemanticOp> ops = new ArrayList<>();
        List<OpId> removed = new ArrayList<>();
        for (SemanticOp op : unit.ops()) {
            if (remove.test(op)) {
                removed.add(op.opId());
            } else {
                ops.add(op);
            }
        }
        LoweredModuleUnit replaced = new LoweredModuleUnit(unit.formatVersion(),
            unit.semanticProfile(), unit.moduleId(), unit.interfaceHash(),
            unit.loweringContextHash(), unit.requiredCapabilities(),
            unit.constructCoverage(), unit.classLayouts(), unit.functions(),
            unit.moduleInit(), unit.exportPlan(), unit.functionBindings(), ops);
        Map<ModuleId, LoweredModuleUnit> modules = new LinkedHashMap<>();
        for (Map.Entry<ModuleId, LoweredModuleUnit> entry
                : lowered.project().modules().entrySet()) {
            modules.put(entry.getKey(),
                entry.getKey().equals(module) ? replaced : entry.getValue());
        }
        ExecutableLoweredProject project = new ExecutableLoweredProject(
            lowered.project().semanticProfile(), lowered.project().interfaceIndex(),
            modules, lowered.project().entryModule());
        StructuredBodyTable table = lowered.tables().get(module);
        Map<BlockId, List<OpId>> blockOps = new LinkedHashMap<>();
        for (Map.Entry<BlockId, List<OpId>> entry : table.blockOps().entrySet()) {
            List<OpId> kept = new ArrayList<>();
            for (OpId id : entry.getValue()) {
                if (!removed.contains(id)) {
                    kept.add(id);
                }
            }
            blockOps.put(entry.getKey(), kept);
        }
        Map<OpId, BlockId> opBlocks = new LinkedHashMap<>(table.opBlocks());
        for (OpId id : removed) {
            opBlocks.remove(id);
        }
        Map<ModuleId, StructuredBodyTable> tables = new LinkedHashMap<>(lowered.tables());
        tables.put(module, new StructuredBodyTable(blockOps, opBlocks));
        return new Lowered(project, tables, lowered.registries(),
            lowered.entryModule());
    }

    /**
     * Both production emitters fail closed on one hand-built inconsistent
     * helper fact with a named producer defect: exactly the
     * {@code IllegalStateException} the production arm maps to its
     * registered {@code SHARED_EMITTER_COVERAGE} rule, leaving the staged
     * set empty.
     */
    private static void assertEmitterRejectsMissingFact(String what,
            Lowered lowered, HostDeclarationSurface surface,
            String... requiredDetails) throws Exception {
        for (Target target : Target.values()) {
            Path out = Files.createTempDirectory("jsonable-missing-fact-");
            PublicationStager stager = PublicationStager.forRoot(out);
            String rejection = null;
            try {
                if (target == Target.LUAJIT) {
                    LuaSemanticEmitter.emitProductionProject(lowered.project(),
                        lowered.tables(), lowered.registries(), surface);
                } else {
                    JvmSemanticEmitter.emitProductionProject(lowered.project(),
                        lowered.tables(), lowered.registries(), "Probe", surface);
                }
            } catch (IllegalStateException guard) {
                rejection = guard.getMessage();
            }
            boolean names = rejection != null;
            for (String detail : requiredDetails) {
                names = names && rejection.contains(detail);
            }
            check(names, what + " [" + target.laneName() + "]: the emitter fails "
                + "closed with the named producer defect (" + rejection + ")");
            check(stager.stagedSet().relativePaths().isEmpty(),
                what + " [" + target.laneName() + "]: the fail-closed emission "
                    + "stages nothing");
            deleteRecursively(out);
        }
    }

    /**
     * The inconsistent-fact negatives (the task criterion's "a missing
     * helper export or a missing callee entry fails closed with the
     * guard's rule identifier and stages nothing"): the otherwise valid
     * cross-module project's generated {@code Widget$fromJson} with its
     * export removed (a hand-built checked-project fact) fails the real
     * production arm closed with one E6005 and an empty staged set on both
     * targets; the missing recorded callee entry fails the real production
     * arm closed the same way through a checker-valid cross-module call to
     * an export the declaring module records no entry for (the entry
     * delegation owns it); and at the lowered-IR mechanism level the same
     * helper facts — a missing {@code EXPORT_PUBLISH} publication and a
     * missing recorded {@code EXTERNAL_ENTRY} — are rejected by both
     * production emitters with the named producer defect and stage nothing.
     */
    private static void testMissingHelperFactsFailClosed() throws Exception {
        System.out.println("-- the inconsistent-fact negatives: a missing generated "
            + "helper export and a missing recorded callee entry fail closed "
            + "through the producer guard and stage nothing --");
        checkEq("SHARED_EMITTER_COVERAGE",
            ProductionProjectEmission.SHARED_EMITTER_COVERAGE,
            "the registered emitter-coverage rule id an emitter rejection maps to");
        Project project = materialize(FAMILY_DIR + "/jsonable-cross-module", Target.JVM);
        try {
            ProjectLocator.LocateResult located = ProjectLocator.locate(
                project.entryFile().toString(), null);
            check(located.context() != null,
                "the cross-module fixture locates for the negatives");
            if (located.context() == null) {
                return;
            }
            CompilationOrchestrator orchestrator = new CompilationOrchestrator(
                located.context(), project.entryFile(), false, false, false, false,
                null, productionInvocation());
            boolean compiled = orchestrator.compile();
            CheckedProjectBuildResult built = orchestrator.checkedProject();
            RequirementManifestResult manifests = orchestrator.requirementManifests();
            HostDeclarationSurface surface = orchestrator.hostDeclarationSurface();
            check(compiled && built != null && built.input() != null
                    && built.index() != null && manifests != null
                    && manifests.manifests() != null && surface != null,
                "the negatives' checked project builds: "
                    + (built == null ? "no checked project" : built.diagnostics()));
            if (!compiled || built == null || built.input() == null
                    || built.index() == null || manifests == null
                    || manifests.manifests() == null || surface == null) {
                return;
            }
            // (a) The missing helper export through the real production arm:
            // the declaring module's checked export list lacks
            // Widget$fromJson while the cross-module caller still reads it.
            ModuleId lib = new ModuleId("jsonable_lib");
            CheckedProjectInput missingExport = withoutExport(built.input(), lib,
                "Widget$fromJson");
            for (Target target : Target.values()) {
                Path out = project.root().resolve("out-missing-export-"
                    + target.laneName());
                PublicationStager stager = PublicationStager.forRoot(out);
                ProductionProjectEmission.Result result;
                List<String> staged;
                try {
                    result = ProductionProjectEmission.run(productionInvocation(),
                        missingExport, built.index(), manifests.manifests(), surface,
                        Map.of(), Map.of(), project.root().toString(),
                        BuiltinErrorDeclaration.synthesized(
                            built.input().modules().get(0).ast().span()),
                        List.of(IntrinsicKind.INT_CONVERT,
                            IntrinsicKind.NUMBER_CONVERT),
                        Set.of(),
                        target == Target.JVM ? Backend.JVM : Backend.LUAJIT, false,
                        DistributionHome.forManifestDirectory(
                            project.root().toString()), stager);
                    staged = new ArrayList<>(stager.stagedSet().relativePaths());
                } finally {
                    stager.discard();
                }
                CompilerDiagnostic first = result.firstDiagnostic();
                check(!result.emitted(), "the missing helper export fails the "
                    + target.laneName() + " production arm closed");
                checkEq("E6005", first == null ? null : first.code(),
                    "the missing helper export merges exactly one E6005 ["
                        + target.laneName() + "]");
                check(first != null && first.message().contains("CONSTRUCT_UNLOWERED")
                        && first.message().contains("Widget$fromJson")
                        && first.message().contains("no recorded EXTERNAL_ENTRY"),
                    "the missing helper export fails through the CONSTRUCT_UNLOWERED "
                        + "guard naming the helper's absent entry ["
                        + target.laneName() + "]: " + (first == null ? null
                            : first.message()));
                check(staged.isEmpty(), "the missing helper export stages nothing ["
                    + target.laneName() + "] (staged " + staged + ")");
            }
            // (b) The missing recorded callee entry through the real
            // production arm: the callee-side counterpart of (a). The
            // declaring module publishes an export whose invocation the
            // module records no EXTERNAL_ENTRY for — the generated
            // helpers' own entry mechanism consumes exactly this relation
            // (the reference's externalEntryRef and the emitter's
            // resolveExternalEntry), and the entry function's
            // entry-delegation-owned export is the checker-valid shape of
            // the fact.
            for (Target target : Target.values()) {
                Path entryRoot = Files.createTempDirectory(
                    "jsonable-missing-entry-");
                try {
                    Path src = entryRoot.resolve("src");
                    Files.createDirectories(src);
                    Files.writeString(src.resolve("lib.deal"),
                        "export function main(): null {\n  return null;\n}\n",
                        StandardCharsets.UTF_8);
                    Files.writeString(src.resolve("app.deal"),
                        "import * as Lib from \"./lib\"\n\n"
                            + "export function test_cross_module_main(): null {\n"
                            + "  Lib.main();\n"
                            + "  return null;\n}\n\n"
                            + "export function main(): null {\n  return null;\n}\n",
                        StandardCharsets.UTF_8);
                    Files.writeString(entryRoot.resolve("deal.json"),
                        "{\n  \"languageVersion\": \"1.2\",\n"
                            + "  \"moduleRoots\": [\"src\"],\n"
                            + "  \"output\": \"out\",\n"
                            + "  \"backend\": \""
                            + (target == Target.JVM ? "jvm" : "luajit")
                            + "\"\n}\n",
                        StandardCharsets.UTF_8);
                    Project entryProject = new Project(entryRoot, src,
                        src.resolve("app.deal"), "app", Map.of(),
                        entryRoot.resolve("out"));
                    List<String> diagnostics = new ArrayList<>();
                    boolean entryCompiled = compile(entryProject,
                        "missing-callee-entry", target, diagnostics);
                    check(!entryCompiled, "the missing recorded callee entry fails the "
                        + target.laneName() + " production arm closed: "
                        + diagnostics);
                    check(diagnostics.stream().anyMatch(d -> d.startsWith("E6005 ")
                            && d.contains("CONSTRUCT_UNLOWERED")
                            && d.contains("no recorded EXTERNAL_ENTRY")),
                        "the missing recorded callee entry fails through the "
                            + "CONSTRUCT_UNLOWERED guard naming the absent entry ["
                            + target.laneName() + "]: " + diagnostics);
                    check(!Files.exists(artifactOf(entryProject, target)),
                        "the missing recorded callee entry stages nothing ["
                            + target.laneName() + "]");
                } finally {
                    deleteRecursively(entryRoot);
                }
            }
            // (c) The same facts at the lowered-IR mechanism level: the
            // otherwise valid project's generated helper loses its
            // EXPORT_PUBLISH publication (a missing helper export), and,
            // separately, its recorded EXTERNAL_ENTRY (a missing callee
            // entry while the export stays published).
            Lowered lowered = lower(project, "inconsistent-fact negatives");
            if (lowered == null) {
                return;
            }
            assertEmitterRejectsMissingFact("the missing helper export",
                withoutUnitOps(lowered, lib,
                    op -> op.kind() == SemanticOpKind.EXPORT_PUBLISH
                        && op.payload().toString().contains("Widget$fromJson")),
                surface, "Widget$fromJson", "never publishes");
            assertEmitterRejectsMissingFact("the missing recorded callee entry",
                withoutUnitOps(lowered, lib,
                    op -> op.kind() == SemanticOpKind.EXTERNAL_ENTRY
                        && op.payload().toString().contains("Widget$fromJson")),
                surface, "externalEntryRef",
                "does not resolve to a recorded EXTERNAL_ENTRY");
        } finally {
            deleteRecursively(project.root());
        }
    }

    // =========================================================================
    // 7b. The three-consumer walk regressions
    // =========================================================================

    /** One synthetic lane-equivalent project of an inline source. */
    private static Project syntheticProject(String moduleName, String source,
            Target target) throws Exception {
        Path root = Files.createTempDirectory("jsonable-synthetic-");
        Path srcRoot = root.resolve("src");
        Files.createDirectories(srcRoot);
        Path entryFile = srcRoot.resolve(moduleName + ".deal");
        Files.writeString(entryFile, source, StandardCharsets.UTF_8);
        Files.writeString(root.resolve("deal.json"),
            "{\n  \"languageVersion\": \"1.2\",\n"
                + "  \"moduleRoots\": [\"src\"],\n  \"output\": \"out\",\n"
                + "  \"backend\": \"" + (target == Target.JVM ? "jvm" : "luajit")
                + "\"\n}\n", StandardCharsets.UTF_8);
        Materialized materialized = new Materialized(moduleName + ".deal", source, 0);
        Map<String, Materialized> modules = new LinkedHashMap<>();
        modules.put(entryFile.toAbsolutePath().normalize().toString(), materialized);
        modules.put(moduleName + ".deal", materialized);
        return new Project(root, srcRoot, entryFile, moduleName, modules,
            root.resolve("out"));
    }

    /** The 1-based line of the first occurrence of a token in a source. */
    private static int lineOf(String source, String needle) {
        return source.substring(0, source.indexOf(needle)).split("\n", -1).length;
    }

    /** The 1-based column of the first occurrence of a token in a source. */
    private static int columnOf(String source, String needle) {
        int index = source.indexOf(needle);
        return index - source.lastIndexOf('\n', index - 1);
    }

    /** The oracle's failure of one synthetic project, or {@code null} on
     * success. */
    private static SemanticRuntimeModel.ErrorSnapshot oracleFailure(Project project,
            List<Export> exports) throws Exception {
        Path driver = project.srcRoot().resolve("__oracle_drive.deal");
        Files.writeString(driver, oracleDriver(project, exports), StandardCharsets.UTF_8);
        Lowered lowered = lower(project, driver, "synthetic oracle");
        if (lowered == null) {
            return null;
        }
        SemanticRuntimeModel.ConsumerRun run = SemanticOracle.executeProjectInits(
            lowered.project(), lowered.tables(), lowered.registries(),
            new SemanticOracle.HostResponder() { });
        if (run.terminal() instanceof SemanticRuntimeModel.Terminal.Success) {
            return null;
        }
        return ((SemanticRuntimeModel.Terminal.DealFailure) run.terminal()).error();
    }

    /** The {@code line:column} suffix of a {@code file:line:column} origin. */
    private static String originPosition(String origin) {
        if (origin == null) {
            return null;
        }
        int lastColon = origin.lastIndexOf(':');
        int prevColon = lastColon < 0 ? -1 : origin.lastIndexOf(':', lastColon - 1);
        if (prevColon < 0) {
            return null;
        }
        return origin.substring(prevColon + 1);
    }

    /**
     * The function-parameter helper invocation (the three-consumer
     * regression): a helper passed to a function-typed parameter and
     * invoked dynamically ({@code f(w)}) renders its pinned JSON failure
     * at the {@code f(w)} call expression in the oracle, the LuaJIT
     * artifact, and the JVM artifact alike — never at an enclosing static
     * call or the generated declaration anchor.
     */
    private static void testDynamicHelperOrigin() throws Exception {
        System.out.println("-- the function-parameter helper invocation: the f(w) "
            + "origin on all three consumers --");
        String source = """
            // @jsonable
            export class Wrapper {
              data: table = {};
            }

            function apply(f: (w: Wrapper) => string, w: Wrapper): string {
              return f(w);
            }

            export function test_dynamic_helper_origin(): string {
              let w: Wrapper = { data: {} };
              w.data.self = w.data;
              return apply(Wrapper$toJson, w);
            }

            export function main(): null {
              return null;
            }
            """;
        int line = lineOf(source, "f(w)");
        int column = columnOf(source, "f(w)");
        String expectedPosition = line + ":" + column;
        List<Export> exports = exportsOf(source);
        // The oracle leg.
        Project oracleProject = syntheticProject("app", source, Target.JVM);
        try {
            SemanticRuntimeModel.ErrorSnapshot oracle =
                oracleFailure(oracleProject, exports);
            check(oracle != null,
                "the oracle rejects the cyclic helper invocation");
            if (oracle != null) {
                checkEq("E8001", oracle.code(), "the oracle's code");
                checkEq("cyclic value cannot be encoded as JSON", oracle.message(),
                    "the oracle's message");
                checkEq(expectedPosition, originPosition(oracle.origin()),
                    "the oracle renders the f(w) call origin (got " + oracle.origin()
                        + ")");
            }
        } finally {
            deleteRecursively(oracleProject.root());
        }
        // The covered lowering path: the f(w) call is a dynamic
        // DEAL-body invocation (CallMode.INDIRECT over a CallCallee.Dynamic),
        // never a statically resolved call.
        Project irProject = syntheticProject("app", source, Target.JVM);
        try {
            Path driver = irProject.srcRoot().resolve("__oracle_drive.deal");
            Files.writeString(driver, oracleDriver(irProject, exports),
                StandardCharsets.UTF_8);
            Lowered lowered = lower(irProject, driver, "dynamic-helper IR");
            if (lowered != null) {
                boolean dynamicCall = false;
                for (LoweredModuleUnit unit : lowered.project().modules().values()) {
                    for (SemanticOp op : unit.ops()) {
                        if (op.kind() == SemanticOpKind.CALL
                                && op.payload() instanceof KindPayload.CallPayload call
                                && call.callee()
                                    instanceof KindPayload.CallCallee.Dynamic
                                && call.dynamicReturnBoundary() != null) {
                            dynamicCall = true;
                        }
                    }
                }
                check(dynamicCall, "the f(w) call lowers as a dynamic "
                    + "DEAL-body invocation");
            }
        } finally {
            deleteRecursively(irProject.root());
        }
        // The two real-toolchain legs.
        for (Target target : Target.values()) {
            Project project = syntheticProject("app", source, target);
            try {
                List<String> diagnostics = new ArrayList<>();
                boolean compiled = compile(project, "dynamic-helper-origin", target,
                    diagnostics);
                check(compiled, "the dynamic-helper project compiles ["
                    + target.laneName() + "]: " + diagnostics);
                if (!compiled) {
                    continue;
                }
                Execution execution = target == Target.LUAJIT
                    ? executeLua(project, exports) : executeJvm(project, exports);
                Capture capture = execution.capture();
                check(capture != null, "the dynamic-helper project fails on ["
                    + target.laneName() + "] (exit " + execution.exitCode()
                    + "; stderr " + execution.stderr() + ")");
                if (capture == null) {
                    continue;
                }
                checkEq("E8001", capture.code(), "the " + target.laneName()
                    + " artifact's code");
                checkEq("cyclic value cannot be encoded as JSON", capture.message(),
                    "the " + target.laneName() + " artifact's message");
                checkEq(expectedPosition,
                    capture.line() + ":" + capture.column(), "the "
                        + target.laneName() + " artifact renders the f(w) call "
                        + "origin (got " + capture.sourceFile() + ":"
                        + capture.line() + ":" + capture.column() + ")");
                check(capture.sourceFile() != null
                        && capture.sourceFile().endsWith("app.deal"),
                    "the " + target.laneName() + " artifact's origin names the "
                        + "declaring module file");
            } finally {
                deleteRecursively(project.root());
            }
        }
    }

    /**
     * The class-instance-in-table-content negative (the three-consumer
     * regression): a class instance inside an array nested in a table
     * field is not JSON-shaped data, so the oracle, the LuaJIT artifact,
     * and the JVM artifact all reject it with the pinned JSON_TO_WALK
     * projection at the helper call origin — the descriptor-free table
     * walk never serializes it.
     */
    private static void testTableClassInstanceRejection() throws Exception {
        System.out.println("-- the table-content class-instance negative: all three "
            + "consumers reject --");
        String source = """
            // @jsonable
            export class Child {
              value: int = 0;
            }

            // @jsonable
            export class Wrapper {
              data: table = {};
            }

            export function test_table_class_instance(): string {
              let child: Child = { value: 7 };
              let w: Wrapper = { data: {} };
              w.data.children = [child];
              return Wrapper$toJson(w);
            }

            export function main(): null {
              return null;
            }
            """;
        int line = lineOf(source, "Wrapper$toJson(w)");
        int column = columnOf(source, "Wrapper$toJson(w)");
        String expectedPosition = line + ":" + column;
        List<Export> exports = exportsOf(source);
        String oracleMessage = null;
        Project oracleProject = syntheticProject("app", source, Target.JVM);
        try {
            SemanticRuntimeModel.ErrorSnapshot oracle =
                oracleFailure(oracleProject, exports);
            check(oracle != null, "the oracle rejects the class instance inside "
                + "an array nested in a table field");
            if (oracle != null) {
                checkEq("E8001", oracle.code(), "the oracle's code");
                check(oracle.message() != null
                        && oracle.message().startsWith("value at data.children[0] "
                            + "is not JSON serializable: "),
                    "the oracle's pinned walk message (got " + oracle.message() + ")");
                checkEq(expectedPosition, originPosition(oracle.origin()),
                    "the oracle renders the helper call origin (got "
                        + oracle.origin() + ")");
                oracleMessage = oracle.message();
            }
        } finally {
            deleteRecursively(oracleProject.root());
        }
        for (Target target : Target.values()) {
            Project project = syntheticProject("app", source, target);
            try {
                List<String> diagnostics = new ArrayList<>();
                boolean compiled = compile(project, "table-class-instance", target,
                    diagnostics);
                check(compiled, "the table-class-instance project compiles ["
                    + target.laneName() + "]: " + diagnostics);
                if (!compiled) {
                    continue;
                }
                Execution execution = target == Target.LUAJIT
                    ? executeLua(project, exports) : executeJvm(project, exports);
                Capture capture = execution.capture();
                check(capture != null, "the table-class-instance project fails on ["
                    + target.laneName() + "] (exit " + execution.exitCode()
                    + "; stderr " + execution.stderr() + ")");
                if (capture == null) {
                    continue;
                }
                checkEq("E8001", capture.code(), "the " + target.laneName()
                    + " artifact's code");
                if (oracleMessage != null) {
                    checkEq(oracleMessage, capture.message(), "the "
                        + target.laneName() + " artifact reproduces the oracle's "
                        + "pinned walk message");
                }
                checkEq(expectedPosition,
                    capture.line() + ":" + capture.column(), "the "
                        + target.laneName() + " artifact renders the helper call "
                        + "origin (got " + capture.sourceFile() + ":"
                        + capture.line() + ":" + capture.column() + ")");
            } finally {
                deleteRecursively(project.root());
            }
        }
    }

    // =========================================================================
    // 7c. The nested-class table-content text regressions
    // =========================================================================

    /** The DEAL string literal spelling of one text. */
    private static String dealStringLiteral(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"")
            + "\"";
    }

    /**
     * The exact helper-text drive: the DEAL body compares the helper's
     * returned text against the exact expected string and throws the actual
     * text on any difference, so every consumer that completes proves the
     * exact string value — the oracle, the LuaJIT artifact, and the JVM
     * artifact alike.
     */
    private static void driveExactHelperText(String label, String source)
            throws Exception {
        List<Export> exports = exportsOf(source);
        Project oracleProject = syntheticProject("app", source, Target.JVM);
        try {
            SemanticRuntimeModel.ErrorSnapshot oracle =
                oracleFailure(oracleProject, exports);
            check(oracle == null, label + ": the oracle produces the exact "
                + "expected helper text (outcome "
                + (oracle == null ? "success"
                    : oracle.code() + " " + oracle.message())
                + ")");
        } finally {
            deleteRecursively(oracleProject.root());
        }
        for (Target target : Target.values()) {
            Project project = syntheticProject("app", source, target);
            try {
                List<String> diagnostics = new ArrayList<>();
                boolean compiled = compile(project, label, target, diagnostics);
                check(compiled, label + " [" + target.laneName() + "]: the project "
                    + "compiles through the production invocation: " + diagnostics);
                if (!compiled) {
                    continue;
                }
                Execution execution = target == Target.LUAJIT
                    ? executeLua(project, exports) : executeJvm(project, exports);
                Capture capture = execution.capture();
                check(capture == null, label + " [" + target.laneName()
                    + "]: the artifact produces the exact expected helper text "
                    + "(exit " + execution.exitCode() + "; "
                    + (capture == null ? ""
                        : capture.code() + " " + capture.message())
                    + ")");
            } finally {
                deleteRecursively(project.root());
            }
        }
    }

    /** The shared nested-class fixture: {@code Wrapper.child.data} is the table field. */
    private static String nestedTableFixtureSource(String function) {
        return "// @jsonable\nexport class Child {\n  data: table = {};\n}\n\n"
            + "// @jsonable\nexport class Wrapper {\n  child: Child = {};\n}\n\n"
            + function
            + "\nexport function main(): null {\n  return null;\n}\n";
    }

    /**
     * The first-insertion-order regression (the three-consumer exact text):
     * a nested class's table field spells its keys in first-insertion order,
     * never in sorted order, so the same object value yields the same string
     * on the oracle and both production artifacts.
     */
    private static void testNestedTableInsertionOrder() throws Exception {
        System.out.println("-- the nested-class table field's first-insertion order: "
            + "the exact three-consumer text --");
        String expected = "{\"child\":{\"data\":{\"z\":\"first\","
            + "\"a\":\"second\"}}}";
        String function = "export function test_nested_table_order(): null {\n"
            + "  let w: Wrapper = { child: { data: {} } };\n"
            + "  w.child.data.z = \"first\";\n"
            + "  w.child.data.a = \"second\";\n"
            + "  let json: string = Wrapper$toJson(w);\n"
            + "  let expected: string = " + dealStringLiteral(expected) + ";\n"
            + "  if (json !== expected) {\n"
            + "    throw { code: \"MISMATCH\", message: json };\n"
            + "  }\n"
            + "  return null;\n}\n";
        driveExactHelperText("nested-table-order",
            nestedTableFixtureSource(function));
    }

    /**
     * The numeric-variant regression (the three-consumer exact text): an
     * int-variant leaf inside a nested class's table field and array spells
     * its integer text and a number-variant leaf its closed decimal text on
     * every consumer — the shared carrier's {@code __nK} marks and
     * {@code __jn} variant carriers (and the JVM's boxed variants) decide
     * the spelling, never the walk's own default.
     */
    private static void testNestedTableNumericVariants() throws Exception {
        System.out.println("-- the nested-class table field's numeric variants: "
            + "int and number leaves in tables and arrays, exact text on all "
            + "three consumers --");
        String expected = "{\"child\":{\"data\":{\"i\":3,\"n\":2.5,"
            + "\"ai\":[7],\"an\":[1.5],\"ri\":1,\"rn\":6.5}}}";
        String function = "export function test_nested_table_numbers(): null {\n"
            + "  let w: Wrapper = { child: { data: {} } };\n"
            + "  w.child.data.i = 3;\n"
            + "  w.child.data.n = 2.5;\n"
            + "  w.child.data.ai = [7];\n"
            + "  w.child.data.an = [1.5];\n"
            // The read-side variant carriers: an int-variant slot read at a
            // number position and a number-variant slot read at a number
            // position both travel as carriers and must keep their own
            // variant's spelling.
            + "  let t: table = { x: 1 };\n"
            + "  let n: number = t.x;\n"
            + "  w.child.data.ri = n;\n"
            + "  let nums: number[] = [6.5];\n"
            + "  let fromNum: number = nums[0];\n"
            + "  w.child.data.rn = fromNum;\n"
            + "  let json: string = Wrapper$toJson(w);\n"
            + "  let expected: string = " + dealStringLiteral(expected) + ";\n"
            + "  if (json !== expected) {\n"
            + "    throw { code: \"MISMATCH\", message: json };\n"
            + "  }\n"
            + "  return null;\n}\n";
        driveExactHelperText("nested-table-numbers",
            nestedTableFixtureSource(function));
    }

    /**
     * The nested-class depth fixture: {@code Wrapper.child.data} is the head
     * of a chain of {@code links} links (one table per link). The valid form
     * compares the helper text against the exact expected string; the invalid
     * form ({@code expectedText == null}) returns the helper text directly
     * (the walk raises).
     */
    private static String nestedClassDepthSource(int links, String expectedText) {
        StringBuilder body = new StringBuilder();
        body.append("  let w: Wrapper = { child: { data: {} } };\n");
        body.append("  let cur: table = w.child.data;\n");
        body.append("  for (let i: int = 0; i < ").append(links)
            .append("; i = i + 1) {\n");
        body.append("    cur = linkAppend(cur);\n  }\n");
        if (expectedText != null) {
            body.append("  let json: string = Wrapper$toJson(w);\n");
            body.append("  let expected: string = ")
                .append(dealStringLiteral(expectedText)).append(";\n");
            body.append("  if (json !== expected) {\n");
            body.append("    throw { code: \"MISMATCH\", message: json };\n  }\n");
            body.append("  return null;\n");
        } else {
            body.append("  return Wrapper$toJson(w);\n");
        }
        return "// @jsonable\nexport class Child {\n  data: table = {};\n}\n\n"
            + "// @jsonable\nexport class Wrapper {\n  child: Child = {};\n}\n\n"
            + "export function linkAppend(cur: table): table {\n"
            + "  let n: table = {};\n"
            + "  cur.next = n;\n"
            + "  return n;\n"
            + "}\n\n"
            + "export function test_nested_class_depth(): "
            + (expectedText != null ? "null" : "string") + " {\n"
            + body
            + "}\n\nexport function main(): null {\n  return null;\n}\n";
    }

    /** The exact JSON text of a {@code Wrapper.child.data} chain of {@code links} links. */
    private static String nestedChainText(int links) {
        return "{\"child\":{\"data\":" + "{\"next\":".repeat(links) + "{}"
            + "}".repeat(links) + "}}";
    }

    /**
     * The JSON walk depth bound in the recursive nested-class path (the
     * three-consumer boundary): the 511-link chain (512 containers) completes
     * on the oracle and both production artifacts, and the 512-link chain
     * (513 containers) fails through the canonical walk arm with the exceeding
     * container's token and field path at the helper call origin — the
     * identical tuple on all three consumers.
     */
    private static void testNestedClassDepthBound() throws Exception {
        System.out.println("-- the nested-class walk's depth bound: 511 links "
            + "succeed, 512 links fail through the walk arm on all three "
            + "consumers --");
        String validSource = nestedClassDepthSource(511, nestedChainText(511));
        List<Export> validExports = exportsOf(validSource);
        Project validOracle = syntheticProject("app", validSource, Target.JVM);
        try {
            SemanticRuntimeModel.ErrorSnapshot oracle =
                oracleFailure(validOracle, validExports);
            check(oracle == null, "the 511-link chain (512 containers) completes "
                + "on the oracle (outcome "
                + (oracle == null ? "success"
                    : oracle.code() + " " + oracle.message())
                + ")");
        } finally {
            deleteRecursively(validOracle.root());
        }
        for (Target target : Target.values()) {
            Project project = syntheticProject("app", validSource, target);
            try {
                List<String> diagnostics = new ArrayList<>();
                boolean compiled = compile(project, "nested-class-depth-ok", target,
                    diagnostics);
                check(compiled, "the 511-link project compiles ["
                    + target.laneName() + "]: " + diagnostics);
                if (!compiled) {
                    continue;
                }
                Execution execution = target == Target.LUAJIT
                    ? executeLua(project, validExports)
                    : executeJvm(project, validExports);
                Capture capture = execution.capture();
                check(capture == null, "the 511-link chain (512 containers) "
                    + "completes on the " + target.laneName() + " artifact (exit "
                    + execution.exitCode() + "; "
                    + (capture == null ? ""
                        : capture.code() + " " + capture.message())
                    + ")");
            } finally {
                deleteRecursively(project.root());
            }
        }
        String invalidSource = nestedClassDepthSource(512, null);
        List<Export> invalidExports = exportsOf(invalidSource);
        String expectedPosition = lineOf(invalidSource, "Wrapper$toJson(w)") + ":"
            + columnOf(invalidSource, "Wrapper$toJson(w)");
        String expectedMessage = "value at child.data" + ".next".repeat(512)
            + " is not JSON serializable: table";
        String oracleMessage = null;
        Project invalidOracle = syntheticProject("app", invalidSource, Target.JVM);
        try {
            SemanticRuntimeModel.ErrorSnapshot oracle =
                oracleFailure(invalidOracle, invalidExports);
            check(oracle != null, "the 512-link chain exceeds the depth bound on "
                + "the oracle");
            if (oracle != null) {
                checkEq("E8001", oracle.code(), "the oracle's depth-overflow code");
                checkEq(expectedMessage, oracle.message(),
                    "the oracle's walk-arm message names the exceeding container");
                checkEq("table", oracle.actual(),
                    "the oracle's actual token is the exceeding container's table");
                checkEq(expectedPosition, originPosition(oracle.origin()),
                    "the oracle renders the helper call origin (got "
                        + oracle.origin() + ")");
                oracleMessage = oracle.message();
            }
        } finally {
            deleteRecursively(invalidOracle.root());
        }
        for (Target target : Target.values()) {
            Project project = syntheticProject("app", invalidSource, target);
            try {
                List<String> diagnostics = new ArrayList<>();
                boolean compiled = compile(project, "nested-class-depth-overflow",
                    target, diagnostics);
                check(compiled, "the 512-link project compiles ["
                    + target.laneName() + "]: " + diagnostics);
                if (!compiled) {
                    continue;
                }
                Execution execution = target == Target.LUAJIT
                    ? executeLua(project, invalidExports)
                    : executeJvm(project, invalidExports);
                Capture capture = execution.capture();
                check(capture != null, "the 512-link chain fails on the "
                    + target.laneName() + " artifact (exit " + execution.exitCode()
                    + ")");
                if (capture == null) {
                    continue;
                }
                checkEq("E8001", capture.code(),
                    "the " + target.laneName() + " depth-overflow code");
                if (oracleMessage != null) {
                    checkEq(oracleMessage, capture.message(), "the "
                        + target.laneName() + " artifact reproduces the oracle's "
                        + "walk-arm message");
                }
                checkEq(expectedPosition,
                    capture.line() + ":" + capture.column(), "the "
                        + target.laneName() + " artifact renders the helper call "
                        + "origin (got " + capture.sourceFile() + ":"
                        + capture.line() + ":" + capture.column() + ")");
            } finally {
                deleteRecursively(project.root());
            }
        }
    }

    // =========================================================================
    // 7d. The oracle carrier mirror and the helper carrier regressions
    // =========================================================================

    /**
     * The three-consumer outcome parity of one inline project: the oracle's
     * success or failure tuple is the reference and every artifact must
     * agree — success where the oracle succeeds, the identical code and
     * message where the oracle fails. The exact-text drive proves a success
     * outcome's text; this parity drive proves the consumers agree on the
     * outcome itself (a deleted array element's admission or rejection).
     */
    private static void driveThreeConsumerParity(String label, String source)
            throws Exception {
        List<Export> exports = exportsOf(source);
        SemanticRuntimeModel.ErrorSnapshot oracle;
        Project oracleProject = syntheticProject("app", source, Target.JVM);
        try {
            oracle = oracleFailure(oracleProject, exports);
        } finally {
            deleteRecursively(oracleProject.root());
        }
        String reference = oracle == null ? "success"
            : oracle.code() + " " + oracle.message();
        System.out.println("   " + label + ": the oracle reference outcome is "
            + reference);
        for (Target target : Target.values()) {
            Project project = syntheticProject("app", source, target);
            try {
                List<String> diagnostics = new ArrayList<>();
                boolean compiled = compile(project, label, target, diagnostics);
                check(compiled, label + " [" + target.laneName()
                    + "]: the project compiles through the production invocation: "
                    + diagnostics);
                if (!compiled) {
                    continue;
                }
                Execution execution = target == Target.LUAJIT
                    ? executeLua(project, exports) : executeJvm(project, exports);
                Capture capture = execution.capture();
                if (oracle == null) {
                    check(capture == null, label + " [" + target.laneName()
                        + "]: the artifact agrees with the oracle's success (exit "
                        + execution.exitCode() + "; "
                        + (capture == null ? ""
                            : capture.code() + " " + capture.message())
                        + ")");
                } else {
                    check(capture != null, label + " [" + target.laneName()
                        + "]: the artifact agrees with the oracle's failure (exit "
                        + execution.exitCode() + "; stderr " + execution.stderr()
                        + ")");
                    if (capture != null) {
                        checkEq(oracle.code(), capture.code(), label + " ["
                            + target.laneName() + "]: the failure code");
                        checkEq(oracle.message(), capture.message(), label + " ["
                            + target.laneName() + "]: the failure message");
                    }
                }
            } finally {
                deleteRecursively(project.root());
            }
        }
    }

    /**
     * The oracle's carrier-to-executor mutation mirror for arrays (the
     * three-consumer regression): a class field's array mutated in place by
     * an element replacement and an append is what the helper serializes on
     * the oracle, the LuaJIT artifact, and the JVM artifact alike — the
     * cached executor view never goes stale behind the carrier's in-place
     * commit.
     */
    private static void testArrayExecutorViewSync() throws Exception {
        System.out.println("-- the oracle's array carrier mirror: replacement and "
            + "append serialize on all three consumers --");
        String source = "// @jsonable\nexport class Box {\n  xs: int[] = [];\n}\n\n"
            + "// @jsonable\nexport class Wrapper {\n  data: table = {};\n}\n\n"
            + "export function test_array_mutation(): null {\n"
            + "  let b: Box = { xs: [1] };\n"
            + "  b.xs[0] = 2;\n"
            + "  b.xs[1] = 3;\n"
            // The second instance's field holds the same array identity;
            // mutating it through the alias must be visible through the
            // first instance's cached view (the mirror preserves identity).
            + "  let c: Box = { xs: b.xs };\n"
            + "  c.xs[0] = 9;\n"
            + "  let json: string = Box$toJson(b);\n"
            + "  let expected: string = " + dealStringLiteral("{\"xs\":[9,3]}")
            + ";\n"
            + "  if (json !== expected) {\n"
            + "    throw { code: \"MISMATCH\", message: json };\n"
            + "  }\n"
            + "  return null;\n"
            + "}\n\n"
            // The array nested in a table field: the cached table view
            // shares the array view, so the same in-place commit is what
            // the descriptor-free table walk serializes.
            + "export function test_table_nested_array(): null {\n"
            + "  let w: Wrapper = { data: { xs: [1] } };\n"
            + "  let arr: int[] = w.data.xs;\n"
            + "  arr[0] = 2;\n"
            + "  arr[1] = 3;\n"
            + "  let json: string = Wrapper$toJson(w);\n"
            + "  let expected: string = "
            + dealStringLiteral("{\"data\":{\"xs\":[2,3]}}") + ";\n"
            + "  if (json !== expected) {\n"
            + "    throw { code: \"MISMATCH\", message: json };\n"
            + "  }\n"
            + "  return null;\n"
            + "}\n\n"
            + "export function main(): null {\n  return null;\n}\n";
        driveExactHelperText("array-executor-view-sync", source);
    }

    /**
     * The oracle's carrier mirror on deletion (the three-consumer
     * agreement): an element deleted from a class field's array is observed
     * by the helper walk on the oracle and both artifacts with the same
     * outcome — the mirror keeps the deleted slot as the missing element,
     * never the stale pre-delete value.
     */
    private static void testArrayExecutorViewDeleteParity() throws Exception {
        System.out.println("-- the oracle's array carrier mirror: a deleted element "
            + "agrees on all three consumers --");
        String source = "// @jsonable\nexport class Box {\n  xs: int[] = [];\n}\n\n"
            + "export function test_array_delete(): string {\n"
            + "  let b: Box = { xs: [1, 2] };\n"
            + "  delete b.xs[0];\n"
            + "  return Box$toJson(b);\n"
            + "}\n\n"
            + "export function main(): null {\n  return null;\n}\n";
        driveThreeConsumerParity("array-executor-view-delete", source);
        // The same missing-slot semantics in the descriptor-free table
        // walk (an array nested in a table field): the deleted slot is the
        // internal missing element on every consumer, never a serialized
        // null or the stale pre-delete value.
        String tableSource = "// @jsonable\nexport class Wrapper {\n"
            + "  data: table = {};\n}\n\n"
            + "export function test_table_array_delete(): string {\n"
            + "  let w: Wrapper = { data: { xs: [1, 2] } };\n"
            + "  let arr: int[] = w.data.xs;\n"
            + "  delete arr[0];\n"
            + "  return Wrapper$toJson(w);\n"
            + "}\n\n"
            + "export function main(): null {\n  return null;\n}\n";
        driveThreeConsumerParity("table-array-view-delete", tableSource);
    }

    /**
     * The nullable-array element admission (the three-consumer regression):
     * a null element is admitted exactly where the element descriptor admits
     * it — {@code (int | null)[]} roundtrips the null element on the oracle,
     * the LuaJIT artifact, and the JVM artifact, while a non-nullable
     * {@code int[]} rejects the same document (language null) on all three.
     */
    private static void testNullableArrayElements() throws Exception {
        System.out.println("-- the nullable-array element admission: the element "
            + "descriptor decides a null element on all three consumers --");
        String source = "// @jsonable\nexport class Box {\n"
            + "  xs: (int | null)[] = [];\n}\n\n"
            + "// @jsonable\nexport class StrictBox {\n"
            + "  xs: int[] = [];\n}\n\n"
            + "export function test_nullable_array_roundtrip(): null {\n"
            + "  let maybe: Box | null = Box$fromJson(\"{\\\"xs\\\":[1,null]}\");\n"
            + "  if (maybe !== null) {\n"
            + "    let b: Box = maybe;\n"
            + "    let json: string = Box$toJson(b);\n"
            + "    let expected: string = "
            + dealStringLiteral("{\"xs\":[1,null]}") + ";\n"
            + "    if (json !== expected) {\n"
            + "      throw { code: \"MISMATCH\", message: json };\n"
            + "    }\n"
            + "    return null;\n"
            + "  }\n"
            + "  throw { code: \"MISMATCH\", message: \"the valid nullable "
            + "array document was rejected\" };\n"
            + "}\n\n"
            + "export function test_strict_array_rejected(): null {\n"
            + "  let maybe: StrictBox | null = StrictBox$fromJson("
            + "\"{\\\"xs\\\":[1,null]}\");\n"
            + "  if (maybe !== null) {\n"
            + "    throw { code: \"MISMATCH\", message: \"the non-nullable "
            + "element was admitted\" };\n"
            + "  }\n"
            + "  return null;\n"
            + "}\n\n"
            + "export function main(): null {\n  return null;\n}\n";
        driveExactHelperText("nullable-array-elements", source);
    }

    /**
     * The read-derived numeric carrier at declared positions (the
     * three-consumer regression): a numeric value read from a container slot
     * travels as the read-side variant carrier ({@code __jn}), and a declared
     * {@code number} field or array element admits it with its own variant's
     * spelling — never a rejection of the carrier representation.
     */
    private static void testReadDerivedNumericCarriers() throws Exception {
        System.out.println("-- read-derived numeric carriers in declared fields and "
            + "array elements: the own-variant spelling on all three consumers --");
        String source = "// @jsonable\nexport class Child {\n"
            + "  n: number = 0.5;\n"
            + "  xs: number[] = [];\n}\n\n"
            + "// @jsonable\nexport class Box {\n"
            + "  child: Child = {};\n}\n\n"
            + "export function test_read_derived_numbers(): null {\n"
            + "  let t: table = { i: 1, n: 2.5 };\n"
            + "  let i: number = t.i;\n"
            + "  let n: number = t.n;\n"
            + "  let b: Box = { child: { n: i, xs: [i, n] } };\n"
            + "  let json: string = Box$toJson(b);\n"
            + "  let expected: string = "
            + dealStringLiteral("{\"child\":{\"n\":1,\"xs\":[1,2.5]}}")
            + ";\n"
            + "  if (json !== expected) {\n"
            + "    throw { code: \"MISMATCH\", message: json };\n"
            + "  }\n"
            + "  return null;\n"
            + "}\n\n"
            + "export function main(): null {\n  return null;\n}\n";
        driveExactHelperText("read-derived-numeric-carriers", source);
    }

    /**
     * The omitted nested-class numeric default (the three-consumer
     * regression): the nested class's decode runs the declared CLASS_DEFAULT
     * children, and a default that produces the read-side numeric variant
     * carrier must conform at its declared {@code number} position (and at
     * the recursive {@code number | null} and {@code number[]} positions)
     * exactly as the oracle's and the JVM's own variants do — the document
     * is admitted and the instance serializes with the carrier's own
     * variant's spelling. The explicit-value control supplies the same
     * values in the document, so the two legs prove the default path and the
     * provided path independently; a LuaJIT rejection of the carrier is the
     * reported defect.
     */
    private static void testNestedClassDefaultNumericCarrier() throws Exception {
        System.out.println("-- the omitted nested-class numeric default: the "
            + "read-derived carrier conforms at its declared positions on all "
            + "three consumers, with the explicit-value control --");
        String expected = "{\"child\":{\"n\":2.5,\"o\":2.5,\"xs\":[2.5]}}";
        String source = "// @jsonable\nexport class Child {\n"
            + "  n: number = getNum();\n"
            + "  o: number | null = getNum();\n"
            + "  xs: number[] = getNums();\n}\n\n"
            + "// @jsonable\nexport class Box {\n"
            + "  child: Child = {};\n}\n\n"
            + "function getNum(): number {\n"
            + "  let t: table = { n: 2.5 };\n"
            + "  return t.n;\n}\n\n"
            + "function getNums(): number[] {\n"
            + "  let t: table = { n: 2.5 };\n"
            + "  let n: number = t.n;\n"
            + "  return [n];\n}\n\n"
            + "export function test_omitted_default_carriers(): null {\n"
            + "  let box: Box | null = Box$fromJson(\"{\\\"child\\\":{}}\");\n"
            + "  if (box !== null) {\n"
            + "    let json: string = Box$toJson(box);\n"
            + "    let expected: string = " + dealStringLiteral(expected) + ";\n"
            + "    if (json !== expected) {\n"
            + "      throw { code: \"MISMATCH\", message: json };\n"
            + "    }\n"
            + "    return null;\n"
            + "  }\n"
            + "  throw { code: \"MISMATCH\", message: \"the omitted valid "
            + "nested default was rejected\" };\n}\n\n"
            + "export function test_explicit_value_control(): null {\n"
            + "  let box: Box | null = Box$fromJson("
            + dealStringLiteral(expected) + ");\n"
            + "  if (box !== null) {\n"
            + "    let json: string = Box$toJson(box);\n"
            + "    let expected: string = " + dealStringLiteral(expected) + ";\n"
            + "    if (json !== expected) {\n"
            + "      throw { code: \"MISMATCH\", message: json };\n"
            + "    }\n"
            + "    return null;\n"
            + "  }\n"
            + "  throw { code: \"MISMATCH\", message: \"the explicit valid "
            + "document was rejected\" };\n}\n\n"
            + "export function main(): null {\n  return null;\n}\n";
        driveExactHelperText("nested-default-numeric-carrier", source);
    }

    /**
     * The oracle main coverage (the family drive's two legs): the main leg
     * lowers the fixture itself as the entry module, so its own entry
     * delegation invokes the declared {@code main} exactly once — a
     * main-only fixture's deliberate failure is captured, never skipped;
     * the export leg's driver invokes only the non-main exports (a
     * cross-module {@code main} call is no valid shape: the declaring
     * module records no {@code EXTERNAL_ENTRY} for it).
     */
    private static void testOracleDriverMainCoverage() throws Exception {
        System.out.println("-- the oracle's main coverage: a main-only fixture's "
            + "deliberate failure is captured by the main leg exactly once --");
        String source = "export function main(): null {\n"
            + "  throw { code: \"MAIN_REACHED\", message: \"ran\" };\n"
            + "}\n";
        List<Export> exports = exportsOf(source);
        checkEq(List.of("main"),
            exports.stream().map(Export::name).toList(),
            "the main-only control fixture carries exactly its main export");
        Project project = syntheticProject("app", source, Target.JVM);
        try {
            // The main leg: the fixture is the entry of its own closure and
            // its entry delegation carries the single ENTRY_INVOKE op (main's
            // one call site), so main runs exactly once.
            Lowered mainLeg = lower(project, "main-only control");
            if (mainLeg == null) {
                return;
            }
            int entryInvokes = 0;
            for (SemanticOp op : mainLeg.project().modules()
                    .get(mainLeg.entryModule()).ops()) {
                if (op.kind() == SemanticOpKind.ENTRY_INVOKE) {
                    entryInvokes++;
                }
            }
            checkEq(1, entryInvokes, "the main leg's entry delegation is the "
                + "fixture main's single invocation (one ENTRY_INVOKE op)");
            SemanticRuntimeModel.ErrorSnapshot mainFailure = oracleMainFailure(
                project, "main-only control (capture)");
            check(mainFailure != null, "the main-only fixture's deliberate failure "
                + "is captured by the oracle's main leg");
            if (mainFailure != null) {
                checkEq("MAIN_REACHED", mainFailure.code(), "the captured main "
                    + "failure's code");
                checkEq("ran", mainFailure.message(), "the captured main failure's "
                    + "message");
            }
            // The export leg: the driver never invokes the fixture's main.
            String driver = oracleDriver(project, exports);
            int driverMainCalls = driver.split(java.util.regex.Pattern
                .quote("fx.main()"), -1).length - 1;
            checkEq(0, driverMainCalls, "the export leg's driver invokes no "
                + "fixture main (" + driver + ")");
        } finally {
            deleteRecursively(project.root());
        }
    }

    /**
     * The pinned-failure leg's negative controls: the sidecar comparison
     * accepts the sidecar's own pin (the control) and rejects unexpected
     * process stdout, an incorrect process exit status, incorrect process
     * stderr, and a missing capture — the process-level pins the
     * runtime-error branch previously never compared.
     */
    private static void testRuntimeErrorSidecarNegativeControls() throws Exception {
        System.out.println("-- the pinned-failure leg's negative controls: the "
            + "process stdout, exit code, and stderr are part of the byte-exact "
            + "pin --");
        Optional<SidecarExpectations.RuntimeExpectation.Executed> pinned =
            pinnedOf(CYCLIC_FIXTURE);
        check(pinned.isPresent(), CYCLIC_FIXTURE + ": the pinned runtime-error "
            + "sidecar parses");
        if (pinned.isEmpty()) {
            return;
        }
        SidecarExpectations.RuntimeExpectation.Executed expectation = pinned.get();
        check(expectation.isRuntimeError(), CYCLIC_FIXTURE + ": the sidecar pins a "
            + "runtime error");
        SidecarExpectations.ErrorExpectation row = expectation.error();
        String stderr = new String(expectation.stderr(), StandardCharsets.UTF_8);
        Capture matching = new Capture(row.code(), row.message(), row.sourceFile(),
            row.line(), row.column(), row.expected().orElse(null),
            row.actual().orElse(null));
        String cleanStdout = new String(expectation.stdout(), StandardCharsets.UTF_8);
        List<String> accepted = runtimeErrorSidecarMismatches(expectation, row,
            expectation.exitCode(), "", stderr, matching);
        check(accepted.isEmpty(), "the comparator accepts the sidecar's own pin "
            + "(the control): " + accepted);
        List<String> extraStdout = runtimeErrorSidecarMismatches(expectation, row,
            expectation.exitCode(), "unexpected output\n", stderr, matching);
        check(extraStdout.stream().anyMatch(m -> m.contains("framed stdout")),
            "the comparator rejects unexpected process stdout: " + extraStdout);
        List<String> wrongExit = runtimeErrorSidecarMismatches(expectation, row,
            expectation.exitCode() + 1, "", stderr, matching);
        check(wrongExit.stream().anyMatch(m -> m.contains("exit code")),
            "the comparator rejects an incorrect process exit status: " + wrongExit);
        List<String> wrongStderr = runtimeErrorSidecarMismatches(expectation, row,
            expectation.exitCode(), "", "boom", matching);
        check(wrongStderr.stream().anyMatch(m -> m.contains("stderr")),
            "the comparator rejects incorrect process stderr: " + wrongStderr);
        List<String> noCapture = runtimeErrorSidecarMismatches(expectation, row,
            expectation.exitCode(), "", stderr, null);
        check(!noCapture.isEmpty(),
            "the comparator rejects a missing capture: " + noCapture);
        check(cleanStdout.length() > 0,
            "the pinned cyclic fixture carries a non-empty stdout transcript");
    }

    // =========================================================================
    // 8. Determinism and the verbatim export key
    // =========================================================================

    /**
     * Repeated lowering and emission of the same fixture are byte-identical
     * (two compiles of one lane-equivalent project emit the identical
     * artifact for each target), and the emitted helper publication carries
     * the DEAL helper name verbatim as its key on both targets (the
     * {@code $}-only-in-quoted-keys invariant: the Lua surface keys the
     * helper by a quoted string, the JVM surface by the same string).
     */
    private static void testDeterminismAndExportKey() throws Exception {
        System.out.println("-- determinism and the verbatim export key --");
        List<String> fixtures = List.of(
            FAMILY_DIR + "/jsonable-helper-exports",
            FAMILY_DIR + "/jsonable-cross-module-nested-class-array");
        List<String> keys = List.of("User$fromJson", "Parent$fromJson");
        for (int index = 0; index < fixtures.size(); index++) {
            String fixtureRel = fixtures.get(index);
            String helper = keys.get(index);
            for (Target target : Target.values()) {
                Project project = materialize(fixtureRel, target);
                try {
                    List<String> firstDiagnostics = new ArrayList<>();
                    List<String> secondDiagnostics = new ArrayList<>();
                    boolean firstCompiled = compile(project, fixtureRel, target,
                        firstDiagnostics);
                    String first = firstCompiled
                        ? Files.readString(artifactOf(project, target)) : null;
                    boolean secondCompiled = compile(project, fixtureRel, target,
                        secondDiagnostics);
                    String second = secondCompiled
                        ? Files.readString(artifactOf(project, target)) : null;
                    check(firstCompiled && secondCompiled, fixtureRel + " ["
                        + target.laneName() + "]: both drives compile: "
                        + firstDiagnostics + " " + secondDiagnostics);
                    if (firstCompiled && secondCompiled) {
                        checkEq(first, second, fixtureRel + " ["
                            + target.laneName() + "]: repeated lowering and "
                            + "emission are byte-identical");
                        checkEq(firstDiagnostics, secondDiagnostics, fixtureRel
                            + " [" + target.laneName() + "]: repeated lowering "
                            + "carries no diagnostic");
                    }
                    if (first == null) {
                        continue;
                    }
                    // The publication's key is the DEAL helper name verbatim:
                    // the emitted Lua surface keys it as a quoted string, the
                    // emitted JVM surface writes the same string.
                    String publication = target == Target.LUAJIT
                        ? "[\"" + helper + "\"] = {__kind = \"function\""
                        : ".write(\"" + helper + "\", ";
                    check(first.contains(publication), fixtureRel + " ["
                        + target.laneName() + "]: the emitted helper publication "
                        + "keys the DEAL name verbatim (" + publication + ")");
                } finally {
                    deleteRecursively(project.root());
                }
            }
        }
    }

    private static void deleteRecursively(Path dir) throws Exception {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path path : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    public static void main(String[] args) throws Exception {
        testCorpusInventory();
        testProductionDrive();
        testOracleAgreement();
        testHelperSurface();
        testNegatives();
        testMissingHelperFactsFailClosed();
        testDynamicHelperOrigin();
        testTableClassInstanceRejection();
        testNestedTableInsertionOrder();
        testNestedTableNumericVariants();
        testNestedClassDepthBound();
        testArrayExecutorViewSync();
        testArrayExecutorViewDeleteParity();
        testNullableArrayElements();
        testReadDerivedNumericCarriers();
        testNestedClassDefaultNumericCarrier();
        testOracleDriverMainCoverage();
        testRuntimeErrorSidecarNegativeControls();
        testDeterminismAndExportKey();
        System.out.println("");
        System.out.println("Jsonable helper production drive: " + passed
            + " passed, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }
}
