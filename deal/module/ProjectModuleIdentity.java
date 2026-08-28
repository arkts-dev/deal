package deal.module;

import java.util.List;
import java.util.Objects;

/**
 * The parent-pinned public project module identity shape (design source
 * {@code strict-project-context-resolution-identity} D6,
 * {@code deal-v1.2-int32-and-bytes-architecture} D6).
 *
 * <p>This identity is derived by {@link ModuleIdentityResolver}'s pure
 * classifier when a resolved {@code .deal} source (never a
 * {@code .d.deal} declaration file) is contained by exactly one
 * configured root with strictly maximal containment:</p>
 *
 * <ul>
 *   <li>{@code configuredRootText} — the root's {@link
 *       deal.project.ConfiguredModuleRoot#configuredText()}: the decoded
 *       manifest spelling exactly as written (not percent-encoded, not
 *       dotted, not {@code $project}, not reconstructed from the
 *       filesystem path).</li>
 *   <li>{@code normalizedRootPath} — the root's
 *       {@link deal.project.ConfiguredModuleRoot#absoluteNormalizedPath()}
 *       (ProjectLocator step 4: absolute + lexical normalization +
 *       symlink resolution of the longest existing directory prefix).</li>
 *   <li>{@code relativeModuleComponents} — the path components from the
 *       root prefix to the defining file's <b>directory</b>, derived from
 *       the canonical symlink-resolved source URI relative to
 *       {@code normalizedRootPath}. The source file name (the final
 *       component) is omitted; a file directly in the root has an empty
 *       component list.</li>
 * </ul>
 *
 * <p>Containment is computed on symlink-resolved paths on both sides
 * (D6): a source whose file is a symlink pointing outside its lexical
 * root is not contained and receives no project identity. The record is
 * immutable — the component list is defensively copied — and is a pure
 * function of the classified inputs.</p>
 *
 * @param configuredRootText     the decoded manifest spelling of the
 *                               selected configured root
 * @param normalizedRootPath     the protected-resolved absolute root path
 * @param relativeModuleComponents the path components from the root to
 *                               the defining file's directory (empty for a
 *                               file directly in the root)
 */
public record ProjectModuleIdentity(
    String configuredRootText,
    String normalizedRootPath,
    List<String> relativeModuleComponents
) {

    public ProjectModuleIdentity {
        Objects.requireNonNull(configuredRootText, "configuredRootText");
        Objects.requireNonNull(normalizedRootPath, "normalizedRootPath");
        Objects.requireNonNull(relativeModuleComponents, "relativeModuleComponents");
        relativeModuleComponents = List.copyOf(relativeModuleComponents);
    }
}
