package deal.semantic.ir;

/**
 * The link kind of an alias async token (parent D13, "Async tokens and
 * aliasing").
 *
 * <p>Closed set — exactly {@link #EXTERNAL_LINK} and
 * {@link #ADAPTER_INNER}; no open or unknown fallback member and no
 * external extension point exist. {@code EXTERNAL_LINK} marks the
 * caller-side token of an {@code ASYNC_START(EXTERNAL)} aliasing the
 * callee unit's canonical token through an {@link ExternalAsyncLink};
 * {@code ADAPTER_INNER} marks the outer token of an adapter-over-async
 * {@code ASYNC_START} aliasing the nested source call's canonical token.
 * Alias chains are acyclic (validator R-ALIAS-CYCLE).</p>
 */
public enum AsyncLinkKind {

    /** Cross-module alias: the referent lives in the callee unit. */
    EXTERNAL_LINK,

    /** Adapter-over-async alias: the referent is the nested source token. */
    ADAPTER_INNER
}
