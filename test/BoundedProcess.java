package deal.test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The harness's bounded subprocess helper (the bounded-subprocesses
 * contract's JVM side): every child a gate-executed test class spawns runs
 * through this surface, so no child can wedge a suite and no stream can
 * grow without bound.
 *
 * <p>Contract: start the child in its own process group (through
 * {@code setsid} when available), drain both streams concurrently with a
 * 1 MiB per-stream cap plus the launcher's truncation marker (draining to
 * EOF past the cap, so a saturated child never deadlocks on a full pipe),
 * and let one monotonic deadline cover both the direct child and the
 * drainage of both streams. A child that exits while a descendant keeps
 * the inherited output pipes open therefore also ends the invocation with
 * the named hard failure: the cleanup terminates the descendants, the
 * owned process group, and the direct child, then drains and reaps inside
 * its own bounded window. {@link BoundedProcessTimeoutException} is an
 * {@link AssertionError}, never a skip. A nonzero child exit is reported
 * verbatim.</p>
 */
public final class BoundedProcess {

    /** The per-stream retained-output cap. */
    public static final int STREAM_CAP_BYTES = 1 << 20;

    /** The launcher's verbatim truncation marker, appended at the cut point. */
    public static final String TRUNCATION_MARKER = "\n[STREAM TRUNCATED at 1 MiB]\n";

    /** The canonical per-child deadline. */
    public static final long SUBPROCESS_BUDGET_MS = 300_000L;

    /**
     * The bounded cleanup window after termination: both drains are joined
     * to EOF and the direct child is reaped inside this window even when
     * the deadline path was taken.
     */
    static final long POST_KILL_DRAIN_BUDGET_MS = 5_000L;

    /** The bounded reap window (a kill that did not land must not wedge). */
    static final long REAP_BUDGET_MS = 5_000L;

    private static final String SETSID_BINARY =
        firstExecutable("/usr/bin/setsid", "/bin/setsid");
    private static final String KILL_BINARY =
        firstExecutable("/bin/kill", "/usr/bin/kill");

    private BoundedProcess() {
    }

    /** One child's exit code and its capped, separately captured streams. */
    public record BoundedResult(int exitCode, String stdout, String stderr,
                                boolean stdoutTruncated, boolean stderrTruncated) {
    }

    /** The hard failure of the timeout path: never a skip, never a value. */
    public static final class BoundedProcessTimeoutException extends AssertionError {
        private static final long serialVersionUID = 1L;

        public BoundedProcessTimeoutException(String message) {
            super(message);
        }
    }

    /** Runs one child with the canonical budget, captured and capped. */
    public static BoundedResult runCaptured(List<String> argv)
            throws IOException, InterruptedException {
        return runCaptured(argv, null, Map.of(), SUBPROCESS_BUDGET_MS);
    }

    /** Runs one child in a working directory with the canonical budget. */
    public static BoundedResult runCaptured(List<String> argv, Path workingDir)
            throws IOException, InterruptedException {
        return runCaptured(argv, workingDir, Map.of(), SUBPROCESS_BUDGET_MS);
    }

    /** Runs one child with environment overrides and the canonical budget. */
    public static BoundedResult runCaptured(List<String> argv, Path workingDir,
            Map<String, String> environment)
            throws IOException, InterruptedException {
        return runCaptured(argv, workingDir, environment, SUBPROCESS_BUDGET_MS);
    }

    /**
     * The reduced-budget variant, package-visible for the harness's own
     * timeout mechanics checks; every release-run call site uses the
     * canonical budget.
     */
    static BoundedResult runCaptured(List<String> argv, Path workingDir,
            Map<String, String> environment, long budgetMs)
            throws IOException, InterruptedException {
        boolean ownGroup = SETSID_BINARY != null;
        List<String> command = new ArrayList<>();
        if (ownGroup) {
            // The child leads its own process group, so a descendant that
            // outlives the direct child stays addressable by the group kill
            // instead of being reparented out of the helper's reach.
            command.add(SETSID_BINARY);
        }
        command.addAll(argv);
        ProcessBuilder builder = new ProcessBuilder(command);
        if (workingDir != null) {
            builder.directory(workingDir.toFile());
        }
        if (environment != null) {
            builder.environment().putAll(environment);
        }
        long deadlineNanos = System.nanoTime()
            + TimeUnit.MILLISECONDS.toNanos(budgetMs);
        Process process = builder.start();
        Drain stdout = new Drain(process.getInputStream());
        Drain stderr = new Drain(process.getErrorStream());
        Thread stdoutThread = startDrain(stdout, "bounded-process-stdout");
        Thread stderrThread = startDrain(stderr, "bounded-process-stderr");
        boolean[] interrupted = {false};
        boolean finished = false;
        boolean drained = false;
        try {
            finished = waitForChild(process, deadlineNanos, interrupted);
            // The same deadline bounds the child and its stream drainage: a
            // descendant holding the inherited pipes ends the invocation
            // with the named timeout, never with an unbounded delay.
            drained = awaitDrains(stdoutThread, stderrThread, deadlineNanos,
                interrupted);
        } finally {
            // The finally owns the whole tree before any outcome propagates:
            // descendants, the owned process group, the direct child, then a
            // drain and a reap inside their own bounded windows.
            terminateTree(process, ownGroup, interrupted);
            awaitDrains(stdoutThread, stderrThread, System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(POST_KILL_DRAIN_BUDGET_MS),
                interrupted);
            reap(process, interrupted);
        }
        if (interrupted[0]) {
            Thread.interrupted();
            throw new InterruptedException(
                "interrupted while waiting for " + argv.get(0));
        }
        if (!finished) {
            throw new BoundedProcessTimeoutException(
                "BOUNDED_PROCESS_TIMEOUT " + argv.get(0));
        }
        if (!drained) {
            // The canonical message stays the bare token; the drainage
            // detail is diagnostic context, not part of the contract.
            System.err.println("bounded-process: the stream drainage "
                + "exceeded the child deadline for " + argv.get(0));
            throw new BoundedProcessTimeoutException(
                "BOUNDED_PROCESS_TIMEOUT " + argv.get(0));
        }
        return new BoundedResult(process.exitValue(), stdout.text(), stderr.text(),
            stdout.truncated(), stderr.truncated());
    }

