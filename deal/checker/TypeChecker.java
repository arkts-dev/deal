package deal.checker;

import deal.ast.*;
import deal.lexer.Diagnostic;
import deal.types.Type;
import deal.types.Types;

import java.util.*;
import deal.diagnostics.DiagnosticCode;

/**
 * Pass 2 of the type checker: type checking.
 *
 * <p>Walks the AST, computes the type of every expression (storing in a
 * {@code Map<ExpressionNode, Type>}), verifies statement rules, manages
 * null narrowing, contextual typing for table reads, and class construction
 * checking.
 *
 * <p>Errors produced: E3001–E3011, E4001–E4005, E5001–E5004.</p>
 */
public final class TypeChecker {

    private final String modulePath;
    private final SymbolTable rootTable;
    private final NameResolver nameResolver;
    private final Map<StatementNode, SymbolTable> scopeMap;
    private final List<Diagnostic> diagnostics = new ArrayList<>();

    // -- Accumulated results --
    private final Map<ExpressionNode, Type> typeMap = new HashMap<>();

    // -- Null narrowing (mutable during checking) --
    private NullNarrowing narrowing = new NullNarrowing();

    // -- Contextual expected type (for table reads and class construction) --
    private Type expectedType = null;

    // -- Tracking variables assigned in try blocks --
    private final Set<String> varsAssignedInTry = new HashSet<>();
    private boolean insideTry = false;

    // -- Current function return type (for return checking) --
    private Type currentReturnType = null;

    // -- Current scope for name resolution --
    private SymbolTable currentScope;

    // -- Loop depth for break/continue validation (F8) --
    private int loopDepth = 0;

    // -- Write-context flag: when true, table member/index access is
    //    treated as a write target (no contextual typing required) --
    private boolean assignmentTargetMode = false;

    private TypeChecker(String modulePath, SymbolTable rootTable,
                        NameResolver nameResolver,
                        Map<StatementNode, SymbolTable> scopeMap) {
        this.modulePath = modulePath;
        this.rootTable = rootTable;
        this.nameResolver = nameResolver;
        this.scopeMap = scopeMap;
        this.currentScope = rootTable;
    }

    // =======================================================================
    // Public API
    // =======================================================================

    /**
     * Type-checks a program and returns the result.
     */
    public static CheckResult check(String modulePath, SymbolTable symbolTable,
                                     NameResolver nameResolver, ProgramNode program) {
        Map<StatementNode, SymbolTable> scopeMap = nameResolver.scopeMap();
        TypeChecker checker = new TypeChecker(modulePath, symbolTable,
            nameResolver, scopeMap);
        checker.walkStatements(program.statements());
        return new CheckResult(
            Map.copyOf(checker.typeMap),
            checker.rootTable,
            List.copyOf(checker.diagnostics)
        );
    }

    // =======================================================================
    // Context interface for intrinsic resolvers
    // =======================================================================

    public interface Context {
        Type typeOf(ExpressionNode expr);
        Symbol resolveSymbol(String name);
        void error(DiagnosticCode code, String message, Span span);
    }

    private final Context ctx = new Context() {
        @Override public Type typeOf(ExpressionNode expr) {
            return TypeChecker.this.typeOf(expr);
        }
        @Override public Symbol resolveSymbol(String name) {
            return currentScope.resolve(name);
        }
        @Override public void error(DiagnosticCode code, String message, Span span) {
            TypeChecker.this.error(code, message, span);
        }
    };

    // =======================================================================
    // Statement walking (scope-aware)
    // =======================================================================

    private void walkStatements(List<StatementNode> statements) {
        for (StatementNode stmt : statements) {
            walkStatement(stmt);
        }
    }

    private void walkStatement(StatementNode stmt) {
        // Check for scope change
        SymbolTable savedScope = currentScope;
        SymbolTable stmtScope = scopeMap.get(stmt);
        if (stmtScope != null) {
            currentScope = stmtScope;
        }

        switch (stmt) {
            case VariableDeclaration vd -> checkVariableDeclaration(vd);
            case FunctionDeclaration fd  -> checkFunctionDeclaration(fd);
            case ClassDeclaration cd     -> { /* checked on construction sites */ }
            case ReturnStatement rs      -> checkReturnStatement(rs);
            case IfStatement is          -> checkIfStatement(is);
            case WhileStatement ws       -> checkWhileStatement(ws);
            case ForStatement fs         -> checkForStatement(fs);
            case ForOfStatement fos      -> checkForOfStatement(fos);
            case BreakStatement bs       -> checkBreak(bs);
            case ContinueStatement cs    -> checkContinue(cs);
            case ExpressionStatement es  -> checkExpression(es.expr());
            case ImportDeclaration id    -> { /* already resolved */ }
            case ExportDeclaration ed    -> walkStatement(ed.declaration());
            case DeleteStatement ds      -> checkDeleteStatement(ds);
            case TryStatement ts         -> checkTryStatement(ts);
            case ThrowStatement ts2      -> checkThrowStatement(ts2);
            case Block b                 -> walkStatements(b.statements());
            default                      -> { /* ignore */ }
        }

        currentScope = savedScope;
    }

    // =======================================================================
    // Variable declaration
    // =======================================================================

    private void checkVariableDeclaration(VariableDeclaration vd) {
        Type targetType;
        if (vd.typeAnnotation().isPresent()) {
            targetType = nameResolver.resolveTypeNode(vd.typeAnnotation().get());
        } else {
            targetType = null;
        }

        Type savedExpected = expectedType;
        expectedType = targetType;
        Type exprType = checkExpression(vd.initializer());
        expectedType = savedExpected;

        if (exprType == Type.Error.INSTANCE) return;
        if (targetType == null) {
            targetType = inferTypeFromExpression(vd.initializer(), exprType);
            if (targetType == null) return;
            // Update the symbol table with the inferred type
            Symbol sym = currentScope.resolve(vd.name());
            if (sym instanceof Symbol.VariableSymbol vs && vs.type() == null) {
                currentScope.defineReplace(vd.name(),
                    new Symbol.VariableSymbol(vd.name(), targetType, false));
            }
        }

        checkAssignment(targetType, exprType, vd.initializer().span());

        narrowing.onAssignment(vd.name());
        if (insideTry) {
            varsAssignedInTry.add(vd.name());
        }
    }

