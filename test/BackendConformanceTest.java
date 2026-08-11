package deal.test;

import deal.ast.*;
import deal.checker.*;
import deal.codegen.lua.LuaBackend;
import deal.lexer.*;
import deal.module.ExportExtractor;
import deal.parser.*;
import deal.types.Type;
import deal.types.Types;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Backend-agnostic conformance test runner for JSON fixture files.
 *
 * <p>Discovers all .json files under test/conformance/fixtures/, parses
 * them according to the backend fixture schema, compiles and executes
 * each test case against the LuaJIT backend, and asserts expectedOutput,
 * expectedError, and expectedExitCode.</p>
 *
 * <p>Fixture format (version 1.0):
 * <pre>{@code
 * {
 *   "version": "1.0",
 *   "tests": [{
 *     "name": "unique-test-name",
 *     "description": "what this test verifies",
 *     "source": "DEAL source code as string",
 *     "expectedOutput": "substring" | null,
 *     "expectedError": "E8001" | null,
 *     "expectedExitCode": 0 | 1,
 *     "backends": ["luajit", "jvm"]
 *   }]
 * }
 * }</pre>
 */
public class BackendConformanceTest {

    private static int passed = 0;
    private static int failed = 0;
    private static int skipped = 0;
    private static boolean luajitAvailable;

    // =========================================================================
    // Data types
    // =========================================================================

    private static final class FixtureTest {
        final String name;
        final String description;
        final String source;
        final String expectedOutput;
        final String expectedError;
        final int expectedExitCode;
        final List<String> backends;

        FixtureTest(String name, String description, String source,
                    String expectedOutput, String expectedError,
                    int expectedExitCode, List<String> backends) {
            this.name = name;
            this.description = description;
            this.source = source;
            this.expectedOutput = expectedOutput;
            this.expectedError = expectedError;
            this.expectedExitCode = expectedExitCode;
            this.backends = backends;
        }
    }

    // =========================================================================
    // Main
    // =========================================================================

    public static void main(String[] args) throws Exception {
        String fixturesRoot = "test/conformance/fixtures/";
        if (args.length > 0) {
            fixturesRoot = args[0];
        }

        try {
            new ProcessBuilder("luajit", "-v").start().waitFor();
            luajitAvailable = true;
        } catch (IOException e) {
            luajitAvailable = false;
        }

        System.out.println("=== Backend Conformance Test Suite ===");
        System.out.println("Fixtures root: " + fixturesRoot);
        System.out.println("LuaJIT: " + (luajitAvailable ? "available" :
            "NOT available (backend tests will be skipped)"));
        System.out.println();

        Path rootPath = Path.of(fixturesRoot);
        if (!Files.isDirectory(rootPath)) {
            System.out.println("Fixtures directory not found: " + fixturesRoot);
            System.exit(1);
            return;
        }

        // Discover all .json fixture files
        List<Path> fixtureFiles = new ArrayList<>();
        try (var stream = Files.walk(rootPath)) {
            stream.filter(p -> p.toString().endsWith(".json"))
                  .sorted()
                  .forEach(fixtureFiles::add);
        }

        System.out.println("Discovered " + fixtureFiles.size() + " fixture file(s)");
        System.out.println();

        for (Path fixtureFile : fixtureFiles) {
            runFixtureFile(fixtureFile);
        }

        // Print summary
        printSummary();

        if (failed > 0) {
            System.exit(1);
        }
    }

    // =========================================================================
    // Fixture processing
    // =========================================================================

    private static void runFixtureFile(Path fixtureFile) {
        System.out.println("  Fixture: " + fixtureFile);
        String jsonText;
        try {
            jsonText = Files.readString(fixtureFile);
        } catch (IOException e) {
            System.out.println("    ERROR: cannot read fixture file: " + e.getMessage());
            failed++;
            return;
        }

        List<FixtureTest> tests;
        try {
            tests = parseFixture(jsonText, fixtureFile.toString());
        } catch (Exception e) {
            System.out.println("    ERROR: invalid fixture: " + e.getMessage());
            failed++;
            return;
        }

        for (FixtureTest test : tests) {
            runFixtureTest(test);
        }
    }

