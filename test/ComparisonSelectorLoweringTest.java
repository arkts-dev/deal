package deal.test;

import deal.ast.BinaryOp;
import deal.diagnostics.CompilerDiagnostic;
import deal.diagnostics.DiagnosticCode;
import deal.semantic.ComparisonSelectorLowering;
import deal.semantic.DescriptorService;
import deal.semantic.ir.AnchorId;
import deal.semantic.ir.BinarySelector;
import deal.semantic.ir.ContractSnapshotCanonicalizer;
import deal.semantic.ir.FailureContractRegistry;
import deal.semantic.ir.FailurePolicyId;
import deal.semantic.ir.KindPayload;
import deal.semantic.ir.LoweringFailureDetail;
import deal.semantic.ir.ModuleId;
import deal.semantic.ir.NullableSide;
import deal.semantic.ir.RuntimeDescriptor;
import deal.semantic.ir.SemanticIdAllocator;
import deal.semantic.ir.SemanticOp;
import deal.semantic.ir.SemanticOpKind;
import deal.semantic.ir.SourceOrigin;
import deal.semantic.ir.SourceOriginKind;
import deal.semantic.ir.SourceSpan;
import deal.semantic.ir.ValueId;
import deal.types.Type;
import deal.types.Types;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Verifies the ISSUE-0407 comparison producer surface:
 * {@link ComparisonSelectorLowering} as the single checked-fact &rarr;
 * {@link BinarySelector} lowering map (binary-comparison-selectors B-D3)
 * and the producer of exactly one {@code BINARY} comparison op per
 * checked comparison — the exact selector per checker-admitted pair, the
 * pinned {@code BinaryPayload} fields ({@code innerDescriptor},
 * {@code side}), the logical-operator exclusion ({@code &&}/{@code ||}
 * never {@code BINARY}), the unreachable E6005 {@code COMPARISON_SELECTOR}
 * producer-defect guard for bytes-involving and no-row pairs through
 * {@code FailureContractRegistry} ({@code capability EVALUATION_ORDER},
 * {@code validatorRule COMPARISON_SELECTOR}, {@code semanticProfile
 * DEAL_V1_2_INT32}, {@code irVersion deal.semantic-ir/1}), the produced
 * op's shape (operands in source order, result type {@code boolean},
 * policy {@code NO_DEAL_FAILURE}, valid T3 contract digest), and
 * byte-identical determinism.
 *
 * <p>Tests:
 * <ol>
 *   <li>Equality map pins: int/number/string/boolean/null equality,
 *       nullable-vs-null in both directions with the correct
 *       {@code side}, equal nullable pairs with {@code side BOTH} and
 *       the inner descriptor (never a reference selector), and equal
 *       array/table/class/function types with {@code REFERENCE_*} and
 *       the shared checked descriptor in {@code innerDescriptor}.</li>
 *   <li>Relational map pins: int/number/string LT/LE/GT/GE.</li>
 *   <li>Payload rules: {@code innerDescriptor}/{@code side} are
 *       {@code null} for every non-nullable selector and carried exactly
 *       for {@code NULLABLE_*}/{@code NULLABLE_NULL_*}/{@code REFERENCE_*}.</li>
 *   <li>Defect rows: bytes-involving pairs (bytes, bytes[], nullable
 *       bytes, nullable-bytes vs null both directions), mixed pairs,
 *       relationals over non-orderable types, {@code &&}/{@code ||},
 *       arithmetic operators, and {@code Type.Error} all raise
 *       {@code ComparisonSelectorLowering.Defect}; the failure-carrier
 *       seam returns the exact E6005 {@code COMPARISON_SELECTOR}
 *       diagnostic through {@code FailureContractRegistry}.</li>
 *   <li>Produced op: exactly one {@code BINARY} op — operands in source
 *       order, operand types via the descriptor service, result type
 *       {@code boolean}, policy {@code NO_DEAL_FAILURE}, caller origin
 *       verbatim, fresh ids from the caller's allocator, and a T3-valid
 *       contract digest.</li>
 *   <li>Determinism: two fresh allocators over the same module produce
 *       identical ids and byte-identical contract digests.</li>
 * </ol>
 */
public class ComparisonSelectorLoweringTest {

    private static final ModuleId MOD = new ModuleId("app.main");

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

