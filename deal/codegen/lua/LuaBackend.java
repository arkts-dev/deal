package deal.codegen.lua;

import deal.ast.*;
import deal.ast.ArrayType;
import deal.ast.FunctionType;
import deal.ast.NullableType;
import deal.checker.CheckResult;
import deal.checker.Symbol;
import deal.checker.SymbolTable;
import deal.codegen.SourceMapGenerator;
import deal.descriptors.CanonicalRuntimeTypeDescriptor;
import deal.diagnostics.CompilerDiagnostic;
import deal.identity.CanonicalClassIdentity;
import deal.identity.CanonicalClassIdentityIndex;
import deal.identity.CanonicalModuleIdentity;
import deal.identity.ProjectModuleIdentity;
import deal.module.ModuleIdentityResolver;
import deal.semantic.ir.SemanticProfile;
import deal.types.Type;
import deal.types.Types;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;
import deal.diagnostics.DiagnosticCode;

/**
 * Lua code generator (ISSUE-0007). Walks the typed AST and emits Lua source code
 * with runtime type checks on every typed boundary.
 *
 * <p>Implements {@link Visitor}{@code <Void>} with one visit method per node type,
 * exhaustive per sealed interface. Uses the type map from the type checker to
 * determine when to insert runtime checks.</p>
 */
public final class LuaBackend implements Visitor<Void> {

    private final Map<ExpressionNode, Type> typeMap;
    private final SymbolTable symbols;

    // Process-wide profile gate (signed-int32 foundation I4): under
    // DEAL_V1_2_INT32 the emitted module preamble writes
    // __rt.__INT32 = true (idempotent; the profile is project-wide) and
    // unary int negation routes through __rt.int_neg with span args. The
    // legacy default keeps the pre-change emission byte-identical.
    private final boolean int32Mode;

    // The per-compilation canonical descriptor service (emitter page D1):
    // every descriptor this backend writes into a v1.2 artifact comes from
    // CanonicalRuntimeTypeDescriptor.encode via the compilation's
    // CanonicalClassIdentityIndex. Production callers supply the
    // orchestrator-built service; the standalone entry points build the
    // single-module adapter surface below.
    private CanonicalRuntimeTypeDescriptor descriptors;

    // The per-compilation canonical identity surface (v1.2 identity
    // carriage, descriptor-identity-propagation D1): the identity index
    // supplies class descriptor text via
    // {@code index.descriptorTextFor(identity)} and the module-path
    // classification (the module-identity layer's own surface) builds
    // the local classes' canonical identities.  Descriptor text is
    // never computed from the dotted module path.
    private CanonicalClassIdentityIndex identityIndex;
    private Function<String, CanonicalModuleIdentity> moduleIdentities =
        mp -> null;
    private StringBuilder out = new StringBuilder();
    private final List<CompilerDiagnostic> diagnostics = new ArrayList<>();
    private int indent = 0;

    private final Map<String, String> exportedValues = new LinkedHashMap<>();

    // ISSUE-0050: deferred @jsonable class metadata for two-pass emission
    private final List<JsonableClassMeta> deferredJsonables = new ArrayList<>();

    private Type currentReturnType = null;
    private String sourceFilePath = "unknown.deal";

    // ISSUE-0082 (runtime-class-identity D2(0)): the module path of the
    // module being generated. Seeded by the static entry points and the
    // public instance constructor; {@link #qualifiedClassName} falls back to
    // {@code sourceFilePath} when null. Every class emission site that has
    // only the AST name (META export, @jsonable helpers) derives its
    // qualified identity string from this path, byte-identical to the
    // checker's module path (NameResolver is seeded with the same value).
    private String modulePath = null;

    private String currentContinueLabel = null;
    private int labelCounter = 0;

    private String tryReturnFlag = null;
    private String tryReturnVal = null;

    // Break/continue flag support (ISSUE-0011): flag-based pattern for
    // break/continue inside try within loops. Allocated at every TryStatement
    // level when inside a loop, with save/restore for nesting.
    private String tryBreakFlag = null;
    private String tryContinueFlag = null;

    private int functionDepth = 0;

    // Retained for potential assertions and future use; no longer used
    // for break/continue detection (ISSUE-0011 replaced that with flag-based pattern).
    private int insideTryDepth = 0;

    // Import resolution map: raw import path → Lua require path
    private Map<String, String> importResolutions = Map.of();

    // Host module declarations (ISSUE-0082, host-module-abi D4):
    // raw import path → (declared export name → Type).  When an import path
    // has an entry here, the import emits `local <alias> = __rt.load_host(
    // "<raw import path byte-for-byte>", { <name> = "<descriptor>", ... })`
    // instead of the raw require — the first argument is never the dotted
    // importResolutions value for that key.
    private Map<String, Map<String, Type>> hostModules = Map.of();

    // ISSUE-0009: for-loop shadow-local lowering.
    // When non-null, all IdentifierExpr nodes with this name in condition
    // and update expressions are remapped to "_name" (the outer counter).
    private String forLoopShadowVar = null;

    // v1.2 entry contract: when true, this module is the selected entry
    // module of the compiler invocation. The entry module must export a
    // non-async main() with signature (): null, and the backend invokes
    // main() from the module after the export table is built.
    private boolean entryModule = false;

    // Span of the last visited declaration named "main", used to anchor
    // the E6004 entry-contract diagnostic at the declaration itself.
    private Span mainDeclSpan = null;

    // Module-scope flag: true while walking statements at Lua chunk scope.
    // Module-level class artifacts are emitted into the __deal namespace
    // table; classes declared inside functions or control-flow constructs
    // keep scope-local artifact locals (ISSUE-0074 D2.6).
    private boolean moduleScope = true;

    // Nested-class declaration tracking (ISSUE-0077, lua-abi-emission-layer
    // D2.6): counts how many non-module-level class declarations of each
    // name are lexically visible in the generated Lua at the current
    // emission point. The frames mirror exactly the Lua scope boundaries
    // emitted during statement walking: function bodies, each then/
    // elseif/else branch of an if chain, while/for/do loop blocks, the
    // try pcall closure, and the catch if-block. Bare blocks emit no Lua
    // scope of their own, so their declarations register in the enclosing
    // frame (or stay visible for the rest of the chunk at chunk level) —
    // exactly the visibility of the emitted `local <C>_defaults`. When
    // emitClassConstruction resolves a root ClassSymbol whose name has a
    // visible nested declaration, it references the bare <C>_defaults local
    // so Lua lexical scoping resolves to the scope-local artifact, exactly
    // as the pre-namespace backend did. The same visibility decides the
    // deferred @jsonable pass's artifact references and the export values
    // for non-module-level class declarations.
    private final Map<String, Integer> nestedClassDeclCount = new HashMap<>();
    private final Deque<List<String>> nestedClassDeclFrames = new ArrayDeque<>();

    /**
     * Kind of the last chunk-visible declaration of a class name, tracked
     * for the chunk-end export-value resolution (see {@link #emitExports}
     * and {@link #resolveClassExportValue}). {@code MODULE_LEVEL}
     * declarations own {@code __deal[...]} namespace artifacts;
     * {@code NESTED} declarations in chunk-level bare blocks own
     * chunk-level locals that stay visible for the rest of the chunk,
     * exactly like the pre-namespace backend's chunk-level
     * {@code local <C>_meta}/{@code local <C>_defaults}.
     */
    private enum ChunkVisibleClassDecl { MODULE_LEVEL, NESTED }

    /**
     * Per class name: the last chunk-visible declaration seen in source
     * order. Updated by {@link #visit(ClassDeclaration)} for every
     * chunk-level declaration (module-level, or nested in a bare
     * chunk-level block). Declarations inside function/branch/loop/try
     * scopes are invisible at the chunk-end export statements and never
     * record here — exactly the visibility of their emitted locals.
     */
    private final Map<String, ChunkVisibleClassDecl> lastChunkVisibleClassDecl =
        new HashMap<>();

    /**
     * Export keys whose slots were registered by an {@code export class}
     * declaration, mapped to the class name (META under the bare class
     * name, DEFAULTS under {@code <C>_defaults}). Their values are
     * resolved at chunk end in {@link #emitExports} against the last
     * chunk-visible declaration of the class name.
     */
    private final Map<String, String> classExportKeyOwners = new HashMap<>();

    // Source map support
    private SourceMapGenerator sourceMapGenerator = null;

    // =========================================================================
    // Nested-class declaration scope tracking
    // =========================================================================

    /** Opens a nested-class declaration frame at a Lua scope boundary. */
    private void pushNestedClassFrame() {
        nestedClassDeclFrames.push(new ArrayList<>());
    }

    /** Closes the innermost nested-class declaration frame. */
    private void popNestedClassFrame() {
        for (String name : nestedClassDeclFrames.pop()) {
            nestedClassDeclCount.merge(name, -1, Integer::sum);
        }
    }

    /**
     * Records a non-module-level class declaration. When a frame is active,
     * the name registers in the innermost frame so the count is decremented
     * when that Lua scope ends; otherwise (chunk-level bare block) the
     * declaration stays visible for the rest of the chunk, matching the
     * visibility of the emitted chunk-local artifact.
     */
    private void recordNestedClassDeclaration(String name) {
        nestedClassDeclCount.merge(name, 1, Integer::sum);
        if (!nestedClassDeclFrames.isEmpty()) {
            nestedClassDeclFrames.peek().add(name);
        }
    }

    /** True if a non-module-level class of this name is lexically visible. */
    private boolean hasVisibleNestedClassDeclaration(String name) {
        return nestedClassDeclCount.getOrDefault(name, 0) > 0;
    }

    /**
     * Entry point: generate Lua source for a complete program.
     */
    public static String generate(ProgramNode program, CheckResult result,
                                   String sourcePath) {
        return generateWithImports(program, result, sourcePath, Map.of());
    }

    /**
     * Profile-aware entry point (signed-int32 foundation I4, harness
     * profile-selection seam): the overload above keeps the
     * {@link SemanticProfile#LEGACY_SAFE_INT} default; the conformance
     * harness passes the per-case invocation's project-wide profile here.
     */
    public static String generate(ProgramNode program, CheckResult result,
                                   String sourcePath,
                                   SemanticProfile semanticProfile) {
        return generateWithImports(program, result, sourcePath, Map.of(),
            semanticProfile);
    }

    /**
     * Entry point with import resolution mapping.
     * Maps raw import paths (e.g. "./lib") to Lua require paths (e.g. "lib").
     * The module path defaults to the source path.
     */
    public static String generateWithImports(ProgramNode program, CheckResult result,
                                              String sourcePath,
                                              Map<String, String> importResolutions) {
        return generateWithImports(program, result, sourcePath, sourcePath,
            importResolutions, Map.of());
    }

    /**
     * Profile-aware import-resolution variant (signed-int32 foundation
     * I4): the module path defaults to the source path and the
     * {@link SemanticProfile#LEGACY_SAFE_INT} default lives in the
     * profile-less overloads above.
     */
    public static String generateWithImports(ProgramNode program, CheckResult result,
                                              String sourcePath,
                                              Map<String, String> importResolutions,
                                              SemanticProfile semanticProfile) {
        return generateWithImports(program, result, sourcePath, sourcePath,
            importResolutions, Map.of(), false, semanticProfile);
    }

    /**
     * Entry point with import resolution mapping and an explicit module path.
     * The module path seeds the module-qualified class identity strings
     * (runtime-class-identity D2(0)); production passes the same value that
     * seeds the NameResolver, so tags and checker descriptors stay
     * byte-identical.
     */
    public static String generateWithImports(ProgramNode program, CheckResult result,
                                              String sourcePath, String modulePath,
                                              Map<String, String> importResolutions) {
        return generateWithImports(program, result, sourcePath, modulePath,
            importResolutions, Map.of());
    }

    /**
     * Entry point with import resolution mapping and host module
     * declarations (ISSUE-0082, host-module-abi D4); the module path
     * defaults to the source path.
     */
    public static String generateWithImports(ProgramNode program, CheckResult result,
                                              String sourcePath,
                                              Map<String, String> importResolutions,
                                              Map<String, Map<String, Type>> hostModules) {
        return generateWithImports(program, result, sourcePath, sourcePath,
            importResolutions, hostModules);
    }

    /**
     * Entry point with import resolution mapping, an explicit module path,
     * and host module declarations (ISSUE-0082, host-module-abi D4).
     *
     * @param importResolutions raw import path → Lua require path mapping
     * @param modulePath        seeds the module-qualified class identity
     *                          strings (runtime-class-identity D2(0));
     *                          production passes the same value that seeds
     *                          the NameResolver, so tags and checker
     *                          descriptors stay byte-identical
     * @param hostModules       raw import path → (declared export name → Type);
     *                          imports whose raw path has an entry here emit
     *                          the {@code __rt.load_host} loader instead of a
     *                          raw require (the entry wins over the
     *                          importResolutions value for that key)
     */
    public static String generateWithImports(ProgramNode program, CheckResult result,
                                              String sourcePath, String modulePath,
                                              Map<String, String> importResolutions,
                                              Map<String, Map<String, Type>> hostModules) {
        return generateWithImports(program, result, sourcePath, modulePath,
            importResolutions, hostModules, false);
    }

    /**
     * Entry variant: {@code entryModule} selects this module as the
     * compiler invocation's entry module. The entry contract (export a
     * non-async {@code main(): null}; backend invokes {@code main()} from
     * the module) applies only when the flag is set.
     */
    public static String generateWithImports(ProgramNode program, CheckResult result,
                                              String sourcePath, String modulePath,
                                              Map<String, String> importResolutions,
                                              Map<String, Map<String, Type>> hostModules,
                                              boolean entryModule) {
        return generateWithImports(program, result, sourcePath, modulePath,
            importResolutions, hostModules, entryModule,
            standaloneIdentityIndex(modulePath, hostModules));
    }

    /**
     * Production seam variant (emitter page D1): generation over the
     * compilation's canonical descriptor service built from the
     * compilation's {@link CanonicalClassIdentityIndex} and module-path
     * classification. Every descriptor emission site consumes it; the
     * standalone overloads above build the single-module adapter surface.
     */
    public static String generateWithImports(ProgramNode program, CheckResult result,
                                              String sourcePath, String modulePath,
                                              Map<String, String> importResolutions,
                                              Map<String, Map<String, Type>> hostModules,
                                              boolean entryModule,
                                              ModuleIdentityResolver.IdentityIndex identityIndex) {
        return generateResult(program, result, sourcePath, modulePath,
            importResolutions, hostModules, entryModule, null, identityIndex,
            SemanticProfile.LEGACY_SAFE_INT).lua();
    }

    /**
     * Profile-aware standalone variant (signed-int32 foundation I4): the
     * single-module adapter descriptor surface plus the caller's
     * project-wide profile. The profile-less overloads above keep the
     * {@link SemanticProfile#LEGACY_SAFE_INT} default.
     */
    public static String generateWithImports(ProgramNode program, CheckResult result,
                                              String sourcePath, String modulePath,
                                              Map<String, String> importResolutions,
                                              Map<String, Map<String, Type>> hostModules,
                                              boolean entryModule,
                                              SemanticProfile semanticProfile) {
        return generateResult(program, result, sourcePath, modulePath,
            importResolutions, hostModules, entryModule, null,
            standaloneIdentityIndex(modulePath, hostModules), semanticProfile)
            .lua();
    }

