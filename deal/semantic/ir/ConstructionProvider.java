package deal.semantic.ir;

public enum ConstructionProvider {

    /** The constructing CLASS_NEW uses its local declaring layout. */
    LOCAL,

    /** Resolve the exact declaring provider through CLASS_FACTORY. */
    SHARED_FACTORY,

    /** Resolve a target ABI construction provider. */
    RETAINED_ABI,

    HOST_DECLARATION,


    BUILTIN_ERROR
}
