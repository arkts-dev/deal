package deal.semantic.ir;

public enum ConstructionProvider {

    /** The constructing CLASS_NEW uses its local declaring layout. */
    LOCAL,

    /** Resolve the exact declaring provider through CLASS_FACTORY. */
    SHARED_FACTORY,

    /** Resolve a target ABI construction provider. */
    RETAINED_ABI,

    /** Construct from a host declaration's structural layout, never host hooks. */
    HOST_DECLARATION,

    /** Construct the builtin Error with explicitly supplied code and message. */
    BUILTIN_ERROR
}