    /**
     * The standalone descriptor service (emitter page D1; the JsBackend
     * single-module adapter convention): the module path itself is the
     * configured root text with no relative components (module path
     * "test.deal" projects "@test.deal/<Name>") plus the intrinsic
     * builtin Error classification. Host-declared modules keep their
     * pinned dotted identity @<dottedPath>/<C>: each hostModules key's
     * dotted form ("host/cfg" &rarr; "host.cfg", the mapping the
     * orchestrator applies to externals keys) joins the classification as
     * a project module whose root text is the dotted path itself
     * (host-module-abi D1/D2). Production callers pass the
     * orchestrator-built per-compilation surface instead.
     */
    private static ModuleIdentityResolver.IdentityIndex standaloneIdentityIndex(
            String modulePath, Map<String, Map<String, Type>> hostModules) {
        Map<String, CanonicalModuleIdentity> byPath = new LinkedHashMap<>();
        byPath.put("", CanonicalModuleIdentity.BuiltinModule.INSTANCE);
        String effective = modulePath == null ? "" : modulePath;
        if (!effective.isEmpty()) {
            byPath.put(effective,
                new CanonicalModuleIdentity.ProjectModule(
                    new ProjectModuleIdentity(effective, effective, List.of())));
        }
        for (String raw : hostModules.keySet()) {
            String dotted = raw.replace('/', '.');
            if (!byPath.containsKey(dotted)) {
                byPath.put(dotted,
                    new CanonicalModuleIdentity.ProjectModule(
                        new ProjectModuleIdentity(dotted, dotted, List.of())));
            }
        }
        return ModuleIdentityResolver.buildIndex(byPath);
    }

    /**
     * The generated Lua source together with the backend diagnostics
     * produced during generation. Production callers merge the diagnostics
     * into the compilation report so backend rejections (E6004) fail
     * the compilation instead of being silently dropped.
     */
    public record GenerationResult(String lua,
                                   List<CompilerDiagnostic> diagnostics) {}

    /**
     * Shared generation core: builds a backend instance, generates the
     * chunk (header, statements, deferred @jsonable code, exports, and —
     * for the entry module — the main() invocation), and returns the
     * source with the backend diagnostics.
     */
    private static GenerationResult generateResult(ProgramNode program,
            CheckResult result, String sourcePath, String modulePath,
            Map<String, String> importResolutions,
            Map<String, Map<String, Type>> hostModules, boolean entryModule,
            SourceMapGenerator smg,
            ModuleIdentityResolver.IdentityIndex identityIndex,
            SemanticProfile semanticProfile) {
        LuaBackend backend = new LuaBackend(result.typeMap(),
            result.symbolTable(), semanticProfile);
        backend.identityIndex = identityIndex;
        backend.moduleIdentities = identityIndex.moduleIdentityLookup();
        backend.descriptors = new CanonicalRuntimeTypeDescriptor(identityIndex);
        backend.sourceFilePath = sourcePath;
        backend.modulePath = modulePath;
        backend.importResolutions = Map.copyOf(importResolutions);
        backend.hostModules = Map.copyOf(hostModules);
        backend.entryModule = entryModule;
        backend.sourceMapGenerator = smg;
        backend.emitHeader();
        backend.emitLine("");

        backend.walkStatements(program.statements());
        backend.emitJsonableCode();
        backend.emitExports();
        return new GenerationResult(backend.out.toString(),
            backend.diagnostics());
    }

    /**
     * Generate Lua source with source map tracking.
     * Returns both the Lua source and the SourceMapGenerator (which can produce
     * the JSON sidecar).
     */
    public static String generateWithSourceMap(ProgramNode program, CheckResult result,
                                                String sourcePath,
                                                Map<String, String> importResolutions,
                                                SourceMapGenerator smg) {
        return generateWithSourceMap(program, result, sourcePath, sourcePath,
            importResolutions, Map.of(), smg);
    }

    /**
     * Source-map variant with an explicit module path
     * (runtime-class-identity D2(0)).
     */
    public static String generateWithSourceMap(ProgramNode program, CheckResult result,
                                                String sourcePath, String modulePath,
                                                Map<String, String> importResolutions,
                                                SourceMapGenerator smg) {
        return generateWithSourceMap(program, result, sourcePath, modulePath,
            importResolutions, Map.of(), smg);
    }

    /**
     * Source-map variant with host module declarations (ISSUE-0082 D4).
     */
    public static String generateWithSourceMap(ProgramNode program, CheckResult result,
                                                String sourcePath,
                                                Map<String, String> importResolutions,
                                                Map<String, Map<String, Type>> hostModules,
                                                SourceMapGenerator smg) {
        return generateWithSourceMap(program, result, sourcePath, sourcePath,
            importResolutions, hostModules, smg);
    }

    /**
     * Source-map variant with host module declarations (ISSUE-0082 D4) and
     * an explicit module path (runtime-class-identity D2(0)); both default
     * in the overload above.
     */
    public static String generateWithSourceMap(ProgramNode program, CheckResult result,
                                                String sourcePath, String modulePath,
                                                Map<String, String> importResolutions,
                                                Map<String, Map<String, Type>> hostModules,
                                                SourceMapGenerator smg) {
        return generateWithSourceMap(program, result, sourcePath, modulePath,
            importResolutions, hostModules, smg, false);
    }

    /**
     * Source-map entry variant (see
     * {@link #generateWithImports(ProgramNode, CheckResult, String, String,
     * Map, Map, boolean)} for the entry contract).
     */
    public static String generateWithSourceMap(ProgramNode program, CheckResult result,
                                                String sourcePath, String modulePath,
                                                Map<String, String> importResolutions,
                                                Map<String, Map<String, Type>> hostModules,
                                                SourceMapGenerator smg,
                                                boolean entryModule) {
        return generateResult(program, result, sourcePath, modulePath,
            importResolutions, hostModules, entryModule, smg,
            standaloneIdentityIndex(modulePath, hostModules),
            SemanticProfile.LEGACY_SAFE_INT).lua();
    }

    /**
     * Generate Lua source and write it to the output path.
     * Runtime library is copied to {@code outputRoot/deal/runtime.lua}.
     *
     * @param outputRoot the root output directory (runtime lands at outputRoot/deal/runtime.lua)
     * @param outputPath the full path for this module's .lua file
     */
    public static void generateToFile(ProgramNode program, CheckResult result,
                                       String sourcePath, Path outputRoot,
                                       Path outputPath) throws IOException {
        generateToFile(program, result, sourcePath, outputRoot, outputPath, false, Map.of());
    }

    /**
     * Generate Lua source and write it to the output path, optionally producing
     * a source map sidecar file.
     *
     * @param outputRoot the root output directory
     * @param outputPath the full path for this module's .lua file
     * @param emitSourceMap if true, a {@code .deal.map.json} sidecar is written
     */
    public static void generateToFile(ProgramNode program, CheckResult result,
                                       String sourcePath, Path outputRoot,
                                       Path outputPath, boolean emitSourceMap)
                                       throws IOException {
        generateToFile(program, result, sourcePath, outputRoot, outputPath,
            emitSourceMap, Map.of());
    }

    /**
     * Generate Lua source and write it to the output path, optionally producing
     * a source map sidecar file, with import resolution mapping.
     *
     * @param outputRoot        the root output directory
     * @param outputPath        the full path for this module's .lua file
     * @param emitSourceMap     if true, a {@code .deal.map.json} sidecar is written
     * @param importResolutions raw import path → Lua require path mapping
     */
    public static void generateToFile(ProgramNode program, CheckResult result,
                                       String sourcePath, Path outputRoot,
                                       Path outputPath, boolean emitSourceMap,
                                       Map<String, String> importResolutions)
                                       throws IOException {
        generateToFile(program, result, sourcePath, sourcePath, outputRoot,
            outputPath, emitSourceMap, importResolutions, Map.of());
    }

    /**
     * File-writing variant with an explicit module path
     * (runtime-class-identity D2(0)).
     */
    public static void generateToFile(ProgramNode program, CheckResult result,
                                       String sourcePath, String modulePath,
                                       Path outputRoot, Path outputPath,
                                       boolean emitSourceMap,
                                       Map<String, String> importResolutions)
                                       throws IOException {
        generateToFile(program, result, sourcePath, modulePath, outputRoot,
            outputPath, emitSourceMap, importResolutions, Map.of());
    }

    /**
     * File-writing variant with host module declarations (ISSUE-0082 D4);
     * the module path defaults to the source path.
     */
    public static void generateToFile(ProgramNode program, CheckResult result,
                                       String sourcePath, Path outputRoot,
                                       Path outputPath, boolean emitSourceMap,
                                       Map<String, String> importResolutions,
                                       Map<String, Map<String, Type>> hostModules)
                                       throws IOException {
        generateToFile(program, result, sourcePath, sourcePath, outputRoot,
            outputPath, emitSourceMap, importResolutions, hostModules);
    }

    /**
     * Profile-carrying convenience variant (signed-int32 foundation I4):
     * mirrors the 7-argument overload with the module path defaulting to
     * the source path and the entry contract off; the runtime library is
     * copied to {@code outputRoot/deal/runtime.lua} exactly like the
     * legacy overloads.
     */
    public static void generateToFile(ProgramNode program, CheckResult result,
                                       String sourcePath, Path outputRoot,
                                       Path outputPath, boolean emitSourceMap,
                                       Map<String, String> importResolutions,
                                       Map<String, Map<String, Type>> hostModules,
                                       SemanticProfile semanticProfile)
                                       throws IOException {
        generateToFile(program, result, sourcePath, sourcePath, outputRoot,
            outputPath, emitSourceMap, importResolutions, hostModules, false,
            semanticProfile);
    }

    /**
     * File-writing variant with an explicit module path, host module
     * declarations, and import resolution mapping.
     *
     * @param modulePath        seeds the module-qualified class identity
     *                          strings (runtime-class-identity D2(0))
     * @param importResolutions raw import path → Lua require path mapping
     * @param hostModules       raw import path → (declared export name → Type)
     *                          (ISSUE-0082, host-module-abi D4)
     */
    public static void generateToFile(ProgramNode program, CheckResult result,
                                       String sourcePath, String modulePath,
                                       Path outputRoot, Path outputPath,
                                       boolean emitSourceMap,
                                       Map<String, String> importResolutions,
                                       Map<String, Map<String, Type>> hostModules)
                                       throws IOException {
        generateToFile(program, result, sourcePath, modulePath, outputRoot,
            outputPath, emitSourceMap, importResolutions, hostModules, false);
    }

    /**
     * File-writing entry variant: additionally returns the generated
     * source and backend diagnostics via {@link GenerationResult}, and
     * writes no artifact when the backend rejected the module (error
     * diagnostics), mirroring the JVM backend's no-artifact-on-rejection
     * contract. {@code entryModule} selects the v1.2 entry contract (see
     * {@link #generateWithImports(ProgramNode, CheckResult, String, String,
     * Map, Map, boolean)}).
     */
    public static GenerationResult generateToFile(ProgramNode program,
                                       CheckResult result,
                                       String sourcePath, String modulePath,
                                       Path outputRoot, Path outputPath,
                                       boolean emitSourceMap,
                                       Map<String, String> importResolutions,
                                       Map<String, Map<String, Type>> hostModules,
                                       boolean entryModule)
                                       throws IOException {
        return generateToFile(program, result, sourcePath, modulePath,
            outputRoot, outputPath, emitSourceMap, importResolutions,
            hostModules, entryModule,
            standaloneIdentityIndex(modulePath, hostModules),
            SemanticProfile.LEGACY_SAFE_INT);
    }

    /**
     * Profile-carrying entry variant (signed-int32 foundation I4): same
     * contract as the overload above with the invocation's semantic
     * profile selecting the int32 gate emission.
     */
    public static GenerationResult generateToFile(ProgramNode program,
                                       CheckResult result,
                                       String sourcePath, String modulePath,
                                       Path outputRoot, Path outputPath,
                                       boolean emitSourceMap,
                                       Map<String, String> importResolutions,
                                       Map<String, Map<String, Type>> hostModules,
                                       boolean entryModule,
                                       SemanticProfile semanticProfile)
                                       throws IOException {
        return generateToFile(program, result, sourcePath, modulePath,
            outputRoot, outputPath, emitSourceMap, importResolutions,
            hostModules, entryModule,
            standaloneIdentityIndex(modulePath, hostModules), semanticProfile);
    }

    /**
     * Production seam variant (emitter page D1): file generation over the
     * compilation's canonical descriptor service. The orchestrator builds
     * the service from the compilation's identity index and module-path
     * classification (the same surface the JS arm consumes) and passes it
     * here; every descriptor emission site reads from it.
     */
    public static GenerationResult generateToFile(ProgramNode program,
                                       CheckResult result,
                                       String sourcePath, String modulePath,
                                       Path outputRoot, Path outputPath,
                                       boolean emitSourceMap,
                                       Map<String, String> importResolutions,
                                       Map<String, Map<String, Type>> hostModules,
                                       boolean entryModule,
                                       ModuleIdentityResolver.IdentityIndex identityIndex)
                                       throws IOException {
        return generateToFile(program, result, sourcePath, modulePath,
            outputRoot, outputPath, emitSourceMap, importResolutions,
            hostModules, entryModule, identityIndex,
            SemanticProfile.LEGACY_SAFE_INT);
    }

    /**
     * Profile-carrying production seam (signed-int32 foundation I4): the
     * orchestrator passes {@code invocation.semanticProfile()} here; under
     * {@code DEAL_V1_2_INT32} the emitted module preamble writes
     * {@code __rt.__INT32 = true} and unary int negation routes through
     * {@code __rt.int_neg}. The overload above keeps the legacy default so
     * direct-test call sites are untouched.
     */
    public static GenerationResult generateToFile(ProgramNode program,
                                       CheckResult result,
                                       String sourcePath, String modulePath,
                                       Path outputRoot, Path outputPath,
                                       boolean emitSourceMap,
                                       Map<String, String> importResolutions,
                                       Map<String, Map<String, Type>> hostModules,
                                       boolean entryModule,
                                       ModuleIdentityResolver.IdentityIndex identityIndex,
                                       SemanticProfile semanticProfile)
                                       throws IOException {
        SourceMapGenerator smg = emitSourceMap ? new SourceMapGenerator() : null;
        GenerationResult gen = generateResult(program, result, sourcePath,
            modulePath, importResolutions, hostModules, entryModule, smg,
            identityIndex, semanticProfile);
        String luaSource = gen.lua();
        boolean hasErrors = gen.diagnostics().stream()
            .anyMatch(d -> "error".equals(d.severity()));
        if (hasErrors) {
            return gen;
        }

        Files.createDirectories(outputPath.getParent());
        Files.writeString(outputPath, luaSource);

        // Write source map sidecar
        if (smg != null && smg.hasMappings()) {
            // Normalize both source and generated paths to be project-relative.
            // The project root is inferred as outputRoot/../.. (for a typical
            // build/lua output dir, this yields the project root).  When that
            // fails we fall back to keeping absolute/relative paths consistent.
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

            String mapJson = smg.toJson(relSourcePath, relGeneratedPath);
            String baseName = outputPath.getFileName().toString();
            baseName = baseName.substring(0, baseName.lastIndexOf('.'));
            Path mapPath = outputPath.resolveSibling(baseName + ".deal.map.json");
            Files.writeString(mapPath, mapJson);
        }

        Path runtimeDest = outputRoot.resolve("deal/runtime.lua");
        if (!Files.exists(runtimeDest)) {
            Files.createDirectories(runtimeDest.getParent());
            InputStream runtimeStream = LuaBackend.class.getClassLoader()
                .getResourceAsStream("deal/runtime.lua");
            if (runtimeStream != null) {
                Files.copy(runtimeStream, runtimeDest);
                runtimeStream.close();
            } else {
                Path runtimeSrc = Path.of("deal/runtime.lua");
                if (Files.exists(runtimeSrc)) {
                    Files.createDirectories(runtimeDest.getParent());
                    Files.copy(runtimeSrc, runtimeDest);
                }
            }
        }
        return gen;
    }

    public List<CompilerDiagnostic> diagnostics() {
        return Collections.unmodifiableList(diagnostics);
    }

    // =========================================================================
    // Constructor
    // =========================================================================

    private LuaBackend(Map<ExpressionNode, Type> typeMap, SymbolTable symbols,
                       SemanticProfile semanticProfile) {
        this.typeMap = new HashMap<>(typeMap);
        this.symbols = symbols;
        this.int32Mode = semanticProfile == SemanticProfile.DEAL_V1_2_INT32;
    }

