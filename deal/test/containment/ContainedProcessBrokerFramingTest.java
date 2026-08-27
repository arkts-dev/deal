package deal.test.containment;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Framing regression tests for the broker client's read path
 * (ISSUE-0182 review finding): the read-side validation must enforce
 * the canonical per-type size caps exactly as the canonical parser does
 * (tools/src/protocol.c dealpg4_parse, DEALPG4_PARSE_ERR_OVERSIZE_LINE /
 * DEALPG4_PARSE_ERR_OVERSIZE_OUT_CHUNK):
 *
 * <ul>
 *   <li>OUT chunk &le; 65536 hex chars (32 KiB raw)</li>
 *   <li>OUT line &le; 65536 + 64 bytes</li>
 *   <li>every other received record &le; 8192 bytes</li>
 *   <li>each cap counts the LF terminator, and the global cap of
 *       131072 bytes applies first</li>
 * </ul>
 *
 * <p>The oversize inputs below are the identical negative set
 * cross-validated against the canonical C parser: the two review
 * evidence inputs (an 80000-hex-char OUT chunk, a REPORT with a
 * 9000-char failureToken) and the C suite's group-1 oversize cases
 * (tools/test/protocol-unit-tests.c). Both parsers must reject every
 * one of them identically with {@code PROTOCOL_ERROR}; boundary-exact
 * lines at the caps must be accepted exactly like the C parser accepts
 * them, and every well-formed broker-facing record must still parse.
 * No socket or process is involved: this test exercises the
 * package-private parse seam directly.
 */
public final class ContainedProcessBrokerFramingTest {

    private static int passed = 0;
    private static int failed = 0;

