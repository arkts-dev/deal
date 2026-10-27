package deal.test;

import deal.diagnostics.CompilerDiagnostic;
import deal.diagnostics.DiagnosticCode;
import deal.diagnostics.DiagnosticRange;
import deal.diagnostics.RangeOrigin;
import deal.semantic.ir.ActualKind;
import deal.semantic.ir.FailureContractRegistry;
import deal.semantic.ir.FailurePolicyId;
import deal.semantic.ir.FailurePolicyRow;
import deal.semantic.ir.LoweringFailureDetail;
import deal.semantic.ir.SemanticCapability;
import deal.semantic.ir.SemanticProfile;

import java.util.LinkedHashMap;
import java.util.Map;

public class FailureContractRegistryTest {

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

    private static void checkEq(Object expected, Object actual, String message) {
        check(java.util.Objects.equals(expected, actual),
            message + " (expected " + expected + ", got " + actual + ")");
    }

    private static void expectThrows(Class<? extends Throwable> type, Runnable action,
                                     String message) {
        try {
            action.run();
            fail(message + " (no exception thrown)");
        } catch (Throwable t) {
            check(type.isInstance(t), message + " (threw " + t.getClass().getSimpleName()
                + ": " + t.getMessage() + ")");
        }
    }

    // =========================================================================
    // 3. E6005 construction + E6000 stays distinct
    // =========================================================================

    static void testE6005Construction() {
        System.out.println("-- E6005 construction --");

        LoweringFailureDetail detail = new LoweringFailureDetail(
            "app.main", SemanticCapability.FOUNDATION_VALUES,
            "INDEX_INTERNAL_ERROR_SENTINEL", SemanticProfile.DEAL_V1_5_BINARY64,
            "deal.semantic-ir/2", "CheckedProjectBuilder");

        String pinnedMessage = "Common semantic lowering failed: module 'app.main'"
            + ", capability FOUNDATION_VALUES"
            + ", validatorRule INDEX_INTERNAL_ERROR_SENTINEL"
            + ", semanticProfile DEAL_V1_5_BINARY64"
            + ", irVersion deal.semantic-ir/2"
            + ", origin CheckedProjectBuilder";
        check(pinnedMessage.equals(FailureContractRegistry.instantiateMessage(detail)),
            "instantiated message is exactly \"" + pinnedMessage + "\"");

        CompilerDiagnostic diagnostic = FailureContractRegistry.e6005(detail);
        check("E6005".equals(diagnostic.code()), "diagnostic code is E6005");
        check(diagnostic.diagnosticCode() == DiagnosticCode.E6005,
            "diagnosticCode is the registered E6005");
        check(DiagnosticCode.E6005.phase() == DiagnosticCode.Phase.BACKEND_LOWERING,
            "E6005 phase is BACKEND_LOWERING");
        check("error".equals(diagnostic.severity()), "E6005 severity is error");
        check(pinnedMessage.equals(diagnostic.message()),
            "diagnostic message is the registry-instantiated message carrying the detail");
        check(diagnostic.message().contains("app.main")
                && diagnostic.message().contains("FOUNDATION_VALUES")
                && diagnostic.message().contains("INDEX_INTERNAL_ERROR_SENTINEL")
                && diagnostic.message().contains("DEAL_V1_5_BINARY64")
                && diagnostic.message().contains("deal.semantic-ir/2")
                && diagnostic.message().contains("CheckedProjectBuilder"),
            "message carries all six detail fields");
        check("Common semantic lowering failed".equals(
                DiagnosticCode.E6005.messageTemplate())
                && diagnostic.message().startsWith("Common semantic lowering failed"),
            "message instantiates the E6005 template prefix");
        check(diagnostic.range() != null
                && diagnostic.range().origin() == RangeOrigin.SYNTHETIC
                && diagnostic.range().isCanonicalSynthetic()
                && "".equals(diagnostic.range().file()),
            "E6005 uses the canonical synthetic range (the detail names a producer, "
                + "not a source span)");
        check(diagnostic.notes().isEmpty(), "no notes attached");

        // The range-aware E6005 factory (ISSUE-0750): a producer that
        // identifies the offending file/span supplies it, and the
        // diagnostic keeps the same code, severity, and registry-owned
        // message — only the range changes.
        DiagnosticRange offending = new DiagnosticRange("src/main.deal",
            3, 26, 3, 27, 74, 75, 1, RangeOrigin.SOURCE);
        CompilerDiagnostic ranged = FailureContractRegistry.e6005(detail, offending);
        check(pinnedMessage.equals(ranged.message()),
            "the ranged E6005 keeps the registry-instantiated message");
        check("E6005".equals(ranged.code()) && ranged.diagnosticCode() == DiagnosticCode.E6005
                && "error".equals(ranged.severity()),
            "the ranged E6005 keeps code, diagnosticCode, and severity");
        check(offending.equals(ranged.range()),
            "the ranged E6005 carries the producer-identified offending range; got "
                + ranged.range());

        // The optional-range contract: an absent (null) range selects the
        // exact legacy synthetic range of the one-argument surface, with no
        // added normalization note — a producer using the optional-range
        // interface never crashes and never acquires a fabricated anchor.
        CompilerDiagnostic absentRange = FailureContractRegistry.e6005(detail, null);
        checkEq(diagnostic.code(), absentRange.code(),
            "the null-range E6005 keeps the code");
        checkEq(diagnostic.diagnosticCode(), absentRange.diagnosticCode(),
            "the null-range E6005 keeps the diagnosticCode");
        checkEq(diagnostic.severity(), absentRange.severity(),
            "the null-range E6005 keeps the severity");
        checkEq(diagnostic.message(), absentRange.message(),
            "the null-range E6005 keeps the registry-instantiated message");
        checkEq(diagnostic.range(), absentRange.range(),
            "the null-range E6005 keeps the exact legacy synthetic range");
        checkEq(diagnostic.notes(), absentRange.notes(),
            "the null-range E6005 adds no normalization note");
        check(absentRange.range().isCanonicalSynthetic()
                && "".equals(absentRange.range().file()),
            "the null-range E6005 is the empty-file canonical synthetic range; got "
                + absentRange.range());

        // Fail closed: null detail or a null detail field never renders.
        expectThrows(NullPointerException.class,
            () -> FailureContractRegistry.e6005(null), "e6005(null) fails closed");
        expectThrows(NullPointerException.class,
            () -> FailureContractRegistry.instantiateMessage(null),
            "instantiateMessage(null) fails closed");
        expectThrows(NullPointerException.class,
            () -> FailureContractRegistry.e6005(new LoweringFailureDetail(
                null, SemanticCapability.FOUNDATION_VALUES, "R-ENUM",
                SemanticProfile.DEAL_V1_5_BINARY64, "deal.semantic-ir/2", "SemanticIrValidator")),
            "a null module field fails closed");

        // E6000 remains distinct: intentional retained-target rejections
        // produce E6000, never E6005, and carry no E6005 payload.
        CompilerDiagnostic retainedRejection = CompilerDiagnostic.error(
            DiagnosticCode.E6000, "Unsupported statement type", DiagnosticRange.synthetic(""));
        check("E6000".equals(retainedRejection.code()), "E6000 construction stays E6000");
        check(retainedRejection.diagnosticCode() == DiagnosticCode.E6000,
            "E6000 diagnosticCode is the registered E6000");
        check(DiagnosticCode.E6000 != DiagnosticCode.E6005,
            "E6000 and E6005 are distinct codes");
        check(!retainedRejection.message().contains("Common semantic lowering failed")
                && !retainedRejection.message().contains("validatorRule")
                && !retainedRejection.message().contains("semanticProfile"),
            "E6000 carries no E6005 payload in its message");
    }

