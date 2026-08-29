package deal.test;

import deal.ast.*;
import deal.checker.*;
import deal.diagnostics.CompilerDiagnostic;
import deal.diagnostics.DiagnosticCode;
import deal.lexer.*;
import deal.parser.*;

import java.util.*;

/**
 * Verifies that every diagnostic code is registered in {@link DiagnosticCode}
 * with the correct phase classification, and that no unregistered diagnostic
 * codes are emitted during compilation.
 *
 * <p>Tests:
 * <ol>
 *   <li>All {@link DiagnosticCode} values have non-null phase and message.</li>
 *   <li>Phase classification follows the spec's code-range rules.</li>
 *   <li>Critical codes have correct phases.</li>
 *   <li>No codes outside E1xxx-E8xxx exist.</li>
 *   <li>No unregistered codes are emitted from error-trigger programs.</li>
 *   <li>Every DiagnosticCode value is documented with its coverage test.</li>
 * </ol>
 */
public class DiagnosticClassificationTest {

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
    // Phase verification
    // =========================================================================

    static void testAllCodesHavePhase() {
        System.out.println("-- All DiagnosticCode values have non-null phase --");
        for (DiagnosticCode dc : DiagnosticCode.values()) {
            check(dc.phase() != null, dc.code() + " has null phase");
            check(dc.messageTemplate() != null && !dc.messageTemplate().isEmpty(),
                dc.code() + " has empty message template");
        }
    }

    static void testPhaseClassificationByRange() {
        System.out.println("-- Phase classification by code range --");
        for (DiagnosticCode dc : DiagnosticCode.values()) {
            String code = dc.code();
            DiagnosticCode.Phase phase = dc.phase();

            if (code.startsWith("E1") || code.startsWith("E2")
                    || code.startsWith("E3") || code.startsWith("E4")
                    || code.startsWith("E5") || code.startsWith("E7")) {
                check(phase == DiagnosticCode.Phase.FRONTEND,
                    code + " should be FRONTEND but is " + phase);
            } else if (code.startsWith("E6")) {
                check(phase == DiagnosticCode.Phase.BACKEND_LOWERING,
                    code + " should be BACKEND_LOWERING but is " + phase);
            } else if (code.startsWith("E8")) {
                check(phase == DiagnosticCode.Phase.RUNTIME,
                    code + " should be RUNTIME but is " + phase);
            }
        }
    }

    static void testCriticalCodes() {
        System.out.println("-- Critical code phase verification --");
        check(DiagnosticCode.E1001.phase() == DiagnosticCode.Phase.FRONTEND,
            "E1001 -> FRONTEND");
        check(DiagnosticCode.E2001.phase() == DiagnosticCode.Phase.FRONTEND,
            "E2001 -> FRONTEND");
        check(DiagnosticCode.E3001.phase() == DiagnosticCode.Phase.FRONTEND,
            "E3001 -> FRONTEND");
        check(DiagnosticCode.E6000.phase() == DiagnosticCode.Phase.BACKEND_LOWERING,
            "E6000 -> BACKEND_LOWERING");
        check(DiagnosticCode.E8001.phase() == DiagnosticCode.Phase.RUNTIME,
            "E8001 -> RUNTIME");
    }

    static void testNoCodeOutsideRange() {
        System.out.println("-- No code outside E1xxx-E8xxx --");
        for (DiagnosticCode dc : DiagnosticCode.values()) {
            String code = dc.code();
            boolean valid = code.matches("E[1-8]\\d{3}");
            check(valid, code + " should match E[1-8]xxx pattern");
        }
    }

    // =========================================================================
    // Trigger-based verification
    // =========================================================================

    private static boolean hasErrors(List<CompilerDiagnostic> diags) {
        return diags.stream().anyMatch(d -> "error".equals(d.severity()));
    }

    private static List<CompilerDiagnostic> compileAndGetDiagnostics(String source,
                                                              String filename) {
        LexResult lex = new Lexer(source, filename).tokenize();
        ParseResult parse = new Parser(lex.tokens(), filename).parse();

        List<CompilerDiagnostic> allDiags = new ArrayList<>();
        allDiags.addAll(lex.diagnostics());
        allDiags.addAll(parse.diagnostics());

        if (parse.hasErrors()) {
            StubModuleResolver resolver = new StubModuleResolver();
            NameResolver nr = new NameResolver(filename, resolver);
            nr.resolve(parse.program());
            allDiags.addAll(nr.diagnostics());
        } else {
            StubModuleResolver resolver = new StubModuleResolver();
            NameResolver nr = new NameResolver(filename, resolver);
            SymbolTable symTable = nr.resolve(parse.program());
            allDiags.addAll(nr.diagnostics());

            if (!hasErrors(allDiags)) {
                CheckResult result = TypeChecker.check(filename, symTable,
                    nr, parse.program());
                allDiags.addAll(result.diagnostics());
            }
        }
        return allDiags;
    }

