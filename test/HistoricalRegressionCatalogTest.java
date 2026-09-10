// =========================================================================
// HistoricalRegressionCatalogTest — the gate-run verification battery for
// the historical, legacy-profile, and legacy-capability catalogs
// (historical-and-legacy-catalogs Verification 1-4; ISSUE-0488)
// =========================================================================

package deal.test;

import deal.checker.CheckResult;
import deal.checker.ModuleResolver;
import deal.checker.NameResolver;
import deal.checker.Symbol;
import deal.checker.SymbolTable;
import deal.checker.TypeChecker;
import deal.diagnostics.CompilerDiagnostic;
import deal.lexer.LexResult;
import deal.lexer.Lexer;
import deal.module.CompilationOrchestrator;
import deal.module.ModuleShapeValidator;
import deal.parser.ParseResult;
import deal.parser.Parser;
import deal.semantic.RequirementManifestResult;
import deal.semantic.SemanticRequirementManifest;
import deal.semantic.ir.InvocationPurpose;
import deal.semantic.ir.SemanticCapability;
import deal.semantic.ir.SemanticProfile;
import deal.types.Type;
import deal.types.Types;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The gate-run verification battery for the three catalogs
 * (ISSUE-0488): historical pin resolution/activity/baselines with the
 * pinned failure classes (Verification 1), the safe-int legacy authority
 * with additive v1.2 replacements and zero credit (Verification 2),
 * the manifest-derived {@code UnsupportedLegacySlice} validation
 * (Verification 3), and the attainable-evidence floors (Verification 4).
 *
 * <p>Every fixture surface the negative probes tamper with is a COPY of
 * the runner's parsed index or of the source tree (a temp directory) —
 * the repository files are never mutated. Every requirement manifest in
 * the assignment validation probes derives through the real
 * {@code CompilationOrchestrator} + {@code deal.semantic.LoweringSupport}
 * pipeline over real source bytes — never fixture-asserted.</p>
 */
public class HistoricalRegressionCatalogTest {

    private static int passed = 0;
    private static int failed = 0;

    private static void check(boolean condition, String message) {
        if (condition) {
            passed++;
        } else {
            failed++;
            System.err.println("FAIL: " + message);
        }
    }

    private static void requireContains(String text, String needle,
            String message) {
        check(text != null && text.contains(needle),
            message + " — expected to contain '" + needle + "', got: "
                + text);
    }

    // =========================================================================
    // Minimal JSON parser (the BackendConformanceTest pattern; JSON null
    // parses to Java null — the catalog tolerates both spellings)
    // =========================================================================

    private static Object parseJson(String s) {
        int[] pos = new int[]{0};
        skipWhitespace(s, pos);
        return parseValue(s, pos);
    }

    private static void skipWhitespace(String s, int[] pos) {
        while (pos[0] < s.length()
                && Character.isWhitespace(s.charAt(pos[0]))) {
            pos[0]++;
        }
    }

    private static Object parseValue(String s, int[] pos) {
        skipWhitespace(s, pos);
        if (pos[0] >= s.length()) {
            return null;
        }
        char c = s.charAt(pos[0]);
        return switch (c) {
            case '"' -> parseString(s, pos);
            case '{' -> parseObject(s, pos);
            case '[' -> parseArray(s, pos);
            case 'n' -> { pos[0] += 4; yield null; }
            case 't' -> { pos[0] += 4; yield true; }
            case 'f' -> { pos[0] += 5; yield false; }
            default -> {
                if (c == '-' || Character.isDigit(c)) {
                    yield parseNumber(s, pos);
                }
                throw new RuntimeException("Unexpected char '" + c
                    + "' at " + pos[0]);
            }
        };
    }

    private static String parseString(String s, int[] pos) {
        pos[0]++;
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
                        case 'n' -> '\n';
                        case 't' -> '\t';
                        case 'r' -> '\r';
                        case '"' -> '"';
                        case '\\' -> '\\';
                        case '/' -> '/';
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
        pos[0]++;
        skipWhitespace(s, pos);
        if (pos[0] < s.length() && s.charAt(pos[0]) == '}') {
            pos[0]++;
            return map;
        }
        while (pos[0] < s.length()) {
            skipWhitespace(s, pos);
            if (pos[0] >= s.length()) {
                break;
            }
            if (s.charAt(pos[0]) == '}') {
                pos[0]++;
                break;
            }
            if (s.charAt(pos[0]) == ',') {
                pos[0]++;
                continue;
            }
            String key = parseString(s, pos);
            skipWhitespace(s, pos);
            if (pos[0] < s.length() && s.charAt(pos[0]) == ':') {
                pos[0]++;
            }
            Object value = parseValue(s, pos);
            map.put(key, value);
        }
        return map;
    }

    private static List<Object> parseArray(String s, int[] pos) {
        List<Object> list = new ArrayList<>();
        pos[0]++;
        skipWhitespace(s, pos);
        if (pos[0] < s.length() && s.charAt(pos[0]) == ']') {
            pos[0]++;
            return list;
        }
        while (pos[0] < s.length()) {
            skipWhitespace(s, pos);
            if (pos[0] >= s.length()) {
                break;
            }
            if (s.charAt(pos[0]) == ']') {
                pos[0]++;
                break;
            }
            if (s.charAt(pos[0]) == ',') {
                pos[0]++;
                continue;
            }
            list.add(parseValue(s, pos));
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
        if (numStr.contains(".") || numStr.contains("e")
                || numStr.contains("E")) {
            return Double.parseDouble(numStr);
        }
        return Long.parseLong(numStr);
    }

    // =========================================================================
    // Slice index construction
    // =========================================================================

    private static final List<String> PINNED_FIXTURE_FILES = List.of(
        "jvm-function-values-slice.json",
        "jvm-arrays-slice.json",
        "jvm-classes-slice.json",
        "jvm-xmod-classes-slice.json",
        "jvm-host-abi-slice.json",
        "jvm-modules-slice.json",
        "jvm-async-slice.json",
        "jvm-int32-slice.json"
    );

    private static Map<String, Map<String, Map<String, Object>>>
            loadSliceIndex(List<String> files) throws Exception {
        Map<String, Map<String, Map<String, Object>>> index =
            new LinkedHashMap<>();
        for (String file : files) {
            String raw = Files.readString(Path.of("test", "conformance",
                "fixtures", file));
            Map<String, Object> root = (Map<String, Object>) parseJson(raw);
            List<Map<String, Object>> tests =
                (List<Map<String, Object>>) root.get("tests");
            Map<String, Map<String, Object>> cases =
                new LinkedHashMap<>();
            for (Map<String, Object> test : tests) {
                cases.put(String.valueOf(test.get("name")), test);
            }
            index.put(file, cases);
        }
        return index;
    }

    private static Map<String, Map<String, Map<String, Object>>>
            tamperedSliceIndex(
            Map<String, Map<String, Map<String, Object>>> index,
            String file, String caseName, String field, Object newValue) {
        Map<String, Map<String, Map<String, Object>>> copy =
            new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Map<String, Object>>> fe
                : index.entrySet()) {
            copy.put(fe.getKey(), fe.getValue());
        }
        Map<String, Object> caseCopy = new LinkedHashMap<>(
            copy.get(file).get(caseName));
        caseCopy.put(field, newValue);
        Map<String, Map<String, Object>> fileCopy = new LinkedHashMap<>(
            copy.get(file));
        fileCopy.put(caseName, caseCopy);
        copy.put(file, fileCopy);
        return copy;
    }

