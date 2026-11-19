package deal.diagnostics;

import java.util.Objects;

/** Mechanically certain syntax choice pinned to the exact diagnostic source snapshot; not edit authority. */
public record DiagnosticEditOffer(String sourceRevision, String file, int startUtf16Offset,
                                  int endUtf16Offset, String expectedText, String replacement) {
    public DiagnosticEditOffer {
        Objects.requireNonNull(sourceRevision, "sourceRevision");
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(expectedText, "expectedText");
        Objects.requireNonNull(replacement, "replacement");
        if (startUtf16Offset < 0 || endUtf16Offset < startUtf16Offset
                || expectedText.length() != endUtf16Offset - startUtf16Offset)
            throw new IllegalArgumentException("Invalid exact edit geometry");
    }

    public static DiagnosticEditOffer forDiagnostic(DiagnosticFacts facts, DiagnosticOccurrence occurrence,
                                                    DiagnosticRange range) {
        var syntax = facts.syntax();
        if (occurrence == null || syntax == null || syntax.insertion() == null
                || range == null || range.origin() != RangeOrigin.SOURCE) return null;
        var insertion = syntax.insertion();
        if (insertion.utf16Offset() != range.startScalarOffset()) return null;
        return new DiagnosticEditOffer(occurrence.sourceRevision(), range.file(), insertion.utf16Offset(),
                insertion.utf16Offset(), "", insertion.text());
    }

    /** Explicit caller choice; stale snapshots and text mismatches fail closed. No compiler state is mutated. */
    public String applyChosen(String actualFile, String source) {
        if (!file.equals(actualFile) || !sourceRevision.equals(DiagnosticOccurrence.sourceRevision(actualFile, source)))
            throw new IllegalArgumentException("Stale syntax edit; inspect the current source revision");
        if (endUtf16Offset > source.length() || !source.substring(startUtf16Offset, endUtf16Offset).equals(expectedText))
            throw new IllegalArgumentException("Syntax edit text precondition failed");
        return source.substring(0, startUtf16Offset) + replacement + source.substring(endUtf16Offset);
    }
}
