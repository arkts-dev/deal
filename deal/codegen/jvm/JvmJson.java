package deal.codegen.jvm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;

/**
 * The shared JVM JSON algorithm of the {@code JSON_FROM_CLASS}/
 * {@code JSON_TO_CLASS} arms (E7/K-D8/K-D10): the closed E8 RFC-8259
 * parse and canonical text algorithms plus the flat-layout class walk
 * over the per-class plans the shared emitter records. The parse mirrors
 * the Lua artifact's prelude algorithm (the differential harness
 * compares the three consumers event-for-event): signed32 integer
 * lexical carriers, duplicate keys keep the last value and the first
 * position, any syntax defect yields {@link #SYNTAX}; the canonical
 * number text is {@link Double#toString(double)}; the from-json walk
 * runs the per-site class-default thunks with their own events and
 * returns language null on every listed failure ({@code JSON_FROM_NULL});
 * the to-json walk returns the deterministic text or throws the first
 * declaration-order {@link Projection} for the arm's {@code JSON_TO_ERROR}
 * projection.
 *
 * <p>A nested {@code @jsonable} class field fails closed (a producer
 * defect naming the nested shape): the nested-decode seam is not part of
 * this slice's emitted plan.</p>
 */
public final class JvmJson {

    private JvmJson() {
    }

    /** The syntax-defect sentinel of the parse (never a language value). */
    public static final Object SYNTAX = new Object() {
        @Override public String toString() {
            return "json-syntax";
        }
    };

    /** The JSON null sentinel inside parsed documents. */
    public static final Object JNULL = new Object() {
        @Override public String toString() {
            return "json-null";
        }
    };

    /** The pinned bounded walk depth (K-D8/K-D10). */
    public static final int MAX_DEPTH = 512;

    /** One field of a class plan: name, descriptor, optionality, default child. */
    public static final class Field {
        public final String name;
        public final String descriptor;
        public final boolean optional;
        public final String kind;
        public final String defaultKey;
        public final String defaultDigest;
        public final Supplier<Object> defaultThunk;

        public Field(String name, String descriptor, boolean optional, String kind) {
            this(name, descriptor, optional, kind, null, null, null);
        }

        public Field(String name, String descriptor, boolean optional, String kind,
                     String defaultKey, String defaultDigest,
                     Supplier<Object> defaultThunk) {
            this.name = name;
            this.descriptor = descriptor;
            this.optional = optional;
            this.kind = kind;
            this.defaultKey = defaultKey;
            this.defaultDigest = defaultDigest;
            this.defaultThunk = defaultThunk;
        }
    }

    /** One class plan: the canonical class identity and its declaration-order fields. */
    public static final class Plan {
        public final String classIdText;
        public final Field[] fields;
        public final Supplier<JvmRuntime.ClassInstance> instanceSupplier;

        public Plan(String classIdText, Field[] fields,
                    Supplier<JvmRuntime.ClassInstance> instanceSupplier) {
            this.classIdText = classIdText;
            this.fields = fields;
            this.instanceSupplier = instanceSupplier;
        }
    }

    /** The first declaration-order to-json failure (the JSON_TO_ERROR projection). */
    public static final class Projection extends RuntimeException {
        public final String fieldPath;
        public final String actual;

        Projection(String fieldPath, String actual) {
            super("value at " + fieldPath + " is not JSON serializable: " + actual);
            this.fieldPath = fieldPath;
            this.actual = actual;
        }
    }

    // =========================================================================
    // Parse
    // =========================================================================

    /** Parses one JSON text; {@link #SYNTAX} on any syntax defect. */
    public static Object parse(String text) {
        Parser parser = new Parser(text);
        return parser.parseDocument();
    }

    private static final class Parser {
        private final String s;
        private int pos;

        Parser(String s) {
            this.s = s;
        }

        Object parseDocument() {
            Object value = parseValue(0);
            if (value == FAIL) {
                return SYNTAX;
            }
            skipWs();
            if (pos < s.length()) {
                return SYNTAX;
            }
            return value;
        }

        private static final Object FAIL = new Object();