    private static void runFixtureTest(FixtureTest test) {
        System.out.print("    [" + test.name + "] ");

        // Check if current backend should run this test
        if (!test.backends.contains("luajit")) {
            System.out.println("SKIP (backend not in backends list: " + test.backends + ")");
            skipped++;
            return;
        }

        if (!luajitAvailable) {
            System.out.println("SKIP (LuaJIT not available)");
            skipped++;
            return;
        }

        // Compile the source
        String lua;
        try {
            lua = compileDealSource(test.source, test.name);
        } catch (CompileException e) {
            // If the test expects a compile error, that's a pass
            if (test.expectedError != null && test.expectedExitCode == 1) {
                if (e.getMessage().contains(test.expectedError)) {
                    System.out.println("OK (compile error matches expected " + test.expectedError + ")");
                    passed++;
                } else {
                    System.out.println("FAIL (expected compile error " + test.expectedError +
                        ", got: " + e.getMessage() + ")");
                    failed++;
                }
            } else {
                System.out.println("FAIL (compile error: " + e.getMessage() + ")");
                failed++;
            }
            return;
        }

        if (lua == null) {
            System.out.println("FAIL (codegen failed)");
            failed++;
            return;
        }

        // Execute the compiled Lua
        ExecutionResult execResult = executeLua(lua, test.expectedError != null);
        if (execResult == null) {
            System.out.println("FAIL (Lua execution failed)");
            failed++;
            return;
        }

        // Assert expectedOutput
        boolean outputOk = true;
        if (test.expectedOutput != null) {
            if (!execResult.output.contains(test.expectedOutput)) {
                System.out.println("FAIL (@expectedOutput not found: \"" + test.expectedOutput + "\")");
                System.out.println("      actual: " + execResult.output.replace("\n", "\\n"));
                outputOk = false;
            }
        }

        // Assert expectedError
        boolean errorOk = true;
        if (test.expectedError != null) {
            String needle = "DEAL_ERROR_CODE: " + test.expectedError;
            if (!execResult.output.contains(needle)) {
                System.out.println("FAIL (expected " + needle + ", got: " +
                    execResult.output.replace("\n", "\\n") + ")");
                errorOk = false;
            }
        }

        // Assert expectedExitCode
        boolean exitCodeOk = true;
        if (execResult.exitCode != test.expectedExitCode) {
            System.out.println("FAIL (expected exit code " + test.expectedExitCode +
                ", got " + execResult.exitCode + ")");
            exitCodeOk = false;
        }

        if (outputOk && errorOk && exitCodeOk) {
            System.out.println("OK");
            passed++;
        } else {
            failed++;
        }
    }

    // =========================================================================
    // Compilation
    // =========================================================================

    private static final class CompileException extends Exception {
        CompileException(String message) {
            super(message);
        }
    }

    private static String compileDealSource(String source, String testName) throws CompileException {
        String filename = testName + ".deal";

        LexResult lex = new Lexer(source, filename).tokenize();
        if (lex.hasErrors()) {
            StringBuilder msg = new StringBuilder();
            for (Diagnostic d : lex.diagnostics()) {
                if ("error".equals(d.severity())) {
                    msg.append(d.code()).append(" ");
                }
            }
            throw new CompileException(msg.toString().trim());
        }

        Parser parser = new Parser(lex.tokens(), filename);
        ParseResult parseResult = parser.parse();
        if (parseResult.hasErrors()) {
            StringBuilder msg = new StringBuilder();
            for (Diagnostic d : parseResult.diagnostics()) {
                if ("error".equals(d.severity())) {
                    msg.append(d.code()).append(" ");
                }
            }
            throw new CompileException(msg.toString().trim());
        }

        BackendModuleResolver resolver = new BackendModuleResolver();
        NameResolver nr = new NameResolver(filename, resolver);
        SymbolTable symTable = nr.resolve(parseResult.program());
        if (nr.diagnostics().stream().anyMatch(d -> "error".equals(d.severity()))) {
            StringBuilder msg = new StringBuilder();
            for (Diagnostic d : nr.diagnostics()) {
                if ("error".equals(d.severity())) {
                    msg.append(d.code()).append(" ");
                }
            }
            throw new CompileException(msg.toString().trim());
        }

        CheckResult result = TypeChecker.check(filename, symTable, nr, parseResult.program());
        if (result.hasErrors()) {
            StringBuilder msg = new StringBuilder();
            for (Diagnostic d : result.diagnostics()) {
                if ("error".equals(d.severity())) {
                    msg.append(d.code()).append(" ");
                }
            }
            throw new CompileException(msg.toString().trim());
        }

        return LuaBackend.generate(parseResult.program(), result, filename);
    }

