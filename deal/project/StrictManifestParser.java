package deal.project;

import deal.diagnostics.CompilerDiagnostic;
import deal.diagnostics.DiagnosticCode;
import deal.diagnostics.DiagnosticRange;
import deal.diagnostics.RangeOrigin;
import deal.source.JsonRangeLexer;
import deal.source.JsonRangeLexer.JsonFaultKind;
import deal.source.JsonRangeLexer.JsonLexFault;
import deal.source.JsonRangeLexer.JsonRangeLexResult;
import deal.source.JsonRangeLexer.JsonRangeToken;
import deal.source.JsonRangeLexer.JsonTokenKind;
import deal.source.ScalarSourceCursor;
import deal.source.SourceScalarRange;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The strict, total, duplicate-safe, closed v1.2 manifest parser (design
 * source {@code strict-project-context-resolution-identity} D2).
 *
 * <p>Input contract: the parser receives <b>strictly UTF-8-decoded text
 * only</b> — the byte-level REPORT-mode decode is ProjectLocator's D1 step
 * 3. It consumes the {@code deal.source.JsonRangeLexer} token/fault
 * substrate and performs <b>no filesystem access and no byte-level
 * decoding</b>: the {@code manifestPath} argument is embedded in
 * diagnostic ranges only, and no {@code java.io} or {@code java.nio.file}
 * type appears on this parser's surface.
 *
 * <p>The strict pass walks the lexer result once in scan order and rejects
 * the first defect in scan order, fail-fast:
 * <ul>
 *   <li>strict structural faults — {@code NON_STRICT_WHITESPACE} (only
 *       SP/HT/LF/CR between tokens), {@code RAW_CONTROL_IN_STRING},
 *       {@code UNPAIRED_SURROGATE}, {@code EXPECTED_END},
 *       {@code EXPECTED_COMMA_OR_END}, {@code EXPECTED_KEY},
 *       {@code EXPECTED_COLON}, {@code EXPECTED_VALUE},
 *       {@code UNEXPECTED_CHARACTER}, {@code TRAILING_CONTENT}; the
 *       substrate's {@code INVALID_ESCAPE} records are never used as
 *       strict errors;</li>
 *   <li>strict literal re-validation from each token's own source slice —
 *       numbers match {@code -?(0|[1-9][0-9]*)(\.[0-9]+)?([eE][+-]?[0-9]+)?}
 *       (so {@code 01}, {@code 1.}, {@code -.5} fail and {@code 1e5} is
 *       valid), booleans/null are exactly {@code true}/{@code false}/
 *       {@code null};</li>
 *   <li>strict per-token string re-scan of every KEY/STRING token from
 *       its own original source slice (re-derived through the
 *       {@code ScalarSourceCursor} scalar→UTF-16 index mapping) with the
 *       pinned escape set <code>" \ / b f n r t uXXXX</code> and true
 *       Unicode decoding: {@code \u005cuXXXX} requires four hex digits and
 *       decodes to the actual Unicode scalar; two consecutive
 *       surrogate-range escapes decode as one supplementary scalar; a
 *       high surrogate not followed by a low-surrogate escape, or a
 *       standalone low-surrogate escape, fails at the escape's range;
 *       any other escape (including a trailing backslash) fails from the
 *       backslash through the offending scalar or the token end;</li>
 *   <li>duplicate members rejected recursively at any depth via the
 *       open-object stack — a KEY whose strictly decoded key repeats a
 *       key of the same enclosing object fails at the second key's range
 *       (top level, every externals entry, every object inside
 *       {@code dependencies} at any depth; sibling objects under an array
 *       never collide);</li>
 *   <li>closed member sets — top-level members are exactly
 *       {@code languageVersion}, {@code moduleRoots}, {@code output},
 *       {@code backend}, {@code stdlib}, {@code dependencies},
 *       {@code externals}; externals entry members are exactly
 *       {@code declaration}, {@code nativeLibrary}; unknown members
 *       (including legacy {@code permissions}/{@code limits}) fail at the
 *       key range.</li>
 * </ul>
 *
 * <p>Only on a clean walk, {@link ProjectConfigValidator}'s post-walk
 * validations run in pinned canonical order independent of member
 * document order: {@code languageVersion} → {@code backend} →
 * {@code moduleRoots} → {@code output} → {@code stdlib} →
 * {@code dependencies} → {@code externals} entries (member order). Every
 * failure is exactly one E2010 {@link CompilerDiagnostic} with a complete
 * SOURCE {@link DiagnosticRange}; no start-only producer exists.
 *
 * <p>Determinism: identical decoded text parses to byte-identical
 * results — no timestamps, ordinals, or process state enter the parse.
 */
