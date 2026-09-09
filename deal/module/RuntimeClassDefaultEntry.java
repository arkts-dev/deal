package deal.module;

import java.util.Objects;

/**
 * The parent-pinned runtime-side plan entry shape (design source
 * {@code deal-v1.2-int32-and-bytes-architecture} D5, adopted verbatim;
 * carrier-shape domain {@code default-plan-carriers} D2/D4;
 * {@code runtime-default-evaluators-and-construction-phases} D1):
 *
 * <pre>
 * RuntimeClassDefaultEntry(name, runtimeTypeDescriptor, optional,
 *   defaultEvaluator?)
 * </pre>
 *
 * <p>The runtime ABI entry shape is {@code {name, descriptor, optional,
 * evaluator}} ({@code runtime-default-evaluators-and-construction-phases}
 * D1; the Lua plan entry consumed by {@code __rt.class_plan_} in
 * {@code deal/runtime.lua}):</p>
 *
 * <ul>
 *   <li>{@code name} — the field name exactly as declared; non-null,
 *       non-empty.</li>
 *   <li>{@code runtimeTypeDescriptor} — the field's canonical runtime
 *       descriptor text (the
 *       {@link deal.descriptors.CanonicalRuntimeTypeDescriptor}
 *       encoding); supplied by the lowering stage that constructs the
 *       entry (ISSUE-0544).</li>
 *   <li>{@code optional} — the separate optional flag of the declared
 *       field form.</li>
 *   <li>{@code defaultEvaluator} — present exactly on required-present
 *       entries and never on optional entries, enforced at construction
 *       as an XOR invariant with {@code optional} (see below).</li>
 * </ul>
 *
 * <p><b>Presence rule (enforced here; {@code runtime-default-evaluators-and-construction-phases}
 * D1):</b> {@code defaultEvaluator} is present exactly on
 * required-present entries and never on optional entries. A
 * required-present entry without an evaluator, or an optional entry
 * with one, is rejected at construction with an
 * {@link IllegalArgumentException} naming the entry. The compiler-side
 * declaration gate — E4001 at the field declaration range — makes the
 * rejected shapes unreachable in the production pipeline, and the
 * runtime's defensive evaluator-less required-entry skip stays
 * defensive.</p>
 *
 * <p>Immutable and deterministic; descriptors and digests are
 * compiler-internal and never appear in runtime descriptors (as
 * decoded inputs they are descriptor text by contract), diagnostic type
 * names, public export keys, or source-language values.</p>
 *
 * @param name                 the field name exactly as declared
 * @param runtimeTypeDescriptor the field's canonical runtime descriptor
 *                             text
 * @param optional             true iff the declared field is optional
 * @param defaultEvaluator     the default evaluator — present exactly
 *                             when {@code optional} is false
 */
public record RuntimeClassDefaultEntry(
    String name,
    String runtimeTypeDescriptor,
    boolean optional,
    RuntimeDefaultEvaluator defaultEvaluator
) {

    public RuntimeClassDefaultEntry {
        Objects.requireNonNull(name, "name");
        if (name.isEmpty()) {
            throw new IllegalArgumentException("name must not be empty");
        }
        Objects.requireNonNull(runtimeTypeDescriptor, "runtimeTypeDescriptor");
        if (optional && defaultEvaluator != null) {
            throw new IllegalArgumentException(
                "field '" + name
                    + "' is optional but carries a default evaluator"
                    + " (an optional entry must never carry one)");
        }
        if (!optional && defaultEvaluator == null) {
            throw new IllegalArgumentException(
                "field '" + name
                    + "' is required-present but carries no default"
                    + " evaluator (a required-present entry must carry one)");
        }
    }
}
