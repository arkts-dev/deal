package deal.test;

import deal.semantic.CompilerInvocation;
import deal.semantic.CompilerProfileProvider;
import deal.semantic.ReleaseConfiguration;

import java.util.ArrayList;
import java.util.List;

public final class ConformanceHarnessMetadata {

    /**
     * The classification header prefixes in metadata order — the
     * exact trimmed-prefix strings both harnesses match in
     * {@code parseMetadata}.
     */
    private static final List<String> HEADER_PREFIXES = List.of(
        "// @spec:", "// @description:", "// @expected:",
        "// @features:", "// @issue:", "// @profile:");

    /**
     * The sole corpus profile declaration the production materialization
     * admits. The value is validated, never selected: the compiler has one
     * executable profile and the metadata selects no semantic arm.
     */
    public static final String PRODUCTION_PROFILE_DECLARATION =
        "deal-v1.4-int32";

    private ConformanceHarnessMetadata() { }

    /**
     * The canonical 40-line metadata-window reader: the {@code // @profile:}
     * values it recognizes, in source order, segmenting the source on
     * {@code \R} (LF, CRLF, CR, VT, FF, NEL, LS and PS) exactly like the
     * concrete harnesses' metadata readers. The reader returns the raw
     * declared values; no caller obtains a semantic profile selection from
     * it, and {@link #validateProfileMetadata} is the admission check.
     */
    public static List<String> windowProfileDeclarations(String rawSource,
            String locator) {
        List<String> declared = new ArrayList<>();
        String[] lines = rawSource.split("\\R", -1);
        for (int i = 0; i < Math.min(lines.length, 40); i++) {
            String line = lines[i].trim();
            if (!line.startsWith("// @profile:")) {
                continue;
            }
            declared.add(line.substring("// @profile:".length()).trim());
        }
        return declared;
    }

    /**
     * Validates the corpus classification metadata of one fixture source:
     * at most one {@code // @profile:} declaration and exactly the sole
     * {@link #PRODUCTION_PROFILE_DECLARATION}. Any other declared value —
     * a legacy/v1.2 profile or an unknown one — and duplicate metadata are
     * rejected; the metadata never selects semantics.
     */
    public static void validateProfileMetadata(String rawSource,
            String locator) {
        validateDeclarations(profileDeclarations(rawSource), locator);
    }

    private static void validateDeclarations(List<String> declared,
            String locator) {
        if (declared.size() > 1) {
            throw new IllegalArgumentException(locator
                + ": duplicate @profile metadata: " + declared.size()
                + " declarations (" + String.join(", ", declared) + ")");
        }
        if (declared.size() == 1
                && !PRODUCTION_PROFILE_DECLARATION.equals(declared.get(0))) {
            throw new IllegalArgumentException(locator
                + ": non-production @profile '" + declared.get(0)
                + "'; the sole admitted profile is "
                + PRODUCTION_PROFILE_DECLARATION);
        }
    }

    /**
     * The gate-input defect name of a corpus source the production
     * materialization must never compile (CD3): a declared non-production
     * profile or malformed classification metadata. The name is part of
     * the reported outcome so the defect is identifiable without reading
     * the message body.
     */
    public static final String HARNESS_DEFECT = "HARNESS_DEFECT";

