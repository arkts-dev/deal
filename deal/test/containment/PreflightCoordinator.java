package deal.test.containment;

import java.util.Arrays;
import java.util.List;

/**
 * Preflight coordinator (fail-closed-toolchain-preflight D4/D7): the
 * only post-readiness JVM owned by this epic's preflight.
 *
 * <p>It connects to the inherited authenticated outer broker
 * ({@code DEALPG4_BROKER_PATH}/{@code DEALPG4_NONCE}), verifies the
 * {@code HELLO_OK} version and the full capability bitmask before any
 * {@code FEATURE_READY} (a missing capability bit or a wrong version
 * exits nonzero without ever sending {@code FEATURE_READY} — the outer
 * then fails {@code READINESS_TIMEOUT} and the gate is nonzero), sends
 * {@code FEATURE_READY <nonce>}, waits for {@code READY_ACK}, then
 * performs bounded round-trip nested invocations of benign commands
 * through {@link ContainedProcessBroker#run(String, String, List,
 * String, Limits)} ({@code luajit -v}, {@code /bin/true}), asserts each
 * {@link ContainedProcessBroker.ProcessResult} is clean (target exit 0,
 * containment-clean {@code REPORT} with {@code failureToken "-"}), sends
 * {@code BYE}, and exits 0.
 *
 * <p>Every request goes through the inherited broker: this class never
 * spawns the launcher, an outer, or any nested supervisor (D7), and it
 * supplies no FFI/library capability probe content (the E13 extension
 * slot stays empty). Any failure — a handshake failure, a capability
 * omission, a broker close, a {@code REJECT} reason token, a malformed
 * or unexpected record, or a non-clean round-trip — prints its named
 * token to stderr and exits nonzero; nothing is retried, skipped, or
 * downgraded.
 */
public final class PreflightCoordinator {

    private PreflightCoordinator() {
        /* Static entry point only. */
    }

    /**
     * Runs the preflight round-trips against the inherited broker and
     * exits 0 on clean success, nonzero on any failure (named token on
     * stderr).
     */
    public static void main(String[] args) {
        int status;
        try {
            status = runPreflight();
        } catch (ContainedProcessBroker.ContainmentException e) {
            System.err.println(e.token() + ": " + e.getMessage());
            status = 1;
        } catch (RuntimeException e) {
            System.err.println("PREFLIGHT_FAILURE: " + e);
            status = 1;
        }
        System.exit(status);
    }

    private static int runPreflight() {
        try (ContainedProcessBroker broker = ContainedProcessBroker.connect()) {
            /* connect() already verified HELLO_OK version=4 and every
             * capability bit; a failure there exited before any
             * FEATURE_READY. */
            broker.featureReady();
            roundTrip(broker, "luajit-version", Arrays.asList("luajit", "-v"));
            roundTrip(broker, "bin-true", Arrays.asList("/bin/true"));
            broker.bye();
            return 0;
        }
    }

    private static void roundTrip(ContainedProcessBroker broker, String phase,
                                  List<String> argv) {
        String cwd = System.getProperty("user.dir");
        ContainedProcessBroker.ProcessResult result =
                broker.run("preflight", phase, argv, cwd, ContainedProcessBroker.Limits.DEFAULTS);
        System.err.println("PREFLIGHT ROUNDTRIP " + phase + " invocationId="
                + result.invocationId + " exitCode=" + result.exitCode + " elapsedMs="
                + result.elapsedMs + " failureToken=" + result.failureToken
                + " disposition=" + result.disposition + " reapCount=" + result.reapCount
                + " adoptCount=" + result.adoptCount);
        if (result.exitCode != 0 || !result.failureToken.equals(ContainedProcessBroker.TOKEN_NONE)
                || !result.disposition.equals(ContainedProcessBroker.CLEAN_SUCCESS)) {
            throw new ContainedProcessBroker.ContainmentException("ROUNDTRIP_FAILED",
                    "round-trip '" + phase + "' was not clean: exitCode=" + result.exitCode
                            + " failureToken=" + result.failureToken
                            + " disposition=" + result.disposition);
        }
    }
}
