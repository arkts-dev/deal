package deal.semantic.ir;

/**
 * The closed semantic capability set of the common lowering layer
 * (foundation F3/F7; schema S4 capability catalog).
 *
 * <p>Closed set — exactly the twelve values below in the pinned S4
 * capability order; no open or unknown fallback member and no external
 * extension point exist. The declaration order is normative: the release
 * capability registry orders its canonical JSON entries by capability in
 * exactly this order ({@code capabilityRegistryHash} = SHA-256 of the
 * canonical JSON over the ordered capability × target cross product).
 * {@link #STDLIB_TIME_CONFLICT} is a routing marker only: a module whose
 * manifest requires it is never common-lowerable in any purpose and stays
 * on retained routes (parent D8; the member-access and import-propagation
 * arms of foundation F3).</p>
 */
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

    /** Routing marker: the module references std/time.nowMillis (never lowered). */
    STDLIB_TIME_CONFLICT,

    /** Classes, class factories/defaults, fields, and JSON class conversions. */
    CLASSES,

    /** Module init/import and export read/publish operations. */
    MODULES
}
