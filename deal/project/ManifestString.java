package deal.project;

import deal.source.SourceScalarRange;

/**
 * A strictly decoded manifest string value plus its value range.
 *
 * <p>Every {@code ManifestString} published by
 * {@link StrictManifestParser} is produced through the per-token strict
 * string re-scan (design source
 * {@code strict-project-context-resolution-identity} D2): the value is
 * decoded with the pinned escape set <code>" \ / b f n r t uXXXX</code>
 * and true Unicode decoding (two consecutive surrogate-range escapes
 * decode as one supplementary scalar). The permissive decoded values of
 * the {@code deal.source.JsonRangeLexer} substrate are never used as
 * strict values.
 *
 * @param value       the strictly decoded string value
 * @param sourceRange the value's half-open decoded-scalar range
 *                    ({@link SourceScalarRange}); the file-less
 *                    content-only carrier — consumers that publish
 *                    diagnostics supply the manifest path themselves
 */
public record ManifestString(String value, SourceScalarRange sourceRange) {
}
