package deal.test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Pins for the harness-only classification-header stripping seam
 * (ISSUE-0272, design fixed-name-directive-events D8 items 1–2):
 * {@link ConformanceHarnessMetadata#stripClassificationHeaders} behavior
 * plus the producer-side JVM temp-project materialization pin — the
 * three copy sites in {@code test/JvmConformanceTest.java}
 * ({@code copyHostBindings}, {@code copyTransitively},
 * {@code copyCompanionAliasIfExplicit}) must leave the production
 * orchestrator header-free sources.
 *
 * <p>JDK-only: the repository's plain main-method runner convention.</p>
 */
public class ConformanceHarnessMetadataTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        testHeaderLinesDropped();
        testExactTrimmedPrefixMatch();
        testNonHeaderLinesPreservedVerbatim();
        testLfTerminators();
        testCrlfTerminators();
        testCrTerminators();
        testEofTerminatedHeader();
        testNoHeaderReturnsInputUnchanged();
        testMaterializationPin();

        System.out.println("\nPassed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    // =========================================================================
    // Assertion helpers
    // =========================================================================

    private static void check(boolean condition, String message) {
        if (condition) {
            passed++;
        } else {
            failed++;
            System.err.println("FAIL: " + message);
        }
    }

    private static void checkEq(Object expected, Object actual,
            String message) {
        if (java.util.Objects.equals(expected, actual)) {
            passed++;
        } else {
            failed++;
            System.err.println("FAIL: " + message + " (expected <"
                + printable(expected) + ">, got <" + printable(actual)
                + ">)");
        }
    }

    private static String printable(Object value) {
        return String.valueOf(value)
            .replace("\r", "\\r").replace("\n", "\\n");
    }

    private static void deleteTree(Path root) {
        try {
            Files.walk(root).sorted(Comparator.reverseOrder())
                .forEach(f -> { try { Files.deleteIfExists(f); }
                    catch (IOException ignored) { } });
        } catch (IOException ignored) { }
    }

    /** Every classification-header line in {@code content}, with its
     * 1-based line number. */
    private static List<String> headerLinesIn(String content) {
        List<String> found = new ArrayList<>();
        String[] lines = content.split("\\r\\n|\\n|\\r", -1);
        for (int i = 0; i < lines.length; i++) {
            if (ConformanceHarnessMetadata
                    .isClassificationHeaderLine(lines[i])) {
                found.add((i + 1) + ": " + lines[i]);
            }
        }
        return found;
    }

    // =========================================================================
    // Helper behavior pins
    // =========================================================================

    /** Every one of the five header prefixes drops its full line. */
    private static void testHeaderLinesDropped() {
        String source = "// @spec: a\n"
            + "// @description: b\n"
            + "// @expected: c\n"
            + "// @features: d\n"
            + "// @issue: e\n"
            + "export function ok(): int { return 1; }\n";
        checkEq("export function ok(): int { return 1; }\n",
            ConformanceHarnessMetadata.stripClassificationHeaders(source),
            "all five header lines must be dropped");
        // No-header tail terminator alone: only the content line stays.
        String onlyHeaders = "// @spec: a\r\n// @issue: b\r\n";
        checkEq("", ConformanceHarnessMetadata.stripClassificationHeaders(
            onlyHeaders), "header-only source strips to empty");
    }

    /** Match is on the trimmed form and the exact prefix (including the
     *  colon); near-prefix forms must survive. */
    private static void testExactTrimmedPrefixMatch() {
        String source = "  // @spec: indented\r\n"
            + "// @spec:x-no-space\r\n"
            + "// @spec\r\n"
            + "//@spec: no-space-after-slash\r\n"
            + "code // @spec: inline\r\n"
            + "// @spec: not-a-header-for-stripping? dropped anyway\r\n";
        // "  // @spec: indented" (trimmed match) and
        // "// @spec:x-no-space" / "// @spec: not-a-header..." (prefix
        // includes the colon) are headers; the three near-prefix forms
        // and the inline occurrence are not.
        checkEq("// @spec\r\n"
            + "//@spec: no-space-after-slash\r\n"
            + "code // @spec: inline\r\n",
            ConformanceHarnessMetadata.stripClassificationHeaders(source),
            "exact trimmed-prefix match: only full header lines dropped");
    }

    /** Non-matching lines are preserved byte-identical, terminators
     *  included, across a mixed-terminator source. */
    private static void testNonHeaderLinesPreservedVerbatim() {
        String source = "// @expected: runtime-ok\r\n"
            + "alpha\r\n"
            + "beta\n"
            + "gamma\r"
            + "delta";
        checkEq("alpha\r\n" + "beta\n" + "gamma\r" + "delta",
            ConformanceHarnessMetadata.stripClassificationHeaders(source),
            "non-header lines must be preserved verbatim (mixed LF/CRLF/CR)");
    }

    private static void testLfTerminators() {
        String source = "// @spec: lf\n"
            + "one\n"
            + "two\n";
        checkEq("one\n" + "two\n",
            ConformanceHarnessMetadata.stripClassificationHeaders(source),
            "LF source: headers dropped, LF terminators preserved");
    }

    private static void testCrlfTerminators() {
        String source = "// @spec: crlf\r\n"
            + "one\r\n"
            + "// @description: x\r\n"
            + "two\r\n";
        checkEq("one\r\n" + "two\r\n",
            ConformanceHarnessMetadata.stripClassificationHeaders(source),
            "CRLF source: headers dropped, CRLF terminators preserved");
    }

    private static void testCrTerminators() {
        String source = "// @spec: cr\r"
            + "one\r"
            + "// @issue: y\r"
            + "two\r";
        checkEq("one\r" + "two\r",
            ConformanceHarnessMetadata.stripClassificationHeaders(source),
            "CR source: headers dropped, CR terminators preserved");
    }

    /** A header line without a trailing terminator (EOF-terminated) is
     *  still dropped; the preceding line keeps its terminator. */
    private static void testEofTerminatedHeader() {
        String source = "one\n// @spec: eof-header";
        checkEq("one\n",
            ConformanceHarnessMetadata.stripClassificationHeaders(source),
            "EOF-terminated header line must be dropped");
        String lastOnly = "// @expected: eof";
        checkEq("",
            ConformanceHarnessMetadata.stripClassificationHeaders(lastOnly),
            "single EOF-terminated header line strips to empty");
    }

    /** A source without any header line is returned unchanged. */
    private static void testNoHeaderReturnsInputUnchanged() {
        String plain = "import * as x from \"./x\"\n"
            + "export function main(): null { return null; }\n";
        check(plain == ConformanceHarnessMetadata
                .stripClassificationHeaders(plain),
            "no-header input must be the same String instance");
        checkEq("",
            ConformanceHarnessMetadata.stripClassificationHeaders(""),
            "empty input unchanged");
    }

    // =========================================================================
    // JVM materialization pin: the three producer-side copy sites leave the
    // temp project header-free for the production CompilationOrchestrator
    // =========================================================================

    private static void testMaterializationPin() throws Exception {
        Path conformanceRoot = Path.of("test/conformance")
            .toAbsolutePath().normalize();

        // (1) copyTransitively: entry + transitive companion whose
        // declaration carries a classification header.
        Path declOnly = conformanceRoot.resolve(
            "backend-runtime/modules/declaration-only-import-compile.deal");
        Path declOnlyLib = conformanceRoot.resolve(
            "backend-runtime/modules/declaration_only_lib.d.deal");

        // (2) copyCompanionAliasIfExplicit: explicit-.deal alias companion
        // carrying a classification header.
        Path aliasEntry = conformanceRoot.resolve(
            "backend-runtime/async-await/async-cross-module.deal");
        Path aliasLib = conformanceRoot.resolve(
            "backend-runtime/async-await/async_lib.deal");

        // (3) host bindings copy: host fixture whose .d.deal binding
        // carries a classification header.
        Path hostEntry = conformanceRoot.resolve(
            "backend-runtime/host-abi/host-nullable-return-ok.deal");
        Path hostBinding = conformanceRoot.resolve(
            "host-fixtures/nullable_return.d.deal");

        // The pin must be able to prove stripping actually happened:
        // every source above must carry at least one header line today.
        for (Path original : List.of(declOnly, declOnlyLib, aliasEntry,
                aliasLib, hostEntry, hostBinding)) {
            check(!headerLinesIn(Files.readString(original)).isEmpty(),
                original + " must carry a classification header "
                    + "(pin fixture drift)");
        }

        Path declOnlyProject = JvmConformanceTest.materializeProject(
            conformanceRoot, declOnly);
        try {
            assertTreeHeaderFree(declOnlyProject,
                "declaration-only companion materialization");
            // The companion is materialized under its corpus stem.
            Path companion = declOnlyProject.resolve(
                "declaration_only_lib.deal");
            check(Files.exists(companion),
                "materialized companion declaration_only_lib.deal exists");
        } finally {
            deleteTree(declOnlyProject);
        }

        Path aliasProject = JvmConformanceTest.materializeProject(
            conformanceRoot, aliasEntry);
        try {
            assertTreeHeaderFree(aliasProject,
                "explicit-.deal alias materialization");
            // Dedup/alias semantics are unchanged: the explicit
            // ./async_lib.deal spelling writes the alias-named copy and
            // the recursive walk writes the stem-named copy.
            Path stem = aliasProject.resolve("async_lib.deal");
            Path alias = aliasProject.resolve("async_lib.deal.deal");
            check(Files.exists(stem) && Files.exists(alias),
                "alias materialization keeps stem copy and alias copy");
            checkEq(Files.readString(stem), Files.readString(alias),
                "stem and alias copies carry identical stripped content");
        } finally {
            deleteTree(aliasProject);
        }

        Path hostProject = JvmConformanceTest.materializeProject(
            conformanceRoot, hostEntry);
        try {
            assertTreeHeaderFree(hostProject,
                "host-ABI bindings materialization");
            Path binding = hostProject.resolve(
                "bindings/nullable_return.d.deal");
            check(Files.exists(binding),
                "materialized host binding nullable_return.d.deal exists");
            check(!Files.readString(binding).contains("@expected"),
                "host binding must not contain the @expected header");
        } finally {
            deleteTree(hostProject);
        }
    }

    /** Every .deal artifact in the materialized temp project is free of
     *  classification-header lines. */
    private static void assertTreeHeaderFree(Path projectRoot,
            String label) throws IOException {
        List<String> offenders = new ArrayList<>();
        try (var stream = Files.walk(projectRoot)) {
            for (Path p : (Iterable<Path>) stream
                    .filter(Files::isRegularFile)::iterator) {
                if (!p.toString().endsWith(".deal")) {
                    continue;
                }
                String rel = projectRoot.relativize(p).toString();
                for (String line : headerLinesIn(Files.readString(p))) {
                    offenders.add(rel + " (" + line + ")");
                }
            }
        }
        check(offenders.isEmpty(),
            label + ": materialized temp project must contain no "
                + "classification-header line; found: " + offenders);
    }
}