    private static HistoricalRegressionCatalog.Row rowCopy(
            HistoricalRegressionCatalog.Row row, String pinId,
            String locator, String baseline) {
        return new HistoricalRegressionCatalog.Row(row.capability(), pinId,
            locator, row.authority(), row.family(),
            row.expectationClass(), row.errorCode(), row.anchors(),
            baseline);
    }

    private static HistoricalRegressionCatalog.Row rowOf(String pinId) {
        for (HistoricalRegressionCatalog.Row row
                : HistoricalRegressionCatalog.ROWS) {
            if (row.pinId().equals(pinId)) {
                return row;
            }
        }
        return null;
    }

    private static List<String> violationsOf(List<String> violations,
            String failureClass, String pinId) {
        List<String> matches = new ArrayList<>();
        for (String violation : violations) {
            if (violation.startsWith(failureClass + " " + pinId)) {
                matches.add(violation);
            }
        }
        return matches;
    }

    // =========================================================================
    // Frontend compile helper (the BackendConformanceTest pipeline shape)
    // =========================================================================

    private static final class StdlibStub implements ModuleResolver {

        private static final Map<String, Map<String, Type>> STDLIB =
            build();

        private static Type intType() { return Type.Int.INSTANCE; }
        private static Type numType() { return Type.Number.INSTANCE; }
        private static Type strType() { return Type.String.INSTANCE; }
        private static Type boolType() { return Type.Boolean.INSTANCE; }
        private static Type nullType() { return Type.Null.INSTANCE; }
        private static Type tableType() { return Type.Table.INSTANCE; }
        private static Type arrayType(Type elem) { return Types.array(elem); }
        private static Type fnType(List<Type> params, Type ret) {
            return Types.func(params, ret);
        }

        @SafeVarargs
        private static Map<String, Type> module(
                Map.Entry<String, Type>... entries) {
            Map<String, Type> m = new LinkedHashMap<>();
            for (var e : entries) {
                m.put(e.getKey(), e.getValue());
            }
            return m;
        }

        private static Map.Entry<String, Type> fn(String name,
                List<Type> params, Type ret) {
            return Map.entry(name, fnType(params, ret));
        }

        private static List<Type> list(Type... types) {
            return List.of(types);
        }

        private static Map<String, Map<String, Type>> build() {
            Map<String, Map<String, Type>> stdlib = new HashMap<>();
            stdlib.put("std/console", module(
                fn("log", list(strType()), nullType()),
                fn("error", list(strType()), nullType())
            ));
            stdlib.put("std/string", module(
                fn("length", list(strType()), intType()),
                fn("substring", list(strType(), intType(), intType()),
                    strType()),
                fn("contains", list(strType(), strType()), boolType()),
                fn("startsWith", list(strType(), strType()), boolType()),
                fn("endsWith", list(strType(), strType()), boolType()),
                fn("replace", list(strType(), strType(), strType()),
                    strType()),
                fn("split", list(strType(), strType()),
                    arrayType(strType())),
                fn("trim", list(strType()), strType())
            ));
            stdlib.put("std/table", module(
                fn("keys", list(tableType()), arrayType(strType()))
            ));
            stdlib.put("std/json", module(
                fn("parse", list(strType()), tableType()),
                fn("stringify", list(tableType()), strType())
            ));
            stdlib.put("std/math", module(
                fn("floor", list(numType()), numType()),
                fn("ceil", list(numType()), numType()),
                fn("sqrt", list(numType()), numType()),
                fn("absInt", list(intType()), intType()),
                fn("absNumber", list(numType()), numType()),
                fn("minInt", list(intType(), intType()), intType()),
                fn("maxInt", list(intType(), intType()), intType())
            ));
            stdlib.put("std/time", module(
                fn("nowMillis", list(), intType())
            ));
            return stdlib;
        }

        @Override
        public Map<String, Type> resolveModule(String modulePath,
                String importingModule, Set<String> modulesInProgress) {
            Map<String, Type> exports = STDLIB.get(modulePath);
            return exports != null ? exports : Map.of();
        }

        @Override
        public Symbol.ClassSymbol resolveClassSymbol(String className,
                String modulePath, String importingModule)
                throws ModuleNotFoundException {
            return null;
        }
    }

    @SuppressWarnings("deprecation")
    private static List<CompilerDiagnostic> compileFrontend(String source,
            String filename, SemanticProfile profile) {
        List<CompilerDiagnostic> errors = new ArrayList<>();
        LexResult lex = new Lexer(source, filename).tokenize();
        for (CompilerDiagnostic d : lex.diagnostics()) {
            if ("error".equals(d.severity())) {
                errors.add(d);
            }
        }
        if (lex.hasErrors()) {
            return errors;
        }
        Parser parser = new Parser(lex.tokens(), filename, profile,
            lex.directiveEvents());
        ParseResult parseResult = parser.parse();
        for (CompilerDiagnostic d : parseResult.diagnostics()) {
            if ("error".equals(d.severity())) {
                errors.add(d);
            }
        }
        if (parseResult.hasErrors()) {
            return errors;
        }
        for (CompilerDiagnostic d : ModuleShapeValidator.validate(
                parseResult.program(), filename,
                filename.endsWith(".d.deal"))) {
            if ("error".equals(d.severity())) {
                errors.add(d);
            }
        }
        if (!errors.isEmpty()) {
            return errors;
        }
        NameResolver nr = new NameResolver(filename,
            new StdlibStub());
        SymbolTable symTable;
        try {
            symTable = nr.resolve(parseResult.program());
        } catch (Exception e) {
            errors.add(CompilerDiagnostic.synthetic("E9999", "error",
                e.getMessage(), filename,
                "missing anchor: fixture source '" + filename + "'"));
            return errors;
        }
        for (CompilerDiagnostic d : nr.diagnostics()) {
            if ("error".equals(d.severity())) {
                errors.add(d);
            }
        }
        CheckResult result = TypeChecker.check(filename, symTable, nr,
            parseResult.program());
        for (CompilerDiagnostic d : result.diagnostics()) {
            if ("error".equals(d.severity())) {
                errors.add(d);
            }
        }
        return errors;
    }

    private static String caseSource(Map<String, Map<String, Object>> file,
            String caseName) {
        Object source = file.get(caseName).get("source");
        return source instanceof String s ? s : null;
    }

    // =========================================================================
    // Requirement-manifest derivation (the real LoweringSupport pass)
    // =========================================================================

