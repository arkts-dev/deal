package deal.ffi;

import deal.diagnostics.DiagnosticRange;

import java.util.Objects;

/**
 * One immutable graph-ordered reference from an extern-C module's
 * default to an imported function wrapper (design source
 * {@code deal-v1.2-directives-and-c-ffi-declarations} D7,
 * {@code luajit-ffi-generated-content-seam} S6: imported references are
 * graph-ordered wrappers/class plans).
 *
 * <p>Ordering discipline: references are collected in source order and
 * then ordered by {@code graphOrder} — the position of the provider
 * module in the compilation's dependency (check) order — with source
 * order as the stable tie-break, so a consumer module's wrappers/plans
 * exist before any depending evaluator runs.</p>
 *
 */
public record FfiImportedFunctionReference(
    String importAlias,
    String exportName,
    String importedModulePath,
    String canonicalDescriptor,
    String providerContractDigest,
    int graphOrder,
    DiagnosticRange sourceRange) {

    public FfiImportedFunctionReference {
        Objects.requireNonNull(importAlias, "importAlias");
        Objects.requireNonNull(exportName, "exportName");
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
