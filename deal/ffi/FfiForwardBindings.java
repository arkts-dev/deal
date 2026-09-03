package deal.ffi;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The immutable forward bindings of one extern-C module (design source
 * {@code deal-v1.2-directives-and-c-ffi-declarations} D7,
 * {@code luajit-ffi-generated-content-seam} S6):
 * {@code FfiForwardBindings(moduleKey, state, sameModuleCells,
 * importedFunctions, importedClassPlans)}.
 *
 * <p>Construction discipline (the generator enforces it): the bindings
 * are assembled with every same-module cell created {@code UNBOUND}
 * <b>before</b> any evaluator lowering, and the imported reference
 * lists are frozen in the compilation's dependency (graph) order.
 * Cells fill only after every symbol/wrapper exists; the runtime seam
 * performs the atomic fill and export publication.</p>
 *
 * @param moduleKey          the descriptor's module key
 * @param state              the bindings state, {@link
 *                           FfiBindingState#UNBOUND} at construction
 * @param cells              the same-module forward cells keyed by
 *                           export name, in source order
 * @param importedFunctions  the frozen graph-ordered imported
 *                           function-wrapper references
 * @param importedClassPlans the frozen graph-ordered imported
 *                           class-plan references
 */
public record FfiForwardBindings(
    String moduleKey,
    FfiBindingState state,
    Map<String, ForwardFunctionCell> cells,
    List<FfiImportedFunctionReference> importedFunctions,
    List<FfiImportedClassPlanReference> importedClassPlans) {

    public FfiForwardBindings {
        Objects.requireNonNull(moduleKey, "moduleKey");
        Objects.requireNonNull(state, "state");
        if (state != FfiBindingState.UNBOUND) {
            throw new IllegalArgumentException(
                "a newly constructed binding set must be UNBOUND");
        }
        cells = Map.copyOf(Objects.requireNonNull(cells, "cells"));
        for (ForwardFunctionCell cell : cells.values()) {
            if (cell.state() != FfiBindingState.UNBOUND) {
                throw new IllegalArgumentException(
                    "binding cell '" + cell.exportName()
                        + "' must be UNBOUND at construction");
            }
            if (!cells.get(cell.exportName()).equals(cell)) {
                throw new IllegalArgumentException(
                    "binding cell key '" + cell.exportName()
                        + "' must match the cell's export name");
            }
        }
        importedFunctions = List.copyOf(Objects.requireNonNull(
            importedFunctions, "importedFunctions"));
        importedClassPlans = List.copyOf(Objects.requireNonNull(
            importedClassPlans, "importedClassPlans"));
    }
}
