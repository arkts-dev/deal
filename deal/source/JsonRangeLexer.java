package deal.source;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A structural JSON scanner over a {@link ScalarSourceCursor} that yields
 * ordered tokens, object-member pairings, and structural defects — each
 * carrying a half-open decoded-Unicode-scalar {@link SourceScalarRange} —
 * and never throws for any input.
 *
 * <p>The token grammar mirrors the permissive hand-rolled manifest parser
 * (<code>DealConfig</code>'s {@code JsonParser}) exactly, so every input
 * today's parser tolerates is scanned without an error-class fault and
 * every input today's parser rejects produces a positional fault whose
 * range names the offending scalar or token:
 *
 * <ul>
 *   <li><b>Inter-token whitespace</b> is pinned to Java
 *       {@link Character#isWhitespace(int)}: SP/HT/LF/CR plus VT (U+000B),
 *       FF (U+000C), FS/GS/RS/US (U+001C–U+001F), and the Unicode space
 *       separators other than NBSP (U+00A0), figure space (U+2007), and
 *       narrow NBSP (U+202F) — e.g. U+1680, U+2000–U+2006, U+2008–U+200A,
 *       U+2028, U+2029, U+205F, U+3000. Scalars outside that set (NBSP,
 *       U+2007, U+202F, …) are never skipped.</li>
 *   <li>Every skipped whitespace run that contains at least one scalar
 *       outside strict-JSON whitespace (SP/HT/LF/CR) is recorded as a
 *       {@code NON_STRICT_WHITESPACE} data fault (never an error); the run
 *       is still skipped exactly as today.</li>
 *   <li><b>Strings</b> are scanned permissively: every scalar that is
 *       neither {@code "} nor {@code \} is consumed and kept in the decoded
 *       value, including raw control characters (recorded as
 *       {@code RAW_CONTROL_IN_STRING} data faults, never errors) and
 *       unpaired surrogates (recorded as {@code UNPAIRED_SURROGATE} data
 *       faults; the scalar is passed through into the decoded value exactly
 *       as today). The escape set is {@code " \ / n r t}; any other escaped
 *       scalar drops the backslash and keeps the scalar, recorded as an
 *       {@code INVALID_ESCAPE} data fault. An unterminated string yields
 *       the partial decoded value plus an {@code EXPECTED_END} fault.</li>
 *   <li><b>Numbers</b> span the same scan as today's {@code parseNumber}
 *       (optional {@code -}, digits, optional {@code .} plus digits,
 *       optional {@code e}/{@code E} plus optional sign plus digits) and
 *       decode {@code Long.parseLong} then {@code Double.parseDouble} over
 *       the consumed span; when both decodes fail the token carries a null
 *       decoded value and an {@code EXPECTED_VALUE} fault.</li>
 *   <li><b>Booleans</b> consume 4 scalars when the next four scalars spell
 *       {@code true} and 5 scalars otherwise (blind, clamped at end of
 *       input); <b>null</b> consumes 4 scalars blindly.</li>
 * </ul>
 *
 * <p>Structural positions record today's failure surface as positional
 * faults (each followed by a scan stop, mirroring today's first-thrown
 * exception):
 * <ul>
 *   <li>member start: a non-KEY token records {@code EXPECTED_KEY} at the
 *       token's range, an out-of-alphabet scalar records
 *       {@code UNEXPECTED_CHARACTER} at the scalar's range, and end of
 *       input records {@code EXPECTED_KEY} at a zero-length end-of-input
 *       range (today's {@code expect('"')} throw);</li>
 *   <li>between key and colon: a non-COLON token records
 *       {@code EXPECTED_COLON}, an out-of-alphabet scalar records
 *       {@code UNEXPECTED_CHARACTER}, end of input records
 *       {@code EXPECTED_COLON} at end of input (today's {@code expect(':')}
 *       throw);</li>
 *   <li>value position with input present: a token that cannot start a
 *       value records {@code EXPECTED_VALUE} at the token's range, an
 *       out-of-alphabet scalar records {@code UNEXPECTED_CHARACTER}, and an
 *       undecodable number records {@code EXPECTED_VALUE} at the number
 *       token's range (today's raw {@code NumberFormatException}); value
 *       position at end of input records {@code EXPECTED_VALUE} at a
 *       zero-length end-of-input range and completes the member with a
 *       null value and a zero-length value range (today's tolerated null
 *       value);</li>
 *   <li>after a completed member value: a scalar that is neither the
 *       container's close marker nor a comma records
 *       {@code EXPECTED_COMMA_OR_END} (token-start scalars) or
 *       {@code UNEXPECTED_CHARACTER} (out-of-alphabet scalars) and
 *       truncates only the innermost container without consuming the
 *       pending scalar (today's loop break); each enclosing container
 *       then re-checks the same pending scalar against its own close
 *       marker or comma — a container that consumes it closes and
 *       scanning continues in the container above it, while a container
 *       that does not match breaks in turn. When the cascade reaches
 *       the root container, the root is truncated and all remaining
 *       input is ignored (today's stop-every-enclosing-container
 *       truncation);</li>
 *   <li>end of input with unclosed objects/arrays/strings records one
 *       {@code EXPECTED_END} fault per unclosed construct at a zero-length
 *       end-of-input range, completing each container with its consumed
 *       partial range (today's tolerated partial structures);</li>
 *   <li>non-whitespace content after the completed root value records
 *       {@code TRAILING_CONTENT} (token-start scalars) or
 *       {@code UNEXPECTED_CHARACTER} (out-of-alphabet scalars) at the first
 *       trailing scalar (today's ignored trailing content).</li>
 * </ul>
 *
 * <p>{@link #lex(String)} never throws for any input — a {@code null}
 * source is treated as empty — and every defect becomes a fault carrying a
 * range. Data faults ({@code NON_STRICT_WHITESPACE},
 * {@code RAW_CONTROL_IN_STRING}, {@code INVALID_ESCAPE},
 * {@code UNPAIRED_SURROGATE}, {@code TRAILING_CONTENT},
 * {@code EXPECTED_END}, and the {@code EXPECTED_VALUE}-at-end-of-input
 * signature) do not stop the scan; positional hard-failure faults do, and
 * the after-value truncation stops only when the cascade reaches the root
 * container, mirroring today's parser exactly.
 *
 * <p>Object members are paired as {@link JsonMemberRange} records for every
 * member at every nesting depth, listed in value-completion order; the
 * member {@code path} lists the object keys from the root to the member
 * (array nesting levels contribute no path element). A member whose value
 * position reached end of input carries a zero-length {@code valueRange}
 * at the end-of-input position.
 */
public final class JsonRangeLexer {

    private JsonRangeLexer() {
    }

    // =========================================================================
    // Result shapes
    // =========================================================================

    /** Token kinds, mirroring today's permissive consumption exactly. */
    public enum JsonTokenKind {
        OBJECT_START, OBJECT_END, ARRAY_START, ARRAY_END,
        KEY, STRING, NUMBER, TRUE, FALSE, NULL, COLON, COMMA
    }

    /** Fault kinds: structural defects plus strict-JSON data faults. */
    public enum JsonFaultKind {
        UNEXPECTED_CHARACTER, EXPECTED_KEY, EXPECTED_COLON, EXPECTED_VALUE,
        EXPECTED_COMMA_OR_END, EXPECTED_END, INVALID_ESCAPE,
        UNPAIRED_SURROGATE, TRAILING_CONTENT, NON_STRICT_WHITESPACE,
        RAW_CONTROL_IN_STRING
    }

    /**
     * A scanned JSON token with its half-open scalar range.
     *
     * <p>{@code decodedValue}: the decoded string for KEY/STRING (today's
     * escape policy), the decoded {@code Long} or {@code Double} for NUMBER
     * (null when both decodes fail), {@link Boolean} for TRUE/FALSE,
     * and null for every other kind.
     *
     * @param kind         the token kind
     * @param range        the half-open decoded-scalar range of the token
     * @param decodedValue the decoded value, or null (see above)
     */
    public record JsonRangeToken(JsonTokenKind kind, SourceScalarRange range, Object decodedValue) {
    }

    /**
     * An object-member pairing recorded for every member at every nesting
     * depth, in value-completion order.
     *
     * @param path        object keys from the root to this member (array
     *                    levels contribute no element)
     * @param keyText     the decoded key string
     * @param keyRange    the key token's range
     * @param valueRange  the completed value's range; zero-length at the
     *                    end-of-input position for a null value at end of
     *                    input
     * @param memberRange key start through value end
     */
    public record JsonMemberRange(List<String> path, String keyText,
                                  SourceScalarRange keyRange, SourceScalarRange valueRange,
                                  SourceScalarRange memberRange) {
        public JsonMemberRange {
            path = List.copyOf(path);
        }
    }

    /**
     * A structural defect or strict-JSON deviation, never an exception.
     *
     * @param kind    the fault kind
     * @param range   the offending position: a one-scalar range for
     *                UNEXPECTED_CHARACTER, RAW_CONTROL_IN_STRING, and
     *                UNPAIRED_SURROGATE; the offending token's range for
     *                positional token faults; the whole skipped whitespace
     *                run for NON_STRICT_WHITESPACE; the backslash plus the
     *                escaped scalar for INVALID_ESCAPE; zero-length at the
     *                position for EXPECTED_COMMA_OR_END, TRAILING_CONTENT,
     *                and the end-of-input faults
     * @param message a human-readable description
     */
    public record JsonLexFault(JsonFaultKind kind, SourceScalarRange range, String message) {
    }

    /**
     * The complete scan result.
     *
     * @param orderedTokens every scanned token in scan order
     * @param members       every completed member at every nesting depth
     * @param faults        every defect in scan order
     */
    public record JsonRangeLexResult(List<JsonRangeToken> orderedTokens,
                                     List<JsonMemberRange> members,
                                     List<JsonLexFault> faults) {
        public JsonRangeLexResult {
            orderedTokens = List.copyOf(orderedTokens);
            members = List.copyOf(members);
            faults = List.copyOf(faults);
        }
    }

    // =========================================================================
    // Entry point
    // =========================================================================

    /**
     * Scans {@code json} into the token/member/fault result shape.
     * Never throws: a {@code null} source is treated as empty, and every
     * defect becomes a {@link JsonLexFault} carrying a range.
     */
    public static JsonRangeLexResult lex(String json) {
        return new Scan(json == null ? "" : json).run();
    }

    // =========================================================================
    // Scanner
    // =========================================================================

    /** Object container states. */
    private static final int OBJ_FIRST = 0;        // right after '{'
    private static final int OBJ_KEY = 1;          // member start after ','
    private static final int OBJ_COLON = 2;        // after the key token
    private static final int OBJ_VALUE = 3;        // after ':'
    private static final int OBJ_AFTER_VALUE = 4;  // after a completed value
    /** Array container states. */
    private static final int ARR_FIRST = 5;        // right after '['
    private static final int ARR_VALUE = 6;        // value expected after ','
    private static final int ARR_AFTER_VALUE = 7;  // after a completed value

    /** One open container during the scan. */
    private static final class Context {
        final boolean isObject;
        int state;
        /** Object-key chain from the root down to this container's members. */
        final List<String> pathPrefix;
        /** Pending member info (objects only). */
        String keyText;
        SourceScalarRange keyRange;
        /** The container's opening-token range (its value span start). */
        final SourceScalarRange containerStart;

        Context(boolean isObject, int state, List<String> pathPrefix,
                SourceScalarRange containerStart) {
            this.isObject = isObject;
            this.state = state;
            this.pathPrefix = pathPrefix;
            this.containerStart = containerStart;
        }
    }

    private static final class Scan {
        private final String source;
        private final ScalarSourceCursor cursor;
        private final List<JsonRangeToken> tokens = new ArrayList<>();
        private final List<JsonMemberRange> members = new ArrayList<>();
        private final List<JsonLexFault> faults = new ArrayList<>();
        private final List<Context> stack = new ArrayList<>();
        private boolean rootDone;
        private boolean stopped;

        Scan(String source) {
            this.source = source;
            this.cursor = new ScalarSourceCursor(source);
        }

        JsonRangeLexResult run() {
            while (!stopped) {
                skipWhitespace();
                if (cursor.atEnd()) {
                    handleEof();
                    break;
                }
                if (stack.isEmpty()) {
                    if (!rootDone) {
                        expectValue();
                    } else {
                        int cp = cursor.peekScalar();
                        if (isTokenStart(cp)) {
                            fault(JsonFaultKind.TRAILING_CONTENT, zeroRange(),
                                "trailing content after root value");
                        } else {
                            fault(JsonFaultKind.UNEXPECTED_CHARACTER, peekRange(),
                                "unexpected character U+" + hex(cp) + " after root value");
                        }
                        stopped = true;
                    }
                } else {
                    step();
                }
            }
            return new JsonRangeLexResult(tokens, members, faults);
        }

        /** One non-EOF step inside the innermost open container. */
        private void step() {
            Context top = stack.get(stack.size() - 1);
            int cp = cursor.peekScalar();
            if (top.isObject) {
                switch (top.state) {
                    case OBJ_FIRST:
                        if (cp == '}') {
                            closeContainer();
                        } else {
                            expectKey();
                        }
                        return;
                    case OBJ_KEY:
                        expectKey();
                        return;
                    case OBJ_COLON:
                        if (cp == ':') {
                            emitSingle(JsonTokenKind.COLON);
                            top.state = OBJ_VALUE;
                        } else if (isTokenStart(cp)) {
                            positionalFault(JsonFaultKind.EXPECTED_COLON, "expected ':'");
                        } else {
                            fault(JsonFaultKind.UNEXPECTED_CHARACTER, peekRange(),
                                "expected ':', found U+" + hex(cp));
                            stopped = true;
                        }
                        return;
                    case OBJ_VALUE:
                        expectValue();
                        return;
                    default: // OBJ_AFTER_VALUE
                        if (cp == ',') {
                            emitSingle(JsonTokenKind.COMMA);
                            top.state = OBJ_KEY;
                        } else if (cp == '}') {
                            closeContainer();
                        } else {
                            breakFault();
                        }
                }
            } else {
                switch (top.state) {
                    case ARR_FIRST:
                        if (cp == ']') {
                            closeContainer();
                        } else {
                            expectValue();
                        }
                        return;
                    case ARR_VALUE:
                        expectValue();
                        return;
                    default: // ARR_AFTER_VALUE
                        if (cp == ',') {
                            emitSingle(JsonTokenKind.COMMA);
                            top.state = ARR_VALUE;
                        } else if (cp == ']') {
                            closeContainer();
                        } else {
                            breakFault();
                        }
                }
            }
        }

        /**
         * Member start: a string becomes the pending key; any other
         * tokenizable scalar or end-of-input handling is a positional
         * hard failure (today's {@code expect('"')} throw).
         */
        private void expectKey() {
            int cp = cursor.peekScalar();
            if (cp == '"') {
                Context top = stack.get(stack.size() - 1);
                JsonRangeToken key = scanString(JsonTokenKind.KEY);
                top.keyText = (String) key.decodedValue();
                top.keyRange = key.range();
                top.state = OBJ_COLON;
                return;
            }
            if (isTokenStart(cp)) {
                positionalFault(JsonFaultKind.EXPECTED_KEY, "expected string key");
                return;
            }
            fault(JsonFaultKind.UNEXPECTED_CHARACTER, peekRange(),
                "expected string key, found U+" + hex(cp));
            stopped = true;
        }

        /** Value position (object member, array element, or root). */
        private void expectValue() {
            int cp = cursor.peekScalar();
            if (cp == '{' || cp == '[') {
                pushContainer(cp == '{');
                return;
            }
            if (cp == '"') {
                JsonRangeToken t = scanString(JsonTokenKind.STRING);
                afterValue(t.range());
                return;
            }
            if (cp == 't' || cp == 'f') {
                afterValue(scanBoolean().range());
                return;
            }
            if (cp == 'n') {
                afterValue(scanNull().range());
                return;
            }
            if (isNumberStart(cp)) {
                JsonRangeToken t = scanNumber();
                if (t.decodedValue() == null) {
                    fault(JsonFaultKind.EXPECTED_VALUE, t.range(), "number cannot be decoded");
                    stopped = true;
                } else {
                    afterValue(t.range());
                }
                return;
            }
            if (isTokenStart(cp)) {
                positionalFault(JsonFaultKind.EXPECTED_VALUE, "expected value");
                return;
            }
            fault(JsonFaultKind.UNEXPECTED_CHARACTER, peekRange(),
                "expected value, found U+" + hex(cp));
            stopped = true;
        }

        /** Completes a scalar value: pairs the pending member or marks root done. */
        private void afterValue(SourceScalarRange valueRange) {
            if (stack.isEmpty()) {
                rootDone = true;
                return;
            }
            Context top = stack.get(stack.size() - 1);
            if (top.isObject) {
                members.add(new JsonMemberRange(
                    concat(top.pathPrefix, top.keyText), top.keyText, top.keyRange,
                    valueRange,
                    new SourceScalarRange(top.keyRange.startLine(), top.keyRange.startColumn(),
                        valueRange.endLine(), valueRange.endColumn(),
                        top.keyRange.startScalarOffset(), valueRange.endScalarOffset())));
                top.state = OBJ_AFTER_VALUE;
            } else {
                top.state = ARR_AFTER_VALUE;
            }
        }

        private void pushContainer(boolean isObject) {
            List<String> prefix;
            if (stack.isEmpty()) {
                prefix = List.of();
            } else {
                Context top = stack.get(stack.size() - 1);
                prefix = top.isObject ? concat(top.pathPrefix, top.keyText) : top.pathPrefix;
            }
            JsonRangeToken open = emitSingle(isObject ? JsonTokenKind.OBJECT_START
                : JsonTokenKind.ARRAY_START);
            stack.add(new Context(isObject, isObject ? OBJ_FIRST : ARR_FIRST, prefix,
                open.range()));
        }

        private void closeContainer() {
            Context top = stack.remove(stack.size() - 1);
            JsonRangeToken close = emitSingle(top.isObject ? JsonTokenKind.OBJECT_END
                : JsonTokenKind.ARRAY_END);
            SourceScalarRange valueRange = new SourceScalarRange(
                top.containerStart.startLine(), top.containerStart.startColumn(),
                close.range().endLine(), close.range().endColumn(),
                top.containerStart.startScalarOffset(), close.range().endScalarOffset());
            afterValue(valueRange);
        }

        /** Completes the innermost container at end of input (partial value). */
        private void completeContainerAtEof() {
            truncateInnermostContainer(false);
        }

        /**
         * Truncates the innermost container at a loop break without
         * consuming the pending scalar: the container completes as a value
         * in the enclosing context, which then re-checks the same pending
         * scalar. When the cascade reaches the root container, the root is
         * complete as consumed so far and today's parser ignores
         * everything after the root loop break, so the scan stops without
         * recording trailing-content faults for the ignored remainder.
         */
        private void truncateInnermostContainer() {
            truncateInnermostContainer(true);
        }

        private void truncateInnermostContainer(boolean stopAtRoot) {
            Context top = stack.remove(stack.size() - 1);
            ScalarSourceCursor.Mark e = cursor.mark();
            SourceScalarRange valueRange = new SourceScalarRange(
                top.containerStart.startLine(), top.containerStart.startColumn(),
                e.line(), e.column(),
                top.containerStart.startScalarOffset(), e.scalarOffset());
            if (stack.isEmpty()) {
                rootDone = true;
                if (stopAtRoot) {
                    stopped = true;
                }
            } else {
                afterValue(valueRange);
            }
        }

        /**
         * After-value position with a scalar that is neither the container
         * close marker nor a comma: record the fault and truncate only the
         * innermost container without consuming the pending scalar (today's
         * loop break). Each enclosing container then re-checks the same
         * pending scalar against its own close marker or comma: a container
         * that consumes it closes and scanning continues in the container
         * above it — subsequent members are then parsed, validated, and
         * diagnosed exactly as today. A container that does not match
         * breaks in turn, and when the cascade reaches the root container
         * the scan stops with the remaining input ignored (today's
         * stop-every-enclosing-container truncation).
         */
        private void breakFault() {
            int cp = cursor.peekScalar();
            if (isTokenStart(cp)) {
                fault(JsonFaultKind.EXPECTED_COMMA_OR_END, zeroRange(),
                    "expected ',' or container end");
            } else {
                fault(JsonFaultKind.UNEXPECTED_CHARACTER, peekRange(),
                    "expected ',' or container end, found U+" + hex(cp));
            }
            truncateInnermostContainer();
        }

        /** Scans the offending token, records the positional fault, and stops. */
        private void positionalFault(JsonFaultKind kind, String message) {
            JsonRangeToken t = scanAnyToken();
            fault(kind, t.range(), message);
            stopped = true;
        }

        /** End of input with open constructs. */
        private void handleEof() {
            while (!stack.isEmpty()) {
                Context top = stack.get(stack.size() - 1);
                if (top.isObject) {
                    switch (top.state) {
                        case OBJ_FIRST:
                        case OBJ_KEY:
                            fault(JsonFaultKind.EXPECTED_KEY, zeroRange(),
                                "expected string key at end of input");
                            return;
                        case OBJ_COLON:
                            fault(JsonFaultKind.EXPECTED_COLON, zeroRange(),
                                "expected ':' at end of input");
                            return;
                        case OBJ_VALUE: {
                            SourceScalarRange eof = zeroRange();
                            fault(JsonFaultKind.EXPECTED_VALUE, eof,
                                "expected value at end of input");
                            afterValue(eof);
                            fault(JsonFaultKind.EXPECTED_END, zeroRange(), "unclosed object");
                            completeContainerAtEof();
                            continue;
                        }
                        default: // OBJ_AFTER_VALUE
                            fault(JsonFaultKind.EXPECTED_END, zeroRange(), "unclosed object");
                            completeContainerAtEof();
                    }
                } else {
                    switch (top.state) {
                        case ARR_FIRST:
                        case ARR_VALUE:
                            fault(JsonFaultKind.EXPECTED_VALUE, zeroRange(),
                                "expected value at end of input");
                            stack.get(stack.size() - 1).state = ARR_AFTER_VALUE;
                            // fall through to the unclosed-array completion
                        default:
                            fault(JsonFaultKind.EXPECTED_END, zeroRange(), "unclosed array");
                            completeContainerAtEof();
                    }
                }
            }
        }

        // =========================================================================
        // Token scanners
        // =========================================================================

        private JsonRangeToken emitSingle(JsonTokenKind kind) {
            ScalarSourceCursor.Mark s = cursor.mark();
            cursor.advance();
            JsonRangeToken t = new JsonRangeToken(kind, range(s, cursor.mark()), null);
            tokens.add(t);
            return t;
        }

        /** Scans whatever token the next scalar starts (positional-fault use). */
        private JsonRangeToken scanAnyToken() {
            switch (cursor.peekScalar()) {
                case '{': return emitSingle(JsonTokenKind.OBJECT_START);
                case '}': return emitSingle(JsonTokenKind.OBJECT_END);
                case '[': return emitSingle(JsonTokenKind.ARRAY_START);
                case ']': return emitSingle(JsonTokenKind.ARRAY_END);
                case ',': return emitSingle(JsonTokenKind.COMMA);
                case ':': return emitSingle(JsonTokenKind.COLON);
                case '"': return scanString(JsonTokenKind.STRING);
                case 't':
                case 'f': return scanBoolean();
                case 'n': return scanNull();
                default: return scanNumber();
            }
        }

        /** Blind 4/5-scalar boolean consumption, clamped at end of input. */
        private JsonRangeToken scanBoolean() {
            ScalarSourceCursor.Mark s = cursor.mark();
            boolean isTrue = source.startsWith("true", s.index());
            consumeBlindly(isTrue ? 4 : 5);
            JsonRangeToken t = new JsonRangeToken(isTrue ? JsonTokenKind.TRUE
                : JsonTokenKind.FALSE, range(s, cursor.mark()), isTrue);
            tokens.add(t);
            return t;
        }

        /** Blind 4-scalar null consumption, clamped at end of input. */
        private JsonRangeToken scanNull() {
            ScalarSourceCursor.Mark s = cursor.mark();
            consumeBlindly(4);
            JsonRangeToken t = new JsonRangeToken(JsonTokenKind.NULL, range(s, cursor.mark()),
                null);
            tokens.add(t);
            return t;
        }

        private void consumeBlindly(int scalars) {
            for (int i = 0; i < scalars && !cursor.atEnd(); i++) {
                cursor.advance();
            }
        }

        /** Today's parseNumber scan plus Long-then-Double decoding. */
        private JsonRangeToken scanNumber() {
            ScalarSourceCursor.Mark s = cursor.mark();
            if (!cursor.atEnd() && cursor.peekScalar() == '-') {
                cursor.advance();
            }
            while (!cursor.atEnd() && Character.isDigit(cursor.peekScalar())) {
                cursor.advance();
            }
            if (!cursor.atEnd() && cursor.peekScalar() == '.') {
                cursor.advance();
                while (!cursor.atEnd() && Character.isDigit(cursor.peekScalar())) {
                    cursor.advance();
                }
            }
            if (!cursor.atEnd()) {
                int cp = cursor.peekScalar();
                if (cp == 'e' || cp == 'E') {
                    cursor.advance();
                    if (!cursor.atEnd()) {
                        int sign = cursor.peekScalar();
                        if (sign == '+' || sign == '-') {
                            cursor.advance();
                        }
                    }
                    while (!cursor.atEnd() && Character.isDigit(cursor.peekScalar())) {
                        cursor.advance();
                    }
                }
            }
            ScalarSourceCursor.Mark e = cursor.mark();
            String num = source.substring(s.index(), e.index());
            Object decoded = null;
            try {
                decoded = Long.parseLong(num);
            } catch (NumberFormatException ex) {
                try {
                    decoded = Double.parseDouble(num);
                } catch (NumberFormatException inner) {
                    decoded = null;
                }
            }
            JsonRangeToken t = new JsonRangeToken(JsonTokenKind.NUMBER, range(s, e), decoded);
            tokens.add(t);
            return t;
        }

        /** Permissive string scan with today's escape policy and data faults. */
        private JsonRangeToken scanString(JsonTokenKind kind) {
            ScalarSourceCursor.Mark s = cursor.mark();
            cursor.advance(); // opening '"'
            StringBuilder sb = new StringBuilder();
            boolean closed = false;
            while (!cursor.atEnd()) {
                int cp = cursor.peekScalar();
                if (cp == '"') {
                    cursor.advance();
                    closed = true;
                    break;
                }
                if (cp == '\\') {
                    ScalarSourceCursor.Mark esc = cursor.mark();
                    cursor.advance();
                    if (cursor.atEnd()) {
                        break; // trailing backslash dropped, string unterminated
                    }
                    int escCp = cursor.peekScalar();
                    switch (escCp) {
                        case '"': cursor.advance(); sb.append('"'); break;
                        case '\\': cursor.advance(); sb.append('\\'); break;
                        case '/': cursor.advance(); sb.append('/'); break;
                        case 'n': cursor.advance(); sb.append('\n'); break;
                        case 'r': cursor.advance(); sb.append('\r'); break;
                        case 't': cursor.advance(); sb.append('\t'); break;
                        default:
                            cursor.advance();
                            sb.appendCodePoint(escCp);
                            fault(JsonFaultKind.INVALID_ESCAPE, range(esc, cursor.mark()),
                                "invalid escape U+" + hex(escCp));
                            break;
                    }
                    continue;
                }
                ScalarSourceCursor.Mark cs = cursor.mark();
                cursor.advance();
                if (cp < 0x20) {
                    fault(JsonFaultKind.RAW_CONTROL_IN_STRING, range(cs, cursor.mark()),
                        "raw control character in string");
                }
                if (cp >= 0xD800 && cp <= 0xDFFF) {
                    fault(JsonFaultKind.UNPAIRED_SURROGATE, range(cs, cursor.mark()),
                        "unpaired surrogate in string");
                }
                sb.appendCodePoint(cp);
            }
            JsonRangeToken t = new JsonRangeToken(kind, range(s, cursor.mark()), sb.toString());
            tokens.add(t);
            if (!closed) {
                fault(JsonFaultKind.EXPECTED_END, zeroRange(), "unterminated string");
            }
            return t;
        }

        // =========================================================================
        // Whitespace and range helpers
        // =========================================================================

        /** Skips Java Character.isWhitespace scalars; records non-strict runs. */
        private void skipWhitespace() {
            ScalarSourceCursor.Mark s = cursor.mark();
            boolean nonStrict = false;
            while (!cursor.atEnd()) {
                int cp = cursor.peekScalar();
                if (!Character.isWhitespace(cp)) {
                    break;
                }
                if (cp != ' ' && cp != '\t' && cp != '\n' && cp != '\r') {
                    nonStrict = true;
                }
                cursor.advance();
            }
            if (nonStrict) {
                fault(JsonFaultKind.NON_STRICT_WHITESPACE, range(s, cursor.mark()),
                    "non-strict JSON whitespace skipped");
            }
        }

        private SourceScalarRange range(ScalarSourceCursor.Mark s, ScalarSourceCursor.Mark e) {
            return new SourceScalarRange(s.line(), s.column(), e.line(), e.column(),
                s.scalarOffset(), e.scalarOffset());
        }

        /** Zero-length range at the current cursor position. */
        private SourceScalarRange zeroRange() {
            ScalarSourceCursor.Mark m = cursor.mark();
            return range(m, m);
        }

        /** One-scalar range of the next (unconsumed) scalar, without consuming. */
        private SourceScalarRange peekRange() {
            ScalarSourceCursor.Mark s = cursor.mark();
            cursor.advance();
            ScalarSourceCursor.Mark e = cursor.mark();
            cursor.reset(s);
            return range(s, e);
        }

        private void fault(JsonFaultKind kind, SourceScalarRange range, String message) {
            faults.add(new JsonLexFault(kind, range, message));
        }

        private static String hex(int cp) {
            return Integer.toHexString(cp).toUpperCase(Locale.ROOT);
        }

        private static List<String> concat(List<String> prefix, String key) {
            List<String> result = new ArrayList<>(prefix);
            result.add(key);
            return List.copyOf(result);
        }

        private static boolean isNumberStart(int cp) {
            return cp == '-' || cp == '.' || Character.isDigit(cp);
        }

        private static boolean isValueStart(int cp) {
            return cp == '{' || cp == '[' || cp == '"' || cp == 't' || cp == 'f' || cp == 'n'
                || isNumberStart(cp);
        }

        private static boolean isTokenStart(int cp) {
            return isValueStart(cp) || cp == ',' || cp == ':' || cp == '}' || cp == ']';
        }
    }
}
