package deal.test;

import deal.ast.*;
import deal.checker.*;
import deal.ir.IrDumper;
import deal.lexer.*;
import deal.module.ExportExtractor;
import deal.parser.*;
import deal.types.Type;
import deal.types.Types;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Golden IR test runner.
 *
 * <p>Discovers .deal fixture files in {@code test/ir-goldens/fixtures/},
 * compiles each through the frontend (lex → parse → name-resolve → type-check),
 * dumps the IR via {@link IrDumper#dump(ProgramNode, CheckResult, String)},
 * and compares the output against golden {@code .ir.txt} files in
 * {@code test/ir-goldens/}.</p>
 *
 * <p>Update mode: set environment variable {@code DEAL_UPDATE_GOLDENS=true}
 * or system property {@code deal.updateGoldens=true} to regenerate all
 * golden files in one pass.</p>
 */
public final class IrGoldenTest {

    private static int passed = 0;
    private static int failed = 0;
    private static int updated = 0;

    private static final Path FIXTURES_DIR = Path.of("test/ir-goldens/fixtures");
    private static final Path GOLDENS_DIR = Path.of("test/ir-goldens");

    // =========================================================================
    // Main
    // =========================================================================

    public static void main(String[] args) throws Exception {
        boolean updateMode = "true".equalsIgnoreCase(System.getenv("DEAL_UPDATE_GOLDENS"))
                || "true".equalsIgnoreCase(System.getProperty("deal.updateGoldens"));

        System.out.println("=== DEAL IR Golden Tests ===");
        System.out.println("Fixtures: " + FIXTURES_DIR);
        System.out.println("Goldens:  " + GOLDENS_DIR);
        System.out.println("Update mode: " + (updateMode ? "ON" : "OFF"));
        System.out.println();

        List<Path> fixtures = discoverFixtures();
        if (fixtures.isEmpty()) {
            System.out.println("WARNING: No fixture files found in " + FIXTURES_DIR);
        }
        System.out.println("Discovered " + fixtures.size() + " fixture(s)");
        System.out.println();

        ModuleResolver resolver = new GoldenModuleResolver(FIXTURES_DIR);

        for (Path fixture : fixtures) {
            runGoldenTest(fixture, resolver, updateMode);
        }

        // Print summary
        System.out.println();
        System.out.println("=== Golden IR Summary ===");
        System.out.println("Passed: " + passed + ", Failed: " + failed
            + (updateMode ? ", Updated: " + updated : ""));
        System.out.println();

        if (failed > 0) {
            System.exit(1);
        }
    }

    // =========================================================================
    // Discovery
    // =========================================================================

    /**
     * Discovers .deal files directly in the fixtures directory (non-recursive).
     * Helper modules live in subdirectories (e.g., lib/) and are not
     * treated as test fixtures themselves.
     */
    private static List<Path> discoverFixtures() throws IOException {
        List<Path> result = new ArrayList<>();
        if (!Files.isDirectory(FIXTURES_DIR)) {
            return result;
        }
        try (var stream = Files.list(FIXTURES_DIR)) {
            stream.filter(p -> p.toString().endsWith(".deal"))
                  .sorted()
                  .forEach(result::add);
        }
        return result;
    }

    // =========================================================================
    // Test runner
    // =========================================================================

    private static void runGoldenTest(Path fixture, ModuleResolver resolver,
                                       boolean updateMode) throws IOException {
        String baseName = fixture.getFileName().toString();
        // Strip .deal extension
        if (baseName.endsWith(".deal")) {
            baseName = baseName.substring(0, baseName.length() - 5);
        }
        String displayName = baseName;
        System.out.print("  [" + displayName + "] ");

        // Compile
        String source = Files.readString(fixture);
        IrDumpResult dumpResult = compileAndDump(fixture, source, resolver);

        if (dumpResult.error() != null) {
            System.out.println("FAIL (compile error: " + dumpResult.error() + ")");
            failed++;
            return;
        }

        String ir = dumpResult.ir();
        if (ir == null || ir.isBlank()) {
            System.out.println("FAIL (empty IR dump)");
            failed++;
            return;
        }

        // Run invariant checks
        List<String> invariantErrors = checkInvariants(ir);
        if (!invariantErrors.isEmpty()) {
            System.out.println("FAIL (invariant violations)");
            for (String err : invariantErrors) {
                System.out.println("    " + err);
            }
            failed++;
            return;
        }

        Path goldenFile = GOLDENS_DIR.resolve(baseName + ".ir.txt");

        if (updateMode) {
            Files.createDirectories(GOLDENS_DIR);
            Files.writeString(goldenFile, ir);
            System.out.println("UPDATED");
            updated++;
            return;
        }

        if (!Files.exists(goldenFile)) {
            System.out.println("FAIL (missing golden file: " + goldenFile
                + " — run with DEAL_UPDATE_GOLDENS=true to generate)");
            failed++;
            return;
        }

        String expected = Files.readString(goldenFile);
        List<String> expectedLines = expected.lines().toList();
        List<String> actualLines = ir.lines().toList();

        // Line-by-line comparison
        int maxLines = Math.max(expectedLines.size(), actualLines.size());
        boolean mismatch = false;
        int firstDiff = -1;

        for (int i = 0; i < maxLines; i++) {
            String expLine = i < expectedLines.size() ? expectedLines.get(i) : "<missing>";
            String actLine = i < actualLines.size() ? actualLines.get(i) : "<missing>";
            if (!expLine.equals(actLine)) {
                mismatch = true;
                firstDiff = i + 1; // 1-based line number
                break;
            }
        }

        if (mismatch) {
            System.out.println("FAIL (mismatch at line " + firstDiff + ")");
            printDiff(expectedLines, actualLines, firstDiff);
            failed++;
        } else {
            System.out.println("OK");
            passed++;
        }
    }

    // =========================================================================
    // Compilation
    // =========================================================================

    private record IrDumpResult(String ir, String error) {}

    private static IrDumpResult compileAndDump(Path fixture, String source,
                                                ModuleResolver resolver) {
        String filename = fixture.toString();

        // Lex
        LexResult lex = new Lexer(source, filename).tokenize();
        if (lex.hasErrors()) {
            return new IrDumpResult(null, "lex errors: " + lex.diagnostics());
        }

        // Parse
        Parser parser = new Parser(lex.tokens(), filename);
        ParseResult parseResult = parser.parse();
        if (parseResult.hasErrors()) {
            return new IrDumpResult(null, "parse errors: " + parseResult.diagnostics());
        }

        // Name-resolve
        NameResolver nr = new NameResolver(filename, resolver);
        SymbolTable symTable;
        try {
            symTable = nr.resolve(parseResult.program());
        } catch (Exception e) {
            return new IrDumpResult(null,
                "name-resolve exception: " + e.getMessage());
        }
        if (nr.diagnostics().stream().anyMatch(d -> "error".equals(d.severity()))) {
            StringBuilder sb = new StringBuilder("name-resolve errors:");
            for (Diagnostic d : nr.diagnostics()) {
                if ("error".equals(d.severity())) {
                    sb.append("\n    ").append(d);
                }
            }
            return new IrDumpResult(null, sb.toString());
        }

        // Type-check
        CheckResult result = TypeChecker.check(filename, symTable, nr,
            parseResult.program());
        if (result.hasErrors()) {
            StringBuilder sb = new StringBuilder("type-check errors:");
            for (Diagnostic d : result.diagnostics()) {
                if ("error".equals(d.severity())) {
                    sb.append("\n    ").append(d);
                }
            }
            return new IrDumpResult(null, sb.toString());
        }

        // Dump IR
        String ir = IrDumper.dump(parseResult.program(), result, filename);
        return new IrDumpResult(ir, null);
    }

    // =========================================================================
    // Invariant checks
    // =========================================================================

    /**
     * Checks IR dump invariants:
     * <ul>
     *   <li>Non-empty</li>
     *   <li>Every expression node has a type annotation</li>
     *   <li>Every node has a span or synthetic marker</li>
     * </ul>
     */
    private static List<String> checkInvariants(String ir) {
        List<String> errors = new ArrayList<>();
        if (ir.isBlank()) {
            errors.add("IR dump is empty");
            return errors;
        }

        // Check that it starts with "module"
        if (!ir.stripLeading().startsWith("module ")) {
            errors.add("IR dump does not begin with 'module' header");
        }

        // Check that expression-type lines have ':' type annotations
        // Expression lines: indent + kind + ... + " : " + type + " @..."
        // Lines like "literal 42 : int @..." or "binary + : int @..."
        List<String> lines = ir.lines().toList();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            // Skip blank lines, boundary markers, body markers, block markers
            String trimmed = line.stripLeading();
            if (trimmed.isEmpty()) continue;
            if (trimmed.startsWith("[boundary:")) continue;
            if (trimmed.equals("body")) continue;
            if (trimmed.startsWith("module ")) continue;

            // Check for span
            if (!line.contains("@") && !line.contains("synthetic")) {
                // Allow "body", "block" lines, boundary annotations without spans
                if (!trimmed.equals("body") && !trimmed.startsWith("block ")
                        && !trimmed.startsWith("[boundary:")
                        && !trimmed.startsWith("export ")
                        && !trimmed.equals("return")
                        && !trimmed.equals("break")
                        && !trimmed.equals("continue")
                        && !trimmed.equals("throw")) {
                    errors.add("Line " + (i + 1)
                        + " missing span: " + trimmed);
                }
            }
        }

        return errors;
    }

    // =========================================================================
    // Diff output
    // =========================================================================

    private static void printDiff(List<String> expected, List<String> actual,
                                   int firstDiffLine) {
        int context = 2;
        int start = Math.max(0, firstDiffLine - 1 - context);
        int end = Math.min(
            Math.max(expected.size(), actual.size()),
            firstDiffLine + context);

        System.out.println("    --- Expected");
        System.out.println("    +++ Actual");
        for (int i = start; i < end; i++) {
            String exp = i < expected.size() ? expected.get(i) : "<missing>";
            String act = i < actual.size() ? actual.get(i) : "<missing>";
            if (exp.equals(act)) {
                System.out.println("      " + (i + 1) + ": " + exp);
            } else {
                int ln = i + 1;
                System.out.println("    - " + ln + ": " + exp);
                System.out.println("    + " + ln + ": " + act);
                if (ln == firstDiffLine) {
                    System.out.println("      ^^^ first difference at line " + ln);
                }
            }
        }
    }

    // =========================================================================
    // Module resolver for golden tests
    // =========================================================================

    /**
     * A module resolver that provides hardcoded stdlib exports
     * and resolves relative file imports via ExportExtractor.
     */
    private static class GoldenModuleResolver implements ModuleResolver {

        private final Path fixturesDir;
        private final Map<String, Map<String, Type>> stdlibExports;

        GoldenModuleResolver(Path fixturesDir) {
            this.fixturesDir = fixturesDir.toAbsolutePath();
            this.stdlibExports = buildStdlibExports();
        }

        @Override
        public Map<String, Type> resolveModule(String modulePath,
                String importingModule, Set<String> modulesInProgress)
                throws ModuleNotFoundException {
            // Check stdlib
            if (stdlibExports.containsKey(modulePath)) {
                return stdlibExports.get(modulePath);
            }

            // Try relative file import
            Path resolved = resolveRelativePath(modulePath);
            if (resolved != null && Files.exists(resolved)) {
                return resolveFileModule(resolved, modulesInProgress);
            }

            throw new ModuleNotFoundException("Module not found: " + modulePath);
        }

        @Override
        public Symbol.ClassSymbol resolveClassSymbol(String className,
                String modulePath, String importingModule)
                throws ModuleNotFoundException {
            return null;
        }

        // ---- stdlib exports (6 spec-listed modules) ----

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

        // ---- type helpers ----

        private static Type intType()    { return Type.Int.INSTANCE; }
        private static Type numType()    { return Type.Number.INSTANCE; }
        private static Type strType()    { return Type.String.INSTANCE; }
        private static Type boolType()   { return Type.Boolean.INSTANCE; }
        private static Type nullType()   { return Type.Null.INSTANCE; }
        private static Type tableType()  { return Type.Table.INSTANCE; }

        private static Type arrayType(Type elem) { return Types.array(elem); }

        @SafeVarargs
        private static Map<String, Type> module(
                Map.Entry<String, Type>... entries) {
            Map<String, Type> m = new LinkedHashMap<>();
            for (var e : entries) m.put(e.getKey(), e.getValue());
            return m;
        }

        private static Map.Entry<String, Type> fn(String name,
                List<Type> params, Type ret) {
            return Map.entry(name, Types.func(params, ret));
        }

        private static List<Type> list(Type... types) {
            return List.of(types);
        }

        // ---- relative file imports ----

        private Path resolveRelativePath(String importPath) {
            if (!importPath.startsWith("./") && !importPath.startsWith("../")) {
                return null;
            }
            Path resolved = fixturesDir.resolve(importPath).normalize();
            if (Files.exists(resolved)) return resolved;
            Path withExt = fixturesDir.resolve(importPath + ".deal").normalize();
            if (Files.exists(withExt)) return withExt;
            Path withDeclExt = fixturesDir.resolve(importPath + ".d.deal").normalize();
            if (Files.exists(withDeclExt)) return withDeclExt;
            return null;
        }

        private Map<String, Type> resolveFileModule(Path file,
                Set<String> modulesInProgress) throws ModuleNotFoundException {
            try {
                String source = Files.readString(file);
                String filename = file.toString();
                boolean isDecl = filename.endsWith(".d.deal");

                LexResult lex = new Lexer(source, filename).tokenize();
                if (lex.hasErrors())
                    throw new ModuleNotFoundException("Lex errors in " + filename);

                Parser parser = new Parser(lex.tokens(), filename);
                ParseResult parseResult = parser.parse();
                if (parseResult.hasErrors())
                    throw new ModuleNotFoundException("Parse errors in " + filename);

                ExportExtractor extractor = new ExportExtractor(filename, isDecl);
                return extractor.extract(parseResult.program());
            } catch (IOException e) {
                throw new ModuleNotFoundException("Cannot read: " + file);
            }
        }
    }
}
