package deal.semantic.ir;

/**
 * The closed parameter-boundary mode of an {@code ASYNC_START} (parent
 * D13/D15; schema S3).
 *
 * <p>Closed set — exactly {@link #RUN} and {@link #ELIDED_BY_ADAPTER}; no
 * open or unknown fallback member and no external extension point exist.
 * {@code ELIDED_BY_ADAPTER} is admissible only on the nested source
 * {@code ASYNC_START} of an adapter-over-async task: the outer adapter
 * invocation's N target-signature checks are the complete parameter-check
 * set, so the nested source op runs zero parameter boundaries (validator
 * R-ELIDED-PLACEMENT rejects every other placement).</p>
 */
public enum ParameterBoundaryMode {

    /** Run the declared parameter boundaries in order. */
    RUN,

    /** Run zero parameter boundaries (adapter-over-async nested source op only). */
    ELIDED_BY_ADAPTER
}
