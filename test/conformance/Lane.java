package deal.test.conformance;

public interface Lane {

    /** The lane's backend name: exactly {@code luajit}, {@code jvm}, or {@code js}. */
    String name();

    /**
     * Executes one case and returns the closed lane outcome. Throwing is
     * a harness defect: the dispatcher wraps it as
     * {@link MismatchClass#HARNESS_DEFECT}.
     *
     * @param laneCase the dispatch unit (never null)
     * @return the lane outcome (never null)
     */
    LaneExecution execute(LaneCase laneCase) throws Exception;
}
