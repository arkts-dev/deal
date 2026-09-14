package deal.test.containment;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The P5-hosted live broker session suite
 * (dealpg4-java-broker-session-tests D1/D2): a plain-Java
 * package-private class with a static run entry, hosted in-process by
 * the P5 coordinator JVM on the inherited authenticated broker
 * connection — never a second JVM, never a second outer session,
 * never a launcher spawn (preflight D7). Every request goes through
 * the inherited broker.
 *
 * <p>The suite observes, against the real outer and real nested
 * supervisors: the authenticated session facts ({@code HELLO_OK}
 * version exactly 4 and the full 63-bit capability mask); the
 * client-side ordering negatives; the single-connection rule observed
 * from the client side (a second connection is closed without
 * {@code HELLO_OK}, and the first connection stays fully functional);
 * record exchange (clean round-trips, a content target through
 * OUT/OUT_END with byte accounting, the 1 MiB cap path, and a
 * gate-clean nonzero-exit target); the record-level ACK validation (a
 * scripted wrong-nonce ACK is answered {@code REJECT <id> <tag>
 * AUTH_FAILED} with the broker open and the record intact — the first
 * ACK is not consumed — and the correct ACK through the
 * scripted-invocation seam then completes the same record CLEAN
 * success); the record-level CANCEL validation (a wrong-nonce CANCEL
 * is answered {@code REJECT <id> <tag> CANCEL_AUTH_FAILED} with the
 * broker open and the record untouched, and the correct-nonce CANCEL
 * of the same record then completes it CLEAN cancelled); the nonce-
 * bound CANCEL flow with the retained {@code STUB_READY} nonce; and
 * the D7 spawn scans. Every observation is gate-clean by construction
 * (D2): no FAILED record and no gate token reaches the P5 session.
 *
 * <p>Failures print {@code BROKER_SESSION_TEST_FAILED <token>} on
 * stderr and return nonzero; {@link PreflightCoordinator} maps the
 * nonzero status to its exit, the outer classifies the coordinator
 * reaped-nonzero ({@code COORDINATOR_LOST}) and both gate scripts fail
 * {@code PREFLIGHT_OUTER_FAILED}. Nothing is retried, skipped, or
 * downgraded.
 */
final class BrokerSessionTestSuite {

    /** An invocationId no registry can hold (cancelLive negative). */
    private static final long UNKNOWN_INVOCATION_ID = 999999999L;

    private BrokerSessionTestSuite() {
        /* Static run entry only. */
    }

    /**
     * Runs the live suite on the authenticated broker and returns 0
     * when every observation held, nonzero otherwise (the named
     * failure token was printed on stderr).
     */
    static int run(ContainedProcessBroker broker) {
        try {
            liveHandshakeAssertion(broker);
            preReadinessOrderingNegatives(broker);
            /* The coordinator's runnable preflight core: FEATURE_READY
             * + the preserved round-trip pair (luajit -v, /bin/true) —
             * the session's primary readiness/chain proof (preflight
             * D4), asserted field-by-field. The suite invokes the
             * core on the live broker and asserts its results. */
            PreflightCoordinator.runCoordinatorCore(broker);
            postReadinessOrderingNegatives(broker);
            observeSecondConnectionRejection(broker);
            scriptedAckRejectionObservation(broker);
            scriptedCancelRejectionObservation(broker);
            contentRoundTrip(broker);
            nonzeroExitRoundTrip(broker);
            chunkedContentRoundTrip(broker);
            long cancelledId = liveCancelFlow(broker);
            /* The terminal-id cancelLive negative: the terminal record
             * cleared the retained nonce, so the CANCEL is refused
             * client-side with CANCEL_AUTH_FAILED before any write. */
            expectRefused("cancelLive for the terminal record", "CANCEL_AUTH_FAILED",
                    () -> broker.cancelLive(cancelledId));
            /* The D7 spawn scan again after the round-trips (second
             * pass: zero Java-spawned launcher processes, the outer
             * observed). */
            PreflightCoordinator.liveSpawnScan(false);
            /* The post-scan marker round-trip: a real contained
             * invocation whose OUTER record line carries the
             * spawn-scan pass marker into the P5 captured output. */
            PreflightCoordinator.roundTrip(broker, "spawn-scan-final",
                    Arrays.asList("/bin/true"));
            requireQuietStreamEnd(broker);
            System.out.println("LIVE SESSION CLEAN: broker stream quiet at session end; "
                    + "every record terminal CLEAN success or clean cancelled; no "
                    + "PROTOCOL_ERROR / AUTH_FAILED / BROKER_STALLED token reached the "
                    + "session, so the outer's final report carries none of them");
            return 0;
        } catch (ContainedProcessBroker.ContainmentException e) {
            System.err.println("BROKER_SESSION_TEST_FAILED " + e.token() + ": "
                    + e.getMessage());
            return 1;
        } catch (RuntimeException e) {
            System.err.println("BROKER_SESSION_TEST_FAILED: " + e);
            return 1;
        }
    }