public final class StrictManifestParser {

    private StrictManifestParser() {
    }

    /** The strict JSON number grammar (D2). */
    private static final Pattern STRICT_NUMBER = Pattern.compile(
        "-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?");

    /** The exact closed top-level member set (D2). */
    private static final Set<String> TOP_LEVEL_MEMBERS = Set.of(
        "languageVersion", "moduleRoots", "output", "backend", "stdlib",
        "dependencies", "externals");

    /** The exact closed externals-entry member set (D2). */
    private static final Set<String> EXTERNALS_ENTRY_MEMBERS = Set.of(
        "declaration", "nativeLibrary");

    /**
     * The sole parser result channel: exactly one of {@code manifest} and
     * {@code failure} is non-null. A failed parse publishes no manifest,
     * and a successful parse carries no diagnostic.
     */
    public record StrictManifestParseResult(ProjectManifest manifest,
                                            CompilerDiagnostic failure) {
        public StrictManifestParseResult {
            if ((manifest == null) == (failure == null)) {
                throw new IllegalArgumentException(
                    "exactly one of manifest and failure must be non-null");
            }
        }
    }

    /**
     * Parses strictly UTF-8-decoded manifest text into a
     * {@link ProjectManifest} or the single fail-fast E2010 diagnostic.
     *
     * <p>Never throws for content: a null text is treated as empty. The
     * {@code manifestPath} is used only to anchor diagnostic ranges; no
     * filesystem access occurs for any input, so parsing content whose
     * referenced files do not exist is not an error.
     *
     * @param manifestPath        the manifest's path as reported in
     *                            diagnostics (may be null, then the empty
     *                            string)
     * @param strictlyDecodedText the strictly UTF-8-decoded manifest text
     *                            (ProjectLocator D1 step 3)
     * @return the parsed manifest, or exactly one E2010
     */
    public static StrictManifestParseResult parse(String manifestPath,
                                                  String strictlyDecodedText) {
        String file = manifestPath == null ? "" : manifestPath;
        String source = strictlyDecodedText == null ? "" : strictlyDecodedText;
        JsonRangeLexResult lexed = JsonRangeLexer.lex(source);

        StrictJsonValue[] rootHolder = new StrictJsonValue[1];
        Map<StrictJsonValue, SourceScalarRange> valueRanges = new IdentityHashMap<>();
        List<Defect> tokenDefects = new ArrayList<>();
        walkTokens(source, lexed.orderedTokens(), tokenDefects, rootHolder, valueRanges);

        // Strict fault events in scan order: every fault kind except
        // INVALID_ESCAPE — the escape decision is the re-scan's alone.
        List<JsonLexFault> strictFaults = new ArrayList<>();
        for (JsonLexFault fault : lexed.faults()) {
            if (fault.kind() != JsonFaultKind.INVALID_ESCAPE) {
                strictFaults.add(fault);
            }
        }

        Defect first = firstDefect(strictFaults, tokenDefects);
        if (first != null) {
            return new StrictManifestParseResult(null,
                e2010(file, first.message(), first.range()));
        }

        ProjectConfigValidator.Outcome outcome = ProjectConfigValidator.validate(
            new StrictJsonDocument(rootHolder[0], valueRanges));
        if (outcome.failure() != null) {
            return new StrictManifestParseResult(null,
                e2010(file, outcome.failure().message(), outcome.failure().anchorRange()));
        }
        return new StrictManifestParseResult(outcome.manifest(), null);
    }

    // =========================================================================
    // The strict walk
    // =========================================================================

    /** Object roles driving the closed-member checks. */
    private enum Role {
        /** The manifest root object. */
        TOP_LEVEL,
        /** The {@code externals} member's map object (keys are specifiers). */
        EXTERNALS_MAP,
        /** One externals entry object. */
        EXTERNALS_ENTRY,
        /** Any other object (opaque contents; duplicates still checked). */
        OPAQUE
    }

