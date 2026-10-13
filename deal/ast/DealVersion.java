package deal.ast;

/**
 * An exact numeric declaration version pair
 * (fixed-name-directive-events D5/D7).
 *
 * <p>Supported version is {@code (1,3)}. Parsing is pure numeric:
 * {@code 1.3}, {@code 01.03}, and {@code 1.03} are all numerically
 * equal to {@code (1,3)}. No migration registry exists or is
 * consulted.</p>
 */
public record DealVersion(long major, long minor) {

    public DealVersion {
        if (major < 0) {
            throw new IllegalArgumentException("major must be >= 0, got " + major);
        }
        if (minor < 0) {
            throw new IllegalArgumentException("minor must be >= 0, got " + minor);
        }
    }

    /** The compiler-supported declaration version. */
    public static final DealVersion V1_3 = new DealVersion(1, 3);
}