    /**
     * Live handshake assertion: the suite's {@code connect()} already
     * required {@code HELLO_OK} version exactly 4 and every bit of the
     * full 63-bit expectation (including bit 32) before any
     * {@code FEATURE_READY}; this additionally asserts the observed
     * mask equals 63 exactly — the post-flip live outer. A mask
     * regression to 31 would have failed the handshake with
     * {@code CAPABILITY_MISSING} before this point.
     */
    private static void liveHandshakeAssertion(ContainedProcessBroker broker) {
        long caps = broker.helloCaps();
        if (caps != ContainedProcessBroker.EXPECTED_CAPABILITY_MASK) {
            throw new ContainedProcessBroker.ContainmentException("LIVE_BROKER_FAILED",
                    "the live outer advertised HELLO_OK capability bitmask " + caps
                            + ", expected exactly "
                            + ContainedProcessBroker.EXPECTED_CAPABILITY_MASK);
        }
        System.out.println("LIVE HANDSHAKE version=" + ContainedProcessBroker.PROTOCOL_VERSION
                + " caps=" + caps + " expected="
                + ContainedProcessBroker.EXPECTED_CAPABILITY_MASK);
    }

    /**
     * The pre-readiness ordering negatives: {@code BYE} before
     * {@code FEATURE_READY}, {@code INVOKE} before readiness, and
     * {@code cancelLive} for an unknown id are each refused client-side
     * ({@code PROTOCOL_ERROR}) before any write; the subsequent clean
     * live round-trips then prove the refusals never reached the
     * socket.
     */
    private static void preReadinessOrderingNegatives(ContainedProcessBroker broker) {
        expectRefused("BYE before FEATURE_READY", "PROTOCOL_ERROR", broker::bye);
        expectRefused("INVOKE before FEATURE_READY", "PROTOCOL_ERROR", () ->
                broker.run("preflight", "ordering-negative",
                        Arrays.asList("luajit", "-v"), System.getProperty("user.dir"),
                        ContainedProcessBroker.Limits.DEFAULTS));
        expectRefused("cancelLive for an unknown id before FEATURE_READY",
                "PROTOCOL_ERROR", () -> broker.cancelLive(UNKNOWN_INVOCATION_ID));
        System.out.println("LIVE ORDERING NEGATIVES (pre-readiness) PASS: every refusal "
                + "was client-side (PROTOCOL_ERROR) before any write");
    }

    /**
     * The post-readiness ordering negatives: a duplicate
     * {@code FEATURE_READY} and a {@code cancelLive} for an unknown id
     * are refused client-side ({@code PROTOCOL_ERROR} /
     * {@code CANCEL_AUTH_FAILED}) before any write.
     */
    private static void postReadinessOrderingNegatives(ContainedProcessBroker broker) {
        expectRefused("duplicate FEATURE_READY", "PROTOCOL_ERROR", broker::featureReady);
        expectRefused("cancelLive for an unknown id", "CANCEL_AUTH_FAILED",
                () -> broker.cancelLive(UNKNOWN_INVOCATION_ID));
        System.out.println("LIVE ORDERING NEGATIVES (post-readiness) PASS: every refusal "
                + "was client-side (PROTOCOL_ERROR / CANCEL_AUTH_FAILED) before any write; "
                + "the subsequent clean live round-trips prove none of them reached the "
                + "socket");
    }

