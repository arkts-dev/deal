package deal.test;

import deal.ast.*;
import deal.checker.*;
import deal.codegen.lua.LuaBackend;
import deal.ir.IrDumper;
import deal.lexer.*;
import deal.parser.*;
import deal.types.Type;
import deal.types.Types;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Loads backend-neutral JSON fixture tests from
 * {@code test/conformance/fixtures/} and executes them against the
 * LuaJIT backend.
 *
 * <p>Fixture format (per {@code conformance-test-architecture} D3):
 * <pre>
 * {
 *   "version": "1.0",
 *   "tests": [{
 *     "name": "unique-test-name",
 *     "description": "what this test verifies",
 *     "source": "DEAL source code as string",
 *     "expectedOutput": "string that stdout must contain" | null,
 *     "expectedError": "E8001" | null,
 *     "expectedExitCode": 0 | 1,
 *     "irContains": ["substrings that the IR dump must contain"],
 *     "irNotContains": ["substrings that the IR dump must NOT contain"],
 *     "backends": ["luajit", "jvm"]
 *   }]
 * }
 * </pre>
 *
 * <p>For IR-only tests, the runtime fields are {@code null} and the
 * runner only validates the IR dump assertions ({@code irContains}
 * and {@code irNotContains}).
 */
public class BackendConformanceTest {

    private static int passed = 0;
    private static int failed = 0;
    private static int skipped = 0;
    private static boolean luajitAvailable;

    // Sentinel for JSON null (distinct from Java null)
    private static final Object JSON_NULL = new Object() {
        @Override public String toString() { return "null"; }
    };

    /** Holds the result of executing a Lua program. */
    private record ExecutionResult(String output, int exitCode) {}

    public static void main(String[] args) throws Exception {
        try {
            new ProcessBuilder("luajit", "-v").start().waitFor();
            luajitAvailable = true;
        } catch (IOException e) {
            luajitAvailable = false;
        }

        System.out.println("=== Backend Conformance Test ===");
        System.out.println("LuaJIT: " + (luajitAvailable ? "available" :
            "NOT available (runtime tests will be skipped)"));
        System.out.println();

        Path fixturesDir = Path.of("test/conformance/fixtures/");
        if (!Files.isDirectory(fixturesDir)) {
            System.out.println("No fixtures directory found at " + fixturesDir);
            System.exit(failed > 0 ? 1 : 0);
        }

        try (var stream = Files.list(fixturesDir)) {
            stream.filter(p -> p.toString().endsWith(".json"))
                  .sorted()
                  .forEach(BackendConformanceTest::runFixtureFile);
        }

        System.out.println();
        System.out.println("=== Backend Conformance Summary ===");
        int total = passed + failed + skipped;
        System.out.println("Total: " + total + ", Passed: " + passed +
            ", Failed: " + failed + ", Skipped: " + skipped);

        if (failed > 0) {
            System.exit(1);
        }
    }

    // =========================================================================
    // Fixture file runner
    // =========================================================================

    @SuppressWarnings("unchecked")
    private static void runFixtureFile(Path file) {
        System.out.println("--- Fixture: " + file.getFileName() + " ---");

        try {
            String raw = Files.readString(file);
            Map<String, Object> root = (Map<String, Object>) parseJson(raw);

            Object version = root.get("version");
            if (!"1.0".equals(String.valueOf(version))) {
                System.out.println("  FAIL: unsupported version: " + version);
                failed++;
                return;
            }

            List<Map<String, Object>> tests =
                (List<Map<String, Object>>) root.get("tests");
            if (tests == null) {
                System.out.println("  FAIL: no 'tests' array in fixture");
                failed++;
                return;
            }

            for (Map<String, Object> test : tests) {
                runTestCase(file.getFileName().toString(), test);
            }
        } catch (Exception e) {
            System.out.println("  FAIL: error processing fixture: " + e.getMessage());
            e.printStackTrace(System.out);
            failed++;
        }
    }

