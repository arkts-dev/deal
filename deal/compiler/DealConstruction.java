package deal.compiler;

import deal.semantic.ir.CanonicalJson;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static deal.compiler.CompilerProtocolJson.*;

/** Source-free compiler construction calls. Handles are batch-local, never runtime instructions.
 * Projection is a candidate; the regular semantic checker remains authoritative. */
public class DealConstruction {
    public static final class Failure extends IllegalArgumentException {
        public final String ownerId;
        public Failure(String ownerId, String message) { super(message); this.ownerId = ownerId; }
    }
    public enum Kind { VALUE, STATEMENT, BLOCK, DECLARATION, UI }
    public record Built(Kind kind, String source) {}
    public static void validateHandle(String id) { identifier(id); }
    private final Map<String, Built> handles = new LinkedHashMap<>();
    private final Map<String, CanonicalJson.Obj> pending = new LinkedHashMap<>();
    private final Set<String> resolving = new java.util.HashSet<>();

    public final String build(CanonicalJson.Obj batch, Kind expected) {
        handles.clear();
        pending.clear();
        resolving.clear();
        var calls = requireArray(field(batch, "calls"), "calls").items();
        if (calls.size() > 512) throw new IllegalArgumentException("CC1001: construction batch exceeds 512 calls");
        for (var raw : calls) {
            var call = requireObject(raw, "construction call");
            String id = identifier(stringField(call, "id"));
            if (pending.putIfAbsent(id, call) != null) throw new IllegalArgumentException("CC1002: duplicate handle " + id);
        }
        for (String id : pending.keySet()) resolve(id);
        return get(stringField(batch, "result"), expected).source();
    }

    private Built resolve(String id) {
        if (handles.containsKey(id)) return handles.get(id);
        var call = pending.get(id);
        if (call == null) throw new Failure(id.matches("[A-Za-z_][A-Za-z0-9_]*") ? id : null, "unknown construction handle: " + id
                + ". Define this id in the current calls batch. A string operand is a handle, not literal text;"
                + " for literal text define a text constructor and use its id. Handles from previous batches are invalid.");
        if (!resolving.add(id)) throw new IllegalArgumentException("cyclic construction dependency: " + id);
        try {
                Built built = invoke(stringField(call, "op"), call);
                if (built.source().length() > 131_072)
                    throw new IllegalArgumentException("construction result exceeds source resource limit");
                handles.put(id, built);
                return built;
        } catch (IllegalArgumentException failure) {
                if (failure instanceof Failure typed && typed.ownerId != null) throw typed;
                throw new Failure(id, "CC1003 at " + id + ": " + failure.getMessage());
        } finally {
            resolving.remove(id);
        }
    }

