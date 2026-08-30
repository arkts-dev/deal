package deal.test;

import deal.ast.*;
import deal.checker.*;
import deal.diagnostics.CompilerDiagnostic;
import deal.codegen.Backend;
import deal.codegen.SourceMapGenerator;
import deal.codegen.js.JsBackend;
import deal.codegen.lua.LuaBackend;
import deal.lexer.*;
import deal.module.CompilationOrchestrator;
import deal.parser.*;
import deal.types.Type;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Source map generation and round-trip tests.
 *
 * <p>Verifies:
 * <ul>
 *   <li>SourceMapGenerator records mappings during codegen</li>
 *   <li>JSON sidecar format matches the spec</li>
 *   <li>All mapping entries have positive positions within bounds</li>
 *   <li>Round-trip position assertions pass for known positions</li>
 *   <li>JS backend (js-v12-source-maps D4): emitter-side mapping
 *       recording through the optional SourceMapGenerator parameter,
 *       exact rebasing for sibling function expressions assembled on
 *       one emitted line (array-literal elements, function-typed class
 *       field defaults), exact rebasing for function-expression values
 *       embedded in arity-extension adapters (sync/async forms and
 *       sibling-shifted splice positions), orchestrator-side
 *       per-module
 *       .deal.map.json sidecar writes for every clean module
 *       (statement-less modules included, with an empty mappings
 *       array) with the --source-map warning retired, and node
 *       runtime-location pins (array bounds, division by zero, throw)
 *       reporting the original .deal file/line/column</li>
 * </ul>
 */
public class SourceMapTest {

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
    // Compile helper with source map
    // =========================================================================

    private record CompileResult(String lua, SourceMapGenerator smg,
                                  ProgramNode program, CheckResult result) {}

    private static CompileResult compileWithSourceMap(String source, String filename) {
        LexResult lex = new Lexer(source, filename).tokenize();
        ParseResult parse = new Parser(lex.tokens(), filename).parse();

        StubModuleResolver resolver = new StubModuleResolver();
        NameResolver nr = new NameResolver(filename, resolver);
        SymbolTable symTable = nr.resolve(parse.program());

        List<CompilerDiagnostic> diags = new ArrayList<>(nr.diagnostics());
        if (diags.stream().anyMatch(d -> "error".equals(d.severity()))) {
            fail("Compilation error in source: " + source.substring(0, Math.min(80, source.length())));
            return null;
        }

        CheckResult result = TypeChecker.check(filename, symTable, nr, parse.program());
        diags.addAll(result.diagnostics());
        if (diags.stream().anyMatch(d -> "error".equals(d.severity()))) {
            fail("Type error in source: " + source.substring(0, Math.min(80, source.length())));
            return null;
        }

        LuaBackend backend = new LuaBackend(result.typeMap(), result.symbolTable(), filename);
        String lua = backend.generateFromInstanceWithSourceMap(parse.program());
        SourceMapGenerator smg = backend.sourceMapGenerator();

        return new CompileResult(lua, smg, parse.program(), result);
    }

    // =========================================================================
    // JSON parsing helper (minimal)
    // =========================================================================

    private record MapEntry(int generatedLine, int generatedColumn,
                             int sourceLine, int sourceColumn) {}

    private static List<MapEntry> parseSourceMapJson(String json) {
        List<MapEntry> entries = new ArrayList<>();

        int mappingsIdx = json.indexOf("\"mappings\"");
        if (mappingsIdx < 0) return entries;

        int arrayStart = json.indexOf('[', mappingsIdx);
        int arrayEnd = json.lastIndexOf(']');
        if (arrayStart < 0 || arrayEnd < 0) return entries;

        String mappingsStr = json.substring(arrayStart + 1, arrayEnd);

        int pos = 0;
        while (pos < mappingsStr.length()) {
            int objStart = mappingsStr.indexOf('{', pos);
            if (objStart < 0) break;
            int objEnd = mappingsStr.indexOf('}', objStart);
            if (objEnd < 0) break;

            String obj = mappingsStr.substring(objStart + 1, objEnd);
            int gl = extractInt(obj, "generatedLine");
            int gc = extractInt(obj, "generatedColumn");
            int sl = extractInt(obj, "sourceLine");
            int sc = extractInt(obj, "sourceColumn");

            if (gl > 0 && sl > 0) {
                entries.add(new MapEntry(gl, gc, sl, sc));
            }

            pos = objEnd + 1;
        }

        return entries;
    }

