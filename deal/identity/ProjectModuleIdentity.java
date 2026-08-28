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
 *       root prefix to the defining file's directory, derived from the
 *       canonical symlink-resolved source URI relative to
 *       {@code normalizedRootPath}; an empty list means the defining
 *       file lies directly in the root directory.</li>
 * </ul>
 *
 * <p>Deterministic and immutable: the component list is defensively
 * copied, and record equality/hashCode are structural over all three
 * components.</p>
 *
 * @param configuredRootText        the configured root's decoded manifest
 *                                  spelling
 * @param normalizedRootPath        the configured root's
 *                                  protected-resolved absolute normalized
 *                                  path
 * @param relativeModuleComponents  the path components from the root
 *                                  prefix to the defining file's
 *                                  directory (possibly empty)
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
