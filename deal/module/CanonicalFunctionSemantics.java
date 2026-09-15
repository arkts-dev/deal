package deal.module;

import deal.semantic.ir.CanonicalJson;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The serializer-owned statement-level canonical grammar for provider
 * function bodies (ISSUE-0542, design source
 * {@code provider-versioned-default-plans} D9):
 *
 * <pre>
 * CanonicalFunctionSemantics(serializerVersion, isAsync,
 *   parameters: ordered canonical type descriptor texts,
 *   returnDescriptor, body: ordered CanonicalStatement)
 * </pre>
 *
 * <ul>
 *   <li>{@code serializerVersion} — the one versioned format constant
 *       shared with the expression grammar
 *       ({@link DefaultSemanticSerializer#SERIALIZER_VERSION}).</li>
 *   <li>{@code isAsync} — the exact sync/async marker of the
 *       function.</li>
 *   <li>{@code parameters} — the ordered E4 canonical descriptor texts
 *       of the parameter types.</li>
 *   <li>{@code returnDescriptor} — the E4 canonical descriptor text of
 *       the return type.</li>
 *   <li>{@code body} — the ordered statement children (source order;
 *       expression children in evaluation order).</li>
 * </ul>
 *
 * <p>The canonical text is the single canonical JSON facility's
 * serialization ({@link deal.semantic.ir.CanonicalJson}); equality
 * compares the full content byte-for-byte and SHA-256 is an index
 * only.</p>
 */
public record CanonicalFunctionSemantics(
    String serializerVersion,
    boolean isAsync,
    List<String> parameters,
    String returnDescriptor,
    List<CanonicalStatement> body
) {

    public CanonicalFunctionSemantics {
        Objects.requireNonNull(serializerVersion, "serializerVersion");
        if (serializerVersion.isEmpty()) {
            throw new IllegalArgumentException(
                "serializerVersion must not be empty");
        }
        parameters = List.copyOf(Objects.requireNonNull(parameters,
            "parameters"));
        Objects.requireNonNull(returnDescriptor, "returnDescriptor");
        body = List.copyOf(Objects.requireNonNull(body, "body"));
    }

    /** The canonical JSON value of this function semantics. */
    public CanonicalJson.Value canonicalJson() {
        List<CanonicalJson.Value> parameterValues = new ArrayList<>();
        for (String parameter : parameters) {
            parameterValues.add(CanonicalJson.str(parameter));
        }
        List<CanonicalJson.Value> statementValues = new ArrayList<>();
        for (CanonicalStatement statement : body) {
            statementValues.add(statement.canonicalJson());
        }
        return CanonicalJson.obj(
            CanonicalJson.e("serializerVersion",
                CanonicalJson.str(serializerVersion)),
            CanonicalJson.e("isAsync", CanonicalJson.bool(isAsync)),
            CanonicalJson.e("parameters",
                CanonicalJson.arr(parameterValues)),
            CanonicalJson.e("returnDescriptor",
                CanonicalJson.str(returnDescriptor)),
            CanonicalJson.e("body", CanonicalJson.arr(statementValues)));
    }

    /**
     * The canonical content text (the deterministic single canonical
     * JSON serialization) — the provider-digest input.
     *
     * @return the canonical text, never null
     */
    public String canonicalText() {
        return CanonicalJson.serializeText(canonicalJson());
    }
}
