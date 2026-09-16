package deal.test.conformance;

import java.util.Objects;
import java.util.OptionalInt;

/**
 * The closed lane execution outcome of the differential gate (G6):
 * either a real execution carrying the captured stdout/stderr bytes and
 * the exact exit code, a compile rejection carrying the exact diagnostic
 * object (the sanctioned C6 divergence), or an infrastructure outcome of
 * one of the five infrastructure classes ({@code ARTIFACT_MISSING},
 * {@code TOOL_MISSING}, {@code LANE_TIMEOUT}, {@code PROCESS_FAILURE},
 * {@code HARNESS_DEFECT} — reported separately, never satisfying a case).
 *
 * <p>The gate's comparators consume these values; the captured bytes are
 * never written into by the gate (trace never contaminates program
 * streams).</p>
 */
public sealed interface LaneExecution
        permits LaneExecution.Executed, LaneExecution.Rejected,
                LaneExecution.Infrastructure {

    /** A real execution: captured stdout/stderr bytes and the exact exit code. */
    record Executed(byte[] stdout, byte[] stderr, int exitCode)
            implements LaneExecution {

        public Executed {
            Objects.requireNonNull(stdout, "stdout must not be null");
            Objects.requireNonNull(stderr, "stderr must not be null");
        }
    }

    /**
     * A compile rejection: the exact diagnostic object the lane produced
     * (mandatory code plus the lane-emitted line/column, if any).
     */
    record Rejected(String code, OptionalInt line, OptionalInt column)
            implements LaneExecution {

        public Rejected {
            Objects.requireNonNull(code, "code must not be null");
            Objects.requireNonNull(line, "line must not be null");
            Objects.requireNonNull(column, "column must not be null");
        }

        /** Convenience: a rejection carrying only the diagnostic code. */
        public static Rejected of(String code) {
            return new Rejected(code, OptionalInt.empty(), OptionalInt.empty());
        }
    }

    /**
     * An infrastructure outcome: one of the five infrastructure mismatch
     * classes with the bounded detail. Never satisfies a case (G6).
     */
    record Infrastructure(MismatchClass clazz, String detail)
            implements LaneExecution {

        public Infrastructure {
            Objects.requireNonNull(clazz, "clazz must not be null");
            Objects.requireNonNull(detail, "detail must not be null");
            if (!clazz.infrastructure()) {
                throw new IllegalArgumentException(
                    clazz + " is not an infrastructure outcome class");
            }
        }
    }
}