    static void testTriggeredCodesAreRegistered() {
        System.out.println("-- Triggered diagnostic codes are registered --");

        Map<String, String> triggers = new LinkedHashMap<>();
        triggers.put("E1001", "@");
        triggers.put("E1003", "\"unterminated");
        triggers.put("E1004", "/* unterminated block comment");
        // v1.2 module top level allows only declarations: executable
        // fragments live inside main() (the spec-v1.2 application-fixture
        // shape), so the trigger still exercises the pinned code.
        String inMain = "export function main(): null { %s return null; }";
        triggers.put("E2000", "function f(): null { break; }");
        triggers.put("E2001", inMain.replace("%s", "let x = y; "));
        triggers.put("E2002", inMain.replace("%s", "let x = 1; let x = 2; "));
        triggers.put("E3001", inMain.replace("%s", "let x: int = 3.14; "));
        triggers.put("E3002", inMain.replace("%s", "let x = []; "));
        triggers.put("E3018", inMain.replace("%s",
            "let t: table = {}; t[0] = 1; "));
        triggers.put("E3008", inMain.replace("%s", "let x = 1; x(); "));
        triggers.put("E3009", inMain.replace("%s",
            "function f(x: int): null { return null; } f(1, 2); "));
        triggers.put("E4001",
            "class Foo { name: string; } "
            + inMain.replace("%s", "let f: Foo = {}; "));
        triggers.put("E4002",
            "class Foo { name: string; } "
            + inMain.replace("%s", "let f: Foo = { name: \"x\", extra: 1 }; "));
        triggers.put("E4007",
            "// @jsonable\nexport class Foo { fn: (x: int) => null = function(x: int): null { return null; }; }");
        triggers.put("E5001", "function f(x: int): null {} f(true);");
        triggers.put("E1047", "function f(...xs: int[]): null { return null; }");
        triggers.put("E1052",
            "class A { x: int = 0; }\n// @deal-version 1.2\n");
        triggers.put("E1053",
            "// @deal-version 1.2\n// @deal-version 1.2\nclass A { x: int = 0; }\n");
        triggers.put("E1054",
            "// @deal-version\nclass A { x: int = 0; }\n");
        triggers.put("E1055",
            "// @deal-version 1.1\nclass A { x: int = 0; }\n");
        triggers.put("E5003",
            "function f(): int { return \"hi\"; }");

        int triggered = 0;
        for (var entry : triggers.entrySet()) {
            String expectedCode = entry.getKey();
            String source = entry.getValue();
            List<CompilerDiagnostic> diags = compileAndGetDiagnostics(source,
                "trigger_" + expectedCode + ".deal");

            boolean found = diags.stream()
                .anyMatch(d -> d.code().equals(expectedCode));

            if (found) {
                DiagnosticCode dc = DiagnosticCode.fromCode(expectedCode);
                check(dc != null,
                    expectedCode + " triggered but not in DiagnosticCode enum");
                if (dc != null) {
                    DiagnosticCode.Phase expPhase = expectedPhaseForCode(expectedCode);
                    check(dc.phase() == expPhase,
                        expectedCode + " phase is " + dc.phase()
                        + " but expected " + expPhase);
                }
                triggered++;
            } else {
                fail(expectedCode + " should have been triggered but was not. "
                    + "Diags: " + diags);
            }
        }
        System.out.println("  Triggered: " + triggered + "/" + triggers.size());
    }

    static void testNoUnregisteredCodesEmitted() {
        System.out.println("-- No unregistered diagnostic codes emitted --");

        String[] programs = {
            "@", "\"unterminated", "/* unterminated", "1.2.3",
            "{", "class {", "function f( {}", "let x = ;",
            "if true { } else x;", "import x from \"m\";",
            "export let x = 1;", "let x: int = true;", "let x = [];",
            "function f(): int { return \"x\"; }", "let x = y;", "break;",
        };

        for (String source : programs) {
            List<CompilerDiagnostic> diags = compileAndGetDiagnostics(source,
                "unreg_test.deal");
            for (CompilerDiagnostic d : diags) {
                String code = d.code();
                if (code == null) {
                    fail("Diagnostic with null code: " + d.message());
                    continue;
                }
                DiagnosticCode dc = DiagnosticCode.fromCode(code);
                if (dc == null) {
                    fail("Unregistered diagnostic code emitted: " + code
                        + " (" + d.message() + ")");
                }
            }
        }
        System.out.println("  Verified: all emitted codes are registered");
    }

