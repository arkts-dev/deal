package deal.codegen.jvm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

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

    /** One field of a class plan in declaration order. */
    public static final class Field {
        public final String name;
        public final String descriptor;
        public final boolean optional;
        public final String kind;
        public Field(String name, String descriptor, boolean optional, String kind) {
            this.name = name;
            this.descriptor = descriptor;
            this.optional = optional;
            this.kind = kind;
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
            // The nested @jsonable class walk resolves a nested class's
            // own plan by its canonical class identity (the same identity
            // the field descriptor text carries).
            PLANS.put(classIdText, this);
        }
    }

    /** The session's class plans by canonical class identity text. */
    private static final java.util.Map<String, Plan> PLANS =
        new java.util.concurrent.ConcurrentHashMap<>();

    /** The plan of one declared class identity, or {@code null}. */
    private static Plan planOf(String classIdText) {
        return PLANS.get(classIdText);
    }

    /**
     * The first declaration-order to-json failure (the {@code JSON_TO_ERROR}
     * projection): the failing position, the failing value's closed
     * typed-boundary token, and the closed arm selection — {@code cycle} is
     * true exactly for a path-local container re-entry, which selects the
     * walk family's cycle arm. The walk selects the arm by its own closed
     * marker, never by a token comparison
     * (jsonable-tojson-walk-arm-binding W3). The carrier is data-only: the
     * catch site renders the arm through {@link JvmRuntime#arm}, which is
     * the only producer of the product text, so no product message is
     * composed here.
     */
    public static final class Projection extends RuntimeException {
        public final String fieldPath;
        public final String actual;
        public final boolean cycle;

        Projection(String fieldPath, String actual) {
            this(fieldPath, actual, false);
        }

        Projection(String fieldPath, String actual, boolean cycle) {
            super(null, null);
            this.fieldPath = fieldPath;
            this.actual = actual;
            this.cycle = cycle;
        }

        /** The path-local container re-entry needle's failure (the cycle arm). */
        static Projection cycle(String fieldPath) {
            return new Projection(fieldPath, null, true);
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
            Object value = parseValue();
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

        /** The sentinel a container open returns to the frame loop. */
        private static final Object OPENED = new Object();

        /**
         * One open container of the stack-safe parser. The frame's
         * {@code state} drives its member loop and {@code pendingKey}
         * carries the object member awaiting its value.
         */
        private static final class Frame {

            static final int OBJECT_FIRST = 0;
            static final int OBJECT_KEY = 1;
            static final int VALUE = 2;
            static final int OBJECT_AFTER = 3;
            static final int ARRAY_FIRST = 4;
            static final int ARRAY_AFTER = 5;

            final boolean array;
            final JvmRuntime.Table table;
            final JvmRuntime.Array elements;
            final java.util.Set<String> decodedKeys;
            String pendingKey;
            int state;

            private Frame(boolean array, JvmRuntime.Table table,
                          JvmRuntime.Array elements,
                          java.util.Set<String> decodedKeys, int state) {
                this.array = array;
                this.table = table;
                this.elements = elements;
                this.decodedKeys = decodedKeys;
                this.state = state;
            }

            static Frame object() {
                return new Frame(false, new JvmRuntime.Table(), null,
                    new java.util.HashSet<>(), OBJECT_FIRST);
            }

            static Frame array() {
                return new Frame(true, null, new JvmRuntime.Array(0), null,
                    ARRAY_FIRST);
            }
        }

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

        /**
         * Parses one value at the cursor: a scalar returns directly, a
         * container pushes its frame on {@code stack} and returns
         * {@link #OPENED}. The frame loop of {@link #parseValue()} then
         * drives the open container.
         */
        private Object parseValueAtCursor(java.util.Deque<Frame> stack) {
            skipWs();
            if (pos >= s.length()) {
                return FAIL;
            }
            char c = s.charAt(pos);
            if (c == '{') {
                pos++;
                stack.push(Frame.object());
                return OPENED;
            }
            if (c == '[') {
                pos++;
                stack.push(Frame.array());
                return OPENED;
            }
            if (c == '"') {
                return parseString();
            }
            if (c == 't') {
                if (s.startsWith("true", pos)) {
                    pos += 4;
                    return Boolean.TRUE;
                }
                return FAIL;
            }
            if (c == 'f') {
                if (s.startsWith("false", pos)) {
                    pos += 5;
                    return Boolean.FALSE;
                }
                return FAIL;
            }
            if (c == 'n') {
                if (s.startsWith("null", pos)) {
                    pos += 4;
                    return JNULL;
                }
                return FAIL;
            }
            if (c == '-' || (c >= '0' && c <= '9')) {
                return parseNumber();
            }
            return FAIL;
        }

        /**
         * The stack-safe parse of one value: an explicit frame stack
         * replaces the recursive descent, so a finite nested document
         * has no stack bound. Every transition and failure outcome is
         * the recursive parser's.
         */
        private Object parseValue() {
            java.util.Deque<Frame> stack = new java.util.ArrayDeque<>();
            Object completed = null;
            boolean haveValue = false;
            while (true) {
                if (haveValue) {
                    if (stack.isEmpty()) {
                        return completed;
                    }
                    Frame parent = stack.peek();
                    if (parent.array) {
                        parent.elements.elements.add(completed);
                        parent.elements.length = parent.elements.elements.size();
                        parent.state = Frame.ARRAY_AFTER;
                    } else {
                        parent.table.write(parent.pendingKey, completed);
                        parent.state = Frame.OBJECT_AFTER;
                    }
                    completed = null;
                    haveValue = false;
                    continue;
                }
                if (stack.isEmpty()) {
                    completed = parseValueAtCursor(stack);
                    if (completed == FAIL) {
                        return FAIL;
                    }
                    haveValue = completed != OPENED;
                    continue;
                }
                Frame frame = stack.peek();
                switch (frame.state) {
                    case Frame.OBJECT_FIRST -> {
                        skipWs();
                        if (pos < s.length() && s.charAt(pos) == '}') {
                            pos++;
                            completed = frame.table;
                            stack.pop();
                            haveValue = true;
                        } else {
                            frame.state = Frame.OBJECT_KEY;
                        }
                    }
                    case Frame.OBJECT_KEY -> {
                        skipWs();
                        if (pos >= s.length() || s.charAt(pos) != '"') {
                            return FAIL;
                        }
                        String key = parseString();
                        if (key == null) {
                            return FAIL;
                        }
                        // A member key whose decoded scalar text equals
                        // an earlier member key of the same object is a
                        // parse defect (the decoded-key duplicate rule).
                        if (!frame.decodedKeys.add(key)) {
                            return FAIL;
                        }
                        skipWs();
                        if (pos >= s.length() || s.charAt(pos) != ':') {
                            return FAIL;
                        }
                        pos++;
                        frame.pendingKey = key;
                        frame.state = Frame.VALUE;
                    }
                    case Frame.VALUE -> {
                        skipWs();
                        if (pos >= s.length()) {
                            return FAIL;
                        }
                        completed = parseValueAtCursor(stack);
                        if (completed == FAIL) {
                            return FAIL;
                        }
                        haveValue = completed != OPENED;
                    }
                    case Frame.OBJECT_AFTER -> {
                        skipWs();
                        if (pos >= s.length()) {
                            return FAIL;
                        }
                        char c = s.charAt(pos);
                        if (c == ',') {
                            pos++;
                            frame.state = Frame.OBJECT_KEY;
                        } else if (c == '}') {
                            pos++;
                            completed = frame.table;
                            stack.pop();
                            haveValue = true;
                        } else {
                            return FAIL;
                        }
                    }
                    case Frame.ARRAY_FIRST -> {
                        skipWs();
                        if (pos < s.length() && s.charAt(pos) == ']') {
                            pos++;
                            completed = frame.elements;
                            stack.pop();
                            haveValue = true;
                        } else {
                            frame.state = Frame.VALUE;
                        }
                    }
                    case Frame.ARRAY_AFTER -> {
                        skipWs();
                        if (pos >= s.length()) {
                            return FAIL;
                        }
                        char c = s.charAt(pos);
                        if (c == ',') {
                            pos++;
                            frame.state = Frame.VALUE;
                        } else if (c == ']') {
                            pos++;
                            completed = frame.elements;
                            stack.pop();
                            haveValue = true;
                        } else {
                            return FAIL;
                        }
                    }
                    default -> throw new IllegalStateException(
                        "unknown JSON parser frame state " + frame.state);
                }
            }
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

        private static boolean isAsciiDigit(char c) {
            return c >= '0' && c <= '9';
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
                if (pos < s.length() && isAsciiDigit(s.charAt(pos))) {
                    return FAIL;
                }
            } else if (c >= '1' && c <= '9') {
                while (pos < s.length() && isAsciiDigit(s.charAt(pos))) {
                    pos++;
                }
            } else {
                return FAIL;
            }
            if (pos < s.length() && s.charAt(pos) == '.') {
                pos++;
                if (pos >= s.length() || !isAsciiDigit(s.charAt(pos))) {
                    return FAIL;
                }
                while (pos < s.length() && isAsciiDigit(s.charAt(pos))) {
                    pos++;
                }
            }
            if (pos < s.length()) {
                char e = s.charAt(pos);
                if (e == 'e' || e == 'E') {
                    pos++;
                    if (pos < s.length()
                            && (s.charAt(pos) == '+' || s.charAt(pos) == '-')) {
                        pos++;
                    }
                    if (pos >= s.length() || !isAsciiDigit(s.charAt(pos))) {
                        return FAIL;
                    }
                    while (pos < s.length() && isAsciiDigit(s.charAt(pos))) {
                        pos++;
                    }
                }
            }
            String text = s.substring(start, pos);
            try {
                return Double.valueOf(
                    deal.semantic.ir.JsonNumberClassifier.toDouble(text));
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
        return value == 0 ? "0" : Double.toString(value);
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

    /**
     * The JSON_FROM_CLASS walk: the tagged instance or language null.
     * Only a JSON object document decodes: a scalar, a JSON null, and an
     * array document of any length — the empty array included — return
     * the language null.
     */
    public static Object fromClass(Plan plan, String text) {
        Object doc = parse(text);
        if (doc == SYNTAX || doc == JNULL) {
            return null;
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
            Object decoded = decodeRaw(field.descriptor, table.entries.get(field.name));
            if (decoded == FAIL) {
                return null;
            }
            values.put(field.name, decoded);
            present.add(field.name);
        }
        for (Field field : plan.fields) {
            if (!field.optional && !present.contains(field.name)) return null;
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
    private static Object decodeRaw(String descriptor, Object raw) {
        if (descriptor.startsWith("nullable:")) {
            if (raw == JNULL) {
                return null;
            }
            return decodeRaw(descriptor.substring("nullable:".length()), raw);
        }
        if (descriptor.startsWith("array(")) {
            if (!(raw instanceof JvmRuntime.Array array)) {
                return FAIL;
            }
            String inner = descriptor.substring("array(".length(),
                descriptor.length() - 1);
            JvmRuntime.Array out = new JvmRuntime.Array(0);
            for (int i = 0; i < array.elements.size(); i++) {
                Object element = decodeRaw(inner, array.elements.get(i));
                if (element == FAIL) {
                    return FAIL;
                }
                out.elements.add(element);
            }
            out.length = out.elements.size();
            return out;
        }
        if (descriptor.startsWith("@")) {
            // The nested @jsonable class decode (K-D8 step 6): the nested
            // class's structural plan validates every required key.
            // The nested value is a JSON object, exactly the root
            // gate's own shape: an array value of any length, the empty
            // array included, is a validation failure.
            if (!(raw instanceof JvmRuntime.Table document)) {
                return FAIL;
            }
            Object nested = decodeNestedClass(descriptor, document);
            return nested == null ? FAIL : nested;
        }
        switch (descriptor) {
            case "null" -> {
                return raw == JNULL ? null : FAIL;
            }
            case "boolean" -> {
                return raw instanceof Boolean ? raw : FAIL;
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
                // A table field accepts only a JSON object
                // (docs/spec-v1.4.md:695): an array-shaped value of any
                // length, the empty array included, is a rejection —
                // never an empty table.
                return raw instanceof JvmRuntime.Table ? toLanguage(raw) : FAIL;
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
    private static Object toLanguage(Object value) {
        if (value instanceof JvmRuntime.Array array) {
            JvmRuntime.Array out = new JvmRuntime.Array(0);
            for (Object element : array.elements) {
                out.elements.add(element == JNULL ? null : toLanguage(element));
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
                element == JNULL ? null : toLanguage(element));
        }
        return out;
    }

    /**
     * The nested @jsonable class decode of one JSON object (K-D8 step 6,
     * the same phase order as the top walk): the nested extra-key gate,
     * required-presence checks, declaration-order decode, final descriptor
     * validation, and the tagged instance. Returns
     * {@code null} on any walk failure (language null), a producer defect
     * when no plan resolves for the declared identity.
     */
    private static Object decodeNestedClass(String classIdText,
                                            JvmRuntime.Table table) {
        Plan nested = planOf(classIdText);
        if (nested == null) {
            throw new IllegalStateException("JSON_FROM_CLASS has no plan "
                + classIdText + " for a nested @jsonable class field "
                + "(producer defect)");
        }
        for (String key : table.keys) {
            if (fieldOf(nested, key) == null) {
                return null;
            }
        }
        Map<String, Object> values = new LinkedHashMap<>();
        Set<String> present = new LinkedHashSet<>();
        for (Field field : nested.fields) {
            if (!table.keys.contains(field.name)) {
                continue;
            }
            Object decoded = decodeRaw(field.descriptor,
                table.entries.get(field.name));
            if (decoded == FAIL) {
                return null;
            }
            values.put(field.name, decoded);
            present.add(field.name);
        }
        for (Field field : nested.fields) {
            if (!field.optional && !present.contains(field.name)) return null;
        }
        for (Field field : nested.fields) {
            if (!present.contains(field.name)) {
                continue;
            }
            if (!conforms(field.descriptor, values.get(field.name))) {
                return null;
            }
        }
        JvmRuntime.ClassInstance instance = nested.instanceSupplier.get();
        for (Field field : nested.fields) {
            if (present.contains(field.name)) {
                instance.write(field.name, values.get(field.name));
            }
        }
        return instance;
    }

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
                // A null element is normalized to the language null the
                // element descriptor then decides: a nullable element admits
                // it (the spec's recursive T | null jsonable types), a
                // non-nullable element keeps its own rejection. The decode
                // already rejected a null at every non-nullable element; the
                // check never rejects it unconditionally.
                if (!conforms(inner, element)) {
                    return false;
                }
            }
            return true;
        }
        if (descriptor.startsWith("@")) {
            // The nested class conformance check: the tagged instance of
            // exactly the declared class identity.
            return value instanceof JvmRuntime.ClassInstance instance
                && instance.classIdText().equals(descriptor);
        }
        return switch (descriptor) {
            case "null" -> value == null;
            case "boolean" -> value instanceof Boolean;
            case "number" -> value instanceof Number;
            case "string" -> value instanceof String string && validScalars(string);
            case "table" -> value instanceof JvmRuntime.Table;
            default -> true;
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
            throw Projection.cycle(path);
        }
        try {
            StringBuilder out = new StringBuilder("{");
            boolean first = true;
            for (Field field : plan.fields) {
                if (!instance.isPresent(field.name)) {
                    if (!field.optional) {
                        throw new Projection(
                            path.isEmpty() ? field.name : path + "." + field.name,
                            actualToken(JvmRuntime.MISSING));
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
                throw Projection.cycle(path);
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
            Plan nested = planOf(descriptor);
            if (nested == null) {
                throw new IllegalStateException("JSON_TO_CLASS has no plan "
                    + descriptor + " for a nested @jsonable class field "
                    + "(producer defect)");
            }
            return encodeInstance(nested, value, path, visited);
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
            case "number" -> {
                if (!(value instanceof Number numeric)) {
                    throw new Projection(path, actualToken(value));
                }
                double number = numeric.doubleValue();
                if (!Double.isFinite(number)) {
                    throw new Projection(path, actualToken(value));
                }
                yield number == 0 ? "0" : Double.toString(number);
            }
            case "string" -> {
                if (!(value instanceof String string)) {
                    throw new Projection(path, actualToken(value));
                }
                String escaped = escapeString(string);
                if (escaped == null) {
                    throw new Projection(path, actualToken(string));
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

    /**
     * One open container of the stack-safe dynamic-value walk: the
     * explicit work stack replaces the recursion, so a finite nested
     * value has no stack bound. Each frame emits its entries in
     * first-insertion/index order from {@link #index}.
     */
    private static final class EncodeFrame {

        final JvmRuntime.Table table;
        final JvmRuntime.Array array;
        final List<String> keys;
        final String path;
        int index;

        EncodeFrame(JvmRuntime.Table table, List<String> keys, String path) {
            this.table = table;
            this.array = null;
            this.keys = keys;
            this.path = path;
        }

        EncodeFrame(JvmRuntime.Array array, String path) {
            this.table = null;
            this.array = array;
            this.keys = null;
            this.path = path;
        }
    }

    /**
     * The stack-safe walk of JSON-shaped tables and arrays (the carrier's
     * first-insertion order, arrays in index order): an explicit work
     * stack replaces the recursion, and the path-local identity set keeps
     * cycle detection (entered on open, removed on close). The first
     * failure in emission order wins unchanged.
     */
    private static String encodeDynamic(Object root, String rootPath,
            java.util.IdentityHashMap<Object, Boolean> visited) {
        StringBuilder out = new StringBuilder();
        java.util.Deque<EncodeFrame> stack = new java.util.ArrayDeque<>();
        appendDynamicValue(out, root, rootPath, visited, stack);
        while (!stack.isEmpty()) {
            EncodeFrame frame = stack.peek();
            if (frame.array == null) {
                if (frame.index < frame.keys.size()) {
                    int i = frame.index++;
                    String key = frame.keys.get(i);
                    String escapedKey = escapeString(key);
                    if (escapedKey == null) {
                        throw new Projection(frame.path, actualToken(key));
                    }
                    if (i > 0) {
                        out.append(',');
                    }
                    Object element = frame.table.entries.get(key);
                    String elementPath = frame.path.isEmpty() ? key
                        : frame.path + "." + key;
                    out.append('"').append(escapedKey).append('"').append(':');
                    appendDynamicValue(out, element, elementPath, visited, stack);
                } else {
                    out.append('}');
                    visited.remove(frame.table);
                    stack.pop();
                }
                continue;
            }
            if (frame.index < frame.array.elements.size()) {
                int i = frame.index++;
                if (i > 0) {
                    out.append(',');
                }
                appendDynamicValue(out, frame.array.elements.get(i),
                    frame.path + "[" + i + "]", visited, stack);
            } else {
                out.append(']');
                visited.remove(frame.array);
                stack.pop();
            }
        }
        return out.toString();
    }

    /**
     * Appends one interior value: a leaf emits its text, a container
     * checks the path-local identity and opens a frame. The first
     * declaration-order failure wins (pre-order), exactly as the
     * recursive walk selected it.
     */
    private static void appendDynamicValue(StringBuilder out, Object value, String path,
            java.util.IdentityHashMap<Object, Boolean> visited,
            java.util.Deque<EncodeFrame> stack) {
        if (value == null || value == JNULL) {
            out.append("null");
            return;
        }
        if (value instanceof Boolean bool) {
            out.append(bool ? "true" : "false");
            return;
        }
        if (value instanceof Number numeric) {
            double number = numeric.doubleValue();
            if (!Double.isFinite(number)) {
                throw new Projection(path, actualToken(number));
            }
            out.append(number == 0 ? "0" : Double.toString(number));
            return;
        }
        if (value instanceof String string) {
            String escaped = escapeString(string);
            if (escaped == null) {
                throw new Projection(path, actualToken(string));
            }
            out.append('"').append(escaped).append('"');
            return;
        }
        if (value instanceof JvmRuntime.Table table) {
            if (visited.put(table, Boolean.TRUE) != null) {
                throw Projection.cycle(path);
            }
            out.append('{');
            // The carrier's first-insertion order (the linked entry map):
            // an object spells its keys exactly as they were inserted,
            // never in sorted order.
            stack.push(new EncodeFrame(table,
                new ArrayList<>(table.entries.keySet()), path));
            return;
        }
        if (value instanceof JvmRuntime.Array array) {
            if (visited.put(array, Boolean.TRUE) != null) {
                throw Projection.cycle(path);
            }
            out.append('[');
            stack.push(new EncodeFrame(array, path));
            return;
        }
        throw new Projection(path, actualToken(value));
    }

    /** One table (object) of JSON-shaped data: the stack-safe dynamic walk. */
    private static String encodeTable(JvmRuntime.Table table, String path,
                                      java.util.IdentityHashMap<Object, Boolean> visited) {
        return encodeDynamic(table, path, visited);
    }

    /** One table/array interior element (JSON-shaped data, first failure wins). */
    private static String encodeLeaf(Object value, String path,
                                     java.util.IdentityHashMap<Object, Boolean> visited) {
        return encodeDynamic(value, path, visited);
    }

    /**
     * The closed typed-boundary token of one failing walk value (the bound
     * {@code JSON_TO_WALK} arm's actual): the target's projection
     * implementation ({@link JvmRuntime#actualOf}) — a class instance
     * projects its carried canonical class atom, never the
     * {@code class:<ClassId>} IR spelling.
     */
    private static String actualToken(Object value) {
        return JvmRuntime.actualOf("ref", value);
    }
}
