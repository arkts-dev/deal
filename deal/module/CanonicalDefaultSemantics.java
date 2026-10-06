package deal.module;

import deal.semantic.ir.CanonicalJson;

import java.util.Objects;

public record CanonicalDefaultSemantics(
    String serializerVersion,
    CanonicalNode root
) {

    public CanonicalDefaultSemantics {
        Objects.requireNonNull(serializerVersion, "serializerVersion");
        if (serializerVersion.isEmpty()) {
            throw new IllegalArgumentException(
                "serializerVersion must not be empty");
        }
        Objects.requireNonNull(root, "root");
    }

    /** The canonical JSON value of this default semantics. */
    public CanonicalJson.Value canonicalJson() {
        return CanonicalJson.obj(
            CanonicalJson.e("serializerVersion",
                CanonicalJson.str(serializerVersion)),
            CanonicalJson.e("root", root.canonicalJson()));
    }

    /**
     * The canonical semantic content text — the deterministic single
     * canonical JSON serialization.
     *
     */
    public String canonicalText() {
        return CanonicalJson.serializeText(canonicalJson());
    }
}
