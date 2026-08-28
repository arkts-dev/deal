package deal.identity;

import java.util.Objects;

/**
 * The canonical public identity of one class: the declaring module's
 * {@link CanonicalModuleIdentity} plus the declared class name (design
 * source {@code deal-v1.2-int32-and-bytes-architecture} D6):
 *
 * <pre>
 * CanonicalClassIdentity(moduleIdentity, className)
 * </pre>
 *
 * <p>This is a pure carrier record: it performs no resolution,
 * classification, representability validation, or descriptor-text
 * projection. Descriptor text comes only from the compilation's
 * {@link CanonicalClassIdentityIndex}; consumers never reverse-parse a
 * root boundary from text.</p>
 *
 * <p>Equality and hashCode are the record's structural semantics over
 * exactly {@code (moduleIdentity, className)} — the comparison contract
 * consumed by nominal type identity. Two identities are equal iff their
 * module identities and class names are equal; no descriptor text, path
 * normalization, or identity allocation enters the comparison.</p>
 *
 * <p><b>Intrinsic builtin identity:</b> the language-intrinsic
 * {@code Error} class is constructed as
 * {@code new CanonicalClassIdentity(CanonicalModuleIdentity.BuiltinModule.INSTANCE,
 * "Error")}. The carrier pins only the constructible shape; the index
 * registration and the {@code @$builtin/Error} descriptor-text projection
 * are owned by the module-identity layer.</p>
 *
 * @param moduleIdentity the declaring module's canonical module identity
 * @param className      the declared class name
 */
public record CanonicalClassIdentity(
    CanonicalModuleIdentity moduleIdentity,
    String className) {

    public CanonicalClassIdentity {
        Objects.requireNonNull(moduleIdentity, "moduleIdentity");
        Objects.requireNonNull(className, "className");
    }
}
