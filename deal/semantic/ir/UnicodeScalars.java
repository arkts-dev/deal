package deal.semantic.ir;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The closed Unicode scalar model (ISSUE-0232 design D5): the single
 * owner of string classification, scalar iteration, and scalar-sequence
 * concatenation over the Java UTF-16 carrier, consumed by the
 * {@code STRING_CONCAT} and {@code FOR_EACH(STRING_SCALARS)} execution
 * contracts of the parent operation table.
 *
 * <pre>{@code
 * ScalarString = Valid(carrier: String)   // scalar sequence; no lone surrogate units
 *              | Invalid                   // classified actual kind invalid-unicode
 * }</pre>
 *
 * <p><b>Validation.</b> {@link #validate(String)} decodes the complete
 * UTF-16 sequence: a lone UTF-16 surrogate unit (high or low) makes the
 * carrier {@link Invalid}; otherwise {@link Valid}.
 * {@link #validate(byte[])} applies the same classification to the raw
 * UTF-8 byte sequences materialized at host boundaries — the pinned
 * defect-class set of the retained runtime
 * ({@code deal/runtime.lua:164-206}): <em>truncated</em> (a multi-byte
 * sequence extends past the end), <em>overlong</em>
 * ({@code E0} with {@code b2 < A0}, {@code F0} with {@code b2 < 90}, or a
 * {@code C0}/{@code C1} lead), <em>bad continuation</em> (a continuation
 * position outside {@code 80..BF}, stray {@code 80..BF} lead bytes
 * included), <em>surrogate</em> ({@code ED} with {@code b2 > 9F} —
 * U+D800..U+DFFF), and <em>above U+10FFFF</em> ({@code F4} with
 * {@code b2 > 8F}, or an {@code F5..FF} lead). {@link Invalid} is the
 * single invalid classification — never a per-defect projection. A valid
 * byte sequence decodes to its carrier exactly once, strictly; the JDK's
 * lenient replacement-characters decoder is never reached by malformed
 * input (fail-closed).</p>
 *
 * <p><b>Iteration.</b> {@link #scalars(Valid)} yields the code points in
 * order — a surrogate pair is one code point; {@link #scalarString(int)}
 * returns the one-single-scalar string for a code point.</p>
 *
 * <p><b>Concatenation.</b> {@link #concat(List)} concatenates scalar
 * sequences in source order, representation-agnostic (byte-level
 * UTF-8/UTF-16 layout is never observable). An {@link Invalid} fragment
 * propagates {@link Invalid} — exposed for the consuming op's named
 * {@code TYPE_DESCRIPTOR} E8001
 * {@code expected string, got invalid Unicode scalar encoding} projection
 * ({@code docs/spec-v1.2.md:64} pins that boundary strings reject invalid
 * encodings, including malformed UTF-8 and unpaired UTF-16 surrogates) —
 * never silently repaired: no replacement characters, no truncation.</p>
 *
 * <p>The component is pure, static, deterministic, and linear in string
 * length; it executes no host code and carries no
 * {@code deal.types} dependency ({@code deal.semantic.ir} is
 * schema-adjacent), and no target representation leaks into it.</p>
 */
public final class UnicodeScalars {

    private UnicodeScalars() {
        // Static surface only; pure and stateless.
    }

    // =========================================================================
    // ScalarString: the closed ADT
    // =========================================================================

    /**
     * The closed string classification: {@link Valid} (a Unicode scalar
     * sequence carried by a Java UTF-16 string with no lone surrogate
     * units) or {@link Invalid} (classified actual kind
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
     * A valid scalar sequence. The carrier is a Java UTF-16 string with
     * no lone surrogate units (a surrogate pair is exactly one scalar);
     * construction fails closed for a carrier that violates the
     * invariant, so a {@code Valid} value always denotes a valid scalar
     * sequence.
     *
     * @param carrier the UTF-16 carrier string; must not be null and must
     *                not contain a lone surrogate unit
     * @throws NullPointerException     if {@code carrier} is null
     * @throws IllegalArgumentException if {@code carrier} contains a lone
     *                                  high or low surrogate unit
     */
    public record Valid(String carrier) implements ScalarString {

        public Valid {
            Objects.requireNonNull(carrier, "carrier must not be null");
            int offset = UnicodeScalars.loneSurrogateOffset(carrier);
            if (offset >= 0) {
                throw new IllegalArgumentException(
                    "carrier contains a lone UTF-16 surrogate unit at offset " + offset
                        + "; Valid carries a Unicode scalar sequence");
            }
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
     * {@code Invalid} the same way).
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
     * Classifies a UTF-16 carrier: the complete sequence decodes to
     * {@link Valid} when it contains no lone surrogate unit, and to
     * {@link Invalid} when it contains a lone high surrogate (a high
     * surrogate not followed by a low surrogate, including a high
     * surrogate at the end) or a lone low surrogate (a low surrogate not
     * preceded by a high surrogate). A valid surrogate pair is one
     * scalar and passes unchanged.
     *
     * @param carrier the UTF-16 carrier string
     * @return {@link Valid} for a scalar-sequence carrier,
     *         {@link Invalid#INSTANCE} otherwise
     * @throws NullPointerException if {@code carrier} is null
     */
    public static ScalarString validate(String carrier) {
        Objects.requireNonNull(carrier, "carrier must not be null");
        return loneSurrogateOffset(carrier) >= 0 ? Invalid.INSTANCE : new Valid(carrier);
    }

    /**
     * Classifies a raw UTF-8 byte sequence as materialized at a host
     * boundary (D5): the complete sequence is validated against the
     * pinned defect-class set of {@code deal/runtime.lua:164-206} —
     * truncated, overlong, bad continuation, surrogate, and above
     * U+10FFFF — and any defect classifies {@link Invalid}, the single
     * invalid classification (never a per-defect projection). Only a
     * fully validated sequence decodes to its {@link Valid} carrier
     * (strictly, exactly once — the JDK's lenient replacement-characters
     * decoder is never reached by malformed input), so no normalization
     * ever occurs in the component.
     *
     * @param utf8 the raw UTF-8 byte sequence
     * @return {@link Valid} carrying the decoded carrier for a
     *         well-formed scalar-sequence encoding,
     *         {@link Invalid#INSTANCE} for any pinned defect class
     * @throws NullPointerException if {@code utf8} is null
     */
    public static ScalarString validate(byte[] utf8) {
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
                return Invalid.INSTANCE;
            }
            if (i + len > n) {
                return Invalid.INSTANCE; // truncated
            }
            if (len >= 2) {
                int b2 = utf8[i + 1] & 0xFF;
                if (b2 < 0x80 || b2 > 0xBF) {
                    return Invalid.INSTANCE; // bad continuation
                }
                if (len == 3) {
                    int b3 = utf8[i + 2] & 0xFF;
                    if (b3 < 0x80 || b3 > 0xBF) {
                        return Invalid.INSTANCE; // bad continuation
                    }
                    if (b1 == 0xE0 && b2 < 0xA0) {
                        return Invalid.INSTANCE; // overlong
                    }
                    if (b1 == 0xED && b2 > 0x9F) {
                        return Invalid.INSTANCE; // surrogate U+D800..U+DFFF
                    }
                } else if (len == 4) {
                    int b3 = utf8[i + 2] & 0xFF;
                    int b4 = utf8[i + 3] & 0xFF;
                    if (b3 < 0x80 || b3 > 0xBF || b4 < 0x80 || b4 > 0xBF) {
                        return Invalid.INSTANCE; // bad continuation
                    }
                    if (b1 == 0xF0 && b2 < 0x90) {
                        return Invalid.INSTANCE; // overlong
                    }
                    if (b1 == 0xF4 && b2 > 0x8F) {
                        return Invalid.INSTANCE; // above U+10FFFF
                    }
                }
            }
            i += len;
        }
        return new Valid(new String(utf8, StandardCharsets.UTF_8));
    }

    /**
     * Returns the offset of the first lone UTF-16 surrogate unit, or
     * {@code -1} when the carrier is a scalar sequence: a high surrogate
     * must be followed by a low surrogate (a pair is one scalar) and a
     * low surrogate must be preceded by a high surrogate.
     */
    static int loneSurrogateOffset(String carrier) {
        for (int i = 0; i < carrier.length(); i++) {
            char c = carrier.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= carrier.length() || !Character.isLowSurrogate(carrier.charAt(i + 1))) {
                    return i;
                }
                i++;
            } else if (Character.isLowSurrogate(c)) {
                return i;
            }
        }
        return -1;
    }

    // =========================================================================
    // Scalar iteration
    // =========================================================================

    /**
     * Yields the code points of a valid scalar sequence in order: a
     * surrogate pair is one code point, and the iteration walks the
     * complete sequence (an empty carrier yields zero code points).
     *
     * @param valid the valid scalar sequence
     * @return the code points in scalar order
     * @throws NullPointerException if {@code valid} is null
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
     * Returns the one-single-scalar string for a code point: one UTF-16
     * {@code char} for a BMP scalar and one surrogate pair for a
     * supplementary scalar. The result is always exactly one Unicode
     * scalar value.
     *
     * @param codePoint a Unicode scalar value (0..0x10FFFF, never a
     *                  surrogate code point)
     * @return the one-single-scalar string
     * @throws IllegalArgumentException if {@code codePoint} is not a
     *                                  Unicode scalar value (outside
     *                                  0..0x10FFFF or a surrogate code
     *                                  point) — a producer defect, never
     *                                  a repaired value
     */
    public static String scalarString(int codePoint) {
        if (codePoint < 0 || codePoint > 0x10FFFF
                || (codePoint >= 0xD800 && codePoint <= 0xDFFF)) {
            throw new IllegalArgumentException(
                "not a Unicode scalar value: " + codePoint);
        }
        return new String(Character.toChars(codePoint));
    }

    // =========================================================================
    // Concatenation
    // =========================================================================

    /**
     * Concatenates scalar sequences in source order
     * (representation-agnostic: byte-level UTF-8/UTF-16 layout is never
     * observable). An {@link Invalid} fragment propagates
     * {@link Invalid} — exposed for the consuming op's named
     * {@code TYPE_DESCRIPTOR} failure, never normalized, truncated, or
     * repaired (fail-closed); an empty fragment list concatenates to the
     * empty scalar sequence.
     *
     * @param fragments the scalar sequences in source order; no element
     *                  may be null
     * @return {@link Valid} carrying the source-order concatenation when
     *         every fragment is valid, {@link Invalid#INSTANCE}
     *         otherwise
     * @throws NullPointerException if {@code fragments} is null or
     *                              contains a null element
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
