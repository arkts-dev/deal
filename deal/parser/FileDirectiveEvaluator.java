package deal.parser;

import deal.ast.FileDirectives;
import deal.diagnostics.CompilerDiagnostic;
import deal.diagnostics.DiagnosticCode;
import deal.lexer.CompilerDirective;

import java.util.List;
/** Validates arguments on structured declaration directive events. */
final class FileDirectiveEvaluator {
    private final List<CompilerDiagnostic> diagnostics;

    FileDirectiveEvaluator(List<CompilerDiagnostic> diagnostics) {
        this.diagnostics = diagnostics;
    }

    void evaluateParentEvents(List<CompilerDirective> events) {
        for (CompilerDirective event : events) {
            evaluateMergedEvent(event);
        }
    }

    void evaluateMergedEvent(CompilerDirective event) {
        if (event.name() != null && !event.trimmedArgument().isEmpty()) {
            diagnostics.add(CompilerDiagnostic.error(DiagnosticCode.E1045,
                "@" + event.name().text() + " takes no argument, got: '"
                    + event.trimmedArgument() + "'", event.sourceRange()));
        }
    }
    /** Declaration directives carry no file-scoped metadata. */
    FileDirectives build() {
        return FileDirectives.empty();
    }
}
