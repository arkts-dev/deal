package deal.semantic.ir;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The JSON default-child production record of
 * {@code deal.semantic-ir/1} (class-construction-jsonable-operations
 * K-D8/K-D11; ISSUE-0515): one table per lowered unit recording, for
 * every {@code @jsonable} class's {@code JSON_FROM_CLASS} op, exactly
 * one per-site {@code CLASS_DEFAULT} child op per defaulted field in
 * declaration order.
 *
 * <pre>{@code
 * JsonDefaultChildTable {
 *   defaultChildren: Map<OpId, List<OpId>>
 *     // JSON_FROM_CLASS op id -> CLASS_DEFAULT child op ids (declaration order)
 * }
 * }</pre>
 *
 * <p><b>Pinned production shape (K-D8 step 5).</b> The
 * {@code JSON_FROM_CLASS} schema payload carries no child list (the
 * payload records only the layout and the JSON string), so the walk's
 * per-site {@code CLASS_DEFAULT} children — the same detached
 * {@code CLASS_DEFAULT} ops the declaration arm emitted for the class
 * (K-D3) — are recorded in this production record carried alongside the
 * unit (the {@link StructuredBodyTable}/
 * {@link ClassFactoryRegistry} precedent). The listed children are the
 * required-present defaulted fields' ops in declaration order: an
 * omitted optional-with-default field stays missing and its default
 * never runs, so its op id never enters the list (the
 * {@code CLASS_NEW(LOCAL)} {@code classDefaultOpIds} rule, K-D4 step 2).
 * At execution the walk skips any child whose field is present in the
 * JSON document (K-D8: defaults apply to omitted required-present fields
 * only), and an absent required-present field without a declared default
 * is a field failure returning language null (K-D9 — no per-type
 * reference defaults are invented). Nested-class defaults never enter
 * this table: a nested decode triggers the nested class's own
 * {@code CLASS_FACTORY} (K-D8 step 6, K-D5 trigger (b)).</p>
 *
 * <p><b>Ownership.</b> This is construct-epic production data — no
 * schema-owned record changes and the foundation validator's closed
 * 14-condition rule set is untouched. The production validator's
 * pinned check (K-D11: exactly one {@code CLASS_DEFAULT} child per
 * defaulted field in declaration order) consumes this record; the
 * record itself admits any binding and rejects nothing. The emission
 * carrier that hands the unit plus this record to the shared emitters
 * is E10's seam; the record and its contract are this epic's.</p>
 *
 * <p>Immutability: the map is defensively copied into an
 * insertion-ordered unmodifiable map and every per-op child list is
 * copied, so later mutation of the constructor argument cannot change
 * the record. Map iteration order (the producer's declaration order) is
 * the deterministic traversal order.</p>
 *
 * @param defaultChildren the {@code JSON_FROM_CLASS} op id to child op
 *                        id list bindings; non-null (keys, values, and
 *                        listed ops must be non-null)
 */
public record JsonDefaultChildTable(Map<OpId, List<OpId>> defaultChildren) {

    public JsonDefaultChildTable {
        Objects.requireNonNull(defaultChildren, "defaultChildren must not be null");
        Map<OpId, List<OpId>> copied = new LinkedHashMap<>();
        for (Map.Entry<OpId, List<OpId>> entry : defaultChildren.entrySet()) {
            Objects.requireNonNull(entry.getKey(), "defaultChildren keys must not be null");
            Objects.requireNonNull(entry.getValue(),
                "defaultChildren values must not be null");
            copied.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        defaultChildren = Collections.unmodifiableMap(copied);
    }

    /**
     * The recorded per-site {@code CLASS_DEFAULT} child op ids of the
     * given {@code JSON_FROM_CLASS} op in declaration order, or
     * {@code null} when the op has no recorded children (the op is not
     * a {@code @jsonable} class's {@code JSON_FROM_CLASS} op, or the
     * class has no required-present defaulted fields).
     *
     * @param jsonFromClassOpId the {@code JSON_FROM_CLASS} op id;
     *                          non-null
     * @return the child op id list in declaration order, or {@code null}
     */
    public List<OpId> childrenOf(OpId jsonFromClassOpId) {
        return defaultChildren.get(Objects.requireNonNull(jsonFromClassOpId,
            "jsonFromClassOpId must not be null"));
    }
}
