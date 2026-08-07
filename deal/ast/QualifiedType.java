package deal.ast;

/**
 * A qualified type reference like {@code ModuleAlias.ClassName}.
 *
 * <p>Used for type annotations that reference a class exported from
 * another module, e.g. {@code let r: B.Result = ...} where B is an
 * import alias and Result is the exported class.</p>
 */
public record QualifiedType(Span span, String moduleName, String typeName) implements TypeNode {}
