package deal.semantic;

import deal.diagnostics.CompilerDiagnostic;
import deal.semantic.ir.ProjectInterfaceIndex;

import java.util.List;
import java.util.Objects;

/**
 * The outcome of one {@link CheckedProjectBuilder#build} call (foundation
 * F2): on success exactly one immutable {@link CheckedProjectInput} and
 * one immutable {@link ProjectInterfaceIndex} with an empty diagnostics
 * list; on an E6005 fact defect both records are {@code null} and the
 * diagnostics list carries the single E6005 produced through the failure
 * contract registry (never a crash, never an invented rendering).
 *
 * @param input       the checked project input; null exactly when the
 *                    build failed
 * @param index       the project interface index; null exactly when the
 *                    build failed
 * @param diagnostics the E6005 diagnostics (empty on success); non-null
 */
public record CheckedProjectBuildResult(
    CheckedProjectInput input,
    ProjectInterfaceIndex index,
    List<CompilerDiagnostic> diagnostics
) {

    public CheckedProjectBuildResult {
        Objects.requireNonNull(diagnostics, "diagnostics must not be null");
        diagnostics = List.copyOf(diagnostics);
        if ((input == null) != (index == null)) {
            throw new IllegalArgumentException(
                "input and index succeed or fail together: exactly one CheckedProjectInput "
                    + "and one ProjectInterfaceIndex per successful build");
        }
        if (input != null && !diagnostics.isEmpty()) {
            throw new IllegalArgumentException(
                "a successful build result carries no diagnostics");
        }
        if (input == null && diagnostics.isEmpty()) {
            throw new IllegalArgumentException(
                "a failed build result carries at least one diagnostic");
        }
    }

    /** Whether the build failed (at least one E6005 diagnostic). */
    public boolean hasErrors() {
        return !diagnostics.isEmpty();
    }
}
