package deal.ffi;

import java.util.List;
import java.util.Objects;

/**
 * One immutable C FFI class descriptor row (design source
 * {@code deal-v1.2-directives-and-c-ffi-declarations} D4):
 * {@code FfiClassDescriptor(name, canonicalClassIdentity,
 * qualifiedDealDescriptor, kind, orderedFields, compilerDefaultPlan)}.
 *
 * <ul>
 *   <li>{@code name} — the DEAL class name exactly as declared.</li>
 *   <li>{@code canonicalClassIdentity} — the index-registered canonical
 *       class identity descriptor text (the nominal identity every
 *       boundary check compares byte-for-byte).</li>
 *   <li>{@code qualifiedDealDescriptor} — the same canonical class atom;
 *       retained as the settled metadata field the runtime seam carries
 *       ({@code luajit-ffi-generated-content-seam} S4).</li>
 *   <li>{@code kind} — {@code C_STRUCT} (source-order scalar fields,
 *       by value) or {@code C_POINTER} (empty opaque {@code void*}
 *       token).</li>
 *   <li>{@code orderedFields} — the validated fields in class source
 *       order; empty for {@code C_POINTER}.</li>
 *   <li>{@code compilerDefaultPlan} — the immutable default plan of a
 *       {@code C_STRUCT} class; {@code null} exactly for
 *       {@code C_POINTER} (no fields, no construction, no plan).</li>
 * </ul>
 */
public record FfiClassDescriptor(String name, String canonicalClassIdentity,
                                 String qualifiedDealDescriptor,
                                 ClassKind kind,
                                 List<FfiFieldDescriptor> orderedFields,
                                 FfiCompilerClassDefaultPlan compilerDefaultPlan) {

    /** The two extern-C class kinds. */
    public enum ClassKind {
        /** Source-order scalar fields crossing C by value. */
        C_STRUCT,
        /** Empty non-null opaque pointer token. */
        C_POINTER
    }

    public FfiClassDescriptor {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(canonicalClassIdentity, "canonicalClassIdentity");
        Objects.requireNonNull(qualifiedDealDescriptor, "qualifiedDealDescriptor");
        Objects.requireNonNull(kind, "kind");
        if (name.isEmpty()) {
            throw new IllegalArgumentException("name must not be empty");
        }
        orderedFields = List.copyOf(Objects.requireNonNull(orderedFields,
            "orderedFields"));
        if (kind == ClassKind.C_STRUCT && compilerDefaultPlan == null) {
            throw new IllegalArgumentException(
                "C_STRUCT class requires a compiler default plan");
        }
        if (kind == ClassKind.C_POINTER && compilerDefaultPlan != null) {
            throw new IllegalArgumentException(
                "C_POINTER class must not carry a compiler default plan");
        }
        if (kind == ClassKind.C_POINTER && !orderedFields.isEmpty()) {
            throw new IllegalArgumentException(
                "C_POINTER class must carry no fields");
        }
    }
}
