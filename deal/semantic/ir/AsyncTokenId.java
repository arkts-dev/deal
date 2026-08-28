package deal.semantic.ir;

import java.util.Objects;

/**
 * A stable async-token identity (parent D13, "Async tokens and aliasing").
 *
 * <p>A canonical token is created exactly once by its owner op; an alias
 * token is created by the referencing op and completes exactly when its
 * canonical referent completes, with the referent's completion
 * value/error unchanged. {@code AWAIT} consumes exactly one token (alias
 * or canonical) once. Alias chains are acyclic and are checked by the
 * validator (R-ALIAS-CYCLE); the frontend guarantees every published token
 * is consumed by exactly one {@code AWAIT} (E3014), and reuse is a
 * validator error (R-TOKEN-REUSE).</p>
 *
 * <p>Closed shape: {@link Canonical} or {@link Alias}; no other token
 * shape exists.</p>
 */
public sealed interface AsyncTokenId extends SemanticId, SemanticValue
    permits AsyncTokenId.Canonical, AsyncTokenId.Alias {

    /** The numeric token identity, non-negative. */
    long tokenId();

    /**
     * A canonical token: created exactly once by its owner op and owned by
     * that op's execution context.
     *
     * @param tokenId the numeric token identity; non-negative
     * @param owner   the creating owner; non-null
     */
    record Canonical(long tokenId, AsyncTokenOwner owner) implements AsyncTokenId {

        public Canonical {
            if (tokenId < 0) {
                throw new IllegalArgumentException("tokenId must be >= 0, got " + tokenId);
            }
            Objects.requireNonNull(owner, "owner must not be null");
        }

        @Override
        public String toString() {
            return "CanonicalToken(" + tokenId + ", " + owner + ")";
        }
    }

    /**
     * An alias token: created by the referencing op and completing exactly
     * when its canonical referent completes. Alias chains are acyclic.
     *
     * @param tokenId  the numeric token identity; non-negative
     * @param referent the referent token; non-null
     * @param linkKind the closed link kind; non-null
     */
    record Alias(long tokenId, AsyncTokenId referent, AsyncLinkKind linkKind)
        implements AsyncTokenId {

        public Alias {
            if (tokenId < 0) {
                throw new IllegalArgumentException("tokenId must be >= 0, got " + tokenId);
            }
            Objects.requireNonNull(referent, "referent must not be null");
            Objects.requireNonNull(linkKind, "linkKind must not be null");
        }

        @Override
        public String toString() {
            return "AliasToken(" + tokenId + " -> " + referent + ", " + linkKind + ")";
        }
    }
}
