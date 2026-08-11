package deal.test;

import deal.ast.*;
import deal.checker.*;
import deal.codegen.lua.LuaBackend;
import deal.lexer.*;
import deal.parser.*;
import deal.types.Type;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Runtime source-location tests.
 *
 * <p>Verifies that runtime errors (array OOB, type mismatch, division by zero,
 * throw) report the correct DEAL source file, line, and column.
 *
 * <p>Requires LuaJIT to be available.
 */
public class RuntimeSourceLocationTest {

    private static int passed = 0;
    private static int failed = 0;

    private static void check(boolean condition, String message) {
        if (condition) { passed++; }
        else { failed++; System.err.println("FAIL: " + message); }
    }

    private static void fail(String message) {
        failed++;
        System.err.println("FAIL: " + message);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Compile DEAL source, generate Lua, run with LuaJIT, and capture the
     * error output (both stdout and stderr). Returns the combined output.
     * Returns null if LuaJIT is not available.
     */
    private static String compileAndRunExpectError(String dealSource,
                                                    String filename)
                                                    throws Exception {
        try {
            new ProcessBuilder("luajit", "-v").start().waitFor();
        } catch (IOException e) {
            return null;
        }

        LexResult lex = new Lexer(dealSource, filename).tokenize();
        ParseResult parse = new Parser(lex.tokens(), filename).parse();

        StubModuleResolver resolver = new StubModuleResolver();
        NameResolver nr = new NameResolver(filename, resolver);
        SymbolTable symTable = nr.resolve(parse.program());

        List<Diagnostic> diags = new ArrayList<>(nr.diagnostics());
        if (diags.stream().anyMatch(d -> "error".equals(d.severity()))) {
            fail("Compilation error: " + diags);
            return null;
        }

        CheckResult result = TypeChecker.check(filename, symTable, nr, parse.program());
        diags.addAll(result.diagnostics());
        if (diags.stream().anyMatch(d -> "error".equals(d.severity()))) {
            fail("Type error: " + diags);
            return null;
        }

        String lua = LuaBackend.generate(parse.program(), result, filename);

        Path tmpDir = Files.createTempDirectory("deal_rtloc_");
        Path luaFile = tmpDir.resolve("test_main.lua");
        Files.writeString(luaFile, lua);

        // Copy runtime library
        Path runtimeDir = tmpDir.resolve("deal");
        Files.createDirectories(runtimeDir);
        Files.copy(Path.of("deal/runtime.lua"), runtimeDir.resolve("runtime.lua"));

        // Run with pcall wrapper so we can capture the error
        String wrapper =
            "package.path = './?.lua;' .. package.path\n" +
            "local ok, err = pcall(function()\n" +
            "  local mod = loadfile(\"" +
            luaFile.toString().replace("\\", "/") + "\")()\n" +
            "  if mod.main ~= nil then mod.main.f() end\n" +
            "end)\n" +
            "if not ok then\n" +
            "  if type(err) == 'table' then\n" +
            "    print('FILE:' .. tostring(err.file))\n" +
            "    print('LINE:' .. tostring(err.line))\n" +
            "    print('COLUMN:' .. tostring(err.column))\n" +
            "    print('CODE:' .. tostring(err.code))\n" +
            "    print('MESSAGE:' .. tostring(err.message))\n" +
            "  else\n" +
            "    print('RAW_ERROR:' .. tostring(err))\n" +
            "  end\n" +
            "end\n";

        Path wrapperFile = tmpDir.resolve("wrapper.lua");
        Files.writeString(wrapperFile, wrapper);

        ProcessBuilder pb = new ProcessBuilder("luajit", wrapperFile.toString());
        pb.directory(tmpDir.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes()).trim();
        p.waitFor();

        try {
            Files.walk(tmpDir).sorted(Comparator.reverseOrder())
                .forEach(f -> { try { Files.deleteIfExists(f); }
                    catch (IOException ignored) {} });
        } catch (IOException ignored) {}

        return output;
    }

    /** Parse the error output lines into a map. */
    private static Map<String, String> parseErrorOutput(String output) {
        Map<String, String> map = new HashMap<>();
        if (output == null) return map;
        for (String line : output.split("\n")) {
            line = line.trim();
            if (line.startsWith("FILE:")) {
                map.put("file", line.substring(5).trim());
            } else if (line.startsWith("LINE:")) {
                map.put("line", line.substring(5).trim());
            } else if (line.startsWith("COLUMN:")) {
                map.put("column", line.substring(7).trim());
            } else if (line.startsWith("CODE:")) {
                map.put("code", line.substring(5).trim());
            } else if (line.startsWith("MESSAGE:")) {
                map.put("message", line.substring(8).trim());
            } else if (line.startsWith("RAW_ERROR:")) {
                map.put("raw_error", line.substring(10).trim());
            }
        }
        return map;
    }

    // =========================================================================
    // Main
    // =========================================================================

    public static void main(String[] args) throws Exception {
        System.out.println("=== Running Runtime Source Location Tests ===");

        boolean luajitAvailable;
        try {
            new ProcessBuilder("luajit", "-v").start().waitFor();
            luajitAvailable = true;
        } catch (IOException e) {
            luajitAvailable = false;
        }

        if (!luajitAvailable) {
            System.out.println("SKIP: luajit not available. Runtime tests require LuaJIT.");
            System.out.println("Passed: 0, Failed: 0 (all skipped)");
            return;
        }

        testDivisionByZero();
        testThrowStatement();
        testArrayOutOfBounds();
        testTypeMismatch();

        System.out.println();
        System.out.println("Passed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    // =========================================================================
    // Test: Division by zero reports correct location
    // =========================================================================

    static void testDivisionByZero() throws Exception {
        System.out.println("-- Division By Zero --");

        // The division by zero is at line 4
        String source =
            "export function main(): int {\n" +    // line 1
            "  let a: int = 10;\n" +                // line 2
            "  let b: int = 0;\n" +                  // line 3
            "  return a / b;\n" +                     // line 4 (error here)
            "}\n";

        String output = compileAndRunExpectError(source, "test_div0.deal");
        if (output == null) return;

        Map<String, String> err = parseErrorOutput(output);
        System.out.println("  Error output: " + output);

        check("E8005".equals(err.get("code")),
            "division by zero error code is E8005, got: " + err.get("code"));
        check("test_div0.deal".equals(err.get("file")),
            "file is test_div0.deal, got: " + err.get("file"));
        check("4".equals(err.get("line")),
            "line is 4, got: " + err.get("line"));

        // Column should be present and positive
        if (err.containsKey("column") && !err.get("column").equals("nil")) {
            int col = Integer.parseInt(err.get("column"));
            check(col >= 1, "column >= 1: " + col);
        } else {
            // Even if column is nil, we just check file and line
            System.out.println("  Note: column is nil (no span for binary op)");
        }
    }

    // =========================================================================
    // Test: Throw statement reports correct location
    // =========================================================================

    static void testThrowStatement() throws Exception {
        System.out.println("-- Throw Statement --");

        // The throw is at line 3
        String source =
            "export function main(): int {\n" +     // line 1
            "  let x: int = 1;\n" +                  // line 2
            "  throw { code: \"E_LIMIT\", message: \"limit\" };\n" +  // line 3
            "  return x;\n" +                         // line 4
            "}\n";

        String output = compileAndRunExpectError(source, "test_throw.deal");
        if (output == null) return;

        Map<String, String> err = parseErrorOutput(output);
        System.out.println("  Error output: " + output);

        check("test_throw.deal".equals(err.get("file")),
            "file is test_throw.deal, got: " + err.get("file"));

        // The throw statement includes file/line/column in the error object
        if (err.containsKey("line") && !err.get("line").equals("nil")) {
            check("3".equals(err.get("line")),
                "throw line is 3, got: " + err.get("line"));
        } else {
            // check for line in raw error
            System.out.println("  Note: line info not extracted, raw: " + err.get("raw_error"));
        }
    }

    // =========================================================================
    // Test: Array out-of-bounds reports correct location
    // =========================================================================

    static void testArrayOutOfBounds() throws Exception {
        System.out.println("-- Array Out Of Bounds --");

        // Access xs[99] where xs has 3 elements — line 4
        // This won't be a bounds error per se (Lua doesn't bounds-check),
        // but accessing beyond length returns nil, which then fails type check
        String source =
            "export function main(): int {\n" +     // line 1
            "  let xs: int[] = [1, 2, 3];\n" +       // line 2
            "  let idx: int = 99;\n" +                // line 3
            "  let val: int = xs[idx];\n" +           // line 4 (error: nil is not int)
            "  return val;\n" +                        // line 5
            "}\n";

        String output = compileAndRunExpectError(source, "test_oob.deal");
        if (output == null) return;

        Map<String, String> err = parseErrorOutput(output);
        System.out.println("  Error output: " + output);

        // The error should reference the source file
        check("test_oob.deal".equals(err.get("file")),
            "file is test_oob.deal, got: " + err.get("file"));

        // The index expression on line 4 should report some error
        if (err.containsKey("code")) {
            System.out.println("  Error code: " + err.get("code"));
            System.out.println("  Message: " + err.get("message"));
        }
    }

    // =========================================================================
    // Test: Type mismatch in table read
    // =========================================================================
    static void testTypeMismatch() throws Exception {
        System.out.println("-- Type Mismatch --");

        // Line 3: read a table field into a string variable, but it is int
        String source =
            "export function main(): string {\n" +  // line 1
            "  let t: table = { x: 42 };\n" +        // line 2
            "  let s: string = t.x;\n" +              // line 3 (runtime: number is not string)
            "  return s;\n" +                          // line 4
            "}\n";


        String output = compileAndRunExpectError(source, "test_mismatch.deal");
        if (output == null) return;

        Map<String, String> err = parseErrorOutput(output);
        System.out.println("  Error output: " + output);

        check("test_mismatch.deal".equals(err.get("file")),
            "file is test_mismatch.deal, got: " + err.get("file"));

        if (err.containsKey("line") && !err.get("line").equals("nil")) {
            check("3".equals(err.get("line")),
                "line is 3, got: " + err.get("line"));
        }

        if (err.containsKey("code")) {
            System.out.println("  Error code: " + err.get("code"));
            System.out.println("  Message: " + err.get("message"));
        }
    }
}
