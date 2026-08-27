package deal.project;

import deal.source.SourceScalarRange;

/**
 * The parser-side externals entry (design source
 * {@code strict-project-context-resolution-identity} D2).
 *
 * <p>The strict parser publishes the strictly decoded {@code declaration}
 * text plus its value range and the classified {@code nativeLibrary};
 * the parser performs no filesystem access, so the declaration file
 * existence/readability check, the protected conversion into
 * {@link NormalizedDeclarationPath}, and the assembly of the completed
 * {@link ExternalEntry} are ProjectLocator step 4 duties.
 *
 * @param rawImportSpecifier the externals map key exactly as written
 * @param declaration        the strictly decoded declaration text plus
 *                           its value range
 * @param nativeLibrary      the classified {@code nativeLibrary}, or null
 *                           when the entry omits it
 * @param sourceRange        the entry's value range (the entry object's
 *                           range), used for entry-level error anchoring
 */
public record ExternalEntrySpec(
    String rawImportSpecifier,
    ManifestString declaration,
    NativeLibraryRef nativeLibrary,
    SourceScalarRange sourceRange
) {
}
