package deal.module;

import deal.diagnostics.DiagnosticRange;

import java.util.Objects;

public record DefaultResourceOccurrence(
    RuntimeResourceReference.Kind kind,
    SemanticModuleIdentity fromSemanticModuleIdentity,
    SemanticModuleIdentity toSemanticModuleIdentity,
    String importAlias,
    SemanticResourceIdentity semanticResourceIdentity,
    DiagnosticRange sourceRange
) {

    public DefaultResourceOccurrence {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(fromSemanticModuleIdentity,
            "fromSemanticModuleIdentity");
        Objects.requireNonNull(toSemanticModuleIdentity,
            "toSemanticModuleIdentity");
        Objects.requireNonNull(importAlias, "importAlias");
        Objects.requireNonNull(semanticResourceIdentity,
            "semanticResourceIdentity");
        Objects.requireNonNull(sourceRange, "sourceRange");
    }
}
