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
     */
    public BindingImmutabilityFacts facts(
            List<SemanticLowerer.BindingCoreBinding> bindings) {
        return new BindingImmutabilityFacts(deriveProofs(bindings), assignments());
    }
}
