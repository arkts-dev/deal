package deal.lexer;

/**
 * The five fixed compiler directive names
 * (fixed-name-directive-events D1/D2).
 *
 * <p>The fixed matching order is the declaration order:
 * {@code deal-version}, {@code jsonable}, {@code extern-c},
 * {@code c-struct}, {@code c-pointer}. The first complete literal match
 * is the name; every remaining pre-terminator scalar is the raw
 * argument, with no delimiter requirement.</p>
 */
public enum DirectiveName {
    DEAL_VERSION("deal-version"),
    JSONABLE("jsonable"),
    EXTERN_C("extern-c"),
    C_STRUCT("c-struct"),
    C_POINTER("c-pointer");

    private final String text;

    DirectiveName(String text) {
        this.text = text;
    }

    /** The literal source spelling of the directive name. */
    public String text() {
        return text;
    }

    /**
     * True for the declaration directives {@code jsonable},
     * {@code c-struct}, {@code c-pointer} — the names that start or
     * extend a pending declaration run (D3).
     */
    public boolean isDeclarationDirective() {
        return this == JSONABLE || this == C_STRUCT || this == C_POINTER;
    }

    /**
     * True for the file directives {@code deal-version} and
     * {@code extern-c} — the names that clear a pending declaration run
     * without anchoring it (D3).
     */
    public boolean isFileDirective() {
        return this == DEAL_VERSION || this == EXTERN_C;
    }

    /**
     * True for the C marker directives {@code c-struct} and
     * {@code c-pointer} (D4).
     */
    public boolean isCMarker() {
        return this == C_STRUCT || this == C_POINTER;
    }
}
