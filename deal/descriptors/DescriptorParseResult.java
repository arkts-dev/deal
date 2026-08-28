package deal.descriptors;

/**
 * Result of {@link CanonicalRuntimeTypeDescriptor#parse(String)}: exactly
 * one complete {@link DescriptorAst} on success or one
 * {@link DescriptorSyntaxError} on failure.
 *
 * <p>This sealed family is the Java realization of the pinned parse
 * signature {@code DescriptorAst | DescriptorSyntaxError}.  Parsing never
 * throws and never yields a partial AST: every failure carries a 0-based
 * Unicode scalar offset and one of the pinned syntax-error kinds.</p>
 */
public sealed interface DescriptorParseResult permits DescriptorAst, DescriptorSyntaxError {
}