    protected Built invoke(String op, CanonicalJson.Obj c) {
        return switch (op) {
            case "text" -> new Built(Kind.VALUE, encode(stringField(c, "value")));
            case "integer" -> new Built(Kind.VALUE, Integer.toString(intField(c, "value")));
            case "boolean" -> {
                var value = field(c, "value");
                if (!(value instanceof CanonicalJson.Bool)) throw new IllegalArgumentException("boolean required");
                yield new Built(Kind.VALUE, encode(value));
            }
            case "reference" -> new Built(Kind.VALUE, identifier(stringField(c, "name")));
            case "path" -> {
                var parts = requireArray(field(c, "parts"), "parts").items();
                if (parts.isEmpty() || parts.size() > 32) throw new IllegalArgumentException("path requires 1..32 identifiers");
                yield new Built(Kind.VALUE, String.join(".", parts.stream().map(part -> {
                    if (!(part instanceof CanonicalJson.Str s)) throw new IllegalArgumentException("path identifier required");
                    return identifier(s.value());
                }).toList()));
            }
            case "field" -> new Built(Kind.VALUE, value(c, "object") + "." + identifier(stringField(c, "name")));
            case "index" -> new Built(Kind.VALUE, value(c, "array") + "[" + value(c, "index") + "]");
            case "emptyArray" -> new Built(Kind.VALUE, "[]");
            case "record" -> new Built(Kind.VALUE, "{" + fields(c, false) + "}");
            case "returnRecord" -> new Built(Kind.BLOCK, "return {" + fields(c, false) + "};");
            case "binary" -> {
                String operator = stringField(c, "operator");
                if (!BINARY.contains(operator)) throw new IllegalArgumentException("unsupported binary operator");
                yield new Built(Kind.VALUE, "(" + value(c, "left") + " " + operator + " " + value(c, "right") + ")");
            }
            case "unary" -> {
                String operator = stringField(c, "operator");
                if (!Set.of("!", "-").contains(operator)) throw new IllegalArgumentException("unsupported unary operator");
                yield new Built(Kind.VALUE, "(" + operator + value(c, "value") + ")");
            }
            case "call" -> new Built(Kind.VALUE, identifier(stringField(c, "name")) + "(" + refs(c, "arguments", Kind.VALUE, ", ") + ")");
            case "local" -> new Built(Kind.STATEMENT, "let " + identifier(stringField(c, "name")) + ": "
                    + type(stringField(c, "type")) + " = " + value(c, "value") + ";");
            case "assign" -> new Built(Kind.STATEMENT, value(c, "target") + " = " + value(c, "value") + ";");
            case "return" -> new Built(Kind.STATEMENT, "return " + value(c, "value") + ";");
            case "if" -> new Built(Kind.STATEMENT, "if (" + value(c, "condition") + ") {\n"
                    + get(stringField(c, "then"), Kind.BLOCK).source() + "\n} else {\n"
                    + get(stringField(c, "else"), Kind.BLOCK).source() + "\n}");
            case "while" -> new Built(Kind.STATEMENT, "while (" + value(c, "condition") + ") {\n"
                    + get(stringField(c, "body"), Kind.BLOCK).source() + "\n}");
            case "block" -> new Built(Kind.BLOCK, refs(c, "statements", Kind.STATEMENT, "\n"));
            case "declareRecord" -> new Built(Kind.DECLARATION, "export class " + identifier(stringField(c, "name"))
                    + " {\n" + fields(c, true) + "\n}");
            case "declareFunction" -> function(c);
            default -> throw new IllegalArgumentException("unknown construction operation " + op);
        };
    }

    protected final Built function(CanonicalJson.Obj c) {
        List<String> parameters = new ArrayList<>();
        for (var raw : requireArray(field(c, "parameters"), "parameters").items()) {
            var p = requireObject(raw, "parameter");
            parameters.add(identifier(stringField(p, "name")) + ": " + type(stringField(p, "type")));
        }
        return new Built(Kind.DECLARATION, "export function " + identifier(stringField(c, "name")) + "("
                + String.join(", ", parameters) + "): " + type(stringField(c, "returns")) + " {\n"
                + get(stringField(c, "body"), Kind.BLOCK).source() + "\n}");
    }

    protected final String fields(CanonicalJson.Obj c, boolean declaration) {
        List<String> result = new ArrayList<>();
        Set<String> names = new java.util.HashSet<>();
        for (var raw : requireArray(field(c, "fields"), "fields").items()) {
            var f = requireObject(raw, "field");
            String name = identifier(stringField(f, "name"));
            if (!names.add(name)) throw new IllegalArgumentException("duplicate field " + name);
            result.add(name + (declaration ? ": " + type(stringField(f, "type")) + " = " : ": ")
                    + value(f, "value") + (declaration ? ";" : ""));
        }
        return String.join(declaration ? "\n" : ", ", result);
    }

    protected final Built get(String id, Kind kind) {
        Built value = resolve(id);
        if (value.kind() != kind) throw new Failure(id, "expected " + kind + " handle: " + id + "; actual " + value.kind());
        return value;
    }

