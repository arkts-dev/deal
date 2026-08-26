package deal.test;

import deal.ast.Span;
import deal.ast.TemplateLiteralExpr;
import deal.ast.TokenType;
import deal.ast.VariableDeclaration;
import deal.codegen.jvm.JvmBackend;
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
import deal.lexer.LexResult;
import deal.lexer.Lexer;
import deal.lexer.Token;
import deal.module.CompilationOrchestrator;
import deal.parser.ParseResult;
import deal.parser.Parser;
import deal.source.JsonRangeLexer;
import deal.source.ScalarPosition;
import deal.source.ScalarSourceCursor;
import deal.source.SourceScalarRange;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
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
 * {@link ScalarSourceCursor} recomputation), the ISSUE-0225
 * checker/validator producer section (name/type error ranges after
 * astral characters, tabs, and CRLF line endings and over multi-line
 * expression spans, each cross-checked against an independent cursor
 * recomputation and against the formatted and structured surfaces),
 * the combined program-span anchor section (E2010/E2011 entry-main
 * validation and JvmBackend E6004 sharing the program-span anchor: a
 * non-(1,1) first statement carries the exact non-zero start scalar
 * offset and an empty/whitespace-only entry pins
 * {@code (file,1,1,1,1,0,0,0,SOURCE)} — never SYNTHETIC, no anchor
 * note), and the ISSUE-0220
 * JsonRangeLexer section ({@link JsonRangeLexer} token/member/fault
 * ranges — every asserted range is an exact half-open
 * {@link SourceScalarRange} recomputed against an independent
 * {@link ScalarSourceCursor} — the pinned permissive acceptance surface,
 * the never-throws sweep, and the {@code deal/source} JDK-only package
 * scan). Later capabilities extend this file with the DealConfig
 * migration and producer-migration sections.
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
        testProgramSpanAnchorsCombined();

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
        System.out.println("-- JsonRangeLexer section --");
        testJsonRangeLexerTokenAndMemberRanges();
        testJsonRangeLexerFaults();
        testJsonRangeLexerBlindWindows();
        testJsonRangeLexerTolerance();
        testJsonRangeLexerNeverThrows();
        testDealSourceJdkOnly();

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
     * Cross-checks the canonical formatted and structured surfaces of one
     * diagnostic against its carrier range (D8): the human rendering
     * carries the exact range header and {@code [span N]} suffix, and the
     * structured document carries the same file, start/end positions,
     * scalar offsets, span length, and origin.
     */
    private static void checkFormattedStructuredAgree(CompilerDiagnostic d,
                                                      String context) {
        if (d == null) {
            check(false, context + ": diagnostic missing for cross-check");
            return;
        }
        DiagnosticRange r = d.range();
        String human = DiagnosticFormatter.format(d);
        String json = DiagnosticStructuredOutput.toJson(List.of(d));
        check(human.startsWith(r.file() + ":" + r.startLine() + ":"
                + r.startColumn() + "-" + r.endLine() + ":" + r.endColumn()
                + ": " + d.severity().toUpperCase() + " " + d.code() + ":"),
            context + ": formatted carries the exact range header: " + human);
        check(human.contains("[span " + r.scalarLength() + "]"),
            context + ": formatted carries the span length: " + human);
        check(json.contains("\"file\": \"" + r.file() + "\"")
                && json.contains("\"startLine\": " + r.startLine())
                && json.contains("\"startColumn\": " + r.startColumn())
                && json.contains("\"endLine\": " + r.endLine())
                && json.contains("\"endColumn\": " + r.endColumn())
                && json.contains("\"startScalarOffset\": " + r.startScalarOffset())
                && json.contains("\"endScalarOffset\": " + r.endScalarOffset())
                && json.contains("\"scalarLength\": " + r.scalarLength())
                && json.contains("\"origin\": \"" + r.origin() + "\""),
            context + ": structured carries the same range fields: " + json);
    }

    /**
     * ISSUE-0225 verification 6: name-resolution and type errors carry
     * SOURCE ranges that are scalar-exact after astral characters, tabs,
     * and CRLF line endings and over multi-line expression spans — each
     * cross-checked against an independent {@link ScalarSourceCursor}
     * recomputation and against the formatted and structured surfaces
     * (D8) — the recorded-span E4008 cycle anchors at the class
     * declaration span, and an E4008 cycle without a recorded span yields
     * the canonical synthetic range plus a note naming the cycle-node
     * class.
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
        checkFormattedStructuredAgree(e2000, "C1");

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
        checkFormattedStructuredAgree(e2001, "C2");

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
            checkFormattedStructuredAgree(e3010, "C3");
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

        // 6. Error position after a CRLF line break: CRLF counts two
        //    scalars and one line break, so the E2000 'break' anchor on
        //    line 2 starts at scalar offset 17 (15 line-1 scalars + 2 CRLF
        //    scalars) — pinned against the independent cursor
        //    recomputation.
        String src6 = "let x: int = 1;\r\nbreak;\r\n";
        List<CompilerDiagnostic> d6 = checkerDiagnostics(src6, "crlf.deal");
        CompilerDiagnostic e2000crlf = checkerDiag(d6, "E2000");
        check(e2000crlf != null, "C6: E2000 present after CRLF, got " + d6);
        checkNeedleRange(e2000crlf, src6, "crlf.deal", "break", "C6");
        if (e2000crlf != null) {
            DiagnosticRange r6 = e2000crlf.range();
            check(r6.startLine() == 2 && r6.startColumn() == 1
                    && r6.startScalarOffset() == 17,
                "C6: E2000 starts at 2:1 with scalar offset 17 (CRLF = two scalars): "
                    + r6);
            checkFormattedStructuredAgree(e2000crlf, "C6");
        }

        // 7. Multi-line expression span across a CRLF break: the E3010
        //    boolean '+' binary-expression span covers line 1's 'true'
        //    through line 2's 'false'; the CRLF contributes two scalars
        //    to the offsets and exactly one line increment to the end
        //    position.
        String src7 = "let x: boolean = true +\r\n    false;\r\n";
        List<CompilerDiagnostic> d7 = checkerDiagnostics(src7, "mlcrlf.deal");
        CompilerDiagnostic e3010crlf = checkerDiag(d7, "E3010");
        check(e3010crlf != null, "C7: E3010 present across CRLF, got " + d7);
        if (e3010crlf != null) {
            int start = src7.indexOf("true");
            int end = src7.indexOf("false") + "false".length();
            ScalarSourceCursor cs = cursorAt(src7, start);
            ScalarSourceCursor ce = cursorAt(src7, end);
            DiagnosticRange r = e3010crlf.range();
            check(r.origin() == RangeOrigin.SOURCE
                    && r.startLine() == 1 && r.startColumn() == cs.column()
                    && r.endLine() == 2 && r.endColumn() == ce.column()
                    && r.startScalarOffset() == cs.scalarOffset()
                    && r.endScalarOffset() == ce.scalarOffset()
                    && r.scalarLength() == ce.scalarOffset() - cs.scalarOffset(),
                "C7: CRLF multi-line span " + r + " vs cursor start (1,"
                    + cs.column() + ",@" + cs.scalarOffset() + ") end (2,"
                    + ce.column() + ",@" + ce.scalarOffset() + ")");
            check(r.endScalarOffset() - r.startScalarOffset() >= 8,
                "C7: the CRLF break contributes two scalars to the span length: "
                    + r);
            checkFormattedStructuredAgree(e3010crlf, "C7");
        }

        // 8. Error position after a tab: the tab counts exactly one
        //    scalar, so the E2000 'break' anchor on line 2 starts at
        //    column 2 with scalar offset 17 (15 line-1 scalars + LF + one
        //    tab scalar).
        String src8 = "let x: int = 1;\n\tbreak;\n";
        List<CompilerDiagnostic> d8 = checkerDiagnostics(src8, "tab.deal");
        CompilerDiagnostic e2000tab = checkerDiag(d8, "E2000");
        check(e2000tab != null, "C8: E2000 present after the tab, got " + d8);
        checkNeedleRange(e2000tab, src8, "tab.deal", "break", "C8");
        if (e2000tab != null) {
            DiagnosticRange r8 = e2000tab.range();
            check(r8.startLine() == 2 && r8.startColumn() == 2
                    && r8.startScalarOffset() == 17,
                "C8: E2000 starts at 2:2 with scalar offset 17 (tab = one scalar): "
                    + r8);
            checkFormattedStructuredAgree(e2000tab, "C8");
        }
    }

    // =========================================================================
    // Combined program-span anchor section (verification 2)
    // =========================================================================

    /**
     * Verification-2 combined program-span fixture: E2010/E2011
     * ({@link CompilationOrchestrator} entry-main validation) and
     * JvmBackend E6004 share the parser's program-span anchor. A first
     * statement that does not start at (1,1) carries a SOURCE range
     * starting at the program start with the exact non-zero start scalar
     * offset; an empty or whitespace-only entry pins
     * {@code (file,1,1,1,1,0,0,0,SOURCE)} — never SYNTHETIC, no anchor
     * note. Each SOURCE anchor is also cross-checked against the
     * formatted and structured surfaces (D8).
     */
    private static void testProgramSpanAnchorsCombined() throws Exception {
        System.out.println("-- Program-span anchors: E2010/E2011/E6004 combined fixture --");

        Path tmp = Files.createTempDirectory("deal_range_progspan_");
        try {
            Path srcDir = Files.createDirectories(tmp.resolve("src"));
            List<Path> moduleRoots = List.of(srcDir.toAbsolutePath());

            // PS1: E2010 — an entry without main whose first statement
            // starts at 2:1 after a leading comment. The orchestrator
            // prints failed-compilation diagnostics on stderr; capture it
            // so the gate output stays clean.
            String commented = "// leading comment\n"
                + "export function run(): int { return 1; }\n";
            Path commentedEntry = srcDir.resolve("ps_e2010.deal");
            Files.writeString(commentedEntry, commented);
            CompilationOrchestrator e2010Orch = new CompilationOrchestrator(
                commentedEntry.toAbsolutePath(), tmp.resolve("build/e2010"),
                false, null, moduleRoots, null);
            boolean ps1Failed;
            PrintStream originalErr = System.err;
            try {
                System.setErr(new PrintStream(new ByteArrayOutputStream(), true,
                    StandardCharsets.UTF_8));
                ps1Failed = !e2010Orch.compile();
            } finally {
                System.setErr(originalErr);
            }
            check(ps1Failed, "PS1: commented entry without main fails compilation");
            CompilerDiagnostic e2010 = firstCode(e2010Orch.diagnostics(), "E2010");
            check(e2010 != null, "PS1: E2010 present: " + e2010Orch.diagnostics());
            if (e2010 != null) {
                int expectedStart = ScalarSourceCursor.scalarCount(commented, 0,
                    commented.indexOf("export"));
                DiagnosticRange r = e2010.range();
                check(r.origin() == RangeOrigin.SOURCE
                        && r.startLine() == 2 && r.startColumn() == 1
                        && r.startScalarOffset() == expectedStart
                        && r.endScalarOffset() > r.startScalarOffset()
                        && r.scalarLength() == r.endScalarOffset() - r.startScalarOffset(),
                    "PS1: E2010 anchors SOURCE at 2:1 with the exact non-zero start offset ("
                        + expectedStart + "): " + r);
                check(e2010.notes().isEmpty(),
                    "PS1: the SOURCE-anchored E2010 carries no anchor note: "
                        + e2010.notes());
                checkFormattedStructuredAgree(e2010, "PS1");
            }

            // PS2: E2011 — a main with a wrong signature after the same
            // leading comment; the entry-main validation anchors at the
            // program span, so the range starts at the export at 2:1.
            String badMain = "// leading comment\n"
                + "export function main(x: int): null { return null; }\n";
            Path badMainEntry = srcDir.resolve("ps_e2011.deal");
            Files.writeString(badMainEntry, badMain);
            CompilationOrchestrator e2011Orch = new CompilationOrchestrator(
                badMainEntry.toAbsolutePath(), tmp.resolve("build/e2011"),
                false, null, moduleRoots, null);
            boolean ps2Failed;
            try {
                System.setErr(new PrintStream(new ByteArrayOutputStream(), true,
                    StandardCharsets.UTF_8));
                ps2Failed = !e2011Orch.compile();
            } finally {
                System.setErr(originalErr);
            }
            check(ps2Failed, "PS2: wrong-signature main fails compilation");
            CompilerDiagnostic e2011 = firstCode(e2011Orch.diagnostics(), "E2011");
            check(e2011 != null, "PS2: E2011 present: " + e2011Orch.diagnostics());
            if (e2011 != null) {
                int expectedStart = ScalarSourceCursor.scalarCount(badMain, 0,
                    badMain.indexOf("export"));
                DiagnosticRange r = e2011.range();
                check(r.origin() == RangeOrigin.SOURCE
                        && r.startLine() == 2 && r.startColumn() == 1
                        && r.startScalarOffset() == expectedStart
                        && r.endScalarOffset() > r.startScalarOffset()
                        && r.scalarLength() == r.endScalarOffset() - r.startScalarOffset(),
                    "PS2: E2011 anchors SOURCE at 2:1 with the exact non-zero start offset ("
                        + expectedStart + "): " + r);
                check(e2011.notes().isEmpty(),
                    "PS2: the SOURCE-anchored E2011 carries no anchor note: "
                        + e2011.notes());
                checkFormattedStructuredAgree(e2011, "PS2");
            }

            // PS3: E2010 for an empty entry pins the explicit zero-length
            // SOURCE range at file start — never SYNTHETIC, no note.
            Path emptyEntry = srcDir.resolve("ps_empty.deal");
            Files.writeString(emptyEntry, "");
            CompilationOrchestrator emptyOrch = new CompilationOrchestrator(
                emptyEntry.toAbsolutePath(), tmp.resolve("build/ps_empty"),
                false, null, moduleRoots, null);
            boolean ps3Failed;
            try {
                System.setErr(new PrintStream(new ByteArrayOutputStream(), true,
                    StandardCharsets.UTF_8));
                ps3Failed = !emptyOrch.compile();
            } finally {
                System.setErr(originalErr);
            }
            check(ps3Failed, "PS3: empty entry fails compilation");
            CompilerDiagnostic emptyE2010 = firstCode(emptyOrch.diagnostics(), "E2010");
            check(emptyE2010 != null,
                "PS3: empty entry produces E2010: " + emptyOrch.diagnostics());
            if (emptyE2010 != null) {
                DiagnosticRange r = emptyE2010.range();
                check(r.origin() == RangeOrigin.SOURCE
                        && r.startLine() == 1 && r.startColumn() == 1
                        && r.endLine() == 1 && r.endColumn() == 1
                        && r.startScalarOffset() == 0
                        && r.endScalarOffset() == 0
                        && r.scalarLength() == 0,
                    "PS3: empty entry E2010 pins (file,1,1,1,1,0,0,0,SOURCE): " + r);
                check(emptyE2010.notes().isEmpty(),
                    "PS3: empty entry E2010 carries no anchor note: "
                        + emptyE2010.notes());
                checkFormattedStructuredAgree(emptyE2010, "PS3");
            }

            // PS4: whitespace-only entry — the same pinned document-start
            // shape (zero tokens; the program span is the explicit
            // zero-length SOURCE range at file start).
            Path wsEntry = srcDir.resolve("ps_ws.deal");
            Files.writeString(wsEntry, " \t\n");
            CompilationOrchestrator wsOrch = new CompilationOrchestrator(
                wsEntry.toAbsolutePath(), tmp.resolve("build/ps_ws"),
                false, null, moduleRoots, null);
            boolean ps4Failed;
            try {
                System.setErr(new PrintStream(new ByteArrayOutputStream(), true,
                    StandardCharsets.UTF_8));
                ps4Failed = !wsOrch.compile();
            } finally {
                System.setErr(originalErr);
            }
            check(ps4Failed, "PS4: whitespace-only entry fails compilation");
            CompilerDiagnostic wsE2010 = firstCode(wsOrch.diagnostics(), "E2010");
            check(wsE2010 != null,
                "PS4: whitespace-only entry produces E2010: " + wsOrch.diagnostics());
            if (wsE2010 != null) {
                DiagnosticRange r = wsE2010.range();
                check(r.origin() == RangeOrigin.SOURCE
                        && r.startLine() == 1 && r.startColumn() == 1
                        && r.endLine() == 1 && r.endColumn() == 1
                        && r.startScalarOffset() == 0
                        && r.endScalarOffset() == 0
                        && r.scalarLength() == 0,
                    "PS4: whitespace-only entry E2010 pins (file,1,1,1,1,0,0,0,SOURCE): "
                        + r);
                check(wsE2010.notes().isEmpty(),
                    "PS4: whitespace-only entry E2010 carries no anchor note: "
                        + wsE2010.notes());
            }

            // PS5: JvmBackend E6004 on the same commented program shape —
            // the backend's entry gate anchors at program.span(), which
            // is SOURCE-exact through the parser's spanBetween
            // construction.
            JvmBackend.JvmCodegenResult res = jvmEntryGenerate(commented,
                "ps_e6004.deal");
            CompilerDiagnostic e6004 = firstCode(res.diagnostics(), "E6004");
            check(e6004 != null, "PS5: E6004 present: " + res.diagnostics());
            if (e6004 != null) {
                int expectedStart = ScalarSourceCursor.scalarCount(commented, 0,
                    commented.indexOf("export"));
                DiagnosticRange r = e6004.range();
                check(r.origin() == RangeOrigin.SOURCE
                        && r.startLine() == 2 && r.startColumn() == 1
                        && r.startScalarOffset() == expectedStart
                        && r.endScalarOffset() > r.startScalarOffset()
                        && r.scalarLength() == r.endScalarOffset() - r.startScalarOffset(),
                    "PS5: E6004 anchors SOURCE at 2:1 with the exact non-zero start offset ("
                        + expectedStart + "): " + r);
                check(e6004.notes().isEmpty(),
                    "PS5: the SOURCE-anchored E6004 carries no anchor note: "
                        + e6004.notes());
                checkFormattedStructuredAgree(e6004, "PS5");
            }

            // PS6: JvmBackend E6004 for an empty program pins the
            // explicit zero-length SOURCE range at file start.
            JvmBackend.JvmCodegenResult emptyRes = jvmEntryGenerate("",
                "ps_e6004_empty.deal");
            CompilerDiagnostic emptyE6004 = firstCode(emptyRes.diagnostics(), "E6004");
            check(emptyE6004 != null,
                "PS6: empty program produces E6004: " + emptyRes.diagnostics());
            if (emptyE6004 != null) {
                DiagnosticRange r = emptyE6004.range();
                check(r.origin() == RangeOrigin.SOURCE
                        && r.startLine() == 1 && r.startColumn() == 1
                        && r.endLine() == 1 && r.endColumn() == 1
                        && r.startScalarOffset() == 0
                        && r.endScalarOffset() == 0
                        && r.scalarLength() == 0,
                    "PS6: empty-program E6004 pins (file,1,1,1,1,0,0,0,SOURCE): " + r);
                check(emptyE6004.notes().isEmpty(),
                    "PS6: empty-program E6004 carries no anchor note: "
                        + emptyE6004.notes());
            }
        } finally {
            try {
                Files.walk(tmp).sorted(Comparator.reverseOrder()).forEach(f -> {
                    try {
                        Files.deleteIfExists(f);
                    } catch (IOException ignored) {
                    }
                });
            } catch (IOException ignored) {
            }
        }
    }

    /** JVM backend entry gate over the full frontend pipeline. */
    private static JvmBackend.JvmCodegenResult jvmEntryGenerate(String source,
                                                                String filename) {
        LexResult lex = new Lexer(source, filename).tokenize();
        ParseResult parse = new Parser(lex.tokens(), filename).parse();
        StubModuleResolver resolver = new StubModuleResolver();
        NameResolver nr = new NameResolver(filename, resolver);
        SymbolTable symTable = nr.resolve(parse.program());
        CheckResult result = TypeChecker.check(filename, symTable, nr,
            parse.program());
        return JvmBackend.generate(parse.program(), result, filename, "main",
            Map.of(), Map.of(), Map.of(), true);
    }

    /** First diagnostic with the given code, or null. */
    private static CompilerDiagnostic firstCode(List<CompilerDiagnostic> diags,
                                                String code) {
        for (CompilerDiagnostic d : diags) {
            if (d.code().equals(code)) {
                return d;
            }
        }
        return null;
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
    // =========================================================================
    // JsonRangeLexer section
    // =========================================================================

    /** Asserts every field of an exact half-open scalar range. */
    private static void checkRange(String label, SourceScalarRange r,
                                   int sl, int sc, int el, int ec, int so, int eo) {
        boolean ok = r.startLine() == sl && r.startColumn() == sc
            && r.endLine() == el && r.endColumn() == ec
            && r.startScalarOffset() == so && r.endScalarOffset() == eo;
        check(ok, label + ": " + r + " != ("
            + sl + "," + sc + "," + el + "," + ec + "," + so + "," + eo + ")");
        check(r.scalarLength() == eo - so,
            label + ": scalarLength() " + r.scalarLength() + " != " + (eo - so));
    }

    /** Cross-checks a lexer range against an independent cursor recomputation. */
    private static void checkRangeAgainstCursor(String label, SourceScalarRange r,
                                                String source) {
        ScalarSourceCursor start = new ScalarSourceCursor(source);
        for (int i = 0; i < r.startScalarOffset(); i++) {
            start.advance();
        }
        check(start.line() == r.startLine() && start.column() == r.startColumn()
                && start.scalarOffset() == r.startScalarOffset(),
            label + ": start of " + r + " disagrees with cursor start " + start.position());
        ScalarSourceCursor end = new ScalarSourceCursor(source);
        for (int i = 0; i < r.endScalarOffset(); i++) {
            end.advance();
        }
        check(end.line() == r.endLine() && end.column() == r.endColumn()
                && end.scalarOffset() == r.endScalarOffset(),
            label + ": end of " + r + " disagrees with cursor end " + end.position());
    }

    private static void testJsonRangeLexerTokenAndMemberRanges() {
        System.out.println("-- JsonRangeLexer: token/member ranges under Unicode, CRLF, and tabs --");

        // Fixture: astral scalars in key and value, tab after CRLF, nested
        // object and array, CRLF before the closing brace.
        String src = "{\r\n\t\"k\uD83D\uDE00y\" : {\"n\" : [1, \"v\uD83D\uDE00\"]}\r\n}";
        JsonRangeLexer.JsonRangeLexResult r = JsonRangeLexer.lex(src);
        check(r.faults().isEmpty(), "unicode fixture: unexpected faults " + r.faults());
        List<JsonRangeLexer.JsonRangeToken> ts = r.orderedTokens();
        check(ts.size() == 13, "unicode fixture: token count " + ts.size() + " != 13");

        String[] kinds = {"OBJECT_START", "KEY", "COLON", "OBJECT_START", "KEY", "COLON",
            "ARRAY_START", "NUMBER", "COMMA", "STRING", "ARRAY_END", "OBJECT_END",
            "OBJECT_END"};
        for (int i = 0; i < kinds.length; i++) {
            check(ts.get(i).kind().name().equals(kinds[i]),
                "unicode token " + i + " kind " + ts.get(i).kind() + " != " + kinds[i]);
        }
        checkRange("OBJECT_START", ts.get(0).range(), 1, 1, 1, 2, 0, 1);
        checkRange("KEY", ts.get(1).range(), 2, 2, 2, 7, 4, 9);
        check(("k\uD83D\uDE00y").equals(ts.get(1).decodedValue()),
            "unicode key decoded value: " + ts.get(1).decodedValue());
        checkRange("COLON", ts.get(2).range(), 2, 8, 2, 9, 10, 11);
        checkRange("nested OBJECT_START", ts.get(3).range(), 2, 10, 2, 11, 12, 13);
        checkRange("nested KEY", ts.get(4).range(), 2, 11, 2, 14, 13, 16);
        check("n".equals(ts.get(4).decodedValue()), "nested key decoded value");
        checkRange("nested COLON", ts.get(5).range(), 2, 15, 2, 16, 17, 18);
        checkRange("ARRAY_START", ts.get(6).range(), 2, 17, 2, 18, 19, 20);
        checkRange("NUMBER", ts.get(7).range(), 2, 18, 2, 19, 20, 21);
        check(Long.valueOf(1L).equals(ts.get(7).decodedValue()),
            "NUMBER decoded value " + ts.get(7).decodedValue() + " != 1L");
        checkRange("COMMA", ts.get(8).range(), 2, 19, 2, 20, 21, 22);
        checkRange("STRING", ts.get(9).range(), 2, 21, 2, 25, 23, 27);
        check(("v\uD83D\uDE00").equals(ts.get(9).decodedValue()),
            "STRING decoded value: " + ts.get(9).decodedValue());
        checkRange("ARRAY_END", ts.get(10).range(), 2, 25, 2, 26, 27, 28);
        checkRange("nested OBJECT_END", ts.get(11).range(), 2, 26, 2, 27, 28, 29);
        checkRange("root OBJECT_END", ts.get(12).range(), 3, 1, 3, 2, 31, 32);

        // Members in value-completion order: the nested member completes first.
        List<JsonRangeLexer.JsonMemberRange> ms = r.members();
        check(ms.size() == 2, "unicode fixture: member count " + ms.size() + " != 2");
        JsonRangeLexer.JsonMemberRange inner = ms.get(0);
        check(inner.path().equals(List.of("k\uD83D\uDE00y", "n")),
            "inner member path " + inner.path());
        check("n".equals(inner.keyText()), "inner member keyText");
        checkRange("inner keyRange", inner.keyRange(), 2, 11, 2, 14, 13, 16);
        checkRange("inner valueRange", inner.valueRange(), 2, 17, 2, 26, 19, 28);
        checkRange("inner memberRange", inner.memberRange(), 2, 11, 2, 26, 13, 28);
        JsonRangeLexer.JsonMemberRange outer = ms.get(1);
        check(outer.path().equals(List.of("k\uD83D\uDE00y")),
            "outer member path " + outer.path());
        check(("k\uD83D\uDE00y").equals(outer.keyText()), "outer member keyText");
        checkRange("outer keyRange", outer.keyRange(), 2, 2, 2, 7, 4, 9);
        checkRange("outer valueRange", outer.valueRange(), 2, 10, 2, 27, 12, 29);
        checkRange("outer memberRange", outer.memberRange(), 2, 2, 2, 27, 4, 29);

        // Every asserted range is recomputed independently from the source.
        for (int i = 0; i < ts.size(); i++) {
            checkRangeAgainstCursor("unicode token " + i, ts.get(i).range(), src);
        }
        checkRangeAgainstCursor("inner key", inner.keyRange(), src);
        checkRangeAgainstCursor("inner value", inner.valueRange(), src);
        checkRangeAgainstCursor("inner member", inner.memberRange(), src);
        checkRangeAgainstCursor("outer key", outer.keyRange(), src);
        checkRangeAgainstCursor("outer value", outer.valueRange(), src);
        checkRangeAgainstCursor("outer member", outer.memberRange(), src);

        // Null source is treated as empty: no tokens, no members, no faults.
        JsonRangeLexer.JsonRangeLexResult empty = JsonRangeLexer.lex(null);
        check(empty.orderedTokens().isEmpty() && empty.members().isEmpty()
                && empty.faults().isEmpty(), "null source must scan as empty");
        JsonRangeLexer.JsonRangeLexResult blank = JsonRangeLexer.lex("");
        check(blank.orderedTokens().isEmpty() && blank.members().isEmpty()
                && blank.faults().isEmpty(), "empty source must scan as empty");
    }

    private static void testJsonRangeLexerFaults() {
        System.out.println("-- JsonRangeLexer: structural faults carry ranges, never throw --");

        // UNEXPECTED_CHARACTER at member start (NBSP is outside the pinned
        // whitespace set): today's expect('"') throw position.
        String s1 = "{\u00A0\"a\": 1}";
        JsonRangeLexer.JsonRangeLexResult r1 = JsonRangeLexer.lex(s1);
        check(r1.faults().size() == 1,
            "NBSP member start: fault count " + r1.faults().size() + " != 1");
        check(r1.faults().get(0).kind() == JsonRangeLexer.JsonFaultKind.UNEXPECTED_CHARACTER,
            "NBSP member start: kind " + r1.faults().get(0).kind());
        checkRange("NBSP member start fault", r1.faults().get(0).range(), 1, 2, 1, 3, 1, 2);
        checkRange("NBSP member start OBJECT_START", r1.orderedTokens().get(0).range(),
            1, 1, 1, 2, 0, 1);
        check(r1.members().isEmpty(), "NBSP member start: no members");

        // UNEXPECTED_CHARACTER at member start after a completed member.
        String s2 = "{\"a\": 1, \u00A0\"b\": 2}";
        JsonRangeLexer.JsonRangeLexResult r2 = JsonRangeLexer.lex(s2);
        check(r2.faults().size() == 1, "member-start NBSP: fault count " + r2.faults().size());
        check(r2.faults().get(0).kind() == JsonRangeLexer.JsonFaultKind.UNEXPECTED_CHARACTER,
            "member-start NBSP: kind " + r2.faults().get(0).kind());
        checkRange("member-start NBSP fault", r2.faults().get(0).range(), 1, 10, 1, 11, 9, 10);
        check(r2.members().size() == 1 && "a".equals(r2.members().get(0).keyText()),
            "member-start NBSP: member a completed before the fault");
        checkRange("member a keyRange", r2.members().get(0).keyRange(), 1, 2, 1, 5, 1, 4);
        checkRange("member a valueRange", r2.members().get(0).valueRange(), 1, 7, 1, 8, 6, 7);
        checkRange("member a memberRange", r2.members().get(0).memberRange(), 1, 2, 1, 8, 1, 7);

        // UNEXPECTED_CHARACTER between key and colon (today's expect(':') throw).
        String s3 = "{\"a\"\u00A0: 1}";
        JsonRangeLexer.JsonRangeLexResult r3 = JsonRangeLexer.lex(s3);
        check(r3.faults().size() == 1, "key-colon NBSP: fault count " + r3.faults().size());
        check(r3.faults().get(0).kind() == JsonRangeLexer.JsonFaultKind.UNEXPECTED_CHARACTER,
            "key-colon NBSP: kind " + r3.faults().get(0).kind());
        checkRange("key-colon NBSP fault", r3.faults().get(0).range(), 1, 5, 1, 6, 4, 5);

        // EXPECTED_KEY at member-start end of input ('{' at EOF).
        JsonRangeLexer.JsonRangeLexResult r4 = JsonRangeLexer.lex("{");
        check(r4.faults().size() == 1
                && r4.faults().get(0).kind() == JsonRangeLexer.JsonFaultKind.EXPECTED_KEY,
            "'{' at EOF: " + r4.faults());
        checkRange("'{' at EOF fault", r4.faults().get(0).range(), 1, 2, 1, 2, 1, 1);

        // EXPECTED_KEY at member-start end of input after a comma.
        JsonRangeLexer.JsonRangeLexResult r5 = JsonRangeLexer.lex("{\"a\": 1,");
        check(r5.faults().size() == 1
                && r5.faults().get(0).kind() == JsonRangeLexer.JsonFaultKind.EXPECTED_KEY,
            "member-start EOF: " + r5.faults());
        checkRange("member-start EOF fault", r5.faults().get(0).range(), 1, 9, 1, 9, 8, 8);
        check(r5.members().size() == 1, "member-start EOF: member a completed before the fault");

        // EXPECTED_KEY at a non-KEY token (NUMBER) at member start.
        JsonRangeLexer.JsonRangeLexResult r6 = JsonRangeLexer.lex("{5: 1}");
        check(r6.faults().size() == 1
                && r6.faults().get(0).kind() == JsonRangeLexer.JsonFaultKind.EXPECTED_KEY,
            "non-KEY member start: " + r6.faults());
        checkRange("non-KEY member start fault", r6.faults().get(0).range(), 1, 2, 1, 3, 1, 2);

        // EXPECTED_COLON at end of input.
        JsonRangeLexer.JsonRangeLexResult r7 = JsonRangeLexer.lex("{\"a\"");
        check(r7.faults().size() == 1
                && r7.faults().get(0).kind() == JsonRangeLexer.JsonFaultKind.EXPECTED_COLON,
            "missing colon EOF: " + r7.faults());
        checkRange("missing colon EOF fault", r7.faults().get(0).range(), 1, 5, 1, 5, 4, 4);

        // EXPECTED_COLON at a non-COLON token between key and colon.
        JsonRangeLexer.JsonRangeLexResult r8 = JsonRangeLexer.lex("{\"a\" 5: 1}");
        check(r8.faults().size() == 1
                && r8.faults().get(0).kind() == JsonRangeLexer.JsonFaultKind.EXPECTED_COLON,
            "non-COLON between key and colon: " + r8.faults());
        checkRange("non-COLON fault", r8.faults().get(0).range(), 1, 6, 1, 7, 5, 6);

        // EXPECTED_VALUE at a non-value token in value position (COMMA).
        JsonRangeLexer.JsonRangeLexResult r9 = JsonRangeLexer.lex("{\"a\": ,}");
        check(r9.faults().size() == 1
                && r9.faults().get(0).kind() == JsonRangeLexer.JsonFaultKind.EXPECTED_VALUE,
            "invalid value position: " + r9.faults());
        checkRange("invalid value fault", r9.faults().get(0).range(), 1, 7, 1, 8, 6, 7);

        // EXPECTED_VALUE at an undecodable number (Long and Double both fail).
        JsonRangeLexer.JsonRangeLexResult r10 = JsonRangeLexer.lex("{\"a\": -}");
        check(r10.faults().size() == 1
                && r10.faults().get(0).kind() == JsonRangeLexer.JsonFaultKind.EXPECTED_VALUE,
            "undecodable number: " + r10.faults());
        checkRange("undecodable number fault", r10.faults().get(0).range(), 1, 7, 1, 8, 6, 7);
        JsonRangeLexer.JsonRangeToken numToken = null;
        for (JsonRangeLexer.JsonRangeToken t : r10.orderedTokens()) {
            if (t.kind() == JsonRangeLexer.JsonTokenKind.NUMBER) {
                numToken = t;
            }
        }
        check(numToken != null && numToken.decodedValue() == null,
            "undecodable number token carries null decoded value");

        // EXPECTED_COMMA_OR_END at the missing comma (tolerated truncation).
        JsonRangeLexer.JsonRangeLexResult r11 = JsonRangeLexer.lex("{\"a\":1 \"b\":2}");
        check(r11.faults().size() == 1
                && r11.faults().get(0).kind()
                    == JsonRangeLexer.JsonFaultKind.EXPECTED_COMMA_OR_END,
            "missing comma: " + r11.faults());
        checkRange("missing comma fault", r11.faults().get(0).range(), 1, 8, 1, 8, 7, 7);
        check(r11.members().size() == 1 && "a".equals(r11.members().get(0).keyText()),
            "missing comma: only member a");

        // UNEXPECTED_CHARACTER after a completed member value (truncation cascade).
        String s12 = "{\"backend\":\"luajit\"\u00A0\"languageVersion\":\"1.0\"}";
        JsonRangeLexer.JsonRangeLexResult r12 = JsonRangeLexer.lex(s12);
        check(r12.faults().size() == 1
                && r12.faults().get(0).kind() == JsonRangeLexer.JsonFaultKind.UNEXPECTED_CHARACTER,
            "after-value NBSP: " + r12.faults());
        checkRange("after-value NBSP fault", r12.faults().get(0).range(), 1, 20, 1, 21, 19, 20);
        check(r12.members().size() == 1
                && "backend".equals(r12.members().get(0).keyText()),
            "after-value NBSP: backend-only truncation");

        // Marker-crossing cascade (review cycle 2 finding): the scalar
        // after the innermost break is the ENCLOSING container's close
        // marker. Today's parser breaks only the innermost container
        // without consuming the scalar; the enclosing container then
        // consumes the marker as its own close and scanning continues
        // above it — post-break members are still scanned.
        String s12b = "{\"k\": [{\"b\":1,\"c\":2], \"backend\": \"wasm\"}";
        JsonRangeLexer.JsonRangeLexResult r12b = JsonRangeLexer.lex(s12b);
        check(r12b.faults().size() == 1
                && r12b.faults().get(0).kind()
                    == JsonRangeLexer.JsonFaultKind.EXPECTED_COMMA_OR_END,
            "marker crossing: " + r12b.faults());
        checkRange("marker crossing fault", r12b.faults().get(0).range(),
            1, 20, 1, 20, 19, 19);
        check(r12b.orderedTokens().size() == 18,
            "marker crossing token count " + r12b.orderedTokens().size());
        check(r12b.orderedTokens().get(12).kind()
                == JsonRangeLexer.JsonTokenKind.ARRAY_END,
            "enclosing array consumes the close marker");
        check(r12b.orderedTokens().get(14).kind() == JsonRangeLexer.JsonTokenKind.KEY
                && "backend".equals(r12b.orderedTokens().get(14).decodedValue()),
            "post-break member key scanned");
        check(r12b.orderedTokens().get(17).kind() == JsonRangeLexer.JsonTokenKind.OBJECT_END,
            "root object closes after the post-break member");
        check(r12b.members().size() == 4,
            "marker crossing members " + r12b.members().size());
        check("backend".equals(r12b.members().get(3).keyText()),
            "post-break member paired");

        // Multi-level cascade: three object breaks on the same array close
        // marker; the array consumes it and the root keeps parsing.
        String s12c = "{\"k\": [{\"a\": {\"b\": {\"c\": 1], \"backend\": \"luajit\"}";
        JsonRangeLexer.JsonRangeLexResult r12c = JsonRangeLexer.lex(s12c);
        check(r12c.faults().size() == 3,
            "multi-level cascade faults " + r12c.faults().size());
        check(r12c.faults().get(0).kind() == JsonRangeLexer.JsonFaultKind.EXPECTED_COMMA_OR_END
                && r12c.faults().get(1).kind()
                    == JsonRangeLexer.JsonFaultKind.EXPECTED_COMMA_OR_END
                && r12c.faults().get(2).kind()
                    == JsonRangeLexer.JsonFaultKind.EXPECTED_COMMA_OR_END,
            "multi-level cascade fault kinds " + r12c.faults());
        checkRange("multi-level cascade fault", r12c.faults().get(0).range(),
            1, 27, 1, 27, 26, 26);
        check(r12c.orderedTokens().get(14).kind()
                == JsonRangeLexer.JsonTokenKind.ARRAY_END,
            "array consumes the marker after three object breaks");
        check(r12c.members().size() == 5
                && "backend".equals(r12c.members().get(4).keyText()),
            "post-break member paired after the multi-level cascade");

        // EXPECTED_VALUE at end of input (tolerated null value) plus
        // EXPECTED_END for the unclosed object.
        JsonRangeLexer.JsonRangeLexResult r13 = JsonRangeLexer.lex("{\"a\":");
        check(r13.faults().size() == 2
                && r13.faults().get(0).kind() == JsonRangeLexer.JsonFaultKind.EXPECTED_VALUE
                && r13.faults().get(1).kind() == JsonRangeLexer.JsonFaultKind.EXPECTED_END,
            "value at EOF: " + r13.faults());
        checkRange("value at EOF fault", r13.faults().get(0).range(), 1, 6, 1, 6, 5, 5);
        checkRange("unclosed object fault", r13.faults().get(1).range(), 1, 6, 1, 6, 5, 5);
        check(r13.members().size() == 1, "value at EOF: null-value member recorded");
        checkRange("null-value member valueRange", r13.members().get(0).valueRange(),
            1, 6, 1, 6, 5, 5);
        checkRange("null-value member memberRange", r13.members().get(0).memberRange(),
            1, 2, 1, 6, 1, 5);

        // EXPECTED_VALUE + EXPECTED_END for '[' at end of input.
        JsonRangeLexer.JsonRangeLexResult r14 = JsonRangeLexer.lex("[");
        check(r14.faults().size() == 2
                && r14.faults().get(0).kind() == JsonRangeLexer.JsonFaultKind.EXPECTED_VALUE
                && r14.faults().get(1).kind() == JsonRangeLexer.JsonFaultKind.EXPECTED_END,
            "'[' at EOF: " + r14.faults());
        checkRange("'[' at EOF EXPECTED_VALUE", r14.faults().get(0).range(), 1, 2, 1, 2, 1, 1);

        // EXPECTED_END for an unclosed object after a completed member.
        JsonRangeLexer.JsonRangeLexResult r15 = JsonRangeLexer.lex("{\"a\":1");
        check(r15.faults().size() == 1
                && r15.faults().get(0).kind() == JsonRangeLexer.JsonFaultKind.EXPECTED_END,
            "unclosed object: " + r15.faults());
        checkRange("unclosed object fault", r15.faults().get(0).range(), 1, 7, 1, 7, 6, 6);

        // EXPECTED_END for an unterminated string plus the unclosed object.
        JsonRangeLexer.JsonRangeLexResult r16 = JsonRangeLexer.lex("{\"a\": \"xy");
        check(r16.faults().size() == 2
                && r16.faults().get(0).kind() == JsonRangeLexer.JsonFaultKind.EXPECTED_END
                && r16.faults().get(1).kind() == JsonRangeLexer.JsonFaultKind.EXPECTED_END,
            "unterminated string: " + r16.faults());
        checkRange("unterminated string fault", r16.faults().get(0).range(), 1, 10, 1, 10, 9, 9);
        check("xy".equals(r16.orderedTokens().get(3).decodedValue()),
            "unterminated string keeps partial decoded value");

        // INVALID_ESCAPE: unknown escape drops the backslash, keeps the scalar.
        JsonRangeLexer.JsonRangeLexResult r17 = JsonRangeLexer.lex("{\"a\": \"\\q\"}");
        check(r17.faults().size() == 1
                && r17.faults().get(0).kind() == JsonRangeLexer.JsonFaultKind.INVALID_ESCAPE,
            "unknown escape: " + r17.faults());
        checkRange("unknown escape fault", r17.faults().get(0).range(), 1, 8, 1, 10, 7, 9);
        check("q".equals(r17.orderedTokens().get(3).decodedValue()),
            "unknown escape decodes as q");

        // UNPAIRED_SURROGATE passes through with a data fault.
        JsonRangeLexer.JsonRangeLexResult r18 = JsonRangeLexer.lex("{\"a\": \"\uD800\"}");
        check(r18.faults().size() == 1
                && r18.faults().get(0).kind() == JsonRangeLexer.JsonFaultKind.UNPAIRED_SURROGATE,
            "unpaired surrogate: " + r18.faults());
        checkRange("unpaired surrogate fault", r18.faults().get(0).range(), 1, 8, 1, 9, 7, 8);
        check("\uD800".equals(r18.orderedTokens().get(3).decodedValue()),
            "unpaired surrogate passes through into the decoded value");

        // TRAILING_CONTENT after the completed root value.
        JsonRangeLexer.JsonRangeLexResult r19 = JsonRangeLexer.lex("{} 5");
        check(r19.faults().size() == 1
                && r19.faults().get(0).kind() == JsonRangeLexer.JsonFaultKind.TRAILING_CONTENT,
            "trailing content: " + r19.faults());
        checkRange("trailing content fault", r19.faults().get(0).range(), 1, 4, 1, 4, 3, 3);

        // UNEXPECTED_CHARACTER as trailing content (untokenizable scalar).
        JsonRangeLexer.JsonRangeLexResult r20 = JsonRangeLexer.lex("{} x");
        check(r20.faults().size() == 1
                && r20.faults().get(0).kind() == JsonRangeLexer.JsonFaultKind.UNEXPECTED_CHARACTER,
            "trailing untokenizable: " + r20.faults());
        checkRange("trailing untokenizable fault", r20.faults().get(0).range(), 1, 4, 1, 5, 3, 4);

        // NON_STRICT_WHITESPACE for U+2028 between key and colon, still skipped.
        JsonRangeLexer.JsonRangeLexResult r21 = JsonRangeLexer.lex("{\"a\"\u2028: 1}");
        check(r21.faults().size() == 1
                && r21.faults().get(0).kind()
                    == JsonRangeLexer.JsonFaultKind.NON_STRICT_WHITESPACE,
            "U+2028 whitespace: " + r21.faults());
        checkRange("U+2028 whitespace fault", r21.faults().get(0).range(), 1, 5, 1, 6, 4, 5);
        check(r21.members().size() == 1 && "a".equals(r21.members().get(0).keyText()),
            "U+2028 whitespace: member a still parsed");
        check(r21.orderedTokens().size() == 5,
            "U+2028 whitespace: 5 tokens " + r21.orderedTokens().size());
        checkRange("U+2028 COLON", r21.orderedTokens().get(2).range(), 1, 6, 1, 7, 5, 6);

        // NON_STRICT_WHITESPACE for the VT/FS run between members, still skipped.
        JsonRangeLexer.JsonRangeLexResult r22 =
            JsonRangeLexer.lex("{\"a\":1,\u000B\u001C\"b\":2}");
        check(r22.faults().size() == 1
                && r22.faults().get(0).kind()
                    == JsonRangeLexer.JsonFaultKind.NON_STRICT_WHITESPACE,
            "VT/FS whitespace: " + r22.faults());
        checkRange("VT/FS whitespace fault", r22.faults().get(0).range(), 1, 8, 1, 10, 7, 9);
        check(r22.members().size() == 2, "VT/FS whitespace: both members still parsed");
        check("b".equals(r22.members().get(1).keyText()), "VT/FS whitespace: member b present");

        // RAW_CONTROL_IN_STRING for the raw LF, kept in the decoded value.
        JsonRangeLexer.JsonRangeLexResult r23 = JsonRangeLexer.lex("{\"a\": \"x\ny\"}");
        check(r23.faults().size() == 1
                && r23.faults().get(0).kind()
                    == JsonRangeLexer.JsonFaultKind.RAW_CONTROL_IN_STRING,
            "raw control: " + r23.faults());
        checkRange("raw control fault", r23.faults().get(0).range(), 1, 9, 2, 1, 8, 9);
        check("x\ny".equals(r23.orderedTokens().get(3).decodedValue()),
            "raw control kept in decoded value: " + r23.orderedTokens().get(3).decodedValue());
        checkRange("raw control STRING range", r23.orderedTokens().get(3).range(),
            1, 7, 2, 3, 6, 11);
        checkRange("raw control member valueRange", r23.members().get(0).valueRange(),
            1, 7, 2, 3, 6, 11);

        // Every fault range recomputed independently from its source.
        checkRangeAgainstCursor("s1 fault", r1.faults().get(0).range(), s1);
        checkRangeAgainstCursor("s2 fault", r2.faults().get(0).range(), s2);
        checkRangeAgainstCursor("s3 fault", r3.faults().get(0).range(), s3);
        checkRangeAgainstCursor("s12 fault", r12.faults().get(0).range(), s12);
        checkRangeAgainstCursor("s23 fault", r23.faults().get(0).range(),
            "{\"a\": \"x\ny\"}");
        checkRangeAgainstCursor("u2028 fault", r21.faults().get(0).range(), "{\"a\"\u2028: 1}");
    }

    /**
     * Blind boolean/null windows under astral scalars (review cycle 3
     * finding): today's hand-rolled parser advanced its UTF-16 index by
     * exactly 4 or 5 code units for boolean/null values, so an astral
     * scalar inside the window counts as two units, and a window ending
     * between a surrogate pair's two units consumes only the high
     * surrogate — the lone low surrogate then scans as today's parser
     * sees it. Token ranges are derived in decoded scalars over the
     * actually consumed span.
     */
    private static void testJsonRangeLexerBlindWindows() {
        System.out.println(
            "-- JsonRangeLexer: blind boolean/null windows under astral scalars --");

        // 4-unit null window ends mid-pair: the consumed span is
        // n,u,l,HIGH (4 units = 4 scalars — the high surrogate is one
        // recovery scalar) and the lone low surrogate breaks the scan
        // (today's index arithmetic lands mid-pair; the following
        // members are never read).
        String s1 = "{\"a\": nul\uD83D\uDE00, \"x\": 1}";
        JsonRangeLexer.JsonRangeLexResult r1 = JsonRangeLexer.lex(s1);
        check(r1.orderedTokens().size() == 4,
            "mid-pair null window: token count " + r1.orderedTokens().size());
        check(r1.orderedTokens().get(3).kind() == JsonRangeLexer.JsonTokenKind.NULL,
            "mid-pair null window: NULL token");
        checkRange("mid-pair null token", r1.orderedTokens().get(3).range(),
            1, 7, 1, 11, 6, 10);
        check(r1.faults().size() == 1
                && r1.faults().get(0).kind()
                    == JsonRangeLexer.JsonFaultKind.UNEXPECTED_CHARACTER,
            "mid-pair null window: " + r1.faults());
        checkRange("mid-pair null fault", r1.faults().get(0).range(),
            1, 11, 1, 12, 10, 11);
        check(r1.faults().get(0).message().contains("U+DE00"),
            "mid-pair null window names the lone low surrogate: "
                + r1.faults().get(0).message());
        check(r1.members().size() == 1 && "a".equals(r1.members().get(0).keyText()),
            "mid-pair null window: only member a");
        checkRangeAgainstCursor("mid-pair null token",
            r1.orderedTokens().get(3).range(), s1);
        checkRangeAgainstCursor("mid-pair null fault",
            r1.faults().get(0).range(), s1);

        // Pair fully inside the 5-unit false window: the window spans
        // t,r,HIGH,LOW,r (5 units = 4 scalars) and ends exactly at the
        // comma — the post-window member is scanned and paired.
        String s2 = "{\"a\": tr\uD83D\uDE00r, \"x\": 1}";
        JsonRangeLexer.JsonRangeLexResult r2 = JsonRangeLexer.lex(s2);
        check(r2.orderedTokens().size() == 9,
            "in-window pair: token count " + r2.orderedTokens().size());
        check(r2.orderedTokens().get(3).kind() == JsonRangeLexer.JsonTokenKind.FALSE
                && Boolean.FALSE.equals(r2.orderedTokens().get(3).decodedValue()),
            "in-window pair: FALSE token");
        checkRange("in-window pair FALSE", r2.orderedTokens().get(3).range(),
            1, 7, 1, 11, 6, 10);
        check(r2.faults().isEmpty(), "in-window pair: no faults " + r2.faults());
        check(r2.members().size() == 2 && "x".equals(r2.members().get(1).keyText()),
            "in-window pair: post-window member x paired");
        checkRangeAgainstCursor("in-window pair FALSE",
            r2.orderedTokens().get(3).range(), s2);

        // The 4-unit true window is exact: the astral scalar after the
        // window is the out-of-alphabet scalar that triggers the
        // truncation cascade — the post-window member is never scanned.
        String s3 = "{\"a\": true\uD83D\uDE00, \"x\": 1}";
        JsonRangeLexer.JsonRangeLexResult r3 = JsonRangeLexer.lex(s3);
        check(r3.orderedTokens().size() == 4,
            "true window: token count " + r3.orderedTokens().size());
        check(Boolean.TRUE.equals(r3.orderedTokens().get(3).decodedValue()),
            "true window: TRUE token");
        checkRange("true window TRUE", r3.orderedTokens().get(3).range(),
            1, 7, 1, 11, 6, 10);
        check(r3.faults().size() == 1
                && r3.faults().get(0).kind()
                    == JsonRangeLexer.JsonFaultKind.UNEXPECTED_CHARACTER,
            "true window: " + r3.faults());
        checkRange("true window fault", r3.faults().get(0).range(),
            1, 11, 1, 12, 10, 11);
        check(r3.faults().get(0).message().contains("U+1F600"),
            "true window names the astral scalar: "
                + r3.faults().get(0).message());
        check(r3.members().size() == 1, "true window: only member a");

        // Astral digit after a number never continues the number scan
        // (today's char-based isDigit): the number decodes as 1 and the
        // out-of-alphabet astral digit triggers the truncation cascade.
        String s4 = "{\"a\": 1\uD835\uDFD8}";
        JsonRangeLexer.JsonRangeLexResult r4 = JsonRangeLexer.lex(s4);
        check(Long.valueOf(1L).equals(r4.orderedTokens().get(3).decodedValue()),
            "astral digit: NUMBER decodes to 1L: "
                + r4.orderedTokens().get(3).decodedValue());
        checkRange("astral digit NUMBER", r4.orderedTokens().get(3).range(),
            1, 7, 1, 8, 6, 7);
        check(r4.faults().size() == 1
                && r4.faults().get(0).kind()
                    == JsonRangeLexer.JsonFaultKind.UNEXPECTED_CHARACTER
                && r4.faults().get(0).message().contains("U+1D7D8"),
            "astral digit: " + r4.faults());
        checkRange("astral digit fault", r4.faults().get(0).range(),
            1, 8, 1, 9, 7, 8);
    }

    private static void testJsonRangeLexerTolerance() {
        System.out.println("-- JsonRangeLexer: today's tolerant acceptance surface --");

        // Permissive numbers decode via Long then Double.
        JsonRangeLexer.JsonRangeLexResult r =
            JsonRangeLexer.lex("{\"a\": [1., 01, 1e5, -.5, .5]}");
        check(r.faults().isEmpty(), "permissive numbers: unexpected faults " + r.faults());
        java.util.List<Object> nums = new java.util.ArrayList<>();
        for (JsonRangeLexer.JsonRangeToken t : r.orderedTokens()) {
            if (t.kind() == JsonRangeLexer.JsonTokenKind.NUMBER) {
                nums.add(t.decodedValue());
            }
        }
        check(nums.size() == 5, "permissive numbers: " + nums.size() + " NUMBER tokens");
        check(nums.get(0) instanceof Double d && d == 1.0, "1. decodes to 1.0: " + nums.get(0));
        check(nums.get(1) instanceof Long l && l == 1L, "01 decodes to 1L: " + nums.get(1));
        check(nums.get(2) instanceof Double d && d == 100000.0, "1e5 decodes: " + nums.get(2));
        check(nums.get(3) instanceof Double d && d == -0.5, "-.5 decodes: " + nums.get(3));
        check(nums.get(4) instanceof Double d && d == 0.5, ".5 decodes: " + nums.get(4));

        // Duplicate keys: both members recorded (last-wins is reader policy).
        JsonRangeLexer.JsonRangeLexResult dup = JsonRangeLexer.lex("{\"a\":1,\"a\":2}");
        check(dup.faults().isEmpty(), "duplicate keys: unexpected faults " + dup.faults());
        check(dup.members().size() == 2
                && dup.members().get(0).path().equals(List.of("a"))
                && dup.members().get(1).path().equals(List.of("a")),
            "duplicate keys: both members recorded");

        // Unclosed string value keeps today's partial decode.
        JsonRangeLexer.JsonRangeLexResult us = JsonRangeLexer.lex("{\"a\": \"xy");
        check(us.members().size() == 1, "unclosed string: member recorded");
        check("xy".equals(us.orderedTokens().get(3).decodedValue()),
            "unclosed string partial value");

        // Unclosed array at EOF keeps the consumed elements.
        JsonRangeLexer.JsonRangeLexResult ua = JsonRangeLexer.lex("[1,2");
        check(ua.faults().size() == 1
                && ua.faults().get(0).kind() == JsonRangeLexer.JsonFaultKind.EXPECTED_END,
            "unclosed array: " + ua.faults());
        checkRange("unclosed array fault", ua.faults().get(0).range(), 1, 5, 1, 5, 4, 4);
        check(ua.orderedTokens().size() == 4, "unclosed array keeps NUMBER 1, COMMA, NUMBER 2");

        // Boolean blind consumption: 't' alone consumes 5 code units
        // (clamped at end of input) and decodes to FALSE; the object then
        // completes partial at end of input.
        JsonRangeLexer.JsonRangeLexResult b = JsonRangeLexer.lex("{\"a\": t}");
        check(b.faults().size() == 1
                && b.faults().get(0).kind() == JsonRangeLexer.JsonFaultKind.EXPECTED_END,
            "'t' at value position: " + b.faults());
        check(Boolean.FALSE.equals(b.orderedTokens().get(3).decodedValue()),
            "'t' blind-decodes to FALSE: " + b.orderedTokens().get(3).decodedValue());
        check(b.members().size() == 1, "'t' value: member recorded with the blind FALSE");
    }

    private static void testJsonRangeLexerNeverThrows() {
        System.out.println("-- JsonRangeLexer: no input causes an exception --");

        String[] nasty = {
            "", "\u0000", "\uD800", "\uFFFF", "{\"a\": \"\\", "}}}}", "[[[",
            "\"\uD800\"", "\u0000{\u0000", "-\u00A0", "\u2028\u2029",
            "{\"a\": -e5}", "tru", "t", "\uD83D\uDE00", "{\",\",",
            "\"\uD83D\uDE00\"", "\"\uD800\uDC00\"", "\u001F\u001F\u001F",
            "{\"a\": nul\uD83D\uDE00", "{\"a\": tr\uD83D\uDE00r",
            "{\"a\": 1\uD835\uDFD8}",
        };
        for (String input : nasty) {
            try {
                JsonRangeLexer.JsonRangeLexResult r = JsonRangeLexer.lex(input);
                for (JsonRangeLexer.JsonRangeToken t : r.orderedTokens()) {
                    check(t.range() != null, "token range never null for input: " + input);
                }
                for (JsonRangeLexer.JsonLexFault f : r.faults()) {
                    check(f.range() != null, "fault range never null for input: " + input);
                }
                for (JsonRangeLexer.JsonMemberRange m : r.members()) {
                    check(m.keyRange() != null && m.valueRange() != null
                            && m.memberRange() != null,
                        "member ranges never null for input: " + input);
                }
            } catch (Throwable t) {
                fail("lex threw for input " + input + ": " + t);
            }
        }
    }

    private static void testDealSourceJdkOnly() {
        System.out.println("-- deal/source has no deal.diagnostics reference (JDK-only) --");

        File dir = new File("deal/source");
        check(dir.isDirectory(), "deal/source directory missing");
        if (!dir.isDirectory()) {
            return;
        }
        File[] files = dir.listFiles((d, name) -> name.endsWith(".java"));
        check(files != null && files.length > 0, "no .java files under deal/source");
        if (files == null) {
            return;
        }
        for (File f : files) {
            String content;
            try {
                content = Files.readString(f.toPath());
            } catch (IOException e) {
                fail("cannot read " + f + ": " + e);
                continue;
            }
            check(!content.contains("deal.diagnostics"),
                f.getName() + " references deal.diagnostics");
            for (String line : content.split("\n")) {
                String t = line.trim();
                if (t.startsWith("import ") && !t.startsWith("import java.")) {
                    fail(f.getName() + ": non-JDK import: " + t);
                }
            }
        }
    }

}
