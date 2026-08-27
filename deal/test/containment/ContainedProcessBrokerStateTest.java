package deal.test.containment;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * Session-ordering, HELLO_OK version-class, and CANCEL-nonce regression
 * tests for the broker client (ISSUE-0182 review findings):
 *
 * <ul>
 *   <li>{@code featureReady()} and {@code bye()} enforce the same
 *       mutual ordering guards as {@code run()} and {@code cancel()}
 *       (requireSessionState), so the client can never emit
 *       out-of-order records against the canonical frame sequence
 *       (HELLO → HELLO_OK → FEATURE_READY → READY_ACK → repeated
 *       invocations → DONE → BYE): {@code bye()} before
 *       {@code featureReady()}, {@code featureReady()} after
 *       {@code bye()}, duplicate {@code featureReady()}/{@code bye()},
 *       and {@code run()}/{@code cancel()} before {@code
 *       FEATURE_READY} or after {@code BYE} are each refused
 *       client-side with {@code PROTOCOL_ERROR} before any write;</li>
 *   <li>the HELLO_OK version check accepts the leading-zero decimal
 *       form {@code "04"} exactly as the canonical {@code
 *       DEALPG4_F_VERSION_4} field class does (decimal text whose
 *       parsed value is exactly 4 — tools/src/protocol.c), verified
 *       over a live socket handshake;</li>
 *   <li>{@code run()} retains the {@code STUB_READY} nonce per live
 *       invocationId — exposed through {@link
 *       ContainedProcessBroker#invocationNonce(long)} during the live
 *       window and cleared at the terminal record — and {@link
 *       ContainedProcessBroker#cancelLive(String)} emits {@code CANCEL
 *       <invocationId> <nonce>} with exactly that retained nonce, so
 *       the required CANCEL flow for a live invocation is reachable;
 *       a cancelLive for a terminal or unknown id is refused
 *       client-side with {@code CANCEL_AUTH_FAILED} before any write
 *       (a guessed nonce is never emitted).</li>
 * </ul>
 *
 * <p>The transport is a real Unix-domain socket pair: the test binds a
 * {@link ServerSocketChannel} on a temporary socket path and runs a
 * scripted peer that answers the handshake and invocation records
 * exactly as the outer broker answers them ({@code HELLO_OK 4 63} for
 * the canonical version-4 capability bitmask, {@code READY_ACK
 * <nonce>} for {@code FEATURE_READY}, {@code INVOKED}/{@code
 * STARTED}/{@code REPORT}/{@code CLEAN} for a round-trip). This is a
 * test-only double for the peer side of the state machine; the live
 * end-to-end verification against the real {@code launcher outer}
 * chain is the issue's combined-dependency step and is not simulated
 * here. Every ordering refusal fires inside the client's writeLock
 * before any write, so the scripted peer also asserts that no record
 * ever reaches the socket for a refused call.
 */
public final class ContainedProcessBrokerStateTest {

    private static final String NONCE = "0123456789abcdef0123456789abcdef";
    private static final int EXPECTED_CAPS = 63;

    /** STUB_READY nonce scripted for the invocation scenarios. */
    private static final String STUB_NONCE = "aabbccddeeff00112233445566778899";
    /** INVOKED invocationId scripted for the invocation scenarios. */
    private static final long INVOCATION_ID = 7;
    /* buildClientTag sanitizes non-token characters, so the phase
     * "bin-true" becomes the catalog token "bin_true". */
    private static final String INVOKE_LINE =
            "DEALPG4 INVOKE preflight_bin_true 2f 1 2f62696e2f74727565";
    private static final String ACK_LINE = "DEALPG4 ACK 7 " + STUB_NONCE;
    private static final String CANCEL_LINE = "DEALPG4 CANCEL 7 " + STUB_NONCE;
    /** Canonical 19-field REPORT: exit 0, no signal, zero bytes per
     * stream (no OUT chunks were relayed, so the client's stream
     * accounting cross-check demands 0/0), all proofs clean. */
    private static final String REPORT_LINE =
            "DEALPG4 REPORT 0 0 1 2 3 4 5 6 7 8 9 0 0 0 0 1 1 1 -";

    private static int passed = 0;
    private static int failed = 0;

    private ContainedProcessBrokerStateTest() {
        /* Static test entry point only. */
    }

    private static void check(boolean condition, String message) {
        if (condition) {
            passed++;
        } else {
            failed++;
            System.err.println("FAIL: " + message);
        }
    }

    private static void checkProtocolError(String what, Runnable action) {
        try {
            action.run();
            check(false, what + ": out-of-order call was not refused");
        } catch (ContainedProcessBroker.ContainmentException e) {
            check(e.token().equals("PROTOCOL_ERROR"),
                    what + ": refused with token '" + e.token()
                            + "', expected PROTOCOL_ERROR (" + e + ")");
        }
    }

    /** Asserts a client-side refusal carrying the expected token. */
    private static void checkRefused(String what, String expectedToken, Runnable action) {
        try {
            action.run();
            check(false, what + ": was not refused");
        } catch (ContainedProcessBroker.ContainmentException e) {
            check(e.token().equals(expectedToken),
                    what + ": refused with token '" + e.token()
                            + "', expected " + expectedToken + " (" + e + ")");
        }
    }

    /**
     * Waits (bounded, 5 s) until the condition holds. Used to
     * synchronize on peer-side observations (bye() carries no response
     * record, and the concurrent CANCEL scenario polls the peer's ACK
     * observation).
     */
    private static boolean awaitCondition(BooleanSupplier condition, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return condition.getAsBoolean();
    }

    /**
     * Waits (bounded, 5 s) until the scripted peer has observed
     * {@code expectedCount} client records; bye() carries no response
     * record, so the test cannot synchronize on a client-side read.
     */
    private static boolean awaitLineCount(ScriptedPeer peer, int expectedCount) {
        return awaitCondition(() -> peer.failure == null
                && peer.seenLines.size() == expectedCount, 5000);
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder line = new StringBuilder();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') {
                return line.toString();
            }
            line.append((char) b);
        }
        return null;
    }

    private static void writeLine(OutputStream out, String line) throws IOException {
        out.write(line.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        out.write('\n');
        out.flush();
    }

    /**
     * Scripted broker peer: answers the canonical handshake records and
     * records every client-emitted record in {@code seenLines} so the
     * test can assert that refused calls wrote nothing.
     */
    private static final class ScriptedPeer implements Runnable {

        final ServerSocketChannel server;
        final List<String> seenLines = new java.util.concurrent.CopyOnWriteArrayList<>();
        final String helloOkVersion;
        volatile IOException failure;

        ScriptedPeer(ServerSocketChannel server) {
            this(server, "4");
        }

        ScriptedPeer(ServerSocketChannel server, String helloOkVersion) {
            this.server = server;
            this.helloOkVersion = helloOkVersion;
        }

        @Override
        public void run() {
            try (SocketChannel connection = server.accept()) {
                InputStream in = Channels.newInputStream(connection);
                OutputStream out = Channels.newOutputStream(connection);
                String line;
                while ((line = readLine(in)) != null) {
                    seenLines.add(line);
                    if (line.startsWith("DEALPG4 HELLO ")) {
                        writeLine(out, "DEALPG4 HELLO_OK " + helloOkVersion
                                + " " + EXPECTED_CAPS);
                    } else if (line.startsWith("DEALPG4 FEATURE_READY ")) {
                        writeLine(out, "DEALPG4 READY_ACK " + NONCE);
                    } else if (line.equals("DEALPG4 BYE")) {
                        writeLine(out, "DEALPG4 BYE");
                    } else {
                        /* Anything else is a client defect (an
                         * out-of-order or malformed emitted record). */
                        failure = new IOException(
                                "scripted peer: unexpected client record: " + line);
                        break;
                    }
                }
            } catch (IOException e) {
                /* The client may close the socket at any point; only
                 * record failures while the peer is still processing. */
                failure = e;
            }
        }
    }

    /**
     * Scripted invocation peer: answers the canonical handshake and
     * one full per-invocation record exchange
     * (INVOKE → INVOKED → [ACK] → STARTED → REPORT → CLEAN), so the
     * tests exercise the client's run() stream and nonce retention
     * over a real socket pair. In cancel mode the peer holds the
     * terminal record until it observes the client's CANCEL and then
     * answers {@code CLEAN cancelled}, proving the CANCEL carried the
     * retained STUB_READY nonce.
     */
    private static final class InvocationPeer implements Runnable {

        final ServerSocketChannel server;
        final List<String> seenLines = new java.util.concurrent.CopyOnWriteArrayList<>();
        final boolean cancelAfterReport;
        volatile IOException failure;
        volatile boolean ackSeen = false;
        volatile String ackLine = null;
        volatile String cancelLine = null;

        InvocationPeer(ServerSocketChannel server, boolean cancelAfterReport) {
            this.server = server;
            this.cancelAfterReport = cancelAfterReport;
        }

        @Override
        public void run() {
            try (SocketChannel connection = server.accept()) {
                InputStream in = Channels.newInputStream(connection);
                OutputStream out = Channels.newOutputStream(connection);
                String line;
                while ((line = readLine(in)) != null) {
                    seenLines.add(line);
                    if (line.startsWith("DEALPG4 HELLO ")) {
                        writeLine(out, "DEALPG4 HELLO_OK 4 " + EXPECTED_CAPS);
                    } else if (line.startsWith("DEALPG4 FEATURE_READY ")) {
                        writeLine(out, "DEALPG4 READY_ACK " + NONCE);
                    } else if (line.startsWith("DEALPG4 INVOKE ")) {
                        String tag = line.split(" ")[2];
                        writeLine(out, "DEALPG4 INVOKED " + INVOCATION_ID + " " + tag);
                        /* Canonical sequence: INVOKED then STUB_READY
                         * (identity verified by the outer) before the
                         * client's ACK. */
                        writeLine(out, "DEALPG4 STUB_READY " + INVOCATION_ID
                                + " 1234 1234 1234 " + STUB_NONCE);
                    } else if (line.startsWith("DEALPG4 ACK ")) {
                        ackLine = line;
                        ackSeen = true;
                        writeLine(out, "DEALPG4 STARTED " + INVOCATION_ID);
                        writeLine(out, REPORT_LINE);
                        if (!cancelAfterReport) {
                            writeLine(out, "DEALPG4 CLEAN " + INVOCATION_ID + " success");
                        }
                    } else if (line.startsWith("DEALPG4 CANCEL ")) {
                        cancelLine = line;
                        writeLine(out, "DEALPG4 CLEAN " + INVOCATION_ID + " cancelled");
                    } else if (line.equals("DEALPG4 BYE")) {
                        writeLine(out, "DEALPG4 BYE");
                    } else {
                        failure = new IOException(
                                "invocation peer: unexpected client record: " + line);
                        break;
                    }
                }
            } catch (IOException e) {
                failure = e;
            }
        }
    }

    private static void closeQuietly(ContainedProcessBroker broker, ServerSocketChannel server,
                                     Path socketPath, Path socketDir) {
        if (broker != null) {
            try {
                broker.close();
            } catch (RuntimeException e) {
                System.err.println("WARNING: broker close failed: " + e);
            }
        }
        if (server != null) {
            try {
                server.close();
            } catch (IOException ignored) {
                /* Best-effort cleanup. */
            }
        }
        try {
            Files.deleteIfExists(socketPath);
            Files.deleteIfExists(socketDir);
        } catch (IOException ignored) {
            /* Best-effort cleanup. */
        }
    }

    private static void runOrderingScenario() throws Exception {
        Path socketDir = Files.createTempDirectory("dealpg4-state-test");
        Path socketPath = socketDir.resolve("broker.sock");
        ContainedProcessBroker broker = null;
        ServerSocketChannel server = null;
        try {
            server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
            server.bind(UnixDomainSocketAddress.of(socketPath));
            ScriptedPeer peer = new ScriptedPeer(server);
            Thread peerThread = new Thread(peer, "scripted-broker-peer");
            peerThread.setDaemon(true);
            peerThread.start();

            final ContainedProcessBroker session =
                    ContainedProcessBroker.connect(socketPath.toString(), NONCE);
            broker = session;
            check(peer.seenLines.size() == 1 && peer.seenLines.get(0).equals("DEALPG4 HELLO " + NONCE),
                    "handshake: HELLO carried the coordinator nonce");
            check(session.coordinatorNonce().equals(NONCE),
                    "handshake: HELLO_OK version=4 and caps=" + EXPECTED_CAPS + " accepted");

            /* bye() before featureReady() → refused, nothing written. */
            checkProtocolError("bye before FEATURE_READY", session::bye);
            check(peer.seenLines.size() == 1,
                    "bye before FEATURE_READY: no record reached the socket");

            /* run()/cancel() before FEATURE_READY (existing
             * requireSessionState; locks the criterion regression). */
            checkProtocolError("INVOKE before FEATURE_READY", () ->
                    session.run("preflight", "bin-true",
                            Arrays.asList("/bin/true"), "/",
                            ContainedProcessBroker.Limits.DEFAULTS));
            checkProtocolError("CANCEL before FEATURE_READY", () ->
                    session.cancel("1", NONCE));
            check(peer.seenLines.size() == 1,
                    "run/cancel before FEATURE_READY: no record reached the socket");

            /* featureReady() succeeds; a duplicate is refused. */
            session.featureReady();
            check(peer.seenLines.size() == 2
                            && peer.seenLines.get(1).equals("DEALPG4 FEATURE_READY " + NONCE),
                    "featureReady: FEATURE_READY carried the coordinator nonce");
            checkProtocolError("duplicate FEATURE_READY", session::featureReady);
            check(peer.seenLines.size() == 2,
                    "duplicate FEATURE_READY: no record reached the socket");

            /* bye() succeeds once; then featureReady() after BYE is
             * refused (the new guard) and a duplicate bye() is refused. */
            session.bye();
            check(awaitLineCount(peer, 3) && peer.seenLines.get(2).equals("DEALPG4 BYE"),
                    "bye: BYE was emitted once");
            checkProtocolError("FEATURE_READY after BYE", session::featureReady);
            check(peer.seenLines.size() == 3,
                    "FEATURE_READY after BYE: no record reached the socket");
            checkProtocolError("duplicate BYE", session::bye);
            check(peer.seenLines.size() == 3,
                    "duplicate BYE: no record reached the socket");
            checkProtocolError("INVOKE after BYE", () ->
                    session.run("preflight", "bin-true",
                            Arrays.asList("/bin/true"), "/",
                            ContainedProcessBroker.Limits.DEFAULTS));

            check(peer.failure == null, "scripted peer saw only expected records: "
                    + (peer.failure == null ? "ok" : peer.failure));
            check(peer.seenLines.equals(Arrays.asList(
                            "DEALPG4 HELLO " + NONCE,
                            "DEALPG4 FEATURE_READY " + NONCE,
                            "DEALPG4 BYE")),
                    "exact emitted record sequence: " + peer.seenLines);
        } finally {
            closeQuietly(broker, server, socketPath, socketDir);
        }
    }

    /** Live handshake against a peer answering HELLO_OK with the
     * leading-zero decimal version "04" (canonical VERSION_4 field
     * class: decimal with value exactly 4 — accepted). */
    private static void runLeadingZeroVersionScenario() throws Exception {
        Path socketDir = Files.createTempDirectory("dealpg4-state-test-v04");
        Path socketPath = socketDir.resolve("broker.sock");
        ContainedProcessBroker broker = null;
        ServerSocketChannel server = null;
        try {
            server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
            server.bind(UnixDomainSocketAddress.of(socketPath));
            ScriptedPeer peer = new ScriptedPeer(server, "04");
            Thread peerThread = new Thread(peer, "scripted-broker-peer-v04");
            peerThread.setDaemon(true);
            peerThread.start();

            final ContainedProcessBroker session =
                    ContainedProcessBroker.connect(socketPath.toString(), NONCE);
            broker = session;
            check(peer.seenLines.size() == 1
                            && peer.seenLines.get(0).equals("DEALPG4 HELLO " + NONCE),
                    "HELLO_OK 04: HELLO carried the coordinator nonce");
            check(session.coordinatorNonce().equals(NONCE),
                    "HELLO_OK 04: leading-zero decimal version accepted "
                            + "(canonical VERSION_4 field class: decimal with value exactly 4)");
            session.featureReady();
            session.bye();
            check(awaitLineCount(peer, 3),
                    "HELLO_OK 04: full session completed over the accepted handshake");
            check(peer.failure == null, "HELLO_OK 04: scripted peer saw only expected records: "
                    + (peer.failure == null ? "ok" : peer.failure));
        } finally {
            closeQuietly(broker, server, socketPath, socketDir);
        }
    }

    /** Live CANCEL flow: run() on one thread retains the STUB_READY
     * nonce; cancelLive() on another thread emits CANCEL with exactly
     * that nonce; the peer answers CLEAN cancelled; the nonce is
     * cleared at the terminal record and further cancels are refused
     * client-side (a guessed nonce is never emitted). */
    private static void runCancelScenario() throws Exception {
        Path socketDir = Files.createTempDirectory("dealpg4-state-test-cancel");
        Path socketPath = socketDir.resolve("broker.sock");
        ContainedProcessBroker broker = null;
        ServerSocketChannel server = null;
        try {
            server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
            server.bind(UnixDomainSocketAddress.of(socketPath));
            InvocationPeer peer = new InvocationPeer(server, true);
            Thread peerThread = new Thread(peer, "invocation-peer-cancel");
            peerThread.setDaemon(true);
            peerThread.start();

            final ContainedProcessBroker session =
                    ContainedProcessBroker.connect(socketPath.toString(), NONCE);
            broker = session;
            check(session.invocationNonce(INVOCATION_ID) == null,
                    "cancel: invocationNonce(7) is null before any STUB_READY");
            session.featureReady();

            AtomicReference<ContainedProcessBroker.ProcessResult> resultRef =
                    new AtomicReference<>();
            AtomicReference<Throwable> errorRef = new AtomicReference<>();
            Thread runner = new Thread(() -> {
                try {
                    resultRef.set(session.run("preflight", "bin-true",
                            Arrays.asList("/bin/true"), "/",
                            ContainedProcessBroker.Limits.DEFAULTS));
                } catch (Throwable t) {
                    errorRef.set(t);
                }
            }, "run-and-cancel");
            runner.setDaemon(true);
            runner.start();

            /* The peer observes ACK only after the client parsed
             * STUB_READY and retained the nonce. */
            boolean ackOk = awaitCondition(() -> peer.ackSeen, 5000);
            check(ackOk, "cancel: peer observed ACK (client parsed STUB_READY)");
            if (ackOk) {
                check(peer.ackLine != null && peer.ackLine.equals(ACK_LINE),
                        "cancel: ACK carried exactly the STUB_READY nonce: " + peer.ackLine);
                check(STUB_NONCE.equals(session.invocationNonce(INVOCATION_ID)),
                        "cancel: invocationNonce(7) exposes the STUB_READY nonce during "
                                + "the live window");

                session.cancelLive(String.valueOf(INVOCATION_ID));

                runner.join(5000);
                check(!runner.isAlive(), "cancel: run() returned after the accepted CANCEL");
                check(errorRef.get() == null, "cancel: run() did not fail: " + errorRef.get());
                ContainedProcessBroker.ProcessResult result = resultRef.get();
                check(result != null, "cancel: run() produced a result");
                check(result != null
                                && result.disposition.equals(
                                        ContainedProcessBroker.CLEAN_CANCELLED)
                                && result.containmentClean(),
                        "cancel: accepted CANCEL surfaced as CLEAN cancelled "
                                + "(containment-clean)");
                check(result != null && result.exitCode == 0
                                && result.invocationId == INVOCATION_ID,
                        "cancel: result maps REPORT exitCode 0 and invocationId 7");
                check(peer.cancelLine != null && peer.cancelLine.equals(CANCEL_LINE),
                        "cancel: CANCEL carried exactly the retained STUB_READY nonce: "
                                + peer.cancelLine);
                check(session.invocationNonce(INVOCATION_ID) == null,
                        "cancel: invocationNonce(7) cleared at the terminal record");

                /* A second cancelLive for the terminal record and one
                 * for an unknown id are refused client-side with
                 * CANCEL_AUTH_FAILED before any write (a guessed nonce
                 * is never emitted). */
                int linesBefore = peer.seenLines.size();
                checkRefused("cancelLive after the terminal record", "CANCEL_AUTH_FAILED",
                        () -> session.cancelLive(INVOCATION_ID));
                check(session.invocationNonce(999) == null,
                        "cancel: invocationNonce(999) is null for an unknown id");
                checkRefused("cancelLive for an unknown id", "CANCEL_AUTH_FAILED",
                        () -> session.cancelLive(999L));
                check(peer.seenLines.size() == linesBefore,
                        "cancel: refused cancelLive calls wrote nothing");
            }

            session.bye();
            check(awaitCondition(() -> peer.failure == null && peer.seenLines.size() == 6,
                            5000),
                    "cancel: peer observed the full record sequence");
            check(peer.failure == null, "cancel: invocation peer saw only expected records: "
                    + (peer.failure == null ? "ok" : peer.failure));
            check(peer.seenLines.equals(Arrays.asList(
                            "DEALPG4 HELLO " + NONCE,
                            "DEALPG4 FEATURE_READY " + NONCE,
                            INVOKE_LINE,
                            ACK_LINE,
                            CANCEL_LINE,
                            "DEALPG4 BYE")),
                    "cancel: exact emitted record sequence: " + peer.seenLines);
        } finally {
            closeQuietly(broker, server, socketPath, socketDir);
        }
    }

    /** Live happy-path invocation: run() maps the full record exchange
     * into a containment-clean ProcessResult and the nonce is retained
     * only across the live window. */
    private static void runHappyPathScenario() throws Exception {
        Path socketDir = Files.createTempDirectory("dealpg4-state-test-happy");
        Path socketPath = socketDir.resolve("broker.sock");
        ContainedProcessBroker broker = null;
        ServerSocketChannel server = null;
        try {
            server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
            server.bind(UnixDomainSocketAddress.of(socketPath));
            InvocationPeer peer = new InvocationPeer(server, false);
            Thread peerThread = new Thread(peer, "invocation-peer-happy");
            peerThread.setDaemon(true);
            peerThread.start();

            final ContainedProcessBroker session =
                    ContainedProcessBroker.connect(socketPath.toString(), NONCE);
            broker = session;
            check(session.invocationNonce(INVOCATION_ID) == null,
                    "happy: invocationNonce(7) is null before any STUB_READY");
            session.featureReady();
            ContainedProcessBroker.ProcessResult result = session.run("preflight", "bin-true",
                    Arrays.asList("/bin/true"), "/",
                    ContainedProcessBroker.Limits.DEFAULTS);
            check(result.success(),
                    "happy: run() completed containment-clean with exit 0");
            check(result.invocationId == INVOCATION_ID && result.exitCode == 0
                            && result.failureToken.equals(ContainedProcessBroker.TOKEN_NONE)
                            && result.disposition.equals(ContainedProcessBroker.CLEAN_SUCCESS),
                    "happy: ProcessResult maps INVOKED/REPORT/CLEAN exactly");
            check(session.invocationNonce(INVOCATION_ID) == null,
                    "happy: invocationNonce(7) cleared at the terminal record");
            session.bye();
            check(awaitCondition(() -> peer.failure == null && peer.seenLines.size() == 5,
                            5000),
                    "happy: peer observed the full record sequence");
            check(peer.failure == null, "happy: invocation peer saw only expected records: "
                    + (peer.failure == null ? "ok" : peer.failure));
            check(peer.seenLines.equals(Arrays.asList(
                            "DEALPG4 HELLO " + NONCE,
                            "DEALPG4 FEATURE_READY " + NONCE,
                            INVOKE_LINE,
                            ACK_LINE,
                            "DEALPG4 BYE")),
                    "happy: exact emitted record sequence: " + peer.seenLines);
        } finally {
            closeQuietly(broker, server, socketPath, socketDir);
        }
    }

    private static void run() throws Exception {
        System.out.println("=== Running ContainedProcessBroker State Tests ===");
        runOrderingScenario();
        runLeadingZeroVersionScenario();
        runCancelScenario();
        runHappyPathScenario();
        System.out.println("Passed: " + passed + ", Failed: " + failed);
    }

    public static void main(String[] args) throws Exception {
        run();
        if (failed > 0) {
            System.exit(1);
        }
    }
}
