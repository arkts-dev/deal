package deal.diagnostics;

import deal.amend.CompilerProtocolJson;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Compiler JSON v3 report writer. */
public final class DiagnosticStructuredOutput {
    private DiagnosticStructuredOutput() {}

    public static void write(Path path, List<CompilerDiagnostic> diagnostics,
                             List<OperationalDiagnostic> failures, CompilationOutcome outcome) throws IOException {
        write(path, diagnostics, failures, outcome, null);
    }

    public static void write(Path path, List<CompilerDiagnostic> diagnostics,
                             List<OperationalDiagnostic> failures, CompilationOutcome outcome, Path projectRoot) throws IOException {
        Files.writeString(path, toJson(diagnostics, failures, outcome, projectRoot));
    }

    public static String toJson(List<CompilerDiagnostic> diagnostics) {
        return toJson(diagnostics, List.of(), null);
    }

    public static String toJson(List<CompilerDiagnostic> diagnostics, List<OperationalDiagnostic> failures) {
        return toJson(diagnostics, failures, null);
    }

    public static String toJson(List<CompilerDiagnostic> diagnostics, List<OperationalDiagnostic> failures, CompilationOutcome outcome) {
        return toJson(diagnostics, failures, outcome, null);
    }

    public static String toJson(List<CompilerDiagnostic> diagnostics, List<OperationalDiagnostic> failures,
                                CompilationOutcome outcome, Path projectRoot) {
        return CompilerProtocolJson.encode(new DiagnosticJson(projectRoot, outcome == null ? null : outcome.sourceBatch())
                .compiler(diagnostics == null ? List.of() : diagnostics, failures, outcome));
    }
}