    // =======================================================================
    // Function declaration
    // =======================================================================

    private void checkFunctionDeclaration(FunctionDeclaration fd) {
        // F2: Resolve from currentScope (not rootTable) so nested functions work.
        // The function name is in the enclosing scope (parent of currentScope
        // which is the function's own parameter scope). currentScope.resolve()
        // walks the scope chain upward, so it will find the function name.
        Type.Func funcType = null;
        Symbol sym = currentScope.resolve(fd.name());
        if (sym instanceof Symbol.FunctionSymbol fs) {
            funcType = fs.funcType();
        }
        // Fallback to rootTable for top-level functions
        if (funcType == null) {
            sym = rootTable.resolve(fd.name());
            if (sym instanceof Symbol.FunctionSymbol fs) {
                funcType = fs.funcType();
            }
        }

        Type savedReturnType = currentReturnType;
        if (funcType != null) {
            currentReturnType = funcType.returnType();
        }

        // F1: Use walkStatement(fd.body()) instead of walkStatements(fd.body().statements())
        // so that the Block's scope (containing let-declared variables) is entered.
        walkStatement(fd.body());

        if (funcType != null) {
            Type retType = funcType.returnType();
            if (!Types.isNull(retType)
                    && !ReturnAnalysis.definitelyReturns(fd.body())) {
                error(DiagnosticCode.E5002,
                    "Function '" + fd.name() + "' must return a value on all paths",
                    fd.span());
            }
        }

        currentReturnType = savedReturnType;
    }

    // =======================================================================
    // Return statement
    // =======================================================================

    private void checkReturnStatement(ReturnStatement rs) {
        if (rs.expr().isPresent()) {
            if (currentReturnType == null) {
                checkExpression(rs.expr().get());
                return;
            }
            Type savedExpected = expectedType;
            expectedType = currentReturnType;
            Type exprType = checkExpression(rs.expr().get());
            expectedType = savedExpected;

            if (exprType == Type.Error.INSTANCE) return;

            if (!isAssignable(currentReturnType, exprType)) {
                error(DiagnosticCode.E5003,
                    "Return type mismatch: expected " + typeName(currentReturnType)
                    + ", got " + typeName(exprType),
                    rs.expr().get().span());
            }
        } else {
            if (currentReturnType != null && !Types.isNull(currentReturnType)) {
                error(DiagnosticCode.E5003,
                    "Return type mismatch: expected " + typeName(currentReturnType)
                    + ", got null",
                    rs.span());
            }
        }
    }

    // =======================================================================
    // If statement
    // =======================================================================

    private void checkIfStatement(IfStatement is) {
        // F6: Set expectedType = boolean for if conditions
        Type savedExpected = expectedType;
        expectedType = Type.Boolean.INSTANCE;
        Type condType = checkExpression(is.condition());
        expectedType = savedExpected;

        if (condType != Type.Error.INSTANCE && !(condType instanceof Type.Boolean)) {
            error(DiagnosticCode.E3007, "If condition must be boolean, got " + typeName(condType),
                is.condition().span());
        }

        NullNarrowing savedNarrowing = narrowing;
        NullNarrowing thenNarrowing = narrowing.branch();
        NullNarrowing elseNarrowing = narrowing.branch();

        // Apply narrowing for then branch
        narrowing = thenNarrowing;
        narrowing.onIfCondition(is.condition(), true, this::declaredTypeOf);
        walkStatement(is.thenBlock());
        thenNarrowing = narrowing;

        // Apply narrowing for else branch
        narrowing = elseNarrowing;
        narrowing.onIfCondition(is.condition(), false, this::declaredTypeOf);
        if (is.elseBranch().isPresent()) {
            switch (is.elseBranch().get()) {
                case Either.Left<IfStatement, Block> left ->
                    checkIfStatement(left.value());
                case Either.Right<IfStatement, Block> right ->
                    walkStatement(right.value());
            }
        }
        elseNarrowing = narrowing;

        if (is.elseBranch().isEmpty()) {
            narrowing = savedNarrowing;
        } else {
            thenNarrowing.merge(elseNarrowing);
            narrowing = thenNarrowing;
        }
    }

    // =======================================================================
    // While statement
    // =======================================================================

    private void checkWhileStatement(WhileStatement ws) {
        // F6: Set expectedType = boolean for while conditions
        Type savedExpected = expectedType;
        expectedType = Type.Boolean.INSTANCE;
        Type condType = checkExpression(ws.condition());
        expectedType = savedExpected;

        if (condType != Type.Error.INSTANCE && !(condType instanceof Type.Boolean)) {
            error(DiagnosticCode.E3007, "While condition must be boolean, got " + typeName(condType),
                ws.condition().span());
        }

        NullNarrowing savedNarrowing = narrowing;
        narrowing = new NullNarrowing();
        loopDepth++;
        walkStatement(ws.body());
        loopDepth--;
        narrowing = savedNarrowing;
    }

    // =======================================================================
    // For statement
    // =======================================================================

    private void checkForStatement(ForStatement fs) {
        fs.init().ifPresent(init -> {
            switch (init) {
                case ForInit.VarDecl vd -> checkVariableDeclaration(vd.decl());
                case ForInit.AssignExpr ae -> checkExpression(ae.expr());
            }
        });

        // F6: Set expectedType = boolean for for conditions
        fs.condition().ifPresent(cond -> {
            Type savedExpected = expectedType;
            expectedType = Type.Boolean.INSTANCE;
            Type condType = checkExpression(cond);
            expectedType = savedExpected;

            if (condType != Type.Error.INSTANCE && !(condType instanceof Type.Boolean)) {
                error(DiagnosticCode.E3007, "For condition must be boolean, got " + typeName(condType),
                    cond.span());
            }
        });

        fs.update().ifPresent(this::checkExpression);

        NullNarrowing savedNarrowing = narrowing;
        narrowing = new NullNarrowing();
        loopDepth++;
        walkStatement(fs.body());
        loopDepth--;
        narrowing = savedNarrowing;
    }