    /**
     * Asserts one client-side refusal: the action must throw a
     * {@link ContainedProcessBroker.ContainmentException} carrying
     * {@code expectedToken} — a call that completes, or one refused
     * with a different token, is {@code LIVE_BROKER_FAILED}.
     */
    private static void expectRefused(String what, String expectedToken, Runnable action) {
        String observed;
        try {
            action.run();
            observed = null; /* not refused */
        } catch (ContainedProcessBroker.ContainmentException e) {
            observed = e.token();
        }
        if (!expectedToken.equals(observed)) {
            throw new ContainedProcessBroker.ContainmentException("LIVE_BROKER_FAILED",
                    what + ": " + (observed == null
                            ? "was not refused"
                            : "refused with token '" + observed + "'")
                            + ", expected " + expectedToken);
        }
        System.out.println("LIVE ORDERING NEGATIVE " + what + " -> " + expectedToken
                + " (client-side, before any write)");
    }

    /**
     * The single-connection rule observed from the client side (D2,
     * outer-coordinator-and-broker D5): the outer accepts exactly one
     * broker connection and closes every further connection without a
     * read. The suite opens a second connection from this same
     * coordinator process and performs the HELLO/HELLO_OK handshake on
     * it — the outer's close surfaces client-side as
     * {@code AUTH_FAILED} before {@code HELLO_OK} (a handshake that
     * completed with {@code HELLO_OK} would be a single-connection-rule
     * violation). Gate-neutral by construction: no record, no token —
     * and the first, authenticated connection stays fully functional,
     * proven by every subsequent live round-trip and rejection
     * observation on it.
     */
    private static void observeSecondConnectionRejection(ContainedProcessBroker broker) {
        String path = System.getenv(ContainedProcessBroker.ENV_BROKER_PATH);
        if (path == null || path.isEmpty()) {
            throw new ContainedProcessBroker.ContainmentException("SECOND_CONNECTION_FAILED",
                    "environment variable " + ContainedProcessBroker.ENV_BROKER_PATH
                            + " is missing — cannot open the second broker connection");
        }
        try {
            ContainedProcessBroker second =
                    ContainedProcessBroker.connect(path, broker.coordinatorNonce());
            /* A second handshake completing HELLO_OK is the
             * single-connection-rule violation: fail. */
            second.close();
            throw new ContainedProcessBroker.ContainmentException("SECOND_CONNECTION_FAILED",
                    "the outer answered the second broker connection with HELLO_OK — "
                            + "the single-connection rule is broken");
        } catch (ContainedProcessBroker.ContainmentException e) {
            if (e.token().equals("AUTH_FAILED") && e.getMessage() != null
                    && e.getMessage().contains("before HELLO_OK")) {
                System.out.println("LIVE SECOND CONNECTION REJECTED: AUTH_FAILED before "
                        + "HELLO_OK (the outer closed the second connection without a "
                        + "read) — " + e.getMessage());
                return;
            }
            if (e.token().equals("BROKER_IO_ERROR")) {
                /* The outer's close raced the HELLO write: the same
                 * rejection — the connection died without HELLO_OK. */
                System.out.println("LIVE SECOND CONNECTION REJECTED: the outer closed the "
                        + "second connection before/around the HELLO write — "
                        + e.getMessage());
                return;
            }
            throw e;
        }
    }

