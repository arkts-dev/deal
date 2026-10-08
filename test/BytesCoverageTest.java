package deal.test;

import deal.checker.BuiltinErrorDeclaration;
import deal.codegen.Backend;
import deal.codegen.jvm.JvmBackend;
import deal.codegen.jvm.JvmNames;
import deal.codegen.jvm.JvmSemanticEmitter;
import deal.codegen.lua.LuaSemanticEmitter;
import deal.diagnostics.CompilerDiagnostic;
import deal.diagnostics.DiagnosticCode;
import deal.identity.CanonicalModuleIdentity;
import deal.module.CompilationOrchestrator;
import deal.project.CliOverrides;
import deal.project.ProjectLocator;
import deal.semantic.CheckedProjectBuildResult;
import deal.semantic.CheckedProjectInput;
import deal.semantic.CompilerInvocation;
import deal.semantic.CompilerProfileProvider;
import deal.semantic.HostDeclarationSurface;
import deal.semantic.ReleaseConfiguration;
import deal.semantic.RequirementManifestResult;
import deal.semantic.SemanticLowerer;
import deal.semantic.SemanticOracle;
import deal.semantic.SemanticRequirementManifest;
import deal.semantic.SemanticRuntimeModel;
import deal.semantic.ir.BoundaryContext;
import deal.semantic.ir.BoundaryExecutor;
import deal.semantic.ir.BoundaryFailure;
import deal.semantic.ir.BoundaryKind;
import deal.semantic.ir.BoundaryOutcome;
import deal.semantic.ir.BoundaryValueView;
import deal.semantic.ir.CallMode;
import deal.semantic.ir.ClassFactoryRegistry;
import deal.semantic.ir.ExecutableLoweredProject;
import deal.semantic.ir.FailurePolicyId;
import deal.semantic.ir.FunctionAllocationIdentity;
import deal.semantic.ir.FunctionExecutionBinding;
import deal.semantic.ir.IndexMode;
import deal.semantic.ir.IntrinsicKind;
import deal.semantic.ir.KindPayload;
import deal.semantic.ir.LoweredModuleUnit;
import deal.semantic.ir.ModuleId;
import deal.semantic.ir.NormalizedSlot;
import deal.semantic.ir.OpId;
import deal.semantic.ir.ProjectInterfaceIndex;
import deal.semantic.ir.RuntimeDescriptor;
import deal.semantic.ir.SemanticIrValidator;
import deal.semantic.ir.SemanticOp;
import deal.semantic.ir.SemanticOpKind;
import deal.semantic.ir.SemanticProfile;
import deal.semantic.ir.StructuredBodyTable;
import deal.semantic.ir.ValueId;
import deal.test.conformance.CorpusFfi;
import deal.test.conformance.ErrorSnapshot;
import deal.test.conformance.SidecarExpectations;