    private static ComparisonSelectorLowering.Defect defect(Runnable runnable) {
        try {
            runnable.run();
            throw new IllegalStateException("no Defect raised");
        } catch (ComparisonSelectorLowering.Defect defect) {
            return defect;
        }
    }

    private static void expectDefect(Runnable runnable, String what) {
        try {
            runnable.run();
            fail("expected ComparisonSelectorLowering.Defect for " + what
                + ", but no exception was raised");
        } catch (ComparisonSelectorLowering.Defect expected) {
            passed++;
        } catch (Throwable other) {
            fail("expected ComparisonSelectorLowering.Defect for " + what + ", got "
                + other.getClass().getSimpleName() + ": " + other.getMessage());
        }
    }

    private static void checkPayload(KindPayload.BinaryPayload payload,
            BinarySelector selector, RuntimeDescriptor innerDescriptor, NullableSide side,
            String what) {
        check(payload != null, what + ": payload produced");
        if (payload == null) return;
        check(payload.selector() == selector,
            what + ": selector is " + selector + ", got " + payload.selector());
        check(java.util.Objects.equals(payload.innerDescriptor(), innerDescriptor),
            what + ": innerDescriptor is " + innerDescriptor + ", got "
                + payload.innerDescriptor());
        check(payload.side() == side,
            what + ": side is " + side + ", got " + payload.side());
    }

    // =========================================================================
    // 1. Equality map pins
    // =========================================================================