    /**
     * Public constructor for tests that need to capture codegen diagnostics.
     * Seeds the module path from the source path so that
     * {@link #generateFromInstance} emits module-qualified class identity
     * strings byte-identical to the static entry points
     * (runtime-class-identity D2(0)), and builds the single-module adapter
     * descriptor surface (emitter page D1): the module path projects
     * "@<modulePath>/<Name>" byte-identical to the legacy qualified
     * identity for identifier-shaped module paths.
     */
    public LuaBackend(Map<ExpressionNode, Type> typeMap, SymbolTable symbols, String sourcePath) {
        this(typeMap, symbols, SemanticProfile.LEGACY_SAFE_INT);
        this.sourceFilePath = sourcePath;
        this.modulePath = sourcePath;
        ModuleIdentityResolver.IdentityIndex standalone =
            standaloneIdentityIndex(sourcePath, Map.of());
        this.identityIndex = standalone;
        this.moduleIdentities = standalone.moduleIdentityLookup();
        this.descriptors = new CanonicalRuntimeTypeDescriptor(standalone);
    }

    /**
     * Instance constructor over an explicit canonical descriptor service
     * (emitter page D1): callers with the compilation's identity surface
     * (the conformance harness, the production orchestrator) pass the
     * service; {@link #generateFromInstance} consumes it for every
     * descriptor emission site.
     */
    public LuaBackend(Map<ExpressionNode, Type> typeMap, SymbolTable symbols,
                      String sourcePath, String modulePath,
                      ModuleIdentityResolver.IdentityIndex identityIndex) {
        this(typeMap, symbols, sourcePath, modulePath, identityIndex,
            SemanticProfile.LEGACY_SAFE_INT);
    }

    /**
     * Instance constructor with an explicit semantic profile
     * (signed-int32 foundation I4): {@link #generateFromInstance} derives
     * the int32 gate/negation emission from it. The overload above keeps
     * the legacy default so direct-test call sites are untouched.
     */
    public LuaBackend(Map<ExpressionNode, Type> typeMap, SymbolTable symbols,
                      String sourcePath, String modulePath,
                      ModuleIdentityResolver.IdentityIndex identityIndex,
                      SemanticProfile semanticProfile) {
        this(typeMap, symbols, semanticProfile);
        this.sourceFilePath = sourcePath;
        this.modulePath = modulePath;
        this.identityIndex = identityIndex;
        this.moduleIdentities = identityIndex.moduleIdentityLookup();
        this.descriptors = new CanonicalRuntimeTypeDescriptor(identityIndex);
    }

    /**
     * Generate Lua source using this instance (for tests that need diagnostics).
     */
    public String generateFromInstance(ProgramNode program) {
        return generateFromInstance(program, false);
    }

    /**
     * Instance generation with the v1.2 entry contract selectable (see
     * {@link #generateWithImports(ProgramNode, CheckResult, String, String,
     * Map, Map, boolean)}).
     */
    public String generateFromInstance(ProgramNode program, boolean entryModule,
            Map<String, String> importResolutions,
            Map<String, Map<String, Type>> hostModules) {
        this.importResolutions = Map.copyOf(importResolutions);
        this.hostModules = Map.copyOf(hostModules);
        return generateFromInstance(program, entryModule);
    }

    public String generateFromInstance(ProgramNode program, boolean entryModule) {
        moduleScope = true;
        nestedClassDeclCount.clear();
        nestedClassDeclFrames.clear();
        lastChunkVisibleClassDecl.clear();
        classExportKeyOwners.clear();
        mainDeclSpan = null;
        this.entryModule = entryModule;
        emitHeader();
        emitLine("");
        walkStatements(program.statements());
        emitJsonableCode();
        emitExports();
        return this.out.toString();
    }

    /**
     * Generate Lua source using this instance with source map tracking.
     * Returns the Lua source; source map can be retrieved via {@link #getSourceMapJson}.
     */
    public String generateFromInstanceWithSourceMap(ProgramNode program) {
        this.sourceMapGenerator = new SourceMapGenerator();
        return generateFromInstance(program);
    }

    /**
     * Returns the source map JSON string, or null if source map generation
     * was not enabled.
     */
    public String getSourceMapJson(String sourcePath, String generatedPath) {
        if (sourceMapGenerator == null || !sourceMapGenerator.hasMappings()) {
            return null;
        }
        return sourceMapGenerator.toJson(sourcePath, generatedPath);
    }

    /**
     * Returns the SourceMapGenerator for inspection in tests.
     */
    public SourceMapGenerator sourceMapGenerator() {
        return sourceMapGenerator;
    }

    // =========================================================================
    // Header / Exports
    // =========================================================================

    private void emitHeader() {
        emitLine("-- Generated by DEAL compiler v0.7");
        emitLine("-- Source: " + sourceFilePath);
        emitLine("");
        emitLine("local __rt = require(\"deal.runtime\")");
        if (int32Mode) {
            emitLine("__rt.__INT32 = true");
        }
        emitLine("");
        emitLine("local __NULL = __rt.__NULL");
        emitLine("local __MISSING = __rt.__MISSING");
        // Intrinsic function-value wrappers: int and number are first-class
        // function values whose sigs equal the seeded static types
        // (number)=>int / (int)=>number. Direct calls route through the
        // wrapper's .f entry with span forwarding; indirect uses (assignment,
        // callback arguments, arity adapters) flow through the wrapper
        // unchanged.
        emitLine("local int = __rt.function_(\"(number)->int\", "
            + "function(...) return __rt.int_convert(...) end)");
        emitLine("local number = __rt.function_(\"(int)->number\", "
            + "function(...) return __rt.number_convert(...) end)");
        emitLine("");
        // Generated-namespace table: owns all compiler-generated module-level
        // artifacts (Error defaults, class defaults/meta, @jsonable helpers).
        // User bindings cannot shadow table fields.
        emitLine("local " + LuaAbi.NAMESPACE + " = {}");
        emitLine(LuaAbi.namespaceAssignment("Error_defaults",
            "{ code = \"\", message = \"\" }"));
        emitLine("");
    }

    private void emitExports() {
        emitLine("local exports = {}");
        for (var entry : exportedValues.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            String ownerClass = classExportKeyOwners.get(key);
            if (ownerClass != null) {
                // Class META/DEFAULTS export values resolve at chunk end
                // to the LAST chunk-visible declaration of the class name,
                // exactly like the pre-namespace backend's bare-name
                // export statements (`exports.C = C_meta`), which Lua
                // lexical scoping resolved at the chunk-end export
                // statements to the last chunk-visible declaration's
                // artifact. A same-name class exported from a chunk-level
                // bare block after a module-level declaration (or vice
                // versa) therefore resolves every export key to the same
                // declaration instead of mixing class identities across
                // the frozen export surface.
                LuaAbi.HelperKind kind = key.equals(ownerClass)
                    ? LuaAbi.HelperKind.META
                    : LuaAbi.HelperKind.DEFAULTS;
                value = resolveClassExportValue(ownerClass, kind);
            }
            emitLine(LuaAbi.exportAssignment(key, value));
        }
        if (entryModule) {
            emitEntryMainInvocation();
        }
        emitLine("return exports");
    }

    /**
     * v1.2 entry contract: the selected entry module must export a
     * non-async {@code main} with signature {@code (): null}; the backend
     * invokes {@code main()} from that module once the export table is
     * built. A violation is a compile-time error (E6004), anchored at the
     * main declaration when one exists.
     */
    private void emitEntryMainInvocation() {
        boolean valid = exportedValues.containsKey("main")
            && entryMainIsValid();
        if (!valid) {
            String message = "entry module must export non-async main(): null; "
                + "found "
                + (exportedValues.containsKey("main")
                    ? "main with a different signature or an async marker"
                    : "no main export");
            if (mainDeclSpan != null) {
                addDiagnostic(DiagnosticCode.E6004, message, mainDeclSpan);
            } else {
                diagnostics.add(CompilerDiagnostic.syntheticError(
                    DiagnosticCode.E6004, message, sourceFilePath,
                    "missing anchor: main declaration span in entry module"));
            }
            return;
        }
        emitLine("exports.main.f()");
    }

    /**
     * True when the exported {@code main} declaration resolves to a
     * function symbol with a non-async {@code (): null} signature.
     */
    private boolean entryMainIsValid() {
        Symbol sym = symbols.resolve("main");
        if (sym instanceof Symbol.FunctionSymbol fs) {
            Type.Func ft = fs.funcType();
            return ft != null
                && !ft.isAsync()
                && ft.paramTypes().isEmpty()
                && ft.returnType() instanceof Type.Null;
        }
        return false;
    }

    /**
     * The chunk-end export value for a class META/DEFAULTS artifact: the
     * {@code __deal} namespace reference when the last chunk-visible
     * declaration of the class name is module-level; otherwise the bare
     * artifact name. The bare name reproduces the pre-namespace backend's
     * export statements, which Lua lexical scoping resolved at chunk end
     * to the last chunk-visible declaration's local (a chunk-level
     * bare-block declaration) or to nil (only function/branch/loop/try
     * scoped declarations).
     */
    private String resolveClassExportValue(String className,
                                           LuaAbi.HelperKind kind) {
        // META and DEFAULTS are the only kinds exported through the
        // class-export keys (FIELDS/FROM_JSON/TO_JSON values follow the
        // deferred pass's last-declared-wins registration instead).
        String suffix = (kind == LuaAbi.HelperKind.META) ? "_meta" : "_defaults";
        if (lastChunkVisibleClassDecl.get(className)
                == ChunkVisibleClassDecl.MODULE_LEVEL) {
            return LuaAbi.helperRef(className, kind);
        }
        return className + suffix;
    }

    // =========================================================================
    // Utilities
    // =========================================================================

    private void emit(String s) { out.append(s); }

    private void emitLine(String s) {
        if (!s.isEmpty()) out.append("  ".repeat(indent));
        out.append(s).append('\n');
    }

    private void emitLine() { out.append('\n'); }

    private void addDiagnostic(DiagnosticCode code, String message, Span span) {
        diagnostics.add(CompilerDiagnostic.error(code, message, span));
    }

    /** Returns the current 1-based line number in the output buffer. */
    private int currentGeneratedLine() {
        int line = 1;
        for (int i = 0; i < out.length(); i++) {
            if (out.charAt(i) == '\n') line++;
        }
        return line;
    }

    /** Returns the current 1-based column number in the output buffer. */
    private int currentGeneratedColumn() {
        int lastNewline = out.lastIndexOf("\n");
        if (lastNewline == -1) return out.length() + 1;
        return out.length() - lastNewline;
    }

    /** Records a source mapping for the given AST span at the current output position. */
    private void recordMapping(Span span) {
        if (sourceMapGenerator != null && span != null) {
            sourceMapGenerator.emitStatement(
                currentGeneratedLine(), currentGeneratedColumn(), span);
        }
    }

    /** Formats span coordinates for a Lua function call argument list. */
    private String spanArgs(Span span) {
        if (span == null) return "nil, nil, nil";
        return "\"" + escapeLuaStringNoQuotes(span.file()) + "\", "
            + span.startLine() + ", " + span.startColumn();
    }

    private Type typeOf(ExpressionNode expr) {
        Type t = typeMap.get(expr);
        if (t == null && expr instanceof LiteralExpr lit) {
            return literalType(lit);
        }
        return t;
    }

    private Type literalType(LiteralExpr lit) {
        return switch (lit.value()) {
            case LiteralValue.NullLiteral n -> Type.Null.INSTANCE;
            case LiteralValue.BooleanLiteral b -> Type.Boolean.INSTANCE;
            case LiteralValue.IntLiteral i -> Type.Int.INSTANCE;
            case LiteralValue.NumberLiteral n -> Type.Number.INSTANCE;
            case LiteralValue.StringLiteral s -> Type.String.INSTANCE;
        };
    }

    private String captureOutput(Runnable action) {
        StringBuilder saved = this.out;
        int savedIndent = this.indent;
        this.out = new StringBuilder();
        try {
            action.run();
            return this.out.toString();
        } finally {
            this.out = saved;
            this.indent = savedIndent;
        }
    }

    private String freshLabel(String prefix) {
        return prefix + "_" + (++labelCounter);
    }

    // =========================================================================
    // Type descriptor generation
    // =========================================================================

    /**
     * The canonical runtime class identity for a class declared in this
     * module (emitter page D1): the canonical descriptor text of
     * {@code Type.Class(name, modulePath)} resolved through the
     * compilation's identity index — never a locally derived spelling.
     * Used at emission sites that hold only the AST class name (META
     * export, @jsonable helpers); construction sites tag with
     * {@code typeDescriptor(cls)} directly. A class whose module has no
     * canonical public identity fails closed here (the pinned internal
     * invariant violation of the descriptor service).
     */
    private String qualifiedClassName(String name) {
        CanonicalClassIdentity identity = localClassIdentity(name);
        return identityIndex.descriptorTextFor(identity);
    }

    /**
     * The canonical class identity of a class declared in THIS module:
     * the module-identity layer's classification of the backend-held
     * module path plus the class name — never a locally derived
     * spelling (descriptor-identity-propagation D1).  A module without
     * a public identity fails closed (the pinned internal invariant
     * violation).
     */
    private CanonicalClassIdentity localClassIdentity(String name) {
        String mp = modulePath != null ? modulePath : sourceFilePath;
        CanonicalModuleIdentity moduleIdentity = moduleIdentities.apply(mp);
        if (moduleIdentity == null) {
            throw new IllegalStateException(
                "no canonical public module identity for module path '" + mp
                    + "': a class there can never be represented "
                    + "(internal invariant violation)");
        }
        return new CanonicalClassIdentity(moduleIdentity, name);
    }

    /** The intrinsic builtin Error class type (E2's synthesis). */
    private Type.Class errorClassType() {
        return Types.classType("Error", new CanonicalClassIdentity(
            CanonicalModuleIdentity.BuiltinModule.INSTANCE, "Error"));
    }

    /** A Class type for a class declared in the given wiring path. */
    private Type.Class classTypeFor(String name, String wiringPath) {
        CanonicalModuleIdentity moduleIdentity =
            moduleIdentities.apply(wiringPath == null ? "" : wiringPath);
        if (moduleIdentity == null) {
            throw new IllegalStateException(
                "no canonical public module identity for module path '"
                    + wiringPath + "': a class there can never be "
                    + "represented (internal invariant violation)");
        }
        return Types.classType(name,
            new CanonicalClassIdentity(moduleIdentity, name));
    }

    /**
     * True when the class type's identity declares in THIS module
     * (v1.2 identity carriage): same-module detection replaces the
     * retired dotted-path comparison.
     */
    private boolean isDeclaredInThisModule(Type.Class cls) {
        String mp = modulePath != null ? modulePath : sourceFilePath;
        CanonicalModuleIdentity mine = moduleIdentities.apply(mp);
        return mine != null
            && cls.identity().moduleIdentity().equals(mine);
    }

    /**
     * The one {@code Type}&rarr;text producer of the backend (emitter page
     * D1): {@link CanonicalRuntimeTypeDescriptor#encode(Type)} over the
     * compilation's canonical descriptor service. The local legacy
     * dialect producer is retired: {@code [D]} arrays, {@code ?D}
     * nullables, {@code bytes}, exact {@code async? (...) -> D} functions,
     * and class atoms byte-for-byte from the identity index (the builtin
     * {@code Error} atom is {@code @$builtin/Error}). No legacy spelling
     * ({@code T[]}, {@code T|null}, bare class names) is ever emitted.
     */
    private String typeDescriptor(Type t) {
        if (t == null) return "null";
        return descriptors.encode(t);
    }