        private void skipWs() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        private Object parseValue(int depth) {
            if (depth > MAX_DEPTH) {
                return FAIL;
            }
            skipWs();
            if (pos >= s.length()) {
                return FAIL;
            }
            char c = s.charAt(pos);
            if (c == '{') {
                pos++;
                JvmRuntime.Table obj = new JvmRuntime.Table();
                skipWs();
                if (pos < s.length() && s.charAt(pos) == '}') {
                    pos++;
                    return obj;
                }
                while (true) {
                    skipWs();
                    if (pos >= s.length() || s.charAt(pos) != '"') {
                        return FAIL;
                    }
                    String key = parseString();
                    if (key == null) {
                        return FAIL;
                    }
                    skipWs();
                    if (pos >= s.length() || s.charAt(pos) != ':') {
                        return FAIL;
                    }
                    pos++;
                    Object value = parseValue(depth + 1);
                    if (value == FAIL) {
                        return FAIL;
                    }
                    obj.write(key, value);
                    skipWs();
                    if (pos >= s.length()) {
                        return FAIL;
                    }
                    c = s.charAt(pos);
                    if (c == ',') {
                        pos++;
                    } else if (c == '}') {
                        pos++;
                        return obj;
                    } else {
                        return FAIL;
                    }
                }
            } else if (c == '[') {
                pos++;
                JvmRuntime.Array arr = new JvmRuntime.Array(0);
                skipWs();
                if (pos < s.length() && s.charAt(pos) == ']') {
                    pos++;
                    return arr;
                }
                while (true) {
                    Object value = parseValue(depth + 1);
                    if (value == FAIL) {
                        return FAIL;
                    }
                    arr.elements.add(value);
                    arr.length = arr.elements.size();
                    skipWs();
                    if (pos >= s.length()) {
                        return FAIL;
                    }
                    c = s.charAt(pos);
                    if (c == ',') {
                        pos++;
                    } else if (c == ']') {
                        pos++;
                        return arr;
                    } else {
                        return FAIL;
                    }
                }
            } else if (c == '"') {
                return parseString();
            } else if (c == 't') {
                if (s.startsWith("true", pos)) {
                    pos += 4;
                    return Boolean.TRUE;
                }
                return FAIL;
            } else if (c == 'f') {
                if (s.startsWith("false", pos)) {
                    pos += 5;
                    return Boolean.FALSE;
                }
                return FAIL;
            } else if (c == 'n') {
                if (s.startsWith("null", pos)) {
                    pos += 4;
                    return JNULL;
                }
                return FAIL;
            } else if (c == '-' || (c >= '0' && c <= '9')) {
                return parseNumber();
            }
            return FAIL;
        }

        private String parseString() {
            pos++;
            StringBuilder out = new StringBuilder();
            while (true) {
                if (pos >= s.length()) {
                    return null;
                }
                char c = s.charAt(pos);
                if (c == '"') {
                    pos++;
                    return out.toString();
                }
                if (c == '\\') {
                    if (pos + 1 >= s.length()) {
                        return null;
                    }
                    char e = s.charAt(pos + 1);
                    pos += 2;
                    switch (e) {
                        case '"' -> out.append('"');
                        case '\\' -> out.append('\\');
                        case '/' -> out.append('/');
                        case 'b' -> out.append('\b');
                        case 'f' -> out.append('\f');
                        case 'n' -> out.append('\n');
                        case 'r' -> out.append('\r');
                        case 't' -> out.append('\t');
                        case 'u' -> {
                            if (pos + 4 > s.length()) {
                                return null;
                            }
                            String hex = s.substring(pos, pos + 4);
                            if (!hex.matches("[0-9a-fA-F]{4}")) {
                                return null;
                            }
                            int cp = Integer.parseInt(hex, 16);
                            pos += 4;
                            if (cp >= 0xD800 && cp <= 0xDBFF) {
                                if (pos + 6 <= s.length() && s.charAt(pos) == '\\'
                                        && s.charAt(pos + 1) == 'u') {
                                    String hex2 = s.substring(pos + 2, pos + 6);
                                    if (hex2.matches("[0-9a-fA-F]{4}")) {
                                        int lo = Integer.parseInt(hex2, 16);
                                        if (lo >= 0xDC00 && lo <= 0xDFFF) {
                                            pos += 6;
                                            cp = 0x10000 + (cp - 0xD800) * 0x400
                                                + (lo - 0xDC00);
                                        }
                                    }
                                }
                                if (cp >= 0xD800 && cp <= 0xDFFF) {
                                    return null;
                                }
                            } else if (cp >= 0xDC00 && cp <= 0xDFFF) {
                                return null;
                            }
                            out.appendCodePoint(cp);
                        }
                        default -> {
                            return null;
                        }
                    }
                } else {
                    if (c < 0x20) {
                        return null;
                    }
                    out.append(c);
                    pos++;
                }
            }
        }