    static void testEqualityMapPins() {
        System.out.println("-- B-D3 equality map pins --");

        checkPayload(
            ComparisonSelectorLowering.payloadOf(BinaryOp.EQ, Type.Int.INSTANCE, Type.Int.INSTANCE),
            BinarySelector.INT32_EQ, null, null, "int === int");
        checkPayload(
            ComparisonSelectorLowering.payloadOf(BinaryOp.NEQ, Type.Int.INSTANCE, Type.Int.INSTANCE),
            BinarySelector.INT32_NE, null, null, "int !== int");

        checkPayload(
            ComparisonSelectorLowering.payloadOf(BinaryOp.EQ, Type.Number.INSTANCE,
                Type.Number.INSTANCE),
            BinarySelector.NUMBER_EQ, null, null, "number === number");
        checkPayload(
            ComparisonSelectorLowering.payloadOf(BinaryOp.NEQ, Type.Number.INSTANCE,
                Type.Number.INSTANCE),
            BinarySelector.NUMBER_NE, null, null, "number !== number");

        checkPayload(
            ComparisonSelectorLowering.payloadOf(BinaryOp.EQ, Type.String.INSTANCE,
                Type.String.INSTANCE),
            BinarySelector.STRING_EQ, null, null, "string === string");
        checkPayload(
            ComparisonSelectorLowering.payloadOf(BinaryOp.NEQ, Type.String.INSTANCE,
                Type.String.INSTANCE),
            BinarySelector.STRING_NE, null, null, "string !== string");

        checkPayload(
            ComparisonSelectorLowering.payloadOf(BinaryOp.EQ, Type.Boolean.INSTANCE,
                Type.Boolean.INSTANCE),
            BinarySelector.BOOLEAN_EQ, null, null, "boolean === boolean");
        checkPayload(
            ComparisonSelectorLowering.payloadOf(BinaryOp.NEQ, Type.Boolean.INSTANCE,
                Type.Boolean.INSTANCE),
            BinarySelector.BOOLEAN_NE, null, null, "boolean !== boolean");

        checkPayload(
            ComparisonSelectorLowering.payloadOf(BinaryOp.EQ, Type.Null.INSTANCE,
                Type.Null.INSTANCE),
            BinarySelector.NULL_EQ, null, null, "null === null");
        checkPayload(
            ComparisonSelectorLowering.payloadOf(BinaryOp.NEQ, Type.Null.INSTANCE,
                Type.Null.INSTANCE),
            BinarySelector.NULL_NE, null, null, "null !== null");

        // Nullable-vs-null in both directions with the correct side.
        Type nullableInt = Types.nullable(Type.Int.INSTANCE);
        checkPayload(
            ComparisonSelectorLowering.payloadOf(BinaryOp.EQ, nullableInt, Type.Null.INSTANCE),
            BinarySelector.NULLABLE_NULL_EQ, RuntimeDescriptor.Int.INSTANCE, NullableSide.LEFT,
            "int|null === null");
        checkPayload(
            ComparisonSelectorLowering.payloadOf(BinaryOp.NEQ, nullableInt, Type.Null.INSTANCE),
            BinarySelector.NULLABLE_NULL_NE, RuntimeDescriptor.Int.INSTANCE, NullableSide.LEFT,
            "int|null !== null");
        checkPayload(
            ComparisonSelectorLowering.payloadOf(BinaryOp.EQ, Type.Null.INSTANCE, nullableInt),
            BinarySelector.NULLABLE_NULL_EQ, RuntimeDescriptor.Int.INSTANCE, NullableSide.RIGHT,
            "null === int|null");
        checkPayload(
            ComparisonSelectorLowering.payloadOf(BinaryOp.NEQ, Type.Null.INSTANCE, nullableInt),
            BinarySelector.NULLABLE_NULL_NE, RuntimeDescriptor.Int.INSTANCE, NullableSide.RIGHT,
            "null !== int|null");

        // Equal nullable pairs: NULLABLE_* with side BOTH and the inner
        // descriptor — never a reference selector (null is not a reference).
        checkPayload(
            ComparisonSelectorLowering.payloadOf(BinaryOp.EQ, nullableInt, nullableInt),
            BinarySelector.NULLABLE_EQ, RuntimeDescriptor.Int.INSTANCE, NullableSide.BOTH,
            "int|null === int|null");
        checkPayload(
            ComparisonSelectorLowering.payloadOf(BinaryOp.NEQ, nullableInt, nullableInt),
            BinarySelector.NULLABLE_NE, RuntimeDescriptor.Int.INSTANCE, NullableSide.BOTH,
            "int|null !== int|null");

        // Equal nullable pair with an array inner: still NULLABLE_* BOTH
        // with the array inner descriptor, never REFERENCE_*.
        Type nullableArray = Types.nullable(Types.array(Type.Int.INSTANCE));
        checkPayload(
            ComparisonSelectorLowering.payloadOf(BinaryOp.EQ, nullableArray, nullableArray),
            BinarySelector.NULLABLE_EQ, new RuntimeDescriptor.Array(RuntimeDescriptor.Int.INSTANCE),
            NullableSide.BOTH,
            "[int]|null === [int]|null uses NULLABLE_EQ BOTH, never REFERENCE_EQ");
        checkPayload(
            ComparisonSelectorLowering.payloadOf(BinaryOp.NEQ, nullableArray, nullableArray),
            BinarySelector.NULLABLE_NE, new RuntimeDescriptor.Array(RuntimeDescriptor.Int.INSTANCE),
            NullableSide.BOTH,
            "[int]|null !== [int]|null uses NULLABLE_NE BOTH, never REFERENCE_NE");

        // Equal reference types: REFERENCE_* with the shared checked
        // descriptor for array, table, class, and function.
        Type intArray = Types.array(Type.Int.INSTANCE);
        checkPayload(
            ComparisonSelectorLowering.payloadOf(BinaryOp.EQ, intArray, intArray),
            BinarySelector.REFERENCE_EQ, new RuntimeDescriptor.Array(RuntimeDescriptor.Int.INSTANCE),
            null, "[int] === [int]");
        checkPayload(
            ComparisonSelectorLowering.payloadOf(BinaryOp.NEQ, intArray, intArray),
            BinarySelector.REFERENCE_NE, new RuntimeDescriptor.Array(RuntimeDescriptor.Int.INSTANCE),
            null, "[int] !== [int]");

        checkPayload(
            ComparisonSelectorLowering.payloadOf(BinaryOp.EQ, Type.Table.INSTANCE,
                Type.Table.INSTANCE),
            BinarySelector.REFERENCE_EQ, RuntimeDescriptor.Table.INSTANCE, null, "table === table");
        checkPayload(
            ComparisonSelectorLowering.payloadOf(BinaryOp.NEQ, Type.Table.INSTANCE,
                Type.Table.INSTANCE),
            BinarySelector.REFERENCE_NE, RuntimeDescriptor.Table.INSTANCE, null, "table !== table");

        Type userClass = Types.classType("User", "app.main");
        RuntimeDescriptor userDescriptor = DescriptorService.describe(userClass);
        checkPayload(
            ComparisonSelectorLowering.payloadOf(BinaryOp.EQ, userClass, userClass),
            BinarySelector.REFERENCE_EQ, userDescriptor, null, "class === class");
        checkPayload(
            ComparisonSelectorLowering.payloadOf(BinaryOp.NEQ, userClass, userClass),
            BinarySelector.REFERENCE_NE, userDescriptor, null, "class !== class");

        Type syncFunc = Types.func(List.of(Type.Int.INSTANCE), Type.Null.INSTANCE);
        RuntimeDescriptor funcDescriptor = DescriptorService.describe(syncFunc);
        checkPayload(
            ComparisonSelectorLowering.payloadOf(BinaryOp.EQ, syncFunc, syncFunc),
            BinarySelector.REFERENCE_EQ, funcDescriptor, null, "function === function");
        checkPayload(
            ComparisonSelectorLowering.payloadOf(BinaryOp.NEQ, syncFunc, syncFunc),
            BinarySelector.REFERENCE_NE, funcDescriptor, null, "function !== function");
    }

