package deal.ast;

/**
 * A named type reference.
 * name is one of: null, boolean, int, number, string, table,
 * Error, or a user-defined class name.
 */
public record NamedType(Span span, String name) implements TypeNode {}