    // =========================================================================
    // Coverage documentation
    // =========================================================================

    static void documentCoverage() {
        System.out.println("-- Diagnostic code coverage --");

        Map<String, String> coverage = new LinkedHashMap<>();

        for (int i = 1001; i <= 1004; i++) coverage.put("E" + i, "LexerTest");
        for (int i = 1005; i <= 1043; i++) coverage.put("E" + i, "ParserTest");
        coverage.put("E1044",
            "LexerTest / DirectiveTest (unrecognized directive)");
        coverage.put("E1045",
            "ParserTest / DirectiveTest (directive argument)");
        coverage.put("E1046",
            "ParserTest / DirectiveTest (directive placement/version)");
        coverage.put("E1047", "ParserTest (rest parameters removed in v1.2)");
        coverage.put("E1048", "ModuleSystemTest (import after declaration)");
        coverage.put("E1049", "ModuleSystemTest (top-level statement)");
        coverage.put("E1050", "ModuleSystemTest (nested import/export)");
        coverage.put("E1051", "ModuleSystemTest (bodyless declaration in .deal)");
        coverage.put("E1052", "LexerTest (@deal-version placement)");
        coverage.put("E1053", "LexerTest (@deal-version duplicate)");
        coverage.put("E1054", "LexerTest (@deal-version argument)");
        coverage.put("E1055", "ParserTest (@deal-version value)");

        coverage.put("E2000", "CheckerTest (break/continue)");
        coverage.put("E2001", "CheckerTest (undeclared identifier)");
        coverage.put("E2002", "CheckerTest (redeclaration)");
        coverage.put("E2003", "ModuleSystemTest (module not found)");
        coverage.put("E2004", "CheckerTest (export not found)");
        coverage.put("E2005", "ModuleSystemTest (circular import)");
        coverage.put("E2006", "CheckerTest (import after declaration)");
        coverage.put("E2007", "CheckerTest (declaration after import)");
        coverage.put("E2008", "CheckerTest (dollar in identifier)");
        coverage.put("E2009", "ModuleSystemTest (externals gating)");
        coverage.put("E2010", "ModuleSystemTest (entry module missing main)");
        coverage.put("E2011", "ModuleSystemTest (entry main wrong signature)");
        coverage.put("E2012", "ModuleSystemTest (manifest configuration errors)");

        coverage.put("E3001", "CheckerTest (type mismatch)");
        coverage.put("E3002", "CheckerTest (empty literal inference)");
        coverage.put("E3003", "CheckerTest (table read context)");
        coverage.put("E3004", "CheckerTest (unknown type)");
        coverage.put("E3005", "CheckerTest (invalid nullable)");
        coverage.put("E3006", "CheckerTest (incomparable types)");
        coverage.put("E3007", "CheckerTest (invalid operand)");
        coverage.put("E3008", "CheckerTest (not callable)");
        coverage.put("E3009", "CheckerTest (arg count mismatch)");
        coverage.put("E3010", "CheckerTest (string+int)");
        coverage.put("E3011", "CheckerTest (array element mismatch)");
        coverage.put("E3012", "CheckerTest (await outside async)");
        coverage.put("E3013", "CheckerTest (await on non-async call)");
        coverage.put("E3014", "CheckerTest (async call without await)");
        coverage.put("E3015", "CheckerTest (for-of iterable type)");
        coverage.put("E3016", "CheckerTest (template literal interpolation)");
        coverage.put("E3017", "CheckerTest (array length read-only)");
        coverage.put("E3018",
            "CheckerTest (table index write/delete key static string gate)");
        coverage.put("E3019",
            "CheckerTest (bytes comparison gate, synthetic bytes-typed admission path) / "
            + "ComparisonSelectorLoweringTest (E6005 COMPARISON_SELECTOR producer guard)");

        coverage.put("E4001", "CheckerTest (missing required field)");
        coverage.put("E4002", "CheckerTest (extra field)");
        coverage.put("E4003", "CheckerTest (field type mismatch)");
        coverage.put("E4004", "CheckerTest (delete required field)");
        coverage.put("E4005", "CheckerTest (has() on required field)");
        coverage.put("E4006", "CheckerTest (class Error prohibition)");
        coverage.put("E4007", "CheckerTest (jsonable field validation)");
        coverage.put("E4008", "CheckerTest (jsonable cycle detection)");

        coverage.put("E5001", "CheckerTest (arg type mismatch)");
        coverage.put("E5002", "CheckerTest (missing return)");
        coverage.put("E5003", "CheckerTest (return type mismatch)");
        coverage.put("E5004", "CheckerTest (reverse arity)");

        coverage.put("E6000",
            "LuaBackendTest (unsupported stmt) / JvmBackendTest (out-of-scope construct)");
        coverage.put("E6001", "LuaBackendTest (continue outside loop)");
        coverage.put("E6002", "Historical (ISSUE-0011, no longer emitted)");
        coverage.put("E6003", "LuaBackendTest (rest parameter rejection)");
        coverage.put("E6004", "LuaBackendTest / JvmBackendTest (entry main validation)");
        coverage.put("E6005",
            "FailureContractRegistryTest (registry-owned detail/message construction; "
            + "ISSUE-0230 foundation, ISSUE-0285) — LoweringFoundationTest pins the "
            + "registration");

        coverage.put("E7001", "ModuleSystemTest (decl file exec stmt)");
        coverage.put("E7002",
            "DirectiveTest (directive-level C marker validation; "
            + "semantic policy: ISSUE-0162)");

        coverage.put("E8001", "test_runtime.lua / LuaBackendIntegrationTest");
        coverage.put("E8002", "LuaBackendIntegrationTest (array OOB)");
        coverage.put("E8003", "test_runtime.lua (array element mismatch)");
        coverage.put("E8004", "LuaBackendIntegrationTest (int out of range)");
        coverage.put("E8005", "test_runtime.lua (division by zero)");
        coverage.put("E8006", "test_runtime.lua (negative exponent)");
        coverage.put("E8007", "test_runtime.lua (extra class field)");
        coverage.put("E8010", "test_runtime.lua (function sig mismatch) / "
            + "BackendConformanceTest jvm-host-abi-slice "
            + "(host return boundary) / JvmBackendTest (host ABI)");
        coverage.put("E8011", "test_runtime.lua (host module load validation) / "
            + "BackendConformanceTest jvm-host-abi-slice "
            + "(missing host export) / JvmBackendTest (host ABI)");
        coverage.put("E8012", "test_runtime.lua (bytes_new negative length; "
            + "bytes_get/bytes_set index bounds) / JsBackendTest (bytes E8012 "
            + "runtime pins)");
        coverage.put("E8013", "test_runtime.lua (bytes_set value outside 0..255) / "
            + "JsBackendTest (bytes E8013 runtime pins)");

        int total = DiagnosticCode.values().length;
        int covered = 0;
        for (DiagnosticCode dc : DiagnosticCode.values()) {
            String where = coverage.get(dc.code());
            if (where != null) {
                covered++;
            } else {
                System.out.println("  " + dc.code()
                    + ": NOT COVERED - missing from coverage map!");
                fail(dc.code() + " not in coverage map");
            }
        }

        System.out.println("  Coverage: " + covered + "/" + total
            + " codes documented");
        check(covered == total, "All " + total + " codes should be documented");
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static DiagnosticCode.Phase expectedPhaseForCode(String code) {
        if (code.startsWith("E1") || code.startsWith("E2")
                || code.startsWith("E3") || code.startsWith("E4")
                || code.startsWith("E5") || code.startsWith("E7")) {
            return DiagnosticCode.Phase.FRONTEND;
        } else if (code.startsWith("E6")) {
            return DiagnosticCode.Phase.BACKEND_LOWERING;
        } else if (code.startsWith("E8")) {
            return DiagnosticCode.Phase.RUNTIME;
        }
        return null;
    }

    // =========================================================================
    // Main
    // =========================================================================

    public static void main(String[] args) {
        System.out.println("=== Diagnostic Classification Test ===\n");

        testAllCodesHavePhase();
        testPhaseClassificationByRange();
        testCriticalCodes();
        testNoCodeOutsideRange();
        testTriggeredCodesAreRegistered();
        testNoUnregisteredCodesEmitted();
        documentCoverage();

        System.out.println("\nPassed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }
}
