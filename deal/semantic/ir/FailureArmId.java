package deal.semantic.ir;

/**
 * The closed DEAL-visible failure-arm set of {@code deal.semantic-ir/1}
 * (canonical failure-projection authority P1).
 *
 * <p>Every failure arm is one member; no open or unknown fallback member
 * exists. {@link FailureContractRegistry} maps every member to exactly one
 * {@link FailureArm} beside its {@link FailurePolicyRow}, and a row's
 * template list equals its declared arms' templates in arm order. An arm
 * is the projection authority: its complete message template with its
 * named parameters, its expected-token source or absence, its actual
 * projection or absence, its origin convention, and its render scope.</p>
 *
 * <p>A policy with no DEAL-visible arm ({@code NO_DEAL_FAILURE},
 * {@code JSON_FROM_NULL}, {@code THROW_TRANSFER},
 * {@code INFRASTRUCTURE_ONLY}) declares no arm and keeps an empty template
 * list. The arm set grows only by the slice that makes an arm reachable —
 * never as a text without its arm.</p>
 */
public enum FailureArmId {

    // TYPE_DESCRIPTOR — the typed-boundary arms (P1/P2)
    TYPED_BOUNDARY_KIND,
    TYPED_BOUNDARY_INVALID_UNICODE,
    CLASS_IDENTITY,
    HOST_STRING_INVALID_UTF8,
    HOST_STRING_SURROGATE,

    // INT32_RESULT / INT32_DIVISOR_THEN_RESULT / INT32_EXPONENT_THEN_RESULT
    INT32_RANGE,
    INT32_DIVISION_BY_ZERO,
    INT32_NEGATIVE_EXPONENT,

    // INT_CONVERSION / NUMBER_CONVERSION
    INT_CONVERSION_NULL,
    INT_CONVERSION_NAN,
    INT_CONVERSION_INFINITY,
    INT_CONVERSION_FRACTIONAL,
    NUMBER_CONVERSION_NULL,

    // array cells
    ARRAY_ELEMENT_KIND,
    ARRAY_READ_NEGATIVE_INDEX,
    ARRAY_WRITE_BOUNDS,
    ARRAY_DELETE_BOUNDS,

    BYTES_ALLOCATE,
    BYTES_READ,
    BYTES_WRITE_BOUNDS,
    BYTES_WRITE_RANGE,

    // FUNCTION_SIGNATURE
    FUNCTION_SIGNATURE_MISMATCH,

    // host cells
    HOST_PARAMETER_CELL,
    HOST_SYNC_RETURN_NOTHING,
    HOST_SYNC_RETURN_CELL,
    ASYNC_COMPLETION_KIND,
    ASYNC_COMPLETION_REFINEMENT,
    ASYNC_SHAPE,

    HOST_LOAD_FAILED,
    HOST_LOAD_NOT_A_MODULE,
    HOST_LOAD_MISSING_EXPORT,
    HOST_LOAD_SIGNATURE_MISMATCH,
    HOST_LOAD_IDENTITY_MISMATCH,
    HOST_LOAD_INVALID_METADATA,

    // CLASS_CONSTRUCTION / JSON_PARSE_SYNTAX / JSON_TO_ERROR / SQRT_NEGATIVE
    CLASS_EXTRA_FIELD,
    JSON_PARSE_ERROR,
    JSON_TO_WALK,
    JSON_STRINGIFY_UNSUPPORTED,
    JSON_TO_WALK_CYCLE,
    SQRT_NEGATIVE;
}
