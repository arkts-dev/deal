package deal.semantic.ir;

import java.util.List;
import java.util.Objects;

/**
 * The module-initialization plan of a {@link LoweredModuleUnit} (parent
 * canonical surfaces; {@code MODULE_INIT} row of the closed operation
 * table). The {@code MODULE_INIT} op enforces the
 * {@code UNINITIALIZED→INITIALIZING→INITIALIZED} transition, records
 * {@code FAILED(error)} on failure, and publishes no exports on failure;
 * the plan carries the resolved imports and the init block the op runs.
 *
 * @param imports   the resolved imported module identities in dependency
 *                  order; non-null
 * @param initBlock the module init body block identity; non-null
 */
public record ModuleInitPlan(List<ModuleId> imports, BlockId initBlock) {

    public ModuleInitPlan(List<ModuleId> imports, BlockId initBlock) {
        this.imports = List.copyOf(imports);
        this.initBlock = Objects.requireNonNull(initBlock, "initBlock must not be null");
    }
}