    private static List<SemanticRequirementManifest> deriveManifests(
            String moduleName, String source) throws Exception {
        Path tmp = Files.createTempDirectory(
            "deal-historical-catalog-manifest");
        try {
            Path src = tmp.resolve("src");
            Files.createDirectories(src);
            Files.writeString(src.resolve(moduleName), source);
            CompilationOrchestrator orchestrator =
                new CompilationOrchestrator(
                    src.resolve(moduleName).toAbsolutePath(),
                    tmp.resolve("build"), false, null,
                    List.of(src.toAbsolutePath()),
                    Path.of("std").toAbsolutePath().normalize());
            boolean ok = orchestrator.compile();
            check(ok, "the manifest fixture compiles through the real "
                + "orchestrator pipeline: " + orchestrator.diagnostics());
            if (!ok) {
                return List.of();
            }
            RequirementManifestResult result =
                orchestrator.requirementManifests();
            check(result != null && !result.hasErrors(),
                "the orchestrator computed the requirement manifests "
                    + "through the real LoweringSupport pass");
            if (result == null || result.hasErrors()) {
                return List.of();
            }
            return new ArrayList<>(result.manifests());
        } finally {
            try (Stream<Path> walk = Files.walk(tmp)) {
                for (Path path : walk.sorted(Comparator.reverseOrder())
                        .toList()) {
                    Files.deleteIfExists(path);
                }
            } catch (Exception e) {
                // Best-effort temp cleanup only.
            }
        }
    }

    // =========================================================================
    // Verification 1: historical pins resolve; baselines equal the tree
    // =========================================================================

