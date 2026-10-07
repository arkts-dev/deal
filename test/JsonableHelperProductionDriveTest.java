package deal.test;

import deal.checker.BuiltinErrorDeclaration;
import deal.codegen.Backend;
import deal.codegen.jvm.JvmBackend;
import deal.diagnostics.CompilerDiagnostic;
import deal.module.CompilationOrchestrator;
import deal.project.ProjectContext;
import deal.project.ProjectLocator;
import deal.semantic.CheckedProjectBuildResult;
import deal.semantic.CompilerInvocation;
import deal.semantic.CompilerProfileProvider;
import deal.semantic.HostDeclarationSurface;
import deal.semantic.ReleaseConfiguration;
import deal.semantic.RequirementManifestResult;
import deal.semantic.SemanticLowerer;
import deal.semantic.SemanticOracle;
import deal.semantic.SemanticRuntimeModel;
import deal.semantic.ir.CallMode;
import deal.semantic.ir.ExecutableLoweredProject;
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
 * <p>Two three-consumer regressions close the walk's remaining arms: a
 * function-parameter invocation of a helper ({@code f(w)} inside the
 * callee body) must render the pinned JSON failure at the {@code f(w)}
 * call expression in the oracle, the LuaJIT artifact, and the JVM
 * artifact alike, and a class instance inside an array nested in a table
 * field must be rejected by all three consumers (the table-content walk
 * stays JSON-shaped).</p>
 *
 * <p>The combined dependency step runs the oracle over every fixture of
 * the family through the same one-lowering closure (the runtime-ok
 * success or the pinned error tuple at the raw corpus coordinates), and
 * captures the cross-module helper call and the pinned helper JSON
 * failure from the lowered project through {@link SemanticOracle}, so the
 * comparison fails if the canonical failure projection authority is
 * broken.</p>
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
        check(capture != null && capture.code() != null, fixtureRel + " ["
            + target.laneName() + "]: the pinned failure is captured (exit "
            + execution.exitCode() + "; stderr " + execution.stderr() + ")");
        if (capture == null || capture.code() == null) {
            return "no-capture";
        }
        checkEq(row.code(), capture.code(), fixtureRel + " [" + target.laneName()
            + "]: the pinned code");
        checkEq(row.message(), capture.message(), fixtureRel + " [" + target.laneName()
            + "]: the pinned message");
        checkEq(row.sourceFile(), capture.sourceFile(), fixtureRel + " ["
            + target.laneName() + "]: the pinned span file (raw corpus coordinates)");
        checkEq(row.line(), capture.line(), fixtureRel + " [" + target.laneName()
            + "]: the pinned raw corpus line");
        checkEq(row.column(), capture.column(), fixtureRel + " [" + target.laneName()
            + "]: the pinned column");
        checkEq(row.expected().orElse(null), capture.expected(), fixtureRel + " ["
            + target.laneName() + "]: the pinned expected field");
        checkEq(row.actual().orElse(null), capture.actual(), fixtureRel + " ["
            + target.laneName() + "]: the pinned actual field");
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
        checkEq(new String(expectation.stdout(), StandardCharsets.UTF_8), framed,
            fixtureRel + " [" + target.laneName()
                + "]: the pin-exact capture reproduces the sidecar transcript "
                + "byte-for-byte");
        return "pin-exact";
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
     * fixture is a non-entry module here (its {@code main} is invoked by
     * the driver's own entry delegation), so every test export runs
     * exactly as the artifact probe's ordered loop runs it.
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

    private static String oracleOutcome(Project project,
            SemanticRuntimeModel.ConsumerRun run) {
        if (run.terminal() instanceof SemanticRuntimeModel.Terminal.Success) {
            return "ok";
        }
        SemanticRuntimeModel.ErrorSnapshot error =
            ((SemanticRuntimeModel.Terminal.DealFailure) run.terminal()).error();
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
            + "family fixture (the cross-module helper call and the pinned "
            + "helper JSON failure included) --");
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
                Path driver = project.srcRoot().resolve("__oracle_drive.deal");
                Files.writeString(driver, oracleDriver(project, exports),
                    StandardCharsets.UTF_8);
                Lowered lowered = lower(project, driver, fixtureRel + " (oracle)");
                if (lowered == null) {
                    continue;
                }
                String outcome = oracleOutcome(project,
                    SemanticOracle.executeProjectInits(lowered.project(),
                        lowered.tables(), lowered.registries(),
                        new SemanticOracle.HostResponder() { }));
                System.out.println("   oracle " + fixtureRel + ": " + outcome);
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
            + "non-@jsonable class, and the missing helper export --");
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
        testDynamicHelperOrigin();
        testTableClassInstanceRejection();
        testDeterminismAndExportKey();
        System.out.println("");
        System.out.println("Jsonable helper production drive: " + passed
            + " passed, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }
}