    /**
     * The quoted-Lua-string form of a runtime type descriptor: the
     * canonical descriptor text wrapped in double quotes and Lua-escaped
     * (emitter page D1 — {@code quotedTypeDescriptor} keeps its
     * Lua-escaping role and calls the canonical service).  Every emission
     * site that embeds a descriptor inside a quoted Lua string must go
     * through this (or escapeLuaStringNoQuotes): descriptor text carries
     * user-authored characters — in particular the externals import key
     * embedded in canonical class atoms ("@$external/<key>/<Name>") —
     * and an unescaped backslash would make the generated chunk invalid
     * Lua ("invalid escape sequence" at require time, with no
     * compile-time diagnostic).
     */
    private String quotedTypeDescriptor(Type t) {
        return "\"" + escapeLuaStringNoQuotes(typeDescriptor(t)) + "\"";
    }

    /**
     * Emits a runtime type check expression.
     */
    private String emitCheckExpr(String valueExpr, Type type) {
        return emitCheckExpr(valueExpr, type, null);
    }

    /**
     * Emits a runtime type check expression with source location information.
     */
    private String emitCheckExpr(String valueExpr, Type type, Span span) {
        if (type == null) return valueExpr;
        String spanParam = spanArgs(span);
        return switch (type) {
            case Type.Null ignored ->
                "__rt.check_null(" + valueExpr + ", " + spanParam + ")";
            case Type.Boolean ignored ->
                "__rt.check_boolean(" + valueExpr + ", " + spanParam + ")";
            case Type.Int ignored ->
                "__rt.check_int(" + valueExpr + ", " + spanParam + ")";
            case Type.Number ignored ->
                "__rt.check_number(" + valueExpr + ", " + spanParam + ")";
            case Type.String ignored ->
                "__rt.check_string(" + valueExpr + ", " + spanParam + ")";
            case Type.Table ignored ->
                "__rt.check_table(" + valueExpr + ", " + spanParam + ")";
            case Type.Bytes ignored ->
                // v1.2 bytes boundary (emitter page D3): the canonical
                // matcher's "bytes" primitive row validates the FFI-backed
                // carrier (__kind == "bytes") and raises E8001 with the
                // forwarded span otherwise. The bytes runtime landed in the
                // value-model step, so no checked program fails this arm.
                "__rt.check_type(\"bytes\", " + valueExpr + ", "
                    + spanParam + ")";
            case Type.Array arr ->
                "__rt.check_array(" + quotedTypeDescriptor(type) + ", "
                    + valueExpr + ", " + spanParam + ")";
            case Type.Nullable n ->
                "__rt.check_nullable(" + quotedTypeDescriptor(n.inner()) + ", "
                    + valueExpr + ", " + spanParam + ")";
            case Type.Class cls ->
                "__rt.check_type(" + quotedTypeDescriptor(cls) + ", "
                    + valueExpr + ", " + spanParam + ")";
            case Type.Func f ->
                "__rt.check_type(" + quotedTypeDescriptor(f) + ", "
                    + valueExpr + ", " + spanParam + ")";
            default -> valueExpr;
        };
    }

    private String checkFunctionFor(Type type) {
        if (type == null) return null;
        return switch (type) {
            case Type.Null ignored -> "__rt.check_null";
            case Type.Boolean ignored -> "__rt.check_boolean";
            case Type.Int ignored -> "__rt.check_int";
            case Type.Number ignored -> "__rt.check_number";
            case Type.String ignored -> "__rt.check_string";
            case Type.Table ignored -> "__rt.check_table";
            // bytes has no dedicated named check helper; null routes the
            // site through emitCheckExpr, whose Bytes arm emits the
            // canonical matcher check __rt.check_type("bytes", ...).
            case Type.Bytes ignored -> null;
            default -> null;
        };
    }

    // =========================================================================
    // Statement walking
    // =========================================================================

    private void walkStatements(List<StatementNode> statements) {
        // DEAL function declarations are hoisted. Lua locals, however, are not
        // visible inside their own initializer (`local f = function() f() end`
        // resolves the inner f as a global). Declare every function in the
        // current statement scope first, then assign its wrapper when visiting
        // the declaration. This supports recursion, forward calls, and mutual
        // recursion while retaining lexical scope.
        for (StatementNode stmt : statements) {
            FunctionDeclaration function = switch (stmt) {
                case FunctionDeclaration fd -> fd;
                case ExportDeclaration ed
                    when ed.declaration() instanceof FunctionDeclaration fd -> fd;
                default -> null;
            };
            if (function != null) {
                emitLine("local " + function.name());
            }
        }
        for (StatementNode stmt : statements) visitStatement(stmt);
    }

    private void visitStatement(StatementNode stmt) {
        // Record source mapping before emitting the statement
        recordMapping(stmt.span());

        switch (stmt) {
            case VariableDeclaration vd -> visit(vd);
            case FunctionDeclaration fd -> visit(fd);
            case ClassDeclaration cd -> visit(cd);
            case ReturnStatement rs -> visit(rs);
            case IfStatement is -> visit(is);
            case WhileStatement ws -> visit(ws);
            case ForStatement fs -> visit(fs);
            case ForOfStatement fos -> visit(fos);
            case BreakStatement bs -> visit(bs);
            case ContinueStatement cs -> visit(cs);
            case ExpressionStatement es -> visit(es);
            case ImportDeclaration id -> visit(id);
            case ExportDeclaration ed -> visit(ed);
            case DeleteStatement ds -> visit(ds);
            case TryStatement ts -> visit(ts);
            case ThrowStatement ts2 -> visit(ts2);
            case Block b -> visit(b);
            default -> addDiagnostic(DiagnosticCode.E6000,
                "unsupported statement type: " + stmt.getClass().getSimpleName(),
                stmt.span());
        }
    }

    // =========================================================================
    // Visitor: Statements
    // =========================================================================

    @Override
    public Void visit(ProgramNode node) {
        moduleScope = true;
        nestedClassDeclCount.clear();
        nestedClassDeclFrames.clear();
        lastChunkVisibleClassDecl.clear();
        classExportKeyOwners.clear();
        emitHeader();
        walkStatements(node.statements());
        emitJsonableCode();
        emitExports();
        return null;
    }

    @Override
    public Void visit(ClassDeclaration node) {
        String name = node.name();
        StringBuilder defaults = new StringBuilder("{");
        boolean first = true;
        for (ClassField field : node.fields()) {
            if (!first) defaults.append(", ");
            first = false;
            String fieldDefault;
            if (field.optional() && field.defaultExpr().isEmpty()) {
                fieldDefault = "__MISSING";
            } else if (field.defaultExpr().isPresent()) {
                fieldDefault = emitExpression(field.defaultExpr().get());
            } else if (field.nullable()) {
                fieldDefault = "__NULL";
            } else {
                fieldDefault = defaultValueForTypeNode(field.type());
            }
            defaults.append(LuaAbi.tableField(field.name(), fieldDefault));
        }
        defaults.append("}");

        emitLine("-- Class: " + name);
        if (moduleScope) {
            emitLine(LuaAbi.namespaceAssignment(
                LuaAbi.helperKey(name, LuaAbi.HelperKind.DEFAULTS),
                defaults.toString()));
            emitLine(LuaAbi.namespaceAssignment(
                LuaAbi.helperKey(name, LuaAbi.HelperKind.META),
                "__rt.export_class(\"" + qualifiedClassName(name) + "\")"));
            // A module-level declaration is chunk-visible at the chunk-end
            // export statements (its artifacts are __deal namespace
            // fields, visible everywhere): record it as the last
            // chunk-visible declaration of this name (D2.6 export-value
            // parity, see emitExports).
            lastChunkVisibleClassDecl.put(name,
                ChunkVisibleClassDecl.MODULE_LEVEL);
        } else {
            emitLine("local " + LuaAbi.helperKey(name, LuaAbi.HelperKind.DEFAULTS)
                + " = " + defaults.toString());
            emitLine("local " + LuaAbi.helperKey(name, LuaAbi.HelperKind.META)
                + " = __rt.export_class(\"" + qualifiedClassName(name) + "\")");
            // Track the declaration so construction sites that resolve to
            // the root ClassSymbol of the same name reference the bare
            // <C>_defaults local (Lua lexical scoping) instead of the
            // __deal namespace entry (nested shadowing, D2.6).
            recordNestedClassDeclaration(name);
            // A nested declaration in a chunk-level bare block emits
            // chunk-level locals that stay visible for the rest of the
            // chunk, including at the chunk-end export statements; it is
            // therefore chunk-visible and records here. Declarations in
            // function/branch/loop/try scopes have an active frame and
            // are invisible at chunk end — they must not record (the
            // pre-namespace backend's bare export names resolved to nil
            // for them, never to an inner-scope local).
            if (nestedClassDeclFrames.isEmpty()) {
                lastChunkVisibleClassDecl.put(name,
                    ChunkVisibleClassDecl.NESTED);
            }
        }
        emitLine();
        return null;
    }

    private String defaultValueForTypeNode(TypeNode typeNode) {
        return switch (typeNode) {
            case NamedType nt -> switch (nt.name()) {
                case "int" -> "0"; case "number" -> "0.0";
                case "boolean" -> "false"; case "string" -> "\"\"";
                case "null" -> "__NULL"; case "table" -> "{}";
                default -> "{}";
            };
            case NullableType ignored -> "__NULL";
            case ArrayType ignored -> "{}";
            case FunctionType ignored -> "{}";
            default -> "nil";
        };
    }

    @Override
    public Void visit(FunctionDeclaration node) {
        String name = node.name();
        Type.Func funcType = getFunctionType(name);

        if ("main".equals(name)) {
            mainDeclSpan = node.span();
        }

        // DEAL v1.2: fixed parameter list only (no rest parameters).
        StringBuilder paramList = new StringBuilder();
        for (int i = 0; i < node.params().size(); i++) {
            if (i > 0) paramList.append(", ");
            paramList.append(node.params().get(i).name());
        }

        String sig = funcType != null ? quotedTypeDescriptor(funcType) : "\"()\"";
        emitLine(name + " = __rt.function_(" + sig
            + ", function(" + paramList.toString() + ")");

        functionDepth++;
        indent++;
        boolean savedModuleScope = moduleScope;
        moduleScope = false;
        pushNestedClassFrame();

        for (Parameter param : node.params()) {
            Type paramType = resolveTypeNode(param.type());
            if (paramType != null && !(paramType instanceof Type.Error)
                && !(paramType instanceof Type.Null)) {
                String checkFn = checkFunctionFor(paramType);
                Span paramSpan = param.type().span();
                if (checkFn != null) {
                    emitLine(checkFn + "(" + param.name() + ", "
                        + spanArgs(paramSpan) + ")");
                } else {
                    emitLine(emitCheckExpr(param.name(), paramType, paramSpan));
                }
            }
        }

        Type savedReturn = currentReturnType;
        currentReturnType = funcType != null ? funcType.returnType() : null;

        if (node.isAsync()) {
            emitLine("return __rt.async_start(function()");
            indent++;
            visit(node.body());
            indent--;
            emitLine("end)");  // close async_start
        } else {
            visit(node.body());
        }

        currentReturnType = savedReturn;
        indent--;
        functionDepth--;
        popNestedClassFrame();
        moduleScope = savedModuleScope;

        emitLine("end)");  // close outer function
        emitLine();
        return null;
    }

    private Type.Func getFunctionType(String name) {
        Symbol sym = symbols.resolve(name);
        if (sym instanceof Symbol.FunctionSymbol fs) return fs.funcType();
        return null;
    }

    @Override
    public Void visit(VariableDeclaration node) {
        String name = node.name();
        boolean hasAnnotation = node.typeAnnotation().isPresent();
        Type targetType = hasAnnotation
            ? resolveTypeNode(node.typeAnnotation().get()) : null;
        Type exprType = typeOf(node.initializer());
        String initLua = emitExpression(node.initializer());

        Span span = node.typeAnnotation().isPresent()
            ? node.typeAnnotation().get().span()
            : node.initializer().span();

        if (hasAnnotation && targetType != null && !(targetType instanceof Type.Error)) {
            if (targetType instanceof Type.Func tf
                && exprType instanceof Type.Func ef
                && isArityExtension(ef, tf)) {
                String adapter = emitArityAdapter(tf, ef, initLua, span);
                emitLine("local " + name + " = " + adapter);
            } else {
                String checked = emitCheckExpr(initLua, targetType, span);
                emitLine("local " + name + " = " + checked);
            }
        } else if (!hasAnnotation && exprType != null
            && node.initializer() instanceof LiteralExpr
            && (exprType instanceof Type.Int || exprType instanceof Type.Boolean
                || exprType instanceof Type.String || exprType instanceof Type.Number
                || exprType instanceof Type.Null)) {
            emitLine("local " + name + " = " + initLua);
        } else {
            Type checkType = targetType != null ? targetType : exprType;
            if (checkType != null && !(checkType instanceof Type.Error)
                && !(checkType instanceof Type.Null)) {
                String checked = emitCheckExpr(initLua, checkType, span);
                emitLine("local " + name + " = " + checked);
            } else {
                emitLine("local " + name + " = " + initLua);
            }
        }
        return null;
    }

    @Override
    public Void visit(ReturnStatement node) {
        if (node.expr().isPresent()) {
            String exprLua = emitExpression(node.expr().get());
            String checkedExpr;
            if (currentReturnType != null
                && !(currentReturnType instanceof Type.Error)
                && !(currentReturnType instanceof Type.Null)) {
                checkedExpr = emitCheckExpr(exprLua, currentReturnType,
                    node.expr().get().span());
            } else {
                checkedExpr = exprLua;
            }

            if (tryReturnFlag != null) {
                emitLine(tryReturnFlag + " = true");
                emitLine(tryReturnVal + " = " + checkedExpr);
            }
            emitLine("return " + checkedExpr);
        } else {
            // F2: bare return in null-typed function must return __NULL, not nil
            if (currentReturnType instanceof Type.Null) {
                if (tryReturnFlag != null) {
                    emitLine(tryReturnFlag + " = true");
                    emitLine(tryReturnVal + " = __NULL");
                }
                emitLine("return __NULL");
            } else {
                if (tryReturnFlag != null) {
                    emitLine(tryReturnFlag + " = true");
                }
                emitLine("return");
            }
        }
        return null;
    }

    @Override
    public Void visit(IfStatement node) {
        boolean savedModuleScope = moduleScope;
        moduleScope = false;
        String condLua = "__rt.check_boolean(" + emitExpression(node.condition())
            + ", " + spanArgs(node.condition().span()) + ")";
        emitLine("if " + condLua + " then");
        indent++;
        pushNestedClassFrame();
        visit(node.thenBlock());
        popNestedClassFrame();
        indent--;

        if (node.elseBranch().isPresent()) {
            switch (node.elseBranch().get()) {
                case Either.Left<IfStatement, Block> left -> {
                    IfStatement elseIf = left.value();
                    emitLine("elseif __rt.check_boolean("
                        + emitExpression(elseIf.condition())
                        + ", " + spanArgs(elseIf.condition().span()) + ") then");
                    indent++;
                    pushNestedClassFrame();
                    visit(elseIf.thenBlock());
                    popNestedClassFrame();
                    indent--;
                    emitElseChain(elseIf);
                }
                case Either.Right<IfStatement, Block> right -> {
                    emitLine("else");
                    indent++;
                    pushNestedClassFrame();
                    visit(right.value());
                    popNestedClassFrame();
                    indent--;
                    emitLine("end");
                }
            }
        } else {
            emitLine("end");
        }
        moduleScope = savedModuleScope;
        return null;
    }

    private void emitElseChain(IfStatement node) {
        if (node.elseBranch().isPresent()) {
            switch (node.elseBranch().get()) {
                case Either.Left<IfStatement, Block> left -> {
                    IfStatement elseIf = left.value();
                    emitLine("elseif __rt.check_boolean("
                        + emitExpression(elseIf.condition())
                        + ", " + spanArgs(elseIf.condition().span()) + ") then");
                    indent++;
                    pushNestedClassFrame();
                    visit(elseIf.thenBlock());
                    popNestedClassFrame();
                    indent--;
                    emitElseChain(elseIf);
                }
                case Either.Right<IfStatement, Block> right -> {
                    emitLine("else");
                    indent++;
                    pushNestedClassFrame();
                    visit(right.value());
                    popNestedClassFrame();
                    indent--;
                    emitLine("end");
                }
            }
        } else {
            emitLine("end");
        }
    }

