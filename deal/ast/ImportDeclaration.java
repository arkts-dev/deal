package deal.ast;

public record ImportDeclaration(
    Span span,
    String alias,
    String modulePath
) implements StatementNode {
}
