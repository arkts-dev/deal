package deal.descriptors;

public interface RuntimeValueModel {

    /**
     * Null-ness: whether {@code value} is the backend's null
     * representation (the {@code null} row's predicate).
     */
    boolean isNull(Object value);

    /** Whether value is the backend's distinct language-undefined carrier. */
    boolean isUndefined(Object value);

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
     * Whether {@code value} is a binary64 number scalar carrier.
     */
    boolean isNumber(Object value);

    /**
     * The number scalar payload of a number carrier — called only when
     * {@link #isNumber(Object)} is true. Preserve negative zero, NaN and
     * infinities; integral-position checks belong to their consumers.
     */
    double numberPayload(Object value);

    /**
     * Table-ness: whether {@code value} is the backend's table
     * representation (the canonical {@code record} row's predicate).
     */
    boolean isTable(Object value);

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
