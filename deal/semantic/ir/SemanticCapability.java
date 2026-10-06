package deal.semantic.ir;

public enum SemanticCapability {

    /** Core value kinds and their operations (CONST, UNARY, BINARY, STRING_CONCAT). */
    FOUNDATION_VALUES,

    /** Signed-32-bit integer semantics and conversion/boundary operations. */
    SIGNED_INT32,

    /** Array/table/string containers, members, indexes, optionals, has, for-of. */
    CONTAINERS_AND_STRINGS,

    /** Structural descriptors of declared types. */
    DESCRIPTORS,

    /** Explicit boundary operations over the closed boundary-assignment table. */
    BOUNDARIES,

    /** Evaluation order, branches, loops, and discards. */
    EVALUATION_ORDER,

    /** Bindings, allocations, recursive groups, and closure creation. */
    BINDINGS,

    /** Calls, callbacks, external entries, async start/await, return, entry invoke. */
    CALLS,

    /** Standard-library algorithm calls over the closed stdlib table. */
    STDLIB_SEMANTICS,

    STDLIB_TIME_CONFLICT,

    /** Classes, class factories/defaults, fields, and JSON class conversions. */
    CLASSES,

    /** Module init/import and export read/publish operations. */
    MODULES
}
