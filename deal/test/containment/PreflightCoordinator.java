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

/**
 * Preflight coordinator (fail-closed-toolchain-preflight D4/D7): the
 * only post-readiness JVM owned by this epic's preflight, hosting the
 * P5 live broker session suite in-process
 * (dealpg4-java-broker-session-tests D1, ISSUE-0561).
 *
 * <p>It connects to the inherited authenticated outer broker
 * ({@code DEALPG4_BROKER_PATH}/{@code DEALPG4_NONCE}), verifies the
 * {@code HELLO_OK} version and every expected capability bit
 * ({@link ContainedProcessBroker#EXPECTED_CAPABILITY_MASK}, the
 * post-flip bitmask the live outer advertises) before any
 * {@code FEATURE_READY} (a missing capability bit or a wrong version
 * exits nonzero without ever sending {@code FEATURE_READY} — the outer
 * then fails {@code READINESS_TIMEOUT} and the gate is nonzero), and
 * then runs the live broker session suite
 * ({@link BrokerSessionTestSuite}) on the single inherited broker
 * connection of its own P5 session — never a second JVM, never a
 * second connection (the outer accepts exactly one), never a launcher
 * spawn (D7). The suite, in order, runs the client-side ordering
 * negatives, invokes this coordinator's runnable preflight core (the
 * readiness chain and the preserved round-trip pair), and then
 * observes the single-connection rule, the record-level ACK/CANCEL
 * nonce rejections through the scripted-invocation seam, record
 * exchange (content, the 1 MiB cap path, a gate-clean nonzero-exit
 * target), the nonce-bound CANCEL flow, and the D7 spawn scans. A
 * non-clean suite status maps to a named token on stderr and a
 * nonzero coordinator exit; the outer classifies the coordinator
 * reaped-nonzero ({@code COORDINATOR_LOST}) and P5 fails the gate —
 * nothing is retried, skipped, or downgraded.
 *
 * <p>Every request goes through the inherited broker: this class never
 * spawns the launcher, an outer, or any nested supervisor (D7), and it
 * supplies no FFI/library capability probe content (the E13 extension
 * slot stays empty). Any failure — a handshake failure, a capability
 * omission, a broker close, a {@code REJECT} reason token, a malformed
 * or unexpected record, a non-clean round-trip, a failed CANCEL flow,
 * or a spawn-scan violation — surfaces as the suite's
 * {@code BROKER_SESSION_TEST_FAILED <token>} on stderr and a nonzero
 * exit.
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

    private PreflightCoordinator() {
        /* Static entry point only. */
    }

    /**
     * Runs the preflight live broker session against the inherited
     * broker and exits 0 on clean success, nonzero on any failure
     * (named token on stderr).
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
             * FEATURE_READY. The live broker session suite runs
             * in-process on the inherited connection (D1); a non-clean
             * suite status maps to a nonzero coordinator exit. */
            return BrokerSessionTestSuite.run(broker);
        }
    }

    /**
     * The coordinator's runnable preflight core (preflight D4;
     * package-private — the live suite invokes it on the live broker
     * and asserts its round-trip results): the readiness chain
     * {@code FEATURE_READY}/{@code READY_ACK} and the two preserved
     * round-trips ({@code luajit -v}, {@code /bin/true}), each asserted
     * field-by-field. This is the session's primary readiness/chain
     * proof; the live suite runs before and after it on the same
     * authenticated broker instance.
     */
    static void runCoordinatorCore(ContainedProcessBroker broker) {
        broker.featureReady();
        roundTrip(broker, "luajit-version", Arrays.asList("luajit", "-v"));
        roundTrip(broker, "bin-true", Arrays.asList("/bin/true"));
    }

    /**
     * One live round-trip through the real outer and a real nested
     * supervisor, asserted field-by-field: exitCode 0, failureToken
     * {@code -}, disposition CLEAN success, elapsedMs &gt; 0, and
     * non-negative reapCount/adoptCount. Package-private: the live
     * suite invokes it for the marker round-trips.
     */
    static void roundTrip(ContainedProcessBroker broker, String phase,
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
     * The D7 spawn scan (package-private — the live suite invokes it in
     * the live window and again after the round-trips): enumerates
     * {@code /proc/*} and identifies every process whose executable is
     * the launcher binary (the {@code /proc/&lt;pid&gt;/exe} resolution
     * against {@code tools/deal-process-launcher-linux-x86_64}, with
     * the {@code deal-process-la} comm as the unreadable-exe fallback).
     * Classification by ppid-chain walk (field 4 of
     * {@code /proc/&lt;pid&gt;/stat}): the process whose pid is the
     * coordinator's parent pid (the outer itself) is allowed; every
     * other launcher process is allowed only if its ppid chain reaches
     * the outer's pid without passing through the coordinator's pid; a
     * chain that passes through the coordinator pid (any Java-spawned
     * launcher) or reaches pid 1 without the outer is a violation. The
     * scan MUST observe the outer and, in the live window
     * ({@code requireLiveNestedSupervisor}), at least one nested serve
     * supervisor (a launcher process whose ppid is the outer) — a
     * vacuous scan fails. Any violation prints
     * {@code SPAWN_SCAN_FAILED <pid>} on stderr and the suite exits
     * nonzero.
     */
    static void liveSpawnScan(boolean requireLiveNestedSupervisor) {
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