    private static void verification1() throws Exception {
        System.out.println("-- Verification 1: historical pins resolve "
            + "and reproduce their pinned baselines --");

        Map<String, Map<String, Map<String, Object>>> sliceIndex =
            loadSliceIndex(PINNED_FIXTURE_FILES);
        List<String> fixtureViolations =
            HistoricalRegressionCatalog.validateFixtureRows(sliceIndex);
        check(fixtureViolations.isEmpty(),
            "every fixture-case pin resolves, executes, and reproduces "
                + "its pinned baseline: " + fixtureViolations);
        List<String> codeViolations = HistoricalRegressionCatalog
            .validateSourceRows(Path.of("."));
        check(codeViolations.isEmpty(),
            "every code-contract pin resolves in-tree and reproduces "
                + "its pinned baseline: " + codeViolations);

        // The pinned pin list is complete (H2): exactly the 22 pinned
        // pin ids, one row per pin.
        List<String> pinned = HistoricalRegressionCatalog.ROWS.stream()
            .map(HistoricalRegressionCatalog.Row::pinId).toList();
        check(pinned.size() == 22,
            "the catalog pins exactly the 22 historical pins, got "
                + pinned.size());
        List<String> expected = List.of(
            "jvm-fv-lua-ref-reassigned-adapter",
            "jvm-fv-lua-ref-callresult-adapter",
            "jvm-fv-sig-check-return-error",
            "jvm-host-async-shape-bad",
            "jvm-host-async-completion-bad",
            "jvm-mod-imported-direct-call",
            "jvm-mod-imported-recursion",
            "jvm-mod-imported-void-call",
            "jvm-async-function-value",
            "jvm-async-function-value-callback",
            "jvm-async-function-value-local-reassign",
            "jvm-async-multi-module",
            "jvm-arr-negative-read-parity",
            "jvm-arr-negative-write-e8002",
            "jvm-arr-gap-write-e8002",
            "lua-array-index-delete-e8002",
            "jvm-array-index-delete-e6000",
            "jvm-arr-eval-order-write",
            "jvm-arr-eval-order-write-hoisted-parity",
            "jvm-class-field-write-eval-order-parity",
            "jvm-xmod-class-construction-defaults",
            "jvm-xmod-class-construction-eval-order");
        check(new ArrayList<>(pinned).equals(expected),
            "the pin list matches the H2 list exactly, got " + pinned);

        // The H2 capability mapping: the union over the rows is exactly
        // the table's capability columns.
        List<SemanticCapability> capabilities =
            HistoricalRegressionCatalog.ROWS.stream()
                .map(HistoricalRegressionCatalog.Row::capability)
                .distinct().sorted().toList();
        check(capabilities.equals(List.of(
                SemanticCapability.CONTAINERS_AND_STRINGS,
                SemanticCapability.EVALUATION_ORDER,
                SemanticCapability.BINDINGS,
                SemanticCapability.CALLS,
                SemanticCapability.CLASSES)),
            "the pins map to exactly the H2 table's capabilities, got "
                + capabilities);

        // Authoring closure (H1 fail-closed): locator kinds without a
        // baseline derivation are rejected at catalog authoring.
        try {
            new HistoricalRegressionCatalog.Row(
                SemanticCapability.BINDINGS, "x",
                "test/conformance/some-fixture.deal",
                HistoricalRegressionCatalog.Authority
                    .BACKEND_RUNTIME_LUAJIT,
                HistoricalRegressionCatalog.Family.ADAPTER,
                HistoricalRegressionCatalog.ExpectationClass.ExactPin,
                null, List.of(), "0".repeat(64));
            check(false, "a BACKEND_RUNTIME_LUAJIT row without a "
                + "derivable baseline is rejected at authoring");
        } catch (IllegalArgumentException expectedEx) {
            check(true, "a BACKEND_RUNTIME_LUAJIT row without a "
                + "derivable baseline is rejected at authoring");
        }
        try {
            new HistoricalRegressionCatalog.Row(
                SemanticCapability.BINDINGS, "x",
                "jvm-arrays-slice.json#jvm-arr-negative-write-e8002",
                HistoricalRegressionCatalog.Authority.BACKEND_SLICE_JVM,
                HistoricalRegressionCatalog.Family.ARRAY_BOUNDS,
                HistoricalRegressionCatalog.ExpectationClass.ExactPin,
                "E8002", List.of(), "0".repeat(64));
            check(false, "a fixture-case row carrying an errorCode is "
                + "rejected at authoring (code-contract fields only)");
        } catch (IllegalArgumentException expectedEx) {
            check(true, "a fixture-case row carrying an errorCode is "
                + "rejected at authoring (code-contract fields only)");
        }
        try {
            new HistoricalRegressionCatalog.Row(
                SemanticCapability.BINDINGS, "x",
                "deal/codegen/lua/LuaBackend.java:1",
                HistoricalRegressionCatalog.Authority
                    .BACKEND_SOURCE_LUAJIT,
                HistoricalRegressionCatalog.Family.ARRAY_DELETE,
                HistoricalRegressionCatalog.ExpectationClass
                    .SameAsShared,
                null, List.of(), "0".repeat(64));
            check(false, "a code-contract row without an errorCode is "
                + "rejected at authoring");
        } catch (IllegalArgumentException expectedEx) {
            check(true, "a code-contract row without an errorCode is "
                + "rejected at authoring");
        }
        try {
            new HistoricalRegressionCatalog.Row(
                SemanticCapability.BINDINGS, "x",
                "jvm-arrays-slice.json#jvm-arr-negative-write-e8002",
                HistoricalRegressionCatalog.Authority.BACKEND_SLICE_JVM,
                HistoricalRegressionCatalog.Family.ARRAY_BOUNDS,
                HistoricalRegressionCatalog.ExpectationClass.ExactPin,
                null, List.of(), "abcd");
            check(false, "a non-digest baseline is rejected at authoring");
        } catch (IllegalArgumentException expectedEx) {
            check(true, "a non-digest baseline is rejected at authoring");
        }

        // ---- Negative probes against tampered row copies ----
        HistoricalRegressionCatalog.Row writeRow = rowOf(
            "jvm-arr-negative-write-e8002");

        // Renamed pin: the case no longer resolves.
        HistoricalRegressionCatalog.Row renamed = rowCopy(writeRow,
            writeRow.pinId(),
            "jvm-arrays-slice.json#jvm-arr-negative-write-e8002-RENAMED",
            writeRow.expectationBaseline());
        List<String> renamedViolations =
            HistoricalRegressionCatalog.validateFixtureRow(renamed,
                sliceIndex);
        check(!violationsOf(renamedViolations,
                HistoricalRegressionCatalog.LOCATOR_DANGLED,
                writeRow.pinId()).isEmpty(),
            "a renamed pin fails HISTORICAL_LOCATOR_DANGLED naming the "
                + "pin: " + renamedViolations);

        // Missing fixture file.
        HistoricalRegressionCatalog.Row missingFile = rowCopy(writeRow,
            writeRow.pinId(), "no-such-slice.json#jvm-arr-negative-write-e8002",
            writeRow.expectationBaseline());
        List<String> missingFileViolations =
            HistoricalRegressionCatalog.validateFixtureRow(missingFile,
                sliceIndex);
        check(!violationsOf(missingFileViolations,
                HistoricalRegressionCatalog.LOCATOR_DANGLED,
                writeRow.pinId()).isEmpty(),
            "a pin naming a missing fixture file fails "
                + "HISTORICAL_LOCATOR_DANGLED: " + missingFileViolations);

        // expectedError re-authored E8002 -> null: the digest no longer
        // equals the pinned baseline, naming the changed field.
        Map<String, Map<String, Map<String, Object>>> errNullIndex =
            tamperedSliceIndex(sliceIndex, "jvm-arrays-slice.json",
                "jvm-arr-negative-write-e8002", "expectedError", null);
        List<String> errNullViolations =
            HistoricalRegressionCatalog.validateFixtureRow(writeRow,
                errNullIndex);
        List<String> errNullMatches = violationsOf(errNullViolations,
            HistoricalRegressionCatalog.EXPECTATION_CHANGED,
            writeRow.pinId());
        check(!errNullMatches.isEmpty()
                && errNullMatches.get(0).contains("expectedError"),
            "re-authoring expectedError E8002 -> null fails "
                + "HISTORICAL_PIN_EXPECTATION_CHANGED naming the "
                + "expectedError field: " + errNullViolations);

        // Narrowed backends array (emptied): the pin never executes.
        Map<String, Map<String, Map<String, Object>>> emptyBackends =
            tamperedSliceIndex(sliceIndex, "jvm-arrays-slice.json",
                "jvm-arr-negative-write-e8002", "backends", List.of());
        List<String> emptyBackendsViolations =
            HistoricalRegressionCatalog.validateFixtureRow(writeRow,
                emptyBackends);
        check(!violationsOf(emptyBackendsViolations,
                HistoricalRegressionCatalog.PIN_INACTIVE,
                writeRow.pinId()).isEmpty(),
            "an emptied backends array fails HISTORICAL_PIN_INACTIVE: "
                + emptyBackendsViolations);

        // Narrowed backends array (one entry dropped): the expectation
        // surface changed, naming the backends field.
        Map<String, Map<String, Map<String, Object>>> narrowedBackends =
            tamperedSliceIndex(sliceIndex, "jvm-arrays-slice.json",
                "jvm-arr-negative-read-parity", "backends",
                List.of("luajit"));
        List<String> narrowedBackendsViolations =
            HistoricalRegressionCatalog.validateFixtureRow(rowOf(
                "jvm-arr-negative-read-parity"), narrowedBackends);
        List<String> narrowedMatches = violationsOf(
            narrowedBackendsViolations,
            HistoricalRegressionCatalog.EXPECTATION_CHANGED,
            "jvm-arr-negative-read-parity");
        check(!narrowedMatches.isEmpty()
                && narrowedMatches.get(0).contains("backends"),
            "a narrowed backends array fails "
                + "HISTORICAL_PIN_EXPECTATION_CHANGED naming the "
                + "backends field: " + narrowedBackendsViolations);

        // Edited irContains: the expectation surface changed, naming the
        // irContains field.
        Map<String, Map<String, Map<String, Object>>> editedIr =
            tamperedSliceIndex(sliceIndex, "jvm-arrays-slice.json",
                "jvm-arr-negative-write-e8002", "irContains",
                List.of("edited-needle"));
        List<String> editedIrViolations =
            HistoricalRegressionCatalog.validateFixtureRow(writeRow,
                editedIr);
        List<String> editedIrMatches = violationsOf(editedIrViolations,
            HistoricalRegressionCatalog.EXPECTATION_CHANGED,
            writeRow.pinId());
        check(!editedIrMatches.isEmpty()
                && editedIrMatches.get(0).contains("irContains"),
            "an edited irContains fails "
                + "HISTORICAL_PIN_EXPECTATION_CHANGED naming the "
                + "irContains field: " + editedIrViolations);

        // Code-contract span byte change (anchors intact, bytes differ).
        Path tamperedTree = Files.createTempDirectory(
            "deal-historical-catalog-tree");
        try {
            Files.createDirectories(tamperedTree.resolve(
                "deal/codegen/lua"));
            String luaSource = Files.readString(
                Path.of("deal/codegen/lua/LuaBackend.java"));
            String tamperedLua = luaSource.replace(
                "local __arr = ", "local __arr   = ");
            Files.writeString(tamperedTree.resolve(
                "deal/codegen/lua/LuaBackend.java"), tamperedLua);
            HistoricalRegressionCatalog.Row luaRow = rowOf(
                "lua-array-index-delete-e8002");
            List<String> spanViolations =
                HistoricalRegressionCatalog.validateCodeRow(luaRow,
                    tamperedTree);
            List<String> spanMatches = violationsOf(spanViolations,
                HistoricalRegressionCatalog.EXPECTATION_CHANGED,
                luaRow.pinId());
            check(!spanMatches.isEmpty()
                    && spanMatches.get(0).contains("span bytes changed"),
                "a code-contract span byte change fails "
                    + "HISTORICAL_PIN_EXPECTATION_CHANGED naming the "
                    + "changed span bytes: " + spanViolations);
        } finally {
            try (Stream<Path> walk = Files.walk(tamperedTree)) {
                for (Path path : walk.sorted(Comparator.reverseOrder())
                        .toList()) {
                    Files.deleteIfExists(path);
                }
            } catch (Exception e) {
                // Best-effort temp cleanup only.
            }
        }

        // Removed anchor text.
        Path anchorTree = Files.createTempDirectory(
            "deal-historical-catalog-anchor");
        try {
            Files.createDirectories(anchorTree.resolve(
                "deal/codegen/lua"));
            String luaSource = Files.readString(
                Path.of("deal/codegen/lua/LuaBackend.java"));
            String tamperedLua = luaSource.replace(
                "if __idx < 0 or __idx > #__arr then error(__rt._err(",
                "if __idx >= 0 then error(__rt._err(");
            Files.writeString(anchorTree.resolve(
                "deal/codegen/lua/LuaBackend.java"), tamperedLua);
            HistoricalRegressionCatalog.Row luaRow = rowOf(
                "lua-array-index-delete-e8002");
            List<String> anchorViolations =
                HistoricalRegressionCatalog.validateCodeRow(luaRow,
                    anchorTree);
            List<String> anchorMatches = violationsOf(anchorViolations,
                HistoricalRegressionCatalog.EXPECTATION_CHANGED,
                luaRow.pinId());
            check(!anchorMatches.isEmpty()
                    && anchorMatches.get(0).contains(
                        "anchor text 'e8002-bounds-before-nil-write'"),
                "a removed code-contract anchor text fails "
                    + "HISTORICAL_PIN_EXPECTATION_CHANGED naming the "
                    + "absent anchor: " + anchorViolations);
        } finally {
            try (Stream<Path> walk = Files.walk(anchorTree)) {
                for (Path path : walk.sorted(Comparator.reverseOrder())
                        .toList()) {
                    Files.deleteIfExists(path);
                }
            } catch (Exception e) {
                // Best-effort temp cleanup only.
            }
        }

        // Code-contract locator failure classes.
        HistoricalRegressionCatalog.Row jvmRow = rowOf(
            "jvm-array-index-delete-e6000");
        List<String> outOfBounds = HistoricalRegressionCatalog
            .validateCodeRow(rowCopy(jvmRow, jvmRow.pinId(),
                "deal/codegen/jvm/JvmBackend.java:999999",
                jvmRow.expectationBaseline()), Path.of("."));
        check(!violationsOf(outOfBounds,
                HistoricalRegressionCatalog.LOCATOR_DANGLED,
                jvmRow.pinId()).isEmpty(),
            "an out-of-bounds code span fails "
                + "HISTORICAL_LOCATOR_DANGLED: " + outOfBounds);
        List<String> missingSource = HistoricalRegressionCatalog
            .validateCodeRow(rowCopy(jvmRow, jvmRow.pinId(),
                "deal/codegen/jvm/Missing.java:1",
                jvmRow.expectationBaseline()), Path.of("."));
        check(!violationsOf(missingSource,
                HistoricalRegressionCatalog.LOCATOR_DANGLED,
                jvmRow.pinId()).isEmpty(),
            "a missing code-contract source fails "
                + "HISTORICAL_LOCATOR_DANGLED: " + missingSource);

        // The real tree validates clean again after every tampered probe.
        check(HistoricalRegressionCatalog.validateSourceRows(Path.of("."))
                .isEmpty(),
            "the untampered tree stays green after every negative probe");

        // The pinned baselines are never auto-updated: the literal
        // baseline equals the digest over the authored surface (the
        // class-initialization check) and the tree-derived surface
        // (the validation above) — a tree change cannot silently
        // re-pin.
        HistoricalRegressionCatalog.Row anchorRow = rowOf(
            "jvm-fv-lua-ref-reassigned-adapter");
        Map<String, Map<String, Map<String, Object>>> changedOutput =
            tamperedSliceIndex(sliceIndex, "jvm-function-values-slice.json",
                "jvm-fv-lua-ref-reassigned-adapter", "expectedOutput",
                "re-authored-output");
        List<String> changedOutputViolations =
            HistoricalRegressionCatalog.validateFixtureRow(anchorRow,
                changedOutput);
        check(!violationsOf(changedOutputViolations,
                HistoricalRegressionCatalog.EXPECTATION_CHANGED,
                anchorRow.pinId()).isEmpty(),
            "a re-authored expectedOutput fails "
                + "HISTORICAL_PIN_EXPECTATION_CHANGED: "
                + changedOutputViolations);
    }