    // =======================================================================
    // For-of statement (D8)
    // =======================================================================

    private void checkForOfStatement(ForOfStatement fos) {
        // In parent scope (no scopeMap entry for ForOfStatement)
        Type iterableType = checkExpression(fos.iterable());
        Type varType = nameResolver.resolveTypeNode(fos.varType());

        if (iterableType instanceof Type.Array arr) {
            if (!Types.equals(arr.element(), varType)) {
                error(DiagnosticCode.E3015,
                    "For-of loop variable type " + typeName(varType)
                    + " does not match array element type " + typeName(arr.element()),
                    fos.span());
            }
        } else if (iterableType instanceof Type.String) {
            if (!(varType instanceof Type.String)) {
                error(DiagnosticCode.E3015,
                    "For-of over string requires loop variable type string, got "
                    + typeName(varType), fos.span());
            }
        } else {
            error(DiagnosticCode.E3015,
                "For-of iterable must be an array or string, got "
                + typeName(iterableType), fos.iterable().span());
        }

        NullNarrowing savedNarrowing = narrowing;
        narrowing = new NullNarrowing();
        loopDepth++;
        walkStatement(fos.body()); // Enters Block scope via scopeMap
        loopDepth--;
        narrowing = savedNarrowing;
    }

    // =======================================================================
    // Break / Continue (F8)
    // =======================================================================

    private void checkBreak(BreakStatement bs) {
        if (loopDepth == 0) {
            error(DiagnosticCode.E2000, "'break' must be inside a loop", bs.span());
        }
    }

    private void checkContinue(ContinueStatement cs) {
        if (loopDepth == 0) {
            error(DiagnosticCode.E2000, "'continue' must be inside a loop", cs.span());
        }
    }

    // =======================================================================
    // Try statement
    // =======================================================================

    private void checkTryStatement(TryStatement ts) {
        boolean savedInsideTry = insideTry;
        insideTry = true;

        walkStatement(ts.tryBlock());

        insideTry = savedInsideTry;

        walkStatement(ts.catchBlock());

        narrowing.revertAll(varsAssignedInTry);
        varsAssignedInTry.clear();
    }

    // =======================================================================
    // Throw statement
    // =======================================================================

    private void checkThrowStatement(ThrowStatement ts) {
        Type savedExpected = expectedType;
        Type errorType = Types.classType("Error", "");
        expectedType = errorType;
        Type exprType = checkExpression(ts.expr());
        expectedType = savedExpected;

        if (exprType != Type.Error.INSTANCE && !isAssignable(errorType, exprType)) {
            error(DiagnosticCode.E3001,
                "throw expression must have type Error, got " + typeName(exprType),
                ts.expr().span());
        }
    }

    // =======================================================================
    // Delete statement (F10: handle table delete without false errors)
    // =======================================================================

    private void checkDeleteStatement(DeleteStatement ds) {
        // F10: Use write-context mode so table member/index access
        // doesn't require contextual typing
        boolean savedMode = assignmentTargetMode;
        assignmentTargetMode = true;
        Type targetType = checkExpression(ds.target());
        assignmentTargetMode = savedMode;

        if (targetType instanceof Type.Error) return;

        if (ds.target() instanceof MemberAccessExpr mae) {
            Type objType = typeOf(mae.object());
            if (objType instanceof Type.Class cls) {
                Symbol sym = currentScope.resolve(cls.name());
                if (sym instanceof Symbol.ClassSymbol cs) {
                    ClassField field = IntrinsicResolvers.findField(cs.fields(), mae.field());
                    if (field != null && !field.optional()) {
                        error(DiagnosticCode.E4004,
                            "Cannot delete required field '" + mae.field() + "'",
                            ds.span());
                    }
                }
            }
            // Table delete: any field is allowed (no further check needed)
        }
        // Index delete on table: any key is allowed
    }

    // =======================================================================
    // Expression checking
    // =======================================================================

    private Type checkExpression(ExpressionNode expr) {
        Type t = computeExpressionType(expr);
        typeMap.put(expr, t);
        return t;
    }

    Type typeOf(ExpressionNode expr) {
        Type t = typeMap.get(expr);
        if (t == null) {
            t = checkExpression(expr);
        }
        return t;
    }

    private Type computeExpressionType(ExpressionNode expr) {
        return switch (expr) {
            case LiteralExpr lit       -> checkLiteral(lit);
            case IdentifierExpr id     -> checkIdentifier(id);
            case BinaryExpr bin        -> checkBinary(bin);
            case UnaryExpr un          -> checkUnary(un);
            case CallExpr call         -> checkCall(call);
            case MemberAccessExpr mae  -> checkMemberAccess(mae);
            case IndexExpr idx         -> checkIndex(idx);
            case ArrayLiteralExpr arr  -> checkArrayLiteral(arr);
            case ObjectLiteralExpr obj -> checkObjectLiteral(obj);
            case FunctionExpr fe       -> checkFunctionExpr(fe);
            case HasExpr has           -> checkHas(has);
            case AssignmentExpr assign -> checkAssignmentExpr(assign);
            case AwaitExpression await -> checkExpression(await.callee());
            case TemplateLiteralExpr tl -> {
                for (int i = 0; i < tl.parts().size(); i++) {
                    ExpressionNode part = tl.parts().get(i);
                    Type partType = checkExpression(part);
                    if (i % 2 == 1 && !(partType instanceof Type.String)) {
                        error(DiagnosticCode.E3016,
                            "Template literal interpolation must have type string, got "
                            + typeName(partType), part.span());
                    }
                }
                yield Type.String.INSTANCE;
            }
        };
    }

    // =======================================================================
    // Literal
    // =======================================================================

    private Type checkLiteral(LiteralExpr lit) {
        return switch (lit.value()) {
            case LiteralValue.NullLiteral n -> Type.Null.INSTANCE;
            case LiteralValue.BooleanLiteral b -> Type.Boolean.INSTANCE;
            case LiteralValue.IntLiteral i -> Type.Int.INSTANCE;
            case LiteralValue.NumberLiteral n -> Type.Number.INSTANCE;
            case LiteralValue.StringLiteral s -> Type.String.INSTANCE;
        };
    }

