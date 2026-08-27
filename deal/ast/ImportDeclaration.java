package deal.ast;

import java.util.List;

/** import * as alias from "modulePath" */
public record ImportDeclaration(
    Span span,
    String alias,
    String modulePath,
    List<String> directives
) implements StatementNode {

    /**
     * Convenience constructor for backward compatibility.
     * {@code directives} defaults to the empty list — no compiler
     * directive (e.g. {@code @extern-c}) attached to the import token
     * (the {@code ClassDeclaration} backward-compat pattern).
     */
    public ImportDeclaration(Span span, String alias, String modulePath) {
        this(span, alias, modulePath, List.of());
    }
}
