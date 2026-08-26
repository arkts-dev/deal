package deal.test;

import deal.ast.ProgramNode;
import deal.checker.CheckResult;
import deal.checker.ModuleResolver;
import deal.checker.NameResolver;
import deal.checker.Symbol;
import deal.checker.SymbolTable;
import deal.checker.TypeChecker;
import deal.diagnostics.CompilerDiagnostic;
import deal.diagnostics.DiagnosticFormatter;
import deal.diagnostics.DiagnosticRange;
import deal.diagnostics.RangeOrigin;
import deal.types.Type;
import deal.types.Types;
import deal.codegen.Backend;
import deal.codegen.jvm.JvmBackend;
import deal.lexer.Diagnostic;
import deal.lexer.LexResult;
import deal.lexer.Lexer;
import deal.module.ModuleShapeValidator;
import deal.module.CompilationOrchestrator;
import deal.module.DealConfig;
import deal.module.DealConfig.DealConfigParseResult;
import deal.parser.ParseResult;
import deal.parser.Parser;
import deal.source.ScalarSourceCursor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Unit tests for the JVM backend skeleton (ISSUE-0091), the first
 * semantic slice (ISSUE-0092 — while loops and template literals), the
 * functions/direct-calls slice (ISSUE-0093 — parameter shadowing
 * against module fields and other visible bindings), and the
 * primitive-array slice (ISSUE-0094 — {@code int[]}/{@code number[]}/
 * {@code string[]}/{@code boolean[]} literals, index reads, element
 * writes, and {@code .length} reads with the spec's runtime checks), the
 * modules/imports/exports slice (ISSUE-0096 — multi-module
 * compilation, namespace imports, and imported direct calls), and the
 * stdlib-boundary slice (ISSUE-0097 — the stdlib modules whose declared
 * functions use only the supported value types: {@code std/console},
 * {@code std/string}, {@code std/math}, {@code std/time}):
 * <ul>
 *   <li>parameter shadowing (ISSUE-0093): a parameter shadowing a
 *       module field emits ONE disambiguated Java name in both the
 *       method signature and the body, a {@code let} shadowing a
 *       disambiguated parameter never reuses the parameter's emitted
 *       name (its initializer's enclosing read binds to the parameter),
 *       double-nested local shadowing reads the nearest enclosing
 *       binding, and the triple-deep shadow chain (field → parameter →
 *       body-top let → inner-block let, with and without the module
 *       field) emits one distinct Java name per binding — never
 *       re-using a shadowed parameter's still-in-Java-scope name, which
 *       javac would reject after the CLI reported success — all compiled
 *       and executed with {@code javac} + {@code java},</li>
 *   <li>while loops: counting/nested/shadowed loops, {@code while (false)}
 *       bodies that never run, per-iteration re-evaluation of conditions
 *       with hoisted null-typed side effects, function-body module-field
 *       dominance guards through loop bodies (a write inside a loop body
 *       dominates nothing after the loop), module-level returns inside
 *       while bodies, use-before-declaration in while conditions, and the
 *       emitted {@code loopCond} identity helper (constant-expression
 *       conditions are never visible to javac; a DEAL function colliding
 *       with the helper signature is E6000),</li>
 *   <li>template literals lowered to string concatenation (interpolated
 *       and plain parts, empty parts elided, single-part templates),</li>
 *   <li>identifier translation and collision-safe class-name derivation,</li>
 *   <li>Java emission for the supported skeleton surface
 *       (literals, arithmetic, locals, if/else, console output, intrinsics),</li>
 *   <li>local classes and nominal checks (ISSUE-0095): module-level
 *       {@code class} declarations emit a generated nested static class
 *       ({@code $C_<name>} extending the {@code $Base} identity holder),
 *       object-literal construction applies defaults per construction with
 *       provided fields evaluated left-to-right, primitive field
 *       reads/writes emit direct accesses, tables emit a minimal ordered
 *       string-key map (the shared {@code $DealRt.Table} class),
 *       and a class-typed table read — the
 *       slice's one untyped boundary — runs the emitted {@code $check<C>}
 *       nominal check (E8001 for wrong-class and non-class values),</li>
 *   <li>imported classes and cross-module nominal identity (ISSUE-0109):
 *       {@code export class} emits the same generated nested class as a
 *       local one (the export only adds the class to the module type),
 *       imported-class values map to the DECLARING module's generated
 *       class ({@code Lib.$C_C}), imported construction
 *       ({@code new Lib.$C_Point(...)} with literal defaults applied
 *       inline and provided fields reordered to declaration order),
 *       class-typed table reads run the declaring module's nominal check
 *       helper (success for a genuine instance; E8001 naming both
 *       module-qualified identities for a same-name sibling from another
 *       module), and a same-named local class never satisfies the
 *       imported-class guard (locality is decided from the Type.Class
 *       module path),</li>
 *   <li>the nullable slice (ISSUE-0108): {@code T | null} for the four
 *       primitives and local classes across locals, module fields, class
 *       fields, parameters, and returns (boxed reference representation,
 *       narrowed reads unbox through the null-check branches),
 *       {@code T[] | null} and {@code (T | null)[]} for the primitive
 *       and local-class element types, class arrays {@code C[]}, the
 *       table-read nullable boundary checks with runtime E8001 failures,
 *       and the {@code int()}/{@code number()} nullable conversion
 *       overloads — all compiled and executed with {@code javac} +
 *       {@code java},</li>
 *   <li>the @jsonable slice (JSON serialization): the generated
 *       {@code C$fromJson}/{@code C$toJson} module exports (translated
 *       with {@code javaName} — {@code User$dfromJson} — exactly what
 *       the same-module and imported call sites emit), the per-class
 *       {@code $jsonFields} descriptor table, fromJson validation
 *       (extra keys, malformed JSON, type mismatches → the DEAL null,
 *       never a throw — including hostile deep-nesting input, where
 *       the emitted parser converts its StackOverflowError to the DEAL
 *       null and the table-value conversion carries a bounded depth
 *       guard), toJson serialization with std/json.lua's
 *       E8001 NaN/Infinity rejection, defaults applied inline,
 *       required no-default fields (the reference defaults table:
 *       the primitive zeroes, a fresh empty table/array per call —
 *       never Java null — and a required class-typed field's absent
 *       key as a fromJson validation failure), nullable/array/
 *       nested-class/optional/table-typed fields
 *       (the three optional-nullable states through the Missing
 *       sentinel with {@code has()} presence, JSON-shaped table data
 *       including array-mode tables), and the slice boundaries
 *       (optional/table-typed fields on NON-jsonable classes,
 *       optional table fields of @jsonable classes — their reads
 *       yield {@code table | null} — and module-level helper calls —
 *       E6000/E1049), runtime cases
 *       compiled and executed with {@code javac} + {@code java},</li>
 *   <li>E6000 rejection of out-of-scope constructs (optional/
 *       array/class/table-typed (non-nullable) class fields, nested class
 *       declarations, table reads with primitive (non-nullable) targets,
 *       table field writes, nested/function arrays, {@code table | null}
 *       values, {@code (table | null)[]} elements, for/for-of
 *       loops, async, non-project imports — at the import statement
 *       itself, even when unused —, module-level returns,
 *       use-before-declaration, runtime-helper name collisions),</li>
 *   <li>the stdlib boundary (ISSUE-0097): {@code std/string}/
 *       {@code std/math}/{@code std/time} imports compile and execute
 *       their declared functions through the emitted {@code __str*}/
 *       {@code __mathSqrt} helpers and fully-qualified
 *       {@code java.lang.Math}/{@code java.lang.String} expressions —
 *       Unicode scalar-value {@code length}/{@code substring}/
 *       {@code split} (including the {@code "héllo"} scalar-count and
 *       scalar-position pins — spec-v1.2 measures string lengths and
 *       positions in Unicode scalar values), plain-text search/replace
 *       with the empty-{@code old} guard, the Lua-whitespace
 *       {@code trim}, the negative-input {@code sqrt} E8001, the
 *       extreme-negative {@code absInt}, and the second-truncated
 *       {@code nowMillis()} — while {@code std/table}
 *       and {@code std/json} imports (used and unused) stay E6000 at the
 *       import statement because their functions require {@code table}
 *       values, all compiled and executed with {@code javac} +
 *       {@code java} subprocesses,</li>
 *   <li>observable-behavior preservation for null-typed side effects
 *       (null-typed returns/initializers/assignments/arguments — including
 *       captures of locals and parameters reassigned later in their scope,
 *       which a lambda lowering would make javac reject), module-level
 *       load-time statements, standalone expression statements, null
 *       equality, floored {@code %} on negative operands, string
 *       equality/ordering, and {@code console.error} → stderr — verified by
 *       executing the emitted artifact with {@code javac} + {@code java}
 *       subprocesses,</li>
 *   <li>left-to-right evaluation-order preservation for hoisted null-typed
 *       side effects: an earlier inline side-effecting (or raising)
 *       operand of the same statement is materialized into a temporary
 *       before the hoisted pre-statement, in every combination position —
 *       never an inverted output order and never a lambda,</li>
 *   <li>dead-code skipping after non-completing statements (a complete
 *       all-returning if/else, return-after-return, block-level dead
 *       lets/expression statements, dead else-if chains with hoisted
 *       null-typed conditions — never an artifact javac rejects as
 *       unreachable), function-body reads of later-declared module fields
 *       governed by a write-dominance analysis (write-then-read allowed;
 *       the no-prior-write shape — LuaJIT fails at call time reading the
 *       global nil — rejected with E6000; the load-time guard stays), and
 *       fixture-schema validation ({@code expectedCompileError} combined
 *       with runtime/IR assertions fails per conformance-test-architecture
 *       D6),</li>
 *   <li>DEAL runtime error codes (E8004/E8005/E8006/E8001 incl. the
 *       negative-exponent and extreme-power paths) surfaced by executing the
 *       emitted artifact,</li>
 *   <li>the DEAL int safe range ±(2^53-1) = ±9007199254740991: boundary
 *       success and E8004 pins for every int-producing operation
 *       (add/sub/mul/neg/{@code **}/int() and out-of-range literals as
 *       operands or at the return), with emission assertions that the
 *       helpers route through the emitted {@code checkInt} bound — never
 *       Java's long bound,</li>
 *   <li>fully-qualified {@code java.lang} references: locals, parameters,
 *       and module fields named {@code System}/{@code Math}/{@code Double}/
 *       {@code String}/{@code Void}/{@code Integer}/{@code Character}/
 *       {@code RuntimeException}/{@code ArithmeticException} coexist with
 *       console output, int arithmetic, number {@code **}, non-finite
 *       literals, string ordering, and runtime errors — all compiled and
 *       executed, with qualified-name emission assertions,</li>
 *   <li>use-before-declaration walking of every {@code if}/{@code else if}
 *       chain condition: module-level, function-body, and deep-chain
 *       forward references are E6000 (never an artifact javac rejects after
 *       the CLI reported success), while chains over already-declared
 *       variables stay clean,</li>
 *   <li>primitive arrays (ISSUE-0094): the emitted {@code __IntArray}/
 *       {@code __NumberArray}/{@code __StringArray}/{@code __BooleanArray}
 *       wrapper classes and read/write helpers (bounds checks: negative
 *       read/write and gap write → E8002, read past the end → E8001
 *       "expected &lt;T&gt;, got null"; element value checks: int elements
 *       route through {@code checkInt} → E8004; the write check runs after
 *       the receiver/index/RHS expressions evaluate, per spec-v1.2
 *       §Operational semantics rule 3), appends at {@code i == length} with
 *       the wrapper identity stable across growth (alias parity),
 *       evaluation order with hoisted null-typed side effects materialized
 *       into temporaries, arrays through function parameters/returns and
 *       module fields, E6000 rejection of nested arrays and
 *       function arrays, and
 *       use-before-declaration guards walking index/array-literal/length
 *       positions — all compiled and executed with {@code javac} +
 *       {@code java},</li>
 *   <li>the backend-selection seam: {@code CompilationOrchestrator} with
 *       {@code Backend.JVM} emits and compiles a real {@code .java} artifact,
 *       rejects out-of-scope projects with E6000, detects class-name
 *       collisions, the default stays LuaJIT, {@code DealConfig} and the CLI
 *       accept {@code jvm},</li>
 *   <li>the modules slice (ISSUE-0096): multi-module orchestrator compiles
 *       emit one artifact class per module with the import resolution map
 *       ({@code testModuleImports}), same-basename modules in different
 *       directories derive isolated class names ({@code
 *       testModuleClassIsolation}), sibling imports initialize
 *       depth-first in import order ({@code testModuleUnusedImportLoadTime}),
 *       backend-level emission pins the load-time {@code __init$} trigger
 *       and the static cross-class call ({@code
 *       testModuleImportBackendEmission}), an unused project-module import
 *       still runs the imported module's load-time code ({@code
 *       testOrchestratorJvmImportSupported}), and a declaration/host-module
 *       import stays E6000 with no artifact ({@code
 *       testOrchestratorJvmDeclarationImportRejected}).</li>
 *   <li>function values and wrappers (ISSUE-0098): per-signature wrapper
 *       classes with spec-convention descriptor strings, per-declaration
 *       wrapper instance fields, indirect-call dispatch through
 *       {@code invoke}, intrinsic function-value wrappers, arity-extension
 *       adapters at variable/assignment positions (static-method
 *       delegation for module functions and effectively-final snapshot
 *       temporaries for locals/parameters the enclosing body never
 *       reassigns; reassigned locals/parameters and non-identifier
 *       adapter values are E6000 until ISSUE-0110 — never a lambda,
 *       never a silent divergence), E8010 runtime signature checks at
 *       callback/return boundaries with LuaJIT's exact message and the
 *       checked value expression evaluated FIRST
 *       (evaluate-then-check — side effects never dropped; a check
 *       operand's VALUE lowers to an actual-shape temporary while the
 *       raising construction stays INLINE at the argument position, so
 *       two arity-mismatched function-valued arguments in one call
 *       compile to valid Java and raise the FIRST parameter's E8010 in
 *       parameter order, pinned in {@code testFunctionValues} and the
 *       {@code jvm-fv-cross-e8010-two-mismatched-args} conformance
 *       fixture), wrapper
 *       reference equality, call-result-callee evaluation order (the
 *       callee materialized into a single-assignment temporary at its
 *       evaluation position, before any argument's hoisted
 *       pre-statements), the deferred signature shapes
 *       (nested/nullable function types, arrays of functions, and
 *       function equality/inequality over array-/class-/nullable-
 *       parameter signatures) rejected with E6000, and cross-module
 *       function values (a Func-typed argument to an imported module
 *       call — including the arity-extension/E8010-boundary shape — and
 *       an imported call result with Func static type) rejected with
 *       E6000 and no entry artifact by the orchestrator
 *       ({@code testCrossModuleFunctionValuesRejected} — the per-module
 *       wrapper classes cannot cross a module boundary, so the pre-fix
 *       emissions were artifacts javac rejected after the CLI reported
 *       success). Every v1.1 module-field/load-time shape — the LIVE
 *       static-field-delegation adapter, the load-time indirect-call
 *       guards, the module-level call-result callee hazards, and the
 *       rest function-type arm — is a v1.2 frontend grammar gate
 *       (E1049/E1047) pinned in {@code testFunctionValues}: the v1.2
 *       module top level holds only declarations, so those shapes no
 *       longer reach a backend.</li>
 * </ul>
 *
 * <p>The end-to-end JVM conformance fixtures live in
 * {@code test/conformance/fixtures/jvm-skeleton.json} and run under
 * {@link BackendConformanceTest}; this class covers the seams and edge
 * behavior at unit level.
 */
public class JvmBackendTest {

    private static int passed = 0;
    private static int failed = 0;
    private static Path tmpDir;

    public static void main(String[] args) throws Exception {
        tmpDir = Files.createTempDirectory("jvm_backend_test_");
        try {
            testBackendNames();
            testIdentifierTranslation();
            testClassNameDerivation();
            testEmissionSmoke();
            testUnsupportedConstructsRejected();
            testWhileLoops();
            testWhileFalseBodySkipped();
            testWhileHoistedConditionPerIteration();
            testWhileContinueTargetsWhileInsideTransformedFor();
            testWhileLoopModuleFieldDominanceGuards();
            testWhileModuleLevelReturnRejected();
            testWhileUseBeforeDeclarationRejected();
            testLoopCondHelperCollision();
            testTemplateLiterals();
            testPrimitiveArrays();
            testArrayRuntimeErrorCodes();
            testArrayEvaluationOrderHoisted();
            testArrayReadComparisonNilSemantics();
            testArrayEvalOrderSideEffectingReceiver();
            testArrayReadComparisonBothReadsOrder();
            testArrayReadComparisonPlainLeftOperandOrder();
            testArrayBoundaryLessReadPositions();
            testArrayUnsupportedElementTypesRejected();
            testArrayUseBeforeDeclarationGuards();
            testNullReturnSideEffects();
            testNullTypedInitializers();
            testNullTypedCapturesWithReassignment();
            testNullEquality();
            testStandaloneExpressionStatements();
            testNumberModStringAndStderrRuntime();
            testModuleLevelStatements();
            testShadowedInitializer();
            testParameterShadowing();
            testClassSlice();
            testTypeDescriptorEmitter();
            testSharedCheckSeam();
            testNullableSlice();
            testAsyncSlice();
            testJsonableSlice();
            testImportedClassValues();
            testFunctionValues();
            testCrossModuleFunctionValuesRejected();
            testUseBeforeDeclarationRejected();
            testFunctionBodyModuleFieldAccessGuards();
            testAssignmentBeforeDeclarationRejected();
            testDeadCodeAfterNonCompletingStatements();
            testRuntimeErrorCodes();
            testIntSafeRange();
            testJavaLangNameCollisions();
            testElseIfChainUseBeforeDeclaration();
            testNonFiniteNumberLiterals();
            testShortCircuitPreservation();
            testEvaluationOrderPreservation();
            testStringScalarOrdering();
            testModuleLevelCallReadingLaterField();
            testModuleLevelCallBeforeFunctionDeclarationRejected();
            testRunnerModuleErrorCodeWithExport();
            testOrchestratorJvmBackend();
            testOrchestratorDefaultStaysLua();
            testOrchestratorJvmRejectsUnsupported();
            testOrchestratorJvmImportSupported();
            testStdlibCallEmission();
            testStdlibTableBoundaryRejected();
            testStdlibExecution();
            testStdlibSqrtNegativeRuntimeError();
            testStdlibScalarSemantics();
            // ISSUE-0106 v1.2 slice: entry-module invocation, Unicode
            // scalar-value string for-of, and boundary string validation.
            testEntryModuleEmitsJvmEntryPoint();
            testEntryGateBackendE6004();
            testStringForOfScalarIteration();
            testArrayForOfRefElementShapes();
            testCatchVarCapturedByNestedFunction();
            testBoundaryStringValidation();
            testStdlibTimeNowMillis();
            testStdlibHelperNameCollisions();
            testOrchestratorJvmStdlibImport();
            testOrchestratorJvmDeclarationImportRejected();
            testHostAbiSlice();
            testOrchestratorJvmClassCollision();
            testModuleImports();
            testModuleClassIsolation();
            testModuleUnusedImportLoadTime();
            testModuleImportBackendEmission();
            testModuleImportUseBeforeImportRejected();
            testOrchestratorJvmSourceMapWarning();
            testDealConfigBackendField();
            testCliBackendFlag();
            testFixtureConfigValidation();
        } finally {
            cleanup();
        }

        System.out.println();
        System.out.println("=== JVM Backend Test Summary ===");
        System.out.println("Passed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

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

    private static Path writeFile(String relativePath, String content) throws IOException {
        Path file = tmpDir.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
    }

    private static void cleanup() {
        try {
            Files.walk(tmpDir).sorted(Comparator.reverseOrder())
                .forEach(f -> { try { Files.deleteIfExists(f); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}
    }

    /** Result of the real frontend pipeline (lexer → parser → resolver → checker). */
    private record Frontend(ProgramNode program, CheckResult checkResult,
                            List<Diagnostic> errors) {}

    // E9999 is the project's test-only pseudo code for a NameResolver
    // exception (the ConformanceTest precedent); the String-code overload is
    // deprecated, and this suppression keeps the build warning-free.
    @SuppressWarnings("deprecation")
    private static Frontend compileFrontend(String source, String filename) {
        return compileFrontend(source, filename,
            new BackendConformanceTest.StubModuleResolver());
    }

    /** Frontend compile with an explicit module resolver (ISSUE-0096 module
     * probes: a fixed export map accepts namespace imports without an
     * orchestrator). */
    @SuppressWarnings("deprecation")
    private static Frontend compileFrontend(String source, String filename,
                                            ModuleResolver moduleResolver) {
        List<Diagnostic> errors = new ArrayList<>();

        LexResult lex = new Lexer(source, filename).tokenize();
        // Transitional ranged-to-legacy boundary conversion (T3 scaffolding,
        // removed in T13): the severity filter is unchanged; each retained
        // entry converts into the still-legacy errors list with start values
        // derived from the range start (position-preserving).
        for (CompilerDiagnostic d : lex.diagnostics()) {
            if ("error".equals(d.severity())) {
                errors.add(new Diagnostic(d.code(), d.severity(), d.message(),
                    d.file(), d.line(), d.column(), d.diagnosticCode()));
            }
        }
        if (lex.hasErrors()) {
            return new Frontend(null, null, errors);
        }

        Parser parser = new Parser(lex.tokens(), filename);
        ParseResult parse = parser.parse();
        // Transitional ranged-to-legacy boundary conversion (T4
        // scaffolding, removed in T13): the severity filter is unchanged;
        // each retained entry converts into the still-legacy errors list
        // with start values derived from the range start
        // (position-preserving).
        for (CompilerDiagnostic d : parse.diagnostics()) {
            if ("error".equals(d.severity())) {
                errors.add(new Diagnostic(d.code(), d.severity(), d.message(),
                    d.file(), d.line(), d.column(), d.diagnosticCode()));
            }
        }
        if (parse.hasErrors()) {
            return new Frontend(null, null, errors);
        }

        // Post-parse module shape validation (v1.2 module top level:
        // E1048/E1049/E1050/E1051), mirroring the orchestrator pipeline —
        // the parser alone no longer rejects v1.1 module shapes.
        // Transitional ranged-to-legacy boundary conversion (T9
        // scaffolding, removed in T13): the severity filter is unchanged;
        // each retained entry converts into the still-legacy errors list
        // with start values derived from the range start
        // (position-preserving).
        for (CompilerDiagnostic d : ModuleShapeValidator.validate(parse.program(),
                filename, filename.endsWith(".d.deal"))) {
            if ("error".equals(d.severity())) {
                errors.add(new Diagnostic(d.code(), d.severity(), d.message(),
                    d.file(), d.line(), d.column(), d.diagnosticCode()));
            }
        }
        if (!errors.isEmpty()) {
            return new Frontend(null, null, errors);
        }

        NameResolver nr = new NameResolver(filename, moduleResolver);
        SymbolTable symTable;
        try {
            symTable = nr.resolve(parse.program());
        } catch (Exception e) {
            errors.add(Diagnostic.error("E9999", e.getMessage(), filename, 1, 1));
            return new Frontend(null, null, errors);
        }
        // Transitional ranged-to-legacy boundary conversions (T9
        // scaffolding, removed in T13): the severity filters are
        // unchanged; each retained entry converts into the still-legacy
        // errors list with start values derived from the range start
        // (position-preserving).
        for (CompilerDiagnostic d : nr.diagnostics()) {
            if ("error".equals(d.severity())) {
                errors.add(new Diagnostic(d.code(), d.severity(), d.message(),
                    d.file(), d.line(), d.column(), d.diagnosticCode()));
            }
        }

        CheckResult result = TypeChecker.check(filename, symTable, nr, parse.program());
        for (CompilerDiagnostic d : result.diagnostics()) {
            if ("error".equals(d.severity())) {
                errors.add(new Diagnostic(d.code(), d.severity(), d.message(),
                    d.file(), d.line(), d.column(), d.diagnosticCode()));
            }
        }

        return new Frontend(parse.program(), result, errors);
    }

    private record ExecResult(String output, int exitCode) {}

    /**
     * Compiles the generated artifact with the javac frontend in-process
     * ({@code BackendConformanceTest.compileWithJavac} — the identical
     * javac passes the binary runs; ISSUE-0109 gate-time work, the
     * several hundred spawn sites here each paid a full JVM boot) and
     * executes it with {@code java} in a subprocess — artifact execution
     * stays the same contract the conformance adapter enforces.
     */
    private static ExecResult compileAndRunJvm(String source, String name)
            throws Exception {
        Frontend f = compileFrontend(source, "jvmtest-" + name + ".deal");
        if (!f.errors().isEmpty()) {
            throw new RuntimeException("frontend errors: " + f.errors());
        }
        // The sourcePath must equal the checker's module path: the
        // checker types with the source FILENAME ("jvmtest-<name>.deal"),
        // and the backend's locality predicate (ISSUE-0095) recognizes a
        // local Type.Class by its module path matching the backend-held
        // module path or source path — the same alignment the
        // BackendConformanceTest adapter uses.
        JvmBackend.JvmCodegenResult res = JvmBackend.generate(
            f.program(), f.checkResult(), "jvmtest-" + name + ".deal", "Main");
        if (res.hasErrors()) {
            throw new RuntimeException("codegen errors: " + res.diagnostics());
        }

        Path dir = Files.createTempDirectory("jvmtest_run_");
        Files.writeString(dir.resolve("Main.java"), res.source());
        Files.writeString(dir.resolve("JvmConformanceRunner.java"),
            BackendConformanceTest.buildJvmRunner(f.program(), "Main"));

        StringBuilder javacErr = new StringBuilder();
        boolean javacOk = BackendConformanceTest.compileWithJavac(dir,
            List.of("Main.java", "JvmConformanceRunner.java"), javacErr);
        if (!javacOk) {
            throw new RuntimeException("javac failed: " + javacErr);
        }

        ProcessBuilder java = new ProcessBuilder("java", "-cp",
            dir.toString(), "JvmConformanceRunner");
        java.redirectErrorStream(true);
        Process p2 = java.start();
        String out = new String(p2.getInputStream().readAllBytes()).trim();
        int exit = p2.waitFor();

        try {
            Files.walk(dir).sorted(Comparator.reverseOrder())
                .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}

        return new ExecResult(out, exit);
    }

    /**
     * Compiles every {@code .java} artifact in {@code dir} together with a
     * generated runner (auto-invoking the entry program's zero-arity
     * exports) with the javac frontend in-process
     * ({@code BackendConformanceTest.compileWithJavac}; ISSUE-0109
     * gate-time work) and executes with {@code java} in a subprocess —
     * artifact execution stays the same contract the conformance adapter
     * enforces for multi-module fixtures.
     */
    private static ExecResult runJvmArtifacts(Path dir, ProgramNode entryProgram,
                                              String entryClass) throws Exception {
        Files.writeString(dir.resolve("JvmConformanceRunner.java"),
            BackendConformanceTest.buildJvmRunner(entryProgram, entryClass));

        List<String> javaFiles = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            stream.filter(p -> p.toString().endsWith(".java"))
                  .sorted()
                  .forEach(p -> javaFiles.add(p.getFileName().toString()));
        }
        StringBuilder javacErr = new StringBuilder();
        boolean javacOk = BackendConformanceTest.compileWithJavac(dir,
            javaFiles, javacErr);
        if (!javacOk) {
            throw new RuntimeException("javac failed: " + javacErr);
        }

        ProcessBuilder java = new ProcessBuilder("java", "-cp", dir.toString(),
            "JvmConformanceRunner");
        java.redirectErrorStream(true);
        Process p2 = java.start();
        String out = new String(p2.getInputStream().readAllBytes()).trim();
        int exit = p2.waitFor();
        return new ExecResult(out, exit);
    }

    /**
     * A fixed-export module resolver for backend-level module probes
     * (ISSUE-0096): the real NameResolver/TypeChecker accept namespace
     * imports without an orchestrator.
     */
    private static final class FixedModuleResolver implements ModuleResolver {
        private final Map<String, Map<String, Type>> modules;

        FixedModuleResolver(Map<String, Map<String, Type>> modules) {
            this.modules = modules;
        }

        @Override
        public Map<String, Type> resolveModule(String modulePath,
                                               String importingModule,
                                               Set<String> modulesInProgress)
                throws ModuleNotFoundException {
            Map<String, Type> exports = modules.get(modulePath);
            if (exports == null) {
                throw new ModuleNotFoundException("Module not found: " + modulePath);
            }
            return exports;
        }

        @Override
        public Symbol.ClassSymbol resolveClassSymbol(String className,
                                                      String modulePath,
                                                      String importingModule)
                throws ModuleNotFoundException {
            return null;
        }
    }

    // =========================================================================
    // Tests
    // =========================================================================

    private static void testBackendNames() {
        System.out.println("-- Backend names --");

        check(Backend.fromCliName("lua").orElse(null) == Backend.LUAJIT,
            "'lua' selects LuaJIT");
        check(Backend.fromCliName("luajit").orElse(null) == Backend.LUAJIT,
            "'luajit' selects LuaJIT");
        check(Backend.fromCliName("jvm").orElse(null) == Backend.JVM,
            "'jvm' selects JVM");
        check(Backend.fromCliName("JVM").orElse(null) == Backend.JVM,
            "backend names are case-insensitive");
        check(Backend.fromCliName("wasm").isEmpty(), "unknown backend is empty");
        check(Backend.fromCliName(null).isEmpty(), "null backend name is empty");
        check(Backend.LUAJIT.cliName().equals("luajit"), "LuaJIT cli name");
        check(Backend.JVM.cliName().equals("jvm"), "JVM cli name");
    }

    private static void testIdentifierTranslation() {
        System.out.println("-- Identifier translation --");

        check(JvmBackend.javaName("x").equals("x"), "plain identifier unchanged");
        check(JvmBackend.javaName("int").equals("_int"),
            "Java keyword gets reserved-word prefix");
        check(JvmBackend.javaName("class").equals("_class"),
            "Java keyword 'class' translated");
        check(JvmBackend.javaName("_").equals("$u"), "'_' escaped");
        check(JvmBackend.javaName("_foo").equals("$ufoo"),
            "leading underscore escaped");
        check(JvmBackend.javaName("$bar").equals("$dbar"),
            "dollar escaped");

        // Injectivity: translations are pairwise distinct and reserved-word
        // prefixes can never collide with escaped identifiers.
        List<String> sample = List.of("int", "_int", "class", "_class", "x",
            "_x", "$x", "$dx", "_", "__", "$", "$$", "foo", "_foo", "$foo",
            "new", "_new", "switch", "_switch", "long", "_long");
        for (int i = 0; i < sample.size(); i++) {
            for (int j = i + 1; j < sample.size(); j++) {
                check(!JvmBackend.javaName(sample.get(i))
                        .equals(JvmBackend.javaName(sample.get(j))),
                    "translation is injective for '" + sample.get(i)
                        + "' vs '" + sample.get(j) + "'");
            }
        }
    }

    private static void testClassNameDerivation() {
        System.out.println("-- Class name derivation --");

        check(JvmBackend.classNameFor("main").equals("Main"),
            "simple module → Main");
        // ISSUE-0091 rework: names derive from the FULL module path so
        // app/main and sub/main can never silently overwrite each other.
        check(JvmBackend.classNameFor("app.main").equals("AppMain"),
            "dotted path → every segment contributes");
        check(JvmBackend.classNameFor("app/sub/main").equals("AppSubMain"),
            "slashed path → every segment contributes");
        check(!JvmBackend.classNameFor("app/main").equals(
                JvmBackend.classNameFor("sub/main")),
            "app/main and sub/main derive distinct class names");
        check(JvmBackend.classNameFor("jvm_main").equals("Jvm_main"),
            "underscore segment kept");
        check(JvmBackend.classNameFor("jvm-fixture").equals("Jvm_fixture"),
            "invalid chars sanitized");
        check(JvmBackend.classNameFor("").equals("Main"),
            "empty path → Main");
    }

    private static void testEmissionSmoke() {
        System.out.println("-- JVM emission smoke --");

        String source = """
            import * as console from "std/console"

            function add(a: int, b: int): int { return a + b; }

            function classify(n: int): int {
              if (n < 0) {
                return 0;
              } else if (n === 0) {
                return 1;
              } else {
                return 2;
              }
            }

            export function test(): int {
              let x: int = add(1, 2);
              let s: string = "lit" + "eral";
              let b: boolean = !false;
              let n: number = number(7);
              let i2: int = int(3.0);
              let z: null = null;
              console.log(s);
              return classify(x - 3) * 10 + int(n) + i2;
            }
            """;

        Frontend f = compileFrontend(source, "jvmtest-smoke.deal");
        check(f.errors().isEmpty(), "smoke frontend clean: " + f.errors());
        if (!f.errors().isEmpty()) return;

        JvmBackend.JvmCodegenResult res =
            JvmBackend.generate(f.program(), f.checkResult(), "jvmtest-smoke.deal", "main");
        check(!res.hasErrors(), "smoke codegen clean: " + res.diagnostics());
        check(res.className().equals("Main"), "class name from module path");
        if (res.hasErrors()) return;

        String java = res.source();
        check(java.contains("public final class Main"), "class declaration");
        // v1.2 module top level has no lets, so no module-field emission
        // surface exists in the smoke shape.
        check(java.contains("static long add(long a, long b)"),
            "function → static method with mapped types");
        check(java.contains("return intAdd(a, b);"), "checked int add");
        check(java.contains("else if ("), "else-if chain");
        check(java.contains("java.lang.System.out.println("), "console.log → java.lang.System.out");
        check(java.contains("intFromNumber(3.0)"), "int() intrinsic");
        check(java.contains("numberFromInt(7L)"), "number() intrinsic");
        check(java.contains("long x = add(1L, 2L);"), "local with int literals");
        check(java.contains("(\"lit\" + \"eral\")"), "string concatenation");
        check(java.contains("(!"), "boolean not");
        check(java.contains("Void z = null;"), "null-typed local from null literal");
        check(java.contains("public static long test()"), "exported function public");
        check(java.contains("static final class DealError"), "runtime error class");
        check(java.contains("static long intPow("), "pow helper emitted");
        check(!java.contains("nullAnd"),
            "null-typed calls are hoisted as pre-statements, not nullAnd-wrapped");
        check(!java.contains(" -> "),
            "emitted code contains no lambdas (capture-safety guarantee; "
                + "wrapper descriptor strings may contain the arrow glyph "
                + "without spaces, e.g. (int,int)->int)");
        check(!java.contains("__rt"), "no Lua runtime references");
    }

    private static void testUnsupportedConstructsRejected() {
        System.out.println("-- Unsupported constructs → E6000 --");

        record Case(String what, String source) {}
        List<Case> cases = List.of(
            new Case("array of nullable table elements", """
                export function test(): null {
                  let xs: (table | null)[] = [];
                }
                """),
            new Case("nullable table type", """
                export function test(): null {
                  let t: table | null = null;
                }
                """),
            new Case("nested class declaration", """
                export function test(): int {
                  class Inner { v: int = 0; }
                  return 1;
                }
                """),
            new Case("async function expression", """
                export function test(): null {
                  let f: async () => int = async function(): int { return 5; };
                  return null;
                }
                """),
            new Case("unused stdlib module import whose functions need table values", """
                import * as j from "std/json"
                export function test(): int { return 1; }
                """),
            new Case("unused non-console module import", """
                import * as m from "./other"
                export function test(): int { return 1; }
                """),
            new Case("function-typed class field", """
                class Holder { cb: (x: int) => int; }
                export function test(): int { return 1; }
                """),
            new Case("self-referential initializer", """
                export function test(): int {
                  let x: int = x + 1;
                  return x;
                }
                """),
            new Case("use of local before its declaration", """
                import * as console from "std/console"
                export function test(): null {
                  console.log(x);
                  let x: string = "later";
                }
                """),
            new Case("function colliding with runtime helper", """
                function intAdd(a: int, b: int): int { return a + b; }
                export function test(): int { return intAdd(1, 2); }
                """)
        );

        for (Case c : cases) {
            Frontend f = compileFrontend(c.source, "jvmtest-unsupported.deal");
            if (!f.errors().isEmpty()) {
                fail("frontend must accept unsupported case '" + c.what()
                    + "' (the backend rejects it): " + f.errors());
                continue;
            }
            JvmBackend.JvmCodegenResult res =
                JvmBackend.generate(f.program(), f.checkResult(),
                    "jvmtest-unsupported.deal", "main");
            check(res.hasErrors(), "backend rejects " + c.what());
            check(res.diagnostics().stream().anyMatch(d -> "E6000".equals(d.code())),
                "E6000 diagnostic for " + c.what() + ": " + res.diagnostics());
        }

        // ISSUE-0102: the shapes this slice promoted out of the E6000
        // rejection list now compile (each generates an artifact the
        // runtime fixtures above also execute through javac + java).
        List<Case> promoted = List.of(
            new Case("optional class field", """
                class Point { x?: int; }
                export function test(): int { return 1; }
                """),
            new Case("nested array type", """
                export function test(): int {
                  let rows: int[][] = [[1, 2], [3, 4]];
                  return rows[0][1];
                }
                """),
            new Case("array of function elements", """
                function add1(x: int): int { return x + 1; }
                export function test(): int {
                  let fs: ((x: int) => int)[] = [add1];
                  return fs[0](3);
                }
                """),
            new Case("table field read with a primitive target type", """
                export function test(): int {
                  let t: table = { item: 5 };
                  let n: int = t.item;
                  return n;
                }
                """),
            new Case("table field assignment", """
                export function test(): int {
                  let t: table = { item: 5 };
                  t.item = 6;
                  return 1;
                }
                """),
            new Case("class-typed class field", """
                class Inner { v: int = 0; }
                class Outer { inner: Inner = {}; }
                export function test(): int { return 1; }
                """),
            new Case("stdlib module import whose functions take table values", """
                import * as t from "std/table"
                export function test(): int { return 1; }
                """),
            new Case("throw", """
                export function test(): null {
                  throw { code: "E_TEST", message: "x" };
                  return;
                }
                """)
        );
        for (Case c : promoted) {
            Frontend f = compileFrontend(c.source, "jvmtest-unsupported.deal");
            if (!f.errors().isEmpty()) {
                fail("frontend must accept promoted case '" + c.what()
                    + "': " + f.errors());
                continue;
            }
            JvmBackend.JvmCodegenResult res =
                JvmBackend.generate(f.program(), f.checkResult(),
                    "jvmtest-unsupported.deal", "main");
            check(!res.hasErrors(),
                "backend now accepts " + c.what() + ": " + res.diagnostics());
        }
    }


    // =========================================================================
    // ISSUE-0092 semantic slice: while loops and template literals
    // =========================================================================

    private static int occurrences(String haystack, String needle) {
        int n = 0;
        for (int i = 0; (i = haystack.indexOf(needle, i)) >= 0; i += needle.length()) {
            n++;
        }
        return n;
    }

    /** While loops compute through local mutation (the ISSA-0092 slice
     * surface: condition, body block scope, reassignment, return). */
    private static void testWhileLoops() throws Exception {
        System.out.println("-- While loops (javac + java) --");

        ExecResult count = compileAndRunJvm("""
            export function test(): int {
              let i: int = 0;
              let sum: int = 0;
              while (i < 5) {
                i = i + 1;
                sum = sum + i;
              }
              return sum;
            }
            """, "whilecount");
        check(count.exitCode() == 0, "counting while exits 0");
        check(count.output().contains("15"),
            "counting while sums 1..5 → 15: " + count.output());

        ExecResult nested = compileAndRunJvm("""
            export function test(): int {
              let total: int = 0;
              let i: int = 1;
              while (i <= 3) {
                let j: int = 1;
                let inner: int = 0;
                while (j <= i) {
                  inner = inner + j;
                  j = j + 1;
                }
                total = total + inner;
                i = i + 1;
              }
              return total;
            }
            """, "whilenested");
        check(nested.exitCode() == 0, "nested while exits 0");
        check(nested.output().contains("10"),
            "nested while sums triangular numbers → 10: " + nested.output());

        // A while-true loop that returns from inside the body: the emitted
        // condition is routed through loopCond so javac never sees a
        // constant-true condition and the trailing return stays reachable.
        ExecResult until = compileAndRunJvm("""
            export function test(): int {
              let i: int = 0;
              while (true) {
                if (i > 2) {
                  return i;
                }
                i = i + 1;
              }
              return -1;
            }
            """, "whileuntil");
        check(until.exitCode() == 0, "while-true loop exits 0");
        check(until.output().contains("3"),
            "while-true loop returns from inside the body → 3: " + until.output());

        // v1.2 module top level has no statements; the condition
        // re-evaluation shape runs inside a function instead (a
        // module-level while would be a parse error, pinned below).
        ExecResult moduleWhile = compileAndRunJvm("""
            import * as console from "std/console"
            function tick(): null { console.log("tick"); }
            export function test(): int {
              let i: int = 0;
              while (i < 2 && tick() === null) {
                i = i + 1;
              }
              return i;
            }
            """, "whilemodule");
        check(moduleWhile.exitCode() == 0, "while-loop exits 0");
        check(occurrences(moduleWhile.output(), "tick") == 2,
            "while condition re-evaluates per iteration → tick ×2: "
                + moduleWhile.output());
        check(moduleWhile.output().contains("2"),
            "while leaves the local at 2: " + moduleWhile.output());

        // v1.2 grammar gate: the module-level while shape is a frontend
        // parse error (E1049).
        Frontend moduleWhileShape = compileFrontend("""
            import * as console from "std/console"
            while (true) {
              console.log("never");
            }
            export function test(): null { return null; }
            """, "jvmtest-whilemodule-shape.deal");
        check(moduleWhileShape.errors().stream()
                .anyMatch(d -> "E1049".equals(d.code())),
            "module-level while rejected with E1049: " + moduleWhileShape.errors());

        // Emission shape: the plain form routes the condition through the
        // loopCond identity helper (never a constant expression) and keeps
        // the body as a Java block.
        Frontend f = compileFrontend("""
            export function test(): int {
              let i: int = 0;
              while (i < 3) {
                i = i + 1;
              }
              return i;
            }
            """, "jvmtest-while-emission.deal");
        check(f.errors().isEmpty(), "while emission probe frontend clean: " + f.errors());
        if (f.errors().isEmpty()) {
            JvmBackend.JvmCodegenResult res = JvmBackend.generate(
                f.program(), f.checkResult(), "jvmtest-while-emission.deal", "main");
            check(!res.hasErrors(), "while emission probe codegen clean: " + res.diagnostics());
            if (!res.hasErrors()) {
                check(res.source().contains("while (loopCond((i < 3L)))"),
                    "condition routed through loopCond: " + res.source().substring(
                        res.source().indexOf("while"), res.source().indexOf("while") + 60));
                check(res.source().contains("static boolean loopCond(boolean v) { return v; }"),
                    "loopCond identity helper emitted");
                check(!res.source().contains(" -> "),
                    "while emission contains no lambda");
            }
        }
    }

    /** {@code while (false)} never runs its body — emitted as a loop whose
     * non-constant condition keeps the body reachable to javac (JLS
     * §14.21) and never taken at runtime, matching LuaJIT. */
    private static void testWhileFalseBodySkipped() throws Exception {
        System.out.println("-- while (false) body never runs (javac + java) --");

        ExecResult skipped = compileAndRunJvm("""
            export function test(): int {
              let x: int = 1;
              while (false) {
                x = x + 1;
              }
              return x;
            }
            """, "whilefalse");
        check(skipped.exitCode() == 0, "while-false exits 0");
        check(skipped.output().contains("1"),
            "while-false body skipped → 1: " + skipped.output());

        // Emission: the condition is wrapped in loopCond so javac does not
        // see a constant-false condition (whose body would be unreachable).
        Frontend f = compileFrontend("""
            export function test(): int {
              let x: int = 1;
              while (false) {
                x = x + 1;
              }
              return x;
            }
            """, "jvmtest-whilefalse-emission.deal");
        check(f.errors().isEmpty(), "while-false probe frontend clean: " + f.errors());
        if (f.errors().isEmpty()) {
            JvmBackend.JvmCodegenResult res = JvmBackend.generate(
                f.program(), f.checkResult(), "jvmtest-whilefalse-emission.deal", "main");
            check(!res.hasErrors(), "while-false probe codegen clean: " + res.diagnostics());
            if (!res.hasErrors()) {
                check(res.source().contains("while (loopCond(false))"),
                    "constant-false condition wrapped in loopCond");
            }
        }
    }

    /** A condition whose evaluation hoists a side-effecting null-typed call
     * must re-run that call on EVERY iteration (LuaJIT re-evaluates the
     * condition each time), not once before the loop. */
    private static void testWhileHoistedConditionPerIteration() throws Exception {
        System.out.println("-- While condition hoisted side effects per iteration (javac + java) --");

        ExecResult perIter = compileAndRunJvm("""
            import * as console from "std/console"
            function tick(): null { console.log("tick"); }
            export function test(): int {
              let i: int = 0;
              while (i < 3 && tick() === null) {
                i = i + 1;
              }
              return i;
            }
            """, "whilehoisted");
        check(perIter.exitCode() == 0, "hoisted-condition while exits 0");
        check(occurrences(perIter.output(), "tick") == 3,
            "condition re-evaluates per iteration → tick ×3: " + perIter.output());
        check(perIter.output().contains("3"),
            "loop counts to 3: " + perIter.output());
    }

    /** A DEAL continue inside a while nested in a TRANSFORMED (hoisted)
     * C-style for must target the while — the nearest enclosing loop —
     * never the enclosing for's re-placed-update label. The transformed
     * for pushes its cont$N label onto {@code loopContinueLabels}; the
     * while pushes a null frame over it, so emitContinue emits plain
     * {@code continue;} and Java resolves it to the nearest Java loop
     * (the while). Without the frame the while-level continue emitted
     * {@code break cont$N;} — exiting the for body, running the for
     * update, and silently skipping the while's remaining iterations
     * (LuaJIT prints 10 / 4, the broken JVM shape printed 5 / 2). */
    private static void testWhileContinueTargetsWhileInsideTransformedFor()
            throws Exception {
        System.out.println("-- While continue inside a transformed for (javac + java) --");

        // Runtime: the reviewer's probe shape — a while with a continue
        // nested in a for whose boolean[]-read condition hoists the
        // side-effecting reads into the transformed form.
        ExecResult plain = compileAndRunJvm("""
            export function test(): int {
              let flags: boolean[] = [true, true, true, true, true];
              let n: int = 0;
              for (let i: int = 0; i < 5 && flags[i]; i = i + 1) {
                let j: int = 0;
                while (j < 2) {
                  j = j + 1;
                  n = n + 1;
                  if (j === 1) { continue; }
                }
              }
              return n;
            }
            """, "whilecontintransfor");
        check(plain.exitCode() == 0,
            "while-continue-in-transformed-for exits 0: " + plain.output());
        check(plain.output().contains("10"),
            "the while-level continue stays inside the while → 2 per for "
                + "iteration, 5 iterations → 10 (LuaJIT parity; the broken "
                + "shape printed 5): " + plain.output());

        // Runtime: the hoisted-condition while variant — the continue
        // must re-run the hoisted per-iteration condition side effects
        // (tick) exactly like LuaJIT, not exit the while.
        ExecResult hoisted = compileAndRunJvm("""
            import * as console from "std/console"
            function tick(): null { console.log("tick"); }
            export function test(): int {
              let flags: boolean[] = [true, true];
              let n: int = 0;
              for (let i: int = 0; i < 2 && flags[i]; i = i + 1) {
                let j: int = 0;
                while (j < 2 && tick() === null) {
                  j = j + 1;
                  n = n + 1;
                  if (j === 1) { continue; }
                }
              }
              return n;
            }
            """, "whilehoistedcontintransfor");
        check(hoisted.exitCode() == 0,
            "hoisted-while-continue-in-transformed-for exits 0: "
                + hoisted.output());
        check(occurrences(hoisted.output(), "tick") == 4,
            "the continue re-enters the while head and re-runs the hoisted "
                + "condition side effects → tick ×4: " + hoisted.output());
        check(hoisted.output().contains("4"),
            "the hoisted while counts fully → 4 (the broken shape printed "
                + "2): " + hoisted.output());

        // Emission shape: the for-level continue still routes to the
        // re-placed-update label, the while-level continue stays a plain
        // Java continue targeting the while, and the while body keeps the
        // loopCond-wrapped condition.
        Frontend f = compileFrontend("""
            export function test(): int {
              let flags: boolean[] = [true, true, true, true, true];
              let n: int = 0;
              for (let i: int = 0; i < 5 && flags[i]; i = i + 1) {
                if (i === 2) { continue; }
                let j: int = 0;
                while (j < 2) {
                  j = j + 1;
                  n = n + 1;
                  if (j === 1) { continue; }
                }
              }
              return n;
            }
            """, "jvmtest-whilecont-transfor.deal");
        check(f.errors().isEmpty(),
            "while-continue-transformed-for probe frontend clean: "
                + f.errors());
        if (f.errors().isEmpty()) {
            JvmBackend.JvmCodegenResult res = JvmBackend.generate(
                f.program(), f.checkResult(),
                "jvmtest-whilecont-transfor.deal", "main");
            check(!res.hasErrors(),
                "while-continue-transformed-for probe codegen clean: "
                    + res.diagnostics());
            if (!res.hasErrors()) {
                check(res.source().contains("break cont$0;"),
                    "the for-level continue still routes through the "
                        + "re-placed-update label");
                check(occurrences(res.source(), "break cont$0;") == 1,
                    "exactly one label-routed continue (the for-level "
                        + "one) — the while-level continue is not one");
                check(occurrences(res.source(), "continue;") == 1,
                    "exactly one plain `continue;` — the while-level one: "
                        + occurrences(res.source(), "continue;"));
                int whileIdx =
                    res.source().indexOf("while (loopCond((j < 2L))) {");
                int contIdx = res.source().indexOf("continue;");
                check(whileIdx >= 0 && contIdx > whileIdx,
                    "the plain `continue;` sits inside the while body "
                        + "(index " + contIdx + " after " + whileIdx + ")");
                check(res.source().contains("while (loopCond((j < 2L))) {"),
                    "the while body keeps the loopCond-wrapped condition");
            }
        }
    }

    /** Function-body module-field dominance guards walk through while
     * bodies: writes inside a loop body dominate nothing after the loop
     * (the body may run zero times), reads/writes of later-declared
     * fields in a condition or body stay E6000, and the field-declared-
     * first shape stays full parity. */
    private static void testWhileLoopModuleFieldDominanceGuards() throws Exception {
        // v1.2 grammar gate: module-level lets were removed, so the v1.1
        // module-field dominance-guard shapes (function-body reads/writes
        // of later-declared module fields) are frontend parse errors
        // (E1049) before any backend analysis. The in-function loop
        // behavior they guarded is pinned by testWhileLoops above.
        Frontend writeInBody = compileFrontend("""
            function f(): int {
              let i: int = 0;
              while (i < 2) {
                x = x + 1;
                i = i + 1;
              }
              return x;
            }
            let x: int = 1;
            export function test(): int { return f(); }
            """, "jvmtest-while-dominance-write.deal");
        check(writeInBody.errors().stream()
                .anyMatch(d -> "E1049".equals(d.code())),
            "while-body write shape rejected with E1049: " + writeInBody.errors());

        Frontend readInCond = compileFrontend("""
            function g(): int {
              let i: int = 0;
              while (i < x) {
                i = i + 1;
              }
              return i;
            }
            let x: int = 5;
            export function test(): int { return g(); }
            """, "jvmtest-while-dominance-read.deal");
        check(readInCond.errors().stream()
                .anyMatch(d -> "E1049".equals(d.code())),
            "while-condition read shape rejected with E1049: " + readInCond.errors());

        Frontend parity = compileFrontend("""
            let x: int = 2;
            function h(): int {
              let i: int = 0;
              while (i < x) {
                i = i + 1;
              }
              return i;
            }
            export function test(): int { return h(); }
            """, "jvmtest-whiledomparity.deal");
        check(parity.errors().stream()
                .anyMatch(d -> "E1049".equals(d.code())),
            "declared-first module field shape rejected with E1049: " + parity.errors());
    
    }

    /** Module-level while bodies cannot contain return (Java initializers
     * cannot return) — E6000, never an artifact javac rejects. */
    private static void testWhileModuleLevelReturnRejected() {
        // v1.2 grammar gate: a module-level while is a frontend parse
        // error (E1049) — the v1.1 module-level-return E6000 surface is
        // removed with the module-level statement grammar.
        Frontend f = compileFrontend("""
            export function test(): int { return 1; }
            while (true) {
              if (false) {
                return;
              }
            }
            """, "jvmtest-while-module-return.deal");
        check(f.errors().stream().anyMatch(d -> "E1049".equals(d.code())),
            "module-level while rejected with E1049: " + f.errors());
    
    }

    /** Use-before-declaration detection walks while conditions: a
     * later-declared variable there is E6000, never an artifact javac
     * rejects after the CLI reported success. */
    private static void testWhileUseBeforeDeclarationRejected() {
        System.out.println("-- Use-before-declaration in a while condition → E6000 --");

        Frontend f = compileFrontend("""
            import * as console from "std/console"
            export function test(): null {
              while (x === 0) {
                console.log("looped");
              }
              let x: int = 0;
            }
            """, "jvmtest-while-undeclared.deal");
        check(f.errors().isEmpty(), "while-undeclared frontend clean: " + f.errors());
        if (f.errors().isEmpty()) {
            JvmBackend.JvmCodegenResult res = JvmBackend.generate(
                f.program(), f.checkResult(),
                "jvmtest-while-undeclared.deal", "main");
            check(res.hasErrors() && res.diagnostics().stream()
                    .anyMatch(d -> "E6000".equals(d.code())),
                "while condition use-before-declaration is E6000: " + res.diagnostics());
        }
    }

    /** A DEAL function whose mapped signature duplicates the emitted
     * loopCond helper would emit a duplicate Java method — E6000. */
    private static void testLoopCondHelperCollision() {
        System.out.println("-- loopCond helper signature collision → E6000 --");

        Frontend f = compileFrontend("""
            function loopCond(v: boolean): boolean { return v; }
            export function test(): boolean { return loopCond(true); }
            """, "jvmtest-loopcond-collision.deal");
        check(f.errors().isEmpty(), "loopCond collision frontend clean: " + f.errors());
        if (f.errors().isEmpty()) {
            JvmBackend.JvmCodegenResult res = JvmBackend.generate(
                f.program(), f.checkResult(),
                "jvmtest-loopcond-collision.deal", "main");
            check(res.hasErrors() && res.diagnostics().stream()
                    .anyMatch(d -> "E6000".equals(d.code())),
                "loopCond helper collision is E6000: " + res.diagnostics());
        }
    }

    /** Template literals lower to string concatenation: interpolated and
     * plain parts, empty parts elided, single-part templates emitted as the
     * literal itself — all string-typed by the checker (E3016 otherwise). */
    private static void testTemplateLiterals() throws Exception {
        System.out.println("-- Template literals (javac + java) --");

        ExecResult run = compileAndRunJvm("""
            export function test(): string {
              let a: string = "alpha";
              let b: string = "beta";
              let t1: string = `x${a}y${b}z`;
              let t2: string = `plain`;
              let t3: string = `${a}b`;
              let t4: string = `x${""}y`;
              return t1 + t2 + t3 + t4;
            }
            """, "templates");
        check(run.exitCode() == 0, "template run exits 0");
        check(run.output().contains("xalphaybetazplainalphabxy"),
            "template interpolations concatenate: " + run.output());

        Frontend f = compileFrontend("""
            export function test(): string {
              let a: string = "alpha";
              let b: string = "beta";
              let t1: string = `x${a}y${b}z`;
              let t2: string = `plain`;
              let t3: string = `${a}b`;
              let t4: string = `x${""}y`;
              return t1 + t2 + t3 + t4;
            }
            """, "jvmtest-template-emission.deal");
        check(f.errors().isEmpty(), "template probe frontend clean: " + f.errors());
        if (f.errors().isEmpty()) {
            JvmBackend.JvmCodegenResult res = JvmBackend.generate(
                f.program(), f.checkResult(), "jvmtest-template-emission.deal", "main");
            check(!res.hasErrors(), "template probe codegen clean: " + res.diagnostics());
            if (!res.hasErrors()) {
                check(res.source().contains(
                        "java.lang.String t1 = (\"x\" + a + \"y\" + b + \"z\");"),
                    "interpolated template lowers to concatenation: "
                        + (res.source().contains("t1 =") ? res.source().substring(
                            res.source().indexOf("t1 ="), res.source().indexOf("t1 =") + 50)
                            : "<missing>"));
                check(res.source().contains("java.lang.String t2 = \"plain\";"),
                    "single-part template emits its literal");
                check(res.source().contains("java.lang.String t3 = (a + \"b\");"),
                    "empty leading literal part elided");
                check(res.source().contains("java.lang.String t4 = (\"x\" + \"\" + \"y\");"),
                    "empty INTERPOLATION part kept (it is an expression, like LuaJIT)");
                check(!res.source().contains("unsupported(\"template literals\""),
                    "no template-literal E6000 fallback in the artifact");
            }
        }
    }

    // =========================================================================
    // ISSUE-0094 semantic slice: primitive arrays (literals, indexing,
    // element assignment, .length) with the spec's runtime checks
    // =========================================================================

    /** Primitive arrays compile and run end-to-end (javac + java): int[]/
     * number[]/string[]/boolean[] literals, index reads, element writes,
     * the i == length append (the xs[xs.length] idiom and a plain
     * index-equals-length write), .length reads, aliases sharing one
     * mutable wrapper (stable across append growth), arrays through
     * function parameters/returns, and module-level array fields — with
     * emission assertions for the wrapper/helper lowering. */
    private static void testPrimitiveArrays() throws Exception {
        System.out.println("-- Primitive arrays (javac + java) --");

        ExecResult run = compileAndRunJvm("""
            export function test(): int {
              let xs: int[] = [10, 20, 30];
              xs[1] = 99;
              xs[xs.length] = 40;
              let b: int[] = xs;
              b[0] = 7;
              let ns: number[] = [1.5, 2.5];
              ns[1] = ns[0] + 1.0;
              let ss: string[] = ["a", "b"];
              ss[0] = ss[1] + "x";
              let bs: boolean[] = [true, false];
              bs[1] = bs[0];
              let total: int = xs[0] + xs[1] + xs[2] + xs[3] + xs.length + b.length;
              if (ns[0] === 1.5 && ns[1] === 2.5 && ss[0] === "bx" && bs[0] && bs[1]) {
                return total;
              }
              return 0;
            }
            """, "arrays");
        check(run.exitCode() == 0, "array run exits 0: " + run.output());
        check(run.output().contains("184"),
            "literal/read/write/append/length compute 184 (7+99+30+40 = 176, "
            + "+ xs.length(4) + b.length(4)): " + run.output());

        // Append + alias + param/return shapes.
        ExecResult append = compileAndRunJvm("""
            function fill(xs: int[], v: int): int[] {
              xs[0] = v;
              return xs;
            }
            export function test(): int {
              let xs: int[] = [];
              xs[xs.length] = 1;
              xs[xs.length] = 2;
              xs[2] = 4;
              let ys: int[] = fill(xs, 9);
              return ys[0] + ys[1] + ys[2] + ys.length;
            }
            """, "append");
        check(append.exitCode() == 0, "append run exits 0: " + append.output());
        check(append.output().contains("18"),
            "append + param write computes 18 (9+2+4+3): " + append.output());

        // v1.2 grammar gate: module-level array fields were removed — the
        // v1.1 load-time mutation shape is a frontend parse error (E1049).
        Frontend moduleShape = compileFrontend("""
            let g: int[] = [1, 2];
            g[0] = 5;
            export function test(): int { return g[0] + g[1] + g.length; }
            """, "jvmtest-modarr.deal");
        check(moduleShape.errors().stream()
                .anyMatch(d -> "E1049".equals(d.code())),
            "module-level array field rejected with E1049: " + moduleShape.errors());

        // Emission shape: wrapper classes, read/write helper calls, length
        // lowering, and checkInt on int element stores.
        Frontend f = compileFrontend("""
            export function test(): int {
              let xs: int[] = [10, 20];
              xs[1] = 99;
              return xs[0] + xs.length;
            }
            """, "jvmtest-arrays-emission.deal");
        check(f.errors().isEmpty(), "array emission frontend clean: " + f.errors());
        if (f.errors().isEmpty()) {
            JvmBackend.JvmCodegenResult res = JvmBackend.generate(
                f.program(), f.checkResult(),
                "jvmtest-arrays-emission.deal", "main");
            check(!res.hasErrors(), "array emission codegen clean: "
                + res.diagnostics());
            if (!res.hasErrors()) {
                String java = res.source();
                check(java.contains("static final class __IntArray"),
                    "__IntArray wrapper class emitted");
                check(java.contains("new __IntArray(new long[]{10L, 20L})"),
                    "int[] literal lowers to new __IntArray(new long[]{…})");
                check(java.contains("__intArrayWrite(xs, 1L, 99L);"),
                    "element write lowers to the __intArrayWrite helper call");
                check(java.contains("__intArrayRead(xs, 0L)"),
                    "element read lowers to the __intArrayRead helper call");
                check(java.contains("((long) xs.data.length)"),
                    ".length lowers to a wrapped storage-length read");
                check(java.contains("v = checkInt(v);"),
                    "int element stores route through checkInt (E8004)");
                check(java.contains("i == (long) a.data.length"),
                    "append growth at i == length is present");
                check(java.contains("static boolean __booleanArrayWrite"),
                    "boolean write helper emitted");
                check(java.contains("static java.lang.String __stringArrayRead"),
                    "string read helper emitted");
                check(java.contains("static double __numberArrayRead"),
                    "number read helper emitted");
                check(!res.source().contains("unsupported(\"array"),
                    "no array E6000 fallback in the artifact");
            }
        }
    }

    /** Array runtime checks surface with DEAL error codes: negative read
     * E8002, read past the end E8001 "expected int, got null", negative
     * write E8002, gap write E8002, out-of-safe-range int element write
     * E8004 (the element value check). */
    private static void testArrayRuntimeErrorCodes() throws Exception {
        System.out.println("-- Array runtime error codes (javac + java) --");

        String[][] errorCases = {
            {"int", "let xs: int[] = [1, 2, 3]; return xs[-1];", "E8002", "negative read"},
            {"int", "let xs: int[] = [1, 2, 3]; return xs[99];", "E8001", "oob read"},
            {"int", "let xs: int[] = [1, 2, 3]; xs[-1] = 9; return xs[0];", "E8002", "negative write"},
            {"int", "let xs: int[] = [1, 2, 3]; xs[4] = 9; return xs[0];", "E8002", "gap write"},
            {"int", "let xs: int[] = [1]; xs[0] = 9223372036854775807; return xs[0];", "E8004", "int element check"},
            {"int", "let xs: int[] = []; xs[xs.length] = 9007199254740991; return 0;", null, "append boundary ok"},
            {"int", "let xs: number[] = [1.5]; xs[99] = 2.5; return 0;", "E8002", "number gap write"},
            {"string", "let ss: string[] = [\"a\"]; return ss[-2];", "E8002", "string negative read"},
            {"boolean", "let bs: boolean[] = [true]; return bs[1];", "E8001", "boolean oob read"},
        };
        for (String[] c : errorCases) {
            ExecResult r = compileAndRunJvm(
                "export function test(): " + c[0] + " { " + c[1] + " }", "arrerr");
            if (c[2] == null) {
                check(r.exitCode() == 0, c[3] + " exits 0: " + r.output());
            } else {
                check(r.exitCode() == 1, c[3] + " exits 1: " + r.output());
                check(r.output().contains("DEAL_ERROR_CODE: " + c[2]),
                    c[3] + " reports " + c[2] + ": " + r.output());
            }
        }

        // The boolean element spelling ("expected boolean, got null") is
        // pinned explicitly: the past-end boolean[] read raises the
        // boundary failure with the element-type message, not a generic
        // one.
        ExecResult boolSpelling = compileAndRunJvm(
            "export function test(): boolean { "
            + "let bs: boolean[] = [true]; return bs[1]; }", "arrerrbool");
        check(boolSpelling.exitCode() == 1
                && boolSpelling.output().contains("DEAL_ERROR_CODE: E8001")
                && boolSpelling.output().contains("expected boolean, got null"),
            "the boolean oob read spells 'expected boolean, got null': "
                + boolSpelling.output());

        // The write check runs AFTER the receiver/index/RHS expressions
        // evaluate (spec §Operational semantics rule 3): the RHS's E8004
        // fires before the E8002 bounds check for a gap write with an
        // out-of-range RHS — the JVM evaluates the helper-call arguments
        // left to right, then the helper performs its checks.
        ExecResult order = compileAndRunJvm("""
            export function test(): int {
              let xs: int[] = [1];
              xs[4] = 9223372036854775807;
              return 0;
            }
            """, "arrordercheck");
        check(order.exitCode() == 1, "gap write with out-of-range RHS exits 1: "
            + order.output());
        check(order.output().contains("DEAL_ERROR_CODE: E8004"),
            "the RHS value check (E8004) runs before the bounds check per "
            + "spec rule 3: " + order.output());
    }

    /** Array evaluation order with hoisted null-typed side effects: the
     * index operand's hoisted print runs before its inline call, which is
     * materialized into a temporary ahead of the RHS's hoisted print —
     * output index-side, index, value-side, value, then the written
     * element. Never a lambda, never an inverted print order. */
    private static void testArrayEvaluationOrderHoisted() throws Exception {
        System.out.println("-- Array evaluation order with hoisted side effects --");

        ExecResult run = compileAndRunJvm("""
            import * as console from "std/console"
            function pick(label: string, z: null): int { console.log(label); return 0; }
            export function test(): int {
              let xs: int[] = [5, 6];
              xs[pick("index", console.log("index-side"))] = pick("value", console.log("value-side"));
              return xs[0];
            }
            """, "arrord");
        check(run.exitCode() == 0, "hoisted array order exits 0: " + run.output());
        check(run.output().contains("index-side\nindex\nvalue-side\nvalue\n0"),
            "hoisted side effects keep left-to-right order: " + run.output());

        Frontend f = compileFrontend("""
            import * as console from "std/console"
            function pick(label: string, z: null): int { console.log(label); return 0; }
            export function test(): int {
              let xs: int[] = [5, 6];
              xs[pick("index", console.log("index-side"))] = pick("value", console.log("value-side"));
              return xs[0];
            }
            """, "jvmtest-arrord-emission.deal");
        check(f.errors().isEmpty(), "hoisted array order frontend clean: " + f.errors());
        if (f.errors().isEmpty()) {
            JvmBackend.JvmCodegenResult res = JvmBackend.generate(
                f.program(), f.checkResult(),
                "jvmtest-arrord-emission.deal", "main");
            check(!res.hasErrors(), "hoisted array order codegen clean: "
                + res.diagnostics());
            if (!res.hasErrors()) {
                String java = res.source();
                check(!java.contains(" -> "),
                    "no lambda emitted for array order (spaced arrow; "
                    + "wrapper descriptor strings may carry the unspaced "
                    + "arrow glyph): " + java);
                int sideIdx = java.indexOf("\"index-side\"");
                int tempIdx = java.indexOf("__t0 = pick(");
                int valueIdx = java.indexOf("\"value-side\"");
                check(sideIdx >= 0 && tempIdx >= 0 && valueIdx >= 0
                        && sideIdx < tempIdx && tempIdx < valueIdx,
                    "the inline index call is materialized into __t0 between "
                    + "the index-side and value-side hoisted prints");
                check(java.contains("__intArrayWrite(xs, __t0, pick(\"value\", null));"),
                    "the write helper call carries the materialized index");
            }
        }
    }

    /** === / !== operand positions with array reads past the end
     * (ISSUE-0094 rework): the spec read-site contract applies no typed
     * boundary at a comparison operand, so LuaJIT reads nil and computes
     * the comparison — the JVM boxed comparison reads must yield the same
     * nil semantics (nil === v false, nil !== v true, nil === nil true)
     * for all four element types instead of raising E8001. Also pins the
     * strict evaluation of the non-read operand (never skipped by a Java
     * short-circuit), the negative-index E8002 that LuaJIT raises
     * unconditionally in the same position, and the boxed-helper emission
     * shape. */
    private static void testArrayReadComparisonNilSemantics() throws Exception {
        System.out.println("-- Array reads in === / !== positions (javac + java) --");

        ExecResult run = compileAndRunJvm("""
            import * as console from "std/console"
            export function test(): null {
              let xs: int[] = [1];
              let ns: number[] = [1.5];
              let ss: string[] = ["a"];
              let bs: boolean[] = [true];
              if (xs[99] === 5) { console.log("bad1"); }
              if (xs[99] !== 5) { console.log("int-neq"); }
              if (xs[99] === xs[99]) { console.log("nil-eq-nil"); }
              if (ns[99] !== 2.5) { console.log("number-neq"); }
              if (ss[99] === "x") { console.log("bad2"); }
              if (ss[99] !== "x") { console.log("string-neq"); }
              if (bs[99] === true) { console.log("bad3"); }
              if (bs[99] !== true) { console.log("boolean-neq"); }
              if (xs[0] === 1 && xs[99] !== 1) { console.log("mixed"); }
            }
            """, "arrcmp");
        check(run.exitCode() == 0, "comparison nil semantics exits 0: " + run.output());
        check(run.output().contains("int-neq")
                && run.output().contains("nil-eq-nil")
                && run.output().contains("number-neq")
                && run.output().contains("string-neq")
                && run.output().contains("boolean-neq")
                && run.output().contains("mixed")
                && !run.output().contains("bad"),
            "nil === v / nil !== v / nil === nil semantics for all four "
            + "element types: " + run.output());

        // The non-read comparison operand always evaluates — the Java
        // null-guard short-circuit must never skip a DEAL-visible effect
        // (LuaJIT evaluates both operands strictly). Both directions.
        ExecResult rhs = compileAndRunJvm("""
            import * as console from "std/console"
            function mark(label: string, v: int): int { console.log(label); return v; }
            export function test(): null {
              let xs: int[] = [1];
              if (xs[99] === mark("rhs", 5)) { console.log("bad-eq"); }
              if (xs[99] !== mark("rhs2", 5)) { console.log("neq-ok"); }
            }
            """, "arrcmprhs");
        check(rhs.exitCode() == 0, "RHS-evaluation shape exits 0: " + rhs.output());
        check(rhs.output().contains("rhs") && rhs.output().contains("rhs2")
                && rhs.output().contains("neq-ok")
                && !rhs.output().contains("bad-eq"),
            "the value operand always evaluates in both directions: "
                + rhs.output());

        ExecResult lhs = compileAndRunJvm("""
            import * as console from "std/console"
            function mark(label: string, v: int): int { console.log(label); return v; }
            export function test(): null {
              let xs: int[] = [1];
              if (mark("lhs", 5) === xs[99]) { console.log("bad-eq"); }
              if (mark("lhs2", 5) !== xs[99]) { console.log("neq-ok"); }
            }
            """, "arrcmplhs");
        check(lhs.exitCode() == 0, "LHS-evaluation shape exits 0: " + lhs.output());
        check(lhs.output().contains("lhs") && lhs.output().contains("lhs2")
                && lhs.output().contains("neq-ok")
                && !lhs.output().contains("bad-eq"),
            "the value operand always evaluates on the left too: "
                + lhs.output());

        // Read-vs-read with only one side past the end: nil === 5 is
        // false, 5 === nil is false, nil !== 5 is true — and the
        // in-bounds elements still compare normally.
        ExecResult rr = compileAndRunJvm("""
            import * as console from "std/console"
            export function test(): null {
              let xs: int[] = [1];
              let ys: int[] = [5];
              if (xs[99] === ys[0]) { console.log("bad-a"); }
              if (ys[0] === xs[99]) { console.log("bad-b"); }
              if (xs[99] !== ys[0]) { console.log("rr-neq"); }
              if (xs[0] === ys[0]) { console.log("bad-c"); }
              if (xs[0] !== ys[0]) { console.log("rr-in-bounds-neq"); }
              if (xs[0] === xs[0]) { console.log("rr-in-bounds-eq"); }
            }
            """, "arrcmprr");
        check(rr.exitCode() == 0, "read-vs-read shape exits 0: " + rr.output());
        check(rr.output().contains("rr-neq")
                && rr.output().contains("rr-in-bounds-neq")
                && rr.output().contains("rr-in-bounds-eq")
                && !rr.output().contains("bad"),
            "read-vs-read nil semantics and in-bounds element comparisons: "
                + rr.output());

        // A negative index in a comparison operand still raises E8002
        // (LuaJIT emits the negative-index check unconditionally at the
        // read, whatever the surrounding position).
        ExecResult neg = compileAndRunJvm("""
            import * as console from "std/console"
            export function test(): null {
              let xs: int[] = [1];
              if (xs[-1] === 5) { console.log("bad"); }
            }
            """, "arrcmpneg");
        check(neg.exitCode() == 1, "negative comparison index exits 1: "
            + neg.output());
        check(neg.output().contains("DEAL_ERROR_CODE: E8002"),
            "negative index in === raises E8002: " + neg.output());

        // A comparison read inside a while condition re-evaluates per
        // iteration: the boxed read pre-statement is flushed inside the
        // loop before the condition test (LuaJIT re-evaluates the
        // condition every iteration), never once before the loop.
        ExecResult w = compileAndRunJvm("""
            export function test(): int {
              let xs: int[] = [1, 2, 3];
              let i: int = 0;
              while (xs[i] !== 3) {
                i = i + 1;
              }
              return i;
            }
            """, "arrcmpwhile");
        check(w.exitCode() == 0, "while comparison read exits 0: " + w.output());
        check(w.output().contains("2"),
            "the boxed comparison read re-evaluates per iteration "
            + "(i reaches 2): " + w.output());

        // Emission shape: boxed helpers, nullable temporaries, no lambda.
        Frontend f = compileFrontend("""
            import * as console from "std/console"
            export function test(): null {
              let xs: int[] = [1];
              if (xs[99] === 5) { console.log("eq"); }
              if (xs[99] !== 5) { console.log("neq"); }
            }
            """, "jvmtest-arrcmp-emission.deal");
        check(f.errors().isEmpty(), "comparison emission frontend clean: " + f.errors());
        if (f.errors().isEmpty()) {
            JvmBackend.JvmCodegenResult res = JvmBackend.generate(
                f.program(), f.checkResult(),
                "jvmtest-arrcmp-emission.deal", "main");
            check(!res.hasErrors(), "comparison emission codegen clean: "
                + res.diagnostics());
            if (!res.hasErrors()) {
                String java = res.source();
                check(java.contains("static java.lang.Long __intArrayReadBoxed"),
                    "__intArrayReadBoxed helper emitted");
                check(java.contains("__intArrayReadBoxed(xs, 99L)"),
                    "past-end === read routes through the boxed helper");
                check(java.contains("static java.lang.String __stringArrayReadBoxed")
                        && java.contains("static java.lang.Double __numberArrayReadBoxed")
                        && java.contains("static java.lang.Boolean __booleanArrayReadBoxed"),
                    "boxed helpers emitted for all four element types");
                check(!java.contains(" -> "),
                    "no lambda emitted for comparison positions (spaced "
                    + "arrow; wrapper descriptor strings may carry the "
                    + "unspaced arrow glyph): " + java);
            }
        }
    }

    /** Array evaluation order with a side-effecting receiver and hoisting
     * index and RHS operands (the reviewer's critical repro): the
     * receiver's inline call must run BEFORE the index operand's hoisted
     * print — the materialization is anchored at the earliest hoist start
     * after the receiver, not at the last hoisting operand's start, which
     * printed b, a, i, c, v. Same anchoring for array literals with a
     * side-effecting first element and hoisting later elements. */
    private static void testArrayEvalOrderSideEffectingReceiver() throws Exception {
        System.out.println("-- Array evaluation order with a side-effecting receiver --");

        ExecResult run = compileAndRunJvm("""
            import * as console from "std/console"
            function pick(label: string, z: null): int { console.log(label); return 0; }
            function getArr(label: string, xs: int[]): int[] { console.log(label); return xs; }
            export function test(): null {
              let xs: int[] = [5, 6];
              getArr("a", xs)[pick("i", console.log("b"))] = pick("v", console.log("c"));
              if (xs[0] === 0) { console.log("write-ok"); }
            }
            """, "arrrecvwrite");
        check(run.exitCode() == 0, "side-effecting receiver write exits 0: "
            + run.output());
        check(run.output().contains("a\nb\ni\nc\nv\nwrite-ok"),
            "receiver 'a' runs before the index operand's hoisted 'b' "
            + "(a, b, i, c, v): " + run.output());

        ExecResult lit = compileAndRunJvm("""
            import * as console from "std/console"
            function pick(label: string, z: null): int { console.log(label); return 0; }
            function getA(label: string): int { console.log(label); return 1; }
            export function test(): null {
              let xs: int[] = [getA("a"), pick("i", console.log("b")), pick("v", console.log("c"))];
              if (xs[0] === 1 && xs[1] === 0 && xs[2] === 0) { console.log("lit-ok"); }
            }
            """, "arrrecvlit");
        check(lit.exitCode() == 0, "side-effecting first literal element exits 0: "
            + lit.output());
        check(lit.output().contains("a\nb\ni\nc\nv\nlit-ok"),
            "literal first element 'a' runs before the hoisted 'b' of the "
            + "second element (a, b, i, c, v): " + lit.output());

        // Emission shape: the receiver's inline call is materialized at
        // the index operand's hoist start — BEFORE the hoisted println —
        // and never inside a lambda.
        Frontend f = compileFrontend("""
            import * as console from "std/console"
            function pick(label: string, z: null): int { console.log(label); return 0; }
            function getArr(label: string, xs: int[]): int[] { console.log(label); return xs; }
            export function test(): null {
              let xs: int[] = [5, 6];
              getArr("a", xs)[pick("i", console.log("b"))] = pick("v", console.log("c"));
            }
            """, "jvmtest-arrrecv-emission.deal");
        check(f.errors().isEmpty(), "receiver-order emission frontend clean: " + f.errors());
        if (f.errors().isEmpty()) {
            JvmBackend.JvmCodegenResult res = JvmBackend.generate(
                f.program(), f.checkResult(),
                "jvmtest-arrrecv-emission.deal", "main");
            check(!res.hasErrors(), "receiver-order emission codegen clean: "
                + res.diagnostics());
            if (!res.hasErrors()) {
                String java = res.source();
                int recvIdx = java.indexOf("__t1 = getArr(\"a\", xs);");
                int hoistIdx = java.indexOf("java.lang.System.out.println(\"b\");");
                int inlineIdx = java.indexOf("__t0 = pick(\"i\", null);");
                check(recvIdx >= 0 && hoistIdx >= 0 && inlineIdx >= 0
                        && recvIdx < hoistIdx && hoistIdx < inlineIdx,
                    "the receiver materialization lands before the index "
                    + "operand's hoisted println and the index's "
                    + "materialized inline call: " + java);
                check(!java.contains(" -> "),
                    "no lambda emitted for the receiver shape (spaced "
                    + "arrow; wrapper descriptor strings may carry the "
                    + "unspaced arrow glyph): " + java);
            }
        }
    }

    /** Both === / !== operands are array reads: the LEFT read evaluates
     * completely — receiver, index, and the boxed helper call — before
     * the right operand's first evaluation. When the left read raises
     * E8002 (negative index) and the right read's receiver/index hoists
     * a null-typed side effect, the JVM must raise before that hoisted
     * print runs (LuaJIT evaluates the left read and raises before the
     * right operand is evaluated): the reviewer's repro
     * {@code ys[-1] === makeArr("made", console.log("h"))[0]} printed
     * "h" before the E8002 when the boxed read pre-statements were
     * appended after the right operand's hoisted println — anchoring
     * the left read's helper call at the left operand's evaluation
     * position (immediately after the left receiver/index hoisted
     * statements, before the right operand is even emitted) fixes it. */
    private static void testArrayReadComparisonBothReadsOrder() throws Exception {
        System.out.println("-- Both-reads === / !== left-read evaluation position --");

        // Shape 1: left read raises E8002; the right read's RECEIVER
        // hoists a null-typed side effect. Only the E8002 may print.
        ExecResult recv = compileAndRunJvm("""
            import * as console from "std/console"
            function makeArr(label: string, z: null): int[] { console.log(label); return [1]; }
            export function test(): null {
              let ys: int[] = [1];
              if (ys[-1] === makeArr("made", console.log("h"))[0]) { console.log("bad"); }
            }
            """, "arrbothnegrecv");
        check(recv.exitCode() == 1, "left-negative/right-hoisting-receiver "
            + "shape exits 1: " + recv.output());
        check(recv.output().contains("DEAL_ERROR_CODE: E8002")
                && !recv.output().contains("h")
                && !recv.output().contains("made")
                && !recv.output().contains("bad"),
            "the left read's E8002 raises before the right receiver's "
            + "hoisted println (no 'h'/'made' before it): " + recv.output());

        // Shape 2: left read raises E8002; the right read's INDEX
        // operand hoists a null-typed side effect. Same contract.
        ExecResult idx = compileAndRunJvm("""
            import * as console from "std/console"
            function pick(label: string, z: null): int { console.log(label); return 0; }
            export function test(): null {
              let ys: int[] = [1];
              let zs: int[] = [1];
              if (ys[-1] === zs[pick("i", console.log("h"))]) { console.log("bad"); }
            }
            """, "arrbothnegidx");
        check(idx.exitCode() == 1, "left-negative/right-hoisting-index "
            + "shape exits 1: " + idx.output());
        check(idx.output().contains("DEAL_ERROR_CODE: E8002")
                && !idx.output().contains("h")
                && !idx.output().contains("bad"),
            "the left read's E8002 raises before the right index's "
            + "hoisted println (no 'h' before it; 'h' always precedes "
            + "pick's 'i' label, and the bare 'i' cannot be pinned "
            + "because the E8002 message 'negative array index' "
            + "contains the letter i): " + idx.output());

        // Positive control: both reads in bounds — the left read still
        // evaluates completely before the right operand's hoisted print
        // and receiver call (h, made, h2, made2, eq-ok).
        ExecResult ok = compileAndRunJvm("""
            import * as console from "std/console"
            function makeArr(label: string, z: null): int[] { console.log(label); return [1]; }
            export function test(): null {
              let ys: int[] = [1];
              if (ys[0] !== makeArr("made", console.log("h"))[0]) { console.log("bad-neq"); }
              if (ys[0] === makeArr("made2", console.log("h2"))[0]) { console.log("eq-ok"); }
            }
            """, "arrbothinbounds");
        check(ok.exitCode() == 0, "in-bounds both-reads shape exits 0: "
            + ok.output());
        check(ok.output().contains("h\nmade\nh2\nmade2\neq-ok")
                && !ok.output().contains("bad"),
            "in-bounds both-reads order is h, made, h2, made2, eq-ok: "
                + ok.output());

        // Emission shape: the left read's boxed helper pre-statement is
        // anchored BEFORE the right operand's hoisted println, and the
        // right read's helper call follows its own operand's hoisted
        // statements.
        Frontend f = compileFrontend("""
            import * as console from "std/console"
            function makeArr(label: string, z: null): int[] { console.log(label); return [1]; }
            export function test(): null {
              let ys: int[] = [1];
              if (ys[-1] === makeArr("made", console.log("h"))[0]) { console.log("bad"); }
            }
            """, "jvmtest-arrboth-order-emission.deal");
        check(f.errors().isEmpty(), "both-reads order emission frontend clean: " + f.errors());
        if (f.errors().isEmpty()) {
            JvmBackend.JvmCodegenResult res = JvmBackend.generate(
                f.program(), f.checkResult(),
                "jvmtest-arrboth-order-emission.deal", "main");
            check(!res.hasErrors(), "both-reads order emission codegen clean: "
                + res.diagnostics());
            if (!res.hasErrors()) {
                String java = res.source();
                int leftReadIdx = java.indexOf(
                    "__intArrayReadBoxed(ys, intNeg(1L));");
                int hoistIdx = java.indexOf(
                    "java.lang.System.out.println(\"h\");");
                int rightReadIdx = java.indexOf(
                    "__intArrayReadBoxed(makeArr(\"made\", null), 0L);");
                check(leftReadIdx >= 0 && hoistIdx >= 0 && rightReadIdx >= 0
                        && leftReadIdx < hoistIdx && hoistIdx < rightReadIdx,
                    "the left read's boxed helper call lands before the "
                    + "right operand's hoisted println, which lands before "
                    + "the right read's helper call: " + java);
                check(!java.contains(" -> "),
                    "no lambda emitted for the both-reads shape (spaced "
                    + "arrow; wrapper descriptor strings may carry the "
                    + "unspaced arrow glyph): " + java);
            }
        }
    }

    /** Boundary-less array-read positions (ISSUE-0094 rework): the spec
     * read-site contract (§Bounds and nil behavior) applies no typed
     * boundary to a discarded read, a {@code !} operand, or a
     * {@code &&}/{@code ||} operand, so LuaJIT computes on the nil a
     * past-end read yields instead of raising E8001 — a discarded read
     * drops it (no error), {@code not nil} is {@code true}, and
     * {@code nil or true} is {@code true} (nil is falsy). The JVM routes
     * those positions through the boxed read helpers (null past the end,
     * still E8002 for a negative index) and Lua's nil semantics: the
     * discard drops the boxed value, {@code !} coerces with
     * {@code (x == null || !x.booleanValue())}, and {@code &&}/{@code ||}
     * lower to boxed {@code java.lang.Boolean} temporaries with
     * truthiness guards — while the result nil (e.g. {@code bs[99] && true})
     * still fails at a typed boolean boundary exactly where LuaJIT's
     * check_boolean(nil) fails (E8001 "expected boolean, got null"). */
    private static void testArrayBoundaryLessReadPositions() throws Exception {
        System.out.println("-- Boundary-less read positions (javac + java) --");

        // The three reviewer shapes in one artifact: discard, !read, and
        // || / && operands — matching the LuaJIT reference exactly.
        ExecResult run = compileAndRunJvm("""
            import * as console from "std/console"
            function pick(label: string, z: null): int { console.log(label); return 0; }
            export function test(): null {
              let xs: int[] = [1];
              let ns: number[] = [1.5];
              let ss: string[] = ["a"];
              let bs: boolean[] = [true];
              xs[99];
              ns[99];
              ss[99];
              bs[99];
              console.log("discard-ok");
              let b: boolean = !bs[99];
              if (b) { console.log("not-coerced-true"); }
              if (!bs[99]) { console.log("not-taken"); }
              let d: boolean = !(bs[99] && true);
              if (d) { console.log("not-niland"); }
              let c: boolean = bs[99] || true;
              if (c) { console.log("or-coerced"); }
              let g: boolean = true || bs[pick("xx", console.log("h"))];
              if (g) { console.log("true-or-skip"); }
              let f: boolean = false && bs[pick("yy", console.log("h2"))];
              if (!f) { console.log("false-and-skip"); }
            }
            """, "arrboundaryless");
        check(run.exitCode() == 0, "boundary-less shapes exit 0: "
            + run.output());
        check(run.output().contains("discard-ok\nnot-coerced-true\nnot-taken\nnot-niland\nor-coerced\ntrue-or-skip\nfalse-and-skip"),
            "discard / ! / && || shapes match the LuaJIT reference "
            + "(discard-ok, not-coerced-true, not-taken, not-niland, "
            + "or-coerced, true-or-skip, false-and-skip): " + run.output());
        check(!run.output().contains("h\n") && !run.output().contains("xx")
                && !run.output().contains("yy"),
            "the skipped && / || right operands never evaluate (no "
            + "hoisted h print, no pick labels): " + run.output());

        // The result nil of `bs[99] && true` fails at the declaration's
        // typed boolean boundary (LuaJIT: check_boolean(nil) → E8001
        // "expected boolean"); the JVM converts with booleanNotNull and
        // must spell the message with the established null convention.
        ExecResult boundary = compileAndRunJvm("""
            export function test(): boolean {
              let bs: boolean[] = [true];
              let b: boolean = bs[99] && true;
              return b;
            }
            """, "arrandboundary");
        check(boundary.exitCode() == 1, "nil && result at a boolean "
            + "boundary exits 1: " + boundary.output());
        check(boundary.output().contains("DEAL_ERROR_CODE: E8001")
                && boundary.output().contains("expected boolean, got null"),
            "the && nil result fails the boolean boundary with E8001 "
            + "'expected boolean, got null': " + boundary.output());

        // The if-condition boundary raises the same way.
        ExecResult cond = compileAndRunJvm("""
            import * as console from "std/console"
            export function test(): null {
              let bs: boolean[] = [true];
              if (bs[99] && true) { console.log("bad"); }
              console.log("unreachable");
            }
            """, "arrandcondboundary");
        check(cond.exitCode() == 1 && cond.output().contains(
                "DEAL_ERROR_CODE: E8001")
                && !cond.output().contains("bad")
                && !cond.output().contains("unreachable"),
            "the && nil result fails the if-condition boundary with E8001 "
            + "(no 'bad'/'unreachable'): " + cond.output());

        // A negative index in a discarded read still raises E8002
        // (LuaJIT raises that unconditionally at the read).
        ExecResult neg = compileAndRunJvm("""
            import * as console from "std/console"
            export function test(): null {
              let xs: int[] = [1];
              xs[-1];
              console.log("unreachable");
            }
            """, "arrdiscardneg");
        check(neg.exitCode() == 1
                && neg.output().contains("DEAL_ERROR_CODE: E8002")
                && !neg.output().contains("unreachable"),
            "a discarded negative-index read still raises E8002: "
                + neg.output());

        // Emission shapes: boxed discard, the ! coercion, the boxed
        // short-circuit temp with truthiness guards, the booleanNotNull
        // boundary conversion — and never a lambda.
        Frontend f = compileFrontend("""
            import * as console from "std/console"
            function pick(label: string, z: null): int { console.log(label); return 0; }
            export function test(): null {
              let xs: int[] = [1];
              let bs: boolean[] = [true];
              xs[99];
              let b: boolean = !bs[99];
              let c: boolean = bs[99] || true;
              let d: boolean = bs[99] && true;
              if (!(bs[99] && true)) { console.log("x"); }
            }
            """, "jvmtest-arrboundaryless-emission.deal");
        check(f.errors().isEmpty(), "boundary-less emission frontend clean: "
            + f.errors());
        if (f.errors().isEmpty()) {
            JvmBackend.JvmCodegenResult res = JvmBackend.generate(
                f.program(), f.checkResult(),
                "jvmtest-arrboundaryless-emission.deal", "main");
            check(!res.hasErrors(), "boundary-less emission codegen clean: "
                + res.diagnostics());
            if (!res.hasErrors()) {
                String java = res.source();
                check(java.contains("__intArrayReadBoxed(xs, 99L)")
                        && java.contains("java.lang.Long __ignored"),
                    "the discarded read emits the boxed helper call and a "
                    + "boxed dummy-local discard: " + java);
                check(java.contains(" == null || !")
                        && java.contains(".booleanValue())"),
                    "the ! operand emits Lua's not coercion "
                    + "(x == null || !x.booleanValue()): " + java);
                check(java.contains("java.lang.Boolean __sc")
                        && java.contains(" != null && ")
                        && java.contains("booleanNotNull("),
                    "&& / || operands lower to boxed short-circuit "
                    + "temporaries with truthiness guards and the typed "
                    + "boundary converts with booleanNotNull: " + java);
                check(!java.contains(" -> "),
                    "no lambda emitted for the boundary-less shapes (spaced "
                    + "arrow; wrapper descriptor strings may carry the "
                    + "unspaced arrow glyph): " + java);
            }
        }
    }

    /** Plain-value-left {@code ===}/{@code !==} operand evaluation
     * order (ISSUE-0094 rework): when the LEFT operand is a plain
     * non-nil-capable value and the RIGHT operand carries the LuaJIT
     * nil semantics (a primitive array read or a nil-aware {@code &&}/
     * {@code ||} result), the left operand's inline evaluation is
     * materialized into a pre-statement IMMEDIATELY — before the right
     * operand is even emitted, so the right operand's boxed read helper
     * call (and its operands' hoisted side effects) can never run
     * first. A late materialization inverted the spec's strict
     * left-to-right order (§Operational semantics):
     * {@code mark("lhs", 5) === xs[-1]} raised the right read's E8002
     * before printing "lhs" (LuaJIT prints "lhs" first) and
     * {@code (9007199254740991 + 1) === xs[-1]} raised the read's E8002
     * where LuaJIT raises the left arithmetic's E8004 first. */
    private static void testArrayReadComparisonPlainLeftOperandOrder()
            throws Exception {
        System.out.println("-- Plain-left === / !== operand order (javac + java) --");

        // The reviewer's int shape: the left effect must print before
        // the right read's E8002. The number/string/boolean and
        // nil-aware-right shapes follow in their own artifacts below.
        ExecResult run = compileAndRunJvm("""
            import * as console from "std/console"
            function mark(label: string, v: int): int { console.log(label); return v; }
            export function test(): null {
              let xs: int[] = [1];
              if (mark("lhs", 5) === xs[-1]) { console.log("bad"); }
            }
            """, "arrplainlhserr");
        check(run.exitCode() == 1
                && run.output().contains("lhs")
                && run.output().contains("DEAL_ERROR_CODE: E8002")
                && !run.output().contains("bad"),
            "int shape: 'lhs' prints before the right read's E8002, no "
            + "'bad': " + run.output());

        ExecResult runN = compileAndRunJvm("""
            import * as console from "std/console"
            function markN(label: string, v: number): number { console.log(label); return v; }
            export function test(): null {
              let ns: number[] = [1.5];
              if (markN("lhsN", 2.5) === ns[-1]) { console.log("badN"); }
            }
            """, "arrplainlhserrnum");
        check(runN.exitCode() == 1
                && runN.output().contains("lhsN")
                && runN.output().contains("DEAL_ERROR_CODE: E8002")
                && !runN.output().contains("badN"),
            "number shape: 'lhsN' prints before the right read's E8002: "
                + runN.output());

        ExecResult runS = compileAndRunJvm("""
            import * as console from "std/console"
            function markS(label: string, v: string): string { console.log(label); return v; }
            export function test(): null {
              let ss: string[] = ["a"];
              if (markS("lhsS", "x") === ss[-1]) { console.log("badS"); }
            }
            """, "arrplainlhserrstr");
        check(runS.exitCode() == 1
                && runS.output().contains("lhsS")
                && runS.output().contains("DEAL_ERROR_CODE: E8002")
                && !runS.output().contains("badS"),
            "string shape: 'lhsS' prints before the right read's E8002: "
                + runS.output());

        ExecResult runB = compileAndRunJvm("""
            import * as console from "std/console"
            function markB(label: string, v: boolean): boolean { console.log(label); return v; }
            export function test(): null {
              let bs: boolean[] = [true];
              if (markB("lhs", true) === bs[-1]) { console.log("bad"); }
            }
            """, "arrplainlhserrbool");
        check(runB.exitCode() == 1
                && runB.output().contains("lhs")
                && runB.output().contains("DEAL_ERROR_CODE: E8002")
                && !runB.output().contains("bad"),
            "boolean shape: 'lhs' prints before the right read's E8002: "
                + runB.output());

        ExecResult runNA = compileAndRunJvm("""
            import * as console from "std/console"
            function markB(label: string, v: boolean): boolean { console.log(label); return v; }
            export function test(): null {
              let bs: boolean[] = [true];
              if (markB("lhs", true) === (bs[-1] && true)) { console.log("bad"); }
            }
            """, "arrplainlhserrnilaware");
        check(runNA.exitCode() == 1
                && runNA.output().contains("lhs")
                && runNA.output().contains("DEAL_ERROR_CODE: E8002")
                && !runNA.output().contains("bad"),
            "nil-aware && right operand: 'lhs' prints before the right "
            + "operand's read E8002: " + runNA.output());

        // Both-raise precedence: the left operand's checked arithmetic
        // must raise E8004 before the right read's E8002.
        ExecResult both = compileAndRunJvm("""
            import * as console from "std/console"
            export function test(): null {
              let xs: int[] = [1];
              if ((9007199254740991 + 1) === xs[-1]) { console.log("bad"); }
            }
            """, "arrplainlhsbothraise");
        check(both.exitCode() == 1
                && both.output().contains("DEAL_ERROR_CODE: E8004")
                && !both.output().contains("E8002")
                && !both.output().contains("bad"),
            "both-raise precedence: the left arithmetic's E8004 raises "
            + "first, never the read's E8002: " + both.output());

        // Positive control: an in-bounds right read with a hoisting
        // index — lhs, h, i, eq-ok (the left call still evaluates
        // completely first).
        ExecResult ok = compileAndRunJvm("""
            import * as console from "std/console"
            function mark(label: string, v: int): int { console.log(label); return v; }
            function pick(label: string, z: null): int { console.log(label); return 0; }
            export function test(): null {
              let xs: int[] = [1];
              let zs: int[] = [5];
              if (mark("lhs", 5) === zs[pick("i", console.log("h"))]) { console.log("eq-ok"); }
            }
            """, "arrplainlhsinbounds");
        check(ok.exitCode() == 0
                && ok.output().contains("lhs\nh\ni\neq-ok")
                && !ok.output().contains("bad"),
            "in-bounds positive control prints lhs, h, i, eq-ok: "
                + ok.output());

        // The JVM's spec order for an effectful right receiver and index
        // (receiver before index, §Operational semantics rule 1):
        // lhs, made, idx — LuaJIT emits the index first (documented
        // divergence), so this shape is pinned JVM-only.
        ExecResult recv = compileAndRunJvm("""
            import * as console from "std/console"
            function mark(label: string, v: int): int { console.log(label); return v; }
            function getArr(label: string, xs: int[]): int[] { console.log(label); return xs; }
            export function test(): null {
              let xs: int[] = [1];
              if (mark("lhs", 5) === getArr("made", xs)[mark("idx", 99)]) { console.log("bad"); }
            }
            """, "arrplainlhsrecv");
        check(recv.exitCode() == 0
                && recv.output().contains("lhs\nmade\nidx")
                && !recv.output().contains("bad"),
            "effectful right receiver/index order is lhs, made, idx "
            + "(past-end read yields nil; 5 === nil is false): "
                + recv.output());

        // Emission shapes: the effectful left operand's materialization
        // pre-statement is anchored BEFORE the right read's boxed helper
        // call (int and checked-arithmetic shapes), a pure left operand
        // is NOT materialized (the comparison references the literal
        // directly), and no lambda is ever emitted.
        Frontend f = compileFrontend("""
            import * as console from "std/console"
            function mark(label: string, v: int): int { console.log(label); return v; }
            export function test(): null {
              let xs: int[] = [1];
              if (mark("lhs", 5) === xs[-1]) { console.log("bad"); }
              if ((9007199254740991 + 1) === xs[-1]) { console.log("bad2"); }
              if (5 === xs[99]) { console.log("bad3"); }
            }
            """, "jvmtest-arrplainlhs-order-emission.deal");
        check(f.errors().isEmpty(), "plain-left order emission frontend clean: " + f.errors());
        if (f.errors().isEmpty()) {
            JvmBackend.JvmCodegenResult res = JvmBackend.generate(
                f.program(), f.checkResult(),
                "jvmtest-arrplainlhs-order-emission.deal", "main");
            check(!res.hasErrors(), "plain-left order emission codegen clean: "
                + res.diagnostics());
            if (!res.hasErrors()) {
                String java = res.source();
                int markIdx = java.indexOf("long __t0 = mark(\"lhs\", 5L);");
                int read1Idx = java.indexOf(
                    "__intArrayReadBoxed(xs, intNeg(1L));");
                int addIdx = java.indexOf(
                    "intAdd(9007199254740991L, 1L);");
                int read2Idx = read1Idx >= 0
                    ? java.indexOf("__intArrayReadBoxed(xs, intNeg(1L));",
                        read1Idx + 1)
                    : -1;
                check(markIdx >= 0 && read1Idx >= 0 && addIdx >= 0
                        && markIdx < read1Idx && addIdx < read2Idx,
                    "each effectful left operand's materialization lands "
                    + "before ITS right read's boxed helper call (mark "
                    + "before the first E8002 read, the checked add "
                    + "before the second E8002 read): " + java);
                check(java.matches(
                        "(?s).*5L == __t\\d+\\.longValue\\(\\).*")
                        && !java.matches("(?s).*long __t\\d+ = 5L;.*"),
                    "a pure literal left operand is not materialized — "
                    + "the comparison references the literal directly "
                    + "and no 'long __t = 5L' pre-statement exists: "
                        + java);
                check(!java.contains(" -> "),
                    "no lambda emitted for the plain-left shapes (spaced "
                    + "arrow; wrapper descriptor strings may carry the "
                    + "unspaced arrow glyph): " + java);
            }
        }
    }

    /** Out-of-slice array shapes are rejected with E6000, never silently
     * miscompiled: nested (multi-dimensional) arrays, arrays of nullable
     * table elements, function arrays, table indexing (read and write),
     * and array element types coming from inferred literals of
     * unsupported element types. (The ISSUE-0108 nullable slice brings
     * nullable arrays, arrays of nullable primitive/class elements, and
     * class arrays in scope.) */
    private static void testArrayUnsupportedElementTypesRejected() {
        System.out.println("-- Unsupported array shapes → E6000 --");

        record Case(String what, String source) {}
        List<Case> cases = List.of(
            new Case("array of nullable table elements", """
                export function test(): null {
                  let xs: (table | null)[] = [];
                }
                """)
        );

        for (Case c : cases) {
            Frontend f = compileFrontend(c.source, "jvmtest-unsupported-arr.deal");
            if (!f.errors().isEmpty()) {
                fail("frontend must accept unsupported array case '" + c.what()
                    + "' (the backend rejects it): " + f.errors());
                continue;
            }
            JvmBackend.JvmCodegenResult res = JvmBackend.generate(
                f.program(), f.checkResult(),
                "jvmtest-unsupported-arr.deal", "main");
            check(res.hasErrors(), "backend rejects " + c.what());
            check(res.diagnostics().stream().anyMatch(d -> "E6000".equals(d.code())),
                "E6000 diagnostic for " + c.what() + ": " + res.diagnostics());
        }

        // ISSUE-0102: nested arrays, function arrays, and table
        // reads/writes promoted out of the E6000 rejection list.
        List<Case> promoted = List.of(
            new Case("nested array literal", """
                export function test(): int {
                  let rows: int[][] = [[1, 2], [3, 4]];
                  return rows[0][1];
                }
                """),
            new Case("array of function elements", """
                function add1(x: int): int { return x + 1; }
                export function test(): int {
                  let fs: ((x: int) => int)[] = [add1];
                  return fs[0](3);
                }
                """),
            new Case("table index read", """
                export function test(): int {
                  let t: table = {};
                  return t.x;
                }
                """),
            new Case("table index write", """
                export function test(): null {
                  let t: table = {};
                  t.x = 1;
                }
                """)
        );
        for (Case c : promoted) {
            Frontend f = compileFrontend(c.source, "jvmtest-unsupported-arr.deal");
            if (!f.errors().isEmpty()) {
                fail("frontend must accept promoted array case '" + c.what()
                    + "': " + f.errors());
                continue;
            }
            JvmBackend.JvmCodegenResult res = JvmBackend.generate(
                f.program(), f.checkResult(),
                "jvmtest-unsupported-arr.deal", "main");
            check(!res.hasErrors(),
                "backend now accepts " + c.what() + ": " + res.diagnostics());
        }
    }

    /** Use-before-declaration guards walk array value positions: a
     * module-level call whose (transitive) body reads a later-declared
     * field through an index, an array literal, or a .length read is
     * E6000 (LuaJIT reads the global nil at load; Java would read the
     * default wrapper); a function reading a field declared after the
     * function through an index is E6000 (write-dominance analysis); an
     * assignment whose index expression references a later-declared local
     * is E6000 (Java cannot-find-symbol); an index write whose array
     * identifier is a later-declared local is E6000. */
    private static void testArrayUseBeforeDeclarationGuards() {
        // v1.2 grammar gate: module-field shapes (a module-level let) are
        // frontend parse errors (E1049) before the backend's
        // use-before-declaration analysis — module fields were removed.
        // The in-function guard shapes keep their E6000 backend rejection.
        record Case(String what, String source) {}
        List<Case> cases = List.of(
            new Case("index expression referencing later local", """
                export function test(): int {
                  let xs: int[] = [1, 2];
                  xs[z] = 9;
                  let z: int = 0;
                  return xs[0];
                }
                """),
            new Case("array identifier referencing later local in write", """
                export function test(): int {
                  x[0] = 9;
                  let x: int[] = [1, 2];
                  return 0;
                }
                """)
        );

        for (Case c : cases) {
            Frontend f = compileFrontend(c.source, "jvmtest-arr-undeclared.deal");
            if (!f.errors().isEmpty()) {
                fail("frontend must accept unsupported array guard case '"
                    + c.what() + "' (the backend rejects it): " + f.errors());
                continue;
            }
            JvmBackend.JvmCodegenResult res = JvmBackend.generate(
                f.program(), f.checkResult(),
                "jvmtest-arr-undeclared.deal", "main");
            check(res.hasErrors(), "backend rejects " + c.what());
            check(res.diagnostics().stream().anyMatch(d -> "E6000".equals(d.code())),
                "E6000 diagnostic for " + c.what() + ": " + res.diagnostics());
        }

        List<String> moduleShapes = List.of(
            """
            let xs: int[] = [1, 2];
            f();
            function f(): int { return xs[0]; }
            export function test(): int { return 1; }
            """,
            """
            f();
            let xs: int[] = [1, 2];
            function f(): int { return xs.length; }
            export function test(): int { return 1; }
            """,
            """
            f();
            let a: int = 1;
            function f(): int[] { return [a, 2]; }
            export function test(): int { return 1; }
            """,
            """
            function f(): int { return xs[0]; }
            let xs: int[] = [1, 2];
            export function test(): int { return f(); }
            """,
            """
            function f(): int { xs[0] = 9; return 0; }
            let xs: int[] = [1, 2];
            export function test(): int { return f(); }
            """,
            """
            function f(): int { xs[xs.length] = 9; return 0; }
            let xs: int[] = [1, 2];
            export function test(): int { return f(); }
            """,
            """
            let xs: int[] = [1, 2];
            function bump(): int { xs[0] = 9; return xs[0]; }
            export function test(): int { return bump() + xs.length; }
            """
        );
        for (String shape : moduleShapes) {
            Frontend f = compileFrontend(shape, "jvmtest-arr-module-shape.deal");
            check(f.errors().stream().anyMatch(d -> "E1049".equals(d.code())),
                "module-field array shape rejected with E1049: " + f.errors());
        }
    
    }

    /**
     * ISSUE-0106 v1.2 entry-module invocation: when a compilation selects
     * an entry module, the backend must invoke {@code main()} from that
     * module (spec-v1.2 §No user-defined globals). The emitted entry
     * class gets a real JVM entry point — {@code public static void
     * main(String[] args)} — that calls the DEAL {@code main} export, so
     * running {@code java <Class>} executes the module's main without any
     * test runner.
     */
    private static void testEntryModuleEmitsJvmEntryPoint() throws Exception {
        System.out.println("-- Entry module emits the JVM entry point --");

        Frontend f = compileFrontend("""
            import * as console from "std/console"
            export function main(): null {
              console.log("entry-main-ran");
              return null;
            }
            export function helper(x: int): int { return x + 1; }
            """, "jvmtest-entry.deal");
        check(f.errors().isEmpty(), "entry frontend clean: " + f.errors());
        if (!f.errors().isEmpty()) return;

        JvmBackend.JvmCodegenResult res = JvmBackend.generate(
            f.program(), f.checkResult(), "jvmtest-entry.deal", "main",
            Map.of(), Map.of(), Map.of(), true);
        check(!res.hasErrors(), "entry codegen clean: " + res.diagnostics());
        if (res.hasErrors()) return;
        check(res.source().contains("public static void main(java.lang.String[] args)"),
            "entry module emits the JVM entry point");
        check(res.source().contains("    main();"),
            "the entry point invokes the DEAL main export");

        // javac + run `java Main` directly: the backend-emitted entry point
        // (not the conformance runner) drives the invocation.
        Path dir = Files.createTempDirectory("jvmtest_entry_");
        Files.writeString(dir.resolve("Main.java"), res.source());
        StringBuilder javacErr = new StringBuilder();
        boolean javacOk = BackendConformanceTest.compileWithJavac(dir,
            List.of("Main.java"), javacErr);
        check(javacOk, "entry artifact compiles: " + javacErr);
        if (javacOk) {
            ProcessBuilder java = new ProcessBuilder("java", "-cp",
                dir.toString(), "Main");
            java.redirectErrorStream(true);
            Process p = java.start();
            String out = new String(p.getInputStream().readAllBytes()).trim();
            int exit = p.waitFor();
            check(exit == 0, "java Main exits 0 (the emitted entry point runs)");
            check(out.contains("entry-main-ran"),
                "main() side effect observed through the emitted entry point: " + out);
        }
        try {
            Files.walk(dir).sorted(Comparator.reverseOrder())
                .forEach(p2 -> { try { Files.deleteIfExists(p2); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}

        // Non-entry modules emit no JVM entry point.
        JvmBackend.JvmCodegenResult lib = JvmBackend.generate(
            f.program(), f.checkResult(), "jvmtest-entry.deal", "main");
        check(!lib.hasErrors() && !lib.source()
                .contains("public static void main(java.lang.String[] args)"),
            "non-entry module emits no JVM entry point");
    }

    /** The JVM backend's entry-point backstop (spec-v1.2 §No user-defined
     * globals): when a compilation selects this module as the entry module,
     * the emitted entry point invokes the DEAL main export; a missing main,
     * a non-exported main, an async main, or a non-null return type is an
     * E6004 diagnostic (the same backend gate the Lua backend raises)
     * instead of a silently broken artifact. The frontend E2010/E2011
     * gate (the orchestrator's selected-entry rule) is pinned by
     * ModuleSystemTest; this pins the JVM emission path. */
    private static void testEntryGateBackendE6004() {
        System.out.println("-- Entry gate E6004 (JVM backend) --");

        List<String> bad = List.of(
            "export function helper(): int { return 1; }",
            "function main(): null { return null; }",
            "export async function main(): null { return null; }",
            "export function main(): int { return 1; }");
        for (String source : bad) {
            Frontend f = compileFrontend(source, "jvmtest-entrygate.deal");
            check(f.errors().isEmpty(), "entry-gate probe frontend clean: "
                + f.errors());
            if (f.errors().isEmpty()) {
                JvmBackend.JvmCodegenResult res = JvmBackend.generate(
                    f.program(), f.checkResult(), "jvmtest-entrygate.deal",
                    "main", Map.of(), Map.of(), Map.of(), true);
                check(res.hasErrors() && res.diagnostics().stream()
                        .anyMatch(d -> "E6004".equals(d.code())),
                    "entry gate E6004: " + res.diagnostics());
            }
        }

        Frontend ok = compileFrontend(
            "export function main(): null { return null; }",
            "jvmtest-entrygate-ok.deal");
        check(ok.errors().isEmpty(), "conforming main(): null passes the gate: "
            + ok.errors());
        if (ok.errors().isEmpty()) {
            JvmBackend.JvmCodegenResult okRes = JvmBackend.generate(
                ok.program(), ok.checkResult(), "jvmtest-entrygate-ok.deal",
                "main", Map.of(), Map.of(), Map.of(), true);
            check(!okRes.hasErrors()
                    && okRes.source().contains("public static void main(java.lang.String[] args)"),
                "conforming entry module emits the JVM entry point");
        }
    }

    /**
     * ISSUE-0106 v1.2 Unicode scalar-value string for-of: each iteration
     * yields one string containing exactly one scalar value, in order —
     * a supplementary character (U+1F600) is ONE iteration, not two UTF-16
     * code units.
     */
    private static void testStringForOfScalarIteration() throws Exception {
        System.out.println("-- String for-of iterates Unicode scalar values --");

        ExecResult res = compileAndRunJvm("""
            import * as console from "std/console"
            export function test(): null {
              let acc: string = "";
              let count: int = 0;
              for (let c: string of "a\ud83d\ude00b") {
                acc = acc + c;
                count = count + 1;
              }
              console.log(acc);
              if (count !== 3) { console.log("bad-count"); return; }
              if (acc !== "a\ud83d\ude00b") { console.log("bad-acc"); return; }
              console.log("for-of-scalar-ok");
            }
            """, "forof-scalar");
        check(res.exitCode() == 0, "string for-of run exits 0: " + res.output());
        check(res.output().contains("for-of-scalar-ok"),
            "string for-of iterates three scalar values: " + res.output());

        // A string for-of over an ASCII string concatenates the scalar
        // values in order.
        ExecResult ascii = compileAndRunJvm("""
            import * as console from "std/console"
            export function test(): null {
              let acc: string = "";
              for (let c: string of "deal") {
                acc = acc + c;
              }
              console.log(acc);
            }
            """, "forof-ascii");
        check(ascii.exitCode() == 0 && ascii.output().contains("deal"),
            "ASCII for-of concatenates in order: " + ascii.output());

        // Array for-of stays rejected with E6000 (documented slice boundary).
        // ISSUE-0102: array for-of promoted out of the E6000 slice
        // boundary — it iterates the wrapper storage in index order with
        // a fresh per-iteration binding (spec-v1.2 §For-of).
        ExecResult arrRun = compileAndRunJvm("""
            import * as console from "std/console"
            export function test(): null {
              let xs: int[] = [1, 2, 3];
              let total: int = 0;
              for (let x: int of xs) {
                total = total + x;
              }
              if (total !== 6) { console.log("bad-sum"); return; }
              console.log("array-forof-ok");
            }
            """, "jvmtest-forof-array");
        check(arrRun.exitCode() == 0,
            "array for-of runs through javac + java: " + arrRun.output());
        check(arrRun.output().contains("array-forof-ok"),
            "array for-of sums its elements: " + arrRun.output());
    }

    /**
     * ISSUE-0102 rework (BOT-0933 finding 1): array for-of over function
     * elements, nullable-function elements, and nested-array elements was
     * silently miscompiled — {@code arrayForOfReadHelper} returned null
     * for these shapes while {@code javaArrayElementType} accepted them
     * without recording an E6000, so {@code emitArrayForOf} emitted the
     * inert {@code null(__iter0, __i0)} placeholder and the CLI reported
     * success for an artifact javac rejects. The helper now mirrors the
     * {@code emitIndexRead} chain: {@code Type.Func} /
     * {@code Nullable(Type.Func)} / {@code Type.Array} elements route
     * through {@code refArrayReadHelper} (per-shape
     * {@code __fnRead$}/{@code __fnOrNullRead$}/{@code __nestedRead$}
     * helpers with E6000 diagnostics for unsupported signatures), so the
     * shapes either run correctly or the backend rejects the program —
     * never a broken artifact after the CLI reported success.
     */
    private static void testArrayForOfRefElementShapes() throws Exception {
        System.out.println("-- Array for-of: function / nested / nullable-function elements --");

        // Runtime: for-of over a function array invokes every element.
        ExecResult fn = compileAndRunJvm("""
            import * as console from "std/console"
            function add1(x: int): int { return x + 1; }
            export function test(): null {
              let fs: ((x: int) => int)[] = [add1, add1];
              let total: int = 0;
              for (let f: (x: int) => int of fs) {
                total = total + f(total);
              }
              if (total !== 3) { console.log("bad-total"); return; }
              console.log("fnarr-forof-ok");
            }
            """, "forof-fnarr");
        check(fn.exitCode() == 0,
            "function-array for-of runs through javac + java: " + fn.output());
        check(fn.output().contains("fnarr-forof-ok"),
            "function-array for-of invokes each element: " + fn.output());

        // Runtime: for-of over a nested primitive array reads each row.
        ExecResult nested = compileAndRunJvm("""
            import * as console from "std/console"
            export function test(): null {
              let rows: int[][] = [[1, 2], [3, 4]];
              let total: int = 0;
              for (let row: int[] of rows) {
                total = total + row[0] + row[1];
              }
              if (total !== 10) { console.log("bad-total"); return; }
              console.log("nested-forof-ok");
            }
            """, "forof-nested");
        check(nested.exitCode() == 0,
            "nested-array for-of runs through javac + java: " + nested.output());
        check(nested.output().contains("nested-forof-ok"),
            "nested-array for-of sums the rows: " + nested.output());

        // Runtime: for-of over a nullable-function array skips the DEAL
        // null element (Java null from the per-signature read).
        ExecResult orNull = compileAndRunJvm("""
            import * as console from "std/console"
            function add1(x: int): int { return x + 1; }
            export function test(): null {
              let fs: (((x: int) => int) | null)[] = [];
              fs[fs.length] = add1;
              fs[fs.length] = null;
              let total: int = 0;
              for (let f: ((x: int) => int) | null of fs) {
                if (f !== null) { total = total + f(total); }
              }
              if (total !== 1) { console.log("bad-total"); return; }
              console.log("fnornull-forof-ok");
            }
            """, "forof-fnornull");
        check(orNull.exitCode() == 0,
            "nullable-function-array for-of runs through javac + java: "
                + orNull.output());
        check(orNull.output().contains("fnornull-forof-ok"),
            "nullable-function-array for-of skips the null element: "
                + orNull.output());

        // Emission shapes: each supported shape reads through its
        // per-shape helper, never the inert null(...) placeholder.
        Frontend shape = compileFrontend("""
            function add1(x: int): int { return x + 1; }
            export function test(): int {
              let fs: ((x: int) => int)[] = [add1];
              let rows: int[][] = [[1]];
              let nfs: (((x: int) => int) | null)[] = [];
              let total: int = 0;
              for (let f: (x: int) => int of fs) { total = total + 1; }
              for (let row: int[] of rows) { total = total + 1; }
              for (let f: ((x: int) => int) | null of nfs) { total = total + 1; }
              return total;
            }
            """, "jvmtest-forof-refshape.deal");
        check(shape.errors().isEmpty(),
            "ref-shape for-of probe frontend clean: " + shape.errors());
        if (shape.errors().isEmpty()) {
            JvmBackend.JvmCodegenResult er = JvmBackend.generate(
                shape.program(), shape.checkResult(),
                "jvmtest-forof-refshape.deal", "main");
            check(!er.hasErrors(),
                "ref-shape for-of codegen clean: " + er.diagnostics());
            if (!er.hasErrors()) {
                check(er.source().contains("__fnRead$Fn1_I_R_I"),
                    "function-array for-of uses the per-signature read helper");
                check(er.source().contains("__nestedRead$__IntArray"),
                    "nested-array for-of uses the per-shape read helper");
                check(er.source().contains("__fnOrNullRead$Fn1_I_R_I"),
                    "nullable-function-array for-of uses the per-signature "
                        + "read helper");
                check(!er.source().contains("null(__iter"),
                    "no inert null(...) placeholder in the for-of reads");
            }
        }

        // Rejection: for-of over an unsupported function signature or a
        // nested array of function elements records E6000 (no artifact a
        // javac step would reject after the CLI reported success).
        Frontend badSig = compileFrontend("""
            function bad(xs: int[]): int { return 1; }
            export function test(): int {
              let fs: ((xs: int[]) => int)[] = [];
              for (let f: (xs: int[]) => int of fs) { }
              return 0;
            }
            """, "jvmtest-forof-badsig.deal");
        check(badSig.errors().isEmpty(),
            "unsupported-signature for-of probe frontend clean: "
                + badSig.errors());
        if (badSig.errors().isEmpty()) {
            JvmBackend.JvmCodegenResult er = JvmBackend.generate(
                badSig.program(), badSig.checkResult(),
                "jvmtest-forof-badsig.deal", "main");
            check(er.hasErrors(), "unsupported-signature for-of rejected");
            check(er.diagnostics().stream()
                    .anyMatch(d -> "E6000".equals(d.code())),
                "E6000 for the unsupported-signature for-of: "
                    + er.diagnostics());
        }
        Frontend badNested = compileFrontend("""
            function add1(x: int): int { return x + 1; }
            export function test(): int {
              let fss: (((x: int) => int)[][]) = [];
              for (let fs: ((x: int) => int)[] of fss) { }
              return 0;
            }
            """, "jvmtest-forof-badnested.deal");
        check(badNested.errors().isEmpty(),
            "nested-function-array for-of probe frontend clean: "
                + badNested.errors());
        if (badNested.errors().isEmpty()) {
            JvmBackend.JvmCodegenResult er = JvmBackend.generate(
                badNested.program(), badNested.checkResult(),
                "jvmtest-forof-badnested.deal", "main");
            check(er.hasErrors(), "nested-function-array for-of rejected");
            check(er.diagnostics().stream()
                    .anyMatch(d -> "E6000".equals(d.code())),
                "E6000 for the nested-function-array for-of: "
                    + er.diagnostics());
        }

        // The real orchestrator pipeline (frontend → codegen → artifacts):
        // the supported shapes compile with success=true and ZERO
        // diagnostics, and the emitted artifacts run through javac + java.
        writeFile("src/refof_main.deal", """
            export function main(): null { return null; }
            function add1(x: int): int { return x + 1; }
            export function run(): int {
              let fs: ((x: int) => int)[] = [add1];
              let rows: int[][] = [[1, 2], [3, 4]];
              let total: int = 0;
              for (let f: (x: int) => int of fs) {
                total = total + f(total);
              }
              for (let row: int[] of rows) {
                total = total + row[0];
              }
              return total;
            }
            """);
        Path entryFile = tmpDir.resolve("src/refof_main.deal").toAbsolutePath();
        Path outputDir = tmpDir.resolve("build/refof");
        List<Path> roots = List.of(tmpDir.resolve("src").toAbsolutePath());
        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            entryFile, outputDir, false, false, false, Backend.JVM,
            (DealConfig) null, roots,
            Path.of(".").toAbsolutePath().normalize());
        boolean success = orchestrator.compile();
        check(success, "for-of over function/nested arrays compiles through "
            + "the orchestrator: " + orchestrator.diagnostics());
        check(orchestrator.diagnostics().isEmpty(),
            "zero diagnostics for the supported ref-shape for-of: "
                + orchestrator.diagnostics());
        check(Files.exists(outputDir.resolve("Refof_main.java")),
            "the ref-shape for-of entry module writes its artifact");
        if (success && Files.exists(outputDir.resolve("Refof_main.java"))) {
            ExecResult exec = runJvmArtifacts(outputDir,
                parseProgram("export function run(): int { return 1; }"),
                "Refof_main");
            check(exec.exitCode() == 0 && exec.output().contains("5"),
                "the orchestrator artifacts run: f(0)=1 then +1+3 = 5: "
                    + exec.output());
        }

        // The unsupported-signature for-of fails the orchestrator with
        // E6000 and writes no entry artifact (the slice contract: E6000,
        // never a broken artifact).
        writeFile("src2/refof_bad.deal", """
            export function main(): null { return null; }
            function bad(xs: int[]): int { return 1; }
            export function run(): int {
              let fs: ((xs: int[]) => int)[] = [];
              for (let f: (xs: int[]) => int of fs) { }
              return 0;
            }
            """);
        Path badEntry = tmpDir.resolve("src2/refof_bad.deal").toAbsolutePath();
        Path badOut = tmpDir.resolve("build/refof_bad");
        List<Path> roots2 = List.of(tmpDir.resolve("src2").toAbsolutePath());
        CompilationOrchestrator badOrchestrator = new CompilationOrchestrator(
            badEntry, badOut, false, false, false, Backend.JVM,
            (DealConfig) null, roots2,
            Path.of(".").toAbsolutePath().normalize());
        boolean badSuccess = badOrchestrator.compile();
        check(!badSuccess,
            "the unsupported-signature for-of fails the orchestrator: "
                + badOrchestrator.diagnostics());
        check(badOrchestrator.diagnostics().stream()
                .anyMatch(d -> "E6000".equals(d.code())),
            "orchestrator reports E6000 for the unsupported-signature "
                + "for-of: " + badOrchestrator.diagnostics());
        check(!Files.exists(badOut.resolve("Refof_bad.java")),
            "no entry artifact when the ref-shape for-of is rejected");

        // Backend-boundary pin: the for-of E6000 arrives through the
        // transitional ranged channel and renders at its real source
        // position — SOURCE origin with exact scalar offsets, never
        // synthetic (1,1). The unsupported call anchors at the for-of
        // statement's own span.
        CompilerDiagnostic e6000 = badOrchestrator.diagnostics().stream()
            .filter(d -> "E6000".equals(d.code())
                && d.message().contains("function arrays whose signature"))
            .findFirst().orElse(null);
        check(e6000 != null, "for-of E6000 present for the boundary pin");
        if (e6000 != null) {
            DiagnosticRange range = e6000.range();
            check(range.origin() == RangeOrigin.SOURCE,
                "backend-boundary E6000 range origin is SOURCE: " + range);
            String src = Files.readString(badEntry);
            int forIdx = src.indexOf("for (let f");
            check(forIdx >= 0, "fixture contains the for-of statement");
            int lineStart = src.lastIndexOf('\n', forIdx) + 1;
            int expectedLine = src.substring(0, forIdx).split("\n", -1).length;
            int expectedColumn = forIdx - lineStart + 1;
            int expectedOffset = ScalarSourceCursor.scalarCount(src, 0, forIdx);
            check(range.startLine() == expectedLine
                    && range.startColumn() == expectedColumn,
                "backend-boundary E6000 starts at the for-of statement: "
                    + range);
            check(range.startScalarOffset() == expectedOffset,
                "backend-boundary E6000 start scalar offset is exact ("
                    + expectedOffset + "): " + range);
            check(range.endScalarOffset() > range.startScalarOffset()
                    && range.scalarLength()
                        == range.endScalarOffset() - range.startScalarOffset(),
                "backend-boundary E6000 spans the for-of statement: "
                    + range);
            String formatted = DiagnosticFormatter.format(e6000);
            check(formatted.contains("[span "),
                "the new formatter renders the backend diagnostic span: "
                    + formatted);
        }
    }

    /** A catch variable captured by a nested function inside the catch
     * block must cell-ify exactly like a captured local or parameter
     * (BOT-0942 finding 2). collectLocalDeclarations declares the catch
     * variable, so the function-level capture analysis marks it captured
     * and declareLocal registers the mapped name; emitTry then declares
     * the final cell at the top of the catch block. Reads and writes
     * route through e$c[0], the closure references the effectively-final
     * cell array, and a DEAL-level reassignment of the catch variable
     * writes the cell — LuaJIT's shared-upvalue semantics without javac's
     * "local variables referenced from an inner class must be final or
     * effectively final" rejection after the CLI reported success. */
    private static void testCatchVarCapturedByNestedFunction() throws Exception {
        System.out.println("-- Catch variable captured by a nested function (javac + java) --");

        // Runtime: the reviewer's probe — a closure reading the catch
        // variable's .code created BEFORE the catch variable is
        // reassigned must observe the NEW value (shared upvalue).
        ExecResult captured = compileAndRunJvm("""
            import * as console from "std/console"
            export function test(): null {
              let ok: string = "no";
              try {
                throw { code: "E1", message: "first" };
              } catch (e) {
                let f: () => string = function(): string { return e.code; };
                e = { code: "E2", message: "second" };
                ok = f();
              }
              if (ok !== "E2") { console.log("bad-code"); return; }
              console.log("catch-cell-ok");
            }
            """, "catchvarcapture");
        check(captured.exitCode() == 0,
            "captured catch variable exits 0 (javac accepted the artifact): "
                + captured.output());
        check(captured.output().contains("catch-cell-ok"),
            "the closure reads the REASSIGNED catch variable's code (E2): "
                + captured.output());

        // Runtime: the same shape where the closure WRITES the catch
        // variable and the later catch-block code reads the new value.
        ExecResult write = compileAndRunJvm("""
            import * as console from "std/console"
            export function test(): null {
              let seen: string = "no";
              try {
                throw { code: "E1", message: "first" };
              } catch (e) {
                let set: () => null = function(): null {
                  e = { code: "E3", message: "third" };
                };
                set();
                seen = e.code;
              }
              if (seen !== "E3") { console.log("bad-seen"); return; }
              console.log("catch-cell-write-ok");
            }
            """, "catchvarwrite");
        check(write.exitCode() == 0,
            "closure-written catch variable exits 0: " + write.output());
        check(write.output().contains("catch-cell-write-ok"),
            "the closure's catch-variable write is visible to the catch "
                + "block: " + write.output());

        // Emission shape: the catch variable lowers to a final cell
        // declared at the top of the catch block; the closure references
        // the cell; the DEAL-level reassignment writes the cell.
        Frontend f = compileFrontend("""
            export function test(): null {
              try {
                throw { code: "E1", message: "first" };
              } catch (e) {
                let f: () => string = function(): string { return e.code; };
                e = { code: "E2", message: "second" };
              }
            }
            """, "jvmtest-catchvar-capture.deal");
        check(f.errors().isEmpty(),
            "catch-var capture probe frontend clean: " + f.errors());
        if (f.errors().isEmpty()) {
            JvmBackend.JvmCodegenResult res = JvmBackend.generate(
                f.program(), f.checkResult(),
                "jvmtest-catchvar-capture.deal", "main");
            check(!res.hasErrors(),
                "catch-var capture probe codegen clean: " + res.diagnostics());
            if (!res.hasErrors()) {
                check(res.source().contains(
                        "final java.lang.RuntimeException[] e$c = { e };"),
                    "the captured catch variable declares its final cell at "
                        + "the top of the catch block");
                check(res.source().contains("return __errorCode(e$c[0]);"),
                    "the closure reads the catch variable through the cell");
                check(res.source().contains(
                        "e$c[0] = new DealError(\"E2\", \"second\");"),
                    "the DEAL-level reassignment writes the cell");
            }
        }

        // Control: a catch variable NO nested function references stays a
        // plain catch parameter — no cell is emitted.
        Frontend plainCatch = compileFrontend("""
            export function test(): string {
              let code: string = "";
              try {
                throw { code: "E1", message: "first" };
              } catch (e) {
                code = e.code;
              }
              return code;
            }
            """, "jvmtest-catchvar-plain.deal");
        check(plainCatch.errors().isEmpty(),
            "plain catch probe frontend clean: " + plainCatch.errors());
        if (plainCatch.errors().isEmpty()) {
            JvmBackend.JvmCodegenResult res = JvmBackend.generate(
                plainCatch.program(), plainCatch.checkResult(),
                "jvmtest-catchvar-plain.deal", "main");
            check(!res.hasErrors(),
                "plain catch probe codegen clean: " + res.diagnostics());
            if (!res.hasErrors()) {
                check(!res.source().contains(
                        "final java.lang.RuntimeException[] e$c"),
                    "an uncaptured catch variable emits no cell");
            }
        }

        // The real orchestrator pipeline (frontend → codegen → artifacts):
        // the captured catch-variable shape compiles with success=true and
        // zero diagnostics, and the emitted artifacts run through javac +
        // java printing the reassigned code.
        writeFile("src/catchcap_main.deal", """
            import * as console from "std/console"
            export function main(): null { return null; }
            export function run(): null {
              let ok: string = "no";
              try {
                throw { code: "E1", message: "first" };
              } catch (e) {
                let f: () => string = function(): string { return e.code; };
                e = { code: "E2", message: "second" };
                ok = f();
              }
              if (ok !== "E2") { console.log("bad-code"); return; }
              console.log("catch-cell-ok");
            }
            """);
        Path catchEntry =
            tmpDir.resolve("src/catchcap_main.deal").toAbsolutePath();
        Path catchOut = tmpDir.resolve("build/catchcap");
        List<Path> catchRoots =
            List.of(tmpDir.resolve("src").toAbsolutePath());
        CompilationOrchestrator catchOrchestrator = new CompilationOrchestrator(
            catchEntry, catchOut, false, false, false, Backend.JVM,
            (DealConfig) null, catchRoots,
            Path.of(".").toAbsolutePath().normalize());
        boolean catchSuccess = catchOrchestrator.compile();
        check(catchSuccess,
            "captured catch-variable program compiles through the "
                + "orchestrator: " + catchOrchestrator.diagnostics());
        check(catchOrchestrator.diagnostics().isEmpty(),
            "zero diagnostics for the captured catch-variable program: "
                + catchOrchestrator.diagnostics());
        check(Files.exists(catchOut.resolve("Catchcap_main.java")),
            "the captured catch-variable entry module writes its artifact");
        if (catchSuccess
                && Files.exists(catchOut.resolve("Catchcap_main.java"))) {
            ExecResult exec = runJvmArtifacts(catchOut,
                parseProgram("export function run(): null { return null; }"),
                "Catchcap_main");
            check(exec.exitCode() == 0
                    && exec.output().contains("catch-cell-ok"),
                "the orchestrator artifacts run and print the reassigned "
                    + "code: " + exec.output());
        }
    }

    /**
     * ISSUE-0106 v1.2 boundary string validation: a string crossing an
     * untyped boundary (a table read) must be a java.lang.String with no
     * unpaired UTF-16 surrogate code units — the JVM string representation
     * contract (spec-v1.2 §String escapes and Unicode / §JVM value
     * mapping). Valid scalar strings (including supplementary characters)
     * pass; the emitted check helper carries the surrogate scan.
     */
    private static void testBoundaryStringValidation() throws Exception {
        System.out.println("-- Boundary string validation (unpaired surrogates) --");

        ExecResult res = compileAndRunJvm("""
            import * as console from "std/console"
            export function test(): null {
              let t: table = { s: "a\ud83d\ude00b" };
              let s: string = t.s;
              console.log(s);
              if (s !== "a\ud83d\ude00b") { console.log("bad-read"); return; }
              console.log("boundary-ok");
            }
            """, "boundary-string");
        check(res.exitCode() == 0, "boundary string run exits 0: " + res.output());
        check(res.output().contains("boundary-ok"),
            "a scalar-valid supplementary string passes the table boundary: "
                + res.output());

        // Emission shape: the boundary check scans for unpaired surrogates.
        Frontend f = compileFrontend("""
            export function test(): string {
              let t: table = { s: "x" };
              let s: string = t.s;
              return s;
            }
            """, "jvmtest-boundary-emission.deal");
        check(f.errors().isEmpty(), "boundary probe frontend clean: " + f.errors());
        if (f.errors().isEmpty()) {
            JvmBackend.JvmCodegenResult er = JvmBackend.generate(
                f.program(), f.checkResult(), "jvmtest-boundary-emission.deal", "main");
            check(!er.hasErrors(), "boundary probe codegen clean: " + er.diagnostics());
            if (!er.hasErrors()) {
                check(er.source().contains("$check(\"string\","),
                    "non-nullable string table reads run the $check string branch");
                check(er.source().contains("__hasUnpairedSurrogate"),
                    "the seam's string branch scans for unpaired surrogate code units");
                check(er.source().contains(
                        "expected string, got string with unpaired surrogate code units"),
                    "the rejection message names the invalid encoding");
            }
        }
    }

    /** Null-typed return expressions keep their side effects (ISSUE-0091
     * rework: `return console.log("x")` must print "x", never be discarded). */
    private static void testNullReturnSideEffects() throws Exception {
        System.out.println("-- Null-typed return side effects (javac + java) --");

        ExecResult direct = compileAndRunJvm("""
            import * as console from "std/console"
            export function test(): null { return console.log("x"); }
            """, "nullret");
        check(direct.exitCode() == 0, "return console.log(...) exits 0");
        check(direct.output().contains("x"),
            "return console.log(...) prints 'x': " + direct.output());

        ExecResult viaHelper = compileAndRunJvm("""
            import * as console from "std/console"
            function helper(): null { console.log("helper-ran"); }
            export function test(): null { return helper(); }
            """, "nullret2");
        check(viaHelper.exitCode() == 0, "return helper() exits 0");
        check(viaHelper.output().contains("helper-ran"),
            "return helper() prints 'helper-ran': " + viaHelper.output());

        // A parenthesized assignment is not a valid Java expression
        // statement (JLS §14.8) — the emitted return must use the
        // unparenthesized assignment core.
        ExecResult assignReturn = compileAndRunJvm("""
            import * as console from "std/console"
            export function test(): null {
              let z: null = null;
              return z = console.log("assign-return");
            }
            """, "nullret3");
        check(assignReturn.exitCode() == 0, "assignment-in-return exits 0");
        check(assignReturn.output().contains("assign-return"),
            "assignment-in-return prints: " + assignReturn.output());
    }

    /** Void-returning calls in null-typed initializers/assignments/arguments
     * are hoisted into pre-statements and lowered to stmt + null, never to
     * invalid Java and never through a lambda. */
    private static void testNullTypedInitializers() throws Exception {
        System.out.println("-- Null-typed initializers/assignments/arguments (javac + java) --");

        ExecResult inits = compileAndRunJvm("""
            import * as console from "std/console"
            function helper(): null { console.log("helper-ran"); }
            export function test(): null {
              let z: null = console.log("assign-log");
              let w: null = helper();
              return;
            }
            """, "nullinits");
        check(inits.exitCode() == 0, "null-typed initializers exit 0");
        check(inits.output().contains("assign-log"),
            "console.log initializer prints: " + inits.output());
        check(inits.output().contains("helper-ran"),
            "helper() initializer prints: " + inits.output());

        ExecResult assign = compileAndRunJvm("""
            import * as console from "std/console"
            export function test(): null {
              let z: null = null;
              z = console.log("assign-stmt");
              return;
            }
            """, "nullassign");
        check(assign.exitCode() == 0, "null-typed assignment exits 0");
        check(assign.output().contains("assign-stmt"),
            "null-typed assignment prints: " + assign.output());

        ExecResult arg = compileAndRunJvm("""
            import * as console from "std/console"
            function pass(x: null): null { return x; }
            export function test(): null {
              let y: null = pass(console.log("arg-log"));
              return;
            }
            """, "nullarg");
        check(arg.exitCode() == 0, "null-typed call argument exits 0");
        check(arg.output().contains("arg-log"),
            "null-typed call argument prints: " + arg.output());

        // v1.2 grammar gate: module-level null-typed field initializers were
        // removed with module-level lets (E1049).
        Frontend moduleFieldShape = compileFrontend("""
            import * as console from "std/console"
            let z: null = console.log("module-null-field");
            export function test(): int { return 1; }
            """, "jvmtest-nullmodulefield.deal");
        check(moduleFieldShape.errors().stream()
                .anyMatch(d -> "E1049".equals(d.code())),
            "module-level null field rejected with E1049: " + moduleFieldShape.errors());

        // A non-null call used as a STATEMENT whose null-typed argument is
        // hoisted: the argument runs before the call.
        ExecResult stmtWithNullArg = compileAndRunJvm("""
            import * as console from "std/console"
            function consume(x: null): int { console.log("consume-ran"); return 1; }
            export function test(): int {
              consume(console.log("arg-first"));
              return 2;
            }
            """, "nullstmtarg");
        check(stmtWithNullArg.exitCode() == 0, "statement call with null arg exits 0");
        check(stmtWithNullArg.output().contains("arg-first"),
            "hoisted argument runs: " + stmtWithNullArg.output());
        check(stmtWithNullArg.output().contains("consume-ran"),
            "consuming call runs: " + stmtWithNullArg.output());
        check(stmtWithNullArg.output().indexOf("arg-first")
                < stmtWithNullArg.output().indexOf("consume-ran"),
            "argument precedes the call: " + stmtWithNullArg.output());
        check(stmtWithNullArg.output().contains("2"),
            "return value printed: " + stmtWithNullArg.output());
    }

    /**
     * Null-typed calls in value positions (initializers, assignments, call
     * arguments) that capture locals or parameters reassigned anywhere in
     * their enclosing scope. A lambda-based lowering (`nullAnd(() -> call)`)
     * would emit "local variables referenced from a lambda expression must
     * be final or effectively final" javac errors for every one of these —
     * the statement-hoisting lowering must keep them all compiling and
     * running with the correct output order.
     */
    private static void testNullTypedCapturesWithReassignment() throws Exception {
        System.out.println("-- Null-typed captures with reassignment (javac + java) --");

        // The reviewer's exact repro: initializer capture, reassignment after.
        ExecResult initAfter = compileAndRunJvm("""
            import * as console from "std/console"
            export function test(): null {
              let a: string = "x";
              let z: null = console.log(a);
              a = "y";
              return;
            }
            """, "capInitAfter");
        check(initAfter.exitCode() == 0, "initializer capture + later reassignment exits 0");
        check(initAfter.output().contains("x"),
            "initializer capture + later reassignment prints: " + initAfter.output());

        // Reassignment BEFORE the capture also breaks effectively-final.
        ExecResult initBefore = compileAndRunJvm("""
            import * as console from "std/console"
            export function test(): null {
              let a: string = "x";
              a = "y";
              let z: null = console.log(a);
              return;
            }
            """, "capInitBefore");
        check(initBefore.exitCode() == 0, "pre-reassigned local capture exits 0");
        check(initBefore.output().contains("y"),
            "pre-reassigned local capture prints the new value: " + initBefore.output());

        // Capture in a call-argument position, reassignment after.
        ExecResult argCapture = compileAndRunJvm("""
            import * as console from "std/console"
            function pass(x: null): null { return x; }
            export function test(): null {
              let a: string = "x";
              let y: null = pass(console.log(a));
              a = "y";
              return;
            }
            """, "capArg");
        check(argCapture.exitCode() == 0, "argument capture + reassignment exits 0");
        check(argCapture.output().contains("x"),
            "argument capture + reassignment prints: " + argCapture.output());

        // Capture in an assignment value position, reassignment after.
        ExecResult assignCapture = compileAndRunJvm("""
            import * as console from "std/console"
            export function test(): null {
              let a: string = "x";
              let z: null = null;
              z = console.log(a);
              a = "y";
              return;
            }
            """, "capAssign");
        check(assignCapture.exitCode() == 0, "assignment capture + reassignment exits 0");
        check(assignCapture.output().contains("x"),
            "assignment capture + reassignment prints: " + assignCapture.output());

        // Capture of a parameter reassigned after the capture.
        ExecResult paramCapture = compileAndRunJvm("""
            import * as console from "std/console"
            function helper(p: string): null {
              let z: null = console.log(p);
              p = "changed";
            }
            export function test(): null { helper("param-captured"); }
            """, "capParam");
        check(paramCapture.exitCode() == 0, "parameter capture + reassignment exits 0");
        check(paramCapture.output().contains("param-captured"),
            "parameter capture + reassignment prints: " + paramCapture.output());

        // A non-null return whose ARGUMENT captures a reassigned local:
        // the hoisted call must run before the return expression.
        ExecResult returnArg = compileAndRunJvm("""
            import * as console from "std/console"
            function consume(x: null): int { return 7; }
            export function test(): int {
              let a: string = "x";
              return consume(console.log(a));
            }
            """, "capReturnArg");
        check(returnArg.exitCode() == 0, "return with captured null arg exits 0");
        check(returnArg.output().contains("x"),
            "return with captured null arg prints: " + returnArg.output());
        check(returnArg.output().contains("7"),
            "return with captured null arg computes: " + returnArg.output());

        // The emitted artifact for these programs must contain no lambdas
        // (the capture-safety guarantee) and must preserve evaluation order:
        // the hoisted call runs before the reassignment that follows it.
        Frontend f = compileFrontend("""
            import * as console from "std/console"
            export function test(): null {
              let a: string = "x";
              let z: null = console.log(a);
              a = "y";
              return;
            }
            """, "jvmtest-cap-nolambda.deal");
        check(f.errors().isEmpty(), "no-lambda probe frontend clean: " + f.errors());
        if (f.errors().isEmpty()) {
            JvmBackend.JvmCodegenResult res = JvmBackend.generate(
                f.program(), f.checkResult(), "jvmtest-cap-nolambda.deal", "main");
            check(!res.hasErrors(), "no-lambda probe codegen clean: " + res.diagnostics());
            if (!res.hasErrors()) {
                check(!res.source().contains(" -> "),
                    "no-lambda probe emits no lambda");
                check(!res.source().contains("nullAnd"),
                    "no-lambda probe emits no nullAnd helper");
                int printIdx = res.source().indexOf("java.lang.System.out.println(a);");
                int reassignIdx = res.source().indexOf("a = \"y\";");
                check(printIdx >= 0 && reassignIdx >= 0 && printIdx < reassignIdx,
                    "hoisted call runs before the later reassignment");
            }
        }
    }

    /** {@code null === null} / {@code z === null} / {@code !==} (spec
     * §Value equality: {@code null === null} is true) emit Java {@code ==}/
     * {@code !=} — no E6000 fallback. */
    private static void testNullEquality() throws Exception {
        System.out.println("-- Null equality (javac + java) --");

        ExecResult eq = compileAndRunJvm("""
            export function test(): int {
              let z: null = null;
              let same: boolean = z === null;
              let litSame: boolean = null === null;
              let neq: boolean = null !== null;
              if (same && litSame && !neq) { return 1; }
              return 0;
            }
            """, "nulleq");
        check(eq.exitCode() == 0, "null equality exits 0");
        check(eq.output().contains("1"),
            "z === null, null === null true, null !== null false: " + eq.output());

        // A side-effecting null-typed operand keeps its evaluation order:
        // the call runs before the comparison.
        ExecResult operand = compileAndRunJvm("""
            import * as console from "std/console"
            function helper(): null { console.log("helper-ran"); }
            export function test(): boolean { return helper() === null; }
            """, "nulleqoperand");
        check(operand.exitCode() == 0, "null-operand equality exits 0");
        check(operand.output().contains("helper-ran"),
            "null-typed call operand runs: " + operand.output());
        check(operand.output().contains("true"),
            "helper() === null is true: " + operand.output());

        // A hoisted call in an if CONDITION runs before the condition line.
        ExecResult cond = compileAndRunJvm("""
            import * as console from "std/console"
            function helper(): null { console.log("cond-ran"); }
            export function test(): int {
              if (helper() === null) { return 1; }
              return 0;
            }
            """, "nulleqcond");
        check(cond.exitCode() == 0, "if-condition null equality exits 0");
        check(cond.output().contains("cond-ran"),
            "hoisted if-condition call runs: " + cond.output());
        check(cond.output().contains("1"),
            "if-condition null equality is true: " + cond.output());

        // A hoisted call in an ELSE-IF condition: Java forbids statements
        // between `}` and `else`, so the backend nests the chain in a plain
        // else block — the call must still run before the condition.
        ExecResult elseIf = compileAndRunJvm("""
            import * as console from "std/console"
            function cond(): null { console.log("elseif-ran"); }
            export function test(): int {
              if (false) {
                return 0;
              } else if (cond() === null) {
                return 1;
              } else {
                return 2;
              }
            }
            """, "nulleqelseif");
        check(elseIf.exitCode() == 0, "hoisted else-if condition exits 0");
        check(elseIf.output().contains("elseif-ran"),
            "hoisted else-if condition runs: " + elseIf.output());
        check(elseIf.output().contains("1"),
            "hoisted else-if condition computes 1: " + elseIf.output());

        // A deeper chain with the hoisted condition in the middle: the
        // nested form must keep every brace balanced and every branch
        // reachable.
        ExecResult deepChain = compileAndRunJvm("""
            import * as console from "std/console"
            function helper(): null { console.log("mid-ran"); }
            export function test(): int {
              if (false) {
                console.log("a");
                return 0;
              } else if (helper() === null) {
                console.log("b");
                return 1;
              } else if (true) {
                console.log("c");
                return 2;
              } else {
                console.log("d");
                return 3;
              }
            }
            """, "nulleqdeep");
        check(deepChain.exitCode() == 0, "deep chain with hoisted middle condition exits 0");
        check(deepChain.output().contains("mid-ran"),
            "deep chain middle condition runs: " + deepChain.output());
        check(deepChain.output().contains("1"),
            "deep chain middle branch computes 1: " + deepChain.output());

        // Ordering nulls is rejected by the FRONTEND (E3007 invalid operand
        // types) — only ===/!== are legal on null — so the backend's E6000
        // fallback is unreachable for this combination.
        Frontend f = compileFrontend(
            "export function test(): boolean { return null < null; }",
            "jvmtest-nullorder.deal");
        check(!f.errors().isEmpty()
                && f.errors().stream().anyMatch(d -> "E3007".equals(d.code())),
            "null ordering is rejected by the frontend: " + f.errors());
    }

    /** Standalone non-call/non-assignment expression statements (`x + 1;`)
     * are checker-accepted and evaluated by LuaJIT (an overflow there is an
     * observable E8004); the backend lowers them to dummy-local declarations
     * so they are genuinely evaluated instead of rejected or discarded. */
    private static void testStandaloneExpressionStatements() throws Exception {
        System.out.println("-- Standalone expression statements (javac + java) --");

        ExecResult discard = compileAndRunJvm("""
            export function test(): int {
              let x: int = 1;
              x + 1;
              return x;
            }
            """, "discardbinop");
        check(discard.exitCode() == 0, "discarded binop exits 0");
        check(discard.output().contains("1"),
            "discarded binop leaves x unchanged: " + discard.output());

        ExecResult overflow = compileAndRunJvm("""
            export function test(): int {
              9223372036854775807 + 1;
              return 0;
            }
            """, "discardoverflow");
        check(overflow.exitCode() == 1, "standalone overflow exits 1");
        check(overflow.output().contains("DEAL_ERROR_CODE: E8004"),
            "standalone overflow reports E8004: " + overflow.output());

        // A string-typed standalone expression gets a String dummy local.
        ExecResult strDiscard = compileAndRunJvm("""
            export function test(): int {
              let s: string = "a";
              s + "b";
              return 1;
            }
            """, "discardstring");
        check(strDiscard.exitCode() == 0, "discarded string concat exits 0");
        check(strDiscard.output().contains("1"),
            "discarded string concat runs: " + strDiscard.output());

        // Two consecutive standalone statements need distinct dummy locals.
        ExecResult twoStmts = compileAndRunJvm("""
            export function test(): int {
              let x: int = 1;
              x + 1;
              x * 2;
              return x;
            }
            """, "twodiscards");
        check(twoStmts.exitCode() == 0, "two standalone statements exit 0");
        check(twoStmts.output().contains("1"),
            "two standalone statements run: " + twoStmts.output());

        Frontend f = compileFrontend(
            "export function test(): int { let x: int = 1; x + 1; return x; }",
            "jvmtest-discard.deal");
        check(f.errors().isEmpty(), "standalone statement frontend clean");
        if (f.errors().isEmpty()) {
            JvmBackend.JvmCodegenResult res = JvmBackend.generate(
                f.program(), f.checkResult(), "jvmtest-discard.deal", "main");
            check(!res.hasErrors(), "standalone statement codegen clean: "
                + res.diagnostics());
            if (!res.hasErrors()) {
                check(res.source().contains("__ignored"),
                    "standalone statement lowered to a dummy local");
            }
        }
    }

    /** Runtime coverage for number floored {@code %} on negative operands,
     * string {@code ===}/ordering, and {@code console.error} → stderr. */
    private static void testNumberModStringAndStderrRuntime() throws Exception {
        System.out.println("-- number %, string ops, console.error (javac + java) --");

        // Lua-style floored modulo: -7 % 3 == 2 (Java's truncated % gives -1).
        ExecResult numMod = compileAndRunJvm(
            "export function test(): number { return -7.0 % 3.0; }",
            "nummod");
        check(numMod.exitCode() == 0, "floored % on negatives exits 0");
        check(numMod.output().contains("2.0"),
            "floored % on negatives computes 2.0: " + numMod.output());

        ExecResult numMod2 = compileAndRunJvm(
            "export function test(): number { return 7.0 % -3.0; }",
            "nummod2");
        check(numMod2.exitCode() == 0, "floored % on negative divisor exits 0");
        check(numMod2.output().contains("-2.0"),
            "7.0 % -3.0 computes -2.0 (floor(-7/3)=-3): " + numMod2.output());

        // String equality and ordering end to end.
        ExecResult strings = compileAndRunJvm("""
            function classify(s: string, t: string): int {
              if (s === t) { return 1; }
              if (s < t) { return 2; }
              return 3;
            }
            export function test(): int {
              return classify("aaa", "aaa") * 100
                   + classify("a", "b") * 10
                   + classify("b", "a");
            }
            """, "strings");
        check(strings.exitCode() == 0, "string ops exit 0");
        check(strings.output().contains("123"),
            "string equality and ordering compute 123: " + strings.output());

        // console.error reaches stderr (merged into the captured stream).
        ExecResult stderr = compileAndRunJvm("""
            import * as console from "std/console"
            export function test(): null { console.error("err-chan"); }
            """, "stderr");
        check(stderr.exitCode() == 0, "console.error exits 0");
        check(stderr.output().contains("err-chan"),
            "console.error output observed: " + stderr.output());
    }

    /** Module-level statements run at load time inside static initializers. */
    private static void testModuleLevelStatements() throws Exception {
        // v1.2 grammar gate: module-level statements (lets, calls, ifs,
        // assignments) were removed — the v1.1 static-initializer surface
        // is a frontend parse error (E1049). The same statement shapes
        // inside functions are pinned by the while/if/null tests above.
        Frontend f = compileFrontend("""
            import * as console from "std/console"
            let x: int = 1;
            console.log("module-if-ran");
            if (x === 1) { console.log("module-if-true"); }
            x = 2;
            export function test(): int { return x; }
            """, "jvmtest-modstmts.deal");
        check(f.errors().stream().anyMatch(d -> "E1049".equals(d.code())),
            "module-level statements rejected with E1049: " + f.errors());

        Frontend interleaved = compileFrontend("""
            import * as console from "std/console"
            function f(): int { console.log("f-ran"); return 1; }
            function g(): int { console.log("g-ran"); return 2; }
            let a: int = f();
            console.log("mid");
            let b: int = g();
            export function test(): int { return a + b; }
            """, "jvmtest-modorder.deal");
        check(interleaved.errors().stream()
                .anyMatch(d -> "E1049".equals(d.code())),
            "interleaved module-level statements rejected with E1049: "
                + interleaved.errors());
    
    }

    /** A shadowed let whose initializer references the outer binding uses the
     * OUTER value (LuaJIT: `local x = x + 1` reads the outer x → 6). */
    private static void testShadowedInitializer() throws Exception {
        System.out.println("-- Shadowed initializer binds to the outer scope --");

        ExecResult exec = compileAndRunJvm("""
            export function test(): int {
              let x: int = 5;
              { let x: int = x + 1; return x; }
            }
            """, "shadowed");
        check(exec.exitCode() == 0, "shadowed initializer exits 0");
        check(exec.output().contains("6"),
            "shadowed initializer computes 6 from the outer x: " + exec.output());

        // v1.2 grammar gate: the module-field shadow shape (a module-level
        // let) was removed — E1049.
        Frontend fieldShadow = compileFrontend("""
            let counter: int = 5;
            export function test(): int {
              let counter: int = counter + 1;
              return counter;
            }
            """, "jvmtest-fieldshadow.deal");
        check(fieldShadow.errors().stream()
                .anyMatch(d -> "E1049".equals(d.code())),
            "module-field shadow rejected with E1049: " + fieldShadow.errors());
    }

    /**
     * ISSUE-0093 regression: a parameter or local that shadows a module
     * field (or any other visible binding) must emit ONE Java name for
     * the binding everywhere — the signature and the body. The old
     * backend declared the parameter through {@code declareLocal} (which
     * disambiguated against the visible module field, {@code x → x$1})
     * but wrote the signature with the raw {@link JvmBackend#javaName}
     * translation ({@code long x}), so the body read {@code x$1} and
     * javac rejected the artifact after the CLI reported success
     * ("cannot find symbol x$1"). The shadowed-{@code let} initializer
     * case was also broken: {@code declareLocal} compared DEAL names
     * (scope keys), not emitted names (scope values), so a local
     * shadowing a disambiguated parameter silently reused the
     * parameter's emitted name and its initializer's enclosing read then
     * referred to the uninitialized new binding
     * ({@code long g$1 = intAdd(g$1, 10L);} — "might not have been
     * initialized"). Both shapes now emit valid Java and compute the
     * LuaJIT values (Lua's {@code local x = x + 1} reads the enclosing
     * binding).
     *
     * <p>ISSUE-0093 rework: the triple-deep shadow chain (field →
     * parameter → body-top let → inner-block let) exposed the remaining
     * overwrite defect — the body-top {@code let} and the parameters
     * share ONE scope map, so {@code declareLocal}'s {@code put}
     * overwrites the parameter's key and its still-in-Java-scope emitted
     * name (JLS §6.4 forbids an inner block from redeclaring a method
     * parameter) vanished from the value scan; the inner-block let then
     * reused it and javac rejected the artifact after the CLI reported
     * success. The backend now reserves every per-function emitted name
     * (parameters + locals) in a set that outlives the map overwrite, and
     * both chain shapes (with and without a module field) emit distinct
     * names and run to the LuaJIT values.
     */
    private static void testParameterShadowing() throws Exception {
        // v1.2 grammar gate: module fields were removed, so the
        // parameter-shadows-field chains are frontend parse errors
        // (E1049). The parameter/local shadow chains survive in-function
        // and are pinned below (the no-field shapes).
        Frontend f = compileFrontend("""
            let x: int = 1;
            function f(x: int): int { return x + 1; }
            export function test(): int { return f(5); }
            """, "jvmtest-paramshadow.deal");
        check(f.errors().stream().anyMatch(d -> "E1049".equals(d.code())),
            "parameter-shadow-field shape rejected with E1049: " + f.errors());

        Frontend triple = compileFrontend("""
            let g: int = 1;
            function f(g: int): int {
              let g: int = g + 10;
              return g;
            }
            export function test(): int { return f(5) + g; }
            """, "jvmtest-tripleshadow.deal");
        check(triple.errors().stream().anyMatch(d -> "E1049".equals(d.code())),
            "triple-shadow field shape rejected with E1049: " + triple.errors());

        // The no-field variant pins the same disambiguation defect class
        // without any module field: the body-top let overwrites the
        // parameter's key, and the inner block must never reuse the
        // parameter's emitted name (the old backend emitted `long g` over
        // the parameter's `long g` — a javac rejection). Every binding
        // now gets a distinct name and the artifact compiles.
        Frontend noField = compileFrontend("""
            function f(g: int): int {
              let g: int = g + 10;
              { let g: int = g + 1; }
              return g;
            }
            export function test(): int { return f(5); }
            """, "jvmtest-shadowchain-nofield.deal");
        if (!noField.errors().isEmpty()) {
            fail("checker must accept the no-field chain probe: "
                + noField.errors());
            return;
        }
        JvmBackend.JvmCodegenResult noFieldRes = JvmBackend.generate(
            noField.program(), noField.checkResult(),
            "jvmtest-shadowchain-nofield.deal", "main");
        check(!noFieldRes.hasErrors(), "no-field chain emits without E6000");
        check(noFieldRes.source().contains("static long f(long g)"),
            "no-field chain: parameter keeps its plain name");
        check(noFieldRes.source().contains("long g$1 = intAdd(g, 10L);"),
            "no-field chain: body-top let takes g$1 and reads the parameter");
        check(noFieldRes.source().contains("long g$2 = intAdd(g$1, 1L);"),
            "no-field chain: inner-block let never reuses the parameter's "
                + "name (g$2, not g)");
        check(noFieldRes.source().contains("return g$1;"),
            "no-field chain: trailing read resolves to the body-top let");

        ExecResult noFieldRun = compileAndRunJvm("""
            function f(g: int): int {
              let g: int = g + 10;
              { let g: int = g + 1; }
              return g;
            }
            export function test(): int { return f(5); }
            """, "shadowchainnofield");
        check(noFieldRun.exitCode() == 0, "no-field chain exits 0");
        check(noFieldRun.output().contains("15"),
            "no-field chain computes 15: " + noFieldRun.output());

        // A let shadowing a parameter: the let's initializer binds to the
        // parameter (LuaJIT's `local g = g + 10` reads the outer binding
        // → 15).
        ExecResult letShadow = compileAndRunJvm("""
            function f(g: int): int {
              let g: int = g + 10;
              return g;
            }
            export function test(): int { return f(5); }
            """, "letshadowparam");
        check(letShadow.exitCode() == 0, "let-shadow-parameter exits 0");
        check(letShadow.output().contains("15"),
            "let-shadow-parameter computes 15: " + letShadow.output());

        // Double-nested local shadowing: each level reads the nearest
        // enclosing binding ({@code x$2 = x$1 + 1} = 3).
        ExecResult nested = compileAndRunJvm("""
            export function test(): int {
              { let x: int = 2; { let x: int = x + 1; return x; } }
            }
            """, "nestedshadow");
        check(nested.exitCode() == 0, "nested shadow exits 0");
        check(nested.output().contains("3"),
            "double-nested shadow computes 3: " + nested.output());
    
    }

    /** ISSUE-0095: local classes and nominal checks — emission shape and
     * real javac + java runs. */
    private static void testClassSlice() throws Exception {
        System.out.println("-- ISSUE-0095 class slice --");

        // Emission shape: generated nested class carrying its spec identity,
        // the nominal-check helper, the minimal table, construction with
        // declaration-order defaults, and a checked table read. A class
        // named like an emitted helper (DealError) stays collision-free
        // through the $C_ prefix.
        String source = """
            class Point {
              x: int = 1;
              y: int = 2;
            }
            class DealError { value: int = 0; }
            export function test(): int {
              let p: Point = { y: 5 };
              let holder: table = { item: p };
              let q: Point = holder.item;
              return q.x + q.y;
            }
            """;
        Frontend f = compileFrontend(source, "jvmtest-class.deal");
        check(f.errors().isEmpty(), "class slice frontend clean: " + f.errors());
        if (!f.errors().isEmpty()) return;
        JvmBackend.JvmCodegenResult res =
            JvmBackend.generate(f.program(), f.checkResult(), "jvmtest-class.deal", "Main");
        check(!res.hasErrors(), "class slice codegen clean: " + res.diagnostics());
        if (res.hasErrors()) return;
        String java = res.source();
        check(java.contains("static final class $C_Point extends $Base"),
            "generated class extends the identity base");
        check(java.contains("super(\"@Main/Point\")"),
            "generated class carries its spec ClassDescriptor identity");
        check(java.contains("static java.lang.Object $check(java.lang.String descriptor, java.lang.Object v)"),
            "the shared descriptor-driven runtime-check seam is emitted");
        check(java.contains("descriptor.equals(\"@Main/Point\")"),
            "the Point class contributes its nominal-check branch to the seam");
        check(java.contains("descriptor.equals(\"@Main/DealError\")"),
            "a class named like the error helper stays collision-free");
        check(java.contains("new $C_Point(1L, 5L)"),
            "construction fills declaration-order defaults around provided fields");
        check(java.contains("new $DealRt.Table().put(\"item\", p)"),
            "table literal chains put calls");
        check(java.contains("$check(\"@Main/Point\", (holder).get(\"item\"))"),
            "class-typed table read runs the shared seam with the spec "
            + "class descriptor");
        check(java.contains("return intAdd((q).x, (q).y);"),
            "class field reads flow into arithmetic");

        // Real artifact: per-construction defaults, a field write, and a
        // nominal-check success through the table boundary.
        ExecResult ok = compileAndRunJvm("""
            class Box { value: int = 0; }
            export function test(): int {
              let a: Box = {};
              let b: Box = { value: 41 };
              a.value = b.value + 1;
              let holder: table = { item: a };
              let c: Box = holder.item;
              return c.value;
            }
            """, "class-slice-ok");
        check(ok.exitCode() == 0 && ok.output().contains("42"),
            "class construction/field write/nominal check run to 42: " + ok.output());

        // Real artifact: an identically-shaped sibling class fails the
        // nominal check — identity is nominal, never structural.
        ExecResult err = compileAndRunJvm("""
            class A { x: int = 0; }
            class B { x: int = 0; }
            export function test(): int {
              let a: A = { x: 7 };
              let holder: table = { item: a };
              let b: B = holder.item;
              return b.x;
            }
            """, "class-slice-err");
        check(err.exitCode() == 1 && err.output().contains("DEAL_ERROR_CODE: E8001"),
            "nominal check failure reports E8001 with exit 1: " + err.output());

        // Real artifact: table-typed reads of a nested table pass through
        // the $check("table", ...) boundary.
        ExecResult tbl = compileAndRunJvm("""
            export function test(): int {
              let t: table = { inner: { n: 5 } };
              let inner: table = t.inner;
              return 1;
            }
            """, "class-slice-table");
        check(tbl.exitCode() == 0 && tbl.output().contains("1"),
            "table-typed boundary reads pass through: " + tbl.output());

        // ISSUE-0095 reviewer round 9: a provided construction value whose
        // emitted code can be the Lua nil (a nil-aware && over boolean[]
        // reads) must stay boxed through the construction materialization —
        // the constructor's booleanNotNull boundary raises E8001, never a
        // bare unboxing NPE (the pre-fix `boolean __t2 = __sc0;` crashed
        // with a NullPointerException instead of a DEAL error).
        ExecResult boolBoundary = compileAndRunJvm("""
            class P { b: boolean = false; }
            export function test(): int {
              let xs: boolean[] = [true, false];
              let p: P = { b: xs[0] && xs[5] };
              return 1;
            }
            """, "class-slice-bool-boundary");
        check(boolBoundary.exitCode() == 1
                && boolBoundary.output().contains("DEAL_ERROR_CODE: E8001"),
            "nil-aware construction value fails the boolean boundary with E8001: "
                + boolBoundary.output());

        // Positive control: an in-bounds nil-aware construction value
        // constructs normally (no over-rejection, no over-boxing).
        ExecResult boolOk = compileAndRunJvm("""
            class P { b: boolean = false; }
            export function test(): int {
              let xs: boolean[] = [true, false];
              let p: P = { b: xs[0] || xs[1] };
              if (p.b === true) { return 1; }
              return 0;
            }
            """, "class-slice-bool-ok");
        check(boolOk.exitCode() == 0 && boolOk.output().contains("1"),
            "in-bounds nil-aware construction value constructs normally: "
                + boolOk.output());

        // ISSUE-0095 reviewer round 9: class-field default expressions run
        // through checkExpression in the checker, so the backend's typeOf
        // sees real types — the pre-fix backend mis-typed `1 + 2` as
        // "ADD on error and error" (E6000) while LuaJIT compiles and runs
        // the same program.
        ExecResult defaultArith = compileAndRunJvm("""
            class Point { x: int = 1 + 2; }
            export function test(): int {
              let p: Point = {};
              return p.x;
            }
            """, "class-slice-default-arith");
        check(defaultArith.exitCode() == 0
                && defaultArith.output().contains("3"),
            "arithmetic default constructs and runs to 3: "
                + defaultArith.output());

        // A default containing a null-typed call: the checker's typeMap now
        // types the inner call, so the call is hoisted into a pre-statement
        // and the constructor argument is the null value — the pre-fix
        // backend emitted `new $C_P(wrap(noise()))` (javac: 'void' type not
        // allowed here) after the CLI reported success.
        ExecResult defaultNullCall = compileAndRunJvm("""
            function noise(): null { return; }
            function wrap(x: null): string { return "w"; }
            class P { x: string = wrap(noise()); }
            export function test(): int {
              let p: P = {};
              if (p.x === "w") { return 1; }
              return 0;
            }
            """, "class-slice-default-nullcall");
        check(defaultNullCall.exitCode() == 0
                && defaultNullCall.output().contains("1"),
            "null-typed call inside a default emits valid Java and runs to 1: "
                + defaultNullCall.output());

        // The nil-aware default boundary shape needs a module-level array
        // field, which v1.2 removed (E1049 grammar gate); the in-function
        // nil-aware boolean boundary is pinned by the nullable-slice tests.
        Frontend defaultBoolBoundaryShape = compileFrontend("""
            let xs: boolean[] = [true, false];
            class P { b: boolean = xs[0] && xs[5]; }
            export function test(): int {
              let p: P = {};
              return 1;
            }
            """, "jvmtest-class-slice-default-bool-boundary.deal");
        check(defaultBoolBoundaryShape.errors().stream()
                .anyMatch(d -> "E1049".equals(d.code())),
            "module-level array field default shape rejected with E1049: "
                + defaultBoolBoundaryShape.errors());

        // ISSUE-0095 reviewer round 10, re-unified by ISSUE-0110: a
        // local class named `Table` is legal DEAL. Its nominal check
        // lives as a descriptor branch INSIDE the single shared seam —
        // the pre-fix shape declared `static $T $checkTable(Object)`
        // twice and javac rejected it ("method $checkTable(Object) is
        // already defined") after the CLI reported success. The shared
        // $check helper has the raw `$` (unspellable in DEAL — javaName
        // escapes it), so no per-kind helper name can collide with a
        // generated class branch any more.
        Frontend tableClass = compileFrontend("""
            class Table { x: int = 0; }
            export function test(): int {
              let t: Table = {};
              let holder: table = { item: t };
              let q: Table = holder.item;
              return q.x;
            }
            """, "jvmtest-class-table.deal");
        check(tableClass.errors().isEmpty(),
            "class named Table frontend clean: " + tableClass.errors());
        if (tableClass.errors().isEmpty()) {
            JvmBackend.JvmCodegenResult tableRes = JvmBackend.generate(
                tableClass.program(), tableClass.checkResult(),
                "jvmtest-class-table.deal", "Main");
            check(!tableRes.hasErrors(),
                "class named Table codegen clean: " + tableRes.diagnostics());
            if (!tableRes.hasErrors()) {
                String tableJava = tableRes.source();
                check(occurrences(tableJava,
                        "static java.lang.Object $check(java.lang.String descriptor, java.lang.Object v)") == 1,
                    "the shared runtime-check seam is declared exactly "
                    + "once");
                check(occurrences(tableJava,
                        "descriptor.equals(\"@Main/Table\")") == 1,
                    "the class Table's nominal-check branch is declared "
                    + "exactly once inside the seam");
                check(tableJava.contains(
                        "$check(\"@Main/Table\", (holder).get(\"item\"))"),
                    "the Table-typed read dispatches on the spec class "
                    + "descriptor: " + tableJava);
                check(!tableJava.contains("static $T $check$Table(")
                        && !tableJava.contains("static $T $checkTable("),
                    "no duplicate per-kind table-check helper remains: "
                    + tableJava);
            }
        }

        // The same program must compile and run as a real artifact:
        // construction of Table, the table round-trip through the
        // class-typed nominal check, and the field read.
        ExecResult tableRun = compileAndRunJvm("""
            class Table { x: int = 0; }
            export function test(): int {
              let t: Table = {};
              let holder: table = { item: t };
              let q: Table = holder.item;
              return q.x;
            }
            """, "class-table-collision");
        check(tableRun.exitCode() == 0 && tableRun.output().contains("0"),
            "the Table-named class artifact compiles and runs through the "
            + "nominal check to 0: " + tableRun.output());

        // Control probes: classes named like the other fixed runtime
        // helpers stay collision-free through the $C_/$check prefixes
        // (Point vs the identity base $Base, and the error-helper name
        // DealError, pinned by the emission checks above).
        ExecResult baseRun = compileAndRunJvm("""
            class Base { x: int = 7; }
            export function test(): int {
              let p: Base = {};
              return p.x;
            }
            """, "class-named-base");
        check(baseRun.exitCode() == 0 && baseRun.output().contains("7"),
            "a class named Base compiles and runs: " + baseRun.output());

        // ISSUE-0095 reviewer round 11: the class-field write branch
        // emitted the receiver inline and flushed the RHS's hoisted
        // pre-statements before the whole assignment statement, so a
        // hoisting RHS (a null-typed call argument, a boxed nil-aware
        // read) ran its side effects BEFORE the receiver's inline
        // effects — the pre-fix artifact for getBox().x =
        // make(note("value")) printed value → target → make while
        // LuaJIT's strict left-to-right order (spec-v1.2 §Operational
        // semantics rule 1: receiver before member/index/call
        // arguments) prints target → value → make. Both operands now
        // route through emitOperandsInOrder, which materializes the
        // receiver into a temporary assigned before the hoisted
        // statements — exactly the array-write branch's fix for the
        // same hazard.
        Frontend fieldWriteOrder = compileFrontend("""
            import * as console from "std/console"
            class Box { x: int = 0; }
            function note(s: string): null { console.log(s); return; }
            function getBox(): Box { note("target"); return { x: 0 }; }
            function make(v: null): int { note("make"); return 1; }
            export function test(): int {
              getBox().x = make(note("value"));
              return 0;
            }
            """, "jvmtest-class-write-order.deal");
        check(fieldWriteOrder.errors().isEmpty(),
            "field-write order probe frontend clean: " + fieldWriteOrder.errors());
        if (fieldWriteOrder.errors().isEmpty()) {
            JvmBackend.JvmCodegenResult orderRes = JvmBackend.generate(
                fieldWriteOrder.program(), fieldWriteOrder.checkResult(),
                "jvmtest-class-write-order.deal", "Main");
            check(!orderRes.hasErrors(),
                "field-write order probe codegen clean: " + orderRes.diagnostics());
            if (!orderRes.hasErrors()) {
                String orderJava = orderRes.source();
                int receiverTemp = orderJava.indexOf(" = getBox();");
                int hoistedCall = orderJava.indexOf("note(\"value\");");
                int store = orderJava.indexOf(".x = make(null);");
                check(receiverTemp >= 0 && hoistedCall >= 0 && store >= 0
                        && receiverTemp < hoistedCall && hoistedCall < store,
                    "receiver materialized into a temp before the RHS's "
                    + "hoisted call, store last: " + orderJava);
            }
        }

        // The same program must run receiver → value → store as a real
        // artifact (LuaJIT parity, pinned by the cross-backend fixture
        // jvm-class-field-write-eval-order-parity).
        ExecResult writeOrderRun = compileAndRunJvm("""
            import * as console from "std/console"
            class Box { x: int = 0; }
            function note(s: string): null { console.log(s); return; }
            function getBox(): Box { note("target"); return { x: 0 }; }
            function make(v: null): int { note("make"); return 1; }
            export function test(): int {
              getBox().x = make(note("value"));
              return 0;
            }
            """, "class-write-order");
        check(writeOrderRun.exitCode() == 0
                && writeOrderRun.output().startsWith("target\nvalue\nmake"),
            "class field write evaluates receiver → value → store "
            + "(spec rule 1): " + writeOrderRun.output());

        // Error precedence: when the RHS raises at its typed boundary (a
        // nil-aware && over boolean[] reads), the receiver must still
        // evaluate first — the pre-fix artifact raised E8001 before the
        // receiver's inline call ran (no "target").
        ExecResult writeErrOrder = compileAndRunJvm("""
            import * as console from "std/console"
            class P { b: boolean = false; }
            function getP(): P { console.log("target"); return { b: false }; }
            export function test(): int {
              let xs: boolean[] = [true, false];
              getP().b = xs[0] && xs[5];
              return 1;
            }
            """, "class-write-err-order");
        check(writeErrOrder.exitCode() == 1
                && writeErrOrder.output().startsWith(
                    "target\nDEAL_ERROR_CODE: E8001"),
            "receiver effects run before the RHS's E8001 boundary "
            + "failure: " + writeErrOrder.output());

        // v1.2 grammar gate: the pure variant's module-level accumulator
        // string is a removed module field (E1049). The evaluation order
        // itself stays pinned by the console-based variant above.
        Frontend writeOrderPureShape = compileFrontend("""
            let noteLog: string = "";
            class Box { x: int = 0; }
            function note(s: string): null { noteLog = noteLog + s; return; }
            function getBox(): Box { note("target"); return { x: 0 }; }
            function make(v: null): int { note("make"); return 1; }
            export function test(): int {
              getBox().x = make(note("value"));
              if (noteLog === "targetvaluemake") { return 0; }
              return 1;
            }
            """, "jvmtest-class-write-order-pure.deal");
        check(writeOrderPureShape.errors().stream()
                .anyMatch(d -> "E1049".equals(d.code())),
            "module-accumulator field write shape rejected with E1049: "
                + writeOrderPureShape.errors());
    }
    // =========================================================================
    // ISSUE-0098 slice: function values and wrappers
    // =========================================================================

    /**
     * Function values and wrappers (ISSUE-0098): first-class function
     * values through the real frontend → JvmBackend codegen → javac →
     * java pipeline, with emission assertions that the artifact carries
     * the per-signature wrapper classes (descriptor strings included),
     * the per-declaration wrapper instance fields, indirect-call
     * dispatch through {@code invoke}, the int/number intrinsic wrapper
     * fields, and the arity-extension adapter shapes (static-method
     * delegation for module functions and effectively-final snapshot
     * temporaries for locals/parameters the enclosing body never
     * reassigns; a reassigned local/parameter and any non-identifier
     * adapter value — which LuaJIT reads live / re-evaluates per invoke —
     * are E6000 until ISSUE-0110, never a lambda, never a silent
     * divergence). Also covered: E8010 runtime signature checks at
     * callback and return boundaries (matching LuaJIT's wrapper checks)
     * with the checked value expression evaluated FIRST — the marker
     * probes pin the evaluate-then-check order with console markers (the
     * v1.2 grammar removed the v1.1 module-field counter), and a later
     * materialized nil-yielding {@code (int | null)[]} read keeps its
     * boxed Java shape (never a primitive-temp unboxing NPE) — wrapper
     * reference equality, the call-result-callee evaluation order (the
     * callee is materialized into a single-assignment temporary at its
     * evaluation position, BEFORE any argument's hoisted pre-statements —
     * spec §Operational semantics rule 1: picker() raises its own E8001 /
     * return-boundary E8010 before bump()'s negative-index read can run,
     * and the E8002 message is pinned out), the reassigned
     * local/parameter adapter E6000 (also firing when the reassignment
     * is hidden in a table-literal property value), and the deferred
     * signature shapes (nested function types, nullable function
     * types, arrays of functions) rejected with E6000 — async function
     * types are ISSUE-0099-slice, covered by testAsyncSlice. Every v1.1
     * module-field/load-time shape — the field adapter with LIVE static
     * -field delegation, the load-time indirect-call guards (a
     * module-level value use of a later-declared function, a field with
     * a not-statically-known value, an assigned function reaching a
     * later-declared function, the adapter live-read retargeted to a
     * later-declared function, the snapshot field capturing a
     * later-declared function, table-literal-hidden assignments,
     * same-statement assignments, load-time-called function bodies as
     * assignment and indirect-call sources, and the module-level
     * call-result callee hazards), the clean load-time indirect-call /
     * reassignment / snapshot-field shapes, and the rest function-type
     * arm — is a v1.2 frontend grammar gate (E1049/E1047) pinned here
     * instead: the v1.2 module top level holds only declarations, so
     * those shapes no longer reach a backend. The in-function shapes run
     * cross-backend with LuaJIT parity, and the removed module-level
     * shapes are E1049-gated in
     * {@code test/conformance/fixtures/jvm-function-values-slice.json}.
     */
    private static void testFunctionValues() throws Exception {
        System.out.println("-- Function values and wrappers (ISSUE-0098) --");

        // Typed variable + indirect call through the wrapper.
        ExecResult typed = compileAndRunJvm("""
            function add(a: int, b: int): int { return a + b; }
            export function test(): int {
              let f: (a: int, b: int) => int = add;
              return f(20, 22);
            }
            """, "jvmtest-fv-typed");
        check(typed.exitCode() == 0, "typed function value exits 0");
        check(typed.output().contains("42"),
            "typed function value computes 42 through the wrapper: "
                + typed.output());

        // Emission assertions: wrapper classes, wrapper fields, indirect
        // dispatch, intrinsic wrappers, and the arity-extension adapter.
        Frontend f = compileFrontend("""
            function add(a: int, b: int): int { return a + b; }
            function inc(x: int): int { return x + 1; }
            export function test(): int {
              let f: (a: int, b: int) => int = add;
              let g: (a: int, b: int) => int = inc;
              return f(1, 2) + g(41, 999);
            }
            """, "jvmtest-fv-emission.deal");
        check(f.errors().isEmpty(), "emission probe frontend clean: " + f.errors());
        if (f.errors().isEmpty()) {
            JvmBackend.JvmCodegenResult res = JvmBackend.generate(
                f.program(), f.checkResult(), "jvmtest-fv-emission.deal", "main");
            check(!res.hasErrors(), "emission probe codegen clean: "
                + res.diagnostics());
            if (!res.hasErrors()) {
                String src = res.source();
                check(src.contains("static abstract class Fn2_II_R_I implements $FnValue {"),
                    "per-signature wrapper class emitted");
                check(src.contains(
                    "final java.lang.String descriptor = \"(int,int)->int\";"),
                    "wrapper carries the spec-convention descriptor");
                check(src.contains("static final Fn2_II_R_I add$fn = new Fn2_II_R_I()"),
                    "per-declaration wrapper instance field emitted");
                check(src.contains("f.invoke(1L, 2L)"),
                    "indirect call dispatches through invoke");
                check(src.contains(
                    "Fn2_II_R_I g = new Fn2_II_R_I() { @Override long invoke(long p0, long p1) { return Main.inc(p0); } };"),
                    "arity adapter delegates to the qualified static method, dropping p1");
                check(src.contains(
                        "static final Fn2_II_R_I add$fn = new Fn2_II_R_I() {")
                    && src.contains(
                        "long invoke(long p0, long p1) { return Main.add(p0, p1); }"),
                    "declaration wrapper delegates to the qualified static method");
                check(src.contains("static final Fn1_N_R_I _int$fn = new Fn1_N_R_I() {")
                    && src.contains("static final Fn1_I_R_N _number$fn = new Fn1_I_R_N() {"),
                    "intrinsic function-value wrappers emitted");
                check(!src.contains(" -> "),
                    "no lambda is emitted (descriptor strings may carry the arrow glyph)");
            }
        }

        // Review finding (MR-0081, review 0009): a DEAL function named
        // `invoke` — the wrapper classes' dispatch method — must work as
        // a direct value, a callback, and an arity-adapted value without
        // recursion (the pre-fix wrapper body called the bare name, which
        // resolved to the anonymous class's OWN invoke: infinite
        // recursion) or javac failure (the arity-adapter body called the
        // bare name with the overlapping-prefix arguments, which javac
        // rejected against the adapter's own wider invoke). The same
        // qualification covers `descriptor` — the wrapper classes'
        // descriptor field, which the pre-fix body shadowed into a
        // "cannot find symbol" javac failure.
        ExecResult invokeName = compileAndRunJvm("""
            function invoke(x: int): int { return x + 1; }
            function apply(f: (x: int) => int, v: int): int { return f(v); }
            export function test(): int {
              let f: (x: int) => int = invoke;
              let g: (a: int, b: int) => int = invoke;
              return f(41) + g(0, 999) + apply(invoke, 0);
            }
            """, "jvmtest-fv-invoke-name");
        check(invokeName.exitCode() == 0,
            "a function named invoke exits 0 (no recursion): " + invokeName.output());
        check(invokeName.output().contains("44"),
            "invoke as a direct value (42), arity-adapted value (1), and "
            + "callback (1) computes 44: " + invokeName.output());
        ExecResult descriptorName = compileAndRunJvm("""
            function descriptor(x: int): int { return x * 2; }
            export function test(): int {
              let f: (a: int, b: int) => int = descriptor;
              return f(21, 999);
            }
            """, "jvmtest-fv-descriptor-name");
        check(descriptorName.exitCode() == 0,
            "a function named descriptor exits 0: " + descriptorName.output());
        check(descriptorName.output().contains("42"),
            "descriptor as an arity-adapted value computes 42: "
                + descriptorName.output());
        Frontend invokeF = compileFrontend("""
            function invoke(x: int): int { return x + 1; }
            export function test(): int {
              let f: (x: int) => int = invoke;
              let g: (a: int, b: int) => int = invoke;
              return f(41) + g(0, 999);
            }
            """, "jvmtest-fv-invoke-name-emit.deal");
        check(invokeF.errors().isEmpty(),
            "invoke-name emission probe frontend clean: " + invokeF.errors());
        if (invokeF.errors().isEmpty()) {
            JvmBackend.JvmCodegenResult res = JvmBackend.generate(
                invokeF.program(), invokeF.checkResult(),
                "jvmtest-fv-invoke-name-emit.deal", "main");
            check(!res.hasErrors(), "invoke-name emission probe codegen clean: "
                + res.diagnostics());
            if (!res.hasErrors()) {
                check(res.source().contains("return Main.invoke(p0);"),
                    "the declaration wrapper delegates to the qualified static invoke");
                check(res.source().contains(
                        "Fn2_II_R_I g = new Fn2_II_R_I() { @Override long invoke(long p0, long p1) { return Main.invoke(p0); } };"),
                    "the arity adapter delegates to the qualified static invoke");
                check(!res.source().contains("return invoke(p0);"),
                    "never a bare invoke call recursing into the wrapper's own invoke");
            }
        }

        // Adapter over a LOCAL function value: the adapter snapshots the
        // local's current value into an effectively-final temporary.
        ExecResult localAdapter = compileAndRunJvm("""
            function inc(x: int): int { return x + 1; }
            export function test(): int {
              let g: (x: int) => int = inc;
              let h: (a: int, b: int) => int = g;
              return h(41, 999);
            }
            """, "jvmtest-fv-local-adapter");
        check(localAdapter.exitCode() == 0, "local adapter exits 0");
        check(localAdapter.output().contains("42"),
            "local-value adapter computes 42: " + localAdapter.output());
        Frontend localF = compileFrontend("""
            function inc(x: int): int { return x + 1; }
            export function test(): int {
              let g: (x: int) => int = inc;
              let h: (a: int, b: int) => int = g;
              return h(41, 999);
            }
            """, "jvmtest-fv-local-adapter-emit.deal");
        check(localF.errors().isEmpty(), "local adapter probe frontend clean");
        if (localF.errors().isEmpty()) {
            JvmBackend.JvmCodegenResult res = JvmBackend.generate(
                localF.program(), localF.checkResult(),
                "jvmtest-fv-local-adapter-emit.deal", "main");
            check(!res.hasErrors(), "local adapter probe codegen clean: "
                + res.diagnostics());
            if (!res.hasErrors()) {
                check(res.source().contains("Fn1_I_R_I __fn0 = g;"),
                    "adapter snapshots the local into an effectively-final temporary");
                check(res.source().contains("__fn0.invoke(p0)"),
                    "adapter delegates through the snapshot temporary");
            }
        }

        // Adapter over a call result: LuaJIT re-evaluates the value
        // expression on EVERY invoke (side effects re-run); the JVM slice
        // cannot emit that without an effectively-final capture — E6000,
        // never a silent once-only evaluation divergence.
        Frontend callAdapterF = compileFrontend("""
            function inc(x: int): int { return x + 1; }
            function picker(): (x: int) => int { return inc; }
            export function test(): int {
              let h: (a: int, b: int) => int = picker();
              return h(41, 999);
            }
            """, "jvmtest-fv-call-adapter.deal");
        check(callAdapterF.errors().isEmpty(),
            "call-result adapter probe frontend clean: " + callAdapterF.errors());
        if (callAdapterF.errors().isEmpty()) {
            JvmBackend.JvmCodegenResult res = JvmBackend.generate(
                callAdapterF.program(), callAdapterF.checkResult(),
                "jvmtest-fv-call-adapter.deal", "main");
            check(res.hasErrors(), "call-result adapter is rejected");
            check(res.diagnostics().stream()
                    .anyMatch(d -> "E6000".equals(d.code())),
                "E6000 for the call-result adapter: " + res.diagnostics());
        }

        // v1.2 grammar gate: module fields were removed, so the
        // module-field adapter shape (the adapter body reading the static
        // field LIVE on every invoke — LuaJIT's adapter re-reads the
        // chunk-local binding, so a reassignment after creation retargets
        // it) is a frontend parse error (E1049) before any backend. The
        // in-function local-adapter coverage above and the reassigned
        // local/parameter E6000 cases below keep the adapter semantics.
        Frontend fieldF = compileFrontend("""
            function inc(x: int): int { return x + 1; }
            let g: (x: int) => int = inc;
            export function test(): int {
              let h: (a: int, b: int) => int = g;
              return h(41, 999);
            }
            """, "jvmtest-fv-field-adapter-emit.deal");
        check(fieldF.errors().stream().anyMatch(d -> "E1049".equals(d.code())),
            "module-field adapter shape rejected with E1049: "
                + fieldF.errors());

        // v1.2 grammar gate: the load-time variant of the field adapter
        // (module-level wrapper fields, a load-time reassignment, and a
        // load-time indirect call) is a frontend parse error (E1049)
        // before any backend — module top level holds only declarations.
        Frontend loadFieldAdapter = compileFrontend("""
            function inc(x: int): int { return x + 1; }
            function dbl(x: int): int { return x * 2; }
            let g: (x: int) => int = inc;
            let h: (a: int, b: int) => int = g;
            g = dbl;
            let r: int = h(10, 999);
            export function test(): int { return r; }
            """, "jvmtest-fv-load-field-adapter.deal");
        check(loadFieldAdapter.errors().stream()
                .anyMatch(d -> "E1049".equals(d.code())),
            "load-time field adapter shape rejected with E1049: "
                + loadFieldAdapter.errors());

        // A call-result callee evaluates COMPLETELY before every argument
        // (spec §Operational semantics rule 1 — LuaJIT evaluates the
        // callee expression first), even when a later argument hoists
        // side-effecting pre-statements: the callee is materialized into
        // a single-assignment temporary at its evaluation position, so
        // picker() runs before bump()'s negative-index read. The probe
        // pins the order with the ERROR: picker's own past-end int[] read
        // raises E8001, and bump's E8002 message never appears (the
        // pre-fix inline `picker().invoke(...)` form hoisted bump() first
        // and raised E8002).
        ExecResult calleeOrder = compileAndRunJvm("""
            function f2(a: int, b: boolean): int { return a; }
            function picker(): (a: int, b: boolean) => int {
              let xs: int[] = [1];
              let v: int = xs[99];
              return f2;
            }
            function bump(): int {
              let ys: int[] = [1];
              return ys[-1];
            }
            export function test(): int {
              let bs: boolean[] = [true];
              return picker()(bump(), true && bs[99]);
            }
            """, "jvmtest-fv-callee-order");
        check(calleeOrder.exitCode() == 1,
            "call-result callee evaluation probe exits 1");
        check(calleeOrder.output().contains("DEAL_ERROR_CODE: E8001"),
            "the callee's own E8001 raises before any argument runs: "
                + calleeOrder.output());
        check(!calleeOrder.output().contains("negative array index"),
            "bump() never runs before the callee: " + calleeOrder.output());

        // The same callee-first order holds when the callee's return
        // boundary raises E8010: picker() raises before bump() and the
        // hoisted && operand are evaluated.
        ExecResult calleeE8010 = compileAndRunJvm("""
            function inc(x: int): int { return x + 1; }
            function picker(): (a: int, b: boolean) => int { return inc; }
            function bump(): int {
              let ys: int[] = [1];
              return ys[-1];
            }
            export function test(): int {
              let bs: boolean[] = [true];
              return picker()(bump(), true && bs[99]);
            }
            """, "jvmtest-fv-callee-e8010");
        check(calleeE8010.exitCode() == 1,
            "call-result callee E8010 probe exits 1");
        check(calleeE8010.output().contains("DEAL_ERROR_CODE: E8010"),
            "the callee's return-boundary E8010 raises before any argument: "
                + calleeE8010.output());
        check(!calleeE8010.output().contains("negative array index"),
            "bump() never runs before the E8010 raise: "
                + calleeE8010.output());

        // Emission assertions: the callee temporary and the dispatch.
        Frontend calleeF = compileFrontend("""
            function add(a: int, b: int): int { return a + b; }
            function picker(): (a: int, b: int) => int { return add; }
            export function test(): int { return picker()(41, 1); }
            """, "jvmtest-fv-callee-emit.deal");
        check(calleeF.errors().isEmpty(),
            "call-result callee emit probe frontend clean: " + calleeF.errors());
        if (calleeF.errors().isEmpty()) {
            JvmBackend.JvmCodegenResult res = JvmBackend.generate(
                calleeF.program(), calleeF.checkResult(),
                "jvmtest-fv-callee-emit.deal", "main");
            check(!res.hasErrors(), "call-result callee emit probe codegen "
                + "clean: " + res.diagnostics());
            if (!res.hasErrors()) {
                check(res.source().contains("Fn2_II_R_I __fn0 = picker();"),
                    "the call-result callee is materialized into a temporary "
                    + "at its evaluation position");
                check(res.source().contains("__fn0.invoke(41L, 1L)"),
                    "the dispatch goes through the callee temporary");
                check(!res.source().contains("picker().invoke"),
                    "never an inline callee whose argument pre-statements "
                    + "would run first");
            }
        }

        // Adapter over a local/parameter that the enclosing body
        // reassigns: LuaJIT's adapter reads the binding live and observes
        // the later assignment; the JVM slice cannot emit a live capture
        // of a reassigned binding — E6000, never a silent snapshot
        // divergence.
        record ReassignedCase(String what, String source) {}
        List<ReassignedCase> reassigned = List.of(
            new ReassignedCase("reassigned local", """
                function inc(x: int): int { return x + 1; }
                function dbl(x: int): int { return x * 2; }
                export function test(): int {
                  let g: (x: int) => int = inc;
                  let h: (a: int, b: int) => int = g;
                  g = dbl;
                  return h(10, 999);
                }
                """),
            new ReassignedCase("reassigned parameter", """
                function inc(x: int): int { return x + 1; }
                function dbl(x: int): int { return x * 2; }
                function probe(g: (x: int) => int): int {
                  let h: (a: int, b: int) => int = g;
                  g = dbl;
                  return h(10, 999);
                }
                export function test(): int { return probe(inc); }
                """),
            new ReassignedCase(
                "reassigned local hidden in a table-literal property value", """
                function inc(x: int): int { return x + 1; }
                function dbl(x: int): int { return x * 2; }
                export function test(): int {
                  let g: (x: int) => int = inc;
                  let h: (a: int, b: int) => int = g;
                  let t = { x: (g = dbl) };
                  return h(10, 999);
                }
                """));
        for (ReassignedCase c : reassigned) {
            Frontend rf = compileFrontend(c.source,
                "jvmtest-fv-reassigned.deal");
            if (!rf.errors().isEmpty()) {
                fail("frontend must accept the reassigned-adapter case '"
                    + c.what() + "' (the backend rejects it): " + rf.errors());
                continue;
            }
            JvmBackend.JvmCodegenResult res = JvmBackend.generate(
                rf.program(), rf.checkResult(), "jvmtest-fv-reassigned.deal",
                "main");
            check(res.hasErrors(), "backend rejects " + c.what());
            check(res.diagnostics().stream()
                    .anyMatch(d -> "E6000".equals(d.code())),
                "E6000 for " + c.what() + ": " + res.diagnostics());
        }

        // E8010 signature check at a callback parameter boundary.
        ExecResult cbCheck = compileAndRunJvm("""
            function inc(x: int): int { return x + 1; }
            function apply(f: (a: int, b: string) => int, v: int): int { return f(v, "ignored"); }
            export function test(): int { return apply(inc, 41); }
            """, "jvmtest-fv-cb-check");
        check(cbCheck.exitCode() == 1, "callback signature check exits 1");
        check(cbCheck.output().contains("DEAL_ERROR_CODE: E8010"),
            "callback signature check raises E8010: " + cbCheck.output());
        check(cbCheck.output().contains(
            "function signature mismatch: expected (int,string)->int, got (int)->int"),
            "callback signature check carries LuaJIT's message: " + cbCheck.output());

        // E8010 signature check at a return boundary.
        ExecResult retCheck = compileAndRunJvm("""
            function inc(x: int): int { return x + 1; }
            function picker(): (a: int, b: int) => int { return inc; }
            export function test(): int {
              let f: (a: int, b: int) => int = picker();
              return f(41, 999);
            }
            """, "jvmtest-fv-ret-check");
        check(retCheck.exitCode() == 1, "return signature check exits 1");
        check(retCheck.output().contains("DEAL_ERROR_CODE: E8010"),
            "return signature check raises E8010: " + retCheck.output());

        // A later operand materialized after an E8010-checking argument
        // keeps the EMITTED code shape (ISSUE-0108 integration): the
        // nil-yielding `(int | null)[]` read past the end yields the DEAL
        // null (boxed `java.lang.Long`), so its materialization temporary
        // must be the boxed type — a `long` temp would auto-unbox the
        // null and crash with a bare Java NPE instead of the E8010 the
        // parameter boundary raises (LuaJIT evaluates the read — nil, no
        // error — and then raises E8010 at callee entry).
        ExecResult cbCheckNullable = compileAndRunJvm("""
            function inc(x: int): int { return x + 1; }
            function take(f: (a: int, b: string) => int, v: int | null): int { return 1; }
            export function test(): int {
              let xs: (int | null)[] = [];
              return take(inc, xs[0]);
            }
            """, "jvmtest-fv-cb-check-nullable");
        check(cbCheckNullable.exitCode() == 1,
            "nullable-later-operand E8010 probe exits 1");
        check(cbCheckNullable.output().contains("DEAL_ERROR_CODE: E8010"),
            "the E8010 raises (never a bare NPE on the null element): "
                + cbCheckNullable.output());
        check(!cbCheckNullable.output().contains("NullPointerException"),
            "no unboxing NPE from a primitive materialization temp: "
                + cbCheckNullable.output());

        // Two arity-mismatched function-valued arguments in ONE call,
        // with an impure later value expression (BOT-0716 finding): both
        // value expressions must evaluate left to right, the FIRST
        // parameter's boundary check must raise E8010 with the FIRST
        // parameter's descriptor, and the emitted artifact must be valid
        // Java. The pre-fix JVM materialized the later operand's RAISING
        // construction into an actual-shape temporary
        // (`Fn0_R_I __t0 = new Fn2_IS_R_I() {…}`), which javac rejected
        // ("incompatible types") after the CLI reported success — and
        // which would have raised the SECOND parameter's check before
        // the first's.
        ExecResult twoChecks = compileAndRunJvm("""
            import * as console from "std/console"
            function inc(x: int): int { return x + 1; }
            function pickZero(): () => int {
              console.log("f2-e8010-two-mismatched-args-ok");
              return zero;
            }
            function zero(): int { return 0; }
            function apply2(f: (a: int, b: string) => int, g: (a: int, b: string) => int): int { return f(1, "s"); }
            export function test(): int { return apply2(inc, pickZero()); }
            """, "jvmtest-fv-cb-two-checks");
        check(twoChecks.exitCode() == 1,
            "two-mismatched-args E8010 probe exits 1");
        check(twoChecks.output().contains("f2-e8010-two-mismatched-args-ok"),
            "the later argument's value expression evaluates before the raise: "
                + twoChecks.output());
        check(twoChecks.output().contains("DEAL_ERROR_CODE: E8010"),
            "the two-mismatched-args E8010 raises: " + twoChecks.output());
        check(twoChecks.output().contains(
            "function signature mismatch: expected (int,string)->int, got (int)->int"),
            "the FIRST parameter's check raises with its descriptor: "
                + twoChecks.output());
        check(!twoChecks.output().contains("got ()->int"),
            "the second parameter's check never runs: " + twoChecks.output());

        // Emission shape: each check operand's VALUE is an actual-shape
        // temporary declared at its evaluation position and each raising
        // construction stays INLINE at its argument position — neither
        // materialization loop ever hoists the raising construction
        // (the pre-fix javac-rejected shape).
        Frontend twoChecksF = compileFrontend("""
            function inc(x: int): int { return x + 1; }
            function pickZero(): () => int { return zero; }
            function zero(): int { return 0; }
            function apply2(f: (a: int, b: string) => int, g: (a: int, b: string) => int): int { return f(1, "s"); }
            export function test(): int { return apply2(inc, pickZero()); }
            """, "jvmtest-fv-cb-two-checks-emit.deal");
        check(twoChecksF.errors().isEmpty(),
            "two-check emission probe frontend clean: " + twoChecksF.errors());
        if (twoChecksF.errors().isEmpty()) {
            JvmBackend.JvmCodegenResult res = JvmBackend.generate(
                twoChecksF.program(), twoChecksF.checkResult(),
                "jvmtest-fv-cb-two-checks-emit.deal", "main");
            check(!res.hasErrors(),
                "two-check emission probe codegen clean: " + res.diagnostics());
            if (!res.hasErrors()) {
                String src = res.source();
                check(src.contains("Fn1_I_R_I __fn0 = inc$fn;"),
                    "the first check operand's value temp carries its ACTUAL shape");
                check(src.contains("Fn0_R_I __fn1 = pickZero();"),
                    "the later check operand's value temp carries its ACTUAL shape");
                check(!src.contains("Fn0_R_I __t")
                    && !src.contains("Fn2_IS_R_I __t")
                    && !src.contains("Fn1_I_R_I __t"),
                    "no materialization temp holds a raising construction");
                check(src.contains("new Fn2_IS_R_I() { { if (!checkSig(\"(int,string)->int\", \"(int)->int\"))")
                    && src.contains("new Fn2_IS_R_I() { { if (!checkSig(\"(int,string)->int\", \"()->int\"))"),
                    "both raising constructions stay inline at their argument positions in parameter order");
                check(src.contains("apply2(new Fn2_IS_R_I()"),
                    "the inline raise sits directly in the call argument list");
            }
        }

        // The checked value expression is evaluated BEFORE E8010 raises
        // (spec §Operational semantics rule 2 / strict return-value
        // evaluation — LuaJIT's evaluate-then-check order). The callback
        // probe pins the ORDER with console markers (the v1.2 grammar
        // removed the v1.1 module-field counter): the checked argument's
        // value expression prints "pre" and the later argument's value
        // prints "post" — both must appear in order before E8010, and
        // the pre-fix snapshot-check wrapper ran neither.
        ExecResult cbEval = compileAndRunJvm("""
            import * as console from "std/console"
            function inc(x: int): int { return x + 1; }
            function markF(tag: string, f: (x: int) => int): (x: int) => int {
              if (tag === "pre") { console.log("f2-e8010-callback-eval-order-pre"); }
              return f;
            }
            function markI(tag: string, v: int): int {
              if (tag === "post") { console.log("f2-e8010-callback-eval-order-post"); }
              return v;
            }
            function take(f: (a: int, b: string) => int, v: int): int { return f(v, "ignored"); }
            export function test(): int { return take(markF("pre", inc), markI("post", 1)); }
            """, "jvmtest-fv-cb-eval");
        check(cbEval.exitCode() == 1, "callback E8010 evaluation probe exits 1");
        check(cbEval.output().contains("f2-e8010-callback-eval-order-pre"),
            "the checked argument's value expression runs before E8010 raises: "
                + cbEval.output());
        check(cbEval.output().contains("f2-e8010-callback-eval-order-post"),
            "the later argument's value expression runs before E8010 raises: "
                + cbEval.output());
        check(cbEval.output().indexOf("f2-e8010-callback-eval-order-pre")
                < cbEval.output().indexOf("f2-e8010-callback-eval-order-post"),
            "checked-value marker precedes the later argument's marker: "
                + cbEval.output());
        check(cbEval.output().contains("DEAL_ERROR_CODE: E8010"),
            "the callback E8010 still raises: " + cbEval.output());

        ExecResult retEval = compileAndRunJvm("""
            import * as console from "std/console"
            function inc(x: int): int { return x + 1; }
            function markF(tag: string, f: (x: int) => int): (x: int) => int {
              if (tag === "pre") { console.log("f2-e8010-return-eval-ok"); }
              return f;
            }
            function picker(): (a: int, b: int) => int { return markF("pre", inc); }
            export function test(): int {
              let f: (a: int, b: int) => int = picker();
              return f(41, 999);
            }
            """, "jvmtest-fv-ret-eval");
        check(retEval.exitCode() == 1, "return E8010 evaluation probe exits 1");
        check(retEval.output().contains("f2-e8010-return-eval-ok"),
            "the returned value expression is evaluated before E8010 raises: "
                + retEval.output());
        check(retEval.output().contains("DEAL_ERROR_CODE: E8010"),
            "the return E8010 still raises: " + retEval.output());

        // v1.2 grammar gate: module fields were removed, so the
        // load-time clean shapes — an indirect call through a
        // function-typed module field whose value is statically known, a
        // load-time field reassignment before the call site, and an
        // equal-signature field snapshot — are frontend parse errors
        // (E1049) before any backend. Their semantics live on only in
        // the in-function local shapes above.
        List<String> loadFieldShapes = List.of(
            """
            function noop42(): int { return 42; }
            let g: () => int = noop42;
            let r: int = g();
            export function test(): int { return r; }
            """,
            """
            function inc(x: int): int { return x + 1; }
            function dbl(x: int): int { return x * 2; }
            let f: (x: int) => int = inc;
            f = dbl;
            let r: int = f(2);
            export function test(): int { return r; }
            """,
            """
            function inc(x: int): int { return x + 1; }
            function dbl(x: int): int { return x * 2; }
            let g1: (x: int) => int = inc;
            g1 = dbl;
            let g: (x: int) => int = g1;
            let r: int = g(21);
            export function test(): int { return r; }
            """);
        for (String shape : loadFieldShapes) {
            Frontend lf = compileFrontend(shape, "jvmtest-fv-load-shape.deal");
            check(lf.errors().stream().anyMatch(d -> "E1049".equals(d.code())),
                "load-time field shape rejected with E1049: " + lf.errors());
        }

        // v1.2 grammar gate: every load-time guard shape (value use of a
        // later-declared function, a not-statically-known field value, an
        // assignment before the call site, and the load-time-called
        // function-body and call-result callee shapes) rests on
        // module-level executable statements, which the v1.2 grammar
        // removes — E1049 before any backend, never a Java forward
        // reference or a silently diverging load-time execution. The
        // in-function live-read value-set edges keep their E6000
        // coverage (the reassigned local/parameter adapter and the
        // call-result adapter cases above).
        record GuardCase(String what, String source) {}
        List<GuardCase> guards = List.of(
            new GuardCase("value use of a later-declared function", """
                let f: () => int = later;
                function later(): int { return 42; }
                export function test(): int { return f(); }
                """),
            new GuardCase("indirect call through a field with a call-valued initializer", """
                function noop42(): int { return 42; }
                function picker(): () => int { return noop42; }
                let g: () => int = picker();
                let r: int = g();
                export function test(): int { return r; }
                """),
            new GuardCase("indirect call whose assigned function reaches a later-declared function", """
                function h(): int { return later(); }
                let g: () => int = h;
                g = h;
                let r: int = g();
                function later(): int { return 1; }
                export function test(): int { return r; }
                """),
            new GuardCase("adapter live-read retargeted to a later-declared function", """
                function inc(x: int): int { return x + 1; }
                function dbl(x: int): int { return later(x); }
                let g: (x: int) => int = inc;
                let h: (a: int, b: int) => int = g;
                g = dbl;
                let r: int = h(10, 999);
                function later(x: int): int { return x * 2; }
                export function test(): int { return r; }
                """),
            new GuardCase("snapshot field capturing a later-declared function", """
                function inc(x: int): int { return x + 1; }
                function dbl(x: int): int { return later(x); }
                let g1: (x: int) => int = inc;
                g1 = dbl;
                let g: (x: int) => int = g1;
                let r: int = g(21);
                function later(x: int): int { return x * 2; }
                export function test(): int { return r; }
                """),
            new GuardCase(
                "table-literal-hidden field assignment before a load-time indirect call", """
                function zero(): int { return 0; }
                function one(): int { return two(); }
                let g: () => int = zero;
                let t = { x: (g = one) };
                let r: int = g();
                function two(): int { return 2; }
                export function test(): int { return r; }
                """),
            new GuardCase(
                "same-statement assignment before a load-time indirect call", """
                function zero(): int { return 0; }
                function one(): int { return two(); }
                function side(f: () => int): int { return f(); }
                let g: () => int = zero;
                let r: int = side(g = one) + g();
                function two(): int { return 2; }
                export function test(): int { return r; }
                """),
            new GuardCase(
                "while-body assignment before a load-time indirect call", """
                function inc(x: int): int { return x + 1; }
                function dbl(x: int): int { return later(x); }
                let f: (x: int) => int = inc;
                let c: boolean = true;
                let acc: int = 0;
                while (c) {
                  f = dbl;
                  acc = f(2);
                  c = false;
                }
                function later(x: int): int { return x * 2; }
                export function test(): int { return acc; }
                """),
            new GuardCase(
                "assignment inside a load-time-called function body", """
                function inc(x: int): int { return x + 1; }
                function a(): int { return b(); }
                let g: (x: int) => int = inc;
                function set(): null { g = a; }
                set();
                let r: int = g(2);
                function b(): int { return 42; }
                export function test(): int { return r; }
                """),
            new GuardCase(
                "indirect call inside a load-time-called function body", """
                function inc(x: int): int { return x + 1; }
                function a(): int { return b(); }
                let g: (x: int) => int = inc;
                function set(): null { g = a; }
                set();
                function run(): int { return g(2); }
                let r: int = run();
                function b(): int { return 42; }
                export function test(): int { return r; }
                """),
            new GuardCase(
                "held function invoking another field indirectly at load time", """
                function inc(x: int): int { return x + 1; }
                function a(): int { return b(); }
                let h: (x: int) => int = inc;
                function run(): int { return h(2); }
                let g: () => int = run;
                let r: int = g();
                function b(): int { return 42; }
                export function test(): int { return r; }
                """),
            new GuardCase(
                "sibling indirect call whose field may hold an assigning function", """
                function zero(): int { return 0; }
                let g: () => int = zero;
                function setG(): int { g = one; return 0; }
                function one(): int { return 2; }
                let h: () => int = setG;
                let r: int = h() + g();
                export function test(): int { return r; }
                """),
            new GuardCase(
                "sibling call-result indirect call in the walked statement", """
                function zero(): int { return 0; }
                function picker(): () => int { return zero; }
                let g: () => int = zero;
                let r: int = picker()() + g();
                export function test(): int { return r; }
                """),
            // Module-level indirect calls through NON-identifier callees
            // (a call or assignment producing a function value): the
            // produced value is not statically known to the load-time
            // guard, so every such load-time call is E6000 — LuaJIT
            // fails at load when the produced function reads or reaches
            // a not-yet-declared value, while Java would silently read
            // the uninitialized static wrapper field (a bare
            // NullPointerException) or run the hoisted method.
            new GuardCase(
                "module-level call-result callee returning a later-declared function", """
                function picker(): (x: int) => int { return inc; }
                let r: int = picker()(2);
                function inc(x: int): int { return x + 1; }
                export function test(): int { return r; }
                """),
            new GuardCase(
                "module-level call-result callee whose produced function reaches a later-declared function", """
                function mid(x: int): int { return laterFn(); }
                function picker(): (x: int) => int { return mid; }
                let r: int = picker()(2);
                function laterFn(): int { return 42; }
                export function test(): int { return r; }
                """),
            new GuardCase(
                "module-level assignment-produced callee", """
                function inc(x: int): int { return x + 1; }
                let g: (x: int) => int = inc;
                let r: int = (g = inc)(2);
                export function test(): int { return r; }
                """));
        for (GuardCase c : guards) {
            Frontend gf = compileFrontend(c.source, "jvmtest-fv-guard.deal");
            check(gf.errors().stream().anyMatch(d -> "E1049".equals(d.code())),
                "load-time guard shape '" + c.what()
                    + "' rejected with E1049: " + gf.errors());
        }

        // Deferred signature shapes → E6000 (ISSUE-0110).
        record DeferredCase(String what, String source) {}
        List<DeferredCase> deferred = List.of(
            // The four former deferred cases — async function types
            // (ISSUE-0099), nested and nullable function types, and
            // arrays of functions (ISSUE-0102) — are promoted out of
            // this list: async function-type bindings run through the
            // ISSUE-0099 wrapper machinery (testAsyncSlice), and the
            // ISSUE-0102 promoted block below pins the other three.
            // Function equality/inequality over deferred signature
            // shapes: the value use reaches emitIdentifier's
            // FunctionSymbol branch without a typed function-value
            // boundary in between, and the wrapper field emitFunction
            // gates on does not exist for these signatures — E6000,
            // never an artifact javac rejects after the CLI reported
            // success.
            new DeferredCase(
                "function equality with array-parameter signatures", """
                function f(xs: int[]): int { return xs[0]; }
                function g(xs: int[]): int { return xs[0]; }
                export function test(): boolean { return f === g; }
                """),
            new DeferredCase(
                "function inequality with class-parameter signatures", """
                class C { v: int = 0; }
                function f(c: C): int { return c.v; }
                function g(c: C): int { return c.v; }
                export function test(): boolean { return f !== g; }
                """),
            new DeferredCase(
                "function equality with nullable-parameter signatures", """
                function f(x: int | null): int { return 1; }
                function g(x: int | null): int { return 2; }
                export function test(): boolean { return f === g; }
                """));
        for (DeferredCase c : deferred) {
            Frontend df = compileFrontend(c.source, "jvmtest-fv-deferred.deal");
            if (!df.errors().isEmpty()) {
                fail("frontend must accept the deferred case '" + c.what()
                    + "' (the backend rejects it): " + df.errors());
                continue;
            }
            JvmBackend.JvmCodegenResult res = JvmBackend.generate(
                df.program(), df.checkResult(), "jvmtest-fv-deferred.deal", "main");
            check(res.hasErrors(), "backend rejects " + c.what());
            check(res.diagnostics().stream().anyMatch(d -> "E6000".equals(d.code())),
                "E6000 for " + c.what() + ": " + res.diagnostics());
        }

        // ISSUE-0102: nested and nullable function types promoted out of
        // the deferred list — they now emit wrapper shapes.
        List<DeferredCase> promoted = List.of(
            new DeferredCase("nested function type", """
                function inc(x: int): int { return x + 1; }
                export function test(): int {
                  let h: (x: int) => (y: int) => int = function(x: int): (y: int) => int { return inc; };
                  return 1;
                }
                """),
            new DeferredCase("nullable function type", """
                function inc(x: int): int { return x + 1; }
                export function test(): int {
                  let f: ((x: int) => int) | null = inc;
                  return 1;
                }
                """));
        for (DeferredCase c : promoted) {
            Frontend df = compileFrontend(c.source, "jvmtest-fv-promoted.deal");
            if (!df.errors().isEmpty()) {
                fail("frontend must accept the promoted case '" + c.what()
                    + "': " + df.errors());
                continue;
            }
            JvmBackend.JvmCodegenResult res = JvmBackend.generate(
                df.program(), df.checkResult(), "jvmtest-fv-promoted.deal",
                "main");
            check(!res.hasErrors(),
                "backend now accepts " + c.what() + ": " + res.diagnostics());
        }

        // v1.2 grammar gate: rest parameters and rest function-type arms
        // were removed from the language, so the v1.1 rest function-type
        // shape is a frontend parse error (E1047) before any backend —
        // the deferred-signature E6000 surface for rest arms no longer
        // exists.
        Frontend restShape = compileFrontend("""
            function join(a: int, ...xs: int[]): int { return 1; }
            export function test(): int {
              let f: (a: int, ...xs: int[]) => int = join;
              return 1;
            }
            """, "jvmtest-fv-deferred-rest.deal");
        check(restShape.errors().stream().anyMatch(d -> "E1047".equals(d.code())),
            "rest function-type shape rejected with E1047: " + restShape.errors());
    }

    /**
     * ISSUE-0098 cross-module function values: the per-signature wrapper
     * classes are emitted per module as NESTED classes, so a function
     * value cannot cross a project-module boundary — a Func-typed
     * argument to an imported module call, or a Func-typed call result
     * used as an assignment value / call-result callee, is E6000 with no
     * entry artifact (never an artifact javac rejects after the CLI
     * reported success). The pre-fix emissions
     * ({@code Lib.apply(inc$fn, 41L)}, {@code Fn1_I_R_I f =
     * Lib.picker();}, {@code Fn1_I_R_I __fn0 = Lib.picker();}) were all
     * javac-rejected, and the member-call path also silently skipped the
     * LuaJIT parameter-boundary E8010 check the same-module path emits.
     * The same four shapes are pinned by the multi-module conformance
     * fixtures in {@code test/conformance/fixtures/
     * jvm-function-values-slice.json} through
     * {@code BackendConformanceTest.runMultiModuleTestCase}.
     */
    private static void testCrossModuleFunctionValuesRejected() throws Exception {
        System.out.println("-- Orchestrator: cross-module function values → E6000 --");

        record XmodCase(String what, String libSource, String entrySource) {}
        List<XmodCase> cases = List.of(
            new XmodCase("callback argument",
                "export function apply(f: (x: int) => int, v: int): int { return f(v); }\n",
                "import * as lib from \"./lib\"\n"
                    + "function inc(x: int): int { return x + 1; }\n"
                    + "export function main(): null { return null; }\n"
                    + "export function test(): int { return lib.apply(inc, 41); }\n"),
            new XmodCase("arity-extension argument (E8010 boundary)",
                "export function apply2(f: (a: int, b: string) => int, v: int): int { return f(v, \"i\"); }\n",
                "import * as lib from \"./lib\"\n"
                    + "function inc(x: int): int { return x + 1; }\n"
                    + "export function main(): null { return null; }\n"
                    + "export function test(): int { return lib.apply2(inc, 41); }\n"),
            new XmodCase("returned function value",
                "export function picker(): (x: int) => int { return inc; }\n"
                    + "function inc(x: int): int { return x + 1; }\n",
                "import * as lib from \"./lib\"\n"
                    + "export function main(): null { return null; }\n"
                    + "export function test(): int {\n"
                    + "  let f: (x: int) => int = lib.picker();\n"
                    + "  return f(41);\n"
                    + "}\n"),
            new XmodCase("imported call-result callee",
                "export function picker(): (x: int) => int { return inc; }\n"
                    + "function inc(x: int): int { return x + 1; }\n",
                "import * as lib from \"./lib\"\n"
                    + "export function main(): null { return null; }\n"
                    + "export function test(): int { return lib.picker()(41); }\n"));

        for (XmodCase c : cases) {
            writeFile("src/lib.deal", c.libSource());
            writeFile("src/entry.deal", c.entrySource());

            Path entryFile = tmpDir.resolve("src/entry.deal").toAbsolutePath();
            Path outputDir = tmpDir.resolve("build/xmod_fv");
            List<Path> roots = List.of(tmpDir.resolve("src").toAbsolutePath());

            CompilationOrchestrator orchestrator = new CompilationOrchestrator(
                entryFile, outputDir, false, false, false, Backend.JVM,
                (DealConfig) null, roots, Path.of(".").toAbsolutePath().normalize());

            boolean success = orchestrator.compile();
            check(!success, "cross-module function-value case '" + c.what()
                + "' is rejected: " + orchestrator.diagnostics());
            check(orchestrator.diagnostics().stream()
                    .anyMatch(d -> "E6000".equals(d.code())),
                "E6000 for cross-module function-value case '" + c.what()
                    + "': " + orchestrator.diagnostics());
            // The rejected ENTRY module writes no artifact (the clean
            // lib module may still be written by the orchestrator's
            // two-pass design) — never an artifact javac rejects after
            // the CLI reported success.
            check(!Files.exists(outputDir.resolve("Entry.java")),
                "no entry artifact for '" + c.what() + "'");

            if (Files.isDirectory(outputDir)) {
                try (var stream = Files.walk(outputDir)) {
                    stream.sorted(Comparator.reverseOrder()).forEach(f -> {
                        try { Files.deleteIfExists(f); } catch (IOException ignored) {}
                    });
                }
            }
        }
    }

    private static void testUseBeforeDeclarationRejected() {
        // v1.2 grammar gate: module-field use-before-declaration shapes
        // (module-level lets/ifs) are frontend parse errors (E1049). The
        // in-function shape keeps its E6000 backend rejection.
        List<String> sources = List.of(
            // self-reference in a local initializer
            "export function test(): int { let x: int = x + 1; return x; }",
            // use of a local before its declaration
            """
            import * as console from "std/console"
            export function test(): null {
              console.log(x);
              let x: string = "later";
            }
            """);

        for (String source : sources) {
            Frontend f = compileFrontend(source, "jvmtest-usebefore.deal");
            if (!f.errors().isEmpty()) {
                fail("checker must accept the use-before-declaration probe "
                    + "(the backend rejects it): " + f.errors());
                continue;
            }
            JvmBackend.JvmCodegenResult res =
                JvmBackend.generate(f.program(), f.checkResult(),
                    "jvmtest-usebefore.deal", "main");
            check(res.hasErrors(), "backend rejects use-before-declaration");
            check(res.diagnostics().stream().anyMatch(d -> "E6000".equals(d.code())),
                "E6000 for use-before-declaration: " + res.diagnostics());
        }

        List<String> moduleShapes = List.of(
            "let x: int = x + 1;\nexport function test(): int { return x; }",
            "let b: int = c + 1;\nlet c: int = 2;\nexport function test(): int { return b; }",
            """
            if (x === 1) { }
            let x: int = 1;
            export function test(): int { return x; }
            """);
        for (String shape : moduleShapes) {
            Frontend f = compileFrontend(shape, "jvmtest-usebefore.deal");
            check(f.errors().stream().anyMatch(d -> "E1049".equals(d.code())),
                "module-field use-before-declaration shape rejected with E1049: "
                    + f.errors());
        }
    
    }

    /**
     * A write to a later-declared function-local with no enclosing binding
     * must be rejected, never emitted as a Java forward reference that
     * javac rejects after the CLI reported success (`x = 5; let x = 1`
     * inside a function: LuaJIT writes the enclosing scope and then the
     * later `local` shadows it; Java has no forward-reference target).
     * Module-level writes to later-declared fields stay allowed
     * (JLS §8.3.3 forward-reference LHS exception; the later initializer
     * wins, exactly like LuaJIT), and so do function-body writes to a
     * module field declared BEFORE the function (LuaJIT's upvalue write —
     * full parity). Function-body writes to a field declared AFTER the
     * function are E6000 (LuaJIT binds them to the GLOBAL, leaving the
     * module-local untouched; a Java static-field write would pollute
     * later readers) — covered by
     * {@link #testFunctionBodyModuleFieldAccessGuards()}.
     */
    /**
     * Function-body access to a module field is governed by the field's
     * declaration order relative to the function:
     * <ul>
     * <li>a function declared AFTER the field reads/writes the
     * module-local upvalue — emitted as a plain static-field access,
     * full parity (the canonical write-then-read shape is allowed and
     * runs identically on both backends, pinned by the cross-backend
     * fixture {@code jvm-function-field-write-parity});</li>
     * <li>a function declared BEFORE the field does not capture the
     * module-local under LuaJIT (the local does not exist when the
     * function value is created): every access in its body binds to the
     * GLOBAL of the same name. A no-prior-write read reads the global
     * nil at call time and fails (E8001) while Java would silently read
     * the initialized static field; a write targets the global — the
     * module-local is untouched, so later readers observe the
     * initializer value — while Java would write the static field and
     * pollute every later reader. Both shapes are E6000 (the
     * write-then-read shape included: the function's own read observes
     * the global write under LuaJIT, but the polluted Java field remains
     * observable by later readers).</li>
     * </ul>
     */
    private static void testFunctionBodyModuleFieldAccessGuards() throws Exception {
        // v1.2 grammar gate: module fields were removed, so every
        // function-body module-field access shape (declared-before and
        // declared-after) is a frontend parse error (E1049). The
        // function-local access rules they guarded survive unchanged.
        List<String> shapes = List.of(
            """
            let x: int = 1;
            function f(): int { x = 5; return x; }
            export function test(): int { return f() + x; }
            """,
            """
            function f(): int { return x; }
            let x: int = 5;
            export function test(): int { return f(); }
            """,
            """
            function f(): null { if (true) { x = 5; } }
            let x: int = 1;
            export function test(): int { f(); return 1; }
            """);
        for (String shape : shapes) {
            Frontend f = compileFrontend(shape, "jvmtest-field-access-shape.deal");
            check(f.errors().stream().anyMatch(d -> "E1049".equals(d.code())),
                "function-body module-field access shape rejected with E1049: "
                    + f.errors());
        }
    
    }

    private static void testAssignmentBeforeDeclarationRejected() throws Exception {
        System.out.println("-- Assignment before declaration → E6000 --");

        List<String> rejected = List.of(
            // the reviewer's exact repro: assignment statement before the let
            "export function test(): int { x = 5; let x: int = 1; return x; }",
            // same write inside a block
            """
            export function test(): int {
              let r: int = 0;
              { x = 5; }
              let x: int = 1;
              return x;
            }
            """,
            // same write inside an if body
            """
            export function test(): int {
              if (true) { x = 5; }
              let x: int = 1;
              return x;
            }
            """,
            // assignment in a return value position
            """
            export function test(): int {
              if (true) { return x = 5; }
              let x: int = 1;
              return x;
            }
            """,
            // assignment in a call-argument position
            """
            function f(y: int): int { return y; }
            export function test(): int {
              let r: int = f(x = 5);
              let x: int = 1;
              return x;
            }
            """);

        for (String source : rejected) {
            Frontend f = compileFrontend(source, "jvmtest-assignbefore.deal");
            if (!f.errors().isEmpty()) {
                fail("checker must accept the assignment-before-declaration "
                    + "probe (the backend rejects it): " + f.errors());
                continue;
            }
            JvmBackend.JvmCodegenResult res =
                JvmBackend.generate(f.program(), f.checkResult(),
                    "jvmtest-assignbefore.deal", "main");
            check(res.hasErrors(), "backend rejects the assignment before "
                + "declaration");
            check(res.diagnostics().stream().anyMatch(d -> "E6000".equals(d.code())),
                "E6000 for the assignment before declaration: "
                    + res.diagnostics());
        }

        // v1.2 grammar gate: the allowed v1.1 module-field write shapes
        // (a module-level let) were removed with module fields — E1049.
        Frontend fieldShadowShape = compileFrontend("""
            let x: int = 1;
            export function test(): int { x = 5; return x; }
            """, "jvmtest-assignfieldshadow.deal");
        check(fieldShadowShape.errors().stream()
                .anyMatch(d -> "E1049".equals(d.code())),
            "module-field shadow write rejected with E1049: "
                + fieldShadowShape.errors());

        Frontend moduleLaterShape = compileFrontend("""
            x = 5;
            let x: int = 1;
            export function test(): int { return x; }
            """, "jvmtest-assignmodulelater.deal");
        check(moduleLaterShape.errors().stream()
                .anyMatch(d -> "E1049".equals(d.code())),
            "module-level later-field write rejected with E1049: "
                + moduleLaterShape.errors());
    }

    /**
     * Dead code after a statement that cannot complete normally must never
     * be emitted: LuaJIT never executes it and javac rejects it as
     * unreachable (JLS §14.21) — emitting it produced exactly the broken
     * artifact the round-4 reviewer found (`return 3;` after a complete
     * all-returning if/else, reported as success by the CLI). Completion
     * tracking: a return cannot complete normally; an if/else whose
     * branches all cannot complete normally cannot complete normally; a
     * block ending in such a statement cannot complete normally. Skipping
     * is semantics-preserving (dead code never runs under LuaJIT) and
     * keeps every artifact valid Java.
     */
    private static void testDeadCodeAfterNonCompletingStatements() throws Exception {
        System.out.println("-- Dead code after non-completing statements is skipped --");

        // The reviewer's exact repro: a dead return after an all-returning
        // if/else. javac must accept the artifact and test() must return 1.
        ExecResult repro = compileAndRunJvm(
            "export function test(): int { if (true) { return 1; } "
                + "else { return 2; } return 3; }",
            "deadcodeifelse");
        check(repro.exitCode() == 0, "dead code after complete if/else exits 0");
        check(repro.output().contains("1"),
            "only the live path runs: " + repro.output());

        // Dead return after return (function level).
        ExecResult afterReturn = compileAndRunJvm(
            "export function test(): int { return 1; return 2; }", "deadcodereturn");
        check(afterReturn.exitCode() == 0, "dead return after return exits 0");
        check(afterReturn.output().contains("1"),
            "first return wins: " + afterReturn.output());

        // Dead code at block level: a block ending in a return cannot fall
        // through, so the following return, dead let, and dead standalone
        // expression statement are all skipped.
        ExecResult block = compileAndRunJvm(
            "export function test(): int { { return 1; } return 2; "
                + "let x: int = 5; x + 1; return x; }",
            "deadcodeblock");
        check(block.exitCode() == 0, "block-level dead code exits 0");
        check(block.output().contains("1"),
            "block-ending return wins: " + block.output());

        // A dead else-if chain whose condition hoists a null-typed
        // side-effecting call (the round-4 stress shape) plus a dead
        // trailing return: valid Java, helper never called.
        ExecResult chain = compileAndRunJvm("""
            import * as console from "std/console"
            function helper(): null { console.log("helper-ran"); }
            export function test(): int {
              if (true) { return 1; }
              else if (helper() === null) { return 2; }
              else { return 3; }
              return 4;
            }
            """, "deadcodechain");
        check(chain.exitCode() == 0, "dead else-if chain exits 0");
        check(chain.output().contains("1"), "live branch runs: " + chain.output());
        check(!chain.output().contains("helper-ran"),
            "the dead branch's hoisted call never runs: " + chain.output());

        // No over-skipping: an if WITHOUT else can complete normally, so
        // the statement after it is live and must still be emitted.
        ExecResult live = compileAndRunJvm(
            "export function test(): int { let x: boolean = false; "
                + "if (x) { return 1; } return 2; }",
            "deadcodenoskip");
        check(live.exitCode() == 0, "live code after open if exits 0");
        check(live.output().contains("2"),
            "statement after an open if is not skipped: " + live.output());

        // Emission assertions: dead statements must not appear in the
        // generated Java source at all.
        Frontend f = compileFrontend(
            "export function test(): int { return 1; return 2; }",
            "jvmtest-deadcode-emit.deal");
        if (f.errors().isEmpty()) {
            JvmBackend.JvmCodegenResult res =
                JvmBackend.generate(f.program(), f.checkResult(),
                    "jvmtest-deadcode-emit.deal", "main");
            check(!res.hasErrors(), "dead-code program has no diagnostics");
            check(res.source().contains("return 1L;"),
                "the live return is emitted");
            check(!res.source().contains("return 2L;"),
                "the dead return is skipped: " + res.source());
        } else {
            fail("checker must accept the dead-code probe: " + f.errors());
        }

        Frontend f2 = compileFrontend(
            "export function test(): int { if (true) { return 1; } "
                + "else { return 2; } let x: int = 5; return x; }",
            "jvmtest-deadcode-emit2.deal");
        if (f2.errors().isEmpty()) {
            JvmBackend.JvmCodegenResult res =
                JvmBackend.generate(f2.program(), f2.checkResult(),
                    "jvmtest-deadcode-emit2.deal", "main");
            check(!res.hasErrors(), "dead-let program has no diagnostics");
            check(!res.source().contains("long x = 5L;"),
                "the dead let declaration is skipped: " + res.source());
        } else {
            fail("checker must accept the dead-let probe: " + f2.errors());
        }
    }

    private static void testRuntimeErrorCodes() throws Exception {
        System.out.println("-- Runtime error codes via javac + java --");

        ExecResult divZero = compileAndRunJvm(
            "export function test(): int { let x: int = 1 / 0; return x; }",
            "divzero");
        check(divZero.exitCode() == 1, "int division by zero exits 1");
        check(divZero.output().contains("DEAL_ERROR_CODE: E8005"),
            "E8005 reported: " + divZero.output());

        ExecResult overflow = compileAndRunJvm(
            "export function test(): int { return 9223372036854775807 + 1; }",
            "overflow");
        check(overflow.exitCode() == 1, "int overflow exits 1");
        check(overflow.output().contains("DEAL_ERROR_CODE: E8004"),
            "E8004 reported: " + overflow.output());

        ExecResult intConvert = compileAndRunJvm(
            "export function test(): int { return int(2.5); }",
            "intconvert");
        check(intConvert.exitCode() == 1, "int(2.5) exits 1");
        check(intConvert.output().contains("DEAL_ERROR_CODE: E8001"),
            "E8001 reported: " + intConvert.output());

        ExecResult negExp = compileAndRunJvm(
            "export function test(): int { return 2 ** -1; }",
            "negexp");
        check(negExp.exitCode() == 1, "negative int exponent exits 1");
        check(negExp.output().contains("DEAL_ERROR_CODE: E8006"),
            "E8006 reported for negative exponent: " + negExp.output());

        // Extreme power: Math.pow overflows to Infinity; LuaJIT's check_int
        // reports E8001 ("expected int, got infinity"), not E8004.
        ExecResult powInf = compileAndRunJvm(
            "export function test(): int { return 10 ** 400; }",
            "powinf");
        check(powInf.exitCode() == 1, "extreme power exits 1");
        check(powInf.output().contains("DEAL_ERROR_CODE: E8001"),
            "E8001 reported for infinite power (LuaJIT check_int alignment): "
                + powInf.output());

        ExecResult ok = compileAndRunJvm(
            "export function test(): int { return int(2.0) + 3; }",
            "ok");
        check(ok.exitCode() == 0, "valid program exits 0");
        check(ok.output().contains("5"), "valid program prints 5: " + ok.output());
    }

    /**
     * DEAL int safe range ±(2^53-1) = ±9007199254740991 (deal/runtime.lua
     * check_int; spec "int | checked ... [-(2^53-1), 2^53-1]"). Every
     * int-producing operation must enforce the bound with E8004 — the
     * helpers check only Java long bounds (±2^63) otherwise, silently
     * computing values LuaJIT rejects (`9007199254740991 + 1` must raise
     * E8004, not print 9007199254740992). Int literals outside the safe
     * range are checked at their point of use (LuaJIT silently rounds such
     * literals to doubles inside arithmetic — e.g. `9223372036854775807
     * % 2` computes 0 there — which the JVM backend refuses to reproduce:
     * it raises E8004 for the invalid int value instead of silently
     * diverging).
     */
    private static void testIntSafeRange() throws Exception {
        System.out.println("-- Int safe range ±(2^53-1) (javac + java) --");

        // The boundary itself is in range (check_int's comparison is
        // exclusive of values outside, inclusive of the boundary).
        ExecResult maxOk = compileAndRunJvm(
            "export function test(): int { return 9007199254740991; }",
            "intmaxok");
        check(maxOk.exitCode() == 0, "2^53-1 exits 0: " + maxOk.output());
        check(maxOk.output().contains("9007199254740991"),
            "2^53-1 prints the value: " + maxOk.output());

        ExecResult minOk = compileAndRunJvm(
            "export function test(): int { return -9007199254740991; }",
            "intminok");
        check(minOk.exitCode() == 0, "-(2^53-1) exits 0: " + minOk.output());
        check(minOk.output().contains("-9007199254740991"),
            "-(2^53-1) prints the value: " + minOk.output());

        ExecResult boundaryAdd = compileAndRunJvm(
            "export function test(): int { return 9007199254740990 + 1; }",
            "intboundaryadd");
        check(boundaryAdd.exitCode() == 0
                && boundaryAdd.output().contains("9007199254740991"),
            "in-range add stays in range: " + boundaryAdd.output());

        // v1.2 grammar gate: the module-level out-of-range literal shape
        // (a module-level let) was removed — E1049. The in-function
        // out-of-range boundary is pinned by the int-boundary tests above.
        Frontend modLitShape = compileFrontend("""
            let x: int = 9223372036854775807;
            export function test(): int { return 1; }
            """, "jvmtest-modintlit.deal");
        check(modLitShape.errors().stream()
                .anyMatch(d -> "E1049".equals(d.code())),
            "module-level literal field rejected with E1049: " + modLitShape.errors());

        // intFromNumber boundary: int(9007199254740991.0) is in range.
        ExecResult convOk = compileAndRunJvm(
            "export function test(): int { return int(9007199254740991.0); }",
            "intconvok");
        check(convOk.exitCode() == 0
                && convOk.output().contains("9007199254740991"),
            "int(2^53-1) stays in range: " + convOk.output());

        // Every int-producing operation enforces the bound with E8004.
        String[][] overflowCases = {
            {"9007199254740991 + 1", "intadd"},
            {"9007199254740991 - (-1)", "intsub"},
            {"9007199254740991 * 2", "intmul"},
            {"-9007199254740991 - 1", "intsubneg"},
            {"-9007199254740992", "intneglit"},
            {"2 ** 53", "intpow53"},
            {"2 ** 62", "intpow62"},
            {"(-2) ** 63", "intpowneg63"},
            {"int(9007199254740992.0)", "intfromnum"},
            {"9223372036854775807", "intlit"},
            {"9223372036854775807 % 2", "intmodlit"},
            {"9223372036854775807 / 1", "intdivlit"},
        };
        for (String[] c : overflowCases) {
            ExecResult r = compileAndRunJvm(
                "export function test(): int { return " + c[0] + "; }", c[1]);
            check(r.exitCode() == 1, c[1] + " exits 1: " + r.output());
            check(r.output().contains("DEAL_ERROR_CODE: E8004"),
                c[1] + " reports E8004: " + r.output());
        }

        // Emission shape: helpers check their results through checkInt with
        // the safe-range bound, and out-of-range literals are wrapped at
        // their point of use.
        Frontend f = compileFrontend(
            "export function test(): int { return 9223372036854775807; }",
            "jvmtest-intrange.deal");
        check(f.errors().isEmpty(), "int-range frontend clean: " + f.errors());
        if (f.errors().isEmpty()) {
            JvmBackend.JvmCodegenResult res = JvmBackend.generate(
                f.program(), f.checkResult(), "jvmtest-intrange.deal", "main");
            check(!res.hasErrors(), "int-range codegen clean: "
                + res.diagnostics());
            if (!res.hasErrors()) {
                String java = res.source();
                check(java.contains("return checkInt(9223372036854775807L);"),
                    "out-of-range literal wrapped in checkInt");
                check(java.contains("static long checkInt(long v)"),
                    "checkInt helper emitted");
                check(java.contains("9007199254740991L"),
                    "safe-range bound present");
                check(java.contains("checkInt(java.lang.Math.addExact(a, b))"),
                    "intAdd checks its result against the safe range");
                check(java.contains("p > 9007199254740991.0"),
                    "intPow enforces the safe range, not the long bound");
                check(java.contains("v > 9007199254740991.0"),
                    "intFromNumber enforces the safe range, not the long bound");
            }
        }
    }

    /**
     * Unqualified java.lang references in generated code (System.out,
     * Math.addExact, Double.isNaN, the String/Void type names, …) would
     * bind to a user's field or local named System/Math/Double/String/Void
     * instead of java.lang, producing an artifact javac rejects while the
     * CLI reports success. Every generated reference must be fully
     * qualified.
     */
    private static void testJavaLangNameCollisions() throws Exception {
        // Locals shadowing every java.lang name the generated code touches.
        ExecResult locals = compileAndRunJvm("""
            import * as console from "std/console"
            export function test(): int {
              let System: int = 1;
              let Math: int = 2;
              let Double: int = 3;
              let String: int = 4;
              let Void: int = 5;
              let Integer: int = 6;
              let Character: int = 7;
              let RuntimeException: int = 8;
              let ArithmeticException: int = 9;
              console.log("names-ok");
              return System + Math + Double + String + Void
                   + Integer + Character + RuntimeException + ArithmeticException;
            }
            """, "namelocals");
        check(locals.exitCode() == 0, "shadowing locals exit 0: " + locals.output());
        check(locals.output().contains("45"),
            "shadowing locals compute 45: " + locals.output());

        // v1.2 grammar gate: module-field java.lang-name collision shapes
        // (module-level lets) were removed with module fields — E1049.
        // The in-function collision surface is pinned by namelocals and
        // the local variants below.
        List<String> moduleFieldShapes = List.of(
            """
            let Math: int = 1;
            export function test(): int { return Math + 2; }
            """,
            """
            let Double: int = 1;
            export function test(): int { return Double + 1; }
            """,
            """
            import * as console from "std/console"
            let System: int = 1;
            console.log("sys-field-ok");
            export function test(): int { return System; }
            """,
            """
            let Integer: int = 0;
            let Character: int = 0;
            export function test(): boolean {
              return "a" < "b";
            }
            """,
            """
            let RuntimeException: int = 1;
            export function test(): int { return RuntimeException / 0; }
            """);
        for (String shape : moduleFieldShapes) {
            Frontend f = compileFrontend(shape, "jvmtest-name-field-shape.deal");
            check(f.errors().stream().anyMatch(d -> "E1049".equals(d.code())),
                "java.lang-name module-field shape rejected with E1049: "
                    + f.errors());
        }

        // Local Double + a 1e999 literal (renders as
        // Double.POSITIVE_INFINITY).
        ExecResult doubleLocal = compileAndRunJvm("""
            export function test(): number {
              let Double: int = 1;
              let n: number = 1e999;
              return n + number(Double);
            }
            """, "doublelocal");
        check(doubleLocal.exitCode() == 0
                && doubleLocal.output().contains("Infinity"),
            "local Double + 1e999 literal: " + doubleLocal.output());

        // Local Math + number ** (inline Math.pow emission).
        ExecResult mathPow = compileAndRunJvm("""
            export function test(): number {
              let Math: int = 0;
              return 2.0 ** 3.0;
            }
            """, "mathpow");
        check(mathPow.exitCode() == 0 && mathPow.output().contains("8.0"),
            "local Math + number **: " + mathPow.output());

        // String-typed parameter named String (the mapped type name).
        ExecResult stringParam = compileAndRunJvm("""
            function f(String: string): string { return String; }
            export function test(): string { return f("param-ok"); }
            """, "stringparam");
        check(stringParam.exitCode() == 0
                && stringParam.output().contains("param-ok"),
            "parameter named String: " + stringParam.output());

        // Null-typed local named Void (the mapped boxed-null type name).
        ExecResult voidLocal = compileAndRunJvm("""
            export function test(): int {
              let Void: null = null;
              return 1;
            }
            """, "voidlocal");
        check(voidLocal.exitCode() == 0 && voidLocal.output().contains("1"),
            "local named Void: " + voidLocal.output());

        // Emission shape: no unqualified java.lang references remain.
        Frontend f = compileFrontend("""
            import * as console from "std/console"
            export function test(): number {
              console.log("qualified");
              return 1e999;
            }
            """, "jvmtest-qualified.deal");
        check(f.errors().isEmpty(), "qualified-name frontend clean: " + f.errors());
        if (f.errors().isEmpty()) {
            JvmBackend.JvmCodegenResult res = JvmBackend.generate(
                f.program(), f.checkResult(), "jvmtest-qualified.deal", "main");
            check(!res.hasErrors(), "qualified-name codegen clean: "
                + res.diagnostics());
            if (!res.hasErrors()) {
                String java = res.source();
                check(java.contains("java.lang.System.out.println"),
                    "console.log targets java.lang.System.out");
                check(java.contains("java.lang.Math.addExact"),
                    "helpers use java.lang.Math.addExact");
                check(java.contains("java.lang.Double.isNaN"),
                    "helpers use java.lang.Double.isNaN");
                check(java.contains("java.lang.Double.POSITIVE_INFINITY"),
                    "non-finite literals use java.lang.Double constants");
                check(java.contains("extends java.lang.RuntimeException"),
                    "DealError extends java.lang.RuntimeException");
            }
        }
    
    }

    /**
     * The use-before-declaration guard must walk every condition of an
     * if/else-if chain: emitIf/emitIfContinuation emit the follow-on
     * conditions directly (no per-statement guard runs for them), so a
     * later-declared variable in an else-if condition previously emitted
     * an illegal forward reference (module level) or a
     * cannot-find-symbol reference (function body) that javac rejected
     * after the CLI reported success.
     */
    private static void testElseIfChainUseBeforeDeclaration() throws Exception {
        System.out.println("-- Else-if chain conditions: use-before-declaration → E6000 --");

        // v1.2 grammar gate: a module-level chain is a frontend parse
        // error (E1049) — module-level statements were removed.
        Frontend moduleCase = compileFrontend("""
            if (true) { } else if (z === 2) { }
            let z: int = 2;
            export function test(): int { return 1; }
            """, "jvmtest-elseif-module.deal");
        check(moduleCase.errors().stream()
                .anyMatch(d -> "E1049".equals(d.code())),
            "module-level else-if chain rejected with E1049: "
                + moduleCase.errors());

        // Function-body chain: `x` is declared after the chain.
        Frontend bodyCase = compileFrontend(
            "export function test(): int { if (true) {} else if (x === 2) {} "
                + "let x: int = 2; return 1; }",
            "jvmtest-elseif-body.deal");
        check(bodyCase.errors().isEmpty(),
            "function-body else-if chain frontend clean: " + bodyCase.errors());
        if (bodyCase.errors().isEmpty()) {
            JvmBackend.JvmCodegenResult res = JvmBackend.generate(
                bodyCase.program(), bodyCase.checkResult(),
                "jvmtest-elseif-body.deal", "main");
            check(res.hasErrors(),
                "function-body else-if forward reference rejected");
            check(res.diagnostics().stream()
                    .anyMatch(d -> d.message().contains("'x'")),
                "function-body diagnostic names x: " + res.diagnostics());
        }

        // Deep chain: the guard must walk past several else-if links to the
        // offending condition.
        Frontend deepCase = compileFrontend(
            "export function test(): int { let a: int = 1; "
                + "if (a === 0) {} else if (a === 1) {} else if (b === 2) {} "
                + "let b: int = 2; return 1; }",
            "jvmtest-elseif-deep.deal");
        check(deepCase.errors().isEmpty(),
            "deep else-if chain frontend clean: " + deepCase.errors());
        if (deepCase.errors().isEmpty()) {
            JvmBackend.JvmCodegenResult res = JvmBackend.generate(
                deepCase.program(), deepCase.checkResult(),
                "jvmtest-elseif-deep.deal", "main");
            check(res.hasErrors(), "deep-chain forward reference rejected");
            check(res.diagnostics().stream()
                    .anyMatch(d -> d.message().contains("'b'")),
                "deep-chain diagnostic names b: " + res.diagnostics());
        }

        // Positive: chain conditions reading already-declared variables
        // stay clean and run (function body).
        ExecResult ok = compileAndRunJvm("""
            export function test(): int {
              let x: int = 2;
              if (x === 1) { return 0; } else if (x === 2) { return 7; }
              return 3;
            }
            """, "elseifok");
        check(ok.exitCode() == 0 && ok.output().contains("7"),
            "clean else-if chain runs: " + ok.output());

        // v1.2 grammar gate: the module-level positive chain shape was
        // removed with module-level statements (E1049); the function-body
        // positive chain above covers the semantics.
        Frontend modOkShape = compileFrontend("""
            let z: int = 2;
            let hit: boolean = false;
            if (z === 1) { } else if (z === 2) { hit = true; }
            export function test(): int { if (hit) { return 1; } return 0; }
            """, "jvmtest-elseifmodok.deal");
        check(modOkShape.errors().stream()
                .anyMatch(d -> "E1049".equals(d.code())),
            "module-level positive chain rejected with E1049: "
                + modOkShape.errors());
    }

    /**
     * Number literals that overflow to Infinity (checker-accepted: the
     * parser stores Double.parseDouble("1e999") with no range check) must
     * render as the Double constants — a bare {@code Infinity} identifier
     * makes javac reject an artifact the CLI reported as successful. The
     * Lua backend emits {@code (1/0)} for the same literal.
     */
    private static void testNonFiniteNumberLiterals() throws Exception {
        System.out.println("-- Non-finite number literals (javac + java) --");

        ExecResult inf = compileAndRunJvm(
            "export function test(): number { let x: number = 1e999; return x; }",
            "inflit");
        check(inf.exitCode() == 0, "1e999 exits 0");
        check(inf.output().contains("Infinity"),
            "1e999 prints Infinity: " + inf.output());

        ExecResult negInf = compileAndRunJvm(
            "export function test(): number { let x: number = -1e999; return x; }",
            "neginflit");
        check(negInf.exitCode() == 0, "-1e999 exits 0");
        check(negInf.output().contains("-Infinity"),
            "-1e999 prints -Infinity: " + negInf.output());

        // Infinity compares equal to itself, like LuaJIT's (1/0) == (1/0).
        ExecResult eq = compileAndRunJvm(
            "export function test(): boolean { return 1e999 === 1e999; }",
            "infeq");
        check(eq.exitCode() == 0, "1e999 === 1e999 exits 0");
        check(eq.output().contains("true"),
            "1e999 === 1e999 is true: " + eq.output());

        // int(1e999) reports E8001, matching LuaJIT's check_int on infinity.
        ExecResult conv = compileAndRunJvm(
            "export function test(): int { return int(1e999); }",
            "infconv");
        check(conv.exitCode() == 1, "int(1e999) exits 1");
        check(conv.output().contains("DEAL_ERROR_CODE: E8001"),
            "int(1e999) reports E8001: " + conv.output());

        // Emission: the Double constant, never a bare Infinity identifier.
        Frontend f = compileFrontend(
            "export function test(): number { let x: number = 1e999; return x; }",
            "jvmtest-inflit.deal");
        check(f.errors().isEmpty(), "non-finite literal frontend clean: " + f.errors());
        if (f.errors().isEmpty()) {
            JvmBackend.JvmCodegenResult res = JvmBackend.generate(
                f.program(), f.checkResult(), "jvmtest-inflit.deal", "main");
            check(!res.hasErrors(), "non-finite literal codegen clean: "
                + res.diagnostics());
            if (!res.hasErrors()) {
                check(res.source().contains("= java.lang.Double.POSITIVE_INFINITY;"),
                    "1e999 renders as java.lang.Double.POSITIVE_INFINITY");
                check(!res.source().matches("(?s).*= Infinity;.*"),
                    "no bare Infinity identifier is emitted");
            }
        }
    }

    /**
     * {@code &&}/{@code ||} short-circuit: a null-typed side-effecting call
     * in a non-leading operand must NOT run when the left operand already
     * decides the result (LuaJIT's {@code and}/{@code or} semantics). The
     * hoisted pre-statements are guarded by the left operand, never flushed
     * unconditionally.
     */
    private static void testShortCircuitPreservation() throws Exception {
        System.out.println("-- && / || short-circuit preservation (javac + java) --");

        ExecResult andSkip = compileAndRunJvm("""
            import * as console from "std/console"
            function helper(): null { console.log("helper-ran"); }
            export function test(): boolean { return false && helper() === null; }
            """, "scandskip");
        check(andSkip.exitCode() == 0, "false && ... exits 0");
        check(andSkip.output().contains("false"),
            "false && ... computes false: " + andSkip.output());
        check(!andSkip.output().contains("helper-ran"),
            "right operand of false && is NOT evaluated: " + andSkip.output());

        ExecResult orSkip = compileAndRunJvm("""
            import * as console from "std/console"
            function helper(): null { console.log("helper-ran"); }
            export function test(): boolean { return true || helper() === null; }
            """, "scorskip");
        check(orSkip.exitCode() == 0, "true || ... exits 0");
        check(orSkip.output().contains("true"),
            "true || ... computes true: " + orSkip.output());
        check(!orSkip.output().contains("helper-ran"),
            "right operand of true || is NOT evaluated: " + orSkip.output());

        ExecResult andRuns = compileAndRunJvm("""
            import * as console from "std/console"
            function helper(): null { console.log("helper-ran"); }
            export function test(): boolean { return true && helper() === null; }
            """, "scandruns");
        check(andRuns.exitCode() == 0, "true && ... exits 0");
        check(andRuns.output().contains("helper-ran"),
            "right operand of true && IS evaluated: " + andRuns.output());
        check(andRuns.output().contains("true"),
            "true && (null === null) computes true: " + andRuns.output());

        ExecResult orRuns = compileAndRunJvm("""
            import * as console from "std/console"
            function helper(): null { console.log("helper-ran"); }
            export function test(): boolean { return false || helper() === null; }
            """, "scorruns");
        check(orRuns.exitCode() == 0, "false || ... exits 0");
        check(orRuns.output().contains("helper-ran"),
            "right operand of false || IS evaluated: " + orRuns.output());
        check(orRuns.output().contains("true"),
            "false || (null === null) computes true: " + orRuns.output());

        // Initializer position: the reviewer's `let b: boolean = ...` shape.
        ExecResult initSkip = compileAndRunJvm("""
            import * as console from "std/console"
            function helper(): null { console.log("helper-ran"); }
            export function test(): int {
              let b: boolean = false && helper() === null;
              if (b) { console.log("b-true"); return 1; }
              console.log("b-false");
              return 0;
            }
            """, "scinitskip");
        check(initSkip.exitCode() == 0, "guarded initializer exits 0");
        check(initSkip.output().contains("b-false"),
            "initializer computes false: " + initSkip.output());
        check(!initSkip.output().contains("helper-ran"),
            "guarded initializer skips the call: " + initSkip.output());

        // Nested short-circuit: the inner guard nests inside the outer one.
        ExecResult nested = compileAndRunJvm("""
            import * as console from "std/console"
            function helper(): null { console.log("helper-ran"); }
            export function test(): boolean {
              return false && (true || helper() === null);
            }
            """, "scnested");
        check(nested.exitCode() == 0, "nested short-circuit exits 0");
        check(!nested.output().contains("helper-ran"),
            "nested guarded operand is NOT evaluated: " + nested.output());
        check(nested.output().contains("false"),
            "nested short-circuit computes false: " + nested.output());

        ExecResult nestedRuns = compileAndRunJvm("""
            import * as console from "std/console"
            function helper(): null { console.log("helper-ran"); }
            export function test(): boolean {
              return true && (false || helper() === null);
            }
            """, "scnestedruns");
        check(nestedRuns.exitCode() == 0, "nested short-circuit (runs) exits 0");
        check(nestedRuns.output().contains("helper-ran"),
            "nested reachable operand IS evaluated: " + nestedRuns.output());

        // Left-operand hoists stay unconditional; right-operand hoists are
        // guarded — evaluation order preserved.
        ExecResult both = compileAndRunJvm("""
            import * as console from "std/console"
            function first(): null { console.log("first-ran"); }
            function helper(): null { console.log("helper-ran"); }
            export function test(): boolean {
              return first() === null && helper() === null;
            }
            """, "scboth");
        check(both.exitCode() == 0, "left+right hoists exit 0");
        check(both.output().contains("first-ran"),
            "left-operand call runs: " + both.output());
        check(both.output().contains("helper-ran"),
            "guarded right-operand call runs (left was true): " + both.output());
        check(both.output().indexOf("first-ran")
                < both.output().indexOf("helper-ran"),
            "evaluation order preserved: " + both.output());

        // v1.2 grammar gate: module-level guarded field initializers were
        // removed with module-level lets (E1049); the guarded-operand
        // semantics run in-function in the shapes above.
        Frontend modSkipShape = compileFrontend("""
            import * as console from "std/console"
            function helper(): null { console.log("helper-ran"); }
            let b: boolean = false && helper() === null;
            export function test(): null { console.log("test-ran"); }
            """, "jvmtest-scmodskip.deal");
        check(modSkipShape.errors().stream()
                .anyMatch(d -> "E1049".equals(d.code())),
            "module-level guarded initializer rejected with E1049: "
                + modSkipShape.errors());

        ExecResult modRuns = compileAndRunJvm("""
            import * as console from "std/console"
            function helper(): null { console.log("helper-ran"); }
            export function test(): null {
              let b: boolean = true && helper() === null;
              console.log("test-ran");
            }
            """, "scmodruns");
        check(modRuns.exitCode() == 0, "guarded initializer (runs) exits 0");
        check(modRuns.output().contains("helper-ran"),
            "reachable guarded operand IS evaluated: " + modRuns.output());

        // Emission assertions: no lambdas; the guarded lowering runs
        // in-function (the module-level field variant is an E1049 gate
        // above — module fields were removed in v1.2).
        Frontend f = compileFrontend("""
            import * as console from "std/console"
            function helper(): null { console.log("helper-ran"); }
            export function test(): null {
              let b: boolean = false && helper() === null;
              console.log("test-ran");
            }
            """, "jvmtest-sc.deal");
        check(f.errors().isEmpty(), "short-circuit probe frontend clean: " + f.errors());
        if (f.errors().isEmpty()) {
            JvmBackend.JvmCodegenResult res = JvmBackend.generate(
                f.program(), f.checkResult(), "jvmtest-sc.deal", "main");
            check(!res.hasErrors(), "short-circuit probe codegen clean: "
                + res.diagnostics());
            if (!res.hasErrors()) {
                check(!res.source().contains(" -> "),
                    "short-circuit lowering emits no lambda");
                check(res.source().contains("boolean b = __sc0;"),
                    "guarded initializer assigns the temporary in-function");
            }
        }
    }

    /** String ordering follows Unicode scalar values (LuaJIT's UTF-8
     * bytewise order), not UTF-16 code-unit order — supplementary
     * characters order after the whole BMP, exactly as under LuaJIT. */
    /**
     * DEAL/LuaJIT evaluate expressions strictly left to right and stop at
     * the first runtime error; a null-typed side-effecting call in a value
     * position is hoisted into a pre-statement flushed before the
     * containing statement, which used to make an EARLIER inline
     * side-effecting operand of the same statement run after it
     * ({@code f(g(), console.log("x"))} printed "x" before g() ran).
     * {@code emitOperandsInOrder} materializes such operands into
     * temporaries in evaluation order; this test pins the observable
     * order in every combination position via javac+java subprocesses.
     */
    private static void testEvaluationOrderPreservation() throws Exception {
        System.out.println("-- Evaluation-order preservation (javac + java) --");

        // The reviewer's exact repro: call-argument position, return.
        ExecResult ret = compileAndRunJvm("""
            import * as console from "std/console"
            function g(): int { console.log("g-ran"); return 7; }
            function f(y: int, x: null): int { return y; }
            export function test(): int { return f(g(), console.log("x")); }
            """, "evalorder-return");
        check(ret.exitCode() == 0, "call-args eval order exits 0");
        check(ret.output().indexOf("g-ran") >= 0
                && ret.output().indexOf("g-ran") < ret.output().indexOf("x"),
            "g() runs before the hoisted console.log (left to right): "
                + ret.output());

        // The same shape in a let-initializer position.
        ExecResult init = compileAndRunJvm("""
            import * as console from "std/console"
            function g(): int { console.log("g-ran"); return 7; }
            function f(y: int, x: null): int { return y; }
            export function test(): int {
              let r: int = f(g(), console.log("x"));
              return r;
            }
            """, "evalorder-init");
        check(init.exitCode() == 0, "initializer eval order exits 0");
        check(init.output().indexOf("g-ran") >= 0
                && init.output().indexOf("g-ran") < init.output().indexOf("x"),
            "initializer position: g() before console.log: " + init.output());

        // Standalone call-statement position.
        ExecResult stmt = compileAndRunJvm("""
            import * as console from "std/console"
            function g(): int { console.log("g-ran"); return 7; }
            function f(y: int, x: null): int { return y; }
            export function test(): null {
              f(g(), console.log("x"));
            }
            """, "evalorder-stmt");
        check(stmt.exitCode() == 0, "call-statement eval order exits 0");
        check(stmt.output().indexOf("g-ran") >= 0
                && stmt.output().indexOf("g-ran") < stmt.output().indexOf("x"),
            "call-statement position: g() before console.log: " + stmt.output());

        // If-condition position.
        ExecResult cond = compileAndRunJvm("""
            import * as console from "std/console"
            function g(): int { console.log("g-ran"); return 7; }
            function f(y: int, x: null): int { return y; }
            export function test(): null {
              if (f(g(), console.log("x")) === 7) { console.log("cond-ok"); }
            }
            """, "evalorder-cond");
        check(cond.exitCode() == 0, "if-condition eval order exits 0");
        check(cond.output().indexOf("g-ran") >= 0
                && cond.output().indexOf("g-ran") < cond.output().indexOf("x"),
            "if-condition position: g() before console.log: " + cond.output());

        // v1.2 grammar gate: the module-level field-initializer position
        // was removed with module-level lets (E1049); the in-function
        // initializer position above keeps the evaluation-order pin.
        Frontend fieldInitShape = compileFrontend("""
            import * as console from "std/console"
            function g(): int { console.log("g-ran"); return 7; }
            function f(y: int, x: null): int { return y; }
            let a: int = f(g(), console.log("x"));
            export function test(): int { return a; }
            """, "jvmtest-evalorder-fieldinit.deal");
        check(fieldInitShape.errors().stream()
                .anyMatch(d -> "E1049".equals(d.code())),
            "field-initializer position rejected with E1049: "
                + fieldInitShape.errors());

        // Non-leading && operand: the short-circuit guard keeps the order.
        ExecResult sc = compileAndRunJvm("""
            import * as console from "std/console"
            function g(): int { console.log("g-ran"); return 7; }
            function f(y: int, x: null): int { return y; }
            export function test(): boolean {
              return true && f(g(), console.log("x")) === 7;
            }
            """, "evalorder-sc");
        check(sc.exitCode() == 0, "short-circuit operand eval order exits 0");
        check(sc.output().indexOf("g-ran") >= 0
                && sc.output().indexOf("g-ran") < sc.output().indexOf("x"),
            "guarded && operand: g() before console.log: " + sc.output());

        // Reverse shape: hoisted operand first, inline call after — the
        // hoisted statement legitimately precedes the later inline call.
        ExecResult reverse = compileAndRunJvm("""
            import * as console from "std/console"
            function g(): int { console.log("g-ran"); return 7; }
            function f(x: null, y: int): int { return y; }
            export function test(): int { return f(console.log("x"), g()); }
            """, "evalorder-reverse");
        check(reverse.exitCode() == 0, "reverse shape exits 0");
        check(reverse.output().indexOf("x") >= 0
                && reverse.output().indexOf("x") < reverse.output().indexOf("g-ran"),
            "reverse shape: hoisted console.log before later g(): " + reverse.output());

        // Three mixed operands: hoisted, inline, hoisted.
        ExecResult mixed = compileAndRunJvm("""
            import * as console from "std/console"
            function g(): int { console.log("g-ran"); return 7; }
            function h(v: null, w: int, u: null): int { return w; }
            export function test(): int {
              return h(console.log("a"), g(), console.log("b"));
            }
            """, "evalorder-mixed");
        check(mixed.exitCode() == 0, "mixed three-operand shape exits 0");
        check(mixed.output().indexOf("a") >= 0
                && mixed.output().indexOf("a") < mixed.output().indexOf("g-ran")
                && mixed.output().indexOf("g-ran") < mixed.output().indexOf("b"),
            "a, then g(), then b (left to right): " + mixed.output());

        // Nested combination: the first operand itself hoists (k's
        // null-typed argument) AND still carries an inline call (k(…))
        // after its own hoisted statement; that inline call must run
        // before the second operand's hoisted print. LuaJIT order:
        // g-ran, y, k-ran, x.
        ExecResult nested = compileAndRunJvm("""
            import * as console from "std/console"
            function g(): int { console.log("g-ran"); return 1; }
            function k(x: null): int { console.log("k-ran"); return 2; }
            function f(y: int, x: null): int { return y; }
            export function test(): int {
              return f(g() + k(console.log("y")), console.log("x"));
            }
            """, "evalorder-nested");
        check(nested.exitCode() == 0, "nested combination exits 0");
        check(nested.output().indexOf("g-ran") >= 0
                && nested.output().indexOf("g-ran") < nested.output().indexOf("y")
                && nested.output().indexOf("y") < nested.output().indexOf("k-ran")
                && nested.output().indexOf("k-ran") < nested.output().indexOf("x"),
            "g-ran, y, k-ran, x (left to right through the nested "
                + "combination): " + nested.output());

        // Binary operands, both directions.
        ExecResult bin = compileAndRunJvm("""
            import * as console from "std/console"
            function g(): int { console.log("g-ran"); return 3; }
            function f(x: null): int { return 4; }
            export function test(): int { return g() + f(console.log("x")); }
            """, "evalorder-bin");
        check(bin.exitCode() == 0, "binary operands exit 0");
        check(bin.output().indexOf("g-ran") >= 0
                && bin.output().indexOf("g-ran") < bin.output().indexOf("x"),
            "left binary operand g() runs before the hoisted call: " + bin.output());

        ExecResult binRev = compileAndRunJvm("""
            import * as console from "std/console"
            function g(): int { console.log("g-ran"); return 3; }
            function f(x: null): int { return 4; }
            export function test(): int { return f(console.log("x")) + g(); }
            """, "evalorder-binrev");
        check(binRev.exitCode() == 0, "reversed binary operands exit 0");
        check(binRev.output().indexOf("x") >= 0
                && binRev.output().indexOf("x") < binRev.output().indexOf("g-ran"),
            "left hoisted operand precedes the later g(): " + binRev.output());

        // An earlier operand that RAISES must raise before the hoisted
        // call runs (LuaJIT stops at the first runtime error): a ** 400
        // raises E8004 and the console.log must never print.
        ExecResult raising = compileAndRunJvm("""
            import * as console from "std/console"
            function f(y: int, x: null): int { return y; }
            export function test(): int {
              let a: int = 2;
              return f(a ** 400, console.log("x"));
            }
            """, "evalorder-raise");
        check(raising.exitCode() != 0, "raising operand exits non-zero");
        check(!raising.output().contains("x"),
            "the hoisted console.log never runs when the earlier operand "
                + "raises: " + raising.output());
        check(raising.output().contains("E8004"),
            "the raising operand reports E8004: " + raising.output());

        // Emission shape: the earlier inline call is materialized into a
        // temporary BEFORE the hoisted statement, and no lambda is emitted.
        Frontend f = compileFrontend("""
            import * as console from "std/console"
            function g(): int { console.log("g-ran"); return 7; }
            function f(y: int, x: null): int { return y; }
            export function test(): int { return f(g(), console.log("x")); }
            """, "jvmtest-evalorder-emit.deal");
        check(f.errors().isEmpty(), "eval-order probe frontend clean: " + f.errors());
        if (f.errors().isEmpty()) {
            JvmBackend.JvmCodegenResult res = JvmBackend.generate(
                f.program(), f.checkResult(), "jvmtest-evalorder-emit.deal", "main");
            check(!res.hasErrors(), "eval-order source emits without diagnostics");
            if (!res.hasErrors()) {
                String src = res.source();
                int tempIdx = src.indexOf("__t0 = g();");
                int logIdx = src.indexOf("java.lang.System.out.println(\"x\");");
                check(tempIdx >= 0 && logIdx >= 0 && tempIdx < logIdx,
                    "the earlier inline call is materialized before the "
                        + "hoisted println: " + src);
                check(!src.contains(" -> "), "no lambda is emitted");
            }
        }
    }

    private static void testStringScalarOrdering() throws Exception {
        System.out.println("-- String scalar-value ordering (javac + java) --");

        ExecResult sup = compileAndRunJvm(
            "export function test(): int { if (\"\uD83D\uDE00\" < \"\uE000\") { return 1; } return 0; }",
            "suporder");
        check(sup.exitCode() == 0, "supplementary ordering exits 0");
        check(sup.output().contains("0"),
            "U+1F600 < U+E000 is false in scalar order (UTF-16 says true): "
                + sup.output());

        ExecResult supReverse = compileAndRunJvm(
            "export function test(): int { if (\"\uE000\" < \"\uD83D\uDE00\") { return 1; } return 0; }",
            "suporder2");
        check(supReverse.exitCode() == 0, "reverse supplementary ordering exits 0");
        check(supReverse.output().contains("1"),
            "U+E000 < U+1F600 is true in scalar order: " + supReverse.output());

        ExecResult supEq = compileAndRunJvm(
            "export function test(): int { if (\"\uD83D\uDE00\" === \"\uD83D\uDE00\") { return 1; } return 0; }",
            "supeq");
        check(supEq.exitCode() == 0, "supplementary equality exits 0");
        check(supEq.output().contains("1"),
            "supplementary equality is exact: " + supEq.output());

        ExecResult ascii = compileAndRunJvm(
            "export function test(): int { if (\"a\" < \"b\") { return 1; } return 0; }",
            "asciiorder");
        check(ascii.exitCode() == 0, "ASCII ordering exits 0");
        check(ascii.output().contains("1"),
            "ASCII ordering still works: " + ascii.output());

        Frontend f = compileFrontend(
            "export function test(): boolean { return \"\uD83D\uDE00\" < \"\uE000\"; }",
            "jvmtest-sup.deal");
        check(f.errors().isEmpty(), "scalar-ordering probe frontend clean: " + f.errors());
        if (f.errors().isEmpty()) {
            JvmBackend.JvmCodegenResult res = JvmBackend.generate(
                f.program(), f.checkResult(), "jvmtest-sup.deal", "main");
            check(!res.hasErrors(), "scalar-ordering probe codegen clean: "
                + res.diagnostics());
            if (!res.hasErrors()) {
                check(res.source().contains("scalarCompare("),
                    "string ordering goes through scalarCompare");
                check(!res.source().contains(".compareTo("),
                    "no UTF-16 compareTo for DEAL string ordering");
            }
        }
    }

    /** Module-level calls to functions whose bodies (transitively) read a
     * module field declared later than the call site are rejected with
     * E6000: LuaJIT fails at load with a nil read while Java would silently
     * read the field's default value. */
    private static void testModuleLevelCallReadingLaterField() throws Exception {
        // v1.2 grammar gate: every module-level call shape reading a later
        // module field (module-level lets removed) is a frontend parse
        // error (E1049) — the E6000 load-time analysis surface was removed
        // with the module-level statement grammar.
        List<String> rejected = List.of(
            """
            import * as console from "std/console"
            function f(): int { return x; }
            f();
            let x: int = 5;
            export function test(): null { console.log("test-ran"); }
            """,
            """
            import * as console from "std/console"
            f();
            let x: int = 5;
            function f(): int { return x; }
            export function test(): null { console.log("test-ran"); }
            """,
            """
            function f(): int { return g(); }
            function g(): int { return x; }
            f();
            let x: int = 5;
            export function test(): int { return 1; }
            """,
            """
            function f(): int { return x; }
            let y: int = f();
            let x: int = 5;
            export function test(): int { return y; }
            """,
            """
            function f(): int { return x; }
            if (f() === 1) { }
            let x: int = 5;
            export function test(): int { return 1; }
            """,
            """
            let x: int = f();
            function f(): int { return x; }
            export function test(): int { return x; }
            """);

        for (String source : rejected) {
            Frontend f = compileFrontend(source, "jvmtest-modcall.deal");
            check(f.errors().stream().anyMatch(d -> "E1049".equals(d.code())),
                "module-level call shape rejected with E1049: " + f.errors());
        }

        // Positive in-function shape: a block-local shadow of an outer
        // local is not an outer read.
        ExecResult shadow = compileAndRunJvm("""
            import * as console from "std/console"
            export function test(): int {
              let x: int = 5;
              { let x: int = 9; }
              return x;
            }
            """, "modcallshadow");
        check(shadow.exitCode() == 0, "local-shadow call exits 0");
        check(shadow.output().contains("5"),
            "block-local shadow does not overwrite the outer local: "
                + shadow.output());
    
    }

    /**
     * LuaJIT assigns each function value at its declaration point in source
     * order, so a module-level call that reaches a function declared at or
     * after the call site fails at load with a nil read — directly
     * (`f(); function f…`), transitively (`function f(): null { g(); } f();
     * function g…`: the callee is declared before the call but its body
     * reaches a later function), or from a field initializer (`let a: int =
     * f()` before `function f`). Java hoists methods and would silently run
     * them; the backend must reject these with E6000 instead. Calls whose
     * callee and transitive callees are all declared before the call site
     * stay allowed (LuaJIT parity, verified with real luajit runs).
     */
    private static void testModuleLevelCallBeforeFunctionDeclarationRejected()
            throws Exception {
        // v1.2 grammar gate: every module-level call shape (module-level
        // executable statements removed) is a frontend parse error
        // (E1049) — the E6000 call-before-declaration surface was removed
        // with the module-level statement grammar.
        List<String> rejected = List.of(
            """
            import * as console from "std/console"
            f();
            function f(): null { console.log("f-ran"); }
            export function test(): int { return 1; }
            """,
            """
            import * as console from "std/console"
            let a: int = f();
            console.log("mid");
            let b: int = g();
            function f(): int { console.log("f-ran"); return 1; }
            function g(): int { console.log("g-ran"); return 2; }
            export function test(): int { return a + b; }
            """,
            """
            import * as console from "std/console"
            function f(): null { g(); }
            f();
            function g(): null { console.log("g-ran"); }
            export function test(): int { return 1; }
            """,
            """
            import * as console from "std/console"
            function f(): null { h(); }
            function h(): null { g(); }
            f();
            function g(): null { console.log("g-ran"); }
            export function test(): int { return 1; }
            """,
            """
            let a: int = f();
            function f(): int { return 1; }
            export function test(): int { return a; }
            """,
            """
            import * as console from "std/console"
            test2();
            export function test2(): null { console.log("later"); }
            export function test(): int { return 1; }
            """);

        for (String source : rejected) {
            Frontend f = compileFrontend(source, "jvmtest-modfndecl.deal");
            check(f.errors().stream().anyMatch(d -> "E1049".equals(d.code())),
                "module-level call shape rejected with E1049: " + f.errors());
        }

        // The positive function-declaration hoisting that the module-level
        // shapes exercised survives: forward calls from a function body to
        // a later-declared module function run normally.
        ExecResult direct = compileAndRunJvm("""
            import * as console from "std/console"
            function f(): int { console.log("f-ran"); return 1; }
            export function test(): int { return f(); }
            """, "modfnorderok");
        check(direct.exitCode() == 0, "post-declaration call exits 0");
        check(direct.output().contains("f-ran"),
            "in-function call ran: " + direct.output());

        ExecResult transitive = compileAndRunJvm("""
            function f(): int { return g(); }
            function g(): int { return 7; }
            export function test(): int { return f(); }
            """, "modfntransok");
        check(transitive.exitCode() == 0, "transitive call exits 0");
        check(transitive.output().contains("7"),
            "transitive call computes 7: " + transitive.output());
    
    }

    /** The DEAL_ERROR_CODE contract must hold for module-level errors
     * whether or not the module has a zero-arity export: with one, the
     * first auto-invocation triggers class initialization and the error
     * arrives as an ExceptionInInitializerError (a LinkageError, not a
     * RuntimeException) that the runner unwraps. */
    private static void testRunnerModuleErrorCodeWithExport() throws Exception {
        // v1.2 grammar gate: module-level error shapes (module-level
        // lets removed) are frontend parse errors (E1049) — there is no
        // module-load-time executable surface left to raise. The runtime
        // error codes themselves are pinned by the in-function boundary
        // tests (E8005 int division by zero etc.).
        Frontend withExport = compileFrontend("""
            export function test(): int { return 1; }
            let x: int = 1 / 0;
            """, "jvmtest-moderrwithexport.deal");
        check(withExport.errors().stream()
                .anyMatch(d -> "E1049".equals(d.code())),
            "module-level error shape rejected with E1049: "
                + withExport.errors());

        Frontend noExport = compileFrontend("""
            let x: int = 1 / 0;
            export function takesArg(y: int): int { return y; }
            """, "jvmtest-moderrnoexport.deal");
        check(noExport.errors().stream()
                .anyMatch(d -> "E1049".equals(d.code())),
            "module-level error without export rejected with E1049: "
                + noExport.errors());
    
    }

    private static void testOrchestratorJvmBackend() throws Exception {
        System.out.println("-- Orchestrator: Backend.JVM use site --");

        writeFile("deal.json", """
            {
              "moduleRoots": ["src"],
              "output": "build/jvm",
              "backend": "jvm"
            }""");
        writeFile("src/jvm_main.deal", """
            import * as console from "std/console"
            export function main(): null { return null; }
            function add(a: int, b: int): int { return a + b; }
            export function run(): int {
              console.log("jvm-orchestrator");
              return add(20, 22);
            }
            """);

        Path entryFile = tmpDir.resolve("src/jvm_main.deal").toAbsolutePath();
        Path outputDir = tmpDir.resolve("build/jvm");
        List<Path> roots = List.of(tmpDir.resolve("src").toAbsolutePath());

        DealConfig config = DealConfig.load(tmpDir).config();
        check(config != null && "jvm".equals(config.backend()),
            "deal.json backend jvm parsed");

        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            entryFile, outputDir, false, false, false, Backend.JVM, config, roots,
            Path.of(".").toAbsolutePath().normalize());

        boolean success = orchestrator.compile();
        check(success, "JVM orchestrator compile succeeds: "
            + orchestrator.diagnostics());
        if (!success) return;

        Path artifact = outputDir.resolve("Jvm_main.java");
        check(Files.exists(artifact), "orchestrator wrote Jvm_main.java");
        check(!Files.exists(outputDir.resolve("jvm_main.lua")),
            "no .lua artifact in JVM mode");
        check(!Files.exists(outputDir.resolve("deal/runtime.lua")),
            "no Lua runtime copied in JVM mode");

        if (Files.exists(artifact)) {
            String java = Files.readString(artifact);
            check(java.contains("public final class Jvm_main"),
                "artifact declares its class");
            check(java.contains("java.lang.System.out.println(\"jvm-orchestrator\")"),
                "console.log mapped to java.lang.System.out");
            check(java.contains("return intAdd(a, b);"), "int arithmetic emitted");

            // The production artifact must be real: javac compiles it.
            ProcessBuilder javac = new ProcessBuilder("javac", "-encoding", "UTF-8",
                artifact.toString());
            javac.directory(outputDir.toFile());
            javac.redirectErrorStream(true);
            Process p = javac.start();
            String javacOut = new String(p.getInputStream().readAllBytes()).trim();
            int javacExit = p.waitFor();
            check(javacExit == 0, "orchestrator artifact compiles with javac: "
                + javacOut);
            check(Files.exists(outputDir.resolve("Jvm_main.class")),
                "javac produced the class file");
        }
    }

    private static void testOrchestratorDefaultStaysLua() throws Exception {
        System.out.println("-- Orchestrator: LuaJIT remains the default --");

        writeFile("src/default_main.deal", """
            import * as console from "std/console"
            export function main(): null { return null; }
            export function run(): null { console.log("default-lua"); }
            """);

        Path entryFile = tmpDir.resolve("src/default_main.deal").toAbsolutePath();
        Path outputDir = tmpDir.resolve("build/default_lua");
        List<Path> roots = List.of(tmpDir.resolve("src").toAbsolutePath());

        // The pre-ISSUE-0091 constructor (no backend parameter).
        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            entryFile, outputDir, false, (DealConfig) null, roots,
            Path.of(".").toAbsolutePath().normalize());

        boolean success = orchestrator.compile();
        check(success, "default (LuaJIT) compile succeeds");
        check(Files.exists(outputDir.resolve("default_main.lua")),
            "default backend still emits .lua");
        check(!Files.exists(outputDir.resolve("Default_main.java")),
            "no .java artifact under the default backend");
        check(Files.exists(outputDir.resolve("deal/runtime.lua")),
            "Lua runtime still copied under the default backend");
    }

    private static void testOrchestratorJvmRejectsUnsupported() throws Exception {
        System.out.println("-- Orchestrator: JVM backend rejects out-of-scope constructs --");

        // ISSUE-0102: optional class fields (x?: int) are now supported
        // — the rejection pin moves to a still-out-of-scope shape (a
        // function-typed class field).
        writeFile("src/unsupported_main.deal", """
            export function main(): null { return null; }
            export class Point {
              x?: int;
            }
            export class CallbackHolder {
              cb: (x: int) => int;
            }
            export function run(): int { return 1; }
            """);

        Path entryFile = tmpDir.resolve("src/unsupported_main.deal").toAbsolutePath();
        Path outputDir = tmpDir.resolve("build/unsupported");
        List<Path> roots = List.of(tmpDir.resolve("src").toAbsolutePath());

        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            entryFile, outputDir, false, false, false, Backend.JVM,
            (DealConfig) null, roots, Path.of(".").toAbsolutePath().normalize());

        boolean success = orchestrator.compile();
        check(!success, "JVM backend rejects function-typed class fields");
        check(orchestrator.diagnostics().stream()
                .anyMatch(d -> "E6000".equals(d.code())),
            "orchestrator reports E6000: " + orchestrator.diagnostics());
        check(!Files.exists(outputDir.resolve("Unsupported_main.java")),
            "no artifact written when the backend reports errors");

        // The optional-field class alone now compiles through the
        // orchestrator (ISSUE-0102 promotion of optional class fields).
        writeFile("src/optional_main.deal", """
            export function main(): null { return null; }
            export class Point {
              x?: int;
            }
            export function run(): int { return 1; }
            """);
        Path optionalEntry = tmpDir.resolve("src/optional_main.deal")
            .toAbsolutePath();
        Path optionalOut = tmpDir.resolve("build/optional_supported");
        CompilationOrchestrator optionalOrchestrator =
            new CompilationOrchestrator(
                optionalEntry, optionalOut, false, false, false,
                Backend.JVM, (DealConfig) null, roots,
                Path.of(".").toAbsolutePath().normalize());
        boolean optionalSuccess = optionalOrchestrator.compile();
        check(optionalSuccess,
            "optional class fields compile through the orchestrator: "
                + optionalOrchestrator.diagnostics());
        check(Files.exists(optionalOut.resolve("Optional_main.java")),
            "optional-class-field module writes its artifact");
    }

    // =========================================================================
    // ISSUE-0099 async/await slice: blocking-call lowering, await-site
    // completion checks, error propagation, and async function values
    // through the ISSUE-0098 wrapper machinery
    // =========================================================================

    /**
     * ISSUE-0099: async declarations emit plain static methods (the
     * spec-permitted JVM blocking-call lowering), await sites emit the
     * call plus the declared-return-type completion check (int
     * completions route through checkInt, null-returning awaits hoist
     * into pre-statements, discard positions evaluate the call), errors
     * raised by awaited operations propagate at the await site, and
     * async function values reuse the ISSUE-0098 wrapper machinery
     * unchanged — the per-signature wrapper shape class, the
     * per-declaration wrapper instance field assigned at the
     * declaration point, indirect awaited calls dispatching through
     * {@code invoke}, and the arity-extension adapter. Async function
     * expressions and non-representable function-type signatures stay
     * E6000, and the pre-rebase module-level function-value read shapes
     * are the v1.2 grammar gate E1049 — never an artifact javac
     * rejects after the CLI reported success.
     */
    private static void testAsyncSlice() throws Exception {
        System.out.println("-- Async/await slice (javac + java) --");

        // Blocking-call lowering (spec-v1.2's permitted JVM form) plus
        // the await-site completion check: an async function emits a
        // plain static method with its declared return type; an int
        // await emits the call wrapped in checkInt. Async function
        // values reuse the ISSUE-0098 wrapper machinery — the
        // per-signature wrapper shape class and the per-declaration
        // wrapper instance field cover async markers, and awaited
        // indirect calls dispatch through invoke.
        String emission = """
            async function g(): int { return 42; }
            async function noop(): null { return; }
            async function value(): int { return 6; }
            async function apply(cb: async (x: int) => int, v: int): int { return await cb(v); }
            export async function test(): int {
              let a: int = await g();
              await noop();
              let f: async () => int = value;
              let b: int = await f();
              return a + b;
            }
            """;
        Frontend fe = compileFrontend(emission, "jvmtest-async.deal");
        check(fe.errors().isEmpty(), "async frontend clean: " + fe.errors());
        if (!fe.errors().isEmpty()) return;
        JvmBackend.JvmCodegenResult res = JvmBackend.generate(
            fe.program(), fe.checkResult(), "jvmtest-async.deal", "main");
        check(!res.hasErrors(), "async codegen clean: " + res.diagnostics());
        if (res.hasErrors()) return;
        String java = res.source();
        check(java.contains("static long g()"),
            "async function g → plain static method");
        check(java.contains("static void noop()"),
            "null-returning async function → void method");
        check(java.contains("checkInt(g())"),
            "await of an int completion wraps the call in checkInt");
        check(java.contains("static abstract class Fn0_R_I"),
            "async () => int uses the ISSUE-0098 wrapper shape class");
        check(java.contains("static abstract class Fn1_I_R_I"),
            "async (x: int) => int uses the ISSUE-0098 wrapper shape class");
        check(java.contains("static final Fn0_R_I value$fn = new Fn0_R_I()"),
            "per-declaration wrapper instance field");
        check(java.contains("Fn0_R_I f = value$fn;"),
            "function-typed local initializes from the wrapper field");
        check(java.contains("checkInt(f.invoke())"),
            "awaited indirect call dispatches through invoke");
        check(java.contains("checkInt(cb.invoke(v))"),
            "await through a callback parameter dispatches through invoke");
        check(java.contains("noop();"),
            "null-returning await hoists the call into a pre-statement");
        check(!java.contains("checkInt(noop()"),
            "no int check on a null completion");

        // Value/discard positions, nested awaits, and error propagation
        // execute through the real javac + java pipeline.
        ExecResult values = compileAndRunJvm("""
            async function inner(): int { return 7; }
            async function outer(x: int): int { return x + 1; }
            async function tick(): int { return 1; }
            export async function test(): int {
              await tick();
              return await outer(await inner());
            }
            """, "asyncvalues");
        check(values.exitCode() == 0, "value/discard/nested awaits exit 0");
        check(values.output().contains("8"),
            "await outer(await inner()) computes 8: " + values.output());

        ExecResult error = compileAndRunJvm("""
            async function innermost(): int { return 1 / 0; }
            async function mid(): int { return await innermost(); }
            export async function test(): int { return await mid(); }
            """, "asyncerror");
        check(error.exitCode() == 1,
            "awaited E8005 propagates → exit 1: " + error.output());
        check(error.output().contains("DEAL_ERROR_CODE: E8005"),
            "DEAL_ERROR_CODE: E8005 surfaces at the outermost await: "
                + error.output());

        ExecResult fnValue = compileAndRunJvm("""
            async function plus1(x: int): int { return x + 1; }
            async function apply(cb: async (x: int) => int, v: int): int { return await cb(v); }
            export async function test(): int { return await apply(plus1, 6); }
            """, "asyncfnvalue");
        check(fnValue.exitCode() == 0, "function-value callback exits 0");
        check(fnValue.output().contains("7"),
            "awaited indirect call through the callback computes 7: "
                + fnValue.output());

        // Arity extension inherits the ISSUE-0098 adapter machinery: a
        // narrower async signature at a wider async target wraps in a
        // delegating adapter whose invoke drops the extra parameters.
        ExecResult arity = compileAndRunJvm("""
            async function one(x: int): int { return x; }
            export async function test(): int {
              let f: async (x: int, y: int) => int = one;
              return await f(1, 2);
            }
            """, "asyncarity");
        check(arity.exitCode() == 0, "async arity extension exits 0");
        check(arity.output().contains("1"),
            "await f(1, 2) drops the extra argument and computes 1: "
                + arity.output());

        // Reassigned-binding guard descends into awaited calls: an
        // assignment hidden in an awaited call's argument list
        // (`apply(g = two, 1)`) reassigns the binding exactly like a
        // bare statement assignment, so the adapter over `g` must not
        // silently snapshot it. LuaJIT's adapter reads the binding live
        // on every invoke — pinned by the LuaJIT-only reference fixture
        // jvm-async-lua-ref-reassigned-adapter (the retargeted adapter
        // computes 11) — and the JVM slice cannot emit a live capture
        // of a reassigned binding: E6000, never a silent divergence.
        Frontend hidden = compileFrontend("""
            async function one(x: int): int { return x; }
            async function two(x: int): int { return x + 10; }
            async function apply(cb: async (x: int) => int, v: int): int { return await cb(v); }
            export async function test(): int {
              let g: async (x: int) => int = one;
              let h: async (x: int, y: int) => int = g;
              await apply(g = two, 1);
              return await h(1, 2);
            }
            """, "jvmtest-async-reassigned.deal");
        check(hidden.errors().isEmpty(),
            "frontend accepts the awaited-argument-hidden reassignment: "
                + hidden.errors());
        if (hidden.errors().isEmpty()) {
            JvmBackend.JvmCodegenResult hiddenRes = JvmBackend.generate(
                hidden.program(), hidden.checkResult(),
                "jvmtest-async-reassigned.deal", "main");
            check(hiddenRes.hasErrors(),
                "backend rejects the awaited-argument-hidden reassignment");
            check(hiddenRes.diagnostics().stream()
                    .anyMatch(d -> "E6000".equals(d.code())),
                "E6000 for the awaited-argument-hidden reassignment: "
                    + hiddenRes.diagnostics());
        }

        // Deferred shapes stay E6000: async function expressions and
        // non-representable async signatures (array parameters).
        Frontend expr = compileFrontend("""
            export async function test(): int {
              let f: async () => int = async function(): int { return 42; };
              return await f();
            }
            """, "jvmtest-async-expr.deal");
        check(expr.errors().isEmpty(),
            "frontend accepts the async function expression: "
                + expr.errors());
        if (!expr.errors().isEmpty()) return;
        JvmBackend.JvmCodegenResult exprRes = JvmBackend.generate(
            expr.program(), expr.checkResult(), "jvmtest-async-expr.deal", "main");
        check(exprRes.hasErrors() && exprRes.diagnostics().stream()
                .anyMatch(d -> "E6000".equals(d.code())),
            "async function expressions stay E6000 (deferred): "
                + exprRes.diagnostics());

        Frontend shape = compileFrontend("""
            async function total(xs: int[]): int { return xs[0]; }
            export async function test(): int {
              let f: async (xs: int[]) => int = total;
              return await f([1]);
            }
            """, "jvmtest-async-shape.deal");
        check(shape.errors().isEmpty(),
            "frontend accepts the non-representable async signature: "
                + shape.errors());
        if (!shape.errors().isEmpty()) return;
        JvmBackend.JvmCodegenResult shapeRes = JvmBackend.generate(
            shape.program(), shape.checkResult(), "jvmtest-async-shape.deal", "main");
        check(shapeRes.hasErrors() && shapeRes.diagnostics().stream()
                .anyMatch(d -> "E6000".equals(d.code())),
            "async signatures with array parameters stay E6000 "
                + "(deferred to ISSUE-0110): " + shapeRes.diagnostics());

        // v1.2 grammar gate: the pre-rebase module-level function-value
        // read shapes (`let g: async () => int = value;` at top level,
        // and a module-level call transitively reading a later-declared
        // function's value) are E1049 at the frontend — the v1.2 module
        // top level holds only declarations, so the load-time guards
        // stay defensive and pinned exactly like the sibling ISSUE-0098
        // load-time guards.
        Frontend late = compileFrontend("""
            let g: async () => int = value;
            async function value(): int { return 1; }
            export async function test(): int { return await g(); }
            """, "jvmtest-async-late.deal");
        check(late.errors().stream()
                .anyMatch(d -> "E1049".equals(d.code())),
            "module-level function-value read rejected with E1049: "
                + late.errors());
    }
    // @jsonable slice (JSON serialization)
    // =========================================================================

    /**
     * The @jsonable slice: the generated {@code C$fromJson}/
     * {@code C$toJson} helpers, the per-class {@code $jsonFields}
     * descriptor table, fromJson/toJson validation semantics (null on
     * extra keys / malformed JSON / type mismatches / unpaired UTF-16
     * surrogate code units in decoded JSON strings, and invalid JSON
     * number spellings (leading zeros, a decimal point without a
     * fraction digit, a sign/digit-starved exponent, a missing integer
     * part — parse failures returning the DEAL null, RFC 8259 /
     * std/json.lua parity) — never a throw; E8001 for a NaN number
     * field in toJson; required no-default fields applying the
     * reference defaults table — the primitive zeroes, a fresh empty
     * table/array per call, never Java null, and a required
     * class-typed field's absent key as a fromJson validation
     * failure), and the slice boundaries
     * — optional/table-typed @jsonable fields, non-jsonable
     * array/class fields, and module-level calls of the generated
     * helpers stay E6000. Every runtime case compiles the emitted
     * artifact with javac and executes it with java; the same surface
     * runs through the BackendConformanceTest adapter in
     * {@code test/conformance/fixtures/jvm-jsonable-slice.json}.
     */
    private static void testJsonableSlice() throws Exception {
        System.out.println("-- @jsonable slice: generated helpers, descriptors, validation, boundaries --");

        ExecResult roundtrip = compileAndRunJvm("""
            // @jsonable
            export class User {
              name: string = "";
              age: int = 0;
            }
            export function test(): string {
              let u: User = { name: "Ada", age: 30 };
              let json: string = User$toJson(u);
              let u2: User | null = User$fromJson(json);
              if (u2 !== null) {
                if (u2.name !== "Ada" || u2.age !== 30) { return "bad"; }
                return "ok";
              }
              return "null";
            }
            """, "jsonable-roundtrip");
        check(roundtrip.exitCode() == 0, "jsonable roundtrip exits 0: "
            + roundtrip.output());
        check(roundtrip.output().contains("ok"), "jsonable roundtrip ok: "
            + roundtrip.output());

        // Extra keys, malformed JSON, type mismatches, and a non-object
        // top-level value all return the DEAL null.
        ExecResult failures = compileAndRunJvm("""
            // @jsonable
            export class User {
              name: string = "";
              age: int = 0;
            }
            export function test(): int {
              let a: User | null = User$fromJson("{\\"name\\":\\"Ada\\",\\"extra\\":1}");
              if (a === null) {
                let b: User | null = User$fromJson("{oops");
                if (b === null) {
                  let c: User | null = User$fromJson("{\\"name\\":\\"Ada\\",\\"age\\":2.5}");
                  if (c === null) {
                    let d: User | null = User$fromJson("[1,2]");
                    if (d === null) { return 1; }
                    return 0;
                  }
                  return 0;
                }
                return 0;
              }
              return 0;
            }
            """, "jsonable-failures");
        check(failures.exitCode() == 0, "jsonable failures exit 0: "
            + failures.output());
        check(failures.output().contains("1"), "jsonable failures return 1: "
            + failures.output());

        // A NaN number field raises E8001 in toJson (std/json.lua parity).
        ExecResult nan = compileAndRunJvm("""
            // @jsonable
            export class Metric {
              value: number = 0.0;
            }
            export function test(): string {
              let m: Metric = { value: 0.0 / 0.0 };
              return Metric$toJson(m);
            }
            """, "jsonable-nan");
        check(nan.output().contains("DEAL_ERROR_CODE: E8001"),
            "toJson of a NaN number field raises E8001: " + nan.output());

        // Emission pins: the artifact carries the descriptor table, the
        // translated helper names, and the JSON runtime — and a module
        // without @jsonable classes emits none of it.
        String source = """
            // @jsonable
            export class User {
              name: string = "";
              age: int = 0;
            }
            export function test(): int { return 1; }
            """;
        Frontend f = compileFrontend(source, "jvmtest-jsonable-pin.deal");
        check(f.errors().isEmpty(), "jsonable pin frontend clean: " + f.errors());
        if (f.errors().isEmpty()) {
            JvmBackend.JvmCodegenResult res = JvmBackend.generate(
                f.program(), f.checkResult(), "jvmtest-jsonable-pin.deal", "Main");
            check(!res.hasErrors(), "jsonable pin codegen clean: " + res.diagnostics());
            if (!res.hasErrors()) {
                String java = res.source();
                check(java.contains(
                        "new java.lang.String[]{\"name\", \"string\", \"false\", \"false\"}"),
                    "the field descriptor rows carry {name, jtype, optional, nullable}");
                check(java.contains("public static $C_User User$dfromJson(java.lang.String s)"),
                    "the generated fromJson export uses the javaName translation");
                check(java.contains("return __jsonStringify($C_User.$toJsonValue(v));"),
                    "the generated toJson export stringifies the field serializer");
                check(java.contains("static java.lang.Object __jsonParse("),
                    "the JSON parser is emitted for a module with @jsonable classes");
                check(java.contains("static final java.lang.String[][] $jsonFields"),
                    "the per-class $jsonFields descriptor is emitted");
                check(java.contains(
                        "catch (java.lang.StackOverflowError e) { return null; }"),
                    "the JSON parser converts deep-nesting stack exhaustion "
                    + "to the DEAL null");
                check(java.contains(
                        "try { return $C_User.$fromJsonValue(__jsonParse(s)); }"),
                    "the public fromJson export wraps the conversion so the "
                    + "exhaustion shapes never escape it");
                check(java.contains("static java.lang.Object __jsonTableValue"
                        + "(java.lang.Object raw, int depth) {"),
                    "the table-value conversion carries the bounded depth "
                    + "parameter");
                check(java.contains("if (depth > 512) throw new "
                        + "java.lang.RuntimeException(\"JSON nesting too "
                        + "deep\");"),
                    "the table-value conversion carries the bounded depth "
                    + "guard");
                check(java.contains("if (__hasUnpairedSurrogate(out)) throw "
                        + "new java.lang.RuntimeException(\"unpaired "
                        + "UTF-16 surrogate code unit in JSON string\");"),
                    "the JSON string parser rejects unpaired UTF-16 "
                    + "surrogate code units");
                check(java.contains("the strict RFC 8259 number"),
                    "the JSON number parser validates the strict RFC "
                    + "8259 number grammar");
                check(java.contains(
                        "if (i < s.length() && s.charAt(i) == '0') { i++; }"),
                    "the JSON number parser rejects leading zeros");
                check(java.contains("if (i >= s.length() || s.charAt(i) < '0' "
                        + "|| s.charAt(i) > '9') throw new "
                        + "java.lang.RuntimeException(\"bad JSON "
                        + "number\");"),
                    "the JSON number parser requires digits after the "
                    + "decimal point and the exponent");
            }
        }
        Frontend plain = compileFrontend("""
            export function test(): int { return 1; }
            """, "jvmtest-jsonable-plain.deal");
        check(plain.errors().isEmpty(), "plain module frontend clean");
        if (plain.errors().isEmpty()) {
            JvmBackend.JvmCodegenResult res = JvmBackend.generate(
                plain.program(), plain.checkResult(), "jvmtest-jsonable-plain.deal", "Main");
            check(!res.source().contains("__jsonParse"),
                "a module without @jsonable classes emits no JSON runtime support");
        }

        // Runtime coverage for the optional and table field forms:
        // the three optional-nullable states (absent / present null /
        // present value with has() presence), the optional-with-default
        // form, table fields with nested objects, and nested JSON arrays
        // as array-mode tables.
        ExecResult optional = compileAndRunJvm("""
            // @jsonable
            export class User {
              name: string = "";
              nick?: string | null;
            }
            export function test(): string {
              let m: User = { name: "A" };
              let mj: string = User$toJson(m);
              let m2: User | null = User$fromJson(mj);
              if (m2 !== null) {
                if (has(m2.nick)) { return "has-missing"; }
              } else { return "m2"; }
              let n: User = { name: "B", nick: null };
              let nj: string = User$toJson(n);
              if (nj !== "{\\\"name\\\":\\\"B\\\",\\\"nick\\\":null}") { return "nj"; }
              let n2: User | null = User$fromJson(nj);
              if (n2 !== null) {
                if (!has(n2.nick) || n2.nick !== null) { return "n2"; }
              } else { return "n2null"; }
              let v: User = { name: "C", nick: "cee" };
              let v2: User | null = User$fromJson(User$toJson(v));
              if (v2 !== null) {
                if (!has(v2.nick)) { return "no-has"; }
                let nick: string | null = v2.nick;
                if (nick !== null) {
                  if (nick !== "cee") { return "bad"; }
                } else { return "nick-null"; }
              } else { return "v2null"; }
              return "ok";
            }
            """, "jsonable-optional");
        check(optional.exitCode() == 0 && optional.output().contains("ok"),
            "optional three-state roundtrip: " + optional.output());

        ExecResult table = compileAndRunJvm("""
            // @jsonable
            export class Wrap {
              data: table = {};
            }
            export function test(): string {
              let w: Wrap = { data: { nested: { inner: "x" } } };
              let json: string = Wrap$toJson(w);
              let w2: Wrap | null = Wrap$fromJson(json);
              if (w2 !== null) {
                let nested: table = w2.data.nested;
                let inner: string = nested.inner;
                if (inner !== "x") { return "inner"; }
                return "ok";
              }
              return "null";
            }
            """, "jsonable-table");
        check(table.exitCode() == 0 && table.output().contains("ok"),
            "table field roundtrip: " + table.output());

        ExecResult tableArrays = compileAndRunJvm("""
            // @jsonable
            export class Wrap {
              data: table = {};
            }
            export function test(): string {
              let w: Wrap | null = Wrap$fromJson("{\\\"data\\\":{\\\"values\\\":[[1,2],[3,4]]}}");
              if (w !== null) {
                let values: table = w.data.values;
                let json: string = Wrap$toJson(w);
                if (json !== "{\\\"data\\\":{\\\"values\\\":[[1,2],[3,4]]}}") { return "json"; }
                return "ok";
              }
              return "null";
            }
            """, "jsonable-table-arrays");
        check(tableArrays.exitCode() == 0
                && tableArrays.output().contains("ok"),
            "table field nested JSON arrays roundtrip: "
                + tableArrays.output());

        // Hostile deep-nesting pins: the fromJson export must return the
        // DEAL null on deeply nested JSON instead of crashing the
        // program. A ~20 KB payload of 20000 nested '[' ... ']' exhausts
        // the emitted parser's stack — the emitted parser converts its
        // StackOverflowError to the DEAL null exactly like LuaJIT's
        // pcall(__json_parse, s). Deep-but-parsable nesting inside a
        // table field (600 nested objects past the 512-level guard)
        // exercises the bounded depth guard of the table-value
        // conversion. Both shapes pin exit 0 and the DEAL null.
        ExecResult deepArrays = compileAndRunJvm("""
            // @jsonable
            export class Wrap {
              data: table = {};
            }
            export function test(): string {
              let s: string = "{\\\"data\\\":";
              let i: int = 0;
              while (i < 20000) { s = s + "["; i = i + 1; }
              i = 0;
              while (i < 20000) { s = s + "]"; i = i + 1; }
              s = s + "}";
              let w: Wrap | null = Wrap$fromJson(s);
              if (w !== null) { return "not-null"; }
              return "ok";
            }
            """, "jsonable-deep-arrays");
        check(deepArrays.exitCode() == 0 && deepArrays.output().contains("ok"),
            "deeply nested JSON arrays return the DEAL null instead of "
            + "crashing the program: " + deepArrays.output());

        ExecResult deepTable = compileAndRunJvm("""
            // @jsonable
            export class Wrap {
              data: table = {};
            }
            export function test(): string {
              let s: string = "{\\\"data\\\":";
              let i: int = 0;
              while (i < 600) { s = s + "{\\\"k\\\":"; i = i + 1; }
              s = s + "{\\\"k\\\":[1]}";
              i = 0;
              while (i < 600) { s = s + "}"; i = i + 1; }
              s = s + "}";
              let w: Wrap | null = Wrap$fromJson(s);
              if (w !== null) { return "not-null"; }
              return "ok";
            }
            """, "jsonable-deep-table-guard");
        check(deepTable.exitCode() == 0 && deepTable.output().contains("ok"),
            "the table-value depth guard converts deep nesting to the "
            + "DEAL null: " + deepTable.output());

        // Unpaired UTF-16 surrogate pin (reviewed defect): the JSON
        // parser's backslash-u escape decoding must reject lone
        // surrogates — a lone high (0xD800) or low (0xDC00) surrogate
        // in a string field or a table-field string leaf is a parse
        // failure returning the DEAL null exactly like LuaJIT's
        // std/json.lua decoder error inside pcall(__json_parse, s) —
        // while a valid high+low pair decodes and reads back. The
        // escape text is built with Java concatenation after the text
        // block via placeholder replacement: a raw backslash-u-D800 in
        // Java source would be a Java unicode escape (a lone surrogate
        // in the test source itself).
        ExecResult surrogates = compileAndRunJvm("""
            // @jsonable
            export class User {
              name: string = "";
            }
            // @jsonable
            export class Wrap {
              data: table = {};
            }
            export function test(): string {
              let hi: User | null = User$fromJson("{\\"name\\":\\"<LONE_HIGH>\\"}");
              if (hi !== null) { return "hi-accepted"; }
              let lo: User | null = User$fromJson("{\\"name\\":\\"<LONE_LOW>\\"}");
              if (lo !== null) { return "lo-accepted"; }
              let pair: User | null = User$fromJson("{\\"name\\":\\"<VALID_PAIR>\\"}");
              if (pair !== null) {
                if (pair.name !== "<PAIR_EMOJI>") { return "pair-bad"; }
              } else { return "pair-null"; }
              let leaf: Wrap | null = Wrap$fromJson("{\\"data\\":{\\"leaf\\":\\"<LONE_HIGH>\\"}}");
              if (leaf !== null) { return "leaf-accepted"; }
              return "ok";
            }
            """.replace("<LONE_HIGH>", "\\" + "uD800")
                .replace("<LONE_LOW>", "\\" + "uDC00")
                .replace("<VALID_PAIR>", "\\" + "uD83D" + "\\" + "uDE00")
                .replace("<PAIR_EMOJI>", "\uD83D\uDE00"),
            "jsonable-unpaired-surrogates");
        check(surrogates.exitCode() == 0
                && surrogates.output().contains("ok"),
            "lone UTF-16 surrogates in JSON strings return the DEAL null "
            + "while a valid surrogate pair decodes: " + surrogates.output());

        // Invalid JSON number spellings (reviewed defect): the emitted
        // parser must validate the strict RFC 8259 number grammar
        // before Double.parseDouble. A leading zero (01, -01, 00), a
        // decimal point without a fraction digit (1.), an exponent
        // without fraction digits (1.e2, 5.e+2), an exponent or sign
        // without digits (1e, 1e+), and a missing integer part (-.5)
        // are parse failures returning the DEAL null for a typed field
        // and for a table-field leaf — exactly like LuaJIT's
        // std/json.lua parse_error inside pcall(__json_parse, s) —
        // while valid spellings (0, 42, 2.5, -0.5, 1e2, 1.5e-2, and a
        // plain table-leaf number) still parse and read back, never an
        // over-rejection.
        ExecResult numbers = compileAndRunJvm("""
            // @jsonable
            export class P {
              age: int = 0;
              score: number = 0.0;
            }
            // @jsonable
            export class Wrap {
              data: table = {};
            }
            export function test(): string {
              let a: P | null = P$fromJson("{\\"age\\":01}");
              if (a !== null) { return "01"; }
              let b: P | null = P$fromJson("{\\"age\\":-01}");
              if (b !== null) { return "-01"; }
              let c: P | null = P$fromJson("{\\"score\\":00}");
              if (c !== null) { return "00"; }
              let d: P | null = P$fromJson("{\\"score\\":1.}");
              if (d !== null) { return "1."; }
              let e: P | null = P$fromJson("{\\"score\\":1.e2}");
              if (e !== null) { return "1.e2"; }
              let f: P | null = P$fromJson("{\\"score\\":5.e+2}");
              if (f !== null) { return "5.e+2"; }
              let g: P | null = P$fromJson("{\\"score\\":-.5}");
              if (g !== null) { return "-.5"; }
              let h: P | null = P$fromJson("{\\"score\\":1e}");
              if (h !== null) { return "1e"; }
              let i: P | null = P$fromJson("{\\"score\\":1e+}");
              if (i !== null) { return "1e+"; }
              let t: Wrap | null = Wrap$fromJson("{\\"data\\":{\\"n\\":01}}");
              if (t !== null) { return "table-01"; }
              let t2: Wrap | null = Wrap$fromJson("{\\"data\\":{\\"n\\":-01}}");
              if (t2 !== null) { return "table--01"; }
              let t3: Wrap | null = Wrap$fromJson("{\\"data\\":{\\"n\\":1.}}");
              if (t3 !== null) { return "table-1."; }
              let t4: Wrap | null = Wrap$fromJson("{\\"data\\":{\\"n\\":1.e2}}");
              if (t4 !== null) { return "table-1.e2"; }
              let t5: Wrap | null = Wrap$fromJson("{\\"data\\":{\\"n\\":-.5}}");
              if (t5 !== null) { return "table--.5"; }
              let ok: P | null = P$fromJson("{\\"age\\":42,\\"score\\":2.5}");
              if (ok !== null) {
                if (ok.age !== 42) { return "age"; }
                if (ok.score !== 2.5) { return "score"; }
              } else {
                return "ok-null";
              }
              let forms: P | null = P$fromJson("{\\"age\\":0,\\"score\\":-0.5}");
              if (forms !== null) {
                if (forms.age !== 0) { return "zero"; }
                if (forms.score !== -0.5) { return "neg"; }
              } else {
                return "forms-null";
              }
              let expo: P | null = P$fromJson("{\\"age\\":0,\\"score\\":1.5e-2}");
              if (expo !== null) {
                if (expo.score !== 0.015) { return "expo"; }
              } else {
                return "expo-null";
              }
              let e2: P | null = P$fromJson("{\\"age\\":0,\\"score\\":1e2}");
              if (e2 !== null) {
                if (e2.score !== 100.0) { return "e2"; }
              } else {
                return "e2-null";
              }
              let tv: Wrap | null = Wrap$fromJson("{\\"data\\":{\\"n\\":10}}");
              if (tv === null) { return "table-10-null"; }
              return "ok";
            }
            """, "jsonable-number-grammar");
        check(numbers.exitCode() == 0 && numbers.output().contains("ok"),
            "invalid JSON number spellings return the DEAL null while "
            + "valid spellings parse: " + numbers.output());

        // Required no-default fields (the reviewed defect): the
        // reference defaults table (LuaBackend.defaultValueForTypeNode)
        // applies the per-type zeroes — 0 / 0.0 / false / "" — and a
        // FRESH empty table/array per call. The pre-fix emission stored
        // Java null into the non-nullable $DealRt.Table/__IntArray/$C
        // slots, so
        // fromJson("{}") produced toJson {"data":null} and the first
        // table read crashed with a raw NullPointerException
        // (LuaJIT: {"data":{}}, {} for arrays, and a DEAL-null read).
        ExecResult nodefault = compileAndRunJvm("""
            // @jsonable
            export class Child {
              x: int = 1;
            }
            // @jsonable
            export class Raw {
              i: int;
              n: number;
              b: boolean;
              s: string;
              data: table;
              xs: int[];
            }
            // @jsonable
            export class Holder {
              c: Child;
              i: int;
            }
            export function test(): string {
              let w: Raw | null = Raw$fromJson("{}");
              if (w !== null) {
                if (w.i !== 0) { return "i"; }
                if (w.n !== 0.0) { return "n"; }
                if (w.b !== false) { return "b"; }
                if (w.s !== "") { return "s"; }
                let j: string = Raw$toJson(w);
                if (j !== "{\\"i\\":0,\\"n\\":0.0,\\"b\\":false,\\"s\\":\\"\\",\\"data\\":{},\\"xs\\":[]}") { return "j:" + j; }
                let t: table = w.data;
                let miss: string | null = t.missing;
                if (miss !== null) { return "miss"; }
                if (w.xs.length !== 0) { return "len"; }
                let n0: int | null = w.xs[0];
                if (n0 !== null) { return "n0"; }
                w.xs[w.xs.length] = 7;
                let w2: Raw | null = Raw$fromJson("{}");
                if (w2 !== null) {
                  if (w2.xs.length !== 0) { return "shared"; }
                  let j2: string = Raw$toJson(w2);
                  if (j2 !== j) { return "j2"; }
                } else {
                  return "w2-null";
                }
                // A required CLASS-typed field with no declared default:
                // the reference defaults-table {} placeholder (a plain
                // Lua table, never a class instance) has no Java value
                // at the typed slot — an absent key is a fromJson
                // validation failure (the DEAL null), never Java null
                // crossing the non-nullable class boundary (the pre-fix
                // JVM silently read null at a typed read where LuaJIT
                // raises E8001).
                let h: Holder | null = Holder$fromJson("{}");
                if (h !== null) { return "h-not-null"; }
                let h2: Holder | null = Holder$fromJson("{\\"c\\":{\\"x\\":9}}");
                if (h2 !== null) {
                  if (h2.c.x !== 9) { return "x"; }
                  if (h2.i !== 0) { return "i"; }
                  let hj: string = Holder$toJson(h2);
                  if (hj !== "{\\"c\\":{\\"x\\":9},\\"i\\":0}") { return "hj:" + hj; }
                  return "ok";
                }
                return "h2-null";
              }
              return "w-null";
            }
            """, "jsonable-required-nodefault");
        check(nodefault.exitCode() == 0
                && nodefault.output().contains("ok"),
            "required no-default fromJson fields apply the reference "
            + "defaults (fresh {}/[] and the primitive zeroes), a "
            + "missing-key table/array read yields the DEAL null never "
            + "a raw NPE, and a required no-default class field's absent "
            + "key is a fromJson validation failure: " + nodefault.output());

        // Emission pins for the same shapes: the fresh table/array
        // defaults and the class-field absent-key validation-failure
        // guard.
        Frontend nodefaultPin = compileFrontend("""
            // @jsonable
            export class Child {
              x: int = 1;
            }
            // @jsonable
            export class Wrap {
              data: table;
              xs: int[];
              c: Child;
            }
            export function test(): int { return 1; }
            """, "jvmtest-jsonable-nodefault-pin.deal");
        check(nodefaultPin.errors().isEmpty(),
            "no-default pin frontend clean: " + nodefaultPin.errors());
        if (nodefaultPin.errors().isEmpty()) {
            JvmBackend.JvmCodegenResult res = JvmBackend.generate(
                nodefaultPin.program(), nodefaultPin.checkResult(),
                "jvmtest-jsonable-nodefault-pin.deal", "Main");
            check(!res.hasErrors(), "no-default pin codegen clean: "
                + res.diagnostics());
            if (!res.hasErrors()) {
                String java = res.source();
                check(java.contains("$DealRt.Table f0 = new $DealRt.Table();"),
                    "a required no-default table field defaults to a "
                    + "fresh empty $DealRt.Table (never Java null)");
                check(java.contains(
                        "__IntArray f1 = new __IntArray(new long[0]);"),
                    "a required no-default array field defaults to a "
                    + "fresh empty wrapper (never Java null)");
                check(java.contains(
                        "if (!m.containsKey(\"c\")) return null;"),
                    "a required no-default class field's absent key is "
                    + "a fromJson validation failure (the placeholder "
                    + "never crosses the typed boundary)");
            }
        }

        // Slice boundaries: non-jsonable array/class fields and the
        // @jsonable optional-table form are E6000 — never silently
        // miscompiled. The optional-table gate pins the reviewed
        // defect: the pre-fix emission stored `data?: table` as the
        // table class and
        // later passed the Missing sentinel into that slot, leaving an
        // artifact javac rejected ('Object cannot be converted to the
        // table class')
        // after the CLI reported success — the field's read yields
        // `table | null`, a value shape the slice keeps out of its
        // typed positions. A module-level (load-time) call of a
        // generated helper is a v1.2 E1049 module-shape gate: the v1.2
        // module top level holds only declarations, so the v1.1
        // load-time shape (LuaJIT reads the helper's not-yet-assigned
        // chunk local and fails; Java would silently run the hoisted
        // method) is rejected before any backend.
        record Gate(String what, String source) {}
        // ISSUE-0102 lifted the NON-jsonable class surface: array,
        // table, and optional fields on non-@jsonable classes compile
        // and run (boxed slots plus $present flags). The @jsonable
        // slice keeps its own Missing-sentinel storage for optional
        // fields — these forms stay accepted alongside it.
        List<Gate> accepted = List.of(
            new Gate("array field of a non-jsonable class", """
                class Plain { v: int = 0; }
                class Outer { xs: Plain[] = []; }
                export function test(): int { return 1; }
                """),
            new Gate("table field of a non-jsonable class", """
                class Plain { v: int = 0; }
                class Outer { meta: table = {}; }
                export function test(): int { return 1; }
                """),
            new Gate("optional field of a non-jsonable class", """
                class Plain { v?: int; }
                export function test(): int { return 1; }
                """)
        );
        for (Gate g : accepted) {
            Frontend fg = compileFrontend(g.source(), "jvmtest-jsonable-gate.deal");
            if (!fg.errors().isEmpty()) {
                fail("frontend must accept '" + g.what() + "': "
                    + fg.errors());
                continue;
            }
            JvmBackend.JvmCodegenResult res = JvmBackend.generate(
                fg.program(), fg.checkResult(), "jvmtest-jsonable-gate.deal", "Main");
            check(!res.hasErrors(), "backend accepts " + g.what() + ": "
                + res.diagnostics());
        }
        List<Gate> gates = List.of(
            new Gate("optional table field of an @jsonable class", """
                // @jsonable
                export class Wrap {
                  data?: table;
                }
                export function test(): int { return 1; }
                """),
            new Gate("nullable table field of an @jsonable class", """
                // @jsonable
                export class Wrap {
                  data: table | null = null;
                }
                export function test(): int { return 1; }
                """),
            new Gate("optional nullable table field of an @jsonable class", """
                // @jsonable
                export class Wrap {
                  data?: table | null;
                }
                export function test(): int { return 1; }
                """)
        );
        for (Gate g : gates) {
            Frontend fg = compileFrontend(g.source(), "jvmtest-jsonable-gate.deal");
            if (!fg.errors().isEmpty()) {
                fail("frontend must accept '" + g.what() + "' (the backend rejects it): "
                    + fg.errors());
                continue;
            }
            JvmBackend.JvmCodegenResult res = JvmBackend.generate(
                fg.program(), fg.checkResult(), "jvmtest-jsonable-gate.deal", "Main");
            check(res.hasErrors(), "backend rejects " + g.what());
            check(res.diagnostics().stream().anyMatch(d -> "E6000".equals(d.code())),
                "E6000 diagnostic for " + g.what() + ": " + res.diagnostics());
        }

        // The v1.2 module-shape gate for a load-time call of a generated
        // helper: the v1.2 module top level holds only declarations, so
        // the v1.1 load-time shape is E1049 before any backend runs.
        Frontend mod = compileFrontend("""
            // @jsonable
            export class User {
              name: string = "";
            }
            let u: User | null = User$fromJson("{}");
            export function test(): int { return 1; }
            """, "jvmtest-jsonable-module-shape.deal");
        check(mod.errors().stream().anyMatch(d -> "E1049".equals(d.code())),
            "the v1.2 module top level rejects a load-time call of the "
            + "generated helper with E1049: " + mod.errors());
    }

    /**
     * ISSUE-0109: imported-class values, imported-class construction, and
     * cross-module nominal identity now compile and run through the real
     * orchestrator pipeline (module discovery → checking → per-module
     * JvmBackend codegen → javac → java), replacing the pre-ISSUE-0109
     * E6000 deferral. Locality is still decided from the Type.Class
     * MODULE PATH: a same-named local class must never satisfy the guard
     * for a foreign path, and the emitted Java type of an imported class
     * is the DECLARING module's generated nested class ({@code Lib.$C_C}),
     * never a local one.
     */
    private static void testImportedClassValues() throws Exception {
        System.out.println("-- Orchestrator: imported-class values, construction, and nominal identity --");



        // (1) An annotation-less local inferred from an imported module's
        // class-typed export: `let c = lib.getC()` declares c as
        // Lib.$C_C (the pre-ISSUE-0109 backend emitted `$C_C c =
        // Lib.getC();` and javac rejected the artifact after the CLI
        // reported success).
        writeFile("src/lib.deal", """
            class C { v: int = 0; }
            export function getC(): C { return { v: 1 }; }
            """);
        writeFile("src/entry.deal", """
            import * as lib from "./lib"
            export function main(): null { return null; }
            export function run(): int {
              let c = lib.getC();
              return c.v + 1;
            }
            """);

        Path entryFile = tmpDir.resolve("src/entry.deal").toAbsolutePath();
        Path outputDir = tmpDir.resolve("build/imported_class");
        List<Path> roots = List.of(tmpDir.resolve("src").toAbsolutePath());

        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            entryFile, outputDir, false, false, false, Backend.JVM,
            (DealConfig) null, roots, Path.of(".").toAbsolutePath().normalize());

        boolean success = orchestrator.compile();
        check(success, "JVM backend supports imported-class values: "
            + orchestrator.diagnostics());
        check(Files.exists(outputDir.resolve("Entry.java"))
                && Files.exists(outputDir.resolve("Lib.java")),
            "imported-class artifacts written");
        if (success && Files.exists(outputDir.resolve("Entry.java"))) {
            String java = Files.readString(outputDir.resolve("Entry.java"));
            check(java.contains("Lib.$C_C c = Lib.getC();"),
                "the inferred imported-class value declares the imported "
                + "module's generated class type: " + java);
            check(java.contains("return intAdd((c).v, 1L);"),
                "the imported class field read emits a direct access fed "
                + "into int arithmetic: " + java);
            ExecResult exec = runJvmArtifacts(outputDir,
                parseProgram("export function run(): int { return 1; }"),
                "Entry");
            check(exec.exitCode() == 0 && exec.output().contains("2"),
                "the imported-class value reads back c.v + 1 = 2: "
                    + exec.output());
        }

        // (2) A same-named LOCAL class must not satisfy the imported-class
        // guard: lib.C stays Lib.$C_C while the local C stays
        // Entry.$C_C (the pre-fix name-keyed guard emitted `$C_C c =
        // Lib.getC();` — javac: incompatible types Lib.$C_C → Entry.$C_C
        // — after the CLI reported success).
        writeFile("src2/lib.deal", """
            class C { v: int = 0; }
            export function getC(): C { return { v: 9 }; }
            """);
        writeFile("src2/entry.deal", """
            import * as lib from "./lib"
            class C { v: int = 0; }
            export function main(): null { return null; }
            export function run(): int {
              let c = lib.getC();
              let localC: C = { v: 4 };
              return c.v * 10 + localC.v;
            }
            """);

        Path entryFile2 = tmpDir.resolve("src2/entry.deal").toAbsolutePath();
        Path outputDir2 = tmpDir.resolve("build/imported_class_same_name");
        List<Path> roots2 = List.of(tmpDir.resolve("src2").toAbsolutePath());

        CompilationOrchestrator orchestrator2 = new CompilationOrchestrator(
            entryFile2, outputDir2, false, false, false, Backend.JVM,
            (DealConfig) null, roots2, Path.of(".").toAbsolutePath().normalize());

        boolean success2 = orchestrator2.compile();
        check(success2, "a same-named local class stays distinct from the "
            + "imported class: " + orchestrator2.diagnostics());
        if (success2 && Files.exists(outputDir2.resolve("Entry.java"))) {
            String java = Files.readString(outputDir2.resolve("Entry.java"));
            check(java.contains("Lib.$C_C c = Lib.getC();"),
                "the imported type references Lib.$C_C even with a local C: "
                    + java);
            check(java.contains("new $C_C(4L)"),
                "the local construction keeps the local $C_C: " + java);
            ExecResult exec = runJvmArtifacts(outputDir2,
                parseProgram("export function run(): int { return 1; }"),
                "Entry");
            check(exec.exitCode() == 0 && exec.output().contains("94"),
                "imported 9 and local 4 stay distinct: 9 * 10 + 4 = 94: "
                    + exec.output());
        }

        // (3) Imported-class construction: a lib.C-context object literal
        // (`lib.takeC({ v: 9 })`) emits `new Lib.$C_C(9L)` in the module
        // that also declares its own C (the pre-fix silent
        // nominal-identity corruption tagged the literal with the LOCAL
        // module identity).
        writeFile("src3/lib.deal", """
            class C { v: int = 0; }
            export function takeC(c: C): int { return c.v; }
            """);
        writeFile("src3/entry.deal", """
            import * as lib from "./lib"
            class C { v: int = 0; }
            export function main(): null { return null; }
            export function run(): int {
              return lib.takeC({ v: 9 });
            }
            """);

        Path entryFile3 = tmpDir.resolve("src3/entry.deal").toAbsolutePath();
        Path outputDir3 = tmpDir.resolve("build/imported_class_construct");
        List<Path> roots3 = List.of(tmpDir.resolve("src3").toAbsolutePath());

        CompilationOrchestrator orchestrator3 = new CompilationOrchestrator(
            entryFile3, outputDir3, false, false, false, Backend.JVM,
            (DealConfig) null, roots3, Path.of(".").toAbsolutePath().normalize());

        boolean success3 = orchestrator3.compile();
        check(success3, "construction of an imported class type compiles: "
            + orchestrator3.diagnostics());
        if (success3 && Files.exists(outputDir3.resolve("Entry.java"))) {
            String java = Files.readString(outputDir3.resolve("Entry.java"));
            check(java.contains("new Lib.$C_C(9L)"),
                "the imported construction emits new Lib.$C_C(...): " + java);
            ExecResult exec = runJvmArtifacts(outputDir3,
                parseProgram("export function run(): int { return 1; }"),
                "Entry");
            check(exec.exitCode() == 0 && exec.output().contains("9"),
                "the imported construction runs to 9: " + exec.output());
        }

        // (4) Imported-class construction with defaulted fields: omitted
        // fields receive their declared literal defaults inline
        // (`new Lib.$C_Point(0L, 5L)` for `{ y: 5 }` over
        // `x: int = 0; y: int = 0`), and a provided field whose literal
        // order differs from declaration order lands in its declared
        // field.
        writeFile("src4/lib.deal", """
            export class Point {
              x: int = 10;
              y: int = 20;
            }
            export function sum(p: Point): int { return p.x + p.y; }
            """);
        writeFile("src4/entry.deal", """
            import * as lib from "./lib"
            export function main(): null { return null; }
            export function run(): int {
              let a: lib.Point = {};
              let b: lib.Point = { y: 5, x: 2 };
              return a.x + a.y * 10 + lib.sum(b);
            }
            """);

        Path entryFile4 = tmpDir.resolve("src4/entry.deal").toAbsolutePath();
        Path outputDir4 = tmpDir.resolve("build/imported_class_defaults");
        List<Path> roots4 = List.of(tmpDir.resolve("src4").toAbsolutePath());

        CompilationOrchestrator orchestrator4 = new CompilationOrchestrator(
            entryFile4, outputDir4, false, false, false, Backend.JVM,
            (DealConfig) null, roots4, Path.of(".").toAbsolutePath().normalize());

        boolean success4 = orchestrator4.compile();
        check(success4, "imported construction with literal defaults "
            + "compiles: " + orchestrator4.diagnostics());
        if (success4 && Files.exists(outputDir4.resolve("Entry.java"))) {
            String java = Files.readString(outputDir4.resolve("Entry.java"));
            check(java.contains("new Lib.$C_Point(10L, 20L)"),
                "the empty literal emits every default inline: " + java);
            check(java.contains("new Lib.$C_Point(2L, 5L)"),
                "provided fields land in declaration order regardless of "
                + "literal order: " + java);
            ExecResult exec = runJvmArtifacts(outputDir4,
                parseProgram("export function run(): int { return 1; }"),
                "Entry");
            check(exec.exitCode() == 0 && exec.output().contains("217"),
                "10 + 20 * 10 + (2 + 5) = 217: " + exec.output());
        }

        // (5) Cross-module nominal runtime checks: two modules export a
        // SAME-NAME class; an instance crossing an untyped table passes
        // its own module's check (Modela.$check("@modela/Item", …)
        // succeeds) and fails the foreign module's check with E8001
        // naming both module-qualified identities (@modela/Item vs
        // @modelb/Item) — identity is
        // module-qualified, never bare-name. The foreign check raises the
        // IMPORTED module's DealError, which the conformance runner
        // reports with the DEAL_ERROR_CODE contract.
        writeFile("src5/modela.deal", """
            export class Item { tag: string = ""; }
            export function makeItem(tag: string): Item { return { tag: tag }; }
            export function readItem(i: Item): string { return i.tag; }
            """);
        writeFile("src5/modelb.deal", """
            export class Item { tag: string = ""; }
            export function makeItem(tag: string): Item { return { tag: tag }; }
            export function readItem(i: Item): string { return i.tag; }
            """);
        writeFile("src5/entry.deal", """
            import * as modela from "./modela"
            import * as modelb from "./modelb"
            export function main(): null { return null; }
            export function run(): string {
              let holder: table = { item: modela.makeItem("from-a") };
              let a: modela.Item = holder.item;
              return modela.readItem(a);
            }
            """);
        writeFile("src5/entry_fail.deal", """
            import * as modela from "./modela"
            import * as modelb from "./modelb"
            export function main(): null { return null; }
            export function run(): string {
              let holder: table = { item: modela.makeItem("from-a") };
              let b: modelb.Item = holder.item;
              return modelb.readItem(b);
            }
            """);

        Path outputDir5 = tmpDir.resolve("build/imported_nominal");
        // Success entry: entry.deal (the orchestrator resolves only the
        // imports each entry needs).
        CompilationOrchestrator orchestrator5 = new CompilationOrchestrator(
            tmpDir.resolve("src5/entry.deal").toAbsolutePath(),
            outputDir5, false, false, false, Backend.JVM,
            (DealConfig) null, List.of(tmpDir.resolve("src5").toAbsolutePath()),
            Path.of(".").toAbsolutePath().normalize());
        boolean success5 = orchestrator5.compile();
        check(success5, "cross-module nominal check success shape compiles: "
            + orchestrator5.diagnostics());
        if (success5 && Files.exists(outputDir5.resolve("Entry.java"))) {
            String java = Files.readString(outputDir5.resolve("Entry.java"));
            check(java.contains("Modela.$check(\"@modela/Item\", ("),
                "the imported class-typed table read dispatches on the "
                + "declaring module's shared seam with the spec class "
                + "descriptor: " + java);
            ExecResult exec = runJvmArtifacts(outputDir5,
                parseProgram("export function run(): string { return \"\"; }"),
                "Entry");
            check(exec.exitCode() == 0 && exec.output().contains("from-a"),
                "the genuine instance passes its own module's nominal "
                + "check: " + exec.output());
        }

        Path outputDir6 = tmpDir.resolve("build/imported_nominal_fail");
        CompilationOrchestrator orchestrator6 = new CompilationOrchestrator(
            tmpDir.resolve("src5/entry_fail.deal").toAbsolutePath(),
            outputDir6, false, false, false, Backend.JVM,
            (DealConfig) null, List.of(tmpDir.resolve("src5").toAbsolutePath()),
            Path.of(".").toAbsolutePath().normalize());
        boolean success6 = orchestrator6.compile();
        check(success6, "cross-module nominal check failure shape compiles: "
            + orchestrator6.diagnostics());
        if (success6 && Files.exists(outputDir6.resolve("Entry_fail.java"))) {
            ExecResult exec = runJvmArtifacts(outputDir6,
                parseProgram("export function run(): string { return \"\"; }"),
                "Entry_fail");
            check(exec.exitCode() == 1,
                "the wrong-module same-name instance fails at runtime "
                + "(exit 1): " + exec.output());
            check(exec.output().contains("DEAL_ERROR_CODE: E8001"),
                "the foreign nominal check reports E8001: " + exec.output());
            check(exec.output().contains(
                    "expected instance of @modelb/Item, got @modela/Item"),
                "the E8001 message names both module-qualified identities: "
                    + exec.output());
        }

        // (6) Positive control: passing an imported class VALUE straight
        // through (`lib.takeC(lib.getC())`) never binds a local or
        // constructs with the foreign type, so it must compile and run —
        // the value is produced and consumed inside lib.
        writeFile("src6/lib.deal", """
            class C { v: int = 0; }
            export function getC(): C { return { v: 42 }; }
            export function takeC(c: C): int { return c.v; }
            """);
        writeFile("src6/entry.deal", """
            import * as lib from "./lib"
            export function main(): null { return null; }
            export function run(): int {
              return lib.takeC(lib.getC());
            }
            """);

        Path entryFile6 = tmpDir.resolve("src6/entry.deal").toAbsolutePath();
        Path outputDir7 = tmpDir.resolve("build/imported_class_passthrough");
        List<Path> roots6 = List.of(tmpDir.resolve("src6").toAbsolutePath());

        CompilationOrchestrator orchestrator7 = new CompilationOrchestrator(
            entryFile6, outputDir7, false, false, false, Backend.JVM,
            (DealConfig) null, roots6, Path.of(".").toAbsolutePath().normalize());

        boolean success7 = orchestrator7.compile();
        check(success7, "imported class value pass-through compiles: "
            + orchestrator7.diagnostics());
        check(Files.exists(outputDir7.resolve("Entry.java"))
                && Files.exists(outputDir7.resolve("Lib.java")),
            "pass-through artifacts written");
        if (success7 && Files.exists(outputDir7.resolve("Entry.java"))) {
            ExecResult exec = runJvmArtifacts(outputDir7,
                parseProgram("export function run(): int { return 1; }"),
                "Entry");
            check(exec.exitCode() == 0 && exec.output().contains("42"),
                "the pass-through call runs to 42: " + exec.output());
        }
    }

    /**
     * ISSUE-0108 nullable slice: the boxed reference representation of
     * {@code T | null} (Long/Double/Boolean for the numeric primitives,
     * String/class/array references), narrowed-read unboxing through the
     * null-check branches, nullable class fields, nullable parameters and
     * returns, {@code T[] | null} and {@code (T | null)[]} for the four
     * primitives and local classes, the table-read nullable boundary
     * checks (E8001 for a wrong inner value — the runtime probe lives in
     * the conformance fixture jvm-nullable-boundary-failure), the
     * nullable conversion intrinsics, and the still-rejected forms
     * ({@code table | null}, {@code (table | null)[]}, optional class
     * fields, class-typed class fields). Every runtime probe compiles
     * the emitted artifact with the real {@code javac} frontend and
     * executes it with {@code java}.
     */
    /**
     * ISSUE-0110: the single JVM type-descriptor emitter spells every
     * supported type form in the spec {@code RuntimeTypeDescriptor}
     * format — primitives, table/null/Error, nullables, arrays (nested
     * and nullable-element), nominal classes with module paths, function
     * types with array/class/nullable parameters, async operation types,
     * and rest arms.
     */
    private static void testTypeDescriptorEmitter() {
        System.out.println("-- Shared type-descriptor emitter (ISSUE-0110) --");
        check(JvmBackend.typeDescriptor(Type.Int.INSTANCE).equals("int"),
            "int spells 'int'");
        check(JvmBackend.typeDescriptor(Type.Number.INSTANCE).equals("number"),
            "number spells 'number'");
        check(JvmBackend.typeDescriptor(Type.Boolean.INSTANCE).equals("boolean"),
            "boolean spells 'boolean'");
        check(JvmBackend.typeDescriptor(Type.String.INSTANCE).equals("string"),
            "string spells 'string'");
        check(JvmBackend.typeDescriptor(Type.Table.INSTANCE).equals("table"),
            "table spells 'table'");
        check(JvmBackend.typeDescriptor(Type.Null.INSTANCE).equals("null"),
            "null spells 'null'");
        check(JvmBackend.typeDescriptor(Type.Error.INSTANCE).equals("Error"),
            "Error spells 'Error'");
        check(JvmBackend.typeDescriptor(Types.nullable(Type.Int.INSTANCE)).equals("?int"),
            "int | null spells '?int'");
        check(JvmBackend.typeDescriptor(Types.array(Type.Int.INSTANCE)).equals("[int]"),
            "int[] spells '[int]'");
        check(JvmBackend.typeDescriptor(
                Types.array(Types.array(Type.Int.INSTANCE))).equals("[[int]]"),
            "int[][] spells '[[int]]'");
        check(JvmBackend.typeDescriptor(
                Types.nullable(Types.array(Type.Int.INSTANCE))).equals("?[int]"),
            "int[] | null spells '?[int]'");
        check(JvmBackend.typeDescriptor(
                Types.array(Types.nullable(Type.Int.INSTANCE))).equals("[?int]"),
            "(int | null)[] spells '[?int]'");
        check(JvmBackend.typeDescriptor(Types.nullable(Types.array(
                Types.nullable(Type.Int.INSTANCE)))).equals("?[?int]"),
            "(int | null)[] | null spells '?[?int]'");
        check(JvmBackend.typeDescriptor(
                Types.classType("User", "models")).equals("@models/User"),
            "a class with a module path spells '@models/User'");
        check(JvmBackend.typeDescriptor(
                Types.classType("User", "")).equals("User"),
            "a class with an empty module path spells the bare name");
        check(JvmBackend.typeDescriptor(Types.nullable(
                Types.classType("User", "app"))).equals("?@app/User"),
            "User | null spells '?@app/User'");
        check(JvmBackend.typeDescriptor(Types.array(
                Types.classType("User", "app"))).equals("[@app/User]"),
            "User[] spells '[@app/User]'");
        check(JvmBackend.typeDescriptor(Types.array(Types.nullable(
                Types.classType("User", "app")))).equals("[?@app/User]"),
            "(User | null)[] spells '[?@app/User]'");
        check(JvmBackend.typeDescriptor(Types.func(
                List.of(Type.Int.INSTANCE), Type.String.INSTANCE))
                .equals("(int)->string"),
            "(int)->string spells '(int)->string'");
        check(JvmBackend.typeDescriptor(Types.func(
                List.of(Type.Int.INSTANCE), Type.String.INSTANCE, true))
                .equals("async(int)->string"),
            "async(int)->string spells 'async(int)->string'");
        check(JvmBackend.typeDescriptor(Types.func(
                List.of(Types.array(Type.Int.INSTANCE)),
                Types.nullable(Types.classType("User", "app"))))
                .equals("([int])->?@app/User"),
            "an array parameter and a nullable class return compose in one "
            + "descriptor");
        check(JvmBackend.typeDescriptor(Types.func(
                List.of(Types.nullable(Type.String.INSTANCE)),
                Types.array(Types.classType("User", "app"))))
                .equals("(?string)->[@app/User]"),
            "a nullable parameter and a class-array return compose in one "
            + "descriptor");
        System.out.println("  typeDescriptor forms pinned");
    }

    /**
     * ISSUE-0110: every supported untyped table-read boundary emits ONE
     * shared {@code $check(descriptor, value)} call carrying the spec
     * {@code RuntimeTypeDescriptor} of the expected type — one
     * convention for classes, nullable classes, nullable primitives,
     * arrays, nullable-element arrays, nullable arrays, and tables —
     * and no retired per-kind helper remains in the artifact.
     */
    private static void testSharedCheckSeam() {
        System.out.println("-- Shared descriptor-driven runtime-check seam (ISSUE-0110) --");
        Frontend f = compileFrontend("""
            class Point { x: int = 0; }
            export function test(): int {
              let p0: Point = { x: 1 };
              let ps0: Point[] = [];
              ps0[0] = p0;
              let holder: table = { p: p0, n: 5, xs: [1], ps: ps0, inner: { k: 2 } };
              let p: Point = holder.p;
              let maybe: Point | null = holder.p;
              let n: int | null = holder.n;
              let xs: int[] = holder.xs;
              let ys: (int | null)[] = holder.xs;
              let zs: int[] | null = holder.xs;
              let ps: Point[] = holder.ps;
              let qs: (Point | null)[] = holder.ps;
              let inner: table = holder.inner;
              let total: int = p.x + xs[0] + ys.length + ps.length
                  + qs.length;
              if (maybe !== null) { total = total + maybe.x; }
              if (n !== null) { total = total + n; }
              if (zs !== null) { total = total + zs.length; }
              return total;
            }
            """, "jvmtest-seam.deal");
        check(f.errors().isEmpty(), "seam fixture frontend clean: " + f.errors());
        if (!f.errors().isEmpty()) return;
        JvmBackend.JvmCodegenResult res = JvmBackend.generate(
            f.program(), f.checkResult(), "jvmtest-seam.deal", "Main");
        check(!res.hasErrors(), "seam fixture codegen clean: " + res.diagnostics());
        if (res.hasErrors()) return;
        String java = res.source();
        check(java.contains("$check(\"@Main/Point\", "),
            "class-typed read dispatches on the spec class descriptor");
        check(java.contains("$check(\"?@Main/Point\", "),
            "nullable class read dispatches on '?@Main/Point'");
        check(java.contains("$check(\"?int\", "),
            "nullable int read dispatches on '?int'");
        check(java.contains("$check(\"[int]\", "),
            "int[] read dispatches on '[int]'");
        check(java.contains("$check(\"[?int]\", "),
            "(int | null)[] read dispatches on '[?int]'");
        check(java.contains("$check(\"?[int]\", "),
            "int[] | null read dispatches on '?[int]'");
        check(java.contains("$check(\"[@Main/Point]\", "),
            "Point[] read dispatches on '[@Main/Point]'");
        check(java.contains("$check(\"[?@Main/Point]\", "),
            "(Point | null)[] read dispatches on '[?@Main/Point]'");
        check(java.contains("$check(\"table\", "),
            "table read dispatches on 'table'");
        check(occurrences(java, "static java.lang.Object $check(java.lang.String descriptor, java.lang.Object v)") == 1,
            "exactly one shared $check seam is emitted");
        check(!java.contains("$check$Table") && !java.contains("$checkNullable")
                && !java.contains("$check$IntArray"),
            "no retired per-kind boundary helper remains in the artifact");
        // The seam artifact is real Java: compile it with javac.
        Path dir = null;
        try {
            dir = Files.createTempDirectory("jvmtest_seam_");
            Files.writeString(dir.resolve("Main.java"), java);
            Files.writeString(dir.resolve("JvmConformanceRunner.java"),
                BackendConformanceTest.buildJvmRunner(f.program(), "Main"));
            StringBuilder err = new StringBuilder();
            boolean ok = BackendConformanceTest.compileWithJavac(dir,
                List.of("Main.java", "JvmConformanceRunner.java"), err);
            check(ok, "the seam artifact compiles with javac: " + err);
        } catch (IOException e) {
            fail("seam javac I/O: " + e);
        } finally {
            if (dir != null) {
                try {
                    Files.walk(dir).sorted(Comparator.reverseOrder())
                        .forEach(p -> { try { Files.deleteIfExists(p); }
                        catch (IOException ignored) {} });
                } catch (IOException ignored) {}
            }
        }
        System.out.println("  seam dispatch descriptors pinned");
    }

    private static void testNullableSlice() throws Exception {
        System.out.println("-- Nullable slice (ISSUE-0108): boxed representation, narrowing, nullable arrays --");

        ExecResult flow = compileAndRunJvm("""
            class Holder {
              i: int | null = 1;
              s: string | null = null;
            }
            function pick(when: int, y: int | null): int | null {
              if (when === 0) { return null; }
              return y;
            }
            export function test(): int {
              let n: int | null = 5;
              let r: int = 0;
              if (n !== null) { r = n; }
              let m: int | null = null;
              if (m === null) { r = r + 10; } else { r = 99; }
              let s: string | null = "ab";
              if (s !== null) { if (s + "c" === "abc") { r = r + 1; } }
              let b: boolean | null = true;
              if (b !== null) { if (b) { r = r + 1; } }
              let xs: int[] | null = [1, 2];
              if (xs !== null) { r = r + xs[0] + xs[1] + xs.length; }
              let ys: (int | null)[] = [];
              ys[0] = null;
              ys[1] = 7;
              let y0: int | null = ys[0];
              if (y0 === null) { r = r + 1; }
              let y1: int | null = ys[1];
              if (y1 !== null) { r = r + y1; }
              let p: int | null = pick(3, 20);
              if (p !== null) { r = r + p; }
              let z: int | null = pick(0, 1);
              if (z === null) { r = r + 1; }
              let h: Holder = {};
              let hi: int | null = h.i;
              if (hi !== null) { r = r + hi; }
              h.s = "x";
              let hs: string | null = h.s;
              if (hs !== null) { if (hs === "x") { r = r + 1; } }
              let t: table = { v: 2 };
              let tv: int | null = t.v;
              if (tv !== null) { r = r + tv; }
              return r;
            }
            """, "nullable-flow");
        check(flow.exitCode() == 0, "nullable flow runs clean: " + flow.output());
        check(flow.output().contains("55"), "nullable flow value 55: " + flow.output());

        ExecResult neg = compileAndRunJvm("""
            export function test(): int {
              let ys: (int | null)[] = [];
              let a: int | null = ys[-1];
              if (a === null) { return 0; }
              return 1;
            }
            """, "nullable-neg-index");
        check(neg.output().contains("DEAL_ERROR_CODE: E8002"),
            "negative index into (int | null)[] raises E8002: " + neg.output());

        record Gate(String what, String source) {}
        List<Gate> gates = List.of(
            new Gate("nullable table type", """
                export function test(): null {
                  let t: table | null = null;
                }
                """),
            new Gate("array of nullable table elements", """
                export function test(): null {
                  let xs: (table | null)[] = [];
                }
                """)
        );
        for (Gate g : gates) {
            Frontend f = compileFrontend(g.source(), "jvmtest-nullable-gate.deal");
            if (!f.errors().isEmpty()) {
                fail("frontend must accept nullable gate '" + g.what()
                    + "' (the backend rejects it): " + f.errors());
                continue;
            }
            JvmBackend.JvmCodegenResult res = JvmBackend.generate(
                f.program(), f.checkResult(),
                "jvmtest-nullable-gate.deal", "main");
            check(res.hasErrors(), "backend rejects " + g.what());
            check(res.diagnostics().stream().anyMatch(d -> "E6000".equals(d.code())),
                "E6000 diagnostic for " + g.what() + ": " + res.diagnostics());
        }

        // ISSUE-0102: optional and class-typed class fields promoted out
        // of the E6000 gate — they now compile (and run through the
        // class fixtures above).
        List<Gate> promoted = List.of(
            new Gate("optional class field", """
                class Point { x?: int; }
                export function test(): int { return 1; }
                """),
            new Gate("class-typed class field", """
                class Inner { v: int = 0; }
                class Outer { inner: Inner = {}; }
                export function test(): int { return 1; }
                """)
        );
        for (Gate g : promoted) {
            Frontend f = compileFrontend(g.source(), "jvmtest-nullable-gate.deal");
            if (!f.errors().isEmpty()) {
                fail("frontend must accept promoted gate '" + g.what()
                    + "': " + f.errors());
                continue;
            }
            JvmBackend.JvmCodegenResult res = JvmBackend.generate(
                f.program(), f.checkResult(),
                "jvmtest-nullable-gate.deal", "main");
            check(!res.hasErrors(),
                "backend now accepts " + g.what() + ": " + res.diagnostics());
        }
    }

    /** An import of a COMPILED project module (ISSUE-0096) is supported by
     * the orchestrator's JVM path — even when the import is never used — and
     * the imported module's load-time side effects still run (the import
     * emits the {@code __init$} initialization trigger exactly where
     * LuaJIT runs require). */
    private static void testOrchestratorJvmImportSupported() throws Exception {
        System.out.println("-- Orchestrator: unused project-module import runs load-time code --");

        // v1.2: the imported module's load-time side effect moves inside
        // an exported function (module-level statements were removed); the
        // load-time trigger surface itself is exercised by the
        // init-trigger fixture in the backend conformance suite.
        writeFile("src/other.deal", """
            import * as console from "std/console"
            export function unused(): int { return 1; }
            export function announce(): null { console.log("other-module-ran"); }
            """);
        writeFile("src/entry.deal", """
            import * as m from "./other"
            export function main(): null { return null; }
            export function run(): int { return 1; }
            """);

        Path entryFile = tmpDir.resolve("src/entry.deal").toAbsolutePath();
        Path outputDir = tmpDir.resolve("build/import_supported");
        List<Path> roots = List.of(tmpDir.resolve("src").toAbsolutePath());

        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            entryFile, outputDir, false, false, false, Backend.JVM,
            (DealConfig) null, roots, Path.of(".").toAbsolutePath().normalize());

        boolean success = orchestrator.compile();
        check(success, "unused project-module import compiles: "
            + orchestrator.diagnostics());
        if (!success) return;

        Path entryArtifact = outputDir.resolve("Entry.java");
        Path otherArtifact = outputDir.resolve("Other.java");
        check(Files.exists(entryArtifact), "entry artifact written");
        check(Files.exists(otherArtifact), "imported module artifact written");

        if (Files.exists(entryArtifact) && Files.exists(otherArtifact)) {
            String java = Files.readString(entryArtifact);
            check(java.contains("Other.__init$();"),
                "the import emits the init trigger for the imported class");
            check(java.contains("static {"),
                "the trigger sits in a load-time static block");

            // The emitted artifacts must be real: javac + java run the
            // imported module's init trigger even though the alias is
            // never used (v1.2: module top level holds only declarations,
            // so the only load-time work is the imported module's own
            // initialization).
            Files.writeString(outputDir.resolve("JvmConformanceRunner.java"),
                BackendConformanceTest.buildJvmRunner(
                    parseProgram("""
                        export function run(): int { return 1; }
                        """), "Entry"));
            ProcessBuilder javac = new ProcessBuilder("javac", "-encoding", "UTF-8",
                "Entry.java", "Other.java", "JvmConformanceRunner.java");
            javac.directory(outputDir.toFile());
            javac.redirectErrorStream(true);
            Process p = javac.start();
            String javacOut = new String(p.getInputStream().readAllBytes()).trim();
            int javacExit = p.waitFor();
            check(javacExit == 0, "orchestrator artifacts compile with javac: "
                + javacOut);

            ProcessBuilder javaRun = new ProcessBuilder("java", "-cp",
                outputDir.toString(), "JvmConformanceRunner");
            javaRun.redirectErrorStream(true);
            Process p2 = javaRun.start();
            String out = new String(p2.getInputStream().readAllBytes()).trim();
            int exit = p2.waitFor();
            check(exit == 0, "runner exits 0");
            check(!out.contains("other-module-ran"),
                "the unused import ran no load-time print (v1.2 top level "
                + "holds only declarations): " + out);
        }
    }

    // =========================================================================
    // ISSUE-0097 stdlib boundary slice: std/console, std/string, std/math, std/time
    // =========================================================================

    /** The supported stdlib aliases emit the ISSUE-0097 call forms:
     * {@code __str*}/{@code __mathSqrt} helpers, plain-text
     * {@code String.contains/startsWith/endsWith}, fully-qualified
     * {@code java.lang.Math} expressions, and the second-truncated
     * {@code nowMillis()} form — with every helper definition present in
     * the module class. */
    private static void testStdlibCallEmission() {
        System.out.println("-- Stdlib call emission (ISSUE-0097) --");

        Frontend f = compileFrontend("""
            import * as str from "std/string"
            import * as math from "std/math"
            import * as time from "std/time"
            export function run(): string {
              let n: int = str.length("abc");
              let sub: string = str.substring("hello", 1, 4);
              let c: boolean = str.contains("hello", "ell");
              let sw: boolean = str.startsWith("hello", "h");
              let ew: boolean = str.endsWith("hello", "o");
              let r: string = str.replace("a", "a", "b");
              let parts: string[] = str.split("a,b", ",");
              let t: string = str.trim("  x  ");
              let fl: number = math.floor(1.9);
              let ce: number = math.ceil(1.1);
              let sq: number = math.sqrt(4.0);
              let ai: int = math.absInt(-3);
              let an: number = math.absNumber(-1.5);
              let mn: int = math.minInt(2, 3);
              let mx: int = math.maxInt(2, 3);
              let now: int = time.nowMillis();
              return sub;
            }
            """, "jvmtest-stdlib-emission.deal");
        if (!f.errors().isEmpty()) {
            fail("frontend must accept the stdlib program: " + f.errors());
            return;
        }
        JvmBackend.JvmCodegenResult res = JvmBackend.generate(f.program(),
            f.checkResult(), "jvmtest-stdlib-emission.deal", "main");
        if (res.hasErrors()) {
            fail("backend must accept the stdlib program: " + res.diagnostics());
            return;
        }
        String java = res.source();
        check(java.contains("__strLength("), "length emits __strLength");
        check(java.contains("__strSubstring("), "substring emits __strSubstring");
        check(java.contains(").contains("), "contains emits String.contains");
        check(java.contains(").startsWith("), "startsWith emits String.startsWith");
        check(java.contains(").endsWith("), "endsWith emits String.endsWith");
        check(java.contains("__strReplace("), "replace emits __strReplace");
        check(java.contains("__strSplit("), "split emits __strSplit");
        check(java.contains("__strTrim("), "trim emits __strTrim");
        check(java.contains("java.lang.Math.floor("), "floor emits Math.floor");
        check(java.contains("java.lang.Math.ceil("), "ceil emits Math.ceil");
        check(java.contains("__mathSqrt("), "sqrt emits __mathSqrt");
        check(java.contains("checkInt(java.lang.Math.abs("),
            "absInt re-checks with checkInt");
        check(java.contains("java.lang.Math.abs("), "absNumber emits Math.abs");
        check(java.contains("java.lang.Math.min("), "minInt emits Math.min");
        check(java.contains("java.lang.Math.max("), "maxInt emits Math.max");
        check(java.contains("(java.lang.System.currentTimeMillis() / 1000L) * 1000L"),
            "nowMillis emits the second-truncated form");
        check(java.contains("static long __strLength(java.lang.String s)"),
            "the __strLength helper definition is emitted");
        check(java.contains("static java.lang.String __strSubstring("),
            "the __strSubstring helper definition is emitted");
        check(java.contains("static __StringArray __strSplit("),
            "the __strSplit helper definition is emitted");
        check(java.contains("static double __mathSqrt(double x)"),
            "the __mathSqrt helper definition is emitted");
    }

    /** {@code std/table} and {@code std/json} imports — used and unused —
     * are E6000 at the import statement: their only functions require
     * {@code table} values, which the JVM slice does not support. */
    private static void testStdlibTableBoundaryRejected() throws Exception {
        System.out.println("-- Stdlib table-boundary imports → E6000 --");

        record Case(String what, String source, String module) {}
        List<Case> cases = List.of(
            new Case("std/table import + table use", """
                import * as t from "std/table"
                export function test(): int {
                  let tbl: table = {};
                  return 1;
                }
                """, "std/table"),
            new Case("unused std/table import", """
                import * as t from "std/table"
                export function test(): int { return 1; }
                """, "std/table"),
            new Case("std/json import + call", """
                import * as j from "std/json"
                export function test(): int {
                  let parsed: table = j.parse("{}");
                  return 1;
                }
                """, "std/json"),
            new Case("unused std/json import", """
                import * as j from "std/json"
                export function test(): int { return 1; }
                """, "std/json")
        );
        for (Case c : cases) {
            Frontend f = compileFrontend(c.source(), "jvmtest-stdlib-boundary.deal");
            if (!f.errors().isEmpty()) {
                fail("frontend must accept the table-boundary stdlib import '"
                    + c.what() + "' (the backend rejects it): " + f.errors());
                continue;
            }
            JvmBackend.JvmCodegenResult res = JvmBackend.generate(f.program(),
                f.checkResult(), "jvmtest-stdlib-boundary.deal", "main");
            if (c.module().equals("std/table")) {
                // ISSUE-0102: std/table.keys now executes — tables map as
                // first-class values, so the import compiles (the emitted
                // artifact carries the __tableKeys helper).
                check(!res.hasErrors(),
                    "backend accepts " + c.what() + ": " + res.diagnostics());
                check(res.source().contains("__tableKeys"),
                    "std/table import emits the keys helper");
                continue;
            }
            check(res.hasErrors(), "backend rejects " + c.what());
            check(res.diagnostics().stream().anyMatch(d ->
                    "E6000".equals(d.code())
                        && d.message().contains("table values")
                        && d.message().contains(c.module())),
                "E6000 names the table boundary for " + c.what() + ": "
                    + res.diagnostics());
        }

        // The orchestrator's JVM path reports the same E6000 at the import
        // statement and writes no artifact for the std/json-importing
        // module (the backend is the single rejection site).
        writeFile("src/table_import.deal", """
            import * as j from "std/json"
            export function main(): null { return null; }
            export function run(): int { return 1; }
            """);
        Path entryFile = tmpDir.resolve("src/table_import.deal").toAbsolutePath();
        Path outputDir = tmpDir.resolve("build/table_boundary");
        List<Path> roots = List.of(tmpDir.resolve("src").toAbsolutePath());
        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            entryFile, outputDir, false, false, false, Backend.JVM,
            (DealConfig) null, roots, Path.of(".").toAbsolutePath().normalize());
        boolean success = orchestrator.compile();
        check(!success, "orchestrator JVM path rejects std/json imports");
        check(orchestrator.diagnostics().stream().anyMatch(d ->
                "E6000".equals(d.code()) && d.message().contains("table values")),
            "orchestrator reports the table boundary: "
                + orchestrator.diagnostics());
        check(!Files.exists(outputDir.resolve("Table_import.java")),
            "no artifact written for the std/json-importing module");
    }

    /** Real stdlib execution through the emitted artifact: every supported
     * std/string and std/math function computes its LuaJIT reference value,
     * including the substring clipping and the v1.2 negative-bound semantics
     * (a negative start behaves as 0, a negative end yields the empty
     * string). */
    private static void testStdlibExecution() throws Exception {
        System.out.println("-- Stdlib execution (ISSUE-0097) --");

        ExecResult res = compileAndRunJvm("""
            import * as str from "std/string"
            import * as math from "std/math"
            import * as console from "std/console"
            export function run(): string {
              let n: int = str.length("hello");
              let sub: string = str.substring("hello", 1, 4);
              let clip: string = str.substring("abc", 1, 10);
              let past: string = str.substring("abc", 5, 10);
              let neg: string = str.substring("abc", -3, 3);
              let negEnd: string = str.substring("abc", 1, -1);
              let negBoth: string = str.substring("abc", 0, -1);
              let negStartMid: string = str.substring("abc", -2, 2);
              let trimmed: string = str.trim("  deal  ");
              let replaced: string = str.replace("a-b-c", "-", ":");
              let kept: string = str.replace("hello", "", "x");
              let parts: string[] = str.split("a,,b", ",");
              let fl: int = int(math.floor(3.8));
              let ce: int = int(math.ceil(3.2));
              let sq: int = int(math.sqrt(9.0));
              let ai: int = math.absInt(-7);
              let an: number = math.absNumber(-2.5);
              let mn: int = math.minInt(3, 9);
              let mx: int = math.maxInt(3, 9);
              let vt: string = str.trim("\u000bhello\u000b");
              let ff: string = str.trim("\u000chello\u000c");
              if (n !== 5 || sub !== "ell" || clip !== "bc" || past !== ""
                  || neg !== "abc" || negEnd !== "" || negBoth !== ""
                  || negStartMid !== "ab" || trimmed !== "deal"
                  || replaced !== "a:b:c" || kept !== "hello"
                  || fl !== 3 || ce !== 4 || sq !== 3 || ai !== 7
                  || an !== 2.5 || mn !== 3 || mx !== 9
                  || vt !== "hello" || ff !== "hello") {
                console.log("stdlib-bad");
                return "bad";
              }
              if (parts.length !== 3 || parts[0] !== "a"
                  || parts[1] !== "" || parts[2] !== "b") {
                console.log("stdlib-bad");
                return "bad";
              }
              console.log("stdlib-ok");
              return "stdlib-done";
            }
            """, "stdlib-execution");
        check(res.exitCode() == 0, "stdlib execution exits 0: " + res.output());
        check(res.output().contains("stdlib-ok"),
            "stdlib execution prints stdlib-ok: " + res.output());
        check(res.output().contains("stdlib-done"),
            "stdlib execution returns the final value: " + res.output());
    }

    /** {@code math.sqrt} of a negative number raises E8001 exactly like
     * std/math.lua — a real runtime error surfaced by executing the
     * emitted artifact. */
    private static void testStdlibSqrtNegativeRuntimeError() throws Exception {
        System.out.println("-- Stdlib sqrt(-1) → E8001 --");

        ExecResult res = compileAndRunJvm("""
            import * as math from "std/math"
            export function run(): number { return math.sqrt(-1.0); }
            """, "stdlib-sqrt-neg");
        check(res.exitCode() == 1, "sqrt(-1) exits 1: " + res.output());
        check(res.output().contains("DEAL_ERROR_CODE: E8001"),
            "sqrt(-1) reports E8001: " + res.output());
        check(res.output().contains("sqrt of negative number"),
            "sqrt(-1) reports the std/math.lua message: " + res.output());
    }

    /** ISSUE-0106 v1.2 Unicode scalar-value semantics: {@code
     * length("héllo")} is 5 scalar values, {@code substring} positions
     * are scalar values ({"é"} at position 1..2), and a supplementary
     * character counts as ONE scalar value everywhere. */
    private static void testStdlibScalarSemantics() throws Exception {
        System.out.println("-- Stdlib Unicode scalar-value semantics --");

        ExecResult res = compileAndRunJvm("""
            import * as str from "std/string"
            export function run(): string {
              let scalars: int = str.length("héllo");
              let e: string = str.substring("héllo", 1, 2);
              let sup: int = str.length("\ud83d\ude00");
              let cut: string = str.substring("a\ud83d\ude00b", 1, 2);
              if (scalars !== 5) { return "bad-scalars"; }
              if (e !== "é") { return "bad-e"; }
              if (sup !== 1) { return "bad-sup"; }
              if (cut !== "\ud83d\ude00") { return "bad-cut"; }
              return "scalars-ok";
            }
            """, "stdlib-scalars");
        check(res.exitCode() == 0, "scalar-semantics run exits 0: " + res.output());
        check(res.output().contains("scalars-ok"),
            "scalar-value semantics compute the v1.2 values: " + res.output());
    }

    /** {@code nowMillis()} reproduces LuaJIT's {@code os.time() * 1000}:
     * positive, near the current epoch, and second-truncated. */
    private static void testStdlibTimeNowMillis() throws Exception {
        System.out.println("-- Stdlib nowMillis (ISSUE-0097) --");

        ExecResult res = compileAndRunJvm("""
            import * as time from "std/time"
            export function run(): int {
              let t: int = time.nowMillis();
              let g: int = t % 1000;
              if (t > 1700000000000 && g === 0) { return 1; }
              return 0;
            }
            """, "stdlib-nowmillis");
        check(res.exitCode() == 0, "nowMillis run exits 0: " + res.output());
        check(res.output().contains("1"), "nowMillis is positive, recent, and "
            + "second-truncated: " + res.output());
    }

    /** DEAL functions named like the emitted stdlib helpers coexist with
     * them: {@link JvmBackend#javaName} escapes every underscore, so the
     * user functions translate to {@code $u$u…} names and never collide
     * with the {@code __str*}/{@code __mathSqrt} helpers. */
    private static void testStdlibHelperNameCollisions() throws Exception {
        System.out.println("-- Stdlib helper name collisions --");

        ExecResult res = compileAndRunJvm("""
            function __strLength(s: string): int { return 1; }
            function __strTrim(s: string): string { return s; }
            function __strSplit(s: string, sep: string): string[] { return []; }
            function __strReplace(s: string, o: string, to: string): string { return s; }
            function __mathSqrt(x: number): number { return x; }
            export function run(): string {
              let a: int = __strLength("abc");
              let b: string = __strTrim("  x  ");
              let c: string[] = __strSplit("a", ",");
              let d: string = __strReplace("a", "a", "b");
              let e: number = __mathSqrt(4.0);
              if (a !== 1 || b !== "  x  " || c.length !== 0
                  || d !== "a" || e !== 4.0) {
                return "collision-bad";
              }
              return "collision-ok";
            }
            """, "stdlib-helper-collide");
        check(res.exitCode() == 0, "helper-name-collision run exits 0: "
            + res.output());
        check(res.output().contains("collision-ok"),
            "user functions named like stdlib helpers coexist and run: "
                + res.output());
    }

    /** The orchestrator's JVM path resolves stdlib imports from the real
     * stdlib declarations, emits the supported stdlib calls, and the
     * emitted artifacts compile and execute. */
    private static void testOrchestratorJvmStdlibImport() throws Exception {
        System.out.println("-- Orchestrator: stdlib imports through the JVM path --");

        writeFile("src/strings.deal", """
            import * as str from "std/string"
            export function wordCount(s: string): int {
              let parts: string[] = str.split(s, " ");
              return parts.length;
            }
            """);
        writeFile("src/entry.deal", """
            import * as strings from "./strings"
            import * as math from "std/math"
            export function main(): null { return null; }
            export function run(): int {
              let words: int = strings.wordCount("a b c");
              return math.absInt(words - 6);
            }
            """);

        Path entryFile = tmpDir.resolve("src/entry.deal").toAbsolutePath();
        Path outputDir = tmpDir.resolve("build/stdlib_import");
        List<Path> roots = List.of(tmpDir.resolve("src").toAbsolutePath());

        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            entryFile, outputDir, false, false, false, Backend.JVM,
            (DealConfig) null, roots, Path.of(".").toAbsolutePath().normalize());

        boolean success = orchestrator.compile();
        check(success, "stdlib-importing project compiles through the "
            + "orchestrator JVM path: " + orchestrator.diagnostics());
        if (!success) return;

        Path entryArtifact = outputDir.resolve("Entry.java");
        Path stringsArtifact = outputDir.resolve("Strings.java");
        check(Files.exists(entryArtifact), "entry artifact written");
        check(Files.exists(stringsArtifact), "imported module artifact written");

        if (Files.exists(entryArtifact) && Files.exists(stringsArtifact)) {
            String java = Files.readString(stringsArtifact);
            check(java.contains("__strSplit("),
                "the imported module emits the std/string split helper call");

            Files.writeString(outputDir.resolve("JvmConformanceRunner.java"),
                BackendConformanceTest.buildJvmRunner(
                    parseProgram("""
                        export function run(): int { return 3; }
                        """), "Entry"));
            ProcessBuilder javac = new ProcessBuilder("javac", "-encoding", "UTF-8",
                "Entry.java", "Strings.java", "JvmConformanceRunner.java");
            javac.directory(outputDir.toFile());
            javac.redirectErrorStream(true);
            Process p = javac.start();
            String javacOut = new String(p.getInputStream().readAllBytes()).trim();
            int javacExit = p.waitFor();
            check(javacExit == 0, "orchestrator stdlib artifacts compile with "
                + "javac: " + javacOut);

            ProcessBuilder javaRun = new ProcessBuilder("java", "-cp",
                outputDir.toString(), "JvmConformanceRunner");
            javaRun.redirectErrorStream(true);
            Process p2 = javaRun.start();
            String out = new String(p2.getInputStream().readAllBytes()).trim();
            int exit = p2.waitFor();
            check(exit == 0, "runner exits 0: " + out);
            check(out.contains("3"),
                "stdlib helpers compute through the cross-module call: " + out);
        }
    }

    /** ISSUE-0100: a relative (./) declaration-file import is now a HOST
     * module on the JVM path — the same classification the LuaJIT use
     * site applies (host-module-abi D5(4)) — so it compiles into host
     * wrapper methods instead of the pre-slice E6000. An unsupported
     * declared export shape (a class export) still fails E6000 at the
     * import statement. */
    private static void testOrchestratorJvmDeclarationImportRejected() throws Exception {
        System.out.println("-- Orchestrator: declaration-file import = host module (ISSUE-0100) --");

        writeFile("src/hostlib.d.deal", """
            export function foo(): int;
            """);
        writeFile("src/entry.deal", """
            import * as m from "./hostlib"
            export function main(): null { return null; }
            export function run(): int { return m.foo(); }
            """);

        Path entryFile = tmpDir.resolve("src/entry.deal").toAbsolutePath();
        Path outputDir = tmpDir.resolve("build/import_decl_rejected");
        List<Path> roots = List.of(tmpDir.resolve("src").toAbsolutePath());

        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            entryFile, outputDir, false, false, false, Backend.JVM,
            (DealConfig) null, roots, Path.of(".").toAbsolutePath().normalize());

        boolean success = orchestrator.compile();
        check(success, "relative declaration-file import compiles as a "
            + "host module: " + orchestrator.diagnostics());
        Path entryArtifact = outputDir.resolve("Entry.java");
        check(Files.exists(entryArtifact), "host-importing module artifact written");
        if (Files.exists(entryArtifact)) {
            String java = Files.readString(entryArtifact);
            check(java.contains("__host$m$foo("),
                "the host export emits its wrapper method");
            check(java.contains("java.lang.Class.forName(\"Hostlib\")"),
                "the wrapper loads the host class derived from the module path");
            check(java.contains("__hostMethod(__h, \"./hostlib\", \"foo\", \"()->int\""),
                "the load-time presence check names the declared export");
        }

        // Unsupported declared export shape: a host class export stays
        // E6000 at the import statement, never silently miscompiled.
        writeFile("src/hostlib.d.deal", """
            export class User { name: string; }
            """);
        writeFile("src/entry.deal", """
            import * as m from "./hostlib"
            export function main(): null { return null; }
            export function run(): int { return 1; }
            """);
        Path outputDir2 = tmpDir.resolve("build/import_decl_class");
        CompilationOrchestrator orchestrator2 = new CompilationOrchestrator(
            entryFile, outputDir2, false, false, false, Backend.JVM,
            (DealConfig) null, roots, Path.of(".").toAbsolutePath().normalize());
        boolean success2 = orchestrator2.compile();
        check(!success2, "host class export fails the JVM compile");
        check(orchestrator2.diagnostics().stream()
                .anyMatch(d -> "E6000".equals(d.code())
                    && d.message().contains("host class exports are not supported")),
            "orchestrator reports E6000 for the host class export: "
                + orchestrator2.diagnostics());
        check(!Files.exists(outputDir2.resolve("Entry.java")),
            "no artifact for the module with the unsupported host export");
    }

    /** ISSUE-0100 end-to-end: an externals-listed host module compiles
     * through the real orchestrator pipeline (deal.json externals → host
     * declaration discovery → typing → JvmBackend host bindings) and the
     * emitted artifact runs against a real host implementation class —
     * declared export exposure, load-time presence checks (E8011 for a
     * missing export), the sync return boundary (E8010 for a wrong
     * runtime kind), the async operation shape and completion checks
     * (E8010/E8001), and the nullable/null boundary. */
    private static void testHostAbiSlice() throws Exception {
        System.out.println("-- Host ABI slice (ISSUE-0100) --");

        writeFile("deal.json", """
            {
              "languageVersion": "1.2",
              "moduleRoots": ["src"],
              "externals": {
                "host/log": { "declaration": "bindings/log.d.deal" }
              }
            }
            """);
        writeFile("bindings/log.d.deal", """
            export function info(level: int, s: string): null;
            export function add(a: int, b: int): int;
            export function find(s: string): string | null;
            export function value(): string;
            export async function fetch(): string;
            """);
        writeFile("src/entry.deal", """
            import * as console from "std/console"
            import * as log from "host/log"
            export function main(): null {
              log.info(1, "hello");
              let f: string | null = log.find("x");
              if (f === null) { console.log("null"); } else { console.log(f); }
              return null;
            }
            export function run(): int { return log.add(2, 3); }
            """);
        writeFile("HostLog.java", """
            import java.util.concurrent.CompletableFuture;
            public final class HostLog {
                public static Object info(long level, String s) { return null; }
                public static Object add(long a, long b) { return Long.valueOf(a + b); }
                public static Object find(String s) { return s; }
                public static Object value() { return "v"; }
                public static Object fetch() { return CompletableFuture.completedFuture("d"); }
            }
            """);

        Path entryFile = tmpDir.resolve("src/entry.deal").toAbsolutePath();
        Path outputDir = tmpDir.resolve("build/host_abi");
        List<Path> roots = List.of(tmpDir.resolve("src").toAbsolutePath());
        DealConfig config = DealConfig.load(tmpDir).config();
        check(config != null, "deal.json with externals loads");
        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            entryFile, outputDir, false, false, false, Backend.JVM,
            config, roots, Path.of(".").toAbsolutePath().normalize());
        boolean success = orchestrator.compile();
        check(success, "externals-listed host module compiles: "
            + orchestrator.diagnostics());
        Path entryArtifact = outputDir.resolve("Entry.java");
        check(Files.exists(entryArtifact), "entry artifact written");
        if (!Files.exists(entryArtifact)) return;

        String java = Files.readString(entryArtifact);
        check(java.contains("static java.lang.reflect.Method $host$log$add$m;"),
            "the declared export emits its cached Method field");
        check(java.contains("java.lang.Class.forName(\"HostLog\")"),
            "the load method loads the module-path-derived host class");
        check(java.contains("__hostMethod(__h, \"host/log\", \"add\", \"(int,int)->int\""),
            "the presence check carries the declared descriptor");
        check(java.contains("static long __host$log$add(long __a0, long __a1)"),
            "the wrapper signature maps the declared parameter types");
        check(java.contains("__hostCheck(\"int\", __r, \"host/log.add\", false)"),
            "the sync wrapper checks the return boundary (E8010 path)");
        check(java.contains("__hostCheck(\"string\", __v, \"host/log.fetch\", true)"),
            "the async wrapper checks the completion value (E8001 path)");
        check(java.contains(
                "__hasUnpairedSurrogate(s)) throw new DealError(completion ? \"E8001\" : \"E8010\""),
            "the host check's string branch runs the v1.2 unpaired-surrogate "
                + "scan (E8010 sync / E8001 completion)");
        check(java.contains("static {\n        __hostLoad$log();\n    }"),
            "the import statement emits the load-time presence-check block");

        // Real execution: javac over the artifacts + host class + runner,
        // then java. The host's load-time call, the nullable return, and
        // the exported add run end to end.
        Files.copy(tmpDir.resolve("HostLog.java"), outputDir.resolve("HostLog.java"));
        Files.writeString(outputDir.resolve("JvmConformanceRunner.java"),
            BackendConformanceTest.buildJvmRunner(
                parseProgram("""
                    export function main(): null { return null; }
                    export function run(): int { return 1; }
                    """), "Entry"));
        ProcessBuilder javac = new ProcessBuilder("javac", "-encoding", "UTF-8",
            "Entry.java", "HostLog.java", "JvmConformanceRunner.java");
        javac.directory(outputDir.toFile());
        javac.redirectErrorStream(true);
        Process p = javac.start();
        String javacOut = new String(p.getInputStream().readAllBytes()).trim();
        check(p.waitFor() == 0, "host artifacts compile with javac: " + javacOut);

        ProcessBuilder javaRun = new ProcessBuilder("java", "-cp",
            outputDir.toString(), "JvmConformanceRunner");
        javaRun.redirectErrorStream(true);
        Process p2 = javaRun.start();
        String out = new String(p2.getInputStream().readAllBytes()).trim();
        int exit = p2.waitFor();
        check(exit == 0, "host fixture runs: " + out);
        check(out.contains("x") && out.contains("5"),
            "the nullable host return and the exported add run: " + out);

        // Missing declared export: the host class lacks add → E8011 at
        // module load, raised by the load-time presence check.
        writeFile("HostLogMissing.java", """
            public final class HostLog {
                public static Object info(long level, String s) { return null; }
                public static Object find(String s) { return s; }
                public static Object value() { return "v"; }
                public static Object fetch() { return java.util.concurrent.CompletableFuture.completedFuture("d"); }
            }
            """);
        Path outputDir2 = tmpDir.resolve("build/host_abi_missing");
        CompilationOrchestrator orchestrator2 = new CompilationOrchestrator(
            entryFile, outputDir2, false, false, false, Backend.JVM,
            config, roots, Path.of(".").toAbsolutePath().normalize());
        check(orchestrator2.compile(), "missing-export project still compiles (the check is load-time): "
            + orchestrator2.diagnostics());
        Files.copy(tmpDir.resolve("HostLogMissing.java"),
            outputDir2.resolve("HostLog.java"));
        Files.writeString(outputDir2.resolve("JvmConformanceRunner.java"),
            BackendConformanceTest.buildJvmRunner(
                parseProgram("""
                    export function main(): null { return null; }
                    export function run(): int { return 1; }
                    """), "Entry"));
        ProcessBuilder javac2 = new ProcessBuilder("javac", "-encoding", "UTF-8",
            "Entry.java", "HostLog.java", "JvmConformanceRunner.java");
        javac2.directory(outputDir2.toFile());
        javac2.redirectErrorStream(true);
        Process p3 = javac2.start();
        String javacOut2 = new String(p3.getInputStream().readAllBytes()).trim();
        check(p3.waitFor() == 0, "missing-export artifacts compile with javac: " + javacOut2);
        ProcessBuilder javaRun2 = new ProcessBuilder("java", "-cp",
            outputDir2.toString(), "JvmConformanceRunner");
        javaRun2.redirectErrorStream(true);
        Process p4 = javaRun2.start();
        String out2 = new String(p4.getInputStream().readAllBytes()).trim();
        int exit2 = p4.waitFor();
        check(exit2 == 1, "missing declared export fails at load: " + out2);
        check(out2.contains("DEAL_ERROR_CODE: E8011"),
            "missing declared export reports E8011: " + out2);

        // Bad sync return: value() returns a Long for a declared string.
        writeFile("HostLogBad.java", """
            public final class HostLog {
                public static Object info(long level, String s) { return null; }
                public static Object add(long a, long b) { return Long.valueOf(a + b); }
                public static Object find(String s) { return s; }
                public static Object value() { return Long.valueOf(42L); }
                public static Object fetch() { return java.util.concurrent.CompletableFuture.completedFuture("d"); }
            }
            """);
        writeFile("src/entry_bad.deal", """
            import * as log from "host/log"
            export function main(): null { return null; }
            export function run(): string { return log.value(); }
            """);
        Path entryBad = tmpDir.resolve("src/entry_bad.deal").toAbsolutePath();
        Path outputDir3 = tmpDir.resolve("build/host_abi_bad_ret");
        CompilationOrchestrator orchestrator3 = new CompilationOrchestrator(
            entryBad, outputDir3, false, false, false, Backend.JVM,
            config, roots, Path.of(".").toAbsolutePath().normalize());
        check(orchestrator3.compile(), "bad-return project compiles: "
            + orchestrator3.diagnostics());
        Files.copy(tmpDir.resolve("HostLogBad.java"),
            outputDir3.resolve("HostLog.java"));
        Files.writeString(outputDir3.resolve("JvmConformanceRunner.java"),
            BackendConformanceTest.buildJvmRunner(
                parseProgram("""
                    export function main(): null { return null; }
                    export function run(): string { return "x"; }
                    """), "Entry_bad"));
        ProcessBuilder javac3 = new ProcessBuilder("javac", "-encoding", "UTF-8",
            "Entry_bad.java", "HostLog.java", "JvmConformanceRunner.java");
        javac3.directory(outputDir3.toFile());
        javac3.redirectErrorStream(true);
        Process p5 = javac3.start();
        String javacOut3 = new String(p5.getInputStream().readAllBytes()).trim();
        check(p5.waitFor() == 0, "bad-return artifacts compile with javac: " + javacOut3);
        ProcessBuilder javaRun3 = new ProcessBuilder("java", "-cp",
            outputDir3.toString(), "JvmConformanceRunner");
        javaRun3.redirectErrorStream(true);
        Process p6 = javaRun3.start();
        String out3 = new String(p6.getInputStream().readAllBytes()).trim();
        int exit3 = p6.waitFor();
        check(exit3 == 1, "wrong-kind host return fails: " + out3);
        check(out3.contains("DEAL_ERROR_CODE: E8010"),
            "wrong-kind host return reports E8010: " + out3);

        // ISSUE-0106 v1.2 boundary string validation at the host return
        // boundary: a sync string return carrying an unpaired UTF-16
        // surrogate (a lone high surrogate) is rejected with E8010 and
        // the seam's message — the host boundary is an ingress where an
        // invalid encoding can enter DEAL.
        writeFile("HostLogBadSurrogate.java", """
            public final class HostLog {
                public static Object info(long level, String s) { return null; }
                public static Object add(long a, long b) { return Long.valueOf(a + b); }
                public static Object find(String s) { return s; }
                public static Object value() { return "a" + (char) 0xD800 + "b"; }
                public static Object fetch() { return java.util.concurrent.CompletableFuture.completedFuture("d"); }
            }
            """);
        Path outputDirSur = tmpDir.resolve("build/host_abi_bad_surrogate");
        CompilationOrchestrator orchestratorSur = new CompilationOrchestrator(
            entryBad, outputDirSur, false, false, false, Backend.JVM,
            config, roots, Path.of(".").toAbsolutePath().normalize());
        check(orchestratorSur.compile(), "surrogate-return project compiles: "
            + orchestratorSur.diagnostics());
        Files.copy(tmpDir.resolve("HostLogBadSurrogate.java"),
            outputDirSur.resolve("HostLog.java"));
        Files.writeString(outputDirSur.resolve("JvmConformanceRunner.java"),
            BackendConformanceTest.buildJvmRunner(
                parseProgram("""
                    export function run(): string { return "x"; }
                    """), "Entry_bad"));
        ProcessBuilder javacSur = new ProcessBuilder("javac", "-encoding", "UTF-8",
            "Entry_bad.java", "HostLog.java", "JvmConformanceRunner.java");
        javacSur.directory(outputDirSur.toFile());
        javacSur.redirectErrorStream(true);
        Process pSur = javacSur.start();
        String javacOutSur = new String(pSur.getInputStream().readAllBytes()).trim();
        check(pSur.waitFor() == 0, "surrogate-return artifacts compile with javac: "
            + javacOutSur);
        ProcessBuilder javaRunSur = new ProcessBuilder("java", "-cp",
            outputDirSur.toString(), "JvmConformanceRunner");
        javaRunSur.redirectErrorStream(true);
        Process pSurRun = javaRunSur.start();
        String outSur = new String(pSurRun.getInputStream().readAllBytes()).trim();
        int exitSur = pSurRun.waitFor();
        check(exitSur == 1 && outSur.contains("DEAL_ERROR_CODE: E8010"),
            "unpaired-surrogate host return reports E8010: " + outSur);
        check(outSur.contains(
                "expected string, got string with unpaired surrogate code units"),
            "the sync boundary rejection carries the seam's message: " + outSur);

        // Async: an entry that awaits the host async export, with the
        // shape (E8010) and completion (E8001) failures.
        writeFile("src/entry_async.deal", """
            import * as log from "host/log"
            export function main(): null { return null; }
            export async function run(): string { return await log.fetch(); }
            """);
        Path entryAsync = tmpDir.resolve("src/entry_async.deal").toAbsolutePath();
        Path outputDir4 = tmpDir.resolve("build/host_abi_async_ok");
        CompilationOrchestrator orchestrator4 = new CompilationOrchestrator(
            entryAsync, outputDir4, false, false, false, Backend.JVM,
            config, roots, Path.of(".").toAbsolutePath().normalize());
        check(orchestrator4.compile(), "async host import compiles: "
            + orchestrator4.diagnostics());
        Files.copy(tmpDir.resolve("HostLog.java"),
            outputDir4.resolve("HostLog.java"));
        Files.writeString(outputDir4.resolve("JvmConformanceRunner.java"),
            BackendConformanceTest.buildJvmRunner(
                parseProgram("""
                    export function main(): null { return null; }
                    export async function run(): string { return "x"; }
                    """), "Entry_async"));
        ProcessBuilder javac4 = new ProcessBuilder("javac", "-encoding", "UTF-8",
            "Entry_async.java", "HostLog.java", "JvmConformanceRunner.java");
        javac4.directory(outputDir4.toFile());
        javac4.redirectErrorStream(true);
        Process p7 = javac4.start();
        String javacOut4 = new String(p7.getInputStream().readAllBytes()).trim();
        check(p7.waitFor() == 0, "async artifacts compile with javac: " + javacOut4);
        ProcessBuilder javaRun4 = new ProcessBuilder("java", "-cp",
            outputDir4.toString(), "JvmConformanceRunner");
        javaRun4.redirectErrorStream(true);
        Process p8 = javaRun4.start();
        String out4 = new String(p8.getInputStream().readAllBytes()).trim();
        int exit4 = p8.waitFor();
        check(exit4 == 0 && out4.contains("d"),
            "await joins the host async operation and checks the completion: "
                + out4);

        writeFile("HostLogAsyncShape.java", """
            public final class HostLog {
                public static Object info(long level, String s) { return null; }
                public static Object add(long a, long b) { return Long.valueOf(a + b); }
                public static Object find(String s) { return s; }
                public static Object value() { return "v"; }
                public static Object fetch() { return "not-an-operation"; }
            }
            """);
        Path outputDir5 = tmpDir.resolve("build/host_abi_async_shape");
        CompilationOrchestrator orchestrator5 = new CompilationOrchestrator(
            entryAsync, outputDir5, false, false, false, Backend.JVM,
            config, roots, Path.of(".").toAbsolutePath().normalize());
        check(orchestrator5.compile(), "async shape project compiles: "
            + orchestrator5.diagnostics());
        Files.copy(tmpDir.resolve("HostLogAsyncShape.java"),
            outputDir5.resolve("HostLog.java"));
        Files.writeString(outputDir5.resolve("JvmConformanceRunner.java"),
            BackendConformanceTest.buildJvmRunner(
                parseProgram("""
                    export function main(): null { return null; }
                    export async function run(): string { return "x"; }
                    """), "Entry_async"));
        ProcessBuilder javac5 = new ProcessBuilder("javac", "-encoding", "UTF-8",
            "Entry_async.java", "HostLog.java", "JvmConformanceRunner.java");
        javac5.directory(outputDir5.toFile());
        javac5.redirectErrorStream(true);
        Process p9 = javac5.start();
        String javacOut5 = new String(p9.getInputStream().readAllBytes()).trim();
        check(p9.waitFor() == 0, "async-shape artifacts compile with javac: " + javacOut5);
        ProcessBuilder javaRun5 = new ProcessBuilder("java", "-cp",
            outputDir5.toString(), "JvmConformanceRunner");
        javaRun5.redirectErrorStream(true);
        Process p10 = javaRun5.start();
        String out5 = new String(p10.getInputStream().readAllBytes()).trim();
        int exit5 = p10.waitFor();
        check(exit5 == 1 && out5.contains("DEAL_ERROR_CODE: E8010"),
            "non-operation host async return reports E8010: " + out5);

        writeFile("HostLogAsyncCompletion.java", """
            public final class HostLog {
                public static Object info(long level, String s) { return null; }
                public static Object add(long a, long b) { return Long.valueOf(a + b); }
                public static Object find(String s) { return s; }
                public static Object value() { return "v"; }
                public static Object fetch() { return java.util.concurrent.CompletableFuture.completedFuture(Long.valueOf(7L)); }
            }
            """);
        Path outputDir6 = tmpDir.resolve("build/host_abi_async_completion");
        CompilationOrchestrator orchestrator6 = new CompilationOrchestrator(
            entryAsync, outputDir6, false, false, false, Backend.JVM,
            config, roots, Path.of(".").toAbsolutePath().normalize());
        check(orchestrator6.compile(), "async completion project compiles: "
            + orchestrator6.diagnostics());
        Files.copy(tmpDir.resolve("HostLogAsyncCompletion.java"),
            outputDir6.resolve("HostLog.java"));
        Files.writeString(outputDir6.resolve("JvmConformanceRunner.java"),
            BackendConformanceTest.buildJvmRunner(
                parseProgram("""
                    export function main(): null { return null; }
                    export async function run(): string { return "x"; }
                    """), "Entry_async"));
        ProcessBuilder javac6 = new ProcessBuilder("javac", "-encoding", "UTF-8",
            "Entry_async.java", "HostLog.java", "JvmConformanceRunner.java");
        javac6.directory(outputDir6.toFile());
        javac6.redirectErrorStream(true);
        Process p11 = javac6.start();
        String javacOut6 = new String(p11.getInputStream().readAllBytes()).trim();
        check(p11.waitFor() == 0, "async-completion artifacts compile with javac: " + javacOut6);
        ProcessBuilder javaRun6 = new ProcessBuilder("java", "-cp",
            outputDir6.toString(), "JvmConformanceRunner");
        javaRun6.redirectErrorStream(true);
        Process p12 = javaRun6.start();
        String out6 = new String(p12.getInputStream().readAllBytes()).trim();
        int exit6 = p12.waitFor();
        check(exit6 == 1 && out6.contains("DEAL_ERROR_CODE: E8001"),
            "wrong completion value reports E8001 at the await site: " + out6);

        // ISSUE-0106: the same v1.2 boundary string validation applies to
        // async completion values — a lone low surrogate completes the
        // operation and the await site raises E8001 with the seam's
        // message (the completion check, not the sync E8010 path).
        writeFile("HostLogAsyncSurrogate.java", """
            public final class HostLog {
                public static Object info(long level, String s) { return null; }
                public static Object add(long a, long b) { return Long.valueOf(a + b); }
                public static Object find(String s) { return s; }
                public static Object value() { return "v"; }
                public static Object fetch() { return java.util.concurrent.CompletableFuture.completedFuture("a" + (char) 0xDC00 + "b"); }
            }
            """);
        Path outputDir7 = tmpDir.resolve("build/host_abi_async_surrogate");
        CompilationOrchestrator orchestrator7 = new CompilationOrchestrator(
            entryAsync, outputDir7, false, false, false, Backend.JVM,
            config, roots, Path.of(".").toAbsolutePath().normalize());
        check(orchestrator7.compile(), "async surrogate project compiles: "
            + orchestrator7.diagnostics());
        Files.copy(tmpDir.resolve("HostLogAsyncSurrogate.java"),
            outputDir7.resolve("HostLog.java"));
        Files.writeString(outputDir7.resolve("JvmConformanceRunner.java"),
            BackendConformanceTest.buildJvmRunner(
                parseProgram("""
                    export async function run(): string { return "x"; }
                    """), "Entry_async"));
        ProcessBuilder javac7 = new ProcessBuilder("javac", "-encoding", "UTF-8",
            "Entry_async.java", "HostLog.java", "JvmConformanceRunner.java");
        javac7.directory(outputDir7.toFile());
        javac7.redirectErrorStream(true);
        Process p13 = javac7.start();
        String javacOut7 = new String(p13.getInputStream().readAllBytes()).trim();
        check(p13.waitFor() == 0, "async-surrogate artifacts compile with javac: "
            + javacOut7);
        ProcessBuilder javaRun7 = new ProcessBuilder("java", "-cp",
            outputDir7.toString(), "JvmConformanceRunner");
        javaRun7.redirectErrorStream(true);
        Process p14 = javaRun7.start();
        String out7 = new String(p14.getInputStream().readAllBytes()).trim();
        int exit7 = p14.waitFor();
        check(exit7 == 1 && out7.contains("DEAL_ERROR_CODE: E8001"),
            "unpaired-surrogate async completion reports E8001 at the await site: "
                + out7);
        check(out7.contains(
                "expected string, got string with unpaired surrogate code units"),
            "the completion boundary rejection carries the seam's message: "
                + out7);
    }

    /** Parses a small DEAL snippet with the real lexer+parser for runner
     * construction (no type checking — the orchestrator already checked the
     * real module). */
    private static ProgramNode parseProgram(String source) {
        try {
            LexResult lex = new Lexer(source, "jvmtest-runner.deal").tokenize();
            if (lex.hasErrors()) throw new IllegalStateException("lex: " + lex.diagnostics());
            ParseResult parse = new Parser(lex.tokens(), "jvmtest-runner.deal").parse();
            if (parse.hasErrors()) throw new IllegalStateException("parse: " + parse.diagnostics());
            return parse.program();
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Multi-module compilation through the orchestrator's JVM path
     * (ISSUE-0096): the entry imports lib and calls its exported add()
     * through the alias; the emitted entry artifact carries the load-time
     * init trigger and the static cross-class call, and the artifacts run
     * with javac + java.
     */
    private static void testModuleImports() throws Exception {
        System.out.println("-- Orchestrator: multi-module import + imported direct call --");

        writeFile("src/lib.deal", """
            function double(x: int): int { return x * 2; }
            export function add(a: int, b: int): int { return double(a) + double(b); }
            """);
        writeFile("src/entry.deal", """
            import * as lib from "./lib"
            export function main(): null { return null; }
            export function run(): int { return lib.add(10, 20); }
            """);

        Path entryFile = tmpDir.resolve("src/entry.deal").toAbsolutePath();
        Path outputDir = tmpDir.resolve("build/imports");
        List<Path> roots = List.of(tmpDir.resolve("src").toAbsolutePath());

        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            entryFile, outputDir, false, false, false, Backend.JVM,
            (DealConfig) null, roots, Path.of(".").toAbsolutePath().normalize());

        boolean success = orchestrator.compile();
        check(success, "multi-module JVM compile succeeds: "
            + orchestrator.diagnostics());
        if (!success) return;

        Path entryArtifact = outputDir.resolve("Entry.java");
        Path libArtifact = outputDir.resolve("Lib.java");
        check(Files.exists(entryArtifact), "entry artifact written");
        check(Files.exists(libArtifact), "imported module artifact written");

        if (Files.exists(entryArtifact)) {
            String java = Files.readString(entryArtifact);
            check(java.contains("Lib.__init$();"),
                "the import emits the load-time init trigger for Lib");
            check(java.contains("return Lib.add(10L, 20L);"),
                "the imported direct call emits a static call on the "
                + "imported class: " + java);
        }

        ExecResult exec = runJvmArtifacts(outputDir,
            parseProgram("export function run(): int { return 0; }"), "Entry");
        check(exec.exitCode() == 0, "artifacts run with exit 0");
        check(exec.output().contains("60"),
            "imported call computes double(10) + double(20) = 60: "
                + exec.output());
    }

    /**
     * Module class-name isolation (ISSUE-0096): two same-basename modules
     * in different directories derive distinct artifact classes
     * ({@code a/calc → ACalc}, {@code b/calc → BCalc}), each with its own
     * same-named export, and the entry calls both through distinct aliases.
     */
    private static void testModuleClassIsolation() throws Exception {
        System.out.println("-- Orchestrator: module class-name isolation --");

        writeFile("src/a/calc.deal", """
            export function compute(): int { return 1; }
            """);
        writeFile("src/b/calc.deal", """
            export function compute(): int { return 2; }
            """);
        writeFile("src/entry.deal", """
            import * as aCalc from "./a/calc"
            import * as bCalc from "./b/calc"
            export function main(): null { return null; }
            export function run(): int { return aCalc.compute() * 10 + bCalc.compute(); }
            """);

        Path entryFile = tmpDir.resolve("src/entry.deal").toAbsolutePath();
        Path outputDir = tmpDir.resolve("build/isolation");
        List<Path> roots = List.of(tmpDir.resolve("src").toAbsolutePath());

        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            entryFile, outputDir, false, false, false, Backend.JVM,
            (DealConfig) null, roots, Path.of(".").toAbsolutePath().normalize());

        boolean success = orchestrator.compile();
        check(success, "class-isolation project compiles: "
            + orchestrator.diagnostics());
        if (!success) return;

        Path aArtifact = outputDir.resolve("ACalc.java");
        Path bArtifact = outputDir.resolve("BCalc.java");
        Path entryArtifact = outputDir.resolve("Entry.java");
        check(Files.exists(aArtifact), "ACalc.java written for a/calc");
        check(Files.exists(bArtifact), "BCalc.java written for b/calc");
        check(Files.exists(entryArtifact), "Entry.java written");
        if (Files.exists(aArtifact)) {
            check(Files.readString(aArtifact).contains("public final class ACalc"),
                "a/calc derives class ACalc");
        }
        if (Files.exists(bArtifact)) {
            check(Files.readString(bArtifact).contains("public final class BCalc"),
                "b/calc derives class BCalc");
        }

        ExecResult exec = runJvmArtifacts(outputDir,
            parseProgram("export function run(): int { return 0; }"), "Entry");
        check(exec.exitCode() == 0, "artifacts run with exit 0");
        check(exec.output().contains("12"),
            "isolated classes compute 1 * 10 + 2 = 12: " + exec.output());
    }

    /**
     * Sibling-import load-time ordering (ISSUE-0096): two project-module
     * imports initialize depth-first in import order (b-load, then c-load),
     * before the importing module's own load-time statements — exactly
     * where LuaJIT runs each require.
     */
    private static void testModuleUnusedImportLoadTime() throws Exception {
        System.out.println("-- Orchestrator: sibling imports run load-time code in import order --");

        // v1.2 module top level has no executable statements: the
        // observable ordering moves inside the exported functions.
        writeFile("src/b.deal", """
            export function plus(x: int): int { return x + 1; }
            """);
        writeFile("src/c.deal", """
            export function base(): int { return 10; }
            """);
        writeFile("src/entry.deal", """
            import * as b from "./b"
            import * as c from "./c"
            export function main(): null { return null; }
            export function run(): int { return b.plus(c.base()); }
            """);

        Path entryFile = tmpDir.resolve("src/entry.deal").toAbsolutePath();
        Path outputDir = tmpDir.resolve("build/sibling_imports");
        List<Path> roots = List.of(tmpDir.resolve("src").toAbsolutePath());

        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            entryFile, outputDir, false, false, false, Backend.JVM,
            (DealConfig) null, roots, Path.of(".").toAbsolutePath().normalize());

        boolean success = orchestrator.compile();
        check(success, "sibling-import project compiles: "
            + orchestrator.diagnostics());
        if (!success) return;

        ExecResult exec = runJvmArtifacts(outputDir,
            parseProgram("export function run(): int { return 0; }"), "Entry");
        check(exec.exitCode() == 0, "artifacts run with exit 0");
        check(!exec.output().contains("load"),
            "no load-time prints in v1.2 (module top level holds only "
            + "declarations): " + exec.output());
        check(exec.output().contains("11"),
            "b.plus(c.base()) = 11: " + exec.output());
    }

    /**
     * Backend-level emission checks for module imports (ISSUE-0096): without
     * an import resolution map the import stays E6000 (the pre-existing
     * rejection, unchanged for the no-map overloads); with the map the
     * import emits the load-time init trigger, the imported direct call
     * emits a static call on the imported class, and an imported
     * null-returning call hoists as a pre-statement.
     */
    private static void testModuleImportBackendEmission() {
        System.out.println("-- Module import codegen emission (ISSUE-0096) --");

        Map<String, Map<String, Type>> modules = Map.of(
            "./lib", Map.of("add", Types.func(
                List.of(Type.Int.INSTANCE, Type.Int.INSTANCE), Type.Int.INSTANCE)));
        Frontend f = compileFrontend("""
            import * as lib from "./lib"
            export function run(): int { return lib.add(10, 20); }
            """, "jvmtest-import.deal", new FixedModuleResolver(modules));
        if (!f.errors().isEmpty()) {
            fail("checker must accept the module-import probe: " + f.errors());
            return;
        }

        // Without an import resolution map the import is E6000 — the
        // imported module's require-time side effects can never be silently
        // dropped (the pre-existing no-map overloads keep this behavior).
        JvmBackend.JvmCodegenResult bare = JvmBackend.generate(
            f.program(), f.checkResult(), "jvmtest-import.deal", "main");
        check(bare.hasErrors(), "import without a resolution map is E6000");
        check(bare.diagnostics().stream().anyMatch(d -> d.message()
                .contains("project modules")),
            "the rejection names the supported import forms: "
                + bare.diagnostics());

        // With the map the import is accepted: the alias maps to the
        // imported module path, the call emits a static call on the
        // imported class, and the import statement emits the load-time
        // init trigger.
        JvmBackend.JvmCodegenResult res = JvmBackend.generate(
            f.program(), f.checkResult(), "jvmtest-import.deal", "main",
            Map.of("./lib", "lib"));
        check(!res.hasErrors(), "module import emits without E6000: "
            + res.diagnostics());
        check(res.source().contains("    static {\n        Lib.__init$();\n    }"),
            "the import emits the load-time init trigger for Lib");
        check(res.source().contains("return Lib.add(10L, 20L);"),
            "the imported direct call emits Lib.add(10L, 20L)");

        // An imported null-returning call is a void pre-statement.
        Map<String, Map<String, Type>> voidModules = Map.of(
            "./lib", Map.of("tick", Types.func(List.of(), Type.Null.INSTANCE)));
        Frontend voidF = compileFrontend("""
            import * as lib from "./lib"
            export function run(): null { lib.tick(); }
            """, "jvmtest-import-void.deal",
            new FixedModuleResolver(voidModules));
        if (!voidF.errors().isEmpty()) {
            fail("checker must accept the imported void-call probe: "
                + voidF.errors());
            return;
        }
        JvmBackend.JvmCodegenResult voidRes = JvmBackend.generate(
            voidF.program(), voidF.checkResult(), "jvmtest-import-void.deal",
            "main", Map.of("./lib", "lib"));
        check(!voidRes.hasErrors(), "imported void call emits without E6000: "
            + voidRes.diagnostics());
        check(voidRes.source().contains("Lib.tick();"),
            "the imported void call emits Lib.tick(); as a pre-statement");
    }

    /**
     * A module-level use of an import alias before its import statement is
     * E6000 (ISSUE-0096): LuaJIT emits the {@code require} at the import's
     * source position, so an earlier use reads the not-yet-required global
     * and fails at load — verified with real luajit for both shapes —
     * while Java would silently initialize the imported class. The
     * transitive shape (a module-level call of a function whose body uses
     * a later import) is included.
     */
    private static void testModuleImportUseBeforeImportRejected() {
        System.out.println("-- Module-level alias use before its import → E6000 --");

        Map<String, Map<String, Type>> modules = Map.of(
            "./lib", Map.of("value", Types.func(List.of(), Type.Int.INSTANCE)));

        // v1.2 grammar gate: every use-before-import shape rests on
        // module-level executable statements, which the v1.2 grammar
        // removes (E1049/E1048). The import-first trigger emission is
        // pinned by testModuleImportBackendEmission below.
        List<String> sources = List.of(
            """
            let base: int = lib.value();
            import * as lib from "./lib"
            export function run(): int { return base; }
            """,
            """
            if (lib.value() === 40) { }
            import * as lib from "./lib"
            export function run(): int { return 1; }
            """,
            """
            function f(): int { return lib.value(); }
            f();
            import * as lib from "./lib"
            export function run(): int { return 1; }
            """,
            """
            import * as lib from "./lib"
            let base: int = lib.value();
            export function run(): int { return base; }
            """,
            // indirect (ISSUE-0098): a module-level indirect call through a
            // function-typed field whose held function uses the alias, with
            // the import declared after the call site — the load-time
            // indirect-call guard applies the same import hazard check as
            // the direct-call path (LuaJIT reads the not-yet-required
            // global at load; Java would silently initialize the imported
            // class).
            """
            function f(): int { return lib.value(); }
            let g: () => int = f;
            let r: int = g();
            import * as lib from "./lib"
            export function run(): int { return r; }
            """);

        for (String source : sources) {
            Frontend f = compileFrontend(source, "jvmtest-import-order.deal",
                new FixedModuleResolver(modules));
            check(f.errors().stream().anyMatch(d ->
                    "E1049".equals(d.code()) || "E1048".equals(d.code())),
                "use-before-import shape rejected with E1049/E1048: "
                    + f.errors());
        }
    }

    /** Two modules whose paths differ only in case would derive the same
     * class name; the orchestrator reports an E6000 instead of silently
     * overwriting one module's artifact. */
    private static void testOrchestratorJvmClassCollision() throws Exception {
        System.out.println("-- Orchestrator: JVM class-name collision → E6000 --");

        writeFile("src/App.deal", """
            export function run(): int { return 1; }
            """);
        writeFile("src/app.deal", """
            export function run(): int { return 2; }
            """);
        writeFile("src/entry.deal", """
            import * as a from "./App"
            import * as b from "./app"
            export function main(): null { return null; }
            """);

        Path entryFile = tmpDir.resolve("src/entry.deal").toAbsolutePath();
        Path outputDir = tmpDir.resolve("build/collision");
        List<Path> roots = List.of(tmpDir.resolve("src").toAbsolutePath());

        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            entryFile, outputDir, false, false, false, Backend.JVM,
            (DealConfig) null, roots, Path.of(".").toAbsolutePath().normalize());

        boolean success = orchestrator.compile();
        check(!success, "class-name collision fails the JVM compile");
        check(orchestrator.diagnostics().stream()
                .anyMatch(d -> "E6000".equals(d.code())
                    && d.message().contains("both derive")),
            "orchestrator reports the class-name collision: " + orchestrator.diagnostics());

        // Anchorless site (D5/D6): the collision diagnostic carries the
        // canonical synthetic range plus a construct-naming note.
        CompilerDiagnostic collision = orchestrator.diagnostics().stream()
            .filter(d -> "E6000".equals(d.code())
                && d.message().contains("both derive"))
            .findFirst().orElse(null);
        check(collision != null, "collision E6000 present for the synthetic pin");
        if (collision != null) {
            check(collision.range().isCanonicalSynthetic()
                    && collision.range().origin() == RangeOrigin.SYNTHETIC,
                "collision E6000 range is canonical synthetic: "
                    + collision.range());
            check(collision.notes().stream().anyMatch(n -> n.message().contains(
                    "missing anchor: module class-name collision between '")),
                "collision E6000 note names the colliding modules: "
                    + collision.notes());
        }
    }

    /**
     * {@code --source-map} combined with {@code --backend jvm} produces no
     * sidecars; the orchestrator must print a warning instead of silently
     * ignoring the request (ISSUE-0091 rework round 3).
     */
    private static void testOrchestratorJvmSourceMapWarning() throws Exception {
        System.out.println("-- Orchestrator: JVM path warns on --source-map --");

        writeFile("src/sm_main.deal", """
            export function main(): null { return null; }
            export function run(): int { return 1; }
            """);

        Path entryFile = tmpDir.resolve("src/sm_main.deal").toAbsolutePath();
        Path outputDir = tmpDir.resolve("build/sm_jvm");
        List<Path> roots = List.of(tmpDir.resolve("src").toAbsolutePath());

        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            entryFile, outputDir, false, false, true, Backend.JVM,
            (DealConfig) null, roots, Path.of(".").toAbsolutePath().normalize());

        PrintStream originalErr = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        boolean success;
        try {
            System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
            success = orchestrator.compile();
        } finally {
            System.err.flush();
            System.setErr(originalErr);
        }
        check(success, "JVM compile with --source-map succeeds");
        String warning = captured.toString(StandardCharsets.UTF_8);
        check(warning.contains("source-map"),
            "warning printed when --source-map is requested on the JVM path: "
                + warning);
        check(Files.exists(outputDir.resolve("Sm_main.java")),
            "the .java artifact is still written");
        try (var stream = Files.walk(outputDir)) {
            check(stream.noneMatch(p -> p.toString().endsWith(".deal.map.json")),
                "no source-map sidecars under the JVM backend");
        }

        // --dump-ir derives the sourceMap flag internally (IR hardening
        // enables source maps with dumps) but is NOT an explicit
        // --source-map request: the warning must not fire.
        Path dumpIrOut = tmpDir.resolve("build/sm_dumpir");
        CompilationOrchestrator dumpIrOnly = new CompilationOrchestrator(
            entryFile, dumpIrOut, false, true, true, false, Backend.JVM,
            (DealConfig) null, roots, Path.of(".").toAbsolutePath().normalize());
        ByteArrayOutputStream capturedDumpIr = new ByteArrayOutputStream();
        boolean dumpIrSuccess;
        try {
            System.setErr(new PrintStream(capturedDumpIr, true, StandardCharsets.UTF_8));
            dumpIrSuccess = dumpIrOnly.compile();
        } finally {
            System.err.flush();
            System.setErr(originalErr);
        }
        check(dumpIrSuccess, "JVM compile with --dump-ir (no --source-map) succeeds");
        check(!capturedDumpIr.toString(StandardCharsets.UTF_8).contains("source-map"),
            "no source-map warning for a --dump-ir-derived sourceMap flag: "
                + capturedDumpIr.toString(StandardCharsets.UTF_8));
        check(Files.exists(dumpIrOut.resolve("Sm_main.java")),
            "the --dump-ir compile still writes the .java artifact");

        // Explicit --source-map together with --dump-ir still warns.
        CompilationOrchestrator both = new CompilationOrchestrator(
            entryFile, outputDir, false, true, true, true, Backend.JVM,
            (DealConfig) null, roots, Path.of(".").toAbsolutePath().normalize());
        ByteArrayOutputStream capturedBoth = new ByteArrayOutputStream();
        boolean bothSuccess;
        try {
            System.setErr(new PrintStream(capturedBoth, true, StandardCharsets.UTF_8));
            bothSuccess = both.compile();
        } finally {
            System.err.flush();
            System.setErr(originalErr);
        }
        check(bothSuccess, "JVM compile with --dump-ir --source-map succeeds");
        check(capturedBoth.toString(StandardCharsets.UTF_8).contains("source-map"),
            "--dump-ir --source-map still prints the warning: "
                + capturedBoth.toString(StandardCharsets.UTF_8));
    }

    private static void testDealConfigBackendField() {
        System.out.println("-- DealConfig backend field --");

        try {
            DealConfigParseResult r = DealConfig.parse(Path.of("deal.json"),
                "{\"backend\": \"jvm\"}");
            check(r.diagnostics().isEmpty(),
                "deal.json 'jvm' carries no diagnostics: " + r.diagnostics());
            check(r.config() != null && "jvm".equals(r.config().backend()),
                "deal.json accepts 'jvm'");
            DealConfigParseResult lua = DealConfig.parse(Path.of("deal.json"),
                "{\"backend\": \"luajit\"}");
            check(lua.diagnostics().isEmpty(),
                "deal.json 'luajit' carries no diagnostics");
            check(lua.config() != null && "luajit".equals(lua.config().backend()),
                "deal.json accepts 'luajit'");
            // ISSUE-0091 rework round 3: the CLI accepts --backend lua as a
            // LuaJIT alias; deal.json must accept the same name.
            DealConfigParseResult alias = DealConfig.parse(Path.of("deal.json"),
                "{\"backend\": \"lua\"}");
            check(alias.diagnostics().isEmpty(),
                "deal.json 'lua' carries no diagnostics");
            check(alias.config() != null && "lua".equals(alias.config().backend()),
                "deal.json accepts 'lua' alias");
            // Case-insensitive spellings, mirroring Backend.fromCliName: the
            // CLI accepts --backend JVM / Lua, so the manifest must accept
            // the same spellings (round-8 review flaw).
            DealConfigParseResult upper = DealConfig.parse(Path.of("deal.json"),
                "{\"backend\": \"JVM\"}");
            check(upper.diagnostics().isEmpty(),
                "deal.json 'JVM' carries no diagnostics");
            check(upper.config() != null && "JVM".equals(upper.config().backend()),
                "deal.json accepts 'JVM'");
            DealConfigParseResult mixed = DealConfig.parse(Path.of("deal.json"),
                "{\"backend\": \"  Lua \"}");
            check(mixed.diagnostics().isEmpty(),
                "deal.json ' Lua ' carries no diagnostics");
            check(mixed.config() != null
                    && "  Lua ".equals(mixed.config().backend()),
                "deal.json accepts ' Lua ' with surrounding whitespace");
        } catch (Exception e) {
            fail("DealConfig jvm parse: " + e.getMessage());
        }

        // Unknown backends are a ranged E2012 naming the supported values.
        DealConfigParseResult bad = DealConfig.parse(Path.of("deal.json"),
            "{\"backend\": \"wasm\"}");
        check(bad.config() == null, "unsupported backend yields no config");
        check(bad.diagnostics().size() == 1,
            "unsupported backend yields exactly one diagnostic: " + bad.diagnostics());
        if (bad.diagnostics().size() == 1) {
            CompilerDiagnostic d = bad.diagnostics().get(0);
            check("E2012".equals(d.code()) && "error".equals(d.severity()),
                "unsupported backend is an E2012 error: " + d);
            check(d.message().contains("luajit")
                    && d.message().contains("jvm"),
                "error message names supported backends: " + d.message());
            check(d.range().origin() == RangeOrigin.SOURCE
                    && "deal.json".equals(d.range().file()),
                "backend E2012 is SOURCE-anchored at the manifest path: " + d.range());
        }

        // End to end: a deal.json "backend": "lua" selects LuaJIT through the
        // CLI exactly like the --backend lua flag. (The manifest lives in
        // the entry file's directory — that is where Main.load looks.)
        try {
            writeFile("lua_proj/src/deal.json", "{\"backend\": \"lua\"}");
            writeFile("lua_proj/src/lua_alias_main.deal",
                "export function main(): null { return null; }\n"
                + "export function run(): null {}");
            Path luaEntry = tmpDir.resolve("lua_proj/src/lua_alias_main.deal")
                .toAbsolutePath();
            Path luaOut = tmpDir.resolve("build/lua_alias");
            int rc = deal.Main.run(new String[] {
                "compile", luaEntry.toString(),
                "--output", luaOut.toString()});
            check(rc == 0, "deal.json backend 'lua' compiles");
            check(Files.exists(luaOut.resolve("lua_alias_main.lua")),
                "deal.json 'lua' emits the .lua artifact");
            check(!Files.exists(luaOut.resolve("Lua_alias_main.java")),
                "deal.json 'lua' emits no .java artifact");
        } catch (IOException e) {
            fail("CLI 'lua' alias test IO: " + e.getMessage());
        }

        // End to end: a deal.json "backend": "JVM" (uppercase — the
        // case-insensitive manifest spelling) selects the JVM backend
        // through the CLI exactly like --backend jvm. (The manifest lives
        // in the entry file's directory — that is where Main.load looks.)
        try {
            writeFile("jvm_proj/src/deal.json", "{\"backend\": \"JVM\"}");
            writeFile("jvm_proj/src/jvm_alias_main.deal",
                "export function main(): null { return null; }\n"
                + "export function run(): int { return 6 * 7; }");
            Path jvmEntry = tmpDir.resolve("jvm_proj/src/jvm_alias_main.deal")
                .toAbsolutePath();
            Path jvmOut = tmpDir.resolve("build/jvm_alias");
            int rc = deal.Main.run(new String[] {
                "compile", jvmEntry.toString(),
                "--output", jvmOut.toString()});
            check(rc == 0, "deal.json backend 'JVM' compiles");
            check(Files.exists(jvmOut.resolve("Jvm_alias_main.java")),
                "deal.json 'JVM' emits the .java artifact");
            check(!Files.exists(jvmOut.resolve("jvm_alias_main.lua")),
                "deal.json 'JVM' emits no .lua artifact");
        } catch (IOException e) {
            fail("CLI 'JVM' alias test IO: " + e.getMessage());
        }
    }

    private static void testCliBackendFlag() {
        System.out.println("-- CLI --backend flag --");

        try {
            writeFile("src/cli_main.deal", """
                import * as console from "std/console"
                export function main(): null { return null; }
                export function run(): int {
                  console.log("cli-jvm");
                  return 6 * 7;
                }
                """);

            Path entry = tmpDir.resolve("src/cli_main.deal").toAbsolutePath();
            Path outDir = tmpDir.resolve("build/cli_jvm");

            int rc = deal.Main.run(new String[] {
                "compile", entry.toString(),
                "--output", outDir.toString(),
                "--backend", "jvm"});
            check(rc == 0, "CLI --backend jvm exits 0");
            check(Files.exists(outDir.resolve("Cli_main.java")),
                "CLI emitted the .java artifact");
            check(!Files.exists(outDir.resolve("cli_main.lua")),
                "CLI emitted no .lua artifact for jvm");

            int rcBad = deal.Main.run(new String[] {
                "compile", entry.toString(),
                "--output", outDir.toString(),
                "--backend", "wasm"});
            check(rcBad == 1, "CLI rejects unknown backend");

            // Default (no flag, no manifest backend field) stays LuaJIT.
            Path outLua = tmpDir.resolve("build/cli_default");
            int rcDefault = deal.Main.run(new String[] {
                "compile", entry.toString(),
                "--output", outLua.toString()});
            check(rcDefault == 0, "CLI default backend compiles");
            check(Files.exists(outLua.resolve("cli_main.lua")),
                "CLI default backend emits .lua");

            // --dump-ir derives the sourceMap flag internally (IR hardening
            // enables source maps with dumps); the JVM source-map warning
            // must fire only for an explicit --source-map request.
            PrintStream originalErr = System.err;
            ByteArrayOutputStream capturedDump = new ByteArrayOutputStream();
            try {
                System.setErr(new PrintStream(capturedDump, true, StandardCharsets.UTF_8));
                int rcDump = deal.Main.run(new String[] {
                    "compile", entry.toString(),
                    "--output", outDir.toString(),
                    "--backend", "jvm", "--dump-ir"});
                check(rcDump == 0, "CLI --backend jvm --dump-ir exits 0");
            } finally {
                System.err.flush();
                System.setErr(originalErr);
            }
            check(!capturedDump.toString(StandardCharsets.UTF_8).contains("source-map"),
                "--dump-ir alone prints no JVM source-map warning: "
                    + capturedDump.toString(StandardCharsets.UTF_8));

            ByteArrayOutputStream capturedBoth = new ByteArrayOutputStream();
            try {
                System.setErr(new PrintStream(capturedBoth, true, StandardCharsets.UTF_8));
                int rcBoth = deal.Main.run(new String[] {
                    "compile", entry.toString(),
                    "--output", outDir.toString(),
                    "--backend", "jvm", "--dump-ir", "--source-map"});
                check(rcBoth == 0, "CLI --backend jvm --dump-ir --source-map exits 0");
            } finally {
                System.err.flush();
                System.setErr(originalErr);
            }
            check(capturedBoth.toString(StandardCharsets.UTF_8).contains("source-map"),
                "--dump-ir --source-map prints the JVM source-map warning: "
                    + capturedBoth.toString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            fail("CLI backend test IO: " + e.getMessage());
        }
    }

    /**
     * Fixture-schema validation (conformance-test-architecture D6): a
     * fixture combining expectedCompileError with runtime or IR assertions
     * would silently drop those assertions (the compile-error gate returns
     * before runtime/IR dispatch), so the harness must fail the fixture
     * with a clear message instead of passing without running its checks.
     */
    private static void testFixtureConfigValidation() {
        System.out.println("-- Fixture config validation --");

        Map<String, Object> base = new LinkedHashMap<>();
        base.put("expectedCompileError", "E3001");

        Map<String, Object> withOutput = new LinkedHashMap<>(base);
        withOutput.put("expectedOutput", "x");
        check(BackendConformanceTest.fixtureConfigViolation(withOutput) != null,
            "expectedCompileError + expectedOutput is a violation");

        Map<String, Object> withError = new LinkedHashMap<>(base);
        withError.put("expectedError", "E8001");
        check(BackendConformanceTest.fixtureConfigViolation(withError) != null,
            "expectedCompileError + expectedError is a violation");

        Map<String, Object> withExit = new LinkedHashMap<>(base);
        withExit.put("expectedExitCode", 1);
        check(BackendConformanceTest.fixtureConfigViolation(withExit) != null,
            "expectedCompileError + expectedExitCode is a violation");

        Map<String, Object> withIr = new LinkedHashMap<>(base);
        withIr.put("irContains", List.of("function test"));
        check(BackendConformanceTest.fixtureConfigViolation(withIr) != null,
            "expectedCompileError + irContains is a violation");

        Map<String, Object> withIrNot = new LinkedHashMap<>(base);
        withIrNot.put("irNotContains", List.of("function test"));
        check(BackendConformanceTest.fixtureConfigViolation(withIrNot) != null,
            "expectedCompileError + irNotContains is a violation");

        // The valid configuration (all other fields null/empty) is clean.
        Map<String, Object> valid = new LinkedHashMap<>(base);
        valid.put("expectedOutput", null);
        valid.put("expectedError", null);
        valid.put("expectedExitCode", null);
        valid.put("irContains", List.of());
        valid.put("irNotContains", List.of());
        check(BackendConformanceTest.fixtureConfigViolation(valid) == null,
            "compile-error fixture with null/empty assertions is valid");

        // Runtime fixtures without expectedCompileError are valid whatever
        // they assert.
        Map<String, Object> runtime = new LinkedHashMap<>();
        runtime.put("expectedOutput", "x");
        runtime.put("expectedExitCode", 0);
        check(BackendConformanceTest.fixtureConfigViolation(runtime) == null,
            "runtime fixture without expectedCompileError is valid");
    }
}
