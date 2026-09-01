package deal.ast;

/**
 * A declaration directive recorded as class metadata
 * (fixed-name-directive-events D5).
 *
 * <p>{@link #JSONABLE} drives the derived {@link ClassDeclaration#isJsonable()}
 * accessor; {@link #C_STRUCT}/{@link #C_POINTER} are recorded for
 * extern-C marker cardinality validation. Semantic C declaration policy
 * belongs to ISSUE-0162.</p>
 */
public enum DeclarationDirective {
    JSONABLE,
    C_STRUCT,
    C_POINTER
}
