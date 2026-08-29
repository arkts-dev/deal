package deal.semantic.ir;

import java.util.Objects;

/**
 * One field entry of a {@link ClassInterface} (parent canonical surfaces;
 * foundation F2): the pinned {@code FieldInterface} shape
 * {@code {name, declaredType, optional, nullable, hasDefault}} in
 * declaration order, carrying declared types only — {@code declaredType}
 * is the canonical type text rendered through the pinned
 * {@code TypeNode → CanonicalTypeText} grammar for every entry kind
 * (class fields are closed AST records
 * {@code {name, optional, nullable, type, defaultExpr}}, so
 * {@code optional}/{@code nullable} come from the record and
 * {@code hasDefault} from {@code defaultExpr} presence). Pure immutable
 * data of {@code deal.semantic-interface/1}.
 *
 * @param name         the field name; non-null
 * @param declaredType the canonical declared-type text; non-null
 * @param optional     whether the field is optional (may be omitted)
 * @param nullable     whether the declared field type admits null
 * @param hasDefault   whether the field carries a default expression
 */
public record FieldInterface(String name, String declaredType, boolean optional,
                             boolean nullable, boolean hasDefault) {

    public FieldInterface {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(declaredType, "declaredType must not be null");
    }

    /**
     * The canonical JSON object of this field entry (sorted keys) — the
     * single mapping the interface index and the {@code TargetModuleAbi}
     * class-layout serialization both use.
     *
     * @return the canonical JSON object
     */
    public CanonicalJson.Value toCanonicalJson() {
        return CanonicalJson.obj(
            CanonicalJson.e("name", CanonicalJson.str(name)),
            CanonicalJson.e("declaredType", CanonicalJson.str(declaredType)),
            CanonicalJson.e("optional", CanonicalJson.bool(optional)),
            CanonicalJson.e("nullable", CanonicalJson.bool(nullable)),
            CanonicalJson.e("hasDefault", CanonicalJson.bool(hasDefault)));
    }
}
