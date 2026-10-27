package deal.ast;

/** A source nullish-union member; duplicates are diagnosed during resolution. */
public record NullableType(Span span, TypeNode innerType, boolean undefinedMember) implements TypeNode {
    public NullableType(Span span, TypeNode innerType) { this(span, innerType, false); }
}
