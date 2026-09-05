package deal.semantic.ir;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The factory-registration production record of
 * {@code deal.semantic-ir/1} (class-construction-jsonable-operations
 * K-D2; ISSUE-0511): one table per lowered unit recording, for each
 * exported class, the {@code CLASS_FACTORY} op registered under the
 * pre-allocated {@link ClassInterface#constructionEntry()}
 * {@link ClassFactoryId}.
 *
 * <pre>{@code
 * ClassFactoryRegistry {
 *   factories: Map<ClassFactoryId, OpId>   // constructionEntry -> CLASS_FACTORY op
 * }
 * }</pre>
 *
 * <p><b>Pinned production shape (K-D2).</b> The lowerer's
 * {@code ClassDeclaration} arm emits, for each exported class, exactly
 * one {@code CLASS_FACTORY} op registered under the deterministic
 * route-independent {@code constructionEntry} id allocated at index-build
 * time (foundation F2); non-exported classes get a layout but never a
 * factory and never a {@code ClassFactoryId} — their construction is
 * always {@code LOCAL}. The registry is the inert id&#8594;op binding
 * carrier the lowerer produces with the unit (the
 * {@link FunctionBindingRegistry}/{@link StructuredBodyTable} precedent):
 * consumers resolve the owner module's factory op by
 * {@code ClassFactoryId} without inference (parent D3), and the
 * factory&#8596;{@code constructionEntry} bijection for exported classes
 * is the production validator's check, never a construction check.</p>
 *
 * <p><b>Ownership.</b> This is construct-epic production data — no
 * schema-owned record changes and the foundation validator's closed
 * 14-condition rule set is untouched. The emission carrier that hands
 * the unit plus this record (and the block-membership table) to the
 * shared emitters is the later epic's seam; the record and its contract
 * are this epic's.</p>
 *
 * <p>Immutability: the map is defensively copied into an
 * insertion-ordered unmodifiable map, so later mutation of the
 * constructor argument cannot change the record. Map iteration order
 * (the producer's declaration order) is the deterministic traversal
 * order.</p>
 *
 * @param factories the constructionEntry-to-factory bindings; non-null
 *                  (keys and values must be non-null)
 */
public record ClassFactoryRegistry(Map<ClassFactoryId, OpId> factories) {

    public ClassFactoryRegistry {
        Objects.requireNonNull(factories, "factories must not be null");
        Map<ClassFactoryId, OpId> copied = new LinkedHashMap<>();
        for (Map.Entry<ClassFactoryId, OpId> entry : factories.entrySet()) {
            Objects.requireNonNull(entry.getKey(), "factories keys must not be null");
            Objects.requireNonNull(entry.getValue(), "factories values must not be null");
            copied.put(entry.getKey(), entry.getValue());
        }
        factories = Collections.unmodifiableMap(copied);
    }

    /**
     * The registered {@code CLASS_FACTORY} op id of the given
     * construction entry, or {@code null} when the id has no factory
     * (a non-exported class is never registered).
     *
     * @param constructionEntry the pre-allocated construction entry;
     *                          non-null
     * @return the factory op id, or {@code null}
     */
    public OpId factoryFor(ClassFactoryId constructionEntry) {
        return factories.get(Objects.requireNonNull(constructionEntry,
            "constructionEntry must not be null"));
    }
}
