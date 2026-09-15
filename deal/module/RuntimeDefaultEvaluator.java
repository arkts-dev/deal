package deal.module;

import java.util.Objects;

/**
 * The parent-pinned runtime default evaluator shape (design source
 * {@code deal-v1.2-int32-and-bytes-architecture} D5, adopted verbatim;
 * carrier-shape domain {@code default-plan-carriers} D2/D8):
 *
 * <pre>
 * RuntimeDefaultEvaluator(semanticDigest, implementationDigest,
 *   invoke: () -&gt; DEAL value)
 * </pre>
 *
 * <ul>
 *   <li>{@code semanticDigest} — the entry's canonical semantic digest
 *       (produced by the serializer epic, ISSUE-0542;
 *       {@code runtime-default-evaluators-and-construction-phases}
 *       D3).</li>
 *   <li>{@code implementationDigest} — SHA-256 over the lowerer's
 *       canonical evaluator implementation content, computed by the
 *       lowering epic (ISSUE-0544); distinct from the semantic digest
 *       so identical semantics realized differently on two backends
 *       stay distinguishable.</li>
 *   <li>{@code invoke} — the labelled zero-argument invocation seam
 *       ({@link Invocation}): closes over the declaring module's scope
 *       and returns the evaluated DEAL value; sync by contract.</li>
 * </ul>
 *
 * <p><b>Sync contract:</b> {@code invoke()} is a zero-argument
 * {@code () -&gt; DEAL value} and is sync by contract — it never
 * suspends, guaranteed by the compiler-side E3020 gate
 * ({@code provider-versioned-default-plans} D2: {@code await} at
 * evaluator scope is rejected before any plan exists). It performs no
 * conversion, no validation, and mutates no plan state; a raised DEAL
 * error propagates unchanged. Evaluator labels, declaring-scope
 * closures, zero import-time evaluation, and one invocation per omitted
 * required entry per construction attempt are the lowering and
 * construction epics' contracts, not this carrier's.</p>
 *
 * <p>Immutable and deterministic except for the intentional invocation
 * effect: the record fields never change, and identical digest inputs
 * produce equal instances. Digests are compiler-internal and never
 * appear in runtime descriptors, diagnostic type names, public export
 * keys, or source-language values.</p>
 *
 * @param semanticDigest      the entry's canonical semantic digest
 * @param implementationDigest the lowerer's evaluator implementation
 *                            digest
 * @param invoke              the zero-argument sync invocation seam
 */
public record RuntimeDefaultEvaluator(
    String semanticDigest,
    String implementationDigest,
    Invocation invoke
) {

    public RuntimeDefaultEvaluator {
        Objects.requireNonNull(semanticDigest, "semanticDigest");
        Objects.requireNonNull(implementationDigest, "implementationDigest");
        Objects.requireNonNull(invoke, "invoke");
    }

    /**
     * The named zero-argument invocation seam of a default evaluator
     * ({@code default-plan-carriers} D8): {@code invoke()} takes zero
     * arguments and returns the evaluated DEAL value (the backend
     * runtime value — {@link Object} on the JVM carrier).
     *
     * <p>Sync by contract: the invocation never suspends (the
     * compiler-side E3020 gate guarantees it). It may raise a DEAL
     * error, which propagates unchanged; it performs no conversion or
     * validation and mutates no evaluator or plan state.</p>
     */
    @FunctionalInterface
    public interface Invocation {

        /**
         * Evaluates the default once and returns the DEAL value.
         *
         * @return the evaluated DEAL value
         */
        Object invoke();
    }
}
