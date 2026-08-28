package deal.semantic.ir;

/**
 * The closed async-start source set of {@code deal.semantic-ir/1} (parent
 * D13; schema S3).
 *
 * <p>Closed set — exactly {@link #DEAL_BODY}, {@link #HOST}, and
 * {@link #EXTERNAL}; no open or unknown fallback member and no external
 * extension point exist. The source is validator-derived from the
 * resolved {@link FunctionExecutionBinding} of the
 * {@code ASYNC_START}'s callee reference — it is never a producer choice:
 * a DEAL body or adapter-over-async task is {@code DEAL_BODY}, an async
 * host function is {@code HOST}, and an async external function is
 * {@code EXTERNAL}.</p>
 */
public enum AsyncStartSource {

    /** DEAL body task (or adapter-over-async task). */
    DEAL_BODY,

    /** Async host function: one host request, canonical token bound to the operation label. */
    HOST,

    /** Async external function: callee unit owns the canonical token and body task. */
    EXTERNAL
}
