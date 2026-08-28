package deal.semantic.ir;

/**
 * The closed external-module kind of the {@code deal.semantic-interface/1}
 * index (parent canonical surfaces): an index entry is an implementation
 * module, a declaration module, a standard-library module, or a host
 * module.
 *
 * <p>Closed set — exactly the four values below; no open or unknown
 * fallback member and no external extension point exist. Under the
 * current orchestrator classification, spec-stdlib modules are
 * {@code STDLIB} index entries and every other declaration file is a
 * {@code HOST} index entry; the parent-pinned {@code DECLARATION} kind has
 * no producer in this epic (the parent-pinned DECLARATION input kind has
 * no producer either).</p>
 */
public enum ExternalModuleKind {

    /** An implementation module in the dependency closure. */
    IMPLEMENTATION,

    /** A declaration module (parent-pinned; no producer in this epic). */
    DECLARATION,

    /** A spec-standard-library module. */
    STDLIB,

    /** A host declaration module. */
    HOST
}
