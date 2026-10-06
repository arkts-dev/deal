package deal.ast;

import deal.diagnostics.DiagnosticRange;

/**
 * File-directive metadata recorded on a program
 * (fixed-name-directive-events D5).
 *
 */
public record FileDirectives(
    DealVersion effectiveDealVersion,
    DealVersion declaredDealVersion,
    boolean externC,
    DiagnosticRange dealVersionRange,
    DiagnosticRange externCRange
) {

    public FileDirectives {
        if (effectiveDealVersion == null) {
            throw new IllegalArgumentException(
                "effectiveDealVersion must not be null");
        }
    }

    /**
     * The empty default: effective {@code (1,2)}, declared absent,
     * no extern-C flag, no ranges.
     */
    public static FileDirectives empty() {
        return new FileDirectives(DealVersion.V1_2, null, false, null, null);
    }
}
