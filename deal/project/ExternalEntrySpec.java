package deal.project;

import deal.source.SourceScalarRange;


public record ExternalEntrySpec(
    String rawImportSpecifier,
    ManifestString declaration,
    SourceScalarRange sourceRange
) {
}