    /**
     * The record-level ACK validation observation (D2/D3,
     * outer-coordinator-and-broker D6): one scripted invocation is
     * driven to {@code STUB_READY} without the auto-ACK (the record is
     * TARGET_PUBLISHED at the outer); a scripted wrong-nonce ACK is
     * answered {@code REJECT <id> <tag> AUTH_FAILED} with the broker
     * open, the record intact, and the first-ACK not consumed; the
     * correct ACK through the seam then releases the same record and
     * completes it CLEAN success — the untouched-record proof.
     */
    private static void scriptedAckRejectionObservation(ContainedProcessBroker broker) {
        String cwd = System.getProperty("user.dir");
        ContainedProcessBroker.ScriptedInvocation invocation =
                broker.beginScriptedInvocation("preflight", "scripted-ack",
                        Arrays.asList("/bin/true"), cwd);
        broker.emitScriptedAck(invocation, wrongNonceFor(invocation.stubNonce));
        ContainedProcessBroker.BrokerRejectionException rejection =
                broker.readScriptedRejection(invocation);
        if (!rejection.reasonToken().equals("AUTH_FAILED")
                || rejection.invocationId() != invocation.invocationId
                || !rejection.clientTag().equals(invocation.clientTag)) {
            throw new ContainedProcessBroker.ContainmentException(
                    "SCRIPTED_ACK_REJECT_FAILED",
                    "the wrong-nonce ACK was answered REJECT "
                            + rejection.invocationId() + " " + rejection.clientTag()
                            + " " + rejection.reasonToken() + " — expected REJECT "
                            + invocation.invocationId + " " + invocation.clientTag
                            + " AUTH_FAILED");
        }
        System.out.println("LIVE ACK REJECTION invocationId=" + invocation.invocationId
                + " token=" + rejection.reasonToken()
                + " (broker open, record intact, first ACK not consumed — the correct "
                + "ACK now completes the same record)");
        /* The record is untouched and the first ACK was not consumed:
         * the correct ACK through the seam releases the same record
         * and completes it CLEAN success. */
        broker.emitScriptedAck(invocation, invocation.stubNonce);
        ContainedProcessBroker.ProcessResult result =
                broker.finishScriptedInvocation(invocation);
        System.out.println("LIVE ACK COMPLETION invocationId=" + result.invocationId
                + " exitCode=" + result.exitCode + " failureToken="
                + result.failureToken + " disposition=" + result.disposition
                + " reapCount=" + result.reapCount + " adoptCount=" + result.adoptCount);
        if (result.invocationId != invocation.invocationId
                || !result.disposition.equals(ContainedProcessBroker.CLEAN_SUCCESS)
                || !result.failureToken.equals(ContainedProcessBroker.TOKEN_NONE)
                || result.exitCode != 0) {
            throw new ContainedProcessBroker.ContainmentException(
                    "SCRIPTED_ACK_COMPLETION_FAILED",
                    "the correct ACK did not complete the same record CLEAN success: "
                            + "disposition=" + result.disposition
                            + " failureToken=" + result.failureToken
                            + " exitCode=" + result.exitCode
                            + " invocationId=" + result.invocationId
                            + " expected " + invocation.invocationId);
        }
    }