    // =========================================================================
    // Execution
    // =========================================================================

    private static final class ExecutionResult {
        final String output;
        final int exitCode;

        ExecutionResult(String output, int exitCode) {
            this.output = output;
            this.exitCode = exitCode;
        }
    }

    private static ExecutionResult executeLua(String luaSource, boolean isXpcallWrapped) {
        String runner;
        if (isXpcallWrapped) {
            runner = buildXpcallRunner(luaSource);
        } else {
            runner = buildRuntimeOkRunner(luaSource);
        }
        try {
            Path tmpDir = Files.createTempDirectory("deal_backend_");
            Path luaFile = tmpDir.resolve("test_main.lua");
            Files.writeString(luaFile, runner);

            // Copy runtime
            Path runtimeDir = tmpDir.resolve("deal");
            Files.createDirectories(runtimeDir);
            Files.copy(Path.of("deal/runtime.lua"), runtimeDir.resolve("runtime.lua"));

            // Copy stdlib .lua files
            Path stdDir = Path.of("std");
            if (Files.isDirectory(stdDir)) {
                Path targetStdDir = tmpDir.resolve("std");
                Files.createDirectories(targetStdDir);
                try (var stream = Files.list(stdDir)) {
                    stream.filter(p -> p.toString().endsWith(".lua"))
                          .forEach(p -> {
                              try {
                                  Files.copy(p, targetStdDir.resolve(p.getFileName()));
                              } catch (IOException ignored) {}
                          });
                }
            }

            ProcessBuilder pb = new ProcessBuilder("luajit", luaFile.toString());
            pb.directory(tmpDir.toFile());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String output = new String(p.getInputStream().readAllBytes()).trim();
            int exit = p.waitFor();

            // Cleanup
            try {
                Files.walk(tmpDir).sorted(Comparator.reverseOrder())
                    .forEach(f -> { try { Files.deleteIfExists(f); } catch (IOException ignored) {} });
            } catch (IOException ignored) {}

            return new ExecutionResult(output, exit);
        } catch (Exception e) {
            System.err.println("    Lua execution exception: " + e.getMessage());
            return null;
        }
    }

    private static String buildRuntimeOkRunner(String generatedLua) {
        return
            "package.path = './?.lua;./std/?.lua;' .. package.path\n" +
            "local __mod = (function()\n" +
            generatedLua + "\n" +
            "end)()\n" +
            "if type(__mod) == 'table' then\n" +
            "  for __k, __v in pairs(__mod) do\n" +
            "    if type(__v) == 'table' and __v.__kind == 'function' then\n" +
            "      __v.f()\n" +
            "    end\n" +
            "  end\n" +
            "end\n";
    }

    private static String buildXpcallRunner(String generatedLua) {
        return
            "package.path = './?.lua;./std/?.lua;' .. package.path\n" +
            "local __ok, __err = xpcall(function()\n" +
            "  local __mod = (function()\n" +
            generatedLua + "\n" +
            "  end)()\n" +
            "  if type(__mod) == 'table' then\n" +
            "    for __k, __v in pairs(__mod) do\n" +
            "      if type(__v) == 'table' and __v.__kind == 'function' then\n" +
            "        __v.f()\n" +
            "      end\n" +
            "    end\n" +
            "  end\n" +
            "end, function(err)\n" +
            "  if type(err) == 'table' and err.code ~= nil then\n" +
            "    print('DEAL_ERROR_CODE: ' .. err.code)\n" +
            "  else\n" +
            "    print('DEAL_ERROR_CODE: ' .. tostring(err))\n" +
            "  end\n" +
            "end)\n" +
            "if not __ok or __err then os.exit(1) end\n";
    }

