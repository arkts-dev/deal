package deal.lexer;
/** The compiler's declaration directive. */
public enum DirectiveName {
    JSONABLE("jsonable");

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
        return true;
    }

    public boolean isFileDirective() {
        return false;
    }
}
