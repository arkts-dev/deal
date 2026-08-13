package deal.test;

import deal.ast.ProgramNode;
import deal.parser.ParseResult;
import deal.checker.CheckResult;
import deal.checker.NameResolver;
import deal.checker.SymbolTable;
import deal.checker.TypeChecker;
import deal.codegen.lua.LuaBackend;
import deal.lexer.LexResult;
import deal.lexer.Lexer;
import deal.parser.Parser;

import org.junit.Assume;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * JUnit4 + Hamcrest backend tests for the {@link LuaAbi} emission layer:
 * compiles DEAL programs through the full pipeline and pins the emitted
 * Lua shapes of every rerouted emission site, then executes the generated
 * Lua with LuaJIT to assert runtime behavior.
 */
public class LuaAbiBackendTest {

    private record CompileResult(String lua, ProgramNode program, CheckResult result) {}

    private record RunResult(int exit, String output) {}

    private static boolean luajitAvailable() {
        try {
            new ProcessBuilder("luajit", "-v").start().waitFor();
            return true;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void assumeLuajit() {
        Assume.assumeTrue("luajit not available", luajitAvailable());
    }

    // =========================================================================
    // Compile / run helpers
    // =========================================================================

    private static CompileResult compile(String source) {
        return compile(source, "test.deal");
    }

    private static CompileResult compile(String source, String filename) {
        LexResult lex = new Lexer(source, filename).tokenize();
        if (lex.hasErrors()) {
            fail("lex errors: " + lex.diagnostics());
        }
        ParseResult parse = new Parser(lex.tokens(), filename).parse();
        if (parse.hasErrors()) {
            fail("parse errors: " + parse.diagnostics());
        }
        StubModuleResolver resolver = new StubModuleResolver();
        NameResolver nr = new NameResolver(filename, resolver);
        SymbolTable symTable = nr.resolve(parse.program());
        if (nr.diagnostics().stream().anyMatch(
                d -> "error".equals(d.severity()))) {
            fail("name-resolution errors: " + nr.diagnostics());
        }
        CheckResult result = TypeChecker.check(filename, symTable, nr,
            parse.program());
        if (result.hasErrors()) {
            fail("type errors: " + result.diagnostics());
        }
        String lua = LuaBackend.generate(parse.program(), result, filename);
        assertNotNull(lua);
        return new CompileResult(lua, parse.program(), result);
    }

    /**
     * Runs generated Lua with the runtime and stdlib available, with an
     * optional probe appended after module load (the module exports are
     * visible as {@code __mod}).
     */
    private static RunResult runLua(String lua, String probe) throws Exception {
        String runner =
            "package.path = './?.lua;./std/?.lua;' .. package.path\n" +
            "local __mod = (function()\n" +
            lua + "\n" +
            "end)()\n" +
            (probe == null ? "" : probe) + "\n";

        Path tmpDir = Files.createTempDirectory("deal_abi_");
        try {
            Path luaFile = tmpDir.resolve("test_main.lua");
            Files.writeString(luaFile, runner);

            Path runtimeDir = tmpDir.resolve("deal");
            Files.createDirectories(runtimeDir);
            Files.copy(Path.of("deal/runtime.lua"), runtimeDir.resolve("runtime.lua"));

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
            return new RunResult(exit, output);
        } finally {
            try {
                Files.walk(tmpDir).sorted(Comparator.reverseOrder())
                    .forEach(f -> {
                        try { Files.deleteIfExists(f); } catch (IOException ignored) {}
                    });
            } catch (IOException ignored) {}
        }
    }

    /**
     * Auto-invokes every exported function wrapper whose export key does not
     * contain {@code $} (the conformance runner's dispatch rule).
     */
    private static String autoInvokeProbe() {
        return
            "if type(__mod) == 'table' then\n" +
            "  for __k, __v in pairs(__mod) do\n" +
            "    if type(__v) == 'table' and __v.__kind == 'function' then\n" +
            "      if not string.find(__k, \"$\", 1, true) then\n" +
            "        __v.f()\n" +
            "      end\n" +
            "    end\n" +
            "  end\n" +
            "end\n";
    }

    /** Asserts every {@code $} in the generated Lua sits inside a quoted string key. */
    private static void assertDollarOnlyInQuotedKeys(String lua) {
        for (int i = lua.indexOf('$'); i >= 0; i = lua.indexOf('$', i + 1)) {
            int open = lua.lastIndexOf('"', i);
            int close = lua.indexOf('"', i + 1);
            assertTrue("$ outside a quoted string key at index " + i
                + " in: " + lua, open >= 0 && close >= 0 && open < i && i < close);
        }
    }

    // =========================================================================
    // RC-3: Lua reserved-word table fields
    // =========================================================================

    @Test
    public void reservedWordTableFieldsEmitBracketKeys() throws Exception {
        String source =
            "export function test_lua_reserved_word_table_fields(): int {\n" +
            "  let t: table = { end: 4, local: 5 };\n" +
            "  let a: int = t.end;\n" +
            "  let b: int = t.local;\n" +
            "  if (a + b !== 9) { throw { code: \"TEST_FAIL\", message: \"reserved word table fields failed\" }; }\n" +
            "  return a + b;\n" +
            "}\n";
        CompileResult out = compile(source);

        assertThat(out.lua(), containsString("[\"end\"] = 4"));
        assertThat(out.lua(), containsString("[\"local\"] = 5"));
        assertThat(out.lua(), containsString("t[\"end\"]"));
        assertThat(out.lua(), containsString("t[\"local\"]"));
        assertThat(out.lua(), not(containsString("t.end")));
        assertThat(out.lua(), not(containsString("{end =")));
        assertThat(out.lua(), not(containsString("{local =")));

        assumeLuajit();
        RunResult run = runLua(out.lua(),
            "print(__mod.test_lua_reserved_word_table_fields.f())");
        assertEquals("luajit exit 0, got: " + run.output(), 0, run.exit());
        assertThat(run.output(), containsString("9"));
    }

    // =========================================================================
    // RC-2: @jsonable helper-name near collision
    // =========================================================================

    @Test
    public void jsonableHelperNameNearCollisionUsesNamespace() throws Exception {
        String source =
            "// @jsonable\n" +
            "export class User {\n" +
            "  name: string = \"\";\n" +
            "}\n" +
            "\n" +
            "export function test_jsonable_helper_name_near_collision(): null {\n" +
            "  let User_fromJson: string = \"local\";\n" +
            "  let u: User | null = User$fromJson(\"{\\\"name\\\":\\\"Ada\\\"}\");\n" +
            "  if (u !== null) {\n" +
            "    if (User_fromJson !== \"local\") { throw { code: \"TEST_FAIL\", message: \"helper name collision\" }; }\n" +
            "    return null;\n" +
            "  }\n" +
            "  throw { code: \"TEST_FAIL\", message: \"fromJson failed\" };\n" +
            "}\n";
        CompileResult out = compile(source);

        assertThat(out.lua(), containsString("__deal[\"User$fromJson\"] = __rt.function_("));
        assertThat(out.lua(), containsString("__deal[\"User$fromJson\"].f("));
        assertThat(out.lua(), containsString("exports[\"User$fromJson\"] = __deal[\"User$fromJson\"]"));
        assertThat(out.lua(), containsString("__deal[\"User_fields\"] = {"));
        assertThat(out.lua(), containsString("__deal[\"User_meta\"] = __rt.export_class(\"User\")"));
        // The retired underscore-form helper binding is gone; the helper is
        // assigned only into the namespace table. (The user's own
        // `let User_fromJson` binding legitimately keeps its local name.)
        assertThat(out.lua(), not(containsString("User_fromJson = __rt.function_(")));
        assertDollarOnlyInQuotedKeys(out.lua());

        assumeLuajit();
        RunResult run = runLua(out.lua(), autoInvokeProbe());
        assertEquals("luajit exit 0, got: " + run.output(), 0, run.exit());
    }

    // =========================================================================
    // Class defaults / construction with reserved field keys
    // =========================================================================

    @Test
    public void classDefaultsAndConstructionUseNamespaceAndBracketKeys() throws Exception {
        String source =
            "export class C {\n" +
            "  end: int = 0;\n" +
            "}\n" +
            "export function make_c(): C {\n" +
            "  return { end: 1 };\n" +
            "}\n";
        CompileResult out = compile(source);

        assertThat(out.lua(), containsString("__deal[\"C_defaults\"] = {[\"end\"] = 0}"));
        assertThat(out.lua(), containsString("__deal[\"C_meta\"] = __rt.export_class(\"C\")"));
        assertThat(out.lua(), containsString(
            "__rt.class_(\"C\", __deal[\"C_defaults\"], {[\"end\"] = 1},"));
        assertThat(out.lua(), not(containsString("local C_defaults")));

        assumeLuajit();
        RunResult run = runLua(out.lua(), "local c = __mod.make_c.f()\nprint(c[\"end\"])");
        assertEquals("luajit exit 0, got: " + run.output(), 0, run.exit());
        assertThat(run.output(), containsString("1"));
    }

    // =========================================================================
    // Block-nested class declarations keep scope-local artifacts (D2.6)
    // =========================================================================

    /**
     * A class declared inside a bare top-level block is block-scoped DEAL
     * (the parser accepts it; referencing it outside the block is E3004),
     * so it is non-module-level: its artifacts keep the scope-local
     * {@code local C_defaults} / {@code local C_meta} form and never write
     * {@code __deal} namespace keys.
     */
    @Test
    public void topLevelBareBlockClassKeepsScopeLocalArtifacts() {
        String source =
            "{ class C { x: int = 0; } let c: C = { x: 1 }; }\n" +
            "export function get(): int { return 0; }\n";
        CompileResult out = compile(source);

        assertThat(out.lua(), containsString("local C_defaults = {x = 0}"));
        assertThat(out.lua(), containsString("local C_meta = __rt.export_class(\"C\")"));
        assertThat(out.lua(), not(containsString("__deal[\"C_defaults\"]")));
        assertThat(out.lua(), not(containsString("__deal[\"C_meta\"]")));
    }

    /** Block-nested class inside a function body: same scope-local rule, executed. */
    @Test
    public void blockNestedClassInFunctionKeepsScopeLocalArtifacts() throws Exception {
        String source =
            "export function test_block_class(): int {\n" +
            "  let out: int = 0;\n" +
            "  {\n" +
            "    class C { x: int = 0; }\n" +
            "    let c: C = { x: 1 };\n" +
            "    out = c.x;\n" +
            "  }\n" +
            "  return out;\n" +
            "}\n";
        CompileResult out = compile(source);

        assertThat(out.lua(), containsString("local C_defaults = {x = 0}"));
        assertThat(out.lua(), containsString("local C_meta = __rt.export_class(\"C\")"));
        assertThat(out.lua(), not(containsString("__deal[\"C_defaults\"]")));
        assertThat(out.lua(), not(containsString("__deal[\"C_meta\"]")));

        assumeLuajit();
        RunResult run = runLua(out.lua(), "print(__mod.test_block_class.f())");
        assertEquals("luajit exit 0, got: " + run.output(), 0, run.exit());
        assertThat(run.output(), containsString("1"));
    }

    // =========================================================================
    // Nested-class name shadowing (D2.6: construction keeps today's behavior)
    // =========================================================================

    /**
     * A function-local class that shadows a module-level class name: the
     * construction site inside the function must reference the bare
     * {@code C_defaults} local (Lua lexical scoping resolves it to the
     * scope-local artifact), not the module-level {@code __deal} entry.
     * This preserves the pre-namespace backend's resolution behavior
     * (lua-abi-emission-layer D2.6 "keeps today's behavior").
     */
    @Test
    public void shadowedModuleClassNameConstructionKeepsScopeLocalDefaults() throws Exception {
        String source =
            "class C { x: int = 0; }\n" +
            "export function test_shadow(): int {\n" +
            "  class C { y: int = 0; }\n" +
            "  let c: C = { y: 7 };\n" +
            "  return c.y;\n" +
            "}\n";
        CompileResult out = compile(source);

        assertThat(out.lua(), containsString("__deal[\"C_defaults\"] = {x = 0}"));
        assertThat(out.lua(), containsString("local C_defaults = {y = 0}"));
        // The construction references the scope-local artifact by bare name;
        // the module-level namespace entry must not be used for it.
        assertThat(out.lua(), containsString("__rt.class_(\"C\", C_defaults, {y = 7},"));
        assertThat(out.lua(), not(containsString("__rt.class_(\"C\", __deal[\"C_defaults\"]")));

        assumeLuajit();
        RunResult run = runLua(out.lua(), "print(__mod.test_shadow.f())");
        assertEquals("luajit exit 0, got: " + run.output(), 0, run.exit());
        assertThat(run.output(), containsString("7"));
    }

    /**
     * A block-nested class that shadows a module-level class name: the
     * construction inside the block keeps the scope-local reference, and a
     * sibling function that constructs the module-level class still uses
     * the {@code __deal} namespace entry (the block declaration registers
     * in the enclosing function's Lua scope, which ends at the function).
     */
    @Test
    public void blockNestedShadowingClassKeepsScopeLocalDefaultsAtTheConstructionSite()
            throws Exception {
        String source =
            "class C { x: int = 0; }\n" +
            "export function test_block_shadow(): int {\n" +
            "  let out: int = 0;\n" +
            "  {\n" +
            "    class C { y: int = 0; }\n" +
            "    let c: C = { y: 6 };\n" +
            "    out = c.y;\n" +
            "  }\n" +
            "  return out;\n" +
            "}\n" +
            "export function after_block(): int {\n" +
            "  let c: C = { x: 3 };\n" +
            "  return c.x;\n" +
            "}\n";
        CompileResult out = compile(source);

        assertThat(out.lua(), containsString("__deal[\"C_defaults\"] = {x = 0}"));
        assertThat(out.lua(), containsString("local C_defaults = {y = 0}"));
        assertThat(out.lua(), containsString("__rt.class_(\"C\", C_defaults, {y = 6},"));
        // The sibling function constructs the module-level class: namespace.
        assertThat(out.lua(), containsString(
            "__rt.class_(\"C\", __deal[\"C_defaults\"], {x = 3},"));

        assumeLuajit();
        RunResult run = runLua(out.lua(),
            "print(__mod.test_block_shadow.f())\nprint(__mod.after_block.f())");
        assertEquals("luajit exit 0, got: " + run.output(), 0, run.exit());
        assertThat(run.output(), containsString("6"));
        assertThat(run.output(), containsString("3"));
    }

    // =========================================================================
    // has() on a class field named with a Lua keyword
    // =========================================================================

    @Test
    public void hasCheckOnReservedFieldUsesBracketForm() throws Exception {
        String source =
            "class C {\n" +
            "  end?: int = 0;\n" +
            "}\n" +
            "export function test_has(): boolean {\n" +
            "  let c: C = { end: 5 };\n" +
            "  return has(c.end);\n" +
            "}\n";
        CompileResult out = compile(source);

        assertThat(out.lua(), containsString("c[\"end\"] ~= nil"));
        assertThat(out.lua(), not(containsString("c.end ~= nil")));

        assumeLuajit();
        RunResult run = runLua(out.lua(), "print(__mod.test_has.f())");
        assertEquals("luajit exit 0, got: " + run.output(), 0, run.exit());
        assertThat(run.output(), containsString("true"));
    }

    // =========================================================================
    // Export-key pins (per-kind rule)
    // =========================================================================

    @Test
    public void exportKeysFollowThePerKindRule() {
        String source =
            "// @jsonable\n" +
            "export class User {\n" +
            "  name: string = \"\";\n" +
            "}\n";
        CompileResult out = compile(source);

        assertThat(out.lua(), containsString("exports.User = __deal[\"User_meta\"]"));
        assertThat(out.lua(), containsString("exports.User_defaults = __deal[\"User_defaults\"]"));
        assertThat(out.lua(), containsString("exports.User_fields = __deal[\"User_fields\"]"));
        assertThat(out.lua(), containsString("exports[\"User$fromJson\"] = __deal[\"User$fromJson\"]"));
        assertThat(out.lua(), containsString("exports[\"User$toJson\"] = __deal[\"User$toJson\"]"));
        assertDollarOnlyInQuotedKeys(out.lua());
    }

    // =========================================================================
    // Error construction via the namespace defaults
    // =========================================================================

    @Test
    public void errorConstructionUsesNamespaceDefaults() throws Exception {
        String source =
            "export function make_err(): Error {\n" +
            "  return { code: \"X\" };\n" +
            "}\n";
        CompileResult out = compile(source);

        assertThat(out.lua(), containsString(
            "__deal[\"Error_defaults\"] = { code = \"\", message = \"\" }"));
        assertThat(out.lua(), containsString(
            "__rt.class_(\"Error\", __deal[\"Error_defaults\"], {code = \"X\"},"));
        // No bare Error_defaults identifier anywhere (header local retired).
        assertThat(out.lua(), not(containsString("local Error_defaults")));
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
            "(^|[^\"A-Za-z0-9_])Error_defaults([^\"A-Za-z0-9_]|$)").matcher(out.lua());
        assertTrue("bare Error_defaults identifier present in: " + out.lua(), !m.find());

        assumeLuajit();
        RunResult run = runLua(out.lua(),
            "local e = __mod.make_err.f()\nprint(e.code .. \"|\" .. e.message)");
        assertEquals("luajit exit 0, got: " + run.output(), 0, run.exit());
        assertThat(run.output(), containsString("X|"));
    }

    // =========================================================================
    // $-containing property names in table literals
    // =========================================================================

    @Test
    public void dollarPropertyNameEmitsBracketKey() throws Exception {
        String source =
            "let t: table = { bad$field: 1 };\n" +
            "export function get_t(): table {\n" +
            "  return t;\n" +
            "}\n";
        CompileResult out = compile(source);

        assertThat(out.lua(), containsString("[\"bad$field\"] = 1"));
        assertDollarOnlyInQuotedKeys(out.lua());

        assumeLuajit();
        RunResult run = runLua(out.lua(),
            "local t = __mod.get_t.f()\nprint(t[\"bad$field\"])");
        assertEquals("luajit exit 0, got: " + run.output(), 0, run.exit());
        assertThat(run.output(), containsString("1"));
    }

    // =========================================================================
    // @jsonable topological order preserved in namespace form
    // =========================================================================

    @Test
    public void jsonableTopologicalOrderPreserved() {
        String source =
            "// @jsonable\n" +
            "export class A {\n" +
            "  b: B;\n" +
            "}\n" +
            "// @jsonable\n" +
            "export class B {\n" +
            "  name: string = \"\";\n" +
            "}\n";
        CompileResult out = compile(source);

        int bFieldsPos = out.lua().indexOf("__deal[\"B_fields\"] = {");
        int aFieldsPos = out.lua().indexOf("__deal[\"A_fields\"] = {");
        assertTrue("B_fields exists", bFieldsPos >= 0);
        assertTrue("A_fields exists", aFieldsPos >= 0);
        assertTrue("B_fields emitted before A_fields (topological sort)",
            bFieldsPos < aFieldsPos);
        assertDollarOnlyInQuotedKeys(out.lua());
    }

    // =========================================================================
    // D5 zero-regression: unrelated emission shapes unchanged
    // =========================================================================

    @Test
    public void forLetShadowAndArrayIndexShapesUnchanged() throws Exception {
        String source =
            "export function loop_sum(): int {\n" +
            "  let xs: int[] = [1, 2, 3];\n" +
            "  let total: int = 0;\n" +
            "  for (let i: int = 0; i < 3; i = i + 1) {\n" +
            "    total = total + xs[i];\n" +
            "  }\n" +
            "  return total;\n" +
            "}\n";
        CompileResult out = compile(source);

        // for-let shadow lowering ("_i" outer counter) unchanged.
        assertThat(out.lua(), containsString("local _i = __rt.check_int(0,"));
        assertThat(out.lua(), containsString("local i = _i"));
        // Array-index read keeps the IIFE shape.
        assertThat(out.lua(), containsString("(function() local __idx = __rt.check_int("));
        assertThat(out.lua(), containsString("__idx + 1"));

        assumeLuajit();
        RunResult run = runLua(out.lua(), "print(__mod.loop_sum.f())");
        assertEquals("luajit exit 0, got: " + run.output(), 0, run.exit());
        assertThat(run.output(), containsString("6"));
    }

    // =========================================================================
    // Deterministic emission
    // =========================================================================

    @Test
    public void emissionIsDeterministic() {
        String source =
            "// @jsonable\n" +
            "export class User {\n" +
            "  name: string = \"\";\n" +
            "}\n" +
            "export function greet(): string { return \"hi\"; }\n";
        assertEquals("same input, same output",
            compile(source).lua(), compile(source).lua());
    }
}
