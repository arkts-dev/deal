package deal.test.conformance;

import deal.ast.ArrayType;
import deal.ast.ClassDeclaration;
import deal.ast.ExportDeclaration;
import deal.ast.FunctionDeclaration;
import deal.ast.ImportDeclaration;
import deal.ast.NamedType;
import deal.ast.NullableType;
import deal.ast.ProgramNode;
import deal.ast.StatementNode;
import deal.ast.TypeNode;
import deal.checker.CheckResult;
import deal.checker.ModuleResolver;
import deal.checker.NameResolver;
import deal.checker.Symbol;
import deal.checker.SymbolTable;
import deal.checker.TypeChecker;
import deal.codegen.lua.LuaBackend;
import deal.diagnostics.CompilerDiagnostic;
import deal.identity.CanonicalClassIdentity;
import deal.identity.CanonicalModuleIdentity;
import deal.identity.ProjectModuleIdentity;
import deal.lexer.LexResult;
import deal.lexer.Lexer;
import deal.module.ExportExtractor;
import deal.module.ModuleIdentityResolver;
import deal.module.ModuleShapeValidator;
import deal.module.StdlibModuleResolver;
import deal.parser.ParseResult;
import deal.parser.Parser;
import deal.semantic.ir.CanonicalJson;
import deal.semantic.ir.SemanticIrTextDecodeException;
import deal.semantic.ir.SemanticProfile;
import deal.test.ConformanceHarnessMetadata;
import deal.test.LegacyProfileRegressionCatalog;
import deal.types.Type;
import deal.types.Types;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * The LuaJIT lane of the v1.2 differential gate (ISSUE-0354; design
 * {@code v12-zero-skip-conformance-gate} G4/G5): the absorbed
 * {@code ConformanceTest} compile → {@link LuaBackend} → {@code luajit}
 * path executed under the Shared Lane Contract.
 *
 * <p>Per case the lane:</p>
 * <ol>
 *   <li>Probes the required tool {@code luajit} once (G3): a missing or
 *       broken-but-present tool is {@link MismatchClass#TOOL_MISSING},
 *       never a skip.</li>
 *   <li>Compiles the fixture plus every transitively imported companion
 *       module and the {@code host/<name>} triplet declarations with the
 *       real frontend (lexer → parser → module shape gate → name
 *       resolution → type checker), companions first so the per-case
 *       identity index classifies every module before its importer.
 *       Every module compiles under the case profile resolved by
 *       {@code LegacyProfileRegressionCatalog.profileFor} — the same A5
 *       per-case profile selection the absorbed runner uses. A compile
 *       failure of any module is a lane compile failure (an
 *       infrastructure outcome with the diagnostic codes), never an
 *       execution outcome.</li>
 *   <li>Generates real Lua artifacts through the real {@link LuaBackend}
 *       (the fixture as the v1.2 entry module, so the backend itself
 *       invokes {@code main(): null} exactly once at chunk end) and
 *       asserts artifact presence before execution — a missing artifact
 *       is {@link MismatchClass#ARTIFACT_MISSING}, never a fabricated
 *       result.</li>
 *   <li>Deploys a fresh temp workspace — the lane runner, the generated
 *       fixture module, {@code deal/runtime.lua}, the generated companion
 *       modules, the {@code std/*.lua} library, and the
 *       {@code host/<name>.lua} triplet implementations — and executes
 *       the runner in a real {@code luajit} subprocess with stdout and
 *       stderr captured as separate byte streams. The gate's dispatcher
 *       owns the harness deadline; on interrupt the lane terminates the
 *       spawned subprocess (the Lane contract requirement).</li>
 *   <li>Invocation contract (G4.4): the lane runner auto-invokes each
 *       non-{@code $} zero-arity exported wrapper exactly once, in
 *       declaration order (derived from the compiled AST — the unordered
 *       {@code pairs} auto-invocation of the absorbed runner is
 *       replaced); {@code main} is never re-invoked (the backend's entry
 *       contract already invoked it); return values are discarded — no
 *       lane prints results; an async export's invocation drives the
 *       operation to completion through the runtime's synchronous async
 *       chain before the verdict.</li>
 *   <li>Error framing (G4.6): on an uncaught DEAL error the lane writes
 *       to stdout exactly {@code DEAL_ERROR_CODE: <code>} then
 *       {@code DEAL_ERROR_SNAPSHOT: <canonical JSON>} and exits 1;
 *       success exits 0. The canonical snapshot serialization is the
 *       shared {@link ErrorSnapshot} serializer — the same helper the
 *       JVM and JS lanes reuse verbatim, so the three lanes can never
 *       drift on serialization. The sidecar's Error Expectation is the
 *       authoritative field set: the lane emits the mandatory
 *       {@code code}/{@code message} plus exactly the span group
 *       ({@code sourceFile}/{@code line}/{@code column}) and the
 *       optional fields ({@code expected}, {@code actual},
 *       {@code frames}, {@code cause}) the sidecar pins and suppresses
 *       every unpinned one — a pinned field the captured error does not
 *       carry is never fabricated, so the comparison fails honestly.
 *       The one sanctioned span-less shape — the locked time selector's
 *       retained {@code nowMillis} wrapper raising E8004 with no
 *       file/line/column ({@code luajit-time-selector-disposition},
 *       Failure and operations) — pairs with a sidecar that omits the
 *       whole span group, so the lane emits the span-less snapshot
 *       exactly as captured. The runner transports the raw captured
 *       error fields through a workspace payload file; the lane
 *       normalizes and frames them.</li>
 *   <li>{@code sourceFile} normalization (corpus C2): the lane maintains
 *       its per-module deployment map (the absolute path every compiled
 *       module's spans carry ↔ its canonical corpus-relative path plus
 *       its stripped classification-header line count), recorded at
 *       compile time; the captured {@code file} is normalized to the
 *       corpus-relative form and the captured {@code line} is rebased
 *       onto raw corpus-file coordinates (the coordinates the sidecars
 *       pin). An unmappable captured {@code file} value is emitted
 *       verbatim, so the byte comparison fails and surfaces the defect.</li>
 * </ol>
 *
 * <p>The lane changes no existing runner: {@code ConformanceTest} keeps
 * running in {@code run_tests.sh} until the zero-skip flip retires it
 * (G5's temporary-coexistence window). No production file is modified.</p>
 */
public class LuaLane implements Lane {

    /** The lane's backend name (G4: exactly {@code luajit}). */
    public static final String LANE_NAME = "luajit";

    /** The lane runner file name inside the temp workspace. */
    public static final String RUNNER_FILE_NAME = "test_main.lua";

    /** The raw captured-error transport file name inside the workspace. */
    public static final String TRANSPORT_FILE_NAME = "lane_error.json";

    /** The runtime artifact deployed beside the runner (CWD-relative). */
    private static final String RUNTIME_LIBRARY = "deal/runtime.lua";

    /** The stdlib surface directory deployed into the workspace. */
    private static final String STD_DIRECTORY = "std";

    private final Path conformanceRoot;
    private final Path hostFixturesRoot;
    private final Path runtimeLibraryPath;
    private final Path stdlibDirectory;

    /** The cached tool probe: null until the first probe (G3). */
    private volatile Boolean luajitProbe;

    /**
     * Creates the lane over one conformance root. The repository runtime
     * artifacts ({@code deal/runtime.lua}, {@code std/*.lua}) resolve
     * from the current working directory — the same surface the absorbed
     * {@code ConformanceTest} runner uses.
     *
     * @param conformanceRoot the conformance root (any path form; the
     *                        lane normalizes it)
     */
    public LuaLane(Path conformanceRoot) {
        this.conformanceRoot = Objects.requireNonNull(conformanceRoot,
            "conformanceRoot must not be null").toAbsolutePath().normalize();
        this.hostFixturesRoot = this.conformanceRoot.resolve("host-fixtures");
        this.runtimeLibraryPath = Path.of(RUNTIME_LIBRARY)
            .toAbsolutePath().normalize();
        this.stdlibDirectory = Path.of(STD_DIRECTORY)
            .toAbsolutePath().normalize();
    }

    @Override
    public String name() {
        return LANE_NAME;
    }

    // =========================================================================
    // Lane execution
    // =========================================================================

    @Override
    public LaneExecution execute(LaneCase laneCase) throws Exception {
        Objects.requireNonNull(laneCase, "laneCase must not be null");

        // Dispatch guards: the Lua lane is registered under its own name
        // and the schema reserves compile-reject for the jvm/js legs.
        if (!LANE_NAME.equals(laneCase.backend())) {
            return new LaneExecution.Infrastructure(MismatchClass.HARNESS_DEFECT,
                "the Lua lane was dispatched for backend "
                    + laneCase.backend() + " — the lane serves exactly "
                    + LANE_NAME);
        }
        if (!(laneCase.expectation()
                instanceof SidecarExpectations.RuntimeExpectation.Executed
                    expected)) {
            return new LaneExecution.Infrastructure(MismatchClass.HARNESS_DEFECT,
                "the Lua lane was dispatched against a compile-reject "
                    + "expectation — the sidecar schema reserves the "
                    + "sanctioned C6 rejection for the jvm and js legs");
        }

        // G3: the required tool must be present and functional; absence is
        // a failure, never a skip.
        if (!luajitAvailable()) {
            return new LaneExecution.Infrastructure(MismatchClass.TOOL_MISSING,
                "the required tool luajit is missing or broken — the lane "
                    + "never skips (G3)");
        }

        // Compile the fixture plus its companions with the real frontend
        // and generate the real Lua artifacts (companions first).
        LuaCompilation compilation = new LuaCompilation(laneCase);
        CompilationOutcome compileOutcome =
            compilation.compileCase();
        if (compileOutcome.failure() != null) {
            return new LaneExecution.Infrastructure(MismatchClass.PROCESS_FAILURE,
                compileOutcome.failure());
        }
        if (compileOutcome.artifactMissing() != null) {
            return new LaneExecution.Infrastructure(MismatchClass.ARTIFACT_MISSING,
                compileOutcome.artifactMissing());
        }
        CompiledModule entry = compileOutcome.entry();
        List<String> orderedExports = orderedZeroArityExports(
            entry.program);

        // Deploy the workspace and execute the real luajit subprocess.
        Path workspace = Files.createTempDirectory("deal_conf_");
        try {
            List<Path> deployed = deployWorkspace(workspace, entry,
                compileOutcome.companions());
            if (deployed == null) {
                return new LaneExecution.Infrastructure(
                    MismatchClass.ARTIFACT_MISSING,
                    "a required repository artifact is missing: "
                        + RUNTIME_LIBRARY + " or the " + STD_DIRECTORY
                        + " surface directory — the lane never fabricates "
                        + "a deployed artifact");
            }
            beforeExecution(workspace);
            List<String> missing = missingArtifacts(workspace, deployed);
            if (!missing.isEmpty()) {
                return new LaneExecution.Infrastructure(
                    MismatchClass.ARTIFACT_MISSING,
                    "the generated artifacts are missing before execution: "
                        + String.join(", ", missing)
                        + " — the lane never substitutes a fabricated result");
            }
            SubprocessResult subprocess = runSubprocess(workspace);
            return assembleOutcome(expected, subprocess, workspace,
                compilation);
        } finally {
            deleteRecursively(workspace);
        }
    }

    /** One lane compilation outcome: the modules or one failure detail. */
    private record CompilationOutcome(
            String failure,
            String artifactMissing,
            CompiledModule entry,
            List<CompiledModule> companions) {

        CompilationOutcome {
            Objects.requireNonNull(companions, "companions must not be null");
            companions = List.copyOf(companions);
        }

        static CompilationOutcome failure(String detail) {
            return new CompilationOutcome(detail, null, null, List.of());
        }

        static CompilationOutcome artifactMissing(String detail) {
            return new CompilationOutcome(null, detail, null, List.of());
        }
    }

    // =========================================================================
    // Compilation (the absorbed ConformanceTest compile path)
    // =========================================================================

    /**
     * One compiled module: its corpus-relative path, its absolute source
     * path (the file value every emitted span carries), its generated Lua
     * source, its parsed program, its stripped-header line count, and the
     * checker artifacts the module resolver consumes.
     */
    private static final class CompiledModule {
        final String corpusPath;
        final String luaSource;
        final ProgramNode program;
        final int headerLinesStripped;
        final SymbolTable symbolTable;
        final NameResolver nameResolver;

        CompiledModule(String corpusPath, String luaSource,
                ProgramNode program, int headerLinesStripped,
                SymbolTable symbolTable, NameResolver nameResolver) {
            this.corpusPath = corpusPath;
            this.luaSource = luaSource;
            this.program = program;
            this.headerLinesStripped = headerLinesStripped;
            this.symbolTable = symbolTable;
            this.nameResolver = nameResolver;
        }
    }

    /**
     * The per-module deployment entry of the lane's deployment map (corpus
     * C2): the canonical corpus-relative path plus the stripped
     * classification-header line count used to rebase captured line
     * values onto raw corpus-file coordinates.
     */
    record DeploymentEntry(String corpusPath, int headerLinesStripped) {

        DeploymentEntry {
            Objects.requireNonNull(corpusPath, "corpusPath must not be null");
        }
    }

    /**
     * The per-case Lua compilation engine: mirrors the absorbed
     * {@code ConformanceTest} {@code CompanionCatalog} +
     * {@code HostRegistry} + {@code ConformanceModuleResolver} surface —
     * one case profile, a per-case identity index, companions compiled
     * depth-first before their importers, host triplet declarations, and
     * the stdlib export surface.
     */
    final class LuaCompilation {

        /** The compiled modules keyed by absolute source path. */
        private final Map<Path, CompiledModule> cache = new LinkedHashMap<>();
        private final Set<Path> inProgress = new HashSet<>();
        /** The deployment map: absolute source path → deployment entry. */
        private final Map<String, DeploymentEntry> deploymentMap =
            new LinkedHashMap<>();
        private final SemanticProfile profile;
        private final HostRegistry hostRegistry;
        private final Map<String, Map<String, Type>> stdlibExports;

        /**
         * The per-case canonical identity surface (the absorbed
         * {@code CompanionCatalog.classifyModulePath} surface).
         */
        private final Map<String, CanonicalModuleIdentity> moduleIdentities =
            new LinkedHashMap<>();

        LuaCompilation(LaneCase laneCase) {
            this.currentCase = laneCase;
            this.profile = LegacyProfileRegressionCatalog.profileFor(
                currentCase.fixturePath());
            this.hostRegistry = new HostRegistry(profile);
            this.stdlibExports = StdlibModuleResolver.stdlibExports(
                stdlibDirectory.toString());
            moduleIdentities.put("",
                CanonicalModuleIdentity.BuiltinModule.INSTANCE);
        }

        private final LaneCase currentCase;

        CompilationOutcome compileCase() {
            LaneCase laneCase = currentCase;
            Path fixtureFile = laneCase.fixtureFile()
                .toAbsolutePath().normalize();
            Path resolved = conformanceRoot.resolve(laneCase.fixturePath())
                .toAbsolutePath().normalize();
            if (!fixtureFile.equals(resolved)) {
                return CompilationOutcome.failure(
                    "harness inconsistency: the dispatched fixture file "
                        + fixtureFile + " does not resolve to the corpus "
                        + "module " + laneCase.fixturePath());
            }
            Map<String, SidecarSchemaValidator.CompilationModule> setByPath =
                new HashMap<>();
            for (SidecarSchemaValidator.CompilationModule module
                    : laneCase.compilationSet()) {
                setByPath.put(module.corpusPath(), module);
            }
            this.setByPath = setByPath;

            CompiledModule entry = compileModule(laneCase.fixturePath(), true);
            if (compileFailure != null) {
                return CompilationOutcome.failure(compileFailure);
            }
            if (artifactMissingDetail != null) {
                return CompilationOutcome.artifactMissing(
                    artifactMissingDetail);
            }
            if (entry == null) {
                return CompilationOutcome.failure(
                    "the fixture " + laneCase.fixturePath() + " failed to "
                        + "compile (cycle or unreadable source)");
            }
            List<CompiledModule> companions = new ArrayList<>();
            for (CompiledModule module : cache.values()) {
                if (!module.corpusPath.equals(laneCase.fixturePath())) {
                    companions.add(module);
                }
            }
            return new CompilationOutcome(null, null, entry, companions);
        }

        private Map<String, SidecarSchemaValidator.CompilationModule> setByPath;
        private String compileFailure;

        CompiledModule compileModule(String corpusPath, boolean isEntry) {
            SidecarSchemaValidator.CompilationModule module =
                setByPath.get(corpusPath);
            if (module == null) {
                compileFailure = "the module " + corpusPath + " is not part "
                    + "of the fixture's compilation set (harness "
                    + "inconsistency)";
                return null;
            }
            Path file = conformanceRoot.resolve(corpusPath)
                .toAbsolutePath().normalize();
            if (!Files.exists(file)) {
                compileFailure = "the corpus module " + corpusPath
                    + " does not exist on disk";
                return null;
            }
            if (inProgress.contains(file)) {
                compileFailure = "the module " + corpusPath
                    + " is part of an import cycle";
                return null;
            }
            CompiledModule cached = cache.get(file);
            if (cached != null) {
                return cached;
            }
            inProgress.add(file);
            try {
                return compile(file, corpusPath, module, isEntry);
            } finally {
                inProgress.remove(file);
            }
        }

        private CompiledModule compile(Path file, String corpusPath,
                SidecarSchemaValidator.CompilationModule provided,
                boolean isEntry) {
            try {
                String raw = Files.readString(file);
                String source = ConformanceHarnessMetadata
                    .stripClassificationHeaders(raw);
                if (!source.equals(provided.source())) {
                    compileFailure = "harness inconsistency: the stripped "
                        + "source of " + corpusPath + " differs from the "
                        + "gate core's compilation set (the lane compiles "
                        + "exactly the sources the sidecar validation "
                        + "verified)";
                    return null;
                }
                int headerLinesStripped = raw.split("\n", -1).length
                    - source.split("\n", -1).length;
                String filename = file.toString();

                LexResult lex = new Lexer(source, filename).tokenize();
                if (lex.hasErrors()) {
                    compileFailure = "the lane compilation failed for "
                        + corpusPath + ": " + describeDiagnostics(
                            lex.diagnostics());
                    return null;
                }

                Parser parser = new Parser(lex.tokens(), filename, profile,
                    lex.directiveEvents());
                ParseResult parseResult = parser.parse();
                if (parseResult.hasErrors()) {
                    compileFailure = "the lane compilation failed for "
                        + corpusPath + ": " + describeDiagnostics(
                            parseResult.diagnostics());
                    return null;
                }

                // The module shape gate runs on the entry module exactly
                // like the absorbed runner's diagnostics pass; companions
                // compile through the catalog surface (no shape pass).
                if (isEntry) {
                    List<CompilerDiagnostic> shape = ModuleShapeValidator
                        .validate(parseResult.program(), filename, false);
                    if (shape.stream().anyMatch(
                            d -> "error".equals(d.severity()))) {
                        compileFailure = "the lane compilation failed for "
                            + corpusPath + ": " + describeDiagnostics(shape);
                        return null;
                    }
                }

                // Import discovery: host triplet imports, and companion
                // imports resolved against the importing module's
                // directory (the absorbed resolveCompanionPath rules).
                Path fileDir = file.getParent();
                Map<String, String> importResolutions = new LinkedHashMap<>();
                Set<String> companionCorpusPaths = new LinkedHashSet<>();
                Map<String, Map<String, Type>> hostModules =
                    new LinkedHashMap<>();
                for (StatementNode stmt
                        : parseResult.program().statements()) {
                    if (!(stmt instanceof ImportDeclaration imp)) {
                        continue;
                    }
                    String importPath = imp.modulePath();
                    if (hostRegistry.isHostModule(importPath)) {
                        try {
                            hostModules.put(importPath, hostRegistry
                                .forModule(importPath).exports());
                        } catch (ModuleResolver.ModuleNotFoundException e) {
                            compileFailure = "the lane compilation failed for "
                                + corpusPath + ": the host triplet "
                                + importPath + " cannot be loaded: "
                                + e.getMessage();
                            return null;
                        }
                        continue;
                    }
                    Path resolved = resolveCompanionPath(importPath, fileDir);
                    if (resolved != null) {
                        String depCorpusPath = CorpusDiscovery.slash(
                            conformanceRoot.relativize(
                                resolved.toAbsolutePath().normalize()));
                        importResolutions.put(importPath,
                            moduleNameFor(resolved));
                        companionCorpusPaths.add(depCorpusPath);
                    }
                }

                // Compile each companion dependency transitively,
                // depth-first, before this module (the absorbed catalog
                // order — the identity index must classify every
                // companion before its importer).
                for (String dep : new ArrayList<>(companionCorpusPaths)) {
                    if (compileModule(dep, false) == null) {
                        if (artifactMissingDetail != null) {
                            return null; // the artifact detail names the
                                         // failing companion
                        }
                        if (compileFailure == null) {
                            compileFailure = "the companion " + dep
                                + " of " + corpusPath
                                + " failed to compile";
                        }
                        return null;
                    }
                }

                // Name resolution rooted at this module, over the case
                // identity surface.
                ConformanceModuleResolver resolver =
                    new ConformanceModuleResolver(fileDir, this, profile);
                NameResolver nr = new NameResolver(filename, resolver,
                    new HashSet<>(), this::classifyModulePath);
                SymbolTable symTable;
                try {
                    symTable = nr.resolve(parseResult.program());
                } catch (Exception e) {
                    compileFailure = "the lane compilation failed for "
                        + corpusPath + ": NameResolver threw "
                        + e.getClass().getSimpleName() + ": "
                        + e.getMessage();
                    return null;
                }
                if (nr.diagnostics().stream().anyMatch(
                        d -> "error".equals(d.severity()))) {
                    compileFailure = "the lane compilation failed for "
                        + corpusPath + ": " + describeDiagnostics(
                            nr.diagnostics());
                    return null;
                }

                CheckResult result = TypeChecker.check(filename, symTable,
                    nr, parseResult.program());
                if (result.hasErrors()) {
                    compileFailure = "the lane compilation failed for "
                        + corpusPath + ": " + describeDiagnostics(
                            result.diagnostics());
                    return null;
                }

                // Register this module's paths before backend construction
                // (the absorbed registerModulePath surface).
                registerModulePath(filename, hostModules);
                LuaBackend backend = new LuaBackend(result.typeMap(),
                    result.symbolTable(), filename, filename,
                    identityIndex(), profile);
                String luaSource = backend.generateFromInstance(
                    parseResult.program(), isEntry, importResolutions,
                    hostModules);
                for (CompilerDiagnostic diagnostic
                        : backend.diagnostics()) {
                    if ("error".equals(diagnostic.severity())) {
                        return recordArtifactMissing(corpusPath,
                            describeDiagnostics(backend.diagnostics()));
                    }
                }
                // The absorbed ABI invariant: generated Lua carries '$'
                // only inside quoted string keys.
                if (!dollarOnlyInQuotedKeys(luaSource)) {
                    return recordArtifactMissing(corpusPath,
                        "the generated Lua of " + corpusPath
                            + " contains '$' outside a quoted string key "
                            + "(the lua-abi-emission-layer invariant)");
                }
                CompiledModule compiled = new CompiledModule(corpusPath,
                    luaSource, parseResult.program(), headerLinesStripped,
                    symTable, nr);
                cache.put(file, compiled);
                deploymentMap.put(filename, new DeploymentEntry(corpusPath,
                    headerLinesStripped));
                return compiled;
            } catch (IOException e) {
                compileFailure = "the lane compilation failed for "
                    + corpusPath + ": cannot read " + file + ": "
                    + e.getMessage();
                return null;
            } catch (Exception e) {
                compileFailure = "the lane compilation failed for "
                    + corpusPath + ": unexpected "
                    + e.getClass().getSimpleName() + ": " + e.getMessage();
                return null;
            }
        }

        /** Records an artifact-missing outcome for the compilation. */
        private CompiledModule recordArtifactMissing(String corpusPath,
                String detail) {
            artifactMissingDetail = "the generated Lua artifact of "
                + corpusPath + " is missing or invalid: " + detail
                + " — the lane never substitutes a fabricated artifact";
            return null;
        }

        private String artifactMissingDetail;

        private ModuleIdentityResolver.IdentityIndex identityIndex() {
            return ModuleIdentityResolver.buildIndex(moduleIdentities);
        }

        private void registerModulePath(String modulePath,
                Map<String, Map<String, Type>> hostModules) {
            if (modulePath != null && !modulePath.isEmpty()
                    && !moduleIdentities.containsKey(modulePath)) {
                moduleIdentities.put(modulePath, classifyModulePath(modulePath));
            }
            for (String raw : hostModules.keySet()) {
                String dotted = raw.replace('/', '.');
                if (!moduleIdentities.containsKey(dotted)) {
                    moduleIdentities.put(dotted, classifyModulePath(dotted));
                }
            }
        }

        /**
         * The absorbed harness identity classification: host triplet
         * modules classify as externals with the dotted raw specifier;
         * corpus modules classify as project modules under the fixed
         * {@code conformance} root with their corpus-relative components
         * (machine-independent class atoms).
         */
        private CanonicalModuleIdentity classifyModulePath(String modulePath) {
            if (modulePath == null || modulePath.isEmpty()) {
                return CanonicalModuleIdentity.BuiltinModule.INSTANCE;
            }
            if (hostRegistry.isHostModule(modulePath)) {
                return new CanonicalModuleIdentity.ExternalModule(
                    modulePath.replace('/', '.'));
            }
            Path p = Path.of(modulePath).toAbsolutePath().normalize();
            Path rel;
            try {
                rel = conformanceRoot.relativize(p);
            } catch (IllegalArgumentException e) {
                rel = p;
            }
            if (rel.startsWith("..")) {
                rel = p;
            }
            List<String> components = new ArrayList<>();
            Path parent = rel.getParent();
            if (parent != null) {
                for (Path part : parent) {
                    String c = part.toString();
                    if (!c.isEmpty() && !c.equals("/")) {
                        components.add(c);
                    }
                }
            }
            Path fileName = rel.getFileName();
            if (fileName != null && !fileName.toString().isEmpty()) {
                components.add(fileName.toString());
            }
            String anchor = parent != null
                ? parent.toAbsolutePath().normalize().toString()
                : modulePath;
            return new CanonicalModuleIdentity.ProjectModule(
                new ProjectModuleIdentity("conformance", anchor, components));
        }

        /**
         * The deployment map of the case: absolute source path → the
         * canonical corpus-relative path plus the stripped-header line
         * count (recorded at compile time — corpus C2).
         */
        Map<String, DeploymentEntry> deploymentMap() {
            return deploymentMap;
        }
    }

    /**
     * Resolves a relative import against the importing module's directory,
     * mirroring the absorbed {@code resolveCompanionPath}: the raw path,
     * then the {@code .deal} and {@code .d.deal} extensions.
     */
    private static Path resolveCompanionPath(String importPath, Path baseDir) {
        if (!importPath.startsWith("./") && !importPath.startsWith("../")) {
            return null;
        }
        Path resolved = baseDir.resolve(importPath).normalize();
        if (Files.exists(resolved)) {
            return resolved;
        }
        Path withExt = baseDir.resolve(importPath + ".deal").normalize();
        if (Files.exists(withExt)) {
            return withExt;
        }
        Path withDeclExt = baseDir.resolve(importPath + ".d.deal").normalize();
        if (Files.exists(withDeclExt)) {
            return withDeclExt;
        }
        return null;
    }

    /** The Lua module name of a .deal file: its stem (absorbed helper). */
    private static String moduleNameFor(Path path) {
        String name = path.getFileName().toString();
        if (name.endsWith(".d.deal")) {
            return name.substring(0, name.length() - ".d.deal".length());
        }
        if (name.endsWith(".deal")) {
            return name.substring(0, name.length() - ".deal".length());
        }
        return name;
    }

    /** Bounded compile-diagnostic detail (code at line:column). */
    private static String describeDiagnostics(
            List<CompilerDiagnostic> diagnostics) {
        List<String> codes = new ArrayList<>();
        for (CompilerDiagnostic diagnostic : diagnostics) {
            if ("error".equals(diagnostic.severity())) {
                String message = diagnostic.message() == null
                    ? "" : diagnostic.message();
                if (message.length() > 120) {
                    message = message.substring(0, 117) + "...";
                }
                codes.add(diagnostic.code() + " at " + diagnostic.line()
                    + ":" + diagnostic.column() + " (" + message + ")");
            }
        }
        if (codes.isEmpty()) {
            return "no error diagnostics";
        }
        if (codes.size() > 4) {
            return String.join(", ", codes.subList(0, 4)) + ", ... ("
                + codes.size() + " errors)";
        }
        return String.join(", ", codes);
    }

    /**
     * True iff every {@code '$} in the generated Lua sits inside a quoted
     * string key (the absorbed ABI invariant).
     */
    private static boolean dollarOnlyInQuotedKeys(String lua) {
        for (int i = lua.indexOf('$'); i >= 0; i = lua.indexOf('$', i + 1)) {
            int open = lua.lastIndexOf('"', i);
            int close = lua.indexOf('"', i + 1);
            if (open < 0 || close < 0 || open >= i || i >= close) {
                return false;
            }
        }
        return true;
    }

    /**
     * The ordered auto-invocation list (G4.4): every non-{@code $}
     * zero-arity exported function of the entry module in declaration
     * order, excluding {@code main} (the backend's entry contract already
     * invoked it exactly once).
     */
    private static List<String> orderedZeroArityExports(ProgramNode program) {
        List<String> names = new ArrayList<>();
        for (StatementNode stmt : program.statements()) {
            if (!(stmt instanceof ExportDeclaration exp)) {
                continue;
            }
            if (!(exp.declaration() instanceof FunctionDeclaration fn)) {
                continue;
            }
            if (fn.params().isEmpty() && !fn.name().contains("$")
                    && !fn.name().equals("main")) {
                names.add(fn.name());
            }
        }
        return names;
    }

    // =========================================================================
    // Host triplets (the absorbed HostRegistry surface)
    // =========================================================================

    /** One host triplet declaration: exports plus synthesized class symbols. */
    private record HostDeclaration(Map<String, Type> exports,
            Map<String, Symbol.ClassSymbol> classSymbols) {

        HostDeclaration {
            exports = Map.copyOf(exports);
            classSymbols = Map.copyOf(classSymbols);
        }
    }

    /** The per-case host triplet registry (host-module-abi D6 surface). */
    private final class HostRegistry {

        private final Map<String, HostDeclaration> cache = new HashMap<>();
        private final SemanticProfile profile;

        HostRegistry(SemanticProfile profile) {
            this.profile = profile;
        }

        boolean isHostModule(String modulePath) {
            if (modulePath == null || modulePath.isEmpty()) {
                return false;
            }
            Path decl = declarationFileFor(toRawPath(modulePath));
            return decl != null && Files.exists(decl);
        }

        HostDeclaration forModule(String modulePath)
                throws ModuleResolver.ModuleNotFoundException {
            String raw = toRawPath(modulePath);
            HostDeclaration cached = cache.get(raw);
            if (cached != null) {
                return cached;
            }
            Path decl = declarationFileFor(raw);
            if (decl == null || !Files.exists(decl)) {
                throw new ModuleResolver.ModuleNotFoundException(
                    "Module not found: " + modulePath);
            }
            try {
                String source = ConformanceHarnessMetadata
                    .stripClassificationHeaders(Files.readString(decl));
                String filename = decl.toString();

                LexResult lex = new Lexer(source, filename).tokenize();
                if (lex.hasErrors()) {
                    throw new ModuleResolver.ModuleNotFoundException(
                        "Lex errors in " + filename);
                }
                Parser parser = new Parser(lex.tokens(), filename, profile,
                    lex.directiveEvents());
                ParseResult parseResult = parser.parse();
                if (parseResult.hasErrors()) {
                    throw new ModuleResolver.ModuleNotFoundException(
                        "Parse errors in " + filename);
                }

                String dotted = raw.replace('/', '.');
                CanonicalModuleIdentity hostIdentity =
                    new CanonicalModuleIdentity.ExternalModule(dotted);
                Map<String, CanonicalModuleIdentity> classification =
                    new LinkedHashMap<>();
                classification.put(dotted, hostIdentity);
                ExportExtractor extractor = new ExportExtractor(dotted, true,
                    classification::get);
                Map<String, Type> exports =
                    extractor.extract(parseResult.program());

                Map<String, Symbol.ClassSymbol> classSymbols =
                    new LinkedHashMap<>();
                for (StatementNode stmt
                        : parseResult.program().statements()) {
                    if (stmt instanceof ClassDeclaration cd) {
                        classSymbols.put(cd.name(), new Symbol.ClassSymbol(
                            cd.name(), cd.fields(), dotted,
                            new CanonicalClassIdentity(hostIdentity,
                                cd.name())));
                    } else if (stmt instanceof ExportDeclaration exp
                            && exp.declaration()
                                instanceof ClassDeclaration cd) {
                        classSymbols.put(cd.name(), new Symbol.ClassSymbol(
                            cd.name(), cd.fields(), dotted,
                            new CanonicalClassIdentity(hostIdentity,
                                cd.name())));
                    }
                }

                HostDeclaration result = new HostDeclaration(
                    Collections.unmodifiableMap(new LinkedHashMap<>(exports)),
                    Collections.unmodifiableMap(new LinkedHashMap<>(
                        classSymbols)));
                cache.put(raw, result);
                return result;
            } catch (IOException e) {
                throw new ModuleResolver.ModuleNotFoundException("Cannot read: " + decl);
            }
        }

        private String toRawPath(String modulePath) {
            return modulePath.replace('.', '/');
        }

        private Path declarationFileFor(String rawPath) {
            String prefix = "host/";
            if (!rawPath.startsWith(prefix)
                    || rawPath.length() == prefix.length()) {
                return null;
            }
            String name = rawPath.substring(prefix.length());
            if (name.contains("/") || name.contains("\\")) {
                return null;
            }
            return hostFixturesRoot.resolve(name + ".d.deal");
        }
    }

    // =========================================================================
    // Module resolution (the absorbed ConformanceModuleResolver surface)
    // =========================================================================

    /** The lane's module resolver over the case compilation surface. */
    private final class ConformanceModuleResolver implements ModuleResolver {

        private final Path testFileDir;
        private final LuaCompilation compilation;
        private final SemanticProfile profile;
        private final Map<String, Map<String, Type>> stdlibExports;
        private final HostRegistry hostRegistry;

        ConformanceModuleResolver(Path testFileDir,
                LuaCompilation compilation, SemanticProfile profile) {
            this.testFileDir = testFileDir;
            this.compilation = compilation;
            this.profile = profile;
            this.stdlibExports = StdlibModuleResolver.stdlibExports(
                stdlibDirectory.toString());
            this.hostRegistry = compilation.hostRegistry;
        }

        @Override
        public Map<String, Type> resolveModule(String modulePath,
                String importingModule, Set<String> modulesInProgress)
                throws ModuleNotFoundException {
            if (stdlibExports.containsKey(modulePath)) {
                return stdlibExports.get(modulePath);
            }
            if (modulePath.startsWith("std/") && !modulePath.startsWith("./")
                    && !modulePath.startsWith("../")) {
                throw new ModuleResolver.ModuleNotFoundException(
                    "Module not found: '" + modulePath
                        + "' is not a spec-listed stdlib module");
            }
            if (hostRegistry.isHostModule(modulePath)) {
                return hostRegistry.forModule(modulePath).exports();
            }
            Path resolved = resolveRelativePath(modulePath);
            if (resolved != null && Files.exists(resolved)) {
                return resolveFileModule(resolved, modulesInProgress);
            }
            throw new ModuleResolver.ModuleNotFoundException(
                "Module not found: " + modulePath);
        }

        @Override
        public Symbol.ClassSymbol resolveClassSymbol(String className,
                String modulePath, String importingModule)
                throws ModuleNotFoundException {
            if (hostRegistry.isHostModule(modulePath)) {
                return hostRegistry.forModule(modulePath)
                    .classSymbols().get(className);
            }
            if (modulePath == null || modulePath.isEmpty()) {
                return null;
            }
            CompiledModule module = compilation.cache.get(
                Path.of(modulePath).toAbsolutePath().normalize());
            if (module == null || module.symbolTable == null) {
                return null;
            }
            Symbol sym = module.symbolTable.resolve(className);
            if (sym instanceof Symbol.ClassSymbol cs) {
                return cs;
            }
            return null;
        }

        @Override
        public Symbol.ClassSymbol resolveClassSymbol(String className,
                CanonicalModuleIdentity declaringModule,
                String importingModule)
                throws ModuleNotFoundException {
            if (declaringModule
                    instanceof CanonicalModuleIdentity.ExternalModule ext) {
                if (hostRegistry.isHostModule(ext.rawImportSpecifier())) {
                    return hostRegistry.forModule(ext.rawImportSpecifier())
                        .classSymbols().get(className);
                }
                return null;
            }
            for (Map.Entry<Path, CompiledModule> entry
                    : compilation.cache.entrySet()) {
                CompiledModule module = entry.getValue();
                if (module.symbolTable == null) {
                    continue;
                }
                CanonicalModuleIdentity classified =
                    compilation.classifyModulePath(
                        entry.getKey().toString());
                if (!declaringModule.equals(classified)) {
                    continue;
                }
                Symbol sym = module.symbolTable.resolve(className);
                if (sym instanceof Symbol.ClassSymbol cs
                        && cs.identity().moduleIdentity()
                            .equals(declaringModule)) {
                    return cs;
                }
            }
            return null;
        }

        @Override
        public boolean isFunctionExportedFromModule(
                CanonicalModuleIdentity declaringModule, String functionName,
                String importingModule)
                throws ModuleNotFoundException {
            if (declaringModule
                    instanceof CanonicalModuleIdentity.ExternalModule ext
                    && hostRegistry.isHostModule(ext.rawImportSpecifier())) {
                return hostRegistry.forModule(ext.rawImportSpecifier())
                    .exports().containsKey(functionName);
            }
            for (Map.Entry<Path, CompiledModule> entry
                    : compilation.cache.entrySet()) {
                CompiledModule module = entry.getValue();
                if (module.symbolTable == null) {
                    continue;
                }
                CanonicalModuleIdentity classified =
                    compilation.classifyModulePath(
                        entry.getKey().toString());
                if (declaringModule.equals(classified)
                        && module.symbolTable.resolve(functionName) != null) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public Type resolveTypeNodeInModule(TypeNode typeNode,
                String modulePath, String importingModule)
                throws ModuleNotFoundException {
            if (hostRegistry.isHostModule(modulePath)) {
                HostDeclaration decl = hostRegistry.forModule(modulePath);
                return resolveHostTypeNode(typeNode, modulePath, decl);
            }
            if (modulePath == null || modulePath.isEmpty()) {
                return null;
            }
            CompiledModule module = compilation.cache.get(
                Path.of(modulePath).toAbsolutePath().normalize());
            if (module == null || module.nameResolver == null) {
                return null;
            }
            return module.nameResolver.resolveTypeNode(typeNode);
        }

        private Type resolveHostTypeNode(TypeNode typeNode,
                String modulePath, HostDeclaration decl) {
            if (typeNode instanceof NamedType nt) {
                Symbol.ClassSymbol cs = decl.classSymbols().get(nt.name());
                if (cs != null) {
                    return Types.classType(nt.name(), cs.identity());
                }
                return null;
            }
            if (typeNode instanceof ArrayType at) {
                Type elem = resolveHostTypeNode(at.elementType(), modulePath,
                    decl);
                return elem == null ? null : Types.array(elem);
            }
            if (typeNode instanceof NullableType nullable) {
                Type inner = resolveHostTypeNode(nullable.innerType(),
                    modulePath, decl);
                if (inner == null) {
                    return null;
                }
                try {
                    return Types.nullable(inner);
                } catch (IllegalArgumentException e) {
                    return null;
                }
            }
            return null;
        }

        private Path resolveRelativePath(String importPath) {
            if (!importPath.startsWith("./") && !importPath.startsWith("../")) {
                return null;
            }
            Path resolved = testFileDir.resolve(importPath).normalize();
            if (Files.exists(resolved)) {
                return resolved;
            }
            Path withExt = testFileDir.resolve(importPath + ".deal").normalize();
            if (Files.exists(withExt)) {
                return withExt;
            }
            Path withDeclExt =
                testFileDir.resolve(importPath + ".d.deal").normalize();
            if (Files.exists(withDeclExt)) {
                return withDeclExt;
            }
            return null;
        }

        private Map<String, Type> resolveFileModule(Path file,
                Set<String> modulesInProgress) throws ModuleNotFoundException {
            try {
                String source = ConformanceHarnessMetadata
                    .stripClassificationHeaders(Files.readString(file));
                String filename = file.toString();
                boolean isDecl = filename.endsWith(".d.deal");

                LexResult lex = new Lexer(source, filename).tokenize();
                if (lex.hasErrors()) {
                    throw new ModuleResolver.ModuleNotFoundException(
                        "Lex errors in " + filename);
                }
                Parser parser = new Parser(lex.tokens(), filename, profile,
                    lex.directiveEvents());
                ParseResult parseResult = parser.parse();
                if (parseResult.hasErrors()) {
                    throw new ModuleResolver.ModuleNotFoundException(
                        "Parse errors in " + filename);
                }
                ExportExtractor extractor = new ExportExtractor(filename,
                    isDecl, compilation::classifyModulePath);
                return extractor.extract(parseResult.program());
            } catch (IOException e) {
                throw new ModuleResolver.ModuleNotFoundException("Cannot read: " + file);
            }
        }
    }

    // =========================================================================
    // Workspace deployment
    // =========================================================================

    /**
     * Deploys the lane runner (with the generated fixture module inlined),
     * {@code deal/runtime.lua}, the generated companion modules, the
     * {@code std/*.lua} library, and the {@code host/<name>.lua} triplet
     * implementations into the workspace. Returns the deployed artifact
     * paths in deployment order, or {@code null} when a required
     * repository artifact is absent.
     */
    private List<Path> deployWorkspace(Path workspace, CompiledModule entry,
            List<CompiledModule> companions) throws IOException {
        if (!Files.exists(runtimeLibraryPath)
                || !Files.isDirectory(stdlibDirectory)) {
            return null;
        }
        List<Path> deployed = new ArrayList<>();

        // The lane runner with the generated fixture module inlined.
        Path runner = workspace.resolve(RUNNER_FILE_NAME);
        Files.writeString(runner, buildRunner(entry.luaSource,
            orderedZeroArityExports(entry.program)));
        deployed.add(runner);

        // The runtime library.
        Path runtimeDir = workspace.resolve("deal");
        Files.createDirectories(runtimeDir);
        Path runtimeTarget = runtimeDir.resolve("runtime.lua");
        Files.copy(runtimeLibraryPath, runtimeTarget);
        deployed.add(runtimeTarget);

        // Companion modules: one <stem>.lua per companion, so the
        // runner's package.path ('./?.lua') resolves every require.
        for (CompiledModule companion : companions) {
            String moduleName = moduleNameFor(Path.of(companion.corpusPath));
            Path companionFile = workspace.resolve(moduleName + ".lua");
            Files.writeString(companionFile, companion.luaSource);
            deployed.add(companionFile);
        }

        // Host triplet implementations (<name>.lua → <tmp>/host/<name>.lua)
        // so raw slash-form require specifiers resolve through
        // './?.lua' → './host/<name>.lua' (host-module-abi D6).
        if (Files.isDirectory(hostFixturesRoot)) {
            Path targetHostDir = workspace.resolve("host");
            Files.createDirectories(targetHostDir);
            try (var stream = Files.list(hostFixturesRoot)) {
                for (Path hostFile : stream
                        .filter(p -> p.toString().endsWith(".lua"))
                        .sorted()
                        .toList()) {
                    Path target = targetHostDir.resolve(
                        hostFile.getFileName());
                    Files.copy(hostFile, target);
                    deployed.add(target);
                }
            }
        }

        // The stdlib library surface.
        Path targetStdDir = workspace.resolve("std");
        Files.createDirectories(targetStdDir);
        try (var stream = Files.list(stdlibDirectory)) {
            for (Path stdFile : stream
                    .filter(p -> p.toString().endsWith(".lua"))
                    .sorted()
                    .toList()) {
                Path target = targetStdDir.resolve(stdFile.getFileName());
                Files.copy(stdFile, target);
                deployed.add(target);
            }
        }
        return deployed;
    }

    /**
     * Returns the deployed artifact paths that are absent on disk, in
     * deployment order (empty when every artifact is present).
     */
    private static List<String> missingArtifacts(Path workspace,
            List<Path> deployed) {
        List<String> missing = new ArrayList<>();
        for (Path artifact : deployed) {
            if (!Files.exists(artifact)) {
                missing.add(workspace.relativize(artifact).toString()
                    .replace('\\', '/'));
            }
        }
        return missing;
    }

    private static void deleteRecursively(Path directory) {
        try (var stream = Files.walk(directory)) {
            stream.sorted(java.util.Comparator.reverseOrder())
                .forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ignored) {
                        // Temp-workspace cleanup; never a verdict input.
                    }
                });
        } catch (IOException ignored) {
            // Temp-workspace cleanup; never a verdict input.
        }
    }

    // =========================================================================
    // Tool probe (G3)
    // =========================================================================

    /**
     * Probes the required {@code luajit} tool once per lane instance: the
     * tool must exist and exit zero on the version probe (a broken-but-
     * present tool is {@code TOOL_MISSING} too — G3).
     */
    protected boolean luajitAvailable() {
        Boolean probe = luajitProbe;
        if (probe == null) {
            synchronized (this) {
                probe = luajitProbe;
                if (probe == null) {
                    probe = probeLuajit();
                    luajitProbe = probe;
                }
            }
        }
        return probe;
    }

    private static boolean probeLuajit() {
        try {
            Process process = new ProcessBuilder("luajit", "-v").start();
            process.getInputStream().readAllBytes();
            process.getErrorStream().readAllBytes();
            return process.waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            Thread.interrupted(); // clear the residual flag (no-op normally)
            return false;
        }
    }

    // =========================================================================
    // Subprocess execution
    // =========================================================================

    /** One subprocess run: the captured stdout/stderr bytes and exit code. */
    private record SubprocessResult(byte[] stdout, byte[] stderr, int exitCode) {

        SubprocessResult {
            Objects.requireNonNull(stdout, "stdout must not be null");
            Objects.requireNonNull(stderr, "stderr must not be null");
        }
    }

    /** Drains one process stream into memory on its own thread. */
    private static final class StreamDrain implements Runnable {
        private final InputStream input;
        private final ByteArrayOutputStream captured = new ByteArrayOutputStream();

        StreamDrain(InputStream input) {
            this.input = input;
        }

        @Override
        public void run() {
            try {
                input.transferTo(captured);
            } catch (IOException ignored) {
                // The stream closes when the process dies; the captured
                // bytes up to that point are the execution outcome.
            }
        }

        byte[] bytes() {
            return captured.toByteArray();
        }
    }

    /**
     * Runs the deployed lane runner in a real {@code luajit} subprocess,
     * capturing stdout and stderr as separate byte streams. On interrupt
     * the subprocess is terminated (the Lane contract requirement the
     * dispatcher's deadline enforcement depends on) and the interrupt is
     * re-asserted.
     */
    private static SubprocessResult runSubprocess(Path workspace)
            throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder("luajit",
            RUNNER_FILE_NAME);
        builder.directory(workspace.toFile());
        Process process = builder.start();

        StreamDrain stdoutDrain = new StreamDrain(process.getInputStream());
        StreamDrain stderrDrain = new StreamDrain(process.getErrorStream());
        Thread stdoutThread = new Thread(stdoutDrain, "lua-lane-stdout");
        Thread stderrThread = new Thread(stderrDrain, "lua-lane-stderr");
        stdoutThread.setDaemon(true);
        stderrThread.setDaemon(true);
        stdoutThread.start();
        stderrThread.start();

        int exit;
        try {
            exit = process.waitFor();
        } catch (InterruptedException e) {
            // The harness-owned deadline: terminate the subprocess and
            // re-assert the interrupt (the Lane contract).
            process.destroyForcibly();
            try {
                process.waitFor(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            stdoutThread.join(5000);
            stderrThread.join(5000);
            throw e;
        }
        stdoutThread.join();
        stderrThread.join();
        return new SubprocessResult(stdoutDrain.bytes(), stderrDrain.bytes(),
            exit);
    }

    // =========================================================================
    // Error framing (G4.6) with the shared canonical serializer
    // =========================================================================

    /** The raw captured error fields transported by the lane runner. */
    private record CapturedError(
            String code,
            String message,
            String file,
            Integer line,
            Integer column,
            Optional<String> expected,
            Optional<String> actual,
            Optional<Integer> frames,
            Optional<String> cause
    ) {

        CapturedError {
            Objects.requireNonNull(expected, "expected must not be null");
            Objects.requireNonNull(actual, "actual must not be null");
            Objects.requireNonNull(frames, "frames must not be null");
            Objects.requireNonNull(cause, "cause must not be null");
        }

        /** True when the captured error carries the full span group. */
        boolean carriesSpan() {
            return file != null && line != null && column != null;
        }
    }

    /**
     * Reads and parses the runner's raw captured-error transport from the
     * workspace, or {@code null} when the run carried no DEAL error
     * payload.
     */
    private static CapturedError readTransport(Path workspace) {
        Path transport = workspace.resolve(TRANSPORT_FILE_NAME);
        if (!Files.exists(transport)) {
            return null;
        }
        try {
            CanonicalJson.Value root = CanonicalJson.parse(
                Files.readString(transport));
            if (!(root instanceof CanonicalJson.Obj obj)) {
                return null;
            }
            Map<String, CanonicalJson.Value> fields = new LinkedHashMap<>();
            for (CanonicalJson.Entry entry : obj.entries()) {
                fields.put(entry.key(), entry.value());
            }
            return new CapturedError(
                stringField(fields, "code"),
                stringField(fields, "message"),
                stringField(fields, "file"),
                intField(fields, "line"),
                intField(fields, "column"),
                Optional.ofNullable(stringField(fields, "expected")),
                Optional.ofNullable(stringField(fields, "actual")),
                Optional.ofNullable(intField(fields, "frames")),
                Optional.ofNullable(stringField(fields, "cause")));
        } catch (IOException | SemanticIrTextDecodeException
                | IllegalArgumentException e) {
            return null;
        }
    }

    private static String stringField(Map<String, CanonicalJson.Value> fields,
            String key) {
        CanonicalJson.Value value = fields.get(key);
        if (!(value instanceof CanonicalJson.Str str)) {
            return null;
        }
        return str.value();
    }

    private static Integer intField(Map<String, CanonicalJson.Value> fields,
            String key) {
        CanonicalJson.Value value = fields.get(key);
        if (!(value instanceof CanonicalJson.Int integer)) {
            return null;
        }
        return integer.value();
    }

    /**
     * Assembles the closed lane outcome from the subprocess run and the
     * captured error: success passes the captured streams through
     * untouched; an uncaught DEAL error appends the exact G4.6 framing
     * built by the shared {@link ErrorSnapshot} canonical serializer with
     * the sidecar as the authoritative field set (the lane emits the
     * mandatory fields plus exactly the pinned span group and pinned
     * optional fields and suppresses every unpinned one; a pinned field
     * the captured error does not carry is never fabricated — the lane
     * fails honestly instead). The one sanctioned span-less shape — the
     * locked time selector's retained {@code nowMillis} wrapper raising
     * E8004 with no file/line/column
     * ({@code luajit-time-selector-disposition}, Failure and
     * operations) — pairs with a sidecar that omits the whole span
     * group, so the lane emits the span-less snapshot exactly as
     * captured. A non-zero exit without a DEAL error payload is a
     * subprocess failure outside the DEAL outcome surface.
     */
    private LaneExecution assembleOutcome(
            SidecarExpectations.RuntimeExpectation.Executed expectation,
            SubprocessResult subprocess, Path workspace,
            LuaCompilation compilation) {
        CapturedError captured = readTransport(workspace);
        if (captured == null) {
            if (subprocess.exitCode() == 0) {
                return new LaneExecution.Executed(subprocess.stdout(),
                    subprocess.stderr(), 0);
            }
            return new LaneExecution.Infrastructure(MismatchClass.PROCESS_FAILURE,
                "the lane subprocess exited " + subprocess.exitCode()
                    + " outside the DEAL outcome surface (no DEAL error "
                    + "payload was captured); stderr: "
                    + boundedText(subprocess.stderr()));
        }
        if (captured.code() == null || captured.message() == null) {
            return new LaneExecution.Infrastructure(MismatchClass.PROCESS_FAILURE,
                "the captured DEAL error carries no complete code/message "
                    + "pair — the lane cannot serialize the canonical "
                    + "snapshot; captured fields: code="
                    + boundedString(captured.code())
                    + ", message=" + boundedString(captured.message()));
        }

        // The sidecar is the authoritative field set. The span group is
        // emitted exactly when the sidecar pins it; the sanctioned
        // span-less shape (the retained nowMillis wrapper raising E8004
        // with no span) pairs with a sidecar that omits the whole group.
        SidecarExpectations.ErrorExpectation pinned = expectation.error();
        boolean spanPinned = pinned == null || pinned.pinsSpan();
        if (spanPinned && !captured.carriesSpan()) {
            return new LaneExecution.Infrastructure(
                MismatchClass.PROCESS_FAILURE,
                "the captured DEAL error carries no span (file, line, "
                    + "column) where one is required — the lane never "
                    + "fabricates a pinned field; captured fields: code="
                    + boundedString(captured.code())
                    + ", message=" + boundedString(captured.message())
                    + ", file=" + boundedString(captured.file())
                    + ", line=" + captured.line() + ", column="
                    + captured.column());
        }
        String sourceFile = null;
        Integer line = null;
        Integer column = null;
        if (spanPinned && captured.carriesSpan()) {
            // sourceFile normalization (corpus C2): map the captured file
            // through the per-module deployment map and rebase the line
            // onto raw corpus-file coordinates; an unmappable file is
            // emitted verbatim so the byte comparison fails and surfaces
            // the defect.
            DeploymentEntry deployment = deploymentEntryFor(captured.file(),
                compilation);
            sourceFile = deployment != null
                ? deployment.corpusPath()
                : captured.file();
            line = deployment != null
                ? captured.line() + deployment.headerLinesStripped()
                : captured.line();
            column = captured.column();
        }

        SidecarExpectations.ErrorExpectation snapshot =
            new SidecarExpectations.ErrorExpectation(
                captured.code(), captured.message(), sourceFile, line,
                column,
                optionalField(pinned, "expected", captured.expected()),
                optionalField(pinned, "actual", captured.actual()),
                optionalField(pinned, "frames", captured.frames()),
                optionalField(pinned, "cause", captured.cause()));

        String framing = ErrorSnapshot.CODE_LINE_PREFIX + captured.code()
            + "\n" + ErrorSnapshot.SNAPSHOT_LINE_PREFIX
            + ErrorSnapshot.canonicalJson(snapshot) + "\n";
        byte[] framedStdout = concat(subprocess.stdout(),
            framing.getBytes(StandardCharsets.UTF_8));
        return new LaneExecution.Executed(framedStdout, subprocess.stderr(),
            subprocess.exitCode());
    }

    /**
     * The optional-field selection rule (C2): with no pinned Error
     * Expectation every captured optional field is emitted; with one,
     * exactly the pinned fields the captured error carries.
     */
    private static <T> Optional<T> optionalField(
            SidecarExpectations.ErrorExpectation pinned, String field,
            Optional<T> captured) {
        if (captured.isEmpty()) {
            return Optional.empty();
        }
        if (pinned == null || pinned.pinsOptional(field)) {
            return captured;
        }
        return Optional.empty();
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] joined = new byte[first.length + second.length];
        System.arraycopy(first, 0, joined, 0, first.length);
        System.arraycopy(second, 0, joined, first.length, second.length);
        return joined;
    }

    /** Bounded single-line text detail for infrastructure outcomes. */
    private static String boundedString(String text) {
        if (text == null) {
            return "null";
        }
        String flat = text.replace("\n", "\\n").replace("\r", "\\r");
        if (flat.length() > 120) {
            return flat.substring(0, 117) + "...";
        }
        return flat;
    }

    /** Bounded single-line stream detail for infrastructure outcomes. */
    private static String boundedText(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8)
            .replace("\n", "\\n").replace("\r", "\\r");
        if (text.length() > 200) {
            return text.substring(0, 197) + "...";
        }
        return text;
    }

    // =========================================================================
    // The lane runner template (G4.4 + the transport half of G4.6)
    // =========================================================================

    /**
     * Builds the lane runner Lua source: loads the generated fixture
     * module (whose entry contract has already invoked {@code main()}
     * exactly once at chunk end), auto-invokes each ordered zero-arity
     * export exactly once in declaration order (return values discarded —
     * no lane prints results), and transports any uncaught DEAL error's
     * raw fields to the workspace payload file for the lane's canonical
     * framing. Non-DEAL errors transport nothing.
     *
     * <p><b>Test seam:</b> scratch lane variants override this method for
     * the lane-contract probes (printing a return value, invoking
     * {@code main} twice, attaching a synthetic {@code frames} field).</p>
     */
    protected String buildRunner(String generatedLua,
            List<String> orderedZeroArityExports) {
        StringBuilder exports = new StringBuilder("{");
        for (String exportName : orderedZeroArityExports) {
            exports.append(' ').append(luaString(exportName)).append(',');
        }
        exports.append(" }");
        return
            "package.path = './?.lua;./std/?.lua;' .. package.path\n"
            + transportWriterLua()
            + "local __lane_exports = " + exports + "\n"
            + "local __ok, __err = xpcall(function()\n"
            + "  local __mod = (function()\n"
            + generatedLua + "\n"
            + "  end)()\n"
            + "  if type(__mod) == 'table' then\n"
            + "    for __i = 1, #__lane_exports do\n"
            + "      local __k = __lane_exports[__i]\n"
            + "      local __v = __mod[__k]\n"
            + "      if type(__v) == 'table' and __v.__kind == 'function' then\n"
            + "        __v.f()\n"
            + "      end\n"
            + "    end\n"
            + "  end\n"
            + "end, function(__err)\n"
            + "  if type(__err) == 'table' and __err.code ~= nil then\n"
            + "    __lane_write_transport(__err)\n"
            + "  end\n"
            + "end)\n"
            + "if not __ok then os.exit(1) end\n";
    }

    /**
     * The Lua transport writer inlined into the runner: writes the raw
     * captured error fields to {@code lane_error.json} in the workspace
     * (fixed field order, minimal JSON string escaping — the lane
     * re-serializes canonically through the shared serializer, so this
     * transport never competes with the canonical form).
     */
    protected static String transportWriterLua() {
        return
            "local function __lane_json_escape(__s)\n"
            + "  local __out = {}\n"
            + "  for __j = 1, #__s do\n"
            + "    local __c = __s:sub(__j, __j)\n"
            + "    local __b = __s:byte(__j)\n"
            + "    if __c == '\"' then __out[#__out + 1] = '\\\\\"'\n"
            + "    elseif __c == '\\\\' then __out[#__out + 1] = '\\\\\\\\'\n"
            + "    elseif __b == 8 then __out[#__out + 1] = '\\\\b'\n"
            + "    elseif __b == 9 then __out[#__out + 1] = '\\\\t'\n"
            + "    elseif __b == 10 then __out[#__out + 1] = '\\\\n'\n"
            + "    elseif __b == 12 then __out[#__out + 1] = '\\\\f'\n"
            + "    elseif __b == 13 then __out[#__out + 1] = '\\\\r'\n"
            + "    elseif __b < 32 then __out[#__out + 1] = "
            + "string.format('\\\\u%04X', __b)\n"
            + "    else __out[#__out + 1] = __c\n"
            + "    end\n"
            + "  end\n"
            + "  return table.concat(__out)\n"
            + "end\n"
            + "local function __lane_write_transport(__err)\n"
            + "  local __f = io.open('" + TRANSPORT_FILE_NAME + "', 'w')\n"
            + "  if not __f then return end\n"
            + "  __f:write('{')\n"
            + "  local __first = true\n"
            + "  local function __str(__k, __v)\n"
            + "    if not __first then __f:write(',') end\n"
            + "    __first = false\n"
            + "    __f:write('\"', __k, '\":\"', __lane_json_escape(__v), '\"')\n"
            + "  end\n"
            + "  local function __num(__k, __v)\n"
            + "    if not __first then __f:write(',') end\n"
            + "    __first = false\n"
            + "    __f:write('\"', __k, '\":', tostring(__v))\n"
            + "  end\n"
            + "  __str('code', __err.code)\n"
            + "  if __err.message ~= nil then __str('message', __err.message) end\n"
            + "  if __err.file ~= nil then __str('file', __err.file) end\n"
            + "  if __err.line ~= nil then __num('line', __err.line) end\n"
            + "  if __err.column ~= nil then __num('column', __err.column) end\n"
            + "  if __err.expected ~= nil then __str('expected', __err.expected) end\n"
            + "  if __err.actual ~= nil then __str('actual', __err.actual) end\n"
            + "  if __err.frames ~= nil then __num('frames', __err.frames) end\n"
            + "  if __err.cause ~= nil then __str('cause', __err.cause) end\n"
            + "  __f:write('}')\n"
            + "  __f:close()\n"
            + "end\n";
    }

    /** A Lua double-quoted string literal with the minimal escapes. */
    private static String luaString(String value) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < value.length(); ) {
            int cp = value.codePointAt(i);
            i += Character.charCount(cp);
            switch (cp) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\t' -> sb.append("\\t");
                case '\n' -> sb.append("\\n");
                case '\f' -> sb.append("\\f");
                case '\r' -> sb.append("\\r");
                default -> {
                    if (cp < 0x20) {
                        sb.append(String.format("\\u%04X", cp));
                    } else {
                        sb.appendCodePoint(cp);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    // =========================================================================
    // Test seams (documented scratch-probe hooks)
    // =========================================================================

    /**
     * <b>Test seam:</b> invoked after the workspace is deployed and
     * before the artifact-presence assertion and the subprocess spawn.
     * Scratch lane variants delete a generated artifact here to prove the
     * lane reports {@link MismatchClass#ARTIFACT_MISSING} instead of
     * fabricating a result.
     */
    protected void beforeExecution(Path workspaceDirectory) {
        // The production lane performs no extra step here.
    }

    /**
     * <b>Test seam:</b> the captured-file deployment lookup. Scratch lane
     * variants return {@code null} here to strip the deployment map, so
     * the captured {@code file} is emitted verbatim and the comparison
     * fails (the unmappable-file probe).
     */
    protected DeploymentEntry deploymentEntryFor(String capturedFile,
            LuaCompilation compilation) {
        return compilation.deploymentMap().get(capturedFile);
    }
}
