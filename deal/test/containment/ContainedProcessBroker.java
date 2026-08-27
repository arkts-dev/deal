package deal.test.containment;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Authenticated DEALPG4 v4 broker client (JEP 380 Unix-domain transport).
 *
 * <p>This is the Java-side containment surface owned by the epic
 * (outer-coordinator-and-broker D5/D10, fail-closed-toolchain-preflight
 * D4/D7): it connects to the outer feature supervisor's per-run broker
 * socket, performs the nonce-bound {@code HELLO}/{@code HELLO_OK} and
 * {@code FEATURE_READY}/{@code READY_ACK} handshakes, and exchanges the
 * canonical per-invocation record sequence
 *
 * <pre>{@code
 * INVOKE -> INVOKED -> STUB_READY -> ACK -> STARTED|EXEC_FAILED
 *        -> OUT chunks, then OUT_END (per stream)
 *        -> REPORT -> CLEAN|FAILED
 * }</pre>
 *
 * (dealpg4-launcher-artifact-and-integrity record catalog; the broker
 * frame sequence contract on outer-coordinator-and-broker). Every
 * coordinator-side write is validated against the canonical v4 framing
 * grammar before it is emitted (a malformed record is never sent), and
 * every received record is validated strictly — a framing defect, an
 * unknown type, or a record unexpected in the current channel state is a
 * hard {@link BrokerProtocolException} ({@code PROTOCOL_ERROR}); a
 * well-formed {@code REJECT} is surfaced as a hard
 * {@link BrokerRejectionException} carrying its reason token — never
 * swallowed, never retried, never skipped.
 *
 * <p>Clock ownership: this client owns no deadline and transmits no
 * limits. The catalog {@code INVOKE} carries no limits fields; the
 * delivered per-invocation budget is outer-computed
 * {@code T = min(invocationLimits.overallTimeoutMs, nestedStopMs - nowMs)}
 * (outer-coordinator-and-broker D4) and surfaces in the native
 * {@code REPORT}. {@link Limits} exists only for the parent-epic
 * {@code ContainedProcessBroker.run(fixtureId, phase, argv, cwd, limits)}
 * API shape (deal-v1.2-luajit-c-ffi-runtime-and-conformance D10) and is
 * never transmitted, so no Java/shell action can extend native time.
 * All record reads block on the socket; native time is bounded by the
 * outer's own deadline/stall machinery, never by this client.
 *
 * <p>Single-reader contract: record reads ({@link #featureReady()},
 * {@link #run(String, String, List, String, Limits)}) belong to one
 * thread at a time and one invocation is outstanding at a time — the
 * canonical preflight usage. Writes ({@link #cancel(String, String)},
 * {@link #bye()}) are synchronized and may be issued concurrently; a
 * mid-run {@code CANCEL} is answered by the same per-invocation record
 * stream ({@code CLEAN cancelled} on success, {@code REJECT ...
 * CANCEL_AUTH_FAILED} otherwise), which the running {@code run()} call
 * consumes and surfaces.
 */
public final class ContainedProcessBroker implements AutoCloseable {

    /** Broker endpoint environment variable (outer-coordinator-and-broker D5). */
    public static final String ENV_BROKER_PATH = "DEALPG4_BROKER_PATH";
    /** Coordinator nonce environment variable (outer-coordinator-and-broker D5). */
    public static final String ENV_NONCE = "DEALPG4_NONCE";

    /** Canonical protocol version carried by HELLO_OK (catalog VERSION_4 field). */
    public static final long PROTOCOL_VERSION = 4;

    /* Capability bits (dealpg4-launcher-artifact-and-integrity capability
     * bitmask: bit 1 subreaper, bit 2 monotonic timer, bit 4 negative-PGID
     * signaling, bit 8 parent-death signal, bit 16 bounded drains, bit 32
     * outer registry/broker). The coordinator must observe every expected
     * bit before sending FEATURE_READY. */
    public static final long CAP_SUBREAPER = 1L << 0;
    public static final long CAP_MONOTONIC_TIMER = 1L << 1;
    public static final long CAP_NEGATIVE_PGID_SIGNALING = 1L << 2;
    public static final long CAP_PARENT_DEATH_SIGNAL = 1L << 3;
    public static final long CAP_BOUNDED_DRAINS = 1L << 4;
    public static final long CAP_OUTER_REGISTRY_BROKER = 1L << 5;
    /** Union of every defined capability bit: 63 (all six bits). */
    public static final long EXPECTED_CAPABILITY_MASK =
            CAP_SUBREAPER | CAP_MONOTONIC_TIMER | CAP_NEGATIVE_PGID_SIGNALING
                    | CAP_PARENT_DEATH_SIGNAL | CAP_BOUNDED_DRAINS
                    | CAP_OUTER_REGISTRY_BROKER;

    /** 1 MiB per-stream output retention cap (supervisor-page D7 drain rule). */
    public static final int MAX_OUTPUT_RETAINED_BYTES = 1048576;
    /** Raw argv byte cap of the INVOKE record (catalog, 64 KiB). */
    public static final int MAX_INVOKE_ARGV_RAW_BYTES = 65536;
    /** Global record line cap: INVOKE <= 131072 bytes (catalog size caps). */
    public static final int MAX_RECORD_LINE_BYTES = 131072;

    /** OUT stream designators (catalog STREAM field class). */
    public static final String STREAM_OUT = "out";
    public static final String STREAM_ERR = "err";

    /** CLEAN final disposition values (catalog FINAL field class). */
    public static final String CLEAN_SUCCESS = "success";
    public static final String CLEAN_CANCELLED = "cancelled";
    /** REPORT failureToken value on a containment-clean record. */
    public static final String TOKEN_NONE = "-";

    private static final int NONCE_HEX_CHARS = 32;
    private static final String RECORD_PREFIX = "DEALPG4 ";

    private final SocketChannel channel;
    private final InputStream in;
    private final OutputStream out;
    private final String coordinatorNonce;
    private final Object writeLock = new Object();
    private boolean closed;
    private boolean featureReadySent;
    private boolean byeSent;

    private ContainedProcessBroker(SocketChannel channel, String coordinatorNonce) {
        this.channel = channel;
        this.in = new BufferedInputStream(Channels.newInputStream(channel));
        this.out = Channels.newOutputStream(channel);
        this.coordinatorNonce = coordinatorNonce;
        this.closed = false;
    }

    /**
     * Connects to the broker described by {@link #ENV_BROKER_PATH} and
     * {@link #ENV_NONCE} and performs the HELLO/HELLO_OK handshake.
     *
     * @throws ContainmentException on any handshake-stage failure:
     *         missing environment, a malformed nonce, connection loss
     *         before HELLO_OK (the outer rejected the peer or the nonce),
     *         a HELLO_OK version field that is not exactly 4 (framing
     *         defect, {@code PROTOCOL_ERROR}), a HELLO_OK capability
     *         bitmask missing any expected bit ({@code
     *         CAPABILITY_MISSING}), or any other framing defect
     *         ({@code PROTOCOL_ERROR}). FEATURE_READY is never sent on any
     *         of these failures.
     */
    public static ContainedProcessBroker connect() {
        String brokerPath = System.getenv(ENV_BROKER_PATH);
        String nonce = System.getenv(ENV_NONCE);
        if (brokerPath == null || brokerPath.isEmpty()) {
            throw new ContainmentException("AUTH_FAILED",
                    "environment variable " + ENV_BROKER_PATH + " is missing or empty");
        }
        if (nonce == null) {
            throw new ContainmentException("AUTH_FAILED",
                    "environment variable " + ENV_NONCE + " is missing");
        }
        return connect(brokerPath, nonce);
    }

    /**
     * Connects to the broker at {@code brokerPath} with the given
     * coordinator nonce and performs the HELLO/HELLO_OK handshake.
     * Failure behavior is identical to {@link #connect()}.
     */
    public static ContainedProcessBroker connect(String brokerPath, String coordinatorNonce) {
        Objects.requireNonNull(brokerPath, "brokerPath");
        Objects.requireNonNull(coordinatorNonce, "coordinatorNonce");
        if (brokerPath.isEmpty()) {
            throw new ContainmentException("AUTH_FAILED", "broker path is empty");
        }
        if (!isNonceHex(coordinatorNonce)) {
            throw new ContainmentException("AUTH_FAILED",
                    "coordinator nonce is not " + NONCE_HEX_CHARS + " lowercase hex");
        }
        SocketChannel channel;
        try {
            channel = SocketChannel.open(UnixDomainSocketAddress.of(brokerPath));
        } catch (IOException e) {
            throw new ContainmentException("AUTH_FAILED",
                    "cannot connect to broker socket " + brokerPath + ": " + e);
        }
        try {
            ContainedProcessBroker broker = new ContainedProcessBroker(channel, coordinatorNonce);
            broker.handshake();
            return broker;
        } catch (RuntimeException e) {
            try {
                channel.close();
            } catch (IOException ignored) {
                // Best-effort close on a failed handshake.
            }
            throw e;
        }
    }

    private void handshake() {
        send("HELLO", coordinatorNonce);
        Record record = readRecord();
        if (record == null) {
            throw new ContainmentException("AUTH_FAILED",
                    "broker closed before HELLO_OK (rejected peer, nonce, or lost coordinator)");
        }
        if (!record.type.equals("HELLO_OK")) {
            throw new BrokerProtocolException("expected HELLO_OK, got " + record.type);
        }
        long caps = parseDecimalLong(record.fields[1], "HELLO_OK caps");
        if ((caps & EXPECTED_CAPABILITY_MASK) != EXPECTED_CAPABILITY_MASK) {
            throw new ContainmentException("CAPABILITY_MISSING",
                    "HELLO_OK capability bitmask " + caps + " lacks expected bits "
                            + EXPECTED_CAPABILITY_MASK);
        }
    }

    /**
     * Sends {@code FEATURE_READY <nonce>} (the nonce bound at connect
     * time) and waits for the matching {@code READY_ACK}.
     *
     * <p>May only be called once, before any invocation. The capability
     * bitmask has already been verified by the handshake, so this method
     * is never reached when a capability bit is missing — the readiness
     * omission criterion is enforced structurally.
     *
     * @throws ContainmentException on a broker close before READY_ACK
     *         ({@code AUTH_FAILED}), a nonce mismatch in the READY_ACK
     *         ({@code AUTH_FAILED}), or a framing/state defect
     *         ({@code PROTOCOL_ERROR}).
     */
    public void featureReady() {
        synchronized (writeLock) {
            if (featureReadySent) {
                throw new ContainmentException("PROTOCOL_ERROR",
                        "FEATURE_READY was already sent (a duplicate would be out of order)");
            }
            featureReadySent = true;
        }
        send("FEATURE_READY", coordinatorNonce);
        Record record = readRecord();
        if (record == null) {
            throw new ContainmentException("AUTH_FAILED",
                    "broker closed before READY_ACK");
        }
        if (!record.type.equals("READY_ACK")) {
            throw new BrokerProtocolException("expected READY_ACK, got " + record.type);
        }
        if (!record.fields[0].equals(coordinatorNonce)) {
            throw new ContainmentException("AUTH_FAILED",
                    "READY_ACK nonce does not match the coordinator nonce");
        }
    }

    /**
     * Runs one contained invocation with the default {@link Limits} (the
     * manifest LauncherLimits; never transmitted — the delivered budget
     * is outer-computed).
     */
    public ProcessResult run(String fixtureId, String phase, List<String> argv, String cwd) {
        return run(fixtureId, phase, argv, cwd, Limits.DEFAULTS);
    }

    /**
     * Runs one contained invocation through the outer broker.
     *
     * <p>Sends one well-formed {@code INVOKE <clientTag> <cwdHex> <argc>
     * <argvHex...>} where {@code clientTag} is derived from
     * {@code fixtureId}/{@code phase} as a catalog token, {@code cwd} and
     * every argv element are even-length lowercase hex of their UTF-8
     * bytes, and the raw argv total is within the 64 KiB cap. The record
     * carries no limits fields (the delivered budget is outer-computed
     * and surfaces in {@code REPORT}). Then expects {@code INVOKED},
     * {@code STUB_READY <invocationId> <stubPid> <pgid> <sid> <nonce>},
     * sends {@code ACK <invocationId> <nonce>} using exactly the
     * {@code STUB_READY} nonce, consumes {@code STARTED|EXEC_FAILED},
     * decodes {@code OUT} hex chunks per stream up to the 1 MiB
     * per-stream retention cap honoring {@code OUT_END}, parses the
     * canonical 19-field {@code REPORT}, and maps the terminal
     * {@code CLEAN}/{@code FAILED} into the returned
     * {@link ProcessResult}.
     *
     * <p>A well-formed {@code REJECT} answer (pre-fork
     * {@code BUDGET_EXHAUSTED}/{@code MALFORMED_INVOKE}/{@code
     * REGISTRY_FULL}, or an {@code AUTH_FAILED}/{@code
     * CANCEL_AUTH_FAILED} answering this invocation's ACK or CANCEL) is
     * surfaced as a {@link BrokerRejectionException} carrying its reason
     * token — never swallowed, never retried.
     *
     * @throws BrokerProtocolException on any framing/state defect, a
     *         channel close before the terminal record, or an
     *         INVOKE whose client-side shape violates the canonical caps
     *         (empty argv element, empty or NUL-containing cwd, argv
     *         beyond the raw 64 KiB cap, record beyond the 131072-byte
     *         line cap) — a malformed INVOKE is never emitted.
     * @throws BrokerRejectionException on a REJECT for this invocation.
     */
    public ProcessResult run(String fixtureId, String phase, List<String> argv, String cwd,
                             Limits limits) {
        Objects.requireNonNull(fixtureId, "fixtureId");
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(argv, "argv");
        Objects.requireNonNull(cwd, "cwd");
        Objects.requireNonNull(limits, "limits");
        requireSessionState("INVOKE");
        String tag = buildClientTag(fixtureId, phase);
        sendInvoke(tag, argv, cwd);

        Record record = readRecord();
        if (record == null) {
            throw new BrokerProtocolException("broker closed before INVOKED");
        }
        if (record.type.equals("REJECT")) {
            throw rejectionFor(record, tag);
        }
        if (!record.type.equals("INVOKED")) {
            throw new BrokerProtocolException("expected INVOKED, got " + record.type);
        }
        long invocationId = parseDecimalLong(record.fields[0], "INVOKED invocationId");
        if (invocationId < 1) {
            throw new BrokerProtocolException("INVOKED invocationId " + invocationId + " < 1");
        }
        if (!record.fields[1].equals(tag)) {
            throw new BrokerProtocolException("INVOKED clientTag " + record.fields[1]
                    + " does not match the sent tag " + tag);
        }

        record = readRecord();
        if (record == null) {
            throw new BrokerProtocolException("broker closed before STUB_READY");
        }
        if (record.type.equals("REJECT")) {
            throw rejectionFor(record, tag);
        }
        if (!record.type.equals("STUB_READY")) {
            throw new BrokerProtocolException("expected STUB_READY, got " + record.type);
        }
        long stubInvocationId = parseDecimalLong(record.fields[0], "STUB_READY invocationId");
        if (stubInvocationId != invocationId) {
            throw new BrokerProtocolException("STUB_READY invocationId " + stubInvocationId
                    + " != INVOKED invocationId " + invocationId);
        }
        String stubNonce = record.fields[4];
        send("ACK", String.valueOf(invocationId), stubNonce);

        /* Post-ACK phase: STARTED|EXEC_FAILED, OUT/OUT_END, REPORT,
         * CLEAN|FAILED. REJECT may answer the ACK (record-level
         * AUTH_FAILED) or a concurrently issued CANCEL
         * (CANCEL_AUTH_FAILED). */
        boolean startedSeen = false;
        boolean execFailedSeen = false;
        boolean reportSeen = false;
        boolean[] outEnded = new boolean[2];
        ByteArrayOutputStream[] accumulated = new ByteArrayOutputStream[2];
        accumulated[0] = new ByteArrayOutputStream();
        accumulated[1] = new ByteArrayOutputStream();
        boolean[] locallyTruncated = new boolean[2];
        Report report = null;
        while (true) {
            record = readRecord();
            if (record == null) {
                throw new BrokerProtocolException(
                        "broker closed before CLEAN/FAILED for invocation " + invocationId);
            }
            switch (record.type) {
                case "REJECT":
                    throw rejectionFor(record, tag);
                case "STARTED": {
                    requireBeforeReport(reportSeen, "STARTED");
                    checkInvocationId(record, invocationId, "STARTED");
                    if (startedSeen || execFailedSeen) {
                        throw new BrokerProtocolException(
                                "duplicate or conflicting STARTED for invocation " + invocationId);
                    }
                    startedSeen = true;
                    break;
                }
                case "EXEC_FAILED": {
                    requireBeforeReport(reportSeen, "EXEC_FAILED");
                    checkInvocationId(record, invocationId, "EXEC_FAILED");
                    if (startedSeen || execFailedSeen) {
                        throw new BrokerProtocolException(
                                "duplicate or conflicting EXEC_FAILED for invocation "
                                        + invocationId);
                    }
                    execFailedSeen = true;
                    break;
                }
                case "OUT": {
                    requireBeforeReport(reportSeen, "OUT");
                    checkInvocationId(record, invocationId, "OUT");
                    int stream = streamIndex(record.fields[1]);
                    if (outEnded[stream]) {
                        throw new BrokerProtocolException(
                                "OUT for stream " + record.fields[1] + " after OUT_END");
                    }
                    appendCapped(accumulated[stream], locallyTruncated, stream,
                            hexDecode(record.fields[2], "OUT chunk"));
                    break;
                }
                case "OUT_END": {
                    requireBeforeReport(reportSeen, "OUT_END");
                    checkInvocationId(record, invocationId, "OUT_END");
                    int stream = streamIndex(record.fields[1]);
                    if (outEnded[stream]) {
                        throw new BrokerProtocolException(
                                "duplicate OUT_END for stream " + record.fields[1]);
                    }
                    outEnded[stream] = true;
                    break;
                }
                case "REPORT": {
                    if (reportSeen) {
                        throw new BrokerProtocolException(
                                "duplicate REPORT for invocation " + invocationId);
                    }
                    report = parseReport(record);
                    reportSeen = true;
                    break;
                }
                case "CLEAN":
                    checkInvocationId(record, invocationId, "CLEAN");
                    requireTerminalEvidence(invocationId, reportSeen, accumulated, report,
                            locallyTruncated);
                    return buildResult(invocationId, record.fields[1], report, accumulated);
                case "FAILED":
                    checkInvocationId(record, invocationId, "FAILED");
                    requireTerminalEvidence(invocationId, reportSeen, accumulated, report,
                            locallyTruncated);
                    return buildFailedResult(invocationId, record.fields[1], report, accumulated);
                default:
                    throw new BrokerProtocolException(
                            "unexpected record " + record.type + " during invocation "
                                    + invocationId);
            }
        }
    }

    /**
     * Sends {@code CANCEL <invocationId> <nonce>} for a live invocation
     * whose {@code STUB_READY} nonce is {@code nonce}.
     *
     * <p>The outcome is surfaced through the invocation's record stream:
     * an accepted CANCEL completes the running {@link #run} call with
     * {@code CLEAN cancelled}, and a rejected CANCEL surfaces as a
     * {@link BrokerRejectionException} ({@code CANCEL_AUTH_FAILED} or
     * another reason token) — the channel stays open and the record is
     * untouched (canonical CANCEL rule); never swallowed, never retried.
     *
     * @throws ContainmentException on a malformed invocationId or nonce
     *         (a malformed CANCEL is never emitted) or on a channel
     *         write failure.
     */
    public void cancel(String invocationId, String nonce) {
        Objects.requireNonNull(invocationId, "invocationId");
        Objects.requireNonNull(nonce, "nonce");
        requireSessionState("CANCEL");
        requireDecimal(invocationId, "CANCEL invocationId");
        if (parseDecimalLong(invocationId, "CANCEL invocationId") < 1) {
            throw new BrokerProtocolException("CANCEL invocationId " + invocationId + " < 1");
        }
        requireNonce(nonce, "CANCEL nonce");
        send("CANCEL", invocationId, nonce);
    }

    /**
     * Sends the session-level {@code BYE} record (canonical frame
     * sequence terminator). Call once after all invocations completed.
     */
    public void bye() {
        synchronized (writeLock) {
            if (byeSent) {
                throw new ContainmentException("PROTOCOL_ERROR",
                        "BYE was already sent (a duplicate would be out of order)");
            }
            byeSent = true;
        }
        send("BYE");
    }

    /**
     * Closes the broker socket. Idempotent.
     */
    @Override
    public void close() {
        synchronized (writeLock) {
            if (closed) {
                return;
            }
            closed = true;
        }
        try {
            channel.close();
        } catch (IOException e) {
            throw new ContainmentException("BROKER_IO_ERROR",
                    "broker socket close failed: " + e);
        }
    }

    /** The coordinator nonce bound at connect time (32 lowercase hex). */
    public String coordinatorNonce() {
        return coordinatorNonce;
    }

    /* === INVOKE emission ============================================== */

    private void sendInvoke(String tag, List<String> argv, String cwd) {
        sendFields("INVOKE", buildInvokeFields(tag, argv, cwd));
    }

    /**
     * Builds the canonical INVOKE fields (clientTag, hex cwd, argc, hex
     * argv) with every catalog cap enforced. Package-private framing
     * seam: never emits a malformed record.
     */
    static List<String> buildInvokeFields(String tag, List<String> argv, String cwd) {
        if (argv.isEmpty()) {
            throw new ContainmentException("MALFORMED_INVOKE",
                    "argv is empty (argc must be >= 1)");
        }
        String cwdHex = encodeInvokeField(cwd, "cwd");
        if (cwdHex.isEmpty()) {
            throw new ContainmentException("MALFORMED_INVOKE",
                    "cwd is empty (the canonical INVOKE cwd decodes to a non-empty path)");
        }
        List<String> fields = new ArrayList<>();
        fields.add(tag);
        fields.add(cwdHex);
        fields.add(String.valueOf(argv.size()));
        long rawArgvBytes = 0;
        for (String element : argv) {
            if (element == null) {
                throw new ContainmentException("MALFORMED_INVOKE", "null argv element");
            }
            String hex = encodeInvokeField(element, "argv element");
            if (hex.isEmpty()) {
                throw new ContainmentException("MALFORMED_INVOKE",
                        "argv element is empty (a zero-byte element is not encodable "
                                + "in the canonical framing)");
            }
            rawArgvBytes += hex.length() / 2;
            if (rawArgvBytes > MAX_INVOKE_ARGV_RAW_BYTES) {
                throw new BrokerProtocolException("INVOKE argv exceeds the "
                        + MAX_INVOKE_ARGV_RAW_BYTES + "-byte raw cap");
            }
            fields.add(hex);
        }
        return fields;
    }

    private static String encodeInvokeField(String value, String what) {
        Objects.requireNonNull(value, what);
        if (value.indexOf('\u0000') >= 0) {
            throw new ContainmentException("MALFORMED_INVOKE",
                    what + " contains a NUL byte (not decodable to a valid path/argv)");
        }
        return hexEncode(value.getBytes(StandardCharsets.UTF_8));
    }

    /* === Record I/O ==================================================== */

    private void send(String type, String... fields) {
        sendFields(type, Arrays.asList(fields));
    }

    private void sendFields(String type, List<String> fields) {
        String line = buildRecordLine(type, fields);
        synchronized (writeLock) {
            if (closed) {
                throw new ContainmentException("BROKER_CLOSED", "broker is closed");
            }
            try {
                out.write(line.toString().getBytes(StandardCharsets.US_ASCII));
            } catch (IOException e) {
                throw new ContainmentException("BROKER_IO_ERROR",
                        "broker write failed: " + e);
            }
        }
    }

    /**
     * Validates and renders one record line (write-side symmetry with
     * the canonical parser: a class-violating or oversize request is
     * refused, so a malformed record is never emitted). Package-private
     * framing seam.
     */
    static String buildRecordLine(String type, List<String> fields) {
        validateOutgoing(type, fields);
        StringBuilder line = new StringBuilder(RECORD_PREFIX.length() + type.length()
                + fields.size() * 16 + 1);
        line.append(RECORD_PREFIX).append(type);
        for (String field : fields) {
            line.append(' ').append(field);
        }
        line.append('\n');
        if (line.length() > MAX_RECORD_LINE_BYTES) {
            throw new BrokerProtocolException(
                    "record line exceeds the " + MAX_RECORD_LINE_BYTES + "-byte cap");
        }
        return line.toString();
    }

    /** Reads one record line (LF-terminated) or null at a clean EOF. */
    private Record readRecord() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(128);
        int b;
        try {
            while ((b = in.read()) != -1) {
                if (b == '\n') {
                    return parseRecord(buffer.toByteArray());
                }
                if (b == '\r') {
                    throw new BrokerProtocolException("record line contains CR");
                }
                buffer.write(b);
                if (buffer.size() > MAX_RECORD_LINE_BYTES) {
                    throw new BrokerProtocolException(
                            "record line exceeds the " + MAX_RECORD_LINE_BYTES + "-byte cap");
                }
            }
        } catch (IOException e) {
            throw new ContainmentException("BROKER_IO_ERROR", "broker read failed: " + e);
        }
        if (buffer.size() == 0) {
            return null;
        }
        throw new BrokerProtocolException("EOF inside a record line");
    }

    static Record parseRecord(byte[] lineBytes) {
        for (byte value : lineBytes) {
            if (value < 0) {
                throw new BrokerProtocolException("record line contains a non-ASCII byte");
            }
        }
        String line = new String(lineBytes, StandardCharsets.US_ASCII);
        if (!line.startsWith(RECORD_PREFIX)) {
            throw new BrokerProtocolException(
                    "record line does not start with the DEALPG4 prefix");
        }
        String[] parts = line.substring(RECORD_PREFIX.length()).split(" ", -1);
        for (String part : parts) {
            if (part.isEmpty()) {
                throw new BrokerProtocolException("empty type token or field in record line");
            }
        }
        String type = parts[0];
        String[] fields = Arrays.copyOfRange(parts, 1, parts.length);
        validateIncoming(type, fields);
        return new Record(type, fields);
    }

    static final class Record {
        final String type;
        final String[] fields;

        Record(String type, String[] fields) {
            this.type = type;
            this.fields = fields;
        }
    }

    /* === Validation (write-side symmetry with the canonical parser) === */

    private static void validateOutgoing(String type, List<String> fields) {
        switch (type) {
            case "HELLO":
                requireFieldCount(type, fields, 1);
                requireNonce(fields.get(0), "HELLO nonce");
                return;
            case "FEATURE_READY":
                requireFieldCount(type, fields, 1);
                requireNonce(fields.get(0), "FEATURE_READY nonce");
                return;
            case "INVOKE": {
                if (fields.size() < 3) {
                    throw new BrokerProtocolException(
                            "INVOKE has " + fields.size() + " fields, expected >= 3");
                }
                requireToken(fields.get(0), "INVOKE clientTag");
                String cwd = fields.get(1);
                if (!cwd.isEmpty()) {
                    requireHex(cwd, "INVOKE cwd");
                }
                requireDecimal(fields.get(2), "INVOKE argc");
                long argc = parseDecimalLong(fields.get(2), "INVOKE argc");
                if (argc < 1) {
                    throw new BrokerProtocolException("INVOKE argc is 0");
                }
                if (argc != fields.size() - 3) {
                    throw new BrokerProtocolException("INVOKE argc " + argc
                            + " != argv field count " + (fields.size() - 3));
                }
                for (int i = 3; i < fields.size(); i++) {
                    requireHex(fields.get(i), "INVOKE argv element");
                }
                return;
            }
            case "ACK":
                requireFieldCount(type, fields, 2);
                requireDecimal(fields.get(0), "ACK invocationId");
                requireNonce(fields.get(1), "ACK nonce");
                return;
            case "CANCEL":
                requireFieldCount(type, fields, 2);
                requireDecimal(fields.get(0), "CANCEL invocationId");
                requireNonce(fields.get(1), "CANCEL nonce");
                return;
            case "BYE":
                requireFieldCount(type, fields, 0);
                return;
            default:
                throw new BrokerProtocolException(
                        "this client never emits record type " + type);
        }
    }

    private static void validateIncoming(String type, String[] fields) {
        switch (type) {
            case "HELLO_OK":
                requireFieldCount(type, fields, 2);
                if (!fields[0].equals(String.valueOf(PROTOCOL_VERSION))) {
                    throw new BrokerProtocolException(
                            "HELLO_OK version '" + fields[0] + "' is not exactly "
                                    + PROTOCOL_VERSION + " (canonical VERSION_4 field class)");
                }
                requireDecimal(fields[1], "HELLO_OK caps");
                return;
            case "READY_ACK":
                requireFieldCount(type, fields, 1);
                requireNonce(fields[0], "READY_ACK nonce");
                return;
            case "INVOKED":
                requireFieldCount(type, fields, 2);
                requireDecimal(fields[0], "INVOKED invocationId");
                requireToken(fields[1], "INVOKED clientTag");
                return;
            case "REJECT":
                requireFieldCount(type, fields, 3);
                requireDecimal(fields[0], "REJECT invocationId");
                requireDashOrToken(fields[1], "REJECT clientTag");
                requireToken(fields[2], "REJECT reasonToken");
                return;
            case "STUB_READY":
                requireFieldCount(type, fields, 5);
                requireDecimal(fields[0], "STUB_READY invocationId");
                requireDecimal(fields[1], "STUB_READY stubPid");
                requireDecimal(fields[2], "STUB_READY pgid");
                requireDecimal(fields[3], "STUB_READY sid");
                requireNonce(fields[4], "STUB_READY nonce");
                return;
            case "STARTED":
                requireFieldCount(type, fields, 1);
                requireDecimal(fields[0], "STARTED invocationId");
                return;
            case "EXEC_FAILED":
                requireFieldCount(type, fields, 2);
                requireDecimal(fields[0], "EXEC_FAILED invocationId");
                requireDecimal(fields[1], "EXEC_FAILED errno");
                return;
            case "OUT":
                requireFieldCount(type, fields, 3);
                requireDecimal(fields[0], "OUT invocationId");
                requireStream(fields[1]);
                requireHex(fields[2], "OUT chunk");
                return;
            case "OUT_END":
                requireFieldCount(type, fields, 2);
                requireDecimal(fields[0], "OUT_END invocationId");
                requireStream(fields[1]);
                return;
            case "REPORT":
                requireFieldCount(type, fields, 19);
                for (int i = 0; i < 13; i++) {
                    requireDecimal(fields[i], "REPORT field " + i);
                }
                for (int i = 13; i < 18; i++) {
                    requireBit(fields[i], "REPORT field " + i);
                }
                requireDashOrToken(fields[18], "REPORT failureToken");
                return;
            case "CLEAN":
                requireFieldCount(type, fields, 2);
                requireDecimal(fields[0], "CLEAN invocationId");
                requireFinal(fields[1]);
                return;
            case "FAILED":
                requireFieldCount(type, fields, 2);
                requireDecimal(fields[0], "FAILED invocationId");
                requireToken(fields[1], "FAILED failureToken");
                return;
            case "DONE":
                requireFieldCount(type, fields, 1);
                requireOuterStatus(fields[0]);
                return;
            case "BYE":
                requireFieldCount(type, fields, 0);
                return;
            default:
                throw new BrokerProtocolException(
                        "record type " + type + " is not expected on the broker channel");
        }
    }

    private static void requireFieldCount(String type, List<String> fields, int count) {
        if (fields.size() != count) {
            throw new BrokerProtocolException(
                    "record " + type + " has " + fields.size() + " fields, expected " + count);
        }
    }

    private static void requireFieldCount(String type, String[] fields, int count) {
        if (fields.length != count) {
            throw new BrokerProtocolException(
                    "record " + type + " has " + fields.length + " fields, expected " + count);
        }
    }

    private static void requireDecimal(String value, String what) {
        if (value.isEmpty()) {
            throw new BrokerProtocolException(what + " is empty (decimal expected)");
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') {
                throw new BrokerProtocolException(
                        what + " '" + value + "' is not a decimal field");
            }
        }
        try {
            Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new BrokerProtocolException(what + " '" + value + "' exceeds int64");
        }
    }

    private static long parseDecimalLong(String value, String what) {
        requireDecimal(value, what);
        return Long.parseLong(value);
    }

    private static void requireNonce(String value, String what) {
        if (!isNonceHex(value)) {
            throw new BrokerProtocolException(
                    what + " '" + value + "' is not " + NONCE_HEX_CHARS + " lowercase hex");
        }
    }

    private static boolean isNonceHex(String value) {
        if (value.length() != NONCE_HEX_CHARS) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!isLowerHex(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static void requireHex(String value, String what) {
        if (value.length() < 2 || (value.length() % 2) != 0) {
            throw new BrokerProtocolException(
                    what + " '" + value + "' is not even-length (>= 2) hex");
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!isLowerHex(c)) {
                throw new BrokerProtocolException(what + " '" + value + "' is not lowercase hex");
            }
        }
    }

    private static boolean isLowerHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
    }

    private static void requireToken(String value, String what) {
        if (value.isEmpty()) {
            throw new BrokerProtocolException(what + " is empty (token expected)");
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || c == '_')) {
                throw new BrokerProtocolException(
                        what + " '" + value + "' is not a [A-Za-z_]+ token");
            }
        }
    }

    private static void requireDashOrToken(String value, String what) {
        if (value.equals("-")) {
            return;
        }
        requireToken(value, what);
    }

    private static void requireStream(String value) {
        if (!value.equals(STREAM_OUT) && !value.equals(STREAM_ERR)) {
            throw new BrokerProtocolException("OUT stream '" + value + "' is not out|err");
        }
    }

    private static void requireFinal(String value) {
        if (!value.equals(CLEAN_SUCCESS) && !value.equals(CLEAN_CANCELLED)) {
            throw new BrokerProtocolException(
                    "CLEAN final '" + value + "' is not success|cancelled");
        }
    }

    private static void requireBit(String value, String what) {
        if (!value.equals("0") && !value.equals("1")) {
            throw new BrokerProtocolException(what + " '" + value + "' is not 0|1");
        }
    }

    private static void requireOuterStatus(String value) {
        if (!value.equals("clean") && !value.equals("failed")) {
            throw new BrokerProtocolException("DONE outerStatus '" + value + "' is not clean|failed");
        }
    }

    /* === Encoding helpers ============================================== */

    private static final char[] HEX_DIGITS = "0123456789abcdef".toCharArray();

    private static String hexEncode(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            out.append(HEX_DIGITS[(value >> 4) & 0xF]).append(HEX_DIGITS[value & 0xF]);
        }
        return out.toString();
    }

    private static byte[] hexDecode(String value, String what) {
        requireHex(value, what);
        byte[] out = new byte[value.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int high = hexNibble(value.charAt(2 * i));
            int low = hexNibble(value.charAt(2 * i + 1));
            out[i] = (byte) ((high << 4) | low);
        }
        return out;
    }

    private static int hexNibble(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        return c - 'a' + 10;
    }

    private static String decodeLenientUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            /* Unreachable with REPLACE actions; fail closed anyway. */
            throw new ContainmentException("BROKER_IO_ERROR",
                    "output decode failed: " + e);
        }
    }

    /* === Per-invocation state handling ================================= */

    private void requireBeforeReport(boolean reportSeen, String type) {
        if (reportSeen) {
            throw new BrokerProtocolException(
                    type + " after REPORT (canonical sequence: REPORT precedes CLEAN|FAILED "
                            + "with no relay records in between)");
        }
    }

    private void requireSessionState(String recordType) {
        synchronized (writeLock) {
            if (!featureReadySent) {
                throw new ContainmentException("PROTOCOL_ERROR",
                        recordType + " before FEATURE_READY would be out of order "
                                + "(the canonical frame sequence emits FEATURE_READY/READY_ACK "
                                + "before any invocation)");
            }
            if (byeSent) {
                throw new ContainmentException("PROTOCOL_ERROR",
                        recordType + " after BYE would be out of order");
            }
        }
    }

    private void checkInvocationId(Record record, long invocationId, String type) {
        long id = parseDecimalLong(record.fields[0], type + " invocationId");
        if (id != invocationId) {
            throw new BrokerProtocolException(type + " invocationId " + id
                    + " != INVOKED invocationId " + invocationId);
        }
    }

    private int streamIndex(String stream) {
        return stream.equals(STREAM_OUT) ? 0 : 1;
    }

    private void appendCapped(ByteArrayOutputStream target, boolean[] truncatedFlag,
                              int stream, byte[] chunk) {
        int room = MAX_OUTPUT_RETAINED_BYTES - target.size();
        if (room > 0) {
            target.write(chunk, 0, Math.min(room, chunk.length));
        }
        if (chunk.length > room) {
            truncatedFlag[stream] = true;
        }
    }

    private BrokerRejectionException rejectionFor(Record record, String ourTag) {
        long id = parseDecimalLong(record.fields[0], "REJECT invocationId");
        String tag = record.fields[1];
        String reason = record.fields[2];
        if (!tag.equals(ourTag) && !tag.equals("-")) {
            throw new BrokerProtocolException(
                    "REJECT clientTag '" + tag + "' names a different invocation");
        }
        return new BrokerRejectionException(reason, id, tag,
                "broker rejected invocation " + id + " (" + tag + ") with " + reason);
    }

    private static final class Report {
        final int exitCode;
        final int termSignal;
        final long elapsedMs;
        final long startupMs;
        final long execMs;
        final long termMs;
        final long killMs;
        final long proofMs;
        final long finalMs;
        final long reapCount;
        final long adoptCount;
        final long stdoutBytes;
        final long stderrBytes;
        final boolean stdoutTruncated;
        final boolean stderrTruncated;
        final boolean groupProof;
        final boolean sessionProof;
        final boolean drainEof;
        final String failureToken;

        Report(int exitCode, int termSignal, long elapsedMs, long startupMs, long execMs,
               long termMs, long killMs, long proofMs, long finalMs, long reapCount,
               long adoptCount, long stdoutBytes, long stderrBytes, boolean stdoutTruncated,
               boolean stderrTruncated, boolean groupProof, boolean sessionProof,
               boolean drainEof, String failureToken) {
            this.exitCode = exitCode;
            this.termSignal = termSignal;
            this.elapsedMs = elapsedMs;
            this.startupMs = startupMs;
            this.execMs = execMs;
            this.termMs = termMs;
            this.killMs = killMs;
            this.proofMs = proofMs;
            this.finalMs = finalMs;
            this.reapCount = reapCount;
            this.adoptCount = adoptCount;
            this.stdoutBytes = stdoutBytes;
            this.stderrBytes = stderrBytes;
            this.stdoutTruncated = stdoutTruncated;
            this.stderrTruncated = stderrTruncated;
            this.groupProof = groupProof;
            this.sessionProof = sessionProof;
            this.drainEof = drainEof;
            this.failureToken = failureToken;
        }
    }

    private static Report parseReport(Record record) {
        String[] f = record.fields;
        int exitCode = parseInt32(f[0], "REPORT exitCode");
        int termSignal = parseInt32(f[1], "REPORT termSignal");
        long elapsedMs = parseDecimalLong(f[2], "REPORT elapsedMs");
        long startupMs = parseDecimalLong(f[3], "REPORT startupMs");
        long execMs = parseDecimalLong(f[4], "REPORT execMs");
        long termMs = parseDecimalLong(f[5], "REPORT termMs");
        long killMs = parseDecimalLong(f[6], "REPORT killMs");
        long proofMs = parseDecimalLong(f[7], "REPORT proofMs");
        long finalMs = parseDecimalLong(f[8], "REPORT finalMs");
        long reapCount = parseDecimalLong(f[9], "REPORT reapCount");
        long adoptCount = parseDecimalLong(f[10], "REPORT adoptCount");
        long stdoutBytes = parseDecimalLong(f[11], "REPORT stdoutBytes");
        long stderrBytes = parseDecimalLong(f[12], "REPORT stderrBytes");
        boolean stdoutTruncated = f[13].equals("1");
        boolean stderrTruncated = f[14].equals("1");
        boolean groupProof = f[15].equals("1");
        boolean sessionProof = f[16].equals("1");
        boolean drainEof = f[17].equals("1");
        String failureToken = f[18];
        return new Report(exitCode, termSignal, elapsedMs, startupMs, execMs, termMs, killMs,
                proofMs, finalMs, reapCount, adoptCount, stdoutBytes, stderrBytes,
                stdoutTruncated, stderrTruncated, groupProof, sessionProof, drainEof,
                failureToken);
    }

    private static int parseInt32(String value, String what) {
        long parsed = parseDecimalLong(value, what);
        if (parsed > Integer.MAX_VALUE) {
            throw new BrokerProtocolException(what + " '" + value + "' exceeds int32");
        }
        return (int) parsed;
    }

    private void requireTerminalEvidence(long invocationId, boolean reportSeen,
                                         ByteArrayOutputStream[] accumulated, Report report,
                                         boolean[] locallyTruncated) {
        if (!reportSeen || report == null) {
            throw new BrokerProtocolException(
                    "terminal record for invocation " + invocationId
                            + " arrived before REPORT (canonical sequence: "
                            + "REPORT precedes CLEAN|FAILED)");
        }
        /* Stream accounting cross-check: the native relay is lossless
         * under the 1 MiB caps, so a non-truncated stream's accumulated
         * bytes must equal the REPORT byte count exactly. */
        long[] accumulatedBytes = {accumulated[0].size(), accumulated[1].size()};
        long[] reportedBytes = {report.stdoutBytes, report.stderrBytes};
        boolean[] truncated = {report.stdoutTruncated, report.stderrTruncated};
        for (int i = 0; i < 2; i++) {
            if (!truncated[i] && !locallyTruncated[i]
                    && accumulatedBytes[i] != reportedBytes[i]) {
                throw new BrokerProtocolException(
                        "stream " + (i == 0 ? STREAM_OUT : STREAM_ERR) + " accounting mismatch: "
                                + accumulatedBytes[i] + " bytes received vs "
                                + reportedBytes[i] + " reported for invocation " + invocationId);
            }
        }
    }

    private ProcessResult buildResult(long invocationId, String finalDisposition,
                                      Report report, ByteArrayOutputStream[] accumulated) {
        return new ProcessResult(invocationId,
                report.exitCode,
                decodeLenientUtf8(accumulated[0].toByteArray()),
                decodeLenientUtf8(accumulated[1].toByteArray()),
                report.elapsedMs, report.termSignal, report.startupMs, report.execMs,
                report.termMs, report.killMs, report.proofMs, report.finalMs, report.reapCount,
                report.adoptCount, report.stdoutBytes, report.stderrBytes,
                report.stdoutTruncated, report.stderrTruncated, report.groupProof,
                report.sessionProof, report.drainEof, report.failureToken, finalDisposition);
    }

    private ProcessResult buildFailedResult(long invocationId, String failedToken,
                                            Report report, ByteArrayOutputStream[] accumulated) {
        /* REPORT.failureToken carries the token of every FAILED record
         * (canonical REPORT contract); enforce the agreement. */
        if (!report.failureToken.equals(TOKEN_NONE)
                && !report.failureToken.equals(failedToken)) {
            throw new BrokerProtocolException(
                    "FAILED token " + failedToken + " disagrees with REPORT failureToken "
                            + report.failureToken);
        }
        return new ProcessResult(invocationId,
                report.exitCode,
                decodeLenientUtf8(accumulated[0].toByteArray()),
                decodeLenientUtf8(accumulated[1].toByteArray()),
                report.elapsedMs, report.termSignal, report.startupMs, report.execMs,
                report.termMs, report.killMs, report.proofMs, report.finalMs, report.reapCount,
                report.adoptCount, report.stdoutBytes, report.stderrBytes,
                report.stdoutTruncated, report.stderrTruncated, report.groupProof,
                report.sessionProof, report.drainEof, failedToken, "failed");
    }

    private static String buildClientTag(String fixtureId, String phase) {
        String a = sanitizeToken(fixtureId);
        String b = sanitizeToken(phase);
        if (a.isEmpty() && b.isEmpty()) {
            return "run";
        }
        if (a.isEmpty()) {
            return b;
        }
        if (b.isEmpty()) {
            return a;
        }
        return a + "_" + b;
    }

    private static String sanitizeToken(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || c == '_') {
                out.append(c);
            } else {
                out.append('_');
            }
        }
        return out.toString();
    }

    /* === Public nested types =========================================== */

    /**
     * Invocation limits for the parent-epic run() API shape
     * (deal-v1.2-luajit-c-ffi-runtime-and-conformance D10). Never
     * transmitted: the catalog INVOKE carries no limits fields, and the
     * delivered per-invocation budget is outer-computed
     * {@code T = min(invocationLimits.overallTimeoutMs, nestedStopMs - nowMs)}
     * (outer-coordinator-and-broker D4), surfacing in REPORT. All
     * milliseconds.
     */
    public static final class Limits {

        /** Manifest LauncherLimits defaults (tools/launcher-manifest.json). */
        public static final Limits DEFAULTS = new Limits(45000, 5000, 35000, 2000, 5000, 3000);

        /** Overall invocation budget (manifest: 45000 ms). */
        public final long overallTimeoutMs;
        /** Pre-release startup bound (manifest: 5000 ms). */
        public final long startupTimeoutMs;
        /** Execution cutoff (manifest: 35000 ms). */
        public final long executionCutoffMs;
        /** TERM grace (manifest: 2000 ms). */
        public final long termGraceMs;
        /** KILL-and-proof reserve (manifest: 5000 ms). */
        public final long killAndProofReserveMs;
        /** Finalization reserve (manifest: 3000 ms). */
        public final long finalizationReserveMs;

        public Limits(long overallTimeoutMs, long startupTimeoutMs, long executionCutoffMs,
                      long termGraceMs, long killAndProofReserveMs,
                      long finalizationReserveMs) {
            this.overallTimeoutMs = overallTimeoutMs;
            this.startupTimeoutMs = startupTimeoutMs;
            this.executionCutoffMs = executionCutoffMs;
            this.termGraceMs = termGraceMs;
            this.killAndProofReserveMs = killAndProofReserveMs;
            this.finalizationReserveMs = finalizationReserveMs;
        }
    }

    /**
     * Result of one contained invocation, mapped from the terminal
     * CLEAN/FAILED record and the canonical 19-field REPORT.
     *
     * <p>REPORT conventions: {@code exitCode} is the target exit code —
     * signal deaths carry {@code 128+signal} (the native side applies
     * the convention, e.g. UNVERIFIED_TARGET_DEATH) — {@code elapsedMs}
     * is the native-measured monotonic elapsed, {@code stdout}/{@code
     * stderr} are the hex-decoded streams (1 MiB per-stream retention
     * cap, decoded leniently), and {@code failureToken} is {@code "-"}
     * on a containment-clean record, otherwise the named failure token
     * (e.g. {@code STUB_BOOTSTRAP_FAILED}, {@code AUTH_FAILED}, {@code
     * EXECUTION_TIMEOUT}, {@code UNVERIFIED_TARGET_DEATH}). {@code
     * disposition} is {@code success}, {@code cancelled} (clean cancel),
     * or {@code failed} (FAILED record).
     */
    public static final class ProcessResult {

        /** The invocation id assigned by the outer (INVOKED). */
        public final long invocationId;
        /** Target exit code (REPORT conventions; signal death = 128+signal). */
        public final int exitCode;
        /** Decoded target stdout (1 MiB retention cap). */
        public final String stdout;
        /** Decoded target stderr (1 MiB retention cap). */
        public final String stderr;
        /** Native-measured monotonic elapsed time (REPORT elapsedMs). */
        public final long elapsedMs;
        /** Signal that terminated the target (REPORT; 0 = none). */
        public final int termSignal;
        /** REPORT startupMs. */
        public final long startupMs;
        /** REPORT execMs. */
        public final long execMs;
        /** REPORT termMs. */
        public final long termMs;
        /** REPORT killMs. */
        public final long killMs;
        /** REPORT proofMs. */
        public final long proofMs;
        /** REPORT finalMs. */
        public final long finalMs;
        /** REPORT reapCount. */
        public final long reapCount;
        /** REPORT adoptCount. */
        public final long adoptCount;
        /** REPORT stdoutBytes. */
        public final long stdoutBytes;
        /** REPORT stderrBytes. */
        public final long stderrBytes;
        /** REPORT stdoutTruncated. */
        public final boolean stdoutTruncated;
        /** REPORT stderrTruncated. */
        public final boolean stderrTruncated;
        /** REPORT groupProof. */
        public final boolean groupProof;
        /** REPORT sessionProof. */
        public final boolean sessionProof;
        /** REPORT drainEof. */
        public final boolean drainEof;
        /** "-" on a containment-clean record, otherwise the failure token. */
        public final String failureToken;
        /** "success", "cancelled", or "failed". */
        public final String disposition;

        ProcessResult(long invocationId, int exitCode, String stdout, String stderr,
                      long elapsedMs, int termSignal, long startupMs, long execMs,
                      long termMs, long killMs, long proofMs, long finalMs, long reapCount,
                      long adoptCount, long stdoutBytes, long stderrBytes,
                      boolean stdoutTruncated, boolean stderrTruncated, boolean groupProof,
                      boolean sessionProof, boolean drainEof, String failureToken,
                      String disposition) {
            this.invocationId = invocationId;
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
            this.elapsedMs = elapsedMs;
            this.termSignal = termSignal;
            this.startupMs = startupMs;
            this.execMs = execMs;
            this.termMs = termMs;
            this.killMs = killMs;
            this.proofMs = proofMs;
            this.finalMs = finalMs;
            this.reapCount = reapCount;
            this.adoptCount = adoptCount;
            this.stdoutBytes = stdoutBytes;
            this.stderrBytes = stderrBytes;
            this.stdoutTruncated = stdoutTruncated;
            this.stderrTruncated = stderrTruncated;
            this.groupProof = groupProof;
            this.sessionProof = sessionProof;
            this.drainEof = drainEof;
            this.failureToken = failureToken;
            this.disposition = disposition;
        }

        /**
         * True when containment itself was clean ({@code failureToken}
         * equals {@code "-"} and the disposition is not {@code failed}).
         * A containment-clean result may still carry a nonzero target
         * exit code (e.g. {@code /bin/false} exits 1 cleanly).
         */
        public boolean containmentClean() {
            return failureToken.equals(TOKEN_NONE)
                    && (disposition.equals(CLEAN_SUCCESS)
                        || disposition.equals(CLEAN_CANCELLED));
        }

        /**
         * True when the round-trip fully succeeded: containment clean,
         * CLEAN final=success, and target exit code 0.
         */
        public boolean success() {
            return containmentClean() && disposition.equals(CLEAN_SUCCESS) && exitCode == 0;
        }
    }

    /**
     * Base hard-failure exception of the broker client, carrying the
     * named failure token. Every failure is terminal: nothing is
     * retried, skipped, or downgraded.
     */
    public static class ContainmentException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final String token;

        protected ContainmentException(String token, String message) {
            super(message);
            this.token = token;
        }

        /** The named failure token (e.g. PROTOCOL_ERROR, AUTH_FAILED). */
        public String token() {
            return token;
        }

        @Override
        public String toString() {
            return token + ": " + getMessage();
        }
    }

    /**
     * Channel-level protocol failure ({@code PROTOCOL_ERROR}): a framing
     * defect, an unknown type, a record unexpected in the channel state,
     * or a channel close before the expected record.
     */
    public static final class BrokerProtocolException extends ContainmentException {

        private static final long serialVersionUID = 1L;

        public BrokerProtocolException(String message) {
            super("PROTOCOL_ERROR", message);
        }
    }

    /**
     * A well-formed {@code REJECT} answered by the outer for this
     * invocation (pre-fork {@code BUDGET_EXHAUSTED},
     * {@code MALFORMED_INVOKE}, {@code REGISTRY_FULL}, or an
     * {@code AUTH_FAILED}/{@code CANCEL_AUTH_FAILED} answering this
     * invocation's ACK/CANCEL). The channel stays open (canonical
     * record-level rule); this client surfaces the rejection as a hard
     * failure carrying the reason token.
     */
    public static final class BrokerRejectionException extends ContainmentException {

        private static final long serialVersionUID = 1L;

        private final String reasonToken;
        private final long invocationId;
        private final String clientTag;

        BrokerRejectionException(String reasonToken, long invocationId, String clientTag,
                                 String message) {
            super(reasonToken, message);
            this.reasonToken = reasonToken;
            this.invocationId = invocationId;
            this.clientTag = clientTag;
        }

        /** The REJECT reason token (canonical reasonTokens). */
        public String reasonToken() {
            return reasonToken;
        }

        /** The invocationId carried by the REJECT (0 for an id-less shape). */
        public long invocationId() {
            return invocationId;
        }

        /** The clientTag carried by the REJECT ("-" for the nested shape). */
        public String clientTag() {
            return clientTag;
        }
    }
}
