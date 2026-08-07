package deal.ast;

/** A parameter within a function type annotation. */
public record FunctionTypeParam(Span span, String name, TypeNode type) {}
