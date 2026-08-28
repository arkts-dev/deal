package deal.descriptors;

import java.util.Objects;

/**
 * A strict canonical-descriptor parse failure.
 *
 * <p>{@code scalarOffset} is the 0-based offset of the offending scalar,
 * counted in decoded Unicode scalar values (code points), never UTF-16
 * code units.  {@code kind} is one of the pinned kinds below.
 * {@code message} is a deterministic, human-readable description.</p>
 *
 * <p>Parsing never throws an exception and never yields a partial AST;
 * every failure is a {@code DescriptorSyntaxError} value.</p>
 */
public record DescriptorSyntaxError(int scalarOffset, Kind kind, String message)
        implements DescriptorParseResult {

    /** The pinned syntax-error kinds of the canonical descriptor grammar. */
    public enum Kind {
        /** A scalar that cannot start or continue a valid descriptor at its position. */
        UNEXPECTED_CHARACTER,
        /** Input ended while a descriptor was still required. */
        UNEXPECTED_END,
        /** A complete descriptor was parsed but scalars remain unconsumed. */
        TRAILING_CONTENT,
        /** A bare class name without the leading {@code @} (legacy spelling). */
        BARE_CLASS_NAME,
        /** A nullable whose inner descriptor is itself a nullable. */
        NESTED_NULLABLE,
        /** A nullable whose inner descriptor is the {@code null} primitive. */
        NULL_INNER,
        /**
         * A class atom that violates the pinned class-atom shape: empty
         * components, a lone {@code @}, a trailing slash, {@code .}/{@code ..}
         * whole components, fewer than two components, contiguous {@code ->}
         * inside a component, or a final component that is not
         * identifier-shaped (including any dotted class-name text such as
         * {@code @src.models.User}).
         */
        INVALID_CLASS_ATOM
    }

    public DescriptorSyntaxError {
        if (scalarOffset < 0) {
            throw new IllegalArgumentException("scalarOffset must not be negative: " + scalarOffset);
        }
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(message, "message must not be null");
    }
}
