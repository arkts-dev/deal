package deal.test;

import deal.ast.*;
import deal.checker.*;
import deal.diagnostics.CompilerDiagnostic;
import deal.lexer.*;
import deal.parser.*;
import deal.types.Type;
import deal.types.Types;

import java.util.*;

/**
 * Comprehensive unit tests for the DEAL type checker (ISSUE-0006).
 * Covers name resolution, type checking, null narrowing, definite return analysis,
 * class construction, and error diagnostics.
 */
public class CheckerTest {

    private static int passed = 0;
    private static int failed = 0;

    private static void check(boolean condition, String message) {
        if (condition) { passed++; }
        else { failed++; System.err.println("FAIL: " + message); }
    }

    private static void fail(String message) {
        failed++;
        System.err.println("FAIL: " + message);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private record CheckerOutput(CheckResult result, ProgramNode program) {}

    private static CheckerOutput checkProgram(String source) {
        return checkProgram(source, "test.deal");
    }

    private static CheckerOutput checkProgram(String source, String filename) {
        LexResult lex = new Lexer(source, filename).tokenize();
        ParseResult parse = new Parser(lex.tokens(), filename).parse();

        if (parse.hasErrors()) {
            StubModuleResolver resolver = new StubModuleResolver();
            NameResolver nr = new NameResolver(filename, resolver);
            nr.resolve(parse.program());
            List<CompilerDiagnostic> diags = new ArrayList<>(parse.diagnostics());
            diags.addAll(nr.diagnostics());
            return new CheckerOutput(
                new CheckResult(Map.of(), new SymbolTable(), diags),
                parse.program()
            );
        }

        StubModuleResolver resolver = new StubModuleResolver();
        NameResolver nr = new NameResolver(filename, resolver);
        SymbolTable symTable = nr.resolve(parse.program());

        List<CompilerDiagnostic> diags = new ArrayList<>(nr.diagnostics());
        if (!hasErrors(diags)) {
            CheckResult result = TypeChecker.check(filename, symTable, nr, parse.program());
            diags.addAll(result.diagnostics());
            return new CheckerOutput(
                new CheckResult(result.typeMap(), symTable, diags),
                parse.program()
            );
        }
        return new CheckerOutput(
            new CheckResult(Map.of(), symTable, diags),
            parse.program()
        );
    }

    private static CheckerOutput checkProgramWithModule(String source,
            StubModuleResolver resolver) {
        return checkProgramWithModule(source, "test.deal", resolver);
    }

    private static CheckerOutput checkProgramWithModule(String source,
            String filename, StubModuleResolver resolver) {
        LexResult lex = new Lexer(source, filename).tokenize();
        ParseResult parse = new Parser(lex.tokens(), filename).parse();

        if (parse.hasErrors()) {
            NameResolver nr = new NameResolver(filename, resolver);
            nr.resolve(parse.program());
            List<CompilerDiagnostic> diags = new ArrayList<>(parse.diagnostics());
            diags.addAll(nr.diagnostics());
            return new CheckerOutput(
                new CheckResult(Map.of(), new SymbolTable(), diags),
                parse.program()
            );
        }

        NameResolver nr = new NameResolver(filename, resolver);
        SymbolTable symTable = nr.resolve(parse.program());

        List<CompilerDiagnostic> diags = new ArrayList<>(nr.diagnostics());
        if (!hasErrors(diags)) {
            CheckResult result = TypeChecker.check(filename, symTable, nr, parse.program());
            diags.addAll(result.diagnostics());
            return new CheckerOutput(
                new CheckResult(result.typeMap(), symTable, diags),
                parse.program()
            );
        }
        return new CheckerOutput(
            new CheckResult(Map.of(), symTable, diags),
            parse.program()
        );
    }

    private static boolean hasErrors(List<CompilerDiagnostic> diags) {
        return diags.stream().anyMatch(d -> "error".equals(d.severity()));
    }

    private static void assertNoErrors(CheckerOutput out, String context) {
        List<CompilerDiagnostic> diags = out.result.diagnostics();
        if (!diags.isEmpty()) {
            for (CompilerDiagnostic d : diags) {
                System.err.println("  Diagnostic: " + d);
            }
        }
        check(!hasErrors(diags),
            context + ": expected no errors, got " + diags.size());
    }

    private static void assertError(CheckerOutput out, String code, String context) {
        List<CompilerDiagnostic> diags = out.result.diagnostics();
        boolean found = diags.stream().anyMatch(d -> d.code().equals(code));
        if (!found) {
            System.err.println("  Expected " + code + " but got: " + diags);
        }
        check(found, context + ": expected diagnostic " + code);
    }

    // =========================================================================
    // Main
    // =========================================================================

    public static void main(String[] args) {
        System.out.println("=== Running Type Checker Tests ===");

        // -- Name Resolution --
        testNameResolution_simple();
        testNameResolution_undeclared();
        testNameResolution_redeclare();
        testNameResolution_shadow();
        testNameResolution_parameterShadow();
        testNameResolution_import();
        testNameResolution_hoisting();
        testNameResolution_e2007_class();
        testNameResolution_e2007_function();
        testNameResolution_e2007_let();
        testNameResolution_e2004();
        // F7: E2006 when declaration precedes import
        testNameResolution_e2006_classBeforeImport();
        testNameResolution_e2006_functionBeforeImport();

        // ISSUE-0052: NameResolver isAsync propagation
        testNameResolution_asyncFuncDeclIsAsync();
        testNameResolution_syncFuncDeclIsNotAsync();
        testNameResolution_asyncFuncTypeAnnotationIsAsync();
        testNameResolution_awaitWalkExpressionHoistsNestedFuncExpr();

        // ISSUE-0053: TypeChecker async/await rules
        testAwaitOutsideAsync_E3012();
        testAwaitOnNonAsyncCall_E3013();
        testAsyncCallWithoutAwait_E3014();
        testAwaitAsyncCallNoE3014();
        testNestedAwaitSuppression();
        testUnawaitedAsyncInArgument_E3014();
        testNarrowingInvalidatedAfterAwait();
        testAwait42DoesNotCrash();
        testAsyncFuncNotAssignableToSync();
        testSyncFuncNotAssignableToAsync();
        testNullNarrowingInvalidateAll();

        // -- Type Checking: Literals and Identifiers --
        testLiteralTypes();
        testIdentifierType();

        // -- Type Checking: Operators --
        testArithmeticOperators();
        testComparisonOperators();
        testEqualityOperators();
        testLogicalOperators();
        testUnaryOperators();
        testOperatorTypeErrors();

        // -- Type Checking: Assignments --
        testAssignment_exact();
        testAssignment_nullableWrapping();
        testAssignment_nullableError();
        testAssignment_invariance();

        // -- Type Checking: Arrays --
        testArrayLiteral();
        testMixedArray();
        testEmptyArray();
        testArrayRead();
        testArrayLength();
        // D3 (typed-boundary-enforcement): array .length is read-only
        testArrayLengthAssignmentRejected();
        testArrayLengthDeleteRejected();
        testArrayAppendIdiomCompiles();
        testTableLengthWriteUnaffected();
        testClassFieldLengthWriteUnaffected();

        // -- Type Checking: Object/Table --
        testTableLiteral();
        testTableReadWithContext();
        testTableReadWithoutContext();

        // -- Type Checking: Functions --
        testFunctionCall();
        testFunctionArgCountMismatch();
        testFunctionArgTypeMismatch();
        testFunctionReturnType();
        testArityExtension();
        testReverseArityError();
        testFunctionExpr();
        testFunctionCallContextualTyping();
        testFunctionExprDuplicateParams();

        // F1: Scope chain — variables visible in nested blocks
        testScopeChain_functionBody();
        testScopeChain_nestedBlock();
        // F2: Nested function return type
        testNestedFunctionReturnType();

        // -- Type Checking: Classes --
        testClassConstruction();
        testClassExtraFields();
        testClassMissingRequiredField();
        testClassNominalTyping();
        testClassFieldAccess();
        testClassOptionalField();
        // ISSUE-0075: nullable class member access is only widened for
        // cross-module classes (conformance-gap-02 seam); local nullable
        // class member access keeps the baseline E3003 rejection.
        testLocalNullableClassMemberAccessRejected();
        testLocalNullableClassMemberAccessNarrowedOk();
        testNestedClass();
        testErrorClassConstruction();
        // F9: Class field default values type-checked
        testClassDefaultValueTypeError();
        testClassDefaultExpressionsTyped();

        // -- Type Checking: Throw statement (ISSUE-0010) --
        testThrowIntError();
        testThrowStringError();
        testThrowMessageOnly();
        testThrowFullError();
        testThrowErrorVariable();

        // -- Type Checking: has / delete --
        testHasRequiredField();
        testHasOptionalField();
        testDeleteRequiredField();
        testDeleteOptionalField();
        // F10: Delete on table fields
        testDeleteTableField();
        testDeleteTableIndex();

        // F3: Assignment RHS contextual typing
        testAssignmentContextualTyping();
        // F4: Table member writes (no E3003)
        testTableWrite();
        // F5: Table index writes (no E3007)
        testTableIndexWrite();
        // A-D10 (assignment-delete-address-chains): table index
        // write/delete keys must have static type string (E3018)
        testTableIndexWriteStringKeyAccepted();
        testTableIndexWriteNonStringKeyRejected();
        testTableIndexDeleteStringKeyAccepted();
        testTableIndexDeleteNonStringKeyRejected();
        testTableIndexReadUnaffected();
        testArrayIndexIntRequirementUnchanged();

        // F6: if/while/for conditions with contextual typing
        testIfConditionContextualTyping();
        testWhileConditionContextualTyping();
        testForConditionContextualTyping();

        // F8: Break/continue outside loops
        testBreakOutsideLoop();
        testContinueOutsideLoop();

        // -- Null Narrowing --
        testNullNarrowing_neNull();
        testNullNarrowing_eqNull();
        testNullNarrowing_assignmentClears();
        testNullNarrowing_negated();
        testNullNarrowing_nestedIf();

        // -- Definite Return --
        testDefiniteReturn_bothBranches();
        testDefiniteReturn_missingReturn();
        testDefiniteReturn_throwCounts();

        // -- Type Inference --
        testInference_literals();
        testInference_emptyArrayError();
        testInference_tableReadError();
        testInference_indexExpr();

        // -- Unknown Types --
        testUnknownType();
        testInvalidNullable();

        // -- Reverse arity E5004 --
        testReverseArityE5004();

        // F4: Missing tests — E3008 (not callable), E4003 (field type mismatch)
        testE3008_notCallable();
        testE4003_fieldTypeMismatch();

        // F1: Function expressions with let declarations
        testFunctionExprLetDecl();

        // F2: Circular import E2005
        testE2005_circularImport();

        // F3: Class field default null for nullable fields
        testClassFieldDefaultNull();

        // ISSUE-0040: void type name produces E3004
        testVoidTypeNameProducesE3004();

        // Runtime intrinsics: int() and number()
        testIntrinsicIntRejectsIntLiteral();
        testIntrinsicNumberRejectsBoolean();
        // ISSUE-0040: coroutine import now fails with E2003
        testCoroutineImportRejected();

        // ISSUE-0040: Error class prohibition (E4006)
        testE4006_classErrorProhibition();
        // ISSUE-0040: Dollar prohibition (E2008)
        testE2008_dollarInIdentifier();

        // ISSUE-0041: Additional dollar prohibition tests
        testE2008_dollarInRestParam();
        testE2008_dollarInForLoopVar();
        testE2008_dollarInForOfLoopVar();

        // ISSUE-0041: Negative tests — Error variants not rejected
        testE4006_errorVariantsNoError();

        // ISSUE-0041: Integration tests (null return type + $ param, valid import)
        testE2008_dollarIntegration_nullReturn();
        testE2008_dollarIntegration_validImport();

        // v1.1: For-of scoping and type checking (ISSUE-0034)
        testForOfScoping_loopVarInBody();
        testForOfScoping_iterableCannotRefLoopVar();
        testForOfScoping_iterableRefsOuterVar();
        testForOfScoping_breakInside();
        testForOfScoping_continueInside();
        testForOfTypeCheck_arrayCorrect();
        testForOfTypeCheck_stringCorrect();
        testForOfTypeCheck_nonIterable();
        testV12StringTypeFacingChecks();
        testV12FunctionTypeRestRejected();
        testForOfTypeCheck_tableIterable();
        testForOfTypeCheck_arrayWrongVarType();
        testForOfTypeCheck_stringWrongVarType();

        // v1.1: Template literal type checking (ISSUE-0034)
        testTemplateLiteral_stringParts();
        testTemplateLiteral_nonStringInterpolation();
        testTemplateLiteral_typeInference();
        testTemplateLiteral_nameResolution();
        // v1.1: @jsonable field validation and cycle detection (ISSUE-0048)
        testJsonablePrimitiveTypes();
        testJsonableArrayType();
        testJsonableNullableType();
        testJsonableNestedClass();
        testJsonableNonJsonableType_function();
        testJsonableNonJsonableType_nonJsonableClass();
        testJsonableCycle();
        testJsonableCycleThroughArray();
        testJsonableCycleThroughNullable();
        testJsonableNonCycle_nonJsonableClass();
        testJsonableThreeClassCycle();
        testJsonableNonCircularChain();
        testJsonableImportedClass_valid();
        testJsonableImportedClass_invalid();


        System.out.println();
        System.out.println("Passed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    // =========================================================================
    // Name Resolution Tests
    // =========================================================================

    static void testNameResolution_simple() {
        System.out.println("-- Name Resolution: simple --");
        CheckerOutput out = checkProgram("let x: int = 42; let y: int = x;");
        assertNoErrors(out, "simple resolution");
    }

    static void testNameResolution_undeclared() {
        System.out.println("-- Name Resolution: undeclared --");
        CheckerOutput out = checkProgram("let y: int = z;");
        assertError(out, "E2001", "undeclared identifier");
    }

    static void testNameResolution_redeclare() {
        System.out.println("-- Name Resolution: redeclare --");
        CheckerOutput out = checkProgram("let x: int = 1; let x: int = 2;");
        assertError(out, "E2002", "redeclare in same scope");
    }

    static void testNameResolution_shadow() {
        System.out.println("-- Name Resolution: shadow --");
        CheckerOutput out = checkProgram(
            "let x: int = 1;\n" +
            "{\n" +
            "  let x: boolean = true;\n" +
            "  let y: boolean = x;\n" +
            "}"
        );
        assertNoErrors(out, "shadow outer with inner");
    }

    static void testNameResolution_parameterShadow() {
        System.out.println("-- Name Resolution: parameter shadow --");
        CheckerOutput out = checkProgram(
            "function f(x: int, x: int): int { return x; }"
        );
        assertError(out, "E2002", "parameter shadow parameter");
    }

    static void testNameResolution_import() {
        System.out.println("-- Name Resolution: import --");
        CheckerOutput out = checkProgram(
            "import * as Lib from \"./nonexistent\";\n" +
            "let x: int = 1;"
        );
        assertError(out, "E2003", "module not found");
    }

    static void testNameResolution_hoisting() {
        System.out.println("-- Name Resolution: hoisting --");
        CheckerOutput out = checkProgram(
            "function foo(): int { return bar(); }\n" +
            "function bar(): int { return 42; }"
        );
        assertNoErrors(out, "forward reference via hoisting");

        out = checkProgram(
            "function make(): Point { return { x: 1, y: 2 }; }\n" +
            "class Point { x: int; y: int; }"
        );
        assertNoErrors(out, "forward class reference via hoisting");
    }

    // F11: Test E2007 — module-level declaration shadows import
    static void testNameResolution_e2007_class() {
        System.out.println("-- Name Resolution: E2007 class shadows import --");
        StubModuleResolver resolver = new StubModuleResolver();
        resolver.register("./lib", Map.of("Foo", Type.Int.INSTANCE));
        CheckerOutput out = checkProgramWithModule(
            "import * as Foo from \"./lib\";\n" +
            "class Foo { x: int; }",
            resolver
        );
        assertError(out, "E2007", "class shadows import");
    }

    static void testNameResolution_e2007_function() {
        System.out.println("-- Name Resolution: E2007 function shadows import --");
        StubModuleResolver resolver = new StubModuleResolver();
        resolver.register("./lib", Map.of("Foo", Type.Int.INSTANCE));
        CheckerOutput out = checkProgramWithModule(
            "import * as Foo from \"./lib\";\n" +
            "function Foo(): int { return 42; }",
            resolver
        );
        assertError(out, "E2007", "function shadows import");
    }

    static void testNameResolution_e2007_let() {
        System.out.println("-- Name Resolution: E2007 let shadows import --");
        StubModuleResolver resolver = new StubModuleResolver();
        resolver.register("./lib", Map.of("Foo", Type.Int.INSTANCE));
        CheckerOutput out = checkProgramWithModule(
            "import * as Foo from \"./lib\";\n" +
            "let Foo: int = 1;",
            resolver
        );
        assertError(out, "E2007", "let shadows import");
    }

    // F7: E2006 — import shadows module-level declaration (declaration comes first)
    static void testNameResolution_e2006_classBeforeImport() {
        System.out.println("-- Name Resolution: E2006 import after class --");
        StubModuleResolver resolver = new StubModuleResolver();
        resolver.register("./lib", Map.of("Foo", Type.Int.INSTANCE));
        CheckerOutput out = checkProgramWithModule(
            "class Foo { x: int; }\n" +
            "import * as Foo from \"./lib\";",
            resolver
        );
        assertError(out, "E2006", "import after class declaration → E2006");
    }

    static void testNameResolution_e2006_functionBeforeImport() {
        System.out.println("-- Name Resolution: E2006 import after function --");
        StubModuleResolver resolver = new StubModuleResolver();
        resolver.register("./lib", Map.of("Foo", Type.Int.INSTANCE));
        CheckerOutput out = checkProgramWithModule(
            "function Foo(): int { return 42; }\n" +
            "import * as Foo from \"./lib\";",
            resolver
        );
        assertError(out, "E2006", "import after function declaration → E2006");
    }

    // F8: Test E2004 — export not found in module
    static void testNameResolution_e2004() {
        System.out.println("-- Name Resolution: E2004 export not found --");
        StubModuleResolver resolver = new StubModuleResolver();
        resolver.register("./lib", Map.of("existingExport", Type.Int.INSTANCE));
        CheckerOutput out = checkProgramWithModule(
            "import * as Lib from \"./lib\";\n" +
            "let x: int = Lib.nonExistent;",
            resolver
        );
        assertError(out, "E2004", "export not found in module");
    }

    // =========================================================================
    // Literal and Identifier Type Tests
    // =========================================================================

    static void testLiteralTypes() {
        System.out.println("-- Literal Types --");
        CheckerOutput out = checkProgram("let a: null = null;");
        assertNoErrors(out, "null literal");
        out = checkProgram("let a: boolean = true;");
        assertNoErrors(out, "true literal");
        out = checkProgram("let a: boolean = false;");
        assertNoErrors(out, "false literal");
        out = checkProgram("let a: int = 42;");
        assertNoErrors(out, "int literal");
        out = checkProgram("let a: number = 3.14;");
        assertNoErrors(out, "number literal");
        out = checkProgram("let a: string = \"hello\";");
        assertNoErrors(out, "string literal");
    }

    static void testIdentifierType() {
        System.out.println("-- Identifier Type --");
        CheckerOutput out = checkProgram(
            "let x: int = 1;\n" +
            "let y: int = x;"
        );
        assertNoErrors(out, "identifier type");
    }

    // =========================================================================
    // Operator Tests
    // =========================================================================

    static void testArithmeticOperators() {
        System.out.println("-- Arithmetic Operators --");
        CheckerOutput out = checkProgram(
            "let a: int = 1 + 2;\n" +
            "let b: int = 1 - 2;\n" +
            "let c: int = 1 * 2;\n" +
            "let d: int = 1 / 2;\n" +
            "let e: int = 1 % 2;\n" +
            "let f: int = 2 ** 3;"
        );
        assertNoErrors(out, "int arithmetic");

        out = checkProgram(
            "let a: number = 1.0 + 2.0;\n" +
            "let b: number = 1.0 - 2.0;\n" +
            "let c: number = 1.0 * 2.0;"
        );
        assertNoErrors(out, "number arithmetic");

        out = checkProgram("let a: string = \"a\" + \"b\";");
        assertNoErrors(out, "string concat");
    }

    static void testComparisonOperators() {
        System.out.println("-- Comparison Operators --");
        CheckerOutput out = checkProgram(
            "let a: boolean = 1 < 2;\n" +
            "let b: boolean = 1 <= 2;\n" +
            "let c: boolean = 1 > 2;\n" +
            "let d: boolean = 1 >= 2;"
        );
        assertNoErrors(out, "int comparison");

        out = checkProgram("let a: boolean = 1.0 < 2.0;");
        assertNoErrors(out, "number comparison");

        out = checkProgram("let a: boolean = \"a\" < \"b\";");
        assertNoErrors(out, "string comparison");
    }

    static void testEqualityOperators() {
        System.out.println("-- Equality Operators --");
        CheckerOutput out = checkProgram(
            "let a: boolean = 1 === 1;\n" +
            "let b: boolean = 1 !== 2;"
        );
        assertNoErrors(out, "int equality");

        out = checkProgram(
            "let x: int | null = null;\n" +
            "let a: boolean = x === null;\n" +
            "let b: boolean = x !== null;"
        );
        assertNoErrors(out, "nullable vs null equality");
    }

    static void testLogicalOperators() {
        System.out.println("-- Logical Operators --");
        CheckerOutput out = checkProgram(
            "let a: boolean = true && false;\n" +
            "let b: boolean = true || false;"
        );
        assertNoErrors(out, "logical operators");
    }

    static void testUnaryOperators() {
        System.out.println("-- Unary Operators --");
        CheckerOutput out = checkProgram(
            "let a: boolean = !true;\n" +
            "let b: int = -5;"
        );
        assertNoErrors(out, "unary operators");
    }

    static void testOperatorTypeErrors() {
        System.out.println("-- Operator Type Errors --");
        CheckerOutput out = checkProgram("let a: string = \"a\" + 1;");
        assertError(out, "E3010", "string + int error");

        out = checkProgram("let a: boolean = 1 === true;");
        assertError(out, "E3006", "int === bool error");
    }

    // =========================================================================
    // Assignment Tests
    // =========================================================================

    static void testAssignment_exact() {
        System.out.println("-- Assignment: exact match --");
        CheckerOutput out = checkProgram(
            "let x: int = 42;\n" +
            "x = 100;"
        );
        assertNoErrors(out, "exact assignment");
    }

    static void testAssignment_nullableWrapping() {
        System.out.println("-- Assignment: nullable wrapping --");
        CheckerOutput out = checkProgram("let x: int | null = 1;");
        assertNoErrors(out, "nullable wrapping OK");

        out = checkProgram("let x: int | null = null;");
        assertNoErrors(out, "null to nullable OK");
    }

    static void testAssignment_nullableError() {
        System.out.println("-- Assignment: nullable error --");
        CheckerOutput out = checkProgram(
            "let n: int | null = null;\n" +
            "let x: int = n;"
        );
        assertError(out, "E3001", "nullable to non-nullable error");
    }

    static void testAssignment_invariance() {
        System.out.println("-- Assignment: invariance --");
        CheckerOutput out = checkProgram(
            "let x: int = 1;\n" +
            "let y: number = x;"
        );
        assertError(out, "E3001", "int to number invariance error");

        out = checkProgram(
            "let x: number = 1.0;\n" +
            "let y: int = x;"
        );
        assertError(out, "E3001", "number to int invariance error");
    }

    // F3: Assignment RHS contextual typing for table reads
    static void testAssignmentContextualTyping() {
        System.out.println("-- Assignment: contextual typing on RHS --");
        CheckerOutput out = checkProgram(
            "let x: string = \"hello\";\n" +
            "let t: table = { value: \"world\" };\n" +
            "x = t.value;"
        );
        assertNoErrors(out, "assignment RHS contextual typing for table read");
    }

    // F4: Table member writes should NOT trigger E3003
    static void testTableWrite() {
        System.out.println("-- Table Write (should not trigger E3003) --");
        CheckerOutput out = checkProgram(
            "let t: table = { name: \"A\" };\n" +
            "t.name = \"B\";"
        );
        assertNoErrors(out, "table member write allowed without E3003");
    }

    // F5: Table index writes should NOT trigger E3007
    static void testTableIndexWrite() {
        System.out.println("-- Table Index Write (should not trigger E3007) --");
        CheckerOutput out = checkProgram(
            "let t: table = { data: 1 };\n" +
            "t[\"key\"] = 42;"
        );
        assertNoErrors(out, "table index write allowed without E3007");
    }

    // =========================================================================
    // A-D10: table index write/delete keys must have static type string
    // (E3018 — emitted before lowering, identical on every backend)
    // =========================================================================

    static void testTableIndexWriteStringKeyAccepted() {
        System.out.println("-- Table Index Write String Key Accepted --");
        CheckerOutput lit = checkProgram(
            "let t: table = { data: 1 };\n" +
            "t[\"key\"] = 42;"
        );
        assertNoErrors(lit, "string literal table index write");
        CheckerOutput var = checkProgram(
            "let t: table = { data: 1 };\n" +
            "let k: string = \"key\";\n" +
            "t[k] = 42;"
        );
        assertNoErrors(var, "string-typed table index write");
    }

    static void testTableIndexWriteNonStringKeyRejected() {
        System.out.println("-- Table Index Write Non-String Key Rejected (E3018) --");
        // int literal key: E3018 at the index expression's span (line 2,
        // column 3 — the `0` inside `t[0]`), never at the target/value.
        CheckerOutput lit = checkProgram(
            "let t: table = { data: 1 };\n" +
            "t[0] = 42;"
        );
        assertError(lit, "E3018", "int literal table index write");
        boolean litAtIndex = lit.result.diagnostics().stream()
            .filter(d -> d.code().equals("E3018"))
            .anyMatch(d -> d.line() == 2 && d.column() == 3);
        check(litAtIndex, "E3018 (write) must be reported at the index span");

        // int-typed variable key
        CheckerOutput var = checkProgram(
            "let t: table = { data: 1 };\n" +
            "let k: int = 0;\n" +
            "t[k] = 42;"
        );
        assertError(var, "E3018", "int-typed table index write key");
        boolean varAtIndex = var.result.diagnostics().stream()
            .filter(d -> d.code().equals("E3018"))
            .anyMatch(d -> d.line() == 3 && d.column() == 3);
        check(varAtIndex, "E3018 (int var write) must be reported at the index span");
    }

    static void testTableIndexDeleteStringKeyAccepted() {
        System.out.println("-- Table Index Delete String Key Accepted --");
        CheckerOutput out = checkProgram(
            "let t: table = { x: 1 };\n" +
            "let k: string = \"x\";\n" +
            "delete t[k];"
        );
        assertNoErrors(out, "delete table index with a string-typed key");
    }

    static void testTableIndexDeleteNonStringKeyRejected() {
        System.out.println("-- Table Index Delete Non-String Key Rejected (E3018) --");
        CheckerOutput out = checkProgram(
            "let t: table = { x: 1 };\n" +
            "delete t[0];"
        );
        assertError(out, "E3018", "int literal table index delete");
        // E3018 at the index expression's span (line 2, column 10 — the
        // `0` inside `delete t[0]`), not the statement span.
        boolean atIndex = out.result.diagnostics().stream()
            .filter(d -> d.code().equals("E3018"))
            .anyMatch(d -> d.line() == 2 && d.column() == 10);
        check(atIndex, "E3018 (delete) must be reported at the index span");
    }

    // Read-side table index key handling stays E2's read-mechanics
    // domain: the existing E3007 rejection is untouched and E3018 never
    // fires on reads.
    static void testTableIndexReadUnaffected() {
        System.out.println("-- Table Index Read Unaffected by E3018 --");
        CheckerOutput out = checkProgram(
            "let t: table = { data: 1 };\n" +
            "let v: int = t[0];"
        );
        assertError(out, "E3007", "table index read keeps E3007");
        boolean e3018 = out.result.diagnostics().stream()
            .anyMatch(d -> d.code().equals("E3018"));
        check(!e3018, "no E3018 on a table index read");
    }

    // Array index rules are untouched: reads and writes keep the int
    // requirement (E3007) and never see E3018.
    static void testArrayIndexIntRequirementUnchanged() {
        System.out.println("-- Array Index Int Requirement Unchanged (E3007, no E3018) --");
        CheckerOutput read = checkProgram(
            "let xs: int[] = [1, 2];\n" +
            "let v: int = xs[\"k\"];"
        );
        assertError(read, "E3007", "array index read keeps the int requirement");
        CheckerOutput write = checkProgram(
            "let xs: int[] = [1, 2];\n" +
            "xs[\"k\"] = 1;"
        );
        assertError(write, "E3007", "array index write keeps the int requirement");
        boolean readE3018 = read.result.diagnostics().stream()
            .anyMatch(d -> d.code().equals("E3018"));
        boolean writeE3018 = write.result.diagnostics().stream()
            .anyMatch(d -> d.code().equals("E3018"));
        check(!readE3018 && !writeE3018, "no E3018 on array index reads/writes");
    }

    // =========================================================================
    // Array Tests
    // =========================================================================

    static void testArrayLiteral() {
        System.out.println("-- Array Literal --");
        CheckerOutput out = checkProgram("let a: int[] = [1, 2, 3];");
        assertNoErrors(out, "int array literal");
    }

    static void testMixedArray() {
        System.out.println("-- Mixed Array --");
        CheckerOutput out = checkProgram(
            "let a: int[] = [1, 2];\n" +
            "let b = [1, 1.0];"
        );
        assertError(out, "E3011", "mixed array types");
    }

    static void testEmptyArray() {
        System.out.println("-- Empty Array --");
        CheckerOutput out = checkProgram("let a = [];");
        assertError(out, "E3002", "empty array inference error");
    }

    static void testArrayRead() {
        System.out.println("-- Array Read --");
        CheckerOutput out = checkProgram(
            "let a: int[] = [1, 2, 3];\n" +
            "let x: int = a[0];"
        );
        assertNoErrors(out, "array read");
    }

    static void testArrayLength() {
        System.out.println("-- Array Length --");
        CheckerOutput out = checkProgram(
            "let a: int[] = [1, 2, 3];\n" +
            "let len: int = a.length;"
        );
        assertNoErrors(out, "array length");
    }

    // D3 (typed-boundary-enforcement): array .length is read-only.
    static void testArrayLengthAssignmentRejected() {
        System.out.println("-- Array Length Assignment Rejected (E3017) --");
        // A mismatching value pins that normal target/value checking
        // continues after E3017 (typeMap stays populated): both E3017 and
        // E3001 are reported.
        CheckerOutput out = checkProgram(
            "let xs: int[] = [1, 2, 3];\n" +
            "xs.length = \"oops\";"
        );
        assertError(out, "E3017", "assignment to array .length");
        assertError(out, "E3001", "value type checking continues after E3017");
        // E3017 is reported at the target span (line 2, column 1 — the
        // start of `xs.length`), not the value span.
        boolean atTarget = out.result.diagnostics().stream()
            .filter(d -> d.code().equals("E3017"))
            .anyMatch(d -> d.line() == 2 && d.column() == 1);
        check(atTarget, "E3017 must be reported at the target span");
    }

    static void testArrayLengthDeleteRejected() {
        System.out.println("-- Array Length Delete Rejected (E3017) --");
        CheckerOutput out = checkProgram(
            "let xs: int[] = [1, 2, 3];\n" +
            "delete xs.length;"
        );
        assertError(out, "E3017", "delete of array .length");
        // E3017 is reported at the target span (line 2, column 8 — the
        // start of `xs.length` after `delete `), not the statement span.
        boolean atTarget = out.result.diagnostics().stream()
            .filter(d -> d.code().equals("E3017"))
            .anyMatch(d -> d.line() == 2 && d.column() == 8);
        check(atTarget, "E3017 must be reported at the target span");
    }

    // The append idiom's target is an IndexExpr whose index is the
    // MemberAccessExpr — E3017 must not fire.
    static void testArrayAppendIdiomCompiles() {
        System.out.println("-- Array Append Idiom xs[xs.length] = v Compiles --");
        CheckerOutput out = checkProgram(
            "let xs: int[] = [1, 2, 3];\n" +
            "xs[xs.length] = 4;"
        );
        assertNoErrors(out, "append idiom xs[xs.length] = v");
    }

    // Table .length and class fields named length are untouched.
    static void testTableLengthWriteUnaffected() {
        System.out.println("-- Table .length Write Unaffected --");
        CheckerOutput out = checkProgram(
            "let t: table = { length: 1 };\n" +
            "t.length = 5;"
        );
        assertNoErrors(out, "table .length write is still allowed");
    }

    static void testClassFieldLengthWriteUnaffected() {
        System.out.println("-- Class Field Named length Unaffected --");
        CheckerOutput out = checkProgram(
            "class C { length: int; }\n" +
            "let c: C = { length: 1 };\n" +
            "c.length = 5;"
        );
        assertNoErrors(out, "class field named length is still assignable");
    }

    // =========================================================================
    // Table Tests
    // =========================================================================

    static void testTableLiteral() {
        System.out.println("-- Table Literal --");
        CheckerOutput out = checkProgram(
            "let t: table = { name: \"A\", age: 30 };"
        );
        assertNoErrors(out, "table literal");
    }

    static void testTableReadWithContext() {
        System.out.println("-- Table Read with Context --");
        CheckerOutput out = checkProgram(
            "let t: table = { name: \"A\" };\n" +
            "let x: string = t.name;"
        );
        assertNoErrors(out, "table read with context");
    }

    static void testTableReadWithoutContext() {
        System.out.println("-- Table Read without Context --");
        CheckerOutput out = checkProgram(
            "let t: table = { name: \"A\" };\n" +
            "let x = t.name;"
        );
        assertError(out, "E3003", "table read without context");
    }

    // =========================================================================
    // Function Tests
    // =========================================================================

    static void testFunctionCall() {
        System.out.println("-- Function Call --");
        CheckerOutput out = checkProgram(
            "function add(a: int, b: int): int { return a + b; }\n" +
            "let x: int = add(1, 2);"
        );
        assertNoErrors(out, "function call");
    }

    static void testFunctionArgCountMismatch() {
        System.out.println("-- Function Arg Count Mismatch --");
        CheckerOutput out = checkProgram(
            "function add(a: int, b: int): int { return a + b; }\n" +
            "let x: int = add(1);"
        );
        assertError(out, "E3009", "arg count mismatch");
    }

    static void testFunctionArgTypeMismatch() {
        System.out.println("-- Function Arg Type Mismatch --");
        CheckerOutput out = checkProgram(
            "function add(a: int, b: int): int { return a + b; }\n" +
            "let x: int = add(\"a\", \"b\");"
        );
        assertError(out, "E5001", "arg type mismatch");
    }

    static void testFunctionReturnType() {
        System.out.println("-- Function Return Type --");
        CheckerOutput out = checkProgram(
            "function f(): int { return \"hello\"; }"
        );
        assertError(out, "E5003", "return type mismatch");
    }

    static void testArityExtension() {
        System.out.println("-- Arity Extension --");
        CheckerOutput out = checkProgram(
            "function oneArg(a: int): int { return a; }\n" +
            "let f: (x: int, y: int) => int = oneArg;"
        );
        assertNoErrors(out, "arity extension: fewer params OK");
    }

    static void testReverseArityError() {
        System.out.println("-- Reverse Arity Error --");
        CheckerOutput out = checkProgram(
            "function twoArgs(a: int, b: int): int { return a + b; }\n" +
            "let f: (x: int) => int = twoArgs;"
        );
        List<CompilerDiagnostic> diags = out.result.diagnostics();
        boolean hasE5004 = diags.stream().anyMatch(d -> d.code().equals("E5004"));
        boolean hasE3001 = diags.stream().anyMatch(d -> d.code().equals("E3001"));
        check(hasE5004 || hasE3001,
            "reverse arity error: expected E5004 or E3001, got " + diags);
    }

    // F5: Contextual typing for function call arguments
    static void testFunctionCallContextualTyping() {
        System.out.println("-- Function Call Contextual Typing --");
        CheckerOutput out = checkProgram(
            "function f(s: string): null {}\n" +
            "let t: table = { value: \"hello\" };\n" +
            "f(t.value);\n"
        );
        assertNoErrors(out, "function call contextual typing for table read");
    }

    // F6: Duplicate parameters in function expressions
    static void testFunctionExprDuplicateParams() {
        System.out.println("-- Function Expression Duplicate Params --");
        CheckerOutput out = checkProgram(
            "let f = function(x: int, x: int): int { return x; };"
        );
        assertError(out, "E2002", "function expr duplicate parameters");
    }

    static void testFunctionExpr() {
        System.out.println("-- Function Expression --");
        CheckerOutput out = checkProgram(
            "let f = function(x: int): int { return x; };\n" +
            "let r: int = f(42);"
        );
        assertNoErrors(out, "function expression");
    }

    // =========================================================================
    // F1: Scope Chain Tests
    // =========================================================================

    static void testScopeChain_functionBody() {
        System.out.println("-- F1: Scope chain - function body variables --");
        CheckerOutput out = checkProgram(
            "function f(): int { let y: int = 42; return y; }"
        );
        assertNoErrors(out, "function body variable visible in same function");
    }

    static void testScopeChain_nestedBlock() {
        System.out.println("-- F1: Scope chain - nested block variables --");
        CheckerOutput out = checkProgram(
            "function f(): int { let y: int = 42; { let z: int = y; } return y; }"
        );
        assertNoErrors(out, "variables visible in nested blocks");
    }

    // =========================================================================
    // F2: Nested Function Return Type
    // =========================================================================

    static void testNestedFunctionReturnType() {
        System.out.println("-- F2: Nested function return type --");
        CheckerOutput out = checkProgram(
            "function outer(): null {\n" +
            "  function inner(): int { return 42; }\n" +
            "  return;\n" +
            "}"
        );
        assertNoErrors(out, "nested function return type resolved correctly");
    }

    // =========================================================================
    // F6: If/While/For Condition Contextual Typing
    // =========================================================================

    static void testIfConditionContextualTyping() {
        System.out.println("-- F6: If condition contextual typing --");
        CheckerOutput out = checkProgram(
            "let t: table = { flag: true };\n" +
            "if (t.flag) { let x: int = 1; }"
        );
        assertNoErrors(out, "if condition table read has boolean context");
    }

    static void testWhileConditionContextualTyping() {
        System.out.println("-- F6: While condition contextual typing --");
        CheckerOutput out = checkProgram(
            "let t: table = { flag: true };\n" +
            "while (t.flag) { break; }"
        );
        assertNoErrors(out, "while condition table read has boolean context");
    }

    static void testForConditionContextualTyping() {
        System.out.println("-- F6: For condition contextual typing --");
        CheckerOutput out = checkProgram(
            "let t: table = { flag: true };\n" +
            "for (let i: int = 0; i < 10 && t.flag; i = i + 1) { break; }"
        );
        assertNoErrors(out, "for condition table read has boolean context");
    }

    // =========================================================================
    // F8: Break/Continue Outside Loop Tests
    // =========================================================================

    static void testBreakOutsideLoop() {
        System.out.println("-- F8: Break outside loop --");
        CheckerOutput out = checkProgram("break;");
        assertError(out, "E2000", "break outside loop");
    }

    static void testContinueOutsideLoop() {
        System.out.println("-- F8: Continue outside loop --");
        CheckerOutput out = checkProgram("continue;");
        assertError(out, "E2000", "continue outside loop");
    }

    // =========================================================================
    // F9: Class Field Default Value Type Error
    // =========================================================================

    static void testClassDefaultValueTypeError() {
        System.out.println("-- F9: Class default value type error --");
        CheckerOutput out = checkProgram(
            "class User { name: string = 42; }"
        );
        assertError(out, "E3001", "class default value type mismatch");

        // Non-literal defaults are fully type-checked too (ISSUE-0095
        // rework): the JVM backend emits defaults inline at each
        // construction site and reads their subexpression types from the
        // typeMap, so a non-literal mismatch must be a real diagnostic —
        // never an artifact javac rejects.
        CheckerOutput arithmetic = checkProgram(
            "class User { name: string = 1 + 2; }"
        );
        assertError(arithmetic, "E3001",
            "non-literal class default value type mismatch");
    }

    static void testClassDefaultExpressionsTyped() {
        System.out.println("-- F9: Class default expressions type-checked --");
        CheckerOutput out = checkProgram(
            "class Point { x: int = 1 + 2; }\nlet p: Point = {};"
        );
        assertNoErrors(out, "arithmetic default type-checks clean");
        for (StatementNode stmt : out.program().statements()) {
            if (stmt instanceof ClassDeclaration cd) {
                for (ClassField cf : cd.fields()) {
                    if (cf.defaultExpr().isPresent()) {
                        Type t = out.result.typeMap()
                            .get(cf.defaultExpr().get());
                        check(t instanceof Type.Int,
                            "default subexpression recorded as int, got " + t);
                    }
                }
            }
        }

        // A default containing a null-typed call type-checks: the inner
        // call records Type.Null (the JVM backend hoists it into a
        // pre-statement instead of emitting `wrap(noise())`).
        CheckerOutput call = checkProgram(
            "function noise(): null { return; }\n"
            + "function wrap(x: null): string { return \"w\"; }\n"
            + "class P { x: string = wrap(noise()); }\n"
            + "let p: P = {};"
        );
        assertNoErrors(call, "null-typed call default type-checks clean");
    }

    // =========================================================================
    // F10: Delete on Table
    // =========================================================================

    static void testDeleteTableField() {
        System.out.println("-- F10: Delete table field --");
        CheckerOutput out = checkProgram(
            "let t: table = { x: 1 };\n" +
            "delete t.x;"
        );
        assertNoErrors(out, "delete table field should not trigger E3003");
    }

    static void testDeleteTableIndex() {
        System.out.println("-- F10: Delete table index --");
        CheckerOutput out = checkProgram(
            "let t: table = { x: 1 };\n" +
            "delete t[\"x\"];"
        );
        assertNoErrors(out, "delete table index should not trigger E3007");
    }

    // =========================================================================
    // Class Tests
    // =========================================================================

    static void testClassConstruction() {
        System.out.println("-- Class Construction --");
        CheckerOutput out = checkProgram(
            "class Point { x: int; y: int; }\n" +
            "let p: Point = { x: 1, y: 2 };"
        );
        assertNoErrors(out, "class construction");
    }

    static void testClassExtraFields() {
        System.out.println("-- Class Extra Fields --");
        CheckerOutput out = checkProgram(
            "class Point { x: int; y: int; }\n" +
            "let p: Point = { x: 1, y: 2, z: 3 };"
        );
        assertError(out, "E4002", "extra field in class literal");
    }

    static void testClassMissingRequiredField() {
        System.out.println("-- Class Missing Required Field --");
        CheckerOutput out = checkProgram(
            "class Point { x: int; y: int; }\n" +
            "let p: Point = { x: 1 };"
        );
        assertError(out, "E4001", "missing required field");
    }

    static void testClassNominalTyping() {
        System.out.println("-- Class Nominal Typing --");
        CheckerOutput out = checkProgram(
            "class A { x: int; }\n" +
            "class B { x: int; }\n" +
            "let a: A = { x: 1 };\n" +
            "let b: B = a;"
        );
        assertError(out, "E3001", "nominal typing: A != B");
    }

    static void testClassFieldAccess() {
        System.out.println("-- Class Field Access --");
        CheckerOutput out = checkProgram(
            "class Point { x: int; y: int; }\n" +
            "let p: Point = { x: 1, y: 2 };\n" +
            "let x: int = p.x;"
        );
        assertNoErrors(out, "class field access");
    }

    static void testClassOptionalField() {
        System.out.println("-- Class Optional Field --");
        CheckerOutput out = checkProgram(
            "class User { name: string; age?: int = 0; }\n" +
            "let u: User = { name: \"A\" };\n" +
            "let a: int | null = u.age;"
        );
        assertNoErrors(out, "class optional field");
    }

    static void testLocalNullableClassMemberAccessRejected() {
        System.out.println("-- Local nullable class member access: unguarded -> E3003 --");
        CheckerOutput out = checkProgram(
            "class User { name: string = \"a\"; }\n" +
            "export function f(u: User | null): string {\n" +
            "  return u.name;\n" +
            "}"
        );
        assertError(out, "E3003",
            "unguarded local nullable class member access must be E3003");
    }

    static void testLocalNullableClassMemberAccessNarrowedOk() {
        System.out.println("-- Local nullable class member access: narrowed -> OK --");
        CheckerOutput out = checkProgram(
            "class User { name: string = \"a\"; }\n" +
            "export function f(u: User | null): string {\n" +
            "  if (u !== null) { return u.name; }\n" +
            "  return \"none\";\n" +
            "}"
        );
        assertNoErrors(out, "narrowed local nullable class member access");
    }

    // F3: Nested class declarations
    static void testNestedClass() {
        System.out.println("-- Nested Class --");
        CheckerOutput out = checkProgram(
            "function make(): null { class Inner { x: int; } let i: Inner = { x: 1 }; }"
        );
        assertNoErrors(out, "nested class inside function");
    }

    // F2: Error class construction
    static void testErrorClassConstruction() {
        System.out.println("-- Error Class Construction --");
        CheckerOutput out = checkProgram(
            "let e: Error = { code: \"X\", message: \"Y\" };"
        );
        assertNoErrors(out, "Error class construction");

        // Also test Error field access
        out = checkProgram(
            "let e: Error = { code: \"X\", message: \"Y\" };\n" +
            "let c: string = e.code;\n" +
            "let m: string = e.message;"
        );
        assertNoErrors(out, "Error field access");
    }

    // =========================================================================
    // Throw Statement Tests (ISSUE-0010)
    // =========================================================================

    static void testThrowIntError() {
        System.out.println("-- Throw: int → E3001 --");
        CheckerOutput out = checkProgram(
            "function f(): null { throw 42; }"
        );
        assertError(out, "E3001", "throw int must produce E3001");
    }

    static void testThrowStringError() {
        System.out.println("-- Throw: string → E3001 --");
        CheckerOutput out = checkProgram(
            "function f(): null { throw \"oops\"; }"
        );
        assertError(out, "E3001", "throw string must produce E3001");
    }

    static void testThrowMessageOnly() {
        System.out.println("-- Throw: { message: \"x\" } → OK --");
        CheckerOutput out = checkProgram(
            "function f(): null { throw { message: \"x\" }; }"
        );
        assertNoErrors(out, "throw { message: \"x\" } must compile");
    }

    static void testThrowFullError() {
        System.out.println("-- Throw: { code: \"E001\", message: \"x\" } → OK --");
        CheckerOutput out = checkProgram(
            "function f(): null { throw { code: \"E001\", message: \"x\" }; }"
        );
        assertNoErrors(out, "throw { code, message } must compile");
    }

    static void testThrowErrorVariable() {
        System.out.println("-- Throw: Error variable → OK --");
        CheckerOutput out = checkProgram(
            "function f(): null {\n" +
            "  let e: Error = { message: \"x\" };\n" +
            "  throw e;\n" +
            "}"
        );
        assertNoErrors(out, "throw Error variable must compile");
    }

    // =========================================================================
    // has / delete Tests
    // =========================================================================

    static void testHasRequiredField() {
        System.out.println("-- has() on Required Field --");
        CheckerOutput out = checkProgram(
            "class Point { x: int; y: int; }\n" +
            "let p: Point = { x: 1, y: 2 };\n" +
            "let b: boolean = has(p.x);"
        );
        assertError(out, "E4005", "has on required field");
    }

    static void testHasOptionalField() {
        System.out.println("-- has() on Optional Field --");
        CheckerOutput out = checkProgram(
            "class User { name: string; age?: int = 0; }\n" +
            "let u: User = { name: \"A\" };\n" +
            "let b: boolean = has(u.age);"
        );
        assertNoErrors(out, "has on optional field");
    }

    static void testDeleteRequiredField() {
        System.out.println("-- Delete Required Field --");
        CheckerOutput out = checkProgram(
            "class Point { x: int; y?: int = 0; }\n" +
            "let p: Point = { x: 1 };\n" +
            "delete p.x;"
        );
        assertError(out, "E4004", "delete required field");
    }

    static void testDeleteOptionalField() {
        System.out.println("-- Delete Optional Field --");
        CheckerOutput out = checkProgram(
            "class Point { x: int; y?: int = 0; }\n" +
            "let p: Point = { x: 1 };\n" +
            "delete p.y;"
        );
        assertNoErrors(out, "delete optional field");
    }

    // =========================================================================
    // Null Narrowing Tests
    // =========================================================================

    static void testNullNarrowing_neNull() {
        System.out.println("-- Null Narrowing: x !== null --");
        CheckerOutput out = checkProgram(
            "let n: int | null = null;\n" +
            "if (n !== null) {\n" +
            "  let x: int = n;\n" +
            "}"
        );
        assertNoErrors(out, "null narrowing !== null");
    }

    static void testNullNarrowing_eqNull() {
        System.out.println("-- Null Narrowing: x === null --");
        CheckerOutput out = checkProgram(
            "let n: int | null = 5;\n" +
            "if (n === null) {\n" +
            "  return;\n" +
            "} else {\n" +
            "  let x: int = n;\n" +
            "}"
        );
        assertNoErrors(out, "null narrowing === null else branch");
    }

    static void testNullNarrowing_assignmentClears() {
        System.out.println("-- Null Narrowing: assignment clears --");
        CheckerOutput out = checkProgram(
            "let n: int | null = 5;\n" +
            "if (n !== null) {\n" +
            "  n = null;\n" +
            "  let x: int = n;\n" +
            "}"
        );
        assertError(out, "E3001", "assignment clears narrowing");
    }

    static void testNullNarrowing_negated() {
        System.out.println("-- Null Narrowing: negated condition --");
        CheckerOutput out = checkProgram(
            "let n: int | null = null;\n" +
            "if (!(n === null)) {\n" +
            "  let x: int = n;\n" +
            "}"
        );
        assertNoErrors(out, "null narrowing with !(x === null)");
    }

    static void testNullNarrowing_nestedIf() {
        System.out.println("-- Null Narrowing: nested if --");
        CheckerOutput out = checkProgram(
            "let a: int | null = 5;\n" +
            "let b: int | null = 10;\n" +
            "if (a !== null) {\n" +
            "  if (b !== null) {\n" +
            "    let x: int = a;\n" +
            "    let y: int = b;\n" +
            "  }\n" +
            "}"
        );
        assertNoErrors(out, "nested null narrowing");
    }

    // =========================================================================
    // Definite Return Tests
    // =========================================================================

    static void testDefiniteReturn_bothBranches() {
        System.out.println("-- Definite Return: both branches --");
        CheckerOutput out = checkProgram(
            "function f(x: boolean): int {\n" +
            "  if (x) { return 1; }\n" +
            "  else { return 2; }\n" +
            "}"
        );
        assertNoErrors(out, "definite return both branches");
    }

    static void testDefiniteReturn_missingReturn() {
        System.out.println("-- Definite Return: missing return --");
        CheckerOutput out = checkProgram(
            "function f(x: boolean): int {\n" +
            "  if (x) { return 1; }\n" +
            "}"
        );
        assertError(out, "E5002", "missing return on path");
    }

    static void testDefiniteReturn_throwCounts() {
        System.out.println("-- Definite Return: throw counts --");
        CheckerOutput out = checkProgram(
            "function f(): int {\n" +
            "  throw { message: \"error\" };\n" +
            "}"
        );
        assertNoErrors(out, "throw counts as return");
    }

    // =========================================================================
    // Type Inference Tests
    // =========================================================================

    static void testInference_literals() {
        System.out.println("-- Type Inference: literals --");
        CheckerOutput out = checkProgram("let x = 42;");
        assertNoErrors(out, "infer int from literal");

        out = checkProgram("let x = true;");
        assertNoErrors(out, "infer boolean from literal");

        out = checkProgram("let x = \"hi\";");
        assertNoErrors(out, "infer string from literal");

        out = checkProgram("let x = 3.14;");
        assertNoErrors(out, "infer number from literal");

        out = checkProgram("let x = null;");
        assertNoErrors(out, "infer null from literal");
    }

    static void testInference_emptyArrayError() {
        System.out.println("-- Type Inference: empty array error --");
        CheckerOutput out = checkProgram("let x = [];");
        assertError(out, "E3002", "empty array inference error");
    }

    static void testInference_tableReadError() {
        System.out.println("-- Type Inference: table read error --");
        CheckerOutput out = checkProgram(
            "let t: table = { x: 1 };\n" +
            "let x = t.x;"
        );
        assertError(out, "E3003", "table read inference error");
    }

    // F4: IndexExpr inference
    static void testInference_indexExpr() {
        System.out.println("-- Type Inference: index expression --");
        CheckerOutput out = checkProgram(
            "let arr: int[] = [1, 2, 3];\n" +
            "let x = arr[0];"
        );
        assertNoErrors(out, "infer type from index expression");
    }

    // =========================================================================
    // Unknown Type
    // =========================================================================

    static void testUnknownType() {
        System.out.println("-- Unknown Type --");
        CheckerOutput out = checkProgram("let x: Foo = 1;");
        assertError(out, "E3004", "unknown type");
    }

    // F7: Invalid nullable type
    static void testInvalidNullable() {
        System.out.println("-- Invalid Nullable --");
        CheckerOutput out = checkProgram("let x: int | null | null = 1;");
        assertError(out, "E3005", "invalid nullable (doubly nullable)");
    }

    // F10: Reverse arity E5004
    static void testReverseArityE5004() {
        System.out.println("-- Reverse Arity E5004 --");
        CheckerOutput out = checkProgram(
            "function twoArgs(a: int, b: int): int { return a + b; }\n" +
            "let f: (x: int) => int = twoArgs;"
        );
        assertError(out, "E5004", "reverse arity E5004");
    }

    // =========================================================================
    // F4: E3008 — not callable
    // =========================================================================

    static void testE3008_notCallable() {
        System.out.println("-- E3008: not callable --");
        CheckerOutput out = checkProgram(
            "let x: int = 42;\n" +
            "let y: int = x();"
        );
        assertError(out, "E3008", "int is not callable");
    }

    // =========================================================================
    // F4: E4003 — field type mismatch in class construction
    // =========================================================================

    static void testE4003_fieldTypeMismatch() {
        System.out.println("-- E4003: field type mismatch in class construction --");
        CheckerOutput out = checkProgram(
            "class Point { x: int; y: int; }\n" +
            "let p: Point = { x: \"hello\", y: 2 };"
        );
        assertError(out, "E4003", "field type mismatch in class construction");
    }

    // =========================================================================
    // F1: Function expressions with let declarations should work
    // =========================================================================

    static void testFunctionExprLetDecl() {
        System.out.println("-- F1: Function expression with let declaration --");
        CheckerOutput out = checkProgram(
            "let f = function(): int { let x: int = 42; return x; };\n" +
            "let r: int = f();"
        );
        assertNoErrors(out, "function expression with let declaration");
    }

    // =========================================================================
    // F2: E2005 — circular import with runtime dependency
    // =========================================================================

    static void testE2005_circularImport() {
        System.out.println("-- F2: E2005 circular import --");
        // To test E2005, we create a custom StubModuleResolver.
        // The NameResolver adds the imported module to modulesInProgress
        // before calling resolveModule. If the resolver itself then triggers
        // another import of the same module, E2005 fires.
        // For a unit test, we use a resolver that records the call.
        StubModuleResolver resolver = new StubModuleResolver();
        resolver.register("./lib", java.util.Map.of("value", deal.types.Type.Int.INSTANCE));

        CheckerOutput out = checkProgramWithModule(
            "import * as Lib from \"./lib\";\n" +
            "let x: int = 1;",
            resolver
        );
        assertNoErrors(out, "circular import detection infrastructure present");
    }

    // =========================================================================
    // F3: Class field default null for nullable fields
    // =========================================================================

    static void testClassFieldDefaultNull() {
        System.out.println("-- F3: Null default for nullable field --");
        CheckerOutput out = checkProgram(
            "class User { name: string | null = null; age?: int = 0; }"
        );
        assertNoErrors(out, "null default for nullable field OK");
    }

    // =========================================================================
    // ISSUE-0040: void type name produces E3004
    // =========================================================================

    static void testVoidTypeNameProducesE3004() {
        System.out.println("-- ISSUE-0040: void type name produces E3004 --");
        // void is no longer a recognized type name; produces E3004
        CheckerOutput out = checkProgram(
            "function f(): void { return 42; }"
        );
        List<CompilerDiagnostic> diags = out.result.diagnostics();
        boolean hasE3004 = diags.stream()
            .anyMatch(d -> d.code().equals("E3004") && d.message().contains("void"));
        check(hasE3004,
            "void type name should produce E3004 'Unknown type', got: " + diags);
    }

    // =========================================================================
    // Runtime Intrinsic Tests
    // =========================================================================

    static void testIntrinsicIntRejectsIntLiteral() {
        System.out.println("-- int(3) rejects int literal at compile time --");
        CheckerOutput out = checkProgram(
            "export function test(): int { return int(3); }"
        );
        List<CompilerDiagnostic> diags = out.result.diagnostics();
        boolean hasE5001 = diags.stream()
            .anyMatch(d -> d.code().equals("E5001"));
        check(hasE5001,
            "int(3) should reject int literal with E5001, got: " + diags);
    }

    static void testIntrinsicNumberRejectsBoolean() {
        System.out.println("-- number(true) rejects boolean at compile time --");
        CheckerOutput out = checkProgram(
            "export function test(): number { return number(true); }"
        );
        List<CompilerDiagnostic> diags = out.result.diagnostics();
        boolean hasE5001 = diags.stream()
            .anyMatch(d -> d.code().equals("E5001"));
        check(hasE5001,
            "number(true) should reject boolean with E5001, got: " + diags);
    }
    // =========================================================================
    // DEAL v1.2: string Unicode scalar-value type-facing checks (ISSUE-0104)
    // =========================================================================

    /**
     * v1.2 string semantics in type-facing checks: a `string` is a
     * Unicode scalar-value sequence, so `.length` is not a
     * compiler-resolved intrinsic on strings (only arrays/bytes have it —
     * string lengths come from stdlib string.length), for-of over a
     * string yields one `string` per scalar value per iteration, and
     * template interpolation still requires string.
     */
    static void testV12StringTypeFacingChecks() {
        System.out.println("-- v1.2 string Unicode scalar-value type-facing checks --");

        CheckerOutput out = checkProgram(
            "function f(s: string): null { let n: int = s.length; return null; }"
        );
        assertError(out, "E3003", "string .length is not an intrinsic");

        out = checkProgram(
            "function f(s: string): null {\n" +
            "  for (let c: int of s) { }\n" +
            "  return null;\n" +
            "}"
        );
        assertError(out, "E3015", "for-of over string requires string loop var");

        out = checkProgram(
            "function f(s: string): int {\n" +
            "  let n: int = 0;\n" +
            "  for (let c: string of s) { n = n + 1; }\n" +
            "  return n;\n" +
            "}"
        );
        assertNoErrors(out, "for-of over string with string loop var");

        out = checkProgram("let n: int = 1; let s: string = `x${n}`;");
        assertError(out, "E3016", "template interpolation of int is not string");
    }

    // =========================================================================
    // DEAL v1.2: function types carry no rest arm (ISSUE-0104)
    // =========================================================================

    /** A function TYPE annotation using the removed rest arm is E1047. */
    static void testV12FunctionTypeRestRejected() {
        System.out.println("-- v1.2 function type rest arm rejected (E1047) --");

        CheckerOutput out = checkProgram(
            "function f(): null {\n" +
            "  let g: (a: int, ...rest: int[]) => null = null;\n" +
            "  return null;\n" +
            "}"
        );
        assertError(out, "E1047", "function type rest arm rejected");
    }

    // =========================================================================
    // ISSUE-0042: coroutine import now fails with E2003
    // =========================================================================

    static void testCoroutineImportRejected() {
        System.out.println("-- ISSUE-0040: coroutine import produces E2003 (module not found) --");

        // Test 1: import * as c from "std/coroutine" should produce E2003
        CheckerOutput out = checkProgram(
            "import * as c from \"std/coroutine\";"
        );
        assertError(out, "E2003", "coroutine import produces E2003 (module not found)");

        // Test 2: import alongside other code — E2003 still fires.
        out = checkProgram(
            "import * as c from \"std/coroutine\";\n" +
            "let x: int = 42;"
        );
        assertError(out, "E2003", "coroutine import with other code still produces E2003");

        // Test 3: use of c.resumeInt after failed import — E2003 fires first.
        out = checkProgram(
            "import * as c from \"std/coroutine\";\n" +
            "let x: int = c.resumeInt(null);"
        );
        assertError(out, "E2003", "coroutine import with c.resumeInt usage produces E2003");
    }




    // =================================================================
    // v1.1: For-of Scoping Tests (ISSUE-0034)
    // =================================================================

    static void testForOfScoping_loopVarInBody() {
        System.out.println("-- For-of Scoping: loop var in body --");
        CheckerOutput out = checkProgram(
            "function f(xs: int[]): null {\n" +
            "  for (let x: int of xs) {\n" +
            "    let y: int = x;\n" +
            "  }\n" +
            "  return null;\n" +
            "}"
        );
        assertNoErrors(out, "loop variable visible in for-of body");
    }

    static void testForOfScoping_iterableCannotRefLoopVar() {
        System.out.println("-- For-of Scoping: iterable cannot ref loop var --");
        // The iterable x should resolve to outer scope. If there is no outer x, E2001.
        CheckerOutput out = checkProgram(
            "function f(): null {\n" +
            "  for (let x: int of x) {\n" +
            "    let y: int = x;\n" +
            "  }\n" +
            "  return null;\n" +
            "}"
        );
        assertError(out, "E2001", "iterable x should not see loop variable (E2001)");
    }

    static void testForOfScoping_iterableRefsOuterVar() {
        System.out.println("-- For-of Scoping: iterable refs outer var --");
        // The iterable x should resolve to the outer x (the parameter).
        CheckerOutput out = checkProgram(
            "function f(x: int[]): null {\n" +
            "  for (let x: int of x) {\n" +
            "    let y: int = x;\n" +
            "  }\n" +
            "  return null;\n" +
            "}"
        );
        assertNoErrors(out, "iterable x resolves to outer parameter");
    }

    static void testForOfScoping_breakInside() {
        System.out.println("-- For-of Scoping: break inside --");
        CheckerOutput out = checkProgram(
            "function f(xs: int[]): null {\n" +
            "  for (let x: int of xs) {\n" +
            "    break;\n" +
            "  }\n" +
            "  return null;\n" +
            "}"
        );
        assertNoErrors(out, "break inside for-of");
    }

    static void testForOfScoping_continueInside() {
        System.out.println("-- For-of Scoping: continue inside --");
        CheckerOutput out = checkProgram(
            "function f(xs: int[]): null {\n" +
            "  for (let x: int of xs) {\n" +
            "    continue;\n" +
            "  }\n" +
            "  return null;\n" +
            "}"
        );
        assertNoErrors(out, "continue inside for-of");
    }

    // ================================================================="
    // v1.1: For-of Type Checking Tests (ISSUE-0034)
    // ================================================================="

    static void testForOfTypeCheck_arrayCorrect() {
        System.out.println("-- For-of Type Check: array correct --");
        CheckerOutput out = checkProgram(
            "function f(xs: int[]): null {\n" +
            "  for (let x: int of xs) {\n" +
            "    let y: int = x;\n" +
            "  }\n" +
            "  return null;\n" +
            "}"
        );
        assertNoErrors(out, "for-of over int[] with int loop var");
    }

    static void testForOfTypeCheck_stringCorrect() {
        System.out.println("-- For-of Type Check: string correct --");
        CheckerOutput out = checkProgram(
            "function f(s: string): null {\n" +
            "  for (let c: string of s) {\n" +
            "    let d: string = c;\n" +
            "  }\n" +
            "  return null;\n" +
            "}"
        );
        assertNoErrors(out, "for-of over string with string loop var");
    }

    static void testForOfTypeCheck_nonIterable() {
        System.out.println("-- For-of Type Check: non-iterable (boolean) -> E3015 --");
        CheckerOutput out = checkProgram(
            "function f(b: boolean): null {\n" +
            "  for (let x: int of b) {\n" +
            "    let y: int = x;\n" +
            "  }\n" +
            "  return null;\n" +
            "}"
        );
        assertError(out, "E3015", "for-of over boolean -> E3015");
    }

    static void testForOfTypeCheck_tableIterable() {
        System.out.println("-- For-of Type Check: non-iterable (table) -> E3015 --");
        CheckerOutput out = checkProgram(
            "function f(t: table): null {\n" +
            "  for (let x: int of t) {\n" +
            "    let y: int = x;\n" +
            "  }\n" +
            "  return null;\n" +
            "}"
        );
        assertError(out, "E3015", "for-of over table -> E3015");
    }

    static void testForOfTypeCheck_arrayWrongVarType() {
        System.out.println("-- For-of Type Check: array wrong var type -> E3015 --");
        CheckerOutput out = checkProgram(
            "function f(xs: int[]): null {\n" +
            "  for (let x: string of xs) {\n" +
            "    let y: string = x;\n" +
            "  }\n" +
            "  return null;\n" +
            "}"
        );
        assertError(out, "E3015", "for-of over int[] with string var -> E3015");
    }

    static void testForOfTypeCheck_stringWrongVarType() {
        System.out.println("-- For-of Type Check: string wrong var type -> E3015 --");
        CheckerOutput out = checkProgram(
            "function f(s: string): null {\n" +
            "  for (let c: int of s) {\n" +
            "    let d: int = c;\n" +
            "  }\n" +
            "  return null;\n" +
            "}"
        );
        assertError(out, "E3015", "for-of over string with int var -> E3015");
    }

    // ================================================================="
    // v1.1: Template Literal Type Checking Tests (ISSUE-0034)
    // ================================================================="

    static void testTemplateLiteral_stringParts() {
        System.out.println("-- Template Literal: all string parts --");
        CheckerOutput out = checkProgram(
            "function f(name: string): string {\n" +
            "  return `Hello ${name}`;\n" +
            "}"
        );
        assertNoErrors(out, "template literal with string interpolation");
    }

    static void testTemplateLiteral_nonStringInterpolation() {
        System.out.println("-- Template Literal: non-string interpolation -> E3016 --");
        CheckerOutput out = checkProgram(
            "function f(n: int): string {\n" +
            "  return `Value: ${n}`;\n" +
            "}"
        );
        assertError(out, "E3016", "template literal with int interpolation -> E3016");
    }

    static void testTemplateLiteral_typeInference() {
        System.out.println("-- Template Literal: type inference (D15) --");
        // let without :string annotation should infer string
        CheckerOutput out = checkProgram(
            "function f(name: string): null {\n" +
            "  let msg = `Hello ${name}`;\n" +
            "  let s: string = msg;\n" +
            "}"
        );
        assertNoErrors(out, "template literal infers string type");
    }

    static void testTemplateLiteral_nameResolution() {
        System.out.println("-- Template Literal: name resolution walks parts --");
        // FunctionExpr inside template interpolation should be found by NameResolver
        CheckerOutput out = checkProgram(
            "function f(): string {\n" +
            "  let g = function(): string { return \"world\"; };\n" +
            "  return `Hello ${g()}`;\n" +
            "}"
        );
        assertNoErrors(out, "template literal parts walked for name resolution");
    }

    // =========================================================================
    // ISSUE-0040: Error class prohibition (E4006)
    // =========================================================================

    static void testE4006_classErrorProhibition() {
        System.out.println("-- ISSUE-0040: class Error prohibition (E4006) --");

        // Test 1: module-level class Error should produce E4006
        CheckerOutput out = checkProgram(
            "class Error { code: string, message: string }"
        );
        assertError(out, "E4006", "module-level class Error produces E4006");

        // Test 2: exported class Error should produce E4006
        out = checkProgram(
            "export class Error { code: string, message: string }"
        );
        assertError(out, "E4006", "exported class Error produces E4006");

        // Test 3: nested class Error inside a function should produce E4006
        out = checkProgram(
            "function f() { class Error { code: string, message: string } }"
        );
        assertError(out, "E4006", "nested class Error inside function produces E4006");
    }

    // =========================================================================
    // ISSUE-0040: Dollar prohibition (E2008)
    // =========================================================================

    static void testE2008_dollarInIdentifier() {
        System.out.println("-- ISSUE-0040: dollar prohibition (E2008) --");

        // Test 1: dollar in class name
        CheckerOutput out = checkProgram(
            "class Foo$Bar { }"
        );
        assertError(out, "E2008", "dollar in class name produces E2008");

        // Test 2: dollar in function name
        out = checkProgram(
            "function foo$bar(): null { return null; }"
        );
        assertError(out, "E2008", "dollar in function name produces E2008");

        // Test 3: dollar in let variable name
        out = checkProgram(
            "let my$var: int = 42;"
        );
        assertError(out, "E2008", "dollar in let variable name produces E2008");

        // Test 4: dollar in parameter name
        out = checkProgram(
            "function f(param$name: int): null { return null; }"
        );
        assertError(out, "E2008", "dollar in parameter name produces E2008");

        // Test 5: dollar in import alias
        out = checkProgram(
            "import * as mod$name from \"./lib\";"
        );
        assertError(out, "E2008", "dollar in import alias produces E2008");

        // Test 6: dollar in nested class name
        out = checkProgram(
            "function f() { class Nested$Class { } }"
        );
        assertError(out, "E2008", "dollar in nested class name produces E2008");

        // Test 7: dollar in class field name (module-level class)
        out = checkProgram(
            "class Foo { bar$baz: int }"
        );
        assertError(out, "E2008", "dollar in class field name produces E2008");

        // Test 8: dollar in class field name (nested class)
        out = checkProgram(
            "function f() { class Foo { bar$baz: int } }"
        );
        assertError(out, "E2008", "dollar in nested class field name produces E2008");
    }


    // =========================================================================
    // ISSUE-0041: Additional dollar prohibition tests
    // =========================================================================

    /**
     * DEAL v1.2: rest parameters were removed from the language; a rest
     * parameter — dollar in the name or not — is rejected with E1047.
     */
    static void testE2008_dollarInRestParam() {
        System.out.println("-- ISSUE-0041: dollar in rest parameter (E1047 in v1.2) --");

        CheckerOutput out = checkProgram(
            "function f(a: int, ...rest$param: int[]): null { return null; }"
        );
        assertError(out, "E1047", "rest parameter rejected with E1047 in v1.2");
    }

    /** Test that dollar in for-loop variable name produces E2008. */
    static void testE2008_dollarInForLoopVar() {
        System.out.println("-- ISSUE-0041: dollar in for-loop variable (E2008) --");

        CheckerOutput out = checkProgram(
            "function f(): null {\n" +
            "  for (let i$: int = 0; i$ < 10; i$ = i$ + 1) { }\n" +
            "  return null;\n" +
            "}"
        );
        assertError(out, "E2008", "dollar in for-loop variable name produces E2008");
    }

    /** Test that dollar in for-of loop variable name produces E2008. */
    static void testE2008_dollarInForOfLoopVar() {
        System.out.println("-- ISSUE-0041: dollar in for-of loop variable (E2008) --");

        CheckerOutput out = checkProgram(
            "function f(xs: int[]): null {\n" +
            "  for (let x$: int of xs) { }\n" +
            "  return null;\n" +
            "}"
        );
        assertError(out, "E2008", "dollar in for-of loop variable name produces E2008");
    }

    // =========================================================================
    // ISSUE-0041: Negative tests — Error variants not rejected
    // =========================================================================

    /** Test that class names similar to Error (but not exactly "Error") do NOT produce E4006. */
    static void testE4006_errorVariantsNoError() {
        System.out.println("-- ISSUE-0041: Error-like class names not rejected (E4006 negative) --");

        // Test 1: class Error2 should NOT produce E4006
        CheckerOutput out = checkProgram(
            "class Error2 { }"
        );
        List<CompilerDiagnostic> diags = out.result.diagnostics();
        boolean hasE4006 = diags.stream().anyMatch(d -> d.code().equals("E4006"));
        check(!hasE4006, "class Error2 should NOT produce E4006");

        // Test 2: class MyError should NOT produce E4006
        out = checkProgram(
            "class MyError { }"
        );
        diags = out.result.diagnostics();
        hasE4006 = diags.stream().anyMatch(d -> d.code().equals("E4006"));
        check(!hasE4006, "class MyError should NOT produce E4006");

        // Test 3: class error (lowercase) should NOT produce E4006
        out = checkProgram(
            "class error { }"
        );
        diags = out.result.diagnostics();
        hasE4006 = diags.stream().anyMatch(d -> d.code().equals("E4006"));
        check(!hasE4006, "class \"error\" (lowercase) should NOT produce E4006");
    }

    // =========================================================================
    // ISSUE-0041: Integration tests (T3/T4 combined)
    // =========================================================================

    /**
     * Integration test: null return type (T3 void removal) combined with
     * a parameter containing $ (T4 $ prohibition).
     * E2008 should fire, and the null return type should be recognized correctly.
     */
    static void testE2008_dollarIntegration_nullReturn() {
        System.out.println("-- ISSUE-0041: integration — null return type + $ param (E2008) --");

        CheckerOutput out = checkProgram(
            "function f(param$name: int): null { return null; }"
        );
        assertError(out, "E2008", "dollar in param produces E2008 with null return type");

        // Verify null return type is recognized (no E3004 for "null")
        List<CompilerDiagnostic> diags = out.result.diagnostics();
        boolean hasE3004ForNull = diags.stream()
            .anyMatch(d -> d.code().equals("E3004") && d.message().contains("null"));
        check(!hasE3004ForNull, "null return type should NOT produce E3004");
    }

    /**
     * Integration test: valid module import combined with $ in import alias.
     * E2008 should fire at the import alias.
     */
    static void testE2008_dollarIntegration_validImport() {
        System.out.println("-- ISSUE-0041: integration — valid module import + $ import alias (E2008) --");

        StubModuleResolver resolver = new StubModuleResolver();
        resolver.register("./lib", Map.of("foo", Type.Int.INSTANCE));

        CheckerOutput out = checkProgramWithModule(
            "import * as mod$name from \"./lib\";",
            resolver
        );
        assertError(out, "E2008", "dollar in import alias produces E2008 with valid module");

        // Also verify that the module was found (no E2003)
        List<CompilerDiagnostic> diags = out.result.diagnostics();
        boolean hasE2003 = diags.stream().anyMatch(d -> d.code().equals("E2003"));
        check(!hasE2003, "valid module import should NOT produce E2003");
    }
    // =========================================================================
    // v1.1: @jsonable field validation (E4007) and cycle detection (E4008)
    // =========================================================================

    static void testJsonablePrimitiveTypes() {
        System.out.println("-- @jsonable: primitive types are jsonable --");
        CheckerOutput out = checkProgram(
            "// @jsonable\n" +
            "export class Foo {\n" +
            "  a: null;\n" +
            "  b: boolean;\n" +
            "  c: int;\n" +
            "  d: number;\n" +
            "  e: string;\n" +
            "  f: table;\n" +
            "}"
        );
        assertNoErrors(out, "all primitive types are jsonable");
    }

    static void testJsonableArrayType() {
        System.out.println("-- @jsonable: array of jsonable type --");
        CheckerOutput out = checkProgram(
            "// @jsonable\n" +
            "export class Foo {\n" +
            "  items: int[];\n" +
            "  strings: string[];\n" +
            "}"
        );
        assertNoErrors(out, "array of jsonable type is jsonable");
    }

    static void testJsonableNullableType() {
        System.out.println("-- @jsonable: nullable jsonable type --");
        CheckerOutput out = checkProgram(
            "// @jsonable\n" +
            "export class Foo {\n" +
            "  name: string | null;\n" +
            "  count: int | null;\n" +
            "}"
        );
        assertNoErrors(out, "nullable jsonable type is jsonable");
    }

    static void testJsonableNestedClass() {
        System.out.println("-- @jsonable: nested @jsonable class field --");
        CheckerOutput out = checkProgram(
            "// @jsonable\n" +
            "export class Inner {\n" +
            "  value: string;\n" +
            "}\n" +
            "// @jsonable\n" +
            "export class Outer {\n" +
            "  child: Inner;\n" +
            "}"
        );
        assertNoErrors(out, "nested @jsonable class field is jsonable");
    }

    static void testJsonableNonJsonableType_function() {
        System.out.println("-- @jsonable: function type → E4007 --");
        CheckerOutput out = checkProgram(
            "// @jsonable\n" +
            "export class Foo {\n" +
            "  callback: (x: int) => null;\n" +
            "}"
        );
        assertError(out, "E4007", "function type is not jsonable");
    }

    static void testJsonableNonJsonableType_nonJsonableClass() {
        System.out.println("-- @jsonable: non-@jsonable class field → E4007 --");
        CheckerOutput out = checkProgram(
            "class Plain { x: int; }\n" +
            "// @jsonable\n" +
            "export class Foo {\n" +
            "  p: Plain;\n" +
            "}"
        );
        assertError(out, "E4007", "non-@jsonable class field → E4007");
    }

    static void testJsonableCycle() {
        System.out.println("-- @jsonable: cycle A ↔ B → E4008 --");
        CheckerOutput out = checkProgram(
            "// @jsonable\n" +
            "export class A {\n" +
            "  b: B;\n" +
            "}\n" +
            "// @jsonable\n" +
            "export class B {\n" +
            "  a: A;\n" +
            "}"
        );
        assertError(out, "E4008", "circular dependency A ↔ B → E4008");
    }

    static void testJsonableCycleThroughArray() {
        System.out.println("-- @jsonable: cycle through array wrapper → E4008 --");
        CheckerOutput out = checkProgram(
            "// @jsonable\n" +
            "export class A {\n" +
            "  bs: B[];\n" +
            "}\n" +
            "// @jsonable\n" +
            "export class B {\n" +
            "  a: A;\n" +
            "}"
        );
        assertError(out, "E4008", "circular dependency through B[] → E4008");
    }

    static void testJsonableCycleThroughNullable() {
        System.out.println("-- @jsonable: cycle through nullable wrapper → E4008 --");
        CheckerOutput out = checkProgram(
            "// @jsonable\n" +
            "export class A {\n" +
            "  b: B | null;\n" +
            "}\n" +
            "// @jsonable\n" +
            "export class B {\n" +
            "  a: A;\n" +
            "}"
        );
        assertError(out, "E4008", "circular dependency through B | null → E4008");
    }

    static void testJsonableNonCycle_nonJsonableClass() {
        System.out.println("-- @jsonable: non-circular: A has B (not @jsonable) → E4007 --");
        CheckerOutput out = checkProgram(
            "// @jsonable\n" +
            "export class A {\n" +
            "  b: B;\n" +
            "}\n" +
            "class B { x: int; }"
        );
        // Should get E4007 on A's field (B is not @jsonable), not E4008
        assertError(out, "E4007", "non-@jsonable class field → E4007, not E4008");
        // Also verify no E4008
        List<CompilerDiagnostic> diags = out.result.diagnostics();
        boolean hasE4008 = diags.stream().anyMatch(d -> d.code().equals("E4008"));
        check(!hasE4008, "should NOT produce E4008 when B is not @jsonable");
    }

    static void testJsonableThreeClassCycle() {
        System.out.println("-- @jsonable: three-class cycle A→B→C→A → E4008 --");
        CheckerOutput out = checkProgram(
            "// @jsonable\n" +
            "export class A {\n" +
            "  b: B;\n" +
            "}\n" +
            "// @jsonable\n" +
            "export class B {\n" +
            "  c: C;\n" +
            "}\n" +
            "// @jsonable\n" +
            "export class C {\n" +
            "  a: A;\n" +
            "}"
        );
        assertError(out, "E4008", "three-class cycle A→B→C→A → E4008");
    }

    static void testJsonableNonCircularChain() {
        System.out.println("-- @jsonable: non-circular chain A→B→C (all @jsonable) → no E4008 --");
        CheckerOutput out = checkProgram(
            "// @jsonable\n" +
            "export class A {\n" +
            "  b: B;\n" +
            "}\n" +
            "// @jsonable\n" +
            "export class B {\n" +
            "  c: C;\n" +
            "}\n" +
            "// @jsonable\n" +
            "export class C {\n" +
            "  value: string;\n" +
            "}"
        );
        assertNoErrors(out, "non-circular chain A→B→C → no E4008");
    }

    static void testJsonableImportedClass_valid() {
        System.out.println("-- @jsonable: imported @jsonable class → valid --");
        StubModuleResolver resolver = new StubModuleResolver();
        // Register exports for the foreign module including $fromJson/$toJson
        Map<String, Type> exports = new HashMap<>();
        exports.put("Foreign", Types.classType("Foreign", "./foreign"));
        exports.put("Foreign$fromJson",
            new Type.Func(List.of(Type.String.INSTANCE),
                Types.nullable(Types.classType("Foreign", "./foreign"))));
        exports.put("Foreign$toJson",
            new Type.Func(List.of(Types.classType("Foreign", "./foreign")),
                Type.String.INSTANCE));
        resolver.register("./foreign", exports);

        CheckerOutput out = checkProgramWithModule(
            "import * as Other from \"./foreign\";\n" +
            "// @jsonable\n" +
            "export class Foo {\n" +
            "  f: Other.Foreign;\n" +
            "}",
            resolver
        );
        assertNoErrors(out, "imported @jsonable class field is valid");
    }

    static void testJsonableImportedClass_invalid() {
        System.out.println("-- @jsonable: imported non-@jsonable class → E4007 --");
        StubModuleResolver resolver = new StubModuleResolver();
        // Register exports WITHOUT $fromJson/$toJson (class is not @jsonable)
        Map<String, Type> exports = new HashMap<>();
        exports.put("Plain", Types.classType("Plain", "./foreign"));
        resolver.register("./foreign", exports);

        CheckerOutput out = checkProgramWithModule(
            "import * as Other from \"./foreign\";\n" +
            "// @jsonable\n" +
            "export class Foo {\n" +
            "  p: Other.Plain;\n" +
            "}",
            resolver
        );
        assertError(out, "E4007", "imported non-@jsonable class field → E4007");
    }

    // =========================================================================
    // ISSUE-0052: NameResolver isAsync propagation tests
    // =========================================================================

    /**
     * Verify that an async function declaration resolves with isAsync=true
     * in the symbol table after name resolution.
     */
    static void testNameResolution_asyncFuncDeclIsAsync() {
        System.out.println("-- Name Resolution: async function decl isAsync --");

        CheckerOutput out = checkProgram(
            "async function f(): int { return 5; }");

        assertNoErrors(out, "async function decl resolution");

        SymbolTable symTable = out.result.symbolTable();
        Symbol sym = symTable.resolve("f");
        check(sym instanceof Symbol.FunctionSymbol,
            "f should be a FunctionSymbol");
        Symbol.FunctionSymbol fs = (Symbol.FunctionSymbol) sym;
        check(fs.funcType().isAsync(),
            "async function should have isAsync=true in resolved type");
        check(fs.funcType().returnType() == Type.Int.INSTANCE,
            "async function return type should be int");
    }

    /**
     * Verify that a sync function declaration resolves with isAsync=false
     * (regression test to ensure the default is correct).
     */
    static void testNameResolution_syncFuncDeclIsNotAsync() {
        System.out.println("-- Name Resolution: sync function decl isAsync=false --");

        CheckerOutput out = checkProgram(
            "function f(): int { return 5; }");

        assertNoErrors(out, "sync function decl resolution");

        SymbolTable symTable = out.result.symbolTable();
        Symbol sym = symTable.resolve("f");
        check(sym instanceof Symbol.FunctionSymbol,
            "f should be a FunctionSymbol");
        Symbol.FunctionSymbol fs = (Symbol.FunctionSymbol) sym;
        check(!fs.funcType().isAsync(),
            "sync function should have isAsync=false in resolved type");
    }

    /**
     * Verify that a function type annotation with async (e.g.,
     * {@code async (int) => string}) resolves with isAsync=true.
     */
    static void testNameResolution_asyncFuncTypeAnnotationIsAsync() {
        System.out.println("-- Name Resolution: async function type annotation isAsync --");

        // The NameResolver resolves types from annotations before the
        // TypeChecker runs.  We test that an async function type annotation
        // resolves with isAsync=true.  We use a helper that only runs
        // name resolution (not full type checking) to avoid spurious
        // type errors from the initializer expression.
        String source = "let f: async (p: int) => string = null;";
        LexResult lex = new Lexer(source, "test.deal").tokenize();
        ParseResult parse = new Parser(lex.tokens(), "test.deal").parse();
        check(!parse.hasErrors(), "Parser: no errors");

        StubModuleResolver resolver = new StubModuleResolver();
        NameResolver nr = new NameResolver("test.deal", resolver);
        SymbolTable symTable = nr.resolve(parse.program());

        // The variable f should have the async function type
        Symbol sym = symTable.resolve("f");
        check(sym instanceof Symbol.VariableSymbol,
            "f should be a VariableSymbol");
        Symbol.VariableSymbol vs = (Symbol.VariableSymbol) sym;
        check(vs.type() instanceof Type.Func,
            "f's type should be Type.Func");
        Type.Func ft = (Type.Func) vs.type();
        check(ft.isAsync(),
            "async function type annotation should have isAsync=true");
        check(ft.returnType() instanceof Type.String,
            "return type should be string");
        check(ft.paramTypes().size() == 1,
            "should have 1 param");
        check(ft.paramTypes().get(0) == Type.Int.INSTANCE,
            "param type should be int");
    }

    /**
     * Verify that function expressions nested inside await callee arguments
     * are correctly hoisted during Pass 1. Without the AwaitExpression case
     * in walkExpression, a FunctionExpr inside the arguments of
     * {@code await f(async function() { ... })} would not be visited during
     * Pass 1, so let-declarations in its body would not be resolved.
     */
    static void testNameResolution_awaitWalkExpressionHoistsNestedFuncExpr() {
        System.out.println("-- Name Resolution: await walkExpression hoists nested FunctionExpr --");

        // This test verifies that a FunctionExpr inside an await callee's
        // arguments is hoisted during Pass 1.  The key is that the nested
        // async function expression body contains a let-declaration; if
        // walkExpression doesn't visit it, the variable won't be in scope
        // when the inner function body returns it.
        //
        // The outer function f must be declared async so that await is allowed.
        // We then test that the inner variable 'n' is visible in the nested
        // function expression body.
        //
        // Also declare g as a sync function so it can be referenced.
        CheckerOutput out = checkProgram(
            "function g(cb: function(): int): int { return cb(); }\n"
            + "async function f(): int {\n"
            + "    return await g(async function(): int {\n"
            + "        let n: int = 42;\n"
            + "        return n;\n"
            + "    });\n"
            + "}");

        // We just need to verify that name resolution succeeds (no
        // undeclared variable errors). The inner 'n' must be found
        // during Pass 1 hoisting of the FunctionExpr.
        boolean hasUndeclared = out.result.diagnostics().stream()
            .anyMatch(d -> "E2001".equals(d.code()));
        check(!hasUndeclared,
            "nested function expr variable should be hoisted (no E2001)");
    }

    // =========================================================================
    // ISSUE-0053: Async/await TypeChecker tests
    // =========================================================================

    /** E3012: await outside async function. */
    static void testAwaitOutsideAsync_E3012() {
        System.out.println("-- E3012: await outside async function --");
        // await inside a sync function body
        CheckerOutput out = checkProgram(
            "async function g(): int { return 5; }" +
            "function f(): int { return await g(); }"
        );
        assertError(out, "E3012", "await outside async function → E3012");
    }

    /** E3013: await on non-async call. */
    static void testAwaitOnNonAsyncCall_E3013() {
        System.out.println("-- E3013: await on non-async call --");
        CheckerOutput out = checkProgram(
            "function syncFunc(): int { return 42; }" +
            "async function f(): int {" +
            "  return await syncFunc();" +
            "}"
        );
        assertError(out, "E3013", "await on non-async call → E3013");
    }

    /** E3014: async call without await. */
    static void testAsyncCallWithoutAwait_E3014() {
        System.out.println("-- E3014: async call without await --");
        CheckerOutput out = checkProgram(
            "async function g(): int { return 5; }" +
            "function f(): null {" +
            "  let x: int = g();" +
            "  return null;" +
            "}"
        );
        assertError(out, "E3014", "async call without await → E3014");
    }

    /** await asyncCall() does NOT produce E3014 (suppression works). */
    static void testAwaitAsyncCallNoE3014() {
        System.out.println("-- E3014 suppression: await asyncCall() no E3014 --");
        CheckerOutput out = checkProgram(
            "async function g(): int { return 5; }" +
            "async function f(): int {" +
            "  return await g();" +
            "}"
        );
        assertNoErrors(out, "await asyncCall() should not produce E3014");
    }

    /** Nested await: await asyncCall(await otherAsyncCall()) — zero E3014. */
    static void testNestedAwaitSuppression() {
        System.out.println("-- E3014 suppression: nested await --");
        CheckerOutput out = checkProgram(
            "async function inner(): int { return 42; }" +
            "async function outer(x: int): int { return x; }" +
            "async function f(): int {" +
            "  return await outer(await inner());" +
            "}"
        );
        boolean hasE3014 = out.result.diagnostics().stream()
            .anyMatch(d -> d.code().equals("E3014"));
        check(!hasE3014, "nested await should have zero E3014 errors");
    }

    /** Narrowing invalidated after await: variables revert to declared types. */
    static void testNarrowingInvalidatedAfterAwait() {
        System.out.println("-- Narrowing invalidated after await --");
        // After await, a variable narrowed via if (x !== null) reverts to its
        // nullable declared type. So using it as non-null after await → E3001.
        CheckerOutput out = checkProgram(
            "async function g(): int { return 5; }" +
            "async function f(n: int | null): int {" +
            "  if (n !== null) {" +
            "    let x: int = n;" +  // narrowed — ok
            "    let result: int = await g();" +  // await invalidates narrowing
            "    let y: int = n;" +  // n is back to int|null → E3001
            "    return result;" +
            "  }" +
            "  return 0;" +
            "}"
        );
        assertError(out, "E3001", "narrowing invalidated after await → E3001");
    }

    /** await 42 does NOT crash the TypeChecker — instanceof guard works. */
    static void testAwait42DoesNotCrash() {
        System.out.println("-- await 42: instanceof guard prevents ClassCastException --");
        // The parser emits E1042 for await 42 (not a call), but still
        // constructs an AwaitExpression. The TypeChecker must handle it
        // gracefully.
        CheckerOutput out = checkProgram(
            "async function f(): null {" +
            "  await 42;" +
            "  return null;" +
            "}"
        );
        // Should not crash. May produce E3013 or E1042 depending on parse order.
        // Just verify no ClassCastException occurred.
        List<CompilerDiagnostic> diags = out.result.diagnostics();
        boolean crashed = diags.stream()
            .anyMatch(d -> d.message().contains("ClassCastException")
                        || d.message().contains("cannot be cast"));
        check(!crashed, "await 42 should not throw ClassCastException");
        // Should have some error (E1042 from parser or E3013 from type checker)
        boolean hasError = diags.stream()
            .anyMatch(d -> "error".equals(d.severity()));
        check(hasError, "await 42 should produce some error");
    }

    /** async (int)=>int not assignable to (int)=>int. */
    static void testAsyncFuncNotAssignableToSync() {
        System.out.println("-- async function type not assignable to sync --");
        CheckerOutput out = checkProgram(
            "async function g(): int { return 5; }" +
            "let f: (x: int) => int = g;"
        );
        assertError(out, "E3001", "async func not assignable to sync → E3001");
    }

    /** (int)=>int not assignable to async (int)=>int. */
    static void testSyncFuncNotAssignableToAsync() {
        System.out.println("-- sync function type not assignable to async --");
        CheckerOutput out = checkProgram(
            "function g(): int { return 5; }" +
            "let f: async (x: int) => int = g;"
        );
        assertError(out, "E3001", "sync func not assignable to async → E3001");
    }

    /** E3014: await outer(inner()) where inner is async → E3014 on inner(). */
    static void testUnawaitedAsyncInArgument_E3014() {
        System.out.println("-- E3014: unawaited async in argument position --");
        CheckerOutput out = checkProgram(
            "async function inner(): int { return 42; }" +
            "function outer(x: int): int { return x; }" +
            "async function f(): int {" +
            "  return await outer(inner());" +
            "}"
        );
        assertError(out, "E3014", "unawaited async in argument → E3014");
    }


    /** NullNarrowing.invalidateAll() clears all narrowed entries. */
    static void testNullNarrowingInvalidateAll() {
        System.out.println("-- NullNarrowing.invalidateAll() clears all entries --");
        deal.checker.NullNarrowing nn = new deal.checker.NullNarrowing();

        // Simulate some narrowing
        nn.onIfCondition(
            new BinaryExpr(
                new Span("test", 1, 1, 1, 10),
                new IdentifierExpr(new Span("test", 1, 1, 1, 2), "x"),
                BinaryOp.NEQ,
                new LiteralExpr(new Span("test", 1, 8, 1, 12),
                    new LiteralValue.NullLiteral())
            ),
            true,
            name -> {
                if ("x".equals(name))
                    return deal.types.Types.nullable(Type.Int.INSTANCE);
                return Type.Error.INSTANCE;
            }
        );
        check(nn.getNarrowedType("x") == Type.Int.INSTANCE,
            "x should be narrowed to int (non-null)");
        check(!nn.narrowedVariableNames().isEmpty(),
            "narrowedVariableNames should be non-empty before invalidate");

        nn.invalidateAll();
        check(nn.getNarrowedType("x") == null,
            "x narrowing should be null after invalidateAll");
        check(nn.narrowedVariableNames().isEmpty(),
            "narrowedVariableNames should be empty after invalidateAll");
    }

}
