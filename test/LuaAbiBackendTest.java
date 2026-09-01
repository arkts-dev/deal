package deal.test;

import deal.ast.ClassField;
import deal.ast.NamedType;
import deal.ast.ProgramNode;
import deal.ast.Span;
import deal.checker.Symbol;
import deal.parser.ParseResult;
import deal.checker.CheckResult;
import deal.checker.NameResolver;
import deal.checker.SymbolTable;
import deal.checker.TypeChecker;
import deal.codegen.lua.LuaBackend;
import deal.identity.CanonicalModuleIdentity;
import deal.identity.ProjectModuleIdentity;
import deal.module.ModuleIdentityResolver;
import deal.lexer.LexResult;
import deal.lexer.Lexer;
import deal.parser.Parser;
import deal.types.Type;
import deal.types.Types;

import org.junit.Assume;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        return compile(source, filename, new StubModuleResolver(),
            Map.of());
    }

    /**
     * Host-module-aware compile variant: the caller-supplied resolver
     * registers the imported module surfaces, and the hostModules map
     * flows into the backend exactly like the conformance harness's
     * host registry (raw import path → declared export name → Type),
     * so the host-class discriminator can be inspected on the emitted
     * chunk.
     */
    private static CompileResult compile(String source, String filename,
            StubModuleResolver resolver,
            Map<String, Map<String, Type>> hostModules) {
        LexResult lex = new Lexer(source, filename).tokenize();
        if (lex.hasErrors()) {
            fail("lex errors: " + lex.diagnostics());
        }
        ParseResult parse = new Parser(lex.tokens(), filename, lex.directiveEvents()).parse();
        if (parse.hasErrors()) {
            fail("parse errors: " + parse.diagnostics());
        }
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
        String lua = hostModules.isEmpty()
            ? LuaBackend.generate(parse.program(), result, filename)
            : LuaBackend.generateWithImports(parse.program(), result,
                filename, filename, Map.of(), hostModules);
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
        assertThat(out.lua(), containsString("{[\"end\"] = 4, [\"local\"] = 5}"));
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

        assertThat(out.lua(), containsString("__deal[\"User$fromJson\"] = __rt.function_(\"(string)->?@test.deal/User\""));
        assertThat(out.lua(), containsString("__deal[\"User$toJson\"] = __rt.function_(\"(@test.deal/User)->string\""));
        assertThat(out.lua(), containsString("__deal[\"User$fromJson\"].f("));
        assertThat(out.lua(), containsString("exports[\"User$fromJson\"] = __deal[\"User$fromJson\"]"));
        assertThat(out.lua(), containsString("__deal[\"User_fields\"] = {"));
        // The fromJson/toJson bodies reference the namespace artifacts
        // (defaultsRefForTypeNode / fieldsRefForTypeNode module-level forms).
        assertThat(out.lua(), containsString(
            "__rt.json_from_plan(\"@test.deal/User\", __deal[\"User_plan\"], parsed, \"test.deal\", 2, 8)"));
        assertThat(out.lua(), containsString(
            "__rt.json_to_json(\"@test.deal/User\", v, __deal[\"User_fields\"])"));
        assertThat(out.lua(), containsString("__deal[\"User_meta\"] = __rt.export_class(\"@test.deal/User\")"));
        // The retired underscore-form helper binding is gone; the helper is
        // assigned only into the namespace table. (The user's own
        // `let User_fromJson` binding legitimately keeps its local name.)
        assertThat(out.lua(), not(containsString("User_fromJson = __rt.function_(")));
        assertDollarOnlyInQuotedKeys(out.lua());

        assumeLuajit();
        // Auto-invoke the exported function (asserts the user local is not
        // corrupted by the generated helper), then run a full
        // fromJson -> toJson -> fromJson roundtrip and assert the value.
        RunResult run = runLua(out.lua(),
            autoInvokeProbe() +
            "local u1 = __mod[\"User$fromJson\"].f(\"{\\\"name\\\":\\\"Ada\\\"}\")\n" +
            "local s1 = __mod[\"User$toJson\"].f(u1)\n" +
            "local u2 = __mod[\"User$fromJson\"].f(s1)\n" +
            "print(u2.name)\n");
        assertEquals("luajit exit 0, got: " + run.output(), 0, run.exit());
        assertThat(run.output(), containsString("Ada"));
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

        assertThat(out.lua(), containsString("__deal[\"C_plan\"] = {{ name = \"end\", descriptor = \"int\", optional = false, evaluator = function() return 0 end }}"));
        assertThat(out.lua(), containsString("__deal[\"C_meta\"] = __rt.export_class(\"@test.deal/C\")"));
        assertThat(out.lua(), containsString(
            "__rt.class_plan_(\"@test.deal/C\", __deal[\"C_plan\"], {[\"end\"] = 1},"));
        assertThat(out.lua(), not(containsString("local C_plan")));

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

        assertThat(out.lua(), containsString("local C_plan = {{ name = \"x\", descriptor = \"int\", optional = false, evaluator = function() return 0 end }}"));
        assertThat(out.lua(), containsString("local C_meta = __rt.export_class(\"@test.deal/C\")"));
        assertThat(out.lua(), not(containsString("__deal[\"C_plan\"]")));
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

        assertThat(out.lua(), containsString("local C_plan = {{ name = \"x\", descriptor = \"int\", optional = false, evaluator = function() return 0 end }}"));
        assertThat(out.lua(), containsString("local C_meta = __rt.export_class(\"@test.deal/C\")"));
        assertThat(out.lua(), not(containsString("__deal[\"C_plan\"]")));
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

        assertThat(out.lua(), containsString("__deal[\"C_plan\"] = {{ name = \"x\", descriptor = \"int\", optional = false, evaluator = function() return 0 end }}"));
        assertThat(out.lua(), containsString("local C_plan = {{ name = \"y\", descriptor = \"int\", optional = false, evaluator = function() return 0 end }}"));
        // The construction references the scope-local artifact by bare name;
        // the module-level namespace entry must not be used for it.
        assertThat(out.lua(), containsString("__rt.class_plan_(\"@test.deal/C\", C_plan, {y = 7},"));
        assertThat(out.lua(), not(containsString("__rt.class_plan_(\"@test.deal/C\", __deal[\"C_plan\"]")));

        assumeLuajit();
        RunResult run = runLua(out.lua(), "print(__mod.test_shadow.f())");
        assertEquals("luajit exit 0, got: " + run.output(), 0, run.exit());
        assertThat(run.output(), containsString("7"));
    }

    /**
     * Lua scope fidelity of the nested-class frame model: a class declared
     * in the then-branch of an if chain is scoped to that branch only.
     * The construction inside the declaring branch references the bare
     * {@code C_defaults} local; the else-branch construction (where the
     * then-branch local is lexically invisible) must reference the
     * module-level {@code __deal} namespace entry — the pre-namespace
     * backend resolved both correctly through Lua lexical scoping, and
     * this must keep working (D2.6 "keeps today's behavior").
     */
    @Test
    public void thenBranchClassShadowKeepsPerBranchScopeFidelity() throws Exception {
        String source =
            "class C { x: int = 0; }\n" +
            "export function test_branches(): int {\n" +
            "  let flag: boolean = false;\n" +
            "  let out: int = 0;\n" +
            "  if (flag) {\n" +
            "    class C { y: int = 0; }\n" +
            "    let c1: C = { y: 5 };\n" +
            "    out = c1.y;\n" +
            "  } else {\n" +
            "    let c2: C = { x: 2 };\n" +
            "    out = c2.x;\n" +
            "  }\n" +
            "  return out;\n" +
            "}\n" +
            "export function test_then(): int {\n" +
            "  let flag: boolean = true;\n" +
            "  let out: int = 0;\n" +
            "  if (flag) {\n" +
            "    class C { y: int = 0; }\n" +
            "    let c1: C = { y: 5 };\n" +
            "    out = c1.y;\n" +
            "  }\n" +
            "  return out;\n" +
            "}\n";
        CompileResult out = compile(source);

        // Construction inside the declaring then-branch: bare scope-local ref.
        assertThat(out.lua(), containsString("__rt.class_plan_(\"@test.deal/C\", C_plan, {y = 5},"));
        // Construction in the else branch (then-branch local invisible):
        // module-level namespace entry.
        assertThat(out.lua(), containsString(
            "__rt.class_plan_(\"@test.deal/C\", __deal[\"C_plan\"], {x = 2},"));
        assertThat(out.lua(), not(containsString(
            "__rt.class_plan_(\"@test.deal/C\", C_plan, {x = 2},")));

        assumeLuajit();
        RunResult run = runLua(out.lua(),
            "print(__mod.test_branches.f())\nprint(__mod.test_then.f())");
        assertEquals("luajit exit 0, got: " + run.output(), 0, run.exit());
        assertThat(run.output(), containsString("2"));
        assertThat(run.output(), containsString("5"));
    }

    /**
     * A class declared in a catch block is scoped to the emitted
     * {@code if not __ok then ... end} catch scope: a construction after
     * the try statement must reference the module-level namespace entry,
     * not the catch-local artifact (function-level try variant).
     */
    @Test
    public void catchBlockClassShadowDoesNotLeakPastTheTry() throws Exception {
        String source =
            "class C { x: int = 0; }\n" +
            "export function f(): int {\n" +
            "  try { throw { code: \"E1\", message: \"m\" }; } catch (e) { class C { y: int = 0; } }\n" +
            "  let c2: C = { x: 2 };\n" +
            "  return c2.x;\n" +
            "}\n";
        CompileResult out = compile(source);

        assertThat(out.lua(), containsString("local C_plan = {{ name = \"y\", descriptor = \"int\", optional = false, evaluator = function() return 0 end }}"));
        assertThat(out.lua(), containsString(
            "__rt.class_plan_(\"@test.deal/C\", __deal[\"C_plan\"], {x = 2},"));

        assumeLuajit();
        RunResult run = runLua(out.lua(), "print(__mod.f.f())");
        assertEquals("luajit exit 0, got: " + run.output(), 0, run.exit());
        assertThat(run.output(), containsString("2"));
    }

    /**
     * Module-level try variant: the catch-block class must not stay visible
     * for the rest of the chunk (the catch block is a Lua scope of its own);
     * a function declared after the try constructs the module-level class
     * via the namespace entry.
     */
    @Test
    public void moduleLevelCatchBlockClassShadowDoesNotLeakPastTheTry() throws Exception {
        String source =
            "class C { x: int = 0; }\n" +
            "try { throw { code: \"E1\", message: \"m\" }; } catch (e) { class C { y: int = 0; } }\n" +
            "export function f(): int {\n" +
            "  let c2: C = { x: 2 };\n" +
            "  return c2.x;\n" +
            "}\n";
        CompileResult out = compile(source);

        assertThat(out.lua(), containsString("local C_plan = {{ name = \"y\", descriptor = \"int\", optional = false, evaluator = function() return 0 end }}"));
        assertThat(out.lua(), containsString(
            "__rt.class_plan_(\"@test.deal/C\", __deal[\"C_plan\"], {x = 2},"));
        assertThat(out.lua(), not(containsString(
            "__rt.class_plan_(\"@test.deal/C\", C_plan, {x = 2},")));

        assumeLuajit();
        RunResult run = runLua(out.lua(), "print(__mod.f.f())");
        assertEquals("luajit exit 0, got: " + run.output(), 0, run.exit());
        assertThat(run.output(), containsString("2"));
    }

    // =========================================================================
    // Non-module-level export classes: scope-consistent export registration
    // =========================================================================

    /**
     * A class exported from a bare top-level block is non-module-level: its
     * artifacts are emitted as chunk-level locals, so the export values must
     * reference those bare locals (as the pre-namespace backend did), never
     * the {@code __deal} namespace entries — which are only written for
     * module-level declarations (D2.6). Consumers importing this module read
     * {@code M.C} / {@code M.C_defaults} off the exports table, so both
     * must be non-nil at runtime.
     */
    @Test
    public void blockNestedExportClassExportsTheScopeLocalArtifacts() throws Exception {
        String source =
            "{ export class C { x: int = 0; } }\n" +
            "export function g(): int { return 1; }\n";
        CompileResult out = compile(source);

        assertThat(out.lua(), containsString("local C_plan = {{ name = \"x\", descriptor = \"int\", optional = false, evaluator = function() return 0 end }}"));
        assertThat(out.lua(), containsString("local C_meta = __rt.export_class(\"@test.deal/C\")"));
        assertThat(out.lua(), containsString("exports.C = C_meta"));
        assertThat(out.lua(), containsString("exports.C_plan = C_plan"));
        assertThat(out.lua(), not(containsString("exports.C = __deal[\"C_meta\"]")));
        assertThat(out.lua(), not(containsString("exports.C_plan = __deal[\"C_plan\"]")));
        assertThat(out.lua(), not(containsString("__deal[\"C_meta\"]")));
        assertThat(out.lua(), not(containsString("__deal[\"C_plan\"]")));

        assumeLuajit();
        // Imported-module construction: a consumer constructs C through the
        // module's exports surface (M.C meta + M.C_defaults defaults).
        RunResult run = runLua(out.lua(),
            "local __rt = require(\"deal.runtime\")\n" +
            "print(__mod.g.f())\n" +
            "if __mod.C == nil then error(\"exports.C is nil\") end\n" +
            "if __mod.C_plan == nil then error(\"exports.C_plan is nil\") end\n" +
            "local c = __rt.class_plan_(\"@test.deal/C\", __mod.C_plan, {x = 3}, nil, nil, nil)\n" +
            "print(c.x)");
        assertEquals("luajit exit 0, got: " + run.output(), 0, run.exit());
        assertThat(run.output(), containsString("1"));
        assertThat(run.output(), containsString("3"));
    }

    /**
     * A block-nested {@code @jsonable} export class keeps its artifacts in
     * the legacy {@code $}→{@code _} scope-local form ({@code local
     * C_fields} / {@code local C_fromJson} / {@code local C_toJson}),
     * never writes {@code __deal} namespace keys (D2.6 ownership invariant),
     * and round-trips through the frozen export keys at runtime.
     */
    @Test
    public void blockNestedJsonableClassRoundTripsViaScopeLocalArtifacts() throws Exception {
        String source =
            "{ // @jsonable\n" +
            "export class C { x: int = 0; } }\n" +
            "export function f(): int { return 1; }\n";
        CompileResult out = compile(source);

        assertThat(out.lua(), containsString("local C_fields = {"));
        assertThat(out.lua(), containsString("local C_fromJson = __rt.function_(\"(string)->?@test.deal/C\", function(s)"));
        assertThat(out.lua(), containsString("local C_toJson = __rt.function_(\"(@test.deal/C)->string\", function(v)"));
        assertThat(out.lua(), containsString("exports.C_fields = C_fields"));
        assertThat(out.lua(), containsString("exports[\"C$fromJson\"] = C_fromJson"));
        assertThat(out.lua(), containsString("exports[\"C$toJson\"] = C_toJson"));
        // The deferred pass references the scope-local defaults/fields and
        // never writes namespace keys for the non-module-level class.
        assertThat(out.lua(), containsString(
            "__rt.json_from_plan(\"@test.deal/C\", C_plan, parsed, \"test.deal\", 2, 8)"));
        assertThat(out.lua(), not(containsString("__deal[\"C_fields\"]")));
        assertThat(out.lua(), not(containsString("__deal[\"C$fromJson\"]")));
        assertThat(out.lua(), not(containsString("__deal[\"C$toJson\"]")));
        assertThat(out.lua(), not(containsString("__deal[\"C_plan\"]")));
        assertThat(out.lua(), not(containsString("__deal[\"C_meta\"]")));
        assertDollarOnlyInQuotedKeys(out.lua());

        assumeLuajit();
        RunResult run = runLua(out.lua(),
            "local c = __mod[\"C$fromJson\"].f(\"{\\\"x\\\": 7}\")\n" +
            "print(c.x)\n" +
            "local s = __mod[\"C$toJson\"].f(c)\n" +
            "local c2 = __mod[\"C$fromJson\"].f(s)\n" +
            "print(c2.x)");
        assertEquals("luajit exit 0, got: " + run.output(), 0, run.exit());
        assertThat(run.output(), containsString("7"));
    }

    /**
     * A nested {@code @jsonable} export class that shadows a module-level
     * {@code @jsonable} class name: the deferred pass is keyed by class
     * name with last-declaration-wins (the pre-namespace backend's
     * structure), so the export values must track the LAST declaration —
     * the one whose artifacts the deferred pass actually emits — or they
     * would reference artifacts that were never written (nil). Verified
     * against the pre-namespace backend, whose single last-wins bare-local
     * artifact kept every export non-nil.
     */
    @Test
    public void nestedJsonableShadowingModuleLevelJsonableKeepsExportsNonNil()
            throws Exception {
        String source =
            "// @jsonable\n" +
            "export class C { x: int = 0; }\n" +
            "{ // @jsonable\n" +
            "export class C { y: int = 0; } }\n" +
            "export function f(): int { return 1; }\n";
        CompileResult out = compile(source);

        assertThat(out.lua(), containsString("local C_fields = {"));
        assertThat(out.lua(), containsString("exports.C_fields = C_fields"));
        assertThat(out.lua(), containsString("exports[\"C$fromJson\"] = C_fromJson"));
        assertThat(out.lua(), containsString("exports[\"C$toJson\"] = C_toJson"));
        assertThat(out.lua(), not(containsString(
            "exports[\"C$fromJson\"] = __deal[\"C$fromJson\"]")));
        // META/DEFAULTS resolve at chunk end to the LAST chunk-visible
        // declaration (the block class), not the first registration: all
        // five export keys serve the same class identity (the
        // pre-namespace backend's chunk-end bare-name resolution).
        assertThat(out.lua(), containsString("exports.C = C_meta"));
        assertThat(out.lua(), containsString("exports.C_plan = C_plan"));
        assertThat(out.lua(), not(containsString("exports.C = __deal[\"C_meta\"]")));
        assertThat(out.lua(), not(containsString(
            "exports.C_plan = __deal[\"C_plan\"]")));
        assertDollarOnlyInQuotedKeys(out.lua());

        assumeLuajit();
        RunResult run = runLua(out.lua(),
            "local c = __mod[\"C$fromJson\"].f(\"{\\\"y\\\": 9}\")\n" +
            "print(c == nil and \"BROKEN\" or c.y)\n" +
            "if __mod.C_fields == nil then print(\"nil fields\") else print(\"fields ok\") end\n" +
            "if __mod.C_plan == nil then error(\"C_plan is nil\") end\n" +
            "if __mod.C_plan[1].name ~= \"y\" then error(\"block plan lost\") end\n" +
            "if #__mod.C_plan ~= 1 then error(\"module plan leaked\") end");
        assertEquals("luajit exit 0, got: " + run.output(), 0, run.exit());
        assertThat(run.output(), containsString("9"));
        assertThat(run.output(), containsString("fields ok"));
    }

    /**
     * The reverse declaration order — a chunk-level bare-block export of
     * a class name first, then a module-level export of the same name —
     * resolves all five export keys to the module-level declaration
     * (namespace forms): at the chunk-end export statements the last
     * chunk-visible declaration is the module-level one, exactly as the
     * pre-namespace backend's bare-name export statements resolved.
     */
    @Test
    public void sameNameBlockThenModuleExportResolvesAllKeysToTheModuleDeclaration()
            throws Exception {
        String source =
            "{ // @jsonable\n" +
            "export class C { y: int = 0; } }\n" +
            "// @jsonable\n" +
            "export class C { x: int = 0; }\n" +
            "export function g(): int { return 1; }\n";
        CompileResult out = compile(source);

        assertThat(out.lua(), containsString("exports.C = __deal[\"C_meta\"]"));
        assertThat(out.lua(), containsString("exports.C_plan = __deal[\"C_plan\"]"));
        assertThat(out.lua(), containsString("exports.C_fields = __deal[\"C_fields\"]"));
        assertThat(out.lua(), containsString("exports[\"C$fromJson\"] = __deal[\"C$fromJson\"]"));
        assertThat(out.lua(), containsString("exports[\"C$toJson\"] = __deal[\"C$toJson\"]"));
        assertThat(out.lua(), not(containsString("exports.C = C_meta")));
        assertThat(out.lua(), not(containsString("exports.C_plan = C_plan")));
        assertDollarOnlyInQuotedKeys(out.lua());

        assumeLuajit();
        RunResult run = runLua(out.lua(),
            "local __rt = require(\"deal.runtime\")\n" +
            "print(__mod.g.f())\n" +
            "if __mod.C_plan == nil then error(\"C_plan is nil\") end\n" +
            "if __mod.C_plan[1].name ~= \"x\" then error(\"module plan lost\") end\n" +
            "if #__mod.C_plan ~= 1 then error(\"block plan leaked\") end\n" +
            "local c = __rt.class_plan_(\"@test.deal/C\", __mod.C_plan, {x = 3}, nil, nil, nil)\n" +
            "print(c.x)\n" +
            "local d = __mod[\"C$fromJson\"].f(\"{\\\"x\\\":9}\")\n" +
            "print(d.x)");
        assertEquals("luajit exit 0, got: " + run.output(), 0, run.exit());
        assertThat(run.output(), containsString("1"));
        assertThat(run.output(), containsString("3"));
        assertThat(run.output(), containsString("9"));
    }

    /**
     * A non-exported chunk-level bare-block class that shadows an
     * exported module-level class name wins the chunk-end export
     * resolution exactly like the pre-namespace backend: its artifact
     * locals are chunk-level locals still visible at the chunk-end export
     * statements, so the exports resolve to the block class (the
     * resolution tracks every chunk-visible declaration, not only
     * exported ones).
     */
    @Test
    public void nonExportedChunkLevelShadowWinsTheExportResolution()
            throws Exception {
        String source =
            "export class C { x: int = 0; }\n" +
            "{ class C { y: int = 0; } }\n" +
            "export function g(): int { return 1; }\n";
        CompileResult out = compile(source);

        assertThat(out.lua(), containsString("local C_plan = {{ name = \"y\", descriptor = \"int\", optional = false, evaluator = function() return 0 end }}"));
        assertThat(out.lua(), containsString("exports.C = C_meta"));
        assertThat(out.lua(), containsString("exports.C_plan = C_plan"));
        assertThat(out.lua(), not(containsString("exports.C = __deal[\"C_meta\"]")));
        assertThat(out.lua(), not(containsString(
            "exports.C_plan = __deal[\"C_plan\"]")));

        assumeLuajit();
        RunResult run = runLua(out.lua(),
            "local __rt = require(\"deal.runtime\")\n" +
            "print(__mod.g.f())\n" +
            "if __mod.C_plan == nil then error(\"C_plan is nil\") end\n" +
            "if __mod.C_plan[1].name ~= \"y\" then error(\"block plan lost\") end\n" +
            "if #__mod.C_plan ~= 1 then error(\"module plan leaked\") end\n" +
            "local c = __rt.class_plan_(\"@test.deal/C\", __mod.C_plan, {y = 2}, nil, nil, nil)\n" +
            "print(c.y)");
        assertEquals("luajit exit 0, got: " + run.output(), 0, run.exit());
        assertThat(run.output(), containsString("1"));
        assertThat(run.output(), containsString("2"));
    }

    /**
     * A function-scoped export class must not overwrite the chunk-visible
     * declaration of the same name: its artifact locals are out of scope
     * at the chunk-end export statements, so the exports keep serving the
     * module-level declaration (the pre-namespace backend's bare export
     * names resolved to the module-level locals in this corner).
     */
    @Test
    public void functionScopedExportDoesNotOverwriteTheChunkVisibleDeclaration()
            throws Exception {
        String source =
            "export class C { x: int = 0; }\n" +
            "export function f(): int {\n" +
            "  { export class C { y: int = 0; } }\n" +
            "  return 1;\n" +
            "}\n";
        CompileResult out = compile(source);

        assertThat(out.lua(), containsString("exports.C = __deal[\"C_meta\"]"));
        assertThat(out.lua(), containsString("exports.C_plan = __deal[\"C_plan\"]"));
        assertThat(out.lua(), not(containsString("exports.C = C_meta")));
        assertThat(out.lua(), not(containsString("exports.C_plan = C_plan")));

        assumeLuajit();
        RunResult run = runLua(out.lua(),
            "local __rt = require(\"deal.runtime\")\n" +
            "print(__mod.f.f())\n" +
            "if __mod.C_plan == nil then error(\"C_plan is nil\") end\n" +
            "if __mod.C_plan[1].name ~= \"x\" then error(\"module plan lost\") end\n" +
            "if #__mod.C_plan ~= 1 then error(\"function-local plan leaked\") end\n" +
            "local c = __rt.class_plan_(\"@test.deal/C\", __mod.C_plan, {x = 3}, nil, nil, nil)\n" +
            "print(c.x)");
        assertEquals("luajit exit 0, got: " + run.output(), 0, run.exit());
        assertThat(run.output(), containsString("1"));
        assertThat(run.output(), containsString("3"));
    }

    /**
     * With only function-scoped export declarations of a class name, the
     * exports keep the bare artifact form, which is nil at runtime — the
     * pre-namespace backend's behavior (its chunk-end bare export names
     * had no visible local to resolve to).
     */
    @Test
    public void functionScopedOnlyExportKeepsTheBareNilResolution()
            throws Exception {
        String source =
            "export function f(): int {\n" +
            "  { export class C { y: int = 0; } }\n" +
            "  return 1;\n" +
            "}\n";
        CompileResult out = compile(source);

        assertThat(out.lua(), containsString("exports.C = C_meta"));
        assertThat(out.lua(), containsString("exports.C_plan = C_plan"));
        assertThat(out.lua(), not(containsString("exports.C = __deal[\"C_meta\"]")));

        assumeLuajit();
        RunResult run = runLua(out.lua(),
            "print(__mod.f.f())\n" +
            "print(tostring(__mod.C))\n" +
            "print(tostring(__mod.C_plan))");
        assertEquals("luajit exit 0, got: " + run.output(), 0, run.exit());
        assertThat(run.output(), containsString("1"));
        assertThat(run.output(), containsString("nil"));
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

        assertThat(out.lua(), containsString("__deal[\"C_plan\"] = {{ name = \"x\", descriptor = \"int\", optional = false, evaluator = function() return 0 end }}"));
        assertThat(out.lua(), containsString("local C_plan = {{ name = \"y\", descriptor = \"int\", optional = false, evaluator = function() return 0 end }}"));
        assertThat(out.lua(), containsString("__rt.class_plan_(\"@test.deal/C\", C_plan, {y = 6},"));
        // The sibling function constructs the module-level class: namespace.
        assertThat(out.lua(), containsString(
            "__rt.class_plan_(\"@test.deal/C\", __deal[\"C_plan\"], {x = 3},"));

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
        assertThat(out.lua(), containsString("exports.User_plan = __deal[\"User_plan\"]"));
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
            "__rt.class_(\"@$builtin/Error\", __deal[\"Error_defaults\"], {code = \"X\"},"));
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
        // The A_fields descriptor references B artifacts in namespace form
        // (the NamedType branch of defaultsRef/fieldsRef).
        assertThat(out.lua(), containsString(
            "plan = __deal[\"B_plan\"], fields = __deal[\"B_fields\"]"));
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
    // ISSUE-0082 host loader declared-map emission (host-module-abi D4;
    // lua-abi-emission-layer D3 key-form policy at the new table-constructor
    // site)
    // =========================================================================

    /**
     * Compiles a module importing host declarations through the hostModules
     * map and pins the loader emission: the raw import specifier as the
     * first argument (never the dotted importResolutions value), Lua-keyword
     * export names and "$" synthetic exports as bracket-string declared-map
     * keys, and the module-qualified class descriptor.
     */
    @Test
    public void hostLoaderDeclaredMapRoutesKeysThroughTableField() {
        Map<String, Type> hostExports = new LinkedHashMap<>();
        hostExports.put("repeat", Types.func(List.of(), Type.Int.INSTANCE));
        // Mirrors ExportExtractor's synthesized @jsonable helpers exactly:
        // C$fromJson: (string) -> C | null;  C$toJson: (C) -> string.
        hostExports.put("User$fromJson", Types.func(List.of(Type.String.INSTANCE),
            Types.nullable(IdentityTestFixtures.classType("User", "host.cfg"))));
        hostExports.put("User$toJson", Types.func(
            List.of(IdentityTestFixtures.classType("User", "host.cfg")), Type.String.INSTANCE));
        hostExports.put("User", IdentityTestFixtures.classType("User", "host.cfg"));

        StubModuleResolver resolver = new StubModuleResolver();
        resolver.register("host/cfg", hostExports);

        String source = "import * as cfg from \"host/cfg\"\n"
            + "export function run(): int { return cfg.repeat(); }\n";
        LexResult lex = new Lexer(source, "test.deal").tokenize();
        if (lex.hasErrors()) fail("lex errors: " + lex.diagnostics());
        ParseResult parse = new Parser(lex.tokens(), "test.deal", lex.directiveEvents()).parse();
        if (parse.hasErrors()) fail("parse errors: " + parse.diagnostics());
        NameResolver nr = new NameResolver("test.deal", resolver);
        SymbolTable symTable = nr.resolve(parse.program());
        if (nr.diagnostics().stream().anyMatch(
                d -> "error".equals(d.severity()))) {
            fail("name-resolution errors: " + nr.diagnostics());
        }
        CheckResult result = TypeChecker.check("test.deal", symTable, nr,
            parse.program());
        if (result.hasErrors()) {
            fail("type errors: " + result.diagnostics());
        }

        // The host branch must win over the importResolutions value for the
        // same raw path: the dotted name is typing-only and never emitted.
        String lua = LuaBackend.generateWithImports(parse.program(), result,
            "test.deal", Map.of("host/cfg", "host.cfg"),
            Map.of("host/cfg", hostExports));

        assertThat(lua, containsString(
            "local cfg = __rt.load_host(\"host/cfg\", {"));
        assertThat(lua, not(containsString("load_host(\"host.cfg\"")));
        assertThat(lua, not(containsString("require(\"host/cfg\")")));
        assertThat(lua, containsString("[\"repeat\"] = \"()->int\""));
        assertThat(lua, containsString(
            "[\"User$fromJson\"] = \"(string)->?@host.cfg/User\""));
        assertThat(lua, containsString(
            "[\"User$toJson\"] = \"(@host.cfg/User)->string\""));
        assertThat(lua, containsString("User = \"@host.cfg/User\""));
        assertDollarOnlyInQuotedKeys(lua);
    }

    /**
     * Descriptor emission pins (emitter page D1, host-module-abi
     * Verification 3c): nullable-function parameters emit the "?F" form,
     * nullable class returns emit the canonical "?T" prefix, and array
     * parameters emit "[T]" — never the legacy "T|null"/"T[]" spellings.
     * DEAL v1.2 removed rest parameters, so the v1.1 rest arms
     * ("...string[]", "...[(int)->int]") are gone: the backend emits
     * fixed-parameter canonical descriptors only.
     */
    @Test
    public void hostLoaderDescriptorEmissionPins() {
        Map<String, Type> hostExports = new LinkedHashMap<>();
        // export function register(cb: ((x: int) => int) | null): null;
        hostExports.put("register", Types.func(
            List.of(Types.nullable(
                Types.func(List.of(Type.Int.INSTANCE), Type.Int.INSTANCE))),
            Type.Null.INSTANCE));
        // export function log(level: string, parts: string[]): null;
        hostExports.put("log", Types.func(
            List.of(Type.String.INSTANCE, Types.array(Type.String.INSTANCE)),
            Type.Null.INSTANCE));
        // export function applyAll(prefix: string, fns: ((x: int) => int)[]): string;
        hostExports.put("applyAll", Types.func(
            List.of(Type.String.INSTANCE,
                Types.array(Types.func(List.of(Type.Int.INSTANCE), Type.Int.INSTANCE))),
            Type.String.INSTANCE));
        // export function find(s: string): User | null;
        hostExports.put("find", Types.func(List.of(Type.String.INSTANCE),
            Types.nullable(IdentityTestFixtures.classType("User", "host.cfg"))));

        StubModuleResolver resolver = new StubModuleResolver();
        resolver.register("host/cfg", hostExports);

        String source = "import * as cfg from \"host/cfg\"\n"
            + "export function f(): int {\n"
            + "  cfg.register(null);\n"
            + "  let parts: string[] = [\"a\", \"b\"];\n"
            + "  cfg.log(\"a\", parts);\n"
            + "  let fns: ((x: int) => int)[] = [];\n"
            + "  cfg.applyAll(\"p\", fns);\n"
            + "  return 1;\n"
            + "}\n";
        LexResult lex = new Lexer(source, "test.deal").tokenize();
        if (lex.hasErrors()) fail("lex errors: " + lex.diagnostics());
        ParseResult parse = new Parser(lex.tokens(), "test.deal", lex.directiveEvents()).parse();
        if (parse.hasErrors()) fail("parse errors: " + parse.diagnostics());
        NameResolver nr = new NameResolver("test.deal", resolver);
        SymbolTable symTable = nr.resolve(parse.program());
        if (nr.diagnostics().stream().anyMatch(
                d -> "error".equals(d.severity()))) {
            fail("name-resolution errors: " + nr.diagnostics());
        }
        CheckResult result = TypeChecker.check("test.deal", symTable, nr,
            parse.program());
        if (result.hasErrors()) {
            fail("type errors: " + result.diagnostics());
        }

        String lua = LuaBackend.generateWithImports(parse.program(), result,
            "test.deal", Map.of(), Map.of("host/cfg", hostExports));

        assertThat(lua, containsString("register = \"(?(int)->int)->null\""));
        assertThat(lua, containsString("log = \"(string,[string])->null\""));
        assertThat(lua, containsString(
            "applyAll = \"(string,[(int)->int])->string\""));
        assertThat(lua, containsString(
            "find = \"(string)->?@host.cfg/User\""));
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

    /**
     * The host loader declared-map emission order is independent of the
     * caller-supplied map implementation.  Immutable maps (Map.of /
     * Map.copyOf) iterate in per-JVM-run randomized order, so iterating the
     * caller's map directly would make generated Lua vary between identical
     * builds.  The backend iterates declared entries sorted by export name:
     * the same module compiled from maps with different implementations and
     * insertion orders must produce byte-identical Lua.
     */
    @Test
    public void hostLoaderEmissionIsIndependentOfCallerMapOrder() {
        // The 7-key declared set from the host-loader pins, plus the
        // orchestrator's typical shape (ping/repeat/User + @jsonable
        // synthetics + nullable-class return).
        Map<String, Type> insertionOrder = new LinkedHashMap<>();
        insertionOrder.put("ping", Types.func(List.of(), Type.Int.INSTANCE));
        insertionOrder.put("repeat", Types.func(List.of(), Type.Int.INSTANCE));
        insertionOrder.put("User$fromJson", Types.func(List.of(Type.String.INSTANCE),
            Types.nullable(IdentityTestFixtures.classType("User", "host.cfg"))));
        insertionOrder.put("User$toJson", Types.func(
            List.of(IdentityTestFixtures.classType("User", "host.cfg")), Type.String.INSTANCE));
        insertionOrder.put("User", IdentityTestFixtures.classType("User", "host.cfg"));
        insertionOrder.put("find", Types.func(List.of(Type.String.INSTANCE),
            Types.nullable(IdentityTestFixtures.classType("User", "host.cfg"))));
        insertionOrder.put("applyAll", Types.func(
            List.of(Type.String.INSTANCE,
                Types.array(Types.func(List.of(Type.Int.INSTANCE), Type.Int.INSTANCE))),
            Type.String.INSTANCE));

        // Reverse insertion order.
        Map<String, Type> reversedOrder = new LinkedHashMap<>();
        List<String> keys = new ArrayList<>(insertionOrder.keySet());
        java.util.Collections.reverse(keys);
        for (String k : keys) {
            reversedOrder.put(k, insertionOrder.get(k));
        }

        // Hash-based implementation (iteration order unrelated to content).
        Map<String, Type> hashMap = new java.util.HashMap<>(insertionOrder);

        // Immutable maps: Map.copyOf/Map.of iterate in per-JVM-run SALT-
        // randomized order (the reviewer-observed nondeterminism source).
        Map<String, Type> copied = Map.copyOf(insertionOrder);
        Map<String, Type> immutable = Map.ofEntries(
            Map.entry("find", insertionOrder.get("find")),
            Map.entry("repeat", insertionOrder.get("repeat")),
            Map.entry("User$toJson", insertionOrder.get("User$toJson")),
            Map.entry("ping", insertionOrder.get("ping")),
            Map.entry("User", insertionOrder.get("User")),
            Map.entry("User$fromJson", insertionOrder.get("User$fromJson")),
            Map.entry("applyAll", insertionOrder.get("applyAll")));

        String source = "import * as cfg from \"host/cfg\"\n"
            + "export function run(): int { return cfg.repeat(); }\n";
        LexResult lex = new Lexer(source, "test.deal").tokenize();
        if (lex.hasErrors()) fail("lex errors: " + lex.diagnostics());
        ParseResult parse = new Parser(lex.tokens(), "test.deal", lex.directiveEvents()).parse();
        if (parse.hasErrors()) fail("parse errors: " + parse.diagnostics());
        StubModuleResolver resolver = new StubModuleResolver();
        resolver.register("host/cfg", insertionOrder);
        NameResolver nr = new NameResolver("test.deal", resolver);
        SymbolTable symTable = nr.resolve(parse.program());
        if (nr.diagnostics().stream().anyMatch(
                d -> "error".equals(d.severity()))) {
            fail("name-resolution errors: " + nr.diagnostics());
        }
        CheckResult result = TypeChecker.check("test.deal", symTable, nr,
            parse.program());
        if (result.hasErrors()) {
            fail("type errors: " + result.diagnostics());
        }

        String reference = LuaBackend.generateWithImports(parse.program(),
            result, "test.deal", Map.of(), Map.of("host/cfg", insertionOrder));
        assertThat(reference, containsString(
            "local cfg = __rt.load_host(\"host/cfg\", {"));
        assertEquals("reverse insertion order, same bytes", reference,
            LuaBackend.generateWithImports(parse.program(), result,
                "test.deal", Map.of(), Map.of("host/cfg", reversedOrder)));
        assertEquals("hash map iteration order, same bytes", reference,
            LuaBackend.generateWithImports(parse.program(), result,
                "test.deal", Map.of(), Map.of("host/cfg", hashMap)));
        assertEquals("Map.copyOf order, same bytes", reference,
            LuaBackend.generateWithImports(parse.program(), result,
                "test.deal", Map.of(), Map.of("host/cfg", copied)));
        assertEquals("Map.ofEntries order, same bytes", reference,
            LuaBackend.generateWithImports(parse.program(), result,
                "test.deal", Map.of(), Map.of("host/cfg", immutable)));

        // The sorted emission pins the canonical order explicitly so a
        // future accidental order change fails loudly.  Sorted by export
        // name: "User" is a prefix of "User$fromJson" and uppercase sorts
        // before lowercase, so User < User$fromJson < ... < repeat.
        int repeatPos = reference.indexOf("[\"repeat\"] = \"()->int\"");
        int fromJsonPos = reference.indexOf(
            "[\"User$fromJson\"] = \"(string)->?@host.cfg/User\"");
        int userPos = reference.indexOf("User = \"@host.cfg/User\"");
        assertTrue("User before User$fromJson (sorted prefix rule)",
            userPos >= 0 && fromJsonPos >= 0 && userPos < fromJsonPos);
        assertTrue("User$fromJson before repeat (sorted)",
            repeatPos >= 0 && fromJsonPos < repeatPos);
    }


    // =========================================================================
    // ISSUE-0340: default-plan artifacts, construction sites, discriminator
    // =========================================================================

    /**
     * Compiler-class plan lowering (emitter page D4): the module-level
     * declaration assigns the {@code <C>_plan} artifact during chunk
     * load — an ordered field-entry list with evaluator closures in
     * class source order — and the chunk never invokes an evaluator.
     * The export surface carries the PLAN key for compiler classes
     * only; construction sites route through {@code __rt.class_plan_}
     * with the plan reference and the literal's span args.
     */
    @Test
    public void planArtifactAssignedAtLoadExportedAndUsedAtConstruction()
            throws Exception {
        String source =
            "export class C {\n" +
            "  x: int = 0;\n" +
            "  items: table = {};\n" +
            "}\n" +
            "export function make(): C {\n" +
            "  return { x: 5 };\n" +
            "}\n";
        CompileResult out = compile(source);

        assertThat(out.lua(), containsString(
            "__deal[\"C_plan\"] = {{ name = \"x\", descriptor = \"int\", "
                + "optional = false, evaluator = function() return 0 end }, "
                + "{ name = \"items\", descriptor = \"table\", "
                + "optional = false, evaluator = function() return {} end }}"));
        assertThat(out.lua(), containsString("exports.C_plan = __deal[\"C_plan\"]"));
        assertThat(out.lua(), not(containsString("exports.C_defaults")));
        assertThat(out.lua(), not(containsString("__deal[\"C_defaults\"]")));
        assertThat(out.lua(), containsString(
            "__rt.class_plan_(\"@test.deal/C\", __deal[\"C_plan\"], {x = 5}, "
                + "\"test.deal\", 6, 10)"));

        assumeLuajit();
        // Two constructions never share the mutable table default (the
        // evaluator constructs it per attempt).
        RunResult run = runLua(out.lua(),
            "local a = __mod.make.f()\n"
            + "local b = __mod.make.f()\n"
            + "a.items.tag = 1\n"
            + "print(b.items.tag == nil and \"FRESH\" or \"SHARED\")");
        assertEquals("luajit exit 0, got: " + run.output(), 0, run.exit());
        assertThat(run.output(), containsString("FRESH"));
    }

    /**
     * The zero-invocation load-time pin: a default expression calling a
     * function declared LATER in the module lives only inside the
     * evaluator closure. A load-time invocation would index the
     * not-yet-assigned function wrapper (nil) and crash the chunk; the
     * chunk loads and the first construction runs the evaluator exactly
     * once.
     */
    @Test
    public void evaluatorClosuresAreNeverInvokedAtModuleLoad() throws Exception {
        String source =
            "class C {\n" +
            "  x: int = later();\n" +
            "}\n" +
            "function later(): int { return 7; }\n" +
            "export function make(): C { return {}; }\n";
        CompileResult out = compile(source);

        assertThat(out.lua(), containsString(
            "evaluator = function() return later.f() end"));
        // The default expression appears exactly once — inside the
        // evaluator closure — never as a load-time chunk statement.
        int calls = 0;
        int idx = 0;
        while ((idx = out.lua().indexOf("later.f()", idx)) != -1) {
            calls++;
            idx += "later.f()".length();
        }
        assertEquals("exactly one later.f() call site (the evaluator)", 1, calls);

        assumeLuajit();
        RunResult run = runLua(out.lua(),
            "print(__mod.make.f().x)\n"
            + "print(__mod.make.f().x)");
        assertEquals("luajit exit 0, got: " + run.output(), 0, run.exit());
        assertThat(run.output(), containsString("7"));
    }

    /**
     * E8007-before-defaults phase-order pin through the emitted plan
     * (runtime page D4 phase 1): the frontend E4002 gate rejects extra
     * fields in compiler-class literals at compile time, so the
     * source-visible corpus probe pins the provided-value arm (E8002)
     * instead — this test drives the emitted {@code <C>_plan} artifact
     * through {@code __rt.class_plan_} directly under real LuaJIT. The
     * evaluator raises E8005 when invoked, so an out-of-order default
     * evaluation would surface E8005 before the extra-field check; the
     * driver observes E8007 with the evaluator untouched.
     */
    @Test
    public void extraProvidedFieldRaisesE8007BeforeAnyDefaultEvaluation()
            throws Exception {
        String source =
            "export class C {\n" +
            "  x: int = 1 / 0;\n" +
            "}\n" +
            "export function make(): C { return {}; }\n";
        CompileResult out = compile(source);

        assertThat(out.lua(), containsString(
            "evaluator = function() return __rt.int_div(1, 0,"));

        assumeLuajit();
        RunResult run = runLua(out.lua(),
            "local __rt = require(\"deal.runtime\")\n"
            + "local ok, err = pcall(__rt.class_plan_, "
            + "\"@test.deal/C\", __mod.C_plan, {extra = 1}, "
            + "\"t.deal\", 3, 5)\n"
            + "if ok then error(\"extra provided field did not raise\") end\n"
            + "if err == nil or err.code ~= \"E8007\" then\n"
            + "  error(\"expected E8007, got \" .. tostring(err and err.code))\n"
            + "end\n"
            + "print(\"E8007-BEFORE-DEFAULTS\")\n");
        assertEquals("luajit exit 0, got: " + run.output(), 0, run.exit());
        assertThat(run.output(), containsString("E8007-BEFORE-DEFAULTS"));
    }

    /**
     * Host-class discriminator (emitter page D4/D7): an imported class
     * whose dotted modulePath equals the dotted form of a hostModules
     * key constructs through exactly today's emission —
     * {@code __rt.class_} over {@code alias[\"<C>_defaults\"]} — and no
     * {@code <C>_plan} is read or exported for it.
     */
    @Test
    public void hostDeclaredImportedClassKeepsTheDefaultsSeamAndReadsNoPlan() {
        String filename = "hosttest.deal";
        Map<String, Type> exports = new LinkedHashMap<>();
        exports.put("ServerConfig", IdentityTestFixtures.classType("ServerConfig", "host.cfg"));
        StubModuleResolver resolver = new StubModuleResolver();
        resolver.register("host/cfg", exports);
        resolver.registerClassSymbol("host.cfg", new Symbol.ClassSymbol(
            "ServerConfig", List.of(
                new ClassField(new Span("host.cfg", 1, 1, 1, 1),
                    "port", false, false, new NamedType(
                        new Span("host.cfg", 1, 1, 1, 1), "int"),
                    java.util.Optional.empty())),
            "host.cfg",
            IdentityTestFixtures.identityOf("host.cfg", "ServerConfig")));
        CompileResult out = compile(
            "import * as cfg from \"host/cfg\"\n" +
            "export function make(): cfg.ServerConfig {\n" +
            "  return { port: 9090 };\n" +
            "}\n",
            filename, resolver, Map.of("host/cfg", exports));

        assertThat(out.lua(), containsString(
            "__rt.class_(\"@host.cfg/ServerConfig\", "
                + "cfg.ServerConfig_defaults, {port = 9090}, "
                + "\"hosttest.deal\", 3, 10)"));
        assertThat(out.lua(), not(containsString("ServerConfig_plan")));
    }

    /**
     * DEAL-imported discriminator arm: the same construction shape for
     * a compiler-declared imported class routes through
     * {@code __rt.class_plan_} over the provider's exported
     * {@code alias[\"<C>_plan\"]}.
     */
    @Test
    public void dealImportedClassConstructsThroughTheExportedPlan() {
        String filename = "dealimport.deal";
        Map<String, Type> exports = new LinkedHashMap<>();
        exports.put("Item", IdentityTestFixtures.classType("Item", "lib"));
        StubModuleResolver resolver = new StubModuleResolver();
        resolver.register("./lib", exports);
        resolver.registerClassSymbol("lib", new Symbol.ClassSymbol(
            "Item", List.of(
                new ClassField(new Span("lib", 1, 1, 1, 1),
                    "value", false, false, new NamedType(
                        new Span("lib", 1, 1, 1, 1), "int"),
                    java.util.Optional.empty())),
            "lib",
            IdentityTestFixtures.identityOf("lib", "Item")));
        // The standalone descriptor surface classifies only the
        // module's own path and the hostModules keys; register "lib" as
        // a project module so the canonical encoder can represent
        // @lib/Item (the conformance harness's module-path
        // classification analog).
        Map<String, CanonicalModuleIdentity> byPath = new LinkedHashMap<>();
        byPath.put("", CanonicalModuleIdentity.BuiltinModule.INSTANCE);
        byPath.put(filename, new CanonicalModuleIdentity.ProjectModule(
            new ProjectModuleIdentity(filename, filename, List.of())));
        byPath.put("lib", new CanonicalModuleIdentity.ProjectModule(
            new ProjectModuleIdentity("lib", "lib", List.of())));
        ModuleIdentityResolver.IdentityIndex index =
            ModuleIdentityResolver.buildIndex(byPath);

        LexResult lex = new Lexer(
            "import * as lib from \"./lib\"\n" +
            "export function make(): lib.Item {\n" +
            "  return { value: 3 };\n" +
            "}\n", filename).tokenize();
        ParseResult parse = new Parser(lex.tokens(), filename).parse();
        NameResolver nr = new NameResolver(filename, resolver);
        SymbolTable symTable = nr.resolve(parse.program());
        CheckResult result = TypeChecker.check(filename, symTable, nr,
            parse.program());
        String lua = LuaBackend.generateWithImports(parse.program(),
            result, filename, filename, Map.of(), Map.of(), false,
            index);
        CompileResult out = new CompileResult(lua, parse.program(), result);

        assertThat(out.lua(), containsString(
            "__rt.class_plan_(\"@lib/Item\", lib.Item_plan, {value = 3}, "
                + "\"dealimport.deal\", 3, 10)"));
        assertThat(out.lua(), not(containsString("Item_defaults")));
    }

    /**
     * Depth >= 3 nested compiler-class fromJson decode (the ISSUE-0340
     * review-cycle fix): the generated nested class-field decoder walks
     * the sub-document's own class-typed fields through
     * {@code fdesc.fields} before {@code __rt.json_from_plan} validates
     * them, so X$fromJson of a document whose nested class field itself
     * contains a class field reconstructs tagged instances at every
     * level instead of silently yielding the DEAL null. Pins the emitted
     * walk plus the runtime round-trip under real LuaJIT.
     */
    @Test
    public void jsonableFromJsonNestedDepth3WalksSubFieldsBeforePlan()
            throws Exception {
        String source =
            "// @jsonable\n" +
            "export class Z {\n" +
            "  n: int = 0;\n" +
            "}\n" +
            "// @jsonable\n" +
            "export class Y {\n" +
            "  z: Z = {};\n" +
            "}\n" +
            "// @jsonable\n" +
            "export class X {\n" +
            "  y: Y = {};\n" +
            "}\n" +
            "export function test_jsonable_fromjson_nested_depth3(): null {\n" +
            "  let x: X | null = X$fromJson(\"{\\\"y\\\":{\\\"z\\\":{\\\"n\\\":7}}}\");\n" +
            "  if (x === null) { throw { code: \"TEST_FAIL\", message: \"depth3 fromJson null\" }; }\n" +
            "  if (x !== null) {\n" +
            "    if (x.y.z.n !== 7) { throw { code: \"TEST_FAIL\", message: \"depth3 value mismatch\" }; }\n" +
            "  }\n" +
            "  return null;\n" +
            "}\n";
        CompileResult out = compile(source);

        // The plan branch pre-tags the sub-document's own class-typed
        // fields before json_from_plan validates them.
        assertThat(out.lua(), containsString(
            "for _, sub in ipairs(fdesc.fields) do"));
        assertThat(out.lua(), containsString(
            "return __rt.json_from_plan(fdesc.className, fdesc.plan, raw)"));

        // Runtime round-trip: the depth-3 document decodes to a tagged
        // instance (TEST_FAIL would throw and fail the run otherwise).
        assumeLuajit();
        RunResult run = runLua(out.lua(), autoInvokeProbe());
        assertEquals("depth-3 fromJson round-trip must pass under "
            + "LuaJIT: " + run.output(), 0, run.exit());
    }

}
