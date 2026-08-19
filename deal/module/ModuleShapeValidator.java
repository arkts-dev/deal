package deal.module;

import deal.ast.*;
import deal.lexer.Diagnostic;
import deal.diagnostics.DiagnosticCode;

import java.util.ArrayList;
import java.util.List;

/**
 * Post-parse module shape validation (DEAL v1.2 module/top-level semantics).
 *
 * <p>The v1.2 grammar restricts a module to
 * {@code ImportDeclaration* TopLevelDeclaration*} — imports first, then
 * only function/class/export declarations.  Imports and exports are not
 * statements, so they may not appear inside blocks or function bodies.
 * This pass runs on the parsed AST (after parsing, before name
 * resolution) and enforces those rules with frontend diagnostics:</p>
 *
 * <ul>
 *   <li>{@code E1048} — import after a non-import top-level declaration</li>
 *   <li>{@code E1049} — statement that is not a top-level declaration</li>
 *   <li>{@code E1050} — import/export in a nested (block) context</li>
 *   <li>{@code E1051} — bodyless (external) function declaration in an
 *       implementation file</li>
 * </ul>
 *
 * <p>Declaration files ({@code .d.deal}) allow leading imports, exports,
 * and class declarations; their statement-level restrictions keep the
 * existing {@code E7001} diagnostics in {@link ExportExtractor}.</p>
 */
public final class ModuleShapeValidator {

    private ModuleShapeValidator() { /* utility class */ }

    /**
     * Validates the top-level and nested shape of a parsed module.
     *
     * @param program           the parsed program
     * @param file              the source file name (for diagnostics)
     * @param isDeclarationFile true when the file is a {@code .d.deal}
     *                          declaration file
     * @return diagnostics for every rule violation, in source order
     */
    public static List<Diagnostic> validate(ProgramNode program, String file,
                                            boolean isDeclarationFile) {
        List<Diagnostic> diagnostics = new ArrayList<>();

        boolean seenNonImportTopLevel = false;
        for (StatementNode stmt : program.statements()) {
            if (stmt instanceof ImportDeclaration imp) {
                if (seenNonImportTopLevel) {
                    diagnostics.add(Diagnostic.error(DiagnosticCode.E1048,
                        "Import declaration must precede all other top-level declarations",
                        imp.span().file(), imp.span().startLine(),
                        imp.span().startColumn()));
                }
            } else if (isTopLevelDeclaration(stmt)) {
                seenNonImportTopLevel = true;
                if (!isDeclarationFile) {
                    if (stmt instanceof ExportDeclaration exp) {
                        checkExportedBody(exp, diagnostics);
                    } else if (stmt instanceof FunctionDeclaration fd) {
                        checkBody(fd, diagnostics);
                    }
                }
            } else if (!isDeclarationFile) {
                // Everything else (let, if, while, return, expression
                // statements, ...) is not a module top-level declaration.
                diagnostics.add(Diagnostic.error(DiagnosticCode.E1049,
                    "Only imports, functions, classes, and exports are allowed at module top level",
                    stmt.span().file(), stmt.span().startLine(),
                    stmt.span().startColumn()));
            }
            // Recurse into the bodies of top-level declarations only;
            // the top-level statement itself is never a nested context.
            checkTopLevelBody(stmt, diagnostics);
        }
        return diagnostics;
    }

    private static boolean isTopLevelDeclaration(StatementNode stmt) {
        return stmt instanceof FunctionDeclaration
            || stmt instanceof ClassDeclaration
            || stmt instanceof ExportDeclaration;
    }

    private static void checkExportedBody(ExportDeclaration exp,
                                          List<Diagnostic> diagnostics) {
        StatementNode decl = exp.declaration();
        if (decl instanceof FunctionDeclaration fd) {
            checkBody(fd, diagnostics);
        }
    }

    /**
     * Bodyless (external) function declarations are only valid in
     * {@code .d.deal} declaration files (E1051).
     */
    private static void checkBody(FunctionDeclaration fd,
                                  List<Diagnostic> diagnostics) {
        if (fd.isExternal()) {
            diagnostics.add(Diagnostic.error(DiagnosticCode.E1051,
                "Function declaration '" + fd.name()
                    + "' must have a body in implementation files",
                fd.span().file(), fd.span().startLine(),
                fd.span().startColumn()));
        }
    }

    /**
     * Recurses into the nested statement contexts of a top-level
     * declaration: function bodies.  Class declarations and imports have
     * no nested statement contexts.
     */
    private static void checkTopLevelBody(StatementNode stmt,
                                          List<Diagnostic> diagnostics) {
        switch (stmt) {
            case FunctionDeclaration fd -> checkNested(fd.body(), diagnostics);
            case ExportDeclaration exp ->
                checkTopLevelBody(exp.declaration(), diagnostics);
            default -> { /* no nested statement scope */ }
        }
    }

    /**
     * Walks a nested statement list (function body, block, control-flow
     * body) and rejects import/export there: imports and exports are
     * module-level declarations, not statements (E1050).
     */
    private static void checkNested(StatementNode stmt,
                                    List<Diagnostic> diagnostics) {
        switch (stmt) {
            case ImportDeclaration imp ->
                diagnostics.add(Diagnostic.error(DiagnosticCode.E1050,
                    "Import declarations are only allowed at module top level",
                    imp.span().file(), imp.span().startLine(),
                    imp.span().startColumn()));
            case ExportDeclaration exp ->
                diagnostics.add(Diagnostic.error(DiagnosticCode.E1050,
                    "Export declarations are only allowed at module top level",
                    exp.span().file(), exp.span().startLine(),
                    exp.span().startColumn()));
            case Block b -> {
                for (StatementNode s : b.statements()) {
                    checkNested(s, diagnostics);
                }
            }
            case FunctionDeclaration fd -> checkNested(fd.body(), diagnostics);
            case IfStatement is -> {
                checkNested(is.thenBlock(), diagnostics);
                is.elseBranch().ifPresent(eb -> {
                    switch (eb) {
                        case Either.Left<IfStatement, Block> left ->
                            checkNested(left.value(), diagnostics);
                        case Either.Right<IfStatement, Block> right ->
                            checkNested(right.value(), diagnostics);
                    }
                });
            }
            case WhileStatement ws -> checkNested(ws.body(), diagnostics);
            case ForStatement fs -> checkNested(fs.body(), diagnostics);
            case ForOfStatement fos -> checkNested(fos.body(), diagnostics);
            case TryStatement ts -> {
                checkNested(ts.tryBlock(), diagnostics);
                checkNested(ts.catchBlock(), diagnostics);
            }
            default -> { /* no nested statement scope */ }
        }
    }
}
