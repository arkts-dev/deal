package deal.project;

import deal.source.SourceScalarRange;

/**
 * The pinned Linux {@code nativeLibrary} classification of an externals
 * entry (design source
 * {@code strict-project-context-resolution-identity} D2 /
 * {@code deal-v1.2-directives-and-c-ffi-declarations} D5):
 *
 * <ol>
 *   <li>starts with {@code /} → {@link Kind#ABSOLUTE_PATH};</li>
 *   <li>otherwise contains {@code /} →
 *       {@link Kind#MANIFEST_RELATIVE_PATH};</li>
 *   <li>contains no {@code /} → {@link Kind#BARE_NAME} (the unchanged
 *       bare loader name).</li>
 * </ol>
 *
 * <p>Backslash is an ordinary Linux filename character and never
 * influences the classification.
 *
 * @param kind        the pinned classification
 * @param loaderText  the strictly decoded loader text exactly as written
 *                    in the manifest
 * @param sourceRange the value's half-open decoded-scalar range
 */
public record NativeLibraryRef(Kind kind, String loaderText, SourceScalarRange sourceRange) {

    /** The pinned native-library classification kinds. */
    public enum Kind {
        /** No {@code /}: the unchanged bare loader name. */
        BARE_NAME,
        /** Starts with {@code /}: an absolute path. */
        ABSOLUTE_PATH,
        /** Contains {@code /} without a leading one: manifest-relative. */
        MANIFEST_RELATIVE_PATH
    }
}
