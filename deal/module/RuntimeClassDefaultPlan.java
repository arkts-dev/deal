package deal.module;

import deal.identity.CanonicalClassIdentity;

import java.util.List;
import java.util.Objects;

/**
 * The parent-pinned runtime-side class default plan shape (design
 * source {@code deal-v1.2-int32-and-bytes-architecture} D5, adopted
 * verbatim; carrier-shape domain {@code default-plan-carriers} D2/D5):
 *
 * <pre>
 * RuntimeClassDefaultPlan(classIdentity,
 *   orderedFields: List&lt;RuntimeClassDefaultEntry&gt;)
 * </pre>
 *
 * <ul>
 *   <li>{@code classIdentity} — the class's canonical public identity
 *       ({@code deal.identity.CanonicalClassIdentity}).</li>
 *   <li>{@code orderedFields} — the field entries in class source order
 *       with unique names; the list is insertion-ordered and
 *       unmodifiable, and duplicate names are rejected at
 *       construction.</li>
 * </ul>
 *
 * <p><b>Producer obligations, documented here and never validated
 * ({@code default-plan-carriers} D5):</b> {@code orderedFields} is in
 * class source order with unique names; each entry carries the runtime
 * presence rule enforced by {@link RuntimeClassDefaultEntry} itself.
 * The lowering epic (ISSUE-0544) constructs runtime plans from the
 * published compiler plans; the construction-consumption epic
 * (ISSUE-0545) consumes them through the pinned four normal phases and
 * the JSON phases 0-6. The carrier enforces duplicate-name rejection
 * only.</p>
 *
 * <p>Immutable and deterministic; identities and descriptors are
 * compiler-internal and never appear in runtime descriptors, diagnostic
 * type names, public export keys, or source-language values.</p>
 *
 * @param classIdentity the class's canonical public identity
 * @param orderedFields the field entries in class source order with
 *                      unique names
 */
public record RuntimeClassDefaultPlan(
    CanonicalClassIdentity classIdentity,
    List<RuntimeClassDefaultEntry> orderedFields
) {

    public RuntimeClassDefaultPlan {
        Objects.requireNonNull(classIdentity, "classIdentity");
        orderedFields =
            CarrierCollections.orderedListCopy(orderedFields, "orderedFields");
        CarrierCollections.rejectDuplicateNames(
            orderedFields.stream().map(RuntimeClassDefaultEntry::name).toList());
    }
}
