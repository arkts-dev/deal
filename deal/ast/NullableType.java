package deal.ast;

/** T | null — a nullable type wrapping an inner type. */
public record NullableType(Span span, TypeNode innerType) implements TypeNode {}
