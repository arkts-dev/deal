package deal.semantic.ir;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

public final class SemanticTable<V> {

    /** The key of slot {@code i}; never contains an absent key. */
    private final ArrayList<String> keys = new ArrayList<>();

    /** The value of slot {@code i}; parallel to {@link #keys}. */
    private final ArrayList<V> values = new ArrayList<>();

    /** Present key → slot index; iteration order of this map is never observable. */
    private final Map<String, Integer> index = new HashMap<>();

    /**
     * The ordered keys of a live read-only view (null for a materialized
     * table): every read asks the backing state again, so the view stays
     * current after a commit to the backing carrier.
     */
    private final Supplier<List<String>> liveKeys;

    /**
     * The current present/missing outcome of one key of a live read-only
     * view (null for a materialized table).
     */
    private final Function<String, Lookup<V>> liveLookup;

    /** Allocates a fresh, empty table with a fresh identity. */
    public SemanticTable() {
        liveKeys = null;
        liveLookup = null;
    }

    private SemanticTable(Supplier<List<String>> liveKeys,
                          Function<String, Lookup<V>> liveLookup) {
        this.liveKeys = liveKeys;
        this.liveLookup = liveLookup;
    }

    /**
     * A read-only live view over opaque backing state: {@code keys} supplies
     * the ordered keys and {@code lookup} the present/missing outcome of one
     * key, both read from the backing state on every call. Identity and
     * current contents cross a representation boundary through this view: a
     * reference cycle in the backing state reads back as a reference cycle
     * in the view (the same view object for the same backing table), and a
     * commit to the backing state is observed by every alias. A live view is
     * never mutated: {@link #put}/{@link #remove} fail closed.
     */
    public static <V> SemanticTable<V> live(Supplier<List<String>> keys,
                                            Function<String, Lookup<V>> lookup) {
        Objects.requireNonNull(keys, "keys must not be null");
        Objects.requireNonNull(lookup, "lookup must not be null");
        return new SemanticTable<>(keys, lookup);
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
     * key replaces the value in place and keeps its slot. A live view
     * fails closed (its backing state owns the contents).
     *
     */
    public void put(String key, V value) {
        if (liveKeys != null) {
            throw new IllegalStateException("a live read-only table view never "
                + "mutates: its backing state owns the contents");
        }
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
        if (liveKeys != null) {
            throw new IllegalStateException("a live read-only table view never "
                + "mutates: its backing state owns the contents");
        }
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
        if (liveLookup != null) {
            Lookup<V> lookup = liveLookup.apply(key);
            Objects.requireNonNull(lookup, "a live table lookup must not be null");
            return lookup;
        }
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
        if (liveKeys != null) {
            return List.copyOf(liveKeys.get());
        }
        return List.copyOf(keys);
    }

    /**
     * The signed32 present-key count (the number of slots).
     *
     */
    public int size() {
        if (liveKeys != null) {
            return liveKeys.get().size();
        }
        return keys.size();
    }
}