    // =========================================================================
    // 2. Relational map pins
    // =========================================================================

    static void testRelationalMapPins() {
        System.out.println("-- B-D3 relational map pins --");

        checkPayload(
            ComparisonSelectorLowering.payloadOf(BinaryOp.LT, Type.Int.INSTANCE, Type.Int.INSTANCE),
            BinarySelector.INT32_LT, null, null, "int < int");
        checkPayload(
            ComparisonSelectorLowering.payloadOf(BinaryOp.LTE, Type.Int.INSTANCE, Type.Int.INSTANCE),
            BinarySelector.INT32_LE, null, null, "int <= int");
        checkPayload(
            ComparisonSelectorLowering.payloadOf(BinaryOp.GT, Type.Int.INSTANCE, Type.Int.INSTANCE),
            BinarySelector.INT32_GT, null, null, "int > int");
        checkPayload(
            ComparisonSelectorLowering.payloadOf(BinaryOp.GTE, Type.Int.INSTANCE, Type.Int.INSTANCE),
            BinarySelector.INT32_GE, null, null, "int >= int");

        checkPayload(
            ComparisonSelectorLowering.payloadOf(BinaryOp.LT, Type.Number.INSTANCE,
                Type.Number.INSTANCE),
            BinarySelector.NUMBER_LT, null, null, "number < number");
        checkPayload(
            ComparisonSelectorLowering.payloadOf(BinaryOp.LTE, Type.Number.INSTANCE,
                Type.Number.INSTANCE),
            BinarySelector.NUMBER_LE, null, null, "number <= number");
        checkPayload(
            ComparisonSelectorLowering.payloadOf(BinaryOp.GT, Type.Number.INSTANCE,
                Type.Number.INSTANCE),
            BinarySelector.NUMBER_GT, null, null, "number > number");
        checkPayload(
            ComparisonSelectorLowering.payloadOf(BinaryOp.GTE, Type.Number.INSTANCE,
                Type.Number.INSTANCE),
            BinarySelector.NUMBER_GE, null, null, "number >= number");

        checkPayload(
            ComparisonSelectorLowering.payloadOf(BinaryOp.LT, Type.String.INSTANCE,
                Type.String.INSTANCE),
            BinarySelector.STRING_LT, null, null, "string < string");
        checkPayload(
            ComparisonSelectorLowering.payloadOf(BinaryOp.LTE, Type.String.INSTANCE,
                Type.String.INSTANCE),
            BinarySelector.STRING_LE, null, null, "string <= string");
        checkPayload(
            ComparisonSelectorLowering.payloadOf(BinaryOp.GT, Type.String.INSTANCE,
                Type.String.INSTANCE),
            BinarySelector.STRING_GT, null, null, "string > string");
        checkPayload(
            ComparisonSelectorLowering.payloadOf(BinaryOp.GTE, Type.String.INSTANCE,
                Type.String.INSTANCE),
            BinarySelector.STRING_GE, null, null, "string >= string");
    }

    // =========================================================================
    // 3. Defect rows: no-row pairs raise COMPARISON_SELECTOR
    // =========================================================================

