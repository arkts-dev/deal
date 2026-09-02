package deal.semantic;

import deal.semantic.ir.BindingGeneration;
import deal.semantic.ir.BindingId;
import deal.semantic.ir.BindingImmutabilityProof;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The checker-side conservative binding-immutability analysis of the
 * BINDINGS capability (ISSUE-0448 proof child, sequencing item 5; design
 * B7): the producer of the closed {@link BindingImmutabilityProof}
 * records that the D15 VALUE arm of the shape-map child consumes. The
 * closed rule (B7, exactly):
 *
 * <p>A binding incarnation carries a {@link BindingImmutabilityProof}
 * iff <em>no assignment to that binding occurs anywhere in its enclosing
 * scope after its declaration</em> — for parameters, any assignment in
 * the function body defeats the proof; for loop variables, the update
 * assignment defeats the proof; for function names, any assignment to
 * the name in the module defeats the proof; intrinsic bindings
 * ({@code int}/{@code number}) always carry the proof (builtin,
 * unassignable).</p>
 *
 * <p><b>Conservatism.</b> Any assignment that resolves to an incarnation
 * defeats that incarnation's proof regardless of its position relative
 * to an adaptation site — the analysis never predicts which assignments
 * execute, and the shape-map child's positions carry no weight here.</p>
 *
 * <p><b>Per-incarnation records.</b> Proofs are recorded per
 * {@code {binding, generation}} over the binding-core child's incarnation
 * map ({@link SemanticLowerer.BindingCoreFacts}): an assignment defeats
 * exactly the incarnation it resolves to at its site — the dominant
 * incarnation there — so the for-let counter (generation 0, the update
 * assignment resolves to it) carries no proof while the per-iteration
 * incarnation (generation 1) does. The records are the closed
 * {@link BindingImmutabilityProof} shape copied into
 * {@code FUNCTION_ADAPT} payloads by the shape-map child.</p>
 *
 * <p><b>Checker facts only.</b> The production walk feeds this analysis
 * with the checked program's assignment sites as resolved by the walk's
 * own dominant-incarnation environment (declaration/parameter facts) and
 * with the checker's intrinsic-binding facts
 * ({@code checks.symbolTable().resolve(name) instanceof
 * IntrinsicSymbol}); it consumes no target, route, or emitter knowledge
 * and executes nothing.</p>
 *
 * <p><b>Narrowing is orthogonal (B3).</b> Proofs concern reassignment
 * only; {@code NullNarrowing} flow state never enters the analysis.</p>
 *
 * <p>Determinism: assignment facts are recorded in walk order and proofs
 * derive in binding-registration order (generation ascending per
 * binding), so repeated analysis of the same checked module yields
 * identical records.</p>
 */
public final class BindingImmutabilityAnalysis {

    /**
     * One resolved variable-assignment fact of the production walk
     * (checker facts): the source name of the target, the binding
     * identity, and the generation of the dominant incarnation at the
     * assignment site — the incarnation this assignment defeats.
     */
    public record BindingAssignment(String name, BindingId binding, long generation) {

        public BindingAssignment {
            Objects.requireNonNull(name, "name must not be null");
            Objects.requireNonNull(binding, "binding must not be null");
            if (generation < 0) {
                throw new IllegalArgumentException(
                    "generation must be >= 0, got " + generation);
            }
        }
    }

    /**
     * The complete fact surface of one analysis: the derived proof
     * records (binding-registration order, generation ascending per
     * binding) plus the resolved assignment facts (walk order). The
     * proofs name the {@code {binding, generation}} of the dominant
     * incarnation at the analyzed site; adaptation positions and mode
     * selection are the shape-map child's (out of this child's window).
     */
    public record BindingImmutabilityFacts(List<BindingImmutabilityProof> proofs,
                                           List<BindingAssignment> assignments) {

        /** The empty fact set (the failure-path value). */
        public static BindingImmutabilityFacts empty() {
            return new BindingImmutabilityFacts(List.of(), List.of());
        }

        public BindingImmutabilityFacts {
            Objects.requireNonNull(proofs, "proofs must not be null");
            Objects.requireNonNull(assignments, "assignments must not be null");
            proofs = List.copyOf(proofs);
            assignments = List.copyOf(assignments);
        }

        /**
         * The proof record of exactly the named {@code {binding,
         * generation}} pair, or empty when that incarnation carries no
         * proof (an assignment defeated it or the pair is unknown).
         *
         * @param binding    the binding identity; non-null
         * @param generation the generation ordinal; non-negative
         * @return the proof record, or empty
         */
        public Optional<BindingImmutabilityProof> proofOf(BindingId binding,
                                                          long generation) {
            Objects.requireNonNull(binding, "binding must not be null");
            if (generation < 0) {
                throw new IllegalArgumentException(
                    "generation must be >= 0, got " + generation);
            }
            for (BindingImmutabilityProof proof : proofs) {
                if (proof.binding().equals(binding) && proof.generation() == generation) {
                    return Optional.of(proof);
                }
            }
            return Optional.empty();
        }

        /** True iff the named {@code {binding, generation}} pair carries a proof. */
        public boolean proven(BindingId binding, long generation) {
            return proofOf(binding, generation).isPresent();
        }
    }

