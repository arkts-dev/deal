package deal.parser;

import deal.ast.FileDirectives;
import deal.ast.ImportDeclaration;
import deal.ast.StatementNode;
import deal.diagnostics.CompilerDiagnostic;
import deal.diagnostics.DiagnosticCode;
import deal.diagnostics.DiagnosticRange;
import deal.lexer.CompilerDirective;
import deal.lexer.DirectiveName;

import java.util.List;

/**
 * File-directive evaluation over structured directive events
 * (fixed-name-directive-events D4, D7, D10).
 *
 * <p>Runs at {@code parse()} start over the parent lexer events and at
 * each template-embedded merge point over the merged events (D10.5);
 * the seen-sets are shared across both, so duplicate checks see parent
 * and merged events alike. Placement of {@code @extern-c} is finalized
 * after statement parsing; {@link #build()} then produces the
 * {@link FileDirectives} record.</p>
 */
final class FileDirectiveEvaluator {

    private final String file;
    private final boolean isDeclarationFile;
    private final List<CompilerDiagnostic> diagnostics;

    private boolean seenExternC = false;
    private CompilerDirective validExternCEvent = null;
    private DiagnosticRange externCRange = null;
    private boolean placementValid = true;

    FileDirectiveEvaluator(String file, List<CompilerDiagnostic> diagnostics) {
        this.file = file;
        this.isDeclarationFile = file != null && file.endsWith(".d.deal");
        this.diagnostics = diagnostics;
    }

    /**
     * Evaluates the parent lexer events before statement parsing (D4):
     * first the extern-c shape, duplicate and placement checks in event order, then the argument sweep in event
     * order.
     */
    void evaluateParentEvents(List<CompilerDirective> events) {
        for (CompilerDirective e : events) {
            if (e.name() == DirectiveName.EXTERN_C) {
                checkExternC(e);
            }
        }
        for (CompilerDirective e : events) {
            argumentSweep(e);
        }
    }

    /**
     * Evaluates one template-embedded merged event at its merge point
     * (D10.5): the same per-event rules as the parse-start evaluator.
     */
    void evaluateMergedEvent(CompilerDirective e) {
        if (e.name() == DirectiveName.EXTERN_C) {
            checkExternC(e);
        }
        argumentSweep(e);
    }

    /**
     * Adopts the seen-set state of another evaluator so duplicate checks
     * share their seen-sets with the parent events (D10.5) — used by the
     * template sub-parser before nested merge points run.
     */
    void adoptSeenState(FileDirectiveEvaluator parent) {
        this.seenExternC = parent.seenExternC;
    }

    /**
     * One {@code @extern-c} event (D4): at most once, and only in
     * declaration ({@code .d.deal}) files — a wrong-file-kind event
     * fails with no silent demotion and never contributes to the
     * extern-C flag.
     */
    private void checkExternC(CompilerDirective e) {
        if (seenExternC) {
            error(DiagnosticCode.E1046,
                "Duplicate @extern-c directive (each file directive may"
                    + " occur at most once)",
                e.sourceRange());
            return;
        }
        seenExternC = true;
        if (externCRange == null) {
            externCRange = e.sourceRange();
        }
        if (!isDeclarationFile) {
            error(DiagnosticCode.E1046,
                "@extern-c is only valid in declaration (.d.deal) files",
                e.sourceRange());
            return;
        }
        validExternCEvent = e;
    }

    /**
     * The argument sweep (D4): any non-empty trimmed argument on
     * {@code jsonable}, {@code extern-c}, {@code c-struct}, or
     * {@code c-pointer} is E1045 at the complete directive comment
     * range. Unknown events carry an empty argument.
     */
    private void argumentSweep(CompilerDirective e) {
        if (e.name() == null || e.trimmedArgument().isEmpty()) {
            return;
        }
        switch (e.name()) {
            case JSONABLE, EXTERN_C, C_STRUCT, C_POINTER -> error(
                DiagnosticCode.E1045,
                "@" + e.name().text() + " takes no argument, got: '"
                    + e.trimmedArgument() + "'",
                e.sourceRange());
            default -> {
                // Unknown events carry no argument.
            }
        }
    }

    /**
     * Finalizes {@code @extern-c} placement over the parsed top-level
     * statements (D4): every statement whose start precedes the
     * extern-c event must be an import, and no import may follow the
     * extern-c event.
     */
    void finalizeExternCPlacement(List<StatementNode> statements) {
        if (validExternCEvent == null) {
            return;
        }
        int eventOffset = validExternCEvent.sourceRange().startScalarOffset();
        for (StatementNode stmt : statements) {
            int startOffset = stmt.span().startScalarOffset();
            boolean precedes = startOffset >= 0 && startOffset < eventOffset;
            if (precedes && !(stmt instanceof ImportDeclaration)) {
                error(DiagnosticCode.E1046,
                    "@extern-c must follow all top-level declarations other"
                        + " than imports",
                    validExternCEvent.sourceRange());
                placementValid = false;
                return;
            }
            if (!precedes && stmt instanceof ImportDeclaration) {
                error(DiagnosticCode.E1046,
                    "@extern-c must precede all import declarations",
                    validExternCEvent.sourceRange());
                placementValid = false;
                return;
            }
        }
    }

    /** The effective extern-C flag (D4): provisional and placement valid. */
    boolean externCEffective() {
        return validExternCEvent != null && placementValid;
    }

    /**
     * Builds the {@link FileDirectives} record (D5). The record is still
     * constructed with the effective default for defensive consumers
     * even when errors occurred.
     */
    FileDirectives build() {
        return new FileDirectives(
            externCEffective(), externCRange);
    }

    private void error(DiagnosticCode code, String message, DiagnosticRange range) {
        diagnostics.add(CompilerDiagnostic.error(code, message, range));
    }
}
