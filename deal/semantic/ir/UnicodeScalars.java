package deal.semantic.ir;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class UnicodeScalars {

    private UnicodeScalars() {
        // Static surface only; pure and stateless.
    }

    /** Match already validated scalar arrays; callers provide an in-range window. */
    public static boolean matchAt(int[] haystack, int[] needle, int offset) {
        for (int i = 0; i < needle.length; i++) {
            if (haystack[offset + i] != needle[i]) return false;
        }
        return true;
    }

    /** Literal scalar search; retains the empty-needle position contract. */
    public static int indexOfSubsequence(int[] haystack, int[] needle, int fromIndex) {
        if (needle.length == 0) return fromIndex <= haystack.length ? fromIndex : -1;
        for (int i = Math.max(0, fromIndex); i + needle.length <= haystack.length; i++) {
            if (matchAt(haystack, needle, i)) return i;
        }
        return -1;
    }

    // =========================================================================
    // ScalarString: the closed ADT
    // =========================================================================

    /**
     * The closed string classification: {@link Valid} (Java UTF-16 units,
     * including lone surrogates) or {@link Invalid} (classified actual kind
     * {@link ActualKind#INVALID_UNICODE}). No other shape exists.
     */
    public sealed interface ScalarString permits UnicodeScalars.Valid, UnicodeScalars.Invalid {

        /**
         * The canonical actual-kind token of this classification:
         * {@link ActualKind#STRING} for {@link Valid} and
         * {@link ActualKind#INVALID_UNICODE} for {@link Invalid}.
         */
        ActualKind actualKind();
    }

    /**
     * Admitted UTF-16 units. Construction preserves every unit, including
     * lone surrogates; byte/source transport validates separately.
     *
     */
    public record Valid(String carrier) implements ScalarString {

        public Valid {
            Objects.requireNonNull(carrier, "carrier must not be null");
        }

        @Override
        public ActualKind actualKind() {
            return ActualKind.STRING;
        }
    }

    /**
     * The single invalid classification. A carrier classified
     * {@code Invalid} renders the closed actual-kind token
     * {@code invalid-unicode} ({@link ActualKind#INVALID_UNICODE} — the
     * schema's closed token); the consuming op projects it through the
     * named {@code TYPE_DESCRIPTOR} failure, never through a
     * per-defect projection (D5: truncated, overlong, bad continuation,
     * surrogate, and above-U+10FFFF materializations all classify
     * {@code Invalid} the same way). The closed {@link Defect} class of
     * the first malformed sequence stays available beside the single
     * classification for the host string-carrier reason selection
     * ({@link #utf8Defect(byte[])}); it never changes this classification.
     * Ordinary Java strings admit every UTF-16 unit without an encoding check.
     */
    public enum Invalid implements ScalarString {

        /** The one invalid classification. */
        INSTANCE;

        @Override
        public ActualKind actualKind() {
            return ActualKind.INVALID_UNICODE;
        }
    }

    // =========================================================================
    // Validation
    // =========================================================================

    /**
     * Admits Java UTF-16 units without normalization. Malformed byte
     * transport uses the byte overload or an explicit invalid presentation.
     *
     */
    public static ScalarString validate(String carrier) {
        Objects.requireNonNull(carrier, "carrier must not be null");
        return new Valid(carrier);
    }

    /**
     * Decodes strict scalar UTF-8 transport, not the runtime's WTF-8 unit
     * representation. Truncated, overlong, bad-continuation, surrogate and
     * above-U+10FFFF byte sequences classify {@link Invalid}. Only a fully
     * validated sequence reaches the JDK decoder, without replacement.
     *
     */
    public static ScalarString validate(byte[] utf8) {
        return utf8Defect(utf8) == null
            ? new Valid(new String(utf8, StandardCharsets.UTF_8))
            : Invalid.INSTANCE;
    }

    /**
     * The two closed invalid-encoding defect classes of the pinned host
     * string-carrier reasons (the {@code HOST_STRING_INVALID_UTF8} /
     * {@code HOST_STRING_SURROGATE} inner-only arms): a surrogate code
     * point encoding is the surrogate class, every other malformed
     * sequence the malformed class. The class refines the single
     * {@link Invalid} classification; it never replaces it.
     */
    public enum Defect {

        /** A surrogate code point encoding ({@code ED A0..BF ...}). */
        SURROGATE,

        /** Every other malformed sequence (truncated, overlong, bad continuation,
         *  above U+10FFFF). */
        MALFORMED
    }

    /**
     * The defect class of the <em>first</em> malformed sequence of a raw
     * UTF-8 byte sequence, or {@code null} when the complete sequence is
     * scalar-valid. The surrogate class belongs to this strict transport
     * channel only: runtime UTF-16 units are admitted separately. The walk
     * stops at the first defect.
     */
    public static Defect utf8Defect(byte[] utf8) {
        Objects.requireNonNull(utf8, "utf8 must not be null");
        int n = utf8.length;
        int i = 0;
        while (i < n) {
            int b1 = utf8[i] & 0xFF;
            int len;
            if (b1 < 0x80) {
                len = 1;
            } else if (b1 >= 0xC2 && b1 <= 0xDF) {
                len = 2;
            } else if (b1 >= 0xE0 && b1 <= 0xEF) {
                len = 3;
            } else if (b1 >= 0xF0 && b1 <= 0xF4) {
                len = 4;
            } else {
                // A stray 80..BF continuation lead, a C0/C1 overlong
                // 2-byte lead, or an F5..FF above-U+10FFFF lead.
                return Defect.MALFORMED;
            }
            if (i + len > n) {
                return Defect.MALFORMED; // truncated
            }
            if (len >= 2) {
                int b2 = utf8[i + 1] & 0xFF;
                if (b2 < 0x80 || b2 > 0xBF) {
                    return Defect.MALFORMED; // bad continuation
                }
                if (len == 3) {
                    int b3 = utf8[i + 2] & 0xFF;
                    if (b3 < 0x80 || b3 > 0xBF) {
                        return Defect.MALFORMED; // bad continuation
                    }
                    if (b1 == 0xE0 && b2 < 0xA0) {
                        return Defect.MALFORMED; // overlong
                    }
                    if (b1 == 0xED && b2 > 0x9F) {
                        return Defect.SURROGATE; // U+D800..U+DFFF
                    }
                } else if (len == 4) {
                    int b3 = utf8[i + 2] & 0xFF;
                    int b4 = utf8[i + 3] & 0xFF;
                    if (b3 < 0x80 || b3 > 0xBF || b4 < 0x80 || b4 > 0xBF) {
                        return Defect.MALFORMED; // bad continuation
                    }
                    if (b1 == 0xF0 && b2 < 0x90) {
                        return Defect.MALFORMED; // overlong
                    }
                    if (b1 == 0xF4 && b2 > 0x8F) {
                        return Defect.MALFORMED; // above U+10FFFF
                    }
                }
            }
            i += len;
        }
        return null;
    }

    // =========================================================================
    // Scalar iteration
    // =========================================================================

    /**
     * Iterates the complete unit sequence, combining valid pairs into code
     * points and yielding lone units individually; empty text yields none.
     *
     */
    public static List<Integer> scalars(Valid valid) {
        Objects.requireNonNull(valid, "valid must not be null");
        String carrier = valid.carrier();
        ArrayList<Integer> codePoints = new ArrayList<>();
        int i = 0;
        while (i < carrier.length()) {
            int codePoint = carrier.codePointAt(i);
            codePoints.add(codePoint);
            i += Character.charCount(codePoint);
        }
        return List.copyOf(codePoints);
    }

    /**
     * Materializes one iteration item: a BMP unit (including a surrogate)
     * or the UTF-16 pair of a supplementary code point.
     *
     */
    public static String scalarString(int codePoint) {
        if (codePoint < 0 || codePoint > 0x10FFFF) {
            throw new IllegalArgumentException(
                "outside Unicode code point range: " + codePoint);
        }
        return new String(Character.toChars(codePoint));
    }

    // =========================================================================
    // Concatenation
    // =========================================================================

    /**
     * Concatenates UTF-16 units in source order without repair or
     * normalization. An explicit {@link Invalid} fragment propagates for
     * the consuming op's {@code TYPE_DESCRIPTOR} failure; no fragments
     * produce empty text.
     *
     */
    public static ScalarString concat(List<? extends ScalarString> fragments) {
        Objects.requireNonNull(fragments, "fragments must not be null");
        StringBuilder carrier = new StringBuilder();
        for (ScalarString fragment : fragments) {
            Objects.requireNonNull(fragment, "fragments must not contain a null element");
            if (fragment instanceof Invalid) {
                return Invalid.INSTANCE;
            }
            carrier.append(((Valid) fragment).carrier());
        }
        return new Valid(carrier.toString());
    }
}