    // =========================================================================
    // Verification 2: the safe-int authority with additive replacements
    // =========================================================================

    private static void verification2() throws Exception {
        System.out.println("-- Verification 2: safe-int rows pass "
            + "unchanged under LEGACY_REGRESSION with additive v1.2 "
            + "replacements --");

        // The pinned safe-int set (H3).
        List<String> safeIntRows =
            HistoricalRegressionCatalog.signedInt32LegacyRows();
        check(safeIntRows.equals(List.of(
                "jvm-skeleton.json#jvm-int-safe-range-boundary",
                "jvm-skeleton.json#jvm-int-safe-range-overflow",
                "jvm-skeleton.json#jvm-int-safe-range-sub",
                "jvm-skeleton.json#jvm-int-safe-range-pow",
                "jvm-skeleton.json#jvm-int-safe-range-negpow",
                "jvm-skeleton.json#jvm-int-safe-range-intrinsic",
                "jvm-skeleton.json#jvm-int-safe-range-literal",
                "jvm-skeleton.json#jvm-int-safe-range-max",
                "jvm-skeleton.json#jvm-int-safe-range-mod",
                "jvm-stdlib-slice.json#jvm-std-math-int",
                "jvm-stdlib-slice.json#jvm-std-time-nowmillis")),
            "the SIGNED_INT32 legacy rows are exactly the pinned "
                + "safe-int set (jvm-std-math-int, jvm-int-safe-range-*, "
                + "jvm-std-time-nowmillis), got " + safeIntRows);

        // Every safe-int row stays catalogued, routes through
        // LEGACY_REGRESSION + LEGACY_SAFE_INT, and earns no credit.
        for (String locator : safeIntRows) {
            check(LegacyProfileRegressionCatalog.isCatalogued(locator),
                "safe-int row '" + locator + "' stays catalogued");
            var invocation = LegacyProfileRegressionCatalog
                .invocationFor(locator);
            check(invocation.purpose() == InvocationPurpose
                    .LEGACY_REGRESSION
                    && invocation.semanticProfile()
                        == SemanticProfile.LEGACY_SAFE_INT,
                "safe-int row '" + locator + "' resolves "
                    + "LEGACY_REGRESSION + LEGACY_SAFE_INT, got "
                    + invocation.purpose() + " + "
                    + invocation.semanticProfile());
            LegacyProfileRegressionCatalog.Row row = null;
            for (LegacyProfileRegressionCatalog.Row candidate
                    : LegacyProfileRegressionCatalog.ROWS) {
                if (candidate.locator().equals(locator)) {
                    row = candidate;
                }
            }
            check(row != null && "none".equals(row.credit()),
                "safe-int row '" + locator + "' carries the pinned "
                    + "'none' credit (a legacy pass earns zero "
                    + "promotion credit)");
        }

        // Every non-excluded replacement is uncatalogued (it runs under
        // the v1.2 profile) and resolves on disk.
        LegacyProfileRegressionCatalog.drainViolations();
        LegacyProfileRegressionCatalog.validateReplacementRows();
        List<String> replacementViolations =
            LegacyProfileRegressionCatalog.drainViolations();
        check(replacementViolations.isEmpty(),
            "every non-excluded replacement resolves on disk: "
                + replacementViolations);
        List<String> replacements =
            HistoricalRegressionCatalog.signedInt32ReplacementRows();
        check(!replacements.isEmpty()
                && replacements.size() == 10,
            "the ten non-excluded replacements are derived (the time "
                + "lock row is excluded), got " + replacements);
        for (String replacement : replacements) {
            check(!LegacyProfileRegressionCatalog.isCatalogued(replacement),
                "replacement '" + replacement + "' is uncatalogued — it "
                    + "runs under DEAL_V1_2_INT32, never the legacy "
                    + "authority");
            var invocation = LegacyProfileRegressionCatalog
                .invocationFor(replacement);
            check(invocation.purpose() == InvocationPurpose.COMMON_SHADOW
                    && invocation.semanticProfile()
                        == SemanticProfile.DEAL_V1_2_INT32,
                "replacement '" + replacement + "' resolves COMMON_SHADOW "
                    + "+ DEAL_V1_2_INT32, got " + invocation.purpose()
                    + " + " + invocation.semanticProfile());
        }

        // Real frontend compiles (the runner pipeline shape): every
        // safe-int source compiles with zero errors under the A5-selected
        // legacy profile — the sources pass unchanged under
        // LEGACY_REGRESSION.
        Map<String, Map<String, Object>> skeleton = loadFileCases(
            "jvm-skeleton.json");
        Map<String, Map<String, Object>> stdlibSlice = loadFileCases(
            "jvm-stdlib-slice.json");
        for (String locator : safeIntRows) {
            String[] parts = locator.split("#", 2);
            Map<String, Map<String, Object>> file =
                parts[0].equals("jvm-skeleton.json") ? skeleton
                    : stdlibSlice;
            String source = caseSource(file, parts[1]);
            check(source != null, "safe-int row '" + locator
                + "' has a source");
            if (source == null) {
                continue;
            }
            List<CompilerDiagnostic> errors = compileFrontend(source,
                "fixture-" + parts[1] + ".deal",
                LegacyProfileRegressionCatalog.profileFor(locator));
            check(errors.isEmpty(), "safe-int row '" + locator
                + "' compiles unchanged under the A5 legacy profile: "
                + errors);
        }

        // Real frontend compiles under the v1.2 profile for the slice
        // replacements.
        Map<String, Map<String, Object>> int32Slice = loadFileCases(
            "jvm-int32-slice.json");
        for (String replacement : replacements) {
            if (!replacement.startsWith("jvm-int32-slice.json#")) {
                continue;
            }
            String[] parts = replacement.split("#", 2);
            String source = caseSource(int32Slice, parts[1]);
            check(source != null, "replacement '" + replacement
                + "' has a source");
            if (source == null) {
                continue;
            }
            List<CompilerDiagnostic> errors = compileFrontend(source,
                "fixture-" + parts[1] + ".deal",
                SemanticProfile.DEAL_V1_2_INT32);
            check(errors.isEmpty(), "additive replacement '" + replacement
                + "' compiles under DEAL_V1_2_INT32: " + errors);
        }

        // The .deal replacements compile under the v1.2 profile.
        for (String replacement : replacements) {
            if (!replacement.endsWith(".deal")) {
                continue;
            }
            Path file = replacement.startsWith("backend-runtime/")
                    || replacement.startsWith("frontend/")
                ? Path.of("test", "conformance", replacement)
                : Path.of(replacement);
            String source = ConformanceHarnessMetadata
                .stripClassificationHeaders(Files.readString(file));
            List<CompilerDiagnostic> errors = compileFrontend(source,
                file.toString(), SemanticProfile.DEAL_V1_2_INT32);
            check(errors.isEmpty(), "additive replacement '" + replacement
                + "' compiles under DEAL_V1_2_INT32: " + errors);
        }

        // The H3 consumption checks are green.
        List<String> consumption =
            HistoricalRegressionCatalog
                .signedInt32LegacyConsumptionViolations();
        check(consumption.isEmpty(),
            "the SIGNED_INT32 legacy consumption checks are green: "
                + consumption);
    }

