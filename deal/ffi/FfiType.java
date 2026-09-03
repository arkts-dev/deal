package deal.ffi;

import java.util.Objects;

/**
 * One immutable C FFI type row of the settled metadata seam
 * (design source {@code deal-v1.2-directives-and-c-ffi-declarations}
 * D4, {@code luajit-ffi-generated-content-seam} S4):
 * {@code FfiType(kind, canonicalDescriptor, canonicalClassIdentity?)}.
 *
 * <ul>
 *   <li>{@code kind} — one of the nine pinned kinds
 *       {@code INT | NUMBER | BOOLEAN | STRING | BYTES | NULL |
 *       C_STRUCT | C_POINTER}.</li>
 *   <li>{@code canonicalDescriptor} — the byte-exact canonical runtime
 *       descriptor produced by the compilation's single Type&rarr;text
 *       authority ({@link deal.descriptors.CanonicalRuntimeTypeDescriptor}),
 *       never a legacy spelling.</li>
 *   <li>{@code canonicalClassIdentity} — the index-registered class
 *       identity descriptor text for {@code C_STRUCT}/{@code C_POINTER}
 *       rows; {@code null} for every other kind.</li>
 * </ul>
 *
 * <p>Immutable; {@code C_STRUCT}/{@code C_POINTER} rows must carry a
 * non-null identity text and every other kind must carry none — the
 * compact constructor enforces the invariant so no consumer can observe
 * a half-shaped row.</p>
 */
public record FfiType(Kind kind, String canonicalDescriptor,
                      String canonicalClassIdentity) {

    /** The nine pinned C FFI type kinds (directives D4). */
    public enum Kind {
        INT,
        NUMBER,
        BOOLEAN,
        STRING,
        BYTES,
        NULL,
        C_STRUCT,
        C_POINTER
    }

    public FfiType {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(canonicalDescriptor, "canonicalDescriptor");
        boolean classKinded = kind == Kind.C_STRUCT || kind == Kind.C_POINTER;
        if (classKinded && canonicalClassIdentity == null) {
            throw new IllegalArgumentException(
                "kind " + kind + " requires a canonical class identity");
        }
        if (!classKinded && canonicalClassIdentity != null) {
            throw new IllegalArgumentException(
                "kind " + kind + " must not carry a canonical class identity");
        }
    }
}
