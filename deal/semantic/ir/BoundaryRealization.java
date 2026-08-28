package deal.semantic.ir;

import java.util.Objects;

/**
 * The closed boundary realization of a {@code BOUNDARY} op (parent D7;
 * schema S3): a target reports either
 * {@code RuntimeValidation(checkId)} — a physical runtime check identified
 * by the target's check id — or {@code RepresentationProof(proofKind)} — a
 * static proof that the target representation makes the check redundant.
 * Both produce the same semantic START/SUCCESS/FAILURE trace phases.
 * Missing realization fails E6005; this sealed shape makes an unreported
 * omission inexpressible.
 *
 * <p>Closed shape: exactly the two variants below; no other realization
 * form exists. The check/proof identifiers are target-owned strings, not
 * semantic IDs.</p>
 */
public sealed interface BoundaryRealization
    permits BoundaryRealization.RuntimeValidation, BoundaryRealization.RepresentationProof {

    /** A physical runtime check identified by the target's check id. */
    record RuntimeValidation(String checkId) implements BoundaryRealization {

        public RuntimeValidation {
            Objects.requireNonNull(checkId, "checkId must not be null");
        }
    }

    /** A static representation proof identified by the target's proof kind. */
    record RepresentationProof(String proofKind) implements BoundaryRealization {

        public RepresentationProof {
            Objects.requireNonNull(proofKind, "proofKind must not be null");
        }
    }
}
