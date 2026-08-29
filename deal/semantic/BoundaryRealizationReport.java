package deal.semantic;

import deal.diagnostics.CompilerDiagnostic;
import deal.semantic.ir.BoundaryKind;
import deal.semantic.ir.BoundaryRealization;
import deal.semantic.ir.FailureContractRegistry;
import deal.semantic.ir.FailurePolicyId;
import deal.semantic.ir.KindPayload;
import deal.semantic.ir.LoweredModuleUnit;
import deal.semantic.ir.LoweringFailureDetail;
import deal.semantic.ir.OpId;
import deal.semantic.ir.SemanticCapability;
import deal.semantic.ir.SemanticOp;
import deal.semantic.ir.SemanticOpKind;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The boundary-realization report of a {@link ModuleEmissionResult}
 * (parent "Shared module emission and publication" contract): the
 * emitter's recorded realization per boundary operation of the emitted
 * unit — {@code RuntimeValidation(checkId)} or
 * {@code RepresentationProof(proofKind)} per parent D7.
 *
 * <p>The record stays the pure immutable carrier (defensively copied,
 * op order preserved); the D4 completion predicate lives beside it:
 * {@link #complete(LoweredModuleUnit, BoundaryRealizationReport)}
 * returns empty exactly when the report is complete for the validated
 * unit — the report's key set equals the unit's {@code BOUNDARY} op-id
 * set and each entry equals the op payload's {@code realization}
 * ({@code deal/semantic/ir/KindPayload.java} BoundaryPayload) — and
 * every admissible {@code RepresentationProof} sits on the only
 * proof-eligible cell, {@code DEAL_TO_HOST} + {@code HOST_PARAMETER}
 * (the one static proof the wiki admits: the retained JVM realizes
 * host-call parameter checks by Java method-signature proof). A missing,
 * extra, or mismatched entry, a proof recorded on any other cell, or an
 * empty {@code checkId}/{@code proofKind} is a completion defect: E6005
 * through {@link FailureContractRegistry} naming the module and the
 * offending op/cell. The predicate mutates nothing; a module whose
 * report fails completion publishes no artifact set (the
 * {@link ModuleEmissionResult#failed()} convention — the emitter wires
 * the returned diagnostic, ISSUE-0239's).
 *
 * @param realizations the boundary realizations keyed by the boundary
 *                     op id; non-null
 */
public record BoundaryRealizationReport(Map<OpId, BoundaryRealization> realizations) {

    /**
     * The completion-defect identifier for a boundary op with no
     * recorded realization (parent D11 item 5, "missing boundary
     * realization").
     */
    public static final String BOUNDARY_REALIZATION_MISSING = "BOUNDARY_REALIZATION_MISSING";

    /**
     * The completion-defect identifier for a report entry whose key is
     * not a {@code BOUNDARY} op of the unit (extras are defects, never
     * tolerated).
     */
    public static final String BOUNDARY_REALIZATION_EXTRA = "BOUNDARY_REALIZATION_EXTRA";

    /** The completion-defect identifier for an entry that does not equal
     *  the op payload's {@code realization}. */
    public static final String BOUNDARY_REALIZATION_MISMATCH = "BOUNDARY_REALIZATION_MISMATCH";

    /** The completion-defect identifier for a {@code RepresentationProof}
     *  recorded outside the admissible {@code DEAL_TO_HOST} +
     *  {@code HOST_PARAMETER} cell (an unauditable omission). */
    public static final String BOUNDARY_REALIZATION_PROOF_NOT_ADMISSIBLE =
        "BOUNDARY_REALIZATION_PROOF_NOT_ADMISSIBLE";

    /** The completion-defect identifier for an empty {@code checkId} or
     *  {@code proofKind} (target-owned identifiers are non-empty
     *  strings). */
    public static final String BOUNDARY_REALIZATION_EMPTY_IDENTIFIER =
        "BOUNDARY_REALIZATION_EMPTY_IDENTIFIER";

    public BoundaryRealizationReport {
        Objects.requireNonNull(realizations, "realizations must not be null");
        Map<OpId, BoundaryRealization> copy = new LinkedHashMap<>();
        for (Map.Entry<OpId, BoundaryRealization> entry : realizations.entrySet()) {
            Objects.requireNonNull(entry.getKey(), "realizations keys must not be null");
            Objects.requireNonNull(entry.getValue(), "realizations values must not be null");
            copy.put(entry.getKey(), entry.getValue());
        }
        realizations = Collections.unmodifiableMap(copy);
    }

    /** The empty report (no boundary realizations recorded). */
    public static BoundaryRealizationReport empty() {
        return new BoundaryRealizationReport(Map.of());
    }

    // =========================================================================
    // Completion predicate (D4) — the closed realization/report completion
    // =========================================================================

    /**
     * The D4 report-completion predicate (report-completion time): empty
     * exactly when {@code report} is complete for the validated
     * {@code unit}.
     *
     * <p><b>Completeness.</b> The report's key set equals the unit's
     * {@code BOUNDARY} op-id set exactly — every boundary of the lowered
     * unit has a recorded realization, extras are defects — and each
     * entry equals the op payload's {@code realization}
     * ({@link KindPayload.BoundaryPayload#realization()}). A missing,
     * extra, or mismatched entry is a completion defect.</p>
     *
     * <p><b>Closed proof eligibility (version 1).</b>
     * {@link BoundaryRealization.RepresentationProof} is admissible only
     * on the {@code DEAL_TO_HOST} + {@code HOST_PARAMETER} cell — the
     * only static proof the wiki admits (the retained JVM realizes
     * host-call parameter checks by Java method-signature proof,
     * {@code deal/codegen/jvm/JvmBackend.java:3978-3990}). Every other
     * cell requires {@link BoundaryRealization.RuntimeValidation}; a
     * proof recorded elsewhere is an unauditable omission and a
     * completion defect. {@code checkId}/{@code proofKind} are
     * target-owned non-empty strings; an empty identifier is a
     * completion defect.</p>
     *
     * <p><b>Deterministic first-failure order.</b> The unit's ops are
     * walked in source order; for each {@code BOUNDARY} op the checks
     * run in the order missing entry, mismatched entry, inadmissible
     * proof cell, empty identifier. Extras are then checked in report
     * key order. The first defect is the single returned diagnostic.</p>
     *
     * <p><b>Boundary (pinned).</b> The eligibility check lives here at
     * report-completion time only: the ISSUE-0230 closed 14-condition
     * validator rule set ({@code deal.semantic.ir.SemanticIrValidator})
     * is unchanged — no validator rule is added, removed, or edited.
     * The emitter that fills the report into
     * {@link ModuleEmissionResult} is ISSUE-0239's; this predicate owns
     * the completeness check, the proof-eligibility check, and the
     * E6005. The predicate mutates nothing and never re-validates the
     * unit (the caller supplies the emitter's validated unit).</p>
     *
     * @param unit   the validated lowered module unit; non-null
     * @param report the emitter's boundary-realization report; non-null
     * @return empty when the report is complete; otherwise the single
     *         E6005 diagnostic (code {@code E6005}, phase
     *         {@code BACKEND_LOWERING}, severity error) produced through
     *         {@code FailureContractRegistry.e6005} with a
     *         {@link LoweringFailureDetail} naming the module (capability
     *         {@code BOUNDARIES}) and the offending op/cell in its origin
     * @throws NullPointerException if {@code unit} or {@code report} is
     *                              null
     */
    public static Optional<CompilerDiagnostic> complete(LoweredModuleUnit unit,
                                                        BoundaryRealizationReport report) {
        Objects.requireNonNull(unit, "unit must not be null");
        Objects.requireNonNull(report, "report must not be null");
        Map<OpId, BoundaryRealization> recorded = report.realizations();
        Set<OpId> boundaryOpIds = new LinkedHashSet<>();
        for (SemanticOp op : unit.ops()) {
            if (op.kind() != SemanticOpKind.BOUNDARY) {
                continue;
            }
            boundaryOpIds.add(op.opId());
            KindPayload.BoundaryPayload payload = (KindPayload.BoundaryPayload) op.payload();
            BoundaryRealization entry = recorded.get(op.opId());
            if (entry == null) {
                return Optional.of(e6005(unit, BOUNDARY_REALIZATION_MISSING,
                    "BOUNDARY op " + op.opId() + " cell (" + payload.kind() + ", "
                        + op.failurePolicy() + ") has no recorded realization"));
            }
            if (!entry.equals(payload.realization())) {
                return Optional.of(e6005(unit, BOUNDARY_REALIZATION_MISMATCH,
                    "BOUNDARY op " + op.opId() + " cell (" + payload.kind() + ", "
                        + op.failurePolicy() + ") recorded realization " + entry
                        + " does not equal the op payload's " + payload.realization()));
            }
            if (entry instanceof BoundaryRealization.RepresentationProof proof) {
                if (payload.kind() != BoundaryKind.DEAL_TO_HOST
                        || op.failurePolicy() != FailurePolicyId.HOST_PARAMETER) {
                    return Optional.of(e6005(unit,
                        BOUNDARY_REALIZATION_PROOF_NOT_ADMISSIBLE,
                        "BOUNDARY op " + op.opId() + " cell (" + payload.kind() + ", "
                            + op.failurePolicy() + ") does not admit RepresentationProof"
                            + " (admissible only on DEAL_TO_HOST + HOST_PARAMETER)"));
                }
                if (proof.proofKind().isEmpty()) {
                    return Optional.of(e6005(unit, BOUNDARY_REALIZATION_EMPTY_IDENTIFIER,
                        "BOUNDARY op " + op.opId() + " carries an empty proofKind"));
                }
            } else if (entry instanceof BoundaryRealization.RuntimeValidation validation
                    && validation.checkId().isEmpty()) {
                return Optional.of(e6005(unit, BOUNDARY_REALIZATION_EMPTY_IDENTIFIER,
                    "BOUNDARY op " + op.opId() + " carries an empty checkId"));
            }
        }
        for (Map.Entry<OpId, BoundaryRealization> reportEntry : recorded.entrySet()) {
            if (!boundaryOpIds.contains(reportEntry.getKey())) {
                return Optional.of(e6005(unit, BOUNDARY_REALIZATION_EXTRA,
                    "report entry " + reportEntry.getKey()
                        + " has no matching BOUNDARY op in the unit"));
            }
        }
        return Optional.empty();
    }

    /**
     * The completion E6005 construction seam: registry-owned message,
     * capability {@code BOUNDARIES}, the named completion-defect
     * identifier as {@code validatorRule}, the unit's profile and IR
     * version, and an origin naming the producing component plus the
     * offending op/cell.
     */
    private static CompilerDiagnostic e6005(LoweredModuleUnit unit, String rule, String what) {
        return FailureContractRegistry.e6005(new LoweringFailureDetail(
            unit.moduleId().path(),
            SemanticCapability.BOUNDARIES,
            rule,
            unit.semanticProfile(),
            LoweredModuleUnit.FORMAT_VERSION,
            "BoundaryRealizationReport " + what));
    }
}
