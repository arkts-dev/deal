package deal.test.conformance;

import deal.checker.CheckResult;
import deal.checker.ModuleResolver;
import deal.checker.ModuleResolver.ModuleNotFoundException;
import deal.checker.NameResolver;
import deal.checker.Symbol;
import deal.checker.SymbolTable;
import deal.checker.TypeChecker;
import deal.diagnostics.CompilerDiagnostic;
import deal.lexer.Lexer;
import deal.lexer.LexResult;
import deal.module.ModuleShapeValidator;
import deal.parser.ParseResult;
import deal.parser.Parser;
import deal.semantic.ir.CanonicalJson;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * ISSUE-0349 (T2) corpus-sidecar validation: the complete sidecar
 * population authored for the corpus is schema-v1 valid and complete.
 *
 * <p>Pins, per the task's completeness obligation (the Zero-Skip
 * Classification Contract makes the sidecar set a single completeness
 * obligation, so the whole runtime-ok set lands in one change):</p>
 * <ol>
 *   <li>Every {@code @expected: runtime-ok} fixture under
 *       {@code test/conformance/backend-runtime/} carries exactly one
 *       sibling {@code <fixture>.expect.json} that validates clean
 *       against {@link SidecarSchemaValidator} schema version 1: exactly
 *       the three backends, mode {@code runtime-ok}, exit code 0, no
 *       {@code error}/{@code sourceFile} field anywhere.</li>
 *   <li>Transcripts follow the C4 authoring rules: stderr is empty
 *       everywhere and stdout is empty except the single
 *       {@code std/console} fixture, whose stdout is exactly
 *       {@code "hello\n"} (the {@code console.log("hello")} string
 *       literal plus the canonical trailing newline of all three
 *       backends).</li>
 *   <li>The two tracked {@code known-fail runtime-*} fixtures carry no
 *       sidecar (they receive sidecars only at the zero-skip flip); the
 *       known-fail population is exactly the two tracked fixtures.</li>
 *   <li>All other fixtures ({@code runtime-error} — owned by T3 —,
 *       {@code compile-ok}, {@code compile-error}, {@code companion},
 *       frontend fixtures) carry no sidecar except the Diagnostics-bullet
 *       fixtures.</li>
 *   <li>The two Diagnostics-bullet fixtures
 *       ({@code frontend/diagnostics/assignment-mismatch.deal},
 *       {@code frontend/diagnostics/return-mismatch.deal}) carry a
 *       Compile Expectation Sidecar that validates clean, and its pin
 *       matches the real frontend diagnostic set field-exact: exactly one
 *       error diagnostic, equal {@code code}/{@code line}/{@code column}/
 *       {@code message} (the Compile Diagnostic comparison of the gate,
 *       run here against the authored pins).</li>
 * </ol>
 *
 * <p>The test runs from the repository root (the {@code run_tests.sh}
 * contract, like {@code ConformanceTest}); it performs read-only I/O on
 * {@code test/conformance/} and mutates nothing.</p>
 */
public class SidecarCorpusValidationTest {

    private static int passed = 0;
    private static int failed = 0;

    /** The corpus root, relative to the repository root. */
    private static final Path CORPUS_ROOT = Path.of("test", "conformance");

    /** The Diagnostics-bullet fixtures carrying Compile Expectation Sidecars. */
    private static final String DIAG_ASSIGNMENT =
        "frontend/diagnostics/assignment-mismatch.deal";
    private static final String DIAG_RETURN =
        "frontend/diagnostics/return-mismatch.deal";

    /** The only runtime-ok fixture whose transcript carries stdout bytes. */
    private static final String CONSOLE_FIXTURE =
        "backend-runtime/stdlib/console/import-log.deal";
    private static final String CONSOLE_STDOUT = "hello\n";

    // =========================================================================
    // Assertion helpers
    // =========================================================================

