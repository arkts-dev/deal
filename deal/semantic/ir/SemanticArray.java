package deal.semantic.ir;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.IntFunction;
import java.util.function.IntSupplier;

public final class SemanticArray<V> {

    /** A producer defect: an element access outside {@code [0, size)}. */
    public static final class Defect extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public Defect(String message) {
            super(message);
        }
    }

    /** The ordered elements; immutable and fixed at allocation (null for a live view). */
    private final List<V> elements;

    /** The current element count of a live read-only view (null for a materialized array). */
    private final IntSupplier liveSize;

    /** The current element of a live read-only view (null for a materialized array). */
    private final IntFunction<V> liveElementAt;

    private SemanticArray(List<V> elements) {
        this.elements = elements;
        this.liveSize = null;
        this.liveElementAt = null;
    }

    private SemanticArray(IntSupplier liveSize, IntFunction<V> liveElementAt) {
        this.elements = null;
        this.liveSize = liveSize;
        this.liveElementAt = liveElementAt;
    }

    /**
     * Allocates a fresh array with a fresh identity holding
     * {@code elements} in the given order.
     *
     */
    public static <V> SemanticArray<V> of(List<V> elements) {
        Objects.requireNonNull(elements, "elements must not be null");
        return new SemanticArray<>(List.copyOf(elements));
    }

    /**
     * Allocates a fresh array with a fresh identity holding the given
     * elements in the given order.
     *
     */
    @SafeVarargs
    public static <V> SemanticArray<V> of(V... elements) {
        Objects.requireNonNull(elements, "elements must not be null");
        return new SemanticArray<>(List.of(elements));
    }

    /**
     * A read-only live view over opaque backing state: {@code size} supplies
     * the current element count and {@code elementAt} the current element,
     * both read from the backing state on every call. Identity and current
     * contents cross a representation boundary through this view: the same
     * view object represents the same backing array, and an in-place commit
     * to the backing elements is observed by every alias. A live view is
     * never mutated (its element list is derived, not stored).
     */
    public static <V> SemanticArray<V> live(IntSupplier size,
                                            IntFunction<V> elementAt) {
        Objects.requireNonNull(size, "size must not be null");
        Objects.requireNonNull(elementAt, "elementAt must not be null");
        return new SemanticArray<>(size, elementAt);
    }

    /**
     * The signed32 element count.
     *
     */
    public int size() {
        return liveSize != null ? liveSize.getAsInt() : elements.size();
    }

    /**
     * Ordered element access: the element at {@code index} (0-based, in
     * allocation order).
     *
     */
    public V elementAt(int index) {
        int size = size();
        if (index < 0 || index >= size) {
            throw new Defect("array element access outside [0, size): index " + index
                + ", size " + size + " — the executor applies the pinned "
                + "bounds policy before accessing the model");
        }
        return liveElementAt != null ? liveElementAt.apply(index)
            : elements.get(index);
    }

    /**
     * The ordered elements as an immutable snapshot in allocation order.
     *
     */
    public List<V> elements() {
        if (liveElementAt == null) {
            return elements;
        }
        int size = size();
        List<V> snapshot = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            snapshot.add(liveElementAt.apply(i));
        }
        return List.copyOf(snapshot);
    }
}
