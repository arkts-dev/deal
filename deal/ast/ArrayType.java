package deal.ast;

/** T[] — an array type with an element type. */
public record ArrayType(Span span, TypeNode elementType, boolean readonly) implements TypeNode {
    public ArrayType(Span span, TypeNode elementType) { this(span, elementType, false); }
}
