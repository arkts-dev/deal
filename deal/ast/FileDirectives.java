package deal.ast;

import deal.diagnostics.DiagnosticRange;

/**
 * File-directive metadata recorded on a program
 * (fixed-name-directive-events D5).
 *
 * @param effectiveDealVersion the effective declaration version: the
 *                             declared valid value, else the compiler
 *                             default {@code (1,2)}
 * @param declaredDealVersion  the declared version, absent when the
 *                             directive is omitted or malformed
 * @param externC              the effective extern-C flag: a valid,
 *                             non-duplicate {@code @extern-c} event in a
 *                             {@code .d.deal} file with valid placement
 * @param dealVersionRange     complete directive comment range of the
 *                             first {@code @deal-version} event, or null
 * @param externCRange         complete directive comment range of the
 *                             first {@code @extern-c} event, or null
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
