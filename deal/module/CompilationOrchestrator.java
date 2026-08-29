package deal.module;

import deal.ast.*;
import deal.checker.*;
import deal.codegen.Backend;
import deal.codegen.SourceMapGenerator;
import deal.codegen.jvm.JvmBackend;
import deal.codegen.js.JsBackend;
import deal.codegen.lua.LuaBackend;
import deal.ir.IrDumper;
import deal.lexer.*;
import deal.diagnostics.CompilerDiagnostic;
import deal.diagnostics.DiagnosticFormatter;
import deal.diagnostics.DiagnosticRange;
import deal.diagnostics.DiagnosticStructuredOutput;
import deal.identity.CanonicalModuleIdentity;
import deal.identity.ProjectModuleIdentity;
import deal.parser.*;
import deal.semantic.CapabilityRegistry;
import deal.semantic.CheckedProjectBuildResult;
import deal.semantic.CheckedProjectBuilder;
import deal.semantic.CompilerInvocation;
import deal.semantic.CompilerProfileProvider;
import deal.semantic.LoweringSupport;
import deal.semantic.MigrationPlanner;
import deal.semantic.ModuleFact;
import deal.semantic.RequirementManifestResult;
import deal.semantic.RoutePlanResult;
import deal.semantic.Target;
import deal.semantic.ir.ModuleId;
import deal.semantic.ir.ReleaseState;
import deal.types.Type;
import deal.types.Types;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import deal.diagnostics.DiagnosticCode;

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
    private final List<Path> moduleRoots;
    /**
     * The configured root texts, aligned index-wise with
     * {@link #moduleRoots}: the manifest-spelled root text when the
     * configuration carries one, else the resolved root path's final
     * component (the implicit project root).  Consumed by the
     * canonical identity classification of {@link #codegenAllJs()}
     * (js-v12-completion-architecture D3).
     */
    private final List<String> configuredRootTexts;
    private final Path stdlibDir;

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

    // Host externals (ISSUE-0082, host-module-abi D5):
    // - externalsDeclarations: raw import path as written → absolute
    //   declaration source path (manifest-relative resolution).  The
    //   declaration is authoritative for that name — on-disk candidates are
    //   not consulted.
    // - externalsModulePaths: declaration source path → dotted module path
    //   (the externals key with '/' → '.' — the typing/class-identity name,
    //   e.g. "host.cfg"; the require path stays the raw key "host/cfg").
    private final Map<String, String> externalsDeclarations;
    private final Map<String, String> externalsModulePaths;

    private static final class ModuleInfo {
        final String sourcePath;
        final String modulePath;
        final boolean isDeclarationFile;
        ProgramNode rawAst;
        Map<String, Type> exports;
        SymbolTable symbolTable;
        CheckResult checkResult;
        ParseResult parseResult;
        NameResolver nameResolver;

        ModuleInfo(String sourcePath, String modulePath, boolean isDeclarationFile) {
            this.sourcePath = sourcePath;
            this.modulePath = modulePath;
            this.isDeclarationFile = isDeclarationFile;
        }
    }

    public CompilationOrchestrator(Path entryFile, Path outputRoot, boolean verbose,
                                    DealConfig config, List<Path> moduleRoots,
                                    Path stdlibDir) {
        this(entryFile, outputRoot, verbose, false, false, Backend.LUAJIT,
            config, moduleRoots, stdlibDir);
    }

    public CompilationOrchestrator(Path entryFile, Path outputRoot, boolean verbose,
                                    boolean dumpIr,
                                    DealConfig config, List<Path> moduleRoots,
                                    Path stdlibDir) {
        this(entryFile, outputRoot, verbose, dumpIr, false, Backend.LUAJIT,
            config, moduleRoots, stdlibDir);
    }

    public CompilationOrchestrator(Path entryFile, Path outputRoot, boolean verbose,
                                    boolean dumpIr, boolean sourceMap,
                                    DealConfig config, List<Path> moduleRoots,
                                    Path stdlibDir) {
        this(entryFile, outputRoot, verbose, dumpIr, sourceMap, Backend.LUAJIT,
            config, moduleRoots, stdlibDir);
    }

    /**
     * Backend-selection entry point (ISSUE-0091). LuaJIT remains the default:
     * all overloads above delegate with {@link Backend#LUAJIT}.
     *
     * <p>This overload treats {@code sourceMap} as the explicit request
     * (pre-ISSUE-0091 callers pass the single source-map flag).
     *
     * @param backend the code-generation backend ({@code lua}/{@code luajit}
     *                or {@code jvm}) selected by the CLI or {@code deal.json}
     */
    public CompilationOrchestrator(Path entryFile, Path outputRoot, boolean verbose,
                                    boolean dumpIr, boolean sourceMap, Backend backend,
                                    DealConfig config, List<Path> moduleRoots,
                                    Path stdlibDir) {
        this(entryFile, outputRoot, verbose, dumpIr, sourceMap, sourceMap,
            backend, config, moduleRoots, stdlibDir);
    }

    /**
     * Backend-selection entry point (ISSUE-0091). LuaJIT remains the default:
     * all overloads above delegate with {@link Backend#LUAJIT}.
     *
     * @param backend the code-generation backend ({@code lua}/{@code luajit}
     *                or {@code jvm}) selected by the CLI or {@code deal.json}
     * @param sourceMapExplicit true when {@code --source-map} was explicitly
     *                requested; {@code sourceMap} is the effective flag
     *                (also derived from {@code --dump-ir})
     */
    public CompilationOrchestrator(Path entryFile, Path outputRoot, boolean verbose,
                                    boolean dumpIr, boolean sourceMap,
                                    boolean sourceMapExplicit, Backend backend,
                                    DealConfig config, List<Path> moduleRoots,
                                    Path stdlibDir) {
        this(entryFile, outputRoot, verbose, dumpIr, sourceMap,
            sourceMapExplicit, backend, config, moduleRoots, stdlibDir, null,
            defaultInvocation());
    }

    /**
     * Full entry point with the structured-output path (D8): when
     * {@code diagnosticsJsonPath} is non-null, {@link #compile()} writes
     * the {@link DiagnosticStructuredOutput} document for every
     * compilation — successful or failed — without changing the exit
     * code. A write failure is a deterministic compiler I/O diagnostic on
     * stderr with exit 1.
     */
    public CompilationOrchestrator(Path entryFile, Path outputRoot, boolean verbose,
                                    boolean dumpIr, boolean sourceMap,
                                    boolean sourceMapExplicit, Backend backend,
                                    DealConfig config, List<Path> moduleRoots,
                                    Path stdlibDir, Path diagnosticsJsonPath,
                                    CompilerInvocation invocation) {
        this.invocation = java.util.Objects.requireNonNull(invocation,
            "invocation must not be null");
        this.backend = backend;
        this.entryFile = entryFile.toAbsolutePath().normalize();
        this.outputRoot = outputRoot.toAbsolutePath().normalize();
        this.verbose = verbose;
        this.dumpIr = dumpIr;
        this.sourceMap = sourceMap;
        this.sourceMapExplicit = sourceMapExplicit;
        this.moduleRoots = moduleRoots;
        List<String> rootTexts = new ArrayList<>(moduleRoots.size());
        for (int i = 0; i < moduleRoots.size(); i++) {
            String text = null;
            if (config != null && config.moduleRoots() != null
                    && i < config.moduleRoots().size()) {
                text = config.moduleRoots().get(i);
            }
            if (text == null || text.isEmpty()) {
                Path fileName = moduleRoots.get(i).getFileName();
                text = fileName != null ? fileName.toString() : "";
            }
            rootTexts.add(text);
        }
        this.configuredRootTexts = List.copyOf(rootTexts);
        this.stdlibDir = stdlibDir;
        this.diagnosticsJsonPath = diagnosticsJsonPath;

        Map<String, String> declarations = new HashMap<>();
        Map<String, String> modulePaths = new HashMap<>();
        if (config != null) {
            Path manifestDir = config.configFile() != null
                ? config.configFile().toAbsolutePath().normalize().getParent()
                : null;
            for (Map.Entry<String, String> entry : config.externals().entrySet()) {
                Path declaration = Path.of(entry.getValue());
                if (manifestDir != null) {
                    declaration = manifestDir.resolve(declaration);
                }
                String sourcePath = declaration.normalize().toString();
                declarations.put(entry.getKey(), sourcePath);
                modulePaths.put(sourcePath, entry.getKey().replace('/', '.'));
            }
        }
        this.externalsDeclarations = Map.copyOf(declarations);
        this.externalsModulePaths = Map.copyOf(modulePaths);
    }

    /**
     * The default release-owned invocation used by every constructor that
     * is not given one explicitly: {@code PUBLIC_BUILD} resolved through
     * {@link CompilerProfileProvider} with this epic's release state
     * {@code PRE_ACTIVATION} (the derived public profile is
     * {@code LEGACY_SAFE_INT} and production SHARED routing stays
     * unreachable, foundation F1/F4). The provider is the only
     * invocation constructor — the orchestrator never constructs an
     * invocation itself.
     */
    private static CompilerInvocation defaultInvocation() {
        return CompilerProfileProvider.resolve(ReleaseState.PRE_ACTIVATION,
            CapabilityRegistry.releaseRegistry());
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
        boolean success = compileInternal();

        // Structured output (D8): the document is written for every
        // compilation — successful or failed — when --diagnostics-json
        // was requested, without changing the exit code. A write failure
        // is a deterministic compiler I/O diagnostic on stderr with exit
        // 1; no raw path exception escapes (parent D11 I/O discipline).
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

            Path file = Path.of(sourcePath);
            if (!Files.exists(file)) {
                // Anchorless site (D5): the discovery queue holds no
                // import declaration for the entry/nonexistent file, so
                // the diagnostic is synthetic with a note naming the
                // unresolved path.
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

            Parser parser = new Parser(lex.tokens(), sourcePath);
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

            // Externals-listed host declarations carry the dotted externals
            // key as their typing/class-identity module path (e.g. "host.cfg"
            // for the raw import path "host/cfg").
            String externalsModulePath = externalsModulePaths.get(sourcePath);
            String modulePath = externalsModulePath != null
                ? externalsModulePath : computeModulePath(file);
            ModuleInfo info = new ModuleInfo(sourcePath, modulePath, isDecl);
            info.rawAst = parseResult.program();
            info.parseResult = parseResult;
            modules.put(sourcePath, info);

            long modElapsed = System.currentTimeMillis() - modStart;
            log("  Parsed: " + sourcePath + " (" + modElapsed + "ms)");

            // Process imports for discovery.  Use tryResolveImportPath to
            // avoid emitting E2003 here — we will emit it in Phase 3 where
            // we have precise source locations (or here with the import span).
            for (StatementNode stmt : parseResult.program().statements()) {
                if (stmt instanceof ImportDeclaration imp) {
                    String importPath = imp.modulePath();
                    String resolved = tryResolveImportPath(importPath, file);
                    if (resolved == null) {
                        // Record E2003 with the import statement's span for
                        // accurate error location (D5).
                        error(DiagnosticCode.E2003,
                            "Module not found: '" + importPath
                                + "'. Searched in: " + describeSearchPaths(importPath, file)
                                + externalsDeclarationNote(importPath),
                            imp.span());
                    } else if (isUndeclaredExternalHostModule(importPath, resolved)) {
                        // E2009: a bare import whose resolution lands on a
                        // non-stdlib declaration file that is not listed in
                        // deal.json externals (host-module-abi D5(3));
                        // anchored at the import declaration span (D5).
                        error(DiagnosticCode.E2009,
                            "Import of external host module '" + importPath
                                + "' is not declared in deal.json externals",
                            imp.span());
                    } else if (!modules.containsKey(resolved)) {
                        pending.add(resolved);
                    }
                }
            }
        }

        long phaseElapsed = System.currentTimeMillis() - phaseStart;
        if (verbose) {
            System.out.println("  Phase 0 total: " + phaseElapsed + "ms");
        }
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
                info.isDeclarationFile);
            extractor.setImportModulePaths(importAliasMap);
            info.exports = extractor.extract(info.rawAst);
            List<CompilerDiagnostic> exportDiags = extractor.diagnostics();
            diagnostics.addAll(exportDiags);
            if (exportDiags.stream().anyMatch(
                    d -> "error".equals(d.severity()))) {
                hasErrors = true;
            }

            // IR dump for declaration files
            if (dumpIr && info.isDeclarationFile && info.rawAst != null) {
                try {
                    String irText = IrDumper.dump(info.rawAst, info.symbolTable, info.modulePath);
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
     * <p>Emitted diagnostics:</p>
     * <ul>
     *   <li>{@code E2010} — the entry module does not export {@code main}</li>
     *   <li>{@code E2011} — {@code main} exists but is async or does not
     *       have signature {@code (): null}</li>
     * </ul>
     */
    private void validateEntryMain() {
        ModuleInfo entry = modules.get(entryFile.toString());
        if (entry == null) return; // discovery already reported E2003

        String file = entry.sourcePath;
        // E2010/E2011 anchor at the entry program span (D5): SOURCE-exact
        // at the program start via the T2 program-span obligation,
        // including the empty/whitespace-only entry case
        // (file,1,1,1,1,0,0,0,SOURCE).
        Span programSpan = entry.rawAst != null ? entry.rawAst.span() : null;

        Type mainType = entry.exports != null
            ? entry.exports.get("main") : null;
        if (mainType == null) {
            if (programSpan != null) {
                error(DiagnosticCode.E2010,
                    "Entry module must export 'main' with non-async signature '(): null'",
                    programSpan);
            } else {
                syntheticError(DiagnosticCode.E2010,
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
            invocation, CapabilityRegistry.releaseRegistry(), checked.input(),
            checked.index(), this.requirementManifests.manifests(), target, Set.of());
        this.routePlan = result;
        diagnostics.addAll(result.diagnostics());
        if (result.hasErrors()) {
            hasErrors = true;
            log("  Route planning failed: " + result.diagnostics());
        }
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
                    String resolved = tryResolveImportPath(imp.modulePath(),
                        Path.of(info.sourcePath));
                    if (resolved != null) {
                        imports.add(new ModuleFact.ImportFact(imp.alias(),
                            imp.modulePath(), resolved));
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

            NameResolver nr = new NameResolver(info.modulePath, resolver);
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
                                    Types.classType(cs.name(), cs.modulePath()));
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
                        Type clsType = Types.classType(cs.name(), cs.modulePath());

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
                    String irText = IrDumper.dump(info.rawAst, result, info.modulePath);
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
        Files.createDirectories(outputRoot);

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
            // Lua use site: the existing LuaJIT emitter, unchanged.
            for (ModuleInfo info : modules.values()) {
                if (info.isDeclarationFile) continue;
                codegenLuaModule(info);
            }
            copyRuntimeLibrary();
            copyStdlibModules();
        }

        long phaseElapsed = System.currentTimeMillis() - phaseStart;
        if (verbose) {
            System.out.println("  Phase 4 total: " + phaseElapsed + "ms");
        }
    }

    /**
     * Lua use site: emits the module via {@link LuaBackend}, with the
     * resolved import map and host-module declarations (unchanged behavior;
     * ISSUE-0091 moved the pre-existing body here verbatim).
     */
    private void codegenLuaModule(ModuleInfo info) throws IOException {
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

        String filePath = info.modulePath.replace('.', '/') + ".lua";
        Path outputPath = outputRoot.resolve(filePath);
        Files.createDirectories(outputPath.getParent());

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
            outputRoot, outputPath, sourceMap, importResolutions, hostModules,
            isEntry);
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
        log("  Generated: " + outputPath + " (" + modElapsed + "ms)");
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
        // Pass 1: generate every module and merge diagnostics. Rejected
        // modules write no artifact.
        List<ModuleInfo> cleanModules = new ArrayList<>();
        Map<ModuleInfo, JvmBackend.JvmCodegenResult> results = new LinkedHashMap<>();
        for (ModuleInfo info : modules.values()) {
            if (info.isDeclarationFile) continue;
            // Import resolutions (ISSUE-0096): raw import path → module
            // path of the imported COMPILED module. Declaration files
            // become host modules (ISSUE-0100) — see the hostModules
            // construction below — instead of the pre-slice E6000.
            // ISSUE-0109: the same discovery pass collects each imported
            // module's class declarations (module path → name →
            // declaration) so the backend can emit imported-class types,
            // construction, and nominal checks against the declaring
            // module's generated nested classes.
            Map<String, String> importResolutions = new HashMap<>();
            Map<String, Map<String, ClassDeclaration>> importedClasses =
                new HashMap<>();
            // ISSUE-0100 host ABI slice: imports of declaration files that
            // are not spec stdlib modules are host modules — the same
            // classification the LuaJIT use site builds (host-module-abi
            // D5) — and their declared export map flows into JvmBackend.
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
            boolean isEntry = info.sourcePath.equals(entryFile.toString());
            // ISSUE-0374 profile plumb: the backend derives its
            // backend-wide int mode from the invocation's project-wide
            // semantic profile — never from a static flag, a system
            // property, or any source/CLI/environment surface.
            JvmBackend.JvmCodegenResult res = JvmBackend.generate(
                info.rawAst, info.checkResult, info.sourcePath, info.modulePath,
                importResolutions, importedClasses, hostModules, isEntry,
                invocation.semanticProfile());
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
            Path outputPath = outputRoot.resolve(className + ".java");
            Files.createDirectories(outputPath.getParent());
            Files.writeString(outputPath, res.source());
            log("  Generated: " + outputPath);
        }
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
        // one per-compilation identity index over the module-path
        // classification, consumed by the JS emitter's descriptor
        // service.  The intrinsic builtin Error classification (the
        // checker's empty module path) and every known module join the
        // map; a module the classification cannot give a public identity
        // (an out-of-root relative source) stays absent — class-free
        // code remains valid, and a class there fails closed at
        // descriptor production (the pinned invariant violation).
        Map<String, CanonicalModuleIdentity> modulePathIdentities =
            new HashMap<>();
        modulePathIdentities.put("", CanonicalModuleIdentity.BuiltinModule.INSTANCE);
        for (ModuleInfo info : modules.values()) {
            CanonicalModuleIdentity identity = classifyModuleIdentity(info);
            if (identity != null) {
                modulePathIdentities.put(info.modulePath, identity);
            }
        }
        ModuleIdentityResolver.IdentityIndex identityIndex =
            ModuleIdentityResolver.buildIndex(modulePathIdentities);

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
            Map<String, Map<String, Type>> hostModules = new HashMap<>();
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
                                        imported.exports != null
                                            ? imported.exports : Map.of());
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
                importResolutions, hostModules, isEntry, identityIndex,
                identityIndex.moduleIdentityLookup(),
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
            Path outputPath = outputRoot.resolve(
                res.modulePath().replace('.', '/') + ".js");
            Files.createDirectories(outputPath.getParent());
            Files.writeString(outputPath, res.source());
            log("  Generated: " + outputPath);

            if (sourceMap && res.sourceMap() != null) {
                String[] paths = sourceMapSidecarPaths(info.sourcePath,
                    outputRoot, outputPath);
                String mapJson = res.sourceMap().toJson(paths[0], paths[1]);
                Path mapPath = outputRoot.resolve(
                    res.modulePath().replace('.', '/')
                        + ".deal.map.json");
                Files.writeString(mapPath, mapJson);
                log("  Source map: " + mapPath);
            }
        }

        copyJsRuntimeLibrary();
        copyStdlibJsModules();
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
     * True when the module is a spec-listed stdlib declaration module
     * (filesystem-discovered under the stdlib directory or registered as a
     * classpath resource).  Stdlib imports stay on the trusted raw-require
     * path (ISSUE-0082, host-module-abi D5(5)).
     */
    private boolean isSpecStdlibModuleInfo(ModuleInfo info) {
        if (info.sourcePath.startsWith("classpath:")) {
            return true;
        }
        return StdlibModuleResolver.isSpecStdlibModule(
            info.modulePath.replace('.', '/'));
    }

    private void copyRuntimeLibrary() throws IOException {
        Path runtimeDest = outputRoot.resolve("deal/runtime.lua");
        if (Files.exists(runtimeDest)) return;

        Files.createDirectories(runtimeDest.getParent());

        InputStream runtimeStream = getClass().getClassLoader()
            .getResourceAsStream("deal/runtime.lua");
        if (runtimeStream != null) {
            Files.copy(runtimeStream, runtimeDest);
            runtimeStream.close();
            log("  Copied runtime: " + runtimeDest);
            return;
        }

        Path runtimeSrc = Path.of("deal/runtime.lua");
        if (Files.exists(runtimeSrc)) {
            Files.copy(runtimeSrc, runtimeDest);
            log("  Copied runtime: " + runtimeDest);
            return;
        }

        // Anchorless site (D5/D6): the note names the missing runtime
        // library path.
        syntheticError(DiagnosticCode.E6000,
            "Runtime library not found: deal/runtime.lua", "",
            "missing anchor: runtime library path 'deal/runtime.lua'");
    }

    /**
     * Copies the spec-listed stdlib .lua implementation files to the output.
     * The module list is derived from the 6 spec-listed stdlib modules.
     */
    private void copyStdlibModules() throws IOException {
        for (String stdlibModule : StdlibModuleResolver.SPEC_STDLIB_MODULES) {
            Path destFile = outputRoot.resolve(stdlibModule + ".lua");
            if (Files.exists(destFile)) continue;

            if (stdlibDir != null) {
                Path srcFile = stdlibDir.resolve(stdlibModule + ".lua");
                if (Files.exists(srcFile)) {
                    Files.createDirectories(destFile.getParent());
                    Files.copy(srcFile, destFile);
                    log("  Copied stdlib: " + stdlibModule);
                    continue;
                }
            }

            // Fallback: try classpath resource for bundled stdlib .lua files
            String resourcePath = "std/" + stdlibModule.substring(4) + ".lua";
            InputStream stream = getClass().getClassLoader()
                .getResourceAsStream(resourcePath);
            if (stream != null) {
                Files.createDirectories(destFile.getParent());
                Files.copy(stream, destFile);
                stream.close();
                log("  Copied stdlib: " + stdlibModule);
            }
        }
    }

    /**
     * JS deployment copy (js-backend-emitter D10): copies deal/runtime.js
     * to <output>/deal/runtime.js — classpath resource first, then the
     * repo-root file, else E6000 — and skips an existing destination
     * (idempotent). The mirror of copyRuntimeLibrary with the .js
     * spelling.
     */
    private void copyJsRuntimeLibrary() throws IOException {
        Path runtimeDest = outputRoot.resolve("deal/runtime.js");
        if (Files.exists(runtimeDest)) return;

        Files.createDirectories(runtimeDest.getParent());

        InputStream runtimeStream = getClass().getClassLoader()
            .getResourceAsStream("deal/runtime.js");
        if (runtimeStream != null) {
            Files.copy(runtimeStream, runtimeDest);
            runtimeStream.close();
            log("  Copied runtime: " + runtimeDest);
            return;
        }

        Path runtimeSrc = Path.of("deal/runtime.js");
        if (Files.exists(runtimeSrc)) {
            Files.copy(runtimeSrc, runtimeDest);
            log("  Copied runtime: " + runtimeDest);
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
     * modules). Stdlib-directory source first, classpath resource
     * fallback, a missing source for a module skipped silently;
     * idempotent. The mirror of copyStdlibModules with the .js spelling
     * (js-backend-emitter D10).
     */
    private void copyStdlibJsModules() throws IOException {
        for (String stdlibModule : StdlibModuleResolver.SPEC_STDLIB_MODULES) {
            Path destFile = outputRoot.resolve(stdlibModule + ".js");
            if (Files.exists(destFile)) continue;

            if (stdlibDir != null) {
                Path srcFile = stdlibDir.resolve(stdlibModule + ".js");
                if (Files.exists(srcFile)) {
                    Files.createDirectories(destFile.getParent());
                    Files.copy(srcFile, destFile);
                    log("  Copied stdlib: " + stdlibModule);
                    continue;
                }
            }

            // Fallback: try classpath resource for bundled stdlib .js files
            String resourcePath = "std/" + stdlibModule.substring(4) + ".js";
            InputStream stream = getClass().getClassLoader()
                .getResourceAsStream(resourcePath);
            if (stream != null) {
                Files.createDirectories(destFile.getParent());
                Files.copy(stream, destFile);
                stream.close();
                log("  Copied stdlib: " + stdlibModule);
            }
        }
    }

    // =========================================================================
    // IR dump helper
    // =========================================================================

    /**
     * Writes an IR dump string to the output directory.
     * The file is placed at {@code <outputRoot>/<module-path>.ir.txt}.
     */
    private void writeIrDump(String modulePath, String irText) throws IOException {
        String filePath = modulePath.replace('.', '/') + ".ir.txt";
        Path outputPath = outputRoot.resolve(filePath);
        Files.createDirectories(outputPath.getParent());
        Files.writeString(outputPath, irText);
        log("  IR dumped: " + outputPath);
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
    public String resolveImportPath(String importPath, Path fromFile) {
        return resolveImportPath(importPath, fromFile, null);
    }

    /**
     * Resolves an import path to a source file, anchoring any E2003
     * re-emission at the given import declaration span when present,
     * falling back to synthetic plus an anchor note naming the import
     * path (D5).
     */
    private String resolveImportPath(String importPath, Path fromFile,
                                      Span importSpan) {
        String resolved = tryResolveImportPath(importPath, fromFile);
        if (resolved == null) {
            StringBuilder msg = new StringBuilder("Module not found: '" + importPath
                + "'. Attempted: ");
            List<String> candidates = buildCandidates(importPath, fromFile);
            for (int i = 0; i < candidates.size(); i++) {
                if (i > 0) msg.append(", ");
                msg.append(candidates.get(i));
            }
            msg.append(externalsDeclarationNote(importPath));
            if (importSpan != null) {
                error(DiagnosticCode.E2003, msg.toString(), importSpan);
            } else {
                syntheticError(DiagnosticCode.E2003, msg.toString(),
                    fromFile.toString(),
                    "missing anchor: import declaration span for import '"
                        + importPath + "'");
            }
        }
        return resolved;
    }

    /**
     * Tries to resolve an import path without emitting diagnostics.
     * Returns the resolved source file path, or {@code null} if not found.
     *
     * <p>For bare imports that look like stdlib module paths (e.g.,
     * {@code "std/io"}), only spec-listed stdlib modules are resolved.
     * Non-spec modules like {@code std/io} and {@code std/coroutine} are
     * rejected with {@code null}, resulting in an E2003 diagnostic.
     */
    private String tryResolveImportPath(String importPath, Path fromFile) {
        // Externals-listed bare imports (ISSUE-0082, host-module-abi D5):
        // the manifest declaration is authoritative for that name — on-disk
        // candidates are not consulted.  A missing declaration file yields
        // null (→ E2003 at the import site).
        String externalsDeclaration = externalsDeclarations.get(importPath);
        if (externalsDeclaration != null) {
            if (Files.exists(Path.of(externalsDeclaration))) {
                return externalsDeclaration;
            }
            return null;
        }

        // Reject non-spec stdlib modules at the discovery/import-resolution level.
        // Bare imports that start with "std/" but are not in the spec list
        // should not resolve (they are not valid stdlib modules).
        if (importPath.startsWith("std/")
                && !importPath.startsWith("./")
                && !importPath.startsWith("../")) {
            if (!StdlibModuleResolver.isSpecStdlibModule(importPath)) {
                return null;
            }
        }

        List<String> candidates = buildCandidates(importPath, fromFile);

        // Try filesystem candidates
        for (String candidate : candidates) {
            if (Files.exists(Path.of(candidate))) {
                return candidate;
            }
        }

        // Fallback: try JAR/classpath resources for bare imports
        if (!importPath.startsWith("./") && !importPath.startsWith("../")) {
            String resourcePath = importPath + ".d.deal";
            InputStream stream = getClass().getClassLoader()
                .getResourceAsStream(resourcePath);
            if (stream != null) {
                try { stream.close(); } catch (IOException ignored) {}
                return registerResourceModule(resourcePath, importPath);
            }
        }

        return null;
    }

    /**
     * True when a bare import resolved to a non-stdlib declaration file that
     * is not listed in deal.json externals (ISSUE-0082, host-module-abi
     * D5(3)-(4)): bare host modules must be declared in externals, while
     * relative ({@code ./}, {@code ../}) declaration imports and the stdlib
     * trusted path are unchanged.
     */
    private boolean isUndeclaredExternalHostModule(String importPath, String resolved) {
        if (importPath.startsWith("./") || importPath.startsWith("../")) {
            return false;
        }
        if (importPath.startsWith("std/")) {
            return false;
        }
        if (resolved.startsWith("classpath:")) {
            return false;
        }
        if (externalsDeclarations.containsKey(importPath)) {
            return false;
        }
        return resolved.endsWith(".d.deal");
    }

    private List<String> buildCandidates(String importPath, Path fromFile) {
        List<String> candidates = new ArrayList<>();

        if (importPath.startsWith("./") || importPath.startsWith("../")) {
            Path resolved = fromFile.getParent().resolve(importPath).normalize();
            addCandidates(candidates, resolved.toString());
        } else {
            for (Path root : moduleRoots) {
                Path resolved = root.resolve(importPath).normalize();
                addCandidates(candidates, resolved.toString());
            }
            if (stdlibDir != null) {
                Path resolved = stdlibDir.resolve(importPath).normalize();
                addCandidates(candidates, resolved.toString());
            }
            Path resolved = Path.of("").toAbsolutePath().resolve(importPath).normalize();
            addCandidates(candidates, resolved.toString());
        }
        return candidates;
    }

    /**
     * Describes the search paths used for a bare import, for use in
     * E2003 diagnostic messages.
     */
    private String describeSearchPaths(String importPath, Path fromFile) {
        List<String> candidates = buildCandidates(importPath, fromFile);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < candidates.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(candidates.get(i));
        }
        return sb.toString();
    }

    /**
     * Names the authoritative externals declaration path for an import
     * listed in deal.json externals, for E2003 messages.  The manifest
     * declaration is authoritative for that name and on-disk candidates
     * are not consulted (host-module-abi D5(2)), so a missing declaration
     * file must point the user at the configured path.  Returns an empty
     * string for imports without an externals entry.
     */
    private String externalsDeclarationNote(String importPath) {
        String declaration = externalsDeclarations.get(importPath);
        return declaration != null
            ? " (externals declaration: " + declaration + ")" : "";
    }

    private String registerResourceModule(String resourcePath, String importPath) {
        String syntheticPath = "classpath:" + resourcePath;
        if (modules.containsKey(syntheticPath)) return syntheticPath;

        try {
            InputStream stream = getClass().getClassLoader()
                .getResourceAsStream(resourcePath);
            if (stream == null) return null;

            String source = new String(stream.readAllBytes());
            stream.close();

            String modulePath = importPath.replace('/', '.');

            LexResult lex = new Lexer(source, syntheticPath).tokenize();
            if (hasLexErrors(lex)) {
                diagnostics.addAll(lex.diagnostics());
                hasErrors = true;
                return null;
            }

            Parser parser = new Parser(lex.tokens(), syntheticPath);
            ParseResult parseResult = parser.parse();
            diagnostics.addAll(parseResult.diagnostics());
            if (parseResult.hasErrors()) {
                hasErrors = true;
            }

            ModuleInfo info = new ModuleInfo(syntheticPath, modulePath, true);
            info.rawAst = parseResult.program();
            info.parseResult = parseResult;
            modules.put(syntheticPath, info);

            return syntheticPath;
        } catch (IOException e) {
            return null;
        }
    }

    private void addCandidates(List<String> candidates, String basePath) {
        candidates.add(basePath + ".deal");
        candidates.add(basePath + "/index.deal");
        candidates.add(basePath + ".d.deal");
        candidates.add(basePath + "/index.d.deal");
    }

    // =========================================================================
    // Path computation
    // =========================================================================

    /**
     * The index of the configured root that most specifically contains
     * the source file (longest absolute normalized prefix), or {@code -1}
     * when no root contains it.
     */
    private int bestRootIndex(Path sourceFile) {
        Path absFile = sourceFile.toAbsolutePath().normalize();
        int bestIndex = -1;
        int bestLength = -1;

        for (int i = 0; i < moduleRoots.size(); i++) {
            Path absRoot = moduleRoots.get(i).toAbsolutePath().normalize();
            if (absFile.startsWith(absRoot)) {
                int len = absRoot.toString().length();
                if (len > bestLength) {
                    bestLength = len;
                    bestIndex = i;
                }
            }
        }
        return bestIndex;
    }

    private String computeModulePath(Path sourceFile) {
        Path absFile = sourceFile.toAbsolutePath().normalize();
        int bestRoot = bestRootIndex(sourceFile);

        if (bestRoot >= 0) {
            Path relative = moduleRoots.get(bestRoot)
                .toAbsolutePath().normalize().relativize(absFile);
            String path = relative.toString();
            if (path.endsWith(".d.deal")) {
                path = path.substring(0, path.length() - ".d.deal".length());
            } else if (path.endsWith(".deal")) {
                path = path.substring(0, path.length() - ".deal".length());
            }
            return path.replace('/', '.').replace('\\', '.');
        }

        // Fallback: use relative path from current working directory.
        // If the source is under the CWD, use the relative path to avoid
        // collisions from filename-only resolution.
        Path cwd = Path.of("").toAbsolutePath().normalize();
        if (absFile.startsWith(cwd)) {
            Path relative = cwd.relativize(absFile);
            String path = relative.toString();
            if (path.endsWith(".d.deal")) {
                path = path.substring(0, path.length() - ".d.deal".length());
            } else if (path.endsWith(".deal")) {
                path = path.substring(0, path.length() - ".deal".length());
            }
            return path.replace('/', '.').replace('\\', '.');
        }

        // Absolute fallback: just the filename (used only when the file is
        // outside both module roots and CWD — typically a test scenario).
        String name = sourceFile.getFileName().toString();
        if (name.endsWith(".d.deal")) {
            return name.substring(0, name.length() - ".d.deal".length());
        } else if (name.endsWith(".deal")) {
            return name.substring(0, name.length() - ".deal".length());
        }
        return name;
    }

    // =========================================================================
    // Canonical module-identity classification (js-v12-completion-architecture D3)
    // =========================================================================

    /**
     * The canonical public module identity of one compiled module, per
     * the identity layer's classification (design source
     * {@code strict-project-context-resolution-identity} D6):
     * externals-listed declarations carry
     * {@code ExternalModule(rawImportSpecifier)} (the manifest key
     * exactly as written), spec stdlib modules and the intrinsic builtin
     * {@code Error} module carry {@code BuiltinModule}, and a configured
     * root-contained source module carries
     * {@code ProjectModule(configuredRootText, relativeModuleComponents)}
     * with the defining file's directory components below its most
     * specific root.  {@code null} means the module has no public
     * identity (an out-of-root relative source): class-free code stays
     * valid, and a class there fails closed at descriptor production.
     */
    private CanonicalModuleIdentity classifyModuleIdentity(ModuleInfo info) {
        String dotted = info.modulePath;
        if (dotted == null || dotted.isEmpty()) {
            return CanonicalModuleIdentity.BuiltinModule.INSTANCE;
        }
        // Externals-listed declarations: the externals key exactly as
        // written (dotted module path -> raw key via the declaration path).
        for (Map.Entry<String, String> entry : externalsDeclarations.entrySet()) {
            String dottedPath = externalsModulePaths.get(entry.getValue());
            if (dotted.equals(dottedPath)) {
                return new CanonicalModuleIdentity.ExternalModule(entry.getKey());
            }
        }
        if (isSpecStdlibModuleInfo(info)) {
            return CanonicalModuleIdentity.BuiltinModule.INSTANCE;
        }
        int rootIndex = bestRootIndex(Path.of(info.sourcePath));
        if (rootIndex < 0) {
            return null;
        }
        String rootText = configuredRootTexts.get(rootIndex);
        if (rootText == null || rootText.isEmpty()) {
            return null;
        }
        Path rootPath = moduleRoots.get(rootIndex);
        return new CanonicalModuleIdentity.ProjectModule(
            new ProjectModuleIdentity(rootText,
                rootPath.toAbsolutePath().normalize().toString(),
                ModuleIdentityResolver.directoryComponents(dotted)));
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
        private final List<CompilerDiagnostic> diagnostics;

        ModuleResolverImpl(Map<String, ModuleInfo> modules,
                           List<CompilerDiagnostic> diagnostics) {
            this.modules = modules;
            this.diagnostics = diagnostics;
        }

        @Override
        public Map<String, Type> resolveModule(String modulePath,
                                                String importingModule,
                                                Set<String> modulesInProgress)
                throws ModuleNotFoundException {
            Path importingFile = null;
            for (ModuleInfo info : modules.values()) {
                if (info.modulePath.equals(importingModule)
                        || info.sourcePath.equals(importingModule)) {
                    importingFile = Path.of(info.sourcePath);
                    break;
                }
            }
            if (importingFile == null) {
                importingFile = Path.of(importingModule);
            }

            for (ModuleInfo info : modules.values()) {
                if (isMatch(modulePath, importingFile, info)) {
                    return info.exports != null ? info.exports : Map.of();
                }
            }

            throw new ModuleNotFoundException("Module not found: " + modulePath);
        }

        private boolean isMatch(String importPath, Path fromFile, ModuleInfo info) {
            if (fromFile == null) return false;

            Path fromDir = fromFile.getParent();
            if (importPath.startsWith("./") || importPath.startsWith("../")) {
                Path resolved = fromDir.resolve(importPath).normalize();
                String resolvedBase = resolved.toString();

                String sourcePath = info.sourcePath;
                if (sourcePath.equals(resolvedBase + ".deal")) return true;
                if (sourcePath.equals(resolvedBase + "/index.deal")) return true;
                if (sourcePath.equals(resolvedBase + ".d.deal")) return true;
                if (sourcePath.equals(resolvedBase + "/index.d.deal")) return true;
            } else {
                if (info.modulePath.equals(importPath.replace('/', '.'))) return true;
                if (info.sourcePath.startsWith("classpath:")) {
                    if (info.modulePath.equals(importPath.replace('/', '.'))) return true;
                }
            }

            return false;
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
                    }
                    return null;
                }
            }
            return null;
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
                    if (info.nameResolver == null) {
                        return null;
                    }
                    Type resolved = info.nameResolver.resolveTypeNode(typeNode);
                    return resolved == Type.Error.INSTANCE ? null : resolved;
                }
            }
            return null;
        }
    }
}
