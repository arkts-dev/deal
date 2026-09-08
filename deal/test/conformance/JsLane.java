package deal.test.conformance;

import deal.ast.ArrayType;
import deal.ast.ClassDeclaration;
import deal.ast.ClassField;
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
import deal.codegen.js.HostModuleDeclarations;
import deal.codegen.js.JsBackend;
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
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * The JavaScript lane of the v1.2 differential gate (ISSUE-0356; design
 * {@code v12-zero-skip-conformance-gate} G4/G5): the absorbed
 * {@code BackendConformanceTest} JS adapter path — the real frontend +
 * {@link JsBackend} codegen, the {@code deal/runtime.js} / {@code std/*.js}
 * / host-triplet deployment, and a real {@code node} subprocess —
 * executed under the Shared Lane Contract.
 *
 * <p>Per case the lane:</p>
 * <ol>
 *   <li>Probes the required tool {@code node} once (G3): a missing or
 *       broken-but-present tool is {@link MismatchClass#TOOL_MISSING},
 *       never a skip.</li>
 *   <li>Compiles the fixture plus every transitively imported companion
 *       module with the real frontend (lexer → parser → module shape
 *       gate → name resolution → type checker), companions first so the
 *       per-case identity index classifies every module before its
 *       importer. Every module compiles under the case profile resolved
 *       by {@code LegacyProfileRegressionCatalog.profileFor} — the same
 *       A5 per-case profile selection the absorbed Lua lane uses. A
 *       compile failure of any module is a lane compile failure (an
 *       infrastructure outcome with the diagnostic codes), never an
 *       execution outcome.</li>
 *   <li>Generates real CommonJS artifacts through the real
 *       {@link JsBackend} production seam — one artifact per module
 *       under its flat corpus stem (the corpus has no duplicate stems
 *       inside one compilation set), the fixture as the v1.2 entry
 *       module (the backend's entry scan emits E6004 and no artifact for
 *       an invalid entry) — and asserts artifact presence before
 *       execution: a missing artifact is
 *       {@link MismatchClass#ARTIFACT_MISSING}, never a fabricated
 *       result.</li>
 *   <li>Deploys a fresh temp workspace — the lane runner, the generated
 *       entry and companion artifacts, {@code deal/runtime.js}, the
 *       {@code std/*.js} library, and the imported {@code host/<name>}
 *       triplet implementations from
 *       {@code test/conformance/host-fixtures/<name>.js} (corpus C5) —
 *       and executes the runner in a real {@code node} subprocess with
 *       stdout and stderr captured as separate byte streams. A fixture
 *       importing {@code host/<name>} whose {@code <name>.js} triplet is
 *       missing fails as a corpus error ({@code ARTIFACT_MISSING} naming
 *       the file), never a skip. The gate's dispatcher owns the harness
 *       deadline; on interrupt the lane terminates the spawned
 *       subprocess (the Lane contract requirement).</li>
 *   <li>Invocation contract (G4.4): the lane runner invokes
 *       {@code main(): null} exactly once (the runner is the node entry,
 *       so the emitted entry shim — keyed on {@code require.main} — stays
 *       inert and the runner owns the single invocation), then
 *       auto-invokes each non-{@code $} zero-arity exported wrapper
 *       exactly once, in declaration order (derived from the compiled
 *       AST); return values are discarded — no lane prints results (the
 *       JVM/JS result-printing of the absorbed runners is removed, the
 *       cross-backend formatting hazard); an async export's invocation
 *       is awaited to completion before the verdict;
 *       {@code $}-prefixed exports are skipped from auto-invocation.</li>
 *   <li>Error framing (G4.6): on an uncaught DEAL error the lane writes
 *       to stdout exactly {@code DEAL_ERROR_CODE: <code>} then
 *       {@code DEAL_ERROR_SNAPSHOT: <canonical JSON>} and exits 1;
 *       success exits 0. The canonical snapshot serialization is the
 *       shared {@link ErrorSnapshot} serializer — the same helper the
 *       Lua and JVM lanes reuse verbatim, so the three lanes can never
 *       drift on serialization. The sidecar's Error Expectation is the
 *       authoritative field set: the lane emits the mandatory fields plus
 *       exactly the optional fields the sidecar pins ({@code expected},
 *       {@code actual}, {@code frames}, {@code cause}) and suppresses
 *       every unpinned optional — a pinned field the captured error does
 *       not carry is never fabricated, so the comparison fails honestly.
 *       The runner transports the raw captured error fields through a
 *       workspace payload file; the lane normalizes and frames them.
 *       stderr carries no framing — a lane writing framing to stderr
 *       diverges and fails.</li>
 *   <li>{@code sourceFile} normalization (corpus C2): the lane maintains
 *       its per-module deployment map (the absolute path every compiled
 *       module's spans carry ↔ its canonical corpus-relative path plus
 *       its stripped classification-header line count), recorded at
 *       compile time; the captured {@code file} is normalized to the
 *       corpus-relative form and the captured {@code line} is rebased
 *       onto raw corpus-file coordinates (the coordinates the sidecars
 *       pin). An unmappable captured {@code file} value is emitted
 *       verbatim, so the byte comparison fails and surfaces the defect.</li>
 *   <li>Sanctioned rejection (corpus C6): for a {@code compile-reject}
 *       expectation the lane compiles without executing and reports the
 *       compile's first error diagnostic as the rejection object; the
 *       comparator's closed cross-check compares it against the pinned
 *       diagnostic (the FFI divergent case lands when the backend epics
 *       register E6006 — until then a divergent expectation fails the
 *       comparison honestly, never a skip).</li>
 * </ol>
 *
 * <p>No skip branch exists in this lane: pre-flip, JS gaps over the
 * {@code .deal} corpus are reported as real differential failures by the
 * gate (the gate is the measuring instrument); the flip (T14) is the
 * point where the full three-backend pass becomes the enforced state.
 * The lane changes no existing runner: {@code BackendConformanceTest} and
 * {@code JsE2eTest} keep running in {@code run_tests.sh} until the
 * absorption/retirement children land (G5's temporary-coexistence
 * window). No production file is modified.</p>
 */
public class JsLane implements Lane {

    /** The lane's backend name (G4: exactly {@code js}). */
    public static final String LANE_NAME = "js";

    /** The lane runner file name inside the temp workspace. */
    public static final String RUNNER_FILE_NAME = "JsLaneRunner.js";

    /** The raw captured-error transport file name inside the workspace. */
    public static final String TRANSPORT_FILE_NAME = "lane_error.json";

    /** The runtime artifact deployed beside the runner (CWD-relative). */
    private static final String RUNTIME_LIBRARY = "deal/runtime.js";

    /** The stdlib surface directory deployed into the workspace. */
    private static final String STD_DIRECTORY = "std";

    /** The host triplet implementation directory inside the workspace. */
    private static final String HOST_DIRECTORY = "host";

    private final Path conformanceRoot;
    private final Path hostFixturesRoot;
    private final Path runtimeLibraryPath;
    private final Path stdlibDirectory;

    /** The cached tool probe: null until the first probe (G3). */
    private volatile Boolean nodeProbe;

    /**
     * Creates the lane over one conformance root. The repository runtime
     * artifacts ({@code deal/runtime.js}, {@code std/*.js}) resolve from
     * the current working directory — the same surface the absorbed
     * {@code BackendConformanceTest} adapter uses.
     *
     * @param conformanceRoot the conformance root (any path form; the
     *                        lane normalizes it)
     */
    public JsLane(Path conformanceRoot) {
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

        // Dispatch guard: the JS lane is registered under its own name.
        if (!LANE_NAME.equals(laneCase.backend())) {
            return new LaneExecution.Infrastructure(MismatchClass.HARNESS_DEFECT,
                "the JS lane was dispatched for backend "
                    + laneCase.backend() + " — the lane serves exactly "
                    + LANE_NAME);
        }

        // The sanctioned C6 compile-reject divergence (schema validation
        // admits it only for the jvm/js legs of an extern-C case).
        if (laneCase.expectation()
                instanceof SidecarExpectations.RuntimeExpectation.Rejected) {
            return executeRejected(laneCase);
        }
        SidecarExpectations.RuntimeExpectation.Executed expected =
            (SidecarExpectations.RuntimeExpectation.Executed)
                laneCase.expectation();

        // G3: the required tool must be present and functional; absence
        // is a failure, never a skip.
        if (!nodeAvailable()) {
            return new LaneExecution.Infrastructure(MismatchClass.TOOL_MISSING,
                "the required tool node is missing or broken — the lane "
                    + "never skips (G3)");
        }

        // Compile the fixture plus its companions with the real frontend
        // and generate the real CommonJS artifacts (companions first).
        JsCompilation compilation = new JsCompilation(laneCase);
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

        // Deploy the workspace and execute the real node subprocess.
        return runDeployedCase(entry, compileOutcome, compilation,
            (subprocess, workspace) -> assembleOutcome(expected,
                subprocess, workspace, compilation));
    }

    /**
     * The compile-reject execution path (corpus C6): compile without
     * executing and report the compile's first error diagnostic as the
     * rejection object — exactly the pinned diagnostic: the code plus
     * the pinned line/column only, with every unpinned field suppressed
     * (the sidecar is the authoritative field set). The comparator's
     * closed cross-check compares it against the pinned diagnostic. A
     * divergent code fails the cross-check; a clean compile under a
     * rejection expectation executes the real artifacts so the
     * comparator names {@code COMPILE_REJECT_MISMATCH} (G6) — the lane
     * never fabricates a rejection, never a skip.
     */
    private LaneExecution executeRejected(LaneCase laneCase)
            throws IOException, InterruptedException {
        SidecarExpectations.RuntimeExpectation.Rejected rejected =
            (SidecarExpectations.RuntimeExpectation.Rejected)
                laneCase.expectation();
        JsCompilation compilation = new JsCompilation(laneCase);
        CompilationOutcome outcome = compilation.compileCase();
        if (outcome.failure() == null && outcome.artifactMissing() == null) {
            // The case compiled clean but the sidecar pins compile
            // rejection: the lane executes the real artifacts (the lane
            // executed instead of rejecting) and the comparator's closed
            // cross-check names COMPILE_REJECT_MISMATCH — an expected
            // rejection that never materialized is a DEAL outcome
            // mismatch, not an infrastructure failure (G6). The lane
            // never fabricates a rejection.
            if (!nodeAvailable()) {
                return new LaneExecution.Infrastructure(
                    MismatchClass.TOOL_MISSING,
                    "the required tool node is missing or broken — the "
                        + "lane never skips (G3)");
            }
            return runDeployedCase(outcome.entry(), outcome, compilation,
                (subprocess, workspace) -> new LaneExecution.Executed(
                    subprocess.stdout(), subprocess.stderr(),
                    subprocess.exitCode()));
        }
        if (compilation.firstErrorCode() == null) {
            String detail = outcome.failure() != null
                ? outcome.failure() : outcome.artifactMissing();
            return new LaneExecution.Infrastructure(MismatchClass.PROCESS_FAILURE,
                "the case failed to compile without an error diagnostic "
                    + "while the sidecar pins compile rejection with code "
                    + rejected.code() + ": " + detail);
        }
        // The rejection object carries exactly the pinned diagnostic:
        // the code plus the pinned line/column; an unpinned field is
        // suppressed (the comparator's closed cross-check fails a lane
        // emitting a field the sidecar does not pin).
        return new LaneExecution.Rejected(compilation.firstErrorCode(),
            rejected.line().isPresent()
                ? OptionalInt.of(compilation.firstErrorLine())
                : OptionalInt.empty(),
            rejected.column().isPresent()
                ? OptionalInt.of(compilation.firstErrorColumn())
                : OptionalInt.empty());
    }

    /**
     * Deploys the compiled case into a fresh temp workspace, asserts
     * artifact presence, and runs the real node subprocess; the given
     * assembler converts the captured subprocess result into the closed
     * lane outcome (the canonical framing assembly for an executed
     * expectation; the raw pass-through for the clean-compile rejection
     * path — the comparator's cross-check owns that verdict).
     */
    private LaneExecution runDeployedCase(CompiledModule entry,
            CompilationOutcome compileOutcome, JsCompilation compilation,
            OutcomeAssembler assembler) throws IOException,
            InterruptedException {
        Path workspace = Files.createTempDirectory("deal_conf_js_");
        try {
            DeployResult deploy = deployWorkspace(workspace, entry,
                compileOutcome.companions(), compilation.importedHostNames());
            if (deploy.missingRepositoryArtifact() != null) {
                return new LaneExecution.Infrastructure(
                    MismatchClass.ARTIFACT_MISSING,
                    deploy.missingRepositoryArtifact());
            }
            beforeExecution(workspace);
            List<String> missing = missingArtifacts(workspace,
                deploy.deployed());
            if (!missing.isEmpty()) {
                return new LaneExecution.Infrastructure(
                    MismatchClass.ARTIFACT_MISSING,
                    "the generated artifacts are missing before execution: "
                        + String.join(", ", missing)
                        + " — the lane never substitutes a fabricated result");
            }
            SubprocessResult subprocess = runSubprocess(workspace);
            return assembler.assemble(subprocess, workspace);
        } finally {
            deleteRecursively(workspace);
        }
    }

    /** One subprocess-result → lane-outcome assembly step. */
    @FunctionalInterface
    private interface OutcomeAssembler {
        LaneExecution assemble(SubprocessResult subprocess, Path workspace);
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
    // Compilation (the absorbed BackendConformanceTest JS adapter path)
    // =========================================================================

    /**
     * One compiled module: its corpus-relative path, its flat corpus stem
     * (the JS module path — the artifact lands at {@code <stem>.js} in the
     * workspace), its generated CommonJS source (null for a declaration
     * companion — declaration files emit no artifact, the orchestrator
     * rule), its parsed program, its stripped-header line count, and the
     * checker artifacts the module resolver consumes.
     */
    private static final class CompiledModule {
        final String corpusPath;
        final String modulePath;
        final String jsSource;
        final ProgramNode program;
        final int headerLinesStripped;
        final SymbolTable symbolTable;
        final NameResolver nameResolver;

        CompiledModule(String corpusPath, String modulePath, String jsSource,
                ProgramNode program, int headerLinesStripped,
                SymbolTable symbolTable, NameResolver nameResolver) {
            this.corpusPath = corpusPath;
            this.modulePath = modulePath;
            this.jsSource = jsSource;
            this.program = program;
            this.headerLinesStripped = headerLinesStripped;
            this.symbolTable = symbolTable;
            this.nameResolver = nameResolver;
        }

        /** True when the module is a declaration file (no JS artifact). */
        boolean isDeclaration() {
            return jsSource == null;
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
     * The per-case JS compilation engine: mirrors the absorbed Lua lane's
     * {@code CompanionCatalog} + {@code HostRegistry} +
     * {@code ConformanceModuleResolver} surface — one case profile, a
     * per-case identity index, companions compiled depth-first before
     * their importers, host triplet declarations, and the stdlib export
     * surface — with the {@code BackendConformanceTest} JS adapter's
     * {@link JsBackend} production-seam invocation generalized to
     * per-module codegen.
     */
    final class JsCompilation {

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
         * The per-case canonical identity surface for the JS backend:
         * dotted module path → module identity. Keys: the builtin empty
         * path, every compiled module's flat stem, and every host triplet
         * raw specifier in dotted form.
         */
        private final Map<String, CanonicalModuleIdentity> jsModuleIdentities =
            new LinkedHashMap<>();

        /** The host triplet names (raw specifier minus {@code host/}) the
         * case imports — the implementations deployed into the workspace. */
        private final Set<String> importedHostNames = new LinkedHashSet<>();

        /** The first error diagnostic of the compile (the rejection object). */
        private String firstErrorCode;
        private int firstErrorLine;
        private int firstErrorColumn;

        /**
         * The per-case canonical identity surface (the absorbed
         * {@code CompanionCatalog.classifyModulePath} surface).
         */
        private final Map<String, CanonicalModuleIdentity> moduleIdentities =
            new LinkedHashMap<>();

        JsCompilation(LaneCase laneCase) {
            this.currentCase = laneCase;
            this.profile = LegacyProfileRegressionCatalog.profileFor(
                currentCase.fixturePath());
            this.hostRegistry = new HostRegistry(profile);
            this.stdlibExports = StdlibModuleResolver.stdlibExports(
                stdlibDirectory.toString());
            moduleIdentities.put("",
                CanonicalModuleIdentity.BuiltinModule.INSTANCE);
            jsModuleIdentities.put("",
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

        /** Records the first error diagnostic (the rejection object). */
        private void recordFirstErrorDiagnostic(
                List<CompilerDiagnostic> diagnostics) {
            if (firstErrorCode != null) {
                return;
            }
            for (CompilerDiagnostic diagnostic : diagnostics) {
                if ("error".equals(diagnostic.severity())) {
                    firstErrorCode = diagnostic.code();
                    firstErrorLine = diagnostic.line();
                    firstErrorColumn = diagnostic.column();
                    return;
                }
            }
        }

        String firstErrorCode() {
            return firstErrorCode;
        }

        int firstErrorLine() {
            return firstErrorLine;
        }

        int firstErrorColumn() {
            return firstErrorColumn;
        }

        /** The imported host triplet names of the case (deployment set). */
        Set<String> importedHostNames() {
            return importedHostNames;
        }

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
                String stem = moduleNameFor(file);

                LexResult lex = new Lexer(source, filename).tokenize();
                if (lex.hasErrors()) {
                    recordFirstErrorDiagnostic(lex.diagnostics());
                    compileFailure = "the lane compilation failed for "
                        + corpusPath + ": " + describeDiagnostics(
                            lex.diagnostics());
                    return null;
                }

                Parser parser = new Parser(lex.tokens(), filename, profile,
                    lex.directiveEvents());
                ParseResult parseResult = parser.parse();
                if (parseResult.hasErrors()) {
                    recordFirstErrorDiagnostic(parseResult.diagnostics());
                    compileFailure = "the lane compilation failed for "
                        + corpusPath + ": " + describeDiagnostics(
                            parseResult.diagnostics());
                    return null;
                }

                // The module shape gate runs on the entry module exactly
                // like the absorbed runner's diagnostics pass; companions
                // compile through the catalog surface (no shape pass).
                if (isEntry && !corpusPath.endsWith(".d.deal")) {
                    List<CompilerDiagnostic> shape = ModuleShapeValidator
                        .validate(parseResult.program(), filename, false);
                    if (shape.stream().anyMatch(
                            d -> "error".equals(d.severity()))) {
                        recordFirstErrorDiagnostic(shape);
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
                Map<String, String> companionCorpusPaths =
                    new LinkedHashMap<>();
                Map<String, HostModuleDeclarations> hostModules =
                    new LinkedHashMap<>();
                for (StatementNode stmt
                        : parseResult.program().statements()) {
                    if (!(stmt instanceof ImportDeclaration imp)) {
                        continue;
                    }
                    String importPath = imp.modulePath();
                    if (hostRegistry.isHostModule(importPath)) {
                        try {
                            HostDeclaration declaration = hostRegistry
                                .forModule(importPath);
                            hostModules.put(importPath,
                                declaration.hostDeclarations());
                            importedHostNames.add(hostNameOf(importPath));
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
                        importResolutions.put(importPath, depCorpusPath);
                        companionCorpusPaths.put(importPath, depCorpusPath);
                    }
                }

                // Compile each companion dependency transitively,
                // depth-first, before this module (the absorbed catalog
                // order — the identity index must classify every
                // companion before its importer).
                for (String dep : new ArrayList<>(
                        companionCorpusPaths.values())) {
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

                // A declaration companion emits no artifact (the
                // orchestrator rule): it compiles parse-only and joins the
                // importer's host-module binding with its declared map.
                if (corpusPath.endsWith(".d.deal")) {
                    CompiledModule declared = new CompiledModule(corpusPath,
                        stem, null, parseResult.program(),
                        headerLinesStripped, null, null);
                    cache.put(file, declared);
                    deploymentMap.put(filename, new DeploymentEntry(
                        corpusPath, headerLinesStripped));
                    return declared;
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
                    recordFirstErrorDiagnostic(nr.diagnostics());
                    compileFailure = "the lane compilation failed for "
                        + corpusPath + ": " + describeDiagnostics(
                            nr.diagnostics());
                    return null;
                }

                CheckResult result = TypeChecker.check(filename, symTable,
                    nr, parseResult.program());
                if (result.hasErrors()) {
                    recordFirstErrorDiagnostic(result.diagnostics());
                    compileFailure = "the lane compilation failed for "
                        + corpusPath + ": " + describeDiagnostics(
                            result.diagnostics());
                    return null;
                }

                // The resolved JS import surface: raw import path →
                // companion module path (flat stem), and the host-module
                // declared maps.
                Map<String, String> jsImportResolutions =
                    new LinkedHashMap<>();
                for (Map.Entry<String, String> entry
                        : importResolutions.entrySet()) {
                    CompiledModule companion = cache.get(
                        conformanceRoot.resolve(entry.getValue())
                            .toAbsolutePath().normalize());
                    if (companion == null) {
                        compileFailure = "harness inconsistency: the "
                            + "companion " + entry.getValue() + " of "
                            + corpusPath + " is not compiled";
                        return null;
                    }
                    if (companion.isDeclaration()) {
                        // A declaration companion binds through the
                        // loadHost declared map (the orchestrator rule),
                        // never through a require of a missing artifact.
                        hostModules.put(entry.getKey(),
                            hostDeclarationsOf(companion));
                    } else {
                        jsImportResolutions.put(entry.getKey(),
                            companion.modulePath);
                    }
                }

                // Register this module's identity entries before backend
                // construction (the absorbed registerModulePath surface).
                registerModulePath(filename, stem, hostModules.keySet());
                JsBackend.JsCodegenResult codegen = JsBackend.generate(
                    parseResult.program(), result, filename, stem,
                    jsImportResolutions, hostModules, Set.of(), isEntry,
                    identityIndex(), identityIndex().moduleIdentityLookup(),
                    null, profile);
                if (codegen.hasErrors()) {
                    recordFirstErrorDiagnostic(codegen.diagnostics());
                    return recordArtifactMissing(corpusPath,
                        describeDiagnostics(codegen.diagnostics()));
                }
                CompiledModule compiled = new CompiledModule(corpusPath,
                    stem, codegen.source(), parseResult.program(),
                    headerLinesStripped, symTable, nr);
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
            artifactMissingDetail = "the generated JS artifact of "
                + corpusPath + " is missing or invalid: " + detail
                + " — the lane never substitutes a fabricated artifact";
            return null;
        }

        private String artifactMissingDetail;

        /**
         * The host declared map of a declaration companion (the
         * orchestrator's {@code hostDeclarationsOf} mirror): the declared
         * export map plus, per class export name, the declaration AST's
         * field records with their resolved declared types.
         */
        private HostModuleDeclarations hostDeclarationsOf(
                CompiledModule declaration) {
            ExportExtractor extractor = new ExportExtractor(
                declaration.modulePath, true, this::classifyModulePath);
            Map<String, Type> exports = extractor.extract(
                declaration.program);
            Map<String, List<HostModuleDeclarations.HostField>> classFields =
                new LinkedHashMap<>();
            for (StatementNode stmt : declaration.program.statements()) {
                ClassDeclaration cd = null;
                if (stmt instanceof ClassDeclaration c) {
                    cd = c;
                } else if (stmt instanceof ExportDeclaration ed
                        && ed.declaration()
                            instanceof ClassDeclaration c) {
                    cd = c;
                }
                if (cd == null) {
                    continue;
                }
                List<HostModuleDeclarations.HostField> fields =
                    new ArrayList<>();
                for (ClassField cf : cd.fields()) {
                    fields.add(new HostModuleDeclarations.HostField(cf,
                        extractor.resolveFieldType(cf.type())));
                }
                classFields.putIfAbsent(cd.name(), fields);
            }
            return new HostModuleDeclarations(
                Collections.unmodifiableMap(
                    new LinkedHashMap<>(exports)),
                Collections.unmodifiableMap(
                    new LinkedHashMap<>(classFields)));
        }

        private ModuleIdentityResolver.IdentityIndex identityIndex() {
            return ModuleIdentityResolver.buildIndex(jsModuleIdentities);
        }

        private void registerModulePath(String modulePath, String stem,
                Set<String> hostModules) {
            if (modulePath != null && !modulePath.isEmpty()
                    && !moduleIdentities.containsKey(modulePath)) {
                moduleIdentities.put(modulePath,
                    classifyModulePath(modulePath));
            }
            if (!jsModuleIdentities.containsKey(stem)) {
                jsModuleIdentities.put(stem,
                    classifyModulePath(modulePath));
            }
            for (String raw : hostModules) {
                String dotted = raw.replace('/', '.');
                if (!moduleIdentities.containsKey(dotted)) {
                    moduleIdentities.put(dotted, classifyModulePath(dotted));
                }
                if (!jsModuleIdentities.containsKey(dotted)) {
                    jsModuleIdentities.put(dotted,
                        new CanonicalModuleIdentity.ExternalModule(raw));
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

    /** The module stem of a .deal file: its file name minus the suffix. */
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

    /** The host triplet name of a raw {@code host/<name>} specifier. */
    private static String hostNameOf(String modulePath) {
        String raw = modulePath.replace('.', '/');
        String prefix = "host/";
        if (!raw.startsWith(prefix) || raw.length() == prefix.length()) {
            return raw;
        }
        return raw.substring(prefix.length());
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
     * The ordered auto-invocation list (G4.4): every non-{@code $}
     * zero-arity exported function of the entry module in declaration
     * order, excluding {@code main} (the lane runner invokes
     * {@code main} exactly once before the loop).
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

    /** One host triplet declaration: exports, synthesized class symbols,
     * and the JS host-module declared map. */
    private record HostDeclaration(Map<String, Type> exports,
            Map<String, Symbol.ClassSymbol> classSymbols,
            HostModuleDeclarations hostDeclarations) {

        HostDeclaration {
            exports = Map.copyOf(exports);
            classSymbols = Map.copyOf(classSymbols);
            Objects.requireNonNull(hostDeclarations,
                "hostDeclarations must not be null");
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

                // The JS host-module declared map (ISSUE-0328,
                // js-v12-host-abi-completion D1): the declared export map
                // plus, per class export name, the declaration AST's
                // field records with their resolved declared types.
                Map<String, List<HostModuleDeclarations.HostField>>
                    classFields = new LinkedHashMap<>();
                for (StatementNode stmt
                        : parseResult.program().statements()) {
                    ClassDeclaration cd = null;
                    if (stmt instanceof ClassDeclaration c) {
                        cd = c;
                    } else if (stmt instanceof ExportDeclaration ed
                            && ed.declaration()
                                instanceof ClassDeclaration c) {
                        cd = c;
                    }
                    if (cd == null) {
                        continue;
                    }
                    List<HostModuleDeclarations.HostField> fields =
                        new ArrayList<>();
                    for (ClassField cf : cd.fields()) {
                        fields.add(new HostModuleDeclarations.HostField(cf,
                            extractor.resolveFieldType(cf.type())));
                    }
                    classFields.putIfAbsent(cd.name(), fields);
                }

                HostDeclaration result = new HostDeclaration(
                    Collections.unmodifiableMap(
                        new LinkedHashMap<>(exports)),
                    Collections.unmodifiableMap(
                        new LinkedHashMap<>(classSymbols)),
                    new HostModuleDeclarations(
                        Collections.unmodifiableMap(
                            new LinkedHashMap<>(exports)),
                        Collections.unmodifiableMap(
                            new LinkedHashMap<>(classFields))));
                cache.put(raw, result);
                return result;
            } catch (IOException e) {
                throw new ModuleResolver.ModuleNotFoundException(
                    "Cannot read: " + decl);
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
        private final JsCompilation compilation;
        private final SemanticProfile profile;
        private final Map<String, Map<String, Type>> stdlibExports;
        private final HostRegistry hostRegistry;

        ConformanceModuleResolver(Path testFileDir,
                JsCompilation compilation, SemanticProfile profile) {
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
                throw new ModuleResolver.ModuleNotFoundException(
                    "Cannot read: " + file);
            }
        }
    }

    // =========================================================================
    // Workspace deployment
    // =========================================================================

    /** One deployment result: the deployed artifacts plus the repository
     * artifact that is missing (null when every required repository
     * artifact is present). */
    private record DeployResult(List<Path> deployed,
                                String missingRepositoryArtifact) {

        DeployResult {
            Objects.requireNonNull(deployed, "deployed must not be null");
            deployed = List.copyOf(deployed);
        }
    }

    /**
     * Deploys the lane runner, the generated entry and companion
     * artifacts, {@code deal/runtime.js}, the {@code std/*.js} library,
     * and the imported {@code host/<name>.js} triplet implementations
     * into the workspace. Returns the deployed artifact paths in
     * deployment order plus the missing repository artifact, if any.
     */
    private DeployResult deployWorkspace(Path workspace, CompiledModule entry,
            List<CompiledModule> companions, Set<String> importedHostNames)
            throws IOException {
        if (!Files.exists(runtimeLibraryPath)
                || !Files.isDirectory(stdlibDirectory)) {
            return new DeployResult(List.of(),
                "a required repository artifact is missing: "
                    + RUNTIME_LIBRARY + " or the " + STD_DIRECTORY
                    + " surface directory — the lane never fabricates "
                    + "a deployed artifact");
        }
        List<Path> deployed = new ArrayList<>();

        // The lane runner (the entry module name and the ordered export
        // list are inlined).
        Path runner = workspace.resolve(RUNNER_FILE_NAME);
        Files.writeString(runner, buildRunner(entry.modulePath,
            orderedZeroArityExports(entry.program)));
        deployed.add(runner);

        // The entry artifact: <stem>.js at the workspace root.
        Path entryArtifact = workspace.resolve(entry.modulePath + ".js");
        Files.writeString(entryArtifact, entry.jsSource);
        deployed.add(entryArtifact);

        // Companion artifacts: one <stem>.js per compiled companion.
        // Declaration companions deploy nothing (the orchestrator rule —
        // declaration files emit no artifact).
        for (CompiledModule companion : companions) {
            if (companion.isDeclaration()) {
                continue;
            }
            Path companionFile = workspace.resolve(
                companion.modulePath + ".js");
            Files.writeString(companionFile, companion.jsSource);
            deployed.add(companionFile);
        }

        // The runtime library.
        Path runtimeDir = workspace.resolve("deal");
        Files.createDirectories(runtimeDir);
        Path runtimeTarget = runtimeDir.resolve("runtime.js");
        Files.copy(runtimeLibraryPath, runtimeTarget);
        deployed.add(runtimeTarget);

        // Host triplet implementations (corpus C5): the lane deploys
        // host-fixtures/<name>.js to <tmp>/host/<name>.js — the file the
        // emitted $rt.loadHost relative require resolves. A fixture
        // importing host/<name> with the .js missing is a corpus error,
        // never a skip.
        if (!importedHostNames.isEmpty()) {
            Path targetHostDir = workspace.resolve(HOST_DIRECTORY);
            Files.createDirectories(targetHostDir);
            for (String hostName : importedHostNames) {
                Path hostFile = hostFixturesRoot.resolve(hostName + ".js");
                if (!Files.exists(hostFile)) {
                    return new DeployResult(deployed,
                        "the host triplet implementation host-fixtures/"
                            + hostName + ".js is missing for the imported "
                            + "host module host/" + hostName
                            + " — a corpus error, never a skip");
                }
                Path target = targetHostDir.resolve(hostName + ".js");
                Files.copy(hostFile, target);
                deployed.add(target);
            }
        }

        // The stdlib library surface.
        Path targetStdDir = workspace.resolve("std");
        Files.createDirectories(targetStdDir);
        try (var stream = Files.list(stdlibDirectory)) {
            for (Path stdFile : stream
                    .filter(p -> p.toString().endsWith(".js"))
                    .sorted()
                    .toList()) {
                Path target = targetStdDir.resolve(stdFile.getFileName());
                Files.copy(stdFile, target);
                deployed.add(target);
            }
        }
        return new DeployResult(deployed, null);
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
     * Probes the required {@code node} tool once per lane instance: the
     * tool must exist and exit zero on the version probe (a broken-but-
     * present tool is {@code TOOL_MISSING} too — G3).
     */
    protected boolean nodeAvailable() {
        Boolean probe = nodeProbe;
        if (probe == null) {
            synchronized (this) {
                probe = nodeProbe;
                if (probe == null) {
                    probe = probeNode();
                    nodeProbe = probe;
                }
            }
        }
        return probe;
    }

    private static boolean probeNode() {
        try {
            Process process = new ProcessBuilder("node", "--version").start();
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
     * Runs the deployed lane runner in a real {@code node} subprocess,
     * capturing stdout and stderr as separate byte streams. On interrupt
     * the subprocess is terminated (the Lane contract requirement the
     * dispatcher's deadline enforcement depends on) and the interrupt is
     * re-asserted.
     */
    private static SubprocessResult runSubprocess(Path workspace)
            throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder("node",
            RUNNER_FILE_NAME);
        builder.directory(workspace.toFile());
        Process process = builder.start();

        StreamDrain stdoutDrain = new StreamDrain(process.getInputStream());
        StreamDrain stderrDrain = new StreamDrain(process.getErrorStream());
        Thread stdoutThread = new Thread(stdoutDrain, "js-lane-stdout");
        Thread stderrThread = new Thread(stderrDrain, "js-lane-stderr");
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

        /** True when every mandatory snapshot field is present. */
        boolean complete() {
            return code != null && message != null && file != null
                && line != null && column != null;
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
     * mandatory fields plus exactly the pinned optional fields and
     * suppresses every unpinned one; the span group is emitted only
     * when the sidecar pins it — the sanctioned span-less shape omits
     * all three, so a captured call-site span against a span-less
     * sidecar is suppressed; a pinned field the captured error does
     * not carry is never fabricated — the comparison fails
     * honestly). A non-zero exit without a complete DEAL error payload is
     * a subprocess failure outside the DEAL outcome surface.
     */
    private LaneExecution assembleOutcome(
            SidecarExpectations.RuntimeExpectation.Executed expectation,
            SubprocessResult subprocess, Path workspace,
            JsCompilation compilation) {
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
        if (!captured.complete()) {
            return new LaneExecution.Infrastructure(MismatchClass.PROCESS_FAILURE,
                "the captured DEAL error carries no complete DEALRuntimeError "
                    + "field set (code, message, file, line, column are "
                    + "mandatory) — the lane cannot serialize the canonical "
                    + "snapshot; captured fields: code="
                    + boundedString(captured.code())
                    + ", message=" + boundedString(captured.message())
                    + ", file=" + boundedString(captured.file())
                    + ", line=" + captured.line() + ", column="
                    + captured.column());
        }

        // sourceFile normalization (corpus C2): map the captured file
        // through the per-module deployment map and rebase the line onto
        // raw corpus-file coordinates; an unmappable file is emitted
        // verbatim so the byte comparison fails and surfaces the defect.
        DeploymentEntry deployment = deploymentEntryFor(captured.file(),
            compilation);
        String sourceFile = deployment != null
            ? deployment.corpusPath()
            : captured.file();
        int line = deployment != null
            ? captured.line() + deployment.headerLinesStripped()
            : captured.line();

        // The sidecar is the authoritative field set (C2): the snapshot
        // emits exactly the pinned fields. The span group is emitted
        // only when the sidecar pins it — the sanctioned span-less
        // shape (the locked time selector's retained nowMillis wrapper
        // raising E8004 with no span) omits all three, so a captured
        // call-site span against a span-less sidecar is suppressed
        // (the serializer emits the span group exactly when pinned);
        // a pinned span the captured error lacks was already reported
        // as the honest never-fabricate PROCESS_FAILURE above. The
        // optional fields are emitted only when pinned.
        SidecarExpectations.ErrorExpectation pinned = expectation.error();
        boolean spanPinned = pinned != null && pinned.pinsSpan();
        SidecarExpectations.ErrorExpectation snapshot =
            new SidecarExpectations.ErrorExpectation(
                captured.code(), captured.message(),
                spanPinned ? sourceFile : null,
                spanPinned ? line : null,
                spanPinned ? captured.column() : null,
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
     * Builds the lane runner JS source for the entry module: requires the
     * emitted entry artifact (the runner is the node entry, so the
     * emitted entry shim — keyed on {@code require.main} — stays inert
     * and never invokes {@code main}), invokes {@code main} exactly once
     * (return value discarded), then auto-invokes each ordered zero-arity
     * export exactly once in declaration order (return values discarded —
     * no lane prints results; async exports awaited to completion), and
     * transports any uncaught DEAL error's raw fields to the workspace
     * payload file for the lane's canonical framing. Non-DEAL errors
     * transport nothing. stderr carries no framing.
     *
     * <p><b>Test seam:</b> scratch lane variants override this method for
     * the lane-contract probes (printing a return value, invoking
     * {@code main} twice, writing framing to stderr, attaching a
     * synthetic {@code frames} field).</p>
     */
    protected String buildRunner(String entryModule,
            List<String> orderedZeroArityExports) {
        return runnerPreamble(entryModule, orderedZeroArityExports)
            + transportWriterJs()
            + runnerAsyncBody(entryModule);
    }

    /** The runner preamble: the strict header, the fs capture, and the
     * ordered export-name list (protected so scratch lane variants in
     * the lane tests can compose runner variants). */
    protected String runnerPreamble(String entryModule,
            List<String> orderedZeroArityExports) {
        StringBuilder names = new StringBuilder("[");
        for (int i = 0; i < orderedZeroArityExports.size(); i++) {
            if (i > 0) {
                names.append(", ");
            }
            names.append(jsStringLiteral(orderedZeroArityExports.get(i)));
        }
        names.append("]");
        return """
// Generated by deal.test.conformance.JsLane — JS lane runner.
// Requires the emitted entry artifact, invokes main exactly once, then
// auto-invokes the ordered non-$ zero-arity exports in declaration order
// with discarded return values, awaiting async exports to completion.
// A caught DEAL error transports its raw fields to lane_error.json; the
// runner prints no framing (the lane appends the canonical G4.6 framing
// through the shared ErrorSnapshot serializer) and stderr stays empty.
"use strict";
const $fs = require("fs");
const $zeroAry = $ZEROARY$;
""".replace("$ZEROARY$", names.toString());
    }

    /** The async runner body: require, main exactly once, the ordered
     * export loop with awaited discarded results, and the catch arm
     * (protected so scratch lane variants can compose runner variants). */
    protected String runnerAsyncBody(String entryModule) {
        return """
(async () => {
  const $mod = require("./$ENTRY$");
  if ($mod.main && $mod.main.$kind === "function") {
    $mod.main.$f();
  }
  for (const $k of $zeroAry) {
    const $v = $mod[$k];
    if ($v && $v.$kind === "function") {
      await $v.$f();
    }
  }
})().catch(($e) => {
  if (typeof $e === "object" && $e !== null
      && typeof $e.code === "string") {
    $laneWriteTransport($e);
  }
  process.exitCode = 1;
});
""".replace("$ENTRY$", entryModule);
    }

    /**
     * The JS transport writer inlined into the runner: writes the raw
     * captured error fields to {@code lane_error.json} in the workspace
     * (fixed field order, minimal JSON string escaping — the lane
     * re-serializes canonically through the shared serializer, so this
     * transport never competes with the canonical form).
     */
    /**
     * The JS transport writer inlined into the runner: writes the raw
     * captured error fields to {@code lane_error.json} in the workspace
     * (fixed field order, minimal JSON string escaping — the lane
     * re-serializes canonically through the shared serializer, so this
     * transport never competes with the canonical form).
     */
    protected static String transportWriterJs() {
        return """
const $laneJsonEscape = ($s) => {
  let $out = "";
  for (let $i = 0; $i < $s.length; $i++) {
    const $c = $s[$i];
    const $b = $s.charCodeAt($i);
    if ($c === "\\"") { $out += "\\\\\\""; }
    else if ($c === "\\\\") { $out += "\\\\\\\\"; }
    else if ($b === 8) { $out += "\\b"; }
    else if ($b === 9) { $out += "\\t"; }
    else if ($b === 10) { $out += "\\n"; }
    else if ($b === 12) { $out += "\\f"; }
    else if ($b === 13) { $out += "\\r"; }
    else if ($b < 32) {
      $out += "\\\\u" + ("0000" + $b.toString(16).toUpperCase()).slice(-4);
    } else { $out += $c; }
  }
  return $out;
};
const $laneWriteTransport = ($e) => {
  let $out = "{";
  let $first = true;
  const $str = ($k, $v) => {
    if (!$first) { $out += ","; }
    $first = false;
    $out += "\\"" + $k + "\\":\\"" + $laneJsonEscape(String($v)) + "\\"";
  };
  const $num = ($k, $v) => {
    if (!$first) { $out += ","; }
    $first = false;
    $out += "\\"" + $k + "\\":" + String($v);
  };
  $str("code", $e.code);
  if ($e.message !== undefined) { $str("message", $e.message); }
  if ($e.file !== undefined) { $str("file", $e.file); }
  if ($e.line !== undefined) { $num("line", $e.line); }
  if ($e.column !== undefined) { $num("column", $e.column); }
  if ($e.expected !== undefined) { $str("expected", $e.expected); }
  if ($e.actual !== undefined) { $str("actual", $e.actual); }
  if ($e.frames !== undefined) { $num("frames", $e.frames); }
  if ($e.cause !== undefined) { $str("cause", $e.cause); }
  $out += "}";
  $fs.writeFileSync("lane_error.json", $out, "utf8");
};
""";
    }

    /** A JS double-quoted string literal with the minimal escapes. */
    private static String jsStringLiteral(String value) {
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
            JsCompilation compilation) {
        return compilation.deploymentMap().get(capturedFile);
    }
}
