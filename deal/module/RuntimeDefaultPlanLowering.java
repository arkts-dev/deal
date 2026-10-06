package deal.module;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

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
