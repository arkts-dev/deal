package deal.semantic;

import deal.diagnostics.CompilerDiagnostic;
import deal.semantic.ir.LoweredModuleUnit;

import java.util.List;
import java.util.Objects;

/**
 * One routed module's emission result — the parent "Shared module
 * emission and publication" contract input shape:
 *
 * <pre>{@code
 * ModuleEmissionResult(
 *   stagedArtifacts, diagnostics, emittedAbiManifest,
 *   BoundaryRealizationReport, OperationContractManifest
 * )
 * }</pre>
 *
 * <p><b>Failure contract (pinned).</b> A module whose emission reported
 * failure ({@link #failed()}) has <b>no staged artifact</b>: the compact
 * constructor enforces that a failed result carries an empty artifact
 * list, so the no-partial-artifact convention (foundation F6; parent
 * "Failed module has no staged artifact") is structural, never a
 * convention the stager must remember to apply. A successful result
 * carries exactly one {@link OperationContractManifest} whose unit is
 * the lowered unit the artifacts realize — the stager's pre-staging
 * validator gate (T6) consumes that unit — and its {@code
 * emittedAbiManifest} is the shared module's own ABI manifest completed
 * at emission (foundation F5/F6; complete only for synthetic flows in
 * this epic — production completion is ISSUE-0239's). The record is
 * immutable and copies every collection defensively.</p>
 *
 * @param stagedArtifacts             the artifacts the module staged;
 *                                    non-null, empty when failed
 * @param diagnostics                 the emission diagnostics; non-null,
 *                                    non-empty iff the emission failed
 * @param emittedAbiManifest          the shared module's own ABI manifest
 *                                    (artifact owner {@code SHARED}), or
 *                                    null when the emission failed
 * @param boundaryRealizationReport   the boundary-realization report; non-null
 * @param operationContractManifest   the operation-contract manifest
 *                                    carrying the validated unit; non-null
 *                                    on success, null when failed
 */
public record ModuleEmissionResult(
    List<StagedArtifact> stagedArtifacts,
    List<CompilerDiagnostic> diagnostics,
    TargetModuleAbi emittedAbiManifest,
    BoundaryRealizationReport boundaryRealizationReport,
    OperationContractManifest operationContractManifest
) {

    public ModuleEmissionResult {
        Objects.requireNonNull(stagedArtifacts, "stagedArtifacts must not be null");
        stagedArtifacts = List.copyOf(stagedArtifacts);
        Objects.requireNonNull(diagnostics, "diagnostics must not be null");
        diagnostics = List.copyOf(diagnostics);
        Objects.requireNonNull(boundaryRealizationReport,
            "boundaryRealizationReport must not be null");
        if (!diagnostics.isEmpty() && !stagedArtifacts.isEmpty()) {
            throw new IllegalArgumentException(
                "a module whose emission reported failure has no staged artifact "
                    + "(foundation F6); a failed result must carry an empty artifact list");
        }
        // A shared emission must carry both its operation-contract manifest
        // (the unit the stager validates before staging) and its emitted ABI
        // manifest; a retained-side artifact carrier carries neither
        // (synthetic in this epic, completed by ISSUE-0239). Completeness is
        // the stager's producer-defect gate (STAGE_MISSING_EMITTED_ABI /
        // STAGE_EMITTED_ABI_MISMATCH), not a record invariant: the record
        // pins the failure shape, the stager pins the completeness checks.
    }

    /**
     * Whether the emission reported failure — exactly
     * {@code !diagnostics.isEmpty()}. A failed module has no staged
     * artifact and its outputs are never written.
     */
    public boolean failed() {
        return !diagnostics.isEmpty();
    }

    /**
     * The validated lowered unit of a successful emission (the unit the
     * operation-contract manifest carries), or {@code null} when the
     * emission failed.
     */
    public LoweredModuleUnit unit() {
        return operationContractManifest == null ? null : operationContractManifest.unit();
    }
}
