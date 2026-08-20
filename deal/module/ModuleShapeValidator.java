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
 * statements, so they may not appear inside blocks or function bodies,
 * including function bodies nested inside expressions (function
 * expressions in variable initializers, call arguments, literals,
 * class-field defaults, loop heads, and so on).  This pass runs on the
 * parsed AST (after parsing, before name resolution) and enforces those
 * rules with frontend diagnostics:</p>
 *
 * <ul>
 *   <li>{@code E1048} — import after a non-import top-level declaration</li>
 *   <li>{@code E1049} — statement that is not a top-level declaration</li>
 *   <li>{@code E1050} — import/export in a nested (non-top-level) context</li>
 *   <li>{@code E1051} — bodyless (external) function declaration in an
 *       implementation file, at any nesting depth (function bodies,
 *       blocks, function-expression bodies reachable from any expression
 *       position, class-field defaults)</li>
 * </ul>
 *
 * <p>Declaration files ({@code .d.deal}) allow leading imports, exports,
 * class declarations, and external function declarations; their
 * statement-level restrictions keep the existing {@code E7001}
 * diagnostics in {@link ExportExtractor}.</p>
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
            checkTopLevelBody(stmt, diagnostics, isDeclarationFile);
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
     * {@code .d.deal} declaration files (E1051), at any nesting depth.
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
     * declaration: function bodies and class field default expressions
     * (which may contain function expressions).  Import declarations have
     * no nested contexts at all.
     */
    private static void checkTopLevelBody(StatementNode stmt,
                                          List<Diagnostic> diagnostics,
                                          boolean isDeclarationFile) {
        switch (stmt) {
            case FunctionDeclaration fd ->
                checkNested(fd.body(), diagnostics, isDeclarationFile);
            case ExportDeclaration exp ->
                checkTopLevelBody(exp.declaration(), diagnostics,
                    isDeclarationFile);
            case ClassDeclaration cd ->
                checkFieldDefaults(cd, diagnostics, isDeclarationFile);
            default -> { /* no nested statement scope */ }
        }
    }

    /**
     * Walks the default value expressions of a class body; a default may
     * be a function expression whose body is a nested statement context.
     */
    private static void checkFieldDefaults(ClassDeclaration cd,
                                           List<Diagnostic> diagnostics,
                                           boolean isDeclarationFile) {
        for (ClassField field : cd.fields()) {
            field.defaultExpr().ifPresent(
                expr -> checkExpression(expr, diagnostics, isDeclarationFile));
        }
    }

    /**
     * Walks a nested statement context (function body, block,
     * control-flow body, loop head expression, expression statement) and
     * rejects import/export there: imports and exports are module-level
     * declarations, not statements (E1050).  Every expression reachable
     * from a statement is visited so that function expressions nested in
     * arbitrary expression positions have their bodies checked too.
     * Bodyless (external) function declarations nested anywhere inside an
     * implementation file are rejected with E1051.
     */
    private static void checkNested(StatementNode stmt,
                                    List<Diagnostic> diagnostics,
                                    boolean isDeclarationFile) {
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
                    checkNested(s, diagnostics, isDeclarationFile);
                }
            }
            case FunctionDeclaration fd -> {
                if (!isDeclarationFile) {
                    checkBody(fd, diagnostics);
                }
                checkNested(fd.body(), diagnostics, isDeclarationFile);
            }
            case ClassDeclaration cd ->
                checkFieldDefaults(cd, diagnostics, isDeclarationFile);
            case VariableDeclaration vd ->
                checkExpression(vd.initializer(), diagnostics,
                    isDeclarationFile);
            case ReturnStatement rs -> rs.expr().ifPresent(
                e -> checkExpression(e, diagnostics, isDeclarationFile));
            case ThrowStatement ts ->
                checkExpression(ts.expr(), diagnostics, isDeclarationFile);
            case ExpressionStatement es ->
                checkExpression(es.expr(), diagnostics, isDeclarationFile);
            case DeleteStatement ds ->
                checkExpression(ds.target(), diagnostics, isDeclarationFile);
            case IfStatement is -> {
                checkExpression(is.condition(), diagnostics, isDeclarationFile);
                checkNested(is.thenBlock(), diagnostics, isDeclarationFile);
                is.elseBranch().ifPresent(eb -> {
                    switch (eb) {
                        case Either.Left<IfStatement, Block> left ->
                            checkNested(left.value(), diagnostics,
                                isDeclarationFile);
                        case Either.Right<IfStatement, Block> right ->
                            checkNested(right.value(), diagnostics,
                                isDeclarationFile);
                    }
                });
            }
            case WhileStatement ws -> {
                checkExpression(ws.condition(), diagnostics, isDeclarationFile);
                checkNested(ws.body(), diagnostics, isDeclarationFile);
            }
            case ForStatement fs -> {
                fs.init().ifPresent(
                    init -> checkForInit(init, diagnostics, isDeclarationFile));
                fs.condition().ifPresent(
                    c -> checkExpression(c, diagnostics, isDeclarationFile));
                fs.update().ifPresent(
                    u -> checkExpression(u, diagnostics, isDeclarationFile));
                checkNested(fs.body(), diagnostics, isDeclarationFile);
            }
            case ForOfStatement fos -> {
                checkExpression(fos.iterable(), diagnostics, isDeclarationFile);
                checkNested(fos.body(), diagnostics, isDeclarationFile);
            }
            case TryStatement ts -> {
                checkNested(ts.tryBlock(), diagnostics, isDeclarationFile);
                checkNested(ts.catchBlock(), diagnostics, isDeclarationFile);
            }
            case BreakStatement bs -> { /* leaf */ }
            case ContinueStatement cs -> { /* leaf */ }
        }
    }

    /** Walks the two for-init alternatives ({@code let} vs assignment). */
    private static void checkForInit(ForInit init,
                                     List<Diagnostic> diagnostics,
                                     boolean isDeclarationFile) {
        switch (init) {
            case ForInit.VarDecl vd ->
                checkNested(vd.decl(), diagnostics, isDeclarationFile);
            case ForInit.AssignExpr ae ->
                checkExpression(ae.expr(), diagnostics, isDeclarationFile);
        }
    }

    /**
     * Walks an expression and every sub-expression.  When a function
     * expression is found, its body is checked as a nested statement
     * context so imports/exports there are rejected with E1050 and
     * bodyless declarations there are rejected with E1051 in
     * implementation files.
     */
    private static void checkExpression(ExpressionNode expr,
                                        List<Diagnostic> diagnostics,
                                        boolean isDeclarationFile) {
        switch (expr) {
            case LiteralExpr le -> { /* leaf */ }
            case IdentifierExpr ie -> { /* leaf */ }
            case FunctionExpr fe ->
                checkNested(fe.body(), diagnostics, isDeclarationFile);
            case CallExpr ce -> {
                checkExpression(ce.callee(), diagnostics, isDeclarationFile);
                for (ExpressionNode arg : ce.args()) {
                    checkExpression(arg, diagnostics, isDeclarationFile);
                }
            }
            case MemberAccessExpr mae ->
                checkExpression(mae.object(), diagnostics, isDeclarationFile);
            case IndexExpr ie -> {
                checkExpression(ie.array(), diagnostics, isDeclarationFile);
                checkExpression(ie.index(), diagnostics, isDeclarationFile);
            }
            case ArrayLiteralExpr ale -> {
                for (ExpressionNode element : ale.elements()) {
                    checkExpression(element, diagnostics, isDeclarationFile);
                }
            }
            case ObjectLiteralExpr ole -> {
                for (Property property : ole.properties()) {
                    checkExpression(property.value(), diagnostics,
                        isDeclarationFile);
                }
            }
            case HasExpr he ->
                checkExpression(he.object(), diagnostics, isDeclarationFile);
            case AssignmentExpr ae -> {
                checkExpression(ae.target(), diagnostics, isDeclarationFile);
                checkExpression(ae.value(), diagnostics, isDeclarationFile);
            }
            case BinaryExpr be -> {
                checkExpression(be.left(), diagnostics, isDeclarationFile);
                checkExpression(be.right(), diagnostics, isDeclarationFile);
            }
            case UnaryExpr ue ->
                checkExpression(ue.expr(), diagnostics, isDeclarationFile);
            case TemplateLiteralExpr tle -> {
                for (ExpressionNode part : tle.parts()) {
                    checkExpression(part, diagnostics, isDeclarationFile);
                }
            }
            case AwaitExpression aw ->
                checkExpression(aw.callee(), diagnostics, isDeclarationFile);
        }
    }
}
