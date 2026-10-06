package deal.test;

import deal.ast.ExportDeclaration;
import deal.ast.FunctionDeclaration;
import deal.ast.ProgramNode;
import deal.ast.StatementNode;
import deal.checker.BuiltinErrorDeclaration;
import deal.codegen.Backend;
import deal.codegen.jvm.JvmBackend;
import deal.diagnostics.CompilerDiagnostic;
import deal.ffi.FfiGeneratedModule;
import deal.identity.CanonicalModuleIdentity;
import deal.lexer.LexResult;
import deal.lexer.Lexer;
import deal.module.CompilationOrchestrator;
import deal.parser.ParseResult;
import deal.parser.Parser;
import deal.project.ExternalEntry;
import deal.project.ProjectContext;
import deal.project.ProjectLocator;
import deal.semantic.CheckedProjectBuildResult;
import deal.semantic.ClassRegistrationSeeds;
import deal.semantic.CompilerInvocation;
import deal.semantic.CompilerProfileProvider;
import deal.semantic.ReleaseConfiguration;
import deal.semantic.RequirementManifestResult;
import deal.semantic.SemanticLowerer;
import deal.semantic.SemanticOracle;
import deal.semantic.SemanticRuntimeModel;
import deal.semantic.ir.BoundaryKind;
import deal.semantic.ir.ClassFactoryRegistry;
import deal.semantic.ir.ClassId;
import deal.semantic.ir.ExecutableLoweredProject;
import deal.semantic.ir.FailureArmId;
import deal.semantic.ir.FailureContractRegistry;
import deal.semantic.ir.IntrinsicKind;
import deal.semantic.ir.KindPayload;
import deal.semantic.ir.LoweredModuleUnit;
import deal.semantic.ir.ModuleId;
import deal.semantic.ir.RuntimeDescriptor;
import deal.semantic.ir.SemanticOp;
import deal.semantic.ir.SemanticOpKind;
import deal.semantic.ir.SemanticProfile;
import deal.semantic.ir.StructuredBodyTable;
import deal.test.conformance.CorpusDiscovery;
import deal.test.conformance.CorpusFfi;
import deal.test.conformance.ErrorSnapshot;
import deal.test.conformance.SidecarExpectations;
import deal.test.conformance.SidecarSchemaValidator;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

public class BytesProductionDriveTest {

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

    // =========================================================================
    // The scope
    // =========================================================================

    private static final Path CONFORMANCE = Path.of("test", "conformance");
    private static final String BYTES_DIR = "backend-runtime/bytes";
    private static final String SOURCE_LOCATION_DIR = "backend-runtime/source-location";
    private static final String STDLIB_JSON_DIR = "backend-runtime/stdlib/json";
    private static final String FFI_DIR = "backend-runtime/ffi";
    private static final String FFI_BYTES =
        FFI_DIR + "/016-ffi-bytes-pointer-length";

    /**
     * The class-carrying host fixture of the externals-identity-convention
     * regression: its declared classes carry the canonical externals
     * identity the corpus host implementation tags.
     */
    private static final String CLASS_HOST_FIXTURE =
        "backend-runtime/host-abi/host-class-export";

    /** The target of one measured leg. */
    private enum Target {
        LUAJIT("luajit"),
        JVM("jvm");

        private final String laneName;

        Target(String laneName) {
            this.laneName = laneName;
        }

        String laneName() {
            return laneName;
        }
    }

    /**
     * The pinned inventory of the {@code backend-runtime/bytes} sidecar
     * fixtures (36 at this revision).
     */
    private static final List<String> BYTES_FIXTURES = List.of(
        "bytes-array-closure",
        "bytes-array-container-ops",
        "bytes-async-closure",
        "bytes-boundary-order",
        "bytes-buffer-ops",
        "bytes-class-default",
        "bytes-class-default-integration",
        "bytes-class-field-descriptor",
        "bytes-descriptor-boundary",
        "bytes-dynamic-async-function-mismatch-e8010",
        "bytes-dynamic-boundary-ok",
        "bytes-dynamic-function-mismatch-e8010",
        "bytes-dynamic-nested-first-element-e8003",
        "bytes-dynamic-nullable-function-ok",
        "bytes-dynamic-wrong-kind-e8001",
        "bytes-fn-adapter-e8010",
        "bytes-fn-adapters",
        "bytes-fn-xmod",
        "bytes-function-array-closure",
        "bytes-identity-equality",
        "bytes-index-bounds",
        "bytes-length",
        "bytes-module-identity",
        "bytes-negative-length-error",
        "bytes-negative-read-error",
        "bytes-nested-arrays",
        "bytes-nested-fn-shapes",
        "bytes-read-at-length-error",
        "bytes-sync-fn-shapes",
        "bytes-write-at-length-error",
        "bytes-write-negative-error",
        "bytes-write-range",
        "bytes-write-single-evaluation",
        "bytes-write-validation-order",
        "bytes-write-zero",
        "bytes-zero-length");

    /** The two dual-mechanism fixtures (delegated to RESIDUAL). */
    private static final List<String> DELEGATED = List.of(
        BYTES_DIR + "/bytes-boundary-order",
        BYTES_DIR + "/bytes-async-closure");

    /** The driven scope: 39 fixtures, 78 fixture-target legs. */
    private static final List<String> DRIVEN = drivenScope();

    private static List<String> drivenScope() {
        List<String> scope = new ArrayList<>();
        for (String name : BYTES_FIXTURES) {
            String path = BYTES_DIR + "/" + name;
            if (!DELEGATED.contains(path)) {
                scope.add(path);
            }
        }
        scope.add(SOURCE_LOCATION_DIR + "/bytes-index-bounds-source");
        scope.add(SOURCE_LOCATION_DIR + "/bytes-write-range-source");
        scope.add(STDLIB_JSON_DIR + "/json-stringify-bytes-error");
        scope.add(STDLIB_JSON_DIR + "/json-stringify-nested-bytes-error");
        scope.add(FFI_BYTES);
        return List.copyOf(scope);
    }

    /** The async-export fixture (driven through its recorded entry). */
    private static final String ASYNC_HOST_FIXTURE =
        BYTES_DIR + "/bytes-class-default-integration";

    /** The async test export of the host-importing integration fixture. */
    private static final String ASYNC_HOST_EXPORT =
        "test_bytes_class_default_integration";

    /** The three guard rule identifiers the compile criterion forbids. */
    private static final List<String> GUARD_RULES = List.of(
        "CONSTRUCT_UNLOWERED", "RETAINED_ABI_DEFERRED", "SHARED_EMITTER_COVERAGE");

    /** The pinned dispatched-corpus count (CD2's count invariant). */
    private static final int DISPATCHED_COUNT_PIN = 390;

    private static CompilerInvocation productionInvocation() {
        return CompilerProfileProvider.resolve(
            ReleaseConfiguration.CURRENT_RELEASE_STATE,
            ReleaseConfiguration.releaseCapabilityRegistry());
    }

    // =========================================================================
    // The measured record
    // =========================================================================

    /** One named field difference between a capture and the sidecar pin. */
    private record Divergence(String field, String captured, String pinned) {

        String describe() {
            return field + ": captured " + captured + " / pinned " + pinned;
        }
    }

    /** The projected capture of one artifact run. */
    private record Capture(String code, String message, String sourceFile,
                           Integer line, Integer column, String expected, String actual) {

        boolean error() {
            return code != null;
        }
    }

    /** One measured fixture-target leg. */
    private record Leg(Target target, boolean compiled, boolean compileReject,
                       List<String> compileDiagnostics, String artifact,
                       Capture capture, List<Divergence> divergences) {

        /**
         * The closed disposition of one leg: the pinned compile-reject leg
         * is pin-exact (it reproduces its pinned compile-reject outcome).
         */
        String outcome() {
            if (compileReject) {
                return "pin-exact";
            }
            if (!compiled) {
                return "compile-failure";
            }
            return divergences.isEmpty() ? "pin-exact" : "divergent";
        }
    }

    /** The oracle's terminal projection for one fixture. */
    private record OracleOutcome(boolean success, String code, String message,
                                 String sourceFile, Integer line, Integer column,
                                 String expected, String actual) {

        /** Whether this terminal agrees with one artifact's projected capture. */
        boolean agreesWith(Capture capture) {
            if (success) {
                return capture == null || !capture.error();
            }
            if (capture == null || !capture.error()) {
                return false;
            }
            return java.util.Objects.equals(code, capture.code())
                && java.util.Objects.equals(message, capture.message())
                && java.util.Objects.equals(sourceFile, capture.sourceFile())
                && java.util.Objects.equals(line, capture.line())
                && java.util.Objects.equals(column, capture.column())
                && java.util.Objects.equals(expected, capture.expected())
                && java.util.Objects.equals(actual, capture.actual());
        }
    }

    private static final Map<String, Map<Target, Leg>> RECORD = new LinkedHashMap<>();
    private static final Map<String, OracleOutcome> ORACLE_RECORD =
        new LinkedHashMap<>();
    private static final Map<String, Map<Target, Boolean>> ORACLE_AGREEMENT =
        new LinkedHashMap<>();
    private static final Map<String, String> DELEGATED_RECORD = new LinkedHashMap<>();

    // =========================================================================
    // 1. The corpus inventory
    // =========================================================================

    private static void testCorpusInventory() throws Exception {
        System.out.println("-- the bytes slate inventory: the pinned 36-fixture "
            + "bytes directory, the companions, and the FFI bytes child --");
        List<String> bytesStems = new ArrayList<>();
        List<String> sidecarStems = new ArrayList<>();
        try (Stream<Path> entries = Files.list(CONFORMANCE.resolve(BYTES_DIR))) {
            for (Path file : entries.sorted().toList()) {
                String name = file.getFileName().toString();
                if (name.endsWith(".deal")) {
                    bytesStems.add(name.substring(0,
                        name.length() - ".deal".length()));
                } else if (name.endsWith(".expect.json")) {
                    sidecarStems.add(name.substring(0,
                        name.length() - ".expect.json".length()));
                }
            }
        }
        bytesStems.sort(Comparator.naturalOrder());
        sidecarStems.sort(Comparator.naturalOrder());
        List<String> pinnedFixtures = new ArrayList<>(BYTES_FIXTURES);
        pinnedFixtures.add("bytes-fn-xmod-lib");
        pinnedFixtures.add("bytes_module_lib");
        pinnedFixtures.sort(Comparator.naturalOrder());
        checkEq(pinnedFixtures, bytesStems,
            "the bytes directory carries exactly the pinned fixture inventory "
                + "(no fixture or companion added or removed)");
        List<String> pinnedSidecars = new ArrayList<>(BYTES_FIXTURES);
        pinnedSidecars.sort(Comparator.naturalOrder());
        checkEq(pinnedSidecars, sidecarStems,
            "the bytes directory carries exactly one sidecar per pinned fixture");
        checkEq(39, DRIVEN.size(), "the driven scope is the 39-fixture scope");
        for (String path : DRIVEN) {
            check(Files.isRegularFile(CONFORMANCE.resolve(path + ".deal")),
                path + " is a corpus fixture");
            check(Files.isRegularFile(CONFORMANCE.resolve(path + ".expect.json")),
                path + " carries its sidecar");
        }
        for (String path : DELEGATED) {
            check(Files.isRegularFile(CONFORMANCE.resolve(path + ".deal")),
                path + " is a corpus fixture");
            check(Files.isRegularFile(CONFORMANCE.resolve(path + ".expect.json")),
                path + " carries its sidecar");
            check(!DRIVEN.contains(path), path + " is not part of the driven scope");
        }
    }

    // =========================================================================
    // 2. The lane-equivalent materialization
    // =========================================================================

    /** One materialized module of a temp project. */
    private record Materialized(String corpusPath, String source,
                                int headerLinesStripped) {
    }

    /** One lane-equivalent temp project. */
    private record Project(Path root, Path srcRoot, Path entryFile, String modulePath,
                           Map<String, Materialized> modules,
                           Map<String, String> rawToDeclaration,
                           List<String> hostNames, List<String> ffiImports,
                           Path outputRoot) {

        Materialized moduleOf(String capturedFile) {
            if (capturedFile == null) {
                return null;
            }
            Materialized direct = modules.get(capturedFile);
            if (direct != null) {
                return direct;
            }
            return modules.get(Path.of(capturedFile).toAbsolutePath().normalize()
                .toString());
        }

        Materialized entryModule() {
            return moduleOf(entryFile.toAbsolutePath().normalize().toString());
        }
    }

