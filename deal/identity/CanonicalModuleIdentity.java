package deal.identity;

import java.util.Objects;

/**
 * The sealed canonical public module identity of one source module
 * (design source {@code deal-v1.2-int32-and-bytes-architecture} D6):
 *
 * <pre>
 * CanonicalModuleIdentity = ProjectModule(ProjectModuleIdentity)
 *   | ExternalModule(rawImportSpecifier) | BuiltinModule
 * </pre>
 *
 * <p>This is a pure carrier hierarchy: it defines the three closed
 * variants and performs no resolution, classification (stdlib/externals
 * predicates, containment, most-specific selection), representability
 * validation, or descriptor-text projection. The producer is the
 * module-identity layer (E2's {@code ModuleIdentityResolver} in
 * {@code deal.module}); {@code deal.identity} only pins the shapes.
 * Record/enum equality and hashCode are structural over the variant
 * components, so identities compare byte-for-byte through their fields.</p>
 */
public sealed interface CanonicalModuleIdentity
    permits CanonicalModuleIdentity.ProjectModule,
           CanonicalModuleIdentity.ExternalModule,
           CanonicalModuleIdentity.BuiltinModule {

    /**
     * A configured-root project module carrying its
     * {@link ProjectModuleIdentity}.
     */
    record ProjectModule(ProjectModuleIdentity projectIdentity)
            implements CanonicalModuleIdentity {

        public ProjectModule {
            Objects.requireNonNull(projectIdentity, "projectIdentity");
        }
    }

    /**
     * An externals-listed declaration module, identified by the externals
     * map key exactly as written in the manifest (the raw import
     * specifier — not normalized, not dotted, not reconstructed).
     */
    record ExternalModule(java.lang.String rawImportSpecifier)
            implements CanonicalModuleIdentity {

        public ExternalModule {
            Objects.requireNonNull(rawImportSpecifier,
                "rawImportSpecifier");
        }
    }

    /**
     * The language-distribution builtin module (the stdlib surface).
     * Singleton: exactly one instance exists.
     */
    enum BuiltinModule implements CanonicalModuleIdentity { INSTANCE }
}