    private static Map<String, Map<String, Object>> loadFileCases(
            String fixtureFile) throws Exception {
        Map<String, Map<String, Map<String, Object>>> index =
            loadSliceIndex(List.of(fixtureFile));
        return index.get(fixtureFile);
    }

    // =========================================================================
    // Verification 3: unsupported-legacy assignment validation
    // =========================================================================

    private static void verification3() throws Exception {
        System.out.println("-- Verification 3: UnsupportedLegacySlice "
            + "validates against exactly one release-owned row --");

        Map<String, Map<String, Map<String, Object>>> sliceIndex =
            loadSliceIndex(PINNED_FIXTURE_FILES);

        // The release-owned rows resolve in-tree.
        List<String> rowViolations = LegacyCapabilityCatalog.validateRows(
            Path.of("."), sliceIndex);
        check(rowViolations.isEmpty(),
            "every release-owned row's evidence locator resolves: "
                + rowViolations);
        check(LegacyCapabilityCatalog.ROWS.size() == 2,
            "the release-owned catalog carries its two evidence-backed "
                + "rows, got " + LegacyCapabilityCatalog.ROWS.size());

        // Real manifests derived through the real LoweringSupport pass
        // over an int-literal source: FOUNDATION_VALUES + SIGNED_INT32.
        List<SemanticRequirementManifest> int32Manifests = deriveManifests(
            "main.deal",
            "export function main(): null { return null; }\n"
                + "export function test(): int { return 1; }");
        boolean claimsInt32 = int32Manifests.stream().anyMatch(
            m -> m.capabilities().contains(SemanticCapability.SIGNED_INT32));
        check(claimsInt32,
            "the real LoweringSupport pass derives the SIGNED_INT32 "
                + "claim for an int-literal source (never "
                + "fixture-asserted): " + int32Manifests);
        List<SemanticRequirementManifest> classManifests = deriveManifests(
            "main.deal",
            "export function main(): null { return null; }\n"
                + "export class C { f: int = 1; }\n"
                + "export function test(): int { return 1; }");
        boolean claimsClasses = classManifests.stream().anyMatch(
            m -> m.capabilities().contains(SemanticCapability.CLASSES));
        check(claimsClasses,
            "the plan-time CLASSES arm (the class epic's landed claim, "
                + "ISSUE-0516) derives the claim for a class source — "
                + "never fixture-asserted: " + classManifests);

        // Missing catalog entry.
        LegacyCapabilityCatalog.AssignmentValidation missingEntry =
            LegacyCapabilityCatalog.validate(
                new LegacyCapabilityCatalog.UnsupportedLegacySlice(
                    "JVM", List.of(SemanticCapability.BINDINGS),
                    "no-such-entry",
                    "jvm-function-values-slice.json#jvm-fv-lua-ref-reassigned-adapter"),
                int32Manifests, Path.of("."), sliceIndex);
        check(!missingEntry.valid()
                && missingEntry.failure().startsWith(
                    LegacyCapabilityCatalog.LEGACY_ASSIGNMENT_INVALID)
                && missingEntry.failure().contains("no-such-entry"),
            "a missing catalog entry fails LEGACY_ASSIGNMENT_INVALID "
                + "naming the entry: " + missingEntry.failure());

        // Declared capabilities differing from the row's.
        LegacyCapabilityCatalog.AssignmentValidation capabilityMismatch =
            LegacyCapabilityCatalog.validate(
                new LegacyCapabilityCatalog.UnsupportedLegacySlice(
                    "JVM", List.of(SemanticCapability.CLASSES),
                    "jvm-function-value-adapter-lua-only-rejection",
                    "jvm-function-values-slice.json#jvm-fv-lua-ref-reassigned-adapter"),
                int32Manifests, Path.of("."), sliceIndex);
        check(!capabilityMismatch.valid()
                && capabilityMismatch.failure().contains(
                    "declared capabilities"),
            "declared capabilities differing from the row's fail "
                + "LEGACY_ASSIGNMENT_INVALID: "
                + capabilityMismatch.failure());

        // Evidence-locator mismatch (an assignment carries no evidence
        // of its own).
        LegacyCapabilityCatalog.AssignmentValidation locatorMismatch =
            LegacyCapabilityCatalog.validate(
                new LegacyCapabilityCatalog.UnsupportedLegacySlice(
                    "JVM", List.of(SemanticCapability.BINDINGS),
                    "jvm-function-value-adapter-lua-only-rejection",
                    "jvm-function-values-slice.json#some-other-case"),
                int32Manifests, Path.of("."), sliceIndex);
        check(!locatorMismatch.valid()
                && locatorMismatch.failure().contains("evidenceLocator"),
            "an assignment evidenceLocator differing from the row's "
                + "fails LEGACY_ASSIGNMENT_INVALID: "
                + locatorMismatch.failure());

        // Target mismatch.
        LegacyCapabilityCatalog.AssignmentValidation targetMismatch =
            LegacyCapabilityCatalog.validate(
                new LegacyCapabilityCatalog.UnsupportedLegacySlice(
                    "LUAJIT", List.of(SemanticCapability.BINDINGS),
                    "jvm-function-value-adapter-lua-only-rejection",
                    "jvm-function-values-slice.json#jvm-fv-lua-ref-reassigned-adapter"),
                int32Manifests, Path.of("."), sliceIndex);
        check(!targetMismatch.valid()
                && targetMismatch.failure().contains("target"),
            "a target mismatch fails LEGACY_ASSIGNMENT_INVALID: "
                + targetMismatch.failure());

        // Unknown target spelling.
        LegacyCapabilityCatalog.AssignmentValidation unknownTarget =
            LegacyCapabilityCatalog.validate(
                new LegacyCapabilityCatalog.UnsupportedLegacySlice(
                    "JS", List.of(SemanticCapability.BINDINGS),
                    "jvm-function-value-adapter-lua-only-rejection",
                    "jvm-function-values-slice.json#jvm-fv-lua-ref-reassigned-adapter"),
                int32Manifests, Path.of("."), sliceIndex);
        check(!unknownTarget.valid()
                && unknownTarget.failure().contains("not a closed"),
            "an unknown target fails LEGACY_ASSIGNMENT_INVALID: "
                + unknownTarget.failure());

        // Dangling evidence locator: the index no longer carries the
        // release-owned row's fixture case.
        Map<String, Map<String, Map<String, Object>>> danglingIndex =
            new LinkedHashMap<>(sliceIndex);
        Map<String, Map<String, Object>> fvFile = new LinkedHashMap<>(
            danglingIndex.get("jvm-function-values-slice.json"));
        fvFile.remove("jvm-fv-lua-ref-reassigned-adapter");
        danglingIndex.put("jvm-function-values-slice.json", fvFile);
        LegacyCapabilityCatalog.AssignmentValidation dangling =
            LegacyCapabilityCatalog.validate(
                new LegacyCapabilityCatalog.UnsupportedLegacySlice(
                    "JVM", List.of(SemanticCapability.BINDINGS),
                    "jvm-function-value-adapter-lua-only-rejection",
                    "jvm-function-values-slice.json#jvm-fv-lua-ref-reassigned-adapter"),
                int32Manifests, Path.of("."), danglingIndex);
        check(!dangling.valid()
                && dangling.failure().contains("does not resolve"),
            "a dangling evidence locator fails LEGACY_ASSIGNMENT_INVALID: "
                + dangling.failure());

        // Manifest/capability mismatch: the real derived manifests do
        // not require the row's capabilities — a fixture cannot hide
        // the mismatch by naming a capability.
        LegacyCapabilityCatalog.AssignmentValidation manifestMismatch =
            LegacyCapabilityCatalog.validate(
                new LegacyCapabilityCatalog.UnsupportedLegacySlice(
                    "JVM", List.of(SemanticCapability.BINDINGS),
                    "jvm-function-value-adapter-lua-only-rejection",
                    "jvm-function-values-slice.json#jvm-fv-lua-ref-reassigned-adapter"),
                classManifests, Path.of("."), sliceIndex);
        check(!manifestMismatch.valid()
                && manifestMismatch.failure().contains(
                    "do not require"),
            "manifests that do not require the row's capabilities fail "
                + "LEGACY_ASSIGNMENT_INVALID: "
                + manifestMismatch.failure());

        // Empty derived manifests are rejected (never fabricated).
        LegacyCapabilityCatalog.AssignmentValidation emptyManifests =
            LegacyCapabilityCatalog.validate(
                new LegacyCapabilityCatalog.UnsupportedLegacySlice(
                    "JVM", List.of(SemanticCapability.BINDINGS),
                    "jvm-function-value-adapter-lua-only-rejection",
                    "jvm-function-values-slice.json#jvm-fv-lua-ref-reassigned-adapter"),
                List.of(), Path.of("."), sliceIndex);
        check(!emptyManifests.valid()
                && emptyManifests.failure().contains("derives no"),
            "an assignment with no derived manifests fails "
                + "LEGACY_ASSIGNMENT_INVALID: "
                + emptyManifests.failure());

        // Positive path: a test row whose capability the real derived
        // manifests genuinely require validates; the retained target
        // records LEGACY_NOT_RUN, shared consumers never skip, and the
        // assignment earns no rollback-equivalence credit.
        LegacyCapabilityCatalog.Row testRow = new LegacyCapabilityCatalog.Row(
            "test-int32-jvm-unsupported",
            LegacyCapabilityCatalog.Target.JVM,
            List.of(SemanticCapability.SIGNED_INT32),
            "jvm-int32-slice.json#int32-add-overflow",
            LegacyCapabilityCatalog.ExpectationClass.BackendReject);
        LegacyCapabilityCatalog.AssignmentValidation positive =
            LegacyCapabilityCatalog.validate(
                new LegacyCapabilityCatalog.UnsupportedLegacySlice(
                    "JVM", List.of(SemanticCapability.SIGNED_INT32),
                    "test-int32-jvm-unsupported",
                    "jvm-int32-slice.json#int32-add-overflow"),
                int32Manifests, Path.of("."), sliceIndex,
                Map.of(testRow.entryId(), testRow));
        check(positive.valid() && positive.row() == testRow,
            "an assignment whose real derived manifests require the "
                + "row's capabilities validates: "
                + positive.failure());
        check(LegacyCapabilityCatalog.LEGACY_NOT_RUN.equals(
                positive.retainedTargetStatus()),
            "a validated assignment records LEGACY_NOT_RUN for the "
                + "retained target");
        check(!positive.rollbackEquivalenceCredit(),
            "a validated assignment earns no rollback-equivalence "
                + "credit");
        check(positive.valid(),
            "a validated assignment never skips a shared consumer (the "
                + "validation is target-scoped; no shared consumer is "
                + "dropped)");
    }