    private static void check(boolean condition, String message) {
        if (condition) { passed++; }
        else { failed++; System.err.println("FAIL: " + message); }
    }

    private static void fail(String message) {
        failed++;
        System.err.println("FAIL: " + message);
    }

    // =========================================================================
    // Corpus discovery and classification
    // =========================================================================

    /**
     * One discovered corpus fixture: its corpus-relative slash path, its
     * source text, and its exact {@code @expected} tag.
     */
    private record Fixture(String corpusPath, String source, String expectedTag) {}

    private static List<Fixture> discover() throws Exception {
        List<Fixture> fixtures = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(CORPUS_ROOT)) {
            List<Path> files = stream
                .filter(p -> p.toString().endsWith(".deal"))
                .filter(p -> {
                    Path rel = CORPUS_ROOT.relativize(p);
                    return rel.getNameCount() == 0
                        || !"host-fixtures".equals(rel.getName(0).toString());
                })
                .sorted()
                .toList();
            for (Path file : files) {
                String corpusPath = slash(CORPUS_ROOT.relativize(file));
                String source = Files.readString(file);
                String expectedTag = expectedTagOf(source, corpusPath);
                fixtures.add(new Fixture(corpusPath, source, expectedTag));
            }
        }
        return fixtures;
    }

    /** The exact {@code @expected} tag from the metadata header block. */
    private static String expectedTagOf(String source, String corpusPath) {
        String[] lines = source.split("\n", -1);
        int linesToScan = Math.min(lines.length, 40);
        for (int i = 0; i < linesToScan; i++) {
            String line = lines[i].trim();
            if (line.startsWith("// @expected:")) {
                return line.substring("// @expected:".length()).trim();
            }
        }
        fail(corpusPath + ": no @expected tag — the v1.2 gate has no "
            + "unclassified skips");
        return "";
    }

    private static String slash(Path path) {
        return path.toString().replace(java.io.File.separatorChar, '/');
    }

    // =========================================================================
    // Sidecar validation
    // =========================================================================

    private static Optional<SidecarSchemaValidator.ClassificationFailure>
            validateSidecar(Fixture fixture, String sidecarText,
                            Set<String> corpusIndex) {
        SidecarSchemaValidator.ValidationContext context =
            new SidecarSchemaValidator.ValidationContext(
                fixture.corpusPath(), fixture.expectedTag(),
                List.of(new SidecarSchemaValidator.CompilationModule(
                    fixture.corpusPath(), fixture.source())),
                corpusIndex);
        return SidecarSchemaValidator.validate(context, sidecarText);
    }

    /** No object key may be {@code error} or {@code sourceFile} anywhere. */
    private static void checkNoErrorField(CanonicalJson.Value value, String path,
                                          String fixture) {
        if (value instanceof CanonicalJson.Obj obj) {
            for (CanonicalJson.Entry entry : obj.entries()) {
                String key = entry.key();
                if (key.equals("error") || key.equals("sourceFile")) {
                    fail(fixture + ": a runtime-ok sidecar must not carry a "
                        + "\"" + key + "\" field (found at " + path + ")");
                }
                checkNoErrorField(entry.value(), path + "." + key, fixture);
            }
        } else if (value instanceof CanonicalJson.Arr arr) {
            int i = 0;
            for (CanonicalJson.Value item : arr.items()) {
                checkNoErrorField(item, path + "[" + i + "]", fixture);
                i++;
            }
        }
    }

    // =========================================================================
    // Checks per classification
    // =========================================================================

    private static void validateRuntimeOkFixture(Fixture fixture,
            Path sidecarPath, Set<String> corpusIndex) throws Exception {
        if (!Files.exists(sidecarPath)) {
            fail(fixture.corpusPath() + ": runtime-ok fixture is missing its "
                + "Structured Expectation Sidecar " + sidecarPath.getFileName());
            return;
        }
        String sidecarText = Files.readString(sidecarPath);
        Optional<SidecarSchemaValidator.ClassificationFailure> failure =
            validateSidecar(fixture, sidecarText, corpusIndex);
        if (failure.isPresent()) {
            fail(failure.get().message());
            return;
        }
        check(true, fixture.corpusPath()
            + ": runtime-ok sidecar validates clean (schema v1)");

        // Transcript authoring pins (C4): stderr always empty; stdout empty
        // except the single std/console fixture.
        CanonicalJson.Value root = CanonicalJson.parse(sidecarText);
        checkNoErrorField(root, "<root>", fixture.corpusPath());
        CanonicalJson.Obj expected = (CanonicalJson.Obj)
            ((CanonicalJson.Obj) root).entries().stream()
                .filter(e -> e.key().equals("expected"))
                .findFirst().orElseThrow().value();
        CanonicalJson.Obj transcript = (CanonicalJson.Obj)
            expected.entries().stream()
                .filter(e -> e.key().equals("transcript"))
                .findFirst().orElseThrow().value();
        String stdout = stringField(transcript, "stdout");
        String stderr = stringField(transcript, "stderr");
        if (fixture.corpusPath().equals(CONSOLE_FIXTURE)) {
            check(stdout.equals(CONSOLE_STDOUT), CONSOLE_FIXTURE
                + ": stdout must be exactly " + describe(CONSOLE_STDOUT)
                + " (the console.log(\"hello\") literal plus the canonical "
                + "trailing newline), got " + describe(stdout));
        } else {
            check(stdout.isEmpty(), fixture.corpusPath()
                + ": runtime-ok transcripts are authored empty — the fixture "
                + "performs no std/console write, got stdout "
                + describe(stdout));
        }
        check(stderr.isEmpty(), fixture.corpusPath()
            + ": stderr must be empty on runtime-ok, got " + describe(stderr));
    }

    private static String stringField(CanonicalJson.Obj obj, String key) {
        CanonicalJson.Value value = obj.entries().stream()
            .filter(e -> e.key().equals(key))
            .findFirst().orElseThrow().value();
        return ((CanonicalJson.Str) value).value();
    }

    private static String describe(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\n", "\\n")
            .replace("\r", "\\r").replace("\t", "\\t") + "\"";
    }

    private static void validateCompileSidecarFixture(Fixture fixture,
            Path sidecarPath, Set<String> corpusIndex) throws Exception {
        if (!Files.exists(sidecarPath)) {
            fail(fixture.corpusPath() + ": the Diagnostics-bullet fixture is "
                + "missing its Compile Expectation Sidecar "
                + sidecarPath.getFileName());
            return;
        }
        String sidecarText = Files.readString(sidecarPath);
        Optional<SidecarSchemaValidator.ClassificationFailure> failure =
            validateSidecar(fixture, sidecarText, corpusIndex);
        if (failure.isPresent()) {
            fail(failure.get().message());
            return;
        }
        check(true, fixture.corpusPath()
            + ": Compile Expectation Sidecar validates clean (schema v1)");

        // Compile Diagnostic comparison: the fixture must emit exactly one
        // error diagnostic equal to the pin field-exact (code, line, column,
        // message — all four are pinned on the Diagnostics-bullet fixtures).
        CanonicalJson.Value root = CanonicalJson.parse(sidecarText);
        CanonicalJson.Obj diagnostic = (CanonicalJson.Obj)
            ((CanonicalJson.Obj) root).entries().stream()
                .filter(e -> e.key().equals("diagnostic"))
                .findFirst().orElseThrow().value();
        String pinCode = stringField(diagnostic, "code");
        int pinLine = intField(diagnostic, "line");
        int pinColumn = intField(diagnostic, "column");
        String pinMessage = stringField(diagnostic, "message");

        List<CompilerDiagnostic> errors = frontendErrors(fixture);
        if (errors.size() != 1) {
            fail(fixture.corpusPath() + ": COMPILE_DIAGNOSTIC_MISMATCH — the "
                + "fixture must emit exactly one error diagnostic, got "
                + errors.size());
            return;
        }
        CompilerDiagnostic d = errors.get(0);
        compareDiagnosticField(fixture.corpusPath(), "code",
            pinCode, d.code());
        compareDiagnosticField(fixture.corpusPath(), "line",
            String.valueOf(pinLine), String.valueOf(d.line()));
        compareDiagnosticField(fixture.corpusPath(), "column",
            String.valueOf(pinColumn), String.valueOf(d.column()));
        compareDiagnosticField(fixture.corpusPath(), "message",
            pinMessage, d.message());
    }

    private static int intField(CanonicalJson.Obj obj, String key) {
        CanonicalJson.Value value = obj.entries().stream()
            .filter(e -> e.key().equals(key))
            .findFirst().orElseThrow().value();
        return ((CanonicalJson.Int) value).value();
    }

    private static void compareDiagnosticField(String fixture, String field,
            String expected, String actual) {
        check(expected.equals(actual), fixture
            + ": COMPILE_DIAGNOSTIC_MISMATCH — diagnostic." + field
            + " must be " + describe(expected) + ", got " + describe(actual));
    }

    // =========================================================================
    // Real frontend compilation (the Compile Diagnostic comparison source)
    // =========================================================================

    /**
     * Runs the backend-neutral frontend pipeline (lexer → parser → module
     * shape gate → name resolution → type checker) and returns every error
     * diagnostic, mirroring {@code ConformanceTest.compileAndGetDiagnostics}
     * with a resolver that rejects every module (the Diagnostics-bullet
     * fixtures import nothing).
     */
    @SuppressWarnings("deprecation")
    private static List<CompilerDiagnostic> frontendErrors(Fixture fixture) {
        String filename = fixture.corpusPath();
        String source = fixture.source();
        List<CompilerDiagnostic> errors = new ArrayList<>();

        LexResult lex = new Lexer(source, filename).tokenize();
        for (CompilerDiagnostic d : lex.diagnostics()) {
            if ("error".equals(d.severity())) { errors.add(d); }
        }
        if (lex.hasErrors()) { return errors; }

        Parser parser = new Parser(lex.tokens(), filename);
        ParseResult parseResult = parser.parse();
        for (CompilerDiagnostic d : parseResult.diagnostics()) {
            if ("error".equals(d.severity())) { errors.add(d); }
        }
        if (parseResult.hasErrors()) { return errors; }

        for (CompilerDiagnostic d : ModuleShapeValidator.validate(
                parseResult.program(), filename, false)) {
            if ("error".equals(d.severity())) { errors.add(d); }
        }
        if (!errors.isEmpty()) { return errors; }

        ModuleResolver resolver = new ModuleResolver() {
            @Override
            public java.util.Map<String, deal.types.Type> resolveModule(
                    String modulePath, String importingModule,
                    Set<String> modulesInProgress) throws ModuleNotFoundException {
                throw new ModuleNotFoundException(
                    "Module not found: " + modulePath);
            }

            @Override
            public Symbol.ClassSymbol resolveClassSymbol(String className,
                    String modulePath, String importingModule)
                    throws ModuleNotFoundException {
                return null;
            }
        };
        NameResolver nr = new NameResolver(filename, resolver);
        SymbolTable symTable;
        try {
            symTable = nr.resolve(parseResult.program());
        } catch (Exception e) {
            errors.add(CompilerDiagnostic.synthetic("E9999", "error",
                e.getMessage(), filename,
                "missing anchor: fixture source '" + filename + "'"));
            return errors;
        }
        for (CompilerDiagnostic d : nr.diagnostics()) {
            if ("error".equals(d.severity())) { errors.add(d); }
        }
        CheckResult result = TypeChecker.check(filename, symTable, nr,
            parseResult.program());
        for (CompilerDiagnostic d : result.diagnostics()) {
            if ("error".equals(d.severity())) { errors.add(d); }
        }
        return errors;
    }

    // =========================================================================
    // Main
    // =========================================================================

    public static void main(String[] args) throws Exception {
        System.out.println("=== Sidecar Corpus Validation Test (ISSUE-0349) ===\n");

        List<Fixture> fixtures = discover();

        // Corpus module index: every discovered corpus-relative module path
        // (the sourceFile check resolves against it).
        Set<String> corpusIndex = new LinkedHashSet<>();
        for (Fixture fixture : fixtures) {
            corpusIndex.add(fixture.corpusPath());
        }

        // The exact sidecar paths the corpus may carry: one per runtime-ok
        // fixture plus the two Diagnostics-bullet Compile Expectation
        // Sidecars.
        Set<String> allowedSidecars = new HashSet<>();
        int runtimeOk = 0;
        for (Fixture fixture : fixtures) {
            boolean isRuntimeOk = "runtime-ok".equals(fixture.expectedTag());
            boolean isDiagnosticsPin = fixture.corpusPath().equals(DIAG_ASSIGNMENT)
                || fixture.corpusPath().equals(DIAG_RETURN);
            Path sidecarPath = sidecarFor(fixture.corpusPath());

            if (isRuntimeOk) {
                runtimeOk++;
                allowedSidecars.add(sidecarPath.toString());
                validateRuntimeOkFixture(fixture, sidecarPath, corpusIndex);
            } else if (isDiagnosticsPin) {
                allowedSidecars.add(sidecarPath.toString());
                validateCompileSidecarFixture(fixture, sidecarPath, corpusIndex);
            } else {
                check(!Files.exists(sidecarPath), fixture.corpusPath()
                    + ": no sidecar is authored for this classification yet "
                    + "(" + fixture.expectedTag() + " — sidecars land with "
                    + "their owning task), found "
                    + sidecarPath.getFileName());
            }
        }

        // Completeness: the runtime-ok population is the task's whole set.
        check(runtimeOk == 192, "the corpus must carry exactly 192 runtime-ok "
            + "fixtures with sidecars, found " + runtimeOk);

        // The tracked known-fail population is exactly the two fixtures that
        // receive sidecars only at the zero-skip flip.
        Set<String> knownFail = new TreeSet<>();
        for (Fixture fixture : fixtures) {
            if (fixture.corpusPath().startsWith("backend-runtime/")
                    && fixture.expectedTag().startsWith("known-fail ")) {
                knownFail.add(fixture.corpusPath());
            }
        }
        Set<String> expectedKnownFail = new TreeSet<>(Set.of(
            "backend-runtime/arithmetic/int-add-overflow.deal",
            "backend-runtime/bytes/bytes-buffer-ops.deal"));
        check(knownFail.equals(expectedKnownFail),
            "the tracked known-fail population must be exactly "
                + expectedKnownFail + ", got " + knownFail);

        // No stray sidecar anywhere in the corpus.
        try (Stream<Path> stream = Files.walk(CORPUS_ROOT)) {
            for (Path sidecar : stream
                    .filter(p -> p.toString().endsWith(".expect.json"))
                    .sorted()
                    .toList()) {
                check(allowedSidecars.contains(sidecar.toString()),
                    "stray sidecar file " + CORPUS_ROOT.relativize(sidecar)
                        + " — a sidecar may exist only next to a runtime-ok "
                        + "fixture or the Diagnostics-bullet fixtures");
            }
        }

        System.out.println("\nPassed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    /** The sibling sidecar path for a corpus-relative fixture path. */
    private static Path sidecarFor(String corpusPath) {
        String stem = corpusPath.endsWith(".deal")
            ? corpusPath.substring(0, corpusPath.length() - ".deal".length())
            : corpusPath;
        return CORPUS_ROOT.resolve(stem + ".expect.json");
    }
}
