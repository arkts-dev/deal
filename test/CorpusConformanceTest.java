package deal.test;

import deal.ast.*;
import deal.checker.*;
import deal.codegen.Backend;
import deal.diagnostics.CompilerDiagnostic;
import deal.lexer.*;
import deal.module.CompilationOrchestrator;
import deal.module.ExportExtractor;
import deal.module.ModuleShapeValidator;
import deal.module.StdlibModuleResolver;
import deal.parser.*;
import deal.semantic.CompilerInvocation;
import deal.semantic.CompilerProfileProvider;
import deal.semantic.ReleaseConfiguration;
import deal.semantic.ir.ReleaseState;
import deal.types.Type;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

public class CorpusConformanceTest {
    private static final ThreadLocal<Backend> TARGET = ThreadLocal.withInitial(() -> Backend.JS);
    private static String backend() { return TARGET.get().name().toLowerCase(java.util.Locale.ROOT); }
    private static final Set<String> completedLegs = ConcurrentHashMap.newKeySet();
    private static final Set<String> requiredLegs = new java.util.TreeSet<>();

    private static final CompilerInvocation LANE_INVOCATION =
        CompilerProfileProvider.resolve(ReleaseState.V1_5_ACTIVE,
            ReleaseConfiguration.releaseCapabilityRegistry());

    // =========================================================================
    // Host harness: one CommonJS implementation per host fixture
    // =========================================================================

    private static Map<String, byte[]> HOST_JS = Map.of();

    // =========================================================================
    // Data types and counters
    // =========================================================================

    private record TestFile(Path path, String relativePath, String spec,
            String description, String expected, String features,
            String issue) {}

    private enum Kind {
        /** compile-ok / compile-error — backend-neutral frontend gate. */
        FRONTEND,
        /** runtime-ok / runtime-error — three production-backend obligations. */
        APPLICABLE,
        /** @expected: companion — classified support module;
         *  standalone-compiled, never node-executed standalone. */
        COMPANION
    }

    private record Classified(TestFile test, Kind kind) {}

    private record Outcome(TestFile test, Classified classified,
            boolean pass, String message) {}

    /** The corpus-coordinate projection of one materialized source: the
     * fixture's corpus-relative path and the number of classification
     * header lines the harness removed, so a Node origin (the physical
     * project file and its stripped line number) projects back to the raw
     * corpus coordinate the sidecar pins. */
    private record SourcePin(String corpusPath, int headerLines) {}

    /** One passing runtime-error observation retained for the exact-verdict
     * negative control: the real Node capture and the js sidecar
     * expectation it matched. */
    private record RuntimeErrorObservation(TestFile test, NodeResult result,
            deal.test.conformance.SidecarExpectations
                .RuntimeExpectation.Executed pinned) {}

    private record OrchestratorRun(boolean success,
                                   List<CompilerDiagnostic> diagnostics,
                                   String capturedOutput) {}

    private record NodeResult(int exitCode, String stdout, String stderr,
            boolean stdoutTruncated, boolean stderrTruncated) {

        /** True when the shared bounded helper capped a stream: the
         * retained prefix plus its truncation marker is diagnostic output,
         * never a complete transcript. */
        boolean captureIncomplete() {
            return stdoutTruncated || stderrTruncated;
        }
    }

    /** A harness-defect failure (missing host declaration/implementation,
     * a manifest the harness generated that does not load): never a
     * fixture pass, never an applicable failure. */
    private static final class HarnessFailure extends RuntimeException {
        HarnessFailure(String message) {
            super(message);
        }
    }

    private static final AtomicInteger frontendTotal = new AtomicInteger();
    private static final AtomicInteger frontendPassed = new AtomicInteger();
    private static final AtomicInteger frontendFailed = new AtomicInteger();
    private static final AtomicInteger applicableTotal = new AtomicInteger();
    private static final AtomicInteger applicablePassed = new AtomicInteger();
    private static final AtomicInteger applicableFailed = new AtomicInteger();
    private static final AtomicInteger companionTotal = new AtomicInteger();
    private static final AtomicInteger companionPassed = new AtomicInteger();
    private static final AtomicInteger companionFailed = new AtomicInteger();
    private static final AtomicInteger nodeExecuted = new AtomicInteger();

    private static final List<Outcome> outcomes =
        Collections.synchronizedList(new ArrayList<>());

    private static final AtomicReference<RuntimeErrorObservation> runtimeErrorObservation =
        new AtomicReference<>();
    /** Corpus-relative paths of every file materialized into an
     * importing fixture's temp project (companions included). */
    private static final Set<String> materializedCorpusFiles =
        Collections.synchronizedSet(new LinkedHashSet<>());

    private static final ThreadLocal<StringBuilder> WORKER_OUTPUT =
        new ThreadLocal<>();
    private static final Object CONSOLE_LOCK = new Object();

    private record SourceSnapshot(String raw, String stripped, List<String> relativeImports) {
        SourceSnapshot {
            relativeImports = List.copyOf(relativeImports);
        }
    }

    private static Map<Path, SourceSnapshot> sourceSnapshots = new ConcurrentHashMap<>();

    private static SourceSnapshot sourceSnapshot(Path file) throws IOException {
        Path normalized = file.toAbsolutePath().normalize();
        try {
            return sourceSnapshots.computeIfAbsent(normalized, path -> {
                try {
                    String raw = Files.readString(path);
                    return new SourceSnapshot(raw,
                        ConformanceHarnessMetadata.stripClassificationHeaders(raw),
                        relativeImportsOf(raw));
                } catch (IOException failure) {
                    throw new UncheckedIOException(failure);
                }
            });
        } catch (UncheckedIOException failure) {
            throw failure.getCause();
        }
    }

    private static String rawSource(Path file) throws IOException {
        return file.toAbsolutePath().normalize().startsWith(conformanceRoot)
            ? sourceSnapshot(file).raw() : Files.readString(file);
    }

    private static Path conformanceRoot = Path.of("test/conformance/")
        .toAbsolutePath().normalize();
    private static Path hostFixturesRoot =
        conformanceRoot.resolve("host-fixtures");
    private static final Path REPO_ROOT = Path.of("")
        .toAbsolutePath().normalize();

    private static final long SUBPROCESS_TIMEOUT_SECONDS = 120;

    /** Companion accounting expectations, computed before the pool runs:
     * every companion corpus-relative path, the companions reachable
     * (transitively) from runtime-classified fixtures, and the
     * companions reachable from any classified fixture. */
    private static Set<String> companionPaths = Set.of();
    private static Set<String> reachableFromRuntime = Set.of();
    private static Set<String> importedByAny = Set.of();
    private static int onDiskTotal;
    private static int onDiskCompanionCount;
    private static int onDiskRuntimeDenominator;

    private static final Pattern EXPORTED_MAIN = Pattern.compile(
        "export\\s+function\\s+main\\s*\\(");

    private static void log(String line) {
        StringBuilder buffer = WORKER_OUTPUT.get();
        if (buffer == null) {
            System.out.println(line);
        } else {
            buffer.append(line).append('\n');
        }
    }

    // =========================================================================
    // Main
    // =========================================================================

    public static void main(String[] args) throws Exception {
        sourceSnapshots = new ConcurrentHashMap<>();
        if (args.length > 0) {
            conformanceRoot = Path.of(args[0]).toAbsolutePath().normalize();
            hostFixturesRoot = conformanceRoot.resolve("host-fixtures");
        }
        if (!Files.isDirectory(conformanceRoot)) {
            System.out.println("No conformance root found at "
                + conformanceRoot);
            System.exit(2);
        }

        // Node absence is a hard run failure, never a skip: the
        // node-executed denominator can only be satisfied by real node
        // subprocess executions.
        if (!probeNode()) {
            System.out.println("GATE FAILURE: node is not available — the "
                + "JS corpus gate requires the environment's Node.js; a "
                + "bypassed node execution is never a pass");
            System.exit(1);
        }

        try {
            // Harness integrity first: the HOST_JS map must match the
            // on-disk host-fixtures subtree exactly (a missing or
            // wrong-shaped implementation is a harness failure, never a
            // pass).
            validateHostHarness();
            for (String tool : List.of("luajit", "javac")) {
                var check = BoundedProcess.runCaptured(List.of(tool, tool.equals("luajit") ? "-v" : "--version"));
                if (check.exitCode() != 0) throw new HarnessFailure("TOOL_MISSING: " + tool);
            }

            // The corpus capture controls next: the bounded capture's
            // cap boundary and the structured verdict's refusal of
            // incomplete observations are exercised over real node
            // children before any fixture runs.
            validateCorpusCaptureControls();

            List<TestFile> tests = discoverTests();
            List<Classified> classified = classifyAll(tests);
            computeCompanionAccounting(tests, classified);
            for (Classified item : classified) if (item.kind() == Kind.APPLICABLE) {
                var sidecar = sidecarOf(item.test().path());
                for (String backend : sidecar.byBackend().keySet()) requiredLegs.add(item.test().relativePath() + ":" + backend);
            }

            int frontend = (int) classified.stream()
                .filter(c -> c.kind() == Kind.FRONTEND).count();
            int applicable = (int) classified.stream()
                .filter(c -> c.kind() == Kind.APPLICABLE).count();
            int companions = (int) classified.stream()
                .filter(c -> c.kind() == Kind.COMPANION).count();
            System.out.println("=== DEAL v1.5 Production Corpus Conformance (JS / LuaJIT / JVM) ===");
            System.out.println("Root: " + conformanceRoot);
            System.out.println("Node: available (a missing node is a hard "
                + "run failure, never a skip)");
            System.out.println("Discovered " + tests.size()
                + " backend-runtime conformance test(s): " + frontend
                + " frontend-classified, " + applicable
                + " runtime fixtures (three backend obligations each), " + companions
                + " companions (classified support modules)");
            System.out.println();

            // Deterministic execution order: sorted by corpus-relative
            // path.
            List<Classified> ordered = new ArrayList<>(classified);
            ordered.sort(Comparator.comparing(
                c -> c.test().relativePath()));

            CorpusExecution.controls();
            int jobs = Integer.parseInt(System.getProperty("deal.test.jobs", "1"));
            if (jobs < 1) {
                throw new IllegalArgumentException("deal.test.jobs must be positive");
            }
            int workers = Math.max(1, Math.min(jobs, ordered.size()));
            ExecutorService pool = Executors.newFixedThreadPool(workers);
            try {
                List<Future<?>> futures = new ArrayList<>();
                for (Classified item : ordered) {
                    futures.add(pool.submit(() -> runOne(item)));
                }
                for (Future<?> future : futures) {
                    future.get();
                }
            } finally {
                pool.shutdownNow();
            }

            // The exact-verdict negative control runs over a real accepted
            // Node observation before the report: the lane must refuse a
            // wrong message, actual token and origin on the same capture.
            validateRuntimeErrorVerdictControls();

            List<Outcome> sorted = new ArrayList<>(outcomes);
            sorted.sort(Comparator.comparing(
                o -> o.test().relativePath()));
            printReport(sorted);
        } catch (RuntimeException e) {
            // Classification failures (unknown @expected, a missing
            // @expected, a mis-classified companion, a dead HOST_JS
            // entry) fail the run — there is no skip branch.
            System.out.println("FAIL (classification failure): "
                + e.getMessage());
            System.exit(1);
        }
    }

    /** Probes that {@code node} is invocable and functional (the same
     * hard gate the JS e2e runner uses). */
    private static boolean probeNode() {
        try { return BoundedProcess.runCaptured(List.of("node", "--version")).exitCode() == 0; }
        catch (IOException | InterruptedException e) { return false; }
    }

    // =========================================================================
    // Host harness validation (js-v12-host-abi-completion D6)
    // =========================================================================

