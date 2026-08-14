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
 *   <li>identifier translation and class-name derivation,</li>
 *   <li>Java emission for the supported skeleton surface
 *       (literals, arithmetic, locals, if/else, console output, intrinsics),</li>
 *   <li>E6000 rejection of out-of-scope constructs (classes, arrays, tables,
 *       loops, async, non-console imports),</li>
 *   <li>DEAL runtime error codes (E8004/E8005) surfaced by executing the
 *       emitted artifact with {@code javac} + {@code java} subprocesses,</li>
 *   <li>the backend-selection seam: {@code CompilationOrchestrator} with
 *       {@code Backend.JVM} emits and compiles a real {@code .java} artifact,
 *       the default stays LuaJIT, {@code DealConfig} and the CLI accept
 *       {@code jvm}.</li>
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
            testRuntimeErrorCodes();
            testOrchestratorJvmBackend();
            testOrchestratorDefaultStaysLua();
            testOrchestratorJvmRejectsUnsupported();
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
        check(JvmBackend.classNameFor("app.main").equals("Main"),
            "dotted path → last segment");
        check(JvmBackend.classNameFor("app/sub/main").equals("Main"),
            "slashed path → last segment");
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
        check(java.contains("Void z = null;"), "null-typed local");
        check(java.contains("public static long test()"), "exported function public");
        check(java.contains("static final class DealError"), "runtime error class");
        check(java.contains("static long intPow("), "pow helper emitted");
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
