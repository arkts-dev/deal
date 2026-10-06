package deal.semantic.ir;

import java.util.Objects;

public record BoundaryContext(
    Integer parameterIndex,
    Integer index,
    Integer length,
    Integer elementIndex,
    String fieldPath
) {

    /** The empty context: no cell names a field. */
    public static BoundaryContext none() {
        return new BoundaryContext(null, null, null, null, null);
    }

    /** The {@code HOST_PARAMETER} context: one-based parameter position. */
    public static BoundaryContext parameter(int oneBasedParameterIndex) {
        if (oneBasedParameterIndex < 1) {
            throw new IllegalArgumentException(
                "parameterIndex is one-based, got " + oneBasedParameterIndex);
        }
        return new BoundaryContext(oneBasedParameterIndex, null, null, null, null);
    }

    /** The {@code ARRAY_READ_INDEX_THEN_DESCRIPTOR} context: the read index. */
    public static BoundaryContext arrayIndex(int index) {
        return new BoundaryContext(null, index, null, null, null);
    }

    /** The {@code ARRAY_WRITE_BOUNDS_THEN_ELEMENT}/{@code ARRAY_DELETE_BOUNDS}
     *  context: the index and the array length at check time. */
    public static BoundaryContext writeBounds(int index, int length) {
        if (length < 0) {
            throw new IllegalArgumentException("length must be >= 0, got " + length);
        }
        return new BoundaryContext(null, index, length, null, null);
    }

    /**
     * The bytes cells' context (K6 item 3): the bytes index and the
     * receiver's length at check time — the read's own length read for
     * {@code BYTES_READ}, the chain's length child for
     * {@code BYTES_WRITE}.
     */
    public static BoundaryContext bytesBounds(int index, int length) {
        if (length < 0) {
            throw new IllegalArgumentException("length must be >= 0, got " + length);
        }
        return new BoundaryContext(null, index, length, null, null);
    }

    /** The {@code ARRAY_ELEMENT_DESCRIPTOR} context: one-based element position. */
    public static BoundaryContext element(int oneBasedElementIndex) {
        if (oneBasedElementIndex < 1) {
            throw new IllegalArgumentException(
                "elementIndex is one-based, got " + oneBasedElementIndex);
        }
        return new BoundaryContext(null, null, null, oneBasedElementIndex, null);
    }

    /** The {@code JSON_TO_ERROR} context: the field path of the failing value. */
    public static BoundaryContext jsonField(String fieldPath) {
        Objects.requireNonNull(fieldPath, "fieldPath must not be null");
        return new BoundaryContext(null, null, null, null, fieldPath);
    }
}
