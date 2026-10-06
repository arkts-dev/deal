package deal.semantic.ir;

public enum DefaultOwner {

    /** Defaults are applied locally by the constructing {@code CLASS_NEW}. */
    LOCAL,

    /** Defaults transfer to the shared owner module's {@code CLASS_FACTORY}. */
    SHARED_FACTORY,

    /** Defaults transfer through the retained target ABI factory entry. */
    RETAINED_ABI,

    HOST_DEFAULTS,

    FFI_PLAN,

    BUILTIN_DEFAULTS
}
