package deal.module;

import deal.ast.*;
import deal.checker.*;
import deal.codegen.Backend;
import deal.codegen.SourceMapGenerator;
import deal.codegen.jvm.JvmBackend;
import deal.codegen.js.HostModuleDeclarations;
import deal.codegen.js.JsBackend;
import deal.codegen.lua.LuaBackend;
import deal.identity.CanonicalModuleIdentity;
import deal.identity.ProjectModuleIdentity;
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
import deal.semantic.CompilerInvocation;
import deal.semantic.CompilerProfileProvider;
import deal.semantic.LoweringSupport;
import deal.semantic.MigrationPlanner;
import deal.semantic.ModuleFact;
import deal.semantic.ReleaseConfiguration;
import deal.semantic.RequirementManifestResult;
import deal.semantic.RoutePlanResult;
import deal.semantic.Target;
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

    /**
     * True when {@code --source-map} was explicitly requested, distinct
     * from {@link #sourceMap} (which is also derived from {@code --dump-ir}
     * because IR hardening enables source maps with it). The JVM
     * source-map warning fires only on the explicit request, never on a
     * {@code --dump-ir}-derived one (ISSUE-0091 rework).
     */
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

    /**
     * The foundation-phase result built once per compile (ISSUE-0288):
     * after phase 3 succeeds, {@link CheckedProjectBuilder} consumes the
     * orchestrator's module map in {@code buildCheckOrder} and produces
     * exactly one checked project input and one project interface index.
     * {@code null} before the foundation phase runs (or when a
     * pre-foundation phase failed).
     */
    private CheckedProjectBuildResult checkedProjectBuild;

    /**
     * The requirement-manifest foundation result of this compile
     * (ISSUE-0289): exactly one manifest per implementation module in
     * dependency order computed by {@link LoweringSupport} after the
     * checked project and interface index (foundation F3/F8) —
     * {@code null} before the manifest phase runs or when a preceding
     * phase failed. The support's E6005 diagnostics (an inconsistent
     * checked/interface fact, never a crash) are merged into
     * {@link #diagnostics()}.
     */
    private RequirementManifestResult requirementManifests;

    /**
     * The route-plan foundation result of this compile (ISSUE-0290): one
     * {@code ModuleRoutePlan} for the compile's target after the
     * manifests — {@code null} before the route-plan phase runs, when a
     * preceding phase failed, or for the JS backend (the closed
     * route-plan target axis is LUAJIT|JVM, foundation F4). Planner
     * E6005 diagnostics (fact defects, never a crash) are also merged
     * into {@link #diagnostics()}.
     */
    private RoutePlanResult routePlan;

    private final Map<String, ModuleInfo> modules = new LinkedHashMap<>();
    private final List<CompilerDiagnostic> diagnostics = new ArrayList<>();
    private boolean hasErrors = false;

    /**
     * The phase-2 dependency (check) order of this compilation, in
     * module-path form — the graph order the extern-C metadata phase
     * orders imported provider references by (empty before phase 2).
     */
    private List<String> dependencyOrder = List.of();

    /**
     * The validated extern-C metadata published by the FFI phase
     * (ISSUE-0162): dotted module path &rarr; the generated module
     * inputs (descriptor, cdef bundle, retained plans, forward
     * bindings). Populated only for the LuaJIT backend after successful
     * validation; empty for JVM/JS and on any validation failure — a
     * failed validation or an incapable backend publishes no metadata
     * and no partial artifact.
     */
    private final Map<String, FfiGeneratedModule> ffiGenerations =
        new LinkedHashMap<>();

    /**
     * The JVM codegen pass-1 results of this compile (ISSUE-0374 profile
     * plumb observability): module source path → the
     * {@link JvmBackend.JvmCodegenResult} the backend generated for every
     * module it accepted — including the backend's recorded int32 mode,
     * the real stored backend state the profile plumb selected
     * ({@link #invocation()}'s semantic profile → {@link
     * JvmBackend#generate}). Read-only; empty before the JVM codegen
     * phase runs or when the backend rejected every module. A pass-2
     * class-name collision does not remove a pass-1 entry (it only
     * blocks the artifact write).
     */
    private final Map<String, JvmBackend.JvmCodegenResult> jvmGeneratedResults =
        new LinkedHashMap<>();

    /**
     * The {@code --diagnostics-json} output path, or {@code null} when the
     * structured document was not requested. When set, the orchestrator
     * writes the {@link DiagnosticStructuredOutput} document for every
     * compilation — successful or failed — without changing the exit code;
     * a write failure is a deterministic compiler I/O diagnostic on
     * stderr with exit 1 (D8, parent D11 I/O discipline).
     */
    private final Path diagnosticsJsonPath;

    /**
     * The pinned three-tier distribution resolver of this compilation's
     * project (ISSUE-0457,
     * {@code release-distribution-packaging-and-discovery} D3): the
     * runtime and stdlib deployment copies resolve their sources
     * through it in the pinned order — project-local surface first,
     * then the language distribution (classpath resources, then the
     * {@code DEAL_HOME} filesystem layout), then the checkout CWD dev
     * fallback.
     */
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

    // Host externals (ISSUE-0082, host-module-abi D5, file-keyed since
    // ISSUE-0269): the externals declarations live in
    // ProjectContext.externals (raw import specifier → validated
    // ExternalEntry). A resolved source whose canonical URI equals an
    // entry's declaration path carries ExternalModule(that key)
    // regardless of the import spelling — the classification recorded on
    // each SourceModuleLocation by the T6 resolver; the dotted
    // typing/class-identity name for an externals module is derived from
    // the raw specifier (the legacy externalsModulePaths behavior).

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

    /**
     * Production entry point (ISSUE-0269 migration, design source
     * {@code strict-project-context-resolution-identity} D7 + Failure and
     * operations): the orchestrator consumes the immutable validated
     * {@link ProjectContext} published by {@link ProjectLocator} plus the
     * compilation options. The backend is the context's effective backend
     * ({@code "luajit"} | {@code "jvm"} | {@code "js"} — the strict
     * backend set), the
     * output root is the context's classified
     * {@link OutputConfigResolver.OutputRef} (created only in the write
     * phase), and module roots, externals declarations, the stdlib
     * surface, and the private deployment identity are the context's
     * validated values. No {@code DealConfig}, no implicit
     * entry-directory root, no CWD bare-lookup fallback, and no lossy
     * {@code computeModulePath} participate: module naming derives from
     * the T5/T6 classification (the dotted root-relative path, the
     * externals raw specifier with {@code /} → {@code .}, the pinned
     * stdlib module name) and the private {@code deploymentModuleId} for
     * unclassified sources.
     *
     * @param context the validated immutable project context
     * @param entryFile the entry source file (absolute normalized
     *                  lexical path — the same path text the T6
     *                  entry-file seam publishes)
     * @param verbose verbose phase/timing output
     * @param dumpIr produce IR dump files
     * @param sourceMap produce source-map sidecars
     * @param sourceMapExplicit true when {@code --source-map} was
     *                         explicitly requested (distinct from the
     *                         {@code --dump-ir}-derived flag)
     * @param diagnosticsJsonPath the {@code --diagnostics-json} output
     *                            path, or null
     * @param invocation the release-owned compiler invocation
     */
    public CompilationOrchestrator(ProjectContext context, Path entryFile,
                                    boolean verbose, boolean dumpIr,
                                    boolean sourceMap, boolean sourceMapExplicit,
                                    Path diagnosticsJsonPath,
                                    CompilerInvocation invocation) {
        this.invocation = java.util.Objects.requireNonNull(invocation,
            "invocation must not be null");
        this.context = java.util.Objects.requireNonNull(context, "context");
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

    /**
     * The deterministic representable {@code configuredText} of a
     * synthesized root: the root path's final component (no manifest
     * spelling exists for a test-only context, and the text only feeds
     * the T7 representability gates and the compilation's
     * class-identity index — the pre-ISSUE-0269 configured-root-text
     * fallback for a config-less compile kept byte-identical). A
     * degenerate root (filesystem root or a relative input) falls back
     * to the pinned {@code "project"} text.
     */
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
     * point; while the release state is {@code PRE_ACTIVATION} the
     * derived public profile is {@code LEGACY_SAFE_INT} and production
     * SHARED routing stays unreachable, foundation F1/F4). The provider
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
     * @return the immutable invocation record
     */
    public CompilerInvocation invocation() {
        return invocation;
    }

    /**
     * Read-only view of {@link #jvmGeneratedResults}: the backend's
     * generated JVM artifact records of this compile keyed by module
     * source path (ISSUE-0374 profile plumb observability).
     *
     * @return the pass-1 accepted module results; empty before the JVM
     *         codegen phase runs
     */
    public Map<String, JvmBackend.JvmCodegenResult> jvmGeneratedResults() {
        return Collections.unmodifiableMap(jvmGeneratedResults);
    }

    /**
     * The checked-project foundation result of this compile (ISSUE-0288):
     * exactly one checked project input and one project interface index
     * built in dependency order after phase 3 succeeds — {@code null} before the foundation phase runs or when
     * a pre-foundation phase failed. The builder's E6005 diagnostics (a
     * fact defect, never a crash) are also merged into
     * {@link #diagnostics()}.
     *
     * @return the build result, or {@code null} when the foundation phase
     *         did not run
     */
    public CheckedProjectBuildResult checkedProject() {
        return checkedProjectBuild;
    }

    /**
     * The requirement-manifest foundation result of this compile
     * (ISSUE-0289): one {@code SemanticRequirementManifest} per
     * implementation module in dependency order — {@code null} before the
     * manifest phase runs or when a preceding phase failed.
     *
     * @return the manifest result, or {@code null} when the phase did not
     *         run
     */
    public RequirementManifestResult requirementManifests() {
        return requirementManifests;
    }

    /**
     * The route-plan foundation result of this compile (ISSUE-0290):
     * exactly one deterministic {@code ModuleRoutePlan} for the
     * compile's target in dependency order — {@code null} before the
     * route-plan phase runs, when a preceding phase failed, or when the
     * backend is JS (no closed route-plan target exists for JS in this
     * epic; the phase is skipped and the retained JS path is
     * untouched).
     *
     * @return the route-plan result, or {@code null} when the phase did
     *         not run
     */
    public RoutePlanResult routePlan() {
        return routePlan;
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
                Files.writeString(diagnosticsJsonPath,
                    DiagnosticStructuredOutput.toJson(diagnostics));
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

        // Foundation phase (F3/F8): after the checked project and index,
        // LoweringSupport computes exactly one SemanticRequirementManifest
        // per implementation module in dependency order (the closed
        // four-part STDLIB_TIME_CONFLICT trigger + constructCoverage
        // rows). Read-only over the checked facts; its E6005 diagnostics
        // fail the compile exactly like frontend errors.
        log("Phase 3.6: Semantic requirement manifests");
        computeRequirementManifests();
        if (hasErrors) { printDiagnostics(); return false; }

        // Foundation phase (F4/F8): after the manifests, MigrationPlanner
        // produces exactly one deterministic ModuleRoutePlan for the
        // compile's target in dependency order (closed routing rules, the
        // closed E6005-vs-LEGACY-reroute split, plan-time TargetModuleAbi
        // records). Read-only over the checked facts; its E6005
        // diagnostics fail the compile exactly like frontend errors. The
        // JS backend has no closed route-plan target in this epic, so the
        // phase is skipped and the retained JS path is untouched.
        log("Phase 3.7: Route plan");
        planRoutesForCompile();
        if (hasErrors) { printDiagnostics(); return false; }

        // FFI phase (ISSUE-0162, design source
        // deal-v1.2-directives-and-c-ffi-declarations D4/D7/D8): after
        // semantic/graph success, validate every extern-C declaration
        // module (E7002 policy), reject the C FFI on an incapable
        // backend (JVM: E6003 FFI_UNSUPPORTED_BACKEND at @extern-c)
        // before any artifact write, and publish the validated
        // metadata/bundle/bindings on LuaJIT for later runtime loading.
        // Validation never evaluates defaults; a failed validation or
        // an incapable backend publishes no metadata and no artifact.
        log("Phase 3.8: C FFI declaration validation and metadata");
        validateCffiDeclarations();
        if (hasErrors) { printDiagnostics(); return false; }

        log("Phase 4: Code generation");
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
                    ClassDeclaration cd = classDeclarationOf(stmt);
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
     * The class declaration of a top-level statement, direct or
     * exported (v1.2 module top level holds only
     * import/function/class/export).
     */
    private static ClassDeclaration classDeclarationOf(StatementNode stmt) {
        if (stmt instanceof ClassDeclaration cd) {
            return cd;
        }
        if (stmt instanceof ExportDeclaration ed
                && ed.declaration() instanceof ClassDeclaration cd) {
            return cd;
        }
        return null;
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
                    ClassDeclaration cd = classDeclarationOf(stmt);
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

    /**
     * DEAL v1.2 selected-entry rule: when a compiler invocation selects an
     * entry module, that module must export {@code main} with non-async
     * signature {@code (): null}; the backend invokes {@code main()} from
     * that module.
     *
     * <p>Emitted diagnostics (ISSUE-0269 D7 re-registration):</p>
     * <ul>
     *   <li>{@code E2012} — the entry module does not export {@code main}</li>
     *   <li>{@code E2011} — {@code main} exists but is async or does not
     *       have signature {@code (): null}</li>
     * </ul>
     */
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
        // The dependency graph retains each import declaration's span
        // (D5): source path -> (resolved target -> import declaration
        // span), for the E2005 cycle-edge anchor chain.
        Map<String, Map<String, Span>> edgeSpans = new LinkedHashMap<>();
        for (Map.Entry<String, ModuleInfo> entry : modules.entrySet()) {
            String sourcePath = entry.getKey();
            ModuleInfo info = entry.getValue();
            Set<String> imports = new LinkedHashSet<>();
            Map<String, Span> spans = new LinkedHashMap<>();

            for (StatementNode stmt : info.rawAst.statements()) {
                if (stmt instanceof ImportDeclaration imp) {
                    String resolved = resolveImportPath(imp.modulePath(),
                        Path.of(sourcePath), imp.span());
                    if (resolved != null && modules.containsKey(resolved)) {
                        imports.add(resolved);
                        spans.put(resolved, imp.span());
                    }
                }
            }
            deps.put(sourcePath, imports);
            edgeSpans.put(sourcePath, spans);
        }

        List<String> order = new ArrayList<>();
        Set<String> remaining = new LinkedHashSet<>(modules.keySet());

        while (!remaining.isEmpty()) {
            boolean found = false;
            for (Iterator<String> it = remaining.iterator(); it.hasNext(); ) {
                String src = it.next();
                Set<String> imports = deps.get(src);
                if (order.containsAll(imports)) {
                    order.add(src);
                    it.remove();
                    found = true;
                }
            }
            if (!found) {
                return handleCycle(deps, edgeSpans, remaining);
            }
        }
        return order;
    }

    private List<String> handleCycle(Map<String, Set<String>> deps,
                                      Map<String, Map<String, Span>> edgeSpans,
                                      Set<String> remaining) {
        // Find the first cycle
        List<String> cycle = new ArrayList<>();
        String start = remaining.iterator().next();
        Set<String> visited = new HashSet<>();
        Deque<String> stack = new ArrayDeque<>();
        findCycle(deps, start, visited, stack, cycle);
        if (cycle.isEmpty()) {
            cycle.addAll(remaining);
        }

        // Check if the first cycle has runtime dependencies
        if (!isDeclarationOnlyCycle(cycle, deps)) {
            reportCycleError(cycle, edgeSpans);
            return null;
        }

        // First cycle is declaration-only. Now check non-cycle modules
        // for additional cycles (multiple disconnected SCCs).
        Set<String> allCycleNodes = new LinkedHashSet<>(cycle);
        Set<String> nonCycle = new LinkedHashSet<>(remaining);
        nonCycle.removeAll(allCycleNodes);

        // Iteratively find and check additional cycles in the non-cycle set
        List<String> additionalCycle;
        while ((additionalCycle = findCycleInSet(deps, nonCycle)) != null
                && !additionalCycle.isEmpty()) {
            if (!isDeclarationOnlyCycle(additionalCycle, deps)) {
                reportCycleError(additionalCycle, edgeSpans);
                return null;
            }
            allCycleNodes.addAll(additionalCycle);
            nonCycle.removeAll(additionalCycle);
        }

        // All cycles are declaration-only. Build the order.
        List<String> order = new ArrayList<>();

        // Step 1: non-cycle modules with all deps already in order
        boolean progress;
        do {
            progress = false;
            for (Iterator<String> it = nonCycle.iterator(); it.hasNext(); ) {
                String src = it.next();
                Set<String> imports = deps.get(src);
                if (order.containsAll(imports)) {
                    order.add(src);
                    it.remove();
                    progress = true;
                }
            }
        } while (progress);

        // Step 2: all cycle modules
        order.addAll(allCycleNodes);

        // Step 3: non-cycle modules whose deps are now satisfied
        do {
            progress = false;
            for (Iterator<String> it = nonCycle.iterator(); it.hasNext(); ) {
                String src = it.next();
                Set<String> imports = deps.get(src);
                if (order.containsAll(imports)) {
                    order.add(src);
                    it.remove();
                    progress = true;
                }
            }
        } while (progress);

        // Step 4: any remaining modules (deps not fully satisfied)
        if (!nonCycle.isEmpty()) {
            for (String src : nonCycle) {
                order.add(src);
            }
        }

        return order;
    }

    /**
     * Emits the E2005 runtime-cycle diagnostic with the D5 anchor chain:
     * the import declaration span of the first cycle module that targets
     * another cycle member (the dependency graph retains each import
     * declaration's span), falling back to that module's program span,
     * then to the canonical synthetic shape with a cycle-edge-naming
     * anchor note.
     */
    private void reportCycleError(List<String> cycle,
                                  Map<String, Map<String, Span>> edgeSpans) {
        StringBuilder cyclePath = new StringBuilder();
        for (int i = 0; i < cycle.size(); i++) {
            if (i > 0) cyclePath.append(" -> ");
            cyclePath.append(cycle.get(i));
        }
        String message = "Circular import with runtime dependency: " + cyclePath;
        diagnostics.add(e2005Diagnostic(cycle, edgeSpans,
            programSpansFor(cycle), message));
        hasErrors = true;
    }

    /**
     * The module program spans of the given cycle, for the E2005 program
     * span fallback (D5).
     */
    private Map<String, Span> programSpansFor(List<String> cycle) {
        Map<String, Span> spans = new HashMap<>();
        for (String sourcePath : cycle) {
            ModuleInfo info = modules.get(sourcePath);
            if (info != null && info.rawAst != null
                    && info.rawAst.span() != null) {
                spans.put(sourcePath, info.rawAst.span());
            }
        }
        return spans;
    }

    /**
     * Builds the E2005 runtime-cycle diagnostic with the D5 anchor chain
     * (public static so the fallback chain is directly pinnable):
     * <ul>
     *   <li>the import declaration span of the first cycle module that
     *       targets another cycle member,</li>
     *   <li>falling back to that module's program span,</li>
     *   <li>then to the canonical synthetic shape
     *       {@code (file,1,1,1,1,0,0,0,SYNTHETIC)} plus an anchor note
     *       naming the cycle edge
     *       ({@code missing anchor: import declaration closing the module
     *       cycle a -> b -> a}) (D5/D6).</li>
     * </ul>
     */
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
     * Finds a cycle in the given set of modules. Returns the cycle nodes
     * (with duplicates removed via LinkedHashSet), or an empty list if
     * no cycle is found.
     */
    private List<String> findCycleInSet(Map<String, Set<String>> deps,
                                         Set<String> candidates) {
        if (candidates.isEmpty()) return null;

        for (String start : candidates) {
            List<String> cycle = new ArrayList<>();
            Set<String> visited = new HashSet<>();
            Deque<String> stack = new ArrayDeque<>();
            if (findCycleRestricted(deps, start, candidates, visited, stack, cycle)) {
                List<String> unique = new ArrayList<>();
                Set<String> seen = new HashSet<>();
                for (String s : cycle) {
                    if (seen.add(s)) {
                        unique.add(s);
                    }
                }
                return unique;
            }
        }
        return new ArrayList<>();
    }

    private boolean findCycleRestricted(Map<String, Set<String>> deps,
                                         String current, Set<String> allowed,
                                         Set<String> visited, Deque<String> stack,
                                         List<String> cycle) {
        if (stack.contains(current)) {
            boolean found = false;
            for (String s : stack) {
                if (s.equals(current)) found = true;
                if (found) cycle.add(s);
            }
            cycle.add(current);
            return true;
        }
        if (visited.contains(current)) return false;

        visited.add(current);
        stack.addLast(current);

        Set<String> imports = deps.get(current);
        if (imports != null) {
            for (String imp : imports) {
                if (!allowed.contains(imp)) continue;
                if (findCycleRestricted(deps, imp, allowed, visited, stack, cycle))
                    return true;
            }
        }

        stack.removeLast();
        return false;
    }


    private boolean isDeclarationOnlyCycle(List<String> cycle,
                                            Map<String, Set<String>> deps) {
        Set<String> cycleSet = new LinkedHashSet<>(cycle);

        for (String modulePath : cycle) {
            ModuleInfo info = modules.get(modulePath);
            if (info == null || info.rawAst == null) continue;

            for (StatementNode stmt : info.rawAst.statements()) {
                if (stmt instanceof ImportDeclaration imp) {
                    String resolved = resolveImportPath(imp.modulePath(),
                        Path.of(modulePath), imp.span());
                    if (resolved != null && cycleSet.contains(resolved)) {
                        if (usesImportAtRuntime(info.rawAst, imp.alias())) {
                            return false;
                        }
                    }
                }
            }
        }
        return true;
    }

    /**
     * Checks whether a module uses a given import alias at runtime
     * (i.e., in top-level executable statements, not just in type positions).
     *
     * <p><b>Known limitation (v0.6):</b> Indirect runtime dependencies are not
     * detected.  If a module defines a function that uses the cyclic import and
     * then calls that function at the top level, the cycle will be incorrectly
     * classified as declaration-only:
     * <pre>{@code
     *   import * as B from "./b"
     *   function helper(): int { return B.getValue(); }
     *   let x: int = helper();  // indirect runtime use of B — not detected
     * }</pre>
     * A full fix requires data-flow analysis, planned for a future release.
     */
    private boolean usesImportAtRuntime(ProgramNode program, String alias) {
        for (StatementNode stmt : program.statements()) {
            if (hasRuntimeImportUsage(stmt, alias)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasRuntimeImportUsage(StatementNode stmt, String alias) {
        return switch (stmt) {
            case VariableDeclaration vd -> {
                if (vd.initializer() != null
                        && exprReferencesImport(vd.initializer(), alias)) {
                    yield true;
                }
                yield false;
            }
            case ExpressionStatement es -> {
                yield exprReferencesImport(es.expr(), alias);
            }
            case ReturnStatement rs -> {
                if (rs.expr().isPresent()
                        && exprReferencesImport(rs.expr().get(), alias)) {
                    yield true;
                }
                yield false;
            }
            case IfStatement is -> {
                if (exprReferencesImport(is.condition(), alias)) yield true;
                if (hasRuntimeImportUsage(is.thenBlock(), alias)) yield true;
                if (is.elseBranch().isPresent()) {
                    Either<IfStatement, Block> eb = is.elseBranch().get();
                    if (eb instanceof Either.Left<IfStatement, Block> left) {
                        if (hasRuntimeImportUsage(left.value(), alias)) yield true;
                    } else if (eb instanceof Either.Right<IfStatement, Block> right) {
                        if (hasRuntimeImportUsage(right.value(), alias)) yield true;
                    }
                }
                yield false;
            }
            case WhileStatement ws -> {
                if (exprReferencesImport(ws.condition(), alias)) yield true;
                if (hasRuntimeImportUsage(ws.body(), alias)) yield true;
                yield false;
            }
            case ForStatement fs -> {
                if (fs.init().isPresent()) {
                    ForInit init = fs.init().get();
                    if (init instanceof ForInit.VarDecl vd) {
                        if (exprReferencesImport(vd.decl().initializer(), alias)) yield true;
                    } else if (init instanceof ForInit.AssignExpr ie) {
                        AssignmentExpr ae = ie.expr();
                        if (exprReferencesImport(ae.target(), alias)) yield true;
                        if (exprReferencesImport(ae.value(), alias)) yield true;
                    }
                }
                if (fs.condition().isPresent()
                        && exprReferencesImport(fs.condition().get(), alias)) yield true;
                if (fs.update().isPresent()
                        && exprReferencesImport(fs.update().get(), alias)) yield true;
                if (hasRuntimeImportUsage(fs.body(), alias)) yield true;
                yield false;
            }
            case Block b -> {
                for (StatementNode s : b.statements()) {
                    if (hasRuntimeImportUsage(s, alias)) yield true;
                }
                yield false;
            }
            case TryStatement ts -> {
                if (hasRuntimeImportUsage(ts.tryBlock(), alias)) yield true;
                if (ts.catchBlock() != null
                        && hasRuntimeImportUsage(ts.catchBlock(), alias)) yield true;
                yield false;
            }
            case ThrowStatement th -> {
                yield exprReferencesImport(th.expr(), alias);
            }
            case ExportDeclaration ed -> {
                yield hasRuntimeImportUsage(ed.declaration(), alias);
            }
            case FunctionDeclaration fd -> false;
            case ClassDeclaration cd -> {
                // DEAL v1.2: class field defaults evaluate at module
                // initialization, so a default referencing a cyclic import
                // creates a runtime dependency (top-level executable
                // statements no longer exist).
                for (ClassField field : cd.fields()) {
                    if (field.defaultExpr().isPresent()
                            && exprReferencesImport(field.defaultExpr().get(), alias)) {
                        yield true;
                    }
                }
                yield false;
            }
            case ImportDeclaration id -> false;
            case DeleteStatement ds -> false;
            case BreakStatement bs -> false;
            case ContinueStatement cs -> false;
            case ForOfStatement fos -> {
                if (exprReferencesImport(fos.iterable(), alias)) yield true;
                if (hasRuntimeImportUsage(fos.body(), alias)) yield true;
                yield false;
            }
        };
    }

    private boolean exprReferencesImport(ExpressionNode expr, String alias) {
        return switch (expr) {
            case IdentifierExpr id -> id.name().equals(alias);
            case MemberAccessExpr ma -> {
                if (exprReferencesImport(ma.object(), alias)) yield true;
                yield false;
            }
            case CallExpr ce -> {
                if (exprReferencesImport(ce.callee(), alias)) yield true;
                for (ExpressionNode arg : ce.args()) {
                    if (exprReferencesImport(arg, alias)) yield true;
                }
                yield false;
            }
            case BinaryExpr be -> {
                if (exprReferencesImport(be.left(), alias)) yield true;
                if (exprReferencesImport(be.right(), alias)) yield true;
                yield false;
            }
            case UnaryExpr ue -> {
                yield exprReferencesImport(ue.expr(), alias);
            }
            case IndexExpr ie -> {
                if (exprReferencesImport(ie.array(), alias)) yield true;
                if (exprReferencesImport(ie.index(), alias)) yield true;
                yield false;
            }
            case ArrayLiteralExpr al -> {
                for (ExpressionNode e : al.elements()) {
                    if (exprReferencesImport(e, alias)) yield true;
                }
                yield false;
            }
            case ObjectLiteralExpr ol -> {
                for (Property p : ol.properties()) {
                    if (exprReferencesImport(p.value(), alias)) yield true;
                }
                yield false;
            }
            case AssignmentExpr ae -> {
                if (exprReferencesImport(ae.target(), alias)) yield true;
                if (exprReferencesImport(ae.value(), alias)) yield true;
                yield false;
            }
            case HasExpr he -> {
                yield exprReferencesImport(he.object(), alias);
            }
            case FunctionExpr fe -> false;
            case LiteralExpr le -> false;
            case AwaitExpression await -> {
                yield exprReferencesImport(await.callee(), alias);
            }
            case TemplateLiteralExpr tl -> {
                for (ExpressionNode part : tl.parts()) {
                    if (exprReferencesImport(part, alias)) yield true;
                }
                yield false;
            }
        };
    }

    private boolean findCycle(Map<String, Set<String>> deps, String current,
                               Set<String> visited, Deque<String> stack,
                               List<String> cycle) {
        if (stack.contains(current)) {
            boolean found = false;
            for (String s : stack) {
                if (s.equals(current)) found = true;
                if (found) cycle.add(s);
            }
            cycle.add(current);
            return true;
        }
        if (visited.contains(current)) return false;

        visited.add(current);
        stack.addLast(current);

        Set<String> imports = deps.get(current);
        if (imports != null) {
            for (String imp : imports) {
                if (findCycle(deps, imp, visited, stack, cycle)) return true;
            }
        }

        stack.removeLast();
        return false;
    }

    // =========================================================================
    // Phase 3.5: Checked project and interface index (foundation, ISSUE-0288)
    // =========================================================================

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

    /**
     * Runs the requirement-manifest foundation after the checked project
     * and interface index (ISSUE-0289): hands the checked project and the
     * index to {@link LoweringSupport}, which computes one manifest per
     * implementation module in dependency order — the closed four-part
     * {@code STDLIB_TIME_CONFLICT} detector and the reachable-construct
     * coverage rows. Support E6005 diagnostics merge into
     * {@link #diagnostics()} and fail the compile; a failed computation
     * leaves {@link #requirementManifests()} null.
     */
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
     * Runs the route-plan foundation after the manifests (ISSUE-0290):
     * hands the checked project, the interface index, the manifests, the
     * release capability registry, and the compile's target to
     * {@link MigrationPlanner} — one deterministic plan per target over
     * the closed routing rules (F4). Public builds stay all-LEGACY while
     * the release state is {@code PRE_ACTIVATION}; planner E6005
     * diagnostics merge into {@link #diagnostics()} and fail the
     * compile. The JS backend skips the phase: the closed route-plan
     * target axis is {@code LUAJIT|JVM} (foundation F4/F5), and no
     * closed target exists for JS in this epic.
     */
    private void planRoutesForCompile() {
        CheckedProjectBuildResult checked = this.checkedProjectBuild;
        if (checked == null || checked.hasErrors()) {
            return; // a pre-planner phase failure already gates the compile
        }
        if (this.requirementManifests == null || this.requirementManifests.hasErrors()) {
            return; // a pre-planner phase failure already gates the compile
        }
        Target target = switch (this.backend) {
            case LUAJIT -> Target.LUAJIT;
            case JVM -> Target.JVM;
            case JS -> null;
        };
        if (target == null) {
            return; // JS: no closed route-plan target in this epic
        }
        RoutePlanResult result = MigrationPlanner.planRoutes(
            invocation, ReleaseConfiguration.releaseCapabilityRegistry(),
            checked.input(), checked.index(),
            this.requirementManifests.manifests(), target, Set.of());
        this.routePlan = result;
        diagnostics.addAll(result.diagnostics());
        if (result.hasErrors()) {
            hasErrors = true;
            log("  Route planning failed: " + result.diagnostics());
        }
    }

    // =========================================================================
    // Phase 3.8: C FFI declaration validation and metadata (ISSUE-0162)
    // =========================================================================

    /**
     * The read-only view of the validated extern-C metadata: dotted
     * module path &rarr; generated module inputs (descriptor, cdef
     * bundle, retained plans, forward bindings). Populated only for the
     * LuaJIT backend after successful validation; empty for JVM/JS and
     * on any validation failure.
     *
     * @return the generated FFI modules (unmodifiable)
     */
    public Map<String, FfiGeneratedModule> ffiGenerations() {
        return Collections.unmodifiableMap(ffiGenerations);
    }

    /**
     * The extern-C metadata phase: runs after semantic/graph success
     * (phases 0–3.7) and before any artifact write (phase 4).
     *
     * <p>Per extern-C declaration module in the graph:</p>
     * <ol>
     *   <li>validate the declaration policy (E7002: sync functions,
     *       export/C names, ABI parameter/return allowlists, same-file
     *       class references, required/defaulted source-order struct
     *       fields, pointer emptiness/non-constructibility) — never
     *       evaluating defaults;</li>
     *   <li>on an incapable backend (JVM) emit E6003 containing
     *       {@code FFI_UNSUPPORTED_BACKEND} at the {@code @extern-c}
     *       directive range after validation and before artifacts;</li>
     *   <li>on LuaJIT publish the validated immutable descriptor plus
     *       the generated cdef bundle, retained plans, and forward
     *       bindings for later runtime loading.</li>
     * </ol>
     *
     * <p>A failed validation or an incapable backend publishes no
     * metadata and no partial artifact (the compile stops before phase
     * 4). The JS backend keeps its pinned import-site E6003 arm
     * (ISSUE-0169 skeleton) and does not run this phase.</p>
     */
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
                // Defensive: an extern-C module that is not
                // externals-listed has no native library and no public
                // module identity — the E2010 library/manifest policy
                // belongs to the config epic (ISSUE-0111); this phase
                // publishes no metadata for it.
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
                    DiagnosticCode.E6003,
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
            if (backend == Backend.JVM) {
                // JVM use site (ISSUE-0091): emit one .java module class per
                // module. Import resolution and the Lua runtime copies are
                // LuaJIT-specific and skipped here.
                codegenAllJvm();
            } else if (backend == Backend.JS) {
                // JS use site (ISSUE-0247 core slice, js-backend-emitter D3):
                // codegenAllJs() replaces the ISSUE-0189 staging guard with the
                // two-pass emitter plus the runtime/stdlib deployment copies.
                codegenAllJs();
            } else {
                // Lua use site (emitter page D1): the LuaJIT emitter consumes
                // the same per-compilation canonical identity surface the JS
                // arm builds — one identity index over the module-path
                // classification plus the intrinsic builtin Error module.
                // Every descriptor the Lua emitter writes resolves through
                // it; the local legacy dialect producer is retired.
                ModuleIdentityResolver.IdentityIndex identityIndex =
                    buildCanonicalIdentitySurface();
                for (ModuleInfo info : modules.values()) {
                    if (info.isDeclarationFile) continue;
                    codegenLuaModule(info, identityIndex);
                }
                copyRuntimeLibrary();
                copyStdlibModules();
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
     * Lua use site: emits the module via {@link LuaBackend}, with the
     * resolved import map, host-module declarations, and the compilation's
     * canonical descriptor service (emitter page D1 — the same surface
     * codegenAllJs builds and consumes).
     */
    private void codegenLuaModule(ModuleInfo info,
                                  ModuleIdentityResolver.IdentityIndex identityIndex)
            throws IOException {
        long modStart = System.currentTimeMillis();

        Map<String, String> importResolutions = new HashMap<>();
        Map<String, Map<String, Type>> hostModules = new HashMap<>();
        for (StatementNode stmt : info.rawAst.statements()) {
            if (stmt instanceof ImportDeclaration imp) {
                String resolvedSource = resolveImportPath(imp.modulePath(),
                    Path.of(info.sourcePath), imp.span());
                if (resolvedSource != null) {
                    ModuleInfo imported = modules.get(resolvedSource);
                    if (imported != null) {
                        importResolutions.put(imp.modulePath(),
                            imported.modulePath);
                        // Host modules (ISSUE-0082, host-module-abi D5):
                        // declaration files that are not spec stdlib
                        // modules load through __rt.load_host with the
                        // raw import path verbatim as the require key.
                        if (imported.isDeclarationFile
                                && !isSpecStdlibModuleInfo(imported)) {
                            hostModules.put(imp.modulePath(),
                                imported.exports != null
                                    ? imported.exports : Map.of());
                        }
                    }
                }
            }
        }

        // The staging sink (whole-project-artifact-publication D1/D5):
        // the same module-relative path, resolved inside the staging
        // tree. The backend writes the artifact and its sidecar there;
        // the live publication paths are passed only for the sidecar's
        // path-string computation, so the per-invocation stage-tree
        // nonce (on-disk names only) never enters artifact content.
        String filePath = info.modulePath.replace('.', '/') + ".lua";
        Path liveOutputPath = outputRoot.resolve(filePath);
        Path stageOutputPath = stager.stagePath(filePath);

        // v1.2 entry contract: the selected entry module must export a
        // non-async main(): null and the backend invokes main() from that
        // module. Only the LuaJIT entry module gets the entry treatment
        // here — the JVM backend owns its own entry handling (JVM work is
        // out of scope for this slice).
        boolean isEntry = Path.of(info.sourcePath).toAbsolutePath().normalize()
            .equals(entryFile.toAbsolutePath().normalize());

        // Use the result-returning variant to produce both the .lua file
        // and the .deal.map.json sidecar (when --source-map is active),
        // and to surface backend diagnostics: a backend rejection
        // (E6004 entry contract; rest parameters are rejected earlier by
        // the parser with E1047) fails the compilation and writes no
        // artifact, mirroring the JVM backend's
        // no-artifact-on-rejection contract. info.modulePath is the same
        // value seeded into NameResolver, so emitted class identity tags
        // stay byte-identical to the checker's descriptors
        // (runtime-class-identity D2(0)).
        LuaBackend.GenerationResult gen = LuaBackend.generateToFile(
            info.rawAst, info.checkResult, info.sourcePath, info.modulePath,
            stager.stageTree(), stageOutputPath, outputRoot, liveOutputPath,
            sourceMap, importResolutions, hostModules,
            isEntry, identityIndex, invocation.semanticProfile());
        // Native ranged backend list (T12): the backend emits
        // CompilerDiagnostic entries directly, so the orchestrator merge
        // needs no boundary conversion — real spans keep their exact
        // scalar offsets and synthetic anchors keep their notes.
        List<CompilerDiagnostic> backendDiags = gen.diagnostics();
        diagnostics.addAll(backendDiags);
        boolean backendError = backendDiags.stream()
            .anyMatch(d -> "error".equals(d.severity()));
        if (backendError) {
            hasErrors = true;
            log("  LuaJIT backend rejected " + info.modulePath + ": "
                + gen.diagnostics());
            return;
        }

        long modElapsed = System.currentTimeMillis() - modStart;
        log("  Generated: " + liveOutputPath + " (" + modElapsed + "ms)");
    }

    /**
     * JVM use site (ISSUE-0091, ISSUE-0096, ISSUE-0100): emits one {@code
     * <ClassName>.java} module class per module, with the per-module import
     * resolution map (raw import path → imported module path) built exactly
     * like the LuaJIT use site — only non-declaration modules are entries.
     * Imports of declaration files that are not spec stdlib modules become
     * HOST modules (ISSUE-0100, host-module-abi D5): their declared export
     * map flows into the backend's hostModules parameter, which emits the
     * load-time-validated host wrappers. Backend diagnostics (E6000 for
     * out-of-slice constructs — including unsupported host export shapes,
     * at the import statement itself) fail the compilation with
     * the standard diagnostic report; no artifact is written for a module
     * the backend rejects. Class names are derived from the full module
     * path ({@code app/main} → {@code AppMain}), and a collision between
     * two modules mapping to the same class name (e.g. a case-only
     * difference) is an E6000 error — never a silent artifact overwrite.
     */

    /** Per-module JVM import context (ISSUE-0096/ISSUE-0100/ISSUE-0109):
     * import resolutions, imported class declarations, and host-module
     * declarations for one module — the same discovery the JVM use site
     * builds. Shared by the ISSUE-0301 shape-collection pre-pass and the
     * per-module codegen pass. */
    private record JvmImportContext(
            Map<String, String> importResolutions,
            Map<String, Map<String, ClassDeclaration>> importedClasses,
            Map<String, Map<String, Type>> hostModules) {}

    /** Builds the per-module JVM import context for {@code info}. */
    private JvmImportContext jvmImportContextOf(ModuleInfo info) {
        // Import resolutions (ISSUE-0096): raw import path → module
        // path of the imported COMPILED module. Declaration files
        // become host modules (ISSUE-0100). ISSUE-0109: the same
        // discovery pass collects each imported module's class
        // declarations (module path → name → declaration).
        Map<String, String> importResolutions = new HashMap<>();
        Map<String, Map<String, ClassDeclaration>> importedClasses =
            new HashMap<>();
        Map<String, Map<String, Type>> hostModules = new HashMap<>();
        for (StatementNode stmt : info.rawAst.statements()) {
            if (stmt instanceof ImportDeclaration imp) {
                String resolvedSource = resolveImportPath(imp.modulePath(),
                    Path.of(info.sourcePath), imp.span());
                if (resolvedSource != null) {
                    ModuleInfo imported = modules.get(resolvedSource);
                    if (imported == null) {
                        continue;
                    }
                    if (imported.isDeclarationFile) {
                        if (!isSpecStdlibModuleInfo(imported)) {
                            hostModules.put(imp.modulePath(),
                                imported.exports != null
                                    ? imported.exports : Map.of());
                        }
                    } else {
                        importResolutions.put(imp.modulePath(),
                            imported.modulePath);
                        Map<String, ClassDeclaration> classes =
                            new LinkedHashMap<>();
                        for (StatementNode importedStmt
                                : imported.rawAst.statements()) {
                            ClassDeclaration cd = null;
                            if (importedStmt instanceof ClassDeclaration c) {
                                cd = c;
                            } else if (importedStmt instanceof ExportDeclaration ed
                                    && ed.declaration() instanceof ClassDeclaration c) {
                                cd = c;
                            }
                            if (cd != null) {
                                classes.putIfAbsent(cd.name(), cd);
                            }
                        }
                        importedClasses.put(imported.modulePath, classes);
                    }
                }
            }
        }
        return new JvmImportContext(importResolutions, importedClasses,
            hostModules);
    }

    private void codegenAllJvm() throws IOException {
        if (sourceMapExplicit) {
            // Source-map sidecars (.deal.map.json) are produced only by the
            // LuaJIT emitter; surface that to the CLI user instead of
            // silently producing no sidecars (ISSUE-0091 rework round 3).
            // Fired only when --source-map was explicitly requested: a
            // --dump-ir-derived sourceMap flag (IR hardening enables source
            // maps with dumps) must not print the warning.
            System.err.println("Warning: --source-map produces no source-map "
                + "sidecars with the JVM backend (source maps are "
                + "LuaJIT-only)");
        }
        // Canonical identity surface (canonical identity carriage,
        // descriptor-identity-propagation D1/D2): the ONE
        // per-compilation identity index over the module-path
        // classification — the same surface the checker consumed — so
        // every class descriptor the JVM backend emits resolves through
        // {@code index.descriptorTextFor(identity)} byte-for-byte from
        // the classified identities (no second Type-to-text producer
        // exists).
        ModuleIdentityResolver.IdentityIndex identityIndex =
            buildCanonicalIdentitySurface();
        // Compilation-wide class-declaration identity surface (ISSUE-0301
        // D4 shared-scope emission): canonical class identity → declaring
        // module path for every class of every checked project module.
        // The entry module's shared-scope wrapper pre-registration
        // resolves a collected shape's class reference through this map
        // when the declaring module is one the entry does not import
        // directly (its own importAliases/importedClasses maps cannot
        // name it), so the wrapper's invoke signature always references
        // the real declaring module's emitted class. Deterministic:
        // module order, then source order per module; the first
        // declaration of an identity wins. Host declarations are
        // excluded (their class exports keep the host ABI lane's
        // import-time E6000s, and the union filter below drops
        // host-referencing shapes before any module pre-registers
        // them).
        Map<CanonicalClassIdentity, String> classDeclaringModules =
            new LinkedHashMap<>();
        for (ModuleInfo info : modules.values()) {
            if (info.isDeclarationFile) continue;
            CanonicalModuleIdentity moduleIdentity =
                classifyModuleIdentity(info);
            if (moduleIdentity == null) continue;
            for (StatementNode stmt : info.rawAst.statements()) {
                ClassDeclaration cd = null;
                if (stmt instanceof ClassDeclaration c) {
                    cd = c;
                } else if (stmt instanceof ExportDeclaration ed
                        && ed.declaration() instanceof ClassDeclaration c) {
                    cd = c;
                }
                if (cd != null) {
                    classDeclaringModules.putIfAbsent(
                        new CanonicalClassIdentity(moduleIdentity,
                            cd.name()),
                        info.modulePath);
                }
            }
        }
        // Pre-codegen collection pass (ISSUE-0301 D4, the shared
        // runtime value surface): walk every checked module's types and
        // collect the closed project-wide shape set — function-signature
        // shapes, nested/function/bytes array element shapes — so the
        // selected entry module's shared $DealRt scope carries every
        // shape any module references. Deterministic: module dependency
        // order (the modules map), then source order per module.
        //
        // Host-class-typed shapes never enter the union: the project's
        // host modules (the raw specifiers any module imports) name
        // declarations whose class exports keep their import-time
        // E6000s (the host ABI lane's carriers), so a shared-scope
        // wrapper referencing a never-emitted host Java class must not
        // be pre-registered by the entry's emission.
        Set<String> projectHostPaths = new LinkedHashSet<>();
        for (ModuleInfo info : modules.values()) {
            if (info.isDeclarationFile) continue;
            projectHostPaths.addAll(jvmImportContextOf(info)
                .hostModules.keySet());
        }
        List<Type> sharedShapes = new ArrayList<>();
        Set<Type> seenShapes = new LinkedHashSet<>();
        for (ModuleInfo info : modules.values()) {
            if (info.isDeclarationFile) continue;
            JvmImportContext ctx = jvmImportContextOf(info);
            for (Type shape : JvmBackend.collectShapes(info.rawAst,
                    info.checkResult, info.sourcePath, info.modulePath,
                    ctx.importResolutions, ctx.importedClasses,
                    ctx.hostModules, invocation.semanticProfile(),
                    identityIndex, identityIndex.moduleIdentityLookup())) {
                if (JvmBackend.shapeReferencesHostModule(shape,
                        projectHostPaths)) {
                    continue;
                }
                if (seenShapes.add(shape)) {
                    sharedShapes.add(shape);
                }
            }
        }
        List<Type> projectShapes = List.copyOf(sharedShapes);

        // Pass 1: generate every module and merge diagnostics. Rejected
        // modules write no artifact.
        List<ModuleInfo> cleanModules = new ArrayList<>();
        Map<ModuleInfo, JvmBackend.JvmCodegenResult> results = new LinkedHashMap<>();
        for (ModuleInfo info : modules.values()) {
            if (info.isDeclarationFile) continue;
            JvmImportContext ctx = jvmImportContextOf(info);
            boolean isEntry = info.sourcePath.equals(entryFile.toString());
            // ISSUE-0374 profile plumb: the backend derives its
            // backend-wide int mode from the invocation's project-wide
            // semantic profile — never from a static flag, a system
            // property, or any source/CLI/environment surface.
            // v1.2 identity carriage (descriptor-identity-propagation
            // D1/D2): the compilation's identity index and module-path
            // classification flow in; every class descriptor the backend
            // emits resolves through index.descriptorTextFor(identity).
            JvmBackend.JvmCodegenResult res = JvmBackend.generate(
                info.rawAst, info.checkResult, info.sourcePath, info.modulePath,
                ctx.importResolutions, ctx.importedClasses, ctx.hostModules,
                isEntry, isEntry, identityIndex,
                identityIndex.moduleIdentityLookup(),
                invocation.semanticProfile(), projectShapes,
                classDeclaringModules);
            for (CompilerDiagnostic d : res.diagnostics()) {
                diagnostics.add(d);
                hasErrors = true;
            }
            if (res.hasErrors()) {
                log("  JVM backend rejected " + info.modulePath + ": "
                    + res.diagnostics());
                continue;
            }
            cleanModules.add(info);
            results.put(info, res);
            jvmGeneratedResults.put(info.sourcePath, res);
        }

        // Pass 2: write artifacts for clean modules, rejecting class-name
        // collisions instead of silently overwriting an earlier module's
        // artifact.
        Map<String, String> classOwners = new LinkedHashMap<>();
        for (ModuleInfo info : cleanModules) {
            JvmBackend.JvmCodegenResult res = results.get(info);
            String className = res.className();
            String previousOwner = classOwners.putIfAbsent(className, info.modulePath);
            if (previousOwner != null) {
                // Anchorless site (D5/D6): the note names the colliding
                // modules and class name.
                syntheticError(DiagnosticCode.E6000,
                    "JVM backend: modules '" + previousOwner + "' and '"
                        + info.modulePath + "' both derive the class name '"
                        + className + "' (rename one module)",
                    info.sourcePath,
                    "missing anchor: module class-name collision between '"
                        + previousOwner + "' and '" + info.modulePath
                        + "' (class '" + className + "')");
                continue;
            }
            // The staging sink (whole-project-artifact-publication
            // D1/D5): the same module-relative path inside the staging
            // tree; the live root is written only by the publish step.
            Path stageOutputPath = stager.stagePath(className + ".java");
            Files.writeString(stageOutputPath, res.source());
            log("  Generated: " + outputRoot.resolve(className + ".java"));
        }
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

    /**
     * JS use site (ISSUE-0247 core slice, js-backend-emitter D3): the JVM
     * two-pass model — pass 1 generates every module and merges
     * diagnostics, pass 2 writes one {@code <modulePath with '/' for
     * '.'>.js} artifact per clean module — plus the LuaJIT
     * deployment-copy precedent (copyJsRuntimeLibrary/
     * copyStdlibJsModules). The per-module import classification mirrors
     * the LuaJIT use site (codegenLuaModule): a resolved non-declaration
     * module is a project import, a resolved declaration file that is not
     * a spec stdlib module is a host module. Backend diagnostics (E6004
     * for an invalid entry module main at this slice; later slices add
     * the E6000/E6003 rejection table) fail the compilation with the
     * standard report; no artifact is written for a rejected module. The
     * dot→slash artifact mapping is injective over the module-path
     * domain, so no class-name-collision gate is needed (unlike
     * codegenAllJvm's). The deployment copies run unconditionally at the
     * end of phase 4 (Lua deployment parity). js-v12-source-maps D1:
     * the explicit --source-map warning retired — the effective
     * sourceMap flag (explicit or --dump-ir-derived) drives the
     * per-module .deal.map.json sidecar writes in pass 2 instead.
     */
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
            // Import classification (the LuaJIT use-site shape,
            // codegenLuaModule): raw import path → module path of the
            // imported COMPILED module; declaration files that are not
            // spec stdlib modules become host modules with their declared
            // export map.
            Map<String, String> importResolutions = new HashMap<>();
            // Host modules (ISSUE-0328, js-v12-host-abi-completion D1):
            // the raw import path carries the declared export map PLUS
            // the declaration AST's class-field records with their
            // orchestrator-resolved field types — the emitter renders
            // the loadHost declared map (functions as canonical
            // declared descriptors, classes as the canonical identity
            // plus the field-descriptor array) from this record.
            Map<String, HostModuleDeclarations> hostModules =
                new HashMap<>();
            // Extern-C imports (fixed-name-directive-events D9): raw
            // import paths whose resolved module is a declaration file
            // with effective FileDirectives.externC — the re-keyed JS
            // E6003 FFI_UNSUPPORTED_BACKEND arm keys on this set at the
            // import statement.
            Set<String> externCImports = new HashSet<>();
            if (info.rawAst != null) {
                for (StatementNode stmt : info.rawAst.statements()) {
                    if (stmt instanceof ImportDeclaration imp) {
                        String resolvedSource = resolveImportPath(imp.modulePath(),
                            Path.of(info.sourcePath), imp.span());
                        if (resolvedSource != null) {
                            ModuleInfo imported = modules.get(resolvedSource);
                            if (imported != null) {
                                importResolutions.put(imp.modulePath(),
                                    imported.modulePath);
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
            }
            boolean isEntry = info.sourcePath.equals(entryFile.toString());
            // js-v12-source-maps D2: the effective sourceMap flag
            // (explicit --source-map or --dump-ir-derived) attaches a
            // mapping recorder to the generation — the optional
            // SourceMapGenerator parameter (the Lua generateResult
            // precedent). A rejected module never serializes its
            // recorder: pass 2 runs for clean modules only.
            // ISSUE-0374 profile plumb (js-v12-int32-bytes D2): the
            // backend derives its backend-wide int mode from the
            // invocation's project-wide semantic profile — never from a
            // static flag, a system property, or any source/CLI/
            // environment surface. Under DEAL_V1_2_INT32 every emitted
            // module calls $rt.setInt32Mode(true) immediately after the
            // runtime $require; LEGACY_SAFE_INT emits no selector.
            JsBackend.JsCodegenResult res = JsBackend.generate(
                info.rawAst, info.checkResult, info.sourcePath, info.modulePath,
                importResolutions, hostModules, externCImports, isEntry,
                identityIndex, identityIndex.moduleIdentityLookup(),
                sourceMap ? new SourceMapGenerator() : null,
                invocation.semanticProfile());
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

    /**
     * The ISSUE-0328 host-module declaration record for one imported
     * declaration file (js-v12-host-abi-completion D1): the declared
     * export map plus, per class export name, the declaration AST's
     * {@link ClassField} records with their resolved declared types.
     * A declaration file never runs the phase-3 name-resolver pass, so
     * the field types resolve structurally through an
     * {@link ExportExtractor} over the declaration's own AST — the
     * same resolution the declaration's export signatures use — with
     * the declaration's import aliases mapped exactly like the
     * phase-1 extraction. A same-module class field type (e.g.
     * {@code endpoint: Endpoint} in {@code cfg.d.deal}) therefore
     * resolves to the declaring module's class identity, which the
     * JS emitter's descriptor service projects to the canonical
     * {@code @$external/&lt;specifier&gt;/&lt;ClassName&gt;} atom.
     */
    private HostModuleDeclarations hostDeclarationsOf(
            ModuleInfo imported) {
        Map<String, Type> declaredExports = imported.exports != null
            ? imported.exports : Map.of();
        Map<String, List<HostModuleDeclarations.HostField>>
            classFields = new LinkedHashMap<>();

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
            imported.modulePath, true);
        extractor.setImportModulePaths(importAliasMap);
        extractor.extract(imported.rawAst);
        for (StatementNode stmt : imported.rawAst.statements()) {
            ClassDeclaration cd = null;
            if (stmt instanceof ClassDeclaration c) {
                cd = c;
            } else if (stmt instanceof ExportDeclaration ed
                    && ed.declaration() instanceof ClassDeclaration c) {
                cd = c;
            }
            if (cd == null) {
                continue;
            }
            List<HostModuleDeclarations.HostField> fields =
                new ArrayList<>();
            for (ClassField cf : cd.fields()) {
                fields.add(new HostModuleDeclarations.HostField(
                    cf, extractor.resolveFieldType(cf.type())));
            }
            classFields.putIfAbsent(cd.name(), fields);
        }
        return new HostModuleDeclarations(declaredExports,
            classFields);
    }

    /**
     * The source/generated path pair passed to
     * {@link SourceMapGenerator#toJson} for a JS sidecar — the exact
     * strings the Lua arm passes (LuaBackend.generateToFile's
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

    /**
     * True when the module is a spec-listed stdlib declaration module:
     * its published classification is
     * {@link CanonicalModuleIdentity.BuiltinModule} — the file-keyed
     * stdlib predicate over the six pinned declaration files under the
     * resolved {@code ProjectContext.stdlibSurfacePath} (D6 (1);
     * ISSUE-0269). Stdlib imports stay on the trusted raw-require path
     * (ISSUE-0082, host-module-abi D5(5)); a same-named file outside
     * the pinned surface is not builtin.
     */
    private boolean isSpecStdlibModuleInfo(ModuleInfo info) {
        return info.location.moduleClassification()
            instanceof CanonicalModuleIdentity.BuiltinModule;
    }

    private void copyRuntimeLibrary() throws IOException {
        // Whole-set semantics (whole-project-artifact-publication D3/D6):
        // the runtime copy always stages fresh from the resolved
        // distribution surface — the pinned three-tier order
        // (ISSUE-0457, D3): classpath resources, then the DEAL_HOME
        // filesystem layout, then the checkout CWD dev fallback
        // (no project-local location is pinned for the runtime) — and
        // the whole-set swap replaces any earlier bytes; no skip of an
        // existing destination remains.
        Optional<DistributionHome.ResolvedSource> runtime =
            stager.stageRuntimeCopy("deal/runtime.lua", distributionHome);
        if (runtime.isPresent()) {
            log("  Copied runtime: " + outputRoot.resolve("deal/runtime.lua")
                + " (" + runtime.get().tier() + ")");
            return;
        }

        // Anchorless site (D5/D6): the note names the missing runtime
        // library path.
        syntheticError(DiagnosticCode.E6000,
            "Runtime library not found: deal/runtime.lua", "",
            "missing anchor: runtime library path 'deal/runtime.lua'");
    }

    /**
     * Copies the spec-listed stdlib .lua implementation files to the
     * output. The module list is derived from the 6 spec-listed stdlib
     * modules. Each copy source resolves through {@link
     * DistributionHome} in the pinned three-tier order (ISSUE-0457,
     * {@code release-distribution-packaging-and-discovery} D3) —
     * project-local surface first, then the language distribution
     * (classpath resources, then the {@code DEAL_HOME} filesystem
     * layout), then the checkout CWD dev fallback — so a v1.2 project
     * shipping a local {@code std/} override stages its own bytes and
     * an out-of-checkout compile stages the distribution's bytes. A
     * module absent at every tier is skipped silently (unchanged).
     */
    private void copyStdlibModules() throws IOException {
        for (String stdlibModule : StdlibModuleResolver.SPEC_STDLIB_MODULES) {
            // Whole-set semantics (whole-project-artifact-publication
            // D3/D6): each stdlib copy stages fresh from the resolved
            // distribution surface — project-local surface first, then
            // the language distribution, then the checkout CWD dev
            // fallback — so a project-local std/ override stages its
            // own bytes and no skip of an existing destination remains.
            String name = stdlibModule.substring("std/".length());
            Optional<DistributionHome.ResolvedSource> source =
                stager.stageStdlibCopy(name, "lua", distributionHome);
            if (source.isEmpty()) {
                continue;
            }
            log("  Copied stdlib: " + stdlibModule + " ("
                + source.get().tier() + ")");
        }
    }

    /**
     * JS deployment copy (js-backend-emitter D10): copies deal/runtime.js
     * to <output>/deal/runtime.js — the resolved distribution surface,
     * else E6000 — staged fresh every compile (whole-set semantics; no
     * destination skip). The mirror of copyRuntimeLibrary with the .js
     * spelling.
     */
    private void copyJsRuntimeLibrary() throws IOException {
        // Whole-set semantics (whole-project-artifact-publication D3/D6):
        // the runtime copy always stages fresh from the resolved
        // distribution surface — the pinned three-tier order
        // (ISSUE-0457, D3) — and the whole-set swap replaces any
        // earlier bytes; no skip of an existing destination remains.
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

    /**
     * Copies the spec-listed stdlib .js implementation files to the
     * output (the module list is derived from the 6 spec-listed stdlib
     * modules). Each copy source resolves through {@link
     * DistributionHome} in the pinned three-tier order (ISSUE-0457) —
     * project-local surface first, then the language distribution
     * (classpath resources, then the {@code DEAL_HOME} filesystem
     * layout), then the checkout CWD dev fallback; a missing source for
     * a module is skipped silently. Staged fresh every compile
     * (whole-set semantics). The mirror of copyStdlibModules with the
     * .js spelling (js-backend-emitter D10).
     */
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
     * @return the resolved source path, or {@code null} if not found
     */
    /**
     * Resolves an import path to a source file through the T6 resolver
     * (the pinned five rules — no legacy buildCandidates, no CWD
     * bare-lookup fallback, no classpath module fallback). A failed
     * resolution merges its E2003/E2009 diagnostic at the canonical
     * synthetic shape plus anchor note when no import declaration span
     * is available.
     *
     * @return the resolved source path (the location's
     *         {@code normalizedSourcePath}), or {@code null} if not found
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

    /**
     * The canonical public module identity of one compiled module, per
     * the identity layer's classification (design source
     * {@code strict-project-context-resolution-identity} D6):
     * externals-listed declarations carry
     * {@code ExternalModule(rawImportSpecifier)} (the manifest key
     * exactly as written), spec stdlib modules carry
     * {@code BuiltinModule}, and a configured root-contained source
     * module carries {@code ProjectModule(configuredRootText,
     * relativeModuleComponents)} — the T6 resolver's file-keyed
     * classification published on the module's
     * {@link SourceModuleLocation} (ISSUE-0269; the legacy
     * specifier-keyed externalsDeclarations/externalsModulePaths maps
     * and the lossy bestRootIndex computation are retired).
     * {@code null} means the module has no public identity (an
     * out-of-root relative source): class-free code stays valid, and a
     * class there fails closed at descriptor production.
     */
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
     * Delegates printing to the canonical formatter (D8); the
     * error/warning summary counts are unchanged.
     */
    private void printDiagnostics() {
        for (CompilerDiagnostic d : diagnostics) {
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
        /**
         * The lazily built name resolvers over declaration-file modules
         * (keyed by dotted module path): declaration files skip the
         * phase-3 name-resolution pass, so a cross-module class-field
         * type annotation declared by a host declaration resolves
         * through a resolver built on demand over the declaration's own
         * AST (ISSUE-0328 — the frontend's shared host-class symbol
         * synthesis). Each resolver is cached before its
         * {@link NameResolver#resolve} completes so declaration-only
         * import cycles terminate.
         */
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
                throws ModuleNotFoundException {
            // Direct internal-name match first: the checker passes dotted
            // module paths carried by Type.Class values (e.g. an
            // externals module's "host.x\y"), which are internal names,
            // never import specifiers.
            for (ModuleInfo info : modules.values()) {
                if (info.modulePath.equals(modulePath)) {
                    return info.exports != null ? info.exports : Map.of();
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
                    return target.exports != null ? target.exports : Map.of();
                }
            }
            throw new ModuleNotFoundException("Module not found: " + modulePath);
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
                    // Host declaration synthesis (ISSUE-0328,
                    // js-v12-host-abi-completion D3): a declaration
                    // file carries no symbol table, so its declared
                    // classes synthesize as ClassSymbols straight from
                    // the declaration AST — DEAL-side construction and
                    // field reads of a declared host class type-check
                    // against the declaration's field records, and the
                    // checker's cross-module field-type resolution runs
                    // against the declaring module's own context (the
                    // resolveTypeNodeInModule override below).
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
                    // Declaration-file owner (ISSUE-0328): resolve
                    // through the lazily built resolver over the
                    // declaration's own AST, so a bare same-module
                    // class name inside a host class field type
                    // resolves against the declaring module — never
                    // against the importing module's scope.
                    Type resolved = declarationResolver(info)
                        .resolveTypeNode(typeNode);
                    return resolved == Type.Error.INSTANCE
                        ? null : resolved;
                }
            }
            return null;
        }

        /**
         * The cached name resolver over one declaration-file module,
         * built on demand (ISSUE-0328 host-class symbol synthesis).
         * The resolver is cached before its resolve completes so
         * declaration-only import cycles terminate; its own import
         * resolution runs through this same module resolver.
         */
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