    /** A representative version probe: runs the child, discards its output. */
    public static int probe(List<String> argv)
            throws IOException, InterruptedException {
        return runCaptured(argv).exitCode();
    }

    private static String firstExecutable(String... candidates) {
        for (String candidate : candidates) {
            if (Files.isExecutable(Path.of(candidate))) {
                return candidate;
            }
        }
        return null;
    }

    private static Thread startDrain(Drain drain, String name) {
        Thread thread = new Thread(drain, name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    /** Waits for the direct child inside the one deadline; {@code false}
     * means the deadline expired. */
    private static boolean waitForChild(Process process, long deadlineNanos,
            boolean[] interrupted) {
        long remaining = deadlineNanos - System.nanoTime();
        if (remaining <= 0) {
            return false;
        }
        try {
            return process.waitFor(remaining, TimeUnit.NANOSECONDS);
        } catch (InterruptedException interruptedWait) {
            interrupted[0] = true;
            return false;
        }
    }

    /** Joins both capped drains to EOF inside the deadline; {@code false}
     * means a drain was still alive at the deadline. */
    private static boolean awaitDrains(Thread stdoutThread, Thread stderrThread,
            long deadlineNanos, boolean[] interrupted) {
        boolean drained = true;
        for (Thread thread : new Thread[] {stdoutThread, stderrThread}) {
            while (thread.isAlive()) {
                long remaining = deadlineNanos - System.nanoTime();
                if (remaining <= 0) {
                    drained = false;
                    break;
                }
                try {
                    thread.join(Math.max(1L,
                        TimeUnit.NANOSECONDS.toMillis(remaining)));
                } catch (InterruptedException interruptedJoin) {
                    interrupted[0] = true;
                }
            }
        }
        return drained;
    }

    /** Terminates the owned tree: descendants first, then the child's process
     * group (when the helper created one), then the direct child. */
    private static void terminateTree(Process process, boolean ownGroup,
            boolean[] interrupted) {
        try {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
        } catch (RuntimeException ignored) {
            // A vanished subtree is already gone; the direct child is next.
        }
        if (ownGroup) {
            killProcessGroup(process.pid(), interrupted);
        }
        process.destroyForcibly();
    }

    /** Kills every remaining member of the child's own process group from
     * outside the group; the helper's own wait is bounded. */
    private static void killProcessGroup(long groupId, boolean[] interrupted) {
        List<String> argv = KILL_BINARY != null
            ? List.of(KILL_BINARY, "-KILL", "--", "-" + groupId)
            : List.of("bash", "-c", "kill -KILL -- -\"$1\"",
                "bounded-process-kill-group", Long.toString(groupId));
        try {
            ProcessBuilder killerBuilder = new ProcessBuilder(argv);
            killerBuilder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            killerBuilder.redirectError(ProcessBuilder.Redirect.DISCARD);
            Process killer = killerBuilder.start();
            boolean killed = false;
            try {
                killed = killer.waitFor(POST_KILL_DRAIN_BUDGET_MS,
                    TimeUnit.MILLISECONDS);
            } catch (InterruptedException interruptedKill) {
                interrupted[0] = true;
            }
            if (!killed) {
                killer.destroyForcibly();
            }
        } catch (Exception ignored) {
            // The direct child's destroyForcibly remains the backstop.
        }
    }

    /** Reaps the direct child inside a bounded window even when the waiting
     * thread is interrupted. */
    private static void reap(Process process, boolean[] interrupted) {
        long deadlineNanos = System.nanoTime()
            + TimeUnit.MILLISECONDS.toNanos(REAP_BUDGET_MS);
        while (process.isAlive()) {
            long remaining = deadlineNanos - System.nanoTime();
            if (remaining <= 0) {
                process.destroyForcibly();
                return;
            }
            try {
                if (process.waitFor(Math.max(1L,
                        TimeUnit.NANOSECONDS.toMillis(remaining)),
                        TimeUnit.MILLISECONDS)) {
                    return;
                }
            } catch (InterruptedException interruptedReap) {
                interrupted[0] = true;
            }
        }
    }

    /** One stream's capped, drain-to-EOF capture. */
    private static final class Drain implements Runnable {
        private final InputStream stream;
        private final ByteArrayOutputStream kept = new ByteArrayOutputStream();
        private volatile boolean truncated;

        Drain(InputStream stream) {
            this.stream = stream;
        }

        @Override
        public void run() {
            byte[] buffer = new byte[8192];
            try (InputStream in = stream) {
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    int room = STREAM_CAP_BYTES - kept.size();
                    int take = Math.min(Math.max(room, 0), read);
                    if (take > 0) {
                        kept.write(buffer, 0, take);
                    }
                    if (take < read) {
                        truncated = true;
                    }
                }
            } catch (IOException ignored) {
                // The stream closed under the cleanup path; the retained
                // prefix is still the result.
            }
        }

        String text() {
            String prefix = kept.toString(StandardCharsets.UTF_8);
            return truncated ? prefix + TRUNCATION_MARKER : prefix;
        }

        boolean truncated() {
            return truncated;
        }
    }
}
