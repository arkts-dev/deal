package deal.ast;

/** A function parameter: name + type annotation. */
public record Parameter(Span span, String name, TypeNode type) {}
