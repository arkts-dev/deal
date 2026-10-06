package deal.semantic.ir;

import deal.diagnostics.CompilerDiagnostic;
import deal.diagnostics.DiagnosticCode;
import deal.diagnostics.DiagnosticRange;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class FailureContractRegistry {

    private FailureContractRegistry() { /* closed data table + E6005 owner */ }

    // =========================================================================
    // Closed row data (parent "Closed failure policies and canonical visible errors")
    // =========================================================================

    private static final String ORIGIN_OPERATION = "the operation origin";
    private static final String CAUSE_NONE = "no cause";
    private static final String FRAMES_ACTIVE = "active DEAL calls from innermost to outermost";

    private static final Map<FailurePolicyId, FailurePolicyRow> ROWS = buildRows();

    private static Map<FailurePolicyId, FailurePolicyRow> buildRows() {
        Map<FailurePolicyId, FailurePolicyRow> rows = new EnumMap<>(FailurePolicyId.class);

        rows.put(FailurePolicyId.NO_DEAL_FAILURE, makeRow(FailurePolicyId.NO_DEAL_FAILURE,
            null, List.of(), List.of(),
            "propagation only: a propagated child/operand failure keeps its own origin",
            "propagation only: a propagated child/operand failure keeps its own cause",
            "propagation only: a propagated child/operand failure keeps its own frames",
            "only already-started child/operand failure may propagate"));

        rows.put(FailurePolicyId.TYPE_DESCRIPTOR, makeRow(FailurePolicyId.TYPE_DESCRIPTOR,
            DiagnosticCode.E8001,
            List.of("expected {kind}",
                "expected string, got invalid Unicode scalar encoding",
                "expected instance of {expected}, got {actual}",
                "expected string, got invalid UTF-8 encoding",
                "expected string, got UTF-16 surrogate code point"),
            List.of("expected", "actual"),
            ORIGIN_OPERATION, CAUSE_NONE, FRAMES_ACTIVE,
            "single check: wrong-kind or invalid-unicode-string projection per the checked "
                + "descriptor (descriptor is expected; actual kind is actual)"));

        rows.put(FailurePolicyId.INT32_RESULT, makeRow(FailurePolicyId.INT32_RESULT,
            DiagnosticCode.E8004, List.of("int out of safe range"), List.of(),
            "arithmetic/boundary origin", CAUSE_NONE, FRAMES_ACTIVE,
            "single range check: integral result outside [-2147483648, 2147483647]"));

        rows.put(FailurePolicyId.INT32_DIVISOR_THEN_RESULT,
            makeRow(FailurePolicyId.INT32_DIVISOR_THEN_RESULT, DiagnosticCode.E8005,
                List.of("integer division by zero"), List.of(),
                ORIGIN_OPERATION, CAUSE_NONE, FRAMES_ACTIVE,
                "zero divisor first, then INT32_RESULT; -2147483648 / -1 is E8004"));

        rows.put(FailurePolicyId.INT32_EXPONENT_THEN_RESULT,
            makeRow(FailurePolicyId.INT32_EXPONENT_THEN_RESULT, DiagnosticCode.E8006,
                List.of("integer exponent must be non-negative"), List.of(),
                ORIGIN_OPERATION, CAUSE_NONE, FRAMES_ACTIVE,
                "negative exponent first, then INT32_RESULT"));

        rows.put(FailurePolicyId.INT_CONVERSION, makeRow(FailurePolicyId.INT_CONVERSION,
            DiagnosticCode.E8001,
            List.of("cannot convert null to int", "expected int, got NaN",
                "expected int, got infinity", "expected int, got non-integer number"),
            List.of(),
            ORIGIN_OPERATION, CAUSE_NONE, FRAMES_ACTIVE,
            "normative order: null, then NaN, then infinity, then fractional, then wrong kind "
                + "via TYPE_DESCRIPTOR, then integral out of range via E8004"));

        rows.put(FailurePolicyId.NUMBER_CONVERSION,
            makeRow(FailurePolicyId.NUMBER_CONVERSION, DiagnosticCode.E8001,
                List.of("cannot convert null to number"), List.of(),
                ORIGIN_OPERATION, CAUSE_NONE, FRAMES_ACTIVE,
                "null first, then wrong kind via TYPE_DESCRIPTOR; int converts exactly to double"));

        rows.put(FailurePolicyId.ARRAY_ELEMENT_DESCRIPTOR,
            makeRow(FailurePolicyId.ARRAY_ELEMENT_DESCRIPTOR, DiagnosticCode.E8003,
                List.of("array element {oneBasedIndex} type mismatch"),
                List.of("oneBasedIndex"),
                ORIGIN_OPERATION, "cause equal to the leaf descriptor error", FRAMES_ACTIVE,
                "first failing element in increasing index order"));

        rows.put(FailurePolicyId.ARRAY_READ_INDEX_THEN_DESCRIPTOR,
            makeRow(FailurePolicyId.ARRAY_READ_INDEX_THEN_DESCRIPTOR, DiagnosticCode.E8002,
                List.of("negative array index"), List.of(),
                ORIGIN_OPERATION, CAUSE_NONE, FRAMES_ACTIVE,
                "negative index first, then read missing and let the contextual boundary decide "
                    + "E8001/null"));

        rows.put(FailurePolicyId.ARRAY_WRITE_BOUNDS_THEN_ELEMENT,
            makeRow(FailurePolicyId.ARRAY_WRITE_BOUNDS_THEN_ELEMENT, DiagnosticCode.E8002,
                List.of("array index out of bounds"), List.of(),
                ORIGIN_OPERATION, CAUSE_NONE, FRAMES_ACTIVE,
                "index < 0 or > length first, then the element boundary; mutation last"));

        rows.put(FailurePolicyId.ARRAY_DELETE_BOUNDS,
            makeRow(FailurePolicyId.ARRAY_DELETE_BOUNDS, DiagnosticCode.E8002,
                List.of("array index out of bounds"), List.of(),
                "the delete-target origin", CAUSE_NONE, FRAMES_ACTIVE,
                "index < 0 or > length fails; otherwise no failure and the commit's nil write "
                    + "runs after the boundary"));

        // The bytes rows (K6; the pinned v1.2 texts): allocation
        // (E8012, the bytes(...) call expression), the element read
        // (E8012, the index expression), and the element write (E8012
        // bounds first at the index expression, then E8013 range at the
        // assignment expression; the mutation runs only after both pass
        // and a failed write changes no storage).
        rows.put(FailurePolicyId.BYTES_ALLOCATE,
            makeRow(FailurePolicyId.BYTES_ALLOCATE, DiagnosticCode.E8012,
                List.of("bytes length must be non-negative"), List.of(),
                "the bytes(...) call expression", CAUSE_NONE, FRAMES_ACTIVE,
                "single check: the allocation length value is non-negative and its "
                    + "allocation succeeds"));

        rows.put(FailurePolicyId.BYTES_READ,
            makeRow(FailurePolicyId.BYTES_READ, DiagnosticCode.E8012,
                List.of("bytes index out of bounds"), List.of(),
                "the index expression", CAUSE_NONE, FRAMES_ACTIVE,
                "single bounds check: index < 0 or index >= b.length fails; otherwise the "
                    + "unsigned byte is read"));

        // Two projections share the row: template 0 is the bounds check
        // (the row's own E8012 at the index expression), template 1 is
        // the value-range check committed by the write (E8013 at the
        // assignment expression; the mutation runs last and a failed
        // write changes no storage).
        rows.put(FailurePolicyId.BYTES_WRITE,
            makeRow(FailurePolicyId.BYTES_WRITE, DiagnosticCode.E8012,
                List.of("bytes index out of bounds", "bytes value out of range"),
                List.of(),
                "the index expression for the bounds check, the assignment expression for "
                    + "the value-range check", CAUSE_NONE, FRAMES_ACTIVE,
                "bounds first (index < 0 or index >= b.length), then the value range "
                    + "(0..255), then the single mutation"));

        rows.put(FailurePolicyId.FUNCTION_SIGNATURE,
            makeRow(FailurePolicyId.FUNCTION_SIGNATURE, DiagnosticCode.E8010,
                List.of("function signature mismatch: expected {expected}, got {actual}"),
                List.of("expected", "actual"),
                "adaptation/boundary origin", CAUSE_NONE, FRAMES_ACTIVE,
                "single signature check; the descriptor-kind rule selects this policy for every "
                    + "descriptor-checking boundary whose checked descriptor is a function type"));

        rows.put(FailurePolicyId.HOST_PARAMETER, makeRow(FailurePolicyId.HOST_PARAMETER,
            DiagnosticCode.E8010,
            List.of("parameter {index} type mismatch: {inner}"),
            List.of("index", "expected", "actual"),
            "the call origin", CAUSE_NONE, FRAMES_ACTIVE,
            "in one-based parameter order at the host call; all argument expressions finish "
                + "before checks"));

        rows.put(FailurePolicyId.HOST_SYNC_RETURN,
            makeRow(FailurePolicyId.HOST_SYNC_RETURN, DiagnosticCode.E8010,
                List.of("return value 1 type mismatch: expected {expected}, got nothing",
                    "return value 1 type mismatch: {inner}"),
                List.of("expected", "actual"),
                "the call origin", CAUSE_NONE, FRAMES_ACTIVE,
                "no value first, then wrong value"));

        rows.put(FailurePolicyId.ASYNC_COMPLETION,
            makeRow(FailurePolicyId.ASYNC_COMPLETION, DiagnosticCode.E8001,
                List.of("expected {expected}", "expected {expected}, got {actual}"),
                List.of("expected", "actual"),
                "await origin", CAUSE_NONE, FRAMES_ACTIVE,
                "operation failure wins, then the completion descriptor check"));

        rows.put(FailurePolicyId.ASYNC_OPERATION_HANDLE,
            makeRow(FailurePolicyId.ASYNC_OPERATION_HANDLE, DiagnosticCode.E8010,
                List.of("host async function must return an async operation, got {actual}"),
                List.of("expected", "actual"),
                "the async call origin", CAUSE_NONE, FRAMES_ACTIVE,
                "the ASYNC_START(HOST) op's own terminal check, not a BOUNDARY child"));

        rows.put(FailurePolicyId.HOST_LOAD, makeRow(FailurePolicyId.HOST_LOAD,
            DiagnosticCode.E8011,
            List.of("failed to load host module '{module}': {reason}",
                "host module '{module}' did not return a module object",
                "missing host export '{name}' in module '{module}'",
                "host export '{name}' in module '{module}' has signature mismatch: "
                    + "expected {expected}, got {actual}",
                "host class '{name}' in module '{module}' has identity mismatch: "
                    + "expected {expected}, got {actual}",
                "host class '{name}' in module '{module}' has invalid {defaults|fields} metadata"),
            List.of("module", "reason", "name", "expected", "actual", "defaults|fields"),
            "import origin", CAUSE_NONE, FRAMES_ACTIVE,
            "first declaration-order defect wins"));

        rows.put(FailurePolicyId.CLASS_CONSTRUCTION,
            makeRow(FailurePolicyId.CLASS_CONSTRUCTION, DiagnosticCode.E8007,
                List.of("extra field '{field}' in class '{classId}'"),
                List.of("field", "classId"),
                ORIGIN_OPERATION, CAUSE_NONE, FRAMES_ACTIVE,
                "extra key first in provided-source order, then declaration-order field "
                    + "boundaries"));

        rows.put(FailurePolicyId.JSON_PARSE_SYNTAX,
            makeRow(FailurePolicyId.JSON_PARSE_SYNTAX, DiagnosticCode.E8001,
                List.of("JSON parse error at position {oneBasedByteOffset}: {reason}"),
                List.of("oneBasedByteOffset", "reason"),
                "the STDLIB_CALL(JSON_PARSE) call origin", CAUSE_NONE, FRAMES_ACTIVE,
                "parameter boundaries first, then the parse; a successful parse whose "
                    + "top-level value is not a table then fails the STDLIB_RETURN boundary"));

        rows.put(FailurePolicyId.JSON_FROM_NULL, makeRow(FailurePolicyId.JSON_FROM_NULL,
            null, List.of(), List.of(),
            "no visible failure: the conversion returns language null",
            "no visible failure: the swallowed failure never escapes",
            "no visible failure: the swallowed failure never escapes",
            "any syntax, unknown-key, descriptor, default, or field failure returns language "
                + "null; no partial instance is visible"));

        // Three projections share the row: template 0 is the @jsonable
        // C$toJson walk's first declaration-order failure (the generated
        // body's pinned fieldPath spelling), template 1 is the
        // STDLIB_CALL(JSON_STRINGIFY) rejection aligned to the corpus
        // pins (the canonical actual-kind token; the walker's fieldPath
        // stays internal metadata, never part of the visible projection),
        // and template 2 is the walk family's cycle arm, appended together
        // with its declaration so the row/arm consistency invariant holds
        // at every merge boundary (jsonable-tojson-walk-arm-binding W4).
        rows.put(FailurePolicyId.JSON_TO_ERROR, makeRow(FailurePolicyId.JSON_TO_ERROR,
            DiagnosticCode.E8001,
            List.of("value at {fieldPath} is not JSON serializable: {actual}",
                "unsupported type for JSON encoding: {actual}",
                "cyclic value cannot be encoded as JSON"),
            List.of("fieldPath", "actual"),
            "call origin", CAUSE_NONE, FRAMES_ACTIVE,
            "first declaration-order unsupported value, wrong identity, cycle, missing "
                + "required value, or nonfinite number"));

        rows.put(FailurePolicyId.SQRT_NEGATIVE, makeRow(FailurePolicyId.SQRT_NEGATIVE,
            DiagnosticCode.E8001, List.of("sqrt of negative number"), List.of(),
            ORIGIN_OPERATION, CAUSE_NONE, FRAMES_ACTIVE,
            "negative non-NaN number fails; NaN returns NaN"));

        rows.put(FailurePolicyId.THROW_TRANSFER, makeRow(FailurePolicyId.THROW_TRANSFER,
            null, List.of(), List.of(),
            "the supplied Error's origin; if it has no origin, the THROW origin",
            CAUSE_NONE, FRAMES_ACTIVE,
            "the supplied Error code/message/origin are preserved unchanged; no new failure "
                + "is synthesized"));

        rows.put(FailurePolicyId.INFRASTRUCTURE_ONLY,
            makeRow(FailurePolicyId.INFRASTRUCTURE_ONLY,
                null, List.of(), List.of(),
                "not a DEAL error: no DEAL diagnostic origin",
                "not a DEAL error: no cause",
                "not a DEAL error: no DEAL frames",
                "sink, clock, process, or harness failure is not a DEAL error and is not "
                    + "catchable"));

        // Fail closed: exactly one row per closed policy — none missing, none extra.
        if (!rows.keySet().equals(EnumSet.allOf(FailurePolicyId.class))) {
            throw new IllegalStateException(
                "failure registry must carry exactly one row per FailurePolicyId value");
        }
        return Collections.unmodifiableMap(new EnumMap<>(rows));
    }

    private static FailurePolicyRow makeRow(FailurePolicyId policy, DiagnosticCode code,
                                            List<String> templates, List<String> metadataKeys,
                                            String originRule, String causeRule,
                                            String frameRule, String precedence) {
        return new FailurePolicyRow(policy, code, templates, metadataKeys,
            originRule, causeRule, frameRule, precedence);
    }

    // =========================================================================
    // Row access
    // =========================================================================

    /**
     * The closed table: every {@link FailurePolicyId} mapped to its single
     * immutable row (unmodifiable; iteration order is the enum
     * declaration order).
     */
    public static Map<FailurePolicyId, FailurePolicyRow> rows() {
        return ROWS;
    }

    /**
     * The single immutable row of a closed policy. The key type is the
     * closed enum — reserved names cannot be expressed — and a missing row
     * fails closed instead of returning any fallback.
     *
     */
    public static FailurePolicyRow row(FailurePolicyId policy) {
        Objects.requireNonNull(policy, "policy must not be null");
        FailurePolicyRow row = ROWS.get(policy);
        if (row == null) {
            throw new IllegalArgumentException("no failure-contract row for policy " + policy);
        }
        return row;
    }

    // =========================================================================
    // Closed arm data (canonical failure-projection authority P1)
    // =========================================================================

    private static final Map<FailureArmId, FailureArm> ARMS = buildArms();

    private static Map<FailureArmId, FailureArm> buildArms() {
        Map<FailureArmId, FailureArm> arms = new EnumMap<>(FailureArmId.class);
        List<FailureArm> declared = new ArrayList<>();

        declared.add(arm(FailureArmId.TYPED_BOUNDARY_KIND, FailurePolicyId.TYPE_DESCRIPTOR, 0,
            FailureArm.ExpectedSource.KIND_TOKEN, null, FailureArm.ActualProjection.TYPED_BOUNDARY,
            FailureArm.OriginConvention.BOUNDARY_CELL, FailureArm.RenderScope.TOP_LEVEL,
            "kind", FailureArm.ParameterSource.KIND_TEXT));
        declared.add(arm(FailureArmId.TYPED_BOUNDARY_INVALID_UNICODE,
            FailurePolicyId.TYPE_DESCRIPTOR, 1,
            FailureArm.ExpectedSource.PINNED_TEXT, "string",
            FailureArm.ActualProjection.TYPED_BOUNDARY,
            FailureArm.OriginConvention.BOUNDARY_CELL, FailureArm.RenderScope.TOP_LEVEL));
        declared.add(arm(FailureArmId.CLASS_IDENTITY, FailurePolicyId.TYPE_DESCRIPTOR, 2,
            FailureArm.ExpectedSource.CLASS_ATOM, null,
            FailureArm.ActualProjection.CARRIED_CLASS_ATOM,
            FailureArm.OriginConvention.BOUNDARY_CELL, FailureArm.RenderScope.TOP_LEVEL,
            "expected", FailureArm.ParameterSource.CLASS_ATOM,
            "actual", FailureArm.ParameterSource.CARRIED_CLASS_ATOM));
        declared.add(arm(FailureArmId.HOST_STRING_INVALID_UTF8,
            FailurePolicyId.TYPE_DESCRIPTOR, 3,
            FailureArm.ExpectedSource.NONE, null, FailureArm.ActualProjection.NONE,
            FailureArm.OriginConvention.BOUNDARY_CELL, FailureArm.RenderScope.INNER_ONLY));
        declared.add(arm(FailureArmId.HOST_STRING_SURROGATE, FailurePolicyId.TYPE_DESCRIPTOR, 4,
            FailureArm.ExpectedSource.NONE, null, FailureArm.ActualProjection.NONE,
            FailureArm.OriginConvention.BOUNDARY_CELL, FailureArm.RenderScope.INNER_ONLY));

        declared.add(arm(FailureArmId.INT32_RANGE, FailurePolicyId.INT32_RESULT, 0,
            FailureArm.ExpectedSource.NONE, null, FailureArm.ActualProjection.NONE,
            FailureArm.OriginConvention.OPERATION_EXPRESSION, FailureArm.RenderScope.TOP_LEVEL));
        declared.add(arm(FailureArmId.INT32_DIVISION_BY_ZERO,
            FailurePolicyId.INT32_DIVISOR_THEN_RESULT, 0,
            FailureArm.ExpectedSource.NONE, null, FailureArm.ActualProjection.NONE,
            FailureArm.OriginConvention.OPERATION_EXPRESSION, FailureArm.RenderScope.TOP_LEVEL));
        declared.add(arm(FailureArmId.INT32_NEGATIVE_EXPONENT,
            FailurePolicyId.INT32_EXPONENT_THEN_RESULT, 0,
            FailureArm.ExpectedSource.NONE, null, FailureArm.ActualProjection.NONE,
            FailureArm.OriginConvention.OPERATION_EXPRESSION, FailureArm.RenderScope.TOP_LEVEL));

        declared.add(arm(FailureArmId.INT_CONVERSION_NULL, FailurePolicyId.INT_CONVERSION, 0,
            FailureArm.ExpectedSource.CELL_DESCRIPTOR, null,
            FailureArm.ActualProjection.TYPED_BOUNDARY,
            FailureArm.OriginConvention.CALL_EXPRESSION, FailureArm.RenderScope.TOP_LEVEL));
        declared.add(arm(FailureArmId.INT_CONVERSION_NAN, FailurePolicyId.INT_CONVERSION, 1,
            FailureArm.ExpectedSource.CELL_DESCRIPTOR, null,
            FailureArm.ActualProjection.TYPED_BOUNDARY,
            FailureArm.OriginConvention.CALL_EXPRESSION, FailureArm.RenderScope.TOP_LEVEL));
        declared.add(arm(FailureArmId.INT_CONVERSION_INFINITY, FailurePolicyId.INT_CONVERSION, 2,
            FailureArm.ExpectedSource.CELL_DESCRIPTOR, null,
            FailureArm.ActualProjection.TYPED_BOUNDARY,
            FailureArm.OriginConvention.CALL_EXPRESSION, FailureArm.RenderScope.TOP_LEVEL));
        declared.add(arm(FailureArmId.INT_CONVERSION_FRACTIONAL,
            FailurePolicyId.INT_CONVERSION, 3,
            FailureArm.ExpectedSource.CELL_DESCRIPTOR, null,
            FailureArm.ActualProjection.TYPED_BOUNDARY,
            FailureArm.OriginConvention.CALL_EXPRESSION, FailureArm.RenderScope.TOP_LEVEL));
        declared.add(arm(FailureArmId.NUMBER_CONVERSION_NULL,
            FailurePolicyId.NUMBER_CONVERSION, 0,
            FailureArm.ExpectedSource.CELL_DESCRIPTOR, null,
            FailureArm.ActualProjection.TYPED_BOUNDARY,
            FailureArm.OriginConvention.CALL_EXPRESSION, FailureArm.RenderScope.TOP_LEVEL));

        declared.add(arm(FailureArmId.ARRAY_ELEMENT_KIND,
            FailurePolicyId.ARRAY_ELEMENT_DESCRIPTOR, 0,
            FailureArm.ExpectedSource.ELEMENT_DESCRIPTOR, null,
            FailureArm.ActualProjection.TYPED_BOUNDARY,
            FailureArm.OriginConvention.BOUNDARY_CELL, FailureArm.RenderScope.TOP_LEVEL,
            "oneBasedIndex", FailureArm.ParameterSource.ONE_BASED_INDEX));
        declared.add(arm(FailureArmId.ARRAY_READ_NEGATIVE_INDEX,
            FailurePolicyId.ARRAY_READ_INDEX_THEN_DESCRIPTOR, 0,
            FailureArm.ExpectedSource.NONE, null, FailureArm.ActualProjection.NONE,
            FailureArm.OriginConvention.READ_EXPRESSION, FailureArm.RenderScope.TOP_LEVEL));
        declared.add(arm(FailureArmId.ARRAY_WRITE_BOUNDS,
            FailurePolicyId.ARRAY_WRITE_BOUNDS_THEN_ELEMENT, 0,
            FailureArm.ExpectedSource.NONE, null, FailureArm.ActualProjection.NONE,
            FailureArm.OriginConvention.ASSIGNMENT_EXPRESSION,
            FailureArm.RenderScope.TOP_LEVEL));
        declared.add(arm(FailureArmId.ARRAY_DELETE_BOUNDS,
            FailurePolicyId.ARRAY_DELETE_BOUNDS, 0,
            FailureArm.ExpectedSource.NONE, null, FailureArm.ActualProjection.NONE,
            FailureArm.OriginConvention.DELETE_TARGET, FailureArm.RenderScope.TOP_LEVEL));

        declared.add(arm(FailureArmId.BYTES_ALLOCATE, FailurePolicyId.BYTES_ALLOCATE, 0,
            FailureArm.ExpectedSource.NONE, null, FailureArm.ActualProjection.NONE,
            FailureArm.OriginConvention.CALL_EXPRESSION, FailureArm.RenderScope.TOP_LEVEL));
        declared.add(arm(FailureArmId.BYTES_READ, FailurePolicyId.BYTES_READ, 0,
            FailureArm.ExpectedSource.NONE, null, FailureArm.ActualProjection.NONE,
            FailureArm.OriginConvention.INDEX_EXPRESSION, FailureArm.RenderScope.TOP_LEVEL));
        declared.add(arm(FailureArmId.BYTES_WRITE_BOUNDS, FailurePolicyId.BYTES_WRITE, 0,
            FailureArm.ExpectedSource.NONE, null, FailureArm.ActualProjection.NONE,
            FailureArm.OriginConvention.INDEX_EXPRESSION, FailureArm.RenderScope.TOP_LEVEL));
        declared.add(armWithCode(FailureArmId.BYTES_WRITE_RANGE, FailurePolicyId.BYTES_WRITE, 1,
            DiagnosticCode.E8013, FailureArm.ExpectedSource.NONE, null,
            FailureArm.ActualProjection.NONE,
            FailureArm.OriginConvention.ASSIGNMENT_EXPRESSION,
            FailureArm.RenderScope.TOP_LEVEL));

        declared.add(arm(FailureArmId.FUNCTION_SIGNATURE_MISMATCH,
            FailurePolicyId.FUNCTION_SIGNATURE, 0,
            FailureArm.ExpectedSource.SIGNATURE, null,
            FailureArm.ActualProjection.CARRIED_SIGNATURE,
            FailureArm.OriginConvention.BOUNDARY_CELL, FailureArm.RenderScope.TOP_LEVEL,
            "expected", FailureArm.ParameterSource.DECLARED_SIGNATURE,
            "actual", FailureArm.ParameterSource.CARRIED_SIGNATURE));

        declared.add(arm(FailureArmId.HOST_PARAMETER_CELL, FailurePolicyId.HOST_PARAMETER, 0,
            FailureArm.ExpectedSource.CELL_DESCRIPTOR, null,
            FailureArm.ActualProjection.CARRIER_KIND,
            FailureArm.OriginConvention.CALL_EXPRESSION, FailureArm.RenderScope.TOP_LEVEL,
            "index", FailureArm.ParameterSource.PARAMETER_INDEX,
            "inner", FailureArm.ParameterSource.HOST_INNER_REASON));
        declared.add(arm(FailureArmId.HOST_SYNC_RETURN_NOTHING,
            FailurePolicyId.HOST_SYNC_RETURN, 0,
            FailureArm.ExpectedSource.CELL_DESCRIPTOR, null,
            FailureArm.ActualProjection.NOTHING_TOKEN,
            FailureArm.OriginConvention.CALL_EXPRESSION, FailureArm.RenderScope.TOP_LEVEL,
            "expected", FailureArm.ParameterSource.CELL_DESCRIPTOR));
        declared.add(arm(FailureArmId.HOST_SYNC_RETURN_CELL,
            FailurePolicyId.HOST_SYNC_RETURN, 1,
            FailureArm.ExpectedSource.CELL_DESCRIPTOR, null,
            FailureArm.ActualProjection.CARRIER_KIND,
            FailureArm.OriginConvention.CALL_EXPRESSION, FailureArm.RenderScope.TOP_LEVEL,
            "inner", FailureArm.ParameterSource.HOST_INNER_REASON));
        declared.add(arm(FailureArmId.ASYNC_COMPLETION_KIND,
            FailurePolicyId.ASYNC_COMPLETION, 0,
            FailureArm.ExpectedSource.KIND_TOKEN, null,
            FailureArm.ActualProjection.COMPLETION,
            FailureArm.OriginConvention.AWAIT_EXPRESSION, FailureArm.RenderScope.TOP_LEVEL,
            "expected", FailureArm.ParameterSource.KIND_TEXT));
        declared.add(arm(FailureArmId.ASYNC_COMPLETION_REFINEMENT,
            FailurePolicyId.ASYNC_COMPLETION, 1,
            FailureArm.ExpectedSource.CELL_DESCRIPTOR, null,
            FailureArm.ActualProjection.TYPED_BOUNDARY,
            FailureArm.OriginConvention.AWAIT_EXPRESSION, FailureArm.RenderScope.TOP_LEVEL,
            "expected", FailureArm.ParameterSource.CELL_DESCRIPTOR,
            "actual", FailureArm.ParameterSource.TYPED_BOUNDARY_ACTUAL));
        declared.add(arm(FailureArmId.ASYNC_SHAPE,
            FailurePolicyId.ASYNC_OPERATION_HANDLE, 0,
            FailureArm.ExpectedSource.ASYNC_OPERATION_TOKEN, null,
            FailureArm.ActualProjection.TYPED_BOUNDARY,
            FailureArm.OriginConvention.CALL_EXPRESSION, FailureArm.RenderScope.TOP_LEVEL,
            "actual", FailureArm.ParameterSource.TYPED_BOUNDARY_ACTUAL));

        declared.add(arm(FailureArmId.HOST_LOAD_FAILED, FailurePolicyId.HOST_LOAD, 0,
            FailureArm.ExpectedSource.NONE, null, FailureArm.ActualProjection.NONE,
            FailureArm.OriginConvention.IMPORT_STATEMENT, FailureArm.RenderScope.TOP_LEVEL,
            "module", FailureArm.ParameterSource.MODULE,
            "reason", FailureArm.ParameterSource.REASON));
        declared.add(arm(FailureArmId.HOST_LOAD_NOT_A_MODULE, FailurePolicyId.HOST_LOAD, 1,
            FailureArm.ExpectedSource.NONE, null, FailureArm.ActualProjection.NONE,
            FailureArm.OriginConvention.IMPORT_STATEMENT, FailureArm.RenderScope.TOP_LEVEL,
            "module", FailureArm.ParameterSource.MODULE));
        declared.add(arm(FailureArmId.HOST_LOAD_MISSING_EXPORT, FailurePolicyId.HOST_LOAD, 2,
            FailureArm.ExpectedSource.NONE, null, FailureArm.ActualProjection.NONE,
            FailureArm.OriginConvention.IMPORT_STATEMENT, FailureArm.RenderScope.TOP_LEVEL,
            "name", FailureArm.ParameterSource.NAME,
            "module", FailureArm.ParameterSource.MODULE));
        declared.add(arm(FailureArmId.HOST_LOAD_SIGNATURE_MISMATCH,
            FailurePolicyId.HOST_LOAD, 3,
            FailureArm.ExpectedSource.NONE, null, FailureArm.ActualProjection.NONE,
            FailureArm.OriginConvention.IMPORT_STATEMENT, FailureArm.RenderScope.TOP_LEVEL,
            "name", FailureArm.ParameterSource.NAME,
            "module", FailureArm.ParameterSource.MODULE,
            "expected", FailureArm.ParameterSource.EXPECTED,
            "actual", FailureArm.ParameterSource.ACTUAL));
        declared.add(arm(FailureArmId.HOST_LOAD_IDENTITY_MISMATCH,
            FailurePolicyId.HOST_LOAD, 4,
            FailureArm.ExpectedSource.NONE, null, FailureArm.ActualProjection.NONE,
            FailureArm.OriginConvention.IMPORT_STATEMENT, FailureArm.RenderScope.TOP_LEVEL,
            "name", FailureArm.ParameterSource.NAME,
            "module", FailureArm.ParameterSource.MODULE,
            "expected", FailureArm.ParameterSource.EXPECTED,
            "actual", FailureArm.ParameterSource.ACTUAL));
        declared.add(arm(FailureArmId.HOST_LOAD_INVALID_METADATA,
            FailurePolicyId.HOST_LOAD, 5,
            FailureArm.ExpectedSource.NONE, null, FailureArm.ActualProjection.NONE,
            FailureArm.OriginConvention.IMPORT_STATEMENT, FailureArm.RenderScope.TOP_LEVEL,
            "name", FailureArm.ParameterSource.NAME,
            "module", FailureArm.ParameterSource.MODULE,
            "defaults|fields", FailureArm.ParameterSource.REASON));

        declared.add(arm(FailureArmId.CLASS_EXTRA_FIELD, FailurePolicyId.CLASS_CONSTRUCTION, 0,
            FailureArm.ExpectedSource.NONE, null, FailureArm.ActualProjection.NONE,
            FailureArm.OriginConvention.CLASS_LITERAL, FailureArm.RenderScope.TOP_LEVEL,
            "field", FailureArm.ParameterSource.FIELD,
            "classId", FailureArm.ParameterSource.CLASS_ID));
        declared.add(arm(FailureArmId.JSON_PARSE_ERROR, FailurePolicyId.JSON_PARSE_SYNTAX, 0,
            FailureArm.ExpectedSource.NONE, null, FailureArm.ActualProjection.NONE,
            FailureArm.OriginConvention.CALL_EXPRESSION, FailureArm.RenderScope.TOP_LEVEL,
            "oneBasedByteOffset", FailureArm.ParameterSource.ONE_BASED_BYTE_OFFSET,
            "reason", FailureArm.ParameterSource.REASON));
        declared.add(arm(FailureArmId.JSON_TO_WALK, FailurePolicyId.JSON_TO_ERROR, 0,
            FailureArm.ExpectedSource.NONE, null,
            FailureArm.ActualProjection.TYPED_BOUNDARY,
            FailureArm.OriginConvention.CALL_EXPRESSION, FailureArm.RenderScope.TOP_LEVEL,
            "fieldPath", FailureArm.ParameterSource.FIELD_PATH,
            "actual", FailureArm.ParameterSource.TYPED_BOUNDARY_ACTUAL));
        declared.add(arm(FailureArmId.JSON_STRINGIFY_UNSUPPORTED,
            FailurePolicyId.JSON_TO_ERROR, 1,
            FailureArm.ExpectedSource.PINNED_TEXT, "string, number, boolean, or table",
            FailureArm.ActualProjection.CARRIER_KIND,
            FailureArm.OriginConvention.CALL_EXPRESSION, FailureArm.RenderScope.TOP_LEVEL,
            "actual", FailureArm.ParameterSource.CARRIER_KIND_ACTUAL));
        // The walk family's cycle arm lands together with its appended row
        // template (jsonable-tojson-walk-arm-binding W4): template index 2
        // keeps the walk arm at 0 and the std/json arm at 1, so the
        // row/arm consistency invariant holds at every merge boundary.
        declared.add(arm(FailureArmId.JSON_TO_WALK_CYCLE,
            FailurePolicyId.JSON_TO_ERROR, 2,
            FailureArm.ExpectedSource.NONE, null,
            FailureArm.ActualProjection.NONE,
            FailureArm.OriginConvention.CALL_EXPRESSION, FailureArm.RenderScope.TOP_LEVEL));
        declared.add(arm(FailureArmId.SQRT_NEGATIVE, FailurePolicyId.SQRT_NEGATIVE, 0,
            FailureArm.ExpectedSource.NONE, null,
            FailureArm.ActualProjection.CANONICAL_VALUE_TEXT,
            FailureArm.OriginConvention.CALL_EXPRESSION, FailureArm.RenderScope.TOP_LEVEL));

        for (FailureArm arm : declared) {
            if (arms.put(arm.id(), arm) != null) {
                throw new IllegalStateException("duplicate failure arm " + arm.id());
            }
        }
        if (!arms.keySet().equals(EnumSet.allOf(FailureArmId.class))) {
            throw new IllegalStateException(
                "failure arm registry must carry exactly one arm per FailureArmId value");
        }
        List<FailureArm> ordered = new ArrayList<>(declared);
        checkArmConsistency(ROWS, ordered);
        return Collections.unmodifiableMap(new EnumMap<>(arms));
    }

    /** Builds one declared arm; the template comes from the arm's row (never a copy). */
    private static FailureArm arm(FailureArmId id, FailurePolicyId policy, int templateIndex,
                                  FailureArm.ExpectedSource expected, String pinnedExpected,
                                  FailureArm.ActualProjection actual,
                                  FailureArm.OriginConvention origin,
                                  FailureArm.RenderScope scope, Object... parameterSources) {
        return armWithCode(id, policy, templateIndex, null, expected, pinnedExpected, actual,
            origin, scope, parameterSources);
    }

    /**
     * Builds one declared arm that pins its own DEAL-visible code beside its
     * row's ({@code BYTES_WRITE}'s value-range arm is the one multi-code arm).
     */
    private static FailureArm armWithCode(FailureArmId id, FailurePolicyId policy,
                                          int templateIndex, DiagnosticCode code,
                                          FailureArm.ExpectedSource expected,
                                          String pinnedExpected,
                                          FailureArm.ActualProjection actual,
                                          FailureArm.OriginConvention origin,
                                          FailureArm.RenderScope scope,
                                          Object... parameterSources) {
        FailurePolicyRow row = row(policy);
        if (templateIndex >= row.templates().size()) {
            throw new IllegalStateException("arm " + id + " names template index "
                + templateIndex + " outside the row " + policy + "'s template list");
        }
        String template = row.templates().get(templateIndex);
        List<String> parameters = placeholdersOf(template);
        Map<String, FailureArm.ParameterSource> sources = new LinkedHashMap<>();
        for (int i = 0; i + 1 < parameterSources.length; i += 2) {
            sources.put((String) parameterSources[i],
                (FailureArm.ParameterSource) parameterSources[i + 1]);
        }
        for (String parameter : parameters) {
            if (!sources.containsKey(parameter)) {
                throw new IllegalStateException("arm " + id + " names no source for its "
                    + "parameter {" + parameter + "}");
            }
        }
        return new FailureArm(id, policy, templateIndex, template, parameters, sources,
            expected, pinnedExpected, actual, origin, scope, code);
    }

    /** The {@code {name}} placeholders of a template, in order of first appearance. */
    private static List<String> placeholdersOf(String template) {
        List<String> parameters = new ArrayList<>();
        int i = 0;
        while (i < template.length()) {
            char c = template.charAt(i);
            if (c == '{') {
                int close = template.indexOf('}', i + 1);
                if (close < 0) {
                    throw new IllegalStateException(
                        "a failure template carries an unclosed placeholder: " + template);
                }
                String name = template.substring(i + 1, close);
                if (!parameters.contains(name)) {
                    parameters.add(name);
                }
                i = close + 1;
            } else {
                i++;
            }
        }
        return parameters;
    }

    /**
     * The fail-closed row/arm consistency invariant (P1; Verification 2):
     * every declared arm's template is its row's template at the arm's
     * index, each row's template list equals its declared arms' templates
     * in arm order (every retained template is bound to exactly one arm),
     * and a policy with no declared arm keeps an empty template list. An
     * unbound retained template, a foreign template, a duplicate binding,
     * or a missing/extra arm fails this check as a producer defect.
     *
     */
    public static void checkArmConsistency(Map<FailurePolicyId, FailurePolicyRow> rows,
                                           List<FailureArm> arms) {
        Objects.requireNonNull(rows, "rows must not be null");
        Objects.requireNonNull(arms, "arms must not be null");
        Map<FailurePolicyId, List<FailureArm>> byRow = new EnumMap<>(FailurePolicyId.class);
        java.util.Set<FailureArmId> seen = EnumSet.noneOf(FailureArmId.class);
        for (FailureArm arm : arms) {
            Objects.requireNonNull(arm, "an arm must not be null");
            if (!seen.add(arm.id())) {
                throw new IllegalStateException("duplicate arm binding for " + arm.id());
            }
            if (arm.hasSiblingOwnedBindingSlot()) {
                throw new IllegalStateException("arm " + arm.id() + " carries a "
                    + "sibling-owned marker in its projection binding, its origin "
                    + "convention, or its parameter sources; no declared arm may "
                    + "carry one (jsonable-tojson-walk-arm-binding W5)");
            }
            FailurePolicyRow row = rows.get(arm.policy());
            if (row == null) {
                throw new IllegalStateException("arm " + arm.id() + " names the policy "
                    + arm.policy() + ", which has no row");
            }
            byRow.computeIfAbsent(arm.policy(), ignored -> new ArrayList<>()).add(arm);
        }
        for (Map.Entry<FailurePolicyId, FailurePolicyRow> entry : rows.entrySet()) {
            FailurePolicyRow row = entry.getValue();
            List<FailureArm> declared = byRow.getOrDefault(entry.getKey(), List.of());
            List<String> bound = new ArrayList<>();
            for (int i = 0; i < declared.size(); i++) {
                FailureArm arm = declared.get(i);
                if (arm.templateIndex() != i) {
                    throw new IllegalStateException("arm " + arm.id() + " of policy "
                        + entry.getKey() + " binds template index " + arm.templateIndex()
                        + " out of arm order (expected " + i + ")");
                }
                if (i >= row.templates().size()
                        || !row.templates().get(i).equals(arm.template())) {
                    throw new IllegalStateException("arm " + arm.id() + " of policy "
                        + entry.getKey() + " carries the foreign template \""
                        + arm.template() + "\", which is not its row's template " + i);
                }
                bound.add(arm.template());
            }
            if (!bound.equals(row.templates())) {
                throw new IllegalStateException("policy " + entry.getKey() + " has the "
                    + "retained templates " + row.templates() + " but its declared arms "
                    + "bind " + bound + " (an unbound retained template or a missing arm)");
            }
        }
    }

    // =========================================================================
    // Arm access and rendering
    // =========================================================================

    /** The closed arm table (unmodifiable; iteration order is arm declaration order). */
    public static Map<FailureArmId, FailureArm> arms() {
        return ARMS;
    }

    /**
     * The DEAL-visible code text of one arm: the arm's own pinned code when it
     * carries one (the multi-code {@code BYTES_WRITE} row's value-range arm),
     * else its row's code; the empty text when the row pins no code.
     */
    public static String codeOf(FailureArm arm) {
        Objects.requireNonNull(arm, "arm must not be null");
        DiagnosticCode code = codeOfArm(arm);
        return code == null ? "" : code.name();
    }

    /** The arm's resolved DEAL-visible code (its own, else its row's). */
    private static DiagnosticCode codeOfArm(FailureArm arm) {
        return arm.code() != null ? arm.code() : row(arm.policy()).code();
    }

    /**
     * The canonical serialization of the closed arm table, one arm per
     * line in arm declaration order:
     * {@code id|code|template|expectedSource|actualProjection|scope|origin|parameterSources},
     * with the arm's own pinned expected text appended for an arm whose
     * expected source is {@link FailureArm.ExpectedSource#PINNED_TEXT}. The
     * parameter sources are the arm's parameters in order, each as
     * {@code name=SOURCE}. The emitted Lua prelude serializes exactly this
     * table — the emitter-side table is compared against this text, so no
     * fork of the authority can exist on the emission side.
     */
    public static List<String> canonicalArmSerialization() {
        List<String> lines = new ArrayList<>();
        for (FailureArm arm : ARMS.values()) {
            String line = arm.id() + "|" + codeOf(arm) + "|" + arm.template() + "|"
                + arm.expectedSource() + "|" + arm.actualProjection() + "|"
                + arm.scope() + "|" + arm.origin() + "|" + parameterSourcesText(arm);
            if (arm.pinnedExpectedText() != null) {
                line = line + "|" + arm.pinnedExpectedText();
            }
            lines.add(line);
        }
        return List.copyOf(lines);
    }

    /** One arm's parameter sources in parameter order ({@code name=SOURCE}, comma-joined). */
    public static String parameterSourcesText(FailureArm arm) {
        Objects.requireNonNull(arm, "arm must not be null");
        StringBuilder text = new StringBuilder();
        for (String parameter : arm.parameters()) {
            if (text.length() > 0) {
                text.append(',');
            }
            text.append(parameter).append('=')
                .append(arm.parameterSources().get(parameter).name());
        }
        return text.toString();
    }

    /**
     * The single declared arm of one closed arm id; an unknown id cannot be
     * expressed at the type level and a missing entry fails closed.
     *
     */
    public static FailureArm arm(FailureArmId id) {
        Objects.requireNonNull(id, "id must not be null");
        FailureArm arm = ARMS.get(id);
        if (arm == null) {
            throw new IllegalArgumentException("no failure arm for arm id " + id);
        }
        return arm;
    }

    /**
     * The arm bound to one retained template position of a row — the
     * template/arm binding of the consistency invariant. A retained
     * template with no bound arm fails closed.
     *
     */
    public static FailureArm armForTemplate(FailurePolicyId policy, int templateIndex) {
        for (FailureArm arm : ARMS.values()) {
            if (arm.policy() == policy && arm.templateIndex() == templateIndex) {
                return arm;
            }
        }
        throw new BoundaryExecutor.Defect("no declared arm is bound to template "
            + templateIndex + " of policy " + policy + " (an unbound retained template is "
            + "a fail-closed producer defect)");
    }

    /**
     * Renders one top-level arm: the arm's own template instantiated with
     * its named parameters, plus the arm's declared {@code expected} and
     * {@code actual} fields. The arm's fields are exactly its declaration:
     * a missing value for a declared field, a value for an undeclared
     * field, a missing or extra named parameter, a render of an
     * {@code INNER_ONLY} arm, or the retained sibling-owned guard of a
     * marked arm fails closed as a producer defect — never a fallback text,
     * never a composed suffix.
     *
     */
    public static BoundaryFailure render(FailureArmId id, Map<String, String> parameters,
                                         String expected, String actual,
                                         BoundaryFailure cause) {
        FailureArm arm = arm(id);
        if (arm.isInnerOnly()) {
            throw new BoundaryExecutor.Defect("arm " + id + " is INNER_ONLY: it renders "
                + "only into another arm's inner reason and publishes no tuple");
        }
        if (arm.isSiblingOwned()) {
            throw new BoundaryExecutor.Defect("arm " + id + " has a SIBLING_OWNED "
                + "projection binding and no production consumer may render it");
        }
        checkParameters(arm, parameters);
        checkArmFields(arm, parameters, expected, actual);
        String message = instantiateArm(arm, parameters);
        return new BoundaryFailure(arm.policy(), codeOfArm(arm), message, expected,
            actual, metadataOf(arm, parameters), cause);
    }

    /**
     * One arm render addressed by its retained template position — the
     * legacy {@code (policy, templateIndex, fields)} entry point. The bound
     * arm's own template, its own code, and its declared field contract are
     * the render: a caller cannot instantiate a retained row template behind
     * its arm's back, so the arm's declared expected/actual shapes, its
     * parameters, and its render scope can never be bypassed in production.
     * The supplied metadata map is the render's metadata verbatim (the walk's
     * internal position keys included) — it is never re-derived here.
     *
     * <p>No declared arm carries a sibling-owned binding slot, so this entry
     * point resolves the bound arm's own declaration for every retained
     * template; the marker's render-time guards stay as the closed backstop
     * for a hand-built marked arm ({@link #render}, {@link #checkActualShape},
     * {@code FailureProjections.actualFor}).</p>
     *
     * @param policy        the row's policy; must not be null
     * @param templateIndex the retained template index; must be in range
     * @param expected      the expected field, or {@code null} exactly when
     *                      the arm declares none
     * @param actual        the actual field, or {@code null} exactly when the
     *                      arm declares none
     * @param metadata      the render's metadata; may be empty, never null
     * @param cause         the leaf failure where the row pins one, else null
     * @return the rendered boundary failure
     * @throws BoundaryExecutor.Defect if the render is not the arm's own
     */
    public static BoundaryFailure renderAtTemplate(FailurePolicyId policy, int templateIndex,
                                                   String expected, String actual,
                                                   Map<String, String> metadata,
                                                   BoundaryFailure cause) {
        Objects.requireNonNull(policy, "policy must not be null");
        FailureArm arm = armForTemplate(policy, templateIndex);
        Map<String, String> supplied = metadata == null
            ? new LinkedHashMap<>() : metadata;
        Map<String, String> parameters = new LinkedHashMap<>();
        for (String parameter : arm.parameters()) {
            // The parameter value comes from the declared source: an
            // expected-token source is the expected field, a carried/actual
            // projection is the actual field, and every other source is the
            // caller's metadata. A source the row-position surface cannot
            // supply (the kind text) stays absent and fails the parameter
            // check closed, so the entry point never guesses a text.
            FailureArm.ParameterSource source = arm.parameterSources().get(parameter);
            String value = switch (source) {
                case CELL_DESCRIPTOR, ELEMENT_DESCRIPTOR, DECLARED_SIGNATURE,
                        CLASS_ATOM -> expected;
                case CARRIED_SIGNATURE, CARRIED_CLASS_ATOM, TYPED_BOUNDARY_ACTUAL,
                        CARRIER_KIND_ACTUAL, SIBLING_OWNED_ACTUAL -> actual;
                case KIND_TEXT -> null;
                default -> supplied.get(parameter);
            };
            if (value != null) {
                parameters.put(parameter, value);
            }
        }
        checkParameters(arm, parameters);
        checkArmFields(arm, parameters, expected, actual);
        return new BoundaryFailure(policy, codeOfArm(arm),
            instantiateArm(arm, parameters), expected, actual, supplied, cause);
    }

    // =========================================================================
    // The declared field shapes (P2): a token outside its arm's closed shape
    // is a producer defect, never a fabricated DEAL-visible field
    // =========================================================================

    /** The closed typed-boundary kind tokens (P2 item 1). */
    private static final Set<String> KIND_TOKENS = Set.of("null", "boolean", "int",
        "number", "string", "table", "bytes", "array", "function", "class");

    /**
     * The closed typed-boundary kind <em>texts</em> (P2 item 3): the
     * {@code {kind}} parameter of a {@code KIND_TOKEN} arm is one of these
     * — a class projects {@code class instance} while its token is
     * {@code class} — and a foreign text never derives a DEAL-visible
     * token.
     */
    private static final Set<String> KIND_TEXTS = Set.of("null", "boolean", "int",
        "number", "string", "table", "bytes", "array", "function",
        "class instance");

    /** The closed typed-boundary actual tokens (P2 item 1). */
    private static final Set<String> TYPED_BOUNDARY_TOKENS = Set.of("nil", "null",
        "boolean", "int", "number", "string", "invalid-unicode", "table", "array",
        "bytes", "function", "async-operation", "nothing", "NaN", "infinity");

    /**
     * The closed completion-variant tokens (P2 item 4): the typed-boundary
     * vocabulary with every numeric carrier projecting the single
     * {@code number} kind, so no bare {@code int} token exists.
     */
    private static final Set<String> COMPLETION_TOKENS = Set.of("nil", "null", "boolean",
        "number", "string", "invalid-unicode", "table", "array", "bytes",
        "function", "async-operation", "nothing", "NaN", "infinity");

    /** The closed carrier-kind tokens (P2 item 2). */
    private static final Set<String> CARRIER_KIND_TOKENS = Set.of("nil", "table",
        "boolean", "number", "string", "bytes", "function");

    /**
     * Enforces the arm's declared expected/actual field contract: presence
     * exactly when the arm declares the field, and the closed shape of the
     * arm's declared token source or projection. A caller-supplied token the
     * arm's declaration does not produce is a producer defect — the renderer
     * never publishes a fabricated field.
     */
    private static void checkArmFields(FailureArm arm, Map<String, String> parameters,
                                       String expected, String actual) {
        if (arm.declaresExpected() ? expected == null : expected != null) {
            throw new BoundaryExecutor.Defect("arm " + arm.id() + " "
                + (arm.declaresExpected() ? "declares" : "does not declare")
                + " an expected field; got " + expected);
        }
        if (arm.declaresActual() ? actual == null : actual != null) {
            throw new BoundaryExecutor.Defect("arm " + arm.id() + " "
                + (arm.declaresActual() ? "declares" : "does not declare")
                + " an actual field; got " + actual);
        }
        if (expected != null) {
            checkExpectedShape(arm, parameters, expected);
        }
        if (actual != null) {
            checkActualShape(arm, actual);
        }
    }

    /** The expected field's declared shape. */
    private static void checkExpectedShape(FailureArm arm, Map<String, String> parameters,
                                           String expected) {
        switch (arm.expectedSource()) {
            case NONE -> { /* the presence check already rejected a value */ }
            case PINNED_TEXT -> {
                if (!arm.pinnedExpectedText().equals(expected)) {
                    throw shapeDefect(arm, "expected", expected, "its pinned text \""
                        + arm.pinnedExpectedText() + "\"");
                }
            }
            case KIND_TOKEN -> {
                String kindText = parameterWithSource(arm, parameters,
                    FailureArm.ParameterSource.KIND_TEXT);
                if (kindText == null) {
                    if (!KIND_TOKENS.contains(expected)) {
                        throw shapeDefect(arm, "expected", expected,
                            "a closed typed-boundary kind token (one of " + KIND_TOKENS + ")");
                    }
                } else {
                    // The kind text itself belongs to the closed vocabulary:
                    // an invented kind text never derives a DEAL-visible
                    // token through the authority.
                    if (!KIND_TEXTS.contains(kindText)) {
                        throw shapeDefect(arm, "kind", kindText,
                            "a closed typed-boundary kind text (one of " + KIND_TEXTS + ")");
                    }
                    if (!kindTokenOfText(kindText).equals(expected)) {
                        throw shapeDefect(arm, "expected", expected,
                            "the kind token of its kind text \"" + kindText + "\"");
                    }
                }
            }
            case CLASS_ATOM -> requireClassAtom(arm, "expected", expected);
            case CELL_DESCRIPTOR, ELEMENT_DESCRIPTOR ->
                requireDescriptorText(arm, "expected", expected, false);
            case SIGNATURE -> requireDescriptorText(arm, "expected", expected, true);
            case ASYNC_OPERATION_TOKEN -> {
                if (!"async operation".equals(expected)) {
                    throw shapeDefect(arm, "expected", expected, "\"async operation\"");
                }
            }
        }
    }

    /** The actual field's declared projection shape. */
    private static void checkActualShape(FailureArm arm, String actual) {
        switch (arm.actualProjection()) {
            case NONE -> { /* the presence check already rejected a value */ }
            case TYPED_BOUNDARY -> requireTypedBoundaryToken(arm, actual, TYPED_BOUNDARY_TOKENS);
            case COMPLETION -> requireTypedBoundaryToken(arm, actual, COMPLETION_TOKENS);
            case CARRIER_KIND -> {
                if (!CARRIER_KIND_TOKENS.contains(actual)) {
                    throw shapeDefect(arm, "actual", actual,
                        "a closed carrier-kind token (one of " + CARRIER_KIND_TOKENS + ")");
                }
            }
            case NOTHING_TOKEN -> {
                if (!"nothing".equals(actual)) {
                    throw shapeDefect(arm, "actual", actual, "\"nothing\"");
                }
            }
            case CARRIED_CLASS_ATOM -> requireClassAtom(arm, "actual", actual);
            case CARRIED_SIGNATURE -> requireDescriptorText(arm, "actual", actual, true);
            case CANONICAL_VALUE_TEXT -> {
                try {
                    Double.parseDouble(actual);
                } catch (NumberFormatException notANumber) {
                    throw shapeDefect(arm, "actual", actual,
                        "the canonical floating-point text of the failing value");
                }
            }
            case SIBLING_OWNED -> throw new BoundaryExecutor.Defect("arm " + arm.id()
                + " has a SIBLING_OWNED projection binding and no production consumer "
                + "may render it");
        }
    }

    /** One (kind text, kind token) pair: a class kind's text is {@code class instance}. */
    private static String kindTokenOfText(String kindText) {
        return "class instance".equals(kindText) ? "class" : kindText;
    }

    /**
     * The closed typed-boundary token check (the carried canonical class
     * atom included). The superseded composed {@code class:<ClassId>}
     * spelling is never a typed-boundary token (jsonable-tojson-walk-arm-
     * binding W1): only the closed vocabulary and a carried canonical class
     * atom are admissible, so a caller that composes the removed spelling
     * fails closed.
     */
    private static void requireTypedBoundaryToken(FailureArm arm, String token,
                                                  Set<String> closed) {
        if (closed.contains(token) || isClassAtomText(token)) {
            return;
        }
        throw shapeDefect(arm, "actual", token, "a closed typed-boundary token");
    }

    /** The declared or carried canonical class atom ({@code @modulePath/Name}). */
    private static void requireClassAtom(FailureArm arm, String field, String value) {
        if (!isClassAtomText(value)) {
            throw shapeDefect(arm, field, value, "a canonical class atom (\"@modulePath/Class\")");
        }
    }

    private static boolean isClassAtomText(String value) {
        if (!value.startsWith("@")) {
            return false;
        }
        try {
            return RuntimeDescriptor.parseCanonicalText(value)
                instanceof RuntimeDescriptor.Class;
        } catch (RuntimeException decodeFailure) {
            return false;
        }
    }

    private static void requireDescriptorText(FailureArm arm, String field, String value,
                                              boolean signatureMayBeEmpty) {
        if (value.isEmpty()) {
            if (signatureMayBeEmpty) {
                return;
            }
            throw shapeDefect(arm, field, value, "a canonical descriptor text");
        }
        try {
            RuntimeDescriptor.parseCanonicalText(value);
            return;
        } catch (RuntimeException decodeFailure) {

        }
        if (!isDialectDescriptorText(value)) {
            throw shapeDefect(arm, field, value, "a canonical descriptor text");
        }
    }

    /** The emitters' internal descriptor dialect ({@code array(…)} etc.). */
    private static boolean isDialectDescriptorText(String text) {
        if (KIND_TOKENS.contains(text) && !"class".equals(text)) {
            return true;
        }
        if (text.startsWith("?")) {
            return isDialectDescriptorText(text.substring(1));
        }
        if (text.startsWith("[") && text.endsWith("]") && text.length() > 2) {
            return isDialectDescriptorText(text.substring(1, text.length() - 1));
        }
        if (text.startsWith("array(") && text.endsWith(")") && text.length() > 7) {
            return isDialectDescriptorText(text.substring(6, text.length() - 1));
        }
        if (text.startsWith("nullable(") && text.endsWith(")") && text.length() > 10) {
            return isDialectDescriptorText(text.substring(9, text.length() - 1));
        }
        if (text.startsWith("function(") && text.endsWith(")") && text.length() > 10) {
            String inner = text.substring(9, text.length() - 1);
            int separator = inner.lastIndexOf(';');
            return separator > 0 && separator + 1 < inner.length()
                && isDialectDescriptorText(inner.substring(separator + 1));
        }
        return false;
    }

    /** The parameter whose declared source is {@code source}, or {@code null}. */
    private static String parameterWithSource(FailureArm arm, Map<String, String> parameters,
                                              FailureArm.ParameterSource source) {
        for (Map.Entry<String, FailureArm.ParameterSource> entry
                : arm.parameterSources().entrySet()) {
            if (entry.getValue() == source) {
                return parameters.get(entry.getKey());
            }
        }
        return null;
    }

    private static BoundaryExecutor.Defect shapeDefect(FailureArm arm, String field,
                                                       String value, String declared) {
        return new BoundaryExecutor.Defect("arm " + arm.id() + " declares its " + field
            + " as " + declared + "; the render supplied \"" + value + "\" (a token "
            + "outside the arm's declared shape is a producer defect)");
    }

    /**
     * Renders one {@code INNER_ONLY} arm's message text — the text a
     * consuming arm substitutes for its {@code {inner}} parameter.
     * Rendering a top-level arm here fails closed.
     *
     */
    public static String renderInner(FailureArmId id, Map<String, String> parameters) {
        FailureArm arm = arm(id);
        if (!arm.isInnerOnly()) {
            throw new BoundaryExecutor.Defect("arm " + id + " is not INNER_ONLY: an "
                + "inner reason render at a boundary/operation site is a producer defect");
        }
        checkParameters(arm, parameters);
        return instantiateArm(arm, parameters);
    }

    /**
     * The descriptor-kind inner reason of one descriptor <em>text</em>
     * (P2 item 3): the typed-boundary kind arm's own text with the text's
     * closed kind. The text must be a closed descriptor spelling — the
     * canonical grammar decoded by the one decoder, or the emitters'
     * internal dialect ({@code array(…)}/… ) — and its kind must be one of
     * the closed typed-boundary kind texts: an unknown descriptor is a
     * fail-closed producer defect, so a host inner reason is never derived
     * from an unvalidated string.
     *
     */
    public static String descriptorKindReason(String descriptorText) {
        Objects.requireNonNull(descriptorText, "descriptorText must not be null");
        String kindText;
        try {
            kindText = FailureProjections.kindText(
                RuntimeDescriptor.parseCanonicalText(descriptorText));
        } catch (RuntimeException canonicalDecodeFailure) {
            if (!isDialectDescriptorText(descriptorText)) {
                throw new BoundaryExecutor.Defect("the host inner-reason render supplied "
                    + "\"" + descriptorText + "\", which is not a closed descriptor "
                    + "text (a producer defect; no inner reason is composed from it)");
            }
            kindText = dialectKindText(descriptorText);
        }
        if (!KIND_TEXTS.contains(kindText)) {
            throw new BoundaryExecutor.Defect("the host inner-reason render supplied "
                + "\"" + descriptorText + "\", whose kind \"" + kindText
                + "\" is outside the closed typed-boundary kind texts " + KIND_TEXTS
                + " (a producer defect)");
        }
        return arm(FailureArmId.TYPED_BOUNDARY_KIND).template()
            .replace("{kind}", kindText);
    }

    /** The closed kind text of one emitters'-dialect descriptor text. */
    private static String dialectKindText(String text) {
        if (text.startsWith("?")) {
            return dialectKindText(text.substring(1));
        }
        if (text.startsWith("nullable(")) {
            return dialectKindText(text.substring(9, text.length() - 1));
        }
        if (text.startsWith("[") || text.startsWith("array(")) {
            return "array";
        }
        if (text.startsWith("(") || text.startsWith("async(")
                || text.startsWith("function(")) {
            return "function";
        }
        return text;
    }

    /**
     * The failure's metadata map: the arm's pinned metadata keys (its row's
     * {@code metadataKeys}) that the render supplied — the field texts
     * ({@code expected}, {@code actual}) stay the failure's own fields and
     * never appear here.
     */
    private static Map<String, String> metadataOf(FailureArm arm,
                                                  Map<String, String> parameters) {
        Map<String, String> metadata = new LinkedHashMap<>();
        for (String key : row(arm.policy()).metadataKeys()) {
            if ("expected".equals(key) || "actual".equals(key)) {
                continue;
            }
            String value = parameters.get(key);
            if (value != null) {
                metadata.put(key, value);
            }
        }
        return metadata;
    }

    private static void checkParameters(FailureArm arm, Map<String, String> parameters) {
        Objects.requireNonNull(parameters, "parameters must not be null");
        if (!parameters.keySet().equals(new java.util.LinkedHashSet<>(arm.parameters()))) {
            throw new BoundaryExecutor.Defect("arm " + arm.id() + " declares the parameters "
                + arm.parameters() + " but the render supplied " + parameters.keySet());
        }
    }

    /** Instantiates the arm's own template; an unbound placeholder is a defect. */
    private static String instantiateArm(FailureArm arm, Map<String, String> parameters) {
        String message = arm.template();
        for (String parameter : arm.parameters()) {
            String value = parameters.get(parameter);
            if (value == null) {
                throw new BoundaryExecutor.Defect("arm " + arm.id() + " has no value for its "
                    + "parameter {" + parameter + "}");
            }
            message = message.replace("{" + parameter + "}", value);
        }
        if (message.indexOf('{') >= 0 || message.indexOf('}') >= 0) {
            throw new BoundaryExecutor.Defect("an uninstantiated placeholder remains in "
                + "arm " + arm.id() + "'s template: \"" + message + "\"");
        }
        return message;
    }

    // =========================================================================
    // E6005 coverage items (parent D11): one named producer per item
    // =========================================================================

    private static final Map<String, String> E6005_COVERAGE_ITEMS = buildCoverageItems();

    private static Map<String, String> buildCoverageItems() {
        Map<String, String> items = new LinkedHashMap<>();
        items.put("missing checked facts", "CheckedProjectBuilder");
        items.put("invalid semantic IR",
            "SemanticIrValidator (the closed 14-condition rule set)");
        items.put("unknown or reserved closed selector/policy",
            "SemanticIrValidator R-ENUM / R-RESERVED-NAME");
        items.put("an operation outside a claimed capability",
            "the construct epics at unit-production time (ISSUE-0231..0239) against the S4 "
                + "capability catalog");
        items.put("missing boundary realization",
            "the construct epics' boundary production (ISSUE-0233..0236) per parent D7");
        items.put("ABI mismatch after compatibility was claimed",
            "TargetAbiValidator at stage time");
        return Collections.unmodifiableMap(items);
    }

    /**
     * The parent-D11 E6005 coverage items in order, each mapped to its
     * single named producing component (unmodifiable).
     */
    public static Map<String, String> e6005CoverageItems() {
        return E6005_COVERAGE_ITEMS;
    }

    // =========================================================================
    // E6005 construction (registry-owned; no consumer hand-crafts the message)
    // =========================================================================

    /**
     * Instantiates the canonical E6005 message from the detail record.
     * The registry owns this construction: the message is the instantiated
     * E6005 template carrying every {@link LoweringFailureDetail} field,
     * deterministically, with no other content.
     *
     */
    public static String instantiateMessage(LoweringFailureDetail detail) {
        Objects.requireNonNull(detail, "detail must not be null");
        Objects.requireNonNull(detail.module(), "detail.module must not be null");
        Objects.requireNonNull(detail.capability(), "detail.capability must not be null");
        Objects.requireNonNull(detail.validatorRule(), "detail.validatorRule must not be null");
        Objects.requireNonNull(detail.semanticProfile(),
            "detail.semanticProfile must not be null");
        Objects.requireNonNull(detail.irVersion(), "detail.irVersion must not be null");
        Objects.requireNonNull(detail.origin(), "detail.origin must not be null");
        return "Common semantic lowering failed: module '" + detail.module()
            + "', capability " + detail.capability()
            + ", validatorRule " + detail.validatorRule()
            + ", semanticProfile " + detail.semanticProfile()
            + ", irVersion " + detail.irVersion()
            + ", origin " + detail.origin();
    }

    /**
     * Builds the E6005 diagnostic for a submitted detail record — the only
     * sanctioned E6005 construction surface. The diagnostic carries code
     * {@code E6005} ({@link DiagnosticCode.Phase#BACKEND_LOWERING}),
     * error severity, the instantiated message carrying the detail, and
     * the canonical synthetic range (the detail carries a producer name,
     * not a source span).
     *
     */
    public static CompilerDiagnostic e6005(LoweringFailureDetail detail) {
        return CompilerDiagnostic.error(DiagnosticCode.E6005, instantiateMessage(detail),
            DiagnosticRange.synthetic(""));
    }
}
