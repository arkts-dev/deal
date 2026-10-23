package deal.test;

import deal.ast.*;
import deal.checker.*;
import deal.diagnostics.CompilerDiagnostic;
import deal.identity.CanonicalClassIdentity;
import deal.diagnostics.DiagnosticNote;
import deal.diagnostics.DiagnosticOrder;
import deal.diagnostics.DiagnosticRange;
import deal.diagnostics.RangeOrigin;
import deal.lexer.*;
import deal.parser.*;
import deal.types.Type;
import deal.types.Types;
import deal.Main;
import deal.test.IdentityTestFixtures;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

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
        ParseResult parse = new Parser(lex.tokens(), filename, lex.directiveEvents()).parse();

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
            String filename, ModuleResolver resolver) {
        LexResult lex = new Lexer(source, filename).tokenize();
        ParseResult parse = new Parser(lex.tokens(), filename, lex.directiveEvents()).parse();

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

    /** Asserts one exact diagnostic code naming one exact message. */
    private static void assertErrorMessage(CheckerOutput out, String code,
            String message, String context) {
        List<CompilerDiagnostic> diags = out.result.diagnostics();
        boolean found = diags.stream().anyMatch(
            d -> d.code().equals(code) && message.equals(d.message()));
        if (!found) {
            System.err.println("  Expected " + code + " '" + message
                + "' but got: " + diags);
        }
        check(found, context + ": expected diagnostic " + code + " '"
            + message + "'");
    }

    private record CliResult(int exitCode, String output) {}

    private static Path cliProject(Path root, String name, String source) throws IOException {
        Path project = root.resolve(name);
        Files.createDirectories(project.resolve("src"));
        Files.writeString(project.resolve("deal.json"),
            "{\"languageVersion\":\"1.3\",\"backend\":\"js\",\"moduleRoots\":[\"src\"]}\n");
        Path entry = project.resolve("src/main.deal");
        Files.writeString(entry, source);
        return entry;
    }

    private static CliResult cliCompile(Path entry, String backend, Path out) throws IOException {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        try (PrintStream capture = new PrintStream(err, true, StandardCharsets.UTF_8)) {
            System.setErr(capture);
            int exitCode = Main.run(new String[]{"compile", entry.toString(),
                "--backend", backend, "--output", out.toString()});
            capture.flush();
            return new CliResult(exitCode, err.toString(StandardCharsets.UTF_8));
        } finally {
            System.setErr(originalErr);
        }
    }

    private static void cliReject(Path entry, Path out, String backend, String what) throws IOException {
        CliResult result = cliCompile(entry, backend, out);
        check(result.exitCode() == 1, what + " is rejected on " + backend + ": " + result.output());
        check(result.output().contains("E1057"), what + " local-class placement rejection is E1057 on " + backend + ": " + result.output());
        check(!Files.exists(out), what + " publishes no artifact directory on " + backend);
    }

    private static void cliAccept(Path entry, Path out, String backend, String what) throws IOException {
        CliResult result = cliCompile(entry, backend, out);
        check(result.exitCode() == 0, what + " compiles on " + backend + ": " + result.output());
    }

    private static void cliNode(Path out, String token) throws IOException, InterruptedException {
        Process node = new ProcessBuilder("node", "main.js").directory(out.toFile())
            .redirectErrorStream(true).start();
        String output = new String(node.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = node.waitFor();
        check(exitCode == 0 && output.contains(token), token + " executes (exit " + exitCode + "): " + output);
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

        testNameResolution_asyncFuncDeclIsAsync();
        testNameResolution_syncFuncDeclIsNotAsync();
        testNameResolution_asyncFuncTypeAnnotationIsAsync();
        testNameResolution_awaitWalkExpressionHoistsNestedFuncExpr();

        testAwaitOutsideAsync_E3012();
        testAwaitOnNonAsyncCall_E3013();
        testAsyncCallWithoutAwait_E3014();
        testAwaitAsyncCallNoE3014();
        testNestedAwaitSuppression();
        testUnawaitedAsyncInArgument_E3014();
        testNarrowingInvalidatedAfterAwait();
        testCatchBindingNarrowingIsolation();
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

        testBytesEqualityAdmission();
        testBytesComparisonNonAdmittedUnchanged();
        testBytesComparisonAdmissionNegatives();

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
        // D4 (deal-v1.2-int32-and-bytes-architecture): bytes .length too
        testBytesLengthAssignmentRejected();
        testBytesLengthDeleteRejected();
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

        testLocalNullableClassMemberAccessRejected();
        testLocalNullableClassMemberAccessNarrowedOk();
        testNullablePropertyPathAndAssignmentInvalidation();
        testNestedDeclarationDoesNotClearEnclosingPending();
        testShadowingInitializerReadsEnclosingNarrowing();
        testShadowingInitializerAssignmentTargetsEnclosingBinding();
        testAwaitInsideShadowingBlockInvalidatesSnapshot();
        testLoopLocalShadowDoesNotInvalidateEnclosingNarrowing();
        testIfJoinPropagatesInvalidation();
        testShadowStateSurvivesBranch();
        testForOfAssignmentInvalidatesEnclosingNarrowing();
        testForHeadDeclarationOrder();
        testClassProvenanceAtInitializationAndNullableTarget();
        testSameNameSiblingDeclarationsPublicCompile();
        testClassProvenanceThroughAssignmentExpressions();
        testClassProvenanceThroughConstructedLiterals();
        testClassProvenanceConstructedLiteralPublicCompile();
        testClassProvenanceContextualConstructionUnderShadowing();
        testClassProvenanceAssignmentExprPublicCompile();
        testNestedClass();
        // ISSUE-0717 (defect snippet 47): a nested class that shadows an
        // enclosing class of the same name stays a distinct declaration;
        // the cross-declaration assignment is E3019.
        testNestedClassShadowingRejected();
        testErrorClassConstruction();
        // F9: Class field default values type-checked
        testClassDefaultValueTypeError();
        testClassDefaultExpressionsTyped();

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

        // ISSUE-0815: the retained corner-case frontend contracts
        testRetainedCornerCaseFrontendContract();

        // The closed direct contextual-read admission: nested literal and
        // template-interpolation reads reject, the direct positions stay
        // admitted (v1.3 §Reading table fields).
        testV13NestedLiteralReadRejected();
        testV13DirectContextualReadControls();

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

        testVoidTypeNameProducesE3004();

        // Runtime intrinsics: int() and number()
        testIntrinsicIntRejectsIntLiteral();
        testIntrinsicNumberRejectsBoolean();

        testCoroutineImportRejected();

        testE4006_classErrorProhibition();

        testE2008_dollarInIdentifier();

        testE2008_dollarInRestParam();
        testE2008_dollarInForLoopVar();
        testE2008_dollarInForOfLoopVar();
        testE2008_dollarInCatchVar();
        testE2008_dollarInCatchVarRejectedOnAllTargets();

        testE4006_errorVariantsNoError();

        testE2008_dollarIntegration_nullReturn();
        testE2008_dollarIntegration_validImport();

        testForOfScoping_loopVarInBody();
        testForOfScoping_iterableCannotRefLoopVar();
        testForOfScoping_iterableRefsOuterVar();
        testForOfScoping_breakInside();
        testForOfScoping_continueInside();
        testForOfTypeCheck_stringCorrect();
        testForOfTypeCheck_nonIterable();
        testV12StringTypeFacingChecks();
        testV12FunctionTypeRestRejected();
        testV12BytesClassSymbolFirstResolution();
        testForOfTypeCheck_tableIterable();
        testForOfTypeCheck_arrayWrongVarType();
        testForOfTypeCheck_stringWrongVarType();

        testTemplateLiteral_stringParts();
        testTemplateLiteral_nonStringInterpolation();
        testTemplateLiteral_typeInference();
        testTemplateLiteral_nameResolution();

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
        testJsonableImportedSameNameDistinctIdentity();
        testImportedClassProviderWithSameNamedLocal();
        testImportedProviderDeleteBesideSameNamedLocal();
        testImportedProviderCrossingRejected();
        testCallReceiverAndFunctionTypedProviderResolution();
        testJsonableImportedThroughArrayAndNullable();
        testJsonableLocalCycleWithSameNamedImported();

        testSymbolTableSymbolsInsertionOrder();
        testSymbolTableAliasSelectionDefinitionOrder();

        // D1 canonical report-time diagnostic ordering
        // (deterministic-diagnostics): helper-order assertions plus the
        // both-surfaces assertion over a real Main.run compile.
        testDiagnosticOrderHelper();
        testDiagnosticOrderBothSurfaces();

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
            "let f: int = 2 * 3;"
        );
        assertNoErrors(out, "int arithmetic");
        assertError(checkProgram("let f: int = 2 ** 3;"), "E1037", "removed power operator rejects");

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

    /**
     * Equal bytes-containing types and nullable-bytes-vs-null pairs are
     * checker-admitted as {@code boolean} — bytes compare by reference
     * identity (docs/spec-v1.3.md §Runtime type descriptor format,
     * Equality semantics). The frontend resolves
     * the {@code bytes} type name and the {@code bytes(n)} intrinsic, so
     * the admission path is driven from real source. The former E3019
     * bytes-comparison gate is lifted; the closed BYTES_EQ/BYTES_NE
     * comparison row and the bytes descriptor carry the pair through
     * lowering.
     */
    static void testBytesEqualityAdmission() {
        System.out.println("-- Bytes Equality Admission (ISSUE-0158 gate lift) --");

        // bytes === bytes / bytes !== bytes: boolean-typed, no diagnostics.
        CheckerOutput out = checkProgram(
            "function f(): null {\n"
            + "  let a: bytes = bytes(2);\n"
            + "  let b: bytes = a;\n"
            + "  let c: boolean = a === b;\n"
            + "  let d: boolean = a !== bytes(2);\n"
            + "  return null;\n"
            + "}"
        );
        assertNoErrors(out, "bytes ===/!== bytes admitted");
        Type comparisonType = out.result.typeMap().get(firstComparisonExpr(out.program()));
        check(comparisonType == Type.Boolean.INSTANCE,
            "bytes === bytes is typed boolean, got " + comparisonType);

        // bytes[] === bytes[] (equal reference shapes containing bytes).
        out = checkProgram(
            "function f(): null {\n"
            + "  let xs: bytes[] = [bytes(1)];\n"
            + "  let ys: bytes[] = xs;\n"
            + "  let c: boolean = xs === ys;\n"
            + "  return null;\n"
            + "}"
        );
        assertNoErrors(out, "bytes[] === bytes[] admitted");

        // bytes|null === bytes|null (equal nullable bytes pairs).
        out = checkProgram(
            "function f(): null {\n"
            + "  let a: bytes | null = bytes(1);\n"
            + "  let b: bytes | null = a;\n"
            + "  let c: boolean = a === b;\n"
            + "  return null;\n"
            + "}"
        );
        assertNoErrors(out, "bytes|null === bytes|null admitted");

        // bytes|null === null / null === bytes|null (both directions).
        out = checkProgram(
            "function f(): null {\n"
            + "  let a: bytes | null = bytes(1);\n"
            + "  let b: bytes | null = null;\n"
            + "  let c: boolean = a === null;\n"
            + "  let d: boolean = b === null;\n"
            + "  let e: boolean = null === a;\n"
            + "  let g: boolean = a !== null;\n"
            + "  return null;\n"
            + "}"
        );
        assertNoErrors(out, "bytes|null vs null admitted in both directions");

        // Functions whose signatures contain bytes compare by identity.
        out = checkProgram(
            "function id(b: bytes): bytes { return b; }\n"
            + "function f(): null {\n"
            + "  let c: boolean = id === id;\n"
            + "  return null;\n"
            + "}"
        );
        assertNoErrors(out, "bytes-bearing function identity admitted");
    }

    /** Finds the first {@link BinaryExpr} comparison in a function body (test fixture). */
    private static BinaryExpr firstComparisonExpr(ProgramNode program) {
        for (StatementNode stmt : program.statements()) {
            if (stmt instanceof FunctionDeclaration fd) {
                for (StatementNode bodyStmt : fd.body().statements()) {
                    if (bodyStmt instanceof VariableDeclaration vd
                            && vd.initializer() instanceof BinaryExpr bin) {
                        return bin;
                    }
                }
            }
        }
        fail("no comparison binary expression found in the fixture program");
        return null;
    }

    static void testBytesComparisonNonAdmittedUnchanged() {
        System.out.println("-- Bytes comparisons: non-admitted pairs keep E3006/E3007 --");

        // bytes === number — not admitted by the equality rules → E3006.
        CheckerOutput out = checkProgram(
            "function f(): null {\n"
            + "  let b: bytes = bytes(1);\n"
            + "  let c: boolean = b === 1;\n"
            + "  return null;\n"
            + "}"
        );
        assertError(out, "E3006", "bytes === number keeps E3006");

        // bytes relational — relationals stay E3007 (spec pins relationals
        // to int/number/string).
        out = checkProgram(
            "function f(): null {\n"
            + "  let b: bytes = bytes(1);\n"
            + "  let c: boolean = b < b;\n"
            + "  return null;\n"
            + "}"
        );
        assertError(out, "E3007", "bytes relational keeps E3007");
    }

    static void testBytesComparisonAdmissionNegatives() {
        System.out.println("-- Bytes equality admission: non-bytes pairs are unaffected --");

        // null === null, nullable-vs-null, equal nullable pairs, array and
        // function reference identity — every non-bytes admitted pair
        // stays admitted (no E3019 exists anymore).
        CheckerOutput out = checkProgram(
            "function f(): null {\n"
            + "  let a: boolean = null === null;\n"
            + "  let b: boolean = null !== null;\n"
            + "  return null;\n"
            + "}"
        );
        assertNoErrors(out, "null ===/!== null unaffected");

        out = checkProgram(
            "function f(n: int | null): null {\n"
            + "  let a: boolean = n === null;\n"
            + "  let b: boolean = null === n;\n"
            + "  let c: boolean = n !== null;\n"
            + "  return null;\n"
            + "}"
        );
        assertNoErrors(out, "nullable-vs-null unaffected");

        out = checkProgram(
            "function f(n: int | null): null {\n"
            + "  let a: boolean = n === n;\n"
            + "  return null;\n"
            + "}"
        );
        assertNoErrors(out, "equal nullable pair unaffected");

        out = checkProgram(
            "function f(): null {\n"
            + "  let xs: int[] = [1, 2];\n"
            + "  let a: boolean = xs === xs;\n"
            + "  return null;\n"
            + "}"
        );
        assertNoErrors(out, "array reference identity unaffected");

        out = checkProgram(
            "function f(): null {\n"
            + "  function g(): null { return null; }\n"
            + "  let a: boolean = g === g;\n"
            + "  return null;\n"
            + "}"
        );
        assertNoErrors(out, "function reference identity unaffected");

        out = checkProgram(
            "function f(): null {\n"
            + "  let t: table = {};\n"
            + "  let a: boolean = t === t;\n"
            + "  return null;\n"
            + "}"
        );
        assertNoErrors(out, "table reference identity unaffected");
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
        assertNoErrors(lit, "table index write allowed without E3007");
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

    // D4 (deal-v1.2-int32-and-bytes-architecture): bytes .length is
    // compiler-resolved and read-only exactly like array .length —
    // assignment and deletion are E3017 at the member target.
    static void testBytesLengthAssignmentRejected() {
        System.out.println("-- Bytes Length Assignment Rejected (E3017) --");
        CheckerOutput out = checkProgram(
            "let b: bytes = bytes(4);\n" +
            "b.length = 9;"
        );
        assertError(out, "E3017", "assignment to bytes .length");
        // E3017 is reported at the target span (line 2, column 1 — the
        // start of `b.length`), not the value span.
        boolean atTarget = out.result.diagnostics().stream()
            .filter(d -> d.code().equals("E3017"))
            .anyMatch(d -> d.line() == 2 && d.column() == 1);
        check(atTarget, "bytes E3017 must be reported at the target span");
    }

    static void testBytesLengthDeleteRejected() {
        System.out.println("-- Bytes Length Delete Rejected (E3017) --");
        CheckerOutput out = checkProgram(
            "let b: bytes = bytes(4);\n" +
            "delete b.length;"
        );
        assertError(out, "E3017", "delete of bytes .length");
        // E3017 is reported at the target span (line 2, column 8 — the
        // start of `b.length` after `delete `).
        boolean atTarget = out.result.diagnostics().stream()
            .filter(d -> d.code().equals("E3017"))
            .anyMatch(d -> d.line() == 2 && d.column() == 8);
        check(atTarget, "bytes E3017 must be reported at the target span");
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
        // The v1.3 admission list is closed (docs/spec-v1.3.md "Reading
        // table fields"): a loop condition requires boolean checking but
        // grants no contextual-read permission, so a table read in the
        // condition is E3003 at the read while the boolean requirement
        // still applies to every non-read condition.
        CheckerOutput read = checkProgram(
            "let t: table = { flag: true };\n" +
            "while (t.flag) { break; }"
        );
        assertError(read, "E3003", "while condition table read rejects E3003");
        CheckerOutput booleanRequirement = checkProgram(
            "let t: table = { flag: true };\n" +
            "while (t) { break; }"
        );
        assertError(booleanRequirement, "E3007",
            "while condition still requires boolean");
    }

    static void testForConditionContextualTyping() {
        System.out.println("-- F6: For condition contextual typing --");
        CheckerOutput read = checkProgram(
            "let t: table = { flag: true };\n" +
            "for (let i: int = 0; i < 10 && t.flag; i = i + 1) { break; }"
        );
        assertError(read, "E3003",
            "for condition table read rejects E3003");
        CheckerOutput booleanRequirement = checkProgram(
            "let t: table = { flag: true };\n" +
            "for (let i: int = 0; t; i = i + 1) { break; }"
        );
        assertError(booleanRequirement, "E3007",
            "for condition still requires boolean");
    }

    /**
     * The retained corner-case frontend contracts (ISSUE-0815 subjects
     * 9-11 plus their legal controls): the closed v1.3 contextual table-read
     * admission rejects the loop-condition and composite-operand reads with
     * E3003 at the read, the try/catch-local obligation rejects a
     * definitely-returning try with a fallthrough catch with E5002 at the
     * catch block even behind a later return, and the legal controls — the
     * five direct read positions, the throw-only try with a fallthrough
     * catch, and catch return/throw — keep compiling.
     */
    static void testRetainedCornerCaseFrontendContract() {
        System.out.println("-- retained corner-case frontend contract:"
            + " contextual-read negatives and the catch obligation --");

        // Subject 9: a while condition grants boolean checking but no
        // contextual-read permission.
        CheckerOutput whileRead = checkProgram(
            "export function f(): boolean {let t: table = {flag:true};"
                + " while(t.flag) {return true;} return false;}\n");
        assertError(whileRead, "E3003", "while-condition table read rejects E3003");
        checkExactRange(whileRead, "E3003", 1, 65, 1, 71,
            "the while-condition read carries its own exact range");

        // Subject 10: the t.flag operand of && does not inherit the
        // enclosing return's permission.
        CheckerOutput logicalRead = checkProgram(
            "export function f(): boolean {let t: table = {flag:true};"
                + " return t.flag && true;}\n");
        assertError(logicalRead, "E3003",
            "composite-operand table read rejects E3003");
        checkExactRange(logicalRead, "E3003", 1, 66, 1, 72,
            "the composite operand carries its own exact range");

        // Subject 11: a definitely-returning try with a fallthrough catch
        // rejects at the catch block even though a later return follows.
        CheckerOutput catchFallthrough = checkProgram(
            "export function f(): int {try {return 1;} catch(e) {} return 2;}\n");
        assertError(catchFallthrough, "E5002",
            "the fallthrough catch rejects E5002");
        checkExactRange(catchFallthrough, "E5002", 1, 52, 1, 54,
            "the catch obligation carries the catch block's exact range");

        // The obligation is independent of the enclosing body's return:
        // the mutual-recursion shape republishes the same E5002 at the
        // catch, never the whole-function falloff.
        CheckerOutput recursive = checkProgram(
            "export function main(): null { function f(): null {try {return null;}"
                + " catch(e) {g();}} function g(): null {f();} f(); return null; }\n");
        assertError(recursive, "E5002",
            "the recursive definitely-returning try rejects E5002");
        checkExactRange(recursive, "E5002", 1, 80, 1, 86,
            "the recursive catch carries its own exact range");

        // Function expressions carry the same obligation.
        CheckerOutput expression = checkProgram(
            "export function f(): null {let g: () => int = function(): int"
                + " {try {return 1;} catch(e) {} return 2;}; return null;}\n");
        assertError(expression, "E5002",
            "the function-expression catch rejects E5002");

        // Legal control: a throw-only try does not trigger the rule, so a
        // fallthrough catch with a valid later return compiles.
        CheckerOutput throwOnly = checkProgram(
            "export function f(): null {try {throw {message:\"x\"};}"
                + " catch(e) {} return null;}\n");
        assertNoErrors(throwOnly, "a throw-only try with a fallthrough catch"
            + " stays legal");

        // Legal controls: catch return and catch throw after a
        // definitely-returning try stay accepted.
        assertNoErrors(checkProgram(
            "export function f(): int {try {return 1;} catch(e) {return 2;}}\n"),
            "a returning catch stays legal");
        assertNoErrors(checkProgram(
            "export function f(): int {try {return 1;} catch(e) {throw e;}}\n"),
            "a throwing catch stays legal");

        // Legal controls: all five admitted direct read positions.
        assertNoErrors(checkProgram(
            "export function f(t: table): int {let x: int = t.n; x = t.n;"
                + " if (t.flag) {return t.n;} return x;}\n"),
            "the direct let/assignment/if/return reads stay admitted");
        assertNoErrors(checkProgram(
            "function g(n: int): null {return null;}"
                + " export function f(t: table): null {g(t.n); return null;}\n"),
            "the direct argument read stays admitted");
    }

    /**
     * The closed direct contextual-read admission (docs/spec-v1.3.md
     * §Reading table fields): only the position's own outermost
     * expression may be the read. A read nested in an array literal
     * element, a class literal field value or a template interpolation
     * part is E3003 at the read — the dotted and the computed form
     * alike — and the surrounding literal's own diagnostic never
     * replaces or doubles it.
     */
    static void testV13NestedLiteralReadRejected() {
        System.out.println("-- nested literal/template table reads reject E3003 --");

        // Array literal element (dotted).
        CheckerOutput arrayElement = checkProgram(
            "let t: table = {x:1}; let xs: int[] = [t.x];");
        assertError(arrayElement, "E3003",
            "array literal element table read rejects E3003");
        checkExactRange(arrayElement, "E3003", 1, 40, 1, 43,
            "the array element read carries its own exact range");
        checkNoCode(arrayElement, "E3011",
            "the rejected element is not re-reported as a mixed literal");

        // Computed array literal element: the static string key grants no
        // admission either.
        CheckerOutput computedElement = checkProgram(
            "let t: table = {x:1}; let xs: int[] = [t[\"x\"]];");
        assertError(computedElement, "E3003",
            "computed array literal element table read rejects E3003");
        checkExactRange(computedElement, "E3003", 1, 40, 1, 46,
            "the computed element read carries its own exact range");

        // Class literal field value.
        CheckerOutput classField = checkProgram(
            "class Box {n: int = 0;} let t: table = {x:1};"
                + " let b: Box = {n:t.x};");
        assertError(classField, "E3003",
            "class literal field table read rejects E3003");
        checkExactRange(classField, "E3003", 1, 63, 1, 66,
            "the class literal field read carries its own exact range");
        checkNoCode(classField, "E4003",
            "the rejected field value is not re-reported as a field type mismatch");

        // Computed class literal field value: the static string key grants
        // no admission either.
        CheckerOutput computedField = checkProgram(
            "class Box {n: int = 0;} let t: table = {x:1};"
                + " let b: Box = {n:t[\"x\"]};");
        assertError(computedField, "E3003",
            "computed class literal field table read rejects E3003");
        checkExactRange(computedField, "E3003", 1, 63, 1, 69,
            "the computed field read carries its own exact range");

        // A class literal nested in an array literal keeps the read's own
        // diagnostic at the read.
        CheckerOutput nestedClass = checkProgram(
            "class Box {n: int = 0;} let t: table = {x:1};"
                + " let bs: Box[] = [{n:t.x}];");
        assertError(nestedClass, "E3003",
            "a read nested two literals deep rejects E3003");
        checkExactRange(nestedClass, "E3003", 1, 67, 1, 70,
            "the doubly nested read carries its own exact range");

        // Template interpolation: the part's string requirement is not a
        // dynamic-read context, and the already-rejected part is never
        // re-reported as E3016.
        CheckerOutput interpolation = checkProgram(
            "let t: table = {s:\"a\"}; let n: string = `v${t.s}`;");
        assertError(interpolation, "E3003",
            "template interpolation table read rejects E3003");
        checkExactRange(interpolation, "E3003", 1, 45, 1, 48,
            "the interpolation read carries its own exact range");
        checkNoCode(interpolation, "E3016",
            "the rejected interpolation part is not re-reported as E3016");

        // The computed interpolation form rejects at its own read too.
        CheckerOutput computedInterpolation = checkProgram(
            "let t: table = {s:\"a\"}; let n: string = `a${t[\"s\"]}`;");
        assertError(computedInterpolation, "E3003",
            "computed interpolation table read rejects E3003");
        checkExactRange(computedInterpolation, "E3003", 1, 45, 1, 51,
            "the computed interpolation read carries its own exact range");
        checkNoCode(computedInterpolation, "E3016",
            "the rejected computed part is not re-reported as E3016");
    }

    /**
     * The direct-read controls of the same admission: all five admitted
     * positions (parenthesized forms included), the computed direct read,
     * an explicitly typed intermediate used inside an array/class literal,
     * contextual class and array construction, and an ordinary template
     * interpolation of a statically typed string.
     */
    static void testV13DirectContextualReadControls() {
        System.out.println("-- the five direct read positions and typed"
            + " intermediates stay admitted --");

        assertNoErrors(checkProgram(
            "function take(n: int): int {return n;}"
                + " function readN(t: table): int {return t.n;}"
                + " let t: table = {n:1, flag:true};"
                + " let a: int = (t.n); a = t.n; a = take(t.n);"
                + " if (t.flag) {a = 1;} a = readN(t);"),
            "the five direct positions (parenthesized initializer included)"
                + " stay admitted");

        assertNoErrors(checkProgram(
            "let t: table = {n:1}; let c: int = t[\"n\"];"),
            "the direct computed read stays admitted");

        assertNoErrors(checkProgram(
            "class Box {n: int = 0;} let t: table = {n:1, s:\"a\"};"
                + " let v: int = t.n; let xs: int[] = [v];"
                + " let b: Box = {n: v}; let bs: Box[] = [{n: v}];"
                + " let sv: string = t.s; let s: string = `v${sv}`;"),
            "a typed intermediate inside literals, contextual class/array"
                + " construction and an ordinary template stay admitted");

        assertNoErrors(checkProgram(
            "class Box {n: int = 0;} let t: table = {x:1};"
                + " let xs: int[] = [0]; xs[0] = t.x;"
                + " let b: Box = {}; b.n = t.x;"),
            "an assignment value is a direct position: the array-element"
                + " and class-field write values stay admitted");
    }

    /** Asserts that no diagnostic of one code was reported. */
    private static void checkNoCode(CheckerOutput out, String code,
            String context) {
        boolean found = out.result.diagnostics().stream()
            .anyMatch(d -> d.code().equals(code));
        if (found) {
            for (CompilerDiagnostic d : out.result.diagnostics()) {
                System.err.println("  Diagnostic: " + d);
            }
        }
        check(!found, context + ": unexpected " + code);
    }

    /** Asserts one exact diagnostic range (start and end line/column). */
    private static void checkExactRange(CheckerOutput out, String code,
            int startLine, int startColumn, int endLine, int endColumn,
            String context) {
        for (CompilerDiagnostic d : out.result.diagnostics()) {
            if (!d.code().equals(code) || d.range() == null) {
                continue;
            }
            check(d.range().startLine() == startLine
                    && d.range().startColumn() == startColumn
                    && d.range().endLine() == endLine
                    && d.range().endColumn() == endColumn,
                context + ": expected " + startLine + ":" + startColumn + "-"
                    + endLine + ":" + endColumn + ", got "
                    + d.range().startLine() + ":" + d.range().startColumn()
                    + "-" + d.range().endLine() + ":" + d.range().endColumn());
            return;
        }
        check(false, context + ": no " + code + " diagnostic with a range"
            + " was reported");
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

    /**
     * ISSUE-0717 review: the supported null narrowing is binding-based
     * only (docs/spec-v1.3.md §Nullable semantics — "No property-path
     * narrowing"), so a nullable member-path receiver stays rejected even
     * after a {@code path !== null} test, and an assignment to a narrowed
     * binding invalidates its refinement (the later dereference is
     * rejected).
     */
    static void testNullablePropertyPathAndAssignmentInvalidation() {
        System.out.println("-- Nullable property paths never narrow; assignment "
            + "invalidates the binding's refinement --");

        CheckerOutput path = checkProgram(
            "class Child { n: int = 0; }\n" +
            "class Parent { child: Child | null = null; }\n" +
            "export function f(p: Parent): int {\n" +
            "  if (p.child !== null) {\n" +
            "    return p.child.n;\n" +
            "  }\n" +
            "  return 0;\n" +
            "}"
        );
        assertError(path, "E3003",
            "a guarded nullable property path is not narrowed (no property-path "
                + "narrowing)");

        CheckerOutput assigned = checkProgram(
            "class C { n: int = 0; }\n" +
            "export function f(): int {\n" +
            "  let x: C | null = {};\n" +
            "  if (x !== null) {\n" +
            "    x = null;\n" +
            "    return x.n;\n" +
            "  }\n" +
            "  return 0;\n" +
            "}"
        );
        assertError(assigned, "E3003",
            "assigning null invalidates the binding's narrowing, so the "
                + "dereference is rejected");
    }

    /**
     * ISSUE-0717 review: only the declaration-owning scope clears its
     * pending let name (docs/spec-v1.3.md §Name resolution — Declaration order and §Scope),
     * so an inner block's same-name declaration never makes an enclosing
     * block's later declaration visible to an earlier reference.
     */
    static void testNestedDeclarationDoesNotClearEnclosingPending() {
        System.out.println("-- Nested same-name declaration keeps the enclosing "
            + "pending let invisible --");
        CheckerOutput out = checkProgram(
            "export function f(): null {\n" +
            "  {\n" +
            "    let x: int = 1;\n" +
            "  }\n" +
            "  let y: int = x;\n" +
            "  let x: int = 2;\n" +
            "  return null;\n" +
            "}"
        );
        assertError(out, "E2001",
            "the outer x is declared after the reference; the inner block's x "
                + "must not make it visible");
    }

    /**
     * ISSUE-0717 review: a shadowing declaration's initializer is checked
     * before the outer refinement is cleared, so it reads the enclosing
     * binding with its current narrowing (docs/spec-v1.3.md §Name resolution —
     * Declaration order and §Nullable semantics).
     */
    static void testShadowingInitializerReadsEnclosingNarrowing() {
        System.out.println("-- Shadowing initializer reads the enclosing "
            + "narrowed binding --");
        CheckerOutput out = checkProgram(
            "export function f(): null {\n" +
            "  let x: string | null = \"a\";\n" +
            "  if (x !== null) {\n" +
            "    let x: string = x;\n" +
            "  }\n" +
            "  return null;\n" +
            "}"
        );
        assertNoErrors(out, "the initializer reads the narrowed enclosing x");
    }

    /**
     * ISSUE-0717 review: an await inside a shadowing block invalidates the
     * enclosing async function's refinements together with the snapshot a
     * shadowing declaration removed (docs/spec-v1.3.md §Nullable
     * semantics): the block exit must not resurrect the invalidated
     * refinement. The no-await block exit still restores it, and an await
     * inside a nested function body stays scoped to that function.
     */
    static void testAwaitInsideShadowingBlockInvalidatesSnapshot() {
        System.out.println("-- an await inside a shadowing block does not leave the "
            + "invalidated refinement restorable --");
        CheckerOutput out = checkProgram(
            "async function tick(): null { return null; }\n" +
            "export async function run(): null {\n" +
            "  let x: string | null = \"ok\";\n" +
            "  if (x !== null) {\n" +
            "    {\n" +
            "      let x: int = 0;\n" +
            "      await tick();\n" +
            "    }\n" +
            "    let s: string = x;\n" +
            "  }\n" +
            "  return null;\n" +
            "}"
        );
        assertError(out, "E3001",
            "the await invalidates the shadowed refinement; the block exit "
                + "must not restore it");

        CheckerOutput restored = checkProgram("""
            async function positiveTick(): null { return null; }
            export function restoredBlock(): null {
              let x: string | null = "ok";
              if (x !== null) {
                { let x: int = 0; }
                let s: string = x;
              }
              return null;
            }
            export async function nestedAwait(): null {
              let x: string | null = "ok";
              if (x !== null) {
                {
                  let x: int = 0;
                  let f = async function(): null { await positiveTick(); return null; };
                }
                let s: string = x;
              }
              return null;
            }
            export function restoredLoop(): null {
              let x: string | null = "ok";
              if (x !== null) {
                {
                  let x: int = 0;
                  for (let n: int of [1]) { let z: int = n; }
                }
                let s: string = x;
              }
              return null;
            }
            export async function nestedLoopAwait(): null {
              let x: string | null = "ok";
              if (x !== null) {
                {
                  let x: int = 0;
                  for (let n: int of [1]) {
                    let f = async function(): null { await positiveTick(); return null; };
                  }
                }
                let s: string = x;
              }
              return null;
            }
            """);
        assertNoErrors(restored,
            "without an await the block exit restores the enclosing refinement");

        assertNoErrors(restored,
            "an await inside a nested function body is scoped to that function");

        CheckerOutput loopAwait = checkProgram(
            "async function tick(): null { return null; }\n" +
            "export async function run(): null {\n" +
            "  let x: string | null = \"ok\";\n" +
            "  if (x !== null) {\n" +
            "    {\n" +
            "      let x: int = 0;\n" +
            "      for (let n: int of [1]) { await tick(); }\n" +
            "    }\n" +
            "    let s: string = x;\n" +
            "  }\n" +
            "  return null;\n" +
            "}"
        );
        assertError(loopAwait, "E3001",
            "an await inside a for-of body invalidates the enclosing "
                + "refinements; the loop exit must not restore them");

        CheckerOutput whileAwait = checkProgram(
            "async function tick(): null { return null; }\n" +
            "export async function run(): null {\n" +
            "  let x: string | null = \"ok\";\n" +
            "  if (x !== null) {\n" +
            "    {\n" +
            "      let x: int = 0;\n" +
            "      while (false) { await tick(); }\n" +
            "    }\n" +
            "    let s: string = x;\n" +
            "  }\n" +
            "  return null;\n" +
            "}"
        );
        assertError(whileAwait, "E3001",
            "an await inside a while body invalidates the enclosing "
                + "refinements; the loop exit must not restore them");

        CheckerOutput forAwait = checkProgram(
            "async function tick(): null { return null; }\n" +
            "export async function run(): null {\n" +
            "  let x: string | null = \"ok\";\n" +
            "  if (x !== null) {\n" +
            "    {\n" +
            "      let x: int = 0;\n" +
            "      for (let n: int = 0; false; n = n + 1) { await tick(); }\n" +
            "    }\n" +
            "    let s: string = x;\n" +
            "  }\n" +
            "  return null;\n" +
            "}"
        );
        assertError(forAwait, "E3001",
            "an await inside a C-style for body invalidates the enclosing "
                + "refinements; the loop exit must not restore them");

        assertNoErrors(restored,
            "without an await the loop exit keeps the shadow snapshot "
                + "restorable");

        assertNoErrors(restored,
            "an await inside a nested function body stays scoped to that "
                + "function even when the body sits inside a loop");
    }

    /**
     * ISSUE-0717 review: an assignment inside a shadowing declaration's
     * initializer targets the enclosing binding (docs/spec-v1.3.md
     * §Name resolution — Declaration order), so its gate is the enclosing binding's
     * declared type — never the not-yet-visible inner declaration's type.
     */
    static void testShadowingInitializerAssignmentTargetsEnclosingBinding() {
        System.out.println("-- an assignment inside a shadowing initializer "
            + "targets the enclosing binding --");
        CheckerOutput valid = checkProgram(
            "export function f(): null {\n" +
            "  let x: int | null = 1;\n" +
            "  if (x !== null) {\n" +
            "    let x: number = number(x = 2);\n" +
            "  }\n" +
            "  return null;\n" +
            "}"
        );
        assertNoErrors(valid,
            "int is assignable to the enclosing int | null");

        CheckerOutput invalid = checkProgram(
            "export function f(): null {\n" +
            "  let x: int | null = 1;\n" +
            "  if (x !== null) {\n" +
            "    let x: number = (x = 2.5);\n" +
            "  }\n" +
            "  return null;\n" +
            "}"
        );
        assertError(invalid, "E3001",
            "number is not assignable to the enclosing int | null");
    }

    /**
     * ISSUE-0717 review: a loop-local declaration of the same name is a
     * distinct binding, so the loop back-edge invalidates only the
     * bindings actually assigned in the body (docs/spec-v1.3.md §Nullable
     * semantics): the enclosing refinement survives, while an assignment
     * to the enclosing binding still invalidates it.
     */
    static void testLoopLocalShadowDoesNotInvalidateEnclosingNarrowing() {
        System.out.println("-- a same-name loop-local declaration leaves the "
            + "enclosing narrowing intact --");
        CheckerOutput out = checkProgram(
            "export function f(): null {\n" +
            "  let x: string | null = \"ok\";\n" +
            "  if (x !== null) {\n" +
            "    while (false) { let x: int = 1; }\n" +
            "    let s: string = x;\n" +
            "  }\n" +
            "  return null;\n" +
            "}"
        );
        assertNoErrors(out,
            "the loop-local x is a different binding; the enclosing refinement "
                + "survives the loop");

        CheckerOutput assigned = checkProgram(
            "export function f(): null {\n" +
            "  let x: string | null = \"ok\";\n" +
            "  if (x !== null) {\n" +
            "    while (true) { x = null; break; }\n" +
            "    let s: string = x;\n" +
            "  }\n" +
            "  return null;\n" +
            "}"
        );
        assertError(assigned, "E3001",
            "an assignment to the enclosing binding inside the loop still "
                + "invalidates it");
    }

    /**
     * ISSUE-0717 review cycle 3: an if without else propagates the
     * invalidation an assignment or an await inside its then arm applied
     * (docs/spec-v1.3.md §Nullable semantics: an assignment invalidates
     * the refinement and an await invalidates every refinement of the
     * enclosing async function), while a harmless arm leaves the incoming
     * refinement intact. Both the with-else and the without-else joins
     * drop the invalidated entry.
     */
    static void testIfJoinPropagatesInvalidation() {
        System.out.println("-- the if join propagates assignment and await "
            + "invalidations --");
        CheckerOutput assigned = checkProgram(
            "export function main(): null {\n" +
            "  let x: string | null = \"a\";\n" +
            "  if (x !== null) {\n" +
            "    if (true) { x = null; }\n" +
            "    let y: string = x;\n" +
            "  }\n" +
            "  return null;\n" +
            "}"
        );
        assertError(assigned, "E3001",
            "an assignment inside an if without else invalidates the refinement "
                + "at the join");

        CheckerOutput awaited = checkProgram(
            "async function tick(): null { return null; }\n" +
            "export async function run(): null {\n" +
            "  let x: string | null = \"ok\";\n" +
            "  if (x !== null) {\n" +
            "    if (true) { await tick(); }\n" +
            "    let s: string = x;\n" +
            "  }\n" +
            "  return null;\n" +
            "}"
        );
        assertError(awaited, "E3001",
            "an await inside an if without else invalidates every refinement "
                + "at the join");

        CheckerOutput elseAssigned = checkProgram(
            "export function main(): null {\n" +
            "  let x: string | null = \"a\";\n" +
            "  if (x !== null) {\n" +
            "    if (false) { } else { x = null; }\n" +
            "    let y: string = x;\n" +
            "  }\n" +
            "  return null;\n" +
            "}"
        );
        assertError(elseAssigned, "E3001",
            "an assignment in the else arm invalidates the refinement at the "
                + "join");

        CheckerOutput elseAwaited = checkProgram(
            "async function tick(): null { return null; }\n" +
            "export async function run(): null {\n" +
            "  let x: string | null = \"ok\";\n" +
            "  if (x !== null) {\n" +
            "    if (false) { } else { await tick(); }\n" +
            "    let s: string = x;\n" +
            "  }\n" +
            "  return null;\n" +
            "}"
        );
        assertError(elseAwaited, "E3001",
            "an await in the else arm invalidates every refinement at the "
                + "join");

        CheckerOutput harmless = checkProgram(
            "export function main(): null {\n" +
            "  let x: string | null = \"a\";\n" +
            "  if (x !== null) {\n" +
            "    if (true) { let z: int = 1; }\n" +
            "    let y: string = x;\n" +
            "  }\n" +
            "  return null;\n" +
            "}"
        );
        assertNoErrors(harmless,
            "a harmless if without else keeps the incoming refinement");
    }

    /**
     * ISSUE-0717 review cycle 3: a shadowing declaration's saved
     * refinement survives an if/else join inside the shadowing block but
     * is never restored when a path invalidated it with an await
     * (docs/spec-v1.3.md §Nullable semantics).
     */
    static void testShadowStateSurvivesBranch() {
        System.out.println("-- the shadow snapshot survives a branch join "
            + "unless a path invalidated it --");
        CheckerOutput ifElse = checkProgram(
            "export function main(): null {\n" +
            "  let x: string | null = \"a\";\n" +
            "  if (x !== null) {\n" +
            "    { let x: int = 0; if (true) {} else {} }\n" +
            "    let y: string = x;\n" +
            "  }\n" +
            "  return null;\n" +
            "}"
        );
        assertNoErrors(ifElse,
            "the shadow snapshot survives the harmless if/else");

        CheckerOutput awaitThen = checkProgram(
            "async function tick(): null { return null; }\n" +
            "export async function run(): null {\n" +
            "  let x: string | null = \"ok\";\n" +
            "  if (x !== null) {\n" +
            "    { let x: int = 0; if (true) { await tick(); } else {} }\n" +
            "    let s: string = x;\n" +
            "  }\n" +
            "  return null;\n" +
            "}"
        );
        assertError(awaitThen, "E3001",
            "an await on one branch prevents the snapshot's restoration");

        CheckerOutput awaitElse = checkProgram(
            "async function tick(): null { return null; }\n" +
            "export async function run(): null {\n" +
            "  let x: string | null = \"ok\";\n" +
            "  if (x !== null) {\n" +
            "    { let x: int = 0; if (false) {} else { await tick(); } }\n" +
            "    let s: string = x;\n" +
            "  }\n" +
            "  return null;\n" +
            "}"
        );
        assertError(awaitElse, "E3001",
            "an await on either branch prevents the snapshot's restoration");
    }

    /**
     * ISSUE-0717 review cycle 3: the for-of back-edge invalidates every
     * binding assigned in the body exactly like while and C-style for
     * (docs/spec-v1.3.md §Nullable semantics), and a same-named
     * iteration binding is a distinct binding that leaves the enclosing
     * refinement intact.
     */
    static void testForOfAssignmentInvalidatesEnclosingNarrowing() {
        System.out.println("-- the for-of back-edge invalidates the bindings "
            + "assigned in the body --");
        CheckerOutput assigned = checkProgram(
            "export function main(): null {\n" +
            "  let x: string | null = \"a\";\n" +
            "  if (x !== null) {\n" +
            "    for (let n: int of [1]) { x = null; }\n" +
            "    let y: string = x;\n" +
            "  }\n" +
            "  return null;\n" +
            "}"
        );
        assertError(assigned, "E3001",
            "an assignment to the enclosing binding inside a for-of body "
                + "invalidates it after the loop");

        CheckerOutput control = checkProgram(
            "export function main(): null {\n" +
            "  let x: string | null = \"a\";\n" +
            "  if (x !== null) {\n" +
            "    for (let x: string of [\"z\"]) { x = \"b\"; }\n" +
            "    let y: string = x;\n" +
            "  }\n" +
            "  return null;\n" +
            "}"
        );
        assertNoErrors(control,
            "a same-named iteration binding is distinct; the enclosing "
                + "refinement survives the for-of");
    }

    /**
     * ISSUE-0717 review cycle 3: a C-style for's loop-head let is visible
     * only after its declaration (docs/spec-v1.3.md §Name resolution — Declaration order;
     * the loop desugars to block-local scope plus a while loop): its
     * initializer resolves to an enclosing binding, a self reference is
     * E2001, and the loop-head shadow ends with the loop.
     */
    static void testForHeadDeclarationOrder() {
        System.out.println("-- the C-style for loop-head let is not visible "
            + "to its own initializer --");
        CheckerOutput self = checkProgram(
            "export function main(): null {\n" +
            "  for (let x: int = x; false;) {}\n" +
            "  return null;\n" +
            "}"
        );
        assertError(self, "E2001",
            "a loop-head self reference has no enclosing declaration");

        CheckerOutput shadow = checkProgram(
            "export function main(): null {\n" +
            "  let x: int = 1;\n" +
            "  for (let x: number = number(x); false;) {}\n" +
            "  return null;\n" +
            "}"
        );
        assertNoErrors(shadow,
            "the initializer reads the enclosing int binding");

        CheckerOutput restored = checkProgram(
            "export function main(): null {\n" +
            "  let x: string | null = \"a\";\n" +
            "  if (x !== null) {\n" +
            "    for (let x: int = 0; false;) {}\n" +
            "    let y: string = x;\n" +
            "  }\n" +
            "  return null;\n" +
            "}"
        );
        assertNoErrors(restored,
            "the loop-head shadow ends with the loop and restores the "
                + "enclosing refinement");
    }

    /**
     * ISSUE-0847: two same-directory sibling files declaring the same
     * class name are two distinct nominal declarations, because the
     * canonical class identity names the defining file. A direct
     * assignment across them is the cross-declaration rejection (E3019)
     * on every backend, and the declaring declaration's own dynamic
     * crossing compiles and executes (the correct-declaration control),
     * including an equivalent import spelling of the same source file.
     */
    static void testSameNameSiblingDeclarationsPublicCompile() {
        System.out.println("-- public deal compile: same-directory sibling "
            + "declarations are distinct nominal types on js/jvm/luajit --");
        Path root;
        try {
            root = Files.createTempDirectory("deal_sibling_identity_");
        } catch (IOException e) {
            fail("same-name sibling identity: cannot create temp directory: "
                + e.getMessage());
            return;
        }
        try {
            Path crossing = root.resolve("crossing");
            Files.createDirectories(crossing.resolve("src"));
            Files.writeString(crossing.resolve("deal.json"),
                "{\"languageVersion\":\"1.3\",\"backend\":\"js\","
                    + "\"moduleRoots\":[\"src\"]}\n");
            Files.writeString(crossing.resolve("src/a.deal"),
                "export class Item { value: int = 1; }\n"
                    + "export function make(): Item { return {}; }\n");
            Files.writeString(crossing.resolve("src/b.deal"),
                "export class Item { value: int = 2; }\n"
                    + "export function make(): Item { return {}; }\n");
            Path crossingEntry = crossing.resolve("src/main.deal");
            Files.writeString(crossingEntry,
                "import * as a from \"./a\";\n"
                    + "import * as b from \"./b\";\n"
                    + "export function main(): null {\n"
                    + "  let wrong: b.Item = a.make();\n"
                    + "  return null;\n"
                    + "}\n");
            for (String backend : List.of("js", "jvm", "luajit")) {
                Path out = root.resolve("crossing_out_" + backend);
                CliResult result = cliCompile(crossingEntry, backend, out);
                check(result.exitCode() == 1
                        && result.output().contains("E3019"),
                    "the direct same-name sibling assignment is the"
                        + " cross-declaration rejection on " + backend + ": "
                        + result.output());
                check(!Files.exists(out),
                    "the rejected sibling assignment publishes no artifacts"
                        + " on " + backend);
            }

            Path control = root.resolve("control");
            Files.createDirectories(control.resolve("src"));
            Files.writeString(control.resolve("deal.json"),
                "{\"languageVersion\":\"1.3\",\"backend\":\"js\","
                    + "\"moduleRoots\":[\"src\"]}\n");
            Files.writeString(control.resolve("src/a.deal"),
                "export class Item { value: int = 1; }\n"
                    + "export function make(): Item { return {}; }\n"
                    + "export function read(item: Item): int {\n"
                    + "  return item.value;\n"
                    + "}\n");
            Files.writeString(control.resolve("src/b.deal"),
                "export class Item { value: int = 2; }\n"
                    + "export function make(): Item { return {}; }\n"
                    + "export function read(item: Item): int {\n"
                    + "  return item.value;\n"
                    + "}\n");
            Path controlEntry = control.resolve("src/main.deal");
            Files.writeString(controlEntry,
                "import * as console from \"std/console\";\n"
                    + "import * as a from \"./a\";\n"
                    + "import * as aAgain from \"././a\";\n"
                    + "import * as b from \"./b\";\n"
                    + "export function main(): null {\n"
                    + "  let t: table = { item: a.make() };\n"
                    + "  let own: a.Item = t.item;\n"
                    + "  let again: aAgain.Item = own;\n"
                    + "  let tb: table = { item: b.make() };\n"
                    + "  let other: b.Item = tb.item;\n"
                    + "  if (a.read(own) !== 1 || a.read(again) !== 1) {\n"
                    + "    throw { code: \"WRONG_A\", message: \"a\" };\n"
                    + "  }\n"
                    + "  if (b.read(other) !== 2) {\n"
                    + "    throw { code: \"WRONG_B\", message: \"b\" };\n"
                    + "  }\n"
                    + "  console.log(\"SIBLING_IDENTITY_OK\");\n"
                    + "  return null;\n"
                    + "}\n");
            Path controlOutJs = root.resolve("control_out_js");
            for (String backend : List.of("js", "jvm", "luajit")) {
                Path out = backend.equals("js")
                    ? controlOutJs : root.resolve("control_out_" + backend);
                cliAccept(controlEntry, out, backend,
                    "the correct-declaration control");
            }
            cliNode(controlOutJs, "SIBLING_IDENTITY_OK");
        } catch (IOException | InterruptedException e) {
            fail("same-name sibling identity: " + e.getMessage());
        } finally {
            deleteRecursively(root);
        }
    }

    /**
     * ISSUE-0717 review cycle 3: the E3019 cross-declaration gate covers
     * declaration initialization and nullable class targets as well as
     * plain assignment expressions (the two declarations share the
     * canonical identity but own distinct runtime layouts).
     */
    static void testClassProvenanceAtInitializationAndNullableTarget() {
        System.out.println("-- class provenance is checked at initialization "
            + "and nullable targets --");
        CheckerOutput init = checkProgram(
            "class A { n: int = 0; }\n" +
            "export function f(): null {\n" +
            "  let a: A = {};\n" +
            "  {\n" +
            "    class A { n: string = \"ok\"; }\n" +
            "    let b: A = a;\n" +
            "  }\n" +
            "  return null;\n" +
            "}"
        );
        assertError(init, "E3019",
            "a declaration initializer that crosses the two declarations "
                + "is E3019");

        CheckerOutput nullable = checkProgram(
            "class A { n: int = 0; }\n" +
            "export function f(): null {\n" +
            "  let a: A | null = {};\n" +
            "  {\n" +
            "    class A { n: string = \"ok\"; }\n" +
            "    let b: A = {};\n" +
            "    a = b;\n" +
            "  }\n" +
            "  return null;\n" +
            "}"
        );
        assertError(nullable, "E3019",
            "an assignment to a nullable class target that crosses the two "
                + "declarations is E3019");

        CheckerOutput control = checkProgram(
            "class A { n: int = 0; }\n" +
            "export function f(): null {\n" +
            "  let a: A = {};\n" +
            "  let b: A = a;\n" +
            "  let c: A | null = a;\n" +
            "  c = b;\n" +
            "  return null;\n" +
            "}"
        );
        assertNoErrors(control,
            "initialization and nullable assignment within one declaration "
                + "stay legal");

        CheckerOutput inferredAlias = checkProgram(
            "class A { n: int = 7; }\n" +
            "export function f(): null {\n" +
            "  let a: A = {};\n" +
            "  let alias = a;\n" +
            "  {\n" +
            "    class A { n: string = \"ok\"; }\n" +
            "    let b: A = alias;\n" +
            "  }\n" +
            "  return null;\n" +
            "}"
        );
        assertError(inferredAlias, "E3019",
            "an inferred alias carries the initializer's declaration "
                + "provenance across the initialization boundary");

        CheckerOutput inferredNullable = checkProgram(
            "class A { n: int = 7; }\n" +
            "export function f(): null {\n" +
            "  let a: A | null = {};\n" +
            "  let alias = a;\n" +
            "  {\n" +
            "    class A { n: string = \"ok\"; }\n" +
            "    let b: A | null = alias;\n" +
            "  }\n" +
            "  return null;\n" +
            "}"
        );
        assertError(inferredNullable, "E3019",
            "an inferred nullable-class alias keeps its provenance at a "
                + "nullable initialization boundary");

        CheckerOutput inferredAssignment = checkProgram(
            "class A { n: int = 7; }\n" +
            "export function f(): null {\n" +
            "  let a: A = {};\n" +
            "  let alias = a;\n" +
            "  {\n" +
            "    class A { n: string = \"ok\"; }\n" +
            "    let b: A = {};\n" +
            "    b = alias;\n" +
            "  }\n" +
            "  return null;\n" +
            "}"
        );
        assertError(inferredAssignment, "E3019",
            "an inferred alias keeps its provenance at an assignment "
                + "expression boundary");

        CheckerOutput aliasControl = checkProgram(
            "class A { n: int = 0; }\n" +
            "export function f(): null {\n" +
            "  let a: A = {};\n" +
            "  let alias = a;\n" +
            "  let b: A = alias;\n" +
            "  let c: A | null = alias;\n" +
            "  return null;\n" +
            "}"
        );
        assertNoErrors(aliasControl,
            "an inferred alias of one declaration stays legal at its own "
                + "initialization and nullable assignment boundaries");
    }

    /**
     * ISSUE-0717 review: an assignment expression evaluates to the assigned
     * value, so the E3019 cross-declaration gate must consume the value's
     * exact declaration provenance through assignment expressions — the
     * initialization, nullable-initialization, assignment-statement, and
     * nested-assignment spellings. Dropping that provenance published a JS
     * artifact whose statically string-typed field held an int, and reached
     * E6005 on LuaJIT/JVM instead of the frontend rejection.
     */
    static void testClassProvenanceThroughAssignmentExpressions() {
        System.out.println("-- class provenance is preserved through "
            + "assignment expressions --");
        CheckerOutput init = checkProgram(
            "class A { n: int = 7; }\n" +
            "export function f(): null {\n" +
            "  let a: A = {};\n" +
            "  {\n" +
            "    class A { n: string = \"ok\"; }\n" +
            "    let b: A = (a = a);\n" +
            "  }\n" +
            "  return null;\n" +
            "}"
        );
        assertError(init, "E3019",
            "an assignment-expression value at an initialization boundary "
                + "keeps its declaration provenance -> E3019");

        CheckerOutput nullable = checkProgram(
            "class A { n: int = 7; }\n" +
            "export function f(): null {\n" +
            "  let a: A = {};\n" +
            "  {\n" +
            "    class A { n: string = \"ok\"; }\n" +
            "    let b: A | null = (a = a);\n" +
            "  }\n" +
            "  return null;\n" +
            "}"
        );
        assertError(nullable, "E3019",
            "an assignment-expression value at a nullable initialization "
                + "boundary keeps its declaration provenance -> E3019");

        CheckerOutput assignment = checkProgram(
            "class A { n: int = 7; }\n" +
            "export function f(): null {\n" +
            "  let a: A = {};\n" +
            "  {\n" +
            "    class A { n: string = \"ok\"; }\n" +
            "    let b: A = {};\n" +
            "    b = (a = a);\n" +
            "  }\n" +
            "  return null;\n" +
            "}"
        );
        assertError(assignment, "E3019",
            "an assignment-expression value at an assignment boundary keeps "
                + "its declaration provenance -> E3019");

        CheckerOutput nested = checkProgram(
            "class A { n: int = 7; }\n" +
            "export function f(): null {\n" +
            "  let a: A = {};\n" +
            "  let c: A = {};\n" +
            "  {\n" +
            "    class A { n: string = \"ok\"; }\n" +
            "    let b: A = (c = (a = a));\n" +
            "  }\n" +
            "  return null;\n" +
            "}"
        );
        assertError(nested, "E3019",
            "nested assignment expressions keep their value provenance "
                + "-> E3019");

        CheckerOutput control = checkProgram(
            "class A { n: int = 7; }\n" +
            "export function f(): null {\n" +
            "  let a: A = {};\n" +
            "  let b: A = (a = a);\n" +
            "  let c: A | null = (a = a);\n" +
            "  c = (b = b);\n" +
            "  return null;\n" +
            "}"
        );
        assertNoErrors(control,
            "assignment-expression values within one declaration stay "
                + "legal (class shadowing is not rejected globally)");
    }

    /**
     * ISSUE-0717 review: a contextually constructed class literal carries
     * the exact declaration that laid it out — the contextual declaration
     * (the assignment target's or the annotated declaration's) when it
     * names the same canonical class, otherwise the declaration
     * construction resolves. The E3019 gate consumes that checked fact at
     * the initialization and assignment boundaries, so a literal crossing
     * two distinct same-named declarations is rejected before any backend
     * lowers the duplicate layout. The reviewer reproduction
     * (`let b: A = (a = {});` under a shadowing class A) previously
     * compiled on JS and failed at run time with E8001, and reached E6005
     * on LuaJIT/JVM.
     */
    static void testClassProvenanceThroughConstructedLiterals() {
        System.out.println("-- class provenance is preserved through "
            + "contextually constructed literals --");
        CheckerOutput init = checkProgram(
            "class A { n: int = 7; }\n" +
            "export function f(): null {\n" +
            "  let a: A = {};\n" +
            "  {\n" +
            "    class A { n: string = \"ok\"; }\n" +
            "    let b: A = (a = {});\n" +
            "  }\n" +
            "  return null;\n" +
            "}"
        );
        assertError(init, "E3019",
            "a constructed-literal assignment-expression value at an "
                + "initialization boundary keeps its declaration "
                + "provenance -> E3019");

        CheckerOutput nullable = checkProgram(
            "class A { n: int = 7; }\n" +
            "export function f(): null {\n" +
            "  let a: A = {};\n" +
            "  {\n" +
            "    class A { n: string = \"ok\"; }\n" +
            "    let b: A | null = (a = {});\n" +
            "  }\n" +
            "  return null;\n" +
            "}"
        );
        assertError(nullable, "E3019",
            "a constructed-literal value at a nullable initialization "
                + "boundary keeps its declaration provenance -> E3019");

        CheckerOutput assignment = checkProgram(
            "class A { n: int = 7; }\n" +
            "export function f(): null {\n" +
            "  let a: A = {};\n" +
            "  {\n" +
            "    class A { n: string = \"ok\"; }\n" +
            "    let b: A = {};\n" +
            "    a = (b = {});\n" +
            "  }\n" +
            "  return null;\n" +
            "}"
        );
        assertError(assignment, "E3019",
            "a constructed-literal assignment-expression value at an "
                + "assignment boundary keeps its declaration provenance "
                + "-> E3019");

        CheckerOutput control = checkProgram(
            "class A { n: int = 7; }\n" +
            "export function f(): null {\n" +
            "  let a: A = {};\n" +
            "  let b: A = (a = {});\n" +
            "  let c: A | null = (a = {});\n" +
            "  c = (b = {});\n" +
            "  return null;\n" +
            "}"
        );
        assertNoErrors(control,
            "constructed-literal assignment-expression values within one "
                + "declaration stay legal");

        CheckerOutput contextual = checkProgram(
            "class A { n: int = 7; }\n" +
            "export function f(): null {\n" +
            "  let a: A = {};\n" +
            "  {\n" +
            "    class A { n: string = \"ok\"; }\n" +
            "    a = {};\n" +
            "  }\n" +
            "  return null;\n" +
            "}"
        );
        assertNoErrors(contextual,
            "a literal assigned to a binding of the enclosing declaration "
                + "is constructed by that declaration (the target's exact "
                + "declaration), never rejected as a spurious crossing");
    }

    /**
     * ISSUE-0717 review: the constructed-literal probes through the
     * release-owned public CLI. The review reproduction and its nullable
     * and assignment-statement spellings are rejected with E3019 on all
     * three backends before any artifact is published, and the
     * same-declaration literal control compiles on all three and its JS
     * artifact executes (node) with the expected field value.
     */
    static void testClassProvenanceConstructedLiteralPublicCompile() {
        System.out.println("-- public deal compile: the constructed-literal "
            + "provenance crossing on js/jvm/luajit --");
        Path root;
        try {
            root = Files.createTempDirectory("deal_provenance_literal_");
        } catch (IOException e) {
            fail("public constructed-literal provenance: cannot create temp "
                + "directory: " + e.getMessage());
            return;
        }
        try {
            String[] negatives = {
                "{\n"
                    + "    class A { n: string = \"ok\"; }\n"
                    + "    let b: A = (a = {});\n"
                    + "  }",
                "{\n"
                    + "    class A { n: string = \"ok\"; }\n"
                    + "    let b: A | null = (a = {});\n"
                    + "  }",
                "{\n"
                    + "    class A { n: string = \"ok\"; }\n"
                    + "    let b: A = {};\n"
                    + "    a = (b = {});\n"
                    + "  }"
            };
            String[] importedCrossings = {
                "let b: foreign.A = (a = {});",
                "let b: foreign.A | null = (a = {});",
                "let b: foreign.A = {}; a = (b = {});"
            };
            StringBuilder crossingSource = new StringBuilder(
                "import * as foreign from \"./foreign/types\";\nclass A { n: int = 7; }\n");
            for (int i = 0; i < importedCrossings.length; i++) {
                crossingSource.append("function crossing").append(i)
                    .append("(): null { let a: A = {}; ").append(importedCrossings[i])
                    .append(" return null; }\n");
            }
            crossingSource.append("export function main(): null { return null; }");
            Path crossingEntry = cliProject(root, "module_crossings", crossingSource.toString());
            Files.createDirectories(crossingEntry.resolveSibling("foreign"));
            Files.writeString(crossingEntry.resolveSibling("foreign").resolve("types.deal"),
                "export class A { n: int = 7; }");
            for (String backend : List.of("js", "jvm", "luajit")) {
                Path out = root.resolve("module_crossings_out_" + backend);
                CliResult result = cliCompile(crossingEntry, backend, out);
                check(result.exitCode() == 1 && result.output().split("E3019", -1).length - 1 == 3,
                    "all three same-name module assignment forms reject as"
                        + " cross-declaration crossings on " + backend + ": "
                        + result.output());
                check(!Files.exists(out), "cross-module rejection publishes no artifacts");
            }
            String[] names = {"init", "nullable", "assignment"};
            for (int p = 0; p < negatives.length; p++) {
                Path probeEntry = cliProject(root, "probe_" + names[p],
                    "class A { n: int = 7; }\n"
                        + "export function main(): null {\n"
                        + "  let a: A = {};\n"
                        + "  " + negatives[p] + "\n"
                        + "  return null;\n"
                        + "}\n");
                for (String backend : List.of("js", "jvm", "luajit")) {
                    Path out = root.resolve("out_" + names[p] + "_" + backend);
                    cliReject(probeEntry, out, backend, "constructed-literal " + names[p]);
                }
            }

            Path controlEntry = cliProject(root, "control",
                "import * as console from \"std/console\";\n"
                    + "class A { n: int = 7; }\n"
                    + "export function main(): null {\n"
                    + "  let a: A = {};\n"
                    + "  let b: A = (a = {});\n"
                    + "  let c: A | null = (a = {});\n"
                    + "  c = (b = {});\n"
                    + "  if (c !== null) {\n"
                    + "    if (c.n !== 7) {\n"
                    + "      throw { code: \"PROVENANCE_FAIL\", message: "
                    + "\"same-declaration value\" };\n"
                    + "    }\n"
                    + "  }\n"
                    + "  console.log(\"CONSTRUCTED_OK\");\n"
                    + "  return null;\n"
                    + "}\n");
            Path controlOutJs = root.resolve("control_out_js");
            for (String backend : List.of("js", "jvm", "luajit")) {
                Path out = backend.equals("js")
                    ? controlOutJs : root.resolve("control_out_" + backend);
                cliAccept(controlEntry, out, backend, "same-declaration constructed-literal control");
            }

            Path artifact = controlOutJs.resolve("main.js");
            check(Files.isRegularFile(artifact),
                "the constructed-literal control's JS artifact exists: "
                    + artifact);
            if (Files.isRegularFile(artifact)) {
                cliNode(controlOutJs, "CONSTRUCTED_OK");
            }
        } catch (IOException e) {
            fail("public constructed-literal provenance: I/O failure: "
                + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("public constructed-literal provenance: interrupted: "
                + e.getMessage());
        } finally {
            deleteRecursively(root);
        }
    }

    /** Number of non-overlapping occurrences of {@code needle}. */
    private static int countOf(String text, String needle) {
        int count = 0;
        int index = text.indexOf(needle);
        while (index >= 0) {
            count++;
            index = text.indexOf(needle, index + needle.length());
        }
        return count;
    }

    /**
     * ISSUE-0717 review 0007: a class-typed object literal under a
     * same-named shadowing declaration is laid out by the declaration the
     * checker selected (the target/context declaration), so the emitted
     * JS must call that declaration's own factory — never the shadowing
     * block's lexical binding. The reviewer reproduction (explicit
     * provided field) and its nullable-target spelling compile through
     * the public CLI on JS, publish an artifact whose construction calls
     * the outer closure while the shadowing declaration's artifacts are
     * renamed, and execute under node with the outer layout's value
     * assertions. The nearest legal controls (a same-declaration
     * construction of the shadowing declaration and a module-level
     * construction after the shadowing block) execute with their own
     * layouts. LuaJIT/JVM stay outside this correction (the duplicate
     * layout still fails closed before emission).
     */
    static void testClassProvenanceContextualConstructionUnderShadowing() {
        System.out.println("-- public deal compile: target-context construction "
            + "under a same-named shadowing declaration --");
        Path root;
        try {
            root = Files.createTempDirectory("deal_shadow_construction_");
        } catch (IOException e) {
            fail("shadow construction: cannot create temp directory: "
                + e.getMessage());
            return;
        }
        try {
            // The reviewer reproduction: the assignment target's exact
            // declaration is the module-level A, and the literal must be
            // constructed by it although the block declares a same-named
            // A whose factory would be the lexical binding.
            String[] probes = {
                // explicit provided field, plain class target
                "import * as console from \"std/console\";\n"
                    + "class A { n: int = 7; }\n"
                    + "export function main(): null {\n"
                    + "  let a: A = {};\n"
                    + "  {\n"
                    + "    class A { n: string = \"ok\"; }\n"
                    + "    a = { n: 8 };\n"
                    + "  }\n"
                    + "  if (a.n !== 8) { throw { code: \"OUTER_LAYOUT\", "
                    + "message: \"outer\" }; }\n"
                    + "  console.log(\"SHADOW_CONSTRUCTION_OK\");\n"
                    + "  return null;\n"
                    + "}\n",
                // nullable class target
                "import * as console from \"std/console\";\n"
                    + "class A { n: int = 7; }\n"
                    + "export function main(): null {\n"
                    + "  let a: A | null = {};\n"
                    + "  {\n"
                    + "    class A { n: string = \"ok\"; }\n"
                    + "    a = { n: 8 };\n"
                    + "  }\n"
                    + "  if (a !== null) {\n"
                    + "    if (a.n !== 8) { throw { code: \"NULLABLE_LAYOUT\", "
                    + "message: \"nullable\" }; }\n"
                    + "  }\n"
                    + "  console.log(\"SHADOW_NULLABLE_OK\");\n"
                    + "  return null;\n"
                    + "}\n"
            };
            String[] names = {"explicit", "nullable"};
            String[] tokens = {"SHADOW_CONSTRUCTION_OK", "SHADOW_NULLABLE_OK"};
            for (int p = 0; p < probes.length; p++) {
                Path entry = cliProject(root, "probe_" + names[p], probes[p]);
                Path out = root.resolve("out_" + names[p]);
                cliReject(entry, out, "js", "original local-class construction " + names[p]);
                String legal = probes[p].replace("class A { n: int = 7; }",
                    "class A { n: int = 7; }\nclass B { n: string = \"ok\"; }")
                    .replace("    class A { n: string = \"ok\"; }", "    let other: B = {};");
                Path legalEntry = cliProject(root, "legal_" + names[p], legal);
                Path legalOut = root.resolve("legal_out_" + names[p]);
                cliAccept(legalEntry, legalOut, "js", "module-class target-context construction " + names[p]);
                check(Files.isRegularFile(legalOut.resolve("main.js")), "legal target-context artifact exists");
                if (Files.isRegularFile(legalOut.resolve("main.js"))) {
                    String js = Files.readString(legalOut.resolve("main.js"), StandardCharsets.UTF_8);
                    check(js.contains("\"$classname\", \"@src/main.deal/A\"")
                            && js.contains("\"$classname\", \"@src/main.deal/B\""),
                        "the distinct module class retains independent factory artifacts");
                    check(countOf(js, "\"$classname\", \"@src/main.deal/A\"") == 2
                            && countOf(js, "\"$classname\", \"@src/main.deal/B\"") == 1,
                        "target-context construction selects the annotated module "
                            + "class factory and never the other class factory");
                    cliNode(legalOut, tokens[p]);
                }

            }

            // Nearest legal controls: the shadowing declaration's own
            // construction (its field stays string) and a module-level
            // construction after the shadowing block (the declared
            // default stays int).
            Path controlEntry = cliProject(root, "control",
                "import * as console from \"std/console\";\n"
                    + "class A { n: int = 7; }\nclass B { n: string = \"ok\"; }\n"
                    + "export function main(): null {\n"
                    + "  let a: A = {};\n"
                    + "  {\n"
                    + "    let b: B = {};\n"
                    + "    if (b.n !== \"ok\") { throw { code: \"INNER_LAYOUT\", "
                    + "message: \"inner\" }; }\n"
                    + "  }\n"
                    + "  let c: A = {};\n"
                    + "  if (c.n !== 7) { throw { code: \"DEFAULT_LAYOUT\", "
                    + "message: \"default\" }; }\n"
                    + "  console.log(\"SHADOW_CONTROL_OK\");\n"
                    + "  return null;\n"
                    + "}\n");
            Path controlOut = root.resolve("control_out");
            cliAccept(controlEntry, controlOut, "js", "legal shadowing control");
            Path controlArtifact = controlOut.resolve("main.js");
            check(Files.isRegularFile(controlArtifact),
                "the legal shadowing control publishes its JS artifact: "
                    + controlArtifact);
            if (Files.isRegularFile(controlArtifact)) {
                cliNode(controlOut, "SHADOW_CONTROL_OK");
            }
        } catch (IOException e) {
            fail("shadow construction: I/O failure: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("shadow construction: interrupted: " + e.getMessage());
        } finally {
            deleteRecursively(root);
        }
    }

    /**
     * ISSUE-0717 review: the same probes through the release-owned public
     * CLI. The cross-declaration assignment-expression forms are rejected
     * with E3019 on all three backends before any artifact is published,
     * and the same-declaration control compiles on all three and its JS
     * artifact executes (node) with the expected field value.
     */
    static void testClassProvenanceAssignmentExprPublicCompile() {
        System.out.println("-- public deal compile: the assignment-expression "
            + "provenance crossing on js/jvm/luajit --");
        Path root;
        try {
            root = Files.createTempDirectory("deal_provenance_assign_");
        } catch (IOException e) {
            fail("public provenance: cannot create temp directory: "
                + e.getMessage());
            return;
        }
        try {
            Path probeEntry = cliProject(root, "probe",
                "class A { n: int = 7; }\n"
                    + "export function main(): null {\n"
                    + "  let a: A = {};\n"
                    + "  {\n"
                    + "    class A { n: string = \"ok\"; }\n"
                    + "    let b: A = (a = a);\n"
                    + "    let s: string = b.n;\n"
                    + "  }\n"
                    + "  return null;\n"
                    + "}\n");
            for (String backend : List.of("js", "jvm", "luajit")) {
                Path out = root.resolve("probe_out_" + backend);
                cliReject(probeEntry, out, backend, "assignment-expression provenance crossing");
            }

            Path controlEntry = cliProject(root, "control",
                "import * as console from \"std/console\";\n"
                    + "class A { n: int = 7; }\n"
                    + "export function main(): null {\n"
                    + "  let a: A = {};\n"
                    + "  let b: A = (a = a);\n"
                    + "  let c: A | null = (a = a);\n"
                    + "  c = (b = b);\n"
                    + "  if (c !== null) {\n"
                    + "    if (c.n !== 7) {\n"
                    + "      throw { code: \"PROVENANCE_FAIL\", message: "
                    + "\"same-declaration value\" };\n"
                    + "    }\n"
                    + "  }\n"
                    + "  console.log(\"PROVENANCE_OK\");\n"
                    + "  return null;\n"
                    + "}\n");
            Path controlOutJs = root.resolve("control_out_js");
            for (String backend : List.of("js", "jvm", "luajit")) {
                Path out = backend.equals("js")
                    ? controlOutJs : root.resolve("control_out_" + backend);
                cliAccept(controlEntry, out, backend, "same-declaration assignment-expression control");
            }

            Path artifact = controlOutJs.resolve("main.js");
            check(Files.isRegularFile(artifact),
                "the control's JS artifact exists: " + artifact);
            if (Files.isRegularFile(artifact)) {
                cliNode(controlOutJs, "PROVENANCE_OK");
            }
        } catch (IOException e) {
            fail("public provenance: I/O failure: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("public provenance: interrupted: " + e.getMessage());
        } finally {
            deleteRecursively(root);
        }
    }

    /**
     * ISSUE-0717 (defect snippet 47): a nested class may shadow an
     * enclosing class of the same name (the JS scope-local shape and the
     * declaration-keyed plans rely on it), but assigning a value of the
     * nested declaration to a binding of the enclosing declaration is
     * rejected (E3019) — the two declarations share the canonical
     * identity (module + name) yet own distinct runtime layouts.
     */
    static void testNestedClassShadowingRejected() {
        System.out.println("-- Nested class shadowing: declaration legal, "
            + "cross-declaration assignment -> E3019 --");
        CheckerOutput shadow = checkProgram(
            "class A { n: int = 0; }\n" +
            "export function f(): null {\n" +
            "  {\n" +
            "    class A { n: int = 0; }\n" +
            "    let b: A = {};\n" +
            "  }\n" +
            "  return null;\n" +
            "}"
        );
        assertNoErrors(shadow,
            "a nested class shadowing an enclosing class declares cleanly");
        CheckerOutput out = checkProgram(
            "class A { n: int = 0; }\n" +
            "export function f(): null {\n" +
            "  let a: A = {};\n" +
            "  {\n" +
            "    class A { n: int = 0; }\n" +
            "    let b: A = {};\n" +
            "    a = b;\n" +
            "  }\n" +
            "  return null;\n" +
            "}"
        );
        assertError(out, "E3019",
            "assigning across two declarations of a same-named class "
                + "must be E3019");
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

        // ISSUE-0879: the null array element type takes the same single
        // '| null' wrapper. A repeated wrapper is still the duplicated
        // nullable the spec forbids, never an admitted null array spelling.
        CheckerOutput repeated = checkProgram("let x: null[] | null | null = null;");
        assertError(repeated, "E3005", "doubly nullable null array");

        // null is not itself nullable, with or without array suffixes.
        CheckerOutput nullNull = checkProgram("let x: null | null = null;");
        assertError(nullNull, "E3005", "null union null");

        // The admitted spellings resolve cleanly instead.
        for (String source : List.of("let x: null[] = [null];",
                "let x: null[][] = [[null]];",
                "let x: null[] | null = null;")) {
            CheckerOutput admitted = checkProgram(source);
            check(admitted.result.diagnostics().isEmpty(), source
                + ": the null array annotation resolves: "
                + admitted.result.diagnostics());
        }
    }

    // F10: Reverse arity E5004
    static void testReverseArityE5004() {
        System.out.println("-- Reverse Arity E5004 --");
        CheckerOutput out = checkProgram(
            "function twoArgs(a: int, b: int): int { return a + b; }\n" +
            "let f: (x: int) => int = twoArgs;"
        );
        assertError(out, "E5004", "reverse arity E5004");
        assertError(out, "E5004", "reverse arity error");
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
    // F2: E2005 — circular import
    // =========================================================================

    static void testE2005_circularImport() {
        System.out.println("-- F2: E2005 circular import (recursion guard) --");
        // The NameResolver adds the imported module path to
        // modulesInProgress before resolving it. A resolver that
        // recursively resolves the imported module's own imports — the
        // pre-discovery frontend shape — re-enters the guard and reports
        // E2005 for the cycle. The production orchestrator additionally
        // rejects the complete resolved graph before checking;
        // CheckedProjectBuilderTest owns the whole-project cycle
        // negatives.
        List<CompilerDiagnostic> nestedDiagnostics = new ArrayList<>();
        ModuleResolver recursive = new ModuleResolver() {
            @Override
            public Map<String, Type> resolveModule(String modulePath,
                    String importingModule, Set<String> modulesInProgress)
                    throws ModuleNotFoundException {
                boolean first = modulePath.equals("./a");
                String source = first
                    ? "import * as B from \"./b\";\nexport function fa(): int;\n"
                    : "import * as A from \"./a\";\nexport function fb(): int;\n";
                String filename = first ? "a.deal" : "b.deal";
                LexResult lex = new Lexer(source, filename).tokenize();
                ParseResult parse = new Parser(lex.tokens(), filename,
                    lex.directiveEvents()).parse();
                NameResolver nested = new NameResolver(filename, this,
                    modulesInProgress);
                nested.resolve(parse.program());
                nestedDiagnostics.addAll(nested.diagnostics());
                return Map.of(first ? "fa" : "fb", Type.Int.INSTANCE);
            }

            @Override
            public Symbol.ClassSymbol resolveClassSymbol(String className,
                    String modulePath, String importingModule) {
                return null;
            }
        };

        checkProgramWithModule(
            "import * as A from \"./a\";\n"
            + "let x: int = 1;\n",
            "test.deal",
            recursive
        );
        boolean rejected = nestedDiagnostics.stream().anyMatch(d ->
            "E2005".equals(d.code())
                && "error".equals(d.severity())
                && "Circular import: './a'".equals(d.message()));
        check(rejected, "the frontend recursion guard rejects the cycle with"
            + " error E2005: " + nestedDiagnostics);
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

    /**
     * The bytes intrinsic sits at the bottom of the spec's name
     * resolution order (docs/spec-v1.3.md §Name resolution: module-level
     * declarations resolve at step 3, compiler intrinsics only at
     * step 5). A user class/function/let named `bytes` is legal and
     * shadows the intrinsic; the `bytes` TYPE annotation keeps its
     * class-symbol-first guard — a checker-accepted user class named
     * `bytes` resolves to its ClassSymbol type and wins over the
     * primitive at any nesting depth.
     */
    static void testV12BytesClassSymbolFirstResolution() {
        System.out.println("-- v1.2 bytes: user declarations named bytes shadow the intrinsic --");

        // The intrinsic stays available when unshadowed.
        CheckerOutput out = checkProgram(
            "let b: bytes = bytes(4); let n: int = b.length;");
        assertNoErrors(out, "unshadowed bytes(4)/b.length resolves to the intrinsic");

        // A module-level user CLASS named bytes is legal (no E2002) and
        // wins over the primitive in type annotations.
        out = checkProgram(
            "class bytes { x: int; }\n" +
            "let b: bytes = { x: 2 };\n" +
            "let n: int = b.x;");
        assertNoErrors(out, "module-level user class named bytes shadows the intrinsic");

        // In expression position the class wins too: a class is not
        // callable, so bytes(3) is E3008 — never the intrinsic call.
        out = checkProgram(
            "class bytes { x: int; }\n" +
            "let y: int = bytes(3);");
        assertError(out, "E3008",
            "user class named bytes is not callable (class wins over the intrinsic)");

        // A module-level user FUNCTION named bytes is legal (no E2002)
        // and calls resolve to the user function.
        out = checkProgram(
            "function bytes(x: int): int { return x + 1; }\n" +
            "let y: int = bytes(3);");
        assertNoErrors(out, "module-level user function named bytes shadows the intrinsic");

        // A module-level LET named bytes is legal and shadows the
        // intrinsic value; the bytes TYPE keeps its primitive meaning
        // (only a ClassSymbol shadows the type).
        out = checkProgram(
            "let bytes: int = 5;\n" +
            "let y: int = bytes;");
        assertNoErrors(out, "module-level let named bytes shadows the intrinsic value");
        out = checkProgram(
            "let bytes: int = 5;\n" +
            "let b: bytes = bytes(2);");
        assertError(out, "E3008",
            "the shadowing let is not callable while the bytes type stays the primitive");

        out = checkProgram(
            "class bytes { x: int; }\n" +
            "function f(): number {\n" +
            "  let b: bytes = { x: 2 };\n" +
            "  let n: int = b.x;\n" +
            "  return 1.5;\n}\n");
        assertNoErrors(out, "module-level bytes class wins in nested function annotations");

        // Two module-level user declarations named bytes still redeclare.
        out = checkProgram(
            "class bytes { x: int; }\n" +
            "function bytes(x: int): int { return x; }");
        assertError(out, "E2002", "two user declarations named bytes still redeclare");

        // The first-class bytes value keeps its seeded signature when
        // unshadowed.
        out = checkProgram(
            "let f: (x: int) => bytes = bytes;\n" +
            "let b: bytes = f(2);");
        assertNoErrors(out, "first-class bytes value keeps its (int)->bytes signature");
    }

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
        assertNoErrors(out, "for-of over int[] with int loop var");
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

    /**
     * ISSUE-0884: a catch identifier declares a local Error variable, so it
     * is a user declaration under the $ prohibition. The canonical E2008
     * wording is kept, every $ position is rejected, and nested catch
     * scopes are each checked. Normal catch bindings, generated @jsonable
     * helper names, and dynamic table/string data keys stay accepted.
     */
    static void testE2008_dollarInCatchVar() {
        System.out.println("-- ISSUE-0884: dollar in catch variable (E2008) --");

        // First-, middle-, and last-position $ are each rejected; the bare
        // $ is the shortest identifier the leading position produces.
        String[] catchVars = { "$bad", "$", "bad$name", "b$ad", "bad$" };
        for (String catchVar : catchVars) {
            CheckerOutput out = checkProgram(
                "export function main(): null {\n"
                + "  try { throw {}; } catch (" + catchVar + ") {}\n"
                + "  return null;\n"
                + "}\n");
            assertError(out, "E2008", "dollar in catch variable '" + catchVar
                + "' produces E2008");
            check(hasDiagnosticMessage(out, "E2008",
                    "Identifier '" + catchVar + "' must not contain '$'"),
                "catch variable '" + catchVar + "' keeps the canonical E2008"
                    + " wording: " + out.result.diagnostics());
        }

        // Nested catch scopes: the inner and the outer declaration are
        // each checked in their own catch scope.
        CheckerOutput nested = checkProgram(
            "export function main(): null {\n"
            + "  try { try {} catch (inner$e) {} } catch (outer$e) {}\n"
            + "  return null;\n"
            + "}\n");
        List<String> nestedMessages = nested.result.diagnostics().stream()
            .filter(d -> d.code().equals("E2008"))
            .map(CompilerDiagnostic::message)
            .toList();
        check(nestedMessages.equals(List.of(
                "Identifier 'inner$e' must not contain '$'",
                "Identifier 'outer$e' must not contain '$'")),
            "nested catch scopes each reject their $ identifier: "
                + nested.result.diagnostics());

        // Passing controls: ordinary catch identifiers (including nested
        // scopes and the Error surface) remain legal.
        CheckerOutput normal = checkProgram(
            "export function main(): null {\n"
            + "  try {\n"
            + "    try { throw {}; } catch (inner) {\n"
            + "      let msg: string = inner.message;\n"
            + "    }\n"
            + "  } catch (outer) {\n"
            + "    let code: string = outer.code;\n"
            + "  }\n"
            + "  return null;\n"
            + "}\n");
        assertNoErrors(normal, "normal catch identifiers stay accepted");

        // Generated names remain compiler-owned and source-visible.
        CheckerOutput helpers = checkProgram(
            "// @jsonable\n"
            + "export class Payload { name: string = \"\"; }\n"
            + "export function main(): null {\n"
            + "  let encoded: string = Payload$toJson({ name: \"x\" });\n"
            + "  let decoded: Payload | null = Payload$fromJson(encoded);\n"
            + "  return null;\n"
            + "}\n");
        assertNoErrors(helpers,
            "generated C$fromJson/C$toJson helpers stay visible");

        // Assessment control: table literal property names and table member
        // reads are dynamic string keys (data), not user declarations, so $
        // stays legal there; string data likewise. A class member read has
        // no such data-key route: it stays the declared-field rejection.
        CheckerOutput tableData = checkProgram(
            "export function main(): null {\n"
            + "  let t: table = { bad$key: 1 };\n"
            + "  let direct: int = t.bad$key;\n"
            + "  let key: string = \"bad$key\";\n"
            + "  t[key] = direct;\n"
            + "  let indexed: int = t[key];\n"
            + "  let text: string = \"price$\";\n"
            + "  let tpl: string = `tag$${text}`;\n"
            + "  return null;\n"
            + "}\n");
        assertNoErrors(tableData,
            "table keys and string data may contain $");

        CheckerOutput classMember = checkProgram(
            "class C { x: int = 0; }\n"
            + "export function main(): null {\n"
            + "  let c: C = { x: 1 };\n"
            + "  let v: int = c.bad$key;\n"
            + "  return null;\n"
            + "}\n");
        assertError(classMember, "E4002",
            "a class member read stays the declared-field rejection");
    }

    /**
     * ISSUE-0884: the catch declaration is rejected on every target
     * request before any artifact is published, with the identifier range
     * and the canonical E2008 wording — first- and middle-position $
     * included, each anchored on its own identifier span.
     */
    static void testE2008_dollarInCatchVarRejectedOnAllTargets() {
        System.out.println("-- ISSUE-0884: dollar in catch variable on all targets --");

        Path root;
        try {
            root = Files.createTempDirectory("deal_e2008_catch_");
        } catch (IOException e) {
            fail("dollar catch variable: cannot create temp directory: "
                + e.getMessage());
            return;
        }
        // name, identifier range on line 2 (start-(start+length))
        String[][] cases = {
            { "$bad", "main.deal:2:28-2:32" },
            { "bad$name", "main.deal:2:28-2:36" },
        };
        try {
            int index = 0;
            for (String[] c : cases) {
                String catchVar = c[0];
                String range = c[1];
                Path entry = cliProject(root, "catch_dollar_" + index++,
                    "export function main(): null {\n"
                    + "  try { throw {}; } catch (" + catchVar + ") {}\n"
                    + "  return null;\n"
                    + "}\n");
                for (String backend : List.of("lua", "luajit", "jvm", "js")) {
                    Path out = root.resolve("out-" + catchVar.replace("$", "s")
                        + "-" + backend);
                    CliResult result = cliCompile(entry, backend, out);
                    check(result.exitCode() == 1,
                        "dollar catch variable '" + catchVar
                            + "' is rejected on " + backend
                            + ": " + result.output());
                    check(result.output().contains("ERROR E2008: Identifier '"
                            + catchVar + "' must not contain '$'"),
                        "dollar catch variable '" + catchVar + "' keeps the"
                            + " canonical E2008 wording on " + backend
                            + ": " + result.output());
                    check(result.output().contains(range),
                        "dollar catch '" + catchVar + "' keeps the exact"
                            + " identifier range on " + backend
                            + ": " + result.output());
                    check(!Files.exists(out),
                        "dollar catch '" + catchVar + "' publishes no artifact"
                            + " directory on " + backend);
                }
            }
        } catch (IOException e) {
            fail("dollar catch variable on all targets: " + e.getMessage());
        } finally {
            deleteRecursively(root);
        }
    }

    /** True when one diagnostic of the code carries exactly the message. */
    private static boolean hasDiagnosticMessage(CheckerOutput out, String code,
                                                String message) {
        return out.result.diagnostics().stream()
            .anyMatch(d -> d.code().equals(code) && d.message().equals(message));
    }

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
        System.out.println("-- @jsonable: bytes field → E4007 --");
        // v1.2 bytes is not jsonable: a bytes-typed @jsonable field is
        // E4007 (deal-v1.2-int32-and-bytes-architecture D3).
        CheckerOutput bytesField = checkProgram(
            "// @jsonable\n" +
            "export class Holder {\n" +
            "  payload: bytes = bytes(2);\n" +
            "}\n"
        );
        assertError(bytesField, "E4007", "bytes field is not jsonable");

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
        exports.put("Foreign", IdentityTestFixtures.classType("Foreign", "./foreign"));
        exports.put("Foreign$fromJson",
            new Type.Func(List.of(Type.String.INSTANCE),
                Types.nullable(IdentityTestFixtures.classType("Foreign", "./foreign"))));
        exports.put("Foreign$toJson",
            new Type.Func(List.of(IdentityTestFixtures.classType("Foreign", "./foreign")),
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
        exports.put("Plain", IdentityTestFixtures.classType("Plain", "./foreign"));
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

    /**
     * A local {@code @jsonable} class and an imported jsonable class of
     * the same name carry distinct canonical identities: the imported
     * class is a valid field type and neither class creates a false
     * dependency edge on the other (ISSUE-0784).
     */

    /**
     * An imported class whose spelling the importing module also declares
     * locally resolves to the imported provider's declaration (ISSUE-0809):
     * the construction, the field read and the {@code has} check all read
     * the imported declaration, never the same-spelled local one.
     */
    static void testImportedClassProviderWithSameNamedLocal() {
        System.out.println("-- imported class provider with a same-named local "
            + "declaration --");
        StubModuleResolver resolver = new StubModuleResolver();
        deal.ast.Span span = new deal.ast.Span("test.deal", 1, 1, 1, 5);
        Symbol.ClassSymbol foreign = new Symbol.ClassSymbol("Point",
            List.of(new ClassField(span, "x", false, false,
                    new deal.ast.NamedType(span, "int"), java.util.Optional.empty()),
                new ClassField(span, "tag", true, false,
                    new deal.ast.NamedType(span, "string"), java.util.Optional.empty())),
            "./foreign", IdentityTestFixtures.identityOf("./foreign", "Point"));
        resolver.registerClassSymbol("./foreign", foreign);
        Map<String, Type> exports = new HashMap<>();
        exports.put("Point", IdentityTestFixtures.classType("Point", "./foreign"));
        resolver.register("./foreign", exports);

        CheckerOutput out = checkProgramWithModule(
            "import * as Other from \"./foreign\";\n" +
            "\n" +
            "class Point {\n" +
            "  y: string = \"\";\n" +
            "}\n" +
            "\n" +
            "export function main(): null {\n" +
            "  let a: Other.Point = { x: 1 };\n" +
            "  let x: int = a.x;\n" +
            "  let tag: boolean = has(a.tag);\n" +
            "  let p: Point = { y: \"s\" };\n" +
            "  let y: string = p.y;\n" +
            "  return null;\n" +
            "}\n",
            resolver);
        assertNoErrors(out, "the imported provider's declaration owns its "
            + "construction, field read and has check while the local "
            + "same-named class stays local");
        boolean sawForeign = out.result.typeMap().values().stream()
            .anyMatch(t -> t instanceof Type.Class cls
                && cls.identity().equals(
                    IdentityTestFixtures.identityOf("./foreign", "Point")));
        check(sawForeign, "the imported construction carries the imported "
            + "provider's canonical identity");
    }

    /**
     * The delete target resolves the imported provider's declaration, not
     * a same-named local declaration (ISSUE-0809 review cycle 1): the
     * required/optional decision of an imported class's field belongs to
     * the declaring provider only, in both directions (a required imported
     * field is never deletable beside an optional local spelling, and an
     * optional imported field stays deletable beside a required local
     * spelling).
     */
    static void testImportedProviderDeleteBesideSameNamedLocal() {
        System.out.println("-- imported-provider delete beside a same-named local "
            + "declaration --");
        deal.ast.Span span = new deal.ast.Span("test.deal", 1, 1, 1, 5);

        // The imported provider's field is required while the local
        // spelling's is optional: the delete must be rejected.
        StubModuleResolver requiredProvider = new StubModuleResolver();
        requiredProvider.registerClassSymbol("./foreign", new Symbol.ClassSymbol(
            "Point",
            List.of(new ClassField(span, "nick", false, false,
                new deal.ast.NamedType(span, "int"), java.util.Optional.empty())),
            "./foreign", IdentityTestFixtures.identityOf("./foreign", "Point")));
        Map<String, Type> requiredExports = new HashMap<>();
        requiredExports.put("Point",
            IdentityTestFixtures.classType("Point", "./foreign"));
        requiredProvider.register("./foreign", requiredExports);
        CheckerOutput required = checkProgramWithModule(
            "import * as Other from \"./foreign\";\n"
            + "\n"
            + "class Point {\n"
            + "  nick?: string;\n"
            + "}\n"
            + "\n"
            + "export function main(): null {\n"
            + "  let a: Other.Point = {};\n"
            + "  delete a.nick;\n"
            + "  return null;\n"
            + "}\n",
            requiredProvider);
        check(required.result.diagnostics().stream()
                .anyMatch(d -> d.code().equals("E4004")),
            "the required imported field's delete is rejected beside an "
                + "optional same-named local declaration: "
                + required.result.diagnostics());

        // The imported provider's field is optional while the local
        // spelling's is required: the delete must be accepted.
        StubModuleResolver optionalProvider = new StubModuleResolver();
        optionalProvider.registerClassSymbol("./foreign", new Symbol.ClassSymbol(
            "Point",
            List.of(new ClassField(span, "nick", true, false,
                new deal.ast.NamedType(span, "string"),
                java.util.Optional.empty())),
            "./foreign", IdentityTestFixtures.identityOf("./foreign", "Point")));
        Map<String, Type> optionalExports = new HashMap<>();
        optionalExports.put("Point",
            IdentityTestFixtures.classType("Point", "./foreign"));
        optionalProvider.register("./foreign", optionalExports);
        CheckerOutput optional = checkProgramWithModule(
            "import * as Other from \"./foreign\";\n"
            + "\n"
            + "class Point {\n"
            + "  nick: int = 1;\n"
            + "}\n"
            + "\n"
            + "export function main(): null {\n"
            + "  let a: Other.Point = {};\n"
            + "  delete a.nick;\n"
            + "  return null;\n"
            + "}\n",
            optionalProvider);
        check(optional.result.diagnostics().stream()
                .noneMatch(d -> d.code().equals("E4004")),
            "the optional imported field's delete is accepted beside a "
                + "required same-named local declaration: "
                + optional.result.diagnostics());
    }

    /**
     * A value whose checked class declaration binds one defining provider
     * never retags to an expected declaration of another provider
     * (ISSUE-0809 review cycle 2): the harness synthesizes one canonical
     * identity for an imported {@code Other.Point} and a local
     * {@code Point} (valid source gives each defining file its own
     * identity), so the pair is one identity and two declarations. Every
     * assignment boundary — declaration initialization, assignment, call
     * argument, return, array element, class-literal field and nullable
     * target — names the crossing deterministically (E3019) instead of
     * accepting the value, while same-provider values stay compatible.
     */
    static void testImportedProviderCrossingRejected() {
        System.out.println("-- a same-identity same-spelled imported/local "
            + "declaration crossing is E3019 at every assignment boundary --");
        deal.ast.Span span = new deal.ast.Span("test.deal", 1, 1, 1, 5);
        // The imported declaration shares the consumer's canonical class
        // identity (two files in one directory) while carrying its own
        // defining provider module: the crossing is a provider crossing,
        // never an identity mismatch.
        deal.identity.CanonicalClassIdentity sharedIdentity =
            IdentityTestFixtures.identityOf("test.deal", "Point");
        StubModuleResolver resolver = new StubModuleResolver();
        resolver.registerClassSymbol("./foreign", new Symbol.ClassSymbol(
            "Point",
            List.of(new ClassField(span, "nick", true, false,
                new deal.ast.NamedType(span, "int"), java.util.Optional.empty())),
            "./foreign", sharedIdentity));
        Type foreignPoint = Types.classType("Point", sharedIdentity, "./foreign");
        Map<String, Type> exports = new HashMap<>();
        exports.put("Point", foreignPoint);
        resolver.register("./foreign", exports);

        String local = "import * as Other from \"./foreign\";\n"
            + "class Point { nick?: string; }\n";

        CheckerOutput init = checkProgramWithModule(local
            + "export function main(): null {\n"
            + "  let y: Other.Point = {};\n"
            + "  let p: Point = y;\n"
            + "  return null;\n"
            + "}\n", resolver);
        assertError(init, "E3019",
            "an imported value at a same-identity local declaration "
                + "initialization is E3019");

        CheckerOutput reverse = checkProgramWithModule(local
            + "export function main(): null {\n"
            + "  let p: Point = {};\n"
            + "  let y: Other.Point = p;\n"
            + "  return null;\n"
            + "}\n", resolver);
        assertError(reverse, "E3019",
            "a local value at a same-identity imported declaration "
                + "initialization is E3019");

        CheckerOutput assignment = checkProgramWithModule(local
            + "export function main(): null {\n"
            + "  let y: Other.Point = {};\n"
            + "  let p: Point = {};\n"
            + "  p = y;\n"
            + "  return null;\n"
            + "}\n", resolver);
        assertError(assignment, "E3019",
            "an assignment crossing the two same-identity declarations is "
                + "E3019");

        CheckerOutput nullable = checkProgramWithModule(local
            + "export function main(): null {\n"
            + "  let y: Other.Point = {};\n"
            + "  let p: Point | null = y;\n"
            + "  return null;\n"
            + "}\n", resolver);
        assertError(nullable, "E3019",
            "a nullable same-identity target keeps the crossing rejection");

        CheckerOutput argument = checkProgramWithModule(local
            + "export function main(): null {\n"
            + "  let y: Other.Point = {};\n"
            + "  take(y);\n"
            + "  return null;\n"
            + "}\n"
            + "function take(p: Point): int { return 0; }\n", resolver);
        assertError(argument, "E3019",
            "an imported value at a local class-typed argument position is "
                + "E3019 (never a silent retag)");

        CheckerOutput returned = checkProgramWithModule(local
            + "function make(): Point { let y: Other.Point = {}; return y; }\n"
            + "export function main(): null { return null; }\n", resolver);
        assertError(returned, "E3019",
            "an imported value at a local declared-return position is E3019");

        CheckerOutput arrayElement = checkProgramWithModule(local
            + "export function main(): null {\n"
            + "  let y: Other.Point = {};\n"
            + "  let xs: Point[] = [y];\n"
            + "  return null;\n"
            + "}\n", resolver);
        assertError(arrayElement, "E3019",
            "an imported value as a same-identity array element is E3019");

        CheckerOutput literalField = checkProgramWithModule(local
            + "class Holder { p: Point = {}; }\n"
            + "export function main(): null {\n"
            + "  let y: Other.Point = {};\n"
            + "  let h: Holder = { p: y };\n"
            + "  return null;\n"
            + "}\n", resolver);
        assertError(literalField, "E3019",
            "an imported value at a same-identity class-literal field is "
                + "E3019");

        // A literal whose expectation's own carrier binds the callee
        // parameter's provider is laid out by that declaration: the
        // enclosing annotation's same-identity declaration never
        // captures it (ISSUE-0809 review cycle 2 finding 1), so the
        // legal nested-call shape constructs the parameter declaration
        // instead of reporting a false crossing.
        CheckerOutput nested = checkProgramWithModule(local
            + "function f(x: Other.Point): Point { return {}; }\n"
            + "function g(x: Other.Point): Other.Point { return x; }\n"
            + "export function main(): null {\n"
            + "  let p: Point = f(g({}));\n"
            + "  return null;\n"
            + "}\n", resolver);
        assertNoErrors(nested,
            "the nested-call literal is laid out by the callee parameter's "
                + "declaration, never captured by the enclosing same-identity "
                + "annotation");

        // Same-provider values stay compatible: the imported declaration
        // initializing itself and the local declaration within itself are
        // legal, and the imported provider's own field stays readable.
        CheckerOutput control = checkProgramWithModule(local
            + "export function main(): null {\n"
            + "  let y: Other.Point = {};\n"
            + "  let y2: Other.Point = y;\n"
            + "  let n: int | null = y2.nick;\n"
            + "  let p: Point = {};\n"
            + "  let p2: Point = p;\n"
            + "  return null;\n"
            + "}\n", resolver);
        assertNoErrors(control,
            "same-provider values remain compatible beside the distinct "
                + "same-spelled declaration");
    }

    static void testCallReceiverAndFunctionTypedProviderResolution() {
        System.out.println("-- a call-result receiver and a function-typed "
            + "callee resolve the declared provider, never a same-identity "
            + "local or first-listed declaration --");
        deal.ast.Span span = new deal.ast.Span("test.deal", 1, 1, 1, 5);
        deal.identity.CanonicalClassIdentity sharedIdentity =
            IdentityTestFixtures.identityOf("test.deal", "Point");
        for (boolean irFirst : new boolean[] { true, false }) {
            String order = irFirst ? "ir registered first"
                : "strict registered first";
            StubModuleResolver resolver = new StubModuleResolver();

            // The optional-field provider: `nick?: string` and the `a: int`
            // member the local declaration does not carry.
            Symbol.ClassSymbol irPoint = new Symbol.ClassSymbol("Point",
                List.of(new ClassField(span, "nick", true, false,
                        new deal.ast.NamedType(span, "string"),
                        java.util.Optional.empty()),
                    new ClassField(span, "a", false, false,
                        new deal.ast.NamedType(span, "int"),
                        java.util.Optional.empty())),
                "./ir", sharedIdentity);
            Map<String, Type> irExports = new HashMap<>();
            irExports.put("Point", Types.classType("Point", sharedIdentity,
                "./ir"));
            irExports.put("make", new Type.Func(List.of(),
                Types.classType("Point", sharedIdentity, "./ir"), false));
            irExports.put("take", new Type.Func(List.of(
                Types.classType("Point", sharedIdentity, "./ir")),
                Type.Int.INSTANCE, false));

            // The required-field provider (the other direction of the
            // required/optional decision).
            Symbol.ClassSymbol strictPoint = new Symbol.ClassSymbol("Point",
                List.of(new ClassField(span, "nick", false, false,
                    new deal.ast.NamedType(span, "int"),
                    java.util.Optional.empty())),
                "./strict", sharedIdentity);
            Map<String, Type> strictExports = new HashMap<>();
            strictExports.put("Point", Types.classType("Point", sharedIdentity,
                "./strict"));
            strictExports.put("make", new Type.Func(List.of(),
                Types.classType("Point", sharedIdentity, "./strict"),
                false));

            if (irFirst) {
                resolver.registerClassSymbol("./ir", irPoint);
                resolver.register("./ir", irExports);
                resolver.registerClassSymbol("./strict", strictPoint);
                resolver.register("./strict", strictExports);
            } else {
                resolver.registerClassSymbol("./strict", strictPoint);
                resolver.register("./strict", strictExports);
                resolver.registerClassSymbol("./ir", irPoint);
                resolver.register("./ir", irExports);
            }

            // The local same-identity declaration carries the required
            // `nick` and no `a`: a name/identity-based fallback would own
            // the call-result receiver's field decisions.
            String local = "import * as Other from \"./ir\";\n"
                + "class Point { nick: int = 1; }\n";
            CheckerOutput resolved = checkProgramWithModule(local
                + "export function main(): null {\n"
                + "  if (has(Other.make().nick)) { return null; }\n"
                + "  let v: int = Other.make().a;\n"
                + "  let y = Other.make();\n"
                + "  delete y.nick;\n"
                + "  let f: (x: Other.Point) => int = Other.take;\n"
                + "  let n: int = f({ nick: \"s\", a: 1 });\n"
                + "  return null;\n"
                + "}\n", resolver);
            assertNoErrors(resolved,
                "the optional receiver provider owns the has check, the call "
                    + "read, the inferred-binding delete and the function-typed "
                    + "argument literal beside the same-identity local "
                    + "declaration (" + order + ")");

            // The reversed requirement direction: the imported provider's
            // `nick` is required while the local same-identity declaration
            // declares it optional — the imported declaration still owns
            // the decisions, so both are refused.
            String optionalLocal = "import * as Other from \"./strict\";\n"
                + "class Point { nick?: string; }\n";
            CheckerOutput hasRequired = checkProgramWithModule(optionalLocal
                + "export function main(): null {\n"
                + "  if (has(Other.make().nick)) { return null; }\n"
                + "  return null;\n"
                + "}\n", resolver);
            assertError(hasRequired, "E4005",
                "a required field of the receiver's declared provider is not "
                    + "silently accepted because a local same-identity "
                    + "declaration is optional (" + order + ")");
            CheckerOutput deleteRequired = checkProgramWithModule(optionalLocal
                + "export function main(): null {\n"
                + "  let y = Other.make();\n"
                + "  delete y.nick;\n"
                + "  return null;\n"
                + "}\n", resolver);
            assertError(deleteRequired, "E4004",
                "a required field of the receiver's declared provider is not "
                    + "deletable because a local same-identity declaration is "
                    + "optional (" + order + ")");
        }
    }

    static void testJsonableImportedSameNameDistinctIdentity() {
        System.out.println("-- @jsonable: distinct same-named imported class identity --");
        StubModuleResolver resolver = new StubModuleResolver();
        Type foreignB = IdentityTestFixtures.classType("B", "./foreign");
        Map<String, Type> exports = new HashMap<>();
        exports.put("B", foreignB);
        exports.put("B$fromJson", new Type.Func(List.of(Type.String.INSTANCE),
            Types.nullable(foreignB)));
        exports.put("B$toJson", new Type.Func(List.of(foreignB),
            Type.String.INSTANCE));
        resolver.register("./foreign", exports);

        CheckerOutput out = checkProgramWithModule(
            "import * as Other from \"./foreign\";\n" +
            "// @jsonable\n" +
            "export class B {\n" +
            "  local: int = 0;\n" +
            "}\n" +
            "// @jsonable\n" +
            "export class A {\n" +
            "  other: Other.B | null = null;\n" +
            "  local: B = {};\n" +
            "}\n",
            resolver
        );
        assertNoErrors(out, "a local and an imported same-named jsonable class"
            + " are distinct identities");
        List<CompilerDiagnostic> diags = out.result.diagnostics();
        check(diags.stream().noneMatch(d -> d.code().equals("E4008")),
            "no false E4008 from the same-named imported class: " + diags);
        check(diags.stream().noneMatch(d -> d.code().equals("E4007")),
            "no false E4007 from the same-named imported class: " + diags);
    }

    /**
     * Imported generated jsonable classes are valid field types through
     * array and nullable wrappers (the identity-keyed helper visibility
     * recursion of ISSUE-0784).
     */
    static void testJsonableImportedThroughArrayAndNullable() {
        System.out.println("-- @jsonable: imported jsonable class through array/nullable --");
        StubModuleResolver resolver = new StubModuleResolver();
        Type foreign = IdentityTestFixtures.classType("Widget", "./foreign");
        Map<String, Type> exports = new HashMap<>();
        exports.put("Widget", foreign);
        exports.put("Widget$fromJson", new Type.Func(
            List.of(Type.String.INSTANCE), Types.nullable(foreign)));
        exports.put("Widget$toJson", new Type.Func(List.of(foreign),
            Type.String.INSTANCE));
        resolver.register("./foreign", exports);

        CheckerOutput out = checkProgramWithModule(
            "import * as Other from \"./foreign\";\n" +
            "// @jsonable\n" +
            "export class Holder {\n" +
            "  items: Other.Widget[] = [];\n" +
            "  note: Other.Widget | null = null;\n" +
            "}\n",
            resolver
        );
        assertNoErrors(out, "imported jsonable classes are valid through"
            + " array and nullable wrappers");
    }

    /**
     * A local generated cycle still rejects with E4008 while an imported
     * jsonable class of the same name exists: identity, not name,
     * decides both the dependency edge and the cycle (ISSUE-0784).
     */
    static void testJsonableLocalCycleWithSameNamedImported() {
        System.out.println("-- @jsonable: local cycle with same-named imported class --");
        StubModuleResolver resolver = new StubModuleResolver();
        Type foreignB = IdentityTestFixtures.classType("B", "./foreign");
        Map<String, Type> exports = new HashMap<>();
        exports.put("B", foreignB);
        exports.put("B$fromJson", new Type.Func(List.of(Type.String.INSTANCE),
            Types.nullable(foreignB)));
        exports.put("B$toJson", new Type.Func(List.of(foreignB),
            Type.String.INSTANCE));
        resolver.register("./foreign", exports);

        CheckerOutput out = checkProgramWithModule(
            "import * as Other from \"./foreign\";\n" +
            "// @jsonable\n" +
            "export class A {\n" +
            "  b: B;\n" +
            "  other: Other.B | null = null;\n" +
            "}\n" +
            "// @jsonable\n" +
            "export class B {\n" +
            "  a: A;\n" +
            "}\n",
            resolver
        );
        assertError(out, "E4008",
            "the local A <-> B cycle still rejects with E4008");
    }

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
        ParseResult parse = new Parser(lex.tokens(), "test.deal", lex.directiveEvents()).parse();
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

    /** The Error class type the builtin `Error` declaration names. */
    private static Type intrinsicErrorType() {
        return Types.classType("Error", NameResolver.intrinsicErrorIdentity());
    }

    /** The first statement (depth-first, source order) of the given type. */
    private static <T extends StatementNode> T findStatement(
            List<StatementNode> statements, Class<T> type) {
        for (StatementNode statement : statements) {
            if (type.isInstance(statement)) {
                return type.cast(statement);
            }
            for (List<StatementNode> children : childStatementLists(statement)) {
                T found = findStatement(children, type);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static <T extends StatementNode> T findStatement(
            StatementNode statement, Class<T> type) {
        return findStatement(List.of(statement), type);
    }

    /** The nested statement lists of one statement, in source order. */
    private static List<List<StatementNode>> childStatementLists(
            StatementNode statement) {
        List<List<StatementNode>> children = new ArrayList<>();
        switch (statement) {
            case ExportDeclaration ed -> children.add(List.of(ed.declaration()));
            case FunctionDeclaration fd -> children.add(fd.body().statements());
            case Block b -> children.add(b.statements());
            case IfStatement is -> {
                children.add(is.thenBlock().statements());
                is.elseBranch().ifPresent(branch -> {
                    if (branch instanceof Either.Left<IfStatement, Block> left) {
                        children.add(List.of(left.value()));
                    } else if (branch instanceof Either.Right<IfStatement, Block> right) {
                        children.add(right.value().statements());
                    }
                });
            }
            case WhileStatement ws -> children.add(ws.body().statements());
            case ForStatement fs -> children.add(fs.body().statements());
            case ForOfStatement fos -> children.add(fos.body().statements());
            case TryStatement ts -> {
                children.add(ts.tryBlock().statements());
                children.add(ts.catchBlock().statements());
            }
            default -> { }
        }
        return children;
    }

    /** The identifier initializer of the statement list's index-th declaration. */
    private static IdentifierExpr identifierInitializer(
            List<StatementNode> statements, int index) {
        if (index < 0 || index >= statements.size()) {
            return null;
        }
        if (statements.get(index) instanceof VariableDeclaration declaration
                && declaration.initializer() instanceof IdentifierExpr id) {
            return id;
        }
        return null;
    }

    /**
     * The read type of the statement at {@code index} of {@code statements}
     * (a `let` declaration initialized directly by an identifier), or null.
     */
    private static Type readTypeAt(CheckerOutput out, List<StatementNode> statements,
            int index) {
        IdentifierExpr read = identifierInitializer(statements, index);
        return read == null ? null : out.result.typeMap().get(read);
    }

    /**
     * The catch identifier is a fresh `Error` binding (docs/spec-v1.3.md
     * §try / catch: "The bound variable e has type Error"), so a same-named
     * outer binding's null refinement never types the catch read and never
     * outlives — or overrides — the outer binding's own flow facts
     * (ISSUE-0888).
     */
    static void testCatchBindingNarrowingIsolation() {
        System.out.println("-- Catch binding isolated from same-named outer refinements --");
        Type errorType = intrinsicErrorType();

        // -- The positive: an int-refined outer binding does not type the
        //    catch read, and the refinement is restored after the catch.
        String intRefined =
            "export function main(): null {" +
            "  let e: int | null = 1;" +
            "  if (e !== null) {" +
            "    try { throw { code: \"C_INT\", message: \"c\" }; }" +
            "    catch (e) { let captured: Error = e; }" +
            "    let afterCatch: int = e;" +
            "  }" +
            "  return null;" +
            "}";
        CheckerOutput out = checkProgram(intRefined);
        assertNoErrors(out, "the catch Error read under an int refinement");
        IfStatement refinedIf = findStatement(out.program().statements(), IfStatement.class);
        check(refinedIf != null, "the int-refinement source carries its if statement");
        TryStatement tryStatement = refinedIf == null ? null
            : findStatement(refinedIf.thenBlock(), TryStatement.class);
        check(tryStatement != null, "the int-refinement source carries its try statement");
        if (tryStatement != null) {
            check(Types.equals(errorType, readTypeAt(out,
                    tryStatement.catchBlock().statements(), 0)),
                "the catch read's checked type is the Error class type, got "
                    + readTypeAt(out, tryStatement.catchBlock().statements(), 0));
        }
        check(Types.equals(Type.Int.INSTANCE, readTypeAt(out,
                refinedIf == null ? List.of() : refinedIf.thenBlock().statements(), 1)),
            "the int refinement is restored after the catch scope exits, got "
                + (refinedIf == null ? null : readTypeAt(out,
                    refinedIf.thenBlock().statements(), 1)));

        // -- The null refinement is the same distinct binding.
        CheckerOutput nullRefined = checkProgram(
            "export function main(): null {" +
            "  let e: int | null = null;" +
            "  if (e === null) {" +
            "    try { throw { code: \"C_NULL\", message: \"n\" }; }" +
            "    catch (e) { let captured: Error = e; }" +
            "  }" +
            "  return null;" +
            "}");
        assertNoErrors(nullRefined, "the catch Error read under a null refinement");
        TryStatement nullTry = findStatement(nullRefined.program().statements(), TryStatement.class);
        check(nullTry != null && Types.equals(errorType,
                readTypeAt(nullRefined, nullTry.catchBlock().statements(), 0)),
            "the null-refined catch read's checked type is the Error class type");

        // -- The unsound inverse: a string refinement must not turn the
        //    catch binding into a string.
        CheckerOutput stringTransfer = checkProgram(
            "export function main(): null {" +
            "  let e: string | null = \"outer\";" +
            "  if (e !== null) {" +
            "    try { throw { code: \"C_STR\", message: \"s\" }; }" +
            "    catch (e) { let unsound: string = e; }" +
            "  }" +
            "  return null;" +
            "}");
        assertErrorMessage(stringTransfer, "E3001",
            "Cannot assign Error to string",
            "a string-refined catch binding is not a string");

        // -- The reported defect's mirror: the catch binding is never the
        //    outer int either.
        CheckerOutput intTransfer = checkProgram(
            "export function main(): null {" +
            "  let e: int | null = 1;" +
            "  if (e !== null) {" +
            "    try { throw { code: \"C_INT\", message: \"i\" }; }" +
            "    catch (e) { let unsound: int = e; }" +
            "  }" +
            "  return null;" +
            "}");
        assertErrorMessage(intTransfer, "E3001",
            "Cannot assign Error to int",
            "an int-refined catch binding is not an int");

        // -- A try-block declaration shadows a distinct binding, so the
        //    outer refinement survives the try/catch.
        CheckerOutput tryShadow = checkProgram(
            "export function main(): null {" +
            "  let e: int | null = 1;" +
            "  if (e !== null) {" +
            "    try { let e: string = \"inner\"; } catch (err) { }" +
            "    let afterCatch: int = e;" +
            "  }" +
            "  return null;" +
            "}");
        assertNoErrors(tryShadow, "a try-block shadowing declaration keeps the outer refinement");
        IfStatement shadowIf = findStatement(tryShadow.program().statements(), IfStatement.class);
        check(Types.equals(Type.Int.INSTANCE, readTypeAt(tryShadow,
                shadowIf == null ? List.of() : shadowIf.thenBlock().statements(), 1)),
            "the outer refinement survives a try-block shadowing declaration");

        // -- A try/catch nested inside an enclosing try block reassigns only
        //    its own catch binding: the try/catch revert closes in the scope
        //    that receives the code after the catch, never in the catch
        //    scope (where the catch binding resolves to itself and would
        //    invalidate the entry the catch scope just restored), so the
        //    same-named outer refinement survives the inner catch and the
        //    outer catch.
        CheckerOutput nestedCatchAssignment = checkProgram(
            "export function main(): null {" +
            "  let e: int | null = 1;" +
            "  if (e !== null) {" +
            "    try {" +
            "      try { throw { code: \"C_IN\", message: \"in\" }; }" +
            "      catch (e) { e = { code: \"C_RE\", message: \"re\" }; }" +
            "      let afterInner: int = e;" +
            "    } catch (outer) { }" +
            "    let afterOuter: int = e;" +
            "  }" +
            "  return null;" +
            "}");
        assertNoErrors(nestedCatchAssignment,
            "a nested catch-binding reassignment keeps the outer refinement");
        IfStatement nestedIf = findStatement(nestedCatchAssignment.program()
            .statements(), IfStatement.class);
        TryStatement enclosingTry = nestedIf == null ? null
            : findStatement(nestedIf.thenBlock(), TryStatement.class);
        check(Types.equals(Type.Int.INSTANCE, readTypeAt(nestedCatchAssignment,
                enclosingTry == null ? List.of()
                    : enclosingTry.tryBlock().statements(), 1)),
            "the outer refinement is restored after the nested catch, got "
                + (enclosingTry == null ? null : readTypeAt(nestedCatchAssignment,
                    enclosingTry.tryBlock().statements(), 1)));
        check(Types.equals(Type.Int.INSTANCE, readTypeAt(nestedCatchAssignment,
                nestedIf == null ? List.of() : nestedIf.thenBlock().statements(), 1)),
            "the outer refinement survives the enclosing catch, got "
                + (nestedIf == null ? null : readTypeAt(nestedCatchAssignment,
                    nestedIf.thenBlock().statements(), 1)));

        // -- The same nesting under a string refinement: the reassigned catch
        //    binding is never the outer string either.
        CheckerOutput nestedString = checkProgram(
            "export function main(): null {" +
            "  let text: string | null = \"outer\";" +
            "  if (text !== null) {" +
            "    try {" +
            "      try { throw { code: \"C_IN\", message: \"in\" }; }" +
            "      catch (text) { text = { code: \"C_RE\", message: \"re\" }; }" +
            "      let afterInner: string = text;" +
            "    } catch (outer) { }" +
            "    let afterOuter: string = text;" +
            "  }" +
            "  return null;" +
            "}");
        assertNoErrors(nestedString,
            "a nested catch-binding reassignment keeps a string refinement");
        IfStatement stringIf = findStatement(nestedString.program().statements(),
            IfStatement.class);
        TryStatement stringTry = stringIf == null ? null
            : findStatement(stringIf.thenBlock(), TryStatement.class);
        check(Types.equals(Type.String.INSTANCE, readTypeAt(nestedString,
                stringTry == null ? List.of() : stringTry.tryBlock().statements(), 1)),
            "the string refinement is restored after the nested catch, got "
                + (stringTry == null ? null : readTypeAt(nestedString,
                    stringTry.tryBlock().statements(), 1)));
        check(Types.equals(Type.String.INSTANCE, readTypeAt(nestedString,
                stringIf == null ? List.of() : stringIf.thenBlock().statements(), 1)),
            "the string refinement survives the enclosing catch, got "
                + (stringIf == null ? null : readTypeAt(nestedString,
                    stringIf.thenBlock().statements(), 1)));

        // -- The catch scope's exit never resurrects a refinement the
        //    language invalidated: an await clears it and an assignment to
        //    the enclosing binding removes it.
        CheckerOutput awaited = checkProgram(
            "async function g(): int { return 5; }" +
            "async function f(e: int | null): int {" +
            "  if (e !== null) {" +
            "    try { throw { code: \"C_AWAIT\", message: \"a\" }; }" +
            "    catch (e) { let captured: Error = e; let resumed: int = await g(); }" +
            "    let unsound: int = e;" +
            "  }" +
            "  return 0;" +
            "}");
        assertErrorMessage(awaited, "E3001", "Cannot assign int | null to int",
            "an await inside the catch never resurrects the outer refinement");
        CheckerOutput outerAssigned = checkProgram(
            "export function main(): null {" +
            "  let value: int | null = 1;" +
            "  if (value !== null) {" +
            "    try { throw { code: \"C_SET\", message: \"s\" }; }" +
            "    catch (err) { value = null; }" +
            "    let unsound: int = value;" +
            "  }" +
            "  return null;" +
            "}");
        assertErrorMessage(outerAssigned, "E3001", "Cannot assign int | null to int",
            "a catch-block assignment to the enclosing binding invalidates its refinement");

        // -- The resolver's own fact: the catch identifier is a distinct
        //    binding declared with the Error type.
        String source =
            "export function main(): null {" +
            "  let e: int | null = 1;" +
            "  if (e !== null) {" +
            "    try { throw { code: \"C\", message: \"c\" }; }" +
            "    catch (e) { let captured: Error = e; }" +
            "  }" +
            "  return null;" +
            "}";
        LexResult lex = new Lexer(source, "test.deal").tokenize();
        ParseResult parse = new Parser(lex.tokens(), "test.deal",
            lex.directiveEvents()).parse();
        NameResolver nr = new NameResolver("test.deal", new StubModuleResolver());
        SymbolTable symTable = nr.resolve(parse.program());
        FunctionDeclaration main = findStatement(parse.program().statements(), FunctionDeclaration.class);
        TryStatement resolvedTry = findStatement(parse.program().statements(), TryStatement.class);
        SymbolTable catchScope = resolvedTry == null ? null : nr.scopeMap().get(resolvedTry);
        Symbol catchSymbol = catchScope == null ? null : catchScope.resolveLocal("e");
        check(catchSymbol instanceof Symbol.VariableSymbol catchVar
                && Types.equals(errorType, catchVar.type()),
            "the catch identifier declares the Error type, got " + catchSymbol);
        SymbolTable bodyScope = main == null ? null : nr.scopeMap().get(main.body());
        Symbol outerSymbol = bodyScope == null ? null : bodyScope.resolve("e");
        check(outerSymbol != null && outerSymbol != catchSymbol,
            "the catch identifier is a distinct binding from the same-named outer binding");
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

    /**
     * symbols() must iterate in define() insertion order: the storage
     * field itself is a LinkedHashMap, so iteration is insertion order —
     * never a JDK hash-bucket order.  The key pair "b" (hash bucket 2
     * under HashMap's default 16-bucket table) then "aa" (bucket 0)
     * inverts under hash-bucket iteration ([aa, b]) and stays [b, aa]
     * under insertion iteration: a HashMap-backed field fails this
     * assertion, a LinkedHashMap-backed field passes.  The returned map
     * must also be a defensive copy.
     */
    static void testSymbolTableSymbolsInsertionOrder() {
        System.out.println("-- SymbolTable: symbols() insertion order and defensive copy --");
        SymbolTable table = new SymbolTable();
        table.define("b", new Symbol.VariableSymbol("b", Type.Int.INSTANCE, false));
        table.define("aa", new Symbol.VariableSymbol("aa", Type.Int.INSTANCE, false));

        List<String> names = new ArrayList<>();
        for (String key : table.symbols().keySet()) {
            names.add(key);
        }
        check(names.equals(List.of("b", "aa")),
            "symbols() iteration equals define() insertion order [b, aa], got " + names);

        List<String> entryOrder = new ArrayList<>();
        for (Map.Entry<String, Symbol> entry : table.symbols().entrySet()) {
            entryOrder.add(entry.getKey());
        }
        check(entryOrder.equals(List.of("b", "aa")),
            "symbols() entrySet iteration equals define() insertion order [b, aa], got "
                + entryOrder);

        Map<String, Symbol> copy = table.symbols();
        copy.put("zz", new Symbol.VariableSymbol("zz", Type.Int.INSTANCE, false));
        check(table.resolveLocal("zz") == null,
            "symbols() returns a defensive copy: a put on the returned map does not affect the table");
        copy.clear();
        check(table.resolveLocal("b") != null && table.resolveLocal("aa") != null,
            "symbols() returns a defensive copy: clearing the returned map does not affect the table");
    }

    /**
     * The first-match alias scan findImportAliasForClass performs
     * (symbols().entrySet() iteration; exports().get(name) instanceof
     * Type.Class with canonical class-identity equality) must select the
     * earliest-defined alias when two module aliases export a same-named
     * class with the same module path.  Reversed definition order must
     * reverse the winner: a HashMap-backed storage field iterates
     * hash-bucket order ("a" lands in bucket 1, "b" in bucket 2 under
     * the default 16-bucket table), so the reversed table would still
     * yield "a" and this assertion fails — the insertion-ordered field
     * is the definition-order guarantee.
     */
    static void testSymbolTableAliasSelectionDefinitionOrder() {
        System.out.println("-- SymbolTable: alias selection follows definition order --");
        Type.Class cType = IdentityTestFixtures.classType("C", "m");
        Map<String, Type> exports = Map.of("C", cType);

        SymbolTable forward = new SymbolTable();
        forward.define("a", new Symbol.ModuleSymbol("a", exports, null));
        forward.define("b", new Symbol.ModuleSymbol("b", exports, null));
        check("a".equals(findImportAliasForClassScan(forward, "C", cType.identity())),
            "first-match scan picks the earliest-defined alias (a)");

        SymbolTable reversed = new SymbolTable();
        reversed.define("b", new Symbol.ModuleSymbol("b", exports, null));
        reversed.define("a", new Symbol.ModuleSymbol("a", exports, null));
        check("b".equals(findImportAliasForClassScan(reversed, "C", cType.identity())),
            "first-match scan follows definition order: reversed definition picks b");
    }

    /**
     * The exact scan LuaBackend.findImportAliasForClass performs over the
     * returned symbols() copy: entrySet() iteration, exports().get(name)
     * instanceof Type.Class, canonical class-identity equality — first
     * match wins.
     */
    private static String findImportAliasForClassScan(SymbolTable table,
            String className, CanonicalClassIdentity identity) {
        for (Map.Entry<String, Symbol> entry : table.symbols().entrySet()) {
            Symbol sym = entry.getValue();
            if (sym instanceof Symbol.ModuleSymbol ms) {
                Type exportType = ms.exports().get(className);
                if (exportType instanceof Type.Class tc
                        && tc.identity().equals(identity)) {
                    return entry.getKey();
                }
            }
        }
        return null;
    }

    // D1 canonical report-time diagnostic ordering (deterministic-diagnostics)
    // =========================================================================

    /**
     * Builds one D9-normalized test diagnostic over a valid SOURCE range
     * with full control of the D1 key fields. Notes are empty.
     */
    private static CompilerDiagnostic orderDiag(String file, int line,
            int column, int scalarOffset, String code, String message,
            String severity) {
        return new CompilerDiagnostic(code, severity, message,
            new DiagnosticRange(file, line, column, line, column + 1,
                scalarOffset, scalarOffset + 1, 1, RangeOrigin.SOURCE),
            null, null);
    }

    /** The file field of one E5003 main line (the text before the first ':'). */
    private static String fileFieldOf(String line) {
        int colon = line.indexOf(':');
        return colon < 0 ? "" : line.substring(0, colon);
    }

    /**
     * D1 helper-order assertions over a constructed diagnostic list whose
     * input order differs from D1 order: file, scalar-offset, line/column,
     * code, message, and severity ordering; stability for equal keys;
     * input-list non-mutation; null yields the empty list.
     */
    static void testDiagnosticOrderHelper() {
        System.out.println("-- D1 helper: canonical diagnostic ordering --");

        // File ordering: file path byte order (String.compareTo).
        CompilerDiagnostic fileB = orderDiag(
            "b.deal", 1, 1, 0, "E0001", "m", "error");
        CompilerDiagnostic fileA = orderDiag(
            "a.deal", 1, 1, 0, "E0001", "m", "error");
        List<CompilerDiagnostic> byFile = DiagnosticOrder.canonical(
            List.of(fileB, fileA));
        check(byFile.size() == 2 && byFile.get(0) == fileA
                && byFile.get(1) == fileB,
            "D1 helper: file ordering (a.deal before b.deal)");

        // Scalar-offset ordering: ascending startScalarOffset.
        CompilerDiagnostic offHigh = orderDiag(
            "f.deal", 1, 1, 20, "E0001", "m", "error");
        CompilerDiagnostic offLow = orderDiag(
            "f.deal", 1, 1, 5, "E0001", "m", "error");
        List<CompilerDiagnostic> byOffset = DiagnosticOrder.canonical(
            List.of(offHigh, offLow));
        check(byOffset.size() == 2 && byOffset.get(0) == offLow
                && byOffset.get(1) == offHigh,
            "D1 helper: scalar-offset ordering (5 before 20)");

        // Line ordering: after file and scalar offset.
        CompilerDiagnostic lineHigh = orderDiag(
            "f.deal", 3, 1, 7, "E0001", "m", "error");
        CompilerDiagnostic lineLow = orderDiag(
            "f.deal", 1, 1, 7, "E0001", "m", "error");
        List<CompilerDiagnostic> byLine = DiagnosticOrder.canonical(
            List.of(lineHigh, lineLow));
        check(byLine.size() == 2 && byLine.get(0) == lineLow
                && byLine.get(1) == lineHigh,
            "D1 helper: line ordering (1 before 3)");

        // Column ordering: after file, scalar offset, and line.
        CompilerDiagnostic colHigh = orderDiag(
            "f.deal", 1, 5, 7, "E0001", "m", "error");
        CompilerDiagnostic colLow = orderDiag(
            "f.deal", 1, 2, 7, "E0001", "m", "error");
        List<CompilerDiagnostic> byColumn = DiagnosticOrder.canonical(
            List.of(colHigh, colLow));
        check(byColumn.size() == 2 && byColumn.get(0) == colLow
                && byColumn.get(1) == colHigh,
            "D1 helper: column ordering (2 before 5)");

        // Code ordering.
        CompilerDiagnostic codeHigh = orderDiag(
            "f.deal", 1, 1, 7, "E2000", "m", "error");
        CompilerDiagnostic codeLow = orderDiag(
            "f.deal", 1, 1, 7, "E1000", "m", "error");
        List<CompilerDiagnostic> byCode = DiagnosticOrder.canonical(
            List.of(codeHigh, codeLow));
        check(byCode.size() == 2 && byCode.get(0) == codeLow
                && byCode.get(1) == codeHigh,
            "D1 helper: code ordering (E1000 before E2000)");

        // Message ordering.
        CompilerDiagnostic msgHigh = orderDiag(
            "f.deal", 1, 1, 7, "E1000", "zebra", "error");
        CompilerDiagnostic msgLow = orderDiag(
            "f.deal", 1, 1, 7, "E1000", "apple", "error");
        List<CompilerDiagnostic> byMessage = DiagnosticOrder.canonical(
            List.of(msgHigh, msgLow));
        check(byMessage.size() == 2 && byMessage.get(0) == msgLow
                && byMessage.get(1) == msgHigh,
            "D1 helper: message ordering (apple before zebra)");

        // Severity ordering: "error" before "warning".
        CompilerDiagnostic sevWarning = orderDiag(
            "f.deal", 1, 1, 7, "E1000", "apple", "warning");
        CompilerDiagnostic sevError = orderDiag(
            "f.deal", 1, 1, 7, "E1000", "apple", "error");
        List<CompilerDiagnostic> bySeverity = DiagnosticOrder.canonical(
            List.of(sevWarning, sevError));
        check(bySeverity.size() == 2 && bySeverity.get(0) == sevError
                && bySeverity.get(1) == sevWarning,
            "D1 helper: severity ordering (error before warning)");

        // Stability: equal keys keep their input relative order (the
        // distinguishing note does not participate in the D1 key).
        CompilerDiagnostic first = new CompilerDiagnostic(
            "E1000", "error", "apple",
            new DiagnosticRange("f.deal", 1, 1, 1, 2, 7, 8, 1,
                RangeOrigin.SOURCE),
            List.of(new DiagnosticNote("first note", null)), null);
        CompilerDiagnostic second = new CompilerDiagnostic(
            "E1000", "error", "apple",
            new DiagnosticRange("f.deal", 1, 1, 1, 2, 7, 8, 1,
                RangeOrigin.SOURCE),
            List.of(new DiagnosticNote("second note", null)), null);
        List<CompilerDiagnostic> stable = DiagnosticOrder.canonical(
            List.of(first, second));
        check(stable.size() == 2 && stable.get(0) == first
                && stable.get(1) == second,
            "D1 helper: stability for equal keys keeps input order");

        // Input-list non-mutation and the fresh-list contract.
        List<CompilerDiagnostic> input = new ArrayList<>(
            List.of(fileB, fileA, offHigh, offLow));
        List<CompilerDiagnostic> snapshot = new ArrayList<>(input);
        List<CompilerDiagnostic> result = DiagnosticOrder.canonical(input);
        check(result != input,
            "D1 helper: canonical returns a new list, never the input");
        check(input.equals(snapshot),
            "D1 helper: the input list is not mutated");
        check(input.get(0) == fileB && input.get(1) == fileA,
            "D1 helper: the input list element order is unchanged");

        // Null input yields the empty list.
        check(DiagnosticOrder.canonical(null).isEmpty(),
            "D1 helper: null input yields the empty list");
    }

    /**
     * D1 both-surfaces assertion: a scratch temp project whose phase-3
     * checker diagnostics are collected dependency-first ([z, a]) while
     * the D1 file order is [a, z], so the assertion genuinely fails when
     * DiagnosticOrder.canonical is unwired from either report site.
     */
    static void testDiagnosticOrderBothSurfaces() {
        System.out.println("-- D1 both surfaces: stderr and "
            + "--diagnostics-json emit the canonical order --");
        Path root;
        try {
            root = Files.createTempDirectory("deal_d1_order_");
        } catch (IOException e) {
            fail("D1 both surfaces: cannot create temp directory: "
                + e.getMessage());
            return;
        }
        try {
            Files.writeString(root.resolve("deal.json"),
                "{\"languageVersion\":\"1.3\",\"backend\":\"luajit\","
                    + "\"moduleRoots\":[\"src\"]}\n");
            Files.createDirectories(root.resolve("src"));
            // z is a's import dependency: buildCheckOrder is
            // dependency-first, so the E5003 collection order is
            // [z, a] while the D1 file order is [a, z].
            Files.writeString(root.resolve("src/z.deal"),
                "export function f(): int { return 1.0; }\n");
            Files.writeString(root.resolve("src/a.deal"),
                "import * as z from \"./z\";\n"
                    + "export function main(): null { return null; }\n"
                    + "function g(): int { return 1.0; }\n");
            Path entry = root.resolve("src/a.deal").toAbsolutePath();
            Path jsonPath = root.resolve("diagnostics.json");

            ByteArrayOutputStream err = new ByteArrayOutputStream();
            PrintStream originalErr = System.err;
            int exitCode;
            try {
                System.setErr(new PrintStream(err, true,
                    StandardCharsets.UTF_8));
                exitCode = Main.run(new String[]{
                    "compile", entry.toString(),
                    "--diagnostics-json", jsonPath.toString()});
                System.err.flush();
            } finally {
                System.setErr(originalErr);
            }
            String stderr = err.toString(StandardCharsets.UTF_8);

            check(exitCode == 1, "D1 both surfaces: compile exits 1");
            List<String> e5003Lines = stderr.lines()
                .filter(l -> l.contains(": ERROR E5003: "))
                .toList();
            check(e5003Lines.size() == 2,
                "D1 both surfaces: exactly two E5003 main lines, got "
                    + e5003Lines.size());
            if (e5003Lines.size() == 2) {
                int aIndex = -1;
                int zIndex = -1;
                for (int i = 0; i < e5003Lines.size(); i++) {
                    String file = fileFieldOf(e5003Lines.get(i));
                    if (file.endsWith("src/a.deal")) aIndex = i;
                    if (file.endsWith("src/z.deal")) zIndex = i;
                }
                check(aIndex == 0 && zIndex == 1,
                    "D1 both surfaces: stderr E5003 file order is "
                        + "src/a.deal then src/z.deal (aIndex=" + aIndex
                        + ", zIndex=" + zIndex + ")");
            }
            check(stderr.lines().anyMatch(
                    l -> l.equals("2 error(s), 0 warning(s)")),
                "D1 both surfaces: summary line is "
                    + "'2 error(s), 0 warning(s)'");

            String json = Files.readString(jsonPath);
            check(json.contains("\"version\": 1"),
                "D1 both surfaces: JSON document stays version 1");
            int arrayStart = json.indexOf("\"diagnostics\": [");
            check(arrayStart >= 0,
                "D1 both surfaces: JSON document has the diagnostics array");
            int aFile = json.indexOf("src/a.deal", arrayStart);
            int zFile = json.indexOf("src/z.deal", arrayStart);
            check(aFile >= 0 && zFile >= 0 && aFile < zFile,
                "D1 both surfaces: JSON diagnostics array lists src/a.deal "
                    + "before src/z.deal");
        } catch (IOException e) {
            fail("D1 both surfaces: I/O failure: " + e.getMessage());
        } finally {
            deleteRecursively(root);
        }
    }

    /** Deletes a scratch temp tree (best effort, recursion-safe). */
    private static void deleteRecursively(Path dir) {
        try {
            if (dir == null || !Files.exists(dir)) return;
            Files.walk(dir).sorted(Comparator.reverseOrder())
                .forEach(f -> {
                    try {
                        Files.deleteIfExists(f);
                    } catch (Exception ignored) {
                    }
                });
        } catch (Exception ignored) {
        }
    }
}
