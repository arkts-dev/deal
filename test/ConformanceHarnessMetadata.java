package deal.test;

import deal.semantic.CompilerInvocation;
import deal.semantic.CompilerProfileProvider;
import deal.semantic.ReleaseConfiguration;
import deal.semantic.ir.ReleaseState;
import deal.semantic.ir.SemanticProfile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
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

    private ConformanceHarnessMetadata() { }

    public static SemanticProfile profileFromMetadata(String rawSource,
            String locator) {
        SemanticProfile profile = SemanticProfile.DEAL_V1_2_INT32;
        boolean found = false;
        String[] lines = rawSource.split("\\R", -1);
        for (int i = 0; i < Math.min(lines.length, 40); i++) {
            String line = lines[i].trim();
            if (!line.startsWith("// @profile:")) {
                continue;
            }
            if (found) {
                throw new IllegalArgumentException(locator
                    + ": duplicate @profile metadata");
            }
            found = true;
            String value = line.substring("// @profile:".length()).trim();
            profile = switch (value) {
                case "legacy-safe-int" -> SemanticProfile.LEGACY_SAFE_INT;
                case "deal-v1.2-int32" -> SemanticProfile.DEAL_V1_2_INT32;
                default -> throw new IllegalArgumentException(locator
                    + ": unknown @profile '" + value + "'");
            };
        }
        return profile;
    }

    public static SemanticProfile profileFromFile(Path file, String locator) {
        try {
            return profileFromMetadata(Files.readString(file), locator);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read profile metadata from "
                + file, e);
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
        SemanticProfile profile;
        try {
            profile = profileFromMetadata(rawSource, locator);
        } catch (IllegalArgumentException malformed) {
            throw new HarnessInputDefectException(locator + ": "
                + HARNESS_DEFECT + " — malformed corpus classification "
                + "metadata: " + malformed.getMessage(), malformed);
        }
        if (profile != SemanticProfile.DEAL_V1_2_INT32) {
            throw new HarnessInputDefectException(locator + ": "
                + HARNESS_DEFECT + " — the corpus input declares the "
                + profile.name() + " profile; the LuaJIT/JVM production "
                + "materialization accepts only "
                + SemanticProfile.DEAL_V1_2_INT32.name()
                + " (no metadata-selected invocation; no compile, no pass, "
                + "no skip)");
        }
        return stripClassificationHeaders(rawSource);
    }

    public static CompilerInvocation invocation(SemanticProfile profile) {
        if (profile == SemanticProfile.LEGACY_SAFE_INT) {
            return CompilerProfileProvider.resolveLegacyRegression(profile,
                ReleaseState.PRE_ACTIVATION,
                ReleaseConfiguration.releaseCapabilityRegistry());
        }
        if (profile == SemanticProfile.DEAL_V1_2_INT32) {
            return CompilerProfileProvider.resolveCommonShadow(profile,
                ReleaseState.PRE_ACTIVATION,
                ReleaseConfiguration.releaseCapabilityRegistry());
        }
        throw new IllegalArgumentException("unsupported semantic profile "
            + profile);
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
