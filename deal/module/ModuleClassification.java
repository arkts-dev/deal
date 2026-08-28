package deal.module;

import deal.identity.CanonicalModuleIdentity;
import deal.identity.ProjectModuleIdentity;

import java.util.Objects;

/**
 * The pure classifier result of
 * {@link ModuleIdentityResolver#classify(deal.project.ProjectContext, String)}
 * (design source {@code strict-project-context-resolution-identity} D6):
 * exactly one canonical public module identity per resolved source —
 * {@code moduleIdentity} — plus the derived {@code projectIdentity}
 * provenance for the {@link CanonicalModuleIdentity.ProjectModule} form
 * (the value {@link SourceModuleLocation#projectIdentity()} carries), and
 * a defensive {@link Issue} marker.
 *
 * <p>Both identity fields are {@code null} for a source with no public
 * module identity (D6 rule (4)): a relative out-of-root {@code .deal}
 * source, a declaration file matching no externals entry and not among
 * the six pinned stdlib files, a rooted non-externals {@code .d.deal}
 * source, or any other unclassified source. Such sources remain valid
 * class-free modules with private semantic identity only.</p>
 *
 * <p>The {@link Issue} markers report states that are unreachable for a
 * valid {@link deal.project.ProjectContext} — the assembly half maps them
 * to the pinned E2010-when-required behavior instead of silently picking
 * a winner:</p>
 *
 * @param moduleIdentity  the canonical module classification, or
 *                        {@code null} when no public module identity
 *                        applies
 * @param projectIdentity the derived {@link ProjectModuleIdentity} for
 *                        the {@link CanonicalModuleIdentity.ProjectModule}
 *                        form, or {@code null}
 * @param issue           the defensive issue marker (never {@code null})
 */
public record ModuleClassification(CanonicalModuleIdentity moduleIdentity,
                                   ProjectModuleIdentity projectIdentity,
                                   Issue issue) {

    /**
     * The defensive classifier issue markers.
     */
    public enum Issue {
        /** No issue: the classification is definitive. */
        NONE,
        /**
         * Two distinct configured roots contain the source with equal
         * (maximal) containment length. Unreachable for a valid
         * ProjectContext (duplicate normalized roots are already E2010 at
         * locate), but detected and reported rather than silently picked.
         */
        EQUAL_ROOT_TIE,
        /**
         * The source's canonical URI satisfies both the pinned stdlib
         * predicate and an externals entry's declaration path. Unreachable
         * for a valid ProjectContext (ProjectLocator step 4(b) rejects
         * the overlap at locate): reported as an internal invariant
         * violation with no identity published.
         */
        STDLIB_EXTERNAL_OVERLAP
    }

    public ModuleClassification {
        Objects.requireNonNull(issue, "issue");
    }
}