    // =======================================================================
    // Identifier
    // =======================================================================

    private Type checkIdentifier(IdentifierExpr id) {
        Symbol sym = currentScope.resolve(id.name());
        if (sym == null) {
            error(DiagnosticCode.E2001, "Undeclared identifier '" + id.name() + "'", id.span());
            return Type.Error.INSTANCE;
        }

        Type declaredType = getDeclaredType(sym);
        Type narrowedType = narrowing.getNarrowedType(id.name());
        if (narrowedType != null) {
            return narrowedType;
        }
        return declaredType;
    }

    // =======================================================================
    // Binary expression
    // =======================================================================

    private Type checkBinary(BinaryExpr bin) {
        Type leftType = checkExpression(bin.left());
        Type rightType = checkExpression(bin.right());

        if (leftType == Type.Error.INSTANCE || rightType == Type.Error.INSTANCE) {
            return Type.Error.INSTANCE;
        }

        BinaryOp op = bin.op();

        if (op == BinaryOp.EQ || op == BinaryOp.NEQ) {
            if (Types.equals(leftType, rightType)) return Type.Boolean.INSTANCE;
            if (isNullableOf(leftType, rightType) || isNullableOf(rightType, leftType))
                return Type.Boolean.INSTANCE;
            error(DiagnosticCode.E3006,
                "Cannot compare " + typeName(leftType) + " with " + typeName(rightType),
                bin.span());
            return Type.Error.INSTANCE;
        }

        if (op == BinaryOp.AND || op == BinaryOp.OR) {
            if (!(leftType instanceof Type.Boolean)) {
                error(DiagnosticCode.E3007, "Left operand of '" + opSymbol(op) + "' must be boolean, got "
                    + typeName(leftType), bin.left().span());
                return Type.Error.INSTANCE;
            }
            if (!(rightType instanceof Type.Boolean)) {
                error(DiagnosticCode.E3007, "Right operand of '" + opSymbol(op) + "' must be boolean, got "
                    + typeName(rightType), bin.right().span());
                return Type.Error.INSTANCE;
            }
            return Type.Boolean.INSTANCE;
        }

        if (op == BinaryOp.LT || op == BinaryOp.LTE || op == BinaryOp.GT || op == BinaryOp.GTE) {
            if (Types.equals(leftType, rightType)) {
                if (leftType instanceof Type.Int || leftType instanceof Type.Number
                    || leftType instanceof Type.String) {
                    return Type.Boolean.INSTANCE;
                }
            }
            error(DiagnosticCode.E3007, "Invalid operand types for comparison: "
                + typeName(leftType) + " and " + typeName(rightType), bin.span());
            return Type.Error.INSTANCE;
        }

        // For +, check special case first
        if (op == BinaryOp.ADD) {
            if (Types.equals(leftType, rightType)) {
                if (leftType instanceof Type.Int || leftType instanceof Type.Number
                    || leftType instanceof Type.String) {
                    return leftType;
                }
            }
            error(DiagnosticCode.E3010, "Invalid operand types for '+': "
                + typeName(leftType) + " and " + typeName(rightType), bin.span());
            return Type.Error.INSTANCE;
        }

        // Other arithmetic: types must match and be int or number
        if (!Types.equals(leftType, rightType)) {
            error(DiagnosticCode.E3007, "Invalid operand types for '" + opSymbol(op) + "': "
                + typeName(leftType) + " and " + typeName(rightType), bin.span());
            return Type.Error.INSTANCE;
        }
        if (leftType instanceof Type.Int || leftType instanceof Type.Number) {
            return leftType;
        }

        error(DiagnosticCode.E3007, "Invalid operand types for '" + opSymbol(op) + "': "
            + typeName(leftType) + " and " + typeName(rightType), bin.span());
        return Type.Error.INSTANCE;
    }

    // =======================================================================
    // Unary expression
    // =======================================================================

    private Type checkUnary(UnaryExpr un) {
        Type exprType = checkExpression(un.expr());
        if (exprType == Type.Error.INSTANCE) return Type.Error.INSTANCE;

        return switch (un.op()) {
            case NOT -> {
                if (!(exprType instanceof Type.Boolean)) {
                    error(DiagnosticCode.E3007, "Operand of '!' must be boolean, got "
                        + typeName(exprType), un.expr().span());
                    yield Type.Error.INSTANCE;
                }
                yield Type.Boolean.INSTANCE;
            }
            case NEG -> {
                if (!(exprType instanceof Type.Int) && !(exprType instanceof Type.Number)) {
                    error(DiagnosticCode.E3007, "Operand of unary '-' must be int or number, got "
                        + typeName(exprType), un.expr().span());
                    yield Type.Error.INSTANCE;
                }
                yield exprType;
            }
        };
    }

    // =======================================================================
    // Call expression
    // =======================================================================

    private Type checkCall(CallExpr call) {
        Type calleeType = checkExpression(call.callee());
        if (calleeType == Type.Error.INSTANCE) {
            for (ExpressionNode arg : call.args()) checkExpression(arg);
            return Type.Error.INSTANCE;
        }

        // F5: Determine parameter types for contextual typing BEFORE checking args
        List<Type> paramTypesForContext = getParamTypesForContext(call);

        List<Type> argTypes = new ArrayList<>();
        for (int i = 0; i < call.args().size(); i++) {
            ExpressionNode arg = call.args().get(i);
            // Set expectedType from the corresponding parameter type, if available
            Type savedExpected = expectedType;
            if (paramTypesForContext != null && i < paramTypesForContext.size()) {
                expectedType = paramTypesForContext.get(i);
            } else if (paramTypesForContext != null && i >= paramTypesForContext.size()
                    && hasRestParam(call)) {
                // Rest parameter: use the element type
                Type restElem = getRestElementType(call);
                if (restElem != null) {
                    expectedType = restElem;
                }
            }
            argTypes.add(checkExpression(arg));
            expectedType = savedExpected;
        }

        // Intrinsic call?
        if (call.callee() instanceof IdentifierExpr id) {
            Symbol sym = currentScope.resolve(id.name());
            if (sym instanceof Symbol.IntrinsicSymbol is) {
                return is.resolver().checkCall(call, argTypes, ctx);
            }
        }

        if (calleeType instanceof Type.Func funcType) {
            return checkFunctionCall(call, funcType, argTypes);
        }

        error(DiagnosticCode.E3008, typeName(calleeType) + " is not callable", call.callee().span());
        return Type.Error.INSTANCE;
    }

