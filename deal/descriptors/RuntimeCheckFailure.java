package deal.descriptors;

import java.util.Set;

/**
 * A runtime check failure per the pinned {@link RuntimeTypeMatcher} table
 * (design source {@code canonical-type-system-and-runtime-descriptors}
 * D4): one of the pinned E8xxx codes with the byte-exact pinned message
 * and the optional source location supplied to the failing check.
 *
 * <p>Failure production is confined to the matcher (and, for the bytes
 * row only, the E6-pinned {@code check_bytes(value, range)} hook whose
 * contract is {@code bytes | E8001}): {@link RuntimeTypeMatcher#check}
 * never throws a raw exception and never yields a partial result — every
 * failure is a {@code RuntimeCheckFailure} value.  The accepted codes are
 * exactly the table's failure codes:</p>
 *
 * <ul>
 *   <li>{@code E8001} — value/shape mismatch family (including the
 *       defensive unparsable-descriptor failure and the string row's
 *       unpaired-surrogate sub-message);</li>
 *   <li>{@code E8003} — {@code array element {i} type mismatch} at the
 *       first failing index;</li>
 *   <li>{@code E8004} — {@code int out of safe range} (finite integral
 *       outside ±(2^53-1));</li>
 *   <li>{@code E8010} — {@code function signature mismatch: expected
 *       {D}, got {actual}} on any carried-descriptor byte delta.</li>
 * </ul>
 *
 * <p>{@code range} is the optional {@link RuntimeSourceLocation} exactly
 * as supplied to the check ({@code null} when no location was
 * supplied).</p>
 */
public record RuntimeCheckFailure(String code, String message, RuntimeSourceLocation range) {

    /**
     * The pinned failure codes of the normative check table — the only
     * codes a {@code RuntimeCheckFailure} may carry.
     */
    public static final Set<String> PINNED_CODES = Set.of("E8001", "E8003", "E8004", "E8010");

    public RuntimeCheckFailure {
        if (code == null || !PINNED_CODES.contains(code)) {
            throw new IllegalArgumentException(
                "code must be one of the pinned matcher codes " + PINNED_CODES + ": " + code);
        }
        if (message == null || message.isEmpty()) {
            throw new IllegalArgumentException("message must not be null or empty");
        }
    }
}
