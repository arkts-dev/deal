package deal.test;

import deal.source.ScalarPosition;
import deal.source.ScalarSourceCursor;

/**
 * Tests for the non-lossy diagnostic range foundation (ISSUE-0216).
 *
 * <p>This file is the home of the ISSUE-0216 verification suites. The first
 * capability to land is the scalar position foundation, so this file
 * currently holds the {@link ScalarSourceCursor} / {@link ScalarPosition}
 * section: hand-computed walk expectations for astral characters, tabs, LF,
 * CRLF, and CR line endings, unpaired surrogates, mark/reset lookahead, and
 * the {@code scalarCount} overloads. Later capabilities extend this file
 * with the carrier, formatter, manifest-range, and producer-migration
 * sections.
 *
 * <p>Runs via main() using the check() helpers; exits non-zero on failure.
 */
public class DiagnosticRangeTest {

    private static int passed = 0;
    private static int failed = 0;

    private static void check(boolean condition, String message) {
        if (condition) {
            passed++;
        } else {
            failed++;
            System.err.println("FAIL: " + message);
        }
    }

    private static void fail(String message) {
        failed++;
        System.err.println("FAIL: " + message);
    }

    // =========================================================================
    // Test runner
    // =========================================================================

    public static void main(String[] args) {
        System.out.println("=== Running Diagnostic Range Tests ===");

        testCursorInitialAndEmpty();
        testCursorFullWalk();
        testCursorCrlfPairing();
        testCursorLineTerminators();
        testCursorSurrogates();
        testCursorMarkReset();
        testScalarCount();

        System.out.println();
        System.out.println("Passed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.err.println("DiagnosticRangeTest FAILED: " + failed + " failure(s)");
            System.exit(1);
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /** Asserts the cursor's line, column, scalarOffset, and position() record. */
    private static void checkCursor(String label, ScalarSourceCursor c,
                                    int line, int column, int offset) {
        check(c.line() == line,
            label + ": line " + c.line() + " != " + line);
        check(c.column() == column,
            label + ": column " + c.column() + " != " + column);
        check(c.scalarOffset() == offset,
            label + ": scalarOffset " + c.scalarOffset() + " != " + offset);
        ScalarPosition p = c.position();
        check(p.line() == line && p.column() == column && p.scalarOffset() == offset,
            label + ": position() record " + p + " != (" + line + "," + column + "," + offset + ")");
    }

    // =========================================================================
    // ScalarSourceCursor section
    // =========================================================================

    private static void testCursorInitialAndEmpty() {
        System.out.println("-- ScalarSourceCursor: initial, empty, and null source --");

        ScalarSourceCursor c = new ScalarSourceCursor("x");
        checkCursor("initial non-empty", c, 1, 1, 0);
        check(!c.atEnd(), "initial non-empty: atEnd() true before any advance");
        check(c.peekScalar() == 'x', "initial non-empty: peekScalar() != 'x'");

        ScalarSourceCursor empty = new ScalarSourceCursor("");
        check(empty.atEnd(), "empty source: atEnd() not true");
        check(empty.peekScalar() == -1, "empty source: peekScalar() != -1");
        check(empty.advance() == -1, "empty source: advance() != -1");
        checkCursor("empty source after advance", empty, 1, 1, 0);

        // Errors: none — a null source is treated as empty.
        ScalarSourceCursor nullSrc = new ScalarSourceCursor(null);
        check(nullSrc.atEnd(), "null source: atEnd() not true");
        check(nullSrc.peekScalar() == -1, "null source: peekScalar() != -1");
        check(nullSrc.advance() == -1, "null source: advance() != -1");
        checkCursor("null source after advance", nullSrc, 1, 1, 0);
    }

    private static void testCursorFullWalk() {
        System.out.println("-- ScalarSourceCursor: full walk (astral, tab, LF/CRLF/CR, unpaired surrogate) --");

        // Fixture: 'a', tab, U+1F600 (astral), CRLF, 'c', lone CR, 'd',
        // unpaired high surrogate U+D800, 'e'.
        // UTF-16 length 11, decoded scalar count 10.
        String s = "a\t\uD83D\uDE00\r\nc\rd\uD800e";
        ScalarSourceCursor c = new ScalarSourceCursor(s);

        // Hand-computed expected state after each advance.
        // CRLF: the '\r' increments the line (1->2) and resets the column;
        // the following '\n' consumes no further line/column change.
        int[] expectedLine =   {1, 1, 1, 2, 2, 2, 3, 3, 3, 3};
        int[] expectedColumn = {2, 3, 4, 1, 1, 2, 1, 2, 3, 4};
        int[] expectedOffset = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
        int[] expectedScalar = {'a', '\t', 0x1F600, '\r', '\n', 'c', '\r', 'd', 0xD800, 'e'};

        int line = 1;
        int column = 1;
        int offset = 0;
        for (int i = 0; i < expectedLine.length; i++) {
            check(c.peekScalar() == expectedScalar[i],
                "walk step " + i + ": peekScalar() " + c.peekScalar()
                    + " != " + expectedScalar[i]);
            checkCursor("walk step " + i + " before advance", c, line, column, offset);
            int consumed = c.advance();
            check(consumed == expectedScalar[i],
                "walk step " + i + ": advance() returned " + consumed
                    + " != " + expectedScalar[i]);
            line = expectedLine[i];
            column = expectedColumn[i];
            offset = expectedOffset[i];
            checkCursor("walk step " + i + " after advance", c, line, column, offset);
        }

        check(c.atEnd(), "full walk: atEnd() not true after all scalars");
        check(c.peekScalar() == -1, "full walk: peekScalar() != -1 at end");
        check(c.advance() == -1, "full walk: advance() != -1 at end");
        checkCursor("full walk after end", c, 3, 4, 10);

        // Independent recomputation: the scalar offset must equal the JDK
        // code-point count of the fixture (unpaired surrogates included).
        check(c.scalarOffset() == s.codePointCount(0, s.length()),
            "full walk: final scalarOffset " + c.scalarOffset()
                + " != codePointCount " + s.codePointCount(0, s.length()));

        // The tab at step 1 increments the column by exactly one scalar.
        check(expectedColumn[1] == 3 && expectedColumn[0] == 2,
            "tab: column incremented by one (1->2->3 across 'a' and tab)");
    }

    private static void testCursorCrlfPairing() {
        System.out.println("-- ScalarSourceCursor: CRLF pairing (two scalars, one line break) --");

        // The canonical pair: '\r' increments the line and resets the column;
        // the immediately following '\n' increments no line, resets no
        // column, and still counts one scalar offset.
        ScalarSourceCursor pair = new ScalarSourceCursor("\r\n");
        check(pair.peekScalar() == '\r', "CRLF: peek != '\\r'");
        check(pair.advance() == '\r', "CRLF: advance() != '\\r'");
        checkCursor("CRLF after '\\r'", pair, 2, 1, 1);
        check(pair.peekScalar() == '\n', "CRLF: peek != '\\n'");
        check(pair.advance() == '\n', "CRLF: advance() != '\\n'");
        checkCursor("CRLF after '\\n'", pair, 2, 1, 2);
        check(pair.atEnd(), "CRLF: atEnd() not true after the pair");
        check(pair.scalarOffset() == 2, "CRLF: pair must count two scalars");

        // Two consecutive pairs: each pair is one line break.
        ScalarSourceCursor twoPairs = new ScalarSourceCursor("\r\n\r\n");
        twoPairs.advance(); // '\r'
        checkCursor("CRLF CRLF after 1", twoPairs, 2, 1, 1);
        twoPairs.advance(); // '\n'
        checkCursor("CRLF CRLF after 2", twoPairs, 2, 1, 2);
        twoPairs.advance(); // '\r'
        checkCursor("CRLF CRLF after 3", twoPairs, 3, 1, 3);
        twoPairs.advance(); // '\n'
        checkCursor("CRLF CRLF after 4", twoPairs, 3, 1, 4);
        check(twoPairs.atEnd(), "CRLF CRLF: atEnd() not true");

        // A '\r' following a '\r' is its own line break (only the single
        // immediately following '\n' pairs with the preceding '\r').
        ScalarSourceCursor crCrLf = new ScalarSourceCursor("\r\r\n");
        crCrLf.advance(); // '\r' -> line 2
        checkCursor("CR CR LF after 1", crCrLf, 2, 1, 1);
        crCrLf.advance(); // '\r' -> line 3 (not paired with the first '\r')
        checkCursor("CR CR LF after 2", crCrLf, 3, 1, 2);
        crCrLf.advance(); // '\n' -> pairs with the second '\r': no change
        checkCursor("CR CR LF after 3", crCrLf, 3, 1, 3);
        check(crCrLf.atEnd(), "CR CR LF: atEnd() not true");

        // Two bare LFs are two line breaks.
        ScalarSourceCursor lfLf = new ScalarSourceCursor("\n\n");
        lfLf.advance();
        checkCursor("LF LF after 1", lfLf, 2, 1, 1);
        lfLf.advance();
        checkCursor("LF LF after 2", lfLf, 3, 1, 2);
        check(lfLf.atEnd(), "LF LF: atEnd() not true");
    }

    private static void testCursorLineTerminators() {
        System.out.println("-- ScalarSourceCursor: lone LF, lone CR, and tab arithmetic --");

        ScalarSourceCursor lf = new ScalarSourceCursor("\n");
        lf.advance();
        checkCursor("lone LF", lf, 2, 1, 1);
        check(lf.atEnd(), "lone LF: atEnd() not true");

        ScalarSourceCursor cr = new ScalarSourceCursor("\r");
        cr.advance();
        checkCursor("lone CR", cr, 2, 1, 1);
        check(cr.atEnd(), "lone CR: atEnd() not true");

        ScalarSourceCursor tabs = new ScalarSourceCursor("\t\t");
        tabs.advance();
        checkCursor("tab 1", tabs, 1, 2, 1);
        tabs.advance();
        checkCursor("tab 2", tabs, 1, 3, 2);
        check(tabs.atEnd(), "tabs: atEnd() not true");

        ScalarSourceCursor lfMid = new ScalarSourceCursor("x\ny");
        lfMid.advance();
        checkCursor("x LF y after 'x'", lfMid, 1, 2, 1);
        lfMid.advance();
        checkCursor("x LF y after LF", lfMid, 2, 1, 2);
        lfMid.advance();
        checkCursor("x LF y after 'y'", lfMid, 2, 2, 3);

        ScalarSourceCursor crMid = new ScalarSourceCursor("x\ry");
        crMid.advance();
        checkCursor("x CR y after 'x'", crMid, 1, 2, 1);
        crMid.advance();
        checkCursor("x CR y after CR", crMid, 2, 1, 2);
        crMid.advance();
        checkCursor("x CR y after 'y'", crMid, 2, 2, 3);
    }

    private static void testCursorSurrogates() {
        System.out.println("-- ScalarSourceCursor: surrogate pairs and unpaired surrogates --");

        // A valid pair is one advance, one column, one offset.
        ScalarSourceCursor pair = new ScalarSourceCursor("\uD83D\uDE00");
        check(pair.peekScalar() == 0x1F600, "pair: peekScalar() != U+1F600");
        check(pair.advance() == 0x1F600, "pair: advance() != U+1F600");
        checkCursor("pair after advance", pair, 1, 2, 1);
        check(pair.atEnd(), "pair: atEnd() not true after the pair");
        check(pair.peekScalar() == -1, "pair: peekScalar() != -1 at end");
        check(pair.advance() == -1, "pair: advance() != -1 at end");
        checkCursor("pair after end", pair, 1, 2, 1);

        // U+10000 (linear-B syllable) is a supplementary pair as well.
        ScalarSourceCursor pair2 = new ScalarSourceCursor("\uD800\uDC00");
        check(pair2.peekScalar() == 0x10000, "pair2: peekScalar() != U+10000");
        pair2.advance();
        checkCursor("pair2 after advance", pair2, 1, 2, 1);

        // Pair in the middle of a line: still one column.
        ScalarSourceCursor mid = new ScalarSourceCursor("a\uD83D\uDE00b");
        mid.advance(); // 'a'
        checkCursor("a pair b after 'a'", mid, 1, 2, 1);
        mid.advance(); // pair
        checkCursor("a pair b after pair", mid, 1, 3, 2);
        mid.advance(); // 'b'
        checkCursor("a pair b after 'b'", mid, 1, 4, 3);

        // An unpaired high surrogate at end of input is one recovery unit.
        ScalarSourceCursor loneHigh = new ScalarSourceCursor("\uD83D");
        check(loneHigh.peekScalar() == 0xD83D, "lone high: peekScalar() != U+D83D");
        check(loneHigh.advance() == 0xD83D, "lone high: advance() != U+D83D");
        checkCursor("lone high after advance", loneHigh, 1, 2, 1);
        check(loneHigh.atEnd(), "lone high: atEnd() not true");

        // An unpaired low surrogate is one recovery unit.
        ScalarSourceCursor loneLow = new ScalarSourceCursor("\uDC00");
        check(loneLow.peekScalar() == 0xDC00, "lone low: peekScalar() != U+DC00");
        loneLow.advance();
        checkCursor("lone low after advance", loneLow, 1, 2, 1);
        check(loneLow.atEnd(), "lone low: atEnd() not true");

        // A high surrogate followed by a non-low-surrogate is unpaired.
        ScalarSourceCursor highThenA = new ScalarSourceCursor("\uD83D\u0041");
        highThenA.advance(); // unpaired high surrogate
        checkCursor("high-then-A after high", highThenA, 1, 2, 1);
        highThenA.advance(); // 'A'
        checkCursor("high-then-A after 'A'", highThenA, 1, 3, 2);

        // No exception for any surrogate shape; advance past end is a no-op.
        ScalarSourceCursor recovery = new ScalarSourceCursor("\uD83D\uDC00\uD83D");
        recovery.advance(); // valid pair
        recovery.advance(); // unpaired high at end
        checkCursor("recovery walk", recovery, 1, 3, 2);
        recovery.advance(); // no-op at end
        checkCursor("recovery walk after end", recovery, 1, 3, 2);
    }

    private static void testCursorMarkReset() {
        System.out.println("-- ScalarSourceCursor: mark/reset lookahead --");

        String s = "a\t\uD83D\uDE00\r\nc\rd\uD800e";
        ScalarSourceCursor c = new ScalarSourceCursor(s);

        // Mark at start.
        ScalarSourceCursor.Mark start = c.mark();
        check(start.line() == 1 && start.column() == 1
                && start.scalarOffset() == 0 && start.index() == 0,
            "mark at start: unexpected snapshot " + start);

        // Advance three scalars ('a', tab, U+1F600) and snapshot mid-walk.
        c.advance();
        c.advance();
        c.advance();
        checkCursor("before mark", c, 1, 4, 3);
        ScalarSourceCursor.Mark m = c.mark();
        check(m.line() == 1 && m.column() == 4
                && m.scalarOffset() == 3 && m.index() == 4,
            "mid-walk mark: unexpected snapshot " + m);

        // Advance further (CRLF pair) and verify reset restores exactly.
        c.advance();
        c.advance();
        checkCursor("after further advances", c, 2, 1, 5);
        c.reset(m);
        checkCursor("after reset to mid-walk mark", c, 1, 4, 3);
        check(c.peekScalar() == '\r', "after reset: peekScalar() != '\\r'");

        // Deterministic: the remaining walk reproduces the fresh-cursor state.
        for (int i = 0; i < 7; i++) {
            c.advance();
        }
        checkCursor("re-walk to end after reset", c, 3, 4, 10);
        check(c.atEnd(), "re-walk: atEnd() not true");

        // Mark at end.
        ScalarSourceCursor.Mark end = c.mark();
        c.advance(); // no-op
        c.reset(end);
        checkCursor("mark at end after reset", c, 3, 4, 10);
        check(c.atEnd(), "mark at end: atEnd() not true after reset");

        // Reset to the start mark restores the initial state exactly.
        c.reset(start);
        checkCursor("reset to start mark", c, 1, 1, 0);
        check(c.peekScalar() == 'a', "reset to start: peekScalar() != 'a'");

        // CRLF pairing state survives mark/reset: a snapshot taken between
        // the '\r' and the '\n' must still pair the '\n' with the '\r'.
        ScalarSourceCursor pair = new ScalarSourceCursor("\r\n");
        pair.advance(); // '\r'
        checkCursor("pair before mark", pair, 2, 1, 1);
        ScalarSourceCursor.Mark pm = pair.mark();
        pair.advance(); // '\n' -> CRLF continuation
        checkCursor("pair after '\\n'", pair, 2, 1, 2);
        pair.reset(pm);
        checkCursor("pair after reset between CR and LF", pair, 2, 1, 1);
        check(pair.peekScalar() == '\n', "pair after reset: peek != '\\n'");
        pair.advance(); // still CRLF continuation: line stays 2, column stays 1
        checkCursor("pair after re-advance of '\\n'", pair, 2, 1, 2);

        // reset(null) is a no-op.
        pair.reset(null);
        checkCursor("reset(null)", pair, 2, 1, 2);
    }

    private static void testScalarCount() {
        System.out.println("-- ScalarSourceCursor: scalarCount overloads --");

        String s = "a\t\uD83D\uDE00\r\nc\rd\uD800e";

        check(ScalarSourceCursor.scalarCount(s) == 10,
            "scalarCount(fixture) " + ScalarSourceCursor.scalarCount(s) + " != 10");
        check(ScalarSourceCursor.scalarCount(s) == s.codePointCount(0, s.length()),
            "scalarCount(fixture) != codePointCount(" + s.codePointCount(0, s.length()) + ")");
        check(ScalarSourceCursor.scalarCount(s) == ScalarSourceCursor.scalarCount(s, 0, s.length()),
            "scalarCount(s) != scalarCount(s, 0, s.length())");

        // Agreement with cursor offsets: record the cursor's UTF-16 index and
        // scalar offset at every scalar boundary, then verify that
        // scalarCount over every substring [i..j) of boundaries equals the
        // scalar-offset difference.
        ScalarSourceCursor c = new ScalarSourceCursor(s);
        int[] index = new int[11];
        int[] offset = new int[11];
        index[0] = c.mark().index();
        offset[0] = c.scalarOffset();
        int boundary = 1;
        while (!c.atEnd()) {
            c.advance();
            index[boundary] = c.mark().index();
            offset[boundary] = c.scalarOffset();
            boundary++;
        }
        check(boundary == 11, "boundary count " + boundary + " != 11");
        for (int i = 0; i < boundary; i++) {
            for (int j = i; j < boundary; j++) {
                int counted = ScalarSourceCursor.scalarCount(s, index[i], index[j]);
                check(counted == offset[j] - offset[i],
                    "scalarCount(s, " + index[i] + ", " + index[j] + ") = " + counted
                        + " != offset difference " + (offset[j] - offset[i]));
            }
        }

        // Surrogate-pair counting.
        check(ScalarSourceCursor.scalarCount("\uD83D\uDE00") == 1,
            "scalarCount(valid pair) != 1");
        check(ScalarSourceCursor.scalarCount("\uD83D") == 1,
            "scalarCount(lone high) != 1");
        check(ScalarSourceCursor.scalarCount("\uDC00") == 1,
            "scalarCount(lone low) != 1");
        check(ScalarSourceCursor.scalarCount("\uD83D\uDE00\uD800") == 2,
            "scalarCount(pair + lone high) != 2");
        check(ScalarSourceCursor.scalarCount("\uD800\uD83D\uDE00") == 2,
            "scalarCount(lone high + pair) != 2");
        check(ScalarSourceCursor.scalarCount("\uD83D\u0041") == 2,
            "scalarCount(lone high + 'A') != 2");
        check(ScalarSourceCursor.scalarCount("ab") == 2, "scalarCount(\"ab\") != 2");
        check(ScalarSourceCursor.scalarCount("\r\n") == 2, "scalarCount(CRLF) != 2");
        check(ScalarSourceCursor.scalarCount("\t") == 1, "scalarCount(tab) != 1");

        // Substring overload: direct code-point count of the UTF-16 range.
        check(ScalarSourceCursor.scalarCount(s, 0, 1) == 1,
            "scalarCount(s, 0, 1) != 1");
        check(ScalarSourceCursor.scalarCount(s, 1, 4) == 2,
            "scalarCount(s, 1, 4) != 2"); // tab + astral pair
        check(ScalarSourceCursor.scalarCount(s, 4, 6) == 2,
            "scalarCount(s, 4, 6) != 2"); // CRLF
        check(ScalarSourceCursor.scalarCount(s, 9, 10) == 1,
            "scalarCount(s, 9, 10) != 1"); // lone high surrogate
        check(ScalarSourceCursor.scalarCount(s, 9, 11) == 2,
            "scalarCount(s, 9, 11) != 2"); // lone high surrogate + 'e'
        // A pair split by the range boundary yields two recovery units.
        check(ScalarSourceCursor.scalarCount("\uD83D\uDE00", 0, 1) == 1,
            "scalarCount(split high half) != 1");
        check(ScalarSourceCursor.scalarCount("\uD83D\uDE00", 1, 2) == 1,
            "scalarCount(split low half) != 1");

        // Errors: none — degenerate bounds never throw.
        check(ScalarSourceCursor.scalarCount(s, -5, 100) == 10,
            "scalarCount clamped bounds != 10");
        check(ScalarSourceCursor.scalarCount(s, 100, 200) == 0,
            "scalarCount beyond end != 0");
        check(ScalarSourceCursor.scalarCount(s, 5, 2) == 0,
            "scalarCount from > to != 0");
        check(ScalarSourceCursor.scalarCount(s, 4, 4) == 0,
            "scalarCount empty range != 0");
        check(ScalarSourceCursor.scalarCount("") == 0, "scalarCount(\"\") != 0");
        check(ScalarSourceCursor.scalarCount(null) == 0, "scalarCount(null) != 0");
        check(ScalarSourceCursor.scalarCount(null, 0, 5) == 0,
            "scalarCount(null, 0, 5) != 0");
    }
}
