package deal.test.conformance;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * The StructuredExpectationComparator of the differential gate core
 * (ISSUE-0353; design {@code v12-zero-skip-conformance-gate} G4/G6):
 * compares one lane execution against the lane's structured expectation
 * exactly and returns the first mismatch of the closed G6 classes.
 *
 * <p>Check order (fixed, so the verdict is deterministic):</p>
 * <ol>
 *   <li>Infrastructure outcomes ({@code ARTIFACT_MISSING},
 *       {@code TOOL_MISSING}, {@code LANE_TIMEOUT},
 *       {@code PROCESS_FAILURE}, {@code HARNESS_DEFECT}) pass through
 *       labeled — they are reported separately from DEAL outcomes and
 *       never satisfy a case.</li>
 *   <li>Compile-reject cross-check ({@code COMPILE_REJECT_MISMATCH}):
 *       the lane must reject exactly when the expectation pins
 *       {@code compile-reject}, producing exactly the pinned diagnostic
 *       object (code plus pinned line/column, nothing else).</li>
 *   <li>Transcript byte comparison ({@code TRANSCRIPT_MISMATCH}):
 *       stdout then stderr, byte-for-byte; the first differing byte is
 *       reported with bounded context. A lane snapshot violating the
 *       canonical serialization diverges here byte-wise from the
 *       sidecar's canonical transcript.</li>
 *   <li>Exact exit code ({@code EXIT_CODE_MISMATCH}).</li>
 *   <li>For {@code runtime-error}: the G4.6 framing is parsed from
 *       stdout, the snapshot's canonical serialization is validated, and
 *       the fields are compared against the sidecar's Error Expectation
 *       — the sidecar is the authoritative field set: the lane must emit
 *       the mandatory fields plus exactly the pinned optional fields and
 *       suppress every unpinned optional
 *       ({@code ERROR_SNAPSHOT_MISMATCH} naming the backend and the first
 *       differing field).</li>
 * </ol>
 */
public final class StructuredExpectationComparator {

    private StructuredExpectationComparator() {
        // Static utility; no instances.
    }

    /** Bounded transcript context: half-window bytes around the divergence. */
    private static final int CONTEXT_HALF_WINDOW = 8;

    /**
     * Compares one lane execution against the lane's structured
     * expectation. Returns the first mismatch, or empty when the lane
     * matches its expectation exactly.
     *
     * @param backend     the lane name ({@code luajit}, {@code jvm},
     *                    {@code js}) — the mismatch subject
     * @param expectation the lane's structured expectation
     * @param execution   the lane's execution outcome
     */
    public static Optional<GateMismatch> compare(String backend,
            SidecarExpectations.RuntimeExpectation expectation,
            LaneExecution execution) {
        Objects.requireNonNull(backend, "backend must not be null");
        Objects.requireNonNull(expectation, "expectation must not be null");
        Objects.requireNonNull(execution, "execution must not be null");

        // 1. Infrastructure outcomes: reported separately, never satisfy a case.
        if (execution instanceof LaneExecution.Infrastructure infra) {
            return Optional.of(new GateMismatch(infra.clazz(), backend,
                infra.detail()));
        }

        // 2. Compile-reject cross-check (the sanctioned C6 divergence).
        if (expectation instanceof SidecarExpectations.RuntimeExpectation.Rejected
                rejected) {
            return compareRejection(backend, rejected, execution);
        }
        SidecarExpectations.RuntimeExpectation.Executed expected =
            (SidecarExpectations.RuntimeExpectation.Executed) expectation;
        if (execution instanceof LaneExecution.Rejected laneRejected) {
            return Optional.of(new GateMismatch(
                MismatchClass.COMPILE_REJECT_MISMATCH, backend,
                "unexpected compile rejection with diagnostic code "
                    + laneRejected.code() + " — the sidecar pins "
                    + expected.mode()));
        }
        LaneExecution.Executed lane = (LaneExecution.Executed) execution;

        // 3. Transcript bytes (stdout then stderr), first differing byte.
        Optional<GateMismatch> stdoutMismatch = compareBytes(backend, "stdout",
            expected.stdout(), lane.stdout());
        if (stdoutMismatch.isPresent()) {
            return stdoutMismatch;
        }
        Optional<GateMismatch> stderrMismatch = compareBytes(backend, "stderr",
            expected.stderr(), lane.stderr());
        if (stderrMismatch.isPresent()) {
            return stderrMismatch;
        }

        // 4. Exact exit code.
        if (lane.exitCode() != expected.exitCode()) {
            return Optional.of(new GateMismatch(MismatchClass.EXIT_CODE_MISMATCH,
                backend, "exitCode must be " + expected.exitCode() + ", got "
                    + lane.exitCode()));
        }

        // 5. Runtime-error: framing parse + canonical validation + the
        // sidecar-authoritative field-exact comparison.
        if (expected.isRuntimeError()) {
            return compareErrorSnapshot(backend, expected.error(),
                lane.stdout());
        }
        return Optional.empty();
    }

