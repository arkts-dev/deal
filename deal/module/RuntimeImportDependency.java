package deal.module;

import deal.diagnostics.DiagnosticRange;

import java.util.Objects;

public record RuntimeImportDependency(
    SemanticModuleIdentity fromSemanticModuleIdentity,
    SemanticModuleIdentity toSemanticModuleIdentity,
    String importAlias,
    SemanticResourceIdentity semanticResourceIdentity,
    String providerContractDigest,
    DiagnosticRange sourceRange,
    Reason reason
) {

    public RuntimeImportDependency {
        Objects.requireNonNull(fromSemanticModuleIdentity,
            "fromSemanticModuleIdentity");
        Objects.requireNonNull(toSemanticModuleIdentity,
            "toSemanticModuleIdentity");
        Objects.requireNonNull(importAlias, "importAlias");
        Objects.requireNonNull(semanticResourceIdentity,
            "semanticResourceIdentity");
        Objects.requireNonNull(providerContractDigest,
            "providerContractDigest");
        Objects.requireNonNull(sourceRange, "sourceRange");
        Objects.requireNonNull(reason, "reason");
    }

    /**
     * The closed two-value edge reason set
     * ({@code provider-versioned-default-plans} D7): ordinary runtime
     * use and deferred default binding over the one dependency-edge
     * carrier. No other reason value exists.
     */
    public enum Reason {
        /** An ordinary runtime-use edge of executable importing code. */
        RUNTIME_USE,
        /** A planner edge from a deferred default evaluator binding. */
        DEFERRED_DEFAULT_BINDING
    }
}
