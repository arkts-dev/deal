package deal.descriptors;

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
