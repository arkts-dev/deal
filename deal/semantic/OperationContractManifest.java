package deal.semantic;

import deal.semantic.ir.LoweredModuleUnit;
import deal.semantic.ir.SemanticIrValidator;

import java.util.List;
import java.util.Objects;

/**
 * The operation-contract manifest of a {@link ModuleEmissionResult}
 * (parent "Shared module emission and publication" contract): the
 * operation contract manifest <b>equal to the unit</b>.
 *
 * <p>Equality holds by construction: the manifest is derived from the
 * validated unit it carries — {@link #snapshotDigests()} is the list of
 * the unit's operations' {@code OperationContractSnapshot.canonicalDigest}
 * values in source order, and {@link #unitCanonicalText()} is the
 * canonical {@code deal.semantic-ir/1} text of the exact unit the
 * snapshot digests describe. The stager validates the carried unit
 * through {@link SemanticIrValidator} before staging (the T6 gate) and
 * publishes the manifest with the artifact set, so a consumer can verify
 * any staged artifact against the exact validated contracts.</p>
 *
 * @param unit the validated lowered unit the manifest describes; non-null
 */
public record OperationContractManifest(LoweredModuleUnit unit) {

    public OperationContractManifest {
        Objects.requireNonNull(unit, "unit must not be null");
    }

    /**
     * The unit's operation-contract snapshot digests in source order —
     * one digest per produced operation of the unit.
     */
    public List<String> snapshotDigests() {
        return unit.ops().stream()
            .map(op -> op.contract().canonicalDigest())
            .toList();
    }

    /**
     * The canonical {@code deal.semantic-ir/1} text of the unit — the
     * deterministic dump the manifest describes.
     */
    public String unitCanonicalText() {
        return SemanticIrValidator.toUnitText(unit);
    }
}
