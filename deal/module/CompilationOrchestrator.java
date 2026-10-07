package deal.module;

import deal.ast.*;
import deal.checker.*;
import deal.codegen.Backend;
import deal.codegen.SourceMapGenerator;
import deal.codegen.HostModuleDeclarations;
import deal.codegen.js.JsBackend;
import deal.identity.CanonicalModuleIdentity;
import deal.ir.IrDumper;
import deal.lexer.*;
import deal.project.ConfiguredModuleRoot;
import deal.project.ExternalEntry;
import deal.project.NormalizedDeclarationPath;
import deal.project.OutputConfigResolver;
import deal.project.ProjectContext;
import deal.project.ProjectDeploymentIdentity;
import deal.project.ProjectLocator;
import deal.project.NativeLibraryRef;
import deal.project.ProtectedPathOps;
import deal.diagnostics.CompilerDiagnostic;
import deal.diagnostics.DiagnosticFormatter;
import deal.diagnostics.DiagnosticOrder;
import deal.diagnostics.DiagnosticRange;
import deal.diagnostics.DiagnosticStructuredOutput;
import deal.descriptors.CanonicalRuntimeTypeDescriptor;
import deal.distribution.DistributionHome;
import deal.ffi.FfiDeclarationValidator;
import deal.ffi.FfiGeneratedModule;
import deal.ffi.LuaFfiBindingGenerator;
import deal.identity.CanonicalClassIdentity;
import deal.publication.PublicationStager;
import deal.parser.*;
import deal.semantic.CheckedProjectBuildResult;
import deal.semantic.CheckedProjectBuilder;
import deal.semantic.CheckedProjectInput;
import deal.semantic.CompilerInvocation;
import deal.semantic.CompilerProfileProvider;
import deal.semantic.HostDeclarationSurface;
import deal.semantic.LoweringSupport;
import deal.semantic.ModuleFact;
import deal.semantic.ReleaseConfiguration;
import deal.semantic.RequirementManifestResult;
import deal.semantic.CheckedModuleInput;
import deal.semantic.ir.IntrinsicKind;
import deal.semantic.ir.ModuleId;
import deal.types.Type;
import deal.types.Types;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import deal.diagnostics.DiagnosticCode;
import java.util.*;

/**
 * Orchestrates multi-module compilation: discovery, parsing, type checking,
 * and code generation for an entire DEAL project.
 *
 * <p>Pipeline phases:
 * <ol>
 *   <li>Discovery: resolve entry module, parse it, find imports, parse
 *       imported modules, repeat depth-first until all modules parsed</li>
 *   <li>Signature extraction: extract export signatures from each parsed
 *       module (lightweight, no full type checking)</li>
 *   <li>Dependency graph: build graph from import relationships, topological
 *       sort, detect circular runtime dependencies</li>
 *   <li>Type checking: in topological order, run Pass 1 (NameResolver) and
 *       Pass 2 (TypeChecker) on each module</li>
 *   <li>Code generation: emit .lua files for each module and copy runtime</li>
 * </ol>
 */
public final class CompilationOrchestrator {

    private final Path entryFile;
    private final Path outputRoot;
    private final boolean verbose;
    private final boolean dumpIr;
    private final boolean sourceMap;

    private final boolean sourceMapExplicit;
    private final Backend backend;

    /**
     * The single immutable exact-v1.2 project context governing this
     * compilation (design source
     * {@code strict-project-context-resolution-identity} D1/D7): the
     * production constructor consumes the context published by
     * {@link ProjectLocator}; the test-only isolated-phase constructors
     * synthesize one from their legacy inputs. The context is the single
     * authority for module roots, externals declarations, the stdlib
     * surface, the output path, and the private deployment identity —
     * the legacy {@code moduleRoots}/{@code stdlibDir} fields and the
     * {@code externalsDeclarations}/{@code externalsModulePaths} maps
     * are gone.
     */
    private final ProjectContext context;

    /**
     * True when the compilation's context was authored from a
     * {@code deal.json} manifest (the production
     * {@link ProjectContext}-taking constructor — CLI, the production
     * orchestrator path, and project-level conformance); false for the
     * test-only isolated-phase constructors whose context is
     * synthesized from the harness's own declarations map (parent D10:
     * those APIs may omit {@code ProjectContext}, and their externals
     * map cannot carry {@code nativeLibrary}). The v1.2 C FFI manifest
     * policy's {@code nativeLibrary} authoring rule
     * (docs/spec-v1.2.md:1891 — "a C FFI entry must include
     * nativeLibrary") is enforced exactly on manifest-authored
     * contexts; the synthesized test-only entries are the harness's
     * authority channel and count as manifest-backed.
     */
    private final boolean manifestAuthoredContext;

    /**
     * The T6 source resolver over {@link #context}: every import — and
     * the entry file via {@link SourceModuleResolver#resolveEntryFile} —
     * resolves through its pinned rules (importer-relative first,
     * externals declaration authority, configured roots then the pinned
     * stdlib surface, the 6-module filter, file-keyed E2009; no CWD
     * module fallback), and every successfully resolved source receives
     * one private {@link SourceModuleLocation}.
     */
    private final SourceModuleResolver sourceResolver;

    /**
     * The T7 identity assembly over {@link #context}: class declarations
     * are gated through
     * {@link ModuleIdentityAssembly#gateClassDeclaration} (the
     * unconditional E2010 for a class in an identity-less source) and
     * required public class identities through
     * {@link ModuleIdentityAssembly#requireClassIdentity}.
     */
    private final ModuleIdentityAssembly identityAssembly;

    /**
     * The published {@link SourceModuleLocation} per module source path
     * (keyed by the location's {@code normalizedSourcePath}), in
     * first-publication order.
     */
    private final Map<String, SourceModuleLocation> locations =
        new LinkedHashMap<>();

    /**
     * The release-owned compiler invocation resolved at compile start
     * (foundation F1/F8): every compile carries exactly one purpose,
     * profile, release state, and derived release-state hash. Public
     * builds resolve PUBLIC_BUILD through
     * {@link CompilerProfileProvider}; the verbose report prints the
     * recorded facts but the recording location is the invocation's
     * immutable {@code releaseStateHash} field.
     */
    private final CompilerInvocation invocation;

    private CheckedProjectBuildResult checkedProjectBuild;

    private RequirementManifestResult requirementManifests;

    private int semanticEmissionCount = 0;

    private final Map<String, ModuleInfo> modules = new LinkedHashMap<>();
    private final List<CompilerDiagnostic> diagnostics = new ArrayList<>();
    private boolean hasErrors = false;

    private List<String> dependencyOrder = List.of();

    private final Map<String, FfiGeneratedModule> ffiGenerations =
        new LinkedHashMap<>();

    private HostDeclarationSurface hostDeclarationSurface;

    private final Map<String, List<PlannedDefaultClass>>
        plannedDefaultClasses = new LinkedHashMap<>();

    private final Map<String, List<PlannedDefaultClass>>
        completedDefaultPlans = new LinkedHashMap<>();

    private List<RuntimeImportDependency> runtimeDependencies =
        List.of();

    private final Map<String, JsBackend.JsCodegenResult>
        jsGeneratedResults = new LinkedHashMap<>();

    /**
     * The {@code --diagnostics-json} output path, or {@code null} when the
     * structured document was not requested. When set, the orchestrator
     * writes the {@link DiagnosticStructuredOutput} document for every
     * compilation — successful or failed — without changing the exit code;
     * a write failure is a deterministic compiler I/O diagnostic on
     * stderr with exit 1 (D8, parent D11 I/O discipline).
     */
    private final Path diagnosticsJsonPath;

    private final DistributionHome distributionHome;

    /**
     * The transactional whole-project publication stager of the running
     * {@link #compile()} call (design source
     * {@code whole-project-artifact-publication} D1-D6): every output
     * byte — phase-4 module artifacts, the runtime/stdlib deployment
     * copies, the IR dumps, and the source-map sidecars — is staged
     * into its per-invocation staging tree and atomically swapped into
     * the live output root by the publish step; nothing writes the live
     * root except that step. {@code null} outside a {@code compile()}
     * call.
     */
    private PublicationStager stager;

    /**
     * The first staging write failure of the running compile (D4): when
     * set, nothing is published, the stage tree is removed in
     * {@link #compile()}, the diagnostics report is unchanged, and the
     * pinned deterministic compiler I/O diagnostic
     * {@code deal: cannot publish artifacts to '<root>': <reason>} is
     * printed on stderr with exit 1.
     */
    private IOException pendingStageFailure;

    private static final class ModuleInfo {
        final String sourcePath;
        final String modulePath;
        final boolean isDeclarationFile;
        final SourceModuleLocation location;
        ProgramNode rawAst;
        Map<String, Type> exports;
        SymbolTable symbolTable;
        CheckResult checkResult;
        ParseResult parseResult;
        NameResolver nameResolver;

        ModuleInfo(String sourcePath, String modulePath, boolean isDeclarationFile,
                   SourceModuleLocation location) {
            this.sourcePath = sourcePath;
            this.modulePath = modulePath;
            this.isDeclarationFile = isDeclarationFile;
            this.location = location;
        }
    }

    public CompilationOrchestrator(ProjectContext context, Path entryFile,
                                    boolean verbose, boolean dumpIr,
                                    boolean sourceMap, boolean sourceMapExplicit,
                                    Path diagnosticsJsonPath,
                                    CompilerInvocation invocation) {
        this.invocation = java.util.Objects.requireNonNull(invocation,
            "invocation must not be null");
        this.context = java.util.Objects.requireNonNull(context, "context");
        this.manifestAuthoredContext = true;
        this.backend = backendOf(context.backend());
        this.entryFile = entryFile.toAbsolutePath().normalize();
        this.outputRoot = Path.of(context.outputPath().absoluteNormalizedPath());
        this.verbose = verbose;
        this.dumpIr = dumpIr;
        this.sourceMap = sourceMap;
        this.sourceMapExplicit = sourceMapExplicit;
        this.diagnosticsJsonPath = diagnosticsJsonPath;
        this.distributionHome = DistributionHome.forManifestDirectory(
            context.manifestDirectory());
        this.sourceResolver = new SourceModuleResolver(context);
        this.identityAssembly = new ModuleIdentityAssembly(context);
    }

    /**
     * Test-only isolated-phase constructor (parent D10: isolated phase
     * APIs may omit {@link ProjectContext}; CLI, the production
     * orchestrator path, and project-level conformance may not). The
     * legacy inputs — explicit module roots, a stdlib-directory hint,
     * an externals declarations map (raw import specifier → declaration
     * path text), the backend, and the output root — are synthesized
     * into an internal {@link ProjectContext} so every compilation still
     * flows through the one {@link SourceModuleResolver} +
     * {@link ModuleIdentityAssembly} pipeline. Production code never
     * constructs these overloads.
     */
    public CompilationOrchestrator(Path entryFile, Path outputRoot, boolean verbose,
                                    Map<String, String> externalsDeclarations,
                                    List<Path> moduleRoots, Path stdlibDir) {
        this(entryFile, outputRoot, verbose, false, false, Backend.LUAJIT,
            externalsDeclarations, moduleRoots, stdlibDir);
    }

    /** Test-only isolated-phase overload; see the 6-argument form. */
    public CompilationOrchestrator(Path entryFile, Path outputRoot, boolean verbose,
                                    boolean dumpIr,
                                    Map<String, String> externalsDeclarations,
                                    List<Path> moduleRoots, Path stdlibDir) {
        this(entryFile, outputRoot, verbose, dumpIr, false, Backend.LUAJIT,
            externalsDeclarations, moduleRoots, stdlibDir);
    }

    /** Test-only isolated-phase overload; see the 6-argument form. */
    public CompilationOrchestrator(Path entryFile, Path outputRoot, boolean verbose,
                                    boolean dumpIr, boolean sourceMap,
                                    Map<String, String> externalsDeclarations,
                                    List<Path> moduleRoots, Path stdlibDir) {
        this(entryFile, outputRoot, verbose, dumpIr, sourceMap, Backend.LUAJIT,
            externalsDeclarations, moduleRoots, stdlibDir);
    }

    /**
     * Test-only isolated-phase overload with an explicit backend (the
     * JS-backend harnesses pass {@link Backend#JS} for test-local
     * compilations without a manifest); see the 6-argument form. LuaJIT
     * remains the default: the overloads above delegate with
     * {@link Backend#LUAJIT}.
     */
    public CompilationOrchestrator(Path entryFile, Path outputRoot, boolean verbose,
                                    boolean dumpIr, boolean sourceMap, Backend backend,
                                    Map<String, String> externalsDeclarations,
                                    List<Path> moduleRoots, Path stdlibDir) {
        this(entryFile, outputRoot, verbose, dumpIr, sourceMap, sourceMap,
            backend, externalsDeclarations, moduleRoots, stdlibDir);
    }

    /**
     * Test-only isolated-phase overload; see the 6-argument form.
     * {@code sourceMapExplicit} distinguishes the explicit
     * {@code --source-map} request from the effective {@code sourceMap}
     * flag (also derived from {@code --dump-ir}).
     */
    public CompilationOrchestrator(Path entryFile, Path outputRoot, boolean verbose,
                                    boolean dumpIr, boolean sourceMap,
                                    boolean sourceMapExplicit, Backend backend,
                                    Map<String, String> externalsDeclarations,
                                    List<Path> moduleRoots, Path stdlibDir) {
        this(entryFile, outputRoot, verbose, dumpIr, sourceMap,
            sourceMapExplicit, backend, externalsDeclarations, moduleRoots,
            stdlibDir, null, defaultInvocation());
    }