import java.io.File;
import java.io.InputStream;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class BytesCoverageTest {

    private static int passed = 0;
    private static int failed = 0;

    private static void check(boolean condition, String message) {
        if (condition) {
            passed++;
        } else {
            failed++;
            System.err.println("FAIL: " + message);
        }
    }

    private static void checkEq(Object expected, Object actual, String message) {
        check(java.util.Objects.equals(expected, actual),
            message + " (expected " + expected + ", got " + actual + ")");
    }

    private static void fail(String message) {
        failed++;
        System.err.println("FAIL: " + message);
    }

    private static final Path CORPUS = Path.of("test", "conformance");
    private static final String BYTES_DIR = "backend-runtime/bytes";
    private static final String SOURCE_LOCATION_DIR = "backend-runtime/source-location";

    // =========================================================================
    // The bytes corpus table
    // =========================================================================

    /**
     * One driveable bytes corpus fixture: its corpus directory, its
     * relative path, the export the drive's entry calls, the export's
     * declared return type, its companions, and the pinned failure row
     * ({@code null} for a runtime-ok fixture).
     */
    private record BytesFixture(String directory, String relativePath, String export,
                                String resultType, List<String> companions, String code,
                                String message, int line, int column, String expected,
                                String actual) {

        BytesFixture(String directory, String relativePath, String export,
                     String resultType, List<String> companions, String code,
                     String message, int line, int column) {
            this(directory, relativePath, export, resultType, companions, code,
                message, line, column, null, null);
        }

        boolean runtimeOk() {
            return code == null;
        }

        /** True for the rows whose sidecar also pins the expected/actual tokens. */
        boolean pinsExpectedActual() {
            return expected != null && actual != null;
        }

        String fixtureFile() {
            return directory + "/" + relativePath + ".deal";
        }

        String sidecarFile() {
            return directory + "/" + relativePath + ".expect.json";
        }

        String what() {
            return relativePath;
        }
    }

    private static BytesFixture ok(String path, String export, String type,
                                   String... companions) {
        return new BytesFixture(BYTES_DIR, path, export, type, List.of(companions), null,
            null, 0, 0);
    }

    private static BytesFixture error(String path, String export, String type,
                                      String code, String message, int line, int column) {
        return new BytesFixture(BYTES_DIR, path, export, type, List.of(), code, message,
            line, column);
    }

    /**
     * One failure row whose sidecar also pins the expected/actual tokens
     * (the exact-tuple rows, including the decoded nested element row).
     */
    private static BytesFixture pinnedError(String path, String export, String type,
                                            String code, String message, int line,
                                            int column, String expected, String actual) {
        return new BytesFixture(BYTES_DIR, path, export, type, List.of(), code, message,
            line, column, expected, actual);
    }

    /**
     * One {@code source-location} bytes fixture: the same pinned rows with
     * the error's own source coordinate (the acceptance's
     * {@code source-location/bytes-*} clause).
     */
    private static BytesFixture sourceError(String path, String export, String type,
                                            String code, String message, int line,
                                            int column) {
        return new BytesFixture(SOURCE_LOCATION_DIR, path, export, type, List.of(), code,
            message, line, column);
    }

    /** The E8012/E8013 and bytes-bearing signature failure fixtures. */
    private static final List<BytesFixture> FAILURES = List.of(
        error("bytes-negative-length-error", "main", "null", "E8012",
            "bytes length must be non-negative", 7, 22),
        error("bytes-index-bounds", "test_bytes_index_bounds", "int", "E8012",
            "bytes index out of bounds", 8, 10),
        error("bytes-read-at-length-error", "main", "null", "E8012",
            "bytes index out of bounds", 8, 20),
        error("bytes-negative-read-error", "main", "null", "E8012",
            "bytes index out of bounds", 8, 20),
        error("bytes-write-at-length-error", "main", "null", "E8012",
            "bytes index out of bounds", 8, 3),
        error("bytes-write-range", "test_bytes_write_range", "null", "E8013",
            "bytes value out of range", 8, 3),
        error("bytes-write-negative-error", "main", "null", "E8013",
            "bytes value out of range", 8, 3),
        pinnedError("bytes-dynamic-function-mismatch-e8010",
            "test_bytes_dynamic_function_mismatch", "int", "E8010",
            "function signature mismatch: expected (bytes)->bytes, got (int)->int", 12, 10,
            "(bytes)->bytes", "(int)->int"),
        pinnedError("bytes-dynamic-async-function-mismatch-e8010",
            "test_bytes_dynamic_async_function_mismatch", "int", "E8010",
            "function signature mismatch: expected async(bytes)->bytes, got (int)->int",
            12, 16, "async(bytes)->bytes", "(int)->int"),
        pinnedError("bytes-dynamic-wrong-kind-e8001", "test_bytes_dynamic_wrong_kind",
            "int", "E8001", "expected bytes", 8, 10, "bytes", "string"),
        pinnedError("bytes-fn-adapter-e8010", "test_bytes_fn_adapter_e8010", "int",
            "E8010",
            "function signature mismatch: expected (bytes,int)->bytes, got (bytes)->bytes",
            8, 21, "(bytes,int)->bytes", "(bytes)->bytes"),
        pinnedError("bytes-dynamic-nested-first-element-e8003",
            "test_bytes_dynamic_nested_first_element", "int", "E8003",
            "array element 1 type mismatch", 10, 11, "[bytes]", "table"),
        // The two source-location pins (the acceptance's
        // source-location/bytes-* clause): the same rows at the error's own
        // source coordinate — the indexing expression for E8012 and the
        // assignment expression for E8013.
        sourceError("bytes-index-bounds-source", "test_bytes_index_bounds_location",
            "int", "E8012", "bytes index out of bounds", 9, 10),
        sourceError("bytes-write-range-source", "test_bytes_write_range_location",
            "null", "E8013", "bytes value out of range", 8, 3));

    /** The runtime-ok fixtures whose closure the drive owns. */
    private static final List<BytesFixture> RUNTIME_OK = List.of(
        ok("bytes-array-closure", "test_bytes_array_closure", "int"),
        ok("bytes-array-container-ops", "test_bytes_array_container_ops", "null"),
        ok("bytes-buffer-ops", "main", "null"),
        ok("bytes-class-default", "main", "null"),
        ok("bytes-class-field-descriptor", "test_bytes_class_field_descriptor", "string"),
        ok("bytes-descriptor-boundary", "test_bytes_descriptor_boundary", "string"),
        ok("bytes-dynamic-boundary-ok", "test_bytes_dynamic_boundary_ok", "string"),
        ok("bytes-dynamic-nullable-function-ok", "test_bytes_dynamic_nullable_function_ok",
            "string"),
        ok("bytes-fn-adapters", "test_bytes_fn_adapters", "int"),
        ok("bytes-function-array-closure", "test_bytes_function_array_closure", "int"),
        ok("bytes-identity-equality", "test_bytes_identity_equality", "int"),
        ok("bytes-length", "test_bytes_length", "null"),
        ok("bytes-module-identity", "test_bytes_module_identity", "string",
            "bytes_module_lib"),
        ok("bytes-nested-arrays", "test_bytes_nested_arrays", "null"),
        ok("bytes-nested-fn-shapes", "test_bytes_nested_fn_shapes", "int"),
        ok("bytes-sync-fn-shapes", "test_bytes_sync_fn_shapes", "int"),
        ok("bytes-write-single-evaluation", "test_bytes_write_single_evaluation", "null"),
        ok("bytes-write-validation-order", "test_bytes_write_validation_after_rhs",
            "null"),
        ok("bytes-write-zero", "main", "null"),
        ok("bytes-zero-length", "main", "null"),        // The host-importing integration fixture: runtime-ok, driven by the
        // host-ABI section below (its test export is async), so the generic
        // corpus loop routes it to that drive instead of the sync entry.
        new BytesFixture(BYTES_DIR, "bytes-class-default-integration",
            "test_bytes_class_default_integration", "int", List.of(), null, null, 0, 0),
        ok("bytes-fn-xmod", "test_bytes_fn_xmod", "int", "bytes-fn-xmod-lib"));

    private static final List<String> NESTED_DECLARATION_FIXTURES = List.of(
        "bytes-boundary-order");

    /**
     * The async-entry fixtures: their own test export is async, so the
     * generic sync-entry drive cannot call it. Each is driven through the
     * oracle's async-entry invocation, the shared three-consumer async
     * matrix (trace parity), and both production project artifacts' async
     * dispatch entries — the fixture's own TEST_FAIL assertions and its
     * bytes-typed dynamic dispatch execute on every consumer.
     */
    private static final Map<String, String> ASYNC_ENTRY_FIXTURES = Map.of(
        "bytes-async-closure", "test_bytes_async_closure");

    /**
     * The fixture whose closure imports the host module
     * {@code host/bytes_roundtrip}: it compiles and lowers once its
     * declaration module is materialized, and its async test export is
     * driven by the host-ABI section with a bytes-aware host implementation
     * on every consumer (the oracle's responder, the deployed Lua host
     * module, and the deployed JVM host class), so the generic sync-entry
     * loop routes it there.
     */
    private static final String HOST_FIXTURE = "bytes-class-default-integration";

    private static List<BytesFixture> allFixtures() {
        List<BytesFixture> all = new ArrayList<>();
        all.addAll(FAILURES);
        all.addAll(RUNTIME_OK);
        for (String nested : NESTED_DECLARATION_FIXTURES) {
            all.add(new BytesFixture(BYTES_DIR, nested, "main", "null", List.of(), null,
                null, 0, 0));
        }
        for (String async : ASYNC_ENTRY_FIXTURES.keySet()) {
            all.add(new BytesFixture(BYTES_DIR, async, "main", "null", List.of(), null,
                null, 0, 0));
        }
        all.add(new BytesFixture(BYTES_DIR, "bytes_module_lib", "echo", "bytes",
            List.of(), null, null, 0, 0));
        all.add(new BytesFixture(BYTES_DIR, "bytes-fn-xmod-lib", "makeId", "null",
            List.of(), null, null, 0, 0));
        return all;
    }

    // =========================================================================
    // 1. The corpus pins
    // =========================================================================

    private static void testCorpusPins() throws Exception {
        System.out.println("-- the bytes corpus sidecars keep their pinned rows --");
        for (BytesFixture fixture : FAILURES) {
            Path sidecar = CORPUS.resolve(fixture.sidecarFile());
            Path file = CORPUS.resolve(fixture.fixtureFile());
            check(Files.exists(sidecar), fixture.what() + " carries its sidecar");
            check(Files.exists(file), fixture.what() + " is a corpus fixture");
            if (!Files.exists(sidecar) || !Files.exists(file)) {
                continue;
            }
            SidecarExpectations.StructuredExpectationSidecar parsed =
                SidecarExpectations.StructuredExpectationSidecar.parse(
                    Files.readString(sidecar, StandardCharsets.UTF_8));
            for (String backend : List.of("luajit", "jvm", "js")) {
                check(parsed.byBackend().containsKey(backend), fixture.what()
                    + ": the sidecar pins the '" + backend + "' lane");
            }
            SidecarExpectations.RuntimeExpectation expectation =
                parsed.expectationFor("luajit");
            check(expectation instanceof SidecarExpectations.RuntimeExpectation.Executed,
                fixture.what() + ": the LuaJIT leg is an executed expectation");
            if (!(expectation
                    instanceof SidecarExpectations.RuntimeExpectation.Executed executed)) {
                continue;
            }
            checkEq("runtime-error", executed.mode(), fixture.what() + ": the pinned mode");
            SidecarExpectations.ErrorExpectation row = executed.error();
            check(row != null, fixture.what() + ": the pinned error snapshot");
            if (row == null) {
                continue;
            }
            checkEq(fixture.code(), row.code(), fixture.what() + ": the pinned code");
            checkEq(fixture.message(), row.message(), fixture.what()
                + ": the pinned message");
            checkEq(fixture.line(), row.line(), fixture.what() + ": the pinned line");
            checkEq(fixture.column(), row.column(), fixture.what()
                + ": the pinned column");
            checkEq(fixture.expected(), row.expected().orElse(null), fixture.what()
                + ": the pinned expected token");
            checkEq(fixture.actual(), row.actual().orElse(null), fixture.what()
                + ": the pinned actual token");
            checkEq(fixture.fixtureFile(), row.sourceFile(), fixture.what()
                + ": the pinned source file");
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            check(fixture.line() <= lines.size(), fixture.what()
                + ": the pinned line is inside the fixture");
            if (fixture.line() <= lines.size()) {
                String line = lines.get(fixture.line() - 1);
                check(fixture.column() <= line.length() + 1, fixture.what()
                    + ": the pinned column is inside the pinned line: '" + line + "'");
            }
            checkEq(1, executed.exitCode(), fixture.what() + ": the pinned exit code");
        }
        for (BytesFixture fixture : RUNTIME_OK) {
            Path sidecar = CORPUS.resolve(fixture.sidecarFile());
            Path file = CORPUS.resolve(fixture.fixtureFile());
            check(Files.exists(sidecar), fixture.what() + " carries its sidecar");
            check(Files.exists(file), fixture.what() + " is a corpus fixture");
            if (!Files.exists(sidecar)) {
                continue;
            }
            SidecarExpectations.StructuredExpectationSidecar parsed =
                SidecarExpectations.StructuredExpectationSidecar.parse(
                    Files.readString(sidecar, StandardCharsets.UTF_8));
            SidecarExpectations.RuntimeExpectation expectation =
                parsed.expectationFor("luajit");
            if (!(expectation
                    instanceof SidecarExpectations.RuntimeExpectation.Executed executed)) {
                fail(fixture.what() + ": the LuaJIT leg is an executed expectation");
                continue;
            }
            checkEq("runtime-ok", executed.mode(), fixture.what() + ": the pinned mode");
            checkEq(0, executed.exitCode(), fixture.what() + ": the pinned exit code");
            checkEq("", new String(executed.stdout(), StandardCharsets.UTF_8),
                fixture.what() + ": the pinned stdout");
            checkEq("", new String(executed.stderr(), StandardCharsets.UTF_8),
                fixture.what() + ": the pinned stderr");
            check(executed.error() == null, fixture.what()
                + ": a runtime-ok fixture pins no error snapshot");
        }
        // The bytes element contract's four pinning fixtures are exactly the
        // negative length, the bounds read/write, and the write range.
        for (String required : List.of("bytes-negative-length-error",
                "bytes-index-bounds", "bytes-read-at-length-error",
                "bytes-write-at-length-error", "bytes-write-range",
                "bytes-write-negative-error")) {
            check(FAILURES.stream().anyMatch(f -> f.relativePath().equals(required)),
                "the failure fixture set carries '" + required + "'");
        }
    }

    // =========================================================================
    // 2. The production-entry drive harness
    // =========================================================================

    private record Compiled(
        Path root,
        CheckedProjectInput checkedProject,
        ProjectInterfaceIndex index,
        List<SemanticRequirementManifest> manifests,
        HostDeclarationSurface surface,
        Map<ModuleId, CanonicalModuleIdentity> identities,
        int strippedHeaderLines) {
    }

    private record Drive(
        Compiled compiled,
        SemanticLowerer.ProjectLoweringResult result,
        ModuleId fixtureModule,
        LoweredModuleUnit unit,
        BytesFixture spec) {

        ExecutableLoweredProject project() {
            return result.project();
        }

        Map<ModuleId, StructuredBodyTable> tables() {
            return result.tables();
        }

        Map<ModuleId, ClassFactoryRegistry> registries() {
            return result.registries();
        }
    }

    private static CompilerInvocation productionInvocation() {
        return CompilerProfileProvider.resolve(ReleaseConfiguration.CURRENT_RELEASE_STATE,
            ReleaseConfiguration.releaseCapabilityRegistry());
    }

    /**
     * Materializes one bytes corpus fixture (headers stripped, the drive's
     * own entry added) and compiles it through the real orchestrator.
     */
    private static Compiled compileFixture(BytesFixture fixture) throws Exception {
        Path root = Files.createTempDirectory("bytes-corpus-");
        Path corpusRoot = root.resolve("corpus");
        Path fixtureFile = corpusRoot.resolve(fixture.fixtureFile());
        Files.createDirectories(fixtureFile.getParent());
        String raw = Files.readString(CORPUS.resolve(fixture.fixtureFile()),
            StandardCharsets.UTF_8);
        String stripped = ConformanceHarnessMetadata.stripClassificationHeaders(raw);
        int strippedLines = raw.split("\n", -1).length
            - stripped.split("\n", -1).length;
        Files.writeString(fixtureFile, stripped, StandardCharsets.UTF_8);
        for (String companion : fixture.companions()) {
            Path companionFile = corpusRoot.resolve(BYTES_DIR)
                .resolve(companion + ".deal");
            Files.createDirectories(companionFile.getParent());
            Files.writeString(companionFile,
                ConformanceHarnessMetadata.stripClassificationHeaders(
                    Files.readString(CORPUS.resolve(BYTES_DIR)
                        .resolve(companion + ".deal"), StandardCharsets.UTF_8)),
                StandardCharsets.UTF_8);
        }
        Path entry = "main".equals(fixture.export())
            ? fixtureFile
            : root.resolve("app.deal");
        if (!"main".equals(fixture.export())) {
            Files.writeString(entry, driver(fixture), StandardCharsets.UTF_8);
        }
        // The host-importing fixture materializes its corpus declaration
        // module (the host-ABI child's fixture) and the externals mapping of
        // the drive's temp project, so the fixture's own closure — and the
        // bytes constructs it carries — compile and lower here too.
        Map<String, String> externals = null;
        boolean hostDeclared = HOST_FIXTURE.equals(fixture.relativePath());
        if (hostDeclared) {
            Path declaration = root.resolve("host").resolve("bytes_roundtrip.d.deal");
            Files.createDirectories(declaration.getParent());
            Files.writeString(declaration, ConformanceHarnessMetadata
                .stripClassificationHeaders(Files.readString(Path.of("test",
                    "conformance", "host-fixtures", "bytes_roundtrip.d.deal"),
                    StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
            externals = new LinkedHashMap<>();
            externals.put("host.bytes_roundtrip",
                declaration.toAbsolutePath().toString());
            externals.put("host/bytes_roundtrip",
                declaration.toAbsolutePath().toString());
        }
        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            entry.toAbsolutePath(), root.resolve("out"), false, false, false, false,
            Backend.LUAJIT, externals, List.of(root.toAbsolutePath()),
            Path.of("std").toAbsolutePath().normalize(), null, productionInvocation());
        boolean compiled = orchestrator.compile();
        check(compiled, fixture.what() + ": the production orchestrator compiles "
            + "the project: " + orchestrator.diagnostics());
        CheckedProjectBuildResult built = orchestrator.checkedProject();
        RequirementManifestResult manifests = orchestrator.requirementManifests();
        check(built != null && built.input() != null && built.index() != null
                && !built.hasErrors(),
            fixture.what() + ": the checked project builds: "
                + (built == null ? "null" : built.diagnostics()));
        if (!compiled || built == null || built.input() == null || built.index() == null
                || built.hasErrors() || manifests == null
                || manifests.manifests() == null
                || orchestrator.hostDeclarationSurface() == null) {
            deleteRecursively(root);
            return null;
        }
        Map<ModuleId, CanonicalModuleIdentity> identities = new LinkedHashMap<>();
        for (ModuleId declaration : orchestrator.hostDeclarationSurface().moduleIds()) {
            identities.put(declaration,
                new CanonicalModuleIdentity.ExternalModule(hostDeclared
                    ? declaration.path().replace('/', '.')
                    : declaration.path()));
        }
        return new Compiled(root, built.input(), built.index(), manifests.manifests(),
            orchestrator.hostDeclarationSurface(), identities, strippedLines);
    }

    /** The drive's own entry: it imports the fixture and calls its test export. */
    private static String driver(BytesFixture fixture) {
        String specifier = fixture.fixtureFile();
        if (specifier.endsWith(".deal")) {
            specifier = specifier.substring(0, specifier.length() - ".deal".length());
        }
        String call = fixture.runtimeOk() && "main".equals(fixture.export())
            && "null".equals(fixture.resultType())
            ? "  fx.main()\n"
            : "  let r: " + fixture.resultType() + " = fx." + fixture.export() + "()\n";
        return "import * as fx from \"./corpus/" + specifier + "\"\n\n"
            + "export function main(): null {\n" + call + "  return null\n}\n";
    }

    /** The production project lowering entry over one compiled fixture. */
    private static Drive lower(Compiled compiled, BytesFixture fixture) {
        SemanticLowerer.ProjectLoweringResult result = SemanticLowerer.lowerProject(
            productionInvocation(), compiled.checkedProject(), compiled.index(),
            compiled.manifests(), compiled.surface(), compiled.identities(), Map.of(),
            BuiltinErrorDeclaration.synthesized(
                compiled.checkedProject().modules().get(0).ast().span()),
            List.of(IntrinsicKind.INT_CONVERT, IntrinsicKind.NUMBER_CONVERT,
                IntrinsicKind.BYTES_NEW), Set.of());
        check(result.project() != null, fixture.what() + ": the production project "
            + "entry lowers the fixture with zero diagnostics: " + result.diagnostics());
        if (result.project() == null) {
            return null;
        }
        ModuleId fixtureModule = null;
        for (ModuleId module : result.project().modules().keySet()) {
            if (module.path().endsWith("." + fixture.relativePath())) {
                fixtureModule = module;
            }
        }
        check(fixtureModule != null, fixture.what() + ": the fixture module is part of "
            + "the closure: " + result.project().modules().keySet());
        if (fixtureModule == null) {
            return null;
        }
        LoweredModuleUnit unit = result.project().modules().get(fixtureModule);
        check(carriesBytesConstruct(unit), fixture.what() + ": the fixture's "
            + "lowered unit carries its bytes construct");
        Optional<CompilerDiagnostic> gate = SemanticIrValidator.validate(
            result.project(), new SemanticIrValidator.ComparisonFacts(
                unit.interfaceHash(), SemanticProfile.DEAL_V1_2_INT32,
                ReleaseConfiguration.releaseCapabilityRegistry()
                    .capabilityRegistryHash()));
        check(gate.isEmpty(), fixture.what() + ": the closed schema and bindings gates "
            + "accept the produced closure: "
            + gate.map(CompilerDiagnostic::message).orElse("admission"));
        if (gate.isPresent()) {
            return null;
        }
        return new Drive(compiled, result, fixtureModule, unit, fixture);
    }

    /** The compiled source coordinate of one pinned raw coordinate. */
    private static String compiledOrigin(Drive drive, int rawLine, int rawColumn) {
        Path mirror = drive.compiled().root().resolve("corpus")
            .resolve(drive.spec().fixtureFile()).toAbsolutePath();
        return mirror + ":" + (rawLine - drive.compiled().strippedHeaderLines())
            + ":" + rawColumn;
    }

    private static void deleteRecursively(Path path) {
        if (path == null) {
            return;
        }
        try {
            if (Files.exists(path)) {
                try (var walk = Files.walk(path)) {
                    walk.sorted(Comparator.reverseOrder()).forEach(entry -> {
                        try {
                            Files.deleteIfExists(entry);
                        } catch (java.io.IOException ignored) {
                            // best effort
                        }
                    });
                }
            }
        } catch (java.io.IOException ignored) {
            // best effort
        }
    }

    // =========================================================================
    // 3. The corpus drive: oracle + both production artifacts
    // =========================================================================

    private static final Set<String> CORPUS_MATRIX_FIXTURES = Set.of(
        "bytes-index-bounds",
        "bytes-write-range",
        "bytes-write-single-evaluation",
        "bytes-write-validation-order",
        "bytes-fn-xmod",
        "bytes-dynamic-function-mismatch-e8010");

    private static void testCorpusDrive() throws Exception {
        System.out.println("-- the bytes corpus through the one production pipeline: "
            + "oracle + shared LuaJIT + shared JVM --");
        for (String required : List.of("bytes-dynamic-boundary-ok",
                "bytes-dynamic-nullable-function-ok", "bytes-dynamic-function-mismatch-e8010",
                "bytes-dynamic-wrong-kind-e8001", "bytes-async-closure", HOST_FIXTURE)) {
            check(allFixtures().stream().anyMatch(f -> f.relativePath().equals(required)),
                "the drive covers the acceptance-listed fixture '" + required + "'");
        }
        for (BytesFixture fixture : allFixtures()) {
            if ("bytes_module_lib".equals(fixture.relativePath())
                    || "bytes-fn-xmod-lib".equals(fixture.relativePath())) {
                continue;
            }
            if (HOST_FIXTURE.equals(fixture.relativePath())) {
                // The async-export host fixture: driven with its own
                // bytes-aware host implementation on every consumer.
                driveHostFixture(fixture);
                continue;
            }
            if (ASYNC_ENTRY_FIXTURES.containsKey(fixture.relativePath())) {
                // The async-export fixture: driven through the async-entry
                // surface of every consumer.
                driveAsyncFixture(fixture,
                    ASYNC_ENTRY_FIXTURES.get(fixture.relativePath()));
                continue;
            }
            Compiled compiled = compileFixture(fixture);
            if (compiled == null) {
                continue;
            }
            try {
                Drive drive = lower(compiled, fixture);
                if (drive == null) {
                    continue;
                }
                List<SemanticRuntimeModel.ConsumerRun> runs;
                if (CORPUS_MATRIX_FIXTURES.contains(fixture.relativePath())) {
                    SemanticDifferentialHarness.Expectation expectation = fixture.runtimeOk()
                        ? SemanticDifferentialHarness.Expectation.success(fixture.what(),
                            List.of(), "null")
                        : SemanticDifferentialHarness.Expectation.failure(fixture.what(),
                            List.of(), fixture.code(),
                            compiledOrigin(drive, fixture.line(), fixture.column()));
                    Path workspace = Files.createTempDirectory("bytes-matrix-");
                    SemanticDifferentialHarness.Verdict verdict;
                    try {
                        verdict = SemanticDifferentialHarness.runProject(drive.project(),
                            drive.tables(), drive.registries(), expectation, workspace);
                    } finally {
                        deleteRecursively(workspace);
                    }
                    checkEq(3, verdict.runs().size(), fixture.what() + ": the drive produced "
                        + "the three consumers: " + verdict.failures());
                    check(verdict.pass(), fixture.what() + ": the three-consumer "
                        + "differential verdict passes (the pinned outcome and origin, the "
                        + "traces event-for-event): " + verdict.failures());
                    runs = verdict.runs();
                } else {
                    for (LoweredModuleUnit unit : drive.project().modules().values()) {
                        Optional<CompilerDiagnostic> chainFailure =
                            deal.semantic.ir.AddressChainProtocol.validate(unit);
                        check(chainFailure.isEmpty(), fixture.what()
                            + ": the address-chain protocol accepts module "
                            + unit.moduleId().path() + ": " + chainFailure);
                    }
                    SemanticRuntimeModel.ConsumerRun oracle =
                        SemanticOracle.executeProjectInits(drive.project(), drive.tables(),
                            drive.registries(), null);
                    check(!oracle.trace().isEmpty(), fixture.what()
                        + ": the oracle produces semantic events");
                    checkEq(List.of(), oracle.effects(), fixture.what()
                        + ": the oracle produces the pinned empty effects");
                    runs = List.of(oracle);
                }
                if (fixture.runtimeOk()) {
                    for (SemanticRuntimeModel.ConsumerRun run : runs) {
                        check(run.terminal()
                                instanceof SemanticRuntimeModel.Terminal.Success success
                                && "null".equals(success.resultAtom()),
                            run.consumer() + " (" + fixture.what() + "): the drive's entry "
                                + "succeeds: " + run.terminal());
                    }
                } else {
                    String expectedOrigin = compiledOrigin(drive, fixture.line(),
                        fixture.column());
                    for (SemanticRuntimeModel.ConsumerRun run : runs) {
                        boolean pinnedTerminal = run.terminal()
                                instanceof SemanticRuntimeModel.Terminal.DealFailure
                                    terminal
                                && fixture.code().equals(terminal.error().code())
                                && fixture.message().equals(terminal.error().message())
                                && expectedOrigin.equals(terminal.error().origin());
                        check(pinnedTerminal, run.consumer() + " (" + fixture.what()
                            + "): the terminal is the pinned row at the pinned origin: "
                            + run.terminal());
                        if (pinnedTerminal && fixture.pinsExpectedActual()
                                && run.terminal()
                                    instanceof SemanticRuntimeModel.Terminal.DealFailure
                                        terminal) {
                            checkEq(fixture.expected(), terminal.error().expected(),
                                run.consumer() + " (" + fixture.what() + "): the oracle "
                                    + "expected token is pin-exact");
                            checkEq(fixture.actual(), terminal.error().actual(),
                                run.consumer() + " (" + fixture.what() + "): the oracle "
                                    + "actual token is pin-exact");
                        }
                    }
                }

                // The production artifacts under the real toolchains. Every
                // capture is compared exactly (status, complete streams,
                // capture health) before the framed outcome is read.
                ArtifactRun lua = luaProduction(drive);
                ArtifactRun jvm = jvmProduction(drive);
                if (fixture.runtimeOk()) {
                    assertTranscript(fixture.what(), "luajit probe", lua.probe(), 0,
                        "OK\n");
                    assertTranscript(fixture.what(), "jvm probe", jvm.probe(), 0,
                        "OK\n");
                    check(lua.terminal() == null && jvm.terminal() == null,
                        fixture.what() + ": a runtime-ok fixture runs no direct "
                            + "terminal artifact");
                    checkEq("OK", lua.outcome(), fixture.what()
                        + ": the LuaJIT production artifact runs the fixture to its "
                        + "pinned outcome");
                    checkEq("OK", jvm.outcome(), fixture.what()
                        + ": the JVM production artifact runs the fixture to its pinned "
                        + "outcome");
                } else {
                    String expectedOrigin = compiledOrigin(drive, fixture.line(),
                        fixture.column());
                    String expectedPrefix = "ERR:" + fixture.code() + "|"
                        + fixture.message() + "|" + expectedOrigin + "|";
                    String probeTranscript = expectedPrefix
                        + (fixture.pinsExpectedActual() ? fixture.expected() : "")
                        + "|"
                        + (fixture.pinsExpectedActual() ? fixture.actual() : "")
                        + "\n";
                    String terminalTranscript = "DEAL_ERROR_CODE: "
                        + fixture.code() + "\n";
                    assertTranscript(fixture.what(), "luajit probe", lua.probe(), 0,
                        probeTranscript);
                    assertTranscript(fixture.what(), "jvm probe", jvm.probe(), 0,
                        probeTranscript);
                    assertTranscript(fixture.what(), "luajit terminal", lua.terminal(),
                        1, terminalTranscript);
                    assertTranscript(fixture.what(), "jvm terminal", jvm.terminal(), 1,
                        terminalTranscript);
                    if (fixture.pinsExpectedActual()) {
                        // The exact-tuple rows (including the decoded nested
                        // element row): a divergent field fails by value.
                        String pinnedOutcome = expectedPrefix + fixture.expected()
                            + "|" + fixture.actual();
                        checkEq(pinnedOutcome, lua.outcome(), fixture.what()
                            + ": the LuaJIT production artifact reproduces the pinned "
                            + "expected/actual tuple");
                        checkEq(pinnedOutcome, jvm.outcome(), fixture.what()
                            + ": the JVM production artifact reproduces the pinned "
                            + "expected/actual tuple");
                    } else {
                        check(lua.outcome().startsWith(expectedPrefix), fixture.what()
                            + ": the LuaJIT production artifact projects the pinned row "
                            + "at the pinned origin: " + lua.outcome());
                        check(jvm.outcome().startsWith(expectedPrefix), fixture.what()
                            + ": the JVM production artifact projects the pinned row at "
                            + "the pinned origin: " + jvm.outcome());
                    }
                }
            } finally {
                deleteRecursively(compiled.root());
            }
        }
    }

    // =========================================================================
    // 4. The read shape and the seven-child write chain
    // =========================================================================

    private static final String SHAPE_SOURCE = """
        export function main(): null {
          let b: bytes = bytes(2);
          let alias: bytes = b;
          b[0] = 7;
          let read: int = b[1];
          let atEnd: int = b[2];
          if (alias[0] !== 7 || read !== 0) {
            throw { code: "TEST_FAIL", message: "shape drive" }
          }
          return null
        }
        """;

    private static void testReadShapeAndWriteChain() throws Exception {
        System.out.println("-- the bytes read shape and the seven-child write chain --");
        Path root = Files.createTempDirectory("bytes-shape-");
        Path src = root.resolve("src");
        Files.createDirectories(src);
        Files.writeString(src.resolve("main.deal"), SHAPE_SOURCE, StandardCharsets.UTF_8);
        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            src.resolve("main.deal").toAbsolutePath(), root.resolve("out"), false,
            false, false, false, Backend.LUAJIT, null, List.of(src.toAbsolutePath()),
            Path.of("std").toAbsolutePath().normalize(), null, productionInvocation());
        check(orchestrator.compile(), "the shape drive compiles: "
            + orchestrator.diagnostics());
        CheckedProjectBuildResult built = orchestrator.checkedProject();
        RequirementManifestResult manifests = orchestrator.requirementManifests();
        SemanticLowerer.ProjectLoweringResult result = SemanticLowerer.lowerProject(
            productionInvocation(), built.input(), built.index(), manifests.manifests(),
            orchestrator.hostDeclarationSurface(), Map.of(), Map.of(),
            BuiltinErrorDeclaration.synthesized(
                built.input().modules().get(0).ast().span()),
            List.of(IntrinsicKind.INT_CONVERT, IntrinsicKind.NUMBER_CONVERT,
                IntrinsicKind.BYTES_NEW), Set.of());
        check(result.project() != null, "the shape drive lowers: " + result.diagnostics());
        if (result.project() == null) {
            deleteRecursively(root);
            return;
        }
        try {
            LoweredModuleUnit unit = result.project().modules().get(new ModuleId("main"));
            check(unit != null, "the shape drive's unit is in the closure");
            if (unit == null) {
                return;
            }
            List<SemanticOp> reads = opsOfKind(unit, SemanticOpKind.INDEX_READ);
            checkEq(3, reads.size(), "the fixture produces the three bytes element "
                + "reads (b[1], b[2], and alias[0]; the write chain's length read is "
                + "an ARRAY_LENGTH child)");
            int checkedReads = 0;
            for (SemanticOp read : reads) {
                KindPayload.IndexReadPayload payload =
                    (KindPayload.IndexReadPayload) read.payload();
                if (!(read.operandTypes().get(0) instanceof RuntimeDescriptor.Bytes)) {
                    continue;
                }
                checkedReads++;
                SemanticOp normalize = opById(unit, producerOf(unit, payload.slot()));
                check(normalize != null
                        && normalize.kind() == SemanticOpKind.INDEX_NORMALIZE,
                    "the bytes read's slot is produced by an INDEX_NORMALIZE");
                if (normalize == null) {
                    continue;
                }
                checkEq(IndexMode.BYTES_READ,
                    ((KindPayload.IndexNormalizePayload) normalize.payload()).mode(),
                    "the bytes read normalizes in BYTES_READ mode");
                checkEq(FailurePolicyId.NO_DEAL_FAILURE, normalize.failurePolicy(),
                    "the bytes normalize stays pure (NO_DEAL_FAILURE)");
                ValueId lengthValue = ((KindPayload.IndexNormalizePayload)
                    normalize.payload()).currentLength();
                SemanticOp length = opById(unit, producerOf(unit, lengthValue));
                check(length != null && length.kind() == SemanticOpKind.ARRAY_LENGTH,
                    "the read's currentLength operand references its own length read");
                check(length != null
                        && length.operands().isEmpty()
                        && length.resultType() == RuntimeDescriptor.Int.INSTANCE,
                    "the length read is an int ARRAY_LENGTH with the INT32_RESULT "
                        + "terminal");
                SemanticOp boundary = opById(unit, payload.elementBoundaryOpId());
                check(boundary != null
                        && boundary.kind() == SemanticOpKind.BOUNDARY
                        && ((KindPayload.BoundaryPayload) boundary.payload()).kind()
                            == BoundaryKind.BYTE_ELEMENT_READ
                        && boundary.failurePolicy() == FailurePolicyId.BYTES_READ,
                    "the read's boundary child is BYTE_ELEMENT_READ under BYTES_READ");
            }
            check(checkedReads >= 2, "the fixture's bytes reads are classified by the "
                + "bytes descriptor (got " + checkedReads + ")");

            List<SemanticOp> chains = new ArrayList<>();
            for (SemanticOp op : opsOfKind(unit, SemanticOpKind.ASSIGN)) {
                if (((KindPayload.AssignPayload) op.payload()).targetKind()
                        == deal.semantic.ir.AssignTargetKind.BYTES_SLOT) {
                    chains.add(op);
                }
            }
            checkEq(1, chains.size(), "the fixture produces one BYTES_SLOT chain");
            if (chains.size() == 1) {
                SemanticOp chain = chains.get(0);
                List<OpId> children =
                    ((KindPayload.AssignPayload) chain.payload()).childOps();
                checkEq(7, children.size(), "the bytes write chain is the seven-child "
                    + "shape");
                if (children.size() == 7) {
                    SemanticOp[] c = new SemanticOp[7];
                    for (int i = 0; i < 7; i++) {
                        c[i] = opById(unit, children.get(i));
                        check(c[i] != null, "the chain child at position " + i
                            + " resolves");
                    }
                    check(c[3] != null && c[3].kind() == SemanticOpKind.ARRAY_LENGTH,
                        "the length child sits at position 3");
                    check(c[3] != null
                            && c[3].resultType() == RuntimeDescriptor.Int.INSTANCE,
                        "the length child is an int read");
                    check(c[4] != null
                            && c[4].kind() == SemanticOpKind.INDEX_NORMALIZE,
                        "the normalize child sits at position 4");
                    if (c[4] != null && c[3] != null) {
                        KindPayload.IndexNormalizePayload normalize =
                            (KindPayload.IndexNormalizePayload) c[4].payload();
                        checkEq(IndexMode.BYTES_WRITE, normalize.mode(),
                            "the chain normalizes in BYTES_WRITE mode");
                        checkEq(c[3].result(), normalize.currentLength(),
                            "the normalize's currentLength references the length child's "
                                + "result");
                        checkEq(c[1].result(), normalize.rawKey(),
                            "the normalize's rawKey references the key child's result");
                    }
                    check(c[5] != null && c[5].kind() == SemanticOpKind.BOUNDARY,
                        "the boundary child sits at position 5");
                    if (c[5] != null) {
                        KindPayload.BoundaryPayload boundary =
                            (KindPayload.BoundaryPayload) c[5].payload();
                        checkEq(BoundaryKind.BYTE_ELEMENT_ASSIGNMENT, boundary.kind(),
                            "the write cell is BYTE_ELEMENT_ASSIGNMENT");
                        checkEq(FailurePolicyId.BYTES_WRITE, c[5].failurePolicy(),
                            "the write cell carries the BYTES_WRITE policy");
                        checkEq(c[2].result(), boundary.input(),
                            "the write cell's input is the checked RHS value");
                    }
                    check(c[6] != null && c[6].kind() == SemanticOpKind.INDEX_WRITE,
                        "the commit child sits at position 6");
                }
            }

            // The read at i == b.length fails the pinned E8012 on all three
            // consumers.
            SemanticDifferentialHarness.Expectation expectation =
                SemanticDifferentialHarness.Expectation.failure("the shape drive",
                    List.of(), "E8012", null);
            SemanticDifferentialHarness.Verdict verdict =
                SemanticDifferentialHarness.runProject(result.project(), result.tables(),
                    result.registries(), expectation,
                    Files.createTempDirectory("bytes-shape-matrix-"));
            check(verdict.pass(), "the read at i == b.length fails E8012 on all three "
                + "consumers: " + verdict.failures());
        } finally {
            deleteRecursively(root);
        }
    }

    // =========================================================================
    // 4b. The bytes element cells: {index, length} and the pinned rows
    // =========================================================================

    /**
     * The two bytes element cells receive exactly {@code {index, length}}
     * (K6 item 3): the read at {@code i == b.length} and below zero
     * projects the pinned E8012 row, the in-bounds read passes, the write
     * cell runs the same bounds row first, the write's value-range
     * projection is the pinned E8013 second template, and a cell executed
     * without its context is a producer defect, never a DEAL projection.
     */
    private static void testBytesBoundaryContext() {
        System.out.println("-- the bytes element cells receive {index, length} --");
        BoundaryOutcome atEnd = BoundaryExecutor.check(FailurePolicyId.BYTES_READ,
            RuntimeDescriptor.Int.INSTANCE, BoundaryValueView.ofInt(0),
            BoundaryContext.bytesBounds(2, 2));
        check(atEnd instanceof BoundaryOutcome.Fail endFail
                && endFail.failure().code() == DiagnosticCode.E8012
                && "bytes index out of bounds".equals(endFail.failure().message()),
            "the BYTE_ELEMENT_READ cell at i == b.length projects the pinned E8012 "
                + "row: " + atEnd);
        BoundaryOutcome belowZero = BoundaryExecutor.check(FailurePolicyId.BYTES_READ,
            RuntimeDescriptor.Int.INSTANCE, BoundaryValueView.ofInt(0),
            BoundaryContext.bytesBounds(-1, 2));
        check(belowZero instanceof BoundaryOutcome.Fail belowFail
                && belowFail.failure().code() == DiagnosticCode.E8012
                && "bytes index out of bounds".equals(belowFail.failure().message()),
            "the BYTE_ELEMENT_READ cell below zero projects the pinned E8012 row: "
                + belowZero);
        BoundaryValueView byteView = BoundaryValueView.ofInt(7);
        BoundaryOutcome inBounds = BoundaryExecutor.check(FailurePolicyId.BYTES_READ,
            RuntimeDescriptor.Int.INSTANCE, byteView, BoundaryContext.bytesBounds(1, 2));
        check(inBounds instanceof BoundaryOutcome.Pass pass
                && byteView.equals(pass.value()),
            "the in-bounds BYTE_ELEMENT_READ cell passes with the byte unchanged: "
                + inBounds);
        BoundaryOutcome writeAtEnd = BoundaryExecutor.check(FailurePolicyId.BYTES_WRITE,
            RuntimeDescriptor.Int.INSTANCE, BoundaryValueView.ofInt(3),
            BoundaryContext.bytesBounds(2, 2));
        check(writeAtEnd instanceof BoundaryOutcome.Fail writeFail
                && writeFail.failure().code() == DiagnosticCode.E8012
                && "bytes index out of bounds".equals(writeFail.failure().message()),
            "the BYTE_ELEMENT_ASSIGNMENT cell at i == b.length runs the pinned E8012 "
                + "bounds row first: " + writeAtEnd);
        BoundaryFailure range = BoundaryExecutor.bytesWriteRangeFailure();
        check(range.code() == DiagnosticCode.E8013
                && "bytes value out of range".equals(range.message()),
            "the write's value-range projection is the pinned E8013 template: "
                + range.message());
        boolean readContextless = false;
        try {
            BoundaryExecutor.check(FailurePolicyId.BYTES_READ,
                RuntimeDescriptor.Int.INSTANCE, BoundaryValueView.ofInt(0),
                BoundaryContext.none());
        } catch (BoundaryExecutor.Defect expected) {
            readContextless = expected.getMessage() != null
                && expected.getMessage().contains("index");
        }
        check(readContextless, "a BYTES_READ cell without its {index, length} "
            + "context is a producer defect");
        boolean writeContextless = false;
        try {
            BoundaryExecutor.check(FailurePolicyId.BYTES_WRITE,
                RuntimeDescriptor.Int.INSTANCE, BoundaryValueView.ofInt(0),
                BoundaryContext.none());
        } catch (BoundaryExecutor.Defect expected) {
            writeContextless = expected.getMessage() != null
                && expected.getMessage().contains("index");
        }
        check(writeContextless, "a BYTE_ELEMENT_ASSIGNMENT cell without its "
            + "{index, length} context is a producer defect");
    }

    // =========================================================================
    // 4c. The decoded-array mark: the real decode producers, the exact
    //     tuples, and projection-only inertness (ISSUE-0710)
    // =========================================================================

    /**
     * One pinned failure projection: code, message, and the token pair.
     * The comparison reports the first divergent field with both values.
     */
    private record Tuple(String code, String message, String expected, String actual) {

        static Tuple of(SemanticRuntimeModel.ErrorSnapshot error) {
            return new Tuple(error.code(), error.message(),
                error.expected() == null ? "" : error.expected(),
                error.actual() == null ? "" : error.actual());
        }

        /** The first divergent field with captured and pinned values, or null. */
        String firstDivergence(Tuple pinned) {
            if (!code.equals(pinned.code())) {
                return "code: captured " + code + ", pinned " + pinned.code();
            }
            if (!message.equals(pinned.message())) {
                return "message: captured " + message + ", pinned " + pinned.message();
            }
            if (!expected.equals(pinned.expected())) {
                return "expected: captured " + expected + ", pinned "
                    + pinned.expected();
            }
            if (!actual.equals(pinned.actual())) {
                return "actual: captured " + actual + ", pinned " + pinned.actual();
            }
            return null;
        }
    }

    /** The scratch unrealized-construct seed (a function-typed materialization). */
    private static final String GUARD_SEED = """
        export function main(): null {
          let f: (a: int) => int = one
          let x: int = f(1)
          return null
        }

        function one(x: int): int {
          return x
        }
        """;

    /**
     * The marked case (B3): a real {@code std/json} decode whose nested
     * arrays carry the mark. The failing element of the outer decoded array
     * renders the reference kind {@code table}.
     */
    private static final String DECODED_NESTED_SOURCE = """
        import * as json from "std/json"

        export function main(): null {
          let t: table = json.parse("{\\"values\\":[[\\"x\\"],[\\"y\\"]]}")
          let xs: bytes[][] = t.values
          return null
        }
        """;

    /**
     * The unmarked control: a DEAL nested array (an array literal) keeps the
     * landed refined token {@code array} at the same projection.
     */
    private static final String UNMARKED_NESTED_SOURCE = """
        export function main(): null {
          let inner: int[] = [1]
          let holder: table = { values: [inner] }
          let xs: bytes[][] = holder.values
          return null
        }
        """;

    /**
     * The host-returned control: the array returned through the host ABI
     * ({@code host/array_return}'s {@code string[]}) is a real host
     * conversion and keeps the unmarked {@code array} token.
     */
    private static final String HOST_RETURNED_NESTED_SOURCE = """
        import * as host from "host/array_return"

        export function main(): null {
          let inner: string[] = host.split("ignored")
          let holder: table = { values: [inner] }
          let xs: bytes[][] = holder.values
          return null
        }
        """;

    /**
     * The {@code STRING_SPLIT} control: the array {@code std/string.split}
     * returns is not a decode result, so its nested element keeps the landed
     * unmarked {@code array} token (B3: the {@code STRING_SPLIT} result is
     * unmarked).
     */
    private static final String SPLIT_NESTED_SOURCE = """
        import * as strings from "std/string"

        export function main(): null {
          let parts: string[] = strings.split("a,b", ",")
          let holder: table = { values: [parts] }
          let xs: bytes[][] = holder.values
          return null
        }
        """;

    /**
     * The {@code TABLE_KEYS} control: the array {@code std/table.keys}
     * returns is not a decode result, so its nested element keeps the landed
     * unmarked {@code array} token (B3: the {@code TABLE_KEYS} result is
     * unmarked).
     */
    private static final String KEYS_NESTED_SOURCE = """
        import * as tables from "std/table"

        export function main(): null {
          let t: table = { a: 1 }
          let ks: string[] = tables.keys(t)
          let holder: table = { values: [ks] }
          let xs: bytes[][] = holder.values
          return null
        }
        """;

    /**
     * The decode tree's second nested level: a {@code bytes[][][]} crossing
     * over a parsed {@code [[["x"]]]} reports the same marked element one
     * wrap deeper, so the decode site marks every array of the tree
     * recursively.
     */
    private static final String DECODED_DEEP_NESTED_SOURCE = """
        import * as json from "std/json"

        export function main(): null {
          let t: table = json.parse("{\\"values\\":[[[\\"x\\"]]]}")
          let xs: bytes[][][] = t.values
          return null
        }
        """;

    /**
     * Projection-only inertness: the decoded tree's identity, length,
     * indexing, alias-observed mutation, and stringify encoding are
     * unchanged by the mark.
     */
    private static final String DECODED_INERTNESS_SOURCE = """
        import * as json from "std/json"

        export function main(): null {
          let t: table = json.parse("{\\"values\\":[[1,2],[3,4]]}")
          let xs: int[][] = t.values
          let first: int[] = xs[0]
          let alias: int[] = xs[0]
          if (first !== alias) {
            throw { code: "TEST_FAIL", message: "decoded identity" }
          }
          let reread: int[][] = t.values
          if (reread !== xs) {
            throw { code: "TEST_FAIL", message: "decoded member identity" }
          }
          if (first.length !== 2) {
            throw { code: "TEST_FAIL", message: "decoded length" }
          }
          if (xs[1][0] !== 3) {
            throw { code: "TEST_FAIL", message: "decoded indexing" }
          }
          first[1] = 9
          if (alias[1] !== 9) {
            throw { code: "TEST_FAIL", message: "decoded alias mutation" }
          }
          let encoded: string = json.stringify(t)
          if (encoded !== "{\\"values\\":[[1,9],[3,4]]}") {
            throw { code: "TEST_FAIL", message: "decoded stringify: " + encoded }
          }
          return null
        }
        """;

    /**
     * Admission inertness: a decoded array stays rejected at a {@code table}
     * descriptor with the landed {@code array} actual token.
     */
    private static final String DECODED_ADMISSION_SOURCE = """
        import * as json from "std/json"
        import * as tables from "std/table"

        export function main(): null {
          let t: table = json.parse("{\\"values\\":[[1,2]]}")
          let ks: string[] = tables.keys(t.values)
          return null
        }
        """;

    /**
     * The decoded-array mark end to end (B3): the closed decode producers
     * are exercised through the real production pipeline, the marked nested
     * element renders {@code table}, the unmarked and host-returned nested
     * elements keep the landed {@code array} token, the decoded tree stays
     * inert outside the array-element projection, and the comparison fails
     * by field on any divergence.
     */
    private static void testDecodedArrayMark() throws Exception {
        System.out.println("-- the decoded-array mark: real decode producers, exact "
            + "tuples, and projection-only inertness (ISSUE-0710) --");

        String markedChunk = driveFocusedFailure("bytes decoded nested element (marked)",
            DECODED_NESTED_SOURCE, null, null, 5, 11,
            new Tuple("E8003", "array element 1 type mismatch", "[bytes]", "table"));

        driveFocusedFailure("bytes unmarked nested element", UNMARKED_NESTED_SOURCE,
            null, null, 4, 11,
            new Tuple("E8003", "array element 1 type mismatch", "[bytes]", "array"));

        driveFocusedFailure("bytes host-returned nested element",
            HOST_RETURNED_NESTED_SOURCE, "array_return", "host/array_return", 6, 11,
            new Tuple("E8003", "array element 1 type mismatch", "[bytes]", "array"));

        // The remaining non-decode array producers (B3 names both): the
        // STRING_SPLIT and TABLE_KEYS results stay unmarked, so the same
        // element projection keeps the landed refined token.
        driveFocusedFailure("bytes split-result nested element",
            SPLIT_NESTED_SOURCE, null, null, 6, 11,
            new Tuple("E8003", "array element 1 type mismatch", "[bytes]", "array"));

        driveFocusedFailure("bytes keys-result nested element",
            KEYS_NESTED_SOURCE, null, null, 7, 11,
            new Tuple("E8003", "array element 1 type mismatch", "[bytes]", "array"));

        driveFocusedFailure("bytes decoded element two levels down (marked)",
            DECODED_DEEP_NESTED_SOURCE, null, null, 5, 11,
            new Tuple("E8003", "array element 1 type mismatch", "[[bytes]]", "table"));

        checkEmittedMarkSurface(markedChunk);

        driveFocusedFailure("decoded array at a table descriptor",
            DECODED_ADMISSION_SOURCE, null, null, 6, 34,
            new Tuple("E8001", "expected table", "table", "array"));

        // Comparison honesty: a pin-exact tuple reports no divergence and a
        // divergent field fails by name with the captured and pinned values.
        Tuple pinned = new Tuple("E8003", "array element 1 type mismatch",
            "[bytes]", "table");
        checkEq(null, pinned.firstDivergence(pinned),
            "a pin-exact tuple reports no divergent field");
        checkEq("actual: captured array, pinned table",
            new Tuple("E8003", "array element 1 type mismatch", "[bytes]", "array")
                .firstDivergence(pinned),
            "the pre-correction actual token fails by field");
        checkEq("code: captured E8001, pinned E8003",
            new Tuple("E8001", "array element 1 type mismatch", "[bytes]", "table")
                .firstDivergence(pinned),
            "a divergent code fails by field");

        checkDeterministicEmission();
        checkAtomicStaging();
    }

    /**
     * Drives one focused failing program through the real production
     * pipeline: the oracle (with the optional host responder), the LuaJIT
     * production artifact, and the JVM production artifact, and asserts the
     * exact pinned tuple on every consumer.
     */
    private static String driveFocusedFailure(String label, String source,
            String hostStem, String hostSpecifier, int line, int column, Tuple pinned)
            throws Exception {
        Path root = Files.createTempDirectory("bytes-mark-");
        try {
            BytesFixture spec = new BytesFixture(BYTES_DIR, label, "main", "null",
                List.of(), pinned.code(), pinned.message(), line, column);
            Compiled compiled = compileFocused(root, source, hostStem, hostSpecifier);
            if (compiled == null) {
                return null;
            }
            Drive drive = lowerFocused(compiled, spec);
            if (drive == null) {
                return null;
            }
            int[] calls = { 0 };
            SemanticOracle.HostResponder responder = hostStem == null ? null
                : focusedArrayHostResponder(calls);
            SemanticRuntimeModel.ConsumerRun oracle =
                SemanticOracle.executeProjectInits(drive.project(), drive.tables(),
                    drive.registries(), responder);
            if (hostStem != null) {
                checkEq(1, calls[0], label + ": the host array return is called once");
            }
            String origin = root.resolve("src").resolve("main.deal").toAbsolutePath()
                + ":" + line + ":" + column;
            check(oracle.terminal()
                    instanceof SemanticRuntimeModel.Terminal.DealFailure,
                label + ": the oracle projects the pinned failure: "
                    + oracle.terminal());
            if (oracle.terminal()
                    instanceof SemanticRuntimeModel.Terminal.DealFailure failure) {
                checkEq(null, Tuple.of(failure.error()).firstDivergence(pinned),
                    label + ": the oracle tuple is pin-exact");
                checkEq(origin, failure.error().origin(),
                    label + ": the oracle origin is the pinned expression");
            }
            Path hostLua = hostStem == null ? null
                : Path.of("test", "conformance", "host-fixtures", hostStem + ".lua");
            Path hostJava = hostStem == null ? null
                : Path.of("test", "conformance", "host-fixtures", hostStem + ".java");
            String probeTranscript = "ERR:" + pinned.code() + "|" + pinned.message()
                + "|" + origin + "|" + pinned.expected() + "|" + pinned.actual()
                + "\n";
            String terminalTranscript = "DEAL_ERROR_CODE: " + pinned.code() + "\n";
            String pinnedOutcome = probeTranscript.strip();
            String chunk = LuaSemanticEmitter.emitProductionProject(drive.project(),
                drive.tables(), drive.registries(), drive.compiled().surface());
            ArtifactRun lua = luaProduction(drive, hostSpecifier, hostLua, chunk);
            assertTranscript(label, "luajit probe", lua.probe(), 0, probeTranscript);
            assertTranscript(label, "luajit terminal", lua.terminal(), 1,
                terminalTranscript);
            checkEq(pinnedOutcome, lua.outcome(), label
                + ": the LuaJIT production artifact reproduces the exact tuple");
            ArtifactRun jvm = jvmProduction(drive, hostSpecifier, hostJava);
            assertTranscript(label, "jvm probe", jvm.probe(), 0, probeTranscript);
            assertTranscript(label, "jvm terminal", jvm.terminal(), 1,
                terminalTranscript);
            checkEq(pinnedOutcome, jvm.outcome(), label
                + ": the JVM production artifact reproduces the exact tuple");
            return chunk;
        } finally {
            deleteRecursively(root);
        }
    }

    /**
     * Drives one focused runtime-ok program through the real production
     * pipeline: the oracle and both production artifacts reach the pinned
     * success with no terminal.
     */
    private static void driveFocusedSuccess(String label, String source)
            throws Exception {
        Path root = Files.createTempDirectory("bytes-inert-");
        try {
            Compiled compiled = compileFocused(root, source, null, null);
            if (compiled != null) {
                driveFocusedSuccess(label, compiled);
            }
        } finally {
            deleteRecursively(root);
        }
    }

    private static void driveFocusedSuccess(String label, Compiled compiled)
            throws Exception {
        BytesFixture spec = new BytesFixture(BYTES_DIR, label, "main", "null",
            List.of(), null, null, 0, 0);
        Drive drive = lowerFocused(compiled, spec);
        if (drive == null) {
            return;
        }
        SemanticRuntimeModel.ConsumerRun oracle =
            SemanticOracle.executeProjectInits(drive.project(), drive.tables(),
                drive.registries(), null);
        check(oracle.terminal() instanceof SemanticRuntimeModel.Terminal.Success,
            label + ": the oracle runs the decoded tree to success: "
                + oracle.terminal());
        ArtifactRun lua = luaProduction(drive);
        assertTranscript(label, "luajit probe", lua.probe(), 0, "OK\n");
        check(lua.terminal() == null, label
            + ": a runtime-ok drive runs no direct terminal artifact");
        checkEq("OK", lua.outcome(), label
            + ": the LuaJIT production artifact runs to success");
        ArtifactRun jvm = jvmProduction(drive);
        assertTranscript(label, "jvm probe", jvm.probe(), 0, "OK\n");
        check(jvm.terminal() == null, label
            + ": a runtime-ok drive runs no direct terminal artifact");
        checkEq("OK", jvm.outcome(), label
            + ": the JVM production artifact runs to success");
    }

    /**
     * One differential matrix case of the indirect bytes drive: its
     * source, whether the drive is expected to fail, and the pinned
     * call-expression coordinate of a failure case.
     */
    private record DifferentialCase(String label, String source, boolean failure,
                                    int line, int column) {
    }

    // =========================================================================
    // 5c. The indirect bytes allocation: the seeded bytes intrinsic value
    // =========================================================================

    /**
     * The indirect allocation drive: zero fill, distinct allocation
     * identities, and an alias observing an in-place write — through the
     * seeded {@code (int) -> bytes} function value.
     */
    private static final String INDIRECT_BYTES_SOURCE = """
        export function main(): null {
          let f: (x: int) => bytes = bytes;
          let b: bytes = f(2);
          let c: bytes = f(3);
          if (b.length !== 2 || c.length !== 3) {
            throw { code: "TEST_FAIL", message: "indirect length" }
          }
          if (b[0] !== 0 || b[1] !== 0 || c[2] !== 0) {
            throw { code: "TEST_FAIL", message: "indirect zero fill" }
          }
          if (b === c) {
            throw { code: "TEST_FAIL", message: "indirect allocation identity" }
          }
          let alias: bytes = b;
          b[0] = 7;
          if (alias[0] !== 7 || b[0] !== 7) {
            throw { code: "TEST_FAIL", message: "alias observes the write" }
          }
          return null
        }
        """;

    /**
     * The remaining first-class value positions: the arity-extension
     * adapter, a function-typed parameter (callback), and an array
     * element call.
     */
    private static final String INDIRECT_BYTES_VALUE_POSITIONS_SOURCE = """
        function call(f: (x: int) => bytes): bytes {
          return f(2)
        }

        export function main(): null {
          let g: (x: int, y: int) => bytes = bytes;
          let adapted: bytes = g(2, 3);
          if (adapted.length !== 2) {
            throw { code: "TEST_FAIL", message: "adapter length" }
          }
          let viaCallback: bytes = call(bytes);
          if (viaCallback.length !== 2) {
            throw { code: "TEST_FAIL", message: "callback length" }
          }
          let xs: ((x: int) => bytes)[] = [bytes];
          let viaElement: bytes = xs[0](3);
          if (viaElement.length !== 3) {
            throw { code: "TEST_FAIL", message: "element length" }
          }
          return null
        }
        """;

    /** The negative length at the direct indirect call expression. */
    private static final String INDIRECT_BYTES_NEGATIVE_SOURCE = """
        export function main(): null {
          let f: (x: int) => bytes = bytes;
          let neg: bytes = f(-1);
          return null
        }
        """;

    /** The negative length through the arity-extension adapter. */
    private static final String INDIRECT_BYTES_ADAPTER_NEGATIVE_SOURCE = """
        export function main(): null {
          let g: (x: int, y: int) => bytes = bytes;
          let neg: bytes = g(-1, 0);
          return null
        }
        """;

    /**
     * The indirect bytes allocation drive (K14/R2): the structural seed
     * and call cells, the zero-fill/identity/alias behaviors on the
     * oracle and both production artifacts, the pinned E8012 tuple at
     * the call expression, and the three-consumer differential matrix.
     */
    private static void testIndirectBytesAllocation() throws Exception {
        System.out.println("-- the indirect bytes allocation: the seeded bytes "
            + "intrinsic value (K14/R2) --");
        checkIndirectBytesStructure();
        driveFocusedSuccess("indirect bytes allocation", INDIRECT_BYTES_SOURCE);
        driveFocusedSuccess("bytes intrinsic value positions",
            INDIRECT_BYTES_VALUE_POSITIONS_SOURCE);
        driveFocusedFailure("indirect bytes negative length",
            INDIRECT_BYTES_NEGATIVE_SOURCE, null, null, 3, 20,
            new Tuple("E8012", "bytes length must be non-negative", "", ""));
        driveFocusedFailure("bytes adapter negative length",
            INDIRECT_BYTES_ADAPTER_NEGATIVE_SOURCE, null, null, 3, 20,
            new Tuple("E8012", "bytes length must be non-negative", "", ""));
        checkIndirectBytesDifferential();
    }

    /**
     * The lowered structure of one indirect bytes allocation: the seeded
     * {@code BYTES_NEW} registration with its pinned declared signature,
     * the identity-preserving load publishing the seeded identity, the
     * indirect call's recorded {@code IntrinsicFunction} binding and host
     * cell family, and the call-expression origin.
     */
    private static void checkIndirectBytesStructure() throws Exception {
        Path root = Files.createTempDirectory("bytes-intrinsic-");
        try {
            BytesFixture spec = new BytesFixture(BYTES_DIR, "indirect bytes", "main",
                "null", List.of(), null, null, 0, 0);
            Compiled compiled = compileFocused(root, INDIRECT_BYTES_SOURCE, null, null);
            if (compiled == null) {
                return;
            }
            Drive drive = lowerFocused(compiled, spec);
            if (drive == null) {
                return;
            }
            LoweredModuleUnit unit = drive.unit();
            FunctionAllocationIdentity seed = null;
            for (Map.Entry<FunctionAllocationIdentity, FunctionExecutionBinding> entry
                    : unit.functionBindings().entrySet()) {
                if (entry.getValue()
                        instanceof FunctionExecutionBinding.IntrinsicFunction intrinsic
                        && intrinsic.kind() == IntrinsicKind.BYTES_NEW) {
                    seed = entry.getKey();
                    checkEq(IntrinsicKind.BYTES_NEW.declaredSignature(),
                        intrinsic.descriptor(),
                        "the bytes seed registration carries its pinned "
                            + "(int)->bytes declared signature");
                }
            }
            check(seed != null, "the unit carries the BYTES_NEW intrinsic seed "
                + "registration");
            if (seed == null) {
                return;
            }
            int published = 0;
            boolean allLoads = true;
            for (SemanticOp op : unit.ops()) {
                if (op.result() instanceof ValueId result && result.id() == seed.id()) {
                    published++;
                    allLoads = allLoads && op.kind() == SemanticOpKind.BINDING_LOAD;
                }
            }
            check(published >= 1 && allLoads, "the seeded bytes identity is published "
                + "only by identity-preserving binding loads; got " + published
                + " publishing op(s)");
            SemanticOp indirect = null;
            int intrinsicCalls = 0;
            for (SemanticOp op : unit.ops()) {
                if (op.kind() != SemanticOpKind.CALL
                        || !(op.payload() instanceof KindPayload.CallPayload call)) {
                    continue;
                }
                if (call.callee() instanceof KindPayload.CallCallee.Static staticCallee
                        && staticCallee.binding()
                            instanceof FunctionExecutionBinding.IntrinsicFunction intrinsic
                        && intrinsic.kind() == IntrinsicKind.BYTES_NEW) {
                    intrinsicCalls++;
                    if (indirect == null) {
                        indirect = op;
                    }
                }
            }
            checkEq(2, intrinsicCalls, "both indirect calls record the seeded "
                + "IntrinsicFunction binding");
            check(indirect != null, "the indirect call of the bytes intrinsic records "
                + "the seeded IntrinsicFunction binding");
            if (indirect == null) {
                return;
            }
            KindPayload.CallPayload payload =
                (KindPayload.CallPayload) indirect.payload();
            checkEq(CallMode.INDIRECT, payload.mode(), "the intrinsic-value call is "
                + "the INDIRECT mode with the statically resolved binding");
            SemanticOp parameter = payload.parameterBoundaryOpIds().isEmpty() ? null
                : opById(unit, payload.parameterBoundaryOpIds().get(0));
            check(parameter != null
                    && ((KindPayload.BoundaryPayload) parameter.payload()).kind()
                        == BoundaryKind.DEAL_TO_HOST
                    && parameter.failurePolicy() == FailurePolicyId.HOST_PARAMETER
                    && RuntimeDescriptor.Int.INSTANCE.equals(
                        ((KindPayload.BoundaryPayload) parameter.payload())
                            .descriptor()),
                "the declared int parameter is the DEAL_TO_HOST + HOST_PARAMETER "
                    + "cell");
            SemanticOp returned = payload.returnBoundaryOpId() == null ? null
                : opById(unit, payload.returnBoundaryOpId());
            check(returned != null
                    && ((KindPayload.BoundaryPayload) returned.payload()).kind()
                        == BoundaryKind.HOST_TO_DEAL
                    && returned.failurePolicy() == FailurePolicyId.HOST_SYNC_RETURN
                    && RuntimeDescriptor.Bytes.INSTANCE.equals(
                        ((KindPayload.BoundaryPayload) returned.payload())
                            .descriptor()),
                "the bytes return is the HOST_TO_DEAL + HOST_SYNC_RETURN cell");
            checkEq(3, indirect.origin().span().startLine(),
                "the indirect call's origin is the call expression's line");
            checkEq(18, indirect.origin().span().startColumn(),
                "the indirect call's origin is the call expression's column");
        } finally {
            deleteRecursively(root);
        }
    }

    /**
     * The three-consumer differential matrix of the indirect bytes drive:
     * each case runs the oracle and both production artifacts and must
     * agree event-for-event (a success terminal, or the pinned E8012 at
     * the call expression).
     */
    private static void checkIndirectBytesDifferential() throws Exception {
        List<DifferentialCase> cases = List.of(
            new DifferentialCase("the indirect bytes allocation",
                INDIRECT_BYTES_SOURCE, false, 0, 0),
            new DifferentialCase("the indirect bytes failure",
                INDIRECT_BYTES_NEGATIVE_SOURCE, true, 3, 20),
            new DifferentialCase("the bytes intrinsic value positions",
                INDIRECT_BYTES_VALUE_POSITIONS_SOURCE, false, 0, 0),
            new DifferentialCase("the bytes adapter failure",
                INDIRECT_BYTES_ADAPTER_NEGATIVE_SOURCE, true, 3, 20));
        for (DifferentialCase differential : cases) {
            Path root = Files.createTempDirectory("bytes-intrinsic-matrix-");
            try {
                BytesFixture spec = new BytesFixture(BYTES_DIR, differential.label(),
                    "main", "null", List.of(), null, null, 0, 0);
                Compiled compiled =
                    compileFocused(root, differential.source(), null, null);
                if (compiled == null) {
                    continue;
                }
                Drive drive = lowerFocused(compiled, spec);
                if (drive == null) {
                    continue;
                }
                SemanticDifferentialHarness.Expectation expectation =
                    differential.failure()
                        ? SemanticDifferentialHarness.Expectation.failure(
                            differential.label(), List.of(), "E8012",
                            root.resolve("src").resolve("main.deal").toAbsolutePath()
                                + ":" + differential.line() + ":"
                                + differential.column())
                        : SemanticDifferentialHarness.Expectation.success(
                            differential.label(), List.of(), "null");
                Path artifacts =
                    Files.createTempDirectory("bytes-intrinsic-artifacts-");
                try {
                    SemanticDifferentialHarness.Verdict verdict =
                        SemanticDifferentialHarness.runProject(drive.project(),
                            drive.tables(), drive.registries(), expectation, artifacts);
                    checkEq(3, verdict.runs().size(), spec.what() + ": the drive "
                        + "produced the three consumers: " + verdict.failures());
                    check(verdict.pass(), spec.what() + ": the three-consumer "
                        + "differential verdict passes: " + verdict.failures());
                } finally {
                    deleteRecursively(artifacts);
                }
            } finally {
                deleteRecursively(root);
            }
        }
    }

    /**
     * The mark's emission surface (B3, per consumer): the emitted LuaJIT
     * prelude carries exactly one mark writer — the stdlib JSON decode
     * realization — and exactly one reader — the array-element token helper,
     * whose definition and single call site are its only other occurrences —
     * so no other emitted surface sets or consults the mark.
     */
    private static void checkEmittedMarkSurface(String chunk) {
        System.out.println("-- the decoded-array mark's emission surface: one writer "
            + "and one reader --");
        if (chunk == null) {
            return;
        }
        checkEq(1, countOf(chunk, "__da = true"),
            "the emitted chunk carries exactly one decoded-array mark writer (the "
                + "stdlib JSON decode realization)");
        checkEq(1, countOf(chunk, "v.__da"),
            "the emitted chunk reads the decoded-array mark at exactly one site (the "
                + "array-element token helper)");
        checkEq(2, countOf(chunk, "__elemRefKind("),
            "the emitted chunk defines and calls the array-element token helper once "
                + "each");
    }

    /** The occurrence count of one literal in a text. */
    private static int countOf(String text, String needle) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    /**
     * The oracle-side host of the focused host-returned-array control:
     * {@code host/array_return}'s {@code split} returns a real (unmarked)
     * string array, the same shape the deployed Lua host module and JVM host
     * class return.
     */
    private static SemanticOracle.HostResponder focusedArrayHostResponder(int[] calls) {
        return new SemanticOracle.HostResponder() {
            @Override
            public SyncOutcome call(ModuleId module, String export,
                    RuntimeDescriptor.Func descriptor,
                    List<SemanticOracle.Value> args) {
                if (!"host.array_return".equals(module.path())
                        || !"split".equals(export)) {
                    return new SyncOutcome.Thrown("E9001", "unknown host call "
                        + module.path() + "#" + export);
                }
                calls[0]++;
                List<SemanticOracle.Value> elements = new ArrayList<>();
                elements.add(new SemanticOracle.Value.StrValue("a"));
                elements.add(new SemanticOracle.Value.StrValue("b"));
                elements.add(new SemanticOracle.Value.StrValue("c"));
                return new SyncOutcome.Returned(new SemanticOracle.Value.ArrayValue(
                    elements, RuntimeDescriptor.String.INSTANCE, false));
            }
        };
    }

    /**
     * Materializes one focused program ({@code src/main.deal} plus the
     * optional host declaration) and compiles it through the release-owned
     * production orchestrator.
     */
    private static Compiled compileFocused(Path root, String source, String hostStem,
            String hostSpecifier) throws Exception {
        Path src = root.resolve("src");
        Files.createDirectories(src);
        Files.writeString(src.resolve("main.deal"), source, StandardCharsets.UTF_8);
        Map<String, String> externals = null;
        if (hostStem != null) {
            Path declaration = root.resolve("host").resolve(hostStem + ".d.deal");
            Files.createDirectories(declaration.getParent());
            Files.writeString(declaration,
                ConformanceHarnessMetadata.stripClassificationHeaders(
                    Files.readString(Path.of("test", "conformance", "host-fixtures",
                        hostStem + ".d.deal"), StandardCharsets.UTF_8)),
                StandardCharsets.UTF_8);
            externals = new LinkedHashMap<>();
            externals.put(hostSpecifier, declaration.toAbsolutePath().toString());
            externals.put(hostSpecifier.replace('.', '/'),
                declaration.toAbsolutePath().toString());
        }
        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            src.resolve("main.deal").toAbsolutePath(), root.resolve("out"), false,
            false, false, false, Backend.LUAJIT, externals,
            List.of(src.toAbsolutePath()), Path.of("std").toAbsolutePath().normalize(),
            null, productionInvocation());
        boolean compiled = orchestrator.compile();
        check(compiled, "the focused program compiles through the release-owned "
            + "invocation: " + orchestrator.diagnostics());
        CheckedProjectBuildResult built = orchestrator.checkedProject();
        RequirementManifestResult manifests = orchestrator.requirementManifests();
        check(built != null && built.input() != null && built.index() != null
                && !built.hasErrors(),
            "the focused program's checked project builds: "
                + (built == null ? "null" : built.diagnostics()));
        if (!compiled || built == null || built.input() == null || built.index() == null
                || built.hasErrors() || manifests == null
                || manifests.manifests() == null
                || orchestrator.hostDeclarationSurface() == null) {
            return null;
        }
        Map<ModuleId, CanonicalModuleIdentity> identities = new LinkedHashMap<>();
        for (ModuleId declaration : orchestrator.hostDeclarationSurface().moduleIds()) {
            identities.put(declaration,
                new CanonicalModuleIdentity.ExternalModule(hostStem == null
                    ? declaration.path() : declaration.path().replace('/', '.')));
        }
        return new Compiled(root, built.input(), built.index(), manifests.manifests(),
            orchestrator.hostDeclarationSurface(), identities, 0);
    }

    /** The production project lowering entry over one focused program. */
    private static Drive lowerFocused(Compiled compiled, BytesFixture spec) {
        SemanticLowerer.ProjectLoweringResult result = SemanticLowerer.lowerProject(
            productionInvocation(), compiled.checkedProject(), compiled.index(),
            compiled.manifests(), compiled.surface(), compiled.identities(), Map.of(),
            BuiltinErrorDeclaration.synthesized(
                compiled.checkedProject().modules().get(0).ast().span()),
            List.of(IntrinsicKind.INT_CONVERT, IntrinsicKind.NUMBER_CONVERT,
                IntrinsicKind.BYTES_NEW), Set.of());
        check(result.project() != null, spec.what() + ": the production project entry "
            + "lowers the focused program with zero diagnostics: "
            + result.diagnostics());
        if (result.project() == null) {
            return null;
        }
        ModuleId fixtureModule = result.project().entryModule();
        LoweredModuleUnit unit = result.project().modules().get(fixtureModule);
        Optional<CompilerDiagnostic> gate = SemanticIrValidator.validate(
            result.project(), new SemanticIrValidator.ComparisonFacts(
                unit.interfaceHash(), SemanticProfile.DEAL_V1_2_INT32,
                ReleaseConfiguration.releaseCapabilityRegistry()
                    .capabilityRegistryHash()));
        check(gate.isEmpty(), spec.what() + ": the closed schema and bindings gates "
            + "accept the produced closure: "
            + gate.map(CompilerDiagnostic::message).orElse("admission"));
        if (gate.isPresent()) {
            return null;
        }
        return new Drive(compiled, result, fixtureModule, unit, spec);
    }

    /**
     * Repeated lowering and emission of one focused program are
     * byte-identical on both targets: two production compiles of the same
     * source publish the same artifact bytes.
     */
    private static void checkDeterministicEmission() throws Exception {
        Path root = Files.createTempDirectory("bytes-determinism-");
        try {
            Path src = root.resolve("src");
            Files.createDirectories(src);
            Files.writeString(src.resolve("main.deal"), DECODED_INERTNESS_SOURCE,
                StandardCharsets.UTF_8);
            Compiled inertness = null;
            for (Backend backend : List.of(Backend.LUAJIT, Backend.JVM)) {
                String artifact = backend == Backend.JVM
                    ? JvmBackend.classNameFor("main") + ".java" : "main.lua";
                String first;
                if (backend == Backend.LUAJIT) {
                    inertness = compileFocused(root, DECODED_INERTNESS_SOURCE,
                        null, null);
                    first = inertness == null ? null : Files.readString(
                        root.resolve("out").resolve(artifact), StandardCharsets.UTF_8);
                } else {
                    first = compileArtifact(root, src, backend, artifact);
                }
                String second = compileArtifact(root, src, backend, artifact);
                check(first != null && second != null, "the " + backend
                    + " determinism drive publishes its artifact");
                checkEq(first, second, "repeated " + backend + " lowering and "
                    + "emission are byte-identical");
            }
            if (inertness != null) {
                driveFocusedSuccess("decoded-array inertness", inertness);
            }
        } finally {
            deleteRecursively(root);
        }
    }

    /** One production compile of the determinism drive; the artifact text. */
    private static String compileArtifact(Path root, Path src, Backend backend,
            String artifact) throws Exception {
        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            src.resolve("main.deal").toAbsolutePath(), root.resolve("out"), false,
            false, false, false, backend, null, List.of(src.toAbsolutePath()),
            Path.of("std").toAbsolutePath().normalize(), null, productionInvocation());
        boolean compiled = orchestrator.compile();
        check(compiled, "the determinism compile (" + backend + ") succeeds: "
            + orchestrator.diagnostics());
        if (!compiled) {
            return null;
        }
        return Files.readString(root.resolve("out").resolve(artifact),
            StandardCharsets.UTF_8);
    }

    /**
     * A fail-closed production compile stages nothing and preserves the
     * previous artifact set byte-identical (the atomic staging contract),
     * on both targets.
     */
    private static void checkAtomicStaging() throws Exception {
        for (Backend backend : List.of(Backend.LUAJIT, Backend.JVM)) {
            Path scratch = Files.createTempDirectory("bytes-atomic-");
            try {
                Path src = scratch.resolve("src");
                Files.createDirectories(src);
                Files.writeString(src.resolve("main.deal"), GUARD_SEED,
                    StandardCharsets.UTF_8);
                Path out = scratch.resolve("out");
                Files.createDirectories(out);
                Files.writeString(out.resolve("main.lua"),
                    "-- the prior artifact set\n", StandardCharsets.UTF_8);
                String before = Files.readString(out.resolve("main.lua"),
                    StandardCharsets.UTF_8);
                CompilationOrchestrator orchestrator = new CompilationOrchestrator(
                    src.resolve("main.deal").toAbsolutePath(), out, false, false,
                    false, false, backend, null, List.of(src.toAbsolutePath()),
                    Path.of("std").toAbsolutePath().normalize(), null,
                    productionInvocation());
                boolean compiled = orchestrator.compile();
                check(!compiled, "the unrealized-construct seed fails closed on "
                    + backend + ": " + orchestrator.diagnostics());
                check(orchestrator.diagnostics().stream().anyMatch(diagnostic ->
                        "E6005".equals(diagnostic.code())
                            && diagnostic.message().contains("CONSTRUCT_UNLOWERED")),
                    "the fail-closed compile names E6005 CONSTRUCT_UNLOWERED on "
                        + backend + ": " + orchestrator.diagnostics());
                check(!Files.exists(out.resolve("deal").resolve("runtime.lua")),
                    "the failing compile stages no deployment copy on " + backend);
                checkEq(before, Files.readString(out.resolve("main.lua"),
                        StandardCharsets.UTF_8),
                    "the failing compile leaves the prior artifact set byte-identical "
                        + "on " + backend);
            } finally {
                deleteRecursively(scratch);
            }
        }
    }

    // =========================================================================
    // 5. The oracle realization
    // =========================================================================

    private static final String ORACLE_SOURCE = """
        export function main(): null {
          let b: bytes = bytes(2);
          let alias: bytes = b;
          alias[0] = 200;
          let fresh: bytes = bytes(2);
          if (b[0] !== 200) {
            throw { code: "TEST_FAIL", message: "alias mutation not observed" }
          }
          if (b[1] !== 0 || fresh[0] !== 0) {
            throw { code: "TEST_FAIL", message: "zero fill mismatch" }
          }
          if (b.length !== 2 || alias.length !== 2) {
            throw { code: "TEST_FAIL", message: "logical length mismatch" }
          }
          if (b !== alias || b === fresh) {
            throw { code: "TEST_FAIL", message: "identity comparison mismatch" }
          }
          return bound(b)
        }

        function bound(value: bytes): null {
          if (value[0] !== 200) {
            throw { code: "TEST_FAIL", message: "boundary crossing lost the value" }
          }
          return null
        }
        """;

    private static void testOracleRealization() throws Exception {
        System.out.println("-- the oracle bytes realization: zero fill, in-place write, "
            + "fixed length, the boundary crossing, and BYTES_EQ/NE identity --");
        Path root = Files.createTempDirectory("bytes-oracle-");
        Path src = root.resolve("src");
        Files.createDirectories(src);
        Files.writeString(src.resolve("main.deal"), ORACLE_SOURCE, StandardCharsets.UTF_8);
        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            src.resolve("main.deal").toAbsolutePath(), root.resolve("out"), false,
            false, false, false, Backend.LUAJIT, null, List.of(src.toAbsolutePath()),
            Path.of("std").toAbsolutePath().normalize(), null, productionInvocation());
        check(orchestrator.compile(), "the oracle drive compiles: "
            + orchestrator.diagnostics());
        CheckedProjectBuildResult built = orchestrator.checkedProject();
        RequirementManifestResult manifests = orchestrator.requirementManifests();
        SemanticLowerer.ProjectLoweringResult result = SemanticLowerer.lowerProject(
            productionInvocation(), built.input(), built.index(), manifests.manifests(),
            orchestrator.hostDeclarationSurface(), Map.of(), Map.of(),
            BuiltinErrorDeclaration.synthesized(
                built.input().modules().get(0).ast().span()),
            List.of(IntrinsicKind.INT_CONVERT, IntrinsicKind.NUMBER_CONVERT,
                IntrinsicKind.BYTES_NEW), Set.of());
        check(result.project() != null, "the oracle drive lowers: "
            + result.diagnostics());
        if (result.project() == null) {
            deleteRecursively(root);
            return;
        }
        try {
            SemanticRuntimeModel.ConsumerRun oracle = SemanticOracle.executeProjectInits(
                result.project(), result.tables(), result.registries(), null);
            check(oracle.terminal() instanceof SemanticRuntimeModel.Terminal.Success,
                "the oracle runs the bytes drive to success: " + oracle.terminal());
            LoweredModuleUnit unit = result.project().modules().get(new ModuleId("main"));
            // The BYTES_EQ/NE rows carry the closed comparison selectors.
            int identityComparisons = 0;
            for (SemanticOp op : unit.ops()) {
                if (op.kind() == SemanticOpKind.BINARY
                        && op.payload() instanceof KindPayload.BinaryPayload binary
                        && (binary.selector() == deal.semantic.ir.BinarySelector.BYTES_EQ
                            || binary.selector()
                                == deal.semantic.ir.BinarySelector.BYTES_NE)) {
                    identityComparisons++;
                }
            }
            checkEq(2, identityComparisons, "the fixture produces both BYTES_EQ and "
                + "BYTES_NE (the allocation-identity comparison)");
            SemanticDifferentialHarness.Verdict verdict =
                SemanticDifferentialHarness.runProject(result.project(), result.tables(),
                    result.registries(),
                    SemanticDifferentialHarness.Expectation.success("the oracle drive",
                        List.of(), "null"),
                    Files.createTempDirectory("bytes-oracle-matrix-"));
            // The entry returns through the bound() call, so the terminal atom is
            // the returned null.
            check(verdict.pass(), "the bytes realization agrees on the oracle and both "
                + "artifacts: " + verdict.failures());
        } finally {
            deleteRecursively(root);
        }
    }

    // =========================================================================
    // 5b. The host-ABI fixture: the bytes-aware host on every consumer
    // =========================================================================

    /** The async test export of the host-importing integration fixture. */
    private static final String HOST_EXPORT = "test_bytes_class_default_integration";

    private static final String HOST_BYTES_ROUNDTRIP_LUA = """
        local rt = require("deal.runtime")
        local calls = 0
        local shared = rt.bytes_new(2)
        return {
          echoBytes = function(b) calls = calls + 1 return b end,
          nullableBytes = function(b) calls = calls + 1 return b end,
          makeBytes = function(n) calls = calls + 1 return rt.bytes_new(n) end,
          sharedBytes = function() return shared end,
          readByte = function(b) calls = calls + 1 return rt.bytes_get(b, 0) end,
          callCount = function() return calls end,
          badBytesReturn = function() calls = calls + 1 return "not-bytes" end,
        }
        """;

    /**
     * The deployed JVM host of the bytes fixture: the shared JVM host ABI's
     * synthesized {@code $DealRt.Bytes} carrier is the declared bytes
     * representation, with one fresh buffer per {@code makeBytes} and one
     * retained buffer for {@code sharedBytes}.
     */
    private static final String HOST_BYTES_ROUNDTRIP_JAVA = """
        public final class %s {
          private static int calls;
          private static $DealRt.Bytes shared = new $DealRt.Bytes(new byte[2]);
          public static Object echoBytes($DealRt.Bytes b) { calls += 1; return b; }
          public static Object nullableBytes($DealRt.Bytes b) { calls += 1; return b; }
          public static Object makeBytes(int n) { calls += 1; return new $DealRt.Bytes(new byte[n]); }
          public static Object sharedBytes() { return shared; }
          public static int readByte($DealRt.Bytes b) { calls += 1; return b.data[0] & 0xFF; }
          public static int callCount() { return calls; }
          public static Object badBytesReturn() { calls += 1; return "not-bytes"; }
        }
        """;

    /**
     * The deterministic host half of the bytes fixture: one retained shared
     * buffer and one call counter — the oracle-side analog of the deployed
     * host module and host class.
     */
    private static final class BytesHostState {

        private int calls;
        private final SemanticOracle.Value.BytesValue shared =
            new SemanticOracle.Value.BytesValue(2);

        int calls() {
            return calls;
        }

        SemanticOracle.HostResponder responder() {
            return new SemanticOracle.HostResponder() {
                @Override
                public SyncOutcome call(ModuleId module, String export,
                        RuntimeDescriptor.Func descriptor,
                        List<SemanticOracle.Value> args) {
                    if (!"host.bytes_roundtrip".equals(module.path())) {
                        return new SyncOutcome.Thrown("E9001",
                            "unknown host module " + module.path());
                    }
                    return switch (export) {
                        case "echoBytes", "nullableBytes" -> {
                            calls++;
                            yield new SyncOutcome.Returned(args.get(0));
                        }
                        case "makeBytes" -> {
                            calls++;
                            long length =
                                ((SemanticOracle.Value.IntValue) args.get(0)).value();
                            yield new SyncOutcome.Returned(
                                new SemanticOracle.Value.BytesValue((int) length));
                        }
                        case "sharedBytes" -> new SyncOutcome.Returned(shared);
                        case "readByte" -> {
                            calls++;
                            SemanticOracle.Value.BytesValue buffer =
                                (SemanticOracle.Value.BytesValue) args.get(0);
                            yield new SyncOutcome.Returned(
                                new SemanticOracle.Value.IntValue(buffer.read(0)));
                        }
                        case "callCount" -> new SyncOutcome.Returned(
                            new SemanticOracle.Value.IntValue(calls));
                        case "badBytesReturn" -> {
                            calls++;
                            yield new SyncOutcome.Returned(
                                new SemanticOracle.Value.StrValue("not-bytes"));
                        }
                        default -> new SyncOutcome.Thrown("E9001",
                            "unknown host export " + export);
                    };
                }
            };
        }
    }

    /**
     * The host-importing integration fixture: the fixture's own async test
     * export runs through the oracle's async-entry invocation, the shared
     * LuaJIT production artifact (deferred main, then the recorded async
     * entry), and the shared JVM production artifact (dealMain, then the
     * emitted static async entry) — each with the same bytes-aware host
     * implementation. The fixture's own assertions (the once-per-attempt
     * default order, the isolated mutable buffers, the retained host-returned
     * identity, both E8001 catches, and the {@code json.stringify} rejection)
     * are the pinned outcome.
     */
    private static void driveHostFixture(BytesFixture spec) throws Exception {
        BytesFixture entry = new BytesFixture(BYTES_DIR, spec.relativePath(), "main",
            "null", List.of(), null, null, 0, 0);
        Compiled compiled = compileFixture(entry);
        if (compiled == null) {
            return;
        }
        try {
            Drive drive = lower(compiled, entry);
            if (drive == null) {
                return;
            }
            BytesHostState host = new BytesHostState();
            SemanticRuntimeModel.ConsumerRun oracle = SemanticOracle.invokeAsyncEntry(
                drive.project(), drive.tables(), host.responder(), drive.fixtureModule(),
                HOST_EXPORT, List.of());
            check(oracle.terminal() instanceof SemanticRuntimeModel.Terminal.Success,
                spec.what() + ": the oracle runs the async host fixture to success: "
                    + oracle.terminal());
            checkEq(4, host.calls(), spec.what() + ": the oracle's host counter reaches "
                + "the fixture's phase-order total");
            hostLuaProduction(drive, spec);
            hostJvmProduction(drive, spec);
        } finally {
            deleteRecursively(compiled.root());
        }
    }

    /**
     * The async-entry fixture drive: the fixture's own async test export runs
     * through the oracle's async-entry invocation, the shared three-consumer
     * async matrix (the trace parity of the oracle and the two shared
     * artifacts), and both production project artifacts' async dispatch
     * entries, so the fixture's own TEST_FAIL battery and its bytes-typed
     * dynamic dispatch execute on every consumer.
     */
    private static void driveAsyncFixture(BytesFixture spec, String export)
            throws Exception {
        Compiled compiled = compileFixture(spec);
        if (compiled == null) {
            return;
        }
        try {
            Drive drive = lower(compiled, spec);
            if (drive == null) {
                return;
            }
            SemanticRuntimeModel.ConsumerRun oracle = SemanticOracle.invokeAsyncEntry(
                drive.project(), drive.tables(), null, drive.fixtureModule(), export,
                List.of());
            check(oracle.terminal() instanceof SemanticRuntimeModel.Terminal.Success
                    success && "int:0".equals(success.resultAtom()),
                spec.what() + ": the oracle runs the async fixture's own test export "
                    + "to the pinned success: " + oracle.terminal());
            Path workspace = Files.createTempDirectory("bytes-async-");
            SemanticDifferentialHarness.Verdict verdict;
            try {
                verdict = SemanticDifferentialHarness.runAsyncEntry(drive.project(),
                    drive.tables(), export, List.of(),
                    SemanticDifferentialHarness.Expectation.success(spec.what(),
                        List.of(), "int:0"),
                    workspace, null);
            } finally {
                deleteRecursively(workspace);
            }
            checkEq(3, verdict.runs().size(), spec.what() + ": the async drive produced "
                + "the three consumers: " + verdict.failures());
            check(verdict.pass(), spec.what() + ": the three-consumer async "
                + "differential verdict passes (the fixture's own TEST_FAIL battery "
                + "and its dispatch traces event-for-event): " + verdict.failures());
            asyncLuaProduction(drive, spec, export);
            asyncJvmProduction(drive, spec, export);
        } finally {
            deleteRecursively(compiled.root());
        }
    }

    /** The shared LuaJIT production artifact of the async fixture, async-driven. */
    private static void asyncLuaProduction(Drive drive, BytesFixture spec, String export)
            throws Exception {
        Path workspace = Files.createTempDirectory("bytes-async-lua");
        try {
            Path artifact = workspace.resolve("project.lua");
            Files.writeString(artifact, LuaSemanticEmitter.emitProductionProject(
                drive.project(), drive.tables(), drive.registries(),
                drive.compiled().surface()), StandardCharsets.UTF_8);
            deployRuntime(workspace);
            Path probe = workspace.resolve("probe.lua");
            Files.writeString(probe, ("""
                local chunk = dofile("%s")
                local ok, err = __dealMain()
                if not ok then
                  print("ERR:INIT|" .. tostring(err))
                  os.exit(0)
                end
                local ok2, res = pcall(__asyncEntries["%s#%s"], "-", true)
                if ok2 then
                  print("OK:" .. tostring(res))
                elseif type(res) == "table" and res.__d then
                  print("ERR:ENTRY|" .. res.code .. "|" .. tostring(res.m))
                else
                  print("ERR:ENTRY|" .. tostring(res))
                end
                """).formatted(artifact.toAbsolutePath().toString(),
                    drive.fixtureModule().path(), export), StandardCharsets.UTF_8);
            BoundedRun probeRun = runLua(workspace, probe,
                Map.of("DEAL_DEFER_MAIN", "1"));
            assertTranscript(spec.what(), "luajit async probe", probeRun, 0,
                "OK:0\n");
        } finally {
            deleteRecursively(workspace);
        }
    }

    /** The shared JVM production artifact of the async fixture, async-driven. */
    private static void asyncJvmProduction(Drive drive, BytesFixture spec, String export)
            throws Exception {
        Path workspace = Files.createTempDirectory("bytes-async-jvm");
        try {
            String className = JvmBackend.classNameFor(
                drive.project().entryModule().path());
            JvmSemanticEmitter.EmissionResult emission =
                JvmSemanticEmitter.emitProductionProject(drive.project(),
                    drive.tables(), drive.registries(), className,
                    drive.compiled().surface());
            Files.writeString(workspace.resolve(className + ".java"), emission.source(),
                StandardCharsets.UTF_8);
            long entryId = -1;
            for (SemanticOp op : drive.unit().ops()) {
                if (op.kind() == SemanticOpKind.EXTERNAL_ENTRY
                        && op.payload() instanceof KindPayload.ExternalEntryPayload payload
                        && payload.async() && export.equals(payload.exportName())) {
                    entryId = op.opId().id();
                }
            }
            check(entryId >= 0, spec.what() + ": the fixture module records its async "
                + "EXTERNAL_ENTRY");
            if (entryId < 0) {
                return;
            }
            Files.writeString(workspace.resolve("Probe.java"), ("""
                final class Probe {
                  public static void main(String[] args) {
                    try {
                      %s.dealMain();
                    } catch (deal.codegen.jvm.JvmRuntime.DealError e) {
                      System.out.println("ERR:INIT|" + e.code + "|" + e.msg);
                      return;
                    }
                    try {
                      Object r = %s.ae%d("-", true, new Object[]{});
                      System.out.println("OK:" + r);
                    } catch (deal.codegen.jvm.JvmRuntime.DealError e) {
                      System.out.println("ERR:ENTRY|" + e.code + "|" + e.msg);
                    }
                  }
                }
                """).formatted(className, className, entryId), StandardCharsets.UTF_8);
            Path classes = workspace.resolve("classes");
            Files.createDirectories(classes);
            BoundedRun compile = runJavac(workspace, classes,
                List.of(className + ".java", "Probe.java"));
            check(compile.captureClean() && compile.exitCode() == 0, spec.what()
                + ": the JVM async fixture compiles with the emitted production "
                + "artifact: " + compile.stdout() + compile.stderr());
            if (compile.exitCode() != 0) {
                return;
            }
            assertTranscript(spec.what(), "jvm async probe",
                runJava(absoluteClasspath(), classes, "Probe"), 0, "OK:0\n");
        } finally {
            deleteRecursively(workspace);
        }
    }

    /** The shared LuaJIT production artifact of the host fixture, host-driven. */
    private static void hostLuaProduction(Drive drive, BytesFixture spec) throws Exception {
        Path workspace = Files.createTempDirectory("bytes-host-lua");
        try {
            Path artifact = workspace.resolve("project.lua");
            Files.writeString(artifact, LuaSemanticEmitter.emitProductionProject(
                drive.project(), drive.tables(), drive.registries(),
                drive.compiled().surface()), StandardCharsets.UTF_8);
            deployRuntime(workspace);
            Path hostDir = workspace.resolve("host");
            Files.createDirectories(hostDir);
            Files.writeString(hostDir.resolve("bytes_roundtrip.lua"),
                HOST_BYTES_ROUNDTRIP_LUA, StandardCharsets.UTF_8);
            Path probe = workspace.resolve("probe.lua");
            Files.writeString(probe, ("""
                local chunk = dofile("%s")
                local ok, err = __dealMain()
                if not ok then
                  print("ERR:INIT|" .. tostring(err))
                  os.exit(0)
                end
                local ok2, res = pcall(__asyncEntries["%s#%s"], "-", true)
                if ok2 then
                  print("OK")
                else
                  print("ERR:ENTRY|" .. tostring(res and res.code or res))
                end
                """).formatted(artifact.toAbsolutePath().toString(),
                    drive.fixtureModule().path(), HOST_EXPORT), StandardCharsets.UTF_8);
            BoundedRun probeRun = runLua(workspace, probe,
                Map.of("DEAL_DEFER_MAIN", "1"));
            assertTranscript(spec.what(), "luajit host probe", probeRun, 0, "OK\n");
        } finally {
            deleteRecursively(workspace);
        }
    }

    /** The shared JVM production artifact of the host fixture, host-driven. */
    private static void hostJvmProduction(Drive drive, BytesFixture spec) throws Exception {
        Path workspace = Files.createTempDirectory("bytes-host-jvm");
        try {
            String className = JvmBackend.classNameFor(
                drive.project().entryModule().path());
            JvmSemanticEmitter.EmissionResult emission =
                JvmSemanticEmitter.emitProductionProject(drive.project(),
                    drive.tables(), drive.registries(), className,
                    drive.compiled().surface());
            Files.writeString(workspace.resolve(className + ".java"), emission.source(),
                StandardCharsets.UTF_8);
            String hostClass = JvmBackend.classNameFor("host/bytes_roundtrip");
            Files.writeString(workspace.resolve(hostClass + ".java"),
                HOST_BYTES_ROUNDTRIP_JAVA.formatted(hostClass), StandardCharsets.UTF_8);
            long entryId = -1;
            for (SemanticOp op : drive.unit().ops()) {
                if (op.kind() == SemanticOpKind.EXTERNAL_ENTRY
                        && op.payload() instanceof KindPayload.ExternalEntryPayload payload
                        && payload.async() && HOST_EXPORT.equals(payload.exportName())) {
                    entryId = op.opId().id();
                }
            }
            check(entryId >= 0, spec.what() + ": the fixture module records its async "
                + "EXTERNAL_ENTRY");
            Files.writeString(workspace.resolve("Probe.java"), ("""
                final class Probe {
                  public static void main(String[] args) {
                    try {
                      %s.dealMain();
                    } catch (deal.codegen.jvm.JvmRuntime.DealError e) {
                      System.out.println("ERR:INIT|" + e.code + "|" + e.msg);
                      return;
                    }
                    try {
                      Object r = %s.ae%d("-", true, new Object[]{});
                      System.out.println("OK");
                    } catch (deal.codegen.jvm.JvmRuntime.DealError e) {
                      System.out.println("ERR:ENTRY|" + e.code + "|" + e.msg);
                    }
                  }
                }
                """).formatted(className, className, entryId), StandardCharsets.UTF_8);
            Path classes = workspace.resolve("classes");
            Files.createDirectories(classes);
            BoundedRun compile = runJavac(workspace, classes,
                List.of(className + ".java", hostClass + ".java", "Probe.java"));
            check(compile.captureClean() && compile.exitCode() == 0, spec.what()
                + ": the JVM host fixture compiles with the emitted host ABI surface "
                + "and the deployed host class: " + compile.stdout()
                + compile.stderr());
            if (compile.exitCode() != 0) {
                return;
            }
            assertTranscript(spec.what(), "jvm host probe",
                runJava(absoluteClasspath(), classes, "Probe"), 0, "OK\n");
        } finally {
            deleteRecursively(workspace);
        }
    }

    /** Whether one unit carries at least one bytes construct (K6). */
    private static boolean carriesBytesConstruct(LoweredModuleUnit unit) {
        for (SemanticOp op : unit.ops()) {
            if (op.kind() == SemanticOpKind.INTRINSIC_CALL
                    && op.payload() instanceof KindPayload.IntrinsicCallPayload intrinsic
                    && intrinsic.kind() == IntrinsicKind.BYTES_NEW) {
                return true;
            }
            if (op.payload() instanceof KindPayload.IndexNormalizePayload normalize
                    && (normalize.mode() == IndexMode.BYTES_READ
                        || normalize.mode() == IndexMode.BYTES_WRITE)) {
                return true;
            }
            if (op.payload() instanceof KindPayload.BoundaryPayload boundary
                    && (boundary.kind() == BoundaryKind.BYTE_ELEMENT_READ
                        || boundary.kind() == BoundaryKind.BYTE_ELEMENT_ASSIGNMENT)) {
                return true;
            }
            if (op.kind() == SemanticOpKind.ASSIGN
                    && op.payload() instanceof KindPayload.AssignPayload assign
                    && assign.targetKind() == deal.semantic.ir.AssignTargetKind.BYTES_SLOT) {
                return true;
            }
            if (op.failurePolicy() == FailurePolicyId.BYTES_ALLOCATE
                    || op.failurePolicy() == FailurePolicyId.BYTES_READ
                    || op.failurePolicy() == FailurePolicyId.BYTES_WRITE) {
                return true;
            }
            if (op.resultType() instanceof RuntimeDescriptor resultDescriptor
                    && containsBytes(resultDescriptor)) {
                return true;
            }
            for (RuntimeDescriptor descriptor : op.operandTypes()) {
                if (containsBytes(descriptor)) {
                    return true;
                }
            }
            if (op.payload() instanceof KindPayload.BoundaryPayload boundary
                    && containsBytes(boundary.descriptor())) {
                return true;
            }
            if (op.kind() == SemanticOpKind.INDEX_READ
                    && op.payload() instanceof KindPayload.IndexReadPayload read) {
                for (SemanticOp candidate : unit.ops()) {
                    if (read.slot().equals(candidate.result())
                            && candidate.resultType()
                                instanceof RuntimeDescriptor.Bytes) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Whether one descriptor contains the bytes carrier at any depth. */
    private static boolean containsBytes(RuntimeDescriptor descriptor) {
        return switch (descriptor) {
            case null -> false;
            case RuntimeDescriptor.Bytes ignored -> true;
            case RuntimeDescriptor.Array array -> containsBytes(array.element());
            case RuntimeDescriptor.Nullable nullable -> containsBytes(nullable.inner());
            case RuntimeDescriptor.Func func -> {
                boolean found = containsBytes(func.returnType());
                for (RuntimeDescriptor parameter : func.paramTypes()) {
                    found = found || containsBytes(parameter);
                }
                yield found;
            }
            default -> false;
        };
    }

    // =========================================================================
    // Shared helpers
    // =========================================================================

    private static List<SemanticOp> opsOfKind(LoweredModuleUnit unit,
                                              SemanticOpKind kind) {
        List<SemanticOp> found = new ArrayList<>();
        for (SemanticOp op : unit.ops()) {
            if (op.kind() == kind) {
                found.add(op);
            }
        }
        return found;
    }

    private static OpId producerOf(LoweredModuleUnit unit, ValueId value) {
        for (SemanticOp op : unit.ops()) {
            if (value.equals(op.result())) {
                return op.opId();
            }
        }
        return null;
    }

    private static SemanticOp opById(LoweredModuleUnit unit, OpId opId) {
        for (SemanticOp op : unit.ops()) {
            if (op.opId().equals(opId)) {
                return op;
            }
        }
        return null;
    }

    // =========================================================================
    // The production artifact runners (the real toolchains)
    // =========================================================================

    /**
     * One production artifact run: the complete bounded capture of the framed
     * probe run and, on a failing drive, of the artifact's direct
     * {@code DEAL_ERROR_CODE} terminal run ({@code null} for a runtime-ok
     * drive). The drives compare both captures exactly (status, stdout,
     * stderr, capture health) before they read the framed outcome.
     */
    private record ArtifactRun(BoundedRun probe, BoundedRun terminal) {

        /** The framed outcome line; read only after the transcript comparison. */
        String outcome() {
            return extractOutcome(probe.stdout());
        }
    }

    /** The probe framing: the last {@code ERR:}/{@code OK} line of a stdout. */
    private static String extractOutcome(String stdout) {
        return stdout.lines()
            .filter(line -> line.startsWith("ERR:") || line.equals("OK"))
            .reduce((first, second) -> second).orElse("");
    }

    private static String absoluteClasspath() {
        StringBuilder resolved = new StringBuilder();
        for (String entry : System.getProperty("java.class.path", "")
                .split(File.pathSeparator)) {
            if (entry.isEmpty()) {
                continue;
            }
            if (resolved.length() > 0) {
                resolved.append(File.pathSeparator);
            }
            resolved.append(Path.of(entry).toAbsolutePath().normalize());
        }
        return resolved.toString();
    }

    /**
     * The bounded subprocess contract (the harness budget): the child wait
     * and both concurrent stream drains share one monotonic deadline; the
     * 1 MiB cap reports overflow instead of truncating; a read failure stays
     * a visible capture-health outcome; every post-start path stops the
     * tracked process tree, closes the pipes, and reaps the child; and an
     * interrupt stops the tree, reaps, and preserves the interrupt status.
     * Scratch cleanup stays with the caller's {@code finally}.
     */
    private static final int STREAM_CAP_BYTES = 1 << 20;
    private static final long PROCESS_BUDGET_SECONDS = 300;
    private static final long KILL_GRACE_SECONDS = 10;
    private static final long DESCENDANT_POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(10);

    /**
     * One bounded subprocess run: the exit status, the complete separate
     * stdout and stderr captures, and the capture health.
     */
    private record BoundedRun(int exitCode, String stdout, String stderr,
                              boolean stdoutOverflow, boolean stderrOverflow,
                              boolean stdoutReadFailure, boolean stderrReadFailure) {

        boolean captureClean() {
            return !stdoutOverflow() && !stderrOverflow()
                && !stdoutReadFailure() && !stderrReadFailure();
        }
    }

    /** One drained stream: the retained text and its capture health. */
    private record Capture(String text, boolean overflow, boolean readFailure) {
    }

    /**
     * The artifact transcript comparison: the exact expected exit status, the
     * complete stdout, an empty stderr, and the capture health. Returns the
     * first divergence by name or {@code null} when the run is pin-exact. The
     * probe framing and the source-coordinate origin are the only
     * transformations applied to the raw streams.
     */
    private static String transcriptDivergence(BoundedRun run, int expectedExit,
            String expectedStdout) {
        if (run.stdoutOverflow() || run.stderrOverflow()) {
            return "capture overflow: the "
                + (run.stdoutOverflow() ? "stdout" : "stderr")
                + " capture exceeded the " + STREAM_CAP_BYTES + "-byte cap";
        }
        if (run.stdoutReadFailure() || run.stderrReadFailure()) {
            return "capture read failure: the "
                + (run.stdoutReadFailure() ? "stdout" : "stderr")
                + " capture did not drain to EOF";
        }
        if (run.exitCode() != expectedExit) {
            return "exit: captured " + run.exitCode() + ", pinned " + expectedExit;
        }
        if (!expectedStdout.equals(run.stdout())) {
            return "stdout: captured " + escaped(run.stdout()) + ", pinned "
                + escaped(expectedStdout);
        }
        if (!run.stderr().isEmpty()) {
            return "stderr: captured " + escaped(run.stderr()) + ", pinned none";
        }
        return null;
    }

    private static String escaped(String text) {
        return "'" + text.replace("\\", "\\\\").replace("\r", "\\r")
            .replace("\n", "\\n") + "'";
    }

    /** Asserts the exact status and complete transcript of one artifact run. */
    private static void assertTranscript(String label, String target, BoundedRun run,
            int expectedExit, String expectedStdout) {
        if (run == null) {
            check(false, label + " (" + target + "): no artifact run was captured");
            return;
        }
        String divergence = transcriptDivergence(run, expectedExit, expectedStdout);
        check(divergence == null, label + " (" + target + "): the exit status and the "
            + "complete transcript are pin-exact, but " + divergence);
    }

    /**
     * Runs one subprocess under the bounded-output and deadline contract. The
     * child wait and both drains share one monotonic deadline; a held pipe or
     * a live descendant is stopped, drained within a fixed grace window, and
     * reported as a hard timeout, never as a skip.
     */
    private static BoundedRun runBounded(List<String> argv, Path workingDir,
            Map<String, String> environment) throws Exception {
        return runBounded(argv, workingDir, environment, PROCESS_BUDGET_SECONDS);
    }

    private static BoundedRun runBounded(List<String> argv, Path workingDir,
            Map<String, String> environment, long budgetSeconds) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(argv);
        builder.directory(workingDir.toFile());
        builder.environment().putAll(environment);
        Process process = builder.start();
        Set<ProcessHandle> descendants =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
        long deadline = System.nanoTime()
            + TimeUnit.SECONDS.toNanos(budgetSeconds);
        CompletableFuture<Capture> stdout = drainAsync(process.getInputStream());
        CompletableFuture<Capture> stderr = drainAsync(process.getErrorStream());
        try {
            boolean drained = awaitExit(process, descendants, deadline)
                && awaitDrains(stdout, stderr, deadline);
            if (!drained) {
                stopProcessTree(process, descendants);
                closePipes(process);
                awaitDrains(stdout, stderr, System.nanoTime()
                    + TimeUnit.SECONDS.toNanos(KILL_GRACE_SECONDS));
                throw new AssertionError("BOUNDED_PROCESS_TIMEOUT " + argv.get(0));
            }
            Capture out = stdout.join();
            Capture err = stderr.join();
            return new BoundedRun(process.exitValue(), out.text(), err.text(),
                out.overflow(), err.overflow(), out.readFailure(), err.readFailure());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted;
        } finally {
            stopProcessTree(process, descendants);
            closePipes(process);
            reap(process);
        }
    }

    /**
     * Waits for the child under the deadline while snapshotting its
     * descendants, so a descendant that survives the direct child stays
     * tracked for the stop path.
     */
    private static boolean awaitExit(Process process, Set<ProcessHandle> descendants,
            long deadline) throws InterruptedException {
        while (true) {
            process.descendants().forEach(descendants::add);
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                return !process.isAlive();
            }
            if (process.waitFor(Math.min(remaining, DESCENDANT_POLL_NANOS),
                    TimeUnit.NANOSECONDS)) {
                return true;
            }
        }
    }

    /** Waits for both captures under the deadline; never blocks past it. */
    private static boolean awaitDrains(CompletableFuture<Capture> stdout,
            CompletableFuture<Capture> stderr, long deadline)
            throws InterruptedException {
        while (System.nanoTime() < deadline) {
            if (stdout.isDone() && stderr.isDone()) {
                return true;
            }
            long remaining = Math.max(1, deadline - System.nanoTime());
            TimeUnit.NANOSECONDS.sleep(Math.min(DESCENDANT_POLL_NANOS, remaining));
        }
        return stdout.isDone() && stderr.isDone();
    }

    /** Drains one stream to EOF in the background; capture failures are data. */
    private static CompletableFuture<Capture> drainAsync(InputStream stream) {
        return CompletableFuture.supplyAsync(() -> drainCapped(stream));
    }

    /**
     * Drains one stream to EOF, retaining at most the cap. Bytes past the cap
     * set the overflow signal, and a read failure keeps the bytes read so far
     * with the read-failure signal - neither is silently dropped.
     */
    private static Capture drainCapped(InputStream stream) {
        java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        boolean overflow = false;
        try {
            int read;
            while ((read = stream.read(buffer)) >= 0) {
                int room = STREAM_CAP_BYTES - captured.size();
                if (read <= room) {
                    captured.write(buffer, 0, read);
                } else {
                    if (room > 0) {
                        captured.write(buffer, 0, room);
                    }
                    overflow = true;
                }
            }
        } catch (java.io.IOException failure) {
            return new Capture(captured.toString(StandardCharsets.UTF_8), overflow,
                true);
        }
        return new Capture(captured.toString(StandardCharsets.UTF_8), overflow, false);
    }

    /** Stops the child and every snapshotted descendant. */
    private static void stopProcessTree(Process process, Set<ProcessHandle> descendants) {
        process.descendants().forEach(descendants::add);
        descendants.forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    /** Closes the child pipes so a drain blocked on a held pipe can finish. */
    private static void closePipes(Process process) {
        try {
            process.getInputStream().close();
        } catch (Exception ignored) {
            // the pipe is already gone
        }
        try {
            process.getErrorStream().close();
        } catch (Exception ignored) {
            // the pipe is already gone
        }
    }

    /** Reaps the child within a bounded wait, preserving the interrupt status. */
    private static void reap(Process process) {
        boolean interrupted = Thread.interrupted();
        try {
            process.waitFor(KILL_GRACE_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException interruption) {
            interrupted = true;
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static ArtifactRun luaProduction(Drive drive) throws Exception {
        return luaProduction(drive, null, null);
    }

    /**
     * The production LuaJIT artifact, optionally with a deployed host
     * module for a host-importing focused program.
     */
    private static ArtifactRun luaProduction(Drive drive, String hostModule,
            Path hostImplementation) throws Exception {
        return luaProduction(drive, hostModule, hostImplementation,
            LuaSemanticEmitter.emitProductionProject(drive.project(), drive.tables(),
                drive.registries(), drive.compiled().surface()));
    }

    private static ArtifactRun luaProduction(Drive drive, String hostModule,
            Path hostImplementation, String chunk) throws Exception {
        Path workspace = Files.createTempDirectory("bytes-lua");
        try {
            Path artifact = workspace.resolve("project.lua");
            Files.writeString(artifact, chunk, StandardCharsets.UTF_8);
            deployRuntime(workspace);
            if (hostModule != null) {
                Path hostDir = workspace.resolve("host");
                Files.createDirectories(hostDir);
                Files.copy(hostImplementation, hostDir.resolve(
                    hostModule.substring(hostModule.lastIndexOf('/') + 1) + ".lua"));
            }
            Path probe = workspace.resolve("probe.lua");
            Files.writeString(probe, """
                local chunk = dofile("%s")
                local ok, err = __dealMain()
                if not ok then
                  if type(err) == "table" and err.__d then
                    local expected = err.e
                    local actual = err.a
                    if expected == nil then expected = "" end
                    if actual == nil then actual = "" end
                    print("ERR:" .. err.code .. "|" .. err.m .. "|" .. err.o .. "|"
                      .. expected .. "|" .. actual)
                  else
                    print("ERR:" .. tostring(err))
                  end
                  os.exit(0)
                end
                print("OK")
                """.formatted(artifact.toAbsolutePath().toString()),
                StandardCharsets.UTF_8);
            BoundedRun probeRun = runLua(workspace, probe,
                Map.of("DEAL_DEFER_MAIN", "1"));
            BoundedRun terminalRun = drive.spec().runtimeOk() ? null
                : runLua(workspace, artifact, Map.of());
            return new ArtifactRun(probeRun, terminalRun);
        } finally {
            deleteRecursively(workspace);
        }
    }

    /** One bounded run of a Lua script in the artifact workspace. */
    private static BoundedRun runLua(Path workspace, Path script,
            Map<String, String> environment) throws Exception {
        return runBounded(List.of("luajit", script.toAbsolutePath().toString()),
            workspace, environment);
    }

    private static void deployRuntime(Path workspace) throws Exception {
        Path runtimeTarget = workspace.resolve("deal").resolve("runtime.lua");
        Files.createDirectories(runtimeTarget.getParent());
        Files.copy(Path.of("deal", "runtime.lua"), runtimeTarget);
        Path stdTarget = workspace.resolve("std");
        Files.createDirectories(stdTarget);
        try (var listing = Files.list(Path.of("std"))) {
            for (Path file : listing.sorted().toList()) {
                if (file.getFileName().toString().endsWith(".lua")) {
                    Files.copy(file, stdTarget.resolve(file.getFileName()));
                }
            }
        }
    }

    private static ArtifactRun jvmProduction(Drive drive) throws Exception {
        return jvmProduction(drive, null, null);
    }

    /**
     * The production JVM artifact, optionally with a deployed host class
     * for a host-importing focused program.
     */
    private static ArtifactRun jvmProduction(Drive drive, String hostModule,
            Path hostImplementation) throws Exception {
        Path workspace = Files.createTempDirectory("bytes-jvm");
        try {
            String className = JvmBackend.classNameFor(
                drive.project().entryModule().path());
            JvmSemanticEmitter.EmissionResult emission =
                JvmSemanticEmitter.emitProductionProject(drive.project(),
                    drive.tables(), drive.registries(), className,
                    drive.compiled().surface());
            Files.writeString(workspace.resolve(className + ".java"),
                emission.source(), StandardCharsets.UTF_8);
            List<String> sources = new ArrayList<>();
            sources.add(className + ".java");
            if (hostModule != null) {
                String hostClass = JvmBackend.classNameFor(hostModule);
                Files.copy(hostImplementation, workspace.resolve(hostClass + ".java"));
                sources.add(hostClass + ".java");
            }
            Files.writeString(workspace.resolve("Probe.java"), """
                final class Probe {
                  public static void main(String[] args) {
                    try {
                      %s.dealMain();
                    } catch (deal.codegen.jvm.JvmRuntime.DealError error) {
                      System.out.println("ERR:" + error.code + "|" + error.msg + "|"
                          + error.origin + "|"
                          + (error.expected == null ? "" : error.expected) + "|"
                          + (error.actual == null ? "" : error.actual));
                      return;
                    }
                    System.out.println("OK");
                  }
                }
                """.formatted(className), StandardCharsets.UTF_8);
            sources.add("Probe.java");
            Path classes = workspace.resolve("classes");
            Files.createDirectories(classes);
            BoundedRun compile = runJavac(workspace, classes, sources);
            check(compile.captureClean() && compile.exitCode() == 0,
                drive.spec().what() + ": the JVM production artifact compiles "
                    + "(javac --release 25 -proc:none): " + compile.stdout()
                    + compile.stderr());
            if (compile.exitCode() != 0) {
                return new ArtifactRun(failedRun(), null);
            }
            BoundedRun probeRun = runJava(absoluteClasspath(), classes, "Probe");
            BoundedRun terminalRun = drive.spec().runtimeOk() ? null
                : runJava(absoluteClasspath(), classes, className);
            return new ArtifactRun(probeRun, terminalRun);
        } finally {
            deleteRecursively(workspace);
        }
    }

    /** One bounded {@code javac} run of the emitted sources. */
    private static BoundedRun runJavac(Path workspace, Path classes,
            List<String> sources) throws Exception {
        List<String> argv = new ArrayList<>(List.of("javac", "--release", "25",
            "-proc:none", "-cp", absoluteClasspath(), "-d", classes.toString()));
        argv.addAll(sources);
        return runBounded(argv, workspace, Map.of());
    }

    /** One bounded {@code java} run of a compiled artifact or probe. */
    private static BoundedRun runJava(String classpath, Path classes, String mainClass)
            throws Exception {
        return runBounded(List.of("java", "-cp",
            classpath + File.pathSeparator + classes, mainClass),
            classes.getParent(), Map.of());
    }

    /** The failed-run placeholder: a compile that produced no artifact. */
    private static BoundedRun failedRun() {
        return new BoundedRun(-1, "", "", false, false, false, false);
    }

    // =========================================================================
    // 7. The focused runner contract: exact status and transcript, capture
    //    health, and the bounded process lifecycle (ISSUE-0710)
    // =========================================================================

    /** A runtime-ok program that prints one unexpected stdout line. */
    private static final String EXTRA_STDOUT_SOURCE = """
        import * as console from "std/console"

        export function main(): null {
          console.log("UNEXPECTED_STDOUT")
          return null
        }
        """;

    /** A runtime-ok program that prints one unexpected stderr line. */
    private static final String EXTRA_STDERR_SOURCE = """
        import * as console from "std/console"

        export function main(): null {
          console.error("UNEXPECTED_STDERR")
          return null
        }
        """;

    /**
     * The focused acceptance contract's runner and comparison paths: extra
     * stdout, extra stderr, a wrong exit status, an over-cap capture, and a
     * read failure each fail by name; an interrupted run stops its child and
     * preserves the interrupt status; and a descendant that survives its
     * parent while holding the pipes is stopped within a bounded window.
     */
    private static void testFocusedRunnerContract() throws Exception {
        System.out.println("-- the focused runner contract: exact status and "
            + "transcript, capture health, and the bounded process lifecycle "
            + "(ISSUE-0710) --");
        Path scratch = Files.createTempDirectory("bytes-runner-");
        try {
            BoundedRun extraStdout = runBounded(List.of("sh", "-c",
                "printf 'OK\\nUNEXPECTED_STDOUT\\n'"), scratch, Map.of());
            String stdoutDivergence = transcriptDivergence(extraStdout, 0, "OK\n");
            check(stdoutDivergence != null && stdoutDivergence.startsWith("stdout:"),
                "extra stdout fails the transcript comparison by name: "
                    + stdoutDivergence);
            check(transcriptDivergence(extraStdout, 0,
                    "OK\nUNEXPECTED_STDOUT\n") == null,
                "the same capture is pin-exact against its true transcript");

            BoundedRun extraStderr = runBounded(List.of("sh", "-c",
                "printf 'OK\\n'; printf 'UNEXPECTED_STDERR\\n' >&2"), scratch,
                Map.of());
            String stderrDivergence = transcriptDivergence(extraStderr, 0, "OK\n");
            check(stderrDivergence != null && stderrDivergence.startsWith("stderr:"),
                "extra stderr fails the transcript comparison by name: "
                    + stderrDivergence);

            BoundedRun wrongStatus = runBounded(List.of("sh", "-c",
                "printf 'OK\\n'; exit 3"), scratch, Map.of());
            String statusDivergence = transcriptDivergence(wrongStatus, 0, "OK\n");
            check(statusDivergence != null && statusDivergence.startsWith("exit:"),
                "a wrong exit status fails the transcript comparison by name: "
                    + statusDivergence);
            checkEq(3, wrongStatus.exitCode(), "the wrong-status control keeps its "
                + "exit status through the runner");

            Capture underCap = drainCapped(fixedStream(STREAM_CAP_BYTES - 1));
            Capture atCap = drainCapped(fixedStream(STREAM_CAP_BYTES));
            Capture overCap = drainCapped(fixedStream(STREAM_CAP_BYTES + 1));
            check(!underCap.overflow() && !atCap.overflow()
                    && underCap.text().length() == STREAM_CAP_BYTES - 1
                    && atCap.text().length() == STREAM_CAP_BYTES,
                "a capture at or below the cap keeps every byte and no overflow "
                    + "signal");
            check(overCap.overflow()
                    && overCap.text().length() == STREAM_CAP_BYTES,
                "a capture past the cap reports overflow and keeps the capped bytes");

            Capture readFailure = drainCapped(new InputStream() {
                @Override
                public int read() {
                    return 'x';
                }

                @Override
                public int read(byte[] bytes, int offset, int length)
                        throws java.io.IOException {
                    throw new java.io.IOException("synthetic read failure");
                }
            });
            check(readFailure.readFailure(),
                "a read failure is reported through the capture path");
            check(!readFailure.overflow(),
                "a read failure is not conflated with an overflow");
        } finally {
            deleteRecursively(scratch);
        }

        checkProgramOutputFailsTheTranscript();
        checkInterruptedRunStopsItsChild();
        checkHeldPipeDescendantRunIsBounded();
    }

    /** A stream of exactly {@code length} 'x' bytes, then EOF. */
    private static InputStream fixedStream(int length) {
        return new InputStream() {
            private int remaining = length;

            @Override
            public int read() {
                return remaining-- > 0 ? 'x' : -1;
            }

            @Override
            public int read(byte[] bytes, int offset, int count) {
                if (remaining <= 0) {
                    return -1;
                }
                int produced = Math.min(count, remaining);
                java.util.Arrays.fill(bytes, offset, offset + produced, (byte) 'x');
                remaining -= produced;
                return produced;
            }
        };
    }

    /**
     * The reviewer-visible trigger: a real production program that prints
     * beyond its pinned transcript is caught by the drive's transcript
     * comparison on both production targets, and nothing reports success.
     */
    private static void checkProgramOutputFailsTheTranscript() throws Exception {
        checkProgramOutput(EXTRA_STDOUT_SOURCE, "stdout:");
        checkProgramOutput(EXTRA_STDERR_SOURCE, "stderr:");
    }

    private static void checkProgramOutput(String source, String expectedFacet)
            throws Exception {
        Path root = Files.createTempDirectory("bytes-program-output-");
        try {
            BytesFixture spec = new BytesFixture(BYTES_DIR, "program-output", "main",
                "null", List.of(), null, null, 0, 0);
            Compiled compiled = compileFocused(root, source, null, null);
            if (compiled == null) {
                return;
            }
            Drive drive = lowerFocused(compiled, spec);
            if (drive == null) {
                return;
            }
            String label = "the real program output control (" + expectedFacet + ")";
            String luaDivergence = transcriptDivergence(luaProduction(drive).probe(), 0,
                "OK\n");
            check(luaDivergence != null && luaDivergence.startsWith(expectedFacet),
                label + ": the LuaJIT artifact's unexpected output fails the "
                    + "transcript comparison: " + luaDivergence);
            String jvmDivergence = transcriptDivergence(jvmProduction(drive).probe(), 0,
                "OK\n");
            check(jvmDivergence != null && jvmDivergence.startsWith(expectedFacet),
                label + ": the JVM artifact's unexpected output fails the transcript "
                    + "comparison: " + jvmDivergence);
        } finally {
            deleteRecursively(root);
        }
    }

    /**
     * An interrupted run must stop its child, reap it, and preserve the
     * interrupt status; the child must not survive the interruption.
     */
    private static void checkInterruptedRunStopsItsChild() throws Exception {
        Path scratch = Files.createTempDirectory("bytes-interrupt-");
        Path pidFile = scratch.resolve("child.pid");
        boolean[] interrupted = { false };
        boolean[] interruptPreserved = { false };
        Throwable[] unexpected = { null };
        Thread runner = new Thread(() -> {
            try {
                runBounded(List.of("sh", "-c",
                    "echo $$ > " + pidFile + "; exec sleep 30"), scratch, Map.of());
                unexpected[0] = new AssertionError("the interrupted run returned");
            } catch (InterruptedException expected) {
                interrupted[0] = true;
                interruptPreserved[0] = Thread.currentThread().isInterrupted();
            } catch (Throwable failure) {
                unexpected[0] = failure;
            }
        });
        runner.start();
        long child = awaitPid(pidFile);
        runner.interrupt();
        runner.join(TimeUnit.SECONDS.toMillis(20));
        try {
            check(!runner.isAlive(),
                "the interrupted runner completes within its budget");
            checkEq(null, unexpected[0],
                "the interrupted runner reports no other failure");
            check(interrupted[0],
                "the interrupted run reports InterruptedException");
            check(interruptPreserved[0],
                "the interrupted run preserves the interrupt status");
            check(child > 0, "the interruption control captured its child pid");
            check(awaitGone(child, 5_000),
                "the interrupted child is stopped, not leaked");
        } finally {
            deleteRecursively(scratch);
        }
    }

    /**
     * A descendant that survives its parent while holding the pipes is
     * stopped by the runner and reported as a bounded timeout, never as an
     * unbounded drain.
     */
    private static void checkHeldPipeDescendantRunIsBounded() throws Exception {
        Path scratch = Files.createTempDirectory("bytes-held-pipe-");
        Path pidFile = scratch.resolve("descendant.pid");
        long started = System.nanoTime();
        AssertionError timeout = null;
        try {
            runBounded(List.of("sh", "-c",
                "sleep 30 & echo $! > " + pidFile + "; sleep 0.2; exit 0"),
                scratch, Map.of(), 1);
        } catch (AssertionError failure) {
            timeout = failure;
        }
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(
            System.nanoTime() - started);
        try {
            check(timeout != null && timeout.getMessage() != null
                    && timeout.getMessage().contains("BOUNDED_PROCESS_TIMEOUT"),
                "the held-pipe run is reported as a bounded timeout: " + timeout);
            check(elapsedMillis < 30_000, "the held-pipe run completes bounded: "
                + elapsedMillis + " ms");
            long descendant = awaitPid(pidFile);
            check(descendant > 0,
                "the held-pipe control captured its descendant pid");
            check(awaitGone(descendant, 5_000),
                "the held-pipe descendant is stopped, not leaked");
        } finally {
            deleteRecursively(scratch);
        }
    }

    /** Waits (bounded) for a child-written pid file; -1 when none appears. */
    private static long awaitPid(Path file) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            if (Files.exists(file)) {
                try {
                    String text = Files.readString(file).strip();
                    if (!text.isEmpty()) {
                        return Long.parseLong(text);
                    }
                } catch (Exception notYet) {
                    // the writer is still completing the file; retry
                }
            }
            TimeUnit.MILLISECONDS.sleep(10);
        }
        return -1;
    }

    /** Bounded wait for a killed process to leave the process table. */
    private static boolean awaitGone(long pid, long millis)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        while (System.nanoTime() < deadline) {
            if (ProcessHandle.of(pid).map(handle -> !handle.isAlive()).orElse(true)) {
                return true;
            }
            TimeUnit.MILLISECONDS.sleep(20);
        }
        return ProcessHandle.of(pid).map(handle -> !handle.isAlive()).orElse(true);
    }

    // =========================================================================
    // 9. The manifest-authored production acceptance: the std/json bytes
    //    companions and ffi/016 through the release-owned invocation
    // =========================================================================

    private static final String STDLIB_JSON_CORPUS = "backend-runtime/stdlib/json";
    private static final String FFI_CORPUS = "backend-runtime/ffi";
    private static final String FFI_BYTES_FIXTURE = "016-ffi-bytes-pointer-length";
    private static final String FFI_NATIVE_SOURCE = FFI_CORPUS + "/support/native.c";
    private static final String FFI_NATIVE_LIBRARY = "libcandidate_native.so";
    private static final String ACCEPT_TRANSPORT = "__accept_transport.txt";

    /** The pinned phase-3.9 JVM extern-C rejection message. */
    private static final String JVM_FFI_REJECTION_MESSAGE =
        "JVM backend: C FFI (@extern-c) declarations are not supported"
            + " (FFI_UNSUPPORTED_BACKEND)";

    /**
     * One manifest-authored corpus project materialized under a temp root.
     * The {@code ffiDeclaration} is the materialized extern-C declaration
     * companion of an FFI project (null otherwise).
     */
    private record ManifestProject(Path root, Path entry, Path out, String corpusRel,
                                   int strippedHeaderLines, String modulePath,
                                   Path ffiDeclaration) {
    }

    /** One manifest-authored compile: the orchestrator and its outcome. */
    private record ManifestCompile(ManifestProject project,
                                   CompilationOrchestrator orchestrator,
                                   boolean success) {
    }

    /** One manifest-authored scratch project (the first-class bytes drives). */
    private record ScratchProject(Path root, Path entry, Path out, String modulePath) {
    }

    /** The artifact-transported DEAL failure tuple of one staged-artifact run. */
    private record CapturedTuple(String code, String message, String file, Integer line,
                                 Integer column, String expected, String actual) {
    }

    /**
     * Materializes one corpus fixture as the manifest entry of a temp
     * project: the header-stripped fixture keeps its corpus-relative path,
     * and the manifest names the target backend (plus the corpus FFI wiring
     * when the fixture imports the extern-C declaration). The FFI wiring
     * carries the caller-owned native library (never the checkout's shared
     * build output), materialized alongside the declaration companion.
     */
    private static ManifestProject materializeCorpusProject(String corpusRel,
            String backend, boolean ffi, Path nativeLibrary) throws Exception {
        Path root = Files.createTempDirectory("bytes-manifest-");
        Path entry = root.resolve(corpusRel);
        Files.createDirectories(entry.getParent());
        String raw = Files.readString(CORPUS.resolve(corpusRel),
            StandardCharsets.UTF_8);
        String stripped = ConformanceHarnessMetadata.stripClassificationHeaders(raw);
        int strippedLines = raw.split("\n", -1).length
            - stripped.split("\n", -1).length;
        Files.writeString(entry, stripped, StandardCharsets.UTF_8);
        String externals = "";
        Path declaration = null;
        if (ffi) {
            CorpusFfi.Wiring wiring = CorpusFfi.wiring(CORPUS).get("candidate/native");
            if (wiring == null) {
                throw new IllegalStateException("the corpus FFI wiring carries no "
                    + "'candidate/native' entry");
            }
            if (nativeLibrary == null) {
                throw new IllegalStateException("the FFI project requires a "
                    + "caller-owned native library");
            }
            declaration = root.resolve("support").resolve("native.d.deal");
            Files.createDirectories(declaration.getParent());
            Files.writeString(declaration, ConformanceHarnessMetadata
                .stripClassificationHeaders(Files.readString(
                    CORPUS.resolve(FFI_CORPUS).resolve(wiring.declarationCorpusPath()),
                    StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
            externals = ",\n  \"externals\": { \"candidate/native\": { "
                + "\"declaration\": \"support/native.d.deal\", \"nativeLibrary\": \""
                + nativeLibrary.toAbsolutePath().normalize().toString()
                    .replace("\\", "\\\\")
                + "\" } }";
        }
        Files.writeString(root.resolve("deal.json"),
            "{\n  \"languageVersion\": \"1.2\",\n  \"moduleRoots\": [\".\"],\n"
                + "  \"output\": \"out\",\n  \"backend\": \"" + backend + "\""
                + externals + "\n}\n", StandardCharsets.UTF_8);
        String modulePath = corpusRel.substring(0,
            corpusRel.length() - ".deal".length()).replace('/', '.');
        return new ManifestProject(root, entry, root.resolve("out"), corpusRel,
            strippedLines, modulePath, declaration);
    }

    /**
     * The manifest-authored release invocation: the project context is
     * resolved by the production locator, the backend is the manifest's
     * authored backend, the compile runs on the release-owned production
     * invocation, and the orchestrator is the manifest-context constructor
     * (never the isolated-phase test constructor).
     */
    private static ManifestCompile compileManifest(ManifestProject project,
            String backend) throws Exception {
        ProjectLocator.LocateResult located = ProjectLocator.locate(
            project.entry().toAbsolutePath().toString(),
            new CliOverrides(null, null));
        check(located.context() != null, project.corpusRel()
            + ": the manifest resolves a project context");
        if (located.context() == null) {
            return new ManifestCompile(project, null, false);
        }
        checkEq(backend, located.context().backend(), project.corpusRel()
            + ": the manifest authors the target backend");
        CompilerInvocation invocation = productionInvocation();
        check(CompilationOrchestrator.isProductionInvocation(invocation),
            project.corpusRel() + ": the compile runs on the release-owned "
                + "production invocation");
        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            located.context(), project.entry().toAbsolutePath(), false, false,
            false, false, null, invocation);
        return new ManifestCompile(project, orchestrator, orchestrator.compile());
    }

    private static String diagnosticsOf(ManifestCompile compile) {
        return compile.orchestrator() == null ? "no context"
            : String.valueOf(compile.orchestrator().diagnostics());
    }

    /**
     * One header-free scratch project under {@code src/} with a manifest
     * that names the target backend, the {@code src} module root, and the
     * {@code out} output root — the first-class bytes drives' project.
     */
    private static ScratchProject materializeScratchProject(String source,
            String backend) throws Exception {
        Path root = Files.createTempDirectory("bytes-first-class-");
        Path src = root.resolve("src");
        Files.createDirectories(src);
        Files.writeString(src.resolve("main.deal"), source, StandardCharsets.UTF_8);
        Files.writeString(root.resolve("deal.json"),
            "{\n  \"languageVersion\": \"1.2\",\n  \"moduleRoots\": [\"src\"],\n"
                + "  \"output\": \"out\",\n  \"backend\": \"" + backend + "\"\n}\n",
            StandardCharsets.UTF_8);
        return new ScratchProject(root, src.resolve("main.deal"), root.resolve("out"),
            "main");
    }

    private static ManifestProject asManifestProject(ScratchProject project) {
        return new ManifestProject(project.root(), project.entry(), project.out(),
            "src/main.deal", 0, project.modulePath(), null);
    }

    private static String luaStringLiteral(String text) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> out.append(c);
            }
        }
        return out.append('"').toString();
    }

    private static String javaString(String text) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                default -> out.append(c);
            }
        }
        return out.append('"').toString();
    }

    /**
     * The staged Lua artifact's acceptance probe: the fixture is the
     * already-staged chunk; the probe drives {@code __dealMain()} and each
     * named export through the artifact's own export surface, and
     * transports the caught DEAL tuple (code, message, origin-derived
     * file/line/column, expected, actual) to the given path. A non-DEAL
     * error is transported as NON_DEAL, never silently swallowed.
     */
    private static String luaAcceptanceProbe(String artifactPath,
            List<String> exports, Path transport) {
        StringBuilder probe = new StringBuilder();
        probe.append("local __transport = ")
            .append(luaStringLiteral(transport.toAbsolutePath().toString()))
            .append("\n");
        probe.append("local function __esc(s)\n")
            .append("  s = tostring(s)\n")
            .append("  s = string.gsub(s, \"\\\\\", \"\\\\\\\\\")\n")
            .append("  s = string.gsub(s, \"\\n\", \"\\\\n\")\n")
            .append("  s = string.gsub(s, \"\\t\", \"\\\\t\")\n")
            .append("  return s\n")
            .append("end\n");
        probe.append("local function __write(fields)\n")
            .append("  local f = io.open(__transport, \"w\")\n")
            .append("  if not f then os.exit(2) end\n")
            .append("  for _, kv in ipairs(fields) do\n")
            .append("    f:write(kv[1], \"\\t\", __esc(kv[2] or \"\"), \"\\n\")\n")
            .append("  end\n")
            .append("  f:close()\n")
            .append("end\n");
        probe.append("local function __capture(err)\n")
            .append("  if type(err) ~= \"table\" or err.code == nil then\n")
            .append("    __write({ {\"code\", \"NON_DEAL\"}, ")
            .append("{\"message\", tostring(err)} })\n")
            .append("    os.exit(1)\n")
            .append("  end\n")
            .append("  local file, line, column = err.file, err.line, err.column\n")
            .append("  if err.o ~= nil then\n")
            .append("    local f, l, c = tostring(err.o):match(")
            .append("\"^(.*):(%d+):(%d+)$\")\n")
            .append("    if f ~= nil then file, line, column = f, tonumber(l), ")
            .append("tonumber(c) end\n")
            .append("  end\n")
            .append("  __write({ {\"code\", err.code}, ")
            .append("{\"message\", err.m or err.message},\n")
            .append("    {\"file\", file}, {\"line\", line}, {\"column\", column},\n")
            .append("    {\"expected\", err.e}, {\"actual\", err.a} })\n")
            .append("  os.exit(1)\n")
            .append("end\n");
        probe.append("local __surface = dofile(")
            .append(luaStringLiteral(artifactPath)).append(")\n");
        probe.append("local __ok, __err = __dealMain()\n");
        probe.append("if not __ok then __capture(__err) end\n");
        for (String export : exports) {
            probe.append("do\n")
                .append("  local __v = __surface[")
                .append(luaStringLiteral(export)).append("]\n")
                .append("  if type(__v) ~= \"table\" or type(__v.f) ~= \"function\" ")
                .append("then os.exit(2) end\n")
                .append("  local __okE, __errE = xpcall(__v.f, ")
                .append("function(e) return e end)\n")
                .append("  if not __okE then __capture(__errE) end\n")
                .append("end\n");
        }
        probe.append("os.exit(0)\n");
        return probe.toString();
    }

    /**
     * The staged JVM artifact's acceptance probe: the fixture is the
     * already-staged class; the probe drives {@code dealMain()} and each
     * named export through the runtime's own export surface, and
     * transports the caught DEAL tuple (code, message, origin, expected,
     * actual) to the given path. A non-DEAL error is transported as
     * NON_DEAL, never silently swallowed.
     */
    private static String jvmAcceptanceProbe(String className, String modulePath,
            List<String> exports, Path transport) {
        StringBuilder probe = new StringBuilder();
        probe.append("public final class Probe {\n");
        probe.append("  public static void main(String[] args) {\n");
        probe.append("    try {\n");
        probe.append("      ").append(className).append(".dealMain();\n");
        for (String export : exports) {
            probe.append("      {\n")
                .append("        Object v = deal.codegen.jvm.JvmRuntime.exportSurface(")
                .append(javaString(modulePath)).append(").read(")
                .append(javaString(export)).append(");\n")
                .append("        if (!(v instanceof deal.codegen.jvm.JvmRuntime.")
                .append("FunctionValue)) { System.exit(2); }\n")
                .append("        ((deal.codegen.jvm.JvmRuntime.FunctionValue) v).fn.")
                .append("invoke(new Object[]{});\n")
                .append("      }\n");
        }
        probe.append("      System.exit(0);\n");
        probe.append("    } catch (Throwable error) {\n");
        probe.append("      transport(error);\n");
        probe.append("    }\n");
        probe.append("  }\n");
        probe.append("  static void transport(Throwable error) {\n");
        probe.append("    Throwable e = error;\n");
        probe.append("    while (e != null && !(e instanceof deal.codegen.jvm.")
            .append("JvmRuntime.DealError) && e.getCause() != null) {\n");
        probe.append("      e = e.getCause();\n");
        probe.append("    }\n");
        probe.append("    StringBuilder out = new StringBuilder();\n");
        probe.append("    if (e instanceof deal.codegen.jvm.JvmRuntime.DealError d) {\n");
        probe.append("      append(out, \"code\", d.code);\n");
        probe.append("      append(out, \"message\", d.msg);\n");
        probe.append("      append(out, \"origin\", d.origin);\n");
        probe.append("      append(out, \"expected\", d.expected);\n");
        probe.append("      append(out, \"actual\", d.actual);\n");
        probe.append("    } else {\n");
        probe.append("      append(out, \"code\", \"NON_DEAL\");\n");
        probe.append("      append(out, \"message\", String.valueOf(error));\n");
        probe.append("    }\n");
        probe.append("    try {\n");
        probe.append("      java.nio.file.Files.writeString(java.nio.file.Path.of(")
            .append(javaString(transport.toAbsolutePath().toString()))
            .append("), out.toString());\n");
        probe.append("    } catch (java.io.IOException ignored) { }\n");
        probe.append("    System.exit(1);\n");
        probe.append("  }\n");
        probe.append("  static void append(StringBuilder out, String key, ")
            .append("String value) {\n");
        probe.append("    if (value == null) { return; }\n");
        probe.append("    String escaped = value.replace(\"\\\\\", \"\\\\\\\\\")")
            .append(".replace(\"\\n\", \"\\\\n\").replace(\"\\t\", \"\\\\t\");\n");
        probe.append("    out.append(key).append('\\t').append(escaped).append('\\n');\n");
        probe.append("  }\n");
        probe.append("}\n");
        return probe.toString();
    }

    /** The transported tuple, or {@code null} when the probe wrote none. */
    private static CapturedTuple readAcceptanceTuple(Path transport) throws Exception {
        if (!Files.isRegularFile(transport)) {
            return null;
        }
        Map<String, String> fields = new LinkedHashMap<>();
        for (String line : Files.readAllLines(transport, StandardCharsets.UTF_8)) {
            int tab = line.indexOf('\t');
            if (tab < 0) {
                continue;
            }
            String key = line.substring(0, tab);
            String value = line.substring(tab + 1);
            if (!value.isEmpty()) {
                fields.put(key, unescapeField(value));
            }
        }
        Integer line = integerField(fields.get("line"));
        Integer column = integerField(fields.get("column"));
        String origin = fields.get("origin");
        if (origin != null) {
            int lastColon = origin.lastIndexOf(':');
            int previousColon = lastColon < 0 ? -1
                : origin.lastIndexOf(':', lastColon - 1);
            if (previousColon >= 0 && lastColon > previousColon + 1) {
                fields.putIfAbsent("file", origin.substring(0, previousColon));
                if (line == null) {
                    line = integerField(origin.substring(previousColon + 1,
                        lastColon));
                }
                if (column == null) {
                    column = integerField(origin.substring(lastColon + 1));
                }
            }
        }
        return new CapturedTuple(fields.get("code"), fields.get("message"),
            fields.get("file"), line, column, fields.get("expected"),
            fields.get("actual"));
    }

    private static Integer integerField(String text) {
        return text == null || text.isEmpty() ? null : Integer.valueOf(text);
    }

    private static String unescapeField(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != '\\' || i + 1 >= text.length()) {
                out.append(c);
                continue;
            }
            char next = text.charAt(++i);
            switch (next) {
                case 'n' -> out.append('\n');
                case 't' -> out.append('\t');
                case '\\' -> out.append('\\');
                default -> out.append(next);
            }
        }
        return out.toString();
    }

    private static void testManifestProductionAcceptance() throws Exception {
        System.out.println("-- the manifest-authored production acceptance: the "
            + "std/json bytes companions and ffi/016 --");
        driveJsonBytesCompanion("json-stringify-bytes-error",
            "test_json_stringify_bytes_error");
        driveJsonBytesCompanion("json-stringify-nested-bytes-error",
            "test_json_stringify_nested_bytes_error");
        driveFfiBytesPointerLength();
    }

    /**
     * One std/json bytes companion through the manifest-authored production
     * invocation: the fixture is the entry, the staged LuaJIT chunk and the
     * staged JVM class are executed with the artifact's own transport, and
     * every sidecar-pinned field (code, message, raw-coordinate rebased
     * line, column, expected, actual, source file, exit code, streams) is
     * compared exactly.
     */
    private static void driveJsonBytesCompanion(String fixture, String export)
            throws Exception {
        String corpusRel = STDLIB_JSON_CORPUS + "/" + fixture + ".deal";
        SidecarExpectations.StructuredExpectationSidecar sidecar =
            SidecarExpectations.StructuredExpectationSidecar.parse(Files.readString(
                CORPUS.resolve(STDLIB_JSON_CORPUS)
                    .resolve(fixture + ".expect.json"), StandardCharsets.UTF_8));

        ManifestProject luaProject = materializeCorpusProject(corpusRel, "luajit",
            false, null);
        try {
            ManifestCompile lua = compileManifest(luaProject, "luajit");
            check(lua.success(), fixture + ": the LuaJIT production compile "
                + "succeeds: " + diagnosticsOf(lua));
            if (lua.success()) {
                Path artifact = luaProject.out().resolve(
                    luaProject.modulePath().replace('.', '/') + ".lua");
                check(Files.isRegularFile(artifact), fixture + ": the staged LuaJIT "
                    + "artifact exists: " + artifact);
                if (Files.isRegularFile(artifact)) {
                    Path transport = luaProject.out().resolve(ACCEPT_TRANSPORT);
                    Path probe = luaProject.out().resolve("__accept_probe.lua");
                    Files.writeString(probe, luaAcceptanceProbe(
                        artifact.toAbsolutePath().toString(), List.of(export),
                        transport), StandardCharsets.UTF_8);
                    BoundedRun run = runBounded(List.of("luajit",
                        "__accept_probe.lua"), luaProject.out(),
                        Map.of("DEAL_DEFER_MAIN", "1"));
                    assertPinnedRuntimeError(fixture + " (luajit staged artifact)",
                        sidecar, "luajit", readAcceptanceTuple(transport), run,
                        luaProject);
                }
            }
        } finally {
            deleteRecursively(luaProject.root());
        }

        ManifestProject jvmProject = materializeCorpusProject(corpusRel, "jvm", false,
            null);
        try {
            ManifestCompile jvm = compileManifest(jvmProject, "jvm");
            check(jvm.success(), fixture + ": the JVM production compile succeeds: "
                + diagnosticsOf(jvm));
            if (jvm.success()) {
                String className = JvmNames.classNameFor(jvmProject.modulePath());
                Path artifact = jvmProject.out().resolve(className + ".java");
                check(Files.isRegularFile(artifact), fixture + ": the staged JVM "
                    + "artifact exists: " + artifact);
                if (Files.isRegularFile(artifact)) {
                    Path transport = jvmProject.out().resolve(ACCEPT_TRANSPORT);
                    Files.writeString(jvmProject.out().resolve("Probe.java"),
                        jvmAcceptanceProbe(className, jvmProject.modulePath(),
                            List.of(export), transport), StandardCharsets.UTF_8);
                    Path classes = jvmProject.out().resolve("classes");
                    Files.createDirectories(classes);
                    BoundedRun compile = runJavac(jvmProject.out(), classes,
                        List.of(artifact.toString(), "Probe.java"));
                    check(compile.captureClean() && compile.exitCode() == 0,
                        fixture + ": the staged JVM artifact compiles with javac "
                            + "--release 25 -proc:none: " + compile.stdout()
                            + compile.stderr());
                    if (compile.exitCode() == 0) {
                        BoundedRun run = runBounded(List.of("java", "-cp",
                            absoluteClasspath() + File.pathSeparator + classes,
                            "Probe"), jvmProject.out(), Map.of());
                        assertPinnedRuntimeError(fixture + " (jvm staged artifact)",
                            sidecar, "jvm", readAcceptanceTuple(transport), run,
                            jvmProject);
                    }
                }
            }
        } finally {
            deleteRecursively(jvmProject.root());
        }
    }

    /**
     * ffi/016 through the manifest-authored production invocation: the
     * LuaJIT staged artifact executes the native pointer-plus-length fold
     * (width, sign, order, length) to its pinned runtime-ok outcome, and
     * both JVM runs are rejected before publication with the sidecar-pinned
     * E6006 — exactly one error, the pinned FFI_UNSUPPORTED_BACKEND message
     * at the materialized declaration's {@code @extern-c} range, no FFI
     * metadata, stages nothing — and the previous artifact set stays
     * byte-identical. The native library is compiled into a private temp
     * directory through the bounded subprocess contract and deleted after
     * all artifact executions finish.
     */
    private static void driveFfiBytesPointerLength() throws Exception {
        String corpusRel = FFI_CORPUS + "/" + FFI_BYTES_FIXTURE + ".deal";
        String raw = Files.readString(CORPUS.resolve(corpusRel),
            StandardCharsets.UTF_8);
        check(raw.contains("data[0] = 128") && raw.contains("data[1] = 255")
                && raw.contains("data[2] = 1") && raw.contains("59443587"),
            "ffi/016 carries the unsigned byte order and the folded native sum "
                + "pin (width, sign, order, length)");
        SidecarExpectations.StructuredExpectationSidecar sidecar =
            SidecarExpectations.StructuredExpectationSidecar.parse(Files.readString(
                CORPUS.resolve(FFI_CORPUS).resolve(FFI_BYTES_FIXTURE
                    + ".expect.json"), StandardCharsets.UTF_8));

        Path nativeDir = Files.createTempDirectory("bytes-ffi-native-");
        try {
            Path nativeLibrary = compileCorpusNativeLibrary(nativeDir);
            ManifestProject luaProject = materializeCorpusProject(corpusRel, "luajit",
                true, nativeLibrary);
            try {
                ManifestCompile lua = compileManifest(luaProject, "luajit");
                check(lua.success(), "ffi/016: the LuaJIT production compile succeeds: "
                    + diagnosticsOf(lua));
                if (lua.success()) {
                    Path artifact = luaProject.out().resolve(
                        luaProject.modulePath().replace('.', '/') + ".lua");
                    check(Files.isRegularFile(artifact), "ffi/016: the staged LuaJIT "
                        + "artifact exists: " + artifact);
                    if (Files.isRegularFile(artifact)) {
                        Path transport = luaProject.out().resolve(ACCEPT_TRANSPORT);
                        Path probe = luaProject.out().resolve("__accept_probe.lua");
                        Files.writeString(probe, luaAcceptanceProbe(
                            artifact.toAbsolutePath().toString(), List.of(), transport),
                            StandardCharsets.UTF_8);
                        BoundedRun run = runBounded(List.of("luajit",
                            "__accept_probe.lua"), luaProject.out(),
                            Map.of("DEAL_DEFER_MAIN", "1"));
                        assertPinnedRuntimeOk("ffi/016 (luajit staged artifact)",
                            sidecar, "luajit", run);
                    }
                }
            } finally {
                deleteRecursively(luaProject.root());
            }

            ManifestProject preserved = materializeCorpusProject(corpusRel, "jvm",
                true, nativeLibrary);
            try {
                Files.createDirectories(preserved.out());
                Files.writeString(preserved.out().resolve("previous-artifact.java"),
                    "previous\n", StandardCharsets.UTF_8);
                Map<String, byte[]> before = snapshotTree(preserved.out());
                ManifestCompile jvm = compileManifest(preserved, "jvm");
                assertPinnedJvmFfiRejection("ffi/016 (preserved output root)",
                    sidecar, jvm);
                checkTreeIdentical(before, preserved.out(), "ffi/016: the JVM rejection "
                    + "stages nothing and preserves the previous artifact set "
                    + "byte-identical");
            } finally {
                deleteRecursively(preserved.root());
            }

            ManifestProject fresh = materializeCorpusProject(corpusRel, "jvm", true,
                nativeLibrary);
            try {
                ManifestCompile jvm = compileManifest(fresh, "jvm");
                assertPinnedJvmFfiRejection("ffi/016 (fresh output root)", sidecar, jvm);
                check(!Files.exists(fresh.out()), "ffi/016: a rejected JVM compile stages "
                    + "no output root");
            } finally {
                deleteRecursively(fresh.root());
            }
        } finally {
            deleteRecursively(nativeDir);
        }
    }

    /**
     * Compiles the committed FFI C fixture ({@code ffi/support/native.c})
     * into the caller-owned temporary directory through the bounded
     * subprocess contract: the drive never writes the checkout's shared
     * {@code build/} output, the GCC compile is deadline- and
     * capture-bounded, and a missing source, an unclean or failed compile,
     * or a missing library fails closed (never a skip). The caller deletes
     * the directory after all artifact executions finish.
     */
    private static Path compileCorpusNativeLibrary(Path nativeDir) throws Exception {
        Path source = CORPUS.resolve(FFI_NATIVE_SOURCE).toAbsolutePath().normalize();
        check(Files.isRegularFile(source),
            "ffi/016: the committed FFI C fixture exists: " + source);
        Path library = nativeDir.resolve(FFI_NATIVE_LIBRARY);
        if (!Files.isRegularFile(source)) {
            return library;
        }
        BoundedRun compile = runBounded(List.of("gcc", "-shared", "-fPIC", "-O2",
            "-o", library.toString(), source.toString()), nativeDir, Map.of());
        check(compile.captureClean(), "ffi/016: the private native-library "
            + "compile is capture-clean");
        checkEq(0, compile.exitCode(), "ffi/016: the private native-library "
            + "compile succeeds: " + compile.stdout() + compile.stderr());
        check(Files.isRegularFile(library), "ffi/016: the private native library "
            + "exists: " + library);
        return library;
    }

    /**
     * The exact JVM pre-publication rejection of one ffi/016 compile: the
     * sidecar's compile-reject leg is the mode/code authority, the
     * orchestrator reports exactly one error — the pinned phase-3.9
     * {@code FFI_UNSUPPORTED_BACKEND} message at the materialized
     * declaration's {@code @extern-c} directive range — and no FFI metadata
     * is generated (the rejection precedes the lowering).
     */
    private static void assertPinnedJvmFfiRejection(String label,
            SidecarExpectations.StructuredExpectationSidecar sidecar,
            ManifestCompile compile) throws Exception {
        check(!compile.success(), label + ": the JVM production compile is rejected");
        SidecarExpectations.RuntimeExpectation leg = sidecar.expectationFor("jvm");
        check(leg instanceof SidecarExpectations.RuntimeExpectation.Rejected rejected
                && "compile-reject".equals(rejected.mode()),
            label + ": the sidecar pins the JVM compile-reject mode");
        if (!(leg instanceof SidecarExpectations.RuntimeExpectation.Rejected rejected)) {
            return;
        }
        Path declaration = compile.project().ffiDeclaration();
        check(declaration != null && Files.isRegularFile(declaration),
            label + ": the materialized extern-C declaration exists: " + declaration);
        if (declaration == null || !Files.isRegularFile(declaration)) {
            return;
        }
        List<CompilerDiagnostic> errors = compile.orchestrator() == null
            ? List.of()
            : compile.orchestrator().diagnostics().stream()
                .filter(diagnostic -> "error".equals(diagnostic.severity())).toList();
        checkEq(1, errors.size(), label + ": exactly one error diagnostic: "
            + diagnosticsOf(compile));
        if (errors.size() != 1) {
            return;
        }
        CompilerDiagnostic rejection = errors.get(0);
        checkEq(rejected.code(), rejection.code(),
            label + ": the sidecar-pinned rejection code");
        checkEq(JVM_FFI_REJECTION_MESSAGE, rejection.message(),
            label + ": the pinned FFI_UNSUPPORTED_BACKEND message");
        String text = Files.readString(declaration, StandardCharsets.UTF_8);
        int directive = text.indexOf("// @extern-c");
        check(directive >= 0, label + ": the materialized declaration carries the "
            + "@extern-c directive");
        if (directive < 0) {
            return;
        }
        int lineStart = text.lastIndexOf('\n', directive) + 1;
        int line = text.substring(0, lineStart).split("\n", -1).length;
        int lineEnd = directive;
        while (lineEnd < text.length() && text.charAt(lineEnd) != '\n'
                && text.charAt(lineEnd) != '\r') {
            lineEnd++;
        }
        checkEq(line, rejection.range().startLine(),
            label + ": the E6006 range starts at the materialized @extern-c line");
        checkEq(directive - lineStart + 1, rejection.range().startColumn(),
            label + ": the E6006 range starts at the materialized @extern-c column");
        checkEq(line, rejection.range().endLine(),
            label + ": the E6006 range ends on the materialized @extern-c line");
        checkEq(lineEnd - lineStart + 1, rejection.range().endColumn(),
            label + ": the E6006 range covers the materialized @extern-c comment");
        checkEq(declaration.toRealPath().toString(), rejection.range().file(),
            label + ": the E6006 range names the materialized declaration");
        check(compile.orchestrator().ffiGenerations().isEmpty(), label + ": the JVM "
            + "rejection publishes no FFI metadata");
    }

    /**
     * The pinned runtime-error leg of one staged-artifact run: the artifact
     * reports the fixture itself as the entry source (fixture-as-entry
     * materialization), the raw coordinates rebase across the stripped
     * headers, and every sidecar-pinned field plus the exit status and both
     * streams compare exactly. The sidecar's own stdout is the canonical
     * framing of the captured tuple.
     */
    private static void assertPinnedRuntimeError(String label,
            SidecarExpectations.StructuredExpectationSidecar sidecar, String backend,
            CapturedTuple tuple, BoundedRun run, ManifestProject project) {
        SidecarExpectations.RuntimeExpectation leg = sidecar.expectationFor(backend);
        check(leg instanceof SidecarExpectations.RuntimeExpectation.Executed executed
                && executed.isRuntimeError(),
            label + ": the sidecar pins a runtime-error leg");
        if (!(leg instanceof SidecarExpectations.RuntimeExpectation.Executed executed)
                || !executed.isRuntimeError()) {
            return;
        }
        check(run != null && run.captureClean(), label + ": the staged-artifact run "
            + "is capture-clean");
        check(tuple != null, label + ": the staged artifact transports the DEAL "
            + "failure tuple");
        if (tuple == null) {
            return;
        }
        SidecarExpectations.ErrorExpectation pinned = executed.error();
        check(pinned != null, label + ": the sidecar pins the error snapshot");
        if (pinned == null) {
            return;
        }
        Path reported = tuple.file() == null ? null
            : Path.of(tuple.file()).toAbsolutePath().normalize();
        String relative = reported == null ? null
            : project.root().relativize(reported).toString()
                .replace(File.separatorChar, '/');
        checkEq(project.corpusRel(), relative, label + ": the artifact reports the "
            + "fixture itself as the entry source (fixture-as-entry materialization)");
        Integer rebasedLine = tuple.line() == null ? null
            : tuple.line() + project.strippedHeaderLines();
        checkEq(pinned.code(), tuple.code(), label + ": the pinned code");
        checkEq(pinned.message(), tuple.message(), label + ": the pinned message");
        checkEq(pinned.line(), rebasedLine, label + ": the pinned raw line (rebased "
            + "across the stripped headers)");
        checkEq(pinned.column(), tuple.column(), label + ": the pinned column");
        checkEq(pinned.expected().orElse(null), tuple.expected(), label + ": the "
            + "pinned expected token");
        checkEq(pinned.actual().orElse(null), tuple.actual(), label + ": the pinned "
            + "actual token");
        checkEq(executed.exitCode(), run.exitCode(), label + ": the pinned exit code");
        checkEq("", run.stdout(), label + ": the probe prints nothing on stdout");
        checkEq(new String(executed.stderr(), StandardCharsets.UTF_8), run.stderr(),
            label + ": the pinned stderr");
        SidecarExpectations.ErrorExpectation captured =
            new SidecarExpectations.ErrorExpectation(tuple.code(), tuple.message(),
                relative, rebasedLine, tuple.column(),
                pinned.pinsOptional("expected")
                    ? java.util.Optional.ofNullable(tuple.expected())
                    : java.util.Optional.empty(),
                pinned.pinsOptional("actual")
                    ? java.util.Optional.ofNullable(tuple.actual())
                    : java.util.Optional.empty(),
                java.util.Optional.empty(), java.util.Optional.empty());
        String framing = ErrorSnapshot.CODE_LINE_PREFIX + tuple.code() + "\n"
            + ErrorSnapshot.SNAPSHOT_LINE_PREFIX
            + ErrorSnapshot.canonicalJson(captured) + "\n";
        checkEq(new String(executed.stdout(), StandardCharsets.UTF_8), framing,
            label + ": the sidecar stdout is the canonical framing of the captured "
                + "tuple");
    }

    /** The pinned runtime-ok leg of one staged-artifact run. */
    private static void assertPinnedRuntimeOk(String label,
            SidecarExpectations.StructuredExpectationSidecar sidecar, String backend,
            BoundedRun run) {
        SidecarExpectations.RuntimeExpectation leg = sidecar.expectationFor(backend);
        check(leg instanceof SidecarExpectations.RuntimeExpectation.Executed,
            label + ": the sidecar pins an executed leg");
        if (!(leg instanceof SidecarExpectations.RuntimeExpectation.Executed executed)) {
            return;
        }
        checkEq("runtime-ok", executed.mode(), label + ": the pinned mode");
        check(executed.error() == null, label + ": a runtime-ok leg pins no error "
            + "snapshot");
        check(run != null && run.captureClean(), label + ": the staged-artifact run "
            + "is capture-clean");
        if (run == null) {
            return;
        }
        checkEq(executed.exitCode(), run.exitCode(), label + ": the pinned exit code");
        checkEq(new String(executed.stdout(), StandardCharsets.UTF_8), run.stdout(),
            label + ": the pinned stdout");
        checkEq(new String(executed.stderr(), StandardCharsets.UTF_8), run.stderr(),
            label + ": the pinned stderr");
    }

    private static Map<String, byte[]> snapshotTree(Path root) throws Exception {
        Map<String, byte[]> snapshot = new LinkedHashMap<>();
        if (!Files.isDirectory(root)) {
            return snapshot;
        }
        try (var walk = Files.walk(root)) {
            for (Path file : walk.filter(Files::isRegularFile).sorted().toList()) {
                snapshot.put(root.relativize(file).toString()
                    .replace(File.separatorChar, '/'), Files.readAllBytes(file));
            }
        }
        return snapshot;
    }

    private static void checkTreeIdentical(Map<String, byte[]> before, Path root,
            String message) throws Exception {
        Map<String, byte[]> after = snapshotTree(root);
        checkEq(before.keySet(), after.keySet(), message + " (the file set)");
        for (Map.Entry<String, byte[]> entry : before.entrySet()) {
            check(java.util.Arrays.equals(entry.getValue(), after.get(entry.getKey())),
                message + " (" + entry.getKey() + ")");
        }
    }

    // =========================================================================
    // 10. The first-class bytes allocation: the indirect ladder on all three
    //     consumers
    // =========================================================================

    /** The first-class bytes allocation success program (K14's bytes member). */
    private static final String FIRST_CLASS_BYTES_ALLOCATE_SOURCE = """
        export function main(): null {
          let annotated: (x: int) => bytes = bytes
          let inferred = bytes
          let a: bytes = annotated(2)
          let b: bytes = inferred(3)
          let carried: ((x: int) => bytes)[] = [bytes]
          let c: bytes = carried[0](4)
          let alias: bytes = a
          a[0] = 7
          if (alias[0] !== 7 || a.length !== 2 || b.length !== 3 || c.length !== 4) {
            throw { code: "TEST_FAIL", message: "indirect allocation identity" }
          }
          if (a[1] !== 0 || b[2] !== 0 || c[3] !== 0) {
            throw { code: "TEST_FAIL", message: "indirect zero fill" }
          }
          return null
        }
        """;

    /** The negative arm: the indirect call's E8012 at the call expression. */
    private static final String FIRST_CLASS_BYTES_NEGATIVE_SOURCE = """
        export function main(): null {
          let g: (x: int) => bytes = bytes
          let bad: bytes = g(-1)
          return null
        }
        """;

    private static void testFirstClassBytesAllocation() throws Exception {
        System.out.println("-- the first-class bytes allocation: indirect zero fill, "
            + "identity, and the negative arm on all three consumers --");
        driveFirstClassBytesSuccess();
        driveFirstClassBytesNegative();
    }

    private static void driveFirstClassBytesSuccess() throws Exception {
        String label = "first-class bytes allocation";
        for (String backend : List.of("luajit", "jvm")) {
            ScratchProject project = materializeScratchProject(
                FIRST_CLASS_BYTES_ALLOCATE_SOURCE, backend);
            try {
                ManifestCompile compile = compileManifest(asManifestProject(project),
                    backend);
                check(compile.success(), label + " (" + backend + "): the "
                    + "manifest-authored compile succeeds: " + diagnosticsOf(compile));
                if (!compile.success()) {
                    continue;
                }
                if ("luajit".equals(backend)) {
                    SemanticRuntimeModel.ConsumerRun oracle =
                        runManifestOracle(compile.orchestrator());
                    check(oracle != null && oracle.terminal()
                            instanceof SemanticRuntimeModel.Terminal.Success,
                        label + ": the oracle runs the indirect allocations to "
                            + "success: " + (oracle == null ? "no lowering"
                                : oracle.terminal()));
                }
                executeScratchArtifact(project, backend, label, true, null);
            } finally {
                deleteRecursively(project.root());
            }
        }
    }

    private static void driveFirstClassBytesNegative() throws Exception {
        String label = "first-class bytes negative length";
        String sentinel = "g(-1)";
        for (String backend : List.of("luajit", "jvm")) {
            ScratchProject project = materializeScratchProject(
                FIRST_CLASS_BYTES_NEGATIVE_SOURCE, backend);
            try {
                ManifestCompile compile = compileManifest(asManifestProject(project),
                    backend);
                check(compile.success(), label + " (" + backend + "): the "
                    + "manifest-authored compile succeeds: " + diagnosticsOf(compile));
                if (!compile.success()) {
                    continue;
                }
                String expectedOrigin = project.entry().toAbsolutePath().toString()
                    + ":" + sentinelLine(FIRST_CLASS_BYTES_NEGATIVE_SOURCE, sentinel)
                    + ":" + sentinelColumn(FIRST_CLASS_BYTES_NEGATIVE_SOURCE, sentinel);
                if ("luajit".equals(backend)) {
                    SemanticRuntimeModel.ConsumerRun oracle =
                        runManifestOracle(compile.orchestrator());
                    boolean pinned = oracle != null && oracle.terminal()
                            instanceof SemanticRuntimeModel.Terminal.DealFailure failure
                        && "E8012".equals(failure.error().code())
                        && "bytes length must be non-negative".equals(
                            failure.error().message())
                        && expectedOrigin.equals(failure.error().origin());
                    check(pinned, label + ": the oracle projects the pinned E8012 at "
                        + "the indirect call expression: " + (oracle == null
                            ? "no lowering" : oracle.terminal()));
                }
                executeScratchArtifact(project, backend, label, false,
                    expectedOrigin);
            } finally {
                deleteRecursively(project.root());
            }
        }
    }

    private static int sentinelLine(String source, String sentinel) {
        String[] lines = source.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains(sentinel)) {
                return i + 1;
            }
        }
        throw new IllegalStateException("the sentinel '" + sentinel
            + "' is absent from the source");
    }

    private static int sentinelColumn(String source, String sentinel) {
        String[] lines = source.split("\n", -1);
        for (String line : lines) {
            int index = line.indexOf(sentinel);
            if (index >= 0) {
                return index + 1;
            }
        }
        throw new IllegalStateException("the sentinel '" + sentinel
            + "' is absent from the source");
    }

    /** The oracle's run of one manifest-authored compile over the closure. */
    private static SemanticRuntimeModel.ConsumerRun runManifestOracle(
            CompilationOrchestrator orchestrator) {
        CheckedProjectBuildResult checked = orchestrator.checkedProject();
        RequirementManifestResult manifests = orchestrator.requirementManifests();
        SemanticLowerer.ProjectLoweringResult result = SemanticLowerer.lowerProject(
            productionInvocation(), checked.input(), checked.index(),
            manifests.manifests(), orchestrator.hostDeclarationSurface(), Map.of(),
            Map.of(), BuiltinErrorDeclaration.synthesized(
                checked.input().modules().get(0).ast().span()),
            List.of(IntrinsicKind.INT_CONVERT, IntrinsicKind.NUMBER_CONVERT,
                IntrinsicKind.BYTES_NEW), Set.of());
        check(result.project() != null, "the manifest oracle lowering runs with zero "
            + "diagnostics: " + result.diagnostics());
        if (result.project() == null) {
            return null;
        }
        return SemanticOracle.executeProjectInits(result.project(), result.tables(),
            result.registries(), null);
    }

    /**
     * Executes the actual staged artifacts of one scratch project on the
     * named backend and asserts the exact status and streams plus the
     * transported DEAL tuple.
     */
    private static void executeScratchArtifact(ScratchProject project, String backend,
            String label, boolean expectSuccess, String expectedOrigin)
            throws Exception {
        if ("luajit".equals(backend)) {
            Path artifact = project.out().resolve(
                project.modulePath().replace('.', '/') + ".lua");
            check(Files.isRegularFile(artifact), label + " (" + backend + "): the "
                + "staged LuaJIT artifact exists: " + artifact);
            if (!Files.isRegularFile(artifact)) {
                return;
            }
            Path transport = project.out().resolve(ACCEPT_TRANSPORT);
            Path probe = project.out().resolve("__accept_probe.lua");
            Files.writeString(probe, luaAcceptanceProbe(
                artifact.toAbsolutePath().toString(), List.of(), transport),
                StandardCharsets.UTF_8);
            BoundedRun run = runBounded(List.of("luajit", "__accept_probe.lua"),
                project.out(), Map.of("DEAL_DEFER_MAIN", "1"));
            assertScratchRun(label + " (" + backend + ")", run,
                readAcceptanceTuple(transport), expectSuccess, expectedOrigin);
            return;
        }
        String className = JvmNames.classNameFor(project.modulePath());
        Path artifact = project.out().resolve(className + ".java");
        check(Files.isRegularFile(artifact), label + " (" + backend + "): the staged "
            + "JVM artifact exists: " + artifact);
        if (!Files.isRegularFile(artifact)) {
            return;
        }
        Path transport = project.out().resolve(ACCEPT_TRANSPORT);
        Files.writeString(project.out().resolve("Probe.java"),
            jvmAcceptanceProbe(className, project.modulePath(), List.of(), transport),
            StandardCharsets.UTF_8);
        Path classes = project.out().resolve("classes");
        Files.createDirectories(classes);
        BoundedRun compile = runJavac(project.out(), classes,
            List.of(artifact.toString(), "Probe.java"));
        check(compile.captureClean() && compile.exitCode() == 0, label + " ("
            + backend + "): the staged JVM artifact compiles with javac --release "
            + "25 -proc:none: " + compile.stdout() + compile.stderr());
        if (compile.exitCode() != 0) {
            return;
        }
        BoundedRun run = runBounded(List.of("java", "-cp",
            absoluteClasspath() + File.pathSeparator + classes, "Probe"),
            project.out(), Map.of());
        assertScratchRun(label + " (" + backend + ")", run,
            readAcceptanceTuple(transport), expectSuccess, expectedOrigin);
    }

    /** The scratch drives' exact status, streams, and transported tuple. */
    private static void assertScratchRun(String label, BoundedRun run,
            CapturedTuple tuple, boolean expectSuccess, String expectedOrigin) {
        check(run != null && run.captureClean(), label + ": the staged-artifact run "
            + "is capture-clean");
        if (run == null) {
            return;
        }
        if (expectSuccess) {
            checkEq(0, run.exitCode(), label + ": the staged artifact exits zero");
            checkEq("", run.stdout(), label + ": the staged artifact prints nothing "
                + "on stdout");
            checkEq("", run.stderr(), label + ": the staged artifact prints nothing "
                + "on stderr");
            check(tuple == null, label + ": the staged artifact projects no DEAL "
                + "error");
            return;
        }
        checkEq(1, run.exitCode(), label + ": the staged artifact exits one on the "
            + "pinned DEAL failure");
        checkEq("", run.stdout(), label + ": the probe prints nothing on stdout");
        checkEq("", run.stderr(), label + ": the probe prints nothing on stderr");
        check(tuple != null, label + ": the staged artifact transports the DEAL "
            + "failure tuple");
        if (tuple == null) {
            return;
        }
        checkEq("E8012", tuple.code(), label + ": the pinned code");
        checkEq("bytes length must be non-negative", tuple.message(),
            label + ": the pinned message");
        checkEq(expectedOrigin, tuple.file() + ":" + tuple.line() + ":"
            + tuple.column(), label + ": the pinned origin is the indirect call "
            + "expression");
        check(tuple.expected() == null && tuple.actual() == null,
            label + ": the allocation row pins no expected/actual tokens");
    }

    // =========================================================================
    // Entry point
    // =========================================================================

    public static void main(String[] args) throws Exception {
        testCorpusPins();
        testCorpusDrive();
        testReadShapeAndWriteChain();
        testBytesBoundaryContext();
        testDecodedArrayMark();
        testIndirectBytesAllocation();
        testFocusedRunnerContract();
        testOracleRealization();
        testFirstClassBytesAllocation();
        testManifestProductionAcceptance();
        System.out.println();
        System.out.println("BytesCoverageTest: " + passed + " passed, " + failed
            + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }
}
