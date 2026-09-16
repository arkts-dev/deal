package deal.test.conformance;

import deal.diagnostics.CompilerDiagnostic;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The Compile Diagnostic comparator of the differential gate core
 * (ISSUE-0353; the Compile Diagnostic contract of
 * {@code v12-zero-skip-conformance-gate}): for a frontend
 * {@code compile-error} fixture with a Compile Expectation Sidecar, the
 * backend-neutral frontend compilation must emit exactly one error
 * diagnostic, and it must equal the pin field-exact — {@code code},
 * {@code line} (from the diagnostic range start, rebased from the
 * header-stripped compile coordinates onto the raw corpus-file
 * coordinates the pin is authored in), {@code column} (range start), and
 * {@code message} when pinned.
 *
 * <p>A second error diagnostic or a differing field is
 * {@code COMPILE_DIAGNOSTIC_MISMATCH} naming the fixture and the first
 * differing field. No backend executes for the fixture.</p>
 */
public final class CompileDiagnosticComparator {

    private CompileDiagnosticComparator() {
        // Static utility; no instances.
    }

    /**
     * Compares the frontend error diagnostics of one fixture against the
     * Compile Expectation Sidecar pin. Returns the first mismatch, or
     * empty when the fixture emits exactly one error diagnostic equal to
     * the pin field-exact.
     *
     * @param fixturePath          the fixture's corpus-relative path (the
     *                             mismatch subject)
     * @param pin                  the typed Compile Expectation Sidecar pin
     * @param errorDiagnostics     the frontend error diagnostics, in
     *                             pipeline order
     * @param headerLinesStripped  the classification-header line count the
     *                             compile strips (line pins are authored in
     *                             raw corpus-file coordinates)
     */
    public static Optional<GateMismatch> compare(String fixturePath,
            SidecarExpectations.CompileDiagnosticPin pin,
            List<CompilerDiagnostic> errorDiagnostics, int headerLinesStripped) {
        Objects.requireNonNull(fixturePath, "fixturePath must not be null");
        Objects.requireNonNull(pin, "pin must not be null");
        Objects.requireNonNull(errorDiagnostics, "errorDiagnostics must not be null");

        if (errorDiagnostics.size() != 1) {
            String detail;
            if (errorDiagnostics.isEmpty()) {
                detail = "the fixture must emit exactly one error diagnostic, "
                    + "got none";
            } else {
                CompilerDiagnostic second = errorDiagnostics.get(1);
                detail = "the fixture must emit exactly one error diagnostic, "
                    + "got " + errorDiagnostics.size() + " (the second is "
                    + second.code() + " at " + second.line() + ":"
                    + second.column() + ": " + second.message() + ")";
            }
            return Optional.of(new GateMismatch(
                MismatchClass.COMPILE_DIAGNOSTIC_MISMATCH, fixturePath, detail));
        }
        CompilerDiagnostic diagnostic = errorDiagnostics.get(0);

        if (!pin.code().equals(diagnostic.code())) {
            return Optional.of(fieldMismatch(fixturePath, "code", pin.code(),
                diagnostic.code(), true));
        }
        int rawLine = diagnostic.line() + headerLinesStripped;
        if (pin.line().isPresent() && pin.line().getAsInt() != rawLine) {
            return Optional.of(fieldMismatch(fixturePath, "line",
                Integer.toString(pin.line().getAsInt()),
                Integer.toString(rawLine), false));
        }
        if (pin.column().isPresent()
                && pin.column().getAsInt() != diagnostic.column()) {
            return Optional.of(fieldMismatch(fixturePath, "column",
                Integer.toString(pin.column().getAsInt()),
                Integer.toString(diagnostic.column()), false));
        }
        if (pin.message().isPresent()
                && !pin.message().get().equals(diagnostic.message())) {
            return Optional.of(fieldMismatch(fixturePath, "message",
                pin.message().get(), diagnostic.message(), true));
        }
        return Optional.empty();
    }

    private static GateMismatch fieldMismatch(String fixturePath, String field,
            String expected, String actual, boolean quote) {
        return new GateMismatch(MismatchClass.COMPILE_DIAGNOSTIC_MISMATCH,
            fixturePath, "diagnostic." + field + " must be "
                + (quote ? describe(expected) : expected) + ", got "
                + (quote ? describe(actual) : actual));
    }

    private static String describe(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r")
            .replace("\t", "\\t") + "\"";
    }
}
