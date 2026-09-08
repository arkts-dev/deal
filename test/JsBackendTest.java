package deal.test;

import deal.ast.ClassDeclaration;
import deal.ast.ClassField;
import deal.ast.ExportDeclaration;
import deal.ast.ProgramNode;
import deal.ast.StatementNode;
import deal.checker.CheckResult;
import deal.checker.ModuleResolver;
import deal.checker.NameResolver;
import deal.checker.SymbolTable;
import deal.checker.TypeChecker;
import deal.codegen.Backend;
import deal.codegen.js.HostModuleDeclarations;
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
import deal.module.ExportExtractor;
import deal.module.ModuleIdentityResolver;
import deal.module.ModuleShapeValidator;
import deal.project.ProjectContext;
import deal.project.ProjectLocator;
import deal.project.StrictManifestParser;
import deal.parser.ParseResult;
import deal.parser.Parser;
import deal.semantic.CapabilityRegistry;
import deal.semantic.CompilerInvocation;
import deal.semantic.CompilerProfileProvider;
import deal.semantic.ReleaseConfiguration;
import deal.semantic.ir.ReleaseState;
import deal.semantic.ir.SemanticProfile;
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
            testJsonableEmissionPins();
            testJsonableRoundTripNode();
            testOrchestratorJsonableCrossModule();
            testNestedClassEmissionPins();
            testNestedClassNodeSemantics();
            testEntryShimAndE6004();
            testSourceLocationArguments();
            testOwnPropertySafeProtoEmission();
            testErrorEmissionPins();
            testArrayElementDeleteEmission();
            testHostGlobalHygiene();
            testUnsupportedConstructsRejected();
            testHostAbiEmissionPins();
            testHostAbiOrchestratorNode();
            testSourceMapSidecarsEmitted();
            testIntArithmeticEdgeCodes();
            testInt32ProfileSelectorEmission();
            testInt32OrchestratorPlumb();
            testInt32MatrixNode();
            testBytesEmissionPins();
            testBytesNodeSemantics();
            testBytesUserNameShadowing();
            testBytesClosureFunctionArrayNode();
            testBytesClosureAsyncContainerNode();
            testBytesClosureClassDefaultsNode();
            testBytesClosureDeepCompositionsNode();
            testBytesClosureModuleBoundary();
            testBytesClosureNoE6000Pins();
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
            testJsonableRuntimeWalkers();
            testIntrinsicFunctionValues();
            testAsyncCompletionAndErrors();
            testAsyncExportInvokerNode();
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
        return compileFrontend(source, filename, modulePath, moduleResolver,
            true);
    }

    /**
     * Frontend compile WITHOUT the module-shape validation pass (the
     * {@code LuaAbiBackendTest} harness shape): the backend-level
     * surface exercises backend contracts the shape rules do not cover —
     * a nested {@code export class} is frontend-rejected (E1050), but
     * the JS backend's scope-local export path
     * (js-v12-completion-architecture D4) still has to hold for the AST
     * shape.
     */
    @SuppressWarnings("deprecation")
    private static Frontend compileFrontendUnshaped(String source,
                                                    String filename) {
        return compileFrontend(source, filename, "Main",
            new BackendConformanceTest.StubModuleResolver(), false);
    }

    @SuppressWarnings("deprecation")
    private static Frontend compileFrontend(String source, String filename,
                                            String modulePath,
                                            ModuleResolver moduleResolver,
                                            boolean shapeValidate) {
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

        // ISSUE-0273: the events-carrying parser — @jsonable binding in
        // the adapter fixtures needs production directive evaluation.
        Parser parser = new Parser(lex.tokens(), filename,
            lex.directiveEvents());
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
        // mirroring the orchestrator pipeline (skipped by the unshaped
        // backend-level surface).
        if (shapeValidate) {
            for (CompilerDiagnostic d : ModuleShapeValidator.validate(parseResult.program(),
                    filename, filename.endsWith(".d.deal"))) {
                if ("error".equals(d.severity())) {
                    errors.add(d);
                }
            }
            if (!errors.isEmpty()) {
                return new Frontend(null, null, errors);
            }
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
                                                      Map<String, HostModuleDeclarations> hostModules,
                                                      boolean isEntry) {
        Frontend f = compileFrontend(source, "jstest-" + name + ".deal");
        if (f.program() == null) {
            return null;
        }
        return JsBackend.generate(f.program(), f.checkResult(),
            "jstest-" + name + ".deal", modulePath, importResolutions,
            hostModules, isEntry);
    }

    /**
     * The adapter-shape generation call with an explicit extern-C import
     * set (fixed-name-directive-events D9): routes through the
     * production seam with a standalone identity surface, exactly as the
     * host-ABI class fixture does — the E6003 re-key pin passes the raw
     * import path here.
     */
    private static JsBackend.JsCodegenResult generateWithExternC(
            String source, String name, String modulePath,
            Map<String, String> importResolutions,
            Map<String, HostModuleDeclarations> hostModules,
            Set<String> externCImports, boolean isEntry) {
        Frontend f = compileFrontend(source, "jstest-" + name + ".deal");
        if (f.program() == null) {
            return null;
        }
        Map<String, CanonicalModuleIdentity> byPath =
            new LinkedHashMap<>();
        byPath.put("", CanonicalModuleIdentity.BuiltinModule.INSTANCE);
        byPath.put(modulePath, new CanonicalModuleIdentity.ProjectModule(
            new ProjectModuleIdentity(modulePath, modulePath, List.of())));
        ModuleIdentityResolver.IdentityIndex index =
            ModuleIdentityResolver.buildIndex(byPath);
        return JsBackend.generate(f.program(), f.checkResult(),
            "jstest-" + name + ".deal", modulePath, importResolutions,
            hostModules, externCImports, isEntry, index,
            index.moduleIdentityLookup(), null,
            SemanticProfile.LEGACY_SAFE_INT);
    }

    /** The adapter-shape generation call over the unshaped frontend
     * (no module-shape validation) — the backend-level surface for
     * contracts the shape rules do not cover (a nested export class). */
    private static JsBackend.JsCodegenResult generateUnshaped(String source,
                                                              String name) {
        Frontend f = compileFrontendUnshaped(source, "jstest-" + name + ".deal");
        if (f.program() == null) {
            return null;
        }
        return JsBackend.generate(f.program(), f.checkResult(),
            "jstest-" + name + ".deal", "Main", Map.of(), Map.of(), false);
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
        // The retained legacy authority: the default LEGACY_SAFE_INT
        // profile (no setInt32Mode emission, the ±(2^53-1) range).
        return runDealNodeProfile(source, name, SemanticProfile.LEGACY_SAFE_INT);
    }

    /**
     * The full JS chain under the real node binary with an explicit
     * project-wide semantic profile: frontend → codegen (the profile
     * plumbed into {@link JsBackend#generate}) → deployment → the
     * conformance runner → node execution. Under DEAL_V1_2_INT32 the
     * emitted artifact calls {@code $rt.setInt32Mode(true)} immediately
     * after the runtime require, activating the signed-32 range before
     * the runner invokes the export.
     */
    private static NodeResult runDealNodeProfile(String source, String name,
                                                 SemanticProfile profile)
            throws Exception {
        Frontend f = compileFrontend(source, "jstest-" + name + ".deal");
        if (!f.errors().isEmpty()) {
            throw new RuntimeException("frontend errors: " + f.errors());
        }
        JsBackend.JsCodegenResult res = JsBackend.generate(f.program(),
            f.checkResult(), "jstest-" + name + ".deal", "Main",
            Map.of(), Map.of(), false, profile);
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

    private static int countOccurrences(String haystack, String needle) {
        int n = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            n++;
            idx += needle.length();
        }
        return n;
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

    /**
     * The full JS chain under node with a production-invoker runner
     * (js-v12-async-export-invocation D5): frontend → codegen →
     * deployment → a runner that requires the emitted entry artifact
     * and calls the production host ABI
     * {@code $rt.invokeAsyncExport} with the pinned export name and
     * return descriptor, printing the distinct result signal.
     */
    private static NodeResult runInvokerNode(String source, String name,
            String exportName, String returnDescriptor) throws Exception {
        Frontend f = compileFrontend(source, "jstest-" + name + ".deal");
        if (!f.errors().isEmpty()) {
            throw new RuntimeException("frontend errors: " + f.errors());
        }
        JsBackend.JsCodegenResult res = JsBackend.generate(f.program(),
            f.checkResult(), "jstest-" + name + ".deal", "Main",
            Map.of(), Map.of(), false, SemanticProfile.LEGACY_SAFE_INT);
        if (res.hasErrors()) {
            throw new RuntimeException("codegen errors: " + res.diagnostics());
        }
        return runInvokerArtifact(res, exportName, returnDescriptor);
    }

    /**
     * The E6004 no-main variant: drives {@link JsBackend#generate} with
     * {@code isEntry = true} on a module without {@code main} — the
     * backstop emits the E6004 diagnostic but still produces the
     * artifact (without the entry shim and without any {@code main}
     * export), which the invoker must reject with the pinned
     * {@code missing main} host failure.
     */
    private static NodeResult runInvokerNodeNoMain(String source, String name,
            String exportName, String returnDescriptor) throws Exception {
        Frontend f = compileFrontend(source, "jstest-" + name + ".deal");
        if (!f.errors().isEmpty()) {
            throw new RuntimeException("frontend errors: " + f.errors());
        }
        JsBackend.JsCodegenResult res = JsBackend.generate(f.program(),
            f.checkResult(), "jstest-" + name + ".deal", "Main",
            Map.of(), Map.of(), true, SemanticProfile.LEGACY_SAFE_INT);
        boolean e6004 = res.diagnostics().stream()
            .anyMatch(d -> "E6004".equals(d.code()));
        if (!e6004) {
            throw new RuntimeException("expected the E6004 backstop, got: "
                + res.diagnostics());
        }
        return runInvokerArtifact(res, exportName, returnDescriptor);
    }

    /** Deploys a generated entry artifact and runs the invoker runner. */
    private static NodeResult runInvokerArtifact(
            JsBackend.JsCodegenResult res, String exportName,
            String returnDescriptor) throws Exception {
        Path dir = deployArtifacts(res, "Main.js");
        String runner = INVOKER_RUNNER_SOURCE
            .replace("EXPORT_NAME", exportName)
            .replace("RETURN_DESCRIPTOR", returnDescriptor);
        Files.writeString(dir.resolve("invoker_runner.js"), runner);
        NodeResult result = runNodeScript(dir, "invoker_runner.js");
        deleteDir(dir);
        return result;
    }

    /**
     * The invoker runner: requires the emitted entry artifact and the
     * deployed runtime, calls {@code $rt.invokeAsyncExport(entry,
     * exportName, returnDescriptor)}, and prints the pinned result
     * signal — {@code INVOKE_OK}, {@code INVOKE_FAILURE}, or
     * {@code INVOKE_ERROR} with the reified fields. An unexpected throw
     * from the invoker itself fails hard.
     */
    private static final String INVOKER_RUNNER_SOURCE = String.join("\n",
        "\"use strict\";",
        "const $rt = require(\"./deal/runtime\");",
        "const $entry = require(\"./Main\");",
        "$rt.invokeAsyncExport($entry, \"EXPORT_NAME\", \"RETURN_DESCRIPTOR\").then((r) => {",
        "  if (r.$ok === true) {",
        "    process.stdout.write(\"INVOKE_OK: \" + r.$value + \"\\n\");",
        "  } else if (typeof r.$failure === \"string\") {",
        "    process.stdout.write(\"INVOKE_FAILURE: \" + r.$failure + \"\\n\");",
        "  } else {",
        "    const e = r.$error;",
        "    process.stdout.write(\"INVOKE_ERROR: \" + e.code + \" \" + e.message",
        "      + (e.file !== $rt.undefined ? \" at \" + e.file + \":\" + e.line + \":\" + e.column : \"\") + \"\\n\");",
        "  }",
        "}, (err) => {",
        "  process.stderr.write(\"INVOKER_THREW: \" + err + \"\\n\");",
        "  process.exit(1);",
        "});",
        "");

    /**
     * The full JS chain with an exactly-once counter runner: the runner
     * wraps the entry's main and oracle {@code $f} with invocation
     * counters, then performs one production invocation and asserts one
     * main call and one export call.
     */
    private static NodeResult runCounterNode(String source, String name)
            throws Exception {
        Frontend f = compileFrontend(source, "jstest-" + name + ".deal");
        if (!f.errors().isEmpty()) {
            throw new RuntimeException("frontend errors: " + f.errors());
        }
        JsBackend.JsCodegenResult res = JsBackend.generate(f.program(),
            f.checkResult(), "jstest-" + name + ".deal", "Main",
            Map.of(), Map.of(), false, SemanticProfile.LEGACY_SAFE_INT);
        if (res.hasErrors()) {
            throw new RuntimeException("codegen errors: " + res.diagnostics());
        }
        Path dir = deployArtifacts(res, "Main.js");
        Files.writeString(dir.resolve("counter_runner.js"),
            COUNTER_RUNNER_SOURCE);
        NodeResult result = runNodeScript(dir, "counter_runner.js");
        deleteDir(dir);
        return result;
    }

    private static final String COUNTER_RUNNER_SOURCE = String.join("\n",
        "\"use strict\";",
        "const $rt = require(\"./deal/runtime\");",
        "const $entry = require(\"./Main\");",
        "let $mainCalls = 0;",
        "let $oracleCalls = 0;",
        "const $mainF = $entry.main.$f;",
        "$entry.main.$f = function() { $mainCalls++; return $mainF(); };",
        "const $oracleF = $entry.oracle.$f;",
        "$entry.oracle.$f = function() { $oracleCalls++; return $oracleF(); };",
        "$rt.invokeAsyncExport($entry, \"oracle\", \"null\").then((r) => {",
        "  process.stdout.write(\"INVOKE_ONCE_OK: main=\" + $mainCalls",
        "    + \" oracle=\" + $oracleCalls + \" value=\" + r.$value + \"\\n\");",
        "  if (r.$ok !== true || $mainCalls !== 1 || $oracleCalls !== 1) {",
        "    process.exit(1);",
        "  }",
        "}, (err) => {",
        "  process.stderr.write(\"INVOKER_THREW: \" + err + \"\\n\");",
        "  process.exit(1);",
        "});",
        "");

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

    /**
     * Strict backend-field pins (ISSUE-0169 remediation, ISSUE-0471):
     * {@code "js"} is part of the strict v1.2 backend set
     * ({@code "luajit"} | {@code "jvm"} | {@code "js"}) — a manifest
     * value of {@code "js"} parses cleanly and publishes the backend,
     * while case/whitespace variants and unknown values stay exactly
     * one E2010 at the backend value range naming the supported set.
     */
    private static void testDealConfigBackendJs() {
        System.out.println("-- Strict manifest backend field: js accepted --");

        StrictManifestParser.StrictManifestParseResult ok =
            StrictManifestParser.parse("deal.json",
                "{\"languageVersion\": \"1.2\", \"backend\": \"js\"}");
        check(ok.manifest() != null && ok.failure() == null,
            "deal.json backend js parses strictly: " + ok.failure());
        if (ok.manifest() != null) {
            check("js".equals(ok.manifest().backend()),
                "the strict manifest publishes backend 'js'");
        }

        for (String bad : new String[] {
                "{\"languageVersion\": \"1.2\", \"backend\": \"  Js \"}",
                "{\"languageVersion\": \"1.2\", \"backend\": \"wasm\"}"}) {
            StrictManifestParser.StrictManifestParseResult r =
                StrictManifestParser.parse("deal.json", bad);
            check(r.manifest() == null && r.failure() != null,
                "deal.json " + bad + " is rejected strictly");
            if (r.failure() != null) {
                CompilerDiagnostic d = r.failure();
                check("E2010".equals(d.code()) && "error".equals(d.severity()),
                    "unsupported backend is an E2010 error: " + d);
                check(d.message().contains("luajit") && d.message().contains("jvm")
                        && d.message().contains("js"),
                    "error message names supported backends incl. js: "
                        + d.message());
            }
        }
    }

    /**
     * The production CLI {@code --backend js} path with the DEFAULT
     * output {@code build/js} (ISSUE-0169 remediation, ISSUE-0471): a
     * real {@code java -cp build deal.Main compile <entry> --backend
     * js} subprocess over a temp project whose manifest carries no
     * backend — the CLI alias selects {@link Backend#JS} through the
     * production locator, the default output is the manifest-relative
     * {@code build/js}, and the compilation deploys the runtime and
     * stdlib copies. The subprocess working directory is the repository
     * root (the deployment copies fall back to the repo-root
     * {@code deal/runtime.js} and {@code std/*.js} sources) while the
     * entry path is absolute, so the backend-dependent default output
     * still lands at {@code <manifestDirectory>/build/js}.
     */
    private static void testCliBackendFlagJsDefaultOutput() throws Exception {
        System.out.println("-- CLI --backend js: production alias, default build/js --");

        Path proj = Files.createTempDirectory("jstest_cli_");
        Files.createDirectories(proj.resolve("src"));
        // A valid strict manifest without a backend field: the CLI
        // alias is the backend-selection authority.
        Files.writeString(proj.resolve("deal.json"),
            "{\"languageVersion\": \"1.2\", \"moduleRoots\": [\"src\"]}\n",
            StandardCharsets.UTF_8);
        Path entry = proj.resolve("src/cli_js_main.deal");
        Files.writeString(entry, """
            export function main(): null { return null; }
            export function run(): int { return 1; }
            """);

        String buildCp = Path.of("build").toAbsolutePath().toString();
        ProcessBuilder pb = new ProcessBuilder("java", "-cp", buildCp,
            "deal.Main", "compile", entry.toString(), "--backend", "js");
        pb.directory(Path.of("").toAbsolutePath().normalize().toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(),
            StandardCharsets.UTF_8);
        int rc = p.waitFor();
        check(rc == 0, "CLI --backend js compiles through the production"
            + " path (exit 0): " + out);
        check(Files.isRegularFile(proj.resolve("build/js/cli_js_main.js")),
            "the default build/js output holds the emitted entry artifact");
        check(Files.isRegularFile(proj.resolve("build/js/deal/runtime.js")),
            "the default build/js output deploys deal/runtime.js");
        check(!out.contains("E2010"),
            "no E2010 for the production js alias: " + out);

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

    /**
     * JSONable emission pins (js-v12-jsonable-completion D1-D2): the
     * two exported wrappers plus the hidden C$fields descriptor export
     * in the pinned shapes — canonical wrapper signatures over the
     * canonical identity, the walker calls with the per-construction
     * defaults thunk, declaration-order descriptor entries with one
     * role per key, and the export assignments under the raw $-sigil
     * keys.
     */
    private static void testJsonableEmissionPins() {
        System.out.println("-- Jsonable emission: C$fromJson/C$toJson wrappers and the hidden C$fields export --");

        JsBackend.JsCodegenResult res = generate("""
            // @jsonable
            export class U {
              name: string = "";
              nick?: string | null;
              age: int = 0;
            }
            export function test(): int { return 1; }
            """, "jsonable-pins");
        check(res != null && !res.hasErrors(), "jsonable codegen clean: "
            + (res == null ? "<null>" : res.diagnostics()));
        if (res == null || res.hasErrors()) return;

        String js = res.source();
        check(js.contains("let U$new; let U$meta; let U$fromJson; "
            + "let U$toJson; let U$fields;"),
            "the header predeclares the $-sigil jsonable artifact bindings");
        check(js.contains("U$fromJson = $rt.function(\"(string)->?@Main/U\", "
            + "(s, $file, $line, $column) => $rt.jsonFromJson(\"@Main/U\", "
            + "U$fields, () => ({ [\"name\"]: \"\", [\"nick\"]: $rt.MISSING, "
            + "[\"age\"]: 0 }), s, $file, $line, $column));"),
            "U$fromJson carries the canonical (string)->?@Main/U signature "
                + "and the walker call with the identity, C$fields, and the "
                + "per-construction defaults thunk");
        check(js.contains("U$toJson = $rt.function(\"(@Main/U)->string\", "
            + "(v, $file, $line, $column) => $rt.jsonToJson(\"@Main/U\", v, "
            + "U$fields, $file, $line, $column));"),
            "U$toJson carries the canonical (@Main/U)->string signature "
                + "and the walker call with the identity backstop");
        check(js.contains("U$fields = [{ name: \"name\", jtype: \"string\", "
            + "optional: false, nullable: false, hasDefault: true }, "
            + "{ name: \"nick\", jtype: \"string\", optional: true, "
            + "nullable: true, hasDefault: false }, "
            + "{ name: \"age\", jtype: \"int\", optional: false, "
            + "nullable: false, hasDefault: true }];"),
            "U$fields is the declaration-order descriptor array with one "
                + "role per key (name/jtype/optional/nullable/hasDefault)");
        check(js.contains("$rt.setProp($exports, \"U$fromJson\", U$fromJson);"),
            "the generated C$fromJson wrapper is exported under its raw key");
        check(js.contains("$rt.setProp($exports, \"U$toJson\", U$toJson);"),
            "the generated C$toJson wrapper is exported under its raw key");
        check(js.contains("$rt.setProp($exports, \"U$fields\", U$fields);"),
            "the hidden C$fields descriptor export is emitted");
    }

    /**
     * The @jsonable roundtrip through the real node binary
     * (js-v12-jsonable-completion): toJson → fromJson → toJson
     * preserves every field value, the three-state optional field
     * omits MISSING and keeps present null, and the top-level gate/
     * extra-key rejections return the DEAL null.
     */
    private static void testJsonableRoundTripNode() {
        System.out.println("-- Node: @jsonable C$fromJson/C$toJson roundtrip --");
        if (!nodeAvailable) { skipNode("jsonable roundtrip"); return; }
        try {
            NodeResult run = runDealNode("""
                // @jsonable
                export class U {
                  name: string = "";
                  nick?: string | null;
                  age: int = 0;
                }
                export function test(): int {
                  let u: U = { name: "Ada", nick: null, age: 36 };
                  let json: string = U$toJson(u);
                  let v: U | null = U$fromJson(json);
                  if (v !== null) {
                    if (v.name !== "Ada" || v.nick !== null || v.age !== 36) { return 0; }
                    let round: string = U$toJson(v);
                    let w: U | null = U$fromJson(round);
                    if (w !== null) {
                      if (w.nick !== null || w.age !== 36) { return 0; }
                    } else { return 0; }
                  } else { return 0; }
                  let missing: U = { name: "A" };
                  let j1: string = U$toJson(missing);
                  if (j1 !== "{\\"name\\":\\"A\\",\\"age\\":0}") { return 0; }
                  let m2: U | null = U$fromJson(j1);
                  if (m2 !== null) {
                    if (has(m2.nick)) { return 0; }
                  } else { return 0; }
                  let extra: U | null = U$fromJson("{\\"name\\":\\"Ada\\",\\"extra\\":1}");
                  if (extra !== null) { return 0; }
                  let scalar: U | null = U$fromJson("42");
                  if (scalar !== null) { return 0; }
                  return 1;
                }
                """, "jsonable-roundtrip");
            check(run.exitCode() == 0 && run.output().equals("1"),
                "the jsonable roundtrip runs under node (1): exit "
                    + run.exitCode() + ", output '" + run.output() + "'");
        } catch (Exception e) {
            fail("jsonable roundtrip node run: " + e.getMessage());
        }
    }

    /**
     * Orchestrator-level cross-module @jsonable pins
     * (js-v12-jsonable-completion D1): a cross-module class-typed field
     * carries the imported module's {@code Lib.C$fields} export, a
     * cross-module array-of-class field carries the same reference in
     * its element entry, and the emitted project roundtrips and reads
     * cross-module class-field arrays through the real node binary
     * (the common-semantic-lowering checked-fact regression: an index
     * into a cross-module class-field array carries its checked type).
     */
    private static void testOrchestratorJsonableCrossModule() throws Exception {
        System.out.println("-- Orchestrator: cross-module @jsonable fields references and roundtrip --");

        writeFile("xjson_proj/deal.json",
            "{\"languageVersion\": \"1.2\", \"backend\": \"js\"}");
        writeFile("xjson_proj/src/lib_a.deal", """
            // @jsonable
            export class Inner {
              v: int = 0;
            }
            export function inner(v: int): Inner {
              return { v: v };
            }
            """);
        writeFile("xjson_proj/src/lib_b.deal", """
            import * as A from "./lib_a"

            // @jsonable
            export class Wrap {
              inner: A.Inner = {};
              children: A.Inner[] = [];
            }
            export function make(a: int): Wrap {
              return { inner: A.inner(a), children: [A.inner(a + 1)] };
            }
            """);
        writeFile("xjson_proj/src/xjson_main.deal", """
            import * as B from "./lib_b"
            import * as console from "std/console"
            export function main(): null {
              let w: B.Wrap = B.make(7);
              let json: string = B.Wrap$toJson(w);
              let w2: B.Wrap | null = B.Wrap$fromJson(json);
              if (w2 === null) { console.log("xmod-jsonable-null"); return null; }
              if (w2.inner.v !== 7) { console.log("xmod-jsonable-inner-mismatch"); return null; }
              if (w2.children[0].v !== 8) { console.log("xmod-jsonable-array-mismatch"); return null; }
              console.log("xmod-jsonable-ok");
              return null;
            }
            """);

        Path entryFile = tmpDir.resolve("xjson_proj/src/xjson_main.deal").toAbsolutePath();
        Path outputDir = tmpDir.resolve("xjson_proj/build/js");
        List<Path> roots = List.of(tmpDir.resolve("xjson_proj/src").toAbsolutePath());

        // Test-only isolated-phase orchestrator path with the legacy
        // roots inputs (the injected deal.json is not consulted; the
        // production manifest/CLI path is pinned separately by
        // testCliBackendFlagJsDefaultOutput / testCliDefaultOutputBuildJs
        // / testDealJsonBackendAcceptance).
        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            entryFile, outputDir, false, false, false, Backend.JS,
            (Map<String, String>) null, roots,
            Path.of(".").toAbsolutePath().normalize());
        boolean success = orchestrator.compile();
        check(success, "cross-module jsonable project compiles: "
            + orchestrator.diagnostics());
        if (!success) return;

        Path libB = outputDir.resolve("lib_b.js");
        check(Files.exists(libB), "orchestrator wrote lib_b.js");
        if (Files.exists(libB)) {
            String js = Files.readString(libB);
            check(js.contains("fields: A.Inner$fields"),
                "the cross-module class-typed field references the imported "
                    + "module's A.Inner$fields export");
            check(js.contains("element: { jtype: \"class\", className: \"@"),
                "the cross-module array-of-class element entry carries the "
                    + "canonical class identity text");
        }

        if (nodeAvailable) {
            ProcessBuilder node = new ProcessBuilder("node", "xjson_main.js");
            node.directory(outputDir.toFile());
            node.redirectErrorStream(true);
            Process np = node.start();
            String nout = new String(np.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8).trim();
            int nrc = np.waitFor();
            check(nrc == 0 && nout.equals("xmod-jsonable-ok"),
                "node xjson_main.js roundtrips the cross-module jsonable "
                    + "graph and reads the class-field array: exit " + nrc
                    + ", output '" + nout + "'");
        } else {
            skipNode("orchestrator cross-module jsonable node run");
        }
    }

    private static void testNestedClassEmissionPins() {
        System.out.println("-- Nested class declarations: scope-local artifacts (ISSUE-0318) --");

        // The former D6 rejection case now generates clean: a block-level
        // class emits the scope-local predeclare/assign pair inside the
        // enclosing block, the defaults thunk closes over the declaring
        // scope, and the identity is the canonical module-qualified text
        // (js-v12-completion-architecture D4).
        JsBackend.JsCodegenResult nested = generate("""
            export function test(): int {
              let seed: int = 40;
              class Inner { v: int = seed + 2; }
              return 1;
            }
            """, "nestedclass");
        check(nested != null && !nested.hasErrors(),
            "nested class declaration generates with no diagnostics: "
                + (nested == null ? "<null>" : nested.diagnostics()));
        if (nested == null || nested.hasErrors()) return;

        String js = nested.source();
        int wrapper = js.indexOf("test$f(");
        int predeclare = js.indexOf("let Inner$new; let Inner$meta;");
        int assign = js.indexOf("Inner$new = (provided, $file, $line, $column)");
        check(wrapper >= 0 && predeclare > wrapper && assign > predeclare,
            "the scope-local predeclare pair sits at the top of the "
                + "enclosing block before the declaration-site assignment");
        check(!js.contains("let Inner$new; let Inner$meta;\nconst $rt"),
            "the nested class artifacts never predeclare in the module header");
        check(js.contains("let Inner$new; let Inner$meta;"),
            "the scope-local predeclare pair binds the artifact names");
        check(js.contains("Inner$new = (provided, $file, $line, $column) => "
                + "$rt.makeClass(\"Inner\", \"@Main/Inner\", "
                + "() => ({ [\"v\"]: $rt.intAdd(seed, 2, "),
            "the declaration-site construction closure carries the "
                + "canonical identity and a defaults thunk closing over seed");
        check(js.contains("Inner$meta = { $kind: \"class\", "
                + "$classname: \"@Main/Inner\" };"),
            "the inline META pair carries the canonical identity text");

        // A scope-local default function is captured by the thunk.
        JsBackend.JsCodegenResult func = generate("""
            export function test(): int {
              let seed: int = 40;
              function next(): int { return seed + 2; }
              class Inner { v?: int = next(); }
              return 1;
            }
            """, "nestedclass-func");
        check(func != null && !func.hasErrors(),
            "nested class with a scope-local default function generates: "
                + (func == null ? "<null>" : func.diagnostics()));
        if (func != null && !func.hasErrors()) {
            check(func.source().contains(
                    "() => ({ [\"v\"]: next.$f("),
                "the defaults thunk captures the scope-local function "
                    + "(call through its wrapper)");
        }

        // An exported nested class writes its two export assignments
        // inline at the declaration site (the scope-local artifacts are
        // not visible at the module-end section) through the ordinary
        // raw-key own-property-safe path.
        JsBackend.JsCodegenResult exported = generateUnshaped("""
            export function test(): int {
              export class Outer { x: int = 1; }
              return 1;
            }
            """, "nestedclass-export");
        check(exported != null && !exported.hasErrors(),
            "exported nested class generates with no diagnostics: "
                + (exported == null ? "<null>" : exported.diagnostics()));
        if (exported != null && !exported.hasErrors()) {
            String xjs = exported.source();
            int block = xjs.indexOf("let Outer$new; let Outer$meta;");
            int metaExport = xjs.indexOf(
                "$rt.setProp($exports, \"Outer\", Outer$meta);");
            int newExport = xjs.indexOf(
                "$rt.setProp($exports, \"Outer$new\", Outer$new);");
            int testExport = xjs.indexOf(
                "$rt.setProp($exports, \"test\", test);");
            check(block >= 0 && metaExport > block && newExport > metaExport,
                "the exported nested class assigns its inline exports at "
                    + "the declaration site inside the block");
            check(metaExport > 0 && metaExport < testExport,
                "the inline META export precedes the module-end section");
            check(xjs.contains("$rt.setProp($exports, \"Outer\", Outer$meta);")
                    && xjs.contains(
                        "$rt.setProp($exports, \"Outer$new\", Outer$new);"),
                "the exported nested class exports C$meta and the hidden "
                    + "C$new under the ordinary raw keys");
        }

        // A nested exported class carrying the @jsonable directive is
        // never jsonable (module-level-export-class-only, checker-owned):
        // the backend applies no @jsonable generation at a nested site —
        // no C$fromJson/C$toJson/C$fields artifacts and no jsonable
        // exports (js-v12-completion-architecture D4).
        JsBackend.JsCodegenResult jsonableNested = generateUnshaped("""
            export function test(): int {
              // @jsonable
              export class Outer { x: int = 1; }
              return 1;
            }
            """, "nestedclass-jsonable");
        check(jsonableNested != null && !jsonableNested.hasErrors(),
            "a nested @jsonable export class generates with no diagnostics: "
                + (jsonableNested == null ? "<null>" : jsonableNested.diagnostics()));
        if (jsonableNested != null && !jsonableNested.hasErrors()) {
            String jjs = jsonableNested.source();
            check(!jjs.contains("Outer$fromJson")
                    && !jjs.contains("Outer$toJson")
                    && !jjs.contains("Outer$fields"),
                "the backend applies no @jsonable generation to a nested "
                    + "class (no C$fromJson/C$toJson/C$fields text)");
            check(!jjs.contains("$rt.setProp($exports, \"Outer$fromJson\""),
                "no jsonable wrapper export assignment references a nested "
                    + "site's scope-local bindings");
        }

        // Scan: the nested-class E6000 arm is gone from the backend.
        try {
            String backend = Files.readString(
                Path.of("deal/codegen/js/JsBackend.java"));
            check(!backend.contains("nested class declarations are not "
                    + "supported"),
                "deal/codegen/js/JsBackend.java no longer carries the "
                    + "nested-class E6000 message");
        } catch (IOException e) {
            fail("nested-class retirement scan failed: " + e.getMessage());
        }
    }

    private static void testNestedClassNodeSemantics() throws Exception {
        System.out.println("-- Node: nested-class defaults scope and per-construction evaluation --");
        if (!nodeAvailable) { skipNode("nested-class defaults scope"); return; }

        // A block-level class whose optional default calls a scope-local
        // function: two constructions observe independent evaluations of
        // the capturing default (40 + 2 then 100 + 2).
        NodeResult run = runDealNode("""
            export function test(): int {
              let seed: int = 40;
              function next(): int { return seed + 2; }
              class Inner {
                v?: int = next();
              }
              let a: Inner = { };
              seed = 100;
              let b: Inner = { };
              if (!has(a.v) || !has(b.v)) { return 0; }
              let av: int | null = a.v;
              if (av !== null) {
                if (av !== 42) { return 0; }
              } else {
                return 0;
              }
              let bv: int | null = b.v;
              if (bv !== null) {
                if (bv !== 102) { return 0; }
              } else {
                return 0;
              }
              return 1;
            }
            """, "nestedclass-node");
        check(run.exitCode() == 0 && run.output().equals("1"),
            "per-construction defaults evaluate in the declaring scope "
                + "(42 then 102): " + run.output() + " (exit "
                + run.exitCode() + ")");

        // The exported nested class's module exports carry the scope-local
        // C$meta and the hidden <C>$new keys, and the META carries the
        // canonical identity text (js-v12-completion-architecture D3/D4).
        Frontend f = compileFrontendUnshaped("""
            export function test(): int {
              export class Outer { x: int = 1; }
              return 1;
            }
            """, "jstest-nestedclass-export.deal");
        check(f.errors().isEmpty(),
            "exported nested class frontend clean: " + f.errors());
        if (!f.errors().isEmpty()) return;
        JsBackend.JsCodegenResult res = JsBackend.generate(f.program(),
            f.checkResult(), "jstest-nestedclass-export.deal", "Main",
            Map.of(), Map.of(), false);
        check(res != null && !res.hasErrors(),
            "exported nested class codegen clean: "
                + (res == null ? "<null>" : res.diagnostics()));
        if (res == null || res.hasErrors()) return;
        Path dir = deployArtifacts(res, "Main.js");
        Files.writeString(dir.resolve("probe.js"),
            "const m = require(\"./Main.js\");\n"
            + "m.test.$f(\"probe\", 1, 1);\n"
            + "if (m.Outer == null || m.Outer.$kind !== \"class\")"
            + " { throw new Error(\"exports.Outer missing\"); }\n"
            + "if (m.Outer.$classname !== \"@Main/Outer\")"
            + " { throw new Error(\"identity: \" + m.Outer.$classname); }\n"
            + "if (typeof m[\"Outer$new\"] !== \"function\")"
            + " { throw new Error(\"exports.Outer$new missing\"); }\n"
            + "const c = m[\"Outer$new\"]({}, \"probe\", 1, 1);\n"
            + "if (c.x !== 1) { throw new Error(\"construction: \" + c.x); }\n"
            + "console.log(\"nested-export-ok\");\n");
        NodeResult probe = runNodeScript(dir, "probe.js");
        deleteDir(dir);
        check(probe.exitCode() == 0
                && probe.output().equals("nested-export-ok"),
            "the exported nested class's module exports carry C$meta and "
                + "C$new with the canonical identity: " + probe.output()
                + " (exit " + probe.exitCode() + ")");

        // Per-scope shadowing: a function-local class shadowing a
        // module-level class of the same name stays legal strict-mode JS
        // (the nested predeclared pair shadows the module-level pair in
        // its own block scope — never a same-scope duplicate binding),
        // and construction inside the function uses the nested class.
        NodeResult shadow = runDealNode("""
            class Shadowed { v: int = 1; }
            export function test(): int {
              class Shadowed { w?: int = 2; }
              let s: Shadowed = { };
              if (!has(s.w)) { return 0; }
              let w: int | null = s.w;
              if (w !== null) {
                if (w !== 2) { return 0; }
              } else {
                return 0;
              }
              return 1;
            }
            """, "nestedclass-shadow");
        check(shadow.exitCode() == 0 && shadow.output().equals("1"),
            "a function-local class shadows the module-level class of "
                + "the same name (nested construction): " + shadow.output()
                + " (exit " + shadow.exitCode() + ")");
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

        // @jsonable emission retired the E6000 arm
        // (js-v12-jsonable-completion D1): the passing emission pins
        // live in testJsonableEmissionPins(). The nested-class E6000
        // arm retired with ISSUE-0318: the passing emission pins live
        // in testNestedClassEmissionPins().

        // The host-ABI E6000 arm retired with ISSUE-0328
        // (js-v12-host-abi-completion D1): a host-module import emits
        // the $rt.loadHost binding with the declared map — the passing
        // emission and runtime pins live in
        // testHostAbiEmissionPins/testHostAbiOrchestratorNode.

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
        // ISSUE-0273 D9 re-key: the trigger is the extern-C import set,
        // not a lexer-attached token directive (the source carries no
        // @extern-c comment — on an implementation file it would be
        // E1046).
        JsBackend.JsCodegenResult extern = generateWithExternC("""
            import * as ffi from "myffi"
            export function test(): int { return 1; }
            """, "rej-extern", "Main", Map.of(),
            Map.of("myffi", new HostModuleDeclarations(Map.of(), Map.of())),
            Set.of("myffi"), false);
        check(extern != null && extern.hasErrors(), "@extern-c import rejected");
        if (extern != null) {
            check(extern.diagnostics().stream().anyMatch(d ->
                    "E6003".equals(d.code())
                        && "error".equals(d.severity())
                        && externDetailText.equals(d.message())
                        && d.range().startLine() == 1),
                "@extern-c rejection is E6003 with the exact current detail "
                    + "text at the import statement: " + extern.diagnostics());
        }

        // The defensive bytes E6000 arms retired with the v1.2 bytes lane
        // (js-v12-int32-bytes D3/D4): a bytes-spelled type and a bytes(n)
        // call now compile clean and lower to the $rt.bytes* members — the
        // passing pins live in testBytesEmissionPins/testBytesNodeSemantics.
        JsBackend.JsCodegenResult bytesClean = generate("""
            export function test(): null {
              let b: bytes = bytes(3);
              if (b.length !== 3) {
                throw { code: "TEST_FAIL", message: "length" };
              }
              return null;
            }
            """, "rej-bytes-retired");
        check(bytesClean != null && !bytesClean.hasErrors(),
            "the retired defensive bytes rejection never fires: "
                + (bytesClean == null ? "<null>" : bytesClean.diagnostics()));
    }


    /**
     * The ISSUE-0328 host-ABI emission pins
     * (js-v12-host-abi-completion D1/D4): the retired E6000 arm's
     * successor — the loadHost import binding, the emitter-rendered
     * declared map (canonical function descriptors; canonical externals
     * class identities plus the declared field-descriptor array), and
     * the D4 read-site deferral (a host-call argument emits the raw
     * expression — the boundary wrapper raises E8010, never a read-site
     * E8001).
     */
    private static void testHostAbiEmissionPins() throws Exception {
        System.out.println("-- Host ABI: loadHost binding, declared map, boundary deferral (ISSUE-0328) --");
        String q = "\"";
        HostModuleDeclarations fnDecl = new HostModuleDeclarations(
            Map.of("hostFn", new Type.Func(List.of(Type.Int.INSTANCE),
                Type.Int.INSTANCE)),
            Map.of());

        // (1) The host import binding: the raw specifier verbatim with
        // the project-import relative rule, the declared map rendered
        // per export, and the member call through the wrapper $f.
        Frontend fnFrontend = compileFrontend("""
            import * as h from "./hostmod"
            export function test(): int { return h.hostFn(1); }
            """, "jstest-host-abi-fn.deal",
            new FixedModuleResolver(Map.of("./hostmod",
                Map.of("hostFn", new Type.Func(
                    List.of(Type.Int.INSTANCE), Type.Int.INSTANCE)))));
        if (fnFrontend.program() == null) {
            fail("host-abi fn frontend failed: " + fnFrontend.errors());
            return;
        }
        check(fnFrontend.errors().isEmpty(),
            "the host import type-checks through the resolver: "
                + fnFrontend.errors());
        JsBackend.JsCodegenResult fn = JsBackend.generate(
            fnFrontend.program(), fnFrontend.checkResult(),
            "jstest-host-abi-fn.deal", "Main", Map.of(),
            Map.of("./hostmod", fnDecl), false);
        check(fn != null && !fn.hasErrors(),
            "the host function import generates with no diagnostics: "
                + (fn == null ? "<null>" : fn.diagnostics()));
        if (fn != null && !fn.hasErrors()) {
            String js = fn.source();
            check(js.contains("$rt.loadHost($require(" + q + "./hostmod"
                    + q + "), {" + q + "hostFn" + q + ": { $k: " + q
                    + "function" + q + ", $d: " + q + "(int)->int" + q
                    + " }})"),
                "the host binding renders the loadHost call with the raw "
                    + "specifier and the canonical function declared map: "
                    + js);
            check(js.contains("h.hostFn.$f(1, "),
                "host member calls route through the wrapper $f");
        }

        // (1b) The nested-module relative rule: a nested emitting module
        // requires the bare host specifier with the ../ prefix — the
        // same project-import relative rule, applied to the raw
        // specifier verbatim.
        Frontend nestedFrontend = compileFrontend("""
            import * as h from "host/mod"
            export function test(): int { return h.hostFn(1); }
            """, "jstest-host-abi-nested.deal",
            new FixedModuleResolver(Map.of("host/mod",
                Map.of("hostFn", new Type.Func(
                    List.of(Type.Int.INSTANCE), Type.Int.INSTANCE)))));
        if (nestedFrontend.program() == null) {
            fail("host-abi nested frontend failed: "
                + nestedFrontend.errors());
            return;
        }
        check(nestedFrontend.errors().isEmpty(),
            "the bare host import type-checks through the resolver: "
                + nestedFrontend.errors());
        JsBackend.JsCodegenResult nested = JsBackend.generate(
            nestedFrontend.program(), nestedFrontend.checkResult(),
            "jstest-host-abi-nested.deal", "app.main", Map.of(),
            Map.of("host/mod", fnDecl), false);
        check(nested != null && !nested.hasErrors(),
            "the nested host import generates with no diagnostics: "
                + (nested == null ? "<null>" : nested.diagnostics()));
        if (nested != null && !nested.hasErrors()) {
            check(nested.source().contains(
                    "$rt.loadHost($require(" + q + "../host/mod" + q + "), {"),
                "the nested emitting module requires ../host/mod — the "
                    + "same relative rule every project import uses: "
                    + nested.source());
        }

        // (2) D4 read-site deferral: a typed table read materializing a
        // host-call argument emits the raw expression — no E8001
        // read-site check — and the runtime boundary wrapper raises the
        // pinned E8010 parameter mismatch under node.
        Frontend deferFrontend = compileFrontend("""
            import * as h from "./hostmod"
            export function test(): int {
              let holder: table = { item: "x" };
              return h.hostFn(holder.item);
            }
            """, "jstest-host-abi-defer.deal",
            new FixedModuleResolver(Map.of("./hostmod",
                Map.of("hostFn", new Type.Func(
                    List.of(Type.Int.INSTANCE), Type.Int.INSTANCE)))));
        if (deferFrontend.program() == null) {
            fail("host-abi defer frontend failed: "
                + deferFrontend.errors());
            return;
        }
        check(deferFrontend.errors().isEmpty(),
            "the deferred host-argument case type-checks: "
                + deferFrontend.errors());
        JsBackend.JsCodegenResult defer = JsBackend.generate(
            deferFrontend.program(), deferFrontend.checkResult(),
            "jstest-host-abi-defer.deal", "Main", Map.of(),
            Map.of("./hostmod", fnDecl), false);
        check(defer != null && !defer.hasErrors(),
            "the deferred host-argument case generates with no "
                + "diagnostics: " + (defer == null ? "<null>"
                    : defer.diagnostics()));
        if (defer != null && !defer.hasErrors()) {
            String js = defer.source();
            check(js.contains("h.hostFn.$f(holder.get(" + q + "item" + q
                    + "), "),
                "the host-call argument is the raw table read (D4 "
                    + "read-site deferral, no pre-check): " + js);
            check(!js.contains("checkType(" + q + "int" + q
                    + ", holder"),
                "no read-site checkType wraps the host-call argument");
            if (nodeAvailable) {
                try {
                    Path dir = deployArtifacts(defer, "Main.js");
                    Files.writeString(dir.resolve("hostmod.js"),
                        "\"use strict\";\n"
                        + "module.exports = { hostFn: function (x) {\n"
                        + "  return x + 1;\n"
                        + "} };\n");
                    Files.writeString(dir.resolve("JsConformanceRunner.js"),
                        BackendConformanceTest.buildJsRunner(
                            compileFrontend("""
                            import * as h from "./hostmod"
                            export function test(): int {
                              let holder: table = { item: "x" };
                              return h.hostFn(holder.item);
                            }
                            """, "jstest-host-abi-defer-run.deal",
                            new FixedModuleResolver(Map.of("./hostmod",
                                Map.of("hostFn",
                                    new Type.Func(List.of(Type.Int.INSTANCE),
                                        Type.Int.INSTANCE)))))
                            .program()));
                    NodeResult run = runNodeScript(dir,
                        "JsConformanceRunner.js");
                    check(run.exitCode() == 1
                            && run.output().contains("DEAL_ERROR_CODE: E8010")
                            && run.output().contains(
                                "parameter 1 type mismatch"),
                        "the boundary wrapper raises E8010 for the "
                            + "wrong-kind host argument, never a read-site "
                            + "E8001: exit " + run.exitCode()
                            + ", output '" + run.output() + "'");
                    deleteDir(dir);
                } catch (Exception e) {
                    fail("host-abi deferral node run: " + e.getMessage());
                }
            } else {
                skipNode("host-abi deferral node run");
            }
        }

        // (3) The class declared map: canonical externals identity plus
        // the declared field-descriptor array — parsed and resolved
        // through the same ExportExtractor surface the orchestrator
        // gather uses, generated over the production seam with the host
        // module's externals classification.
        String declSource = """
            export class Endpoint {
              path: string;
            }
            export class ServerConfig {
              port: int;
              endpoint: Endpoint;
              tags?: string[];
              note?: string | null;
            }
            export function describe(s: ServerConfig): string;
            """;
        LexResult dlex = new Lexer(declSource, "hostmod.d.deal").tokenize();
        check(dlex != null && !dlex.hasErrors(),
            "the host declaration lexes clean");
        if (dlex == null || dlex.hasErrors()) return;
        ParseResult dparse = new Parser(dlex.tokens(), "hostmod.d.deal", dlex.directiveEvents())
            .parse();
        check(dparse != null && !dparse.hasErrors(),
            "the host declaration parses clean");
        if (dparse == null || dparse.hasErrors()) return;
        // The v1.2 identity carriage: the host declaration's classes
        // carry the externals classification (the same surface the
        // production seam consumes), never the dotted-path default.
        Map<String, CanonicalModuleIdentity> hostmodClassification =
            new LinkedHashMap<>();
        hostmodClassification.put("hostmod",
            new CanonicalModuleIdentity.ExternalModule("hostmod"));
        ExportExtractor extractor = new ExportExtractor("hostmod", true,
            hostmodClassification::get);
        Map<String, Type> declExports = extractor.extract(dparse.program());
        Map<String, List<HostModuleDeclarations.HostField>> declFields =
            new LinkedHashMap<>();
        for (StatementNode stmt : dparse.program().statements()) {
            ClassDeclaration cd = null;
            if (stmt instanceof ClassDeclaration c) {
                cd = c;
            } else if (stmt instanceof ExportDeclaration ed
                    && ed.declaration() instanceof ClassDeclaration c) {
                cd = c;
            }
            if (cd == null) continue;
            List<HostModuleDeclarations.HostField> fields =
                new ArrayList<>();
            for (ClassField cf : cd.fields()) {
                fields.add(new HostModuleDeclarations.HostField(cf,
                    extractor.resolveFieldType(cf.type())));
            }
            declFields.putIfAbsent(cd.name(), fields);
        }

        Map<String, CanonicalModuleIdentity> byPath =
            new LinkedHashMap<>();
        byPath.put("", CanonicalModuleIdentity.BuiltinModule.INSTANCE);
        byPath.put("Main", new CanonicalModuleIdentity.ProjectModule(
            new ProjectModuleIdentity("Main", "Main", List.of())));
        byPath.put("hostmod",
            new CanonicalModuleIdentity.ExternalModule("hostmod"));
        ModuleIdentityResolver.IdentityIndex index =
            ModuleIdentityResolver.buildIndex(byPath);

        Frontend cf = compileFrontend("""
            import * as cfg from "./hostmod"
            export function test(): null { return null; }
            """, "jstest-host-abi-class.deal",
            new FixedModuleResolver(Map.of("./hostmod", declExports)));
        if (cf.program() == null) {
            fail("host-abi class frontend failed: " + cf.errors());
            return;
        }
        JsBackend.JsCodegenResult cls = JsBackend.generate(cf.program(),
            cf.checkResult(), "jstest-host-abi-class.deal", "Main",
            Map.of(), Map.of("./hostmod",
                new HostModuleDeclarations(declExports, declFields)),
            Set.of(), false, index, index.moduleIdentityLookup(), null,
            SemanticProfile.LEGACY_SAFE_INT);
        check(cls != null && !cls.hasErrors(),
            "the host class declared map generates clean: "
                + (cls == null ? "<null>" : cls.diagnostics()));
        if (cls != null && !cls.hasErrors()) {
            String js = cls.source();
            check(js.contains("$k: " + q + "class" + q + ", $d: " + q
                    + "@$external/hostmod/ServerConfig" + q),
                "the class entry carries the canonical externals "
                    + "identity: " + js);
            check(js.contains("{ name: " + q + "endpoint" + q + ", $d: "
                    + q + "@$external/hostmod/Endpoint" + q
                    + ", optional: false, nullable: false, "
                    + "hasDefault: false }"),
                "the same-module class field descriptor resolves to the "
                    + "declaring module's canonical identity: " + js);
            check(js.contains("{ name: " + q + "tags" + q + ", $d: " + q
                    + "[string]" + q + ", optional: true, nullable: false,"
                    + " hasDefault: false }"),
                "the optional array field renders the canonical [D] "
                    + "descriptor and the optional flag: " + js);
            check(js.contains("{ name: " + q + "note" + q + ", $d: " + q
                    + "?string" + q + ", optional: true, nullable: true,"
                    + " hasDefault: false }"),
                "the nullable optional field renders the canonical ?D "
                    + "descriptor and both flags: " + js);
            check(js.contains(q + "describe" + q + ": { $k: " + q
                    + "function" + q + ", $d: " + q
                    + "(@$external/hostmod/ServerConfig)->string" + q
                    + " }"),
                "the class-typed parameter descriptor projects the "
                    + "canonical identity inside the function entry: "
                    + js);
        }
    }

    /**
     * The orchestrator-level host-ABI chain (ISSUE-0328): a real
     * deal.json externals wiring, the declaration-file gather with
     * class-field records, frontend host-class symbol synthesis, the
     * emitted loadHost artifact, and a node execution through a
     * deployed host implementation — the E6000 successor pin.
     */
    private static void testHostAbiOrchestratorNode() throws Exception {
        System.out.println("-- Orchestrator: host ABI end-to-end under node (ISSUE-0328) --");

        writeFile("hostjs_proj/deal.json",
            "{\"languageVersion\": \"1.2\", \"backend\": \"js\",\n"
            + " \"externals\": {\"host/cfg\": {\"declaration\": "
            + "\"bindings/cfg.d.deal\"}}}\n");
        writeFile("hostjs_proj/bindings/cfg.d.deal", """
            export class Endpoint {
              path: string;
            }
            export class ServerConfig {
              port: int;
              endpoint: Endpoint;
              tags?: string[];
              note?: string | null;
            }
            export function describe(s: ServerConfig): string;
            """);
        writeFile("hostjs_proj/src/hostjs_main.deal", """
            import * as cfg from "host/cfg"
            export function main(): null {
              let s: cfg.ServerConfig = {
                port: 9090,
                endpoint: { path: "/api" },
                tags: ["dev"],
                note: null,
              };
              let described: string = cfg.describe(s);
              if (described !== "/api:9090") {
                throw { code: "TEST_FAIL", message: "described" };
              }
              return null;
            }
            """);

        Path entryFile = tmpDir.resolve(
            "hostjs_proj/src/hostjs_main.deal").toAbsolutePath();
        Path outputDir = tmpDir.resolve("hostjs_proj/build/js");
        List<Path> roots = List.of(tmpDir.resolve(
            "hostjs_proj/src").toAbsolutePath());

        // ISSUE-0269: the externals authority flows through the
        // test-only synthesized context (raw specifier → declaration
        // path resolved from the entry directory — the injected
        // deal.json spelling "bindings/cfg.d.deal" relative to the
        // project root is "../bindings/cfg.d.deal" here).
        Map<String, String> externals = Map.of(
            "host/cfg", "../bindings/cfg.d.deal");
        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            entryFile, outputDir, false, false, false, Backend.JS,
            externals, roots, Path.of(".").toAbsolutePath().normalize());
        boolean success = orchestrator.compile();
        check(success, "the host-ABI project compiles (the E6000 arm "
            + "retired): " + orchestrator.diagnostics());
        if (!success) return;
        check(orchestrator.diagnostics().stream()
                .noneMatch(d -> "E6000".equals(d.code())),
            "no E6000 is reported for the host-ABI project: "
                + orchestrator.diagnostics());
        Path entryArtifact = outputDir.resolve("hostjs_main.js");
        check(Files.exists(entryArtifact),
            "the entry artifact is written for the host-ABI project");
        if (Files.exists(entryArtifact)) {
            String js = Files.readString(entryArtifact);
            check(js.contains("$rt.loadHost($require(" + q()
                    + "./host/cfg" + q() + "), {"),
                "the emitted binding loads the raw host specifier "
                    + "verbatim with the project-import relative rule: "
                    + js);
            check(js.contains(q() + "@$external/host/cfg/ServerConfig"
                    + q()),
                "the declared class map carries the canonical externals "
                    + "identity: " + js);
        }

        if (nodeAvailable) {
            writeFile("hostjs_proj/build/js/host/cfg.js", """
                "use strict";
                module.exports = {
                  Endpoint: { $kind: "class", $classname: "@$external/host/cfg/Endpoint" },
                  Endpoint_defaults: { path: "/" },
                  ServerConfig: { $kind: "class", $classname: "@$external/host/cfg/ServerConfig" },
                  ServerConfig_defaults: { port: 8080 },
                  describe: function (s) { return s.endpoint.path + ":" + s.port; },
                };
                """);
            ProcessBuilder node = new ProcessBuilder("node",
                "hostjs_main.js");
            node.directory(outputDir.toFile());
            node.redirectErrorStream(true);
            Process np = node.start();
            String nout = new String(np.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8).trim();
            int nrc = np.waitFor();
            check(nrc == 0 && nout.isEmpty(),
                "node hostjs_main.js constructs the declared host class, "
                    + "crosses the boundary, and reads the described "
                    + "value: exit " + nrc + ", output '" + nout + "'");
        } else {
            skipNode("orchestrator host-ABI node run");
        }
    }

    /** The quote character as a string (emission-pin assertions). */
    private static String q() {
        return "\"";
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
                null, roots,
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
                null, roots,
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

    private static void testInt32ProfileSelectorEmission() throws Exception {
        System.out.println("-- Emission pins: $rt.setInt32Mode(true) profile selector --");

        String source = "export function test(): int { return 2147483647 + 1; }";
        Frontend f = compileFrontend(source, "jstest-int32-selector.deal");
        if (f.program() == null) {
            fail("int32 selector frontend failed: " + f.errors());
            return;
        }

        // DEAL_V1_2_INT32: the selector lands immediately after the
        // runtime $require line, exactly once per module.
        JsBackend.JsCodegenResult int32 = JsBackend.generate(f.program(),
            f.checkResult(), "jstest-int32-selector.deal", "Main",
            Map.of(), Map.of(), false, SemanticProfile.DEAL_V1_2_INT32);
        check(!int32.hasErrors(),
            "DEAL_V1_2_INT32 generation clean: " + int32.diagnostics());
        String int32Source = int32.source();
        check(int32Source.contains(
                "const $rt = $require(\"./deal/runtime\");\n"
                    + "$rt.setInt32Mode(true);\n"),
            "DEAL_V1_2_INT32 emits $rt.setInt32Mode(true) immediately after "
                + "the runtime $require");
        check(countOccurrences(int32Source, "$rt.setInt32Mode(true);") == 1,
            "the selector appears exactly once per emitted module");

        // The legacy profile: no selector anywhere in the artifact, and
        // the header is byte-identical to the int32 header minus the
        // selector line (the selector is the only emission delta).
        JsBackend.JsCodegenResult legacy = JsBackend.generate(f.program(),
            f.checkResult(), "jstest-int32-selector.deal", "Main",
            Map.of(), Map.of(), false); // default: LEGACY_SAFE_INT
        check(!legacy.hasErrors(),
            "LEGACY_SAFE_INT generation clean: " + legacy.diagnostics());
        check(!legacy.source().contains("setInt32Mode"),
            "LEGACY_SAFE_INT emits no selector");
        check(legacy.source().equals(int32Source.replace(
                "\n$rt.setInt32Mode(true);", "")),
            "the selector line is the only artifact delta between the "
                + "profiles (retained ±(2^53-1) emission unchanged)");
    }

    private static void testInt32OrchestratorPlumb() throws Exception {
        System.out.println("-- Orchestrator plumb: invocation.semanticProfile() → selector in every module --");

        writeFile("js_int32_proj/deal.json",
            "{\"languageVersion\": \"1.2\", \"backend\": \"js\"}");
        writeFile("js_int32_proj/src/app/lib.deal",
            "export function ping(): string { return \"pong\"; }");
        writeFile("js_int32_proj/src/app/main.deal",
            "import * as lib from \"./lib\"\n"
                + "export function main(): null {\n"
                + "  if (lib.ping() === \"pong\") { return null; }\n"
                + "  return null;\n"
                + "}\n");

        Path entryFile = tmpDir.resolve("js_int32_proj/src/app/main.deal")
            .toAbsolutePath();
        Path outputDir = tmpDir.resolve("js_int32_proj/build/js");
        List<Path> roots = List.of(tmpDir.resolve("js_int32_proj/src")
            .toAbsolutePath());

        // The strict v1.2 schema accepts backend "js" (ISSUE-0169
        // remediation, ISSUE-0471); this plumb still drives the
        // isolated-phase orchestrator path because the profile-selected
        // invocation (V1_2_ACTIVE → DEAL_V1_2_INT32) is test-only.
        StrictManifestParser.StrictManifestParseResult strictJs =
            StrictManifestParser.parse("deal.json",
                "{\"languageVersion\": \"1.2\", \"backend\": \"js\"}");
        check(strictJs.manifest() != null && strictJs.failure() == null,
            "int32 plumb deal.json backend js parses strictly: "
                + strictJs.failure());
        if (strictJs.manifest() != null) {
            check("js".equals(strictJs.manifest().backend()),
                "int32 plumb strict manifest publishes backend js");
        }

        // The release-owned invocation the plumb must carry: PUBLIC_BUILD
        // × V1_2_ACTIVE resolves DEAL_V1_2_INT32.
        CompilerInvocation int32Invocation = CompilerProfileProvider.resolve(
            ReleaseState.V1_2_ACTIVE, CapabilityRegistry.releaseRegistry());
        check(int32Invocation.semanticProfile()
                == SemanticProfile.DEAL_V1_2_INT32,
            "V1_2_ACTIVE invocation resolves DEAL_V1_2_INT32");

        CompilationOrchestrator active = new CompilationOrchestrator(
            entryFile, outputDir, false, false, false, false, Backend.JS,
            (Map<String, String>) null, roots,
            Path.of(".").toAbsolutePath().normalize(), null,
            int32Invocation);
        boolean activeOk = active.compile();
        check(activeOk, "int32-profile JS orchestrator compile succeeds: "
            + active.diagnostics());
        if (activeOk) {
            for (String artifactName : List.of("app/main.js", "app/lib.js")) {
                Path artifact = outputDir.resolve(artifactName);
                check(Files.exists(artifact),
                    "orchestrator wrote " + artifactName);
                if (Files.exists(artifact)) {
                    String js = Files.readString(artifact);
                    check(js.contains("$rt.setInt32Mode(true);"),
                        artifactName + " emits the selector under "
                            + "DEAL_V1_2_INT32");
                    check(countOccurrences(js, "$rt.setInt32Mode(true);") == 1,
                        artifactName + " emits the selector exactly once");
                }
            }
        }

        // The default invocation is now the committed V1_2_ACTIVE public
        // build: it plumbs the int32 mode (the selector appears exactly
        // once per emitted module).
        Path defaultOutputDir = tmpDir.resolve("js_int32_proj/build/js_default");
        CompilationOrchestrator defaultOrchestrator = new CompilationOrchestrator(
            entryFile, defaultOutputDir, false, false, false, Backend.JS,
            (Map<String, String>) null, roots,
            Path.of(".").toAbsolutePath().normalize());
        boolean defaultOk = defaultOrchestrator.compile();
        check(defaultOk, "default JS orchestrator compile succeeds: "
            + defaultOrchestrator.diagnostics());
        check(defaultOrchestrator.invocation().semanticProfile()
                == SemanticProfile.DEAL_V1_2_INT32,
            "the orchestrator default invocation derives DEAL_V1_2_INT32 "
                + "under the committed V1_2_ACTIVE release state");
        if (defaultOk) {
            for (String artifactName : List.of("app/main.js", "app/lib.js")) {
                Path artifact = defaultOutputDir.resolve(artifactName);
                check(Files.exists(artifact),
                    "default orchestrator wrote " + artifactName);
                if (Files.exists(artifact)) {
                    check(Files.readString(artifact).contains("setInt32Mode"),
                        artifactName + " emits the int32 selector under the "
                            + "default post-flip invocation");
                }
            }
        }

        // The explicit PRE_ACTIVATION invocation keeps LEGACY_SAFE_INT
        // (the internal matrix row) and plumbs the legacy mode: no
        // selector in any module.
        Path legacyOutputDir = tmpDir.resolve("js_int32_proj/build/js_legacy");
        CompilationOrchestrator legacy = new CompilationOrchestrator(
            entryFile, legacyOutputDir, false, false, false, false, Backend.JS,
            (Map<String, String>) null, roots,
            Path.of(".").toAbsolutePath().normalize(), null,
            CompilerProfileProvider.resolve(ReleaseState.PRE_ACTIVATION,
                CapabilityRegistry.releaseRegistry()));
        boolean legacyOk = legacy.compile();
        check(legacyOk, "legacy JS orchestrator compile succeeds: "
            + legacy.diagnostics());
        check(legacy.invocation().semanticProfile()
                == SemanticProfile.LEGACY_SAFE_INT,
            "the explicit PRE_ACTIVATION invocation keeps "
                + "PRE_ACTIVATION → LEGACY_SAFE_INT");
        if (legacyOk) {
            for (String artifactName : List.of("app/main.js", "app/lib.js")) {
                Path artifact = legacyOutputDir.resolve(artifactName);
                check(Files.exists(artifact),
                    "legacy orchestrator wrote " + artifactName);
                if (Files.exists(artifact)) {
                    check(!Files.readString(artifact).contains("setInt32Mode"),
                        artifactName + " emits no selector under "
                            + "LEGACY_SAFE_INT");
                }
            }
        }
    }


    private static void testInt32MatrixNode() throws Exception {
        System.out.println("-- Node: signed-int32 matrix under DEAL_V1_2_INT32 --");
        if (!nodeAvailable) { skipNode("signed-int32 matrix"); return; }

        // Boundary acceptance: the inclusive signed-32 extremes pass.
        NodeResult maxOk = runDealNodeProfile(
            "export function test(): int { return 2147483647; }",
            "int32-max-ok", SemanticProfile.DEAL_V1_2_INT32);
        check(maxOk.exitCode() == 0 && maxOk.output().equals("2147483647"),
            "2147483647 is the accepted int32 maximum: " + maxOk.output());

        NodeResult minOk = runDealNodeProfile(
            "export function test(): int { return -2147483648; }",
            "int32-min-ok", SemanticProfile.DEAL_V1_2_INT32);
        check(minOk.exitCode() == 0 && minOk.output().equals("-2147483648"),
            "-2147483648 is the accepted int32 minimum: " + minOk.output());
        // Overflow on every producing site: E8004 with the pinned
        // "int out of safe range" message, exit 1.
        NodeResult addOver = runDealNodeProfile(
            "export function test(): int { return 2147483647 + 1; }",
            "int32-add-over", SemanticProfile.DEAL_V1_2_INT32);
        check(addOver.exitCode() == 1
                && addOver.output().contains("DEAL_ERROR_CODE: E8004")
                && addOver.output().contains("int out of safe range"),
            "2147483647 + 1 → E8004 'int out of safe range', exit 1: "
                + addOver.output());

        NodeResult subUnder = runDealNodeProfile(
            "export function test(): int { return -2147483648 - 1; }",
            "int32-sub-under", SemanticProfile.DEAL_V1_2_INT32);
        check(subUnder.exitCode() == 1
                && subUnder.output().contains("DEAL_ERROR_CODE: E8004")
                && subUnder.output().contains("int out of safe range"),
            "-2147483648 - 1 → E8004 'int out of safe range', exit 1: "
                + subUnder.output());

        NodeResult divOver = runDealNodeProfile(
            "export function test(): int { return -2147483648 / -1; }",
            "int32-div-over", SemanticProfile.DEAL_V1_2_INT32);
        check(divOver.exitCode() == 1
                && divOver.output().contains("DEAL_ERROR_CODE: E8004"),
            "MIN_VALUE / -1 → E8004 (the truncated quotient leaves the "
                + "int32 range), exit 1: " + divOver.output());

        NodeResult modOver = runDealNodeProfile(
            "export function test(): int { return -2147483648 % -1; }",
            "int32-mod-over", SemanticProfile.DEAL_V1_2_INT32);
        check(modOver.exitCode() == 1
                && modOver.output().contains("DEAL_ERROR_CODE: E8004"),
            "MIN_VALUE % -1 → E8004 (the reference's truncating-quotient "
                + "gate), exit 1: " + modOver.output());

        NodeResult negOver = runDealNodeProfile(
            "export function test(): int { return -(-2147483648); }",
            "int32-neg-over", SemanticProfile.DEAL_V1_2_INT32);
        check(negOver.exitCode() == 1
                && negOver.output().contains("DEAL_ERROR_CODE: E8004"),
            "-(-2147483648) → E8004 via checkInt, exit 1: "
                + negOver.output());

        // E8005 first on a zero divisor (intDiv and intMod alike).
        NodeResult divz = runDealNodeProfile(
            "export function test(): int { return 5 / 0; }",
            "int32-div-zero", SemanticProfile.DEAL_V1_2_INT32);
        check(divz.exitCode() == 1
                && divz.output().contains("DEAL_ERROR_CODE: E8005")
                && divz.output().contains("integer division by zero"),
            "zero divisor → E8005, exit 1: " + divz.output());

        NodeResult modz = runDealNodeProfile(
            "export function test(): int { return 5 % 0; }",
            "int32-mod-zero", SemanticProfile.DEAL_V1_2_INT32);
        check(modz.exitCode() == 1
                && modz.output().contains("DEAL_ERROR_CODE: E8005"),
            "mod by zero → E8005, exit 1: " + modz.output());

        NodeResult negexp = runDealNodeProfile(
            "export function test(): int { return 2 ** -1; }",
            "int32-neg-exp", SemanticProfile.DEAL_V1_2_INT32);
        check(negexp.exitCode() == 1
                && negexp.output().contains("DEAL_ERROR_CODE: E8006"),
            "negative exponent → E8006, exit 1: " + negexp.output());

        NodeResult powOver = runDealNodeProfile(
            "export function test(): int { return 2 ** 10000; }",
            "int32-pow-over", SemanticProfile.DEAL_V1_2_INT32);
        check(powOver.exitCode() == 1
                && powOver.output().contains("DEAL_ERROR_CODE: E8001")
                && powOver.output().contains("expected int, got infinity"),
            "2 ** 10000 → E8001 'expected int, got infinity' (the "
                + "±Infinity arm stays before the range arm), exit 1: "
                + powOver.output());

        // Truncating division/remainder signs stay truncation-toward-zero.
        NodeResult trunc = runDealNodeProfile(
            "export function test(): int { let a: int = -7 / 3; "
                + "let b: int = 7 / -3; let c: int = -7 % 3; "
                + "if (a === -2 && b === -2 && c === -1) { return 1; } "
                + "return 0; }",
            "int32-trunc-signs", SemanticProfile.DEAL_V1_2_INT32);
        check(trunc.exitCode() == 0 && trunc.output().equals("1"),
            "-7 / 3 === -2, 7 / -3 === -2, -7 % 3 === -1 (truncation "
                + "toward zero): " + trunc.output());

        // Conversion boundaries: int() of a finite out-of-range value is
        // E8004; NaN/±Infinity/non-integer are E8001.
        NodeResult convRange = runDealNodeProfile(
            "export function test(): int { return int(2147483648.0); }",
            "int32-conv-range", SemanticProfile.DEAL_V1_2_INT32);
        check(convRange.exitCode() == 1
                && convRange.output().contains("DEAL_ERROR_CODE: E8004")
                && convRange.output().contains("int out of safe range"),
            "int(2147483648.0) → E8004, exit 1: " + convRange.output());

        NodeResult convNan = runDealNodeProfile(
            "export function test(): int { return int(0.0 / 0.0); }",
            "int32-conv-nan", SemanticProfile.DEAL_V1_2_INT32);
        check(convNan.exitCode() == 1
                && convNan.output().contains("DEAL_ERROR_CODE: E8001")
                && convNan.output().contains("expected int, got NaN"),
            "int(NaN) → E8001 'expected int, got NaN', exit 1: "
                + convNan.output());

        NodeResult convInf = runDealNodeProfile(
            "export function test(): int { return int(1.0 / 0.0); }",
            "int32-conv-inf", SemanticProfile.DEAL_V1_2_INT32);
        check(convInf.exitCode() == 1
                && convInf.output().contains("DEAL_ERROR_CODE: E8001")
                && convInf.output().contains("expected int, got infinity"),
            "int(Infinity) → E8001 'expected int, got infinity', exit 1: "
                + convInf.output());

        NodeResult convFrac = runDealNodeProfile(
            "export function test(): int { return int(3.5); }",
            "int32-conv-frac", SemanticProfile.DEAL_V1_2_INT32);
        check(convFrac.exitCode() == 1
                && convFrac.output().contains("DEAL_ERROR_CODE: E8001")
                && convFrac.output().contains(
                    "expected int, got non-integer number"),
            "int(3.5) → E8001 non-integer, exit 1: " + convFrac.output());

        // The nil-equivalent conversion arm through the activated runtime
        // (the pinned message; only emitter mis-emission reaches it).
        NodeResult convNull = runRuntimeProbe("int32-conv-null",
            "\"use strict\";\n"
                + "const $rt = require(\"./deal/runtime\");\n"
                + "$rt.setInt32Mode(true);\n"
                + "try {\n"
                + "  $rt.intConvert(null, \"probe.js\", 1, 1);\n"
                + "  console.log(\"FAIL: no throw\");\n"
                + "} catch (e) {\n"
                + "  if (e.$dealCode === \"E8001\" && e.message === \"cannot convert null to int\") {\n"
                + "    console.log(\"E8001-NULL-OK\");\n"
                + "  } else {\n"
                + "    console.log(\"FAIL: \" + e.$dealCode + \" \" + e.message);\n"
                + "  }\n"
                + "}\n");
        check(convNull.exitCode() == 0
                && convNull.output().equals("E8001-NULL-OK"),
            "int(null) → E8001 'cannot convert null to int' under the "
                + "int32 profile: " + convNull.output());

        // -0 normalizes to 0.
        NodeResult negZero = runDealNodeProfile(
            "export function test(): int { let z: int = -0; "
                + "if (z === 0) { return 1; } return 0; }",
            "int32-neg-zero", SemanticProfile.DEAL_V1_2_INT32);
        check(negZero.exitCode() == 0 && negZero.output().equals("1"),
            "-0 normalizes to 0 (-0 === 0): " + negZero.output());

        // The combined gate: the int32 artifacts carry the canonical
        // descriptor and check texts (T1's descriptor service and T3's
        // range gate) — the matrix fails if either breaks.
        Frontend canonical = compileFrontend(
            "export function test(): int { return int(3.5); }",
            "jstest-int32-canonical.deal");
        if (canonical.program() == null) {
            fail("int32 canonical frontend failed: " + canonical.errors());
            return;
        }
        JsBackend.JsCodegenResult canonicalRes = JsBackend.generate(
            canonical.program(), canonical.checkResult(),
            "jstest-int32-canonical.deal", "Main",
            Map.of(), Map.of(), false, SemanticProfile.DEAL_V1_2_INT32);
        check(!canonicalRes.hasErrors(),
            "int32 canonical generation clean: " + canonicalRes.diagnostics());
        check(canonicalRes.source().contains("$rt.setInt32Mode(true);"),
            "the int32 artifact carries the selector (the matrix runs "
                + "through the activated range gate)");
        check(canonicalRes.source().contains("$rt.checkInt("),
            "the int32 artifact carries the checkInt boundary text (T3's "
                + "parameterized range gate)");
        check(canonicalRes.source().contains(
                "$rt.function(\"(number)->int\""),
            "the int32 artifact carries the canonical (number)->int "
                + "wrapper seed (T1's canonical descriptors)");

        // Legacy authority: under LEGACY_SAFE_INT the same sources keep
        // the retained ±(2^53-1) behavior and no selector is emitted.
        NodeResult legacyOver = runDealNodeProfile(
            "export function test(): int { return 2147483647 + 1; }",
            "legacy-add-over", SemanticProfile.LEGACY_SAFE_INT);
        check(legacyOver.exitCode() == 0
                && legacyOver.output().equals("2147483648"),
            "under LEGACY_SAFE_INT 2147483647 + 1 stays inside the "
                + "retained ±(2^53-1) range (2147483648): "
                + legacyOver.output());

        NodeResult legacyMax = runDealNodeProfile(
            "export function test(): int { return 9007199254740991; }",
            "legacy-safe-max", SemanticProfile.LEGACY_SAFE_INT);
        check(legacyMax.exitCode() == 0
                && legacyMax.output().equals("9007199254740991"),
            "the ±(2^53-1) safe-range boundary passes under "
                + "LEGACY_SAFE_INT: " + legacyMax.output());
    }

    private static void testBytesEmissionPins() {
        System.out.println("-- Bytes emission pins: $rt.bytes* sites and canonical bytes descriptors --");

        JsBackend.JsCodegenResult res = generate("""
            function id(b: bytes): bytes { return b; }
            export function test(): int {
              let b: bytes = bytes(4);
              let n: int = b.length;
              let v: int = b[0];
              b[0] = 255;
              let t: table = {};
              let x: bytes = t.b;
              let m: bytes | null = t.m;
              let ys: bytes[] = t.arr;
              let g: (b: bytes) => bytes = t.g;
              let f: (x: int) => bytes = bytes;
              let z: int = f(2)[0];
              let c: bytes = id(b);
              return n + v + z;
            }
            """, "bytesemit");
        check(res != null && !res.hasErrors(), "bytes emission clean: "
            + (res == null ? "<null>" : res.diagnostics()));
        if (res == null || res.hasErrors()) return;

        String js = res.source();
        check(js.contains("$rt.bytes(4, \"jstest-bytesemit.deal\""),
            "bytes(4) lowers to $rt.bytes(4, <file>, <line>, <column>)");
        check(js.contains("$rt.bytesLength(b)"),
            "b.length lowers to $rt.bytesLength(b) (compiler-resolved, "
                + "not a member lookup)");
        check(js.contains("$rt.bytesGet(b, 0, \"jstest-bytesemit.deal\""),
            "b[0] read lowers to $rt.bytesGet(b, 0, <span>)");
        check(js.contains("$rt.bytesSet(b, 0, 255, \"jstest-bytesemit.deal\""),
            "b[0] = 255 lowers to $rt.bytesSet(b, 0, 255, <span>) with "
                + "the receiver, index, and RHS in argument order (the "
                + "pinned evaluation order: receiver/index side effects "
                + "before the RHS, RHS before validation)");
        check(js.contains("$rt.checkBytes("),
            "bytes-typed declaration boundaries emit $rt.checkBytes");
        check(js.contains("$rt.checkNullable(\"bytes\", t.get(\"m\")"),
            "nullable bytes table read checks with the canonical bytes "
                + "inner descriptor (T1's service)");
        check(js.contains("$rt.checkArray(\"[bytes]\", t.get(\"arr\")"),
            "bytes[] table read routes through checkArray with the "
                + "canonical [bytes] descriptor (T1's service)");
        check(js.contains("$rt.checkType(\"(bytes)->bytes\", t.get(\"g\")"),
            "function-typed table read checks with the canonical "
                + "(bytes)->bytes descriptor (T1's service)");
        check(js.contains("const bytes = $rt.function(\"(int)->bytes\""),
            "the header seeds the first-class bytes wrapper with the "
                + "canonical (int)->bytes signature");
        check(js.contains("$rt.checkType(\"(int)->bytes\", bytes, "),
            "a first-class bytes reference crosses the canonical "
                + "(int)->bytes boundary check");
        check(!js.contains("bytes is not supported"),
            "no defensive bytes E6000 text remains in the artifact");
    }

    private static void testBytesNodeSemantics() throws Exception {
        System.out.println("-- Node: bytes semantics over the real pipeline --");
        if (!nodeAvailable) { skipNode("bytes semantics"); return; }

        // The exact bytes-buffer-ops.deal program body (zero-fill,
        // unsigned 0..255 roundtrip, immutable length, reference-copy
        // aliasing) through frontend → JsBackend → node.
        NodeResult ops = runDealNode("""
            export function main(): null {
              let b: bytes = bytes(4);
              if (b.length !== 4) {
                throw { code: "TEST_FAIL", message: "bytes: initial length mismatch" };
              }
              if (b[0] !== 0) {
                throw { code: "TEST_FAIL", message: "bytes: zero-fill mismatch" };
              }
              b[0] = 255;
              b[1] = 128;
              let hi: int = b[0];
              let lo: int = b[1];
              if (hi !== 255 || lo !== 128) {
                throw { code: "TEST_FAIL", message: "bytes: byte value roundtrip mismatch" };
              }
              let alias: bytes = b;
              alias[2] = 7;
              if (b[2] !== 7) {
                throw { code: "TEST_FAIL", message: "bytes: reference copy mismatch" };
              }
              return null;
            }
            """, "bytes-buffer-ops");
        check(ops.exitCode() == 0,
            "bytes-buffer-ops body runs under node (real $Uint8Array "
                + "storage, zero-fill, 0..255 roundtrip, length, aliasing), "
                + "exit 0: " + ops.output());

        // Empty buffer: bytes(0) has length 0 and its first read is E8012.
        NodeResult empty = runDealNode(
            "export function test(): int { let b: bytes = bytes(0); "
                + "if (b.length !== 0) { return 0; } "
                + "let v: int = b[0]; return v; }",
            "bytes-empty-oob");
        check(empty.exitCode() == 1
                && empty.output().contains("DEAL_ERROR_CODE: E8012")
                && empty.output().contains("bytes index out of bounds"),
            "bytes(0) has length 0 and b[0] reads raise E8012 "
                + "'bytes index out of bounds': " + empty.output());

        // Bounds: negative index and index === length on reads and
        // writes (bytes never append).
        NodeResult negRead = runDealNode(
            "export function test(): int { let b: bytes = bytes(2); "
                + "return b[-1]; }",
            "bytes-neg-read");
        check(negRead.exitCode() == 1
                && negRead.output().contains("DEAL_ERROR_CODE: E8012"),
            "negative byte read raises E8012: " + negRead.output());

        NodeResult endRead = runDealNode(
            "export function test(): int { let b: bytes = bytes(2); "
                + "return b[2]; }",
            "bytes-end-read");
        check(endRead.exitCode() == 1
                && endRead.output().contains("DEAL_ERROR_CODE: E8012"),
            "read at index === length raises E8012 (bytes never "
                + "append): " + endRead.output());

        NodeResult negWrite = runDealNode(
            "export function test(): int { let b: bytes = bytes(2); "
                + "b[-1] = 1; return 0; }",
            "bytes-neg-write");
        check(negWrite.exitCode() == 1
                && negWrite.output().contains("DEAL_ERROR_CODE: E8012"),
            "negative byte write raises E8012: " + negWrite.output());

        NodeResult endWrite = runDealNode(
            "export function test(): int { let b: bytes = bytes(2); "
                + "b[2] = 7; return 0; }",
            "bytes-end-write");
        check(endWrite.exitCode() == 1
                && endWrite.output().contains("DEAL_ERROR_CODE: E8012"),
            "write at index === length raises E8012 (bytes never "
                + "append): " + endWrite.output());

        // Value range: writes outside 0..255 raise E8013 and change no
        // storage — the RHS (with its side effect) completes before the
        // write validation, pinned through the try/catch log.
        NodeResult val256 = runDealNode(
            "export function test(): int { let b: bytes = bytes(2); "
                + "b[1] = 256; return 0; }",
            "bytes-e8013");
        check(val256.exitCode() == 1
                && val256.output().contains("DEAL_ERROR_CODE: E8013")
                && val256.output().contains("bytes value out of range"),
            "writing 256 raises E8013 'bytes value out of range': "
                + val256.output());

        // The pinned write-side order, observed through a caught E8013:
        // the RHS (with its side effect) completes before write
        // validation, and a failed write changes no storage.
        NodeResult val256Caught = runDealNode("""
            function tooBig(t: table): int {
              let log: string = t.log;
              log = log + "v";
              t.log = log;
              return 256;
            }
            export function test(): int {
              let t: table = {};
              t.log = "";
              let b: bytes = bytes(2);
              try {
                b[1] = tooBig(t);
              } catch (e) {
                let log: string = t.log;
                if (log !== "v") {
                  throw { code: "TEST_FAIL", message: "RHS side effect missing: " + log };
                }
                if (b[1] !== 0) {
                  throw { code: "TEST_FAIL", message: "failed write changed storage" };
                }
                return 1;
              }
              return 0;
            }
            """, "bytes-e8013-caught");
        check(val256Caught.exitCode() == 0
                && val256Caught.output().equals("1"),
            "a caught E8013 write proves the RHS side effect ran before "
                + "write validation and the failed write changed no "
                + "storage (returns 1): " + val256Caught.output());

        NodeResult valNeg = runDealNode(
            "export function test(): int { let b: bytes = bytes(2); "
                + "b[0] = -1; return 0; }",
            "bytes-neg-value");
        check(valNeg.exitCode() == 1
                && valNeg.output().contains("DEAL_ERROR_CODE: E8013"),
            "writing -1 raises E8013: " + valNeg.output());

        // Evaluation order: the receiver, the index, and the RHS side
        // effects complete in order before validation — a valid write
        // logs "riv".
        NodeResult order = runDealNode("""
            function record(t: table, tag: string): int {
              let log: string = t.log;
              log = log + tag;
              t.log = log;
              return 1;
            }
            function getb(t: table): bytes {
              let log: string = t.log;
              log = log + "r";
              t.log = log;
              return bytes(3);
            }
            export function test(): null {
              let t: table = {};
              t.log = "";
              getb(t)[record(t, "i")] = record(t, "v");
              let order: string = t.log;
              if (order !== "riv") {
                throw { code: "TEST_FAIL", message: "evaluation order: " + order };
              }
              return null;
            }
            """, "bytes-eval-order");
        check(order.exitCode() == 0,
            "byte write evaluates receiver, index, then RHS in order "
                + "(log 'riv'), exit 0: " + order.output());

        // Allocation length above the signed-int32 logical-length bound
        // raises E8012 (under LEGACY_SAFE_INT the value itself is
        // representable, so the length gate is the failing arm).
        NodeResult tooLong = runDealNode(
            "export function test(): int { let b: bytes = bytes(2147483648); "
                + "return 0; }",
            "bytes-too-long");
        check(tooLong.exitCode() == 1
                && tooLong.output().contains("DEAL_ERROR_CODE: E8012")
                && tooLong.output().contains("bytes length out of bounds"),
            "bytes(2147483648) raises E8012 (the signed-int32 "
                + "logical-length bound): " + tooLong.output());

        NodeResult negLen = runDealNode(
            "export function test(): int { let b: bytes = bytes(-1); "
                + "return 0; }",
            "bytes-neg-length");
        check(negLen.exitCode() == 1
                && negLen.output().contains("DEAL_ERROR_CODE: E8012")
                && negLen.output().contains("bytes length must be non-negative"),
            "bytes(-1) raises E8012 'bytes length must be non-negative': "
                + negLen.output());

        // checkType("bytes") acceptance and rejection through the table
        // boundary (the matcher table's bytes row).
        NodeResult accept = runDealNode(
            "export function test(): int { let t: table = {}; "
                + "t.b = bytes(1); let x: bytes = t.b; return 1; }",
            "bytes-checktype-ok");
        check(accept.exitCode() == 0 && accept.output().equals("1"),
            "checkType(\"bytes\") accepts the $Uint8Array carrier: "
                + accept.output());

        NodeResult reject = runDealNode(
            "export function test(): int { let t: table = {}; "
                + "t.bad = 42; let x: bytes = t.bad; return 0; }",
            "bytes-checktype-reject");
        check(reject.exitCode() == 1
                && reject.output().contains("DEAL_ERROR_CODE: E8001")
                && reject.output().contains("expected bytes"),
            "checkType(\"bytes\") rejects a non-bytes value with E8001 "
                + "'expected bytes': " + reject.output());

        // Reference identity across function boundaries (sync and async)
        // and bytes values inside arrays (the recursive closure smoke).
        NodeResult identity = runDealNode("""
            function id(b: bytes): bytes { return b; }
            async function dup(b: bytes): bytes { return b; }
            export async function test(): int {
              let b: bytes = bytes(2);
              let sync: bytes = id(b);
              sync[0] = 9;
              if (b[0] !== 9) { return 0; }
              let c: bytes = await dup(b);
              c[1] = 8;
              if (b[1] !== 8) { return 0; }
              let xs: bytes[] = [bytes(1), bytes(2)];
              xs[0][0] = 7;
              if (xs[0][0] !== 7 || xs[1][0] !== 0) { return 0; }
              return 1;
            }
            """, "bytes-identity-closure");
        check(identity.exitCode() == 0 && identity.output().equals("1"),
            "reference identity across sync/async boundaries and "
                + "mutation through [bytes] arrays: " + identity.output());

        // JSON rejection: a bytes value reaching std/json.stringify via
        // a table raises the pinned unsupported-type E8001.
        NodeResult jsonReject = runDealNode("""
            import * as json from "std/json"
            export function test(): string {
              let t: table = { b: bytes(2) };
              return json.stringify(t);
            }
            """, "bytes-json-reject");
        check(jsonReject.exitCode() == 1
                && jsonReject.output().contains("DEAL_ERROR_CODE: E8001")
                && jsonReject.output().contains(
                    "unsupported type for JSON encoding: bytes"),
            "a bytes value through std/json.stringify raises E8001 "
                + "'unsupported type for JSON encoding: bytes': "
                + jsonReject.output());
    }

    /**
     * The class-symbol-first bytes guard (js-v12-int32-bytes D3/D4,
     * spec-v1.2.md §Name resolution): `bytes` is not a DEAL keyword and
     * module-level declarations resolve at step 3 (imports at step 4)
     * before the compiler intrinsics at step 5, so a module-level user
     * class/function named `bytes` must stay legal and shadow the
     * intrinsic — with no redeclaration diagnostic (E2002), no header
     * `const bytes` seed collision, and working artifacts under node.
     */
    private static void testBytesUserNameShadowing() throws Exception {
        System.out.println("-- Bytes: user class/function named bytes shadows the intrinsic --");

        // (a) A module-level user CLASS named bytes: checker-accepted,
        // no E2002, the annotation resolves to the user ClassSymbol, and
        // the artifact omits the intrinsic header seed (the class binds
        // only bytes$new/bytes$meta).
        JsBackend.JsCodegenResult clsRes = generate("""
            class bytes { x: int }
            export function test(): int {
              let b: bytes = { x: 2 };
              return b.x;
            }
            """, "bytes-user-class");
        check(clsRes != null && !clsRes.hasErrors(),
            "module-level user class named bytes compiles clean: "
                + (clsRes == null ? "<null>" : clsRes.diagnostics()));
        if (clsRes != null && !clsRes.hasErrors()) {
            String js = clsRes.source();
            check(!js.contains("const bytes = $rt.function(\"(int)->bytes\""),
                "no intrinsic header bytes seed when a user class named "
                    + "bytes occupies the module binding");
            check(js.contains("let bytes$new; let bytes$meta;")
                    && js.contains("bytes$new = (provided, $file, $line, $column) =>"),
                "the user class named bytes emits its bytes$new/bytes$meta "
                    + "artifact pair");
            check(js.contains("$rt.makeClass(\"bytes\""),
                "class construction routes through $rt.makeClass with "
                    + "the user class identity");
        }

        // (b) A module-level user FUNCTION named bytes: checker-accepted,
        // no E2002, calls resolve to the user function, and the
        // artifact's predeclared `let bytes;` assignment replaces the
        // skipped intrinsic header seed.
        JsBackend.JsCodegenResult fnRes = generate("""
            function bytes(x: int): int { return x + 1; }
            export function test(): int { return bytes(3); }
            """, "bytes-user-function");
        check(fnRes != null && !fnRes.hasErrors(),
            "module-level user function named bytes compiles clean: "
                + (fnRes == null ? "<null>" : fnRes.diagnostics()));
        if (fnRes != null && !fnRes.hasErrors()) {
            String js = fnRes.source();
            check(!js.contains("const bytes = $rt.function(\"(int)->bytes\""),
                "no intrinsic header bytes seed when a user function "
                    + "named bytes occupies the module binding");
            check(js.contains("let bytes;") && js.contains("bytes = $rt.function("),
                "the user function named bytes keeps its predeclare-then-"
                    + "assign binding");
            check(!js.contains("$rt.bytes(3"),
                "bytes(3) calls the user function (the $rt.bytes call "
                    + "form never fires for the shadowed binding)");
        }

        if (!nodeAvailable) { skipNode("bytes user-name shadowing"); return; }

        NodeResult clsRun = runDealNode("""
            class bytes { x: int }
            export function test(): int {
              let b: bytes = { x: 2 };
              return b.x;
            }
            """, "bytes-user-class-run");
        check(clsRun.exitCode() == 0 && clsRun.output().equals("2"),
            "module-level user class named bytes runs under node (b.x "
                + "=== 2): " + clsRun.output());

        NodeResult fnRun = runDealNode("""
            function bytes(x: int): int { return x + 1; }
            export function test(): int { return bytes(3); }
            """, "bytes-user-function-run");
        check(fnRun.exitCode() == 0 && fnRun.output().equals("4"),
            "module-level user function named bytes runs under node "
                + "(bytes(3) === 4): " + fnRun.output());

        // (c) The review's finding (b) repro end-to-end: a NESTED user
        // class named bytes. The annotation resolves to the nested
        // ClassSymbol (no E3001), the canonical scope-local
        // bytes$new/bytes$meta pair constructs it with its defaults,
        // and the artifact runs under node.
        NodeResult nestedRun = runDealNode("""
            export function test(): int {
              let out: int = 0;
              {
                class bytes { x: int = 0; }
                let b: bytes = { x: 2 };
                out = b.x;
              }
              return out;
            }
            """, "bytes-user-class-nested-run");
        check(nestedRun.exitCode() == 0 && nestedRun.output().equals("2"),
            "nested user class named bytes runs under node (b.x === 2): "
                + nestedRun.output());
    }

    // =========================================================================
    // ISSUE-0323 — JS recursive bytes closure verification (js-v12-int32-bytes D5)
    // =========================================================================

    /**
     * The closure surface of the first-class sync bytes value: a
     * {@code ((bytes)->bytes)[]} array built from a real
     * bytes-transforming function, stored, invoked through the array
     * index, element-reassigned, and content-asserted through buffer
     * mutation — plus the arity-extension adapter over a bytes
     * signature, a nested function declaration, and the E8013/E8012/
     * E8001 failure propagation through the closure chain (the "no
     * depth limit, no E6000" surface — every legal position executes
     * real generated code).
     */
    private static void testBytesClosureFunctionArrayNode() throws Exception {
        System.out.println("-- Node: bytes closure ((bytes)->bytes)[] and arity adapters --");
        if (!nodeAvailable) { skipNode("bytes closure function array"); return; }

        NodeResult run = runDealNode("""
            function step(b: bytes): bytes {
              b[0] = b[0] + 1;
              return b;
            }
            export function test(): int {
              function localStep(b: bytes): bytes {
                b[0] = b[0] + 2;
                return b;
              }
              let b: bytes = bytes(2);
              let f: (b: bytes) => bytes = step;
              let fs: ((b: bytes) => bytes)[] = [step, f, localStep];
              if (fs.length !== 3) { return 0; }
              fs[0](b);
              let out: bytes = fs[1](b);
              if (b[0] !== 2 || out[0] !== 2) { return 0; }
              let alias: bytes = b;
              alias[1] = 5;
              if (b[1] !== 5) { return 0; }
              fs[2](b);
              if (b[0] !== 4) { return 0; }
              fs[1] = step;
              fs[1](b);
              if (b[0] !== 5) { return 0; }
              let wide: (b: bytes, extra: int) => bytes = step;
              wide(b, 41);
              if (b[0] !== 6) { return 0; }
              return 1;
            }
            """, "bytes-closure-fn-array");
        check(run.exitCode() == 0 && run.output().equals("1"),
            "((bytes)->bytes)[] value built from real bytes-transforming "
                + "functions (module-level and nested declarations), stored, "
                + "invoked through the index, element-reassigned, aliased, "
                + "and content-asserted through mutation — plus the "
                + "(bytes,int)->bytes arity adapter dropping the extra "
                + "parameter (returns 1): " + run.output());

        // The E8013/E8012 propagation through the closure chain: the
        // failure raised inside a function-array member crosses the
        // wrapper, the array index, and the caller unchanged.
        NodeResult poison = runDealNode("""
            function poison(b: bytes): bytes {
              b[0] = 256;
              return b;
            }
            export function test(): int {
              let fs: ((b: bytes) => bytes)[] = [poison];
              let b: bytes = bytes(1);
              fs[0](b);
              return 0;
            }
            """, "bytes-closure-fn-array-poison");
        check(poison.exitCode() == 1
                && poison.output().contains("DEAL_ERROR_CODE: E8013"),
            "an E8013 write through the ((bytes)->bytes)[] closure chain "
                + "propagates the pinned bytesSet failure unchanged: "
                + poison.output());

        NodeResult oob = runDealNode("""
            function outOfBounds(b: bytes): bytes {
              let v: int = b[9];
              return b;
            }
            export function test(): int {
              let fs: ((b: bytes) => bytes)[] = [outOfBounds];
              let b: bytes = bytes(1);
              fs[0](b);
              return 0;
            }
            """, "bytes-closure-fn-array-oob");
        check(oob.exitCode() == 1
                && oob.output().contains("DEAL_ERROR_CODE: E8012"),
            "an E8012 read through the ((bytes)->bytes)[] closure chain "
                + "propagates the pinned bytesGet failure unchanged: "
                + oob.output());

        // The adapter's extended-parameter checkInt boundary is real
        // (the checkInt gate, T3): a dynamic non-int value surfacing
        // from a table read into the extra parameter raises E8001 at
        // the adapter entry.
        NodeResult adapterCheck = runDealNode("""
            function step(b: bytes): bytes {
              b[0] = b[0] + 1;
              return b;
            }
            export function test(): int {
              let t: table = {};
              t.extra = 3.5;
              let b: bytes = bytes(1);
              let wide: (b: bytes, extra: int) => bytes = step;
              wide(b, t.extra);
              return 0;
            }
            """, "bytes-closure-adapter-checkint");
        check(adapterCheck.exitCode() == 1
                && adapterCheck.output().contains("DEAL_ERROR_CODE: E8001")
                && adapterCheck.output().contains("expected int"),
            "the arity adapter's extended-parameter checkInt boundary "
                + "raises E8001 'expected int' for a dynamic non-int "
                + "value: " + adapterCheck.output());
    }

    /**
     * Containerized async bytes function values awaited end-to-end: the
     * async wrapper carries the canonical {@code async(bytes)->bytes}
     * signature byte-for-byte across an array container, a class-field
     * container, and a dynamic table boundary, and every awaited
     * completion mutates the same buffer (content asserted, never a
     * shape-only check).
     */
    private static void testBytesClosureAsyncContainerNode() throws Exception {
        System.out.println("-- Node: containerized async bytes function values awaited end-to-end --");
        if (!nodeAvailable) { skipNode("bytes closure async containers"); return; }

        NodeResult run = runDealNode("""
            async function bump(b: bytes): bytes {
              b[0] = b[0] + 1;
              return b;
            }
            class Box { f: async (b: bytes) => bytes; }
            export async function test(): int {
              let b: bytes = bytes(1);
              let afs: (async (b: bytes) => bytes)[] = [bump, bump];
              let out: bytes = await afs[1](b);
              if (out[0] !== 1 || b[0] !== 1) { return 0; }
              let box: Box = { f: bump };
              let out2: bytes = await box.f(b);
              if (out2[0] !== 2 || b[0] !== 2) { return 0; }
              let t: table = {};
              t.f = bump;
              let g: async (b: bytes) => bytes = t.f;
              let out3: bytes = await g(b);
              if (out3[0] !== 3 || b[0] !== 3) { return 0; }
              return 1;
            }
            """, "bytes-closure-async-container");
        check(run.exitCode() == 0 && run.output().equals("1"),
            "containerized async bytes function values — an "
                + "[async(bytes)->bytes] array element, a class-field "
                + "container, and a dynamic table boundary — awaited "
                + "end-to-end with content-asserted buffer mutation "
                + "(returns 1): " + run.output());
    }

    /**
     * Class-field bytes defaults: a bytes-typed default expression
     * evaluates fresh per construction (zero-filled, never shared),
     * a provided literal overrides it, and the compiler-resolved
     * .length/reads/writes hold on the field positions.
     */
    private static void testBytesClosureClassDefaultsNode() throws Exception {
        System.out.println("-- Node: bytes class fields and per-construction defaults --");
        if (!nodeAvailable) { skipNode("bytes closure class defaults"); return; }

        NodeResult run = runDealNode("""
            class Buffer { buf: bytes = bytes(2); }
            export function test(): int {
              let h: Buffer = {};
              if (h.buf.length !== 2) { return 0; }
              h.buf[0] = 6;
              if (h.buf[0] !== 6) { return 0; }
              let h2: Buffer = {};
              if (h2.buf[0] !== 0 || h2.buf.length !== 2) { return 0; }
              h.buf[0] = 8;
              if (h2.buf[0] !== 0) { return 0; }
              let h3: Buffer = { buf: bytes(1) };
              if (h3.buf.length !== 1 || h3.buf[0] !== 0) { return 0; }
              return 1;
            }
            """, "bytes-closure-class-defaults");
        check(run.exitCode() == 0 && run.output().equals("1"),
            "bytes class fields: fresh zero-filled per-construction "
                + "defaults (no aliasing between instances), provided-"
                + "literal override, and compiler-resolved .length/"
                + "read/write positions (returns 1): " + run.output());
    }

    /**
     * Arbitrary-depth Array/Nullable compositions roundtrip through
     * dynamic and function boundaries: the {@code [?[bytes]]} element
     * shape (nullable array of bytes inside an array — depth 2) and the
     * top-level {@code ?[[bytes]]} shape (depth 3) cross table set/get
     * and function parameter/return boundaries with the canonical
     * descriptor text byte-exact, the depth walk validates the real
     * buffers (positive case) and rejects a wrong inner shape at the
     * pinned depth (negative case), and the mutated buffer stays
     * observable through the parallel non-null view. The nullable
     * shapes are flow-only in checker-accepted source: the
     * {@code === null} test that would unpack them is checker-admitted
     * since the ISSUE-0158 E3019 gate lift (bytes identity equality —
     * binary-comparison-selectors B-D7 assigned the lift to
     * ISSUE-0111/ISSUE-0158), never a backend rejection.
     */
    private static void testBytesClosureDeepCompositionsNode() throws Exception {
        System.out.println("-- Node: arbitrary-depth Array/Nullable bytes compositions through boundaries --");
        if (!nodeAvailable) { skipNode("bytes closure deep compositions"); return; }

        NodeResult run = runDealNode("""
            function passDeep(x: (bytes[] | null)[]): (bytes[] | null)[] {
              return x;
            }
            function idTop(x: (bytes[])[] | null): (bytes[])[] | null {
              return x;
            }
            export function test(): int {
              let b0: bytes = bytes(2);
              let inner: bytes[] = [b0];
              let xs: (bytes[] | null)[] = [];
              xs[0] = inner;
              xs[1] = null;
              let deep: (bytes[])[] | null = [inner];
              let t: table = {};
              t.xs = xs;
              t.deep = deep;
              let back: (bytes[] | null)[] = t.xs;
              let again: (bytes[] | null)[] = passDeep(back);
              let backTop: (bytes[])[] | null = t.deep;
              let againTop: (bytes[])[] | null = idTop(backTop);
              if (again.length !== 2) { return 0; }
              inner[0][0] = 9;
              if (b0[0] !== 9) { return 0; }
              return 1;
            }
            """, "bytes-closure-deep-composition");
        check(run.exitCode() == 0 && run.output().equals("1"),
            "(bytes[] | null)[] and (bytes[])[] | null compositions "
                + "roundtrip through table and function boundaries at "
                + "depth with the canonical [?[bytes]]/[[bytes]] walks, "
                + "and the mutated buffer stays observable through the "
                + "parallel non-null view (returns 1): " + run.output());

        // The depth walk is real: a wrong inner shape surfacing from a
        // table read fails at the pinned depth with the E8003-wrapped
        // inner mismatch naming the failing descriptor.
        NodeResult badDepth2 = runDealNode("""
            export function test(): int {
              let t: table = {};
              t.bad = [[7]];
              let back: (bytes[] | null)[] = t.bad;
              return 0;
            }
            """, "bytes-closure-deep-negative-d2");
        check(badDepth2.exitCode() == 1
                && badDepth2.output().contains("DEAL_ERROR_CODE: E8003")
                && badDepth2.output().contains("expected bytes"),
            "a wrong inner shape at depth 2 raises the E8003-wrapped "
                + "element mismatch naming 'expected bytes' (the walker "
                + "reached the bytes row): " + badDepth2.output());

        NodeResult badDepth3 = runDealNode("""
            export function test(): int {
              let t: table = {};
              t.bad = [42];
              let back: (bytes[])[] | null = t.bad;
              return 0;
            }
            """, "bytes-closure-deep-negative-d3");
        check(badDepth3.exitCode() == 1
                && badDepth3.output().contains("DEAL_ERROR_CODE: E8003")
                && badDepth3.output().contains("expected array"),
            "a wrong inner shape at depth 3 raises the E8003-wrapped "
                + "element mismatch naming 'expected array' (the walker "
                + "reached the second array level): " + badDepth3.output());
    }

    /**
     * The module boundary: an {@code async(bytes)->bytes} export
     * crosses the import/export surface with the canonical signature
     * byte-exact in the artifact, and the real node chain awaits the
     * imported wrapper whose entry parameter check and declared-return
     * check validate the bytes carrier — plus the sync cross-module
     * roundtrip.
     */
    private static void testBytesClosureModuleBoundary() throws Exception {
        System.out.println("-- Orchestrator: async(bytes)->bytes across a module boundary --");

        writeFile("bytesx_proj/deal.json",
            "{\"languageVersion\": \"1.2\", \"backend\": \"js\"}");
        writeFile("bytesx_proj/src/bytes_closure_lib.deal", """
            export function syncId(b: bytes): bytes {
              return b;
            }
            export async function tx(b: bytes): bytes {
              b[0] = b[0] + 5;
              return b;
            }
            """);
        writeFile("bytesx_proj/src/bytes_closure_main.deal", """
            import * as lib from "./bytes_closure_lib"
            export function main(): null { return null; }
            export function syncBridge(): int {
              let b: bytes = bytes(2);
              b[1] = 3;
              let out: bytes = lib.syncId(b);
              if (out[0] !== 0 || out[1] !== 3 || b[1] !== 3) {
                throw { code: "TEST_FAIL", message: "sync cross-module bytes mismatch" };
              }
              return 1;
            }
            export async function asyncBridge(): int {
              let b: bytes = bytes(2);
              let out: bytes = await lib.tx(b);
              if (out[0] !== 5 || b[0] !== 5) {
                throw { code: "TEST_FAIL", message: "async cross-module bytes mismatch" };
              }
              return 1;
            }
            """);

        Path entryFile = tmpDir.resolve("bytesx_proj/src/bytes_closure_main.deal").toAbsolutePath();
        Path outputDir = tmpDir.resolve("bytesx_proj/build/js");
        List<Path> roots = List.of(tmpDir.resolve("bytesx_proj/src").toAbsolutePath());

        // Test-only isolated-phase orchestrator path (the production
        // manifest/CLI path is pinned separately by the
        // testCli*/testDealJson acceptance tests).
        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            entryFile, outputDir, false, false, false, Backend.JS,
            (Map<String, String>) null, roots,
            Path.of(".").toAbsolutePath().normalize());
        boolean success = orchestrator.compile();
        check(success, "bytes closure cross-module project compiles: "
            + orchestrator.diagnostics());
        if (!success) return;

        Path libArtifact = outputDir.resolve("bytes_closure_lib.js");
        Path mainArtifact = outputDir.resolve("bytes_closure_main.js");
        check(Files.exists(libArtifact), "orchestrator wrote bytes_closure_lib.js");
        check(Files.exists(mainArtifact), "orchestrator wrote bytes_closure_main.js");
        if (Files.exists(libArtifact)) {
            String js = Files.readString(libArtifact);
            check(js.contains("$rt.function(\"async(bytes)->bytes\", "
                    + "async function tx$f("),
                "the exported async wrapper carries the canonical "
                    + "async(bytes)->bytes signature byte-for-byte");
            check(js.contains("$rt.function(\"(bytes)->bytes\", "
                    + "function syncId$f("),
                "the exported sync wrapper carries the canonical "
                    + "(bytes)->bytes signature byte-for-byte");
            check(js.contains("$rt.checkBytes(b, "),
                "the imported-wrapper entry parameter check validates "
                    + "the bytes carrier at the module boundary");
            check(js.contains("$rt.setProp($exports, \"tx\", tx)")
                    && js.contains("$rt.setProp($exports, \"syncId\", syncId)"),
                "both bytes-bearing functions export under their raw keys");
        }
        if (Files.exists(mainArtifact)) {
            String js = Files.readString(mainArtifact);
            check(js.contains("(await lib.tx.$f("),
                "the importing module awaits the imported async wrapper "
                    + "through lib.tx.$f");
            check(js.contains("lib.syncId.$f("),
                "the importing module calls the imported sync wrapper "
                    + "through lib.syncId.$f");
        }

        if (nodeAvailable) {
            // The driver exercises the production module surface: it
            // requires the compiled entry and runs the two exported
            // bridges (sync and async) through their wrappers — the
            // async bridge awaits the imported async(bytes)->bytes
            // export across the real module boundary.
            Files.writeString(outputDir.resolve("bytes_closure_driver.js"), """
                "use strict";
                const $main = require("./bytes_closure_main");
                (async () => {
                  const $sync = $main.syncBridge.$f();
                  if ($sync !== 1) { throw new Error("syncBridge returned " + $sync); }
                  const $async = await $main.asyncBridge.$f();
                  if ($async !== 1) { throw new Error("asyncBridge returned " + $async); }
                  console.log("bytes-module-boundary-ok");
                })().catch((e) => { console.error(e && e.stack || String(e)); process.exit(1); });
                """);
            ProcessBuilder node = new ProcessBuilder("node", "bytes_closure_driver.js");
            node.directory(outputDir.toFile());
            node.redirectErrorStream(true);
            Process np = node.start();
            String nout = new String(np.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8).trim();
            int nrc = np.waitFor();
            check(nrc == 0 && nout.equals("bytes-module-boundary-ok"),
                "the driver awaits the imported async(bytes)->bytes export "
                    + "across the real module boundary with content-"
                    + "asserted buffer mutation: exit " + nrc
                    + ", output '" + nout + "'");
        } else {
            skipNode("bytes closure module-boundary node run");
        }
    }

    /**
     * The no-E6000 pins: every closure program generates with zero
     * diagnostics and no E6000 for bytes nesting or function shape
     * alone, the canonical descriptor texts appear byte-exact at the
     * emitted boundary sites, and bytes identity equality is
     * checker-admitted since the ISSUE-0158 E3019 gate lift
     * (binary-comparison-selectors B-D7 — the frontend admission rule
     * with native identity comparison emission), never a backend
     * rejection.
     */
    private static void testBytesClosureNoE6000Pins() throws Exception {
        System.out.println("-- Bytes closure: no-E6000 generation pins and canonical descriptor texts --");

        String[] closurePrograms = {
            // The ((bytes)->bytes)[] + arity adapter shape.
            """
            function step(b: bytes): bytes { b[0] = b[0] + 1; return b; }
            export function test(): int {
              let fs: ((b: bytes) => bytes)[] = [step];
              let wide: (b: bytes, extra: int) => bytes = step;
              let b: bytes = bytes(1);
              fs[0](b);
              wide(b, 1);
              return b[0];
            }
            """,
            // The containerized async bytes function value shape.
            """
            async function bump(b: bytes): bytes { b[0] = b[0] + 1; return b; }
            class Box { f: async (b: bytes) => bytes; }
            export async function test(): int {
              let afs: (async (b: bytes) => bytes)[] = [bump];
              let box: Box = { f: bump };
              let b: bytes = bytes(1);
              let x: bytes = await afs[0](b);
              let y: bytes = await box.f(b);
              return x[0] + y[0];
            }
            """,
            // The arbitrary-depth Array/Nullable composition shape.
            """
            function passDeep(x: (bytes[] | null)[]): (bytes[] | null)[] {
              return x;
            }
            export function test(): int {
              let xs: (bytes[] | null)[] = [];
              let deep: (bytes[])[] | null = [[bytes(1)]];
              let t: table = {};
              t.xs = xs;
              t.deep = deep;
              let back: (bytes[] | null)[] = t.xs;
              let again: (bytes[] | null)[] = passDeep(back);
              let backTop: (bytes[])[] | null = t.deep;
              return again.length;
            }
            """,
        };
        String[] names = {
            "closure-pin-fn-array",
            "closure-pin-async-container",
            "closure-pin-deep-composition",
        };
        for (int i = 0; i < closurePrograms.length; i++) {
            JsBackend.JsCodegenResult res = generate(closurePrograms[i],
                names[i]);
            check(res != null && !res.hasErrors(),
                "closure program '" + names[i] + "' generates with zero "
                    + "diagnostics: " + (res == null ? "<null>"
                        : res.diagnostics()));
            if (res == null || res.hasErrors()) {
                continue;
            }
            check(!res.diagnostics().contains("E6000")
                    && !res.source().contains("is not supported"),
                "closure program '" + names[i] + "' emits no E6000 text "
                    + "for bytes nesting or function shape alone");
        }

        // Canonical descriptor texts byte-exact at the emitted boundary
        // sites (T1's descriptor service realized through the runtime
        // matcher).
        JsBackend.JsCodegenResult fnArray = generate(
            closurePrograms[0], names[0]);
        if (fnArray != null && !fnArray.hasErrors()) {
            String js = fnArray.source();
            check(js.contains("$rt.checkArray(\"[(bytes)->bytes]\", "),
                "the ((bytes)->bytes)[] declaration boundary emits the "
                    + "canonical [(bytes)->bytes] descriptor byte-exact");
            check(js.contains("$rt.function(\"(bytes,int)->bytes\", "),
                "the arity adapter carries the canonical "
                    + "(bytes,int)->bytes target descriptor byte-exact");
        }
        JsBackend.JsCodegenResult asyncContainer = generate(
            closurePrograms[1], names[1]);
        if (asyncContainer != null && !asyncContainer.hasErrors()) {
            String js = asyncContainer.source();
            check(js.contains("$rt.checkArray(\"[async(bytes)->bytes]\", "),
                "the async function-value array boundary emits the "
                    + "canonical [async(bytes)->bytes] descriptor byte-exact");
            check(js.contains("$rt.function(\"async(bytes)->bytes\", "
                    + "async function bump$f("),
                "the async wrapper carries the canonical "
                    + "async(bytes)->bytes signature byte-for-byte");
        }
        JsBackend.JsCodegenResult deep = generate(
            closurePrograms[2], names[2]);
        if (deep != null && !deep.hasErrors()) {
            String js = deep.source();
            check(js.contains("$rt.checkArray(\"[?[bytes]]\", "),
                "the (bytes[] | null)[] boundary emits the canonical "
                    + "[?[bytes]] descriptor byte-exact");
            check(js.contains("$rt.checkNullable(\"[[bytes]]\", "),
                "the (bytes[])[] | null boundary emits the canonical "
                    + "[[bytes]] inner descriptor byte-exact");
        }

        // The legal-equality boundary: bytes identity equality is
        // checker-admitted since the ISSUE-0158 E3019 gate lift
        // (binary-comparison-selectors B-D7 assigned the lift to
        // ISSUE-0111/ISSUE-0158) and emits native identity comparison
        // ($rt carriers are Uint8Array objects, so `===` is reference
        // identity) — never a backend E6000 rejection.
        Frontend eq = compileFrontend("""
            export function test(): int {
              let a: bytes = bytes(1);
              let b: bytes = a;
              let c: bytes = bytes(1);
              if (!(a === b)) { return 0; }
              if (a === c) { return 0; }
              if (a !== c) { return 1; }
              return 0;
            }
            """, "jstest-bytes-closure-eq.deal");
        check(eq.errors().isEmpty(),
            "bytes identity equality is checker-admitted with zero "
                + "diagnostics (frontend, ISSUE-0158 lift): " + eq.errors());
        if (eq.errors().isEmpty()) {
            JsBackend.JsCodegenResult eqRes = generate(
                """
            export function test(): int {
              let a: bytes = bytes(1);
              let b: bytes = a;
              let c: bytes = bytes(1);
              if (!(a === b)) { return 0; }
              if (a === c) { return 0; }
              if (a !== c) { return 1; }
              return 0;
            }
            """, "jstest-bytes-closure-eq.deal");
            check(eqRes != null && !eqRes.hasErrors(),
                "the equality closure position generates with zero "
                    + "diagnostics: " + (eqRes == null ? "<null>"
                        : eqRes.diagnostics()));
            if (eqRes != null && !eqRes.hasErrors()) {
                check(eqRes.source().contains("(a === b)")
                        && eqRes.source().contains("(a !== c)"),
                    "bytes equality emits native identity comparison");
            }
        }
        if (nodeAvailable) {
            NodeResult eqRun = runDealNode("""
                export function test(): int {
                  let a: bytes = bytes(1);
                  let b: bytes = a;
                  let c: bytes = bytes(1);
                  if (!(a === b)) { return 0; }
                  if (a === c) { return 0; }
                  if (a !== c) { return 1; }
                  return 0;
                }
                """, "bytes-closure-eq-node");
            check(eqRun.exitCode() == 0 && eqRun.output().equals("1"),
                "bytes identity equality executes real reference identity "
                    + "on Node (alias true, distinct buffers false; "
                    + "returns 1): " + eqRun.output());
        }
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

        // The one Type->text producer over the index (v1.2 identity
        // carriage: class types carry their identities; encode resolves
        // the projection from the identity).
        CanonicalRuntimeTypeDescriptor service =
            new CanonicalRuntimeTypeDescriptor(index);
        check("[@src/app/User]".equals(service.encode(
                new Type.Array(Types.classType("User",
                    new CanonicalClassIdentity(byPath.get("app.user"),
                        "User"))))),
            "encode(Type) emits [@src/app/User] via the index");
        check("(?@src/app/User)->boolean".equals(service.encode(
                new Type.Func(List.of(new Type.Nullable(
                    Types.classType("User",
                        new CanonicalClassIdentity(byPath.get("app.user"),
                            "User")))), Type.Boolean.INSTANCE))),
            "encode(Type) emits ?D function parameters");
        check("@$builtin/Error".equals(service.encode(Types.classType("Error",
                new CanonicalClassIdentity(
                    CanonicalModuleIdentity.BuiltinModule.INSTANCE,
                    "Error")))),
            "encode(Type) emits @$builtin/Error for the builtin Error type");
        check("async(bytes)->bytes".equals(service.encode(
                new Type.Func(List.of(Type.Bytes.INSTANCE), Type.Bytes.INSTANCE, true))),
            "encode(Type) emits async(bytes)->bytes");

        boolean absentIdentityThrows = false;
        try {
            service.encode(Types.classType("Ghost",
                new CanonicalClassIdentity(
                    new CanonicalModuleIdentity.ProjectModule(
                        new ProjectModuleIdentity("bad root",
                            "bad root", List.of())),
                    "Ghost")));
        } catch (IllegalStateException e) {
            absentIdentityThrows = true;
        }
        check(absentIdentityThrows,
            "a class whose identity projects unrepresentable text fails "
            + "closed at the index (invariant)");
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

    private static void testJsonableRuntimeWalkers() throws Exception {
        System.out.println("-- Node: @jsonable runtime walkers (jsonFromJson/jsonToJson) --");
        if (!nodeAvailable) { skipNode("jsonable runtime walkers"); return; }

        // Direct $rt-surface probes of the pinned walker contract
        // (js-v12-jsonable-completion D2-D5): the top-level gate and the
        // {}/[] collapse, extra-key rejection, the three-state roundtrip,
        // the int-range E8004 null collapse, the provided-value
        // failure/zero-defaults phase order, and the toJson E8001 arms.
        NodeResult run = runRuntimeProbe("jsonable-walkers", """
            "use strict";
            const $rt = require("./deal/runtime");
            let $fail = "";
            const $f = (n, j, o, x, e) => {
              const $e = { name: n, jtype: j, optional: !!o, nullable: !!x, hasDefault: true };
              if (e !== undefined) {
                if (e.element !== undefined) $e.element = e.element;
                if (e.className !== undefined) $e.className = e.className;
                if (e.fields !== undefined) $e.fields = e.fields;
              }
              return $e;
            };
            const $fields = [$f("name", "string", false, false),
              $f("nick", "string", true, true), $f("age", "int", false, false)];
            const $thunk = () => ({ name: "", nick: $rt.MISSING, age: 0 });
            // Top-level gate: scalars, null, and [1,2] are null; {}/[]
            // both decode to the defaulted instance with equal texts.
            if ($rt.jsonFromJson("@m/U", $fields, $thunk, "42", "p.js", 1, 1) !== null) $fail = "scalar";
            if ($rt.jsonFromJson("@m/U", $fields, $thunk, "null", "p.js", 1, 1) !== null) $fail = "null-doc";
            if ($rt.jsonFromJson("@m/U", $fields, $thunk, "[1,2]", "p.js", 1, 1) !== null) $fail = "array-doc";
            const $o = $rt.jsonFromJson("@m/U", $fields, $thunk, "{}", "p.js", 1, 1);
            const $a = $rt.jsonFromJson("@m/U", $fields, $thunk, "[]", "p.js", 1, 1);
            if ($o === null || $a === null) $fail = "collapse-null";
            if ($o.$kind !== "class" || $o.$classname !== "@m/U") $fail = "tags";
            if ($rt.jsonToJson("@m/U", $o, $fields, "p.js", 1, 1)
                !== $rt.jsonToJson("@m/U", $a, $fields, "p.js", 1, 1)) $fail = "collapse-text";
            if ($rt.jsonToJson("@m/U", $o, $fields, "p.js", 1, 1)
                !== '{"name":"","age":0}') $fail = "collapse-omit:" + $rt.jsonToJson("@m/U", $o, $fields, "p.js", 1, 1);
            // Extra-key rejection, before any defaults run.
            if ($rt.jsonFromJson("@m/U", $fields, $thunk, '{"name":"Ada","extra":1}', "p.js", 1, 1) !== null) $fail = "extra-key";
            // Three-state: missing omitted, present-null kept, value kept.
            const $m = $rt.jsonFromJson("@m/U", $fields, $thunk, '{"name":"A","age":1}', "p.js", 1, 1);
            if ($m === null || $m.nick !== $rt.MISSING) $fail = "three-missing";
            if ($rt.jsonToJson("@m/U", $m, $fields, "p.js", 1, 1) !== '{"name":"A","age":1}') $fail = "three-omit";
            const $n = $rt.jsonFromJson("@m/U", $fields, $thunk, '{"name":"B","nick":null,"age":2}', "p.js", 1, 1);
            if ($n === null || $n.nick !== null) $fail = "three-null";
            if ($rt.jsonToJson("@m/U", $n, $fields, "p.js", 1, 1) !== '{"name":"B","nick":null,"age":2}') $fail = "three-null-text";
            // Int range: 2^53 collapses to the DEAL null (E8004 inside).
            if ($rt.jsonFromJson("@m/U", $fields, $thunk, '{"age":9007199254740992}', "p.js", 1, 1) !== null) $fail = "int-range";
            // Provided-value failure runs no defaults (counting thunk).
            let $runs = 0;
            const $counting = () => { $runs++; return { name: "", nick: $rt.MISSING, age: 0 }; };
            if ($rt.jsonFromJson("@m/U", $fields, $counting, '{"age":"x"}', "p.js", 1, 1) !== null) $fail = "bad-int";
            if ($runs !== 0) $fail = "defaults-ran:" + $runs;
            // toJson identity backstop and cycle rejection (E8001).
            try { $rt.jsonToJson("@m/Other", $m, $fields, "p.js", 1, 1); $fail = "identity-pass"; }
            catch (e) { if (e.$dealCode !== "E8001") $fail = "identity-code"; }
            const $nodeFields = [$f("next", "class", false, true,
              { className: "@m/Node", fields: [] })];
            const $node = $rt.makeClass("Node", "@m/Node", () => ({ next: null }), null, "p.js", 1, 1);
            $rt.setProp($node, "next", $node);
            try { $rt.jsonToJson("@m/Node", $node, $nodeFields, "p.js", 1, 1); $fail = "cycle-pass"; }
            catch (e) { if (e.$dealCode !== "E8001") $fail = "cycle-code"; }
            console.log($fail === "" ? "JSONABLE-WALKERS-OK" : "JSONABLE-WALKERS-FAIL: " + $fail);
            """);
        check(run.exitCode() == 0
                && run.output().equals("JSONABLE-WALKERS-OK"),
            "the jsonable runtime walkers honor the top-level gate, the "
                + "{}/[] collapse, extra-key rejection, the three-state "
                + "roundtrip, the E8004 int collapse, the zero-defaults "
                + "phase order, and the toJson E8001 arms: " + run.output());
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

    private static void testAsyncExportInvokerNode() throws Exception {
        System.out.println("-- Node: $rt.invokeAsyncExport — production async-export invocation --");
        if (!nodeAvailable) { skipNode("async-export invoker"); return; }

        // Positive: the production oracle scenario (parent D9
        // Verification 11) — init via the host's require, main once, the
        // exact async()->null selection, one native await, the byte-exact
        // completion check, and the bytes closure exercised end to end
        // inside the oracle (js-v12-int32-bytes D5).
        NodeResult ok = runInvokerNode("""
            class Holder { f: async (b: bytes) => bytes; }
            async function bump(b: bytes): bytes {
              b[0] = b[0] + 1;
              return b;
            }
            export function main(): null { return null; }
            export async function oracle(): null {
              let b: bytes = bytes(2);
              b[0] = 1;
              b[1] = 41;
              let f: async (b: bytes) => bytes = bump;
              let h: Holder = { f: f };
              let out: bytes = await h.f(b);
              if (out[0] !== 2 || b[0] !== 2 || b[1] !== 41) {
                throw { code: "ORACLE_FAIL", message: "bytes closure content" };
              }
              out[0] = 9;
              if (b[0] !== 9) {
                throw { code: "ORACLE_FAIL", message: "bytes closure identity" };
              }
              return null;
            }
            """, "invoker-ok", "oracle", "null");
        check(ok.exitCode() == 0 && ok.output().equals("INVOKE_OK: null"),
            "invokeAsyncExport(entry, \"oracle\", \"null\") over the "
                + "production oracle yields { $ok: true, $value: null } "
                + "with the bytes closure content/identity assertions "
                + "green inside the oracle: " + ok.output());

        // Negative host-failure signals: a wrong export name, a sync
        // export, a parameterized export, a descriptor-mismatched
        // returnDescriptor, and a non-wrapper export value each yield
        // { $ok: false, $failure } with the pinned reason — never a DEAL
        // error, never a false runtime-error pass.
        String negatives = """
            export class Payload { v: int; }
            export function main(): null { return null; }
            export async function oracle(): null { return null; }
            export async function takes(i: int): int { return i; }
            """;
        NodeResult wrongName = runInvokerNode(negatives, "invoker-wrong-name",
            "nope", "null");
        check(wrongName.exitCode() == 0 && wrongName.output().equals(
                "INVOKE_FAILURE: export not found: nope"),
            "a wrong export name yields the pinned missing-export host "
                + "failure: " + wrongName.output());
        NodeResult syncExport = runInvokerNode(negatives, "invoker-sync",
            "main", "null");
        check(syncExport.exitCode() == 0 && syncExport.output().equals(
                "INVOKE_FAILURE: sync export: expected async()->null, got ()->null"),
            "a sync export yields the pinned sync host failure: "
                + syncExport.output());
        NodeResult paramExport = runInvokerNode(negatives, "invoker-param",
            "takes", "int");
        check(paramExport.exitCode() == 0 && paramExport.output().equals(
                "INVOKE_FAILURE: parameterized export: expected async()->int, got async(int)->int"),
            "a parameterized export yields the pinned parameterized host "
                + "failure: " + paramExport.output());
        NodeResult mismatch = runInvokerNode(negatives, "invoker-mismatch",
            "oracle", "int");
        check(mismatch.exitCode() == 0 && mismatch.output().equals(
                "INVOKE_FAILURE: descriptor mismatch: expected async()->int, got async()->null"),
            "a descriptor-mismatched returnDescriptor yields the pinned "
                + "descriptor-mismatch host failure: " + mismatch.output());
        NodeResult nonWrapper = runInvokerNode(negatives, "invoker-non-wrapper",
            "Payload", "null");
        check(nonWrapper.exitCode() == 0 && nonWrapper.output().equals(
                "INVOKE_FAILURE: export is not a function: expected async()->null"),
            "a non-wrapper export value (an exported class) yields the "
                + "pinned non-function host failure: " + nonWrapper.output());
        NodeResult noMain = runInvokerNodeNoMain("""
            export async function oracle(): null { return null; }
            """, "invoker-no-main", "oracle", "null");
        check(noMain.exitCode() == 0 && noMain.output().equals(
                "INVOKE_FAILURE: missing main"),
            "a main-less entry artifact (the E6004 backstop shape) yields "
                + "the pinned missing-main host failure: " + noMain.output());

        // DEAL-error signal with the exact source location: a rejected
        // operation propagates the reified E8005 code/message and the
        // pinned file/line/column of the failing operator.
        NodeResult rejected = runInvokerNode("""
            async function bomb(): int {
              return 1 / 0;
            }
            export function main(): null { return null; }
            export async function oracle(): null {
              await bomb();
              return null;
            }
            """, "invoker-reject", "oracle", "null");
        check(rejected.exitCode() == 0 && rejected.output().equals(
                "INVOKE_ERROR: E8005 integer division by zero at jstest-invoker-reject.deal:2:10"),
            "a rejected oracle yields { $ok: false, $error } with the "
                + "reified E8005 code and the exact source location: "
                + rejected.output());

        // The defensive arms no checker-accepted DEAL source reaches: the
        // completion-mismatch arm reifies E8001 "expected null"; a DEAL
        // error from main reifies with its exact code/message and
        // file/line/column; an invalid entry or return descriptor is a
        // host failure — never a DEAL error, never a throw.
        NodeResult probe = runRuntimeProbe("async-export-invoker", """
            "use strict";
            const $rt = require("./deal/runtime");
            (async () => {
              let $fail = "";
              const $entry = {
                main: $rt.function("()->null", function() { return null; }),
                oracle: { $kind: "function", $sig: "async()->null",
                  $f: async function() { return 42; } },
              };
              const $r = await $rt.invokeAsyncExport($entry, "oracle", "null");
              if ($r.$ok !== false) $fail = "ok-signal";
              if ($r.$failure !== undefined) $fail = "failure-signal";
              if ($r.$error === null || typeof $r.$error !== "object") $fail = "error-shape";
              if ($r.$error.$kind !== "class" || $r.$error.$classname !== "@$builtin/Error") $fail = "error-tags";
              if ($r.$error.code !== "E8001" || $r.$error.message !== "expected null") $fail = "error-fields";
              const $rm = { main: { $kind: "function", $sig: "()->null",
                $f: function() { $rt.fail("E8001", "main boom", "probe.js", 7, 3); } },
                oracle: $rt.function("async()->null", async function() { return null; }) };
              const $r2 = await $rt.invokeAsyncExport($rm, "oracle", "null");
              if ($r2.$ok !== false || $r2.$failure !== undefined) $fail = "main-error-signal";
              if ($r2.$error.code !== "E8001" || $r2.$error.message !== "main boom"
                  || $r2.$error.file !== "probe.js" || $r2.$error.line !== 7
                  || $r2.$error.column !== 3) $fail = "main-error-fields";
              const $r3 = await $rt.invokeAsyncExport(null, "oracle", "null");
              if ($r3.$failure !== "entry exports is not a module object") $fail = "entry-guard";
              const $r4 = await $rt.invokeAsyncExport($entry, "oracle", 42);
              if ($r4.$failure !== "invalid return descriptor") $fail = "descriptor-guard";
              console.log($fail === "" ? "INVOKER-PROBE-OK" : "INVOKER-PROBE-FAIL: " + $fail);
            })();
            """);
        check(probe.exitCode() == 0
                && probe.output().equals("INVOKER-PROBE-OK"),
            "the defensive completion-mismatch arm reifies E8001 'expected "
                + "null', a DEAL error from main reifies with its exact "
                + "fields, and invalid entry/descriptor inputs yield host "
                + "failures: " + probe.output());

        // Exactly-once: a runner-instrumented production entry — main
        // and the oracle wrapped with invocation counters — asserts
        // exactly one main call and one export call across one
        // main-then-invoke run.
        NodeResult counted = runCounterNode("""
            export function main(): null { return null; }
            export async function oracle(): null { return null; }
            """, "invoker-once");
        check(counted.exitCode() == 0 && counted.output().equals(
                "INVOKE_ONCE_OK: main=1 oracle=1 value=null"),
            "one main-then-invoke run invokes main exactly once and the "
                + "export exactly once: " + counted.output());
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

        // Test-only isolated-phase orchestrator path drives Backend.JS
        // directly with the legacy roots/stdlib inputs (the production
        // manifest/CLI path is pinned separately by
        // testCliBackendFlagJsDefaultOutput / testCliDefaultOutputBuildJs
        // / testDealJsonBackendAcceptance).
        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            entryFile, outputDir, false, false, false, Backend.JS,
            (Map<String, String>) null, roots,
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
        // Test-only isolated-phase Backend.JS path (the production
        // manifest/CLI path is pinned separately).
        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            entryFile, outputDir, false, false, false, Backend.JS,
            (Map<String, String>) null, roots,
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

        // The retired @jsonable rejection (js-v12-jsonable-completion
        // D1), the retired nested-class rejection (ISSUE-0318), and the
        // retired host-ABI E6000 (ISSUE-0328 — the passing model lives
        // in testHostAbiOrchestratorNode) no longer drive this pin; the
        // still-live @extern-c E6003 arm keeps the
        // no-partial-artifact rejection model covered — a rejected
        // module fails the whole compilation and nothing is published
        // (the transactional whole-set contract,
        // whole-project-artifact-publication D3/D4; the single-module
        // model lives in testNoPartialArtifactOnRejection).
        // ISSUE-0273 D9 re-key: the E6003 trigger is an import of an
        // extern-C declaration module — the @extern-c file directive
        // lives on ffi.d.deal, never on the importing implementation
        // file (where it is E1046). The import is manifest-backed (an
        // externals entry with nativeLibrary) — an unbacked extern-C
        // import is the frontend E2010 invalid-manifest-policy
        // rejection (docs/spec-v1.2.md:1891), which the production
        // pipeline emits before codegen (ISSUE-0477 remediation).
        writeFile("rej_proj/deal.json",
            "{\"languageVersion\": \"1.2\", \"backend\": \"js\","
                + " \"moduleRoots\": [\"src\"], \"externals\": {"
                + " \"ffi\": {\"declaration\": \"src/ffi.d.deal\","
                + " \"nativeLibrary\": \"libhost\"}}}");
        writeFile("rej_proj/src/ffi.d.deal", """
            // @extern-c
            export function cFn(x: int): int;
            """);
        writeFile("rej_proj/src/lib.deal", """
            import * as ffi from "ffi"
            export function use(): int { return ffi.cFn(1); }
            """);
        writeFile("rej_proj/src/rej_main.deal", """
            import * as lib from "./lib"
            export function main(): null { return null; }
            export function run(): int { return 1; }
            """);

        Path entryFile = tmpDir.resolve("rej_proj/src/rej_main.deal").toAbsolutePath();
        // The production locator path (ISSUE-0169 remediation,
        // ISSUE-0471): the manifest-selected Backend.JS compile
        // rejects the manifest-backed @extern-c import with E6003.
        ProjectLocator.LocateResult located = ProjectLocator.locate(
            entryFile.toString(), null);
        check(located.context() != null && located.e2010() == null
                && located.cliDiagnostic() == null,
            "rejection manifest locates a context: "
                + (located.e2010() != null
                    ? located.e2010() : located.cliDiagnostic()));
        if (located.context() == null) {
            return;
        }
        CompilerInvocation invocation = CompilerProfileProvider.resolve(
            ReleaseConfiguration.CURRENT_RELEASE_STATE,
            ReleaseConfiguration.releaseCapabilityRegistry());
        CompilationOrchestrator orchestrator =
            new CompilationOrchestrator(located.context(), entryFile,
                false, false, false, false, null, invocation);
        Path outputDir = Path.of(located.context().outputPath()
            .absoluteNormalizedPath());
        boolean success = orchestrator.compile();
        check(!success, "the @extern-c project fails the compilation");
        check(orchestrator.diagnostics().stream().anyMatch(d ->
                "E6003".equals(d.code())),
            "the orchestrator reports E6003: " + orchestrator.diagnostics());
        check(!Files.exists(outputDir.resolve("lib.js")),
            "the rejected module writes no artifact");
        // Transactional publication (whole-project-artifact-publication
        // D3/D4): a rejected module fails the whole compilation, so
        // nothing is published — the clean sibling's staged artifact is
        // discarded with the stage tree and never reaches the live
        // root.
        check(!Files.exists(outputDir.resolve("rej_main.js")),
            "no clean-sibling artifact is published (whole-set failure "
                + "contract)");
        check(!Files.exists(outputDir),
            "the failed compilation publishes no live root at all");
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

    /**
     * deal.json {@code "backend": "js"} selects the JS backend through
     * the production locator (ISSUE-0169 remediation, ISSUE-0471): the
     * strict manifest parses, the published context carries the
     * effective backend {@code "js"} and the backend-dependent default
     * output {@code build/js} (manifest-relative), and no diagnostic is
     * produced. An unknown backend stays one E2010 naming the supported
     * set.
     */
    private static void testDealJsonBackendAcceptance() throws Exception {
        Path projectDir = null;
        try {
            projectDir = Files.createTempDirectory("deal_js_backend_test_");
            Files.writeString(projectDir.resolve("deal.json"),
                "{\n  \"languageVersion\": \"1.2\",\n  \"backend\": \"js\"\n}\n",
                StandardCharsets.UTF_8);
            Files.writeString(projectDir.resolve("main.deal"),
                "export function main(): null { return null; }\n",
                StandardCharsets.UTF_8);
            ProjectLocator.LocateResult result = ProjectLocator.locate(
                projectDir.resolve("main.deal").toString(), null);
            check(result.context() != null && result.e2010() == null
                    && result.cliDiagnostic() == null,
                "deal.json with \"backend\": \"js\" locates a context: "
                    + (result.e2010() != null
                        ? result.e2010() : result.cliDiagnostic()));
            if (result.context() != null) {
                check("js".equals(result.context().backend()),
                    "the published context's effective backend is \"js\"");
                check("build/js".equals(
                        result.context().outputPath().decodedText()),
                    "the js default output text is build/js");
                check(result.context().outputPath()
                            .absoluteNormalizedPath()
                            .equals(projectDir.toRealPath().toString()
                                + "/build/js"),
                    "the js default output resolves from the manifest"
                        + " directory");
                check(!Files.exists(projectDir.resolve("build")),
                    "locate created no output directory");
            }

            // An unknown backend stays a configuration error (E2010).
            Files.writeString(projectDir.resolve("deal.json"),
                "{\n  \"languageVersion\": \"1.2\",\n"
                    + "  \"backend\": \"python\"\n}\n",
                StandardCharsets.UTF_8);
            ProjectLocator.LocateResult badResult = ProjectLocator.locate(
                projectDir.resolve("main.deal").toString(), null);
            check(badResult.context() == null && badResult.e2010() != null,
                "an unknown manifest backend is rejected with E2010: "
                    + (badResult.e2010() != null
                        ? badResult.e2010() : badResult.cliDiagnostic()));
        } finally {
            deleteDir(projectDir);
        }
    }

    /**
     * Production CLI over a manifest-selected {@code "backend": "js"}
     * (ISSUE-0169 remediation, ISSUE-0471): {@code deal.Main compile}
     * with no {@code --backend} flag selects {@link Backend#JS} from
     * the strict manifest through the production locator, exits 0, and
     * writes the emitted entry artifact plus the deployed runtime under
     * the manifest-relative default {@code build/js}. The subprocess
     * working directory is the repository root (the deployment copies
     * fall back to the repo-root {@code deal/runtime.js} source) while
     * the entry path is absolute.
     */
    private static void testCliDefaultOutputBuildJs() throws Exception {
        Path projectDir = null;
        try {
            projectDir = Files.createTempDirectory("deal_js_cli_test_");
            Files.writeString(projectDir.resolve("deal.json"),
                "{\n  \"languageVersion\": \"1.2\",\n  \"backend\": \"js\",\n"
                    + "  \"moduleRoots\": [\".\"]\n}\n",
                StandardCharsets.UTF_8);
            Path entry = projectDir.resolve("main.deal");
            Files.writeString(entry,
                "export function main(): null { return null; }\n",
                StandardCharsets.UTF_8);

            Path buildDir = Path.of("build").toAbsolutePath().normalize();
            Process process = new ProcessBuilder(
                "java", "-ea", "-cp", buildDir.toString(), "deal.Main",
                "compile", entry.toString())
                .directory(Path.of("").toAbsolutePath().normalize().toFile())
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
                "CLI compile with the manifest-selected js backend exits 0: "
                    + output);
            check(Files.isRegularFile(projectDir.resolve("build/js/main.js")),
                "the manifest-selected build/js default holds the emitted"
                    + " entry artifact");
            check(Files.isRegularFile(
                    projectDir.resolve("build/js/deal/runtime.js")),
                "the manifest-selected build/js default deploys"
                    + " deal/runtime.js");
            check(!output.contains("E2010") && !output.contains("E6000"),
                "no E2010/E6000 for the production js manifest backend: "
                    + output);
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
                "{\n  \"languageVersion\": \"1.2\",\n  \"backend\": \"js\",\n"
                    + "  \"moduleRoots\": [\".\"]\n}\n",
                StandardCharsets.UTF_8);
            Path entry = projectDir.resolve("main.deal");
            Files.writeString(entry,
                "import * as c from \"std/console\";\n\n"
                    + "export function main(): null {\n"
                    + "  c.log(\"deploy\");\n"
                    + "  return null;\n"
                    + "}\n",
                StandardCharsets.UTF_8);
            // The production locator path (ISSUE-0169 remediation,
            // ISSUE-0471): the manifest selects Backend.JS, the
            // context-based orchestrator constructor runs the compile,
            // and the default output build/js receives the runtime and
            // stdlib deployment copies.
            ProjectLocator.LocateResult located = ProjectLocator.locate(
                entry.toString(), null);
            check(located.context() != null && located.e2010() == null
                    && located.cliDiagnostic() == null,
                "deployment-copies manifest locates a context: "
                    + (located.e2010() != null
                        ? located.e2010() : located.cliDiagnostic()));
            if (located.context() == null) {
                return;
            }
            CompilerInvocation invocation = CompilerProfileProvider.resolve(
                ReleaseConfiguration.CURRENT_RELEASE_STATE,
                ReleaseConfiguration.releaseCapabilityRegistry());
            CompilationOrchestrator orchestrator =
                new CompilationOrchestrator(located.context(), entry,
                    false, false, false, false, null, invocation);
            boolean ok = orchestrator.compile();
            check(ok, "JS orchestrator compile succeeds: "
                + orchestrator.diagnostics());
            Path outputRoot = Path.of(located.context().outputPath()
                .absoluteNormalizedPath());
            check(outputRoot.toString().endsWith("build/js"),
                "the production default output root is build/js: "
                    + outputRoot);
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
     * No-partial-artifact: an {@code @extern-c} import (the retained
     * E6003 rejection) fails the compilation and the rejected module
     * writes no artifact — the rejection model the retired host-ABI
     * E6000 arm used to cover (ISSUE-0328 retired that arm; the
     * host-ABI passing model lives in testHostAbiOrchestratorNode).
     */
    private static void testNoPartialArtifactOnRejection() throws Exception {
        Path projectDir = null;
        try {
            projectDir = Files.createTempDirectory("deal_js_reject_test_");
            Files.writeString(projectDir.resolve("deal.json"),
                "{\n  \"languageVersion\": \"1.2\",\n  \"backend\": \"js\",\n"
                    + "  \"moduleRoots\": [\".\"],\n"
                    + "  \"externals\": {\"ffi\": {\"declaration\":"
                    + " \"ffi.d.deal\", \"nativeLibrary\": \"libhost\"}}\n}\n",
                StandardCharsets.UTF_8);
            // ISSUE-0273 D9 re-key: the E6003 trigger is an import of an
            // extern-C declaration module — the @extern-c file directive
            // lives on ffi.d.deal (on an implementation file it is
            // E1046). The import is manifest-backed (an externals entry
            // with nativeLibrary) — an unbacked extern-C import is the
            // frontend E2010 invalid-manifest-policy rejection
            // (docs/spec-v1.2.md:1891), emitted before codegen
            // (ISSUE-0477 remediation).
            Files.writeString(projectDir.resolve("ffi.d.deal"),
                "// @extern-c\nexport function cFn(x: int): int;\n",
                StandardCharsets.UTF_8);
            Path entry = projectDir.resolve("main.deal");
            Files.writeString(entry,
                "import * as ffi from \"ffi\";\n\n"
                    + "export function main(): null {\n"
                    + "  let v: int = ffi.cFn(1);\n"
                    + "  return null;\n"
                    + "}\n",
                StandardCharsets.UTF_8);
            // The production locator path (ISSUE-0169 remediation,
            // ISSUE-0471): the manifest-selected Backend.JS compile
            // rejects the @extern-c import with E6003 and writes no
            // artifact for the rejected module.
            ProjectLocator.LocateResult located = ProjectLocator.locate(
                entry.toString(), null);
            check(located.context() != null && located.e2010() == null
                    && located.cliDiagnostic() == null,
                "rejection manifest locates a context: "
                    + (located.e2010() != null
                        ? located.e2010() : located.cliDiagnostic()));
            if (located.context() == null) {
                return;
            }
            CompilerInvocation invocation = CompilerProfileProvider.resolve(
                ReleaseConfiguration.CURRENT_RELEASE_STATE,
                ReleaseConfiguration.releaseCapabilityRegistry());
            CompilationOrchestrator orchestrator =
                new CompilationOrchestrator(located.context(), entry,
                    false, false, false, false, null, invocation);
            Path outputRoot = Path.of(located.context().outputPath()
                .absoluteNormalizedPath());
            boolean ok = orchestrator.compile();
            check(!ok, "an @extern-c import fails the JS compilation");
            check(orchestrator.diagnostics().stream()
                    .anyMatch(d -> "E6003".equals(d.code())),
                "the rejection is E6003: " + orchestrator.diagnostics());
            check(!Files.exists(outputRoot.resolve("main.js")),
                "no entry artifact is written for the rejected module");
            check(!Files.exists(outputRoot.resolve("ffi.js")),
                "no artifact is written for the declaration file");
        } finally {
            deleteDir(projectDir);
        }
    }
}
