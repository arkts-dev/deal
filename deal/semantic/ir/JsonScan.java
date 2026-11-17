package deal.semantic.ir;

/**
 * The shared RFC-8259 scanner cores of the JSON parsers
 * ({@code SharedStdlibSemantics}, {@code JvmRuntime},
 * {@code CanonicalJson}, the LuaJIT async-export invoker): the cursor
 * operations, the whitespace/literal/string/hex-escape scans, and the
 * number-token scan, plus the stack-safe container grammar used by the
 * semantic and JVM readers. The exact decimal classification of one scanned
 * token is {@link JsonNumberClassifier}'s one algorithm. Value production
 * stays in each parser's own reader; the hooks build the parser's own
 * failure type.
 */
public final class JsonScan {

    private JsonScan() {
    }

    private static final String REASON_UNEXPECTED_CHARACTER = "unexpected character";
    private static final String REASON_UNTERMINATED_STRING = "unterminated string";
    private static final String REASON_INVALID_ESCAPE = "invalid escape";
    private static final String REASON_INVALID_NUMBER = "invalid number";
    private static final String REASON_LEADING_ZERO = "leading zero";
    private static final String REASON_UNEXPECTED_END = "unexpected end of input";

    /**
     * The code-point cursor and scanner of a parser over
     * {@code int[]} code points, tracking the 1-based UTF-8 byte offset of
     * the current scan position.
     */
    public abstract static class CodePointCursor {

        private final int[] codePoints;
        private int position;
        private long bytesConsumed;

        protected CodePointCursor(int[] codePoints) {
            this.codePoints = codePoints;
        }

        /** The parser's own failure for one pinned reason. */
        public abstract RuntimeException failure(String reason);

        public final boolean atEnd() {
            return position >= codePoints.length;
        }

        public final int peek() {
            return position < codePoints.length ? codePoints[position] : -1;
        }

        public final void advance() {
            bytesConsumed += utf8Length(codePoints[position]);
            position++;
        }

        /** The 1-based UTF-8 byte offset of the current scan position. */
        public final long offsetOfCurrent() {
            return bytesConsumed + 1;
        }

        public final void skipWs() {
            while (!atEnd() && isWs(peek())) {
                advance();
            }
        }

        public final <T> T parseLiteral(String word, T value) {
            for (int i = 0; i < word.length(); i++) {
                if (atEnd()) {
                    throw failure(REASON_UNEXPECTED_END);
                }
                if (peek() != word.codePointAt(i)) {
                    throw failure(REASON_UNEXPECTED_CHARACTER);
                }
                advance();
            }
            return value;
        }

        /** Exactly four hex digits; a non-hex digit or end of input is an invalid escape. */
        public final int parseHex4() {
            int value = 0;
            for (int i = 0; i < 4; i++) {
                if (atEnd()) {
                    throw failure(REASON_INVALID_ESCAPE);
                }
                int digit = hexDigit(peek());
                if (digit < 0) {
                    throw failure(REASON_INVALID_ESCAPE);
                }
                advance();
                value = value * 16 + digit;
            }
            return value;
        }

        public final String parseString() {
            advance(); // '"'
            StringBuilder result = new StringBuilder();
            while (true) {
                if (atEnd()) {
                    throw failure(REASON_UNTERMINATED_STRING);
                }
                int c = peek();
                if (c == '"') {
                    advance();
                    return result.toString();
                }
                if (c == '\\') {
                    advance(); // backslash
                    if (atEnd()) {
                        throw failure(REASON_UNTERMINATED_STRING);
                    }
                    int escaped = peek();
                    switch (escaped) {
                        case '"' -> {
                            advance();
                            result.append('"');
                        }
                        case '\\' -> {
                            advance();
                            result.append('\\');
                        }
                        case '/' -> {
                            advance();
                            result.append('/');
                        }
                        case 'b' -> {
                            advance();
                            result.append('\b');
                        }
                        case 'f' -> {
                            advance();
                            result.append('\f');
                        }
                        case 'n' -> {
                            advance();
                            result.append('\n');
                        }
                        case 'r' -> {
                            advance();
                            result.append('\r');
                        }
                        case 't' -> {
                            advance();
                            result.append('\t');
                        }
                        case 'u' -> {
                            advance(); // 'u'
                            result.append((char) parseHex4());
                        }
                        default -> throw failure(REASON_INVALID_ESCAPE);
                    }
                } else if (c < 0x20) {
                    // A raw control character is never allowed unescaped.
                    throw failure(REASON_UNEXPECTED_CHARACTER);
                } else {
                    advance();
                    result.appendCodePoint(c);
                }
            }
        }