    @SuppressWarnings("unchecked")
    private static void runTestCase(String fixtureName, Map<String, Object> test) {
        String name = jsonString(test, "name", "<unnamed>");
        String description = jsonString(test, "description", "");
        String source = jsonString(test, "source", null);
        List<String> backends = (List<String>) test.getOrDefault("backends", List.of());

        if (source == null) {
            System.out.println("  [" + name + "] FAIL: missing 'source' field");
            failed++;
            return;
        }

        boolean appliesToLuajit = backends.contains("luajit");

        try {
            // Compile and dump IR — always do this for IR assertions
            var cr = compileForIR(source, "fixture-" + name + ".deal");
            String ir = IrDumper.dump(cr.program(), cr.checkResult(), "fixture-" + name);

            // Check IR assertions
            List<String> irContains = (List<String>) test.getOrDefault("irContains", List.of());
            List<String> irNotContains = (List<String>) test.getOrDefault("irNotContains", List.of());

            boolean irOk = true;
            for (String needle : irContains) {
                if (!ir.contains(needle)) {
                    System.out.println("  [" + name + "] FAIL: IR should contain '" + needle + "'");
                    System.out.println("    IR:\n" + ir);
                    irOk = false;
                }
            }
            for (String needle : irNotContains) {
                if (ir.contains(needle)) {
                    System.out.println("  [" + name + "] FAIL: IR should NOT contain '" + needle + "'");
                    System.out.println("    IR:\n" + ir);
                    irOk = false;
                }
            }

            if (!irOk) {
                failed++;
                return;
            }

            // If runtime test + luajit available + applies to luajit
            Object expectedOutput = test.get("expectedOutput");
            Object expectedError = test.get("expectedError");
            Object expectedExitCode = test.get("expectedExitCode");

            boolean hasRuntimeAssertions = (expectedOutput != null && expectedOutput != JSON_NULL)
                || (expectedError != null && expectedError != JSON_NULL)
                || (expectedExitCode != null && expectedExitCode != JSON_NULL);

            if (hasRuntimeAssertions) {
                if (!appliesToLuajit) {
                    System.out.println("  [" + name + "] SKIP (runtime test, not for luajit)");
                    skipped++;
                    return;
                }
                if (!luajitAvailable) {
                    System.out.println("  [" + name + "] SKIP (LuaJIT not available)");
                    skipped++;
                    return;
                }

                String lua = generateLua(source, "fixture-" + name + ".deal");
                if (lua == null) {
                    System.out.println("  [" + name + "] FAIL: codegen failed");
                    failed++;
                    return;
                }

                boolean isErrorTest = (expectedError != null && expectedError != JSON_NULL);
                ExecutionResult execResult = executeLua(lua, isErrorTest);
                if (execResult == null) {
                    System.out.println("  [" + name + "] FAIL: Lua execution returned null");
                    failed++;
                    return;
                }
                String output = execResult.output();
                int actualExitCode = execResult.exitCode();

                if (expectedOutput != null && expectedOutput != JSON_NULL) {
                    String expStr = String.valueOf(expectedOutput);
                    if (!output.contains(expStr)) {
                        System.out.println("  [" + name + "] FAIL: expected output '" +
                            expStr + "', got: " + output);
                        failed++;
                        return;
                    }
                }

                if (expectedError != null && expectedError != JSON_NULL) {
                    String expErr = String.valueOf(expectedError);
                    if (!output.contains("DEAL_ERROR_CODE: " + expErr)) {
                        System.out.println("  [" + name + "] FAIL: expected error '" +
                            expErr + "', got: " + output);
                        failed++;
                        return;
                    }
                }

                // Assert expected exit code
                if (expectedExitCode != null && expectedExitCode != JSON_NULL) {
                    int expCode = ((Number) expectedExitCode).intValue();
                    if (actualExitCode != expCode) {
                        System.out.println("  [" + name + "] FAIL: expected exit code " +
                            expCode + ", got " + actualExitCode);
                        System.out.println("    Output: " + output);
                        failed++;
                        return;
                    }
                }
            }

            System.out.println("  [" + name + "] OK" +
                (description.isEmpty() ? "" : " — " + description));
            passed++;

        } catch (Exception e) {
            System.out.println("  [" + name + "] FAIL: " + e.getMessage());
            e.printStackTrace(System.out);
            failed++;
        }
    }

    // =========================================================================
    // Compilation helpers
    // =========================================================================

    private record IRCompileResult(ProgramNode program, CheckResult checkResult,
                                    SymbolTable symbolTable) {}

