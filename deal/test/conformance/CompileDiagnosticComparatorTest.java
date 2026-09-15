package deal.test.conformance;

import deal.diagnostics.CompilerDiagnostic;
import deal.diagnostics.DiagnosticRange;
import deal.diagnostics.RangeOrigin;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Compile Diagnostic comparator tests (ISSUE-0353 Verification): the
 * comparator over synthetic diagnostics, the two real Diagnostics-bullet
 * fixtures with their authored pins, and scratch-copy perturbations —
 * a perturbed pin (code/line/column/message) fails with
 * {@code COMPILE_DIAGNOSTIC_MISMATCH} naming the fixture and field, and a
 * scratch fixture emitting a second diagnostic fails. The frontend
 * compilation is the real backend-neutral pipeline; no backend executes.
 */
public class CompileDiagnosticComparatorTest {

    private static int passed = 0;
    private static int failed = 0;

    private static final Path CORPUS_ROOT = Path.of("test", "conformance");

    public static void main(String[] args) throws Exception {
        System.out.println("=== CompileDiagnosticComparator Tests (ISSUE-0353) ===\n");

        syntheticUnitCases();
        realCorpusPins();
        scratchCopyPerturbations();

        System.out.println("\nPassed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void check(boolean condition, String message) {
        if (condition) { passed++; }
        else { failed++; System.err.println("FAIL: " + message); }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static CompilerDiagnostic diag(String code, String message,
            int line, int column) {
        return new CompilerDiagnostic(code, "error", message,
            new DiagnosticRange("scratch.fixture", line, column, line, column,
                0, 0, 0, RangeOrigin.SOURCE),
            List.of(), null);
    }

    private static Optional<GateMismatch> compare(String fixturePath,
            SidecarExpectations.CompileDiagnosticPin pin,
            List<CompilerDiagnostic> errors, int headerLinesStripped) {
        return CompileDiagnosticComparator.compare(fixturePath, pin, errors,
            headerLinesStripped);
    }

    private static void assertMismatch(Optional<GateMismatch> result,
            String subject, String detailPart) {
        check(result.isPresent(), subject + " must mismatch (got none)");
        if (result.isEmpty()) {
            return;
        }
        GateMismatch mismatch = result.get();
        check(mismatch.clazz() == MismatchClass.COMPILE_DIAGNOSTIC_MISMATCH,
            subject + " class must be COMPILE_DIAGNOSTIC_MISMATCH, got "
                + mismatch.clazz());
        check(subject.equals(mismatch.subject()),
            "subject must be " + subject + ", got " + mismatch.subject());
        check(mismatch.detail().contains(detailPart),
            subject + " detail must contain \"" + detailPart + "\", got: "
                + mismatch.detail());
    }

    private static Path writeScratch(Path root, String corpusPath,
            String content) throws IOException {
        Path file = root.resolve(corpusPath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
    }

    private static DifferentialGate.GateRun runGate(Path root) throws IOException {
        PrintStream capture = new PrintStream(new ByteArrayOutputStream(),
            true, StandardCharsets.UTF_8);
        return DifferentialGate.run(root, Map.of(), 2,
            Duration.ofSeconds(5), capture);
    }

    // =========================================================================
    // Synthetic unit cases
    // =========================================================================

    private static void syntheticUnitCases() {
        SidecarExpectations.CompileDiagnosticPin pin =
            new SidecarExpectations.CompileDiagnosticPin("E3001",
                OptionalInt.of(8), OptionalInt.of(1),
                Optional.of("Cannot assign number to int"));

        // Field-exact match: the diagnostic sits at raw line 8 with zero
        // stripped header lines, and at stripped line 3 with 5 stripped
        // header lines (the rebase maps 3 + 5 = 8).
        check(compare("frontend/diagnostics/x.deal", pin,
            List.of(diag("E3001", "Cannot assign number to int", 8, 1)), 0)
            .isEmpty(),
            "synthetic: field-exact pin match passes");
        check(compare("frontend/diagnostics/x.deal", pin,
            List.of(diag("E3001", "Cannot assign number to int", 3, 1)), 5)
            .isEmpty(),
            "synthetic: the header-line rebase maps stripped line 3 onto "
                + "raw line 8");

        // Zero diagnostics.
        assertMismatch(compare("frontend/diagnostics/x.deal", pin,
            List.of(), 0),
            "frontend/diagnostics/x.deal",
            "exactly one error diagnostic, got none");

        // A second error diagnostic.
        assertMismatch(compare("frontend/diagnostics/x.deal", pin,
            List.of(diag("E3001", "Cannot assign number to int", 8, 1),
                diag("E5003", "Return type mismatch", 12, 10)), 0),
            "frontend/diagnostics/x.deal",
            "exactly one error diagnostic, got 2 (the second is E5003");

        // Each field, one at a time.
        assertMismatch(compare("frontend/diagnostics/x.deal", pin,
            List.of(diag("E3999", "Cannot assign number to int", 8, 1)), 0),
            "frontend/diagnostics/x.deal",
            "diagnostic.code must be \"E3001\", got \"E3999\"");
        assertMismatch(compare("frontend/diagnostics/x.deal", pin,
            List.of(diag("E3001", "Cannot assign number to int", 9, 1)), 0),
            "frontend/diagnostics/x.deal",
            "diagnostic.line must be 8, got 9");
        assertMismatch(compare("frontend/diagnostics/x.deal", pin,
            List.of(diag("E3001", "Cannot assign number to int", 8, 2)), 0),
            "frontend/diagnostics/x.deal",
            "diagnostic.column must be 1, got 2");
        assertMismatch(compare("frontend/diagnostics/x.deal", pin,
            List.of(diag("E3001", "perturbed message", 8, 1)), 0),
            "frontend/diagnostics/x.deal",
            "diagnostic.message must be \"Cannot assign number to int\", "
                + "got \"perturbed message\"");

        // A pin without line/column/message only compares the code.
        SidecarExpectations.CompileDiagnosticPin codeOnlyPin =
            new SidecarExpectations.CompileDiagnosticPin("E3001",
                OptionalInt.empty(), OptionalInt.empty(), Optional.empty());
        check(compare("frontend/diagnostics/x.deal", codeOnlyPin,
            List.of(diag("E3001", "any message", 1, 1)), 0).isEmpty(),
            "synthetic: a code-only pin compares only the code");
    }

    // =========================================================================
    // Real Diagnostics-bullet fixtures (the real frontend, the authored pins)
    // =========================================================================

    private static void realCorpusPins() throws IOException {
        String[] fixtures = {
            "frontend/diagnostics/assignment-mismatch.deal",
            "frontend/diagnostics/return-mismatch.deal"
        };
        for (String corpusPath : fixtures) {
            Path file = CORPUS_ROOT.resolve(corpusPath);
            String raw = Files.readString(file);
            String source = deal.test.ConformanceHarnessMetadata
                .stripClassificationHeaders(raw);
            int headerLinesStripped =
                raw.split("\n", -1).length - source.split("\n", -1).length;
            String sidecarText = Files.readString(CORPUS_ROOT.resolve(
                corpusPath.substring(0, corpusPath.length() - ".deal".length())
                    + ".expect.json"));
            SidecarExpectations.CompileDiagnosticPin pin =
                SidecarExpectations.CompileDiagnosticPin.parse(sidecarText);
            List<CompilerDiagnostic> errors =
                FrontendCompiler.errorDiagnostics(source, corpusPath);
            Optional<GateMismatch> mismatch = CompileDiagnosticComparator
                .compare(corpusPath, pin, errors, headerLinesStripped);
            check(mismatch.isEmpty(),
                corpusPath + ": the authored pin matches the real frontend "
                    + "diagnostic field-exact"
                    + (mismatch.isEmpty() ? "" : " — " + mismatch.get().detail()));
        }
    }

    // =========================================================================
    // Scratch-copy perturbations (real frontend, scratch conformance roots)
    // =========================================================================

    private static final String SCRATCH_ASSIGNMENT_FIXTURE = """
        // @spec: Diagnostics — Compile-time diagnostic format
        // @description: Assignment to wrong type produces diagnostic
        // @expected: compile-error E3001
        // @features: diagnostics, assignment

        export function main(): null {
        let y: int = 1;
        y = 2.0;

        return null;
        }
        """;

    private static final String SCRATCH_ASSIGNMENT_SIDECAR = """
        {
          "version": 1,
          "mode": "compile-error",
          "diagnostic": {
            "code": "E3001",
            "line": 8,
            "column": 1,
            "message": "Cannot assign number to int"
          }
        }
        """;

    private static void scratchCopyPerturbations() throws IOException {
        // The unperturbed scratch copy passes (the authored pin matches the
        // real frontend diagnostic field-exact).
        {
            Path root = Files.createTempDirectory("gate_compile_diag_");
            writeScratch(root, "frontend/diagnostics/assignment-mismatch.deal",
                SCRATCH_ASSIGNMENT_FIXTURE);
            writeScratch(root, "frontend/diagnostics/assignment-mismatch.expect.json",
                SCRATCH_ASSIGNMENT_SIDECAR);
            DifferentialGate.GateRun run = runGate(root);
            check(run.ok(), "scratch copy: the unperturbed pin passes the gate");
            check(run.compileDiagnosticComparisons() == 1,
                "scratch copy: exactly one compile-diagnostic comparison ran");
            clean(root);
        }
        // A perturbed code: the fixture's @expected moves with the pin so
        // the classification cross-check passes and the comparator names
        // the differing field (the real frontend emits E3001).
        {
            Path root = Files.createTempDirectory("gate_compile_diag_");
            writeScratch(root, "frontend/diagnostics/assignment-mismatch.deal",
                SCRATCH_ASSIGNMENT_FIXTURE.replace(
                    "// @expected: compile-error E3001",
                    "// @expected: compile-error E3999"));
            writeScratch(root, "frontend/diagnostics/assignment-mismatch.expect.json",
                SCRATCH_ASSIGNMENT_SIDECAR.replace("\"code\": \"E3001\"",
                    "\"code\": \"E3999\""));
            DifferentialGate.GateRun run = runGate(root);
            check(!run.ok(), "perturbed code: the gate fails");
            check(run.failures().size() == 1, "perturbed code: one failure, "
                + "got " + run.failures().size());
            check(run.failures().get(0).kind().equals("compile-diagnostic"),
                "perturbed code: the failure is COMPILE_DIAGNOSTIC_MISMATCH, "
                    + "got " + run.failures().get(0).kind());
            check(run.failures().get(0).subject().equals(
                    "frontend/diagnostics/assignment-mismatch.deal"),
                "perturbed code: the failure names the fixture");
            check(run.failures().get(0).detail().contains(
                    "diagnostic.code must be \"E3999\", got \"E3001\""),
                "perturbed code: the failure names the field, got: "
                    + run.failures().get(0).detail());
            clean(root);
        }
        // A perturbed line.
        {
            Path root = Files.createTempDirectory("gate_compile_diag_");
            writeScratch(root, "frontend/diagnostics/assignment-mismatch.deal",
                SCRATCH_ASSIGNMENT_FIXTURE);
            writeScratch(root, "frontend/diagnostics/assignment-mismatch.expect.json",
                SCRATCH_ASSIGNMENT_SIDECAR.replace("\"line\": 8",
                    "\"line\": 999"));
            DifferentialGate.GateRun run = runGate(root);
            check(!run.ok(), "perturbed line: the gate fails");
            check(run.failures().size() == 1
                    && run.failures().get(0).detail().contains(
                        "diagnostic.line must be 999, got 8"),
                "perturbed line: the failure names the field, got: "
                    + (run.failures().isEmpty() ? "<none>"
                        : run.failures().get(0).detail()));
            clean(root);
        }
        // A perturbed column.
        {
            Path root = Files.createTempDirectory("gate_compile_diag_");
            writeScratch(root, "frontend/diagnostics/assignment-mismatch.deal",
                SCRATCH_ASSIGNMENT_FIXTURE);
            writeScratch(root, "frontend/diagnostics/assignment-mismatch.expect.json",
                SCRATCH_ASSIGNMENT_SIDECAR.replace("\"column\": 1",
                    "\"column\": 99"));
            DifferentialGate.GateRun run = runGate(root);
            check(!run.ok(), "perturbed column: the gate fails");
            check(run.failures().size() == 1
                    && run.failures().get(0).detail().contains(
                        "diagnostic.column must be 99, got 1"),
                "perturbed column: the failure names the field, got: "
                    + (run.failures().isEmpty() ? "<none>"
                        : run.failures().get(0).detail()));
            clean(root);
        }
        // A perturbed message.
        {
            Path root = Files.createTempDirectory("gate_compile_diag_");
            writeScratch(root, "frontend/diagnostics/assignment-mismatch.deal",
                SCRATCH_ASSIGNMENT_FIXTURE);
            writeScratch(root, "frontend/diagnostics/assignment-mismatch.expect.json",
                SCRATCH_ASSIGNMENT_SIDECAR.replace(
                    "\"message\": \"Cannot assign number to int\"",
                    "\"message\": \"perturbed message\""));
            DifferentialGate.GateRun run = runGate(root);
            check(!run.ok(), "perturbed message: the gate fails");
            check(run.failures().size() == 1
                    && run.failures().get(0).detail().contains(
                        "diagnostic.message must be \"perturbed message\", "
                            + "got \"Cannot assign number to int\""),
                "perturbed message: the failure names the field, got: "
                    + (run.failures().isEmpty() ? "<none>"
                        : run.failures().get(0).detail()));
            clean(root);
        }
        // A second error diagnostic: the fixture emits two E3001 errors and
        // the pin matches only the first — the count check fails first.
        {
            Path root = Files.createTempDirectory("gate_compile_diag_");
            String twoErrors = """
                // @spec: Diagnostics — Compile-time diagnostic format
                // @description: Two assignment mismatches produce two diagnostics
                // @expected: compile-error E3001
                // @features: diagnostics, assignment

                export function main(): null {
                let y: int = 1;
                y = 2.0;
                let z: int = 1;
                z = 3.0;

                return null;
                }
                """;
            writeScratch(root, "frontend/diagnostics/assignment-mismatch.deal",
                twoErrors);
            writeScratch(root, "frontend/diagnostics/assignment-mismatch.expect.json",
                SCRATCH_ASSIGNMENT_SIDECAR);
            DifferentialGate.GateRun run = runGate(root);
            check(!run.ok(), "second diagnostic: the gate fails");
            check(run.failures().size() == 1
                    && run.failures().get(0).detail().contains(
                        "exactly one error diagnostic, got 2"),
                "second diagnostic: the failure names the count, got: "
                    + (run.failures().isEmpty() ? "<none>"
                        : run.failures().get(0).detail()));
            clean(root);
        }
    }

    private static void clean(Path root) throws IOException {
        try (var stream = Files.walk(root)) {
            for (Path path : stream.sorted(Comparator.reverseOrder())
                    .toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
