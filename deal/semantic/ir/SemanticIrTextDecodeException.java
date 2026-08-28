package deal.semantic.ir;

/**
 * The transport-level text-decode failure of the canonical JSON parser
 * (schema S2; conformance D1): raised when versioned
 * {@code deal.semantic-ir/1} text violates the pinned framing — malformed
 * JSON syntax, invalid UTF-8, a non-canonical number form, a duplicate
 * object key, an unpaired surrogate escape, trailing content after the
 * top-level value, or a pinned field/value-type/version mismatch of the
 * snapshot record shape.
 *
 * <p>This exception is deliberately <em>not</em> a diagnostic and not a
 * DEAL result: it is never E6005, never an E8 code, and never one of the
 * closed 14 validator rules — decode failures are the infrastructure-kind
 * treatment the conformance taxonomy reserves for fixture/harness
 * failures, while the closed validator rule set remains the only E6005
 * rule producer. The parser performs no closed-enum, reserved-name,
 * policy, boundary-assignment, or profile validation: out-of-set and
 * reserved names survive the parse and reach the validator's rule checks
 * as raw strings (S2).</p>
 */
public class SemanticIrTextDecodeException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the decode failure with the transport-level reason.
     *
     * @param message the decode failure reason; non-null
     */
    public SemanticIrTextDecodeException(String message) {
        super(message);
    }
}