    private static IRCompileResult compileForIR(String source, String filename) {
        LexResult lex = new Lexer(source, filename).tokenize();
        if (lex.diagnostics().stream().anyMatch(d -> "error".equals(d.severity()))) {
            throw new RuntimeException("Lex error: " + lex.diagnostics());
        }
        Parser parser = new Parser(lex.tokens(), filename);
        ParseResult parseResult = parser.parse();
        if (parseResult.hasErrors()) {
            throw new RuntimeException("Parse error: " + parseResult.diagnostics());
        }
        ProgramNode program = parseResult.program();

        StubModuleResolver resolver = new StubModuleResolver();
        NameResolver nr = new NameResolver(filename, resolver);
        SymbolTable symTable = nr.resolve(program);
        CheckResult result = TypeChecker.check(filename, symTable, nr, program);

        return new IRCompileResult(program, result, symTable);
    }

    private static String generateLua(String source, String filename) {
        try {
            LexResult lex = new Lexer(source, filename).tokenize();
            if (lex.hasErrors()) return null;

            Parser parser = new Parser(lex.tokens(), filename);
            ParseResult parseResult = parser.parse();
            if (parseResult.hasErrors()) return null;

            StubModuleResolver resolver = new StubModuleResolver();
            NameResolver nr = new NameResolver(filename, resolver);
            SymbolTable symTable = nr.resolve(parseResult.program());
            CheckResult result = TypeChecker.check(filename, symTable, nr, parseResult.program());
            if (result.hasErrors()) return null;

            return LuaBackend.generate(parseResult.program(), result, filename);
        } catch (Exception e) {
            return null;
        }
    }

    // =========================================================================
    // Lua execution
    // =========================================================================

