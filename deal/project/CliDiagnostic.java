package deal.project;

import deal.diagnostics.DiagnosticRange;

import java.util.Objects;

/**
 * A CLI-level configuration failure (design source
 * {@code strict-project-context-resolution-identity} D1,
 * {@code deal-v1.2-directives-and-c-ffi-declarations} D10): a malformed
 * entry-file value or a malformed CLI override, both CLI-supplied values.
 *
 * <p>This is the second visible failure of
 * {@link ProjectLocator#locate(String, CliOverrides)}: a
 * {@code CliDiagnostic} is produced — and no {@link ProjectContext} is
 * published — for a missing/directory/unreadable/NUL-bearing/
 * unrepresentable entry file and for an invalid backend alias or output
 * override. It is never produced for manifest-derived values: every
 * manifest failure is E2010, and an override is only consulted after the
 * manifest parsed (an override can never bypass a malformed manifest).
 *
 * <p>The CLI consumes this shape as a deterministic exit-1 message; it is
 * not a {@code deal.diagnostics.CompilerDiagnostic} because no compilation
 * source exists — CLI-supplied values have no scalar source range, so the
 * carrier uses the canonical synthetic shape anchored at the entry-file
 * value (the only CLI value the user named). The range is always
 * non-null.</p>
 *
 * @param message the deterministic user-facing message (paths may appear
 *                in messages; private identity values never do)
 * @param range   the canonical synthetic diagnostic range anchored at the
 *                entry-file value
 */
public record CliDiagnostic(String message, DiagnosticRange range) {

    public CliDiagnostic {
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(range, "range");
    }
}