    /** One open container during the walk. */
    private static final class Frame {
        final boolean isObject;
        final Role role;
        final SourceScalarRange startRange;
        final LinkedHashMap<String, StrictJsonValue> members;
        final ArrayList<StrictJsonValue> elements;
        final Set<String> keys;
        String pendingKey;

        private Frame(boolean isObject, Role role, SourceScalarRange startRange) {
            this.isObject = isObject;
            this.role = role;
            this.startRange = startRange;
            this.members = isObject ? new LinkedHashMap<>() : null;
            this.elements = isObject ? null : new ArrayList<>();
            this.keys = isObject ? new HashSet<>() : null;
        }

        static Frame object(Role role, SourceScalarRange startRange) {
            return new Frame(true, role, startRange);
        }

        static Frame array(SourceScalarRange startRange) {
            return new Frame(false, Role.OPAQUE, startRange);
        }
    }

    /** One scan-order defect: an anchor range plus a message. */
    private record Defect(SourceScalarRange range, String message) {
    }

    /**
     * The result of one strict string re-scan: the strictly decoded value
     * and an optional defect as slice-local scalar offsets
     * {@code [start, end)}.
     */
    private record StrictScan(String value, int[] defect) {
    }

    /**
     * Walks the ordered tokens once in scan order, collecting per-token
     * strict defects (literal re-validation, string re-scan, duplicate
     * keys, closed member sets) and building the strictly decoded model
     * with identity-keyed value ranges. The model is only consumed when
     * no defect exists.
     */
    private static void walkTokens(String source, List<JsonRangeToken> tokens,
                                   List<Defect> defects, StrictJsonValue[] rootHolder,
                                   Map<StrictJsonValue, SourceScalarRange> valueRanges) {
        ScalarIndex index = new ScalarIndex(source);
        Deque<Frame> frames = new ArrayDeque<>();
        for (JsonRangeToken token : tokens) {
            switch (token.kind()) {
                case OBJECT_START -> frames.push(Frame.object(objectRole(frames), token.range()));
                case ARRAY_START -> frames.push(Frame.array(token.range()));
                case KEY -> {
                    Frame top = frames.peek();
                    if (top == null || !top.isObject) {
                        break; // defensive: the lexer never emits KEY outside an object
                    }
                    StrictScan scan = strictScanString(index.slice(token.range()));
                    if (scan.defect() != null) {
                        defects.add(new Defect(tokenRange(index, token,
                            scan.defect()[0], scan.defect()[1]),
                            "deal.json: invalid JSON: invalid escape sequence in member key"));
                    } else {
                        String key = scan.value();
                        if (!top.keys.add(key)) {
                            defects.add(new Defect(token.range(),
                                "deal.json: duplicate member '" + key + "'"));
                        } else if (top.role == Role.TOP_LEVEL && !TOP_LEVEL_MEMBERS.contains(key)) {
                            defects.add(new Defect(token.range(),
                                "deal.json: unknown member '" + key + "'"));
                        } else if (top.role == Role.EXTERNALS_ENTRY
                                && !EXTERNALS_ENTRY_MEMBERS.contains(key)) {
                            defects.add(new Defect(token.range(),
                                "deal.json: unknown member '" + key + "' in externals entry"));
                        }
                        top.pendingKey = key;
                    }
                }
                case STRING -> {
                    StrictScan scan = strictScanString(index.slice(token.range()));
                    if (scan.defect() != null) {
                        defects.add(new Defect(tokenRange(index, token,
                            scan.defect()[0], scan.defect()[1]),
                            "deal.json: invalid JSON: invalid escape sequence in string"));
                    }
                    completeValue(frames, new StrictJsonValue.StringVal(scan.value()),
                        token.range(), rootHolder, valueRanges);
                }
                case NUMBER -> {
                    String slice = index.slice(token.range());
                    if (!STRICT_NUMBER.matcher(slice).matches()) {
                        defects.add(new Defect(token.range(),
                            "deal.json: invalid JSON: number '" + slice
                                + "' does not match the strict JSON grammar"));
                    }
                    completeValue(frames, new StrictJsonValue.NumberVal(slice), token.range(),
                        rootHolder, valueRanges);
                }
                case TRUE -> {
                    if (!index.slice(token.range()).equals("true")) {
                        defects.add(new Defect(token.range(),
                            "deal.json: invalid JSON: expected literal 'true'"));
                    }
                    completeValue(frames, new StrictJsonValue.BoolVal(true), token.range(),
                        rootHolder, valueRanges);
                }
                case FALSE -> {
                    if (!index.slice(token.range()).equals("false")) {
                        defects.add(new Defect(token.range(),
                            "deal.json: invalid JSON: expected literal 'false'"));
                    }
                    completeValue(frames, new StrictJsonValue.BoolVal(false), token.range(),
                        rootHolder, valueRanges);
                }
                case NULL -> {
                    if (!index.slice(token.range()).equals("null")) {
                        defects.add(new Defect(token.range(),
                            "deal.json: invalid JSON: expected literal 'null'"));
                    }
                    completeValue(frames, new StrictJsonValue.NullVal(), token.range(),
                        rootHolder, valueRanges);
                }
                case OBJECT_END, ARRAY_END -> {
                    Frame closed = frames.poll();
                    if (closed == null) {
                        break; // defensive: unbalanced close token
                    }
                    StrictJsonValue value = closed.isObject
                        ? new StrictJsonValue.ObjectVal(closed.members)
                        : new StrictJsonValue.ArrayVal(closed.elements);
                    completeValue(frames, value, span(closed.startRange, token.range()),
                        rootHolder, valueRanges);
                }
                case COLON, COMMA -> {
                    // Structural pairing is enforced by the substrate's
                    // fault surface, all of which is strict-relevant.
                }
            }
        }
    }

