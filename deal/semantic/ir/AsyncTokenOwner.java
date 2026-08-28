package deal.semantic.ir;

/**
 * The owner of a canonical async token (parent D13, "Async tokens and
 * aliasing").
 *
 * <p>Closed set — exactly {@link #DEAL_BODY_TASK} and
 * {@link #HOST_OPERATION}; no open or unknown fallback member and no
 * external extension point exist. A canonical token is created exactly
 * once by its owner op: an {@code ASYNC_START(DEAL_BODY)} or an async
 * {@code EXTERNAL_ENTRY} for a DEAL body task, or an
 * {@code ASYNC_START(HOST)} bound one-to-one to the scenario
 * {@code operationLabel} for a host operation.</p>
 */
public enum AsyncTokenOwner {

    /** The canonical token of a DEAL async body task. */
    DEAL_BODY_TASK,

    /** The canonical token bound to a scenario host operation label. */
    HOST_OPERATION
}
