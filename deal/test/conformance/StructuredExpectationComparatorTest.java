package deal.test.conformance;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Comparator unit tests (ISSUE-0353 Verification): the
 * StructuredExpectationComparator over synthetic transcripts and error
 * snapshots — perturbed console strings, missing newlines, extra and
 * reordered writes, canonical-serialization violations, the
 * sidecar-authoritative error field set, exit-code and compile-reject
 * mismatch classes, and the infrastructure passthrough.
 *
 * <p>All inputs here are synthetic unit fixtures; no lane implementation
 * exists yet (the lanes land in T7-T9), so lane executions are built
 * directly from the closed {@link LaneExecution} values.</p>
 */
public class StructuredExpectationComparatorTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        System.out.println("=== StructuredExpectationComparator Tests (ISSUE-0353) ===\n");

        runtimeOkMatches();
        transcriptPerturbations();
        exitCodeMismatch();
        runtimeErrorMatches();
        canonicalSerializationViolations();
        authoritativeFieldSet();
        mandatoryFieldMismatch();
        spanGroupAuthority();
        malformedFraming();
        framingCodeMismatch();
        nonCanonicalSnapshotDirectValidation();
        rejectionCases();
        infrastructurePassthrough();

        System.out.println("\nPassed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static void check(boolean condition, String message) {
        if (condition) { passed++; }
        else { failed++; System.err.println("FAIL: " + message); }
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static SidecarExpectations.ErrorExpectation err(String code,
            String message, String sourceFile, int line, int column) {
        return new SidecarExpectations.ErrorExpectation(code, message,
            sourceFile, line, column, Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty());
    }

    /** The sanctioned span-less shape: code/message, no span group. */
    private static SidecarExpectations.ErrorExpectation spanlessErr(
            String code, String message) {
        return new SidecarExpectations.ErrorExpectation(code, message,
            null, null, null, Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty());
    }

    private static SidecarExpectations.RuntimeExpectation runtimeOk(
            String stdout, String stderr) {
        return new SidecarExpectations.RuntimeExpectation.Executed(
            "runtime-ok", bytes(stdout), bytes(stderr), 0, null);
    }

    private static SidecarExpectations.RuntimeExpectation runtimeError(
            SidecarExpectations.ErrorExpectation error, String stdout) {
        return new SidecarExpectations.RuntimeExpectation.Executed(
            "runtime-error", bytes(stdout), bytes(""), 1, error);
    }

    /** The canonical G4.6 framing for one error expectation. */
    private static String framed(SidecarExpectations.ErrorExpectation error) {
        return ErrorSnapshot.CODE_LINE_PREFIX + error.code() + "\n"
            + ErrorSnapshot.SNAPSHOT_LINE_PREFIX
            + ErrorSnapshot.canonicalJson(error) + "\n";
    }

    private static LaneExecution executed(String stdout, String stderr,
            int exitCode) {
        return new LaneExecution.Executed(bytes(stdout), bytes(stderr),
            exitCode);
    }

    private static Optional<GateMismatch> compare(String backend,
            SidecarExpectations.RuntimeExpectation expectation,
            LaneExecution execution) {
        return StructuredExpectationComparator.compare(backend, expectation,
            execution);
    }

    private static void assertMismatch(Optional<GateMismatch> result,
            MismatchClass expectedClass, String subject, String detailPart) {
        check(result.isPresent(), subject + " must mismatch with "
            + expectedClass + " (got none)");
        if (result.isEmpty()) {
            return;
        }
        GateMismatch mismatch = result.get();
        check(mismatch.clazz() == expectedClass,
            subject + " class must be " + expectedClass + ", got "
                + mismatch.clazz() + ": " + mismatch.detail());
        check(subject.equals(mismatch.subject()),
            "subject must be " + subject + ", got " + mismatch.subject());
        check(mismatch.detail().contains(detailPart),
            subject + " detail must contain \"" + detailPart + "\", got: "
                + mismatch.detail());
    }

    // =========================================================================
    // Cases
    // =========================================================================

    private static void runtimeOkMatches() {
        SidecarExpectations.RuntimeExpectation expectation =
            runtimeOk("hello\n", "");
        check(compare("luajit", expectation,
            executed("hello\n", "", 0)).isEmpty(),
            "runtime-ok: matching transcript and exit code pass on luajit");
        check(compare("jvm", expectation,
            executed("hello\n", "", 0)).isEmpty(),
            "runtime-ok: matching transcript and exit code pass on jvm");
        check(compare("js", expectation,
            executed("hello\n", "", 0)).isEmpty(),
            "runtime-ok: matching transcript and exit code pass on js");
    }

    private static void transcriptPerturbations() {
        SidecarExpectations.RuntimeExpectation expectation =
            runtimeOk("hello\n", "");

        // A perturbed console string: detected at the first differing byte
        // with bounded context.
        assertMismatch(compare("luajit", expectation,
            executed("hallo\n", "", 0)),
            MismatchClass.TRANSCRIPT_MISMATCH, "luajit",
            "stdout differs at byte 1: expected 0x65, got 0x61");
        check(compare("luajit", expectation,
            executed("hallo\n", "", 0)).get().detail()
                .contains("context expected \""),
            "perturbed console string: bounded expected context present");

        // A missing newline: the streams end at different lengths.
        assertMismatch(compare("luajit", expectation,
            executed("hello", "", 0)),
            MismatchClass.TRANSCRIPT_MISMATCH, "luajit",
            "stdout differs at byte 5");

        // An extra write: the surplus byte is the first difference.
        assertMismatch(compare("luajit", expectation,
            executed("hello\nb\n", "", 0)),
            MismatchClass.TRANSCRIPT_MISMATCH, "luajit",
            "stdout differs at byte 6");

        // A reordered write: the first byte already differs.
        SidecarExpectations.RuntimeExpectation ordered =
            runtimeOk("first\nsecond\n", "");
        assertMismatch(compare("jvm", ordered,
            executed("second\nfirst\n", "", 0)),
            MismatchClass.TRANSCRIPT_MISMATCH, "jvm",
            "stdout differs at byte 0");

        // A stderr perturbation names the stderr stream.
        assertMismatch(compare("js", expectation,
            executed("hello\n", "noise", 0)),
            MismatchClass.TRANSCRIPT_MISMATCH, "js",
            "stderr differs at byte 0");
    }

    private static void exitCodeMismatch() {
        SidecarExpectations.RuntimeExpectation expectation =
            runtimeOk("hello\n", "");
        assertMismatch(compare("luajit", expectation,
            executed("hello\n", "", 1)),
            MismatchClass.EXIT_CODE_MISMATCH, "luajit",
            "exitCode must be 0, got 1");
        SidecarExpectations.ErrorExpectation error = err("E8001",
            "expected int", "backend-runtime/runtime-errors/x.deal", 6, 21);
        SidecarExpectations.RuntimeExpectation errorExpectation =
            runtimeError(error, framed(error));
        assertMismatch(compare("jvm", errorExpectation,
            executed(framed(error), "", 0)),
            MismatchClass.EXIT_CODE_MISMATCH, "jvm",
            "exitCode must be 1, got 0");
    }

    private static void runtimeErrorMatches() {
        SidecarExpectations.ErrorExpectation error = new SidecarExpectations
            .ErrorExpectation("E8001", "expected int",
                "backend-runtime/runtime-errors/type-mismatch-e8001.deal",
                6, 21, Optional.of("int"), Optional.of("string"),
                Optional.empty(), Optional.empty());
        SidecarExpectations.RuntimeExpectation expectation =
            runtimeError(error, framed(error));
        check(compare("luajit", expectation,
            executed(framed(error), "", 1)).isEmpty(),
            "runtime-error: the exact canonical framing passes");
        check(compare("jvm", expectation,
            executed(framed(error), "", 1)).isEmpty(),
            "runtime-error: the exact canonical framing passes on jvm");
        check(compare("js", expectation,
            executed(framed(error), "", 1)).isEmpty(),
            "runtime-error: the exact canonical framing passes on js");
    }

    private static void canonicalSerializationViolations() {
        SidecarExpectations.ErrorExpectation error = err("E8001",
            "expected int", "backend-runtime/runtime-errors/x.deal", 6, 21);
        String canonical = framed(error);
        SidecarExpectations.RuntimeExpectation expectation =
            runtimeError(error, canonical);

        // Wrong key order: the byte comparison fails at the first
        // reordered token.
        String wrongKeyOrder = ErrorSnapshot.CODE_LINE_PREFIX + "E8001\n"
            + ErrorSnapshot.SNAPSHOT_LINE_PREFIX
            + "{\"message\":\"expected int\",\"code\":\"E8001\","
            + "\"sourceFile\":\"backend-runtime/runtime-errors/x.deal\","
            + "\"line\":6,\"column\":21}\n";
        assertMismatch(compare("luajit", expectation,
            executed(wrongKeyOrder, "", 1)),
            MismatchClass.TRANSCRIPT_MISMATCH, "luajit",
            "stdout differs at byte");

        // A unicode-escaped non-ASCII character: raw UTF-8 is canonical.
        SidecarExpectations.ErrorExpectation utf8Error = err("E8001",
            "caf\u00e9", "backend-runtime/runtime-errors/x.deal", 6, 21);
        SidecarExpectations.RuntimeExpectation utf8Expectation =
            runtimeError(utf8Error, framed(utf8Error));
        String unicodeEscaped = ErrorSnapshot.CODE_LINE_PREFIX + "E8001\n"
            + ErrorSnapshot.SNAPSHOT_LINE_PREFIX
            + "{\"code\":\"E8001\",\"message\":\"caf\\u00e9\","
            + "\"sourceFile\":\"backend-runtime/runtime-errors/x.deal\","
            + "\"line\":6,\"column\":21}\n";
        assertMismatch(compare("jvm", utf8Expectation,
            executed(unicodeEscaped, "", 1)),
            MismatchClass.TRANSCRIPT_MISMATCH, "jvm",
            "stdout differs at byte");

        // Inter-token whitespace: the canonical form has none.
        String spaced = ErrorSnapshot.CODE_LINE_PREFIX + "E8001\n"
            + ErrorSnapshot.SNAPSHOT_LINE_PREFIX
            + "{\"code\": \"E8001\", \"message\": \"expected int\", "
            + "\"sourceFile\": \"backend-runtime/runtime-errors/x.deal\", "
            + "\"line\": 6, \"column\": 21}\n";
        assertMismatch(compare("js", expectation,
            executed(spaced, "", 1)),
            MismatchClass.TRANSCRIPT_MISMATCH, "js",
            "stdout differs at byte");

        // A leading-zero integer: canonical decimal integers carry none.
        String leadingZero = ErrorSnapshot.CODE_LINE_PREFIX + "E8001\n"
            + ErrorSnapshot.SNAPSHOT_LINE_PREFIX
            + "{\"code\":\"E8001\",\"message\":\"expected int\","
            + "\"sourceFile\":\"backend-runtime/runtime-errors/x.deal\","
            + "\"line\":06,\"column\":21}\n";
        assertMismatch(compare("luajit", expectation,
            executed(leadingZero, "", 1)),
            MismatchClass.TRANSCRIPT_MISMATCH, "luajit",
            "stdout differs at byte");

        // A missing trailing newline: the framing line must end with one.
        String noTrailingNewline = ErrorSnapshot.CODE_LINE_PREFIX + "E8001\n"
            + ErrorSnapshot.SNAPSHOT_LINE_PREFIX
            + ErrorSnapshot.canonicalJson(error);
        assertMismatch(compare("luajit", expectation,
            executed(noTrailingNewline, "", 1)),
            MismatchClass.TRANSCRIPT_MISMATCH, "luajit",
            "stdout differs at byte");
    }

    private static void authoritativeFieldSet() {
        // An unpinned optional field emitted by the lane: the sidecar's
        // error object pins no `expected` but its transcript snapshot
        // carries one (a defective sidecar the gate still catches) — the
        // snapshot comparison names the backend and the first differing
        // field.
        SidecarExpectations.ErrorExpectation unpinned = err("E8001",
            "expected int", "backend-runtime/runtime-errors/x.deal", 6, 21);
        String emittedSnapshot = ErrorSnapshot.canonicalJson(
            new SidecarExpectations.ErrorExpectation("E8001",
                "expected int", "backend-runtime/runtime-errors/x.deal", 6,
                21, Optional.of("int"), Optional.empty(), Optional.empty(),
                Optional.empty()));
        SidecarExpectations.RuntimeExpectation emittedExpectation =
            new SidecarExpectations.RuntimeExpectation.Executed(
                "runtime-error",
                bytes(ErrorSnapshot.CODE_LINE_PREFIX + "E8001\n"
                    + ErrorSnapshot.SNAPSHOT_LINE_PREFIX + emittedSnapshot
                    + "\n"),
                bytes(""), 1, unpinned);
        assertMismatch(compare("jvm", emittedExpectation,
            executed(ErrorSnapshot.CODE_LINE_PREFIX + "E8001\n"
                + ErrorSnapshot.SNAPSHOT_LINE_PREFIX + emittedSnapshot
                + "\n", "", 1)),
            MismatchClass.ERROR_SNAPSHOT_MISMATCH, "jvm",
            "the lane emitted the optional error field 'expected'");

        // A pinned optional field suppressed by the lane: the sidecar pins
        // `expected` but its transcript snapshot omits it.
        SidecarExpectations.ErrorExpectation pinned = new SidecarExpectations
            .ErrorExpectation("E8001", "expected int",
                "backend-runtime/runtime-errors/x.deal", 6, 21,
                Optional.of("int"), Optional.empty(), Optional.empty(),
                Optional.empty());
        String suppressedSnapshot = ErrorSnapshot.canonicalJson(
            err("E8001", "expected int",
                "backend-runtime/runtime-errors/x.deal", 6, 21));
        SidecarExpectations.RuntimeExpectation suppressedExpectation =
            new SidecarExpectations.RuntimeExpectation.Executed(
                "runtime-error",
                bytes(ErrorSnapshot.CODE_LINE_PREFIX + "E8001\n"
                    + ErrorSnapshot.SNAPSHOT_LINE_PREFIX + suppressedSnapshot
                    + "\n"),
                bytes(""), 1, pinned);
        assertMismatch(compare("js", suppressedExpectation,
            executed(ErrorSnapshot.CODE_LINE_PREFIX + "E8001\n"
                + ErrorSnapshot.SNAPSHOT_LINE_PREFIX + suppressedSnapshot
                + "\n", "", 1)),
            MismatchClass.ERROR_SNAPSHOT_MISMATCH, "js",
            "the lane suppressed the pinned optional error field 'expected'");

        // A pinned optional field with the wrong value.
        SidecarExpectations.ErrorExpectation wrongPinned = new
            SidecarExpectations.ErrorExpectation("E8001", "expected int",
                "backend-runtime/runtime-errors/x.deal", 6, 21,
                Optional.of("int"), Optional.empty(), Optional.empty(),
                Optional.empty());
        String wrongSnapshot = ErrorSnapshot.canonicalJson(
            new SidecarExpectations.ErrorExpectation("E8001",
                "expected int", "backend-runtime/runtime-errors/x.deal", 6,
                21, Optional.of("number"), Optional.empty(), Optional.empty(),
                Optional.empty()));
        SidecarExpectations.RuntimeExpectation wrongExpectation =
            new SidecarExpectations.RuntimeExpectation.Executed(
                "runtime-error",
                bytes(ErrorSnapshot.CODE_LINE_PREFIX + "E8001\n"
                    + ErrorSnapshot.SNAPSHOT_LINE_PREFIX + wrongSnapshot
                    + "\n"),
                bytes(""), 1, wrongPinned);
        assertMismatch(compare("luajit", wrongExpectation,
            executed(ErrorSnapshot.CODE_LINE_PREFIX + "E8001\n"
                + ErrorSnapshot.SNAPSHOT_LINE_PREFIX + wrongSnapshot
                + "\n", "", 1)),
            MismatchClass.ERROR_SNAPSHOT_MISMATCH, "luajit",
            "error.expected must be \"int\", got \"number\"");
    }

    private static void mandatoryFieldMismatch() {
        // A mandatory field with the wrong value: the sidecar's error
        // object pins message "expected int" but its transcript snapshot
        // carries "expected string" (a defective sidecar the gate still
        // catches) — the lane matches the transcript bytes, so the
        // snapshot field comparison names the first differing field.
        SidecarExpectations.ErrorExpectation expectedError = err("E8001",
            "expected int", "backend-runtime/runtime-errors/x.deal", 6, 21);
        String laneSnapshot = ErrorSnapshot.canonicalJson(
            err("E8001", "expected string",
                "backend-runtime/runtime-errors/x.deal", 6, 21));
        String framing = ErrorSnapshot.CODE_LINE_PREFIX + "E8001\n"
            + ErrorSnapshot.SNAPSHOT_LINE_PREFIX + laneSnapshot + "\n";
        SidecarExpectations.RuntimeExpectation expectation =
            runtimeError(expectedError, framing);
        assertMismatch(compare("luajit", expectation,
            executed(framing, "", 1)),
            MismatchClass.ERROR_SNAPSHOT_MISMATCH, "luajit",
            "error.message must be \"expected int\", got \"expected string\"");
    }

    /**
     * The span group (sourceFile, line, column) is emitted exactly when
     * the sidecar pins it: the sanctioned span-less shape (the locked
     * time selector's retained nowMillis wrapper raising E8004 with no
     * span — luajit-time-selector-disposition, Failure and operations)
     * matches a lane that emits code/message only; a lane emitting the
     * unpinned group, or suppressing a pinned group, mismatches naming
     * the group.
     */
    private static void spanGroupAuthority() {
        // Span-less sidecar + span-less lane snapshot: pass.
        SidecarExpectations.ErrorExpectation spanless = spanlessErr(
            "E8004", "int out of safe range");
        SidecarExpectations.RuntimeExpectation expectation =
            runtimeError(spanless, framed(spanless));
        check(compare("luajit", expectation,
            executed(framed(spanless), "", 1)).isEmpty(),
            "span-less: the code/message-only snapshot matches the "
                + "span-less sidecar");
        check(framed(spanless).contains("{\"code\":\"E8004\","
                + "\"message\":\"int out of safe range\"}\n"),
            "span-less: the canonical snapshot carries exactly code and "
                + "message, got " + framed(spanless));

        // The lane emits the span group the sidecar does not pin
        // (the expectation's transcript carries the lane's own framing
        // so the field comparison, not the transcript, names the defect).
        SidecarExpectations.ErrorExpectation spanEmitted = err("E8004",
            "int out of safe range",
            "backend-runtime/stdlib-edge/time-now-millis-positive.deal",
            9, 18);
        SidecarExpectations.RuntimeExpectation emittedExpectation =
            runtimeError(spanless, framed(spanEmitted));
        assertMismatch(compare("luajit", emittedExpectation,
            executed(framed(spanEmitted), "", 1)),
            MismatchClass.ERROR_SNAPSHOT_MISMATCH, "luajit",
            "the lane emitted the span group");

        // The lane suppresses the span group the sidecar pins.
        SidecarExpectations.ErrorExpectation spanPinned = err("E8004",
            "int out of safe range",
            "backend-runtime/stdlib-edge/time-now-millis-positive.deal",
            9, 18);
        SidecarExpectations.RuntimeExpectation pinnedExpectation =
            runtimeError(spanPinned, framed(spanless));
        assertMismatch(compare("luajit", pinnedExpectation,
            executed(framed(spanless), "", 1)),
            MismatchClass.ERROR_SNAPSHOT_MISMATCH, "luajit",
            "the lane suppressed the pinned span group");

        // A partial span group in the lane snapshot violates the
        // canonical serialization (the group must be complete or absent);
        // the expectation's transcript carries the lane's own framing so
        // the framing validation names the defect.
        String partial = ErrorSnapshot.CODE_LINE_PREFIX + "E8004\n"
            + ErrorSnapshot.SNAPSHOT_LINE_PREFIX
            + "{\"code\":\"E8004\",\"message\":\"int out of safe range\","
            + "\"line\":9}\n";
        SidecarExpectations.RuntimeExpectation partialExpectation =
            runtimeError(spanless, partial);
        assertMismatch(compare("luajit", partialExpectation,
            executed(partial, "", 1)),
            MismatchClass.ERROR_SNAPSHOT_MISMATCH, "luajit",
            "carries no well-formed DEAL_ERROR_CODE/DEAL_ERROR_SNAPSHOT");
    }

    private static void malformedFraming() {
        // A defective runtime-error sidecar whose transcript carries no
        // framing at all: the snapshot parse fails with a bounded detail.
        SidecarExpectations.ErrorExpectation error = err("E8001",
            "expected int", "backend-runtime/runtime-errors/x.deal", 6, 21);
        SidecarExpectations.RuntimeExpectation expectation =
            runtimeError(error, "done\n");
        assertMismatch(compare("luajit", expectation,
            executed("done\n", "", 1)),
            MismatchClass.ERROR_SNAPSHOT_MISMATCH, "luajit",
            "carries no well-formed DEAL_ERROR_CODE/DEAL_ERROR_SNAPSHOT");
    }

    private static void framingCodeMismatch() {
        // The framing code line must equal the snapshot's code field.
        SidecarExpectations.ErrorExpectation error = err("E8001",
            "expected int", "backend-runtime/runtime-errors/x.deal", 6, 21);
        String inconsistent = ErrorSnapshot.CODE_LINE_PREFIX + "E9999\n"
            + ErrorSnapshot.SNAPSHOT_LINE_PREFIX
            + ErrorSnapshot.canonicalJson(error) + "\n";
        SidecarExpectations.RuntimeExpectation expectation =
            runtimeError(error, inconsistent);
        assertMismatch(compare("luajit", expectation,
            executed(inconsistent, "", 1)),
            MismatchClass.ERROR_SNAPSHOT_MISMATCH, "luajit",
            "carries no well-formed DEAL_ERROR_CODE/DEAL_ERROR_SNAPSHOT");
    }

    private static void nonCanonicalSnapshotDirectValidation() {
        // Defense in depth: even when the transcript bytes match (a
        // sidecar whose own snapshot violates the canonical
        // serialization), the snapshot's canonical validation fails.
        SidecarExpectations.ErrorExpectation error = err("E8001",
            "expected int", "backend-runtime/runtime-errors/x.deal", 6, 21);
        String nonCanonical = "{\"message\":\"expected int\",\"code\":\"E8001\","
            + "\"sourceFile\":\"backend-runtime/runtime-errors/x.deal\","
            + "\"line\":6,\"column\":21}";
        String framing = ErrorSnapshot.CODE_LINE_PREFIX + "E8001\n"
            + ErrorSnapshot.SNAPSHOT_LINE_PREFIX + nonCanonical + "\n";
        SidecarExpectations.RuntimeExpectation expectation =
            runtimeError(error, framing);
        assertMismatch(compare("luajit", expectation,
            executed(framing, "", 1)),
            MismatchClass.ERROR_SNAPSHOT_MISMATCH, "luajit",
            "carries no well-formed DEAL_ERROR_CODE/DEAL_ERROR_SNAPSHOT");
    }

    private static void rejectionCases() {
        SidecarExpectations.RuntimeExpectation.Rejected rejection =
            new SidecarExpectations.RuntimeExpectation.Rejected(
                "compile-reject", "E6006", OptionalInt.of(3),
                OptionalInt.of(9));

        // The exact pinned diagnostic object passes.
        check(compare("jvm", rejection,
            new LaneExecution.Rejected("E6006", OptionalInt.of(3),
                OptionalInt.of(9))).isEmpty(),
            "compile-reject: the exact pinned diagnostic passes on jvm");

        // A different rejection code is a mismatch naming the code.
        assertMismatch(compare("jvm", rejection,
            LaneExecution.Rejected.of("E6000")),
            MismatchClass.COMPILE_REJECT_MISMATCH, "jvm",
            "diagnostic.code must be E6006, got E6000");

        // An execution where a rejection is pinned.
        assertMismatch(compare("js", rejection,
            executed("", "", 0)),
            MismatchClass.COMPILE_REJECT_MISMATCH, "js",
            "the lane executed instead of rejecting");

        // A pinned line suppressed by the lane.
        assertMismatch(compare("jvm", rejection,
            new LaneExecution.Rejected("E6006", OptionalInt.empty(),
                OptionalInt.of(9))),
            MismatchClass.COMPILE_REJECT_MISMATCH, "jvm",
            "the lane suppressed the pinned diagnostic.line");

        // An unpinned field emitted by the lane.
        SidecarExpectations.RuntimeExpectation.Rejected codeOnly =
            new SidecarExpectations.RuntimeExpectation.Rejected(
                "compile-reject", "E6006", OptionalInt.empty(),
                OptionalInt.empty());
        assertMismatch(compare("js", codeOnly,
            new LaneExecution.Rejected("E6006", OptionalInt.of(3),
                OptionalInt.empty())),
            MismatchClass.COMPILE_REJECT_MISMATCH, "js",
            "the lane emitted diagnostic.line (3) the sidecar does not pin");

        // An unexpected rejection where an execution is pinned.
        SidecarExpectations.RuntimeExpectation expectation =
            runtimeOk("hello\n", "");
        assertMismatch(compare("luajit", expectation,
            LaneExecution.Rejected.of("E6006")),
            MismatchClass.COMPILE_REJECT_MISMATCH, "luajit",
            "unexpected compile rejection with diagnostic code E6006");
    }

    private static void infrastructurePassthrough() {
        String[] details = {
            "required artifact deal/runtime.lua missing",
            "the lane's required tool is missing or broken",
            "deadline exceeded — the execution was terminated",
            "the subprocess failed outside the DEAL outcome surface",
            "harness defect: the lane returned a malformed outcome"
        };
        MismatchClass[] classes = {
            MismatchClass.ARTIFACT_MISSING,
            MismatchClass.TOOL_MISSING,
            MismatchClass.LANE_TIMEOUT,
            MismatchClass.PROCESS_FAILURE,
            MismatchClass.HARNESS_DEFECT
        };
        SidecarExpectations.RuntimeExpectation expectation =
            runtimeOk("hello\n", "");
        for (int i = 0; i < classes.length; i++) {
            Optional<GateMismatch> result = compare("luajit", expectation,
                new LaneExecution.Infrastructure(classes[i], details[i]));
            check(result.isPresent(), classes[i] + " must mismatch");
            if (result.isEmpty()) {
                continue;
            }
            check(result.get().clazz() == classes[i],
                classes[i] + " class must pass through, got "
                    + result.get().clazz());
            check(result.get().infrastructure(),
                classes[i] + " must be labeled infrastructure");
            check(details[i].equals(result.get().detail()),
                classes[i] + " detail must pass through verbatim");
        }
        // Infrastructure outcomes never satisfy a case: every one of the
        // five classes is a mismatch.
        check(true, "infrastructure outcomes are reported separately and "
            + "never satisfy a case (all five classes asserted above)");
    }
}