    static void testDefectRows() {
        System.out.println("-- No-row pairs raise the COMPARISON_SELECTOR defect --");

        // Bytes-involving pairs (the E3019 gate removes them before
        // lowering; reaching the producer is a defect — B-D7).
        expectDefect(() -> ComparisonSelectorLowering.payloadOf(BinaryOp.EQ,
            Type.Bytes.INSTANCE, Type.Bytes.INSTANCE), "bytes === bytes");
        expectDefect(() -> ComparisonSelectorLowering.payloadOf(BinaryOp.NEQ,
            Type.Bytes.INSTANCE, Type.Bytes.INSTANCE), "bytes !== bytes");
        Type bytesArray = Types.array(Type.Bytes.INSTANCE);
        expectDefect(() -> ComparisonSelectorLowering.payloadOf(BinaryOp.EQ,
            bytesArray, bytesArray), "bytes[] === bytes[]");
        Type nullableBytes = Types.nullable(Type.Bytes.INSTANCE);
        expectDefect(() -> ComparisonSelectorLowering.payloadOf(BinaryOp.EQ,
            nullableBytes, nullableBytes), "bytes|null === bytes|null");
        expectDefect(() -> ComparisonSelectorLowering.payloadOf(BinaryOp.EQ,
            nullableBytes, Type.Null.INSTANCE), "bytes|null === null");
        expectDefect(() -> ComparisonSelectorLowering.payloadOf(BinaryOp.EQ,
            Type.Null.INSTANCE, nullableBytes), "null === bytes|null");
        Type funcWithBytes = Types.func(List.of(Type.Int.INSTANCE), Type.Bytes.INSTANCE);
        expectDefect(() -> ComparisonSelectorLowering.payloadOf(BinaryOp.EQ,
            funcWithBytes, funcWithBytes), "(int)=>bytes === (int)=>bytes");

        // Mixed pairs (E3006 at the checker) and non-orderable
        // relationals (E3007 at the checker).
        expectDefect(() -> ComparisonSelectorLowering.payloadOf(BinaryOp.EQ,
            Type.Bytes.INSTANCE, Type.Number.INSTANCE), "bytes === number");
        expectDefect(() -> ComparisonSelectorLowering.payloadOf(BinaryOp.EQ,
            Type.Int.INSTANCE, Type.Number.INSTANCE), "int === number");
        expectDefect(() -> ComparisonSelectorLowering.payloadOf(BinaryOp.LT,
            Type.Bytes.INSTANCE, Type.Bytes.INSTANCE), "bytes < bytes");
        expectDefect(() -> ComparisonSelectorLowering.payloadOf(BinaryOp.LT,
            Type.Boolean.INSTANCE, Type.Boolean.INSTANCE), "boolean < boolean");
        expectDefect(() -> ComparisonSelectorLowering.payloadOf(BinaryOp.LT,
            Type.Null.INSTANCE, Type.Null.INSTANCE), "null < null");
        expectDefect(() -> ComparisonSelectorLowering.payloadOf(BinaryOp.LT,
            Types.array(Type.Int.INSTANCE), Types.array(Type.Int.INSTANCE)),
            "[int] < [int]");
        expectDefect(() -> ComparisonSelectorLowering.payloadOf(BinaryOp.LT,
            Type.Table.INSTANCE, Type.Table.INSTANCE), "table < table");

        // The comparison producer never handles logical or arithmetic
        // operators (&&/|| lower to BRANCH; arithmetic is the
        // signed-int32/containers epics' domain).
        expectDefect(() -> ComparisonSelectorLowering.payloadOf(BinaryOp.AND,
            Type.Boolean.INSTANCE, Type.Boolean.INSTANCE), "true && false");
        expectDefect(() -> ComparisonSelectorLowering.payloadOf(BinaryOp.OR,
            Type.Boolean.INSTANCE, Type.Boolean.INSTANCE), "true || false");
        expectDefect(() -> ComparisonSelectorLowering.payloadOf(BinaryOp.ADD,
            Type.Int.INSTANCE, Type.Int.INSTANCE), "int + int");
        expectDefect(() -> ComparisonSelectorLowering.payloadOf(BinaryOp.SUB,
            Type.Number.INSTANCE, Type.Number.INSTANCE), "number - number");

        // The internal Error sentinel has no row.
        expectDefect(() -> ComparisonSelectorLowering.payloadOf(BinaryOp.EQ,
            Type.Error.INSTANCE, Type.Int.INSTANCE), "error === int");

        // Fail closed on null inputs.
        try {
            ComparisonSelectorLowering.payloadOf(null, Type.Int.INSTANCE, Type.Int.INSTANCE);
            fail("payloadOf(null op, ...) should throw NullPointerException");
        } catch (NullPointerException expected) {
            passed++;
        }
        try {
            ComparisonSelectorLowering.payloadOf(BinaryOp.EQ, null, Type.Int.INSTANCE);
            fail("payloadOf(EQ, null, ...) should throw NullPointerException");
        } catch (NullPointerException expected) {
            passed++;
        }
    }

