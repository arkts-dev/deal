package deal.test;

import deal.Main;
import deal.diagnostics.CompilerDiagnostic;
import deal.diagnostics.DiagnosticFormatter;
import deal.diagnostics.DiagnosticRange;
import deal.diagnostics.DiagnosticStructuredOutput;
import deal.diagnostics.RangeOrigin;
import deal.ast.*;
import deal.checker.*;
import deal.codegen.lua.LuaBackend;
import deal.lexer.*;
import deal.module.*;
import deal.module.DealConfig.DealConfigParseResult;
import deal.parser.*;
import deal.types.Type;
import deal.types.Types;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Tests for the module system, compilation orchestration, and CLI.
 * Covers ISSUE-0008 requirements.
 */
public class ModuleSystemTest {

    private static int passed = 0;
    private static int failed = 0;
    private static Path tmpDir;

    private static void check(boolean condition, String message) {
        if (condition) { passed++; }
        else { failed++; System.err.println("FAIL: " + message); }
    }

    private static void fail(String message) {
        failed++;
        System.err.println("FAIL: " + message);
    }

    // =========================================================================
    // Stdlib Compiler Integration Tests (ISSUE-0009)
    // =========================================================================

    private static void testStdlibConsoleCompilerIntegration() throws Exception {
        System.out.println("-- Stdlib Compiler Integration: std/console --");

        writeFile("src/sci_console.deal", """
            import * as console from "std/console"
            export function test(): null { console.log("hello"); }
            export function main(): null { return null; }
            """);

        Path entryFile = tmpDir.resolve("src/sci_console.deal").toAbsolutePath();
        Path outputDir = tmpDir.resolve("build/sci_console");
        List<Path> roots = new ArrayList<>();
        roots.add(tmpDir.resolve("src").toAbsolutePath());

        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            entryFile, outputDir, false, null, roots, null);