    /**
     * A named gate-input defect: the corpus input is rejected before any
     * export parsing, materialization or compilation, so the fixture can
     * never compile, pass or skip under a metadata-selected invocation.
     */
    public static final class HarnessInputDefectException
            extends IllegalArgumentException {

        private static final long serialVersionUID = 1L;

        HarnessInputDefectException(String message) {
            super(message);
        }

        HarnessInputDefectException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * The LuaJIT/JVM corpus materialization of one raw fixture source
     * (CD3): the original classification metadata is validated against
     * the production profile before anything is stripped, parsed or
     * compiled. Metadata absent or the production profile permits the
     * compile; any other declared profile, an unknown profile value, or
     * duplicate metadata is a named fatal gate-input defect naming the
     * fixture and the profile — the production targets take no
     * metadata-selected invocation, so no compile, pass or skip happens.
     *
     * <p>The validation observes <em>every</em> {@code // @profile:}
     * declaration of the whole source, not only the 40-line metadata
     * window of {@link #windowProfileDeclarations}, and it segments the
     * source with the canonical reader's own {@code \R} line terminators
     * (LF, CRLF, CR, VT, FF, NEL, LS and PS). A declaration after line
     * 40, a second declaration before it, or a declaration separated by a
     * non-CR/LF terminator would otherwise reach the metadata readers
     * while the production materialization compiled the cell without the
     * declaration.</p>
     *
     * @param rawSource the fixture file's raw text, classification headers
     *                  included
     * @param locator   the corpus-relative fixture path used in the defect
     *                  message
     * @return the header-stripped source of a production-profile corpus
     *         input
     * @throws HarnessInputDefectException for a non-production declaration
     *         or malformed metadata
     */
    public static String productionCorpusSource(String rawSource,
            String locator) {
        List<String> declared = profileDeclarations(rawSource);
        if (declared.size() > 1) {
            throw new HarnessInputDefectException(locator + ": "
                + HARNESS_DEFECT + " — duplicate corpus classification "
                + "metadata: " + declared.size() + " @profile declarations ("
                + String.join(", ", declared) + "); the LuaJIT/JVM "
                + "production materialization accepts at most one "
                + PRODUCTION_PROFILE_DECLARATION + " declaration");
        }
        if (declared.size() == 1
                && !PRODUCTION_PROFILE_DECLARATION.equals(declared.get(0))) {
            throw new HarnessInputDefectException(locator + ": "
                + HARNESS_DEFECT + " — the corpus input declares the "
                + "non-production @profile '" + declared.get(0)
                + "'; the LuaJIT/JVM production materialization accepts only "
                + PRODUCTION_PROFILE_DECLARATION
                + " (no metadata-selected invocation; no compile, no "
                + "pass, no skip)");
        }
        return stripClassificationHeaders(rawSource);
    }

    /**
     * Every {@code // @profile:} declaration of {@code source}, in source
     * order, scanned over the whole input with the canonical metadata
     * reader's line segmentation: {@link #windowProfileDeclarations} splits on
     * {@code \R} (LF, CRLF, CR, VT, FF, NEL, LS and PS), and this scan
     * uses the identical segmentation, so the result is a superset of the
     * declarations that reader can recognize (within its 40-line window,
     * or anywhere in the input). A declaration separated by any of those
     * terminators is observable by the metadata readers and therefore
     * must be observable by the production-profile validation.
     */
    public static List<String> profileDeclarations(String source) {
        List<String> values = new ArrayList<>();
        for (String line : source.split("\\R", -1)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("// @profile:")) {
                values.add(trimmed.substring("// @profile:".length()).trim());
            }
        }
        return values;
    }

    /**
     * The harness invocation record: the one release-owned production
     * invocation. Metadata cannot select alternate semantics, so the
     * harnesses compile the same record the public compiler resolves.
     */
    public static CompilerInvocation invocation() {
        return CompilerProfileProvider.resolve(
            ReleaseConfiguration.CURRENT_RELEASE_STATE,
            ReleaseConfiguration.releaseCapabilityRegistry());
    }

    /**
     * True when {@code line}'s trimmed form starts with one of the five
     * classification header prefixes (the same trimmed-prefix check
     * {@code parseMetadata} applies to raw fixture lines).
     */
    static boolean isClassificationHeaderLine(String line) {
        String trimmed = line.trim();
        for (String prefix : HEADER_PREFIXES) {
            if (trimmed.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns {@code source} with every classification-header line
     * removed.
     *
     * <p>A full line whose trimmed form starts with one of the five
     * header prefixes is dropped (line content plus its terminator).
     * LF, CRLF, and CR line endings are all handled: terminators are
     * matched per character ({@code \n}, {@code \r\n}, {@code \r}), so
     * a CR-only file is not mangled into LF lines. Every non-matching
     * line — including its original terminator characters — is appended
     * verbatim from the input, so its bytes are unchanged. A final line
     * without a terminator is still classified, and is dropped when it
     * is a header. A source without any header line is returned
     * unchanged (the same String instance).</p>
     */
    public static String stripClassificationHeaders(String source) {
        StringBuilder out = new StringBuilder(source.length());
        boolean droppedAny = false;
        int lineStart = 0;
        int i = 0;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '\n') {
                if (!isClassificationHeaderLine(
                        source.substring(lineStart, i))) {
                    out.append(source, lineStart, i + 1);
                } else {
                    droppedAny = true;
                }
                lineStart = i + 1;
                i++;
            } else if (c == '\r') {
                int contentEnd = i;
                i++;
                if (i < source.length() && source.charAt(i) == '\n') {
                    i++;
                }
                if (!isClassificationHeaderLine(
                        source.substring(lineStart, contentEnd))) {
                    out.append(source, lineStart, i);
                } else {
                    droppedAny = true;
                }
                lineStart = i;
            } else {
                i++;
            }
        }
        if (lineStart < source.length()) {
            if (!isClassificationHeaderLine(
                    source.substring(lineStart))) {
                out.append(source, lineStart, source.length());
            } else {
                droppedAny = true;
            }
        }
        return droppedAny ? out.toString() : source;
    }
}
