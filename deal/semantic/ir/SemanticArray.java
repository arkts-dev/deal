package deal.semantic.ir;

import java.util.List;
import java.util.Objects;

/**
 * The closed ordered array of the semantic value model (ISSUE-0383
 * component C2; ISSUE-0232 design D6): the array value the
 * {@code ContainerOpsExecutor} (C3) uses for
 * {@code ARRAY_NEW}/{@code ARRAY_LENGTH} and the semantic oracle (E11)
 * composes.
 *
 * <p><b>Closed shape.</b> An ordered element list with the signed32
 * element count ({@link #size()}) and ordered element access
 * ({@link #elementAt(int)}); elements are fixed at allocation, so
 * element order is immutable for the array's lifetime. Arrays are
 * 0-based ({@code docs/spec-v1.2.md:1348-1417}). Every factory call
 * allocates a fresh array with a fresh identity (object identity,
 * observable by {@code REFERENCE_EQ}); no pooling or interning exists,
 * so two allocations with equal content are distinct references.</p>
 *
 * <p><b>No bounds or descriptor enforcement.</b> The model projects no
 * DEAL-visible policy: the pinned array bounds (E8002) and element
 * descriptor (E8003) projections are the executor's and the boundary
 * policy's (C3/E4). An out-of-range access reaching the model is a
 * producer defect ({@link Defect} — internal control flow, fail closed,
 * never a DEAL projection and never a crash): the executor applies its
 * bounds policy before accessing the model.</p>
 *
 * <p><b>Null discipline.</b> Element values are never the Java
 * {@code null} reference (language null is the closed value type's
 * explicit null variant), so a null element or a null list argument is a
 * producer defect ({@link NullPointerException}, fail closed).</p>
 *
 * <p><b>Purity and bounds.</b> Deterministic: no randomness, no host
 * code, no I/O, no {@code deal.types} dependency. Storage is linear in
 * the element count. The element value type {@code V} is the closed
 * value representation of the consumer, exactly as for
 * {@link SemanticTable}.</p>
 *
 * @param <V> the closed element value type; never the Java {@code null}
 *            reference
 */
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
     * @param elements the ordered elements; non-null, no null elements
     * @return a fresh array value; each call has a fresh identity
     * @throws NullPointerException if {@code elements} is null or contains
     *                              a null element
     */
    public static <V> SemanticArray<V> of(List<V> elements) {
        Objects.requireNonNull(elements, "elements must not be null");
        return new SemanticArray<>(List.copyOf(elements));
    }

    /**
     * Allocates a fresh array with a fresh identity holding the given
     * elements in the given order.
     *
     * @param elements the ordered elements; non-null, no null elements
     * @return a fresh array value; each call has a fresh identity
     * @throws NullPointerException if {@code elements} is null or contains
     *                              a null element
     */
    @SafeVarargs
    public static <V> SemanticArray<V> of(V... elements) {
        Objects.requireNonNull(elements, "elements must not be null");
        return new SemanticArray<>(List.of(elements));
    }

    /**
     * The signed32 element count.
     *
     * @return the element count, always {@code >= 0}
     */
    public int size() {
        return elements.size();
    }

    /**
     * Ordered element access: the element at {@code index} (0-based, in
     * allocation order).
     *
     * @param index the 0-based element index; in {@code [0, size)}
     * @return the element at {@code index}
     * @throws Defect if {@code index} is outside {@code [0, size)} — a
     *                producer defect, never a DEAL projection (the model
     *                performs no bounds enforcement; the executor applies
     *                the pinned bounds policy before access)
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
     * @return an immutable list of the elements in order
     */
    public List<V> elements() {
        return elements;
    }
}
