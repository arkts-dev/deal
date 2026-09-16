package deal.test.conformance;

import deal.ast.ArrayType;
import deal.ast.ClassDeclaration;
import deal.ast.ExportDeclaration;
import deal.ast.NamedType;
import deal.ast.NullableType;
import deal.ast.ProgramNode;
import deal.ast.StatementNode;
import deal.ast.TypeNode;
import deal.checker.Symbol;
import deal.descriptors.CanonicalRuntimeTypeDescriptor;
import deal.diagnostics.CompilerDiagnostic;
import deal.ffi.FfiDeclarationValidator;
import deal.ffi.FfiGeneratedModule;
import deal.ffi.LuaFfiBindingGenerator;
import deal.identity.CanonicalClassIdentity;
import deal.identity.CanonicalModuleIdentity;
import deal.lexer.LexResult;
import deal.lexer.Lexer;
import deal.module.ExportExtractor;
import deal.module.ModuleIdentityAssembly;
import deal.module.SemanticModuleIdentity;
import deal.module.SourceModuleLocation;
import deal.parser.ParseResult;
import deal.parser.Parser;
import deal.project.ConfiguredModuleRoot;
import deal.project.ExternalEntry;
import deal.project.NativeLibraryRef;
import deal.project.NormalizedDeclarationPath;
import deal.project.OutputConfigResolver;
import deal.project.ProjectContext;
import deal.project.ProjectDeploymentIdentity;
import deal.project.ProtectedPathOps;
import deal.semantic.ir.CanonicalJson;
import deal.semantic.ir.SemanticIrTextDecodeException;
import deal.semantic.ir.SemanticProfile;
import deal.test.ConformanceHarnessMetadata;
import deal.types.Type;
import deal.types.Types;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The corpus C FFI support surface (ISSUE-0507 FFI candidate fixture
 * conformance): the closed externals wiring of
 * {@code test/conformance/backend-runtime/ffi/deal.json}, the GCC
 * native-library bootstrap over
 * {@code backend-runtime/ffi/support/native.c}, the production
 * declaration validation ({@link FfiDeclarationValidator}), the
 * production FFIGEN boundary
 * ({@link LuaFfiBindingGenerator#generate}), and the checker-facing
 * export/class-symbol surface every lane's module resolver consumes.
 *
 * <p>The wiring is corpus-owned: the harness reads the on-disk
 * {@code deal.json} (schema: the {@code externals} map of raw import
 * specifier &rarr; {@code declaration} + {@code nativeLibrary}) and
 * derives every resolution from it — no import path, declaration, or
 * library is hardcoded in code. The {@code nativeLibrary} loader text
 * names a library under {@code support/}; the bootstrap GCC-compiles
 * {@code support/native.c} into the ignored {@code build/} directory
 * once per JVM and the loader text is re-pointed at that
 * symlink-resolved absolute artifact (kind {@code ABSOLUTE_PATH}), so
 * the generated {@code load_ffi} call sites open the real library at
 * runtime and the committed tree is never mutated. A loader text whose
 * basename is not the bootstrapped library's basename names a library
 * that is never built — the honest missing-library wiring of the
 * {@code candidate/native-missing-library} entry (the runtime open is
 * the existence check).</p>
 *
 * <p>The per-declaration identity surface is the real production one:
 * a {@link ProjectContext} with the externals entry, a
 * {@link ModuleIdentityAssembly} over it, and a
 * {@link SourceModuleLocation} classified as the
 * {@code ExternalModule(raw)} declaration — the validator's
 * class-identity gates therefore run exactly as they do under the
 * orchestrator. The bootstrap is fail-closed: a missing GCC, a failed
 * fixture compile, or an unreadable wiring file fails the lane
 * honestly (an {@link IllegalStateException} the caller records as a
 * fixture/harness failure), never a skip.</p>
 *
 * <p>The component is test-side only: production code never depends on
 * corpus artifacts, and no production FFI behavior is added beyond the
 * codegen seam the real emission path exposes.</p>
 */
public final class CorpusFfi {

    /** The corpus-relative FFI directory (slash form). */
    public static final String FFI_DIR = "backend-runtime/ffi";

    /** The corpus-relative support directory (slash form). */
    public static final String SUPPORT_DIR = FFI_DIR + "/support";

    /** The corpus-owned externals wiring file name. */
    public static final String CONFIG_FILE_NAME = "deal.json";

    /** The committed C library source file name under {@code support/}. */
    public static final String NATIVE_SOURCE_NAME = "native.c";

    /** The bootstrapped native library basename under {@code build/}. */
    public static final String NATIVE_LIBRARY_NAME = "libcandidate_native.so";

    private CorpusFfi() {
        // Static utility; no instances.
    }

    // =========================================================================
    // Wiring records
    // =========================================================================

    /**
     * One externals wiring entry of the corpus config: the raw import
     * specifier, the declaration's corpus-relative slash path, and the
     * authored native-library loader text.
     */
    public record Wiring(String rawImportSpecifier,
                         String declarationCorpusPath,
                         String nativeLibraryLoaderText) {

        public Wiring {
            Objects.requireNonNull(rawImportSpecifier, "rawImportSpecifier");
            Objects.requireNonNull(declarationCorpusPath,
                "declarationCorpusPath");
            Objects.requireNonNull(nativeLibraryLoaderText,
                "nativeLibraryLoaderText");
        }

        /** The dotted typing/class-identity module path. */
        public String dottedPath() {
            return rawImportSpecifier.replace('/', '.');
        }

        /** The canonical external module identity text. */
        public String canonicalExternalIdentity() {
            return "@$external/" + rawImportSpecifier;
        }
    }

    /** The loaded corpus wiring, cached per normalized conformance root. */
    private static volatile Map<String, Map<String, Wiring>> wiringCache;

    /** The bootstrapped library path text, cached per JVM (lazy). */
    private static volatile String cachedLibraryPath;

    /** The per-wiring compiled declaration surface, keyed by root + raw. */
    private static final Map<String, Module> MODULE_CACHE =
        new LinkedHashMap<>();

    /**
     * The compiled declaration surface of one wiring entry: the
     * checker-facing exports and class symbols, the production
     * validation diagnostics, and the FFIGEN-generated module for the
     * LuaJIT emission path (null when validation failed).
     */
    public static final class Module {
        private final Map<String, Type> exports;
        private final Map<String, Symbol.ClassSymbol> classSymbols;
        private final List<CompilerDiagnostic> validationDiagnostics;
        private final FfiGeneratedModule generatedModule;

        Module(Map<String, Type> exports,
                Map<String, Symbol.ClassSymbol> classSymbols,
                List<CompilerDiagnostic> validationDiagnostics,
                FfiGeneratedModule generatedModule) {
            this.exports = Collections.unmodifiableMap(
                new LinkedHashMap<>(exports));
            this.classSymbols = Collections.unmodifiableMap(
                new LinkedHashMap<>(classSymbols));
            this.validationDiagnostics = List.copyOf(validationDiagnostics);
            this.generatedModule = generatedModule;
        }

        /** The declared export map (the checker's import surface). */
        public Map<String, Type> exports() {
            return exports;
        }

        /** The synthesized class symbols (canonical external identities). */
        public Map<String, Symbol.ClassSymbol> classSymbols() {
            return classSymbols;
        }

        /** The production FfiDeclarationValidator diagnostics. */
        public List<CompilerDiagnostic> validationDiagnostics() {
            return validationDiagnostics;
        }

        /** The FFIGEN-generated module, or null when validation failed. */
        public FfiGeneratedModule generatedModule() {
            return generatedModule;
        }
    }

    // =========================================================================
    // Wiring loading
    // =========================================================================

    /** The FFI config file of one conformance root. */
    public static Path configFile(Path conformanceRoot) {
        return conformanceRoot.resolve(FFI_DIR).resolve(CONFIG_FILE_NAME);
    }

    /** The corpus-relative wiring table (raw specifier &rarr; entry).
     * An absent config file is the empty table (a scratch corpus carries
     * no FFI surface); a present-but-unreadable config fails closed. */
    public static Map<String, Wiring> wiring(Path conformanceRoot) {
        String key = conformanceRoot.toAbsolutePath().normalize().toString();
        Map<String, Map<String, Wiring>> cached = wiringCache;
        if (cached != null && cached.containsKey(key)) {
            return cached.get(key);
        }
        synchronized (CorpusFfi.class) {
            cached = wiringCache;
            if (cached != null && cached.containsKey(key)) {
                return cached.get(key);
            }
            Map<String, Wiring> loaded = loadWiring(conformanceRoot);
            Map<String, Map<String, Wiring>> next =
                new LinkedHashMap<>();
            if (cached != null) {
                next.putAll(cached);
            }
            next.put(key, loaded);
            wiringCache = Map.copyOf(next);
            return loaded;
        }
    }

    /** True when the module path names a corpus FFI externals import. */
    public static boolean isFfiImport(Path conformanceRoot, String modulePath) {
        if (modulePath == null || modulePath.isEmpty()) {
            return false;
        }
        return wiringFor(conformanceRoot, modulePath) != null;
    }

    /** The wiring entry of one FFI import path, or null when absent. */
    public static Wiring wiringFor(Path conformanceRoot, String modulePath) {
        if (modulePath == null || modulePath.isEmpty()) {
            return null;
        }
        Wiring direct = wiring(conformanceRoot).get(modulePath);
        return direct != null ? direct
            : wiring(conformanceRoot).get(modulePath.replace('.', '/'));
    }

    private static Map<String, Wiring> loadWiring(Path conformanceRoot) {
        Path file = configFile(conformanceRoot);
        if (!Files.isRegularFile(file)) {
            return Map.of();
        }
        String json;
        try {
            json = Files.readString(file);
        } catch (IOException e) {
            throw new IllegalStateException(
                "cannot read the corpus FFI wiring " + file + ": "
                    + e.getMessage());
        }
        CanonicalJson.Value root;
        try {
            root = CanonicalJson.parse(json);
        } catch (SemanticIrTextDecodeException | IllegalArgumentException e) {
            throw new IllegalStateException(
                "malformed corpus FFI wiring " + file + ": "
                    + e.getMessage());
        }
        if (!(root instanceof CanonicalJson.Obj obj)) {
            throw new IllegalStateException(
                "the corpus FFI wiring root must be a JSON object: " + file);
        }
        Map<String, CanonicalJson.Value> fields = new LinkedHashMap<>();
        for (CanonicalJson.Entry entry : obj.entries()) {
            fields.put(entry.key(), entry.value());
        }
        CanonicalJson.Value externalsValue = fields.get("externals");
        if (!(externalsValue instanceof CanonicalJson.Obj externals)) {
            throw new IllegalStateException(
                "the corpus FFI wiring carries no externals object: "
                    + file);
        }
        Map<String, Wiring> result = new LinkedHashMap<>();
        for (CanonicalJson.Entry entry : externals.entries()) {
            if (!(entry.value() instanceof CanonicalJson.Obj wiringObj)) {
                throw new IllegalStateException(
                    "the corpus FFI wiring entry '" + entry.key()
                        + "' must be a JSON object: " + file);
            }
            Map<String, CanonicalJson.Value> entryFields = new LinkedHashMap<>();
            for (CanonicalJson.Entry field : wiringObj.entries()) {
                entryFields.put(field.key(), field.value());
            }
            String declaration = stringField(entryFields, "declaration",
                file, entry.key());
            String library = stringField(entryFields, "nativeLibrary",
                file, entry.key());
            result.put(entry.key(),
                new Wiring(entry.key(), declaration, library));
        }
        return Collections.unmodifiableMap(result);
    }

    private static String stringField(Map<String, CanonicalJson.Value> fields,
            String key, Path file, String raw) {
        CanonicalJson.Value value = fields.get(key);
        if (!(value instanceof CanonicalJson.Str str)) {
            throw new IllegalStateException(
                "the corpus FFI wiring entry '" + raw + "' carries no "
                    + key + " string: " + file);
        }
        return str.value();
    }

    // =========================================================================
    // Native-library bootstrap (GCC, fail-closed)
    // =========================================================================

    /**
     * The symlink-resolved absolute path text of the bootstrapped native
     * library: the GCC compile of {@code support/native.c} into the
     * ignored {@code build/} directory, run once per JVM. A missing GCC,
     * a missing fixture source, or a failed compile raises
     * {@link IllegalStateException} — the lanes record it as a fixture
     * failure, never a skip (the fail-closed tool contract).
     */
    public static String nativeLibraryPath(Path conformanceRoot) {
        String cached = cachedLibraryPath;
        if (cached != null) {
            return cached;
        }
        synchronized (CorpusFfi.class) {
            cached = cachedLibraryPath;
            if (cached != null) {
                return cached;
            }
            cached = bootstrapLibrary(conformanceRoot);
            cachedLibraryPath = cached;
            return cached;
        }
    }

    private static String bootstrapLibrary(Path conformanceRoot) {
        Path nativeSource = conformanceRoot.resolve(SUPPORT_DIR)
            .resolve(NATIVE_SOURCE_NAME).toAbsolutePath().normalize();
        if (!Files.isRegularFile(nativeSource)) {
            throw new IllegalStateException(
                "the committed FFI C fixture is missing: " + nativeSource);
        }
        Path buildDir = Path.of("build").toAbsolutePath().normalize();
        try {
            Files.createDirectories(buildDir);
        } catch (IOException e) {
            throw new IllegalStateException(
                "cannot create the FFI bootstrap directory " + buildDir
                    + ": " + e.getMessage());
        }
        Path library = buildDir.resolve(NATIVE_LIBRARY_NAME);
        try {
            Process gcc = new ProcessBuilder("gcc", "-shared", "-fPIC",
                "-O2", "-o", library.toString(), nativeSource.toString())
                .redirectErrorStream(true)
                .start();
            String output = new String(
                gcc.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
            int exit = gcc.waitFor();
            if (exit != 0 || !Files.isRegularFile(library)) {
                throw new IllegalStateException(
                    "the FFI native-library bootstrap failed (gcc exit "
                        + exit + "): " + output.trim());
            }
        } catch (IOException | InterruptedException e) {
            Thread.interrupted(); // clear the residual flag (no-op normally)
            throw new IllegalStateException(
                "the FFI native-library bootstrap failed: "
                    + e.getMessage());
        }
        ProtectedPathOps.PathResult resolved =
            ProtectedPathOps.normalizePrefixResolved(library.toString());
        return resolved instanceof ProtectedPathOps.PathResult.Success
            success ? success.resolvedPath().toString()
            : library.toString();
    }

    /**
     * The loader text one wiring entry emits: the bootstrapped absolute
     * library path when the authored loader basename equals the
     * bootstrapped library's basename, and the never-built sibling path
     * under {@code build/} otherwise (the honest missing-library
     * wiring — the runtime open is the existence check).
     */
    public static String loaderTextFor(Path conformanceRoot, Wiring wiring) {
        String basename = Path.of(wiring.nativeLibraryLoaderText())
            .getFileName().toString();
        if (NATIVE_LIBRARY_NAME.equals(basename)) {
            return nativeLibraryPath(conformanceRoot);
        }
        Path buildDir = Path.of("build").toAbsolutePath().normalize();
        ProtectedPathOps.PathResult resolved =
            ProtectedPathOps.normalizePrefixResolved(
                buildDir.resolve(basename).toString());
        return resolved instanceof ProtectedPathOps.PathResult.Success
            success ? success.resolvedPath().toString()
            : buildDir.resolve(basename).toString();
    }

    // =========================================================================
    // Declaration compile surface (validation + FFIGEN + checker exports)
    // =========================================================================

    /** The cache key of one wiring entry under one conformance root. */
    private static String cacheKey(Path conformanceRoot, String raw) {
        return conformanceRoot.toAbsolutePath().normalize() + "|" + raw;
    }

    /**
     * The compiled declaration surface of one FFI import: parses the
     * corpus declaration, runs the production
     * {@link FfiDeclarationValidator} with the real identity assembly,
     * runs the production FFIGEN boundary on a validated declaration,
     * and synthesizes the checker-facing export/class-symbol surface
     * (canonical external identities — the same shape the host registry
     * provides). Cached per (conformance root, raw specifier).
     */
    public static Module module(Path conformanceRoot, String modulePath,
            SemanticProfile profile) {
        Objects.requireNonNull(profile, "profile must not be null");
        Wiring wiring = wiringFor(conformanceRoot, modulePath);
        if (wiring == null) {
            throw new IllegalArgumentException(
                "not a corpus FFI import: " + modulePath);
        }
        String key = cacheKey(conformanceRoot, wiring.rawImportSpecifier());
        synchronized (MODULE_CACHE) {
            Module cached = MODULE_CACHE.get(key);
            if (cached != null) {
                return cached;
            }
        }
        Module compiled = compileDeclaration(conformanceRoot, wiring,
            profile);
        synchronized (MODULE_CACHE) {
            MODULE_CACHE.putIfAbsent(key, compiled);
            return MODULE_CACHE.get(key);
        }
    }

    private static Module compileDeclaration(Path conformanceRoot,
            Wiring wiring, SemanticProfile profile) {
        // The declaration path is manifest-relative: resolved against
        // the FFI directory that carries the corpus deal.json.
        Path declaration = conformanceRoot.resolve(FFI_DIR)
            .resolve(wiring.declarationCorpusPath())
            .toAbsolutePath().normalize();
        String filename = declaration.toString();
        String source;
        try {
            source = ConformanceHarnessMetadata.stripClassificationHeaders(
                Files.readString(declaration));
        } catch (IOException e) {
            throw new IllegalStateException(
                "cannot read the corpus FFI declaration " + declaration
                    + ": " + e.getMessage());
        }

        LexResult lex = new Lexer(source, filename).tokenize();
        if (lex.hasErrors()) {
            return new Module(Map.of(), Map.of(), lex.diagnostics(), null);
        }
        Parser parser = new Parser(lex.tokens(), filename, profile,
            lex.directiveEvents());
        ParseResult parseResult = parser.parse();
        if (parseResult.hasErrors()) {
            return new Module(Map.of(), Map.of(),
                parseResult.diagnostics(), null);
        }
        ProgramNode program = parseResult.program();

        // The checker-facing surface (the host-registry shape): exports
        // plus class symbols carrying the canonical external identities.
        CanonicalModuleIdentity externalIdentity =
            new CanonicalModuleIdentity.ExternalModule(
                wiring.rawImportSpecifier());
        Map<String, CanonicalModuleIdentity> classification =
            new LinkedHashMap<>();
        classification.put(wiring.dottedPath(), externalIdentity);
        ExportExtractor extractor = new ExportExtractor(wiring.dottedPath(),
            true, classification::get);
        Map<String, Type> exports = extractor.extract(program);
        Map<String, Symbol.ClassSymbol> classSymbols = new LinkedHashMap<>();
        for (StatementNode stmt : program.statements()) {
            ClassDeclaration cd = null;
            if (stmt instanceof ClassDeclaration c) {
                cd = c;
            } else if (stmt instanceof ExportDeclaration exp
                    && exp.declaration() instanceof ClassDeclaration c) {
                cd = c;
            }
            if (cd != null) {
                classSymbols.put(cd.name(), new Symbol.ClassSymbol(
                    cd.name(), cd.fields(), wiring.dottedPath(),
                    new CanonicalClassIdentity(externalIdentity,
                        cd.name())));
            }
        }

        // The production FFI declaration validation over the real
        // identity assembly (the orchestrator's phase-3.8 surface),
        // followed by the production FFIGEN boundary on a validated
        // declaration.
        String loaderText = loaderTextFor(conformanceRoot, wiring);
        IdentitySurface surface = identitySurface(conformanceRoot, wiring,
            declaration, loaderText);
        FfiDeclarationValidator.Result result =
            FfiDeclarationValidator.validate(
                program, wiring.dottedPath(), surface.location(),
                wiring.canonicalExternalIdentity(), surface.assembly(),
                new CanonicalRuntimeTypeDescriptor(
                    surface.assembly().index()),
                NativeLibraryRef.Kind.ABSOLUTE_PATH.name(),
                loaderText,
                List.of(wiring.dottedPath()),
                Map.of());
        FfiGeneratedModule generated = null;
        if (!result.hasErrors()) {
            LuaFfiBindingGenerator.GeneratedBindings bindings =
                LuaFfiBindingGenerator.generate(result.descriptor(),
                    result.importedFunctions(), result.importedClassPlans());
            generated = new FfiGeneratedModule(wiring.dottedPath(),
                result.descriptor(), bindings.cdefBundle(),
                bindings.plans(), bindings.bindings());
        }
        return new Module(exports, classSymbols, result.diagnostics(),
            generated);
    }

    /** The real identity surface: context + assembly + source location. */
    private record IdentitySurface(ProjectContext context,
                                   ModuleIdentityAssembly assembly,
                                   SourceModuleLocation location) {
    }

    /**
     * The real production identity assembly over an externals-carrying
     * project context (the test-only isolated-phase context shape the
     * orchestrator synthesizes; the validator's class-identity gates
     * run identically).
     */
    private static IdentitySurface identitySurface(Path conformanceRoot,
            Wiring wiring, Path declaration, String loaderText) {
        Path ffiDir = conformanceRoot.resolve(FFI_DIR)
            .toAbsolutePath().normalize();
        String ffiDirText = ffiDir.toString();
        Path config = configFile(conformanceRoot).toAbsolutePath()
            .normalize();
        ProjectDeploymentIdentity deployment;
        try {
            deployment = new ProjectDeploymentIdentity(
                fileUriOf(config),
                sha256Hex(Files.readAllBytes(config)));
        } catch (IOException e) {
            throw new IllegalStateException(
                "cannot read the corpus FFI wiring " + config + ": "
                    + e.getMessage());
        }
        ExternalEntry external = new ExternalEntry(
            wiring.rawImportSpecifier(),
            new NormalizedDeclarationPath(declaration.toString(), null),
            new NativeLibraryRef(NativeLibraryRef.Kind.ABSOLUTE_PATH,
                loaderText, null),
            null);
        ProjectContext context = new ProjectContext(
            config.toString(),
            ffiDirText,
            ffiDirText,
            "1.2",
            List.of(new ConfiguredModuleRoot("ffi", ffiDirText, null)),
            new OutputConfigResolver.OutputRef(
                OutputConfigResolver.Source.MANIFEST,
                OutputConfigResolver.Kind.ABSOLUTE_PATH,
                ffiDirText, ffiDirText, null),
            "luajit",
            Map.of(wiring.rawImportSpecifier(), external),
            "1.2",
            null,
            List.of(),
            deployment);
        ModuleIdentityAssembly assembly = new ModuleIdentityAssembly(
            context);
        CanonicalModuleIdentity externalIdentity =
            new CanonicalModuleIdentity.ExternalModule(
                wiring.rawImportSpecifier());
        SourceModuleLocation location = new SourceModuleLocation(
            declaration.toString(),
            new SemanticModuleIdentity(deployment,
                fileUriOf(declaration)),
            deploymentModuleIdOf(declaration.toString()),
            null,
            externalIdentity);
        return new IdentitySurface(context, assembly, location);
    }

    /** The protected-resolved {@code file:} URI text of one path. */
    private static String fileUriOf(Path path) {
        ProtectedPathOps.UriResult uri = ProtectedPathOps.toFileUri(path);
        if (!(uri instanceof ProtectedPathOps.UriResult.Success success)) {
            throw new IllegalStateException(
                "cannot derive a file URI for " + path + ": "
                    + ((ProtectedPathOps.UriResult.Failure) uri).reason());
        }
        return success.uri().toString();
    }

    private static String deploymentModuleIdOf(String filename) {
        ProtectedPathOps.ByteResult framed =
            ProtectedPathOps.lengthPrefixedUtf8(filename);
        if (!(framed instanceof ProtectedPathOps.ByteResult.Success
                success)) {
            throw new IllegalStateException(
                "cannot frame the FFI declaration path: "
                    + ((ProtectedPathOps.ByteResult.Failure) framed)
                        .reason());
        }
        return "m" + sha256Hex(success.bytes()).substring(0, 16);
    }

    /** SHA-256 of bytes as 64 lowercase hex characters (the shared
     * canonical digest facility — the single implementation assertion
     * admits only the pinned digest sites). */
    private static String sha256Hex(byte[] bytes) {
        return CanonicalJson.sha256Hex(bytes);
    }

    // =========================================================================
    // Checker-facing resolution helpers (the host-registry surface)
    // =========================================================================

    /**
     * Resolves a field type annotation of an FFI class against the
     * declaration's own class registry (the host-registry shape):
     * NamedType nodes naming registered classes resolve to
     * {@code Types.classType(name, identity)}; ArrayType/NullableType
     * wrappers rebuild around resolved inners; any other shape returns
     * null so the caller falls back to its local resolution.
     */
    public static Type resolveTypeNode(TypeNode typeNode,
            Map<String, Symbol.ClassSymbol> classSymbols) {
        if (typeNode instanceof NamedType nt) {
            Symbol.ClassSymbol cs = classSymbols.get(nt.name());
            if (cs != null) {
                return Types.classType(nt.name(), cs.identity());
            }
            return null;
        }
        if (typeNode instanceof ArrayType at) {
            Type element = resolveTypeNode(at.elementType(), classSymbols);
            return element == null ? null : Types.array(element);
        }
        if (typeNode instanceof NullableType nullable) {
            Type inner = resolveTypeNode(nullable.innerType(),
                classSymbols);
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

    /**
     * True when the declaring external module is a corpus FFI import and
     * the function is one of its declared exports (the
     * {@code isFunctionExportedFromModule} host-registry surface).
     */
    public static boolean declaresFunction(Path conformanceRoot,
            CanonicalModuleIdentity declaringModule, String functionName,
            SemanticProfile profile) {
        if (!(declaringModule
                instanceof CanonicalModuleIdentity.ExternalModule ext)) {
            return false;
        }
        Wiring wiring = wiringFor(conformanceRoot,
            ext.rawImportSpecifier());
        if (wiring == null) {
            return false;
        }
        return module(conformanceRoot, ext.rawImportSpecifier(), profile)
            .exports().containsKey(functionName);
    }

    /**
     * The class symbol of an FFI import's declaring external module, or
     * null (the {@code resolveClassSymbol} host-registry surface).
     */
    public static Symbol.ClassSymbol classSymbol(Path conformanceRoot,
            CanonicalModuleIdentity declaringModule, String className,
            SemanticProfile profile) {
        if (!(declaringModule
                instanceof CanonicalModuleIdentity.ExternalModule ext)) {
            return null;
        }
        Wiring wiring = wiringFor(conformanceRoot,
            ext.rawImportSpecifier());
        if (wiring == null) {
            return null;
        }
        return module(conformanceRoot, ext.rawImportSpecifier(), profile)
            .classSymbols().get(className);
    }
}