    /**
     * The canonical test-only isolated-phase constructor: synthesizes the
     * internal {@link ProjectContext} from the legacy inputs (see
     * {@link #synthesizeContext}) and installs the one
     * {@link SourceModuleResolver} + {@link ModuleIdentityAssembly}
     * pipeline. With the structured-output path set (D8),
     * {@link #compile()} writes the {@link DiagnosticStructuredOutput}
     * document for every compilation — successful or failed — without
     * changing the exit code; a write failure is a deterministic
     * compiler I/O diagnostic on stderr with exit 1.
     */
    public CompilationOrchestrator(Path entryFile, Path outputRoot, boolean verbose,
                                    boolean dumpIr, boolean sourceMap,
                                    boolean sourceMapExplicit, Backend backend,
                                    Map<String, String> externalsDeclarations,
                                    List<Path> moduleRoots, Path stdlibDir,
                                    Path diagnosticsJsonPath,
                                    CompilerInvocation invocation) {
        this.invocation = java.util.Objects.requireNonNull(invocation,
            "invocation must not be null");
        this.context = synthesizeContext(entryFile, outputRoot, backend,
            externalsDeclarations, moduleRoots, stdlibDir);
        this.manifestAuthoredContext = false;
        this.backend = backend;
        this.entryFile = entryFile.toAbsolutePath().normalize();
        this.outputRoot = outputRoot.toAbsolutePath().normalize();
        this.verbose = verbose;
        this.dumpIr = dumpIr;
        this.sourceMap = sourceMap;
        this.sourceMapExplicit = sourceMapExplicit;
        this.diagnosticsJsonPath = diagnosticsJsonPath;
        this.distributionHome = DistributionHome.forManifestDirectory(
            context.manifestDirectory());
        this.sourceResolver = new SourceModuleResolver(context);
        this.identityAssembly = new ModuleIdentityAssembly(context);
    }

    /**
     * The strict effective backend of a published context
     * ({@code "luajit"} → {@link Backend#LUAJIT}, {@code "jvm"} →
     * {@link Backend#JVM}, {@code "js"} → {@link Backend#JS}); any
     * other text is a defensive programming error for a validated
     * context (the strict backend set is closed) and fails with
     * {@link IllegalArgumentException}.
     */
    private static Backend backendOf(String backendText) {
        return switch (backendText) {
            case "luajit" -> Backend.LUAJIT;
            case "jvm" -> Backend.JVM;
            case "js" -> Backend.JS;
            default -> throw new IllegalArgumentException(
                "unsupported effective backend: " + backendText);
        };
    }

    /**
     * Synthesizes the internal {@link ProjectContext} of a test-only
     * isolated-phase constructor (no {@code ProjectContext} input, parent
     * D10): the given module roots become {@link ConfiguredModuleRoot}s
     * through the D4 root conversion (no existence requirement;
     * longest-existing-directory-prefix symlink resolution) with a
     * deterministic representable {@code configuredText} derived from the
     * root's absolute path components (test setups have no manifest
     * spelling); the externals declarations map becomes
     * {@link ExternalEntry} records with normalized declaration paths;
     * the stdlib surface is probed (stdlib-directory hint first, then the
     * entry directory, then the process CWD) and the six spec-listed
     * declaration files under the surface are canonicalized; the output
     * is classified from the given output root; and the private
     * deployment identity is a deterministic synthetic value over the
     * entry directory (test-only compilations carry no manifest bytes).
     * Deterministic for unchanged inputs.
     */
    private static ProjectContext synthesizeContext(Path entryFile, Path outputRoot,
            Backend backend, Map<String, String> externalsDeclarations,
            List<Path> moduleRoots, Path stdlibDir) {
        Path entryDir = entryFile.toAbsolutePath().normalize().getParent();
        String entryDirText = entryDir.toString();

        List<ConfiguredModuleRoot> roots = new ArrayList<>();
        for (Path root : moduleRoots) {
            String rootText = root.toAbsolutePath().normalize().toString();
            ProtectedPathOps.PathResult converted =
                ProtectedPathOps.normalizePrefixResolved(rootText);
            String absolute = converted instanceof ProtectedPathOps.PathResult.Success success
                ? success.resolvedPath().toString()
                : rootText;
            roots.add(new ConfiguredModuleRoot(
                configuredRootTextOf(Path.of(absolute)), absolute, null));
        }

        String surface = probeLegacySurface(stdlibDir, entryDir);
        List<String> stdlibDeclarationFiles =
            resolveSurfaceDeclarationFiles(surface);

        Map<String, ExternalEntry> externals = new LinkedHashMap<>();
        if (externalsDeclarations != null) {
            for (Map.Entry<String, String> entry
                    : externalsDeclarations.entrySet()) {
                Path declaration = Path.of(entry.getValue());
                if (!declaration.isAbsolute()) {
                    declaration = entryDir.resolve(declaration);
                }
                externals.put(entry.getKey(), new ExternalEntry(entry.getKey(),
                    new NormalizedDeclarationPath(
                        declaration.normalize().toString(), null),
                    null, null));
            }
        }

        OutputConfigResolver.OutputRef output = new OutputConfigResolver.OutputRef(
            OutputConfigResolver.Source.MANIFEST,
            outputRoot.isAbsolute() ? OutputConfigResolver.Kind.ABSOLUTE_PATH
                : OutputConfigResolver.Kind.MANIFEST_RELATIVE_PATH,
            outputRoot.toString(),
            outputRoot.toAbsolutePath().normalize().toString(), null);

        return new ProjectContext(
            entryDir.resolve("deal.json").normalize().toString(),
            entryDirText,
            entryDirText,
            "1.2",
            roots,
            output,
            backend.cliName(),
            externals,
            "1.2",
            surface,
            stdlibDeclarationFiles,
            syntheticDeploymentIdentity(entryDir));
    }

    private static String configuredRootTextOf(Path absoluteRoot) {
        Path fileName = absoluteRoot.getFileName();
        return fileName == null || fileName.toString().isEmpty()
            ? "project" : fileName.toString();
    }

    /**
     * The legacy stdlib-surface probe of a synthesized context: the
     * stdlib-directory hint (the legacy "parent of std/" value) first,
     * then {@code <entryDirectory>/std}, then the process-CWD
     * {@code std} directory; absence is a value (null), never an error.
     * The returned path is fully symlink-resolved when present.
     */
    private static String probeLegacySurface(Path stdlibDir, Path entryDir) {
        Optional<Path> hinted = ProtectedPathOps.probeDirectory(
            stdlibDir == null ? entryDir.resolve("std")
                : stdlibDir.resolve("std"));
        if (hinted.isPresent()) {
            return hinted.get().toString();
        }
        Optional<Path> local = ProtectedPathOps.probeDirectory(
            entryDir.resolve("std"));
        if (local.isPresent()) {
            return local.get().toString();
        }
        Optional<Path> distribution = ProtectedPathOps.probeDirectory(
            Path.of("").toAbsolutePath().resolve("std"));
        return distribution.map(Path::toString).orElse(null);
    }

    /**
     * The six spec-listed stdlib declaration files under a resolved
     * surface, each fully symlink-resolved (the
     * {@link ProjectContext#stdlibDeclarationFiles()} derivation of a
     * synthesized context); missing/non-regular/unresolvable files are
     * omitted, and an absent surface yields the empty list.
     */
    private static List<String> resolveSurfaceDeclarationFiles(String surface) {
        List<String> files = new ArrayList<>();
        if (surface == null) {
            return files;
        }
        for (String module : ProjectLocator.SPEC_STDLIB_MODULES) {
            Path pinned = Path.of(surface).resolve(module + ".d.deal");
            if (!Files.isRegularFile(pinned)) {
                continue;
            }
            try {
                files.add(pinned.toRealPath().toString());
            } catch (IOException ignored) {
                // No canonical path: no resolved source can equal it.
            }
        }
        return files;
    }

    /**
     * The deterministic synthetic deployment identity of a test-only
     * context: the {@code file:} URI of a synthesized manifest path
     * under the entry directory plus SHA-256 over the empty manifest
     * byte array (test-only compilations carry no manifest bytes).
     */
    private static ProjectDeploymentIdentity syntheticDeploymentIdentity(
            Path entryDir) {
        ProtectedPathOps.UriResult uri = ProtectedPathOps.toFileUri(
            entryDir.resolve("deal.json"));
        String uriText = uri instanceof ProtectedPathOps.UriResult.Success success
            ? success.uri().toString()
            : "file:" + entryDir.resolve("deal.json");
        // The pinned module identity-digest facility (the closed compiler
        // SHA-256 registry owns every digest site; this synthesized
        // test-only identity reuses it rather than a second digest
        // implementation).
        return new ProjectDeploymentIdentity(uriText,
            IdentityDigests.sha256Hex(new byte[0]));
    }

    /**
     * The default release-owned invocation used by every constructor that
     * is not given one explicitly: {@code PUBLIC_BUILD} resolved through
     * {@link CompilerProfileProvider} from
     * {@link ReleaseConfiguration#CURRENT_RELEASE_STATE} and the release
     * capability registry (A2 — the single release-owned selection
     * point; the release state is {@code V1_2_ACTIVE} after the E12
     * activation release action, so the derived public profile is
     * {@code DEAL_V1_2_INT32} and production SHARED routing is eligible
     * for the promoted capability × target pairs, foundation F1/F4).
     * is the only invocation constructor — the orchestrator never
     * constructs an invocation itself.
     */
    private static CompilerInvocation defaultInvocation() {
        return CompilerProfileProvider.resolve(
            ReleaseConfiguration.CURRENT_RELEASE_STATE,
            ReleaseConfiguration.releaseCapabilityRegistry());
    }

    /**
     * The release-owned compiler invocation of this compile: exactly one
     * purpose/profile/release state with the derived release-state hash
     * recorded (foundation F1) — resolved before checking/lowering and
     * recorded verbatim on every invocation, verbose or not.
     *
     */
    public CompilerInvocation invocation() {
        return invocation;
    }

    public CheckedProjectBuildResult checkedProject() {
        return checkedProjectBuild;
    }

    public RequirementManifestResult requirementManifests() {
        return requirementManifests;
    }

    public int semanticEmissionCount() {
        return semanticEmissionCount;
    }

    // =========================================================================
    // Public API
    // =========================================================================

    public boolean compile() throws IOException {
        // Transactional publication (whole-project-artifact-publication
        // D1-D6): one fresh staging stager per compile; the stage tree
        // and the per-root lock are created lazily at the first staged
        // write (an IR dump in phase 1/3, or a phase-4 artifact) and
        // the staged set is atomically swapped into the live output
        // root only when the compilation succeeded.
        stager = PublicationStager.forRoot(outputRoot);
        pendingStageFailure = null;
        boolean success;
        try {
            success = compileInternal();
            if (success) {
                try {
                    stager.publish();
                } catch (IOException publishFailure) {
                    // Publish-step I/O failure (D4): the stager already
                    // restored/cleaned per D2; report the pinned
                    // deterministic compiler I/O diagnostic on stderr
                    // with exit 1.
                    System.err.println("deal: cannot publish artifacts to '"
                        + outputRoot + "': " + publishFailure.getMessage());
                    success = false;
                }
            }
        } finally {
            if (!stager.published()) {
                // Any failure (or no publish) removes the stage tree and
                // releases the per-root lock: the prior live set is
                // untouched (an absent prior set stays absent) and no
                // staging residue survives.
                stager.discard();
            }
            stager = null;
        }
        if (pendingStageFailure != null) {
            // Staging write failure (D4): nothing published, diagnostics
            // report unchanged, pinned deterministic compiler I/O
            // diagnostic on stderr with exit 1.
            System.err.println("deal: cannot publish artifacts to '"
                + outputRoot + "': " + pendingStageFailure.getMessage());
            success = false;
        }

        // Structured output (D8): the document is written for every
        // compilation — successful or failed — when --diagnostics-json
        // was requested, without changing the exit code; it is a
        // caller-requested report outside the published set. A write
        // failure is a deterministic compiler I/O diagnostic on stderr
        // with exit 1; no raw path exception escapes (parent D11 I/O
        // discipline).
        if (diagnosticsJsonPath != null) {
            try {
                // D1 (deterministic-diagnostics): the document receives
                // the canonical report-ordered list; the internal
                // emission-order collection is unchanged.
                Files.writeString(diagnosticsJsonPath,
                    DiagnosticStructuredOutput.toJson(
                        DiagnosticOrder.canonical(diagnostics)));
            } catch (IOException e) {
                System.err.println("deal: cannot write diagnostics JSON to '"
                    + diagnosticsJsonPath + "': " + e.getMessage());
                return false;
            }
        }
        return success;
    }