    /**
     * The object role of the next container: the root, the externals map,
     * an externals entry, or opaque (everything else, including any
     * object under an array and every object inside {@code dependencies}
     * contents at any depth).
     */
    private static Role objectRole(Deque<Frame> frames) {
        if (frames.isEmpty()) {
            return Role.TOP_LEVEL;
        }
        Frame parent = frames.peek();
        if (!parent.isObject) {
            return Role.OPAQUE;
        }
        if (parent.role == Role.TOP_LEVEL && "externals".equals(parent.pendingKey)) {
            return Role.EXTERNALS_MAP;
        }
        if (parent.role == Role.EXTERNALS_MAP) {
            return Role.EXTERNALS_ENTRY;
        }
        return Role.OPAQUE;
    }

    /** Completes a value: attaches it to the pending member/array, or the root. */
    private static void completeValue(Deque<Frame> frames, StrictJsonValue value,
                                      SourceScalarRange range, StrictJsonValue[] rootHolder,
                                      Map<StrictJsonValue, SourceScalarRange> valueRanges) {
        if (frames.isEmpty()) {
            rootHolder[0] = value;
        } else {
            Frame top = frames.peek();
            if (top.isObject) {
                top.members.put(top.pendingKey, value);
                top.pendingKey = null;
            } else {
                top.elements.add(value);
            }
        }
        valueRanges.put(value, range);
    }

    /** The container's whole range: start token start through end token end. */
    private static SourceScalarRange span(SourceScalarRange start, SourceScalarRange end) {
        return new SourceScalarRange(start.startLine(), start.startColumn(),
            end.endLine(), end.endColumn(),
            start.startScalarOffset(), end.endScalarOffset());
    }

    /**
     * Merges the scan-ordered strict fault events and the scan-ordered
     * token defect events; at an equal start offset the substrate fault
     * wins (deterministic — the only real tie is an undecodable-number
     * token whose substrate EXPECTED_VALUE fault and strict-grammar
     * re-validation share the token range).
     */
    private static Defect firstDefect(List<JsonLexFault> faults, List<Defect> tokenDefects) {
        int faultIndex = 0;
        int defectIndex = 0;
        while (faultIndex < faults.size() || defectIndex < tokenDefects.size()) {
            JsonLexFault fault = faultIndex < faults.size() ? faults.get(faultIndex) : null;
            Defect tokenDefect = defectIndex < tokenDefects.size()
                ? tokenDefects.get(defectIndex) : null;
            if (fault == null) {
                return tokenDefect;
            }
            if (tokenDefect == null) {
                return faultDefect(fault);
            }
            int comparison = Integer.compare(fault.range().startScalarOffset(),
                tokenDefect.range().startScalarOffset());
            if (comparison <= 0) {
                return faultDefect(fault);
            }
            return tokenDefect;
        }
        return null;
    }

