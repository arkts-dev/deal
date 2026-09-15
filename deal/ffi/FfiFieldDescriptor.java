package deal.ffi;

import java.util.Objects;

/**
 * One immutable C FFI struct-field descriptor row (design source
 * {@code deal-v1.2-directives-and-c-ffi-declarations} D4):
 * {@code FfiFieldDescriptor(dealName, fieldOrdinal, type: FfiType)}.
 *
 * <ul>
 *   <li>{@code dealName} — the DEAL field name exactly as declared
 *       (DEAL names never enter a cdef: generated structs expose the
 *       ordinal members {@code deal_fN} only).</li>
 *   <li>{@code fieldOrdinal} — the 0-based source-order ordinal; the
 *       generated C member name is {@code "deal_f" + fieldOrdinal}.</li>
 *   <li>{@code type} — the field's validated ABI type row.</li>
 * </ul>
 */
public record FfiFieldDescriptor(String dealName, int fieldOrdinal,
                                 FfiType type) {

    public FfiFieldDescriptor {
        Objects.requireNonNull(dealName, "dealName");
        Objects.requireNonNull(type, "type");
        if (dealName.isEmpty()) {
            throw new IllegalArgumentException("dealName must not be empty");
        }
        if (fieldOrdinal < 0) {
            throw new IllegalArgumentException(
                "fieldOrdinal must be non-negative, got " + fieldOrdinal);
        }
    }
}
