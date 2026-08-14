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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Unit tests for the JVM backend skeleton (ISSUE-0091):
 * <ul>
 *   <li>identifier translation and collision-safe class-name derivation,</li>
 *   <li>Java emission for the supported skeleton surface
 *       (literals, arithmetic, locals, if/else, console output, intrinsics),</li>
 *   <li>E6000 rejection of out-of-scope constructs (classes, arrays, tables,
 *       loops, async, non-console imports — at the import statement itself,
 *       even when unused —, module-level returns, use-before-declaration,
 *       runtime-helper name collisions),</li>
 *   <li>observable-behavior preservation for null-typed side effects
 *       (null-typed returns/initializers/assignments/arguments), module-level
 *       load-time statements, and shadowed initializers — verified by
 *       executing the emitted artifact with {@code javac} + {@code java}
 *       subprocesses,</li>
 *   <li>DEAL runtime error codes (E8004/E8005/E8006/E8001 incl. the
 *       negative-exponent and extreme-power paths) surfaced by executing the
 *       emitted artifact,</li>
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
            testNullReturnSideEffects();
            testNullTypedInitializers();
            testModuleLevelStatements();
            testShadowedInitializer();
            testUseBeforeDeclarationRejected();
            testRuntimeErrorCodes();
            testOrchestratorJvmBackend();
            testOrchestratorDefaultStaysLua();
            testOrchestratorJvmRejectsUnsupported();
            testOrchestratorJvmImportRejected();
            testOrchestratorJvmClassCollision();
            testDealConfigBackendField();
            testCliBackendFlag();
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
        check(java.contains("System.out.println("), "console.log → System.out");
        check(java.contains("intFromNumber(3.0)"), "int() intrinsic");
        check(java.contains("numberFromInt(7L)"), "number() intrinsic");
        check(java.contains("long x = add(1L, 2L);"), "local with int literals");
        check(java.contains("(\"lit\" + \"eral\")"), "string concatenation");
        check(java.contains("(!"), "boolean not");
        check(java.contains("Void z = null;"), "null-typed local from null literal");
        check(java.contains("public static long test()"), "exported function public");
        check(java.contains("static final class DealError"), "runtime error class");
        check(java.contains("static long intPow("), "pow helper emitted");
        check(java.contains("static Void nullAnd("), "nullAnd helper emitted");
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
            new Case("while loop", """
                export function test(): int {
                  let i: int = 0;
                  while (i < 3) { i = i + 1; }
                  return i;
                }
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
     * are lowered to stmt + null (via nullAnd), never to invalid Java. */
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
                check(java.contains("    System.out.println(\"module-if-ran\");"),
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
        // module-level statements must run in source order.
        ExecResult order = compileAndRunJvm("""
            import * as console from "std/console"
            let a: int = f();
            console.log("mid");
            let b: int = g();
            function f(): int { console.log("f-ran"); return 1; }
            function g(): int { console.log("g-ran"); return 2; }
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
            check(java.contains("System.out.println(\"jvm-orchestrator\")"),
                "console.log mapped to System.out");
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

    private static void testDealConfigBackendField() {
        System.out.println("-- DealConfig backend field --");

        try {
            DealConfig c = DealConfig.parse(Path.of("deal.json"),
                "{\"backend\": \"jvm\"}");
            check("jvm".equals(c.backend()), "deal.json accepts 'jvm'");
            DealConfig lua = DealConfig.parse(Path.of("deal.json"),
                "{\"backend\": \"luajit\"}");
            check("luajit".equals(lua.backend()), "deal.json accepts 'luajit'");
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
        } catch (IOException e) {
            fail("CLI backend test IO: " + e.getMessage());
        }
    }
}
