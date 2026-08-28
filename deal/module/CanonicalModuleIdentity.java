package deal.module;

import java.util.Objects;

/**
 * The one canonical public module identity per resolved source (design
 * source {@code strict-project-context-resolution-identity} D6,
 * {@code deal-v1.2-int32-and-bytes-architecture} D6):
 *
 * <pre>{@code
 * CanonicalModuleIdentity = ProjectModule(ProjectModuleIdentity)
 *   | ExternalModule(rawImportSpecifier) | BuiltinModule
 * }</pre>
 *
 * <p>{@link ModuleIdentityResolver}'s pure classifier assigns exactly one
 * of the three forms per resolved source, in the pinned precedence order
 * — (1) {@link BuiltinModule} for the six spec-listed stdlib declaration
 * files under the pinned stdlib surface, (2) {@link ExternalModule} for a
 * canonical file equal to an externals entry's validated declaration
 * path, (3) {@link ProjectModule} for a {@code .deal} (never
 * {@code .d.deal}) source contained by exactly one configured root with
 * strictly maximal containment — and no identity at all for every other
 * source. The externals and stdlib predicates are mutually exclusive for
 * every valid {@code ProjectContext} (ProjectLocator step 4(b) rejects
 * the overlap), so at most one form ever applies and the classification
 * is a pure function of the resolved source's canonical URI, independent
 * of the import spelling and of resolution order.</p>
 *
 * <p>All three forms are value objects: equality and hashing are
 * structural, and identical inputs produce identical values.</p>
 */
public sealed interface CanonicalModuleIdentity
        permits CanonicalModuleIdentity.ProjectModule,
                CanonicalModuleIdentity.ExternalModule,
                CanonicalModuleIdentity.BuiltinModule {

    /**
     * A rooted {@code .deal} source under one configured root: the
     * project form.
     *
     * @param projectIdentity the derived {@link ProjectModuleIdentity}
     *                        (configured root text, normalized root path,
     *                        relative module components)
     */
    record ProjectModule(ProjectModuleIdentity projectIdentity)
            implements CanonicalModuleIdentity {
        public ProjectModule {
            Objects.requireNonNull(projectIdentity, "projectIdentity");
        }
    }

    /**
     * A resolved source whose canonical file equals an externals entry's
     * validated declaration path (file-keyed, regardless of the import
     * spelling).
     *
     * @param rawImportSpecifier the externals map key exactly as written
     */
    record ExternalModule(String rawImportSpecifier)
            implements CanonicalModuleIdentity {
        public ExternalModule {
            Objects.requireNonNull(rawImportSpecifier, "rawImportSpecifier");
        }
    }

    /**
     * One of the six spec-listed stdlib declaration files under the
     * pinned {@code ProjectContext.stdlibSurfacePath} (file-keyed,
     * regardless of the import spelling). Carries no data: the stdlib
     * surface declares no classes today.
     */
    record BuiltinModule() implements CanonicalModuleIdentity {
    }
}
