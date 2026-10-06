package deal.test.conformance;

public enum MismatchClass {
    /** First differing transcript byte with bounded context (G4.5). */
    TRANSCRIPT_MISMATCH,
    /** First differing error snapshot field (sidecar-authoritative field set). */
    ERROR_SNAPSHOT_MISMATCH,
    /** First differing compile diagnostic field (Compile Diagnostic contract). */
    COMPILE_DIAGNOSTIC_MISMATCH,
    /** The captured exit code differs from the sidecar's exact exit code. */
    EXIT_CODE_MISMATCH,
    /** Expected or unexpected compile rejection (corpus C6 divergence). */
    COMPILE_REJECT_MISMATCH,
    /** A lane's required generated artifacts are missing (G4.2). */
    ARTIFACT_MISSING,
    /** A lane's required tool is missing or broken (G3). */
    TOOL_MISSING,
    /** A lane exceeded its harness-owned deadline; the execution was terminated. */
    LANE_TIMEOUT,
    /** A lane subprocess failed outside the DEAL outcome surface. */
    PROCESS_FAILURE,
    /** A harness/lane contract defect (including an unregistered lane). */
    HARNESS_DEFECT;

    /**
     * True for the five infrastructure outcomes (G6): reported separately
     * from DEAL outcomes and never satisfying a case.
     */
    public boolean infrastructure() {
        return switch (this) {
            case ARTIFACT_MISSING, TOOL_MISSING, LANE_TIMEOUT,
                 PROCESS_FAILURE, HARNESS_DEFECT -> true;
            default -> false;
        };
    }
}
