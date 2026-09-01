package deal.ast;

/** import * as alias from "modulePath" */
public record ImportDeclaration(
    Span span,
    String alias,
    String modulePath
) implements StatementNode {
}
