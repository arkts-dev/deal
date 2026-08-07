package deal.ast;

/** T[] — an array type with an element type. */
public record ArrayType(Span span, TypeNode elementType) implements TypeNode {}