    // =========================================================================
    // Module resolver
    // =========================================================================

    private static class BackendModuleResolver implements ModuleResolver {
        private final Map<String, Map<String, Type>> stdlibExports;

        BackendModuleResolver() {
            this.stdlibExports = buildStdlibExports();
        }

        @Override
        public Map<String, Type> resolveModule(String modulePath,
                String importingModule, Set<String> modulesInProgress)
                throws ModuleNotFoundException {
            if (stdlibExports.containsKey(modulePath)) {
                return stdlibExports.get(modulePath);
            }
            throw new ModuleNotFoundException("Module not found: " + modulePath);
        }

        @Override
        public Symbol.ClassSymbol resolveClassSymbol(String className,
                String modulePath, String importingModule)
                throws ModuleNotFoundException {
            return null;
        }

        private static Map<String, Map<String, Type>> buildStdlibExports() {
            Map<String, Map<String, Type>> map = new LinkedHashMap<>();

            map.put("std/console", module(
                fn("log", list(strType()), nullType()),
                fn("error", list(strType()), nullType())
            ));

            map.put("std/string", module(
                fn("length", list(strType()), intType()),
                fn("substring", list(strType(), intType(), intType()), strType()),
                fn("contains", list(strType(), strType()), boolType()),
                fn("startsWith", list(strType(), strType()), boolType()),
                fn("endsWith", list(strType(), strType()), boolType()),
                fn("replace", list(strType(), strType(), strType()), strType()),
                fn("split", list(strType(), strType()), arrayType(strType())),
                fn("trim", list(strType()), strType())
            ));

            map.put("std/table", module(
                fn("keys", list(tableType()), arrayType(strType()))
            ));

            map.put("std/json", module(
                fn("parse", list(strType()), tableType()),
                fn("stringify", list(tableType()), strType())
            ));

            map.put("std/math", module(
                fn("floor", list(numType()), numType()),
                fn("ceil", list(numType()), numType()),
                fn("sqrt", list(numType()), numType()),
                fn("absInt", list(intType()), intType()),
                fn("absNumber", list(numType()), numType()),
                fn("minInt", list(intType(), intType()), intType()),
                fn("maxInt", list(intType(), intType()), intType())
            ));

            map.put("std/time", module(
                fn("nowMillis", list(), intType())
            ));

            return map;
        }

        private static Type intType()    { return Type.Int.INSTANCE; }
        private static Type numType()    { return Type.Number.INSTANCE; }
        private static Type strType()    { return Type.String.INSTANCE; }
        private static Type boolType()   { return Type.Boolean.INSTANCE; }
        private static Type nullType()   { return Type.Null.INSTANCE; }
        private static Type tableType()  { return Type.Table.INSTANCE; }

        private static Type arrayType(Type elem) { return Types.array(elem); }
        private static Type fnType(List<Type> params, Type ret) { return Types.func(params, ret); }

        @SafeVarargs
        private static Map<String, Type> module(Map.Entry<String, Type>... entries) {
            Map<String, Type> m = new LinkedHashMap<>();
            for (var e : entries) m.put(e.getKey(), e.getValue());
            return m;
        }

        private static Map.Entry<String, Type> fn(String name, List<Type> params, Type ret) {
            return Map.entry(name, fnType(params, ret));
        }

        private static List<Type> list(Type... types) { return List.of(types); }
    }

    // =========================================================================
    // Minimal JSON parser
    // =========================================================================

