package deal.test;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
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
 * {@code setsid} when available), give it FIFOs for stdout and stderr,
 * and drain both read ends concurrently with a 1 MiB per-stream cap plus
 * the launcher's truncation marker (draining to EOF past the cap, so a
 * saturated child never deadlocks), under one monotonic deadline that
 * covers both the direct child and the drainage of both streams. The
 * helper owns the FIFO read ends directly, so capture completeness is the
 * kernel's EOF — every write end closed — and never the JVM's pipe
 * reclamation at the direct child's exit: a descendant that outlives the
 * direct child keeps its inherited write end open, the drains stay blocked
 * in read, and its later output is captured instead of being dropped. A
 * descendant that still holds the write ends at the deadline therefore
 * ends the invocation with the named hard failure, and the cleanup
 * terminates the descendants, the owned process group, and the direct
 * child, then drains and reaps inside its own bounded window.
 * {@link BoundedProcessTimeoutException} is an
 * {@link AssertionError}, never a skip. A nonzero child exit is reported
 * verbatim. EOF is mandatory: every reader failure is recorded, and a
 * completed child whose capture did not reach a clean EOF on both streams
 * is refused with the named hard {@code BOUNDED_PROCESS_DRAIN_FAILURE}
 * failure instead of a partially empty transcript.</p>
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
    private static final String MKFIFO_BINARY =
        firstExecutable("/usr/bin/mkfifo", "/bin/mkfifo");

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
        if (MKFIFO_BINARY == null) {
            throw new IOException("the bounded-process capture requires the"
                + " mkfifo utility for its kernel-EOF stream transport");
        }
        boolean ownGroup = SETSID_BINARY != null;
        List<String> command = new ArrayList<>();
        if (ownGroup) {
            // The child leads its own process group, so a descendant that
            // outlives the direct child stays addressable by the group kill
            // instead of being reparented out of the helper's reach.
            command.add(SETSID_BINARY);
        }
        command.addAll(argv);
        Path captureDir = Files.createTempDirectory("bounded-process-");
        Path fifoOut = captureDir.resolve("stdout.fifo");
        Path fifoErr = captureDir.resolve("stderr.fifo");
        Drain stdout = new Drain(fifoOut);
        Drain stderr = new Drain(fifoErr);
        Process process = null;
        Thread stdoutThread = null;
        Thread stderrThread = null;
        boolean[] interrupted = {false};
        boolean finished = false;
        boolean drained = false;
        long deadlineNanos = System.nanoTime()
            + TimeUnit.MILLISECONDS.toNanos(budgetMs);
        try {
            makeFifos(fifoOut, fifoErr);
            ProcessBuilder builder = new ProcessBuilder(command);
            if (workingDir != null) {
                builder.directory(workingDir.toFile());
            }
            if (environment != null) {
                builder.environment().putAll(environment);
            }
            // Kernel EOF, never the JVM's pipe reclamation: the child's
            // streams are FIFOs whose read ends this JVM holds directly, so
            // the capture reaches EOF only when the child and every process
            // that inherited its write ends have closed them. A descendant
            // that outlives the direct child therefore keeps the capture
            // open — its later output is read, never dropped — while the
            // one deadline still bounds the wait.
            builder.redirectOutput(fifoOut.toFile());
            builder.redirectError(fifoErr.toFile());
            // The readers open the FIFOs first (a read open blocks until a
            // writer appears; the spawn's write open blocks until a reader
            // appears), then the child runs and the same deadline covers it
            // and the drainage of both streams.
            stdoutThread = startDrain(stdout, "bounded-process-stdout");
            stderrThread = startDrain(stderr, "bounded-process-stderr");
            process = builder.start();
            finished = waitForChild(process, deadlineNanos, interrupted);
            drained = awaitDrains(stdoutThread, stderrThread, deadlineNanos,
                interrupted);
        } finally {
            // The finally owns the whole tree before any outcome propagates:
            // descendants, the owned process group, the direct child, then
            // the readers' EOF, a bounded reap, and the capture files.
            if (process != null) {
                terminateTree(process, ownGroup, interrupted);
            }
            unblockAbandonedReader(stdoutThread, stdout, fifoOut);
            unblockAbandonedReader(stderrThread, stderr, fifoErr);
            awaitDrains(stdoutThread, stderrThread, System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(POST_KILL_DRAIN_BUDGET_MS),
                interrupted);
            if (process != null) {
                reap(process, interrupted);
            }
            deleteCapture(captureDir);
        }
        if (interrupted[0]) {
            Thread.interrupted();
            throw new InterruptedException(
                "interrupted while waiting for " + argv.get(0));
        }
        if (!finished) {
            throw timeout(argv.get(0),
                captureFailure(argv.get(0), stdout, stderr));
        }
        if (!drained) {
            // The canonical message stays the bare token; the drainage
            // detail is diagnostic context, not part of the contract. A
            // descendant still holding the inherited FIFO write ends at the
            // deadline is this shape: the readers cannot reach EOF, so the
            // capture is incomplete and the run is a hard failure — never
            // the partial prefix as a transcript.
            System.err.println("bounded-process: the stream drainage "
                + "exceeded the child deadline for " + argv.get(0));
            throw timeout(argv.get(0),
                captureFailure(argv.get(0), stdout, stderr));
        }
        // Kernel EOF is mandatory: a drain reaches EOF only when every write
        // end of the FIFO is closed, so a completed child whose capture did
        // not reach a clean EOF on both streams (a reader failure or an
        // abandoned read end) is a failed invocation, never a partially
        // empty observation (the fail-closed drain contract).
        AssertionError incomplete = captureFailure(argv.get(0), stdout, stderr);
        if (incomplete != null) {
            throw incomplete;
        }
        return new BoundedResult(process.exitValue(), stdout.text(), stderr.text(),
            stdout.truncated(), stderr.truncated());
    }

    /** Creates the two capture FIFOs through the mkfifo utility; the
     * writers' open cannot block an unreachable reader when creation
     * fails. */
    private static void makeFifos(Path fifoOut, Path fifoErr)
            throws IOException {
        Process mkfifo = new ProcessBuilder(MKFIFO_BINARY,
            fifoOut.toString(), fifoErr.toString())
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start();
        boolean done;
        try {
            done = mkfifo.waitFor(REAP_BUDGET_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interruptedWait) {
            mkfifo.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while creating the capture "
                + "FIFOs " + fifoOut + " and " + fifoErr);
        }
        if (!done) {
            mkfifo.destroyForcibly();
            throw new IOException("the capture FIFOs " + fifoOut + " and "
                + fifoErr + " were not created inside the bounded window");
        }
        if (mkfifo.exitValue() != 0) {
            throw new IOException("mkfifo for the capture FIFOs " + fifoOut
                + " and " + fifoErr + " failed with exit "
                + mkfifo.exitValue());
        }
    }

    /**
     * Unblocks one drain that is stuck with no writer: when the spawn never
     * opened the FIFO's write end (a failed start), the reader's blocking
     * read open would otherwise wait forever. An O_RDWR open of a FIFO
     * never blocks, so it supplies the pending reader's missing counterpart
     * and then closes, and the reader finishes at EOF. A reader that is
     * already reading is unaffected (its FIFO has a writer, or the group
     * kill just closed one).
     */
    private static void unblockAbandonedReader(Thread thread, Drain drain,
            Path fifo) {
        if (thread == null || !thread.isAlive() || drain.eofReached()
                || drain.failure() != null) {
            return;
        }
        try (RandomAccessFile seed = new RandomAccessFile(fifo.toFile(),
                "rw")) {
            // The read-write open completes immediately even when no other
            // end exists; closing it leaves the pending reader at EOF once
            // no other writer remains.
        } catch (IOException | RuntimeException ignored) {
            // The reader join below stays bounded; an unlinked FIFO is no
            // longer a writer-blocked read.
        }
    }

    /** Removes one invocation's capture FIFOs and directory (best effort:
     * an already-unlinked capture is gone). */
    private static void deleteCapture(Path captureDir) {
        try (DirectoryStream<Path> entries =
                Files.newDirectoryStream(captureDir)) {
            for (Path entry : entries) {
                Files.deleteIfExists(entry);
            }
        } catch (IOException ignored) {
            // best effort
        }
        try {
            Files.deleteIfExists(captureDir);
        } catch (IOException ignored) {
            // best effort
        }
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

    /** The named timeout failure; when the timeout is primary and the
     * capture also fell short, the capture detail is attached. */
    private static BoundedProcessTimeoutException timeout(String child,
            AssertionError capture) {
        BoundedProcessTimeoutException timeout =
            new BoundedProcessTimeoutException(
                "BOUNDED_PROCESS_TIMEOUT " + child);
        if (capture != null) {
            timeout.addSuppressed(capture);
        }
        return timeout;
    }

    /**
     * The clean-EOF refusal: {@code null} when both streams reached EOF
     * without a read failure, otherwise the named hard
     * {@code BOUNDED_PROCESS_DRAIN_FAILURE}. Package-visible with
     * {@link Drain} so the registered harness battery exercises the same
     * decision the runner applies.
     */
    static AssertionError captureFailure(String child, Drain stdout, Drain stderr) {
        if (cleanEof(stdout) && cleanEof(stderr)) {
            return null;
        }
        return new AssertionError("BOUNDED_PROCESS_DRAIN_FAILURE " + child
            + " (stdout " + describe(stdout) + "; stderr " + describe(stderr)
            + ")");
    }

    /** One stream's capture completeness: EOF reached, no read failure. */
    private static boolean cleanEof(Drain drain) {
        return drain.failure() == null && drain.eofReached();
    }

    private static String describe(Drain drain) {
        if (drain.failure() != null) {
            return String.valueOf(drain.failure());
        }
        return drain.eofReached() ? "clean EOF" : "no EOF";
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
     * means a drain was still alive at the deadline. A drain that was
     * never started (an early infrastructure failure) is already ended. */
    private static boolean awaitDrains(Thread stdoutThread, Thread stderrThread,
            long deadlineNanos, boolean[] interrupted) {
        boolean drained = true;
        for (Thread thread : new Thread[] {stdoutThread, stderrThread}) {
            if (thread == null) {
                continue;
            }
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

    /**
     * One stream's capped, drain-to-EOF capture. A reader failure is
     * recorded, never swallowed: the retained prefix is kept for
     * diagnostics, but the runner refuses the invocation unless the read
     * loop ended at a clean EOF. The input-stream constructor serves the
     * registered harness battery's direct decision checks; the FIFO-path
     * constructor defers the open into the drain thread, so the child's
     * write open rendezvous with a pending reader open instead of
     * blocking the runner. Package-visible with the decision helpers
     * above so the battery exercises the same state the runner consumes.
     */
    static final class Drain implements Runnable {
        private final InputStream stream;
        private final Path fifo;
        private final ByteArrayOutputStream kept = new ByteArrayOutputStream();
        private volatile boolean truncated;
        private volatile boolean eofReached;
        private volatile IOException failure;

        Drain(InputStream stream) {
            this.stream = stream;
            this.fifo = null;
        }

        /** The FIFO path: the read end is opened inside the drain thread,
         * whose blocking open is the writer's rendezvous. */
        Drain(Path fifo) {
            this.stream = null;
            this.fifo = fifo;
        }

        @Override
        public void run() {
            byte[] buffer = new byte[8192];
            try (InputStream in = stream != null ? stream
                    : new FileInputStream(fifo.toFile())) {
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
                eofReached = true;
            } catch (IOException failed) {
                // A reader failure is recorded and fails the invocation
                // after bounded teardown; it is never read as a complete
                // transcript.
                failure = failed;
            }
        }

        String text() {
            String prefix = kept.toString(StandardCharsets.UTF_8);
            return truncated ? prefix + TRUNCATION_MARKER : prefix;
        }

        boolean truncated() {
            return truncated;
        }

        /** The recorded reader failure, or {@code null} for a clean EOF. */
        IOException failure() {
            return failure;
        }

        /** Whether the read loop ended at EOF rather than on a failure. */
        boolean eofReached() {
            return eofReached;
        }
    }
}