    // =========================================================================
    // 4. E6005 COMPARISON_SELECTOR failure carrier
    // =========================================================================

    static void testE6005ComparisonSelectorCarrier() {
        System.out.println("-- E6005 COMPARISON_SELECTOR failure carrier --");

        ComparisonSelectorLowering.Defect defect = defect(
            () -> ComparisonSelectorLowering.payloadOf(BinaryOp.EQ,
                Type.Bytes.INSTANCE, Type.Bytes.INSTANCE));

        LoweringFailureDetail detail =
            ComparisonSelectorLowering.loweringFailureDetail(MOD, defect);
        check("app.main".equals(detail.module()), "detail module is app.main");
        check(detail.capability() == deal.semantic.ir.SemanticCapability.EVALUATION_ORDER,
            "detail capability is EVALUATION_ORDER");
        check("COMPARISON_SELECTOR".equals(detail.validatorRule()),
            "detail validatorRule is COMPARISON_SELECTOR");
        check(detail.semanticProfile() == deal.semantic.ir.SemanticProfile.DEAL_V1_2_INT32,
            "detail semanticProfile is DEAL_V1_2_INT32");
        check("deal.semantic-ir/1".equals(detail.irVersion()),
            "detail irVersion is deal.semantic-ir/1");
        check(detail.origin().startsWith("ComparisonSelectorLowering COMPARISON_SELECTOR"),
            "detail origin names the ComparisonSelectorLowering COMPARISON_SELECTOR seam");

        CompilerDiagnostic diagnostic = FailureContractRegistry.e6005(detail);
        CompilerDiagnostic directDiagnostic =
            ComparisonSelectorLowering.e6005(MOD, defect);
        check("E6005".equals(diagnostic.code()), "diagnostic code is E6005");
        check(diagnostic.diagnosticCode() == DiagnosticCode.E6005,
            "diagnosticCode is the registered E6005");
        check(DiagnosticCode.E6005.phase() == DiagnosticCode.Phase.BACKEND_LOWERING,
            "E6005 phase is BACKEND_LOWERING");
        check("error".equals(diagnostic.severity()), "E6005 severity is error");
        check(diagnostic.message().contains("app.main")
                && diagnostic.message().contains("EVALUATION_ORDER")
                && diagnostic.message().contains("COMPARISON_SELECTOR")
                && diagnostic.message().contains("DEAL_V1_2_INT32")
                && diagnostic.message().contains("deal.semantic-ir/1"),
            "E6005 message carries the pinned detail fields");
        check(directDiagnostic.code().equals("E6005")
                && directDiagnostic.diagnosticCode() == DiagnosticCode.E6005
                && directDiagnostic.message().equals(diagnostic.message()),
            "the producer's e6005 seam builds the identical registry-owned diagnostic");

        // The same carrier serves every defect row (deterministic seam).
        ComparisonSelectorLowering.Defect logicalDefect = defect(
            () -> ComparisonSelectorLowering.payloadOf(BinaryOp.AND,
                Type.Boolean.INSTANCE, Type.Boolean.INSTANCE));
        LoweringFailureDetail logicalDetail =
            ComparisonSelectorLowering.loweringFailureDetail(MOD, logicalDefect);
        check("COMPARISON_SELECTOR".equals(logicalDetail.validatorRule())
                && logicalDetail.capability() == deal.semantic.ir.SemanticCapability.EVALUATION_ORDER,
            "the logical-operator defect uses the same COMPARISON_SELECTOR carrier");
    }

    // =========================================================================
    // 5. One BINARY op per checked comparison
    // =========================================================================

    private static SourceOrigin origin() {
        return new SourceOrigin("test.deal", new SourceSpan("test.deal", 1, 10, 1, 21),
            SourceOriginKind.USER, new AnchorId(1), null);
    }

