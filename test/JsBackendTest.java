package deal.test;

import deal.ast.ProgramNode;
import deal.checker.CheckResult;
import deal.checker.ModuleResolver;
import deal.checker.NameResolver;
import deal.checker.SymbolTable;
import deal.checker.TypeChecker;
import deal.codegen.Backend;
import deal.codegen.js.JsBackend;
import deal.descriptors.CanonicalRuntimeTypeDescriptor;
import deal.diagnostics.CompilerDiagnostic;
import deal.identity.CanonicalClassIdentity;
import deal.identity.CanonicalClassIdentityIndex;
import deal.identity.CanonicalModuleIdentity;
import deal.identity.ProjectModuleIdentity;
import deal.lexer.LexResult;
import deal.lexer.Lexer;
import deal.module.CompilationOrchestrator;
import deal.module.DealConfig;
import deal.module.DealConfig.DealConfigParseResult;
import deal.module.ModuleIdentityResolver;
import deal.module.ModuleShapeValidator;
import deal.parser.ParseResult;
import deal.parser.Parser;
import deal.types.Type;
import deal.types.Types;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
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
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * JS backend unit surface (js-backend-conformance-e2e D2): the permanent
 * home of the js-backend-architecture emission pins, the D6 rejection
 * pins, the js-backend-runtime Verification 1-9 node semantic cases, and
 * the orchestrator-level seam/deployment pins — {@code Backend.JS}
 * selection, {@code deal.json} {@code "backend": "js"} acceptance, the
 * default {@code build/js} output, the runtime/stdlib deployment copies,
 * and no-partial-artifact on rejection.
 *
 * <p>Emission and rejection pins inspect generated CommonJS source
 * without executing Node on every case (the {@link JvmBackendTest}
 * pattern); the bounded node semantic cases execute the real
 * frontend → {@link JsBackend} → node chain through the deployed
 * {@code deal/runtime.js} and {@code std/*.js} artifacts with the
 * conformance runner ({@link BackendConformanceTest#buildJsRunner}),
 * which auto-invokes zero-arity exports in declaration order, prints
 * non-null results, awaits thenables, and maps uncaught errors to the
 * shared {@code DEAL_ERROR_CODE: <code>} stderr + exit-1 contract.
 * Direct-runtime probes execute the {@code $rt} surface under the real
 * node binary for the defensive arms no checker-accepted DEAL source
 * reaches (the E8007 extra-field arm, js-backend-runtime D5). Node
 * unavailability skips the node cases (never fails them); every other
 * case runs unconditionally.
 */
public class JsBackendTest {

    private static int passed = 0;
    private static int failed = 0;
    private static int skipped = 0;
    private static Path tmpDir;
    private static boolean nodeAvailable;

    public static void main(String[] args) throws Exception {
        tmpDir = Files.createTempDirectory("js_backend_test_");
        nodeAvailable = probeNode();
        System.out.println("Node.js: " + (nodeAvailable ? "available"
            : "NOT available (node semantic cases will be skipped)"));
        try {
            testBackendNamesJs();
            testDealConfigBackendJs();
            testCliBackendFlagJsDefaultOutput();
            testModuleShapeRoot();
            testModuleShapeNestedRequires();
            testReservedWordTranslation();
            testWrapperShapeAndDescriptors();
            testCheckSiteDescriptorPins();
            testSpanParameterCollisionEmission();
            testClassEmissionPins();
            testEntryShimAndE6004();
            testSourceLocationArguments();
            testOwnPropertySafeProtoEmission();
            testErrorEmissionPins();
            testArrayElementDeleteEmission();
            testHostGlobalHygiene();
            testUnsupportedConstructsRejected();
            testSourceMapSidecarsEmitted();
            testIntArithmeticEdgeCodes();
            testNumModFloored();
            testScalarStringOps();
            testOptionalThreeState();
            testNilEquivalentNullableReads();
            testE8010SignatureMismatch();
            testE8007ExtraClassFieldRuntime();
            testClassIdentityTagPair();
            testCanonicalDescriptorMatcherPins();
            testLegacyDescriptorRejectionPins();
            testCanonicalClassIdentityIndex();
            testLegacyDialectRetirementScan();
            testErrorDefaultFilling();
            testContextualErrorConstruction();
            testRethrowPreservesCode();
            testArrayElementDeleteSemantics();
            testSpanParameterCollisionExecution();
            testStdJsonRoundTrips();
            testJsonStoredFunctionE8001();
            testIntrinsicFunctionValues();
            testAsyncCompletionAndErrors();
            testOrchestratorJsBackend();
            testOrchestratorJsNestedModule();
            testOrchestratorJsRejectsUnsupported();
            testBackendSelectionNames();
            testDealJsonBackendAcceptance();
            testCliDefaultOutputBuildJs();
            testOrchestratorDeploymentCopies();
            testNoPartialArtifactOnRejection();
        } finally {
            cleanup();
        }

        System.out.println();
        System.out.println("=== JS Backend Test Summary ===");
        System.out.println("Passed: " + passed + ", Failed: " + failed
            + ", Skipped: " + skipped);
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

    /** Counts one toolchain skip (node unavailable) — a skip, never a
     * failure (js-backend-conformance-e2e D1/D2 toolchain rule). */
    private static void skipNode(String reason) {
        skipped++;
        System.out.println("  SKIP (node unavailable): " + reason);
    }

    private static boolean probeNode() {
        try {
            Process node = new ProcessBuilder("node", "--version")
                .redirectErrorStream(true).start();
            return node.waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
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
                            List<CompilerDiagnostic> errors) {}

    /**
     * Frontend compile with the JS adapter's checker module path
     * {@code Main} (the conformance-adapter convention): the checker
     * seeds every local {@code Type.Class} module path from this string,
     * and the backend seeds its instance {@code $classname} tags from its
     * own modulePath — the two must agree byte-for-byte for class
     * identities to cross typed boundaries.
     */
    private static Frontend compileFrontend(String source, String filename) {
        return compileFrontend(source, filename, "Main",
            new BackendConformanceTest.StubModuleResolver());
    }

    /** Frontend compile with an explicit module resolver (module probes). */
    @SuppressWarnings("deprecation")
    private static Frontend compileFrontend(String source, String filename,
                                            ModuleResolver moduleResolver) {
        return compileFrontend(source, filename, "Main", moduleResolver);
    }

    @SuppressWarnings("deprecation")
    private static Frontend compileFrontend(String source, String filename,
                                            String modulePath,
                                            ModuleResolver moduleResolver) {
        List<CompilerDiagnostic> errors = new ArrayList<>();

        LexResult lex = new Lexer(source, filename).tokenize();
        for (CompilerDiagnostic d : lex.diagnostics()) {
            if ("error".equals(d.severity())) {
                errors.add(d);
            }
        }
        if (lex.hasErrors()) {
            return new Frontend(null, null, errors);
        }

        Parser parser = new Parser(lex.tokens(), filename);
        ParseResult parseResult = parser.parse();
        for (CompilerDiagnostic d : parseResult.diagnostics()) {
            if ("error".equals(d.severity())) {
                errors.add(d);
            }
        }
        if (parseResult.hasErrors()) {
            return new Frontend(null, null, errors);
        }

        // Post-parse module shape validation (v1.2 module top level),
        // mirroring the orchestrator pipeline.
        for (CompilerDiagnostic d : ModuleShapeValidator.validate(parseResult.program(),
                filename, filename.endsWith(".d.deal"))) {
            if ("error".equals(d.severity())) {
                errors.add(d);
            }
        }
        if (!errors.isEmpty()) {
            return new Frontend(null, null, errors);
        }

        NameResolver nr = new NameResolver(modulePath, moduleResolver);
        SymbolTable symTable;
        try {
            symTable = nr.resolve(parseResult.program());
        } catch (Exception e) {
            errors.add(CompilerDiagnostic.synthetic("E9999", "error",
                e.getMessage(), filename,
                "missing anchor: fixture source '" + filename + "'"));
            return new Frontend(null, null, errors);
        }
        for (CompilerDiagnostic d : nr.diagnostics()) {
            if ("error".equals(d.severity())) {
                errors.add(d);
            }
        }

        CheckResult result = TypeChecker.check(modulePath, symTable, nr,
            parseResult.program());
        for (CompilerDiagnostic d : result.diagnostics()) {
            if ("error".equals(d.severity())) {
                errors.add(d);
            }
        }

        return new Frontend(parseResult.program(), result, errors);
    }

    /** The adapter-shape generation call: sourcePath = the frontend
     * filename, modulePath = "Main", no imports, not the entry. */
    private static JsBackend.JsCodegenResult generate(String source, String name) {
        return generate(source, name, "Main", Map.of(), Map.of(), false);
    }

    private static JsBackend.JsCodegenResult generate(String source, String name,
                                                      String modulePath,
                                                      Map<String, String> importResolutions,
                                                      Map<String, Map<String, Type>> hostModules,
                                                      boolean isEntry) {
        Frontend f = compileFrontend(source, "jstest-" + name + ".deal");
        if (f.program() == null) {
            return null;
        }
        return JsBackend.generate(f.program(), f.checkResult(),
            "jstest-" + name + ".deal", modulePath, importResolutions,
            hostModules, isEntry);
    }

    private record NodeResult(String output, int exitCode) {}

    /**
     * Deploys one generated artifact plus the runtime/stdlib support set
     * into a fresh temp dir (the conformance adapter's deployment shape:
     * {@link BackendConformanceTest#deployJsSupport}).
     */
    private static Path deployArtifacts(JsBackend.JsCodegenResult res,
                                        String artifactName) throws IOException {
        Path dir = Files.createTempDirectory("jstest_run_");
        Files.writeString(dir.resolve(artifactName), res.source());
        BackendConformanceTest.deployJsSupport(dir);
        return dir;
    }

    /** Executes a node script in {@code dir} and returns the combined
     * output and exit code. */
    private static NodeResult runNodeScript(Path dir, String scriptFile)
            throws Exception {
        ProcessBuilder pb = new ProcessBuilder("node", scriptFile);
        pb.directory(dir.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(),
            StandardCharsets.UTF_8).trim();
        int exit = p.waitFor();
        return new NodeResult(out, exit);
    }

    private static void deleteDir(Path dir) {
        try {
            Files.walk(dir).sorted(Comparator.reverseOrder())
                .forEach(f -> { try { Files.deleteIfExists(f); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}
    }

    /**
     * The full JS chain under the real node binary: frontend → codegen →
     * deployment → the conformance runner → node execution.
     */
    private static NodeResult runDealNode(String source, String name)
            throws Exception {
        Frontend f = compileFrontend(source, "jstest-" + name + ".deal");
        if (!f.errors().isEmpty()) {
            throw new RuntimeException("frontend errors: " + f.errors());
        }
        JsBackend.JsCodegenResult res = JsBackend.generate(f.program(),
            f.checkResult(), "jstest-" + name + ".deal", "Main",
            Map.of(), Map.of(), false);
        if (res.hasErrors()) {
            throw new RuntimeException("codegen errors: " + res.diagnostics());
        }
        Path dir = deployArtifacts(res, "Main.js");
        Files.writeString(dir.resolve("JsConformanceRunner.js"),
            BackendConformanceTest.buildJsRunner(f.program()));
        NodeResult result = runNodeScript(dir, "JsConformanceRunner.js");
        deleteDir(dir);
        return result;
    }

    /**
     * A direct {@code $rt}-surface probe under node (the runtime-page
     * defensive arms no checker-accepted DEAL source reaches).
     */
    private static NodeResult runRuntimeProbe(String name, String script)
            throws Exception {
        Path dir = Files.createTempDirectory("jstest_probe_");
        BackendConformanceTest.deployJsSupport(dir);
        Files.writeString(dir.resolve("probe.js"), script);
        NodeResult result = runNodeScript(dir, "probe.js");
        deleteDir(dir);
        return result;
    }

    /** A fixed-export module resolver for backend-level module probes. */
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
        public deal.checker.Symbol.ClassSymbol resolveClassSymbol(
                String className, String modulePath, String importingModule)
                throws ModuleNotFoundException {
            return null;
        }
    }

    // =========================================================================
    // Seam and manifest pins
    // =========================================================================

    private static void testBackendNamesJs() {
        System.out.println("-- Backend names: JS seam --");

        check(Backend.fromCliName("js").orElse(null) == Backend.JS,
            "'js' selects the JS backend");
        check(Backend.fromCliName("Js").orElse(null) == Backend.JS,
            "'Js' selects the JS backend (case-insensitive)");
        check(Backend.fromCliName(" JS ").orElse(null) == Backend.JS,
            "' JS ' selects the JS backend (trim + lowercase)");
        check(Backend.JS.cliName().equals("js"), "JS cli name is 'js'");
        check(Backend.fromCliName("lua").orElse(null) == Backend.LUAJIT,
            "'lua' still selects LuaJIT");
        check(Backend.fromCliName("luajit").orElse(null) == Backend.LUAJIT,
            "'luajit' still selects LuaJIT");
        check(Backend.fromCliName("jvm").orElse(null) == Backend.JVM,
            "'jvm' still selects JVM");
        check(Backend.fromCliName("wasm").isEmpty(), "unknown backend is empty");
    }

    private static void testDealConfigBackendJs() {
        System.out.println("-- DealConfig backend field: js --");

        DealConfigParseResult r = DealConfig.parse(Path.of("deal.json"),
            "{\"backend\": \"js\"}");
        check(r.diagnostics().isEmpty(),
            "deal.json 'js' carries no diagnostics: " + r.diagnostics());
        check(r.config() != null && "js".equals(r.config().backend()),
            "deal.json accepts 'js'");

        DealConfigParseResult upper = DealConfig.parse(Path.of("deal.json"),
            "{\"backend\": \"  Js \"}");
        check(upper.diagnostics().isEmpty(),
            "deal.json ' Js ' carries no diagnostics");
        check(upper.config() != null && "  Js ".equals(upper.config().backend()),
            "deal.json accepts ' Js ' with surrounding whitespace");

        DealConfigParseResult bad = DealConfig.parse(Path.of("deal.json"),
            "{\"backend\": \"wasm\"}");
        check(bad.config() == null, "unsupported backend yields no config");
        check(bad.diagnostics().size() == 1,
            "unsupported backend yields exactly one diagnostic: " + bad.diagnostics());
        if (bad.diagnostics().size() == 1) {
            CompilerDiagnostic d = bad.diagnostics().get(0);
            check("E2012".equals(d.code()) && "error".equals(d.severity()),
                "unsupported backend is an E2012 error: " + d);
            check(d.message().contains("luajit") && d.message().contains("jvm")
                    && d.message().contains("js"),
                "error message names supported backends: " + d.message());
        }
    }

    /**
     * The CLI {@code --backend js} path with the DEFAULT output
     * {@code build/js}: a real {@code java -cp build deal.Main compile
     * <entry> --backend js} subprocess with the working directory set to
     * a temp project root, so the CLI's relative default output lands at
     * {@code <cwd>/build/js} (the in-process {@code user.dir} cannot be
     * changed after the default filesystem caches its default directory).
     */
    private static void testCliBackendFlagJsDefaultOutput() throws Exception {
        System.out.println("-- CLI --backend js: default output build/js --");

        Path proj = Files.createTempDirectory("jstest_cli_");
        Files.createDirectories(proj.resolve("src"));
        // No stdlib import: the subprocess working directory is the temp
        // project, where the repo-root std/*.d.deal declarations are not
        // resolvable; the default-output pin needs no import.
        Files.writeString(proj.resolve("src/cli_js_main.deal"), """
            export function main(): null { return null; }
            export function run(): int { return 1; }
            """);

        // The subprocess working directory is the temp project, so the
        // CLI's repo-relative runtime file fallback and stdlib directory
        // resolve against it: stage the same files the orchestrator
        // deploys (a plain file copy of the committed artifacts, not a
        // deployment bypass — the copy targets are the production
        // fallback paths).
        Files.createDirectories(proj.resolve("deal"));
        Files.copy(Path.of("deal/runtime.js"), proj.resolve("deal/runtime.js"));
        Files.createDirectories(proj.resolve("std"));
        for (String stdlibModule : List.of("console", "string", "table",
                "json", "math", "time")) {
            Files.copy(Path.of("std/" + stdlibModule + ".js"),
                proj.resolve("std/" + stdlibModule + ".js"));
        }

        String buildCp = Path.of("build").toAbsolutePath().toString();
        ProcessBuilder pb = new ProcessBuilder("java", "-cp", buildCp,
            "deal.Main", "compile", "src/cli_js_main.deal", "--backend", "js");
        pb.directory(proj.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(),
            StandardCharsets.UTF_8);
        int rc = p.waitFor();
        check(rc == 0, "CLI --backend js exits 0: " + out);

        Path defaultOut = proj.resolve("build/js");
        check(Files.exists(defaultOut.resolve("cli_js_main.js")),
            "default output build/js holds the emitted cli_js_main.js");
        check(Files.exists(defaultOut.resolve("deal/runtime.js")),
            "default output build/js holds deal/runtime.js");
        check(Files.exists(defaultOut.resolve("std/console.js")),
            "default output build/js holds std/console.js");
        check(!Files.exists(defaultOut.resolve("cli_js_main.lua")),
            "no .lua artifact under the js backend");

        // The deployed entry runs under node (main(): null exits 0) —
        // a node-dependent sub-check guarded like every node semantic
        // case: node unavailability skips it, never fails it.
        if (nodeAvailable) {
            ProcessBuilder node = new ProcessBuilder("node", "cli_js_main.js");
            node.directory(defaultOut.toFile());
            node.redirectErrorStream(true);
            Process np = node.start();
            String nout = new String(np.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8).trim();
            int nrc = np.waitFor();
            check(nrc == 0, "node entry run exits 0: " + nout);
        } else {
            skipNode("CLI --backend js default-output node run");
        }

        deleteDir(proj);
    }

    // =========================================================================
    // Emission pins (js-backend-architecture Verification 2)
    // =========================================================================

    private static void testModuleShapeRoot() {
        System.out.println("-- Module shape: root module --");

        JsBackend.JsCodegenResult res = generate("""
            import * as console from "std/console"
            function helper(x: int): int { return x; }
            export function test(): int { return helper(1); }
            """, "shape-root", "main", Map.of(), Map.of(), false);
        check(res != null && !res.hasErrors(), "shape-root codegen clean: "
            + (res == null ? "<null>" : res.diagnostics()));
        if (res == null || res.hasErrors()) return;

        String js = res.source();
        check(js.contains("\"use strict\";"), "strict-mode header");
        check(js.contains("const $require = require; const $module = module; "
            + "const $exports = exports;"),
            "the $require/$module/$exports CommonJS capture");
        check(js.contains("const $rt = $require(\"./deal/runtime\");"),
            "root module requires ./deal/runtime");
        check(js.contains("const int = $rt.function(\"(number)->int\", "
            + "(v, $file, $line, $column) => $rt.intConvert(v, $file, $line, $column));"),
            "the int intrinsic header wrapper with sig (number)->int");
        check(js.contains("const number = $rt.function(\"(int)->number\", "
            + "(v, $file, $line, $column) => $rt.numberConvert(v, $file, $line, $column));"),
            "the number intrinsic header wrapper with sig (int)->number");
        check(js.contains("const $ErrorDefaults = () => ({ [\"code\"]: \"\", "
            + "[\"message\"]: \"\" });"),
            "the header-synthesized $ErrorDefaults thunk");
        check(js.contains("const Error$new = (provided, $file, $line, $column) => "
            + "$rt.makeClass(\"Error\", \"@$builtin/Error\", $ErrorDefaults, provided, "
            + "$file, $line, $column);"),
            "the header-synthesized Error$new closure (canonical identity "
                + "@$builtin/Error)");
        check(js.contains("const Error$meta = { $kind: \"class\", "
            + "$classname: \"@$builtin/Error\" };"),
            "the header-synthesized Error$meta META pair "
                + "(canonical @$builtin/Error identity)");
        check(!js.contains("$rt.setProp($exports, \"$ErrorDefaults\""),
            "$ErrorDefaults stays module-private (never exported)");
        check(!js.contains("$rt.setProp($exports, \"Error$new\""),
            "Error$new stays module-private (never exported)");
        check(js.contains("const console = $require(\"./std/console\");"),
            "spec-stdlib import binds through the relative require");
        check(js.contains("let helper;") && js.contains("let test;"),
            "predeclared let bindings for module functions");
        check(js.contains("$rt.setProp($exports, \"test\", test);"),
            "own-property-safe export assignment with the raw key");
        check(!js.contains("module.exports"),
            "no bare module.exports spelling");
    }

    private static void testModuleShapeNestedRequires() {
        System.out.println("-- Module shape: nested relative requires --");

        JsBackend.JsCodegenResult nested = generate("""
            import * as strings from "std/string"
            export function test(): int { return strings.length("x"); }
            """, "shape-nested", "app.main", Map.of(), Map.of(), false);
        check(nested != null && !nested.hasErrors(), "nested codegen clean");
        if (nested == null || nested.hasErrors()) return;
        check(nested.source().contains("const $rt = $require(\"../deal/runtime\");"),
            "app/main.js requires ../deal/runtime");
        check(nested.source().contains("const strings = $require(\"../std/string\");"),
            "nested module requires ../std/string");

        JsBackend.JsCodegenResult deep = generate("""
            export function test(): int { return 1; }
            """, "shape-deep", "app.sub.main", Map.of(), Map.of(), false);
        check(deep != null && !deep.hasErrors(), "deep nested codegen clean");
        if (deep == null || deep.hasErrors()) return;
        check(deep.source().contains("const $rt = $require(\"../../deal/runtime\");"),
            "app/sub/main.js requires ../../deal/runtime");

        // Project imports: sibling and nested-target relative specifiers.
        JsBackend.JsCodegenResult proj = generate("""
            import * as a from "./sub/util"
            import * as b from "../lib"
            export function test(): int { return 1; }
            """, "shape-proj", "app.main",
            Map.of("./sub/util", "app.sub.util", "../lib", "lib"),
            Map.of(), false);
        check(proj != null && !proj.hasErrors(), "project-import codegen clean");
        if (proj == null || proj.hasErrors()) return;
        check(proj.source().contains("const a = $require(\"./sub/util\");"),
            "app/main importing app.sub.util emits ./sub/util");
        check(proj.source().contains("const b = $require(\"../lib\");"),
            "app/main importing root lib emits ../lib");
    }

    private static void testReservedWordTranslation() {
        System.out.println("-- Reserved-word translation (binding positions) --");

        JsBackend.JsCodegenResult res = generate("""
            class Holder {
              static: int = 0;
            }
            export function test(): int {
              let static: int = 1;
              let eval: int = 2;
              let h: Holder = { static: 3 };
              return static + eval + h.static;
            }
            """, "reserved");
        check(res != null && !res.hasErrors(), "reserved-word codegen clean: "
            + (res == null ? "<null>" : res.diagnostics()));
        if (res == null || res.hasErrors()) return;

        String js = res.source();
        check(js.contains("let static$ = "), "let static binds as static$");
        check(js.contains("let eval$ = "), "let eval binds as eval$");
        check(js.contains("static$"), "static$ references emit translated");
        check(js.contains("h.static"), "property position keeps the raw key");
        check(js.contains("[\"static\"]: 3"), "provided literal uses the raw computed key");
        check(js.contains("[\"static\"]: 0"), "defaults thunk uses the raw computed key");
        check(js.contains("$rt.setProp($exports, \"test\", test);"),
            "export key stays raw");
    }

    private static void testWrapperShapeAndDescriptors() {
        System.out.println("-- Wrapper shapes and exact canonical descriptors --");

        JsBackend.JsCodegenResult res = generate("""
            class User { name: string = ""; }
            function add(a: int, b: int): int { return a + b; }
            function first(xs: int[]): int { return xs[0]; }
            function wrap(u: User): User { return u; }
            function maybe(s: string | null): string | null { return s; }
            function maybeUser(u: User | null): User | null { return u; }
            function go(f: (x: int) => int, v: int): int { return f(v); }
            async function aget(): int { return 1; }
            export function test(): int { return add(1, 2); }
            """, "wrappers");
        check(res != null && !res.hasErrors(), "wrapper codegen clean: "
            + (res == null ? "<null>" : res.diagnostics()));
        if (res == null || res.hasErrors()) return;

        String js = res.source();
        check(js.contains("add = $rt.function(\"(int,int)->int\", "
            + "function add$f(a, b, $file, $line, $column) {"),
            "add wrapper with the exact (int,int)->int descriptor");
        check(js.contains("$rt.checkInt(a, $file, $line, $column);"),
            "entry parameter check with the forwarded span");
        check(js.contains("$rt.checkInt(b, $file, $line, $column);"),
            "second parameter check in parameter order");
        check(js.contains("first = $rt.function(\"([int])->int\""),
            "canonical array descriptor [int] in the wrapper sig "
                + "(the legacy int[] spelling is gone)");
        check(js.contains("wrap = $rt.function(\"(@Main/User)->@Main/User\""),
            "canonical class atoms module-qualified (@Main/User)");
        check(js.contains("maybe = $rt.function(\"(?string)->?string\""),
            "canonical nullable descriptors (?string — the legacy "
                + "T|null form is gone)");
        check(js.contains("maybeUser = $rt.function(\"(?@Main/User)->?@Main/User\""),
            "canonical nullable class atoms (?@Main/User-shaped ?D)");
        check(js.contains("go = $rt.function(\"((int)->int,int)->int\""),
            "function-typed parameters carry their descriptor");
        check(js.contains("aget = $rt.function(\"async()->int\", "
            + "async function aget$f("),
            "async wrapper carries the exact async(...) -> D sig");
        check(!js.contains("|null"),
            "no legacy |null spelling anywhere in the artifact");
        check(!js.contains("\"int[]\""),
            "no legacy int[] descriptor spelling anywhere in the artifact");
    }

    private static void testCheckSiteDescriptorPins() {
        System.out.println("-- checkType call-site descriptors (canonical text) --");

        JsBackend.JsCodegenResult res = generate("""
            class User { name: string = ""; }
            export function test(): int {
              let t: table = {};
              let b: int[] = t.arr;
              let a: int = t.n;
              let m: int | null = t.m;
              let u: User | null = t.u;
              let c: User = t.c;
              let g: (x: int) => int = t.g;
              return a;
            }
            """, "checksites");
        check(res != null && !res.hasErrors(), "check-site codegen clean: "
            + (res == null ? "<null>" : res.diagnostics()));
        if (res == null || res.hasErrors()) return;

        String js = res.source();
        check(js.contains("$rt.checkNullable(\"int\", t.get(\"m\")"),
            "nullable int table read checks with the canonical int inner");
        check(js.contains("$rt.checkNullable(\"@Main/User\", t.get(\"u\")"),
            "nullable class-typed table read checks with the canonical "
                + "class atom inner");
        check(js.contains("$rt.checkType(\"@Main/User\", t.get(\"c\")"),
            "class-typed table read checks with the canonical @Main/User atom");
        check(js.contains("$rt.checkType(\"(int)->int\", t.get(\"g\")"),
            "function-typed table read checks with the canonical (int)->int "
                + "descriptor");
        check(js.contains("$rt.checkArray(\"[int]\", t.get(\"arr\")"),
            "array-typed table read routes through checkArray with [int]");
    }

    private static void testSpanParameterCollisionEmission() {
        System.out.println("-- Span parameters: $file/$line/$column (collision-free) --");

        JsBackend.JsCodegenResult res = generate("""
            function f(file: string, line: int, column: string): int { return line; }
            export function test(): int { return f("a", 3, "b"); }
            """, "spancollide");
        check(res != null && !res.hasErrors(), "span-collision codegen clean: "
            + (res == null ? "<null>" : res.diagnostics()));
        if (res == null || res.hasErrors()) return;

        String js = res.source();
        check(js.contains("function f$f(file, line, column, $file, $line, $column) {"),
            "user parameters file/line/column plus the $-prefixed span triple "
                + "(duplicate-free strict-mode parameter list)");
        check(!js.contains("function f$f(file, line, column, file, line, column)"),
            "no duplicate bare span parameters in the same parameter list");
        check(js.contains("$rt.checkString(file, $file, $line, $column);"),
            "parameter checks forward the $-prefixed span");
        check(js.contains("$rt.checkInt(line, $file, $line, $column);"),
            "the line parameter checks with the forwarded span");
    }

    private static void testClassEmissionPins() {
        System.out.println("-- Class artifacts: defaults thunks, META, hidden C$new exports --");

        JsBackend.JsCodegenResult res = generate("""
            class Point {
              x: int = 10;
              nick?: string | null;
            }
            class Hidden { v: int = 0; }
            export class Public {
              y: int = 0;
            }
            export function test(): int { return 1; }
            """, "classpins");
        check(res != null && !res.hasErrors(), "class-pins codegen clean: "
            + (res == null ? "<null>" : res.diagnostics()));
        if (res == null || res.hasErrors()) return;

        String js = res.source();
        check(js.contains("Point$new = (provided, $file, $line, $column) => "
            + "$rt.makeClass(\"Point\", \"@Main/Point\", "
            + "() => ({ [\"x\"]: 10, [\"nick\"]: $rt.MISSING }), "
            + "provided, $file, $line, $column);"),
            "Point$new with the computed-key defaults thunk (MISSING optionals)");
        check(js.contains("Point$meta = { $kind: \"class\", "
            + "$classname: \"@Main/Point\" };"),
            "inline META pair with the module-qualified identity");
        check(js.contains("$rt.setProp($exports, \"Public\", Public$meta);"),
            "exported class exports the META under the raw key");
        check(js.contains("$rt.setProp($exports, \"Public$new\", Public$new);"),
            "exported class exports the hidden C$new closure");
        check(!js.contains("$rt.setProp($exports, \"Point$new\""),
            "non-exported class keeps its C$new module-private");
        check(!js.contains("$rt.setProp($exports, \"Hidden$new\""),
            "non-exported class keeps its C$new module-private");
    }

    private static void testEntryShimAndE6004() {
        System.out.println("-- Entry shim and the E6004 backstop --");

        JsBackend.JsCodegenResult entry = generate("""
            export function main(): null { return null; }
            """, "entry", "main", Map.of(), Map.of(), true);
        check(entry != null && !entry.hasErrors(), "entry codegen clean: "
            + (entry == null ? "<null>" : entry.diagnostics()));
        if (entry == null || entry.hasErrors()) return;
        check(entry.source().contains("if ($require.main === $module) {"),
            "entry shim guards on $require.main === $module");
        check(entry.source().contains("$exports.main.$f();"),
            "entry shim invokes the exported main wrapper");
        check(entry.source().contains("$rt.reportUncaught("),
            "entry shim routes uncaught errors through $rt.reportUncaught");
        check(entry.source().contains("$err.file !== $rt.undefined"),
            "entry shim's location check spells the runtime nil-equivalent");

        JsBackend.JsCodegenResult noMain = generate("""
            export function run(): int { return 1; }
            """, "entry-nomain", "main", Map.of(), Map.of(), true);
        check(noMain != null && noMain.hasErrors(),
            "an entry without main(): null carries an error");
        if (noMain != null) {
            check(noMain.diagnostics().stream().anyMatch(
                    d -> "E6004".equals(d.code())),
                "E6004 backstop diagnostic: " + noMain.diagnostics());
            check(!noMain.source().contains("$require.main === $module"),
                "no entry shim emitted for the rejected entry");
        }

        JsBackend.JsCodegenResult notEntry = generate("""
            export function main(): null { return null; }
            """, "entry-notentry", "main", Map.of(), Map.of(), false);
        check(notEntry != null && !notEntry.hasErrors(),
            "non-entry main compiles clean");
        if (notEntry != null) {
            check(!notEntry.source().contains("$require.main === $module"),
                "non-entry modules never emit the shim");
        }
    }

    private static void testSourceLocationArguments() {
        System.out.println("-- Source-location arguments on checks and .$f calls --");

        JsBackend.JsCodegenResult res = generate("""
            export function test(): int {
              let y: int = int(3.0);
              return y;
            }
            """, "loc");
        check(res != null && !res.hasErrors(), "loc codegen clean");
        if (res == null || res.hasErrors()) return;

        String js = res.source();
        check(js.contains("int.$f(3.0, \"jstest-loc.deal\", 2, 16)"),
            "the conversion call carries the literal call-site span");
        check(js.contains("return $rt.checkInt(y, \"jstest-loc.deal\", 3, 10);"),
            "the return check carries the literal return-site span");
        check(js.contains("\"jstest-loc.deal\""),
            "the .deal source file appears as a literal argument");
    }

    private static void testOwnPropertySafeProtoEmission() {
        System.out.println("-- Own-property-safe __proto__ emission --");

        JsBackend.JsCodegenResult res = generate("""
            class P {
              __proto__?: int;
            }
            export function __proto__(): int { return 1; }
            export function test(): int {
              let p: P = { __proto__: 7 };
              if (!has(p.__proto__)) { return 0; }
              p.__proto__ = 9;
              return p.__proto__;
            }
            """, "protoemit");
        check(res != null && !res.hasErrors(), "proto emission codegen clean: "
            + (res == null ? "<null>" : res.diagnostics()));
        if (res == null || res.hasErrors()) return;

        String js = res.source();
        check(js.contains("[\"__proto__\"]: $rt.MISSING"),
            "defaults thunk spells the __proto__ field as a computed key");
        check(js.contains("P$new({ [\"__proto__\"]: 7 }, "),
            "provided object literal spells __proto__ as a computed key");
        check(js.contains("$rt.setProp(p, \"__proto__\", "),
            "class field write routes through $rt.setProp");
        check(js.contains("$rt.has(p, \"__proto__\")"),
            "has() reads the field by raw name");
        check(js.contains("return $rt.checkInt($rt.optRead(p.__proto__), "),
            "optional field reads route through optRead with the raw property name");
        check(js.contains("$rt.setProp($exports, \"__proto__\", __proto__);"),
            "the __proto__ export writes own-property-safely with the raw key");
        check(js.contains("let __proto__;"),
            "the __proto__ function predeclares its plain binding");

        // Import-side read: a second module importing the exporter reads
        // the raw member through the module alias.
        Map<String, Map<String, Type>> modules = new LinkedHashMap<>();
        modules.put("./proto_lib", Map.of("__proto__",
            Types.func(List.of(), Type.Int.INSTANCE)));
        Frontend f = compileFrontend("""
            import * as pl from "./proto_lib"
            export function test(): int { return pl.__proto__(); }
            """, "jstest-protoimport.deal",
            new FixedModuleResolver(modules));
        check(f.errors().isEmpty(), "proto import frontend clean: " + f.errors());
        if (f.errors().isEmpty()) {
            JsBackend.JsCodegenResult imp = JsBackend.generate(f.program(),
                f.checkResult(), "jstest-protoimport.deal", "main",
                Map.of("./proto_lib", "proto_lib"), Map.of(), false);
            check(!imp.hasErrors(), "proto import codegen clean: " + imp.diagnostics());
            check(imp.source().contains("const pl = $require(\"./proto_lib\");"),
                "project import requires the relative module");
            check(imp.source().contains("pl.__proto__.$f("),
                "import-side read keeps the raw property position");
        }
    }

    private static void testErrorEmissionPins() {
        System.out.println("-- Error emission pins: literal default filling, "
            + "contextual construction, rethrow --");

        JsBackend.JsCodegenResult res = generate("""
            function boomCode(): null {
              if (true) { throw { code: "E_CODE" }; }
              return null;
            }
            function boomMsg(): null {
              if (true) { throw { message: "m" }; }
              return null;
            }
            function produce(): Error { return { message: "ret" }; }
            function rethrow(): null {
              try {
                throw { code: "C", message: "m" };
              } catch (e) {
                throw e;
              }
              return null;
            }
            export function test(): null {
              boomCode();
              boomMsg();
              produce();
              rethrow();
              return null;
            }
            """, "errpins");
        check(res != null && !res.hasErrors(), "error pins codegen clean: "
            + (res == null ? "<null>" : res.diagnostics()));
        if (res == null || res.hasErrors()) return;

        String js = res.source();
        check(js.contains("throw $rt.errorValue(\"E_CODE\", \"\", "
            + "\"jstest-errpins.deal\","),
            "a literal throw with an omitted message emits the \"\" default");
        check(js.contains("throw $rt.errorValue(\"\", \"m\", "
            + "\"jstest-errpins.deal\","),
            "a literal throw with an omitted code emits the \"\" default");
        check(js.contains("Error$new({ [\"message\"]: \"ret\" }, "
            + "\"jstest-errpins.deal\","),
            "a contextual Error literal constructs through Error$new");
        check(js.contains("throw $rt.errorValue(\"C\", \"m\", "
            + "\"jstest-errpins.deal\","),
            "the inner literal throw emits the errorValue form");
        check(js.contains("throw e;"),
            "the non-literal rethrow emits throw e; verbatim");
        check(js.contains("const e = $rt.reifyError($e);"),
            "the catch binding reifies through $rt.reifyError");
        check(!js.contains("throw $rt.errorValue(e"),
            "the rethrow never rewraps through errorValue");
    }

    private static void testArrayElementDeleteEmission() {
        System.out.println("-- Array-element delete emission pin --");

        JsBackend.JsCodegenResult res = generate("""
            export function test(): int {
              let xs: int[] = [1, 2, 3];
              delete xs[1];
              return xs.length;
            }
            """, "delemit");
        check(res != null && !res.hasErrors(), "delete emission codegen clean: "
            + (res == null ? "<null>" : res.diagnostics()));
        if (res == null || res.hasErrors()) return;

        String js = res.source();
        check(js.contains("const $idx = $rt.checkInt(1, "
            + "\"jstest-delemit.deal\", 3, 10);"),
            "the delete index crosses $rt.checkInt");
        check(js.contains("if ($idx < 0 || $idx > $arr.length) { "
            + "$rt.fail(\"E8002\", \"array index out of bounds\", "
            + "\"jstest-delemit.deal\", 3, 10); }"),
            "the E8002 bounds gate precedes the write");
        check(js.contains("if ($idx !== $arr.length) { $arr[$idx] = "
            + "$rt.undefined; }"),
            "the nil-out write is guarded by $idx !== $arr.length "
                + "(no write emitted at i === length)");
        check(!js.replace("if ($idx !== $arr.length) { $arr[$idx] = "
            + "$rt.undefined; }", "").contains("$arr[$idx] = "),
            "the only element write is the guarded nil-out");
        check(js.contains("$rt.undefined"),
            "the nil-equivalent write spells the runtime member");
        check(!js.replace("$rt.undefined", "").contains("undefined"),
            "no bare undefined spelling anywhere in the artifact");
    }

    private static void testHostGlobalHygiene() {
        System.out.println("-- Host-global hygiene: $rt.undefined, never bare undefined --");

        // (a) Without a user binding named undefined, the generated source
        //     never spells the bare host-global at all.
        JsBackend.JsCodegenResult plain = generate("""
            export function test(): int {
              let xs: int[] = [1, 2, 3];
              delete xs[1];
              let n: int = 0;
              for (let x: int of xs) { n = n + 1; }
              return n;
            }
            """, "hygiene");
        check(plain != null && !plain.hasErrors(), "hygiene codegen clean");
        if (plain != null && !plain.hasErrors()) {
            check(plain.source().contains("$arr[$idx] = $rt.undefined;"),
                "the delete write spells $rt.undefined");
            check(plain.source().contains("if ($iter[$i] === $rt.undefined) { break; }"),
                "the for-of nil-stop comparison spells $rt.undefined");
            String residual = plain.source().replace("$rt.undefined", "");
            if (residual.contains("undefined")) {
                int idx = residual.indexOf("undefined");
                System.err.println("RESIDUAL-undefined at " + idx + ": ..."
                    + residual.substring(Math.max(0, idx - 60),
                        Math.min(residual.length(), idx + 60)) + "...");
            }
            check(!residual.contains("undefined"),
                "generated source never spells the bare host-global undefined");
        }

        // (b) With a checker-accepted user binding literally named
        //     undefined, the nil-equivalent write/compare sites still
        //     spell $rt.undefined, and the program runs under node with
        //     the binding intact (the shadow cannot break them).
        JsBackend.JsCodegenResult shadow = generate("""
            export function test(): int {
              let undefined: int = 7;
              let xs: int[] = [1, 2, 3];
              delete xs[1];
              let n: int = 0;
              for (let x: int of xs) { n = n + 1; }
              return undefined + xs.length + n;
            }
            """, "hygiene-shadow");
        check(shadow != null && !shadow.hasErrors(),
            "shadow hygiene codegen clean: "
                + (shadow == null ? "<null>" : shadow.diagnostics()));
        if (shadow != null && !shadow.hasErrors()) {
            String js = shadow.source();
            check(js.contains("let undefined = $rt.checkInt(7, "),
                "the user binding named undefined emits as declared");
            check(js.contains("$arr[$idx] = $rt.undefined;"),
                "the delete write spells $rt.undefined despite the binding");
            check(js.contains("if ($iter[$i] === $rt.undefined) { break; }"),
                "the for-of nil-stop spells $rt.undefined despite the binding");
            check(!js.contains("= undefined;") && !js.contains("=== undefined")
                    && !js.contains("!== undefined"),
                "no write/compare site spells the bare host-global");
            if (nodeAvailable) {
                try {
                    NodeResult run = runDealNode(shadowTestSource(), "hygiene-run");
                    check(run.exitCode() == 0 && run.output().equals("11"),
                        "the shadowed program runs under node: 7 + 3 + 1 = 11, got "
                            + run.output() + " (exit " + run.exitCode() + ")");
                } catch (Exception e) {
                    fail("hygiene node run: " + e.getMessage());
                }
            } else {
                skipNode("host-global hygiene node run");
            }
        }
    }

    private static String shadowTestSource() {
        return """
            export function test(): int {
              let undefined: int = 7;
              let xs: int[] = [1, 2, 3];
              delete xs[1];
              let n: int = 0;
              for (let x: int of xs) { n = n + 1; }
              return undefined + xs.length + n;
            }
            """;
    }

    // =========================================================================
    // Rejection pins (the testUnsupportedConstructsRejected analog)
    // =========================================================================

    private static void testUnsupportedConstructsRejected() {
        System.out.println("-- Unsupported constructs → E6000/E6003 --");

        // @jsonable on an exported class: E6000 at the declaration site.
        JsBackend.JsCodegenResult jsonable = generate("""
            // @jsonable
            export class U {
              name: string = "";
            }
            export function test(): int { return 1; }
            """, "rej-jsonable");
        check(jsonable != null, "jsonable module generated");
        if (jsonable != null) {
            check(jsonable.hasErrors(), "hasErrors() holds for @jsonable");
            check(jsonable.diagnostics().stream().anyMatch(d ->
                    "E6000".equals(d.code())
                        && "error".equals(d.severity())
                        && d.message().contains("@jsonable")
                        && d.range().startLine() == 2),
                "@jsonable rejection is E6000 naming the construct at the "
                    + "declaration site: " + jsonable.diagnostics());
        }

        // Nested class declaration: E6000 at the declaration site.
        JsBackend.JsCodegenResult nested = generate("""
            export function test(): int {
              class Inner { v: int = 0; }
              return 1;
            }
            """, "rej-nested");
        check(nested != null && nested.hasErrors(), "nested class rejected");
        if (nested != null) {
            check(nested.diagnostics().stream().anyMatch(d ->
                    "E6000".equals(d.code())
                        && d.message().contains("nested class declarations")
                        && d.range().startLine() == 2),
                "nested-class rejection is E6000 at the declaration site: "
                    + nested.diagnostics());
        }

        // Host ABI (non-stdlib declaration-file import): E6000 at the
        // import statement.
        JsBackend.JsCodegenResult host = generate("""
            import * as h from "./hostmod"
            export function test(): int { return 1; }
            """, "rej-host", "Main", Map.of(),
            Map.of("./hostmod", Map.<String, Type>of()), false);
        check(host != null && host.hasErrors(), "host-module import rejected");
        if (host != null) {
            check(host.diagnostics().stream().anyMatch(d ->
                    "E6000".equals(d.code())
                        && d.message().contains("host-module imports")
                        && d.range().startLine() == 1),
                "host-ABI rejection is E6000 at the import statement: "
                    + host.diagnostics());
        }

        // @extern-c import: E6003 with the EXACT current detail text
        // (the JSON-slice-corpus rejection-detail pin migrated here,
        // ISSUE-0359; v12-three-backend-conformance-corpus C1/C6:
        // backend-internal rejection detail texts are never corpus
        // fields). ISSUE-0277 migrates the production emission to E6006
        // and must update this pin together with that change — this unit
        // test pins the CURRENT emission (E6003 + the current text at
        // deal/codegen/js/JsBackend.java:799) and must not pin E6006
        // before the backend epic lands it.
        String externDetailText = "JavaScript backend: @extern-c imports "
            + "are not supported (FFI_UNSUPPORTED_BACKEND, "
            + "ISSUE-0169 skeleton)";
        JsBackend.JsCodegenResult extern = generate("""
            // @extern-c
            import * as ffi from "myffi"
            export function test(): int { return 1; }
            """, "rej-extern", "Main", Map.of(),
            Map.of("myffi", Map.<String, Type>of()), false);
        check(extern != null && extern.hasErrors(), "@extern-c import rejected");
        if (extern != null) {
            check(extern.diagnostics().stream().anyMatch(d ->
                    "E6003".equals(d.code())
                        && "error".equals(d.severity())
                        && externDetailText.equals(d.message())
                        && d.range().startLine() == 2),
                "@extern-c rejection is E6003 with the exact current detail "
                    + "text at the import statement: " + extern.diagnostics());
        }

        // The defensive bytes arms (the frontend rejects the program; the
        // backend still reports exactly one E6000 per site).
        JsBackend.JsCodegenResult bytesType = generate("""
            export function test(): null {
              let b: bytes = 0;
              return null;
            }
            """, "rej-bytes");
        check(bytesType != null, "bytes-type module generated");
        if (bytesType != null) {
            check(bytesType.hasErrors(), "hasErrors() holds for bytes type");
            check(bytesType.diagnostics().stream().filter(d ->
                    "E6000".equals(d.code())
                        && d.message().contains("bytes is not supported"))
                    .count() == 1,
                "exactly one E6000 at the bytes type use site: "
                    + bytesType.diagnostics());
        }
        JsBackend.JsCodegenResult bytesCall = generate("""
            export function test(): int { return bytes(3); }
            """, "rej-bytescall");
        check(bytesCall != null, "bytes-call module generated");
        if (bytesCall != null) {
            check(bytesCall.hasErrors(), "hasErrors() holds for bytes(n)");
            check(bytesCall.diagnostics().stream().filter(d ->
                    "E6000".equals(d.code())
                        && d.message().contains("bytes is not supported"))
                    .count() == 1,
                "exactly one E6000 at the bytes(n) call site: "
                    + bytesCall.diagnostics());
        }
    }

    private static void testSourceMapSidecarsEmitted() throws Exception {
        System.out.println("-- Orchestrator: --source-map sidecars, warning retired --");

        writeFile("src/sm_main.deal",
            "export function main(): null { return null; }\n"
            + "export function run(): int { return 1; }\n");
        Path entryFile = tmpDir.resolve("src/sm_main.deal").toAbsolutePath();
        Path outputDir = tmpDir.resolve("build/sm_js");
        List<Path> roots = List.of(tmpDir.resolve("src").toAbsolutePath());

        PrintStream originalErr = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
            CompilationOrchestrator orchestrator = new CompilationOrchestrator(
                entryFile, outputDir, false, false, true, Backend.JS,
                (DealConfig) null, roots,
                Path.of(".").toAbsolutePath().normalize());
            boolean success = orchestrator.compile();
            check(success, "explicit --source-map still compiles");
        } finally {
            System.err.flush();
            System.setErr(originalErr);
        }
        String stderrText = captured.toString(StandardCharsets.UTF_8);
        check(!stderrText.contains("source-map"),
            "the explicit --source-map run prints no warning (the warning "
                + "retired): " + stderrText);

        // The explicit --source-map run writes one sidecar per clean
        // module, next to the emitted artifact, in the spec format.
        Path artifact = outputDir.resolve("sm_main.js");
        Path sidecar = outputDir.resolve("sm_main.deal.map.json");
        check(Files.exists(artifact), "the sm_main.js artifact is written");
        check(Files.exists(sidecar),
            "the .deal.map.json sidecar is written next to the artifact");
        if (Files.exists(sidecar)) {
            String json = Files.readString(sidecar);
            check(json.contains("\"version\": 1"), "sidecar version is 1");
            check(json.contains("\"source\": \"src/sm_main.deal\""),
                "sidecar source is the project-relative .deal path: " + json);
            check(json.contains("\"generated\": \"build/sm_js/sm_main.js\""),
                "sidecar generated is the project-relative .js artifact "
                    + "path: " + json);
            check(json.contains("\"mappings\""), "sidecar carries mappings");
        }

        // Without the effective flag, no warning fires and no sidecar is
        // written.
        Path outputDir2 = tmpDir.resolve("build/sm_js2");
        ByteArrayOutputStream captured2 = new ByteArrayOutputStream();
        try {
            System.setErr(new PrintStream(captured2, true, StandardCharsets.UTF_8));
            CompilationOrchestrator orchestrator = new CompilationOrchestrator(
                entryFile, outputDir2, false, false, false, Backend.JS,
                (DealConfig) null, roots,
                Path.of(".").toAbsolutePath().normalize());
            boolean success = orchestrator.compile();
            check(success, "no --source-map compiles");
        } finally {
            System.err.flush();
            System.setErr(originalErr);
        }
        check(!captured2.toString(StandardCharsets.UTF_8).contains("source-map"),
            "no source-map warning without the explicit flag: "
                + captured2.toString(StandardCharsets.UTF_8));
        check(!Files.exists(outputDir2.resolve("sm_main.deal.map.json")),
            "no .deal.map.json sidecar without the effective flag");
    }

    // =========================================================================
    // Node semantic cases (js-backend-runtime Verification 1-9 home)
    // =========================================================================

    private static void testIntArithmeticEdgeCodes() throws Exception {
        System.out.println("-- Node: int arithmetic edge codes --");
        if (!nodeAvailable) { skipNode("int arithmetic edge codes"); return; }

        NodeResult pow = runDealNode(
            "export function test(): int { return 2 ** 10000; }",
            "int-pow-overflow");
        check(pow.exitCode() == 1 && pow.output().contains("DEAL_ERROR_CODE: E8001")
                && pow.output().contains("expected int, got infinity"),
            "2 ** 10000 → E8001 'expected int, got infinity' (checkInt order "
                + "before the finite-range arm), exit 1: " + pow.output());

        NodeResult range = runDealNode(
            "export function test(): int { return int(1.0e300); }",
            "int-finite-range");
        check(range.exitCode() == 1 && range.output().contains("DEAL_ERROR_CODE: E8004")
                && range.output().contains("int out of safe range"),
            "finite out-of-range → E8004 'int out of safe range', exit 1: "
                + range.output());

        NodeResult divz = runDealNode(
            "export function test(): int { return 5 / 0; }",
            "int-div-zero");
        check(divz.exitCode() == 1 && divz.output().contains("DEAL_ERROR_CODE: E8005")
                && divz.output().contains("integer division by zero"),
            "int division by zero → E8005, exit 1: " + divz.output());

        NodeResult negexp = runDealNode(
            "export function test(): int { return 2 ** -1; }",
            "int-neg-exp");
        check(negexp.exitCode() == 1 && negexp.output().contains("DEAL_ERROR_CODE: E8006")
                && negexp.output().contains("integer exponent must be non-negative"),
            "negative exponent → E8006, exit 1: " + negexp.output());

        NodeResult boundary = runDealNode(
            "export function test(): int { return 9007199254740991; }",
            "int-safe-max");
        check(boundary.exitCode() == 0 && boundary.output().equals("9007199254740991"),
            "the ±(2^53-1) safe-range boundary passes: " + boundary.output());

        NodeResult trunc = runDealNode(
            "export function test(): int { return -7 % 3; }",
            "int-mod-trunc");
        check(trunc.exitCode() == 0 && trunc.output().equals("-1"),
            "int % int is truncated (-7 % 3 === -1): " + trunc.output());
    }

    private static void testNumModFloored() throws Exception {
        System.out.println("-- Node: floored number % number --");
        if (!nodeAvailable) { skipNode("floored number %"); return; }

        NodeResult a = runDealNode(
            "export function test(): number { return -5.0 % 2.0; }",
            "nummod-a");
        check(a.exitCode() == 0 && a.output().equals("1"),
            "-5.0 % 2.0 === 1 (floored): " + a.output());
        NodeResult b = runDealNode(
            "export function test(): number { return 5.0 % -2.0; }",
            "nummod-b");
        check(b.exitCode() == 0 && b.output().equals("-1"),
            "5.0 % -2.0 === -1 (floored): " + b.output());
        NodeResult c = runDealNode(
            "export function test(): number { return -5.0 % -2.0; }",
            "nummod-c");
        check(c.exitCode() == 0 && c.output().equals("-1"),
            "-5.0 % -2.0 === -1 (floored): " + c.output());
        NodeResult d = runDealNode(
            "export function test(): number { return 5.0 % 2.0; }",
            "nummod-d");
        check(d.exitCode() == 0 && d.output().equals("1"),
            "5.0 % 2.0 === 1 (floored): " + d.output());
    }

    private static void testScalarStringOps() throws Exception {
        System.out.println("-- Node: scalar-value string ops --");
        if (!nodeAvailable) { skipNode("scalar string ops"); return; }

        NodeResult run = runDealNode("""
            import * as strings from "std/string"
            export function test(): int {
              let s: string = "𝒜B";
              let n: int = 0;
              for (let c: string of s) { n = n + 1; }
              let len: int = strings.length(s);
              let sub: string = strings.substring(s, 0, 1);
              let parts: string[] = strings.split(s, "");
              if (n === 2 && len === 2 && sub === "𝒜" && parts.length === 2) {
                if ("B" < "𝒜" && "𝒜" > "B") { return 1; }
              }
              return 0;
            }
            """, "scalar");
        check(run.exitCode() == 0 && run.output().equals("1"),
            "supplementary-char length/substring/split/for-of/ordering: "
                + run.output());
    }

    private static void testOptionalThreeState() throws Exception {
        System.out.println("-- Node: optional three-state --");
        if (!nodeAvailable) { skipNode("optional three-state"); return; }

        NodeResult run = runDealNode("""
            class Tri { field?: int | null; }
            export function test(): int {
              let missing: Tri = {};
              if (has(missing.field)) { return 0; }
              if (missing.field !== null) { return 0; }
              let explicitNull: Tri = { field: null };
              if (!has(explicitNull.field) || explicitNull.field !== null) { return 0; }
              let value: Tri = { field: 5 };
              if (!has(value.field)) { return 0; }
              let v: int | null = value.field;
              if (v !== null) {
                if (v !== 5) { return 0; }
              } else {
                return 0;
              }
              delete value.field;
              if (has(value.field)) { return 0; }
              if (value.field !== null) { return 0; }
              return 1;
            }
            """, "three-state");
        check(run.exitCode() == 0 && run.output().equals("1"),
            "missing / explicit-null / value / delete states: " + run.output());
    }

    private static void testNilEquivalentNullableReads() throws Exception {
        System.out.println("-- Node: nil-equivalent nullable reads --");
        if (!nodeAvailable) { skipNode("nil-equivalent nullable reads"); return; }

        NodeResult table = runDealNode("""
            export function test(): int {
              let t: table = {};
              t.x = "v";
              delete t.x;
              let after: string | null = t.x;
              if (after !== null) { return 0; }
              return 1;
            }
            """, "nil-table");
        check(table.exitCode() == 0 && table.output().equals("1"),
            "a deleted table field reads as null contextually: " + table.output());

        NodeResult arr = runDealNode("""
            export function test(): int {
              let xs: (int | null)[] = [];
              xs[0] = 1;
              let v: int | null = xs[9];
              if (v === null) { return 1; }
              return 0;
            }
            """, "nil-array");
        check(arr.exitCode() == 0 && arr.output().equals("1"),
            "a nullable-array out-of-bounds read yields DEAL null: " + arr.output());

        NodeResult nonNull = runDealNode(
            "export function test(): int { let xs: int[] = [1]; return xs[9]; }",
            "nil-array-nonnullable");
        check(nonNull.exitCode() == 1
                && nonNull.output().contains("DEAL_ERROR_CODE: E8001")
                && nonNull.output().contains("expected int"),
            "the same read at a non-nullable target rejects with E8001: "
                + nonNull.output());
    }

    private static void testE8010SignatureMismatch() throws Exception {
        System.out.println("-- Node: E8010 signature mismatch --");
        if (!nodeAvailable) { skipNode("E8010 signature mismatch"); return; }

        NodeResult run = runDealNode("""
            function add(a: int, b: int): int { return a + b; }
            export function test(): int {
              let t: table = {};
              t.g = add;
              let f: (x: int) => int = t.g;
              return f(1);
            }
            """, "e8010");
        check(run.exitCode() == 1 && run.output().contains("DEAL_ERROR_CODE: E8010")
                && run.output().contains(
                    "function signature mismatch: expected (int)->int, got (int,int)->int"),
            "a wrong-signature table-stored wrapper crossing a function-typed "
                + "boundary raises E8010: " + run.output());
    }

    private static void testE8007ExtraClassFieldRuntime() throws Exception {
        System.out.println("-- Node: E8007 extra class field (runtime arm) --");
        if (!nodeAvailable) { skipNode("E8007 extra class field"); return; }

        NodeResult run = runRuntimeProbe("e8007", """
            "use strict";
            const $rt = require("./deal/runtime");
            try {
              $rt.makeClass("C", "@m/C", () => ({ ["x"]: 0 }), { ["y"]: 1 }, "probe.js", 3, 1);
              console.log("NO-ERROR");
            } catch (e) {
              console.log(e.$dealCode + ": " + e.message);
            }
            """);
        check(run.exitCode() == 0
                && run.output().equals("E8007: extra field 'y' in class 'C'"),
            "makeClass rejects an extra provided field with E8007: " + run.output());
    }

    private static void testClassIdentityTagPair() throws Exception {
        System.out.println("-- Node: class identity tag pair --");
        if (!nodeAvailable) { skipNode("class identity"); return; }

        NodeResult ok = runDealNode("""
            class User { name: string = ""; }
            export function test(): int {
              let u: User = { name: "a" };
              let t: table = { item: u };
              let u2: User = t.item;
              if (u2.name !== "a") { return 0; }
              return 1;
            }
            """, "identity-ok");
        check(ok.exitCode() == 0 && ok.output().equals("1"),
            "a constructed instance passes its class-typed boundary: " + ok.output());

        NodeResult wrong = runDealNode("""
            class User { name: string = ""; }
            class Other { name: string = ""; }
            export function test(): int {
              let o: Other = { name: "b" };
              let t: table = { item: o };
              let bad: User = t.item;
              return 0;
            }
            """, "identity-wrong");
        check(wrong.exitCode() == 1
                && wrong.output().contains("DEAL_ERROR_CODE: E8001")
                && wrong.output().contains(
                    "expected instance of @Main/User, got @Main/Other"),
            "a wrong-identity instance fails the boundary with E8001 naming "
                + "both identities: " + wrong.output());

        NodeResult tags = runRuntimeProbe("tags", """
            "use strict";
            const $rt = require("./deal/runtime");
            const $v = $rt.makeClass("C", "@m/C", () => ({ ["x"]: 0 }), { ["x"]: 1 }, "probe.js", 1, 1);
            if ($v.$kind !== "class" || $v.$classname !== "@m/C") {
              console.log("BAD-TAGS");
            } else {
              $rt.checkType("@m/C", $v, "probe.js", 1, 1);
              try {
                $rt.checkType("@m/Other", $v, "probe.js", 1, 1);
                console.log("NO-ERROR");
              } catch (e) {
                console.log(e.$dealCode);
              }
            }
            """);
        check(tags.exitCode() == 0 && tags.output().equals("E8001"),
            "instances carry $kind: \"class\" + $classname; checkType('@mod/Name') "
                + "accepts the matching identity and rejects a mismatch: "
                + tags.output());
    }

    private static void testCanonicalDescriptorMatcherPins() throws Exception {
        System.out.println("-- Node: canonical descriptor matcher pins --");
        if (!nodeAvailable) { skipNode("canonical matcher pins"); return; }

        // The canonical grammar accepts ?D and [D] over ANY descriptor D —
        // function descriptors included — and compares class atoms and
        // function signatures byte-for-byte.
        NodeResult run = runRuntimeProbe("matcher", """
            "use strict";
            const $rt = require("./deal/runtime");
            let $fail = "";
            // ?D and [D] over function descriptors.
            const $f = $rt.function("(int)->int", function(x, $file, $line, $column) { return x; });
            $rt.checkType("?(int)->int", $f, "probe.js", 1, 1);
            $rt.checkType("[(int)->int]", [$f], "probe.js", 1, 1);
            // Byte-for-byte class-atom comparison.
            const $v = $rt.makeClass("C", "@m/C", () => ({ ["x"]: 0 }), { ["x"]: 1 }, "probe.js", 1, 1);
            $rt.checkType("@m/C", $v, "probe.js", 1, 1);
            try { $rt.checkType("@m/c", $v, "probe.js", 1, 1); $fail = "class-case"; } catch (e) {}
            // Differential whitespace pin: U+200B ZERO WIDTH SPACE is a
            // legal component scalar in the canonical alphabet (the Java
            // parser and the identity index forbid only 2000-200A), so
            // the class atom @a<U+200B>b/C parses and compares
            // byte-for-byte; U+200A HAIR SPACE is forbidden whitespace
            // and takes the pinned E8001 cannot-parse arm.
            const $v2 = $rt.makeClass("C", "@a\\u200Bb/C", () => ({ ["x"]: 0 }), { ["x"]: 1 }, "probe.js", 1, 1);
            $rt.checkType("@a\\u200Bb/C", $v2, "probe.js", 1, 1);
            try {
              $rt.checkType("@a\\u200Ab/C", $v2, "probe.js", 1, 1);
              $fail = "u200a-accepted";
            } catch (e) {
              if (e.$dealCode !== "E8001"
                  || e.message !== "internal: cannot parse type descriptor: @a\\u200Ab/C") {
                $fail = "u200a-arm:" + e.$dealCode + ":" + e.message;
              }
            }
            // U+0085 NEXT LINE is pinned White_Space property whitespace
            // (the Java parser, the identity index, and $CANONICAL_WS
            // all forbid it), so @a<U+0085>b/C takes the same pinned
            // E8001 cannot-parse arm.
            try {
              $rt.checkType("@a\\u0085b/C", $v2, "probe.js", 1, 1);
              $fail = "u0085-accepted";
            } catch (e) {
              if (e.$dealCode !== "E8001"
                  || e.message !== "internal: cannot parse type descriptor: @a\\u0085b/C") {
                $fail = "u0085-arm:" + e.$dealCode + ":" + e.message;
              }
            }
            // E8003 pinned array-element message at the first failing index.
            try {
              $rt.checkType("[int]", [1, "x", 3], "probe.js", 1, 1);
              $fail = "e8003";
            } catch (e) {
              if (e.$dealCode !== "E8003" || e.message !== "array element 2 type mismatch: expected int") {
                $fail = "e8003-msg:" + e.$dealCode + ":" + e.message;
              }
            }
            // E8010 pinned function-signature message on any descriptor delta.
            const $g = $rt.function("(int,int)->int", function(a, b, $file, $line, $column) { return a + b; });
            try {
              $rt.checkType("(int)->int", $g, "probe.js", 1, 1);
              $fail = "e8010";
            } catch (e) {
              if (e.$dealCode !== "E8010" || e.message !== "function signature mismatch: expected (int)->int, got (int,int)->int") {
                $fail = "e8010-msg:" + e.$dealCode + ":" + e.message;
              }
            }
            // E8004 pinned int safe-range message routes through checkInt.
            try {
              $rt.checkType("int", 2 ** 60, "probe.js", 1, 1);
              $fail = "e8004";
            } catch (e) {
              if (e.$dealCode !== "E8004" || e.message !== "int out of safe range") {
                $fail = "e8004-msg:" + e.$dealCode + ":" + e.message;
              }
            }
            // The builtin Error identity is @$builtin/Error end-to-end.
            const $err = $rt.errorValue("E_TEST", "boom", "probe.js", 1, 1);
            if ($err.$kind !== "class" || $err.$classname !== "@$builtin/Error") {
              $fail = "error-identity:" + String($err.$classname);
            }
            if ($rt.reifyError($err) !== $err) { $fail = "reify-passthrough"; }
            if ($fail === "") { console.log("CANONICAL-MATCHER-OK"); }
            else { console.log("FAIL: " + $fail); }
            """);
        check(run.exitCode() == 0 && run.output().equals("CANONICAL-MATCHER-OK"),
            "canonical ?/[D] acceptance over function descriptors, byte-for-byte "
                + "class atoms, and the pinned E8003/E8010/E8004/@$builtin/Error "
                + "surfaces: " + run.output());
    }

    private static void testLegacyDescriptorRejectionPins() throws Exception {
        System.out.println("-- Node: legacy descriptor spellings rejected (cannot-parse arm) --");
        if (!nodeAvailable) { skipNode("legacy descriptor rejection"); return; }

        NodeResult run = runRuntimeProbe("legacy-reject", """
            "use strict";
            const $rt = require("./deal/runtime");
            let $fail = "";
            const $legacy = ["int[]", "int|null", "table[]", "Error", "User",
              "?Error", "Error|null", "(int)->int[]", "(int)->int|null",
              "@Foo", "@src.models.User", "@m/c.Name", "??int", "?null",
              "[]", "(int)int", "async[int]", "@a/", "@a//b", "int..."];
            for (let $i = 0; $i < $legacy.length; $i++) {
              try {
                $rt.checkType($legacy[$i], null, "probe.js", 1, 1);
                $fail = "accepted:" + $legacy[$i];
                break;
              } catch (e) {
                if (e.$dealCode !== "E8001"
                    || e.message !== "internal: cannot parse type descriptor: " + $legacy[$i]) {
                  $fail = "wrong-arm:" + $legacy[$i] + " -> " + e.$dealCode + ": " + e.message;
                  break;
                }
              }
            }
            if ($fail === "") { console.log("LEGACY-REJECTED-OK"); }
            else { console.log("FAIL: " + $fail); }
            """);
        check(run.exitCode() == 0 && run.output().equals("LEGACY-REJECTED-OK"),
            "every legacy spelling takes the pinned E8001 cannot-parse arm "
                + "with the exact message: " + run.output());
    }

    private static void testCanonicalClassIdentityIndex() {
        System.out.println("-- Canonical identity index projections and encode --");

        Map<String, CanonicalModuleIdentity> byPath = new LinkedHashMap<>();
        byPath.put("", CanonicalModuleIdentity.BuiltinModule.INSTANCE);
        byPath.put("app.user",
            new CanonicalModuleIdentity.ProjectModule(
                new ProjectModuleIdentity("src", "/tmp/src", List.of("app"))));
        byPath.put("orch_main",
            new CanonicalModuleIdentity.ProjectModule(
                new ProjectModuleIdentity("src", "/tmp/src", List.of())));
        byPath.put("host.cfg",
            new CanonicalModuleIdentity.ExternalModule("host/cfg"));
        ModuleIdentityResolver.IdentityIndex index =
            ModuleIdentityResolver.buildIndex(byPath);

        check("@$builtin/Error".equals(index.descriptorTextFor(
                new CanonicalClassIdentity(
                    CanonicalModuleIdentity.BuiltinModule.INSTANCE, "Error"))),
            "builtin Error projects @$builtin/Error");
        check("@src/app/User".equals(index.descriptorTextFor(
                new CanonicalClassIdentity(byPath.get("app.user"), "User"))),
            "project class projects @<rootText>/<components>/<Name>");
        check("@src/Helper".equals(index.descriptorTextFor(
                new CanonicalClassIdentity(byPath.get("orch_main"), "Helper"))),
            "root-level module projects @<rootText>/<Name>");
        check("@$external/host/cfg/ServerConfig".equals(index.descriptorTextFor(
                new CanonicalClassIdentity(byPath.get("host.cfg"), "ServerConfig"))),
            "externals class projects @$external/<rawSpecifier>/<Name>");
        check("@src/app/User".equals(index.descriptorTextFor("app.user", "User")),
            "module-path convenience accessor round-trips");
        check(new CanonicalClassIdentity(byPath.get("app.user"), "User")
                .equals(index.identityForDescriptorText("@src/app/User")),
            "identityForDescriptorText round-trips a produced text");

        boolean builtinOtherThrows = false;
        try {
            index.descriptorTextFor(new CanonicalClassIdentity(
                CanonicalModuleIdentity.BuiltinModule.INSTANCE, "Other"));
        } catch (IllegalStateException e) {
            builtinOtherThrows = true;
        }
        check(builtinOtherThrows,
            "a non-Error builtin identity has no pinned projection (fail-closed)");

        boolean unrepresentableThrows = false;
        try {
            ModuleIdentityResolver.IdentityIndex bad = ModuleIdentityResolver.buildIndex(
                Map.of("", CanonicalModuleIdentity.BuiltinModule.INSTANCE,
                    "m", new CanonicalModuleIdentity.ProjectModule(
                        new ProjectModuleIdentity("bad root", "/x", List.of()))));
            bad.descriptorTextFor(new CanonicalClassIdentity(byPath2Identity(bad, "m"), "C"));
        } catch (IllegalStateException e) {
            unrepresentableThrows = true;
        }
        check(unrepresentableThrows,
            "an unrepresentable root text fails closed at projection");

        // The one Type->text producer over the index.
        CanonicalRuntimeTypeDescriptor service =
            new CanonicalRuntimeTypeDescriptor(index, index.moduleIdentityLookup());
        check("[@src/app/User]".equals(service.encode(
                new Type.Array(Types.classType("User", "app.user")))),
            "encode(Type) emits [@src/app/User] via the index");
        check("(?@src/app/User)->boolean".equals(service.encode(
                new Type.Func(List.of(new Type.Nullable(
                    Types.classType("User", "app.user"))), Type.Boolean.INSTANCE))),
            "encode(Type) emits ?D function parameters");
        check("@$builtin/Error".equals(service.encode(Types.classType("Error", ""))),
            "encode(Type) emits @$builtin/Error for the builtin Error type");
        check("async(bytes)->bytes".equals(service.encode(
                new Type.Func(List.of(Type.Bytes.INSTANCE), Type.Bytes.INSTANCE, true))),
            "encode(Type) emits async(bytes)->bytes");

        boolean absentIdentityThrows = false;
        try {
            service.encode(Types.classType("Ghost", "unclassified.mod"));
        } catch (IllegalStateException e) {
            absentIdentityThrows = true;
        }
        check(absentIdentityThrows,
            "a class from an unclassified module path fails closed (invariant)");
        boolean errorSentinelThrows = false;
        try {
            service.encode(Type.Error.INSTANCE);
        } catch (IllegalStateException e) {
            errorSentinelThrows = true;
        }
        check(errorSentinelThrows,
            "Type.Error has no canonical descriptor (invariant)");
    }

    /** The module identity of a dotted path inside a freshly built index. */
    private static CanonicalModuleIdentity byPath2Identity(
            CanonicalClassIdentityIndex index, String modulePath) {
        if (index instanceof ModuleIdentityResolver.IdentityIndex ii) {
            return ii.modulePathIdentities().get(modulePath);
        }
        throw new AssertionError("expected the module-identity index");
    }

    private static void testLegacyDialectRetirementScan() {
        System.out.println("-- Scan: jsTypeDescriptor and the legacy dialect are gone --");
        try {
            String js = Files.readString(Path.of("deal/codegen/js/JsBackend.java"));
            check(!js.contains("jsTypeDescriptor"),
                "deal/codegen/js/JsBackend.java no longer contains jsTypeDescriptor");
            check(!js.contains("|null"),
                "deal/codegen/js/JsBackend.java spells no legacy |null text");
            check(!js.contains("\"T[]\"") && !js.contains("+ \"[]\""),
                "deal/codegen/js/JsBackend.java spells no legacy T[] suffix text");
            String rt = Files.readString(Path.of("deal/runtime.js"));
            check(rt.contains("internal: cannot parse type descriptor"),
                "deal/runtime.js carries the pinned cannot-parse arm");
            check(!rt.contains("$classname\", \"Error\")"),
                "deal/runtime.js no longer tags the bare Error identity");
        } catch (IOException e) {
            fail("legacy-dialect scan failed: " + e.getMessage());
        }
    }

    private static void testErrorDefaultFilling() throws Exception {
        System.out.println("-- Node: Error-literal default filling --");
        if (!nodeAvailable) { skipNode("Error default filling"); return; }

        NodeResult run = runDealNode("""
            function checkMsg(): int {
              try {
                throw { message: "x" };
              } catch (e) {
                if (e.code === "" && e.message === "x") { return 1; }
                return 0;
              }
              return 0;
            }
            function checkCode(): int {
              try {
                throw { code: "C" };
              } catch (e) {
                if (e.code === "C" && e.message === "") { return 1; }
                return 0;
              }
              return 0;
            }
            export function test(): int { return checkMsg() * 10 + checkCode(); }
            """, "err-default");
        check(run.exitCode() == 0 && run.output().equals("11"),
            "throw { message: \"x\" } catches with e.code === \"\" and "
                + "throw { code: \"C\" } catches with e.message === \"\": "
                + run.output());
    }

    private static void testContextualErrorConstruction() throws Exception {
        System.out.println("-- Node: contextual Error construction --");
        if (!nodeAvailable) { skipNode("contextual Error construction"); return; }

        NodeResult run = runDealNode("""
            function produce(): Error { return { message: "ret" }; }
            export function test(): int {
              let e: Error = { message: "x" };
              if (e.code !== "" || e.message !== "x") { return 0; }
              let f: Error = produce();
              if (f.code !== "" || f.message !== "ret") { return 0; }
              return 1;
            }
            """, "err-contextual");
        check(run.exitCode() == 0 && run.output().equals("1"),
            "let e: Error = { message: \"x\" } and a return-position "
                + "contextual literal fill the omitted code with \"\": "
                + run.output());
    }

    private static void testRethrowPreservesCode() throws Exception {
        System.out.println("-- Node: non-literal throw / rethrow --");
        if (!nodeAvailable) { skipNode("rethrow"); return; }

        NodeResult caught = runDealNode("""
            function inner(): null {
              try {
                throw { code: "USER_RETHROW", message: "boom" };
              } catch (e) {
                throw e;
              }
              return null;
            }
            export function test(): int {
              try {
                inner();
              } catch (e) {
                if (e.code === "USER_RETHROW" && e.message === "boom") { return 1; }
                return 0;
              }
              return 0;
            }
            """, "rethrow-caught");
        check(caught.exitCode() == 0 && caught.output().equals("1"),
            "catch (e) { throw e; } preserves code/message to the outer "
                + "catch: " + caught.output());

        NodeResult shim = runDealNode("""
            function inner(): null {
              try {
                throw { code: "SHIM_CODE", message: "shim" };
              } catch (e) {
                throw e;
              }
              return null;
            }
            export function test(): null {
              inner();
              return null;
            }
            """, "rethrow-shim");
        check(shim.exitCode() == 1
                && shim.output().contains("DEAL_ERROR_CODE: SHIM_CODE")
                && shim.output().contains("shim"),
            "an uncaught rethrow preserves code/message to the entry shim's "
                + "DEAL_ERROR_CODE line, exit 1: " + shim.output());
    }

    private static void testArrayElementDeleteSemantics() throws Exception {
        System.out.println("-- Node: array-element delete semantics --");
        if (!nodeAvailable) { skipNode("array-element delete"); return; }

        NodeResult valid = runDealNode("""
            export function test(): int {
              let xs: int[] = [1, 2, 3];
              delete xs[1];
              if (xs.length !== 3) { return 0; }
              let first: int | null = xs[0];
              let deleted: int | null = xs[1];
              let last: int | null = xs[2];
              if (first !== null) {
                if (first !== 1) { return 0; }
              } else {
                return 0;
              }
              if (deleted !== null) { return 0; }
              if (last !== null) {
                if (last !== 3) { return 0; }
              } else {
                return 0;
              }
              let n: int = 0;
              for (let x: int of xs) { n = n + 1; }
              if (n !== 1) { return 0; }
              return 1;
            }
            """, "del-valid");
        check(valid.exitCode() == 0 && valid.output().equals("1"),
            "a valid delete nil-outs via $rt.undefined (nullable read null, "
                + "for-of stops at the deleted element, length unchanged): "
                + valid.output());

        NodeResult nonNull = runDealNode(
            "export function test(): int { let xs: int[] = [1, 2, 3]; delete xs[1]; return xs[1]; }",
            "del-nonnullable");
        check(nonNull.exitCode() == 1
                && nonNull.output().contains("DEAL_ERROR_CODE: E8001"),
            "a deleted element at a non-nullable target rejects with E8001: "
                + nonNull.output());

        NodeResult negative = runDealNode(
            "export function test(): int { let xs: int[] = [1, 2, 3]; delete xs[-1]; return 0; }",
            "del-negative");
        check(negative.exitCode() == 1
                && negative.output().contains("DEAL_ERROR_CODE: E8002")
                && negative.output().contains("array index out of bounds"),
            "a negative delete index raises E8002 'array index out of bounds': "
                + negative.output());

        NodeResult beyond = runDealNode(
            "export function test(): int { let xs: int[] = [1, 2, 3]; delete xs[9]; return 0; }",
            "del-beyond");
        check(beyond.exitCode() == 1
                && beyond.output().contains("DEAL_ERROR_CODE: E8002")
                && beyond.output().contains("array index out of bounds"),
            "a beyond-length delete index raises E8002: " + beyond.output());

        NodeResult atLength = runDealNode("""
            export function test(): int {
              let xs: int[] = [1, 2, 3];
              delete xs[3];
              if (xs.length !== 3) { return 0; }
              if (xs[0] !== 1 || xs[1] !== 2 || xs[2] !== 3) { return 0; }
              return 1;
            }
            """, "del-at-length");
        check(atLength.exitCode() == 0 && atLength.output().equals("1"),
            "a delete at i === length is a no-op (length and elements "
                + "unchanged): " + atLength.output());
    }

    private static void testSpanParameterCollisionExecution() throws Exception {
        System.out.println("-- Node: span-parameter collision safety --");
        if (!nodeAvailable) { skipNode("span-parameter collision execution"); return; }

        NodeResult ok = runDealNode("""
            function f(file: string, line: int, column: string): int { return line; }
            export function test(): int { return f("a", 41, "b"); }
            """, "span-run");
        check(ok.exitCode() == 0 && ok.output().equals("41"),
            "a function with user parameters named file/line/column runs "
                + "under node with the $file/$line/$column span parameters: "
                + ok.output());

        NodeResult located = runDealNode("""
            function g(line: int): int { return line + 1; }
            export function test(): int {
              let t: table = {};
              t.v = "not-int";
              return g(t.v);
            }
            """, "span-located");
        check(located.exitCode() == 1
                && located.output().contains("DEAL_ERROR_CODE: E8001")
                && located.output().contains("expected int")
                && located.output().contains(
                    " at jstest-span-located.deal:5:10"),
            "the boundary error reports the call-site location: "
                + located.output());
    }

    private static void testStdJsonRoundTrips() throws Exception {
        System.out.println("-- Node: std/json round-trips --");
        if (!nodeAvailable) { skipNode("std/json round-trips"); return; }

        NodeResult run = runDealNode("""
            import * as json from "std/json"
            import * as tables from "std/table"
            class User {
              name: string = "";
              nick?: string | null;
            }
            export function test(): int {
              let n: table = json.parse("null");
              let ns: string = json.stringify(n);
              if (ns !== "null") { return 0; }
              let nk: string[] = tables.keys(n);
              if (nk.length !== 0) { return 0; }
              let arr: table = json.parse("[1,2]");
              let arrJson: string = json.stringify(arr);
              if (arrJson !== "[1,2]") { return 0; }
              let ak: string[] = tables.keys(arr);
              if (ak.length !== 2 || ak[0] !== "1" || ak[1] !== "2") { return 0; }
              let p: table = { __proto__: "x" };
              let ps: string = json.stringify(p);
              if (ps !== "{\\"__proto__\\":\\"x\\"}") { return 0; }
              let pr: table = json.parse(ps);
              let prs: string = json.stringify(pr);
              if (prs !== "{\\"__proto__\\":\\"x\\"}") { return 0; }
              let u: User = { name: "Ada" };
              let g: table = { u: u };
              let gs: string = json.stringify(g);
              if (gs !== "{\\"u\\":{\\"__kind\\":\\"class\\",\\"__classname\\":\\"@Main/User\\",\\"name\\":\\"Ada\\"}}") { return 0; }
              return 1;
            }
            """, "json-roundtrip");
        check(run.exitCode() == 0 && run.output().equals("1"),
            "parse(\"null\")/parse(\"[1,2]\") cross the table boundary and "
                + "round-trip; __proto__-keyed tables and class instances "
                + "encode per the reference: " + run.output());
    }

    private static void testJsonStoredFunctionE8001() throws Exception {
        System.out.println("-- Node: std/json stringify of a stored function → E8001 --");
        if (!nodeAvailable) { skipNode("json stored function"); return; }

        NodeResult run = runDealNode("""
            import * as json from "std/json"
            export function test(): string {
              let t: table = { f: function(): int { return 1; } };
              return json.stringify(t);
            }
            """, "json-function");
        check(run.exitCode() == 1
                && run.output().contains("DEAL_ERROR_CODE: E8001")
                && run.output().contains(
                    "unsupported type for JSON encoding: function"),
            "a stored function value raises E8001 from stringify: "
                + run.output());
    }

    private static void testIntrinsicFunctionValues() throws Exception {
        System.out.println("-- Node: intrinsics as function values --");
        if (!nodeAvailable) { skipNode("intrinsic function values"); return; }

        NodeResult run = runDealNode("""
            import * as console from "std/console"
            function applyInt(f: (x: number) => int, v: number): int { return f(v); }
            function applyNumber(f: (x: int) => number, v: int): number { return f(v); }
            export function test(): int {
              let f: (x: number) => int = int;
              let a: int = f(3.0);
              let b: int = applyInt(int, 4.0);
              let nb: number = applyNumber(number, 7);
              let wide: (x: number, y: number) => int = int;
              let c: int = wide(9.0, 1.0);
              let g: (x: string) => null = console.log;
              g("intrinsic-console-value");
              if (a === 3 && b === 4 && nb === 7.0 && c === 9) { return 1; }
              return 0;
            }
            """, "intrinsics");
        check(run.exitCode() == 0 && run.output().contains("intrinsic-console-value")
                && run.output().contains("1"),
            "int/number as values, callbacks, arity extension, and the "
                + "console member as a value: " + run.output());
    }

    private static void testAsyncCompletionAndErrors() throws Exception {
        System.out.println("-- Node: async completion and error propagation --");
        if (!nodeAvailable) { skipNode("async completion"); return; }

        NodeResult ok = runDealNode("""
            async function g(): int { return 42; }
            async function greet(name: string): string { return "hi-" + name; }
            async function noop(): null { return; }
            export async function test(): string {
              await noop();
              let x: int = await g();
              if (x !== 42) { return "bad"; }
              return await greet("n");
            }
            """, "async-ok");
        check(ok.exitCode() == 0 && ok.output().equals("hi-n"),
            "the async export's awaited completion value resolves before "
                + "printing: " + ok.output());

        NodeResult bad = runDealNode("""
            async function bomb(): int { return 1 / 0; }
            export async function test(): int { return await bomb(); }
            """, "async-err");
        check(bad.exitCode() == 1 && bad.output().contains("DEAL_ERROR_CODE: E8005"),
            "an awaited error propagates natively at the await site, exit 1: "
                + bad.output());
    }

    // =========================================================================
    // Orchestrator-level tests
    // =========================================================================

    private static void testOrchestratorJsBackend() throws Exception {
        System.out.println("-- Orchestrator: Backend.JS use site --");

        writeFile("js_proj/deal.json", """
            {
              "languageVersion": "1.2",
              "backend": "js"
            }""");
        writeFile("js_proj/src/orch_main.deal", """
            import * as console from "std/console"
            export function main(): null {
              console.log("js-orchestrator-main");
              return null;
            }
            function add(a: int, b: int): int { return a + b; }
            export function run(): int { return add(20, 22); }
            """);

        Path entryFile = tmpDir.resolve("js_proj/src/orch_main.deal").toAbsolutePath();
        Path outputDir = tmpDir.resolve("js_proj/build/js");
        List<Path> roots = List.of(tmpDir.resolve("js_proj/src").toAbsolutePath());

        DealConfig config = DealConfig.load(tmpDir.resolve("js_proj")).config();
        check(config != null && "js".equals(config.backend()),
            "deal.json backend js parsed");

        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            entryFile, outputDir, false, false, false, Backend.JS, config, roots,
            Path.of(".").toAbsolutePath().normalize());
        boolean success = orchestrator.compile();
        check(success, "JS orchestrator compile succeeds: "
            + orchestrator.diagnostics());
        if (!success) return;

        Path artifact = outputDir.resolve("orch_main.js");
        check(Files.exists(artifact), "orchestrator wrote orch_main.js");
        check(Files.exists(outputDir.resolve("deal/runtime.js")),
            "orchestrator deployed deal/runtime.js");
        for (String stdlibModule : List.of("console", "string", "table",
                "json", "math", "time")) {
            check(Files.exists(outputDir.resolve("std/" + stdlibModule + ".js")),
                "orchestrator deployed std/" + stdlibModule + ".js");
        }
        check(!Files.exists(outputDir.resolve("orch_main.lua")),
            "no .lua artifact in JS mode");
        check(!Files.exists(outputDir.resolve("deal/runtime.lua")),
            "no Lua runtime copied in JS mode");

        if (Files.exists(artifact)) {
            String js = Files.readString(artifact);
            check(js.contains("$require(\"./deal/runtime\")"),
                "the artifact requires ./deal/runtime");
            check(js.contains("if ($require.main === $module) {"),
                "the entry artifact carries the entry shim");
            check(js.contains("$exports.main.$f();"),
                "the shim invokes the exported main wrapper");
            check(js.contains("console.log.$f(\"js-orchestrator-main\","),
                "the console.log call emits through the stdlib wrapper");
        }

        // The deployed project runs under the real node binary: the entry
        // shim invokes main, prints, and exits 0 — a node-dependent
        // sub-check guarded like every node semantic case: node
        // unavailability skips it, never fails it.
        if (nodeAvailable) {
            ProcessBuilder node = new ProcessBuilder("node", "orch_main.js");
            node.directory(outputDir.toFile());
            node.redirectErrorStream(true);
            Process np = node.start();
            String nout = new String(np.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8).trim();
            int nrc = np.waitFor();
            check(nrc == 0 && nout.equals("js-orchestrator-main"),
                "node <output>/orch_main.js runs the entry shim: exit " + nrc
                    + ", output '" + nout + "'");
        } else {
            skipNode("orchestrator Backend.JS node run");
        }
    }

    private static void testOrchestratorJsNestedModule() throws Exception {
        System.out.println("-- Orchestrator: nested module artifacts and relative requires --");

        writeFile("nest_proj/deal.json",
            "{\"languageVersion\": \"1.2\", \"backend\": \"js\"}");
        writeFile("nest_proj/src/app/lib.deal",
            "export function tag(): string { return \"nested-module-ok\"; }");
        writeFile("nest_proj/src/app/main.deal", """
            import * as lib from "./lib"
            import * as console from "std/console"
            export function main(): null {
              console.log(lib.tag());
              return null;
            }
            """);

        Path entryFile = tmpDir.resolve("nest_proj/src/app/main.deal").toAbsolutePath();
        Path outputDir = tmpDir.resolve("nest_proj/build/js");
        List<Path> roots = List.of(tmpDir.resolve("nest_proj/src").toAbsolutePath());
        DealConfig config = DealConfig.load(tmpDir.resolve("nest_proj")).config();

        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            entryFile, outputDir, false, false, false, Backend.JS, config, roots,
            Path.of(".").toAbsolutePath().normalize());
        boolean success = orchestrator.compile();
        check(success, "nested JS orchestrator compile succeeds: "
            + orchestrator.diagnostics());
        if (!success) return;

        Path entryArtifact = outputDir.resolve("app/main.js");
        Path libArtifact = outputDir.resolve("app/lib.js");
        check(Files.exists(entryArtifact), "orchestrator wrote app/main.js");
        check(Files.exists(libArtifact), "orchestrator wrote app/lib.js");
        if (Files.exists(entryArtifact)) {
            String js = Files.readString(entryArtifact);
            check(js.contains("$require(\"../deal/runtime\")"),
                "app/main.js requires ../deal/runtime");
            check(js.contains("$require(\"./lib\")"),
                "app/main.js requires the sibling ./lib");
        }
        if (Files.exists(libArtifact)) {
            String js = Files.readString(libArtifact);
            check(js.contains("$require(\"../deal/runtime\")"),
                "app/lib.js requires ../deal/runtime");
        }

        // The deployed nested entry runs under the real node binary — a
        // node-dependent sub-check guarded like every node semantic case:
        // node unavailability skips it, never fails it.
        if (nodeAvailable) {
            ProcessBuilder node = new ProcessBuilder("node", "app/main.js");
            node.directory(outputDir.toFile());
            node.redirectErrorStream(true);
            Process np = node.start();
            String nout = new String(np.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8).trim();
            int nrc = np.waitFor();
            check(nrc == 0 && nout.equals("nested-module-ok"),
                "node app/main.js runs the nested entry chain: exit " + nrc
                    + ", output '" + nout + "'");
        } else {
            skipNode("orchestrator nested-module node run");
        }
    }

    private static void testOrchestratorJsRejectsUnsupported() throws Exception {
        System.out.println("-- Orchestrator: no-partial-artifact on rejection --");

        writeFile("rej_proj/deal.json",
            "{\"languageVersion\": \"1.2\", \"backend\": \"js\"}");
        writeFile("rej_proj/src/lib.deal", """
            // @jsonable
            export class Data {
              v: int = 0;
            }
            """);
        writeFile("rej_proj/src/rej_main.deal", """
            import * as lib from "./lib"
            export function main(): null { return null; }
            export function run(): int { return 1; }
            """);

        Path entryFile = tmpDir.resolve("rej_proj/src/rej_main.deal").toAbsolutePath();
        Path outputDir = tmpDir.resolve("rej_proj/build/js");
        List<Path> roots = List.of(tmpDir.resolve("rej_proj/src").toAbsolutePath());
        DealConfig config = DealConfig.load(tmpDir.resolve("rej_proj")).config();

        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            entryFile, outputDir, false, false, false, Backend.JS, config, roots,
            Path.of(".").toAbsolutePath().normalize());
        boolean success = orchestrator.compile();
        check(!success, "the @jsonable project fails the compilation");
        check(orchestrator.diagnostics().stream().anyMatch(d ->
                "E6000".equals(d.code())),
            "the orchestrator reports E6000: " + orchestrator.diagnostics());
        check(!Files.exists(outputDir.resolve("lib.js")),
            "the rejected module writes no artifact");
        check(Files.exists(outputDir.resolve("rej_main.js")),
            "the clean sibling entry still writes its artifact (two-pass model)");
    }

    // =========================================================================
    // ISSUE-0194 orchestrator-level slice (merged; distinct trigger paths)
    // =========================================================================

    /** Backend.JS joins the single selection seam under the name "js". */
    private static void testBackendSelectionNames() {
        check(Backend.fromCliName("js").orElse(null) == Backend.JS,
            "\"js\" selects Backend.JS");
        check(Backend.fromCliName("JS").orElse(null) == Backend.JS,
            "backend names are case-insensitive (\"JS\" selects Backend.JS)");
        check(Backend.fromCliName(" Js ").orElse(null) == Backend.JS,
            "backend names are trimmed (\" Js \" selects Backend.JS)");
        check(Backend.JS.cliName().equals("js"),
            "Backend.JS.cliName() is \"js\"");
        check(Backend.fromCliName("jvm").orElse(null) == Backend.JVM,
            "\"jvm\" still selects Backend.JVM");
        check(Backend.fromCliName("luajit").orElse(null) == Backend.LUAJIT,
            "\"luajit\" still selects Backend.LUAJIT");
        check(Backend.fromCliName("python").isEmpty(),
            "unknown backend names are rejected");
    }

    /** deal.json "backend": "js" is accepted with languageVersion 1.2. */
    private static void testDealJsonBackendAcceptance() throws Exception {
        Path projectDir = null;
        try {
            projectDir = Files.createTempDirectory("deal_js_backend_test_");
            Files.writeString(projectDir.resolve("deal.json"),
                "{\n  \"languageVersion\": \"1.2\",\n  \"backend\": \"js\"\n}\n",
                StandardCharsets.UTF_8);
            DealConfigParseResult result = DealConfig.load(projectDir);
            check(result.config() != null,
                "deal.json with \"backend\": \"js\" loads a config");
            check(result.diagnostics().isEmpty(),
                "deal.json with \"backend\": \"js\" has zero diagnostics: "
                    + result.diagnostics());
            if (result.config() != null) {
                check("js".equals(result.config().backend()),
                    "loaded config backend is \"js\"");
                check("1.2".equals(result.config().languageVersion()),
                    "loaded config languageVersion is \"1.2\"");
                check(Backend.fromCliName(result.config().backend())
                        .orElse(null) == Backend.JS,
                    "the manifest backend resolves to Backend.JS");
            }

            // An unknown backend stays a configuration error.
            Files.writeString(projectDir.resolve("deal.json"),
                "{\n  \"languageVersion\": \"1.2\",\n"
                    + "  \"backend\": \"python\"\n}\n",
                StandardCharsets.UTF_8);
            DealConfigParseResult badResult = DealConfig.load(projectDir);
            check(badResult.config() == null
                    || !badResult.diagnostics().isEmpty(),
                "an unknown manifest backend is rejected with diagnostics: "
                    + badResult.diagnostics());
        } finally {
            deleteDir(projectDir);
        }
    }

    /**
     * The CLI's default output for the JS backend is {@code build/js}
     * (a real {@code deal.Main} subprocess run from a temp project whose
     * manifest selects {@code "js"} — no {@code --backend}, no
     * {@code --output} flag).
     */
    private static void testCliDefaultOutputBuildJs() throws Exception {
        Path projectDir = null;
        try {
            projectDir = Files.createTempDirectory("deal_js_cli_test_");
            Files.writeString(projectDir.resolve("deal.json"),
                "{\n  \"languageVersion\": \"1.2\",\n  \"backend\": \"js\"\n}\n",
                StandardCharsets.UTF_8);
            Files.writeString(projectDir.resolve("main.deal"),
                "import * as c from \"std/console\";\n\n"
                    + "export function main(): null {\n"
                    + "  c.log(\"cli default output\");\n"
                    + "  return null;\n"
                    + "}\n",
                StandardCharsets.UTF_8);
            // The project-local std/ directory (the .d.deal declarations)
            // and deal/runtime.js keep the subprocess self-contained
            // regardless of CWD (the orchestrator resolves the runtime
            // file CWD-relative when no classpath resource exists).
            Path localStd = projectDir.resolve("std");
            Path localDeal = projectDir.resolve("deal");
            Files.createDirectories(localDeal);
            Files.copy(Path.of("deal/runtime.js").toAbsolutePath(),
                localDeal.resolve("runtime.js"));
            Files.createDirectories(localStd);
            Path repoStd = Path.of("std").toAbsolutePath().normalize();
            try (Stream<Path> walk = Files.walk(repoStd, 1)) {
                for (Path file : walk.filter(Files::isRegularFile)
                        .filter(p -> p.toString().endsWith(".d.deal")
                            || p.toString().endsWith(".js"))
                        .sorted(Comparator.comparing(Path::toString))
                        .toList()) {
                    Files.copy(file, localStd.resolve(file.getFileName()));
                }
            }

            Path buildDir = Path.of("build").toAbsolutePath().normalize();
            Process process = new ProcessBuilder(
                "java", "-ea", "-cp", buildDir.toString(), "deal.Main",
                "compile", "main.deal")
                .directory(projectDir.toFile())
                .redirectErrorStream(true)
                .start();
            String output;
            try (InputStream in = process.getInputStream()) {
                output = new String(in.readAllBytes(),
                    StandardCharsets.UTF_8);
            }
            if (!process.waitFor(120, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                check(false, "CLI subprocess timed out");
                return;
            }
            check(process.exitValue() == 0,
                "CLI compile exits 0 with the manifest-selected JS backend: "
                    + output);
            check(Files.isRegularFile(projectDir.resolve("build/js/main.js")),
                "default JS output build/js holds the entry artifact: "
                    + output);
            check(Files.isRegularFile(
                    projectDir.resolve("build/js/deal/runtime.js")),
                "default JS output build/js holds deal/runtime.js");
            check(Files.isRegularFile(
                    projectDir.resolve("build/js/std/console.js")),
                "default JS output build/js holds std/console.js");
        } finally {
            deleteDir(projectDir);
        }
    }

    /**
     * The orchestrator deploys deal/runtime.js and the six spec
     * std/*.js modules (including std/console.js) next to the emitted
     * artifacts.
     */
    private static void testOrchestratorDeploymentCopies() throws Exception {
        Path projectDir = null;
        try {
            projectDir = Files.createTempDirectory("deal_js_deploy_test_");
            Files.writeString(projectDir.resolve("deal.json"),
                "{\n  \"languageVersion\": \"1.2\",\n  \"backend\": \"js\"\n}\n",
                StandardCharsets.UTF_8);
            Files.writeString(projectDir.resolve("main.deal"),
                "import * as c from \"std/console\";\n\n"
                    + "export function main(): null {\n"
                    + "  c.log(\"deploy\");\n"
                    + "  return null;\n"
                    + "}\n",
                StandardCharsets.UTF_8);
            DealConfigParseResult configResult =
                DealConfig.load(projectDir);
            Path outputRoot = projectDir.resolve("out");
            CompilationOrchestrator orchestrator =
                new CompilationOrchestrator(projectDir.resolve("main.deal"),
                    outputRoot, false, false, false, Backend.JS,
                    configResult.config(), List.of(projectDir),
                    Path.of("").toAbsolutePath().normalize());
            boolean ok = orchestrator.compile();
            check(ok, "JS orchestrator compile succeeds: "
                + orchestrator.diagnostics());
            check(Files.isRegularFile(outputRoot.resolve("main.js")),
                "the entry artifact is emitted");
            check(Files.isRegularFile(
                    outputRoot.resolve("deal/runtime.js")),
                "deal/runtime.js is deployed to the output");
            for (String module : List.of("console", "string", "table",
                    "json", "math", "time")) {
                check(Files.isRegularFile(outputRoot.resolve(
                        "std/" + module + ".js")),
                    "std/" + module + ".js is deployed to the output");
            }
        } finally {
            deleteDir(projectDir);
        }
    }

    /**
     * No-partial-artifact: a host-ABI import (a non-stdlib declaration
     * file) is rejected with E6000 and the rejected module writes no
     * artifact.
     */
    private static void testNoPartialArtifactOnRejection() throws Exception {
        Path projectDir = null;
        try {
            projectDir = Files.createTempDirectory("deal_js_reject_test_");
            Files.writeString(projectDir.resolve("deal.json"),
                "{\n  \"languageVersion\": \"1.2\",\n  \"backend\": \"js\"\n}\n",
                StandardCharsets.UTF_8);
            Files.writeString(projectDir.resolve("host.d.deal"),
                "export function hostFn(x: int): int;\n",
                StandardCharsets.UTF_8);
            Files.writeString(projectDir.resolve("main.deal"),
                "import * as host from \"./host\";\n\n"
                    + "export function main(): null {\n"
                    + "  let v: int = host.hostFn(1);\n"
                    + "  return null;\n"
                    + "}\n",
                StandardCharsets.UTF_8);
            DealConfigParseResult configResult =
                DealConfig.load(projectDir);
            Path outputRoot = projectDir.resolve("out");
            CompilationOrchestrator orchestrator =
                new CompilationOrchestrator(projectDir.resolve("main.deal"),
                    outputRoot, false, false, false, Backend.JS,
                    configResult.config(), List.of(projectDir),
                    Path.of("").toAbsolutePath().normalize());
            boolean ok = orchestrator.compile();
            check(!ok, "a host-ABI import fails the JS compilation");
            check(orchestrator.diagnostics().stream()
                    .anyMatch(d -> "E6000".equals(d.code())),
                "the rejection is E6000: " + orchestrator.diagnostics());
            check(orchestrator.diagnostics().stream()
                    .noneMatch(d -> "E6003".equals(d.code())),
                "the host-ABI rejection is E6000, not E6003: "
                    + orchestrator.diagnostics());
            check(!Files.exists(outputRoot.resolve("main.js")),
                "no entry artifact is written for the rejected module");
            check(!Files.exists(outputRoot.resolve("host.js")),
                "no artifact is written for the declaration file");
        } finally {
            deleteDir(projectDir);
        }
    }
}
