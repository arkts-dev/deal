package deal.semantic.ir;

/**
 * The sealed family of stable semantic identities of
 * {@code deal.semantic-ir/1} (parent D4; schema S1).
 *
 * <p>Every identity is globally unique within one
 * {@link ExecutableLoweredProject}; {@link OpId} additionally carries its
 * module so cross-unit {@code parentOpId} references are unambiguous.
 * Uniqueness within a project is enforced by the validator (T6), never by
 * construction. The sealed hierarchy is the only identity surface: no
 * consumer can introduce an identity type outside this file, and no
 * semantic ID is a frontend object, an AST node, a {@code SymbolTable}, or
 * a {@code CheckResult} entry (those identity-keyed frontend facts are
 * copied into these stable IDs at bridge time).</p>
 *
 * <p>Closed set of identity kinds: {@link ModuleId} (dotted module path),
 * {@link ClassId} ({@code @modulePath/ClassName}; {@code Error} is
 * builtin), {@link FunctionId}, {@link BindingId}, {@link ValueId},
 * {@link AsyncTokenId} ({@code CANONICAL} or {@code ALIAS}),
 * {@link AnchorId}, {@link OpId} (carries its module), plus the
 * schema-referenced {@link BlockId}, {@link ClassFactoryId}, and
 * {@link FunctionAllocationIdentity}.</p>
 */
public sealed interface SemanticId
    permits ModuleId,
            ClassId,
            FunctionId,
            BindingId,
            ValueId,
            AsyncTokenId,
            AnchorId,
            BlockId,
            OpId,
            ClassFactoryId,
            FunctionAllocationIdentity {
}