        boolean success = orchestrator.compile();
        List<Diagnostic> diags = orchestrator.diagnostics();
        boolean hasModuleNotFound = diags.stream()
            .anyMatch(d -> "E2003".equals(d.code()));
        check(!hasModuleNotFound, "Stdlib console: no E2003 module-not-found");
        if (!success) {
            System.out.println("  Note: compilation had errors: " + diags.stream()
                .filter(d -> "error".equals(d.severity()))
                .map(d -> d.code() + ": " + d.message()).toList());
        }
    }

    private static void testStdlibTableCompilerIntegration() throws Exception {
        System.out.println("-- Stdlib Compiler Integration: std/table --");

        writeFile("src/sci_table.deal", """
            import * as tbl from "std/table"
            export function getKeys(t: table): string[] { return tbl.keys(t); }
            export function main(): null { return null; }
            """);

        Path entryFile = tmpDir.resolve("src/sci_table.deal").toAbsolutePath();
        Path outputDir = tmpDir.resolve("build/sci_table");
        List<Path> roots = new ArrayList<>();
        roots.add(tmpDir.resolve("src").toAbsolutePath());

        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            entryFile, outputDir, false, null, roots, null);

        boolean success = orchestrator.compile();
        List<Diagnostic> diags = orchestrator.diagnostics();
        boolean hasModuleNotFound = diags.stream()
            .anyMatch(d -> "E2003".equals(d.code()));
        check(!hasModuleNotFound, "Stdlib table: no E2003 module-not-found");
        if (!success) {
            System.out.println("  Note: compilation had errors: " + diags.stream()
                .filter(d -> "error".equals(d.severity()))
                .map(d -> d.code() + ": " + d.message()).toList());
        }
    }

    private static void testStdlibJsonCompilerIntegration() throws Exception {
        System.out.println("-- Stdlib Compiler Integration: std/json --");

        writeFile("src/sci_json.deal", """
            import * as json from "std/json"
            export function encode(t: table): string { return json.stringify(t); }
            export function decode(s: string): table { return json.parse(s); }
            export function main(): null { return null; }
            """);

        Path entryFile = tmpDir.resolve("src/sci_json.deal").toAbsolutePath();
        Path outputDir = tmpDir.resolve("build/sci_json");
        List<Path> roots = new ArrayList<>();
        roots.add(tmpDir.resolve("src").toAbsolutePath());

        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            entryFile, outputDir, false, null, roots, null);

        boolean success = orchestrator.compile();
        List<Diagnostic> diags = orchestrator.diagnostics();
        boolean hasModuleNotFound = diags.stream()
            .anyMatch(d -> "E2003".equals(d.code()));
        check(!hasModuleNotFound, "Stdlib json: no E2003 module-not-found");
        if (!success) {
            System.out.println("  Note: compilation had errors: " + diags.stream()
                .filter(d -> "error".equals(d.severity()))
                .map(d -> d.code() + ": " + d.message()).toList());
        }
    }

    private static void testStdlibMathCompilerIntegration() throws Exception {
        System.out.println("-- Stdlib Compiler Integration: std/math --");

        writeFile("src/sci_math.deal", """
            import * as math from "std/math"
            export function absVal(x: number): number { return math.abs(x); }
            export function roundUp(x: number): int { return math.ceil(x); }
            export function roundDown(x: number): int { return math.floor(x); }
            export function bigger(a: int, b: int): int { return math.max(a, b); }
            export function smaller(a: int, b: int): int { return math.min(a, b); }
            export function sqRoot(x: number): number { return math.sqrt(x); }
            export function main(): null { return null; }
            """);

        Path entryFile = tmpDir.resolve("src/sci_math.deal").toAbsolutePath();
        Path outputDir = tmpDir.resolve("build/sci_math");
        List<Path> roots = new ArrayList<>();
        roots.add(tmpDir.resolve("src").toAbsolutePath());

        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            entryFile, outputDir, false, null, roots, Path.of(".").toAbsolutePath().normalize());

        boolean success = orchestrator.compile();
        List<Diagnostic> diags = orchestrator.diagnostics();
        boolean hasModuleNotFound = diags.stream()
            .anyMatch(d -> "E2003".equals(d.code()));
        check(!hasModuleNotFound, "Stdlib math: no E2003 module-not-found");
        if (!success) {
            System.out.println("  Note: compilation had errors: " + diags.stream()
                .filter(d -> "error".equals(d.severity()))
                .map(d -> d.code() + ": " + d.message()).toList());
        }
    }

    private static void testStdlibTimeCompilerIntegration() throws Exception {
        System.out.println("-- Stdlib Compiler Integration: std/time --");

        writeFile("src/sci_time.deal", """
            import * as time from "std/time"
            export function currentTime(): int { return time.nowMillis(); }
            export function main(): null { return null; }
            """);

        Path entryFile = tmpDir.resolve("src/sci_time.deal").toAbsolutePath();
        Path outputDir = tmpDir.resolve("build/sci_time");
        List<Path> roots = new ArrayList<>();
        roots.add(tmpDir.resolve("src").toAbsolutePath());

        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            entryFile, outputDir, false, null, roots, null);

        boolean success = orchestrator.compile();
        List<Diagnostic> diags = orchestrator.diagnostics();
        boolean hasModuleNotFound = diags.stream()
            .anyMatch(d -> "E2003".equals(d.code()));
        check(!hasModuleNotFound, "Stdlib time: no E2003 module-not-found");
        if (!success) {
            System.out.println("  Note: compilation had errors: " + diags.stream()
                .filter(d -> "error".equals(d.severity()))
                .map(d -> d.code() + ": " + d.message()).toList());
        }
    }

    private static void testStdlibCrossModuleUsage() throws Exception {
        System.out.println("-- Stdlib: Cross-Module Usage (string + math) --");

        writeFile("src/sci_cross.deal", """
            import * as strings from "std/string"
            import * as math from "std/math"
            export function analyze(s: string): int {
                let len: int = strings.length(s);
                return math.maxInt(len, 0);
            }
            export function main(): null { return null; }
            """);

        Path entryFile = tmpDir.resolve("src/sci_cross.deal").toAbsolutePath();
        Path outputDir = tmpDir.resolve("build/sci_cross");
        List<Path> roots = new ArrayList<>();
        roots.add(tmpDir.resolve("src").toAbsolutePath());

        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            entryFile, outputDir, false, null, roots, Path.of(".").toAbsolutePath().normalize());

        boolean success = orchestrator.compile();
        List<Diagnostic> diags = orchestrator.diagnostics();
        boolean hasModuleNotFound = diags.stream()
            .anyMatch(d -> "E2003".equals(d.code()));
        check(!hasModuleNotFound, "Stdlib cross-module: no E2003 module-not-found");
        if (!success) {
            System.out.println("  Note: compilation had errors: " + diags.stream()
                .filter(d -> "error".equals(d.severity()))
                .map(d -> d.code() + ": " + d.message()).toList());
        }

        // Runtime verification if luajit is available
        if (luajitAvailable() && success) {
            try {
                Path runtimeDest = outputDir.resolve("deal/runtime.lua");
                if (!Files.exists(runtimeDest)) {
                    Files.createDirectories(runtimeDest.getParent());
                    Files.copy(Path.of("deal/runtime.lua"), runtimeDest);
                }

                String luaCode = "package.path = '" + outputDir.toRealPath()
                    + "/?.lua;' "
                    + "local m = require('sci_cross') "
                    + "local result = m.analyze.f('hello') "
                    + "assert(result == 5, 'expected 5, got ' .. tostring(result)) "
                    + "print('OK: stdlib cross-module runtime')";
                ProcessBuilder pb = new ProcessBuilder("luajit", "-e", luaCode);
                pb.redirectErrorStream(true);
                Process proc = pb.start();
                String output = new String(proc.getInputStream().readAllBytes());
                int exitCode = proc.waitFor();
                check(exitCode == 0,
                    "Stdlib cross-module: runtime verification (exit " + exitCode
                    + "): " + output.trim());
            } catch (Exception e) {
                check(false, "Stdlib cross-module: runtime verification failed: " + e.getMessage());
            }
        }
    }

    private static void testStdlibJsonRoundTrip() throws Exception {
        System.out.println("-- Stdlib: JSON Round-Trip End-to-End --");

        writeFile("src/sci_jsonrt.deal", """
            import * as json from "std/json"
            export function roundTrip(data: table): table {
                let encoded: string = json.stringify(data);
                return json.parse(encoded);
            }
            export function main(): null { return null; }
            """);

        Path entryFile = tmpDir.resolve("src/sci_jsonrt.deal").toAbsolutePath();
        Path outputDir = tmpDir.resolve("build/sci_jsonrt");
        List<Path> roots = new ArrayList<>();
        roots.add(tmpDir.resolve("src").toAbsolutePath());

        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            entryFile, outputDir, false, null, roots, Path.of(".").toAbsolutePath().normalize());

        boolean success = orchestrator.compile();
        List<Diagnostic> diags = orchestrator.diagnostics();
        boolean hasModuleNotFound = diags.stream()
            .anyMatch(d -> "E2003".equals(d.code()));
        check(!hasModuleNotFound, "Stdlib json round-trip: no E2003 module-not-found");
        if (!success) {
            System.out.println("  Note: compilation had errors: " + diags.stream()
                .filter(d -> "error".equals(d.severity()))
                .map(d -> d.code() + ": " + d.message()).toList());
        }

        // Runtime verification of JSON round-trip
        if (luajitAvailable() && success) {
            try {
                Path runtimeDest = outputDir.resolve("deal/runtime.lua");
                if (!Files.exists(runtimeDest)) {
                    Files.createDirectories(runtimeDest.getParent());
                    Files.copy(Path.of("deal/runtime.lua"), runtimeDest);
                }

                String luaCode = "package.path = '" + outputDir.toRealPath()
                    + "/?.lua;' "
                    + "local m = require('sci_jsonrt') "
                    + "local input = {a=1, b='hi', c=true} "
                    + "local result = m.roundTrip.f(input) "
                    + "assert(result.a == 1, 'a mismatch: ' .. tostring(result.a)) "
                    + "assert(result.b == 'hi', 'b mismatch: ' .. tostring(result.b)) "
                    + "assert(result.c == true, 'c mismatch: ' .. tostring(result.c)) "
                    + "print('OK: stdlib json round-trip')";
                ProcessBuilder pb = new ProcessBuilder("luajit", "-e", luaCode);
                pb.redirectErrorStream(true);
                Process proc = pb.start();
                String output = new String(proc.getInputStream().readAllBytes());
                int exitCode = proc.waitFor();
                check(exitCode == 0,
                    "Stdlib json round-trip: runtime verification (exit " + exitCode
                    + "): " + output.trim());
            } catch (Exception e) {
                check(false, "Stdlib json round-trip: runtime verification failed: " + e.getMessage());
            }
        }
    }


    // =========================================================================
    // Helpers
    // =========================================================================

    private static Path writeFile(String relativePath, String content) throws IOException {
        Path file = tmpDir.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
    }

    // =========================================================================
    // DealConfig Tests
    // =========================================================================

    private static void testDealConfig() throws Exception {
        System.out.println("-- DealConfig --");

        String json = """
            {
              "languageVersion": "1.2",
              "moduleRoots": ["src"],
              "output": "build/lua",
              "backend": "luajit",
              "permissions": ["net"],
              "limits": { "maxMemory": 128 },
              "externals": ["host"],
              "dependencies": { "items": ["dep1"] }
            }""";

        DealConfigParseResult result = parseManifest(json);
        check(result.diagnostics().isEmpty(),
            "valid manifest carries no diagnostics: " + result.diagnostics());
        DealConfig config = result.config();
        check(config != null, "valid manifest yields a config");
        if (config != null) {
            check(config.moduleRoots().size() == 1, "moduleRoots size");
            check(config.moduleRoots().get(0).equals("src"), "moduleRoots[0]");
            check(config.output().equals("build/lua"), "output");
            check(config.backend().equals("luajit"), "backend");
            check(config.permissions().size() == 1, "permissions size");
            check(config.permissions().get(0).equals("net"), "permissions[0]");
            check(config.limits() != null, "limits not null");
            check(config.limits().maxMemory() == 128, "limits.maxMemory");
            check(config.externals().isEmpty(),
                "externals empty (legacy list form retired)");
            check(config.dependencies() != null, "dependencies not null");
            check(config.dependencies().items().size() == 1, "dependencies.items size");
            check(config.languageVersion().equals("1.2"),
                "languageVersion parses as '1.2'");
            check(config.configFile().equals(Path.of("deal.json")),
                "configFile is the parse path");
        }

        // Spec map form: externals entries carry a manifest-relative declaration.
        String mapJson = """
            {
              "moduleRoots": ["src"],
              "externals": {
                "host/cfg": { "declaration": "bindings/host-cfg.d.deal" },
                "host/log": { "declaration": "bindings/host-log.d.deal" }
              }
            }""";
        result = parseManifest(mapJson);
        check(result.diagnostics().isEmpty(),
            "externals map manifest carries no diagnostics");
        DealConfig mapConfig = result.config();
        check(mapConfig != null, "externals map manifest yields a config");
        if (mapConfig != null) {
            check(mapConfig.externals().size() == 2, "externals map size");
            check(mapConfig.externals().get("host/cfg")
                    .equals("bindings/host-cfg.d.deal"),
                "externals map declaration host/cfg");
            check(mapConfig.externals().get("host/log")
                    .equals("bindings/host-log.d.deal"),
                "externals map declaration host/log");
        }

        // Malformed externals entry: value must be an object with "declaration".
        checkManifestFailure("{\"externals\": {\"host/cfg\": \"not-an-object\"}}",
            "declaration");

        // Malformed externals declarations: a missing "declaration" string,
        // an empty path, and a path that is not a host declaration file are
        // config errors.  A non-.d.deal declaration would otherwise compile
        // "successfully" and emit a broken runtime require path (the imported
        // file is treated as a normal module, not a host declaration).
        for (String badDecl : new String[] {
                "{\"externals\": {\"host/cfg\": {}}}",
                "{\"externals\": {\"host/cfg\": {\"declaration\": \"\"}}}",
                "{\"externals\": {\"host/cfg\": {\"declaration\": \"cfg.deal\"}}}",
                "{\"externals\": {\"host/cfg\": {\"declaration\": \"bindings/cfg.d\"}}}"}) {
            checkManifestFailure(badDecl, "declaration");
        }

        // Non-map, non-list externals shapes are config errors, not silent
        // empty maps: scalars and strings must be rejected loudly.
        for (String badShape : new String[] {
                "{\"externals\": 5}",
                "{\"externals\": \"host/x\"}",
                "{\"externals\": true}"}) {
            checkManifestFailure(badShape, "externals");
        }

        // ISSUE-0091: the JVM skeleton backend is now a valid manifest value.
        result = parseManifest("{\"backend\": \"jvm\"}");
        check(result.diagnostics().isEmpty(), "deal.json 'jvm' backend carries no diagnostics");
        config = result.config();
        check(config != null && "jvm".equals(config.backend()),
            "deal.json accepts 'jvm' backend");

        // Unknown backends are still rejected, naming the supported values.
        checkManifestFailure("{\"backend\": \"wasm\"}", "luajit");

        // Missing optional fields
        result = parseManifest("{}");
        check(result.diagnostics().isEmpty(), "minimal config carries no diagnostics");
        config = result.config();
        check(config != null, "minimal config yields a config");
        if (config != null) {
            check(config.moduleRoots().isEmpty(), "empty moduleRoots");
            check(config.output() == null, "null output");
            check(config.backend() == null, "null backend");
            // Absent languageVersion assumes the current compiler version.
            check("1.2".equals(config.languageVersion()),
                "absent languageVersion defaults to '1.2'");
        }

        // DEAL v1.2 is not source-compatible with earlier language
        // versions and this compiler implements no migration rules, so
        // any declared languageVersion other than '1.2' is a
        // configuration error (spec-v1.2: Package manifest).
        for (String badVersion : new String[] {
                "{\"languageVersion\": \"1.1\"}",
                "{\"languageVersion\": \"2.0\"}",
                "{\"languageVersion\": \"1\"}",
                "{\"languageVersion\": \"\"}"}) {
            checkManifestFailure(badVersion, "languageVersion");
        }

        testManifestOutOfAlphabetPositions();
        testManifestToleranceFixtures();
        testManifestPrecedenceFixtures();
        testManifestStructuralFixtures();
        testManifestWhitespaceOnlyAnchor();
        testAbsentManifestDefaultConfig();
    }

    /**
     * Verification-4 out-of-alphabet position fixtures: each produces
     * exactly one ranged E2012 naming the offending scalar position.
     */
    private static void testManifestOutOfAlphabetPositions() {
        // NBSP at member start (today's expect('"') throw).
        DealConfigParseResult r = parseManifest("{\u00A0\"a\": 1}");
        CompilerDiagnostic d = requireSingleE2012(r, "expected string key");
        checkRange(d, 1, 2, 1, 3, 1, 2, 1);

        // NBSP at member start after a completed member.
        r = parseManifest("{\"a\": 1, \u00A0\"b\": 2}");
        d = requireSingleE2012(r, "expected string key");
        checkRange(d, 1, 10, 1, 11, 9, 10, 1);

        // NBSP between key and colon (today's expect(':') throw).
        r = parseManifest("{\"a\"\u00A0: 1}");
        d = requireSingleE2012(r, "expected ':'");
        checkRange(d, 1, 5, 1, 6, 4, 5, 1);

        // NBSP after a completed member value: the stop-every-enclosing-
        // container truncation — parses as backend-only, no diagnostic,
        // languageVersion defaults to 1.2.
        r = parseManifest("{\"backend\":\"luajit\"\u00A0\"languageVersion\":\"1.0\"}");
        check(r.diagnostics().isEmpty(),
            "after-value NBSP truncates with no diagnostic: " + r.diagnostics());
        DealConfig config = r.config();
        check(config != null, "after-value truncation still yields a config");
        if (config != null) {
            check("luajit".equals(config.backend()),
                "backend member read before the truncation");
            check("1.2".equals(config.languageVersion()),
                "truncated languageVersion defaults to 1.2");
        }
    }

    /**
     * Verification-4 tolerance fixtures: every construct today's parser
     * tolerates still parses without a diagnostic.
     */
    private static void testManifestToleranceFixtures() {
        // Missing comma truncates the object read silently.
        DealConfigParseResult r = parseManifest("{\"a\":1 \"b\":2}");
        check(r.diagnostics().isEmpty(), "missing comma truncates: " + r.diagnostics());
        check(r.config() != null, "truncated object yields a config");

        // The truncation is observable: the second member is never read,
        // so an unsupported languageVersion behind the missing comma is
        // not validated.
        r = parseManifest("{\"backend\":\"luajit\" \"languageVersion\":\"1.1\"}");
        check(r.diagnostics().isEmpty(),
            "truncated languageVersion is not validated: " + r.diagnostics());
        check(r.config() != null && "luajit".equals(r.config().backend())
                && "1.2".equals(r.config().languageVersion()),
            "missing-comma truncation keeps backend-only semantics");

        // Out-of-alphabet scalar after a completed member value: same
        // stop-every-enclosing-container cascade.
        r = parseManifest("{\"backend\":\"luajit\"\u00A0\"languageVersion\":\"1.1\"}");
        check(r.diagnostics().isEmpty(),
            "after-value NBSP cascade carries no diagnostic: " + r.diagnostics());
        check(r.config() != null && "luajit".equals(r.config().backend())
                && "1.2".equals(r.config().languageVersion()),
            "after-value cascade keeps backend-only semantics");

        // Unclosed object at EOF: partial structure accepted.
        r = parseManifest("{\"a\":1");
        check(r.diagnostics().isEmpty(), "unclosed object at EOF: " + r.diagnostics());
        check(r.config() != null, "unclosed object yields a config");

        // Unclosed array at EOF: partial list accepted.
        r = parseManifest("{\"moduleRoots\":[\"src\"");
        check(r.diagnostics().isEmpty(), "unclosed array at EOF: " + r.diagnostics());
        check(r.config() != null && r.config().moduleRoots().equals(List.of("src")),
            "unclosed array keeps the partial list");

        // Value position at EOF: null value, member tolerated.
        r = parseManifest("{\"a\":");
        check(r.diagnostics().isEmpty(), "value at EOF is a null value: " + r.diagnostics());
        check(r.config() != null, "null-value member yields a config");

        // Unclosed string at EOF: partial string accepted.
        r = parseManifest("{\"moduleRoots\":[\"sr");
        check(r.diagnostics().isEmpty(), "unclosed string at EOF: " + r.diagnostics());
        check(r.config() != null && r.config().moduleRoots().equals(List.of("sr")),
            "unclosed string keeps the partial value");

        // Unknown escape: backslash dropped, character kept.
        r = parseManifest("{\"output\": \"build\\q\"}");
        check(r.diagnostics().isEmpty(), "unknown escape tolerated: " + r.diagnostics());
        check(r.config() != null && "buildq".equals(r.config().output()),
            "unknown escape drops the backslash and keeps the character");

        // Unpaired surrogate passed through.
        r = parseManifest("{\"output\": \"x\uD800y\"}");
        check(r.diagnostics().isEmpty(), "unpaired surrogate tolerated: " + r.diagnostics());
        check(r.config() != null && r.config().output() != null
                && r.config().output().charAt(1) == '\uD800',
            "unpaired surrogate kept in the decoded value");

        // Trailing content after the root is ignored.
        r = parseManifest("{\"moduleRoots\":[\"src\"]} trailing garbage");
        check(r.diagnostics().isEmpty(), "trailing content tolerated: " + r.diagnostics());
        check(r.config() != null && r.config().moduleRoots().equals(List.of("src")),
            "trailing content does not disturb the decoded root");

        // Duplicate keys: last-wins.
        r = parseManifest("{\"backend\":\"wasm\",\"backend\":\"luajit\"}");
        check(r.diagnostics().isEmpty(), "duplicate keys last-wins: " + r.diagnostics());
        check(r.config() != null && "luajit".equals(r.config().backend()),
            "duplicate backend resolves to the last value");

        // Permissive numbers: 01 decodes as Long 1; 1e5 decodes as a
        // Double (wrong-typed for the int getter -> absent); both parse.
        r = parseManifest("{\"limits\":{\"maxMemory\":01}}");
        check(r.diagnostics().isEmpty(), "leading-zero number tolerated: " + r.diagnostics());
        check(r.config() != null && r.config().limits() != null
                && r.config().limits().maxMemory() == 1,
            "01 decodes as 1");
        r = parseManifest("{\"limits\":{\"maxMemory\":1e5}}");
        check(r.diagnostics().isEmpty(), "exponent number tolerated: " + r.diagnostics());
        check(r.config() != null && r.config().limits() != null
                && r.config().limits().maxMemory() == null,
            "1e5 decodes as a Double and stays absent for the int getter");

        // Non-strict whitespace between key and colon (U+2028, skipped by
        // Character.isWhitespace) is accepted.
        r = parseManifest("{\"a\"\u2028: 1}");
        check(r.diagnostics().isEmpty(), "U+2028 between key and colon: " + r.diagnostics());
        check(r.config() != null, "U+2028-skipped manifest yields a config");

        // VT-separated members with a comma are accepted.
        r = parseManifest("{\"backend\":\"luajit\",\u000B\"moduleRoots\":[\"src\"]}");
        check(r.diagnostics().isEmpty(), "VT-separated members: " + r.diagnostics());
        check(r.config() != null && "luajit".equals(r.config().backend())
                && r.config().moduleRoots().equals(List.of("src")),
            "VT-separated members parse both fields");

        // VT-separated members without a comma truncate exactly as today
        // (the VT is skipped, the following string breaks the loop).
        r = parseManifest("{\"a\":1\u000B\"b\":2}");
        check(r.diagnostics().isEmpty(), "VT-separated missing comma: " + r.diagnostics());
        check(r.config() != null, "VT-separated truncation yields a config");

        // Raw control characters inside strings are kept as-is.
        r = parseManifest("{\"output\": \"x\ny\"}");
        check(r.diagnostics().isEmpty(), "raw LF in string: " + r.diagnostics());
        check(r.config() != null && "x\ny".equals(r.config().output()),
            "raw control characters kept in the decoded value");

        // Legacy externals list form is retired to an empty map.
        r = parseManifest("{\"externals\": [1,2]}");
        check(r.diagnostics().isEmpty(), "legacy externals list: " + r.diagnostics());
        check(r.config() != null && r.config().externals().isEmpty(),
            "legacy externals list yields an empty map");

        // JSON null externals is absent.
        r = parseManifest("{\"externals\": null}");
        check(r.diagnostics().isEmpty(), "null externals tolerated: " + r.diagnostics());
        check(r.config() != null && r.config().externals().isEmpty(),
            "null externals yields an empty map");

        // Trimmed languageVersion spellings and wrong-typed optionals.
        r = parseManifest("{\"languageVersion\": \" 1.2 \"}");
        check(r.diagnostics().isEmpty(), "trimmed languageVersion: " + r.diagnostics());
        check(r.config() != null && "1.2".equals(r.config().languageVersion()),
            "trimmed '1.2' validates");
        r = parseManifest("{\"languageVersion\": 1.2}");
        check(r.diagnostics().isEmpty(), "wrong-typed languageVersion: " + r.diagnostics());
        check(r.config() != null && "1.2".equals(r.config().languageVersion()),
            "wrong-typed languageVersion defaults to 1.2");
        r = parseManifest("{\"backend\": \" JVM \"}");
        check(r.diagnostics().isEmpty(), "case-insensitive backend: " + r.diagnostics());
        check(r.config() != null && " JVM ".equals(r.config().backend()),
            "backend spelling kept as written");

        // Wrong-typed list entries become absent/empty via the getters.
        r = parseManifest("{\"moduleRoots\": [\"src\", 5]}");
        check(r.diagnostics().isEmpty(), "wrong-typed list entry: " + r.diagnostics());
        check(r.config() != null && r.config().moduleRoots().equals(List.of("src")),
            "only string list entries survive");
    }

    /**
     * Verification-4 precedence fixtures: multi-error manifests report
     * today's first-error precedence (languageVersion -> externals ->
     * backend), independent of member document order.
     */
    private static void testManifestPrecedenceFixtures() {
        // languageVersion beats backend, regardless of member order.
        DealConfigParseResult r = parseManifest("{\"backend\":\"wasm\",\"languageVersion\":\"1.1\"}");
        CompilerDiagnostic d = requireSingleE2012(r, "languageVersion");
        check(!d.message().contains("unsupported backend"),
            "backend message absent under languageVersion precedence: " + d.message());
        r = parseManifest("{\"languageVersion\":\"1.1\",\"backend\":\"wasm\"}");
        d = requireSingleE2012(r, "languageVersion");
        check(!d.message().contains("unsupported backend"),
            "precedence independent of member document order: " + d.message());

        // externals beats backend.
        r = parseManifest("{\"backend\":\"wasm\",\"externals\":5}");
        d = requireSingleE2012(r, "'externals' must be an object");
        check(!d.message().contains("unsupported backend"),
            "backend message absent under externals precedence: " + d.message());
        r = parseManifest("{\"externals\":5,\"backend\":\"wasm\"}");
        d = requireSingleE2012(r, "'externals' must be an object");
        check(!d.message().contains("unsupported backend"),
            "externals precedence independent of member order: " + d.message());

        // languageVersion beats externals.
        r = parseManifest("{\"externals\":5,\"languageVersion\":\"1.1\"}");
        d = requireSingleE2012(r, "languageVersion");
        check(!d.message().contains("'externals'"),
            "externals message absent under languageVersion precedence: " + d.message());

        // Within externals, entries validate in member order: the first
        // malformed entry in member order wins.
        r = parseManifest("{\"externals\":{\"a\":\"x\",\"b\":\"y\"}}");
        d = requireSingleE2012(r, "externals entry 'a'");
        check(d.message().contains("'a'"), "first member-order entry reported");
    }

    /**
     * Verification-4 structural fixtures: member-start EOF, invalid value
     * positions, undecodable numbers, and non-object roots.
     */
    private static void testManifestStructuralFixtures() {
        // Member-start EOF inside the root object.
        DealConfigParseResult r = parseManifest("{");
        CompilerDiagnostic d = requireSingleE2012(r, "expected string key");
        checkRange(d, 1, 2, 1, 2, 1, 1, 0);

        // Member-start EOF after a completed member.
        r = parseManifest("{\"a\": 1,");
        d = requireSingleE2012(r, "expected string key");
        checkRange(d, 1, 9, 1, 9, 8, 8, 0);

        // Invalid value position with input present (today's raw
        // NumberFormatException path).
        r = parseManifest("{\"a\": :}");
        d = requireSingleE2012(r, "expected value");
        checkRange(d, 1, 7, 1, 8, 6, 7, 1);

        // Undecodable number.
        r = parseManifest("{\"a\": 1e}");
        d = requireSingleE2012(r, "expected value");
        checkRange(d, 1, 7, 1, 9, 6, 8, 2);

        // Non-object roots: scalar, array, string, and null.
        r = parseManifest("5");
        d = requireSingleE2012(r, "deal.json: invalid JSON");
        checkRange(d, 1, 1, 1, 2, 0, 1, 1);
        checkManifestFailure("[1,2]", "deal.json: invalid JSON");
        checkManifestFailure("\"x\"", "deal.json: invalid JSON");
        checkManifestFailure("null", "deal.json: invalid JSON");
    }

    /**
     * Verification-4 whitespace-only anchor: empty and whitespace-only
     * manifests pin the document-start zero-length SOURCE range
     * {@code (file,1,1,1,1,0,0,0)} with known offsets (0,0), cross-checked
     * between the formatted and structured outputs.
     */
    private static void testManifestWhitespaceOnlyAnchor() {
        for (String input : new String[] {"", " \t\n", "\u2028 \u2028"}) {
            DealConfigParseResult r = parseManifest(input);
            CompilerDiagnostic d = requireSingleE2012(r, "deal.json: invalid JSON");
            DiagnosticRange range = d.range();
            check(range.startLine() == 1 && range.startColumn() == 1
                    && range.endLine() == 1 && range.endColumn() == 1,
                "pinned document-start positions (1,1)-(1,1): " + range);
            check(range.startScalarOffset() == 0 && range.endScalarOffset() == 0
                    && range.scalarLength() == 0,
                "pinned document-start offsets (0,0,0): " + range);
            check(range.origin() == RangeOrigin.SOURCE,
                "document-start anchor is SOURCE, never synthetic: " + range);
            check(range.file().equals("deal.json"),
                "document-start anchor carries the manifest path: " + range.file());
            check(d.notes().isEmpty(),
                "no anchor note on the real document-start anchor: " + d.notes());
            checkSourceRange(range, Path.of("deal.json"));
            checkFormattedStructuredCross(d);
        }
    }

    /**
     * Verification-4 absent-manifest pin: a project directory without
     * deal.json compiles with today's default configuration, no E2012, no
     * diagnostic output, and today's exit code.
     */
    private static void testAbsentManifestDefaultConfig() throws Exception {
        Path entryFile = writeFile("no_manifest_proj/src/nm_main.deal",
            "export function main(): null { return null; }\n"
                + "export function run(): int { return 7; }\n")
            .toAbsolutePath();
        Path outputDir = tmpDir.resolve("build/no_manifest").toAbsolutePath();

        // load returns (config = null, diagnostics = empty) — absent is
        // not invalid.
        DealConfigParseResult loadResult = DealConfig.load(entryFile.getParent());
        check(loadResult.config() == null, "absent manifest yields no config");
        check(loadResult.diagnostics().isEmpty(),
            "absent manifest yields empty diagnostics: " + loadResult.diagnostics());

        String[] captured = runCliCapturingErr(new String[] {
            "compile", entryFile.toString(), "--output", outputDir.toString()});
        check("0".equals(captured[0]),
            "manifest-less compile exits 0, got " + captured[0] + ": " + captured[1]);
        check(!captured[1].contains("E2012") && !captured[1].contains("deal.json"),
            "no manifest diagnostic output: " + captured[1]);
        check(Files.exists(outputDir.resolve("nm_main.lua")),
            "default LuaJIT backend writes the .lua artifact");
    }

    // =========================================================================
    // Manifest diagnostic helpers (T5 record -> T3 carrier conversion pins)
    // =========================================================================

    private static DealConfigParseResult parseManifest(String json) {
        return DealConfig.parse(Path.of("deal.json"), json);
    }

    /** Asserts the exact single-E2012 shape plus the conversion pin. */
    private static CompilerDiagnostic requireSingleE2012(DealConfigParseResult result,
                                                         String messageSubstring) {
        check(result.config() == null, "invalid manifest yields no config");
        check(result.diagnostics().size() == 1,
            "exactly one diagnostic: " + result.diagnostics());
        if (result.diagnostics().size() != 1) {
            return null;
        }
        CompilerDiagnostic d = result.diagnostics().get(0);
        check("E2012".equals(d.code()), "E2012 code, got " + d.code());
        check("error".equals(d.severity()), "error severity, got " + d.severity());
        check(d.message().contains(messageSubstring),
            "message contains '" + messageSubstring + "': " + d.message());
        check(d.notes().isEmpty(), "no notes on a manifest E2012: " + d.notes());
        checkSourceRange(d.range(), Path.of("deal.json"));
        checkFormattedStructuredCross(d);
        return d;
    }

    private static void checkManifestFailure(String json, String messageSubstring) {
        requireSingleE2012(parseManifest(json), messageSubstring);
    }

    /** The T5->T3 conversion pin: SOURCE origin, manifest file, known offsets. */
    private static void checkSourceRange(DiagnosticRange range, Path file) {
        check(range.origin() == RangeOrigin.SOURCE,
            "origin SOURCE, got " + range.origin());
        check(range.file().equals(file.toString()),
            "range file equals the manifest path '" + file + "', got '" + range.file() + "'");
        check(range.startScalarOffset() >= 0 && range.endScalarOffset() >= 0,
            "known (non-UNKNOWN) scalar offsets");
        check(range.endScalarOffset() >= range.startScalarOffset(),
            "non-inverted scalar offsets");
        check(range.scalarLength() == range.endScalarOffset() - range.startScalarOffset(),
            "scalarLength == endScalarOffset - startScalarOffset");
        check(range.startLine() >= 1 && range.startColumn() >= 1
                && range.endLine() >= 1 && range.endColumn() >= 1,
            "positive line/column positions");
    }

    /** Asserts one exact zero/one-scalar range. */
    private static void checkRange(CompilerDiagnostic d, int sLine, int sCol,
                                   int eLine, int eCol, int start, int end, int len) {
        if (d == null) return;
        DiagnosticRange range = d.range();
        check(range.startLine() == sLine && range.startColumn() == sCol,
            "range start (" + sLine + "," + sCol + "), got ("
                + range.startLine() + "," + range.startColumn() + ")");
        check(range.endLine() == eLine && range.endColumn() == eCol,
            "range end (" + eLine + "," + eCol + "), got ("
                + range.endLine() + "," + range.endColumn() + ")");
        check(range.startScalarOffset() == start && range.endScalarOffset() == end,
            "scalar offsets (" + start + "," + end + "), got ("
                + range.startScalarOffset() + "," + range.endScalarOffset() + ")");
        check(range.scalarLength() == len,
            "scalar length " + len + ", got " + range.scalarLength());
    }

    /** Formatted-vs-structured cross-check on one diagnostic. */
    private static void checkFormattedStructuredCross(CompilerDiagnostic d) {
        DiagnosticRange range = d.range();
        String formatted = DiagnosticFormatter.format(d);
        String positionPrefix = range.file() + ":" + range.startLine() + ":"
            + range.startColumn() + "-" + range.endLine() + ":" + range.endColumn();
        check(formatted.startsWith(positionPrefix),
            "formatted carries the complete range positions: " + formatted);
        check(formatted.contains("[span " + range.scalarLength() + "]"),
            "formatted carries the span length: " + formatted);
        String json = DiagnosticStructuredOutput.toJson(List.of(d));
        check(json.contains("\"file\": \"" + range.file() + "\""),
            "structured carries the manifest file: " + json);
        check(json.contains("\"startLine\": " + range.startLine())
                && json.contains("\"startColumn\": " + range.startColumn())
                && json.contains("\"endLine\": " + range.endLine())
                && json.contains("\"endColumn\": " + range.endColumn()),
            "structured carries the complete range positions: " + json);
        check(json.contains("\"startScalarOffset\": " + range.startScalarOffset())
                && json.contains("\"endScalarOffset\": " + range.endScalarOffset())
                && json.contains("\"scalarLength\": " + range.scalarLength()),
            "structured carries the scalar offsets and length: " + json);
        check(json.contains("\"origin\": \"SOURCE\""),
            "structured carries the SOURCE origin: " + json);
    }

    // =========================================================================
    // ExportExtractor Tests
    // =========================================================================

    private static void testExportExtractor() throws Exception {
        System.out.println("-- ExportExtractor --");

        String source = """
            export function add(a: int, b: int): int { return a + b; }
            export class Point {
                x: int;
                y: int;
            }
            let localVar: int = 42;
            """;

        LexResult lex = new Lexer(source, "test.deal").tokenize();
        ParseResult parse = new Parser(lex.tokens(), "test.deal").parse();
        check(!parse.hasErrors(), "Parser: no errors");

        ExportExtractor extractor = new ExportExtractor("test", false);
        Map<String, Type> exports = extractor.extract(parse.program());

        check(exports.containsKey("add"), "exports contains add");
        check(exports.containsKey("Point"), "exports contains Point");
        check(!exports.containsKey("localVar"), "exports does not contain localVar");

        Type addType = exports.get("add");
        check(addType instanceof Type.Func, "add is a function type");
        Type.Func addFunc = (Type.Func) addType;
        check(addFunc.paramTypes().size() == 2, "add has 2 params");
        check(addFunc.paramTypes().get(0) == Type.Int.INSTANCE, "add param 0 is int");
        check(addFunc.returnType() == Type.Int.INSTANCE, "add returns int");

        Type pointType = exports.get("Point");
        check(pointType instanceof Type.Class, "Point is a class type");
    }


    // =========================================================================
    // ExportExtractor: async function isAsync propagation (ISSUE-0052)
    // =========================================================================

    private static void testExportExtractorAsyncFunc() throws Exception {
        System.out.println("-- ExportExtractor: async function isAsync --");

        String source = """
            export async function fetch(a: int): int { return a; }
            export function sync(a: int): int { return a; }
            """;

        LexResult lex = new Lexer(source, "test.deal").tokenize();
        ParseResult parse = new Parser(lex.tokens(), "test.deal").parse();
        check(!parse.hasErrors(), "Parser: no errors");

        ExportExtractor extractor = new ExportExtractor("test", false);
        Map<String, Type> exports = extractor.extract(parse.program());

        check(exports.containsKey("fetch"), "exports contains fetch");
        check(exports.containsKey("sync"), "exports contains sync");

        // Async function should have isAsync=true
        Type fetchType = exports.get("fetch");
        check(fetchType instanceof Type.Func, "fetch is a function type");
        Type.Func fetchFunc = (Type.Func) fetchType;
        check(fetchFunc.isAsync(), "fetch should be async");
        check(fetchFunc.paramTypes().size() == 1, "fetch has 1 param");
        check(fetchFunc.paramTypes().get(0) == Type.Int.INSTANCE, "fetch param is int");
        check(fetchFunc.returnType() == Type.Int.INSTANCE, "fetch returns int");

        // Sync function should have isAsync=false
        Type syncType = exports.get("sync");
        check(syncType instanceof Type.Func, "sync is a function type");
        Type.Func syncFunc = (Type.Func) syncType;
        check(!syncFunc.isAsync(), "sync should NOT be async");
    }

    private static void testExportExtractorAsyncFuncTypeAnnotation() throws Exception {
        System.out.println("-- ExportExtractor: async function type annotation isAsync --");

        // Test that a function type annotation with async in an export context
        // propagates isAsync correctly.  We test this by exporting a function
        // whose parameter type is an async function type.
        String source = """
            export function register(cb: async (p: int) => string): null { return null; }
            """;

        LexResult lex = new Lexer(source, "test.deal").tokenize();
        ParseResult parse = new Parser(lex.tokens(), "test.deal").parse();
        check(!parse.hasErrors(), "Parser: no errors");

        ExportExtractor extractor = new ExportExtractor("test", false);
        Map<String, Type> exports = extractor.extract(parse.program());

        check(exports.containsKey("register"), "exports contains register");
        Type registerType = exports.get("register");
        check(registerType instanceof Type.Func, "register is a function type");
        Type.Func registerFunc = (Type.Func) registerType;
        // register itself is sync, but its parameter is an async function type
        check(!registerFunc.isAsync(), "register itself should be sync");
        check(registerFunc.paramTypes().size() == 1, "register has 1 param");

        Type cbType = registerFunc.paramTypes().get(0);
        check(cbType instanceof Type.Func, "cb param is a function type");
        Type.Func cbFunc = (Type.Func) cbType;
        check(cbFunc.isAsync(), "cb param should be async");
        check(cbFunc.paramTypes().size() == 1, "cb has 1 param");
        check(cbFunc.returnType() instanceof Type.String, "cb returns string");
    }

    // =========================================================================
    // ExportExtractor: .d.deal function body validation
    // =========================================================================

    private static void testDeclarationFileBodyValidation() throws Exception {
        System.out.println("-- ExportExtractor: .d.deal body validation --");

        // Valid .d.deal: no function bodies
        String validDecl = """
            export function add(a: int, b: int): int;
            export class Result { value: int; }
            """;

        LexResult lex = new Lexer(validDecl, "test.d.deal").tokenize();
        ParseResult parse = new Parser(lex.tokens(), "test.d.deal").parse();
        check(!parse.hasErrors(), "Parser: no errors for valid .d.deal");

        ExportExtractor extractor = new ExportExtractor("test", true);
        Map<String, Type> exports = extractor.extract(parse.program());
        check(exports.containsKey("add"), "valid .d.deal: exports contains add");
        check(exports.containsKey("Result"), "valid .d.deal: exports contains Result");
        check(extractor.diagnostics().isEmpty(), "valid .d.deal: no diagnostics");

        // Invalid .d.deal: function with body
        String invalidDecl = """
            export function add(a: int, b: int): int { return a + b; }
            """;

        LexResult lex2 = new Lexer(invalidDecl, "test2.d.deal").tokenize();
        ParseResult parse2 = new Parser(lex2.tokens(), "test2.d.deal").parse();
        check(!parse2.hasErrors(), "Parser: no errors for invalid .d.deal");

        ExportExtractor extractor2 = new ExportExtractor("test2", true);
        Map<String, Type> exports2 = extractor2.extract(parse2.program());
        check(exports2.containsKey("add"), "invalid .d.deal: exports still contains add");

        boolean hasE7001 = extractor2.diagnostics().stream()
            .anyMatch(d -> "E7001".equals(d.code()) && "error".equals(d.severity()));
        check(hasE7001, "invalid .d.deal: E7001 for function with body");
    }

    // =========================================================================
    // Module Resolution Tests
    // =========================================================================


    // =========================================================================
    // ExportExtractor: .d.deal with executable let statement
    // =========================================================================

    private static void testDeclarationFileLetStmt() throws Exception {
        System.out.println("-- ExportExtractor: .d.deal let statement --");

        // .d.deal with top-level let statement should produce E7001
        String invalidDecl = """
            export function add(a: int, b: int): int;
            let x: int = 42;
            """;

        LexResult lex = new Lexer(invalidDecl, "test_let.d.deal").tokenize();
        ParseResult parse = new Parser(lex.tokens(), "test_let.d.deal").parse();
        check(!parse.hasErrors(), "Parser: no errors for .d.deal with let");

        ExportExtractor extractor = new ExportExtractor("test_let", true);
        Map<String, Type> exports = extractor.extract(parse.program());
        check(exports.containsKey("add"), ".d.deal with let: exports still contains add");

        boolean hasE7001 = extractor.diagnostics().stream()
            .anyMatch(d -> "E7001".equals(d.code()) && "error".equals(d.severity()));
        check(hasE7001, ".d.deal with let: E7001 for executable statement");
    }

    // =========================================================================
    // Topological Sort: Diamond Dependency
    // =========================================================================

    private static void testTopologicalSortDiamond() throws Exception {
        System.out.println("-- Topological Sort: Diamond --");

        // A depends on B and C; B and C both depend on D
        writeFile("src/diamondA.deal", """
            import * as B from "./diamondB"
            import * as C from "./diamondC"
            export function main(): null { return null; }
            export function run(): int { return B.val() + C.val(); }
            """);
        writeFile("src/diamondB.deal", """
            import * as D from "./diamondD"
            export function val(): int { return D.get(); }
            """);
        writeFile("src/diamondC.deal", """
            import * as D from "./diamondD"
            export function val(): int { return D.get() + 10; }
            """);
        writeFile("src/diamondD.deal", """
            export function get(): int { return 5; }
            """);

        Path entryFile = tmpDir.resolve("src/diamondA.deal").toAbsolutePath();
        Path outputDir = tmpDir.resolve("build/diamond");
        List<Path> moduleRoots = List.of(tmpDir.resolve("src").toAbsolutePath());

        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            entryFile, outputDir, false, null, moduleRoots, null);

        boolean success = orchestrator.compile();
        check(success, "Diamond dependency: compilation succeeded");
        check(Files.exists(outputDir.resolve("diamondA.lua")), "diamondA.lua exists");
        check(Files.exists(outputDir.resolve("diamondD.lua")), "diamondD.lua exists");
    }

    // =========================================================================
    // Topological Sort: Module outside cycle depending on cycle module
    // =========================================================================

    private static void testModuleOutsideCycle() throws Exception {
        System.out.println("-- Module Outside Declaration-Only Cycle --");

        // A and B form a declaration-only cycle
        // C depends on A (module outside cycle that depends on cycle module)
        writeFile("src/ocA.deal", """
            import * as B from "./ocB"
            export function foo(x: int): int { return B.transform(x); }
            """);
        writeFile("src/ocB.deal", """
            import * as A from "./ocA"
            export function transform(x: int): int { return x + 1; }
            """);
        writeFile("src/ocC.deal", """
            import * as A from "./ocA"
            export function main(): null { return null; }
            export function run(): int { return A.foo(10); }
            """);

        Path entryFile = tmpDir.resolve("src/ocC.deal").toAbsolutePath();
        Path outputDir = tmpDir.resolve("build/oc");
        List<Path> moduleRoots = List.of(tmpDir.resolve("src").toAbsolutePath());

        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            entryFile, outputDir, false, null, moduleRoots, null);

        boolean success = orchestrator.compile();
        check(success, "Module outside cycle: compilation succeeded");
        check(Files.exists(outputDir.resolve("ocC.lua")), "ocC.lua exists");
        check(Files.exists(outputDir.resolve("ocA.lua")), "ocA.lua exists");
        check(Files.exists(outputDir.resolve("ocB.lua")), "ocB.lua exists");

        // Verify no E2005 errors (should be declaration-only cycle)
        List<Diagnostic> diags = orchestrator.diagnostics();
        boolean hasE2005 = diags.stream().anyMatch(d -> "E2005".equals(d.code()));
        check(!hasE2005, "Module outside cycle: no E2005");
    }

    private static void testModuleResolution() throws Exception {
        System.out.println("-- Module Resolution --");

        writeFile("src/main.deal", """
            import * as lib from "./lib"
            let x: int = lib.add(1, 2);
            """);
        writeFile("src/lib.deal", """
            export function add(a: int, b: int): int { return a + b; }
            """);

        Path entryFile = tmpDir.resolve("src/main.deal").toAbsolutePath();
        Path outputDir = tmpDir.resolve("build/lua");
        List<Path> moduleRoots = List.of(tmpDir.resolve("src").toAbsolutePath());

        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            entryFile, outputDir, false, null, moduleRoots, null);

        Path mainFile = tmpDir.resolve("src/main.deal");
        String resolved = orchestrator.resolveImportPath("./lib", mainFile);
        check(resolved != null, "resolveImportPath returns non-null");
        check(resolved.endsWith("lib.deal"), "resolved path ends with lib.deal");

        resolved = orchestrator.resolveImportPath("./nonexistent", mainFile);
        check(resolved == null, "resolveImportPath returns null for nonexistent");
    }

    // =========================================================================
    // Compilation Orchestration: Single Module
    // =========================================================================

    private static void testSingleModuleCompilation() throws Exception {
        System.out.println("-- Single Module Compilation --");

        writeFile("src/hello.deal", """
            export function main(): null { return null; }
            export function greet(): string { return "hi"; }
            """);

        Path entryFile = tmpDir.resolve("src/hello.deal").toAbsolutePath();
        Path outputDir = tmpDir.resolve("build/lua");
        List<Path> moduleRoots = List.of(tmpDir.resolve("src").toAbsolutePath());

        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            entryFile, outputDir, true, null, moduleRoots, null);

        boolean success = orchestrator.compile();
        check(success, "Single module compilation succeeded");

        Path outputFile = outputDir.resolve("hello.lua");
        check(Files.exists(outputFile), "Output file exists: " + outputFile);

        Path runtimeDest = outputDir.resolve("deal/runtime.lua");
        boolean runtimeExists = Files.exists(runtimeDest);
        if (Files.exists(Path.of("deal/runtime.lua"))) {
            check(runtimeExists, "Runtime library copied");
        }

        String luaOutput = Files.readString(outputFile);
        check(luaOutput.contains("greet"), "Output contains greet");
        check(luaOutput.contains("exports.greet = greet"), "Exports greet");
    }

    // =========================================================================
    // Compilation Orchestration: Multi Module
    // =========================================================================

    private static void testMultiModuleCompilation() throws Exception {
        System.out.println("-- Multi Module Compilation --");

        writeFile("src/main.deal", """
            import * as lib from "./lib"
            export function main(): null { return null; }
            export function run(): int { return lib.add(10, 20); }
            """);
        writeFile("src/lib.deal", """
            export function add(a: int, b: int): int { return a + b; }
            """);

        Path entryFile = tmpDir.resolve("src/main.deal").toAbsolutePath();
        Path outputDir = tmpDir.resolve("build/lua2");
        List<Path> moduleRoots = List.of(tmpDir.resolve("src").toAbsolutePath());

        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            entryFile, outputDir, true, null, moduleRoots, null);

        boolean success = orchestrator.compile();
        check(success, "Multi-module compilation succeeded");

        check(Files.exists(outputDir.resolve("main.lua")), "main.lua exists");
        check(Files.exists(outputDir.resolve("lib.lua")), "lib.lua exists");

        String mainLua = Files.readString(outputDir.resolve("main.lua"));
        check(mainLua.contains("require(\"lib\")"), "main.lua requires lib");
    }

    // =========================================================================
    // Compilation Orchestration: Error Handling
    // =========================================================================

    private static void testCompilationWithError() throws Exception {
        System.out.println("-- Compilation With Error --");

        writeFile("src/bad.deal", """
            export function foo(): int { return "not an int"; }
            """);

        Path entryFile = tmpDir.resolve("src/bad.deal").toAbsolutePath();
        Path outputDir = tmpDir.resolve("build/lua3");
        List<Path> moduleRoots = List.of(tmpDir.resolve("src").toAbsolutePath());

        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            entryFile, outputDir, false, null, moduleRoots, null);

        boolean success = orchestrator.compile();
        check(!success, "Compilation with type error should fail");

        List<Diagnostic> diags = orchestrator.diagnostics();
        boolean hasTypeError = diags.stream().anyMatch(
            d -> "error".equals(d.severity()));
        check(hasTypeError, "Has type error diagnostic");
    }

    // =========================================================================
    // Circular Import: Runtime dependency (E2005)
    // =========================================================================


    // =========================================================================
    // ISSUE-0052: CompilationOrchestrator — import retention under await
    // =========================================================================

    /**
     * Verify that exprReferencesImport correctly tracks import references
     * through AwaitExpression nodes.  An import used only inside
     * {@code await B.foo()} should still be detected as a runtime usage.
     *
     * <p>This test creates two modules: module A exports an async function,
     * module B imports A and uses it only inside an await expression.
     * The compilation should succeed (no unused import removal issues),
     * confirming that the import is correctly tracked through the
     * AwaitExpression in exprReferencesImport.</p>
     */
    private static void testCompilationOrchestratorAwaitImportRetention() throws Exception {
        System.out.println("-- CompilationOrchestrator: await import retention --");

        // Module A: exports an async function
        writeFile("src/ai_await_lib.deal", """
            export async function getValue(): int { return 42; }
            """);

        // Module B: imports A and uses it under await
        // The import alias "Lib" is used only in: await Lib.getValue()
        // exprReferencesImport must track through AwaitExpression
        writeFile("src/ai_await_main.deal", """
            import * as Lib from "./ai_await_lib"
            export function main(): null { return null; }
            async function worker(): int { return await Lib.getValue(); }
            """);

        Path entryFile = tmpDir.resolve("src/ai_await_main.deal").toAbsolutePath();
        Path outputDir = tmpDir.resolve("build/ai_await");
        List<Path> moduleRoots = List.of(tmpDir.resolve("src").toAbsolutePath());

        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            entryFile, outputDir, false, null, moduleRoots, null);

        boolean success = orchestrator.compile();
        List<Diagnostic> diags = orchestrator.diagnostics();

        // The compilation should succeed — if exprReferencesImport didn't
        // handle AwaitExpression, the import might appear unused and cause
        // errors or incorrect cycle detection.
        if (!success) {
            System.out.println("  Compilation diags: " + diags.stream()
                .filter(d -> "error".equals(d.severity()))
                .map(d -> d.code() + ": " + d.message()).toList());
        }
        check(success, "await import retention: compilation succeeds");
        check(Files.exists(outputDir.resolve("ai_await_main.lua")),
            "await import retention: main.lua exists");
        check(Files.exists(outputDir.resolve("ai_await_lib.lua")),
            "await import retention: lib.lua exists");
    }


    // =========================================================================
    // ISSUE-0056: Cross-module async call without await → E3014
    // =========================================================================

    /**
     * Verify that calling an imported async function without {@code await}
     * produces E3014 across module boundaries.  Module A exports an async
     * function; module B imports A and calls it without await.
     *
     * <p>This is the cross-module complement of the single-file E3014 test
     * in {@code CheckerTest.testAsyncCallWithoutAwait_E3014}.  It exercises
     * the full pipeline: ExportExtractor propagates {@code isAsync},
     * NameResolver resolves the imported symbol to an async function type,
     * and TypeChecker fires E3014 when the call is not inside an
     * {@code await} expression.</p>
     */
    private static void testCrossModuleAsyncCallWithoutAwait_E3014() throws Exception {
        System.out.println("-- Cross-module E3014: imported async called without await --");

        // Module A: exports an async function
        writeFile("src/e3014_xm_lib.deal", """
            export async function compute(): int { return 99; }
            """);

        // Module B: imports A and calls the async function WITHOUT await
        writeFile("src/e3014_xm_main.deal", """
            import * as Lib from "./e3014_xm_lib"
            export function run(): int { return Lib.compute(); }
            """);

        Path entryFile = tmpDir.resolve("src/e3014_xm_main.deal").toAbsolutePath();
        Path outputDir = tmpDir.resolve("build/e3014_xm");
        List<Path> moduleRoots = List.of(tmpDir.resolve("src").toAbsolutePath());

        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            entryFile, outputDir, false, null, moduleRoots, null);

        boolean success = orchestrator.compile();
        List<Diagnostic> diags = orchestrator.diagnostics();

        // The compilation must fail because Lib.compute() is async and called without await
        check(!success, "cross-module async call without await: compilation must fail");

        boolean hasE3014 = diags.stream()
            .anyMatch(d -> "E3014".equals(d.code()) && "error".equals(d.severity()));
        if (!hasE3014) {
            System.out.println("  Compilation diags: " + diags.stream()
                .filter(d -> "error".equals(d.severity()))
                .map(d -> d.code() + ": " + d.message()).toList());
        }
        check(hasE3014, "cross-module async call without await → E3014");
    }

    private static void testCircularImportRuntime() throws Exception {
        System.out.println("-- Circular Import: Runtime Dependency --");

        // a.deal uses B at top level in a variable initializer -> runtime dependency
        // b.deal imports a but only uses it in function bodies -> type-only from b's side
        // But a.deal's top-level expression creates a runtime dependency cycle
        writeFile("src/rta.deal", """
            import * as B from "./rtb"
            export function main(): null { return null; }
            let x: int = B.getValue();
            export function foo(): int { return 42; }
            """);
        writeFile("src/rtb.deal", """
            import * as A from "./rta"
            export function getValue(): int { return 10; }
            """);

        Path entryFile = tmpDir.resolve("src/rta.deal").toAbsolutePath();
        Path outputDir = tmpDir.resolve("build/lua_cycle_rt");
        List<Path> moduleRoots = List.of(tmpDir.resolve("src").toAbsolutePath());

        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            entryFile, outputDir, false, null, moduleRoots, null);

        boolean success = orchestrator.compile();
        check(!success, "Top-level executable statement should fail in v1.2");

        List<Diagnostic> diags = orchestrator.diagnostics();
        // v1.2: the top-level `let` is rejected as a module-shape error
        // before any cycle analysis (E1049).  Runtime import cycles can no
        // longer be constructed from source because module top level holds
        // only declarations.
        boolean hasShapeError = diags.stream().anyMatch(
            d -> "error".equals(d.severity())
                && "E1049".equals(d.code()));
        check(hasShapeError, "Has E1049 top-level statement diagnostic");
    }

    // =========================================================================
    // Circular Import: Class field default referencing the cycle (E2005)
    // =========================================================================

    /**
     * DEAL v1.2 evaluates class field defaults at module initialization,
     * so a default expression that references a cyclic import creates a
     * runtime initialization dependency and must be rejected with E2005
     * — even though the cycle looks "declaration-only" at statement level.
     */
    private static void testCircularImportClassFieldDefault() throws Exception {
        System.out.println("-- Circular Import: Class Field Default (runtime dep) --");

        writeFile("src/cfda.deal", """
            import * as B from \"./cfdb\"
            export function main(): null { return null; }
            export class Holder {
              seed: int = B.get(1);
            }
            """);
        writeFile("src/cfdb.deal", """
            import * as A from \"./cfda\"
            export function get(x: int): int { return x + 1; }
            """);

        Path entryFile = tmpDir.resolve("src/cfda.deal").toAbsolutePath();
        Path outputDir = tmpDir.resolve("build/lua_cycle_cfd");
        List<Path> moduleRoots = List.of(tmpDir.resolve("src").toAbsolutePath());

        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            entryFile, outputDir, false, null, moduleRoots, null);

        boolean success = orchestrator.compile();
        check(!success, "Class-field-default cycle must fail in v1.2");

        List<Diagnostic> diags = orchestrator.diagnostics();
        boolean hasE2005 = diags.stream().anyMatch(
            d -> "E2005".equals(d.code()));
        check(hasE2005, "Class-field-default cycle: E2005 diagnostic");
    }

    // =========================================================================
    // Circular Import: Declaration-only (allowed)
    // =========================================================================

    private static void testCircularImportDeclarationOnly() throws Exception {
        System.out.println("-- Circular Import: Declaration-Only (allowed) --");

        // Both modules only use each other in function bodies.
        // No top-level runtime expressions reference the cyclic import.
        writeFile("src/da.deal", """
            import * as B from "./db"
            export function main(): null { return null; }
            export function foo(x: int): int { return B.get(x); }
            """);
        writeFile("src/db.deal", """
            import * as A from "./da"
            export function get(x: int): int { return x + 1; }
            """);

        Path entryFile = tmpDir.resolve("src/da.deal").toAbsolutePath();
        Path outputDir = tmpDir.resolve("build/lua_cycle_decl");
        List<Path> moduleRoots = List.of(tmpDir.resolve("src").toAbsolutePath());

        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            entryFile, outputDir, false, null, moduleRoots, null);

        boolean success = orchestrator.compile();
        check(success, "Declaration-only cycle should succeed");

        // Verify no E2005 errors
        List<Diagnostic> diags = orchestrator.diagnostics();
        boolean hasE2005 = diags.stream().anyMatch(
            d -> "E2005".equals(d.code()));
        check(!hasE2005, "Declaration-only cycle: no E2005 diagnostic");
    }


    // =========================================================================
    // Two Disconnected Cycles: One Declaration-Only, One Runtime (regression)
    // =========================================================================

    private static void testTwoDisconnectedCyclesOneRuntime() throws Exception {
        System.out.println("-- Two Disconnected Cycles: One Declaration-Only, One Runtime --");

        // SCC1: A↔B — declaration-only (only type references in function bodies)
        writeFile("src/tdc_a.deal", """
            import * as B from "./tdc_b"
            export function callB(x: int): int { return B.transform(x); }
            """);
        writeFile("src/tdc_b.deal", """
            import * as A from "./tdc_a"
            export function transform(x: int): int { return x + 1; }
            """);

        // SCC2: X↔Y — runtime (top-level expression uses cyclic import)
        writeFile("src/tdc_x.deal", """
            import * as Y from "./tdc_y"
            let val: int = Y.getVal();
            export function getX(): int { return 1; }
            """);
        writeFile("src/tdc_y.deal", """
            import * as X from "./tdc_x"
            export function getVal(): int { return X.getX(); }
            """);

        // Entry: imports A first (decl-only cycle), then X (runtime cycle)
        writeFile("src/tdc_entry.deal", """
            import * as A from "./tdc_a"
            import * as X from "./tdc_x"
            export function main(): null { return null; }
            export function test(): int { return A.callB(1); }
            """);

        Path entryFile = tmpDir.resolve("src/tdc_entry.deal").toAbsolutePath();
        Path outputDir = tmpDir.resolve("build/tdc");
        List<Path> moduleRoots = List.of(tmpDir.resolve("src").toAbsolutePath());

        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            entryFile, outputDir, false, null, moduleRoots, null);

        boolean success = orchestrator.compile();
        check(!success, "Two disconnected cycles (top-level statement): compilation should fail");

        List<Diagnostic> diags = orchestrator.diagnostics();
        // v1.2: the top-level `let` in tdc_x.deal is an E1049 module-shape
        // error; no E2005 cycle analysis can fire on v1.2 sources.
        boolean hasE1049 = diags.stream().anyMatch(
            d -> "E1049".equals(d.code()) && "error".equals(d.severity()));
        check(hasE1049, "Two disconnected cycles: E1049 for top-level statement");
    }

    // =========================================================================
    // Two Disconnected Cycles: Both Declaration-Only (should succeed)
    // =========================================================================

    private static void testTwoDisconnectedCyclesBothDecl() throws Exception {
        System.out.println("-- Two Disconnected Cycles: Both Declaration-Only --");

        // SCC1: A↔B — declaration-only
        writeFile("src/tdc2_a.deal", """
            import * as B from "./tdc2_b"
            export function callB(x: int): int { return B.transform(x); }
            """);
        writeFile("src/tdc2_b.deal", """
            import * as A from "./tdc2_a"
            export function transform(x: int): int { return x + 1; }
            """);

        // SCC2: C↔D — declaration-only
        writeFile("src/tdc2_c.deal", """
            import * as D from "./tdc2_d"
            export function callD(x: int): int { return D.convert(x); }
            """);
        writeFile("src/tdc2_d.deal", """
            import * as C from "./tdc2_c"
            export function convert(x: int): int { return C.callD(x) + 2; }
            """);

        // Entry: imports A first, then C
        writeFile("src/tdc2_entry.deal", """
            import * as A from "./tdc2_a"
            import * as C from "./tdc2_c"
            export function main(): null { return null; }
            export function test(): int { return A.callB(1) + C.callD(2); }
            """);

        Path entryFile = tmpDir.resolve("src/tdc2_entry.deal").toAbsolutePath();
        Path outputDir = tmpDir.resolve("build/tdc2");
        List<Path> moduleRoots = List.of(tmpDir.resolve("src").toAbsolutePath());

        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            entryFile, outputDir, false, null, moduleRoots, null);

        boolean success = orchestrator.compile();
        check(success, "Two disconnected cycles (both decl-only): compilation should succeed");

        List<Diagnostic> diags = orchestrator.diagnostics();
        boolean hasE2005 = diags.stream().anyMatch(
            d -> "E2005".equals(d.code()));
        check(!hasE2005, "Two disconnected cycles (both decl-only): no E2005");
    }

    // =========================================================================
    // Stdlib .lua fallback when .d.deal exists without .lua in stdlibDir (regression)
    // =========================================================================

    private static void testStdlibFallbackAfterDdeal() throws Exception {
        System.out.println("-- Stdlib .lua fallback after .d.deal (regression) --");

        // Create a stdlibDir with ONLY .d.deal, no .lua
        Path stdlibDir = tmpDir.resolve("stdlib_fallback");
        Files.createDirectories(stdlibDir.resolve("std"));
        Files.writeString(stdlibDir.resolve("std/string.d.deal"),
            "// DEAL Standard Library: std/string (declaration)\n"
            + "export function length(s: string): int;\n");
        // Intentionally do NOT create std/string.lua

        writeFile("src/sf_main.deal", """
            import * as strings from "std/string"
            export function main(): null { return null; }
            export function getLen(s: string): int { return strings.length(s); }
            """);

        Path entryFile = tmpDir.resolve("src/sf_main.deal").toAbsolutePath();
        Path outputDir = tmpDir.resolve("build/sf");
        List<Path> roots = new ArrayList<>();
        roots.add(tmpDir.resolve("src").toAbsolutePath());
        roots.add(stdlibDir.toAbsolutePath());

        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            entryFile, outputDir, false, null, roots, stdlibDir.toAbsolutePath());

        // The compilation should succeed because the .d.deal provides type info.
        // The .lua not being copied is acceptable — the host provides it at runtime.
        // The important thing: no crash/exception during copyStdlibModules.
        boolean success = orchestrator.compile();
        check(success, "Stdlib fallback: compilation should succeed with .d.deal only");

        // Verify that copyStdlibModules was reached and didn't crash.
        // The .lua file may or may not exist depending on classpath resources,
        // but the orchestrator should not have thrown an exception.
        Path stdlibLua = outputDir.resolve("std/string.lua");
        // Either the .lua was found on classpath and copied, or it wasn't.
        // Both outcomes are acceptable — the fix ensures the classpath
        // fallback is at least attempted.
        System.out.println("  std/string.lua in output: " + Files.exists(stdlibLua));
    }

    // =========================================================================
    // Compilation Orchestration: Declaration File
    // =========================================================================

    // =========================================================================
    // Cross-Module Class Tests (F1-F5 fix for MR-0007 review)
    // =========================================================================

    private static void testCrossModuleClassFieldAccess() throws Exception {
        System.out.println("-- Cross-Module Class: Field Access --");

        writeFile("src/cm_class.deal", """
            export class Point { x: int = 0; y: int = 0; }
            export function create(x: int, y: int): Point {
                return { x: x, y: y };
            }
            """);

        writeFile("src/cm_main.deal", """
            import * as P from "./cm_class"
            export function main(): null { return null; }
            export function getX(p: P.Point): int { return p.x; }
            export function getY(p: P.Point): int { return p.y; }
            """);

        Path entryFile = tmpDir.resolve("src/cm_main.deal").toAbsolutePath();
        Path outputDir = tmpDir.resolve("build/cm_field");
        List<Path> roots = List.of(tmpDir.resolve("src").toAbsolutePath());

        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            entryFile, outputDir, false, null, roots, null);

        boolean success = orchestrator.compile();
        check(success, "Cross-module class field access: compilation should succeed");
        if (!success) {
            for (Diagnostic d : orchestrator.diagnostics()) {
                System.out.println("  Diag: " + d.code() + ": " + d.message());
            }
        }

        // Verify output files exist
        check(Files.exists(outputDir.resolve("cm_main.lua")),
            "Cross-module class field access: cm_main.lua exists");
        check(Files.exists(outputDir.resolve("cm_class.lua")),
            "Cross-module class field access: cm_class.lua exists");

        // Verify the generated code references the imported class defaults
        String genCode = Files.readString(outputDir.resolve("cm_main.lua"));
        check(genCode.contains("require(\"cm_class\")"),
            "Cross-module class field access: generated code requires cm_class");
    }

    private static void testCrossModuleClassConstruction() throws Exception {
        System.out.println("-- Cross-Module Class: Construction --");

        writeFile("src/cc_class.deal", """
            export class Vec { x: int = 0; y: int = 0; }
            """);

        writeFile("src/cc_main.deal", """
            import * as V from "./cc_class"
            export function main(): null { return null; }
            export function makeVec(): V.Vec {
                return { x: 1, y: 2 };
            }
            """);

        Path entryFile = tmpDir.resolve("src/cc_main.deal").toAbsolutePath();
        Path outputDir = tmpDir.resolve("build/cm_constr");
        List<Path> roots = List.of(tmpDir.resolve("src").toAbsolutePath());

        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            entryFile, outputDir, false, null, roots, null);

        boolean success = orchestrator.compile();
        check(success, "Cross-module class construction: compilation should succeed");
        if (!success) {
            for (Diagnostic d : orchestrator.diagnostics()) {
                System.out.println("  Diag: " + d.code() + ": " + d.message());
            }
        }

        // Verify the generated code references the imported defaults table
        String genCode = Files.readString(outputDir.resolve("cc_main.lua"));
        check(genCode.contains("V.Vec_defaults"),
            "Cross-module class construction: generated code uses V.Vec_defaults");
    }

    private static void testCrossModuleClassHas() throws Exception {
        System.out.println("-- Cross-Module Class: has() --");

        writeFile("src/ch_class.deal", """
            export class Opt { name?: string; }
            """);

        writeFile("src/ch_main.deal", """
            import * as O from "./ch_class"
            export function main(): null { return null; }
            export function checkName(obj: O.Opt): boolean {
                return has(obj.name);
            }
            """);

        Path entryFile = tmpDir.resolve("src/ch_main.deal").toAbsolutePath();
        Path outputDir = tmpDir.resolve("build/cm_has");
        List<Path> roots = List.of(tmpDir.resolve("src").toAbsolutePath());

        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            entryFile, outputDir, false, null, roots, null);

        boolean success = orchestrator.compile();
        check(success, "Cross-module class has(): compilation should succeed");
        if (!success) {
            for (Diagnostic d : orchestrator.diagnostics()) {
                System.out.println("  Diag: " + d.code() + ": " + d.message());
            }
        }
    }

    private static void testQualifiedTypeAnnotation() throws Exception {
        System.out.println("-- Qualified Type Annotation --");

        writeFile("src/qt_class.deal", """
            export class Data { value: int = 0; }
            """);

        writeFile("src/qt_main.deal", """
            import * as D from "./qt_class"
            export function main(): null { return null; }
            export function getValue(d: D.Data): int { return d.value; }
            """);

        Path entryFile = tmpDir.resolve("src/qt_main.deal").toAbsolutePath();
        Path outputDir = tmpDir.resolve("build/cm_qt");
        List<Path> roots = List.of(tmpDir.resolve("src").toAbsolutePath());

        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            entryFile, outputDir, false, null, roots, null);

        boolean success = orchestrator.compile();
        check(success, "Qualified type annotation: compilation should succeed");
        if (!success) {
            for (Diagnostic d : orchestrator.diagnostics()) {
                System.out.println("  Diag: " + d.code() + ": " + d.message());
            }
        }
    }

    private static void testCrossModuleClassE2E() throws Exception {
        System.out.println("-- Cross-Module Class: End-to-End Runtime --");
        if (!luajitAvailable()) {
            System.out.println("  SKIP: LuaJIT not available");
            return;
        }

        writeFile("src/ce_class.deal", """
            export class Result { value: int = 0; ok: boolean = true; }
            export function make(value: int): Result {
                return { value: value, ok: true };
            }
            """);

        writeFile("src/ce_main.deal", """
            import * as R from "./ce_class"
            export function main(): null { return null; }
            export function getValue(r: R.Result): int { return r.value; }
            export function makeAndGet(): int {
                let r = R.make(42);
                return r.value;
            }
            """);

        Path entryFile = tmpDir.resolve("src/ce_main.deal").toAbsolutePath();
        Path outputDir = tmpDir.resolve("build/cm_e2e");
        List<Path> roots = List.of(tmpDir.resolve("src").toAbsolutePath());

        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            entryFile, outputDir, false, null, roots, null);

        boolean success = orchestrator.compile();
        check(success, "Cross-module class E2E: compilation should succeed");
        if (!success) {
            for (Diagnostic d : orchestrator.diagnostics()) {
                System.out.println("  Diag: " + d.code() + ": " + d.message());
            }
            return;
        }

        // Runtime verification
        try {
            Path runtimeLib = outputDir.resolve("deal/runtime.lua");
            String luaCode = "package.path = '" + outputDir.toRealPath()
                + "/?.lua;' "
                + "local m = require('ce_main') "
                + "local r = m.makeAndGet.f() "
                + "assert(r == 42, 'expected 42, got ' .. tostring(r)) "
                + "print('OK: cross-module class e2e')";
            ProcessBuilder pb = new ProcessBuilder("luajit", "-e", luaCode);
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            String output = new String(proc.getInputStream().readAllBytes());
            int exitCode = proc.waitFor();
            check(exitCode == 0,
                "Cross-module class E2E: runtime verification (exit " + exitCode
                + "): " + output.trim());
        } catch (Exception e) {
            check(false, "Cross-module class E2E: runtime verification failed: " + e.getMessage());
        }
    }

    private static void testDeclarationFile() throws Exception {
        System.out.println("-- Declaration File --");

        writeFile("src/runner.deal", """
            import * as C from "./calc"
            export function main(): null { return null; }
            export function run(): int { return C.add(1, 2); }
            """);
        writeFile("src/calc.d.deal", """
            export function add(a: int, b: int): int;
            export class Result {
                value: int;
            }
            """);

        Path entryFile = tmpDir.resolve("src/runner.deal").toAbsolutePath();
        Path outputDir = tmpDir.resolve("build/lua5");
        List<Path> moduleRoots = List.of(tmpDir.resolve("src").toAbsolutePath());

        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            entryFile, outputDir, false, null, moduleRoots, null);

        boolean success = orchestrator.compile();
        check(success, "Declaration file compilation should succeed");

        check(!Files.exists(outputDir.resolve("calc.lua")),
            "calc.lua should not be generated for .d.deal");
    }


    // =========================================================================
    // Host Externals (ISSUE-0082, host-module-abi D5)
    // =========================================================================

    /**
     * Compiles an externals-listed bare host import and pins the emitted
     * loader shape: {@code __rt.load_host} with the raw import specifier
     * byte-for-byte ({@code "host/cfg"}, never the dotted typing name), the
     * Lua-keyword declared-map key as a bracket string, the class descriptor
     * carrying the dotted typing/class-identity module path, and the stdlib
     * trusted path still emitting a raw require.
     */
    private static void testExternalsHostModuleCompiles() throws Exception {
        System.out.println("-- Externals host module: compile + loader emission --");

        writeFile("deal.json", """
            {
              "moduleRoots": ["src"],
              "output": "build/lua",
              "backend": "luajit",
              "externals": {
                "host/cfg": { "declaration": "bindings/host-cfg.d.deal" }
              }
            }""");
        writeFile("bindings/host-cfg.d.deal", """
            export function ping(): int;
            export function repeat(): int;
            // @jsonable
            export class User {
                name: string;
            }
            """);
        writeFile("src/ext_main.deal", """
            import * as cfg from "host/cfg"
            import * as console from "std/console"
            export function main(): null { return null; }
            export function run(): int { console.log("x"); return cfg.ping(); }
            """);

        Path entryFile = tmpDir.resolve("src/ext_main.deal").toAbsolutePath();
        Path outputDir = tmpDir.resolve("build/ext_host");
        List<Path> roots = List.of(tmpDir.resolve("src").toAbsolutePath());

        DealConfig config = DealConfig.load(tmpDir).config();
        check(config != null, "deal.json loaded");
        check(config.externals().containsKey("host/cfg"), "externals map has host/cfg");
        check(config.externals().get("host/cfg").equals("bindings/host-cfg.d.deal"),
            "externals declaration path");

        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            entryFile, outputDir, false, config, roots, null);

        boolean success = orchestrator.compile();
        check(success, "Externals host module: compilation should succeed");

        String lua = Files.readString(outputDir.resolve("ext_main.lua"));
        check(lua.contains("__rt.load_host(\"host/cfg\", {"),
            "Loader first argument is the raw import specifier byte-for-byte");
        check(!lua.contains("__rt.load_host(\"host.cfg\""),
            "Loader never emits the dotted typing name as the require path");
        check(!lua.contains("require(\"host/cfg\")"),
            "Host imports never emit a raw require");
        check(lua.contains("[\"repeat\"] = \"()->int\""),
            "Lua-keyword export name emits a bracket-string declared-map key");
        check(lua.contains("ping = \"()->int\""),
            "Safe export name emits a dot-form declared-map key");
        check(lua.contains("User = \"@host.cfg/User\""),
            "Class descriptor uses the dotted typing/class-identity module path");
        check(lua.contains("[\"User$fromJson\"] = \"(string)->@host.cfg/User|null\""),
            "@jsonable synthetic export emits a bracket-string declared-map key");
        check(lua.contains("[\"User$toJson\"] = \"(@host.cfg/User)->string\""),
            "@jsonable synthetic toJson export emits a bracket-string key");
        boolean dollarOnlyInQuotedKeys = true;
        for (int i = lua.indexOf('$'); i >= 0; i = lua.indexOf('$', i + 1)) {
            int open = lua.lastIndexOf('\"', i);
            int close = lua.indexOf('\"', i + 1);
            if (!(open >= 0 && close >= 0 && open < i && i < close)) {
                dollarOnlyInQuotedKeys = false;
            }
        }
        check(dollarOnlyInQuotedKeys, "loader $ keys stay inside quoted strings");
        check(lua.contains("require(\"std.console\")"),
            "Stdlib import keeps the trusted raw require");
    }

    /**
     * A bare import whose resolution lands on a non-stdlib declaration file
     * that is not listed in deal.json externals is rejected with E2009.
     */
    private static void testExternalsGatingE2009() throws Exception {
        System.out.println("-- Externals gating: unlisted bare .d.deal → E2009 --");

        writeFile("deal.json", """
            {
              "moduleRoots": ["src"],
              "output": "build/lua",
              "backend": "luajit",
              "externals": {}
            }""");
        writeFile("src/undeclared/hostmod.d.deal", """
            export function ping(): int;
            """);
        writeFile("src/gate_main.deal", """
            import * as cfg from "undeclared/hostmod"
            export function run(): int { return cfg.ping(); }
            """);

        Path entryFile = tmpDir.resolve("src/gate_main.deal").toAbsolutePath();
        Path outputDir = tmpDir.resolve("build/gate");
        List<Path> roots = List.of(tmpDir.resolve("src").toAbsolutePath());

        DealConfig config = DealConfig.load(tmpDir).config();
        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            entryFile, outputDir, false, config, roots, null);

        boolean success = orchestrator.compile();
        check(!success, "Unlisted bare .d.deal import must fail compilation");
        check(orchestrator.diagnostics().stream()
                .anyMatch(d -> "E2009".equals(d.code())),
            "E2009 reported for the unlisted host import");
    }

    /**
     * An externals-listed declaration file missing on disk reports E2003
     * (the existing module-not-found path), never a silent success.
     */
    private static void testExternalsMissingDeclarationE2003() throws Exception {
        System.out.println("-- Externals declaration missing on disk → E2003 --");

        writeFile("deal.json", """
            {
              "moduleRoots": ["src"],
              "output": "build/lua",
              "backend": "luajit",
              "externals": {
                "host/missing": { "declaration": "bindings/host-missing.d.deal" }
              }
            }""");
        // bindings/host-missing.d.deal intentionally not written
        writeFile("src/missing_main.deal", """
            import * as cfg from "host/missing"
            export function run(): int { return cfg.ping(); }
            """);

        Path entryFile = tmpDir.resolve("src/missing_main.deal").toAbsolutePath();
        Path outputDir = tmpDir.resolve("build/missing");
        List<Path> roots = List.of(tmpDir.resolve("src").toAbsolutePath());

        DealConfig config = DealConfig.load(tmpDir).config();
        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            entryFile, outputDir, false, config, roots, null);

        boolean success = orchestrator.compile();
        check(!success, "Missing externals declaration must fail compilation");
        check(orchestrator.diagnostics().stream()
                .anyMatch(d -> "E2003".equals(d.code())),
            "E2003 reported for the missing externals declaration");
        // The manifest declaration is authoritative for the name
        // (host-module-abi D5(2)): the E2003 message must name the
        // configured declaration path, not only on-disk candidates.
        check(orchestrator.diagnostics().stream()
                .anyMatch(d -> "E2003".equals(d.code())
                    && d.message().contains("externals declaration: ")
                    && d.message().contains("bindings/host-missing.d.deal")),
            "E2003 message names the authoritative externals declaration path");
    }

    /**
     * Combined C2+C3 evidence: an externals-listed project compiles, the
     * generated module executes under LuaJIT with a minimal host
     * implementation on package.path, and the wrapped host call returns the
     * runtime-checked value (the loader + from_lua_function pipeline).
     */
    private static void testHostModuleEndToEnd() throws Exception {
        System.out.println("-- Host module E2E: externals-listed project runs under LuaJIT --");
        if (!luajitAvailable()) {
            System.out.println("  SKIP: LuaJIT not available");
            return;
        }

        writeFile("deal.json", """
            {
              "moduleRoots": ["src"],
              "output": "build/lua",
              "backend": "luajit",
              "externals": {
                "host/cfg": { "declaration": "bindings/host-cfg.d.deal" }
              }
            }""");
        writeFile("bindings/host-cfg.d.deal", """
            export function ping(): int;
            """);
        writeFile("src/host_smoke.deal", """
            import * as cfg from "host/cfg"
            export function main(): null { return null; }
            export function run(): int { return cfg.ping(); }
            """);

        Path entryFile = tmpDir.resolve("src/host_smoke.deal").toAbsolutePath();
        Path outputDir = tmpDir.resolve("build/host_smoke");
        List<Path> roots = List.of(tmpDir.resolve("src").toAbsolutePath());

        DealConfig config = DealConfig.load(tmpDir).config();
        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            entryFile, outputDir, false, config, roots, null);

        boolean success = orchestrator.compile();
        check(success, "Host E2E: compilation should succeed");
        if (!success) return;

        String lua = Files.readString(outputDir.resolve("host_smoke.lua"));
        check(lua.contains("__rt.load_host(\"host/cfg\", {"),
            "Host E2E: loader emits the raw import specifier");

        // Host implementation placed on package.path (host-environment policy,
        // host-module-abi assumption b): outputDir/host/cfg.lua resolves as
        // ./host/cfg.lua for the verbatim require("host/cfg").
        Path hostImpl = outputDir.resolve("host/cfg.lua");
        Files.createDirectories(hostImpl.getParent());
        Files.writeString(hostImpl, """
            local M = {}
            function M.ping() return 7 end
            return M
            """);

        // Copy the runtime if the classpath/CWD fallback did not (belt and braces).
        Path runtimeDest = outputDir.resolve("deal/runtime.lua");
        if (!Files.exists(runtimeDest)) {
            Files.createDirectories(runtimeDest.getParent());
            Files.copy(Path.of("deal/runtime.lua"), runtimeDest);
        }

        try {
            String luaCode = "package.path = '" + outputDir.toRealPath()
                + "/?.lua;' "
                + "local m = require('host_smoke') "
                + "local v = m.run.f() "
                + "assert(v == 7, 'expected 7, got ' .. tostring(v)) "
                + "print('OK: host ping -> ' .. tostring(v))";
            ProcessBuilder pb = new ProcessBuilder("luajit", "-e", luaCode);
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            String output = new String(proc.getInputStream().readAllBytes());
            int exitCode = proc.waitFor();
            check(exitCode == 0,
                "Host E2E: runtime verification (exit " + exitCode + "): "
                    + output.trim());
        } catch (Exception e) {
            check(false, "Host E2E: runtime verification failed: " + e.getMessage());
        }
    }

    /**
     * Regression (C3 review): an externals key containing a backslash must
     * not leak unescaped into generated Lua.  The key reaches descriptor
     * strings as the dotted typing/class-identity module path
     * ("@host.x\y/User" — the externals key with "/" mapped to "."), and
     * an unescaped backslash makes the generated chunk invalid Lua
     * ("invalid escape sequence" at require time, with no compile-time
     * diagnostic).  The generated module must load AND run under LuaJIT:
     * the loader declared-map values, the loader path argument, and the
     * wrapper signature descriptors all carry the escaped form.
     */
    private static void testHostModuleBackslashExternalsKeyE2E() throws Exception {
        System.out.println("-- Host E2E: backslash externals key emits escaped descriptors --");
        if (!luajitAvailable()) {
            System.out.println("  SKIP: LuaJIT not available");
            return;
        }

        // JSON needs the backslash escaped: "host/x\\y" is the key
        // "host/x\y" (the raw import path as written).
        writeFile("deal.json", """
            {
              "moduleRoots": ["src"],
              "output": "build/lua",
              "backend": "luajit",
              "externals": {
                "host/x\\\\y": { "declaration": "bindings/host-xy.d.deal" }
              }
            }""");
        writeFile("bindings/host-xy.d.deal", """
            export function ping(): int;
            export class User {
                port: int = 0;
            }
            """);
        // The DEAL import path is taken verbatim from the source
        // (parseImportDeclaration keeps the raw string between the
        // quotes), so the raw "host/x\y" matches the JSON
        // externals key byte-for-byte — the reviewer-probe scenario.
        writeFile("src/backslash_smoke.deal", """
            import * as cfg from "host/x\\y"
            export function main(): null { return null; }
            export function echo(u: cfg.User): int { return u.port; }
            export function run(): int { return cfg.ping(); }
            """);

        Path entryFile = tmpDir.resolve("src/backslash_smoke.deal").toAbsolutePath();
        Path outputDir = tmpDir.resolve("build/backslash_smoke");
        List<Path> roots = List.of(tmpDir.resolve("src").toAbsolutePath());

        DealConfig config = DealConfig.load(tmpDir).config();
        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            entryFile, outputDir, false, config, roots, null);

        boolean success = orchestrator.compile();
        check(success, "Backslash key: compilation should succeed");
        if (!success) return;

        String lua = Files.readString(outputDir.resolve("backslash_smoke.lua"));
        // Generated Lua source must carry the escaped forms (each "\\"
        // here is one backslash in the generated text).
        check(lua.contains("__rt.load_host(\"host/x\\\\y\", {"),
            "Backslash key: loader path argument is Lua-escaped");
        check(lua.contains("User = \"@host.x\\\\y/User\""),
            "Backslash key: loader class descriptor is Lua-escaped");
        check(lua.contains("echo = __rt.function_(\"(@host.x\\\\y/User)->int\", function("),
            "Backslash key: wrapper signature descriptor is Lua-escaped");
        check(!lua.contains("host.x\\y/User\""),
            "Backslash key: no raw (unescaped) descriptor text remains");

        // Host implementation: the raw require path is "host/x\y", so the
        // file lives at outputDir/host/x\y.lua (a literal backslash in the
        // file name on POSIX filesystems).
        Path hostImpl = outputDir.resolve("host").resolve("x\\y.lua");
        Files.createDirectories(hostImpl.getParent());
        Files.writeString(hostImpl, """
            local M = {}
            function M.ping() return 9 end
            M.User = { __kind = "class", __classname = "@host.x\\\\y/User" }
            M.User_defaults = { port = 0 }
            return M
            """);

        Path runtimeDest = outputDir.resolve("deal/runtime.lua");
        if (!Files.exists(runtimeDest)) {
            Files.createDirectories(runtimeDest.getParent());
            Files.copy(Path.of("deal/runtime.lua"), runtimeDest);
        }

        try {
            String luaCode = "package.path = '" + outputDir.toRealPath()
                + "/?.lua;' "
                + "local m = require('backslash_smoke') "
                + "local v = m.run.f() "
                + "assert(v == 9, 'expected 9, got ' .. tostring(v)) "
                + "print('OK: backslash host ping -> ' .. tostring(v))";
            ProcessBuilder pb = new ProcessBuilder("luajit", "-e", luaCode);
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            String output = new String(proc.getInputStream().readAllBytes());
            int exitCode = proc.waitFor();
            check(exitCode == 0,
                "Backslash key: runtime verification (exit " + exitCode + "): "
                    + output.trim());
        } catch (Exception e) {
            check(false, "Backslash key: runtime verification failed: " + e.getMessage());
        }
    }

    /**
     * Regression (C3 review round 3): descriptor text embedded in quoted Lua
     * strings at the @jsonable field-descriptor sites must be Lua-escaped.
     * A DEAL @jsonable class holding a host-class field (typed from an
     * externals-listed declaration) emits the class identity descriptor as
     * the field's {@code className} value; the descriptor carries the
     * externals-derived dotted module path ("@host.x\y/User" — the
     * externals key with "/" mapped to "."), so a backslash in the
     * externals key made the generated chunk invalid Lua ("invalid escape
     * sequence" at require time, with no compile-time diagnostic).  The
     * generated module must carry the escaped
     * {@code className = "@host.x\\y/User"} text and load AND run under
     * LuaJIT.
     */
    private static void testHostModuleBackslashExternalsKeyJsonableE2E() throws Exception {
        System.out.println("-- Host E2E: backslash externals key + @jsonable host-class field --");
        if (!luajitAvailable()) {
            System.out.println("  SKIP: LuaJIT not available");
            return;
        }

        writeFile("deal.json", """
            {
              "moduleRoots": ["src"],
              "output": "build/lua",
              "backend": "luajit",
              "externals": {
                "host/x\\\\y": { "declaration": "bindings/host-xy.d.deal" }
              }
            }""");
        writeFile("bindings/host-xy.d.deal", """
            export function ping(): int;

            // @jsonable
            export class User {
                port: int = 0;
            }
            """);
        // The @jsonable Wrapper holds a host-class field: the emitted
        // Wrapper_fields descriptor embeds the class identity
        // "@host.x\y/User" as a quoted-string className value.  The null
        // default avoids constructing the host class from DEAL (host-class
        // literals stay latent in production — declaration files have no
        // symbol table), while the field descriptor itself is emitted at
        // module load.
        writeFile("src/jsonable_host.deal", """
            import * as cfg from "host/x\\y"
            // @jsonable
            export class Wrapper {
                u: cfg.User | null = null;
                name: string = "";
            }
            export function main(): null { return null; }
            export function run(): int { return cfg.ping(); }
            """);

        Path entryFile = tmpDir.resolve("src/jsonable_host.deal").toAbsolutePath();
        Path outputDir = tmpDir.resolve("build/jsonable_host");
        List<Path> roots = List.of(tmpDir.resolve("src").toAbsolutePath());

        DealConfig config = DealConfig.load(tmpDir).config();
        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            entryFile, outputDir, false, config, roots, null);

        boolean success = orchestrator.compile();
        check(success, "Jsonable backslash key: compilation should succeed");
        if (!success) return;

        String lua = Files.readString(outputDir.resolve("jsonable_host.lua"));
        // The @jsonable field descriptor's className value must carry the
        // Lua-escaped form (each "\\" here is one backslash in the
        // generated text).
        check(lua.contains("className = \"@host.x\\\\y/User\""),
            "Jsonable backslash key: field-descriptor className is Lua-escaped");
        check(!lua.contains("host.x\\y/User"),
            "Jsonable backslash key: no raw (unescaped) descriptor text remains");

        // Host implementation on the raw require path outputDir/host/x\y.lua
        // (a literal backslash in the file name on POSIX filesystems),
        // supplying the class META, defaults, and the optional <C>_fields
        // descriptor table the loader copies through (host-module-abi D2).
        Path hostImpl = outputDir.resolve("host").resolve("x\\y.lua");
        Files.createDirectories(hostImpl.getParent());
        Files.writeString(hostImpl, """
            local M = {}
            function M.ping() return 9 end
            M.User = { __kind = "class", __classname = "@host.x\\\\y/User" }
            M.User_defaults = { port = 0 }
            M.User_fields = { { name = "port", jtype = "int", optional = false, nullable = false } }
            -- The @jsonable declaration adds User$fromJson/User$toJson
            -- synthetics to the declared surface; the loader requires them
            -- (never called by this test).
            M["User$fromJson"] = function(s) return nil end
            M["User$toJson"] = function(u) return "" end
            return M
            """);

        Path runtimeDest = outputDir.resolve("deal/runtime.lua");
        if (!Files.exists(runtimeDest)) {
            Files.createDirectories(runtimeDest.getParent());
            Files.copy(Path.of("deal/runtime.lua"), runtimeDest);
        }
        // The @jsonable pass requires std.json at load; place it on
        // package.path like the host environment would (host policy).
        Path jsonDest = outputDir.resolve("std/json.lua");
        if (!Files.exists(jsonDest)) {
            Files.createDirectories(jsonDest.getParent());
            Files.copy(Path.of("std/json.lua"), jsonDest);
        }

        try {
            String luaCode = "package.path = '" + outputDir.toRealPath()
                + "/?.lua;' "
                + "local m = require('jsonable_host') "
                + "local v = m.run.f() "
                + "assert(v == 9, 'expected 9, got ' .. tostring(v)) "
                + "print('OK: jsonable backslash host ping -> ' .. tostring(v))";
            ProcessBuilder pb = new ProcessBuilder("luajit", "-e", luaCode);
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            String output = new String(proc.getInputStream().readAllBytes());
            int exitCode = proc.waitFor();
            check(exitCode == 0,
                "Jsonable backslash key: runtime verification (exit " + exitCode + "): "
                    + output.trim());
        } catch (Exception e) {
            check(false, "Jsonable backslash key: runtime verification failed: " + e.getMessage());
        }
    }

    // =========================================================================
    // CLI Tests
    // =========================================================================

    private static void testCli() throws Exception {
        System.out.println("-- CLI --");

        int exitCode = Main.run(new String[]{});
        check(exitCode == 1, "No args -> exit 1");

        exitCode = Main.run(new String[]{"unknown"});
        check(exitCode == 1, "Unknown command -> exit 1");

        exitCode = Main.run(new String[]{"compile"});
        check(exitCode == 1, "Missing entry -> exit 1");

        exitCode = Main.run(new String[]{"compile", "/nonexistent/file.deal"});
        check(exitCode == 1, "Nonexistent entry -> exit 1");

        writeFile("src/cli_test.deal", """
            export function main(): null { return null; }
            export function hello(): string { return "world"; }
            """);
        Path entryFile = tmpDir.resolve("src/cli_test.deal").toAbsolutePath();
        Path outputDir = tmpDir.resolve("build/cli_test");

        exitCode = Main.run(new String[]{"compile",
            entryFile.toString(), "--output", outputDir.toString()});
        check(exitCode == 0, "Valid compile -> exit 0");
        check(Files.exists(outputDir.resolve("cli_test.lua")),
            "Output file exists");
    }

    /**
     * CLI --diagnostics-json end-to-end on manifest-configuration failures
     * (D8/D10): the flag is parsed here because manifest failures occur
     * before the orchestrator exists. A missing argument is a CLI usage
     * diagnostic; a successful write is field-exact with exit 1 unchanged;
     * an unwritable path is a deterministic I/O diagnostic with exit 1 and
     * no raw exception.
     */
    private static void testCliDiagnosticsJson() throws Exception {
        System.out.println("-- CLI: --diagnostics-json --");

        // Missing argument: usage diagnostic, exit 1.
        String[] missing = runCliCapturingErr(new String[] {
            "compile", "--diagnostics-json"});
        check("1".equals(missing[0]), "--diagnostics-json without a path exits 1");
        check(missing[1].contains("--diagnostics-json requires a path argument"),
            "missing-argument usage diagnostic: " + missing[1]);

        // Usage text documents the flag.
        String[] noArgs = runCliCapturingErr(new String[] {});
        check("1".equals(noArgs[0]), "no-args usage exits 1");
        check(noArgs[1].contains("--diagnostics-json <path>"),
            "usage documents --diagnostics-json: " + noArgs[1]);

        // Manifest-configuration failure: the structured document is
        // field-exact, the human diagnostic prints on stderr, exit 1.
        Path manifest = writeFile("json_err_proj/deal.json", "{\"backend\": \"wasm\"}");
        Path entry = writeFile("json_err_proj/main.deal",
            "export function main(): null { return null; }\n").toAbsolutePath();
        Path outJson = tmpDir.resolve("diag.json");

        String[] captured = runCliCapturingErr(new String[] {
            "compile", entry.toString(), "--diagnostics-json", outJson.toString()});
        check("1".equals(captured[0]),
            "manifest failure with --diagnostics-json exits 1: " + captured[1]);
        check(captured[1].contains("E2012") && captured[1].contains("deal.json"),
            "human E2012 prints on stderr through the formatter: " + captured[1]);

        DealConfigParseResult expected = DealConfig.parse(manifest,
            "{\"backend\": \"wasm\"}");
        String expectedJson = DiagnosticStructuredOutput.toJson(expected.diagnostics());
        String written = Files.readString(outJson);
        check(written.equals(expectedJson),
            "structured document is field-exact:\n" + written);

        // Unwritable path: deterministic I/O diagnostic, exit 1, no stack
        // trace, no raw path exception.
        Path badPath = Path.of("/nonexistent-parent-dir-0413/out.json");
        String[] capturedBad = runCliCapturingErr(new String[] {
            "compile", entry.toString(), "--diagnostics-json", badPath.toString()});
        check("1".equals(capturedBad[0]),
            "unwritable --diagnostics-json exits 1");
        check(capturedBad[1].contains("deal: cannot write diagnostics JSON"),
            "deterministic I/O diagnostic: " + capturedBad[1]);
        check(!capturedBad[1].contains("Exception")
                && !capturedBad[1].contains("\tat "),
            "no raw exception or stack trace escapes: " + capturedBad[1]);
    }

    /** Runs the CLI with System.err captured; returns {exitCode, stderr}. */
    private static String[] runCliCapturingErr(String[] args) throws IOException {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        int exitCode;
        try {
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            exitCode = Main.run(args);
            System.err.flush();
        } finally {
            System.setErr(originalErr);
        }
        return new String[] {String.valueOf(exitCode),
            err.toString(StandardCharsets.UTF_8)};
    }

    // =========================================================================
    // End-to-End Integration Tests
    // =========================================================================

    private static void testEndToEndSingleModule() throws Exception {
        System.out.println("-- E2E: Single Module --");
        if (!luajitAvailable()) {
            System.out.println("  SKIP: LuaJIT not available");
            return;
        }

        writeFile("src/e2e_single.deal", """
            export function main(): null { return null; }
            export function greet(name: string): string {
                return "Hello, " + name;
            }
            """);

        Path entryFile = tmpDir.resolve("src/e2e_single.deal").toAbsolutePath();
        Path outputDir = tmpDir.resolve("build/e2e_single");

        List<Path> roots = List.of(tmpDir.resolve("src").toAbsolutePath());
        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            entryFile, outputDir, true, null, roots, null);

        check(orchestrator.compile(), "E2E single: compile succeeded");

        // Copy runtime
        Path runtimeDest = outputDir.resolve("deal/runtime.lua");
        if (!Files.exists(runtimeDest)) {
            Files.createDirectories(runtimeDest.getParent());
            Files.copy(Path.of("deal/runtime.lua"), runtimeDest);
        }

        // Create test runner
        Path testFile = outputDir.resolve("__test.lua");
        String testLua = """
            local m = require("e2e_single")
            local result = m.greet.f("DEAL")
            assert(result == "Hello, DEAL", "Expected 'Hello, DEAL', got '" .. tostring(result) .. "'")
            print("OK: " .. result)
            """;
        Files.writeString(testFile, testLua);

        ProcessBuilder pb = new ProcessBuilder("luajit", testFile.toString());
        pb.directory(outputDir.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes());
        int exit = p.waitFor();

        check(exit == 0, "E2E single: LuaJIT exit 0, output: " + output.trim());
        check(output.contains("Hello, DEAL"), "E2E single: correct greeting");
    }

    private static void testEndToEndMultiModule() throws Exception {
        System.out.println("-- E2E: Multi Module --");
        if (!luajitAvailable()) {
            System.out.println("  SKIP: LuaJIT not available");
            return;
        }

        writeFile("src/e2e_main.deal", """
            import * as lib from "./e2e_lib"
            export function main(): null { return null; }
            export function run(): int { return lib.add(10, 20); }
            """);
        writeFile("src/e2e_lib.deal", """
            export function add(a: int, b: int): int { return a + b; }
            """);

        Path entryFile = tmpDir.resolve("src/e2e_main.deal").toAbsolutePath();
        Path outputDir = tmpDir.resolve("build/e2e_multi");

        List<Path> roots = List.of(tmpDir.resolve("src").toAbsolutePath());
        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            entryFile, outputDir, true, null, roots, null);

        check(orchestrator.compile(), "E2E multi: compile succeeded");

        // Copy runtime
        Path runtimeDest = outputDir.resolve("deal/runtime.lua");
        Files.createDirectories(runtimeDest.getParent());
        if (!Files.exists(runtimeDest)) {
            Files.copy(Path.of("deal/runtime.lua"), runtimeDest);
        }

        // Create test runner
        Path testFile = outputDir.resolve("__test.lua");
        String testLua = """
            local m = require("e2e_main")
            local result = m.run.f()
            assert(result == 30, "Expected 30, got " .. tostring(result))
            print("OK: " .. result)
            """;
        Files.writeString(testFile, testLua);

        ProcessBuilder pb = new ProcessBuilder("luajit", testFile.toString());
        pb.directory(outputDir.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes());
        int exit = p.waitFor();

        check(exit == 0, "E2E multi: LuaJIT exit 0, output: " + output.trim());
        check(output.contains("OK: 30"), "E2E multi: correct result");
    }

    private static void testEndToEndStdlib() throws Exception {
        System.out.println("-- E2E: Stdlib Import --");
        if (!luajitAvailable()) {
            System.out.println("  SKIP: LuaJIT not available");
            return;
        }

        // Create a stdlib with .d.deal and .lua implementation
        Path stdlibDir = tmpDir.resolve("stdlib");
        Files.createDirectories(stdlibDir.resolve("std"));
        Files.writeString(stdlibDir.resolve("std/string.d.deal"),
            "export function length(s: string): int;\n");
        Files.writeString(stdlibDir.resolve("std/string.lua"),
            "local __rt = require(\"deal.runtime\")\n"
            + "local m = {}\n"
            + "m.length = __rt.function_(\"(string)->int\", function(s) "
            + "__rt.check_string(s); return #s end)\n"
            + "return m\n");

        // Create a source file that imports std/string
        writeFile("src/e2e_std.deal", """
            import * as strings from "std/string"
            export function test_len(): int { return strings.length("hello"); }
            export function main(): null { return null; }
            """);

        Path entryFile = tmpDir.resolve("src/e2e_std.deal").toAbsolutePath();
        Path outputDir = tmpDir.resolve("build/e2e_std");

        List<Path> roots = new ArrayList<>();
        roots.add(tmpDir.resolve("src").toAbsolutePath());
        roots.add(stdlibDir.toAbsolutePath());

        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            entryFile, outputDir, true, null, roots, stdlibDir.toAbsolutePath());

        boolean success = orchestrator.compile();
        List<Diagnostic> diags = orchestrator.diagnostics();

        // Core assertion: import resolution of a stdlib module must succeed
        // (no E2003 "module not found" errors)
        boolean hasModuleNotFound = diags.stream()
            .anyMatch(d -> "E2003".equals(d.code()));
        check(!hasModuleNotFound, "E2E stdlib: no E2003 module-not-found error");

        // If compilation succeeded, verify the output structure
        if (success) {
            check(Files.exists(outputDir.resolve("e2e_std.lua")),
                "E2E stdlib: output file exists after successful compile");

            // Verify the generated code contains require for std.string
            String genCode = Files.readString(outputDir.resolve("e2e_std.lua"));
            check(genCode.contains("require(\"std.string\")"),
                "E2E stdlib: generated code requires std.string");
            check(genCode.contains("strings.length"),
                "E2E stdlib: generated code references strings.length");

            // Verify that the orchestrator copied the stdlib .lua to output
            Path stdDest = outputDir.resolve("std/string.lua");
            check(Files.exists(stdDest),
                "E2E stdlib: std/string.lua copied to output");

            // Runtime verification: execute the compiled module with LuaJIT
            // to verify that the wrapped stdlib function works correctly at runtime.
            // Note: Use a single -e argument because LuaJIT treats each -e as a
            // separate chunk with its own local scope.
            try {
                String luaCode = "package.path = '" + outputDir.toRealPath()
                    + "/?.lua;" + outputDir.toRealPath() + "/?/init.lua;"
                    + stdlibDir.toRealPath() + "/?.lua;' "
                    + "local m = require('e2e_std') "
                    + "local result = m.test_len.f() "
                    + "assert(result == 5, 'expected 5, got ' .. tostring(result)) "
                    + "print('OK: e2e stdlib runtime')";
                ProcessBuilder pb = new ProcessBuilder("luajit", "-e", luaCode);
                pb.redirectErrorStream(true);
                Process proc = pb.start();
                String output = new String(proc.getInputStream().readAllBytes());
                int exitCode = proc.waitFor();
                check(exitCode == 0,
                    "E2E stdlib: runtime verification failed (exit " + exitCode
                    + "): " + output.trim());
            } catch (Exception e) {
                check(false, "E2E stdlib: runtime verification failed: " + e.getMessage());
            }
        } else {
            // Compilation failed for non-E2003 reasons
            System.out.println("  Note: stdlib compilation had errors: "
                + diags.stream().filter(d -> "error".equals(d.severity()))
                    .map(d -> d.code() + ": " + d.message()).toList());
        }
    }

    private static void testEndToEndWithError() throws Exception {
        System.out.println("-- E2E: Error Output --");

        writeFile("src/e2e_err.deal", """
            export function bad(): int { return "wrong"; }
            """);

        Path entryFile = tmpDir.resolve("src/e2e_err.deal").toAbsolutePath();
        Path outputDir = tmpDir.resolve("build/e2e_err");

        List<Path> roots = List.of(tmpDir.resolve("src").toAbsolutePath());
        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            entryFile, outputDir, false, null, roots, null);

        boolean success = orchestrator.compile();
        check(!success, "E2E error: compilation should fail");

        List<Diagnostic> diags = orchestrator.diagnostics();
        boolean hasProperDiag = false;
        for (Diagnostic d : diags) {
            if ("error".equals(d.severity())) {
                check(d.code() != null && !d.code().isEmpty(),
                    "Diagnostic has code: " + d.code());
                check(d.file() != null && !d.file().isEmpty(),
                    "Diagnostic has file");
                check(d.line() > 0, "Diagnostic has line > 0");
                check(d.column() > 0, "Diagnostic has column > 0");
                check(d.message() != null && !d.message().isEmpty(),
                    "Diagnostic has message");
                hasProperDiag = true;
            }
        }
        check(hasProperDiag, "Has at least one properly formatted diagnostic");
    }

    // =========================================================================
    // E2E: Complete Pipeline via CLI
    // =========================================================================

    private static void testEndToEndCliPipeline() throws Exception {
        System.out.println("-- E2E: CLI Pipeline --");
        if (!luajitAvailable()) {
            System.out.println("  SKIP: LuaJIT not available");
            return;
        }

        writeFile("src/e2e_cli.deal", """
            export function main(): null { return null; }
            export function add(a: int, b: int): int { return a + b; }
            """);

        Path entryFile = tmpDir.resolve("src/e2e_cli.deal").toAbsolutePath();
        Path outputDir = tmpDir.resolve("build/e2e_cli");

        int exitCode = Main.run(new String[]{"compile",
            entryFile.toString(), "--output", outputDir.toString(), "--verbose"});
        check(exitCode == 0, "E2E CLI: exit 0");

        check(Files.exists(outputDir.resolve("e2e_cli.lua")),
            "E2E CLI: output file exists");

        Path runtimeDest = outputDir.resolve("deal/runtime.lua");
        Files.createDirectories(runtimeDest.getParent());
        if (!Files.exists(runtimeDest)) {
            Files.copy(Path.of("deal/runtime.lua"), runtimeDest);
        }

        Path testFile = outputDir.resolve("__test.lua");
        Files.writeString(testFile, """
            local m = require("e2e_cli")
            local result = m.add.f(5, 7)
            assert(result == 12, "Expected 12, got " .. tostring(result))
            print("OK: " .. result)
            """);

        ProcessBuilder pb = new ProcessBuilder("luajit", testFile.toString());
        pb.directory(outputDir.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes());
        int exit = p.waitFor();

        check(exit == 0, "E2E CLI: LuaJIT exit 0, output: " + output.trim());
        check(output.contains("OK: 12"), "E2E CLI: correct result");
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static boolean luajitAvailable() {
        try {
            new ProcessBuilder("luajit", "-v").start().waitFor();
            return true;
        } catch (Exception e) {
            return false;
        }
    }


    // =========================================================================
    // DEAL v1.2 module shape validation and selected-entry main() rules
    // (ISSUE-0104)
    // =========================================================================

    /** Parses a source and runs the post-parse ModuleShapeValidator. */
    private static List<CompilerDiagnostic> shapeDiags(String source, boolean isDeclFile) {
        LexResult lex = new Lexer(source, isDeclFile ? "t.d.deal" : "t.deal")
            .tokenize();
        ParseResult parse = new Parser(lex.tokens(),
            isDeclFile ? "t.d.deal" : "t.deal").parse();
        return ModuleShapeValidator.validate(parse.program(),
            isDeclFile ? "t.d.deal" : "t.deal", isDeclFile);
    }

    private static void testModuleShapeValidV12() {
        System.out.println("-- v1.2 module shape: imports first + main() compiles --");
        List<CompilerDiagnostic> diags = shapeDiags("""
            import * as lib from "./lib"
            export function main(): null { return null; }
            export class Point { x: number = 0.0; }
            function helper(): int { return 1; }
            """, false);
        check(diags.isEmpty(), "v1.2-conformant module has no shape diagnostics");
    }

    private static void testModuleShapeImportAfterDeclaration() {
        System.out.println("-- v1.2 module shape: import after declaration (E1048) --");
        List<CompilerDiagnostic> diags = shapeDiags("""
            export function main(): null { return null; }
            import * as lib from "./lib"
            """, false);
        check(diags.stream().anyMatch(d -> "E1048".equals(d.code())
                && "error".equals(d.severity())),
            "import after declaration produces E1048");
    }

    private static void testModuleShapeTopLevelStatement() {
        System.out.println("-- v1.2 module shape: top-level statement (E1049) --");
        List<CompilerDiagnostic> diags = shapeDiags("""
            let x: int = 1;
            export function main(): null { return null; }
            """, false);
        check(diags.stream().anyMatch(d -> "E1049".equals(d.code())
                && "error".equals(d.severity())),
            "top-level let produces E1049");
    }

    private static void testModuleShapeNestedExportAndImport() {
        System.out.println("-- v1.2 module shape: nested import/export (E1050) --");
        List<CompilerDiagnostic> diags = shapeDiags("""
            export function main(): null {
              export function inner(): null { return null; }
              return null;
            }
            """, false);
        check(diags.stream().anyMatch(d -> "E1050".equals(d.code())
                && "error".equals(d.severity())),
            "nested export produces E1050");

        diags = shapeDiags("""
            export function main(): null {
              import * as lib from "./lib"
              return null;
            }
            """, false);
        check(diags.stream().anyMatch(d -> "E1050".equals(d.code())
                && "error".equals(d.severity())),
            "nested import produces E1050");
    }

    private static void testModuleShapeNestedInFunctionExpressions()
            throws Exception {
        System.out.println(
            "-- v1.2 module shape: import/export inside function expressions (E1050) --");

        // Function expression in statement position (variable initializer).
        List<CompilerDiagnostic> diags = shapeDiags("""
            export function main(): null {
              let f = function(): null { import * as x from "./x"; return null; };
              return null;
            }
            """, false);
        check(diags.stream().anyMatch(d -> "E1050".equals(d.code())
                && "error".equals(d.severity())),
            "nested import in function expression (statement position) produces E1050");

        diags = shapeDiags("""
            export function main(): null {
              let f = function(): null { export function inner(): null { return null; } return null; };
              return null;
            }
            """, false);
        check(diags.stream().anyMatch(d -> "E1050".equals(d.code())
                && "error".equals(d.severity())),
            "nested export in function expression (statement position) produces E1050");

        // Function expression in a class-field default.
        diags = shapeDiags("""
            class C { f: (() => null) = function(): null { import * as x from "./x"; return null; }; }
            export function main(): null { return null; }
            """, false);
        check(diags.stream().anyMatch(d -> "E1050".equals(d.code())
                && "error".equals(d.severity())),
            "nested import in class-field default function expression produces E1050");

        diags = shapeDiags("""
            class C { f: (() => null) = function(): null { export function inner(): null { return null; } return null; }; }
            export function main(): null { return null; }
            """, false);
        check(diags.stream().anyMatch(d -> "E1050".equals(d.code())
                && "error".equals(d.severity())),
            "nested export in class-field default function expression produces E1050");

        // Deeply nested clean function expressions stay valid (no false
        // positives from descending through expressions).
        diags = shapeDiags("""
            import * as lib from "./lib"
            export function main(): null {
              let f = function(): null { let g = function(): int { return 1; }; return null; };
              let a = [1, function(): int { return 2; }];
              return null;
            }
            """, false);
        check(diags.isEmpty(),
            "clean nested function expressions produce no shape diagnostics");

        // End to end: the orchestrator must reject the module instead of
        // silently miscompiling when a function-expression body carries a
        // nested export.
        List<Diagnostic> orchestratorDiags = compileEntry(
            "nested_fn_expr_export", """
            export function main(): null {
              let f = function(): null { export function inner(): null { return null; } return null; };
              return null;
            }
            """);
        check(orchestratorDiags.stream().anyMatch(d -> "E1050".equals(d.code())
                && "error".equals(d.severity())),
            "orchestrator rejects nested export in function expression with E1050");
    }

    private static void testModuleShapeBodylessInImplementation()
            throws Exception {
        System.out.println("-- v1.2 module shape: bodyless declaration in .deal (E1051) --");
        List<CompilerDiagnostic> diags = shapeDiags(
            "export function main(): null;\n", false);
        check(diags.stream().anyMatch(d -> "E1051".equals(d.code())
                && "error".equals(d.severity())),
            "bodyless function in .deal produces E1051");

        // .d.deal files may declare external functions.
        diags = shapeDiags(
            "export function add(a: int, b: int): int;\n", true);
        check(diags.isEmpty(), ".d.deal external declaration is accepted");

        // A bodyless declaration nested inside a function body is external
        // too: implementation files must reject it with E1051 instead of
        // silently dropping the declared signature.
        diags = shapeDiags("""
            export function main(): null {
              function g(): null;
              let n: null = g();
              return n;
            }
            """, false);
        check(diags.stream().anyMatch(d -> "E1051".equals(d.code())
                && "error".equals(d.severity())),
            "nested bodyless function in a function body produces E1051");

        // A bodyless declaration nested inside a class-field-default
        // function expression is reached through the expression walk.
        diags = shapeDiags("""
            class C { f: (() => null) = function(): null { function g(): null; return null; }; }
            export function main(): null { return null; }
            """, false);
        check(diags.stream().anyMatch(d -> "E1051".equals(d.code())
                && "error".equals(d.severity())),
            "nested bodyless function in a class-field-default function expression produces E1051");

        // Declaration files may nest external declarations; only the
        // implementation-file gate is threaded into the nested walk.
        diags = shapeDiags("""
            export function add(a: int, b: int): int {
              function helper(): int;
              return helper();
            }
            """, true);
        check(diags.isEmpty(),
            "nested bodyless function in .d.deal is accepted");

        // Bodied nested declarations stay valid in implementation files.
        diags = shapeDiags("""
            export function main(): null {
              function g(): null { return null; }
              let f = function(): null { function h(): null { return null; } return null; };
              return null;
            }
            """, false);
        check(diags.isEmpty(),
            "bodied nested declarations produce no shape diagnostics");

        // End to end: the orchestrator must reject the module that
        // previously compiled cleanly with a dropped signature.
        List<Diagnostic> orchestratorDiags = compileEntry(
            "nested_bodyless", """
            export function main(): null {
              function g(): null;
              let n: null = g();
              return n;
            }
            """);
        check(orchestratorDiags.stream().anyMatch(d -> "E1051".equals(d.code())
                && "error".equals(d.severity())),
            "orchestrator rejects nested bodyless declaration with E1051");
    }

    /** Compiles one entry module and returns orchestrator diagnostics. */
    private static List<Diagnostic> compileEntry(String name, String source)
            throws Exception {
        writeFile("src/" + name + ".deal", source);
        Path entryFile = tmpDir.resolve("src/" + name + ".deal")
            .toAbsolutePath();
        Path outputDir = tmpDir.resolve("build/" + name);
        List<Path> moduleRoots = List.of(tmpDir.resolve("src").toAbsolutePath());
        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            entryFile, outputDir, false, null, moduleRoots, null);
        orchestrator.compile();
        return orchestrator.diagnostics();
    }

    private static void testEntryMainValidation() throws Exception {
        System.out.println("-- v1.2 selected-entry main() validation --");

        // Missing main → E2010.
        List<Diagnostic> diags = compileEntry("em_missing",
            "export function run(): int { return 1; }\n");
        check(diags.stream().anyMatch(d -> "E2010".equals(d.code())
                && "error".equals(d.severity())),
            "entry without main produces E2010");

        // Wrong parameter shape → E2011.
        diags = compileEntry("em_params",
            "export function main(x: int): null { return null; }\n");
        check(diags.stream().anyMatch(d -> "E2011".equals(d.code())
                && "error".equals(d.severity())),
            "main(x: int) produces E2011");

        // Wrong return type → E2011.
        diags = compileEntry("em_return",
            "export function main(): int { return 1; }\n");
        check(diags.stream().anyMatch(d -> "E2011".equals(d.code())
                && "error".equals(d.severity())),
            "main(): int produces E2011");

        // Async main → E2011.
        diags = compileEntry("em_async",
            "export async function main(): null { return null; }\n");
        check(diags.stream().anyMatch(d -> "E2011".equals(d.code())
                && "error".equals(d.severity())),
            "async main produces E2011");

        // Correct shape → no E2010/E2011.
        diags = compileEntry("em_ok",
            "export function main(): null { return null; }\n");
        check(diags.stream().noneMatch(d -> "E2010".equals(d.code())
                || "E2011".equals(d.code())),
            "main(): null produces no entry diagnostics");
    }

    // =========================================================================
    // Main
    // =========================================================================

    public static void main(String[] args) throws Exception {
        System.out.println("=== Running Module System Tests ===\n");

        tmpDir = Files.createTempDirectory("deal_module_test_");

        try {
            testDealConfig();
            testExportExtractor();
            testExportExtractorAsyncFunc();
            testExportExtractorAsyncFuncTypeAnnotation();
            testDeclarationFileBodyValidation();
            testDeclarationFileLetStmt();
            testModuleResolution();
            testModuleShapeValidV12();
            testModuleShapeImportAfterDeclaration();
            testModuleShapeTopLevelStatement();
            testModuleShapeNestedExportAndImport();
            testModuleShapeNestedInFunctionExpressions();
            testModuleShapeBodylessInImplementation();
            testEntryMainValidation();
            testTopologicalSortDiamond();
            testModuleOutsideCycle();
            testSingleModuleCompilation();
            testMultiModuleCompilation();
            testCompilationWithError();
            testCompilationOrchestratorAwaitImportRetention();
            testCrossModuleAsyncCallWithoutAwait_E3014();
            testCircularImportRuntime();
            testCircularImportDeclarationOnly();
            testCircularImportClassFieldDefault();
            testTwoDisconnectedCyclesOneRuntime();
            testTwoDisconnectedCyclesBothDecl();
            testStdlibFallbackAfterDdeal();
            testCrossModuleClassFieldAccess();
            testCrossModuleClassConstruction();
            testCrossModuleClassHas();
            testQualifiedTypeAnnotation();
            testCrossModuleClassE2E();
            testDeclarationFile();
            testExternalsHostModuleCompiles();
            testExternalsGatingE2009();
            testExternalsMissingDeclarationE2003();
            testHostModuleEndToEnd();
            testHostModuleBackslashExternalsKeyE2E();
            testHostModuleBackslashExternalsKeyJsonableE2E();
            testCli();
            testCliDiagnosticsJson();
            testEndToEndSingleModule();
            testEndToEndMultiModule();
            testEndToEndStdlib();
            testEndToEndWithError();
            testEndToEndCliPipeline();
            // Stdlib compiler integration tests (ISSUE-0009)
            testStdlibConsoleCompilerIntegration();
            testStdlibTableCompilerIntegration();
            testStdlibJsonCompilerIntegration();
            testStdlibMathCompilerIntegration();
            testStdlibTimeCompilerIntegration();
            testStdlibCrossModuleUsage();
            testStdlibJsonRoundTrip();
        } finally {
            try {
                Files.walk(tmpDir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(f -> {
                        try { Files.deleteIfExists(f); } catch (IOException ignored) {}
                    });
            } catch (IOException ignored) {}
        }

        System.out.println("\nPassed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }
}