    private static void validateHostHarness() throws IOException {
        if (!Files.isDirectory(hostFixturesRoot)) {
            System.out.println("GATE FAILURE: host-fixtures directory "
                + "missing at " + hostFixturesRoot + " — the HOST_JS "
                + "harness cannot be validated");
            System.exit(1);
        }
        List<String> declNames = new ArrayList<>();
        List<String> luaNames = new ArrayList<>();
        List<String> javaNames = new ArrayList<>();
        List<String> jsNames = new ArrayList<>();
        try (var stream = Files.list(hostFixturesRoot)) {
            for (Path p : (Iterable<Path>) stream.sorted()::iterator) {
                String name = p.getFileName().toString();
                if (name.endsWith(".d.deal")) {
                    declNames.add(name.substring(0,
                        name.length() - ".d.deal".length()));
                } else if (name.endsWith(".lua")) {
                    luaNames.add(name.substring(0,
                        name.length() - ".lua".length()));
                } else if (name.endsWith(".java")) {
                    javaNames.add(name.substring(0,
                        name.length() - ".java".length()));
                } else if (name.endsWith(".js")) {
                    jsNames.add(name.substring(0,
                        name.length() - ".js".length()));
                }
            }
        }
        Collections.sort(declNames);
        Collections.sort(luaNames);
        Collections.sort(javaNames);
        Collections.sort(jsNames);

        boolean ok = true;
        // Triplet layout completeness: the declarations and all three
        // per-backend implementation sets must name the same fixtures.
        if (!declNames.equals(luaNames) || !declNames.equals(javaNames)
                || !declNames.equals(jsNames)) {
            System.out.println("GATE FAILURE: host-fixtures triplet "
                + "layout mismatch — .d.deal names " + declNames
                + " vs .lua names " + luaNames + " vs .java names "
                + javaNames + " vs .js names " + jsNames
                + " (harness failure; a gap is never a pass)");
            ok = false;
        }
        Map<String, byte[]> implementations = new LinkedHashMap<>();
        for (String name : declNames) {
            Path onDiskJs = hostFixturesRoot.resolve(name + ".js");
            if (!Files.isRegularFile(onDiskJs)) {
                System.out.println("GATE FAILURE: the on-disk host "
                    + "implementation " + onDiskJs + " is missing"
                    + " (harness failure, never a pass)");
                ok = false;
                continue;
            }
            byte[] bytes = Files.readAllBytes(onDiskJs);
            String source = new String(bytes, StandardCharsets.UTF_8);
            if (source.isBlank() || !source.contains("module.exports")) {
                System.out.println("GATE FAILURE: the on-disk host "
                    + "implementation " + onDiskJs + " is not a "
                    + "well-shaped CommonJS module (missing "
                    + "module.exports) — a wrong-shaped implementation "
                    + "is a harness failure, never a pass");
                ok = false;
                continue;
            }
            implementations.put(name, bytes);
        }
        if (!ok) {
            System.exit(1);
        }
        HOST_JS = Map.copyOf(implementations);
        System.out.println("Host harness: " + declNames.size()
            + " host-fixture declaration(s) validated against "
            + HOST_JS.size() + " cached on-disk .js implementation(s)");
    }

    // =========================================================================
    // Discovery and classification
    // =========================================================================

    private static List<TestFile> discoverTests() throws IOException {
        Path backendRoot = conformanceRoot.resolve("backend-runtime");
        List<TestFile> result = new ArrayList<>();
        try (var stream = Files.walk(backendRoot)) {
            stream.filter(p -> p.toString().endsWith(".deal"))
                  .sorted()
                  .forEach(p -> {
                      TestFile tf = parseMetadata(p);
                      if (tf != null) {
                          result.add(tf);
                      }
                  });
        }
        return result;
    }

