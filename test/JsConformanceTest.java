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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class JsConformanceTest {

    private static final CompilerInvocation LANE_INVOCATION =
        CompilerProfileProvider.resolve(ReleaseState.V1_3_ACTIVE,
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
        /** runtime-ok / runtime-error — JS-applicable backend test. */
        APPLICABLE,
        /** @expected: companion — classified support module;
         *  standalone-compiled, never node-executed standalone. */
        COMPANION
    }

    private record Classified(TestFile test, Kind kind, String expectedCode) {}

    private record Outcome(TestFile test, Classified classified,
            boolean pass, String message) {}

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

    /** The lane driver's raw captured-error transport file (the JS
     * counterpart of the shared lane contract's payload transport): the
     * driver writes the caught DEAL error's own fields here, so the lane
     * re-serializes them through the shared canonical serializer instead of
     * comparing a prefix of a program stream. */
    private static final String ERROR_TRANSPORT_FILE = "lane_error.json";

    /** The corpus identity namespace of the pinned descriptor tokens
     * ({@code @conformance/<corpusPath>/<Class>}). */
    private static final String CORPUS_IDENTITY_NAMESPACE = "conformance";

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

            // The corpus capture controls next: the bounded capture's
            // cap boundary and the structured verdict's refusal of
            // incomplete observations are exercised over real node
            // children before any fixture runs.
            validateCorpusCaptureControls();

            // The runtime-error assertion controls next: the exact error
            // assertion (message, coordinates, pinned optional fields, and
            // no-DEAL-payload honesty) is exercised through the live verdict
            // before any fixture runs, so a regression to code-substring
            // acceptance cannot pass.
            validateRuntimeErrorAssertionControls();

            List<TestFile> tests = discoverTests();
            List<Classified> classified = classifyAll(tests);
            computeCompanionAccounting(tests, classified);

            int frontend = (int) classified.stream()
                .filter(c -> c.kind() == Kind.FRONTEND).count();
            int applicable = (int) classified.stream()
                .filter(c -> c.kind() == Kind.APPLICABLE).count();
            int companions = (int) classified.stream()
                .filter(c -> c.kind() == Kind.COMPANION).count();
            System.out.println("=== DEAL v1.3 JavaScript Corpus "
                + "Conformance (ISSUE-0278 — JsConformanceTest) ===");
            System.out.println("Root: " + conformanceRoot);
            System.out.println("Node: available (a missing node is a hard "
                + "run failure, never a skip)");
            System.out.println("Discovered " + tests.size()
                + " backend-runtime conformance test(s): " + frontend
                + " frontend-classified, " + applicable
                + " JS-applicable backend-runtime, " + companions
                + " companions (classified support modules)");
            System.out.println();

            // Deterministic execution order: sorted by corpus-relative
            // path.
            List<Classified> ordered = new ArrayList<>(classified);
            ordered.sort(Comparator.comparing(
                c -> c.test().relativePath()));

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
        try {
            Process probe = new ProcessBuilder("node", "--version")
                .redirectErrorStream(true).start();
            return probe.waitFor(30, TimeUnit.SECONDS)
                && probe.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
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
                    + file + " — the v1.3 gate has no unclassified files");
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
                result.add(new Classified(test, Kind.COMPANION, ""));
            } else if (expected.startsWith("compile-ok")) {
                result.add(new Classified(test, Kind.FRONTEND, ""));
            } else if (expected.startsWith("compile-error ")) {
                result.add(new Classified(test, Kind.FRONTEND,
                    expected.substring("compile-error ".length()).trim()));
            } else if (expected.startsWith("runtime-ok")) {
                result.add(new Classified(test, Kind.APPLICABLE, ""));
            } else if (expected.startsWith("runtime-error ")) {
                result.add(new Classified(test, Kind.APPLICABLE,
                    expected.substring("runtime-error ".length()).trim()));
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

                for (String importPath : ffiImports(List.of(current))) {
                    deal.test.conformance.CorpusFfi.Wiring wiring =
                        deal.test.conformance.CorpusFfi.wiringFor(
                            conformanceRoot, importPath);
                    if (wiring == null) {
                        continue;
                    }
                    String declarationCorpus =
                        deal.test.conformance.CorpusFfi.FFI_DIR + "/"
                            + wiring.declarationCorpusPath();
                    TestFile target = byPath.get(
                        conformanceRoot.resolve(declarationCorpus)
                            .toAbsolutePath().normalize().toString());
                    if (target != null
                            && target.expected().equals("companion")) {
                        result.add(target.relativePath());
                    }
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
                case APPLICABLE -> runApplicable(classified);
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
            // ISSUE-0717 defect snippets 25/26: the null-return fall-off
            // gate (see NullReturnFallOff).
            all.addAll(deal.checker.NullReturnFallOff.diagnostics(
                parseResult.program()));

            for (StatementNode stmt
                    : parseResult.program().statements()) {
                if (stmt instanceof ImportDeclaration imp
                        && deal.test.conformance.CorpusFfi.isFfiImport(
                            conformanceRoot, imp.modulePath())) {
                    all.addAll(deal.test.conformance.CorpusFfi.module(
                        conformanceRoot, imp.modulePath())
                        .validationDiagnostics());
                }
            }
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

            if (deal.test.conformance.CorpusFfi.isFfiImport(
                    conformanceRoot, modulePath)) {
                return deal.test.conformance.CorpusFfi.module(
                    conformanceRoot, modulePath).exports();
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

        Path projectRoot = null;
        try {
            projectRoot = Files.createTempDirectory("deal_js_conf_");

            // 1. Materialize the test file and its transitive companion
            // closure into the temp project root (flat stem namespace;
            // the corpus has no duplicate stems). Relative imports
            // resolve on disk from the corpus directories.
            Map<String, Path> written = new LinkedHashMap<>();
            writeModuleFiles(projectRoot, test.path(), written);
            String entryRel = corpusStem(test.path()) + ".deal";
            Path entryFile = projectRoot.resolve(entryRel);
            Path outputRoot = projectRoot.resolve("out");

            // 2. The production manifest: languageVersion "1.3" plus the
            // externals entries wiring every bare host/ import to its
            // host-fixtures declaration (the JvmConformanceTest
            // externals-wiring pattern).
            Set<String> hostNames = hostImports(written.values());
            Set<String> ffiNames = ffiImports(written.values());
            Map<String, String> externals =
                writeProjectManifest(projectRoot, hostNames, ffiNames);

            OrchestratorRun run = runOrchestrator(projectRoot, entryFile,
                outputRoot, externals);
            if (!run.success()) {

                deal.test.conformance.SidecarExpectations
                        .StructuredExpectationSidecar sidecar =
                    sidecarOf(test.path());
                if (sidecar != null
                        && sidecar.expectationFor("js")
                            instanceof deal.test.conformance
                                .SidecarExpectations.RuntimeExpectation
                                .Rejected rejected
                        && "E6006".equals(rejected.code())
                        && run.diagnostics().stream().anyMatch(
                            d -> "error".equals(d.severity())
                                && "E6006".equals(d.code()))
                        && !Files.exists(outputRoot.resolve(
                            corpusStem(test.path()) + ".js"))) {
                        applicablePassed.incrementAndGet();
                    log("  [" + test.relativePath()
                        + "] OK (compile-reject E6006 "
                        + "FFI_UNSUPPORTED_BACKEND)");
                    return new Outcome(test, classified, true,
                        "compile-reject E6006 FFI_UNSUPPORTED_BACKEND");
                }
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
                corpusStem(test.path()) + ".js");
            if (!Files.exists(entryArtifact)) {
                    applicableFailed.incrementAndGet();
                    log("  [" + test.relativePath()
                        + "] FAIL (JS codegen produced no '"
                        + entryArtifact.getFileName() + "' artifact)");
                return new Outcome(test, classified, false,
                    "no .js artifact produced");
            }
            Path runtimeArtifact = outputRoot.resolve("deal/runtime.js");
            if (!Files.exists(runtimeArtifact)) {
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
            for (String hostName : hostNames) {
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

            // 6. Runner: auto-invokes the entry module's zero-arity
            // exports, driven by the real parser's export list.
            ProgramNode entryProgram = parseEntryProgram(entryFile);
            Files.writeString(outputRoot.resolve("JsConformanceRunner.js"),
                buildJsCorpusRunner(entryProgram,
                    corpusStem(test.path())));

            // 7. Execute the deployed artifact set with a real node
            // subprocess.
            NodeResult result = runNode(outputRoot);

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
            if (runtimeOkMode) {
                // The JS lane is a real structured-expectation lane: the
                // runtime-ok transcript (exact stdout bytes, exact stderr
                // bytes) and exit code are compared field-exact against
                // the fixture's Structured Expectation Sidecar through the
                // landed comparator, so a wrong observation is never a
                // pass. A fixture without a sidecar is a corpus defect;
                // the fallback keeps the legacy exit-code gate for it.
                deal.test.conformance.SidecarExpectations
                        .StructuredExpectationSidecar sidecar =
                    sidecarOf(test.path());
                Optional<String> mismatch = Optional.empty();
                if (sidecar != null
                        && sidecar.expectationFor("js") instanceof
                            deal.test.conformance.SidecarExpectations
                                .RuntimeExpectation.Executed pinned) {
                    mismatch = structuredRuntimeVerdict(result, pinned);
                } else if (result.exitCode != 0) {
                    mismatch = Optional.of("runtime-ok test exited "
                        + result.exitCode + ": " + result.stderr);
                }
                if (mismatch.isPresent()) {
                        applicableFailed.incrementAndGet();
                        log("  [" + test.relativePath()
                            + "] FAIL (runtime-ok structured expectation): "
                            + mismatch.get());
                    return new Outcome(test, classified, false,
                        "structured expectation mismatch: "
                            + mismatch.get());
                }
                    applicablePassed.incrementAndGet();
                    log("  [" + test.relativePath() + "] OK");
                return new Outcome(test, classified, true, "runtime ok");
            }

            // The runtime-error lane is the sidecar's exact error
            // assertion, never a code substring: the lane driver transports
            // the caught DEAL error's own fields, the lane projects the
            // carrier's source coordinates and generated descriptor tokens
            // through the case's deployment map (corpus C2), frames the real
            // stdout with the shared canonical serializer, and compares the
            // whole observation through the landed comparator — exact
            // transcript bytes, exact exit code, and every sidecar-pinned
            // error field.
            deal.test.conformance.SidecarExpectations
                    .StructuredExpectationSidecar errorSidecar =
                sidecarOf(test.path());
            if (errorSidecar == null) {
                    applicableFailed.incrementAndGet();
                log("  [" + test.relativePath() + "] FAIL (a runtime-error "
                    + "fixture without a Structured Expectation Sidecar is a "
                    + "corpus defect, never a pass)");
                return new Outcome(test, classified, false,
                    "runtime-error fixture without a sidecar");
            }
            Optional<String> errorMismatch = runtimeErrorVerdict(result,
                outputRoot, errorSidecar.expectationFor("js"),
                deploymentOf(written, projectRoot));
            if (errorMismatch.isPresent()) {
                    applicableFailed.incrementAndGet();
                log("  [" + test.relativePath()
                    + "] FAIL (runtime-error structured expectation): "
                    + errorMismatch.get());
                return new Outcome(test, classified, false,
                    "structured expectation mismatch: "
                        + errorMismatch.get());
            }
                    applicablePassed.incrementAndGet();
                log("  [" + test.relativePath()
                    + "] OK (exact runtime-error assertion)");
            return new Outcome(test, classified, true,
                "runtime error exactly as the sidecar pins");
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
            if (projectRoot != null) {
                try {
                    Files.walk(projectRoot).sorted(Comparator.reverseOrder())
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
     * {@code DEAL_V1_3_INT32} release invocation) drives the
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
                CompilationOrchestrator orchestrator =
                    new CompilationOrchestrator(
                        entryFile.toAbsolutePath().normalize(),
                        outputRoot.toAbsolutePath().normalize(),
                        false, false, false, false, Backend.JS,
                        externalsDeclarations,
                        List.of(projectRoot.toAbsolutePath().normalize()),
                        REPO_ROOT, null, LANE_INVOCATION);
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
     * the temp project: {@code languageVersion "1.3"} always, plus the
     * externals map wiring every bare {@code host/<name>} import to the
     * copied {@code bindings/<name>.d.deal} declaration. A missing host
     * declaration or a manifest the harness generated that does not
     * load is a harness failure.
     */

    private static Map<String, String> writeProjectManifest(
            Path projectRoot, Set<String> hostNames, Set<String> ffiImports)
            throws IOException {
        StringBuilder dealJson = new StringBuilder();
        dealJson.append("{\n  \"languageVersion\": \"1.3\",\n");
        dealJson.append("  \"moduleRoots\": [\".\"],\n");
        dealJson.append("  \"output\": \"out\",\n");
        dealJson.append("  \"backend\": \"js\"");
        Map<String, String> externals = new LinkedHashMap<>();

        for (String raw : ffiImports) {
            deal.test.conformance.CorpusFfi.Wiring wiring =
                deal.test.conformance.CorpusFfi.wiringFor(
                    conformanceRoot, raw);
            if (wiring == null) {
                throw new HarnessFailure(
                    "no corpus FFI wiring for " + raw + " — the JS lane "
                        + "cannot wire the externals entry");
            }
            Path declaration = conformanceRoot.resolve(
                    deal.test.conformance.CorpusFfi.FFI_DIR)
                .resolve(wiring.declarationCorpusPath());
            if (!Files.isRegularFile(declaration)) {
                throw new HarnessFailure(
                    "the corpus FFI declaration is missing: "
                        + declaration);
            }
            String declRel = "bindings/ffi/" + raw.replace('/', '_')
                + ".d.deal";
            Path declTarget = projectRoot.resolve(declRel);
            if (declTarget.getParent() != null) {
                Files.createDirectories(declTarget.getParent());
            }
            Files.writeString(declTarget,
                sourceSnapshot(declaration).stripped());
            externals.put(raw,
                declTarget.toAbsolutePath().normalize().toString());
            materializedCorpusFiles.add(
                deal.test.conformance.CorpusFfi.FFI_DIR + "/"
                    + wiring.declarationCorpusPath());
        }
        if (!hostNames.isEmpty() || !ffiImports.isEmpty()) {
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
                    .append("\": { \"declaration\": \"")
                    .append(declRel).append("\" }");

                String declPath = declTarget.toAbsolutePath()
                    .normalize().toString();
                externals.put("host." + hostName, declPath);
                externals.put("host/" + hostName, declPath);
            }
            for (String raw : ffiImports) {
                if (!first) {
                    dealJson.append(",\n");
                }
                first = false;
                dealJson.append("    \"").append(raw)
                    .append("\": { \"declaration\": \"bindings/ffi/")
                    .append(raw.replace('/', '_'))
                    .append(".d.deal\" }");
            }
            dealJson.append("\n  }");
        }
        dealJson.append("\n}\n");
        Files.writeString(projectRoot.resolve("deal.json"), dealJson);
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
        copyTransitively(entry,
            entry.toAbsolutePath().normalize().getParent(),
            projectRoot, written);
    }

    private static void copyTransitively(Path file, Path entryDir,
            Path projectRoot, Map<String, Path> written)
            throws IOException {
        Path normalized = file.toAbsolutePath().normalize();
        if (written.containsKey(normalized.toString())) return;
        // Companions inside the entry's own corpus directory keep their
        // subdirectory layout (the v1.2 identity carriage: two files in
        // one directory share the module identity, so nested companions
        // need their directories preserved to stay nominally distinct);
        // every other companion keeps the flat stem layout.
        Path target;
        try {
            Path rel = entryDir.relativize(normalized);
            if (rel.startsWith("..") || rel.getNameCount() <= 1) {
                target = projectRoot.resolve(corpusStem(normalized)
                    + ".deal");
            } else {
                target = projectRoot.resolve(rel);
            }
        } catch (IllegalArgumentException e) {
            target = projectRoot.resolve(corpusStem(normalized) + ".deal");
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
            // An import spelling carrying an explicit .deal extension
            // resolves to its alias-named copy in the flat project root
            // (CompilationOrchestrator searches basePath + ".deal"
            // first), so materialize the companion under that name
            // before the recursive walk.
            copyCompanionAliasIfExplicit(resolved, importPath, projectRoot);
            copyTransitively(resolved, entryDir, projectRoot, written);
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
            String importPath, Path projectRoot) throws IOException {
        if (!importPath.endsWith(".deal")) return;
        String aliasBase = importPath;
        if (aliasBase.startsWith("./")) {
            aliasBase = aliasBase.substring("./".length());
        } else if (aliasBase.startsWith("../")) {
            aliasBase = aliasBase.substring("../".length());
        }
        Path aliasTarget = projectRoot.resolve(aliasBase + ".deal");
        if (Files.exists(aliasTarget)) return;

        Files.writeString(aliasTarget, sourceSnapshot(resolved).stripped());
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

    /** Corpus FFI externals import specifiers ({@code candidate/*})
     * appearing in any of the materialized files' sources, in order
     * (drives deal.json externals generation through the corpus-owned
     * FFI wiring). */
    private static Set<String> ffiImports(Collection<Path> files)
            throws IOException {
        Set<String> ffi = new LinkedHashSet<>();
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
                if (deal.test.conformance.CorpusFfi.isFfiImport(
                        conformanceRoot, path)) {
                    ffi.add(path);
                }
            }
        }
        return ffi;
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
     * through the runtime and its raw fields are written to the lane's
     * payload transport (the {@code lane_error.json} payload) with exit
     * code 1 and no program output; the lane re-serializes those fields
     * through the shared canonical Error Snapshot serializer, so the
     * transport never competes with the canonical form and no stream
     * prefix is ever compared.
     */
    private static String buildJsCorpusRunner(ProgramNode program,
            String entryModule) {
        boolean hasMain = false;
        List<String> zeroAry = new ArrayList<>();
        for (StatementNode stmt : program.statements()) {
            if (stmt instanceof ExportDeclaration ed
                    && ed.declaration() instanceof FunctionDeclaration fd
                    && fd.params().isEmpty()
                    && !fd.name().contains("$")) {
                if (fd.name().equals("main")) {
                    hasMain = true;
                } else {
                    zeroAry.add(fd.name());
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("// Generated by deal.test.JsConformanceTest — JS corpus runner.\n");
        sb.append("// Requires the entry artifact, invokes main once first when\n");
        sb.append("// exported, then the remaining zero-arity exported wrappers in\n");
        sb.append("// declaration order (each exactly once, results discarded) and\n");
        sb.append("// transports an uncaught DEAL error's raw fields to the\n");
        sb.append("// lane payload file and exits 1 (the lane frames the\n");
        sb.append("// canonical Error Snapshot from those fields).\n");
        sb.append("\"use strict\";\n");
        sb.append("const $fs = require(\"fs\");\n");
        sb.append("const $process = process;\n");
        sb.append("const $rt = require(\"./deal/runtime\");\n");
        sb.append(transportWriterSource());
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
        // The entry require sits inside the async body so a load-time
        // failure (a host loadHost E8011, a module-load error) routes
        // through the shared catch — the DEAL_ERROR_CODE stderr + exit-1
        // contract — never a raw uncaught stack trace.
        sb.append("(async () => {\n");
        sb.append("  const $entry = require(\"./").append(entryModule)
            .append("\");\n");
        sb.append("  for (const $k of $order) {\n");
        sb.append("    const $v = $entry[$k];\n");
        sb.append("    if ($v && $v.$kind === \"function\") {\n");
        sb.append("      await $v.$f();\n");
        sb.append("    }\n");
        sb.append("  }\n");
        sb.append("})().catch((e) => {\n");
        sb.append("  const $err = $rt.reifyError(e);\n");
        sb.append("  if ($err.code !== $rt.undefined) {\n");
        sb.append("    $laneWriteTransport($err);\n");
        sb.append("  }\n");
        sb.append("  $process.exitCode = 1;\n");
        sb.append("});\n");
        return sb.toString();
    }

    /**
     * The driver's payload transport writer: the raw captured error fields
     * in a fixed field order, serialized with the host's minimal JSON
     * escaping. The lane re-serializes them canonically, so this transport
     * never competes with the canonical snapshot form.
     */
    private static String transportWriterSource() {
        return String.join("\n",
            "const $laneWriteTransport = ($e) => {",
            "  const $fields = { code: $e.code };",
            "  if ($e.message !== undefined) { $fields.message = $e.message; }",
            "  if ($e.file !== undefined) { $fields.file = $e.file; }",
            "  if ($e.line !== undefined) { $fields.line = $e.line; }",
            "  if ($e.column !== undefined) { $fields.column = $e.column; }",
            "  if ($e.expected !== undefined) {",
            "    $fields.expected = $e.expected;",
            "  }",
            "  if ($e.actual !== undefined) { $fields.actual = $e.actual; }",
            "  if ($e.frames !== undefined) { $fields.frames = $e.frames; }",
            "  if ($e.cause !== undefined) { $fields.cause = $e.cause; }",
            "  $fs.writeFileSync(\"" + ERROR_TRANSPORT_FILE + "\",",
            "    JSON.stringify($fields), \"utf8\");",
            "};",
            "");
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
            deal.test.conformance.SidecarExpectations.RuntimeExpectation.Executed
                pinned) {
        String refusal = captureRefusal(result);
        if (refusal != null) {
            return Optional.of(refusal);
        }
        return deal.test.conformance.StructuredExpectationComparator
            .compare("js", pinned,
                new deal.test.conformance.LaneExecution.Executed(
                    result.stdout.getBytes(StandardCharsets.UTF_8),
                    result.stderr.getBytes(StandardCharsets.UTF_8),
                    result.exitCode))
            .map(deal.test.conformance.GateMismatch::message);
    }

    /**
     * One materialized module of one case (corpus C2): its canonical
     * corpus-relative path, the stripped-header line rebase, and its parent
     * directory components relative to the module root — the prefix a
     * generated class-descriptor token carries for that module.
     */
    private record DeploymentEntry(String corpusPath, int headerLinesStripped,
            List<String> identityDirectories) {
        DeploymentEntry {
            identityDirectories = List.copyOf(identityDirectories);
        }
    }

    /**
     * The case's deployment map: every materialized source path (absolute
     * normalized, plus the raw target spelling) mapped to its corpus
     * identity, the module-directory index of the generated descriptor
     * tokens, and the module root's own configured text. Built from the same
     * materialization the compilation consumed, so the projection never
     * guesses a module from a basename or from sidecar vocabulary.
     */
    private record CaseDeployment(Map<String, DeploymentEntry> byPath,
            Map<String, List<DeploymentEntry>> byIdentityDirectories,
            String identityRootText) {

        CaseDeployment {
            byPath = Map.copyOf(byPath);
        }

        /** The deployment entry of a captured source path, or {@code null}
         * for an unmappable path (emitted verbatim so the comparison fails
         * and surfaces the defect, never a fabricated corpus path). */
        DeploymentEntry lookup(String capturedFile) {
            if (capturedFile == null) {
                return null;
            }
            DeploymentEntry direct = byPath.get(capturedFile);
            if (direct != null) {
                return direct;
            }
            try {
                return byPath.get(Path.of(capturedFile).toAbsolutePath()
                    .normalize().toString());
            } catch (RuntimeException unusable) {
                return null;
            }
        }
    }

    private static CaseDeployment deploymentOf(Map<String, Path> written,
            Path projectRoot) throws IOException {
        Map<String, DeploymentEntry> byPath = new LinkedHashMap<>();
        Map<String, List<DeploymentEntry>> byDirectories = new LinkedHashMap<>();
        Path root = projectRoot.toAbsolutePath().normalize();
        for (Map.Entry<String, Path> module : written.entrySet()) {
            Path corpusFile = Path.of(module.getKey()).toAbsolutePath()
                .normalize();
            Path target = module.getValue().toAbsolutePath().normalize();
            SourceSnapshot snapshot = sourceSnapshot(corpusFile);
            int headerLinesStripped = snapshot.raw().split("\n", -1).length
                - snapshot.stripped().split("\n", -1).length;
            List<String> directories = new ArrayList<>();
            Path parent = target.getParent();
            if (parent != null && parent.startsWith(root)) {
                for (Path component : root.relativize(parent)) {
                    if (!component.toString().isEmpty()) {
                        directories.add(component.toString());
                    }
                }
            }
            DeploymentEntry entry = new DeploymentEntry(
                corpusRelOf(corpusFile), headerLinesStripped, directories);
            byPath.put(target.toString(), entry);
            byPath.put(module.getValue().toString(), entry);
            List<DeploymentEntry> sameDirectory = byDirectories
                .computeIfAbsent(String.join("/", directories),
                    ignored -> new ArrayList<>());
            if (!sameDirectory.contains(entry)) {
                sameDirectory.add(entry);
            }
        }
        return new CaseDeployment(byPath, byDirectories,
            root.getFileName() == null ? "" : root.getFileName().toString());
    }

    /** The raw captured error fields transported by the lane driver: the
     * product's own DEAL error value, never a lane-composed text. */
    private record CapturedError(String code, String message, String file,
            Integer line, Integer column, Optional<String> expected,
            Optional<String> actual, Optional<Integer> frames,
            Optional<String> cause) {

        CapturedError {
            Objects.requireNonNull(expected);
            Objects.requireNonNull(actual);
            Objects.requireNonNull(frames);
            Objects.requireNonNull(cause);
        }

        /** True when every mandatory snapshot field is present. */
        boolean complete() {
            return code != null && message != null && file != null
                && line != null && column != null;
        }
    }

    /** Reads the driver's raw captured-error transport, or {@code null}
     * when the run carried no DEAL error payload. */
    private static CapturedError readErrorTransport(Path outputRoot) {
        Path transport = outputRoot.resolve(ERROR_TRANSPORT_FILE);
        if (!Files.isRegularFile(transport)) {
            return null;
        }
        try {
            deal.semantic.ir.CanonicalJson.Value root =
                deal.semantic.ir.CanonicalJson.parse(
                    Files.readString(transport, StandardCharsets.UTF_8));
            if (!(root instanceof deal.semantic.ir.CanonicalJson.Obj obj)) {
                return null;
            }
            Map<String, deal.semantic.ir.CanonicalJson.Value> fields =
                new LinkedHashMap<>();
            for (deal.semantic.ir.CanonicalJson.Entry entry : obj.entries()) {
                fields.put(entry.key(), entry.value());
            }
            return new CapturedError(transportString(fields, "code"),
                transportString(fields, "message"),
                transportString(fields, "file"),
                transportInt(fields, "line"),
                transportInt(fields, "column"),
                Optional.ofNullable(transportString(fields, "expected")),
                Optional.ofNullable(transportString(fields, "actual")),
                Optional.ofNullable(transportInt(fields, "frames")),
                Optional.ofNullable(transportString(fields, "cause")));
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    private static String transportString(
            Map<String, deal.semantic.ir.CanonicalJson.Value> fields,
            String key) {
        return fields.get(key) instanceof deal.semantic.ir.CanonicalJson.Str str
            ? str.value() : null;
    }

    private static Integer transportInt(
            Map<String, deal.semantic.ir.CanonicalJson.Value> fields,
            String key) {
        return fields.get(key) instanceof deal.semantic.ir.CanonicalJson.Int value
            ? value.value() : null;
    }

    /**
     * The JS lane's exact runtime-error verdict: the caught error's own
     * fields, projected through the case's deployment map and framed with
     * the shared canonical serializer, compared field-exact against the
     * sidecar's error assertion through the landed comparator. An
     * incomplete capture is refused by name, a missing DEAL error payload is
     * a failing observation, and a pinned field the product does not carry
     * stays absent (the comparator reports the suppression — it is never
     * fabricated from the sidecar).
     */
    private static Optional<String> runtimeErrorVerdict(NodeResult result,
            Path outputRoot,
            deal.test.conformance.SidecarExpectations.RuntimeExpectation pinned,
            CaseDeployment deployment) {
        String refusal = captureRefusal(result);
        if (refusal != null) {
            return Optional.of(refusal);
        }
        if (pinned instanceof
                deal.test.conformance.SidecarExpectations.RuntimeExpectation
                    .Rejected) {
            // A compile-reject pin on a lane that executed is the
            // comparator's mismatch, never a code-substring pass.
            return deal.test.conformance.StructuredExpectationComparator
                .compare("js", pinned, executed(result))
                .map(deal.test.conformance.GateMismatch::message);
        }
        deal.test.conformance.SidecarExpectations.RuntimeExpectation.Executed
            expected =
                (deal.test.conformance.SidecarExpectations.RuntimeExpectation
                    .Executed) pinned;
        if (!expected.isRuntimeError() || expected.error() == null) {
            return Optional.of("the js sidecar pins mode " + expected.mode()
                + " for a runtime-error fixture (no error assertion exists "
                + "to compare)");
        }
        CapturedError captured = readErrorTransport(outputRoot);
        if (captured == null) {
            return Optional.of("the lane exited " + result.exitCode
                + " without a DEAL error payload (a runtime-error fixture "
                + "must raise the pinned DEAL error; stderr: "
                + result.stderr + ")");
        }
        if (!captured.complete()) {
            return Optional.of("the captured DEAL error carries no complete "
                + "DEALRuntimeError field set (code, message, file, line and "
                + "column are mandatory): code=" + captured.code()
                + ", message=" + captured.message() + ", file="
                + captured.file() + ", line=" + captured.line()
                + ", column=" + captured.column());
        }
        deal.test.conformance.SidecarExpectations.ErrorExpectation snapshot =
            projectCapturedError(captured, expected.error(), deployment);
        String framing = deal.test.conformance.ErrorSnapshot.CODE_LINE_PREFIX
            + snapshot.code() + "\n"
            + deal.test.conformance.ErrorSnapshot.SNAPSHOT_LINE_PREFIX
            + deal.test.conformance.ErrorSnapshot.canonicalJson(snapshot)
            + "\n";
        byte[] stdout = concat(result.stdout.getBytes(StandardCharsets.UTF_8),
            framing.getBytes(StandardCharsets.UTF_8));
        return deal.test.conformance.StructuredExpectationComparator
            .compare("js", expected,
                new deal.test.conformance.LaneExecution.Executed(stdout,
                    result.stderr.getBytes(StandardCharsets.UTF_8),
                    result.exitCode))
            .map(deal.test.conformance.GateMismatch::message);
    }

    private static deal.test.conformance.LaneExecution.Executed executed(
            NodeResult result) {
        return new deal.test.conformance.LaneExecution.Executed(
            result.stdout.getBytes(StandardCharsets.UTF_8),
            result.stderr.getBytes(StandardCharsets.UTF_8),
            result.exitCode);
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] joined = new byte[first.length + second.length];
        System.arraycopy(first, 0, joined, 0, first.length);
        System.arraycopy(second, 0, joined, first.length, second.length);
        return joined;
    }

    /**
     * Projects one captured DEAL error onto the sidecar-authoritative field
     * set (C2): the carrier's source path maps through the deployment map
     * and the stripped-source line rebases onto the raw corpus coordinate,
     * generated class-descriptor tokens map through the same deployment
     * identity, and exactly the pinned optional fields are emitted. A pinned
     * span the captured error lacks already failed the completeness check.
     */
    private static deal.test.conformance.SidecarExpectations
            .ErrorExpectation projectCapturedError(CapturedError captured,
                deal.test.conformance.SidecarExpectations.ErrorExpectation
                    assertion,
                CaseDeployment deployment) {
        DeploymentEntry module = deployment.lookup(captured.file());
        String sourceFile = module != null ? module.corpusPath()
            : captured.file();
        int line = module != null
            ? captured.line() + module.headerLinesStripped()
            : captured.line();
        boolean spanPinned = assertion.pinsSpan();
        return new deal.test.conformance.SidecarExpectations.ErrorExpectation(
            captured.code(),
            projectDescriptorTokens(captured.message(), deployment),
            spanPinned ? sourceFile : null,
            spanPinned ? line : null,
            spanPinned ? captured.column() : null,
            pinnedOptional(assertion, "expected", captured.expected(),
                deployment),
            pinnedOptional(assertion, "actual", captured.actual(), deployment),
            pinnedOptional(assertion, "frames", captured.frames(), null),
            pinnedOptional(assertion, "cause", captured.cause(), null));
    }

    private static <T> Optional<T> pinnedOptional(
            deal.test.conformance.SidecarExpectations.ErrorExpectation assertion,
            String field, Optional<T> captured, CaseDeployment deployment) {
        if (captured.isEmpty() || !assertion.pinsOptional(field)) {
            return Optional.empty();
        }
        if (deployment != null && captured.get() instanceof String text) {
            @SuppressWarnings("unchecked")
            T projected = (T) projectDescriptorTokens(text, deployment);
            return Optional.of(projected);
        }
        return captured;
    }

    /**
     * The lane-local generated-descriptor projection: a class descriptor
     * token whose module prefix is positively identified by the case's
     * deployment map maps to the canonical corpus identity
     * {@code @conformance/<corpusPath>/<Class>}. An unknown or ambiguous
     * prefix and authored text containing {@code @} stay verbatim — never a
     * basename guess and never sidecar vocabulary.
     */
    private static String projectDescriptorTokens(String text,
            CaseDeployment deployment) {
        String rootText = deployment.identityRootText();
        if (text == null || text.indexOf('@') < 0 || rootText.isEmpty()) {
            return text;
        }
        Pattern token = Pattern.compile("@" + Pattern.quote(rootText)
            + "((?:/[\\p{L}\\p{N}_.\\-]+)+)");
        Matcher matcher = token.matcher(text);
        StringBuilder projected = new StringBuilder();
        while (matcher.find()) {
            String[] components = matcher.group(1).substring(1).split("/");
            List<DeploymentEntry> modules = deployment.byIdentityDirectories()
                .get(String.join("/",
                    Arrays.copyOf(components, components.length - 1)));
            String replacement = matcher.group();
            if (modules != null && modules.size() == 1) {
                replacement = "@" + CORPUS_IDENTITY_NAMESPACE + "/"
                    + modules.get(0).corpusPath() + "/"
                    + components[components.length - 1];
            }
            matcher.appendReplacement(projected,
                Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(projected);
        return projected.toString();
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

    /**
     * The registered runtime-error assertion controls: the lane's exact
     * verdict is exercised over a synthetic carrier, a synthetic deployment
     * map and the shared canonical framing. An exact observation passes; a
     * wrong message, a wrong coordinate, a suppressed pinned optional field,
     * a compile-reject pin on an executed lane and a run without a DEAL
     * error payload each fail — a code substring can never satisfy the
     * assertion. Any failed control exits the suite non-zero.
     */
    private static void validateRuntimeErrorAssertionControls() {
        Path controlRoot = null;
        boolean ok = true;
        try {
            controlRoot = Files.createTempDirectory(
                "js-error-assertion-control-");
            ok &= checkRuntimeErrorAssertion(controlRoot);
        } catch (IOException failure) {
            System.out.println("FAIL: the runtime-error assertion controls "
                + "could not run: " + failure);
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
            System.out.println("GATE FAILURE: runtime-error assertion "
                + "control(s) failed — a code substring is never the "
                + "sidecar's exact error assertion");
            System.exit(1);
        }
        System.out.println("Runtime-error assertion controls: exact "
            + "message/coordinates/pinned fields accepted; wrong message, "
            + "wrong coordinate, suppressed pinned field, executed-vs-"
            + "compile-reject and a missing DEAL payload refused");
    }

    private static boolean checkRuntimeErrorAssertion(Path controlRoot)
            throws IOException {
        Path moduleRoot = controlRoot.resolve("conformance");
        Path moduleFile = moduleRoot.resolve("modid_lib_b").resolve(
            "lib_b.deal");
        Files.createDirectories(moduleFile.getParent());
        Files.writeString(moduleFile, "// @spec: control\n"
            + "// @expected: runtime-error E8001\n"
            + "export function f(): null {\n  return null;\n}\n");
        Path outputRoot = controlRoot.resolve("out");
        Files.createDirectories(outputRoot);
        String corpusPath = "backend-runtime/modules/modid_lib_b/lib_b.deal";
        DeploymentEntry entry = new DeploymentEntry(corpusPath, 2,
            List.of("modid_lib_b"));
        CaseDeployment deployment = new CaseDeployment(
            Map.of(moduleFile.toString(), entry),
            Map.of("modid_lib_b", List.of(entry)), "conformance");
        String classIdentity = "@" + CORPUS_IDENTITY_NAMESPACE + "/"
            + corpusPath + "/Point";
        deal.test.conformance.SidecarExpectations.ErrorExpectation assertion =
            new deal.test.conformance.SidecarExpectations.ErrorExpectation(
                "E8001", "expected instance of " + classIdentity + ", got table",
                corpusPath, 5, 9, Optional.of(classIdentity),
                Optional.of("table"), Optional.empty(), Optional.empty());
        deal.test.conformance.SidecarExpectations.RuntimeExpectation.Executed
            pinned = new deal.test.conformance.SidecarExpectations
                .RuntimeExpectation.Executed("runtime-error",
                    framedStdout(assertion), new byte[0], 1, assertion);
        boolean ok = true;
        // The exact carrier passes: the generated token projects to the
        // corpus identity and the stripped line rebases onto the raw line.
        ok &= expectVerdict(outputRoot, deployment, pinned, capturedError(
            "E8001", "expected instance of @conformance/modid_lib_b/Point, "
                + "got table", moduleFile, 3, 9,
            Optional.of("@conformance/modid_lib_b/Point"), Optional.of("table")),
            false, "the exact carrier + projection");
        // A message differing only beyond the code fails.
        ok &= expectVerdict(outputRoot, deployment, pinned, capturedError(
            "E8001", "expected instance", moduleFile, 3, 9,
            Optional.of("@conformance/modid_lib_b/Point"), Optional.of("table")),
            true, "a wrong message with the right code");
        // A wrong column fails.
        ok &= expectVerdict(outputRoot, deployment, pinned, capturedError(
            "E8001", "expected instance of @conformance/modid_lib_b/Point, "
                + "got table", moduleFile, 3, 10,
            Optional.of("@conformance/modid_lib_b/Point"), Optional.of("table")),
            true, "a wrong column");
        // A pinned optional the carrier does not carry fails.
        ok &= expectVerdict(outputRoot, deployment, pinned, capturedError(
            "E8001", "expected instance of @conformance/modid_lib_b/Point, "
                + "got table", moduleFile, 3, 9,
            Optional.empty(), Optional.of("table")),
            true, "a suppressed pinned expected field");
        // No DEAL payload at all fails, even with an exit-1 capture.
        Files.deleteIfExists(outputRoot.resolve(ERROR_TRANSPORT_FILE));
        Optional<String> missing = runtimeErrorVerdict(
            new NodeResult(1, "", "", false, false), outputRoot, pinned,
            deployment);
        if (missing.isEmpty()) {
            System.out.println("FAIL: the runtime-error control accepted a "
                + "run with no DEAL error payload");
            ok = false;
        }
        // A compile-reject pin on a lane that executed fails.
        deal.test.conformance.SidecarExpectations.RuntimeExpectation.Rejected
            rejected = new deal.test.conformance.SidecarExpectations
                .RuntimeExpectation.Rejected("compile-reject", "E6006",
                    OptionalInt.empty(), OptionalInt.empty());
        Optional<String> executedVerdict = runtimeErrorVerdict(
            new NodeResult(0, "", "", false, false), outputRoot, rejected,
            deployment);
        if (executedVerdict.isEmpty()) {
            System.out.println("FAIL: the runtime-error control accepted an "
                + "executed lane against a compile-reject pin");
            ok = false;
        }
        return ok;
    }

    /** One control round: write the carrier transport, apply the live
     * verdict through the shared comparator, and require the pinned
     * acceptance/rejection. */
    private static boolean expectVerdict(Path outputRoot,
            CaseDeployment deployment,
            deal.test.conformance.SidecarExpectations.RuntimeExpectation.Executed
                pinned,
            CapturedError captured, boolean mustFail, String what)
            throws IOException {
        writeErrorTransport(outputRoot, captured);
        Optional<String> verdict = runtimeErrorVerdict(
            new NodeResult(1, "", "", false, false), outputRoot, pinned,
            deployment);
        if (mustFail == verdict.isEmpty()) {
            System.out.println("FAIL: the runtime-error control " + what
                + (mustFail ? " was accepted" : " was refused: "
                    + verdict.orElse("")));
            return false;
        }
        return true;
    }

    private static CapturedError capturedError(String code, String message,
            Path file, int line, int column, Optional<String> expected,
            Optional<String> actual) {
        return new CapturedError(code, message, file.toString(), line, column,
            expected, actual, Optional.empty(), Optional.empty());
    }

    private static void writeErrorTransport(Path outputRoot,
            CapturedError captured) throws IOException {
        List<String> fields = new ArrayList<>();
        addTransportString(fields, "code", captured.code());
        addTransportString(fields, "message", captured.message());
        addTransportString(fields, "file", captured.file());
        if (captured.line() != null) {
            fields.add("\"line\":" + captured.line());
        }
        if (captured.column() != null) {
            fields.add("\"column\":" + captured.column());
        }
        addTransportString(fields, "expected", captured.expected().orElse(null));
        addTransportString(fields, "actual", captured.actual().orElse(null));
        Files.writeString(outputRoot.resolve(ERROR_TRANSPORT_FILE),
            "{" + String.join(",", fields) + "}", StandardCharsets.UTF_8);
    }

    private static void addTransportString(List<String> fields, String key,
            String value) {
        if (value == null) {
            return;
        }
        fields.add("\"" + key + "\":\""
            + value.replace("\\", "\\\\").replace("\"", "\\\"")
            + "\"");
    }

    private static byte[] framedStdout(
            deal.test.conformance.SidecarExpectations.ErrorExpectation
                assertion) {
        return (deal.test.conformance.ErrorSnapshot.CODE_LINE_PREFIX
            + assertion.code() + "\n"
            + deal.test.conformance.ErrorSnapshot.SNAPSHOT_LINE_PREFIX
            + deal.test.conformance.ErrorSnapshot.canonicalJson(assertion)
            + "\n").getBytes(StandardCharsets.UTF_8);
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
        System.out.println("=== DEAL v1.3 JavaScript Corpus Conformance "
            + "Summary (ISSUE-0278 — JsConformanceTest) ===");

        int ft = frontendTotal.get();
        int fp = frontendPassed.get();
        int ff = frontendFailed.get();
        System.out.printf("Frontend (backend-neutral compile-ok/"
            + "compile-error): total %d, passed %d, failed %d%n",
            ft, fp, ff);

        int at = applicableTotal.get();
        int ap = applicablePassed.get();
        int af = applicableFailed.get();
        int denominator = onDiskRuntimeDenominator;
        double pct = denominator == 0 ? 0.0
            : ap * 100.0 / denominator;
        System.out.printf("Backend-runtime on Node: denominator %d, passed %d, "
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
            System.out.println("GATE FAILURE: node-executed pass rate "
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
        int classifiedTotal = ft + at + ct;
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
            + "on node zero failures and 100% of the node-executed "
            + denominator + "-test denominator; companion counts equal the "
            + "on-disk corpus and every companion standalone-compiles and "
            + "participates in its importers' temp projects.");
    }
}
