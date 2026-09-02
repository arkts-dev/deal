package deal.test.conformance;

import java.util.Objects;

/**
 * One verdict mismatch of the differential gate: the closed
 * {@link MismatchClass}, the subject the mismatch names (a backend lane
 * name for differential lane mismatches, a fixture path for
 * backend-neutral mismatches), and the bounded first-mismatch detail.
 *
 * <p>Infrastructure outcomes are reported separately from DEAL outcomes
 * and never satisfy a case (G6): {@link #infrastructure()} mirrors
 * {@link MismatchClass#infrastructure()}.</p>
 */
public record GateMismatch(MismatchClass clazz, String subject, String detail) {

    public GateMismatch {
        Objects.requireNonNull(clazz, "clazz must not be null");
        Objects.requireNonNull(subject, "subject must not be null");
        Objects.requireNonNull(detail, "detail must not be null");
    }

    /** True for infrastructure outcomes (never satisfy a case). */
    public boolean infrastructure() {
        return clazz.infrastructure();
    }

    /** The gate-readable one-line failure: subject, class, detail. */
    public String message() {
        return subject + ": " + clazz + ": " + detail;
    }

    @Override
    public String toString() {
        return message();
    }
}
