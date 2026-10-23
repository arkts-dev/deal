package deal.semantic.ir;

import java.util.Objects;

public record BoundaryContext(
    Integer parameterIndex,
    Double index,
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

    /**
     * The {@code ARRAY_READ_INDEX_THEN_DESCRIPTOR} context: the read index
     * and the current logical array length at check time. Both bounds are
     * checked ({@code index < 0} or {@code index >= length} is the pinned
     * E8002 before any storage access or element/result validation).
     */
    public static BoundaryContext arrayRead(double index, int length) {
        if (length < 0) {
            throw new IllegalArgumentException("length must be >= 0, got " + length);
        }
        return new BoundaryContext(null, index, length, null, null);
    }

    /** The {@code ARRAY_WRITE_BOUNDS_THEN_ELEMENT}/{@code ARRAY_DELETE_BOUNDS}
     *  context: the index and the array length at check time. */
    public static BoundaryContext writeBounds(double index, int length) {
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