        private Object parseNumber() {
            int start = pos;
            if (pos < s.length() && s.charAt(pos) == '-') {
                pos++;
            }
            if (pos >= s.length()) {
                return FAIL;
            }
            char c = s.charAt(pos);
            if (c == '0') {
                pos++;
                if (pos < s.length() && Character.isDigit(s.charAt(pos))) {
                    return FAIL;
                }
            } else if (c >= '1' && c <= '9') {
                while (pos < s.length() && Character.isDigit(s.charAt(pos))) {
                    pos++;
                }
            } else {
                return FAIL;
            }
            boolean integral = true;
            if (pos < s.length() && s.charAt(pos) == '.') {
                integral = false;
                pos++;
                if (pos >= s.length() || !Character.isDigit(s.charAt(pos))) {
                    return FAIL;
                }
                while (pos < s.length() && Character.isDigit(s.charAt(pos))) {
                    pos++;
                }
            }
            if (pos < s.length()) {
                char e = s.charAt(pos);
                if (e == 'e' || e == 'E') {
                    integral = false;
                    pos++;
                    if (pos < s.length()
                            && (s.charAt(pos) == '+' || s.charAt(pos) == '-')) {
                        pos++;
                    }
                    if (pos >= s.length() || !Character.isDigit(s.charAt(pos))) {
                        return FAIL;
                    }
                    while (pos < s.length() && Character.isDigit(s.charAt(pos))) {
                        pos++;
                    }
                }
            }
            String text = s.substring(start, pos);
            try {
                double value = Double.parseDouble(text);
                if (integral && value >= -2147483648d && value <= 2147483647d) {
                    return (long) value;
                }
                return value;
            } catch (NumberFormatException failure) {
                return FAIL;
            }
        }
    }

    // =========================================================================
    // Canonical text
    // =========================================================================

    /** The E8 canonical number text (shortest round-trippable, Double.toString). */
    public static String numberText(double value) {
        return Double.toString(value);
    }

