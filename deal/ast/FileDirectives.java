package deal.ast;

import deal.diagnostics.DiagnosticRange;

/** File-level declaration metadata; language version belongs to the project. */
public record FileDirectives(boolean externC, DiagnosticRange externCRange) {
    public static FileDirectives empty() {
        return new FileDirectives(false, null);
    }
}
