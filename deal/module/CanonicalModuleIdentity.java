package deal.module;

import java.util.Objects;

/**
 * The canonical public module identity classification (design source
 * {@code strict-project-context-resolution-identity} D6,
 * {@code deal-v1.2-int32-and-bytes-architecture} D6):
 *
 * <pre>
 * CanonicalModuleIdentity = ProjectModule(ProjectModuleIdentity)
 *   | ExternalModule(rawImportSpecifier) | BuiltinModule
 * </pre>
 *
 * <p>Exactly one canonical public module identity exists per resolved
 * source; the {@link ModuleIdentityResolver} classifier derives it in the
 * pinned fixed order over canonical (symlink-resolved) source URIs, so the
 * classification is a pure function of the resolved file and never of the
 * import spelling or the resolution order:</p>
 *
 * <ol>
 *   <li>{@link BuiltinModule} — the canonical URI equals one of the six
 *       spec-listed stdlib declaration files under the pinned
 *       {@code ProjectContext.stdlibSurfacePath} (file-keyed).</li>
 *   <li>{@link ExternalModule} — the canonical URI equals an externals
 *       entry's validated declaration path (file-keyed; the raw import
 *       specifier is the entry's manifest key exactly as written).</li>
 *   <li>{@link ProjectModule} — a {@code .deal} source (never
 *       {@code .d.deal}) contained by a configured root with strictly
 *       maximal containment.</li>
 * </ol>
 *
 * <p>All three forms are structural value objects: two equal identities
 * carry equal components, and no address, timestamp, ordinal, or process
 * state enters any component.</p>
 */
public sealed interface CanonicalModuleIdentity
        permits CanonicalModuleIdentity.ProjectModule,
                CanonicalModuleIdentity.ExternalModule,
                CanonicalModuleIdentity.BuiltinModule {

    /**
     * A rooted project module: carries the derived
     * {@link ProjectModuleIdentity} (configured-root provenance plus the
     * relative module components).
     *
     * @param identity the derived project module identity
     */
    record ProjectModule(ProjectModuleIdentity identity)
            implements CanonicalModuleIdentity {
        public ProjectModule {
            Objects.requireNonNull(identity, "identity");
        }
    }

    /**
     * An externals-listed host declaration module: carries the externals
     * map key exactly as written in the manifest (the entry whose
     * validated declaration path equals the resolved canonical file).
     *
     * @param rawImportSpecifier the externals entry's raw import
     *                           specifier exactly as written
     */
    record ExternalModule(String rawImportSpecifier)
            implements CanonicalModuleIdentity {
        public ExternalModule {
            Objects.requireNonNull(rawImportSpecifier, "rawImportSpecifier");
        }
    }

    /**
     * A stdlib (language-distribution) module: one of the six spec-listed
     * stdlib declaration files under the pinned stdlib surface. Carries no
     * components — the projection for stdlib classes is the stdlib
     * epics' boundary, and the intrinsic builtin {@code Error} synthesis
     * reuses this form with the fixed class name {@code "Error"}.
     */
    record BuiltinModule() implements CanonicalModuleIdentity {
    }
}