    /**
     * Parses a fixture JSON file into a list of FixtureTest objects.
     * This is a minimal recursive-descent parser that handles the exact
     * schema required for backend fixtures.
     */
    private static List<FixtureTest> parseFixture(String json, String filename) {
        JsonValue root = new JsonParser(json).parseValue();
        if (!(root instanceof JsonObject obj)) {
            throw new IllegalArgumentException(filename + ": expected JSON object at root");
        }

        String version = obj.getString("version");
        if (version == null || !version.equals("1.0")) {
            throw new IllegalArgumentException(filename + ": missing or unsupported version: " + version);
        }

        JsonValue testsVal = obj.get("tests");
        if (!(testsVal instanceof JsonArray arr)) {
            throw new IllegalArgumentException(filename + ": missing or invalid 'tests' array");
        }

        List<FixtureTest> tests = new ArrayList<>();
        for (JsonValue testVal : arr.elements()) {
            if (!(testVal instanceof JsonObject testObj)) {
                throw new IllegalArgumentException(filename + ": test entry is not an object");
            }

            String name = testObj.getString("name");
            if (name == null) {
                throw new IllegalArgumentException(filename + ": test missing required 'name' field");
            }

            String description = testObj.getString("description");
            if (description == null) description = "";

            String source = testObj.getString("source");
            if (source == null) {
                throw new IllegalArgumentException(filename + "/" + name + ": missing required 'source' field");
            }

            String expectedOutput = testObj.getString("expectedOutput");
            String expectedError = testObj.getString("expectedError");

            Integer expectedExitCode = testObj.getInt("expectedExitCode");
            if (expectedExitCode == null) expectedExitCode = 0;

            JsonValue backendsVal = testObj.get("backends");
            if (!(backendsVal instanceof JsonArray backendsArr)) {
                throw new IllegalArgumentException(filename + "/" + name + ": missing or invalid 'backends' array");
            }

            List<String> backends = new ArrayList<>();
            for (JsonValue bv : backendsArr.elements()) {
                if (bv instanceof JsonString bs) {
                    backends.add(bs.value);
                }
            }

            tests.add(new FixtureTest(name, description, source,
                expectedOutput, expectedError, expectedExitCode, backends));
        }

        return tests;
    }

    // ---- JSON value types ----

    private interface JsonValue {}

    private record JsonString(String value) implements JsonValue {
        @Override public String toString() { return "\"" + value + "\""; }
    }

    private record JsonNumber(double value) implements JsonValue {
        int intValue() { return (int) value; }
    }

    private record JsonBoolean(boolean value) implements JsonValue {}

    private static final class JsonNull implements JsonValue {
        static final JsonNull INSTANCE = new JsonNull();
    }

    private static final class JsonArray implements JsonValue {
        private final List<JsonValue> elements;

        JsonArray(List<JsonValue> elements) { this.elements = elements; }
        List<JsonValue> elements() { return elements; }
    }

    private static final class JsonObject implements JsonValue {
        private final Map<String, JsonValue> members;

        JsonObject(Map<String, JsonValue> members) { this.members = members; }
        JsonValue get(String key) { return members.get(key); }

        String getString(String key) {
            JsonValue v = members.get(key);
            if (v instanceof JsonString s) return s.value;
            if (v instanceof JsonNull) return null;
            return null;
        }

        Integer getInt(String key) {
            JsonValue v = members.get(key);
            if (v instanceof JsonNumber n) return n.intValue();
            return null;
        }
    }

    // ---- Recursive-descent JSON parser ----

    private static final class JsonParser {
        private final String input;
        private int pos;

        JsonParser(String input) {
            this.input = input;
            this.pos = 0;
        }

