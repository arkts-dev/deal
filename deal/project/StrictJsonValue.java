package deal.project;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A strictly decoded JSON value.
 *
 * <p>This value model is the carrier for the spec-reserved
 * {@code dependencies} contents of {@link ProjectManifest}, whose contents
 * are preserved opaquely. Every published value comes from
 * {@link StrictManifestParser}'s strict walk: strings are strictly
 * re-scanned and decoded with the pinned escape set
 * <code>" \ / b f n r t uXXXX</code> and true Unicode decoding, numbers
 * are strictly re-validated against the JSON grammar
 * {@code -?(0|[1-9][0-9]*)(\.[0-9]+)?([eE][+-]?[0-9]+)?} (their exact
 * source text is preserved losslessly), and literals are exactly
 * {@code true}/{@code false}/{@code null}. The permissive decoded values
 * of the {@code deal.source.JsonRangeLexer} substrate are never used.
 *
 * <p>Object member maps preserve document member order; arrays preserve
 * element order. All instances are immutable.
 */
public sealed interface StrictJsonValue
    permits StrictJsonValue.StringVal, StrictJsonValue.NumberVal,
            StrictJsonValue.BoolVal, StrictJsonValue.NullVal,
            StrictJsonValue.ArrayVal, StrictJsonValue.ObjectVal {

    /** A strictly decoded string value. */
    record StringVal(String value) implements StrictJsonValue {
    }

    /**
     * A strict-grammar-validated number, preserved as its exact source
     * text (lossless; no long/double decoding is applied).
     */
    record NumberVal(String sourceText) implements StrictJsonValue {
    }

    /** A strict JSON boolean. */
    record BoolVal(boolean value) implements StrictJsonValue {
    }

    /** The strict JSON {@code null}. */
    record NullVal() implements StrictJsonValue {
    }

    /** A strict JSON array in element order. */
    record ArrayVal(List<StrictJsonValue> elements) implements StrictJsonValue {
        public ArrayVal {
            elements = List.copyOf(elements);
        }
    }

    /** A strict JSON object in document member order. */
    record ObjectVal(Map<String, StrictJsonValue> members) implements StrictJsonValue {
        public ObjectVal {
            members = Collections.unmodifiableMap(new LinkedHashMap<>(members));
        }
    }
}
