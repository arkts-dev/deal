package deal.project;

import deal.source.SourceScalarRange;

/**
 * The completed externals entry (design source
 * {@code strict-project-context-resolution-identity} D1/D2).
 *
 * <p>ProjectLocator step 4 completes each parser-side
 * {@link ExternalEntrySpec} through the D4 declaration-row conversion
 * (existence + regular/readable verification + full symlink resolution)
 * into this shape; it appears in the published context's externals map
 * only after that conversion. The strict parser defines the shape but
 * never assembles an instance.
 *
 * @param rawImportSpecifier the externals map key exactly as written
 * @param declarationPath    the fully symlink-resolved declaration path
 *                           completed by ProjectLocator step 4
 * @param nativeLibrary      the classified {@code nativeLibrary}, or null
 *                           when the entry omits it
 * @param sourceRange        the entry's value range (the entry object's
 *                           range)
 */
public record ExternalEntry(
    String rawImportSpecifier,
    NormalizedDeclarationPath declarationPath,
    NativeLibraryRef nativeLibrary,
    SourceScalarRange sourceRange
) {
}
