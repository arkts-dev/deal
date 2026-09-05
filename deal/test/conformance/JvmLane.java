package deal.test.conformance;

import deal.ast.ExportDeclaration;
import deal.ast.FunctionDeclaration;
import deal.ast.ProgramNode;
import deal.ast.StatementNode;
import deal.codegen.jvm.JvmBackend;
import deal.diagnostics.CompilerDiagnostic;
import deal.lexer.LexResult;
import deal.lexer.Lexer;
import deal.module.CompilationOrchestrator;
import deal.parser.ParseResult;
import deal.parser.Parser;
import deal.project.ProjectContext;
import deal.project.ProjectLocator;
import deal.semantic.CompilerInvocation;
import deal.semantic.ir.CanonicalJson;
import deal.semantic.ir.SemanticIrTextDecodeException;
import deal.test.ConformanceHarnessMetadata;
import deal.test.LegacyProfileRegressionCatalog;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
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
 * The JVM lane of the v1.2 differential gate (ISSUE-0355; design
 * {@code v12-zero-skip-conformance-gate} G4/G5): the absorbed
 * {@code JvmConformanceTest} whole-project pipeline — module
 * materialization → {@code deal.json} → {@link ProjectLocator} →
 * {@link CompilationOrchestrator} → per-module {@link JvmBackend}
 * codegen → javac-semantics compile of every emitted artifact plus the
 * lane runner → a real {@code java} subprocess — executed under the
 * Shared Lane Contract.
 *
 * <p>Per case the lane:</p>
 * <ol>
 *   <li>Probes the required tools {@code javac} + {@code java} once
 *       (G3): a missing or broken-but-present tool is
 *       {@link MismatchClass#TOOL_MISSING}, never a skip.</li>
 *   <li>Materializes the fixture plus its compilation set into a fresh
 *       temp project root ({@code src/} modules with the absorbed
 *       flat-stem/subdirectory layout and explicit-{@code .deal} alias
 *       copies, {@code bindings/} host declarations, and the injected
 *       exact-v1.2 {@code deal.json}) and routes the real
 *       {@link CompilationOrchestrator} through
 *       {@link LegacyProfileRegressionCatalog#invocationFor} — the
 *       per-case A5 invocation seam, so the catalogued
 *       legacy-regression fixtures compile under
 *       {@code LEGACY_REGRESSION + LEGACY_SAFE_INT} exactly as they do
 *       on the legacy harness. A compile failure of any module is a lane
 *       compile failure (an infrastructure outcome with the diagnostic
 *       codes), never an execution outcome.</li>
 *   <li>Asserts artifact presence before execution (G4.2): the emitted
 *       entry {@code .java} artifact must exist after codegen, javac
 *       must accept every emitted {@code .java} artifact plus the lane
 *       runner, and the entry and runner {@code .class} artifacts must
 *       exist after javac — a missing or rejected artifact is
 *       {@link MismatchClass#ARTIFACT_MISSING}, never a fabricated
 *       result. The in-process javac frontend runs with
 *       {@code -g:source,lines} (the absorbed options minus
 *       {@code -g:none}): the lane's error-file capture needs the
 *       {@code SourceFile} attribute of the emitted artifacts, which
 *       {@code -g:none} strips.</li>
 *   <li>Deploys the {@code host-fixtures/&lt;name&gt;.java} triplet
 *       implementations beside the emitted default-package artifacts
 *       under their {@code classNameFor} names (corpus C5): a fixture
 *       importing {@code host/&lt;name&gt;} whose {@code .java} file is
 *       missing is a corpus error ({@link MismatchClass#HARNESS_DEFECT}
 *       naming the missing file), never a skip.</li>
 *   <li>Executes the lane runner in a real {@code java} subprocess with
 *       stdout and stderr captured as separate byte streams. The gate's
 *       dispatcher owns the harness deadline; on interrupt the lane
 *       terminates the spawned subprocess (the Lane contract
 *       requirement).</li>
 *   <li>Invocation contract (G4.4): the lane runner invokes the entry
 *       module's {@code main(): null} exactly once (the backend entry
 *       contract's observable order — {@code main} first, mirroring the
 *       Lua lane's chunk-end invocation) and then auto-invokes each
 *       non-{@code $} zero-arity exported wrapper exactly once, in
 *       declaration order (derived from the compiled AST); return values
 *       are discarded — no lane prints results (the result-printing of
 *       the absorbed {@code buildJvmRunner} is removed, closing the
 *       cross-backend formatting hazard); an async export's blocking
 *       invocation drives the operation to completion before the
 *       verdict (the JVM backend emits async function declarations as
 *       plain blocking methods).</li>
 *   <li>Error framing (G4.6): on an uncaught DEAL error the lane writes
 *       to stdout exactly {@code DEAL_ERROR_CODE: <code>} then
 *       {@code DEAL_ERROR_SNAPSHOT: <canonical JSON>} and exits 1;
 *       success exits 0. The canonical snapshot serialization is the
 *       shared {@link ErrorSnapshot} serializer — the same helper the
 *       LuaJIT and JS lanes reuse verbatim, so the three lanes can
 *       never drift on serialization. The sidecar's Error Expectation
 *       is the authoritative field set: the lane emits the mandatory
 *       fields plus exactly the optional fields the sidecar pins
 *       ({@code expected}, {@code actual}, {@code frames},
 *       {@code cause}) and suppresses every unpinned optional — a
 *       pinned field the captured error does not carry is never
 *       fabricated, so the comparison fails honestly. The lane runner
 *       transports the raw captured error fields through a workspace
 *       payload file (code and message through the absorbed DealError
 *       reflection unwrap; {@code file}/{@code line} from the error's
 *       first emitted-artifact stack frame); the lane normalizes and
 *       frames them. The JVM runtime error carries no
 *       {@code column}/{@code expected}/{@code actual}/{@code frames}/
 *       {@code cause} fields today (the backend epic ISSUE-0276 owns
 *       the convergence): a captured error without the complete
 *       mandatory field set is reported as a process failure naming the
 *       missing field — never fabricated.</li>
 *   <li>{@code sourceFile} normalization (corpus C2): the lane records
 *       its per-module deployment map at compile time (every deployed
 *       module's temp {@code src} path and its emitted artifact class
 *       file name ↔ its canonical corpus-relative path; the host
 *       triplet artifact names ↔ {@code host-fixtures/&lt;name&gt;.java}).
 *       A captured error {@code file} inside the temp project root is
 *       emitted as the canonical corpus-relative path — the fixture's
 *       path, or a throwing companion's path when the companion throws;
 *       an unmappable captured {@code file} value is emitted verbatim,
 *       so the byte comparison fails and surfaces the defect.</li>
 *   <li>Compile-reject path (corpus C6): for a {@code compile-reject}
 *       expectation the lane reports the orchestrator's rejection as
 *       {@link LaneExecution.Rejected} when the first error diagnostic
 *       exists and no entry artifact was emitted — with the actual
 *       diagnostic code, matching or not, so the comparator
 *       cross-checks the exact diagnostic object and reports
 *       {@code COMPILE_REJECT_MISMATCH} with the exact code delta on a
 *       differently-coded rejection (the pinned line/column are
 *       emitted only when the sidecar pins them). A clean compile
 *       under a rejection pin is refused as {@code PROCESS_FAILURE} —
 *       the lane never executes a module whose sidecar pins
 *       rejection.</li>
 * </ol>
 *
 * <p>Pre-flip skip tolerance (G2/G8): the lane keeps the absorbed
 * {@code JvmConformanceTest} skip registry ({@link #skipRegistry()},
 * the capability-skip baseline with its gap ids) and validates it
 * against the on-disk corpus at construction — a stale entry naming a
 * missing or non-runtime-classified fixture is recorded in
 * {@link #registryDefects()} with a promotion instruction. The gate
 * applies the tolerance through {@link #preFlipSkipRegistry()}: a
 * registry-tracked fixture whose lane outcome still fails is reported
 * tracked non-fatal with its gap id; a fixture that starts passing
 * fails the gate with a promotion instruction. The flip (T14) deletes
 * the registry with its mechanisms; until then the legacy
 * {@code JvmConformanceTest} keeps running unchanged in
 * {@code run_tests.sh} (G5's temporary-coexistence window).</p>
 *
 * <p>The lane changes no production file: it reuses the real
 * {@link JvmBackend} as-is; any JVM divergence found is a differential
 * failure reported to ISSUE-0276, never a harness workaround.</p>
 */
public class JvmLane implements Lane {

    /** The lane's backend name (G4: exactly {@code jvm}). */
    public static final String LANE_NAME = "jvm";

    /** The lane runner class name inside the output root. */
    public static final String RUNNER_CLASS_NAME = "JvmLaneRunner";

    /** The raw captured-error transport file name (workspace payload). */
    public static final String TRANSPORT_FILE_NAME = "lane_error.json";

    /** The absorbed project materialization layout names. */
    static final String SRC_DIRECTORY = "src";
    static final String OUTPUT_DIRECTORY = "out";
    static final String BINDINGS_DIRECTORY = "bindings";

    private final Path conformanceRoot;
    private final Path hostFixturesRoot;

    /** The cached tool probe: null until the first probe (G3). */
    private volatile Boolean jvmProbe;

    /**
     * Creates the lane over one conformance root and validates the
     * pre-flip skip registry against the on-disk corpus immediately
     * (a stale entry is recorded in {@link #registryDefects()} with a
     * promotion instruction — the gate also cross-checks the registry
     * against its own discovery as defense in depth).
     *
     * @param conformanceRoot the conformance root (any path form; the
     *                        lane normalizes it)
     */
    public JvmLane(Path conformanceRoot) {
        this.conformanceRoot = Objects.requireNonNull(conformanceRoot,
            "conformanceRoot must not be null").toAbsolutePath().normalize();
        this.hostFixturesRoot = this.conformanceRoot.resolve("host-fixtures");
        validateRegistryInternal();
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

        // Dispatch guards: the JVM lane is registered under its own name.
        if (!LANE_NAME.equals(laneCase.backend())) {
            return new LaneExecution.Infrastructure(MismatchClass.HARNESS_DEFECT,
                "the JVM lane was dispatched for backend "
                    + laneCase.backend() + " — the lane serves exactly "
                    + LANE_NAME);
        }

        // G3: the required tools must be present and functional; absence
        // is a failure, never a skip.
        if (!jvmAvailable()) {
            return new LaneExecution.Infrastructure(MismatchClass.TOOL_MISSING,
                "the required tools javac/java are missing or broken — the "
                    + "lane never skips (G3)");
        }

        JvmCompilation compilation = new JvmCompilation(laneCase);
        CompilationOutcome compileOutcome = compilation.compileCase();

        try {
            // The compile-reject path (corpus C6): the sanctioned
            // per-backend divergence pins an exact diagnostic code and no
            // artifacts.
            if (laneCase.expectation()
                    instanceof SidecarExpectations.RuntimeExpectation.Rejected
                        rejected) {
                return assembleRejection(compileOutcome, rejected,
                    compilation);
            }
            if (!(laneCase.expectation()
                    instanceof SidecarExpectations.RuntimeExpectation.Executed
                        expected)) {
                return new LaneExecution.Infrastructure(
                    MismatchClass.HARNESS_DEFECT,
                    "the JVM lane was dispatched against an unknown "
                        + "expectation shape — the schema closes the "
                        + "expectation variants");
            }
            if (compileOutcome.failure() != null) {
                return new LaneExecution.Infrastructure(
                    MismatchClass.PROCESS_FAILURE, compileOutcome.failure());
            }
            if (compileOutcome.artifactMissing() != null) {
                return new LaneExecution.Infrastructure(
                    MismatchClass.ARTIFACT_MISSING,
                    compileOutcome.artifactMissing());
            }
            Path projectRoot = compilation.projectRoot();
            Path outputRoot = compilation.outputRoot();
            // Host triplet deployment (corpus C5): the <name>.java
            // implementation beside the emitted default-package artifacts
            // under the classNameFor name; a missing implementation is a
            // corpus error, never a skip.
            for (String hostName : compilation.hostNames()) {
                Path hostJava = hostFixturesRoot.resolve(hostName + ".java");
                if (!Files.isRegularFile(hostJava)) {
                    return new LaneExecution.Infrastructure(
                        MismatchClass.HARNESS_DEFECT,
                        "corpus error: the fixture imports host/" + hostName
                            + " but the JVM host implementation "
                            + "host-fixtures/" + hostName + ".java is missing "
                            + "— the lane never skips a host-importing "
                            + "fixture (corpus C5)");
                }
                String hostClass = JvmBackend.classNameFor("host/" + hostName);
                Files.copy(hostJava, outputRoot.resolve(hostClass + ".java"));
                compilation.recordHostArtifact(hostClass + ".java", hostName);
            }

            // The lane runner: main exactly once plus the declaration-order
            // zero-arity export auto-invocation with discarded results.
            Path runnerFile = outputRoot.resolve(RUNNER_CLASS_NAME + ".java");
            Files.writeString(runnerFile, buildRunner(
                compileOutcome.entryProgram(), compileOutcome.entryClass()));

            // javac semantics compile (in-process javax.tools — the
            // identical parse/enter/analyze/generate passes the javac
            // binary runs) over every emitted .java artifact plus the
            // lane runner and the host classes.
            List<String> javaFiles = new ArrayList<>();
            try (var stream = Files.list(outputRoot)) {
                stream.filter(p -> p.toString().endsWith(".java"))
                      .sorted()
                      .forEach(p -> javaFiles.add(p.getFileName().toString()));
            }
            StringBuilder javacErr = new StringBuilder();
            if (!compileWithJavac(outputRoot, javaFiles, javacErr)) {
                return new LaneExecution.Infrastructure(
                    MismatchClass.ARTIFACT_MISSING,
                    "javac rejected the emitted artifacts: "
                        + boundedText(javacErr.toString()));
            }
            beforeExecution(projectRoot, outputRoot);
            List<String> missing = missingClassArtifacts(outputRoot,
                compileOutcome.entryClass());
            if (!missing.isEmpty()) {
                return new LaneExecution.Infrastructure(
                    MismatchClass.ARTIFACT_MISSING,
                    "the generated artifacts are missing before execution: "
                        + String.join(", ", missing)
                        + " — the lane never substitutes a fabricated result");
            }
            SubprocessResult subprocess = runSubprocess(outputRoot);
            return assembleOutcome(expected, subprocess, outputRoot,
                compilation);
        } finally {
            deleteRecursively(compilation.projectRoot());
        }
    }

    /** One lane compilation outcome: the modules or one failure detail. */
    private record CompilationOutcome(
            String failure,
            String artifactMissing,
            ProgramNode entryProgram,
            String entryClass) {

        static CompilationOutcome failure(String detail) {
            return new CompilationOutcome(detail, null, null, null);
        }

        static CompilationOutcome artifactMissing(String detail) {
            return new CompilationOutcome(null, detail, null, null);
        }
    }

    /**
     * Assembles the compile-reject outcome (corpus C6): when the first
     * error diagnostic exists and no entry artifact was emitted, the
     * lane reports the rejection honestly as
     * {@link LaneExecution.Rejected} with the actual diagnostic code —
     * matching or not — so the comparator cross-checks the exact
     * diagnostic object and reports {@code COMPILE_REJECT_MISMATCH}
     * with the exact code delta on a differently-coded rejection (the
     * pinned line/column are emitted only when the sidecar pins them;
     * the comparator rejects an unpinned emitted field). An emitted
     * entry artifact alongside the failure, a clean compile, or a
     * failure without diagnostics is refused as
     * {@code PROCESS_FAILURE} — the lane never executes a module whose
     * sidecar pins rejection and never fabricates a rejection.
     */
    private LaneExecution assembleRejection(CompilationOutcome outcome,
            SidecarExpectations.RuntimeExpectation.Rejected rejected,
            JvmCompilation compilation) {
        if (outcome.failure() != null) {
            List<CompilerDiagnostic> errors =
                compilation.orchestratorErrorDiagnostics();
            Path outputRoot = compilation.outputRoot();
            if (outputRoot == null || errors.isEmpty()) {
                return new LaneExecution.Infrastructure(
                    MismatchClass.PROCESS_FAILURE, outcome.failure());
            }
            CompilerDiagnostic first = errors.get(0);
            Path entryJava = outputRoot.resolve(
                compilation.entryClass() + ".java");
            if (Files.exists(entryJava)) {
                // The pipeline emitted the entry artifact and still
                // failed: the C6 rejection contract (reject before any
                // artifact) is broken — report the pipeline failure
                // honestly, never as a rejection.
                return new LaneExecution.Infrastructure(
                    MismatchClass.PROCESS_FAILURE,
                    outcome.failure());
            }
            // No entry artifact was emitted: the backend genuinely
            // rejected. Report the rejection with its actual code —
            // matching or not — so the comparator cross-checks the
            // exact diagnostic object and reports
            // COMPILE_REJECT_MISMATCH with the exact code delta on
            // divergence (G6 closes "expected or unexpected
            // rejection").
            return new LaneExecution.Rejected(first.code(),
                rejected.line().isPresent()
                    ? OptionalInt.of(first.line())
                    : OptionalInt.empty(),
                rejected.column().isPresent()
                    ? OptionalInt.of(first.column())
                    : OptionalInt.empty());
        }
        if (outcome.artifactMissing() != null) {
            return new LaneExecution.Infrastructure(
                MismatchClass.PROCESS_FAILURE, outcome.artifactMissing());
        }
        // The compile succeeded: the backend did not reject. The caller
        // continues into the execution path only when the expectation is
        // Executed; for a Rejected expectation the lane refuses to
        // execute a module whose sidecar pins rejection and reports the
        // missing rejection as PROCESS_FAILURE — never a fabricated
        // rejection.
        return new LaneExecution.Infrastructure(MismatchClass.PROCESS_FAILURE,
            "the sidecar pins compile rejection with diagnostic code "
                + rejected.code() + " but the real pipeline compiled "
                + "cleanly — the lane never fabricates a rejection");
    }

    // =========================================================================
    // Compilation (the absorbed JvmConformanceTest whole-project path)
    // =========================================================================

    /**
     * The per-module deployment entry of the lane's deployment map
     * (corpus C2): the canonical corpus-relative path of the module
     * whose temp-project artifact the captured error {@code file}
     * names. Unlike the Lua lane, the captured JVM error line is a
     * generated-Java stack line, not a DEAL-source coordinate — no
     * header-line rebase exists on this lane; the value is emitted
     * verbatim so a non-converged location fails the comparison
     * honestly (ISSUE-0276 owns the backend convergence).
     */
    record DeploymentEntry(String corpusPath) {

        DeploymentEntry {
            Objects.requireNonNull(corpusPath, "corpusPath must not be null");
        }
    }

    /**
     * The per-case JVM compilation engine: materializes the compilation
     * set into a fresh temp project root, drives the real
     * {@link CompilationOrchestrator} through the absorbed
     * {@code ProjectLocator} + {@code deal.json} surface, records the
     * deployment map, and asserts the entry artifact.
     */
    final class JvmCompilation {

        private final LaneCase laneCase;
        private final Map<String, SidecarSchemaValidator.CompilationModule>
            setByPath = new HashMap<>();
        /** The deployment map: temp src path / artifact file name →
         * deployment entry. */
        private final Map<String, DeploymentEntry> deploymentMap =
            new LinkedHashMap<>();
        private final Set<String> hostNames = new LinkedHashSet<>();

        private Path projectRoot;
        private Path srcRoot;
        private Path outputRoot;
        private String entryClass;
        private List<CompilerDiagnostic> orchestratorErrors = List.of();

        JvmCompilation(LaneCase laneCase) {
            this.laneCase = laneCase;
            for (SidecarSchemaValidator.CompilationModule module
                    : laneCase.compilationSet()) {
                setByPath.put(module.corpusPath(), module);
            }
        }

        Path projectRoot() {
            return projectRoot;
        }

        Path outputRoot() {
            return outputRoot;
        }

        Set<String> hostNames() {
            return hostNames;
        }

        String entryClass() {
            return entryClass;
        }

        List<CompilerDiagnostic> orchestratorErrorDiagnostics() {
            return orchestratorErrors;
        }

        /** Records a host triplet artifact name in the deployment map. */
        void recordHostArtifact(String artifactName, String hostName) {
            deploymentMap.put(artifactName, new DeploymentEntry(
                "host-fixtures/" + hostName + ".java"));
        }

        /**
         * The deployment map of the case: temp {@code src} path or
         * emitted artifact file name → the canonical corpus-relative
         * path (recorded at compile time — corpus C2).
         */
        Map<String, DeploymentEntry> deploymentMap() {
            return deploymentMap;
        }

        CompilationOutcome compileCase() {
            // Harness consistency: the dispatched fixture file must
            // resolve to the corpus module and the compilation set must
            // be complete for the lane's materialization.
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
            SidecarSchemaValidator.CompilationModule entryModule =
                setByPath.get(laneCase.fixturePath());
            if (entryModule == null) {
                return CompilationOutcome.failure(
                    "the fixture " + laneCase.fixturePath() + " is not part "
                        + "of the fixture's compilation set (harness "
                        + "inconsistency)");
            }

            projectRoot = null;
            try {
                projectRoot = Files.createTempDirectory("deal_jvm_lane_");
                srcRoot = projectRoot.resolve(SRC_DIRECTORY);
                outputRoot = projectRoot.resolve(OUTPUT_DIRECTORY);

                Path entryCorpusFile = conformanceRoot.resolve(
                    laneCase.fixturePath()).toAbsolutePath().normalize();
                Path entryDir = entryCorpusFile.getParent();

                // 1. Materialize every compilation-set module into the
                // temp src root (the absorbed writeModuleFiles layout:
                // companions inside the entry's own corpus directory keep
                // their subdirectory layout; every other module keeps the
                // flat stem layout). The lane writes the header-stripped
                // sources the gate core verified (ISSUE-0272 D8: the
                // orchestrator never lexes a classification header).
                for (SidecarSchemaValidator.CompilationModule module
                        : laneCase.compilationSet()) {
                    Path corpusFile = conformanceRoot.resolve(
                        module.corpusPath()).toAbsolutePath().normalize();
                    if (!Files.exists(corpusFile)) {
                        return CompilationOutcome.failure(
                            "the corpus module " + module.corpusPath()
                                + " does not exist on disk");
                    }
                    String stripped = ConformanceHarnessMetadata
                        .stripClassificationHeaders(
                            Files.readString(corpusFile));
                    if (!stripped.equals(module.source())) {
                        return CompilationOutcome.failure(
                            "harness inconsistency: the stripped source of "
                                + module.corpusPath() + " differs from the "
                                + "gate core's compilation set (the lane "
                                + "compiles exactly the sources the sidecar "
                                + "validation verified)");
                    }
                    Path target = moduleTarget(corpusFile, entryDir);
                    if (target.getParent() != null) {
                        Files.createDirectories(target.getParent());
                    }
                    Files.writeString(target, module.source());
                    deploymentMap.put(target.toString(),
                        new DeploymentEntry(module.corpusPath()));
                    deploymentMap.put(artifactNameFor(target),
                        new DeploymentEntry(module.corpusPath()));
                }

                // Explicit-.deal alias copies (the absorbed
                // copyCompanionAliasIfExplicit rule): an import spelling
                // carrying an explicit .deal extension resolves to its
                // alias-named copy in the flat project root.
                for (SidecarSchemaValidator.CompilationModule module
                        : laneCase.compilationSet()) {
                    Path moduleCorpusDir = Path.of(module.corpusPath())
                        .getParent();
                    Path baseDir = moduleCorpusDir == null
                        ? conformanceRoot
                        : conformanceRoot.resolve(moduleCorpusDir);
                    for (String importPath : relativeImports(module.source())) {
                        Path companion = resolveCompanionPath(importPath,
                            baseDir);
                        if (companion == null) {
                            continue;
                        }
                        String companionCorpus = CorpusDiscovery.slash(
                            conformanceRoot.relativize(companion
                                .toAbsolutePath().normalize()));
                        SidecarSchemaValidator.CompilationModule companionModule =
                            setByPath.get(companionCorpus);
                        if (companionModule == null
                                || !importPath.endsWith(".deal")) {
                            continue;
                        }
                        String aliasBase = importPath;
                        if (aliasBase.startsWith("./")) {
                            aliasBase = aliasBase.substring("./".length());
                        } else if (aliasBase.startsWith("../")) {
                            aliasBase = aliasBase.substring("../".length());
                        }
                        Path aliasTarget = srcRoot.resolve(aliasBase + ".deal");
                        if (Files.exists(aliasTarget)) {
                            continue;
                        }
                        Files.writeString(aliasTarget,
                            companionModule.source());
                        deploymentMap.put(artifactNameFor(aliasTarget),
                            new DeploymentEntry(companionCorpus));
                    }
                }

                // 2. Host bindings: every raw host import of the
                // compilation set wires to its header-free declaration
                // under bindings/ (the absorbed copyHostBindings seam);
                // the implementation .java files deploy after codegen.
                for (SidecarSchemaValidator.CompilationModule module
                        : laneCase.compilationSet()) {
                    hostNames.addAll(hostImports(module.source()));
                }
                for (String hostName : hostNames) {
                    Path decl = hostFixturesRoot.resolve(hostName + ".d.deal");
                    if (!Files.isRegularFile(decl)) {
                        return CompilationOutcome.failure(
                            "host declaration missing for " + hostName
                                + " (host-fixtures/" + hostName
                                + ".d.deal) — the lane cannot wire the "
                                + "externals map");
                    }
                    Files.createDirectories(
                        projectRoot.resolve(BINDINGS_DIRECTORY));
                    Files.writeString(projectRoot.resolve(
                            BINDINGS_DIRECTORY + "/" + hostName + ".d.deal"),
                        ConformanceHarnessMetadata.stripClassificationHeaders(
                            Files.readString(decl)));
                }

                // 3. The injected exact-v1.2 deal.json (the absorbed
                // ISSUE-0269 surface): moduleRoots ["src"], output "out",
                // backend "jvm", externals wiring every raw host import
                // path to its binding declaration.
                StringBuilder dealJson = new StringBuilder();
                dealJson.append("{\n  \"languageVersion\": \"1.2\",\n");
                dealJson.append("  \"moduleRoots\": [\"").append(SRC_DIRECTORY)
                    .append("\"],\n");
                dealJson.append("  \"output\": \"").append(OUTPUT_DIRECTORY)
                    .append("\",\n");
                dealJson.append("  \"backend\": \"jvm\"");
                if (!hostNames.isEmpty()) {
                    dealJson.append(",\n  \"externals\": {\n");
                    boolean first = true;
                    for (String hostName : hostNames) {
                        if (!first) {
                            dealJson.append(",\n");
                        }
                        first = false;
                        dealJson.append("    \"host/").append(hostName)
                            .append("\": { \"declaration\": \"")
                            .append(BINDINGS_DIRECTORY).append("/")
                            .append(hostName).append(".d.deal\" }");
                    }
                    dealJson.append("\n  }");
                }
                dealJson.append("\n}\n");
                Files.writeString(projectRoot.resolve("deal.json"), dealJson);

                String entryRel = SRC_DIRECTORY + "/"
                    + moduleNameOf(entryCorpusFile.getFileName().toString())
                    + ".deal";
                // The entry class name is fixed by the entry's module
                // path (the orchestrator's naming), so the
                // compile-reject path can check whether the entry
                // artifact was emitted even when the orchestrator
                // fails.
                entryClass = entryClassName(entryRel);
                Path entryFile = projectRoot.resolve(entryRel);
                ProjectLocator.LocateResult located =
                    ProjectLocator.locate(entryFile.toString(), null);
                if (located.context() == null) {
                    return CompilationOutcome.failure(
                        "generated deal.json did not locate strictly: "
                            + located.e2010());
                }

                // 4. The real whole-project pipeline: module discovery,
                // signature extraction, dependency ordering, name
                // resolution, type checking, per-module JvmBackend
                // codegen — driven by the published immutable
                // ProjectContext and the per-case A5 invocation seam.
                CompilerInvocation invocation =
                    LegacyProfileRegressionCatalog.invocationFor(
                        laneCase.fixturePath());
                OrchestratorRun run = runOrchestrator(entryFile,
                    located.context(), invocation);
                if (!run.success()) {
                    orchestratorErrors = run.diagnostics();
                    return CompilationOutcome.failure(
                        "the lane compilation failed for "
                            + laneCase.fixturePath() + ": "
                            + describeDiagnostics(run.diagnostics())
                            + (run.capturedOutput().isBlank() ? "" : "\n"
                                + run.capturedOutput()));
                }

                // 5. Codegen was real: the entry artifact must exist
                // before javac runs.
                Path entryJava = outputRoot.resolve(entryClass + ".java");
                if (!Files.exists(entryJava)) {
                    return CompilationOutcome.artifactMissing(
                        "JVM codegen produced no '"
                            + entryJava.getFileName() + "' artifact for "
                            + laneCase.fixturePath()
                            + " — the lane never substitutes a fabricated "
                            + "artifact");
                }

                // 6. The entry program (the runner's export list comes
                // from the real parser over the already-stripped temp
                // copy — the same surface the absorbed runner parses).
                ProgramNode entryProgram = parseEntryProgram(
                    entryModule.source(), entryFile.toString(),
                    invocation.semanticProfile());
                return new CompilationOutcome(null, null, entryProgram,
                    entryClass);
            } catch (IOException e) {
                deleteProjectRoot();
                return CompilationOutcome.failure(
                    "the lane compilation failed for "
                        + laneCase.fixturePath() + ": " + e.getMessage());
            } catch (RuntimeException e) {
                deleteProjectRoot();
                return CompilationOutcome.failure(
                    "the lane compilation failed for "
                        + laneCase.fixturePath() + ": unexpected "
                        + e.getClass().getSimpleName() + ": "
                        + e.getMessage());
            }
        }

        /** Deletes the temp project root (a compile-failure cleanup). */
        private void deleteProjectRoot() {
            if (projectRoot != null) {
                deleteRecursively(projectRoot);
                projectRoot = null;
            }
        }

        /** The materialized temp target of one corpus module (absorbed
         * copyTransitively layout). */
        private Path moduleTarget(Path corpusFile, Path entryDir) {
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

        /** The emitted artifact class name of one materialized module
         * (the orchestrator's module naming: temp-root-relative path
         * minus the .deal suffix, dotted, through classNameFor). */
        private String artifactNameFor(Path target) {
            String rel = CorpusDiscovery.slash(srcRoot.relativize(target));
            if (rel.endsWith(".deal")) {
                rel = rel.substring(0, rel.length() - ".deal".length());
            }
            return JvmBackend.classNameFor(rel.replace('/', '.')) + ".java";
        }
    }

    private record OrchestratorRun(boolean success,
                                   List<CompilerDiagnostic> diagnostics,
                                   String capturedOutput) { }

    /**
     * Runs the real {@link CompilationOrchestrator} with the
     * context-driven production constructor over the temp project
     * (ISSUE-0269). Stdout/stderr is captured under the shared console
     * lock so per-case output stays clean and parallel workers never
     * interleave (the absorbed runner's CONSOLE_LOCK pattern).
     */
    private static OrchestratorRun runOrchestrator(Path entryFile,
            ProjectContext context, CompilerInvocation invocation) {
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        synchronized (JvmLane.class) {
            PrintStream originalOut = System.out;
            PrintStream originalErr = System.err;
            try {
                System.setOut(new PrintStream(captured, true,
                    StandardCharsets.UTF_8));
                System.setErr(new PrintStream(captured, true,
                    StandardCharsets.UTF_8));
                CompilationOrchestrator orchestrator =
                    new CompilationOrchestrator(context,
                        entryFile.toAbsolutePath().normalize(),
                        false, false, false, false, null, invocation);
                boolean success = orchestrator.compile();
                return new OrchestratorRun(success,
                    orchestrator.diagnostics(),
                    captured.toString(StandardCharsets.UTF_8));
            } catch (IOException e) {
                return new OrchestratorRun(false, List.of(),
                    "orchestrator I/O failure: " + e + "\n"
                        + captured.toString(StandardCharsets.UTF_8));
            } finally {
                System.out.flush();
                System.err.flush();
                System.setOut(originalOut);
                System.setErr(originalErr);
            }
        }
    }

    /** The corpus module name of a .deal file: its stem (absorbed helper). */
    private static String moduleNameOf(String fileName) {
        if (fileName.endsWith(".d.deal")) {
            return fileName.substring(0, fileName.length() - ".d.deal".length());
        }
        if (fileName.endsWith(".deal")) {
            return fileName.substring(0, fileName.length() - ".deal".length());
        }
        return fileName;
    }

    /**
     * Class name of the entry module's emitted artifact: the temp
     * project root is the single module root, so the entry's module path
     * is its file stem and {@link JvmBackend#classNameFor} derives the
     * class name the orchestrator used (the absorbed entryClassName
     * rule).
     */
    private static String entryClassName(String entryRel) {
        String path = entryRel;
        if (path.startsWith(SRC_DIRECTORY + "/")) {
            path = path.substring((SRC_DIRECTORY + "/").length());
        }
        if (path.endsWith(".deal")) {
            path = path.substring(0, path.length() - ".deal".length());
        }
        return JvmBackend.classNameFor(
            path.replace('/', '.').replace('\\', '.'));
    }

    /**
     * Parses the entry module with the real lexer + parser for the
     * runner (its export list drives auto-invocation). Type checking is
     * NOT re-run here — the orchestrator already checked every module —
     * so this parse cannot act as a checker bypass (the absorbed
     * parseEntryProgram surface).
     */
    private static ProgramNode parseEntryProgram(String source,
            String filename, deal.semantic.ir.SemanticProfile profile)
            throws IOException {
        LexResult lex = new Lexer(source, filename).tokenize();
        if (lex.hasErrors()) {
            throw new IllegalStateException("entry module lex errors: "
                + lex.diagnostics());
        }
        ParseResult parse = new Parser(lex.tokens(), filename, profile)
            .parse();
        if (parse.hasErrors()) {
            throw new IllegalStateException("entry module parse errors: "
                + parse.diagnostics());
        }
        return parse.program();
    }

    /**
     * The ordered auto-invocation list (G4.4): every non-{@code $}
     * zero-arity exported function of the entry module in declaration
     * order, excluding {@code main} (the lane runner invokes
     * {@code main}: null exactly once before the list, mirroring the Lua
     * lane's chunk-end entry contract).
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
                names.add(JvmBackend.javaName(fn.name()));
            }
        }
        return names;
    }

    // =========================================================================
    // Materialization helpers (absorbed verbatim)
    // =========================================================================

    /** Relative import paths ({@code ./} / {@code ../}) appearing in the
     * source, in order (the absorbed relativeImports line scan). */
    private static List<String> relativeImports(String source) {
        List<String> paths = new ArrayList<>();
        for (String line : source.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("import ")) {
                continue;
            }
            int from = trimmed.indexOf(" from \"");
            if (from < 0) {
                continue;
            }
            int end = trimmed.indexOf('"', from + 7);
            if (end < 0) {
                continue;
            }
            String path = trimmed.substring(from + 7, end);
            if (path.startsWith("./") || path.startsWith("../")) {
                paths.add(path);
            }
        }
        return paths;
    }

    /** Bare host import names ({@code host/<name>}) appearing in the
     * source, in order (drives deal.json externals generation). */
    private static Set<String> hostImports(String source) {
        Set<String> hosts = new LinkedHashSet<>();
        for (String line : source.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("import ")) {
                continue;
            }
            int from = trimmed.indexOf(" from \"");
            if (from < 0) {
                continue;
            }
            int end = trimmed.indexOf('"', from + 7);
            if (end < 0) {
                continue;
            }
            String path = trimmed.substring(from + 7, end);
            if (path.startsWith("host/")) {
                hosts.add(path.substring("host/".length()));
            }
        }
        return hosts;
    }

    /** Resolve a relative import path to a .deal/.d.deal file on disk
     * (the same resolution the LuaJIT conformance harness uses). */
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

    /** Bounded compile-diagnostic detail (code at line:column). */
    private static String describeDiagnostics(
            List<CompilerDiagnostic> diagnostics) {
        if (diagnostics == null) {
            return "no error diagnostics";
        }
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
     * Compiles the named {@code .java} files in {@code dir} with the
     * javac frontend in-process (javax.tools — the identical
     * parse/enter/analyze/generate passes the {@code javac} binary
     * runs). The absorbed {@code -g:none} option is replaced with
     * {@code -g:source,lines}: the lane's error-file capture reads the
     * emitted artifacts' {@code SourceFile} attribute (stripped by
     * {@code -g:none}), and no fixture consumes the local-variable
     * tables the option omits. Returns true when javac accepted every
     * file; appends diagnostics to {@code err} otherwise.
     */
    private static boolean compileWithJavac(Path dir, List<String> javaFiles,
            StringBuilder err) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            err.append("no system Java compiler available (javax.tools)");
            return false;
        }
        DiagnosticCollector<JavaFileObject> diagnostics =
            new DiagnosticCollector<>();
        try (StandardJavaFileManager fileManager = compiler
                .getStandardFileManager(diagnostics, null,
                    StandardCharsets.UTF_8)) {
            List<java.io.File> files = new ArrayList<>();
            for (String name : javaFiles) {
                files.add(dir.resolve(name).toFile());
            }
            Iterable<? extends JavaFileObject> units =
                fileManager.getJavaFileObjectsFromFiles(files);
            StringWriter messages = new StringWriter();
            List<String> options = List.of(
                "-encoding", "UTF-8",
                "-classpath", dir.toString(),
                "-d", dir.toString(),
                "-proc:none",
                "-implicit:none",
                "-g:source,lines");
            Boolean ok = compiler.getTask(messages, fileManager, diagnostics,
                options, null, units).call();
            if (!Boolean.TRUE.equals(ok)) {
                err.append(messages);
                for (Diagnostic<? extends JavaFileObject> diagnostic
                        : diagnostics.getDiagnostics()) {
                    err.append(diagnostic.toString()).append('\n');
                }
                return false;
            }
            return true;
        } catch (IOException e) {
            err.append(e.toString());
            return false;
        }
    }

    /** Returns the required {@code .class} artifacts that are absent, in
     * order (empty when every artifact is present). */
    private static List<String> missingClassArtifacts(Path outputRoot,
            String entryClass) {
        List<String> missing = new ArrayList<>();
        for (String name : List.of(entryClass + ".class",
                RUNNER_CLASS_NAME + ".class")) {
            if (!Files.exists(outputRoot.resolve(name))) {
                missing.add(name);
            }
        }
        return missing;
    }

    private static void deleteRecursively(Path directory) {
        if (directory == null) {
            return;
        }
        try (var stream = Files.walk(directory)) {
            stream.sorted(Comparator.reverseOrder())
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
     * Probes the required {@code javac} + {@code java} tools once per
     * lane instance: both binaries must exist and exit zero on the
     * version probe, and the in-process javac frontend
     * (javax.tools) must be available — a broken-but-present tool is
     * {@code TOOL_MISSING} too (G3).
     */
    protected boolean jvmAvailable() {
        Boolean probe = jvmProbe;
        if (probe == null) {
            synchronized (this) {
                probe = jvmProbe;
                if (probe == null) {
                    probe = probeJvm();
                    jvmProbe = probe;
                }
            }
        }
        return probe;
    }

    private static boolean probeJvm() {
        try {
            Process javac = new ProcessBuilder("javac", "-version")
                .redirectErrorStream(true).start();
            javac.getInputStream().readAllBytes();
            if (javac.waitFor() != 0) {
                return false;
            }
            Process java = new ProcessBuilder("java", "-version")
                .redirectErrorStream(true).start();
            java.getInputStream().readAllBytes();
            if (java.waitFor() != 0) {
                return false;
            }
            return ToolProvider.getSystemJavaCompiler() != null;
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
     * Runs the deployed lane runner in a real {@code java} subprocess
     * over the emitted output root, capturing stdout and stderr as
     * separate byte streams. On interrupt the subprocess is terminated
     * (the Lane contract requirement the dispatcher's deadline
     * enforcement depends on) and the interrupt is re-asserted.
     */
    private static SubprocessResult runSubprocess(Path outputRoot)
            throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder("java", "-cp",
            outputRoot.toString(), RUNNER_CLASS_NAME);
        builder.directory(outputRoot.toFile());
        Process process = builder.start();

        StreamDrain stdoutDrain = new StreamDrain(process.getInputStream());
        StreamDrain stderrDrain = new StreamDrain(process.getErrorStream());
        Thread stdoutThread = new Thread(stdoutDrain, "jvm-lane-stdout");
        Thread stderrThread = new Thread(stderrDrain, "jvm-lane-stderr");
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

    /** The raw captured error fields transported by the lane runner
     * (package-private: the scratch transport-injection probes construct
     * it). */
    record CapturedError(
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
     * Reads and parses the runner's raw captured-error transport from
     * the output root, or {@code null} when the run carried no DEAL
     * error payload.
     */
    protected CapturedError readTransport(Path outputRoot) {
        Path transport = outputRoot.resolve(TRANSPORT_FILE_NAME);
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
     * suppresses every unpinned one; a pinned field the captured error
     * does not carry is never fabricated — the comparison fails
     * honestly). A captured error without the complete mandatory field
     * set is a process failure naming the missing field (the JVM runtime
     * error carries no {@code column} today — the backend epic
     * ISSUE-0276 owns the convergence; the lane never fabricates a
     * location). A non-zero exit without a DEAL error payload is a
     * subprocess failure outside the DEAL outcome surface.
     */
    private LaneExecution assembleOutcome(
            SidecarExpectations.RuntimeExpectation.Executed expectation,
            SubprocessResult subprocess, Path outputRoot,
            JvmCompilation compilation) {
        CapturedError captured = readTransport(outputRoot);
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
                    + "snapshot; the JVM runtime error carries no column/"
                    + "expected/actual/frames/cause fields yet (ISSUE-0276 "
                    + "owns the backend convergence), and the lane never "
                    + "fabricates them; captured fields: code="
                    + boundedString(captured.code())
                    + ", message=" + boundedString(captured.message())
                    + ", file=" + boundedString(captured.file())
                    + ", line=" + captured.line() + ", column="
                    + captured.column());
        }

        // sourceFile normalization (corpus C2): map the captured file
        // through the per-module deployment map; an unmappable file is
        // emitted verbatim so the byte comparison fails and surfaces the
        // defect. The captured line is a generated-Java stack line — it
        // is emitted verbatim (no DEAL-coordinate rebase exists on this
        // lane) so a non-converged location fails honestly.
        DeploymentEntry deployment = deploymentEntryFor(captured.file(),
            compilation);
        String sourceFile = deployment != null
            ? deployment.corpusPath()
            : captured.file();

        // The sidecar is the authoritative field set: emit exactly the
        // pinned optional fields (no error expectation pins nothing).
        SidecarExpectations.ErrorExpectation pinned = expectation.error();
        SidecarExpectations.ErrorExpectation snapshot =
            new SidecarExpectations.ErrorExpectation(
                captured.code(), captured.message(), sourceFile,
                captured.line(), captured.column(),
                optionalField(pinned, "expected", captured.expected()),
                optionalField(pinned, "actual", captured.actual()),
                optionalField(pinned, "frames", captured.frames()),
                optionalField(pinned, "cause", captured.cause()));

        String framing = framing(snapshot);
        byte[] framedStdout = concat(subprocess.stdout(),
            framing.getBytes(StandardCharsets.UTF_8));
        return new LaneExecution.Executed(framedStdout, subprocess.stderr(),
            subprocess.exitCode());
    }

    /**
     * The G4.6 framing lines of one snapshot, built by the shared
     * {@link ErrorSnapshot} serializer (never a lane-local copy — the
     * three lanes cannot drift on serialization).
     */
    protected String framing(
            SidecarExpectations.ErrorExpectation snapshot) {
        return ErrorSnapshot.CODE_LINE_PREFIX + snapshot.code()
            + "\n" + ErrorSnapshot.SNAPSHOT_LINE_PREFIX
            + ErrorSnapshot.canonicalJson(snapshot) + "\n";
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
    private static String boundedText(String text) {
        if (text == null) {
            return "";
        }
        String flat = text.replace("\n", "\\n").replace("\r", "\\r");
        if (flat.length() > 200) {
            return flat.substring(0, 197) + "...";
        }
        return flat;
    }

    private static String boundedText(byte[] bytes) {
        return boundedText(new String(bytes, StandardCharsets.UTF_8));
    }

    // =========================================================================
    // The lane runner template (G4.4 + the transport half of G4.6)
    // =========================================================================

    /**
     * Builds the lane runner Java source: invokes the entry module's
     * {@code main()} exactly once (the backend entry contract — the
     * emitted {@code main(String[])} entry point is never invoked, so
     * {@code main}: null never runs twice), then auto-invokes each
     * ordered zero-arity export exactly once in declaration order with
     * discarded results (no lane prints results — the absorbed runner's
     * result-printing is removed), and transports any uncaught DEAL
     * error's raw fields to the workspace payload file for the lane's
     * canonical framing. Non-DEAL errors transport nothing.
     *
     * <p><b>Test seam:</b> scratch lane variants override this method for
     * the lane-contract probes (printing a return value, invoking
     * {@code main} twice, reordering the export list).</p>
     */
    protected String buildRunner(ProgramNode program, String entryClass) {
        List<String> exports = orderedZeroArityExports(program);
        StringBuilder sb = new StringBuilder();
        sb.append("// Generated by deal.test.conformance.JvmLane — JVM lane ")
            .append("runner (G4).\n");
        sb.append("public final class ").append(RUNNER_CLASS_NAME)
            .append(" {\n");
        sb.append("    public static void main(String[] args) {\n");
        sb.append("        try {\n");
        sb.append("            ").append(entryClass).append(".main();\n");
        for (String exportName : exports) {
            sb.append("            ").append(entryClass).append('.')
                .append(exportName).append("();\n");
        }
        sb.append("        } catch (Throwable e) {\n");
        sb.append("            transport(e);\n");
        sb.append("            System.exit(1);\n");
        sb.append("        }\n");
        sb.append("    }\n");
        sb.append("    // A DEAL error raised inside an IMPORTED module is that\n");
        sb.append("    // module's own nested DealError class, so it is not an\n");
        sb.append("    // instance of the entry module's DealError and no catch\n");
        sb.append("    // above names its type. Every emitted DealError is a\n");
        sb.append("    // RuntimeException whose simple name is \"DealError\" with\n");
        sb.append("    // a package-private String `code` field; the reflection\n");
        sb.append("    // unwrap reports the DEAL code and message uniformly (the\n");
        sb.append("    // absorbed runner's reportError unwrap).\n");
        sb.append("    private static void transport(Throwable e) {\n");
        sb.append("        Throwable t = e;\n");
        sb.append("        while (t instanceof ExceptionInInitializerError\n");
        sb.append("                && t.getCause() != null) {\n");
        sb.append("            t = t.getCause();\n");
        sb.append("        }\n");
        sb.append("        String code = null;\n");
        sb.append("        if (\"DealError\".equals(t.getClass().getSimpleName())) {\n");
        sb.append("            try {\n");
        sb.append("                java.lang.reflect.Field f = t.getClass()\n");
        sb.append("                    .getDeclaredField(\"code\");\n");
        sb.append("                f.setAccessible(true);\n");
        sb.append("                code = java.lang.String.valueOf(f.get(t));\n");
        sb.append("            } catch (ReflectiveOperationException ignored) { }\n");
        sb.append("        }\n");
        sb.append("        if (code == null) {\n");
        sb.append("            return; // non-DEAL error: transport nothing\n");
        sb.append("        }\n");
        sb.append("        String file = null;\n");
        sb.append("        int line = -1;\n");
        sb.append("        StackTraceElement[] frames = t.getStackTrace();\n");
        sb.append("        for (StackTraceElement f : frames) {\n");
        sb.append("            if (!\"").append(RUNNER_CLASS_NAME).append("\"")
            .append(".equals(f.getClassName())) {\n");
        sb.append("                file = f.getFileName();\n");
        sb.append("                line = f.getLineNumber();\n");
        sb.append("                break;\n");
        sb.append("            }\n");
        sb.append("        }\n");
        sb.append("        // The JVM runtime error carries code/message plus the\n");
        sb.append("        // stack-trace file/line only: column, expected, actual,\n");
        sb.append("        // frames, and cause are never transported (the lane\n");
        sb.append("        // reports the incomplete capture honestly).\n");
        sb.append("        StringBuilder json = new StringBuilder(\"{\");\n");
        sb.append("        json.append(\"\\\"code\\\":\").append(js(code));\n");
        sb.append("        String message = t.getMessage();\n");
        sb.append("        if (message != null) {\n");
        sb.append("            json.append(\",\\\"message\\\":\").append(js(message));\n");
        sb.append("        }\n");
        sb.append("        if (file != null) {\n");
        sb.append("            json.append(\",\\\"file\\\":\").append(js(file));\n");
        sb.append("        }\n");
        sb.append("        if (line >= 0) {\n");
        sb.append("            json.append(\",\\\"line\\\":\").append(line);\n");
        sb.append("        }\n");
        sb.append("        json.append(\"}\");\n");
        sb.append("        try {\n");
        sb.append("            java.nio.file.Files.writeString(\n");
        sb.append("                java.nio.file.Path.of(\"").append(TRANSPORT_FILE_NAME)
            .append("\"), json);\n");
        sb.append("        } catch (java.io.IOException ignored) { }\n");
        sb.append("    }\n");
        sb.append("    // Minimal JSON string escaping (the lane re-serializes\n");
        sb.append("    // canonically through the shared serializer, so this\n");
        sb.append("    // transport never competes with the canonical form).\n");
        sb.append("    private static String js(String s) {\n");
        sb.append("        StringBuilder out = new StringBuilder(\"\\\"\");\n");
        sb.append("        for (int i = 0; i < s.length(); i++) {\n");
        sb.append("            char c = s.charAt(i);\n");
        sb.append("            switch (c) {\n");
        sb.append("                case '\"': out.append(\"\\\\\\\"\"); break;\n");
        sb.append("                case '\\\\': out.append(\"\\\\\\\\\"); break;\n");
        sb.append("                case '\\b': out.append(\"\\\\b\"); break;\n");
        sb.append("                case '\\t': out.append(\"\\\\t\"); break;\n");
        sb.append("                case '\\n': out.append(\"\\\\n\"); break;\n");
        sb.append("                case '\\f': out.append(\"\\\\f\"); break;\n");
        sb.append("                case '\\r': out.append(\"\\\\r\"); break;\n");
        sb.append("                default:\n");
        sb.append("                    if (c < 0x20) {\n");
        sb.append("                        out.append(String.format(\"\\\\u%04X\", ")
            .append("(int) c));\n");
        sb.append("                    } else {\n");
        sb.append("                        out.append(c);\n");
        sb.append("                    }\n");
        sb.append("            }\n");
        sb.append("        }\n");
        sb.append("        return out.append('\"').toString();\n");
        sb.append("    }\n");
        sb.append("}\n");
        return sb.toString();
    }

    // =========================================================================
    // Pre-flip skip registry (G2/G8 tolerance)
    // =========================================================================

    /** One skip-registry entry: corpus-relative path, documented reason,
     * and gap id (absorbed verbatim from {@code JvmConformanceTest.SKIPS}). */
    public record SkipEntry(String path, String reason, String gapId) {

        public SkipEntry {
            Objects.requireNonNull(path, "path must not be null");
            Objects.requireNonNull(reason, "reason must not be null");
            Objects.requireNonNull(gapId, "gapId must not be null");
        }
    }

    /** The complete skip registry (absorbed verbatim from
     * {@code JvmConformanceTest.SKIPS}). Every entry must name an
     * on-disk runtime-classified corpus test; the lane validates the
     * registry against the corpus at construction and the gate
     * cross-checks it against its own discovery, so a stale entry fails
     * the gate with a promotion instruction. */
    private static final Map<String, SkipEntry> SKIPS = new LinkedHashMap<>();
    static {
        // ---- JVM-GAP-STDJSON: the std/json JVM boundary ----
        skip("backend-runtime/class-runtime-errors/dynamic-bad-class-array-element-e8001.deal",
            "json.parse builds the dynamic array value.", "JVM-GAP-STDJSON");
        skip("backend-runtime/class-runtime-errors/dynamic-bad-class-param-e8001.deal",
            "json.parse builds the dynamic class value.", "JVM-GAP-STDJSON");
        skip("backend-runtime/class-runtime-errors/dynamic-bad-class-return-e8001.deal",
            "json.parse builds the dynamic class value.", "JVM-GAP-STDJSON");
        skip("backend-runtime/class-runtime-errors/dynamic-bad-imported-class-param-e8001.deal",
            "json.parse builds the dynamic imported-class value.",
            "JVM-GAP-STDJSON");
        skip("backend-runtime/class-runtime-errors/dynamic-bad-nullable-class-e8001.deal",
            "json.parse builds the dynamic nullable-class value.",
            "JVM-GAP-STDJSON");
        skip("backend-runtime/runtime-errors/json-stringify-function-e8001.deal",
            "json.stringify of a function-holding table.",
            "JVM-GAP-STDJSON");
        skip("backend-runtime/source-location/json-error-source.deal",
            "json.stringify of a function-holding table (E8001) requires "
                + "the std/json JVM boundary.", "JVM-GAP-STDJSON");
        skip("backend-runtime/source-location-precision/class-param-error-source.deal",
            "json.parse builds the dynamic class value.", "JVM-GAP-STDJSON");
        skip("backend-runtime/type-system/dynamic-array-element-e8003.deal",
            "json.parse of a mixed array.", "JVM-GAP-STDJSON");
        skip("backend-runtime/stdlib/json/int32-boundary-parse.deal",
            "json.parse int32 number mapping (2147483647/2147483648/"
                + "-2147483648/-2147483649/-0) and stringify output.",
            "JVM-GAP-STDJSON");
        skip("backend-runtime/stdlib/json/json-stringify-roundtrip.deal",
            "json.parse/stringify int-number document round-trips.",
            "JVM-GAP-STDJSON");
        skip("backend-runtime/stdlib/json/json-stringify-bytes-error.deal",
            "json.stringify of a bytes-holding table (E8001).",
            "JVM-GAP-STDJSON");
        skip("backend-runtime/stdlib/table/keys-nonstring-exclusion.deal",
            "json.parse builds the integer-keyed array table whose "
                + "non-string keys the fixture pins excluded from "
                + "std/table.keys.", "JVM-GAP-STDJSON");

        // ---- JVM-GAP-DESCRIPTORS: retired with the canonical matcher
        // realization (ISSUE-0301) ----
        // The emitted $check function row raises E8010 on any
        // carried-descriptor delta, so canonical-sig-mismatch-e8010
        // passes the real pipeline and the stale-skip gate forced the
        // entry's removal.

        // ---- JVM-GAP-INT32: the signed-int32 runtime gate ----
        // The profile-selected JVM int32 helper bodies landed
        // (ISSUE-0394), so the retained v1.2 invocation raises E8004 for
        // the arithmetic/conversion/negation cases — those entries
        // became stale under the A5 seam and were removed with the
        // promotions. The std/math.absInt residual was resolved by
        // ISSUE-0397 I6 and the stale skip was removed.

        // ---- JVM-GAP-BYTES: the bytes runtime lane (ISSUE-0277) ----
        // The v1.2 corpus pins the FFI-backed bytes carrier. The JVM
        // backend has no bytes lane yet (E6000 at every bytes site), so
        // the LuaJIT-owned bytes expectations below cannot pass on JVM.
        // Once JVM bytes lands (ISSUE-0277), the probes start passing
        // and the stale-skip gate forces these entries out.
        skip("backend-runtime/bytes/bytes-buffer-ops.deal",
            "the zero-filled bytes buffer, 0..255 byte writes, and "
                + "reference-copy semantics require the bytes carrier; "
                + "JvmBackend raises E6000 at bytes sites (ISSUE-0277).",
            "JVM-GAP-BYTES");
        skip("backend-runtime/bytes/bytes-index-bounds.deal",
            "E8012 on byte reads/writes outside [0, b.length) requires "
                + "the bytes carrier; JvmBackend raises E6000 at bytes "
                + "sites (ISSUE-0277).", "JVM-GAP-BYTES");
        skip("backend-runtime/bytes/bytes-write-range.deal",
            "E8013 on byte values outside 0..255 requires the bytes "
                + "carrier; JvmBackend raises E6000 at bytes sites "
                + "(ISSUE-0277).", "JVM-GAP-BYTES");
        skip("backend-runtime/bytes/bytes-length.deal",
            "the compiler-resolved bytes .length requires the bytes "
                + "carrier; JvmBackend raises E6000 at bytes sites "
                + "(ISSUE-0277).", "JVM-GAP-BYTES");
        skip("backend-runtime/bytes/bytes-descriptor-boundary.deal",
            "canonical [bytes]/?(bytes)/function bytes descriptors "
                + "require the bytes carrier; JvmBackend raises E6000 at "
                + "bytes sites (ISSUE-0277).", "JVM-GAP-BYTES");
        skip("backend-runtime/bytes/bytes-class-field-descriptor.deal",
            "class fields carrying real bytes buffers require the bytes "
                + "carrier; JvmBackend raises E6000 at bytes sites "
                + "(ISSUE-0277).", "JVM-GAP-BYTES");
        skip("backend-runtime/bytes/bytes-write-single-evaluation.deal",
            "the once-only receiver/index/RHS bytes write sequence "
                + "requires the bytes carrier; JvmBackend raises E6000 at "
                + "bytes sites (ISSUE-0277).", "JVM-GAP-BYTES");
        skip("backend-runtime/bytes/bytes-write-validation-order.deal",
            "validation-after-RHS bytes write ordering requires the "
                + "bytes carrier; JvmBackend raises E6000 at bytes sites "
                + "(ISSUE-0277).", "JVM-GAP-BYTES");
        skip("backend-runtime/source-location/bytes-index-bounds-source.deal",
            "the E8012 bytes bounds location requires the bytes carrier; "
                + "JvmBackend raises E6000 at bytes sites (ISSUE-0277).",
            "JVM-GAP-BYTES");
        skip("backend-runtime/source-location/bytes-write-range-source.deal",
            "the E8013 bytes value location requires the bytes carrier; "
                + "JvmBackend raises E6000 at bytes sites (ISSUE-0277).",
            "JVM-GAP-BYTES");

        // ---- JVM-GAP-JSONABLE-RESIDUAL: residual @jsonable JVM defects ----
        // The two error-typed member-access/NEQ entries retired with
        // the orchestrator's cross-module checked-fact resolution
        // (ISSUE-0326): their skip entries were stale and the gate
        // forced the removal.
        skip("backend-runtime/jsonable/jsonable-fromjson-top-level-scalar.deal",
            "requires @jsonable code generation and the std/json boundary.",
            "JVM-GAP-JSONABLE-RESIDUAL");
        skip("backend-runtime/jsonable/jsonable-optional-nullable-nested-class.deal",
            "E6000: NEQ over error/null/int and member access as a value.",
            "JVM-GAP-JSONABLE-RESIDUAL");
        skip("backend-runtime/jsonable/nested-array-roundtrip.deal",
            "emitted $fromJsonValue redeclares locals (l0/a0/i0/e0); "
                + "javac rejects the artifact.", "JVM-GAP-JSONABLE-RESIDUAL");
        skip("backend-runtime/jsonable/jsonable-table-field-nested-arrays.deal",
            "runtime E8001 \"value is not JSON-shaped\": toJson of a "
                + "table field holding nested arrays.",
            "JVM-GAP-JSONABLE-RESIDUAL");
        // jsonable-tojson-rejects-cyclic-table.deal is deliberately NOT
        // registered: it passes on JVM — the ISSUE-0168 JVM slice's own
        // cycle detection raises E8001 — so a skip entry would be stale
        // and fail the stale-skip gate deterministically.

        // ---- JVM-GAP-HOST-ABI-SHAPES: unsupported declared host shapes ----
        skip("backend-runtime/host-abi/host-array-return-ok.deal",
            "E6000: declared array-typed host return (the JVM host ABI "
                + "slice supports primitive/string/nullable returns "
                + "only).", "JVM-GAP-HOST-ABI-SHAPES");
        skip("backend-runtime/host-abi/host-rest-ok.deal",
            "E6000: declared array-typed host parameter (v1.2 fixed-array "
                + "host form).", "JVM-GAP-HOST-ABI-SHAPES");
        skip("backend-runtime/host-abi/host-rest-bad.deal",
            "E6000: declared array-typed host parameter (v1.2 fixed-array "
                + "host form).", "JVM-GAP-HOST-ABI-SHAPES");
        skip("backend-runtime/host-abi/host-boundary-apply-function.deal",
            "E6000: declared function-typed host parameter.",
            "JVM-GAP-HOST-ABI-SHAPES");
        skip("backend-runtime/host-abi/host-nullable-function-param.deal",
            "E6000: declared function | null host parameter.",
            "JVM-GAP-HOST-ABI-SHAPES");
        skip("backend-runtime/host-abi/host-nullable-function-param-bad.deal",
            "E6000: declared function | null host parameter.",
            "JVM-GAP-HOST-ABI-SHAPES");
        skip("backend-runtime/host-abi/host-nullable-function-return-ok.deal",
            "E6000: declared function | null host return.",
            "JVM-GAP-HOST-ABI-SHAPES");
        skip("backend-runtime/host-abi/host-nullable-function-return-bad.deal",
            "E6000: declared function | null host return.",
            "JVM-GAP-HOST-ABI-SHAPES");
        skip("backend-runtime/host-abi/host-class-export.deal",
            "host class exports unsupported on JVM: E3004 \"Unknown "
                + "class 'ServerConfig'\" (the JVM externals path "
                + "synthesizes no host class symbols and the backend "
                + "rejects host class exports).", "JVM-GAP-HOST-ABI-SHAPES");
        skip("backend-runtime/host-abi/host-export-presence.deal",
            "host class exports unsupported on JVM: E3004 \"Unknown "
                + "class 'Config'\" (same root cause).",
            "JVM-GAP-HOST-ABI-SHAPES");
        skip("backend-runtime/host-abi/host-class-default-isolation.deal",
            "host class exports unsupported on JVM: E3004 \"Unknown "
                + "class 'ServerConfig'\" (same root cause as "
                + "host-class-export).", "JVM-GAP-HOST-ABI-SHAPES");
        skip("backend-runtime/host-abi/host-class-extra-field.deal",
            "host class exports unsupported on JVM: E3004 \"Unknown "
                + "class 'Config'\" (same root cause as "
                + "host-export-presence).", "JVM-GAP-HOST-ABI-SHAPES");
        skip("backend-runtime/host-abi/host-prewrapped-ok.deal",
            "the Lua pre-wrapped export form (sig-annotated tables) is a "
                + "LuaJIT host-loader mechanism with no JVM analog (no "
                + "Java host implementation can express it).",
            "JVM-GAP-HOST-ABI-SHAPES");
        skip("backend-runtime/host-abi/host-prewrapped-bad.deal",
            "the Lua pre-wrapped export form; no JVM analog.",
            "JVM-GAP-HOST-ABI-SHAPES");
        skip("backend-runtime/defaults/plan-host-discriminator.deal",
            "host class exports unsupported on JVM: E3004 \"Unknown "
                + "class 'ServerConfig'\" (the JVM externals path "
                + "synthesizes no host class symbols and the backend "
                + "rejects host class exports — same root cause as "
                + "host-class-export).", "JVM-GAP-HOST-ABI-SHAPES");

        // ---- JVM-GAP-DEFAULTS-PLANS: the v1.2 default-plan lane
        // (ISSUE-0340, LuaJIT-owned) ----
        // The defaults corpus pins per-attempt default plans: imported
        // non-literal defaults run in the declaring module's scope under
        // LuaJIT, and the phase-order fixture probes provided-value
        // evaluation before defaults with a caught E8002. The JVM
        // backend evaluates defaults inline per call and rejects
        // non-literal defaults on imported classes with E6000, and its
        // slice rejects the Error-typed nullable local plus the
        // catch-assignment pattern of the phase-order probe. Both
        // fixtures stay LuaJIT/JS-lane pins until JVM default plans land
        // (ISSUE-0277).
        skip("backend-runtime/defaults/plan-imported-provider-scope.deal",
            "E6000: non-literal default expression on an imported class "
                + "(JVM defaults evaluate in the declaring module's "
                + "scope under LuaJIT; the JVM imported-class slice "
                + "rejects them).", "JVM-GAP-DEFAULTS-PLANS");
        skip("backend-runtime/defaults/plan-phase-order-provided-before-defaults.deal",
            "E6000: the Error | null catch-probe local and the "
                + "catch-block assignment are outside the JVM slice "
                + "(class-typed local values are local-module-class "
                + "only and catch assignments reject forward "
                + "references), so the caught-E8002 phase-order probe "
                + "cannot compile.", "JVM-GAP-DEFAULTS-PLANS");

        // ---- JVM-GAP-XMOD-FNVALUE: retired with the shared runtime
        // value surface (ISSUE-0301) ----
        // The four cross-module function-value fixtures pass the real
        // pipeline on the shared $DealRt wrapper carriers and were
        // removed with their promotion; the stale-skip gate forced the
        // removals. host-async-shape-value (a host export used as a
        // first-class function value) stays with the host ABI shapes
        // lane below.
        skip("backend-runtime/host-abi/host-async-shape-value.deal",
            "E6000: module aliases used as values (host async export "
                + "as a function value).", "JVM-GAP-HOST-ABI-SHAPES");

        // ---- JVM-GAP-XMOD-ARRAY: retired with the shared runtime value
        // surface (ISSUE-0301) ----
        // await-returning-array-indexed passes the real pipeline on the
        // shared $DealRt array carriers and was removed with its
        // promotion; the stale-skip gate forced the removal.

        // ---- JVM-GAP-ASYNC-FNEXPR: async function expressions ----
        skip("backend-runtime/async-await/async-fn-expr.deal",
            "E6000: async function expressions.", "JVM-GAP-ASYNC-FNEXPR");
        skip("backend-runtime/async-await/async-await-statement.deal",
            "E6000: block-level async functions and async function "
                + "expressions (plus the forward-reference guard).",
            "JVM-GAP-ASYNC-FNEXPR");
    }

    private static void skip(String path, String reason, String gapId) {
        SKIPS.put(path, new SkipEntry(path, reason, gapId));
    }

    /** The per-lane registry view (test seam: scratch lanes override
     * this to inject a synthetic entry for the stale-registry probes). */
    protected Map<String, SkipEntry> skipRegistry() {
        return SKIPS;
    }

    /** The registry-validation defects recorded at construction (empty
     * when every entry names an on-disk runtime-classified fixture). */
    private final List<String> registryDefects = new ArrayList<>();

    /**
     * The registry-validation defects: a stale entry naming a missing or
     * non-runtime-classified fixture is recorded here with its promotion
     * instruction (the gate cross-checks the same registry against its
     * own discovery — defense in depth).
     */
    public List<String> registryDefects() {
        return List.copyOf(registryDefects);
    }

    /**
     * The gate-facing pre-flip skip-registry view: the gate applies the
     * G8 tolerance to this backend (a tracked failing outcome is
     * non-fatal with its gap id; a passing outcome or a missing fixture
     * is a stale entry with a promotion instruction).
     */
    public DifferentialGate.PreFlipSkipRegistry preFlipSkipRegistry() {
        return new DifferentialGate.PreFlipSkipRegistry() {
            @Override
            public Optional<DifferentialGate.PreFlipSkipEntry> entryFor(
                    String corpusPath) {
                SkipEntry entry = skipRegistry().get(corpusPath);
                if (entry == null) {
                    return Optional.empty();
                }
                return Optional.of(new DifferentialGate.PreFlipSkipEntry(
                    entry.path(), entry.gapId(), entry.reason()));
            }

            @Override
            public List<DifferentialGate.PreFlipSkipEntry> entries() {
                List<DifferentialGate.PreFlipSkipEntry> entries =
                    new ArrayList<>();
                for (SkipEntry entry : skipRegistry().values()) {
                    entries.add(new DifferentialGate.PreFlipSkipEntry(
                        entry.path(), entry.gapId(), entry.reason()));
                }
                return entries;
            }
        };
    }

    /**
     * Validates the skip registry against the on-disk corpus at
     * construction: every entry must name a discovered
     * runtime-classified fixture. A stale entry is recorded in
     * {@link #registryDefects()} with its promotion instruction.
     */
    protected void validateRegistry() {
        // The re-validation seam: scratch lanes call this after injecting
        // a synthetic registry entry (the constructor validates the real
        // registry directly, so no overridable method dispatches before
        // the subclass initializes).
        registryDefects.clear();
        validateAgainstCorpus(skipRegistry());
    }

    private void validateRegistryInternal() {
        registryDefects.clear();
        validateAgainstCorpus(SKIPS);
    }

    private void validateAgainstCorpus(Map<String, SkipEntry> registry) {
        try {
            CorpusDiscovery.DiscoveryResult discovery =
                CorpusDiscovery.discover(conformanceRoot);
            Map<String, CorpusDiscovery.Fixture> byPath =
                discovery.corpusByPath();
            for (SkipEntry entry : registry.values()) {
                CorpusDiscovery.Fixture fixture = byPath.get(entry.path());
                if (fixture == null || !fixture.runtimeClassified()) {
                    registryDefects.add("stale skip-registry entry "
                        + entry.path() + " (gap id " + entry.gapId() + "): "
                        + (fixture == null
                            ? "the fixture is missing from the corpus"
                            : "the fixture carries no runtime classification")
                        + " — promotion instruction: remove the "
                        + "skip-registry entry");
                }
            }
        } catch (IOException e) {
            registryDefects.add("the skip-registry validation failed: "
                + e.getMessage());
        }
    }

    // =========================================================================
    // Test seams (documented scratch-probe hooks)
    // =========================================================================

    /**
     * <b>Test seam:</b> invoked after the workspace is deployed and the
     * javac compile completed, before the artifact-presence assertion
     * and the subprocess spawn. Scratch lane variants delete a generated
     * artifact here to prove the lane reports
     * {@link MismatchClass#ARTIFACT_MISSING} instead of fabricating a
     * result.
     */
    protected void beforeExecution(Path projectRoot, Path outputRoot) {
        // The production lane performs no extra step here.
    }

    /**
     * <b>Test seam:</b> the captured-file deployment lookup. Scratch lane
     * variants return {@code null} here to strip the deployment map, so
     * the captured {@code file} is emitted verbatim and the comparison
     * fails (the unmappable-file probe).
     */
    protected DeploymentEntry deploymentEntryFor(String capturedFile,
            JvmCompilation compilation) {
        return compilation.deploymentMap().get(capturedFile);
    }
}
