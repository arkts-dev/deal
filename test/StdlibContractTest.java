package deal.test;

import deal.ast.*;
import deal.lexer.*;
import deal.module.ExportExtractor;
import deal.module.StdlibModuleResolver;
import deal.parser.*;
import deal.types.Type;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Verifies that every declared export in the 6 spec-listed stdlib modules
 * has a matching .lua implementation, and that no .lua implementation
 * contains extra exports beyond what is declared.
 *
 * <p>Also verifies that each declared function accepts the declared number
 * of arguments by inspecting the Lua function wrapper's descriptor ({@code sig})
 * and comparing the parameter count against the {@code Type.Func} signature.
 *
 * <p>Non-spec modules (std/coroutine, std/io) are excluded from validation.
 */
public class StdlibContractTest {

    private static int passed = 0;
    private static int failed = 0;
    private static int skipped = 0;

    private static boolean luajitAvailable;

    private static void check(boolean condition, String message) {
        if (condition) { passed++; }
        else { failed++; System.err.println("FAIL: " + message); }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== Stdlib Contract Tests ===\n");
        System.out.println("Verifying stdlib declarations match implementations...\n");

        // Check LuaJIT availability
        try {
            new ProcessBuilder("luajit", "-v").start().waitFor();
            luajitAvailable = true;
        } catch (IOException e) {
            luajitAvailable = false;
        }

        if (!luajitAvailable) {
            System.out.println("WARNING: LuaJIT not available — runtime contract checks will be skipped");
        }

        Map<String, Map<String, Type>> stdlibExports = StdlibModuleResolver.stdlibExports();

        for (String modulePath : StdlibModuleResolver.SPEC_STDLIB_MODULES) {
            testModule(modulePath, stdlibExports.get(modulePath));
        }

        // Verify non-spec modules are excluded
        testNonSpecExcluded();

        System.out.println("\nPassed: " + passed + ", Failed: " + failed
            + ", Skipped: " + skipped);
        if (failed > 0) {
            System.exit(1);
        }
    }

    // =========================================================================
    // Per-module contract checks
    // =========================================================================

    private static void testModule(String modulePath,
                                    Map<String, Type> declaredExports) {
        System.out.println("-- " + modulePath + " --");

        // Test 1: .d.deal file exists and parses
        String declFile = modulePath + ".d.deal";
        Path declPath = Path.of(declFile);
        check(Files.exists(declPath),
            modulePath + ": declaration file exists: " + declFile);
        if (!Files.exists(declPath)) return;

        // Test 2: .lua implementation file exists
        String implFile = modulePath + ".lua";
        Path implPath = Path.of(implFile);
        check(Files.exists(implPath),
            modulePath + ": implementation file exists: " + implFile);

        if (!Files.exists(implPath)) {
            System.out.println("  WARNING: No .lua implementation for " + modulePath
                + " — skipping runtime checks");
            return;
        }

        if (declaredExports == null || declaredExports.isEmpty()) {
            check(false, modulePath + ": no declared exports found in .d.deal");
            return;
        }

        // Test 3: Load the .lua module and verify declared exports exist
        if (luajitAvailable) {
            Map<String, Integer> luaExports = loadLuaExports(modulePath);
            if (luaExports == null) {
                check(false, modulePath + ": failed to load .lua module");
                return;
            }

            // Verify every declared export exists in .lua
            for (Map.Entry<String, Type> entry : declaredExports.entrySet()) {
                String name = entry.getKey();
                Type type = entry.getValue();

                if (type instanceof Type.Func func) {
                    int declaredParamCount = func.paramTypes().size();

                    if (luaExports.containsKey(name)) {
                        int luaParamCount = luaExports.get(name);
                        check(true, modulePath + "." + name
                            + ": declared export found in .lua implementation");

                        // Verify parameter count matches
                        if (declaredParamCount == luaParamCount) {
                            check(true, modulePath + "." + name
                                + ": parameter count matches (" + declaredParamCount + ")");
                        } else {
                            check(false, modulePath + "." + name
                                + ": parameter count mismatch — "
                                + "declared " + declaredParamCount
                                + ", implementation expects " + luaParamCount);
                        }
                    } else {
                        check(false, modulePath + "." + name
                            + ": declared in .d.deal but NOT found in .lua implementation");
                    }
                } else {
                    // Non-function exports (classes, etc.)
                    if (luaExports.containsKey(name)) {
                        check(true, modulePath + "." + name
                            + ": declared export found in .lua implementation");
                    } else {
                        check(false, modulePath + "." + name
                            + ": declared in .d.deal but NOT found in .lua implementation");
                    }
                }
            }

            // Test 4: Verify no extra functions exist beyond declarations
            Set<String> declaredNames = declaredExports.keySet();
            for (String luaExport : luaExports.keySet()) {
                if (!luaExport.startsWith("_") && !declaredNames.contains(luaExport)) {
                    check(false, modulePath + "." + luaExport
                        + ": exists in .lua but NOT declared in .d.deal");
                }
            }
        } else {
            skipped += declaredExports.size();
        }
    }