    /**
     * Materializes one fixture as the lane does: the fixture is the entry
     * module, its transitively imported companions sit at their
     * entry-directory-relative layout, the host declarations land under
     * {@code bindings/}, the corpus FFI declarations carry their corpus
     * {@code nativeLibrary} wiring, and one generated {@code deal.json}
     * wires the module root, the output, the target backend, and the
     * externals.
     */
    private static Project materialize(String fixtureRel, Target target)
            throws Exception {
        Path root = Files.createTempDirectory("bytes-prod-drive-");
        Path srcRoot = root.resolve("src");
        Files.createDirectories(srcRoot);
        Path bindings = root.resolve("bindings");
        Path entryCorpus = CONFORMANCE.resolve(fixtureRel + ".deal")
            .toAbsolutePath().normalize();
        Path entryDir = entryCorpus.getParent();

        Map<String, Materialized> modules = new LinkedHashMap<>();
        Map<String, String> rawToDeclaration = new LinkedHashMap<>();
        List<String> hostNames = new ArrayList<>();
        List<String> ffiImports = new ArrayList<>();

        // (1) The compilation set: the fixture plus every transitively
        // imported relative companion, materialized with the lane's
        // entry-directory-relative layout.
        for (String corpusPath : relativeClosureOf(
                CorpusDiscovery.slash(CONFORMANCE.toAbsolutePath().normalize()
                    .relativize(entryCorpus)))) {
            Path corpusFile = CONFORMANCE.resolve(corpusPath);
            String raw = Files.readString(corpusFile, StandardCharsets.UTF_8);
            String stripped = ConformanceHarnessMetadata
                .stripClassificationHeaders(raw);
            int headers = raw.split("\n", -1).length - stripped.split("\n", -1).length;
            Path targetPath = moduleTarget(corpusFile, entryDir, srcRoot);
            Files.createDirectories(targetPath.getParent());
            Files.writeString(targetPath, stripped, StandardCharsets.UTF_8);
            modules.put(targetPath.toAbsolutePath().normalize().toString(),
                new Materialized(corpusPath, stripped, headers));
        }
        String entryStem = moduleNameOf(entryCorpus.getFileName().toString());

        // (2) The declaration wiring: host declarations under bindings/,
        // the corpus FFI declarations with their nativeLibrary.
        for (Materialized module : modules.values()) {
            for (String hostName : hostImports(module.source())) {
                if (!hostNames.contains(hostName)) {
                    hostNames.add(hostName);
                }
            }
            for (String raw : CorpusDiscovery.ffiImportPaths(module.source())) {
                if (CorpusFfi.isFfiImport(CONFORMANCE, raw)
                        && !ffiImports.contains(raw)) {
                    ffiImports.add(raw);
                }
            }
        }
        Files.createDirectories(bindings);
        for (String hostName : hostNames) {
            Path declaration = Path.of("test", "conformance", "host-fixtures",
                hostName + ".d.deal");
            check(Files.isRegularFile(declaration), fixtureRel
                + ": the host declaration " + declaration + " exists");
            Files.writeString(bindings.resolve(hostName + ".d.deal"),
                ConformanceHarnessMetadata.stripClassificationHeaders(
                    Files.readString(declaration, StandardCharsets.UTF_8)),
                StandardCharsets.UTF_8);
            rawToDeclaration.put("host/" + hostName,
                "bindings/" + hostName + ".d.deal");
        }
        for (String raw : ffiImports) {
            CorpusFfi.Wiring wiring = CorpusFfi.wiringFor(CONFORMANCE, raw);
            Path declaration = CONFORMANCE.resolve(CorpusFfi.FFI_DIR)
                .resolve(wiring.declarationCorpusPath());
            check(Files.isRegularFile(declaration), fixtureRel
                + ": the corpus FFI declaration " + declaration + " exists");
            String fileName = "ffi_" + raw.replace('/', '_') + ".d.deal";
            Files.writeString(bindings.resolve(fileName),
                ConformanceHarnessMetadata.stripClassificationHeaders(
                    Files.readString(declaration, StandardCharsets.UTF_8)),
                StandardCharsets.UTF_8);
            rawToDeclaration.put(raw, "bindings/" + fileName);
        }

        // (3) One generated deal.json (the lane's rule).
        StringBuilder dealJson = new StringBuilder();
        dealJson.append("{\n  \"languageVersion\": \"1.2\",\n");
        dealJson.append("  \"moduleRoots\": [\"src\"],\n");
        dealJson.append("  \"output\": \"out\",\n");
        dealJson.append("  \"backend\": \"")
            .append(target == Target.JVM ? "jvm" : "luajit").append("\"");
        if (!rawToDeclaration.isEmpty()) {
            dealJson.append(",\n  \"externals\": {\n");
            boolean first = true;
            for (Map.Entry<String, String> entry : rawToDeclaration.entrySet()) {
                if (!first) {
                    dealJson.append(",\n");
                }
                first = false;
                dealJson.append("    \"").append(entry.getKey())
                    .append("\": { \"declaration\": \"")
                    .append(entry.getValue()).append("\"");
                CorpusFfi.Wiring wiring = CorpusFfi.wiringFor(CONFORMANCE,
                    entry.getKey());
                if (wiring != null) {
                    dealJson.append(", \"nativeLibrary\": \"")
                        .append(CorpusFfi.loaderTextFor(CONFORMANCE, wiring))
                        .append("\"");
                }
                dealJson.append(" }");
            }
            dealJson.append("\n  }");
        }
        dealJson.append("\n}\n");
        Files.writeString(root.resolve("deal.json"), dealJson);

        return new Project(root, srcRoot, srcRoot.resolve(entryStem + ".deal"),
            entryStem, modules, rawToDeclaration, hostNames, ffiImports,
            root.resolve("out"));
    }

    /**
     * The transitively imported relative companion closure of one
     * corpus-relative module path, dependency-first, cycle-guarded.
     */
    private static List<String> relativeClosureOf(String rootCorpusPath)
            throws Exception {
        List<String> ordered = new ArrayList<>();
        collectRelativeClosure(rootCorpusPath, ordered, new LinkedHashSet<>());
        return ordered;
    }

    private static void collectRelativeClosure(String corpusPath, List<String> ordered,
            Set<String> seen) throws Exception {
        if (!seen.add(corpusPath)) {
            return;
        }
        String stripped = ConformanceHarnessMetadata.stripClassificationHeaders(
            Files.readString(CONFORMANCE.resolve(corpusPath), StandardCharsets.UTF_8));
        for (String importPath : CorpusDiscovery.relativeImportPaths(stripped)) {
            String resolved = CorpusDiscovery.resolveRelativeImport(corpusPath,
                importPath, CONFORMANCE);
            if (resolved != null) {
                collectRelativeClosure(resolved, ordered, seen);
            }
        }
        ordered.add(corpusPath);
    }

    /** The lane's entry-directory-relative module target. */
    private static Path moduleTarget(Path corpusFile, Path entryDir, Path srcRoot) {
        Path rel;
        try {
            rel = entryDir.relativize(corpusFile);
        } catch (IllegalArgumentException e) {
            return srcRoot.resolve(moduleNameOf(
                corpusFile.getFileName().toString()) + ".deal");
        }
        if (rel.startsWith("..") || rel.getNameCount() <= 1) {
            return srcRoot.resolve(moduleNameOf(
                corpusFile.getFileName().toString()) + ".deal");
        }
        return srcRoot.resolve(rel);
    }

    private static String moduleNameOf(String fileName) {
        if (fileName.endsWith(".d.deal")) {
            return fileName.substring(0, fileName.length() - ".d.deal".length());
        }
        if (fileName.endsWith(".deal")) {
            return fileName.substring(0, fileName.length() - ".deal".length());
        }
        return fileName;
    }

    /** Bare {@code host/<name>} import names of one source, in order. */
    private static List<String> hostImports(String source) {
        List<String> hosts = new ArrayList<>();
        for (String path : CorpusDiscovery.ffiImportPaths(source)) {
            if (path.startsWith("host/")
                    && !CorpusFfi.isFfiImport(CONFORMANCE, path)) {
                hosts.add(path.substring("host/".length()));
            }
        }
        return hosts;
    }

    // =========================================================================
    // 3. The production compile and the deferred-entry drive
    // =========================================================================

