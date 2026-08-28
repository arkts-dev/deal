package deal.module;

import java.util.List;
import java.util.Objects;

/**
 * The parent-pinned project module identity shape (design source
 * {@code strict-project-context-resolution-identity} D6,
 * {@code deal-v1.2-int32-and-bytes-architecture} D6):
 * {@code ProjectModuleIdentity(configuredRootText, normalizedRootPath,
 * relativeModuleComponents)}.
 *
 * <ul>
 *   <li>{@code configuredRootText} — the root's {@code configuredText}:
 *       the decoded manifest spelling, never percent-encoded, dotted,
 *       reconstructed from the filesystem path, or replaced by a marker
 *       name. It is the exact text that appears in the class
 *       descriptor-text projection.</li>
 *   <li>{@code normalizedRootPath} — the root's
 *       {@code absoluteNormalizedPath} (ProjectLocator step 4: absolute +
 *       lexical normalization + symlink resolution of the longest
 *       existing directory prefix; existence never required). This is the
 *       containment prefix used on the symlink-resolved side.</li>
 *   <li>{@code relativeModuleComponents} — the path components from the
 *       root prefix to the defining file's directory, derived from the
 *       canonical symlink-resolved source URI relative to
 *       {@code normalizedRootPath}. The source file name (suffix and
 *       stem) is omitted; a file directly inside the root yields the
 *       empty list.</li>
 * </ul>
 *
 * <p>The record is immutable: the components list is defensively copied.
 * These values are compiler-internal identity inputs and never become
 * runtime descriptor text, diagnostic type names, public export keys, or
 * source-language values.</p>
 *
 * @param configuredRootText       the decoded manifest spelling of the
 *                                 selected root
 * @param normalizedRootPath       the protected-resolved root prefix
 * @param relativeModuleComponents the path components from the root to
 *                                 the defining file's directory
 */
public record ProjectModuleIdentity(String configuredRootText, String normalizedRootPath,
                                    List<String> relativeModuleComponents) {

    public ProjectModuleIdentity {
        Objects.requireNonNull(configuredRootText, "configuredRootText");
        Objects.requireNonNull(normalizedRootPath, "normalizedRootPath");
        Objects.requireNonNull(relativeModuleComponents, "relativeModuleComponents");
        relativeModuleComponents = List.copyOf(relativeModuleComponents);
    }
}
