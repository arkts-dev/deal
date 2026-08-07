package deal.ast;

/**
 * export (FunctionDeclaration | ClassDeclaration)
 *
 * <p>The declaration must be a FunctionDeclaration or ClassDeclaration;
 * this is enforced at parse time.</p>
 */
public record ExportDeclaration(
    Span span,
    StatementNode declaration
) implements StatementNode {}
