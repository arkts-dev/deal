package deal.semantic;

import deal.ast.ProgramNode;
import deal.checker.CheckResult;
import deal.checker.SymbolTable;
import deal.semantic.ir.ModuleId;
import deal.types.Type;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One module's read-only fact bundle handed from the orchestrator to
 * {@link CheckedProjectBuilder} in dependency order
 * ({@code buildCheckOrder}, foundation F2/F8): the parsed AST, the
 * Phase-3-corrected resolved export map in its insertion order, the
 * checked facts (module symbol table + {@link CheckResult}, present only
 * for implementation modules), the orchestrator's spec-stdlib
 * classification fact, and the orchestrator's resolved imports in source
 * order ({@code resolveImportPath} plus the externals maps).
 *
 * <p>The builder consumes these facts strictly read-only — it never
 * mutates the AST, {@code CheckResult}, or symbol tables (no re-lex, no
 * re-parse). Declaration files carry {@code symbolTable == null} and
 * {@code checkResult == null} by construction: they are never
 * type-checked and never populate the checked project input.</p>
 *
 * @param sourcePath         the resolved source file path; non-null
 * @param moduleId           the dotted module path; non-null
 * @param isDeclarationFile  whether the module is a declaration file
 * @param isSpecStdlibModule the orchestrator's spec-stdlib classification
 *                           fact ({@code isSpecStdlibModuleInfo})
 * @param ast                the parsed program AST; non-null
 * @param exports            the Phase-3-corrected resolved export map in
 *                           insertion order (implementation modules) or
 *                           the signature-extractor map (declaration
 *                           files, used only for the {@code @jsonable}
 *                           synthetic-exports rule); non-null
 * @param symbolTable        the module symbol table; null for declaration files
 * @param checkResult        the checked facts; null for declaration files
 * @param imports            the orchestrator's resolved imports in source
 *                           order; non-null
 */
public record ModuleFact(
    String sourcePath,
    ModuleId moduleId,
    boolean isDeclarationFile,
    boolean isSpecStdlibModule,
    ProgramNode ast,
    Map<String, Type> exports,
    SymbolTable symbolTable,
    CheckResult checkResult,
    List<ImportFact> imports
) {

    /**
     * One orchestrator-resolved import fact in source order: the import
     * alias, the raw specifier as written, and the resolved source path of
     * the target module (the orchestrator's {@code resolveImportPath} +
     * externals resolution result).
     */
    public record ImportFact(String alias, String modulePath, String resolvedSourcePath) {

        public ImportFact {
            Objects.requireNonNull(alias, "alias must not be null");
            Objects.requireNonNull(modulePath, "modulePath must not be null");
            Objects.requireNonNull(resolvedSourcePath, "resolvedSourcePath must not be null");
        }
    }

    public ModuleFact {
        Objects.requireNonNull(sourcePath, "sourcePath must not be null");
        Objects.requireNonNull(moduleId, "moduleId must not be null");
        Objects.requireNonNull(ast, "ast must not be null");
        // Insertion-order-preserving defensive copy: the export map's
        // insertion order (declaration order, then the @jsonable
        // synthetics) is part of the pinned fact source.
        exports = Collections.unmodifiableMap(new LinkedHashMap<>(exports));
        imports = List.copyOf(imports);
    }
}