    /** The compilation pipeline proper; see {@link #compile()}. */
    private boolean compileInternal() throws IOException {
        long startTime = System.currentTimeMillis();

        // The invocation was resolved at compile start (F1/F8); the
        // verbose report prints the recorded facts. The recording
        // location is the invocation's immutable releaseStateHash field —
        // present on every invocation, verbose or not.
        if (verbose) {
            System.out.println("Purpose: " + invocation.purpose());
            System.out.println("Semantic profile: " + invocation.semanticProfile());
            System.out.println("Release state: " + invocation.releaseState());
            System.out.println("Release-state hash: " + invocation.releaseStateHash());
        }

        log("Phase 0: Module discovery and parsing");
        discoverAndParse();
        if (hasErrors) { printDiagnostics(); return false; }

        log("Phase 1: Export signature extraction");
        extractSignatures();
        if (hasErrors) { printDiagnostics(); return false; }

        // DEAL v1.2 selected-entry rule: the entry module must export
        // non-async main() with signature (): null.  The backend invokes
        // main() from that module.
        validateEntryMain();

        log("Phase 2: Dependency graph and ordering");
        List<String> checkOrder = buildCheckOrder();
        if (checkOrder == null) { printDiagnostics(); return false; }
        this.dependencyOrder = List.copyOf(checkOrder);

        log("Phase 3: Type checking (" + checkOrder.size() + " modules)");
        typeCheckAll(checkOrder);
        if (hasErrors) { printDiagnostics(); return false; }

        // Foundation phase (F2/F8): after phase 3 succeeds, the builder
        // produces exactly one CheckedProjectInput and one
        // ProjectInterfaceIndex per compile in dependency order. The
        // builder is strictly read-only over the checked facts; its E6005
        // diagnostics fail the compile exactly like frontend errors.
        log("Phase 3.5: Checked project and interface index");
        buildCheckedProject(checkOrder);
        if (hasErrors) { printDiagnostics(); return false; }

        log("Phase 3.6: Semantic requirement manifests");
        computeRequirementManifests();
        if (hasErrors) { printDiagnostics(); return false; }

        // Phase 3.7 (route planning) is retired: the one production arm
        // consults no route plan, and no retained dispatch exists.

        log("Phase 3.8: Default planning and declaration default analysis");
        planDefaultClasses();
        if (hasErrors) { printDiagnostics(); return false; }

        log("Phase 3.85: Runtime dependency graph and plan publication");
        evaluateDependencyGraph();
        if (hasErrors) { printDiagnostics(); return false; }

        log("Phase 3.9: C FFI declaration validation and metadata");
        validateCffiDeclarations();
        if (hasErrors) { printDiagnostics(); return false; }

        log("Phase 4: Code generation");

        hostDeclarationSurface = produceHostDeclarationSurface();
        if (hasErrors) { printDiagnostics(); return false; }
        codegenAll();
        if (hasErrors) { printDiagnostics(); return false; }

        long elapsed = System.currentTimeMillis() - startTime;
        System.out.println("Compilation successful: " + modules.size() + " module(s)");
        if (verbose) {
            System.out.println("Total time: " + elapsed + "ms");
        }
        return true;
    }

    public List<CompilerDiagnostic> diagnostics() {
        return Collections.unmodifiableList(diagnostics);
    }

    // =========================================================================
    // Phase 0: Discovery and parsing
    // =========================================================================

    private void discoverAndParse() throws IOException {
        long phaseStart = System.currentTimeMillis();
        String entrySourcePath = entryFile.toString();
        Queue<String> pending = new ArrayDeque<>();
        pending.add(entrySourcePath);

        while (!pending.isEmpty()) {
            String sourcePath = pending.poll();
            if (modules.containsKey(sourcePath)) continue;

            // The location of this source: imports carry their published
            // T6 location through the pending queue; the entry file is
            // published through the resolver's entry-file seam.
            SourceModuleLocation location = locations.get(sourcePath);
            if (location == null) {
                SourceModuleResolver.ResolveResult entryResult =
                    sourceResolver.resolveEntryFile(sourcePath,
                        Span.synthetic(sourcePath));
                if (entryResult instanceof SourceModuleResolver.ResolveResult.Failure f) {
                    diagnostics.add(f.diagnostic());
                    hasErrors = true;
                    continue;
                }
                location = ((SourceModuleResolver.ResolveResult.Resolved)
                    entryResult).location();
                locations.put(sourcePath, location);
            }

            Path file = Path.of(sourcePath);
            if (!Files.exists(file)) {
                // Anchorless site (D5): the queue holds no import
                // declaration for a vanished file, so the diagnostic is
                // synthetic with a note naming the unresolved path.
                syntheticError(DiagnosticCode.E2003,
                    "Module not found: " + sourcePath, sourcePath,
                    "missing anchor: unresolved module path '"
                        + sourcePath + "'");
                continue;
            }

            long modStart = System.currentTimeMillis();
            boolean isDecl = sourcePath.endsWith(".d.deal");

            String source;
            try {
                source = Files.readString(file);
            } catch (IOException e) {
                // Anchorless site (D5): no import declaration span is
                // available for the unreadable queue file, so the
                // diagnostic is synthetic with a note naming the
                // unreadable path.
                syntheticError(DiagnosticCode.E2003,
                    "Cannot read module: " + sourcePath + " ("
                        + e.getMessage() + ")", sourcePath,
                    "missing anchor: unreadable module path '"
                        + sourcePath + "'");
                continue;
            }

            LexResult lex = new Lexer(source, sourcePath).tokenize();
            diagnostics.addAll(lex.diagnostics());
            if (hasLexErrors(lex)) {
                hasErrors = true;
                continue;
            }

            // Phase-0 parsing is profile-aware (signed-int32 foundation
            // I1): under DEAL_V1_2_INT32 the E1036 int32 gate and the
            // -2147483648 immediate-token special case apply; the legacy
            // parse contract is unchanged otherwise. The lexer's
            // directive events flow in through the events-carrying
            // constructor (fixed-name-directive-events D1), so
            // file-directive evaluation and declaration binding run
            // exactly as in production.
            Parser parser = new Parser(lex.tokens(), sourcePath,
                invocation.semanticProfile(), lex.directiveEvents());
            ParseResult parseResult = parser.parse();
            diagnostics.addAll(parseResult.diagnostics());
            if (parseResult.hasErrors()) {
                hasErrors = true;
            }

            // Post-parse v1.2 module shape validation: imports precede all
            // non-import declarations, top level holds only
            // import/function/class/export, imports and exports are not
            // nested statements, and implementation files have no bodyless
            // (external) function declarations.
            List<CompilerDiagnostic> shapeDiags =
                ModuleShapeValidator.validate(parseResult.program(), sourcePath, isDecl);
            diagnostics.addAll(shapeDiags);
            if (shapeDiags.stream().anyMatch(d -> "error".equals(d.severity()))) {
                hasErrors = true;
            }

            // The internal module name derives from the T5/T6
            // classification: the dotted root-relative path for a
            // ProjectModule, the externals raw specifier with '/' → '.',
            // the pinned stdlib module name, and the private
            // deploymentModuleId for an unclassified source (the lossy
            // computeModulePath dotted fallbacks are retired).
            ModuleInfo info = new ModuleInfo(sourcePath,
                modulePathFor(location), isDecl, location);
            info.rawAst = parseResult.program();
            info.parseResult = parseResult;
            modules.put(sourcePath, info);

            // T7 class-declaration gate (rule (d)): a class in a source
            // with no public module identity — an out-of-root relative
            // source, a rooted non-externals .d.deal, or a non-spec
            // .d.deal inside the std directory — is E2010 at the class
            // name span unconditionally at the declaration, before any
            // class/export/default/FFI metadata or artifact is
            // published. Run only for a cleanly parsed module so partial
            // ASTs never fabricate gates.
            if (!parseResult.hasErrors() && parseResult.program() != null) {
                for (StatementNode stmt : parseResult.program().statements()) {
                    ClassDeclaration cd = AstDeclarations.classDeclarationOf(stmt);
                    if (cd == null) {
                        continue;
                    }
                    ModuleIdentityAssembly.DeclarationResult gate =
                        identityAssembly.gateClassDeclaration(location,
                            cd.name(), classSpanOf(cd, sourcePath));
                    if (gate instanceof ModuleIdentityAssembly.DeclarationResult.Failure f) {
                        diagnostics.add(f.diagnostic());
                        hasErrors = true;
                    }
                }
            }

            long modElapsed = System.currentTimeMillis() - modStart;
            log("  Parsed: " + sourcePath + " (" + modElapsed + "ms)");

            // Process imports for discovery through the T6 resolver's
            // pinned rules (importer-relative first, externals authority,
            // configured roots then the pinned stdlib surface, the
            // 6-module filter, file-keyed E2009; no CWD fallback). A
            // failed resolution merges its E2003/E2009 at the import
            // span and fails the compile; a resolved source queues its
            // lexical path and publishes its location once.
            for (StatementNode stmt : parseResult.program().statements()) {
                if (stmt instanceof ImportDeclaration imp) {
                    SourceModuleResolver.ResolveResult result =
                        sourceResolver.resolve(info.sourcePath,
                            imp.modulePath(), imp.span());
                    if (result instanceof SourceModuleResolver.ResolveResult.Resolved r) {
                        String target = r.location().normalizedSourcePath();
                        locations.putIfAbsent(target, r.location());
                        if (!modules.containsKey(target)) {
                            pending.add(target);
                        }
                    } else {
                        diagnostics.add(
                            ((SourceModuleResolver.ResolveResult.Failure) result)
                                .diagnostic());
                        hasErrors = true;
                    }
                }
            }
        }

        long phaseElapsed = System.currentTimeMillis() - phaseStart;
        if (verbose) {
            System.out.println("  Phase 0 total: " + phaseElapsed + "ms");
        }
    }

    /**
     * The pinned class-name anchor of a class declaration: the
     * declaration's span (the convention of E4006's class diagnostics),
     * or the canonical synthetic span for a declaration without one.
     */
    private static Span classSpanOf(ClassDeclaration cd, String sourcePath) {
        return cd.span() != null ? cd.span() : Span.synthetic(sourcePath);
    }

    /**
     * The internal module name of a published source location, derived
     * from its T5/T6 classification (never the lossy
     * {@code computeModulePath} dotted path): {@code std.<module>} for a
     * {@code BuiltinModule} source (pinned module order), the externals
     * raw import specifier with {@code /} → {@code .} for an
     * {@code ExternalModule} source (the legacy externalsModulePaths
     * behavior), the dotted root-relative path (suffix stripped) for a
     * {@code ProjectModule} source, and the private
     * {@code deploymentModuleId} for an unclassified source.
     */
    private String modulePathFor(SourceModuleLocation location) {
        CanonicalModuleIdentity classification = location.moduleClassification();
        if (classification instanceof CanonicalModuleIdentity.BuiltinModule) {
            String stem = stdlibModuleStemOf(location);
            if (stem != null) {
                return "std." + stem;
            }
        } else if (classification instanceof CanonicalModuleIdentity.ExternalModule external) {
            return external.rawImportSpecifier().replace('/', '.');
        } else if (classification instanceof CanonicalModuleIdentity.ProjectModule project) {
            Path rootPath = Path.of(
                project.projectIdentity().normalizedRootPath());
            Path sourcePath = Path.of(location.normalizedSourcePath());
            String relative = rootPath.relativize(sourcePath).toString();
            return stripSourceSuffix(relative).replace('/', '.')
                .replace('\\', '.');
        }
        return location.deploymentModuleId();
    }

    /**
     * The stdlib module stem ({@code console}...{@code time}) of a
     * {@code BuiltinModule} source: derived from the canonical resolved
     * file's stem, restricted to the six pinned names (a partial
     * surface — e.g. a test-only surface holding a subset of the six
     * files — keeps the stem-to-name mapping exact; a positional list
     * index would misname partial surfaces).
     */
    private String stdlibModuleStemOf(SourceModuleLocation location) {
        Path canonicalPath = canonicalPathOf(location);
        if (canonicalPath == null || canonicalPath.getFileName() == null) {
            return null;
        }
        String name = canonicalPath.getFileName().toString();
        if (!name.endsWith(".d.deal")) {
            return null;
        }
        String stem = name.substring(0, name.length() - ".d.deal".length());
        return ModuleIdentityResolver.SPEC_STDLIB_MODULE_NAMES.contains(stem)
            ? stem : null;
    }

