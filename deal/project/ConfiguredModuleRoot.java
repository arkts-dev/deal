package deal.project;

import deal.source.SourceScalarRange;

/**
 * The parent-pinned configured-root shape (design source
 * {@code strict-project-context-resolution-identity} D1/D2,
 * {@code deal-v1.2-directives-and-c-ffi-declarations} D10).
 *
 * <p>The strict parser publishes the strictly decoded
 * {@code configuredText} plus {@code sourceRange} (as a
 * {@link ManifestString} in {@link ProjectManifest#moduleRoots()});
 * ProjectLocator step 4 completes {@code absoluteNormalizedPath} through
 * the D4 root conversion: absolute + lexical normalization + symlink
 * resolution of the longest existing directory prefix. Existence is never
 * required at locate, and the parser itself performs no filesystem access
 * — this record only pins the shape.
 *
 * @param configuredText         the decoded manifest spelling (not
 *                               percent-encoded, not dotted, not
 *                               reconstructed from the filesystem path)
 * @param absoluteNormalizedPath the protected-resolved absolute normalized
 *                               path, completed only by ProjectLocator
 *                               step 4
 * @param sourceRange            the member value's half-open
 *                               decoded-scalar range
 */
public record ConfiguredModuleRoot(String configuredText, String absoluteNormalizedPath,
                                   SourceScalarRange sourceRange) {
}