    /** The RFC-8259 string body of a scalar-valid string (invalid → null). */
    public static String escapeString(String text) {
        if (!validScalars(text)) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    /**
     * The scalar-validity check of the closed Unicode scalar domain: no
     * unpaired surrogate and no U+10FFFF-exceeding pair (the Java
     * carrier is UTF-8-decoded by the runtime already).
     */
    public static boolean validScalars(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(i + 1))) {
                    return false;
                }
                i++;
            } else if (Character.isLowSurrogate(c)) {
                return false;
            }
        }
        return true;
    }

    // =========================================================================
    // The from-json walk (JSON_FROM_NULL)
    // =========================================================================

    /** The JSON_FROM_CLASS walk: the tagged instance or language null. */
    public static Object fromClass(Plan plan, String text) {
        Object doc = parse(text);
        if (doc == SYNTAX || doc == JNULL) {
            return null;
        }
        if (doc instanceof JvmRuntime.Array && ((JvmRuntime.Array) doc).length == 0) {
            doc = new JvmRuntime.Table();
        }
        if (!(doc instanceof JvmRuntime.Table table)) {
            return null;
        }
        // The extra-key gate in document order.
        for (String key : table.entries.keySet()) {
            if (fieldOf(plan, key) == null) {
                return null;
            }
        }
        Map<String, Object> values = new LinkedHashMap<>();
        Set<String> present = new LinkedHashSet<>();
        // Provided-field decode in declaration order.
        for (Field field : plan.fields) {
            if (!table.keys.contains(field.name)) {
                continue;
            }
            Object decoded = decodeRaw(field.descriptor, table.entries.get(field.name), 0);
            if (decoded == FAIL) {
                return null;
            }
            values.put(field.name, decoded);
            present.add(field.name);
        }
        // Default application in declaration order (skip-provided).
        for (Field field : plan.fields) {
            if (present.contains(field.name) || field.optional) {
                continue;
            }
            if (field.defaultThunk == null) {
                return null;
            }
            Object produced;
            try {
                produced = runDefault(field);
            } catch (DefaultFailure failure) {
                return null;
            }
            values.put(field.name, produced);
            present.add(field.name);
        }
        // Final validation in declaration order.
        for (Field field : plan.fields) {
            if (!present.contains(field.name)) {
                continue;
            }
            if (!conforms(field.descriptor, values.get(field.name))) {
                return null;
            }
        }
        JvmRuntime.ClassInstance instance = plan.instanceSupplier.get();
        for (Field field : plan.fields) {
            if (present.contains(field.name)) {
                instance.write(field.name, values.get(field.name));
            }
        }
        return instance;
    }

    /** The failed default child (the JSON_FROM_NULL carrier of the walk). */
    private static final class DefaultFailure extends RuntimeException {
        DefaultFailure(Throwable cause) {
            super(cause);
        }
    }

    /** One CLASS_DEFAULT child execution with its own events; throws on failure. */
    private static Object runDefault(Field field) {
        JvmRuntime.ev(JvmRuntime.currentModule(), field.defaultKey, "START",
            "CLASS_DEFAULT", field.defaultDigest, "-", List.of(), null, null);
        Object produced;
        try {
            produced = field.defaultThunk.get();
        } catch (RuntimeException failure) {
            JvmRuntime.ev(JvmRuntime.currentModule(), field.defaultKey, "FAILURE",
                "CLASS_DEFAULT", field.defaultDigest, "-", List.of(), null,
                JvmRuntime.errtext(failure));
            throw new DefaultFailure(failure);
        }
        JvmRuntime.ev(JvmRuntime.currentModule(), field.defaultKey, "SUCCESS",
            "CLASS_DEFAULT", field.defaultDigest, "-", List.of(),
            JvmRuntime.atom(produced, field.kind), null);
        return produced;
    }

    private static final Object FAIL = new Object();

    private static Field fieldOf(Plan plan, String name) {
        for (Field field : plan.fields) {
            if (field.name.equals(name)) {
                return field;
            }
        }
        return null;
    }

    /** One provided value's decode against a declared descriptor. */
    private static Object decodeRaw(String descriptor, Object raw, int depth) {
        if (depth > MAX_DEPTH) {
            return FAIL;
        }
        if (descriptor.startsWith("nullable:")) {
            if (raw == JNULL) {
                return null;
            }
            return decodeRaw(descriptor.substring("nullable:".length()), raw, depth);
        }
        if (descriptor.startsWith("array(")) {
            if (!(raw instanceof JvmRuntime.Array array)) {
                return FAIL;
            }
            String inner = descriptor.substring("array(".length(),
                descriptor.length() - 1);
            JvmRuntime.Array out = new JvmRuntime.Array(0);
            for (int i = 0; i < array.elements.size(); i++) {
                Object element = decodeRaw(inner, array.elements.get(i), depth + 1);
                if (element == FAIL) {
                    return FAIL;
                }
                out.elements.add(element);
            }
            out.length = out.elements.size();
            return out;
        }
        if (descriptor.startsWith("@")) {
            throw new IllegalStateException("a nested @jsonable class field ("
                + descriptor + ") has no shared JSON walk in this slice "
                + "(producer defect, the nested shape is not emitted)");
        }
        switch (descriptor) {
            case "null" -> {
                return raw == JNULL ? null : FAIL;
            }
            case "boolean" -> {
                return raw instanceof Boolean ? raw : FAIL;
            }
            case "int" -> {
                return raw instanceof Long ? raw : FAIL;
            }
            case "number" -> {
                // A number descriptor admits both lexical forms; an
                // integer lexical carrier converts exactly to the number
                // variant (the oracle's Int -> Number rule), so a decoded
                // number slot always carries its own variant.
                if (raw instanceof Long longValue) {
                    return Double.valueOf(longValue.doubleValue());
                }
                return raw instanceof Double ? raw : FAIL;
            }
            case "string" -> {
                return raw instanceof String string && validScalars(string) ? raw : FAIL;
            }
            case "table" -> {
                if (raw instanceof JvmRuntime.Table) {
                    return toLanguage(raw, 0);
                }
                if (raw instanceof JvmRuntime.Array array && array.length == 0) {
                    return new JvmRuntime.Table();
                }
                return FAIL;
            }
            default -> {
                return FAIL;
            }
        }
    }

    /**
     * Converts one parsed JSON subtree into the shared language value
     * model: containers recurse, a scalar leaf maps as-is (its own
     * Long/Double/String/Boolean variant — the same per-occurrence
     * classification the oracle's Int/Number carriers keep), and the
     * JSON-null marker is mapped by the caller.
     */
    private static Object toLanguage(Object value, int depth) {
        if (depth > MAX_DEPTH) {
            return null;
        }
        if (value instanceof JvmRuntime.Array array) {
            JvmRuntime.Array out = new JvmRuntime.Array(0);
            for (Object element : array.elements) {
                out.elements.add(element == JNULL ? null : toLanguage(element, depth + 1));
            }
            out.length = out.elements.size();
            return out;
        }
        if (!(value instanceof JvmRuntime.Table table)) {
            return value;
        }
        JvmRuntime.Table out = new JvmRuntime.Table();
        for (Map.Entry<String, Object> entry : table.entries.entrySet()) {
            Object element = entry.getValue();
            out.write(entry.getKey(),
                element == JNULL ? null : toLanguage(element, depth + 1));
        }
        return out;
    }

    /** The final descriptor conformance check of the walk (K-D8 step 7). */
    private static boolean conforms(String descriptor, Object value) {
        if (descriptor.startsWith("nullable:")) {
            if (value == null) {
                return true;
            }
            return conforms(descriptor.substring("nullable:".length()), value);
        }
        if (descriptor.startsWith("array(")) {
            if (!(value instanceof JvmRuntime.Array array)) {
                return false;
            }
            String inner = descriptor.substring("array(".length(),
                descriptor.length() - 1);
            for (Object element : array.elements) {
                if (element == null || !conforms(inner, element)) {
                    return false;
                }
            }
            return true;
        }
        return switch (descriptor) {
            case "null" -> value == null;
            case "boolean" -> value instanceof Boolean;
            case "int" -> value instanceof Long;
            case "number" -> value instanceof Double || value instanceof Long;
            case "string" -> value instanceof String string && validScalars(string);
            case "table" -> value instanceof JvmRuntime.Table;
            default -> !descriptor.startsWith("@");
        };
    }

    // =========================================================================
    // The to-json walk (JSON_TO_ERROR)
    // =========================================================================

    /** The JSON_TO_CLASS walk: the deterministic text or a JSON_TO_ERROR projection. */
    public static String toClass(Plan plan, Object root) {
        return encodeInstance(plan, root, "", new java.util.IdentityHashMap<>());
    }

    private static String encodeInstance(Plan plan, Object value, String path,
                                         java.util.IdentityHashMap<Object, Boolean> visited) {
        if (!(value instanceof JvmRuntime.ClassInstance instance)
                || !instance.classIdText().equals(plan.classIdText)) {
            throw new Projection(path, actualToken(value));
        }
        if (visited.put(value, Boolean.TRUE) != null) {
            throw new Projection(path, actualToken(value));
        }
        try {
            StringBuilder out = new StringBuilder("{");
            boolean first = true;
            for (Field field : plan.fields) {
                if (!instance.isPresent(field.name)) {
                    if (!field.optional) {
                        throw new Projection(
                            path.isEmpty() ? field.name : path + "." + field.name,
                            "missing");
                    }
                    continue;
                }
                Object fieldValue = instance.read(field.name);
                String fieldPath = path.isEmpty() ? field.name : path + "." + field.name;
                String encoded = encodeField(field.descriptor, fieldValue, fieldPath,
                    visited);
                if (!first) {
                    out.append(',');
                }
                first = false;
                out.append('"').append(escapeString(field.name)).append('"').append(':')
                    .append(encoded);
            }
            out.append('}');
            return out.toString();
        } finally {
            visited.remove(value);
        }
    }

    private static String encodeField(String descriptor, Object value, String path,
                                      java.util.IdentityHashMap<Object, Boolean> visited) {
        if (descriptor.startsWith("nullable:")) {
            if (value == null) {
                return "null";
            }
            return encodeField(descriptor.substring("nullable:".length()), value, path,
                visited);
        }
        if (descriptor.startsWith("array(")) {
            if (!(value instanceof JvmRuntime.Array array)) {
                throw new Projection(path, actualToken(value));
            }
            if (visited.put(value, Boolean.TRUE) != null) {
                throw new Projection(path, "array");
            }
            try {
                String inner = descriptor.substring("array(".length(),
                    descriptor.length() - 1);
                StringBuilder out = new StringBuilder("[");
                for (int i = 0; i < array.elements.size(); i++) {
                    if (i > 0) {
                        out.append(',');
                    }
                    out.append(encodeField(inner, array.elements.get(i),
                        path + "[" + i + "]", visited));
                }
                out.append(']');
                return out.toString();
            } finally {
                visited.remove(value);
            }
        }
        if (descriptor.startsWith("@")) {
            throw new IllegalStateException("a nested @jsonable class field ("
                + descriptor + ") has no shared JSON walk in this slice "
                + "(producer defect, the nested shape is not emitted)");
        }
        return switch (descriptor) {
            case "null" -> {
                if (value != null) {
                    throw new Projection(path, actualToken(value));
                }
                yield "null";
            }
            case "boolean" -> {
                if (!(value instanceof Boolean bool)) {
                    throw new Projection(path, actualToken(value));
                }
                yield bool ? "true" : "false";
            }
            case "int" -> {
                if (!(value instanceof Long longValue)) {
                    throw new Projection(path, actualToken(value));
                }
                yield Long.toString(longValue);
            }
            case "number" -> {
                if (value instanceof Long longValue) {
                    yield Long.toString(longValue);
                }
                if (!(value instanceof Double number)) {
                    throw new Projection(path, actualToken(value));
                }
                if (!Double.isFinite(number)) {
                    throw new Projection(path, "number");
                }
                yield Double.toString(number);
            }
            case "string" -> {
                if (!(value instanceof String string)) {
                    throw new Projection(path, actualToken(value));
                }
                String escaped = escapeString(string);
                if (escaped == null) {
                    throw new Projection(path, "invalid-unicode");
                }
                yield "\"" + escaped + "\"";
            }
            case "table" -> {
                if (!(value instanceof JvmRuntime.Table table)) {
                    throw new Projection(path, actualToken(value));
                }
                yield encodeTable(table, path, visited);
            }
            default -> throw new Projection(path, actualToken(value));
        };
    }

    private static String encodeTable(JvmRuntime.Table table, String path,
                                      java.util.IdentityHashMap<Object, Boolean> visited) {
        if (visited.put(table, Boolean.TRUE) != null) {
            throw new Projection(path, "table");
        }
        try {
            Set<String> keys = new TreeSet<>(table.keys);
            StringBuilder out = new StringBuilder("{");
            boolean first = true;
            for (String key : keys) {
                String escapedKey = escapeString(key);
                if (escapedKey == null) {
                    throw new Projection(path, "shape");
                }
                if (!first) {
                    out.append(',');
                }
                first = false;
                Object element = table.entries.get(key);
                String elementPath = path.isEmpty() ? key : path + "." + key;
                out.append('"').append(escapedKey).append('"').append(':');
                out.append(encodeLeaf(element, elementPath, visited));
            }
            out.append('}');
            return out.toString();
        } finally {
            visited.remove(table);
        }
    }

    /** One table/array interior element (JSON-shaped data, first failure wins). */
    private static String encodeLeaf(Object value, String path,
                                     java.util.IdentityHashMap<Object, Boolean> visited) {
        if (value == null || value == JNULL) {
            return "null";
        }
        if (value instanceof Boolean bool) {
            return bool ? "true" : "false";
        }
        if (value instanceof Long longValue) {
            return Long.toString(longValue);
        }
        if (value instanceof Double number) {
            if (!Double.isFinite(number)) {
                throw new Projection(path, "number");
            }
            return Double.toString(number);
        }
        if (value instanceof String string) {
            String escaped = escapeString(string);
            if (escaped == null) {
                throw new Projection(path, "invalid-unicode");
            }
            return "\"" + escaped + "\"";
        }
        if (value instanceof JvmRuntime.Table table) {
            return encodeTable(table, path, visited);
        }
        if (value instanceof JvmRuntime.Array array) {
            if (visited.put(array, Boolean.TRUE) != null) {
                throw new Projection(path, "array");
            }
            try {
                StringBuilder out = new StringBuilder("[");
                for (int i = 0; i < array.elements.size(); i++) {
                    if (i > 0) {
                        out.append(',');
                    }
                    out.append(encodeLeaf(array.elements.get(i), path + "[" + i + "]",
                        visited));
                }
                out.append(']');
                return out.toString();
            } finally {
                visited.remove(array);
            }
        }
        throw new Projection(path, actualToken(value));
    }

    /** The canonical actual-kind token of one value (classes render their identity). */
    private static String actualToken(Object value) {
        if (value instanceof JvmRuntime.ClassInstance instance) {
            return "class:" + instance.classIdText();
        }
        return JvmRuntime.actualOf("ref", value);
    }
}