    /** Maps one strict substrate fault to its fail-fast defect. */
    private static Defect faultDefect(JsonLexFault fault) {
        String message;
        switch (fault.kind()) {
            case NON_STRICT_WHITESPACE -> message = "deal.json: invalid JSON: non-strict JSON"
                + " whitespace (only space, tab, LF, and CR are allowed between tokens)";
            case RAW_CONTROL_IN_STRING -> message =
                "deal.json: invalid JSON: raw control character in string";
            case UNPAIRED_SURROGATE -> message =
                "deal.json: invalid JSON: unpaired surrogate in string";
            default -> message = "deal.json: invalid JSON: " + fault.message();
        }
        return new Defect(fault.range(), message);
    }

    // =========================================================================
    // Strict string re-scan (D2)
    // =========================================================================

    /**
     * Re-scans one KEY/STRING token's own original source slice with the
     * pinned strict escape set <code>" \ / b f n r t uXXXX</code> and
     * true Unicode decoding. Returns the strictly decoded value and, when
     * the token violates the strict set, the first defect as slice-local
     * scalar offsets {@code [start, end)} — the backslash through the
     * offending scalar, or through the token end for a trailing
     * backslash/truncated {@code \u005cu} escape; a surrogate defect covers
     * the six scalars of its {@code \u005cuXXXX} escape. An unterminated
     * string yields no re-scan defect: the substrate's EXPECTED_END fault
     * supplies the failure.
     */
    private static StrictScan strictScanString(String slice) {
        ScalarSourceCursor cursor = new ScalarSourceCursor(slice);
        StringBuilder value = new StringBuilder();
        if (cursor.atEnd() || cursor.peekScalar() != '"') {
            // Defensive: token slices always start with the opening quote.
            return new StrictScan("", null);
        }
        cursor.advance();
        while (!cursor.atEnd()) {
            int cp = cursor.peekScalar();
            if (cp == '"') {
                cursor.advance();
                return new StrictScan(value.toString(), null);
            }
            if (cp == '\\') {
                ScalarSourceCursor.Mark escape = cursor.mark();
                cursor.advance();
                if (cursor.atEnd()) {
                    return new StrictScan(value.toString(),
                        new int[] {escape.scalarOffset(), cursor.scalarOffset()});
                }
                int escaped = cursor.peekScalar();
                switch (escaped) {
                    case '"': cursor.advance(); value.append('"'); break;
                    case '\\': cursor.advance(); value.append('\\'); break;
                    case '/': cursor.advance(); value.append('/'); break;
                    case 'b': cursor.advance(); value.append('\b'); break;
                    case 'f': cursor.advance(); value.append('\f'); break;
                    case 'n': cursor.advance(); value.append('\n'); break;
                    case 'r': cursor.advance(); value.append('\r'); break;
                    case 't': cursor.advance(); value.append('\t'); break;
                    case 'u': {
                        StrictScan unicode = scanUnicodeEscape(cursor, escape, value);
                        if (unicode.defect() != null) {
                            return unicode;
                        }
                        break;
                    }
                    default:
                        cursor.advance();
                        return new StrictScan(value.toString(),
                            new int[] {escape.scalarOffset(), cursor.scalarOffset()});
                }
                continue;
            }
            cursor.advance();
            value.appendCodePoint(cp);
        }
        return new StrictScan(value.toString(), null);
    }

