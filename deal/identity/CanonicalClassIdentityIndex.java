package deal.identity;

/**
 * The canonical class-identity index contract (design source
 * {@code strict-project-context-resolution-identity} D6,
 * {@code canonical-type-system-and-runtime-descriptors} D3).
 *
 * <p>One immutable index per compilation, published by the
 * module-identity layer (E2's {@code ModuleIdentityResolver} in
 * {@code deal.module}) and consumed read-only by descriptor encoding,
 * planners, metadata, and later FFI seams. {@code deal.identity} pins
 * the accessor contract only; it performs no lookup, resolution,
 * classification, or descriptor-text projection.</p>
 *
 * <p>The index is a bijection between registered
 * {@link CanonicalClassIdentity} values — keyed structurally by their
 * {@code (moduleIdentity, className)} pair — and full class descriptor
 * texts (including the leading {@code '@'}), keyed byte-for-byte. Every
 * registered identity maps to exactly one text and every registered text
 * maps back to exactly one identity. Consumer code recovers identity
 * structure from text only through this index; it never reverse-parses a
 * root/relative-path boundary.</p>
 *
 * <p><b>Absent-lookup contract (pinned):</b> an identity or descriptor
 * text absent from the index is an internal invariant violation for the
 * caller — it means a consumer requested public identity that the
 * identity layer never validated and registered (for published projects
 * the identity-representability gate precedes every consumer). There is
 * never a silent fallback and never invented text:</p>
 *
 * <ul>
 *   <li>{@link #descriptorTextFor(CanonicalClassIdentity)} returns the
 *       index-registered projection text byte-for-byte for a registered
 *       identity and throws {@link IllegalStateException} otherwise; it
 *       never returns {@code null} and never computes or invents
 *       text.</li>
 *   <li>{@link #identityForDescriptorText(String)} returns the
 *       registered identity for a byte-identical descriptor text and
 *       throws {@link IllegalStateException} otherwise; it never invents
 *       or reconstructs an identity and never normalizes the text.</li>
 * </ul>
 */
public interface CanonicalClassIdentityIndex {

    /**
     * Returns the index-registered canonical descriptor text for the
     * given identity, byte-for-byte (including the leading {@code '@'}).
     *
     * <p>The lookup key is the identity's structural
     * {@code (moduleIdentity, className)} pair.</p>
     *
     * @param identity the registered canonical class identity
     * @return the index-registered descriptor text, never {@code null}
     * @throws IllegalStateException when the identity is absent from the
     *         index (pinned internal invariant violation for the caller)
     */
    String descriptorTextFor(CanonicalClassIdentity identity);

    /**
     * Returns the registered canonical class identity for the given
     * descriptor text, matched byte-for-byte (no path normalization, no
     * case folding, no boundary inference).
     *
     * @param descriptorText the full class descriptor text, including the
     *                       leading {@code '@'}
     * @return the registered identity, never {@code null}
     * @throws IllegalStateException when the text is absent from the
     *         index (pinned internal invariant violation for the caller)
     */
    CanonicalClassIdentity identityForDescriptorText(String descriptorText);
}