        /** The scanned number token: the raw lexeme text. */
        public record NumberScan(String text) {
        }

        /** Scans one RFC-8259 number token; the classification stays with the caller. */
        public final NumberScan parseNumberText() {
            int start = position;
            if (peek() == '-') {
                advance();
            }
            if (atEnd()) {
                throw failure(REASON_INVALID_NUMBER);
            }
            int c = peek();
            if (c < '0' || c > '9') {
                throw failure(REASON_INVALID_NUMBER);
            }
            if (c == '0') {
                advance();
                if (!atEnd() && isDigit(peek())) {
                    throw failure(REASON_LEADING_ZERO);
                }
            } else {
                advance();
                while (!atEnd() && isDigit(peek())) {
                    advance();
                }
            }
            if (!atEnd() && peek() == '.') {
                advance();
                if (atEnd() || !isDigit(peek())) {
                    throw failure(REASON_INVALID_NUMBER);
                }
                while (!atEnd() && isDigit(peek())) {
                    advance();
                }
            }
            if (!atEnd() && (peek() == 'e' || peek() == 'E')) {
                advance();
                if (!atEnd() && (peek() == '+' || peek() == '-')) {
                    advance();
                }
                if (atEnd() || !isDigit(peek())) {
                    throw failure(REASON_INVALID_NUMBER);
                }
                while (!atEnd() && isDigit(peek())) {
                    advance();
                }
            }
            return new NumberScan(new String(codePoints, start, position - start));
        }

        private static boolean isWs(int codePoint) {
            return codePoint == 0x20 || codePoint == 0x09
                || codePoint == 0x0A || codePoint == 0x0D;
        }

        private static boolean isDigit(int codePoint) {
            return codePoint >= '0' && codePoint <= '9';
        }

        private static int hexDigit(int codePoint) {
            if (codePoint >= '0' && codePoint <= '9') {
                return codePoint - '0';
            }
            if (codePoint >= 'a' && codePoint <= 'f') {
                return codePoint - 'a' + 10;
            }
            if (codePoint >= 'A' && codePoint <= 'F') {
                return codePoint - 'A' + 10;
            }
            return -1;
        }

        /** The UTF-8 byte length of one Unicode scalar value. */
        private static int utf8Length(int codePoint) {
            return codePoint < 0x80 ? 1 : codePoint < 0x800 ? 2 : codePoint < 0x10000 ? 3 : 4;
        }
    }

    /** Stack-safe container grammar; readers retain scalar conversion and native storage. */
    public abstract static class ContainerReader<V, T, A> extends CodePointCursor {
        protected ContainerReader(int[] codePoints) { super(codePoints); }

        protected abstract T newObject();
        protected abstract A newArray();
        protected abstract void put(T object, String key, V value);
        protected abstract void append(A array, V value);
        protected abstract V finishObject(T object);
        protected abstract V finishArray(A array);
        protected abstract V parseScalar();
        protected abstract RuntimeException failureAt(String reason, long byteOffset);

        private static final int OBJECT_FIRST = 0, OBJECT_KEY = 1, VALUE = 2,
            OBJECT_AFTER = 3, ARRAY_FIRST = 4, ARRAY_AFTER = 5;

        private static final class Frame<T, A> {
            final T object;
            final A array;
            final java.util.Set<String> keys;
            String key;
            int state;

            Frame(T object, A array, int state) {
                this.object = object;
                this.array = array;
                this.state = state;
                keys = object == null ? null : new java.util.HashSet<>();
            }
        }

