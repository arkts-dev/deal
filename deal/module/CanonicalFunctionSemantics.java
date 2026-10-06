package deal.module;

import deal.semantic.ir.CanonicalJson;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

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
     */
    public String canonicalText() {
        return CanonicalJson.serializeText(canonicalJson());
    }
}
