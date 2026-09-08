package deal.test.conformance;

import deal.ast.ClassDeclaration;
import deal.ast.ExportDeclaration;
import deal.ast.FunctionDeclaration;
import deal.ast.ProgramNode;
import deal.ast.StatementNode;
import deal.ast.TypeNode;
import deal.checker.ModuleResolver;
import deal.checker.NameResolver;
import deal.checker.Symbol;
import deal.identity.CanonicalModuleIdentity;
import deal.lexer.Lexer;
import deal.lexer.LexResult;
import deal.module.ExportExtractor;
import deal.module.StdlibModuleResolver;
import deal.parser.ParseResult;
import deal.parser.Parser;
import deal.semantic.ir.SemanticProfile;
import deal.test.IdentityTestFixtures;
import deal.types.Type;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The corpus-aware module resolver of the gate's frontend-corpus
 * execution (ISSUE-0357; design {@code v12-zero-skip-conformance-gate}
 * G1): the same resolution surface the legacy runners' compile-stage
 * tests use — spec-listed stdlib exports from the stdlib declarations,
 * relative {@code ./} / {@code ../} corpus imports resolved through the
 * on-disk corpus (the discovered fixtures' header-stripped sources) via
 * {@link ExportExtractor}, and synthesized class symbols carrying the
 * single-module standalone identity convention.
 *
 * <p>Backend-neutral: no backend executes; the resolver only feeds the
 * real frontend pipeline ({@link FrontendCompiler}).</p>
 */
final class CorpusFrontendResolver implements ModuleResolver {

    private final String fixtureCorpusPath;
    private final Path conformanceRoot;
    private final Map<String, CorpusDiscovery.Fixture> corpusByPath;
    private final Map<String, Map<String, Type>> stdlibExports;
    private final SemanticProfile profile;

    /**
     * Creates the resolver over one discovered corpus.
     *
     * @param fixtureCorpusPath the importing fixture's corpus-relative
     *                          slash path (relative import base)
     * @param conformanceRoot   the absolute normalized conformance root
     * @param corpusByPath      the discovered corpus keyed by path
     * @param profile           the per-case A5 parse/check profile
     */
    CorpusFrontendResolver(String fixtureCorpusPath, Path conformanceRoot,
            Map<String, CorpusDiscovery.Fixture> corpusByPath,
            SemanticProfile profile) {
        this.fixtureCorpusPath = Objects.requireNonNull(fixtureCorpusPath,
            "fixtureCorpusPath must not be null");
        this.conformanceRoot = Objects.requireNonNull(conformanceRoot,
            "conformanceRoot must not be null").toAbsolutePath().normalize();
        this.corpusByPath = Objects.requireNonNull(corpusByPath,
            "corpusByPath must not be null");
        this.profile = Objects.requireNonNull(profile, "profile must not be null");
        this.stdlibExports = StdlibModuleResolver.stdlibExports(
            Path.of("std").toAbsolutePath().normalize().toString());
    }

    @Override
    public Map<String, Type> resolveModule(String modulePath,
            String importingModule, Set<String> modulesInProgress)
            throws ModuleNotFoundException,
                CffiImportWithoutNativeLibraryException {
        if (stdlibExports.containsKey(modulePath)) {
            return stdlibExports.get(modulePath);
        }
        // Reject non-spec stdlib paths (std/io, std/coroutine).
        if (modulePath.startsWith("std/") && !modulePath.startsWith("./")
                && !modulePath.startsWith("../")) {
            throw new ModuleNotFoundException(
                "Module not found: '" + modulePath
                    + "' is not a spec-listed stdlib module");
        }
        String resolved = CorpusDiscovery.resolveRelativeImport(
            fixtureCorpusPath, modulePath, conformanceRoot);
        CorpusDiscovery.Fixture dependency = resolved == null
            ? null : corpusByPath.get(resolved);
        if (dependency != null) {
            ProgramNode dependencyProgram = parse(dependency).program();
            // The v1.2 C FFI manifest policy (docs/spec-v1.2.md:1891):
            // a C FFI declaration file — a .d.deal file whose effective
            // @extern-c holds — may only be imported through a deal.json
            // externals entry specifying nativeLibrary. The gate's
            // frontend corpus has no manifest, so such an import is an
            // invalid project configuration — the same
            // CffiImportWithoutNativeLibraryException the legacy
            // runners' compile-stage resolvers throw (ConformanceTest /
            // JvmConformanceTest), which the checker maps to E2010 at
            // the import span. This is the gate's real-frontend
            // observation of the promoted ffi-manifest rejection.
            if (resolved.endsWith(".d.deal")
                    && dependencyProgram.fileDirectives().externC()) {
                throw new CffiImportWithoutNativeLibraryException(modulePath);
            }
            ExportExtractor extractor = new ExportExtractor(resolved,
                resolved.endsWith(".d.deal"));
            return extractor.extract(dependencyProgram);
        }
        throw new ModuleNotFoundException("Module not found: " + modulePath);
    }

    @Override
    public Symbol.ClassSymbol resolveClassSymbol(String className,
            String modulePath, String importingModule)
            throws ModuleNotFoundException {
        if (modulePath == null || modulePath.isEmpty()) {
            return null;
        }
        CorpusDiscovery.Fixture dependency = dependencyOf(modulePath);
        if (dependency == null) {
            return null;
        }
        return classSymbolsOf(dependency).get(className);
    }

    @Override
    public Symbol.ClassSymbol resolveClassSymbol(String className,
            CanonicalModuleIdentity declaringModule, String importingModule)
            throws ModuleNotFoundException {
        // v1.2 identity carriage: route the carried companion identity
        // (the standalone single-module convention this resolver
        // synthesizes) back to the synthesized symbols.
        for (CorpusDiscovery.Fixture dependency : corpusByPath.values()) {
            if (declaringModule.equals(
                    IdentityTestFixtures.moduleIdentityOf(stem(dependency)))) {
                Symbol.ClassSymbol symbol =
                    classSymbolsOf(dependency).get(className);
                if (symbol != null) {
                    return symbol;
                }
            }
        }
        return null;
    }

    @Override
    public boolean isFunctionExportedFromModule(
            CanonicalModuleIdentity declaringModule, String functionName,
            String importingModule) throws ModuleNotFoundException {
        for (CorpusDiscovery.Fixture dependency : corpusByPath.values()) {
            if (declaringModule.equals(
                    IdentityTestFixtures.moduleIdentityOf(stem(dependency)))) {
                ParseResult parsed = parse(dependency);
                for (StatementNode statement : parsed.program().statements()) {
                    if (statement instanceof ExportDeclaration export
                            && export.declaration()
                                instanceof FunctionDeclaration function
                            && function.name().equals(functionName)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    @Override
    public Type resolveTypeNodeInModule(TypeNode typeNode,
            String modulePath, String importingModule)
            throws ModuleNotFoundException {
        if (modulePath == null || modulePath.isEmpty()) {
            return null;
        }
        CorpusDiscovery.Fixture dependency = dependencyOf(modulePath);
        if (dependency == null) {
            return null;
        }
        try {
            NameResolver nr = new NameResolver(dependency.corpusPath(), this);
            nr.resolve(parse(dependency).program());
            return nr.resolveTypeNode(typeNode);
        } catch (Exception e) {
            return null;
        }
    }

    // =========================================================================
    // Corpus helpers
    // =========================================================================

    /** Parses a discovered corpus module under the case profile. */
    private ParseResult parse(CorpusDiscovery.Fixture fixture) {
        LexResult lex = new Lexer(fixture.source(), fixture.corpusPath())
            .tokenize();
        Parser parser = new Parser(lex.tokens(), fixture.corpusPath(), profile,
            lex.directiveEvents());
        return parser.parse();
    }

    /**
     * The companion a carried dotted module path names: the same
     * stem-only convention the absorbed frontend gate uses (the
     * standalone single-module identity view).
     */
    private CorpusDiscovery.Fixture dependencyOf(String modulePath) {
        String resolved = CorpusDiscovery.resolveRelativeImport(
            fixtureCorpusPath, modulePath, conformanceRoot);
        return resolved == null ? null : corpusByPath.get(resolved);
    }

    /** The module-path stem of a discovered fixture (no extension). */
    private static String stem(CorpusDiscovery.Fixture fixture) {
        String name = fixture.corpusPath().substring(
            fixture.corpusPath().lastIndexOf('/') + 1);
        if (name.endsWith(".d.deal")) {
            return name.substring(0, name.length() - ".d.deal".length());
        }
        return name.substring(0, name.length() - ".deal".length());
    }

    /** Synthesizes the class symbols of one parsed companion module
     * (the dotted module path view the checker consumes). */
    private Map<String, Symbol.ClassSymbol> classSymbolsOf(
            CorpusDiscovery.Fixture dependency) {
        Map<String, Symbol.ClassSymbol> symbols = new LinkedHashMap<>();
        try {
            ProgramNode program = parse(dependency).program();
            if (program == null) {
                return symbols;
            }
            String dotted = stem(dependency);
            for (StatementNode statement : program.statements()) {
                ClassDeclaration declaration = null;
                if (statement instanceof ClassDeclaration cd) {
                    declaration = cd;
                } else if (statement instanceof ExportDeclaration export
                        && export.declaration() instanceof ClassDeclaration cd) {
                    declaration = cd;
                }
                if (declaration != null) {
                    symbols.put(declaration.name(), new Symbol.ClassSymbol(
                        declaration.name(), declaration.fields(), dotted,
                        IdentityTestFixtures.identityOf(dotted,
                            declaration.name())));
                }
            }
        } catch (RuntimeException ignored) {
            // Best-effort synthesis; the fixture's own diagnostics
            // surface the defect.
        }
        return symbols;
    }
}