        public final V parseValue() {
            java.util.Deque<Frame<T, A>> stack = new java.util.ArrayDeque<>();
            V completed = null;
            boolean haveValue = false;
            while (true) {
                if (haveValue) {
                    if (stack.isEmpty()) return completed;
                    Frame<T, A> parent = stack.peek();
                    if (parent.object == null) {
                        append(parent.array, completed);
                        parent.state = ARRAY_AFTER;
                    } else {
                        put(parent.object, parent.key, completed);
                        parent.state = OBJECT_AFTER;
                    }
                    completed = null;
                    haveValue = false;
                    continue;
                }
                if (stack.isEmpty() || stack.peek().state == VALUE) {
                    skipWs();
                    if (atEnd()) throw failure(REASON_UNEXPECTED_END);
                    switch (peek()) {
                        case '{' -> {
                            advance();
                            stack.push(new Frame<>(newObject(), null, OBJECT_FIRST));
                        }
                        case '[' -> {
                            advance();
                            stack.push(new Frame<>(null, newArray(), ARRAY_FIRST));
                        }
                        default -> {
                            completed = parseScalar();
                            haveValue = true;
                        }
                    }
                    continue;
                }
                Frame<T, A> frame = stack.peek();
                skipWs();
                switch (frame.state) {
                    case OBJECT_FIRST -> {
                        if (!atEnd() && peek() == '}') {
                            advance();
                            completed = finishObject(frame.object);
                            stack.pop();
                            haveValue = true;
                        } else {
                            frame.state = OBJECT_KEY;
                        }
                    }
                    case OBJECT_KEY -> {
                        if (atEnd()) throw failure(REASON_UNEXPECTED_END);
                        if (peek() != '"') throw failure("missing key");
                        long offset = offsetOfCurrent();
                        String key = parseString();
                        if (!frame.keys.add(key)) throw failureAt("duplicate key", offset);
                        skipWs();
                        if (atEnd() || peek() != ':') throw failure("missing colon");
                        advance();
                        frame.key = key;
                        frame.state = VALUE;
                    }
                    case OBJECT_AFTER -> {
                        if (atEnd()) throw failure("unterminated object");
                        if (peek() == ',') {
                            advance();
                            frame.state = OBJECT_KEY;
                        } else if (peek() == '}') {
                            advance();
                            completed = finishObject(frame.object);
                            stack.pop();
                            haveValue = true;
                        } else {
                            throw failure("missing comma");
                        }
                    }
                    case ARRAY_FIRST -> {
                        if (!atEnd() && peek() == ']') {
                            advance();
                            completed = finishArray(frame.array);
                            stack.pop();
                            haveValue = true;
                        } else {
                            frame.state = VALUE;
                        }
                    }
                    case ARRAY_AFTER -> {
                        if (atEnd()) throw failure("unterminated array");
                        if (peek() == ',') {
                            advance();
                            frame.state = VALUE;
                        } else if (peek() == ']') {
                            advance();
                            completed = finishArray(frame.array);
                            stack.pop();
                            haveValue = true;
                        } else {
                            throw failure("missing comma");
                        }
                    }
                    default -> throw new IllegalStateException("unknown JSON reader frame state " + frame.state);
                }
            }
        }
    }

    /**
     * The character cursor and scanner of a parser over {@code String}
     * text.
     */
    public abstract static class CharCursor {

        protected final String text;
        protected int pos;

        protected CharCursor(String text) {
            this.text = text;
        }

        /** The parser's own failure for one pinned reason. */
        public abstract RuntimeException err(String reason);

        public final boolean atEnd() {
            return pos >= text.length();
        }

        public final char peek() {
            return text.charAt(pos);
        }

        public final void skipWhitespace() {
            while (!atEnd()) {
                char c = peek();
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    return;
                }
            }
        }

        public final boolean isDelimiter(char c) {
            return c == ' ' || c == '\t' || c == '\n' || c == '\r'
                || c == ',' || c == ']' || c == '}';
        }

        public final <T> T parseLiteral(String word, T value) {
            if (!text.startsWith(word, pos)) {
                throw err("malformed literal (expected \"" + word + "\")");
            }
            pos += word.length();
            if (!atEnd() && !isDelimiter(peek())) {
                throw err("malformed literal \"" + word + "\"");
            }
            return value;
        }
    }
}