    private static int extractInt(String json, String key) {
        int keyIdx = json.indexOf("\"" + key + "\"");
        if (keyIdx < 0) return -1;
        int colonIdx = json.indexOf(':', keyIdx);
        if (colonIdx < 0) return -1;
        int numStart = colonIdx + 1;
        while (numStart < json.length() && Character.isWhitespace(json.charAt(numStart)))
            numStart++;
        int numEnd = numStart;
        while (numEnd < json.length() && (Character.isDigit(json.charAt(numEnd)) || json.charAt(numEnd) == '-'))
            numEnd++;
        if (numEnd == numStart) return -1;
        try {
            return Integer.parseInt(json.substring(numStart, numEnd));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // =========================================================================
    // Main
    // =========================================================================

    public static void main(String[] args) {
        System.out.println("=== Running Source Map Tests ===");

        testSourceMapGenerated();
        testSourceMapJsonFormat();
        testSourceMapMappingBounds();
        testSourceMapRoundTrip();
        testSourceMapTrueRoundTrip();
        testSourceMapMultipleStatements();
        testSourceMapNoSourceMapFlag();
        testSourceMapGeneratedPath();
        testJsEmitterMappingRecording();
        testJsFunctionExprSiblingRebasing();
        testJsArityAdapterValueRebasing();
        testJsSidecarsRealPipeline();
        testJsSidecarStatementlessModule();
        testJsSidecarsDumpIrDerived();
        testJsSidecarRejectedModule();
        testJsNodeRuntimeLocations();

        System.out.println();
        System.out.println("Passed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    // =========================================================================
    // Test: Source map is generated
    // =========================================================================

    static void testSourceMapGenerated() {
        System.out.println("-- Source Map Generated --");

        String source =
            "let x: int = 1;\n" +
            "let y: string = \"hello\";\n";

        CompileResult out = compileWithSourceMap(source, "test.deal");
        if (out == null) return;

        check(out.smg != null, "source map generator is not null");
        check(out.smg.hasMappings(), "source map has at least one mapping");

        List<SourceMapGenerator.Mapping> mappings = out.smg.mappings();
        check(mappings.size() >= 2, "at least 2 mappings for 2 statements, got: "
            + mappings.size());
    }

    // =========================================================================
    // Test: Source map JSON format
    // =========================================================================

    static void testSourceMapJsonFormat() {
        System.out.println("-- Source Map JSON Format --");

        String source =
            "let x: int = 1;\n" +
            "let y: string = \"hello\";\n";

        CompileResult out = compileWithSourceMap(source, "test.deal");
        if (out == null) return;

        String json = out.smg.toJson("test.deal", "test.lua");
        check(json != null, "toJson returns non-null");
        check(json.contains("\"version\": 1"), "version is 1");
        check(json.contains("\"source\": \"test.deal\""), "source path present");
        check(json.contains("\"generated\": \"test.lua\""), "generated path present");
        check(json.contains("\"mappings\""), "mappings array present");

        // Verify it's valid JSON by parsing
        List<MapEntry> entries = parseSourceMapJson(json);
        check(!entries.isEmpty(), "parsed entries from JSON");

        // Check each entry has valid fields
        for (MapEntry e : entries) {
            check(e.generatedLine() > 0, "generatedLine > 0: " + e.generatedLine());
            check(e.generatedColumn() > 0, "generatedColumn > 0: " + e.generatedColumn());
            check(e.sourceLine() > 0, "sourceLine > 0: " + e.sourceLine());
            check(e.sourceColumn() > 0, "sourceColumn > 0: " + e.sourceColumn());
        }
    }

    // =========================================================================
    // Test: Mapping bounds
    // =========================================================================

    static void testSourceMapMappingBounds() {
        System.out.println("-- Source Map Mapping Bounds --");

        String source =
            "// line 1 comment\n" +
            "let a: int = 1;\n" +       // line 2
            "let b: int = 2;\n" +       // line 3
            "let c: int = 3;\n" +       // line 4
            "let d: int = 4;\n" +       // line 5
            "let e: int = 5;\n";        // line 6

        CompileResult out = compileWithSourceMap(source, "test.deal");
        if (out == null) return;

        List<SourceMapGenerator.Mapping> mappings = out.smg.mappings();

        check(mappings.size() >= 5, "at least 5 mappings for 5 statements, got: "
            + mappings.size());

        for (SourceMapGenerator.Mapping m : mappings) {
            check(m.sourceLine() >= 1 && m.sourceLine() <= 6,
                "sourceLine " + m.sourceLine() + " is within [1,6]");
            check(m.sourceColumn() >= 1,
                "sourceColumn " + m.sourceColumn() + " >= 1");
        }

        System.out.println("  Mappings: " + mappings.size());
        for (SourceMapGenerator.Mapping m : mappings) {
            System.out.println("    gen L" + m.generatedLine() + ":" + m.generatedColumn()
                + " -> src L" + m.sourceLine() + ":" + m.sourceColumn());
        }
    }

    // =========================================================================
    // Test: Round-trip position assertions (basic)
    // =========================================================================

    static void testSourceMapRoundTrip() {
        System.out.println("-- Source Map Round-Trip (Basic) --");

        String source =
            "export function test_func(a: int): int {\n" +   // line 1
            "  let x: int = a;\n" +                             // line 2
            "  let y: int = x + 1;\n" +                         // line 3
            "  let z: int = y * 2;\n" +                         // line 4
            "  if (z > 10) {\n" +                                // line 5
            "    return z;\n" +                                   // line 6
            "  }\n" +                                             // line 7
            "  return y;\n" +                                     // line 8
            "}\n";

        CompileResult out = compileWithSourceMap(source, "test.deal");
        if (out == null) return;

        List<SourceMapGenerator.Mapping> mappings = out.smg.mappings();
        check(!mappings.isEmpty(), "mappings generated for round-trip test");

        // Collect source lines that have mappings
        Set<Integer> mappedSourceLines = new HashSet<>();
        for (SourceMapGenerator.Mapping m : mappings) {
            mappedSourceLines.add(m.sourceLine());
        }

        check(mappedSourceLines.size() >= 3, "at least 3 distinct source lines mapped, got: "
            + mappedSourceLines.size());

        // Check that specific lines are mapped
        check(mappedSourceLines.contains(2), "line 2 is mapped");
        check(mappedSourceLines.contains(3), "line 3 is mapped");
        check(mappedSourceLines.contains(8), "line 8 is mapped");

        // Bounds checks
        for (SourceMapGenerator.Mapping m : mappings) {
            check(m.sourceLine() >= 1 && m.sourceLine() <= 9,
                "round-trip: sourceLine " + m.sourceLine() + " within [1,9]");
            check(m.sourceColumn() >= 1,
                "round-trip: sourceColumn " + m.sourceColumn() + " >= 1");
        }

        System.out.println("  " + mappedSourceLines.size()
            + " distinct source lines mapped");
    }

    // =========================================================================
    // Test: True round-trip — concrete source position maps to correct
    //        generated position
    // =========================================================================

    static void testSourceMapTrueRoundTrip() {
        System.out.println("-- Source Map True Round-Trip --");

        // A program with clearly identifiable source positions.
        // Line 3 has `let y: int = x + 1;` — we'll map this specific position.
        String source =
            "export function test_func(a: int): int {\n" +   // line 1
            "  let x: int = a;\n" +                             // line 2
            "  let y: int = x + 1;\n" +                         // line 3
            "  let z: int = y * 2;\n" +                         // line 4
            "  return z;\n" +                                    // line 5
            "}\n";

        CompileResult out = compileWithSourceMap(source, "test.deal");
        if (out == null) return;

        String lua = out.lua;
        List<SourceMapGenerator.Mapping> mappings = out.smg.mappings();

        check(!mappings.isEmpty(), "true round-trip: mappings exist");

        // Print all mappings for debugging
        System.out.println("  All mappings:");
        for (SourceMapGenerator.Mapping m : mappings) {
            System.out.println("    gen L" + m.generatedLine() + ":" + m.generatedColumn()
                + " -> src L" + m.sourceLine() + ":" + m.sourceColumn());
        }

        // ================================================================
        // Round-trip 1: For each mapping, verify the generated position
        // actually exists in the generated Lua at that line/column.
        // ================================================================
        String[] luaLines = lua.split("\n");
        int roundTripPasses = 0;

        for (SourceMapGenerator.Mapping m : mappings) {
            int genLine = m.generatedLine();
            int genCol = m.generatedColumn();

            // Verify the generated line exists
            if (genLine >= 1 && genLine <= luaLines.length) {
                String genLineText = luaLines[genLine - 1];
                // The column should reference a position within the line
                if (genCol >= 1 && genCol <= genLineText.length() + 1) {
                    roundTripPasses++;
                }
            }
        }

        check(roundTripPasses >= 3,
            "at least 3 mappings have valid generated positions, got: " + roundTripPasses);

        // ================================================================
        // Round-trip 2: Find the mapping for a known source position
        // (line 3, "let y") and verify it maps to a location in the
        // generated Lua that contains "let y" or the generated equivalent.
        // ================================================================
        boolean foundLine3 = false;
        for (SourceMapGenerator.Mapping m : mappings) {
            if (m.sourceLine() == 3) {
                foundLine3 = true;
                int genLine = m.generatedLine();
                // Verify the generated line exists
                check(genLine >= 1 && genLine <= luaLines.length,
                    "line 3 mapping: generated line " + genLine + " is valid");

                if (genLine >= 1 && genLine <= luaLines.length) {
                    String genText = luaLines[genLine - 1];
                    System.out.println("  Line 3 maps to generated line " + genLine
                        + ": " + genText.trim());
                    // The generated line should contain something related to y
                    check(genText.contains("y") || genText.contains("x"),
                        "generated line " + genLine + " is related to statement on source line 3");
                }
                break;
            }
        }
        check(foundLine3, "found mapping for source line 3");

        // ================================================================
        // Round-trip 3: Pick 5 known positions and verify they all have
        // corresponding mappings.
        // ================================================================
        Set<Integer> knownLines = Set.of(2, 3, 4, 5);
        int knownFound = 0;
        for (SourceMapGenerator.Mapping m : mappings) {
            if (knownLines.contains(m.sourceLine())) {
                knownFound++;
            }
        }
        check(knownFound == 4,
            "all 4 known lines have mappings, got: " + knownFound);

        // ================================================================
        // Round-trip 4: Verify generated positions are monotonically
        // increasing (mappings are emitted in order).
        // ================================================================
        int prevGenLine = 0;
        boolean monotonic = true;
        for (SourceMapGenerator.Mapping m : mappings) {
            if (m.generatedLine() < prevGenLine) {
                monotonic = false;
                break;
            }
            prevGenLine = m.generatedLine();
        }
        check(monotonic, "mappings are in monotonically increasing generated line order");
    }

    // =========================================================================
    // Test: Multiple statements produce distinct mappings
    // =========================================================================

    static void testSourceMapMultipleStatements() {
        System.out.println("-- Source Map Multiple Statements --");

        StringBuilder src = new StringBuilder();
        for (int i = 1; i <= 10; i++) {
            src.append("let v").append(i).append(": int = ").append(i).append(";\n");
        }

        CompileResult out = compileWithSourceMap(src.toString(), "test.deal");
        if (out == null) return;

        List<SourceMapGenerator.Mapping> mappings = out.smg.mappings();

        check(mappings.size() >= 10, "at least 10 mappings for 10 statements, got: "
            + mappings.size());

        Set<Integer> sourceLines = new HashSet<>();
        for (SourceMapGenerator.Mapping m : mappings) {
            sourceLines.add(m.sourceLine());
        }
        check(sourceLines.size() >= 8, "at least 8 distinct source lines mapped, got: "
            + sourceLines.size());
    }

    // =========================================================================
    // Test: No source map when not requested
    // =========================================================================

    static void testSourceMapNoSourceMapFlag() {
        System.out.println("-- Source Map Not Generated Without Flag --");

        String source = "let x: int = 1;\n";

        LexResult lex = new Lexer(source, "test.deal").tokenize();
        ParseResult parse = new Parser(lex.tokens(), "test.deal").parse();
        StubModuleResolver resolver = new StubModuleResolver();
        NameResolver nr = new NameResolver("test.deal", resolver);
        SymbolTable symTable = nr.resolve(parse.program());
        CheckResult result = TypeChecker.check("test.deal", symTable, nr, parse.program());

        LuaBackend backend = new LuaBackend(result.typeMap(), result.symbolTable(), "test.deal");
        String lua = backend.generateFromInstance(parse.program());

        check(lua != null && !lua.isEmpty(), "Lua generated without source map");
        check(backend.sourceMapGenerator() == null,
            "sourceMapGenerator is null when not requested");
    }

    // =========================================================================
    // Test: Generated path in JSON
    // =========================================================================

    static void testSourceMapGeneratedPath() {
        System.out.println("-- Source Map Generated Path --");

        String source = "let x: int = 1;\n";

        CompileResult out = compileWithSourceMap(source, "src/main.deal");
        if (out == null) return;

        String json = out.smg.toJson("src/main.deal", "build/lua/main.lua");
        check(json.contains("\"source\": \"src/main.deal\""), "source path correct");
        check(json.contains("\"generated\": \"build/lua/main.lua\""),
            "generated path correct");
    }

    // =========================================================================
    // JS cases (js-v12-source-maps D4)
    // =========================================================================

    private static boolean nodeAvailable = probeNode();

    /** Counts one toolchain skip (node unavailable) — a skip, never a
     * failure (the JsBackendTest node-case toolchain rule). */
    private static void skipNode(String reason) {
        System.out.println("  SKIP (node unavailable): " + reason);
    }

    private static boolean probeNode() {
        try {
            Process node = new ProcessBuilder("node", "--version")
                .redirectErrorStream(true).start();
            return node.waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    private static void deleteDir(Path dir) {
        try {
            Files.walk(dir).sorted(Comparator.reverseOrder())
                .forEach(f -> { try { Files.deleteIfExists(f); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}
    }

    /** The frontend compile shared by the JS cases (the JsBackendTest
     * adapter shape: checker module path {@code Main}, the
     * {@code StubModuleResolver}). */
    private record JsFrontend(ProgramNode program, CheckResult checkResult) {}

    private static JsFrontend parseChecked(String source, String filename) {
        LexResult lex = new Lexer(source, filename).tokenize();
        if (lex.hasErrors()) {
            fail("JS case lex errors: " + lex.diagnostics());
            return null;
        }
        ParseResult parse = new Parser(lex.tokens(), filename).parse();
        if (parse.hasErrors()) {
            fail("JS case parse errors: " + parse.diagnostics());
            return null;
        }
        StubModuleResolver resolver = new StubModuleResolver();
        NameResolver nr = new NameResolver("Main", resolver);
        SymbolTable symTable = nr.resolve(parse.program());
        List<CompilerDiagnostic> diags = new ArrayList<>(nr.diagnostics());
        CheckResult result = TypeChecker.check("Main", symTable, nr,
            parse.program());
        diags.addAll(result.diagnostics());
        if (diags.stream().anyMatch(d -> "error".equals(d.severity()))) {
            fail("JS case frontend errors: " + diags);
            return null;
        }
        return new JsFrontend(parse.program(), result);
    }

    /** 1-based line count of a multi-line string. */
    private static int lineCount(String text) {
        int lines = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') lines++;
        }
        return lines;
    }

    /** Extracts a string-valued JSON field ({@code "key": "value"}). */
    private static String extractString(String json, String key) {
        int keyIdx = json.indexOf("\"" + key + "\"");
        if (keyIdx < 0) return null;
        int colonIdx = json.indexOf(':', keyIdx);
        if (colonIdx < 0) return null;
        int valStart = json.indexOf('"', colonIdx);
        if (valStart < 0) return null;
        int valEnd = json.indexOf('"', valStart + 1);
        if (valEnd < 0) return null;
        return json.substring(valStart + 1, valEnd);
    }

    /**
     * Emitter-side recording (js-v12-source-maps D2): the optional
     * SourceMapGenerator parameter records one mapping at each generated
     * statement-group boundary — wrapper, body statement, and return
     * groups — with the generated position from the emitter's
     * output-buffer state mapped to the AST statement's span start. The
     * positions stay within the final artifact bounds and round-trip to
     * the statement's generated line. The 7-arg generate overload keeps
     * source-map recording disabled (null recorder).
     */
    static void testJsEmitterMappingRecording() {
        System.out.println("-- JS emitter: mapping recording at statement groups --");

        String source =
            "function helper(x: int): int { return x; }\n" +   // line 1
            "export function test(): int {\n" +                  // line 2
            "  let a: int = 1;\n" +                               // line 3
            "  let b: int = helper(a) + 2;\n" +                   // line 4
            "  let cb: () => int = function(): int { return b; };\n"
                +                                                 // line 5
            "  return b;\n" +                                     // line 6
            "}\n";                                                // line 7

        JsFrontend frontend = parseChecked(source, "jsmap-emitter.deal");
        if (frontend == null) return;

        SourceMapGenerator smg = new SourceMapGenerator();
        JsBackend.JsCodegenResult res = JsBackend.generate(frontend.program(),
            frontend.checkResult(), "jsmap-emitter.deal", "Main", Map.of(),
            Map.of(), false, smg);
        check(res != null && !res.hasErrors(), "JS emitter codegen clean: "
            + (res == null ? "<null>" : res.diagnostics()));
        if (res == null || res.hasErrors()) return;
        check(res.sourceMap() == smg, "the result carries the recorder");

        // The 7-arg overload disables recording (null recorder).
        JsBackend.JsCodegenResult plain = JsBackend.generate(frontend.program(),
            frontend.checkResult(), "jsmap-emitter.deal", "Main", Map.of(),
            Map.of(), false);
        check(plain.sourceMap() == null,
            "the recorder-less generate overload carries a null source map");

        List<SourceMapGenerator.Mapping> mappings = smg.mappings();
        check(smg.hasMappings(), "the recorder collected mappings");
        String[] artifactLines = res.source().split("\n", -1);

        // Statement-group coverage: the wrapper declarations (lines 1-2)
        // and the body statements (lines 3-5) each carry a mapping at
        // their span start (column 1 for declarations, column 3 for
        // indented body statements).
        check(hasMappingAt(mappings, 1, 1), "helper declaration mapped (1:1)");
        check(hasMappingAt(mappings, 2, 1), "test declaration mapped (2:1)");
        check(hasMappingAt(mappings, 3, 3), "let a mapped (3:3)");
        check(hasMappingAt(mappings, 4, 3), "let b mapped (4:3)");
        check(hasMappingAt(mappings, 5, 3),
            "function-expression declaration mapped (5:3)");
        check(hasMappingAt(mappings, 6, 3), "return b mapped (6:3)");

        // Bounds: every generated position lands inside the artifact,
        // every source position inside the source.
        int sourceLines = lineCount(source);
        boolean allInBounds = true;
        for (SourceMapGenerator.Mapping m : mappings) {
            if (m.generatedLine() < 1
                    || m.generatedLine() > artifactLines.length
                    || m.generatedColumn() < 1
                    || m.generatedColumn() > artifactLines[
                        m.generatedLine() - 1].length() + 1
                    || m.sourceLine() < 1 || m.sourceLine() > sourceLines
                    || m.sourceColumn() < 1) {
                allInBounds = false;
                fail("mapping out of bounds: " + m);
            }
        }
        check(allInBounds, "every mapping within source/generated bounds");

        // Round-trip: the pinned statements' mappings land on the
        // artifact lines carrying their generated code.
        for (SourceMapGenerator.Mapping m : mappings) {
            if (m.sourceLine() == 3 && m.sourceColumn() == 3) {
                check(artifactLines[m.generatedLine() - 1].contains("let a"),
                    "line 3 round-trips to the 'let a' artifact line: "
                        + artifactLines[m.generatedLine() - 1]);
            }
            if (m.sourceLine() == 4 && m.sourceColumn() == 3) {
                check(artifactLines[m.generatedLine() - 1].contains("helper.$f"),
                    "line 4 round-trips to the helper.$f call line: "
                        + artifactLines[m.generatedLine() - 1]);
            }
            if (m.sourceLine() == 5 && m.sourceColumn() == 41) {
                check(artifactLines[m.generatedLine() - 1]
                        .contains("return $rt.checkInt(b"),
                    "the function-expression body statement round-trips "
                        + "to the capture-rebased artifact line: "
                        + artifactLines[m.generatedLine() - 1]);
            }
        }

        // Generated positions are monotonically increasing (emission order).
        int prevGenLine = 0;
        boolean monotonic = true;
        for (SourceMapGenerator.Mapping m : mappings) {
            if (m.generatedLine() < prevGenLine) {
                monotonic = false;
                break;
            }
            prevGenLine = m.generatedLine();
        }
        check(monotonic, "mappings are in monotonically increasing generated line order");

        System.out.println("  Mappings: " + mappings.size());
        for (SourceMapGenerator.Mapping m : mappings) {
            System.out.println("    gen L" + m.generatedLine() + ":" + m.generatedColumn()
                + " -> src L" + m.sourceLine() + ":" + m.sourceColumn());
        }
    }

    private static boolean hasMappingAt(List<SourceMapGenerator.Mapping> mappings,
                                        int sourceLine, int sourceColumn) {
        for (SourceMapGenerator.Mapping m : mappings) {
            if (m.sourceLine() == sourceLine
                    && m.sourceColumn() == sourceColumn) {
                return true;
            }
        }
        return false;
    }

    /**
     * Sibling function expressions on one emitted line
     * (js-v12-source-maps D2 exactness): a statement's expression text
     * is assembled in memory before {@code line()} splices it into the
     * artifact, so when one statement carries two or more function
     * expressions (array-literal elements, object-literal properties,
     * call arguments, function-typed class field defaults), each
     * wrapper and each captured body statement must
     * map to the artifact line carrying its OWN generated code — the
     * capture splice rebase adds the in-flight newlines of the
     * expression text already assembled on the current line, and the
     * second wrapper's body never re-maps to the first wrapper's
     * lines (the review-cycle-2 positional defect). The generated-line
     * sequence stays monotonically increasing.
     */
    static void testJsFunctionExprSiblingRebasing() {
        System.out.println(
            "-- JS emitter: sibling function-expression rebasing --");

        // Array-literal case: two function expressions in one statement
        // (the first wrapper/body on the statement's own artifact line
        // and the next; the second wrapper/body on the following two).
        String arraySource =
            "export function main(): null {\n" +                     // line 1
            "  let fs: (() => int)[] = [function(): int { return 1; }, function(): int { return 2; }];\n"
                +                                                     // line 2
            "  return null;\n" +                                     // line 3
            "}\n";                                                   // line 4
        String[] arrayLines = arraySource.split("\n", -1);
        int aWrap1 = arrayLines[1].indexOf("function") + 1;
        int aRet1 = arrayLines[1].indexOf("return 1") + 1;
        int aWrap2 = arrayLines[1].indexOf("function", aWrap1) + 1;
        int aRet2 = arrayLines[1].indexOf("return 2") + 1;

        JsFrontend frontend = parseChecked(arraySource,
            "jsmap-siblings.deal");
        if (frontend == null) return;
        SourceMapGenerator smg = new SourceMapGenerator();
        JsBackend.JsCodegenResult res = JsBackend.generate(
            frontend.program(), frontend.checkResult(),
            "jsmap-siblings.deal", "Main", Map.of(), Map.of(), false, smg);
        check(res != null && !res.hasErrors(), "sibling codegen clean: "
            + (res == null ? "<null>" : res.diagnostics()));
        if (res == null || res.hasErrors()) return;

        String[] artifactLines = res.source().split("\n", -1);
        List<SourceMapGenerator.Mapping> mappings = smg.mappings();
        checkSiblingGroup(mappings, artifactLines, 2, aWrap1, aRet1,
            "first array wrapper", "let fs", "checkInt(1",
            "$rt.function(\"()->int\"");
        checkSiblingGroup(mappings, artifactLines, 2, aWrap2, aRet2,
            "second array wrapper", "}), $rt.function", "checkInt(2",
            "$rt.function(\"()->int\"");
        int aWrap1Gen = generatedLineOf(mappings, 2, aWrap1);
        int aWrap2Gen = generatedLineOf(mappings, 2, aWrap2);
        int aRet1Gen = generatedLineOf(mappings, 2, aRet1);
        int aRet2Gen = generatedLineOf(mappings, 2, aRet2);
        check(aWrap1Gen < aRet1Gen && aRet1Gen < aWrap2Gen
                && aWrap2Gen < aRet2Gen,
            "array sibling positions strictly increase "
                + "(wrapper1 " + aWrap1Gen + " < body1 " + aRet1Gen
                + " < wrapper2 " + aWrap2Gen + " < body2 " + aRet2Gen
                + ")");

        // Class-field case: two function-typed defaults in one class
        // declaration (the defaults thunk assembles both on one
        // artifact line).
        String classSource =
            "class Box {\n" +                                         // line 1
            "  a: () => int = function(): int { return 1; };\n" +    // line 2
            "  b: () => int = function(): int { return 2; };\n" +    // line 3
            "}\n" +                                                   // line 4
            "export function main(): null { return null; }\n";       // line 5
        String[] classLines = classSource.split("\n", -1);
        int cWrap1 = classLines[1].indexOf("function") + 1;
        int cRet1 = classLines[1].indexOf("return 1") + 1;
        int cWrap2 = classLines[2].indexOf("function") + 1;
        int cRet2 = classLines[2].indexOf("return 2") + 1;

        JsFrontend clsFrontend = parseChecked(classSource,
            "jsmap-class-defaults.deal");
        if (clsFrontend == null) return;
        SourceMapGenerator clsSmg = new SourceMapGenerator();
        JsBackend.JsCodegenResult clsRes = JsBackend.generate(
            clsFrontend.program(), clsFrontend.checkResult(),
            "jsmap-class-defaults.deal", "Main", Map.of(), Map.of(),
            false, clsSmg);
        check(clsRes != null && !clsRes.hasErrors(),
            "class-default codegen clean: "
                + (clsRes == null ? "<null>" : clsRes.diagnostics()));
        if (clsRes == null || clsRes.hasErrors()) return;

        String[] clsArtifact = clsRes.source().split("\n", -1);
        List<SourceMapGenerator.Mapping> clsMappings = clsSmg.mappings();
        checkSiblingGroup(clsMappings, clsArtifact, 2, cWrap1, cRet1,
            "first class default wrapper", "[\"a\"]: $rt.function",
            "checkInt(1", "$rt.function(\"()->int\"");
        checkSiblingGroup(clsMappings, clsArtifact, 3, cWrap2, cRet2,
            "second class default wrapper", "[\"b\"]: $rt.function",
            "checkInt(2", "$rt.function(\"()->int\"");
        int cWrap1Gen = generatedLineOf(clsMappings, 2, cWrap1);
        int cRet1Gen = generatedLineOf(clsMappings, 2, cRet1);
        int cWrap2Gen = generatedLineOf(clsMappings, 3, cWrap2);
        int cRet2Gen = generatedLineOf(clsMappings, 3, cRet2);
        check(cWrap1Gen < cRet1Gen && cRet1Gen < cWrap2Gen
                && cWrap2Gen < cRet2Gen,
            "class-default positions strictly increase "
                + "(wrapper1 " + cWrap1Gen + " < body1 " + cRet1Gen
                + " < wrapper2 " + cWrap2Gen + " < body2 " + cRet2Gen
                + ")");

        // Object-literal case: two function-typed properties in one
        // literal (both wrappers land on the literal's own artifact
        // line group).
        String objectSource =
            "export function main(): null {\n" +                    // line 1
            "  let o = { a: function(): int { return 3; }, b: function(): int { return 4; } };\n"
                +                                                    // line 2
            "  return null;\n" +                                   // line 3
            "}\n";                                                 // line 4
        String[] objectLines = objectSource.split("\n", -1);
        int oWrap1 = objectLines[1].indexOf("function") + 1;
        int oRet1 = objectLines[1].indexOf("return 3") + 1;
        int oWrap2 = objectLines[1].indexOf("function", oWrap1) + 1;
        int oRet2 = objectLines[1].indexOf("return 4") + 1;

        JsFrontend objFrontend = parseChecked(objectSource,
            "jsmap-sibling-object.deal");
        if (objFrontend == null) return;
        SourceMapGenerator objSmg = new SourceMapGenerator();
        JsBackend.JsCodegenResult objRes = JsBackend.generate(
            objFrontend.program(), objFrontend.checkResult(),
            "jsmap-sibling-object.deal", "Main", Map.of(), Map.of(),
            false, objSmg);
        check(objRes != null && !objRes.hasErrors(),
            "object-literal codegen clean: "
                + (objRes == null ? "<null>" : objRes.diagnostics()));
        if (objRes == null || objRes.hasErrors()) return;

        String[] objArtifact = objRes.source().split("\n", -1);
        List<SourceMapGenerator.Mapping> objMappings = objSmg.mappings();
        checkSiblingGroup(objMappings, objArtifact, 2, oWrap1, oRet1,
            "first object property wrapper", "[\"a\"]: $rt.function",
            "checkInt(3", "$rt.function(\"()->int\"");
        checkSiblingGroup(objMappings, objArtifact, 2, oWrap2, oRet2,
            "second object property wrapper", "[\"b\"]: $rt.function",
            "checkInt(4", "$rt.function(\"()->int\"");
        int oWrap1Gen = generatedLineOf(objMappings, 2, oWrap1);
        int oRet1Gen = generatedLineOf(objMappings, 2, oRet1);
        int oWrap2Gen = generatedLineOf(objMappings, 2, oWrap2);
        int oRet2Gen = generatedLineOf(objMappings, 2, oRet2);
        check(oWrap1Gen < oRet1Gen && oRet1Gen < oWrap2Gen
                && oWrap2Gen < oRet2Gen,
            "object-literal positions strictly increase "
                + "(wrapper1 " + oWrap1Gen + " < body1 " + oRet1Gen
                + " < wrapper2 " + oWrap2Gen + " < body2 " + oRet2Gen
                + ")");

        // Call-argument case: two function-expression arguments in one
        // call statement.
        String callSource =
            "export function consume(a: () => int, b: () => int): int { return a() + b(); }\n"
                +                                                  // line 1
            "export function main(): null {\n" +                  // line 2
            "  consume(function(): int { return 5; }, function(): int { return 6; });\n"
                +                                                  // line 3
            "  return null;\n" +                                  // line 4
            "}\n";                                                // line 5
        String[] callLines = callSource.split("\n", -1);
        int pWrap1 = callLines[2].indexOf("function") + 1;
        int pRet1 = callLines[2].indexOf("return 5") + 1;
        int pWrap2 = callLines[2].indexOf("function", pWrap1) + 1;
        int pRet2 = callLines[2].indexOf("return 6") + 1;

        JsFrontend callFrontend = parseChecked(callSource,
            "jsmap-sibling-call.deal");
        if (callFrontend == null) return;
        SourceMapGenerator callSmg = new SourceMapGenerator();
        JsBackend.JsCodegenResult callRes = JsBackend.generate(
            callFrontend.program(), callFrontend.checkResult(),
            "jsmap-sibling-call.deal", "Main", Map.of(), Map.of(),
            false, callSmg);
        check(callRes != null && !callRes.hasErrors(),
            "call-argument codegen clean: "
                + (callRes == null ? "<null>" : callRes.diagnostics()));
        if (callRes == null || callRes.hasErrors()) return;

        String[] callArtifact = callRes.source().split("\n", -1);
        List<SourceMapGenerator.Mapping> callMappings = callSmg.mappings();
        checkSiblingGroup(callMappings, callArtifact, 3, pWrap1, pRet1,
            "first call argument wrapper", "consume.$f($rt.function",
            "checkInt(5", "$rt.function(\"()->int\"");
        checkSiblingGroup(callMappings, callArtifact, 3, pWrap2, pRet2,
            "second call argument wrapper", "}), $rt.function",
            "checkInt(6", "$rt.function(\"()->int\"");
        int pWrap1Gen = generatedLineOf(callMappings, 3, pWrap1);
        int pRet1Gen = generatedLineOf(callMappings, 3, pRet1);
        int pWrap2Gen = generatedLineOf(callMappings, 3, pWrap2);
        int pRet2Gen = generatedLineOf(callMappings, 3, pRet2);
        check(pWrap1Gen < pRet1Gen && pRet1Gen < pWrap2Gen
                && pWrap2Gen < pRet2Gen,
            "call-argument positions strictly increase "
                + "(wrapper1 " + pWrap1Gen + " < body1 " + pRet1Gen
                + " < wrapper2 " + pWrap2Gen + " < body2 " + pRet2Gen
                + ")");

        // The whole sequence stays monotonic in every case.
        check(monotonicGeneratedLines(mappings),
            "array case generated lines monotonically increase");
        check(monotonicGeneratedLines(clsMappings),
            "class case generated lines monotonically increase");
        check(monotonicGeneratedLines(objMappings),
            "object-literal case generated lines monotonically increase");
        check(monotonicGeneratedLines(callMappings),
            "call-argument case generated lines monotonically increase");
    }

    /**
     * One wrapper/body pair's exact rebasing pins: the wrapper mapping
     * lands on the artifact line carrying the wrapper's opening (which
     * must also carry the distinguishing marker), and the body mapping
     * lands on the artifact line carrying its own generated return.
     */
    private static void checkSiblingGroup(
            List<SourceMapGenerator.Mapping> mappings,
            String[] artifactLines, int sourceLine, int wrapCol,
            int bodyCol, String label, String wrapMarker,
            String bodyMarker, String wrapperSig) {
        int wrapGen = generatedLineOf(mappings, sourceLine, wrapCol);
        int bodyGen = generatedLineOf(mappings, sourceLine, bodyCol);
        check(wrapGen > 0, label + " mapping exists at "
            + sourceLine + ":" + wrapCol);
        check(bodyGen > 0, label + " body mapping exists at "
            + sourceLine + ":" + bodyCol);
        if (wrapGen > 0) {
            check(artifactLines[wrapGen - 1].contains(wrapMarker)
                    && artifactLines[wrapGen - 1].contains(wrapperSig),
                label + " round-trips to its own wrapper line: "
                    + artifactLines[wrapGen - 1]);
        }
        if (bodyGen > 0) {
            check(artifactLines[bodyGen - 1].contains(bodyMarker),
                label + " body round-trips to its own return line: "
                    + artifactLines[bodyGen - 1]);
        }
    }

    /** The generated line recorded for the mapping pinned at the given
     * source position, or 0 when absent. */
    private static int generatedLineOf(
            List<SourceMapGenerator.Mapping> mappings, int sourceLine,
            int sourceColumn) {
        for (SourceMapGenerator.Mapping m : mappings) {
            if (m.sourceLine() == sourceLine
                    && m.sourceColumn() == sourceColumn) {
                return m.generatedLine();
            }
        }
        return 0;
    }

    private static boolean monotonicGeneratedLines(
            List<SourceMapGenerator.Mapping> mappings) {
        int prev = 0;
        for (SourceMapGenerator.Mapping m : mappings) {
            if (m.generatedLine() < prev) return false;
            prev = m.generatedLine();
        }
        return true;
    }

    /**
     * Arity-adapter-embedded function-expression values
     * (js-v12-source-maps D2 exactness, review-cycle-3 correction):
     * when a function-typed target declares more parameters than the
     * value, the arity-extension adapter assembles its structural
     * prefix (wrapper-opening and extended-parameter check lines)
     * before splicing the pre-emitted value text into its return
     * line — the value's wrapper and captured-body mappings must
     * rebase past those structural lines to the artifact lines
     * carrying their OWN generated code, never stay pinned to the
     * enclosing statement line (the adapter's opening line). Covers
     * the sync embedded form, the async embedded form, and the
     * sibling case where expression text already assembled on the
     * statement's line shifts the splice position further.
     */
    static void testJsArityAdapterValueRebasing() {
        System.out.println("-- JS emitter: arity-adapter value rebasing --");

        // Sync embedded form: the zero-parameter value crosses into the
        // one-parameter target; the adapter's structural prefix is its
        // wrapper-opening line plus one extended-parameter check line.
        String syncSource =
            "export function main(): null {\n" +                    // line 1
            "  let f: (a: int) => int = function(): int { return 1; };\n"
                +                                                    // line 2
            "  return null;\n" +                                    // line 3
            "}\n";                                                  // line 4
        String[] syncLines = syncSource.split("\n", -1);
        int sWrap = syncLines[1].indexOf("function") + 1;
        int sBody = syncLines[1].indexOf("return 1") + 1;

        JsFrontend syncFrontend = parseChecked(syncSource,
            "jsmap-adapter-sync.deal");
        if (syncFrontend == null) return;
        SourceMapGenerator syncSmg = new SourceMapGenerator();
        JsBackend.JsCodegenResult syncRes = JsBackend.generate(
            syncFrontend.program(), syncFrontend.checkResult(),
            "jsmap-adapter-sync.deal", "Main", Map.of(), Map.of(),
            false, syncSmg);
        check(syncRes != null && !syncRes.hasErrors(),
            "sync adapter codegen clean: "
                + (syncRes == null ? "<null>" : syncRes.diagnostics()));
        if (syncRes == null || syncRes.hasErrors()) return;

        String[] syncArtifact = syncRes.source().split("\n", -1);
        List<SourceMapGenerator.Mapping> syncMappings = syncSmg.mappings();
        int sWrapGen = generatedLineOf(syncMappings, 2, sWrap);
        int sBodyGen = generatedLineOf(syncMappings, 2, sBody);
        check(sWrapGen > 0, "sync adapter wrapper mapping exists at 2:"
            + sWrap);
        check(sBodyGen > 0, "sync adapter body mapping exists at 2:"
            + sBody);
        if (sWrapGen > 0) {
            String wrapLine = syncArtifact[sWrapGen - 1];
            check(wrapLine.contains("$rt.function(\"()->int\"")
                    && wrapLine.contains("return $rt.checkInt(")
                    && !wrapLine.contains("$p0"),
                "sync adapter wrapper round-trips to the adapter return "
                    + "line carrying its own opening (never the adapter "
                    + "opening line): " + wrapLine);
        }
        if (sBodyGen > 0) {
            check(syncArtifact[sBodyGen - 1]
                    .contains("return $rt.checkInt(1"),
                "sync adapter body round-trips to its own return line: "
                    + syncArtifact[sBodyGen - 1]);
        }
        check(sWrapGen > 0 && sBodyGen > 0 && sWrapGen < sBodyGen,
            "sync adapter positions strictly increase (wrapper "
                + sWrapGen + " < body " + sBodyGen + ")");
        check(monotonicGeneratedLines(syncMappings),
            "sync adapter case generated lines monotonically increase");

        // Async embedded form: the async target's adapter carries the
        // same structural prefix shape around the embedded async value.
        String asyncSource =
            "export async function g(): int { return 7; }\n" +       // line 1
            "export function main(): null {\n" +                     // line 2
            "  let f: async (a: int) => int = async function(): int { return await g(); };\n"
                +                                                    // line 3
            "  return null;\n" +                                    // line 4
            "}\n";                                                  // line 5
        String[] asyncLines = asyncSource.split("\n", -1);
        // The async FunctionExpr span starts at the 'async' keyword
        // (the emitter records the span start), so the wrapper pin
        // anchors on the value's 'async function' spelling.
        int aWrap = asyncLines[2].indexOf("async function") + 1;
        int aBody = asyncLines[2].indexOf("return", aWrap) + 1;

        JsFrontend asyncFrontend = parseChecked(asyncSource,
            "jsmap-adapter-async.deal");
        if (asyncFrontend == null) return;
        SourceMapGenerator asyncSmg = new SourceMapGenerator();
        JsBackend.JsCodegenResult asyncRes = JsBackend.generate(
            asyncFrontend.program(), asyncFrontend.checkResult(),
            "jsmap-adapter-async.deal", "Main", Map.of(), Map.of(),
            false, asyncSmg);
        check(asyncRes != null && !asyncRes.hasErrors(),
            "async adapter codegen clean: "
                + (asyncRes == null ? "<null>" : asyncRes.diagnostics()));
        if (asyncRes == null || asyncRes.hasErrors()) return;

        String[] asyncArtifact = asyncRes.source().split("\n", -1);
        List<SourceMapGenerator.Mapping> asyncMappings = asyncSmg.mappings();
        int aWrapGen = generatedLineOf(asyncMappings, 3, aWrap);
        int aBodyGen = generatedLineOf(asyncMappings, 3, aBody);
        check(aWrapGen > 0, "async adapter wrapper mapping exists at 3:"
            + aWrap);
        check(aBodyGen > 0, "async adapter body mapping exists at 3:"
            + aBody);
        if (aWrapGen > 0) {
            String wrapLine = asyncArtifact[aWrapGen - 1];
            check(wrapLine.contains("$rt.function(\"async()->int\"")
                    && wrapLine.contains("return $rt.function(")
                    && !wrapLine.contains("$p0"),
                "async adapter wrapper round-trips to the adapter return "
                    + "line carrying its own opening (never the adapter "
                    + "opening line): " + wrapLine);
        }
        if (aBodyGen > 0) {
            check(asyncArtifact[aBodyGen - 1]
                    .contains("return $rt.checkInt((await g.$f("),
                "async adapter body round-trips to its own return line: "
                    + asyncArtifact[aBodyGen - 1]);
        }
        check(aWrapGen > 0 && aBodyGen > 0 && aWrapGen < aBodyGen,
            "async adapter positions strictly increase (wrapper "
                + aWrapGen + " < body " + aBodyGen + ")");
        check(monotonicGeneratedLines(asyncMappings),
            "async adapter case generated lines monotonically increase");

        // Sibling case: an adapter value in expression position after a
        // sibling function expression already assembled on the
        // statement's line — the value's splice position rebases past
        // the sibling's body lines AND the adapter's structural prefix.
        String siblingSource =
            "export function consume(a: () => int, b: (x: int) => int): int { return a() + b(1); }\n"
                +                                                    // line 1
            "let f: (a: int) => int = function(a: int): int { return a; };\n"
                +                                                    // line 2
            "export function main(): int {\n" +                      // line 3
            "  return consume(function(): int { return 2; }, f = function(): int { return 1; });\n"
                +                                                    // line 4
            "}\n";                                                  // line 5
        String[] siblingLines = siblingSource.split("\n", -1);
        int sibWrap = siblingLines[3].indexOf("function", 30) + 1;
        int sibBody = siblingLines[3].indexOf("return 1") + 1;

        JsFrontend siblingFrontend = parseChecked(siblingSource,
            "jsmap-adapter-sibling.deal");
        if (siblingFrontend == null) return;
        SourceMapGenerator siblingSmg = new SourceMapGenerator();
        JsBackend.JsCodegenResult siblingRes = JsBackend.generate(
            siblingFrontend.program(), siblingFrontend.checkResult(),
            "jsmap-adapter-sibling.deal", "Main", Map.of(), Map.of(),
            false, siblingSmg);
        check(siblingRes != null && !siblingRes.hasErrors(),
            "sibling adapter codegen clean: "
                + (siblingRes == null ? "<null>"
                    : siblingRes.diagnostics()));
        if (siblingRes == null || siblingRes.hasErrors()) return;

        String[] siblingArtifact = siblingRes.source().split("\n", -1);
        List<SourceMapGenerator.Mapping> siblingMappings =
            siblingSmg.mappings();
        int sibWrapGen = generatedLineOf(siblingMappings, 4, sibWrap);
        int sibBodyGen = generatedLineOf(siblingMappings, 4, sibBody);
        check(sibWrapGen > 0, "sibling adapter wrapper mapping exists at 4:"
            + sibWrap);
        check(sibBodyGen > 0, "sibling adapter body mapping exists at 4:"
            + sibBody);
        if (sibWrapGen > 0) {
            String wrapLine = siblingArtifact[sibWrapGen - 1];
            check(wrapLine.contains("$rt.function(\"()->int\"")
                    && wrapLine.contains("return $rt.checkInt("),
                "sibling adapter wrapper round-trips to the line "
                    + "carrying its own opening past the sibling's body "
                    + "lines: " + wrapLine);
        }
        if (sibBodyGen > 0) {
            check(siblingArtifact[sibBodyGen - 1]
                    .contains("return $rt.checkInt(1"),
                "sibling adapter body round-trips to its own return "
                    + "line: " + siblingArtifact[sibBodyGen - 1]);
        }
        check(sibWrapGen > 0 && sibBodyGen > 0 && sibWrapGen < sibBodyGen,
            "sibling adapter positions strictly increase (wrapper "
                + sibWrapGen + " < body " + sibBodyGen + ")");
        check(monotonicGeneratedLines(siblingMappings),
            "sibling adapter case generated lines monotonically "
                + "increase");
    }

    /**
     * The real-pipeline sidecar contract (js-v12-source-maps D1/D4):
     * an explicit-flag --source-map compile through the orchestrator
     * prints no warning and writes one {@code <module>.deal.map.json}
     * per clean module with the spec format fields, bounded mappings,
     * per-statement coverage, and round-trip positions — the artifacts
     * the orchestrator serializes from the emitter's recorded mappings,
     * so an emitter/orchestrator divergence breaks the round-trip pins.
     */
    static void testJsSidecarsRealPipeline() {
        System.out.println("-- JS sidecars: real pipeline --");
        Path proj = null;
        try {
            proj = Files.createTempDirectory("sourcemap_js_");
            Path src = proj.resolve("src");
            Files.createDirectories(src);
            Path mainSrc = src.resolve("main.deal");
            Files.writeString(mainSrc, """
                import * as lib from "./lib"
                export function main(): null {
                  let a: int = 1;
                  let b: int = lib.count();
                  return null;
                }
                """);
            Files.writeString(src.resolve("lib.deal"),
                "export function count(): int { return 7; }\n");

            Path outputRoot = proj.resolve("build/js");
            PrintStream originalErr = System.err;
            ByteArrayOutputStream captured = new ByteArrayOutputStream();
            boolean ok;
            try {
                System.setErr(new PrintStream(captured, true,
                    StandardCharsets.UTF_8));
                CompilationOrchestrator orchestrator =
                    new CompilationOrchestrator(mainSrc, outputRoot, false,
                        false, true, Backend.JS, (deal.module.DealConfig) null,
                        List.of(src), Path.of(".").toAbsolutePath().normalize());
                ok = orchestrator.compile();
            } finally {
                System.err.flush();
                System.setErr(originalErr);
            }
            check(ok, "the explicit --source-map JS compile succeeds");
            check(!captured.toString(StandardCharsets.UTF_8)
                    .contains("source-map"),
                "the explicit --source-map JS run prints no warning: "
                    + captured.toString(StandardCharsets.UTF_8));

            // One sidecar per clean module, next to the artifact.
            Path mainArtifact = outputRoot.resolve("main.js");
            Path mainSidecar = outputRoot.resolve("main.deal.map.json");
            Path libArtifact = outputRoot.resolve("lib.js");
            Path libSidecar = outputRoot.resolve("lib.deal.map.json");
            check(Files.exists(mainArtifact), "main.js emitted");
            check(Files.exists(mainSidecar), "main.deal.map.json emitted");
            check(Files.exists(libArtifact), "lib.js emitted");
            check(Files.exists(libSidecar), "lib.deal.map.json emitted");

            if (Files.exists(mainSidecar)) {
                String json = Files.readString(mainSidecar);
                check(json.contains("\"version\": 1"),
                    "sidecar version is 1");
                check("src/main.deal".equals(extractString(json, "source")),
                    "sidecar source is the project-relative .deal path: "
                        + extractString(json, "source"));
                check("build/js/main.js".equals(extractString(json, "generated")),
                    "sidecar generated is the project-relative artifact "
                        + "path: " + extractString(json, "generated"));
                List<MapEntry> entries = parseSourceMapJson(json);
                check(!entries.isEmpty(), "main sidecar carries mappings");

                String[] artifactLines = Files.readString(mainArtifact)
                    .split("\n", -1);
                String source = Files.readString(mainSrc);
                int sourceLines = lineCount(source);
                boolean inBounds = true;
                for (MapEntry e : entries) {
                    if (e.generatedLine() < 1
                            || e.generatedLine() > artifactLines.length
                            || e.generatedColumn() < 1
                            || e.generatedColumn() > artifactLines[
                                e.generatedLine() - 1].length() + 1
                            || e.sourceLine() < 1
                            || e.sourceLine() > sourceLines
                            || e.sourceColumn() < 1) {
                        inBounds = false;
                        fail("main sidecar mapping out of bounds: " + e);
                    }
                }
                check(inBounds, "every main mapping within bounds");

                // Per-statement coverage: source lines 3-5 (the pinned
                // statements) each carry at least one mapping at the
                // span start (column 3).
                for (int line : new int[] { 3, 4, 5 }) {
                    boolean found = false;
                    for (MapEntry e : entries) {
                        if (e.sourceLine() == line && e.sourceColumn() == 3) {
                            found = true;
                            break;
                        }
                    }
                    check(found, "source line " + line + " has a mapping");
                }

                // Round-trip: the pinned statements' mappings land on
                // the artifact lines carrying their generated code, and
                // the entry-shim mapping (the main declaration span,
                // 2:8) lands on the shim's source-comment site.
                for (MapEntry e : entries) {
                    if (e.sourceLine() == 3 && e.sourceColumn() == 3) {
                        check(artifactLines[e.generatedLine() - 1]
                                .contains("let a"),
                            "line 3 round-trips to the 'let a' artifact "
                                + "line: "
                                + artifactLines[e.generatedLine() - 1]);
                    }
                    if (e.sourceLine() == 4 && e.sourceColumn() == 3) {
                        check(artifactLines[e.generatedLine() - 1]
                                .contains("lib.count.$f"),
                            "line 4 round-trips to the lib.count.$f call "
                                + "line: "
                                + artifactLines[e.generatedLine() - 1]);
                    }
                    if (e.sourceLine() == 2 && e.sourceColumn() == 8) {
                        check(artifactLines[e.generatedLine() - 1]
                                .contains("Entry invocation"),
                            "the entry-shim mapping (2:8) lands on the "
                                + "shim's source-comment site: "
                                + artifactLines[e.generatedLine() - 1]);
                    }
                }
            }
            if (Files.exists(libSidecar)) {
                String json = Files.readString(libSidecar);
                check(json.contains("\"version\": 1"),
                    "lib sidecar version is 1");
                check("src/lib.deal".equals(extractString(json, "source")),
                    "lib sidecar source path correct: "
                        + extractString(json, "source"));
                check("build/js/lib.js".equals(extractString(json, "generated")),
                    "lib sidecar generated path correct: "
                        + extractString(json, "generated"));
                check(!parseSourceMapJson(json).isEmpty(),
                    "lib sidecar carries mappings");
            }
        } catch (IOException e) {
            fail("JS sidecar pipeline threw: " + e);
        } finally {
            if (proj != null) deleteDir(proj);
        }
    }

    /**
     * One sidecar per clean module, statement-less modules included
     * (js-v12-source-maps D1): a zero-byte sibling module — the
     * pipeline accepts empty sources and emits their {@code .js}
     * artifact — writes its {@code .deal.map.json} sidecar next to the
     * artifact with the spec fields and an empty mappings array. No
     * hasMappings() gate may skip the write.
     */
    static void testJsSidecarStatementlessModule() {
        System.out.println("-- JS sidecars: statement-less module sidecar --");
        Path proj = null;
        try {
            proj = Files.createTempDirectory("sourcemap_js_empty_");
            Path src = proj.resolve("src");
            Files.createDirectories(src);
            Path mainSrc = src.resolve("main.deal");
            Files.writeString(mainSrc, """
                import * as e from "./empty"
                export function main(): null {
                  return null;
                }
                """);
            Files.writeString(src.resolve("empty.deal"), "");

            Path outputRoot = proj.resolve("build/js");
            PrintStream originalErr = System.err;
            ByteArrayOutputStream captured = new ByteArrayOutputStream();
            boolean ok;
            try {
                System.setErr(new PrintStream(captured, true,
                    StandardCharsets.UTF_8));
                CompilationOrchestrator orchestrator =
                    new CompilationOrchestrator(mainSrc, outputRoot, false,
                        false, true, Backend.JS,
                        (deal.module.DealConfig) null, List.of(src),
                        Path.of(".").toAbsolutePath().normalize());
                ok = orchestrator.compile();
            } finally {
                System.err.flush();
                System.setErr(originalErr);
            }
            check(ok, "the statement-less-module --source-map compile succeeds");
            check(!captured.toString(StandardCharsets.UTF_8)
                    .contains("source-map"),
                "no warning for the statement-less module compile: "
                    + captured.toString(StandardCharsets.UTF_8));

            Path emptyArtifact = outputRoot.resolve("empty.js");
            Path emptySidecar = outputRoot.resolve("empty.deal.map.json");
            check(Files.exists(emptyArtifact),
                "the statement-less module writes its .js artifact");
            check(Files.exists(emptySidecar),
                "the statement-less module writes its .deal.map.json "
                    + "sidecar next to the artifact");

            if (Files.exists(emptySidecar)) {
                String json = Files.readString(emptySidecar);
                check(json.contains("\"version\": 1"),
                    "statement-less sidecar version is 1");
                check("src/empty.deal".equals(extractString(json, "source")),
                    "statement-less sidecar source path correct: "
                        + extractString(json, "source"));
                check("build/js/empty.js".equals(extractString(json, "generated")),
                    "statement-less sidecar generated path correct: "
                        + extractString(json, "generated"));
                check(json.contains("\"mappings\": ["),
                    "statement-less sidecar carries the mappings key");
                check(parseSourceMapJson(json).isEmpty(),
                    "statement-less sidecar mappings array is empty");
            }
        } catch (IOException e) {
            fail("JS statement-less sidecar case threw: " + e);
        } finally {
            if (proj != null) deleteDir(proj);
        }
    }

    /**
     * The effective sourceMap flag governs emission: a --dump-ir-derived
     * flag (Main passes {@code dumpIr || sourceMap} as the effective
     * flag, explicit {@code false}) writes sidecars and prints no
     * warning.
     */
    static void testJsSidecarsDumpIrDerived() {
        System.out.println("-- JS sidecars: --dump-ir-derived effective flag --");
        Path proj = null;
        try {
            proj = Files.createTempDirectory("sourcemap_js_dumpir_");
            Path src = proj.resolve("src");
            Files.createDirectories(src);
            Path mainSrc = src.resolve("main.deal");
            Files.writeString(mainSrc, """
                export function main(): null {
                  let a: int = 1;
                  return null;
                }
                """);
            Path outputRoot = proj.resolve("build/js");

            PrintStream originalErr = System.err;
            ByteArrayOutputStream captured = new ByteArrayOutputStream();
            boolean ok;
            try {
                System.setErr(new PrintStream(captured, true,
                    StandardCharsets.UTF_8));
                CompilationOrchestrator orchestrator =
                    new CompilationOrchestrator(mainSrc, outputRoot, false,
                        true, true, false, Backend.JS,
                        (deal.module.DealConfig) null, List.of(src),
                        Path.of(".").toAbsolutePath().normalize());
                ok = orchestrator.compile();
            } finally {
                System.err.flush();
                System.setErr(originalErr);
            }
            check(ok, "--dump-ir-derived sourceMap compile succeeds");
            check(!captured.toString(StandardCharsets.UTF_8)
                    .contains("source-map"),
                "no warning for the dump-ir-derived effective flag: "
                    + captured.toString(StandardCharsets.UTF_8));
            check(Files.exists(outputRoot.resolve("main.deal.map.json")),
                "the dump-ir-derived effective flag writes the sidecar");
        } catch (IOException e) {
            fail("JS dump-ir sidecar case threw: " + e);
        } finally {
            if (proj != null) deleteDir(proj);
        }
    }

    /**
     * The two-pass no-partial-artifact contract: a rejected module
     * writes no {@code .js} and no sidecar while a clean sibling keeps
     * both.
     */
    static void testJsSidecarRejectedModule() {
        System.out.println("-- JS sidecars: rejected module writes no sidecar --");
        Path proj = null;
        try {
            proj = Files.createTempDirectory("sourcemap_js_reject_");
            Path src = proj.resolve("src");
            Files.createDirectories(src);
            Path mainSrc = src.resolve("main.deal");
            Files.writeString(mainSrc, """
                import * as bad from "./bad"
                export function main(): null {
                  let a: int = 1;
                  return null;
                }
                """);
            // The retired @jsonable E6000 (ISSUE-0326), the retired
            // nested-class E6000 (ISSUE-0318), and the retired
            // host-ABI E6000 (ISSUE-0328) no longer drive this
            // pin; the still-live @extern-c E6003 arm keeps the
            // rejected-module model covered.
            Files.writeString(src.resolve("host.d.deal"), """
                export function hostFn(x: int): int;
                """);
            Files.writeString(src.resolve("bad.deal"), """
                // @extern-c
                import * as host from "./host"
                export function make(): int { return host.hostFn(1); }
                """);
            Path outputRoot = proj.resolve("build/js");

            CompilationOrchestrator orchestrator =
                new CompilationOrchestrator(mainSrc, outputRoot, false,
                    false, true, Backend.JS,
                    (deal.module.DealConfig) null, List.of(src),
                    Path.of(".").toAbsolutePath().normalize());
            boolean ok = orchestrator.compile();
            check(!ok, "the rejected module fails the compilation");
            check(!Files.exists(outputRoot.resolve("bad.js")),
                "the rejected module writes no .js artifact");
            check(!Files.exists(outputRoot.resolve("bad.deal.map.json")),
                "the rejected module writes no sidecar");
            check(Files.exists(outputRoot.resolve("main.js")),
                "the clean sibling writes its artifact (two-pass model)");
            check(Files.exists(outputRoot.resolve("main.deal.map.json")),
                "the clean sibling writes its sidecar (two-pass model)");
        } catch (IOException e) {
            fail("JS rejected-module sidecar case threw: " + e);
        } finally {
            if (proj != null) deleteDir(proj);
        }
    }

    /**
     * Node runtime-location pins (js-v12-source-maps D3/D4): array
     * bounds, division by zero, and throw errors at pinned source
     * positions report the original {@code .deal} file/line/column
     * through the {@code DEAL_ERROR_CODE} stderr surface (the
     * entry-shim catch composes the located error object's
     * file/line/column fields into the message) — executed through the
     * real node binary, node unavailability skips (the JsBackendTest
     * node-case pattern).
     */
    static void testJsNodeRuntimeLocations() {
        System.out.println("-- JS node: runtime locations on pinned errors --");
        if (!nodeAvailable) { skipNode("JS runtime locations"); return; }

        Path proj = null;
        try {
            proj = Files.createTempDirectory("sourcemap_js_node_");
            Path src = proj.resolve("src");
            Files.createDirectories(src);
            Path mainSrc = src.resolve("main.deal");

            // Array bounds: the negative index raises E8002 at the
            // index expression's span start (line 3, column 16).
            Files.writeString(mainSrc, """
                export function main(): null {
                  let xs: int[] = [1, 2];
                  let v: int = xs[-1];
                  return null;
                }
                """);
            NodeRun bounds = compileAndRunNode(proj, mainSrc, "build1");
            check(bounds != null && bounds.exitCode() == 1
                    && bounds.output().contains("DEAL_ERROR_CODE: E8002")
                    && bounds.output().contains("negative array index")
                    && bounds.output().contains(" at " + mainSrc.toString()
                        + ":3:16"),
                "array bounds error reports the original file/line/column: "
                    + (bounds == null ? "<null>" : bounds.output()));

            // Division by zero: E8005 at the binary expression's span
            // start (line 2, column 16).
            Files.writeString(mainSrc, """
                export function main(): null {
                  let q: int = 5 / 0;
                  return null;
                }
                """);
            NodeRun divz = compileAndRunNode(proj, mainSrc, "build2");
            check(divz != null && divz.exitCode() == 1
                    && divz.output().contains("DEAL_ERROR_CODE: E8005")
                    && divz.output().contains("integer division by zero")
                    && divz.output().contains(" at " + mainSrc.toString()
                        + ":2:16"),
                "division-by-zero error reports the original file/line/column: "
                    + (divz == null ? "<null>" : divz.output()));

            // Throw: the error value carries the throw statement's span
            // (line 2, column 3).
            Files.writeString(mainSrc, """
                export function main(): null {
                  throw { code: "E9999", message: "boom" };
                  return null;
                }
                """);
            NodeRun thr = compileAndRunNode(proj, mainSrc, "build3");
            check(thr != null && thr.exitCode() == 1
                    && thr.output().contains("DEAL_ERROR_CODE: E9999")
                    && thr.output().contains("boom at " + mainSrc.toString()
                        + ":2:3"),
                "throw error reports the original file/line/column: "
                    + (thr == null ? "<null>" : thr.output()));
        } catch (IOException e) {
            fail("JS node location case threw: " + e);
        } finally {
            if (proj != null) deleteDir(proj);
        }
    }

    private record NodeRun(String output, int exitCode) {}

    /** Compiles the entry through the real orchestrator pipeline and
     * runs {@code node <output>/main.js}. */
    private static NodeRun compileAndRunNode(Path proj, Path mainSrc,
                                             String outDirName)
            throws IOException {
        Path outputRoot = proj.resolve(outDirName);
        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            mainSrc, outputRoot, false, false, false, Backend.JS,
            (deal.module.DealConfig) null, List.of(proj.resolve("src")),
            Path.of(".").toAbsolutePath().normalize());
        boolean ok = orchestrator.compile();
        if (!ok) {
            fail("node-location fixture compile failed: "
                + orchestrator.diagnostics());
            return null;
        }
        try {
            ProcessBuilder pb = new ProcessBuilder("node", "main.js");
            pb.directory(outputRoot.toFile());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            if (!p.waitFor(60, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                fail("node run timed out");
                return null;
            }
            String out = new String(p.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8).trim();
            return new NodeRun(out, p.exitValue());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("node run interrupted: " + e);
            return null;
        }
    }
}
