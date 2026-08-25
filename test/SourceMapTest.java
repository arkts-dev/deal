package deal.test;

import deal.ast.*;
import deal.checker.*;
import deal.diagnostics.CompilerDiagnostic;
import deal.codegen.SourceMapGenerator;
import deal.codegen.lua.LuaBackend;
import deal.lexer.*;
import deal.parser.*;
import deal.types.Type;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Source map generation and round-trip tests.
 *
 * <p>Verifies:
 * <ul>
 *   <li>SourceMapGenerator records mappings during codegen</li>
 *   <li>JSON sidecar format matches the spec</li>
 *   <li>All mapping entries have positive positions within bounds</li>
 *   <li>Round-trip position assertions pass for known positions</li>
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
}