    /**
     * The rejection cross-check: the lane must reject with exactly the
     * pinned diagnostic object — the exact code plus the pinned
     * line/column, and no unpinned field.
     */
    private static Optional<GateMismatch> compareRejection(String backend,
            SidecarExpectations.RuntimeExpectation.Rejected rejected,
            LaneExecution execution) {
        if (!(execution instanceof LaneExecution.Rejected laneRejected)) {
            return Optional.of(new GateMismatch(
                MismatchClass.COMPILE_REJECT_MISMATCH, backend,
                "the sidecar pins compile rejection with diagnostic code "
                    + rejected.code() + " but the lane executed instead of "
                    + "rejecting"));
        }
        if (!rejected.code().equals(laneRejected.code())) {
            return Optional.of(new GateMismatch(
                MismatchClass.COMPILE_REJECT_MISMATCH, backend,
                "diagnostic.code must be " + rejected.code() + ", got "
                    + laneRejected.code()));
        }
        Optional<GateMismatch> lineMismatch = compareOptionalInt(backend,
            "diagnostic.line", rejected.line(), laneRejected.line());
        if (lineMismatch.isPresent()) {
            return lineMismatch;
        }
        return compareOptionalInt(backend, "diagnostic.column",
            rejected.column(), laneRejected.column());
    }

    private static Optional<GateMismatch> compareOptionalInt(String backend,
            String field, OptionalInt pinned, OptionalInt emitted) {
        if (pinned.isPresent() && emitted.isEmpty()) {
            return Optional.of(new GateMismatch(
                MismatchClass.COMPILE_REJECT_MISMATCH, backend,
                "the lane suppressed the pinned " + field + " ("
                    + pinned.getAsInt() + ")"));
        }
        if (pinned.isEmpty() && emitted.isPresent()) {
            return Optional.of(new GateMismatch(
                MismatchClass.COMPILE_REJECT_MISMATCH, backend,
                "the lane emitted " + field + " (" + emitted.getAsInt()
                    + ") the sidecar does not pin"));
        }
        if (pinned.isPresent() && emitted.getAsInt() != pinned.getAsInt()) {
            return Optional.of(new GateMismatch(
                MismatchClass.COMPILE_REJECT_MISMATCH, backend,
                field + " must be " + pinned.getAsInt() + ", got "
                    + emitted.getAsInt()));
        }
        return Optional.empty();
    }

    /** Byte-for-byte transcript comparison with bounded first-mismatch context. */
    private static Optional<GateMismatch> compareBytes(String backend,
            String stream, byte[] expected, byte[] actual) {
        int limit = Math.min(expected.length, actual.length);
        for (int i = 0; i < limit; i++) {
            if (expected[i] != actual[i]) {
                return Optional.of(new GateMismatch(
                    MismatchClass.TRANSCRIPT_MISMATCH, backend,
                    stream + " differs at byte " + i + ": expected 0x"
                        + String.format("%02X", expected[i]) + ", got 0x"
                        + String.format("%02X", actual[i]) + "; context "
                        + "expected " + boundedContext(expected, i)
                        + ", got " + boundedContext(actual, i)));
            }
        }
        if (expected.length != actual.length) {
            int i = limit;
            return Optional.of(new GateMismatch(
                MismatchClass.TRANSCRIPT_MISMATCH, backend,
                stream + " differs at byte " + i + ": expected "
                    + (expected.length > actual.length
                        ? "byte 0x" + String.format("%02X", expected[i])
                        : "end of stream")
                    + ", got "
                    + (expected.length < actual.length
                        ? "byte 0x" + String.format("%02X", actual[i])
                        : "end of stream")
                    + "; context expected "
                    + boundedContext(expected, i) + ", got "
                    + boundedContext(actual, i)));
        }
        return Optional.empty();
    }