    /** Compiles one materialized project through the release-owned arm. */
    private static boolean compile(Project project, String fixtureRel, Target target,
            List<String> diagnosticsOut) throws Exception {
        ProjectLocator.LocateResult located = ProjectLocator.locate(
            project.entryFile().toString(), null);
        if (located.context() == null) {
            check(false, fixtureRel + " [" + target.laneName()
                + "]: the generated deal.json locates strictly");
            return false;
        }
        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            withDottedHostIdentities(located.context(), project),
            project.entryFile(), false, false, false, false,
            null, productionInvocation());
        boolean compiled = orchestrator.compile();
        for (CompilerDiagnostic diagnostic : orchestrator.diagnostics()) {
            if ("error".equals(diagnostic.severity())) {
                diagnosticsOut.add(diagnostic.code() + " " + diagnostic.message()
                    + " at " + diagnostic.range().file() + ":"
                    + diagnostic.line() + ":" + diagnostic.column());
            }
        }
        return compiled;
    }

    /**
     * The corpus conformance externals-identity convention (the JVM
     * lane's rule): a host module's class identities project through its
     * dotted typing name ({@code @$external/host.cfg/...}), while import
     * resolution keys on the raw specifier ({@code host/cfg}) the
     * generated {@code deal.json} wires. The manifest carries the single
     * valid raw-key entry (ProjectLocator rejects two externals entries
     * declaring the same file); the compile context is rebuilt with the
     * dotted entry inserted first, the documented first-entry rule of
     * ModuleIdentityResolver's file-keyed externals classification, so
     * the declaration module classifies under the dotted identity the
     * lanes and the corpus host implementations use.
     */
    private static ProjectContext withDottedHostIdentities(ProjectContext context,
            Project project) {
        if (project.hostNames().isEmpty()) {
            return context;
        }
        Map<String, ExternalEntry> externals = new LinkedHashMap<>();
        for (String hostName : project.hostNames()) {
            String rawKey = "host/" + hostName;
            ExternalEntry rawEntry = context.externals().get(rawKey);
            if (rawEntry == null) {
                continue;
            }
            String dottedKey = "host." + hostName;
            externals.put(dottedKey, new ExternalEntry(dottedKey,
                rawEntry.declarationPath(), rawEntry.nativeLibrary(),
                rawEntry.sourceRange()));
            externals.put(rawKey, rawEntry);
        }
        for (Map.Entry<String, ExternalEntry> entry
                : context.externals().entrySet()) {
            externals.putIfAbsent(entry.getKey(), entry.getValue());
        }
        return new ProjectContext(context.manifestPath(), context.projectRoot(),
            context.manifestDirectory(), context.languageVersion(),
            context.configuredModuleRoots(), context.outputPath(),
            context.backend(), externals, context.stdlibVersion(),
            context.stdlibSurfacePath(), context.stdlibDeclarationFiles(),
            context.projectDeploymentIdentity());
    }

    /** The emitted artifact path of one compiled project and target. */
    private static Path artifactOf(Project project, Target target) {
        String name = target == Target.JVM
            ? JvmBackend.classNameFor(project.modulePath()) + ".java"
            : project.modulePath().replace('.', '/') + ".lua";
        return project.outputRoot().resolve(name);
    }

    /** One executed artifact's raw outcome. */
    private record Execution(int exitCode, String stdout, String stderr,
                             Capture capture, boolean probeDefect) {
    }

    /** The ordered zero-arity exports of the entry module. */
    private record Export(String name, boolean async, int index) {
    }

    private static List<Export> exportsOf(String source, String file) {
        LexResult lex = new Lexer(source, file).tokenize();
        if (lex.hasErrors()) {
            throw new IllegalStateException("entry lex errors: " + lex.diagnostics());
        }
        ParseResult parse = new Parser(lex.tokens(), file,
            SemanticProfile.DEAL_V1_2_INT32, lex.directiveEvents()).parse();
        if (parse.hasErrors()) {
            throw new IllegalStateException("entry parse errors: "
                + parse.diagnostics());
        }
        ProgramNode program = parse.program();
        List<Export> exports = new ArrayList<>();
        int index = 0;
        for (StatementNode statement : program.statements()) {
            if (statement instanceof ExportDeclaration exp
                    && exp.declaration() instanceof FunctionDeclaration fn) {
                if (fn.params().isEmpty() && !fn.name().contains("$")) {
                    exports.add(new Export(fn.name(), fn.isAsync(), index++));
                }
            }
        }
        return exports;
    }

    /** Drives one staged artifact with the deferred-entry probe. */
    private static Execution execute(Project project, List<Export> exports,
            Long asyncEntryId, Target target) throws Exception {
        return target == Target.LUAJIT
            ? executeLua(project, exports)
            : executeJvm(project, exports, asyncEntryId);
    }

    private static final String TRANSPORT_FILE = "__probe_transport.txt";

    private static Execution executeLua(Project project, List<Export> exports)
            throws Exception {
        Path out = project.outputRoot();
        Path artifact = artifactOf(project, Target.LUAJIT);
        // The deployed corpus host implementations (the lane's triplet rule):
        // the fixture importing host/<name> loads its Lua module at the
        // emitted host load site.
        for (String hostName : project.hostNames()) {
            Path hostLua = Path.of("test", "conformance", "host-fixtures",
                hostName + ".lua");
            check(Files.isRegularFile(hostLua), project.modulePath()
                + ": the LuaJIT host implementation " + hostLua + " exists");
            Path target = out.resolve("host").resolve(hostName + ".lua");
            Files.createDirectories(target.getParent());
            Files.copy(hostLua, target);
        }
        Path transport = out.resolve(TRANSPORT_FILE);
        Files.deleteIfExists(transport);
        Path probe = out.resolve("__probe.lua");
        Files.writeString(probe, luaProbe(project, exports, artifact, transport),
            StandardCharsets.UTF_8);
        ProcessBuilder builder = new ProcessBuilder("luajit",
            probe.toAbsolutePath().toString());
        builder.directory(out.toFile());
        builder.environment().put("DEAL_DEFER_MAIN", "1");
        Process process = builder.start();
        String stdout = new String(process.getInputStream().readAllBytes(),
            StandardCharsets.UTF_8);
        String stderr = new String(process.getErrorStream().readAllBytes(),
            StandardCharsets.UTF_8);
        int exit = process.waitFor();
        Capture capture = readTransport(transport);
        boolean defect = capture != null && "PROBE_DEFECT".equals(capture.code());
        return new Execution(exit, stdout, stderr, capture, defect);
    }

    /** The LuaJIT deferred-entry probe source. */
    private static String luaProbe(Project project, List<Export> exports,
            Path artifact, Path transport) {
        StringBuilder probe = new StringBuilder();
        probe.append("local function __escape(s)\n");
        probe.append("  s = tostring(s)\n");
        probe.append("  s = string.gsub(s, \"\\\\\", \"\\\\\\\\\")\n");
        probe.append("  s = string.gsub(s, \"\\n\", \"\\\\n\")\n");
        probe.append("  s = string.gsub(s, \"\\t\", \"\\\\t\")\n");
        probe.append("  return s\n");
        probe.append("end\n");
        probe.append("local function __transport(fields)\n");
        probe.append("  local f = io.open(\"").append(transport).append("\", \"w\")\n");
        probe.append("  if not f then os.exit(3) end\n");
        probe.append("  for _, kv in ipairs(fields) do\n");
        probe.append("    f:write(kv[1], \"\\t\", __escape(kv[2] or \"\"), \"\\n\")\n");
        probe.append("  end\n");
        probe.append("  f:close()\n");
        probe.append("end\n");
        probe.append("local function __fields(err)\n");
        probe.append("  if type(err) ~= \"table\" then\n");
        probe.append("    return { {\"code\", \"NON_DEAL\"},"
            + " {\"message\", tostring(err)} }\n");
        probe.append("  end\n");
        probe.append("  local file, line, column = err.file, err.line, err.column\n");
        probe.append("  if err.o ~= nil then\n");
        probe.append("    local f, l, c = tostring(err.o)"
            + ":match(\"^(.*):(%d+):(%d+)$\")\n");
        probe.append("    file, line, column = f, l, c\n");
        probe.append("  end\n");
        probe.append("  return { {\"code\", err.code},"
            + " {\"message\", err.message or err.m},\n");
        probe.append("    {\"file\", file}, {\"line\", line}, {\"column\", column},\n");
        probe.append("    {\"expected\", err.e}, {\"actual\", err.a} }\n");
        probe.append("end\n");
        probe.append("local function __fail(err)\n");
        probe.append("  __transport(__fields(err))\n");
        probe.append("  os.exit(1)\n");
        probe.append("end\n");
        probe.append("local function __probeDefect(detail)\n");
        probe.append("  __transport({ {\"code\", \"PROBE_DEFECT\"},"
            + " {\"message\", detail} })\n");
        probe.append("  os.exit(1)\n");
        probe.append("end\n");
        probe.append("dofile(\"").append(artifact.toAbsolutePath().normalize())
            .append("\")\n");
        probe.append("local __ok, __err = __dealMain()\n");
        probe.append("if not __ok then __fail(__err) end\n");
        for (Export export : exports) {
            if ("main".equals(export.name())) {
                continue;
            }
            if (export.async()) {
                probe.append("local __okA").append(export.index())
                    .append(", __errA").append(export.index())
                    .append(" = pcall(__asyncEntries[\"")
                    .append(project.modulePath()).append("#")
                    .append(export.name()).append("\"], \"-\", true)\n");
                probe.append("if not __okA").append(export.index())
                    .append(" then __fail(__errA").append(export.index())
                    .append(") end\n");
            } else {
                probe.append("local __v").append(export.index())
                    .append(" = __exportSurfaces[\"").append(project.modulePath())
                    .append("\"][\"").append(export.name()).append("\"]\n");
                probe.append("if type(__v").append(export.index())
                    .append(") ~= \"table\" or type(__v")
                    .append(export.index())
                    .append(".f) ~= \"function\" then __probeDefect(\"the entry"
                        + " surface publishes no function for ")
                    .append(export.name()).append("\") end\n");
                probe.append("local __okS").append(export.index())
                    .append(", __errS").append(export.index())
                    .append(" = pcall(__v").append(export.index())
                    .append(".f)\n");
                probe.append("if not __okS").append(export.index())
                    .append(" then __fail(__errS").append(export.index())
                    .append(") end\n");
            }
        }
        probe.append("os.exit(0)\n");
        return probe.toString();
    }

    private static Execution executeJvm(Project project, List<Export> exports,
            Long asyncEntryId) throws Exception {
        Path out = project.outputRoot();
        Path artifact = artifactOf(project, Target.JVM);
        Path transport = out.resolve(TRANSPORT_FILE);
        Files.deleteIfExists(transport);
        String className = JvmBackend.classNameFor(project.modulePath());
        Path classes = out.resolve("classes");
        Files.createDirectories(classes);
        String classpath = absoluteClasspath();
        List<String> sources = new ArrayList<>();
        sources.add(artifact.toString());
        for (String hostName : project.hostNames()) {
            Path hostFile = Path.of("test", "conformance", "host-fixtures",
                hostName + ".java");
            check(Files.isRegularFile(hostFile), project.modulePath()
                + ": the JVM host implementation " + hostFile + " exists");
            String hostClass = JvmBackend.classNameFor("host/" + hostName);
            Path target = out.resolve(hostClass + ".java");
            Files.copy(hostFile, target);
            sources.add(target.toString());
        }
        Files.writeString(out.resolve("Probe.java"),
            jvmProbe(project, exports, asyncEntryId, transport),
            StandardCharsets.UTF_8);
        sources.add(out.resolve("Probe.java").toString());
        ProcessBuilder javac = new ProcessBuilder();
        javac.command().add("javac");
        javac.command().add("--release");
        javac.command().add("25");
        javac.command().add("-proc:none");
        javac.command().add("-cp");
        javac.command().add(classpath);
        javac.command().add("-d");
        javac.command().add(classes.toString());
        javac.command().addAll(sources);
        javac.directory(out.toFile());
        javac.redirectErrorStream(true);
        Process compile = javac.start();
        String compileOut = new String(compile.getInputStream().readAllBytes(),
            StandardCharsets.UTF_8);
        int compileExit = compile.waitFor();
        checkEq(0, compileExit, project.modulePath() + " [jvm]: the emitted "
            + "production artifact compiles with javac --release 25 -proc:none: "
            + compileOut);
        if (compileExit != 0) {
            return new Execution(-1, "", compileOut, null, false);
        }
        ProcessBuilder run = new ProcessBuilder("java", "-cp",
            classpath + File.pathSeparator + classes, "Probe");
        run.directory(out.toFile());
        Process process = run.start();
        String stdout = new String(process.getInputStream().readAllBytes(),
            StandardCharsets.UTF_8);
        String stderr = new String(process.getErrorStream().readAllBytes(),
            StandardCharsets.UTF_8);
        int exit = process.waitFor();
        Capture capture = readTransport(transport);
        boolean defect = capture != null && "PROBE_DEFECT".equals(capture.code());
        return new Execution(exit, stdout, stderr, capture, defect);
    }

    /** The JVM deferred-entry probe source. */
    private static String jvmProbe(Project project, List<Export> exports,
            Long asyncEntryId, Path transport) {
        String className = JvmBackend.classNameFor(project.modulePath());
        StringBuilder probe = new StringBuilder();
        probe.append("public final class Probe {\n");
        probe.append("  static final String TRANSPORT = \"")
            .append(transport.toString().replace("\\", "\\\\")).append("\";\n");
        probe.append("  public static void main(String[] args) {\n");
        probe.append("    try {\n");
        probe.append("      ").append(className).append(".dealMain();\n");
        for (Export export : exports) {
            if ("main".equals(export.name())) {
                continue;
            }
            if (export.async()) {
                probe.append("      ").append(className).append(".ae")
                    .append(asyncEntryId == null ? "MISSING" : asyncEntryId)
                    .append("(\"-\", true, new Object[]{});\n");
            } else {
                probe.append("      {\n");
                probe.append("        Object v = deal.codegen.jvm.JvmRuntime")
                    .append(".exportSurface(\"").append(project.modulePath())
                    .append("\").read(\"").append(export.name()).append("\");\n");
                probe.append("        if (!(v instanceof deal.codegen.jvm")
                    .append(".JvmRuntime.FunctionValue)) {\n");
                probe.append("          transport(new IllegalStateException("
                    + "\"PROBE_DEFECT: the entry surface publishes no function "
                    + "for ").append(export.name()).append("\"));\n");
                probe.append("          return;\n");
                probe.append("        }\n");
                probe.append("        ((deal.codegen.jvm.JvmRuntime.FunctionValue) v)")
                    .append(".fn.invoke(new Object[]{});\n");
                probe.append("      }\n");
            }
        }
        probe.append("      System.exit(0);\n");
        probe.append("    } catch (Throwable error) {\n");
        probe.append("      transport(error);\n");
        probe.append("    }\n");
        probe.append("  }\n");
        probe.append("  static void transport(Throwable error) {\n");
        probe.append("    Throwable e = error;\n");
        probe.append("    while (e != null && !(e instanceof deal.codegen.jvm")
            .append(".JvmRuntime.DealError) && e.getCause() != null) {\n");
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
        probe.append("      java.nio.file.Files.writeString("
            + "java.nio.file.Path.of(TRANSPORT), out.toString());\n");
        probe.append("    } catch (java.io.IOException ignored) {\n");
        probe.append("      // best effort; the drive reports the missing capture\n");
        probe.append("    }\n");
        probe.append("    System.exit(1);\n");
        probe.append("  }\n");
        probe.append("  static void append(StringBuilder out, String key,"
            + " String value) {\n");
        probe.append("    if (value == null) { return; }\n");
        probe.append("    String escaped = value.replace(\"\\\\\", \"\\\\\\\\\")"
            + ".replace(\"\\n\", \"\\\\n\").replace(\"\\t\", \"\\\\t\");\n");
        probe.append("    out.append(key).append('\\t').append(escaped)"
            + ".append('\\n');\n");
        probe.append("  }\n");
        probe.append("}\n");
        return probe.toString();
    }

    /** Reads one transport file into the projected capture. */
    private static Capture readTransport(Path transport) throws Exception {
        if (!Files.isRegularFile(transport)) {
            return null;
        }
        Map<String, String> fields = new LinkedHashMap<>();
        for (String line : Files.readString(transport, StandardCharsets.UTF_8)
                .split("\n", -1)) {
            if (line.isEmpty()) {
                continue;
            }
            int tab = line.indexOf('\t');
            if (tab < 0) {
                continue;
            }
            fields.put(line.substring(0, tab), unescape(line.substring(tab + 1)));
        }
        if (fields.isEmpty()) {
            return null;
        }
        String origin = fields.get("origin");
        String file = fields.get("file");
        Integer line = null;
        Integer column = null;
        if (origin != null) {
            int lastColon = origin.lastIndexOf(':');
            int prevColon = origin.lastIndexOf(':', lastColon - 1);
            if (prevColon > 0 && lastColon > prevColon) {
                file = origin.substring(0, prevColon);
                line = Integer.valueOf(origin.substring(prevColon + 1, lastColon));
                column = Integer.valueOf(origin.substring(lastColon + 1));
            }
        } else {
            String lineText = fields.get("line");
            String columnText = fields.get("column");
            line = lineText == null || lineText.isEmpty() ? null
                : Integer.valueOf(lineText);
            column = columnText == null || columnText.isEmpty() ? null
                : Integer.valueOf(columnText);
        }
        return new Capture(fields.get("code"), fields.get("message"), file, line,
            column, emptyToNull(fields.get("expected")),
            emptyToNull(fields.get("actual")));
    }

    /** The absent-field projection of one transported value. */
    private static String emptyToNull(String text) {
        return text == null || text.isEmpty() ? null : text;
    }

    private static String unescape(String text) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < text.length()) {
                char next = text.charAt(++i);
                out.append(switch (next) {
                    case 'n' -> '\n';
                    case 't' -> '\t';
                    case '\\' -> '\\';
                    default -> next;
                });
            } else {
                out.append(c);
            }
        }
        return out.toString();
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

    // =========================================================================
    // 4. The comparison
    // =========================================================================

    /**
     * The sidecar-authoritative comparison: the mandatory fields always, the
     * span group only when pinned, the optional fields only when pinned,
     * unpinned fields suppressed, nothing fabricated. The capture is the
     * normalized projection (corpus-relative source file, raw corpus line).
     */
    private static List<Divergence> compare(Capture capture,
            SidecarExpectations.RuntimeExpectation.Executed pinned,
            Execution execution) {
        List<Divergence> divergences = new ArrayList<>();
        if (pinned.isRuntimeError()) {
            SidecarExpectations.ErrorExpectation row = pinned.error();
            if (capture == null || !capture.error()) {
                divergences.add(new Divergence("code",
                    String.valueOf(capture == null ? null : capture.code()),
                    row.code()));
                return divergences;
            }
            add(divergences, "code", capture.code(), row.code());
            add(divergences, "message", capture.message(), row.message());
            if (row.pinsSpan()) {
                add(divergences, "sourceFile", capture.sourceFile(), row.sourceFile());
                add(divergences, "line", String.valueOf(capture.line()),
                    String.valueOf(row.line()));
                add(divergences, "column", String.valueOf(capture.column()),
                    String.valueOf(row.column()));
            }
            if (row.expected().isPresent()) {
                add(divergences, "expected", capture.expected(), row.expected().get());
            }
            if (row.actual().isPresent()) {
                add(divergences, "actual", capture.actual(), row.actual().get());
            }
            return divergences;
        }
        if (capture != null && capture.error()) {
            divergences.add(new Divergence("code", capture.code(), null));
            return divergences;
        }
        if (execution.exitCode() != pinned.exitCode()) {
            divergences.add(new Divergence("exitCode",
                String.valueOf(execution.exitCode()),
                String.valueOf(pinned.exitCode())));
        }
        if (!execution.stdout().equals(
                new String(pinned.stdout(), StandardCharsets.UTF_8))) {
            divergences.add(new Divergence("stdout", execution.stdout(),
                new String(pinned.stdout(), StandardCharsets.UTF_8)));
        }
        if (!execution.stderr().equals(
                new String(pinned.stderr(), StandardCharsets.UTF_8))) {
            divergences.add(new Divergence("stderr", execution.stderr(),
                new String(pinned.stderr(), StandardCharsets.UTF_8)));
        }
        return divergences;
    }

    /** The normalized projection of one captured artifact run. */
    private static Capture normalize(Project project, Capture capture) {
        if (capture == null) {
            return null;
        }
        return new Capture(capture.code(), capture.message(),
            corpusFile(project, capture.sourceFile()),
            rebasedLine(project, capture), capture.column(), capture.expected(),
            capture.actual());
    }

    /**
     * The lane's canonical error framing of one capture under the pin's
     * suppression rule (the byte-exact sidecar transcript the comparison
     * reproduces).
     */
    private static String framedSnapshot(Capture capture,
            SidecarExpectations.ErrorExpectation pinned) {
        SidecarExpectations.ErrorExpectation snapshot =
            new SidecarExpectations.ErrorExpectation(capture.code(),
                capture.message(),
                pinned.pinsSpan() ? capture.sourceFile() : null,
                pinned.pinsSpan() ? capture.line() : null,
                pinned.pinsSpan() ? capture.column() : null,
                pinned.expected().isPresent()
                    ? Optional.ofNullable(capture.expected()) : Optional.empty(),
                pinned.actual().isPresent()
                    ? Optional.ofNullable(capture.actual()) : Optional.empty(),
                Optional.empty(), Optional.empty());
        return ErrorSnapshot.CODE_LINE_PREFIX + capture.code() + "\n"
            + ErrorSnapshot.SNAPSHOT_LINE_PREFIX
            + ErrorSnapshot.canonicalJson(snapshot) + "\n";
    }

    private static void add(List<Divergence> divergences, String field,
            String captured, String pinned) {
        if (!java.util.Objects.equals(captured, pinned)) {
            divergences.add(new Divergence(field, captured, pinned));
        }
    }

    /** The corpus-relative form of one captured origin file. */
    private static String corpusFile(Project project, String capturedFile) {
        Materialized module = project.moduleOf(capturedFile);
        return module != null ? module.corpusPath() : capturedFile;
    }

    /** The raw corpus line of one captured module-relative line. */
    private static Integer rebasedLine(Project project, Capture capture) {
        if (capture == null || capture.line() == null) {
            return null;
        }
        Materialized module = project.moduleOf(capture.sourceFile());
        return module == null ? capture.line()
            : capture.line() + module.headerLinesStripped();
    }

    /** The sidecar's pinned LuaJIT-executed expectation of one fixture. */
    private static SidecarExpectations.RuntimeExpectation.Executed pinnedOf(
            String fixtureRel) throws Exception {
        SidecarExpectations.StructuredExpectationSidecar sidecar =
            SidecarExpectations.StructuredExpectationSidecar.parse(
                Files.readString(CONFORMANCE.resolve(fixtureRel + ".expect.json"),
                    StandardCharsets.UTF_8));
        SidecarExpectations.RuntimeExpectation expectation =
            sidecar.expectationFor("luajit");
        if (!(expectation
                instanceof SidecarExpectations.RuntimeExpectation.Executed executed)) {
            throw new IllegalStateException(fixtureRel + " is not executed");
        }
        return executed;
    }

    // =========================================================================
    // 5. The oracle legs
    // =========================================================================

    /** One manually compiled and lowered closure (the oracle leg). */
    private record Lowered(ExecutableLoweredProject project,
                           Map<ModuleId, StructuredBodyTable> tables,
                           Map<ModuleId, ClassFactoryRegistry> registries,
                           ClassRegistrationSeeds seeds,
                           ModuleId entryModule, LoweredModuleUnit entryUnit) {
    }

    /**
     * Compiles and lowers one project through the release-owned production
     * invocation: the test-only context constructor synthesizes the same
     * module-root/externals surface the lane-equivalent {@code deal.json}
     * carries, so the oracle runs the very same closure.
     */
    private static Lowered lower(String label, Project project, Path entry,
            Path outputRoot) throws Exception {
        Map<String, String> externals = new LinkedHashMap<>();
        for (String hostName : project.hostNames()) {
            String registration = project.rawToDeclaration().get("host/" + hostName);
            if (registration != null) {
                externals.put("host." + hostName, project.root()
                    .resolve(registration).toAbsolutePath().normalize().toString());
            }
        }
        for (Map.Entry<String, String> registration
                : project.rawToDeclaration().entrySet()) {
            externals.putIfAbsent(registration.getKey(),
                project.root().resolve(registration.getValue())
                    .toAbsolutePath().normalize().toString());
        }
        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            entry.toAbsolutePath(), outputRoot, false, false, false, false,
            Backend.LUAJIT, externals, List.of(project.srcRoot()),
            Path.of("std").toAbsolutePath().normalize(), null,
            productionInvocation());
        boolean compiled = orchestrator.compile();
        CheckedProjectBuildResult built = orchestrator.checkedProject();
        RequirementManifestResult manifests = orchestrator.requirementManifests();
        check(compiled && built != null && built.input() != null
                && built.index() != null && !built.hasErrors() && manifests != null
                && manifests.manifests() != null
                && orchestrator.hostDeclarationSurface() != null,
            label + ": the oracle closure compiles: "
                + (built == null ? "no checked project" : built.diagnostics()));
        if (!compiled || built == null || built.input() == null
                || built.index() == null || built.hasErrors() || manifests == null
                || manifests.manifests() == null
                || orchestrator.hostDeclarationSurface() == null) {
            return null;
        }
        Map<ModuleId, CanonicalModuleIdentity> identities = new LinkedHashMap<>();
        Map<ModuleId, FfiGeneratedModule> externC = new LinkedHashMap<>();
        for (ModuleId declaration
                : orchestrator.hostDeclarationSurface().moduleIds()) {
            String raw = rawSpecifierOf(project, declaration.path());
            String identity = declarationIdentityOf(raw);
            identities.put(declaration,
                new CanonicalModuleIdentity.ExternalModule(identity));
            if (CorpusFfi.isFfiImport(CONFORMANCE, raw)) {
                CorpusFfi.Module module = CorpusFfi.module(CONFORMANCE, raw,
                    SemanticProfile.DEAL_V1_2_INT32);
                if (module.generatedModule() != null) {
                    externC.put(declaration, module.generatedModule());
                }
            }
        }
        SemanticLowerer.ProjectLoweringResult result = SemanticLowerer.lowerProject(
            productionInvocation(), built.input(), built.index(),
            manifests.manifests(), orchestrator.hostDeclarationSurface(),
            identities, externC,
            BuiltinErrorDeclaration.synthesized(
                built.input().modules().get(0).ast().span()),
            List.of(IntrinsicKind.INT_CONVERT, IntrinsicKind.NUMBER_CONVERT),
            Set.of());
        check(result.project() != null, label + ": the oracle closure lowers "
            + "with zero diagnostics: " + result.diagnostics());
        if (result.project() == null) {
            return null;
        }
        ModuleId entryModule = result.project().entryModule();
        return new Lowered(result.project(), result.tables(), result.registries(),
            result.seeds(), entryModule,
            result.project().modules().get(entryModule));
    }

    /**
     * Captures the oracle terminal for one fixture. The sync fixtures run
     * both oracle shapes over the very same closure and prefer the failure
     * in the artifact drive's order: the fixture as the entry module (the
     * entry delegation owns {@code main}) first, then a driver entry that
     * invokes the entry module's ordered zero-arity exports exactly as the
     * artifact probe's ordered loop does. The host-importing async fixture
     * runs through its own recorded async entry with the bytes-aware host
     * responder.
     */
    private static OracleOutcome oracleLeg(String fixtureRel, Project project,
            List<Export> exports, Lowered laneLowering) throws Exception {
        boolean asyncFixture = ASYNC_HOST_FIXTURE.equals(fixtureRel);
        if (asyncFixture) {
            if (laneLowering == null) {
                return null;
            }
            SemanticRuntimeModel.ConsumerRun run = SemanticOracle.invokeAsyncEntry(
                laneLowering.project(), laneLowering.tables(), new HostState(),
                laneLowering.entryModule(), ASYNC_HOST_EXPORT, List.of());
            return oracleOutcome(project, run);
        }
        // (a) The fixture as the entry module: the entry delegation runs
        // main exactly as the artifacts' deferred module-init walk does.
        Lowered entryLowering = laneLowering != null ? laneLowering
            : lower(fixtureRel + " (oracle entry)", project, project.entryFile(),
                project.root().resolve("out-oracle-entry"));
        if (entryLowering == null) {
            return null;
        }
        OracleOutcome main = oracleOutcome(project,
            SemanticOracle.executeProjectInits(entryLowering.project(),
                entryLowering.tables(), entryLowering.registries(),
                new HostState()));
        if (main != null && !main.success()) {
            return main;
        }
        // (b) The driver entry: the same ordered zero-arity export loop the
        // artifact probe runs after the module-init walk.
        Path entry = project.srcRoot().resolve("__oracle_drive.deal");
        Files.writeString(entry, oracleDriver(project, exports),
            StandardCharsets.UTF_8);
        Lowered shim = lower(fixtureRel + " (oracle exports)", project, entry,
            project.root().resolve("out-oracle"));
        if (shim == null) {
            return null;
        }
        OracleOutcome exportOutcome = oracleOutcome(project,
            SemanticOracle.executeProjectInits(shim.project(), shim.tables(),
                shim.registries(), new HostState()));
        if (exportOutcome != null && !exportOutcome.success()) {
            return exportOutcome;
        }
        return main;
    }

    /** Projects one oracle consumer run onto the terminal tuple. */
    private static OracleOutcome oracleOutcome(Project project,
            SemanticRuntimeModel.ConsumerRun run) {
        if (run.terminal() instanceof SemanticRuntimeModel.Terminal.Success) {
            return new OracleOutcome(true, null, null, null, null, null, null, null);
        }
        SemanticRuntimeModel.ErrorSnapshot error =
            ((SemanticRuntimeModel.Terminal.DealFailure) run.terminal()).error();
        String file = null;
        Integer line = null;
        Integer column = null;
        if (error.origin() != null) {
            int lastColon = error.origin().lastIndexOf(':');
            int prevColon = error.origin().lastIndexOf(':', lastColon - 1);
            if (prevColon > 0 && lastColon > prevColon) {
                file = error.origin().substring(0, prevColon);
                line = Integer.valueOf(error.origin()
                    .substring(prevColon + 1, lastColon));
                column = Integer.valueOf(error.origin().substring(lastColon + 1));
            }
        }
        Materialized module = project.moduleOf(file);
        return new OracleOutcome(false, error.code(), error.message(),
            module == null ? file : module.corpusPath(),
            module == null || line == null ? line
                : line + module.headerLinesStripped(),
            column, error.expected(), error.actual());
    }

    /**
     * The oracle driver entry for the ordered zero-arity export loop: the
     * fixture is a non-entry module here, so {@code main} is not invoked by
     * any init walk — the first oracle shape (the fixture as the entry
     * module) covers it.
     */
    private static String oracleDriver(Project project, List<Export> exports) {
        String fixtureStem = moduleNameOf(project.entryFile().getFileName().toString());
        StringBuilder source = new StringBuilder();
        source.append("import * as fx from \"./").append(fixtureStem)
            .append("\"\n\n");
        source.append("export function main(): null {\n");
        for (Export export : exports) {
            if ("main".equals(export.name()) || export.async()) {
                continue;
            }
            source.append("  fx.").append(export.name()).append("()\n");
        }
        return source.append("  return null\n}\n").toString();
    }

    /**
     * The deterministic host half of the host-importing bytes fixture: one
     * retained shared buffer and one call counter, the oracle-side analog
     * of the deployed corpus host implementation.
     */
    private static final class HostState implements SemanticOracle.HostResponder {

        private int calls;
        private final SemanticOracle.Value.BytesValue shared =
            new SemanticOracle.Value.BytesValue(2);

        @Override
        public SyncOutcome call(ModuleId module, String export,
                RuntimeDescriptor.Func descriptor,
                List<SemanticOracle.Value> args) {
            if (module.path().startsWith("candidate")) {
                // The corpus C FFI declaration (ffi/016): the oracle-side
                // realization of the pinned native fold
                // (support/native.c: sum = length; sum = sum*257 + byte).
                if ("ffi_bytes_sum".equals(export) && args.size() == 1) {
                    SemanticOracle.Value.BytesValue buffer =
                        (SemanticOracle.Value.BytesValue) args.get(0);
                    long sum = buffer.length();
                    for (int i = 0; i < buffer.length(); i++) {
                        sum = sum * 257 + buffer.read(i);
                    }
                    return new SyncOutcome.Returned(
                        new SemanticOracle.Value.IntValue(sum));
                }
                return new SyncOutcome.Thrown("E9001",
                    "unknown FFI export " + export);
            }
            if (!module.path().contains("bytes_roundtrip")) {
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
    }

    // =========================================================================
    // 6. The drive
    // =========================================================================

    private static void testProductionDrive() throws Exception {
        System.out.println("-- the lane-equivalent bytes production drive: "
            + DRIVEN.size() + " fixtures, one outcome per target --");
        for (String fixtureRel : DRIVEN) {
            Project luaProject = materialize(fixtureRel, Target.LUAJIT);
            Project jvmProject = materialize(fixtureRel, Target.JVM);
            try {
                List<Export> exports = exportsOf(jvmProject.entryModule().source(),
                    jvmProject.entryFile().toString());
                Lowered laneLowering = null;
                Long asyncEntryId = null;
                if (exports.stream().anyMatch(Export::async)) {
                    laneLowering = lower(fixtureRel + " (async entry)", jvmProject,
                        jvmProject.entryFile(),
                        jvmProject.root().resolve("out-async"));
                    asyncEntryId = laneLowering == null ? null
                        : asyncEntryIdOf(laneLowering);
                }
                Map<Target, Leg> legs = new LinkedHashMap<>();
                legs.put(Target.LUAJIT, driveLeg(fixtureRel, luaProject,
                    Target.LUAJIT, exports, null));
                legs.put(Target.JVM, driveLeg(fixtureRel, jvmProject,
                    Target.JVM, exports, asyncEntryId));
                RECORD.put(fixtureRel, legs);
                OracleOutcome oracle = oracleLeg(fixtureRel, jvmProject, exports,
                    laneLowering);
                ORACLE_RECORD.put(fixtureRel, oracle);
                Map<Target, Boolean> agreement = new LinkedHashMap<>();
                for (Target target : Target.values()) {
                    Leg leg = legs.get(target);
                    if (leg.compileReject()) {
                        continue;
                    }
                    agreement.put(target, oracle != null
                        && oracle.agreesWith(leg.capture()));
                }
                ORACLE_AGREEMENT.put(fixtureRel, agreement);
                System.out.println("   " + fixtureRel + ": "
                    + legs.get(Target.LUAJIT).outcome() + " | "
                    + legs.get(Target.JVM).outcome() + " | oracle "
                    + (oracle == null ? "missing"
                        : oracle.success()
                            ? "success"
                            : oracle.code() + " " + oracle.message()));
                for (Divergence divergence : legs.get(Target.LUAJIT).divergences()) {
                    System.out.println("      [luajit] " + divergence.describe());
                }
                for (Divergence divergence : legs.get(Target.JVM).divergences()) {
                    System.out.println("      [jvm] " + divergence.describe());
                }
            } finally {
                deleteRecursively(luaProject.root());
                deleteRecursively(jvmProject.root());
            }
        }
        for (String fixture : DELEGATED) {
            DELEGATED_RECORD.put(fixture,
                "delegated — requires sub-architecture (RESIDUAL): the "
                    + "lane-equivalent compile/execute/sidecar acceptance "
                    + "(fixture as entry module, host/FFI wiring per the lane "
                    + "rule, the release-owned production invocation on LuaJIT "
                    + "and JVM, the async export driven to completion through its "
                    + "recorded entry, the sidecar-authoritative capture "
                    + "comparison with the header rebase) of "
                    + "bytes/bytes-boundary-order and bytes/bytes-async-closure; "
                    + "never skipped, never treated as passed; the landed "
                    + "BytesCoverageTest nested-declaration and async-entry "
                    + "drives stay green and untouched");
        }
    }

    private static Leg driveLeg(String fixtureRel, Project project, Target target,
            List<Export> exports, Long asyncEntryId) throws Exception {
        List<String> diagnostics = new ArrayList<>();
        boolean compiled = compile(project, fixtureRel, target, diagnostics);
        if (FFI_BYTES.equals(fixtureRel) && target == Target.JVM) {
            check(!compiled, fixtureRel + " [jvm]: the pinned E6006 extern-C "
                + "rejection (the compile fails closed)");
            checkEq(1, diagnostics.size(), fixtureRel + " [jvm]: exactly one "
                + "compile diagnostic: " + diagnostics);
            if (diagnostics.size() == 1) {
                check(diagnostics.get(0).startsWith("E6006 "), fixtureRel
                    + " [jvm]: the pinned E6006 diagnostic: " + diagnostics);
            }
            Path artifact = artifactOf(project, Target.JVM);
            check(!Files.exists(artifact), fixtureRel + " [jvm]: no artifact "
                + "publishes for the compile reject");
            return new Leg(target, false, true, diagnostics, null, null, List.of());
        }
        for (String diagnostic : diagnostics) {
            for (String rule : GUARD_RULES) {
                check(!diagnostic.contains(rule), fixtureRel + " ["
                    + target.laneName() + "]: zero E6005 " + rule + " ("
                    + diagnostic + ")");
            }
            check(!diagnostic.startsWith("E6005 "), fixtureRel + " ["
                + target.laneName() + "]: zero E6005 over the in-scope fixture: "
                + diagnostic);
        }
        check(compiled, fixtureRel + " [" + target.laneName()
            + "]: the fixture compiles through the release-owned production "
            + "invocation: " + diagnostics);
        Path artifact = artifactOf(project, target);
        check(Files.isRegularFile(artifact), fixtureRel + " ["
            + target.laneName() + "]: the artifact publishes at " + artifact);
        if (!compiled || !Files.isRegularFile(artifact)) {
            return new Leg(target, false, false, diagnostics,
                Files.exists(artifact) ? artifact.toString() : null, null,
                List.of());
        }
        Execution execution = execute(project, exports, asyncEntryId, target);
        SidecarExpectations.RuntimeExpectation.Executed pinned = pinnedOf(fixtureRel);
        check(!execution.probeDefect(), fixtureRel + " [" + target.laneName()
            + "]: the artifact drive reaches every export: "
            + (execution.capture() == null ? ""
                : String.valueOf(execution.capture().message())));
        Capture capture = normalize(project, execution.capture());
        if (capture == null && pinned.isRuntimeError()) {
            fail(fixtureRel + " [" + target.laneName() + "]: the artifact drive "
                + "captured no tuple where the sidecar pins a failure (exit "
                + execution.exitCode() + "; stdout " + execution.stdout()
                + "; stderr " + execution.stderr() + ")");
        }
        List<Divergence> divergences = compare(capture, pinned, execution);
        if (pinned.isRuntimeError()) {
            // The probe projects the tuple and its own empty transcript; the
            // comparison reproduces the pinned lane framing from the
            // mandatory and pinned fields (never a fabricated field).
            checkEq(1, execution.exitCode(), fixtureRel + " ["
                + target.laneName() + "]: the failing artifact run exits 1");
            check(execution.stdout().isEmpty() && execution.stderr().isEmpty(),
                fixtureRel + " [" + target.laneName() + "]: the probe projects "
                    + "the tuple through its transport, not its streams");
            if (divergences.isEmpty() && pinned.error() != null) {
                checkEq(new String(pinned.stdout(), StandardCharsets.UTF_8),
                    framedSnapshot(capture, pinned.error()), fixtureRel + " ["
                        + target.laneName() + "]: the pin-exact capture "
                        + "reproduces the sidecar transcript byte-for-byte");
            }
        } else if (divergences.isEmpty()) {
            checkEq(0, execution.exitCode(), fixtureRel + " ["
                + target.laneName() + "]: the runtime-ok artifact run exits 0");
        }
        return new Leg(target, true, false, diagnostics, artifact.toString(),
            capture, divergences);
    }

    /** The recorded async entry op id of one lowered closure. */
    private static Long asyncEntryIdOf(Lowered lowered) {
        for (SemanticOp op : lowered.entryUnit().ops()) {
            if (op.kind() == SemanticOpKind.EXTERNAL_ENTRY
                    && op.payload() instanceof KindPayload.ExternalEntryPayload payload
                    && payload.async()
                    && ASYNC_HOST_EXPORT.equals(payload.exportName())) {
                return op.opId().id();
            }
        }
        return null;
    }

    // =========================================================================
    // 6b. The externals-identity convention (the dotted host typing name)
    // =========================================================================

    /**
     * The corpus externals-identity convention, driven end to end: a host
     * declaration's class identities project through its dotted typing
     * name ({@code @$external/host.cfg/...}) — the JVM lane's rebuild rule
     * and the spelling the corpus host implementation tags
     * ({@code host-fixtures/cfg.lua}) — while import resolution keys on
     * the raw specifier the generated {@code deal.json} wires. The bytes
     * slate's own host import is class-free, so this focused regression
     * drives a class-carrying host declaration through the same
     * materialization, compile, and deferred-entry path: under a
     * raw-externals-only context the emitted class atoms carry the raw
     * spelling and the deployed host implementation's class-identity
     * check fails (E8011); with the dotted-first context the leg
     * reproduces the fixture's sidecar.
     */
    private static void testExternalsIdentityConvention() throws Exception {
        System.out.println("-- the externals-identity convention: the dotted "
            + "host typing name --");
        Map<Target, Project> projects = new LinkedHashMap<>();
        try {
            for (Target target : Target.values()) {
                Project project = materialize(CLASS_HOST_FIXTURE, target);
                projects.put(target, project);
                List<String> diagnostics = new ArrayList<>();
                boolean compiled = compile(project, CLASS_HOST_FIXTURE, target,
                    diagnostics);
                check(compiled, CLASS_HOST_FIXTURE + " [" + target.laneName()
                    + "]: the class-carrying host declaration compiles through "
                    + "the release-owned production invocation: " + diagnostics);
                Path artifact = artifactOf(project, target);
                check(Files.isRegularFile(artifact), CLASS_HOST_FIXTURE + " ["
                    + target.laneName() + "]: the artifact publishes");
                if (!compiled || !Files.isRegularFile(artifact)) {
                    continue;
                }
                String source = Files.readString(artifact, StandardCharsets.UTF_8);
                check(source.contains("@$external/host.cfg/ServerConfig"),
                    CLASS_HOST_FIXTURE + " [" + target.laneName()
                        + "]: the emitted class identity carries the dotted "
                        + "host typing name");
                check(!source.contains("@$external/host/cfg/"),
                    CLASS_HOST_FIXTURE + " [" + target.laneName()
                        + "]: the emitted class identity carries no raw "
                        + "externals spelling");
            }
            Project luaProject = projects.get(Target.LUAJIT);
            if (luaProject == null) {
                return;
            }
            // The oracle leg's declaration identity map: the lowered
            // closure's declared-class registration must carry the dotted
            // typing name (@$external/host.cfg/ServerConfig), never the raw
            // externals spelling.
            Lowered oracle = lower(CLASS_HOST_FIXTURE + " (identity oracle)",
                luaProject, luaProject.entryFile(),
                luaProject.root().resolve("out-oracle-identity"));
            check(oracle != null, CLASS_HOST_FIXTURE
                + " [oracle]: the class-carrying host declaration lowers");
            if (oracle != null) {
                check(oracle.seeds().registrations().keySet().contains(
                        new ClassId("$external/host.cfg", "ServerConfig")),
                    CLASS_HOST_FIXTURE + " [oracle]: the declaration identity "
                        + "map carries the dotted host typing name: "
                        + oracle.seeds().registrations().keySet());
                check(oracle.seeds().registrations().keySet().stream()
                        .noneMatch(id -> id.text().contains("$external/host/cfg")),
                    CLASS_HOST_FIXTURE + " [oracle]: the declaration identity "
                        + "map carries no raw externals spelling");
            }
            List<Export> exports = exportsOf(luaProject.entryModule().source(),
                luaProject.entryFile().toString());
            Leg leg = driveLeg(CLASS_HOST_FIXTURE, luaProject, Target.LUAJIT,
                exports, null);
            checkEq("pin-exact", leg.outcome(), CLASS_HOST_FIXTURE
                + " [luajit]: the deployed corpus host implementation's "
                + "class-identity check passes against the dotted typing name");
        } finally {
            for (Project project : projects.values()) {
                deleteRecursively(project.root());
            }
        }
    }

    /** The arm's pinned expected text (the serialized row's own field). */
    private static final String JSON_STRINGIFY_EXPECTED_TEXT =
        "string, number, boolean, or table";

    /**
     * The emitted bytes carrier arm of the JSON_STRINGIFY walk: a bytes
     * value renders the arm's own carrier-kind projection through the same
     * {@code sfFail} render as every other carrier kind, before the
     * generic table fallback.
     */
    private static final String JSON_STRINGIFY_BYTES_ARM =
        "if v.__kind == \"bytes\" then sfFail(v); return end";

    /**
     * One scratch shape of the arm's direct drive: the entry source, its
     * pinned {@code json.stringify} call-expression span, and the pinned
     * arm tuple.
     */
    private record StringifyShape(String label, String stem, String source,
                                  int line, int column, String message,
                                  String actual) {
    }

    /** The bytes value at the top level of the stringified table. */
    private static final String STRINGIFY_BYTES_TOP_SOURCE = """
        import * as json from "std/json";

        export function test_json_stringify_bytes_top(): string {
          let b: bytes = bytes(2);
          b[0] = 65;
          let t: table = { payload: b };
          return json.stringify(t);
        }

        export function main(): null {
          return null;
        }
        """;

    /** The bytes value as an element of a {@code bytes[]} table member. */
    private static final String STRINGIFY_BYTES_ARRAY_SOURCE = """
        import * as json from "std/json";

        export function test_json_stringify_bytes_array(): string {
          let b: bytes = bytes(1);
          b[0] = 65;
          let arr: bytes[] = [b];
          let t: table = { bucket: arr };
          return json.stringify(t);
        }

        export function main(): null {
          return null;
        }
        """;

    /** The bytes value as a leaf four tables deep. */
    private static final String STRINGIFY_BYTES_DEEP_SOURCE = """
        import * as json from "std/json";

        export function test_json_stringify_bytes_deep(): string {
          let b: bytes = bytes(1);
          b[0] = 65;
          let outer: table = { level1: {} };
          let level1: table = outer.level1;
          let level2: table = {};
          level1.level2 = level2;
          let level3: table = {};
          level2.level3 = level3;
          level3.bucket = b;
          return json.stringify(outer);
        }

        export function main(): null {
          return null;
        }
        """;

    private static final String STRINGIFY_CLASS_SOURCE = """
        import * as json from "std/json";

        class Box { value: int = 0; }

        export function test_json_stringify_class_control(): string {
          let b: Box = { value: 1 };
          let t: table = { box: b };
          return json.stringify(t);
        }

        export function main(): null {
          return null;
        }
        """;

    /**
     * The four scratch shapes: the three reachable bytes nesting shapes
     * (the top-level table member, the {@code bytes[]} element, and the
     * leaf four tables deep) and the class-instance carrier-kind control.
     * Every shape pins its own {@code json.stringify} call-expression span
     * and the arm's tuple.
     */
    private static final List<StringifyShape> JSON_STRINGIFY_SHAPES = List.of(
        new StringifyShape("the top-level table member",
            "json_stringify_bytes_top", STRINGIFY_BYTES_TOP_SOURCE, 7, 10,
            "unsupported type for JSON encoding: bytes", "bytes"),
        new StringifyShape("the bytes[] element",
            "json_stringify_bytes_array", STRINGIFY_BYTES_ARRAY_SOURCE, 8, 10,
            "unsupported type for JSON encoding: bytes", "bytes"),
        new StringifyShape("the four-tables-deep leaf",
            "json_stringify_bytes_deep", STRINGIFY_BYTES_DEEP_SOURCE, 13, 10,
            "unsupported type for JSON encoding: bytes", "bytes"),
        new StringifyShape("the class-instance member",
            "json_stringify_class_control", STRINGIFY_CLASS_SOURCE, 8, 10,
            "unsupported type for JSON encoding: table", "table"));

    private static void testJsonStringifyBytesArm() throws Exception {
        System.out.println("-- the JSON_STRINGIFY bytes carrier arm: the three "
            + "nesting shapes, the carrier-kind controls, and the rendered row --");
        checkEq(FailureContractRegistry.arm(FailureArmId.JSON_STRINGIFY_UNSUPPORTED)
                .pinnedExpectedText(), JSON_STRINGIFY_EXPECTED_TEXT,
            "the pinned expected text is the JSON_STRINGIFY_UNSUPPORTED row's "
                + "own field, never a re-literalled site text");
        for (StringifyShape shape : JSON_STRINGIFY_SHAPES) {
            Map<Target, Project> projects = new LinkedHashMap<>();
            try {
                Map<Target, Capture> captures = new LinkedHashMap<>();
                for (Target target : Target.values()) {
                    Project project = materializeScratch(shape.stem(),
                        shape.source(), target);
                    projects.put(target, project);
                    List<Export> exports = exportsOf(
                        project.entryModule().source(),
                        project.entryFile().toString());
                    Capture capture = driveShape(shape.label(), project, target,
                        exports);
                    captures.put(target, capture);
                    checkShapeTuple(shape, target, capture);
                }
                Project luajitProject = projects.get(Target.LUAJIT);
                List<Export> exports = exportsOf(
                    luajitProject.entryModule().source(),
                    luajitProject.entryFile().toString());
                OracleOutcome oracle = oracleLeg(shape.label(), luajitProject,
                    exports, null);
                check(oracle != null && !oracle.success(), shape.label()
                    + " [oracle]: the shared model raises the rejection: " + oracle);
                if (oracle != null) {
                    for (Target target : Target.values()) {
                        check(oracle.agreesWith(captures.get(target)), shape.label()
                            + " [" + target.laneName() + "]: the oracle terminal "
                            + "agrees with the artifact tuple");
                    }
                }
                checkEmittedStringifyArm(shape.label(), luajitProject);
            } finally {
                for (Project project : projects.values()) {
                    deleteRecursively(project.root());
                }
            }
        }

        for (String fixture : List.of(
                "backend-runtime/runtime-errors/json-stringify-function-e8001",
                "backend-runtime/stdlib/json/json-stringify-roundtrip")) {
            Map<Target, Project> projects = new LinkedHashMap<>();
            try {
                for (Target target : Target.values()) {
                    Project project = materialize(fixture, target);
                    projects.put(target, project);
                    List<Export> exports = exportsOf(
                        project.entryModule().source(),
                        project.entryFile().toString());
                    Leg leg = driveLeg(fixture, project, target, exports, null);
                    checkEq("pin-exact", leg.outcome(), fixture + " ["
                        + target.laneName() + "]: the carrier-kind control "
                        + "reproduces its sidecar pins");
                    if (fixture.endsWith("json-stringify-function-e8001")
                            && leg.capture() != null) {
                        checkEq("function", leg.capture().actual(), fixture + " ["
                            + target.laneName() + "]: the function member keeps "
                            + "its landed token");
                        checkEq("unsupported type for JSON encoding: function",
                            leg.capture().message(), fixture + " ["
                                + target.laneName() + "]: the function member "
                                + "renders the landed token");
                    }
                }
            } finally {
                for (Project project : projects.values()) {
                    deleteRecursively(project.root());
                }
            }
        }
    }

    /**
     * Materializes one scratch entry module exactly as the lane does: the
     * source is the entry module of the temp project (no companion, no
     * declaration), and one generated {@code deal.json} wires the module
     * root and the target backend.
     */
    private static Project materializeScratch(String stem, String source,
            Target target) throws Exception {
        Path root = Files.createTempDirectory("bytes-prod-scratch-");
        Path srcRoot = root.resolve("src");
        Files.createDirectories(srcRoot);
        Path entry = srcRoot.resolve(stem + ".deal");
        Files.writeString(entry, source, StandardCharsets.UTF_8);
        Map<String, Materialized> modules = new LinkedHashMap<>();
        modules.put(entry.toAbsolutePath().normalize().toString(),
            new Materialized("scratch/" + stem + ".deal", source, 0));
        Files.writeString(root.resolve("deal.json"),
            "{\n  \"languageVersion\": \"1.2\",\n"
                + "  \"moduleRoots\": [\"src\"],\n"
                + "  \"output\": \"out\",\n"
                + "  \"backend\": \""
                + (target == Target.JVM ? "jvm" : "luajit") + "\"\n}\n");
        return new Project(root, srcRoot, entry, stem, modules, Map.of(),
            List.of(), List.of(), root.resolve("out"));
    }

    /**
     * Compiles and drives one scratch shape through the release-owned
     * production invocation on one target; the projected capture is null
     * only when the compile or the publish failed (already reported).
     */
    private static Capture driveShape(String label, Project project, Target target,
            List<Export> exports) throws Exception {
        List<String> diagnostics = new ArrayList<>();
        boolean compiled = compile(project, label, target, diagnostics);
        check(compiled, label + " [" + target.laneName() + "]: the scratch entry "
            + "compiles through the release-owned production invocation: "
            + diagnostics);
        check(diagnostics.isEmpty(), label + " [" + target.laneName() + "]: the "
            + "scratch compile carries no diagnostic: " + diagnostics);
        Path artifact = artifactOf(project, target);
        check(Files.isRegularFile(artifact), label + " [" + target.laneName()
            + "]: the artifact publishes at " + artifact);
        if (!compiled || !Files.isRegularFile(artifact)) {
            return null;
        }
        Execution execution = execute(project, exports, null, target);
        check(!execution.probeDefect(), label + " [" + target.laneName()
            + "]: the probe reaches the failing export: "
            + (execution.capture() == null ? ""
                : String.valueOf(execution.capture().message())));
        checkEq(1, execution.exitCode(), label + " [" + target.laneName()
            + "]: the failing artifact run exits 1");
        check(execution.stdout().isEmpty() && execution.stderr().isEmpty(), label
            + " [" + target.laneName() + "]: the probe projects the tuple through "
            + "its transport, not its streams (stdout " + execution.stdout()
            + "; stderr " + execution.stderr() + ")");
        return normalize(project, execution.capture());
    }

    /** The pinned arm tuple of one scratch shape on one target. */
    private static void checkShapeTuple(StringifyShape shape, Target target,
            Capture capture) {
        String label = shape.label() + " [" + target.laneName() + "]";
        check(capture != null, label + ": the artifact drive captures the tuple");
        if (capture == null) {
            return;
        }
        checkEq("E8001", capture.code(), label + ": the pinned code");
        checkEq(shape.message(), capture.message(), label + ": the pinned message");
        checkEq(JSON_STRINGIFY_EXPECTED_TEXT, capture.expected(), label
            + ": the arm row's own pinned expected text");
        checkEq(shape.actual(), capture.actual(), label
            + ": the pinned carrier-kind token");
        checkEq(shape.line(), capture.line(), label
            + ": the json.stringify call-expression line");
        checkEq(shape.column(), capture.column(), label
            + ": the json.stringify call-expression column");
    }

    /**
     * The emitted walk's own render, asserted over the staged LuaJIT
     * artifact: the bytes carrier arm sits in the table branch with the
     * other carrier-kind arms and before the generic table fallback, the
     * actual token comes from the closed std/json carrier-kind projection,
     * and the message and expected text come from the serialized arm row —
     * the failure site holds no composed text of its own.
     */
    private static void checkEmittedStringifyArm(String label, Project luajitProject)
            throws Exception {
        String emitted = Files.readString(artifactOf(luajitProject, Target.LUAJIT),
            StandardCharsets.UTF_8);
        int start = emitted.indexOf("elseif fn == \"JSON_STRINGIFY\" then");
        int end = emitted.indexOf("elseif fn == \"MATH_FLOOR\" then");
        check(start >= 0 && end > start, label + " [luajit]: the emitted chunk "
            + "carries the JSON_STRINGIFY walk");
        if (start < 0 || end <= start) {
            return;
        }
        String walk = emitted.substring(start, end);
        int arm = walk.indexOf(JSON_STRINGIFY_BYTES_ARM);
        int functionArm = walk.indexOf("if v.__fn ~= nil or v.__f or "
            + "v.__kind == \"function\" then");
        int fallback = walk.indexOf("        sfFail(v)\n        return\n      end");
        check(arm >= 0, label + " [luajit]: the walk's table branch carries the "
            + "bytes carrier arm: " + JSON_STRINGIFY_BYTES_ARM);
        check(functionArm >= 0 && arm > functionArm && fallback > arm, label
            + " [luajit]: the bytes arm is ordered with the other carrier-kind "
            + "arms and before the generic table fallback (arm " + arm
            + ", function " + functionArm + ", fallback " + fallback + ")");
        check(walk.contains("local actual = __stdJsonKind(v)"), label
            + " [luajit]: the actual token is the closed std/json carrier-kind "
            + "projection");
        check(walk.contains("__sfail(\"JSON_STRINGIFY_UNSUPPORTED\""), label
            + " [luajit]: the arm renders through the closed row");
        check(walk.contains("__arms.JSON_STRINGIFY_UNSUPPORTED.p"), label
            + " [luajit]: the expected text is the serialized row's own field");
        check(!walk.contains("unsupported type for JSON encoding"), label
            + " [luajit]: the failure site holds no composed message text");
        check(walk.contains("if v.__c or v.__d then sfFail(v); return end"), label
            + " [luajit]: the class-instance carrier arm stays landed beside the "
            + "bytes arm");
        check(walk.contains("if v.__t then"), label + " [luajit]: the shared "
            + "table carrier arm stays landed");
    }

    // =========================================================================
    // 7. The baseline record
    // =========================================================================

    /** One pinned baseline cell. */
    private record BaselineCell(String disposition, List<Divergence> divergences,
                                boolean compileReject) {

        static BaselineCell pinExact() {
            return new BaselineCell("pin-exact", List.of(), false);
        }

        static BaselineCell pinnedCompileReject() {
            return new BaselineCell("pin-exact", List.of(), true);
        }

        static BaselineCell divergent(Divergence... divergences) {
            return new BaselineCell("divergent", List.of(divergences), false);
        }
    }

    /** One pinned divergent leg: the fixture, the target, and the fields. */
    private record DivergentLeg(String fixture, Target target,
                                List<Divergence> differences) {
    }

    private static Divergence div(String field, String captured, String pinned) {
        return new Divergence(field, captured, pinned);
    }

    private static DivergentLeg divergent(String fixture, Target target,
            Divergence... differences) {
        return new DivergentLeg(fixture, target, List.of(differences));
    }

    /**
     * The measured divergences of the drive's baseline, each named by
     * fixture, target, field, captured value, and pinned value. The design's
     * measured baseline reproduced four divergent legs (the two
     * {@code stdlib/json} LuaJIT legs and the two
     * {@code bytes-dynamic-nested-first-element-e8003} legs); the two
     * {@code stdlib/json} LuaJIT legs are already fixed at this revision —
     * the emitted walk renders the closed std/json carrier-kind projection
     * ({@code bytes}) — so the measured baseline carries the two remaining
     * legs, with the oracle producing the same {@code array} tuple.
     */
    private static final List<DivergentLeg> PINNED_DIVERGENCES = List.of(
        divergent(BYTES_DIR + "/bytes-dynamic-nested-first-element-e8003",
            Target.LUAJIT,
            div("actual", "array", "table")),
        divergent(BYTES_DIR + "/bytes-dynamic-nested-first-element-e8003",
            Target.JVM,
            div("actual", "array", "table")));

    /**
     * The pinned totals of the baseline record: the 78 cells are 76
     * pin-exact legs (75 executed legs reproducing their pinned tuple plus
     * {@code ffi/016}'s one pinned compile-reject leg) and the 2 measured
     * divergent legs.
     */
    private static final int PIN_EXACT_LEGS = 76;
    private static final int DIVERGENT_LEGS = 2;
    private static final int COMPILE_REJECT_LEGS = 1;

    /** The pinned baseline: one cell per driven fixture and target. */
    private static Map<String, Map<Target, BaselineCell>> pinnedBaseline() {
        Map<String, Map<Target, BaselineCell>> baseline = new LinkedHashMap<>();
        for (String fixture : DRIVEN) {
            Map<Target, BaselineCell> legs = new LinkedHashMap<>();
            for (Target target : Target.values()) {
                legs.put(target, BaselineCell.pinExact());
            }
            baseline.put(fixture, legs);
        }
        baseline.get(FFI_BYTES).put(Target.JVM, BaselineCell.pinnedCompileReject());
        for (DivergentLeg leg : PINNED_DIVERGENCES) {
            baseline.get(leg.fixture()).put(leg.target(),
                BaselineCell.divergent(
                    leg.differences().toArray(new Divergence[0])));
        }
        return baseline;
    }

    /**
     * Compares one measured record with the pinned baseline: exactly one row
     * per fixture and target, the pinned disposition and the pinned
     * divergence fields/values, and the pinned totals.
     */
    private static List<String> baselineFailures(
            Map<String, Map<Target, Leg>> measured,
            Map<String, String> delegated, int pinExact, int divergentCount,
            int compileReject) {
        List<String> failures = new ArrayList<>();
        Map<String, Map<Target, BaselineCell>> pinned = pinnedBaseline();
        if (!new LinkedHashSet<>(DRIVEN).equals(measured.keySet())) {
            failures.add("the accounting rows are exactly the 39-fixture scope: "
                + measured.keySet());
        }
        for (Map.Entry<String, Map<Target, BaselineCell>> row : pinned.entrySet()) {
            Map<Target, Leg> legs = measured.get(row.getKey());
            if (legs == null) {
                failures.add("the accounting carries no row for " + row.getKey());
                continue;
            }
            if (!legs.keySet().equals(Set.of(Target.values()))) {
                failures.add("the accounting carries one cell per target for "
                    + row.getKey() + ": " + legs.keySet());
            }
            for (Map.Entry<Target, BaselineCell> cell : row.getValue().entrySet()) {
                Leg leg = legs.get(cell.getKey());
                if (leg == null) {
                    continue;
                }
                if (!cell.getValue().disposition().equals(leg.outcome())) {
                    failures.add(row.getKey() + " ["
                        + cell.getKey().laneName() + "]: the measured outcome is "
                        + leg.outcome() + ", the pinned baseline is "
                        + cell.getValue().disposition()
                        + (leg.divergences().isEmpty() ? ""
                            : " (" + describe(leg.divergences()) + ")"));
                    continue;
                }
                if (cell.getValue().compileReject() != leg.compileReject()) {
                    failures.add(row.getKey() + " ["
                        + cell.getKey().laneName() + "]: the pinned compile-reject "
                        + "leg measured " + leg.outcome() + " (compileReject "
                        + leg.compileReject() + ")");
                    continue;
                }
                if (leg.compileReject() && leg.artifact() != null) {
                    failures.add(row.getKey() + " ["
                        + cell.getKey().laneName() + "]: the compile reject "
                        + "publishes no artifact, got " + leg.artifact());
                }
                if (!leg.divergences().equals(cell.getValue().divergences())) {
                    failures.add(row.getKey() + " ["
                        + cell.getKey().laneName() + "]: the divergences are "
                        + describe(leg.divergences()) + ", the pinned baseline is "
                        + describe(cell.getValue().divergences()));
                }
            }
        }
        for (String fixture : DELEGATED) {
            if (!delegated.containsKey(fixture)) {
                failures.add(fixture + ": the delegated disposition is reported");
            }
            if (measured.containsKey(fixture)) {
                failures.add(fixture + ": a delegated fixture carries no driven "
                    + "leg (it is never treated as passed)");
            }
        }
        int measuredOk = 0;
        int measuredDivergent = 0;
        int measuredReject = 0;
        for (Map.Entry<String, Map<Target, Leg>> row : measured.entrySet()) {
            for (Leg leg : row.getValue().values()) {
                switch (leg.outcome()) {
                    case "pin-exact" -> measuredOk++;
                    case "divergent" -> measuredDivergent++;
                    default -> failures.add(row.getKey() + " ["
                        + leg.target().laneName() + "]: the outcome is "
                        + leg.outcome());
                }
                if (leg.compileReject()) {
                    measuredReject++;
                }
            }
        }
        if (measuredOk != pinExact || measuredDivergent != divergentCount
                || measuredReject != compileReject) {
            failures.add("the accounting totals are " + measuredOk + " pin-exact / "
                + measuredDivergent + " divergent / " + measuredReject
                + " compile-reject; the pinned baseline is " + pinExact + " / "
                + divergentCount + " / " + compileReject);
        }
        return failures;
    }

    private static String describe(List<Divergence> divergences) {
        List<String> parts = new ArrayList<>();
        for (Divergence divergence : divergences) {
            parts.add(divergence.describe());
        }
        return "[" + String.join("; ", parts) + "]";
    }

    // =========================================================================
    // 8. The invariants
    // =========================================================================

    private static void testInvariants() throws Exception {
        System.out.println("-- the preserved invariants: the count pin, the "
            + "sidecar schema, the boundary set, and the untouched surfaces --");

        // The dispatched corpus count equals its current pin (CD2).
        CorpusDiscovery.DiscoveryResult discovery =
            CorpusDiscovery.discover(CONFORMANCE.toAbsolutePath().normalize());
        long dispatched = discovery.fixtures().stream()
            .filter(CorpusDiscovery.Fixture::runtimeClassified).count();
        checkEq((long) DISPATCHED_COUNT_PIN, dispatched,
            "the dispatched corpus count equals its current pin");
        check(discovery.failures().isEmpty(),
            "zero classification failures over the corpus: "
                + discovery.failures());

        // The sidecar schema validates unchanged over the drive's scope.
        Map<String, CorpusDiscovery.Fixture> corpusByPath = discovery.corpusByPath();
        for (String fixtureRel : DRIVEN) {
            String corpusPath = fixtureRel + ".deal";
            CorpusDiscovery.Fixture fixture = corpusByPath.get(corpusPath);
            check(fixture != null, fixtureRel + " is a discovered corpus fixture");
            if (fixture == null) {
                continue;
            }
            SidecarSchemaValidator.ValidationContext context =
                new SidecarSchemaValidator.ValidationContext(corpusPath,
                    fixture.expected(),
                    CorpusDiscovery.compilationSet(fixture, corpusByPath, CONFORMANCE),
                    discovery.corpusModuleIndex());
            Optional<SidecarSchemaValidator.ClassificationFailure> failure =
                SidecarSchemaValidator.validate(context,
                    Files.readString(CONFORMANCE.resolve(fixtureRel + ".expect.json"),
                        StandardCharsets.UTF_8));
            check(failure.isEmpty(), fixtureRel + ": the sidecar validates against "
                + "the unchanged schema: " + failure
                    .map(SidecarSchemaValidator.ClassificationFailure::message)
                    .orElse(""));
        }

        // The closed BoundaryKind set: the 27 pinned members with the two
        // reserved names.
        checkEq(27, BoundaryKind.values().length,
            "the BoundaryKind set is exactly the 27 pinned members");
        checkEq(List.of("C_FFI_TO_DEAL", "DEAL_TO_C_FFI"), BoundaryKind.RESERVED_NAMES,
            "C_FFI_TO_DEAL/DEAL_TO_C_FFI are the only reserved names");
        for (String reserved : BoundaryKind.RESERVED_NAMES) {
            for (BoundaryKind kind : BoundaryKind.values()) {
                check(!kind.name().equals(reserved),
                    reserved + " stays a reserved (non-member) name");
            }
        }

        Path bytesCoverage = Path.of("test", "BytesCoverageTest.java");
        check(Files.isRegularFile(bytesCoverage),
            "the landed element-contract battery stays in the tree");
        String coverageSource = Files.readString(bytesCoverage, StandardCharsets.UTF_8);
        for (String delegated : DELEGATED) {
            String stem = moduleNameOf(Path.of(delegated).getFileName().toString());
            check(coverageSource.contains("\"" + stem + "\""), delegated
                + ": the landed BytesCoverageTest drive names the fixture");
        }
        String manifest = Files.readString(Path.of("tools", "gate-manifest.sh"),
            StandardCharsets.UTF_8);
        check(manifest.contains("deal.test.BytesCoverageTest"),
            "the landed element-contract battery stays registered");
        check(manifest.contains("deal.test.BytesProductionDriveTest"),
            "the bytes production drive is registered in the gate manifest");
        // The JS lane and the corpus membership are untouched by this drive.
        for (String jsFile : List.of("deal/runtime.js", "std/json.js",
                "test/conformance/JsLane.java")) {
            check(Files.isRegularFile(Path.of(jsFile)),
                "the unchanged JS lane file " + jsFile + " stays in the tree");
        }
        check(Files.isRegularFile(Path.of("std", "json.lua")),
            "the unchanged std/json.lua stays in the tree");

        // The post-state: no test-side residue in the corpus (every file of
        // an in-scope directory is a fixture or a sidecar) and the drive's
        // temp projects leave nothing behind.
        for (Path directory : List.of(CONFORMANCE.resolve(BYTES_DIR),
                CONFORMANCE.resolve(SOURCE_LOCATION_DIR),
                CONFORMANCE.resolve(STDLIB_JSON_DIR))) {
            try (Stream<Path> entries = Files.list(directory)) {
                for (Path file : entries.toList()) {
                    String name = file.getFileName().toString();
                    check(name.endsWith(".deal") || name.endsWith(".expect.json"),
                        directory + "/" + name + " is a fixture or a sidecar "
                            + "(no drive residue)");
                }
            }
        }

        // Repeated lowering and emission of the same fixture are
        // byte-identical (two compiles of one lane-equivalent project emit
        // the identical artifact for each target).
        for (String fixtureRel : List.of(BYTES_DIR + "/bytes-length",
                ASYNC_HOST_FIXTURE)) {
            for (Target target : Target.values()) {
                Project project = materialize(fixtureRel, target);
                try {
                    List<String> firstDiagnostics = new ArrayList<>();
                    List<String> secondDiagnostics = new ArrayList<>();
                    boolean firstCompiled = compile(project, fixtureRel, target,
                        firstDiagnostics);
                    String first = firstCompiled
                        ? Files.readString(artifactOf(project, target)) : null;
                    boolean secondCompiled = compile(project, fixtureRel, target,
                        secondDiagnostics);
                    String second = secondCompiled
                        ? Files.readString(artifactOf(project, target)) : null;
                    check(firstCompiled && secondCompiled, fixtureRel + " ["
                        + target.laneName() + "]: both drives compile: "
                        + firstDiagnostics + " " + secondDiagnostics);
                    if (firstCompiled && secondCompiled) {
                        checkEq(first, second, fixtureRel + " ["
                            + target.laneName() + "]: repeated emission is "
                            + "byte-identical");
                        checkEq(firstDiagnostics, secondDiagnostics, fixtureRel
                            + " [" + target.laneName() + "]: repeated lowering "
                            + "carries no diagnostic");
                    }
                } finally {
                    deleteRecursively(project.root());
                }
            }
        }
    }

    // =========================================================================
    // 9. The baseline, the oracle agreement, and the negative controls
    // =========================================================================

    private static void testBaseline() {
        // The compile criterion over the whole scope (no aggregate-only
        // reporting; the per-fixture/per-target lines above are the record).
        long guarded = RECORD.values().stream().flatMap(map -> map.values().stream())
            .filter(leg -> leg.compileDiagnostics().stream()
                .anyMatch(d -> GUARD_RULES.stream().anyMatch(d::contains)))
            .count();
        System.out.println("   the compile criterion: " + RECORD.size()
            + " fixtures on both targets through the release-owned production "
            + "invocation, " + guarded + " legs carrying a guard diagnostic, "
            + "one pinned compile-reject leg (ffi/016 on the JVM)");
        checkEq(0L, guarded, "zero E6005 guard diagnostics over the scope");
        System.out.println("-- the baseline record --");
        List<String> failures = baselineFailures(RECORD, DELEGATED_RECORD,
            PIN_EXACT_LEGS, DIVERGENT_LEGS, COMPILE_REJECT_LEGS);
        for (String failure : failures) {
            fail("baseline: " + failure);
        }
        check(failures.isEmpty(), "the measured record equals the pinned baseline");
        checkEq(39, RECORD.size(), "the accounting carries one row per fixture");
        long cells = RECORD.values().stream().mapToLong(Map::size).sum();
        checkEq(78L, cells, "the accounting carries one cell per fixture and target");
        System.out.println("   " + PIN_EXACT_LEGS + " pin-exact legs ("
            + (PIN_EXACT_LEGS - COMPILE_REJECT_LEGS)
            + " executed + the pinned ffi/016 compile-reject leg), "
            + DIVERGENT_LEGS + " divergent legs, " + DELEGATED_RECORD.size()
            + " delegated fixtures, " + cells + " cells");
        for (String fixture : DELEGATED) {
            check(DELEGATED_RECORD.containsKey(fixture), fixture
                + " is reported delegated (never skipped, never passed)");
            System.out.println("   " + fixture + ": "
                + DELEGATED_RECORD.get(fixture));
        }
        for (DivergentLeg leg : PINNED_DIVERGENCES) {
            System.out.println("   " + leg.fixture() + " ["
                + leg.target().laneName() + "]: divergent, "
                + describe(leg.differences()));
        }

        for (String fixture : List.of(
                STDLIB_JSON_DIR + "/json-stringify-bytes-error",
                STDLIB_JSON_DIR + "/json-stringify-nested-bytes-error")) {
            Leg leg = RECORD.get(fixture).get(Target.LUAJIT);
            check(leg != null && "pin-exact".equals(leg.outcome()), fixture
                + " [luajit]: the design baseline's divergence is corrected");
            if (leg != null && leg.capture() != null) {
                checkEq("unsupported type for JSON encoding: bytes",
                    leg.capture().message(), fixture + " [luajit]: the pinned "
                        + "message renders the carrier kind");
                checkEq("bytes", leg.capture().actual(), fixture
                    + " [luajit]: the pinned actual renders the carrier kind");
            }
        }
    }

    private static void testOracleAgreement() {
        System.out.println("-- the baseline oracle-agreement record --");
        int missing = 0;
        for (String fixtureRel : DRIVEN) {
            if (ORACLE_RECORD.get(fixtureRel) == null) {
                missing++;
                fail("oracle: " + fixtureRel + " carries no oracle terminal");
            }
        }
        checkEq(0, missing, "every driven fixture carries its oracle terminal");
        List<String> disagreements = new ArrayList<>();
        for (Map.Entry<String, Map<Target, Boolean>> entry
                : ORACLE_AGREEMENT.entrySet()) {
            for (Map.Entry<Target, Boolean> cell : entry.getValue().entrySet()) {
                if (cell.getValue()) {
                    continue;
                }
                OracleOutcome oracle = ORACLE_RECORD.get(entry.getKey());
                Capture capture = RECORD.get(entry.getKey())
                    .get(cell.getKey()).capture();
                disagreements.add(entry.getKey() + " ["
                    + cell.getKey().laneName() + "]: oracle " + oracle
                    + " vs artifact " + capture);
            }
        }
        if (!disagreements.isEmpty()
                && !disagreements.equals(PINNED_ORACLE_DISAGREEMENTS)) {
            fail("the oracle-agreement record is " + disagreements
                + ", the pinned baseline is " + PINNED_ORACLE_DISAGREEMENTS);
        }
    }

    /**
     * The raw externals specifier of one resolved declaration module path
     * (the registration key of the generated {@code deal.json}).
     */
    private static String rawSpecifierOf(Project project, String modulePath) {
        for (String raw : project.rawToDeclaration().keySet()) {
            if (raw.replace('/', '.').equals(modulePath)
                    || raw.equals(modulePath)) {
                return raw;
            }
        }
        return modulePath;
    }

    /**
     * The canonical external identity text of one declaration's raw
     * specifier (the production lane-equivalent classification): a host
     * declaration projects its dotted typing name
     * ({@code host.cfg}) — the corpus externals-identity convention the
     * lanes, the class atoms, and the corpus host implementations share —
     * while an extern-C declaration keeps its raw externals key.
     */
    private static String declarationIdentityOf(String raw) {
        return raw.startsWith("host/") || raw.startsWith("host.")
            ? raw.replace('/', '.') : raw;
    }

    /**
     * The pinned oracle-agreement record: the baseline's disagreements (an
     * empty list means every leg agrees).
     */
    private static final List<String> PINNED_ORACLE_DISAGREEMENTS = List.of();

    private static void testNegativeControls() throws Exception {
        System.out.println("-- the negative controls: the guard seed, the perturbed "
            + "field, the removed row, and the fabricated skip --");

        // (a) A scratch fixture carrying an unrealized construct fails by
        // fixture and target with the guard rule named, stages nothing, and
        // leaves the prior artifact set byte-identical.
        for (Target target : Target.values()) {
            Path scratch = Files.createTempDirectory("bytes-drive-guard-");
            try {
                Files.createDirectories(scratch.resolve("src"));
                Files.writeString(scratch.resolve("deal.json"), """
                    {
                      "languageVersion": "1.2",
                      "moduleRoots": ["src"],
                      "output": "out",
                      "backend": "%s"
                    }
                    """.formatted(target.laneName()));
                Files.writeString(scratch.resolve("src/main.deal"), GUARD_SEED,
                    StandardCharsets.UTF_8);
                Files.createDirectories(scratch.resolve("out"));
                Files.writeString(scratch.resolve("out/main.lua"),
                    "-- the prior artifact set\n", StandardCharsets.UTF_8);
                String before = Files.readString(scratch.resolve("out/main.lua"),
                    StandardCharsets.UTF_8);
                Project scratchProject = new Project(scratch, scratch.resolve("src"),
                    scratch.resolve("src/main.deal"), "main", Map.of(), Map.of(),
                    List.of(), List.of(), scratch.resolve("out"));
                List<String> diagnostics = new ArrayList<>();
                boolean compiled = compile(scratchProject, "the guard seed",
                    target, diagnostics);
                check(!compiled, "the guard seed fails closed on "
                    + target.laneName());
                check(diagnostics.stream().anyMatch(d -> d.startsWith("E6005 ")
                        && d.contains("CONSTRUCT_UNLOWERED")),
                    "the guard seed names E6005 CONSTRUCT_UNLOWERED on "
                        + target.laneName() + ": " + diagnostics);
                check(!Files.exists(scratch.resolve("out/deal/runtime.lua")),
                    "the failing compile stages no deployment copy on "
                        + target.laneName());
                checkEq(before, Files.readString(scratch.resolve("out/main.lua"),
                        StandardCharsets.UTF_8),
                    "a failing compile leaves the prior artifact set "
                        + "byte-identical on " + target.laneName());
            } finally {
                deleteRecursively(scratch);
            }
        }

        // (b) A perturbed captured field fails by field name.
        String fixture = BYTES_DIR + "/bytes-index-bounds";
        Leg leg = RECORD.get(fixture).get(Target.LUAJIT);
        check(leg != null && leg.capture() != null, fixture
            + " carries a captured tuple for the control");
        if (leg != null && leg.capture() != null) {
            Capture perturbed = new Capture(leg.capture().code(),
                "a perturbed message", leg.capture().sourceFile(),
                leg.capture().line(), leg.capture().column(),
                leg.capture().expected(), leg.capture().actual());
            Execution synthetic = new Execution(1, "", "", perturbed, false);
            List<Divergence> divergences = compare(perturbed,
                pinnedOf(fixture), synthetic);
            checkEq(List.of("message"), divergences.stream()
                    .map(Divergence::field).toList(),
                "a perturbed captured field is reported by field name");
        }

        // (c) A removed row, a greenwashed divergent leg, a delegated fixture
        // treated as passed, and an ffi/016 JVM leg that reports a published
        // artifact each fail the baseline record.
        Map<String, Map<Target, Leg>> removed = new LinkedHashMap<>(RECORD);
        removed.remove(fixture);
        check(baselineFailures(removed, DELEGATED_RECORD, PIN_EXACT_LEGS,
                DIVERGENT_LEGS, COMPILE_REJECT_LEGS).stream()
                .anyMatch(f -> f.contains("no row for " + fixture)),
            "a removed accounting row fails the baseline record");
        Map<String, Map<Target, Leg>> greenwashed = new LinkedHashMap<>(RECORD);
        String divergentFixture = BYTES_DIR
            + "/bytes-dynamic-nested-first-element-e8003";
        Map<Target, Leg> mutated = new LinkedHashMap<>(
            greenwashed.get(divergentFixture));
        Leg divergentLeg = mutated.get(Target.LUAJIT);
        mutated.put(Target.LUAJIT, new Leg(divergentLeg.target(),
            divergentLeg.compiled(), divergentLeg.compileReject(),
            divergentLeg.compileDiagnostics(), divergentLeg.artifact(),
            divergentLeg.capture(), List.of()));
        greenwashed.put(divergentFixture, mutated);
        check(baselineFailures(greenwashed, DELEGATED_RECORD, PIN_EXACT_LEGS + 1,
                DIVERGENT_LEGS - 1, COMPILE_REJECT_LEGS).stream()
                .anyMatch(f -> f.contains("bytes-dynamic-nested-first-element")),
            "a greenwashed divergent leg fails by fixture");
        Map<String, Map<Target, Leg>> delegatedAsPassed =
            new LinkedHashMap<>(RECORD);
        Map<Target, Leg> delegatedLegs = new LinkedHashMap<>();
        for (Target target : Target.values()) {
            delegatedLegs.put(target, new Leg(target, true, false, List.of(),
                "out", null, List.of()));
        }
        delegatedAsPassed.put(DELEGATED.get(0), delegatedLegs);
        check(!baselineFailures(delegatedAsPassed, DELEGATED_RECORD,
                PIN_EXACT_LEGS + 2, DIVERGENT_LEGS, COMPILE_REJECT_LEGS).isEmpty(),
            "a delegated fixture treated as passed fails the baseline record");
        Map<String, Map<Target, Leg>> rejectPublished = new LinkedHashMap<>(RECORD);
        Map<Target, Leg> ffiLegs = new LinkedHashMap<>(rejectPublished.get(FFI_BYTES));
        Leg rejectLeg = ffiLegs.get(Target.JVM);
        ffiLegs.put(Target.JVM, new Leg(Target.JVM, true, true,
            rejectLeg.compileDiagnostics(), "out/Main.java", null, List.of()));
        rejectPublished.put(FFI_BYTES, ffiLegs);
        check(baselineFailures(rejectPublished, DELEGATED_RECORD, PIN_EXACT_LEGS,
                DIVERGENT_LEGS, COMPILE_REJECT_LEGS).stream()
                .anyMatch(f -> f.contains("publishes no artifact")),
            "an ffi/016 JVM leg that reports a published artifact fails the "
                + "baseline record");
        Map<String, Map<Target, Leg>> fabricatedExecution =
            new LinkedHashMap<>(RECORD);
        Map<Target, Leg> fabricatedLegs = new LinkedHashMap<>(
            fabricatedExecution.get(FFI_BYTES));
        Leg fabricatedLeg = fabricatedLegs.get(Target.JVM);
        fabricatedLegs.put(Target.JVM, new Leg(Target.JVM, true, false,
            fabricatedLeg.compileDiagnostics(), "out/Main.java", null, List.of()));
        fabricatedExecution.put(FFI_BYTES, fabricatedLegs);
        check(baselineFailures(fabricatedExecution, DELEGATED_RECORD,
                PIN_EXACT_LEGS, DIVERGENT_LEGS,
                COMPILE_REJECT_LEGS - 1).stream()
                .anyMatch(f -> f.contains("pinned compile-reject leg")),
            "an ffi/016 JVM leg that reports an execution instead of the pinned "
                + "compile reject fails the baseline record");
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

    // =========================================================================
    // Utilities
    // =========================================================================

    private static void deleteRecursively(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            for (Path entry : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(entry);
            }
        } catch (Exception ignored) {
            // Best-effort temp cleanup; never part of a test result.
        }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== The Lane-Equivalent Bytes Production Drive "
            + "(ISSUE-0707) ===");
        testCorpusInventory();
        testProductionDrive();
        testExternalsIdentityConvention();
        testJsonStringifyBytesArm();
        testBaseline();
        testOracleAgreement();
        testInvariants();
        testNegativeControls();
        System.out.println();
        System.out.println("passed: " + passed + ", failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }
}
