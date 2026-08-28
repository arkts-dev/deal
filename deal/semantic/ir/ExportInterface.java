package deal.semantic.ir;

import java.util.Objects;

/**
 * One export entry of an {@link ExternalModuleInterface} (parent canonical
 * surfaces; foundation F2). The pinned {@code ExportInterface} shape
 * carries declared types only: {@code canonicalTypeText} is the canonical
 * type text — implementation entries render the module's
 * Phase-3-corrected resolved export map, and STDLIB/HOST declaration
 * entries render the declaration AST through the pinned
 * {@code TypeNode → CanonicalTypeText} grammar producing the same forms.
 * Pure immutable data of {@code deal.semantic-interface/1}.
 *
 * @param name              the export name; non-null
 * @param canonicalTypeText the canonical declared-type text; non-null
 */
public record ExportInterface(String name, String canonicalTypeText) {

    public ExportInterface {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(canonicalTypeText, "canonicalTypeText must not be null");
    }
}
