package deal.module;

import deal.checker.Symbol;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Package-private construction helpers for the default-plan carrier
 * records (the carrier-shape domain of ISSUE-0540). Not part of the
 * public carrier surface; every helper produces defensively immutable,
 * insertion-ordered collections and fails fast on nulls and duplicates.
 */
final class CarrierCollections {

    private CarrierCollections() {
    }

    /**
     * Copies the input collection into an unmodifiable list, preserving
     * the input's own iteration order. Rejects a null input and null
     * elements.
     */
    static <T> List<T> orderedListCopy(Collection<T> input, String fieldName) {
        Objects.requireNonNull(input, fieldName);
        List<T> copy = new ArrayList<>(input.size());
        for (T element : input) {
            if (element == null) {
                throw new NullPointerException(fieldName + " contains a null element");
            }
            copy.add(element);
        }
        return List.copyOf(copy);
    }

    /**
     * Copies the input collection into an unmodifiable insertion-ordered
     * set, iterating the input in its own iteration order (so a List
     * input keeps list order and a LinkedHashSet input keeps its
     * insertion order). Rejects a null input, null elements, and
     * duplicate elements (structural record equality).
     */
    static <T> Set<T> orderedSetCopy(Collection<T> input, String fieldName) {
        Objects.requireNonNull(input, fieldName);
        LinkedHashSet<T> copy = new LinkedHashSet<>();
        for (T element : input) {
            if (element == null) {
                throw new NullPointerException(fieldName + " contains a null element");
            }
            if (!copy.add(element)) {
                throw new IllegalArgumentException(
                    fieldName + " contains a duplicate element: " + element);
            }
        }
        return Collections.unmodifiableSet(copy);
    }

    /**
     * Copies the input map into an unmodifiable insertion-ordered map
     * (the input's own iteration order). Rejects a null input, null
     * keys, and null values. A repeated key keeps its first insertion
     * position with the later value (the JDK {@link LinkedHashMap} put
     * contract); the exact key semantics are owned by the planner epic.
     */
    static Map<String, Symbol> bindingsCopy(Map<String, Symbol> input,
                                            String fieldName) {
        Objects.requireNonNull(input, fieldName);
        LinkedHashMap<String, Symbol> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Symbol> entry : input.entrySet()) {
            String key = entry.getKey();
            if (key == null) {
                throw new NullPointerException(fieldName + " contains a null key");
            }
            if (entry.getValue() == null) {
                throw new NullPointerException(
                    fieldName + " contains a null value for key '" + key + "'");
            }
            copy.put(key, entry.getValue());
        }
        return Collections.unmodifiableMap(copy);
    }

    /**
     * Rejects a duplicate entry name (both plan records pin unique
     * field names; {@code default-plan-carriers} D5).
     */
    static void rejectDuplicateNames(Collection<String> names) {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (String name : names) {
            if (!seen.add(name)) {
                throw new IllegalArgumentException(
                    "duplicate field name '" + name
                        + "' (orderedFields must have unique names)");
            }
        }
    }
}