    /**
     * The lexical absolute path of a canonical resolved-source URI
     * (no filesystem access), or null for a non-{@code file:} URI.
     */
    private static Path canonicalPathOf(SourceModuleLocation location) {
        try {
            Path path = Path.of(URI.create(
                location.semanticModuleIdentity().canonicalResolvedSourceUri()));
            return path.normalize();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Strips the {@code .deal}/{@code .d.deal} source suffix of one
     * relative path text.
     */
    private static String stripSourceSuffix(String path) {
        if (path.endsWith(".d.deal")) {
            return path.substring(0, path.length() - ".d.deal".length());
        }
        if (path.endsWith(".deal")) {
            return path.substring(0, path.length() - ".deal".length());
        }
        return path;
    }

    private boolean hasLexErrors(LexResult lex) {
        return lex.diagnostics().stream().anyMatch(d -> "error".equals(d.severity()));
    }

    // =========================================================================
    // Phase 1: Signature extraction
    // =========================================================================

    private void extractSignatures() {
        long phaseStart = System.currentTimeMillis();

        for (ModuleInfo info : modules.values()) {
            long modStart = System.currentTimeMillis();

            // Build import alias → module path mapping for qualified type
            // resolution in export signatures (e.g., V.Vec → "cc_class".Vec).
            Map<String, String> importAliasMap = new HashMap<>();
            if (info.rawAst != null) {
                for (StatementNode stmt : info.rawAst.statements()) {
                    if (stmt instanceof ImportDeclaration imp) {
                        String resolvedSource = resolveImportPath(
                            imp.modulePath(), Path.of(info.sourcePath),
                            imp.span());
                        if (resolvedSource != null) {
                            ModuleInfo imported = modules.get(resolvedSource);
                            if (imported != null) {
                                importAliasMap.put(imp.alias(), imported.modulePath);
                            }
                        }
                    }
                }
            }

            ExportExtractor extractor = new ExportExtractor(info.modulePath,
                info.isDeclarationFile, modulePathClassification()::get);
            extractor.setImportModulePaths(importAliasMap);
            info.exports = extractor.extract(info.rawAst);
            List<CompilerDiagnostic> exportDiags = extractor.diagnostics();
            diagnostics.addAll(exportDiags);
            if (exportDiags.stream().anyMatch(
                    d -> "error".equals(d.severity()))) {
                hasErrors = true;
            }

            // T7 required-identity routing (D6 (a)/(b)/(c)): every class
            // declaration — exported or not — requires its public class
            // identity here. The LuaJIT backend emits a class tag (and
            // therefore the canonical descriptor projection) for every
            // class declaration in an emitted module, and an
            // unrepresentable identity must fail E2010 at the class name
            // span before any metadata or artifact is published — never
            // escape as a backend-dependent raw exception at descriptor
            // emission (the JVM backend compiles a non-exported class
            // without a tag, so gating every class makes the behavior
            // backend-independent). The assembled identity is registered
            // in the compilation's CanonicalClassIdentityIndex; an
            // unrepresentable identity (reserved first root component,
            // forbidden characters, ambiguous containment, an
            // unrepresentable externals specifier, a builtin class other
            // than Error) is E2010 at the class name span. The
            // unconditional identity-less rule (d) already fired in
            // phase 0, whose failure aborts the compile before this
            // phase runs, so an identity-less source is never re-gated
            // here.
            if (info.rawAst != null) {
                for (StatementNode stmt : info.rawAst.statements()) {
                    ClassDeclaration cd = AstDeclarations.classDeclarationOf(stmt);
                    if (cd == null) {
                        continue;
                    }
                    ModuleIdentityAssembly.ClassIdentityResult required =
                        identityAssembly.requireClassIdentity(info.location,
                            cd.name(), classSpanOf(cd, info.sourcePath));
                    if (required instanceof ModuleIdentityAssembly.ClassIdentityResult.Failure f) {
                        diagnostics.add(f.diagnostic());
                        hasErrors = true;
                    }
                }
            }

            // IR dump for declaration files
            if (dumpIr && info.isDeclarationFile && info.rawAst != null) {
                try {
                    String irText = IrDumper.dump(info.rawAst, info.symbolTable,
                        info.modulePath, buildCanonicalIdentitySurface());
                    if (!irText.isEmpty()) {
                        writeIrDump(info.modulePath, irText);
                    }
                } catch (Exception e) {
                    // Anchorless site (D5/D6): an IR-dump failure has no
                    // source construct anchor; the note names the failed
                    // module path.
                    diagnostics.add(e6001IrDumpFailure(info.sourcePath,
                        e.getMessage()));
                    hasErrors = true;
                }
            }

            long modElapsed = System.currentTimeMillis() - modStart;
            log("  Signatures extracted: " + info.sourcePath + " (" + modElapsed + "ms)");
        }

        long phaseElapsed = System.currentTimeMillis() - phaseStart;
        if (verbose) {
            System.out.println("  Phase 1 total: " + phaseElapsed + "ms");
        }
    }

    private void validateEntryMain() {
        ModuleInfo entry = modules.get(entryFile.toString());
        if (entry == null) return; // discovery already reported E2003

        String file = entry.sourcePath;
        // E2012/E2011 anchor at the entry program span (D5): SOURCE-exact
        // at the program start via the T2 program-span obligation,
        // including the empty/whitespace-only entry case
        // (file,1,1,1,1,0,0,0,SOURCE).
        Span programSpan = entry.rawAst != null ? entry.rawAst.span() : null;

        Type mainType = entry.exports != null
            ? entry.exports.get("main") : null;
        if (mainType == null) {
            if (programSpan != null) {
                error(DiagnosticCode.E2012,
                    "Entry module must export 'main' with non-async signature '(): null'",
                    programSpan);
            } else {
                syntheticError(DiagnosticCode.E2012,
                    "Entry module must export 'main' with non-async signature '(): null'",
                    file,
                    "missing anchor: entry program span for module '" + file + "'");
            }
            return;
        }

        boolean validMain = mainType instanceof Type.Func f
            && f.paramTypes().isEmpty()
            && !f.isAsync()
            && f.returnType() instanceof Type.Null;
        if (!validMain) {
            if (programSpan != null) {
                error(DiagnosticCode.E2011,
                    "Entry module 'main' must have non-async signature '(): null'",
                    programSpan);
            } else {
                syntheticError(DiagnosticCode.E2011,
                    "Entry module 'main' must have non-async signature '(): null'",
                    file,
                    "missing anchor: entry program span for module '" + file + "'");
            }
        }
    }

    // =========================================================================
    // Phase 2: Dependency graph and topological ordering
    // ... (unchanged)
    // =========================================================================

    private List<String> buildCheckOrder() {
        Map<String, Set<String>> deps = new LinkedHashMap<>();
        for (Map.Entry<String, ModuleInfo> entry : modules.entrySet()) {
            String sourcePath = entry.getKey();
            ModuleInfo info = entry.getValue();
            Set<String> imports = new LinkedHashSet<>();
            for (StatementNode stmt : info.rawAst.statements()) {
                if (stmt instanceof ImportDeclaration imp) {
                    String resolved = resolveImportPath(imp.modulePath(),
                        Path.of(sourcePath), imp.span());
                    if (resolved != null && modules.containsKey(resolved)) {
                        imports.add(resolved);
                    }
                }
            }
            deps.put(sourcePath, imports);
        }
        return ModuleDependencyGraph.initializationOrder(
            new ArrayList<>(modules.keySet()), deps);
    }

    public static CompilerDiagnostic e2005Diagnostic(List<String> cycle,
            Map<String, Map<String, Span>> edgeSpans,
            Map<String, Span> programSpans, String message) {
        Set<String> cycleSet = new LinkedHashSet<>(cycle);
        // The chain applies to the first cycle module that targets
        // another cycle member: its import declaration span, falling back
        // to that module's program span. Modules without a cycle-member
        // edge are skipped entirely; when no module has a usable anchor,
        // the result is the canonical synthetic shape plus the
        // cycle-edge-naming note.
        Span anchor = null;
        for (String sourcePath : cycle) {
            boolean targetsCycleMember = false;
            Span edgeSpan = null;
            Map<String, Span> edges = edgeSpans.get(sourcePath);
            if (edges != null) {
                for (Map.Entry<String, Span> edge : edges.entrySet()) {
                    if (cycleSet.contains(edge.getKey())) {
                        targetsCycleMember = true;
                        if (edge.getValue() != null) {
                            edgeSpan = edge.getValue();
                            break;
                        }
                    }
                }
            }
            if (!targetsCycleMember) {
                continue;
            }
            anchor = edgeSpan != null ? edgeSpan : programSpans.get(sourcePath);
            break;
        }
        if (anchor != null) {
            return CompilerDiagnostic.error(DiagnosticCode.E2005, message,
                anchor);
        }
        String file = cycle.isEmpty() ? "" : cycle.get(0);
        return CompilerDiagnostic.syntheticError(DiagnosticCode.E2005, message,
            file,
            "missing anchor: import declaration closing the module cycle "
                + String.join(" -> ", cycle));
    }

    /**
     * Builds the E6001 IR-dump-failure diagnostic (public static so the
     * synthetic contract is directly pinnable): an IR-dump failure has no
     * source construct anchor, so the diagnostic carries the canonical
     * synthetic shape {@code (file,1,1,1,1,0,0,0,SYNTHETIC)} plus an
     * anchor note naming the failed module path (D5/D6).
     */
    public static CompilerDiagnostic e6001IrDumpFailure(String sourcePath,
                                                         String failureMessage) {
        return CompilerDiagnostic.syntheticError(DiagnosticCode.E6001,
            "IR dump failed for " + sourcePath + ": " + failureMessage,
            sourcePath,
            "missing anchor: IR dump path for module '" + sourcePath + "'");
    }

    /**
     * Runs the checked-project foundation after phase 3: packages the
     * orchestrator's module facts in {@code buildCheckOrder} (source
     * paths, dotted module paths, ASTs, Phase-3-corrected export maps,
     * checked facts, the spec-stdlib classification, and the resolved
     * imports) and hands them to {@link CheckedProjectBuilder}. Builder
     * E6005 diagnostics merge into {@link #diagnostics()} and fail the
     * compile; a failed build leaves {@link #checkedProject()} null.
     */
    private void buildCheckedProject(List<String> checkOrder) {
        List<ModuleFact> facts = new ArrayList<>(checkOrder.size());
        for (String sourcePath : checkOrder) {
            ModuleInfo info = modules.get(sourcePath);
            if (info == null) {
                continue; // defensive: buildCheckOrder names only discovered modules
            }
            facts.add(toModuleFact(info));
        }
        ModuleInfo entryInfo = modules.get(entryFile.toString());
        if (entryInfo == null) {
            return; // defensive: phase 3 gates the entry module earlier
        }
        CheckedProjectBuildResult result = CheckedProjectBuilder.build(
            invocation, new ModuleId(entryInfo.modulePath), facts);
        this.checkedProjectBuild = result;
        diagnostics.addAll(result.diagnostics());
        if (result.hasErrors()) {
            hasErrors = true;
            log("  Checked project build failed: " + result.diagnostics());
        }
    }

    private void computeRequirementManifests() {
        CheckedProjectBuildResult checked = this.checkedProjectBuild;
        if (checked == null || checked.hasErrors()) {
            return; // a pre-manifest phase failure already gates the compile
        }
        RequirementManifestResult result = LoweringSupport.computeManifests(
            invocation, checked.input(), checked.index());
        this.requirementManifests = result;
        diagnostics.addAll(result.diagnostics());
        if (result.hasErrors()) {
            hasErrors = true;
            log("  Requirement manifest computation failed: " + result.diagnostics());
        }
    }

    /**
     * The read-only view of the validated extern-C metadata: dotted
     * module path &rarr; generated module inputs (descriptor, cdef
     * bundle, retained plans, forward bindings). Populated only for the
     * LuaJIT backend after successful validation; empty for JVM/JS and
     * on any validation failure.
     *
     */
    public Map<String, FfiGeneratedModule> ffiGenerations() {
        return Collections.unmodifiableMap(ffiGenerations);
    }

    private void planDefaultClasses() {
        // The planning epics require public class identity for every
        // plan-bearing class and the canonical descriptor encoder
        // resolves class atoms through the assembly index: register the
        // intrinsic Error projection and every class declaration of
        // every implementation module (phase 1 registered the
        // top-level classes; nested classes join idempotently here)
        // before any entry encodes a descriptor.
        identityAssembly.intrinsicErrorIdentity();
        for (ModuleInfo info : modules.values()) {
            if (info.isDeclarationFile || info.checkResult == null) {
                continue;
            }
            for (Map.Entry<ClassDeclaration, SymbolTable> entry
                    : info.checkResult.classScopes().entrySet()) {
                ModuleIdentityAssembly.ClassIdentityResult required =
                    identityAssembly.requireClassIdentity(info.location,
                        entry.getKey().name(), entry.getKey().span());
                if (required
                        instanceof ModuleIdentityAssembly.ClassIdentityResult
                            .Failure f) {
                    diagnostics.add(f.diagnostic());
                    hasErrors = true;
                }
            }
        }
        if (hasErrors) {
            return;
        }

        CanonicalRuntimeTypeDescriptor descriptors =
            new CanonicalRuntimeTypeDescriptor(identityAssembly.index());
        Map<String, CanonicalModuleIdentity> classification =
            modulePathClassification();
        ModuleResolverImpl moduleResolver = new ModuleResolverImpl(modules,
            diagnostics);

        for (String sourcePath : dependencyOrder) {
            ModuleInfo info = modules.get(sourcePath);
            if (info == null || info.rawAst == null
                    || info.location == null) {
                continue;
            }
            Map<String, DefaultPlanImport> imports =
                defaultPlanImportsOf(info);
            if (info.isDeclarationFile) {
                if (!isExternCModuleInfo(info)) {
                    // Host-declared classes are exempt from the plan
                    // gates and produce no plan (the host supplies
                    // <C>_defaults at load).
                    continue;
                }
                DefaultDeclarationModuleInput input =
                    new DefaultDeclarationModuleInput(
                        info.sourcePath, info.modulePath, info.rawAst,
                        info.location, imports, descriptors,
                        moduleResolver, classification);
                DeclarationSemanticAnalyzer.Result result =
                    DeclarationSemanticAnalyzer.analyze(input,
                        identityAssembly);
                diagnostics.addAll(result.diagnostics());
                if (result.hasErrors()) {
                    hasErrors = true;
                } else {
                    plannedDefaultClasses.put(info.sourcePath,
                        result.plannedClasses());
                }
                continue;
            }
            if (info.checkResult == null || info.nameResolver == null) {
                continue; // defensive: checked facts always exist here
            }
            DefaultPlanModuleInput input = new DefaultPlanModuleInput(
                info.sourcePath, info.modulePath, info.rawAst,
                info.location, info.checkResult, info.nameResolver,
                imports, descriptors, classification);
            DefaultSemanticPlanner.Result result =
                DefaultSemanticPlanner.plan(input, identityAssembly);
            diagnostics.addAll(result.diagnostics());
            if (result.hasErrors()) {
                hasErrors = true;
            } else {
                plannedDefaultClasses.put(info.sourcePath,
                    result.plannedClasses());
            }
        }
    }

    /**
     * The import surface of one module: import alias &rarr; the
     * provider's read-only planning snapshot (source path, dotted
     * module path, parsed program, resolved location, extracted export
     * map, symbol table when available, and the declaration/extern-C
     * flags), insertion-ordered by the module's import declarations.
     */
    private Map<String, DefaultPlanImport> defaultPlanImportsOf(
            ModuleInfo info) {
        Map<String, DefaultPlanImport> imports = new LinkedHashMap<>();
        if (info.rawAst == null) {
            return imports;
        }
        for (StatementNode stmt : info.rawAst.statements()) {
            if (!(stmt instanceof ImportDeclaration imp)) {
                continue;
            }
            String resolvedSource = resolveImportPath(imp.modulePath(),
                Path.of(info.sourcePath), imp.span());
            if (resolvedSource == null) {
                continue; // the E2003/E2009 was already reported
            }
            ModuleInfo imported = modules.get(resolvedSource);
            if (imported == null || imported.rawAst == null
                    || imported.location == null) {
                continue;
            }
            imports.put(imp.alias(), new DefaultPlanImport(
                imported.sourcePath, imported.modulePath, imported.rawAst,
                imported.location,
                imported.exports != null ? imported.exports : Map.of(),
                imported.symbolTable, imported.isDeclarationFile,
                isExternCModuleInfo(imported)));
        }
        return imports;
    }

    /**
     * The planning result of the current compile: module source path
     * &rarr; the planned classes with their provisional occurrence
     * data, in planning order. Read-only; empty before the planning
     * phase runs, when a preceding phase failed, when planning
     * produced error-level diagnostics, or when the dependency graph
     * rejected the compilation (E2005 publishes no plan — the
     * provisional plans are discarded).
     */
    public Map<String, List<PlannedDefaultClass>> plannedDefaultClasses() {
        return Collections.unmodifiableMap(plannedDefaultClasses);
    }

    public Map<String, List<PlannedDefaultClass>> completedDefaultPlans() {
        return Collections.unmodifiableMap(completedDefaultPlans);
    }

    public Map<String, JsBackend.JsCodegenResult> jsGeneratedResults() {
        return Collections.unmodifiableMap(jsGeneratedResults);
    }

    public Map<String, List<PlannedDefaultClass>>
            completedPlansByModulePath() {
        Map<String, List<PlannedDefaultClass>> byModulePath =
            new LinkedHashMap<>();
        for (ModuleInfo info : modules.values()) {
            List<PlannedDefaultClass> plans =
                completedDefaultPlans.get(info.sourcePath);
            if (plans != null && !plans.isEmpty()) {
                byModulePath.put(info.modulePath, plans);
            }
        }
        return Collections.unmodifiableMap(byModulePath);
    }

    public List<RuntimeImportDependency> runtimeDependencies() {
        return List.copyOf(runtimeDependencies);
    }

    public List<String> dependencyOrder() {
        return List.copyOf(dependencyOrder);
    }

    public Map<String, DefaultSerializerModuleInput>
            defaultSerializerModuleInputs() {
        Map<String, DefaultSerializerModuleInput> inputs =
            new LinkedHashMap<>();
        if (identityAssembly == null) {
            return Collections.unmodifiableMap(inputs);
        }
        CanonicalRuntimeTypeDescriptor descriptors =
            new CanonicalRuntimeTypeDescriptor(identityAssembly.index());
        Map<String, CanonicalModuleIdentity> classification =
            modulePathClassification();
        ModuleResolverImpl moduleResolver = new ModuleResolverImpl(modules,
            diagnostics);
        for (ModuleInfo info : modules.values()) {
            if (info.rawAst == null || info.location == null) {
                continue;
            }
            // Defensive: the serializer is only invoked after a
            // successful planning phase, so modules whose checked
            // facts never completed (a failed compile) stay outside
            // the seam — the serializer input contract requires the
            // exact declaration/implementation fact split.
            if (!info.isDeclarationFile
                    && (info.checkResult == null
                        || info.nameResolver == null)) {
                continue;
            }
            inputs.put(info.sourcePath, new DefaultSerializerModuleInput(
                info.sourcePath, info.modulePath, info.rawAst,
                info.location,
                info.isDeclarationFile ? null : info.checkResult,
                info.isDeclarationFile ? null : info.nameResolver,
                defaultPlanImportsOf(info),
                info.exports != null ? info.exports : Map.of(),
                descriptors, classification,
                info.isDeclarationFile ? moduleResolver : null,
                info.isDeclarationFile, isExternCModuleInfo(info)));
        }
        return Collections.unmodifiableMap(inputs);
    }

    private void evaluateDependencyGraph() {
        Map<String, ModuleDependencyGraph.ModuleInput> inputs =
            new LinkedHashMap<>();
        for (Map.Entry<String, ModuleInfo> entry : modules.entrySet()) {
            String sourcePath = entry.getKey();
            ModuleInfo info = entry.getValue();
            if (info.rawAst == null || info.location == null) {
                continue;
            }
            if (!info.isDeclarationFile
                    && (info.checkResult == null
                        || info.nameResolver == null)) {
                continue;
            }
            inputs.put(sourcePath, new ModuleDependencyGraph.ModuleInput(
                info.sourcePath, info.modulePath, info.rawAst,
                info.location,
                info.isDeclarationFile ? null : info.checkResult,
                info.isDeclarationFile ? null : info.nameResolver,
                defaultPlanImportsOf(info), modulePathClassification(),
                info.isDeclarationFile));
        }
        // The E2005 anchor chain inputs: each module's import-edge
        // spans and program span.
        Map<String, Map<String, Span>> edgeSpans = new LinkedHashMap<>();
        Map<String, Span> programSpans = new LinkedHashMap<>();
        for (Map.Entry<String, ModuleInfo> entry : modules.entrySet()) {
            String sourcePath = entry.getKey();
            ModuleInfo info = entry.getValue();
            Map<String, Span> spans = new LinkedHashMap<>();
            if (info.rawAst != null) {
                for (StatementNode stmt : info.rawAst.statements()) {
                    if (stmt instanceof ImportDeclaration imp) {
                        String resolved = resolveImportPath(
                            imp.modulePath(), Path.of(sourcePath),
                            imp.span());
                        if (resolved != null
                                && modules.containsKey(resolved)) {
                            spans.put(resolved, imp.span());
                        }
                    }
                }
                if (info.rawAst.span() != null) {
                    programSpans.put(sourcePath, info.rawAst.span());
                }
            }
            edgeSpans.put(sourcePath, spans);
        }

        ModuleDependencyGraph.DigestFreeResult free =
            ModuleDependencyGraph.digestFreePass(inputs,
                plannedDefaultClasses, edgeSpans, programSpans);
        if (free.failed()) {
            // E2005: a runtime SCC — publish no plan, no FFI metadata,
            // and no artifact; the provisional plans are discarded.
            diagnostics.addAll(free.diagnostics());
            hasErrors = true;
            plannedDefaultClasses.clear();
            runtimeDependencies = List.of();
            return;
        }
        // Acyclic: request the provider digests now — the serializer
        // completes every default's runtimeResources and additionally
        // demands the ordinary occurrences' provider digests.
        DefaultSemanticSerializer.Result serialized =
            DefaultSemanticSerializer.serialize(
                defaultSerializerModuleInputs(), plannedDefaultClasses,
                free.ordinaryOccurrences());
        diagnostics.addAll(serialized.diagnostics());
        if (serialized.hasErrors()) {
            hasErrors = true;
            plannedDefaultClasses.clear();
            runtimeDependencies = List.of();
            return;
        }
        ModuleDependencyGraph.PublishedGraph published =
            ModuleDependencyGraph.finalizeGraph(free,
                plannedDefaultClasses, serialized.serializedClasses(),
                serialized.providerDigests());
        completedDefaultPlans.putAll(published.completedPlans());
        runtimeDependencies = published.runtimeDependencies();
        dependencyOrder = free.initializationOrder();
        log("  Runtime graph: " + runtimeDependencies.size()
            + " dependency edge(s); initialization order "
            + dependencyOrder.size() + " module(s)");
    }

    private void validateCffiDeclarations() {
        if (backend == Backend.JS) {
            return;
        }
        // The validator keys graph-order lookups by dotted module path
        // (the import-surface key space), while {@code dependencyOrder}
        // is the phase-2 check order of absolute source paths: translate
        // the order into the validator's key space so every imported
        // reference receives its real dependency-order position instead
        // of a degenerate constant.
        List<String> ffiDependencyOrder = dependencyOrder.stream()
            .map(modules::get)
            .filter(Objects::nonNull)
            .map(info -> info.modulePath)
            .toList();
        CanonicalRuntimeTypeDescriptor descriptorEncoder =
            new CanonicalRuntimeTypeDescriptor(identityAssembly.index());
        for (ModuleInfo info : modules.values()) {
            if (!isExternCModuleInfo(info)) {
                continue;
            }
            String canonicalExternalIdentity = canonicalExternalIdentityOf(info);
            if (canonicalExternalIdentity == null) {

                continue;
            }
            NativeLibraryRef nativeLibrary = nativeLibraryOf(info);
            FfiDeclarationValidator.Result result =
                FfiDeclarationValidator.validate(
                    info.rawAst, info.modulePath, info.location,
                    canonicalExternalIdentity, identityAssembly,
                    descriptorEncoder,
                    nativeLibrary == null ? null
                        : nativeLibrary.kind().name(),
                    nativeLibrary == null ? null
                        : nativeLibrary.loaderText(),
                    ffiDependencyOrder, importTargetsOf(info));
            diagnostics.addAll(result.diagnostics());
            if (result.hasErrors()) {
                hasErrors = true;
                continue;
            }
            if (backend == Backend.JVM) {
                // Backend capability rejection (D8): after semantic/
                // declaration validation, before any artifact write.
                deal.diagnostics.DiagnosticRange externCRange =
                    info.rawAst.fileDirectives().externCRange();
                diagnostics.add(CompilerDiagnostic.error(
                    DiagnosticCode.E6006,
                    "JVM backend: C FFI (@extern-c) declarations are not"
                        + " supported (FFI_UNSUPPORTED_BACKEND)",
                    externCRange != null ? externCRange
                        : deal.diagnostics.DiagnosticRange.synthetic(
                            info.sourcePath)));
                hasErrors = true;
                continue;
            }
            // LuaJIT: the validated descriptor is accepted for later
            // runtime loading; the generator produces the loader inputs
            // (cells before any plan content; imported references in
            // graph order; no evaluator ever invoked).
            LuaFfiBindingGenerator.GeneratedBindings generated =
                LuaFfiBindingGenerator.generate(result.descriptor(),
                    result.importedFunctions(), result.importedClassPlans());
            ffiGenerations.put(info.modulePath, new FfiGeneratedModule(
                info.modulePath, result.descriptor(),
                generated.cdefBundle(), generated.plans(),
                generated.bindings()));
        }
    }

    /** True for a parsed extern-C declaration module. */
    private static boolean isExternCModuleInfo(ModuleInfo info) {
        return info.isDeclarationFile && info.rawAst != null
            && info.rawAst.fileDirectives().externC();
    }

    /**
     * The canonical external module identity text of an externals-listed
     * module ({@code @$external/&lt;rawImportSpecifier&gt;}), or null
     * when the module has no external classification.
     */
    private String canonicalExternalIdentityOf(ModuleInfo info) {
        CanonicalModuleIdentity identity = classifyModuleIdentity(info);
        if (identity instanceof CanonicalModuleIdentity.ExternalModule ext) {
            return "@$external/" + ext.rawImportSpecifier();
        }
        return null;
    }

    /** The externals entry's classified native library, or null. */
    private NativeLibraryRef nativeLibraryOf(ModuleInfo info) {
        CanonicalModuleIdentity identity = classifyModuleIdentity(info);
        if (identity instanceof CanonicalModuleIdentity.ExternalModule ext) {
            ExternalEntry entry = context.externals().get(
                ext.rawImportSpecifier());
            return entry == null ? null : entry.nativeLibrary();
        }
        return null;
    }

    /**
     * The resolved import surface of one module: import alias &rarr;
     * the provider's dotted module path and extracted export map (the
     * extern-C metadata phase resolves default-expression provider
     * references through it).
     */
    private Map<String, FfiDeclarationValidator.ImportTarget> importTargetsOf(
            ModuleInfo info) {
        Map<String, FfiDeclarationValidator.ImportTarget> targets =
            new LinkedHashMap<>();
        if (info.rawAst == null) {
            return targets;
        }
        for (StatementNode stmt : info.rawAst.statements()) {
            if (!(stmt instanceof ImportDeclaration imp)) {
                continue;
            }
            String resolvedSource = resolveImportPath(imp.modulePath(),
                Path.of(info.sourcePath), imp.span());
            if (resolvedSource == null) {
                continue;
            }
            ModuleInfo imported = modules.get(resolvedSource);
            if (imported == null) {
                continue;
            }
            targets.put(imp.alias(), new FfiDeclarationValidator.ImportTarget(
                imported.modulePath,
                imported.exports != null ? imported.exports : Map.of()));
        }
        return targets;
    }

    /**
     * Packages one orchestrator module's read-only facts for the builder:
     * the resolved imports in source order re-run through the same import
     * resolution the discovery/typing phases used ({@code resolveImportPath}
     * plus the externals maps — the try-variant emits no diagnostics; an
     * unresolvable import would have failed the compile with E2003 before
     * this phase).
     */
    private ModuleFact toModuleFact(ModuleInfo info) {
        List<ModuleFact.ImportFact> imports = new ArrayList<>();
        if (info.rawAst != null) {
            for (StatementNode stmt : info.rawAst.statements()) {
                if (stmt instanceof ImportDeclaration imp) {
                    // The same T6 resolution the discovery/typing phases
                    // used, re-run without diagnostics (an unresolvable
                    // import would have failed the compile with
                    // E2003/E2009 before this phase).
                    SourceModuleResolver.ResolveResult result =
                        sourceResolver.resolve(info.sourcePath,
                            imp.modulePath(), imp.span());
                    if (result instanceof SourceModuleResolver.ResolveResult.Resolved r) {
                        imports.add(new ModuleFact.ImportFact(imp.alias(),
                            imp.modulePath(),
                            r.location().normalizedSourcePath()));
                    }
                }
            }
        }
        return new ModuleFact(info.sourcePath, new ModuleId(info.modulePath),
            info.isDeclarationFile, isSpecStdlibModuleInfo(info), info.rawAst,
            info.exports != null ? info.exports : Map.of(),
            info.symbolTable, info.checkResult, imports);
    }

    // =========================================================================
    // Phase 3: Type checking
    // =========================================================================

    private void typeCheckAll(List<String> order) {
        long phaseStart = System.currentTimeMillis();
        ModuleResolverImpl resolver = new ModuleResolverImpl(modules, diagnostics);

        for (String sourcePath : order) {
            ModuleInfo info = modules.get(sourcePath);
            if (info.isDeclarationFile) continue;

            long modStart = System.currentTimeMillis();

            NameResolver nr = new NameResolver(info.modulePath, resolver,
                new HashSet<>(), modulePathClassification()::get);
            info.nameResolver = nr;
            SymbolTable symTable = nr.resolve(info.rawAst);
            info.symbolTable = symTable;

            // F1 fix: Rebuild exports with correctly resolved types from name
            // resolution.  The ExportExtractor in Phase 1 uses import aliases
            // for qualified types (e.g., "V" instead of "cc_class"), which are
            // local to the exporting module and meaningless to downstream
            // importers.  After name resolution, we know the correct module
            // paths and can produce accurate export types.
            Map<String, Type> correctedExports = new LinkedHashMap<>();
            for (StatementNode stmt : info.rawAst.statements()) {
                if (stmt instanceof ExportDeclaration exp) {
                    String exportName = null;
                    if (exp.declaration() instanceof FunctionDeclaration fd) {
                        exportName = fd.name();
                    } else if (exp.declaration() instanceof ClassDeclaration cd) {
                        exportName = cd.name();
                    }
                    if (exportName != null) {
                        Symbol sym = symTable.resolve(exportName);
                        if (sym != null) {
                            if (sym instanceof Symbol.FunctionSymbol fs) {
                                correctedExports.put(exportName, fs.funcType());
                            } else if (sym instanceof Symbol.ClassSymbol cs) {
                                correctedExports.put(exportName,
                                    Types.classType(cs.name(), cs.identity()));
                            }
                        }
                    }
                }
            }

            // D6: Append synthetic C$fromJson and C$toJson exports for @jsonable
            // classes.  These are added after the symbol-table-based correction
            // so that synthetic entries are present in the final export map
            // consumed by downstream modules.
            for (StatementNode stmt : info.rawAst.statements()) {
                if (stmt instanceof ExportDeclaration exp
                        && exp.declaration() instanceof ClassDeclaration cd
                        && cd.isJsonable()) {
                    Symbol clsSym = symTable.resolve(cd.name());
                    if (clsSym instanceof Symbol.ClassSymbol cs) {
                        Type clsType = Types.classType(cs.name(), cs.identity());

                        // C$fromJson: (string) -> C | null
                        Type.Func fromJsonType = new Type.Func(
                            List.of(Type.String.INSTANCE),
                            Types.nullable(clsType));
                        correctedExports.put(cd.name() + "$fromJson", fromJsonType);

                        // C$toJson: (C) -> string
                        Type.Func toJsonType = new Type.Func(
                            List.of(clsType),
                            Type.String.INSTANCE);
                        correctedExports.put(cd.name() + "$toJson", toJsonType);
                    }
                }
            }

            info.exports = correctedExports;

            List<CompilerDiagnostic> nameDiags = nr.diagnostics();
            diagnostics.addAll(nameDiags);
            if (hasNameErrors(nameDiags)) {
                hasErrors = true;
                long modElapsed = System.currentTimeMillis() - modStart;
                log("  Checked: " + sourcePath + " (name resolution error, " + modElapsed + "ms)");
                continue;
            }

            CheckResult result = TypeChecker.check(info.modulePath, symTable,
                nr, info.rawAst);
            info.checkResult = result;
            diagnostics.addAll(result.diagnostics());
            if (result.hasErrors()) {
                hasErrors = true;
            }

            // IR dump for full modules (after type checking, before codegen)
            if (dumpIr && !result.hasErrors() && info.rawAst != null) {
                try {
                    String irText = IrDumper.dump(info.rawAst, result,
                        info.modulePath, buildCanonicalIdentitySurface());
                    if (!irText.isEmpty()) {
                        writeIrDump(info.modulePath, irText);
                    }
                } catch (Exception e) {
                    // Anchorless site (D5/D6): the note names the failed
                    // module path.
                    diagnostics.add(e6001IrDumpFailure(info.sourcePath,
                        e.getMessage()));
                    hasErrors = true;
                }
            }

            long modElapsed = System.currentTimeMillis() - modStart;
            log("  Checked: " + sourcePath + " (" + modElapsed + "ms)");
        }

        long phaseElapsed = System.currentTimeMillis() - phaseStart;
        if (verbose) {
            System.out.println("  Phase 3 total: " + phaseElapsed + "ms");
        }
    }

    private boolean hasNameErrors(List<CompilerDiagnostic> diags) {
        return diags.stream().anyMatch(d -> "error".equals(d.severity()));
    }

    // =========================================================================
    // Phase 4: Code generation
    // =========================================================================

    private void codegenAll() throws IOException {
        long phaseStart = System.currentTimeMillis();
        try {
            if (backend == Backend.JS) {
                codegenAllJs();
            } else {
                // The one production arm: a LuaJIT/JVM compile of any
                // recorded invocation (including a harness metadata
                // record) runs the release-owned project emission. The
                // invocation record selects no arm; only the lowering's
                // profile guard rejects a legacy profile. Its failure is
                // the one E6005 LOWER_LEGACY_PROFILE_REJECTED.
                emitProductionProject();
            }
        } catch (IOException stagingFailure) {
            // A staging write failure (D4): nothing is published, the
            // stage tree is removed by compile(), the diagnostics report
            // is unchanged, and the pinned deterministic compiler I/O
            // diagnostic is printed with exit 1.
            stageFailure(stagingFailure);
        }

        long phaseElapsed = System.currentTimeMillis() - phaseStart;
        if (verbose) {
            System.out.println("  Phase 4 total: " + phaseElapsed + "ms");
        }
    }

    /**
     * The identity-based production-invocation predicate (P4; design
     * source {@code production-project-emission-and-atomic-cutover}):
     * true exactly for the record
     * {@code CompilerProfileProvider.resolve(ReleaseConfiguration
     * .CURRENT_RELEASE_STATE, ReleaseConfiguration
     * .releaseCapabilityRegistry())} — the record {@code deal.Main}
     * resolves and {@link #defaultInvocation()} returns. The predicate is
     * a read-only record-identity fact consumed by the acceptance audit;
     * the compile path never branches on it, because phase 4 always runs
     * the one production arm.
     *
     */
    public static boolean isProductionInvocation(CompilerInvocation invocation) {
        Objects.requireNonNull(invocation, "invocation must not be null");
        return invocation.equals(defaultInvocation());
    }

    /**
     * The one phase-4 production entry (P5/P6): exactly one
     * {@link ProductionProjectEmission#run} over the compile's declared
     * inputs — the C9 source-map warning, the one project lowering, the
     * pre-emission closure guard, the one production emission, and the
     * one staged project artifact plus the unchanged LuaJIT deployment
     * copies — then the one project emission record
     * ({@link #semanticEmissionCount}). A lowering, guard, or emission
     * failure merges its first E6005 diagnostic and stages nothing: the
     * unchanged publication transaction then preserves the previous
     * artifact set byte-for-byte.
     */
    private void emitProductionProject() throws IOException {
        CheckedProjectBuildResult checked = checkedProjectBuild;
        if (checked == null || checked.hasErrors() || checked.input() == null
                || checked.index() == null) {
            throw new IllegalStateException("the production arm runs after a"
                + " successful checked-project build (producer defect)");
        }
        if (requirementManifests == null || requirementManifests.hasErrors()
                || requirementManifests.manifests() == null) {
            throw new IllegalStateException("the production arm runs after a"
                + " successful manifest phase (producer defect)");
        }
        if (hostDeclarationSurface == null) {
            throw new IllegalStateException("the production arm runs after a"
                + " successful declaration surface (producer defect)");
        }
        ProductionProjectEmission.Result result = ProductionProjectEmission.run(
            invocation, checked.input(), checked.index(),
            requirementManifests.manifests(), hostDeclarationSurface,
            declarationModuleIdentities(), externCGeneratedModules(),
            context.manifestDirectory(),
            builtinErrorDeclaration(checked.input()),
            List.of(IntrinsicKind.INT_CONVERT, IntrinsicKind.NUMBER_CONVERT),
            Set.of(), backend, sourceMapExplicit, distributionHome, stager);
        if (result.emitted()) {
            semanticEmissionCount++;
            log("  Generated (production project emission): "
                + outputRoot.resolve(result.artifactRelativePath()));
        } else {
            diagnostics.addAll(result.diagnostics());
            hasErrors = true;
            log("  Production project emission failed: "
                + result.diagnostics());
        }
    }

    /**
     * The compilation's declaration-module identity map: the public
     * canonical identity of every declaration module the declaration
     * surface covers, keyed by module identity (the one project
     * lowering's registration-seed and class-identity input). The
     * identity is the compilation's single module-path classification
     * — the externals-listed declaration module's identity is the
     * externals key exactly as written.
     */
    private Map<ModuleId, CanonicalModuleIdentity> declarationModuleIdentities() {
        Map<String, CanonicalModuleIdentity> classification =
            modulePathClassification();
        Map<ModuleId, CanonicalModuleIdentity> identities = new LinkedHashMap<>();
        for (ModuleId declarationModule : hostDeclarationSurface.moduleIds()) {
            CanonicalModuleIdentity identity =
                classification.get(declarationModule.path());
            if (identity != null) {
                identities.put(declarationModule, identity);
            }
        }
        return identities;
    }

    /**
     * The validated extern-C generated modules of this compile keyed by
     * module identity (the one project lowering's FFI registration-seed
     * input; empty when the compile imports no extern-C declaration).
     */
    private Map<ModuleId, FfiGeneratedModule> externCGeneratedModules() {
        Map<ModuleId, FfiGeneratedModule> generatedModules = new LinkedHashMap<>();
        for (Map.Entry<String, FfiGeneratedModule> generated
                : ffiGenerations.entrySet()) {
            generatedModules.put(new ModuleId(generated.getKey()),
                generated.getValue());
        }
        return generatedModules;
    }

    /**
     * The compiler-owned builtin {@code Error} declaration of this
     * compile: the entry module's synthesized declaration — the same
     * one authority the checker's root binding derives from
     * ({@link BuiltinErrorDeclaration#synthesized}), so the builtin
     * layout seed is never derived from source text, a host
     * declaration, or an interface-index entry.
     */
    private static BuiltinErrorDeclaration builtinErrorDeclaration(
            CheckedProjectInput checkedProject) {
        for (CheckedModuleInput module : checkedProject.modules()) {
            if (module.moduleId().equals(checkedProject.entryModule())) {
                return BuiltinErrorDeclaration.synthesized(module.ast().span());
            }
        }
        throw new IllegalStateException("the checked project carries no entry"
            + " module (producer defect)");
    }

    /**
     * Records the first staging write failure of the compile (D4): it
     * sets the error state so the compile stops at the next phase
     * boundary, and {@link #compile()} renders the pinned deterministic
     * compiler I/O diagnostic.
     */
    private void stageFailure(IOException failure) {
        if (pendingStageFailure == null) {
            pendingStageFailure = failure;
        }
        hasErrors = true;
    }

    /**
     * Resolves one import statement through the T6 resolver and records
     * the raw-path &rarr; compiled-module-path resolution when the
     * imported module is known; returns the imported module, or null
     * when the import resolves to no known module (the raw path was
     * already resolved by discovery).
     */
    private ModuleInfo resolveImport(ImportDeclaration imp, ModuleInfo info,
            Map<String, String> importResolutions) {
        String resolvedSource = resolveImportPath(imp.modulePath(),
            Path.of(info.sourcePath), imp.span());
        if (resolvedSource == null) {
            return null;
        }
        ModuleInfo imported = modules.get(resolvedSource);
        if (imported != null) {
            importResolutions.put(imp.modulePath(), imported.modulePath);
        }
        return imported;
    }

    /**
     * The per-compilation canonical identity surface (emitter page D1;
     * js-v12-completion-architecture D3): one identity index over the
     * module-path classification, consumed by the Lua and JS emitters'
     * descriptor services.  The intrinsic builtin Error classification
     * (the checker's empty module path) and every known module join the
     * map; a module the classification cannot give a public identity
     * (an out-of-root relative source) stays absent — class-free code
     * remains valid, and a class there fails closed at descriptor
     * production (the pinned invariant violation).
     */
    private ModuleIdentityResolver.IdentityIndex buildCanonicalIdentitySurface() {
        return ModuleIdentityResolver.buildIndex(modulePathClassification());
    }

    /**
     * The compilation's module-path classification (the module-identity
     * layer's single classification map): every known module path plus
     * the intrinsic builtin Error module path.  The checker, the export
     * extractor, the IR dumper, and the backends all obtain class
     * identities exclusively from this surface (v1.2 identity carriage,
     * descriptor-identity-propagation D1) — never from dotted-path
     * reconstruction.
     */
    private Map<String, CanonicalModuleIdentity> modulePathClassification() {
        Map<String, CanonicalModuleIdentity> modulePathIdentities =
            new HashMap<>();
        modulePathIdentities.put("", CanonicalModuleIdentity.BuiltinModule.INSTANCE);
        for (ModuleInfo info : modules.values()) {
            CanonicalModuleIdentity identity = classifyModuleIdentity(info);
            if (identity != null) {
                modulePathIdentities.put(info.modulePath, identity);
            }
        }
        return modulePathIdentities;
    }

    private void codegenAllJs() throws IOException {
        // Canonical identity surface (js-v12-completion-architecture D3):
        // the shared per-compilation surface both emitter arms consume.
        ModuleIdentityResolver.IdentityIndex identityIndex =
            buildCanonicalIdentitySurface();

        // Pass 1: generate every module and merge diagnostics. Rejected
        // modules write no artifact.
        List<ModuleInfo> cleanModules = new ArrayList<>();
        Map<ModuleInfo, JsBackend.JsCodegenResult> results =
            new LinkedHashMap<>();
        for (ModuleInfo info : modules.values()) {
            if (info.isDeclarationFile) continue;
            // Import classification: raw import path → module path of the
            // imported COMPILED module; declaration files that are not
            // spec stdlib modules become host modules with their declared
            // export map.
            Map<String, String> importResolutions = new HashMap<>();

            Map<String, HostModuleDeclarations> hostModules =
                new HashMap<>();
            // Extern-C imports (fixed-name-directive-events D9): raw
            // import paths whose resolved module is a declaration file
            // with effective FileDirectives.externC — the re-keyed JS
            // E6006 FFI_UNSUPPORTED_BACKEND arm keys on this set at the
            // import statement.
            Set<String> externCImports = new HashSet<>();
            if (info.rawAst != null) {
                for (StatementNode stmt : info.rawAst.statements()) {
                    if (stmt instanceof ImportDeclaration imp) {
                        ModuleInfo imported = resolveImport(imp, info,
                            importResolutions);
                        if (imported != null) {
                            // Host modules: declaration files that are
                            // not spec stdlib modules (the LuaJIT use
                            // site's classification, verbatim).
                            if (imported.isDeclarationFile
                                    && !isSpecStdlibModuleInfo(imported)) {
                                hostModules.put(imp.modulePath(),
                                    hostDeclarationsOf(imported));
                            }
                            if (imported.isDeclarationFile
                                    && imported.rawAst != null
                                    && imported.rawAst.fileDirectives()
                                        .externC()) {
                                externCImports.add(imp.modulePath());
                            }
                        }
                    }
                }
            }
            boolean isEntry = info.sourcePath.equals(entryFile.toString());

            JsBackend.JsCodegenResult res = JsBackend.generate(
                info.rawAst, info.checkResult, info.sourcePath, info.modulePath,
                importResolutions, hostModules, externCImports, isEntry,
                identityIndex, identityIndex.moduleIdentityLookup(),
                sourceMap ? new SourceMapGenerator() : null,
                invocation.semanticProfile(),
                completedPlansByModulePath());
            // Native ranged backend list (T12): the backend emits
            // CompilerDiagnostic entries directly, so the orchestrator
            // merge needs no boundary conversion — real spans keep their
            // exact scalar offsets and synthetic anchors keep their notes.
            List<CompilerDiagnostic> backendDiags = res.diagnostics();
            diagnostics.addAll(backendDiags);
            if (backendDiags.stream()
                    .anyMatch(d -> "error".equals(d.severity()))) {
                hasErrors = true;
            }
            if (res.hasErrors()) {
                log("  JavaScript backend rejected " + info.modulePath + ": "
                    + res.diagnostics());
                continue;
            }
            cleanModules.add(info);
            results.put(info, res);
            jsGeneratedResults.put(info.sourcePath, res);
        }

        // Pass 2: write artifacts for clean modules only. With the
        // effective sourceMap flag, each clean module additionally
        // writes its .deal.map.json sidecar (js-v12-source-maps D1):
        // one <modulePath with '/' for '.'>.deal.map.json next to the
        // artifact, serialized from the module's recorded mappings via
        // SourceMapGenerator.toJson with the same project-relative
        // source/generated path strings the Lua arm passes. A clean
        // statement-less module (an empty or comment-only source)
        // writes its sidecar with an empty mappings array — every
        // clean module gets exactly one sidecar, so no hasMappings()
        // gate may skip the write. A rejected module writes no .js
        // and no sidecar (the two-pass no-partial-artifact contract).
        for (ModuleInfo info : cleanModules) {
            JsBackend.JsCodegenResult res = results.get(info);
            // The staging sink (whole-project-artifact-publication
            // D1/D5): the same module-relative paths inside the staging
            // tree; the live publication path feeds only the sidecar's
            // path-string computation, so the per-invocation stage-tree
            // nonce (on-disk names only) never enters artifact content.
            String artifactRel = res.modulePath().replace('.', '/') + ".js";
            Path liveArtifactPath = outputRoot.resolve(artifactRel);
            Path stageOutputPath = stager.stagePath(artifactRel);
            Files.writeString(stageOutputPath, res.source());
            log("  Generated: " + liveArtifactPath);

            if (sourceMap && res.sourceMap() != null) {
                String[] paths = sourceMapSidecarPaths(info.sourcePath,
                    outputRoot, liveArtifactPath);
                String mapJson = res.sourceMap().toJson(paths[0], paths[1]);
                Path stageMapPath = stager.stagePath(
                    res.modulePath().replace('.', '/')
                        + ".deal.map.json");
                Files.writeString(stageMapPath, mapJson);
                log("  Source map: " + outputRoot.resolve(
                    res.modulePath().replace('.', '/')
                        + ".deal.map.json"));
            }
        }

        copyJsRuntimeLibrary();
        copyStdlibJsModules();
    }

    public HostDeclarationSurface hostDeclarationSurface() {
        return hostDeclarationSurface;
    }

    /**
     * Produces the declaration surface over every host and extern-C
     * declaration module of the compilation (the spec stdlib declaration
     * modules keep their {@code BuiltinModule} classification and are not
     * host declarations). A producer defect — the first declared class
     * field whose resolved declared type has no runtime representation —
     * merges its E6005 into the compile's diagnostics and yields no
     * surface.
     *
     */
    private HostDeclarationSurface produceHostDeclarationSurface() {
        List<HostDeclarationSurface.DeclarationFacts> facts =
            new ArrayList<>();
        for (ModuleInfo info : modules.values()) {
            if (!info.isDeclarationFile || info.rawAst == null
                    || isSpecStdlibModuleInfo(info)) {
                continue;
            }
            facts.add(declarationFactsOf(info));
        }
        HostDeclarationSurface.Production production =
            HostDeclarationSurface.produce(facts);
        if (production.hasErrors()) {
            diagnostics.addAll(production.diagnostics());
            hasErrors = true;
            return null;
        }
        log("  Declaration surface: " + facts.size()
            + " declaration module(s)");
        return production.surface();
    }

    /**
     * The declaration facts of one declaration module: the declared
     * export map (the phase-1 {@link ExportExtractor} output for a
     * declaration file, in declaration order), the per-class field
     * records in declaration order with their resolved declared types,
     * and the declaration kind ({@code EXTERN_C} for a module the
     * metadata phase classifies extern-C, {@code HOST} otherwise). The
     * field types resolve in the declaring module's own context through
     * an {@link ExportExtractor} seeded with the compilation's
     * module-path classification and the declaration's resolved import
     * aliases — the exact resolution the retained host consumers read.
     */
    private HostDeclarationSurface.DeclarationFacts declarationFactsOf(
            ModuleInfo imported) {
        Map<String, String> importAliasMap = new HashMap<>();
        for (StatementNode stmt : imported.rawAst.statements()) {
            if (stmt instanceof ImportDeclaration imp) {
                String resolved = resolveImportPath(imp.modulePath(),
                    Path.of(imported.sourcePath));
                if (resolved != null) {
                    ModuleInfo target = modules.get(resolved);
                    if (target != null) {
                        importAliasMap.put(imp.alias(),
                            target.modulePath);
                    }
                }
            }
        }

        ExportExtractor extractor = new ExportExtractor(
            imported.modulePath, true, modulePathClassification()::get);
        extractor.setImportModulePaths(importAliasMap);
        extractor.extract(imported.rawAst);
        Map<String, HostDeclarationSurface.DeclaredClass> classes =
            new LinkedHashMap<>();
        HostDeclarationSurface.DeclarationKind kind = isExternCModuleInfo(imported)
            ? HostDeclarationSurface.DeclarationKind.EXTERN_C
            : HostDeclarationSurface.DeclarationKind.HOST;
        for (StatementNode stmt : imported.rawAst.statements()) {
            ClassDeclaration cd = AstDeclarations.classDeclarationOf(stmt);
            if (cd == null) {
                continue;
            }
            List<HostDeclarationSurface.DeclaredField> fields =
                new ArrayList<>();
            for (ClassField cf : cd.fields()) {
                fields.add(new HostDeclarationSurface.DeclaredField(
                    cf, extractor.resolveFieldType(cf.type())));
            }
            classes.putIfAbsent(cd.name(),
                new HostDeclarationSurface.DeclaredClass(
                    cd.name(), kind, fields));
        }
        Map<String, Type> declaredExports = imported.exports != null
            ? imported.exports : Map.of();
        return new HostDeclarationSurface.DeclarationFacts(
            new ModuleId(imported.modulePath), kind, declaredExports,
            classes);
    }

    private HostModuleDeclarations hostDeclarationsOf(
            ModuleInfo imported) {
        HostDeclarationSurface.DeclarationFacts facts = hostDeclarationSurface
            .require(new ModuleId(imported.modulePath));
        Map<String, List<HostModuleDeclarations.HostField>> classFields =
            new LinkedHashMap<>();
        for (Map.Entry<String, HostDeclarationSurface.DeclaredClass> entry
                : facts.classes().entrySet()) {
            List<HostModuleDeclarations.HostField> fields =
                new ArrayList<>();
            for (HostDeclarationSurface.DeclaredField field
                    : entry.getValue().fields()) {
                fields.add(new HostModuleDeclarations.HostField(
                    field.declaration(), field.type()));
            }
            classFields.put(entry.getKey(), fields);
        }
        return new HostModuleDeclarations(facts.exports(), classFields);
    }

    /**
     * The source/generated path pair passed to
     * {@link SourceMapGenerator#toJson} for a JS sidecar — the exact
     * strings the Lua arm passes (the retained test-scope AST codegen's
     * project-relative normalization, mirrored verbatim): the source
     * path and the generated artifact path relativized against the
     * project root inferred as {@code outputRoot/../..} when the
     * relativization stays inside the project; otherwise the fallback
     * pair — the raw source path and the output-root-relative
     * generated path.
     */
    private static String[] sourceMapSidecarPaths(String sourcePath,
                                                  Path outputRoot,
                                                  Path outputPath) {
        String relSourcePath = sourcePath;
        String relGeneratedPath = outputRoot.relativize(outputPath).toString();

        try {
            Path absOutputRoot = outputRoot.toAbsolutePath().normalize();
            Path projectRoot = absOutputRoot.resolve("..").resolve("..").normalize();
            Path absSource = Path.of(sourcePath).toAbsolutePath();

            Path srcRel = projectRoot.relativize(absSource);
            if (!srcRel.startsWith("..")) {
                relSourcePath = srcRel.toString();
            }

            Path genRel = projectRoot.relativize(outputPath.toAbsolutePath());
            if (!genRel.startsWith("..")) {
                relGeneratedPath = genRel.toString();
            }
        } catch (IllegalArgumentException e) {
            // Keep fallback paths if relativization fails
        }

        return new String[] { relSourcePath, relGeneratedPath };
    }

    private boolean isSpecStdlibModuleInfo(ModuleInfo info) {
        return info.location.moduleClassification()
            instanceof CanonicalModuleIdentity.BuiltinModule;
    }

    /**
     * JS deployment copy (js-backend-emitter D10): copies deal/runtime.js
     * to <output>/deal/runtime.js — the resolved distribution surface,
     * else E6000 — staged fresh every compile (whole-set semantics; no
     * destination skip). The mirror of copyRuntimeLibrary with the .js
     * spelling.
     */
    private void copyJsRuntimeLibrary() throws IOException {

        Optional<DistributionHome.ResolvedSource> runtime =
            stager.stageRuntimeCopy("deal/runtime.js", distributionHome);
        if (runtime.isPresent()) {
            log("  Copied runtime: " + outputRoot.resolve("deal/runtime.js")
                + " (" + runtime.get().tier() + ")");
            return;
        }

        // Anchorless site (D5/D6): the note names the missing runtime
        // library path.
        syntheticError(DiagnosticCode.E6000,
            "Runtime library not found: deal/runtime.js", "",
            "missing anchor: runtime library path 'deal/runtime.js'");
    }

    private void copyStdlibJsModules() throws IOException {
        for (String stdlibModule : StdlibModuleResolver.SPEC_STDLIB_MODULES) {
            // Whole-set semantics (whole-project-artifact-publication
            // D3/D6): each stdlib copy stages fresh from the resolved
            // distribution surface — project-local surface first, then
            // the language distribution, then the checkout CWD dev
            // fallback — and no skip of an existing destination remains.
            String name = stdlibModule.substring("std/".length());
            Optional<DistributionHome.ResolvedSource> source =
                stager.stageStdlibCopy(name, "js", distributionHome);
            if (source.isEmpty()) {
                continue;
            }
            log("  Copied stdlib: " + stdlibModule + " ("
                + source.get().tier() + ")");
        }
    }

    // =========================================================================
    // IR dump helper
    // =========================================================================

    /**
     * Stages an IR dump string into the publication staging tree (D1/D5):
     * the file is placed at {@code <outputRoot>/<module-path>.ir.txt}
     * after a successful publish, and appears atomically with the set —
     * dumps of a failed compilation are discarded with the stage tree
     * and never reach the live root (today's phases 1/3 dumps wrote the
     * live root directly). A staging write failure records the pinned
     * publish diagnostic (D4); the {@link IrDumper} failure diagnostic
     * (E6001) stays with the callers' exception handling.
     */
    private void writeIrDump(String modulePath, String irText) {
        if (pendingStageFailure != null) {
            return; // a staging failure already recorded: nothing more stages
        }
        String filePath = modulePath.replace('.', '/') + ".ir.txt";
        try {
            stager.stage(filePath, irText.getBytes(StandardCharsets.UTF_8));
        } catch (IOException stagingFailure) {
            stageFailure(stagingFailure);
            return;
        }
        log("  IR dumped: " + outputRoot.resolve(filePath));
    }

    // =========================================================================
    // Import resolution
    // =========================================================================

    /**
     * Resolves an import path to a source file.  Emits E2003 if
     * resolution fails; without an import declaration span the re-emission
     * is synthetic with an anchor note naming the import path (D5).
     *
     */
    /**
     * Resolves an import path to a source file through the T6 resolver
     * (the pinned five rules — no legacy buildCandidates, no CWD
     * bare-lookup fallback, no classpath module fallback). A failed
     * resolution merges its E2003/E2009 diagnostic at the canonical
     * synthetic shape plus anchor note when no import declaration span
     * is available.
     *
     */
    public String resolveImportPath(String importPath, Path fromFile) {
        return resolveImportPath(importPath, fromFile, null);
    }

    /**
     * Resolves an import path to a source file through the T6 resolver,
     * anchoring any failure diagnostic at the given import declaration
     * span when present, falling back to the canonical synthetic shape
     * plus the anchor note (D5).
     */
    private String resolveImportPath(String importPath, Path fromFile,
                                      Span importSpan) {
        Span anchor = importSpan != null
            ? importSpan : Span.synthetic(fromFile.toString());
        SourceModuleResolver.ResolveResult result =
            sourceResolver.resolve(fromFile.toString(), importPath, anchor);
        if (result instanceof SourceModuleResolver.ResolveResult.Resolved r) {
            locations.putIfAbsent(r.location().normalizedSourcePath(),
                r.location());
            return r.location().normalizedSourcePath();
        }
        CompilerDiagnostic failure =
            ((SourceModuleResolver.ResolveResult.Failure) result).diagnostic();
        String key = fromFile + "|" + importPath;
        if (reportedResolveFailures.add(key)) {
            diagnostics.add(failure);
            hasErrors = true;
        }
        return null;
    }

    /**
     * The import-resolution failures already merged into
     * {@link #diagnostics()} (importer path ‖ specifier), so a
     * re-resolution of the same import in a later phase never
     * duplicates a diagnostic.
     */
    private final Set<String> reportedResolveFailures = new HashSet<>();

    // =========================================================================
    // Canonical module-identity classification (js-v12-completion-architecture D3)
    // =========================================================================

    private CanonicalModuleIdentity classifyModuleIdentity(ModuleInfo info) {
        if (info.location == null) {
            return null;
        }
        return info.location.moduleClassification();
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private void log(String msg) {
        if (verbose) System.out.println(msg);
    }

    private void error(DiagnosticCode code, String message, DiagnosticRange range) {
        diagnostics.add(CompilerDiagnostic.error(code, message, range));
        hasErrors = true;
    }

    /** Span overload: {@code span.range()}; a SYNTHETIC conversion
     * appends the mandatory D4 anchor note. */
    private void error(DiagnosticCode code, String message, Span span) {
        diagnostics.add(CompilerDiagnostic.error(code, message, span));
        hasErrors = true;
    }

    /** Explicit synthetic factory with a construct-naming anchor note (D6). */
    private void syntheticError(DiagnosticCode code, String message, String file,
                                String missingAnchorNote) {
        diagnostics.add(CompilerDiagnostic.syntheticError(code, message, file,
            missingAnchorNote));
        hasErrors = true;
    }

    /**
     * Delegates printing to the canonical formatter (D8) over the D1
     * canonical report order (deterministic-diagnostics): the stderr
     * diagnostic blocks appear in the canonical total order while the
     * internal collection order is unchanged; the error/warning summary
     * counts are computed over the internal list exactly as before.
     */
    private void printDiagnostics() {
        for (CompilerDiagnostic d : DiagnosticOrder.canonical(diagnostics)) {
            System.err.println(DiagnosticFormatter.format(d));
        }
        long errorCount = diagnostics.stream()
            .filter(d -> "error".equals(d.severity())).count();
        long warnCount = diagnostics.stream()
            .filter(d -> "warning".equals(d.severity())).count();
        System.err.println(errorCount + " error(s), " + warnCount + " warning(s)");
    }

    // =========================================================================
    // ModuleResolverImpl
    // =========================================================================

    final class ModuleResolverImpl implements ModuleResolver {

        private final Map<String, ModuleInfo> modules;

        private final Map<String, NameResolver> declarationResolvers =
            new HashMap<>();

        ModuleResolverImpl(Map<String, ModuleInfo> modules,
                           List<CompilerDiagnostic> diagnostics) {
            this.modules = modules;
        }

        @Override
        public Map<String, Type> resolveModule(String modulePath,
                                                String importingModule,
                                                Set<String> modulesInProgress)
                throws ModuleNotFoundException,
                    CffiImportWithoutNativeLibraryException {
            // Direct internal-name match first: the checker passes dotted
            // module paths carried by Type.Class values (e.g. an
            // externals module's "host.x\y"), which are internal names,
            // never import specifiers.
            for (ModuleInfo info : modules.values()) {
                if (info.modulePath.equals(modulePath)) {
                    return cffiManifestCheckedExports(info, modulePath);
                }
            }
            // Otherwise modulePath is the import specifier exactly as
            // written (e.g. "./lib", "std/console", "host/cfg");
            // importingModule is the importing module's internal dotted
            // module name. The T6 resolver derives the resolved source
            // from the importer's source path, so the importer must be
            // located by its internal name first.
            String importerSource = sourcePathForInternalName(importingModule);
            if (importerSource == null && modules.containsKey(importingModule)) {
                // Defensive: an importingModule spelled as a source path.
                importerSource = importingModule;
            }
            if (importerSource == null) {
                throw new ModuleNotFoundException(
                    "Module not found: " + modulePath);
            }
            SourceModuleResolver.ResolveResult result = sourceResolver.resolve(
                importerSource, modulePath, Span.synthetic(importerSource));
            if (result instanceof SourceModuleResolver.ResolveResult.Resolved r) {
                ModuleInfo target = modules.get(
                    r.location().normalizedSourcePath());
                if (target != null) {
                    return cffiManifestCheckedExports(target, modulePath);
                }
            }
            throw new ModuleNotFoundException("Module not found: " + modulePath);
        }

        /**
         * The v1.2 C FFI manifest policy (docs/spec-v1.2.md:1891): a C
         * FFI declaration file — a {@code .d.deal} file whose effective
         * {@link FileDirectives#externC()} holds — may only be imported
         * through a {@code deal.json} externals entry that declares the
         * file with a classified {@code nativeLibrary}. The resolved
         * target's module classification is
         * {@code ExternalModule(rawImportSpecifier)} exactly when an
         * externals entry declares the file (file-keyed,
         * {@code ModuleIdentityResolver} D6 rule (2)); no entry at all
         * is always the invalid-manifest-policy rejection, and a
         * manifest-authored entry without {@code nativeLibrary} is the
         * rejection too (the test-only isolated-phase constructors
         * synthesize their entries from the harness's declarations map,
         * which cannot carry {@code nativeLibrary}, and their entries
         * count as backed — see {@link #cffiManifestBacked(ModuleInfo)}).
         * The checker maps the exception to E2010 at the import span
         * ({@code deal/checker/NameResolver.java},
         * {@code processImport}) — the production emission site behind
         * the promoted conformance pin.
         */
        private Map<String, Type> cffiManifestCheckedExports(
                ModuleInfo target, String importSpecifier)
                throws CffiImportWithoutNativeLibraryException {
            if (target.isDeclarationFile && target.rawAst != null
                    && target.rawAst.fileDirectives().externC()
                    && !cffiManifestBacked(target)) {
                throw new CffiImportWithoutNativeLibraryException(
                    importSpecifier);
            }
            return target.exports != null ? target.exports : Map.of();
        }

        /**
         * True when the externals entry that declares the target
         * (file-keyed classification) satisfies the v1.2 C FFI manifest
         * policy; false when no entry declares the file, or when a
         * manifest-authored entry omits {@code nativeLibrary}.
         *
         * <p>The {@code nativeLibrary} authoring rule
         * (docs/spec-v1.2.md:1891 — "a C FFI entry must include
         * nativeLibrary") applies to manifest-authored contexts: the
         * production locator path parses the entry's classified
         * {@code nativeLibrary}, and an entry that omits it is the
         * E2010 invalid-manifest-policy rejection. The test-only
         * isolated-phase constructors synthesize their externals
         * entries from the harness's declarations map, which cannot
         * carry {@code nativeLibrary}; there the harness's explicit
         * entry is the authority channel and counts as backed.</p>
         */
        private boolean cffiManifestBacked(ModuleInfo target) {
            CanonicalModuleIdentity classification = target.location == null
                ? null : target.location.moduleClassification();
            if (!(classification
                    instanceof CanonicalModuleIdentity.ExternalModule ext)) {
                return false;
            }
            ExternalEntry entry = context.externals().get(
                ext.rawImportSpecifier());
            if (entry == null) {
                return false;
            }
            return manifestAuthoredContext
                ? entry.nativeLibrary() != null
                : true;
        }

        /**
         * The source path of the module whose internal dotted name is
         * {@code internalName}, or null.
         */
        private String sourcePathForInternalName(String internalName) {
            for (ModuleInfo info : modules.values()) {
                if (info.modulePath.equals(internalName)) {
                    return info.sourcePath;
                }
            }
            return null;
        }

        @Override
        public Symbol.ClassSymbol resolveClassSymbol(String className,
                                                      String modulePath,
                                                      String importingModule)
                throws ModuleNotFoundException {
            for (ModuleInfo info : modules.values()) {
                if (info.modulePath.equals(modulePath)) {
                    if (info.symbolTable != null) {
                        Symbol sym = info.symbolTable.resolve(className);
                        if (sym instanceof Symbol.ClassSymbol cs) return cs;
                        return null;
                    }

                    for (StatementNode stmt : info.rawAst.statements()) {
                        ClassDeclaration cd = null;
                        if (stmt instanceof ClassDeclaration c) {
                            cd = c;
                        } else if (stmt instanceof ExportDeclaration ed
                                && ed.declaration()
                                    instanceof ClassDeclaration c) {
                            cd = c;
                        }
                        if (cd != null && cd.name().equals(className)) {
                            CanonicalModuleIdentity moduleIdentity =
                                classifyModuleIdentity(info);
                            if (moduleIdentity == null) {
                                throw new IllegalStateException(
                                    "no canonical public module identity for"
                                        + " host declaration module '"
                                        + info.modulePath
                                        + "': its declared classes can never"
                                        + " carry an identity (internal"
                                        + " invariant violation)");
                            }
                            return new Symbol.ClassSymbol(cd.name(),
                                cd.fields(), info.modulePath,
                                new CanonicalClassIdentity(moduleIdentity,
                                    cd.name()));
                        }
                    }
                    return null;
                }
            }
            return null;
        }

        @Override
        public Symbol.ClassSymbol resolveClassSymbol(String className,
                CanonicalModuleIdentity declaringModule,
                String importingModule)
                throws ModuleNotFoundException {
            // v1.2 identity carriage: imported classes carry the declaring
            // source's identity; route on the module-identity
            // classification (the same single classifier the identity
            // surface consumes).
            for (ModuleInfo info : modules.values()) {
                CanonicalModuleIdentity identity =
                    classifyModuleIdentity(info);
                if (identity == null || !identity.equals(declaringModule)) {
                    continue;
                }
                if (info.symbolTable != null) {
                    Symbol sym = info.symbolTable.resolve(className);
                    if (sym instanceof Symbol.ClassSymbol cs) return cs;
                    // Two files in one directory share the module
                    // identity; the declaring file's table may live in a
                    // later module — keep scanning.
                    continue;
                }
                // Host declaration synthesis (the modulePath-keyed path's
                // counterpart): declared classes of an externals-listed
                // declaration carry the externals identity.
                for (StatementNode stmt : info.rawAst.statements()) {
                    ClassDeclaration cd = null;
                    if (stmt instanceof ClassDeclaration c) {
                        cd = c;
                    } else if (stmt instanceof ExportDeclaration ed
                            && ed.declaration()
                                instanceof ClassDeclaration c) {
                        cd = c;
                    }
                    if (cd != null && cd.name().equals(className)) {
                        return new Symbol.ClassSymbol(cd.name(),
                            cd.fields(), info.modulePath,
                            new CanonicalClassIdentity(identity,
                                cd.name()));
                    }
                }
                // Two files in one directory share the module identity;
                // keep scanning for the declaring module.
                continue;
            }
            return null;
        }

        @Override
        public boolean isFunctionExportedFromModule(
                CanonicalModuleIdentity declaringModule,
                String functionName, String importingModule)
                throws ModuleNotFoundException {
            // v1.2 identity carriage: route on the module-identity
            // classification; the declaring module's exports map answers
            // the jsonable synthetic-export queries.
            for (ModuleInfo info : modules.values()) {
                CanonicalModuleIdentity identity =
                    classifyModuleIdentity(info);
                if (identity != null && identity.equals(declaringModule)
                        && info.exports != null
                        && info.exports.containsKey(functionName)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Resolves a type node against the owning module's own
         * name-resolved scope — the phase-3 dependency order guarantees
         * the owner's {@link #typeCheckAll} turn (and therefore its
         * {@code NameResolver}) completed before any importer checks,
         * so the importer's cross-module class-field type annotations
         * (e.g. {@code children: Child[]} declared in a companion)
         * resolve against the declaring module instead of silently
         * falling back to the importing module's scope where the bare
         * class name is unknown ({@code Type.Error} facts). An
         * unresolved owner type ({@code Type.Error}) returns
         * {@code null} so the caller keeps its documented local
         * fallback; a declaration-file owner has no name resolver and
         * returns {@code null} the same way.
         */
        @Override
        public Type resolveTypeNodeInModule(TypeNode typeNode,
                                            String modulePath,
                                            String importingModule)
                throws ModuleNotFoundException {
            for (ModuleInfo info : modules.values()) {
                if (info.modulePath.equals(modulePath)) {
                    if (info.nameResolver != null) {
                        Type resolved =
                            info.nameResolver.resolveTypeNode(typeNode);
                        return resolved == Type.Error.INSTANCE
                            ? null : resolved;
                    }

                    Type resolved = declarationResolver(info)
                        .resolveTypeNode(typeNode);
                    return resolved == Type.Error.INSTANCE
                        ? null : resolved;
                }
            }
            return null;
        }

        private NameResolver declarationResolver(ModuleInfo info) {
            NameResolver cached = declarationResolvers.get(info.modulePath);
            if (cached != null) {
                return cached;
            }
            // v1.2 identity carriage: the declaration's classes carry the
            // compilation's classification identities (externals/builtin),
            // never the standalone dotted-path default — the synthesized
            // host-class field types must carry the same identity the
            // host-class symbols carry.
            NameResolver built = new NameResolver(info.modulePath, this,
                new HashSet<>(), modulePathClassification()::get);
            declarationResolvers.put(info.modulePath, built);
            built.resolve(info.rawAst);
            return built;
        }
    }
}
