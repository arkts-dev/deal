package deal.test;

import deal.ast.ProgramNode;
import deal.checker.CheckResult;
import deal.checker.NameResolver;
import deal.checker.SymbolTable;
import deal.checker.TypeChecker;
import deal.codegen.Backend;
import deal.codegen.jvm.JvmBackend;
import deal.lexer.Diagnostic;
import deal.lexer.LexResult;
import deal.lexer.Lexer;
import deal.module.CompilationOrchestrator;
import deal.module.DealConfig;
import deal.parser.ParseResult;
import deal.parser.Parser;

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

/**
 * Unit tests for the JVM backend skeleton (ISSUE-0091), the first
 * semantic slice (ISSUE-0092 — while loops and template literals), and
 * the functions/direct-calls slice (ISSUE-0093 — parameter shadowing
 * against module fields and other visible bindings):
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
 *   <li>E6000 rejection of out-of-scope constructs (classes, arrays, tables,
 *       loops, async, non-console imports — at the import statement itself,
 *       even when unused —, module-level returns, use-before-declaration,
 *       runtime-helper name collisions),</li>
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
 *   <li>the backend-selection seam: {@code CompilationOrchestrator} with
 *       {@code Backend.JVM} emits and compiles a real {@code .java} artifact,
 *       rejects out-of-scope projects with E6000, detects class-name
 *       collisions, the default stays LuaJIT, {@code DealConfig} and the CLI
 *       accept {@code jvm}.</li>
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
            testWhileLoopModuleFieldDominanceGuards();
            testWhileModuleLevelReturnRejected();
            testWhileUseBeforeDeclarationRejected();
            testLoopCondHelperCollision();
            testTemplateLiterals();
            testNullReturnSideEffects();
            testNullTypedInitializers();
            testNullTypedCapturesWithReassignment();
            testNullEquality();
            testStandaloneExpressionStatements();
            testNumberModStringAndStderrRuntime();
            testModuleLevelStatements();
            testShadowedInitializer();
            testParameterShadowing();
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
            testOrchestratorJvmImportRejected();
            testOrchestratorJvmClassCollision();
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
        List<Diagnostic> errors = new ArrayList<>();

        LexResult lex = new Lexer(source, filename).tokenize();
        for (Diagnostic d : lex.diagnostics()) {
            if ("error".equals(d.severity())) errors.add(d);
        }
        if (lex.hasErrors()) {
            return new Frontend(null, null, errors);
        }

        Parser parser = new Parser(lex.tokens(), filename);
        ParseResult parse = parser.parse();
        for (Diagnostic d : parse.diagnostics()) {
            if ("error".equals(d.severity())) errors.add(d);
        }
        if (parse.hasErrors()) {
            return new Frontend(null, null, errors);
        }

        BackendConformanceTest.StubModuleResolver resolver =
            new BackendConformanceTest.StubModuleResolver();
        NameResolver nr = new NameResolver(filename, resolver);
        SymbolTable symTable;
        try {
            symTable = nr.resolve(parse.program());
        } catch (Exception e) {
            errors.add(Diagnostic.error("E9999", e.getMessage(), filename, 1, 1));
            return new Frontend(null, null, errors);
        }
        for (Diagnostic d : nr.diagnostics()) {
            if ("error".equals(d.severity())) errors.add(d);
        }

        CheckResult result = TypeChecker.check(filename, symTable, nr, parse.program());
        for (Diagnostic d : result.diagnostics()) {
            if ("error".equals(d.severity())) errors.add(d);
        }

        return new Frontend(parse.program(), result, errors);
    }

    private record ExecResult(String output, int exitCode) {}

    /**
     * Compiles the generated artifact with javac and executes it with java in
     * subprocesses — the same contract the conformance adapter enforces.
     */
    private static ExecResult compileAndRunJvm(String source, String name)
            throws Exception {
        Frontend f = compileFrontend(source, "jvmtest-" + name + ".deal");
        if (!f.errors().isEmpty()) {
            throw new RuntimeException("frontend errors: " + f.errors());
        }
        JvmBackend.JvmCodegenResult res =
            JvmBackend.generate(f.program(), f.checkResult(), name, "Main");
        if (res.hasErrors()) {
            throw new RuntimeException("codegen errors: " + res.diagnostics());
        }

        Path dir = Files.createTempDirectory("jvmtest_run_");
        Files.writeString(dir.resolve("Main.java"), res.source());
        Files.writeString(dir.resolve("JvmConformanceRunner.java"),
            BackendConformanceTest.buildJvmRunner(f.program(), "Main"));

        ProcessBuilder javac = new ProcessBuilder("javac", "-encoding", "UTF-8",
            "Main.java", "JvmConformanceRunner.java");
        javac.directory(dir.toFile());
        javac.redirectErrorStream(true);
        Process p1 = javac.start();
        String javacOut = new String(p1.getInputStream().readAllBytes()).trim();
        int javacExit = p1.waitFor();
        if (javacExit != 0) {
            throw new RuntimeException("javac failed: " + javacOut);
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

            let counter: int = 7;

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
        check(java.contains("static long counter = 7L;"),
            "module-level let → static field");
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
        check(!java.contains("->"),
            "emitted code contains no lambdas (capture-safety guarantee)");
        check(!java.contains("__rt"), "no Lua runtime references");
    }

    private static void testUnsupportedConstructsRejected() {
        System.out.println("-- Unsupported constructs → E6000 --");

        record Case(String what, String source) {}
        List<Case> cases = List.of(
            new Case("class declaration", """
                export class Point {
                  x: int;
                }
                export function test(): int { return 1; }
                """),
            new Case("array literal and indexing", """
                export function test(): int {
                  let xs: int[] = [1, 2];
                  return xs[0];
                }
                """),
            new Case("table-typed value", """
                export function test(): null {
                  let t: table = {};
                  return;
                }
                """),
            new Case("async function", """
                export async function test(): int { return 5; }
                """),
            new Case("non-console module import", """
                import * as s from "std/string"
                export function test(): int { return s.length("ab"); }
                """),
            new Case("unused non-console module import", """
                import * as m from "./other"
                export function test(): int { return 1; }
                """),
            new Case("nullable type", """
                export function test(): null {
                  let n: int | null = null;
                  return;
                }
                """),
            new Case("throw", """
                export function test(): null {
                  throw { code: "E_TEST", message: "x" };
                  return;
                }
                """),
            new Case("module-level return", """
                export function test(): int { return 1; }
                return 1;
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
            new Case("forward reference to module field", """
                let b: int = c + 1;
                let c: int = 2;
                export function test(): int { return b; }
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

        // A module-level while runs at load time: the field starts at 0,
        // the loop ticks twice, and the export observes 2.
        ExecResult moduleWhile = compileAndRunJvm("""
            import * as console from "std/console"
            function tick(): null { console.log("tick"); }
            let i: int = 0;
            while (i < 2 && tick() === null) {
              i = i + 1;
            }
            export function test(): int { return i; }
            """, "whilemodule");
        check(moduleWhile.exitCode() == 0, "module-level while exits 0");
        check(occurrences(moduleWhile.output(), "tick") == 2,
            "module-level while condition re-evaluates per iteration → tick ×2: "
                + moduleWhile.output());
        check(moduleWhile.output().contains("2"),
            "module-level while leaves the field at 2: " + moduleWhile.output());

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
                check(!res.source().contains("->"),
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

    /** Function-body module-field dominance guards walk through while
     * bodies: writes inside a loop body dominate nothing after the loop
     * (the body may run zero times), reads/writes of later-declared
     * fields in a condition or body stay E6000, and the field-declared-
     * first shape stays full parity. */
    private static void testWhileLoopModuleFieldDominanceGuards() throws Exception {
        System.out.println("-- While loops in the module-field dominance guards --");

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
        check(writeInBody.errors().isEmpty(),
            "while-body write shape frontend clean: " + writeInBody.errors());
        if (writeInBody.errors().isEmpty()) {
            JvmBackend.JvmCodegenResult res = JvmBackend.generate(
                writeInBody.program(), writeInBody.checkResult(),
                "jvmtest-while-dominance-write.deal", "main");
            check(res.hasErrors() && res.diagnostics().stream()
                    .anyMatch(d -> "E6000".equals(d.code())),
                "while-body write to a later field is E6000: " + res.diagnostics());
        }

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
        check(readInCond.errors().isEmpty(),
            "while-condition read shape frontend clean: " + readInCond.errors());
        if (readInCond.errors().isEmpty()) {
            JvmBackend.JvmCodegenResult res = JvmBackend.generate(
                readInCond.program(), readInCond.checkResult(),
                "jvmtest-while-dominance-read.deal", "main");
            check(res.hasErrors() && res.diagnostics().stream()
                    .anyMatch(d -> "E6000".equals(d.code())),
                "while-condition read of a later field is E6000: " + res.diagnostics());
        }

        // The positive shape: field declared BEFORE the function — the
        // module-local upvalue read with full parity, exercised through a
        // real while loop.
        ExecResult parity = compileAndRunJvm("""
            let x: int = 2;
            function h(): int {
              let i: int = 0;
              while (i < x) {
                i = i + 1;
              }
              return i;
            }
            export function test(): int { return h(); }
            """, "whiledomparity");
        check(parity.exitCode() == 0, "declared-first while parity exits 0");
        check(parity.output().contains("2"),
            "declared-first field read in a while condition → 2: " + parity.output());
    }

    /** Module-level while bodies cannot contain return (Java initializers
     * cannot return) — E6000, never an artifact javac rejects. */
    private static void testWhileModuleLevelReturnRejected() {
        System.out.println("-- Module-level return inside a while body → E6000 --");

        Frontend f = compileFrontend("""
            export function test(): int { return 1; }
            while (true) {
              if (false) {
                return;
              }
            }
            """, "jvmtest-while-module-return.deal");
        check(f.errors().isEmpty(), "module-while-return frontend clean: " + f.errors());
        if (f.errors().isEmpty()) {
            JvmBackend.JvmCodegenResult res = JvmBackend.generate(
                f.program(), f.checkResult(),
                "jvmtest-while-module-return.deal", "main");
            check(res.hasErrors() && res.diagnostics().stream()
                    .anyMatch(d -> "E6000".equals(d.code())),
                "module-level return inside a while body is E6000: " + res.diagnostics());
        }
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

        // A module-level null-typed field initializer hoists its side effect
        // into a static block before the field declaration (valid Java).
        ExecResult moduleField = compileAndRunJvm("""
            import * as console from "std/console"
            let z: null = console.log("module-null-field");
            export function test(): int { return 1; }
            """, "nullmodulefield");
        check(moduleField.exitCode() == 0, "module-level null field exits 0");
        check(moduleField.output().contains("module-null-field"),
            "module-level null field initializer runs: " + moduleField.output());
        check(moduleField.output().contains("1"),
            "module-level null field module still works: " + moduleField.output());

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
                check(!res.source().contains("->"),
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
        System.out.println("-- Module-level statements (static initializers) --");

        Frontend f = compileFrontend("""
            import * as console from "std/console"
            let x: int = 1;
            console.log("module-if-ran");
            if (x === 1) { console.log("module-if-true"); }
            x = 2;
            export function test(): int { return x; }
            """, "jvmtest-modstmts.deal");
        check(f.errors().isEmpty(), "module-level statements frontend clean");
        if (f.errors().isEmpty()) {
            JvmBackend.JvmCodegenResult res = JvmBackend.generate(
                f.program(), f.checkResult(), "jvmtest-modstmts.deal", "main");
            check(!res.hasErrors(), "module-level statements codegen clean: "
                + res.diagnostics());
            if (!res.hasErrors()) {
                String java = res.source();
                check(java.contains("static {"), "static initializer emitted");
                check(java.contains("    java.lang.System.out.println(\"module-if-ran\");"),
                    "module-level call inside the static block");
                check(java.contains("    if ((x == 1L)) {"),
                    "module-level if inside the static block");
                check(java.contains("    x = 2L;"),
                    "module-level assignment inside the static block");
                check(java.contains("static long x = 1L;"),
                    "module field declared as a class member");
            }
        }

        ExecResult exec = compileAndRunJvm("""
            import * as console from "std/console"
            let x: int = 1;
            console.log("module-if-ran");
            if (x === 1) { console.log("module-if-true"); }
            x = 2;
            export function test(): int { return x; }
            """, "modstmts");
        check(exec.exitCode() == 0, "module-level statements exit 0");
        check(exec.output().contains("module-if-ran"),
            "module-level console.log ran at class init: " + exec.output());
        check(exec.output().contains("module-if-true"),
            "module-level if ran at class init: " + exec.output());
        check(exec.output().contains("2"),
            "module-level assignment visible to exported function: " + exec.output());

        // Interleaved ordering: side-effecting field initializers and
        // module-level statements must run in source order. f/g are
        // declared BEFORE their call sites — LuaJIT assigns each function
        // value at its declaration point, so the old shape (fields calling
        // f/g before the declarations) fails at load under LuaJIT and is
        // rejected with E6000 by the backend (see
        // testModuleLevelCallBeforeFunctionDeclarationRejected); declaring
        // them first makes this shape load-time-parity with LuaJIT.
        ExecResult order = compileAndRunJvm("""
            import * as console from "std/console"
            function f(): int { console.log("f-ran"); return 1; }
            function g(): int { console.log("g-ran"); return 2; }
            let a: int = f();
            console.log("mid");
            let b: int = g();
            export function test(): int { return a + b; }
            """, "modorder");
        check(order.exitCode() == 0, "interleaved module order exits 0");
        check(order.output().contains("f-ran"), "first field initializer ran");
        check(order.output().contains("mid"), "module statement ran between fields");
        check(order.output().contains("g-ran"), "second field initializer ran");
        check(order.output().indexOf("f-ran") < order.output().indexOf("mid"),
            "f-ran precedes mid: " + order.output());
        check(order.output().indexOf("mid") < order.output().indexOf("g-ran"),
            "mid precedes g-ran: " + order.output());
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

        // Same pattern at module scope: a function-local shadow reads the
        // module field.
        ExecResult fieldShadow = compileAndRunJvm("""
            let counter: int = 5;
            export function test(): int {
              let counter: int = counter + 1;
              return counter;
            }
            """, "fieldshadow");
        check(fieldShadow.exitCode() == 0, "field shadow exits 0");
        check(fieldShadow.output().contains("6"),
            "field shadow computes 6 from the module field: " + fieldShadow.output());
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
        System.out.println("-- Parameter shadowing (ISSUE-0093) --");

        // A parameter shadowing a module field: the body must read the
        // parameter, not the field, and the signature/body names must match.
        Frontend f = compileFrontend("""
            let x: int = 1;
            function f(x: int): int { return x + 1; }
            export function test(): int { return f(5); }
            """, "jvmtest-paramshadow.deal");
        if (!f.errors().isEmpty()) {
            fail("checker must accept the parameter-shadow probe: " + f.errors());
            return;
        }
        JvmBackend.JvmCodegenResult res = JvmBackend.generate(
            f.program(), f.checkResult(), "jvmtest-paramshadow.deal", "main");
        check(!res.hasErrors(), "parameter shadow emits without E6000");
        check(res.source().contains("static long f(long x$1)"),
            "signature uses the declared (disambiguated) parameter name: "
                + res.source());
        check(res.source().contains("intAdd(x$1, 1L)"),
            "body reads the same declared parameter name");

        ExecResult exec = compileAndRunJvm("""
            let x: int = 1;
            function f(x: int): int { return x + 1; }
            export function test(): int { return f(5); }
            """, "paramshadow");
        check(exec.exitCode() == 0, "parameter shadow exits 0");
        check(exec.output().contains("6"),
            "parameter shadow computes 6 from the parameter: " + exec.output());

        // A let shadowing a parameter that itself shadows a module field:
        // the let's initializer binds to the parameter (LuaJIT's
        // `local g = g + 10` reads the outer binding → 15), and the module
        // field keeps its own value (1) → 16.
        ExecResult triple = compileAndRunJvm("""
            let g: int = 1;
            function f(g: int): int {
              let g: int = g + 10;
              return g;
            }
            export function test(): int { return f(5) + g; }
            """, "tripleshadow");
        check(triple.exitCode() == 0, "triple shadow exits 0");
        check(triple.output().contains("16"),
            "let-shadow-parameter-shadow-field computes 15 + field 1 = 16: "
                + triple.output());

        // Double-nested local shadowing: each level reads the nearest
        // enclosing binding ({@code x$2 = x$1 + 1} = 3).
        ExecResult nested = compileAndRunJvm("""
            let x: int = 1;
            export function test(): int {
              { let x: int = 2; { let x: int = x + 1; return x; } }
            }
            """, "nestedshadow");
        check(nested.exitCode() == 0, "nested shadow exits 0");
        check(nested.output().contains("3"),
            "double-nested shadow computes 3: " + nested.output());

        // ISSUE-0093 rework regression: the triple-deep shadow chain
        // (field → parameter → body-top let → inner-block let). The
        // body-top let OVERWRITES the parameter's scope-map key (they
        // share the function scope), so the parameter's emitted name —
        // still in Java scope per JLS §6.4 (an inner block may not
        // redeclare a method parameter) — must stay reserved for
        // collision purposes. It used to be lost with the overwrite: the
        // inner block then re-emitted `long g$1` over the parameter's
        // `long g$1` and javac rejected the artifact after the CLI
        // reported success ("variable g$1 is already defined in method
        // f(long)"). The backend now keeps a per-function set of every
        // emitted binding name (parameters + locals), so every binding in
        // the chain gets a distinct name.
        Frontend chain = compileFrontend("""
            let g: int = 1;
            function f(g: int): int {
              let g: int = g + 10;
              { let g: int = g + 1; }
              return g;
            }
            export function test(): int { return f(5) + g; }
            """, "jvmtest-shadowchain.deal");
        if (!chain.errors().isEmpty()) {
            fail("checker must accept the triple-shadow chain probe: "
                + chain.errors());
            return;
        }
        JvmBackend.JvmCodegenResult chainRes = JvmBackend.generate(
            chain.program(), chain.checkResult(), "jvmtest-shadowchain.deal",
            "main");
        check(!chainRes.hasErrors(), "triple-shadow chain emits without E6000");
        check(chainRes.source().contains("static long f(long g$1)"),
            "chain: signature keeps the parameter's disambiguated name");
        check(chainRes.source().contains("long g$2 = intAdd(g$1, 10L);"),
            "chain: body-top let takes the next free name and reads the parameter");
        check(chainRes.source().contains("long g$3 = intAdd(g$2, 1L);"),
            "chain: inner-block let never reuses the parameter's name "
                + "(g$3, not g$1)");
        check(chainRes.source().contains("return g$2;"),
            "chain: trailing read resolves to the body-top let");

        ExecResult chainRun = compileAndRunJvm("""
            let g: int = 1;
            function f(g: int): int {
              let g: int = g + 10;
              { let g: int = g + 1; }
              return g;
            }
            export function test(): int { return f(5) + g; }
            """, "shadowchain");
        check(chainRun.exitCode() == 0, "triple-shadow chain exits 0");
        check(chainRun.output().contains("16"),
            "triple-shadow chain computes 15 + field 1 = 16: "
                + chainRun.output());

        // The no-field variant pins the same defect class without any
        // module field: the body-top let overwrites the parameter's key
        // again, and the inner block used to emit `long g` over the
        // parameter's `long g` — the same javac rejection. Every binding
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
    }

    private static void testUseBeforeDeclarationRejected() {
        System.out.println("-- Use-before-declaration → E6000 --");

        // LuaJIT reads nil for these uses and fails at runtime; the backend
        // must never emit a Java forward reference that javac would reject
        // after the CLI reported success.
        List<String> sources = List.of(
            // self-reference in a local initializer
            "export function test(): int { let x: int = x + 1; return x; }",
            // self-reference at module scope
            "let x: int = x + 1;\nexport function test(): int { return x; }",
            // use of a local before its declaration
            """
            import * as console from "std/console"
            export function test(): null {
              console.log(x);
              let x: string = "later";
            }
            """,
            // forward reference to a later module field in a field initializer
            "let b: int = c + 1;\nlet c: int = 2;\nexport function test(): int { return b; }",
            // forward reference to a later module field in a module-level if
            """
            if (x === 1) { }
            let x: int = 1;
            export function test(): int { return x; }
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
        System.out.println("-- Function-body module-field access guards (declaration order) --");

        // ---- Allowed: the field is declared BEFORE the function ----
        // (LuaJIT's upvalue write/read = Java's static-field write/read).
        ExecResult parity = compileAndRunJvm("""
            let x: int = 1;
            function f(): int { x = 5; return x; }
            export function test(): int { return f() + x; }
            """, "fieldwriteparity");
        check(parity.exitCode() == 0, "post-declaration write+read exits 0");
        check(parity.output().contains("10"),
            "post-declaration write+read: f()=5 and x=5 (upvalue parity): "
                + parity.output());

        // A write in EVERY branch of an if/else then a read — allowed.
        ExecResult bothBranches = compileAndRunJvm("""
            let x: int = 1;
            function f(): int { if (true) { x = 5; } else { x = 5; } return x; }
            export function test(): int { return f(); }
            """, "fieldwriteboth");
        check(bothBranches.exitCode() == 0, "both-branches write exits 0");
        check(bothBranches.output().contains("5"),
            "post-declaration write in both branches reads 5: "
                + bothBranches.output());

        // Writes in if-condition and call-argument positions — allowed.
        ExecResult positions = compileAndRunJvm("""
            let x: int = 1;
            function g(v: int): int { return v; }
            function f(): int { x = 5; if (x === 5) { return g(x); } return 0; }
            export function test(): int { return f(); }
            """, "fieldwritepositions");
        check(positions.exitCode() == 0, "condition/argument write exits 0");
        check(positions.output().contains("5"),
            "condition and call-argument reads observe the upvalue write (5): "
                + positions.output());

        // A function-local shadow of the module field is a local use, not a
        // field access — allowed.
        ExecResult shadowed = compileAndRunJvm("""
            function f(): int { let x: int = 9; return x; }
            let x: int = 5;
            export function test(): int { return f(); }
            """, "fieldshadow");
        check(shadowed.exitCode() == 0, "function-local shadow exits 0");
        check(shadowed.output().contains("9"),
            "function-local shadow reads the local (9): " + shadowed.output());

        // ---- Rejected: the field is declared AFTER the function ----
        // LuaJIT binds every access in a pre-declaration function to the
        // GLOBAL: a no-prior-write read fails at call time (E8001) while
        // Java would silently read the initialized static field; a write
        // leaves the module-local untouched under LuaJIT (later readers
        // observe the initializer value) while Java would pollute the
        // static field. Both shapes — and every position a write can
        // appear in — are E6000.
        List<String> rejected = List.of(
            // the reviewer's round-8 critical: write-then-read in a
            // pre-declaration function plus a LATER reader — real luajit
            // prints "parity" (f()==5, g()==1); the JVM static field
            // would make g()==5
            """
            import * as console from "std/console"
            function f(): int { x = 5; return x; }
            let x: int = 1;
            export function g(): int { return x; }
            export function test(): null {
              if (f() === 5 && g() === 1) { console.log("parity"); }
            }
            """,
            // write-then-read without a later reader: the function's own
            // read observes the global write under LuaJIT, but the write
            // still pollutes the Java static field — rejected
            """
            function f(): int { x = 5; return x; }
            let x: int = 1;
            export function test(): int { return f(); }
            """,
            // write-only shape
            """
            function f(): null { if (true) { x = 5; } }
            let x: int = 1;
            export function test(): null { f(); }
            """,
            // write inside a block
            """
            function f(): null { { x = 5; } }
            let x: int = 1;
            export function test(): null { f(); }
            """,
            // write in both if/else branches then read — the write itself
            // is rejected regardless of branch coverage
            """
            function f(): int { if (true) { x = 5; } else { x = 5; } return x; }
            let x: int = 1;
            export function test(): int { return f(); }
            """,
            // write in a return value position
            """
            function f(): int { return x = 5; }
            let x: int = 1;
            export function test(): int { return f(); }
            """,
            // write in a call-argument position
            """
            function g(v: int): null { }
            function f(): int { g(x = 5); return 0; }
            let x: int = 1;
            export function test(): int { return f(); }
            """,
            // the reviewer's exact round-8 repro: plain read, no prior write
            """
            function f(): int { return x; }
            let x: int = 5;
            export function test(): int { return f(); }
            """,
            // read in an initializer position, no prior write
            """
            function f(): int { let y: int = x + 1; return y; }
            let x: int = 5;
            export function test(): int { return f(); }
            """,
            // a write in a taken-only branch does not dominate the read
            // after it (LuaJIT reads the global nil when the branch is not
            // taken) — conservative rejection
            """
            function f(): int { if (true) { x = 5; } return x; }
            let x: int = 1;
            export function test(): int { return f(); }
            """,
            // a write inside a called function does not establish
            // dominance (the callee's writes may be conditional) —
            // conservative rejection
            """
            function setx(): null { x = 5; }
            function f(): int { setx(); return x; }
            let x: int = 1;
            export function test(): int { return f(); }
            """);
        for (String source : rejected) {
            Frontend f = compileFrontend(source, "jvmtest-forwardaccess-rej.deal");
            if (!f.errors().isEmpty()) {
                fail("checker must accept the pre-declaration access probe: "
                    + f.errors());
                continue;
            }
            JvmBackend.JvmCodegenResult res = JvmBackend.generate(
                f.program(), f.checkResult(), "jvmtest-forwardaccess-rej.deal", "main");
            check(res.hasErrors(),
                "pre-declaration module-field access is rejected with E6000");
            check(res.diagnostics().stream().anyMatch(d -> "E6000".equals(d.code())),
                "E6000 for the pre-declaration access: " + res.diagnostics());
        }

        // The load-time guards are untouched: a module-level initializer
        // reading a later field is still E6000 (Java illegal forward
        // reference; LuaJIT reads the not-yet-declared global at load).
        Frontend moduleFwd = compileFrontend(
            "let b: int = c + 1;\nlet c: int = 2;\n"
                + "export function test(): int { return b; }",
            "jvmtest-module-fwd.deal");
        if (moduleFwd.errors().isEmpty()) {
            JvmBackend.JvmCodegenResult res =
                JvmBackend.generate(moduleFwd.program(), moduleFwd.checkResult(),
                    "jvmtest-module-fwd.deal", "main");
            check(res.hasErrors(), "module-level later-field read is still rejected");
            check(res.diagnostics().stream().anyMatch(d -> "E6000".equals(d.code())),
                "E6000 for the module-level later-field read: " + res.diagnostics());
        } else {
            fail("checker must accept the module-level forward probe: " + moduleFwd.errors());
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

        // Allowed: a function-body write to a module field declared BEFORE
        // the function resolves to the static field — LuaJIT's upvalue
        // write; both observe 5.
        ExecResult fieldShadow = compileAndRunJvm("""
            let x: int = 1;
            export function test(): int { x = 5; return x; }
            """, "assignfieldshadow");
        check(fieldShadow.exitCode() == 0, "module-field shadow write exits 0");
        check(fieldShadow.output().contains("5"),
            "module-field shadow write observes 5: " + fieldShadow.output());

        // Allowed: a module-level write to a later-declared field is legal
        // Java (JLS §8.3.3 LHS exception) and the later initializer wins,
        // exactly like LuaJIT (probed: both observe 1).
        ExecResult moduleLater = compileAndRunJvm("""
            x = 5;
            let x: int = 1;
            export function test(): int { return x; }
            """, "assignmodulelater");
        check(moduleLater.exitCode() == 0, "module-level later-field write exits 0");
        check(moduleLater.output().contains("1"),
            "module-level later-field write observes 1: "
                + moduleLater.output());
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

        // A module-level out-of-range literal throws during class init;
        // the runner unwraps ExceptionInInitializerError into the
        // DEAL_ERROR_CODE contract (LuaJIT fails at load the same way).
        ExecResult modLit = compileAndRunJvm("""
            let x: int = 9223372036854775807;
            export function test(): int { return 1; }
            """, "modintlit");
        check(modLit.exitCode() == 1, "module-level out-of-range literal exits 1: "
            + modLit.output());
        check(modLit.output().contains("DEAL_ERROR_CODE: E8004"),
            "module-level out-of-range literal reports E8004: " + modLit.output());

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
        System.out.println("-- java.lang name collisions (javac + java) --");

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

        // Module field named Math + int arithmetic (the always-emitted
        // intAdd/intSub helpers reference Math.addExact).
        ExecResult mathField = compileAndRunJvm("""
            let Math: int = 1;
            export function test(): int { return Math + 2; }
            """, "mathfield");
        check(mathField.exitCode() == 0 && mathField.output().contains("3"),
            "module field Math + int arithmetic: " + mathField.output());

        // Module field named Double (the always-emitted intFromNumber helper
        // references Double.isNaN even when int() is never called).
        ExecResult doubleField = compileAndRunJvm("""
            let Double: int = 1;
            export function test(): int { return Double + 1; }
            """, "doublefield");
        check(doubleField.exitCode() == 0 && doubleField.output().contains("2"),
            "module field Double + int arithmetic: " + doubleField.output());

        // Module field named System + module-level console.log (load-time
        // static-initializer output goes through System.out).
        ExecResult systemField = compileAndRunJvm("""
            import * as console from "std/console"
            let System: int = 1;
            console.log("sys-field-ok");
            export function test(): int { return System; }
            """, "systemfield");
        check(systemField.exitCode() == 0
                && systemField.output().contains("sys-field-ok"),
            "module field System + module-level console.log: " + systemField.output());
        check(systemField.output().contains("1"),
            "module field System read back: " + systemField.output());

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

        // Module field named Integer + string ordering (scalarCompare uses
        // Integer.compare and Character.charCount).
        ExecResult integerField = compileAndRunJvm("""
            let Integer: int = 0;
            let Character: int = 0;
            export function test(): boolean {
              return "a" < "b";
            }
            """, "integerfield");
        check(integerField.exitCode() == 0
                && integerField.output().contains("true"),
            "fields Integer/Character + string ordering: " + integerField.output());

        // Module field named RuntimeException + a runtime error (DealError
        // extends java.lang.RuntimeException).
        ExecResult runtimeField = compileAndRunJvm("""
            let RuntimeException: int = 1;
            export function test(): int { return RuntimeException / 0; }
            """, "runtimefield");
        check(runtimeField.exitCode() == 1
                && runtimeField.output().contains("DEAL_ERROR_CODE: E8005"),
            "field RuntimeException + E8005 still surfaces: " + runtimeField.output());

        // Emission shape: no unqualified java.lang references remain.
        Frontend f = compileFrontend("""
            import * as console from "std/console"
            let System: int = 1;
            console.log("qualified");
            export function test(): number { return 1e999; }
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

        // Module-level chain: `z` is declared after the chain (LuaJIT reads
        // nil at load and the condition is false; Java would emit an
        // illegal forward reference).
        Frontend moduleCase = compileFrontend("""
            if (true) { } else if (z === 2) { }
            let z: int = 2;
            export function test(): int { return 1; }
            """, "jvmtest-elseif-module.deal");
        check(moduleCase.errors().isEmpty(),
            "module-level else-if chain frontend clean: " + moduleCase.errors());
        if (moduleCase.errors().isEmpty()) {
            JvmBackend.JvmCodegenResult res = JvmBackend.generate(
                moduleCase.program(), moduleCase.checkResult(),
                "jvmtest-elseif-module.deal", "main");
            check(res.hasErrors(),
                "module-level else-if forward reference rejected");
            check(res.diagnostics().stream()
                    .anyMatch(d -> d.message().contains("'z'")),
                "module-level diagnostic names z: " + res.diagnostics());
        }

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

        // Positive: a module-level chain over already-declared fields.
        ExecResult modOk = compileAndRunJvm("""
            let z: int = 2;
            let hit: boolean = false;
            if (z === 1) { } else if (z === 2) { hit = true; }
            export function test(): int { if (hit) { return 1; } return 0; }
            """, "elseifmodok");
        check(modOk.exitCode() == 0 && modOk.output().contains("1"),
            "clean module-level else-if chain runs: " + modOk.output());
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

        // Module-level field initializer with a guarded operand: the
        // temporary must stay in scope, so the field is declared
        // uninitialized and assigned inside the same static block.
        ExecResult modSkip = compileAndRunJvm("""
            import * as console from "std/console"
            function helper(): null { console.log("helper-ran"); }
            let b: boolean = false && helper() === null;
            export function test(): null { console.log("test-ran"); }
            """, "scmodskip");
        check(modSkip.exitCode() == 0, "module-level guarded initializer exits 0");
        check(modSkip.output().contains("test-ran"),
            "module still runs: " + modSkip.output());
        check(!modSkip.output().contains("helper-ran"),
            "module-level guarded operand is NOT evaluated: " + modSkip.output());

        ExecResult modRuns = compileAndRunJvm("""
            import * as console from "std/console"
            function helper(): null { console.log("helper-ran"); }
            let b: boolean = true && helper() === null;
            export function test(): null { console.log("test-ran"); }
            """, "scmodruns");
        check(modRuns.exitCode() == 0, "module-level reachable initializer exits 0");
        check(modRuns.output().contains("helper-ran"),
            "module-level reachable operand IS evaluated: " + modRuns.output());
        check(modRuns.output().indexOf("helper-ran")
                < modRuns.output().indexOf("test-ran"),
            "module-level initializer runs before the exported call: " + modRuns.output());

        // Emission assertions: no lambdas, and the module-level case
        // declares the field then assigns it in the static block.
        Frontend f = compileFrontend("""
            import * as console from "std/console"
            function helper(): null { console.log("helper-ran"); }
            let b: boolean = false && helper() === null;
            export function test(): null { console.log("test-ran"); }
            """, "jvmtest-sc.deal");
        check(f.errors().isEmpty(), "short-circuit probe frontend clean: " + f.errors());
        if (f.errors().isEmpty()) {
            JvmBackend.JvmCodegenResult res = JvmBackend.generate(
                f.program(), f.checkResult(), "jvmtest-sc.deal", "main");
            check(!res.hasErrors(), "short-circuit probe codegen clean: "
                + res.diagnostics());
            if (!res.hasErrors()) {
                check(!res.source().contains("->"),
                    "short-circuit lowering emits no lambda");
                check(res.source().contains("static boolean b;"),
                    "module-level guarded initializer declares the field uninitialized");
                check(res.source().contains("b = __sc0;"),
                    "module-level guarded initializer assigns inside the static block");
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

        // Module-level field-initializer position: the materialized
        // temporary is declared inside the static block that also holds
        // the hoisted statement and the field assignment (a class-body
        // initializer cannot see a block-local declaration).
        ExecResult fieldInit = compileAndRunJvm("""
            import * as console from "std/console"
            function g(): int { console.log("g-ran"); return 7; }
            function f(y: int, x: null): int { return y; }
            let a: int = f(g(), console.log("x"));
            export function test(): int { return a; }
            """, "evalorder-fieldinit");
        check(fieldInit.exitCode() == 0, "field-initializer eval order exits 0");
        check(fieldInit.output().indexOf("g-ran") >= 0
                && fieldInit.output().indexOf("g-ran")
                    < fieldInit.output().indexOf("x"),
            "field-initializer position: g() before console.log: "
                + fieldInit.output());
        check(fieldInit.output().contains("7"),
            "field-initializer position computes 7: " + fieldInit.output());

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
                check(!src.contains("->"), "no lambda is emitted");
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
        System.out.println("-- Module-level call reading a later field -> E6000 --");

        List<String> rejected = List.of(
            // call before the field the callee reads (callee declared first)
            """
            import * as console from "std/console"
            function f(): int { return x; }
            f();
            let x: int = 5;
            export function test(): null { console.log("test-ran"); }
            """,
            // the reviewer's shape: call before the callee's own declaration
            """
            import * as console from "std/console"
            f();
            let x: int = 5;
            function f(): int { return x; }
            export function test(): null { console.log("test-ran"); }
            """,
            // transitive: f calls g which reads the later field
            """
            function f(): int { return g(); }
            function g(): int { return x; }
            f();
            let x: int = 5;
            export function test(): int { return 1; }
            """,
            // call in a module-level field initializer
            """
            function f(): int { return x; }
            let y: int = f();
            let x: int = 5;
            export function test(): int { return y; }
            """,
            // call in a module-level if condition
            """
            function f(): int { return x; }
            if (f() === 1) { }
            let x: int = 5;
            export function test(): int { return 1; }
            """,
            // a field's own initializer calling a function that reads the
            // field being initialized (LuaJIT reads nil, fails at load)
            """
            let x: int = f();
            function f(): int { return x; }
            export function test(): int { return x; }
            """);

        for (String source : rejected) {
            Frontend f = compileFrontend(source, "jvmtest-modcall.deal");
            if (!f.errors().isEmpty()) {
                fail("checker must accept the module-level call probe "
                    + "(the backend rejects it): " + f.errors());
                continue;
            }
            JvmBackend.JvmCodegenResult res = JvmBackend.generate(
                f.program(), f.checkResult(), "jvmtest-modcall.deal", "main");
            check(res.hasErrors(), "backend rejects the module-level call "
                + "reading a later field");
            check(res.diagnostics().stream().anyMatch(d -> "E6000".equals(d.code())),
                "E6000 for the module-level call reading a later field: "
                    + res.diagnostics());
        }

        // Positive: a call reading a field declared BEFORE the call site is
        // fine, and a function-local shadow of a module field is not a
        // field read.
        ExecResult ok = compileAndRunJvm("""
            import * as console from "std/console"
            let x: int = 5;
            function f(): int { return x; }
            f();
            export function test(): int { return 1; }
            """, "modcallok");
        check(ok.exitCode() == 0, "earlier-field call exits 0");
        check(ok.output().contains("1"),
            "earlier-field call module still works: " + ok.output());

        ExecResult shadow = compileAndRunJvm("""
            import * as console from "std/console"
            let x: int = 5;
            function f(): int { let x: int = 9; return x; }
            f();
            export function test(): int { return 1; }
            """, "modcallshadow");
        check(shadow.exitCode() == 0, "local-shadow call exits 0");
        check(shadow.output().contains("1"),
            "local shadow of a module field is not a field read: " + shadow.output());
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
        System.out.println("-- Module-level call before function declaration → E6000 --");

        List<String> rejected = List.of(
            // direct: call before the callee's own declaration
            """
            import * as console from "std/console"
            f();
            function f(): null { console.log("f-ran"); }
            export function test(): int { return 1; }
            """,
            // the exact shape the module-level ordering test used before
            // rework (field initializers calling later-declared functions)
            """
            import * as console from "std/console"
            let a: int = f();
            console.log("mid");
            let b: int = g();
            function f(): int { console.log("f-ran"); return 1; }
            function g(): int { console.log("g-ran"); return 2; }
            export function test(): int { return a + b; }
            """,
            // transitive: the callee is declared first, but its body calls
            // a function declared later than the call site
            """
            import * as console from "std/console"
            function f(): null { g(); }
            f();
            function g(): null { console.log("g-ran"); }
            export function test(): int { return 1; }
            """,
            // deep transitive chain f → h → g with g declared later
            """
            import * as console from "std/console"
            function f(): null { h(); }
            function h(): null { g(); }
            f();
            function g(): null { console.log("g-ran"); }
            export function test(): int { return 1; }
            """,
            // a field initializer calling a later-declared function
            """
            let a: int = f();
            function f(): int { return 1; }
            export function test(): int { return a; }
            """,
            // a module-level call of an export declared later
            """
            import * as console from "std/console"
            test2();
            export function test2(): null { console.log("later"); }
            export function test(): int { return 1; }
            """);

        for (String source : rejected) {
            Frontend f = compileFrontend(source, "jvmtest-modfndecl.deal");
            if (!f.errors().isEmpty()) {
                fail("checker must accept the module-level call-before-"
                    + "declaration probe (the backend rejects it): "
                    + f.errors());
                continue;
            }
            JvmBackend.JvmCodegenResult res =
                JvmBackend.generate(f.program(), f.checkResult(),
                    "jvmtest-modfndecl.deal", "main");
            check(res.hasErrors(), "backend rejects the module-level call "
                + "reaching a later-declared function");
            check(res.diagnostics().stream().anyMatch(d -> "E6000".equals(d.code())),
                "E6000 for the module-level call reaching a later-declared "
                    + "function: " + res.diagnostics());
        }

        // Positive: all declarations before the call site run at load with
        // the same observable order as LuaJIT (verified with luajit).
        ExecResult direct = compileAndRunJvm("""
            import * as console from "std/console"
            function f(): int { console.log("f-ran"); return 1; }
            f();
            export function test(): int { return 1; }
            """, "modfnorderok");
        check(direct.exitCode() == 0, "post-declaration call exits 0");
        check(direct.output().contains("f-ran"),
            "post-declaration module call ran at load: " + direct.output());

        // Transitive positive: the callee's body calls another function
        // that is also declared before the call site.
        ExecResult transitive = compileAndRunJvm("""
            function f(): int { return g(); }
            function g(): int { return 7; }
            f();
            export function test(): int { return 1; }
            """, "modfntransok");
        check(transitive.exitCode() == 0, "transitive post-declaration call exits 0");
        check(transitive.output().contains("1"),
            "transitive post-declaration call module still works: "
                + transitive.output());
    }

    /** The DEAL_ERROR_CODE contract must hold for module-level errors
     * whether or not the module has a zero-arity export: with one, the
     * first auto-invocation triggers class initialization and the error
     * arrives as an ExceptionInInitializerError (a LinkageError, not a
     * RuntimeException) that the runner unwraps. */
    private static void testRunnerModuleErrorCodeWithExport() throws Exception {
        System.out.println("-- Module-level error + zero-arity export (DEAL_ERROR_CODE) --");

        ExecResult withExport = compileAndRunJvm("""
            export function test(): int { return 1; }
            let x: int = 1 / 0;
            """, "moderrwithexport");
        check(withExport.exitCode() == 1, "module-level error with export exits 1");
        check(withExport.output().contains("DEAL_ERROR_CODE: E8005"),
            "DEAL_ERROR_CODE contract holds with a zero-arity export: "
                + withExport.output());

        ExecResult noExport = compileAndRunJvm("""
            let x: int = 1 / 0;
            export function takesArg(y: int): int { return y; }
            """, "moderrnoexport");
        check(noExport.exitCode() == 1, "module-level error without export exits 1");
        check(noExport.output().contains("DEAL_ERROR_CODE: E8005"),
            "DEAL_ERROR_CODE contract holds without a zero-arity export: "
                + noExport.output());
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
            function add(a: int, b: int): int { return a + b; }
            export function run(): int {
              console.log("jvm-orchestrator");
              return add(20, 22);
            }
            """);

        Path entryFile = tmpDir.resolve("src/jvm_main.deal").toAbsolutePath();
        Path outputDir = tmpDir.resolve("build/jvm");
        List<Path> roots = List.of(tmpDir.resolve("src").toAbsolutePath());

        DealConfig config = DealConfig.load(tmpDir);
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

        writeFile("src/unsupported_main.deal", """
            export class Point {
              x: int;
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
        check(!success, "JVM backend rejects class declarations");
        check(orchestrator.diagnostics().stream()
                .anyMatch(d -> "E6000".equals(d.code())),
            "orchestrator reports E6000: " + orchestrator.diagnostics());
        check(!Files.exists(outputDir.resolve("Unsupported_main.java")),
            "no artifact written when the backend reports errors");
    }

    /** A non-std/console import is rejected by the orchestrator's JVM path at
     * the import statement itself — even when the import is never used — so
     * the imported module's LuaJIT require-time side effects can never be
     * silently dropped. */
    private static void testOrchestratorJvmImportRejected() throws Exception {
        System.out.println("-- Orchestrator: unused non-console import → E6000 --");

        writeFile("src/other.deal", """
            import * as console from "std/console"
            console.log("other-module-ran");
            export function unused(): int { return 1; }
            """);
        writeFile("src/entry.deal", """
            import * as m from "./other"
            export function run(): int { return 1; }
            """);

        Path entryFile = tmpDir.resolve("src/entry.deal").toAbsolutePath();
        Path outputDir = tmpDir.resolve("build/import_rejected");
        List<Path> roots = List.of(tmpDir.resolve("src").toAbsolutePath());

        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            entryFile, outputDir, false, false, false, Backend.JVM,
            (DealConfig) null, roots, Path.of(".").toAbsolutePath().normalize());

        boolean success = orchestrator.compile();
        check(!success, "unused non-console import fails the JVM compile");
        check(orchestrator.diagnostics().stream()
                .anyMatch(d -> "E6000".equals(d.code())),
            "orchestrator reports E6000 for the import: " + orchestrator.diagnostics());
        check(!Files.exists(outputDir.resolve("Entry.java")),
            "no artifact for the module with the rejected import");
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
            export function main(): int { return 3; }
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
    }

    /**
     * {@code --source-map} combined with {@code --backend jvm} produces no
     * sidecars; the orchestrator must print a warning instead of silently
     * ignoring the request (ISSUE-0091 rework round 3).
     */
    private static void testOrchestratorJvmSourceMapWarning() throws Exception {
        System.out.println("-- Orchestrator: JVM path warns on --source-map --");

        writeFile("src/sm_main.deal", """
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
            DealConfig c = DealConfig.parse(Path.of("deal.json"),
                "{\"backend\": \"jvm\"}");
            check("jvm".equals(c.backend()), "deal.json accepts 'jvm'");
            DealConfig lua = DealConfig.parse(Path.of("deal.json"),
                "{\"backend\": \"luajit\"}");
            check("luajit".equals(lua.backend()), "deal.json accepts 'luajit'");
            // ISSUE-0091 rework round 3: the CLI accepts --backend lua as a
            // LuaJIT alias; deal.json must accept the same name.
            DealConfig alias = DealConfig.parse(Path.of("deal.json"),
                "{\"backend\": \"lua\"}");
            check("lua".equals(alias.backend()), "deal.json accepts 'lua' alias");
            // Case-insensitive spellings, mirroring Backend.fromCliName: the
            // CLI accepts --backend JVM / Lua, so the manifest must accept
            // the same spellings (round-8 review flaw).
            DealConfig upper = DealConfig.parse(Path.of("deal.json"),
                "{\"backend\": \"JVM\"}");
            check("JVM".equals(upper.backend()), "deal.json accepts 'JVM'");
            DealConfig mixed = DealConfig.parse(Path.of("deal.json"),
                "{\"backend\": \"  Lua \"}");
            check("  Lua ".equals(mixed.backend()),
                "deal.json accepts ' Lua ' with surrounding whitespace");
        } catch (Exception e) {
            fail("DealConfig jvm parse: " + e.getMessage());
        }

        try {
            DealConfig.parse(Path.of("deal.json"), "{\"backend\": \"wasm\"}");
            fail("unsupported backend must throw");
        } catch (IllegalArgumentException e) {
            check(e.getMessage().contains("luajit")
                    && e.getMessage().contains("jvm"),
                "error message names supported backends: " + e.getMessage());
        }

        // End to end: a deal.json "backend": "lua" selects LuaJIT through the
        // CLI exactly like the --backend lua flag. (The manifest lives in
        // the entry file's directory — that is where Main.load looks.)
        try {
            writeFile("lua_proj/src/deal.json", "{\"backend\": \"lua\"}");
            writeFile("lua_proj/src/lua_alias_main.deal",
                "export function run(): null {}");
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
                "export function run(): int { return 6 * 7; }");
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
