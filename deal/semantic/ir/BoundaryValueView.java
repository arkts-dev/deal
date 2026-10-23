package deal.semantic.ir;

import java.util.AbstractList;
import java.util.List;
import java.util.Objects;
import java.util.function.IntFunction;

public record BoundaryValueView(
    ActualKind kind,
    String classId,
    Double numberValue,
    RuntimeDescriptor.Func functionSignature,
    List<BoundaryValueView> elements,
    boolean decodedArrayMark,
    UnicodeScalars.Defect unicodeDefect
) {

    /**
     * The classification-only view constructor: every kind but
     * {@code INVALID_UNICODE} carries no encoding defect, and an invalid
     * Unicode view without an explicit class is the malformed
     * (non-surrogate) class.
     */
    public BoundaryValueView(ActualKind kind, String classId, Double numberValue,
                             RuntimeDescriptor.Func functionSignature,
                             List<BoundaryValueView> elements,
                             boolean decodedArrayMark) {
        this(kind, classId, numberValue, functionSignature, elements, decodedArrayMark,
            kind == ActualKind.INVALID_UNICODE
                ? UnicodeScalars.Defect.MALFORMED : null);
    }

    public BoundaryValueView {
        Objects.requireNonNull(kind, "kind must not be null");
        switch (kind) {
            case CLASS -> {
                if (classId == null || classId.isEmpty()) {
                    throw new IllegalArgumentException(
                        "a CLASS view carries the canonical class-id atom text "
                            + "(@modulePath/ClassName); classId must be non-null and non-empty");
                }
            }
            case NUMBER -> {
                if (numberValue == null) {
                    throw new IllegalArgumentException(
                        "a NUMBER view must carry its IEEE-754 value in numberValue");
                }
            }
            case FUNCTION -> {
                if (functionSignature == null) {
                    throw new IllegalArgumentException(
                        "a FUNCTION view must carry its signature in functionSignature");
                }
            }
            case ARRAY -> {
                if (elements == null) {
                    throw new IllegalArgumentException(
                        "an ARRAY view must carry its element views in elements");
                }
            }
            default -> {
                // NULL, MISSING, BOOLEAN, STRING, TABLE,
                // ASYNC_OPERATION, NOTHING: no payload field.
                // An INVALID_UNICODE view carries its closed encoding
                // defect class in unicodeDefect instead.
            }
        }
        if (kind != ActualKind.CLASS && classId != null) {
            throw new IllegalArgumentException(
                "only a CLASS view carries a class id (a non-class actual kind never "
                    + "carries a class name)");
        }
        if (kind != ActualKind.NUMBER && numberValue != null) {
            throw new IllegalArgumentException(
                "only a NUMBER view carries a numeric value");
        }
        if (kind != ActualKind.FUNCTION && functionSignature != null) {
            throw new IllegalArgumentException(
                "only a FUNCTION view carries a function signature");
        }
        if (kind != ActualKind.ARRAY && elements != null) {
            throw new IllegalArgumentException("only an ARRAY view carries element views");
        }
        if (decodedArrayMark && kind != ActualKind.ARRAY) {
            throw new IllegalArgumentException(
                "the decoded-array mark is legal only on an ARRAY view (a "
                    + "non-ARRAY view carrying it is a fail-closed producer defect)");
        }
        if (kind != ActualKind.INVALID_UNICODE && unicodeDefect != null) {
            throw new IllegalArgumentException(
                "only an INVALID_UNICODE view carries an encoding defect; a "
                    + kind + " view with a defect is a fail-closed producer defect");
        }
        if (kind == ActualKind.INVALID_UNICODE && unicodeDefect == null) {
            throw new IllegalArgumentException(
                "an INVALID_UNICODE view names its closed encoding defect "
                    + "(the two pinned host string-carrier reasons); use "
                    + "ofInvalidUnicode(Defect)");
        }
        if (elements != null && !(elements instanceof LazyElementViews)) {
            // A lazily materialized element list is already immutable by
            // construction: copying it here would materialize every element
            // view the boundary check has not reached yet.
            elements = List.copyOf(elements);
        }
    }

    /**
     * The lazily materialized element views of one ARRAY view: element i's
     * view is produced on the first read of index i and memoized, so a
     * descriptor-directed boundary walk converts exactly the elements it
     * inspects. A later or wrong-kind subtree the declared descriptor never
     * descends into is never converted: a reference-cyclic carrier
     * terminates through the descriptor's finite depth, and the canonical
     * first-failing-element rule decides the tuple before any later element
     * is visited. Identity equality and hashing keep record comparison from
     * materializing (or recursing through) the list.
     */
    private static final class LazyElementViews extends AbstractList<BoundaryValueView> {

        private final int size;
        private final IntFunction<BoundaryValueView> producer;
        private final BoundaryValueView[] cache;

        LazyElementViews(int size, IntFunction<BoundaryValueView> producer) {
            if (size < 0) {
                throw new IllegalArgumentException(
                    "an ARRAY view's element count must be non-negative, got " + size);
            }
            this.size = size;
            this.producer = Objects.requireNonNull(producer,
                "the lazy element-view producer must not be null");
            this.cache = new BoundaryValueView[size];
        }

        @Override
        public BoundaryValueView get(int index) {
            Objects.checkIndex(index, size);
            BoundaryValueView view = cache[index];
            if (view == null) {
                view = Objects.requireNonNull(producer.apply(index),
                    "the lazy element-view producer returned no view for index "
                        + index);
                cache[index] = view;
            }
            return view;
        }

        @Override
        public int size() {
            return size;
        }

        @Override
        public boolean equals(Object other) {
            return this == other;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(this);
        }
    }

    /** A payload-free view for {@code NULL}, {@code MISSING}, {@code BOOLEAN},
     *  {@code INT}, {@code STRING}, {@code TABLE},
     *  {@code ASYNC_OPERATION}, and {@code NOTHING}. An
     *  {@code INVALID_UNICODE} view always names its defect; use
     *  {@link #ofInvalidUnicode(UnicodeScalars.Defect)} — or this factory,
     *  which defaults that view to the malformed class. */
    public static BoundaryValueView of(ActualKind kind) {
        Objects.requireNonNull(kind, "kind must not be null");
        return switch (kind) {
            case NULL, MISSING, BOOLEAN, STRING, TABLE, ASYNC_OPERATION,
                 NOTHING, INVALID_UNICODE ->
                new BoundaryValueView(kind, null, null, null, null, false);
            case CLASS, NUMBER, FUNCTION, ARRAY ->
                throw new IllegalArgumentException(kind
                    + " carries a payload; use the payload-bearing factory");
        };
    }

    /**
     * An {@code INVALID_UNICODE} view carrying its closed encoding defect
     * class: {@link UnicodeScalars.Defect#SURROGATE} for a surrogate code
     * point sequence (the pinned UTF-16 surrogate code point reason) and
     * {@link UnicodeScalars.Defect#MALFORMED} for every other malformed
     * encoding (the pinned invalid UTF-8 encoding reason). The typed
     * boundary projection renders both classes as the single closed
     * {@code invalid-unicode} actual token; the host inner-reason render
     * selects the pinned text from this class.
     */
    public static BoundaryValueView ofInvalidUnicode(UnicodeScalars.Defect defect) {
        Objects.requireNonNull(defect, "defect must not be null");
        return new BoundaryValueView(ActualKind.INVALID_UNICODE, null, null, null, null,
            false, defect);
    }

    /**
     * An unmarked {@code ARRAY} view carrying its element views in index
     * order (a DEAL array literal, an array produced by any other op, or a
     * host-returned array).
     */
    public static BoundaryValueView ofArray(BoundaryValueView... elements) {
        Objects.requireNonNull(elements, "elements must not be null");
        return ofArray(List.of(elements));
    }

    /** {@link #ofArray(BoundaryValueView...)} over a list. */
    public static BoundaryValueView ofArray(List<BoundaryValueView> elements) {
        Objects.requireNonNull(elements, "elements must not be null");
        return new BoundaryValueView(ActualKind.ARRAY, null, null, null,
            List.copyOf(elements), false);
    }

    /**
     * A <em>decoded</em> {@code ARRAY} view (B3): the closed provenance mark
     * of the stdlib JSON decode sites. Its element views carry their own
     * marks, so every array of a decoded tree is marked at every depth.
     */
    public static BoundaryValueView ofDecodedArray(BoundaryValueView... elements) {
        Objects.requireNonNull(elements, "elements must not be null");
        return ofDecodedArray(List.of(elements));
    }

    /** {@link #ofDecodedArray(BoundaryValueView...)} over a list. */
    public static BoundaryValueView ofDecodedArray(
            List<BoundaryValueView> elements) {
        Objects.requireNonNull(elements, "elements must not be null");
        return new BoundaryValueView(ActualKind.ARRAY, null, null, null,
            List.copyOf(elements), true);
    }

    /** A {@code NUMBER} view carrying its IEEE-754 value (NaN/infinity included). */
    public static BoundaryValueView ofNumber(double value) {
        return new BoundaryValueView(ActualKind.NUMBER, null, value, null, null, false);
    }

    /**
     * A lazily materialized {@code ARRAY} view: element {@code i}'s view is
     * produced on the first read of that index and memoized, so the
     * boundary engine's descriptor-directed walk converts only the elements
     * it inspects (a subtree the declared descriptor never descends into is
     * never converted, and a reference-cyclic carrier terminates).
     */
    public static BoundaryValueView ofLazyArray(
            int size, IntFunction<BoundaryValueView> elementViews) {
        return new BoundaryValueView(ActualKind.ARRAY, null, null, null,
            new LazyElementViews(size, elementViews), false);
    }

    /** {@link #ofLazyArray(int, IntFunction)} carrying the decoded-array mark. */
    public static BoundaryValueView ofLazyDecodedArray(
            int size, IntFunction<BoundaryValueView> elementViews) {
        return new BoundaryValueView(ActualKind.ARRAY, null, null, null,
            new LazyElementViews(size, elementViews), true);
    }

    /** A {@code CLASS} view carrying the canonical {@code @modulePath/ClassName} atom text. */
    public static BoundaryValueView ofClass(String classId) {
        Objects.requireNonNull(classId, "classId must not be null");
        if (classId.isEmpty()) {
            throw new IllegalArgumentException("classId must not be empty");
        }
        return new BoundaryValueView(ActualKind.CLASS, classId, null, null, null, false);
    }

    /** A {@code FUNCTION} view carrying the value's actual signature. */
    public static BoundaryValueView ofFunction(RuntimeDescriptor.Func signature) {
        Objects.requireNonNull(signature, "signature must not be null");
        return new BoundaryValueView(ActualKind.FUNCTION, null, null, signature, null,
            false);
    }

    /** The language-null view ({@code ActualKind.NULL}). */
    public static BoundaryValueView nullView() {
        return of(ActualKind.NULL);
    }
}
