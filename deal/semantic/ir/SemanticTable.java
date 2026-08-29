package deal.semantic.ir;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The closed first-insertion-order table of the semantic value model
 * (ISSUE-0383 component C2; ISSUE-0232 design D4, parent D8): the
 * order primitive the {@code ContainerOpsExecutor} (C3), the semantic
 * oracle (E11), and the future {@code table.keys} contract consume.
 *
 * <p><b>Closed order contract (D4, exact).</b>
 * <ul>
 *   <li>{@link #put(String, Object)} — a new key appends a slot at the
 *       end; an existing present key replaces the value in place and
 *       keeps its slot (overwrite never moves a key).</li>
 *   <li>{@link #remove(String)} — deletes the slot entirely; a later
 *       {@code put} of a deleted key appends a fresh slot at the end
 *       (delete+reinsert moves the key to the end). Removing an absent
 *       key is a no-op ({@code docs/spec-v1.2.md:1243-1346}: delete
 *       removes the field; null is distinct from absence).</li>
 *   <li>{@link #get(String)} — {@code Present} with the stored value or
 *       {@code Missing} (the schema's internal {@code missing},
 *       {@link ActualKind#MISSING}). A present null value (the closed
 *       value type's explicit null variant, e.g.
 *       {@link ScalarValue.Null}) is {@code Present} and is therefore
 *       distinguishable from the {@code Missing} outcome of an absent
 *       key.</li>
 *   <li>{@link #keys()} — the string keys in first-insertion order. No
 *       lexical or hash ordering exists anywhere in the model: the only
 *       observable order is insertion order.</li>
 * </ul>
 *
 * <p>{@code TABLE_NEW} executes {@code put} per entry in source order, so
 * duplicate literal keys keep the first source position and the last
 * value (D4; {@code docs/spec-v1.2.md:2927-2937} item 7). The
 * write/delete address chains ({@code ASSIGN}/{@code DELETE} with
 * {@code MEMBER_WRITE}/{@code INDEX_WRITE} commit ops) that drive
 * {@code put}/{@code remove} are E5's (ISSUE-0234) and are excluded
 * here.</p>
 *
 * <p><b>Null discipline.</b> Neither the key nor the value is ever the
 * Java {@code null} reference: language null is the closed value type's
 * explicit null variant — never a raw reference — so the {@code Missing}
 * outcome is unambiguous. Null arguments are producer defects
 * ({@link NullPointerException}, fail closed, never a DEAL projection).
 * Keys are strings (identifier property names).</p>
 *
 * <p><b>Purity, identity, and bounds.</b> Deterministic: no randomness,
 * no host code, no I/O, no {@code deal.types} dependency. Order storage
 * is linear in the number of present keys (parallel slot arrays plus one
 * key index). A fresh instance carries a fresh identity (object
 * identity, observable by {@code REFERENCE_EQ}); no pooling or interning
 * exists. {@code size()} is the signed32 present-key count.</p>
 *
 * @param <V> the closed slot value type of the consumer (the executor's
 *            resolved prior-step value, the oracle's heap value); never
 *            the Java {@code null} reference
 */
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
     * @param <V> the slot value type
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
     * @param key   the string key; non-null
     * @param value the slot value; non-null (language null is the closed
     *              value type's explicit null variant)
     * @throws NullPointerException if {@code key} or {@code value} is null
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
     * @param key the string key; non-null
     * @throws NullPointerException if {@code key} is null
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
     * @param key the string key; non-null
     * @return the closed lookup outcome; deterministic
     * @throws NullPointerException if {@code key} is null
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
     * @return an immutable snapshot of the keys in first-insertion order
     */
    public List<String> keys() {
        return List.copyOf(keys);
    }

    /**
     * The signed32 present-key count (the number of slots).
     *
     * @return the present-key count, always {@code >= 0}
     */
    public int size() {
        return keys.size();
    }
}
