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
import deal.codegen.HostModuleDeclarations;
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
import java.util.Optional;
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
 * generated-artifact runner ({@link StubModuleResolver#buildJsRunner}),
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
            PreparedJs nestedExport = prepareJsUnshaped("""
                export class Outer { x: int = 1; }
                export function test(): int { return 1; }
                """, "moduleclass-export");
            PreparedJs nestedShadow = prepareJs("""
                class Outer { v: int = 1; }
                class Alternate { w: string = "s"; }
                export function test(): int {
                  let a: Outer = {};
                  {
                    a = { v: 9 };
                    let b: Alternate = {};
                    if (b.w !== "s") { return 0; }
                  }
                  if (a.v !== 9) { return 0; }
                  return 1;
                }
                """, "moduleclass-distinct-base");
            testNestedClassEmissionPins(nestedExport, nestedShadow);
            testNestedClassNodeSemantics(nestedExport, nestedShadow);
            testEntryShimAndE6004();
            testSourceLocationArguments();
            testOwnPropertySafeProtoEmission();
            testErrorEmissionPins();
            testArrayElementDeleteEmission();
            testArrayReadBoundsEmission();
            testHostGlobalHygiene();
            testUnsupportedConstructsRejected();
            testHostAbiEmissionPins();
            testHostAbiOrchestratorNode();
            testHostOccurrenceAndTraversalNode();
            testSourceMapSidecarsEmitted();
            testIntArithmeticEdgeCodes();
            testInt32RuntimeGateEmission();
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
            testErrorDefaultFilling();
            testContextualErrorConstruction();
            testRethrowPreservesCode();
            testArrayElementDeleteSemantics();
            testSpanParameterCollisionExecution();
            testStdJsonRoundTrips();
            testJsonDecodedArrayElementKindNode();
            testJsonDecodedArrayReadLengthNode();
            testJsonableDecodedArrayFieldNode();
            testJsonStoredFunctionE8001();
            testJsonableRuntimeWalkers();
            testIntrinsicFunctionValues();
            testAsyncCompletionAndErrors();
            testAsyncExportInvokerNode();
            testProductionArityExtensionOrderNode();
            testAwaitCapableArrayWriteOrderNode();
            testAsyncErrorDetailTransportNode();
            testCornerCasePublicErrorsNode();
            testProductionCompanionDirectNode();
            testProductionNodeCaptureBoundary();
            testOrchestratorJsBackend();
            testOrchestratorJsNestedModule();
            testOrchestratorJsRejectsUnsupported();
            testDealJsonBackendAcceptance();
            testCliDefaultOutputBuildJs();
            testElseIfChainWithoutFinalElse();
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
            new StubModuleResolver());
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
            new StubModuleResolver(), false);
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
     * host-ABI class fixture does — the E6006 re-key pin passes the raw
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
            index.moduleIdentityLookup(), null);
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

    private record PreparedJs(Frontend frontend,
                              JsBackend.JsCodegenResult emission) {}

    private static PreparedJs prepareJs(String source, String name) {
        String filename = "jstest-" + name + ".deal";
        Frontend f = compileFrontend(source, filename);
        JsBackend.JsCodegenResult res = f.program() == null ? null
            : JsBackend.generate(f.program(), f.checkResult(), filename,
                "Main", Map.of(), Map.of(), false);
        return new PreparedJs(f, res);
    }

    private static PreparedJs prepareJsUnshaped(String source, String name) {
        String filename = "jstest-" + name + ".deal";
        Frontend f = compileFrontendUnshaped(source, filename);
        JsBackend.JsCodegenResult res = f.program() == null ? null
            : JsBackend.generate(f.program(), f.checkResult(), filename,
                "Main", Map.of(), Map.of(), false);
        return new PreparedJs(f, res);
    }

    private record NodeResult(String output, int exitCode) {}

    /**
     * Deploys one generated artifact plus the runtime/stdlib support set
     * into a fresh temp dir (the conformance adapter's deployment shape:
     * {@link StubModuleResolver#deployJsSupport}).
     */
    private static Path deployArtifacts(JsBackend.JsCodegenResult res,
                                        String artifactName) throws IOException {
        Path dir = Files.createTempDirectory("jstest_run_");
        Files.writeString(dir.resolve(artifactName), res.source());
        StubModuleResolver.deployJsSupport(dir);
        return dir;
    }

    /** Executes a node script in {@code dir} and returns the combined
     * output and exit code. */
    private static NodeResult runNodeScript(Path dir, String scriptFile,
            String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("node", scriptFile));
        command.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(command);
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
        return runDealArtifact(prepareJs(source, name));
    }

    private static JsBackend.JsCodegenResult checkedEmission(PreparedJs prepared) {
        if (!prepared.frontend().errors().isEmpty()) {
            throw new RuntimeException("frontend errors: "
                + prepared.frontend().errors());
        }
        JsBackend.JsCodegenResult res = prepared.emission();
        if (res.hasErrors()) {
            throw new RuntimeException("codegen errors: " + res.diagnostics());
        }
        return res;
    }

    private static NodeResult runDealArtifact(PreparedJs prepared)
            throws Exception {
        Path dir = deployArtifacts(checkedEmission(prepared), "Main.js");
        try {
            Files.writeString(dir.resolve("JsConformanceRunner.js"),
                StubModuleResolver.buildJsRunner(prepared.frontend().program()));
            return runNodeScript(dir, "JsConformanceRunner.js");
        } finally {
            deleteDir(dir);
        }
    }

    /**
     * A direct {@code $rt}-surface probe under node (the runtime-page
     * defensive arms no checker-accepted DEAL source reaches).
     */
    private static NodeResult runRuntimeProbe(String name, String script)
            throws Exception {
        Path dir = Files.createTempDirectory("jstest_probe_");
        StubModuleResolver.deployJsSupport(dir);
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
        return runInvokerArtifact(checkedEmission(prepareJs(source, name)),
            exportName, returnDescriptor);
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
            Map.of(), Map.of(), true);
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
        return runArtifactScript(res, INVOKER_RUNNER_SOURCE,
            exportName, returnDescriptor);
    }

    private static NodeResult runArtifactScript(JsBackend.JsCodegenResult res,
            String script, String... args) throws Exception {
        Path dir = deployArtifacts(res, "Main.js");
        try {
            Files.writeString(dir.resolve("runner.js"), script);
            return runNodeScript(dir, "runner.js", args);
        } finally {
            deleteDir(dir);
        }
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
        "$rt.invokeAsyncExport($entry, process.argv[2], process.argv[3]).then((r) => {",
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
        return runArtifactScript(checkedEmission(prepareJs(source, name)),
            COUNTER_RUNNER_SOURCE);
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

    /**
     * ISSUE-0756 subject {@code async-extended-argument-order} on the real
     * v1.2 public path: the subject — its own
     * {@code console.log("async-extended-argument-order")} output retained —
     * is published through the public {@code deal compile} entry under the
     * v1.2 manifest (the emitted artifact carries the
     * {@code DEAL_V1_3_INT32} selector), and a real node process then
     * requires the published artifact and invokes the export through the
     * production host ABI {@code $rt.invokeAsyncExport}. The subject's own
     * output is the only stdout write, so the node transcript is the
     * package's exact stream expectation; the runner records the checked
     * null completion in its result file. The legacy-profile in-memory
     * generation path cannot satisfy this check.
     */
    private static void testProductionArityExtensionOrderNode()
            throws Exception {
        System.out.println("-- Node: the v1.2 published arity-extension"
            + " subject through $rt.invokeAsyncExport --");
        if (!nodeAvailable) {
            skipNode("production arity-extension order");
            return;
        }
        ProductionNodeRun run = runProductionInvokerNode("""
            import * as c from "std/console";

            export function main(): null { return null; }

            export async function run(): null {
              let trace: string = "";
              async function value(s: string, x: int): int { trace = trace + s; return x; }
              async function small(x: int): int { trace = trace + "F"; return x; }
              let f: async (x: int, y: int) => int = small;
              let r: int = await f(await value("A", 7), await value("B", 8));
              if (r !== 7 || trace !== "ABF") {
                throw { code: "ARITY_FAIL", message: "async extended arguments: " + trace };
              }
              c.log("async-extended-argument-order");
              return null;
            }
            """, "production-arity-order", "run", "null");
        check(run.v12Profile(), "the published artifact runs the signed-32"
            + " gate from runtime load (no profile selector needed)");
        check(run.exitCode() == 0, "the published subject's host process"
            + " exits 0: exit=" + run.exitCode() + " stderr=<" + run.stderr()
            + ">");
        check(run.stdout().equals("async-extended-argument-order\n"),
            "the published subject's own stdout is the exact package"
                + " stream expectation: <" + run.stdout() + ">");
        check(run.stderr().isEmpty(), "the published subject keeps the"
            + " package's empty stderr: <" + run.stderr() + ">");
        check(run.signal().equals("INVOKE_OK: null\n"), "the production"
            + " host ABI reports the checked null completion: <"
            + run.signal() + ">");
    }

    /**
     * The JS async error transport: the public {@code $ok:false,$error}
     * result of a failing awaited export carries the complete ErrorDetail
     * — code/message, the read-check origin, expected/actual and the
     * captured frames naming the generated fail/run functions — through
     * the existing error object, never a code/location-only record.
     */
    private static void testAsyncErrorDetailTransportNode() throws Exception {
        System.out.println("-- Node: the async $error result carries the"
            + " complete ErrorDetail --");
        if (!nodeAvailable) {
            skipNode("async error detail transport");
            return;
        }
        JsBackend.JsCodegenResult res = checkedEmission(prepareJs("""
            async function fail(): int {
              let t: table = {value: "bad"};
              let x: int = t.value;
              return x;
            }
            export function main(): null { return null; }
            export async function oracle(): int { return await fail(); }
            """, "async-detail"));
        NodeResult result = runArtifactScript(res, String.join("\n",
            "\"use strict\";",
            "const $rt = require(\"./deal/runtime\");",
            "const $entry = require(\"./Main\");",
            "$rt.invokeAsyncExport($entry, \"oracle\", \"int\").then((r) => {",
            "  if (r.$ok === true) {",
            "    process.stdout.write(\"DETAIL: unexpected value\");",
            "    return;",
            "  }",
            "  const e = r.$error;",
            "  process.stdout.write(\"DETAIL: \" + e.code + \"|\" + e.message",
            "    + \"|\" + e.line + \":\" + e.column + \"|\" + e.expected",
            "    + \"|\" + e.actual + \"|\" + e.frames);",
            "});",
            ""));
        String expected = "DETAIL: E8001|expected int|3:16|int|string";
        check(result.exitCode() == 0
                && result.output().startsWith(expected)
                && result.output().contains("fail$f")
                && result.output().contains("oracle$f"),
            "the async $error result carries the complete ErrorDetail with"
                + " the generated fail/run frames: " + result.output());
    }

    /**
     * The retained corner-case public errors on the JS lane (ISSUE-0815
     * subjects 5-8): the published two-module project's {@code run} export
     * is invoked through the actual public surfaces — the sync direct and
     * rethrow forms through the exported-function wrapper, the async
     * direct and rethrow forms through the production
     * {@code $rt.invokeAsyncExport} API with exportName {@code run} and
     * returnDescriptor {@code int} — and every result carries the complete
     * ErrorDetail (code, message, companion origin, expected, actual and
     * the captured fail/run frames), while the program's own streams stay
     * empty.
     */
    private static void testCornerCasePublicErrorsNode() throws Exception {
        System.out.println("-- Node: the retained corner-case public errors"
            + " (sync/async, direct/rethrow) --");
        if (!nodeAvailable) { skipNode("corner-case public errors"); return; }
        for (boolean rethrow : List.of(false, true)) {
            for (boolean async : List.of(false, true)) {
                String mode = async ? "async" : "sync";
                String name = mode + "-" + (rethrow ? "rethrow" : "direct");
                String prefix = async ? "async " : "";
                String call = "return " + (async ? "await " : "") + "helper.fail();";
                Path root = publishCornerCaseProject(name,
                    COMPANION_DIRECT_HELPER_SOURCE.replace("export function",
                        "export " + prefix + "function"),
                    COMPANION_DIRECT_MAIN_SOURCE
                        .replace("export function run", "export " + prefix + "function run")
                        .replace("return helper.fail();", rethrow
                            ? "try {" + call + "} catch(e) {throw e;}" : call));
                try {
                    assertCornerCasePublished(name, root, mode);
                } finally {
                    deleteDir(root);
                }
            }
        }
    }

    /**
     * The exported-function-wrapper host of the sync subjects: it
     * initializes the module through the published {@code main} wrapper,
     * invokes the sync {@code run} wrapper, catches the escaped companion
     * error and records the complete ErrorDetail in a private file, so both
     * program streams remain the program's own.
     */
    private static final String CORNER_CASE_HOST_SOURCE = """
        "use strict";
        const fs = require("fs");
        const $rt = require("./deal/runtime");
        const $entry = require("./main");
        const signal = process.argv[3];
        function record(error) {
          if (!error || typeof error !== "object") {
            fs.writeFileSync(signal, "NO_ERROR\\n");
            process.exit(4);
          }
          fs.writeFileSync(signal, [
            "code=" + error.code,
            "message=" + error.message,
            "file=" + error.file,
            "line=" + error.line,
            "column=" + error.column,
            "expected=" + error.expected,
            "actual=" + error.actual,
            "frames=" + error.frames,
            "cause=" + Object.hasOwn(error, "cause"),
          ].join("\\n") + "\\n");
        }
        if (process.argv[2] === "async") {
          $rt.invokeAsyncExport($entry, "run", "int").then((r) => {
            if (r.$ok === true) {
              fs.writeFileSync(signal, "value=" + r.$value + "\\n");
            } else if (typeof r.$failure === "string") {
              fs.writeFileSync(signal, "failure=" + r.$failure + "\\n");
            } else {
              record(r.$error);
            }
          });
        } else {
          $entry.main.$f();
          let error;
          try { $entry.run.$f(); } catch (caught) { error = caught; }
          record(error);
        }
        """;

    /** Publishes one two-module corner-case project through the public
     * {@code deal compile} entry and returns the project root. */
    private static Path publishCornerCaseProject(String name,
            String helperSource, String mainSource) throws Exception {
        Path root = Files.createTempDirectory("jstest_corner_case_" + name + "_");
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("deal.json"),
            "{\"languageVersion\": \"1.3\", \"moduleRoots\": [\"src\"],"
                + " \"output\": \"out\", \"backend\": \"js\"}\n",
            StandardCharsets.UTF_8);
        Files.writeString(root.resolve("src/helper.deal"), helperSource,
            StandardCharsets.UTF_8);
        Path entry = root.resolve("src/main.deal");
        Files.writeString(entry, mainSource, StandardCharsets.UTF_8);
        int exit = deal.Main.run(new String[] {"compile",
            entry.toAbsolutePath().toString()});
        check(exit == 0, name + ": the public deal compile of the"
            + " corner-case project succeeds: exit=" + exit);
        if (exit != 0) {
            throw new IllegalStateException(name + ": the corner-case"
                + " project did not compile through the public entry");
        }
        return root;
    }

    /**
     * Drives one published corner-case project through one public host and
     * asserts the complete observed ErrorDetail: the exact fields of the
     * companion read, the program's empty streams and the wrong-fields
     * controls of the shared verdict.
     */
    private static void assertCornerCasePublished(String name, Path root,
            String mode) throws Exception {
        Path out = root.resolve("out");
        boolean v12Profile = !Files.readString(out.resolve("main.js"),
            StandardCharsets.UTF_8).contains("setInt32Mode");
        check(v12Profile, name + ": the published artifact carries no profile"
            + " switch under the sole v1.3 profile");
        Files.writeString(out.resolve("corner_host.js"), CORNER_CASE_HOST_SOURCE,
            StandardCharsets.UTF_8);
        ProductionNodeRun run = runNodeSplit(out, "corner_host.js",
            "corner_error.txt", v12Profile, mode, "corner_error.txt");
        check(run.exitCode() == 0, name + ": the public host exits 0: exit="
            + run.exitCode() + " stderr=<" + run.stderr() + ">");
        check(run.stdout().isEmpty(), name + ": the program stdout stays"
            + " empty: <" + run.stdout() + ">");
        check(run.stderr().isEmpty(), name + ": the program stderr stays"
            + " empty: <" + run.stderr() + ">");
        check(run.signal().contains("cause=false\n"),
            name + ": direct/rethrow error has no own cause property");
        CompanionErrorRecord observed = CompanionErrorRecord.parse(run.signal());
        Optional<String> verdict = companionDirectMismatch(observed);
        check(verdict.isEmpty(), name + ": the public error record is the"
            + " complete ErrorDetail: " + verdict.orElse("match"));
        check(companionDirectMismatch(observed.withColumn("10")).isPresent(),
            name + ": the verdict rejects the later annotation column 10");
        check(companionDirectMismatch(
                observed.withFrames("Object.someOther$f")).isPresent(),
            name + ": the verdict rejects frames without the fail/run"
                + " functions");
    }

    /**
     * The await-capable array write: the JS assignment sequence must
     * evaluate receiver, index and the awaited RHS once in that order
     * before the E8002 bounds rejection, publish no write and preserve
     * alias/length/value, then append. The subject runs through the
     * production async-export surface and reports its result as the
     * completion value, so a reordered or suppressed operand fails the
     * run.
     */
    private static void testAwaitCapableArrayWriteOrderNode()
            throws Exception {
        System.out.println("-- Node: the await-capable array write keeps the"
            + " receiver/index/RHS order before the bounds gate --");
        if (!nodeAvailable) {
            skipNode("await-capable array write");
            return;
        }
        NodeResult run = runInvokerNode("""
            export function main(): null { return null; }
            export async function oracle(): int {
              let xs: int[] = [7];
              let alias: int[] = xs;
              let trace: string = "";
              async function value(): int { trace = trace + "V"; return 9; }
              function index(): int { trace = trace + "I"; return 2; }
              function receiver(): int[] { trace = trace + "R"; return xs; }
              try { receiver()[index()] = await value(); trace = trace + "X"; }
              catch (e) { if (e.code !== "E8002") { return 0; } trace = trace + "C"; }
              if (trace !== "RIVC" || alias.length !== 1 || alias[0] !== 7) { return 0; }
              alias[alias.length] = 8;
              if (xs.length !== 2 || xs[1] !== 8) { return 0; }
              let zero: table = {value: -0.0};
              function admitted(x: int): int {
                if (1.0 / number(x) > 0.0) { return 1; }
                return 0;
              }
              return admitted(zero.value);
            }
            """, "invoker-await-write", "oracle", "int");
        check(run.exitCode() == 0 && run.output().equals("INVOKE_OK: 1"),
            "the awaited array write evaluates RIV before the bounds gate,"
                + " publishes no write and appends: " + run.output());
    }

    /**
     * ISSUE-0756 subject {@code public-companion-direct} on the real v1.2
     * public JS surface: the two-module project (the entry imports the
     * companion module) is published through the public {@code deal
     * compile} entry under the v1.2 manifest — the emitted artifact
     * carries the {@code DEAL_V1_3_INT32} selector — and a real node
     * process then requires the published entry and invokes the exported
     * wrappers exactly as the package's exported-wrapper host does:
     * {@code main} first, then the throwing exported {@code run} under
     * try/catch. The escaped companion DEAL runtime error is the
     * independent expectation: code {@code E8001}, a message reporting the
     * int type failure, {@code expected int}, {@code actual string}, the
     * companion origin {@code src/helper.deal} line 3 and the accepted
     * check anchor column; the program streams stay empty and the host
     * exits 0. The host records the observed fields in a private file, so
     * the field comparison is exact and stdout/stderr stay the program's
     * own streams; the same verdict rejects the wrong-field and
     * wrong-origin controls.
     */
    private static void testProductionCompanionDirectNode() throws Exception {
        System.out.println("-- Node: the v1.2 published public-companion-direct"
            + " project through the exported-wrapper host --");
        if (!nodeAvailable) {
            skipNode("production public-companion-direct");
            return;
        }
        Path root = publishCornerCaseProject("public-companion-direct",
            COMPANION_DIRECT_HELPER_SOURCE, COMPANION_DIRECT_MAIN_SOURCE);
        try {
            Path out = root.resolve("out");
            Path artifact = out.resolve("main.js");
            check(Files.isRegularFile(artifact), "the public compile"
                + " publishes the entry artifact: " + artifact);
            if (!Files.isRegularFile(artifact)) {
                return;
            }
            boolean v12Profile = !Files.readString(artifact,
                StandardCharsets.UTF_8).contains("setInt32Mode");
            check(v12Profile, "the published artifact carries no profile"
                + " selector (the signed-32 gate is active from load)");
            Files.writeString(out.resolve("companion_host.js"),
                CORNER_CASE_HOST_SOURCE, StandardCharsets.UTF_8);
            ProductionNodeRun run = runNodeSplit(out, "companion_host.js",
                "companion_error.txt", v12Profile, "sync", "companion_error.txt");
            check(run.exitCode() == 0, "the exported-wrapper host process"
                + " exits 0: exit=" + run.exitCode() + " stderr=<"
                + run.stderr() + ">");
            check(run.stdout().isEmpty(), "the independent stdout of the"
                + " public program is empty: <" + run.stdout() + ">");
            check(run.stderr().isEmpty(), "the independent stderr of the"
                + " public program is empty: <" + run.stderr() + ">");
            CompanionErrorRecord observed =
                CompanionErrorRecord.parse(run.signal());
            Optional<String> verdict = companionDirectMismatch(observed);
            check(verdict.isEmpty(), "the escaped companion error matches"
                + " the independent fields: " + verdict.orElse("match"));
            check(companionDirectMismatch(observed.withCode("E8005"))
                    .isPresent(),
                "the verdict rejects a wrong error code");
            check(companionDirectMismatch(observed.withMessage(
                    "typed value rejected")).isPresent(),
                "the verdict rejects a message that does not report the"
                    + " int type failure");
            check(companionDirectMismatch(observed.withExpected("string"))
                    .isPresent(),
                "the verdict rejects a wrong expected type");
            check(companionDirectMismatch(observed.withActual("int"))
                    .isPresent(),
                "the verdict rejects a wrong actual type");
            check(companionDirectMismatch(observed.withFile(
                    "/tmp/other/src/main.deal")).isPresent(),
                "the verdict rejects a wrong origin file");
            check(companionDirectMismatch(observed.withLine("4"))
                    .isPresent(),
                "the verdict rejects a wrong origin line");
            check(companionDirectMismatch(observed.withColumn("10"))
                    .isPresent(),
                "the verdict rejects the later annotation column 10");
            check(companionDirectMismatch(
                    observed.withFrames("Object.someOther$f"))
                    .isPresent(),
                "the verdict rejects frames without the fail/run functions");
        } finally {
            deleteDir(root);
        }
    }

    /**
     * The public-host capture boundary controls in the node position: the
     * shared bounded-process helper's per-stream 1 MiB cap. A host emitting
     * one byte below the cap and exactly at the cap is a complete,
     * untruncated observation and is accepted with its recorded host signal;
     * one byte past the cap in either stream position is retained only as
     * the bounded prefix plus the helper's truncation marker and is refused
     * by name before any transcript comparison or signal acceptance, so a
     * truncated or over-limit observation can never pass as a production
     * transcript.
     */
    private static void testProductionNodeCaptureBoundary() throws Exception {
        System.out.println("-- Node: the public-host capture boundary (the"
            + " shared 1 MiB per-stream cap) --");
        if (!nodeAvailable) {
            skipNode("public-host capture boundary");
            return;
        }
        Path dir = Files.createTempDirectory(
            "jstest_node_capture_boundary_");
        try {
            int cap = BoundedProcess.STREAM_CAP_BYTES;
            for (int bytes : List.of(cap - 1, cap)) {
                writeNodeCaptureEmitter(dir, bytes, false);
                ProductionNodeRun accepted = runNodeSplit(dir,
                    "capture_emitter.js", true);
                check(accepted.exitCode() == 0, "the node host emitting "
                    + bytes + " bytes exits 0: exit=" + accepted.exitCode()
                    + " stderr=<" + accepted.stderr() + ">");
                check(accepted.stdout().length() == bytes,
                    "the " + bytes + "-byte stdout is captured completely"
                        + " with no truncation: got "
                        + accepted.stdout().length() + " chars");
                check(allFiller(accepted.stdout(), 'z'), "the " + bytes
                    + "-byte stdout is the emitted filler bytes");
                check(accepted.stderr().isEmpty(), "the " + bytes
                    + "-byte host keeps the other stream empty: <"
                    + accepted.stderr() + ">");
                check("SIGNAL\n".equals(accepted.signal()),
                    "the complete " + bytes + "-byte capture accepts the"
                        + " recorded host signal: <" + accepted.signal()
                        + ">");
            }
            for (boolean stderrPosition : List.of(false, true)) {
                writeNodeCaptureEmitter(dir, cap + 1, stderrPosition);
                BoundedProcess.BoundedResult over =
                    BoundedProcess.runCaptured(
                        List.of("node", "capture_emitter.js"), dir);
                String position = stderrPosition ? "stderr" : "stdout";
                check(over.stdoutTruncated() == !stderrPosition
                        && over.stderrTruncated() == stderrPosition,
                    "only the " + position + " position is flagged"
                        + " truncated past the cap: stdout="
                        + over.stdoutTruncated() + ", stderr="
                        + over.stderrTruncated());
                Optional<String> refused = nodeCaptureRefusal(over);
                check(refused.isPresent() && refused.get()
                        .startsWith("JS_PUBLIC_HOST_CAPTURE_TRUNCATED"),
                    "the over-limit " + position + " observation is"
                        + " refused by name before any comparison: "
                        + refused);
                String retained = stderrPosition
                    ? over.stderr() : over.stdout();
                check(retained.length() == cap
                        + BoundedProcess.TRUNCATION_MARKER.length(),
                    "the retained " + position + " is exactly the cap plus"
                        + " the helper's truncation marker: got "
                        + retained.length() + " chars");
                check(retained.endsWith(BoundedProcess.TRUNCATION_MARKER),
                    "the retained " + position + " ends with the helper's"
                        + " truncation marker");
                // The production node helper must refuse the truncated
                // observation even though the child wrote its recorded host
                // signal file: the signal is never accepted from an
                // incomplete capture.
                boolean refusedRun = false;
                try {
                    runNodeSplit(dir, "capture_emitter.js", true);
                } catch (AssertionError hardRefusal) {
                    refusedRun = true;
                    check(hardRefusal.getMessage() != null
                            && hardRefusal.getMessage().startsWith(
                                "JS_PUBLIC_HOST_CAPTURE_TRUNCATED"),
                        "the production node helper refuses the over-limit "
                            + position + " capture by name: "
                            + hardRefusal.getMessage());
                }
                check(refusedRun, "the over-limit " + position
                    + " capture is a hard named refusal, never a partial"
                    + " transcript");
                check(Files.exists(dir.resolve("invoke_result.txt")),
                    "the host signal file of the refused " + position
                        + " capture exists but is not accepted");
            }
        } finally {
            deleteDir(dir);
        }
    }

    /** Writes the boundary emitter: {@code bytes} filler bytes on the
     * selected stream, then the recorded host signal file. */
    private static void writeNodeCaptureEmitter(Path dir, int bytes,
            boolean stderrPosition) throws IOException {
        Files.writeString(dir.resolve("capture_emitter.js"), String.join("\n",
            "\"use strict\";",
            "const fs = require(\"fs\");",
            "process." + (stderrPosition ? "stderr" : "stdout")
                + ".write(\"z\".repeat(" + bytes + "), () => {",
            "  fs.writeFileSync(\"invoke_result.txt\", \"SIGNAL\\n\");",
            "  process.exit(0);",
            "});",
            ""), StandardCharsets.UTF_8);
    }

    /** Whether every character of one captured stream is the filler. */
    private static boolean allFiller(String captured, char expected) {
        for (int i = 0; i < captured.length(); i++) {
            if (captured.charAt(i) != expected) {
                return false;
            }
        }
        return true;
    }

    /**
     * The published {@code public-companion-direct} sources (the external
     * package's bytes): the companion module performs the type-failing
     * read on line 3 and the entry module re-exports {@code run} over it.
     */
    private static final String COMPANION_DIRECT_HELPER_SOURCE =
        String.join("\n",
            "export function fail(): int {",
            "  let t: table = {value: \"bad\"};",
            "  let x: int = t.value;",
            "  return x;",
            "}",
            "");

    private static final String COMPANION_DIRECT_MAIN_SOURCE =
        String.join("\n",
            "import * as c from \"std/console\";",
            "import * as helper from \"./helper\";",
            "export function main(): null {return null;}",
            "export function run(): int {return helper.fail();}",
            "");

    /** One observed public-companion-direct error field record. */
    private record CompanionErrorRecord(String code, String message,
            String file, String line, String column, String expected,
            String actual, String frames) {

        static CompanionErrorRecord parse(String transcript) {
            String code = null;
            String message = null;
            String file = null;
            String line = null;
            String column = null;
            String expected = null;
            String actual = null;
            String frames = null;
            for (String field : transcript.split("\n")) {
                int split = field.indexOf('=');
                if (split < 0) {
                    continue;
                }
                String key = field.substring(0, split);
                String value = field.substring(split + 1);
                switch (key) {
                    case "code" -> code = value;
                    case "message" -> message = value;
                    case "file" -> file = value;
                    case "line" -> line = value;
                    case "column" -> column = value;
                    case "expected" -> expected = value;
                    case "actual" -> actual = value;
                    case "frames" -> frames = value;
                    default -> { }
                }
            }
            return new CompanionErrorRecord(code, message, file, line,
                column, expected, actual, frames);
        }

        CompanionErrorRecord withCode(String value) {
            return new CompanionErrorRecord(value, message, file, line,
                column, expected, actual, frames);
        }

        CompanionErrorRecord withMessage(String value) {
            return new CompanionErrorRecord(code, value, file, line,
                column, expected, actual, frames);
        }

        CompanionErrorRecord withFile(String value) {
            return new CompanionErrorRecord(code, message, value, line,
                column, expected, actual, frames);
        }

        CompanionErrorRecord withLine(String value) {
            return new CompanionErrorRecord(code, message, file, value,
                column, expected, actual, frames);
        }

        CompanionErrorRecord withColumn(String value) {
            return new CompanionErrorRecord(code, message, file, line,
                value, expected, actual, frames);
        }

        CompanionErrorRecord withExpected(String value) {
            return new CompanionErrorRecord(code, message, file, line,
                column, value, actual, frames);
        }

        CompanionErrorRecord withActual(String value) {
            return new CompanionErrorRecord(code, message, file, line,
                column, expected, value, frames);
        }

        CompanionErrorRecord withFrames(String value) {
            return new CompanionErrorRecord(code, message, file, line,
                column, expected, actual, value);
        }
    }

    /**
     * The independent public-companion-direct verdict over the observed
     * public error record: the exact prescribed fields and the companion
     * origin. The message is the exact typed-kind arm text, the column is
     * the contextual read check the lowering selects at the companion
     * read ({@code t.value}, column 16) — never the later annotation
     * check at column 10. The same function is the wrong-observation
     * control.
     */
    private static Optional<String> companionDirectMismatch(
            CompanionErrorRecord observed) {
        if (!"E8001".equals(observed.code())) {
            return Optional.of("error code must be E8001, got "
                + observed.code());
        }
        if (!"expected int".equals(observed.message())) {
            return Optional.of("the message must be the typed-kind arm's"
                + " exact text 'expected int', got <" + observed.message()
                + ">");
        }
        if (!"int".equals(observed.expected())) {
            return Optional.of("expected must be int, got "
                + observed.expected());
        }
        if (!"string".equals(observed.actual())) {
            return Optional.of("actual must be string, got "
                + observed.actual());
        }
        String file = observed.file() == null ? ""
            : observed.file().replace('\\', '/');
        if (!file.endsWith("/src/helper.deal")) {
            return Optional.of("the origin file must be the companion"
                + " source src/helper.deal, got " + observed.file());
        }
        if (!"3".equals(observed.line())) {
            return Optional.of("the origin line must be 3, got "
                + observed.line());
        }
        if (!"16".equals(observed.column())) {
            return Optional.of("the origin column must be the contextual"
                + " read check anchor 16, got " + observed.column());
        }
        String frames = observed.frames() == null ? "" : observed.frames();
        if (!frames.contains("fail$f") || !frames.contains("run$f")) {
            return Optional.of("the frames must name the generated helper"
                + " fail and entry run functions (fail$f/run$f), got <"
                + observed.frames() + ">");
        }
        return Optional.empty();
    }

    /**
     * Publishes one subject through the public {@code deal compile} entry
     * and drives it through the production JS host ABI in a real node
     * process: the runner requires the published entry and the deployed
     * runtime, invokes the selected export once through
     * {@code $rt.invokeAsyncExport}, and records the result signal in
     * {@code invoke_result.txt} — it writes nothing else, so stdout stays
     * the subject's own output and stderr stays empty unless the host
     * fails. Streams are captured separately through private files.
     */
    private static ProductionNodeRun runProductionInvokerNode(String source,
            String name, String exportName, String returnDescriptor)
            throws Exception {
        Path root = Files.createTempDirectory("jstest_production_invoker_");
        try {
            Files.createDirectories(root.resolve("src"));
            Files.writeString(root.resolve("deal.json"),
                "{\"languageVersion\": \"1.3\","
                    + " \"moduleRoots\": [\"src\"],"
                    + " \"output\": \"out\","
                    + " \"backend\": \"js\"}\n",
                StandardCharsets.UTF_8);
            Path entry = root.resolve("src/main.deal");
            Files.writeString(entry, source, StandardCharsets.UTF_8);
            int exit = deal.Main.run(new String[] {"compile",
                entry.toAbsolutePath().toString()});
            if (exit != 0) {
                throw new RuntimeException("the public deal compile of "
                    + name + " failed with exit " + exit);
            }
            Path out = root.resolve("out");
            Path artifact = out.resolve("main.js");
            if (!Files.isRegularFile(artifact)) {
                throw new RuntimeException("the public compile published no"
                    + " entry artifact for " + name + ": " + artifact);
            }
            boolean v12Profile = !Files.readString(artifact,
                StandardCharsets.UTF_8).contains("setInt32Mode");
            Files.writeString(out.resolve("invoker_runner.js"),
                PRODUCTION_INVOKER_RUNNER_SOURCE
                    .replace("EXPORT_NAME", exportName)
                    .replace("RETURN_DESCRIPTOR", returnDescriptor),
                StandardCharsets.UTF_8);
            return runNodeSplit(out, "invoker_runner.js", v12Profile);
        } finally {
            deleteDir(root);
        }
    }

    /**
     * The production-invoker runner of the published-artifact drive: the
     * subject's output is the only stdout write, the invocation result is
     * recorded in {@code invoke_result.txt}, and a non-value, a non-null
     * completion, or a thrown invoker failure exits non-zero after leaving
     * the diagnostic on stderr.
     */
    private static final String PRODUCTION_INVOKER_RUNNER_SOURCE =
        String.join("\n",
            "\"use strict\";",
            "const fs = require(\"fs\");",
            "const $rt = require(\"./deal/runtime\");",
            "const $entry = require(\"./main\");",
            "$rt.invokeAsyncExport($entry, \"EXPORT_NAME\", \"RETURN_DESCRIPTOR\")",
            "  .then((r) => {",
            "    let signal;",
            "    if (r.$ok !== true) {",
            "      signal = \"INVOKE_NOT_OK: \" + JSON.stringify(r);",
            "    } else if (r.$value !== null) {",
            "      signal = \"INVOKE_NOT_NULL: \" + JSON.stringify(r.$value);",
            "    } else {",
            "      signal = \"INVOKE_OK: null\";",
            "    }",
            "    fs.writeFileSync(\"invoke_result.txt\", signal + \"\\n\");",
            "    if (!signal.startsWith(\"INVOKE_OK:\")) { process.exit(2); }",
            "  }, (err) => {",
            "    fs.writeFileSync(\"invoke_result.txt\",",
            "      \"INVOKE_THREW: \" + err + \"\\n\");",
            "    process.stderr.write(\"INVOKER_THREW: \" + err + \"\\n\");",
            "    process.exit(3);",
            "  });",
            "");

    /** One published-artifact node run with separate exact streams. */
    private record ProductionNodeRun(int exitCode, String stdout,
                                     String stderr, String signal,
                                     boolean v12Profile) {}

    /** Executes node in {@code dir} with separately captured streams and
     * the production invoker's result signal file. */
    private static ProductionNodeRun runNodeSplit(Path dir,
            String scriptFile, boolean v12Profile) throws Exception {
        return runNodeSplit(dir, scriptFile, "invoke_result.txt",
            v12Profile);
    }

    /**
     * Executes node in {@code dir} through the landed shared bounded-process
     * helper ({@code deal.test.BoundedProcess}): the canonical deadline, the
     * per-stream 1 MiB cap, and the owned process-group release. A truncated
     * or over-limit observation is refused by name before the streams are
     * compared or the recorded host signal is accepted, so a retained
     * prefix or truncation marker is never read as a production transcript.
     */
    private static ProductionNodeRun runNodeSplit(Path dir,
            String scriptFile, String signalFile, boolean v12Profile,
            String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("node", scriptFile));
        command.addAll(List.of(args));
        BoundedProcess.BoundedResult capture = BoundedProcess.runCaptured(
            command, dir);
        Optional<String> refused = nodeCaptureRefusal(capture);
        if (refused.isPresent()) {
            throw new AssertionError(refused.get());
        }
        Path signal = dir.resolve(signalFile);
        return new ProductionNodeRun(capture.exitCode(), capture.stdout(),
            capture.stderr(),
            Files.exists(signal)
                ? Files.readString(signal, StandardCharsets.UTF_8)
                : "",
            v12Profile);
    }

    /**
     * The named refusal of a node capture that passed the shared helper's
     * per-stream bound: the retained prefix plus the helper's truncation
     * marker is diagnostics only and is never compared as a production
     * transcript, nor is the recorded host signal accepted from it.
     */
    private static Optional<String> nodeCaptureRefusal(
            BoundedProcess.BoundedResult capture) {
        if (!capture.stdoutTruncated() && !capture.stderrTruncated()) {
            return Optional.empty();
        }
        return Optional.of("JS_PUBLIC_HOST_CAPTURE_TRUNCATED: the node"
            + " observation passed the shared helper's "
            + BoundedProcess.STREAM_CAP_BYTES + "-byte per-stream bound"
            + " (stdout " + capture.stdoutTruncated() + ", stderr "
            + capture.stderrTruncated() + "); a truncated observation is"
            + " never compared as a transcript or accepted as a host"
            + " signal");
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
        System.out.println("-- Strict manifest backend field: js accepted --");

        StrictManifestParser.StrictManifestParseResult ok =
            StrictManifestParser.parse("deal.json",
                "{\"languageVersion\": \"1.3\", \"backend\": \"js\"}");
        check(ok.manifest() != null && ok.failure() == null,
            "deal.json backend js parses strictly: " + ok.failure());
        if (ok.manifest() != null) {
            check("js".equals(ok.manifest().backend()),
                "the strict manifest publishes backend 'js'");
        }

        for (String bad : new String[] {
                "{\"languageVersion\": \"1.3\", \"backend\": \"  Js \"}",
                "{\"languageVersion\": \"1.3\", \"backend\": \"wasm\"}"}) {
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

    private static void testCliBackendFlagJsDefaultOutput() throws Exception {
        System.out.println("-- CLI --backend js: production alias, default build/js --");

        Path proj = Files.createTempDirectory("jstest_cli_");
        Files.createDirectories(proj.resolve("src"));
        // A valid strict manifest without a backend field: the CLI
        // alias is the backend-selection authority.
        Files.writeString(proj.resolve("deal.json"),
            "{\"languageVersion\": \"1.3\", \"moduleRoots\": [\"src\"]}\n",
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
            import * as console from "std/console";
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
            + "$rt.makeClass(\"Error\", \"Error\", $ErrorDefaults, provided, "
            + "$file, $line, $column);"),
            "the header-synthesized Error$new closure (the canonical builtin atom \"Error\")");
        check(js.contains("const Error$meta = { $kind: \"class\", "
            + "$classname: \"Error\" };"),
            "the header-synthesized Error$meta META pair "
                + "(the canonical builtin atom \"Error\")");
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
            import * as strings from "std/string";
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
            import * as a from "./sub/util";
            import * as b from "../lib";
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
        check(js.contains("Holder$plan = [{ name: \"static\", "
            + "descriptor: \"int\", optional: false, evaluator: () => 0 }];"),
            "the synthesized plan list keys the field by the raw name");
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
        check(js.contains("$rt.checkInt(a, \"jstest-wrappers.deal\", 2, 17);"),
            "entry parameter check carries the parameter's declared type span");
        check(js.contains("$rt.checkInt(b, \"jstest-wrappers.deal\", 2, 25);"),
            "second parameter check carries its own declared type span");
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
        check(js.contains("$rt.checkString(file, \"jstest-spancollide.deal\", 1, 18);"),
            "parameter checks carry the parameter's declared type span");
        check(js.contains("$rt.checkInt(line, \"jstest-spancollide.deal\", 1, 32);"),
            "the line parameter check carries its own declared type span");
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
        check(js.contains("Point$plan = [{ name: \"x\", descriptor: \"int\", "
            + "optional: false, evaluator: () => 10 },{ name: \"nick\", "
            + "descriptor: \"?string\", optional: true }];"),
            "Point$plan carries the synthesized plan list (required "
                + "evaluator, optional entry with no evaluator)");
        check(js.contains("Point$new = (provided, $file, $line, $column) => "
            + "$rt.classPlan(\"@Main/Point\", Point$plan, provided, $file, "
            + "$line, $column);"),
            "Point$new constructs through $rt.classPlan over the plan list");
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
        check(js.contains("let U$new; let U$meta; let U$plan; "
            + "let U$fromJson; let U$toJson; let U$fields;"),
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
            "{\"languageVersion\": \"1.3\", \"backend\": \"js\"}");
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
            import * as A from "./lib_a";

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
            import * as B from "./lib_b";
            import * as console from "std/console";
            export function main(): null {
              let w: B.Wrap = B.make(7);
              let json: string = B.Wrap$toJson(w);
              let w2: B.Wrap | null = B.Wrap$fromJson(json);
              if (w2 === null) {
                console.log("xmod-jsonable-null");
                return null;
              } else {
                if (w2.inner.v !== 7) { console.log("xmod-jsonable-inner-mismatch"); return null; }
                if (w2.children[0].v !== 8) { console.log("xmod-jsonable-array-mismatch"); return null; }
                console.log("xmod-jsonable-ok");
                return null;
              }
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

    private static String nestedClassShadowSubject() {
        return """
            class Outer { v: int = 1; }
            export function test(): int {
              let a: Outer = {};
              {
                class Outer { w: string = "s"; }
                a = { v: 9 };
                let b: Outer = {};
                if (b.w !== "s") { return 0; }
              }
              if (a.v !== 9) { return 0; }
              return 1;
            }
            """;
    }

    private static void assertLocalClassRejected(String source, String tag) {
        Frontend f = compileFrontend(source, tag + ".deal");
        String expected = source.contains("export class") ? "E1050" : "E1057";
        check(f.errors().stream().anyMatch(d -> d.code().equals(expected)),
            tag + ": v1.3 rejects local class/export placement: " + f.errors());
        check(f.errors().stream().noneMatch(d -> d.code().equals("E3019")),
            tag + ": placement rejection precedes nominal typing");
    }

    private static void testNestedClassEmissionPins(PreparedJs nestedExport,
                                                   PreparedJs nestedShadow) {
        System.out.println("-- Module class artifacts and removed local-class placement --");

        // Preserve removed local placement as a frontend negative; the legal
        // module equivalent retains default-plan, factory and nominal META pins.
        assertLocalClassRejected("""
            export function test(): int {
              let seed: int = 40;
              class Inner { v: int = seed + 2; }
              return 1;
            }
            """, "nestedclass");
        JsBackend.JsCodegenResult nested = generate("""
            class Seed { value: int = 40; }
            class Inner { v: int = function(): int { let seed: Seed = {}; return seed.value + 2; }(); }
            export function test(): int { return 1; }
            """, "moduleclass");
        check(nested != null && !nested.hasErrors(),
            "nested class declaration generates with no diagnostics: "
                + (nested == null ? "<null>" : nested.diagnostics()));
        if (nested == null || nested.hasErrors()) return;

        String js = nested.source();
        int wrapper = js.indexOf("test$f(");
        int predeclare = js.indexOf("let Inner$new; let Inner$meta;");
        int assign = js.indexOf("Inner$new = (provided, $file, $line, $column)");
        check(wrapper >= 0 && predeclare >= 0 && assign > predeclare,
            "the module class predeclares before its factory assignment");
        check(js.contains("let Inner$new; let Inner$meta;"),
            "the scope-local predeclare pair binds the artifact names");
        check(js.contains("Inner$plan = [{ name: \"v\", descriptor: \"int\", "
                + "optional: false, evaluator: () => "),
            "the declaration-site plan list carries the canonical identity "
                + "and an evaluator closing over seed");
        check(js.contains("Inner$new = (provided, $file, $line, $column) => "
                + "$rt.classPlan(\"@Main/Inner\", Inner$plan, provided, "
                + "$file, $line, $column);"),
            "the declaration-site construction closure constructs through "
                + "$rt.classPlan over the scope-local plan");
        check(js.contains("Inner$meta = { $kind: \"class\", "
                + "$classname: \"@Main/Inner\" };"),
            "the inline META pair carries the canonical identity text");

        // A legal module default invokes an ordinary function wrapper.
        // Its removed local-class counterpart remains a separate negative.
        assertLocalClassRejected("""
            export function test(): int {
              let seed: int = 40;
              function next(): int { return seed + 2; }
              class Inner { v: int = next(); }
              return 1;
            }
            """, "nestedclass-func");
        JsBackend.JsCodegenResult func = generate("""
            function next(): int { let seed: int = 40; return seed + 2; }
            class Inner { v: int = next(); }
            export function test(): int { return 1; }
            """, "moduleclass-func");
        check(func != null && !func.hasErrors(),
            "nested class with a scope-local default function generates: "
                + (func == null ? "<null>" : func.diagnostics()));
        if (func != null && !func.hasErrors()) {
            check(func.source().contains("evaluator: () => next.$f("),
                "the plan evaluator calls the ordinary function wrapper");
        }

        // A module class exports META and hidden construction under the
        // ordinary raw-key own-property-safe path.
        JsBackend.JsCodegenResult exported = nestedExport.emission();
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
            check(metaExport > 0 && testExport > 0,
                "the module class META and test function are both exported");
            check(xjs.contains("$rt.setProp($exports, \"Outer\", Outer$meta);")
                    && xjs.contains(
                        "$rt.setProp($exports, \"Outer$new\", Outer$new);"),
                "the exported nested class exports C$meta and the hidden "
                    + "C$new under the ordinary raw keys");
        }

        // Neither export nor @jsonable permits a local class in v1.3.
        assertLocalClassRejected("""
            export function test(): int {
              // @jsonable
              export class Outer { x: int = 1; }
              return 1;
            }
            """, "nestedclass-jsonable");
        assertLocalClassRejected("""
            export function test(): int {
              export class Outer { x: int = 1; }
              return 1;
            }
            """, "nestedclass-export");
        assertLocalClassRejected(nestedClassShadowSubject(), "nestedclass-shadow-base");

        // Distinct legal module classes retain separate factory/layout identity;
        // a target-context assignment constructs through its annotated class.
        JsBackend.JsCodegenResult shadowBase = nestedShadow.emission();
        check(shadowBase != null && !shadowBase.hasErrors(),
            "a shadowing nested class generates with no diagnostics: "
                + (shadowBase == null ? "<null>" : shadowBase.diagnostics()));
        if (shadowBase != null && !shadowBase.hasErrors()) {
            String sjs = shadowBase.source();
            check(sjs.contains("let Alternate$new; let Alternate$meta;"),
                "the second module class has its own unambiguous artifacts");
            check(sjs.contains("Outer$new({ [\"v\"]: 9 }"),
                "the target-context literal constructs through the shadowed "
                    + "declaration's factory");
            check(sjs.contains("Alternate$new({ },"),
                "the second module class constructs through its own factory");
            check(!sjs.contains("Alternate$new({ [\"v\"]: 9 }"),
                "the outer-typed assignment never names the other factory");
        }
    }

    private static void testNestedClassNodeSemantics(PreparedJs nestedExport,
                                                    PreparedJs nestedShadow)
            throws Exception {
        System.out.println("-- Node: module defaults, ordinary captures and per-construction evaluation --");
        if (!nodeAvailable) { skipNode("nested-class defaults scope"); return; }

        // Keep the original local-class subject as a negative. The legal
        // module default creates a fresh ordinary captured binding each time:
        // one instance advances 42 -> 102 while the other still starts at 42.
        assertLocalClassRejected("""
            export function test(): int {
              let seed: int = 40;
              function next(): int { return seed + 2; }
              class Inner {
                v: int = next();
              }
              let a: Inner = { };
              seed = 100;
              let b: Inner = { };
              if (a.v !== 42) { return 0; }
              if (b.v !== 102) { return 0; }
              return 1;
            }
            """, "nestedclass-node");
        NodeResult run = runDealNode("""
            class Inner {
              next: () => int = function(): () => int {
                let seed: int = 40;
                return function(): int { let value: int = seed + 2; seed = 100; return value; };
              }();
            }
            export function test(): int {
              let a: Inner = { };
              let b: Inner = { };
              if (a.next() !== 42) { return 0; }
              if (a.next() !== 102) { return 0; }
              if (b.next() !== 42) { return 0; }
              return 1;
            }
            """, "moduleclass-node");
        check(run.exitCode() == 0 && run.output().equals("1"),
            "per-construction ordinary captures stay fresh (42, 102; other instance 42): " + run.output() + " (exit "
                + run.exitCode() + ")");

        // Exported module classes retain public META and hidden construction
        // keys, with canonical module-qualified identity.
        Frontend f = nestedExport.frontend();
        check(f.errors().isEmpty(),
            "exported nested class frontend clean: " + f.errors());
        if (!f.errors().isEmpty()) return;
        JsBackend.JsCodegenResult res = nestedExport.emission();
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

        // Removed local shadowing rejects; distinct module classes remain legal
        // when constructed inside ordinary function/block scopes.
        assertLocalClassRejected("""
            class Shadowed { v: int = 1; }
            export function test(): int {
              class Shadowed { w: int = 2; }
              let s: Shadowed = { };
              if (s.w !== 2) { return 0; }
              return 1;
            }
            """, "nestedclass-shadow");
        NodeResult shadow = runDealNode("""
            class Shadowed { v: int = 1; }
            class Alternate { w: int = 2; }
            export function test(): int {
              let s: Alternate = { };
              if (s.w !== 2) { return 0; }
              return 1;
            }
            """, "moduleclass-distinct");
        check(shadow.exitCode() == 0 && shadow.output().equals("1"),
            "a distinct module class constructs inside a function: " + shadow.output()
                + " (exit " + shadow.exitCode() + ")");

        // Target-context construction and the other module class retain
        // independent layouts through the same block.
        NodeResult shadowConstruct = runDealArtifact(nestedShadow);
        check(shadowConstruct.exitCode() == 0
                && shadowConstruct.output().equals("1"),
            "a target-context construction under a shadowing declaration "
                + "runs the shadowed declaration's factory: "
                + shadowConstruct.output() + " (exit "
                + shadowConstruct.exitCode() + ")");
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
        check(js.contains("P$plan = [{ name: \"__proto__\", "
            + "descriptor: \"int\", optional: true }];"),
            "the synthesized plan spells the __proto__ field as a plan entry");
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

        Map<String, Map<String, Type>> modules = new LinkedHashMap<>();
        modules.put("./proto_lib", Map.of("__proto__",
            Types.func(List.of(), Type.Int.INSTANCE)));
        Frontend f = compileFrontend("""
            import * as pl from "./proto_lib";
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
        check(js.contains("if ($idx < 0 || $idx > $rt.arrayLength($arr)) { "
            + "$rt.fail(\"E8002\", \"array index out of bounds\", "
            + "\"jstest-delemit.deal\", 3, 10); }"),
            "the E8002 bounds gate precedes the write");
        check(js.contains("if ($idx !== $rt.arrayLength($arr)) { "
            + "$rt.arrayDelete($arr, $idx); }"),
            "the nil-out commit is guarded by $idx !== $rt.arrayLength($arr) "
                + "(no write emitted at i === length)");
        check(!js.replace("if ($idx !== $rt.arrayLength($arr)) { "
            + "$rt.arrayDelete($arr, $idx); }", "").contains("$rt.arrayDelete("),
            "the only element write is the guarded nil-out");
        check(js.contains("$rt.arrayDelete($arr, $idx);"),
            "the nil-equivalent commit routes through the carrier-agnostic "
                + "runtime member");
        check(js.contains("$rt.arrayLength(xs)"),
            "the array .length read routes through $rt.arrayLength");
        check(!js.replace("$rt.undefined", "").contains("undefined"),
            "no bare undefined spelling anywhere in the artifact");
    }

    /**
     * The v1.3 array-read emission pin: the IIFE binds the receiver before
     * the checked index, enforces the strict bounds gate E8002 "array index
     * out of bounds" for {@code < 0} and {@code >= length} at the read
     * expression, and only then reads the element. A read that evaluated
     * the index first or gated only the negative bound fails these exact
     * assertions.
     */
    private static void testArrayReadBoundsEmission() {
        System.out.println("-- Array-read bounds emission pin --");

        JsBackend.JsCodegenResult res = generate("""
            function receiver(xs: int[]): int[] {
              return xs;
            }
            export function test(): int {
              let xs: int[] = [1, 2, 3];
              return receiver(xs)[1];
            }
            """, "readbounds");
        check(res != null && !res.hasErrors(), "array-read bounds codegen clean: "
            + (res == null ? "<null>" : res.diagnostics()));
        if (res == null || res.hasErrors()) return;

        String js = res.source();
        int arrPos = js.indexOf("const $arr = ");
        int idxPos = js.indexOf("const $idx = $rt.checkInt(");
        check(arrPos >= 0 && idxPos > arrPos,
            "the read binds the receiver before the checked index");
        check(js.contains("if ($idx < 0 || $idx >= $rt.arrayLength($arr)) { "
                + "$rt.fail(\"E8002\", \"array index out of bounds\", "),
            "the read gates both bounds with E8002 'array index out of bounds'");
        check(js.contains("return $rt.arrayAt($arr, $idx); })()"),
            "the read returns the carrier-agnostic position only after the "
                + "bounds gate");
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
            check(plain.source().contains("$rt.arrayDelete($arr, $idx);"),
                "the delete commit routes through $rt.arrayDelete");
            check(plain.source().contains("const $el = $rt.arrayAt($iter, $i);"),
                "the for-of element read routes through $rt.arrayAt");
            check(plain.source().contains("if ($el === $rt.undefined) { break; }"),
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
        PreparedJs shadowPrepared = prepareJs(shadowTestSource(), "hygiene-shadow");
        JsBackend.JsCodegenResult shadow = shadowPrepared.emission();
        check(shadow != null && !shadow.hasErrors(),
            "shadow hygiene codegen clean: "
                + (shadow == null ? "<null>" : shadow.diagnostics()));
        if (shadow != null && !shadow.hasErrors()) {
            String js = shadow.source();
            check(js.contains("let undefined = $rt.checkInt(7, "),
                "the user binding named undefined emits as declared");
            check(js.contains("$rt.arrayDelete($arr, $idx);"),
                "the delete commit routes through $rt.arrayDelete despite the binding");
            check(js.contains("const $el = $rt.arrayAt($iter, $i);"),
                "the for-of element read routes through $rt.arrayAt despite the binding");
            check(js.contains("if ($el === $rt.undefined) { break; }"),
                "the for-of nil-stop spells $rt.undefined despite the binding");
            check(!js.contains("= undefined;") && !js.contains("=== undefined")
                    && !js.contains("!== undefined"),
                "no write/compare site spells the bare host-global");
            if (nodeAvailable) {
                try {
                    NodeResult run = runDealArtifact(shadowPrepared);
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
        System.out.println("-- Unsupported constructs → E6000/E6006 --");

        String externDetailText = "JavaScript backend: @extern-c imports "
            + "are not supported (FFI_UNSUPPORTED_BACKEND, "
            + "ISSUE-0169 skeleton)";

        JsBackend.JsCodegenResult extern = generateWithExternC("""
            import * as ffi from "myffi";
            export function test(): int { return 1; }
            """, "rej-extern", "Main", Map.of(),
            Map.of("myffi", new HostModuleDeclarations(Map.of(), Map.of())),
            Set.of("myffi"), false);
        check(extern != null && extern.hasErrors(), "@extern-c import rejected");
        if (extern != null) {
            check(extern.diagnostics().stream().anyMatch(d ->
                    "E6006".equals(d.code())
                        && "error".equals(d.severity())
                        && externDetailText.equals(d.message())
                        && d.range().startLine() == 1),
                "@extern-c rejection is E6006 with the exact current detail "
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
            import * as h from "./hostmod";
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
                    + " }}, " + q + "./hostmod" + q
                    + ", " + q + "jstest-host-abi-fn.deal" + q + ", 1, 1)"),
                "the host binding renders the loadHost call with the raw "
                    + "specifier, the canonical function declared map, and "
                    + "the import-site span: " + js);
            check(js.contains("h.hostFn.$f(1, "),
                "host member calls route through the wrapper $f");
        }

        // (1b) The nested-module relative rule: a nested emitting module
        // requires the bare host specifier with the ../ prefix — the
        // same project-import relative rule, applied to the raw
        // specifier verbatim.
        Frontend nestedFrontend = compileFrontend("""
            import * as h from "host/mod";
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
            import * as h from "./hostmod";
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
                        StubModuleResolver.buildJsRunner(deferFrontend.program()));
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
            import * as cfg from "./hostmod";
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
            Set.of(), false, index, index.moduleIdentityLookup(), null);
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

    private static void testHostAbiOrchestratorNode() throws Exception {
        System.out.println("-- Orchestrator: host ABI end-to-end under node (ISSUE-0328) --");

        writeFile("hostjs_proj/deal.json",
            "{\"languageVersion\": \"1.3\", \"backend\": \"js\",\n"
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
            import * as cfg from "host/cfg";
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
                const $rt = require("../deal/runtime");
                module.exports = {
                  Endpoint: { $kind: "class", $classname: "@$external/host/cfg/Endpoint" },
                  Endpoint_defaults: { path: "/" },
                  ServerConfig: { $kind: "class", $classname: "@$external/host/cfg/ServerConfig" },
                  ServerConfig_defaults: { port: 8080, endpoint: $rt.MISSING, tags: $rt.MISSING, note: $rt.MISSING },
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

    // =========================================================================
    // ISSUE-0797: occurrence-owned host imports, name-sorted reads, the
    // unlocated raw-shape guard, and live-position traversal
    // =========================================================================

    /**
     * The shared compatibility prerequisite's selected behaviors on the real
     * JS chain: (1) the deployed runtime's per-call typed surfaces — fresh
     * wrappers and synthesized {@code C$new}/{@code C$fields}, retained raw
     * META/default references, identical same-import reads, unequal
     * cross-import functions, declaration-order first-failure stop, and the
     * complete unlocated raw-shape E8011 tuple — probed through the deployed
     * {@code $rt} surface; (2) the full orchestrator project with two
     * same-file aliases and one cross-unit import: the raw Node require runs
     * exactly once, the declared reads follow ascending export-name order
     * (the declaration file names them out of order), and the typed calls
     * cross both units; (3) the array for-of live-position traversal
     * (deleted-slot stop, appended-element visit, one iterable evaluation)
     * through a real emitted artifact under node.
     */
    private static void testHostOccurrenceAndTraversalNode() throws Exception {
        System.out.println("-- ISSUE-0797: occurrence-owned host imports, name-sorted "
            + "declared reads, the unlocated raw-shape guard, and the live-position "
            + "array traversal --");

        if (!nodeAvailable) {
            skipNode("ISSUE-0797 host-occurrence and traversal node drive");
            return;
        }

        // (0) The shared ascending-name declared-export selection: the
        // declaration-ordered provider map stays untouched while the
        // selected view is export-name-sorted (the JS read order).
        Map<String, Type> declared = new java.util.LinkedHashMap<>();
        declared.put("zeta", new Type.Func(List.of(), Type.Int.INSTANCE));
        declared.put("alpha", new Type.Func(List.of(), Type.Int.INSTANCE));
        Map<String, Type> selected =
            deal.semantic.HostDeclarationSurface.sortedByName(declared);
        check(List.of("alpha", "zeta").equals(List.copyOf(selected.keySet())),
            "the shared ascending-name selection sorts the declared exports by "
                + "Java String name: " + selected.keySet());
        check(List.of("zeta", "alpha").equals(List.copyOf(declared.keySet())),
            "the declaration-ordered provider fact is not reordered in place: "
                + declared.keySet());

        // (1) The deployed runtime surface: two loadHost calls over one raw
        // export table model two source HOST imports of one resolved file.
        String probe = String.join("\n",
            "\"use strict\";",
            "const $rt = require(\"./deal/runtime\");",
            "function say(s) { process.stdout.write(s + \"\\n\"); }",
            "const decl = { alpha: { $k: \"function\", $d: \"()->int\" },",
            "  zeta: { $k: \"function\", $d: \"()->int\" } };",
            "const order = [];",
            "const raw = {};",
            "Object.defineProperty(raw, \"alpha\", { enumerable: true,",
            "  get: function () { order.push(\"alpha\"); return function () { return 7; }; } });",
            "Object.defineProperty(raw, \"zeta\", { enumerable: true,",
            "  get: function () { order.push(\"zeta\"); return function () { return 9; }; } });",
            "const s1 = $rt.loadHost(raw, decl, \"host/echo\", \"main.deal\", 1, 1);",
            "const s2 = $rt.loadHost(raw, decl, \"host/echo\", \"main.deal\", 2, 1);",
            "say(\"read-order=\" + order.join(\",\"));",
            "say(\"same-import-identity=\" + (s1.alpha === s1.alpha));",
            "say(\"cross-import-inequality=\" + (s1.alpha !== s2.alpha));",
            "say(\"typed-call=\" + s1.alpha.$f() + s2.zeta.$f());",
            "const rawClass = { $kind: \"class\", $classname: \"@$external/host/echo/Echo\" };",
            "const rawClassDefaults = { v: 1 };",
            "const classRaw = { Echo: rawClass, Echo_defaults: rawClassDefaults };",
            "const classDecl = { Echo: { $k: \"class\",",
            "  $d: \"@$external/host/echo/Echo\", $fields: [] } };",
            "const c1 = $rt.loadHost(classRaw, classDecl, \"host/echo\", \"main.deal\", 1, 1);",
            "const c2 = $rt.loadHost(classRaw, classDecl, \"host/echo\", \"main.deal\", 2, 1);",
            "say(\"meta-identity=\" + (c1.Echo === rawClass && c2.Echo === rawClass));",
            "say(\"fresh-new=\" + (c1.Echo$new !== c2.Echo$new));",
            "say(\"fresh-fields=\" + (c1.Echo$fields !== c2.Echo$fields));",
            // The pre-wrapped raw export shape ($rt.function's
            // {$kind, $sig, $f} object — the DEAL-compiled-module
            // producer): two source imports of one resolved file own
            // fresh unequal typed wrappers, repeated same-import reads
            // are identical, the raw wrapper object is never cloned, and
            // a $sig mismatch fails E8011 at the import origin.
            "const pwRaw = {};",
            "const pwFn = function () { return 5; };",
            "const pwExported = $rt.function(\"()->int\", pwFn);",
            "pwRaw.pw = pwExported;",
            "const pwDecl = { pw: { $k: \"function\", $d: \"()->int\" } };",
            "const pw1 = $rt.loadHost(pwRaw, pwDecl, \"host/echo\", \"main.deal\", 1, 1);",
            "const pw2 = $rt.loadHost(pwRaw, pwDecl, \"host/echo\", \"main.deal\", 2, 1);",
            "say(\"pw-same-import-identity=\" + (pw1.pw === pw1.pw));",
            "say(\"pw-cross-import-inequality=\" + (pw1.pw !== pw2.pw));",
            "say(\"pw-typed-call=\" + pw1.pw.$f() + pw2.pw.$f());",
            "say(\"pw-raw-non-cloning=\" + (pwRaw.pw === pwExported",
            "  && pw1.pw !== pwExported && pw2.pw !== pwExported));",
            "say(\"pw-signature=\" + pw1.pw.$sig + \",\" + pw2.pw.$sig);",
            // The re-wrapped $f keeps the call-time enforcement: a
            // matching $sig with a wrong-kind return still fails E8010.
            "try {",
            "  const pwJunk = $rt.loadHost({ pw: $rt.function(\"()->int\",",
            "    function () { return \"junk\"; }) }, pwDecl,",
            "    \"host/echo\", \"main.deal\", 1, 1);",
            "  pwJunk.pw.$f();",
            "  say(\"pw-enforce=none\");",
            "} catch (e) {",
            "  say(\"pw-enforce=\" + e.code + \"|\" + e.message + \"|\" + e.expected",
            "    + \"|\" + e.actual);",
            "}",
            "try {",
            "  $rt.loadHost({ pw: $rt.function(\"()->string\", pwFn) }, pwDecl,",
            "    \"host/echo\", \"main.deal\", 1, 1);",
            "  say(\"pw-mismatch=none\");",
            "} catch (e) {",
            "  say(\"pw-mismatch=\" + e.code + \"|\" + e.message + \"|\" + e.expected",
            "    + \"|\" + e.actual + \"|file=\" + e.file + \"|line=\" + e.line",
            "    + \"|column=\" + e.column);",
            "}",
            // The first invalid export stops later reads: alpha (ascending
            // first) fails, so zeta is never read.
            "const failOrder = [];",
            "const rawFail = {};",
            "Object.defineProperty(rawFail, \"alpha\", { enumerable: true,",
            "  get: function () { failOrder.push(\"alpha\"); return 17; } });",
            "Object.defineProperty(rawFail, \"zeta\", { enumerable: true,",
            "  get: function () { failOrder.push(\"zeta\"); return function () { return 9; }; } });",
            "try {",
            "  $rt.loadHost(rawFail, decl, \"host/echo\", \"main.deal\", 1, 1);",
            "  say(\"first-failure=none\");",
            "} catch (e) {",
            "  say(\"first-failure=\" + e.code + \" \" + e.message);",
            "}",
            "say(\"failure-read-order=\" + failOrder.join(\",\"));",
            // The raw non-object guard: the complete unlocated tuple of
            // every JS diagnostic kind the guard rejects — a number, the JS
            // null and the absent marker (the JS runtime's own $kindOf
            // vocabulary, never a Lua-shaped carrier kind).
            "for (const raw of [17, null, undefined]) {",
            "  try {",
            "    $rt.loadHost(raw, decl, \"host/echo\", \"main.deal\", 1, 1);",
            "    say(\"raw-shape=none\");",
            "  } catch (e) {",
            "    say(\"raw-shape=\" + e.code + \"|\" + e.message + \"|\" + e.expected",
            "      + \"|\" + e.actual",
            "      + \"|file=\" + (e.file === undefined ? \"<absent>\" : e.file)",
            "      + \"|line=\" + (e.line === undefined ? \"<absent>\" : e.line)",
            "      + \"|column=\" + (e.column === undefined ? \"<absent>\" : e.column));",
            "  }",
            "}",
            "");
        NodeResult probeResult = runRuntimeProbe("host_occurrence", probe);
        check(probeResult.exitCode() == 0,
            "the host-occurrence runtime probe exits 0: exit "
                + probeResult.exitCode() + ", output '"
                + probeResult.output() + "'");
        String probeOut = probeResult.output();
        check(probeOut.contains("read-order=alpha,zeta"),
            "the loader reads the supplied declaration order: " + probeOut);
        check(probeOut.contains("same-import-identity=true"),
            "repeated reads from one surface are identical: " + probeOut);
        check(probeOut.contains("cross-import-inequality=true"),
            "different source imports' typed functions are unequal: " + probeOut);
        check(probeOut.contains("typed-call=79"),
            "both typed surfaces call through their own wrappers: " + probeOut);
        check(probeOut.contains("meta-identity=true"),
            "the raw class META reference is preserved, never cloned: " + probeOut);
        check(probeOut.contains("fresh-new=true"),
            "each import synthesizes a fresh C$new: " + probeOut);
        check(probeOut.contains("fresh-fields=true"),
            "each import synthesizes a fresh C$fields: " + probeOut);
        check(probeOut.contains("pw-same-import-identity=true"),
            "a prewrapped export's repeated same-import reads are identical: "
                + probeOut);
        check(probeOut.contains("pw-cross-import-inequality=true"),
            "prewrapped exports across different source imports are unequal "
                + "typed wrappers: " + probeOut);
        check(probeOut.contains("pw-typed-call=55"),
            "both prewrapped typed surfaces call through the re-enforced $f: "
                + probeOut);
        check(probeOut.contains("pw-raw-non-cloning=true"),
            "the raw prewrapped export object is preserved, never cloned: "
                + probeOut);
        check(probeOut.contains("pw-signature=()->int,()->int"),
            "the re-wrapped prewrapped exports keep the declared signature: "
                + probeOut);
        check(probeOut.contains("pw-enforce=E8010|return value 1 type mismatch: "
                + "expected int|int|string"),
            "the re-wrapped prewrapped $f enforces the declared return "
                + "descriptor: " + probeOut);
        check(probeOut.contains("pw-mismatch=E8011|host export 'pw' in module "
                + "'host/echo' has signature mismatch: expected ()->int, got "
                + "()->string|()->int|()->string|file=main.deal|line=1|column=1"),
            "a prewrapped $sig mismatch fails E8011 at the import origin with "
                + "the declared/seen signatures: " + probeOut);
        check(probeOut.contains("first-failure=E8011 host export 'alpha' "
                + "in module 'host/echo' is not a function"),
            "the first invalid export fails at its own name: " + probeOut);
        check(probeOut.contains("failure-read-order=alpha"),
            "the first invalid export stops later reads: " + probeOut);
        check(probeOut.contains("raw-shape=E8011|host module did not return a "
                + "module object|table|number|file=<absent>|line=<absent>|column=<absent>"),
            "the raw non-object guard renders the complete unlocated tuple: "
                + probeOut);
        check(probeOut.contains("raw-shape=E8011|host module did not return a "
                + "module object|table|null|file=<absent>|line=<absent>|column=<absent>"),
            "the raw null guard renders the JS null actual (never the Lua-shaped "
                + "carrier-kind table) with the complete unlocated tuple: " + probeOut);
        check(probeOut.contains("raw-shape=E8011|host module did not return a "
                + "module object|table|nil|file=<absent>|line=<absent>|column=<absent>"),
            "the raw absent guard renders the nil actual with the complete "
                + "unlocated tuple: " + probeOut);

        // (2) The full orchestrator project: two same-file aliases and one
        // cross-unit import of one resolved host file.
        writeFile("occ_proj/deal.json",
            "{\"languageVersion\": \"1.3\", \"backend\": \"js\",\n"
            + " \"externals\": {\"host/echo\": {\"declaration\": "
            + "\"../bindings/echo.d.deal\"}}}\n");
        writeFile("occ_proj/bindings/echo.d.deal", """
            export function zeta(): int;
            export function alpha(): int;
            export function values(): int[];
            """);
        writeFile("occ_proj/src/occ_util.deal", """
            import * as e from "host/echo";
            export function read(): int { return e.alpha(); }
            """);
        writeFile("occ_proj/src/occ_main.deal", """
            import * as h1 from "host/echo";
            import * as h2 from "host/echo";
            import * as util from "./occ_util";
            export function main(): null {
              if (h1.alpha !== h1.alpha) {
                throw { code: "SAME_IMPORT_IDENTITY", message: "same import" };
              }
              if (h1.alpha === h2.alpha) {
                throw { code: "CROSS_IMPORT_INEQUALITY", message: "imports" };
              }
              if (h1.alpha() !== 7 || h2.alpha() !== 7) {
                throw { code: "HOST_CALL", message: "direct" };
              }
              if (util.read() !== 7) {
                throw { code: "CROSS_UNIT_CALL", message: "cross unit" };
              }
              let xs: int[] = [1, 2, 3];
              let sum: int = 0;
              for (let e: int of xs) {
                sum = sum + e;
                if (e === 1) { delete xs[1]; }
              }
              if (sum !== 1) {
                throw { code: "DELETE_STOP", message: "deleted slot" };
              }
              let ys: int[] = [1, 2];
              let appended: int = 0;
              for (let e: int of ys) {
                appended = appended + e;
                if (e === 1) { ys[ys.length] = 7; }
              }
              if (appended !== 10) {
                throw { code: "APPEND_VISIT", message: "appended element" };
              }
              let once: int = 0;
              for (let e: int of h1.values()) {
                once = once + e;
              }
              if (once !== 3) {
                throw { code: "ITERABLE_ONCE", message: "iterable" };
              }
              return null;
            }
            """);
        Path entryFile = tmpDir.resolve(
            "occ_proj/src/occ_main.deal").toAbsolutePath();
        Path outputDir = tmpDir.resolve("occ_proj/build/js");
        List<Path> roots = List.of(tmpDir.resolve(
            "occ_proj/src").toAbsolutePath());
        Map<String, String> externals = Map.of(
            "host/echo", "../bindings/echo.d.deal");
        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            entryFile, outputDir, false, false, false, Backend.JS,
            externals, roots, Path.of(".").toAbsolutePath().normalize());
        boolean success = orchestrator.compile();
        check(success, "the occurrence project compiles: "
            + orchestrator.diagnostics());
        if (!success) {
            return;
        }

        String entryJs = Files.readString(outputDir.resolve("occ_main.js"));
        check(entryJs.contains("$rt.loadHost($require(" + q() + "./host/echo"
                + q() + "), {" + q() + "alpha" + q() + ":"),
            "the emitted declared map is export-name-sorted: " + entryJs);
        check(entryJs.indexOf(q() + "alpha" + q() + ":") < entryJs.indexOf(
                q() + "values" + q() + ":")
                && entryJs.indexOf(q() + "values" + q() + ":") < entryJs.indexOf(
                    q() + "zeta" + q() + ":"),
            "the declared map carries ascending alpha, values, zeta: " + entryJs);

        writeFile("occ_proj/build/js/host/echo.js", """
            "use strict";
            globalThis.__echoInit = (globalThis.__echoInit || 0) + 1;
            const order = [];
            globalThis.__readOrder = order;
            const calls = { n: 0 };
            globalThis.__valuesCalls = calls;
            module.exports = {};
            Object.defineProperty(module.exports, "zeta", { enumerable: true,
              get: function () { order.push("zeta"); return function () { return 9; }; } });
            Object.defineProperty(module.exports, "alpha", { enumerable: true,
              get: function () { order.push("alpha"); return function () { return 7; }; } });
            Object.defineProperty(module.exports, "values", { enumerable: true,
              get: function () {
                order.push("values");
                return function () {
                  calls.n = calls.n + 1;
                  return calls.n === 1 ? [1, 2] : [100, 200];
                };
              } });
            """);
        writeFile("occ_proj/build/js/occ_runner.js", String.join("\n",
            "\"use strict\";",
            "const $entry = require(\"./occ_main\");",
            "$entry.main.$f();",
            "process.stdout.write(\"init=\" + globalThis.__echoInit + \"\\n\");",
            "process.stdout.write(\"read-order=\" + globalThis.__readOrder.join(\",\")",
            "  + \"\\n\");",
            "process.stdout.write(\"values-calls=\" + globalThis.__valuesCalls.n",
            "  + \"\\n\");",
            ""));
        ProcessBuilder node = new ProcessBuilder("node", "occ_runner.js");
        node.directory(outputDir.toFile());
        node.redirectErrorStream(true);
        Process np = node.start();
        String nout = new String(np.getInputStream().readAllBytes(),
            StandardCharsets.UTF_8).trim();
        int nrc = np.waitFor();
        check(nrc == 0, "the occurrence project runs to completion under node: "
            + "exit " + nrc + ", output '" + nout + "'");
        check(nout.contains("init=1"), "the raw host file is required exactly "
            + "once across the same-file aliases and the cross-unit import: " + nout);
        check(nout.contains("read-order=alpha,values,zeta"),
            "the declared reads run in ascending export-name order: " + nout);
        check(nout.contains("values-calls=1"),
            "the for-of iterable is evaluated exactly once: " + nout);

        // (3) The declared-order failure controls: the declaration file
        // names zeta before alpha, and the ascending-name selection must
        // still read alpha first and stop at the first invalid export —
        // the earlier implementation read the provider/declaration order
        // on these paths.
        writeFile("ord_proj/deal.json",
            "{\"languageVersion\": \"1.3\", \"backend\": \"js\",\n"
            + " \"externals\": {\"host/ord\": {\"declaration\": "
            + "\"../bindings/ord.d.deal\"}}}\n");
        writeFile("ord_proj/bindings/ord.d.deal", """
            export function zeta(): int;
            export function alpha(): int;
            """);
        writeFile("ord_proj/src/ord_main.deal", """
            import * as h from "host/ord";
            export function main(): null {
              if (h.alpha() !== 7) {
                throw { code: "ALPHA", message: "alpha" };
              }
              if (h.zeta() !== 9) {
                throw { code: "ZETA", message: "zeta" };
              }
              return null;
            }
            """);
        Path ordEntry = tmpDir.resolve(
            "ord_proj/src/ord_main.deal").toAbsolutePath();
        Path ordOut = tmpDir.resolve("ord_proj/build/js");
        CompilationOrchestrator ordOrchestrator = new CompilationOrchestrator(
            ordEntry, ordOut, false, false, false, Backend.JS,
            Map.of("host/ord", "../bindings/ord.d.deal"),
            List.of(tmpDir.resolve("ord_proj/src").toAbsolutePath()),
            Path.of(".").toAbsolutePath().normalize());
        check(ordOrchestrator.compile(),
            "the declared-order project compiles: "
                + ordOrchestrator.diagnostics());
        String ordEntryJs = Files.readString(ordOut.resolve("ord_main.js"));
        check(ordEntryJs.contains("$rt.loadHost($require(" + q() + "./host/ord"
                + q() + "), {" + q() + "alpha" + q() + ":")
                && ordEntryJs.indexOf(q() + "alpha" + q() + ":") < ordEntryJs
                    .indexOf(q() + "zeta" + q() + ":"),
            "the zeta-first declaration file still emits the ascending declared "
                + "map alpha, zeta: " + ordEntryJs);
        writeFile("ord_proj/build/js/ord_runner.js", String.join("\n",
            "\"use strict\";",
            "try {",
            "  const $entry = require(\"./ord_main\");",
            "  $entry.main.$f();",
            "  process.stdout.write(\"run=ok\\n\");",
            "} catch (e) {",
            "  process.stdout.write(\"run=\" + e.code + \"|\" + e.message + \"|\"",
            "    + (e.expected === undefined ? \"<absent>\" : e.expected) + \"|\"",
            "    + (e.actual === undefined ? \"<absent>\" : e.actual)",
            "    + \"|file=\" + (e.file === undefined ? \"<absent>\" : e.file)",
            "    + \"|line=\" + (e.line === undefined ? \"<absent>\" : e.line)",
            "    + \"|column=\" + (e.column === undefined ? \"<absent>\" : e.column)",
            "    + \"\\n\");",
            "}",
            "process.stdout.write(\"read-order=\"",
            "  + (globalThis.__ordOrder || []).join(\",\") + \"|\\n\");",
            ""));
        String ordOrigin = "|file=" + ordEntry + "|line=1|column=1";
        String ordAlphaFn = "$rt.function(\"()->int\", function () { return 7; })";
        String ordZetaFn = "$rt.function(\"()->int\", function () { return 9; })";
        String ordAlphaGetter = "Object.defineProperty(module.exports, \"alpha\", "
            + "{ enumerable: true, get: function () { order.push(\"alpha\"); "
            + "return %s; } });";
        String ordZetaGetter = "Object.defineProperty(module.exports, \"zeta\", "
            + "{ enumerable: true, get: function () { order.push(\"zeta\"); "
            + "return %s; } });";

        // (3a) alpha (ascending first) invalid: the exact first-invalid
        // tuple at the import origin, and zeta is never read even though
        // the declaration names it first.
        writeFile("ord_proj/build/js/host/ord.js", ordHost(
            ordZetaGetter.formatted(ordZetaFn) + ordAlphaGetter.formatted("17")));
        NodeResult ordAlphaFail = runNodeScript(ordOut, "ord_runner.js");
        check(ordAlphaFail.output().contains("run=E8011|host export 'alpha' in "
                + "module 'host/ord' is not a function|function|number" + ordOrigin),
            "the alpha failure renders the exact first-invalid E8011 tuple at "
                + "the import origin: " + ordAlphaFail.output());
        check(ordAlphaFail.output().contains("read-order=alpha|"),
            "the alpha failure reads only alpha and stops before zeta: "
                + ordAlphaFail.output());

        // (3b) alpha valid, zeta invalid: the later failure reads
        // [alpha, zeta] in ascending name order and stops there.
        writeFile("ord_proj/build/js/host/ord.js", ordHost(
            ordZetaGetter.formatted("17") + ordAlphaGetter.formatted(ordAlphaFn)));
        NodeResult ordZetaFail = runNodeScript(ordOut, "ord_runner.js");
        check(ordZetaFail.output().contains("run=E8011|host export 'zeta' in "
                + "module 'host/ord' is not a function|function|number" + ordOrigin),
            "the later zeta failure renders the exact E8011 tuple at the import "
                + "origin: " + ordZetaFail.output());
        check(ordZetaFail.output().contains("read-order=alpha,zeta|"),
            "the later failure reads alpha, zeta in ascending name order: "
                + ordZetaFail.output());

        // (3c) zeta missing: the exact missing-export tuple with no
        // later effects (alpha is the only read).
        writeFile("ord_proj/build/js/host/ord.js", ordHost(
            ordAlphaGetter.formatted(ordAlphaFn)));
        NodeResult ordZetaMissing = runNodeScript(ordOut, "ord_runner.js");
        check(ordZetaMissing.output().contains("run=E8011|missing host export "
                + "'zeta' in module 'host/ord'|<absent>|<absent>" + ordOrigin),
            "the missing zeta renders the exact missing-export tuple at the "
                + "import origin: " + ordZetaMissing.output());
        check(ordZetaMissing.output().contains("read-order=alpha|"),
            "the missing zeta stops after alpha: " + ordZetaMissing.output());

        // (3d) the valid success control: the same zeta-first declaration
        // reads [alpha, zeta] ascending and returns.
        writeFile("ord_proj/build/js/host/ord.js", ordHost(
            ordZetaGetter.formatted(ordZetaFn) + ordAlphaGetter.formatted(ordAlphaFn)));
        NodeResult ordOk = runNodeScript(ordOut, "ord_runner.js");
        check(ordOk.output().contains("run=ok"),
            "the valid zeta-first declaration project runs to completion: "
                + ordOk.output());
        check(ordOk.output().contains("read-order=alpha,zeta|"),
            "the valid control reads alpha, zeta in ascending name order: "
                + ordOk.output());
    }

    /**
     * One declared-order failure-control host body: the module exports
     * object plus the supplied getter definitions (the caller supplies
     * the zeta getter before the alpha getter, so the host file's own
     * property order can never explain an ascending read).
     */
    private static String ordHost(String getters) {
        return """
            "use strict";
            const order = [];
            globalThis.__ordOrder = order;
            const $rt = require("../deal/runtime");
            module.exports = {};
            """ + getters;
    }

    private static void testSourceMapSidecarsEmitted() throws Exception {
        System.out.println("-- Orchestrator: default and --source-map sidecars, "
            + "warning retired --");

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

        // The default compile (no explicit spelling) fires no warning and
        // writes the same sidecar: the v1.3 default debugging artifacts are
        // unconditional.
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
        Path defaultSidecar = outputDir2.resolve("sm_main.deal.map.json");
        check(Files.exists(defaultSidecar),
            "the default compile writes the .deal.map.json sidecar");
        if (Files.exists(defaultSidecar)) {
            String json = Files.readString(defaultSidecar);
            check(json.contains("\"version\": 1")
                    && json.contains("\"source\": \"src/sm_main.deal\"")
                    && json.contains(
                        "\"generated\": \"build/sm_js2/sm_main.js\""),
                "the default sidecar keeps the spec version and the "
                    + "project-relative paths: " + json);
        }
    }

    // =========================================================================
    // Node semantic cases (js-backend-runtime Verification 1-9 home)
    // =========================================================================

    private static void testIntArithmeticEdgeCodes() throws Exception {
        System.out.println("-- Node: int arithmetic edge codes --");
        if (!nodeAvailable) { skipNode("int arithmetic edge codes"); return; }

        Frontend removedPower = compileFrontend(
            "export function test(): int { return 2 ** 10000; }", "int-pow-overflow.deal");
        check(removedPower.errors().stream().anyMatch(d -> d.code().equals("E1037")),
            "removed power syntax rejects before emission");
        NodeResult pow = runDealNode(
            "export function test(): int { return int(1.0 / 0.0); }",
            "int-infinity");
        check(pow.exitCode() == 1 && pow.output().contains("DEAL_ERROR_CODE: E8001")
                && pow.output().contains("expected int, got infinity"),
            "int(infinity) → E8001 'expected int, got infinity' (checkInt order "
                + "before the finite-range arm), exit 1: " + pow.output());

        NodeResult range = runDealNode(
            "export function test(): int { return int(1.0e300); }",
            "int-finite-range");
        check(range.exitCode() == 1 && range.output().contains("DEAL_ERROR_CODE: E8004")
                && range.output().contains("int out of safe range"),
            "finite out-of-range → E8004 'int out of safe range' (the "
                + "pinned template), exit 1: " + range.output());

        NodeResult divz = runDealNode(
            "export function test(): int { return 5 / 0; }",
            "int-div-zero");
        check(divz.exitCode() == 1 && divz.output().contains("DEAL_ERROR_CODE: E8005")
                && divz.output().contains("integer division by zero"),
            "int division by zero → E8005, exit 1: " + divz.output());

        Frontend negativePower = compileFrontend(
            "export function test(): int { return 2 ** -1; }", "int-neg-exp.deal");
        check(negativePower.errors().stream().anyMatch(d -> d.code().equals("E1037")),
            "removed negative power syntax rejects before emission");

        Frontend removedSafeMax = compileFrontend(
            "export function test(): int { return 9007199254740991; }",
            "int-safe-max.deal");
        check(removedSafeMax.errors().stream()
                .anyMatch(d -> d.code().equals("E1036")),
            "a literal beyond the signed-32 range rejects before emission "
                + "(the legacy ±(2^53-1) range is gone)");

        NodeResult trunc = runDealNode(
            "export function test(): int { return -7 % 3; }",
            "int-mod-trunc");
        check(trunc.exitCode() == 0 && trunc.output().equals("-1"),
            "int % int is truncated (-7 % 3 === -1): " + trunc.output());
    }

    private static void testInt32RuntimeGateEmission() throws Exception {
        System.out.println("-- Emission pins: the signed-32 gate is on from"
            + " runtime load (no profile selector) --");

        String source = "export function test(): int { return 2147483647 + 1; }";
        Frontend f = compileFrontend(source, "jstest-int32-selector.deal");
        if (f.program() == null) {
            fail("int32 gate frontend failed: " + f.errors());
            return;
        }

        JsBackend.JsCodegenResult res = JsBackend.generate(f.program(),
            f.checkResult(), "jstest-int32-selector.deal", "Main",
            Map.of(), Map.of(), false);
        check(!res.hasErrors(),
            "the sole-profile generation is clean: " + res.diagnostics());
        check(!res.source().contains("setInt32Mode"),
            "the emitted module carries no profile selector");
        check(res.source().contains("$rt.checkInt("),
            "the emitted module carries the checkInt boundary text");
    }

    private static void testInt32OrchestratorPlumb() throws Exception {
        System.out.println("-- Orchestrator plumb: the one invocation runs the"
            + " signed-32 gate from runtime load in every module --");

        writeFile("js_int32_proj/deal.json",
            "{\"languageVersion\": \"1.3\", \"backend\": \"js\"}");
        writeFile("js_int32_proj/src/app/lib.deal",
            "export function ping(): string { return \"pong\"; }");
        writeFile("js_int32_proj/src/app/main.deal",
            "import * as lib from \"./lib\";\n"
                + "export function main(): null {\n"
                + "  if (lib.ping() === \"pong\") { return null; }\n"
                + "  return null;\n"
                + "}\n");

        Path entryFile = tmpDir.resolve("js_int32_proj/src/app/main.deal")
            .toAbsolutePath();
        Path outputDir = tmpDir.resolve("js_int32_proj/build/js");
        List<Path> roots = List.of(tmpDir.resolve("js_int32_proj/src")
            .toAbsolutePath());

        StrictManifestParser.StrictManifestParseResult strictJs =
            StrictManifestParser.parse("deal.json",
                "{\"languageVersion\": \"1.3\", \"backend\": \"js\"}");
        check(strictJs.manifest() != null && strictJs.failure() == null,
            "int32 plumb deal.json backend js parses strictly: "
                + strictJs.failure());
        if (strictJs.manifest() != null) {
            check("js".equals(strictJs.manifest().backend()),
                "int32 plumb strict manifest publishes backend js");
        }

        CompilerInvocation invocation = CompilerProfileProvider.resolve(
            ReleaseState.V1_3_ACTIVE, CapabilityRegistry.releaseRegistry());

        CompilationOrchestrator active = new CompilationOrchestrator(
            entryFile, outputDir, false, false, false, false, Backend.JS,
            (Map<String, String>) null, roots,
            Path.of(".").toAbsolutePath().normalize(), null,
            invocation);
        boolean activeOk = active.compile();
        check(activeOk, "JS orchestrator compile succeeds: "
            + active.diagnostics());
        if (activeOk) {
            for (String artifactName : List.of("app/main.js", "app/lib.js")) {
                Path artifact = outputDir.resolve(artifactName);
                check(Files.exists(artifact),
                    "orchestrator wrote " + artifactName);
                if (Files.exists(artifact)) {
                    String js = Files.readString(artifact);
                    check(!js.contains("setInt32Mode"),
                        artifactName + " carries no profile selector (the "
                            + "runtime gate is on from load)");
                    check(js.contains("$require(\"")
                            && js.contains("deal/runtime\")"),
                        artifactName + " requires the deployed runtime");
                }
            }
        }

        // The default invocation is the same record.
        Path defaultOutputDir = tmpDir.resolve("js_int32_proj/build/js_default");
        CompilationOrchestrator defaultOrchestrator = new CompilationOrchestrator(
            entryFile, defaultOutputDir, false, false, false, Backend.JS,
            (Map<String, String>) null, roots,
            Path.of(".").toAbsolutePath().normalize());
        boolean defaultOk = defaultOrchestrator.compile();
        check(defaultOk, "default JS orchestrator compile succeeds: "
            + defaultOrchestrator.diagnostics());
        if (defaultOk) {
            for (String artifactName : List.of("app/main.js", "app/lib.js")) {
                Path artifact = defaultOutputDir.resolve(artifactName);
                check(Files.exists(artifact),
                    "default orchestrator wrote " + artifactName);
                if (Files.exists(artifact)) {
                    check(!Files.readString(artifact).contains("setInt32Mode"),
                        artifactName + " emits no profile selector");
                }
            }
        }

    }

    private static void testInt32MatrixNode() throws Exception {
        System.out.println("-- Node: signed-int32 matrix under DEAL_V1_3_INT32 --");
        if (!nodeAvailable) { skipNode("signed-int32 matrix"); return; }

        String[][] rows = {
            {"max-ok", "return 2147483647;", "2147483647"},
            {"min-ok", "return -2147483648;", "-2147483648"},
            {"add-over", "return 2147483647 + 1;", "E8004", "int out of safe range"},
            {"sub-under", "return -2147483648 - 1;", "E8004", "int out of safe range"},
            {"div-over", "return -2147483648 / -1;", "E8004"},
            {"mod-min-neg-one", "return -2147483648 % -1;", "0"},
            {"neg-over", "return -(-2147483648);", "E8004"},
            {"div-zero", "return 5 / 0;", "E8005", "integer division by zero"},
            {"mod-zero", "return 5 % 0;", "E8005"},
            {"neg-exp", "return 2 ** -1;", "E1037"},
            {"pow-over", "return 2 ** 10000;", "E1037"},
            {"trunc-signs", "let a: int = -7 / 3; let b: int = 7 / -3; let c: int = -7 % 3; "
                + "if (a === -2 && b === -2 && c === -1) { return 1; } return 0;", "1"},
            {"conv-range", "return int(2147483648.0);", "E8004", "int out of safe range"},
            {"conv-nan", "return int(0.0 / 0.0);", "E8001", "expected int, got NaN"},
            {"conv-inf", "return int(1.0 / 0.0);", "E8001", "expected int, got infinity"}
        };
        for (String[] row : rows) {
            if (row[2].equals("E1037")) {
                Frontend rejected = compileFrontend("export function test(): int { " + row[1] + " }",
                    "int32-" + row[0] + ".deal");
                check(rejected.errors().stream().anyMatch(d -> d.code().equals("E1037")),
                    row[0] + ": removed power syntax rejects before emission");
                continue;
            }
            NodeResult result = runDealNode(
                "export function test(): int { " + row[1] + " }",
                "int32-" + row[0]);
            boolean failure = row[2].startsWith("E");
            check(result.exitCode() == (failure ? 1 : 0)
                    && (failure ? result.output().contains("DEAL_ERROR_CODE: " + row[2])
                        && (row.length == 3 || result.output().contains(row[3]))
                        : result.output().equals(row[2])),
                "int32-" + row[0] + ": " + result.output());
        }

        PreparedJs fractional = prepareJs(
            "export function test(): int { return int(3.5); }",
            "int32-conv-frac");
        NodeResult convFrac = runDealArtifact(fractional);
        check(convFrac.exitCode() == 1
                && convFrac.output().contains("DEAL_ERROR_CODE: E8001")
                && convFrac.output().contains(
                    "expected int, got non-integer number"),
            "int(3.5) → E8001 non-integer, exit 1: " + convFrac.output());

        // The nil-equivalent conversion arm through the runtime (the
        // pinned message; only emitter mis-emission reaches it).
        NodeResult convNull = runRuntimeProbe("int32-conv-null",
            "\"use strict\";\n"
                + "const $rt = require(\"./deal/runtime\");\n"
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
        NodeResult negZero = runDealNode(
            "export function test(): int { let z: int = -0; "
                + "if (z === 0) { return 1; } return 0; }",
            "int32-neg-zero");
        check(negZero.exitCode() == 0 && negZero.output().equals("1"),
            "-0 normalizes to 0 (-0 === 0): " + negZero.output());

        // The combined gate: the int32 artifacts carry the canonical
        // descriptor and check texts (T1's descriptor service and T3's
        // range gate) — the matrix fails if either breaks.
        Frontend canonical = fractional.frontend();
        if (canonical.program() == null) {
            fail("int32 canonical frontend failed: " + canonical.errors());
            return;
        }
        JsBackend.JsCodegenResult canonicalRes = fractional.emission();
        check(!canonicalRes.hasErrors(),
            "int32 canonical generation clean: " + canonicalRes.diagnostics());
        check(!canonicalRes.source().contains("setInt32Mode"),
            "the artifact carries no selector (the runtime signed-32 gate "
                + "is active from load)");
        check(canonicalRes.source().contains("$rt.checkInt("),
            "the int32 artifact carries the checkInt boundary text (T3's "
                + "parameterized range gate)");
        check(canonicalRes.source().contains(
                "$rt.function(\"(number)->int\""),
            "the int32 artifact carries the canonical (number)->int "
                + "wrapper seed (T1's canonical descriptors)");

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

        // An allocation length above the signed-int32 logical-length
        // bound is unrepresentable under the sole profile: the literal
        // rejects at admission (E1036) before any allocation.
        Frontend tooLong = compileFrontend(
            "export function test(): int { let b: bytes = bytes(2147483648); "
                + "return 0; }", "bytes-too-long.deal");
        check(tooLong.errors().stream().anyMatch(d -> d.code().equals("E1036")),
            "bytes(2147483648) rejects before emission: " + tooLong.errors());

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
            import * as json from "std/json";
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
     * docs/spec-v1.3.md §Name resolution): `bytes` is not a DEAL keyword and
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
        PreparedJs clsPrepared = prepareJs("""
            class bytes { x: int; }
            export function test(): int {
              let b: bytes = { x: 2 };
              return b.x;
            }
            """, "bytes-user-class");
        JsBackend.JsCodegenResult clsRes = clsPrepared.emission();
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
            check(js.contains("bytes$plan = [{ name: \"x\", descriptor: "
                    + "\"int\", optional: false, evaluator: () => 0 }];")
                    && js.contains("$rt.classPlan(\"@Main/bytes\", "
                        + "bytes$plan, provided, $file, $line, $column);"),
                "class construction routes through $rt.classPlan over the "
                    + "synthesized plan with the user class identity");
        }

        // (b) A module-level user FUNCTION named bytes: checker-accepted,
        // no E2002, calls resolve to the user function, and the
        // artifact's predeclared `let bytes;` assignment replaces the
        // skipped intrinsic header seed.
        PreparedJs fnPrepared = prepareJs("""
            function bytes(x: int): int { return x + 1; }
            export function test(): int { return bytes(3); }
            """, "bytes-user-function");
        JsBackend.JsCodegenResult fnRes = fnPrepared.emission();
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

        NodeResult clsRun = runDealArtifact(clsPrepared);
        check(clsRun.exitCode() == 0 && clsRun.output().equals("2"),
            "module-level user class named bytes runs under node (b.x "
                + "=== 2): " + clsRun.output());

        NodeResult fnRun = runDealArtifact(fnPrepared);
        check(fnRun.exitCode() == 0 && fnRun.output().equals("4"),
            "module-level user function named bytes runs under node "
                + "(bytes(3) === 4): " + fnRun.output());

        // (c) Preserve the local bytes-class placement negative, then execute
        // the legal module-level user class from a nested block.
        assertLocalClassRejected("""
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
        NodeResult nestedRun = runDealNode("""
            class bytes { x: int = 0; }
            export function test(): int {
              let out: int = 0;
              {
                let b: bytes = { x: 2 };
                out = b.x;
              }
              return out;
            }
            """, "bytes-user-class-block-use");
        check(nestedRun.exitCode() == 0 && nestedRun.output().equals("2"),
            "module user class named bytes runs from a nested block (b.x === 2): "
                + nestedRun.output());
    }

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
                && badDepth2.output().contains(
                    "array element 1 type mismatch"),
            "a wrong inner shape at depth 2 raises the E8003 element "
                + "mismatch at the failing element (the converged message "
                + "form carries no inner text — the reached descriptor row "
                + "is the error's expected field): " + badDepth2.output());

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
                && badDepth3.output().contains(
                    "array element 1 type mismatch"),
            "a wrong inner shape at depth 3 raises the E8003 element "
                + "mismatch at the failing element (the converged message "
                + "form carries no inner text — the reached descriptor row "
                + "is the error's expected field): " + badDepth3.output());
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
            "{\"languageVersion\": \"1.3\", \"backend\": \"js\"}");
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
            import * as lib from "./bytes_closure_lib";
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
        Map<String, JsBackend.JsCodegenResult> emissions = new LinkedHashMap<>();
        for (int i = 0; i < closurePrograms.length; i++) {
            JsBackend.JsCodegenResult res = generate(closurePrograms[i],
                names[i]);
            emissions.put(names[i], res);
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
        JsBackend.JsCodegenResult fnArray = emissions.get(names[0]);
        if (fnArray != null && !fnArray.hasErrors()) {
            String js = fnArray.source();
            check(js.contains("$rt.checkArray(\"[(bytes)->bytes]\", "),
                "the ((bytes)->bytes)[] declaration boundary emits the "
                    + "canonical [(bytes)->bytes] descriptor byte-exact");
            check(js.contains("$rt.function(\"(bytes,int)->bytes\", "),
                "the arity adapter carries the canonical "
                    + "(bytes,int)->bytes target descriptor byte-exact");
        }
        JsBackend.JsCodegenResult asyncContainer = emissions.get(names[1]);
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
        JsBackend.JsCodegenResult deep = emissions.get(names[2]);
        if (deep != null && !deep.hasErrors()) {
            String js = deep.source();
            check(js.contains("$rt.checkArray(\"[?[bytes]]\", "),
                "the (bytes[] | null)[] boundary emits the canonical "
                    + "[?[bytes]] descriptor byte-exact");
            check(js.contains("$rt.checkNullable(\"[[bytes]]\", "),
                "the (bytes[])[] | null boundary emits the canonical "
                    + "[[bytes]] inner descriptor byte-exact");
        }

        PreparedJs eqPrepared = prepareJs("""
            export function test(): int {
              let a: bytes = bytes(1);
              let b: bytes = a;
              let c: bytes = bytes(1);
              if (!(a === b)) { return 0; }
              if (a === c) { return 0; }
              if (a !== c) { return 1; }
              return 0;
            }
            """, "bytes-closure-eq");
        Frontend eq = eqPrepared.frontend();
        check(eq.errors().isEmpty(),
            "bytes identity equality is checker-admitted with zero "
                + "diagnostics (frontend, ISSUE-0158 lift): " + eq.errors());
        if (eq.errors().isEmpty()) {
            JsBackend.JsCodegenResult eqRes = eqPrepared.emission();
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
            NodeResult eqRun = runDealArtifact(eqPrepared);
            check(eqRun.exitCode() == 0 && eqRun.output().equals("1"),
                "bytes identity equality executes real reference identity "
                    + "on Node (alias true, distinct buffers false; "
                    + "returns 1): " + eqRun.output());
        }
    }

    private static void testNumModFloored() throws Exception {
        System.out.println("-- v1.3: remainder requires int operands --");
        String[] subjects = { "-5.0 % 2.0", "5.0 % -2.0", "-5.0 % -2.0", "5.0 % 2.0" };
        for (String subject : subjects) {
            Frontend rejected = compileFrontend(
                "export function test(): number { return " + subject + "; }", "nummod.deal");
            check(rejected.errors().stream().anyMatch(d -> d.code().equals("E3007")
                    && d.message().contains("requires int operands")),
                subject + ": number remainder rejects before emission: " + rejected.errors());
        }
    }

    private static void testScalarStringOps() throws Exception {
        System.out.println("-- Node: scalar-value string ops --");
        if (!nodeAvailable) { skipNode("scalar string ops"); return; }

        NodeResult run = runDealNode("""
            import * as strings from "std/string";
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
              if (v === null) { return 0; }
              return 1;
            }
            """, "nil-array");
        check(arr.exitCode() == 1
                && arr.output().contains("DEAL_ERROR_CODE: E8002")
                && arr.output().contains("array index out of bounds"),
            "a nullable-array out-of-bounds read rejects with E8002 (bounds "
                + "precede the nullable element decision): " + arr.output());

        NodeResult inRange = runDealNode("""
            export function test(): int {
              let xs: (int | null)[] = [];
              xs[0] = 1;
              let v: int | null = xs[0];
              if (v !== null) {
                if (v !== 1) { return 0; }
                return 1;
              }
              return 0;
            }
            """, "inrange-array");
        check(inRange.exitCode() == 0 && inRange.output().equals("1"),
            "an in-range nullable read keeps its value: " + inRange.output());

        NodeResult nonNull = runDealNode(
            "export function test(): int { let xs: int[] = [1]; return xs[9]; }",
            "nil-array-nonnullable");
        check(nonNull.exitCode() == 1
                && nonNull.output().contains("DEAL_ERROR_CODE: E8002")
                && nonNull.output().contains("array index out of bounds"),
            "the same read at a non-nullable target rejects with E8002: "
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
              if (e.$dealCode !== "E8003" || e.message !== "array element 2 type mismatch") {
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
            // E8004 pinned message routes through checkInt (the runtime's
            // signed-32 gate is active from load).
            try {
              $rt.checkType("int", 2 ** 60, "probe.js", 1, 1);
              $fail = "e8004";
            } catch (e) {
              if (e.$dealCode !== "E8004" || e.message !== "int out of safe range") {
                $fail = "e8004-msg:" + e.$dealCode + ":" + e.message;
              }
            }
            // The builtin Error identity is the bare Error atom end-to-end,
            // and the superseded aliases never decode (no fallback identity).
            const $err = $rt.errorValue("E_TEST", "boom", "probe.js", 1, 1);
            if ($err.$kind !== "class" || $err.$classname !== "Error") {
              $fail = "error-identity:" + String($err.$classname);
            }
            if ($rt.reifyError($err) !== $err) { $fail = "reify-passthrough"; }
            if ($rt.checkType("Error", $err, "probe.js", 1, 1) !== $err) {
              $fail = "error-boundary";
            }
            if ($rt.checkType("?Error", $err, "probe.js", 1, 1) !== $err) {
              $fail = "error-nullable-boundary";
            }
            if ($rt.checkType("[Error]", [$err], "probe.js", 1, 1)[0] !== $err) {
              $fail = "error-array-boundary";
            }
            try {
              $rt.checkType("@/Error", $err, "probe.js", 1, 1);
              $fail = "superseded-alias-accepted";
            } catch (e) {
              if (e.message !== "internal: cannot parse type descriptor: @/Error") {
                $fail = "superseded-alias-arm:" + e.message;
              }
            }
            if ($fail === "") { console.log("CANONICAL-MATCHER-OK"); }
            else { console.log("FAIL: " + $fail); }
            """);
        check(run.exitCode() == 0 && run.output().equals("CANONICAL-MATCHER-OK"),
            "canonical ?/[D] acceptance over function descriptors, byte-for-byte "
                + "class atoms, and the pinned E8003/E8010/E8004/Error-atom "
                + "surfaces: " + run.output());
    }

    private static void testLegacyDescriptorRejectionPins() throws Exception {
        System.out.println("-- Node: legacy descriptor spellings rejected (cannot-parse arm) --");
        if (!nodeAvailable) { skipNode("legacy descriptor rejection"); return; }

        NodeResult run = runRuntimeProbe("legacy-reject", """
            "use strict";
            const $rt = require("./deal/runtime");
            let $fail = "";
            const $legacy = ["int[]", "int|null", "table[]", "User",
              "?User", "Errorx", "Error|null", "(int)->int[]", "(int)->int|null",
              "@Foo", "@src.models.User", "@m/c.Name", "??int", "?null",
              "[]", "(int)int", "async[int]", "@a/", "@a//b", "int...",
              "@/Error", "@$builtin/Error"];
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

        check("Error".equals(index.descriptorTextFor(
                new CanonicalClassIdentity(
                    CanonicalModuleIdentity.BuiltinModule.INSTANCE, "Error"))),
            "the builtin Error class projects the bare atom Error");
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
        check("Error".equals(service.encode(Types.classType("Error",
                new CanonicalClassIdentity(
                    CanonicalModuleIdentity.BuiltinModule.INSTANCE,
                    "Error")))),
            "encode(Type) emits the bare Error atom for the builtin Error type");
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
                    " at jstest-span-located.deal:1:18"),
            "the parameter boundary error reports the parameter's declared "
                + "location (the reference's parameter-check span): "
                + located.output());
    }

    private static void testStdJsonRoundTrips() throws Exception {
        System.out.println("-- Node: std/json round-trips --");
        if (!nodeAvailable) { skipNode("std/json round-trips"); return; }

        NodeResult run = runDealNode("""
            import * as json from "std/json";
            class User {
              name: string = "";
              nick?: string | null;
            }
            export function test(): int {
              // The declared (string)->table exit rejects a top-level JSON
              // array and null with the shared boundary outcome (the decoded
              // array is the array carrier, the decoded null is language
              // null); neither is admitted as a table.
              let arrayOutcome: string = "";
              try {
                let arr: table = json.parse("[1,2]");
                arrayOutcome = "accepted";
              } catch (e) {
                arrayOutcome = e.code + ":" + e.message;
              }
              if (arrayOutcome !== "E8001:expected table") { return 0; }
              let nullOutcome: string = "";
              try {
                let n: table = json.parse("null");
                nullOutcome = "accepted";
              } catch (e) {
                nullOutcome = e.code + ":" + e.message;
              }
              if (nullOutcome !== "E8001:expected table") { return 0; }
              let p: table = { __proto__: "x" };
              let ps: string = json.stringify(p);
              if (ps !== "{\\"__proto__\\":\\"x\\"}") { return 0; }
              let pr: table = json.parse(ps);
              let prs: string = json.stringify(pr);
              if (prs !== "{\\"__proto__\\":\\"x\\"}") { return 0; }
              let u: User = { name: "Ada" };
              let g: table = { u: u };
              // A class instance inside table content is not JSON-shaped:
              // the walk rejects it with the closed unsupported-type arm
              // (class values are never implicitly table arguments).
              let rejected: boolean = false;
              try {
                let gs: string = json.stringify(g);
              } catch (e) {
                if (e.code === "E8001" && e.message === "unsupported type for JSON encoding: table") {
                  rejected = true;
                }
              }
              if (!rejected) { return 0; }
              return 1;
            }
            """, "json-roundtrip");
        check(run.exitCode() == 0 && run.output().equals("1"),
            "parse of a top-level array/null rejects with the shared "
                + "E8001/expected-table outcome, a __proto__-keyed table "
                + "round-trips and a class instance inside table content is "
                + "rejected by the closed unsupported-type arm: " + run.output());

        // The exact JS-lane tuple for both decode carriers, captured from
        // the emitted artifact's thrown error (the production JS emission
        // under node; the raw catch preserves the expected/actual fields
        // the source-level Error projects as code/message only).
        NodeResult tuple = runArtifactScript(checkedEmission(prepareJs("""
            import * as json from "std/json";
            export function test_array(): null {
              let t: table = json.parse("[1,2]");
              return null;
            }
            export function test_null(): null {
              let t: table = json.parse("null");
              return null;
            }
            """, "json-table-boundary")), """
            "use strict";
            const $rt = require("./deal/runtime");
            const $entry = require("./Main");
            for (const $k of ["test_array", "test_null"]) {
              try {
                $entry[$k].$f("p.deal", 1, 1);
                process.stdout.write($k + ": NO-FAILURE\\n");
              } catch ($e) {
                const $err = $rt.reifyError($e);
                process.stdout.write($k + ": " + $err.code + "|" + $err.message
                  + "|" + $err.expected + "|" + $err.actual + "\\n");
              }
            }
            """);
        check(tuple.exitCode() == 0
                && tuple.output().equals("test_array: E8001|expected table|table|array"
                    + "\ntest_null: E8001|expected table|table|null"),
            "the emitted JS artifact rejects both top-level decode carriers "
                + "with the shared expected/actual tokens: " + tuple.output());
    }

    /**
     * The array-element arm's reference-kind projection (the decoded-array
     * mark, B3) through the production JS lane: the
     * bytes-dynamic-nested-first-element-e8003 fixture shape — a decoded
     * JSON array crossing the recursive {@code [[bytes]]} descriptor —
     * reports the reference value model's {@code table} element token,
     * never the {@code array} token its table-descriptor projection
     * carries. The direct suite pins the runtime surface; this pin
     * executes the emitted artifact through the deployed runtime.
     */
    private static void testJsonDecodedArrayElementKindNode() throws Exception {
        System.out.println("-- Node: the decoded-array element reference-kind"
            + " projection --");
        if (!nodeAvailable) { skipNode("decoded-array element kind"); return; }

        JsBackend.JsCodegenResult res = checkedEmission(prepareJs("""
            import * as json from "std/json";

            export function test(): int {
              let t: table = json.parse("{\\"values\\":[[\\"x\\"],[\\"y\\"]]}");
              let xs: bytes[][] = t.values;
              return xs[0].length;
            }
            """, "json-element-kind"));
        NodeResult result = runArtifactScript(res, String.join("\n",
            "\"use strict\";",
            "const $rt = require(\"./deal/runtime\");",
            "const $entry = require(\"./Main\");",
            "try {",
            "  $entry.test.$f();",
            "  process.stdout.write(\"ELEMENT: no error\");",
            "} catch (e) {",
            "  const $err = $rt.reifyError(e);",
            "  process.stdout.write(\"ELEMENT: \" + $err.code + \"|\" + $err.message",
            "    + \"|\" + $err.expected + \"|\" + $err.actual);",
            "}",
            ""));
        check(result.exitCode() == 0 && result.output().equals(
                "ELEMENT: E8003|array element 1 type mismatch|[bytes]|table"),
            "the emitted artifact reports the failing decoded-array element"
                + " as the reference `table` kind (the fixture's pinned"
                + " tuple): " + result.output());
    }

    /**
     * The decoded-array carrier end to end (ISSUE-0827): a std/json
     * decoded JSON array crossing an array boundary behaves as its
     * declared DEAL array on the production JS lane — element reads, the
     * length read (empty and non-empty), an in-place element write
     * observed through every alias and through the shared JSON encode
     * walk, a delete that yields the nil-equivalent without changing the
     * length, and the E8002 bounds gate for the beyond-length position —
     * the oracle/LuaJIT/JVM behavior the shared array boundary admits.
     * The carrier is the runtime's array-marked Map; the emitted reads,
     * length reads, writes, deletes and bounds gates route through the
     * carrier-agnostic runtime members
     * ($rt.arrayAt/arrayLength/arraySet/arrayDelete).
     */
    private static void testJsonDecodedArrayReadLengthNode() throws Exception {
        System.out.println("-- Node: decoded-array element read and length"
            + " through the production artifact --");
        if (!nodeAvailable) { skipNode("decoded-array read and length"); return; }

        NodeResult run = runDealNode("""
            import * as json from "std/json";

            export function test(): int {
              let t: table = json.parse("{\\"items\\":[[1,2],[3,4]]}");
              let xs: int[][] = t.items;
              let first: int[] = xs[0];
              let alias: int[] = xs[0];
              if (first !== alias) { return 0; }
              if (first.length !== 2) { return 0; }
              if (xs.length !== 2) { return 0; }
              if (xs[1][0] !== 3) { return 0; }
              first[1] = 9;
              if (alias[1] !== 9) { return 0; }
              if (xs[0][1] !== 9) { return 0; }
              if (json.stringify(t) !== "{\\"items\\":[[1,9],[3,4]]}") { return 0; }
              let emptyT: table = json.parse("{\\"items\\":[]}");
              let empty: int[] = emptyT.items;
              if (empty.length !== 0) { return 0; }
              delete xs[0];
              if (xs.length !== 2) { return 0; }
              let deleted: boolean = false;
              try {
                let gone: int[] = xs[0];
              } catch (e) {
                if (e.code === "E8001" && e.message === "expected array") { deleted = true; }
              }
              if (!deleted) { return 0; }
              let caught: boolean = false;
              try {
                let oob: int[] = xs[2];
              } catch (e) {
                if (e.code === "E8002"
                    && e.message === "array index out of bounds") { caught = true; }
              }
              if (!caught) { return 0; }
              return 1;
            }
            """, "json-decoded-array-read");
        check(run.exitCode() == 0 && run.output().equals("1"),
            "the production artifact reads decoded-array elements and length"
                + " (empty and non-empty), writes through one alias and"
                + " observes it through another and through the JSON encode"
                + " walk, deletes to the nil-equivalent without changing the"
                + " length, and gates the beyond-length read with E8002: "
                + run.output() + " (exit " + run.exitCode() + ")");
    }

    /**
     * The @jsonable class-walk serializer and the decoded-array carrier
     * (the ISSUE-0827 review's blocking finding): a decoded JSON array
     * (the std/json array-marked Map) stored in a declared array field
     * through the checker-admitted dynamic table read — the carrier
     * {@code $rt.checkArray} returns unchanged — serializes as its
     * declared DEAL array. The walk reads the length and every element
     * through the carrier-agnostic runtime members and projects the
     * decoded-null carrier as language null, so the JS lane matches the
     * Oracle/LuaJIT/JVM lanes for a flat field, a nested
     * (array-of-array) field, an empty decoded array, a nullable element
     * holding a decoded JSON null, and an alias write observed by the
     * walk.
     */
    private static void testJsonableDecodedArrayFieldNode() throws Exception {
        System.out.println("-- Node: @jsonable declared array field holding a"
            + " decoded JSON array --");
        if (!nodeAvailable) { skipNode("jsonable decoded-array field"); return; }

        NodeResult run = runDealNode("""
            import * as json from "std/json";

            // @jsonable
            export class Box {
              nums: int[] = [];
              tags: string[][] = [];
              maybe: (int|null)[] = [];
              ns: number[] = [];
              nmaybe: (number|null)[] = [];
              nnested: number[][] = [];
            }

            export function test(): int {
              let t: table = json.parse("{\\"values\\":[1,2]}");
              let b: Box = {};
              b.nums = t.values;
              if (Box$toJson(b) !== "{\\"nums\\":[1,2],\\"tags\\":[],\\"maybe\\":[],\\"ns\\":[],\\"nmaybe\\":[],\\"nnested\\":[]}") { return 0; }
              let n: table = json.parse("{\\"values\\":[[\\"a\\"],[\\"b\\",\\"c\\"]]}");
              let c: Box = {};
              c.tags = n.values;
              if (Box$toJson(c) !== "{\\"nums\\":[],\\"tags\\":[[\\"a\\"],[\\"b\\",\\"c\\"]],\\"maybe\\":[],\\"ns\\":[],\\"nmaybe\\":[],\\"nnested\\":[]}") { return 0; }
              let alias: int[] = b.nums;
              alias[0] = 7;
              if (Box$toJson(b) !== "{\\"nums\\":[7,2],\\"tags\\":[],\\"maybe\\":[],\\"ns\\":[],\\"nmaybe\\":[],\\"nnested\\":[]}") { return 0; }
              let empty: table = json.parse("{\\"values\\":[]}");
              let e: Box = {};
              e.nums = empty.values;
              if (Box$toJson(e) !== "{\\"nums\\":[],\\"tags\\":[],\\"maybe\\":[],\\"ns\\":[],\\"nmaybe\\":[],\\"nnested\\":[]}") { return 0; }
              let mixed: table = json.parse("{\\"values\\":[1,null]}");
              let m: Box = {};
              m.maybe = mixed.values;
              if (Box$toJson(m) !== "{\\"nums\\":[],\\"tags\\":[],\\"maybe\\":[1,null],\\"ns\\":[],\\"nmaybe\\":[],\\"nnested\\":[]}") { return 0; }
              // A decoded array's number slots keep the graph's own
              // classification: 1, 1.0 and -0.0 are int-classified and
              // spell as integers, 1.5 keeps the number spelling.
              let numv: table = json.parse("{\\"values\\":[1,1.0,10]}");
              let nb: Box = {};
              nb.ns = numv.values;
              if (Box$toJson(nb) !== "{\\"nums\\":[],\\"tags\\":[],\\"maybe\\":[],\\"ns\\":[1,1,10],\\"nmaybe\\":[],\\"nnested\\":[]}") { return 0; }
              let nfrac: table = json.parse("{\\"values\\":[1.5,-0.0]}");
              let fb: Box = {};
              fb.ns = nfrac.values;
              if (Box$toJson(fb) !== "{\\"nums\\":[],\\"tags\\":[],\\"maybe\\":[],\\"ns\\":[1.5,0],\\"nmaybe\\":[],\\"nnested\\":[]}") { return 0; }
              let nnull: table = json.parse("{\\"values\\":[1,null,2]}");
              let nm: Box = {};
              nm.nmaybe = nnull.values;
              if (Box$toJson(nm) !== "{\\"nums\\":[],\\"tags\\":[],\\"maybe\\":[],\\"ns\\":[],\\"nmaybe\\":[1,null,2],\\"nnested\\":[]}") { return 0; }
              let nnest: table = json.parse("{\\"values\\":[[1],[2,3]]}");
              let nn: Box = {};
              nn.nnested = nnest.values;
              if (Box$toJson(nn) !== "{\\"nums\\":[],\\"tags\\":[],\\"maybe\\":[],\\"ns\\":[],\\"nmaybe\\":[],\\"nnested\\":[[1],[2,3]]}") { return 0; }
              // A decoded slot carrying the number variant is a number in
              // the shared classification even when its double is
              // integral, so the int row fails exactly like the other
              // lanes instead of re-rendering it as an int.
              let nv: table = json.parse("{\\"values\\":[2147483647.00000001]}");
              let vb: Box = {};
              vb.nums = nv.values;
              let intnum: string = "";
              try { intnum = Box$toJson(vb); }
              catch (e) { intnum = e.code + "|" + e.message; }
              if (intnum !== "E8001|value at nums[0] is not JSON serializable: number") { return 0; }
              return 1;
            }
            """, "jsonable-decoded-array-field");
        check(run.exitCode() == 0 && run.output().equals("1"),
            "the production artifact serializes a declared array field holding"
                + " a decoded JSON array (flat, nested, empty, a nullable"
                + " element holding a decoded JSON null, and number-classified"
                + " elements: 1/1.0/10 and -0.0 keep the integer spelling while"
                + " 1.5 spells the canonical decimal text, and a number-variant"
                + " element at an int field fails with the shared number token,"
                + " matching the shared classification the Oracle/LuaJIT/JVM"
                + " walk renders) as the"
                + " DEAL array text, and observes an alias"
                + " write through the walk: "
                + run.output() + " (exit " + run.exitCode() + ")");
    }

    private static void testJsonStoredFunctionE8001() throws Exception {
        System.out.println("-- Node: std/json stringify of a stored function → E8001 --");
        if (!nodeAvailable) { skipNode("json stored function"); return; }

        NodeResult run = runDealNode("""
            import * as json from "std/json";
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
            import * as console from "std/console";
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

        // ISSUE-0756 subject async-extended-argument-order: its faithful
        // post-rebase home is the published-artifact drive
        // (testProductionArityExtensionOrderNode), which exercises the
        // real v1.2 public compilation and the production host ABI with
        // the subject's own output; this legacy-profile invoker surface
        // is not a v1.2 public-artifact regression.

        // Negative host-failure signals: a wrong export name, a sync
        // export, a parameterized export, a descriptor-mismatched
        // returnDescriptor, and a non-wrapper export value each yield
        // { $ok: false, $failure } with the pinned reason — never a DEAL
        // error, never a false runtime-error pass.
        PreparedJs negatives = prepareJs("""
            export class Payload { v: int; }
            export function main(): null { return null; }
            export async function oracle(): null { return null; }
            export async function takes(i: int): int { return i; }
            """, "invoker-wrong-name");
        JsBackend.JsCodegenResult negativeEmission = checkedEmission(negatives);
        Path negativeDir = deployArtifacts(negativeEmission, "Main.js");
        try {
            Files.writeString(negativeDir.resolve("invoker_runner.js"),
                INVOKER_RUNNER_SOURCE);
            for (List<String> c : List.of(
                    List.of("nope", "null", "export not found: nope"),
                    List.of("main", "null", "sync export: expected async()->null, got ()->null"),
                    List.of("takes", "int", "parameterized export: expected async()->int, got async(int)->int"),
                    List.of("oracle", "int", "descriptor mismatch: expected async()->int, got async()->null"),
                    List.of("Payload", "null", "export is not a function: expected async()->null"))) {
                NodeResult result = runNodeScript(negativeDir,
                    "invoker_runner.js", c.get(0), c.get(1));
                check(result.exitCode() == 0 && result.output().equals(
                        "INVOKE_FAILURE: " + c.get(2)),
                    c.get(0) + ": the invoker yields the pinned host failure: "
                        + result.output());
            }
        } finally {
            deleteDir(negativeDir);
        }
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
              if ($r.$error.$kind !== "class" || $r.$error.$classname !== "Error") $fail = "error-tags";
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
              "languageVersion": "1.3",
              "backend": "js"
            }""");
        writeFile("js_proj/src/orch_main.deal", """
            import * as console from "std/console";
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
            "{\"languageVersion\": \"1.3\", \"backend\": \"js\"}");
        writeFile("nest_proj/src/app/lib.deal",
            "export function tag(): string { return \"nested-module-ok\"; }");
        writeFile("nest_proj/src/app/main.deal", """
            import * as lib from "./lib";
            import * as console from "std/console";
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

        writeFile("rej_proj/deal.json",
            "{\"languageVersion\": \"1.3\", \"backend\": \"js\","
                + " \"moduleRoots\": [\"src\"], \"externals\": {"
                + " \"ffi\": {\"declaration\": \"src/ffi.d.deal\","
                + " \"nativeLibrary\": \"libhost\"}}}");
        writeFile("rej_proj/src/ffi.d.deal", """
            // @extern-c
            export function cFn(x: int): int;
            """);
        writeFile("rej_proj/src/lib.deal", """
            import * as ffi from "ffi";
            export function use(): int { return ffi.cFn(1); }
            """);
        writeFile("rej_proj/src/rej_main.deal", """
            import * as lib from "./lib";
            export function main(): null { return null; }
            export function run(): int { return 1; }
            """);

        Path entryFile = tmpDir.resolve("rej_proj/src/rej_main.deal").toAbsolutePath();

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
                "E6006".equals(d.code())),
            "the orchestrator reports E6006: " + orchestrator.diagnostics());
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

    private static void testDealJsonBackendAcceptance() throws Exception {
        Path projectDir = null;
        try {
            projectDir = Files.createTempDirectory("deal_js_backend_test_");
            Files.writeString(projectDir.resolve("deal.json"),
                "{\n  \"languageVersion\": \"1.3\",\n  \"backend\": \"js\"\n}\n",
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
                "{\n  \"languageVersion\": \"1.3\",\n"
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

    private static void testCliDefaultOutputBuildJs() throws Exception {
        Path projectDir = null;
        try {
            projectDir = Files.createTempDirectory("deal_js_cli_test_");
            Files.writeString(projectDir.resolve("deal.json"),
                "{\n  \"languageVersion\": \"1.3\",\n  \"backend\": \"js\",\n"
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
     * The GitHub #7 report's exact source: the public CLI case. The
     * absent-terminal-else chain must select the later branch, so the
     * artifact's entry shim prints "pick ok" and exits 0.
     */
    private static final String ELSE_IF_REPRODUCER = """
        import * as console from "std/console";
        function pick(n: int): int {
          let r: int = 0;
          if (n === 1) { r = 10; }
          else if (n === 2) { r = 20; }
          return r;
        }
        export function main(): null {
          if (pick(2) === 20) { console.log("pick ok"); }
          return null;
        }
        """;

    /**
     * The reusable GitHub #7 regression subject: a two-arm else-if
     * chain whose terminal else is absent ({@code terminalElse} false)
     * or present. The exported zero-arity drivers expose the
     * first-branch (10), later-branch (20) and unmatched (0 without the
     * terminal else, 30 with it) results to the artifact runner, so the
     * migrated semantic-IR route can drive the same subject after it
     * replaces this AST route.
     */
    private static String elseIfChainSubject(boolean terminalElse) {
        String elseClause = terminalElse ? "  else { r = 30; }\n" : "";
        return """
            function pick(n: int): int {
              let r: int = 0;
              if (n === 1) { r = 10; }
              else if (n === 2) { r = 20; }
            %s  return r;
            }
            export function firstBranch(): int { return pick(1); }
            export function laterBranch(): int { return pick(2); }
            export function unmatchedBranch(): int { return pick(3); }
            """.formatted(elseClause);
    }

    /**
     * GitHub #7: an else-if chain whose terminal else is absent is valid
     * language syntax and must emit a closed chain that selects the
     * matching branch and leaves the value unchanged when no branch
     * matches. The branch matrix and the final-else control run through
     * the real frontend → JsBackend → node chain; the public
     * release-owned path compiles the issue's exact project with zero
     * diagnostics and executes the published CommonJS artifact under
     * the real node binary.
     */
    private static void testElseIfChainWithoutFinalElse() throws Exception {
        System.out.println("-- Node: else-if chains with an absent terminal else"
            + " (GitHub #7) --");

        if (nodeAvailable) {
            NodeResult noTerminalElse = runDealNode(elseIfChainSubject(false),
                "else-if-no-final-else");
            check(noTerminalElse.exitCode() == 0
                    && noTerminalElse.output().equals("10\n20\n0"),
                "the absent-terminal-else chain returns the first branch"
                    + " (10), the later branch (20) and the unchanged value"
                    + " (0) when no branch matches: exit "
                    + noTerminalElse.exitCode() + ", output '"
                    + noTerminalElse.output() + "'");

            NodeResult terminalElse = runDealNode(elseIfChainSubject(true),
                "else-if-final-else-control");
            check(terminalElse.exitCode() == 0
                    && terminalElse.output().equals("10\n20\n30"),
                "the final-else control keeps first 10, later 20 and the"
                    + " terminal-else unmatched result 30: exit "
                    + terminalElse.exitCode() + ", output '"
                    + terminalElse.output() + "'");
        } else {
            skipNode("else-if chain branch matrix");
        }

        Path projectDir = Files.createTempDirectory("deal_js_elseif_test_");
        try {
            Files.writeString(projectDir.resolve("deal.json"), """
                {
                  "languageVersion": "1.3",
                  "stdlib": "1.3",
                  "moduleRoots": ["src"],
                  "backend": "js",
                  "output": "build/js",
                  "dependencies": {},
                  "externals": {}
                }
                """, StandardCharsets.UTF_8);
            Files.createDirectories(projectDir.resolve("src"));
            Path entry = projectDir.resolve("src/main.deal");
            Files.writeString(entry, ELSE_IF_REPRODUCER,
                StandardCharsets.UTF_8);
            Path diagnosticsJson = projectDir.resolve("diagnostics.json");

            // The public release-owned CLI surface: the release-owned
            // invocation and the whole-project publication, with the
            // source-map sidecars enabled on the same run.
            Path buildDir = Path.of("build").toAbsolutePath().normalize();
            Process process = new ProcessBuilder(
                "java", "-ea", "-cp", buildDir.toString(), "deal.Main",
                "compile", entry.toString(), "--source-map",
                "--diagnostics-json", diagnosticsJson.toString())
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
                check(false, "else-if CLI subprocess timed out");
                return;
            }
            check(process.exitValue() == 0,
                "the public CLI compile of the GitHub #7 project exits 0: "
                    + output);
            check(Files.isRegularFile(diagnosticsJson),
                "the public compile writes the structured diagnostics"
                    + " document");
            if (Files.isRegularFile(diagnosticsJson)) {
                String diag = Files.readString(diagnosticsJson);
                check(diag.contains("\"version\": 1")
                        && diag.contains("\"diagnostics\": []"),
                    "the public compile succeeds with zero diagnostics: "
                        + diag);
            }

            Path outputRoot = projectDir.resolve("build/js");
            Path artifact = outputRoot.resolve("main.js");
            check(Files.isRegularFile(artifact),
                "the published artifact build/js/main.js exists");
            check(Files.isRegularFile(outputRoot.resolve("deal/runtime.js")),
                "the published output deploys deal/runtime.js");

            Path sidecar = outputRoot.resolve("main.deal.map.json");
            check(Files.isRegularFile(sidecar),
                "the --source-map sidecar is written for the chain module");
            if (Files.isRegularFile(sidecar)) {
                String json = Files.readString(sidecar);
                check(json.contains("\"version\": 1")
                        && json.contains("\"source\": \"src/main.deal\"")
                        && json.contains(
                            "\"generated\": \"build/js/main.js\""),
                    "the sidecar keeps the spec version and the project-"
                        + "relative source/generated paths: " + json);
                check(json.contains("\"sourceLine\": 5"),
                    "the sidecar maps the later branch's source line 5: "
                        + json);
            }

            if (nodeAvailable && Files.isRegularFile(artifact)) {
                NodeResult run = runNodeScript(outputRoot, "main.js");
                check(run.exitCode() == 0 && run.output().equals("pick ok"),
                    "the published CommonJS artifact runs under the real"
                        + " node binary, prints \"pick ok\" and exits 0:"
                        + " exit " + run.exitCode() + ", output '"
                        + run.output() + "'");
            } else {
                skipNode("GitHub #7 published artifact");
            }
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
                "{\n  \"languageVersion\": \"1.3\",\n  \"backend\": \"js\",\n"
                    + "  \"moduleRoots\": [\".\"]\n}\n",
                StandardCharsets.UTF_8);
            Path entry = projectDir.resolve("main.deal");
            Files.writeString(entry,
                "import * as c from \"std/console\";\n;\n"
                    + "export function main(): null {\n"
                    + "  c.log(\"deploy\");\n"
                    + "  return null;\n"
                    + "}\n",
                StandardCharsets.UTF_8);

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

    private static void testNoPartialArtifactOnRejection() throws Exception {
        Path projectDir = null;
        try {
            projectDir = Files.createTempDirectory("deal_js_reject_test_");
            Files.writeString(projectDir.resolve("deal.json"),
                "{\n  \"languageVersion\": \"1.3\",\n  \"backend\": \"js\",\n"
                    + "  \"moduleRoots\": [\".\"],\n"
                    + "  \"externals\": {\"ffi\": {\"declaration\":"
                    + " \"ffi.d.deal\", \"nativeLibrary\": \"libhost\"}}\n}\n",
                StandardCharsets.UTF_8);

            Files.writeString(projectDir.resolve("ffi.d.deal"),
                "// @extern-c\nexport function cFn(x: int): int;\n",
                StandardCharsets.UTF_8);
            Path entry = projectDir.resolve("main.deal");
            Files.writeString(entry,
                "import * as ffi from \"ffi\";\n;\n"
                    + "export function main(): null {\n"
                    + "  let v: int = ffi.cFn(1);\n"
                    + "  return null;\n"
                    + "}\n",
                StandardCharsets.UTF_8);

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
                    .anyMatch(d -> "E6006".equals(d.code())),
                "the rejection is E6006: " + orchestrator.diagnostics());
            check(!Files.exists(outputRoot.resolve("main.js")),
                "no entry artifact is written for the rejected module");
            check(!Files.exists(outputRoot.resolve("ffi.js")),
                "no artifact is written for the declaration file");
        } finally {
            deleteDir(projectDir);
        }
    }
}