    // =========================================================================
    // Verification 4: attainable-evidence floors
    // =========================================================================

    private static void verification4() throws Exception {
        System.out.println("-- Verification 4: the attainable-evidence "
            + "floors hold --");

        Map<String, Map<String, Map<String, Object>>> sliceIndex =
            loadSliceIndex(PINNED_FIXTURE_FILES);
        List<String> floorViolations = HistoricalRegressionCatalog
            .retainedAssignmentFloorViolations(Path.of("."), sliceIndex);
        check(floorViolations.isEmpty(),
            "the attainable floors hold over the real tree/index: "
                + floorViolations);

        // The floors fail when a pin is tampered (the floor is computed
        // from the pins, never weakened by assignments).
        Map<String, Map<String, Map<String, Object>>> tampered =
            tamperedSliceIndex(sliceIndex, "jvm-arrays-slice.json",
                "jvm-arr-negative-write-e8002", "expectedError", null);
        List<String> tamperedFloors = HistoricalRegressionCatalog
            .retainedAssignmentFloorViolations(Path.of("."), tampered);
        check(!tamperedFloors.isEmpty(),
            "a weakened pin drops the corpus below the floor: "
                + tamperedFloors);

        // The floors still hold when an assignment validates: a
        // validated unsupported state cannot replace attainable
        // retained evidence.
        List<LegacyCapabilityCatalog.AssignmentValidation> valid =
            List.of(new LegacyCapabilityCatalog.AssignmentValidation(
                true,
                "valid — retained target JVM records LEGACY_NOT_RUN; "
                    + "no rollback-equivalence credit; shared consumers "
                    + "never skip",
                LegacyCapabilityCatalog.ROWS.get(1)));
        HistoricalRegressionCatalog.RetainedAssignmentsInput input =
            HistoricalRegressionCatalog.retainedAssignmentsItem(
                Path.of("."), sliceIndex, valid);
        check(input.pass() && input.assignmentCount() == 1
                && input.floorPinCount() == 22,
            "the RETAINED_ASSIGNMENTS gate input passes with a "
                + "validated assignment and the full 22-pin floor: "
                + input.violations());
        List<LegacyCapabilityCatalog.AssignmentValidation> invalid =
            List.of(new LegacyCapabilityCatalog.AssignmentValidation(
                false, LegacyCapabilityCatalog.LEGACY_ASSIGNMENT_INVALID
                    + " — catalog entry 'missing' does not exist",
                null));
        HistoricalRegressionCatalog.RetainedAssignmentsInput badInput =
            HistoricalRegressionCatalog.retainedAssignmentsItem(
                Path.of("."), sliceIndex, invalid);
        check(!badInput.pass()
                && badInput.violations().get(0).startsWith(
                    LegacyCapabilityCatalog.LEGACY_ASSIGNMENT_INVALID),
            "an invalid assignment fails the RETAINED_ASSIGNMENTS gate "
                + "input: " + badInput.violations());

        // The per-capability historical gate inputs pass over the real
        // tree/index (the HISTORICAL_TESTS item input).
        Map<String, Map<String, Map<String, Object>>> realIndex =
            loadSliceIndex(PINNED_FIXTURE_FILES);
        for (SemanticCapability capability : List.of(
                SemanticCapability.BINDINGS,
                SemanticCapability.CALLS,
                SemanticCapability.CONTAINERS_AND_STRINGS,
                SemanticCapability.EVALUATION_ORDER,
                SemanticCapability.CLASSES)) {
            HistoricalRegressionCatalog.HistoricalGateInput gate =
                HistoricalRegressionCatalog.historicalItem(capability,
                    Path.of("."), realIndex);
            check(gate.pass() && gate.pinnedRows() > 0,
                "the HISTORICAL_TESTS input for " + capability
                    + " passes over the real tree/index ("
                    + gate.pinnedRows() + " pins): "
                    + gate.violations());
        }
        HistoricalRegressionCatalog.HistoricalGateInput noEvidenceGate =
            HistoricalRegressionCatalog.historicalItem(
                SemanticCapability.SIGNED_INT32, Path.of("."),
                realIndex);
        check(!noEvidenceGate.pass()
                && noEvidenceGate.violations().stream().anyMatch(
                    v -> v.contains("HISTORICAL_EVIDENCE_MISSING")),
            "without the wired runners' execution evidence the "
                + "SIGNED_INT32 historical item fails on "
                + "HISTORICAL_EVIDENCE_MISSING (evidence is never "
                + "invented): " + noEvidenceGate.violations());
        for (String locator
                : HistoricalRegressionCatalog.signedInt32LegacyRows()) {
            HistoricalRegressionCatalog.recordLegacyExecution(locator);
        }
        for (String locator
                : HistoricalRegressionCatalog
                    .signedInt32ReplacementRows()) {
            HistoricalRegressionCatalog.recordV12Execution(locator);
        }
        HistoricalRegressionCatalog.HistoricalGateInput int32Gate =
            HistoricalRegressionCatalog.historicalItem(
                SemanticCapability.SIGNED_INT32, Path.of("."),
                realIndex);
        check(int32Gate.pass()
                && int32Gate.pinnedRows() == 0,
            "with the wired runners' green evidence recorded, the "
                + "HISTORICAL_TESTS input for SIGNED_INT32 passes — it "
                + "consumes the landed LegacyProfileRegressionCatalog "
                + "safe-int rows and their additive v1.2 replacements: "
                + int32Gate.violations());
        List<String> globalFailure = HistoricalRegressionCatalog
            .executedEvidenceViolations(true);
        check(!globalFailure.isEmpty()
                && globalFailure.stream().anyMatch(
                    v -> v.contains("HISTORICAL_EVIDENCE_GLOBAL_FAILURE")),
            "a global failure in a runner that executed the rows voids "
                + "the execution evidence: " + globalFailure);

        // The tampered tree/index fails the gate inputs.
        HistoricalRegressionCatalog.HistoricalGateInput tamperedGate =
            HistoricalRegressionCatalog.historicalItem(
                SemanticCapability.CONTAINERS_AND_STRINGS,
                Path.of("."), tampered);
        check(!tamperedGate.pass()
                && !tamperedGate.violations().isEmpty(),
            "a tampered pin fails the capability's HISTORICAL_TESTS "
                + "input: " + tamperedGate.violations());
    }

    // =========================================================================
    // Main
    // =========================================================================

    public static void main(String[] args) throws Exception {
        System.out.println("=== Historical / Legacy-Profile / "
            + "Legacy-Capability Catalog Tests (ISSUE-0488) ===");
        System.out.println();
        verification1();
        System.out.println();
        verification2();
        System.out.println();
        verification3();
        System.out.println();
        verification4();
        System.out.println();
        System.out.println("=== Catalog tests: " + passed + " passed, "
            + failed + " failed ===");
        if (failed > 0) {
            System.exit(1);
        }
    }
}