    /**
     * Extracts parameter types from a callee for contextual typing.
     * Returns null if not available.
     */
    private List<Type> getParamTypesForContext(CallExpr call) {
        if (call.callee() instanceof IdentifierExpr id) {
            Symbol sym = currentScope.resolve(id.name());
            if (sym instanceof Symbol.FunctionSymbol fs) {
                List<Type> pts = new ArrayList<>(fs.funcType().paramTypes());
                if (fs.funcType().restType().isPresent()) {
                    pts.add(fs.funcType().restType().get().element());
                }
                return pts;
            }
            if (sym instanceof Symbol.IntrinsicSymbol is) {
                if (is.type() instanceof Type.Func ft) {
                    return new ArrayList<>(ft.paramTypes());
                }
            }
        }
        Type calleeType = typeMap.get(call.callee());
        if (calleeType instanceof Type.Func ft) {
            List<Type> pts = new ArrayList<>(ft.paramTypes());
            if (ft.restType().isPresent()) {
                pts.add(ft.restType().get().element());
            }
            return pts;
        }
        calleeType = checkExpression(call.callee());
        if (calleeType instanceof Type.Func ft) {
            List<Type> pts = new ArrayList<>(ft.paramTypes());
            if (ft.restType().isPresent()) {
                pts.add(ft.restType().get().element());
            }
            return pts;
        }
        return null;
    }

    private boolean hasRestParam(CallExpr call) {
        if (call.callee() instanceof IdentifierExpr id) {
            Symbol sym = currentScope.resolve(id.name());
            if (sym instanceof Symbol.FunctionSymbol fs) {
                return fs.funcType().restType().isPresent();
            }
        }
        Type calleeType = typeMap.get(call.callee());
        if (calleeType instanceof Type.Func ft) {
            return ft.restType().isPresent();
        }
        return false;
    }

    private Type getRestElementType(CallExpr call) {
        if (call.callee() instanceof IdentifierExpr id) {
            Symbol sym = currentScope.resolve(id.name());
            if (sym instanceof Symbol.FunctionSymbol fs
                    && fs.funcType().restType().isPresent()) {
                return fs.funcType().restType().get().element();
            }
        }
        Type calleeType = typeMap.get(call.callee());
        if (calleeType instanceof Type.Func ft && ft.restType().isPresent()) {
            return ft.restType().get().element();
        }
        return null;
    }

    private Type checkFunctionCall(CallExpr call, Type.Func funcType, List<Type> argTypes) {
        int paramCount = funcType.paramTypes().size();
        boolean hasRest = funcType.restType().isPresent();

        if (!hasRest && argTypes.size() != paramCount) {
            error(DiagnosticCode.E3009,
                "Function argument count mismatch: expected " + paramCount
                + ", got " + argTypes.size(),
                call.span());
            return Type.Error.INSTANCE;
        }
        if (hasRest && argTypes.size() < paramCount) {
            error(DiagnosticCode.E3009,
                "Function argument count mismatch: expected at least " + paramCount
                + ", got " + argTypes.size(),
                call.span());
            return Type.Error.INSTANCE;
        }

        for (int i = 0; i < paramCount; i++) {
            Type paramType = funcType.paramTypes().get(i);
            Type argType = argTypes.get(i);
            if (argType == Type.Error.INSTANCE) continue;
            if (!isAssignable(paramType, argType)) {
                error(DiagnosticCode.E5001,
                    "Function argument type mismatch at position " + (i + 1)
                    + ": expected " + typeName(paramType)
                    + ", got " + typeName(argType),
                    call.args().get(i).span());
            }
        }

        if (hasRest) {
            Type.Array restArr = funcType.restType().get();
            Type restElemType = restArr.element();
            for (int i = paramCount; i < argTypes.size(); i++) {
                Type argType = argTypes.get(i);
                if (argType == Type.Error.INSTANCE) continue;
                if (!isAssignable(restElemType, argType)) {
                    error(DiagnosticCode.E5001,
                        "Rest argument type mismatch at position " + (i + 1)
                        + ": expected " + typeName(restElemType)
                        + ", got " + typeName(argType),
                        call.args().get(i).span());
                }
            }
        }

        return funcType.returnType();
    }

    // =======================================================================
    // Member access
    // =======================================================================

