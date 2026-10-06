package deal.semantic.ir;

public enum ExternalModuleKind {

    /** An implementation module in the dependency closure. */
    IMPLEMENTATION,

    DECLARATION,

    /** A spec-standard-library module. */
    STDLIB,

    /** A host declaration module. */
    HOST
}
