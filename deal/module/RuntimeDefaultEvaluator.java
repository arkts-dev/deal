package deal.module;

import java.util.Objects;

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
         */
        Object invoke();
    }
}
