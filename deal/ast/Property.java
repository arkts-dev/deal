package deal.ast;

/** A property within an object literal: name + value expression. */
public record Property(Span span, String name, ExpressionNode value) {}
