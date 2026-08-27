package deal.project;

import deal.source.SourceScalarRange;

/**
 * The parent-pinned normalized declaration path shape (design source
 * {@code strict-project-context-resolution-identity} D1/D2,
 * {@code deal-v1.2-directives-and-c-ffi-declarations} D5).
 *
 * <p>The strict parser publishes the decoded declaration text plus its
 * value range (as {@link ManifestString#declaration()} inside
 * {@link ExternalEntrySpec}); ProjectLocator step 4 requires the
 * declaration file to exist as a regular readable file and completes
 * {@code absoluteNormalizedPath} by full symlink resolution (the D4
 * declaration row). This record only pins the shape — the parser never
 * touches the filesystem.
 *
 * @param absoluteNormalizedPath the fully symlink-resolved absolute path,
 *                               completed only by ProjectLocator step 4
 * @param sourceRange            the declaration member's value range
 */
public record NormalizedDeclarationPath(String absoluteNormalizedPath,
                                        SourceScalarRange sourceRange) {
}
