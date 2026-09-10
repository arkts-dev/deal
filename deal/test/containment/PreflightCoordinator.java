package deal.test.containment;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Preflight coordinator (fail-closed-toolchain-preflight D4/D7): the
 * only post-readiness JVM owned by this epic's preflight, hosting the
 * P5 live broker session suite in-process (ISSUE-0561).
 *
 * <p>It connects to the inherited authenticated outer broker
 * ({@code DEALPG4_BROKER_PATH}/{@code DEALPG4_NONCE}), verifies the
 * {@code HELLO_OK} version and every expected capability bit
 * ({@link ContainedProcessBroker#EXPECTED_CAPABILITY_MASK}, the stage
 * bitmask the live outer advertises) before any {@code FEATURE_READY}
 * (a missing capability bit or a wrong version exits nonzero without
 * ever sending {@code FEATURE_READY} — the outer then fails
 * {@code READINESS_TIMEOUT} and the gate is nonzero), and additionally
 * asserts the observed bitmask equals 63 exactly, then runs the live
 * suite through the single inherited broker connection of its own P5
 * session — never a second JVM, never a second connection (the outer
 * accepts exactly one), never a launcher spawn (D7):
 *
 * <ol>
 *   <li>the live handshake assertion ({@code HELLO_OK} version exactly
 *       4, observed mask exactly 63 including bit 32 — a mask
 *       regression to 31 fails the handshake with
 *       {@code CAPABILITY_MISSING} before any {@code FEATURE_READY}
 *       and the gate goes nonzero);</li>
 *   <li>the live ordering negatives — duplicate {@code FEATURE_READY},
 *       {@code BYE} before {@code FEATURE_READY}, {@code INVOKE}
 *       before readiness, and {@code cancelLive} for an unknown or
 *       terminal id — each refused client-side
 *       ({@code PROTOCOL_ERROR} / {@code CANCEL_AUTH_FAILED}) before
 *       any write; the suite then proves the refusals never reached
 *       the socket by completing subsequent clean live round-trips
 *       and asserting the session stream stayed quiet (a
 *       {@code PROTOCOL_ERROR} / {@code AUTH_FAILED} /
 *       {@code BROKER_STALLED} event on the outer would have closed
 *       the broker or left a pending record), and the P5 shell greps
 *       the outer's final report for the same token lines
 *       (tools/preflight-lib.sh, unchanged);</li>
 *   <li>the live round-trips (the preserved pair {@code luajit -v} and
 *       {@code /bin/true}) through the real outer and real nested
 *       supervisors, each {@link ContainedProcessBroker.ProcessResult}
 *       asserted field-by-field — exitCode 0, failureToken {@code -},
 *       disposition CLEAN success, elapsedMs &gt; 0, non-negative
 *       reapCount/adoptCount — real records relayed by the real outer
 *       (no scripted peer);</li>
 *   <li>the live nonce-bound {@code CANCEL}: one long-running benign
 *       invocation ({@code /bin/sh -c "trap 'exit 0' TERM; sleep 30"},
 *       bounded by its own 45 s native deadline) with a concurrent
 *       {@link ContainedProcessBroker#cancelLive(long)} carrying the
 *       exact registry nonce (the nonce retained from the live
 *       {@code STUB_READY} relay — the invocation nonce the outer
 *       generated at registration, the only nonce it accepts) — the
 *       record ends CLEAN cancelled (the trap-and-exit target dies
 *       {@code CLD_EXITED} under the cancel TERM, the canonical
 *       clean-cancel derivation; a plain {@code sleep 30} would
 *       classify {@code FAILED CALLER_LOST} and fail the gate) and
 *       the outer's final report shows the record clean
 *       cancelled;</li>
 *   <li>the D7 spawn scan — while the live invocation is in flight and
 *       again after the round-trips: {@code /proc}/* enumeration
 *       identifying every process whose executable is the launcher
 *       binary (exe/comm check against
 *       {@code tools/deal-process-launcher-linux-x86_64}), classified
 *       by ppid-chain walk (field 4 of {@code /proc/&lt;pid&gt;/stat}):
 *       the process whose pid is the coordinator's parent (the outer
 *       itself) is allowed; every other launcher process is allowed
 *       only if its ppid chain reaches the outer's pid without
 *       passing through the coordinator's pid; a chain that passes
 *       through the coordinator pid (any Java-spawned launcher) or
 *       reaches pid 1 without the outer is a violation. The scan MUST
 *       observe the outer and at least one nested serve supervisor
 *       during the live window (anti-hollow — a scan that sees
 *       nothing fails) and MUST find zero Java-spawned launcher
 *       processes; any violation prints
 *       {@code SPAWN_SCAN_FAILED <pid>} on stderr and the suite exits
 *       nonzero. Each passed scan is followed by a real contained
 *       marker round-trip whose {@code OUTER record} line carries the
 *       spawn-scan pass marker into the P5 captured output;</li>
 *   <li>the session end: the suite asserts the broker stream is quiet,
 *       closes the broker, and exits 0 (the BYE-less clean exit per
 *       outer-coordinator-and-broker Verification 2) — the outer
 *       reaps the coordinator with status 0, every record is
 *       terminal, the final proof passes, and the outer exits 0.</li>
 * </ol>
 *
 * <p>Every request goes through the inherited broker: this class never
 * spawns the launcher, an outer, or any nested supervisor (D7), and it
 * supplies no FFI/library capability probe content (the E13 extension
 * slot stays empty). Any failure — a handshake failure, a capability
 * omission, a broker close, a {@code REJECT} reason token, a malformed
 * or unexpected record, a non-clean round-trip, a failed CANCEL flow,
 * or a spawn-scan violation — prints its named token
 * ({@code LIVE_BROKER_FAILED} / {@code ROUNDTRIP_FAILED} /
 * {@code SPAWN_SCAN_FAILED <pid>} / {@code CANCEL_FLOW_FAILED}) on
 * stderr and exits nonzero; the outer then classifies the coordinator
 * reaped-nonzero ({@code COORDINATOR_LOST}) and P5 fails the gate —
 * nothing is retried, skipped, or downgraded.
 */
public final class PreflightCoordinator {

    /** The launcher artifact path relative to the repository root
     * (the coordinator cwd) — the spawn scan's exe identity. */
    private static final String LAUNCHER_RELATIVE_PATH =
            "tools/deal-process-launcher-linux-x86_64";
    /** The kernel's 15-character comm truncation of
     * "deal-process-launcher-linux-x86_64" — the spawn scan's comm
     * fallback identity when /proc/&lt;pid&gt;/exe is unreadable. */
    private static final String LAUNCHER_COMM = "deal-process-la";
    /** An invocationId no registry can hold (cancelLive negative). */
    private static final long UNKNOWN_INVOCATION_ID = 999999999L;

    private PreflightCoordinator() {
        /* Static entry point only. */
    }

    /**
     * Runs the preflight live broker session suite against the
     * inherited broker and exits 0 on clean success, nonzero on any
     * failure (named token on stderr).
     */
    public static void main(String[] args) {
        int status;
        try {
            status = runPreflight();
        } catch (ContainedProcessBroker.ContainmentException e) {
            System.err.println(e.token() + ": " + e.getMessage());
            status = 1;
        } catch (RuntimeException e) {
            System.err.println("LIVE_BROKER_FAILED: " + e);
            status = 1;
        }
        System.exit(status);
    }

    private static int runPreflight() {
        try (ContainedProcessBroker broker = ContainedProcessBroker.connect()) {
            /* connect() already verified HELLO_OK version=4 and every
             * capability bit; a failure there exited before any
             * FEATURE_READY. */
            liveSession(broker);
            /* Short-run clean session end: close without BYE. The
             * outer's frame pin is DONE -> BYE, and DONE is emitted
             * only at the 14:40 INVOKE cutoff; the outer's clean-exit
             * discrimination (Verification 2) accepts a coordinator
             * reaped with status 0 and every record terminal at
             * broker EOF. The try-with-resources close below delivers
             * the EOF. */
            return 0;
        }
    }

    /**
     * The live broker session suite, in order: the handshake
     * assertion, the ordering negatives, the preserved round-trip
     * pair, the nonce-bound CANCEL flow with the in-flight spawn
     * scan, the terminal-id cancelLive negative, the post-round-trip
     * spawn scan, and the quiet-stream session end. Every step is an
     * assertion against the live outer's real records — no canned
     * responses.
     */
    private static void liveSession(ContainedProcessBroker broker) {
        liveHandshakeAssertion(broker);
        liveOrderingNegatives(broker);
        /* The live round-trips: real records relayed by the real outer
         * through real nested supervisors (the preserved pair). */
        roundTrip(broker, "luajit-version", Arrays.asList("luajit", "-v"));
        roundTrip(broker, "bin-true", Arrays.asList("/bin/true"));
        long cancelledId = liveCancelFlow(broker);
        /* The terminal-id cancelLive negative: the terminal record
         * cleared the retained nonce, so the CANCEL is refused
         * client-side with CANCEL_AUTH_FAILED before any write. */
        expectRefused("cancelLive for the terminal record", "CANCEL_AUTH_FAILED",
                () -> broker.cancelLive(cancelledId));
        /* The D7 spawn scan again after the round-trips (second pass:
         * zero Java-spawned launcher processes, the outer observed). */
        liveSpawnScan(false);
        /* The post-scan marker round-trip: a real contained invocation
         * whose OUTER record line carries the spawn-scan pass marker
         * into the P5 captured output. */
        roundTrip(broker, "spawn-scan-final", Arrays.asList("/bin/true"));
        /* Session end: the stream must be quiet — no buffered or
         * pending record and no EOF — the client-side proof that no
         * PROTOCOL_ERROR / AUTH_FAILED / BROKER_STALLED token reached
         * this session (each would have closed the broker; a
         * record-level rejection would have left a pending REJECT).
         * The P5 shell then greps the outer's final report for the
         * same token lines (tools/preflight-lib.sh, unchanged). */
        if (!broker.streamQuiet()) {
            throw new ContainedProcessBroker.ContainmentException("LIVE_BROKER_FAILED",
                    "the broker stream carried unexpected data at session end — a "
                            + "PROTOCOL_ERROR / AUTH_FAILED / BROKER_STALLED event would "
                            + "have closed the channel or left a pending record");
        }
        System.out.println("LIVE SESSION CLEAN: broker stream quiet at session end; "
                + "every record terminal CLEAN success or clean cancelled; no "
                + "PROTOCOL_ERROR / AUTH_FAILED / BROKER_STALLED token reached the "
                + "session, so the outer's final report carries none of them");
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
     * The live ordering negatives: duplicate {@code FEATURE_READY},
     * {@code BYE} before {@code FEATURE_READY}, {@code INVOKE} before
     * readiness, and {@code cancelLive} for an unknown/terminal id are
     * each refused client-side ({@code PROTOCOL_ERROR} /
     * {@code CANCEL_AUTH_FAILED}) before any write; the subsequent
     * clean live round-trips then prove the refusals never reached
     * the socket.
     */
    private static void liveOrderingNegatives(ContainedProcessBroker broker) {
        /* Pre-readiness refusals (client-side, before any write). */
        expectRefused("BYE before FEATURE_READY", "PROTOCOL_ERROR", broker::bye);
        expectRefused("INVOKE before FEATURE_READY", "PROTOCOL_ERROR", () ->
                broker.run("preflight", "ordering-negative",
                        Arrays.asList("luajit", "-v"), System.getProperty("user.dir"),
                        ContainedProcessBroker.Limits.DEFAULTS));
        expectRefused("cancelLive for an unknown id before FEATURE_READY",
                "PROTOCOL_ERROR", () -> broker.cancelLive(UNKNOWN_INVOCATION_ID));
        broker.featureReady();
        /* Post-readiness refusals. */
        expectRefused("duplicate FEATURE_READY", "PROTOCOL_ERROR", broker::featureReady);
        expectRefused("cancelLive for an unknown id", "CANCEL_AUTH_FAILED",
                () -> broker.cancelLive(UNKNOWN_INVOCATION_ID));
        System.out.println("LIVE ORDERING NEGATIVES PASS: every refusal was client-side "
                + "(PROTOCOL_ERROR / CANCEL_AUTH_FAILED) before any write; the subsequent "
                + "clean live round-trips prove none of them reached the socket");
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
     * One live round-trip through the real outer and a real nested
     * supervisor, asserted field-by-field: exitCode 0, failureToken
     * {@code -}, disposition CLEAN success, elapsedMs &gt; 0, and
     * non-negative reapCount/adoptCount.
     */
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
        if (result.exitCode != 0
                || !result.failureToken.equals(ContainedProcessBroker.TOKEN_NONE)
                || !result.disposition.equals(ContainedProcessBroker.CLEAN_SUCCESS)
                || result.elapsedMs <= 0
                || result.reapCount < 0
                || result.adoptCount < 0) {
            throw new ContainedProcessBroker.ContainmentException("ROUNDTRIP_FAILED",
                    "round-trip '" + phase + "' was not clean: exitCode=" + result.exitCode
                            + " failureToken=" + result.failureToken
                            + " disposition=" + result.disposition
                            + " elapsedMs=" + result.elapsedMs
                            + " reapCount=" + result.reapCount
                            + " adoptCount=" + result.adoptCount);
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
        liveSpawnScan(true);

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
        roundTrip(broker, "spawn-scan-live", Arrays.asList("/bin/true"));
        return invocationId;
    }

    /**
     * The D7 spawn scan: enumerates {@code /proc/*} and identifies
     * every process whose executable is the launcher binary (the
     * {@code /proc/&lt;pid&gt;/exe} resolution against
     * {@code tools/deal-process-launcher-linux-x86_64}, with the
     * {@code deal-process-la} comm as the unreadable-exe fallback).
     * Classification by ppid-chain walk (field 4 of
     * {@code /proc/&lt;pid&gt;/stat}): the process whose pid is the
     * coordinator's parent pid (the outer itself) is allowed; every
     * other launcher process is allowed only if its ppid chain
     * reaches the outer's pid without passing through the
     * coordinator's pid; a chain that passes through the coordinator
     * pid (any Java-spawned launcher) or reaches pid 1 without the
     * outer is a violation. The scan MUST observe the outer and, in
     * the live window ({@code requireLiveNestedSupervisor}), at least
     * one nested serve supervisor (a launcher process whose ppid is
     * the outer) — a vacuous scan fails. Any violation prints
     * {@code SPAWN_SCAN_FAILED <pid>} on stderr and the suite exits
     * nonzero.
     */
    private static void liveSpawnScan(boolean requireLiveNestedSupervisor) {
        long coordinatorPid = ProcessHandle.current().pid();
        ProcessHandle outerHandle = ProcessHandle.current().parent().orElse(null);
        long outerPid = outerHandle == null ? -1 : outerHandle.pid();
        if (outerPid < 1) {
            throw new ContainedProcessBroker.ContainmentException("SPAWN_SCAN_FAILED -1",
                    "the coordinator has no observable parent (not running under the "
                            + "outer)");
        }
        Path launcher;
        try {
            launcher = Path.of(System.getProperty("user.dir"),
                    LAUNCHER_RELATIVE_PATH).toRealPath();
        } catch (IOException e) {
            throw new ContainedProcessBroker.ContainmentException("SPAWN_SCAN_FAILED -1",
                    "cannot resolve the launcher binary path: " + e);
        }

        Map<Long, Long> ppidByPid = new HashMap<>();
        List<Long> launcherPids = new ArrayList<>();
        try (DirectoryStream<Path> proc = Files.newDirectoryStream(Path.of("/proc"))) {
            for (Path entry : proc) {
                String name = entry.getFileName().toString();
                if (!isAllDigits(name)) {
                    continue;
                }
                long pid;
                try {
                    pid = Long.parseLong(name);
                } catch (NumberFormatException e) {
                    continue;
                }
                if (pid == coordinatorPid) {
                    continue; /* the coordinator JVM is java, not the launcher */
                }
                if (!isLauncherProcess(pid, launcher)) {
                    continue;
                }
                Long ppid = readPpid(pid);
                if (ppid == null) {
                    /* The process vanished between the exe
                     * identification and the stat read (it exited and
                     * was reaped): it no longer exists, so there is
                     * nothing to classify. The anti-hollow
                     * must-observes (the outer; the live serve
                     * supervisor during the live window) hold on
                     * stable processes and are enforced below. */
                    continue;
                }
                ppidByPid.put(pid, ppid);
                launcherPids.add(pid);
            }
        } catch (IOException e) {
            throw new ContainedProcessBroker.ContainmentException("SPAWN_SCAN_FAILED -1",
                    "cannot enumerate /proc: " + e);
        }

        boolean sawOuter = false;
        int nestedSupervisors = 0;
        for (long pid : launcherPids) {
            long ppid = ppidByPid.get(pid);
            if (pid == outerPid) {
                sawOuter = true; /* the outer itself: allowed by identity */
                continue;
            }
            if (ppid == outerPid) {
                nestedSupervisors++;
            }
            if (!chainReachesOuter(pid, ppid, outerPid, coordinatorPid)) {
                throw new ContainedProcessBroker.ContainmentException(
                        "SPAWN_SCAN_FAILED " + pid,
                        "launcher process " + pid + " is not an outer-forked supervisor: "
                                + "its ppid chain either passes through the coordinator "
                                + "(a Java-spawned launcher) or reaches pid 1 without "
                                + "the outer");
            }
        }
        if (!sawOuter) {
            throw new ContainedProcessBroker.ContainmentException(
                    "SPAWN_SCAN_FAILED " + outerPid,
                    "the spawn scan did not observe the outer (pid " + outerPid
                            + ") — a vacuous scan is a failure");
        }
        if (requireLiveNestedSupervisor && nestedSupervisors == 0) {
            throw new ContainedProcessBroker.ContainmentException(
                    "SPAWN_SCAN_FAILED " + outerPid,
                    "the live-window spawn scan observed no nested serve supervisor "
                            + "(no launcher process whose ppid is the outer) — the scan "
                            + "must really see the allowed outer-forked supervisors");
        }
        System.out.println("SPAWN SCAN PASS live=" + requireLiveNestedSupervisor
                + " coordinatorPid=" + coordinatorPid + " outerPid=" + outerPid
                + " launcherProcesses=" + launcherPids.size()
                + " nestedSupervisors=" + nestedSupervisors + " violations=0");
    }

    /**
     * Whether the launcher process {@code pid}'s ppid chain reaches
     * the outer's pid without passing through the coordinator's pid.
     * A chain that passes through the coordinator pid (any
     * Java-spawned launcher), reaches pid 1/0 without the outer, or
     * cannot be walked (unreadable ppid, cycle) is a violation.
     */
    private static boolean chainReachesOuter(long pid, long initialPpid,
                                             long outerPid, long coordinatorPid) {
        /* Start the walk at the ppid snapshot taken during the
         * enumeration — never re-read the leaf's own stat: the leaf
         * (e.g. the marker round-trip's serve supervisor) may be
         * reaped between the enumeration and the walk, and its
         * observed chain fact is the snapshot. Intermediates (a
         * supervisor's parent, an outer-forked chain) outlive their
         * leaves by construction. */
        if (initialPpid == outerPid) {
            return true; /* a direct outer fork: allowed without any read */
        }
        if (initialPpid == coordinatorPid) {
            return false; /* a Java-spawned launcher (the chain passes
                             through the coordinator) */
        }
        long cur = initialPpid;
        Set<Long> seen = new HashSet<>();
        seen.add(pid);
        while (true) {
            if (cur == outerPid) {
                return true; /* reached the outer without the coordinator */
            }
            if (cur == coordinatorPid) {
                return false; /* a Java-spawned launcher (chain passes through
                                 the coordinator) */
            }
            if (!seen.add(cur)) {
                return false; /* ppid cycle: never reaches the outer */
            }
            Long parent = readPpid(cur);
            if (parent == null) {
                return false; /* unclassifiable chain: fail closed */
            }
            if (parent <= 1) {
                return false; /* reached pid 1/0 without the outer */
            }
            cur = parent.longValue();
        }
    }

    /**
     * Whether the process {@code pid}'s executable is the launcher
     * binary: the primary check resolves {@code /proc/&lt;pid&gt;/exe}
     * against the canonical launcher path; when the exe link is
     * unreadable (the process exited or the link is transient), the
     * comm check against {@link #LAUNCHER_COMM} is the fallback. A
     * readable exe that resolves elsewhere is not the launcher.
     */
    private static boolean isLauncherProcess(long pid, Path launcher) {
        Path procExe = Path.of("/proc", String.valueOf(pid), "exe");
        try {
            Path exe = Files.readSymbolicLink(procExe);
            return exe.equals(launcher);
        } catch (IOException e) {
            /* exe unreadable: fall back to the comm identity. */
        }
        try {
            String comm = Files.readString(Path.of("/proc", String.valueOf(pid), "comm"))
                    .trim();
            return LAUNCHER_COMM.equals(comm);
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Reads field 4 (ppid) of {@code /proc/&lt;pid&gt;/stat}: the
     * fields after the last {@code ')'} are space-separated, starting
     * with the state (field 3), so the ppid is the second token after
     * the comm close-paren. Null when unreadable.
     */
    private static Long readPpid(long pid) {
        Path stat = Path.of("/proc", String.valueOf(pid), "stat");
        try {
            String content = Files.readString(stat);
            int close = content.lastIndexOf(')');
            if (close < 0) {
                return null;
            }
            String rest = content.substring(close + 1).trim();
            String[] fields = rest.split("\\s+");
            if (fields.length < 2) {
                return null;
            }
            try {
                return Long.valueOf(Long.parseLong(fields[1]));
            } catch (NumberFormatException e) {
                return null;
            }
        } catch (IOException e) {
            return null;
        }
    }

    private static boolean isAllDigits(String value) {
        if (value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }
}
