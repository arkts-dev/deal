package deal.descriptors;

/**
 * The single value-model seam through which
 * {@link RuntimeTypeMatcher#check(String, Object, RuntimeSourceLocation)}
 * reads backend-represented values (design source
 * {@code canonical-type-system-and-runtime-descriptors} D4).
 *
 * <p>The compiler side holds no backend value carriers; the matcher
 * therefore inspects every value exclusively through this seam.  Its
 * accessors are exactly the normative check table's named
 * backend-representation predicates and nothing else:</p>
 *
 * <ul>
 *   <li>{@link #isNull(Object)} — null-ness: the {@code null} row's
 *       backend null representation predicate.</li>
 *   <li>{@link #isBoolean(Object)} — the boolean scalar-carrier
 *       predicate (the {@code boolean} row's "backend scalar
 *       predicate"); the boolean carrier's payload is the predicate
 *       value itself, so the row consults no separate payload.</li>
 *   <li>{@link #isString(Object)} / {@link #stringPayload(Object)} —
 *       the string scalar-carrier predicate and the string scalar
 *       payload (the {@code string} row's JVM unpaired-surrogate
 *       sub-check reads the payload).</li>
 *   <li>{@link #isNumber(Object)} / {@link #numberPayload(Object)} —
 *       the number scalar-carrier predicate and the number scalar
 *       payload.  The payload is also the {@code int} row's carrier
 *       payload: the table pins the int predicate as "number carrier
 *       that is finite and integral, -0 normalized to 0, then the
 *       safe-range gate", so the matcher applies the
 *       finite/integral/-0/range gates itself — never the seam.</li>
 *   <li>{@link #isTable(Object)} — table-ness: the {@code table}
 *       row's backend table representation predicate.</li>
 *   <li>{@link #checkBytes(Object, RuntimeSourceLocation)} — the bytes
 *       hook with the pinned {@code check_bytes(value, range)}
 *       contract (parent D4 helper set: {@code -> bytes | E8001}).
 *       E6 owns the bytes representation and predicate; the seam
 *       implementer supplies the hook implementation and this epic's
 *       tests inject a conformant double.</li>
 *   <li>{@link #classIdentityText(Object)} — the tagged class identity
 *       text of the {@code @...} row ({@code null} when the value
 *       carries no class tag).</li>
 *   <li>{@link #functionDescriptorText(Object)} — the function-wrapper
 *       carried canonical descriptor text of the function row
 *       ({@code null} when the value is not a function wrapper).</li>
 *   <li>{@link #isArray(Object)}, {@link #arrayLength(Object)},
 *       {@link #arrayElementAt(Object, int)} — the array-representation
 *       predicate and ordered array element access in index order.</li>
 * </ul>
 *
 * <p>The seam adds no checking semantics beyond the table: every
 * accessor returns a raw backend-representation fact, none of them
 * decides pass or fail, and failure production stays in the matcher.
 * The only pinned exception is the bytes hook, whose {@code bytes |
 * E8001} contract (parent D4) makes the hook itself produce its own
 * E8001 failure value; the matcher routes the row exclusively through
 * that hook and propagates its outcome unchanged.</p>
 *
 * <p>Calling conventions (pinned):</p>
 * <ul>
 *   <li>{@code stringPayload} is called only when
 *       {@code isString(value)} is true and must return the carried
 *       string.</li>
 *   <li>{@code numberPayload} is called only when
 *       {@code isNumber(value)} is true and must return the carried
 *       number.</li>
 *   <li>{@code arrayLength} and {@code arrayElementAt} are called only
 *       when {@code isArray(value)} is true, with
 *       {@code 0 <= index < arrayLength(value)}.</li>
 *   <li>{@code checkBytes(value, range)} returns the checked bytes
 *       value on success or a {@link RuntimeCheckFailure} carrying
 *       {@code E8001} on failure — never {@code null}, never a raw
 *       exception.</li>
 * </ul>
 *
 * <p>Implementations must be deterministic and side-effect free for a
 * single check: the matcher guarantees it never mutates values and
 * relies on accessors not mutating them either.</p>
 */
public interface RuntimeValueModel {

    /**
     * Null-ness: whether {@code value} is the backend's null
     * representation (the {@code null} row's predicate).
     */
    boolean isNull(Object value);

    /**
     * Whether {@code value} is a boolean scalar carrier (the
     * {@code boolean} row's backend scalar predicate).
     */
    boolean isBoolean(Object value);

    /**
     * Whether {@code value} is a string scalar carrier (the
     * {@code string} row's backend scalar predicate).
     */
    boolean isString(Object value);

    /**
     * The string scalar payload of a string carrier — called only when
     * {@link #isString(Object)} is true.  The matcher applies the JVM
     * unpaired-surrogate sub-check to the returned text.
     */
    String stringPayload(Object value);

    /**
     * Whether {@code value} is a number scalar carrier (the
     * {@code number} row's backend scalar predicate and the
     * {@code int} row's carrier — see the type-level javadoc).
     */
    boolean isNumber(Object value);

    /**
     * The number scalar payload of a number carrier — called only when
     * {@link #isNumber(Object)} is true.  This is also the int row's
     * carrier payload: the matcher applies the finite/integral/-0
     * normalization/safe-range gates to it, never the seam.
     */
    double numberPayload(Object value);

    /**
     * Table-ness: whether {@code value} is the backend's table
     * representation (the {@code table} row's predicate).
     */
    boolean isTable(Object value);

    /**
     * The E6-pinned bytes hook {@code check_bytes(value, range)} with
     * the parent D4 helper-set contract {@code -> bytes | E8001}.
     *
     * <p>The matcher's bytes row routes exclusively through this hook
     * and consults no other predicate; the hook receives
     * {@code (value, range)} unchanged and its outcome drives the row.
     * E6 owns the representation and predicate; until E6 lands, the
     * seam implementer supplies the hook implementation.</p>
     *
     * @param value the backend-represented value being checked
     * @param range the optional source location exactly as supplied to
     *              the check
     * @return the checked bytes value on success, or a
     *         {@link RuntimeCheckFailure} carrying {@code E8001} on
     *         failure — never {@code null}
     */
    Object checkBytes(Object value, RuntimeSourceLocation range);

    /**
     * The tagged class identity text of the {@code @...} row: the
     * runtime tag the value carries, or {@code null} when the value is
     * not a tagged class instance.  The matcher compares the returned
     * text byte-for-byte against the parsed {@link DescriptorAst.ClassAtom}
     * full text.
     */
    String classIdentityText(Object value);

    /**
     * The function-wrapper carried canonical descriptor text of the
     * function row, or {@code null} when the value is not a function
     * wrapper.  The matcher compares the returned text byte-for-byte
     * against the parsed {@link DescriptorAst.FunctionAtom} text.
     */
    String functionDescriptorText(Object value);

    /**
     * Whether {@code value} is the backend's array representation (the
     * {@code [D]} row's predicate).
     */
    boolean isArray(Object value);

    /**
     * The ordered element count of an array — called only when
     * {@link #isArray(Object)} is true.
     */
    int arrayLength(Object value);

    /**
     * The array element at a 0-based index — called only when
     * {@link #isArray(Object)} is true and
     * {@code 0 <= index < arrayLength(value)}.  The matcher checks the
     * elements recursively in index order.
     */
    Object arrayElementAt(Object value, int index);
}
