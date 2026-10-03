package deal.test;

import deal.ast.ExportDeclaration;
import deal.ast.FunctionDeclaration;
import deal.ast.ProgramNode;
import deal.ast.StatementNode;
import deal.codegen.Backend;
import deal.codegen.jvm.JvmBackend;
import deal.diagnostics.CompilerDiagnostic;
import deal.lexer.LexResult;
import deal.lexer.Lexer;
import deal.module.CompilationOrchestrator;
import deal.parser.ParseResult;
import deal.parser.Parser;
import deal.project.ExternalEntry;
import deal.project.ProjectContext;
import deal.project.ProjectLocator;
import deal.semantic.CompilerInvocation;
import deal.semantic.CompilerProfileProvider;
import deal.semantic.ReleaseConfiguration;
import deal.semantic.SemanticLowerer;
import deal.semantic.ir.FailureContractRegistry;
import deal.semantic.ir.LoweringFailureDetail;
import deal.semantic.ir.SemanticCapability;
import deal.semantic.ir.SemanticProfile;
import deal.module.ProductionProjectEmission;
import deal.test.conformance.CorpusDiscovery;
import deal.test.conformance.CorpusFfi;
import deal.test.conformance.ErrorSnapshot;
import deal.test.conformance.GateMismatch;
import deal.test.conformance.LaneExecution;
import deal.test.conformance.SidecarExpectations;
import deal.test.conformance.SidecarSchemaValidator;
import deal.test.conformance.StructuredExpectationComparator;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * The registered production-corpus audit over the dispatched set
 * ({@code dispatched-corpus-production-acceptance} A1-A5 and the
 * production-corpus audit contract plus the corpus-invariant contract;
 * {@code conformance-corpus-disposition-and-profile-repin} CD2/CD3/CD5;
 * {@code conformance-cutover-guards-pins-and-production-source-removal}
 * HR4(a); {@code dispatched-corpus-production-realization} R1/R8).
 *
 * <p>One registered drive measures the whole dispatched runtime corpus
 * through the release-owned production pipeline:</p>
 *
 * <ol>
 *   <li><b>The corpus invariants.</b> Every {@code runtime-ok}/
 *       {@code runtime-error} fixture under
 *       {@code test/conformance/backend-runtime/**} is dispatched (the
 *       pinned count), every fixture's sidecar validates clean against
 *       the landed schema validator and pins all three backends, and no
 *       fixture or sidecar is reshaped by this drive.</li>
 *   <li><b>The lane-equivalent materialization.</b> Every fixture is
 *       written as the entry module of a temp project with the
 *       entry-directory-relative layout the gate's lane rule uses, its
 *       transitively imported companions, the explicit-{@code .deal}
 *       alias copies, every {@code host/<name>} declaration under
 *       {@code bindings/}, the corpus C FFI declarations with the
 *       corpus {@code nativeLibrary} wiring, and one generated
 *       {@code deal.json} whose raw host keys resolve the declaration
 *       files while the compile context carries the dotted typing name
 *       first (the lanes' externals-identity convention).</li>
 *   <li><b>The compile criterion.</b> Every dispatched fixture compiles
 *       on {@link Backend#LUAJIT} and {@link Backend#JVM} under the
 *       release-owned production invocation with zero E6005
 *       {@code CONSTRUCT_UNLOWERED}/{@code RETAINED_ABI_DEFERRED}/
 *       {@code SHARED_EMITTER_COVERAGE}; the extern-C fixtures'
 *       {@code jvm} legs reproduce exactly the pinned E6006 rejection
 *       and stage no artifact. The declaration-importing group's
 *       per-fixture, per-target compile outcomes are part of the
 *       reported measurement (never an E2003 from a non-materializing
 *       probe).</li>
 *   <li><b>The capture criterion.</b> Each producible fixture's staged
 *       artifact is driven with the deferred-entry probe: the deferred
 *       module-init entry ({@code __dealMain()}/{@code dealMain()})
 *       then every remaining zero-arity export once in declaration
 *       order through the export surface, inside protected calls. The
 *       capture is projected to {@code code}, {@code message}, the span
 *       group, {@code expected}, and {@code actual}, rebased onto raw
 *       corpus coordinates by the module's stripped-header delta, and
 *       compared with the sidecar through the landed comparison
 *       surfaces ({@link ErrorSnapshot},
 *       {@link StructuredExpectationComparator}) — mandatory fields
 *       always, the span group and optionals only when pinned, nothing
 *       fabricated.</li>
 *   <li><b>The negative control.</b> A corrupted staged artifact fails
 *       the audit by name (the capture criterion is not vacuous), and a
 *       hand-built guard diagnostic is reported by the guard detector
 *       (the compile criterion is not vacuous). The acceptance policy's
 *       truth table is asserted directly, so the fatality rule itself is
 *       not vacuous.</li>
 *   <li><b>The acceptance verdicts and the pre-precondition rule.</b> The
 *       run computes the three acceptance verdicts — zero
 *       {@code CONSTRUCT_UNLOWERED}, zero {@code RETAINED_ABI_DEFERRED}/
 *       {@code SHARED_EMITTER_COVERAGE}, and byte-exact captures on the
 *       producible set — and reports each. While the dispatched corpus
 *       still carries a checker-valid {@code CONSTRUCT_UNLOWERED} deficit
 *       (the sibling-owned realization work has not landed), the audit
 *       runs in its <em>pre-precondition reporting mode</em>: every
 *       failing leg is named by fixture and target and the run exits
 *       zero, exactly as
 *       {@code conformance-cutover-guards-pins-and-production-source-removal}
 *       HR4(a) and {@code dispatched-corpus-production-acceptance} A5 and
 *       CD5 prescribe ("before it, the corpus run reports the residual
 *       failures per fixture and never skips"). As soon as the compile
 *       criterion is met (zero {@code CONSTRUCT_UNLOWERED} legs), the
 *       audit engages its <em>strict acceptance mode</em>: every
 *       non-pin-exact capture leg fails the audit by fixture and field —
 *       the combined-with-dependencies step. {@code RETAINED_ABI_DEFERRED}
 *       and {@code SHARED_EMITTER_COVERAGE} legs, non-guard compile
 *       failures, pinned-rejection mismatches, and the audit-input defects
 *       (an unresolvable declaration wiring, a missing sidecar, a missing
 *       host implementation) are fatal in both modes; nothing is skipped,
 *       excluded, or fabricated in either mode.</li>
 *   <li><b>The materialization fidelity.</b> The declaration wiring is
 *       load-bearing: a probe that omits the host/FFI declaration
 *       externals reproduces the frontend E2003 gap, while the audit's
 *       materialized drive compiles the same fixture.</li>
 * </ol>
 *
 * <p>The drive is read-only over the corpus: every temp project is
 * deleted, no fixture, sidecar, lane file, or repository artifact is
 * written, and the audit selects no invocation other than the
 * release-owned production one.</p>
 */
public class ProductionCorpusAuditTest {

    private static final Path CONFORMANCE = Path.of("test", "conformance");
    private static final Path HOST_FIXTURES = Path.of("test", "conformance",
        "host-fixtures");

    /** The dispatched runtime-fixture count pin (the gate's 390). */
    private static final int DISPATCHED_COUNT_PIN = 390;

    /** The three fail-closed producer guards the compile criterion forbids. */
    private static final List<String> GUARD_RULES = List.of(
        SemanticLowerer.CONSTRUCT_UNLOWERED,
        SemanticLowerer.RETAINED_ABI_DEFERRED,
        ProductionProjectEmission.SHARED_EMITTER_COVERAGE);

    /** The probe transport file name inside an execution workspace. */
    private static final String TRANSPORT_FILE = "__probe_transport.txt";

    /** The bounded per-subprocess deadline (the harness deadline convention). */
    private static final long SUBPROCESS_TIMEOUT_SECONDS = 120;

    private static final AtomicInteger passed = new AtomicInteger();
    private static final AtomicInteger fatalCount = new AtomicInteger();
    private static final AtomicInteger compileLegs = new AtomicInteger();
    private static final AtomicInteger rejectLegs = new AtomicInteger();
    private static final AtomicInteger captureLegs = new AtomicInteger();
    private static final List<String> fatalLines =
        Collections.synchronizedList(new ArrayList<>());
    private static final List<Leg> legs =
        Collections.synchronizedList(new ArrayList<>());

    /**
     * One measured cell's class. The class decides whether the cell's verdict
     * is fatal or reported: the pre-precondition rule (HR4(a), A5, CD5) keeps
     * the sibling-owned construct and capture deficit visible but non-fatal
     * until the compile criterion is met, after which every non-pass leg is
     * fatal.
     */
    enum LegClass {
        /** The cell reproduced its pin (or its sanctioned compile-reject pin). */
        PASS,
        /** A checker-valid E6005 {@code CONSTRUCT_UNLOWERED} leg. */
        CONSTRUCT_DEFICIT,
        /** A pinned-field mismatch, or a production drive/load/emission failure. */
        CAPTURE_DEFICIT,
        /** A {@code RETAINED_ABI_DEFERRED}/{@code SHARED_EMITTER_COVERAGE} leg. */
        ARCHITECTURAL_GUARD,
        /** A non-guard compile failure or a pinned-rejection mismatch. */
        DEFECT
    }

    /** One measured cell: its target, its class, and its reported line. */
    record Leg(Target target, LegClass clazz, String line) {
    }

    /** True iff a leg's class is fatal under the given acceptance mode. */
    static boolean isFatal(LegClass clazz, boolean acceptanceMode) {
        return switch (clazz) {
            case PASS -> false;
            case ARCHITECTURAL_GUARD, DEFECT -> true;
            case CONSTRUCT_DEFICIT, CAPTURE_DEFICIT -> acceptanceMode;
        };
    }

    private static void check(boolean condition, String message) {
        if (condition) {
            passed.incrementAndGet();
        } else {
            fatal(message);
        }
    }

    private static void fatal(String message) {
        fatalCount.incrementAndGet();
        fatalLines.add(message);
        System.err.println("FAIL: " + message);
    }

    /**
     * A corpus-input defect (an unresolvable declaration wiring, a missing
     * host implementation): an audit-input defect, never a construct gap, and
     * fatal in every mode (the audit contract's fail-closed rule).
     */
    static final class AuditInputException extends RuntimeException {
        AuditInputException(String message) {
            super(message);
        }
    }

    private static void checkEq(Object expected, Object actual, String message) {
        check(Objects.equals(expected, actual), message + " (expected "
            + expected + ", got " + actual + ")");
    }

    // =========================================================================
    // The targets and the materialized project
    // =========================================================================

    /** The two production targets of the audit. */
    enum Target {
        LUAJIT("luajit"), JVM("jvm");

        private final String laneName;

        Target(String laneName) {
            this.laneName = laneName;
        }

        String laneName() {
            return laneName;
        }
    }

    /** One materialized corpus module: its corpus path, source, header delta. */
    record Materialized(String corpusPath, String source, int headerLinesStripped) {
    }

    /** One lane-equivalent temp project. */
    record Project(Path root, Path srcRoot, Path entryFile, String modulePath,
                   Map<String, Materialized> modules,
                   Map<String, String> rawToDeclaration,
                   List<String> hostNames, List<String> ffiImports,
                   Path outputRoot,
                   Map<String, String> modulePathToCorpus,
                   Map<String, String> moduleDirToCorpus) {

        Materialized moduleOf(String capturedFile) {
            if (capturedFile == null) {
                return null;
            }
            Materialized direct = modules.get(capturedFile);
            if (direct != null) {
                return direct;
            }
            return modules.get(Path.of(capturedFile).toAbsolutePath()
                .normalize().toString());
        }
    }

    /** The release-owned production invocation. */
    static CompilerInvocation productionInvocation() {
        return CompilerProfileProvider.resolve(
            ReleaseConfiguration.CURRENT_RELEASE_STATE,
            ReleaseConfiguration.releaseCapabilityRegistry());
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

    /** Resolve a relative import path to a .deal/.d.deal file on disk. */
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

    private static void collectRelativeClosure(String corpusPath,
            List<String> ordered, Set<String> seen) throws Exception {
        if (!seen.add(corpusPath)) {
            return;
        }
        String stripped = ConformanceHarnessMetadata
            .stripClassificationHeaders(Files.readString(
                CONFORMANCE.resolve(corpusPath), StandardCharsets.UTF_8));
        for (String importPath : CorpusDiscovery.relativeImportPaths(stripped)) {
            String resolved = CorpusDiscovery.resolveRelativeImport(corpusPath,
                importPath, CONFORMANCE);
            if (resolved != null) {
                collectRelativeClosure(resolved, ordered, seen);
            }
        }
        ordered.add(corpusPath);
    }

    private static List<String> relativeClosureOf(String rootCorpusPath)
            throws Exception {
        List<String> ordered = new ArrayList<>();
        collectRelativeClosure(rootCorpusPath, ordered, new LinkedHashSet<>());
        return ordered;
    }

    /**
     * Materializes one fixture exactly as the lanes do: the fixture is the
     * entry module, its transitively imported companions sit at their
     * entry-directory-relative layout (with the explicit-{@code .deal}
     * alias copies), the host declarations land under {@code bindings/}, the
     * corpus FFI declarations carry their corpus {@code nativeLibrary}
     * wiring, and one generated {@code deal.json} wires the module root, the
     * output, the target backend, and the externals.
     *
     * @param fixtureRel       the corpus-relative fixture path without the
     *                         {@code .deal} extension
     * @param target           the emission target
     * @param wireDeclarations {@code false} omits the externals wiring (the
     *                         load-bearing fidelity probe)
     */
    private static Project materialize(String fixtureRel, Target target,
            boolean wireDeclarations) throws Exception {
        Path root = Files.createTempDirectory("production-corpus-audit-");
        try {
            return materializeInto(root, fixtureRel, target, wireDeclarations);
        } catch (Exception | Error e) {
            // The post-state rule holds on the failure paths too: a
            // materialization that cannot resolve a declaration (the
            // fail-closed audit-input defect) removes its temp project.
            deleteRecursively(root);
            throw e;
        }
    }

    /** The materialization body; {@code root} is owned by the caller. */
    private static Project materializeInto(Path root, String fixtureRel,
            Target target, boolean wireDeclarations) throws Exception {
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
        Map<String, String> modulePathToCorpus = new LinkedHashMap<>();
        Map<String, String> moduleDirToCorpus = new LinkedHashMap<>();

        // 1. The compilation set with the lane's entry-relative layout.
        for (String corpusPath : relativeClosureOf(CorpusDiscovery.slash(
                CONFORMANCE.toAbsolutePath().normalize().relativize(entryCorpus)))) {
            Path corpusFile = CONFORMANCE.resolve(corpusPath).toAbsolutePath()
                .normalize();
            String raw = Files.readString(corpusFile, StandardCharsets.UTF_8);
            String stripped = ConformanceHarnessMetadata
                .stripClassificationHeaders(raw);
            int headers = raw.split("\n", -1).length
                - stripped.split("\n", -1).length;
            Path targetPath = moduleTarget(corpusFile, entryDir, srcRoot);
            Files.createDirectories(targetPath.getParent());
            Files.writeString(targetPath, stripped, StandardCharsets.UTF_8);
            modules.put(targetPath.toAbsolutePath().normalize().toString(),
                new Materialized(corpusPath, stripped, headers));
            String modulePath = CorpusDiscovery.slash(srcRoot.relativize(targetPath));
            if (modulePath.endsWith(".deal")) {
                modulePath = modulePath.substring(0,
                    modulePath.length() - ".deal".length());
            }
            modulePathToCorpus.put(modulePath, corpusPath);
            modulePathToCorpus.put(modulePath.replace('/', '.'), corpusPath);
            int slash = modulePath.lastIndexOf('/');
            moduleDirToCorpus.putIfAbsent(slash < 0 ? ""
                : modulePath.substring(0, slash), corpusPath);
        }
        String entryStem = moduleNameOf(entryCorpus.getFileName().toString());

        // 1b. The explicit-.deal alias copies (the absorbed
        // copyCompanionAliasIfExplicit rule): an import spelling carrying an
        // explicit .deal extension resolves to its alias-named copy in the
        // flat project root.
        for (Materialized module : List.copyOf(modules.values())) {
            Path moduleCorpusDir = Path.of(module.corpusPath()).getParent();
            Path baseDir = moduleCorpusDir == null ? CONFORMANCE
                : CONFORMANCE.resolve(moduleCorpusDir);
            for (String importPath : CorpusDiscovery.relativeImportPaths(
                    module.source())) {
                Path companion = resolveCompanionPath(importPath, baseDir);
                if (companion == null || !importPath.endsWith(".deal")) {
                    continue;
                }
                String companionCorpus = CorpusDiscovery.slash(
                    CONFORMANCE.toAbsolutePath().normalize()
                        .relativize(companion.toAbsolutePath().normalize()));
                Materialized companionModule = null;
                for (Materialized candidate : modules.values()) {
                    if (candidate.corpusPath().equals(companionCorpus)) {
                        companionModule = candidate;
                        break;
                    }
                }
                if (companionModule == null) {
                    continue;
                }
                String aliasBase = importPath.startsWith("./")
                    ? importPath.substring(2)
                    : importPath.startsWith("../")
                        ? importPath.substring(3) : importPath;
                Path aliasTarget = srcRoot.resolve(aliasBase + ".deal");
                if (Files.exists(aliasTarget)) {
                    continue;
                }
                Files.createDirectories(aliasTarget.getParent());
                Files.writeString(aliasTarget, companionModule.source(),
                    StandardCharsets.UTF_8);
            }
        }

        // 2. The declaration wiring: host declarations under bindings/, the
        // corpus FFI declarations with their nativeLibrary.
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
        if (wireDeclarations) {
            Files.createDirectories(bindings);
            for (String hostName : hostNames) {
                Path declaration = HOST_FIXTURES.resolve(hostName + ".d.deal");
                if (!Files.isRegularFile(declaration)) {
                    throw new AuditInputException(fixtureRel
                        + ": the host declaration " + declaration + " is missing");
                }
                Files.writeString(bindings.resolve(hostName + ".d.deal"),
                    ConformanceHarnessMetadata.stripClassificationHeaders(
                        Files.readString(declaration, StandardCharsets.UTF_8)),
                    StandardCharsets.UTF_8);
                rawToDeclaration.put("host/" + hostName,
                    "bindings/" + hostName + ".d.deal");
            }
            for (String raw : ffiImports) {
                CorpusFfi.Wiring wiring = CorpusFfi.wiringFor(CONFORMANCE, raw);
                if (wiring == null) {
                    throw new AuditInputException(fixtureRel
                        + ": no corpus FFI wiring for " + raw);
                }
                Path declaration = CONFORMANCE.resolve(CorpusFfi.FFI_DIR)
                    .resolve(wiring.declarationCorpusPath());
                if (!Files.isRegularFile(declaration)) {
                    throw new AuditInputException(fixtureRel
                        + ": the corpus FFI declaration " + declaration
                        + " is missing");
                }
                String fileName = "ffi_" + raw.replace('/', '_') + ".d.deal";
                Files.writeString(bindings.resolve(fileName),
                    ConformanceHarnessMetadata.stripClassificationHeaders(
                        Files.readString(declaration, StandardCharsets.UTF_8)),
                    StandardCharsets.UTF_8);
                rawToDeclaration.put(raw, "bindings/" + fileName);
            }
        }

        // 3. One generated deal.json (the lane's rule).
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
            root.resolve("out"), modulePathToCorpus, moduleDirToCorpus);
    }

    /**
     * The corpus conformance externals-identity convention (the lanes'
     * rule): a host module's class identities project through its dotted
     * typing name while import resolution keys on the raw specifier.
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

    // =========================================================================
    // Compile
    // =========================================================================

    /** One compile outcome of one fixture and target. */
    record CompileOutcome(boolean compiled, boolean located,
                          List<CompilerDiagnostic> errors,
                          List<CompilerDiagnostic> allDiagnostics,
                          String detail) {

        boolean guardFired() {
            return errors.stream().anyMatch(ProductionCorpusAuditTest::isGuardDiagnostic);
        }

        String firstErrorDetail() {
            if (!errors.isEmpty()) {
                CompilerDiagnostic first = errors.get(0);
                return first.code() + " at " + first.range().file() + ":"
                    + first.line() + ":" + first.column() + " "
                    + first.message();
            }
            return detail;
        }
    }

    /** True iff the diagnostic is one of the three fail-closed producer guards. */
    static boolean isGuardDiagnostic(CompilerDiagnostic diagnostic) {
        String message = diagnostic.message() == null ? ""
            : diagnostic.message();
        return GUARD_RULES.stream().anyMatch(message::contains);
    }

    private static CompileOutcome compile(Project project) throws Exception {
        ProjectLocator.LocateResult located = ProjectLocator.locate(
            project.entryFile().toString(), null);
        if (located.context() == null) {
            return new CompileOutcome(false, false, List.of(), List.of(),
                "the generated deal.json did not locate strictly: "
                    + located.e2010());
        }
        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            withDottedHostIdentities(located.context(), project),
            project.entryFile(), false, false, false, false, null,
            productionInvocation());
        boolean compiled = orchestrator.compile();
        List<CompilerDiagnostic> errors = new ArrayList<>();
        for (CompilerDiagnostic diagnostic : orchestrator.diagnostics()) {
            if ("error".equals(diagnostic.severity())) {
                errors.add(diagnostic);
            }
        }
        return new CompileOutcome(compiled, true, errors,
            orchestrator.diagnostics(), null);
    }

    /** The emitted artifact path of one compiled project and target. */
    static Path artifactOf(Project project, Target target) {
        String name = target == Target.JVM
            ? JvmBackend.classNameFor(project.modulePath()) + ".java"
            : project.modulePath().replace('.', '/') + ".lua";
        return project.outputRoot().resolve(name);
    }

    // =========================================================================
    // The ordered export surface
    // =========================================================================

    /** One ordered zero-arity export of the entry module. */
    record Export(String name, boolean async) {
    }

    /** The ordered remaining zero-arity exports (main excluded). */
    static List<Export> exportsOf(String source, String file) {
        LexResult lex = new Lexer(source, file).tokenize();
        if (lex.hasErrors()) {
            throw new IllegalStateException("entry lex errors: "
                + lex.diagnostics());
        }
        ParseResult parse = new Parser(lex.tokens(), file,
            SemanticProfile.DEAL_V1_2_INT32, lex.directiveEvents()).parse();
        if (parse.hasErrors()) {
            throw new IllegalStateException("entry parse errors: "
                + parse.diagnostics());
        }
        ProgramNode program = parse.program();
        List<Export> exports = new ArrayList<>();
        for (StatementNode statement : program.statements()) {
            if (statement instanceof ExportDeclaration exp
                    && exp.declaration() instanceof FunctionDeclaration fn
                    && fn.params().isEmpty() && !fn.name().contains("$")
                    && !fn.name().equals("main")) {
                exports.add(new Export(fn.name(), fn.isAsync()));
            }
        }
        return exports;
    }

    // =========================================================================
    // The deferred-entry drives
    // =========================================================================

    /** The projected capture of one artifact drive; {@code defect} is probe-owned. */
    record Capture(String code, String message, String sourceFile, Integer line,
                   Integer column, String expected, String actual,
                   String defect) {

        boolean error() {
            return code != null && !"PROBE_DEFECT".equals(code);
        }
    }

    /** One executed artifact's raw outcome. */
    record Execution(int exitCode, byte[] stdout, byte[] stderr, Capture capture) {
    }

    private static Execution execute(Project project, List<Export> exports,
            Target target) throws Exception {
        return target == Target.LUAJIT
            ? executeLua(project, exports)
            : executeJvm(project, exports);
    }

    private static Execution executeLua(Project project, List<Export> exports)
            throws Exception {
        Path out = project.outputRoot();
        Path artifact = artifactOf(project, Target.LUAJIT);
        if (!Files.isRegularFile(artifact)) {
            throw new IllegalStateException(project.modulePath()
                + " [luajit]: the staged production chunk " + artifact
                + " is missing");
        }
        for (String hostName : project.hostNames()) {
            Path hostLua = HOST_FIXTURES.resolve(hostName + ".lua");
            if (!Files.isRegularFile(hostLua)) {
                throw new AuditInputException(project.modulePath()
                    + " [luajit]: the host implementation " + hostLua
                    + " is missing");
            }
            Path targetFile = out.resolve("host").resolve(hostName + ".lua");
            Files.createDirectories(targetFile.getParent());
            Files.copy(hostLua, targetFile);
        }
        Path transport = out.resolve(TRANSPORT_FILE);
        Files.deleteIfExists(transport);
        Path probe = out.resolve("__audit_probe.lua");
        Files.writeString(probe, luaProbe(project, exports, artifact, transport),
            StandardCharsets.UTF_8);
        ProcessBuilder builder = new ProcessBuilder("luajit",
            probe.toAbsolutePath().toString());
        builder.directory(out.toFile());
        builder.environment().put("DEAL_DEFER_MAIN", "1");
        ProcessOutcome outcome = runProcess(builder);
        return new Execution(outcome.exitCode(), outcome.stdout(),
            outcome.stderr(), readTransport(transport));
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
        probe.append("  local f = io.open(\"").append(transport)
            .append("\", \"w\")\n");
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
        int index = 0;
        for (Export export : exports) {
            index++;
            if (export.async()) {
                probe.append("local __okA").append(index)
                    .append(", __errA").append(index)
                    .append(" = pcall(__asyncEntries[\"")
                    .append(project.modulePath()).append("#")
                    .append(export.name()).append("\"], \"-\", true)\n");
                probe.append("if not __okA").append(index)
                    .append(" then __fail(__errA").append(index)
                    .append(") end\n");
            } else {
                probe.append("local __v").append(index)
                    .append(" = __exportSurfaces[\"")
                    .append(project.modulePath()).append("\"][\"")
                    .append(export.name()).append("\"]\n");
                probe.append("if type(__v").append(index)
                    .append(") ~= \"table\" or type(__v")
                    .append(index)
                    .append(".f) ~= \"function\" then __probeDefect(\"the entry"
                        + " surface publishes no function for ")
                    .append(export.name()).append("\") end\n");
                probe.append("local __okS").append(index)
                    .append(", __errS").append(index)
                    .append(" = pcall(__v").append(index)
                    .append(".f)\n");
                probe.append("if not __okS").append(index)
                    .append(" then __fail(__errS").append(index)
                    .append(") end\n");
            }
        }
        probe.append("os.exit(0)\n");
        return probe.toString();
    }

    private static Execution executeJvm(Project project, List<Export> exports)
            throws Exception {
        Path out = project.outputRoot();
        Path artifact = artifactOf(project, Target.JVM);
        if (!Files.isRegularFile(artifact)) {
            throw new IllegalStateException(project.modulePath()
                + " [jvm]: the staged production class " + artifact
                + " is missing");
        }
        Path transport = out.resolve(TRANSPORT_FILE);
        Files.deleteIfExists(transport);
        String className = JvmBackend.classNameFor(project.modulePath());
        Path classes = out.resolve("classes");
        Files.createDirectories(classes);
        String classpath = absoluteClasspath();
        List<String> sources = new ArrayList<>();
        sources.add(artifact.toString());
        for (String hostName : project.hostNames()) {
            Path hostFile = HOST_FIXTURES.resolve(hostName + ".java");
            if (!Files.isRegularFile(hostFile)) {
                throw new AuditInputException(project.modulePath()
                    + " [jvm]: the host implementation " + hostFile
                    + " is missing");
            }
            String hostClass = JvmBackend.classNameFor("host/" + hostName);
            Path targetFile = out.resolve(hostClass + ".java");
            Files.copy(hostFile, targetFile);
            sources.add(targetFile.toString());
        }
        Files.writeString(out.resolve("AuditProbe.java"),
            jvmProbe(project, exports, transport), StandardCharsets.UTF_8);
        sources.add(out.resolve("AuditProbe.java").toString());
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
        ProcessOutcome compile = runProcess(javac);
        if (compile.exitCode() != 0) {
            throw new IllegalStateException(project.modulePath()
                + " [jvm]: the emitted production artifact does not compile"
                + " with javac --release 25 -proc:none: "
                + new String(compile.stdout(), StandardCharsets.UTF_8));
        }
        ProcessBuilder run = new ProcessBuilder("java", "-cp",
            classpath + File.pathSeparator + classes, "AuditProbe");
        run.directory(out.toFile());
        ProcessOutcome outcome = runProcess(run);
        return new Execution(outcome.exitCode(), outcome.stdout(),
            outcome.stderr(), readTransport(transport));
    }

    /** The JVM deferred-entry probe source. */
    private static String jvmProbe(Project project, List<Export> exports,
            Path transport) {
        String className = JvmBackend.classNameFor(project.modulePath());
        StringBuilder probe = new StringBuilder();
        probe.append("public final class AuditProbe {\n");
        probe.append("  static final String TRANSPORT = \"")
            .append(transport.toString().replace("\\", "\\\\")).append("\";\n");
        probe.append("  public static void main(String[] args) {\n");
        probe.append("    try {\n");
        probe.append("      ").append(className).append(".dealMain();\n");
        int index = 0;
        for (Export export : exports) {
            index++;
            probe.append("      {\n");
            probe.append("        Object v").append(index)
                .append(" = deal.codegen.jvm.JvmRuntime.exportSurface(\"")
                .append(project.modulePath()).append("\").read(\"")
                .append(export.name()).append("\");\n");
            probe.append("        if (!(v").append(index)
                .append(" instanceof deal.codegen.jvm.JvmRuntime.FunctionValue)) {\n");
            probe.append("          transport(new IllegalStateException("
                + "\"PROBE_DEFECT: the entry surface publishes no function "
                + "for ").append(export.name()).append("\"));\n");
            probe.append("          return;\n");
            probe.append("        }\n");
            probe.append("        ((deal.codegen.jvm.JvmRuntime.FunctionValue) v")
                .append(index).append(").fn.invoke(new Object[]{});\n");
            probe.append("      }\n");
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
        String code = fields.get("code");
        return new Capture(code, fields.get("message"), file, line, column,
            emptyToNull(fields.get("expected")), emptyToNull(fields.get("actual")),
            "PROBE_DEFECT".equals(code) ? fields.get("message") : null);
    }

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
    // Bounded subprocesses
    // =========================================================================

    record ProcessOutcome(int exitCode, byte[] stdout, byte[] stderr) {
    }

    private static ProcessOutcome runProcess(ProcessBuilder builder)
            throws Exception {
        Process process = builder.start();
        byte[][] stdout = new byte[1][];
        byte[][] stderr = new byte[1][];
        Thread outThread = new Thread(() ->
            stdout[0] = readAll(process.getInputStream()), "audit-stdout");
        Thread errThread = new Thread(() ->
            stderr[0] = readAll(process.getErrorStream()), "audit-stderr");
        outThread.setDaemon(true);
        errThread.setDaemon(true);
        outThread.start();
        errThread.start();
        if (!process.waitFor(SUBPROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            process.waitFor();
            throw new IllegalStateException("the subprocess exceeded the "
                + SUBPROCESS_TIMEOUT_SECONDS + "s bounded deadline");
        }
        outThread.join(1000);
        errThread.join(1000);
        return new ProcessOutcome(process.exitValue(),
            stdout[0] == null ? new byte[0] : stdout[0],
            stderr[0] == null ? new byte[0] : stderr[0]);
    }

    private static byte[] readAll(java.io.InputStream stream) {
        try {
            return stream.readAllBytes();
        } catch (Exception e) {
            return new byte[0];
        }
    }

    // =========================================================================
    // The sidecar-authoritative comparison
    // =========================================================================

    /** The normalized projection of one captured drive. */
    static Capture normalize(Project project, Capture capture) {
        if (capture == null) {
            return null;
        }
        Materialized module = project.moduleOf(capture.sourceFile());
        String sourceFile = module != null ? module.corpusPath()
            : capture.sourceFile();
        Integer line = capture.line();
        if (line != null && module != null) {
            line = line + module.headerLinesStripped();
        }
        return new Capture(capture.code(),
            canonicalIdentities(project, capture.message(), sourceFile),
            sourceFile, line, capture.column(),
            canonicalIdentities(project, capture.expected(), sourceFile),
            canonicalIdentities(project, capture.actual(), sourceFile),
            canonicalIdentities(project, capture.defect(), sourceFile));
    }

    /**
     * Rewrites the temp project's module-qualified class-descriptor texts
     * to the canonical corpus vocabulary (the lanes' rule): an
     * {@code @src/<module path>/<Class>} local identity becomes
     * {@code @conformance/<corpus path>/<Class>}; an unmappable token is
     * emitted verbatim so the comparison surfaces the defect.
     */
    static String canonicalIdentities(Project project, String text,
            String sourceFile) {
        if (text == null || text.indexOf('@') < 0) {
            return text;
        }
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c != '@') {
                out.append(c);
                i++;
                continue;
            }
            int end = i + 1;
            while (end < text.length()
                    && (Character.isLetterOrDigit(text.charAt(end))
                        || text.charAt(end) == '/' || text.charAt(end) == '_'
                        || text.charAt(end) == '$' || text.charAt(end) == '-'
                        || text.charAt(end) == '.')) {
                end++;
            }
            out.append(canonicalIdentity(project, text.substring(i, end),
                sourceFile));
            i = end;
        }
        return out.toString();
    }

    private static String canonicalIdentity(Project project, String token,
            String sourceFile) {
        String prefix = "@src/";
        if (!token.startsWith(prefix)) {
            return token;
        }
        String rest = token.substring(prefix.length());
        int slash = rest.lastIndexOf('/');
        String corpus;
        String className;
        if (slash < 0) {
            corpus = sourceFile;
            className = rest;
        } else {
            String modulePath = rest.substring(0, slash);
            corpus = project.modulePathToCorpus().get(modulePath);
            if (corpus == null) {
                corpus = project.moduleDirToCorpus().get(modulePath);
            }
            className = rest.substring(slash + 1);
        }
        if (corpus == null || className.isEmpty()) {
            return token;
        }
        return "@conformance/" + corpus + "/" + className;
    }

    /**
     * The canonical error framing of one capture under the pin's suppression
     * rule (the bytes the landed comparator consumes).
     */
    private static byte[] framedSnapshot(Capture capture,
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
        String framing = ErrorSnapshot.CODE_LINE_PREFIX + capture.code() + "\n"
            + ErrorSnapshot.SNAPSHOT_LINE_PREFIX
            + ErrorSnapshot.canonicalJson(snapshot) + "\n";
        return framing.getBytes(StandardCharsets.UTF_8);
    }

    /** The sidecar's typed expectation of one fixture and target. */
    static SidecarExpectations.RuntimeExpectation expectationOf(
            CorpusDiscovery.Fixture fixture, Target target) throws Exception {
        SidecarExpectations.StructuredExpectationSidecar sidecar =
            SidecarExpectations.StructuredExpectationSidecar.parse(
                Files.readString(fixture.sidecarPath(CONFORMANCE),
                    StandardCharsets.UTF_8));
        return sidecar.expectationFor(target.laneName());
    }

    /**
     * Compares one driven cell with its sidecar through the landed comparator
     * and returns the failure line, or empty on an exact pass.
     */
    static Optional<String> compareCell(String fixtureLabel, Target target,
            Capture capture, Execution execution,
            SidecarExpectations.RuntimeExpectation expectation) {
        if (capture != null && capture.defect() != null) {
            return Optional.of(fixtureLabel + " [" + target.laneName()
                + "]: the deferred-entry probe reported a defect: "
                + capture.defect());
        }
        LaneExecution laneExecution;
        if (expectation instanceof SidecarExpectations.RuntimeExpectation.Rejected
                rejected) {
            // The sanctioned extern-C rejection leg: the compile reject is
            // compared separately; a capture here is a defect.
            return Optional.of(fixtureLabel + " [" + target.laneName()
                + "]: the sidecar pins compile rejection ("
                + rejected.code() + ") but the reachable arm executed");
        }
        SidecarExpectations.RuntimeExpectation.Executed executed =
            (SidecarExpectations.RuntimeExpectation.Executed) expectation;
        if (executed.isRuntimeError()) {
            if (capture == null || !capture.error()) {
                return Optional.of(fixtureLabel + " [" + target.laneName()
                    + "]: the sidecar pins runtime-error " + executed.error().code()
                    + " but the drive completed normally"
                    + (capture == null ? " (no capture)" : ""));
            }
            laneExecution = new LaneExecution.Executed(
                framedSnapshot(capture, executed.error()),
                new byte[0], 1);
        } else {
            if (capture != null && capture.error()) {
                return Optional.of(fixtureLabel + " [" + target.laneName()
                    + "]: the sidecar pins runtime-ok but the drive raised "
                    + capture.code() + ": " + capture.message());
            }
            laneExecution = new LaneExecution.Executed(execution.stdout(),
                execution.stderr(), execution.exitCode());
        }
        Optional<GateMismatch> mismatch = StructuredExpectationComparator.compare(
            target.laneName(), expectation, laneExecution);
        return mismatch.map(m -> fixtureLabel + " [" + target.laneName() + "] "
            + m.clazz() + ": " + m.detail());
    }

    /** The field-level detail of one capture against its pin (reporting aid). */
    static String captureDetail(Capture capture,
            SidecarExpectations.ErrorExpectation pinned) {
        if (capture == null) {
            return "captured=null pinned=(" + pinned.code() + ", "
                + pinned.message() + ")";
        }
        return "captured=(" + capture.code() + ", " + capture.message() + ", "
            + capture.sourceFile() + ":" + capture.line() + ":" + capture.column()
            + ", " + capture.expected() + ", " + capture.actual()
            + ") pinned=(" + pinned.code() + ", " + pinned.message() + ", "
            + pinned.sourceFile() + ":" + pinned.line() + ":" + pinned.column()
            + ", " + pinned.expected().orElse(null) + ", "
            + pinned.actual().orElse(null) + ")";
    }

    // =========================================================================
    // The per-cell verdicts
    // =========================================================================

    /** One fixture/target cell's verdict, its class, and its reported line. */
    record CellVerdict(boolean pass, LegClass clazz, String line) {
    }

    /** True iff the outcome carries a RETAINED_ABI_DEFERRED/SHARED_EMITTER_COVERAGE error. */
    static boolean architecturalGuardFired(CompileOutcome outcome) {
        return outcome.errors().stream().anyMatch(diagnostic -> {
            String message = diagnostic.message() == null ? ""
                : diagnostic.message();
            return message.contains(SemanticLowerer.RETAINED_ABI_DEFERRED)
                || message.contains(
                    ProductionProjectEmission.SHARED_EMITTER_COVERAGE);
        });
    }

    /**
     * The compile criterion of one cell: a compile with zero guard rules; on
     * the JVM the extern-C group's pinned rejection is the sanctioned
     * outcome. The declaration-import group is measured by this same
     * materializing drive. A checker-valid guard leg is classed as the
     * sibling-owned construct deficit (reported before the precondition,
     * fatal once the compile criterion is met); the two architectural guards,
     * a non-guard compile failure, and a pinned-rejection mismatch are always
     * fatal.
     */
    static CellVerdict compileVerdict(String fixtureLabel, Target target,
            CompileOutcome outcome,
            SidecarExpectations.RuntimeExpectation expectation) {
        if (outcome.guardFired()) {
            LegClass clazz = architecturalGuardFired(outcome)
                ? LegClass.ARCHITECTURAL_GUARD : LegClass.CONSTRUCT_DEFICIT;
            return new CellVerdict(false, clazz, fixtureLabel + " ["
                + target.laneName() + "] compile E6005 guard: "
                + outcome.firstErrorDetail());
        }
        if (expectation instanceof SidecarExpectations.RuntimeExpectation.Rejected
                rejected) {
            if (outcome.compiled()) {
                return new CellVerdict(false, LegClass.DEFECT, fixtureLabel
                    + " [" + target.laneName() + "] the sidecar pins compile "
                    + "rejection " + rejected.code() + " but the production "
                    + "compile emitted an artifact");
            }
            Optional<GateMismatch> mismatch = StructuredExpectationComparator
                .compare(target.laneName(), expectation,
                    new LaneExecution.Rejected(
                        outcome.errors().isEmpty() ? "<none>"
                            : outcome.errors().get(0).code(),
                        rejected.line().isPresent()
                            ? OptionalInt.of(outcome.errors().isEmpty() ? 0
                                : outcome.errors().get(0).line())
                            : OptionalInt.empty(),
                        rejected.column().isPresent()
                            ? OptionalInt.of(outcome.errors().isEmpty() ? 0
                                : outcome.errors().get(0).column())
                            : OptionalInt.empty()));
            if (mismatch.isPresent()) {
                return new CellVerdict(false, LegClass.DEFECT, fixtureLabel
                    + " [" + target.laneName() + "] " + mismatch.get().clazz()
                    + ": " + mismatch.get().detail());
            }
            return new CellVerdict(true, LegClass.PASS, fixtureLabel + " ["
                + target.laneName() + "] PASS compile-reject "
                + rejected.code());
        }
        if (!outcome.compiled()) {
            String rule = outcome.errors().stream().map(CompilerDiagnostic::code)
                .filter(code -> "E6005".equals(code)).findFirst().orElse(null);
            return new CellVerdict(false, LegClass.DEFECT, fixtureLabel + " ["
                + target.laneName() + "] compile FAILED"
                + (rule == null ? "" : " E6005")
                + ": " + outcome.firstErrorDetail());
        }
        return new CellVerdict(true, LegClass.PASS, fixtureLabel + " ["
            + target.laneName() + "] PASS compile");
    }

    // =========================================================================
    // 1. The corpus invariants
    // =========================================================================

    private static void testCorpusInvariants(CorpusDiscovery.DiscoveryResult discovery)
            throws Exception {
        System.out.println("-- the corpus invariants: the dispatched set, its "
            + "count, the sidecar schema, and the three-backend dispatch --");
        checkEq(0, discovery.failures().size(),
            "every corpus fixture classifies (no classification failure)");
        Map<String, CorpusDiscovery.Fixture> byPath = discovery.corpusByPath();
        List<CorpusDiscovery.Fixture> dispatched = new ArrayList<>();
        for (CorpusDiscovery.Fixture fixture : discovery.fixtures()) {
            if (fixture.runtimeClassified()) {
                dispatched.add(fixture);
            }
        }
        checkEq(DISPATCHED_COUNT_PIN, dispatched.size(),
            "the dispatched runtime-fixture count is the pinned value");
        Set<String> corpusIndex = discovery.corpusModuleIndex();
        int validated = 0;
        for (CorpusDiscovery.Fixture fixture : dispatched) {
            Path sidecarPath = fixture.sidecarPath(CONFORMANCE);
            check(Files.isRegularFile(sidecarPath),
                fixture.corpusPath() + " carries its expectation sidecar");
            List<SidecarSchemaValidator.CompilationModule> compilationSet =
                CorpusDiscovery.compilationSet(fixture, byPath, CONFORMANCE);
            Optional<SidecarSchemaValidator.ClassificationFailure> failure =
                SidecarSchemaValidator.validate(
                    new SidecarSchemaValidator.ValidationContext(
                        fixture.corpusPath(), fixture.expected(), compilationSet,
                        corpusIndex),
                    Files.readString(sidecarPath, StandardCharsets.UTF_8));
            if (failure.isEmpty()) {
                validated++;
            } else {
                fatal(fixture.corpusPath() + ": the sidecar does not validate: "
                    + failure.get().message());
            }
            SidecarExpectations.StructuredExpectationSidecar sidecar =
                SidecarExpectations.StructuredExpectationSidecar.parse(
                    Files.readString(sidecarPath, StandardCharsets.UTF_8));
            for (String backend : SidecarSchemaValidator.BACKEND_NAMES) {
                check(sidecar.expectationFor(backend) != null,
                    fixture.corpusPath() + " dispatches to backend " + backend);
            }
        }
        checkEq(dispatched.size(), validated,
            "every dispatched sidecar validates clean against schema version 1");
    }

    // =========================================================================
    // 2/3. The compile and capture criteria, one materialized drive per cell
    // =========================================================================

    private static void testFixture(CorpusDiscovery.Fixture fixture,
            List<String> report, List<String> declarationImports) throws Exception {
        String fixtureRel = fixture.corpusPath().substring(0,
            fixture.corpusPath().length() - ".deal".length());
        String label = fixture.corpusPath();
        List<Export> exports;
        try {
            exports = exportsOf(fixture.source(), fixture.corpusPath());
        } catch (RuntimeException e) {
            fatal(label + ": the entry module does not parse for the export "
                + "drive: " + e);
            return;
        }
        if (fixture.source().contains("from \"host/")
                || fixture.source().contains("candidate/")) {
            declarationImports.add(label);
        }
        for (Target target : Target.values()) {
            materializeCompileDrive(fixture, fixtureRel, label, target, exports,
                report);
        }
    }

    /** One fixture/target cell: materialize, compile, and (when producible) drive. */
    private static void materializeCompileDrive(CorpusDiscovery.Fixture fixture,
            String fixtureRel, String label, Target target, List<Export> exports,
            List<String> report) throws Exception {
        Project project = materialize(fixtureRel, target, true);
        try {
            CompileOutcome outcome = compile(project);
            SidecarExpectations.RuntimeExpectation expectation =
                expectationOf(fixture, target);
            CellVerdict compileLeg = compileVerdict(label, target, outcome,
                expectation);
            record(compileLeg, target, report);
            if (!compileLeg.pass()) {
                return;
            }
            if (expectation
                    instanceof SidecarExpectations.RuntimeExpectation.Rejected) {
                // The sanctioned extern-C rejection leg: the compile criterion
                // owns it, and no artifact exists to drive.
                rejectLegs.incrementAndGet();
                return;
            }
            compileLegs.incrementAndGet();
            Execution execution;
            try {
                execution = execute(project, exports, target);
            } catch (AuditInputException e) {
                // A missing host implementation is an audit-input defect,
                // never a construct gap; it is fatal in every mode.
                fatal(label + " [" + target.laneName() + "] audit input: " + e);
                return;
            } catch (Exception e) {
                record(new CellVerdict(false, LegClass.CAPTURE_DEFICIT, label
                    + " [" + target.laneName() + "]: the drive failed: " + e),
                    target, report);
                return;
            }
            captureLegs.incrementAndGet();
            Capture normalized = normalize(project, execution.capture());
            Optional<String> mismatch = compareCell(label, target, normalized,
                execution, expectation);
            if (mismatch.isEmpty()) {
                record(new CellVerdict(true, LegClass.PASS, label + " ["
                    + target.laneName() + "] PASS capture"), target, report);
                return;
            }
            String detail = mismatch.get();
            if ((expectation
                    instanceof SidecarExpectations.RuntimeExpectation.Executed
                        executed) && executed.isRuntimeError()) {
                detail = detail + " - " + captureDetail(normalized,
                    executed.error());
            }
            record(new CellVerdict(false, LegClass.CAPTURE_DEFICIT, detail),
                target, report);
        } finally {
            deleteRecursively(project.root());
        }
    }

    /** Records one cell: the ordered report line, the pass tally, and the leg. */
    private static void record(CellVerdict verdict, Target target,
            List<String> report) {
        synchronized (report) {
            report.add(verdict.line());
        }
        if (verdict.pass()) {
            passed.incrementAndGet();
        }
        legs.add(new Leg(target, verdict.clazz(), verdict.line()));
    }

    // =========================================================================
    // 4. The negative control
    // =========================================================================

    private static void testNegativeControl() throws Exception {
        System.out.println("-- the negative control: a corrupted staged artifact "
            + "fails the audit by name --");
        String fixtureRel = "backend-runtime/control-flow/for-of-break-continue";
        CorpusDiscovery.Fixture fixture = fixtureOf(fixtureRel + ".deal");
        List<Export> exports = exportsOf(fixture.source(), fixture.corpusPath());
        Project project = materialize(fixtureRel, Target.LUAJIT, true);
        try {
            CompileOutcome outcome = compile(project);
            check(outcome.compiled(), "the negative-control fixture compiles: "
                + outcome.firstErrorDetail());
            if (!outcome.compiled()) {
                return;
            }
            // The pristine drive reproduces the runtime-ok pin.
            Execution pristine = execute(project, exports, Target.LUAJIT);
            SidecarExpectations.RuntimeExpectation expectation =
                expectationOf(fixture, Target.LUAJIT);
            Optional<String> pristineMismatch = compareCell(
                fixture.corpusPath(), Target.LUAJIT,
                normalize(project, pristine.capture()), pristine, expectation);
            check(pristineMismatch.isEmpty(),
                "the pristine negative-control drive passes: "
                    + pristineMismatch.orElse(""));

            // Corrupt the staged chunk: the corrupted copy carries a
            // broken chunk (the production artifact's own bytes replaced by
            // a chunk that publishes no deferred entry), so the drive
            // cannot load it and the comparison fails by fixture and field.
            Path artifact = artifactOf(project, Target.LUAJIT);
            String text = Files.readString(artifact, StandardCharsets.UTF_8);
            check(!text.isEmpty(), "the staged chunk carries the production "
                + "artifact the negative control corrupts");
            Path corrupted = project.outputRoot().resolve("__corrupted.lua");
            Files.writeString(corrupted,
                text.substring(0, Math.min(64, text.length()))
                    .replace("__dealMain", "__dealMainBroken"),
                StandardCharsets.UTF_8);
            Path transport = project.outputRoot().resolve(TRANSPORT_FILE);
            Files.deleteIfExists(transport);
            Path probe = project.outputRoot().resolve("__corrupted_probe.lua");
            Files.writeString(probe, luaProbe(project, exports, corrupted,
                transport), StandardCharsets.UTF_8);
            ProcessBuilder builder = new ProcessBuilder("luajit",
                probe.toAbsolutePath().toString());
            builder.directory(project.outputRoot().toFile());
            builder.environment().put("DEAL_DEFER_MAIN", "1");
            ProcessOutcome corruptedRun = runProcess(builder);
            Capture capture = normalize(project, readTransport(transport));
            Execution corruptedExecution = new Execution(corruptedRun.exitCode(),
                corruptedRun.stdout(), corruptedRun.stderr(), readTransport(transport));
            Optional<String> corruptedMismatch = compareCell(
                fixture.corpusPath(), Target.LUAJIT, capture, corruptedExecution,
                expectation);
            check(corruptedMismatch.isPresent(),
                "the corrupted artifact fails the capture criterion");
            check(corruptedMismatch.isPresent()
                    && corruptedMismatch.get().contains(fixture.corpusPath()),
                "the corrupted-artifact failure names the fixture: "
                    + corruptedMismatch.orElse(""));
            System.out.println("  negative control: "
                + corruptedMismatch.orElse("<no failure>"));

            // The compile criterion's guard detection is not vacuous: a
            // hand-built E6005 guard diagnostic is reported by the detector.
            CompilerDiagnostic guard = FailureContractRegistry.e6005(
                new LoweringFailureDetail("negative-control",
                    SemanticCapability.CONTAINERS_AND_STRINGS,
                    SemanticLowerer.CONSTRUCT_UNLOWERED,
                    SemanticProfile.DEAL_V1_2_INT32, "deal.semantic-ir/1",
                    "SemanticLowerer CONSTRUCT_UNLOWERED (negative control)"));
            check(isGuardDiagnostic(guard),
                "the guard detector reports a hand-built CONSTRUCT_UNLOWERED "
                    + "E6005");

            // The acceptance policy's truth table is not vacuous: the
            // pre-precondition rule reports the construct and capture deficit
            // while the compile criterion is unmet, and the strict acceptance
            // mode makes every non-pass leg fatal once it is met.
            check(!isFatal(LegClass.CONSTRUCT_DEFICIT, false)
                    && !isFatal(LegClass.CAPTURE_DEFICIT, false),
                "the pre-precondition reporting rule makes the construct and "
                    + "capture deficit non-fatal while the compile criterion "
                    + "is unmet");
            check(isFatal(LegClass.CONSTRUCT_DEFICIT, true)
                    && isFatal(LegClass.CAPTURE_DEFICIT, true),
                "the strict acceptance mode makes every non-pass leg fatal");
            check(isFatal(LegClass.ARCHITECTURAL_GUARD, false)
                    && isFatal(LegClass.ARCHITECTURAL_GUARD, true)
                    && isFatal(LegClass.DEFECT, false)
                    && isFatal(LegClass.DEFECT, true),
                "architectural-guard legs and non-guard defects are fatal in "
                    + "both modes");
            check(!isFatal(LegClass.PASS, false)
                    && !isFatal(LegClass.PASS, true),
                "a pin-exact leg is never fatal");
        } finally {
            deleteRecursively(project.root());
        }
    }

    private static CorpusDiscovery.Fixture fixtureOf(String corpusPath)
            throws Exception {
        CorpusDiscovery.DiscoveryResult discovery = CorpusDiscovery.discover(CONFORMANCE);
        for (CorpusDiscovery.Fixture fixture : discovery.fixtures()) {
            if (fixture.corpusPath().equals(corpusPath)) {
                return fixture;
            }
        }
        throw new IllegalStateException("the corpus carries no fixture "
            + corpusPath);
    }

    // =========================================================================
    // 5. The materialization fidelity
    // =========================================================================

    private static void testMaterializationFidelity() throws Exception {
        System.out.println("-- the materialization fidelity: the declaration "
            + "wiring and the companion layout are load-bearing (an omitted "
            + "declaration reproduces the frontend E2003 gap) --");
        String[] samples = {
            "backend-runtime/host-abi/host-array-return-ok",
            "backend-runtime/ffi/012-ffi-double-roundtrip",
            "backend-runtime/modules/modid-class-identity",
            "backend-runtime/async-await/async-cross-module",
        };
        for (String fixtureRel : samples) {
            CorpusDiscovery.Fixture fixture = fixtureOf(fixtureRel + ".deal");
            Project wired = materialize(fixtureRel, Target.LUAJIT, true);
            try {
                // (a) The materialized layout: the implementation modules of
                // the compilation set (the declaration modules are wired as
                // bindings), the alias copy, and the nested companion.
                Set<String> materialized = new LinkedHashSet<>(wired.modules()
                    .values().stream().map(Materialized::corpusPath).toList());
                for (SidecarSchemaValidator.CompilationModule module
                        : CorpusDiscovery.compilationSet(fixture,
                            CorpusDiscovery.discover(CONFORMANCE).corpusByPath(),
                            CONFORMANCE)) {
                    if (module.corpusPath().endsWith(".d.deal")) {
                        continue; // a declaration module is wired as a binding
                    }
                    check(materialized.contains(module.corpusPath()),
                        fixtureRel + ": the materialization carries the "
                            + "compilation-set module " + module.corpusPath());
                }
                if (fixtureRel.contains("async-cross-module")) {
                    check(Files.isRegularFile(wired.srcRoot()
                            .resolve("async_lib.deal.deal")),
                        fixtureRel + ": the explicit-.deal alias copy exists at "
                            + "src/async_lib.deal.deal");
                }
                if (fixtureRel.contains("modid-class-identity")) {
                    check(Files.isRegularFile(wired.srcRoot()
                            .resolve("modid_lib_a/lib_a.deal")),
                        fixtureRel + ": the nested companion keeps its layout");
                }
                if (fixtureRel.contains("ffi/012")) {
                    check(Files.isRegularFile(wired.root().resolve("bindings")
                            .resolve("ffi_candidate_native.d.deal")),
                        fixtureRel + ": the corpus FFI declaration is wired "
                            + "under bindings/");
                }
                for (String declaration : wired.rawToDeclaration().values()) {
                    check(Files.isRegularFile(wired.root().resolve(declaration)),
                        fixtureRel + ": the wired declaration exists: "
                            + declaration);
                }
                CompileOutcome outcome = compile(wired);
                check(outcome.compiled(), fixtureRel + ": the wired "
                    + "materialization compiles: " + outcome.firstErrorDetail());
            } finally {
                deleteRecursively(wired.root());
            }

            // (b) The load-bearing probe: omit the declaration wiring (host/
            // FFI) or the companion copy (relative import) and observe the
            // frontend E2003 gap the lanes' materialization prevents.
            Project unwired = materialize(fixtureRel, Target.LUAJIT,
                !(fixtureRel.contains("host-abi") || fixtureRel.contains("ffi/012")));
            try {
                if (fixtureRel.contains("modid-class-identity")) {
                    Files.deleteIfExists(unwired.srcRoot()
                        .resolve("modid_lib_a/lib_a.deal"));
                }
                if (fixtureRel.contains("async-cross-module")) {
                    Files.deleteIfExists(unwired.srcRoot()
                        .resolve("async_lib.deal.deal"));
                }
                CompileOutcome outcome = compile(unwired);
                check(!outcome.compiled(),
                    fixtureRel + ": the omitted declaration must not compile "
                        + "(the wiring is load-bearing)");
                boolean e2003 = outcome.errors().stream()
                    .anyMatch(d -> "E2003".equals(d.code()));
                check(e2003, fixtureRel + ": the omitted declaration "
                    + "reproduces the frontend E2003 gap: "
                    + outcome.firstErrorDetail());
            } finally {
                deleteRecursively(unwired.root());
            }
        }
    }

    // =========================================================================
    // Utilities
    // =========================================================================

    static void deleteRecursively(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception ignored) {
                    // best-effort temp cleanup
                }
            }
        } catch (Exception ignored) {
            // best-effort temp cleanup
        }
    }

    // =========================================================================
    // Main
    // =========================================================================

    public static void main(String[] args) throws Exception {
        System.out.println("=== Production-Corpus Audit Tests (ISSUE-0703) ===");
        // The audit's own bounded measurement pool: the registered gate runs
        // the suite with `--jobs 1`, and the whole dispatch measured serially
        // would exceed the harness's command budget, so the audit keeps a
        // bounded internal pool (default min(8, processors); the
        // `deal.audit.jobs` property overrides it) and reports it. The
        // pool only drives this test's own compiles and toolchain
        // subprocesses; the harness's suite-level scheduling is unchanged.
        int auditJobs = Integer.getInteger("deal.audit.jobs",
            Math.max(1, Math.min(8,
                Runtime.getRuntime().availableProcessors())));
        System.out.println("Audit worker bound: " + auditJobs
            + " (deal.audit.jobs="
            + System.getProperty("deal.audit.jobs", "<unset>") + ")");

        CorpusDiscovery.DiscoveryResult discovery =
            CorpusDiscovery.discover(CONFORMANCE);
        testCorpusInvariants(discovery);
        List<CorpusDiscovery.Fixture> dispatched = discovery.fixtures().stream()
            .filter(CorpusDiscovery.Fixture::runtimeClassified)
            .toList();

        System.out.println("-- the compile and capture criteria: every dispatched "
            + "fixture compiles on both production targets with zero producer "
            + "guards, and every producible fixture's capture reproduces its "
            + "sidecar through the landed comparison --");
        List<String> report = Collections.synchronizedList(new ArrayList<>());
        List<String> declarationImports =
            Collections.synchronizedList(new ArrayList<>());
        ExecutorService pool = Executors.newFixedThreadPool(auditJobs);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (CorpusDiscovery.Fixture fixture : dispatched) {
                futures.add(pool.submit(() -> {
                    try {
                        testFixture(fixture, report, declarationImports);
                    } catch (Exception e) {
                        // An audit-input defect (an unresolvable declaration
                        // wiring, a missing sidecar, a missing host
                        // implementation): fatal, never a construct gap.
                        fatal(fixture.corpusPath() + ": audit input: " + e);
                    }
                }));
            }
            for (Future<?> future : futures) {
                future.get();
            }
        } finally {
            pool.shutdown();
        }
        System.out.println("  declaration-importing group measured: "
            + declarationImports.size() + " fixtures");

        testNegativeControl();
        testMaterializationFidelity();

        List<String> ordered = new ArrayList<>(report);
        ordered.sort(Comparator.naturalOrder());
        System.out.println();
        System.out.println("-- per-fixture, per-target verdicts --");
        for (String line : ordered) {
            System.out.println("  " + line);
        }
        System.out.println();
        System.out.println("dispatched fixtures: " + dispatched.size()
            + " (pin " + DISPATCHED_COUNT_PIN + ")");
        System.out.println("compile legs: " + compileLegs.get()
            + " pass / " + (dispatched.size() * Target.values().length
                - compileLegs.get() - rejectLegs.get()) + " fail, "
            + "compile-reject legs: " + rejectLegs.get()
            + " (the pinned extern-C E6006)");
        System.out.println("capture legs: " + captureLegs.get()
            + " driven on the producible set");
        for (Target target : Target.values()) {
            long targetPass = legs.stream().filter(leg ->
                leg.target() == target && leg.clazz() == LegClass.PASS).count();
            long targetFail = legs.stream().filter(leg ->
                leg.target() == target && leg.clazz() != LegClass.PASS).count();
            System.out.println("  " + target.laneName() + ": " + targetPass
                + " pass / " + targetFail + " fail");
        }

        // The acceptance verdicts and the pre-precondition rule (HR4(a), A5,
        // CD5): while the dispatched set still carries a checker-valid
        // CONSTRUCT_UNLOWERED deficit the corpus run reports the residual
        // failures per fixture and never skips; once the compile criterion is
        // met the strict acceptance gate engages and every non-pin-exact
        // capture leg is fatal.
        boolean acceptanceMode = legs.stream().noneMatch(leg ->
            leg.clazz() == LegClass.CONSTRUCT_DEFICIT);
        long constructDeficit = legs.stream().filter(leg ->
            leg.clazz() == LegClass.CONSTRUCT_DEFICIT).count();
        long architecturalDeficit = legs.stream().filter(leg ->
            leg.clazz() == LegClass.ARCHITECTURAL_GUARD).count();
        long captureDeficit = legs.stream().filter(leg ->
            leg.clazz() == LegClass.CAPTURE_DEFICIT).count();
        long defects = legs.stream().filter(leg ->
            leg.clazz() == LegClass.DEFECT).count();
        List<String> deficits = new ArrayList<>();
        for (Leg leg : legs) {
            if (leg.clazz() == LegClass.PASS) {
                continue;
            }
            if (isFatal(leg.clazz(), acceptanceMode)) {
                fatal(leg.line());
            } else {
                deficits.add(leg.line());
            }
        }
        System.out.println();
        System.out.println("-- the acceptance verdicts --");
        System.out.println("zero CONSTRUCT_UNLOWERED over the dispatched set: "
            + (constructDeficit == 0 ? "PASS"
                : "FAIL (" + constructDeficit + " legs)"));
        System.out.println("zero RETAINED_ABI_DEFERRED / SHARED_EMITTER_COVERAGE: "
            + (architecturalDeficit == 0 ? "PASS"
                : "FAIL (" + architecturalDeficit + " legs)"));
        System.out.println("byte-exact captures on the producible set: "
            + (captureDeficit == 0 ? "PASS"
                : "FAIL (" + captureDeficit + " legs)"));
        System.out.println("non-guard compile failures and pinned-rejection "
            + "mismatches: " + (defects == 0 ? "none"
                : defects + " legs"));
        System.out.println("strict acceptance mode: " + (acceptanceMode
            ? "ENGAGED (every non-pin-exact capture leg is fatal)"
            : "DISENGAGED (the pre-precondition reporting rule: the "
                + "CONSTRUCT_UNLOWERED deficit is reported per fixture, "
                + "never skipped)"));
        if (!deficits.isEmpty()) {
            System.out.println();
            System.out.println("Acceptance deficit (reported, non-fatal under "
                + "the pre-precondition rule):");
            for (String line : deficits) {
                System.out.println("  " + line);
            }
        }
        System.out.println();
        System.out.println("passed: " + passed.get() + ", fatal: "
            + fatalCount.get() + ", reported deficit: " + deficits.size());
        if (fatalCount.get() > 0) {
            System.out.println();
            System.out.println("Fatal audit failures:");
            for (String line : fatalLines) {
                System.out.println("  " + line);
            }
            System.exit(1);
        }
    }
}
