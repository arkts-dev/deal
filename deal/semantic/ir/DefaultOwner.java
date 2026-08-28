package deal.semantic.ir;

/**
 * The closed default-ownership set of class construction (parent D16;
 * schema S3): who fills defaults for omitted required-present fields of a
 * constructed class.
 *
 * <p>Closed set — exactly {@link #LOCAL}, {@link #SHARED_FACTORY}, and
 * {@link #RETAINED_ABI}; no open or unknown fallback member and no
 * external extension point exist. {@code LOCAL} runs {@code CLASS_DEFAULT}
 * children in declaration order inside the caller's {@code CLASS_NEW};
 * {@code SHARED_FACTORY} transfers to the owner module's
 * {@code CLASS_FACTORY} op; {@code RETAINED_ABI} transfers through the
 * {@code TargetModuleAbi} factory entry (no common default ops). Defaults
 * evaluate per construction in the declaring module's scope.</p>
 */
public enum DefaultOwner {

    /** Defaults are applied locally by the constructing {@code CLASS_NEW}. */
    LOCAL,

    /** Defaults transfer to the shared owner module's {@code CLASS_FACTORY}. */
    SHARED_FACTORY,

    /** Defaults transfer through the retained target ABI factory entry. */
    RETAINED_ABI
}
