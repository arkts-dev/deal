package deal.semantic.ir;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class SemanticTable<V> {

    /** The key of slot {@code i}; never contains an absent key. */
    private final ArrayList<String> keys = new ArrayList<>();

    /** The value of slot {@code i}; parallel to {@link #keys}. */
    private final ArrayList<V> values = new ArrayList<>();

    /** Present key → slot index; iteration order of this map is never observable. */
    private final Map<String, Integer> index = new HashMap<>();

    /** Allocates a fresh, empty table with a fresh identity. */
    public SemanticTable() {
    }

    /**
     * The closed outcome of one {@link SemanticTable} key lookup:
     * {@code Present(value) | Missing}. A present null value (the closed
     * value type's explicit null variant) is {@code Present},
     * distinguishable from the {@code Missing} outcome of an absent key —
     * the internal {@code missing} of the schema
     * ({@link ActualKind#MISSING}), never a Java null reference.
     *
     */
    public sealed interface Lookup<V> permits Lookup.Present, Lookup.Missing {

        /** The key is present; {@code value} is its current slot value. */
        record Present<V>(V value) implements Lookup<V> {

            public Present {
                Objects.requireNonNull(value, "value must not be null");
            }
        }

        /** The key is absent (the schema's internal missing). */
        record Missing<V>() implements Lookup<V> {
        }
    }

    /**
     * Stores {@code value} under {@code key} with the closed order
     * contract: a new key appends a slot at the end; an existing present
     * key replaces the value in place and keeps its slot.
     *
     */
    public void put(String key, V value) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(value,
            "value must not be null (language null is the closed value type's "
                + "explicit null variant, never a raw reference)");
        Integer existing = index.get(key);
        if (existing != null) {
            values.set(existing, value);
            return;
        }
        index.put(key, keys.size());
        keys.add(key);
        values.add(value);
    }

    /**
     * Deletes the slot of {@code key} entirely. A later {@code put} of a
     * deleted key appends a fresh slot at the end (delete+reinsert moves
     * the key to the end). Removing an absent key is a no-op.
     *
     */
    public void remove(String key) {
        Objects.requireNonNull(key, "key must not be null");
        Integer slot = index.remove(key);
        if (slot == null) {
            return;
        }
        keys.remove(slot.intValue());
        values.remove(slot.intValue());
        // Shift the indices of every slot after the removed one so the
        // parallel arrays stay aligned; order of the remaining slots is
        // preserved exactly.
        for (int i = slot; i < keys.size(); i++) {
            index.put(keys.get(i), i);
        }
    }

    /**
     * Reads {@code key}: {@link Lookup.Present} with the stored value or
     * {@link Lookup.Missing} for an absent key. A present null value is
     * {@code Present} and never conflated with {@code Missing}.
     *
     */
    public Lookup<V> get(String key) {
        Objects.requireNonNull(key, "key must not be null");
        Integer slot = index.get(key);
        if (slot == null) {
            return new Lookup.Missing<>();
        }
        return new Lookup.Present<>(values.get(slot));
    }

    /**
     * The string keys in first-insertion order. The returned list is an
     * immutable snapshot (subsequent {@code put}/{@code remove} calls do
     * not change it). No lexical or hash ordering exists anywhere in the
     * model.
     *
     */
    public List<String> keys() {
        return List.copyOf(keys);
    }

    /**
     * The signed32 present-key count (the number of slots).
     *
     */
    public int size() {
        return keys.size();
    }
}
