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

/**
 * Session-ordering regression tests for the broker client
 * (ISSUE-0182 review finding): {@code featureReady()} and {@code bye()}
 * must enforce the same mutual ordering guards as {@code run()} and
 * {@code cancel()} (requireSessionState), so the client can never emit
 * out-of-order records against the canonical frame sequence
 * (HELLO → HELLO_OK → FEATURE_READY → READY_ACK → repeated invocations
 * → DONE → BYE):
 *
 * <ul>
 *   <li>{@code bye()} before {@code featureReady()} → refused
 *       client-side, {@code PROTOCOL_ERROR}, nothing written;</li>
 *   <li>{@code featureReady()} after {@code bye()} → refused
 *       client-side, {@code PROTOCOL_ERROR}, nothing written;</li>
 *   <li>duplicate {@code featureReady()} and duplicate {@code bye()}
 *       → refused client-side, {@code PROTOCOL_ERROR};</li>
 *   <li>{@code run()}/{@code cancel()} before {@code FEATURE_READY} and
 *       after {@code BYE} → refused client-side,
 *       {@code PROTOCOL_ERROR} (existing requireSessionState).</li>
 * </ul>
 *
 * <p>The transport is a real Unix-domain socket pair: the test binds a
 * {@link ServerSocketChannel} on a temporary socket path and runs a
 * scripted peer that answers the handshake records exactly as the outer
 * broker answers them ({@code HELLO_OK 4 63} for the canonical
 * version-4 capability bitmask, {@code READY_ACK <nonce>} for
 * {@code FEATURE_READY}). This is a test-only double for the peer side
 * of the state machine; the live end-to-end verification against the
 * real {@code launcher outer} chain is the issue's combined-dependency
 * step and is not simulated here. Every ordering refusal fires inside
 * the client's writeLock before any write, so the scripted peer also
 * asserts that no record ever reaches the socket for a refused call.
 */
public final class ContainedProcessBrokerStateTest {

    private static final String NONCE = "0123456789abcdef0123456789abcdef";
    private static final int EXPECTED_CAPS = 63;

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

    /**
     * Waits (bounded, 5 s) until the scripted peer has observed
     * {@code expectedCount} client records; bye() carries no response
     * record, so the test cannot synchronize on a client-side read.
     */
    private static boolean awaitLineCount(ScriptedPeer peer, int expectedCount) {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            if (peer.failure != null) {
                return false;
            }
            if (peer.seenLines.size() == expectedCount) {
                return true;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
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
        volatile IOException failure;

        ScriptedPeer(ServerSocketChannel server) {
            this.server = server;
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

    private static void run() throws Exception {
        System.out.println("=== Running ContainedProcessBroker State Tests ===");

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
            Files.deleteIfExists(socketPath);
            Files.deleteIfExists(socketDir);
        }

        System.out.println("Passed: " + passed + ", Failed: " + failed);
    }

    public static void main(String[] args) throws Exception {
        run();
        if (failed > 0) {
            System.exit(1);
        }
    }
}