    private Type checkMemberAccess(MemberAccessExpr mae) {
        Type objType = checkExpression(mae.object());
        if (objType == Type.Error.INSTANCE) return Type.Error.INSTANCE;

        String field = mae.field();

        // Array length intrinsic
        if (objType instanceof Type.Array && field.equals("length")) {
            return Type.Int.INSTANCE;
        }

        // Module symbol via identifier (must be checked before Table)
        if (mae.object() instanceof IdentifierExpr id) {
            Symbol sym = currentScope.resolve(id.name());
            if (sym instanceof Symbol.ModuleSymbol ms) {
                Type exportType = ms.exports().get(field);
                if (exportType != null) return exportType;
                error(DiagnosticCode.E2004, "Export '" + field + "' not found in module '"
                    + id.name() + "'. Available: " + String.join(", ", ms.exports().keySet()), mae.span());
                return Type.Error.INSTANCE;
            }
        }

        // Class field access (including built-in Error class)
        if (objType instanceof Type.Class cls) {
            Symbol sym = currentScope.resolve(cls.name());
            // If not found locally, try cross-module resolution
            if (!(sym instanceof Symbol.ClassSymbol)) {
                Symbol.ClassSymbol importedCs =
                    nameResolver.resolveClassSymbol(cls.name(), cls.modulePath());
                if (importedCs != null) sym = importedCs;
            }
            if (sym instanceof Symbol.ClassSymbol cs) {
                ClassField cf = IntrinsicResolvers.findField(cs.fields(), field);
                if (cf != null) {
                    Type fieldType = nameResolver.resolveTypeNode(cf.type());
                    if (cf.optional() && !cf.nullable()) {
                        try {
                            fieldType = Types.nullable(fieldType);
                        } catch (IllegalArgumentException e) { /* already nullable */ }
                    }
                    return fieldType;
                } else {
                    error(DiagnosticCode.E4002, "Field '" + field + "' not declared in class '"
                        + cls.name() + "'", mae.span());
                    return Type.Error.INSTANCE;
                }
            }
            return Type.Error.INSTANCE;
        }

        // F4: Table access — in write context (assignment target), allow without
        // contextual type. Otherwise require contextual target type.
        if (objType instanceof Type.Table) {
            if (assignmentTargetMode) {
                return Type.Table.INSTANCE;
            }
            if (expectedType != null && !Types.isNull(expectedType)) {
                return expectedType;
            }
            error(DiagnosticCode.E3003,
                "Table field read requires contextual target type", mae.span());
            return Type.Error.INSTANCE;
        }

        error(DiagnosticCode.E3003,
            "Cannot access field '" + field + "' on type " + typeName(objType), mae.span());
        return Type.Error.INSTANCE;
    }

    // =======================================================================
    // Index expression
    // =======================================================================

    private Type checkIndex(IndexExpr idx) {
        Type arrayType = checkExpression(idx.array());
        Type indexType = checkExpression(idx.index());
        if (arrayType == Type.Error.INSTANCE || indexType == Type.Error.INSTANCE)
            return Type.Error.INSTANCE;

        // F5: Table index — in write context (assignment target or delete),
        // allow any index type and skip further checks
        if (arrayType instanceof Type.Table && assignmentTargetMode) {
            return Type.Table.INSTANCE;
        }

        // For arrays and reads, the index must be int
        if (!(indexType instanceof Type.Int)) {
            error(DiagnosticCode.E3007, "Array index must be int, got " + typeName(indexType),
                idx.index().span());
            return Type.Error.INSTANCE;
        }
        if (arrayType instanceof Type.Array arr) {
            return arr.element();
        }

        error(DiagnosticCode.E3007, "Cannot index type " + typeName(arrayType), idx.array().span());
        return Type.Error.INSTANCE;
    }

    // =======================================================================
    // Array literal
    // =======================================================================

    private Type checkArrayLiteral(ArrayLiteralExpr arr) {
        List<ExpressionNode> elements = arr.elements();
        if (elements.isEmpty()) {
            if (expectedType instanceof Type.Array expectedArr) return expectedArr;
            error(DiagnosticCode.E3002, "Cannot infer type of empty array literal", arr.span());
            return Type.Error.INSTANCE;
        }

        Type firstType = null;
        boolean mixed = false;
        for (ExpressionNode elem : elements) {
            Type elemType = checkExpression(elem);
            if (elemType == Type.Error.INSTANCE) continue;
            if (firstType == null) firstType = elemType;
            else if (!Types.equals(firstType, elemType)) {
                mixed = true;
                error(DiagnosticCode.E3011,
                    "Mixed types in array literal: " + typeName(firstType)
                    + " and " + typeName(elemType),
                    elem.span());
            }
        }
        if (mixed || firstType == null) return Type.Error.INSTANCE;
        return Types.array(firstType);
    }

    // =======================================================================
    // Object literal
    // =======================================================================

    private Type checkObjectLiteral(ObjectLiteralExpr obj) {
        if (expectedType instanceof Type.Class cls) {
            return checkClassConstruction(obj, cls);
        }
        for (Property prop : obj.properties()) {
            checkExpression(prop.value());
        }
        if (obj.properties().isEmpty() && expectedType == null) {
            error(DiagnosticCode.E3002, "Cannot infer type of empty object literal", obj.span());
            return Type.Error.INSTANCE;
        }
        return Type.Table.INSTANCE;
    }

    private Type checkClassConstruction(ObjectLiteralExpr obj, Type.Class cls) {
        Symbol sym = currentScope.resolve(cls.name());
        // If not found locally, try cross-module resolution
        if (!(sym instanceof Symbol.ClassSymbol)) {
            Symbol.ClassSymbol importedCs =
                nameResolver.resolveClassSymbol(cls.name(), cls.modulePath());
            if (importedCs != null) sym = importedCs;
        }
        if (!(sym instanceof Symbol.ClassSymbol cs)) {
            error(DiagnosticCode.E3004, "Unknown class '" + cls.name() + "'", obj.span());
            return Type.Error.INSTANCE;
        }

        List<ClassField> classFields = cs.fields();
        Map<String, Property> provided = new HashMap<>();
        for (Property prop : obj.properties()) {
            provided.put(prop.name(), prop);
        }

        Set<String> classFieldNames = new HashSet<>();
        for (ClassField cf : classFields) classFieldNames.add(cf.name());

        for (String propName : provided.keySet()) {
            if (!classFieldNames.contains(propName)) {
                error(DiagnosticCode.E4002,
                    "Extra field '" + propName + "' in class literal for '" + cls.name() + "'",
                    provided.get(propName).span());
            }
        }

        for (ClassField cf : classFields) {
            Property prop = provided.get(cf.name());
            Type fieldType = nameResolver.resolveTypeNode(cf.type());

            if (prop != null) {
                Type savedExpected = expectedType;
                expectedType = fieldType;
                Type valueType = checkExpression(prop.value());
                expectedType = savedExpected;
                if (valueType != Type.Error.INSTANCE) {
                    if (!isAssignable(fieldType, valueType)) {
                        error(DiagnosticCode.E4003,
                            "Type mismatch for field '" + cf.name() + "': expected "
                            + typeName(fieldType) + ", got " + typeName(valueType),
                            prop.span());
                    }
                }
            } else {
                if (!cf.optional() && cf.defaultExpr().isEmpty()) {
                    error(DiagnosticCode.E4001,
                        "Missing required field '" + cf.name()
                        + "' in class literal for '" + cls.name() + "'",
                        obj.span());
                }
            }
        }
        return cls;
    }

