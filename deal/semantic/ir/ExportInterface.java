package deal.semantic.ir;

import java.util.Objects;

/**
 * One export entry of an {@link ExternalModuleInterface} (parent canonical
 * surfaces; foundation F2). The pinned {@code ExportInterface} shape
 * carries declared types only: {@code declaredType} is the canonical type
 * text — implementation entries render the module's Phase-3-corrected
 * resolved export map, and STDLIB/HOST declaration entries render the
 * declaration AST through the pinned {@code TypeNode → CanonicalTypeText}
 * grammar producing the same forms. Pure immutable data of
 * {@code deal.semantic-interface/1}.
 *
 * @param name         the export name; non-null
 * @param declaredType the canonical declared-type text; non-null
 */
public record ExportInterface(String name, String declaredType) {

    public ExportInterface {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(declaredType, "declaredType must not be null");
    }

    /** The canonical JSON object of this export entry (sorted keys). */
    CanonicalJson.Value toCanonicalJson() {
        return CanonicalJson.obj(
            CanonicalJson.e("name", CanonicalJson.str(name)),
            CanonicalJson.e("declaredType", CanonicalJson.str(declaredType)));
    }
}