    /**
     * The record-level CANCEL validation observation (D2/D3,
     * outer-coordinator-and-broker D7): one scripted invocation is
     * driven to {@code STUB_READY} without the auto-ACK (the record is
     * TARGET_PUBLISHED at the outer, where the command provably never
     * started and no relay record can interleave); a wrong-nonce
     * CANCEL (through the existing explicit-nonce cancel surface) is
     * answered {@code REJECT <id> <tag> CANCEL_AUTH_FAILED} with the
     * broker open and the record untouched; the correct ACK through
     * the seam then releases the same record, and the correct-nonce
     * CANCEL of that released record completes it CLEAN cancelled.
     * The target is the trap-and-exit shape with a readiness marker
     * ({@code trap 'exit 0' TERM; echo <marker>; sleep 30}): a
     * pre-release cancel signal-kills the still pre-exec stub and
     * classifies {@code FAILED CALLER_LOST} (the supervisor's
     * cancel-path signal death), and a post-release cancel that races
     * the shell's trap installation does the same — so the clean
     * cancellation of the same record is gated on the marker: the
     * continuation emits the exact-nonce CANCEL only once the marker
     * bytes arrived on stdout, i.e. after the trap is installed, and
     * the trap-and-exit target then dies {@code CLD_EXITED} under the
     * cancel TERM — the canonical CLEAN cancelled derivation.
     */
    private static void scriptedCancelRejectionObservation(ContainedProcessBroker broker) {
        String cwd = System.getProperty("user.dir");
        String trapReadyMarker = "dealpg4-trap-ready";
        ContainedProcessBroker.ScriptedInvocation invocation =
                broker.beginScriptedInvocation("preflight", "scripted-cancel",
                        Arrays.asList("/bin/sh", "-c",
                                "trap 'exit 0' TERM; echo " + trapReadyMarker
                                        + "; sleep 30"), cwd);
        /* The wrong-nonce CANCEL through the existing explicit-nonce
         * cancel(invocationId, nonce) surface (D3: CANCEL needs no new
         * seam). Pre-release the record stream carries nothing but the
         * REJECT answer, so the observation is exact. */
        broker.cancel(String.valueOf(invocation.invocationId),
                wrongNonceFor(invocation.stubNonce));
        ContainedProcessBroker.BrokerRejectionException rejection =
                broker.readScriptedRejection(invocation);
        if (!rejection.reasonToken().equals("CANCEL_AUTH_FAILED")
                || rejection.invocationId() != invocation.invocationId
                || !rejection.clientTag().equals(invocation.clientTag)) {
            throw new ContainedProcessBroker.ContainmentException(
                    "SCRIPTED_CANCEL_REJECT_FAILED",
                    "the wrong-nonce CANCEL was answered REJECT "
                            + rejection.invocationId() + " " + rejection.clientTag()
                            + " " + rejection.reasonToken() + " — expected REJECT "
                            + invocation.invocationId + " " + invocation.clientTag
                            + " CANCEL_AUTH_FAILED");
        }
        System.out.println("LIVE CANCEL REJECTION invocationId=" + invocation.invocationId
                + " token=" + rejection.reasonToken()
                + " (broker open, record untouched — the correct ACK then releases "
                + "the same record and the correct-nonce CANCEL cancels it)");
        /* The record is untouched: the correct ACK through the seam
         * releases the same record (RELEASED at the outer when the ACK
         * write completes). The marker-gated continuation then emits
         * the exact-nonce CANCEL once the trap-ready marker arrived —
         * the trap is installed, the cancel TERM hits the trap, the
         * target exits CLD_EXITED, and the record completes CLEAN
         * cancelled (never the pre-trap signal-death race). */
        broker.emitScriptedAck(invocation, invocation.stubNonce);
        ContainedProcessBroker.ProcessResult result =
                broker.finishScriptedInvocation(invocation, trapReadyMarker);
        System.out.println("LIVE CANCEL COMPLETION invocationId=" + result.invocationId
                + " failureToken=" + result.failureToken
                + " disposition=" + result.disposition
                + " reapCount=" + result.reapCount + " adoptCount=" + result.adoptCount);
        if (result.invocationId != invocation.invocationId
                || !result.disposition.equals(ContainedProcessBroker.CLEAN_CANCELLED)
                || !result.failureToken.equals(ContainedProcessBroker.TOKEN_NONE)
                || !result.containmentClean()) {
            throw new ContainedProcessBroker.ContainmentException(
                    "SCRIPTED_CANCEL_COMPLETION_FAILED",
                    "the correct-nonce CANCEL did not complete the same record CLEAN "
                            + "cancelled: disposition=" + result.disposition
                            + " failureToken=" + result.failureToken
                            + " invocationId=" + result.invocationId
                            + " expected " + invocation.invocationId);
        }
    }