    /**
     * Scans the four hex digits after a consumed {@code \u005cu}, decodes the
     * 16-bit unit, and combines two consecutive surrogate-range escapes
     * into one supplementary scalar. A truncated or non-hex escape fails
     * from the backslash through the offending scalar/token end; a high
     * surrogate not followed by a low-surrogate escape, and a standalone
     * low-surrogate escape, fail over the six scalars of the escape.
     */
    private static StrictScan scanUnicodeEscape(ScalarSourceCursor cursor,
                                                ScalarSourceCursor.Mark escape,
                                                StringBuilder value) {
        cursor.advance(); // consume the 'u'
        int unit = 0;
        for (int i = 0; i < 4; i++) {
            if (cursor.atEnd()) {
                return new StrictScan(value.toString(),
                    new int[] {escape.scalarOffset(), cursor.scalarOffset()});
            }
            int hex = hexValue(cursor.peekScalar());
            if (hex < 0) {
                cursor.advance();
                return new StrictScan(value.toString(),
                    new int[] {escape.scalarOffset(), cursor.scalarOffset()});
            }
            cursor.advance();
            unit = (unit << 4) | hex;
        }
        if (unit >= 0xD800 && unit <= 0xDBFF) {
            int escapeStart = escape.scalarOffset();
            int escapeEnd = escapeStart + 6;
            if (cursor.atEnd() || cursor.peekScalar() != '\\') {
                return new StrictScan(value.toString(), new int[] {escapeStart, escapeEnd});
            }
            cursor.advance();
            if (cursor.atEnd() || cursor.peekScalar() != 'u') {
                return new StrictScan(value.toString(), new int[] {escapeStart, escapeEnd});
            }
            cursor.advance();
            int low = 0;
            for (int i = 0; i < 4; i++) {
                if (cursor.atEnd()) {
                    return new StrictScan(value.toString(),
                        new int[] {escapeStart, escapeEnd});
                }
                int hex = hexValue(cursor.peekScalar());
                if (hex < 0) {
                    cursor.advance();
                    return new StrictScan(value.toString(),
                        new int[] {escapeStart, escapeEnd});
                }
                cursor.advance();
                low = (low << 4) | hex;
            }
            if (low < 0xDC00 || low > 0xDFFF) {
                return new StrictScan(value.toString(), new int[] {escapeStart, escapeEnd});
            }
            value.appendCodePoint(Character.toCodePoint((char) unit, (char) low));
            return new StrictScan(value.toString(), null);
        }
        if (unit >= 0xDC00 && unit <= 0xDFFF) {
            return new StrictScan(value.toString(),
                new int[] {escape.scalarOffset(), escape.scalarOffset() + 6});
        }
        value.appendCodePoint(unit);
        return new StrictScan(value.toString(), null);
    }

    private static int hexValue(int cp) {
        if (cp >= '0' && cp <= '9') {
            return cp - '0';
        }
        if (cp >= 'a' && cp <= 'f') {
            return cp - 'a' + 10;
        }
        if (cp >= 'A' && cp <= 'F') {
            return cp - 'A' + 10;
        }
        return -1;
    }

    // =========================================================================
    // Scalar index, ranges, and the E2010 conversion
    // =========================================================================

    /**
     * The scalar→UTF-16 index mapping (plus per-offset line/column)
     * re-derived with {@link ScalarSourceCursor}: {@code utf16[k]} is the
     * UTF-16 index of decoded scalar {@code k}, so a token's original
     * source slice is {@code source.substring(utf16[start], utf16[end])}.
     */
    private static final class ScalarIndex {
        private final String source;
        private final int[] utf16;
        private final int[] line;
        private final int[] column;

        ScalarIndex(String source) {
            this.source = source;
            int total = ScalarSourceCursor.scalarCount(source);
            this.utf16 = new int[total + 1];
            this.line = new int[total + 1];
            this.column = new int[total + 1];
            ScalarSourceCursor cursor = new ScalarSourceCursor(source);
            int i = 0;
            while (!cursor.atEnd()) {
                utf16[i] = cursor.index();
                line[i] = cursor.line();
                column[i] = cursor.column();
                cursor.advance();
                i++;
            }
            utf16[i] = cursor.index();
            line[i] = cursor.line();
            column[i] = cursor.column();
        }

        String slice(SourceScalarRange range) {
            return source.substring(utf16[range.startScalarOffset()],
                utf16[range.endScalarOffset()]);
        }

        SourceScalarRange range(int startScalar, int endScalar) {
            return new SourceScalarRange(line[startScalar], column[startScalar],
                line[endScalar], column[endScalar], startScalar, endScalar);
        }
    }

    /** Translates slice-local offsets inside a token to document coordinates. */
    private static SourceScalarRange tokenRange(ScalarIndex index, JsonRangeToken token,
                                                int sliceStart, int sliceEnd) {
        int base = token.range().startScalarOffset();
        return index.range(base + sliceStart, base + sliceEnd);
    }

    /** One fail-fast E2010 with a complete SOURCE scalar range. */
    private static CompilerDiagnostic e2010(String file, String message,
                                            SourceScalarRange range) {
        return CompilerDiagnostic.error(DiagnosticCode.E2010, message, new DiagnosticRange(file,
            range.startLine(), range.startColumn(), range.endLine(), range.endColumn(),
            range.startScalarOffset(), range.endScalarOffset(),
            range.endScalarOffset() - range.startScalarOffset(), RangeOrigin.SOURCE));
    }
}
