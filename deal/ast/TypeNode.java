package deal.ast;

/**
 * Root sealed interface for all type annotation AST nodes.
 * These are the parse-tree representations of type syntax,
 * distinct from the internal {@link deal.types.Type} representation.
 */
public sealed interface TypeNode
    permits NamedType,
            ArrayType,
            NullableType,
            FunctionType,
            QualifiedType {

    Span span();
}
