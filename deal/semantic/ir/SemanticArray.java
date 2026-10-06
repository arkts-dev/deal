package deal.semantic.ir;

import java.util.List;
import java.util.Objects;

public final class SemanticArray<V> {

    /** A producer defect: an element access outside {@code [0, size)}. */
    public static final class Defect extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public Defect(String message) {
            super(message);
        }
    }

    /** The ordered elements; immutable and fixed at allocation. */
    private final List<V> elements;

    private SemanticArray(List<V> elements) {
        this.elements = elements;
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
     * The signed32 element count.
     *
     */
    public int size() {
        return elements.size();
    }

    /**
     * Ordered element access: the element at {@code index} (0-based, in
     * allocation order).
     *
     */
    public V elementAt(int index) {
        if (index < 0 || index >= elements.size()) {
            throw new Defect("array element access outside [0, size): index " + index
                + ", size " + elements.size() + " — the executor applies the pinned "
                + "bounds policy before accessing the model");
        }
        return elements.get(index);
    }

    /**
     * The ordered elements as an immutable snapshot in allocation order.
     *
     */
    public List<V> elements() {
        return elements;
    }
}