    @Override
    public Void visit(WhileStatement node) {
        boolean savedModuleScope = moduleScope;
        moduleScope = false;
        pushNestedClassFrame();
        String condLua = "__rt.check_boolean(" + emitExpression(node.condition())
            + ", " + spanArgs(node.condition().span()) + ")";

        String savedLabel = currentContinueLabel;
        String loopLabel = freshLabel("__continue");
        currentContinueLabel = loopLabel;

        emitLine("while " + condLua + " do");
        indent++;
        visit(node.body());
        emitLine("::" + loopLabel + "::");
        indent--;
        emitLine("end");

        currentContinueLabel = savedLabel;
        popNestedClassFrame();
        moduleScope = savedModuleScope;
        return null;
    }

    @Override
    public Void visit(ForStatement node) {
        boolean savedModuleScope = moduleScope;
        moduleScope = false;
        pushNestedClassFrame();
        emitLine("-- DEAL for-loop (v0.6 lowering)");
        emitLine("do");
        indent++;

        // Determine if this is a let-declared loop variable (shadow-local pattern).
        // When the for-init is a let declaration, we create a shadow variable _name
        // for the outer counter and remap condition/update references to it.
        String loopVarName = null;
        boolean hasLetInit = false;

        if (node.init().isPresent()) {
            ForInit init = node.init().get();
            if (init instanceof ForInit.VarDecl) {
                hasLetInit = true;
                loopVarName = ((ForInit.VarDecl) init).decl().name();
            }
        }

        if (node.init().isPresent()) {
            ForInit init = node.init().get();
            switch (init) {
                case ForInit.VarDecl vd -> {
                    VariableDeclaration decl = vd.decl();
                    Type varType = decl.typeAnnotation().isPresent()
                        ? resolveTypeNode(decl.typeAnnotation().get()) : null;
                    String initExpr = emitExpression(decl.initializer());
                    // Emit the outer counter as "_name" instead of "name"
                    String shadowName = "_" + decl.name();
                    Span initSpan = decl.typeAnnotation().isPresent()
                        ? decl.typeAnnotation().get().span()
                        : decl.initializer().span();
                    if (varType != null && !(varType instanceof Type.Error)) {
                        emitLine("local " + shadowName + " = "
                            + emitCheckExpr(initExpr, varType, initSpan));
                    } else {
                        emitLine("local " + shadowName + " = " + initExpr);
                    }
                }
                case ForInit.AssignExpr ae -> visit(ae.expr());
            }
        }

        // Condition: remap loop variable references to the shadow name
        String savedShadowVar = this.forLoopShadowVar;
        this.forLoopShadowVar = hasLetInit ? loopVarName : null;
        Span condSpan = node.condition().isPresent()
            ? node.condition().get().span() : null;
        String condStr = node.condition().isPresent()
            ? "__rt.check_boolean(" + emitExpression(node.condition().get())
                + ", " + spanArgs(condSpan) + ")"
            : "true";
        this.forLoopShadowVar = savedShadowVar;

        String savedLabel = currentContinueLabel;
        String loopLabel = freshLabel("__continue");
        currentContinueLabel = loopLabel;

        emitLine("while " + condStr + " do");
        indent++;

        // Per-iteration fresh binding: copy the outer counter into a new local
        if (hasLetInit) {
            emitLine("local " + loopVarName + " = _" + loopVarName);
        }

        visit(node.body());
        // Continue landing pad: placed before the update so that continue
        // in a C-style for-loop jumps to the update step, then re-checks condition.
        emitLine("::" + loopLabel + "::");
        if (node.update().isPresent()) {
            // Update: remap loop variable references to the shadow name
            this.forLoopShadowVar = hasLetInit ? loopVarName : null;
            emitLine(emitExpression(node.update().get()));
            this.forLoopShadowVar = savedShadowVar;
        }
        indent--;
        emitLine("end");  // while
        indent--;
        emitLine("end");  // do

        currentContinueLabel = savedLabel;
        popNestedClassFrame();
        moduleScope = savedModuleScope;
        return null;
    }

    @Override
    public Void visit(ForOfStatement node) {
        boolean savedModuleScope = moduleScope;
        moduleScope = false;
        pushNestedClassFrame();
        Type iterableType = typeOf(node.iterable());

        emitLine("do");
        indent++;

        String iterableExpr = emitExpression(node.iterable());
        String varName = node.varName();

        String savedLabel = currentContinueLabel;
        String loopLabel = freshLabel("__continue");
        currentContinueLabel = loopLabel;

        if (iterableType instanceof Type.Array) {
            emitLine("for __i, " + varName + " in ipairs(" + iterableExpr + ") do");
        } else {
            // v1.2 string iteration: one Unicode scalar value per step.
            // Hoist the iterable expression to a local for single
            // evaluation (D17), then walk scalar values with
            // __rt.utf8_next — never byte indices.
            emitLine("local __iterable = " + iterableExpr);
            emitLine("local __i = 0");
            emitLine("while true do");
            indent++;
            emitLine("local __n, __ch = __rt.utf8_next(__iterable, __i)");
            emitLine("if __n == nil then break end");
            emitLine("__i = __n");
            emitLine("local " + varName + " = __ch");
            indent--;
        }

        indent++;
        visit(node.body());
        emitLine("::" + loopLabel + "::");
        indent--;
        emitLine("end");  // for / while

        indent--;
        emitLine("end");  // do

        currentContinueLabel = savedLabel;
        popNestedClassFrame();
        moduleScope = savedModuleScope;
        return null;
    }

    @Override
    public Void visit(BreakStatement node) {
        if (tryBreakFlag != null) {
            // Inside a try that is inside a loop: set flag and exit pcall
            emitLine(tryBreakFlag + " = true");
            emitLine("return");
        } else {
            emitLine("break");
        }
        return null;
    }

    @Override
    public Void visit(ContinueStatement node) {
        if (tryContinueFlag != null) {
            // Inside a try that is inside a loop: set flag and exit pcall
            emitLine(tryContinueFlag + " = true");
            emitLine("return");
        } else if (currentContinueLabel != null) {
            emitLine("goto " + currentContinueLabel);
        } else {
            addDiagnostic(DiagnosticCode.E6001, "continue outside loop", node.span());
        }
        return null;
    }

    @Override
    public Void visit(ExpressionStatement node) {
        String expr = emitExpression(node.expr());
        if (expr.startsWith("(")) {
            emitLine(";" + expr);
        } else {
            emitLine(expr);
        }
        return null;
    }

    @Override
    public Void visit(ImportDeclaration node) {
        // Host-module branch (ISSUE-0082, host-module-abi D4): the declared
        // exports drive the runtime loader.  The first argument is the raw
        // import specifier byte-for-byte — never the dotted importResolutions
        // value for that key (the dotted module path is the typing/class-
        // identity name only).  Declared-map keys route through the LuaAbi
        // table-constructor key-form policy (Lua-keyword export names and
        // "$" synthetic exports emit bracket-string keys).
        Map<String, Type> declared = hostModules.get(node.modulePath());
        if (declared != null) {
            emitLine("local " + node.alias() + " = __rt.load_host(\""
                + escapeLuaStringNoQuotes(node.modulePath()) + "\", {");
            indent++;
            // Deterministic emission (host-module-abi Failure and
            // operations): the declared-map order must be independent of the
            // caller-supplied map implementation.  Immutable maps
            // (Map.of/Map.copyOf) and hash maps iterate in per-JVM-run
            // randomized order, which would make generated Lua vary between
            // identical builds — iterate the declared entries sorted by
            // export name.
            for (Map.Entry<String, Type> entry
                    : new TreeMap<>(declared).entrySet()) {
                emitLine(LuaAbi.tableField(entry.getKey(),
                    quotedTypeDescriptor(entry.getValue())) + ",");
            }
            indent--;
            emitLine("})");
            return null;
        }

        String requirePath = importResolutions.getOrDefault(
            node.modulePath(), node.modulePath());
        emitLine("local " + node.alias() + " = require(\""
            + requirePath + "\")");
        return null;
    }

    @Override
    public Void visit(ExportDeclaration node) {
        switch (node.declaration()) {
            case FunctionDeclaration fd -> {
                exportedValues.putIfAbsent(fd.name(), fd.name());
                visit(fd);
            }
            case ClassDeclaration cd -> {
                // Capture the declaration scope BEFORE visit(cd): a class
                // exported from a non-module-level position emits its
                // artifacts as scope-local locals, so the export values
                // must reference those bare locals (as the pre-namespace
                // backend did) instead of the __deal namespace entries
                // that are only written for module-level declarations
                // (lua-abi-emission-layer D2.6).
                boolean moduleLevel = moduleScope;
                if (exportedValues.putIfAbsent(cd.name(),
                    moduleLevel
                        ? LuaAbi.helperRef(cd.name(), LuaAbi.HelperKind.META)
                        : cd.name() + "_meta") == null) {
                    // Only the registration that won the slot marks it for
                    // chunk-end resolution: the final value is resolved in
                    // emitExports() against the LAST chunk-visible
                    // declaration of this class name (see
                    // resolveClassExportValue). The registered value above
                    // is a placeholder that emitExports() replaces.
                    classExportKeyOwners.put(cd.name(), cd.name());
                }
                // Also export the defaults table so importing modules
                // can construct instances of this class.
                String defaultsKey = LuaAbi.helperKey(
                    cd.name(), LuaAbi.HelperKind.DEFAULTS);
                if (exportedValues.putIfAbsent(defaultsKey,
                    moduleLevel
                        ? LuaAbi.helperRef(cd.name(), LuaAbi.HelperKind.DEFAULTS)
                        : cd.name() + "_defaults") == null) {
                    classExportKeyOwners.put(defaultsKey, cd.name());
                }
                visit(cd);

                // ISSUE-0050: @jsonable deferred codegen
                if (cd.isJsonable()) {
                    // Record metadata for deferred emission
                    deferredJsonables.add(new JsonableClassMeta(
                        cd.name(), cd.fields(), moduleLevel));
                    // Register exports for generated jsonable artifacts.
                    // put (not putIfAbsent): the deferred pass is keyed by
                    // class name with last-declaration-wins (the
                    // pre-namespace backend's structure), so when the same
                    // name is exported from both a module-level and a
                    // nested position, the export values must track the
                    // LAST declaration — the one whose artifacts the
                    // deferred pass actually emits — or the exports would
                    // reference artifacts that were never written (nil).
                    exportedValues.put(
                        LuaAbi.helperKey(cd.name(), LuaAbi.HelperKind.FIELDS),
                        moduleLevel
                            ? LuaAbi.helperRef(cd.name(), LuaAbi.HelperKind.FIELDS)
                            : cd.name() + "_fields");
                    exportedValues.put(
                        LuaAbi.helperKey(cd.name(), LuaAbi.HelperKind.FROM_JSON),
                        moduleLevel
                            ? LuaAbi.helperRef(cd.name(), LuaAbi.HelperKind.FROM_JSON)
                            : cd.name() + "_fromJson");
                    exportedValues.put(
                        LuaAbi.helperKey(cd.name(), LuaAbi.HelperKind.TO_JSON),
                        moduleLevel
                            ? LuaAbi.helperRef(cd.name(), LuaAbi.HelperKind.TO_JSON)
                            : cd.name() + "_toJson");
                }
            }
            default -> {}
        }
        return null;
    }

    @Override
    public Void visit(DeleteStatement node) {
        if (node.target() instanceof IndexExpr idx) {
            Type arrType = typeOf(idx.array());
            if (arrType instanceof Type.Array) {
                String arr = emitExpression(idx.array());
                String index = emitExpression(idx.index());
                String myIndent = "  ".repeat(indent);
                emitLine("do");
                indent++;
                emitLine("local __arr = " + arr);
                emitLine("local __idx = __rt.check_int(" + index
                    + ", " + spanArgs(idx.span()) + ")");
                emitLine("if __idx < 0 or __idx > #__arr then error(__rt._err(\"E8002\", "
                    + "\"array index out of bounds\", " + spanArgs(idx.span()) + ")) end");
                emitLine("__arr[__idx + 1] = nil");
                indent--;
                emitLine("end");
                return null;
            }
        }
        emitLine(emitExpression(node.target()) + " = nil");
        return null;
    }

    @Override
    public Void visit(TryStatement node) {
        boolean savedModuleScope = moduleScope;
        moduleScope = false;
        // ---- Save try-return flags ----
        String savedFlag = tryReturnFlag;
        String savedVal = tryReturnVal;
        String myFlag = null;
        String myVal = null;

        if (functionDepth > 0) {
            int tryId = ++labelCounter;
            myFlag = "__try_returned_" + tryId;
            myVal = "__try_return_val_" + tryId;
            tryReturnFlag = myFlag;
            tryReturnVal = myVal;

            emitLine("local " + myFlag + " = false");
            emitLine("local " + myVal + " = nil");
        }

        // ---- Save and allocate try-break/continue flags ----
        boolean inLoop = currentContinueLabel != null;
        String savedBreakFlag = tryBreakFlag;
        String savedContinueFlag = tryContinueFlag;
        String myBreakFlag = null;
        String myContinueFlag = null;

        if (inLoop) {
            int tryId = ++labelCounter;
            myBreakFlag = "__try_break_" + tryId;
            myContinueFlag = "__try_continue_" + tryId;
            tryBreakFlag = myBreakFlag;
            tryContinueFlag = myContinueFlag;

            emitLine("local " + myBreakFlag + " = false");
            emitLine("local " + myContinueFlag + " = false");
        }

        // ---- Emit pcall wrapper ----
        emitLine("local __ok, __err = pcall(function()");
        indent++;
        insideTryDepth++;
        pushNestedClassFrame();  // the pcall closure is a Lua scope boundary
        visit(node.tryBlock());
        popNestedClassFrame();
        insideTryDepth--;
        indent--;
        emitLine("end)");

        // ---- Restore ALL flags before catch block (catch is outside pcall) ----
        tryReturnFlag = savedFlag;
        tryReturnVal = savedVal;
        tryBreakFlag = savedBreakFlag;
        tryContinueFlag = savedContinueFlag;

        // ---- Catch block ----
        // The emitted `if not __ok then ... end` chain is a Lua scope of its
        // own: push a nested-class frame so declarations in the catch block
        // stop being visible when the catch scope ends, exactly mirroring
        // the Lua lexical scope of the emitted local artifacts.
        emitLine("if not __ok then");
        indent++;
        pushNestedClassFrame();
        emitLine("local " + node.catchVar());
        emitLine("if type(__err) == \"table\" and __err.code ~= nil then");
        indent++;
        emitLine(node.catchVar() + " = __rt.error_value(__err.code, __err.message)");
        indent--;
        emitLine("else");
        indent++;
        emitLine(node.catchVar()
            + " = __rt.error_value(\"E8001\", tostring(__err))");
        indent--;
        emitLine("end");
        visit(node.catchBlock());
        popNestedClassFrame();
        indent--;

        // ---- Try-return flag check (after catch, continues elseif chain) ----
        if (functionDepth > 0) {
            emitLine("elseif " + myFlag + " then");
            indent++;
            // Propagate to outer try's flag for nested try/catch
            if (savedFlag != null) {
                emitLine(savedFlag + " = true");
                emitLine(savedVal + " = " + myVal);
            }
            emitLine("return " + myVal);
            indent--;
        }

        // ---- Try-break/continue flag checks (ISSUE-0011) ----
        if (inLoop) {
            if (functionDepth > 0) {
                // Continue the elseif chain
                emitLine("elseif " + myBreakFlag + " then");
            } else {
                // Close the catch if-block first, start a new if
                emitLine("end");
                emitLine("if " + myBreakFlag + " then");
            }
            indent++;
            if (savedBreakFlag != null) {
                // Nested try: propagate to outer try's break flag
                emitLine(savedBreakFlag + " = true");
                emitLine("return");
            } else {
                // Outermost try: real break
                emitLine("break");
            }
            indent--;

            emitLine("elseif " + myContinueFlag + " then");
            indent++;
            if (savedContinueFlag != null) {
                // Nested try: propagate to outer try's continue flag
                emitLine(savedContinueFlag + " = true");
                emitLine("return");
            } else {
                // Outermost try: real continue via goto
                emitLine("goto " + currentContinueLabel);
            }
            indent--;
        }

        emitLine("end");
        moduleScope = savedModuleScope;
        return null;
    }

