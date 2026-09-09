package deal.module;

import deal.ast.ProgramNode;
import deal.checker.SymbolTable;
import deal.types.Type;

import java.util.Map;
import java.util.Objects;

/**
 * The read-only provider-module snapshot the default planning epics
 * (ISSUE-0541) resolve imported references against: one entry per
 * import alias of the consuming module.
 *
 * <p>The snapshot carries exactly the landed facts planning needs —
 * the provider's resolved source location (private semantic identity),
 * its parsed program (declaration lookup for resource identities), its
 * extracted export map (member resolution), its phase-3 symbol table
 * when available (null for declaration modules), and the two
 * classification flags. It carries no plans and no digests: provider
 * plans and digests belong to the serializer epic.</p>
 *
 * @param sourcePath        the provider's absolute normalized source
 *                          path
 * @param modulePath        the provider's dotted module path
 * @param program           the provider's parsed program
 * @param location          the provider's resolved source location
 * @param exports           the provider's extracted export map
 *                          (name &rarr; resolved type)
 * @param symbolTable       the provider's phase-3 symbol table, or
 *                          null for declaration modules
 * @param isDeclarationFile true when the provider is a {@code .d.deal}
 *                          declaration module
 * @param isExternC         true when the provider is an extern-C
 *                          declaration module
 */
public record DefaultPlanImport(
    String sourcePath,
    String modulePath,
    ProgramNode program,
    SourceModuleLocation location,
    Map<String, Type> exports,
    SymbolTable symbolTable,
    boolean isDeclarationFile,
    boolean isExternC
) {

    public DefaultPlanImport {
        Objects.requireNonNull(sourcePath, "sourcePath");
        Objects.requireNonNull(modulePath, "modulePath");
        Objects.requireNonNull(program, "program");
        Objects.requireNonNull(location, "location");
        exports = Map.copyOf(Objects.requireNonNull(exports, "exports"));
    }

    /**
     * True when the provider module is plan-bearing for class-default
     * references: implementation modules and extern-C declaration
     * modules publish class default plans; host-declared classes
     * produce no plan ({@code provider-versioned-default-plans} D2 —
     * the host supplies {@code <C>_defaults} at load).
     */
    public boolean publishesClassPlans() {
        return !isDeclarationFile || isExternC;
    }
}
