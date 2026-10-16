package deal.semantic;

import deal.diagnostics.DiagnosticRange;
import deal.semantic.ir.ModuleId;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

    /**
     * This input with each module's recorded contextual class-literal
     * provider aliases replaced by the supplied per-module map: the
     * dependency graph publishes the recorded provider occurrences after
     * the checked-project build, so the one lowering consumes the
     * complete facts through this copy. A module absent from the supplied
     * map keeps no recorded provider.
     */
    public CheckedProjectInput withClassLiteralProviders(
            Map<ModuleId, Map<DiagnosticRange, String>> classLiteralProviders) {
        Objects.requireNonNull(classLiteralProviders,
            "classLiteralProviders must not be null");
        List<CheckedModuleInput> enriched = new ArrayList<>(modules.size());
        for (CheckedModuleInput module : modules) {
            enriched.add(new CheckedModuleInput(module.moduleId(), module.sourceId(),
                module.sourcePath(), module.ast(), module.checks(), module.imports(),
                module.exports(), module.kind(),
                classLiteralProviders.getOrDefault(module.moduleId(),
                    module.classLiteralProviders())));
        }
        return new CheckedProjectInput(invocation, entryModule, enriched,
            releaseStateHash);
    }
}