    // =======================================================================
    // Function expression
    // =======================================================================

    private Type checkFunctionExpr(FunctionExpr fe) {
        List<Type> paramTypes = new ArrayList<>();
        for (Parameter p : fe.params()) {
            paramTypes.add(nameResolver.resolveTypeNode(p.type()));
        }
        Optional<Type.Array> restType = fe.restParam()
            .map(rp -> (Type.Array) nameResolver.resolveTypeNode(rp.type()));
        Type returnType = nameResolver.resolveTypeNode(fe.returnType());
        Type.Func funcType = new Type.Func(paramTypes, restType, returnType, fe.isAsync());

        // F1: Check if Pass 1 recorded a scope for this function expression.
        // If so, use it instead of creating a fresh scope — this ensures
        // let-declarations from Pass 1 are visible.
        SymbolTable savedScope = currentScope;
        SymbolTable feScope = scopeMap.get(fe.body());
        if (feScope != null) {
            currentScope = feScope;
        } else {
            currentScope = currentScope.enterScope();

            // F6: Check for duplicate parameters before defining
            Set<String> paramNames = new HashSet<>();
            for (Parameter p : fe.params()) {
                if (paramNames.contains(p.name())) {
                    error(DiagnosticCode.E2002, "Duplicate parameter '" + p.name() + "'", p.span());
                    continue;
                }
                paramNames.add(p.name());
                Type pt = nameResolver.resolveTypeNode(p.type());
                currentScope.define(p.name(), new Symbol.VariableSymbol(p.name(), pt, true));
            }
            fe.restParam().ifPresent(rp -> {
                if (paramNames.contains(rp.name())) {
                    error(DiagnosticCode.E2002, "Duplicate parameter '" + rp.name() + "'", rp.span());
                    return;
                }
                paramNames.add(rp.name());
                Type rt = nameResolver.resolveTypeNode(rp.type());
                currentScope.define(rp.name(), new Symbol.VariableSymbol(rp.name(), rt, true));
            });
        }

        Type savedReturnType = currentReturnType;
        currentReturnType = returnType;

        // F1: Use walkStatement(fe.body()) instead of walkStatements(fe.body().statements())
        // so that the Block's scope is entered.
        walkStatement(fe.body());

        if (!Types.isNull(returnType)
                && !ReturnAnalysis.definitelyReturns(fe.body())) {
            error(DiagnosticCode.E5002,
                "Function expression must return a value on all paths", fe.span());
        }

        currentReturnType = savedReturnType;
        currentScope = savedScope;

        return funcType;
    }

    // =======================================================================
    // Has expression
    // =======================================================================

    private Type checkHas(HasExpr has) {
        Type objType = checkExpression(has.object());
        if (objType == Type.Error.INSTANCE) return Type.Error.INSTANCE;

        if (!(objType instanceof Type.Class cls)) {
            error(DiagnosticCode.E4005,
                "'has' argument must be a class field access, got " + typeName(objType),
                has.span());
            return Type.Error.INSTANCE;
        }

        Symbol sym = currentScope.resolve(cls.name());
        // If not found locally, try cross-module resolution
        if (!(sym instanceof Symbol.ClassSymbol)) {
            Symbol.ClassSymbol importedCs =
                nameResolver.resolveClassSymbol(cls.name(), cls.modulePath());
            if (importedCs != null) sym = importedCs;
        }
        if (!(sym instanceof Symbol.ClassSymbol cs)) {
            error(DiagnosticCode.E4005, "Class '" + cls.name() + "' not found", has.span());
            return Type.Error.INSTANCE;
        }

        ClassField field = IntrinsicResolvers.findField(cs.fields(), has.field());
        if (field == null) {
            error(DiagnosticCode.E4005,
                "Field '" + has.field() + "' not declared in class '" + cls.name() + "'",
                has.span());
            return Type.Error.INSTANCE;
        }
        if (!field.optional()) {
            error(DiagnosticCode.E4005,
                "'has' argument must be an optional class field; '" + has.field()
                + "' is required",
                has.span());
            return Type.Error.INSTANCE;
        }
        return Type.Boolean.INSTANCE;
    }

    // =======================================================================
    // Assignment expression
    // =======================================================================

    private Type checkAssignmentExpr(AssignmentExpr assign) {
        // F3 & F4: Determine target type first (with write-context mode for
        // table accesses), then use it as expectedType for the value.
        boolean savedMode = assignmentTargetMode;
        assignmentTargetMode = true;
        Type targetType = checkExpression(assign.target());
        assignmentTargetMode = savedMode;

        // F3: Use the target type as contextual expected type for the value
        Type savedExpected = expectedType;
        if (targetType != null && targetType != Type.Error.INSTANCE
                && !(targetType instanceof Type.Table)) {
            expectedType = targetType;
        }
        Type valueType = checkExpression(assign.value());
        expectedType = savedExpected;

        if (valueType == Type.Error.INSTANCE || targetType == Type.Error.INSTANCE) {
            return Type.Error.INSTANCE;
        }

        // For table write targets, no type checking needed (writes are unchecked)
        if (targetType instanceof Type.Table) {
            // Table write — always allowed
        } else if (!isAssignable(targetType, valueType)) {
            // F10: Detect reverse arity for E5004
            if (targetType instanceof Type.Func tf && valueType instanceof Type.Func af
                    && isReverseArity(af, tf)) {
                error(DiagnosticCode.E5004,
                    "Arity extension failed: actual function has more parameters ("
                    + af.paramTypes().size() + ") than target ("
                    + tf.paramTypes().size() + ")",
                    assign.span());
            } else {
                error(DiagnosticCode.E3001,
                    "Cannot assign " + typeName(valueType) + " to " + typeName(targetType),
                    assign.span());
            }
        }

        if (assign.target() instanceof IdentifierExpr id) {
            narrowing.onAssignment(id.name());
            if (insideTry) varsAssignedInTry.add(id.name());
        }

        return valueType;
    }

    // =======================================================================
    // Assignment compatibility
    // =======================================================================