    protected final String value(CanonicalJson.Obj c, String key) {
        var operand = field(c, key);
        if (operand instanceof CanonicalJson.Str s) return get(s.value(), Kind.VALUE).source();
        if (operand instanceof CanonicalJson.Bool) return encode(operand);
        return Integer.toString(intField(c, key));
    }
    protected final String refs(CanonicalJson.Obj c, String key, Kind kind, String separator) {
        return String.join(separator, requireArray(field(c, key), key).items().stream()
                .map(v -> { if (!(v instanceof CanonicalJson.Str s)) throw new IllegalArgumentException("handle required");
                    return get(s.value(), kind).source(); }).toList());
    }
    protected static String identifier(String name) {
        if (name.isEmpty() || !(Character.isLetter(name.charAt(0)) || name.charAt(0) == '_'))
            throw new IllegalArgumentException("identifier required");
        for (int i = 1; i < name.length(); i++) if (!(Character.isLetterOrDigit(name.charAt(i)) || name.charAt(i) == '_'))
            throw new IllegalArgumentException("identifier required");
        return name;
    }
    protected static String type(String name) {
        String base = name;
        while (base.endsWith("[]")) base = base.substring(0, base.length() - 2);
        identifier(base);
        return name;
    }

    private static final List<String> BINARY = List.of("+", "-", "*", "/", "%", "===", "!==", "<", "<=", ">", ">=", "&&", "||");
    public static Map<String, Object> textSchema() { return Map.of("type", "string"); }
    public static Map<String, Object> operandSchema() {
        return Map.of("anyOf", List.of(textSchema(), Map.of("type", "integer"), Map.of("type", "boolean")));
    }
    public static Map<String, Object> objectSchema(Map<String, Object> properties) {
        return Map.of("type", "object", "additionalProperties", false, "properties", properties, "required", properties.keySet().stream().sorted().toList());
    }
    public static Map<String, Object> arraySchema(Map<String, Object> items) { return Map.of("type", "array", "items", items); }
    public static Map<String, Object> callSchema(String op, Map<String, Object> args) {
        var fields = new LinkedHashMap<String, Object>(args);
        fields.put("id", textSchema()); fields.put("op", Map.of("type", "string", "const", op));
        return objectSchema(fields);
    }
    public static List<Map<String, Object>> operationSchemas() {
        var s = textSchema();
        var v = operandSchema();
        var field = objectSchema(Map.of("name", s, "value", v));
        var result = new ArrayList<Map<String, Object>>();
        result.add(callSchema("text", Map.of("value", s)));
        result.add(callSchema("integer", Map.of("value", Map.of("type", "integer"))));
        result.add(callSchema("boolean", Map.of("value", Map.of("type", "boolean"))));
        result.add(callSchema("reference", Map.of("name", s)));
        result.add(callSchema("path", Map.of("parts", Map.of("type", "array", "minItems", 1, "maxItems", 32, "items", s))));
        result.add(callSchema("field", Map.of("object", v, "name", s)));
        result.add(callSchema("index", Map.of("array", v, "index", v)));
        result.add(callSchema("emptyArray", Map.of()));
        result.add(callSchema("record", Map.of("fields", arraySchema(field))));
        result.add(callSchema("returnRecord", Map.of("fields", arraySchema(field))));
        result.add(callSchema("binary", Map.of("left", v, "right", v, "operator", Map.of("type", "string", "enum", BINARY))));
        result.add(callSchema("unary", Map.of("value", v, "operator", Map.of("type", "string", "enum", List.of("!", "-")))));
        result.add(callSchema("call", Map.of("name", s, "arguments", arraySchema(s))));
        result.add(callSchema("local", Map.of("name", s, "type", s, "value", v)));
        result.add(callSchema("assign", Map.of("target", v, "value", v)));
        result.add(callSchema("return", Map.of("value", v)));
        result.add(callSchema("if", Map.of("condition", v, "then", s, "else", s)));
        result.add(callSchema("while", Map.of("condition", v, "body", s)));
        result.add(callSchema("block", Map.of("statements", arraySchema(s))));
        result.add(callSchema("declareRecord", Map.of("name", s, "fields", arraySchema(objectSchema(Map.of("name", s, "type", s, "value", v))))));
        result.add(callSchema("declareFunction", functionSchema()));
        return result;
    }
    public static Map<String, Object> functionSchema() {
        var s = textSchema();
        return Map.of("name", s, "parameters", arraySchema(objectSchema(Map.of("name", s, "type", s))), "returns", s, "body", s);
    }
    public static Map<String, Object> schema(List<Map<String, Object>> operations) {
        return objectSchema(Map.of("calls", Map.of("type", "array", "maxItems", 512, "items", Map.of("anyOf", operations)), "result", textSchema()));
    }
}
