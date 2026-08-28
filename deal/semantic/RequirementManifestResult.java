package deal.semantic;

import deal.diagnostics.CompilerDiagnostic;

import java.util.List;
import java.util.Objects;

/**
 * The outcome of one {@link LoweringSupport#computeManifests} call
 * (foundation F3): on success exactly one immutable
 * {@link SemanticRequirementManifest} per implementation module in
 * dependency (check) order with an empty diagnostics list; on an E6005
 * fact defect the manifest list is {@code null} and the diagnostics list
 * carries the single E6005 produced through the failure contract registry
 * (T5) — never a crash, never an invented claim, and never any other new
 * error. Inconsistent checked facts — an import resolving outside the
 * dependency-ordered index, an implementation input entry without its
 * {@code CheckResult}, an out-of-grammar checked fact the detector
 * requires — are the only E6005 sources; every SHARED-ineligibility
 * condition is a silent claim or a later planner condition, never an
 * error here.
 *
 * @param manifests   the dependency-ordered manifests; null exactly when
 *                    the computation failed
 * @param diagnostics the E6005 diagnostics (empty on success); non-null
 */
public record RequirementManifestResult(
    List<SemanticRequirementManifest> manifests,
    List<CompilerDiagnostic> diagnostics
) {

    public RequirementManifestResult {
        Objects.requireNonNull(diagnostics, "diagnostics must not be null");
        diagnostics = List.copyOf(diagnostics);
        if (manifests == null && diagnostics.isEmpty()) {
            throw new IllegalArgumentException(
                "a failed manifest computation carries at least one diagnostic");
        }
        if (manifests != null && !diagnostics.isEmpty()) {
            throw new IllegalArgumentException(
                "a successful manifest computation carries no diagnostics");
        }
        if (manifests != null) {
            manifests = List.copyOf(manifests);
        }
    }

    /** Whether the computation failed (at least one E6005 diagnostic). */
    public boolean hasErrors() {
        return !diagnostics.isEmpty();
    }
}