    /**
     * Record exchange with content: a target writing known bytes to
     * both streams; the relayed OUT/OUT_END chunks must reproduce both
     * streams byte-exactly with the REPORT byte accounting matching
     * (the client's stream accounting cross-check enforces the counts
     * for non-truncated streams; this observation additionally pins the
     * content itself).
     */
    private static void contentRoundTrip(ContainedProcessBroker broker) {
        String cwd = System.getProperty("user.dir");
        String outText = "dealpg4-live-stdout-content";
        String errText = "dealpg4-live-stderr-content";
        ContainedProcessBroker.ProcessResult result = broker.run("preflight",
                "content-roundtrip",
                Arrays.asList("/bin/sh", "-c",
                        "printf '" + outText + "'; printf '" + errText + "' >&2"),
                cwd, ContainedProcessBroker.Limits.DEFAULTS);
        System.out.println("LIVE CONTENT ROUNDTRIP invocationId=" + result.invocationId
                + " stdoutBytes=" + result.stdoutBytes + " stderrBytes="
                + result.stderrBytes + " failureToken=" + result.failureToken
                + " disposition=" + result.disposition);
        if (result.exitCode != 0
                || !result.failureToken.equals(ContainedProcessBroker.TOKEN_NONE)
                || !result.disposition.equals(ContainedProcessBroker.CLEAN_SUCCESS)
                || !result.stdout.equals(outText)
                || !result.stderr.equals(errText)
                || result.stdoutBytes != outText.length()
                || result.stderrBytes != errText.length()
                || result.stdoutTruncated || result.stderrTruncated
                || !result.groupProof || !result.sessionProof || !result.drainEof) {
            throw new ContainedProcessBroker.ContainmentException("CONTENT_ROUNDTRIP_FAILED",
                    "the content round-trip did not reproduce both streams with matching "
                            + "byte accounting: exitCode=" + result.exitCode
                            + " failureToken=" + result.failureToken
                            + " disposition=" + result.disposition
                            + " stdoutBytes=" + result.stdoutBytes
                            + " stderrBytes=" + result.stderrBytes
                            + " stdoutTruncated=" + result.stdoutTruncated
                            + " stderrTruncated=" + result.stderrTruncated
                            + " groupProof=" + result.groupProof
                            + " sessionProof=" + result.sessionProof
                            + " drainEof=" + result.drainEof);
        }
    }

    /**
     * Round-trip failure surfacing (gate-clean, D2): a target that
     * exits nonzero with clean containment is {@code CLEAN success}
     * with {@code REPORT.exitCode} carrying the target's code (an exit
     * code outside the reserved set {2,3,4,5,6} is positive exec
     * evidence, so the record stays gate-clean — no FAILED record, no
     * gate token). The client must surface the nonzero exit with a
     * containment-clean REPORT.
     */
    private static void nonzeroExitRoundTrip(ContainedProcessBroker broker) {
        String cwd = System.getProperty("user.dir");
        ContainedProcessBroker.ProcessResult result = broker.run("preflight",
                "nonzero-exit", Arrays.asList("/bin/false"), cwd,
                ContainedProcessBroker.Limits.DEFAULTS);
        System.out.println("LIVE NONZERO EXIT ROUNDTRIP invocationId=" + result.invocationId
                + " exitCode=" + result.exitCode + " failureToken="
                + result.failureToken + " disposition=" + result.disposition
                + " elapsedMs=" + result.elapsedMs);
        if (!result.disposition.equals(ContainedProcessBroker.CLEAN_SUCCESS)
                || !result.failureToken.equals(ContainedProcessBroker.TOKEN_NONE)
                || !result.containmentClean()
                || result.exitCode != 1) {
            throw new ContainedProcessBroker.ContainmentException("NONZERO_EXIT_FAILED",
                    "the nonzero-exit target was not surfaced as CLEAN success with "
                            + "REPORT.exitCode 1: exitCode=" + result.exitCode
                            + " failureToken=" + result.failureToken
                            + " disposition=" + result.disposition);
        }
        System.out.println("LIVE NONZERO EXIT GATE-CLEAN: CLEAN success with "
                + "REPORT.exitCode " + result.exitCode + " and failureToken \"-\" — the "
                + "gate follows record outcomes, not target exit codes, so this record "
                + "stays gate-clean");
    }

