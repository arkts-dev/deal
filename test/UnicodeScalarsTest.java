package deal.test;

import deal.semantic.ir.ActualKind;
import deal.semantic.ir.UnicodeScalars;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Verifies the ISSUE-0232 D5 Unicode scalar model
 * ({@link UnicodeScalars}): the closed {@code ScalarString} ADT
 * ({@code Valid(carrier) | Invalid}), the complete-sequence validation of
 * UTF-16 carriers (a lone surrogate unit classifies {@code Invalid}) and
 * of the raw UTF-8 byte sequences materialized at host boundaries (each
 * of the five pinned defect classes — truncated, overlong, bad
 * continuation, surrogate, above U+10FFFF — classifies {@code Invalid}
 * the same way, never a per-defect projection), scalar iteration (a
 * surrogate pair is one code point; one single-scalar string per code
 * point in source order), scalar-sequence concatenation in source order,
 * the fail-closed rule (no normalization of invalid strings — no
 * replacement characters, no truncation — {@code Invalid} is exposed for
 * the consuming op's named {@code TYPE_DESCRIPTOR} projection), and the
 * {@code Invalid → invalid-unicode} actual-kind rendering against the
 * closed {@link ActualKind} token.
 *
 * <p>Pinned cases (containers-and-strings-lowering Verification 2/3):
 * <ol>
 *   <li>A valid multi-byte code point (U+1F600, one surrogate pair)
 *       decodes as exactly one scalar.</li>
 *   <li>Lone high and lone low surrogate carriers classify
 *       {@code Invalid} (high at end, high followed by a non-low unit,
 *       lone low before a pair included).</li>
 *   <li>Each of the five pinned UTF-8 defect classes classifies
 *       {@code Invalid}: truncated, overlong, bad continuation,
 *       surrogate, above U+10FFFF (plus the lead-byte shapes the
 *       retained validator rejects: stray continuation, C0/C1 overlong
 *       lead, F5..FF above-U+10FFFF lead).</li>
 *   <li>Valid UTF-8 bytes decode strictly to their carrier (U+1F600,
 *       multi-scalar, empty).</li>
 *   <li>Iteration yields one single-scalar string per code point in
 *       source order; an empty string yields zero scalars; U+10FFFF is
 *       one scalar.</li>
 *   <li>Concatenation of multi-scalar fragments matches the source-order
 *       scalar sequence; an {@code Invalid} fragment propagates
 *       {@code Invalid} (fail-closed exposure, never a repair).</li>
 *   <li>The {@code Invalid → invalid-unicode} actual-kind rendering is
 *       asserted against {@link ActualKind#INVALID_UNICODE}.</li>
 *   <li>Defect guards: a {@code Valid} carrier with a lone surrogate, a
 *       non-scalar {@code scalarString} argument, and null arguments
 *       fail closed; repeats are byte-identical.</li>
 * </ol>
 */
public class UnicodeScalarsTest {

    private static int passed = 0;
    private static int failed = 0;

    private static void check(boolean condition, String message) {
        if (condition) { passed++; }
        else { failed++; System.err.println("FAIL: " + message); }
    }

    private static void fail(String message) {
        failed++;
        System.err.println("FAIL: " + message);
    }

    private static void expectIllegalArgument(Runnable runnable, String what) {
        try {
            runnable.run();
            fail("expected IllegalArgumentException for " + what + ", but no exception was raised");
        } catch (IllegalArgumentException expected) {
            passed++;
        } catch (Throwable other) {
            fail("expected IllegalArgumentException for " + what + ", got "
                + other.getClass().getSimpleName() + ": " + other.getMessage());
        }
    }

    private static void expectNpe(Runnable runnable, String what) {
        try {
            runnable.run();
            fail("expected NullPointerException for " + what + ", but no exception was raised");
        } catch (NullPointerException expected) {
            passed++;
        } catch (Throwable other) {
            fail("expected NullPointerException for " + what + ", got "
                + other.getClass().getSimpleName() + ": " + other.getMessage());
        }
    }

    /** Byte-array literal helper (values above 0x7F need no casts). */
    private static byte[] bytes(int... values) {
        byte[] result = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            result[i] = (byte) values[i];
        }
        return result;
    }

    private static UnicodeScalars.Valid valid(String carrier) {
        return (UnicodeScalars.Valid) UnicodeScalars.validate(carrier);
    }

    // =========================================================================
    // 1. The ADT and the invalid-unicode actual-kind rendering
    // =========================================================================

    static void testAdtAndActualKindRendering() {
        System.out.println("-- ScalarString ADT and the invalid-unicode actual-kind rendering --");

        check(UnicodeScalars.Invalid.INSTANCE.actualKind() == ActualKind.INVALID_UNICODE,
            "Invalid renders the closed ActualKind.INVALID_UNICODE token kind");
        check("invalid-unicode".equals(ActualKind.INVALID_UNICODE.token()),
            "the INVALID_UNICODE token text is the pinned invalid-unicode");
        check("invalid-unicode".equals(
                ActualKind.canonicalToken(ActualKind.INVALID_UNICODE, null)),
            "canonicalToken renders invalid-unicode without a class id");
        check(valid("x").actualKind() == ActualKind.STRING,
            "Valid renders ActualKind.STRING");

        // Invalid is the single invalid classification: the same instance
        // for every defect shape, never a per-defect projection.
        UnicodeScalars.ScalarString loneHigh = UnicodeScalars.validate("\uD800");
        UnicodeScalars.ScalarString truncated = UnicodeScalars.validate(bytes(0xE2, 0x28));
        check(loneHigh == UnicodeScalars.Invalid.INSTANCE
                && truncated == UnicodeScalars.Invalid.INSTANCE
                && loneHigh == truncated,
            "Invalid is the single invalid classification (one instance, no per-defect shape)");
    }

    // =========================================================================
    // 2. UTF-16 carrier validation: U+1F600 is one scalar; lone surrogates fail
    // =========================================================================

    static void testUtf16CarrierValidation() {
        System.out.println("-- UTF-16 carrier validation --");

        // The pinned case: U+1F600 is one surrogate pair and one scalar.
        UnicodeScalars.ScalarString smiley = UnicodeScalars.validate("\uD83D\uDE00");
        check(smiley instanceof UnicodeScalars.Valid, "U+1F600 carrier is Valid");
        check("\uD83D\uDE00".equals(((UnicodeScalars.Valid) smiley).carrier()),
            "a valid carrier passes unchanged");
        check(List.of(0x1F600).equals(UnicodeScalars.scalars((UnicodeScalars.Valid) smiley)),
            "U+1F600 (one surrogate pair) decodes as exactly one scalar");

        // Lone high surrogates.
        check(UnicodeScalars.validate("\uD800") == UnicodeScalars.Invalid.INSTANCE,
            "a lone high surrogate classifies Invalid");
        check(UnicodeScalars.validate("ab\uD800") == UnicodeScalars.Invalid.INSTANCE,
            "a high surrogate at the end classifies Invalid");
        check(UnicodeScalars.validate("\uD800X") == UnicodeScalars.Invalid.INSTANCE,
            "a high surrogate followed by a non-low unit classifies Invalid");
        check(UnicodeScalars.validate("\uD800\uD800\uDC00") == UnicodeScalars.Invalid.INSTANCE,
            "a high surrogate followed by another high surrogate classifies Invalid");

        // Lone low surrogates.
        check(UnicodeScalars.validate("\uDC00") == UnicodeScalars.Invalid.INSTANCE,
            "a lone low surrogate classifies Invalid");
        check(UnicodeScalars.validate("\uDC00\uDE00") == UnicodeScalars.Invalid.INSTANCE,
            "a lone low surrogate before a valid pair classifies Invalid");
        check(UnicodeScalars.validate("a\uDC00b") == UnicodeScalars.Invalid.INSTANCE,
            "a low surrogate surrounded by BMP scalars classifies Invalid");

        // Valid carriers stay Valid: pairs, BMP, empty, and U+10FFFF.
        check(UnicodeScalars.validate("\uD83D\uDE00\uD83D\uDE01")
                instanceof UnicodeScalars.Valid,
            "two adjacent surrogate pairs are two scalars and stay Valid");
        check(UnicodeScalars.validate("a\uD83D\uDE00b") instanceof UnicodeScalars.Valid,
            "a BMP-pair-BMP carrier is Valid");
        check(UnicodeScalars.validate("\uDBFF\uDFFF") instanceof UnicodeScalars.Valid,
            "U+10FFFF (one surrogate pair) is Valid");
        check(UnicodeScalars.validate("") instanceof UnicodeScalars.Valid,
            "the empty carrier is Valid");
    }

    // =========================================================================
    // 3. UTF-8 byte validation: the five pinned defect classes + strict decode
    // =========================================================================

    static void testUtf8DefectClasses() {
        System.out.println("-- UTF-8 byte validation: the five pinned defect classes --");

        // Truncated: a 3-byte lead with only two bytes present.
        check(UnicodeScalars.validate(bytes(0xE2, 0x82)) == UnicodeScalars.Invalid.INSTANCE,
            "truncated (E2 82 without the third byte) classifies Invalid");
        check(UnicodeScalars.validate(bytes(0xF0, 0x9F, 0x98)) == UnicodeScalars.Invalid.INSTANCE,
            "truncated (F0 9F 98 without the fourth byte) classifies Invalid");

        // Overlong: E0 with b2 < A0, F0 with b2 < 90, and the C0/C1 lead.
        check(UnicodeScalars.validate(bytes(0xE0, 0x80, 0x80)) == UnicodeScalars.Invalid.INSTANCE,
            "overlong (E0 80 80, an overlong NUL) classifies Invalid");
        check(UnicodeScalars.validate(bytes(0xF0, 0x80, 0x80, 0x80))
                == UnicodeScalars.Invalid.INSTANCE,
            "overlong (F0 80 80 80) classifies Invalid");
        check(UnicodeScalars.validate(bytes(0xC0, 0x80)) == UnicodeScalars.Invalid.INSTANCE,
            "the C0 overlong 2-byte lead classifies Invalid");
        check(UnicodeScalars.validate(bytes(0xC1, 0xBF)) == UnicodeScalars.Invalid.INSTANCE,
            "the C1 overlong 2-byte lead classifies Invalid");

        // Bad continuation: a continuation position outside 80..BF.
        check(UnicodeScalars.validate(bytes(0xE2, 0x28, 0xA1)) == UnicodeScalars.Invalid.INSTANCE,
            "bad continuation (E2 28 A1, second byte not a continuation) classifies Invalid");
        check(UnicodeScalars.validate(bytes(0xC2, 0x41)) == UnicodeScalars.Invalid.INSTANCE,
            "bad continuation (C2 41) classifies Invalid");
        check(UnicodeScalars.validate(bytes(0xF0, 0x9F, 0x98, 0x00))
                == UnicodeScalars.Invalid.INSTANCE,
            "bad continuation (F0 9F 98 00, fourth byte not a continuation) classifies Invalid");
        check(UnicodeScalars.validate(bytes(0x80)) == UnicodeScalars.Invalid.INSTANCE,
            "a stray continuation lead byte classifies Invalid");

        // Surrogate: ED with b2 > 9F (U+D800..U+DFFF).
        check(UnicodeScalars.validate(bytes(0xED, 0xA0, 0x80)) == UnicodeScalars.Invalid.INSTANCE,
            "surrogate (ED A0 80, U+D800) classifies Invalid");
        check(UnicodeScalars.validate(bytes(0xED, 0xBF, 0xBF)) == UnicodeScalars.Invalid.INSTANCE,
            "surrogate (ED BF BF, U+DFFF) classifies Invalid");

        // Above U+10FFFF: F4 with b2 > 8F and the F5..FF lead.
        check(UnicodeScalars.validate(bytes(0xF4, 0x90, 0x80, 0x80))
                == UnicodeScalars.Invalid.INSTANCE,
            "above U+10FFFF (F4 90 80 80) classifies Invalid");
        check(UnicodeScalars.validate(bytes(0xF5, 0x80, 0x80, 0x80))
                == UnicodeScalars.Invalid.INSTANCE,
            "the F5 above-U+10FFFF lead classifies Invalid");
        check(UnicodeScalars.validate(bytes(0xFF)) == UnicodeScalars.Invalid.INSTANCE,
            "the FF above-U+10FFFF lead classifies Invalid");

        // Every defect renders the single invalid classification: no
        // per-defect projection and no repair exists anywhere.
        check(UnicodeScalars.validate(bytes(0xE2, 0x82)).actualKind()
                == ActualKind.INVALID_UNICODE,
            "a truncated sequence renders the invalid-unicode actual kind");
        check(UnicodeScalars.validate(bytes(0xE0, 0x80, 0x80)).actualKind()
                == ActualKind.INVALID_UNICODE,
            "an overlong sequence renders the invalid-unicode actual kind");
        check(UnicodeScalars.validate(bytes(0xE2, 0x28, 0xA1)).actualKind()
                == ActualKind.INVALID_UNICODE,
            "a bad-continuation sequence renders the invalid-unicode actual kind");
        check(UnicodeScalars.validate(bytes(0xED, 0xA0, 0x80)).actualKind()
                == ActualKind.INVALID_UNICODE,
            "a surrogate sequence renders the invalid-unicode actual kind");
        check(UnicodeScalars.validate(bytes(0xF4, 0x90, 0x80, 0x80)).actualKind()
                == ActualKind.INVALID_UNICODE,
            "an above-U+10FFFF sequence renders the invalid-unicode actual kind");
    }

    static void testUtf8StrictDecode() {
        System.out.println("-- UTF-8 strict decode of valid sequences --");

        UnicodeScalars.ScalarString smiley =
            UnicodeScalars.validate(bytes(0xF0, 0x9F, 0x98, 0x80));
        check(smiley instanceof UnicodeScalars.Valid, "the U+1F600 UTF-8 bytes are Valid");
        check("\uD83D\uDE00".equals(((UnicodeScalars.Valid) smiley).carrier()),
            "the U+1F600 UTF-8 bytes decode exactly to the surrogate-pair carrier");

        UnicodeScalars.ScalarString multi = UnicodeScalars.validate(
            "a\u00E9\uD83D\uDE00b".getBytes(StandardCharsets.UTF_8));
        check(multi instanceof UnicodeScalars.Valid, "a multi-scalar UTF-8 sequence is Valid");
        check("a\u00E9\uD83D\uDE00b".equals(((UnicodeScalars.Valid) multi).carrier()),
            "the multi-scalar UTF-8 sequence decodes exactly to its carrier");
        check(List.of(0x61, 0xE9, 0x1F600, 0x62).equals(
                UnicodeScalars.scalars((UnicodeScalars.Valid) multi)),
            "the decoded carrier carries exactly the encoded scalar sequence");

        check(UnicodeScalars.validate("abc".getBytes(StandardCharsets.UTF_8))
                instanceof UnicodeScalars.Valid,
            "plain ASCII bytes are Valid");
        UnicodeScalars.ScalarString empty = UnicodeScalars.validate(new byte[0]);
        check(empty instanceof UnicodeScalars.Valid
                && "".equals(((UnicodeScalars.Valid) empty).carrier()),
            "the empty byte sequence decodes to the empty Valid carrier");
    }

    // =========================================================================
    // 4. Iteration: one single-scalar string per code point in source order
    // =========================================================================

    static void testIteration() {
        System.out.println("-- scalar iteration: one single-scalar string per code point --");

        UnicodeScalars.Valid multi = valid("a\uD83D\uDE00b");
        check(List.of(0x61, 0x1F600, 0x62).equals(UnicodeScalars.scalars(multi)),
            "a-pair-b yields code points [U+0061, U+1F600, U+0062] in order");
        check(List.of("a", "\uD83D\uDE00", "b").equals(scalarStrings(multi)),
            "iteration yields one single-scalar string per code point in source order");

        UnicodeScalars.Valid max = valid("\uDBFF\uDFFF");
        check(List.of(0x10FFFF).equals(UnicodeScalars.scalars(max)),
            "U+10FFFF is exactly one scalar");
        check("\uDBFF\uDFFF".equals(UnicodeScalars.scalarString(0x10FFFF)),
            "scalarString(0x10FFFF) is the one-single-scalar string");

        check(List.of(0x41, 0xE9, 0x1F600).equals(
                UnicodeScalars.scalars(valid("A\u00E9\uD83D\uDE00"))),
            "A-e-acute-pair yields [U+0041, U+00E9, U+1F600] in order");

        UnicodeScalars.Valid empty = valid("");
        check(UnicodeScalars.scalars(empty).isEmpty(),
            "an empty string yields zero scalars");
    }

    static void testScalarString() {
        System.out.println("-- scalarString: the one-single-scalar string --");

        check("\uD83D\uDE00".equals(UnicodeScalars.scalarString(0x1F600)),
            "scalarString(0x1F600) is the surrogate-pair carrier of one scalar");
        check("A".equals(UnicodeScalars.scalarString(0x41)),
            "scalarString(0x41) is one BMP scalar");

        // Every scalarString result is exactly one valid scalar.
        for (int cp : new int[] {0x00, 0x41, 0xE9, 0x1F600, 0x10FFFF}) {
            String s = UnicodeScalars.scalarString(cp);
            UnicodeScalars.ScalarString classified = UnicodeScalars.validate(s);
            check(classified instanceof UnicodeScalars.Valid
                    && List.of(cp).equals(UnicodeScalars.scalars((UnicodeScalars.Valid) classified)),
                "scalarString(0x" + Integer.toHexString(cp)
                    + ") is a Valid string carrying exactly that one scalar");
        }

        // Non-scalar code points fail closed (a producer defect, never a repair).
        expectIllegalArgument(() -> UnicodeScalars.scalarString(0xD800),
            "scalarString on a high surrogate code point");
        expectIllegalArgument(() -> UnicodeScalars.scalarString(0xDFFF),
            "scalarString on a low surrogate code point");
        expectIllegalArgument(() -> UnicodeScalars.scalarString(-1),
            "scalarString on a negative code point");
        expectIllegalArgument(() -> UnicodeScalars.scalarString(0x110000),
            "scalarString above U+10FFFF");
    }

    // =========================================================================
    // 5. Concatenation in source order; fail-closed Invalid propagation
    // =========================================================================

    static void testConcat() {
        System.out.println("-- concatenation: source-order scalar sequences --");

        UnicodeScalars.ScalarString joined = UnicodeScalars.concat(List.of(
            valid("a\uD83D\uDE00"), valid("\u00E9b")));
        check(joined instanceof UnicodeScalars.Valid, "two valid fragments concat to Valid");
        check("a\uD83D\uDE00\u00E9b".equals(((UnicodeScalars.Valid) joined).carrier()),
            "the carriers concat in source order");
        check(List.of(0x61, 0x1F600, 0xE9, 0x62).equals(
                UnicodeScalars.scalars((UnicodeScalars.Valid) joined)),
            "the concatenated scalar sequence matches the source-order scalar sequence");

        UnicodeScalars.ScalarString reversed = UnicodeScalars.concat(List.of(
            valid("\u00E9b"), valid("a\uD83D\uDE00")));
        check("\u00E9ba\uD83D\uDE00".equals(((UnicodeScalars.Valid) reversed).carrier()),
            "fragment order is the payload order (reversed fragments concat reversed)");

        check("abc".equals(((UnicodeScalars.Valid) UnicodeScalars.concat(List.of(
                valid("a"), valid("b"), valid("c")))).carrier()),
            "single-scalar fragments concat in order");
        check("".equals(((UnicodeScalars.Valid) UnicodeScalars.concat(List.of())).carrier()),
            "an empty fragment list concatenates to the empty scalar sequence");
        check("a\uD83D\uDE00".equals(((UnicodeScalars.Valid) UnicodeScalars.concat(List.of(
                valid("a\uD83D\uDE00")))).carrier()),
            "a single fragment concatenates to itself");

        // Fail-closed: an Invalid fragment propagates Invalid — exposed,
        // never normalized, truncated, or replaced (no U+FFFD anywhere).
        UnicodeScalars.ScalarString withInvalid = UnicodeScalars.concat(List.of(
            valid("x"), UnicodeScalars.Invalid.INSTANCE, valid("y")));
        check(withInvalid == UnicodeScalars.Invalid.INSTANCE,
            "an Invalid fragment propagates the single Invalid classification");
        check(UnicodeScalars.concat(List.of(UnicodeScalars.Invalid.INSTANCE))
                == UnicodeScalars.Invalid.INSTANCE,
            "a lone Invalid fragment concatenates to Invalid");
        check(UnicodeScalars.concat(List.of(UnicodeScalars.validate("\uD800")))
                == UnicodeScalars.Invalid.INSTANCE,
            "a lone-surrogate fragment concatenates to Invalid (never repaired)");
        check(UnicodeScalars.concat(List.of(UnicodeScalars.validate(bytes(0xE2, 0x82))))
                == UnicodeScalars.Invalid.INSTANCE,
            "a truncated-UTF-8 fragment concatenates to Invalid (never repaired)");
    }

    // =========================================================================
    // 6. Fail-closed guards and determinism
    // =========================================================================

    static void testFailClosedGuards() {
        System.out.println("-- fail-closed guards --");

        expectIllegalArgument(() -> new UnicodeScalars.Valid("\uD800"),
            "a Valid constructed from a lone high surrogate");
        expectIllegalArgument(() -> new UnicodeScalars.Valid("\uDC00"),
            "a Valid constructed from a lone low surrogate");
        expectIllegalArgument(() -> new UnicodeScalars.Valid("ab\uD800"),
            "a Valid constructed from a trailing high surrogate");
        UnicodeScalars.Valid direct = new UnicodeScalars.Valid("a\uD83D\uDE00");
        check("a\uD83D\uDE00".equals(direct.carrier()),
            "a directly constructed Valid pair carrier is accepted");

        expectNpe(() -> UnicodeScalars.validate((String) null), "validate(String null)");
        expectNpe(() -> UnicodeScalars.validate((byte[]) null), "validate(byte[] null)");
        expectNpe(() -> new UnicodeScalars.Valid(null), "new Valid(null)");
        expectNpe(() -> UnicodeScalars.scalars(null), "scalars(null)");
        expectNpe(() -> UnicodeScalars.concat(null), "concat(null)");
        expectNpe(() -> UnicodeScalars.concat(List.of(valid("a"), null)),
            "concat with a null fragment");
    }

    static void testDeterminism() {
        System.out.println("-- determinism: byte-identical repeats --");

        List<UnicodeScalars.ScalarString> fragments = List.of(
            valid("a\uD83D\uDE00"), valid("\u00E9b"), valid("c\uD83D\uDE01"));
        UnicodeScalars.ScalarString first = UnicodeScalars.concat(fragments);
        for (int i = 0; i < 3; i++) {
            UnicodeScalars.ScalarString repeat = UnicodeScalars.concat(fragments);
            check(first.equals(repeat), "repeated concat is structurally identical");
            check(UnicodeScalars.scalars(valid("a\uD83D\uDE00\u00E9bc\uD83D\uDE01"))
                    .equals(UnicodeScalars.scalars(valid("a\uD83D\uDE00\u00E9bc\uD83D\uDE01"))),
                "repeated scalar iteration is identical");
            check(UnicodeScalars.validate("\uD83D\uDE00")
                    .equals(UnicodeScalars.validate("\uD83D\uDE00")),
                "repeated UTF-16 validation is identical");
            check(UnicodeScalars.validate(bytes(0xE2, 0x82))
                    == UnicodeScalars.validate(bytes(0xE2, 0x82)),
                "repeated UTF-8 validation returns the identical classification");
        }
    }

    // =========================================================================
    // Helpers and runner
    // =========================================================================

    /** Maps a valid scalar sequence to its one-single-scalar-string stream. */
    private static List<String> scalarStrings(UnicodeScalars.Valid valid) {
        ArrayList<String> strings = new ArrayList<>();
        for (int codePoint : UnicodeScalars.scalars(valid)) {
            strings.add(UnicodeScalars.scalarString(codePoint));
        }
        return strings;
    }

    public static void main(String[] args) {
        System.out.println("=== Unicode Scalars Test (ISSUE-0382, ISSUE-0232 D5) ===\n");

        testAdtAndActualKindRendering();
        testUtf16CarrierValidation();
        testUtf8DefectClasses();
        testUtf8StrictDecode();
        testIteration();
        testScalarString();
        testConcat();
        testFailClosedGuards();
        testDeterminism();

        System.out.println("\nPassed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }
}
