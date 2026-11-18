package deal.diagnostics;

import java.util.List;
import java.util.Objects;

/** Observed compiler execution, not certification or inferred artifact readiness. */
public record CompilationOutcome(String backend, String semanticProfile, String releaseStateHash,
                                 boolean published, List<PhaseResult> phases) {
    public enum Phase {
        DISCOVERY_PARSE, SIGNATURES, ENTRY_VALIDATION, MODULE_ORDER, TYPECHECK,
        CHECKED_PROJECT, REQUIREMENTS, CONSTRUCTION_PLANS, DEPENDENCY_PLAN,
        EMISSION, STAGE_DUMPS, PUBLICATION
    }
    public enum Status { SKIPPED, RUNNING, SUCCEEDED, FAILED }
    public record PhaseResult(Phase phase, Status status) {
        public PhaseResult {
            Objects.requireNonNull(phase, "phase");
            Objects.requireNonNull(status, "status");
        }
    }
    public CompilationOutcome {
        Objects.requireNonNull(backend, "backend");
        Objects.requireNonNull(semanticProfile, "semanticProfile");
        Objects.requireNonNull(releaseStateHash, "releaseStateHash");
        phases = List.copyOf(phases);
        if (phases.size() != Phase.values().length)
            throw new IllegalArgumentException("Each compiler phase must have an observed status");
        for (int i = 0; i < phases.size(); i++)
            if (phases.get(i).phase() != Phase.values()[i])
                throw new IllegalArgumentException("Compiler phase order mismatch");
        if (published != (phases.getLast().status() == Status.SUCCEEDED))
            throw new IllegalArgumentException("Publication must match observed atomic replacement");
    }
}
