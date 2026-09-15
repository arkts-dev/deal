package deal.module;

import deal.types.Type;

import java.util.Objects;

/**
 * The parent-pinned compiler-side plan entry shape (design source
 * {@code deal-v1.2-int32-and-bytes-architecture} D5, adopted verbatim;
 * carrier-shape domain {@code default-plan-carriers} D2/D4):
 *
 * <pre>
 * CompilerClassDefaultEntry(name, resolvedFieldType,
 *   runtimeTypeDescriptor, optional, defaultExpression?)
 * </pre>
 *
 * <ul>
 *   <li>{@code name} — the field name exactly as declared; non-null,
 *       non-empty.</li>
 *   <li>{@code resolvedFieldType} — the field's resolved type (the
 *       default type-checks against it; E3001 at the default range is
 *       the producer-side gate).</li>
 *   <li>{@code runtimeTypeDescriptor} — the field's canonical runtime
 *       descriptor text (the
 *       {@link deal.descriptors.CanonicalRuntimeTypeDescriptor}
 *       encoding); supplied by the entry-constructing stage (the
 *       planner epic, ISSUE-0541).</li>
 *   <li>{@code optional} — the separate optional flag of the declared
 *       field form.</li>
 *   <li>{@code defaultExpression} — present exactly when the field is
 *       required-present and never on an optional entry, enforced at
 *       construction as an XOR invariant with {@code optional} (see
 *       below).</li>
 * </ul>
 *
 * <p><b>Presence rule (enforced here; {@code provider-versioned-default-plans}
 * D2):</b> {@code defaultExpression} is present exactly when the field
 * is required-present. A required-present entry without a default
 * expression, or an optional entry with one, is rejected at
 * construction with an {@link IllegalArgumentException} naming the
 * entry. The declaration-shape gate — E4001 at the field declaration
 * range for a required-present field without a default in a plan-bearing
 * class — is the planner's producer-side gate that makes the rejected
 * shapes unreachable in the production pipeline; the optional side
 * reflects that a declared default on an optional field is
 * checker-validated metadata that never evaluates.</p>
 *
 * <p>Immutable and deterministic; identities and descriptors are
 * compiler-internal and never appear in runtime descriptors, diagnostic
 * type names, public export keys, or source-language values.</p>
 *
 * @param name                the field name exactly as declared
 * @param resolvedFieldType   the field's resolved type
 * @param runtimeTypeDescriptor the field's canonical runtime descriptor
 *                             text
 * @param optional            true iff the declared field is optional
 * @param defaultExpression   the resolved default expression — present
 *                            exactly when {@code optional} is false
 */
public record CompilerClassDefaultEntry(
    String name,
    Type resolvedFieldType,
    String runtimeTypeDescriptor,
    boolean optional,
    ResolvedDefaultExpression defaultExpression
) {

    public CompilerClassDefaultEntry {
        Objects.requireNonNull(name, "name");
        if (name.isEmpty()) {
            throw new IllegalArgumentException("name must not be empty");
        }
        Objects.requireNonNull(resolvedFieldType, "resolvedFieldType");
        Objects.requireNonNull(runtimeTypeDescriptor, "runtimeTypeDescriptor");
        if (optional && defaultExpression != null) {
            throw new IllegalArgumentException(
                "field '" + name
                    + "' is optional but carries a default expression"
                    + " (an optional entry must not carry one)");
        }
        if (!optional && defaultExpression == null) {
            throw new IllegalArgumentException(
                "field '" + name
                    + "' is required-present but carries no default"
                    + " expression (a required-present entry must carry one)");
        }
    }
}
