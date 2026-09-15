package deal.module;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The backend-neutral runtime plan realization seam of the lowering epic
 * (ISSUE-0544; design source
 * {@code runtime-default-evaluators-and-construction-phases} D1-D3): one
 * {@link RuntimeClassDefaultPlan} per published
 * {@link CompilerClassDefaultPlan}, realized from the backend lowerer's
 * per-entry evaluator realizations.
 *
 * <p><b>Realization contract.</b> {@link #realize} consumes the
 * published compiler plan (the graph-completed form: canonical
 * descriptors, optional flags, and serializer-completed
 * {@code ResolvedDefaultExpression}s with their semantic digests), the
 * class's canonical descriptor text (from the compilation's one
 * descriptor service — the
 * {@link deal.identity.CanonicalClassIdentityIndex} projection), and
 * one {@link EvaluatorRealization} per required-present entry, in plan
 * entry order, and produces:</p>
 *
 * <ul>
 *   <li>{@code RuntimeClassDefaultPlan(classIdentity, orderedFields)}
 *       — the runtime plan with one {@link RuntimeClassDefaultEntry}
 *       per compiler entry in the same order; each entry carries the
 *       field name, the plan's canonical runtime descriptor, the
 *       optional flag, and a {@link RuntimeDefaultEvaluator} exactly on
 *       required-present entries (the
 *       {@code runtime-default-evaluators-and-construction-phases} D1
 *       presence rule, additionally enforced by the entry carrier).</li>
 *   <li>per-entry {@code RuntimeDefaultEvaluator(semanticDigest,
 *       implementationDigest, invoke)} — the semantic digest taken
 *       verbatim from the compiler plan's completed expression, the
 *       implementation digest computed here, and the zero-argument
 *       invocation seam supplied by the backend lowerer (the generated
 *       artifact-side evaluator closure; D2).</li>
 * </ul>
 *
 * <p><b>Implementation digest derivation (D3).</b> The digest is
 * SHA-256 over the shared length-prefixed UTF-8 serialization
 * ({@link deal.project.ProtectedPathOps#lengthPrefixedUtf8}) of the
 * canonical evaluator implementation content — the label (the pinned
 * {@code (classIdentity, fieldName, semanticDigest)} triple text) and
 * the generated evaluator artifact text, joined by a newline. No
 * address, ordinal, timestamp, or process state enters the input, so
 * identical generated evaluators always produce the identical digest
 * and two backends realizing identical semantics with different
 * generated implementations produce distinct digests (the D3
 * differential property).</p>
 *
 * <p><b>Validation.</b> Realization is fail-closed over shape
 * mismatches: a realization count differing from the required-present
 * entry count, an out-of-order label, an empty label/content, or a null
 * invocation is rejected with an {@link IllegalArgumentException}
 * naming the defect — a lowering defect, never a source diagnostic (the
 * planner's E4001/E3020 gates and the plan carriers' presence rules
 * already make the mismatched shapes unreachable in the production
 * pipeline).</p>
 *
 * <p>Immutable and deterministic; identities, digests, labels, and
 * contents are compiler-internal and never appear in runtime
 * descriptors, diagnostic type names, public export keys, or
 * source-language values.</p>
 */
public final class RuntimeDefaultPlanLowering {

    private RuntimeDefaultPlanLowering() {
        // Static entry; no instances.
    }

    /**
     * One backend-lowered evaluator realization for a required-present
     * entry: the pinned label triple, the generated evaluator artifact
     * text (the canonical implementation content input), and the
     * zero-argument invocation seam.
     *
     * @param label            the pinned label text
     *                         {@code (classIdentity, fieldName,
     *                         semanticDigest)}
     * @param evaluatorContent the generated evaluator artifact text
     *                         (e.g. the Lua {@code function() ... end}
     *                         closure, the JS thunk-entry expression, or
     *                         the JVM static method body text)
     * @param invocation       the zero-argument sync invocation seam
     *                         (the backend's carrier-side closure over
     *                         the generated evaluator)
     */
    public record EvaluatorRealization(
        String label,
        String evaluatorContent,
        RuntimeDefaultEvaluator.Invocation invocation
    ) {

        public EvaluatorRealization {
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(evaluatorContent, "evaluatorContent");
            Objects.requireNonNull(invocation, "invocation");
            if (label.isEmpty()) {
                throw new IllegalArgumentException("label must not be empty");
            }
            if (evaluatorContent.isEmpty()) {
                throw new IllegalArgumentException(
                    "evaluatorContent must not be empty");
            }
        }
    }

    /**
     * The realization result: the runtime plan plus the per-entry
     * implementation digests in plan entry order (one digest per
     * required-present entry; optional entries contribute none).
     *
     * @param plan                  the realized runtime class default plan
     * @param implementationDigests the ordered per-entry implementation
     *                              digests
     */
    public record Realization(
        RuntimeClassDefaultPlan plan,
        List<String> implementationDigests
    ) {

        public Realization {
            plan = Objects.requireNonNull(plan, "plan");
            implementationDigests = List.copyOf(Objects.requireNonNull(
                implementationDigests, "implementationDigests"));
        }
    }

    /**
     * Realizes one runtime plan from the published compiler plan and
     * the backend's ordered per-required-entry evaluator realizations.
     *
     * @param plan              the graph-completed compiler plan
     * @param classIdentityText the canonical descriptor text of
     *                          {@code plan.classIdentity()} (the
     *                          compilation's
     *                          {@code CanonicalClassIdentityIndex}
     *                          projection)
     * @param evaluators        the ordered evaluator realizations —
     *                          exactly one per required-present entry,
     *                          in plan entry order
     * @return the realized runtime plan and the per-entry implementation
     *         digests
     * @throws IllegalArgumentException on any shape mismatch (a lowering
     *                                  defect; see the class contract)
     */
    public static Realization realize(CompilerClassDefaultPlan plan,
                                      String classIdentityText,
                                      List<EvaluatorRealization> evaluators) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(classIdentityText, "classIdentityText");
        Objects.requireNonNull(evaluators, "evaluators");
        List<RuntimeClassDefaultEntry> entries = new ArrayList<>();
        List<String> digests = new ArrayList<>();
        int evaluatorIndex = 0;
        for (CompilerClassDefaultEntry entry : plan.orderedFields()) {
            if (!entry.optional()) {
                ResolvedDefaultExpression expression =
                    entry.defaultExpression();
                if (expression.semanticDigest() == null) {
                    throw new IllegalArgumentException(
                        "the default of field '" + entry.name()
                            + "' has no semantic digest (the plan is not"
                            + " serializer-completed)");
                }
                if (evaluatorIndex >= evaluators.size()) {
                    throw new IllegalArgumentException(
                        "missing evaluator realization for required"
                            + " field '" + entry.name() + "'");
                }
                EvaluatorRealization realization =
                    evaluators.get(evaluatorIndex++);
                String label = labelOf(classIdentityText, entry.name(),
                    expression.semanticDigest());
                if (!realization.label().equals(label)) {
                    throw new IllegalArgumentException(
                        "evaluator label mismatch for field '"
                            + entry.name() + "': expected '" + label
                            + "', got '" + realization.label() + "'");
                }
                String implementationDigest =
                    implementationDigestOf(realization);
                digests.add(implementationDigest);
                entries.add(new RuntimeClassDefaultEntry(entry.name(),
                    entry.runtimeTypeDescriptor(), false,
                    new RuntimeDefaultEvaluator(
                        expression.semanticDigest(),
                        implementationDigest,
                        realization.invocation())));
            } else {
                entries.add(new RuntimeClassDefaultEntry(entry.name(),
                    entry.runtimeTypeDescriptor(), true, null));
            }
        }
        if (evaluatorIndex != evaluators.size()) {
            throw new IllegalArgumentException(
                evaluators.size() - evaluatorIndex
                    + " evaluator realization(s) supplied for optional"
                    + " entries or entries outside the plan");
        }
        return new Realization(new RuntimeClassDefaultPlan(
            plan.classIdentity(), entries), digests);
    }

    /**
     * The pinned label text of one evaluator (D2): the
     * {@code (classIdentity, fieldName, semanticDigest)} triple where
     * {@code classIdentity} is the canonical descriptor text.
     */
    public static String labelOf(String classIdentityText,
                                 String fieldName,
                                 String semanticDigest) {
        Objects.requireNonNull(classIdentityText, "classIdentityText");
        Objects.requireNonNull(fieldName, "fieldName");
        Objects.requireNonNull(semanticDigest, "semanticDigest");
        return "(" + classIdentityText + ", " + fieldName + ", "
            + semanticDigest + ")";
    }

    /**
     * The pinned deterministic implementation-digest derivation (D3):
     * SHA-256 (64 lowercase hex chars) over the shared length-prefixed
     * UTF-8 serialization of the canonical evaluator implementation
     * content — {@code label + "\n" + evaluatorContent}. No address,
     * ordinal, timestamp, or process state enters the input.
     */
    public static String implementationDigestOf(
            EvaluatorRealization realization) {
        Objects.requireNonNull(realization, "realization");
        String content = realization.label() + "\n"
            + realization.evaluatorContent();
        deal.project.ProtectedPathOps.ByteResult framed =
            deal.project.ProtectedPathOps.lengthPrefixedUtf8(content);
        if (framed instanceof deal.project.ProtectedPathOps.ByteResult.Success s) {
            return IdentityDigests.sha256Hex(s.bytes());
        }
        throw new IllegalArgumentException(
            "canonical evaluator implementation content is not a Unicode"
                + " scalar sequence");
    }
}