    private ContainedProcessBrokerFramingTest() {
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

    private static String repeat(char c, int count) {
        char[] chars = new char[count];
        Arrays.fill(chars, c);
        return new String(chars);
    }

    private static byte[] line(String content) {
        return content.getBytes(StandardCharsets.US_ASCII);
    }

    /** Asserts the line is rejected as PROTOCOL_ERROR by the named cap. */
    private static void expectOversize(String what, String content,
                                       String messageFragment) {
        try {
            ContainedProcessBroker.parseRecord(line(content));
            check(false, what + ": oversize line was accepted");
        } catch (ContainedProcessBroker.BrokerProtocolException e) {
            check(e.getMessage().contains(messageFragment),
                    what + ": wrong rejection ('" + e.getMessage()
                            + "'), expected cap '" + messageFragment + "'");
        }
    }

    /** Asserts the line is rejected as PROTOCOL_ERROR (any framing defect). */
    private static void expectProtocolError(String what, String content) {
        try {
            ContainedProcessBroker.parseRecord(line(content));
            check(false, what + ": framing-defective line was accepted");
        } catch (ContainedProcessBroker.BrokerProtocolException e) {
            check(true, what);
        }
    }

    private static void expectAccepted(String what, String content,
                                       String expectedType) {
        try {
            ContainedProcessBroker.Record record =
                    ContainedProcessBroker.parseRecord(line(content));
            check(record.type.equals(expectedType),
                    what + ": parsed type " + record.type + ", expected " + expectedType);
        } catch (RuntimeException e) {
            check(false, what + ": unexpected rejection: " + e);
        }
    }

    private static void run() {
        System.out.println("=== Running ContainedProcessBroker Framing Tests ===");

        /* Review evidence input 1: OUT with an 80000-hex-char chunk.
         * The canonical parser rejects it as oversize record line; the
         * client must reject it identically (line > 65536+64). */
        expectOversize("review input 1: OUT 80000-hex-char chunk",
                "DEALPG4 OUT 7 out " + repeat('a', 80000),
                "OUT record line exceeds the 65600-byte cap");

        /* Review evidence input 2: REPORT with a 9000-char failureToken
         * (~9 KB line). The canonical parser rejects it as oversize
         * record line (8192-byte cap); the client must reject it
         * identically. */
        expectOversize("review input 2: REPORT 9000-char failureToken",
                "DEALPG4 REPORT 0 0 1 2 3 4 5 6 7 8 9 10 11 0 0 1 1 1 "
                        + repeat('c', 9000),
                "record REPORT line exceeds the 8192-byte cap");

        /* C-parallel negatives (tools/test/protocol-unit-tests.c
         * group 1 oversize cases). */
        expectOversize("C-parallel: DONE 8200-char line over the 8192-byte cap",
                "DEALPG4 DONE " + repeat('c', 8200),
                "record DONE line exceeds the 8192-byte cap");
        expectOversize("C-parallel: OUT 65584-hex-char chunk (line over 65536+64)",
                "DEALPG4 OUT 1 out " + repeat('a', 65584),
                "OUT record line exceeds the 65600-byte cap");
        expectOversize("C-parallel: OUT 65538-hex-char chunk (line under cap, chunk over)",
                "DEALPG4 OUT 1 out " + repeat('a', 65538),
                "OUT chunk exceeds the 65536-hex-char cap");

        /* Boundary-exact cases: the caps count the LF exactly like
         * protocol.c's len accounting (content + 1). */
        expectAccepted("boundary: OUT chunk at exactly 65536 hex chars",
                "DEALPG4 OUT 1 out " + repeat('a', 65536), "OUT");
        /* 65537 hex chars is odd-length: the hex grammar fires first
         * (canonical FIELD_ENCODING, also PROTOCOL_ERROR with channel
         * close); the even-length 65538 case below exercises the
         * chunk-cap check itself. */
        expectProtocolError("boundary: OUT chunk at 65537 hex chars (odd length)",
                "DEALPG4 OUT 1 out " + repeat('a', 65537));
        expectOversize("boundary: OUT chunk at 65538 hex chars (even, over the cap)",
                "DEALPG4 OUT 1 out " + repeat('a', 65538),
                "OUT chunk exceeds the 65536-hex-char cap");

        String reportBase = "DEALPG4 REPORT 0 0 1 2 3 4 5 6 7 8 9 10 11 0 0 1 1 1 ";
        int maxToken = 8191 - reportBase.length(); /* content + LF == 8192 */
        expectAccepted("boundary: REPORT at exactly 8192 bytes incl LF",
                reportBase + repeat('c', maxToken), "REPORT");
        expectOversize("boundary: REPORT at 8193 bytes incl LF",
                reportBase + repeat('c', maxToken + 1),
                "record REPORT line exceeds the 8192-byte cap");

        /* HELLO_OK version: the canonical VERSION_4 field class is
         * decimal with value exactly 4 (protocol.c
         * DEALPG4_F_VERSION_4) — leading zeros are valid ("04", "004")
         * and string equality with "4" is not the check; a version
         * whose decimal value is not 4, a non-decimal version, or an
         * int64-overflowing version is a framing defect. Both parsers
         * classify these identically (C-verified). */
        expectAccepted("HELLO_OK version: leading-zero decimal '04' is exactly 4",
                "DEALPG4 HELLO_OK 04 63", "HELLO_OK");
        expectAccepted("HELLO_OK version: leading-zero decimal '004' is exactly 4",
                "DEALPG4 HELLO_OK 004 63", "HELLO_OK");
        expectAccepted("HELLO_OK version: '00004' is exactly 4",
                "DEALPG4 HELLO_OK 00004 63", "HELLO_OK");
        expectProtocolError("HELLO_OK version: decimal value 5 is not exactly 4",
                "DEALPG4 HELLO_OK 5 63");
        expectProtocolError("HELLO_OK version: decimal value 40 is not exactly 4",
                "DEALPG4 HELLO_OK 40 63");
        expectProtocolError("HELLO_OK version: decimal value 3 is not exactly 4",
                "DEALPG4 HELLO_OK 3 63");
        expectProtocolError("HELLO_OK version: '4x' is not a decimal",
                "DEALPG4 HELLO_OK 4x 63");
        expectProtocolError("HELLO_OK version: '-4' is not a decimal",
                "DEALPG4 HELLO_OK -4 63");
        expectProtocolError("HELLO_OK version: empty field is not a decimal",
                "DEALPG4 HELLO_OK  63");
        expectProtocolError("HELLO_OK version: int64 overflow",
                "DEALPG4 HELLO_OK 99999999999999999999999999 63");

        /* The global 131072-byte cap still fires first for a line
         * beyond it (canonical check order). */
        expectOversize("global cap: 200000-hex-char OUT line",
                "DEALPG4 OUT 1 out " + repeat('a', 200000),
                "record line exceeds the 131072-byte cap");

        /* Smoke: every well-formed broker-facing record still parses
         * after the cap tightening. */
        String nonce = "0123456789abcdef0123456789abcdef";
        String[][] valid = {
            {"HELLO_OK", "4 63"},
            {"READY_ACK", nonce},
            {"INVOKED", "7 run_phase"},
            {"REJECT", "7 run_phase CANCEL_AUTH_FAILED"},
            {"STUB_READY", "7 1234 1234 1234 " + nonce},
            {"STARTED", "7"},
            {"EXEC_FAILED", "7 2"},
            {"OUT", "7 out 68656c6c6f"},
            {"OUT_END", "7 err"},
            {"REPORT", "0 0 1 2 3 4 5 6 7 8 9 10 11 0 0 1 1 1 -"},
            {"CLEAN", "7 success"},
            {"FAILED", "7 EXECUTION_TIMEOUT"},
            {"DONE", "clean"},
            {"BYE", ""},
        };
        for (String[] pair : valid) {
            String content = "DEALPG4 " + pair[0]
                    + (pair[1].isEmpty() ? "" : " " + pair[1]);
            expectAccepted("smoke: " + pair[0], content, pair[0]);
        }

        System.out.println("Passed: " + passed + ", Failed: " + failed);
    }

    public static void main(String[] args) {
        run();
        if (failed > 0) {
            System.exit(1);
        }
    }
}
