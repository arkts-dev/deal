package deal.semantic.ir;

import java.util.Objects;

/** A declaration-order field: canonical declared type, presence and nullability. */
public record FieldInterface(String name, String declaredType, boolean optional,
                             boolean nullable) {
    public FieldInterface {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(declaredType, "declaredType must not be null");
    }

    public CanonicalJson.Value toCanonicalJson() {
        return CanonicalJson.obj(
            CanonicalJson.e("name", CanonicalJson.str(name)),
            CanonicalJson.e("declaredType", CanonicalJson.str(declaredType)),
            CanonicalJson.e("optional", CanonicalJson.bool(optional)),
            CanonicalJson.e("nullable", CanonicalJson.bool(nullable)));
    }
}
