package deal.semantic.ir;

/**
 * The closed index-mode set of {@code deal.semantic-ir/1} (parent D14;
 * schema S3).
 *
 * <p>Closed set — exactly {@link #ARRAY_READ}, {@link #ARRAY_WRITE},
 * {@link #TABLE_READ}, and {@link #TABLE_WRITE}; no open or unknown
 * fallback member and no external extension point exist.
 * {@code INDEX_NORMALIZE} carries the mode: it is a pure slot/key
 * computation (policy {@code NO_DEAL_FAILURE}) reading the resolved
 * receiver's current length at normalize time; negative/out-of-range
 * enforcement happens only in the named read/write/delete boundary
 * policies.</p>
 */
public enum IndexMode {

    /** Array read slot normalization. */
    ARRAY_READ,

    /** Array write slot normalization. */
    ARRAY_WRITE,

    /** Table read key normalization. */
    TABLE_READ,

    /** Table write key normalization. */
    TABLE_WRITE
}
