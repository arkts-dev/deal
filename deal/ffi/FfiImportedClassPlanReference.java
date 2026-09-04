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
 * @param importAlias             the import alias exactly as written at
 *                                the consuming site
 * @param className               the referenced exported class name
 * @param importedModulePath      the provider module's dotted module
 *                                path
 * @param canonicalDescriptor     the provider class's canonical class
 *                                atom
 * @param providerContractDigest  SHA-256 over the canonical provider
 *                                plan-identity content (a changed
 *                                provider class identity changes the
 *                                digest)
 * @param graphOrder              the provider module's dependency-order
 *                                position
 * @param sourceRange             the consuming site's complete scalar
 *                                range
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