    /**
     * Record exchange with multi-chunk streams (D2; the 1 MiB cap path
     * of the OUT/OUT_END relay): a target writing past a single chunk
     * on each stream (100000 stdout bytes, 50000 stderr bytes — three
     * and two relay chunks respectively), well inside the outer's
     * relay-budget regime so the relay is lossless and the client's
     * stream accounting cross-check must hold byte-exactly. The native
     * 1 MiB drain truncation flags and the truncation marker are
     * native-battery content (probe/selftest, supervisor-page D7): the
     * outer's relay queue drops saturated OUT payload by design (the
     * truncation consequence of the write-side contract), so a live
     * suite target that saturated the drain would not receive the full
     * retained stream — the multi-chunk target pins the relay chunking
     * and byte accounting without entering the drop regime, and the
     * client-side 1 MiB retention cap is the same machinery.
     */
    private static void chunkedContentRoundTrip(ContainedProcessBroker broker) {
        String cwd = System.getProperty("user.dir");
        long outCount = 100000;
        long errCount = 50000;
        ContainedProcessBroker.ProcessResult result = broker.run("preflight",
                "chunked-content",
                Arrays.asList("/bin/sh", "-c",
                        "head -c " + outCount + " /dev/zero; head -c " + errCount
                                + " /dev/zero >&2"),
                cwd, ContainedProcessBroker.Limits.DEFAULTS);
        System.out.println("LIVE CHUNKED CONTENT ROUNDTRIP invocationId="
                + result.invocationId + " exitCode=" + result.exitCode
                + " stdoutBytes=" + result.stdoutBytes + " stderrBytes="
                + result.stderrBytes + " stdoutTruncated=" + result.stdoutTruncated
                + " stderrTruncated=" + result.stderrTruncated
                + " failureToken=" + result.failureToken
                + " disposition=" + result.disposition);
        if (result.exitCode != 0
                || !result.failureToken.equals(ContainedProcessBroker.TOKEN_NONE)
                || !result.disposition.equals(ContainedProcessBroker.CLEAN_SUCCESS)
                || result.stdout.length() != outCount
                || result.stderr.length() != errCount
                || result.stdoutBytes != outCount
                || result.stderrBytes != errCount
                || result.stdoutTruncated || result.stderrTruncated
                || !result.groupProof || !result.sessionProof || !result.drainEof) {
            throw new ContainedProcessBroker.ContainmentException(
                    "CHUNKED_CONTENT_FAILED",
                    "the multi-chunk round-trip did not reproduce both streams with "
                            + "matching byte accounting: exitCode=" + result.exitCode
                            + " failureToken=" + result.failureToken
                            + " disposition=" + result.disposition
                            + " stdoutBytes=" + result.stdoutBytes
                            + " stderrBytes=" + result.stderrBytes
                            + " stdoutTruncated=" + result.stdoutTruncated
                            + " stderrTruncated=" + result.stderrTruncated
                            + " groupProof=" + result.groupProof
                            + " sessionProof=" + result.sessionProof
                            + " drainEof=" + result.drainEof);
        }
    }

