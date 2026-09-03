package deal.ffi;

import java.util.List;
import java.util.Objects;

/**
 * The immutable compiler-side default plan of one extern-C
 * {@code C_STRUCT} class (design source
 * {@code deal-v1.2-directives-and-c-ffi-declarations} D4/D7, the
 * int32/bytes page D5 plan contract):
 *
 * <pre>
 * FfiCompilerClassDefaultPlan(classIdentityText, orderedEntries,
 *   canonicalPlanContent, semanticDefaultContents,
 *   evaluatorImplementationContents, planDigest)
 * </pre>
 *
 * <p>The plan is published only after every default resolves and
 * validates, it is retained — never invoked — during generation and
 * load planning (deferred evaluators), and its three content strings
 * participate byte-exactly in the runtime initialization identity
 * ({@code luajit-ffi-generated-content-seam} S5). Every behavior-bearing
 * input — field names/descriptors/optionality, default-expression
 * structure, literals, operators, resolved provider identities and
 * contract digests, and behavior-affecting source ranges — changes at
 * least one content string and therefore the derived identity.</p>
 *
 * <p><b>Privacy:</b> private semantic identity URIs never appear in any
 * content string: they participate through deterministic SHA-256 digests
 * only, so the retained (and later serialized) plan never exposes
 * deployment paths.</p>
 *
 * @param classIdentityText             the class's canonical descriptor
 *                                      identity text (the plan-map key)
 * @param entries                       the ordered field entries in class
 *                                      source order
 * @param canonicalPlanContent          the complete canonical plan
 *                                      serialization (CanonicalJson)
 * @param semanticDefaultContents       the canonical semantic-default
 *                                      content (identity digests, provider
 *                                      digests, descriptors, ranges)
 * @param evaluatorImplementationContents the canonical evaluator
 *                                      implementation content
 *                                      (per-entry deferred evaluator
 *                                      serializations)
 * @param planDigest                    SHA-256 over
 *                                      {@code canonicalPlanContent}
 *                                      (an index only)
 */
public record FfiCompilerClassDefaultPlan(
    String classIdentityText,
    List<Entry> entries,
    String canonicalPlanContent,
    String semanticDefaultContents,
    String evaluatorImplementationContents,
    String planDigest) {

    public FfiCompilerClassDefaultPlan {
        Objects.requireNonNull(classIdentityText, "classIdentityText");
        Objects.requireNonNull(canonicalPlanContent, "canonicalPlanContent");
        Objects.requireNonNull(semanticDefaultContents,
            "semanticDefaultContents");
        Objects.requireNonNull(evaluatorImplementationContents,
            "evaluatorImplementationContents");
        Objects.requireNonNull(planDigest, "planDigest");
        entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
    }

    /**
     * One ordered plan entry: the runtime-shape fields (name, canonical
     * descriptor, optional flag, deferred-evaluator presence) plus the
     * canonical deferred evaluator content the lowering child consumes.
     * No evaluator is ever invoked by this record.
     *
     * @param name               the field name exactly as declared
     * @param canonicalDescriptor the field's canonical runtime descriptor
     * @param optional           true iff the field is optional (for
     *                           extern-C structs always false — optional
     *                           fields are E7002)
     * @param hasDefaultEvaluator true iff the field carries a deferred
     *                           default evaluator
     * @param evaluatorContent   the canonical deferred evaluator content,
     *                           null exactly when
     *                           {@code hasDefaultEvaluator} is false
     */
    public record Entry(String name, String canonicalDescriptor,
                        boolean optional, boolean hasDefaultEvaluator,
                        String evaluatorContent) {

        public Entry {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(canonicalDescriptor,
                "canonicalDescriptor");
            if (name.isEmpty()) {
                throw new IllegalArgumentException("name must not be empty");
            }
            if (hasDefaultEvaluator && evaluatorContent == null) {
                throw new IllegalArgumentException(
                    "hasDefaultEvaluator requires evaluatorContent");
            }
            if (!hasDefaultEvaluator && evaluatorContent != null) {
                throw new IllegalArgumentException(
                    "evaluatorContent requires hasDefaultEvaluator");
            }
        }
    }
}
