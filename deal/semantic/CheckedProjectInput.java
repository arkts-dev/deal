package deal.semantic;

import deal.semantic.ir.ModuleId;

import java.util.List;
import java.util.Objects;

/**
 * The immutable checked project of one compile (parent D1/D2; foundation
 * F2):
 *
 * <pre>{@code
 * CheckedProjectInput {
 *   invocation, entryModule, modules: [CheckedModuleInput], releaseStateHash
 * }
 * }</pre>
 *
 * <p>Produced once per compile by {@link CheckedProjectBuilder} after
 * project-wide checking succeeds, in dependency order. The
 * {@code releaseStateHash} is the invocation's derived
 * {@code releaseStateHash} recorded <em>verbatim</em> (parent D2:
 * "CheckedProjectInput records profile, purpose, and release-state
 * hash") — a copy of the derived invocation field, never a recomputation;
 * the compact constructor rejects any value that differs from the
 * invocation's recorded field. The module list covers the implementation
 * modules only, in dependency (check) order.</p>
 *
 * @param invocation      the release-owned compiler invocation; non-null
 * @param entryModule     the entry module's dotted module path; non-null
 * @param modules         the implementation modules in dependency order; non-null
 * @param releaseStateHash the invocation's derived release-state hash
 *                        recorded verbatim (F1/F2); non-null
 */
public record CheckedProjectInput(
    CompilerInvocation invocation,
    ModuleId entryModule,
    List<CheckedModuleInput> modules,
    String releaseStateHash
) {

    public CheckedProjectInput {
        Objects.requireNonNull(invocation, "invocation must not be null");
        Objects.requireNonNull(entryModule, "entryModule must not be null");
        modules = List.copyOf(modules);
        Objects.requireNonNull(releaseStateHash, "releaseStateHash must not be null");
        if (!invocation.releaseStateHash().equals(releaseStateHash)) {
            throw new IllegalArgumentException(
                "releaseStateHash must equal the invocation's derived release-state hash "
                    + "verbatim (parent D2): recorded by the builder as a copy of the "
                    + "invocation field, never a recomputation");
        }
    }
}
