package deal.test.conformance;

import deal.checker.CheckResult;
import deal.checker.ModuleResolver;
import deal.checker.NameResolver;
import deal.checker.Symbol;
import deal.checker.SymbolTable;
import deal.checker.TypeChecker;
import deal.diagnostics.CompilerDiagnostic;
import deal.lexer.Lexer;
import deal.lexer.LexResult;
import deal.module.ModuleShapeValidator;
import deal.parser.ParseResult;
import deal.parser.Parser;
import deal.semantic.ir.SemanticProfile;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The gate's backend-neutral frontend compilation (ISSUE-0353;
 * corpus-aware arm added by the gate-integration child ISSUE-0357): the
 * real lexer → parser → module shape gate → name resolution → type
 * checker pipeline, mirroring the {@code BackendConformanceTest}
 * frontend-gate path and the legacy runners' compile-stage surface.
 * Returns every error diagnostic in pipeline order; a bypassed stage
 * yields no diagnostics, so a compile-error pin can never be satisfied
 * by a hollow pipeline.
 *
 * <p>Two arms:</p>
 * <ul>
 *   <li>The stub-resolver arm
 *       ({@link #errorDiagnostics(String, String)}): the module resolver
 *       rejects every module import — the surface the Compile Diagnostic
 *       comparison uses (the pinned Diagnostics fixtures are standalone
 *       compile-error units, and an import would add an {@code E2003}
 *       diagnostic — a second error diagnostic the comparison reports
 *       instead of hiding).</li>
 *   <li>The corpus-aware arm
 *       ({@link #errorDiagnostics(String, String, SemanticProfile,
 *       ModuleResolver)}): the gate's frontend-corpus execution (G1 —
 *       {@code compile-ok} fixtures compile, {@code compile-error}
 *       fixtures reject with their pinned code) compiles with the
 *       per-fixture A5 profile and a real corpus module resolver
 *       (stdlib exports, relative corpus imports), mirroring the
 *       legacy runners' compile-stage resolution.</li>
 * </ul>
 */
public final class FrontendCompiler {

    private FrontendCompiler() {
        // Static utility; no instances.
    }

    /**
     * Runs the real frontend pipeline over the header-stripped source
     * with the stub module resolver (every import rejected) and returns
     * every error diagnostic (severity {@code "error"} only) in pipeline
     * order. No backend executes.
     *
     * @param source   the header-stripped DEAL source (never null)
     * @param filename the module's corpus-relative path (never null)
     */
    @SuppressWarnings("deprecation")
    public static List<CompilerDiagnostic> errorDiagnostics(String source,
            String filename) {
        return errorDiagnostics(source, filename, SemanticProfile.LEGACY_SAFE_INT,
            stubResolver());
    }

    /**
     * Runs the real frontend pipeline over the header-stripped source
     * with the caller's profile and module resolver and returns every
     * error diagnostic (severity {@code "error"} only) in pipeline
     * order. No backend executes.
     *
     * @param source   the header-stripped DEAL source (never null)
     * @param filename the module's corpus-relative path (never null)
     * @param profile  the per-case A5 parse/check profile (never null)
     * @param resolver the module resolver (never null)
     */
    @SuppressWarnings("deprecation")
    public static List<CompilerDiagnostic> errorDiagnostics(String source,
            String filename, SemanticProfile profile, ModuleResolver resolver) {
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(filename, "filename must not be null");
        Objects.requireNonNull(profile, "profile must not be null");
        Objects.requireNonNull(resolver, "resolver must not be null");
        List<CompilerDiagnostic> errors = new ArrayList<>();

        LexResult lex = new Lexer(source, filename).tokenize();
        collectErrors(lex.diagnostics(), errors);
        if (lex.hasErrors()) {
            return errors;
        }

        Parser parser = new Parser(lex.tokens(), filename, profile,
            lex.directiveEvents());
        ParseResult parseResult = parser.parse();
        collectErrors(parseResult.diagnostics(), errors);
        if (parseResult.hasErrors()) {
            return errors;
        }

        // Post-parse module shape validation (E1048/E1049/E1050/E1051),
        // mirroring the orchestrator pipeline — the parser alone does not
        // reject v1.1 module shapes.
        collectErrors(ModuleShapeValidator.validate(parseResult.program(),
            filename, filename.endsWith(".d.deal")), errors);
        if (!errors.isEmpty()) {
            return errors;
        }
        if (filename.endsWith(".d.deal")) {
            // The declaration-file pipeline (the legacy runners' .d.deal
            // arm): signature extraction after the declaration shape
            // gate — the sole E7001 emission site. No name resolution or
            // type checking runs for bodyless declaration files.
            deal.module.ExportExtractor extractor =
                new deal.module.ExportExtractor(filename, true);
            extractor.extract(parseResult.program());
            collectErrors(extractor.diagnostics(), errors);
            return errors;
        }

        NameResolver nr = new NameResolver(filename, resolver);
        SymbolTable symTable;
        try {
            symTable = nr.resolve(parseResult.program());
        } catch (Exception e) {
            // E9999 is the project's test-only pseudo code for an
            // unexpected NameResolver exception (the ConformanceTest
            // precedent); the deprecated synthetic factory carries the
            // canonical synthetic range plus an anchor note naming the
            // fixture source.
            errors.add(CompilerDiagnostic.synthetic("E9999", "error",
                e.getMessage(), filename,
                "missing anchor: fixture source '" + filename + "'"));
            return errors;
        }
        collectErrors(nr.diagnostics(), errors);

        CheckResult result = TypeChecker.check(filename, symTable, nr,
            parseResult.program());
        collectErrors(result.diagnostics(), errors);
        return errors;
    }

    /** The stub resolver of the pin arm: every module import rejected. */
    private static ModuleResolver stubResolver() {
        return new ModuleResolver() {
            @Override
            public Map<String, deal.types.Type> resolveModule(String modulePath,
                    String importingModule, Set<String> modulesInProgress)
                    throws ModuleNotFoundException {
                throw new ModuleNotFoundException(
                    "Module not found: " + modulePath);
            }

            @Override
            public Symbol.ClassSymbol resolveClassSymbol(String className,
                    String modulePath, String importingModule)
                    throws ModuleNotFoundException {
                return null;
            }
        };
    }

    private static void collectErrors(List<CompilerDiagnostic> diagnostics,
            List<CompilerDiagnostic> errors) {
        for (CompilerDiagnostic diagnostic : diagnostics) {
            if ("error".equals(diagnostic.severity())) {
                errors.add(diagnostic);
            }
        }
    }
}
