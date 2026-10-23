package deal.semantic.ir;

import java.util.List;


public enum BoundaryKind {

    VARIABLE_DECLARATION,
    VARIABLE_ASSIGNMENT,
    CLASS_FIELD_ASSIGNMENT,
    ARRAY_ELEMENT_ASSIGNMENT,
    ARRAY_ELEMENT_READ,
    ARRAY_ELEMENT_DELETE,
    ARRAY_LITERAL_ELEMENT,
    FUNCTION_PARAMETER,
    FUNCTION_RETURN,
    ASYNC_COMPLETION,
    CLASS_LITERAL_FIELD,
    UNTYPED_CLASS_INPUT,
    OPTIONAL_FIELD_READ,
    CONTEXTUAL_TABLE_READ,
    IMPORTED_MEMBER_READ,
    MODULE_EXPORT,
    HOST_TO_DEAL,
    DEAL_TO_HOST,
    STDLIB_PARAMETER,
    STDLIB_RETURN,
    EXTERNAL_PARAMETER,
    EXTERNAL_RETURN,
    JSON_FROM_FIELD,
    JSON_TO_FIELD,
    /**
     * The operand/condition/value-consumer/throw-transfer position's
     * validation of one identifier read whose checked type is a
     * null-narrowing refinement of the binding's declared nullable type
     * (declared {@code T | null}, read as {@code T}) — docs/spec-v1.3.md
     * Nullable semantics: narrowing is optimistic across calls, so an
     * indirect captured-binding mutation leaves the refined read's value
     * invalid and the consuming operator, index chain, table member
     * operation, {@code .length} read, for-of loop, {@code has} presence
     * read or throw transfer must report the typed-boundary projection
     * instead of coercing it, letting a raw backend error escape, reading a
     * length from it, dereferencing the loop's iterable carrier (the loop's
     * initialization dereferences that carrier before any iteration, so the
     * admission precedes it), reading presence off it, or dereferencing the
     * value's transport in the throw machinery. The op publishes the
     * admitted value (its result is the consumer's operand, index chain
     * child, table member receiver, {@code ARRAY_LENGTH} receiver, the
     * loop's iterable, the throw op's operand or the presence read's
     * receiver — the refined class descriptor's nominal check) and carries
     * the descriptor-kind policy of its own descriptor.
     */
    NARROWED_READ;

    /**
     * The reserved boundary names, invalid as {@code BoundaryKind} values
     * in {@code deal.semantic-ir/2}. They are not enum members.
     */
    public static final List<String> RESERVED_NAMES = List.of();
    /** Returns true iff {@code name} is a reserved (invalid) boundary name. */
    public static boolean isReservedName(String name) {
        return RESERVED_NAMES.contains(name);
    }
}