    /**
     * The live nonce-bound CANCEL flow: one long-running benign
     * invocation ({@code /bin/sh -c "trap 'exit 0' TERM; sleep 30"},
     * bounded by its own 45 s native deadline) runs on a worker
     * thread while the main thread awaits the live window, runs the
     * in-flight spawn scan, and issues a concurrent
     * {@code cancelLive} — the CANCEL carries exactly the retained
     * {@code STUB_READY} nonce (the registry nonce the outer
     * generated at registration; the only nonce it accepts). The
     * record must end CLEAN cancelled with {@code failureToken "-"};
     * the outer's final report then shows the record clean cancelled.
     * The target traps TERM and exits 0 on its own: the nested
     * supervisor's canonical cancel-path terminal derivation is CLEAN
     * cancelled for a target that dies {@code CLD_EXITED}, while a
     * target the cancel path itself signal-kills classifies
     * {@code FAILED CALLER_LOST} (supervisor-engine D5(c)) — a plain
     * {@code sleep 30} would therefore fail the gate, so the
     * trap-and-exit shape is the deterministic clean-cancel target.
     *
     * @return the cancelled invocationId (for the terminal-id
     *         cancelLive negative).
     */
    private static long liveCancelFlow(ContainedProcessBroker broker) {
        String cwd = System.getProperty("user.dir");
        AtomicReference<ContainedProcessBroker.ProcessResult> resultRef =
                new AtomicReference<>();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();
        Thread runner = new Thread(() -> {
            try {
                resultRef.set(broker.run("preflight", "cancel-sleep",
                        Arrays.asList("/bin/sh", "-c",
                                "trap 'exit 0' TERM; sleep 30"), cwd,
                        ContainedProcessBroker.Limits.DEFAULTS));
            } catch (Throwable t) {
                errorRef.set(t);
            }
        }, "live-cancel-sleep");
        runner.setDaemon(true);
        runner.start();

        long invocationId;
        try {
            invocationId = broker.awaitLiveInvocation(15000);
        } catch (ContainedProcessBroker.ContainmentException e) {
            throw new ContainedProcessBroker.ContainmentException("CANCEL_FLOW_FAILED",
                    "the live invocation never became observable: " + e);
        }
        System.out.println("LIVE CANCEL WINDOW invocationId=" + invocationId
                + " (STUB_READY nonce retained; the sleep-30 target is in flight)");

        /* The D7 spawn scan while the live invocation is in flight:
         * anti-hollow — it must observe the outer and at least one
         * nested serve supervisor, and must find zero Java-spawned
         * launcher processes. */
        PreflightCoordinator.liveSpawnScan(true);

        /* The concurrent nonce-bound CANCEL: cancelLive emits
         * CANCEL <invocationId> <nonce> with exactly the retained
         * STUB_READY nonce. */
        broker.cancelLive(invocationId);
        try {
            runner.join(30000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ContainedProcessBroker.ContainmentException("CANCEL_FLOW_FAILED",
                    "interrupted while joining the cancelled invocation: " + e);
        }
        if (runner.isAlive()) {
            throw new ContainedProcessBroker.ContainmentException("CANCEL_FLOW_FAILED",
                    "the cancelled invocation did not reach its terminal record");
        }
        Throwable failure = errorRef.get();
        if (failure != null) {
            throw new ContainedProcessBroker.ContainmentException("CANCEL_FLOW_FAILED",
                    "the cancelled invocation failed: " + failure);
        }
        ContainedProcessBroker.ProcessResult result = resultRef.get();
        if (result == null) {
            throw new ContainedProcessBroker.ContainmentException("CANCEL_FLOW_FAILED",
                    "the cancelled invocation produced no result");
        }
        System.out.println("LIVE CANCEL invocationId=" + result.invocationId
                + " exitCode=" + result.exitCode + " elapsedMs=" + result.elapsedMs
                + " failureToken=" + result.failureToken
                + " disposition=" + result.disposition
                + " reapCount=" + result.reapCount + " adoptCount=" + result.adoptCount);
        if (result.invocationId != invocationId
                || !result.disposition.equals(ContainedProcessBroker.CLEAN_CANCELLED)
                || !result.failureToken.equals(ContainedProcessBroker.TOKEN_NONE)
                || !result.containmentClean()) {
            throw new ContainedProcessBroker.ContainmentException("CANCEL_FLOW_FAILED",
                    "the accepted CANCEL did not end CLEAN cancelled: disposition="
                            + result.disposition + " failureToken=" + result.failureToken
                            + " invocationId=" + result.invocationId
                            + " expected " + invocationId);
        }
        /* The spawn-scan pass marker round-trip: a real contained
         * invocation whose OUTER record line in the P5 captured
         * report marks the passed live-window scan. */
        PreflightCoordinator.roundTrip(broker, "spawn-scan-live", Arrays.asList("/bin/true"));
        return invocationId;
    }

    /**
     * The quiet-stream session end: the broker stream must be quiet at
     * session end — no buffered or pending record bytes on the socket
     * and no EOF yet. A {@code PROTOCOL_ERROR} / {@code AUTH_FAILED} /
     * {@code BROKER_STALLED} event on the outer would have closed the
     * broker, and a record-level rejection would have left a pending
     * record, so a quiet stream plus every completed round-trip is the
     * client-side proof that no such token reached this session. The P5
     * shell then greps the outer's final report for the same token
     * lines (tools/preflight-lib.sh, unchanged).
     */
    private static void requireQuietStreamEnd(ContainedProcessBroker broker) {
        if (!broker.streamQuiet()) {
            throw new ContainedProcessBroker.ContainmentException("LIVE_BROKER_FAILED",
                    "the broker stream carried unexpected data at session end — a "
                            + "PROTOCOL_ERROR / AUTH_FAILED / BROKER_STALLED event would "
                            + "have closed the channel or left a pending record");
        }
    }

    /** A well-formed 32-char lowercase-hex nonce that differs from the
     * invocation nonce (the first hex digit toggled) — the scripted
     * wrong nonce for the ACK/CANCEL rejection observations. */
    private static String wrongNonceFor(String nonce) {
        char first = nonce.charAt(0);
        char flipped = first == '0' ? '1' : '0';
        return flipped + nonce.substring(1);
    }
}