    private static String boundedContext(byte[] bytes, int index) {
        int from = Math.max(0, index - CONTEXT_HALF_WINDOW);
        int to = Math.min(bytes.length, index + CONTEXT_HALF_WINDOW);
        StringBuilder sb = new StringBuilder("\"");
        for (int i = from; i < to; i++) {
            int b = bytes[i] & 0xFF;
            switch (b) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (b < 0x20 || b >= 0x7F) {
                        sb.append(String.format("\\x%02X", b));
                    } else {
                        sb.append((char) b);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    /**
     * The runtime-error snapshot comparison: parses the G4.6 framing from
     * the captured stdout, validates the canonical snapshot serialization,
     * and compares the fields against the sidecar's Error Expectation with
     * the sidecar as the authoritative field set (mandatory fields plus
     * exactly the pinned optionals; a pinned optional missing or an
     * unpinned optional emitted is a mismatch naming the backend and the
     * first differing field).
     */
    private static Optional<GateMismatch> compareErrorSnapshot(String backend,
            SidecarExpectations.ErrorExpectation expectation, byte[] stdout) {
        Optional<ErrorSnapshot.Framed> framed = ErrorSnapshot.parseFraming(stdout);
        if (framed.isEmpty()) {
            return Optional.of(new GateMismatch(
                MismatchClass.ERROR_SNAPSHOT_MISMATCH, backend,
                ErrorSnapshot.framingViolationDetail(stdout)));
        }
        SidecarExpectations.ErrorExpectation fields = framed.get().fields();

        // Mandatory code/message, in canonical order.
        for (String field : List.of("code", "message")) {
            String expectedValue = fieldValue(expectation, field);
            String actualValue = fieldValue(fields, field);
            if (!expectedValue.equals(actualValue)) {
                return Optional.of(new GateMismatch(
                    MismatchClass.ERROR_SNAPSHOT_MISMATCH, backend,
                    "error." + field + " must be " + describe(expectedValue)
                        + ", got " + describe(actualValue)));
            }
        }
        // The span group (sourceFile, line, column): emitted exactly
        // when the sidecar pins it. The one sanctioned span-less shape —
        // the locked time selector's retained nowMillis wrapper raising
        // E8004 with no file/line/column
        // (luajit-time-selector-disposition, Failure and operations) —
        // pins none of the three, so a lane emitting the group there is
        // a mismatch and a lane suppressing it matches.
        boolean spanPinned = expectation.pinsSpan();
        boolean spanEmitted = fields.pinsSpan();
        if (spanPinned && !spanEmitted) {
            return Optional.of(new GateMismatch(
                MismatchClass.ERROR_SNAPSHOT_MISMATCH, backend,
                "the lane suppressed the pinned span group "
                    + "(sourceFile/line/column) — the sidecar pins "
                    + describe(fieldValue(expectation, "sourceFile"))
                    + ":" + fieldValue(expectation, "line") + ":"
                    + fieldValue(expectation, "column")));
        }
        if (!spanPinned && spanEmitted) {
            return Optional.of(new GateMismatch(
                MismatchClass.ERROR_SNAPSHOT_MISMATCH, backend,
                "the lane emitted the span group (sourceFile/line/column)"
                    + " the sidecar does not pin — the sanctioned "
                    + "span-less shape must stay span-less"));
        }
        if (spanPinned) {
            for (String field : List.of("sourceFile", "line", "column")) {
                String expectedValue = fieldValue(expectation, field);
                String actualValue = fieldValue(fields, field);
                if (!expectedValue.equals(actualValue)) {
                    return Optional.of(new GateMismatch(
                        MismatchClass.ERROR_SNAPSHOT_MISMATCH, backend,
                        "error." + field + " must be " + describe(expectedValue)
                            + ", got " + describe(actualValue)));
                }
            }
        }
        // Optional fields: the sidecar is the authoritative field set.
        for (String field : ErrorSnapshot.CANONICAL_KEY_ORDER.subList(5, 9)) {
            boolean pinned = pinsOptional(expectation, field);
            boolean emitted = pinsOptional(fields, field);
            if (pinned && !emitted) {
                return Optional.of(new GateMismatch(
                    MismatchClass.ERROR_SNAPSHOT_MISMATCH, backend,
                    "the lane suppressed the pinned optional error field '"
                        + field + "' (the sidecar pins "
                        + describe(fieldValue(expectation, field)) + ")"));
            }
            if (!pinned && emitted) {
                return Optional.of(new GateMismatch(
                    MismatchClass.ERROR_SNAPSHOT_MISMATCH, backend,
                    "the lane emitted the optional error field '" + field
                        + "' (" + describe(fieldValue(fields, field))
                        + ") the sidecar does not pin"));
            }
            if (pinned) {
                String expectedValue = fieldValue(expectation, field);
                String actualValue = fieldValue(fields, field);
                if (!expectedValue.equals(actualValue)) {
                    return Optional.of(new GateMismatch(
                        MismatchClass.ERROR_SNAPSHOT_MISMATCH, backend,
                        "error." + field + " must be " + describe(expectedValue)
                            + ", got " + describe(actualValue)));
                }
            }
        }
        return Optional.empty();
    }

    private static boolean pinsOptional(SidecarExpectations.ErrorExpectation f,
            String field) {
        return switch (field) {
            case "expected" -> f.expected().isPresent();
            case "actual" -> f.actual().isPresent();
            case "frames" -> f.frames().isPresent();
            case "cause" -> f.cause().isPresent();
            default -> throw new IllegalArgumentException(
                "not an optional error field: " + field);
        };
    }

    private static String fieldValue(SidecarExpectations.ErrorExpectation f,
            String field) {
        return switch (field) {
            case "code" -> f.code();
            case "message" -> f.message();
            case "sourceFile" -> f.sourceFile();
            case "line" -> f.line() == null ? null : Integer.toString(f.line());
            case "column" -> f.column() == null ? null : Integer.toString(f.column());
            case "expected" -> f.expected().orElseThrow();
            case "actual" -> f.actual().orElseThrow();
            case "frames" -> Integer.toString(f.frames().orElseThrow());
            case "cause" -> f.cause().orElseThrow();
            default -> throw new IllegalArgumentException(
                "not an error field: " + field);
        };
    }

    private static String describe(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r")
            .replace("\t", "\\t") + "\"";
    }
}