    @Override
    public Void visit(ThrowStatement node) {
        Span throwSpan = node.span();
        if (node.expr() instanceof ObjectLiteralExpr objLit) {
            // Reify the thrown Error as a tagged builtin-Error class
            // instance (runtime-class-identity D3): positional arguments to
            // __rt.error_value(code, message, file, line, column). Provided
            // code/message keep their values; absent ones default to "".
            // The checker rejects extra fields in Error literals (E4002),
            // so only code/message can appear.
            String codeExpr = "\"\"";
            String messageExpr = "\"\"";
            for (Property prop : objLit.properties()) {
                if (prop.name().equals("code")) {
                    codeExpr = emitExpression(prop.value());
                } else if (prop.name().equals("message")) {
                    messageExpr = emitExpression(prop.value());
                }
            }
            StringBuilder sb = new StringBuilder("error(__rt.error_value(")
                .append(codeExpr).append(", ").append(messageExpr)
                .append(", \"")
                .append(escapeLuaStringNoQuotes(throwSpan.file()))
                .append("\", ").append(throwSpan.startLine())
                .append(", ").append(throwSpan.startColumn())
                .append("))");
            emitLine(sb.toString());
        } else {
            emitLine("error(" + emitExpression(node.expr()) + ")");
        }
        return null;
    }

    @Override
    public Void visit(Block node) {
        // A bare block is a lexical container like every other nesting
        // container: a class declared inside it is block-scoped, not
        // module-level, so its artifacts keep the scope-local form
        // (local <C>_defaults / local <C>_meta) and never write
        // __deal namespace keys (lua-abi-emission-layer D2.6).
        boolean savedModuleScope = moduleScope;
        moduleScope = false;
        walkStatements(node.statements());
        moduleScope = savedModuleScope;
        return null;
    }

    // =========================================================================
    // Expression emission
    // =========================================================================

    private String emitExpression(ExpressionNode expr) {
        return switch (expr) {
            case LiteralExpr lit -> emitLiteral(lit);
            case IdentifierExpr id -> emitIdentifier(id);
            case BinaryExpr bin -> emitBinary(bin);
            case UnaryExpr un -> emitUnary(un);
            case CallExpr call -> emitCall(call);
            case MemberAccessExpr mae -> emitMemberAccess(mae);
            case IndexExpr idx -> emitIndex(idx);
            case ArrayLiteralExpr arr -> emitArrayLiteral(arr);
            case ObjectLiteralExpr obj -> emitObjectLiteral(obj);
            case FunctionExpr fe -> emitFunctionExpr(fe);
            case HasExpr has -> emitHas(has);
            case AssignmentExpr assign -> emitAssignment(assign);
            case AwaitExpression await ->
                emitCheckExpr("coroutine.yield(" + emitExpression(await.callee()) + ")",
                    typeOf(await), await.span());
            case TemplateLiteralExpr tl -> emitTemplateLiteral(tl);
        };
    }

    @Override public Void visit(LiteralExpr node) {
        emitLine(emitLiteral(node)); return null;
    }

    private String emitLiteral(LiteralExpr lit) {
        return switch (lit.value()) {
            case LiteralValue.NullLiteral n -> "__NULL";
            case LiteralValue.BooleanLiteral b -> b.value() ? "true" : "false";
            case LiteralValue.IntLiteral i -> Long.toString(i.value());
            case LiteralValue.NumberLiteral n -> {
                double v = n.value();
                if (Double.isNaN(v)) yield "(0/0)";
                if (Double.isInfinite(v)) yield v > 0 ? "(1/0)" : "(-1/0)";
                yield Double.toString(v);
            }
            case LiteralValue.StringLiteral s -> escapeLuaString(s.value());
        };
    }

    @Override public Void visit(IdentifierExpr node) {
        emitLine(emitIdentifier(node)); return null;
    }

    /**
     * Emit an identifier. When forLoopShadowVar is set and the identifier
     * name matches, emits the shadow name (prefixed with "_") so that the
     * condition and update expressions in a for-let loop reference the
     * outer counter.
     */
    private String emitIdentifier(IdentifierExpr id) {
        String name = id.name();
        if (forLoopShadowVar != null && name.equals(forLoopShadowVar)) {
            return "_" + name;
        }
        if (name.indexOf('$') >= 0) {
            return LuaAbi.generatedRef(name);
        }
        return name;
    }

    @Override public Void visit(BinaryExpr node) {
        emitLine(emitBinary(node)); return null;
    }

    private String emitBinary(BinaryExpr bin) {
        Type leftType = typeOf(bin.left());
        Type rightType = typeOf(bin.right());
        String left = emitExpression(bin.left());
        String right = emitExpression(bin.right());
        BinaryOp op = bin.op();
        Span span = bin.span();
        String spanParam = spanArgs(span);

        if (op == BinaryOp.ADD && leftType instanceof Type.String
            && rightType instanceof Type.String) {
            return left + " .. " + right;
        }

        if (leftType instanceof Type.Int && rightType instanceof Type.Int) {
            return switch (op) {
                case ADD -> "__rt.int_add(" + left + ", " + right
                    + ", " + spanParam + ")";
                case SUB -> "__rt.int_sub(" + left + ", " + right
                    + ", " + spanParam + ")";
                case MUL -> "__rt.int_mul(" + left + ", " + right
                    + ", " + spanParam + ")";
                case DIV -> "__rt.int_div(" + left + ", " + right
                    + ", " + spanParam + ")";
                case MOD -> "__rt.int_mod(" + left + ", " + right
                    + ", " + spanParam + ")";
                case POW -> "__rt.int_pow(" + left + ", " + right
                    + ", " + spanParam + ")";
                default -> "(" + left + " " + opSymbolLua(op) + " " + right + ")";
            };
        }

        if (leftType instanceof Type.Number || rightType instanceof Type.Number) {
            return switch (op) {
                case ADD -> "(" + left + " + " + right + ")";
                case SUB -> "(" + left + " - " + right + ")";
                case MUL -> "(" + left + " * " + right + ")";
                case DIV -> "(" + left + " / " + right + ")";
                case MOD -> "(" + left + " % " + right + ")";
                case POW -> "(" + left + " ^ " + right + ")";
                default -> "(" + left + " " + opSymbolLua(op) + " " + right + ")";
            };
        }

        // Nullable-vs-nullable comparison
        if (leftType instanceof Type.Nullable && rightType instanceof Type.Nullable) {
            if (op == BinaryOp.EQ) {
                return "(" + left + " == " + right + " or ("
                    + "(" + left + " == nil or " + left + " == __NULL) and ("
                    + right + " == nil or " + right + " == __NULL)))";
            }
            if (op == BinaryOp.NEQ) {
                return "(not (" + left + " == " + right + " or ("
                    + "(" + left + " == nil or " + left + " == __NULL) and ("
                    + right + " == nil or " + right + " == __NULL))))";
            }
        }

        if (op == BinaryOp.EQ && isNullLiteral(bin.right()) && leftType instanceof Type.Nullable) {
            return "(" + left + " == nil or " + left + " == __NULL)";
        }
        if (op == BinaryOp.EQ && isNullLiteral(bin.left()) && rightType instanceof Type.Nullable) {
            return "(" + right + " == nil or " + right + " == __NULL)";
        }
        if (op == BinaryOp.NEQ && isNullLiteral(bin.right()) && leftType instanceof Type.Nullable) {
            return "(" + left + " ~= nil and " + left + " ~= __NULL)";
        }
        if (op == BinaryOp.NEQ && isNullLiteral(bin.left()) && rightType instanceof Type.Nullable) {
            return "(" + right + " ~= nil and " + right + " ~= __NULL)";
        }
        if (op == BinaryOp.EQ) return "(" + left + " == " + right + ")";
        if (op == BinaryOp.NEQ) return "(" + left + " ~= " + right + ")";
        if (op == BinaryOp.LT) return "(" + left + " < " + right + ")";
        if (op == BinaryOp.LTE) return "(" + left + " <= " + right + ")";
        if (op == BinaryOp.GT) return "(" + left + " > " + right + ")";
        if (op == BinaryOp.GTE) return "(" + left + " >= " + right + ")";
        if (op == BinaryOp.AND) return "(" + left + " and " + right + ")";
        if (op == BinaryOp.OR) return "(" + left + " or " + right + ")";

        return "(" + left + " " + opSymbolLua(op) + " " + right + ")";
    }

    @Override public Void visit(UnaryExpr node) {
        emitLine(emitUnary(node)); return null;
    }

    private String emitUnary(UnaryExpr un) {
        String expr = emitExpression(un.expr());
        return switch (un.op()) {
            case NOT -> "(not (" + expr + "))";
            case NEG -> {
                // Signed-int32 foundation I4 (refines emitter page D2):
                // under DEAL_V1_2_INT32 unary minus on an int-typed
                // operand routes through the checked negation gate so
                // -(-2147483648) raises E8004 at the negating
                // expression's own location and -0 normalizes to 0;
                // number negation stays native IEEE, and the legacy
                // profile keeps the raw (-expr) emission byte-identical
                // (the int32 gate/negation is profile-selected only).
                if (int32Mode && typeOf(un.expr()) instanceof Type.Int) {
                    yield "__rt.int_neg(" + expr + ", "
                        + spanArgs(un.span()) + ")";
                }
                yield "(-" + expr + ")";
            }
        };
    }

    @Override public Void visit(CallExpr node) {
        emitLine(emitCall(node)); return null;
    }

    private String emitCall(CallExpr call) {
        Type calleeType = typeOf(call.callee());
        StringBuilder args = new StringBuilder();
        for (int i = 0; i < call.args().size(); i++) {
            if (i > 0) args.append(", ");
            args.append(emitExpression(call.args().get(i)));
        }
        // v1.2 bytes intrinsic (emitter page D3): bytes(n) lowers to
        // __rt.bytes_new(__rt.check_int(<n>, span), span) directly — the
        // length argument is checked at the call site and the allocation
        // entry receives the call-site span. The guard mirrors the
        // checker's resolution: only the root bytes intrinsic routes here
        // (a checker-accepted module-level declaration named bytes removed
        // the intrinsic binding, so symbols.resolve returns the user
        // symbol and the generic branches below handle the call).
        if (call.callee() instanceof IdentifierExpr id
                && id.name().equals("bytes")
                && symbols.resolve(id.name()) instanceof Symbol.IntrinsicSymbol) {
            if (call.args().size() == 1) {
                return "__rt.bytes_new(__rt.check_int(" + args.toString()
                    + ", " + spanArgs(call.span()) + "), "
                    + spanArgs(call.span()) + ")";
            }
            // Checker-error programs with a wrong arity never execute; keep
            // the call structurally complete instead of emitting invalid
            // Lua.
            return "__rt.bytes_new(" + args.toString() + ", "
                + spanArgs(call.span()) + ")";
        }
        // Intrinsic calls (int, number) route through the wrapper's .f entry
        // so the call-site span is forwarded on direct calls; indirect calls
        // (callee not a bare intrinsic name) fall through to the generic
        // Type.Func branch and reach the same wrapper without a span.
        if (call.callee() instanceof IdentifierExpr id) {
            Symbol sym = symbols.resolve(id.name());
            if (sym instanceof Symbol.IntrinsicSymbol) {
                return emitExpression(call.callee()) + ".f(" + args.toString()
                    + ", " + spanArgs(call.span()) + ")";
            }
        }
        if (calleeType instanceof Type.Func) {
            return emitExpression(call.callee()) + ".f(" + args.toString() + ")";
        }
        return emitExpression(call.callee()) + "(" + args.toString() + ")";
    }

    @Override public Void visit(MemberAccessExpr node) {
        emitLine(emitMemberAccess(node)); return null;
    }

    private String emitMemberAccess(MemberAccessExpr mae) {
        String obj = emitExpression(mae.object());
        Type objType = typeOf(mae.object());
        String field = mae.field();
        if (field.equals("length") && objType instanceof Type.Array) {
            return "#" + obj;
        }
        // v1.2 bytes length (emitter page D3): b.length lowers to
        // __rt.bytes_length(<b>, span) — the compiler-resolved immutable
        // logical allocation length, never a member lookup, never
        // dispatchable (assignment/deletion of .length never reaches
        // codegen — frontend E3017).
        if (field.equals("length") && objType instanceof Type.Bytes) {
            return "__rt.bytes_length(" + obj + ", "
                + spanArgs(mae.span()) + ")";
        }
        return LuaAbi.memberAccess(obj, field);
    }

    @Override public Void visit(IndexExpr node) {
        String idx = emitIndex(node);
        if (idx.startsWith("(")) {
            emitLine(";" + idx);
        } else {
            emitLine(idx);
        }
        return null;
    }

    private String emitIndex(IndexExpr idx) {
        Type arrayType = typeOf(idx.array());
        String arr = emitExpression(idx.array());
        String index = emitExpression(idx.index());
        Span span = idx.span();
        if (arrayType instanceof Type.Array) {
            return "(function() local __idx = __rt.check_int(" + index + ", "
                + spanArgs(span) + "); if __idx < 0 then error(__rt._err(\"E8002\", "
                + "\"negative array index\", " + spanArgs(span) + ")) end; "
                + "return " + arr + "[__idx + 1] end)()";
        }
        // v1.2 bytes read (emitter page D3): b[i] lowers to
        // __rt.bytes_get(<b>, __rt.check_int(<i>, span), span) — the index
        // is checked at the read site and the bytes entry carries the
        // index expression's span for E8012.
        if (arrayType instanceof Type.Bytes) {
            return "__rt.bytes_get(" + arr + ", __rt.check_int(" + index
                + ", " + spanArgs(span) + "), " + spanArgs(span) + ")";
        }
        return arr + "[" + index + "]";
    }

    @Override public Void visit(ArrayLiteralExpr node) {
        emitLine(emitArrayLiteral(node)); return null;
    }

