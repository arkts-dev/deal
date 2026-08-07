package deal.checker;

import deal.ast.*;
import deal.lexer.Diagnostic;
import deal.types.Type;
import deal.types.Types;

import java.util.*;

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
        void error(String code, String message, Span span);
    }

    private final Context ctx = new Context() {
        @Override public Type typeOf(ExpressionNode expr) {
            return TypeChecker.this.typeOf(expr);
        }
        @Override public Symbol resolveSymbol(String name) {
            return currentScope.resolve(name);
        }
        @Override public void error(String code, String message, Span span) {
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
            case BreakStatement bs       -> { /* no type checking needed */ }
            case ContinueStatement cs    -> { /* no type checking needed */ }
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
        Type.Func funcType = null;
        Symbol sym = rootTable.resolve(fd.name());
        if (sym instanceof Symbol.FunctionSymbol fs) {
            funcType = fs.funcType();
        }

        Type savedReturnType = currentReturnType;
        if (funcType != null) {
            currentReturnType = funcType.returnType();
        }

        // The function scope is already entered via walkStatement
        // Just check the body
        walkStatements(fd.body().statements());

        if (funcType != null) {
            Type retType = funcType.returnType();
            if (!(retType instanceof Type.Null)
                    && !ReturnAnalysis.definitelyReturns(fd.body())) {
                error("E5002",
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
                error("E5003",
                    "Return type mismatch: expected " + typeName(currentReturnType)
                    + ", got " + typeName(exprType),
                    rs.expr().get().span());
            }
        } else {
            if (currentReturnType != null && !(currentReturnType instanceof Type.Null)) {
                error("E5003",
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
        Type condType = checkExpression(is.condition());
        if (condType != Type.Error.INSTANCE && !(condType instanceof Type.Boolean)) {
            error("E3007", "If condition must be boolean, got " + typeName(condType),
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
        Type condType = checkExpression(ws.condition());
        if (condType != Type.Error.INSTANCE && !(condType instanceof Type.Boolean)) {
            error("E3007", "While condition must be boolean, got " + typeName(condType),
                ws.condition().span());
        }

        NullNarrowing savedNarrowing = narrowing;
        narrowing = new NullNarrowing();
        walkStatement(ws.body());
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

        fs.condition().ifPresent(cond -> {
            Type condType = checkExpression(cond);
            if (condType != Type.Error.INSTANCE && !(condType instanceof Type.Boolean)) {
                error("E3007", "For condition must be boolean, got " + typeName(condType),
                    cond.span());
            }
        });

        fs.update().ifPresent(this::checkExpression);

        NullNarrowing savedNarrowing = narrowing;
        narrowing = new NullNarrowing();
        walkStatement(fs.body());
        narrowing = savedNarrowing;
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
        checkExpression(ts.expr());
    }

    // =======================================================================
    // Delete statement
    // =======================================================================

    private void checkDeleteStatement(DeleteStatement ds) {
        Type targetType = checkExpression(ds.target());
        if (targetType instanceof Type.Error) return;

        if (ds.target() instanceof MemberAccessExpr mae) {
            Type objType = typeOf(mae.object());
            if (objType instanceof Type.Class cls) {
                Symbol sym = currentScope.resolve(cls.name());
                if (sym instanceof Symbol.ClassSymbol cs) {
                    ClassField field = IntrinsicResolvers.findField(cs.fields(), mae.field());
                    if (field != null && !field.optional()) {
                        error("E4004",
                            "Cannot delete required field '" + mae.field() + "'",
                            ds.span());
                    }
                }
            }
        }
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
            error("E2001", "Undeclared identifier '" + id.name() + "'", id.span());
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
            error("E3006",
                "Cannot compare " + typeName(leftType) + " with " + typeName(rightType),
                bin.span());
            return Type.Error.INSTANCE;
        }

        if (op == BinaryOp.AND || op == BinaryOp.OR) {
            if (!(leftType instanceof Type.Boolean)) {
                error("E3007", "Left operand of '" + opSymbol(op) + "' must be boolean, got "
                    + typeName(leftType), bin.left().span());
                return Type.Error.INSTANCE;
            }
            if (!(rightType instanceof Type.Boolean)) {
                error("E3007", "Right operand of '" + opSymbol(op) + "' must be boolean, got "
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
            error("E3007", "Invalid operand types for comparison: "
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
            error("E3010", "Invalid operand types for '+': "
                + typeName(leftType) + " and " + typeName(rightType), bin.span());
            return Type.Error.INSTANCE;
        }

        // Other arithmetic: types must match and be int or number
        if (!Types.equals(leftType, rightType)) {
            error("E3007", "Invalid operand types for '" + opSymbol(op) + "': "
                + typeName(leftType) + " and " + typeName(rightType), bin.span());
            return Type.Error.INSTANCE;
        }
        if (leftType instanceof Type.Int || leftType instanceof Type.Number) {
            return leftType;
        }

        error("E3007", "Invalid operand types for '" + opSymbol(op) + "': "
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
                    error("E3007", "Operand of '!' must be boolean, got "
                        + typeName(exprType), un.expr().span());
                    yield Type.Error.INSTANCE;
                }
                yield Type.Boolean.INSTANCE;
            }
            case NEG -> {
                if (!(exprType instanceof Type.Int) && !(exprType instanceof Type.Number)) {
                    error("E3007", "Operand of unary '-' must be int or number, got "
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

        List<Type> argTypes = new ArrayList<>();
        for (ExpressionNode arg : call.args()) {
            argTypes.add(checkExpression(arg));
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

        error("E3008", typeName(calleeType) + " is not callable", call.callee().span());
        return Type.Error.INSTANCE;
    }

    private Type checkFunctionCall(CallExpr call, Type.Func funcType, List<Type> argTypes) {
        int paramCount = funcType.paramTypes().size();
        boolean hasRest = funcType.restType().isPresent();

        if (!hasRest && argTypes.size() != paramCount) {
            error("E3009",
                "Function argument count mismatch: expected " + paramCount
                + ", got " + argTypes.size(),
                call.span());
            return Type.Error.INSTANCE;
        }
        if (hasRest && argTypes.size() < paramCount) {
            error("E3009",
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
                error("E5001",
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
                    error("E5001",
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

        // Class field access
        if (objType instanceof Type.Class cls) {
            Symbol sym = currentScope.resolve(cls.name());
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
                    error("E4002", "Field '" + field + "' not declared in class '"
                        + cls.name() + "'", mae.span());
                    return Type.Error.INSTANCE;
                }
            }
            return Type.Error.INSTANCE;
        }

        // Error type: .code and .message
        if (objType instanceof Type.Error) {
            if (field.equals("code") || field.equals("message")) {
                return Type.String.INSTANCE;
            }
            error("E4002", "Unknown field '" + field + "' on Error", mae.span());
            return Type.Error.INSTANCE;
        }

        // Table access — requires contextual type
        if (objType instanceof Type.Table) {
            if (expectedType != null && !(expectedType instanceof Type.Null)) {
                return expectedType;
            }
            error("E3003",
                "Table field read requires contextual target type", mae.span());
            return Type.Error.INSTANCE;
        }

        // Module symbol via identifier
        if (mae.object() instanceof IdentifierExpr id) {
            Symbol sym = currentScope.resolve(id.name());
            if (sym instanceof Symbol.ModuleSymbol ms) {
                Type exportType = ms.exports().get(field);
                if (exportType != null) return exportType;
                error("E2004", "Export '" + field + "' not found in module '"
                    + id.name() + "'", mae.span());
                return Type.Error.INSTANCE;
            }
        }

        error("E3003",
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
        if (!(indexType instanceof Type.Int)) {
            error("E3007", "Array index must be int, got " + typeName(indexType),
                idx.index().span());
            return Type.Error.INSTANCE;
        }
        if (arrayType instanceof Type.Array arr) {
            return arr.element();
        }
        error("E3007", "Cannot index type " + typeName(arrayType), idx.array().span());
        return Type.Error.INSTANCE;
    }

    // =======================================================================
    // Array literal
    // =======================================================================

    private Type checkArrayLiteral(ArrayLiteralExpr arr) {
        List<ExpressionNode> elements = arr.elements();
        if (elements.isEmpty()) {
            if (expectedType instanceof Type.Array expectedArr) return expectedArr;
            error("E3002", "Cannot infer type of empty array literal", arr.span());
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
                error("E3011",
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
            error("E3002", "Cannot infer type of empty object literal", obj.span());
            return Type.Error.INSTANCE;
        }
        return Type.Table.INSTANCE;
    }

    private Type checkClassConstruction(ObjectLiteralExpr obj, Type.Class cls) {
        Symbol sym = currentScope.resolve(cls.name());
        if (!(sym instanceof Symbol.ClassSymbol cs)) {
            error("E3004", "Unknown class '" + cls.name() + "'", obj.span());
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
                error("E4002",
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
                        error("E4003",
                            "Type mismatch for field '" + cf.name() + "': expected "
                            + typeName(fieldType) + ", got " + typeName(valueType),
                            prop.span());
                    }
                }
            } else {
                if (!cf.optional() && cf.defaultExpr().isEmpty()) {
                    error("E4001",
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
        Type.Func funcType = new Type.Func(paramTypes, restType, returnType);

        // Enter scope for parameters - but NameResolver didn't create a scope
        // for function expressions! We need to handle this locally.
        SymbolTable savedScope = currentScope;
        currentScope = currentScope.enterScope();

        // Define parameters in this scope
        for (Parameter p : fe.params()) {
            Type pt = nameResolver.resolveTypeNode(p.type());
            currentScope.define(p.name(), new Symbol.VariableSymbol(p.name(), pt, true));
        }
        fe.restParam().ifPresent(rp -> {
            Type rt = nameResolver.resolveTypeNode(rp.type());
            currentScope.define(rp.name(), new Symbol.VariableSymbol(rp.name(), rt, true));
        });

        Type savedReturnType = currentReturnType;
        currentReturnType = returnType;

        walkStatements(fe.body().statements());

        if (!(returnType instanceof Type.Null)
                && !ReturnAnalysis.definitelyReturns(fe.body())) {
            error("E5002",
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
            error("E4005",
                "'has' argument must be a class field access, got " + typeName(objType),
                has.span());
            return Type.Error.INSTANCE;
        }

        Symbol sym = currentScope.resolve(cls.name());
        if (!(sym instanceof Symbol.ClassSymbol cs)) {
            error("E4005", "Class '" + cls.name() + "' not found", has.span());
            return Type.Error.INSTANCE;
        }

        ClassField field = IntrinsicResolvers.findField(cs.fields(), has.field());
        if (field == null) {
            error("E4005",
                "Field '" + has.field() + "' not declared in class '" + cls.name() + "'",
                has.span());
            return Type.Error.INSTANCE;
        }
        if (!field.optional()) {
            error("E4005",
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
        Type valueType = checkExpression(assign.value());
        Type targetType = checkExpression(assign.target());

        if (valueType == Type.Error.INSTANCE || targetType == Type.Error.INSTANCE) {
            return Type.Error.INSTANCE;
        }

        if (!isAssignable(targetType, valueType)) {
            error("E3001",
                "Cannot assign " + typeName(valueType) + " to " + typeName(targetType),
                assign.span());
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
            error("E3001",
                "Cannot assign " + typeName(exprType) + " to " + typeName(targetType),
                span);
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

    // =======================================================================
    // Type inference for let without annotation
    // =======================================================================

    private Type inferTypeFromExpression(ExpressionNode init, Type exprType) {
        if (exprType == Type.Error.INSTANCE) return null;
        return switch (init) {
            case LiteralExpr lit -> exprType;
            case ArrayLiteralExpr arr -> {
                if (arr.elements().isEmpty()) {
                    error("E3002", "Cannot infer type of empty array literal", init.span());
                    yield null;
                }
                yield exprType;
            }
            case ObjectLiteralExpr obj -> {
                if (obj.properties().isEmpty()) {
                    error("E3002", "Cannot infer type of empty object literal", init.span());
                    yield null;
                }
                yield exprType;
            }
            case CallExpr call -> exprType;
            case FunctionExpr fe -> exprType;
            case IdentifierExpr id -> exprType;
            case UnaryExpr un -> exprType;
            case BinaryExpr bin -> exprType;
            case MemberAccessExpr mae -> {
                if (typeOf(mae.object()) instanceof Type.Table) {
                    error("E3003",
                        "Table field read requires contextual target type", init.span());
                    yield null;
                }
                yield exprType;
            }
            case HasExpr has -> exprType;
            default -> {
                error("E3002", "Cannot infer type of this expression", init.span());
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

    static String typeName(Type t) {
        if (t == null) return "null";
        return switch (t) {
            case Type.Null ignored -> "null";
            case Type.Boolean ignored -> "boolean";
            case Type.Int ignored -> "int";
            case Type.Number ignored -> "number";
            case Type.String ignored -> "string";
            case Type.Table ignored -> "table";
            case Type.Coroutine ignored -> "coroutine";
            case Type.Error ignored -> "Error";
            case Type.Array a -> typeName(a.element()) + "[]";
            case Type.Nullable n -> typeName(n.inner()) + " | null";
            case Type.Class c -> c.name();
            case Type.Func f -> "function";
        };
    }

    private void error(String code, String message, Span span) {
        diagnostics.add(new Diagnostic(code, "error", message,
            span.file(), span.startLine(), span.startColumn()));
    }
}
