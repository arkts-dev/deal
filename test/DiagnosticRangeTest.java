package deal.test;

import deal.ast.Span;
import deal.ast.TemplateLiteralExpr;
import deal.ast.TokenType;
import deal.ast.VariableDeclaration;
import deal.diagnostics.CompilerDiagnostic;
import deal.diagnostics.DiagnosticCode;
import deal.diagnostics.DiagnosticFormatter;
import deal.diagnostics.DiagnosticNote;
import deal.diagnostics.DiagnosticRange;
import deal.diagnostics.DiagnosticStructuredOutput;
import deal.diagnostics.RangeOrigin;
import deal.checker.CheckResult;
import deal.checker.NameResolver;
import deal.checker.SymbolTable;
import deal.checker.TypeChecker;
import deal.lexer.Diagnostic;
import deal.lexer.LexResult;
import deal.lexer.Lexer;
import deal.lexer.Token;
import deal.parser.ParseResult;
import deal.parser.Parser;
import deal.source.ScalarPosition;
import deal.source.ScalarSourceCursor;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tests for the non-lossy diagnostic range foundation (ISSUE-0216 /
 * ISSUE-0217 / ISSUE-0218 / ISSUE-0219).
 *
 * <p>This file is the home of the ISSUE-0216/ISSUE-0217/ISSUE-0218/
 * ISSUE-0219 verification suites. It holds the {@link ScalarSourceCursor} /
 * {@link ScalarPosition} section (hand-computed walk expectations for
 * astral characters, tabs, LF, CRLF, and CR line endings, unpaired
 * surrogates, mark/reset lookahead, and the {@code scalarCount}
 * overloads), the Token/Span scalar-offset section (the UNKNOWN_OFFSET
 * sentinel on convenience constructors, {@code hasScalarOffsets()},
 * verbatim offset preservation through {@code Token.withDirectives},
 * {@code Span.synthetic} UNKNOWN offsets, scalar-count recomputation
 * against {@code ScalarSourceCursor}, parser span-helper propagation, the
 * empty-program (0,0) span, and the past-end {@code peek()} pseudo-EOF
 * position carrying), and the ISSUE-0218 carrier section
 * ({@link DiagnosticRange}/{@link RangeOrigin}/{@link DiagnosticNote}/
 * {@link CompilerDiagnostic} record invariants, the D4
 * {@code Span.range()}/{@code Token.range()} conversions including the
 * UNKNOWN→SYNTHETIC origin pins, the SOURCE-implies-known-offsets
 * invariant, the D6 synthetic contract with anchor notes, the D9
 * normalization pins, and the D5 factory surface), and the ISSUE-0219
 * formatter/structured section ({@link DiagnosticFormatter} exact human
 * renderings for multi-line and zero-length SOURCE ranges, the canonical
 * SYNTHETIC shape, and both note renderings; {@link
 * DiagnosticStructuredOutput} exact deterministic JSON field order and
 * values; the formatted-vs-structured cross-check; and the {@code
 * CompilerDiagnostic.toString()} canonical-formatter delegation), and the
 * ISSUE-0224 template-interpolation rebasing section (sub-lexed
 * diagnostic ranges inside ${...} rebased to exact original scalar
 * offsets, the rebased sub-parser EOF token anchoring end-of-input errors
 * at the expression-end raw position, and raw-positioned E1042/D16
 * anchor ranges — each cross-checked against an independent
 * {@link ScalarSourceCursor} recomputation). Later capabilities extend
 * this file with the manifest-range and producer-migration sections.
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

    public static void main(String[] args) throws Exception {
        System.out.println("=== Running Diagnostic Range Tests ===");

        testCursorInitialAndEmpty();
        testCursorFullWalk();
        testCursorCrlfPairing();
        testCursorLineTerminators();
        testCursorSurrogates();
        testCursorMarkReset();
        testScalarCount();

        testTokenOffsets();
        testSpanOffsets();
        testSpanHelperPropagation();
        testPeekPseudoEof();
        testTemplateInterpolationRebasing();
        testCheckerProducerRanges();

        testCarrierRecords();
        testRangeConversions();
        testSourceImpliesKnownOffsets();
        testSyntheticContract();
        testD9Normalization();
        testFactorySurface();
        testFormatter();
        testStructuredOutput();
        testFormattedStructuredCrossCheck();
        testToStringDelegation();

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

    // =========================================================================
    // Token/Span scalar-offset section
    // =========================================================================

    private static void testTokenOffsets() {
        System.out.println("-- Token offsets: UNKNOWN sentinel, constructors, withDirectives --");

        check(Token.UNKNOWN_OFFSET == -1, "Token.UNKNOWN_OFFSET != -1");

        // 5-argument convenience constructor: no offset information, never 0.
        Token t5 = new Token(TokenType.IDENTIFIER, "x", 2, 3, 1);
        check(t5.startScalarOffset() == Token.UNKNOWN_OFFSET,
            "5-arg Token startScalarOffset " + t5.startScalarOffset() + " != UNKNOWN_OFFSET");
        check(t5.scalarLength() == Token.UNKNOWN_OFFSET,
            "5-arg Token scalarLength " + t5.scalarLength() + " != UNKNOWN_OFFSET");
        check(!t5.hasScalarOffsets(), "5-arg Token hasScalarOffsets() must be false");
        check(t5.endScalarOffset() == Token.UNKNOWN_OFFSET,
            "5-arg Token endScalarOffset() " + t5.endScalarOffset() + " != UNKNOWN_OFFSET");
        check(t5.line() == 2 && t5.column() == 3 && t5.length() == 1,
            "5-arg Token positions/length changed");
        check(t5.directives().isEmpty(), "5-arg Token directives must default to empty");

        // 6-argument convenience constructor with directives: UNKNOWN offsets.
        Token t6 = new Token(TokenType.EXPORT, "export", 1, 1, 6, List.of("@jsonable"));
        check(t6.startScalarOffset() == Token.UNKNOWN_OFFSET
                && t6.scalarLength() == Token.UNKNOWN_OFFSET,
            "6-arg Token must default both offsets to UNKNOWN_OFFSET");
        check(!t6.hasScalarOffsets(), "6-arg Token hasScalarOffsets() must be false");
        check(t6.directives().equals(List.of("@jsonable")),
            "6-arg Token directives lost");

        // Canonical constructor with known offsets: start 7, scalar length 3.
        Token known = new Token(TokenType.IDENTIFIER, "a\uD83D\uDE00b", 4, 5, 4, 7, 3, List.of());
        check(known.hasScalarOffsets(), "known-offset Token hasScalarOffsets() must be true");
        check(known.startScalarOffset() == 7 && known.scalarLength() == 3,
            "known-offset Token offsets (" + known.startScalarOffset() + ","
                + known.scalarLength() + ") != (7,3)");
        check(known.endScalarOffset() == 10,
            "known-offset Token endScalarOffset() " + known.endScalarOffset() + " != 10");

        // Independent recomputation: 'a' + U+1F600 + 'b' is three decoded
        // scalars and four UTF-16 units; start + scalarLength must equal the
        // expected half-open end offset.
        check(ScalarSourceCursor.scalarCount(known.lexeme()) == 3,
            "scalarCount(known.lexeme()) " + ScalarSourceCursor.scalarCount(known.lexeme())
                + " != 3");
        check(known.lexeme().length() == 4,
            "known-offset Token UTF-16 length " + known.lexeme().length() + " != 4");
        check(known.startScalarOffset() + known.scalarLength() == 10,
            "startScalarOffset + scalarLength != expected end offset 10");

        // withDirectives preserves offsets verbatim (known in -> known out)
        // and never routes through the offset-less convenience constructors.
        Token withD = known.withDirectives(List.of("@jsonable", "@deal-version 1.2"));
        check(withD.startScalarOffset() == 7 && withD.scalarLength() == 3,
            "withDirectives must preserve known offsets verbatim, got ("
                + withD.startScalarOffset() + "," + withD.scalarLength() + ")");
        check(withD.hasScalarOffsets(),
            "withDirectives result hasScalarOffsets() must stay true");
        check(withD.directives().equals(List.of("@jsonable", "@deal-version 1.2")),
            "withDirectives directives lost");
        check(withD.type() == known.type() && withD.lexeme().equals(known.lexeme())
                && withD.line() == 4 && withD.column() == 5 && withD.length() == 4,
            "withDirectives must copy all other fields");

        // withDirectives preserves UNKNOWN verbatim (UNKNOWN in -> UNKNOWN out).
        Token unknownD = t6.withDirectives(List.of());
        check(unknownD.startScalarOffset() == Token.UNKNOWN_OFFSET
                && unknownD.scalarLength() == Token.UNKNOWN_OFFSET,
            "withDirectives must preserve UNKNOWN offsets verbatim");
        check(!unknownD.hasScalarOffsets(),
            "withDirectives UNKNOWN result hasScalarOffsets() must stay false");
        check(unknownD.directives().isEmpty(),
            "withDirectives with empty list must clear directives");

        // hasScalarOffsets(): true iff exactly both components non-negative.
        check(!new Token(TokenType.IDENTIFIER, "x", 1, 1, 1, Token.UNKNOWN_OFFSET, 2, List.of())
                .hasScalarOffsets(),
            "negative start offset must make hasScalarOffsets() false");
        check(!new Token(TokenType.IDENTIFIER, "x", 1, 1, 1, 0, Token.UNKNOWN_OFFSET, List.of())
                .hasScalarOffsets(),
            "negative scalarLength must make hasScalarOffsets() false");
        check(new Token(TokenType.IDENTIFIER, "x", 1, 1, 1, 0, 0, List.of())
                .hasScalarOffsets(),
            "(0,0) offsets must make hasScalarOffsets() true");

        // Existing validation of line/column/length is unchanged.
        boolean threw = false;
        try {
            new Token(TokenType.IDENTIFIER, "x", 0, 1, 1);
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check(threw, "Token line < 1 validation must remain");
        threw = false;
        try {
            new Token(null, "x", 1, 1, 1);
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check(threw, "Token null type validation must remain");
    }

    private static void testSpanOffsets() {
        System.out.println("-- Span offsets: UNKNOWN sentinel, constructors, synthetic --");

        check(Span.UNKNOWN_OFFSET == -1, "Span.UNKNOWN_OFFSET != -1");

        // 5-argument convenience constructor: no offset information, never 0.
        Span s5 = new Span("f.deal", 1, 5, 1, 10);
        check(s5.startScalarOffset() == Span.UNKNOWN_OFFSET
                && s5.endScalarOffset() == Span.UNKNOWN_OFFSET,
            "5-arg Span must default both offsets to UNKNOWN_OFFSET");
        check(!s5.hasScalarOffsets(), "5-arg Span hasScalarOffsets() must be false");
        check(s5.startLine() == 1 && s5.startColumn() == 5
                && s5.endLine() == 1 && s5.endColumn() == 10,
            "5-arg Span positions changed");

        // Explicit-offset constructor: known half-open range [4, 9).
        Span known = new Span("f.deal", 2, 3, 2, 7, 4, 9);
        check(known.hasScalarOffsets(), "known-offset Span hasScalarOffsets() must be true");
        check(known.startScalarOffset() == 4 && known.endScalarOffset() == 9,
            "known-offset Span offsets (" + known.startScalarOffset() + ","
                + known.endScalarOffset() + ") != (4,9)");
        check(known.endScalarOffset() - known.startScalarOffset() == 5,
            "half-open span length " + (known.endScalarOffset() - known.startScalarOffset())
                + " != 5");
        check(known.startLine() == 2 && known.startColumn() == 3
                && known.endLine() == 2 && known.endColumn() == 7,
            "known-offset Span positions changed");

        // Span.synthetic remains (1,1,1,1) and carries UNKNOWN offsets.
        Span syn = Span.synthetic("f.deal");
        check(syn.file().equals("f.deal"), "Span.synthetic file");
        check(syn.startLine() == 1 && syn.startColumn() == 1
                && syn.endLine() == 1 && syn.endColumn() == 1,
            "Span.synthetic positions must remain (1,1,1,1)");
        check(!syn.hasScalarOffsets(), "Span.synthetic must carry UNKNOWN offsets");
        check(syn.startScalarOffset() == Span.UNKNOWN_OFFSET
                && syn.endScalarOffset() == Span.UNKNOWN_OFFSET,
            "Span.synthetic offsets (" + syn.startScalarOffset() + ","
                + syn.endScalarOffset() + ") != UNKNOWN_OFFSET");

        // hasScalarOffsets(): true iff exactly both components non-negative.
        check(!new Span("f", 1, 1, 1, 1, Span.UNKNOWN_OFFSET, 2).hasScalarOffsets(),
            "negative start offset must make Span hasScalarOffsets() false");
        check(!new Span("f", 1, 1, 1, 1, 0, Span.UNKNOWN_OFFSET).hasScalarOffsets(),
            "negative end offset must make Span hasScalarOffsets() false");
        check(new Span("f", 1, 1, 1, 1, 0, 0).hasScalarOffsets(),
            "(0,0) offsets must make Span hasScalarOffsets() true");

        // Existing validation pins are unchanged (end not before start).
        boolean threw = false;
        try {
            new Span("x", 2, 1, 1, 1);
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check(threw, "Span end-before-start validation must remain");
    }

    /**
     * Exercises the parser's span helpers through the public parse() entry:
     * spanOf (expression spans), spanBetween(Token,Token) (statement spans),
     * spanBetween(Span,Span) (program span), and the empty-program (0,0)
     * explicit-offset span.
     */
    private static void testSpanHelperPropagation() {
        System.out.println("-- Token/Span offsets: parser span helper propagation --");

        // Known offsets: hand-built tokens for "let x = 42;" at exact scalar
        // positions (offsets 0-11, EOF at scalar offset 11).
        Token let = new Token(TokenType.LET, "let", 1, 1, 3, 0, 3, List.of());
        Token name = new Token(TokenType.IDENTIFIER, "x", 1, 5, 1, 4, 1, List.of());
        Token eq = new Token(TokenType.EQ_SIGN, "=", 1, 7, 1, 6, 1, List.of());
        Token num = new Token(TokenType.INT_LITERAL, "42", 1, 9, 2, 8, 2, List.of());
        Token semi = new Token(TokenType.SEMICOLON, ";", 1, 11, 1, 10, 1, List.of());
        Token eof = new Token(TokenType.EOF, "", 1, 12, 0, 11, 0, List.of());

        ParseResult r = new Parser(List.of(let, name, eq, num, semi, eof), "f.deal").parse();
        check(r.diagnostics().isEmpty(), "known-offset program must parse clean, got "
            + r.diagnostics());
        Span prog = r.program().span();
        check(prog.hasScalarOffsets(), "program span must carry known offsets");
        check(prog.startScalarOffset() == 0 && prog.endScalarOffset() == 11,
            "program span offsets (" + prog.startScalarOffset() + ","
                + prog.endScalarOffset() + ") != (0,11)");
        check(prog.startLine() == 1 && prog.startColumn() == 1
                && prog.endLine() == 1 && prog.endColumn() == 12,
            "program span positions must stay (1,1)-(1,12)");

        // Statement span: spanBetween(Token,Token) — let start to the
        // consumed ';'/'EOF' end. Lexeme scalar counts recomputed
        // independently.
        check(ScalarSourceCursor.scalarCount("let") == 3, "scalarCount(\"let\") != 3");
        check(ScalarSourceCursor.scalarCount("42") == 2, "scalarCount(\"42\") != 2");
        check(let.startScalarOffset() + let.scalarLength() == 3,
            "let token end offset != 3");
        check(num.startScalarOffset() + num.scalarLength() == 10,
            "42 token end offset != 10");
        check(r.program().statements().size() == 1, "known-offset program stmt count");
        Span stmt = r.program().statements().get(0).span();
        check(stmt.startScalarOffset() == 0 && stmt.endScalarOffset() == 11,
            "statement span offsets (" + stmt.startScalarOffset() + ","
                + stmt.endScalarOffset() + ") != (0,11)");

        // UNKNOWN in -> UNKNOWN out: the same program built with offset-less
        // convenience constructors everywhere.
        Token ulet = new Token(TokenType.LET, "let", 1, 1, 3);
        Token uname = new Token(TokenType.IDENTIFIER, "x", 1, 5, 1);
        Token ueq = new Token(TokenType.EQ_SIGN, "=", 1, 7, 1);
        Token unum = new Token(TokenType.INT_LITERAL, "42", 1, 9, 2);
        Token usemi = new Token(TokenType.SEMICOLON, ";", 1, 11, 1);
        Token ueof = new Token(TokenType.EOF, "", 1, 12, 0);
        ParseResult ur = new Parser(List.of(ulet, uname, ueq, unum, usemi, ueof),
            "f.deal").parse();
        check(ur.diagnostics().isEmpty(), "UNKNOWN-input program must parse clean");
        Span uprog = ur.program().span();
        check(!uprog.hasScalarOffsets(),
            "UNKNOWN-input program span must carry UNKNOWN offsets");
        check(uprog.startScalarOffset() == Span.UNKNOWN_OFFSET
                && uprog.endScalarOffset() == Span.UNKNOWN_OFFSET,
            "UNKNOWN-input program span offsets (" + uprog.startScalarOffset() + ","
                + uprog.endScalarOffset() + ") != UNKNOWN");
        check(uprog.startLine() == 1 && uprog.startColumn() == 1
                && uprog.endLine() == 1 && uprog.endColumn() == 12,
            "UNKNOWN-input program span positions must stay (1,1)-(1,12)");

        // Mixed: known start offset, UNKNOWN end offset -> the known start
        // propagates, the end stays UNKNOWN, hasScalarOffsets() is false.
        Token mlet = new Token(TokenType.LET, "let", 1, 1, 3, 0, 3, List.of());
        ParseResult mr = new Parser(List.of(mlet, uname, ueq, unum, usemi, ueof),
            "f.deal").parse();
        Span mprog = mr.program().span();
        check(mprog.startScalarOffset() == 0,
            "mixed-input span start offset must propagate the known start, got "
                + mprog.startScalarOffset());
        check(mprog.endScalarOffset() == Span.UNKNOWN_OFFSET,
            "mixed-input span end offset must stay UNKNOWN, got "
                + mprog.endScalarOffset());
        check(!mprog.hasScalarOffsets(),
            "mixed-input span hasScalarOffsets() must be false");

        // Empty program (no tokens at all): explicit (0,0) offsets, never
        // UNKNOWN, positions (1,1)-(1,1).
        Span empty = new Parser(List.of(), "f.deal").parse().program().span();
        check(empty.startLine() == 1 && empty.startColumn() == 1
                && empty.endLine() == 1 && empty.endColumn() == 1,
            "empty program span positions must stay (1,1)-(1,1)");
        check(empty.hasScalarOffsets(), "empty program span must carry explicit offsets");
        check(empty.startScalarOffset() == 0 && empty.endScalarOffset() == 0,
            "empty program span offsets (" + empty.startScalarOffset() + ","
                + empty.endScalarOffset() + ") != (0,0)");

        // EOF-token-only list is also an empty program with (0,0) offsets.
        Span eofOnly = new Parser(List.of(
            new Token(TokenType.EOF, "", 3, 4, 0, 7, 0, List.of())), "f.deal")
            .parse().program().span();
        check(eofOnly.hasScalarOffsets() && eofOnly.startScalarOffset() == 0
                && eofOnly.endScalarOffset() == 0,
            "EOF-only program span offsets (" + eofOnly.startScalarOffset() + ","
                + eofOnly.endScalarOffset() + ") != (0,0)");

        // Two statements: the program span is spanBetween(first.span(),
        // last.span()) — first statement start / last statement end.
        Token a = new Token(TokenType.IDENTIFIER, "a", 2, 3, 1, 5, 1, List.of());
        Token asemi = new Token(TokenType.SEMICOLON, ";", 2, 4, 1, 6, 1, List.of());
        Token b = new Token(TokenType.IDENTIFIER, "b", 3, 1, 1, 8, 1, List.of());
        Token bsemi = new Token(TokenType.SEMICOLON, ";", 3, 2, 1, 9, 1, List.of());
        Token eof2 = new Token(TokenType.EOF, "", 3, 3, 0, 10, 0, List.of());
        ParseResult two = new Parser(List.of(a, asemi, b, bsemi, eof2), "f.deal").parse();
        check(two.diagnostics().isEmpty(), "two-statement program must parse clean, got "
            + two.diagnostics());
        Span twospan = two.program().span();
        check(twospan.hasScalarOffsets(), "two-statement program span must have offsets");
        check(twospan.startScalarOffset() == 5 && twospan.endScalarOffset() == 9,
            "two-statement program span offsets (" + twospan.startScalarOffset() + ","
                + twospan.endScalarOffset() + ") != (5,9)");
        check(twospan.startLine() == 2 && twospan.startColumn() == 3
                && twospan.endLine() == 3 && twospan.endColumn() == 1,
            "two-statement program span positions must stay (2,3)-(3,1)");
    }

    private static void testPeekPseudoEof() {
        System.out.println("-- Token/Span offsets: peek() past-end pseudo-EOF position carrying --");

        // No EOF token in the input list (defensive/test-only): the
        // pseudo-EOF keeps the historical (1,1) fallback position with no
        // offset information. The end-of-input error converts to the
        // canonical SYNTHETIC shape with the D4 anchor note naming the
        // (1,1) fallback anchor — never a SOURCE range.
        ParseResult noEof = new Parser(List.of(
            new Token(TokenType.LET, "let", 1, 1, 3, 0, 3, List.of())), "f.deal").parse();
        List<CompilerDiagnostic> diags = noEof.diagnostics();
        check(diags.size() == 1,
            "no-EOF list: expected exactly 1 diagnostic, got " + diags.size());
        if (!diags.isEmpty()) {
            CompilerDiagnostic d = diags.get(0);
            check(d.range().origin() == RangeOrigin.SYNTHETIC,
                "no-EOF pseudo-EOF diagnostic must be SYNTHETIC, got "
                    + d.range().origin());
            check(d.line() == 1 && d.column() == 1,
                "no-EOF pseudo-EOF must keep the (1,1) fallback position, got ("
                    + d.line() + "," + d.column() + ")");
            check(d.notes().size() == 1
                    && d.notes().get(0).message()
                        .equals("missing anchor: f.deal:1:1"),
                "no-EOF pseudo-EOF diagnostic must carry the D4 anchor note, got "
                    + d.notes());
        }
        // The same parse still yields the explicit empty-program (0,0) span.
        Span emptySpan = noEof.program().span();
        check(emptySpan.hasScalarOffsets() && emptySpan.startScalarOffset() == 0
                && emptySpan.endScalarOffset() == 0,
            "no-EOF parse: program span offsets (" + emptySpan.startScalarOffset() + ","
                + emptySpan.endScalarOffset() + ") != (0,0)");

        // EOF token present at a non-(1,1) position: end-of-input errors
        // anchor at the real EOF-token position — a zero-length SOURCE
        // range at the EOF token's computed scalar offsets.
        ParseResult withEof = new Parser(List.of(
            new Token(TokenType.LET, "let", 1, 1, 3, 0, 3, List.of()),
            new Token(TokenType.EOF, "", 3, 4, 0, 9, 0, List.of())), "f.deal").parse();
        List<CompilerDiagnostic> eofDiags = withEof.diagnostics();
        check(eofDiags.size() == 1,
            "EOF-terminated list: expected exactly 1 diagnostic, got " + eofDiags.size());
        if (!eofDiags.isEmpty()) {
            CompilerDiagnostic d = eofDiags.get(0);
            check(d.line() == 3 && d.column() == 4,
                "end-of-input error must anchor at the real EOF token position (3,4), got ("
                    + d.line() + "," + d.column() + ")");
            check(d.range().origin() == RangeOrigin.SOURCE,
                "EOF-anchored error range must be SOURCE, got " + d.range().origin());
            check(d.range().startScalarOffset() == 9
                    && d.range().endScalarOffset() == 9
                    && d.range().scalarLength() == 0,
                "EOF-anchored error offsets must be (9,9) with zero length, got ("
                    + d.range().startScalarOffset() + ","
                    + d.range().endScalarOffset() + ")");
        }
    }

    // =========================================================================
    // ISSUE-0224: template-interpolation scalar rebasing
    // =========================================================================

    private static ParseResult parseTemplateSource(String source) {
        LexResult lex = new Lexer(source, "test.deal").tokenize();
        return new Parser(lex.tokens(), "test.deal").parse();
    }

    private static CompilerDiagnostic templateDiag(List<CompilerDiagnostic> diags,
                                                   String code) {
        for (CompilerDiagnostic d : diags) {
            if (d.code().equals(code)) {
                return d;
            }
        }
        return null;
    }

    /** Cursor recomputation cross-check: the scalar at {@code offset}. */
    private static void checkTemplateCursor(String source, int offset,
                                            int expectedLine, int expectedColumn,
                                            String context) {
        ScalarSourceCursor cursor = new ScalarSourceCursor(source);
        for (int i = 0; i < offset; i++) {
            cursor.advance();
        }
        check(cursor.line() == expectedLine && cursor.column() == expectedColumn,
            context + ": cursor at scalar offset " + offset + " is ("
                + cursor.line() + "," + cursor.column() + "), expected ("
                + expectedLine + "," + expectedColumn + ")");
    }

    /**
     * ISSUE-0224 verification 5: every sub-lexed diagnostic inside ${...}
     * rebases through the TemplateScalarMap to exact original scalar
     * offsets; the adjusted sub-parser token list retains a rebased EOF
     * token so end-of-input errors anchor at the expression-end raw
     * position; E1042 pseudo-token and D16 placeholder ranges are
     * raw-positioned. Each expectation is cross-checked against an
     * independent ScalarSourceCursor recomputation of the original source.
     */
    private static void testTemplateInterpolationRebasing() {
        System.out.println("-- Template interpolation scalar rebasing (ISSUE-0224) --");

        // 1. End-of-input E1037 anchors at the closing '}' position with
        //    SOURCE origin and exact scalar offsets — never SYNTHETIC (1,1).
        String src1 = "let x = `${foo +}`;";
        ParseResult r1 = parseTemplateSource(src1);
        CompilerDiagnostic e1037 = templateDiag(r1.diagnostics(), "E1037");
        check(e1037 != null, "T1: E1037 present for `${foo +}`");
        if (e1037 != null) {
            DiagnosticRange r = e1037.range();
            check(r.origin() == RangeOrigin.SOURCE,
                "T1: E1037 origin SOURCE, got " + r.origin());
            check(r.startLine() == 1 && r.startColumn() == 17,
                "T1: E1037 start (1,17), got (" + r.startLine() + "," + r.startColumn() + ")");
            check(r.endLine() == 1 && r.endColumn() == 17,
                "T1: E1037 end (1,17), got (" + r.endLine() + "," + r.endColumn() + ")");
            check(r.startScalarOffset() == 16 && r.endScalarOffset() == 16
                    && r.scalarLength() == 0,
                "T1: E1037 offsets (16,16) len 0, got (" + r.startScalarOffset()
                    + "," + r.endScalarOffset() + ") len " + r.scalarLength());
            check(e1037.notes().isEmpty(),
                "T1: SOURCE anchor carries no notes, got " + e1037.notes());
        }
        checkTemplateCursor(src1, 16, 1, 17, "T1");

        // 2. An end-of-input error inside an unterminated interpolation
        //    anchors at the end of the raw template content (the closing
        //    backtick's position).
        String src2 = "let x = `a${foo +`;";
        ParseResult r2 = parseTemplateSource(src2);
        CompilerDiagnostic e1037b = templateDiag(r2.diagnostics(), "E1037");
        check(e1037b != null, "T2: E1037 present for the unterminated interpolation");
        if (e1037b != null) {
            DiagnosticRange r = e1037b.range();
            check(r.origin() == RangeOrigin.SOURCE
                    && r.startLine() == 1 && r.startColumn() == 18
                    && r.endLine() == 1 && r.endColumn() == 18
                    && r.startScalarOffset() == 17 && r.endScalarOffset() == 17
                    && r.scalarLength() == 0,
                "T2: E1037 at (1,18)-(1,18) offsets (17,17) len 0, got " + r);
        }
        checkTemplateCursor(src2, 17, 1, 18, "T2");

        // 3. A sub-lexer error after a recognized escape rebases through the
        //    scalar map: an escape's decoded scalar maps to the escape's
        //    first raw scalar, so the '@' diagnostic lands on the raw '@'
        //    position, not the decoded-coordinate position.
        String src3 = "let x = `${\\$x + @}`;";
        ParseResult r3 = parseTemplateSource(src3);
        CompilerDiagnostic e1001 = templateDiag(r3.diagnostics(), "E1001");
        check(e1001 != null, "T3: E1001 present for `${\\$x + @}`");
        if (e1001 != null) {
            DiagnosticRange r = e1001.range();
            check(r.origin() == RangeOrigin.SOURCE
                    && r.startLine() == 1 && r.startColumn() == 18
                    && r.endLine() == 1 && r.endColumn() == 19
                    && r.startScalarOffset() == 17 && r.endScalarOffset() == 18
                    && r.scalarLength() == 1,
                "T3: E1001 at (1,18)-(1,19) offsets (17,18) len 1, got " + r);
        }
        checkTemplateCursor(src3, 17, 1, 18, "T3");

        // 4. Astral content before the interpolation: the supplementary
        //    character counts one scalar in column and offset arithmetic.
        String src4 = "let x = `\uD83D\uDE00${foo +}`;";
        ParseResult r4 = parseTemplateSource(src4);
        CompilerDiagnostic e1037c = templateDiag(r4.diagnostics(), "E1037");
        check(e1037c != null, "T4: E1037 present after the astral prefix");
        if (e1037c != null) {
            DiagnosticRange r = e1037c.range();
            check(r.origin() == RangeOrigin.SOURCE
                    && r.startLine() == 1 && r.startColumn() == 18
                    && r.endLine() == 1 && r.endColumn() == 18
                    && r.startScalarOffset() == 17 && r.endScalarOffset() == 17
                    && r.scalarLength() == 0,
                "T4: E1037 at (1,18)-(1,18) offsets (17,17) len 0, got " + r);
        }
        checkTemplateCursor(src4, 17, 1, 18, "T4");

        // 5. A tab before the interpolation counts one scalar.
        String src5 = "let x = `\t${foo +}`;";
        ParseResult r5 = parseTemplateSource(src5);
        CompilerDiagnostic e1037d = templateDiag(r5.diagnostics(), "E1037");
        check(e1037d != null, "T5: E1037 present after the tab prefix");
        if (e1037d != null) {
            DiagnosticRange r = e1037d.range();
            check(r.origin() == RangeOrigin.SOURCE
                    && r.startLine() == 1 && r.startColumn() == 18
                    && r.endLine() == 1 && r.endColumn() == 18
                    && r.startScalarOffset() == 17 && r.endScalarOffset() == 17
                    && r.scalarLength() == 0,
                "T5: E1037 at (1,18)-(1,18) offsets (17,17) len 0, got " + r);
        }
        checkTemplateCursor(src5, 17, 1, 18, "T5");

        // 6. E1042 pseudo-tokens are raw-positioned and scalar-exact: an
        //    invalid escape after astral content marks the escape character's
        //    raw position.
        String src6 = "let x = `hi\uD83D\uDE00\\q`;";
        ParseResult r6 = parseTemplateSource(src6);
        CompilerDiagnostic e1042 = templateDiag(r6.diagnostics(), "E1042");
        check(e1042 != null, "T6: E1042 present for the invalid escape after astral");
        if (e1042 != null) {
            DiagnosticRange r = e1042.range();
            check(r.origin() == RangeOrigin.SOURCE
                    && r.startLine() == 1 && r.startColumn() == 14
                    && r.endLine() == 1 && r.endColumn() == 15
                    && r.startScalarOffset() == 13 && r.endScalarOffset() == 14
                    && r.scalarLength() == 1,
                "T6: E1042 at (1,14)-(1,15) offsets (13,14) len 1, got " + r);
        }
        checkTemplateCursor(src6, 13, 1, 14, "T6");

        // 7. Empty expression: E1042 and the D16 placeholder carry zero
        //    scalar length at the expression-start raw position.
        String src7 = "let x = `${}`;";
        ParseResult r7 = parseTemplateSource(src7);
        CompilerDiagnostic e1042b = templateDiag(r7.diagnostics(), "E1042");
        check(e1042b != null, "T7: E1042 present for the empty interpolation");
        if (e1042b != null) {
            DiagnosticRange r = e1042b.range();
            check(r.origin() == RangeOrigin.SOURCE
                    && r.startLine() == 1 && r.startColumn() == 12
                    && r.endLine() == 1 && r.endColumn() == 12
                    && r.startScalarOffset() == 11 && r.endScalarOffset() == 11
                    && r.scalarLength() == 0,
                "T7: E1042 at (1,12)-(1,12) offsets (11,11) len 0, got " + r);
        }
        if (!r7.program().statements().isEmpty()
                && r7.program().statements().get(0) instanceof VariableDeclaration vd7
                && vd7.initializer() instanceof TemplateLiteralExpr tl7
                && tl7.parts().size() == 3) {
            Span placeholder = tl7.parts().get(1).span();
            check(placeholder.startLine() == 1 && placeholder.startColumn() == 12
                    && placeholder.endLine() == 1 && placeholder.endColumn() == 12,
                "T7: D16 placeholder span (1,12)-(1,12), got " + placeholder);
            check(placeholder.startScalarOffset() == 11
                    && placeholder.endScalarOffset() == 11,
                "T7: D16 placeholder offsets (11,11), got ("
                    + placeholder.startScalarOffset() + ","
                    + placeholder.endScalarOffset() + ")");
        } else {
            fail("T7: expected a 3-part template literal with a placeholder");
        }

        // 8. Formatted and structured surfaces of a rebased diagnostic agree
        //    (formatted-vs-structured cross-check on the E1037 fixture).
        if (e1037 != null) {
            String formatted = DiagnosticFormatter.format(e1037);
            check(formatted.contains("test.deal:1:17-1:17")
                    && formatted.contains("ERROR E1037")
                    && formatted.contains("span 0"),
                "T8: formatted E1037 must name (1,17)-(1,17) with span 0, got: " + formatted);
            String json = DiagnosticStructuredOutput.toJson(List.of(e1037));
            check(json.contains("\"startLine\": 1")
                    && json.contains("\"startColumn\": 17")
                    && json.contains("\"startScalarOffset\": 16")
                    && json.contains("\"endScalarOffset\": 16")
                    && json.contains("\"scalarLength\": 0")
                    && json.contains("\"origin\": \"SOURCE\""),
                "T8: structured E1037 must carry the exact range fields, got: " + json);
        }
    }

    // =========================================================================
    // ISSUE-0225 checker/validator producer migration (verification 6)
    // =========================================================================

    /** Full frontend checker pipeline: name resolution + type checking. */
    private static List<CompilerDiagnostic> checkerDiagnostics(String source,
                                                                String filename) {
        LexResult lex = new Lexer(source, filename).tokenize();
        ParseResult parse = new Parser(lex.tokens(), filename).parse();
        StubModuleResolver resolver = new StubModuleResolver();
        NameResolver nr = new NameResolver(filename, resolver);
        SymbolTable symTable = nr.resolve(parse.program());
        List<CompilerDiagnostic> diags = new ArrayList<>(nr.diagnostics());
        CheckResult result = TypeChecker.check(filename, symTable, nr,
            parse.program());
        diags.addAll(result.diagnostics());
        return diags;
    }

    /** Finds a diagnostic with the given code. */
    private static CompilerDiagnostic checkerDiag(List<CompilerDiagnostic> diags,
                                                   String code) {
        for (CompilerDiagnostic d : diags) {
            if (d.code().equals(code)) {
                return d;
            }
        }
        return null;
    }

    /**
     * Independent recomputation cross-check: the cursor advanced to the
     * UTF-16 index of {@code needle} (a code-point boundary) and to the
     * position right after the needle. The diagnostic range must match the
     * cursor's scalar line/column/offset arithmetic exactly.
     */
    private static ScalarSourceCursor cursorAt(String source, int utf16Index) {
        ScalarSourceCursor cursor = new ScalarSourceCursor(source);
        int i = 0;
        while (i < utf16Index) {
            int codePoint = source.codePointAt(i);
            cursor.advance();
            i += Character.charCount(codePoint);
        }
        return cursor;
    }

    /** Asserts the diagnostic range equals the needle's recomputed range. */
    private static void checkNeedleRange(CompilerDiagnostic d, String source,
                                         String filename, String needle,
                                         String context) {
        if (d == null) {
            check(false, context + ": diagnostic missing");
            return;
        }
        int startIndex = source.indexOf(needle);
        int endIndex = startIndex + needle.length();
        ScalarSourceCursor cs = cursorAt(source, startIndex);
        ScalarSourceCursor ce = cursorAt(source, endIndex);
        DiagnosticRange r = d.range();
        check(r != null, context + ": range present");
        check(r.origin() == RangeOrigin.SOURCE
                && filename.equals(r.file())
                && r.startLine() == cs.line()
                && r.startColumn() == cs.column()
                && r.endLine() == ce.line()
                && r.endColumn() == ce.column()
                && r.startScalarOffset() == cs.scalarOffset()
                && r.endScalarOffset() == ce.scalarOffset()
                && r.scalarLength() == ce.scalarOffset() - cs.scalarOffset(),
            context + ": range " + r + " vs cursor start (" + cs.line() + ","
                + cs.column() + ",@" + cs.scalarOffset() + ") end (" + ce.line()
                + "," + ce.column() + ",@" + ce.scalarOffset() + ")");
    }

    /**
     * ISSUE-0225 verification 6: name-resolution and type errors carry
     * SOURCE ranges that are scalar-exact after astral characters and over
     * multi-line spans (cross-checked against an independent
     * {@link ScalarSourceCursor} recomputation), the recorded-span E4008
     * cycle anchors at the class declaration span, and an E4008 cycle
     * without a recorded span yields the canonical synthetic range plus a
     * note naming the cycle-node class.
     */
    private static void testCheckerProducerRanges() throws Exception {
        System.out.println("-- Checker/validator producer ranges (ISSUE-0225) --");

        // 1. Name-resolution error after an astral character: E2000
        //    'break' outside a loop, anchored at the break keyword on the
        //    line after the astral variable name. The astral counts one
        //    scalar, so the break token starts at scalar offset 17 — a
        //    UTF-16 column would place it one unit later.
        String src1 = "let \uD83D\uDE00s: int = 1;\nbreak;\n";
        List<CompilerDiagnostic> d1 = checkerDiagnostics(src1, "nr.deal");
        CompilerDiagnostic e2000 = checkerDiag(d1, "E2000");
        check(e2000 != null, "C1: E2000 present, got " + d1);
        checkNeedleRange(e2000, src1, "nr.deal", "break", "C1");

        // 2. Type error after an astral character on the same line: E2001
        //    'Undeclared identifier' at the identifier span. The astral in
        //    the string literal counts one scalar, so 'missing' starts at
        //    (1,35) with scalar offset 34 — a UTF-16 column would report
        //    36.
        String src2 =
            "let s: string = \"\uD83D\uDE00\"; let y: int = missing;\n";
        List<CompilerDiagnostic> d2 = checkerDiagnostics(src2, "tc.deal");
        CompilerDiagnostic e2001 = checkerDiag(d2, "E2001");
        check(e2001 != null, "C2: E2001 present, got " + d2);
        checkNeedleRange(e2001, src2, "tc.deal", "missing", "C2");

        // 3. Multi-line span: E3010 on a boolean '+' expression whose
        //    binary-expression span covers both lines (start of 'true'
        //    through the end of 'false').
        String src3 = "let x: boolean = true +\n    false;\n";
        List<CompilerDiagnostic> d3 = checkerDiagnostics(src3, "ml.deal");
        CompilerDiagnostic e3010 = checkerDiag(d3, "E3010");
        check(e3010 != null, "C3: E3010 present, got " + d3);
        if (e3010 != null) {
            int start = src3.indexOf("true");
            int end = src3.indexOf("false") + "false".length();
            ScalarSourceCursor cs = cursorAt(src3, start);
            ScalarSourceCursor ce = cursorAt(src3, end);
            DiagnosticRange r = e3010.range();
            check(r.origin() == RangeOrigin.SOURCE
                    && r.startLine() == 1 && r.startColumn() == cs.column()
                    && r.endLine() == 2 && r.endColumn() == ce.column()
                    && r.startScalarOffset() == cs.scalarOffset()
                    && r.endScalarOffset() == ce.scalarOffset()
                    && r.scalarLength() == ce.scalarOffset() - cs.scalarOffset(),
                "C3: multi-line span " + r + " vs cursor start (1,"
                    + cs.column() + ",@" + cs.scalarOffset() + ") end (2,"
                    + ce.column() + ",@" + ce.scalarOffset() + ")");
        }

        // 4. Recorded-span E4008: the A <-> B cycle anchors at the class
        //    declaration span of the cycle-closing node (B).
        String src4 = "// @jsonable\n"
            + "export class A { b: B; }\n"
            + "// @jsonable\n"
            + "export class B { a: A; }\n";
        List<CompilerDiagnostic> d4 = checkerDiagnostics(src4, "cyc.deal");
        CompilerDiagnostic e4008 = checkerDiag(d4, "E4008");
        check(e4008 != null, "C4: E4008 present, got " + d4);
        if (e4008 != null) {
            String classB = "class B { a: A; }";
            int start = src4.indexOf(classB);
            int end = start + classB.length();
            ScalarSourceCursor cs = cursorAt(src4, start);
            ScalarSourceCursor ce = cursorAt(src4, end);
            DiagnosticRange r = e4008.range();
            check(r.origin() == RangeOrigin.SOURCE
                    && "cyc.deal".equals(r.file())
                    && r.startLine() == cs.line()
                    && r.startColumn() == cs.column()
                    && r.endLine() == ce.line()
                    && r.endColumn() == ce.column()
                    && r.startScalarOffset() == cs.scalarOffset()
                    && r.endScalarOffset() == ce.scalarOffset()
                    && r.scalarLength() == ce.scalarOffset() - cs.scalarOffset(),
                "C4: E4008 class-declaration span " + r + " vs cursor ("
                    + cs.line() + "," + cs.column() + ",@" + cs.scalarOffset()
                    + ")-(" + ce.line() + "," + ce.column() + ",@"
                    + ce.scalarOffset() + ")");
        }

        // 5. E4008 cycle without a recorded span (defensive fallback):
        //    the canonical synthetic range plus an anchor note naming the
        //    cycle-node class. Production records the span for every
        //    walked @jsonable class declaration before its dependencies,
        //    so the test drives the detector directly with the span map
        //    empty.
        String src5 = "// @jsonable\nclass A { a: A; }\n";
        LexResult lex5 = new Lexer(src5, "fallback.deal").tokenize();
        ParseResult parse5 = new Parser(lex5.tokens(), "fallback.deal").parse();
        StubModuleResolver resolver5 = new StubModuleResolver();
        NameResolver nr5 = new NameResolver("fallback.deal", resolver5);
        SymbolTable sym5 = nr5.resolve(parse5.program());

        Constructor<TypeChecker> ctor = TypeChecker.class
            .getDeclaredConstructor(String.class, SymbolTable.class,
                NameResolver.class, Map.class);
        ctor.setAccessible(true);
        TypeChecker checker = ctor.newInstance("fallback.deal", sym5, nr5,
            nr5.scopeMap());

        Field depsField = TypeChecker.class.getDeclaredField("jsonableClassDeps");
        depsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Set<String>> deps =
            (Map<String, Set<String>>) depsField.get(checker);
        deps.put("A", Set.of("A"));

        Method detect = TypeChecker.class.getDeclaredMethod("detectJsonableCycles");
        detect.setAccessible(true);
        detect.invoke(checker);

        Field diagsField = TypeChecker.class.getDeclaredField("diagnostics");
        diagsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<CompilerDiagnostic> fallbackDiags =
            (List<CompilerDiagnostic>) diagsField.get(checker);

        check(fallbackDiags.size() == 1,
            "C5: fallback emits exactly one diagnostic, got " + fallbackDiags);
        if (fallbackDiags.size() == 1) {
            CompilerDiagnostic fb = fallbackDiags.get(0);
            check("E4008".equals(fb.code()) && "error".equals(fb.severity())
                    && "Circular @jsonable class dependency: A \u2192 A"
                        .equals(fb.message()),
                "C5: E4008 error with the cycle message, got " + fb);
            DiagnosticRange r = fb.range();
            check(r.origin() == RangeOrigin.SYNTHETIC
                    && "fallback.deal".equals(r.file())
                    && r.startLine() == 1 && r.startColumn() == 1
                    && r.endLine() == 1 && r.endColumn() == 1
                    && r.startScalarOffset() == 0 && r.endScalarOffset() == 0
                    && r.scalarLength() == 0,
                "C5: canonical synthetic (file,1,1,1,1,0,0,0,SYNTHETIC), got " + r);
            boolean noteNamesClass = fb.notes().stream().anyMatch(n ->
                n.range() == null && ("missing anchor: class declaration span"
                    + " for cycle node 'A'").equals(n.message()));
            check(noteNamesClass,
                "C5: anchor note names the cycle-node class, got " + fb.notes());
        }
    }

    // =========================================================================
    // ISSUE-0218 carrier section (verification 1 minus formatter/JSON)
    // =========================================================================

    private static void testCarrierRecords() {
        System.out.println("-- Carrier records: DiagnosticRange, RangeOrigin, DiagnosticNote --");

        // DiagnosticRange holds every component verbatim.
        DiagnosticRange r = new DiagnosticRange("f.deal", 2, 3, 2, 8, 4, 9, 5,
            RangeOrigin.SOURCE);
        check(r.file().equals("f.deal"), "DiagnosticRange file");
        check(r.startLine() == 2 && r.startColumn() == 3,
            "DiagnosticRange start position (2,3), got (" + r.startLine() + "," + r.startColumn() + ")");
        check(r.endLine() == 2 && r.endColumn() == 8,
            "DiagnosticRange end position (2,8), got (" + r.endLine() + "," + r.endColumn() + ")");
        check(r.startScalarOffset() == 4 && r.endScalarOffset() == 9,
            "DiagnosticRange offsets (4,9), got (" + r.startScalarOffset() + "," + r.endScalarOffset() + ")");
        check(r.scalarLength() == 5, "DiagnosticRange scalarLength 5");
        check(r.origin() == RangeOrigin.SOURCE, "DiagnosticRange origin");
        check(r.scalarLength() == r.endScalarOffset() - r.startScalarOffset(),
            "scalarLength == endScalarOffset - startScalarOffset invariant");
        check(r.hasScalarOffsets(), "well-formed range hasScalarOffsets()");
        check(!r.isCanonicalSynthetic(), "SOURCE range is not canonical synthetic");

        // Plain immutable record: performs no validation and never throws —
        // nulls, non-positive positions, and negative offsets construct
        // verbatim (D9 owns normalization).
        DiagnosticRange raw1 = new DiagnosticRange(null, 0, 0, 0, 0, -1, -1, -1, null);
        check(raw1.file() == null && raw1.origin() == null,
            "defective range constructs verbatim (no validation)");
        DiagnosticRange raw2 = new DiagnosticRange("f", 2, 5, 1, 1, 7, 5, -2, RangeOrigin.SOURCE);
        check(raw2.scalarLength() == -2 && raw2.endScalarOffset() == 5,
            "inverted-offset range constructs verbatim (no validation)");

        // DiagnosticRange.synthetic(file): the canonical shape; a null file
        // becomes the empty string; never throws.
        DiagnosticRange syn = DiagnosticRange.synthetic("mod.deal");
        check(syn.file().equals("mod.deal"), "synthetic file");
        check(syn.startLine() == 1 && syn.startColumn() == 1
                && syn.endLine() == 1 && syn.endColumn() == 1,
            "synthetic positions must be (1,1)-(1,1)");
        check(syn.startScalarOffset() == 0 && syn.endScalarOffset() == 0
                && syn.scalarLength() == 0,
            "synthetic offsets and scalarLength must all be 0");
        check(syn.origin() == RangeOrigin.SYNTHETIC, "synthetic origin");
        check(syn.isCanonicalSynthetic(), "synthetic must be canonical synthetic");
        check(syn.hasScalarOffsets(), "synthetic carries known (0,0) offsets");
        check(DiagnosticRange.synthetic(null).file().equals(""),
            "synthetic(null) file must be the empty string");

        // RangeOrigin values.
        check(RangeOrigin.valueOf("SOURCE") == RangeOrigin.SOURCE
                && RangeOrigin.valueOf("SYNTHETIC") == RangeOrigin.SYNTHETIC,
            "RangeOrigin values");

        // DiagnosticNote: message required, secondary range nullable.
        DiagnosticNote msgOnly = new DiagnosticNote("missing anchor: x", null);
        check(msgOnly.message().equals("missing anchor: x") && msgOnly.range() == null,
            "message-only note");
        DiagnosticNote withRange = new DiagnosticNote("hint", r);
        check(withRange.range() == r, "secondary-range note keeps its range");
        boolean threw = false;
        try {
            new DiagnosticNote(null, null);
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check(threw, "DiagnosticNote null message must throw IAE");
    }

    private static void testRangeConversions() {
        System.out.println("-- D4 conversions: Span.range() and Token.range() --");

        // Span with known offsets, non-empty: the inclusive AST end column
        // translates to the half-open range end +1 exactly once; offsets are
        // half-open; scalar length = end - start.
        Span s = new Span("f.deal", 2, 3, 2, 7, 4, 9);
        DiagnosticRange sr = s.range();
        check(sr.origin() == RangeOrigin.SOURCE,
            "known span range origin must be SOURCE, got " + sr.origin());
        check(sr.file().equals("f.deal"), "span range file");
        check(sr.startLine() == 2 && sr.startColumn() == 3, "span range start (2,3)");
        check(sr.endLine() == 2 && sr.endColumn() == 8,
            "inclusive end column 7 must translate to half-open end column 8, got "
                + sr.endColumn());
        check(sr.startScalarOffset() == 4 && sr.endScalarOffset() == 9,
            "span range offsets (4,9)");
        check(sr.scalarLength() == 5, "span range scalar length 9-4=5");

        // Zero-length span (equal start/end positions and equal offsets):
        // end = start, no +1 translation.
        Span zero = new Span("f.deal", 1, 1, 1, 1, 0, 0);
        DiagnosticRange zr = zero.range();
        check(zr.origin() == RangeOrigin.SOURCE,
            "zero-length span range origin must be SOURCE");
        check(zr.startLine() == 1 && zr.startColumn() == 1
                && zr.endLine() == 1 && zr.endColumn() == 1,
            "zero-length span range must stay (1,1)-(1,1) with no +1 translation");
        check(zr.startScalarOffset() == 0 && zr.endScalarOffset() == 0
                && zr.scalarLength() == 0,
            "zero-length span range offsets/length must be (0,0,0)");

        // Multi-line span: end line unchanged, end column +1.
        Span multi = new Span("f.deal", 1, 5, 3, 2, 0, 17);
        DiagnosticRange mr = multi.range();
        check(mr.endLine() == 3 && mr.endColumn() == 3,
            "multi-line span end column +1: (3,2) -> (3,3)");
        check(mr.scalarLength() == 17, "multi-line span scalar length");

        // UNKNOWN offsets -> canonical SYNTHETIC, never SOURCE (origin pin).
        Span unknown = new Span("f.deal", 2, 3, 2, 7);
        DiagnosticRange ur = unknown.range();
        check(ur.origin() == RangeOrigin.SYNTHETIC,
            "UNKNOWN-offset span must convert to SYNTHETIC, got " + ur.origin());
        check(ur.isCanonicalSynthetic(),
            "UNKNOWN-offset span range must be canonical synthetic: " + ur);
        check(ur.file().equals("f.deal"),
            "UNKNOWN-offset span synthetic range keeps the span file");

        // Span.synthetic converts to SYNTHETIC as well (absolute rule).
        DiagnosticRange synr = Span.synthetic("f.deal").range();
        check(synr.origin() == RangeOrigin.SYNTHETIC,
            "Span.synthetic must convert to SYNTHETIC, got " + synr.origin());
        check(synr.isCanonicalSynthetic(),
            "Span.synthetic range must be canonical synthetic");

        // Mixed known/UNKNOWN components -> SYNTHETIC.
        Span mixed = new Span("f.deal", 1, 1, 1, 3, 0, Span.UNKNOWN_OFFSET);
        check(!mixed.hasScalarOffsets(), "mixed span hasScalarOffsets() must be false");
        check(mixed.range().origin() == RangeOrigin.SYNTHETIC,
            "mixed-offset span must convert to SYNTHETIC");

        // Token with known offsets: end column = column + scalarLength;
        // half-open offsets. 'a' + U+1F600 + 'b' is 3 scalars.
        Token t = new Token(TokenType.IDENTIFIER, "a\uD83D\uDE00b", 4, 5, 4, 7, 3, List.of());
        DiagnosticRange tr = t.range("f.deal");
        check(tr.origin() == RangeOrigin.SOURCE,
            "known token range origin must be SOURCE, got " + tr.origin());
        check(tr.file().equals("f.deal"), "token range file");
        check(tr.startLine() == 4 && tr.startColumn() == 5, "token range start (4,5)");
        check(tr.endLine() == 4 && tr.endColumn() == 8,
            "token range end column must be column + scalarLength = 5 + 3, got "
                + tr.endColumn());
        check(tr.startScalarOffset() == 7 && tr.endScalarOffset() == 10
                && tr.scalarLength() == 3,
            "token range offsets/length must be (7,10,3)");

        // Zero-scalar-length EOF token: zero-length range at its position.
        Token eof = new Token(TokenType.EOF, "", 3, 4, 0, 9, 0, List.of());
        DiagnosticRange er = eof.range("f.deal");
        check(er.origin() == RangeOrigin.SOURCE, "EOF token range origin must be SOURCE");
        check(er.startLine() == 3 && er.startColumn() == 4
                && er.endLine() == 3 && er.endColumn() == 4,
            "zero-length EOF token range must stay at (3,4)-(3,4)");
        check(er.startScalarOffset() == 9 && er.endScalarOffset() == 9
                && er.scalarLength() == 0,
            "EOF token range offsets/length must be (9,9,0)");

        // UNKNOWN-offset token -> SYNTHETIC (origin pin); file defaults to
        // the empty string for the no-file overload.
        Token ut = new Token(TokenType.IDENTIFIER, "x", 2, 3, 1);
        DiagnosticRange utr = ut.range();
        check(utr.origin() == RangeOrigin.SYNTHETIC,
            "UNKNOWN-offset token must convert to SYNTHETIC, got " + utr.origin());
        check(utr.isCanonicalSynthetic(),
            "UNKNOWN-offset token range must be canonical synthetic");
        check(utr.file().equals(""), "token range() defaults to the empty file");
        check(ut.range("g.deal").file().equals("g.deal"),
            "token range(String file) must use the given file");
        check(ut.range(null).file().equals(""), "token range(null) must use the empty file");
    }

    private static void testSourceImpliesKnownOffsets() {
        System.out.println("-- SOURCE-implies-known-offsets invariant --");

        // Every SOURCE range produced by the D4 conversions carries computed
        // known offsets and a consistent scalar length.
        DiagnosticRange[] sources = {
            new Span("f.deal", 2, 3, 2, 7, 4, 9).range(),
            new Span("f.deal", 1, 1, 1, 1, 0, 0).range(),
            new Span("f.deal", 1, 5, 3, 2, 0, 17).range(),
            new Token(TokenType.IDENTIFIER, "abc", 1, 2, 3, 6, 3, List.of()).range("f.deal"),
            new Token(TokenType.EOF, "", 3, 4, 0, 9, 0, List.of()).range("f.deal"),
        };
        for (DiagnosticRange r : sources) {
            check(r.origin() == RangeOrigin.SOURCE, "range must be SOURCE: " + r);
            check(r.hasScalarOffsets(),
                "SOURCE range must carry known non-negative offsets: " + r);
            check(r.scalarLength() == r.endScalarOffset() - r.startScalarOffset(),
                "SOURCE range must satisfy scalarLength == end - start: " + r);
        }

        // An anchor without computed offsets can never yield SOURCE:
        // UNKNOWN spans/tokens and synthetic ranges are SYNTHETIC.
        DiagnosticRange[] synthetic = {
            new Span("f.deal", 2, 3, 2, 7).range(),
            Span.synthetic("f.deal").range(),
            new Span("f.deal", 1, 1, 1, 3, 0, Span.UNKNOWN_OFFSET).range(),
            new Token(TokenType.IDENTIFIER, "x", 2, 3, 1).range(),
            DiagnosticRange.synthetic("f.deal"),
        };
        for (DiagnosticRange r : synthetic) {
            check(r.origin() == RangeOrigin.SYNTHETIC,
                "offset-less anchor must yield SYNTHETIC, got " + r.origin() + " for " + r);
            check(r.isCanonicalSynthetic(),
                "offset-less anchor must yield the canonical synthetic shape: " + r);
        }
    }

    private static void testSyntheticContract() {
        System.out.println("-- D6 synthetic contract: canonical shape + anchor note --");

        // syntheticError: canonical synthetic shape plus the provided
        // construct-naming anchor note.
        CompilerDiagnostic e = CompilerDiagnostic.syntheticError(DiagnosticCode.E4008,
            "circular jsonable dependency", "mod.deal",
            "missing anchor: class declaration span for cycle node 'A'");
        check(e.code().equals("E4008"), "syntheticError code");
        check(e.severity().equals("error"), "syntheticError severity");
        check(e.message().equals("circular jsonable dependency"), "syntheticError message");
        check(e.diagnosticCode() == DiagnosticCode.E4008, "syntheticError diagnosticCode");
        check(e.range().isCanonicalSynthetic(),
            "syntheticError range must be canonical synthetic");
        check(e.range().file().equals("mod.deal"), "syntheticError range file");
        check(e.range().origin() == RangeOrigin.SYNTHETIC, "syntheticError origin");
        check(e.file().equals("mod.deal") && e.line() == 1 && e.column() == 1,
            "syntheticError accessors derive from the range start");
        check(e.notes().size() == 1
                && e.notes().get(0).message().equals(
                    "missing anchor: class declaration span for cycle node 'A'")
                && e.notes().get(0).range() == null,
            "syntheticError must carry the provided construct-naming anchor note, got "
                + e.notes());

        // syntheticWarning: same contract, warning severity, empty file kept.
        CompilerDiagnostic w = CompilerDiagnostic.syntheticWarning(DiagnosticCode.E2005,
            "circular import", "",
            "missing anchor: import declaration closing the module cycle a -> b -> a");
        check(w.severity().equals("warning"), "syntheticWarning severity");
        check(w.range().file().equals(""), "syntheticWarning empty file stays empty");
        check(w.range().isCanonicalSynthetic(), "syntheticWarning canonical shape");
        check(w.notes().size() == 1
                && w.notes().get(0).message().contains("missing anchor"),
            "syntheticWarning anchor note");

        // The span factory appends the mandatory D4 anchor note when the
        // conversion yields SYNTHETIC.
        CompilerDiagnostic spanErr = CompilerDiagnostic.error(DiagnosticCode.E3001,
            "type mismatch", new Span("src.mod", 2, 3, 2, 8));
        check(spanErr.range().origin() == RangeOrigin.SYNTHETIC,
            "UNKNOWN span factory range must be SYNTHETIC");
        check(spanErr.notes().size() == 1
                && spanErr.notes().get(0).message().equals("missing anchor: src.mod:2:3"),
            "span factory must append the mandatory anchor note, got " + spanErr.notes());

        // The token factory appends the mandatory D4 anchor note as well
        // (tokens carry no file, so the note uses the empty file).
        CompilerDiagnostic tokWarn = CompilerDiagnostic.warning(DiagnosticCode.E1043,
            "invalid placement", new Token(TokenType.EXPORT, "export", 2, 1, 6));
        check(tokWarn.range().origin() == RangeOrigin.SYNTHETIC,
            "UNKNOWN token factory range must be SYNTHETIC");
        check(tokWarn.notes().size() == 1
                && tokWarn.notes().get(0).message().equals("missing anchor: :2:1"),
            "token factory must append the mandatory anchor note, got " + tokWarn.notes());

        // Known-offset anchors through the same factories: SOURCE range, no
        // anchor note.
        CompilerDiagnostic knownSpan = CompilerDiagnostic.error(DiagnosticCode.E3001,
            "type mismatch", new Span("src.mod", 2, 3, 2, 7, 4, 9));
        check(knownSpan.range().origin() == RangeOrigin.SOURCE,
            "known span factory range must be SOURCE");
        check(knownSpan.notes().isEmpty(),
            "known span factory must not append an anchor note");
        CompilerDiagnostic knownTok = CompilerDiagnostic.warning(DiagnosticCode.E1043,
            "invalid placement", new Token(TokenType.EXPORT, "export", 2, 1, 6, 11, 6, List.of()));
        check(knownTok.range().origin() == RangeOrigin.SOURCE,
            "known token factory range must be SOURCE");
        check(knownTok.range().file().equals(""),
            "known token factory range uses the empty file (tokens carry no file)");
        check(knownTok.notes().isEmpty(),
            "known token factory must not append an anchor note");

        // A null or empty anchor note is a programmer error.
        boolean threw = false;
        try {
            CompilerDiagnostic.syntheticError(DiagnosticCode.E6000, "m", "f", null);
        } catch (IllegalArgumentException ex) {
            threw = true;
        }
        check(threw, "syntheticError with a null anchor note must throw IAE");
        threw = false;
        try {
            CompilerDiagnostic.syntheticError(DiagnosticCode.E6000, "m", "f", "");
        } catch (IllegalArgumentException ex) {
            threw = true;
        }
        check(threw, "syntheticError with an empty anchor note must throw IAE");
    }

    private static void testD9Normalization() {
        System.out.println("-- D9 normalization: defects become the originating diagnostic --");

        // 1. UNKNOWN (negative) offsets with SOURCE origin: normalized to the
        // canonical SYNTHETIC shape — never clamped in place as a SOURCE
        // (1,1,1,1,0,0,0) range (origin pin).
        CompilerDiagnostic d1 = new CompilerDiagnostic("E3001", "error", "unknown offsets",
            new DiagnosticRange("f.deal", 1, 1, 1, 5, Span.UNKNOWN_OFFSET, 4, -1,
                RangeOrigin.SOURCE),
            null, DiagnosticCode.E3001);
        check(d1.code().equals("E3001") && d1.message().equals("unknown offsets")
                && d1.severity().equals("error"),
            "UNKNOWN-offset normalization keeps code/severity/message");
        check(d1.diagnosticCode() == DiagnosticCode.E3001,
            "UNKNOWN-offset normalization keeps diagnosticCode");
        check(d1.range().origin() == RangeOrigin.SYNTHETIC,
            "UNKNOWN-offset SOURCE range must normalize to SYNTHETIC origin, got "
                + d1.range().origin());
        check(d1.range().isCanonicalSynthetic(),
            "UNKNOWN-offset range must normalize to the canonical synthetic shape: " + d1.range());
        check(d1.range().file().equals("f.deal"),
            "UNKNOWN-offset synthetic keeps the original file");
        check(d1.notes().size() == 1
                && d1.notes().get(0).message().equals(
                    "internal range defect: unknown or negative scalar offsets")
                && d1.notes().get(0).range() == null,
            "UNKNOWN-offset normalization appends the defect note, got " + d1.notes());

        // 2. Negative end offset: same SYNTHETIC normalization.
        CompilerDiagnostic d2 = new CompilerDiagnostic("E3001", "warning", "negative end",
            new DiagnosticRange("f.deal", 2, 3, 2, 5, 5, -2, -1, RangeOrigin.SOURCE),
            null, DiagnosticCode.E3001);
        check(d2.range().origin() == RangeOrigin.SYNTHETIC
                && d2.range().isCanonicalSynthetic(),
            "negative-offset range must normalize to canonical SYNTHETIC");
        check(d2.severity().equals("warning"),
            "severity kept through offset normalization");
        check(d2.notes().size() == 1
                && d2.notes().get(0).message().contains("internal range defect"),
            "negative-offset normalization appends one defect note");

        // 3. Inverted offsets (end < start, both non-negative): SYNTHETIC.
        CompilerDiagnostic d3 = new CompilerDiagnostic("E3001", "error", "inverted offsets",
            new DiagnosticRange("f.deal", 1, 1, 1, 5, 9, 4, 5, RangeOrigin.SOURCE),
            null, DiagnosticCode.E3001);
        check(d3.range().origin() == RangeOrigin.SYNTHETIC
                && d3.range().isCanonicalSynthetic(),
            "inverted-offset range must normalize to canonical SYNTHETIC, got " + d3.range());
        check(d3.notes().size() == 1
                && d3.notes().get(0).message().equals(
                    "internal range defect: inverted scalar offsets"),
            "inverted-offset normalization appends the defect note, got " + d3.notes());

        // 4. Null range: canonical synthetic with the empty file.
        CompilerDiagnostic d4 = new CompilerDiagnostic("E1001", "error", "null range",
            null, null, DiagnosticCode.E1001);
        check(d4.range() != null && d4.range().isCanonicalSynthetic()
                && d4.range().file().equals(""),
            "null range must fall back to canonical synthetic with the empty file");
        check(d4.notes().size() == 1
                && d4.notes().get(0).message().equals("internal range defect: null range"),
            "null range appends the defect note");

        // 5. Null file: canonical synthetic with the empty file.
        CompilerDiagnostic d5 = new CompilerDiagnostic("E1001", "error", "null file",
            new DiagnosticRange(null, 2, 3, 2, 5, 4, 6, 2, RangeOrigin.SOURCE),
            null, DiagnosticCode.E1001);
        check(d5.range().isCanonicalSynthetic() && d5.range().file().equals(""),
            "null file must fall back to canonical synthetic with the empty file");
        check(d5.notes().size() == 1
                && d5.notes().get(0).message().equals("internal range defect: null range file"),
            "null file appends the defect note");

        // 6. Valid offsets with inverted line/column pairs: collapse the end
        // onto the start — a zero-length SOURCE range at the computed start.
        CompilerDiagnostic d6 = new CompilerDiagnostic("E3001", "error", "inverted positions",
            new DiagnosticRange("f.deal", 2, 5, 1, 1, 5, 7, 2, RangeOrigin.SOURCE),
            null, DiagnosticCode.E3001);
        check(d6.range().origin() == RangeOrigin.SOURCE,
            "valid-offset position inversion must collapse to SOURCE, got "
                + d6.range().origin());
        check(d6.range().file().equals("f.deal"), "collapsed range keeps the file");
        check(d6.range().startLine() == 2 && d6.range().startColumn() == 5
                && d6.range().endLine() == 2 && d6.range().endColumn() == 5,
            "inverted positions must collapse the end onto the start (2,5)-(2,5), got "
                + d6.range());
        check(d6.range().startScalarOffset() == 5 && d6.range().endScalarOffset() == 5
                && d6.range().scalarLength() == 0,
            "collapsed range must be zero-length at the computed start offset 5, got "
                + d6.range());
        check(d6.notes().size() == 1
                && d6.notes().get(0).message().equals(
                    "internal range defect: invalid line or column positions"),
            "position inversion appends exactly the position defect note, got " + d6.notes());

        // 7. Non-positive start with valid offsets: clamps to (1,1), keeps
        // the computed start offset as a zero-length range.
        CompilerDiagnostic d7 = new CompilerDiagnostic("E3001", "error", "non-positive start",
            new DiagnosticRange("f.deal", 0, 0, 0, 0, 5, 7, 2, RangeOrigin.SOURCE),
            null, DiagnosticCode.E3001);
        check(d7.range().origin() == RangeOrigin.SOURCE,
            "non-positive start collapse keeps SOURCE origin");
        check(d7.range().startLine() == 1 && d7.range().startColumn() == 1
                && d7.range().endLine() == 1 && d7.range().endColumn() == 1,
            "non-positive start must clamp to (1,1)-(1,1), got " + d7.range());
        check(d7.range().startScalarOffset() == 5 && d7.range().endScalarOffset() == 5
                && d7.range().scalarLength() == 0,
            "non-positive start must keep its computed start offset 5 as a zero-length range");

        // 8. scalarLength mismatch: recomputed from the offsets.
        CompilerDiagnostic d8 = new CompilerDiagnostic("E3001", "error", "length mismatch",
            new DiagnosticRange("f.deal", 1, 1, 1, 5, 0, 4, 9, RangeOrigin.SOURCE),
            null, DiagnosticCode.E3001);
        check(d8.range().origin() == RangeOrigin.SOURCE,
            "scalarLength mismatch keeps SOURCE origin");
        check(d8.range().scalarLength() == 4,
            "scalarLength must recompute to end - start = 4, got " + d8.range().scalarLength());
        check(d8.range().startScalarOffset() == 0 && d8.range().endScalarOffset() == 4,
            "scalarLength mismatch keeps the offsets");
        check(d8.notes().size() == 1
                && d8.notes().get(0).message().equals(
                    "internal range defect: scalar length mismatch"),
            "scalarLength mismatch appends the defect note, got " + d8.notes());

        // 9. Unknown severity: normalizes to "error" with a defect note.
        CompilerDiagnostic d9 = new CompilerDiagnostic("E3001", "fatal", "unknown severity",
            new DiagnosticRange("f.deal", 1, 1, 1, 3, 0, 2, 2, RangeOrigin.SOURCE),
            null, DiagnosticCode.E3001);
        check(d9.severity().equals("error"),
            "unknown severity must normalize to \"error\", got " + d9.severity());
        check(d9.notes().size() == 1
                && d9.notes().get(0).message().equals("internal range defect: unknown severity"),
            "unknown severity appends the defect note");

        // 10. Non-canonical synthetic range: normalized to the canonical shape.
        CompilerDiagnostic d10 = new CompilerDiagnostic("E6000", "error", "non-canonical synthetic",
            new DiagnosticRange("m.deal", 2, 3, 2, 5, 4, 6, 2, RangeOrigin.SYNTHETIC),
            null, DiagnosticCode.E6000);
        check(d10.range().isCanonicalSynthetic()
                && d10.range().origin() == RangeOrigin.SYNTHETIC,
            "non-canonical synthetic must normalize to the canonical shape, got " + d10.range());
        check(d10.range().file().equals("m.deal"),
            "non-canonical synthetic normalization keeps the file");
        check(d10.notes().size() == 1
                && d10.notes().get(0).message().equals(
                    "internal range defect: non-canonical synthetic range"),
            "non-canonical synthetic appends the defect note, got " + d10.notes());

        // SYNTHETIC origin with UNKNOWN offsets is non-canonical and
        // normalizes to the canonical shape as well.
        CompilerDiagnostic d10b = new CompilerDiagnostic("E6000", "error",
            "synthetic unknown offsets",
            new DiagnosticRange("m.deal", 1, 1, 1, 1, Span.UNKNOWN_OFFSET, 0, -1,
                RangeOrigin.SYNTHETIC),
            null, DiagnosticCode.E6000);
        check(d10b.range().isCanonicalSynthetic(),
            "SYNTHETIC with UNKNOWN offsets must normalize to the canonical shape");
        check(d10b.notes().size() == 1
                && d10b.notes().get(0).message().equals(
                    "internal range defect: non-canonical synthetic range"),
            "SYNTHETIC with UNKNOWN offsets appends the non-canonical synthetic note");

        // 11. Null origin is a defect too: canonical synthetic + note.
        CompilerDiagnostic d11 = new CompilerDiagnostic("E3001", "error", "null origin",
            new DiagnosticRange("f.deal", 1, 1, 1, 3, 0, 2, 2, null),
            null, DiagnosticCode.E3001);
        check(d11.range().isCanonicalSynthetic(),
            "null origin must normalize to the canonical synthetic shape");
        check(d11.notes().size() == 1
                && d11.notes().get(0).message().equals("internal range defect: null origin"),
            "null origin appends the defect note");

        // 12. A valid SOURCE range and a canonical synthetic range pass
        // through unchanged with no defect notes.
        DiagnosticRange good = new DiagnosticRange("f.deal", 2, 3, 2, 7, 4, 8, 4,
            RangeOrigin.SOURCE);
        CompilerDiagnostic ok = new CompilerDiagnostic("E3001", "warning", "valid",
            good, null, DiagnosticCode.E3001);
        check(ok.range() == good, "valid SOURCE range must pass through unchanged");
        check(ok.notes().isEmpty(), "valid range must carry no defect notes");
        check(ok.severity().equals("warning"), "valid warning severity unchanged");
        check(ok.file().equals("f.deal") && ok.line() == 2 && ok.column() == 3,
            "accessors derive from the range start");
        DiagnosticRange goodSyn = DiagnosticRange.synthetic("m.deal");
        CompilerDiagnostic okSyn = new CompilerDiagnostic("E6000", "error", "valid synthetic",
            goodSyn, null, DiagnosticCode.E6000);
        check(okSyn.range() == goodSyn, "canonical synthetic must pass through unchanged");
        check(okSyn.notes().isEmpty(), "canonical synthetic must carry no defect notes");

        // 13. Null/empty code, severity, or message remain immediate
        // programmer errors (IllegalArgumentException).
        boolean threw = false;
        try {
            new CompilerDiagnostic(null, "error", "m", good, null, DiagnosticCode.E3001);
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check(threw, "null code must throw IAE");
        threw = false;
        try {
            new CompilerDiagnostic("", "error", "m", good, null, DiagnosticCode.E3001);
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check(threw, "empty code must throw IAE");
        threw = false;
        try {
            new CompilerDiagnostic("E3001", null, "m", good, null, DiagnosticCode.E3001);
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check(threw, "null severity must throw IAE");
        threw = false;
        try {
            new CompilerDiagnostic("E3001", "", "m", good, null, DiagnosticCode.E3001);
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check(threw, "empty severity must throw IAE");
        threw = false;
        try {
            new CompilerDiagnostic("E3001", "error", null, good, null, DiagnosticCode.E3001);
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check(threw, "null message must throw IAE");
        threw = false;
        try {
            new CompilerDiagnostic("E3001", "error", "", good, null, DiagnosticCode.E3001);
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check(threw, "empty message must throw IAE");

        // 14. Notes handling: null becomes empty; provided notes are copied
        // defensively and in order; the result is unmodifiable.
        List<DiagnosticNote> mutable = new ArrayList<>();
        mutable.add(new DiagnosticNote("hint", good));
        CompilerDiagnostic withNotes = new CompilerDiagnostic("E3001", "error", "notes",
            good, mutable, DiagnosticCode.E3001);
        check(withNotes.notes().size() == 1
                && withNotes.notes().get(0).message().equals("hint"),
            "provided notes are preserved in order");
        mutable.add(new DiagnosticNote("extra", null));
        check(withNotes.notes().size() == 1,
            "notes must be defensively copied (later mutation of the source list has no effect)");
        boolean addThrew = false;
        try {
            withNotes.notes().add(new DiagnosticNote("nope", null));
        } catch (UnsupportedOperationException e) {
            addThrew = true;
        }
        check(addThrew, "notes() must be unmodifiable (List.copyOf)");
        CompilerDiagnostic nullNotes = new CompilerDiagnostic("E3001", "error", "null notes",
            good, null, DiagnosticCode.E3001);
        check(nullNotes.notes().isEmpty(), "null notes must become an empty list");

        // 15. Defect notes append after the provided notes.
        CompilerDiagnostic appended = new CompilerDiagnostic("E3001", "error", "appended",
            new DiagnosticRange("f.deal", 1, 1, 1, 5, 0, 4, 9, RangeOrigin.SOURCE),
            List.of(new DiagnosticNote("first", null)), DiagnosticCode.E3001);
        check(appended.notes().size() == 2
                && appended.notes().get(0).message().equals("first")
                && appended.notes().get(1).message().equals(
                    "internal range defect: scalar length mismatch"),
            "defect notes must append after the provided notes, got " + appended.notes());

        // 16. toString keeps the SEVERITY, code, and message substrings.
        String ts = ok.toString();
        check(ts.contains("WARNING") && ts.contains("E3001") && ts.contains("valid"),
            "toString keeps the SEVERITY, code, and message substrings: " + ts);
        String tsErr = okSyn.toString();
        check(tsErr.contains("ERROR") && tsErr.contains("E6000"),
            "error toString keeps the SEVERITY and code substrings: " + tsErr);
    }

    @SuppressWarnings("deprecation")
    private static void testFactorySurface() {
        System.out.println("-- D5 factory surface: canonical and deprecated factories --");

        // Canonical DiagnosticRange factories.
        DiagnosticRange r = new DiagnosticRange("f.deal", 1, 2, 1, 5, 3, 6, 3,
            RangeOrigin.SOURCE);
        CompilerDiagnostic e = CompilerDiagnostic.error(DiagnosticCode.E3001,
            "type mismatch", r);
        check(e.code().equals("E3001") && e.severity().equals("error")
                && e.message().equals("type mismatch") && e.range() == r
                && e.diagnosticCode() == DiagnosticCode.E3001,
            "error(DiagnosticCode, message, range) fields");
        CompilerDiagnostic w = CompilerDiagnostic.warning(DiagnosticCode.E1043,
            "placement", r);
        check(w.severity().equals("warning") && w.code().equals("E1043"),
            "warning(DiagnosticCode, message, range) fields");

        // Deprecated string-code factories: pseudo codes yield null
        // diagnosticCode; registered codes resolve.
        CompilerDiagnostic pseudo = CompilerDiagnostic.error("E9999", "pseudo", r);
        check(pseudo.code().equals("E9999") && pseudo.severity().equals("error")
                && pseudo.diagnosticCode() == null,
            "deprecated string-code error factory: pseudo code yields null diagnosticCode");
        CompilerDiagnostic pseudoW = CompilerDiagnostic.warning("W0001", "pseudo warning", r);
        check(pseudoW.code().equals("W0001") && pseudoW.severity().equals("warning")
                && pseudoW.diagnosticCode() == null,
            "deprecated string-code warning factory");
        CompilerDiagnostic registered = CompilerDiagnostic.error("E3001", "registered", r);
        check(registered.diagnosticCode() == DiagnosticCode.E3001,
            "deprecated string-code factory resolves registered codes");

        // Deprecated synthetic factory.
        CompilerDiagnostic syn = CompilerDiagnostic.synthetic("E9999", "warning", "fixture",
            "fixture.deal", "missing anchor: fixture source 'fixture.deal'");
        check(syn.code().equals("E9999") && syn.severity().equals("warning")
                && syn.message().equals("fixture") && syn.diagnosticCode() == null,
            "deprecated synthetic factory fields");
        check(syn.range().isCanonicalSynthetic() && syn.range().file().equals("fixture.deal"),
            "deprecated synthetic factory range");
        check(syn.notes().size() == 1
                && syn.notes().get(0).message().equals(
                    "missing anchor: fixture source 'fixture.deal'"),
            "deprecated synthetic factory anchor note");

        // The three deprecated methods carry @Deprecated; the canonical
        // factories do not.
        try {
            check(CompilerDiagnostic.class.getMethod("error",
                    String.class, String.class, DiagnosticRange.class)
                    .isAnnotationPresent(Deprecated.class),
                "error(String, String, DiagnosticRange) must be @Deprecated");
            check(CompilerDiagnostic.class.getMethod("warning",
                    String.class, String.class, DiagnosticRange.class)
                    .isAnnotationPresent(Deprecated.class),
                "warning(String, String, DiagnosticRange) must be @Deprecated");
            check(CompilerDiagnostic.class.getMethod("synthetic",
                    String.class, String.class, String.class, String.class, String.class)
                    .isAnnotationPresent(Deprecated.class),
                "synthetic(String, String, String, String, String) must be @Deprecated");
            check(!CompilerDiagnostic.class.getMethod("error",
                    DiagnosticCode.class, String.class, DiagnosticRange.class)
                    .isAnnotationPresent(Deprecated.class),
                "error(DiagnosticCode, String, DiagnosticRange) must not be @Deprecated");
            check(!CompilerDiagnostic.class.getMethod("syntheticError",
                    DiagnosticCode.class, String.class, String.class, String.class)
                    .isAnnotationPresent(Deprecated.class),
                "syntheticError must not be @Deprecated");
        } catch (NoSuchMethodException ex) {
            fail("factory method missing: " + ex);
        }

        // A null DiagnosticCode is a programmer error.
        boolean threw = false;
        try {
            CompilerDiagnostic.error((DiagnosticCode) null, "m", r);
        } catch (IllegalArgumentException ex) {
            threw = true;
        }
        check(threw, "error(null DiagnosticCode, ...) must throw IAE");
    }

    // =========================================================================
    // ISSUE-0219 formatter/structured section (verification 1)
    // =========================================================================

    /** The shared multi-line SOURCE fixture: (2,5)-(3,9), offsets [20,24), span 4. */
    private static CompilerDiagnostic multiLineDiagnostic() {
        return CompilerDiagnostic.error(DiagnosticCode.E3001, "type mismatch",
            new DiagnosticRange("src/main.deal", 2, 5, 3, 9, 20, 24, 4,
                RangeOrigin.SOURCE));
    }

    private static void testFormatter() {
        System.out.println("-- DiagnosticFormatter: canonical human format (D8) --");

        // Multi-line SOURCE range, no notes.
        CompilerDiagnostic multi = multiLineDiagnostic();
        check(DiagnosticFormatter.format(multi).equals(
                "src/main.deal:2:5-3:9: ERROR E3001: type mismatch [span 4]"),
            "multi-line SOURCE format, got: " + DiagnosticFormatter.format(multi));

        // Zero-length SOURCE range at a real anchor.
        CompilerDiagnostic zero = CompilerDiagnostic.error(DiagnosticCode.E2010,
            "Entry module must export 'main'",
            new DiagnosticRange("f.deal", 1, 1, 1, 1, 0, 0, 0, RangeOrigin.SOURCE));
        check(DiagnosticFormatter.format(zero).equals(
                "f.deal:1:1-1:1: ERROR E2010: Entry module must export 'main' [span 0]"),
            "zero-length SOURCE format, got: " + DiagnosticFormatter.format(zero));

        // Canonical SYNTHETIC range with a construct-naming anchor note.
        CompilerDiagnostic syn = CompilerDiagnostic.syntheticError(DiagnosticCode.E4008,
            "circular jsonable dependency", "mod.deal",
            "missing anchor: class declaration span for cycle node 'A'");
        check(DiagnosticFormatter.format(syn).equals(
                "mod.deal:1:1-1:1: ERROR E4008: circular jsonable dependency [span 0]\n"
                    + "    note: missing anchor: class declaration span for cycle node 'A'"),
            "SYNTHETIC format with construct-naming note, got: " + DiagnosticFormatter.format(syn));

        // Message-only note rendering.
        CompilerDiagnostic msgOnly = new CompilerDiagnostic("E3001", "warning",
            "type mismatch", multi.range(),
            List.of(new DiagnosticNote("consider using number", null)),
            DiagnosticCode.E3001);
        check(DiagnosticFormatter.format(msgOnly).equals(
                "src/main.deal:2:5-3:9: WARNING E3001: type mismatch [span 4]\n"
                    + "    note: consider using number"),
            "message-only note rendering, got: " + DiagnosticFormatter.format(msgOnly));

        // Secondary-range note rendering.
        CompilerDiagnostic secondary = new CompilerDiagnostic("E3001", "error",
            "type mismatch", multi.range(),
            List.of(new DiagnosticNote("see declaration",
                new DiagnosticRange("other.deal", 1, 2, 1, 6, 0, 4, 4,
                    RangeOrigin.SOURCE))),
            DiagnosticCode.E3001);
        check(DiagnosticFormatter.format(secondary).equals(
                "src/main.deal:2:5-3:9: ERROR E3001: type mismatch [span 4]\n"
                    + "    note: see declaration (at other.deal:1:2-1:6, span 4)"),
            "secondary-range note rendering, got: " + DiagnosticFormatter.format(secondary));

        // Both note renderings in one diagnostic, in note order.
        CompilerDiagnostic both = new CompilerDiagnostic("E3001", "error", "type mismatch",
            multi.range(),
            List.of(new DiagnosticNote("first hint", null),
                new DiagnosticNote("see declaration",
                    new DiagnosticRange("other.deal", 2, 1, 2, 4, 7, 10, 3,
                        RangeOrigin.SOURCE))),
            DiagnosticCode.E3001);
        check(DiagnosticFormatter.format(both).equals(
                "src/main.deal:2:5-3:9: ERROR E3001: type mismatch [span 4]\n"
                    + "    note: first hint\n"
                    + "    note: see declaration (at other.deal:2:1-2:4, span 3)"),
            "both note renderings in order, got: " + DiagnosticFormatter.format(both));

        // Determinism: formatting twice yields the identical string.
        check(DiagnosticFormatter.format(both).equals(DiagnosticFormatter.format(both)),
            "formatter must be deterministic");
    }

    private static void testStructuredOutput() {
        System.out.println("-- DiagnosticStructuredOutput: deterministic JSON v1 (D8) --");

        // Empty diagnostics list; a null list yields the same empty document.
        String emptyJson = "{\n"
            + "  \"version\": 1,\n"
            + "  \"diagnostics\": []\n"
            + "}";
        check(DiagnosticStructuredOutput.toJson(List.of()).equals(emptyJson),
            "empty diagnostics JSON shape, got: " + DiagnosticStructuredOutput.toJson(List.of()));
        check(DiagnosticStructuredOutput.toJson(null).equals(emptyJson),
            "null diagnostics list yields the empty document");

        // Single multi-line SOURCE diagnostic, no notes: exact field order
        // (code, severity, message, range{file, startLine, startColumn,
        // endLine, endColumn, startScalarOffset, endScalarOffset,
        // scalarLength, origin}, notes).
        CompilerDiagnostic multi = multiLineDiagnostic();
        String multiJson = "{\n"
            + "  \"version\": 1,\n"
            + "  \"diagnostics\": [\n"
            + "    {\n"
            + "      \"code\": \"E3001\",\n"
            + "      \"severity\": \"error\",\n"
            + "      \"message\": \"type mismatch\",\n"
            + "      \"range\": {\n"
            + "        \"file\": \"src/main.deal\",\n"
            + "        \"startLine\": 2,\n"
            + "        \"startColumn\": 5,\n"
            + "        \"endLine\": 3,\n"
            + "        \"endColumn\": 9,\n"
            + "        \"startScalarOffset\": 20,\n"
            + "        \"endScalarOffset\": 24,\n"
            + "        \"scalarLength\": 4,\n"
            + "        \"origin\": \"SOURCE\"\n"
            + "      },\n"
            + "      \"notes\": []\n"
            + "    }\n"
            + "  ]\n"
            + "}";
        check(DiagnosticStructuredOutput.toJson(List.of(multi)).equals(multiJson),
            "single-diagnostic JSON field order, got: "
                + DiagnosticStructuredOutput.toJson(List.of(multi)));

        // Zero-length SOURCE range: positions (1,1)-(1,1), offsets (0,0),
        // scalar length 0, origin SOURCE.
        CompilerDiagnostic zero = CompilerDiagnostic.error(DiagnosticCode.E2010,
            "Entry module must export 'main'",
            new DiagnosticRange("f.deal", 1, 1, 1, 1, 0, 0, 0, RangeOrigin.SOURCE));
        String zeroJson = "{\n"
            + "  \"version\": 1,\n"
            + "  \"diagnostics\": [\n"
            + "    {\n"
            + "      \"code\": \"E2010\",\n"
            + "      \"severity\": \"error\",\n"
            + "      \"message\": \"Entry module must export 'main'\",\n"
            + "      \"range\": {\n"
            + "        \"file\": \"f.deal\",\n"
            + "        \"startLine\": 1,\n"
            + "        \"startColumn\": 1,\n"
            + "        \"endLine\": 1,\n"
            + "        \"endColumn\": 1,\n"
            + "        \"startScalarOffset\": 0,\n"
            + "        \"endScalarOffset\": 0,\n"
            + "        \"scalarLength\": 0,\n"
            + "        \"origin\": \"SOURCE\"\n"
            + "      },\n"
            + "      \"notes\": []\n"
            + "    }\n"
            + "  ]\n"
            + "}";
        check(DiagnosticStructuredOutput.toJson(List.of(zero)).equals(zeroJson),
            "zero-length SOURCE JSON, got: "
                + DiagnosticStructuredOutput.toJson(List.of(zero)));

        // Canonical SYNTHETIC range with a construct-naming note: origin
        // SYNTHETIC, canonical (1,1)-(1,1) shape, the note is message-only
        // ("range": null).
        CompilerDiagnostic syn = CompilerDiagnostic.syntheticError(DiagnosticCode.E4008,
            "circular jsonable dependency", "mod.deal",
            "missing anchor: class declaration span for cycle node 'A'");
        String synJson = "{\n"
            + "  \"version\": 1,\n"
            + "  \"diagnostics\": [\n"
            + "    {\n"
            + "      \"code\": \"E4008\",\n"
            + "      \"severity\": \"error\",\n"
            + "      \"message\": \"circular jsonable dependency\",\n"
            + "      \"range\": {\n"
            + "        \"file\": \"mod.deal\",\n"
            + "        \"startLine\": 1,\n"
            + "        \"startColumn\": 1,\n"
            + "        \"endLine\": 1,\n"
            + "        \"endColumn\": 1,\n"
            + "        \"startScalarOffset\": 0,\n"
            + "        \"endScalarOffset\": 0,\n"
            + "        \"scalarLength\": 0,\n"
            + "        \"origin\": \"SYNTHETIC\"\n"
            + "      },\n"
            + "      \"notes\": [\n"
            + "        {\n"
            + "          \"message\": \"missing anchor: class declaration span for cycle node 'A'\",\n"
            + "          \"range\": null\n"
            + "        }\n"
            + "      ]\n"
            + "    }\n"
            + "  ]\n"
            + "}";
        check(DiagnosticStructuredOutput.toJson(List.of(syn)).equals(synJson),
            "SYNTHETIC JSON with message-only anchor note, got: "
                + DiagnosticStructuredOutput.toJson(List.of(syn)));

        // Both note shapes: a message-only note ("range": null) and a
        // secondary-range note (nested range object), in note order.
        CompilerDiagnostic withNotes = new CompilerDiagnostic("E3001", "error",
            "type mismatch", multi.range(),
            List.of(new DiagnosticNote("hint", null),
                new DiagnosticNote("see declaration",
                    new DiagnosticRange("other.deal", 1, 2, 1, 6, 0, 4, 4,
                        RangeOrigin.SOURCE))),
            DiagnosticCode.E3001);
        String notesJson = "{\n"
            + "  \"version\": 1,\n"
            + "  \"diagnostics\": [\n"
            + "    {\n"
            + "      \"code\": \"E3001\",\n"
            + "      \"severity\": \"error\",\n"
            + "      \"message\": \"type mismatch\",\n"
            + "      \"range\": {\n"
            + "        \"file\": \"src/main.deal\",\n"
            + "        \"startLine\": 2,\n"
            + "        \"startColumn\": 5,\n"
            + "        \"endLine\": 3,\n"
            + "        \"endColumn\": 9,\n"
            + "        \"startScalarOffset\": 20,\n"
            + "        \"endScalarOffset\": 24,\n"
            + "        \"scalarLength\": 4,\n"
            + "        \"origin\": \"SOURCE\"\n"
            + "      },\n"
            + "      \"notes\": [\n"
            + "        {\n"
            + "          \"message\": \"hint\",\n"
            + "          \"range\": null\n"
            + "        },\n"
            + "        {\n"
            + "          \"message\": \"see declaration\",\n"
            + "          \"range\": {\n"
            + "            \"file\": \"other.deal\",\n"
            + "            \"startLine\": 1,\n"
            + "            \"startColumn\": 2,\n"
            + "            \"endLine\": 1,\n"
            + "            \"endColumn\": 6,\n"
            + "            \"startScalarOffset\": 0,\n"
            + "            \"endScalarOffset\": 4,\n"
            + "            \"scalarLength\": 4,\n"
            + "            \"origin\": \"SOURCE\"\n"
            + "          }\n"
            + "        }\n"
            + "      ]\n"
            + "    }\n"
            + "  ]\n"
            + "}";
        check(DiagnosticStructuredOutput.toJson(List.of(withNotes)).equals(notesJson),
            "both note shapes serialized in order, got: "
                + DiagnosticStructuredOutput.toJson(List.of(withNotes)));

        // Two diagnostics: comma separation and list order.
        String two = DiagnosticStructuredOutput.toJson(List.of(multi, zero));
        check(two.contains("    },\n    {"), "diagnostics separated by a comma in list order");
        check(two.indexOf("\"code\": \"E3001\"") < two.indexOf("\"code\": \"E2010\""),
            "diagnostics serialized in list order");

        // String escaping: quotes, backslashes, tab, newline, and a control
        // character below U+0020.
        CompilerDiagnostic escaped = CompilerDiagnostic.error(DiagnosticCode.E3001,
            "bad \"key\" \\ path\tnext\nline\u0007bell", multi.range());
        String escapedJson = DiagnosticStructuredOutput.toJson(List.of(escaped));
        check(escapedJson.contains(
                "\"message\": \"bad \\\"key\\\" \\\\ path\\tnext\\nline"
                    + "\\" + "u0007bell\""),
            "JSON string escaping, got: " + escapedJson);

        // Determinism: serializing twice yields the identical document.
        check(DiagnosticStructuredOutput.toJson(List.of(withNotes))
                .equals(DiagnosticStructuredOutput.toJson(List.of(withNotes))),
            "structured output must be deterministic");
    }

    private static void testFormattedStructuredCrossCheck() {
        System.out.println("-- Formatted-vs-structured cross-check --");

        CompilerDiagnostic d = new CompilerDiagnostic("E3001", "error", "type mismatch",
            new DiagnosticRange("src/main.deal", 2, 5, 3, 9, 20, 24, 4,
                RangeOrigin.SOURCE),
            List.of(new DiagnosticNote("hint", null),
                new DiagnosticNote("see declaration",
                    new DiagnosticRange("other.deal", 1, 2, 1, 6, 0, 4, 4,
                        RangeOrigin.SOURCE))),
            DiagnosticCode.E3001);
        String human = DiagnosticFormatter.format(d);
        String json = DiagnosticStructuredOutput.toJson(List.of(d));

        // Every field present in both surfaces agrees: file, start/end
        // positions, span length, severity, code, message.
        check(human.startsWith("src/main.deal:2:5-3:9:"),
            "human carries the file/start/end positions: " + human);
        check(human.contains("ERROR E3001: type mismatch [span 4]"),
            "human carries severity/code/message/span length: " + human);
        check(json.contains("\"file\": \"src/main.deal\"")
                && json.contains("\"startLine\": 2")
                && json.contains("\"startColumn\": 5")
                && json.contains("\"endLine\": 3")
                && json.contains("\"endColumn\": 9"),
            "structured carries the same file/start/end positions: " + json);

        // Scalar offsets and span length: the structured end - start equals
        // the human [span N] suffix.
        check(json.contains("\"startScalarOffset\": 20")
                && json.contains("\"endScalarOffset\": 24")
                && json.contains("\"scalarLength\": 4"),
            "structured carries the same offsets and span length: " + json);
        check(human.contains("[span 4]"),
            "human span length agrees with the structured offsets: " + human);

        // Origin: structured-only field, SOURCE for this fixture.
        check(json.contains("\"origin\": \"SOURCE\""),
            "structured carries the SOURCE origin: " + json);

        // Notes: the message-only note renders without a range in both
        // surfaces; the secondary-range note carries the same range in both.
        check(human.contains("note: hint"),
            "human carries the message-only note: " + human);
        check(json.contains("\"message\": \"hint\"") && json.contains("\"range\": null"),
            "structured carries the message-only note with a null range: " + json);
        check(human.contains("note: see declaration (at other.deal:1:2-1:6, span 4)"),
            "human carries the secondary-range note: " + human);
        check(json.contains("\"message\": \"see declaration\"")
                && json.contains("\"file\": \"other.deal\"")
                && json.contains("\"startLine\": 1")
                && json.contains("\"endColumn\": 6")
                && json.contains("\"startScalarOffset\": 0")
                && json.contains("\"endScalarOffset\": 4"),
            "structured carries the same secondary-range note: " + json);

        // SYNTHETIC fixture: both surfaces carry the canonical (1,1)-(1,1)
        // zero-length shape; the structured origin is SYNTHETIC.
        CompilerDiagnostic syn = CompilerDiagnostic.syntheticError(DiagnosticCode.E4008,
            "circular jsonable dependency", "mod.deal",
            "missing anchor: class declaration span for cycle node 'A'");
        String synHuman = DiagnosticFormatter.format(syn);
        String synJson = DiagnosticStructuredOutput.toJson(List.of(syn));
        check(synHuman.startsWith("mod.deal:1:1-1:1:")
                && synHuman.contains("[span 0]"),
            "synthetic human carries the canonical (1,1)-(1,1) shape: " + synHuman);
        check(synJson.contains("\"origin\": \"SYNTHETIC\"")
                && synJson.contains("\"scalarLength\": 0")
                && synJson.contains("\"startScalarOffset\": 0"),
            "synthetic structured carries the canonical zero-length shape: " + synJson);
        check(synHuman.contains("missing anchor: class declaration span for cycle node 'A'")
                && synJson.contains(
                    "\"message\": \"missing anchor: class declaration span for cycle node 'A'\""),
            "both surfaces carry the construct-naming anchor note");
    }

    private static void testToStringDelegation() {
        System.out.println("-- CompilerDiagnostic.toString() delegates to the canonical formatter --");

        List<CompilerDiagnostic> fixtures = List.of(
            multiLineDiagnostic(),
            CompilerDiagnostic.error(DiagnosticCode.E2010, "Entry module must export 'main'",
                new DiagnosticRange("f.deal", 1, 1, 1, 1, 0, 0, 0, RangeOrigin.SOURCE)),
            CompilerDiagnostic.syntheticError(DiagnosticCode.E4008,
                "circular jsonable dependency", "mod.deal",
                "missing anchor: class declaration span for cycle node 'A'"),
            new CompilerDiagnostic("E3001", "warning", "type mismatch",
                new DiagnosticRange("src/main.deal", 2, 5, 3, 9, 20, 24, 4,
                    RangeOrigin.SOURCE),
                List.of(new DiagnosticNote("consider using number", null)),
                DiagnosticCode.E3001),
            new CompilerDiagnostic("E3001", "error", "type mismatch",
                new DiagnosticRange("src/main.deal", 2, 5, 3, 9, 20, 24, 4,
                    RangeOrigin.SOURCE),
                List.of(new DiagnosticNote("see declaration",
                    new DiagnosticRange("other.deal", 1, 2, 1, 6, 0, 4, 4,
                        RangeOrigin.SOURCE))),
                DiagnosticCode.E3001));

        for (CompilerDiagnostic d : fixtures) {
            String ts = d.toString();
            check(ts.equals(DiagnosticFormatter.format(d)),
                "toString must equal DiagnosticFormatter.format exactly: " + ts);
            check(ts.contains(d.severity().toUpperCase()),
                "toString must contain the SEVERITY substring: " + ts);
            check(ts.contains(d.code()),
                "toString must contain the code substring: " + ts);
            check(ts.contains(d.message()),
                "toString must contain the message substring: " + ts);
        }
    }
}