    static void testProducedOpShape() {
        System.out.println("-- One BINARY comparison op per checked comparison --");

        ValueId left = new ValueId(5);
        ValueId right = new ValueId(6);
        SemanticIdAllocator allocator = SemanticIdAllocator.over(List.of(MOD));
        SourceOrigin opOrigin = origin();

        SemanticOp op = ComparisonSelectorLowering.produce(MOD, BinaryOp.EQ,
            Type.Int.INSTANCE, Type.Int.INSTANCE, left, right, opOrigin, allocator, 3, 0);

        check(op.kind() == SemanticOpKind.BINARY, "kind is BINARY");
        check(op.result() instanceof ValueId, "result is a fresh ValueId");
        check(op.resultType() == RuntimeDescriptor.Boolean.INSTANCE,
            "result type is boolean");
        check(op.operands().equals(List.of(left, right)),
            "operands appear in source order (left then right)");
        check(op.operandTypes().equals(List.of(RuntimeDescriptor.Int.INSTANCE,
                RuntimeDescriptor.Int.INSTANCE)),
            "operand types are the checked operand descriptors");
        check(op.failurePolicy() == FailurePolicyId.NO_DEAL_FAILURE,
            "policy is NO_DEAL_FAILURE (validator-pinned binaryPolicy)");
        check(op.origin() == opOrigin, "the caller's origin is carried verbatim");
        check(op.payload() instanceof KindPayload.BinaryPayload payload
                && payload.selector() == BinarySelector.INT32_EQ
                && payload.innerDescriptor() == null
                && payload.side() == null,
            "payload is the B-D3 INT32_EQ payload");

        // T3 contract wiring: the recomputed digest equals the carried
        // digest, so the op is validator-compatible (R-DIGEST).
        String recomputed = ContractSnapshotCanonicalizer.digest(op.contract());
        check(recomputed.equals(op.contract().canonicalDigest()),
            "the contract digest recomputes equal (T3-valid op)");
        check(op.contract().selector() == BinarySelector.INT32_EQ,
            "the snapshot carries the exact selector");
        check(op.contract().payload() == op.payload()
                && op.contract().resultType() == RuntimeDescriptor.Boolean.INSTANCE
                && op.contract().operandTypes().equals(op.operandTypes())
                && op.contract().failurePolicy() == FailurePolicyId.NO_DEAL_FAILURE,
            "the snapshot mirrors the op's own fields (contract wiring)");

        // A nullable comparison produces the nullable payload with the
        // pinned inner descriptor and side.
        SemanticIdAllocator allocator2 = SemanticIdAllocator.over(List.of(MOD));
        Type nullableInt = Types.nullable(Type.Int.INSTANCE);
        SemanticOp nullableOp = ComparisonSelectorLowering.produce(MOD, BinaryOp.EQ,
            nullableInt, Type.Null.INSTANCE, new ValueId(7), new ValueId(8), origin(),
            allocator2, 4, 0);
        check(nullableOp.payload() instanceof KindPayload.BinaryPayload payload
                && payload.selector() == BinarySelector.NULLABLE_NULL_EQ
                && payload.innerDescriptor() == RuntimeDescriptor.Int.INSTANCE
                && payload.side() == NullableSide.LEFT,
            "produce carries the NULLABLE_NULL_EQ payload (LEFT, inner int)");
        check(nullableOp.resultType() == RuntimeDescriptor.Boolean.INSTANCE,
            "nullable comparison result type is boolean");

        // A no-row pair raises the Defect from produce (defensive guard).
        expectDefect(() -> ComparisonSelectorLowering.produce(MOD, BinaryOp.EQ,
            Type.Bytes.INSTANCE, Type.Bytes.INSTANCE, new ValueId(9), new ValueId(10),
            origin(), SemanticIdAllocator.over(List.of(MOD)), 5, 0),
            "produce(bytes === bytes) raises the COMPARISON_SELECTOR defect");

        // Fail closed on null/negative inputs.
        try {
            ComparisonSelectorLowering.produce(MOD, BinaryOp.EQ, Type.Int.INSTANCE,
                Type.Int.INSTANCE, null, right, origin(), allocator, 3, 0);
            fail("produce with a null operand should throw NullPointerException");
        } catch (NullPointerException expected) {
            passed++;
        }
        try {
            ComparisonSelectorLowering.produce(MOD, BinaryOp.EQ, Type.Int.INSTANCE,
                Type.Int.INSTANCE, left, right, origin(), allocator, -1, 0);
            fail("produce with a negative sourceOrdinal should throw IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            passed++;
        }
    }

    // =========================================================================
    // 6. Determinism
    // =========================================================================

    static void testDeterminism() {
        System.out.println("-- Determinism: identical ids and digests across fresh allocators --");

        ValueId left = new ValueId(11);
        ValueId right = new ValueId(12);

        SemanticOp first = ComparisonSelectorLowering.produce(MOD, BinaryOp.LTE,
            Type.Number.INSTANCE, Type.Number.INSTANCE, left, right, origin(),
            SemanticIdAllocator.over(List.of(MOD)), 7, 1);
        SemanticOp second = ComparisonSelectorLowering.produce(MOD, BinaryOp.LTE,
            Type.Number.INSTANCE, Type.Number.INSTANCE, left, right, origin(),
            SemanticIdAllocator.over(List.of(MOD)), 7, 1);

        check(first.result().equals(second.result()),
            "identical result ValueIds from fresh allocators at the same coordinates");
        check(first.opId().equals(second.opId()),
            "identical OpIds from fresh allocators at the same coordinates");
        check(first.contract().canonicalDigest().equals(second.contract().canonicalDigest()),
            "byte-identical contract digests across fresh allocators");
        check(first.payload() instanceof KindPayload.BinaryPayload payload
                && payload.selector() == BinarySelector.NUMBER_LE,
            "both runs select NUMBER_LE");
    }

    // =========================================================================
    // 7. Phase-3 gate position: checker diagnostics abort before routing
    // =========================================================================

    static void testPhase3CheckerGateAbortsBeforeRouting() throws Exception {
        System.out.println("-- Phase-3 gate position: checker diagnostics abort before routing --");

        // The E3019 gate lives in checkBinary (phase 3, the checker). The
        // orchestrator fails the compile on phase-3 checker diagnostics
        // before the checked project (phase 3.5) and the route plan
        // (phase 3.7) are produced, so no route purpose ever carries a
        // bytes comparison into lowering. The v1.2 frontend cannot
        // produce a bytes-typed expression yet (bytes value semantics are
        // ISSUE-0111/ISSUE-0158's, so the bytes type name is unresolved —
        // E3004), which makes the exact E3019 shadow-route scenario
        // unsourceable in this revision; this pin proves the structural
        // backbone: any phase-3 checker rejection (E3019 included, once
        // bytes comparisons become producible) aborts the compile in
        // phase 3 — before phase 3.5 and before phase 3.7 route planning —
        // with no lowering and no E6005.
        Path tmp = Files.createTempDirectory("deal-comparison-gate");
        try {
            Path entry = tmp.resolve("entry.deal");
            Files.writeString(entry, ""
                + "export function main(): null {\n"
                + "  let a: boolean = 1 === true;\n"
                + "  return null;\n"
                + "}\n");
            Path output = tmp.resolve("build");

            deal.module.CompilationOrchestrator orchestrator =
                new deal.module.CompilationOrchestrator(
                    entry.toAbsolutePath().normalize(),
                    output.toAbsolutePath().normalize(),
                    false, null, List.of(tmp.toAbsolutePath().normalize()),
                    Path.of("std").toAbsolutePath().normalize());
            boolean ok = orchestrator.compile();
            check(!ok, "a checker-failing module fails the compile in phase 3");
            check(orchestrator.diagnostics().stream()
                    .anyMatch(d -> d.code().equals("E3006")),
                "the phase-3 checker diagnostic (E3006) is reported");
            check(orchestrator.checkedProject() == null,
                "phase 3.5 (checked project) never ran");
            check(orchestrator.routePlan() == null,
                "phase 3.7 (route plan) never ran — no route purpose carries a "
                    + "checker-rejected comparison into lowering");
            check(orchestrator.diagnostics().stream()
                    .noneMatch(d -> d.code().equals("E6005")),
                "a checker rejection is never E6005 (producer-defect only)");
        } finally {
            // Best-effort temp cleanup only; never part of a test result.
            try (java.util.stream.Stream<Path> walk = Files.walk(tmp)) {
                walk.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> p.toFile().delete());
            } catch (java.io.IOException ignored) {
                // Temp cleanup is best-effort.
            }
        }
    }

    // =========================================================================
    // Runner
    // =========================================================================

    public static void main(String[] args) {
        System.out.println("=== Comparison Selector Lowering Test (ISSUE-0407 B-D3/B-D7) ===\n");

        testEqualityMapPins();
        testRelationalMapPins();
        testDefectRows();
        testE6005ComparisonSelectorCarrier();
        testProducedOpShape();
        testDeterminism();
        try {
            testPhase3CheckerGateAbortsBeforeRouting();
        } catch (Exception e) {
            fail("phase-3 gate-position pin threw: " + e);
        }

        System.out.println("\nPassed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }
}
