package deal.ast;

import java.util.List;

/**
 * Template literal expression: {@code `text ${expr} text`}.
 * The {@code parts} list alternates between string literals (even indices)
 * and interpolated expressions (odd indices). A template with no
 * interpolations has a single string literal part.
 */
public record TemplateLiteralExpr(
    Span span,
    List<ExpressionNode> parts
) implements ExpressionNode {}
