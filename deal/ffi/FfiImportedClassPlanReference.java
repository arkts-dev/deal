package deal.ffi;

import deal.diagnostics.DiagnosticRange;

import java.util.Objects;

/**
 * One immutable graph-ordered reference from an extern-C module's
 * default to an imported class default plan (design source
 * {@code deal-v1.2-directives-and-c-ffi-declarations} D7,
 * {@code luajit-ffi-generated-content-seam} S6). The ordering
 * discipline equals {@link FfiImportedFunctionReference}: collected in
 * source order, ordered by the provider module's dependency-order
 * position.
 *
 */
public record FfiImportedClassPlanReference(
    String importAlias,
    String className,
    String importedModulePath,
    String canonicalDescriptor,
    String providerContractDigest,
    int graphOrder,
    DiagnosticRange sourceRange) {

    public FfiImportedClassPlanReference {
        Objects.requireNonNull(importAlias, "importAlias");
        Objects.requireNonNull(className, "className");
        Objects.requireNonNull(importedModulePath, "importedModulePath");
        Objects.requireNonNull(canonicalDescriptor, "canonicalDescriptor");
        Objects.requireNonNull(providerContractDigest, "providerContractDigest");
        Objects.requireNonNull(sourceRange, "sourceRange");
        if (graphOrder < 0) {
            throw new IllegalArgumentException(
                "graphOrder must be non-negative, got " + graphOrder);
        }
    }
}
