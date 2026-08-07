package deal.module;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses and holds the contents of a {@code deal.json} project manifest.
 *
 * <p>Only {@code moduleRoots}, {@code output}, and {@code backend} are
 * actively used in v0.6; the remaining fields are parsed but not enforced.</p>
 */
public final class DealConfig {

    private final List<String> moduleRoots;
    private final String output;
    private final String backend;
    private final List<String> permissions;
    private final Limits limits;
    private final List<String> externals;
    private final Dependencies dependencies;
    private final Path configFile;

    private DealConfig(Path configFile, List<String> moduleRoots, String output,
                       String backend, List<String> permissions, Limits limits,
                       List<String> externals, Dependencies dependencies) {
        this.configFile = configFile;
        this.moduleRoots = moduleRoots;
        this.output = output;
        this.backend = backend;
        this.permissions = permissions;
        this.limits = limits;
        this.externals = externals;
        this.dependencies = dependencies;
    }

    /** Attempts to load deal.json from the given directory. Returns null if not found. */
    public static DealConfig load(Path projectDir) throws IOException {
        Path configFile = projectDir.resolve("deal.json");
        if (!Files.exists(configFile)) return null;
        String raw = Files.readString(configFile);
        return parse(configFile, raw);
    }

    /** Parse deal.json JSON string. */
    public static DealConfig parse(Path configFile, String json) {
        // Simple hand-rolled JSON parser — deal.json is small and structured
        JsonObject root = JsonObject.parse(json);
        if (root == null) {
            throw new IllegalArgumentException("deal.json: invalid JSON");
        }

        List<String> moduleRoots = root.getStringList("moduleRoots");
        String output = root.getString("output");
        String backend = root.getString("backend");
        List<String> permissions = root.getStringList("permissions");
        Limits limits = Limits.fromJson(root.getObject("limits"));
        List<String> externals = root.getStringList("externals");
        Dependencies dependencies = Dependencies.fromJson(root.getObject("dependencies"));

        // Validate backend
        if (backend != null && !backend.equals("luajit")) {
            throw new IllegalArgumentException(
                "deal.json: unsupported backend '" + backend + "'. v0.6 only supports 'luajit'");
        }

        return new DealConfig(configFile, moduleRoots, output, backend,
            permissions, limits, externals, dependencies);
    }

    public List<String> moduleRoots() { return moduleRoots; }
    public String output() { return output; }
    public String backend() { return backend; }
    public List<String> permissions() { return permissions; }
    public Limits limits() { return limits; }
    public List<String> externals() { return externals; }
    public Dependencies dependencies() { return dependencies; }
    public Path configFile() { return configFile; }

    /** Reserved for future use. */
    public record Limits(Integer maxMemory, Integer maxCpuTime) {
        static Limits fromJson(JsonObject obj) {
            if (obj == null) return null;
            return new Limits(obj.getInt("maxMemory"), obj.getInt("maxCpuTime"));
        }
    }

    /** Reserved for future use. */
    public record Dependencies(List<String> items) {
        static Dependencies fromJson(JsonObject obj) {
            if (obj == null) return null;
            return new Dependencies(obj.getStringList("items"));
        }
    }

    // =========================================================================
    // Minimal JSON parser (no external dependencies)
    // =========================================================================

    private static final class JsonObject {
        private final java.util.Map<String, Object> map;

        private JsonObject(java.util.Map<String, Object> map) { this.map = map; }

        static JsonObject parse(String json) {
            JsonParser p = new JsonParser(json);
            Object val = p.parseValue();
            if (val instanceof java.util.Map<?, ?> m) {
                @SuppressWarnings("unchecked")
                java.util.Map<String, Object> cast =
                    (java.util.Map<String, Object>) m;
                return new JsonObject(cast);
            }
            return null;
        }

        String getString(String key) {
            Object v = map.get(key);
            return v instanceof String s ? s : null;
        }

        Integer getInt(String key) {
            Object v = map.get(key);
            if (v instanceof Long l) return l.intValue();
            if (v instanceof Integer i) return i;
            return null;
        }

        List<String> getStringList(String key) {
            Object v = map.get(key);
            if (v instanceof java.util.List<?> l) {
                List<String> result = new ArrayList<>();
                for (Object item : l) {
                    if (item instanceof String s) result.add(s);
                }
                return result;
            }
            return List.of();
        }

        JsonObject getObject(String key) {
            Object v = map.get(key);
            if (v instanceof java.util.Map<?, ?> m) {
                @SuppressWarnings("unchecked")
                java.util.Map<String, Object> cast =
                    (java.util.Map<String, Object>) m;
                return new JsonObject(cast);
            }
            return null;
        }
    }

    private static final class JsonParser {
        private final String s;
        private int i;

        JsonParser(String s) { this.s = s; this.i = 0; }

        Object parseValue() {
            skipWS();
            if (i >= s.length()) return null;
            char c = s.charAt(i);
            return switch (c) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> parseString();
                default -> {
                    if (c == 't' || c == 'f') { yield parseBoolean(); }
                    if (c == 'n') { yield parseNull(); }
                    yield parseNumber();
                }
            };
        }

        java.util.Map<String, Object> parseObject() {
            expect('{');
            java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
            skipWS();
            if (i < s.length() && s.charAt(i) == '}') { i++; return map; }
            while (true) {
                skipWS();
                String key = parseString();
                skipWS();
                expect(':');
                Object val = parseValue();
                map.put(key, val);
                skipWS();
                if (i < s.length() && s.charAt(i) == ',') { i++; continue; }
                if (i < s.length() && s.charAt(i) == '}') { i++; break; }
                break;
            }
            return map;
        }

        java.util.List<Object> parseArray() {
            expect('[');
            java.util.List<Object> list = new ArrayList<>();
            skipWS();
            if (i < s.length() && s.charAt(i) == ']') { i++; return list; }
            while (true) {
                list.add(parseValue());
                skipWS();
                if (i < s.length() && s.charAt(i) == ',') { i++; continue; }
                if (i < s.length() && s.charAt(i) == ']') { i++; break; }
                break;
            }
            return list;
        }

        String parseString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == '"') { i++; return sb.toString(); }
                if (c == '\\') {
                    i++;
                    if (i < s.length()) {
                        char escape = s.charAt(i);
                        switch (escape) {
                            case '"': sb.append('"'); break;
                            case '\\': sb.append('\\'); break;
                            case '/': sb.append('/'); break;
                            case 'n': sb.append('\n'); break;
                            case 'r': sb.append('\r'); break;
                            case 't': sb.append('\t'); break;
                            default: sb.append(escape); break;
                        }
                    }
                } else {
                    sb.append(c);
                }
                i++;
            }
            return sb.toString();
        }

        Boolean parseBoolean() {
            if (s.startsWith("true", i)) { i += 4; return true; }
            i += 5; return false;
        }

        Object parseNull() { i += 4; return null; }

        Object parseNumber() {
            int start = i;
            if (i < s.length() && s.charAt(i) == '-') i++;
            while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
            if (i < s.length() && s.charAt(i) == '.') {
                i++;
                while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
            }
            if (i < s.length() && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
                i++;
                if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) i++;
                while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
            }
            String num = s.substring(start, i);
            try {
                return Long.parseLong(num);
            } catch (NumberFormatException e) {
                return Double.parseDouble(num);
            }
        }

        void expect(char c) {
            skipWS();
            if (i < s.length() && s.charAt(i) == c) { i++; return; }
            throw new IllegalArgumentException("Expected '" + c + "' at position " + i);
        }

        void skipWS() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }
    }
}
