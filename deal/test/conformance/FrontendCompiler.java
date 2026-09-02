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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The gate's backend-neutral frontend compilation (ISSUE-0353): the real
 * lexer → parser → module shape gate → name resolution → type checker
 * pipeline, mirroring the {@code BackendConformanceTest} frontend-gate
 * path. Returns every error diagnostic in pipeline order; a bypassed
 * stage yields no diagnostics, so a compile-error pin can never be
 * satisfied by a hollow pipeline.
 *
 * <p>The module resolver rejects every module import (the
 * {@code BackendConformanceTest} stub-resolver surface): the fixtures
 * whose diagnostic pins the gate compares are standalone compile-error
 * units, and an import would add an {@code E2003} diagnostic — a second
 * error diagnostic the Compile Diagnostic comparison reports instead of
 * hiding.</p>
 */
public final class FrontendCompiler {

    private FrontendCompiler() {
        // Static utility; no instances.
    }

    /**
     * Runs the real frontend pipeline over the header-stripped source and
     * returns every error diagnostic (severity {@code "error"} only) in
     * pipeline order. No backend executes.
     *
     * @param source   the header-stripped DEAL source (never null)
     * @param filename the module's corpus-relative path (never null)
     */
    @SuppressWarnings("deprecation")
    public static List<CompilerDiagnostic> errorDiagnostics(String source,
            String filename) {
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(filename, "filename must not be null");
        List<CompilerDiagnostic> errors = new ArrayList<>();

        LexResult lex = new Lexer(source, filename).tokenize();
        collectErrors(lex.diagnostics(), errors);
        if (lex.hasErrors()) {
            return errors;
        }

        Parser parser = new Parser(lex.tokens(), filename, lex.directiveEvents());
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

        ModuleResolver resolver = new ModuleResolver() {
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

    private static void collectErrors(List<CompilerDiagnostic> diagnostics,
            List<CompilerDiagnostic> errors) {
        for (CompilerDiagnostic diagnostic : diagnostics) {
            if ("error".equals(diagnostic.severity())) {
                errors.add(diagnostic);
            }
        }
    }
}