    private void checkAssignment(Type targetType, Type exprType, Span span) {
        if (targetType == null || exprType == null) return;
        if (targetType == Type.Error.INSTANCE || exprType == Type.Error.INSTANCE) return;
        if (!isAssignable(targetType, exprType)) {
            // F10: Detect reverse arity for E5004
            if (targetType instanceof Type.Func tf && exprType instanceof Type.Func af
                    && isReverseArity(af, tf)) {
                error(DiagnosticCode.E5004,
                    "Arity extension failed: actual function has more parameters ("
                    + af.paramTypes().size() + ") than target ("
                    + tf.paramTypes().size() + ")",
                    span);
            } else {
                error(DiagnosticCode.E3001,
                    "Cannot assign " + typeName(exprType) + " to " + typeName(targetType),
                    span);
            }
        }
    }

    private boolean isAssignable(Type expected, Type actual) {
        if (Types.equals(expected, actual)) return true;
        if (expected instanceof Type.Nullable ne) {
            if (Types.equals(ne.inner(), actual)) return true;
            if (actual instanceof Type.Null) return true;
        }
        if (expected instanceof Type.Func ef && actual instanceof Type.Func af) {
            return Types.isAssignable(af, ef);
        }
        return false;
    }
    /**
     * F10: Detects the reverse arity case where the actual function has more
     * parameters than the target, but the overlapping params and return type
     * match. This should emit E5004 instead of E3001.
     */
    private static boolean isReverseArity(Type.Func actual, Type.Func target) {
        if (actual.restType().isPresent() || target.restType().isPresent()) return false;
        if (!Types.equals(actual.returnType(), target.returnType())) return false;
        int actualCount = actual.paramTypes().size();
        int targetCount = target.paramTypes().size();
        if (actualCount <= targetCount) return false;
        for (int i = 0; i < targetCount; i++) {
            if (!Types.equals(actual.paramTypes().get(i), target.paramTypes().get(i)))
                return false;
        }
        return true;
    }

    // =======================================================================
    // Type inference for let without annotation
    // =======================================================================

    private Type inferTypeFromExpression(ExpressionNode init, Type exprType) {
        if (exprType == Type.Error.INSTANCE) return null;
        return switch (init) {
            case LiteralExpr lit -> exprType;
            case ArrayLiteralExpr arr -> {
                if (arr.elements().isEmpty()) {
                    error(DiagnosticCode.E3002, "Cannot infer type of empty array literal", init.span());
                    yield null;
                }
                yield exprType;
            }
            case ObjectLiteralExpr obj -> {
                if (obj.properties().isEmpty()) {
                    error(DiagnosticCode.E3002, "Cannot infer type of empty object literal", init.span());
                    yield null;
                }
                yield exprType;
            }
            case CallExpr call -> exprType;
            case FunctionExpr fe -> exprType;
            case IdentifierExpr id -> exprType;
            case IndexExpr idx -> exprType;
            case UnaryExpr un -> exprType;
            case BinaryExpr bin -> exprType;
            case MemberAccessExpr mae -> {
                if (typeOf(mae.object()) instanceof Type.Table) {
                    error(DiagnosticCode.E3003,
                        "Table field read requires contextual target type", init.span());
                    yield null;
                }
                yield exprType;
            }
            case HasExpr has -> exprType;
            case TemplateLiteralExpr tl -> exprType;
            default -> {
                error(DiagnosticCode.E3002, "Cannot infer type of this expression", init.span());
                yield null;
            }
        };
    }

    // =======================================================================
    // Helpers
    // =======================================================================

    private Type declaredTypeOf(String varName) {
        Symbol sym = currentScope.resolve(varName);
        if (sym instanceof Symbol.VariableSymbol vs) {
            Type t = vs.type();
            if (t != null) return t;
        } else if (sym instanceof Symbol.FunctionSymbol fs) {
            return fs.funcType();
        } else if (sym instanceof Symbol.IntrinsicSymbol is) {
            return is.type();
        }
        return Type.Error.INSTANCE;
    }

    private Type getDeclaredType(Symbol sym) {
        return switch (sym) {
            case Symbol.VariableSymbol vs -> {
                Type t = vs.type();
                yield t != null ? t : Type.Error.INSTANCE;
            }
            case Symbol.FunctionSymbol fs -> fs.funcType();
            case Symbol.ClassSymbol cs ->
                Types.classType(cs.name(), cs.modulePath());
            case Symbol.ModuleSymbol ms -> Type.Table.INSTANCE;
            case Symbol.IntrinsicSymbol is -> is.type();
        };
    }

    private static boolean isNullableOf(Type maybeNullable, Type other) {
        return maybeNullable instanceof Type.Nullable && other instanceof Type.Null;
    }

    private String opSymbol(BinaryOp op) {
        return switch (op) {
            case ADD -> "+";
            case SUB -> "-";
            case MUL -> "*";
            case DIV -> "/";
            case MOD -> "%";
            case POW -> "**";
            case EQ -> "===";
            case NEQ -> "!==";
            case LT -> "<";
            case LTE -> "<=";
            case GT -> ">";
            case GTE -> ">=";
            case AND -> "&&";
            case OR -> "||";
        };
    }

    /**
     * Returns a human-readable name for a type for use in diagnostic messages.
     * Returns {@code "<error>"} for the internal error sentinel.
     */
    static String typeName(Type t) {
        if (t == null) return "null";
        return switch (t) {
            case Type.Null ignored -> "null";
            case Type.Boolean ignored -> "boolean";
            case Type.Int ignored -> "int";
            case Type.Number ignored -> "number";
            case Type.String ignored -> "string";
            case Type.Table ignored -> "table";
            case Type.Error ignored -> "<error>";
            case Type.Array a -> typeName(a.element()) + "[]";
            case Type.Nullable n -> typeName(n.inner()) + " | null";
            case Type.Class c -> c.name();
            case Type.Func f -> "function";
        };
    }

    private void error(DiagnosticCode code, String message, Span span) {
        diagnostics.add(Diagnostic.error(code, message,
            span.file(), span.startLine(), span.startColumn()));
    }
}
