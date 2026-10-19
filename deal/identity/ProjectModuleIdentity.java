package deal.identity;

import java.util.List;
import java.util.Objects;

/**
 * The public project-module identity half of a canonical module identity
 * (design source {@code deal-v1.2-int32-and-bytes-architecture} D6,
 * {@code strict-project-context-resolution-identity} D6).
 *
 * <p>This is a pure carrier record: it stores the pinned inputs for one
 * configured-root source module and performs no resolution,
 * classification, containment, or descriptor-text derivation. The
 * producer is the module-identity layer (E2's {@code ModuleIdentityResolver}
 * in {@code deal.module}); {@code deal.identity} only pins the shape.</p>
 *
 * <ul>
 *   <li>{@code configuredRootText} — the selected configured root's
 *       decoded manifest spelling (the root's {@code configuredText});
 *       not percent-encoded, not dotted, not reconstructed from a
 *       filesystem path.</li>
 *   <li>{@code normalizedRootPath} — the root's protected-resolved
 *       absolute normalized path (the root's
 *       {@code absoluteNormalizedPath}; symlink-resolved on the longest
 *       existing directory prefix).</li>
 *   <li>{@code relativeModuleComponents} — the path components from the
 *       root prefix to the defining file, its own file name included,
 *       derived from the canonical symlink-resolved source URI relative
 *       to {@code normalizedRootPath}; the defining file is the final
 *       component, so the identity names one declaration and two
 *       same-named sibling files never share it.</li>
 * </ul>
 *
 * <p>Deterministic and immutable: the component list is defensively
 * copied, and record equality/hashCode are structural over all three
 * components.</p>
 *
 */
public record ProjectModuleIdentity(
    String configuredRootText,
    String normalizedRootPath,
    List<String> relativeModuleComponents) {

    public ProjectModuleIdentity {
        Objects.requireNonNull(configuredRootText, "configuredRootText");
        Objects.requireNonNull(normalizedRootPath, "normalizedRootPath");
        Objects.requireNonNull(relativeModuleComponents,
            "relativeModuleComponents");
        relativeModuleComponents = List.copyOf(relativeModuleComponents);
    }
}