    // =========================================================================
    // LuaJIT: load module and extract export names with parameter counts
    // =========================================================================

    /**
     * Loads a stdlib .lua module and returns a map of export name → parameter count.
     * Parameter count is extracted from the function wrapper's {@code sig} descriptor
     * (the parenthesized portion before {@code ->}).
     * Returns null if the module cannot be loaded.
     */
    private static Map<String, Integer> loadLuaExports(String modulePath) {
        String luaScript = buildLuaLoadExportsScript(modulePath);
        String output = runLuaScript(luaScript);

        if (output == null || output.trim().isEmpty()) {
            return null;
        }

        Map<String, Integer> exports = new LinkedHashMap<>();
        for (String line : output.trim().split("\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;

            if (line.startsWith("ERROR:")) {
                System.err.println("  Lua error: " + line);
                return null;
            }
            if (line.startsWith("EXPORT:")) {
                // Format: EXPORT:name:paramCount
                String rest = line.substring("EXPORT:".length()).trim();
                int lastColon = rest.lastIndexOf(':');
                if (lastColon >= 0) {
                    String name = rest.substring(0, lastColon);
                    String countStr = rest.substring(lastColon + 1);
                    try {
                        int count = Integer.parseInt(countStr);
                        if (!name.isEmpty()) {
                            exports.put(name, count);
                        }
                    } catch (NumberFormatException e) {
                        System.err.println("  Lua output parse error for line: " + line);
                    }
                }
            }
        }

        return exports;
    }

    /**
     * Builds a Lua script that loads a stdlib module and prints each exported
     * function name together with its expected parameter count.
     *
     * <p>Output format: {@code EXPORT:<name>:<paramCount>}
     *
     * <p>The parameter count is derived from the function wrapper's {@code sig}
     * field by parsing the parameter list between {@code (} and {@code )->}.
     * For example, {@code "(string,int)->int"} yields 2, {@code "()->int"} yields 0.
     * Rest parameters ({@code ...T[]}) are counted as one additional parameter.
     */
    private static String buildLuaLoadExportsScript(String modulePath) {
        String implFile = modulePath + ".lua";
        return
            "package.path = './?.lua;./?/init.lua;' .. package.path\n"
            + "local ok, mod = pcall(dofile, '" + implFile + "')\n"
            + "if not ok then\n"
            + "  print('ERROR: ' .. tostring(mod))\n"
            + "  os.exit(0)\n"
            + "end\n"
            + "if type(mod) ~= 'table' then\n"
            + "  print('ERROR: module is not a table, got ' .. type(mod))\n"
            + "  os.exit(0)\n"
            + "end\n"
            + "local function count_params(sig)\n"
            + "  if sig == nil then return -1 end\n"
            + "  -- Extract the parenthesized parameter list: (params)->ret\n"
            + "  local open_paren = sig:find('(', 1, true)\n"
            + "  if open_paren == nil then return -1 end\n"
            + "  local close_marker = sig:find(')->', open_paren, true)\n"
            + "  if close_marker == nil then return -1 end\n"
            + "  local param_str = sig:sub(open_paren + 1, close_marker - 1)\n"
            + "  if param_str == '' then return 0 end\n"
            + "  -- Count commas + 1, but handle '...' rest parameters as 1 param\n"
            + "  local count = 0\n"
            + "  local depth = 0\n"
            + "  local in_rest = false\n"
            + "  local i = 1\n"
            + "  while i <= #param_str do\n"
            + "    local c = param_str:sub(i, i)\n"
            + "    if c == ',' and depth == 0 and not in_rest then\n"
            + "      count = count + 1\n"
            + "    elseif c == '<' or c == '[' or c == '(' then\n"
            + "      depth = depth + 1\n"
            + "    elseif c == '>' or c == ']' or c == ')' then\n"
            + "      depth = depth - 1\n"
            + "    elseif i <= #param_str - 2 and param_str:sub(i, i+2) == '...' then\n"
            + "      in_rest = true\n"
            + "      i = i + 2\n"
            + "    end\n"
            + "    i = i + 1\n"
            + "  end\n"
            + "  count = count + 1\n"
            + "  return count\n"
            + "end\n"
            + "for k, v in pairs(mod) do\n"
            + "  if type(k) == 'string' then\n"
            + "    local exportable = false\n"
            + "    local sig = nil\n"
            + "    if type(v) == 'function' then\n"
            + "      exportable = true\n"
            + "    elseif type(v) == 'table' and v.__kind == 'function' then\n"
            + "      exportable = true\n"
            + "      sig = v.sig\n"
            + "    end\n"
            + "    if exportable then\n"
            + "      local pc = count_params(sig)\n"
            + "      print('EXPORT:' .. k .. ':' .. tostring(pc))\n"
            + "    end\n"
            + "  end\n"
            + "end\n";
    }

    // =========================================================================
    // Lua execution helper
    // =========================================================================

    private static String runLuaScript(String luaScript) {
        try {
            Path tmpDir = Files.createTempDirectory("deal_sct_");
            Path luaFile = tmpDir.resolve("check.lua");
            Files.writeString(luaFile, luaScript);

            ProcessBuilder pb = new ProcessBuilder("luajit", luaFile.toString());
            pb.directory(Path.of("").toAbsolutePath().toFile());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String output = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor();

            // Cleanup
            try {
                Files.walk(tmpDir).sorted(Comparator.reverseOrder())
                    .forEach(f -> {
                        try { Files.deleteIfExists(f); } catch (IOException ignored) {}
                    });
            } catch (IOException ignored) {}

            return output;
        } catch (Exception e) {
            System.err.println("    Lua execution exception: " + e.getMessage());
            return null;
        }
    }

    // =========================================================================
    // Non-spec module exclusion
    // =========================================================================

    private static void testNonSpecExcluded() {
        System.out.println("\n-- Non-spec module exclusion --");

        // std/coroutine and std/io exist on disk but should NOT be in spec list
        List<String> allDeclFiles = StdlibModuleResolver.discoverAllDeclFiles();

        for (String modulePath : allDeclFiles) {
            if (!StdlibModuleResolver.isSpecStdlibModule(modulePath)) {
                check(true, modulePath
                    + ": correctly excluded from spec stdlib list");

                // Verify it's not in the exports map
                Map<String, Map<String, Type>> exports = StdlibModuleResolver.stdlibExports();
                check(!exports.containsKey(modulePath),
                    modulePath + ": not present in stdlib exports map");
            }
        }

        // Verify the 6 spec modules ARE in the exports map
        for (String modulePath : StdlibModuleResolver.SPEC_STDLIB_MODULES) {
            Map<String, Map<String, Type>> exports = StdlibModuleResolver.stdlibExports();
            check(exports.containsKey(modulePath),
                modulePath + ": present in stdlib exports map");
        }
    }
}
