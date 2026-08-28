package deal.semantic.ir;

/**
 * The structured payload carrier of every E6005 diagnostic (schema S5;
 * foundation F2/F4/F6).
 *
 * <p>E6005 is {@code E6005(Phase.BACKEND_LOWERING,
 * "Common semantic lowering failed")} in
 * {@code deal.diagnostics.DiagnosticCode}, reserved by parent D11 for a
 * violated claimed common contract. Every E6005 carries exactly this
 * immutable record — no extra fields — and no E6005 diagnostic may be
 * constructed without one. The failure contract registry owns the detail
 * construction and the instantiated message; this record is only the
 * payload carrier.</p>
 *
 * @param module the dotted module path (ModuleId) of the module whose
 *               common-lowering contract failed
 * @param capability the semantic capability involved in the failure
 * @param validatorRule the failing validator rule ID (R-*) or the
 *                      producer's fact-defect identifier (e.g.
 *                      {@code INDEX_INTERNAL_ERROR_SENTINEL})
 * @param semanticProfile the invocation's project semantic profile
 * @param irVersion the pinned {@code deal.semantic-ir/1} schema version
 * @param origin the producing component or origin description
 */
public record LoweringFailureDetail(
    String module,
    SemanticCapability capability,
    String validatorRule,
    SemanticProfile semanticProfile,
    String irVersion,
    String origin
) {
}
