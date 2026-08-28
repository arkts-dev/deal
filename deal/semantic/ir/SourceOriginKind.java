package deal.semantic.ir;

/**
 * The origin kind of a {@link SourceOrigin} (mandatory op header; S2):
 * {@code USER} for ops lowering user source constructs, {@code SYNTHETIC}
 * for compiler-generated ops (adapters, boundary reification, and other
 * lowering machinery).
 *
 * <p>Closed set — exactly the two values below; no open or unknown
 * fallback member and no external extension point exist.</p>
 */
public enum SourceOriginKind {

    /** The op lowers a user source construct. */
    USER,

    /** The op is compiler-generated. */
    SYNTHETIC
}