    private String emitArrayLiteral(ArrayLiteralExpr arr) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < arr.elements().size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(emitExpression(arr.elements().get(i)));
        }
        sb.append("}");
        return sb.toString();
    }

    @Override public Void visit(ObjectLiteralExpr node) {
        emitLine(emitObjectLiteral(node)); return null;
    }

    private String emitObjectLiteral(ObjectLiteralExpr obj) {
        Type expectedType = typeOf(obj);
        if (expectedType instanceof Type.Class cls) {
            return emitClassConstruction(cls, obj);
        }
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Property prop : obj.properties()) {
            if (!first) sb.append(", ");
            first = false;
            sb.append(LuaAbi.tableField(prop.name(),
                emitExpression(prop.value())));
        }
        sb.append("}");
        return sb.toString();
    }

    private String emitClassConstruction(Type.Class cls, ObjectLiteralExpr obj) {
        String className = cls.name();
        Symbol sym = symbols.resolve(className);

        StringBuilder provided = new StringBuilder("{");
        boolean first = true;
        for (Property prop : obj.properties()) {
            if (!first) provided.append(", ");
            first = false;
            provided.append(LuaAbi.tableField(prop.name(),
                emitExpression(prop.value())));
        }
        provided.append("}");

        String defaultsRef;
        if (sym instanceof Symbol.ClassSymbol cs) {
            // Root ClassSymbol (module-level class, including the seeded
            // Error): the artifact normally lives in the __deal namespace
            // table. When a non-module-level declaration of the same name
            // is lexically visible at the construction site (nested-class
            // shadowing, lua-abi-emission-layer D2.6), reference the bare
            // <C>_defaults local instead so Lua lexical scoping resolves to
            // the scope-local artifact, exactly as the pre-namespace
            // backend did.
            defaultsRef = hasVisibleNestedClassDeclaration(className)
                ? className + "_defaults"
                : LuaAbi.helperRef(className, LuaAbi.HelperKind.DEFAULTS);
        } else {
            // The import alias whose export carries this exact identity
            // wins first: two files in one directory share the module
            // identity, so alias presence — never identity locality —
            // distinguishes an imported class from a same-module nested
            // declaration.
            String alias = findImportAliasForClass(className, cls.identity());
            if (alias == null && isDeclaredInThisModule(cls)
                    && !cls.identity().moduleIdentity().equals(
                        CanonicalModuleIdentity.BuiltinModule.INSTANCE)) {
                // Same-module class that is not in the root symbol
                // table: a nested declaration (block/function-local).
                // Reference the scope-local <C>_defaults artifact when
                // it is lexically visible at the construction site
                // (lua-abi-emission-layer D2.6) — the checker
                // (ISSUE-0318 seam) now types these literals as class
                // constructions. A non-visible reference can only come
                // from a checker-error program, so the {} fallback
                // mirrors the imported-class defensive arm.
                defaultsRef = hasVisibleNestedClassDeclaration(className)
                    ? className + "_defaults"
                    : "{}";
            } else if (alias != null) {
                defaultsRef = LuaAbi.memberAccess(alias,
                    LuaAbi.helperKey(className, LuaAbi.HelperKind.DEFAULTS));
            } else {
                defaultsRef = "{}";
            }
        }

        Span cspan = obj.span();
        return "__rt.class_(" + quotedTypeDescriptor(cls) + ", " + defaultsRef
            + ", " + provided.toString() + ", " + spanArgs(cspan) + ")";
    }

    /**
     * Searches the root symbol table for a ModuleSymbol whose exports
     * include the given class with the exact canonical class identity.
     * Returns the import alias (module local name) or {@code null} if
     * not found.
     *
     * <p>The identity parameter is essential: when two imported
     * modules export classes with the same name (e.g., both {@code mod1}
     * and {@code mod2} export {@code class Result}), checking only the
     * class name would return the wrong alias and produce incorrect
     * defaults-table references (v1.2 identity carriage — imported
     * classes carry the declaring source's identity).
     */
    private String findImportAliasForClass(String className,
                                           CanonicalClassIdentity identity) {
        for (var entry : symbols.symbols().entrySet()) {
            Symbol sym = entry.getValue();
            if (sym instanceof Symbol.ModuleSymbol ms) {
                Type exportType = ms.exports().get(className);
                if (exportType instanceof Type.Class tc
                        && tc.identity().equals(identity)) {
                    return entry.getKey();
                }
            }
        }
        return null;
    }

    @Override public Void visit(FunctionExpr node) {
        emitLine(emitFunctionExpr(node)); return null;
    }

    private String emitFunctionExpr(FunctionExpr fe) {
        // DEAL v1.2: fixed parameter list only (no rest parameters).
        StringBuilder paramList = new StringBuilder();
        for (int i = 0; i < fe.params().size(); i++) {
            if (i > 0) paramList.append(", ");
            paramList.append(fe.params().get(i).name());
        }

        Type funcType = typeOf(fe);
        String sig = funcType instanceof Type.Func f
            ? quotedTypeDescriptor(f) : "\"()\"";

        Type savedReturn = currentReturnType;
        if (funcType instanceof Type.Func f) currentReturnType = f.returnType();

        int savedIndent = indent;
        indent = 0;
        functionDepth++;

        String bodyStr = captureOutput(() -> {
            boolean savedModuleScope = moduleScope;
            moduleScope = false;
            pushNestedClassFrame();
            indent = 1;

            for (Parameter param : fe.params()) {
                Type paramType = resolveTypeNode(param.type());
                if (paramType != null && !(paramType instanceof Type.Error)
                    && !(paramType instanceof Type.Null)) {
                    String checkFn = checkFunctionFor(paramType);
                    Span paramSpan = param.type().span();
                    if (checkFn != null) {
                        emitLine(checkFn + "(" + param.name() + ", "
                            + spanArgs(paramSpan) + ")");
                    } else {
                        emitLine(emitCheckExpr(param.name(), paramType, paramSpan));
                    }
                }
            }
            if (fe.isAsync()) {
                emitLine("return __rt.async_start(function()");
                indent++;
                visit(fe.body());
                indent--;
                emitLine("end)");  // close async_start
            } else {
                visit(fe.body());
            }
            popNestedClassFrame();
            moduleScope = savedModuleScope;
        });

        functionDepth--;
        currentReturnType = savedReturn;
        indent = savedIndent;

        return "__rt.function_(" + sig + ", function("
            + paramList.toString() + ")\n" + bodyStr
            + "  ".repeat(indent) + "end)";
    }

    @Override public Void visit(HasExpr node) {
        emitLine(emitHas(node)); return null;
    }

    private String emitHas(HasExpr has) {
        return LuaAbi.hasCheck(emitExpression(has.object()), has.field());
    }

    @Override public Void visit(AssignmentExpr node) {
        emitLine(emitAssignment(node)); return null;
    }

    @Override public Void visit(AwaitExpression node) {
        emitLine(emitExpression(node));
        return null;
    }

    private String emitTemplateLiteral(TemplateLiteralExpr tl) {
        List<ExpressionNode> parts = tl.parts();
        if (parts.size() == 1) {
            // No interpolations: emit plain string literal
            return emitExpression(parts.get(0));
        }
        StringBuilder sb = new StringBuilder("(");
        boolean first = true;
        for (int i = 0; i < parts.size(); i++) {
            ExpressionNode part = parts.get(i);
            if (i % 2 == 0) {
                // String part: skip if empty
                String emitted = emitExpression(part);
                if (emitted.equals("\"\"") || emitted.equals("''")) {
                    continue;
                }
                if (!first) sb.append(" .. ");
                sb.append(emitted);
                first = false;
            } else {
                // Expression part
                if (!first) sb.append(" .. ");
                sb.append(emitExpression(part));
                first = false;
            }
        }
        sb.append(")");
        return sb.toString();
    }

    private String emitAssignment(AssignmentExpr assign) {
        Type targetType = typeOf(assign.target());
        Type valueType = typeOf(assign.value());
        String targetLua = emitExpression(assign.target());
        String valueLua = emitExpression(assign.value());
        Span span = assign.span();

        if (isArityExtension(valueType, targetType)) {
            return targetLua + " = "
                + emitArityAdapter((Type.Func) targetType, (Type.Func) valueType, valueLua, span);
        }

        if (assign.target() instanceof IndexExpr idx) {
            Type arrType = typeOf(idx.array());
            if (arrType instanceof Type.Array arrT) {
                String arr = emitExpression(idx.array());
                String index = emitExpression(idx.index());

                if (idx.index() instanceof MemberAccessExpr mae
                    && mae.field().equals("length")
                    && typeOf(mae.object()) instanceof Type.Array) {
                    return arr + "[#" + arr + " + 1] = "
                        + emitCheckExpr(valueLua, arrT.element(), span);
                }

                String myIndent = "  ".repeat(indent);
                return "do\n"
                    + myIndent + "  local __arr = " + arr + "\n"
                    + myIndent + "  local __idx = __rt.check_int(" + index
                    + ", " + spanArgs(idx.span()) + ")\n"
                    + myIndent + "  if __idx < 0 or __idx > #__arr then error(__rt._err(\"E8002\", "
                    + "\"array index out of bounds\", " + spanArgs(idx.span()) + ")) end\n"
                    + myIndent + "  __arr[__idx + 1] = "
                    + emitCheckExpr(valueLua, arrT.element(), span) + "\n"
                    + myIndent + "end";
            }
            // v1.2 bytes write (emitter page D3): b[i] = v emits exactly
            // one single-evaluation sequence — the receiver and the checked
            // index evaluate before the RHS, the RHS evaluates before any
            // write validation (E8012 index, E8013 range, E8001 non-int
            // value, all raised inside __rt.bytes_set), and no operand text
            // is ever re-emitted. This replaces the duplicated, order-
            // sensitive emitAssignment target re-emission for bytes writes;
            // array write emission stays unchanged (common layer owns
            // semantic tables).
            if (arrType instanceof Type.Bytes) {
                String arr = emitExpression(idx.array());
                String index = emitExpression(idx.index());
                String myIndent = "  ".repeat(indent);
                return "do\n"
                    + myIndent + "  local __b = " + arr + "\n"
                    + myIndent + "  local __i = __rt.check_int(" + index
                    + ", " + spanArgs(idx.span()) + ")\n"
                    + myIndent + "  local __v = " + valueLua + "\n"
                    + myIndent + "  __rt.bytes_set(__b, __i, __v, "
                    + spanArgs(idx.span()) + ")\n"
                    + myIndent + "end";
            }
        }

        return targetLua + " = " + valueLua;
    }

    private boolean isArityExtension(Type valueType, Type targetType) {
        if (valueType instanceof Type.Func vf && targetType instanceof Type.Func tf) {
            return Types.isAssignable(vf, tf) && !Types.equals(vf, tf)
                && vf.paramTypes().size() < tf.paramTypes().size();
        }
        return false;
    }

    private String emitArityAdapter(Type.Func targetFunc, Type.Func valueFunc,
                                     String valueLua, Span span) {
        StringBuilder sb = new StringBuilder();
        sb.append("__rt.function_(").append(quotedTypeDescriptor(targetFunc))
            .append(", function(");
        for (int i = 0; i < targetFunc.paramTypes().size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append("__p").append(i);
        }
        sb.append(")\n");

        String bodyIndent = "  ".repeat(indent + 1);
        for (int i = 0; i < targetFunc.paramTypes().size(); i++) {
            Type pt = targetFunc.paramTypes().get(i);
            sb.append(bodyIndent).append(emitCheckExpr("__p" + i, pt, span)).append("\n");
        }

        sb.append(bodyIndent).append("return ");
        String innerCall = valueLua + ".f("
            + buildOverlappingArgs(valueFunc.paramTypes().size()) + ")";
        if (targetFunc.isAsync()) {
            // An async adapter returns the inner async operation handle
            // untouched: the declared return type is enforced at the
            // await site once the runtime resumes the awaiting
            // coroutine with the completion value (mirroring the
            // __rt.from_lua_function async dispatch rule). Checking the
            // handle here would raise E8001 against the operation table
            // before the inner operation ever completes (ISSUE-0099).
            sb.append(innerCall);
        } else {
            sb.append(emitCheckExpr(innerCall, targetFunc.returnType(), span));
        }
        sb.append("\n");
        sb.append("  ".repeat(indent)).append("end)");
        return sb.toString();
    }

    private String buildOverlappingArgs(int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) sb.append(", ");
            sb.append("__p").append(i);
        }
        return sb.toString();
    }

    // =========================================================================
    // Type node visitors (no-op)
    // =========================================================================

    @Override public Void visit(NamedType node) { return null; }
    @Override public Void visit(ArrayType node) { return null; }
    @Override public Void visit(NullableType node) { return null; }
    @Override public Void visit(FunctionType node) { return null; }

    // =========================================================================
    // ISSUE-0050: @jsonable codegen — deferred two-pass emission with
    // topological sort
    // =========================================================================

    /**
     * Metadata for a single @jsonable class, recorded during statement
     * walking and processed during {@link #emitJsonableCode()}.
     * No dependency names are pre-computed — the dependency graph is
     * built from scratch in emitJsonableCode() when deferredJsonables
     * is complete.
     */
    private static final class JsonableClassMeta {
        final String className;
        final List<ClassField> fields;

        /**
         * True when the class declaration sits at Lua chunk scope
         * (module-level). Non-module-level @jsonable export classes keep
         * their artifacts as scope-local locals in the old backend's
         * {@code $}→{@code _} local form ({@code local C_fields},
         * {@code local C_fromJson}, {@code local C_toJson}) and never
         * write {@code __deal} namespace keys (D2.6 ownership invariant).
         */
        final boolean moduleLevel;

        JsonableClassMeta(String className, List<ClassField> fields,
                          boolean moduleLevel) {
            this.className = className;
            this.fields = fields;
            this.moduleLevel = moduleLevel;
        }
    }

    /**
     * Emits all deferred @jsonable code after all statements have been walked.
     *
     * <p>Performs two sub-passes after topological sort by same-module
     * class-typed field dependencies:
     * <ol>
     *   <li>Field descriptor tables ({@code C_fields}) in dependency order.</li>
     *   <li>{@code C$fromJson} and {@code C$toJson} functions in the same order.</li>
     * </ol>
     *
     * <p>Because same-module circular @jsonable class dependencies are caught
     * as E4008 in Phase 3 (TypeChecker), the topological sort here is
     * guaranteed acyclic — no cycle error is emitted during codegen.</p>
     */
    private void emitJsonableCode() {
        if (deferredJsonables.isEmpty()) return;

        // Emit JSON module loading only when there are @jsonable classes.
        // This conditional emission avoids an unconditional require("std.json")
        // at the top of every module including non-jsonable ones, which would
        // force all modules to have std/ available at runtime.  Module-level
        // locals are scoped to the entire Lua chunk regardless of where they
        // appear, so emitting them here (after walkStatements) is functionally
        // identical to emitting them in emitHeader() — the generated C$fromJson
        // and C$toJson closures capture these locals correctly.
        // Access raw functions via .f because std.json exports are __rt.function_
        // wrappers (tables with no __call metamethod).
        emitLine("-- @jsonable: JSON module loading");
        emitLine("local __json = require(\"std.json\")");
        emitLine("local __json_parse = __json.parse.f");
        emitLine("local __json_stringify = __json.stringify.f");
        emitLine("");

        // Build lookup map by class name
        Map<String, JsonableClassMeta> metaByName = new LinkedHashMap<>();
        for (JsonableClassMeta meta : deferredJsonables) {
            metaByName.put(meta.className, meta);
        }
        Set<String> jsonableNames = metaByName.keySet();

        // Step 1: Build dependency graph from scratch against the complete set.
        // For each class A, scan each field's AST type node, recursively
        // unwrapping ArrayType and NullableType wrappers to find underlying
        // NamedType references. For each same-module @jsonable class B found,
        // add edge A → B (A depends on B, so B must be emitted before A).
        Map<String, Set<String>> deps = new LinkedHashMap<>();
        for (JsonableClassMeta meta : deferredJsonables) {
            Set<String> classDeps = new LinkedHashSet<>();
            for (ClassField field : meta.fields) {
                collectSameModuleDeps(field.type(), classDeps, jsonableNames);
            }
            deps.put(meta.className, classDeps);
        }

        // Step 2: Topological sort (Kahn's algorithm).
        // Build in-degree map: for each dep B of A, A must come after B.
        Map<String, Integer> inDegree = new LinkedHashMap<>();
        Map<String, List<String>> successors = new LinkedHashMap<>();
        for (String name : jsonableNames) {
            inDegree.put(name, 0);
            successors.put(name, new ArrayList<>());
        }
        for (var entry : deps.entrySet()) {
            String a = entry.getKey();
            for (String b : entry.getValue()) {
                // Edge B → A: B must precede A
                successors.get(b).add(a);
                inDegree.merge(a, 1, Integer::sum);
            }
        }

        // Start with nodes having no dependencies
        List<String> sorted = new ArrayList<>();
        Deque<String> queue = new ArrayDeque<>();
        for (var entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) {
                queue.add(entry.getKey());
            }
        }

        while (!queue.isEmpty()) {
            String name = queue.poll();
            sorted.add(name);
            for (String succ : successors.get(name)) {
                int deg = inDegree.merge(succ, -1, Integer::sum);
                if (deg == 0) {
                    queue.add(succ);
                }
            }
        }

        // If the sort didn't include all nodes, there's a cycle.
        // This shouldn't happen (E4008 in Phase 3), but handle gracefully.
        if (sorted.size() != jsonableNames.size()) {
            // Fall back to declaration order for any remaining nodes
            Set<String> sortedSet = new HashSet<>(sorted);
            for (JsonableClassMeta meta : deferredJsonables) {
                if (!sortedSet.contains(meta.className)) {
                    sorted.add(meta.className);
                }
            }
        }

        // Sub-pass 1: Field descriptors in sorted order
        emitLine("-- @jsonable field descriptors (topologically sorted)");
        for (String className : sorted) {
            JsonableClassMeta meta = metaByName.get(className);
            emitFieldDescriptor(meta);
        }
        emitLine();

        // Sub-pass 2: C$fromJson and C$toJson functions in sorted order
        emitLine("-- @jsonable serialization functions (topologically sorted)");
        for (String className : sorted) {
            JsonableClassMeta meta = metaByName.get(className);
            emitFromJson(meta);
            emitToJson(meta);
        }
        emitLine();
    }

    /**
     * Recursively scans an AST type node for same-module @jsonable class
     * dependencies. Unwraps ArrayType and NullableType wrappers to find
     * underlying NamedType references. QualifiedType (cross-module) is
     * skipped — imported class locals are already available via require.
     */
    private void collectSameModuleDeps(TypeNode typeNode, Set<String> deps,
                                        Set<String> jsonableNames) {
        TypeNode inner = typeNode;
        if (inner instanceof NullableType nt) {
            inner = nt.innerType();
        }
        if (inner instanceof ArrayType at) {
            collectSameModuleDeps(at.elementType(), deps, jsonableNames);
        } else if (inner instanceof NamedType nt) {
            if (jsonableNames.contains(nt.name())) {
                deps.add(nt.name());
            }
        }
        // QualifiedType: cross-module, no same-module dep
        // FunctionType: not jsonable, skip
    }

    /**
     * Emits the {@code C_fields} descriptor table for a single @jsonable class.
     */
    private void emitFieldDescriptor(JsonableClassMeta meta) {
        String name = meta.className;
        emitLine(meta.moduleLevel
            ? LuaAbi.namespaceAssignment(
                LuaAbi.helperKey(name, LuaAbi.HelperKind.FIELDS), "{")
            : "local " + LuaAbi.helperKey(name, LuaAbi.HelperKind.FIELDS) + " = {");
        indent++;
        List<ClassField> fields = meta.fields;
        for (int i = 0; i < fields.size(); i++) {
            ClassField field = fields.get(i);
            String comma = (i < fields.size() - 1) ? "," : "";
            emitLine(emitSingleFieldDescriptor(field) + comma);
        }
        indent--;
        emitLine("}");
    }

    /**
     * Emits a single field descriptor entry as a Lua table literal.
     */
    private String emitSingleFieldDescriptor(ClassField field) {
        TypeNode typeNode = field.type();
        boolean nullable = field.nullable();
        boolean optional = field.optional();

        // Unwrap NullableType to get the inner type for jtype determination
        TypeNode innerType = typeNode;
        if (innerType instanceof NullableType nt) {
            innerType = nt.innerType();
        }

        String jtype = jtypeForTypeNode(innerType);

        StringBuilder sb = new StringBuilder();
        sb.append("{ name = \"").append(field.name()).append("\"");
        sb.append(", jtype = \"").append(jtype).append("\"");
        sb.append(", optional = ").append(optional ? "true" : "false");
        sb.append(", nullable = ").append(nullable ? "true" : "false");

        if (jtype.equals("class")) {
            sb.append(", className = \"")
                .append(escapeLuaStringNoQuotes(classNameFromTypeNode(innerType)))
                .append("\"");
            sb.append(", defaults = ").append(defaultsRefForTypeNode(innerType));
            sb.append(", fields = ").append(fieldsRefForTypeNode(innerType));
        } else if (jtype.equals("array")) {
            ArrayType at = (ArrayType) innerType;
            sb.append(", element = ")
                .append(emitElementDescriptor(at.elementType()));
        }

        sb.append(" }");
        return sb.toString();
    }

    /**
     * Emits the element sub-descriptor for an array field.
     */
    private String emitElementDescriptor(TypeNode elementType) {
        // Check if the element itself is nullable before unwrapping
        boolean nullable = elementType instanceof NullableType;

        // Unwrap NullableType to get the inner type for jtype determination
        TypeNode inner = elementType;
        if (inner instanceof NullableType nt) {
            inner = nt.innerType();
        }
        String jtype = jtypeForTypeNode(inner);

        StringBuilder sb = new StringBuilder();
        sb.append("{ jtype = \"").append(jtype).append("\"");
        sb.append(", optional = false, nullable = ").append(nullable ? "true" : "false");

        if (jtype.equals("class")) {
            sb.append(", className = \"")
                .append(escapeLuaStringNoQuotes(classNameFromTypeNode(inner)))
                .append("\"");
            sb.append(", defaults = ").append(defaultsRefForTypeNode(inner));
            sb.append(", fields = ").append(fieldsRefForTypeNode(inner));
        } else if (jtype.equals("array")) {
            ArrayType at = (ArrayType) inner;
            sb.append(", element = ")
                .append(emitElementDescriptor(at.elementType()));
        }

        sb.append(" }");
        return sb.toString();
    }

    /**
     * Determines the jtype string for a TypeNode (after NullableType unwrapping).
     */
    private String jtypeForTypeNode(TypeNode typeNode) {
        return switch (typeNode) {
            case NamedType nt -> switch (nt.name()) {
                case "null" -> "null";
                case "boolean" -> "boolean";
                case "int" -> "int";
                case "number" -> "number";
                case "string" -> "string";
                case "table" -> "table";
                default -> "class";  // user-defined class
            };
            case ArrayType at -> "array";
            case QualifiedType qt -> "class";
            case NullableType nt -> jtypeForTypeNode(nt.innerType());
            case FunctionType ft -> "function";  // not expected for jsonable
        };
    }

    /**
     * Extracts the class identity descriptor for a type node representing a
     * class type. Resolved through {@link #resolveTypeNode} so cross-module
     * {@code QualifiedType} fields carry the defining module's true module
     * path (not the import alias), and same-module named classes carry the
     * checker's module path — byte-identical to construction tags and
     * {@code check_type} descriptors (runtime-class-identity D2(4)).
     */
    private String classNameFromTypeNode(TypeNode typeNode) {
        Type resolved = resolveTypeNode(typeNode);
        if (resolved instanceof Type.Class cls) {
            return typeDescriptor(cls);
        }
        return switch (typeNode) {
            case NamedType nt -> nt.name();
            case QualifiedType qt -> qt.typeName();
            default -> "Unknown";
        };
    }

    /**
     * Returns the Lua reference for the defaults table of a class-typed field.
     */
    private String defaultsRefForTypeNode(TypeNode typeNode) {
        return switch (typeNode) {
            case NamedType nt -> visibleNestedArtifactRef(nt.name(),
                LuaAbi.HelperKind.DEFAULTS);
            case QualifiedType qt -> LuaAbi.memberAccess(qt.moduleName(),
                LuaAbi.helperKey(qt.typeName(), LuaAbi.HelperKind.DEFAULTS));
            default -> "{}";
        };
    }

    /**
     * Returns the Lua reference for the fields descriptor table of a class-typed field.
     */
    private String fieldsRefForTypeNode(TypeNode typeNode) {
        return switch (typeNode) {
            case NamedType nt -> visibleNestedArtifactRef(nt.name(),
                LuaAbi.HelperKind.FIELDS);
            case QualifiedType qt -> LuaAbi.memberAccess(qt.moduleName(),
                LuaAbi.helperKey(qt.typeName(), LuaAbi.HelperKind.FIELDS));
            default -> "{}";
        };
    }

    /**
     * Reference to a class artifact used by the deferred @jsonable pass.
     * Module-level classes keep their artifacts in the {@code __deal}
     * namespace table; a class declared at non-module level whose artifact
     * local is still lexically visible at the deferred-pass emission point
     * (a bare chunk-level block emits no Lua scope, so its locals stay
     * visible for the rest of the chunk) is referenced by the bare local
     * name, exactly as the pre-namespace backend resolved it.
     */
    private String visibleNestedArtifactRef(String className,
                                            LuaAbi.HelperKind kind) {
        if (hasVisibleNestedClassDeclaration(className)) {
            // The legacy $→_ local form of the pre-namespace backend
            // (jsonable-v1.1 D9): locals cannot contain '$'.
            String suffix = switch (kind) {
                case DEFAULTS -> "_defaults";
                case META -> "_meta";
                case FIELDS -> "_fields";
                case FROM_JSON -> "_fromJson";
                case TO_JSON -> "_toJson";
            };
            return className + suffix;
        }
        return LuaAbi.helperRef(className, kind);
    }

    /**
     * Emits the {@code C$fromJson} function for a single @jsonable class.
     */
    private void emitFromJson(JsonableClassMeta meta) {
        String name = meta.className;
        String identity = qualifiedClassName(name);
        // Canonical nullable form (emitter page D1): "?<identity>", never
        // the legacy "<identity>|null" suffix.
        String sig = "(string)->?" + identity;

        // Non-module-level @jsonable classes keep the legacy $→_ scope-local
        // binding (local C_fromJson) and reference their scope-local
        // C_defaults/C_fields artifacts; only module-level classes write and
        // read the __deal namespace table (lua-abi-emission-layer D2.6).
        String defaultsRef = meta.moduleLevel
            ? LuaAbi.helperRef(name, LuaAbi.HelperKind.DEFAULTS)
            : name + "_defaults";
        String fieldsRef = meta.moduleLevel
            ? LuaAbi.helperRef(name, LuaAbi.HelperKind.FIELDS)
            : name + "_fields";

        emitLine(meta.moduleLevel
            ? LuaAbi.namespaceAssignment(
                LuaAbi.helperKey(name, LuaAbi.HelperKind.FROM_JSON),
                "__rt.function_(\"" + sig + "\", function(s)")
            : "local " + name + "_fromJson = __rt.function_(\"" + sig
                + "\", function(s)");
        indent++;
        emitLine("__rt.check_string(s)");
        emitLine("local ok, parsed = pcall(__json_parse, s)");
        emitLine("if not ok then return __NULL end");
        emitLine("local instance = __rt.json_from_json(\"" + identity
            + "\", parsed, " + defaultsRef + ", " + fieldsRef + ")");
        emitLine("if instance == nil then return __NULL end");
        emitLine("return instance");
        indent--;
        emitLine("end)");
        emitLine();
    }

    /**
     * Emits the {@code C$toJson} function for a single @jsonable class.
     */
    private void emitToJson(JsonableClassMeta meta) {
        String name = meta.className;
        String identity = qualifiedClassName(name);
        String sig = "(" + identity + ")->string";

        String fieldsRef = meta.moduleLevel
            ? LuaAbi.helperRef(name, LuaAbi.HelperKind.FIELDS)
            : name + "_fields";

        emitLine(meta.moduleLevel
            ? LuaAbi.namespaceAssignment(
                LuaAbi.helperKey(name, LuaAbi.HelperKind.TO_JSON),
                "__rt.function_(\"" + sig + "\", function(v)")
            : "local " + name + "_toJson = __rt.function_(\"" + sig
                + "\", function(v)");
        indent++;
        emitLine("__rt.check_type(\"" + identity + "\", v)");
        emitLine("local t = __rt.json_to_json(\"" + identity
            + "\", v, " + fieldsRef + ")");
        emitLine("return __json_stringify(t)");
        indent--;
        emitLine("end)");
        emitLine();
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private boolean isNullLiteral(ExpressionNode expr) {
        return expr instanceof LiteralExpr lit
            && lit.value() instanceof LiteralValue.NullLiteral;
    }

    private String escapeLuaString(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        sb.append("\"");
        return sb.toString();
    }

    /**
     * Like {@link #escapeLuaString} but without surrounding quotes.
     * Used for embedding file paths and runtime type descriptors in
     * generated code (see {@link #quotedTypeDescriptor}).
     */
    private String escapeLuaStringNoQuotes(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    private String opSymbolLua(BinaryOp op) {
        return switch (op) {
            case ADD -> "+"; case SUB -> "-"; case MUL -> "*";
            case DIV -> "/"; case MOD -> "%"; case POW -> "^";
            case EQ -> "=="; case NEQ -> "~="; case LT -> "<";
            case LTE -> "<="; case GT -> ">"; case GTE -> ">=";
            case AND -> "and"; case OR -> "or";
        };
    }

    private Type resolveTypeNode(TypeNode typeNode) {
        return switch (typeNode) {
            case NamedType nt -> switch (nt.name()) {
                case "null" -> Type.Null.INSTANCE;
                case "boolean" -> Type.Boolean.INSTANCE;
                case "int" -> Type.Int.INSTANCE;
                case "number" -> Type.Number.INSTANCE;
                case "string" -> Type.String.INSTANCE;
                case "table" -> Type.Table.INSTANCE;
                case "Error" -> errorClassType();
                // v1.2 bytes (emitter page D3): a bytes-spelled named
                // type resolves to the canonical Type.Bytes primitive.
                // bytes is not a DEAL keyword, so a checker-accepted
                // user class named bytes resolves to its ClassSymbol and
                // wins over the primitive (the JS sibling uses the same
                // class-symbol-first guard, js-v12-int32-bytes D3); the
                // visible nested-class scope frames carry the same
                // decision here.
                case "bytes" -> {
                    Symbol sym = symbols.resolve(nt.name());
                    if (sym instanceof Symbol.ClassSymbol cs) {
                        yield Types.classType(nt.name(), cs.identity());
                    }
                    if (hasVisibleNestedClassDeclaration(nt.name())) {
                        yield classTypeFor(nt.name(), modulePath);
                    }
                    yield Type.Bytes.INSTANCE;
                }
                default -> {
                    Symbol sym = symbols.resolve(nt.name());
                    if (sym instanceof Symbol.ClassSymbol cs) {
                        yield Types.classType(nt.name(), cs.identity());
                    }
                    yield Type.Error.INSTANCE;
                }
            };
            case QualifiedType qt -> {
                // Qualified type like B.Result — look up the module symbol
                // and extract the class type.
                Symbol sym = symbols.resolve(qt.moduleName());
                if (sym instanceof Symbol.ModuleSymbol ms) {
                    Type exportType = ms.exports().get(qt.typeName());
                    if (exportType != null) yield exportType;
                }
                yield classTypeFor(qt.typeName(), qt.moduleName());
            }
            case deal.ast.ArrayType at -> {
                Type elem = resolveTypeNode(at.elementType());
                if (elem == Type.Error.INSTANCE) yield Type.Error.INSTANCE;
                yield new Type.Array(elem);
            }
            case deal.ast.NullableType nt2 -> {
                Type inner = resolveTypeNode(nt2.innerType());
                if (inner == Type.Error.INSTANCE) yield Type.Error.INSTANCE;
                yield new Type.Nullable(inner);
            }
            case deal.ast.FunctionType ft -> {
                List<Type> paramTypes = new ArrayList<>();
                for (FunctionTypeParam ftp : ft.params()) {
                    Type pt = resolveTypeNode(ftp.type());
                    if (pt == Type.Error.INSTANCE) yield Type.Error.INSTANCE;
                    paramTypes.add(pt);
                }
                Type ret = resolveTypeNode(ft.returnType());
                if (ret == Type.Error.INSTANCE) yield Type.Error.INSTANCE;
                yield new Type.Func(paramTypes, ret, ft.isAsync());
            }
        };
    }
}
