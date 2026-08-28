package deal.semantic.ir;

import java.util.Objects;

/**
 * One field entry of a {@link ClassInterface} (parent canonical surfaces;
 * foundation F2): declaration order, presence, and default ownership,
 * carrying declared types only — {@code canonicalTypeText} is the
 * canonical type text (the {@code TypeNode → CanonicalTypeText} grammar
 * for declaration entries; the module's Phase-3-corrected resolved export
 * map for implementation entries). Pure immutable data of
 * {@code deal.semantic-interface/1}.
 *
 * @param name              the field name; non-null
 * @param canonicalTypeText the canonical declared-type text; non-null
 * @param required          whether the field is required-present
 * @param defaultOwner      the default ownership of the field; non-null
 */
public record FieldInterface(String name, String canonicalTypeText, boolean required,
                             DefaultOwner defaultOwner) {

    public FieldInterface {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(canonicalTypeText, "canonicalTypeText must not be null");
        Objects.requireNonNull(defaultOwner, "defaultOwner must not be null");
    }
}
