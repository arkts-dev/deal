package deal.semantic.ir;

import deal.diagnostics.DiagnosticRange;
import deal.diagnostics.RangeOrigin;

import java.util.Objects;

/**
 * The retained diagnostic origin metadata of one {@link RawOp} (design
 * {@code shared-lowering-diagnostic-origins} D3): the complete existing
 * {@link SourceOrigin} key set — source id, file, positions, scalar
 * offsets, origin kind, and anchor identity — carried as immutable pure
 * data, in addition to the {@link RawOp#parentOpId()} link the semantic
 * checks consume.
 *
 * <p>Typed-to-raw conversion copies the operation's actual origin; text
 * parsing retains the supplied values. The record deliberately performs no
 * validation: the origin is diagnostic-only metadata, so a doctored text
 * with missing or malformed coordinates is carried byte-intact and never
 * becomes a transport or semantic rejection. The diagnostic conversion
 * below extracts the available values safely and lets the existing
 * {@link deal.diagnostics.CompilerDiagnostic} normalization own every
 * malformed range.</p>
 *
 */
public record RawOrigin(
    String sourceId,
    String file,
    int startLine,
    int startColumn,
    int endLine,
    int endColumn,
    int startScalarOffset,
    int endScalarOffset,
    String kind,
    long anchorId
) implements DiagnosticOriginCarrier {

    public RawOrigin {
        // Diagnostic-only data: no validation, no rejection, no guessing.
    }

    /** Copies one produced semantic origin into the raw metadata record. */
    public static RawOrigin of(SourceOrigin origin) {
        Objects.requireNonNull(origin, "origin must not be null");
        SourceSpan span = origin.span();
        return new RawOrigin(origin.sourceId(), span.file(), span.startLine(),
            span.startColumn(), span.endLine(), span.endColumn(),
            span.startScalarOffset(), span.endScalarOffset(),
            origin.kind().name(), origin.anchorId().id());
    }

    /**
     * The origin's diagnostic range (D5): the same inclusive-to-half-open
     * conversion as {@link SourceSpan#range()}. An absent file falls back
     * to the empty-file canonical synthetic range; an offsetless span keeps
     * its known file through the canonical synthetic conversion ("unknown
     * offsets are never SOURCE"); known offsets yield the SOURCE range, and
     * every malformed value is normalized by
     * {@link deal.diagnostics.CompilerDiagnostic}, never guessed here.
     */
    @Override
    public DiagnosticRange diagnosticRange() {
        if (file == null) {
            return DiagnosticRange.synthetic("");
        }
        if (startScalarOffset < 0 || endScalarOffset < 0) {
            return DiagnosticRange.synthetic(file);
        }
        if (startLine == endLine && startColumn == endColumn
                && startScalarOffset == endScalarOffset) {
            return new DiagnosticRange(file, startLine, startColumn, startLine,
                startColumn, startScalarOffset, startScalarOffset, 0,
                RangeOrigin.SOURCE);
        }
        int exclusiveEndColumn =
            endColumn == Integer.MAX_VALUE ? endColumn : endColumn + 1;
        return new DiagnosticRange(file, startLine, startColumn, endLine,
            exclusiveEndColumn, startScalarOffset, endScalarOffset,
            endScalarOffset - startScalarOffset, RangeOrigin.SOURCE);
    }
}
