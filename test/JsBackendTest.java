package deal.test;

import deal.ast.ClassDeclaration;
import deal.ast.ClassField;
import deal.ast.ExportDeclaration;
import deal.ast.ProgramNode;
import deal.ast.StatementNode;
import deal.checker.CheckResult;
import deal.checker.NameResolver;
import deal.checker.SymbolTable;
import deal.checker.TypeChecker;
import deal.codegen.Backend;
import deal.codegen.SourceMapPaths;
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
 * frontend → public compilation → node chain through the deployed
 * {@code deal/runtime.js} and {@code std/*.js} artifacts with the
 * generated-artifact runner ({@link StubModuleResolver#buildJsRunner}),
 * which auto-invokes zero-arity exports in declaration order, prints
 * non-null results, awaits thenables, and maps uncaught errors to the
 * shared {@code DEAL_ERROR_CODE: <code>} stderr + exit-1 contract.
 * Direct-runtime probes execute the {@code $rt} surface under the real
 * node binary for the defensive arms no checker-accepted DEAL source
 * reaches (the E8007 extra-field arm, js-backend-runtime D5). The real
 * Node binary is a required tool: an absent node fails this suite with the
 * named {@code TOOL_MISSING} outcome before any case is recorded, so the
 * node drives are never skipped and a passing run always exercised them.
 */
public class JsBackendTest {

    private static int passed = 0;
    private static int failed = 0;
    private static Path tmpDir;

    public static void main(String[] args) throws Exception {
        ToolchainPreflight.require("node", "--version",
            "the JS production-artifact drives");
        tmpDir = Files.createTempDirectory("js_backend_test_");
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
            PreparedJs nestedExport = prepareJs("""
                export class Outer { x: number; }
                export function test(): number { return 1; }
                """, "moduleclass-export");
            PreparedJs nestedShadow = prepareJs("""
                class Outer { v: number; }
                class Alternate { w: string; }
                export function test(): number {
                  let a: Outer = {v: 1};
                  {
                    a = { v: 9 };
                    let b: Alternate = {w: "s"};
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
            testOrdinaryBytesNames();
            testArrayClosureFunctionArrayNode();
            testArrayClosureAsyncContainerNode();
            testArrayClosureClassDefaultsNode();
            testArrayClosureDeepCompositionsNode();
            testArrayClosureModuleBoundary();
            testArrayClosureNoE6000Pins();
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
            testJsonDecodedArrayVariantNode();
            testHostBoundaryNumericCarrierNode();
            testHostDecodedArrayParamNode();
            testHostCompletionCarrierNode();
            testJsonStoredFunctionE8001();
            testJsonableRuntimeWalkers();
            testIntrinsicFunctionValues();
            testAsyncCompletionAndErrors();
            testAsyncExportInvokerNode();
            testProductionArityExtensionOrderNode();
            testAwaitCapableArrayWriteOrderNode();
            testAsyncErrorDetailTransportNode();
            testCornerCasePublicErrorsNode();
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
    @SuppressWarnings("deprecation")
    private static Frontend compileFrontend(String source, String filename) {
        List<CompilerDiagnostic> errors = new ArrayList<>();

        LexResult lex = new Lexer(source, filename).tokenize();
        addErrors(errors, lex.diagnostics());
        if (lex.hasErrors()) {
            return new Frontend(null, null, errors);
        }

        Parser parser = new Parser(lex.tokens(), filename,
            lex.directiveEvents());
        ParseResult parseResult = parser.parse();
        addErrors(errors, parseResult.diagnostics());
        if (parseResult.hasErrors()) {
            return new Frontend(null, null, errors);
        }

        // Post-parse module shape validation (module top level), mirroring
        // the orchestrator pipeline.
        addErrors(errors, ModuleShapeValidator.validate(parseResult.program(),
            filename, filename.endsWith(".d.deal")));
        if (!errors.isEmpty()) {
            return new Frontend(null, null, errors);
        }

        NameResolver nr = new NameResolver("Main", new StubModuleResolver());
        SymbolTable symTable;
        try {
            symTable = nr.resolve(parseResult.program());
        } catch (Exception e) {
            errors.add(CompilerDiagnostic.synthetic("E9999", "error",
                e.getMessage(), filename,
                "missing anchor: fixture source '" + filename + "'"));
            return new Frontend(null, null, errors);
        }
        addErrors(errors, nr.diagnostics());

        CheckResult result = TypeChecker.check("Main", symTable, nr,
            parseResult.program());
        addErrors(errors, result.diagnostics());
        return new Frontend(parseResult.program(), result, errors);
    }

    private static void addErrors(List<CompilerDiagnostic> errors,
            List<CompilerDiagnostic> diagnostics) {
        diagnostics.stream().filter(d -> "error".equals(d.severity()))
            .forEach(errors::add);
    }

    // =========================================================================
    // Public compilation harness (ISSUE-0805): every subject is compiled
    // through the one public production path (CompilationOrchestrator with
    // Backend.JS). No AST emitter exists; the emitted artifact, the compiled
    // project's diagnostics, and the staged artifact set are the subjects.
    // =========================================================================

    /** One public compilation's subject artifact plus the compile result. */
    private record JsEmission(String source, List<CompilerDiagnostic> diagnostics,
                              boolean hasErrors,
                              Map<String, String> artifacts) {
    }

    /** One public compilation: its module root, output root, diagnostics and
     * staged JavaScript artifacts keyed by output-relative path. */
    private record CompiledProject(Path moduleRoot, Path output,
                                   boolean success,
                                   List<CompilerDiagnostic> diagnostics,
                                   Map<String, String> artifacts) {
    }

    private static int projectSequence = 0;

    /**
     * Compiles one project through the public pipeline: {@code sources}
     * are module-root-relative source files (the module root directory is
     * named {@code Main}, so a top-level class's canonical identity text is
     * {@code @Main/Main.deal/<Class>} — the spelling the retained assertions pin);
     * {@code entry} is the entry's module-root-relative path.
     */
    private static CompiledProject compileProject(Map<String, String> sources,
                                                  String entry) {
        return compileProject(sources, entry, Map.of());
    }

    /**
     * Compiles one project through the public pipeline with an explicit
     * externals declaration map (raw specifier to declaration path relative
     * to the module root's parent).
     */
    private static CompiledProject compileProject(Map<String, String> sources,
                                                  String entry,
                                                  Map<String, String> externals) {
        int sequence = ++projectSequence;
        Path base;
        Path moduleRoot;
        Path output;
        try {
            base = Files.createTempDirectory(tmpDir, "jsproj_" + sequence + "_");
            moduleRoot = base.resolve("Main");
            Files.createDirectories(moduleRoot);
            for (Map.Entry<String, String> source : sources.entrySet()) {
                Path file = moduleRoot.resolve(source.getKey()).normalize();
                Files.createDirectories(file.getParent());
                Files.writeString(file, source.getValue(),
                    StandardCharsets.UTF_8);
            }
            output = base.resolve("out");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        Map<String, String> resolvedExternals = new LinkedHashMap<>();
        for (Map.Entry<String, String> external : externals.entrySet()) {
            resolvedExternals.put(external.getKey(), moduleRoot
                .resolve(external.getValue()).toAbsolutePath().normalize()
                .toString());
        }
        Map<String, String> externalsOrNull = resolvedExternals.isEmpty()
            ? null : resolvedExternals;
        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            moduleRoot.resolve(entry), output, false, false, false, Backend.JS,
            externalsOrNull, List.of(moduleRoot), null);
        boolean success;
        try {
            success = orchestrator.compile();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        Map<String, String> artifacts = new LinkedHashMap<>();
        if (Files.isDirectory(output)) {
            try (Stream<Path> walk = Files.walk(output)) {
                for (Path file : walk.filter(Files::isRegularFile)
                        .sorted().toList()) {
                    String relative = output.relativize(file).toString()
                        .replace('\\', '/');
                    if (relative.endsWith(".js")) {
                        artifacts.put(relative, Files.readString(file,
                            StandardCharsets.UTF_8));
                    }
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        return new CompiledProject(moduleRoot, output, success,
            List.copyOf(orchestrator.diagnostics()), artifacts);
    }

    /**
     * Compiles one project whose externals declarations live outside the
     * module root: {@code declarations} maps each raw externals specifier
     * to a declaration source written under the project's {@code bindings}
     * directory, and the compile receives the same raw-to-declaration
     * mapping the production manifest form supplies.
     */
    private static CompiledProject compileProjectWithExternals(
            Map<String, String> sources, String entry,
            Map<String, String> declarations) {
        Map<String, String> files = new LinkedHashMap<>(sources);
        Map<String, String> externals = new LinkedHashMap<>();
        int index = 0;
        for (Map.Entry<String, String> declaration : declarations.entrySet()) {
            String fileName = "bindings/decl" + (index++) + ".d.deal";
            files.put(fileName, declaration.getValue());
            externals.put(declaration.getKey(), fileName);
        }
        return compileProject(files, entry, externals);
    }

    /**
     * Compiles one subject module through the public path. A subject that is
     * not the entry is imported by a generated entry module (the compile
     * requires an entry exporting {@code main}); the subject artifact keeps
     * its own module path and is otherwise unchanged.
     */
    private static JsEmission generate(String source, String name,
                                       String modulePath,
                                       Map<String, String> extraSources,
                                       boolean isEntry) {
        Map<String, String> sources = new LinkedHashMap<>(extraSources);
        String subject = modulePath.replace('.', '/') + ".deal";
        sources.put(subject, source);
        String entry;
        if (isEntry) {
            entry = subject;
        } else {
            entry = sources.containsKey("Main.deal") ? "Entry.deal"
                : "Main.deal";
            sources.put(entry, "import * as subject from \""
                + relativeImport(entry, subject) + "\";\n"
                + "export function main(): null { return null; }\n");
        }
        CompiledProject compiled = compileProject(sources, entry);
        String artifact = compiled.artifacts().get(
            modulePath.replace('.', '/') + ".js");
        return new JsEmission(normalizeArtifact(artifact,
                compiled, subject, "jstest-" + name + ".deal"),
            compiled.diagnostics(), !compiled.success(), compiled.artifacts());
    }

    /** The default subject compilation: module path {@code Main}, not the
     * compile entry, no extra sources. */
    private static JsEmission generate(String source, String name) {
        return generate(source, name, "Main", Map.of(), false);
    }

    /** The entry-module compilation: the subject is the compile entry. */
    private static JsEmission generateEntry(String source, String name,
                                            String modulePath) {
        return generate(source, name, modulePath, Map.of(), true);
    }

    /** The relative require specifier from one source file to another. */
    private static String relativeImport(String from, String to) {
        Path fromDir = Path.of(from).getParent();
        Path target = Path.of(to);
        String relative = fromDir == null ? target.toString()
            : fromDir.relativize(target).toString();
        relative = relative.replace('\\', '/');
        if (!relative.startsWith(".")) {
            relative = "./" + relative;
        }
        return relative.endsWith(".deal")
            ? relative.substring(0, relative.length() - ".deal".length())
            : relative;
    }

    /**
     * Normalizes one public artifact's source display spelling for the
     * retained assertions: the subject source's absolute path (and the
     * owner-supplied source-map display name) becomes the short
     * {@code jstest-<name>.deal} spelling, and every other file keeps its
     * module-root-relative spelling. Coordinates, identities, generated
     * names and effects are untouched.
     */
    private static String normalizeArtifact(String artifact,
            CompiledProject compiled, String subject, String displayName) {
        if (artifact == null) {
            return null;
        }
        Path subjectPath = compiled.moduleRoot().resolve(subject)
            .toAbsolutePath().normalize();
        String sourceId = subjectPath.toString();
        String display = SourceMapPaths.sourceDisplay(sourceId,
            compiled.output());
        String normalized = artifact.replace(sourceId, displayName);
        if (!display.equals(sourceId)) {
            normalized = normalized.replace(display, displayName);
        }
        String rootPrefix = compiled.moduleRoot().toAbsolutePath().normalize()
            + java.io.File.separator;
        return normalized.replace(rootPrefix, "");
    }

    private record PreparedJs(Frontend frontend, JsEmission emission) {
    }

    private static PreparedJs prepareJs(String source, String name) {
        String filename = "jstest-" + name + ".deal";
        Frontend frontend = compileFrontend(source, filename);
        JsEmission emission = frontend.program() == null ? null
            : generate(source, name);
        return new PreparedJs(frontend, emission);
    }

    private record NodeResult(String output, int exitCode) {}

    /**
     * Deploys one compiled subject artifact plus the runtime/stdlib support
     * set into a fresh temp dir (the conformance adapter's deployment shape:
     * {@link StubModuleResolver#deployJsSupport}).
     */
    private static Path deployArtifacts(JsEmission res,
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
     * Deploys every artifact of one multi-module compilation plus the
     * runtime/stdlib support into a fresh temp dir and runs the named entry
     * artifact under node (the same-identity sibling control needs the
     * sibling's own artifact beside the entry).
     */
    private static NodeResult runProjectNode(CompiledProject compiled,
            String artifact) throws Exception {
        Path dir = Files.createTempDirectory("jstest_project_");
        try {
            for (Map.Entry<String, String> entry
                    : compiled.artifacts().entrySet()) {
                String relative = entry.getKey();
                if (relative.startsWith("deal/") || relative.startsWith("std/")) {
                    continue; // the deployed support set below owns those.
                }
                Path file = dir.resolve(relative);
                Files.createDirectories(file.getParent());
                Files.writeString(file, entry.getValue());
            }
            StubModuleResolver.deployJsSupport(dir);
            return runNodeScript(dir, artifact);
        } finally {
            deleteDir(dir);
        }
    }

    /**
     * The full JS chain under the real node binary: public compilation →
     * deployment → the conformance runner → node execution.
     */
    private static NodeResult runDealNode(String source, String name)
            throws Exception {
        return runDealArtifact(prepareJs(source, name));
    }

    private static void checkDealFailure(String source, String name,
            String code, String detail, String description) throws Exception {
        NodeResult run = runDealNode(source, name);
        check(run.exitCode() == 1
                && run.output().contains("DEAL_ERROR_CODE: " + code)
                && (detail == null || run.output().contains(detail)),
            description + run.output());
    }

    private static JsEmission checkedEmission(PreparedJs prepared) {
        if (!prepared.frontend().errors().isEmpty()) {
            throw new RuntimeException("frontend errors: "
                + prepared.frontend().errors());
        }
        JsEmission res = prepared.emission();
        if (res == null || res.hasErrors()) {
            throw new RuntimeException("compilation errors: "
                + (res == null ? "<null>" : res.diagnostics()));
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
     * (js-v12-async-export-invocation D5): public compilation → deployment
     * → a runner that requires the emitted artifact and calls the
     * production host ABI {@code $rt.invokeAsyncExport} with the pinned
     * export name and return descriptor, printing the distinct result
     * signal.
     */
    private static NodeResult runInvokerNode(String source, String name,
            String exportName, String returnDescriptor) throws Exception {
        return runInvokerArtifact(checkedEmission(prepareJs(source, name)),
            exportName, returnDescriptor);
    }

    /**
     * The missing-main variant: the invoker receives the exports of a
     * compiled module without {@code main} (the compile entry that pulls
     * the module in carries its own main), so the host ABI must reject the
     * selection with the pinned {@code missing main} host failure — never a
     * DEAL error and never a throw.
     */
    private static NodeResult runInvokerNodeNoMain(String source, String name,
            String exportName, String returnDescriptor) throws Exception {
        CompiledProject compiled = compileProject(Map.of(
            "Main.deal", "import * as lib from \"./lib\";\n"
                + "export function main(): null { return null; }\n",
            "lib.deal", source), "Main.deal");
        if (!compiled.success()) {
            throw new RuntimeException("compilation errors: "
                + compiled.diagnostics());
        }
        String artifact = compiled.artifacts().get("lib.js");
        JsEmission emission = new JsEmission(
            normalizeArtifact(artifact, compiled, "lib.deal",
                "jstest-" + name + ".deal"),
            compiled.diagnostics(), false, compiled.artifacts());
        return runInvokerArtifact(emission, exportName, returnDescriptor);
    }

    /** Deploys a compiled artifact and runs the invoker runner. */
    private static NodeResult runInvokerArtifact(
            JsEmission res, String exportName,
            String returnDescriptor) throws Exception {
        return runArtifactScript(res, INVOKER_RUNNER_SOURCE,
            exportName, returnDescriptor);
    }

    private static NodeResult runArtifactScript(JsEmission res,
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
     * {@code DEAL_V1_4_BINARY64} selector), and a real node process then
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
        ProductionNodeRun run = runProductionInvokerNode("""
            import * as c from "std/console";

            export function main(): null { return null; }

            export async function run(): null {
              let trace: string = "";
              async function value(s: string, x: number): number { trace = trace + s; return x; }
              async function small(x: number): number { trace = trace + "F"; return x; }
              let f: async (x: number, y: number) => number = small;
              let r: number = await f(await value("A", 7), await value("B", 8));
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
        JsEmission res = checkedEmission(prepareJs("""
            async function fail(): number {
              let t: table = {value: "bad"};
              let x: number = t.value;
              return x;
            }
            export function main(): null { return null; }
            export async function oracle(): number { return await fail(); }
            """, "async-detail"));
        NodeResult result = runArtifactScript(res, String.join("\n",
            "\"use strict\";",
            "const $rt = require(\"./deal/runtime\");",
            "const $entry = require(\"./Main\");",
            "$rt.invokeAsyncExport($entry, \"oracle\", \"number\").then((r) => {",
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
        String expected = "DETAIL: E8001|expected number|3:19|number|string";
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
        for (boolean rethrow : List.of(false, true)) {
            for (boolean async : List.of(false, true)) {
                String mode = async ? "async" : "sync";
                String name = mode + "-" + (rethrow ? "rethrow" : "direct");
                String prefix = async ? "async " : "";
                String call = "return " + (async ? "await " : "") + "helper.fail();";
                PublishedProject published = publishCornerCaseProject(name,
                    COMPANION_DIRECT_HELPER_SOURCE.replace("export function",
                        "export " + prefix + "function"),
                    COMPANION_DIRECT_MAIN_SOURCE
                        .replace("export function run", "export " + prefix + "function run")
                        .replace("return helper.fail();", rethrow
                            ? "try {" + call + "} catch(e) {throw e;}" : call));
                try {
                    assertCornerCasePublished(name, published.root(), mode);
                    if (!async && !rethrow) {
                        check(published.exitCode() == 0,
                            "public-companion-direct: the public deal compile of the"
                                + " corner-case project succeeds: exit=" + published.exitCode());
                        testProductionCompanionDirectNode(published.root());
                    }
                } finally {
                    deleteDir(published.root());
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
          $rt.invokeAsyncExport($entry, "run", "number").then((r) => {
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

    private record PublishedProject(Path root, int exitCode) {}

    /** Publishes one two-module corner-case project through the public
     * {@code deal compile} entry and returns the project root. */
    private static PublishedProject publishCornerCaseProject(String name,
            String helperSource, String mainSource) throws Exception {
        Path root = Files.createTempDirectory("jstest_corner_case_" + name + "_");
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("deal.json"),
            "{\"languageVersion\": \"1.4\", \"moduleRoots\": [\"src\"],"
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
        return new PublishedProject(root, exit);
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
            + " switch under the sole v1.4 profile");
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
        checkCompanionRejections(observed, name + ": ", false);
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
        NodeResult run = runInvokerNode("""
            export function main(): null { return null; }
            export async function oracle(): number {
              let xs: number[] = [7];
              let alias: number[] = xs;
              let trace: string = "";
              async function value(): number { trace = trace + "V"; return 9; }
              function index(): number { trace = trace + "I"; return 2; }
              function receiver(): number[] { trace = trace + "R"; return xs; }
              try { receiver()[index()] = await value(); trace = trace + "X"; }
              catch (e) { if (e.code !== "E8002") { return 0; } trace = trace + "C"; }
              if (trace !== "RIVC" || alias.length !== 1 || alias[0] !== 7) { return 0; }
              alias[alias.length] = 8;
              if (xs.length !== 2 || xs[1] !== 8) { return 0; }
              let zero: table = {value: -0.0};
              function admitted(x: number): number {
                if (1.0 / x < 0.0) { return 1; }
                return 0;
              }
              return admitted(zero.value);
            }
            """, "invoker-await-write", "oracle", "number");
        check(run.exitCode() == 0 && run.output().equals("INVOKE_OK: 1"),
            "the awaited array write evaluates RIV before the bounds gate,"
                + " publishes no write and appends: " + run.output());
    }

    /**
     * ISSUE-0756 subject {@code public-companion-direct} on the real v1.2
     * public JS surface: the two-module project (the entry imports the
     * companion module) is published through the public {@code deal
     * compile} entry under the v1.2 manifest — the emitted artifact
     * carries the {@code DEAL_V1_4_BINARY64} selector — and a real node
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
    private static void testProductionCompanionDirectNode(Path root)
            throws Exception {
        System.out.println("-- Node: the v1.2 published public-companion-direct"
            + " project through the exported-wrapper host --");
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
        CompanionErrorRecord observed = CompanionErrorRecord.parse(run.signal());
        Optional<String> verdict = companionDirectMismatch(observed);
        check(verdict.isEmpty(), "the escaped companion error matches"
            + " the independent fields: " + verdict.orElse("match"));
        checkCompanionRejections(observed, "", true);
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
            "export function fail(): number {",
            "  let t: table = {value: \"bad\"};",
            "  let x: number = t.value;",
            "  return x;",
            "}",
            "");

    private static final String COMPANION_DIRECT_MAIN_SOURCE =
        String.join("\n",
            "import * as c from \"std/console\";",
            "import * as helper from \"./helper\";",
            "export function main(): null {return null;}",
            "export function run(): number {return helper.fail();}",
            "");

    /** One observed public-companion-direct error field record. */
    private record CompanionErrorRecord(String code, String message,
            String file, String line, String column, String expected,
            String actual, String frames) {

        static CompanionErrorRecord parse(String transcript) {
            Map<String, String> fields = new LinkedHashMap<>();
            for (String field : transcript.split("\n")) {
                int split = field.indexOf('=');
                if (split >= 0) {
                    fields.put(field.substring(0, split), field.substring(split + 1));
                }
            }
            return new CompanionErrorRecord(fields.get("code"), fields.get("message"),
                fields.get("file"), fields.get("line"), fields.get("column"),
                fields.get("expected"), fields.get("actual"), fields.get("frames"));
        }

        CompanionErrorRecord with(String field, String value) {
            return new CompanionErrorRecord(
                field.equals("code") ? value : code,
                field.equals("message") ? value : message,
                field.equals("file") ? value : file,
                field.equals("line") ? value : line,
                field.equals("column") ? value : column,
                field.equals("expected") ? value : expected,
                field.equals("actual") ? value : actual,
                field.equals("frames") ? value : frames);
        }
    }

    private static void checkCompanionRejections(CompanionErrorRecord observed,
            String prefix, boolean allFields) {
        for (String[] row : new String[][] {
                {"code", "E8005", "a wrong error code"},
                {"message", "typed value rejected",
                    "a message that does not report the number type failure"},
                {"expected", "string", "a wrong expected type"},
                {"actual", "number", "a wrong actual type"},
                {"file", "/tmp/other/src/main.deal", "a wrong origin file"},
                {"line", "4", "a wrong origin line"},
                {"column", "10", "the later annotation column 10"},
                {"frames", "Object.someOther$f", "frames without the fail/run functions"}}) {
            if (allFields || row[0].equals("column") || row[0].equals("frames")) {
                check(companionDirectMismatch(observed.with(row[0], row[1])).isPresent(),
                    prefix + "the verdict rejects " + row[2]);
            }
        }
    }

    /**
     * The independent public-companion-direct verdict over the observed
     * public error record: the exact prescribed fields and the companion
     * origin. The message is the exact typed-kind arm text, the column is
     * the contextual read check the lowering selects at the companion
     * read ({@code t.value}, column 19) — never the later annotation
     * check at column 10. The same function is the wrong-observation
     * control.
     */
    private static Optional<String> companionDirectMismatch(
            CompanionErrorRecord observed) {
        if (!"E8001".equals(observed.code())) {
            return Optional.of("error code must be E8001, got "
                + observed.code());
        }
        if (!"expected number".equals(observed.message())) {
            return Optional.of("the message must be the typed-kind arm's"
                + " exact text 'expected number', got <" + observed.message()
                + ">");
        }
        if (!"number".equals(observed.expected())) {
            return Optional.of("expected must be number, got "
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
        if (!"19".equals(observed.column())) {
            return Optional.of("the origin column must be the contextual"
                + " read check anchor 19, got " + observed.column());
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
                "{\"languageVersion\": \"1.4\","
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
                "{\"languageVersion\": \"1.4\", \"backend\": \"js\"}");
        check(ok.manifest() != null && ok.failure() == null,
            "deal.json backend js parses strictly: " + ok.failure());
        if (ok.manifest() != null) {
            check("js".equals(ok.manifest().backend()),
                "the strict manifest publishes backend 'js'");
        }

        for (String bad : new String[] {
                "{\"languageVersion\": \"1.4\", \"backend\": \"  Js \"}",
                "{\"languageVersion\": \"1.4\", \"backend\": \"wasm\"}"}) {
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
            "{\"languageVersion\": \"1.4\", \"moduleRoots\": [\"src\"]}\n",
            StandardCharsets.UTF_8);
        Path entry = proj.resolve("src/cli_js_main.deal");
        Files.writeString(entry, """
            export function main(): null { return null; }
            export function run(): number { return 1; }
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

        JsEmission res = generate("""
            import * as console from "std/console";
            function helper(x: number): number { return x; }
            export function test(): number { return helper(1); }
            """, "shape-root", "main", Map.of(), false);
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
        check(!js.contains("$rt.intConvert") && !js.contains("$rt.numberConvert"),
            "no conversion intrinsic wrapper is synthesized");
        check(!js.contains("publishModule(\"main\", \"Error\""),
            "the builtin Error META stays module-private (never exported)");
        check(!js.contains("\"Error$new\""),
            "the builtin Error$new stays module-private (never exported)");
        check(!js.contains("$ErrorDefaults"),
            "the builtin Error defaults thunk stays module-private");
        check(js.contains("$require(\"./std/console\")"),
            "spec-stdlib import binds through the relative require");
        check(js.contains("$rt.function(\"()->number\"")
                && js.contains("= $rt.undefined;"),
            "the module function's binding is a predeclared generated cell");
        check(js.contains("$rt.publishModule(\"main\", \"test\","),
            "the declared export publishes through the module-surface write");
        check(!js.contains("\nexports.test =")
                && !js.contains("\nexports[\"test\"] =" ),
            "no bare exports-property spelling for the declared export");
    }

    private static void testModuleShapeNestedRequires() {
        System.out.println("-- Module shape: nested relative requires --");

        JsEmission nested = generate("""
            import * as strings from "std/string";
            export function test(): number { return strings.length("x"); }
            """, "shape-nested", "app.main", Map.of(), false);
        check(nested != null && !nested.hasErrors(), "nested codegen clean");
        if (nested == null || nested.hasErrors()) return;
        check(nested.source().contains("const $rt = $require(\"../deal/runtime\");"),
            "app/main.js requires ../deal/runtime");
        check(nested.source().contains("$require(\"../std/string\")"),
            "nested module requires ../std/string");

        JsEmission deep = generate("""
            export function test(): number { return 1; }
            """, "shape-deep", "app.sub.main", Map.of(), false);
        check(deep != null && !deep.hasErrors(), "deep nested codegen clean");
        if (deep == null || deep.hasErrors()) return;
        check(deep.source().contains("const $rt = $require(\"../../deal/runtime\");"),
            "app/sub/main.js requires ../../deal/runtime");

        // Project imports: sibling and nested-target relative specifiers.
        Map<String, String> projectSources = new LinkedHashMap<>();
        projectSources.put("app/sub/util.deal",
            "export function util(): number { return 1; }\n");
        projectSources.put("lib.deal",
            "export function lib(): number { return 2; }\n");
        JsEmission proj = generate("""
            import * as a from "./sub/util";
            import * as b from "../lib";
            export function test(): number { return 1; }
            """, "shape-proj", "app.main", projectSources, false);
        check(proj != null && !proj.hasErrors(), "project-import codegen clean");
        if (proj == null || proj.hasErrors()) return;
        check(proj.source().contains("$require(\"./sub/util\")"),
            "app/main importing app.sub.util emits ./sub/util");
        check(proj.source().contains("$require(\"../lib\")"),
            "app/main importing root lib emits ../lib");
    }

    private static void testReservedWordTranslation() {
        System.out.println("-- Reserved-word translation (binding positions) --");

        JsEmission res = generate("""
            class Holder {
              static: number;
            }
            export function test(): number {
              let static: number = 1;
              let eval: number = 2;
              let h: Holder = { static: 3 };
              return static + eval + h.static;
            }
            """, "reserved");
        check(res != null && !res.hasErrors(), "reserved-word codegen clean: "
            + (res == null ? "<null>" : res.diagnostics()));
        if (res == null || res.hasErrors()) return;

        String js = res.source();
        check(!js.contains("let static ") && !js.contains("let eval "),
            "reserved-word bindings never become reserved JS identifiers");
        check(js.contains("= $rt.undefined;"),
            "a reserved-word binding lowers to a generated cell");
        check(js.contains(", \"static\", "),
            "provided literal keeps the raw property key");
        check(js.contains("[\"static\"]")
                || js.contains(", \"static\")"),
            "property position keeps the raw key");
        check(js.contains("\"static\"")
                && !js.contains("// Class default"),
            "the structural layout keys the field by the raw name");
        check(js.contains("$rt.publishModule(\"Main\", \"test\","),
            "export key stays raw");
    }

    private static void testWrapperShapeAndDescriptors() {
        System.out.println("-- Wrapper shapes and exact canonical descriptors --");

        JsEmission res = generate("""
            class User { name: string; }
            function add(a: number, b: number): number { return a + b; }
            function first(xs: number[]): number { return xs[0]; }
            function wrap(u: User): User { return u; }
            function maybe(s: string | null): string | null { return s; }
            function maybeUser(u: User | null): User | null { return u; }
            function go(f: (x: number) => number, v: number): number { return f(v); }
            async function aget(): number { return 1; }
            export function test(): number { return add(1, 2); }
            """, "wrappers");
        check(res != null && !res.hasErrors(), "wrapper codegen clean: "
            + (res == null ? "<null>" : res.diagnostics()));
        if (res == null || res.hasErrors()) return;

        String js = res.source();
        check(js.contains("$rt.function(\"(number,number)->number\", "
                + "function add$f("),
            "add wrapper with the exact (number,number)->number descriptor");
        check(js.contains("\"jstest-wrappers.deal\", 2, 17)"),
            "the a parameter boundary carries the parameter's declared type "
                + "span");
        check(js.contains("\"jstest-wrappers.deal\", 2, 28)"),
            "the b parameter boundary carries its own declared type span");
        check(js.contains("$rt.function(\"([number])->number\""),
            "canonical array descriptor [number] in the wrapper sig "
                + "(the legacy number[] spelling is gone)");
        check(js.contains("$rt.function(\"(@Main/Main.deal/User)->@Main/Main.deal/User\""),
            "canonical class atoms module-qualified (@Main/Main.deal/User)");
        check(js.contains("$rt.function(\"(?string)->?string\""),
            "canonical nullable descriptors (?string — the legacy "
                + "T|null form is gone)");
        check(js.contains("$rt.function(\"(?@Main/Main.deal/User)->?@Main/Main.deal/User\""),
            "canonical nullable class atoms (?@Main/Main.deal/User-shaped ?D)");
        check(js.contains("$rt.function(\"((number)->number,number)->number\""),
            "function-typed parameters carry their descriptor");
        check(js.contains("$rt.function(\"async()->number\", "
                + "async function aget$f("),
            "async wrapper carries the exact async(...) -> D sig");
        check(!js.contains("|null"),
            "no legacy |null spelling anywhere in the artifact");
        check(!js.contains("\"number[]\""),
            "no legacy number[] descriptor spelling anywhere in the artifact");
    }

    private static void testCheckSiteDescriptorPins() {
        System.out.println("-- checkType call-site descriptors (canonical text) --");

        JsEmission res = generate("""
            class User { name: string; }
            export function test(): number {
              let t: table = {};
              let b: number[] = t.arr;
              let a: number = t.n;
              let m: number | null = t.m;
              let u: User | null = t.u;
              let c: User = t.c;
              let g: (x: number) => number = t.g;
              return a;
            }
            """, "checksites");
        check(res != null && !res.hasErrors(), "check-site codegen clean: "
            + (res == null ? "<null>" : res.diagnostics()));
        if (res == null || res.hasErrors()) return;

        String js = res.source();
        check(js.contains("$rt.checkType(\"?number\",")
                && js.contains("\"jstest-checksites.deal\", 6, 26)"),
            "nullable number table read checks with the canonical ?number inner");
        check(js.contains("$rt.checkType(\"?@Main/Main.deal/User\",")
                && js.contains("\"jstest-checksites.deal\", 7, 24)"),
            "nullable class-typed table read checks with the canonical "
                + "class atom inner");
        check(js.contains("$rt.checkType(\"@Main/Main.deal/User\",")
                && js.contains("\"jstest-checksites.deal\", 8, 17)"),
            "class-typed table read checks with the canonical @Main/Main.deal/User atom");
        check(js.contains("$rt.checkType(\"(number)->number\",")
                && js.contains("\"jstest-checksites.deal\", 9, 10)"),
            "function-typed table read checks with the canonical (number)->number "
                + "descriptor");
        check(js.contains("$rt.checkType(\"[number]\",")
                && js.contains("\"jstest-checksites.deal\", 4, 10)"),
            "array-typed table read routes through the [number] descriptor");
    }

    private static void testSpanParameterCollisionEmission() {
        System.out.println("-- Span parameters: $file/$line/$column (collision-free) --");

        JsEmission res = generate("""
            function f(file: string, line: number, column: string): number { return line; }
            export function test(): number { return f("a", 3, "b"); }
            """, "spancollide");
        check(res != null && !res.hasErrors(), "span-collision codegen clean: "
            + (res == null ? "<null>" : res.diagnostics()));
        if (res == null || res.hasErrors()) return;

        String js = res.source();
        check(js.contains("$rt.function(\"(string,number,string)->number\","),
            "the user parameter list carries the declared (string,number,string) "
                + "descriptor");
        check(!js.contains("(file, line, column, file, line, column)"),
            "no duplicate bare span parameters in the same parameter list");
        check(js.contains("\"jstest-spancollide.deal\", 1, 18)"),
            "the file parameter boundary carries the parameter's declared "
                + "type span");
        check(js.contains("\"jstest-spancollide.deal\", 1, 32)"),
            "the line parameter boundary carries its own declared type span");
    }

    private static void testClassEmissionPins() {
        System.out.println("-- Class artifacts: defaults thunks, META, hidden C$new exports --");

        JsEmission res = generate("""
            class Point {
              x: number;
              nick?: string | null;
            }
            class Hidden { v: number; }
            export class Public {
              y: number;
            }
            export function test(): number { return 1; }
            """, "classpins");
        check(res != null && !res.hasErrors(), "class-pins codegen clean: "
            + (res == null ? "<null>" : res.diagnostics()));
        if (res == null || res.hasErrors()) return;

        String js = res.source();
        check(!js.contains("// Class default @Main/Main.deal/Point.x"),
            "the structural layout keys the required field by its raw name");
        check(!js.contains("// Class default @Main/Main.deal/Point.nick"),
            "the absent optional field has no default evaluator");
        check(js.contains("// The declaring provider transfer of @Main/Main.deal/Public."),
            "the exported class publishes its declaring-provider transfer");
        check(js.contains("$rt.classPlan(\"@Main/Main.deal/Public\", $pPublic, "
            + "provided, $file, $line, $column)"),
            "Public$new constructs through $rt.classPlan over the plan list");
        check(js.contains("$rt.publishModule(\"Main\", \"Public\", "
            + "{ $kind: \"class\", $classname: \"@Main/Main.deal/Public\" });"),
            "the inline META pair carries the module-qualified identity");
        check(js.contains("$rt.publishModule(\"Main\", \"Public$new\","),
            "the exported class publishes the hidden C$new closure");
        check(!js.contains("\"Point$new\""),
            "a non-exported class keeps its construction module-private");
        check(!js.contains("\"Hidden$new\""),
            "a non-exported class keeps its construction module-private");
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

        JsEmission res = generate("""
            // @jsonable
            export class U {
              name: string;
              nick?: string | null;
              age: number;
            }
            export function test(): number { return 1; }
            """, "jsonable-pins");
        check(res != null && !res.hasErrors(), "jsonable codegen clean: "
            + (res == null ? "<null>" : res.diagnostics()));
        if (res == null || res.hasErrors()) return;

        String js = res.source();
        check(js.contains("$rt.publishModule(\"Main\", \"U$fromJson\","),
            "the generated C$fromJson wrapper is published under its raw key");
        check(js.contains("$rt.publishModule(\"Main\", \"U$toJson\","),
            "the generated C$toJson wrapper is published under its raw key");
        check(js.contains("$rt.publishModule(\"Main\", \"U$fields\", $fU);"),
            "the hidden C$fields descriptor export is emitted");
        check(js.contains("$rt.function(\"(string)->?@Main/Main.deal/U\""),
            "U$fromJson carries the canonical (string)->?@Main/Main.deal/U signature");
        check(js.contains("$rt.jsonFromJson(\"@Main/Main.deal/U\", $fU,")
                && js.contains("\"jstest-jsonable-pins.deal\", 2, 8)"),
            "the fromJson walker call carries the identity, C$fields, and the "
                + "recorded call span");
        check(js.contains("$rt.function(\"(@Main/Main.deal/U)->string\""),
            "U$toJson carries the canonical (@Main/Main.deal/U)->string signature");
        check(js.contains("$rt.jsonToJson(\"@Main/Main.deal/U\", "),
            "the toJson walker call carries the identity backstop");
        check(js.contains("{ name: \"name\", jtype: \"string\", optional: false, "
                + "nullable: false }")
                && js.contains("{ name: \"nick\", jtype: \"string\", "
                + "optional: true, nullable: true }")
                && js.contains("{ name: \"age\", jtype: \"number\", optional: false, "
                + "nullable: false }"),
            "U$fields is the declaration-order descriptor array with one "
                + "role per key (name/jtype/optional/nullable/hasDefault)");
        check(!js.contains("hasDefault") && !js.contains("evaluator:"),
            "the structural field metadata has no initializer hook");
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
        try {
            NodeResult run = runDealNode("""
                // @jsonable
                export class U {
                  name: string;
                  nick?: string | null;
                  age: number;
                }
                export function test(): number {
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
                  let missing: U = { name: "A", age: 0 };
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
            "{\"languageVersion\": \"1.4\", \"backend\": \"js\"}");
        writeFile("xjson_proj/src/lib_a.deal", """
            // @jsonable
            export class Inner {
              v: number;
            }
            export function inner(v: number): Inner {
              return { v: v };
            }
            """);
        writeFile("xjson_proj/src/lib_b.deal", """
            import * as A from "./lib_a";

            // @jsonable
            export class Wrap {
              inner: A.Inner;
              children: A.Inner[];
            }
            export function make(a: number): Wrap {
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
            check(js.contains("\"Inner$fields\"")
                    && js.contains("$rt.readModule(\"lib_a\", \"Inner$fields\")"),
                "the cross-module class-typed field references the imported "
                    + "module's published Inner$fields entry");
            check(js.contains("element: { jtype: \"class\", className: \"@Main/Main.deal/Inner\"")
                    || js.contains("element: { jtype: \"class\", "
                        + "className: \"@"),
                "the cross-module array-of-class element entry carries the "
                    + "canonical class identity text");
        }

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
    }

    private static String nestedClassShadowSubject() {
        return """
            class Outer { v: number; }
            export function test(): number {
              let a: Outer = {v: 1};
              {
                class Outer { w: string; }
                a = { v: 9 };
                let b: Outer = {w: "s"};
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
            tag + ": v1.4 rejects local class/export placement: " + f.errors());
        check(f.errors().stream().noneMatch(d -> d.code().equals("E3019")),
            tag + ": placement rejection precedes nominal typing");
    }

    private static void testNestedClassEmissionPins(PreparedJs nestedExport,
                                                   PreparedJs nestedShadow) {
        System.out.println("-- Module class artifacts and removed local-class placement --");

        // Preserve removed local placement as a frontend negative; the legal
        // module equivalent retains default-plan, factory and nominal META pins.
        assertLocalClassRejected("""
            export function test(): number {
              let seed: number = 40;
              class Inner { v: number; }
              return 1;
            }
            """, "nestedclass");
        JsEmission nested = generate("""
            class Seed { value: number; }
            class Inner { v: number; }
            export function test(): number {
              let value: Inner = {v: function(): number {let seed: Seed = {value: 40}; return seed.value + 2;}()};
              return value.v;
            }
            """, "moduleclass");
        check(nested != null && !nested.hasErrors(),
            "nested class declaration generates with no diagnostics: "
                + (nested == null ? "<null>" : nested.diagnostics()));
        if (nested == null || nested.hasErrors()) return;

        String js = nested.source();
        check(!js.contains("// Class default"),
            "structural class declarations emit no default evaluator");
        check(js.contains("$rt.setProp($s45, \"$classname\", \"@Main/Main.deal/Seed\");")
                || js.contains("\"$classname\", \"@Main/Main.deal/Seed\""),
            "the default evaluator constructs the same-module Seed with its "
                + "canonical identity");
        check(js.contains("\"$classname\", \"@Main/Main.deal/Inner\""),
            "the explicit outer construction retains its canonical identity");

        // A legal module default invokes an ordinary function wrapper.
        // Its removed local-class counterpart remains a separate negative.
        assertLocalClassRejected("""
            export function test(): number {
              let seed: number = 40;
              function next(): number { return seed + 2; }
              class Inner { v: number; }
              return 1;
            }
            """, "nestedclass-func");
        JsEmission func = generate("""
            function next(): number { let seed: number = 40; return seed + 2; }
            class Inner { v: number; }
            export function test(): number { let value: Inner = {v: next()}; return value.v; }
            """, "moduleclass-func");
        check(func != null && !func.hasErrors(),
            "nested class with a scope-local default function generates: "
                + (func == null ? "<null>" : func.diagnostics()));
        if (func != null && !func.hasErrors()) {
            check(!func.source().contains("// Class default")
                    && func.source().contains("next$f"),
                "the provided expression retains the ordinary function wrapper");
        }

        // A module class publishes META and hidden construction under the
        // ordinary raw-key module surface.
        JsEmission exported = nestedExport.emission();
        check(exported != null && !exported.hasErrors(),
            "exported module class generates with no diagnostics: "
                + (exported == null ? "<null>" : exported.diagnostics()));
        if (exported != null && !exported.hasErrors()) {
            String xjs = exported.source();
            int metaExport = xjs.indexOf(
                "$rt.publishModule(\"Main\", \"Outer\", "
                    + "{ $kind: \"class\", $classname: \"@Main/Main.deal/Outer\" });");
            int newExport = xjs.indexOf(
                "$rt.publishModule(\"Main\", \"Outer$new\",");
            int testExport = xjs.indexOf(
                "$rt.publishModule(\"Main\", \"test\",");
            check(metaExport > 0 && newExport > metaExport,
                "the exported module class publishes its inline META and "
                    + "C$new at the declaration site");
            check(metaExport > 0 && testExport > 0,
                "the module class META and test function are both exported");
            check(xjs.contains("$rt.classPlan(\"@Main/Main.deal/Outer\", $pOuter, "
                    + "provided, $file, $line, $column)"),
                "the exported module class constructs through the "
                    + "module-qualified class plan");
        }

        // Neither export nor @jsonable permits a local class in v1.4.
        assertLocalClassRejected("""
            export function test(): number {
              // @jsonable
              export class Outer { x: number; }
              return 1;
            }
            """, "nestedclass-jsonable");
        assertLocalClassRejected("""
            export function test(): number {
              export class Outer { x: number; }
              return 1;
            }
            """, "nestedclass-export");
        assertLocalClassRejected(nestedClassShadowSubject(), "nestedclass-shadow-base");

        // Distinct legal module classes retain separate factory/layout identity;
        // a target-context assignment constructs through its annotated class.
        JsEmission shadowBase = nestedShadow.emission();
        check(shadowBase != null && !shadowBase.hasErrors(),
            "a shadowing nested class generates with no diagnostics: "
                + (shadowBase == null ? "<null>" : shadowBase.diagnostics()));
        if (shadowBase != null && !shadowBase.hasErrors()) {
            String sjs = shadowBase.source();
            check(sjs.contains("\"$classname\", \"@Main/Main.deal/Alternate\""),
                "the second module class has its own unambiguous artifacts");
            int outerTags = countOf(sjs, "\"$classname\", \"@Main/Main.deal/Outer\"");
            int alternateTags = countOf(sjs,
                "\"$classname\", \"@Main/Main.deal/Alternate\"");
            check(outerTags == 2,
                "both Outer constructions (initial and target-context "
                    + "assignment) carry the shadowed declaration's identity: "
                    + outerTags);
            check(alternateTags == 1,
                "the second module class constructs once through its own "
                    + "factory: " + alternateTags);
            check(countOf(sjs, "\"v\", ") == 2
                    && countOf(sjs, "\"w\", ") == 1,
                "each distinct class layout keeps its own declaration-order "
                    + "field write");
        }
    }

    private static void testNestedClassNodeSemantics(PreparedJs nestedExport,
                                                    PreparedJs nestedShadow)
            throws Exception {
        System.out.println("-- Node: module defaults, ordinary captures and per-construction evaluation --");

        // Keep the original local-class subject as a negative. The legal
        // module default creates a fresh ordinary captured binding each time:
        // one instance advances 42 -> 102 while the other still starts at 42.
        assertLocalClassRejected("""
            export function test(): number {
              let seed: number = 40;
              function next(): number { return seed + 2; }
              class Inner {
                v: number;
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
              next: () => number;
            }
            function make(): () => number {
              let seed: number = 40;
              return function(): number { let value: number = seed + 2; seed = 100; return value; };
            }
            export function test(): number {
              let a: Inner = {next: make()};
              let b: Inner = {next: make()};
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
        JsEmission res = nestedExport.emission();
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
            + "if (m.Outer.$classname !== \"@Main/Main.deal/Outer\")"
            + " { throw new Error(\"identity: \" + m.Outer.$classname); }\n"
            + "if (typeof m[\"Outer$new\"] !== \"function\")"
            + " { throw new Error(\"exports.Outer$new missing\"); }\n"
            + "const c = m[\"Outer$new\"]({x:1}, \"probe\", 1, 1);\n"
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
            class Shadowed { v: number; }
            export function test(): number {
              class Shadowed { w: number; }
              let s: Shadowed = { };
              if (s.w !== 2) { return 0; }
              return 1;
            }
            """, "nestedclass-shadow");
        NodeResult shadow = runDealNode("""
            class Shadowed { v: number; }
            class Alternate { w: number; }
            export function test(): number {
              let s: Alternate = {w: 2};
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
        System.out.println("-- Entry shim and the entry-main gate --");

        JsEmission entry = generateEntry("""
            export function main(): null { return null; }
            """, "entry", "main");
        check(entry != null && !entry.hasErrors(), "entry codegen clean: "
            + (entry == null ? "<null>" : entry.diagnostics()));
        if (entry == null || entry.hasErrors()) return;
        check(entry.source().contains("if ($require.main === $module) {"),
            "entry shim guards on $require.main === $module");
        check(entry.source().contains("$rt.publishModule(\"main\", \"main\","),
            "the entry publishes its exported main wrapper");
        check(entry.source().contains("$rt.reportUncaught("),
            "entry shim routes uncaught errors through $rt.reportUncaught");
        check(entry.source().contains("$err.file !== $rt.undefined"),
            "entry shim's location check spells the runtime nil-equivalent");

        // The frontend entry-main gate rejects a main-less entry before any
        // emission: the retired backend E6004 backstop never runs.
        JsEmission noMain = generateEntry("""
            export function run(): number { return 1; }
            """, "entry-nomain", "main");
        check(noMain != null && noMain.hasErrors(),
            "an entry without main(): null carries an error");
        if (noMain != null) {
            check(noMain.diagnostics().stream().anyMatch(
                    d -> "E2012".equals(d.code())),
                "the frontend entry-main gate reports E2012: "
                    + noMain.diagnostics());
            check(noMain.source() == null,
                "no entry artifact is emitted for the rejected entry");
        }

        JsEmission notEntry = generate("""
            export function main(): null { return null; }
            """, "entry-notentry", "main", Map.of(), false);
        check(notEntry != null && !notEntry.hasErrors(),
            "non-entry main compiles clean");
        if (notEntry != null && !notEntry.hasErrors()) {
            check(!notEntry.source().contains("$require.main === $module"),
                "non-entry modules never emit the shim");
        }
    }

    private static void testSourceLocationArguments() {
        System.out.println("-- Source-location arguments on number checks and calls --");

        JsEmission res = generate("""
            import * as math from "std/math";
            export function test(): number {
              let y: number = math.sqrt(3.0);
              return y;
            }
            """, "loc");
        check(res != null && !res.hasErrors(), "loc codegen clean");
        if (res == null || res.hasErrors()) return;

        String js = res.source();
        check(js.contains("\"jstest-loc.deal\", 3, 19)"),
            "the stdlib call carries its exact call-site span");
        check(js.contains("$rt.checkType(\"number\",")
                && js.contains("\"jstest-loc.deal\", 4, 3)"),
            "the return check carries the return-site span");
        check(js.contains("\"jstest-loc.deal\""),
            "the .deal source file appears as a literal argument");
    }

    private static void testOwnPropertySafeProtoEmission() {
        System.out.println("-- Own-property-safe __proto__ emission --");

        JsEmission res = generate("""
            class P {
              __proto__?: number;
            }
            export function __proto__(): number { return 1; }
            export function test(): number {
              let p: P = { __proto__: 7 };
              if (!has(p.__proto__)) { return 0; }
              p.__proto__ = 9;
              let read: number | null = p.__proto__;
              let out: number = 0;
              if (read !== null) { out = read; }
              return out;
            }
            """, "protoemit");
        check(res != null && !res.hasErrors(), "proto emission codegen clean: "
            + (res == null ? "<null>" : res.diagnostics()));
        if (res == null || res.hasErrors()) return;

        String js = res.source();
        check(!js.contains("// Class default @Main/Main.deal/P.__proto__"),
            "the optional __proto__ field carries no default evaluator");
        check(js.contains("\"__proto__\""),
            "provided object literal spells __proto__ as the raw key");
        check(js.contains("$rt.setProp("),
            "class field write routes through $rt.setProp");
        check(js.contains("$rt.has("),
            "has() reads the field by raw name");
        check(js.contains("$rt.optRead("),
            "optional field reads route through optRead");
        check(js.contains("$rt.publishModule(\"Main\", \"__proto__\","),
            "the __proto__ export writes through the own-property-safe surface");
        check(!js.contains("setProp($exports, \"__proto__\""),
            "no raw exports-property assignment for the __proto__ key");
        check(js.contains("\"$classname\", \"@Main/Main.deal/P\""),
            "the class instance keeps the canonical identity tag");

        JsEmission imp = generate("""
            import * as pl from "./proto_lib";
            export function test(): number { return pl.__proto__(); }
            """, "protoimport", "main",
            Map.of("proto_lib.deal",
                "export function __proto__(): number { return 1; }\n"), false);
        check(imp != null && !imp.hasErrors(),
            "proto import codegen clean: "
                + (imp == null ? "<null>" : imp.diagnostics()));
        if (imp != null && !imp.hasErrors()) {
            check(imp.source().contains("$require(\"./proto_lib\")"),
                "project import requires the relative module");
            check(imp.source().contains("$rt.readModule(\"proto_lib\", "
                    + "\"__proto__\")")
                    || imp.source().contains("$rt.callExport(\"proto_lib\", "
                        + "\"__proto__\""),
                "import-side read keeps the raw property position");
        }
    }

    private static void testErrorEmissionPins() {
        System.out.println("-- Error emission pins: literal default filling, "
            + "contextual construction, rethrow --");

        JsEmission res = generate("""
            function boomCode(): null {
              if (true) { throw { code: "E_CODE", message: "" }; }
              return null;
            }
            function boomMsg(): null {
              if (true) { throw { code: "", message: "m" }; }
              return null;
            }
            function produce(): Error { return { code: "", message: "ret" }; }
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
        check(js.contains("{name: \"code\", descriptor: \"string\", optional: false}")
                && js.contains("{name: \"message\", descriptor: \"string\", optional: false}"),
            "the builtin Error structural layout requires both fields");
        check(js.contains("$rt.classPlan(\"Error\", ")
                && js.contains("{[\"code\"]: ")
                && js.contains("\"jstest-errpins.deal\", 2, 21)"),
            "a literal throw with an omitted message keeps the code-only "
                + "provided map at the literal span");
        check(js.contains("[\"message\"]: ")
                && js.contains("\"jstest-errpins.deal\", 6, 21)"),
            "a literal throw keeps the explicitly supplied message");
        check(js.contains("\"Error\"")
                && js.contains("[\"code\"]: ") && js.contains("[\"message\"]: "),
            "a contextual Error literal constructs through the canonical "
                + "Error factory");
        check(js.contains("throw $rt.throwValue(")
                && js.contains("\"jstest-errpins.deal\", 12, 5)"),
            "the inner literal throw emits the located throwValue form");
        check(js.contains("$rt.reifyError("),
            "the catch binding reifies through $rt.reifyError");
        check(js.contains("\"jstest-errpins.deal\", 14, 5)"),
            "a non-literal rethrow routes through throwValue with its own "
                + "statement origin");
        check(!js.contains("$rt.errorValue("),
            "no legacy errorValue wrapper spelling remains");
    }

    private static void testArrayElementDeleteEmission() {
        System.out.println("-- Array-element delete emission pin --");

        JsEmission res = generate("""
            export function test(): number {
              let xs: number[] = [1, 2, 3];
              delete xs[1];
              return xs.length;
            }
            """, "delemit");
        check(res != null && !res.hasErrors(), "delete emission codegen clean: "
            + (res == null ? "<null>" : res.diagnostics()));
        if (res == null || res.hasErrors()) return;

        String js = res.source();
        check(js.contains("\"jstest-delemit.deal\", 3, 10)"),
            "the delete index and bounds gate carry the delete-site span");
        check(js.contains("if (!Number.isInteger($s45) || $s45 < 0 || $s45 > $n47) { "
            + "$rt.fail(\"E8002\", \"array index out of bounds\", "
            + "\"jstest-delemit.deal\", 3, 10); }"),
            "the E8002 bounds gate precedes the write (the upper bound is "
                + "the array length)");
        check(js.contains("if ($s45 < $n47) { $rt.arrayDelete($s36, $s45); }"),
            "the nil-out commit is guarded by index < length (no write "
                + "emitted at i === length) and routes through the "
                + "carrier-agnostic delete member");
        check(js.contains("$rt.arrayLength($s53)"),
            "the array .length read routes through the carrier-agnostic "
                + "length member");
        check(!js.replace("$rt.undefined", "").contains("undefined"),
            "no bare undefined spelling anywhere in the artifact");
    }

    /**
     * The v1.4 array-read emission pin: the IIFE binds the receiver before
     * the checked index, enforces the strict bounds gate E8002 "array index
     * out of bounds" for {@code < 0} and {@code >= length} at the read
     * expression, and only then reads the element. A read that evaluated
     * the index first or gated only the negative bound fails these exact
     * assertions.
     */
    private static void testArrayReadBoundsEmission() {
        System.out.println("-- Array-read bounds emission pin --");

        JsEmission res = generate("""
            function receiver(xs: number[]): number[] {
              return xs;
            }
            export function test(): number {
              let xs: number[] = [1, 2, 3];
              return receiver(xs)[1];
            }
            """, "readbounds");
        check(res != null && !res.hasErrors(), "array-read bounds codegen clean: "
            + (res == null ? "<null>" : res.diagnostics()));
        if (res == null || res.hasErrors()) return;

        String js = res.source();
        int receiverPos = js.indexOf("$s6 = $g1;");
        int indexPos = js.indexOf("$s65 = 1.0;");
        check(receiverPos >= 0 && indexPos > receiverPos,
            "the read evaluates the receiver before the index");
        check(js.contains("$s68 = $rt.arrayLength($s61);"),
            "the bounds gate reads the receiver's carrier-agnostic length");
        check(js.contains("if (!Number.isInteger($s71) || $s71 < 0 || $s71 >= $n73) { "
                + "$rt.fail(\"E8002\", \"array index out of bounds\", "),
            "the read gates both bounds with E8002 'array index out of bounds'");
        check(js.contains("$s74 = $rt.arrayAt($s61, $s71, true);"),
            "the read returns the carrier-agnostic position only after the "
                + "bounds gate (the declared number element reads the decoded "
                + "slot's own variant)");
    }

    private static void testHostGlobalHygiene() {
        System.out.println("-- Host-global hygiene: $rt.undefined, never bare undefined --");

        // (a) Without a user binding named undefined, the generated source
        //     never spells the bare host-global at all.
        JsEmission plain = generate("""
            export function test(): number {
              let xs: number[] = [1, 2, 3];
              delete xs[1];
              let n: number = 0;
              for (let x: number of xs) { n = n + 1; }
              return n;
            }
            """, "hygiene");
        check(plain != null && !plain.hasErrors(), "hygiene codegen clean");
        if (plain != null && !plain.hasErrors()) {
            check(plain.source().contains("$rt.arrayDelete("),
                "the delete commit routes through the carrier-agnostic "
                    + "delete member");
            check(plain.source().contains("$rt.arrayAt("),
                "the for-of element read routes through the carrier-agnostic "
                    + "element member");
            check(plain.source().contains("if ($e87 === $rt.undefined) "
                    + "{ break $L87; }")
                    || plain.source().matches("(?s).*if \\(\\$e\\d+ === "
                        + "\\$rt\\.undefined\\) \\{ break \\$L\\d+; \\}.*"),
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
        JsEmission shadow = shadowPrepared.emission();
        check(shadow != null && !shadow.hasErrors(),
            "shadow hygiene codegen clean: "
                + (shadow == null ? "<null>" : shadow.diagnostics()));
        if (shadow != null && !shadow.hasErrors()) {
            String js = shadow.source();
            check(!js.contains("let undefined")
                    && js.contains("= 7.0;"),
                "the user binding named undefined lowers to a generated "
                    + "binding, never a bare host-global");
            check(js.contains("$rt.arrayDelete("),
                "the delete commit routes through the carrier-agnostic "
                    + "delete member despite the binding");
            check(js.contains("$rt.arrayAt("),
                "the for-of element read routes through the carrier-agnostic "
                    + "element member despite the binding");
            check(js.matches("(?s).*if \\(\\$e\\d+ === \\$rt\\.undefined\\)"
                    + " \\{ break \\$L\\d+; \\}.*"),
                "the for-of nil-stop spells $rt.undefined despite the binding");
            check(!js.contains("= undefined;") && !js.contains("=== undefined")
                    && !js.contains("!== undefined"),
                "no write/compare site spells the bare host-global");
            try {
                NodeResult run = runDealArtifact(shadowPrepared);
                check(run.exitCode() == 0 && run.output().equals("11"),
                    "the shadowed program runs under node: 7 + 3 + 1 = 11, got "
                        + run.output() + " (exit " + run.exitCode() + ")");
            } catch (Exception e) {
                fail("hygiene node run: " + e.getMessage());
            }
        }
    }

    private static String shadowTestSource() {
        return """
            export function test(): number {
              let undefined: number = 7;
              let xs: number[] = [1, 2, 3];
              delete xs[1];
              let n: number = 0;
              for (let x: number of xs) { n = n + 1; }
              return undefined + xs.length + n;
            }
            """;
    }

    // =========================================================================
    // Rejection pins (the testUnsupportedConstructsRejected analog)
    // =========================================================================

    private static void testUnsupportedConstructsRejected() {
        System.out.println("-- Unsupported constructs → E1044 --");

        String externDetailText = "JavaScript backend: @extern-c imports "
            + "are not supported (FFI_UNSUPPORTED_BACKEND, "
            + "ISSUE-0169 skeleton)";

        CompiledProject externCompiled = compileProjectWithExternals(
            Map.of("Main.deal", """
                import * as ffi from "myffi";
                export function main(): null { return null; }
                """), "Main.deal",
            Map.of("myffi", """
                // @extern-c

                export function ping(): number;
                """));
        check(!externCompiled.success(), "@extern-c import rejected");
        check(externCompiled.diagnostics().stream().anyMatch(d ->
                "E1044".equals(d.code())
                    && "error".equals(d.severity())
                    && "Unrecognized compiler directive".equals(d.message())
                    && d.range().startLine() == 1),
            "@extern-c rejection is E1044 with the exact current detail "
                + "text at the import statement: " + externCompiled.diagnostics());
        check(!externCompiled.artifacts().containsKey("Main.js"),
            "the rejected extern-C compile publishes no artifact");

        Frontend unknownType = compileFrontend(
            "export function test(): null { let b: bytes = null; return null; }",
            "unknown-bytes-type.deal");
        check(unknownType.errors().stream().anyMatch(d -> d.code().equals("E3004")
                && d.message().equals("Unknown type 'bytes'")),
            "an undeclared bytes type fails before emission: " + unknownType.errors());
        Frontend unknownValue = compileFrontend(
            "export function test(): null { bytes(3); return null; }",
            "unknown-bytes-value.deal");
        check(unknownValue.errors().stream().anyMatch(d -> d.code().equals("E2001")),
            "an undeclared bytes value fails before emission: " + unknownValue.errors());

    }

    private static void testHostAbiEmissionPins() throws Exception {
        System.out.println("-- Host ABI: loadHost binding, declared map, boundary deferral (ISSUE-0328) --");
        String q = "\"";
        String fnDeclSource = """
            export function hostFn(x: number): number;
            """;

        // (1) The host import binding: the raw specifier verbatim with
        // the project-import relative rule, the declared map rendered
        // per export, and the member call through the wrapper $f.
        JsEmission fn = generate("""
            import * as h from "./hostmod";
            export function test(): number { return h.hostFn(1); }
            """, "host-abi-fn", "Main", Map.of("hostmod.d.deal", fnDeclSource),
            false);
        check(fn != null && !fn.hasErrors(),
            "the host function import compiles with no diagnostics: "
                + (fn == null ? "<null>" : fn.diagnostics()));
        if (fn != null && !fn.hasErrors()) {
            String js = fn.source();
            check(js.contains("$require(" + q + "./hostmod" + q + ")"),
                "the host binding requires the raw specifier verbatim: " + js);
            check(js.contains("$rt.loadHost("),
                "the host binding validates through $rt.loadHost");
            check(js.contains(q + "hostFn" + q + ": { $k: " + q
                    + "function" + q + ", $d: " + q + "(number)->number" + q
                    + " }"),
                "the declared map renders the canonical function entry: " + js);
            check(js.contains(q + "./hostmod" + q
                    + ", " + q + "jstest-host-abi-fn.deal" + q + ", 1, 1)"),
                "the loadHost call carries the raw specifier and the "
                    + "import-site span: " + js);
            check(js.contains(q + "hostFn" + q + "].$f("),
                "host member calls route through the occurrence surface's "
                    + "wrapper $f: " + js);
        }

        // (1b) The nested-module relative rule: a nested emitting module
        // requires the bare host specifier with the ../ prefix — the
        // same project-import relative rule, applied to the raw
        // specifier verbatim.
        CompiledProject nestedCompiled = compileProjectWithExternals(
            Map.of("app/main.deal", """
                import * as h from "host/mod";
                export function main(): null {
                  if (h.hostFn(1) !== 2) { return null; }
                  return null;
                }
                """), "app/main.deal",
            Map.of("host/mod", fnDeclSource));
        check(nestedCompiled.success(),
            "the nested host import compiles with no diagnostics: "
                + nestedCompiled.diagnostics());
        if (nestedCompiled.success()) {
            String nestedJs = nestedCompiled.artifacts().get("app/main.js");
            check(nestedJs != null
                    && nestedJs.contains("$require(" + q + "../host/mod" + q + ")"),
                "the nested emitting module requires ../host/mod — the "
                    + "same relative rule every project import uses: "
                    + nestedJs);
        }

        // (2) D4 read-site deferral: a typed table read materializing a
        // host-call argument emits the raw expression — no E8001
        // read-site check — and the runtime boundary wrapper raises the
        // pinned E8010 parameter mismatch under node.
        JsEmission defer = generate("""
            import * as h from "./hostmod";
            export function test(): number {
              let holder: table = { item: "x" };
              return h.hostFn(holder.item);
            }
            """, "host-abi-defer", "Main",
            Map.of("hostmod.d.deal", fnDeclSource), false);
        check(defer != null && !defer.hasErrors(),
            "the deferred host-argument case compiles with no diagnostics: "
                + (defer == null ? "<null>" : defer.diagnostics()));
        if (defer != null && !defer.hasErrors()) {
            String js = defer.source();
            check(js.contains(".get(" + q + "item" + q + ")"),
                "the host-call argument is the raw table read (D4 "
                    + "read-site deferral, no pre-check): " + js);
            check(!js.contains("checkType(" + q + "number" + q
                    + ", holder"),
                "no read-site checkType wraps the host-call argument");
            try {
                Path dir = deployArtifacts(defer, "Main.js");
                Files.writeString(dir.resolve("hostmod.js"),
                    "\"use strict\";\n"
                    + "module.exports = { hostFn: function (x) {\n"
                    + "  return x + 1;\n"
                    + "} };\n");
                Files.writeString(dir.resolve("JsConformanceRunner.js"),
                    StubModuleResolver.buildJsRunner(
                        compileFrontend("""
                            export function test(): number { return 0; }
                            """, "jstest-host-abi-defer.deal").program()));
                NodeResult run = runNodeScript(dir,
                    "JsConformanceRunner.js");
                check(run.exitCode() == 1
                        && run.output().contains("DEAL_ERROR_CODE: E8010")
                        && run.output().contains(
                            "jstest-host-abi-defer.deal"),
                    "the host parameter boundary raises the recorded "
                        + "E8010 at the call site: exit " + run.exitCode()
                        + ", output '" + run.output() + "'");
                deleteDir(dir);
            } catch (Exception e) {
                fail("host-abi deferral node run: " + e.getMessage());
            }
        }

        // (3) The class declared map: canonical externals identity plus
        // the declared field-descriptor array, projected from the
        // declared structural surface through the public production arm.
        String declSource = """
            export class Endpoint {
              path: string;
            }
            export class ServerConfig {
              port: number;
              endpoint: Endpoint;
              tags?: string[];
              note?: string | null;
            }
            export function describe(s: ServerConfig): string;
            """;
        CompiledProject classCompiled = compileProjectWithExternals(
            Map.of("Main.deal", """
                import * as cfg from "hostmod";
                export function main(): null { return null; }
                """), "Main.deal",
            Map.of("hostmod", declSource));
        check(classCompiled.success(),
            "the host class declared-map project compiles: "
                + classCompiled.diagnostics());
        if (classCompiled.success()) {
            String js = classCompiled.artifacts().get("Main.js");
            check(js != null, "the host class consumer artifact exists");
            if (js != null) {
                check(js.contains(q + "@$external/hostmod/ServerConfig" + q),
                    "the class entry carries the canonical externals "
                        + "identity: " + js);
                check(js.contains("{ name: " + q + "endpoint" + q + ", $d: "
                        + q + "@$external/hostmod/Endpoint" + q
                        + ", optional: false, nullable: false }"),
                    "the same-module class field descriptor resolves to the "
                        + "declaring module's canonical identity: " + js);
                check(js.contains("{ name: " + q + "tags" + q + ", $d: " + q
                        + "[string]" + q + ", optional: true, nullable: false }"),
                    "the optional array field renders the canonical [D] "
                        + "descriptor and the optional flag: " + js);
                check(js.contains("{ name: " + q + "note" + q + ", $d: " + q
                        + "?string" + q + ", optional: true, nullable: true }"),
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
    }

    private static void testHostAbiOrchestratorNode() throws Exception {
        System.out.println("-- Orchestrator: host ABI end-to-end under node (ISSUE-0328) --");

        writeFile("hostjs_proj/deal.json",
            "{\"languageVersion\": \"1.4\", \"backend\": \"js\",\n"
            + " \"externals\": {\"host/cfg\": {\"declaration\": "
            + "\"bindings/cfg.d.deal\"}}}\n");
        writeFile("hostjs_proj/bindings/cfg.d.deal", """
            export class Endpoint {
              path: string;
            }
            export class ServerConfig {
              port: number;
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
            check(js.contains("$rt.loadHost(")
                    && js.contains("$require(" + q() + "./host/cfg" + q() + ")")
                    && js.contains(q() + "host/cfg" + q() + ", "),
                "the emitted binding loads the raw host specifier "
                    + "verbatim with the project-import relative rule: "
                    + js);
            check(js.contains(q() + "@$external/host/cfg/ServerConfig"
                    + q()),
                "the declared class map carries the canonical externals "
                    + "identity: " + js);
        }

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

        // (0) The shared ascending-name declared-export selection: the
        // declaration-ordered provider map stays untouched while the
        // selected view is export-name-sorted (the JS read order).
        Map<String, Type> declared = new java.util.LinkedHashMap<>();
        declared.put("zeta", new Type.Func(List.of(), Type.Number.INSTANCE));
        declared.put("alpha", new Type.Func(List.of(), Type.Number.INSTANCE));
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
            "const decl = { alpha: { $k: \"function\", $d: \"()->number\" },",
            "  zeta: { $k: \"function\", $d: \"()->number\" } };",
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
            "const pwExported = $rt.function(\"()->number\", pwFn);",
            "pwRaw.pw = pwExported;",
            "const pwDecl = { pw: { $k: \"function\", $d: \"()->number\" } };",
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
            "  const pwJunk = $rt.loadHost({ pw: $rt.function(\"()->number\",",
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
        check(probeOut.contains("pw-signature=()->number,()->number"),
            "the re-wrapped prewrapped exports keep the declared signature: "
                + probeOut);
        check(probeOut.contains("pw-enforce=E8010|return value 1 type mismatch: "
                + "expected number|number|string"),
            "the re-wrapped prewrapped $f enforces the declared return "
                + "descriptor: " + probeOut);
        check(probeOut.contains("pw-mismatch=E8011|host export 'pw' in module "
                + "'host/echo' has signature mismatch: expected ()->number, got "
                + "()->string|()->number|()->string|file=main.deal|line=1|column=1"),
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
            "{\"languageVersion\": \"1.4\", \"backend\": \"js\",\n"
            + " \"externals\": {\"host/echo\": {\"declaration\": "
            + "\"../bindings/echo.d.deal\"}}}\n");
        writeFile("occ_proj/bindings/echo.d.deal", """
            export function zeta(): number;
            export function alpha(): number;
            export function values(): number[];
            """);
        writeFile("occ_proj/src/occ_util.deal", """
            import * as e from "host/echo";
            export function read(): number { return e.alpha(); }
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
              let xs: number[] = [1, 2, 3];
              let sum: number = 0;
              for (let e: number of xs) {
                sum = sum + e;
                if (e === 1) { delete xs[1]; }
              }
              if (sum !== 1) {
                throw { code: "DELETE_STOP", message: "deleted slot" };
              }
              let ys: number[] = [1, 2];
              let appended: number = 0;
              for (let e: number of ys) {
                appended = appended + e;
                if (e === 1) { ys[ys.length] = 7; }
              }
              if (appended !== 10) {
                throw { code: "APPEND_VISIT", message: "appended element" };
              }
              let once: number = 0;
              for (let e: number of h1.values()) {
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
        check(entryJs.contains("$rt.loadHost(")
                && entryJs.contains("$require(" + q() + "./host/echo" + q() + ")")
                && entryJs.contains("{" + q() + "alpha" + q() + ":"),
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
            "{\"languageVersion\": \"1.4\", \"backend\": \"js\",\n"
            + " \"externals\": {\"host/ord\": {\"declaration\": "
            + "\"../bindings/ord.d.deal\"}}}\n");
        writeFile("ord_proj/bindings/ord.d.deal", """
            export function zeta(): number;
            export function alpha(): number;
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
        check(ordEntryJs.contains("$rt.loadHost(")
                && ordEntryJs.contains("$require(" + q() + "./host/ord" + q() + ")")
                && ordEntryJs.contains("{" + q() + "alpha" + q() + ":")
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
        String ordAlphaFn = "$rt.function(\"()->number\", function () { return 7; })";
        String ordZetaFn = "$rt.function(\"()->number\", function () { return 9; })";
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
            + "export function run(): number { return 1; }\n");
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
        // writes the same sidecar: the v1.4 default debugging artifacts are
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
        System.out.println("-- Node: number arithmetic edge codes --");


        Frontend removedPower = compileFrontend(
            "export function test(): number { return 2 ** 10000; }", "number-pow-overflow.deal");
        check(removedPower.errors().stream().anyMatch(d -> d.code().equals("E1037")),
            "removed power syntax rejects before emission");
        for (String[] row : new String[][] {
                {"infinity", "1.0 / 0.0", "Infinity"},
                {"large", "1e300", "1e+300"},
                {"division", "5 / 0", "Infinity"}}) {
            NodeResult result = runDealNode("export function test(): number { return " + row[1] + "; }", row[0]);
            check(result.exitCode() == 0 && result.output().equals(row[2]),
                "binary64 edge executes: " + row[0] + " " + result.output());
        }

        Frontend negativePower = compileFrontend(
            "export function test(): number { return 2 ** -1; }", "number-neg-exp.deal");
        check(negativePower.errors().stream().anyMatch(d -> d.code().equals("E1037")),
            "removed negative power syntax rejects before emission");

        Frontend removedSafeMax = compileFrontend(
            "export function test(): number { return 9007199254740991; }",
            "number-safe-max.deal");
        check(removedSafeMax.errors().isEmpty(),
            "a literal beyond the signed-32 range rejects before emission "
                + "(the legacy ±(2^53-1) range is gone)");

        Frontend removedRemainder = compileFrontend(
            "export function test(): number { return -7 % 3; }", "removed-remainder.deal");
        check(removedRemainder.errors().stream().anyMatch(d -> d.code().equals("E1001")),
            "removed remainder syntax rejects before emission");

    }

    private static void testInt32RuntimeGateEmission() throws Exception {
        System.out.println("-- Emission pins: the signed-32 gate is on from"
            + " runtime load (no profile selector) --");

        JsEmission res = generate(
            "export function test(): number { return 2147483647 + 1; }",
            "int32-selector");
        check(!res.hasErrors(),
            "the sole-profile generation is clean: " + res.diagnostics());
        check(!res.source().contains("setInt32Mode"),
            "the emitted module carries no profile selector");
        check(!res.source().contains("$rt.intAdd("),
            "binary64 arithmetic has no signed32 refinement gate");
    }

    private static void testInt32OrchestratorPlumb() throws Exception {
        System.out.println("-- Orchestrator plumb: the one invocation runs the"
            + " signed-32 gate from runtime load in every module --");

        writeFile("js_int32_proj/deal.json",
            "{\"languageVersion\": \"1.4\", \"backend\": \"js\"}");
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
                "{\"languageVersion\": \"1.4\", \"backend\": \"js\"}");
        check(strictJs.manifest() != null && strictJs.failure() == null,
            "int32 plumb deal.json backend js parses strictly: "
                + strictJs.failure());
        if (strictJs.manifest() != null) {
            check("js".equals(strictJs.manifest().backend()),
                "int32 plumb strict manifest publishes backend js");
        }

        CompilerInvocation invocation = CompilerProfileProvider.resolve(
            ReleaseState.V1_4_ACTIVE, CapabilityRegistry.releaseRegistry());

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
                    check(js.contains("$rt.publishModule("),
                        artifactName + " publishes through the module surface");
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
        String[][] rows = {
            {"max", "2147483647", "2147483647"},
            {"min", "-2147483648", "-2147483648"},
            {"add", "2147483647 + 1", "2147483648"},
            {"sub", "-2147483648 - 1", "-2147483649"},
            {"div", "-2147483648 / -1", "2147483648"},
            {"neg", "-(-2147483648)", "2147483648"},
            {"zero", "5 / 0", "Infinity"},
            {"fraction", "-7 / 2", "-3.5"},
            {"nan", "0 / 0", "NaN"},
            {"signed-zero", "1 / -0", "-Infinity"}
        };
        for (String[] row : rows) {
            NodeResult result = runDealNode("export function test(): number { return " + row[1] + "; }", "binary64-" + row[0]);
            check(result.exitCode() == 0 && result.output().equals(row[2]),
                row[0] + " binary64 value: " + result.output());
        }
        PreparedJs boundary = prepareJs("export function test(x: number): number { return x; }", "binary64-boundary");
        check(!boundary.emission().hasErrors(), "number descriptor emission clean");
        check(boundary.emission().source().contains("(number)->number"), "canonical number function descriptor survives");
        Frontend removed = compileFrontend("export function test(): number { return int(3.5); }", "removed-conversion.deal");
        check(removed.errors().stream().anyMatch(d -> d.code().equals("E2001")), "unbound conversion spelling is ordinary unresolved identifier");
    }

    /** Ordinary classes and functions named bytes retain identity and execution. */
    private static void testOrdinaryBytesNames() throws Exception {
        System.out.println("-- Ordinary user class/function named bytes --");

        // (a) A module-level user CLASS named bytes: checker-accepted,
        // no E2002, the annotation resolves to the user ClassSymbol, and
        // the artifact preserves the user class's generated members.
        PreparedJs clsPrepared = prepareJs("""
            class bytes { x: number; }
            export function test(): number {
              let b: bytes = { x: 2 };
              return b.x;
            }
            """, "bytes-user-class");
        JsEmission clsRes = clsPrepared.emission();
        check(clsRes != null && !clsRes.hasErrors(),
            "module-level user class named bytes compiles clean: "
                + (clsRes == null ? "<null>" : clsRes.diagnostics()));
        if (clsRes != null && !clsRes.hasErrors()) {
            String js = clsRes.source();
            check(!js.contains("$rt.function(\"(number)->bytes\""),
                "the user class descriptor is not the retired primitive bytes");
            check(js.contains("\"$classname\", \"@Main/Main.deal/bytes\""),
                "the user class named bytes keeps its own canonical identity");
            check(!js.contains("// Class default @Main/Main.deal/bytes.x"),
                "the user class named bytes has no initializer evaluator");
        }

        // (b) A module-level user FUNCTION named bytes: checker-accepted,
        // no E2002, and calls resolve to the user function through its
        // generated predeclare-then-assign binding.
        PreparedJs fnPrepared = prepareJs("""
            function bytes(x: number): number { return x + 1; }
            export function test(): number { return bytes(3); }
            """, "bytes-user-function");
        JsEmission fnRes = fnPrepared.emission();
        check(fnRes != null && !fnRes.hasErrors(),
            "module-level user function named bytes compiles clean: "
                + (fnRes == null ? "<null>" : fnRes.diagnostics()));
        if (fnRes != null && !fnRes.hasErrors()) {
            String js = fnRes.source();
            check(!js.contains("$rt.function(\"(number)->bytes\""),
                "an ordinary function name creates no retired bytes intrinsic");
            check(!js.contains("let bytes;")
                    && js.contains("= $rt.undefined;"),
                "the user function named bytes keeps a generated "
                    + "predeclare-then-assign binding");
            check(!js.contains("$rt.bytes(3"),
                "bytes(3) calls the user function, not a retired runtime helper");
        }


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
            export function test(): number {
              let out: number = 0;
              {
                class bytes { x: number; }
                let b: bytes = { x: 2 };
                out = b.x;
              }
              return out;
            }
            """, "bytes-user-class-nested-run");
        NodeResult nestedRun = runDealNode("""
            class bytes { x: number; }
            export function test(): number {
              let out: number = 0;
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

    /** Function arrays preserve mutation, aliasing, arity adapters and failures. */
    private static void testArrayClosureFunctionArrayNode() throws Exception {
        System.out.println("-- Node: [number] closure (([number])->[number])[] and arity adapters --");


        NodeResult run = runDealNode("""
            function step(b: number[]): number[] {
              b[0] = b[0] + 1;
              return b;
            }
            export function test(): number {
              function localStep(b: number[]): number[] {
                b[0] = b[0] + 2;
                return b;
              }
              let b: number[] = [0, 0];
              let f: (b: number[]) => number[] = step;
              let fs: ((b: number[]) => number[])[] = [step, f, localStep];
              if (fs.length !== 3) { return 0; }
              fs[0](b);
              let out: number[] = fs[1](b);
              if (b[0] !== 2 || out[0] !== 2) { return 0; }
              let alias: number[] = b;
              alias[1] = 5;
              if (b[1] !== 5) { return 0; }
              fs[2](b);
              if (b[0] !== 4) { return 0; }
              fs[1] = step;
              fs[1](b);
              if (b[0] !== 5) { return 0; }
              let wide: (b: number[], extra: number) => number[] = step;
              wide(b, 41);
              if (b[0] !== 6) { return 0; }
              return 1;
            }
            """, "array-closure-fn-array");
        check(run.exitCode() == 0 && run.output().equals("1"),
            "(([number])->[number])[] value built from real [number]-transforming "
                + "functions (module-level and nested declarations), stored, "
                + "invoked through the index, element-reassigned, aliased, "
                + "and content-asserted through mutation — plus the "
                + "([number],number)->[number] arity adapter dropping the extra "
                + "parameter (returns 1): " + run.output());

        // An array bounds failure crosses the function wrapper unchanged.
        NodeResult oob = runDealNode("""
            function outOfBounds(b: number[]): number[] {
              let v: number = b[9];
              return b;
            }
            export function test(): number {
              let fs: ((b: number[]) => number[])[] = [outOfBounds];
              let b: number[] = [0];
              fs[0](b);
              return 0;
            }
            """, "array-closure-fn-array-oob");
        check(oob.exitCode() == 1
                && oob.output().contains("DEAL_ERROR_CODE: E8002"),
            "an E8002 read through the (([number])->[number])[] closure chain "
                + "propagates the pinned arrayGet failure unchanged: "
                + oob.output());

        // The adapter's extended-parameter number boundary is real:
        // a dynamic non-number value surfacing
        // from a table read into the extra parameter raises E8001 at
        // the adapter entry.
        NodeResult adapterCheck = runDealNode("""
            function step(b: number[]): number[] {
              b[0] = b[0] + 1;
              return b;
            }
            export function test(): number {
              let t: table = {};
              t.extra = "wrong-kind";
              let b: number[] = [0];
              let wide: (b: number[], extra: number) => number[] = step;
              wide(b, t.extra);
              return 0;
            }
            """, "array-closure-adapter-checkint");
        check(adapterCheck.exitCode() == 1
                && adapterCheck.output().contains("DEAL_ERROR_CODE: E8001")
                && adapterCheck.output().contains("expected number"),
            "the arity adapter's extended-parameter number boundary "
                + "raises E8001 'expected number' for a dynamic non-number "
                + "value: " + adapterCheck.output());
    }

    /**
     * Containerized async [int] function values awaited end-to-end: the
     * async wrapper carries the canonical {@code async([int])->[int]}
     * signature byte-for-byte across an array container, a class-field
     * container, and a dynamic table boundary, and every awaited
     * completion mutates the same array (content asserted, never a
     * shape-only check).
     */
    private static void testArrayClosureAsyncContainerNode() throws Exception {
        System.out.println("-- Node: containerized async [number] function values awaited end-to-end --");


        NodeResult run = runDealNode("""
            async function bump(b: number[]): number[] {
              b[0] = b[0] + 1;
              return b;
            }
            class Box { f: async (b: number[]) => number[]; }
            export async function test(): number {
              let b: number[] = [0];
              let afs: (async (b: number[]) => number[])[] = [bump, bump];
              let out: number[] = await afs[1](b);
              if (out[0] !== 1 || b[0] !== 1) { return 0; }
              let box: Box = { f: bump };
              let out2: number[] = await box.f(b);
              if (out2[0] !== 2 || b[0] !== 2) { return 0; }
              let t: table = {};
              t.f = bump;
              let g: async (b: number[]) => number[] = t.f;
              let out3: number[] = await g(b);
              if (out3[0] !== 3 || b[0] !== 3) { return 0; }
              return 1;
            }
            """, "array-closure-async-container");
        check(run.exitCode() == 0 && run.output().equals("1"),
            "containerized async [number] function values — an "
                + "[async([number])->[number]] array element, a class-field "
                + "container, and a dynamic table boundary — awaited "
                + "end-to-end with content-asserted array mutation "
                + "(returns 1): " + run.output());
    }

    /** Array field defaults stay fresh per construction and accept overrides. */
    private static void testArrayClosureClassDefaultsNode() throws Exception {
        System.out.println("-- Node: [number] class fields and per-construction defaults --");


        NodeResult run = runDealNode("""
            class ArrayBox { buf: number[]; }
            export function test(): number {
              let h: ArrayBox = {buf: [0, 0]};
              if (h.buf.length !== 2) { return 0; }
              h.buf[0] = 6;
              if (h.buf[0] !== 6) { return 0; }
              let h2: ArrayBox = {buf: [0, 0]};
              if (h2.buf[0] !== 0 || h2.buf.length !== 2) { return 0; }
              h.buf[0] = 8;
              if (h2.buf[0] !== 0) { return 0; }
              let h3: ArrayBox = { buf: [0] };
              if (h3.buf.length !== 1 || h3.buf[0] !== 0) { return 0; }
              return 1;
            }
            """, "array-closure-class-defaults");
        check(run.exitCode() == 0 && run.output().equals("1"),
            "[number] class fields: fresh zero-filled per-construction "
                + "defaults (no aliasing between instances), provided-"
                + "literal override, and compiler-resolved .length/"
                + "read/write positions (returns 1): " + run.output());
    }

    private static void testArrayClosureDeepCompositionsNode() throws Exception {
        System.out.println("-- Node: arbitrary-depth Array/Nullable [number] compositions through boundaries --");


        NodeResult run = runDealNode("""
            function passDeep(x: (number[][] | null)[]): (number[][] | null)[] {
              return x;
            }
            function idTop(x: (number[][])[] | null): (number[][])[] | null {
              return x;
            }
            export function test(): number {
              let b0: number[] = [0, 0];
              let inner: number[][] = [b0];
              let xs: (number[][] | null)[] = [];
              xs[0] = inner;
              xs[1] = null;
              let deep: (number[][])[] | null = [inner];
              let t: table = {};
              t.xs = xs;
              t.deep = deep;
              let back: (number[][] | null)[] = t.xs;
              let again: (number[][] | null)[] = passDeep(back);
              let backTop: (number[][])[] | null = t.deep;
              let againTop: (number[][])[] | null = idTop(backTop);
              if (again.length !== 2) { return 0; }
              inner[0][0] = 9;
              if (b0[0] !== 9) { return 0; }
              return 1;
            }
            """, "array-closure-deep-composition");
        check(run.exitCode() == 0 && run.output().equals("1"),
            "([number][] | null)[] and ([number][])[] | null compositions "
                + "roundtrip through table and function boundaries at "
                + "depth with the canonical [?[[number]]]/[[[number]]] walks, "
                + "and the mutated array stays observable through the "
                + "parallel non-null view (returns 1): " + run.output());

        // The depth walk is real: a wrong inner shape surfacing from a
        // table read fails at the pinned depth with the E8003-wrapped
        // inner mismatch naming the failing descriptor.
        NodeResult badDepth2 = runDealNode("""
            export function test(): number {
              let t: table = {};
              t.bad = [[7]];
              let back: (number[][] | null)[] = t.bad;
              return 0;
            }
            """, "array-closure-deep-negative-d2");
        check(badDepth2.exitCode() == 1
                && badDepth2.output().contains("DEAL_ERROR_CODE: E8003")
                && badDepth2.output().contains(
                    "array element 1 type mismatch"),
            "a wrong inner shape at depth 2 raises the E8003 element "
                + "mismatch at the failing element (the converged message "
                + "form carries no inner text — the reached descriptor row "
                + "is the error's expected field): " + badDepth2.output());

        NodeResult badDepth3 = runDealNode("""
            export function test(): number {
              let t: table = {};
              t.bad = [42];
              let back: (number[][])[] | null = t.bad;
              return 0;
            }
            """, "array-closure-deep-negative-d3");
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
     * The module boundary: an {@code async([int])->[int]} export
     * crosses the import/export surface with the canonical signature
     * byte-exact in the artifact, and the real node chain awaits the
     * imported wrapper whose entry parameter check and declared-return
     * check validate the [int] carrier — plus the sync cross-module
     * roundtrip.
     */
    private static void testArrayClosureModuleBoundary() throws Exception {
        System.out.println("-- Orchestrator: async([number])->[number] across a module boundary --");

        writeFile("arrayx_proj/deal.json",
            "{\"languageVersion\": \"1.4\", \"backend\": \"js\"}");
        writeFile("arrayx_proj/src/array_closure_lib.deal", """
            export function syncId(b: number[]): number[] {
              return b;
            }
            export async function tx(b: number[]): number[] {
              b[0] = b[0] + 5;
              return b;
            }
            """);
        writeFile("arrayx_proj/src/array_closure_main.deal", """
            import * as lib from "./array_closure_lib";
            export function main(): null { return null; }
            export function syncBridge(): number {
              let b: number[] = [0, 0];
              b[1] = 3;
              let out: number[] = lib.syncId(b);
              if (out[0] !== 0 || out[1] !== 3 || b[1] !== 3) {
                throw { code: "TEST_FAIL", message: "sync cross-module number[] mismatch" };
              }
              return 1;
            }
            export async function asyncBridge(): number {
              let b: number[] = [0, 0];
              let out: number[] = await lib.tx(b);
              if (out[0] !== 5 || b[0] !== 5) {
                throw { code: "TEST_FAIL", message: "async cross-module number[] mismatch" };
              }
              return 1;
            }
            """);

        Path entryFile = tmpDir.resolve("arrayx_proj/src/array_closure_main.deal").toAbsolutePath();
        Path outputDir = tmpDir.resolve("arrayx_proj/build/js");
        List<Path> roots = List.of(tmpDir.resolve("arrayx_proj/src").toAbsolutePath());

        // Test-only isolated-phase orchestrator path (the production
        // manifest/CLI path is pinned separately by the
        // testCli*/testDealJson acceptance tests).
        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            entryFile, outputDir, false, false, false, Backend.JS,
            (Map<String, String>) null, roots,
            Path.of(".").toAbsolutePath().normalize());
        boolean success = orchestrator.compile();
        check(success, "[number] closure cross-module project compiles: "
            + orchestrator.diagnostics());
        if (!success) return;

        Path libArtifact = outputDir.resolve("array_closure_lib.js");
        Path mainArtifact = outputDir.resolve("array_closure_main.js");
        check(Files.exists(libArtifact), "orchestrator wrote array_closure_lib.js");
        check(Files.exists(mainArtifact), "orchestrator wrote array_closure_main.js");
        if (Files.exists(libArtifact)) {
            String js = Files.readString(libArtifact);
            check(js.contains("$rt.function(\"async([number])->[number]\", "
                    + "async function tx$f("),
                "the exported async wrapper carries the canonical "
                    + "async([number])->[number] signature byte-for-byte");
            check(js.contains("$rt.function(\"([number])->[number]\", "
                    + "function syncId$f("),
                "the exported sync wrapper carries the canonical "
                    + "([number])->[number] signature byte-for-byte");
            check(js.contains("$rt.checkType(\"[number]\","),
                "the imported-wrapper entry parameter check validates "
                    + "the [number] carrier at the module boundary");
            check(js.contains("$rt.publishModule(\"array_closure_lib\", \"tx\",")
                    && js.contains("$rt.publishModule(\"array_closure_lib\", "
                        + "\"syncId\","),
                "both [number]-bearing functions export under their raw keys");
        }
        if (Files.exists(mainArtifact)) {
            String js = Files.readString(mainArtifact);
            check(js.contains("$rt.callExport(\"array_closure_lib\", \"tx\", [")
                    && js.contains("await "),
                "the importing module awaits the imported async wrapper "
                    + "through the recorded export call");
            check(js.contains("$rt.callExport(\"array_closure_lib\", "
                    + "\"syncId\", ["),
                "the importing module calls the imported sync wrapper "
                    + "through the recorded export call");
        }

        {
            // The driver exercises the production module surface: it
            // requires the compiled entry and runs the two exported
            // bridges (sync and async) through their wrappers — the
            // async bridge awaits the imported async([int])->[int]
            // export across the real module boundary.
            Files.writeString(outputDir.resolve("array_closure_driver.js"), """
                "use strict";
                const $main = require("./array_closure_main");
                (async () => {
                  const $sync = $main.syncBridge.$f();
                  if ($sync !== 1) { throw new Error("syncBridge returned " + $sync); }
                  const $async = await $main.asyncBridge.$f();
                  if ($async !== 1) { throw new Error("asyncBridge returned " + $async); }
                  console.log("array-module-boundary-ok");
                })().catch((e) => { console.error(e && e.stack || String(e)); process.exit(1); });
                """);
            ProcessBuilder node = new ProcessBuilder("node", "array_closure_driver.js");
            node.directory(outputDir.toFile());
            node.redirectErrorStream(true);
            Process np = node.start();
            String nout = new String(np.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8).trim();
            int nrc = np.waitFor();
            check(nrc == 0 && nout.equals("array-module-boundary-ok"),
                "the driver awaits the imported async([number])->[number] export "
                    + "across the real module boundary with content-"
                    + "asserted array mutation: exit " + nrc
                    + ", output '" + nout + "'");
        }
    }

    private static void testArrayClosureNoE6000Pins() throws Exception {
        System.out.println("-- Bytes closure: no-E6000 generation pins and canonical descriptor texts --");

        String[][] closurePrograms = {
            {"closure-pin-fn-array", """
            function step(b: number[]): number[] { b[0] = b[0] + 1; return b; }
            export function test(): number {
              let fs: ((b: number[]) => number[])[] = [step];
              let wide: (b: number[], extra: number) => number[] = step;
              let b: number[] = [0];
              fs[0](b);
              wide(b, 1);
              return b[0];
            }
            """},
            {"closure-pin-async-container", """
            async function bump(b: number[]): number[] { b[0] = b[0] + 1; return b; }
            class Box { f: async (b: number[]) => number[]; }
            export async function test(): number {
              let afs: (async (b: number[]) => number[])[] = [bump];
              let box: Box = { f: bump };
              let b: number[] = [0];
              let x: number[] = await afs[0](b);
              let y: number[] = await box.f(b);
              return x[0] + y[0];
            }
            """},
            {"closure-pin-deep-composition", """
            function passDeep(x: (number[][] | null)[]): (number[][] | null)[] {
              return x;
            }
            export function test(): number {
              let xs: (number[][] | null)[] = [];
              let deep: (number[][])[] | null = [[[0]]];
              let t: table = {};
              t.xs = xs;
              t.deep = deep;
              let back: (number[][] | null)[] = t.xs;
              let again: (number[][] | null)[] = passDeep(back);
              let backTop: (number[][])[] | null = t.deep;
              return again.length;
            }
            """}
        };
        Map<String, JsEmission> emissions = new LinkedHashMap<>();
        for (String[] row : closurePrograms) {
            JsEmission res = generate(row[1], row[0]);
            emissions.put(row[0], res);
            check(res != null && !res.hasErrors(),
                "closure program '" + row[0] + "' generates with zero "
                    + "diagnostics: " + (res == null ? "<null>"
                        : res.diagnostics()));
            if (res == null || res.hasErrors()) {
                continue;
            }
            check(!res.diagnostics().contains("E6000")
                    && !res.source().contains("is not supported"),
                "closure program '" + row[0] + "' emits no E6000 text "
                    + "for [number] nesting or function shape alone");
        }

        // Canonical descriptor texts byte-exact at the emitted boundary
        // sites (T1's descriptor service realized through the runtime
        // matcher).
        JsEmission fnArray = emissions.get(closurePrograms[0][0]);
        if (fnArray != null && !fnArray.hasErrors()) {
            String js = fnArray.source();
            check(js.contains("$rt.checkType(\"[([number])->[number]]\","),
                "the (([number])->[number])[] declaration boundary emits the "
                    + "canonical [([number])->[number]] descriptor byte-exact");
            check(js.contains("$rt.function(\"([number],number)->[number]\", "),
                "the arity adapter carries the canonical "
                    + "([number],number)->[number] target descriptor byte-exact");
        }
        JsEmission asyncContainer = emissions.get(closurePrograms[1][0]);
        if (asyncContainer != null && !asyncContainer.hasErrors()) {
            String js = asyncContainer.source();
            check(js.contains("$rt.checkType(\"[async([number])->[number]]\","),
                "the async function-value array boundary emits the "
                    + "canonical [async([number])->[number]] descriptor byte-exact");
            check(js.contains("$rt.function(\"async([number])->[number]\", "
                    + "async function bump$f("),
                "the async wrapper carries the canonical "
                    + "async([number])->[number] signature byte-for-byte");
        }
        JsEmission deep = emissions.get(closurePrograms[2][0]);
        if (deep != null && !deep.hasErrors()) {
            String js = deep.source();
            check(js.contains("$rt.checkType(\"[?[[number]]]\","),
                "the ([number][] | null)[] boundary emits the canonical "
                    + "[?[[number]]] descriptor byte-exact");
            check(js.contains("$rt.checkType(\"?[[[number]]]\","),
                "the ([number][])[] | null boundary emits the canonical "
                    + "[[[number]]] inner descriptor byte-exact");
        }

        PreparedJs eqPrepared = prepareJs("""
            export function test(): number {
              let a: number[] = [0];
              let b: number[] = a;
              let c: number[] = [0];
              if (!(a === b)) { return 0; }
              if (a === c) { return 0; }
              if (a !== c) { return 1; }
              return 0;
            }
            """, "array-closure-eq");
        Frontend eq = eqPrepared.frontend();
        check(eq.errors().isEmpty(),
            "[number] identity equality is checker-admitted with zero "
                + "diagnostics (frontend, ISSUE-0158 lift): " + eq.errors());
        if (eq.errors().isEmpty()) {
            JsEmission eqRes = eqPrepared.emission();
            check(eqRes != null && !eqRes.hasErrors(),
                "the equality closure position generates with zero "
                    + "diagnostics: " + (eqRes == null ? "<null>"
                        : eqRes.diagnostics()));
            if (eqRes != null && !eqRes.hasErrors()) {
                check(eqRes.source().matches(
                            "(?s).*\\$s\\d+ === \\$s\\d+.*")
                        && eqRes.source().matches(
                            "(?s).*\\$s\\d+ !== \\$s\\d+.*"),
                    "[number] equality emits native identity comparison");
            }
        }
        {
            NodeResult eqRun = runDealArtifact(eqPrepared);
            check(eqRun.exitCode() == 0 && eqRun.output().equals("1"),
                "[number] identity equality executes real reference identity "
                    + "on Node (alias true, distinct arrays false; "
                    + "returns 1): " + eqRun.output());
        }
    }

    private static void testNumModFloored() throws Exception {
        System.out.println("-- v1.4: remainder syntax is rejected --");
        String[] subjects = { "-5.0 % 2.0", "5.0 % -2.0", "-5.0 % -2.0", "5.0 % 2.0" };
        for (String subject : subjects) {
            Frontend rejected = compileFrontend(
                "export function test(): number { return " + subject + "; }", "nummod.deal");
            check(rejected.errors().stream().anyMatch(d -> d.code().equals("E1001")
                    && d.message().equals("Unrecognized character: '%'")),
                subject + ": number remainder rejects before emission: " + rejected.errors());
        }
    }

    private static void testScalarStringOps() throws Exception {
        System.out.println("-- Node: scalar-value string ops --");

        NodeResult run = runDealNode("""
            import * as strings from "std/string";
            export function test(): number {
              let s: string = "𝒜B";
              let n: number = 0;
              for (let c: string of s) { n = n + 1; }
              let len: number = strings.length(s);
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

        NodeResult run = runDealNode("""
            class Tri { field?: number | null; }
            export function test(): number {
              let missing: Tri = {};
              if (has(missing.field)) { return 0; }
              if (missing.field !== null) { return 0; }
              let explicitNull: Tri = { field: null };
              if (!has(explicitNull.field) || explicitNull.field !== null) { return 0; }
              let value: Tri = { field: 5 };
              if (!has(value.field)) { return 0; }
              let v: number | null = value.field;
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

        NodeResult table = runDealNode("""
            export function test(): number {
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
            export function test(): number {
              let xs: (number | null)[] = [];
              xs[0] = 1;
              let v: number | null = xs[9];
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
            export function test(): number {
              let xs: (number | null)[] = [];
              xs[0] = 1;
              let v: number | null = xs[0];
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
            "export function test(): number { let xs: number[] = [1]; return xs[9]; }",
            "nil-array-nonnullable");
        check(nonNull.exitCode() == 1
                && nonNull.output().contains("DEAL_ERROR_CODE: E8002")
                && nonNull.output().contains("array index out of bounds"),
            "the same read at a non-nullable target rejects with E8002: "
                + nonNull.output());
    }

    private static void testE8010SignatureMismatch() throws Exception {
        System.out.println("-- Node: E8010 signature mismatch --");

        NodeResult run = runDealNode("""
            function add(a: number, b: number): number { return a + b; }
            export function test(): number {
              let t: table = {};
              t.g = add;
              let f: (x: number) => number = t.g;
              return f(1);
            }
            """, "e8010");
        check(run.exitCode() == 1 && run.output().contains("DEAL_ERROR_CODE: E8010")
                && run.output().contains(
                    "function signature mismatch: expected (number)->number, got (number,number)->number"),
            "a wrong-signature table-stored wrapper crossing a function-typed "
                + "boundary raises E8010: " + run.output());
    }

    private static void testE8007ExtraClassFieldRuntime() throws Exception {
        System.out.println("-- Node: E8007 extra class field (runtime arm) --");

        NodeResult run = runRuntimeProbe("e8007", """
            "use strict";
            const $rt = require("./deal/runtime");
            try {
              $rt.classPlan("@m/C", [{name:"x",descriptor:"number",optional:false}], { ["y"]: 1 }, "probe.js", 3, 1);
              console.log("NO-ERROR");
            } catch (e) {
              console.log(e.$dealCode + ": " + e.message);
            }
            """);
        check(run.exitCode() == 0
                && run.output().equals("E8007: extra field 'y' in class '@m/C'"),
            "classPlan rejects an extra provided field with E8007: " + run.output());
    }

    private static void testClassIdentityTagPair() throws Exception {
        System.out.println("-- Node: class identity tag pair --");

        NodeResult ok = runDealNode("""
            class User { name: string; }
            export function test(): number {
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
            class User { name: string; }
            class Other { name: string; }
            export function test(): number {
              let o: Other = { name: "b" };
              let t: table = { item: o };
              let bad: User = t.item;
              return 0;
            }
            """, "identity-wrong");
        check(wrong.exitCode() == 1
                && wrong.output().contains("DEAL_ERROR_CODE: E8001")
                && wrong.output().contains(
                    "expected instance of @Main/Main.deal/User, got @Main/Main.deal/Other"),
            "a wrong-identity instance fails the boundary with E8001 naming "
                + "both identities: " + wrong.output());

        NodeResult tags = runRuntimeProbe("tags", """
            "use strict";
            const $rt = require("./deal/runtime");
            const $v = $rt.classPlan("@m/C", [{name:"x",descriptor:"number",optional:false}], { ["x"]: 1 }, "probe.js", 1, 1);
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

        // The canonical grammar accepts ?D and [D] over ANY descriptor D —
        // function descriptors included — and compares class atoms and
        // function signatures byte-for-byte.
        NodeResult run = runRuntimeProbe("matcher", """
            "use strict";
            const $rt = require("./deal/runtime");
            let $fail = "";
            // ?D and [D] over function descriptors.
            const $f = $rt.function("(number)->number", function(x, $file, $line, $column) { return x; });
            $rt.checkType("?(number)->number", $f, "probe.js", 1, 1);
            $rt.checkType("[(number)->number]", [$f], "probe.js", 1, 1);
            // Byte-for-byte class-atom comparison.
            const $v = $rt.classPlan("@m/C", [{name:"x",descriptor:"number",optional:false}], { ["x"]: 1 }, "probe.js", 1, 1);
            $rt.checkType("@m/C", $v, "probe.js", 1, 1);
            try { $rt.checkType("@m/c", $v, "probe.js", 1, 1); $fail = "class-case"; } catch (e) {}
            // Differential whitespace pin: U+200B ZERO WIDTH SPACE is a
            // legal component scalar in the canonical alphabet (the Java
            // parser and the identity index forbid only 2000-200A), so
            // the class atom @a<U+200B>b/C parses and compares
            // byte-for-byte; U+200A HAIR SPACE is forbidden whitespace
            // and takes the pinned E8001 cannot-parse arm.
            const $v2 = $rt.classPlan("@a\\u200Bb/C", [{name:"x",descriptor:"number",optional:false}], { ["x"]: 1 }, "probe.js", 1, 1);
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
              $rt.checkType("[number]", [1, "x", 3], "probe.js", 1, 1);
              $fail = "e8003";
            } catch (e) {
              if (e.$dealCode !== "E8003" || e.message !== "array element 2 type mismatch") {
                $fail = "e8003-msg:" + e.$dealCode + ":" + e.message;
              }
            }
            // E8010 pinned function-signature message on any descriptor delta.
            const $g = $rt.function("(number,number)->number", function(a, b, $file, $line, $column) { return a + b; });
            try {
              $rt.checkType("(number)->number", $g, "probe.js", 1, 1);
              $fail = "e8010";
            } catch (e) {
              if (e.$dealCode !== "E8010" || e.message !== "function signature mismatch: expected (number)->number, got (number,number)->number") {
                $fail = "e8010-msg:" + e.$dealCode + ":" + e.message;
              }
            }
            // Every numeric payload has the same descriptor, including IEEE specials.
            for (const value of [2 ** 60, 1.5, NaN, Infinity, -Infinity, -0]) {
              if (!Object.is($rt.checkType("number", value, "probe.js", 1, 1), value)) {
                $fail = "number-admission:" + String(value);
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

        NodeResult run = runRuntimeProbe("legacy-reject", """
            "use strict";
            const $rt = require("./deal/runtime");
            let $fail = "";
            const $legacy = ["number[]", "number|null", "table[]", "User",
              "?User", "Errorx", "Error|null", "(number)->number[]", "(number)->number|null",
              "@Foo", "@src.models.User", "@m/c.Name", "??number", "?null",
              "[]", "(number)number", "async[number]", "@a/", "@a//b", "number...",
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
        check("async(table)->table".equals(service.encode(
                new Type.Func(List.of(Type.Table.INSTANCE), Type.Table.INSTANCE, true))),
            "encode(Type) emits async(table)->table");

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

        NodeResult run = runDealNode("""
            function checkMsg(): number {
              try {
                throw { code: "", message: "x" };
              } catch (e) {
                if (e.code === "" && e.message === "x") { return 1; }
                return 0;
              }
              return 0;
            }
            function checkCode(): number {
              try {
                throw { code: "C", message: "" };
              } catch (e) {
                if (e.code === "C" && e.message === "") { return 1; }
                return 0;
              }
              return 0;
            }
            export function test(): number { return checkMsg() * 10 + checkCode(); }
            """, "err-default");
        check(run.exitCode() == 0 && run.output().equals("11"),
            "throw { message: \"x\" } catches with e.code === \"\" and "
                + "throw { code: \"C\" } catches with e.message === \"\": "
                + run.output());
    }

    private static void testContextualErrorConstruction() throws Exception {
        System.out.println("-- Node: contextual Error construction --");

        NodeResult run = runDealNode("""
            function produce(): Error { return { code: "", message: "ret" }; }
            export function test(): number {
              let e: Error = { code: "", message: "x" };
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

        NodeResult caught = runDealNode("""
            function inner(): null {
              try {
                throw { code: "USER_RETHROW", message: "boom" };
              } catch (e) {
                throw e;
              }
              return null;
            }
            export function test(): number {
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

        NodeResult valid = runDealNode("""
            export function test(): number {
              let xs: number[] = [1, 2, 3];
              delete xs[1];
              if (xs.length !== 3) { return 0; }
              let first: number | null = xs[0];
              let deleted: number | null = xs[1];
              let last: number | null = xs[2];
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
              let n: number = 0;
              for (let x: number of xs) { n = n + 1; }
              if (n !== 1) { return 0; }
              return 1;
            }
            """, "del-valid");
        check(valid.exitCode() == 0 && valid.output().equals("1"),
            "a valid delete nil-outs via $rt.undefined (nullable read null, "
                + "for-of stops at the deleted element, length unchanged): "
                + valid.output());

        NodeResult nonNull = runDealNode(
            "export function test(): number { let xs: number[] = [1, 2, 3]; delete xs[1]; return xs[1]; }",
            "del-nonnullable");
        check(nonNull.exitCode() == 1
                && nonNull.output().contains("DEAL_ERROR_CODE: E8001"),
            "a deleted element at a non-nullable target rejects with E8001: "
                + nonNull.output());

        for (String[] row : new String[][] {
                {"negative", "-1", "a negative delete index raises E8002 'array index out of bounds': "},
                {"beyond", "9", "a beyond-length delete index raises E8002: "}}) {
            checkDealFailure("export function test(): number { let xs: number[] = [1, 2, 3]; "
                    + "delete xs[" + row[1] + "]; return 0; }",
                "del-" + row[0], "E8002", "array index out of bounds", row[2]);
        }

        NodeResult atLength = runDealNode("""
            export function test(): number {
              let xs: number[] = [1, 2, 3];
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

        NodeResult ok = runDealNode("""
            function f(file: string, line: number, column: string): number { return line; }
            export function test(): number { return f("a", 41, "b"); }
            """, "span-run");
        check(ok.exitCode() == 0 && ok.output().equals("41"),
            "a function with user parameters named file/line/column runs "
                + "under node with the $file/$line/$column span parameters: "
                + ok.output());

        NodeResult located = runDealNode("""
            function g(line: number): number { return line + 1; }
            export function test(): number {
              let t: table = {};
              t.v = "not-number";
              return g(t.v);
            }
            """, "span-located");
        check(located.exitCode() == 1
                && located.output().contains("DEAL_ERROR_CODE: E8001")
                && located.output().contains("expected number")
                && located.output().contains(
                    " at jstest-span-located.deal:1:18"),
            "the parameter boundary error reports the parameter's declared "
                + "location (the reference's parameter-check span): "
                + located.output());
    }

    private static void testStdJsonRoundTrips() throws Exception {
        System.out.println("-- Node: std/json round-trips --");

        NodeResult run = runDealNode("""
            import * as json from "std/json";
            class User {
              name: string;
              nick?: string | null;
            }
            export function test(): number {
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
     * nested array fixture shape — a decoded
     * JSON array crossing the recursive {@code [[table]]} descriptor —
     * reports the reference value model's {@code table} element token,
     * never the {@code array} token its table-descriptor projection
     * carries. The direct suite pins the runtime surface; this pin
     * executes the emitted artifact through the deployed runtime.
     */
    private static void testJsonDecodedArrayElementKindNode() throws Exception {
        System.out.println("-- Node: the decoded-array element reference-kind"
            + " projection --");

        JsEmission res = checkedEmission(prepareJs("""
            import * as json from "std/json";

            export function test(): number {
              let t: table = json.parse("{\\"values\\":[[\\"x\\"],[\\"y\\"]]}");
              let xs: table[][] = t.values;
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
                "ELEMENT: E8003|array element 1 type mismatch|[table]|table"),
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

        NodeResult run = runDealNode("""
            import * as json from "std/json";
            function equivalent(text: string, expected: string): boolean {
              return json.stringify(json.parse(text)) === json.stringify(json.parse(expected));
            }

            export function test(): number {
              let t: table = json.parse("{\\"items\\":[[1,2],[3,4]]}");
              let xs: number[][] = t.items;
              let first: number[] = xs[0];
              let alias: number[] = xs[0];
              if (first !== alias) { return 0; }
              if (first.length !== 2) { return 0; }
              if (xs.length !== 2) { return 0; }
              if (xs[1][0] !== 3) { return 0; }
              first[1] = 9;
              if (alias[1] !== 9) { return 0; }
              if (xs[0][1] !== 9) { return 0; }
              if (!equivalent(json.stringify(t), "{\\"items\\":[[1,9],[3,4]]}")) { return 0; }
              let emptyT: table = json.parse("{\\"items\\":[]}");
              let empty: number[] = emptyT.items;
              if (empty.length !== 0) { return 0; }
              delete xs[0];
              if (xs.length !== 2) { return 0; }
              let deleted: boolean = false;
              try {
                let gone: number[] = xs[0];
              } catch (e) {
                if (e.code === "E8001" && e.message === "expected array") { deleted = true; }
              }
              if (!deleted) { return 0; }
              let caught: boolean = false;
              try {
                let oob: number[] = xs[2];
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

        NodeResult run = runDealNode("""
            import * as json from "std/json";
            function equivalent(text: string, expected: string): boolean {
              return json.stringify(json.parse(text)) === json.stringify(json.parse(expected));
            }

            // @jsonable
            export class Box {
              nums: number[];
              tags: string[][];
              maybe: (number|null)[];
              ns: number[];
              nmaybe: (number|null)[];
              nnested: number[][];
            }

            export function test(): number {
              let t: table = json.parse("{\\"values\\":[1,2]}");
              let b: Box = {nums: [], tags: [], maybe: [], ns: [], nmaybe: [], nnested: []};
              b.nums = t.values;
              if (!equivalent(Box$toJson(b), "{\\"nums\\":[1,2],\\"tags\\":[],\\"maybe\\":[],\\"ns\\":[],\\"nmaybe\\":[],\\"nnested\\":[]}")) { return 0; }
              let n: table = json.parse("{\\"values\\":[[\\"a\\"],[\\"b\\",\\"c\\"]]}");
              let c: Box = {nums: [], tags: [], maybe: [], ns: [], nmaybe: [], nnested: []};
              c.tags = n.values;
              if (!equivalent(Box$toJson(c), "{\\"nums\\":[],\\"tags\\":[[\\"a\\"],[\\"b\\",\\"c\\"]],\\"maybe\\":[],\\"ns\\":[],\\"nmaybe\\":[],\\"nnested\\":[]}")) { return 0; }
              let alias: number[] = b.nums;
              alias[0] = 7;
              if (!equivalent(Box$toJson(b), "{\\"nums\\":[7,2],\\"tags\\":[],\\"maybe\\":[],\\"ns\\":[],\\"nmaybe\\":[],\\"nnested\\":[]}")) { return 0; }
              let empty: table = json.parse("{\\"values\\":[]}");
              let e: Box = {nums: [], tags: [], maybe: [], ns: [], nmaybe: [], nnested: []};
              e.nums = empty.values;
              if (!equivalent(Box$toJson(e), "{\\"nums\\":[],\\"tags\\":[],\\"maybe\\":[],\\"ns\\":[],\\"nmaybe\\":[],\\"nnested\\":[]}")) { return 0; }
              let mixed: table = json.parse("{\\"values\\":[1,null]}");
              let m: Box = {nums: [], tags: [], maybe: [], ns: [], nmaybe: [], nnested: []};
              m.maybe = mixed.values;
              if (!equivalent(Box$toJson(m), "{\\"nums\\":[],\\"tags\\":[],\\"maybe\\":[1,null],\\"ns\\":[],\\"nmaybe\\":[],\\"nnested\\":[]}")) { return 0; }
              // A decoded array's number slots keep the graph's own
              // classification: 1, 1.0 and -0.0 are number-classified and
              // spell as integers, 1.5 keeps the number spelling.
              let numv: table = json.parse("{\\"values\\":[1,1.0,10]}");
              let nb: Box = {nums: [], tags: [], maybe: [], ns: [], nmaybe: [], nnested: []};
              nb.ns = numv.values;
              if (!equivalent(Box$toJson(nb), "{\\"nums\\":[],\\"tags\\":[],\\"maybe\\":[],\\"ns\\":[1,1,10],\\"nmaybe\\":[],\\"nnested\\":[]}")) { return 0; }
              let nfrac: table = json.parse("{\\"values\\":[1.5,-0.0]}");
              let fb: Box = {nums: [], tags: [], maybe: [], ns: [], nmaybe: [], nnested: []};
              fb.ns = nfrac.values;
              if (!equivalent(Box$toJson(fb), "{\\"nums\\":[],\\"tags\\":[],\\"maybe\\":[],\\"ns\\":[1.5,0],\\"nmaybe\\":[],\\"nnested\\":[]}")) { return 0; }
              let nnull: table = json.parse("{\\"values\\":[1,null,2]}");
              let nm: Box = {nums: [], tags: [], maybe: [], ns: [], nmaybe: [], nnested: []};
              nm.nmaybe = nnull.values;
              if (!equivalent(Box$toJson(nm), "{\\"nums\\":[],\\"tags\\":[],\\"maybe\\":[],\\"ns\\":[],\\"nmaybe\\":[1,null,2],\\"nnested\\":[]}")) { return 0; }
              let nnest: table = json.parse("{\\"values\\":[[1],[2,3]]}");
              let nn: Box = {nums: [], tags: [], maybe: [], ns: [], nmaybe: [], nnested: []};
              nn.nnested = nnest.values;
              if (!equivalent(Box$toJson(nn), "{\\"nums\\":[],\\"tags\\":[],\\"maybe\\":[],\\"ns\\":[],\\"nmaybe\\":[],\\"nnested\\":[[1],[2,3]]}")) { return 0; }
              // A decoded slot carrying the number variant is a number in
              // the shared classification even when its double is
              // integral, so the number row fails exactly like the other
              // lanes instead of re-rendering it as an number.
              let nv: table = json.parse("{\\"values\\":[2147483647.00000001]}");
              let vb: Box = {nums: [], tags: [], maybe: [], ns: [], nmaybe: [], nnested: []};
              vb.nums = nv.values;
              let intnum: string = "";
              try { intnum = Box$toJson(vb); }
              catch (e) { intnum = e.code + "|" + e.message; }
              if (!equivalent(intnum, "{\\"nums\\":[2147483647],\\"tags\\":[],\\"maybe\\":[],\\"ns\\":[],\\"nmaybe\\":[],\\"nnested\\":[]}")) { return 0; }
              return 1;
            }
            """, "jsonable-decoded-array-field");
        check(run.exitCode() == 0 && run.output().equals("1"),
            "the production artifact serializes a declared array field holding"
                + " a decoded JSON array (flat, nested, empty, a nullable"
                + " element holding a decoded JSON null, and number-classified"
                + " elements: 1/1.0/10 and -0.0 keep the integer spelling while"
                + " 1.5 spells the canonical decimal text, and a number-variant"
                + " element at an number field fails with the shared number token,"
                + " matching the shared classification the Oracle/LuaJIT/JVM"
                + " walk renders) as the"
                + " DEAL array text, and observes an alias"
                + " write through the walk: "
                + run.output() + " (exit " + run.exitCode() + ")");
    }

    /**
     * The decoded-array variant parity of the committed write, the element
     * read and the deleted slot (the ISSUE-0827 review cycle-3 findings;
     * the same rule test/AsyncExportInvokerProductionTest pins for the
     * emitted LuaJIT/JVM projections):
     * <ul>
     *   <li>a committed element write records the written value's own
     *       variant on the slot (__numKey), so a decoded number-variant
     *       slot rewritten with an int expression encodes as the int and a
     *       number-typed write spells the canonical decimal text — through
     *       both the @jsonable class walk and the std/json walk;</li>
     *   <li>the element read materializes the slot's recorded variant (the
     *       __readVar rule) so a value leaving the decoded graph keeps its
     *       own classification at the following boundary and encode
     *       consumers, while the decoded values still compare and compute
     *       by value;</li>
     *   <li>an absent (deleted) slot is the internal missing value: both
     *       encode walks raise the closed nil failure instead of
     *       fabricating a present JSON null, and a genuinely present null
     *       element keeps its JSON null slot.</li>
     * </ul>
     * The whole drive is the production frontend -> public production compilation ->
     * deployed runtime chain under node.
     */
    private static void testJsonDecodedArrayVariantNode() throws Exception {
        System.out.println("-- Node: decoded-array variant through the"
            + " committed write, the element read and the deleted slot --");

        NodeResult run = runDealNode("""
            import * as json from "std/json";
            function equivalent(text: string, expected: string): boolean {
              return json.stringify(json.parse(text)) === json.stringify(json.parse(expected));
            }

            // @jsonable
            export class WBox {
              nums: number[];
            }

            // @jsonable
            export class IBox {
              n: number;
            }

            // @jsonable
            export class NBox {
              n: number;
            }

            export function test(): number {
              // (1) The committed write records the written value's own
              // variant: a decoded number-variant slot rewritten with an
              // number expression is an number slot afterwards.
              let t1: table = json.parse("{\\"values\\":[2147483647.00000001]}");
              let b: WBox = {nums: []};
              b.nums = t1.values;
              let alias: number[] = b.nums;
              alias[0] = 7;
              if (!equivalent(WBox$toJson(b), "{\\"nums\\":[7]}")) { return 0; }
              let t2: table = json.parse("{\\"values\\":[2147483647.00000001]}");
              let xs2: number[] = t2.values;
              xs2[0] = 7;
              if (!equivalent(json.stringify(t2), "{\\"values\\":[7]}")) { return 0; }
              // A number-typed write sets the mark, so the slot spells the
              // canonical decimal text.
              let t3: table = json.parse("{\\"values\\":[1]}");
              let xs3: number[] = t3.values;
              xs3[0] = 1e21;
              if (!equivalent(json.stringify(t3), "{\\"values\\":[1.0E21]}")) { return 0; }
              // Delete then rewrite: the cleared mark never survives the
              // rewrite.
              let t3b: table = json.parse("{\\"values\\":[1]}");
              let nsb: number[] = t3b.values;
              delete nsb[0];
              nsb[0] = 1e21;
              if (!equivalent(json.stringify(t3b), "{\\"values\\":[1.0E21]}")) { return 0; }
              // A delete whose checked index is a read-side variant
              // carrier: an index equal to the length is the guarded
              // no-op (the carrier's numeric value, not the object, is
              // compared to the length) and an in-range carrier index
              // still commits exactly one slot.
              let t3c: table = json.parse("{\\"a\\":[1,2],\\"b\\":[2.0000000000000000001]}");
              let xsA: number[] = t3c.a;
              let xsB: number[] = t3c.b;
              delete xsA[xsB[0]];
              if (xsA.length !== 2) { return 0; }
              if (!equivalent(json.stringify(t3c), "{\\"a\\":[1,2],\\"b\\":[2.0]}")) { return 0; }
              let t3d: table = json.parse("{\\"a\\":[1,2,3],\\"b\\":[1.0000000000000000001]}");
              let xsC: number[] = t3d.a;
              let xsD: number[] = t3d.b;
              delete xsC[xsD[0]];
              if (xsC.length !== 3) { return 0; }
              let gone: boolean = false;
              try {
                let missing: number = xsC[1];
              } catch (e) {
                if (e.code === "E8001" && e.message === "expected number") { gone = true; }
              }
              if (!gone) { return 0; }
              let dnil: string = "";
              try { dnil = json.stringify(t3d); } catch (e) { dnil = e.code + "|" + e.message; }
              if (dnil !== "E8001|unsupported type for JSON encoding: nil") { return 0; }

              // (2) The element read materializes the slot's own variant at
              // the positions the declared kind cannot express.
              let t4: table = json.parse("{\\"values\\":[2147483647.00000001]}");
              let xs4: number[] = t4.values;
              let ib: IBox = {n: 0};
              ib.n = xs4[0];
              let r1: string = "";
              try { r1 = IBox$toJson(ib); } catch (e) { r1 = e.code + "|" + e.message; }
              if (!equivalent(r1, "{\\"n\\":2147483647}")) { return 0; }
              let t5: table = json.parse("{\\"values\\":[1]}");
              let xs5: number[] = t5.values;
              let nb: NBox = {n: 0};
              nb.n = xs5[0];
              if (!equivalent(NBox$toJson(nb), "{\\"n\\":1}")) { return 0; }
              // The materialized variant still compares and computes by
              // value (the carriers unwrap at every numeric consumer: the
              // strict-equality emit, the native number operators, the number
              // runtime members and the conversions).
              if (xs5[0] !== 1.0) { return 0; }
              if (xs4[0] !== 2147483647) { return 0; }
              let sum: number = xs5[0] + 0.5;
              if (sum !== 1.5) { return 0; }
              let doubled: number = xs5[0] * 2.0;
              if (doubled !== 2.0) { return 0; }
              let halves: number = xs5[0] / 2.0;
              if (halves !== 0.5) { return 0; }
              let rest: number = xs4[0] - 2147483647;
              if (rest !== 0) { return 0; }
              let count: number = 0;
              let total: number = 0.0;
              for (let v: number of xs5) {
                count = count + 1;
                total = total + v;
              }
              if (count !== 1) { return 0; }
              if (total !== 1.0) { return 0; }
              // The conversions produce the target representation.
              if ((xs5[0]) !== 1) { return 0; }
              if ((xs4[0] - 2147483647) !== 0.0) { return 0; }
              // The number value model has no negative zero: a number-variant
              // read carrier whose double is -0 normalizes at the number
              // boundary (the plain path's v = v + 0), so number(-0.0) is
              // the number zero and the following number()/write spells 0.0
              // on every lane.
              let tz: table = json.parse("{\\"ys\\":[1.5]}");
              let negZero: number[] = tz.ys;
              negZero[0] = -0.0;
              let zn: number = (negZero[0]);
              negZero[0] = zn;
              if (1 / zn >= 0 || json.stringify(tz) !== "{\\"ys\\":[0]}") { return 0; }

              // (3) A deleted slot is the internal missing value: both
              // encode walks raise the closed nil failure; a genuinely
              // present null element keeps its JSON null slot.
              let t6: table = json.parse("{\\"values\\":[1,2]}");
              let xs6: number[] = t6.values;
              delete xs6[0];
              let d1: string = "";
              try { d1 = json.stringify(t6); } catch (e) { d1 = e.code + "|" + e.message; }
              if (d1 !== "E8001|unsupported type for JSON encoding: nil") { return 0; }
              let t7: table = json.parse("{\\"values\\":[1,2]}");
              let db: WBox = {nums: []};
              db.nums = t7.values;
              let ys: number[] = db.nums;
              delete ys[0];
              let d2: string = "";
              try { d2 = WBox$toJson(db); } catch (e) { d2 = e.code + "|" + e.message; }
              if (d2 !== "E8001|value at nums[0] is not JSON serializable: nil") { return 0; }
              let zs: number[] = [1,2];
              delete zs[0];
              let d3: string = "";
              try { d3 = json.stringify({xs: zs}); } catch (e) { d3 = e.code + "|" + e.message; }
              if (d3 !== "E8001|unsupported type for JSON encoding: nil") { return 0; }
              let t8: table = json.parse("{\\"values\\":[1,null,2]}");
              if (!equivalent(json.stringify(t8), "{\\"values\\":[1,null,2]}")) { return 0; }
              return 1;
            }
            """, "json-decoded-array-variant");
        check(run.exitCode() == 0 && run.output().equals("1"),
            "the production artifact keeps the decoded slot's own variant"
                + " through the committed write (both encode walks), the"
                + " element read (the class walk renders the number spelling and"
                + " the number token for the mismatched positions, while the"
                + " values still compare and compute by value) and the deleted"
                + " slot (the closed nil failure, never a fabricated JSON"
                + " null; a present null element keeps its slot): "
                + run.output() + " (exit " + run.exitCode() + ")");
    }

    /**
     * The host boundary and the decoded-array read carrier (the ISSUE-0827
     * review cycle-4 finding): a checked host call hands the raw host the
     * declared host-facing number, never the runtime's internal read-side
     * variant carrier, and the async-export invoker projects a checked
     * completion's numeric carriers to their numbers at the top level and
     * every nested container leaf (the JVM host-cell/completion projection
     * and the LuaJIT __hostParamAdmit/__hostNumVar/__asyncExportCompletion
     * rules). The drive is the production frontend -> public production compilation ->
     * deployed runtime chain under node over a manifest-declared host
     * module: a number-variant carrier and an int-variant carrier (a decoded
     * int slot read at a declared number position) reach the sync and async
     * host functions as their numbers, a nullable numeric position still
     * receives the language null, a scalar completion is its number, a
     * nested completion and its aliased inner array are projected with
     * their identities preserved, and no internal carrier key reaches the
     * host caller.
     */
    private static void testHostBoundaryNumericCarrierNode() throws Exception {
        System.out.println("-- Node: host numeric parameters and async-export"
            + " completions project read carriers --");

        writeFile("carrier_proj/deal.json", """
            {"languageVersion": "1.4", "backend": "js",
             "moduleRoots": ["src"], "output": "out",
             "externals": {"host/probe": {"declaration": "bindings/probe.d.deal"}}}
            """);
        writeFile("carrier_proj/bindings/probe.d.deal", """
            export function isNumber(x: number): boolean;
            export function isInteger(x: number): boolean;
            export function isAbsent(x: number | null): boolean;
            export async function echo(x: number): number;
            """);
        writeFile("carrier_proj/src/main.deal", """
            import * as json from "std/json";
            import * as h from "host/probe";

            export function main(): null { return null; }

            export async function probe(): number {
              let t: table = json.parse("{\\"xs\\":[7.5,1]}");
              let xs: number[] = t.xs;
              let count: number = 0;
              if (h.isNumber(xs[0])) { count = count + 1; }
              if (h.isNumber(xs[1])) { count = count + 2; }
              if (h.isInteger(xs[1])) { count = count + 4; }
              if (h.isAbsent(null)) { count = count + 8; }
              let nt: table = json.parse("{\\"ys\\":[null]}");
              let nys: (number | null)[] = nt.ys;
              if (h.isAbsent(nys[0])) { count = count + 64; }
              let echoed: number = await h.echo(xs[0]);
              if (echoed === 7.5) { count = count + 16; }
              if (h.isNumber(echoed)) { count = count + 32; }
              return count;
            }

            export async function run(): number {
              let t: table = json.parse("{\\"xs\\":[7.5]}");
              let xs: number[] = t.xs;
              return xs[0];
            }

            export async function nested(): number[] {
              let t: table = json.parse("{\\"xs\\":[1,7.5]}");
              let xs: number[] = t.xs;
              return [xs[0], xs[1], 8.25];
            }

            export async function shared(): number[][] {
              let t: table = json.parse("{\\"xs\\":[1]}");
              let xs: number[] = t.xs;
              return [xs, xs];
            }

            export async function tabled(): table {
              let t: table = json.parse("{\\"xs\\":[7.5]}");
              let xs: number[] = t.xs;
              let out: table = { x: xs[0] };
              return out;
            }
            """);
        Path entry = tmpDir.resolve("carrier_proj/src/main.deal")
            .toAbsolutePath();
        int exit = deal.Main.run(new String[] {"compile", entry.toString()});
        check(exit == 0, "the host-carrier project compiles through the"
            + " public deal compile: exit=" + exit);
        if (exit != 0) {
            return;
        }
        writeFile("carrier_proj/out/host/probe.js", """
            "use strict";
            module.exports = {
              isNumber: function (x) { return typeof x === "number"; },
              isInteger: function (x) { return typeof x === "number" && Number.isInteger(x); },
              isAbsent: function (x) { return x === null; },
              echo: async function (x) { return x; },
            };
            """);
        writeFile("carrier_proj/out/carrier_runner.js", String.join("\n",
            "\"use strict\";",
            "const $rt = require(\"./deal/runtime\");",
            "const $entry = require(\"./main\");",
            "const $out = [];",
            "(async () => {",
            "  $out.push(\"probe=\" + await $entry.probe.$f());",
            "  const $scalar = await $rt.invokeAsyncExport($entry, \"run\", \"number\");",
            "  $out.push(\"scalar=\" + ($scalar.$ok === true",
            "    && typeof $scalar.$value === \"number\"",
            "    && $scalar.$value === 7.5 ? \"ok\" : \"bad:\" + JSON.stringify($scalar)));",
            "  const $nested = await $rt.invokeAsyncExport($entry, \"nested\", \"[number]\");",
            "  $out.push(\"nested=\" + ($nested.$ok === true",
            "    && JSON.stringify($nested.$value) === \"[1,7.5,8.25]\"",
            "    ? \"ok\" : \"bad:\" + JSON.stringify($nested)));",
            "  const $shared = await $rt.invokeAsyncExport($entry, \"shared\", \"[[number]]\");",
            "  const $tabled = await $rt.invokeAsyncExport($entry, \"tabled\", \"table\");",
            "  $out.push(\"tabled=\" + ($tabled.$ok === true",
            "    && typeof $rt.tableGet($tabled.$value, \"x\") === \"number\"",
            "    && $rt.tableGet($tabled.$value, \"x\") === 7.5",
            "    ? \"ok\" : \"bad:\" + JSON.stringify($tabled)));",
            "  $out.push(\"shared=\" + ($shared.$ok === true",
            "    && Array.isArray($shared.$value) && $shared.$value.length === 2",
            "    && $shared.$value[0] === $shared.$value[1]",
            "    ? \"ok\" : \"bad:\" + JSON.stringify($shared)));",
            "  process.stdout.write($out.join(\"\\n\") + \"\\n\");",
            "})().catch((e) => { process.stderr.write(\"RUNNER \" + e + \"\\n\"); process.exit(1); });",
            ""));
        NodeResult run = runNodeScript(
            tmpDir.resolve("carrier_proj/out").toAbsolutePath(),
            "carrier_runner.js");
        check(run.exitCode() == 0 && run.output().equals(String.join("\n",
                "probe=127",
                "scalar=ok",
                "nested=ok",
                "tabled=ok",
                "shared=ok")),
            "the production artifact hands the host its declared numeric"
                + " values and the invoker its projected completion"
                + " (scalar, nested leaves and preserved aliases, no internal"
                + " carrier key): " + run.output() + " (exit "
                + run.exitCode() + ")");
    }

    /**
     * The host-parameter projection is carrier-complete (the ISSUE-0828
     * review finding): a declared host parameter hands the host the
     * declared DEAL value, never an internal std/json decode carrier.
     * Driven through the production frontend -> public production compilation ->
     * deployed runtime chain under node, over the rest_join-style host
     * boundary:
     * <ul>
     *   <li>a {@code string[]} obtained from {@code std/json.parse} object
     *       content arrives as a native Array — the pre-fix projection
     *       passed the array-marked Map and the host's {@code parts.join}
     *       threw;</li>
     *   <li>a decoded JSON null nested in a {@code (int|null)[]} arrives as
     *       the language null, not the marked null Map;</li>
     *   <li>two parameters aliasing one DEAL array arrive as one projected
     *       container (the identity and alias contract);</li>
     *   <li>the array's per-slot number variant survives the crossing, so
     *       the echoed array re-encodes its {@code 1e17} leaf as
     *       {@code 1.0E17} exactly like the DEAL-side array;</li>
     *   <li>a declared host-class parameter arrives with its declared
     *       fields projected the same way, so an array field filled from
     *       decoded content is a native Array the host can join;</li>
     *   <li>a language null crossing a non-nullable host parameter or
     *       return reports the canonical carrier-kind token {@code table}
     *       (the LuaJIT {@code __carrierKind(__NULL)} and JVM
     *       {@code __hostKind(null)} projection), never the JS {@code null}
     *       spelling, so the public host-boundary failure tuple agrees with
     *       the LuaJIT/JVM lanes and the closed carrier-kind
     *       projection.</li>
     * </ul>
     */
    private static void testHostDecodedArrayParamNode() throws Exception {
        System.out.println("-- Node: the host-parameter projection is"
            + " carrier-complete --");

        writeFile("hostparam_proj/deal.json", """
            {"languageVersion": "1.4", "backend": "js",
             "moduleRoots": ["src"], "output": "out",
             "externals": {
               "host/rest_join": {"declaration": "bindings/rest_join.d.deal"},
               "host/cfg": {"declaration": "bindings/cfg.d.deal"}}}
            """);
        writeFile("hostparam_proj/bindings/rest_join.d.deal", """
            export function join(sep: string, parts: string[]): string;
            export function shape(parts: string[]): string;
            export function observe(parts: (number | null)[], alias: (number | null)[]): string;
            export function echoNumbers(nums: number[]): number[];
            export function echoInt(value: number): number;
            export function value(): number;
            """);
        writeFile("hostparam_proj/bindings/cfg.d.deal", """
            export class Endpoint {
              path: string;
            }

            export class ServerConfig {
              port: number;
              endpoint: Endpoint;
              tags?: string[];
              note?: string | null;
            }

            export function describe(s: ServerConfig): string;
            """);
        writeFile("hostparam_proj/src/main.deal", """
            import * as json from "std/json";
            import * as host from "host/rest_join";
            import * as cfg from "host/cfg";

            export function main(): null { return null; }

            export function run(): string {
              let t: table = json.parse("{\\"items\\":[\\"a\\",\\"b\\"]}");
              let xs: string[] = t.items;
              let joined: string = host.join("-", xs);
              let shape: string = host.shape(xs);
              let empty: string[] = [];
              let joinedEmpty: string = host.join("+", empty);

              let u: table = json.parse("{\\"nums\\":[1,null,3]}");
              let ns: (number | null)[] = u.nums;
              let observed: string = host.observe(ns, ns);

              let v: table = json.parse("{\\"ms\\":[1e17,1.5]}");
              let ms: number[] = v.ms;
              let echoed: number[] = host.echoNumbers(ms);
              let box: table = { xs: echoed };
              let variants: string = json.stringify(box);

              let w: table = json.parse("{\\"tags\\":[\\"x\\",\\"y\\"]}");
              let tags: string[] = w.tags;
              let sc: cfg.ServerConfig = { port: 7, endpoint: { path: "/p" },
                tags: tags, note: null };
              let field: string = cfg.describe(sc);

              return "joined=" + joined + "\\n"
                + "empty=" + joinedEmpty + "\\n"
                + "shape=" + shape + "\\n"
                + "observed=" + observed + "\\n"
                + "variants=" + variants + "\\n"
                + "field=" + field;
            }

            export function nullParam(): table {
              let t: table = { v: null };
              let x: number = host.echoInt(t.v);
              return { ok: x };
            }

            export function nullReturn(): number {
              return host.value();
            }
            """);
        Path entry = tmpDir.resolve("hostparam_proj/src/main.deal")
            .toAbsolutePath();
        int exit = deal.Main.run(new String[] {"compile", entry.toString()});
        check(exit == 0, "the host decoded-array parameter project compiles"
            + " through the public deal compile: exit=" + exit);
        if (exit != 0) {
            return;
        }
        // The host implementations the emitted relative requires resolve
        // (the conformance harness's <outputRoot>/<raw specifier>.js
        // deployment shape).
        writeFile("hostparam_proj/out/host/rest_join.js", """
            "use strict";

            module.exports = {
              join: function (sep, parts) {
                return Array.isArray(parts) ? parts.join(sep) : "not-array";
              },
              shape: function (parts) {
                if (!Array.isArray(parts)) { return "not-array"; }
                return "array:" + parts.length + ":" + parts.join(",");
              },
              observe: function (parts, alias) {
                if (!Array.isArray(parts) || !Array.isArray(alias)) {
                  return "not-array";
                }
                const slot = parts[1] === null ? "null" : String(parts[1]);
                return Array.isArray(parts) ? "array:" + parts.length + ":"
                  + String(parts[0]) + "," + slot + "," + String(parts[2])
                  + (parts === alias ? ":same" : ":different") : "not-array";
              },
              echoNumbers: function (nums) { return nums; },
              echoInt: function (value) { return value; },
              value: function () { return null; },
            };
            """);
        writeFile("hostparam_proj/out/host/cfg.js", """
            "use strict";

            const $rt = require("../deal/runtime");

            module.exports = {
              Endpoint: {
                $kind: "class",
                $classname: "@$external/host/cfg/Endpoint",
              },

              Endpoint_defaults: {
                path: "/",
              },

              ServerConfig: {
                $kind: "class",
                $classname: "@$external/host/cfg/ServerConfig",
              },

              ServerConfig_defaults: {
                port: 8080,
                endpoint: $rt.MISSING,
                tags: $rt.MISSING,
                note: $rt.MISSING,
              },

              describe: function (s) {
                if (!Array.isArray(s.tags)) { return "not-array-field"; }
                return s.endpoint.path + ":" + s.port + ":" + s.tags.join(",");
              },
            };
            """);
        writeFile("hostparam_proj/out/hostparam_runner.js", String.join("\n",
            "\"use strict\";",
            "const $entry = require(\"./main\");",
            "process.stdout.write($entry.run.$f() + \"\\n\");",
            ""));
        NodeResult run = runNodeScript(
            tmpDir.resolve("hostparam_proj/out").toAbsolutePath(),
            "hostparam_runner.js");
        check(run.exitCode() == 0 && run.output().equals(String.join("\n",
                "joined=a-b",
                "empty=",
                "shape=array:2:a,b",
                "observed=array:3:1,null,3:same",
                "variants={\"xs\":[1.0E17,1.5]}",
                "field=/p:7:x,y")),
            "a declared host parameter hands the host the declared DEAL value"
                + " (native array, language null, preserved alias and number"
                + " variant) and a declared host-class parameter hands it"
                + " projected declared fields: " + run.output() + " (exit "
                + run.exitCode() + ")");

        // The language-null host crossing: the carrier-kind projection maps
        // the DEAL null sentinel to the table token on every consumer
        // (FailureProjections.carrierKindToken(LANGUAGE_NULL), the LuaJIT
        // prelude's __carrierKind(__NULL) and the JVM __hostKind(null)), so
        // a production JS artifact reports the same public failure tuple.
        writeFile("hostparam_proj/out/host_null_runner.js", String.join("\n",
            "\"use strict\";",
            "const $entry = require(\"./main\");",
            "const $lines = [];",
            "for (const $name of [\"nullParam\", \"nullReturn\"]) {",
            "  try {",
            "    $entry[$name].$f();",
            "    $lines.push($name + \"=none\");",
            "  } catch (e) {",
            "    $lines.push($name + \"=\" + e.code + \"|\" + e.message",
            "      + \"|\" + e.expected + \"|\" + e.actual);",
            "  }",
            "}",
            "process.stdout.write($lines.join(\"\\n\") + \"\\n\");",
            ""));
        NodeResult nullRun = runNodeScript(
            tmpDir.resolve("hostparam_proj/out").toAbsolutePath(),
            "host_null_runner.js");
        check(nullRun.exitCode() == 0 && nullRun.output().equals(String.join(
                "\n",
                "nullParam=E8010|parameter 1 type mismatch: expected number|number|table",
                "nullReturn=E8010|return value 1 type mismatch: expected number|number|table")),
            "a language null crossing a declared host boundary reports the"
                + " canonical table carrier kind (matching the LuaJIT and JVM"
                + " projections, never the JS null spelling): " + nullRun.output()
                + " (exit " + nullRun.exitCode() + ")");
    }

    /**
     * The async-export completion projection is carrier-complete (the
     * ISSUE-0827 review finding): the host-visible completion keeps the
     * decoded graph's own shape and classification instead of losing it to
     * the projection. Driven through the production frontend -> public compilation
     * emission -> deployed runtime chain under node:
     * <ul>
     *   <li>a decoded object completion keeps the decoded-array carrier and
     *       the number-variant leaf's canonical text ({@code 1.0E17}), its
     *       int leaf's integer text, and its decoded JSON null as the
     *       language null — the pre-fix projection re-spelled the number as
     *       an integer and rendered the array as an object;</li>
     *   <li>a decoded {@code (int|null)[]} completion is still the array
     *       carrier with its null slot ({@code [1,null]}), not an object
     *       with an empty table in the null position;</li>
     *   <li>a class-typed completion is a copy whose numeric field is the
     *       declared host number, holds no internal carrier, and re-encodes
     *       through the generated helper exactly like the DEAL-side
     *       instance;</li>
     *   <li>a table completion holding one decoded array twice keeps one
     *       projected container per source container (the identity and
     *       alias contract).</li>
     * </ul>
     */
    private static void testHostCompletionCarrierNode() throws Exception {
        System.out.println("-- Node: the async-export completion projection keeps"
            + " the decoded carriers --");

        writeFile("completion_proj/deal.json", """
            {"languageVersion": "1.4", "backend": "js",
             "moduleRoots": ["src"], "output": "out"}
            """);
        writeFile("completion_proj/src/main.deal", """
            import * as json from "std/json";

            // @jsonable
            export class C {
              n: number;
            }

            export function main(): null { return null; }

            export async function decoded(): table {
              return json.parse("{\\"n\\":1e17,\\"i\\":2,\\"xs\\":[1.5,1],\\"z\\":null}");
            }

            export async function nullable(): (number | null)[] {
              let t: table = json.parse("{\\"xs\\":[1,null]}");
              let xs: (number | null)[] = t.xs;
              return xs;
            }

            export async function box(): C {
              let t: table = json.parse("{\\"xs\\":[1]}");
              let xs: number[] = t.xs;
              let c: C = {n: 0};
              c.n = xs[0];
              return c;
            }

            export async function aliased(): table {
              let t: table = json.parse("{\\"xs\\":[1.5]}");
              let xs: number[] = t.xs;
              return { a: xs, b: xs };
            }
            """);
        Path entry = tmpDir.resolve("completion_proj/src/main.deal")
            .toAbsolutePath();
        int exit = deal.Main.run(new String[] {"compile", entry.toString()});
        check(exit == 0, "the completion-carrier project compiles through the"
            + " public deal compile: exit=" + exit);
        if (exit != 0) {
            return;
        }
        writeFile("completion_proj/out/completion_runner.js", String.join("\n",
            "\"use strict\";",
            "const $rt = require(\"./deal/runtime\");",
            "const $entry = require(\"./main\");",
            "const $out = [];",
            "// The host-visible graph must hold no runtime carrier object at any",
            "// depth (the internal $jn key).",
            "const $hasCarrier = (v) => {",
            "  const $seen = new Set();",
            "  const $walk = (x) => {",
            "    if (x === null || typeof x !== \"object\") return false;",
            "    if ($seen.has(x)) return false;",
            "    $seen.add(x);",
            "    if (x.$jn === true) return true;",
            "    if (x instanceof Map) {",
            "      for (const $child of x.values()) if ($walk($child)) return true;",
            "      return false;",
            "    }",
            "    if (Array.isArray(x)) {",
            "      for (const $child of x) if ($walk($child)) return true;",
            "      return false;",
            "    }",
            "    for (const $key of Object.keys(x)) if ($walk(x[$key])) return true;",
            "    return false;",
            "  };",
            "  return $walk(v);",
            "};",
            "(async () => {",
            "  const $decoded = await $rt.invokeAsyncExport($entry, \"decoded\", \"table\");",
            "  if ($decoded.$ok !== true) {",
            "    $out.push(\"decoded=FAIL:\" + JSON.stringify($decoded));",
            "  } else {",
            "    const $d = $decoded.$value;",
            "    $out.push(\"decoded=\" + $rt.jsonStringifyText($d));",
            "    $out.push(\"decodedArray=\" + $rt.isJsonArrayTable($rt.tableGet($d, \"xs\")));",
            "    $out.push(\"decodedNull=\" + $rt.tableGet($d, \"z\"));",
            "    $out.push(\"decodedNullTable=\" + $rt.isJsonNullTable($rt.tableGet($d, \"z\")));",
            "    $out.push(\"decodedInt=\" + $rt.tableGet($d, \"i\"));",
            "    $out.push(\"decodedCheck=\" + ($rt.checkType(\"table\", $d) === $d));",
            "  }",
            "  const $nullable = await $rt.invokeAsyncExport($entry, \"nullable\", \"[?number]\");",
            "  if ($nullable.$ok !== true) {",
            "    $out.push(\"nullable=FAIL:\" + JSON.stringify($nullable));",
            "  } else {",
            "    const $n = $nullable.$value;",
            "    $out.push(\"nullable=\" + $rt.jsonStringifyText($n));",
            "    $out.push(\"nullableArray=\" + $rt.isJsonArrayTable($n));",
            "    $out.push(\"nullableLength=\" + $rt.arrayLength($n));",
            "    $out.push(\"nullableSlot=\" + $rt.arrayAt($n, 1));",
            "    $out.push(\"nullableCheck=\" + ($rt.checkType(\"[?number]\", $n) === $n));",
            "  }",
            "  const $box = await $rt.invokeAsyncExport($entry, \"box\", $entry.C.$classname);",
            "  if ($box.$ok !== true) {",
            "    $out.push(\"box=FAIL:\" + JSON.stringify($box));",
            "  } else {",
            "    const $b = $box.$value;",
            "    $out.push(\"boxType=\" + typeof $b.n + \":\" + ($b.n === 1));",
            "    $out.push(\"boxTags=\" + ($b.$kind === \"class\"",
            "      && $b.$classname === $entry.C.$classname));",
            "    $out.push(\"boxWalk=\" + $entry.C$toJson.$f($b, \"runner.js\", 1, 1));",
            "    $out.push(\"boxCheck=\" + ($rt.checkType($entry.C.$classname, $b) === $b));",
            "    $out.push(\"boxCarrier=\" + $hasCarrier($b));",
            "  }",
            "  const $aliased = await $rt.invokeAsyncExport($entry, \"aliased\", \"table\");",
            "  if ($aliased.$ok !== true) {",
            "    $out.push(\"alias=FAIL:\" + JSON.stringify($aliased));",
            "  } else {",
            "    const $a = $aliased.$value;",
            "    $out.push(\"alias=\" + ($rt.tableGet($a, \"a\") === $rt.tableGet($a, \"b\")));",
            "    $out.push(\"aliasText=\" + $rt.jsonStringifyText($rt.tableGet($a, \"a\")));",
            "    $out.push(\"aliasCarrier=\" + $hasCarrier($a));",
            "  }",
            "  process.stdout.write($out.join(\"\\n\") + \"\\n\");",
            "})().catch((e) => { process.stderr.write(\"RUNNER \" + e + \"\\n\"); process.exit(1); });",
            ""));
        NodeResult run = runNodeScript(
            tmpDir.resolve("completion_proj/out").toAbsolutePath(),
            "completion_runner.js");
        check(run.exitCode() == 0 && run.output().equals(String.join("\n",
                "decoded={\"n\":1.0E17,\"i\":2.0,\"xs\":[1.5,1.0],\"z\":null}",
                "decodedArray=true",
                "decodedNull=null",
                "decodedNullTable=false",
                "decodedInt=2",
                "decodedCheck=true",
                "nullable=[1.0,null]",
                "nullableArray=true",
                "nullableLength=2",
                "nullableSlot=null",
                "nullableCheck=true",
                "boxType=number:true",
                "boxTags=true",
                "boxWalk={\"n\":1.0}",
                "boxCheck=true",
                "boxCarrier=false",
                "alias=true",
                "aliasText=[1.5]",
                "aliasCarrier=false")),
            "the invoker's completion projection keeps the decoded array"
                + " carrier, the number-variant spelling, the language null and"
                + " the class field's declared number, with no internal carrier"
                + " in the host-visible graph: " + run.output() + " (exit "
                + run.exitCode() + ")");
    }

    private static void testJsonStoredFunctionE8001() throws Exception {
        System.out.println("-- Node: std/json stringify of a stored function → E8001 --");

        NodeResult run = runDealNode("""
            import * as json from "std/json";
            export function test(): string {
              let t: table = { f: function(): number { return 1; } };
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

        // Direct $rt-surface probes of the pinned walker contract
        // (js-v12-jsonable-completion D2-D5): the top-level object gate
        // (the empty array included, at the root and at a nested class
        // value), extra-key rejection, the three-state roundtrip,
        // the int-range E8004 null collapse, the provided-value
        // failure/zero-defaults phase order, and the toJson E8001 arms.
        NodeResult run = runRuntimeProbe("jsonable-walkers", """
            "use strict";
            const $rt = require("./deal/runtime");
            let $fail = "";
            const $f = (n, j, o, x, e) => {
              const $e = { name: n, jtype: j, optional: !!o, nullable: !!x };
              if (e !== undefined) {
                if (e.element !== undefined) $e.element = e.element;
                if (e.className !== undefined) $e.className = e.className;
                if (e.fields !== undefined) $e.fields = e.fields;
              }
              return $e;
            };
            const $fields = [$f("name", "string", false, false),
              $f("nick", "string", true, true), $f("age", "number", false, false)];
            const $thunk = () => ({ name: "", nick: $rt.MISSING, age: 0 });
            // Top-level gate: scalars, null, and any array — the empty
            // array included — are null; {} decodes to the defaulted
            // instance.
            if ($rt.jsonFromJson("@m/U", $fields, "42", "p.js", 1, 1) !== null) $fail = "scalar";
            if ($rt.jsonFromJson("@m/U", $fields, "null", "p.js", 1, 1) !== null) $fail = "null-doc";
            if ($rt.jsonFromJson("@m/U", $fields, "[1,2]", "p.js", 1, 1) !== null) $fail = "array-doc";
            if ($rt.jsonFromJson("@m/U", $fields, "[]", "p.js", 1, 1) !== null) $fail = "empty-array-doc";
            if ($rt.jsonFromJson("@m/U", $fields, "{}", "p.js", 1, 1) !== null) $fail = "missing-required";
            const $o = $rt.jsonFromJson("@m/U", $fields, '{"name":"","age":0}', "p.js", 1, 1);
            if ($o === null) throw new Error("complete-object-null");
            if ($o.$kind !== "class" || $o.$classname !== "@m/U") $fail = "tags";
            if ($rt.jsonToJson("@m/U", $o, $fields, "p.js", 1, 1)
                !== '{"name":"","age":0}') $fail = "empty-object-text:" + $rt.jsonToJson("@m/U", $o, $fields, "p.js", 1, 1);
            // A nested class value accepts only a JSON object: [] is a
            // validation failure returning the DEAL null.
            const $nestedFields = [$f("child", "class", false, false,
              { className: "@m/U", fields: $fields })];
            if ($rt.jsonFromJson("@m/P", $nestedFields, '{"child":[]}', "p.js", 1, 1) !== null) $fail = "nested-empty-array";
            // Extra-key rejection, before any defaults run.
            if ($rt.jsonFromJson("@m/U", $fields, '{"name":"Ada","age":0,"extra":1}', "p.js", 1, 1) !== null) $fail = "extra-key";
            // Three-state: missing omitted, present-null kept, value kept.
            const $m = $rt.jsonFromJson("@m/U", $fields, '{"name":"A","age":1}', "p.js", 1, 1);
            if ($m === null || $m.nick !== $rt.MISSING) $fail = "three-missing";
            if ($rt.jsonToJson("@m/U", $m, $fields, "p.js", 1, 1) !== '{"name":"A","age":1.0}') $fail = "three-omit";
            const $n = $rt.jsonFromJson("@m/U", $fields, '{"name":"B","nick":null,"age":2}', "p.js", 1, 1);
            if ($n === null || $n.nick !== null) $fail = "three-null";
            if ($rt.jsonToJson("@m/U", $n, $fields, "p.js", 1, 1) !== '{"name":"B","nick":null,"age":2.0}') $fail = "three-null-text";
            // Binary64 magnitudes beyond signed32 remain admitted field values.
            const $large = $rt.jsonFromJson("@m/U", $fields, '{"name":"","age":9007199254740992}', "p.js", 1, 1);
            if ($large === null || $large.age !== 9007199254740992) $fail = "number-value";
            // Provided-value failure runs no defaults (counting thunk).
            let $runs = 0;
            const $counting = () => { $runs++; return { name: "", nick: $rt.MISSING, age: 0 }; };
            if ($rt.jsonFromJson("@m/U", $fields, '{"name":"","age":"x"}', "p.js", 1, 1) !== null) $fail = "bad-number";
            if ($runs !== 0) $fail = "defaults-ran:" + $runs;
            // toJson identity backstop and cycle rejection (E8001).
            try { $rt.jsonToJson("@m/Other", $m, $fields, "p.js", 1, 1); $fail = "identity-pass"; }
            catch (e) { if (e.$dealCode !== "E8001") $fail = "identity-code"; }
            const $nodeFields = [$f("next", "class", false, true,
              { className: "@m/Node", fields: [] })];
            const $node = $rt.classPlan("@m/Node", [{name:"next",descriptor:"?@m/Node",optional:false}], {next:null}, "p.js", 1, 1);
            $rt.setProp($node, "next", $node);
            try { $rt.jsonToJson("@m/Node", $node, $nodeFields, "p.js", 1, 1); $fail = "cycle-pass"; }
            catch (e) { if (e.$dealCode !== "E8001") $fail = "cycle-code"; }
            console.log($fail === "" ? "JSONABLE-WALKERS-OK" : "JSONABLE-WALKERS-FAIL: " + $fail);
            """);
        check(run.exitCode() == 0
                && run.output().equals("JSONABLE-WALKERS-OK"),
            "the jsonable runtime walkers honor the top-level object gate "
                + "(the empty array rejected at the root and at a nested class value), "
                + "extra-key rejection, the three-state "
                + "roundtrip, wide-number admission, the zero-defaults "
                + "phase order, and the toJson E8001 arms: " + run.output());
    }

    private static void testIntrinsicFunctionValues() throws Exception {
        System.out.println("-- Node: ordinary numeric names as function values --");


        NodeResult run = runDealNode("""
            import * as console from "std/console";
            function applyInt(f: (x: number) => number, v: number): number { return f(v); }
            function applyNumber(f: (x: number) => number, v: number): number { return f(v); }
            function int(x: number): number { return x; }
            function number(x: number): number { return x; }
            export function test(): number {
              let f: (x: number) => number = int;
              let a: number = f(3.0);
              let b: number = applyInt(int, 4.0);
              let nb: number = applyNumber(number, 7);
              let wide: (x: number, y: number) => number = number;
              let c: number = wide(9.0, 1.0);
              let g: (x: string) => null = console.log;
              g("intrinsic-console-value");
              if (a === 3 && b === 4 && nb === 7.0 && c === 9) { return 1; }
              return 0;
            }
            """, "intrinsics");
        check(run.exitCode() == 0 && run.output().contains("intrinsic-console-value")
                && run.output().contains("1"),
            "int/number ordinary values, callbacks, arity extension, and the "
                + "console member as a value: " + run.output());
    }

    private static void testAsyncCompletionAndErrors() throws Exception {
        System.out.println("-- Node: async completion and error propagation --");

        NodeResult ok = runDealNode("""
            async function g(): number { return 42; }
            async function greet(name: string): string { return "hi-" + name; }
            async function noop(): null { return; }
            export async function test(): string {
              await noop();
              let x: number = await g();
              if (x !== 42) { return "bad"; }
              return await greet("n");
            }
            """, "async-ok");
        check(ok.exitCode() == 0 && ok.output().equals("hi-n"),
            "the async export's awaited completion value resolves before "
                + "printing: " + ok.output());

        NodeResult bad = runDealNode("""
            import * as math from "std/math";
            async function bomb(): number { return math.sqrt(-1); }
            export async function test(): number { return await bomb(); }
            """, "async-err");
        check(bad.exitCode() == 1 && bad.output().contains("DEAL_ERROR_CODE: E8001"),
            "an awaited error propagates natively at the await site, exit 1: "
                + bad.output());
    }

    private static void testAsyncExportInvokerNode() throws Exception {
        System.out.println("-- Node: $rt.invokeAsyncExport — production async-export invocation --");

        // Positive: the production oracle scenario (parent D9
        // Verification 11) — init via the host's require, main once, the
        // exact async()->null selection, one native await, the byte-exact
        // completion check, and the int[] closure exercised end to end
        // inside the oracle (js-v12-int32-int[] D5).
        NodeResult ok = runInvokerNode("""
            async function bump(b: number[]): number[] {
              b[0] = b[0] + 1;
              return b;
            }
            class Holder { f: async (b: number[]) => number[]; }
            export function main(): null { return null; }
            export async function oracle(): null {
              let b: number[] = [0, 0];
              b[0] = 1;
              b[1] = 41;
              let f: async (b: number[]) => number[] = bump;
              let h: Holder = { f: f };
              let out: number[] = await h.f(b);
              if (out[0] !== 2 || b[0] !== 2 || b[1] !== 41) {
                throw { code: "ORACLE_FAIL", message: "number[] closure content" };
              }
              out[0] = 9;
              if (b[0] !== 9) {
                throw { code: "ORACLE_FAIL", message: "number[] closure identity" };
              }
              return null;
            }
            """, "invoker-ok", "oracle", "null");
        check(ok.exitCode() == 0 && ok.output().equals("INVOKE_OK: null"),
            "invokeAsyncExport(entry, \"oracle\", \"null\") over the "
                + "production oracle yields { $ok: true, $value: null } "
                + "with the number[] closure content/identity assertions "
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
            export class Payload { v: number; }
            export function main(): null { return null; }
            export async function oracle(): null { return null; }
            export async function takes(i: number): number { return i; }
            """, "invoker-wrong-name");
        JsEmission negativeEmission = checkedEmission(negatives);
        Path negativeDir = deployArtifacts(negativeEmission, "Main.js");
        try {
            Files.writeString(negativeDir.resolve("invoker_runner.js"),
                INVOKER_RUNNER_SOURCE);
            for (List<String> c : List.of(
                    List.of("nope", "null", "export not found: nope"),
                    List.of("main", "null", "sync export: expected async()->null, got ()->null"),
                    List.of("takes", "number", "parameterized export: expected async()->number, got async(number)->number"),
                    List.of("oracle", "number", "descriptor mismatch: expected async()->number, got async()->null"),
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

        // DEAL-error signal with the exact source location of the failing call.
        NodeResult rejected = runInvokerNode("""
            import * as math from "std/math";
            async function bomb(): number {
              return math.sqrt(-1);
            }
            export function main(): null { return null; }
            export async function oracle(): null {
              await bomb();
              return null;
            }
            """, "invoker-reject", "oracle", "null");
        check(rejected.exitCode() == 0 && rejected.output().equals(
                "INVOKE_ERROR: E8001 sqrt of negative number at jstest-invoker-reject.deal:3:10"),
            "a rejected oracle yields { $ok: false, $error } with the "
                + "reified E8001 code and the exact source location: "
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
              "languageVersion": "1.4",
              "backend": "js"
            }""");
        writeFile("js_proj/src/orch_main.deal", """
            import * as console from "std/console";
            export function main(): null {
              console.log("js-orchestrator-main");
              return null;
            }
            function add(a: number, b: number): number { return a + b; }
            export function run(): number { return add(20, 22); }
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
            java.util.regex.Matcher mainPublish = java.util.regex.Pattern
                .compile("\\$rt\\.publishModule\\(\"orch_main\", \"main\", "
                    + "(\\$s\\d+)\\);")
                .matcher(js);
            boolean mainPublished = mainPublish.find();
            check(mainPublished,
                "the entry publishes its exported main wrapper");
            if (mainPublished) {
                check(js.matches("(?s).*if \\(\\$require\\.main === \\$module\\)"
                        + " \\{\\s*try \\{\\s*\\$s\\d+ = "
                        + java.util.regex.Pattern.quote(mainPublish.group(1))
                        + "\\.\\$f\\(.*"),
                    "the shim invokes the entry's published main wrapper");
            }
            check(js.contains("[\"log\"].$f(")
                    && js.contains("\"js-orchestrator-main\""),
                "the console.log call emits through the stdlib member wrapper");
        }

        // The deployed project runs under the real node binary: the entry
        // shim invokes main, prints, and exits 0 — a node-dependent
        // sub-check guarded like every node semantic case: node
        // unavailability skips it, never fails it.
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
    }

    private static void testOrchestratorJsNestedModule() throws Exception {
        System.out.println("-- Orchestrator: nested module artifacts and relative requires --");

        writeFile("nest_proj/deal.json",
            "{\"languageVersion\": \"1.4\", \"backend\": \"js\"}");
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
    }

    private static void testOrchestratorJsRejectsUnsupported() throws Exception {
        System.out.println("-- Orchestrator: no-partial-artifact on rejection --");

        writeFile("rej_proj/deal.json",
            "{\"languageVersion\": \"1.4\", \"backend\": \"js\","
                + " \"moduleRoots\": [\"src\"], \"externals\": {"
                + " \"ffi\": {\"declaration\": \"src/ffi.d.deal\""
                + "}}}");
        writeFile("rej_proj/src/ffi.d.deal", """
            // @extern-c
            export function cFn(x: number): number;
            """);
        writeFile("rej_proj/src/lib.deal", """
            import * as ffi from "ffi";
            export function use(): number { return ffi.cFn(1); }
            """);
        writeFile("rej_proj/src/rej_main.deal", """
            import * as lib from "./lib";
            export function main(): null { return null; }
            export function run(): number { return 1; }
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
                "E1044".equals(d.code())),
            "the orchestrator reports E1044: " + orchestrator.diagnostics());
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
                "{\n  \"languageVersion\": \"1.4\",\n  \"backend\": \"js\"\n}\n",
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
                "{\n  \"languageVersion\": \"1.4\",\n"
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
                "{\n  \"languageVersion\": \"1.4\",\n  \"backend\": \"js\",\n"
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
        function pick(n: number): number {
          let r: number = 0;
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
            function pick(n: number): number {
              let r: number = 0;
              if (n === 1) { r = 10; }
              else if (n === 2) { r = 20; }
            %s  return r;
            }
            export function firstBranch(): number { return pick(1); }
            export function laterBranch(): number { return pick(2); }
            export function unmatchedBranch(): number { return pick(3); }
            """.formatted(elseClause);
    }

    /**
     * GitHub #7: an else-if chain whose terminal else is absent is valid
     * language syntax and must emit a closed chain that selects the
     * matching branch and leaves the value unchanged when no branch
     * matches. The branch matrix and the final-else control run through
     * the real frontend → public compilation → node chain; the public
     * release-owned path compiles the issue's exact project with zero
     * diagnostics and executes the published CommonJS artifact under
     * the real node binary.
     */
    private static void testElseIfChainWithoutFinalElse() throws Exception {
        System.out.println("-- Node: else-if chains with an absent terminal else"
            + " (GitHub #7) --");

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

        Path projectDir = Files.createTempDirectory("deal_js_elseif_test_");
        try {
            Files.writeString(projectDir.resolve("deal.json"), """
                {
                  "languageVersion": "1.4",
                  "stdlib": "1.4",
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

            if (Files.isRegularFile(artifact)) {
                NodeResult run = runNodeScript(outputRoot, "main.js");
                check(run.exitCode() == 0 && run.output().equals("pick ok"),
                    "the published CommonJS artifact runs under the real"
                        + " node binary, prints \"pick ok\" and exits 0:"
                        + " exit " + run.exitCode() + ", output '"
                        + run.output() + "'");
            } else {
                fail("the published CommonJS artifact is missing");
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
                "{\n  \"languageVersion\": \"1.4\",\n  \"backend\": \"js\",\n"
                    + "  \"moduleRoots\": [\".\"]\n}\n",
                StandardCharsets.UTF_8);
            Path entry = projectDir.resolve("main.deal");
            Files.writeString(entry,
                "import * as c from \"std/console\";\n"
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
                "{\n  \"languageVersion\": \"1.4\",\n  \"backend\": \"js\",\n"
                    + "  \"moduleRoots\": [\".\"],\n"
                    + "  \"externals\": {\"ffi\": {\"declaration\":"
                    + " \"ffi.d.deal\"}}\n}\n",
                StandardCharsets.UTF_8);

            Files.writeString(projectDir.resolve("ffi.d.deal"),
                "// @extern-c\nexport function cFn(x: number): number;\n",
                StandardCharsets.UTF_8);
            Path entry = projectDir.resolve("main.deal");
            Files.writeString(entry,
                "import * as ffi from \"ffi\";\n"
                    + "export function main(): null {\n"
                    + "  let v: number = ffi.cFn(1);\n"
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
                    .anyMatch(d -> "E1044".equals(d.code())),
                "the rejection is E1044: " + orchestrator.diagnostics());
            check(!Files.exists(outputRoot.resolve("main.js")),
                "no entry artifact is written for the rejected module");
            check(!Files.exists(outputRoot.resolve("ffi.js")),
                "no artifact is written for the declaration file");
        } finally {
            deleteDir(projectDir);
        }
    }
}
