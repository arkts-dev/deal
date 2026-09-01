package deal.test;

import deal.ast.*;
import deal.types.*;
import deal.diagnostics.CompilerDiagnostic;
import deal.diagnostics.DiagnosticCode;
import deal.diagnostics.DiagnosticRange;
import deal.diagnostics.RangeOrigin;
import java.util.List;
import java.util.Optional;

/**
 * Comprehensive unit tests for AST nodes and type representation.
 * Runs via main() using assertions. Enable with -ea JVM flag.
 */
public class AstAndTypesTest {

    // =========================================================================
    // Test runner
    // =========================================================================

    private static int passed = 0;
    private static int failed = 0;

    private static void check(boolean condition, String message) {
        if (condition) {
            passed++;
        } else {
            failed++;
            System.err.println("FAIL: " + message);
        }
    }

    private static void fail(String message) {
        failed++;
        System.err.println("FAIL: " + message);
    }

    private static <T extends Throwable> void assertThrows(
            Class<T> expectedType, Runnable action, String message) {
        try {
            action.run();
            fail(message + " — expected " + expectedType.getSimpleName() + " but no exception thrown");
        } catch (Throwable t) {
            if (expectedType.isInstance(t)) {
                passed++;
            } else {
                fail(message + " — expected " + expectedType.getSimpleName()
                     + " but got " + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }
    }

    // Helper: returns a ForInit as Object so instanceof checks can't be
    // compile-time optimized away
    private static Object asObject(ForInit fi) { return fi; }
    private static Object asObjectEither(Either<?, ?> e) { return e; }

    // =========================================================================
    // Tests
    // =========================================================================

    public static void main(String[] args) {
        System.out.println("=== Running AST and Type Representation Tests ===");

        testDiagnosticWarning();
        testSpan();
        testForInit();
        testEither();
        testAllAstNodes();
        testLiteralValues();
        testTypeCanonicalization();
        testNullableTypeInvariants();
        testTypeEquality();
        testArityExtension();
        testAsyncTypeEquality();
        testAsyncAssignability();
        testVisitor();

        System.out.println();
        System.out.println("Passed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    // -----------------------------------------------------------------------
    // Diagnostic factory tests
    // -----------------------------------------------------------------------

    @SuppressWarnings("deprecation")
    static void testDiagnosticWarning() {
        System.out.println("-- Diagnostic factory tests --");

        // Test warning via DiagnosticCode with a SOURCE range (preferred):
        // the range-carrying factory keeps the code/severity/message pins,
        // and the compatibility accessors derive from the range start.
        DiagnosticRange w1Range = new DiagnosticRange("test.deal", 3, 7, 3, 12,
            10, 15, 5, RangeOrigin.SOURCE);
        CompilerDiagnostic w1 = CompilerDiagnostic.warning(DiagnosticCode.E1001,
            "test warning", w1Range);
        check(w1.severity().equals("warning"),
            "warning severity should be 'warning'");
        check(w1.code().equals("E1001"),
            "warning code should be E1001");
        check(w1.message().equals("test warning"),
            "warning message preserved");
        check(w1.file().equals("test.deal"),
            "warning file preserved");
        check(w1.file().equals(w1Range.file())
                && w1.line() == w1Range.startLine()
                && w1.column() == w1Range.startColumn(),
            "warning accessors derive from the range start");
        check(w1.line() == 3,
            "warning line preserved: " + w1.line());
        check(w1.column() == 7,
            "warning column preserved: " + w1.column());
        check(w1.diagnosticCode() == DiagnosticCode.E1001,
            "warning diagnosticCode should be E1001");
        check(w1.range() == w1Range,
            "warning range preserved");

        // Test warning via string code (deprecated path)
        DiagnosticRange w2Range = new DiagnosticRange("test.deal", 5, 2, 5, 6,
            3, 7, 4, RangeOrigin.SOURCE);
        CompilerDiagnostic w2 = CompilerDiagnostic.warning("W0001",
            "string-code warning", w2Range);
        check(w2.severity().equals("warning"),
            "string-code warning severity should be 'warning'");
        check(w2.code().equals("W0001"),
            "string-code warning code preserved");
        check(w2.message().equals("string-code warning"),
            "string-code warning message preserved");
        check(w2.file().equals(w2Range.file())
                && w2.line() == w2Range.startLine()
                && w2.column() == w2Range.startColumn(),
            "string-code warning accessors derive from the range start");

        // Test that error() still produces 'error' severity (no regression)
        DiagnosticRange e1Range = new DiagnosticRange("test.deal", 1, 1, 1, 5,
            0, 4, 4, RangeOrigin.SOURCE);
        CompilerDiagnostic e1 = CompilerDiagnostic.error(DiagnosticCode.E1001,
            "test error", e1Range);
        check(e1.severity().equals("error"),
            "error severity should still be 'error'");
        check(e1.code().equals("E1001"),
            "error code preserved");
        check(e1.file().equals(e1Range.file())
                && e1.line() == e1Range.startLine()
                && e1.column() == e1Range.startColumn(),
            "error accessors derive from the range start");

        CompilerDiagnostic e2 = CompilerDiagnostic.error("E9999",
            "string error", e1Range);
        check(e2.severity().equals("error"),
            "string-code error severity should still be 'error'");

        // Test toString formatting (canonical formatter delegation): the
        // SEVERITY, code, and message substrings are pinned (D7).
        String ws = w1.toString();
        check(ws.contains("WARNING"),
            "warning toString contains WARNING: " + ws);
        check(ws.contains("E1001"),
            "warning toString contains code: " + ws);
        check(ws.contains("test warning"),
            "warning toString contains message: " + ws);

        String es = e1.toString();
        check(es.contains("ERROR"),
            "error toString contains ERROR: " + es);
        check(es.contains("E1001"),
            "error toString contains code: " + es);
        check(es.contains("test error"),
            "error toString contains message: " + es);
    }

    // -----------------------------------------------------------------------
    // Span tests
    // -----------------------------------------------------------------------

    static void testSpan() {
        System.out.println("-- Span --");

        Span s = new Span("test.deal", 1, 5, 1, 10);
        check(s.file().equals("test.deal"), "file");
        check(s.startLine() == 1, "startLine");
        check(s.startColumn() == 5, "startColumn");
        check(s.endLine() == 1, "endLine");
        check(s.endColumn() == 10, "endColumn");

        Span multi = new Span("test.deal", 2, 1, 4, 3);
        check(multi.startLine() == 2 && multi.endLine() == 4, "multi-line span");

        // Span validation
        assertThrows(IllegalArgumentException.class,
            () -> new Span("x", 0, 1, 1, 1), "line < 1");
        assertThrows(IllegalArgumentException.class,
            () -> new Span("x", 1, 0, 1, 1), "column < 1");
        assertThrows(IllegalArgumentException.class,
            () -> new Span("x", 2, 1, 1, 1), "end before start");
        assertThrows(NullPointerException.class,
            () -> new Span(null, 1, 1, 1, 1), "null file");

        check(Span.synthetic("mod.deal").file().equals("mod.deal"), "synthetic span");
    }

    // -----------------------------------------------------------------------
    // ForInit tests
    // -----------------------------------------------------------------------

    static void testForInit() {
        System.out.println("-- ForInit --");

        Span span = new Span("test.deal", 1, 1, 1, 10);
        IdentifierExpr id = new IdentifierExpr(span, "x");
        LiteralExpr lit = new LiteralExpr(span, new LiteralValue.IntLiteral(0));
        VariableDeclaration varDecl = new VariableDeclaration(span, "i",
            Optional.empty(), lit);

        ForInit.VarDecl vd = new ForInit.VarDecl(varDecl);
        AssignmentExpr assign = new AssignmentExpr(span, id, lit);
        ForInit.AssignExpr ae = new ForInit.AssignExpr(assign);

        // Distinct types (using Object to avoid compile-time instanceof optimization)
        Object vdObj = asObject(vd);
        Object aeObj = asObject(ae);
        check(vdObj instanceof ForInit.VarDecl, "VarDecl instanceof ForInit.VarDecl");
        check(aeObj instanceof ForInit.AssignExpr, "AssignExpr instanceof ForInit.AssignExpr");
        check(!(vdObj instanceof ForInit.AssignExpr), "VarDecl not instanceof AssignExpr");
        check(!(aeObj instanceof ForInit.VarDecl), "AssignExpr not instanceof VarDecl");

        // span() delegation
        check(vd.span() == varDecl.span(), "VarDecl span delegates to VariableDeclaration");
        check(ae.span() == assign.span(), "AssignExpr span delegates to AssignmentExpr");

        // Pattern matching via switch
        ForInit init = vd;
        String kind = switch (init) {
            case ForInit.VarDecl v -> "var";
            case ForInit.AssignExpr a -> "assign";
        };
        check(kind.equals("var"), "pattern match ForInit.VarDecl");

        init = ae;
        kind = switch (init) {
            case ForInit.VarDecl v -> "var";
            case ForInit.AssignExpr a -> "assign";
        };
        check(kind.equals("assign"), "pattern match ForInit.AssignExpr");
    }

    // -----------------------------------------------------------------------
    // Either tests
    // -----------------------------------------------------------------------

    static void testEither() {
        System.out.println("-- Either --");

        Span span = new Span("test.deal", 1, 1, 1, 1);
        Block block = new Block(span, List.of());
        IfStatement ifStmt = new IfStatement(span,
            new LiteralExpr(span, new LiteralValue.BooleanLiteral(true)),
            block, Optional.empty());

        Either.Left<IfStatement, Block> left = new Either.Left<>(ifStmt);
        Either.Right<IfStatement, Block> right = new Either.Right<>(block);

        // Distinct types (using Object)
        Object lObj = asObjectEither(left);
        Object rObj = asObjectEither(right);
        check(lObj instanceof Either.Left, "Left instanceof Left");
        check(rObj instanceof Either.Right, "Right instanceof Right");
        check(!(lObj instanceof Either.Right), "Left not instanceof Right");
        check(!(rObj instanceof Either.Left), "Right not instanceof Left");

        // Value extraction
        check(left.value() == ifStmt, "Left.value() returns wrapped IfStatement");
        check(right.value() == block, "Right.value() returns wrapped Block");

        // Pattern matching via switch
        Either<IfStatement, Block> e = left;
        String kind = switch (e) {
            case Either.Left<IfStatement, Block> l -> "left";
            case Either.Right<IfStatement, Block> r -> "right";
        };
        check(kind.equals("left"), "pattern match Left");

        e = right;
        kind = switch (e) {
            case Either.Left<IfStatement, Block> l -> "left";
            case Either.Right<IfStatement, Block> r -> "right";
        };
        check(kind.equals("right"), "pattern match Right");

        // IfStatement with else-if (Left)
        IfStatement withElseIf = new IfStatement(span,
            new LiteralExpr(span, new LiteralValue.BooleanLiteral(true)),
            block, Optional.of(new Either.Left<>(ifStmt)));
        check(withElseIf.elseBranch().isPresent(), "else-if present");
        check(withElseIf.elseBranch().get() instanceof Either.Left, "else-if is Left");

        // IfStatement with else-block (Right)
        IfStatement withElse = new IfStatement(span,
            new LiteralExpr(span, new LiteralValue.BooleanLiteral(true)),
            block, Optional.of(new Either.Right<>(block)));
        check(withElse.elseBranch().isPresent(), "else-block present");
        check(withElse.elseBranch().get() instanceof Either.Right, "else-block is Right");
    }

    // -----------------------------------------------------------------------
    // All AST node instantiation
    // -----------------------------------------------------------------------

    static void testAllAstNodes() {
        System.out.println("-- AST Node Instantiation --");

        Span span = new Span("test.deal", 1, 1, 1, 5);
        Span s2 = new Span("test.deal", 2, 1, 2, 5);

        IdentifierExpr id = new IdentifierExpr(span, "x");
        LiteralExpr lit0 = new LiteralExpr(span, new LiteralValue.IntLiteral(0));
        LiteralExpr litTrue = new LiteralExpr(span, new LiteralValue.BooleanLiteral(true));
        LiteralExpr litNull = new LiteralExpr(span, new LiteralValue.NullLiteral());
        Parameter param = new Parameter(span, "p", new NamedType(span, "int"));
        Block emptyBlock = new Block(span, List.of());

        // --- Statements ---
        ClassField field = new ClassField(span, "name", false, false,
            new NamedType(span, "string"), Optional.of(new LiteralExpr(span, new LiteralValue.StringLiteral(""))));
        ClassDeclaration classDecl = new ClassDeclaration(span, "User", List.of(field));
        check(classDecl.name().equals("User"), "ClassDeclaration");
        check(classDecl instanceof StatementNode, "ClassDeclaration is StatementNode");

        FunctionDeclaration funcDecl = new FunctionDeclaration(span, "f",
            List.of(param),
            new NamedType(span, "int"), emptyBlock, false, false);
        check(funcDecl.name().equals("f"), "FunctionDeclaration");
        check(funcDecl instanceof StatementNode, "FunctionDeclaration is StatementNode");

        VariableDeclaration varDecl = new VariableDeclaration(span, "x",
            Optional.of(new NamedType(span, "int")), lit0);
        check(varDecl.name().equals("x"), "VariableDeclaration");
        check(varDecl instanceof StatementNode, "VariableDeclaration is StatementNode");

        ReturnStatement ret = new ReturnStatement(span, Optional.of(lit0));
        check(ret.expr().isPresent(), "ReturnStatement with expr");
        ReturnStatement retVoid = new ReturnStatement(span, Optional.empty());
        check(retVoid.expr().isEmpty(), "ReturnStatement void");
        check(ret instanceof StatementNode, "ReturnStatement is StatementNode");

        IfStatement ifStmt = new IfStatement(span, litTrue, emptyBlock, Optional.empty());
        check(ifStmt.condition() == litTrue, "IfStatement");
        check(ifStmt instanceof StatementNode, "IfStatement is StatementNode");

        WhileStatement whileStmt = new WhileStatement(span, litTrue, emptyBlock);
        check(whileStmt.condition() == litTrue, "WhileStatement");
        check(whileStmt instanceof StatementNode, "WhileStatement is StatementNode");

        ForStatement forStmt = new ForStatement(span, Optional.empty(),
            Optional.of(litTrue), Optional.empty(), emptyBlock);
        check(forStmt.init().isEmpty(), "ForStatement no init");
        check(forStmt.condition().isPresent(), "ForStatement with condition");
        check(forStmt instanceof StatementNode, "ForStatement is StatementNode");

        // ForStatement with VarDecl init
        ForInit.VarDecl forInit = new ForInit.VarDecl(varDecl);
        ForStatement forStmt2 = new ForStatement(span, Optional.of(forInit),
            Optional.of(litTrue), Optional.empty(), emptyBlock);
        check(forStmt2.init().isPresent() && forStmt2.init().get() instanceof ForInit.VarDecl,
            "ForStatement with VarDecl init");

        // ForStatement with AssignExpr init
        ForInit.AssignExpr forInitAssign = new ForInit.AssignExpr(
            new AssignmentExpr(span, id, lit0));
        ForStatement forStmt3 = new ForStatement(span, Optional.of(forInitAssign),
            Optional.of(litTrue), Optional.empty(), emptyBlock);
        check(forStmt3.init().isPresent() && forStmt3.init().get() instanceof ForInit.AssignExpr,
            "ForStatement with AssignExpr init");

        BreakStatement breakStmt = new BreakStatement(span);
        check(breakStmt instanceof StatementNode, "BreakStatement is StatementNode");

        ContinueStatement continueStmt = new ContinueStatement(span);
        check(continueStmt instanceof StatementNode, "ContinueStatement is StatementNode");

        ExpressionStatement exprStmt = new ExpressionStatement(span, lit0);
        check(exprStmt.expr() == lit0, "ExpressionStatement");
        check(exprStmt instanceof StatementNode, "ExpressionStatement is StatementNode");

        ImportDeclaration importDecl = new ImportDeclaration(span, "m", "./module");
        check(importDecl.alias().equals("m"), "ImportDeclaration alias");
        check(importDecl.modulePath().equals("./module"), "ImportDeclaration path");
        check(importDecl instanceof StatementNode, "ImportDeclaration is StatementNode");

        ExportDeclaration exportDecl = new ExportDeclaration(span, funcDecl);
        check(exportDecl.declaration() == funcDecl, "ExportDeclaration");
        check(exportDecl instanceof StatementNode, "ExportDeclaration is StatementNode");

        DeleteStatement deleteStmt = new DeleteStatement(span,
            new MemberAccessExpr(span, id, "field"));
        check(deleteStmt.target() instanceof MemberAccessExpr, "DeleteStatement");
        check(deleteStmt instanceof StatementNode, "DeleteStatement is StatementNode");

        TryStatement tryStmt = new TryStatement(span, emptyBlock, "e",
            new Block(s2, List.of()));
        check(tryStmt.catchVar().equals("e"), "TryStatement catchVar");
        check(tryStmt instanceof StatementNode, "TryStatement is StatementNode");

        ThrowStatement throwStmt = new ThrowStatement(span, id);
        check(throwStmt.expr() == id, "ThrowStatement");
        check(throwStmt instanceof StatementNode, "ThrowStatement is StatementNode");
        // --- ForOfStatement ---
        ForOfStatement forOfStmt = new ForOfStatement(span, "x",
            new NamedType(span, "int"), id, emptyBlock);
        check(forOfStmt.varName().equals("x"), "ForOfStatement varName");
        check(forOfStmt.iterable() == id, "ForOfStatement iterable");
        check(forOfStmt.body() == emptyBlock, "ForOfStatement body");
        check(forOfStmt instanceof StatementNode, "ForOfStatement is StatementNode");


        Block block = new Block(span, List.of(varDecl, ret));
        check(block.statements().size() == 2, "Block with 2 statements");
        check(block instanceof StatementNode, "Block is StatementNode");

        // --- Expressions ---
        LiteralExpr litStr = new LiteralExpr(span, new LiteralValue.StringLiteral("hello"));
        check(litStr instanceof ExpressionNode, "LiteralExpr is ExpressionNode");

        IdentifierExpr id2 = new IdentifierExpr(span, "y");
        check(id2 instanceof ExpressionNode, "IdentifierExpr is ExpressionNode");

        BinaryExpr bin = new BinaryExpr(span, id, BinaryOp.ADD, id2);
        check(bin.op() == BinaryOp.ADD, "BinaryExpr ADD");
        check(bin instanceof ExpressionNode, "BinaryExpr is ExpressionNode");

        UnaryExpr un = new UnaryExpr(span, UnaryOp.NOT, litTrue);
        check(un.op() == UnaryOp.NOT, "UnaryExpr NOT");
        check(un instanceof ExpressionNode, "UnaryExpr is ExpressionNode");

        CallExpr call = new CallExpr(span, id, List.of(lit0, litTrue));
        check(call.args().size() == 2, "CallExpr with 2 args");
        check(call instanceof ExpressionNode, "CallExpr is ExpressionNode");

        MemberAccessExpr member = new MemberAccessExpr(span, id, "field");
        check(member.field().equals("field"), "MemberAccessExpr");
        check(member instanceof ExpressionNode, "MemberAccessExpr is ExpressionNode");

        IndexExpr index = new IndexExpr(span, id, lit0);
        check(index instanceof ExpressionNode, "IndexExpr is ExpressionNode");

        ArrayLiteralExpr arr = new ArrayLiteralExpr(span, List.of(lit0, lit0));
        check(arr.elements().size() == 2, "ArrayLiteralExpr with 2 elements");
        check(arr instanceof ExpressionNode, "ArrayLiteralExpr is ExpressionNode");

        ObjectLiteralExpr obj = new ObjectLiteralExpr(span,
            List.of(new Property(span, "key", litStr)));
        check(obj.properties().size() == 1, "ObjectLiteralExpr with 1 property");
        check(obj instanceof ExpressionNode, "ObjectLiteralExpr is ExpressionNode");

        FunctionExpr funcExpr = new FunctionExpr(span, List.of(),
            new NamedType(span, "null"), emptyBlock, false);
        check(funcExpr instanceof ExpressionNode, "FunctionExpr is ExpressionNode");

        HasExpr has = new HasExpr(span, id, "optField");
        check(has.field().equals("optField"), "HasExpr");
        check(has instanceof ExpressionNode, "HasExpr is ExpressionNode");

        AssignmentExpr assign = new AssignmentExpr(span, id, lit0);
        check(assign.target() == id && assign.value() == lit0, "AssignmentExpr");
        check(assign instanceof ExpressionNode, "AssignmentExpr is ExpressionNode");

        // --- TemplateLiteralExpr ---
        LiteralExpr helloPart = new LiteralExpr(span, new LiteralValue.StringLiteral("Hello "));
        LiteralExpr worldPart = new LiteralExpr(span, new LiteralValue.StringLiteral("!"));
        TemplateLiteralExpr template = new TemplateLiteralExpr(span,
            List.of(helloPart, id, worldPart));
        check(template.parts().size() == 3, "TemplateLiteralExpr with 3 parts");
        check(template.parts().get(0) == helloPart, "TemplateLiteralExpr part 0 is string");
        check(template.parts().get(1) == id, "TemplateLiteralExpr part 1 is expression");
        check(template.parts().get(2) == worldPart, "TemplateLiteralExpr part 2 is string");
        check(template instanceof ExpressionNode, "TemplateLiteralExpr is ExpressionNode");

        // Single-part template (no interpolation)
        TemplateLiteralExpr plain = new TemplateLiteralExpr(span,
            List.of(new LiteralExpr(span, new LiteralValue.StringLiteral("plain"))));
        check(plain.parts().size() == 1, "TemplateLiteralExpr plain has 1 part");
        check(plain instanceof ExpressionNode, "TemplateLiteralExpr plain is ExpressionNode");

        // --- Type nodes ---
        NamedType namedType = new NamedType(span, "int");
        check(namedType.name().equals("int"), "NamedType");
        check(namedType instanceof TypeNode, "NamedType is TypeNode");

        deal.ast.ArrayType arrayType = new deal.ast.ArrayType(span, namedType);
        check(arrayType.elementType() == namedType, "ArrayType");
        check(arrayType instanceof TypeNode, "ArrayType is TypeNode");

        deal.ast.NullableType nullableType = new deal.ast.NullableType(span, namedType);
        check(nullableType.innerType() == namedType, "NullableType");
        check(nullableType instanceof TypeNode, "NullableType is TypeNode");

        FunctionTypeParam ftp = new FunctionTypeParam(span, "p", namedType);
        deal.ast.FunctionType funcType = new deal.ast.FunctionType(span,
            List.of(ftp), namedType, false);
        check(funcType.params().size() == 1, "FunctionType with 1 param");
        check(funcType instanceof TypeNode, "FunctionType is TypeNode");

        // --- ProgramNode ---
        ProgramNode prog = new ProgramNode(span, List.of(varDecl, funcDecl));
        check(prog.statements().size() == 2, "ProgramNode with 2 statements");
        check(prog.span() == span, "ProgramNode span");

        // --- BinaryOp coverage ---
        for (BinaryOp op : BinaryOp.values()) {
            BinaryExpr be = new BinaryExpr(span, id, op, id2);
            check(be.op() == op, "BinaryOp." + op.name());
        }

        // --- UnaryOp coverage ---
        for (UnaryOp op : UnaryOp.values()) {
            UnaryExpr ue = new UnaryExpr(span, op, id);
            check(ue.op() == op, "UnaryOp." + op.name());
        }
    }

    // -----------------------------------------------------------------------
    // LiteralValue tests
    // -----------------------------------------------------------------------

    static void testLiteralValues() {
        System.out.println("-- LiteralValue --");

        LiteralValue.NullLiteral nl = new LiteralValue.NullLiteral();
        check(nl instanceof LiteralValue, "NullLiteral is LiteralValue");

        LiteralValue.BooleanLiteral bl = new LiteralValue.BooleanLiteral(true);
        check(bl.value() == true, "BooleanLiteral true");
        LiteralValue.BooleanLiteral bl2 = new LiteralValue.BooleanLiteral(false);
        check(bl2.value() == false, "BooleanLiteral false");

        LiteralValue.IntLiteral il = new LiteralValue.IntLiteral(42);
        check(il.value() == 42, "IntLiteral 42");
        LiteralValue.IntLiteral ilNeg = new LiteralValue.IntLiteral(-1);
        check(ilNeg.value() == -1, "IntLiteral -1");

        LiteralValue.NumberLiteral numl = new LiteralValue.NumberLiteral(3.14);
        check(numl.value() == 3.14, "NumberLiteral 3.14");
        LiteralValue.NumberLiteral nanl = new LiteralValue.NumberLiteral(Double.NaN);
        check(Double.isNaN(nanl.value()), "NumberLiteral NaN");

        LiteralValue.StringLiteral sl = new LiteralValue.StringLiteral("hello");
        check(sl.value().equals("hello"), "StringLiteral hello");
        LiteralValue.StringLiteral slEmpty = new LiteralValue.StringLiteral("");
        check(slEmpty.value().isEmpty(), "StringLiteral empty");

        // Pattern matching via switch
        LiteralValue lv = il;
        String kind = switch (lv) {
            case LiteralValue.NullLiteral n -> "null";
            case LiteralValue.BooleanLiteral b -> "bool";
            case LiteralValue.IntLiteral i -> "int";
            case LiteralValue.NumberLiteral n -> "number";
            case LiteralValue.StringLiteral s -> "string";
        };
        check(kind.equals("int"), "LiteralValue pattern match int");
    }

    // -----------------------------------------------------------------------
    // Type canonicalization
    // -----------------------------------------------------------------------

    static void testTypeCanonicalization() {
        System.out.println("-- Type Canonicalization --");

        // Rule 1-8: Primitives are identity
        check(Types.canonicalize(Type.Null.INSTANCE) == Type.Null.INSTANCE, "canonicalize null → null");
        check(Types.canonicalize(Type.Boolean.INSTANCE) == Type.Boolean.INSTANCE, "canonicalize boolean → boolean");
        check(Types.canonicalize(Type.Int.INSTANCE) == Type.Int.INSTANCE, "canonicalize int → int");
        check(Types.canonicalize(Type.Number.INSTANCE) == Type.Number.INSTANCE, "canonicalize number → number");
        check(Types.canonicalize(Type.String.INSTANCE) == Type.String.INSTANCE, "canonicalize string → string");
        check(Types.canonicalize(Type.Table.INSTANCE) == Type.Table.INSTANCE, "canonicalize table → table");
        check(Types.canonicalize(Type.Error.INSTANCE) == Type.Error.INSTANCE, "canonicalize Error → Error");

        // Rule 9: T[] → ArrayType(canonicalize(T))
        Type.Array arrInt = Types.array(Type.Int.INSTANCE);
        check(arrInt.element() == Type.Int.INSTANCE, "canonicalize int[]");
        Type.Array arrArr = Types.array(arrInt);
        check(Types.equals(arrArr.element(), arrInt), "canonicalize int[][]");

        // Rule 10: T | null → NullableType(canonicalize(T))
        Type.Nullable nullInt = Types.nullable(Type.Int.INSTANCE);
        check(nullInt.inner() == Type.Int.INSTANCE, "canonicalize int | null");

        // Rule 12: function type without rest
        Type.Func f1 = Types.func(List.of(Type.Int.INSTANCE), Type.Boolean.INSTANCE);
        check(f1.paramTypes().size() == 1, "func (int)=>boolean param count");
        check(f1.paramTypes().get(0) == Type.Int.INSTANCE, "func (int)=>boolean param type");
        check(f1.returnType() == Type.Boolean.INSTANCE, "func (int)=>boolean return type");
        // DEAL v1.2: function types carry no rest arm.

        // Class type (v1.2 identity carriage)
        Type.Class cls = IdentityTestFixtures.classType("User", "main");
        check(cls.name().equals("User"), "Class name");
        check(cls.identity().equals(
                IdentityTestFixtures.identityOf("main", "User")),
            "Class identity (module identity + class name)");
    }

    // -----------------------------------------------------------------------
    // NullableType invariants
    // -----------------------------------------------------------------------

    static void testNullableTypeInvariants() {
        System.out.println("-- NullableType Invariants --");

        // Nullable(null) → throws
        assertThrows(IllegalArgumentException.class,
            () -> new Type.Nullable(Type.Null.INSTANCE),
            "Nullable(Null) throws");

        // Nullable(Nullable(T)) → throws
        assertThrows(IllegalArgumentException.class,
            () -> new Type.Nullable(new Type.Nullable(Type.Int.INSTANCE)),
            "Nullable(Nullable(Int)) throws");

        // Valid Nullable
        Type.Nullable n = new Type.Nullable(Type.Int.INSTANCE);
        check(n.inner() == Type.Int.INSTANCE, "Nullable(Int) valid");
    }

    // -----------------------------------------------------------------------
    // Type equality
    // -----------------------------------------------------------------------

    static void testTypeEquality() {
        System.out.println("-- Type Equality --");

        // Primitives
        check(Types.equals(Type.Int.INSTANCE, Type.Int.INSTANCE), "int == int");
        check(!Types.equals(Type.Int.INSTANCE, Type.Number.INSTANCE), "int != number");
        check(!Types.equals(Type.Int.INSTANCE, Type.Null.INSTANCE), "int != null");

        // Array
        check(Types.equals(Types.array(Type.Int.INSTANCE), Types.array(Type.Int.INSTANCE)),
            "int[] == int[]");
        check(!Types.equals(Types.array(Type.Int.INSTANCE), Types.array(Type.Number.INSTANCE)),
            "int[] != number[]");

        // Nullable
        check(Types.equals(Types.nullable(Type.Int.INSTANCE), Types.nullable(Type.Int.INSTANCE)),
            "int|null == int|null");
        check(!Types.equals(Types.nullable(Type.Int.INSTANCE), Types.nullable(Type.Number.INSTANCE)),
            "int|null != number|null");

        // Class: nominal — canonical class-identity equality
        check(Types.equals(IdentityTestFixtures.classType("A", "mod"),
                IdentityTestFixtures.classType("A", "mod")),
            "Class A from mod == Class A from mod (equal identities)");
        check(!Types.equals(IdentityTestFixtures.classType("A", "mod"),
                IdentityTestFixtures.classType("B", "mod")),
            "Class A != Class B (same module identity)");
        check(!Types.equals(IdentityTestFixtures.classType("A", "mod1"),
                IdentityTestFixtures.classType("A", "mod2")),
            "Class A from mod1 != Class A from mod2 (distinct identities)");

        // Function
        Type.Func f1 = Types.func(List.of(Type.Int.INSTANCE), Type.Boolean.INSTANCE);
        Type.Func f2 = Types.func(List.of(Type.Int.INSTANCE), Type.Boolean.INSTANCE);
        check(Types.equals(f1, f2), "(int)=>boolean == (int)=>boolean");

        Type.Func f3 = Types.func(List.of(Type.Number.INSTANCE), Type.Boolean.INSTANCE);
        check(!Types.equals(f1, f3), "(int)=>boolean != (number)=>boolean");

        Type.Func f4 = Types.func(List.of(Type.Int.INSTANCE), Type.Int.INSTANCE);
        check(!Types.equals(f1, f4), "(int)=>boolean != (int)=>int");


        // Null-safe
        check(!Types.equals(null, Type.Int.INSTANCE), "null != int");
        check(!Types.equals(Type.Int.INSTANCE, null), "int != null");
    }

    // -----------------------------------------------------------------------
    // Arity extension
    // -----------------------------------------------------------------------

    static void testArityExtension() {
        System.out.println("-- Arity Extension --");

        Type.Func f1 = Types.func(List.of(Type.Int.INSTANCE), Type.Boolean.INSTANCE);
        // (int) => boolean

        Type.Func f2 = Types.func(List.of(Type.Int.INSTANCE, Type.Int.INSTANCE), Type.Boolean.INSTANCE);
        // (int, int) => boolean

        Type.Func f3 = Types.func(List.of(Type.Int.INSTANCE, Type.Int.INSTANCE, Type.Int.INSTANCE),
            Type.Boolean.INSTANCE);
        // (int, int, int) => boolean

        // Arity extension: actual fewer params → OK
        check(Types.isAssignable(f1, f2), "(int)=>boolean assignable to (int,int)=>boolean");
        check(Types.isAssignable(f1, f3), "(int)=>boolean assignable to (int,int,int)=>boolean");
        check(Types.isAssignable(f2, f3), "(int,int)=>boolean assignable to (int,int,int)=>boolean");

        // Reverse NOT assignable
        check(!Types.isAssignable(f2, f1), "(int,int)=>boolean NOT assignable to (int)=>boolean");
        check(!Types.isAssignable(f3, f2), "(int,int,int)=>boolean NOT assignable to (int,int)=>boolean");
        check(!Types.isAssignable(f3, f1), "(int,int,int)=>boolean NOT assignable to (int)=>boolean");

        // Exact match
        check(Types.isAssignable(f1, f1), "(int)=>boolean assignable to itself");

        // Return type mismatch
        Type.Func f1r = Types.func(List.of(Type.Int.INSTANCE), Type.Int.INSTANCE);
        check(!Types.isAssignable(f1, f1r), "return type mismatch: boolean vs int");

        // Param type mismatch
        Type.Func f1p = Types.func(List.of(Type.Number.INSTANCE), Type.Boolean.INSTANCE);
        check(!Types.isAssignable(f1p, f2), "param type mismatch: number vs int");

    }

    // -----------------------------------------------------------------------
    // Visitor tests
    // -----------------------------------------------------------------------

    static void testVisitor() {
        System.out.println("-- Visitor --");

        Span span = new Span("test.deal", 1, 1, 1, 5);
        IdentifierExpr id = new IdentifierExpr(span, "x");

        // Test that a visitor can be partially implemented — all methods have defaults
        Visitor<String> partialVisitor = new Visitor<>() {
            @Override
            public String visit(IdentifierExpr node) {
                return node.name();
            }
            @Override
            public String visit(LiteralExpr node) {
                return node.value().toString();
            }
        };

        check(partialVisitor.visit(id).equals("x"), "partial visitor handles IdentifierExpr");

        LiteralExpr lit = new LiteralExpr(span, new LiteralValue.IntLiteral(42));
        check(partialVisitor.visit(lit).equals("42"), "partial visitor handles LiteralExpr");

        // Unhandled node returns null (default)
        Block block = new Block(span, List.of());
        check(partialVisitor.visit(block) == null, "partial visitor returns null for unhandled Block");

        // Full visitor covering all methods — just verify it compiles and runs
        Visitor<String> fullVisitor = new Visitor<>() {
            @Override public String visit(ProgramNode n) { return "Program"; }
            @Override public String visit(ClassDeclaration n) { return "Class"; }
            @Override public String visit(FunctionDeclaration n) { return "Function"; }
            @Override public String visit(VariableDeclaration n) { return "Var"; }
            @Override public String visit(ReturnStatement n) { return "Return"; }
            @Override public String visit(IfStatement n) { return "If"; }
            @Override public String visit(WhileStatement n) { return "While"; }
            @Override public String visit(ForStatement n) { return "For"; }
            @Override public String visit(BreakStatement n) { return "Break"; }
            @Override public String visit(ContinueStatement n) { return "Continue"; }
            @Override public String visit(ExpressionStatement n) { return "ExprStmt"; }
            @Override public String visit(ImportDeclaration n) { return "Import"; }
            @Override public String visit(ExportDeclaration n) { return "Export"; }
            @Override public String visit(DeleteStatement n) { return "Delete"; }
            @Override public String visit(TryStatement n) { return "Try"; }
            @Override public String visit(ThrowStatement n) { return "Throw"; }
            @Override public String visit(Block n) { return "Block"; }
            @Override public String visit(LiteralExpr n) { return "Literal"; }
            @Override public String visit(IdentifierExpr n) { return "Ident"; }
            @Override public String visit(BinaryExpr n) { return "Binary"; }
            @Override public String visit(UnaryExpr n) { return "Unary"; }
            @Override public String visit(CallExpr n) { return "Call"; }
            @Override public String visit(MemberAccessExpr n) { return "Member"; }
            @Override public String visit(IndexExpr n) { return "Index"; }
            @Override public String visit(ArrayLiteralExpr n) { return "ArrayLit"; }
            @Override public String visit(ObjectLiteralExpr n) { return "ObjLit"; }
            @Override public String visit(FunctionExpr n) { return "FuncExpr"; }
            @Override public String visit(HasExpr n) { return "Has"; }
            @Override public String visit(AssignmentExpr n) { return "Assign"; }
            @Override public String visit(TemplateLiteralExpr n) { return "TemplateLiteral"; }
            @Override public String visit(ForOfStatement n) { return "ForOf"; }
            @Override public String visit(NamedType n) { return "NamedType"; }
            @Override public String visit(deal.ast.ArrayType n) { return "ASTArrayType"; }
            @Override public String visit(deal.ast.NullableType n) { return "ASTNullableType"; }
            @Override public String visit(deal.ast.FunctionType n) { return "ASTFuncType"; }
        };

        ProgramNode prog = new ProgramNode(span, List.of());
        check(fullVisitor.visit(prog).equals("Program"), "full visitor ProgramNode");

        check(fullVisitor.visit(new BreakStatement(span)).equals("Break"), "full visitor BreakStatement");
        check(fullVisitor.visit(new ContinueStatement(span)).equals("Continue"), "full visitor ContinueStatement");
        check(fullVisitor.visit(id).equals("Ident"), "full visitor IdentifierExpr");
        check(fullVisitor.visit(lit).equals("Literal"), "full visitor LiteralExpr");
    }

    // -----------------------------------------------------------------------
    // Async function type equality (ISSUE-0053)
    // -----------------------------------------------------------------------

    static void testAsyncTypeEquality() {
        System.out.println("-- Async Type Equality --");

        // Sync vs async: not equal
        Type.Func sync = Types.func(List.of(Type.Int.INSTANCE), Type.Boolean.INSTANCE);
        Type.Func async = Types.func(List.of(Type.Int.INSTANCE), Type.Boolean.INSTANCE, true);
        check(!Types.equals(sync, async), "sync != async (same params/return)");
        check(!Types.equals(async, sync), "async != sync (same params/return)");

        // Async vs async: equal when same
        Type.Func async2 = Types.func(List.of(Type.Int.INSTANCE), Type.Boolean.INSTANCE, true);
        check(Types.equals(async, async2), "async == async (same params/return)");

        // Async with different params: not equal
        Type.Func async3 = Types.func(List.of(Type.Number.INSTANCE), Type.Boolean.INSTANCE, true);
        check(!Types.equals(async, async3), "async(int)=>bool != async(number)=>bool");

        // Async with different return: not equal
        Type.Func async4 = Types.func(List.of(Type.Int.INSTANCE), Type.Int.INSTANCE, true);
        check(!Types.equals(async, async4), "async(int)=>bool != async(int)=>int");

        // Sync equals sync (regression)
        Type.Func sync2 = Types.func(List.of(Type.Int.INSTANCE), Type.Boolean.INSTANCE);
        check(Types.equals(sync, sync2), "sync == sync (same params/return)");
    }

    // -----------------------------------------------------------------------
    // Async function type assignability (ISSUE-0053)
    // -----------------------------------------------------------------------

    static void testAsyncAssignability() {
        System.out.println("-- Async Assignability --");

        Type.Func syncIntBool = Types.func(List.of(Type.Int.INSTANCE), Type.Boolean.INSTANCE);
        Type.Func asyncIntBool = Types.func(List.of(Type.Int.INSTANCE), Type.Boolean.INSTANCE, true);

        // Async to sync: NOT assignable
        check(!Types.isAssignable(asyncIntBool, syncIntBool),
            "async NOT assignable to sync");
        check(!Types.isAssignable(syncIntBool, asyncIntBool),
            "sync NOT assignable to async");

        // Async to async with arity extension
        Type.Func asyncOneArg = Types.func(List.of(Type.Int.INSTANCE), Type.Boolean.INSTANCE, true);
        Type.Func asyncTwoArg = Types.func(
            List.of(Type.Int.INSTANCE, Type.Int.INSTANCE), Type.Boolean.INSTANCE, true);
        check(Types.isAssignable(asyncOneArg, asyncTwoArg),
            "async(int)=>bool assignable to async(int,int)=>bool (arity extension)");

        // Async arity extension reverse: NOT assignable
        check(!Types.isAssignable(asyncTwoArg, asyncOneArg),
            "async(int,int)=>bool NOT assignable to async(int)=>bool");

        // Sync to same sync with arity extension (regression)
        Type.Func syncOneArg = Types.func(List.of(Type.Int.INSTANCE), Type.Boolean.INSTANCE);
        Type.Func syncTwoArg = Types.func(
            List.of(Type.Int.INSTANCE, Type.Int.INSTANCE), Type.Boolean.INSTANCE);
        check(Types.isAssignable(syncOneArg, syncTwoArg),
            "sync(int)=>bool assignable to sync(int,int)=>bool");
    }

}