    // =========================================================================
    // 4. Canonical actual-kind tokens
    // =========================================================================

    static void testCanonicalActualKinds() {
        System.out.println("-- Canonical actual-kind tokens --");

        Map<ActualKind, String> tokens = new LinkedHashMap<>();
        tokens.put(ActualKind.NULL, "null");
        tokens.put(ActualKind.MISSING, "missing");
        tokens.put(ActualKind.BOOLEAN, "boolean");
        tokens.put(ActualKind.NUMBER, "int");
        tokens.put(ActualKind.NUMBER, "number");
        tokens.put(ActualKind.STRING, "string");
        tokens.put(ActualKind.TABLE, "table");
        tokens.put(ActualKind.ARRAY, "array");
        tokens.put(ActualKind.FUNCTION, "function");
        tokens.put(ActualKind.ASYNC_OPERATION, "async-operation");
        tokens.put(ActualKind.NOTHING, "nothing");
        tokens.put(ActualKind.INVALID_UNICODE, "invalid-unicode");
        for (var entry : tokens.entrySet()) {
            check(entry.getValue().equals(ActualKind.canonicalToken(entry.getKey(), null)),
                "canonicalToken(" + entry.getKey() + ", null) renders the fixed token");
        }
        check("class:a.b.C".equals(ActualKind.canonicalToken(ActualKind.CLASS, "a.b.C")),
            "CLASS renders class:<ClassId>");

        // Target class names never appear: fail closed for every illegal
        // combination.
        expectThrows(NullPointerException.class,
            () -> ActualKind.canonicalToken(null, null), "null kind fails closed");
        expectThrows(NullPointerException.class,
            () -> ActualKind.canonicalToken(ActualKind.CLASS, null),
            "CLASS without a class id fails closed");
        expectThrows(IllegalArgumentException.class,
            () -> ActualKind.canonicalToken(ActualKind.CLASS, ""),
            "CLASS with an empty class id fails closed");
        expectThrows(IllegalArgumentException.class,
            () -> ActualKind.canonicalToken(ActualKind.TABLE, "some.Class"),
            "a non-class actual kind with a class name fails closed (target class names "
                + "never appear)");
    }

    // =========================================================================
    // 5. Immutability
    // =========================================================================

    static void testImmutability() {
        System.out.println("-- Immutability --");

        Map<FailurePolicyId, FailurePolicyRow> rows = FailureContractRegistry.rows();
        expectThrows(UnsupportedOperationException.class,
            () -> rows.put(FailurePolicyId.NO_DEAL_FAILURE,
                FailureContractRegistry.row(FailurePolicyId.TYPE_DESCRIPTOR)),
            "the row table is unmodifiable");

        FailurePolicyRow row = FailureContractRegistry.row(FailurePolicyId.HOST_LOAD);
        expectThrows(UnsupportedOperationException.class,
            () -> row.templates().add("x"), "row templates are unmodifiable");
        expectThrows(UnsupportedOperationException.class,
            () -> row.metadataKeys().add("x"), "row metadata keys are unmodifiable");

        Map<String, String> coverage = FailureContractRegistry.e6005CoverageItems();
        expectThrows(UnsupportedOperationException.class,
            () -> coverage.put("x", "y"), "the coverage-item map is unmodifiable");
    }

    // =========================================================================
    // Main
    // =========================================================================

    public static void main(String[] args) {
        System.out.println("=== Failure Contract Registry Test (ISSUE-0285) ===\n");

        expectThrows(NullPointerException.class,
            () -> FailureContractRegistry.row(null), "null policy fails closed");
        testE6005Construction();
        testCanonicalActualKinds();
        testImmutability();

        System.out.println("\nPassed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }
}
