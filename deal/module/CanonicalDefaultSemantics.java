package deal.module;

import deal.semantic.ir.CanonicalJson;

import java.util.Objects;

/**
 * The serializer-owned canonical serialization of one resolved default
 * expression's typed semantics (ISSUE-0542, design source
 * {@code provider-versioned-default-plans} D6):
 *
 * <pre>
 * CanonicalDefaultSemantics(serializerVersion, root: CanonicalNode)
 * </pre>
 *
 * <p>The canonical text is the single canonical JSON facility's
 * serialization ({@link deal.semantic.ir.CanonicalJson}) of
 * {@code {serializerVersion, root}}; it is the content stored in
 * {@link ResolvedDefaultExpression#canonicalSemanticContent()}.
 * Equality compares the full content byte-for-byte; the semantic
 * digest is SHA-256 over the length-prefixed UTF-8 of the content (an
 * index only).</p>
 */
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
     * @return the canonical text, never null
     */
    public String canonicalText() {
        return CanonicalJson.serializeText(canonicalJson());
    }
}