    /** The resolved assignment facts in walk order. */
    private final List<BindingAssignment> assignmentFacts = new ArrayList<>();

    /** The defeated {@code {binding, generation}} pairs. */
    private final Set<BindingGeneration> assigned = new LinkedHashSet<>();

    /** The intrinsic binding identities (always proven, B7). */
    private final Set<BindingId> intrinsicBindings = new LinkedHashSet<>();

    /**
     * Records one resolved variable assignment of the production walk:
     * the assignment defeats the incarnation it names — the dominant
     * incarnation at the assignment site, per the walk's own resolution.
     *
     * @param name       the source identifier name of the target; non-null
     * @param binding    the resolved binding identity; non-null
     * @param generation the resolved dominant generation; non-negative
     */
    public void recordAssignment(String name, BindingId binding, long generation) {
        BindingAssignment fact = new BindingAssignment(name, binding, generation);
        assignmentFacts.add(fact);
        assigned.add(new BindingGeneration(binding, generation));
    }

    /**
     * Marks one intrinsic binding ({@code int}/{@code number}, B7):
     * intrinsic bindings always carry the proof — builtin, unassignable —
     * regardless of any assignment fact.
     *
     * @param binding the intrinsic binding identity; non-null
     */
    public void markIntrinsic(BindingId binding) {
        intrinsicBindings.add(Objects.requireNonNull(binding,
            "binding must not be null"));
    }

    /** True iff an assignment defeated the named {@code {binding, generation}} pair. */
    public boolean assigned(BindingId binding, long generation) {
        return assigned.contains(new BindingGeneration(binding, generation));
    }

    /** True iff the binding is a marked intrinsic binding (always proven). */
    public boolean intrinsic(BindingId binding) {
        return intrinsicBindings.contains(binding);
    }

    /** The resolved assignment facts in walk order (immutable snapshot). */
    public List<BindingAssignment> assignments() {
        return List.copyOf(assignmentFacts);
    }

    /**
     * Derives the proof records over the binding-core child's incarnation
     * map in the map's registration order (generation ascending per
     * binding): one {@link BindingImmutabilityProof} per incarnation
     * unless an assignment defeated it; intrinsic bindings always carry
     * their proofs.
     *
     * @param bindings the binding-core incarnation map (registration
     *                 order); non-null
     * @return the proof records in registration order
     */
    public List<BindingImmutabilityProof> deriveProofs(
            List<SemanticLowerer.BindingCoreBinding> bindings) {
        Objects.requireNonNull(bindings, "bindings must not be null");
        List<BindingImmutabilityProof> proofs = new ArrayList<>();
        for (SemanticLowerer.BindingCoreBinding binding : bindings) {
            for (SemanticLowerer.BindingCoreIncarnation incarnation
                    : binding.incarnations()) {
                if (intrinsic(binding.binding())
                        || !assigned(binding.binding(), incarnation.generation())) {
                    proofs.add(new BindingImmutabilityProof(binding.binding(),
                        incarnation.generation()));
                }
            }
        }
        return proofs;
    }

    /**
     * The complete fact surface over the binding-core incarnation map:
     * the derived proof records plus the resolved assignment facts.
     *
     * @param bindings the binding-core incarnation map (registration
     *                 order); non-null
     * @return the complete fact surface; non-null
     */
    public BindingImmutabilityFacts facts(
            List<SemanticLowerer.BindingCoreBinding> bindings) {
        return new BindingImmutabilityFacts(deriveProofs(bindings), assignments());
    }
}
