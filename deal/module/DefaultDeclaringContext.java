package deal.module;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record DefaultDeclaringContext(
    SemanticModuleIdentity declaringModuleIdentity,
    Map<String, SemanticModuleIdentity> importAliases
) implements DeclaringLexicalContext {

    public DefaultDeclaringContext {
        Objects.requireNonNull(declaringModuleIdentity,
            "declaringModuleIdentity");
        importAliases = Map.copyOf(Objects.requireNonNull(importAliases,
            "importAliases"));
    }

    /**
     * The declaring context of one module's resolved import surface: its
     * private semantic identity plus alias spelling &rarr; imported
     * module's private semantic identity, derived from the imports'
     * resolved locations in insertion order.
     */
    static DefaultDeclaringContext of(Map<String, DefaultPlanImport> imports,
            SourceModuleLocation location) {
        Map<String, SemanticModuleIdentity> aliases = new LinkedHashMap<>();
        for (Map.Entry<String, DefaultPlanImport> entry : imports.entrySet()) {
            aliases.put(entry.getKey(),
                entry.getValue().location().semanticModuleIdentity());
        }
        return new DefaultDeclaringContext(
            location.semanticModuleIdentity(), aliases);
    }
}