    private static TestFile parseMetadata(Path file) {
        try {
            List<String> lines = sourceSnapshot(file).raw().lines().toList();
            String spec = "";
            String description = "";
            String expected = "";
            String features = "";
            String issue = "";

            int linesToScan = Math.min(lines.size(), 40);
            for (int i = 0; i < linesToScan; i++) {
                String line = lines.get(i).trim();
                if (line.startsWith("// @spec:")) {
                    spec = line.substring("// @spec:".length()).trim();
                } else if (line.startsWith("// @description:")) {
                    description = line.substring(
                        "// @description:".length()).trim();
                } else if (line.startsWith("// @expected:")) {
                    expected = line.substring("// @expected:".length()).trim();
                } else if (line.startsWith("// @features:")) {
                    features = line.substring(
                        "// @features:".length()).trim();
                } else if (line.startsWith("// @issue:")) {
                    issue = line.substring("// @issue:".length()).trim();
                }
            }

            if (expected.isEmpty()) {
                throw new IllegalStateException("no @expected tag in "
                    + file + " — the v1.5 gate has no unclassified files");
            }

            Path normalized = file.toAbsolutePath().normalize();
            Path rel = conformanceRoot.relativize(normalized);
            String relPath = rel.toString().replace('\\', '/');
            return new TestFile(normalized, relPath, spec, description,
                expected, features, issue);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
    }

    /**
     * The deterministic classification policy. See the class javadoc.
     * An unknown or missing {@code @expected}, or a companion that
     * exports {@code main}, is a classification failure.
     */
    private static List<Classified> classifyAll(List<TestFile> tests) {
        List<Classified> result = new ArrayList<>();
        for (TestFile test : tests) {
            String expected = test.expected();
            if (expected.equals("companion")) {
                // Classified support modules carry no entry main and are
                // never node-executed standalone.
                if (exportsMain(test.path())) {
                    throw new IllegalStateException("companion "
                        + test.relativePath() + " exports main — "
                        + "companions are classified support modules that "
                        + "are never node-executed standalone");
                }
                result.add(new Classified(test, Kind.COMPANION));
            } else if (expected.startsWith("compile-ok")) {
                result.add(new Classified(test, Kind.FRONTEND));
            } else if (expected.startsWith("compile-error ")) {
                result.add(new Classified(test, Kind.FRONTEND));
            } else if (expected.startsWith("runtime-ok")) {
                result.add(new Classified(test, Kind.APPLICABLE));
            } else if (expected.startsWith("runtime-error ")) {
                result.add(new Classified(test, Kind.APPLICABLE));
            } else {
                // Unknown @expected is a harness failure, never a skip.
                throw new IllegalStateException("unknown @expected '"
                    + expected + "' in " + test.relativePath());
            }
        }
        return result;
    }

    /** True when the file declares an exported {@code main} function. */
    private static boolean exportsMain(Path file) {
        try {
            String source = rawSource(file);
            return EXPORTED_MAIN.matcher(source).find();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
    }

    /**
     * Computes the companion accounting expectations over the on-disk
     * corpus before the worker pool runs: the denominator totals, the
     * companion path set, the companions reachable (transitively) from
     * runtime-classified fixtures, and the
     * companions reachable from any classified non-companion fixture
     * (the dead-companion check).
     */
    private static void computeCompanionAccounting(List<TestFile> tests,
            List<Classified> classified) {
        onDiskTotal = tests.size();
        onDiskCompanionCount = (int) tests.stream()
            .filter(t -> t.expected().equals("companion")).count();
        onDiskRuntimeDenominator = (int) tests.stream()
            .filter(t -> t.expected().startsWith("runtime-ok")
                || t.expected().startsWith("runtime-error "))
            .count();

        Map<String, TestFile> byPath = new LinkedHashMap<>();
        for (TestFile test : tests) {
            byPath.put(test.path().toString(), test);
        }

        Set<String> companions = new LinkedHashSet<>();
        for (Classified c : classified) {
            if (c.kind() == Kind.COMPANION) {
                companions.add(c.test().relativePath());
            }
        }
        companionPaths = Set.copyOf(companions);

        Set<String> fromRuntime = new LinkedHashSet<>();
        Set<String> fromAny = new LinkedHashSet<>();
        for (Classified c : classified) {
            if (c.kind() == Kind.COMPANION) continue;
            Set<String> reach = transitivelyImportedCompanions(
                c.test().path(), byPath);
            fromAny.addAll(reach);
            if (c.kind() == Kind.APPLICABLE) {
                fromRuntime.addAll(reach);
            }
        }
        reachableFromRuntime = Set.copyOf(fromRuntime);
        importedByAny = Set.copyOf(fromAny);
    }

    /** The set of companion corpus-relative paths transitively reachable
     * from a fixture through its on-disk relative imports. */
    private static Set<String> transitivelyImportedCompanions(Path file,
            Map<String, TestFile> byPath) {
        Set<String> result = new LinkedHashSet<>();
        Deque<Path> stack = new ArrayDeque<>();
        Set<Path> seen = new HashSet<>();
        stack.push(file.toAbsolutePath().normalize());
        while (!stack.isEmpty()) {
            Path current = stack.pop();
            if (!seen.add(current)) continue;
            try {
                for (String importPath : relativeImports(current)) {
                    Path resolved = resolveCompanionPath(importPath,
                        current.getParent());
                    if (resolved == null) continue;
                    Path normalized = resolved.toAbsolutePath().normalize();
                    TestFile target = byPath.get(normalized.toString());
                    if (target != null
                            && target.expected().equals("companion")) {
                        result.add(target.relativePath());
                    }
                    stack.push(normalized);
                }


            } catch (IOException ignored) {
                // Unreadable file: the importer's run reports the failure.
            }
        }
        return result;
    }

    // =========================================================================
    // Execution dispatch
    // =========================================================================

    private static void runOne(Classified classified) {
        StringBuilder buffer = new StringBuilder();
        WORKER_OUTPUT.set(buffer);
        try {
            Outcome outcome = switch (classified.kind()) {
                case FRONTEND -> runFrontend(classified);
                case APPLICABLE -> {
                    Outcome last = null;
                    for (Backend target : List.of(Backend.JS, Backend.LUAJIT, Backend.JVM)) {
                        TARGET.set(target);
                        try { last = runApplicable(classified); }
                        catch (Throwable e) {
                            applicableFailed.incrementAndGet();
                            last = new Outcome(classified.test(), classified, false, backend() + ": " + e);
                        }
                        outcomes.add(last);
                        log("    " + backend() + ": " + (last.pass() ? "PASS" : "FAIL " + last.message()));
                        if (last.pass() && !completedLegs.add(classified.test().relativePath() + ":" + backend()))
                            throw new HarnessFailure("duplicate consumer leg");
                    }
                    TARGET.remove();
                    yield null;
                }
                case COMPANION -> runCompanion(classified);
            };
            if (outcome != null) {
                outcomes.add(outcome);
            }
        } catch (Throwable e) {
            log("  [" + classified.test().relativePath()
                + "] FAIL: runner exception: " + e.getMessage());
            if (classified.kind() == Kind.FRONTEND) {
                frontendFailed.incrementAndGet();
                outcomes.add(new Outcome(classified.test(), classified,
                    false, "runner exception: " + e.getMessage()));
            } else if (classified.kind() == Kind.APPLICABLE) {
                applicableFailed.incrementAndGet();
                outcomes.add(new Outcome(classified.test(), classified,
                    false, "runner exception: " + e.getMessage()));
            } else {
                companionFailed.incrementAndGet();
                outcomes.add(new Outcome(classified.test(), classified,
                    false, "runner exception: " + e.getMessage()));
            }
        } finally {
            TARGET.remove();
            WORKER_OUTPUT.remove();
            synchronized (CONSOLE_LOCK) {
                System.out.print(buffer);
            }
        }
    }

    // =========================================================================
    // Frontend gate (backend-neutral compile-ok / compile-error)
    // =========================================================================

    private static Outcome runFrontend(Classified classified) {
        TestFile test = classified.test();
        frontendTotal.incrementAndGet();
        List<CompilerDiagnostic> diags = frontendDiagnostics(test.path());
        boolean hasErrors = diags.stream()
            .anyMatch(d -> "error".equals(d.severity()));
        String expected = test.expected();
        if (expected.startsWith("compile-ok")) {
            if (hasErrors) {
                frontendFailed.incrementAndGet();
                StringBuilder sb = new StringBuilder();
                for (CompilerDiagnostic d : diags) {
                    if ("error".equals(d.severity())) {
                        sb.append("    ").append(d).append('\n');
                    }
                }
                log("  [" + test.relativePath()
                    + "] FAIL (unexpected compile errors)\n" + sb);
                return new Outcome(test, classified, false,
                    "unexpected compile errors");
            }
            frontendPassed.incrementAndGet();
            log("  [" + test.relativePath() + "] OK (frontend)");
            return new Outcome(test, classified, true, "compile ok");
        }
        String code = expected.substring("compile-error ".length()).trim();
        boolean found = diags.stream().anyMatch(
            d -> "error".equals(d.severity()) && code.equals(d.code()));
        if (found) {
            frontendPassed.incrementAndGet();
            log("  [" + test.relativePath() + "] OK (found " + code + ")");
            return new Outcome(test, classified, true, "found " + code);
        }
        List<String> gotCodes = diags.stream()
            .filter(d -> "error".equals(d.severity()))
            .map(CompilerDiagnostic::code)
            .toList();
        frontendFailed.incrementAndGet();
        log("  [" + test.relativePath() + "] FAIL (expected " + code
            + ", got: " + gotCodes + ")");
        return new Outcome(test, classified, false,
            "expected " + code + ", got: " + gotCodes);
    }

    /**
     * The shared frontend pipeline — lexer → parser → name resolver →
     * type checker — with the same stdlib/relative-import resolution the
     * LuaJIT conformance harness uses (stdlib exports from the
     * spec-listed .d.deal declarations, relative imports via
     * {@link ExportExtractor}). A bypassed parser or checker yields no
     * diagnostics and the compile-error tests fail.
     */
    @SuppressWarnings("deprecation")
    private static List<CompilerDiagnostic> frontendDiagnostics(Path file) {
        List<CompilerDiagnostic> all = new ArrayList<>();
        try {
            String source = sourceSnapshot(file).stripped();
            String filename = file.toString();

            LexResult lex = new Lexer(source, filename).tokenize();
            all.addAll(lex.diagnostics());
            if (lex.hasErrors()) return all;

            Parser parser = new Parser(lex.tokens(), filename, lex.directiveEvents());
            ParseResult parseResult = parser.parse();
            all.addAll(parseResult.diagnostics());
            if (parseResult.hasErrors()) return all;

            FrontendModuleResolver resolver = new FrontendModuleResolver(file);
            NameResolver nr = new NameResolver(filename, resolver);
            SymbolTable symTable;
            try {
                symTable = nr.resolve(parseResult.program());
            } catch (Exception e) {
                // E9999 is the test-only pseudo code for an unexpected
                // NameResolver exception: the deprecated synthetic factory
                // carries the canonical synthetic range plus an anchor
                // note naming the fixture file.
                all.add(CompilerDiagnostic.synthetic("E9999", "error",
                    e.getMessage(), filename,
                    "missing anchor: fixture source '" + filename + "'"));
                return all;
            }
            all.addAll(nr.diagnostics());

            CheckResult result = TypeChecker.check(filename, symTable, nr,
                parseResult.program());
            all.addAll(result.diagnostics());

            return all;
        } catch (IOException e) {
            String filename = file.toString();
            all.add(CompilerDiagnostic.synthetic("E9999", "error",
                "cannot read " + file, filename,
                "missing anchor: fixture source '" + filename + "'"));
            return all;
        }
    }

    /** Module resolver for the frontend gate: spec-listed stdlib exports
     * and relative {@code ./} / {@code ../} imports resolved through
     * {@link ExportExtractor} with synthesized class symbols — the same
     * resolution surface the LuaJIT conformance harness uses for
     * compile-stage tests. */
    private static final class FrontendModuleResolver
            implements ModuleResolver {
        private final Path testFileDir;
        private final Map<String, Map<String, Type>> stdlibExports;

        FrontendModuleResolver(Path testFile) {
            this.testFileDir = testFile.toAbsolutePath().getParent();
            this.stdlibExports = StdlibModuleResolver.stdlibExports(
                Path.of("std").toAbsolutePath().normalize().toString());
        }

        @Override
        public Map<String, Type> resolveModule(String modulePath,
                String importingModule, Set<String> modulesInProgress)
                throws ModuleNotFoundException {
            if (stdlibExports.containsKey(modulePath)) {
                return stdlibExports.get(modulePath);
            }
            if (modulePath.startsWith("std/") && !modulePath.startsWith("./")
                    && !modulePath.startsWith("../")) {
                throw new ModuleNotFoundException(
                    "Module not found: '" + modulePath
                    + "' is not a spec-listed stdlib module");
            }


            Path resolved = resolveRelativePath(modulePath);
            if (resolved != null && Files.exists(resolved)) {
                try {

                    String source = sourceSnapshot(resolved).stripped();
                    boolean isDecl = resolved.toString().endsWith(".d.deal");
                    LexResult lex = new Lexer(source, resolved.toString())
                        .tokenize();
                    if (lex.hasErrors()) {
                        throw new ModuleNotFoundException("Lex errors in "
                            + resolved);
                    }
                    Parser parser = new Parser(lex.tokens(),
                        resolved.toString());
                    ParseResult parseResult = parser.parse();
                    if (parseResult.hasErrors()) {
                        throw new ModuleNotFoundException("Parse errors in "
                            + resolved);
                    }
                    ExportExtractor extractor = new ExportExtractor(
                        resolved.toString(), isDecl);
                    return extractor.extract(parseResult.program());
                } catch (IOException e) {
                    throw new ModuleNotFoundException("Cannot read: "
                        + resolved);
                }
            }
            throw new ModuleNotFoundException("Module not found: "
                + modulePath);
        }

        @Override
        public Symbol.ClassSymbol resolveClassSymbol(String className,
                String modulePath, String importingModule)
                throws ModuleNotFoundException {
            if (modulePath == null || modulePath.isEmpty()) return null;
            Path resolved = resolveRelativePath(modulePath);
            if (resolved == null || !Files.exists(resolved)) return null;
            return classSymbolsOf(resolved).get(className);
        }

        @Override
        public Type resolveTypeNodeInModule(TypeNode typeNode,
                String modulePath, String importingModule)
                throws ModuleNotFoundException {
            if (modulePath == null || modulePath.isEmpty()) return null;
            Path resolved = resolveRelativePath(modulePath);
            if (resolved == null || !Files.exists(resolved)) return null;
            try {

                String source = sourceSnapshot(resolved).stripped();
                LexResult lex = new Lexer(source, resolved.toString())
                    .tokenize();
                if (lex.hasErrors()) return null;
                Parser parser = new Parser(lex.tokens(),
                    resolved.toString());
                ParseResult parseResult = parser.parse();
                if (parseResult.hasErrors()) return null;
                NameResolver nr = new NameResolver(resolved.toString(), this);
                nr.resolve(parseResult.program());
                return nr.resolveTypeNode(typeNode);
            } catch (Exception e) {
                return null;
            }
        }

        /** Parses a relative companion and synthesizes its class symbols
         * (the dotted module path view the checker consumes). */
        private Map<String, Symbol.ClassSymbol> classSymbolsOf(Path file) {
            Map<String, Symbol.ClassSymbol> symbols = new LinkedHashMap<>();
            try {

                String source = sourceSnapshot(file).stripped();
                LexResult lex = new Lexer(source, file.toString()).tokenize();
                if (lex.hasErrors()) return symbols;
                Parser parser = new Parser(lex.tokens(), file.toString(), lex.directiveEvents());
                ParseResult parseResult = parser.parse();
                if (parseResult.hasErrors()) return symbols;
                String dotted = modulePathOf(file);
                for (StatementNode stmt : parseResult.program().statements()) {
                    if (stmt instanceof ClassDeclaration cd) {
                        symbols.put(cd.name(), new Symbol.ClassSymbol(
                            cd.name(), cd.fields(), dotted,
                            IdentityTestFixtures.identityOf(dotted,
                                cd.name())));
                    } else if (stmt instanceof ExportDeclaration exp
                            && exp.declaration() instanceof ClassDeclaration cd) {
                        symbols.put(cd.name(), new Symbol.ClassSymbol(
                            cd.name(), cd.fields(), dotted,
                            IdentityTestFixtures.identityOf(dotted,
                                cd.name())));
                    }
                }
            } catch (IOException ignored) { }
            return symbols;
        }

        private String modulePathOf(Path file) {
            String name = file.getFileName().toString();
            if (name.endsWith(".d.deal")) {
                return name.substring(0, name.length() - ".d.deal".length());
            }
            return name.substring(0, name.length() - ".deal".length());
        }

        private Path resolveRelativePath(String importPath) {
            if (!importPath.startsWith("./") && !importPath.startsWith("../")) {
                return null;
            }
            Path resolved = testFileDir.resolve(importPath).normalize();
            if (Files.exists(resolved)) return resolved;
            Path withExt = testFileDir.resolve(importPath + ".deal").normalize();
            if (Files.exists(withExt)) return withExt;
            Path withDeclExt = testFileDir.resolve(
                importPath + ".d.deal").normalize();
            if (Files.exists(withDeclExt)) return withDeclExt;
            return null;
        }
    }

    // =========================================================================
    // Companions: standalone compilation through the shared frontend /
    // declaration gate
    // =========================================================================

    private static Outcome runCompanion(Classified classified) {
        TestFile test = classified.test();
        companionTotal.incrementAndGet();
        List<CompilerDiagnostic> diags =
            compileCompanionStandalone(test.path());
        boolean hasErrors = diags.stream()
            .anyMatch(d -> "error".equals(d.severity()));
        if (hasErrors) {
            companionFailed.incrementAndGet();
            StringBuilder sb = new StringBuilder();
            for (CompilerDiagnostic d : diags) {
                if ("error".equals(d.severity())) {
                    sb.append("    ").append(d).append('\n');
                }
            }
            log("  [" + test.relativePath()
                + "] FAIL (companion support module does not compile)\n"
                + sb);
            return new Outcome(test, classified, false,
                "companion does not compile");
        }
        companionPassed.incrementAndGet();
        log("  [" + test.relativePath()
            + "] COMPANION (classified support module; compiles)");
        return new Outcome(test, classified, true, "companion compiles");
    }

    /**
     * Compiles a classified companion support module standalone. Regular
     * {@code .deal} companions run the shared frontend pipeline
     * (lexer/parser, the v1.2 module shape gate, name resolution, type
     * checking). {@code .d.deal} declaration companions run the
     * declaration-file pipeline the harness itself uses when the parent
     * fixture imports them (lexer/parser plus {@link ExportExtractor}),
     * because external function declarations have no body to type-check.
     * The {@code deal.test.ConformanceTest} companion path, realized
     * here so this runner fails on any companion failure.
     */
    @SuppressWarnings("deprecation")
    private static List<CompilerDiagnostic> compileCompanionStandalone(
            Path file) {
        try {
            String source = sourceSnapshot(file).stripped();
            String filename = file.toString();

            LexResult lex = new Lexer(source, filename).tokenize();
            if (lex.hasErrors()) {
                return lex.diagnostics();
            }

            Parser parser = new Parser(lex.tokens(), filename, lex.directiveEvents());
            ParseResult parseResult = parser.parse();
            if (parseResult.hasErrors()) {
                return parseResult.diagnostics();
            }

            if (filename.endsWith(".d.deal")) {
                ExportExtractor extractor =
                    new ExportExtractor(filename, true);
                extractor.extract(parseResult.program());
                return extractor.diagnostics();
            }

            List<CompilerDiagnostic> shapeDiags =
                ModuleShapeValidator.validate(
                    parseResult.program(), filename, false);
            if (shapeDiags.stream().anyMatch(
                    d -> "error".equals(d.severity()))) {
                return new ArrayList<>(shapeDiags);
            }

            List<CompilerDiagnostic> allDiags = new ArrayList<>();
            FrontendModuleResolver resolver =
                new FrontendModuleResolver(file);
            NameResolver nr = new NameResolver(filename, resolver);
            SymbolTable symTable;
            try {
                symTable = nr.resolve(parseResult.program());
            } catch (Exception e) {
                allDiags.add(CompilerDiagnostic.synthetic("E9999", "error",
                    e.getMessage(), filename,
                    "missing anchor: companion source '" + filename + "'"));
                return allDiags;
            }
            allDiags.addAll(nr.diagnostics());
            CheckResult result = TypeChecker.check(filename, symTable, nr,
                parseResult.program());
            allDiags.addAll(result.diagnostics());
            return allDiags;
        } catch (IOException e) {
            String filename = file.toString();
            return List.of(CompilerDiagnostic.synthetic("E9999", "error",
                "cannot read: " + e.getMessage(), filename,
                "missing anchor: companion source '" + filename + "'"));
        }
    }

    // =========================================================================
    // Backend-runtime execution: orchestrator → public production arm → node
    // =========================================================================

    private static Outcome runApplicable(Classified classified) {
        TestFile test = classified.test();
        applicableTotal.incrementAndGet();

        Path scratchRoot = null;
        Path projectRoot = null;
        try {
            // The corpus-coordinate project root: the root component is
            // named `conformance` and every source keeps its
            // corpus-relative path, so the compiler's public class atoms
            // are the pinned corpus identities (`@conformance/<module
            // path>/<Class>`) and both the physical source path and the
            // stripped line project back to the sidecar's raw coordinate.
            scratchRoot = Files.createTempDirectory("deal_js_conf_");
            projectRoot = scratchRoot.resolve("conformance");
            Files.createDirectories(projectRoot);

            // 1. Materialize the test file and its transitive companion
            // closure into the corpus-relative project root. Relative
            // imports resolve on disk from the corpus directories.
            Map<String, Path> written = new LinkedHashMap<>();
            writeModuleFiles(projectRoot, test.path(), written);
            String entryRel = corpusRelOf(test.path()) + "/main.deal";
            Path entryFile = projectRoot.resolve(entryRel);
            // The emitted artifact path is the module id's own
            // .replace('.', '/') + ".js" spelling (the module id is the
            // corpus path with '/' spelled '.'), so the `.deal` directory
            // component becomes an artifact path segment.
            String entryModulePath =
                (corpusRelOf(test.path()) + "/main").replace('.', '/');
            Path outputRoot = projectRoot.resolve("out");

            // 2. The production manifest: languageVersion "1.5" plus the
            // externals entries wiring every bare host/ import to its
            // host-fixtures declaration (the JvmConformanceTest
            // externals-wiring pattern).
            Set<String> hostNames = hostImports(written.values());
            Map<String, String> externals =
                writeProjectManifest(projectRoot, hostNames);

            OrchestratorRun run = runOrchestrator(projectRoot, entryFile,
                outputRoot, externals);
            if (!run.success()) {

                    applicableFailed.incrementAndGet();
                    log("  [" + test.relativePath()
                        + "] FAIL (orchestrator compile failed): "
                        + run.diagnostics() + "\n" + run.capturedOutput());
                return new Outcome(test, classified, false,
                    "orchestrator compile failed: " + run.diagnostics());
            }

            // 4. Codegen was real: the entry artifact and the deployed
            // runtime must exist before node runs — a bypassed codegen
            // or deployment is never a pass.
            Path entryArtifact = outputRoot.resolve(
                entryModulePath + ".js");
            if (TARGET.get() == Backend.JS && !Files.exists(entryArtifact)) {
                    applicableFailed.incrementAndGet();
                    log("  [" + test.relativePath()
                        + "] FAIL (JS codegen produced no '"
                        + entryArtifact.getFileName() + "' artifact)");
                return new Outcome(test, classified, false,
                    "no .js artifact produced");
            }
            Path runtimeArtifact = outputRoot.resolve("deal/runtime.js");
            if (TARGET.get() == Backend.JS && !Files.exists(runtimeArtifact)) {
                    applicableFailed.incrementAndGet();
                    log("  [" + test.relativePath()
                        + "] FAIL (deal/runtime.js not deployed — a "
                        + "bypassed runtime deployment is not a pass)");
                return new Outcome(test, classified, false,
                    "deal/runtime.js not deployed");
            }

            // 5. Host deployment (js-v12-host-abi-completion D6): every
            // host implementation lands at
            // <outputRoot>/<raw specifier>.js, the file the emitted
            // relative require resolves.
            for (String hostName : TARGET.get() == Backend.JS ? hostNames : Set.<String>of()) {
                byte[] hostSource = HOST_JS.get(hostName);
                if (hostSource == null) {
                    throw new HarnessFailure("no HOST_JS implementation "
                        + "for host/" + hostName + " — a missing host "
                        + "implementation is a harness failure, never a "
                        + "pass");
                }
                Path dest = outputRoot.resolve("host").resolve(
                    hostName + ".js");
                Files.createDirectories(dest.getParent());
                Files.write(dest, hostSource);
            }

            // 6. The fixture's structured expectation: the runtime-ok and
            // runtime-error lanes both compare through it (the exact
            // comparator, never a code substring). The runner reports
            // exactly the optional error fields the backend sidecar pins, so
            // the observation stays sidecar-authoritative and an unpinned
            // field never leaks into the snapshot.
            deal.test.conformance.SidecarExpectations
                    .StructuredExpectationSidecar sidecar =
                sidecarOf(test.path());
            deal.test.conformance.SidecarExpectations.RuntimeExpectation
                jsExpectation = sidecar == null ? null
                    : sidecar.expectationFor(backend());
            if (jsExpectation == null) throw new HarnessFailure("missing backend expectation: " + backend());
            if (jsExpectation instanceof deal.test.conformance.SidecarExpectations.RuntimeExpectation.Rejected)
                throw new HarnessFailure("expected compile rejection, but production compilation succeeded");
            List<String> pinnedOptionals = new ArrayList<>();
            if (jsExpectation instanceof deal.test.conformance
                    .SidecarExpectations.RuntimeExpectation.Executed
                        pinnedExecuted
                    && pinnedExecuted.isRuntimeError()) {
                deal.test.conformance.SidecarExpectations.ErrorExpectation
                    error = pinnedExecuted.error();
                for (String field : List.of("expected", "actual", "frames",
                        "cause")) {
                    if (error.pinsOptional(field)) {
                        pinnedOptionals.add(field);
                    }
                }
            }

            // 6b. Runner: auto-invokes the entry module's zero-arity
            // exports, driven by the real parser's export list. It also
            // owns the shared exact error framing (DEAL_ERROR_CODE plus
            // DEAL_ERROR_SNAPSHOT on stdout, exit 1, empty stderr), which
            // the structured comparator consumes field-exact.
            ProgramNode entryProgram = parseEntryProgram(entryFile);
            boolean expectedFailure = jsExpectation instanceof deal.test.conformance.SidecarExpectations.RuntimeExpectation.Executed executed && executed.isRuntimeError();
            List<CorpusExecution.Probe> probes = CorpusExecution.probes(entryProgram, expectedFailure);
            NodeResult result;
            if (TARGET.get() == Backend.JS) {
                Files.writeString(outputRoot.resolve("JsConformanceRunner.js"),
                    buildJsCorpusRunner(entryProgram, entryModulePath,
                        sourcePins(written, entryFile, corpusRelOf(test.path())),
                        corpusTokens(projectRoot, written, entryFile, corpusRelOf(test.path())), pinnedOptionals, probes));
                result = runNode(outputRoot);
            } else {
                Map<String, CorpusExecution.Source> pins = new LinkedHashMap<>();
                sourcePins(written, entryFile, corpusRelOf(test.path())).forEach((path, pin) ->
                    pins.put(path, new CorpusExecution.Source(pin.corpusPath(), pin.headerLines())));
                String module = (corpusRelOf(test.path()) + "/main").replace('/', '.');
                var capture = CorpusExecution.run(TARGET.get(), outputRoot, module, probes, pins,
                    corpusTokens(projectRoot, written, entryFile, corpusRelOf(test.path())), pinnedOptionals,
                    hostNames, hostFixturesRoot);
                result = new NodeResult(capture.exitCode(), capture.stdout(), capture.stderr(),
                    capture.stdoutTruncated(), capture.stderrTruncated());
            }
            boolean loadFailure = expectedFailure && ((deal.test.conformance.SidecarExpectations.RuntimeExpectation.Executed)jsExpectation)
                .error().code().equals("FFI_LIBRARY_LOAD") || expectedFailure &&
                ((deal.test.conformance.SidecarExpectations.RuntimeExpectation.Executed)jsExpectation).error().code().equals("E8011");
            var invocationFailure = CorpusExecution.receiptVerdict(outputRoot, probes, expectedFailure, loadFailure);
            if (invocationFailure.isPresent()) throw new HarnessFailure(backend() + ": " + invocationFailure.get() + "; exit=" + result.exitCode() + "; stdout=" + result.stdout() + "; stderr=" + result.stderr());

            // The capture contract comes first: a capture that hit the
            // shared helper's per-stream cap is an incomplete observation —
            // its retained prefix and the helper's truncation marker are
            // diagnostics, never the program's exact transcript. It is
            // refused by the named non-passing infrastructure outcome
            // before any comparison input is constructed, so a capped
            // prefix can never be read as a complete execution (the
            // same flags are refused by the LuaJIT/JVM and public-host
            // drives).
            String captureRefusal = captureRefusal(result);
            if (captureRefusal != null) {
                    applicableFailed.incrementAndGet();
                    log("  [" + test.relativePath()
                        + "] FAIL (capture infrastructure refusal — the "
                        + "bounded prefix is never a transcript): "
                        + captureRefusal);
                return new Outcome(test, classified, false,
                    "capture infrastructure refusal: " + captureRefusal);
            }

            boolean runtimeOkMode = test.expected().startsWith("runtime-ok");
            boolean runtimeErrorMode =
                test.expected().startsWith("runtime-error ");
            if (runtimeOkMode || runtimeErrorMode) {
                // The JS lane is a real structured-expectation lane: the
                // transcript (exact stdout bytes, exact stderr bytes), the
                // exit code, and — for runtime-error — the canonical error
                // snapshot are compared field-exact against the fixture's
                // Structured Expectation Sidecar through the landed
                // comparator. There is no code-substring fallback: a
                // runtime-error fixture without a js expectation is a
                // corpus defect and fails the lane.
                String mode = runtimeOkMode ? "runtime-ok" : "runtime-error";
                Optional<String> mismatch = Optional.empty();
                if (jsExpectation != null) {
                    mismatch = structuredRuntimeVerdict(result, jsExpectation);
                } else if (runtimeOkMode) {
                    if (result.exitCode != 0) {
                        mismatch = Optional.of("runtime-ok test exited "
                            + result.exitCode + ": " + result.stderr);
                    }
                } else {
                    mismatch = Optional.of("the runtime-error fixture "
                        + "carries no backend sidecar expectation — a missing "
                        + "exact expectation is a corpus defect, never a "
                        + "pass");
                }
                if (mismatch.isPresent()) {
                        applicableFailed.incrementAndGet();
                        log("  [" + test.relativePath()
                            + "] FAIL (" + mode
                            + " structured expectation): "
                            + mismatch.get());
                    return new Outcome(test, classified, false,
                        "structured expectation mismatch: "
                            + mismatch.get() + " [exit=" + result.exitCode() + ", stderr=" + result.stderr() + "]");
                }
                if (runtimeErrorMode) {
                    if (!(jsExpectation instanceof deal.test.conformance
                            .SidecarExpectations.RuntimeExpectation.Executed
                                executed)
                            || !executed.isRuntimeError()) {
                            applicableFailed.incrementAndGet();
                            log("  [" + test.relativePath()
                                + "] FAIL (the backend sidecar does not pin a "
                                + "runtime error)");
                        return new Outcome(test, classified, false,
                            "the backend sidecar does not pin a runtime error");
                    }
                    if (TARGET.get() == Backend.JS && runtimeErrorObservation.get() == null
                            && executed.error().actual().isPresent()
                            && executed.error().pinsSpan()) {
                        runtimeErrorObservation.compareAndSet(null,
                            new RuntimeErrorObservation(test, result, executed));
                    }
                }
                    applicablePassed.incrementAndGet();
                    log("  [" + test.relativePath() + "] OK");
                return new Outcome(test, classified, true,
                    runtimeOkMode ? "runtime ok" : "runtime error");
            }
            applicableFailed.incrementAndGet();
            log("  [" + test.relativePath()
                + "] FAIL (unknown runtime classification '"
                + test.expected() + "')");
            return new Outcome(test, classified, false,
                "unknown runtime classification '" + test.expected() + "'");
        } catch (HarnessFailure e) {
            applicableFailed.incrementAndGet();
            log("  [" + test.relativePath()
                + "] FAIL (harness failure — never a pass): "
                + e.getMessage());
            return new Outcome(test, classified, false,
                "harness failure: " + e.getMessage());
        } catch (Exception e) {
            applicableFailed.incrementAndGet();
            log("  [" + test.relativePath()
                + "] FAIL (execution exception): " + e.getMessage());
            return new Outcome(test, classified, false,
                "execution exception: " + e.getMessage());
        } finally {
            if (scratchRoot != null) {
                try {
                    Files.walk(scratchRoot).sorted(Comparator.reverseOrder())
                        .forEach(f -> { try { Files.deleteIfExists(f); }
                            catch (IOException ignored) { } });
                } catch (IOException ignored) { }
            }
        }
    }

    /**
     * Runs the real {@link CompilationOrchestrator} with
     * {@link Backend#JS} over the temp project: module discovery,
     * signature extraction, dependency ordering, name resolution, type
     * checking, and per-module public production compilation into
     * {@code outputRoot}, with the repository root as the stdlib
     * directory (the JsE2eTest production-pipeline pattern). The
     * explicit {@link #LANE_INVOCATION} (the sole
     * {@code DEAL_V1_5_BINARY64} release invocation) drives the
     * compilation, so the emitted artifacts run the signed-32 gate from
     * runtime load and the shared stdlib-edge time fixture crosses the
     * declared number return of {@code nowMillis} unchanged (a
     * contemporary epoch-millisecond reading above the signed-32 range).
     * Stdout/stderr is
     * captured so per-test output stays clean.
     */
    private static OrchestratorRun runOrchestrator(Path projectRoot,
            Path entryFile, Path outputRoot,
            Map<String, String> externalsDeclarations) {
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        synchronized (CONSOLE_LOCK) {
            PrintStream originalOut = System.out;
            PrintStream originalErr = System.err;
            try {
                System.setOut(new PrintStream(captured, true,
                    StandardCharsets.UTF_8));
                System.setErr(new PrintStream(captured, true,
                    StandardCharsets.UTF_8));
                CompilationOrchestrator orchestrator;

                    orchestrator = new CompilationOrchestrator(entryFile.toAbsolutePath().normalize(),
                        outputRoot.toAbsolutePath().normalize(), false, false, false, false, TARGET.get(),
                        externalsDeclarations, List.of(projectRoot.toAbsolutePath().normalize()), REPO_ROOT, null, LANE_INVOCATION);

                boolean success = orchestrator.compile();
                return new OrchestratorRun(success,
                    orchestrator.diagnostics(),
                    captured.toString(StandardCharsets.UTF_8));
            } catch (IOException e) {
                return new OrchestratorRun(false, List.of(),
                    "orchestrator I/O failure: " + e + "\n"
                        + captured.toString(StandardCharsets.UTF_8));
            } finally {
                System.out.flush();
                System.err.flush();
                System.setOut(originalOut);
                System.setErr(originalErr);
            }
        }
    }

    /**
     * Writes the production project manifest ({@code deal.json}) into
     * the temp project: {@code languageVersion "1.5", stdlib "1.5"} always, plus the
     * externals map wiring every bare {@code host/<name>} import to the
     * copied {@code bindings/<name>.d.deal} declaration. A missing host
     * declaration or a manifest the harness generated that does not
     * load is a harness failure.
     */

    private static Map<String, String> writeProjectManifest(
            Path projectRoot, Set<String> hostNames)
            throws IOException {
        StringBuilder dealJson = new StringBuilder();
        dealJson.append("{\n  \"languageVersion\": \"1.5\",\n  \"stdlib\": \"1.5\",\n");
        dealJson.append("  \"moduleRoots\": [\"conformance\"],\n");
        dealJson.append("  \"output\": \"conformance/out\",\n");
        dealJson.append("  \"backend\": \"").append(backend()).append("\"");
        Map<String, String> externals = new LinkedHashMap<>();


        if (!hostNames.isEmpty()) {
            dealJson.append(",\n  \"externals\": {\n");
            boolean first = true;
            for (String hostName : hostNames) {
                Path decl = hostFixturesRoot.resolve(hostName + ".d.deal");
                if (!Files.isRegularFile(decl)) {
                    throw new HarnessFailure("host declaration missing for"
                        + " host/" + hostName + " — the HOST_JS harness "
                        + "cannot wire the externals entry");
                }
                if (!first) {
                    dealJson.append(",\n");
                }
                first = false;
                String declRel = "bindings/" + hostName + ".d.deal";
                Files.createDirectories(projectRoot.resolve("bindings"));
                Path declTarget = projectRoot.resolve(declRel);

                Files.writeString(declTarget,
                    sourceSnapshot(decl).stripped());
                dealJson.append("    \"host/").append(hostName)
                    .append("\": { \"declaration\": \"conformance/")
                    .append(declRel).append("\" }");

                String declPath = declTarget.toAbsolutePath()
                    .normalize().toString();
                externals.put("host." + hostName, declPath);
                externals.put("host/" + hostName, declPath);
            }

            dealJson.append("\n  }");
        }
        dealJson.append("\n}\n");
        Files.writeString(projectRoot.getParent().resolve("deal.json"), dealJson);
        return externals;
    }

    /** The corpus-relative file stem (name without {@code .deal}). */
    private static String corpusStem(Path file) {
        String name = file.getFileName().toString();
        if (name.endsWith(".d.deal")) {
            return name.substring(0, name.length() - ".d.deal".length());
        }
        return name.substring(0, name.length() - ".deal".length());
    }

    /** The corpus-relative path of a file (slash-separated). */
    private static String corpusRelOf(Path file) {
        Path normalized = file.toAbsolutePath().normalize();
        return conformanceRoot.relativize(normalized).toString()
            .replace('\\', '/');
    }

    /**
     * Copies the entry file and its transitive companion closure into
     * the flat temp project root. Relative imports resolve against each
     * file's own corpus directory (the same resolution the LuaJIT
     * conformance harness uses); stdlib and bare host imports stop the
     * walk (stdlib resolves through the production resolver, host
     * modules through deal.json externals). Every copied corpus file is
     * recorded so the companion-participation gate can verify that each
     * companion materialized into at least one importing fixture's temp
     * project.
     */
    private static void writeModuleFiles(Path projectRoot, Path entry,
            Map<String, Path> written) throws IOException {
        Path normalized = entry.toAbsolutePath().normalize();
        Path entryDir = normalized.getParent();
        // The canonical corpus layout: the fixture source is the entry
        // module `main.deal` inside the directory named after its corpus
        // file (`<fixture>.deal/`), and the companions keep their
        // entry-directory-relative paths under that directory. The
        // configured root is `conformance`, so a class defined in the
        // fixture projects the pinned public atom
        // `@conformance/<corpus path>/<Class>` — the same identity the
        // published boundary-corpus drive pins — and an imported
        // companion keeps its corpus-relative source path for the span
        // projection.
        Path fixtureDir = projectRoot.resolve(corpusRelOf(normalized));
        copyTransitively(normalized, entryDir, fixtureDir, normalized,
            written);
    }

    private static void copyTransitively(Path file, Path entryDir,
            Path fixtureDir, Path entryPath, Map<String, Path> written)
            throws IOException {
        Path normalized = file.toAbsolutePath().normalize();
        if (written.containsKey(normalized.toString())) return;
        Path target;
        if (normalized.equals(entryPath)) {
            target = fixtureDir.resolve("main.deal");
        } else {
            try {
                Path rel = entryDir.relativize(normalized);
                if (rel.startsWith("..")) {
                    target = fixtureDir.resolve(corpusStem(normalized)
                        + ".deal");
                } else {
                    target = fixtureDir.resolve(rel);
                }
            } catch (IllegalArgumentException e) {
                target = fixtureDir.resolve(corpusStem(normalized) + ".deal");
            }
        }
        if (target.getParent() != null) {
            Files.createDirectories(target.getParent());
        }

        Files.writeString(target, sourceSnapshot(normalized).stripped());
        written.put(normalized.toString(), target);
        materializedCorpusFiles.add(corpusRelOf(normalized));
        for (String importPath : relativeImports(normalized)) {
            Path resolved = resolveCompanionPath(importPath,
                normalized.getParent());
            if (resolved == null) continue;
            copyTransitively(resolved, entryDir, fixtureDir, entryPath,
                written);
            // An import spelling carrying an explicit .deal extension
            // resolves to its alias-named copy beside the materialized
            // companion (CompilationOrchestrator searches
            // basePath + ".deal" first), so materialize the alias after
            // the recursive walk.
            Path companionTarget = written.get(
                resolved.toAbsolutePath().normalize().toString());
            if (companionTarget != null) {
                copyCompanionAliasIfExplicit(resolved, importPath,
                    companionTarget);
            }
        }
    }

    /**
     * Materializes a resolved companion under the production-resolved
     * alias name when the import spelling carries an explicit
     * {@code .deal} extension ({@code ./async_lib.deal} →
     * {@code async_lib.deal.deal}): {@link CompilationOrchestrator}
     * searches {@code basePath + ".deal"} first, and its resolution rule
     * resolves the spelling to exactly that alias file. The stem-named
     * copy is still written by the recursive walk; stem-only spellings
     * need no alias and are untouched. The copy is idempotent across
     * repeated explicit spellings of the same companion.
     */
    private static void copyCompanionAliasIfExplicit(Path resolved,
            String importPath, Path materialized) throws IOException {
        if (!importPath.endsWith(".deal")) return;
        // The production resolver's base for the exact specifier is the
        // spelling's target path, and its first candidate appends .deal:
        // the alias copy sits beside the materialized companion, at
        // <materialized name>.deal.
        Path aliasTarget = materialized.resolveSibling(
            materialized.getFileName().toString() + ".deal");
        if (Files.exists(aliasTarget)) return;

        Files.writeString(aliasTarget,
            sourceSnapshot(resolved.toAbsolutePath().normalize()).stripped());
    }

    /** Relative import paths ({@code ./} / {@code ../}) appearing in the
     * file's source, in order. */
    private static List<String> relativeImports(Path file)
            throws IOException {
        return sourceSnapshot(file).relativeImports();
    }

    private static List<String> relativeImportsOf(String source) {
        List<String> paths = new ArrayList<>();
        for (String line : source.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("import ")) continue;
            int from = trimmed.indexOf(" from \"");
            if (from < 0) continue;
            int end = trimmed.indexOf('"', from + 7);
            if (end < 0) continue;
            String path = trimmed.substring(from + 7, end);
            if (path.startsWith("./") || path.startsWith("../")) {
                paths.add(path);
            }
        }
        return paths;
    }

    /** Bare host import names ({@code host/<name>}) appearing in any of
     * the materialized files' sources, in order (drives deal.json
     * externals generation and the HOST_JS deployment). */
    private static Set<String> hostImports(Collection<Path> files)
            throws IOException {
        Set<String> hosts = new LinkedHashSet<>();
        for (Path file : files) {
            String source = rawSource(file);
            for (String line : source.split("\n")) {
                String trimmed = line.trim();
                if (!trimmed.startsWith("import ")) continue;
                int from = trimmed.indexOf(" from \"");
                if (from < 0) continue;
                int end = trimmed.indexOf('"', from + 7);
                if (end < 0) continue;
                String path = trimmed.substring(from + 7, end);
                if (path.startsWith("host/")) {
                    hosts.add(path.substring("host/".length()));
                }
            }
        }
        return hosts;
    }
    /**
     * The parsed Structured Expectation Sidecar of one fixture, or
     * null when the fixture carries no sidecar (the compile-reject
     * verdict check reads the sanctioned js-leg pin).
     */
    private static deal.test.conformance.SidecarExpectations
            .StructuredExpectationSidecar sidecarOf(Path file) {
        String name = file.getFileName().toString();
        Path sidecar;
        if (name.endsWith(".deal")) {
            sidecar = file.resolveSibling(
                name.substring(0, name.length() - ".deal".length())
                    + ".expect.json");
        } else {
            sidecar = file.resolveSibling(name + ".expect.json");
        }
        if (!Files.isRegularFile(sidecar)) {
            return null;
        }
        try {
            return deal.test.conformance.SidecarExpectations
                .StructuredExpectationSidecar.parse(
                    Files.readString(sidecar));
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException(
                "cannot parse the sidecar " + sidecar + ": "
                    + e.getMessage());
        }
    }

    /** Resolve a relative import path to a .deal/.d.deal file on disk
     * (the same resolution the LuaJIT conformance harness uses). */
    private static Path resolveCompanionPath(String importPath,
            Path baseDir) {
        if (!importPath.startsWith("./") && !importPath.startsWith("../")) {
            return null;
        }
        Path resolved = baseDir.resolve(importPath).normalize();
        if (Files.exists(resolved)) return resolved;
        Path withExt = baseDir.resolve(importPath + ".deal").normalize();
        if (Files.exists(withExt)) return withExt;
        Path withDeclExt = baseDir.resolve(importPath + ".d.deal").normalize();
        if (Files.exists(withDeclExt)) return withDeclExt;
        return null;
    }

    /**
     * Parses the entry module with the real lexer + parser for the
     * runner (its export list drives auto-invocation). Type checking is
     * NOT re-run here — the orchestrator already checked every module —
     * so this parse cannot act as a checker bypass.
     */
    private static ProgramNode parseEntryProgram(Path entryFile)
            throws IOException {
        String source = Files.readString(entryFile);
        LexResult lex = new Lexer(source, entryFile.toString()).tokenize();
        if (lex.hasErrors()) {
            throw new IllegalStateException("entry module lex errors: "
                + lex.diagnostics());
        }
        ParseResult parse = new Parser(lex.tokens(),
            entryFile.toString()).parse();
        if (parse.hasErrors()) {
            throw new IllegalStateException("entry module parse errors: "
                + parse.diagnostics());
        }
        return parse.program();
    }

    /**
     * Builds the JS corpus runner source for a fixture: requires the
     * entry artifact and the runtime, then invokes the module's entry
     * surface exactly as the shared lane contract does (G4.4) —
     * {@code main} once first when exported (the emitted artifact's own
     * {@code require.main === module} guard never runs it under
     * {@code require}), then every remaining non-{@code $} zero-arity
     * exported wrapper in declaration order, each exactly once, awaiting
     * thenable results and discarding return values (no lane prints
     * results). A thrown error or a rejected Promise — including a
     * module-load error inside the required entry artifact — is reified
     * and reported through the shared G4.6 lane framing: exactly
     * {@code DEAL_ERROR_CODE: <code>} and
     * {@code DEAL_ERROR_SNAPSHOT: <canonical JSON>} as the final two
     * stdout lines, an empty stderr, and exit 1. The snapshot reports the
     * mandatory code/message, the corpus-coordinate span group, and
     * exactly the optional fields the js sidecar pins (the
     * sidecar-authoritative field set), so the structured comparator can
     * compare the observation field-exact — there is no code-substring
     * observation.
     */
    private static String buildJsCorpusRunner(ProgramNode program,
            String entryModule, Map<String, SourcePin> sourcePins,
            Map<String, String> corpusTokens, List<String> pinnedOptionals, List<CorpusExecution.Probe> probes) {
        boolean hasMain = true;
        List<String> zeroAry = probes.stream().map(CorpusExecution.Probe::name).filter(n -> !n.equals("main")).toList();
        StringBuilder sb = new StringBuilder();
        sb.append("// Generated by deal.test.CorpusConformanceTest — JS corpus adapter.\n");
        sb.append("// Requires the entry artifact, invokes main once first when\n");
        sb.append("// exported, then the remaining zero-arity exported wrappers in\n");
        sb.append("// declaration order (each exactly once, results discarded), and\n");
        sb.append("// reports an uncaught error through the shared G4.6 lane framing:\n");
        sb.append("// DEAL_ERROR_CODE + DEAL_ERROR_SNAPSHOT on stdout, empty stderr,\n");
        sb.append("// exit 1. The snapshot's source span projects the physical\n");
        sb.append("// materialized file and stripped line back to the raw corpus\n");
        sb.append("// coordinate, and its optional fields are exactly the js\n");
        sb.append("// sidecar's pins.\n");
        sb.append("\"use strict\";\n");
        sb.append("const $path = require(\"path\");\n");
        sb.append("const $rt = require(\"./deal/runtime\");\n");
        sb.append("const $fs = require('fs'); const $mark = s => $fs.appendFileSync('").append(CorpusExecution.RECEIPT).append("',s+'\\n');\n");
        sb.append("const $order = [");
        boolean firstEntry = true;
        if (hasMain) {
            sb.append("\"main\"");
            firstEntry = false;
        }
        for (String name : zeroAry) {
            if (!firstEntry) sb.append(", ");
            sb.append("\"").append(name).append("\"");
            firstEntry = false;
        }
        sb.append("];\n");
        sb.append("const $sourcePins = {");
        boolean firstPin = true;
        for (Map.Entry<String, SourcePin> pin : sourcePins.entrySet()) {
            if (!firstPin) sb.append(",");
            sb.append("\n  ").append(jsStringLiteral(pin.getKey()))
                .append(": [")
                .append(jsStringLiteral(pin.getValue().corpusPath()))
                .append(", ").append(pin.getValue().headerLines()).append("]");
            firstPin = false;
        }
        sb.append(firstPin ? "};\n" : "\n};\n");
        // The class-atom module-path projection: the compiler projects a
        // class as @conformance/<module path>/<Class>, where the module
        // path is the source file's project-relative path — the defining
        // file is the identity's final path component. The sidecar pins
        // the corpus file path form (@conformance/<corpus source>/<Class>),
        // exactly the projection CanonicalProjectionParityTest applies, so
        // the runner maps each materialized module path back to its corpus
        // source path before the comparison (the identity spelling and the
        // corpus spelling are the same text for every corpus module). Class
        // names and every other token are untouched.
        sb.append("const $corpusTokens = {");
        boolean firstToken = true;
        for (Map.Entry<String, String> token : corpusTokens.entrySet()) {
            if (!firstToken) sb.append(",");
            sb.append("\n  ").append(jsStringLiteral(token.getKey()))
                .append(": ").append(jsStringLiteral(token.getValue()));
            firstToken = false;
        }
        sb.append(firstToken ? "};\n" : "\n};\n");
        sb.append("""
function $normalizeAtoms($s) {
  if (typeof $s !== "string" || $s.indexOf("@conformance/") < 0) return $s;
  let $out = "";
  let $i = 0;
  while (true) {
    const $at = $s.indexOf("@conformance/", $i);
    if ($at < 0) { $out += $s.slice($i); break; }
    $out += $s.slice($i, $at);
    let $end = $at + "@conformance/".length;
    while ($end < $s.length && " ,\\"'()".indexOf($s.charAt($end)) < 0) $end++;
    const $token = $s.slice($at, $end);
    const $rest = $token.slice("@conformance/".length);
    const $slash = $rest.lastIndexOf("/");
    let $mapped = $token;
    if ($slash > 0) {
      const $modulePath = $rest.slice(0, $slash);
      const $className = $rest.slice($slash + 1);
      const $corpus = $corpusTokens[$modulePath];
      if ($corpus !== undefined) {
        $mapped = "@conformance/" + $corpus + "/" + $className;
      }
    }
    $out += $mapped;
    $i = $end;
  }
  return $out;
}

""");
        sb.append("const $pinned = [");
        for (int i = 0; i < pinnedOptionals.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(jsStringLiteral(pinnedOptionals.get(i)));
        }
        sb.append("];\n");
        sb.append("""
function $jsonString($s) {
  let $out = '"';
  for (let $i = 0; $i < $s.length; ) {
    const $cp = $s.codePointAt($i);
    $i += $cp > 0xFFFF ? 2 : 1;
    if ($cp === 34) $out += '\\\\"';
    else if ($cp === 92) $out += '\\\\\\\\';
    else if ($cp === 8) $out += '\\\\b';
    else if ($cp === 9) $out += '\\\\t';
    else if ($cp === 10) $out += '\\\\n';
    else if ($cp === 12) $out += '\\\\f';
    else if ($cp === 13) $out += '\\\\r';
    else if ($cp < 0x20) {
      $out += '\\\\u' + $cp.toString(16).toUpperCase().padStart(4, '0');
    } else {
      $out += String.fromCodePoint($cp);
    }
  }
  return $out + '"';
}

function $snapshot($err) {
  const $fields = [];
  const $field = ($k, $v) => $jsonString($k) + ":"
    + $jsonString($normalizeAtoms(String($v)));
  const $intField = ($k, $v) => $jsonString($k) + ":" + String($v);
  $fields.push($field("code", $err.code));
  $fields.push($field("message", $err.message));
  const $pin = typeof $err.file === "string"
    ? ($sourcePins[$err.file] || $sourcePins[$path.resolve($err.file)])
    : undefined;
  if ($pin !== undefined && $err.line !== $rt.undefined
      && $err.column !== $rt.undefined) {
    $fields.push($field("sourceFile", $pin[0]));
    $fields.push($intField("line", Number($err.line) + $pin[1]));
    $fields.push($intField("column", Number($err.column)));
  }
  for (const $f of $pinned) {
    const $v = $err[$f];
    if ($v === $rt.undefined) continue;
    if ($f === "frames" && typeof $v === "number") {
      $fields.push($intField($f, $v));
    } else {
      $fields.push($field($f, $v));
    }
  }
  return "{" + $fields.join(",") + "}";
}

""");
        // The entry require sits inside the async body so a load-time
        // failure (a host loadHost E8011, a module-load error) routes
        // through the same framing — never a raw uncaught stack trace.
        sb.append("(async () => {\n");
        sb.append("  $mark('load:start'); const $entry = require(\"./").append(entryModule)
            .append("\"); $mark('load:done');\n");
        sb.append("  for (const $k of $order) {\n");
        sb.append("    const $v = $entry[$k];\n");
        sb.append("    if (!$v || $v.$kind !== 'function') throw new Error('missing corpus probe '+$k);\n");
        sb.append("    $mark($k+':start'); await $v.$f(); $mark($k+':done');\n");
        sb.append("  }\n");
        sb.append("})().catch(($e) => {\n");
        sb.append("  const $err = $rt.reifyError($e);\n");
        sb.append("  process.stdout.write(\"DEAL_ERROR_CODE: \" + $err.code + \"\\n\");\n");
        sb.append("  process.stdout.write(\"DEAL_ERROR_SNAPSHOT: \" + $snapshot($err) + \"\\n\");\n");
        sb.append("  process.exitCode = 1;\n");
        sb.append("});\n");
        return sb.toString();
    }

    /**
     * The class-atom module-path projection of one materialized project:
     * every materialized module's module path — its project-relative
     * source path, the defining file included — maps to its corpus source
     * path (the entry's `main.deal` maps to the fixture's corpus file
     * path, which is also its sidecar sourceFile). `$normalizeAtoms` uses
     * the map on observed class atoms only.
     */
    private static Map<String, String> corpusTokens(Path projectRoot,
            Map<String, Path> written, Path entryTarget,
            String entryCorpusPath) {
        Map<String, String> tokens = new LinkedHashMap<>();
        Path normalizedEntry = entryTarget.toAbsolutePath().normalize();
        Path normalizedRoot = projectRoot.toAbsolutePath().normalize();
        // The configured root is the project root's own directory name
        // (`conformance`); the compiler's module paths are project-relative
        // and carry the defining file name as their final component.
        for (Map.Entry<String, Path> entry : written.entrySet()) {
            Path original = Path.of(entry.getKey()).toAbsolutePath()
                .normalize();
            if (!original.startsWith(conformanceRoot)) {
                continue;
            }
            Path target = entry.getValue().toAbsolutePath().normalize();
            String corpusPath = target.equals(normalizedEntry)
                ? entryCorpusPath : corpusRelOf(original);
            String modulePath = normalizedRoot.relativize(target).toString()
                .replace('\\', '/');
            tokens.put(modulePath, corpusPath);
        }
        return tokens;
    }

    /** One JS string literal for the generated runner (quote/backslash/
     * newline escaped; the fixture paths and field names carry no other
     * control characters). */
    private static String jsStringLiteral(String text) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                default -> sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    /**
     * The corpus-coordinate projection table of one materialized project:
     * every written corpus source's physical path maps to its
     * corpus-relative path and the number of classification header lines
     * the harness removed (the raw-coordinate offset). A generated or
     * non-corpus file has no entry and keeps the Node origin as observed.
     */
    private static Map<String, SourcePin> sourcePins(
            Map<String, Path> written, Path entryTarget,
            String entryCorpusPath) throws IOException {
        Map<String, SourcePin> pins = new LinkedHashMap<>();
        Path normalizedEntryTarget = entryTarget.toAbsolutePath().normalize();
        for (Map.Entry<String, Path> entry : written.entrySet()) {
            Path original = Path.of(entry.getKey()).toAbsolutePath()
                .normalize();
            if (!original.startsWith(conformanceRoot)) {
                continue;
            }
            Path target = entry.getValue().toAbsolutePath().normalize();
            SourceSnapshot snapshot = sourceSnapshot(original);
            int headerLines = snapshot.raw().split("\n", -1).length
                - snapshot.stripped().split("\n", -1).length;
            String corpusPath = target.equals(normalizedEntryTarget)
                ? entryCorpusPath : corpusRelOf(original);
            pins.put(target.toString(),
                new SourcePin(corpusPath, headerLines));
        }
        return pins;
    }

    /** One bounded node subprocess run over the deployed artifact set
     * with captured streams. The shared helper's per-stream truncation
     * flags are carried into the verdict input, never dropped. */
    private static NodeResult runNode(Path outputRoot)
            throws IOException, InterruptedException {
        nodeExecuted.incrementAndGet();
        try {
            BoundedProcess.BoundedResult result = BoundedProcess.runCaptured(
                List.of("node", "JsConformanceRunner.js"), outputRoot,
                Map.of(), TimeUnit.SECONDS.toMillis(SUBPROCESS_TIMEOUT_SECONDS));
            return new NodeResult(result.exitCode(), result.stdout(),
                result.stderr(), result.stdoutTruncated(),
                result.stderrTruncated());
        } catch (BoundedProcess.BoundedProcessTimeoutException timeout) {
            return new NodeResult(-1, "", "node subprocess timed out", false,
                false);
        } catch (IOException failure) {
            nodeExecuted.decrementAndGet();
            throw failure;
        }
    }

    // =========================================================================
    // Capture completeness and the corpus capture controls
    // =========================================================================

    /** The named non-passing outcome for an over-limit capture: the
     * bounded helper's retained prefix plus truncation marker is
     * diagnostics, never a complete transcript. */
    static final String CAPTURE_REFUSAL = "JS_CORPUS_CAPTURE_TRUNCATED";

    /** The named capture refusal, or {@code null} for a complete capture
     * whose streams can be compared. */
    private static String captureRefusal(NodeResult result) {
        if (!result.captureIncomplete()) {
            return null;
        }
        List<String> streams = new ArrayList<>();
        if (result.stdoutTruncated()) {
            streams.add("stdout");
        }
        if (result.stderrTruncated()) {
            streams.add("stderr");
        }
        return CAPTURE_REFUSAL + " (" + String.join("+", streams)
            + " over the " + BoundedProcess.STREAM_CAP_BYTES
            + "-byte per-stream cap)";
    }

    /**
     * The JS lane's structured runtime-ok verdict over one bounded
     * capture: an incomplete (capped) capture is refused by its named
     * non-passing infrastructure outcome before the comparison input
     * exists; only a complete capture reaches the landed field-exact
     * comparator.
     */
    private static Optional<String> structuredRuntimeVerdict(NodeResult result,
            deal.test.conformance.SidecarExpectations.RuntimeExpectation
                pinned) {
        String refusal = captureRefusal(result);
        if (refusal != null) {
            return Optional.of(refusal);
        }
        return deal.test.conformance.StructuredExpectationComparator
            .compare(backend(), pinned,
                new deal.test.conformance.LaneExecution.Executed(
                    result.stdout.getBytes(StandardCharsets.UTF_8),
                    result.stderr.getBytes(StandardCharsets.UTF_8),
                    result.exitCode))
            .map(deal.test.conformance.GateMismatch::message);
    }

    /**
     * The runtime-error exact-verdict negative control: over a real Node
     * observation the lane accepted, the exact capture must match its js
     * sidecar through the structured comparator, and a mutated message,
     * actual token or source origin on that same capture must fail. A
     * corpus run without a pinned actual-token/span observation cannot
     * falsify the exact lane and is itself a gate failure (never a skip).
     */
    private static void validateRuntimeErrorVerdictControls() {
        RuntimeErrorObservation candidate = runtimeErrorObservation.get();
        if (candidate == null) {
            System.out.println("GATE FAILURE: no accepted runtime-error "
                + "observation pins an actual token and a source span — the "
                + "exact-verdict negative control cannot run");
            System.exit(1);
        }
        Optional<deal.test.conformance.ErrorSnapshot.Framed> framed =
            deal.test.conformance.ErrorSnapshot.parseFraming(
            candidate.result().stdout().getBytes(StandardCharsets.UTF_8));
        if (framed.isEmpty()) {
            System.out.println("GATE FAILURE: the accepted runtime-error "
                + "observation carries no canonical framing");
            System.exit(1);
        }
        deal.test.conformance.SidecarExpectations.ErrorExpectation fields =
            framed.get().fields();
        if (fields.actual().isEmpty() || !fields.pinsSpan()) {
            System.out.println("GATE FAILURE: the accepted runtime-error "
                + "observation carries no actual token or source span");
            System.exit(1);
        }
        var wrongMessage = new deal.test.conformance.SidecarExpectations
            .ErrorExpectation(fields.code(),
                fields.message() + " (negative control)", fields.sourceFile(),
                fields.line(), fields.column(), fields.expected(),
                fields.actual(), fields.frames(), fields.cause());
        boolean ok = checkRuntimeErrorVerdict(candidate, framedResult(wrongMessage),
            "a wrong message fails the exact lane");
        var wrongActual = new deal.test.conformance.SidecarExpectations
            .ErrorExpectation(fields.code(), fields.message(),
                fields.sourceFile(), fields.line(), fields.column(),
                fields.expected(),
                Optional.of(fields.actual().get() + "-negative-control"),
                fields.frames(), fields.cause());
        ok &= checkRuntimeErrorVerdict(candidate, framedResult(wrongActual),
            "a wrong actual token fails the exact lane");
        var wrongOrigin = new deal.test.conformance.SidecarExpectations
            .ErrorExpectation(fields.code(), fields.message(),
                fields.sourceFile(), fields.line() + 1, fields.column(),
                fields.expected(), fields.actual(), fields.frames(),
                fields.cause());
        ok &= checkRuntimeErrorVerdict(candidate, framedResult(wrongOrigin),
            "a wrong source origin fails the exact lane");
        if (!ok) {
            System.out.println("GATE FAILURE: a runtime-error exact-verdict "
                + "control failed — a wrong message, actual token or origin "
                + "accepted by the lane is never a pass");
            System.exit(1);
        }
        System.out.println("Runtime-error exact-verdict controls: the real "
            + "Node observation matches its js sidecar field-exact; a wrong "
            + "message, actual token and source origin are each refused ("
            + candidate.test().relativePath() + ")");
    }

    private static boolean checkRuntimeErrorVerdict(
            RuntimeErrorObservation candidate, NodeResult observation,
            String what) {
        if (structuredRuntimeVerdict(observation, candidate.pinned()).isEmpty()) {
            System.out.println("FAIL: " + what + " was accepted (no mismatch)");
            return false;
        }
        return true;
    }

    /** The G4.6 framing bytes of one error snapshot (the exact lane's
     * stdout shape: the code line, the canonical snapshot line, one
     * trailing newline). */
    private static NodeResult framedResult(
            deal.test.conformance.SidecarExpectations.ErrorExpectation fields) {
        String framing = deal.test.conformance.ErrorSnapshot.CODE_LINE_PREFIX
            + fields.code() + "\n"
            + deal.test.conformance.ErrorSnapshot.SNAPSHOT_LINE_PREFIX
            + deal.test.conformance.ErrorSnapshot.canonicalJson(fields) + "\n";
        return new NodeResult(1, framing, "", false, false);
    }

    /**
     * The registered corpus capture controls: the same bounded capture
     * the corpus lane runs ({@link BoundedProcess} over a real node
     * child) and the same structured runtime-ok verdict the lane applies
     * are exercised at the per-stream cap boundary in both stream
     * positions. cap&minus;1 and exact-cap are complete captures and must
     * match their exact transcripts; cap+1 is refused by the named
     * infrastructure outcome even when the pinned expectation is exactly
     * the retained prefix plus the helper's truncation marker; an
     * ordinary wrong byte below the cap stays a transcript mismatch. Any
     * failed control exits the suite non-zero — a capture false-pass is
     * never a pass.
     */
    private static void validateCorpusCaptureControls() {
        Path controlRoot = null;
        boolean ok = true;
        try {
            controlRoot = Files.createTempDirectory(
                "js-corpus-capture-control-");
            Files.writeString(controlRoot.resolve("capture-producer.js"),
                captureProducerSource());
            for (String stream : List.of("stdout", "stderr")) {
                ok &= checkCaptureBoundary(controlRoot, stream,
                    BoundedProcess.STREAM_CAP_BYTES - 1);
                ok &= checkCaptureBoundary(controlRoot, stream,
                    BoundedProcess.STREAM_CAP_BYTES);
                ok &= checkCaptureBoundary(controlRoot, stream,
                    BoundedProcess.STREAM_CAP_BYTES + 1);
            }
            ok &= checkCaptureWrongByte(controlRoot);
        } catch (IOException failure) {
            System.out.println("FAIL: the corpus capture controls could not "
                + "run: " + failure);
            ok = false;
        } finally {
            if (controlRoot != null) {
                try {
                    Path root = controlRoot;
                    Files.walk(root).sorted(Comparator.reverseOrder())
                        .forEach(f -> { try { Files.deleteIfExists(f); }
                            catch (IOException ignored) { } });
                } catch (IOException ignored) { }
            }
        }
        if (!ok) {
            System.out.println("GATE FAILURE: corpus capture control(s) "
                + "failed — an incomplete observation accepted as a "
                + "transcript is never a pass");
            System.exit(1);
        }
        System.out.println("Corpus capture controls: cap-1/cap/cap+1 in "
            + "both stream positions refused or accepted exactly as the "
            + "structured verdict requires (" + CAPTURE_REFUSAL + ")");
    }

    /** One cap-boundary control round in one stream position: a real
     * node producer writes exactly {@code bytes} filler bytes to the
     * named stream, and the structured verdict must accept the complete
     * capture or refuse the capped one by name. */
    private static boolean checkCaptureBoundary(Path controlRoot,
            String stream, int bytes) {
        boolean stdoutStream = "stdout".equals(stream);
        boolean overLimit = bytes > BoundedProcess.STREAM_CAP_BYTES;
        NodeResult captured;
        try {
            captured = captureControlChild(controlRoot, stream, bytes);
        } catch (IOException | InterruptedException failure) {
            System.out.println("FAIL: capture control (" + stream + ", "
                + bytes + " bytes) did not run: " + failure);
            return false;
        }
        byte[] expectedStream = expectedCaptureBytes(bytes);
        byte[] expectedStdout = stdoutStream ? expectedStream : new byte[0];
        byte[] expectedStderr = stdoutStream ? new byte[0] : expectedStream;
        deal.test.conformance.SidecarExpectations.RuntimeExpectation.Executed pinned =
            new deal.test.conformance.SidecarExpectations.RuntimeExpectation.Executed(
                "runtime-ok", expectedStdout, expectedStderr, 0, null);
        Optional<String> verdict = structuredRuntimeVerdict(captured, pinned);
        boolean ok = true;
        if (captured.exitCode() != 0) {
            System.out.println("FAIL: capture control (" + stream + ", "
                + bytes + " bytes) exited " + captured.exitCode()
                + " instead of 0");
            ok = false;
        }
        boolean flagged = stdoutStream ? captured.stdoutTruncated()
            : captured.stderrTruncated();
        boolean otherFlagged = stdoutStream ? captured.stderrTruncated()
            : captured.stdoutTruncated();
        if (otherFlagged) {
            System.out.println("FAIL: capture control (" + stream + ", "
                + bytes + " bytes) flagged the other stream");
            ok = false;
        }
        if (overLimit) {
            if (!flagged) {
                System.out.println("FAIL: capture control (" + stream
                    + ", " + bytes + " bytes) is over the cap but was "
                    + "not flagged truncated");
                ok = false;
            }
            if (verdict.isEmpty()
                    || !verdict.get().startsWith(CAPTURE_REFUSAL)) {
                System.out.println("FAIL: capture control (" + stream + ", "
                    + bytes + " bytes) pinned the retained prefix plus "
                    + "marker and the verdict still accepted it: "
                    + verdict.orElse("<accepted>"));
                ok = false;
            }
            String retained = stdoutStream ? captured.stdout()
                : captured.stderr();
            if (!retained.equals(new String(expectedStream,
                    StandardCharsets.UTF_8))) {
                System.out.println("FAIL: capture control (" + stream + ", "
                    + bytes + " bytes) did not retain exactly the cap "
                    + "prefix plus the truncation marker");
                ok = false;
            }
        } else {
            if (flagged) {
                System.out.println("FAIL: capture control (" + stream
                    + ", " + bytes + " bytes) is within the cap but was "
                    + "flagged truncated");
                ok = false;
            }
            if (verdict.isPresent()) {
                System.out.println("FAIL: capture control (" + stream + ", "
                    + bytes + " bytes) is a complete exact transcript but "
                    + "was not accepted: " + verdict.get());
                ok = false;
            }
        }
        return ok;
    }

    /** An ordinary wrong-byte expectation below the cap stays a
     * transcript mismatch, never a capture refusal. */
    private static boolean checkCaptureWrongByte(Path controlRoot) {
        int bytes = 4096;
        NodeResult captured;
        try {
            captured = captureControlChild(controlRoot, "stdout", bytes);
        } catch (IOException | InterruptedException failure) {
            System.out.println("FAIL: the wrong-byte capture control did "
                + "not run: " + failure);
            return false;
        }
        byte[] expected = expectedCaptureBytes(bytes);
        expected[2048] ^= 0x01;
        deal.test.conformance.SidecarExpectations.RuntimeExpectation.Executed pinned =
            new deal.test.conformance.SidecarExpectations.RuntimeExpectation.Executed(
                "runtime-ok", expected, new byte[0], 0, null);
        Optional<String> verdict = structuredRuntimeVerdict(captured, pinned);
        if (verdict.isEmpty()
                || !verdict.get().contains("TRANSCRIPT_MISMATCH")) {
            System.out.println("FAIL: the wrong-byte capture control "
                + "expected a transcript mismatch, got: "
                + verdict.orElse("<accepted>"));
            return false;
        }
        return true;
    }

    /** Runs one real node producer through the shared bounded capture
     * and carries its truncation flags into the verdict input. */
    private static NodeResult captureControlChild(Path controlRoot,
            String stream, int bytes)
            throws IOException, InterruptedException {
        BoundedProcess.BoundedResult result = BoundedProcess.runCaptured(
            List.of("node", "capture-producer.js", stream,
                Integer.toString(bytes)), controlRoot);
        return new NodeResult(result.exitCode(), result.stdout(),
            result.stderr(), result.stdoutTruncated(),
            result.stderrTruncated());
    }

    /** The independent expectation for one boundary round: the filler
     * bytes (capped) plus the helper's verbatim truncation marker when
     * the producer exceeded the cap. */
    private static byte[] expectedCaptureBytes(int bytes) {
        int retained = Math.min(bytes, BoundedProcess.STREAM_CAP_BYTES);
        byte[] filler = new byte[retained];
        Arrays.fill(filler, (byte) 'x');
        if (bytes <= BoundedProcess.STREAM_CAP_BYTES) {
            return filler;
        }
        byte[] marker = BoundedProcess.TRUNCATION_MARKER.getBytes(
            StandardCharsets.UTF_8);
        byte[] combined = Arrays.copyOf(filler, filler.length + marker.length);
        System.arraycopy(marker, 0, combined, filler.length, marker.length);
        return combined;
    }

    /** The capture control's real node producer: exactly N 'x' bytes to
     * the named stream, exit 0. */
    private static String captureProducerSource() {
        return "\"use strict\";\n"
            + "const stream = process.argv[2];\n"
            + "const count = Number(process.argv[3]);\n"
            + "const chunk = Buffer.alloc(65536, 0x78);\n"
            + "const out = stream === \"stderr\" ? process.stderr\n"
            + "  : process.stdout;\n"
            + "let written = 0;\n"
            + "while (written < count) {\n"
            + "  const n = Math.min(chunk.length, count - written);\n"
            + "  out.write(chunk.subarray(0, n));\n"
            + "  written += n;\n"
            + "}\n";
    }

    // =========================================================================
    // Report
    // =========================================================================

    private static void printReport(List<Outcome> sorted) {
        System.out.println();
        System.out.println("=== DEAL v1.5 Production Corpus Conformance Summary ===");

        int ft = frontendTotal.get();
        int fp = frontendPassed.get();
        int ff = frontendFailed.get();
        System.out.printf("Frontend (backend-neutral compile-ok/"
            + "compile-error): total %d, passed %d, failed %d%n",
            ft, fp, ff);

        int at = applicableTotal.get();
        int ap = applicablePassed.get();
        int af = applicableFailed.get();
        int denominator = onDiskRuntimeDenominator * 3;
        double pct = denominator == 0 ? 0.0
            : ap * 100.0 / denominator;
        System.out.printf("Backend obligations (JS/LuaJIT/JVM, execution or exact rejection): denominator %d, passed %d, "
            + "failed %d, node subprocess runs %d — pass rate %.1f%%%n",
            denominator, ap, af, nodeExecuted.get(), pct);
        System.out.println();

        int ct = companionTotal.get();
        int cp = companionPassed.get();
        int cf = companionFailed.get();
        Set<String> materializedCompanions = new LinkedHashSet<>(
            materializedCorpusFiles);
        materializedCompanions.retainAll(companionPaths);
        System.out.printf("Companions (classified support modules; "
            + "standalone-compiled through the shared frontend/"
            + "declaration gate, materialized transitively into every "
            + "importing fixture's temp project): classified %d (on-disk "
            + "@expected: companion %d), passed %d, failed %d%n",
            ct, onDiskCompanionCount, cp, cf);
        System.out.printf("Companion importer participation: %d "
            + "companion(s) reachable from runtime-classified fixtures, "
            + "%d materialized into importing fixtures' temp projects%n",
            reachableFromRuntime.size(), materializedCompanions.size());
        System.out.println();

        // Largest failing feature groups (review evidence).
        Map<String, Integer> failingGroups = new LinkedHashMap<>();
        for (Outcome o : sorted) {
            if (o.classified().kind() != Kind.APPLICABLE || o.pass()) {
                continue;
            }
            String[] segs = o.test().relativePath().split("/");
            String key = segs.length > 1
                && "backend-runtime".equals(segs[0]) ? segs[1] : segs[0];
            failingGroups.merge(key, 1, Integer::sum);
        }
        if (!failingGroups.isEmpty()) {
            System.out.println("Largest remaining failing feature groups:");
            List<Map.Entry<String, Integer>> entries =
                new ArrayList<>(failingGroups.entrySet());
            entries.sort((a, b) -> Integer.compare(b.getValue(),
                a.getValue()));
            for (Map.Entry<String, Integer> e : entries) {
                System.out.printf("  %-35s %d failing test(s)%n",
                    e.getKey(), e.getValue());
            }
            System.out.println();
        }

        // Gates.
        boolean ok = true;
        if (ff > 0) {
            System.out.println("GATE FAILURE: " + ff
                + " frontend test(s) failed — 100% required");
            ok = false;
        }
        if (af > 0) {
            System.out.println("GATE FAILURE: " + af
                + " applicable backend-runtime test(s) failed — zero "
                + "applicable failures required");
            ok = false;
        }
        if (pct < 100.0) {
            System.out.println("GATE FAILURE: backend-obligation pass rate "
                + String.format(java.util.Locale.ROOT, "%.1f", pct)
                + "% below the 100% threshold (denominator "
                + denominator + ")");
            ok = false;
        }
        if (cf > 0) {
            System.out.println("GATE FAILURE: " + cf
                + " companion(s) failed standalone compilation — every "
                + "@expected: companion module must keep compiling "
                + "through the shared frontend/declaration gate");
            ok = false;
        }
        if (ct != onDiskCompanionCount) {
            System.out.println("GATE FAILURE: classified companion total "
                + ct + " differs from the on-disk @expected: companion "
                + "count " + onDiskCompanionCount);
            ok = false;
        }
        Set<String> deadCompanions = new LinkedHashSet<>(companionPaths);
        deadCompanions.removeAll(importedByAny);
        if (!deadCompanions.isEmpty()) {
            System.out.println("GATE FAILURE: dead companion(s) — no "
                + "classified fixture imports them: " + deadCompanions);
            ok = false;
        }
        Set<String> unmaterialized = new LinkedHashSet<>(
            reachableFromRuntime);
        unmaterialized.removeAll(materializedCompanions);
        Set<String> overMaterialized = new LinkedHashSet<>(
            materializedCompanions);
        overMaterialized.removeAll(reachableFromRuntime);
        if (!unmaterialized.isEmpty() || !overMaterialized.isEmpty()) {
            System.out.println("GATE FAILURE: companion importer "
                + "participation mismatch — reachable from "
                + "runtime-classified fixtures but never materialized: "
                + unmaterialized + "; materialized but not reachable: "
                + overMaterialized);
            ok = false;
        }
        int classifiedRuntimeTotal = at;
        if (classifiedRuntimeTotal != denominator) {
            System.out.println("GATE FAILURE: classified runtime total "
                + classifiedRuntimeTotal + " differs from the on-disk "
                + "runtime denominator " + denominator);
            ok = false;
        }
        int classifiedTotal = ft + at / 3 + ct;
        if (!completedLegs.equals(requiredLegs)) {
            Set<String> missing = new java.util.TreeSet<>(requiredLegs); missing.removeAll(completedLegs);
            Set<String> unexpected = new java.util.TreeSet<>(completedLegs); unexpected.removeAll(requiredLegs);
            System.out.println("GATE FAILURE: backend consumer conservation: missing " + missing + "; unexpected " + unexpected);
            ok = false;
        }
        if (classifiedTotal != onDiskTotal) {
            System.out.println("GATE FAILURE: classified total (FRONTEND "
                + "+ APPLICABLE + COMPANION) "
                + classifiedTotal + " differs from the on-disk "
                + "backend-runtime denominator " + onDiskTotal);
            ok = false;
        }
        if (!ok) {
            System.exit(1);
        }
        System.out.println("Gates PASSED: frontend 100%; backend-runtime "
            + "on all three production backends zero failures and 100% of the declared "
            + denominator + "-test denominator; companion counts equal the "
            + "on-disk corpus and every companion standalone-compiles and "
            + "participates in its importers' temp projects.");
    }
}
