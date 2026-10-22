package deal.codegen;

import deal.diagnostics.DiagnosticRange;
import deal.semantic.ir.DiagnosticOriginCarrier;

/**
 * The common source-bearing emitter rejection signal (design
 * {@code shared-lowering-diagnostic-origins} D4): the existing
 * {@link IllegalStateException} classification with the existing message
 * and cause, plus the optional immutable diagnostic range of the explicit
 * rejected operation or detached body the emitter refused.
 *
 * <p>The production arm owns classification and projection: an emitter
 * signal that identified its offending operation carries that operation's
 * own copied origin, and the arm projects it through the range-aware
 * registry surface. A genuinely originless emitter failure stays an
 * ordinary {@link IllegalStateException} and keeps the canonical
 * synthetic projection. Source-bearing signals retain the same range contract.</p>
 *
 */
public class SharedEmissionFailure extends IllegalStateException
        implements DiagnosticOriginCarrier {

    private static final long serialVersionUID = 1L;

    /** The explicit rejected operation's own range, or {@code null}. */
    private final DiagnosticRange offendingRange;

    public SharedEmissionFailure(String message, DiagnosticRange offendingRange) {
        super(message);
        this.offendingRange = offendingRange;
    }

    public SharedEmissionFailure(String message, Throwable cause,
                                 DiagnosticRange offendingRange) {
        super(message, cause);
        this.offendingRange = offendingRange;
    }

    /**
     * The rejected operation's own source range, or {@code null} when the
     * signal names no identified operation (the canonical synthetic
     * projection stays the E6005's own).
     */
    @Override
    public DiagnosticRange diagnosticRange() {
        return offendingRange;
    }
}