        JsonValue parseValue() {
            skipWhitespace();
            if (pos >= input.length()) {
                throw new IllegalArgumentException("Unexpected end of JSON input");
            }
            char c = input.charAt(pos);
            return switch (c) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> parseString();
                case 't', 'f' -> parseBoolean();
                case 'n' -> parseNull();
                default -> {
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        yield parseNumber();
                    }
                    throw new IllegalArgumentException("Unexpected character at pos " + pos + ": " + c);
                }
            };
        }

        JsonObject parseObject() {
            expect('{');
            Map<String, JsonValue> members = new LinkedHashMap<>();
            skipWhitespace();
            if (input.charAt(pos) != '}') {
                while (true) {
                    skipWhitespace();
                    JsonString key = parseString();
                    skipWhitespace();
                    expect(':');
                    skipWhitespace();
                    JsonValue value = parseValue();
                    members.put(key.value, value);
                    skipWhitespace();
                    if (input.charAt(pos) == '}') break;
                    expect(',');
                }
            }
            expect('}');
            return new JsonObject(members);
        }

        JsonArray parseArray() {
            expect('[');
            List<JsonValue> elements = new ArrayList<>();
            skipWhitespace();
            if (input.charAt(pos) != ']') {
                while (true) {
                    skipWhitespace();
                    elements.add(parseValue());
                    skipWhitespace();
                    if (input.charAt(pos) == ']') break;
                    expect(',');
                }
            }
            expect(']');
            return new JsonArray(elements);
        }

        JsonString parseString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (pos < input.length()) {
                char c = input.charAt(pos);
                if (c == '"') {
                    pos++;
                    return new JsonString(sb.toString());
                }
                if (c == '\\') {
                    pos++;
                    if (pos >= input.length()) break;
                    char esc = input.charAt(pos);
                    switch (esc) {
                        case '"': sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/': sb.append('/'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        case 'u':
                            if (pos + 4 >= input.length()) break;
                            String hex = input.substring(pos + 1, pos + 5);
                            sb.append((char) Integer.parseInt(hex, 16));
                            pos += 4;
                            break;
                        default:
                            sb.append(esc);
                            break;
                    }
                } else {
                    sb.append(c);
                }
                pos++;
            }
            throw new IllegalArgumentException("Unterminated string");
        }

        JsonNumber parseNumber() {
            int start = pos;
            if (pos < input.length() && input.charAt(pos) == '-') pos++;
            while (pos < input.length() && input.charAt(pos) >= '0' && input.charAt(pos) <= '9') pos++;
            if (pos < input.length() && input.charAt(pos) == '.') {
                pos++;
                while (pos < input.length() && input.charAt(pos) >= '0' && input.charAt(pos) <= '9') pos++;
            }
            if (pos < input.length() && (input.charAt(pos) == 'e' || input.charAt(pos) == 'E')) {
                pos++;
                if (pos < input.length() && (input.charAt(pos) == '+' || input.charAt(pos) == '-')) pos++;
                while (pos < input.length() && input.charAt(pos) >= '0' && input.charAt(pos) <= '9') pos++;
            }
            double value = Double.parseDouble(input.substring(start, pos));
            return new JsonNumber(value);
        }

        JsonBoolean parseBoolean() {
            if (input.startsWith("true", pos)) {
                pos += 4;
                return new JsonBoolean(true);
            } else if (input.startsWith("false", pos)) {
                pos += 5;
                return new JsonBoolean(false);
            }
            throw new IllegalArgumentException("Expected true or false at pos " + pos);
        }

        JsonNull parseNull() {
            if (input.startsWith("null", pos)) {
                pos += 4;
                return JsonNull.INSTANCE;
            }
            throw new IllegalArgumentException("Expected null at pos " + pos);
        }

        void skipWhitespace() {
            while (pos < input.length()) {
                char c = input.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        void expect(char c) {
            if (pos >= input.length() || input.charAt(pos) != c) {
                throw new IllegalArgumentException("Expected '" + c + "' at pos " + pos +
                    ", got: " + (pos < input.length() ? "'" + input.charAt(pos) + "'" : "EOF"));
            }
            pos++;
        }
    }

    // =========================================================================
    // Summary
    // =========================================================================

    private static void printSummary() {
        System.out.println();
        System.out.println("=== Backend Conformance Summary ===");
        int total = passed + failed + skipped;
        System.out.println("Total: " + total + ", Passed: " + passed +
            ", Failed: " + failed + ", Skipped: " + skipped);
    }
}