    private static ExecutionResult executeLua(String luaSource, boolean isXpcallWrapped) {
        String runner;
        if (isXpcallWrapped) {
            runner = buildXpcallRunner(luaSource);
        } else {
            runner = buildRuntimeOkRunner(luaSource);
        }
        try {
            Path tmpDir = Files.createTempDirectory("deal_backend_conf_");
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
            int exitCode = p.waitFor();

            // Cleanup
            try {
                Files.walk(tmpDir).sorted(Comparator.reverseOrder())
                    .forEach(f -> { try { Files.deleteIfExists(f); } catch (IOException ignored) {} });
            } catch (IOException ignored) {}

            return new ExecutionResult(output, exitCode);
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
            "if not __ok then os.exit(1) end\n";
    }

    // =========================================================================
    // Minimal JSON parser — no external dependencies
    // =========================================================================

    /**
     * Parses a JSON string into a Java object tree (Map, List, String,
     * Number, Boolean, JSON_NULL).
     */
    private static Object parseJson(String s) {
        int[] pos = new int[]{0};
        skipWhitespace(s, pos);
        return parseValue(s, pos);
    }

    private static void skipWhitespace(String s, int[] pos) {
        while (pos[0] < s.length() && Character.isWhitespace(s.charAt(pos[0]))) {
            pos[0]++;
        }
    }

    private static Object parseValue(String s, int[] pos) {
        skipWhitespace(s, pos);
        if (pos[0] >= s.length()) return JSON_NULL;

        char c = s.charAt(pos[0]);
        return switch (c) {
            case '"' -> parseString(s, pos);
            case '{' -> parseObject(s, pos);
            case '[' -> parseArray(s, pos);
            case 'n' -> { pos[0] += 4; yield JSON_NULL; }
            case 't' -> { pos[0] += 4; yield true; }
            case 'f' -> { pos[0] += 5; yield false; }
            default -> {
                if (c == '-' || Character.isDigit(c)) {
                    yield parseNumber(s, pos);
                }
                throw new RuntimeException("Unexpected char '" + c + "' at " + pos[0]);
            }
        };
    }

    private static String parseString(String s, int[] pos) {
        pos[0]++; // skip opening quote
        StringBuilder sb = new StringBuilder();
        while (pos[0] < s.length()) {
            char c = s.charAt(pos[0]);
            if (c == '"') {
                pos[0]++;
                return sb.toString();
            }
            if (c == '\\') {
                pos[0]++;
                if (pos[0] < s.length()) {
                    char ec = s.charAt(pos[0]);
                    sb.append(switch (ec) {
                        case 'n' -> '\n'; case 't' -> '\t'; case 'r' -> '\r';
                        case '"' -> '"'; case '\\' -> '\\'; case '/' -> '/';
                        default -> ec;
                    });
                }
            } else {
                sb.append(c);
            }
            pos[0]++;
        }
        return sb.toString();
    }

    private static Map<String, Object> parseObject(String s, int[] pos) {
        Map<String, Object> map = new LinkedHashMap<>();
        pos[0]++; // skip '{'
        skipWhitespace(s, pos);
        if (pos[0] < s.length() && s.charAt(pos[0]) == '}') {
            pos[0]++;
            return map;
        }
        while (pos[0] < s.length()) {
            skipWhitespace(s, pos);
            if (pos[0] >= s.length()) break;
            if (s.charAt(pos[0]) == '}') { pos[0]++; break; }
            if (s.charAt(pos[0]) == ',') { pos[0]++; continue; }

            String key = parseString(s, pos);
            skipWhitespace(s, pos);
            if (pos[0] < s.length() && s.charAt(pos[0]) == ':') pos[0]++;
            Object value = parseValue(s, pos);
            map.put(key, value);
        }
        return map;
    }

    private static List<Object> parseArray(String s, int[] pos) {
        List<Object> list = new ArrayList<>();
        pos[0]++; // skip '['
        skipWhitespace(s, pos);
        if (pos[0] < s.length() && s.charAt(pos[0]) == ']') {
            pos[0]++;
            return list;
        }
        while (pos[0] < s.length()) {
            skipWhitespace(s, pos);
            if (pos[0] >= s.length()) break;
            if (s.charAt(pos[0]) == ']') { pos[0]++; break; }
            if (s.charAt(pos[0]) == ',') { pos[0]++; continue; }

            Object value = parseValue(s, pos);
            list.add(value);
        }
        return list;
    }

    private static Number parseNumber(String s, int[] pos) {
        StringBuilder sb = new StringBuilder();
        while (pos[0] < s.length()) {
            char c = s.charAt(pos[0]);
            if (Character.isDigit(c) || c == '.' || c == '-' || c == 'e'
                || c == 'E' || c == '+') {
                sb.append(c);
                pos[0]++;
            } else {
                break;
            }
        }
        String numStr = sb.toString();
        if (numStr.contains(".") || numStr.contains("e") || numStr.contains("E")) {
            return Double.parseDouble(numStr);
        }
        return Long.parseLong(numStr);
    }

    private static String jsonString(Map<String, Object> map, String key, String defaultValue) {
        Object v = map.get(key);
        if (v == null || v == JSON_NULL) return defaultValue;
        return String.valueOf(v);
    }

    // =========================================================================
    // Stub module resolver
    // =========================================================================

    static class StubModuleResolver implements ModuleResolver {
        private static final Map<String, Map<String, Type>> STDLIB;

        static {
            STDLIB = new HashMap<>();
            STDLIB.put("std/console", module(
                fn("log", list(strType()), nullType()),
                fn("error", list(strType()), nullType())
            ));
            STDLIB.put("std/string", module(
                fn("length", list(strType()), intType()),
                fn("substring", list(strType(), intType(), intType()), strType()),
                fn("contains", list(strType(), strType()), boolType()),
                fn("startsWith", list(strType(), strType()), boolType()),
                fn("endsWith", list(strType(), strType()), boolType()),
                fn("replace", list(strType(), strType(), strType()), strType()),
                fn("split", list(strType(), strType()), arrayType(strType())),
                fn("trim", list(strType()), strType())
            ));
            STDLIB.put("std/table", module(
                fn("keys", list(tableType()), arrayType(strType()))
            ));
            STDLIB.put("std/json", module(
                fn("parse", list(strType()), tableType()),
                fn("stringify", list(tableType()), strType())
            ));
            STDLIB.put("std/math", module(
                fn("floor", list(numType()), numType()),
                fn("ceil", list(numType()), numType()),
                fn("sqrt", list(numType()), numType()),
                fn("absInt", list(intType()), intType()),
                fn("absNumber", list(numType()), numType()),
                fn("minInt", list(intType(), intType()), intType()),
                fn("maxInt", list(intType(), intType()), intType())
            ));
            STDLIB.put("std/time", module(
                fn("nowMillis", list(), intType())
            ));
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

        @Override
        public Map<String, Type> resolveModule(String modulePath, String importingModule,
                                                Set<String> modulesInProgress)
                throws ModuleNotFoundException {
            Map<String, Type> exports = STDLIB.get(modulePath);
            if (exports != null) return exports;
            return Map.of();
        }

        @Override
        public Symbol.ClassSymbol resolveClassSymbol(String className, String modulePath,
                                                      String importingModule)
                throws ModuleNotFoundException {
            return null;
        }
    }
}
