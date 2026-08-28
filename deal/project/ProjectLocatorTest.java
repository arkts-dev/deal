package deal.project;

import deal.diagnostics.CompilerDiagnostic;
import deal.diagnostics.DiagnosticNote;
import deal.diagnostics.DiagnosticRange;
import deal.diagnostics.RangeOrigin;
import deal.source.ScalarSourceCursor;
import deal.source.SourceScalarRange;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The test battery for {@link ProjectLocator} (ISSUE-0265 T4, design
 * source {@code strict-project-context-resolution-identity} D1/D4,
 * verification items 1–4, 6–7, and the combined T1+T2+T3 dependency
 * gate): entry validation (missing, directory, unreadable, NUL,
 * unrepresentable → CliDiagnostic, no context); ancestor discovery
 * (one/zero/two+ manifests, walk to the root, candidate notes);
 * strict UTF-8 REPORT-mode decode with real byte fixtures (invalid
 * continuation, truncated final sequence, overlong form, CESU-8
 * surrogate encoding → one E2010 at the first offending byte range,
 * span length 1, before any schema check; valid multi-byte scalars; a
 * literal {@code \uFFFD} escape; a leading BOM fails the strict pass);
 * override rules (valid alias lua|luajit|jvm after trim+lowercase;
 * invalid backend and empty/whitespace-only/NUL/unrepresentable output
 * overrides are CliDiagnostics; a malformed manifest fails before any
 * override is consulted); step-4 roots (conversion, non-existent roots,
 * no implicit root, duplicate normalized roots incl. symlink-alias and
 * lexical spellings at the second member's value range); step-4
 * externals (existence/readability at the declaration value range,
 * nativeLibrary classification, symlink resolution, cross-entry
 * duplicate declaration paths, stdlib-overlap rejection direct and
 * symlinked with the module-naming note); output (backend-dependent
 * defaults, manifest/CLI classification, symlinked prefixes, no
 * directory creation, the D1 step-5 failure mapping seam); stdlib
 * surface (project-local first, else the process-CWD surface, else
 * absent via a subprocess fixture); deployment identity (SHA-256 over
 * the exact bytes, whitespace-change sensitivity, symlink-spelling URI
 * equivalence); the privacy invariant (no digest or {@code file:} URI
 * text in diagnostics); determinism (identical inputs → equal
 * contexts); and the combined end-to-end fixture asserting every
 * published {@link ProjectContext} field before and after a manifest
 * byte mutation.
 *
 * <p>Runs via main() using the repository's plain check()-helper
 * convention; exits non-zero on failure. The subprocess mode
 * {@code --sub-locate <entry>} runs a single locate in a fresh JVM with
 * a controlled working directory (for the absent-stdlib-surface case)
 * and prints {@code SURFACE=<path>} / {@code SURFACE=absent}.</p>
 */
public final class ProjectLocatorTest {

    private ProjectLocatorTest() {
    }

    private static int passed = 0;
    private static int failed = 0;
    private static Path tmpDir;
    /** {@code tmpDir.toRealPath()} — the symlink-resolved expectation base. */
    private static Path realTmp;
    /** A std-free working directory for the subprocess surface probe. */
    private static Path emptyCwdDir;
    /** True when no ancestor of realTmp outside the fixtures has a deal.json. */
    private static boolean environmentClean = true;

    private static final String VALID_MANIFEST = "{\n  \"languageVersion\": \"1.2\"\n}\n";

    // =========================================================================
    // Test runner
    // =========================================================================

    public static void main(String[] args) throws Exception {
        if (args.length > 0) {
            System.exit(runSubMode(args));
        }
        tmpDir = Files.createTempDirectory("deal-locator-");
        emptyCwdDir = Files.createTempDirectory("deal-locator-cwd-");
        try {
            realTmp = tmpDir.toRealPath();
            environmentClean = !ancestorsHaveDealJson(realTmp);
            if (!environmentClean) {
                System.out.println("NOTE: a deal.json exists above the fixture root;"
                    + " discovery-multiplicity assertions that walk to the filesystem"
                    + " root are skipped.");
            }
            testEntryValidation();
            testAncestorDiscovery();
            testStrictDecodeByteFixtures();
            testStrictDecodeValidMultibyteAndLiteralUfffd();
            testBomFails();
            testOverrideRules();
            testRoots();
            testRootDuplicateDetection();
            testExternalsDeclarationValidation();
            testExternalsDuplicateDeclarations();
            testStdlibOverlapRejection();
            testOutputResolution();
            testStdlibSurface();
            testDeploymentIdentity();
            testPrivacyInvariant();
            testDeterminism();
            testNoArtifactCreation();
            testCombinedEndToEnd();
        } finally {
            cleanup();
        }
        System.out.println();
        System.out.println("Passed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.err.println("ProjectLocatorTest FAILED: " + failed + " failure(s)");
            System.exit(1);
        }
    }

    // =========================================================================
    // Subprocess mode (controlled working directory)
    // =========================================================================

    private static int runSubMode(String[] args) {
        if (args.length != 2 || !args[0].equals("--sub-locate")) {
            System.err.println("usage: ProjectLocatorTest --sub-locate <entry>");
            return 2;
        }
        ProjectLocator.LocateResult result = ProjectLocator.locate(args[1], null);
        if (result.context() == null) {
            String failure = result.e2010() != null
                ? result.e2010().message()
                : result.cliDiagnostic().message();
            System.out.println("SUB-FAILURE: " + failure);
            return 1;
        }
        ProjectContext context = result.context();
        System.out.println("SURFACE=" + (context.stdlibSurfacePath() == null
            ? "absent" : context.stdlibSurfacePath()));
        return 0;
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

    private static void fail(String message) {
        failed++;
        System.err.println("FAIL: " + message);
    }

    // =========================================================================
    // Fixture helpers
    // =========================================================================

    private static Path fixture(String name) throws IOException {
        return Files.createDirectories(tmpDir.resolve(name));
    }

    private static Path writeBytes(Path file, byte[] bytes) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, bytes);
        return file;
    }

    private static Path writeText(Path file, String text) throws IOException {
        return writeBytes(file, text.getBytes(StandardCharsets.UTF_8));
    }

    /** Writes a trivial (unparsed) entry file; locate never parses it. */
    private static Path writeEntry(Path dir, String name) throws IOException {
        return writeText(dir.resolve(name), "export function main(): null { }\n");
    }

    /** A fixture directory with a valid manifest and an entry file. */
    private static Path entryWithManifest(String name, String manifest) throws IOException {
        Path dir = fixture(name);
        writeText(dir.resolve("deal.json"), manifest);
        return writeEntry(dir, "main.deal");
    }

    /** Locates and asserts success; returns the published context. */
    private static ProjectContext locateOk(Path entry) {
        ProjectLocator.LocateResult result =
            ProjectLocator.locate(entry.toString(), null);
        if (result.context() != null) {
            passed++;
        } else {
            fail("locate should succeed for entry " + entry + " but returned: "
                + describeFailure(result));
        }
        return result.context();
    }

    /** Locates and asserts the single E2010 failure; returns it. */
    private static CompilerDiagnostic locateE2010(Path entry) {
        ProjectLocator.LocateResult result =
            ProjectLocator.locate(entry.toString(), null);
        if (result.e2010() != null && result.context() == null
                && result.cliDiagnostic() == null) {
            passed++;
        } else {
            fail("locate should fail with exactly one E2010 for entry " + entry
                + " but returned: " + describeFailure(result));
        }
        return result.e2010();
    }

    private static String describeFailure(ProjectLocator.LocateResult result) {
        if (result.context() != null) {
            return "success context for " + result.context().manifestPath();
        }
        if (result.e2010() != null) {
            return "E2010: " + result.e2010().message();
        }
        if (result.cliDiagnostic() != null) {
            return "CliDiagnostic: " + result.cliDiagnostic().message();
        }
        return "<empty result>";
    }

    /** Asserts the diagnostic carries the exact expected SOURCE range. */
    private static void checkRange(CompilerDiagnostic diagnostic, String file,
                                   SourceScalarRange expected) {
        if (diagnostic == null) {
            fail("diagnostic is null; expected range " + expected);
            return;
        }
        DiagnosticRange range = diagnostic.range();
        check(range != null, "diagnostic range present");
        if (range == null) {
            return;
        }
        check(range.origin() == RangeOrigin.SOURCE,
            "range origin SOURCE, got " + range.origin());
        check(file.equals(range.file()), "range file '" + file + "', got '" + range.file() + "'");
        check(range.startLine() == expected.startLine(),
            "range startLine " + expected.startLine() + ", got " + range.startLine());
        check(range.startColumn() == expected.startColumn(),
            "range startColumn " + expected.startColumn() + ", got " + range.startColumn());
        check(range.endLine() == expected.endLine(),
            "range endLine " + expected.endLine() + ", got " + range.endLine());
        check(range.endColumn() == expected.endColumn(),
            "range endColumn " + expected.endColumn() + ", got " + range.endColumn());
        check(range.startScalarOffset() == expected.startScalarOffset(),
            "range startScalarOffset " + expected.startScalarOffset() + ", got "
                + range.startScalarOffset());
        check(range.endScalarOffset() == expected.endScalarOffset(),
            "range endScalarOffset " + expected.endScalarOffset() + ", got "
                + range.endScalarOffset());
        check(range.scalarLength() == expected.scalarLength(),
            "range scalarLength " + expected.scalarLength() + ", got " + range.scalarLength());
    }

    /**
     * The scalar range of one ASCII needle occurrence in an ASCII/JSON
     * source text: the scalar offset equals the UTF-16 index for ASCII
     * content, and positions are derived with the cursor.
     */
    private static SourceScalarRange rangeOfOccurrence(String source, String needle,
                                                       int occurrence) {
        int index = -1;
        for (int i = 0; i < occurrence; i++) {
            index = source.indexOf(needle, index + 1);
            if (index < 0) {
                throw new IllegalStateException("needle '" + needle + "' occurrence "
                    + occurrence + " not found");
            }
        }
        return rangeOf(source, index, needle.length());
    }

    /** The scalar range {@code [start, start+length)} of an ASCII source. */
    private static SourceScalarRange rangeOf(String source, int scalarStart, int length) {
        ScalarSourceCursor cursor = new ScalarSourceCursor(source);
        while (cursor.scalarOffset() < scalarStart && !cursor.atEnd()) {
            cursor.advance();
        }
        int startLine = cursor.line();
        int startColumn = cursor.column();
        for (int i = 0; i < length && !cursor.atEnd(); i++) {
            cursor.advance();
        }
        return new SourceScalarRange(startLine, startColumn, cursor.line(), cursor.column(),
            scalarStart, scalarStart + length);
    }

    /** True when any ancestor of {@code dir} up to the root has a deal.json. */
    private static boolean ancestorsHaveDealJson(Path dir) {
        Path current = dir.getParent();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("deal.json"))) {
                return true;
            }
            current = current.getParent();
        }
        return false;
    }

    /** Removes a directory tree, symlinks included. */
    private static void deleteTree(Path root) {
        try (var stream = Files.walk(root)) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Best-effort fixture cleanup.
                }
            });
        } catch (IOException ignored) {
            // Best-effort fixture cleanup.
        }
    }

    private static void cleanup() {
        deleteTree(tmpDir);
        deleteTree(emptyCwdDir);
    }

    /**
     * Makes a file unreadable (POSIX permissions to none); returns false
     * when the platform still reports it readable (e.g. running as root),
     * in which case the caller must skip the unreadable assertions.
     */
    private static boolean makeUnreadable(Path file) throws IOException {
        Files.setPosixFilePermissions(file, Set.of());
        return !Files.isReadable(file);
    }

    private static Path makeWritable(Path file) throws IOException {
        Files.setPosixFilePermissions(file, Set.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        return file;
    }

    /** Independent SHA-256 hex (the test pins the digest input bytes). */
    private static String sha256Hex(byte[] bytes) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        byte[] hash = digest.digest(bytes);
        StringBuilder hex = new StringBuilder(64);
        for (byte b : hash) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16));
            hex.append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }

    private static final Pattern HEX64 = Pattern.compile("[0-9a-f]{64}");

    private static void checkNoPrivateIdentityText(CompilerDiagnostic diagnostic,
                                                   String what) {
        if (diagnostic == null) {
            fail(what + ": expected a diagnostic");
            return;
        }
        check(!diagnostic.message().contains("file:"),
            what + ": message must not contain a file: URI: " + diagnostic.message());
        check(!HEX64.matcher(diagnostic.message()).find(),
            what + ": message must not contain a digest: " + diagnostic.message());
        for (DiagnosticNote note : diagnostic.notes()) {
            check(!note.message().contains("file:"),
                what + ": note must not contain a file: URI: " + note.message());
            check(!HEX64.matcher(note.message()).find(),
                what + ": note must not contain a digest: " + note.message());
        }
    }

    // =========================================================================
    // Entry validation (verification 1)
    // =========================================================================

    private static void testEntryValidation() throws Exception {
        System.out.println("-- Entry validation");

        Path dir = fixture("entry");
        Path validEntry = entryWithManifest("entry-ok", VALID_MANIFEST);

        ProjectLocator.LocateResult nullResult = ProjectLocator.locate(null, null);
        check(nullResult.cliDiagnostic() != null && nullResult.context() == null
                && nullResult.e2010() == null,
            "null entry is a CliDiagnostic with no context");

        ProjectLocator.LocateResult emptyResult = ProjectLocator.locate("", null);
        check(emptyResult.cliDiagnostic() != null && emptyResult.context() == null,
            "empty entry is a CliDiagnostic with no context");

        Path missing = dir.resolve("missing.deal");
        ProjectLocator.LocateResult missingResult =
            ProjectLocator.locate(missing.toString(), null);
        check(missingResult.cliDiagnostic() != null && missingResult.context() == null,
            "missing entry is a CliDiagnostic with no context");

        ProjectLocator.LocateResult directoryResult =
            ProjectLocator.locate(dir.toString(), null);
        check(directoryResult.cliDiagnostic() != null && directoryResult.context() == null,
            "directory entry is a CliDiagnostic with no context");

        ProjectLocator.LocateResult nulResult =
            ProjectLocator.locate("main\u0000.deal", null);
        check(nulResult.cliDiagnostic() != null && nulResult.context() == null,
            "NUL-bearing entry is a CliDiagnostic with no context");

        ProjectLocator.LocateResult surrogateResult =
            ProjectLocator.locate("main\uD800.deal", null);
        check(surrogateResult.cliDiagnostic() != null && surrogateResult.context() == null,
            "unpaired-surrogate entry is a CliDiagnostic with no context");

        Path unreadable = writeEntry(fixture("entry-unreadable"), "locked.deal");
        if (makeUnreadable(unreadable)) {
            ProjectLocator.LocateResult unreadableResult =
                ProjectLocator.locate(unreadable.toString(), null);
            check(unreadableResult.cliDiagnostic() != null && unreadableResult.context() == null,
                "unreadable entry is a CliDiagnostic with no context");
        } else {
            System.out.println("NOTE: unreadable-entry assertion skipped (running as root)");
        }
        makeWritable(unreadable);

        ProjectContext context = locateOk(validEntry);
        check(context != null, "valid entry locates a context");
        checkRangeOnSuccess(context);
    }

    private static void checkRangeOnSuccess(ProjectContext context) {
        check(context.languageVersion().equals("1.2"),
            "context languageVersion is 1.2");
        check(context.projectRoot().equals(context.manifestDirectory()),
            "projectRoot equals manifestDirectory");
    }

    // =========================================================================
    // Ancestor discovery (verification 1)
    // =========================================================================

    private static void testAncestorDiscovery() throws Exception {
        System.out.println("-- Ancestor discovery");

        // One manifest in the entry's directory.
        Path entry = entryWithManifest("discovery-one", VALID_MANIFEST);
        ProjectContext context = locateOk(entry);
        Path manifestReal = entry.getParent().resolve("deal.json").toRealPath();
        check(manifestReal.toString().equals(context.manifestPath()),
            "manifestPath is the symlink-resolved manifest: " + context.manifestPath());
        check(context.manifestDirectory().equals(manifestReal.getParent().toString()),
            "manifestDirectory is the resolved manifest's parent");

        if (!environmentClean) {
            return;
        }

        // Zero manifests anywhere in the chain: one E2010 with the
        // v1.2-manifest note, anchored at the pinned synthetic shape.
        Path orphanDir = fixture("discovery-zero");
        Path orphanEntry = writeEntry(orphanDir, "main.deal");
        CompilerDiagnostic zero = locateE2010(orphanEntry);
        if (zero != null) {
            check(zero.range().origin() == RangeOrigin.SYNTHETIC
                    && zero.range().isCanonicalSynthetic(),
                "zero-manifest E2010 uses the canonical synthetic shape");
            check(zero.range().file().equals(orphanEntry.toString()),
                "zero-manifest E2010 anchors at the entry-file value");
            boolean manifestNote = zero.notes().stream().anyMatch(note ->
                note.message().contains("languageVersion \"1.2\""));
            check(manifestNote, "zero-manifest E2010 carries the v1.2-manifest note");
        }

        // Two manifests (entry dir + its parent): the walk continues to
        // the root after a hit, and every candidate is reported.
        Path nested = fixture("discovery-two/nested");
        Path nestedEntry = writeEntry(nested, "main.deal");
        writeText(nested.resolve("deal.json"), VALID_MANIFEST);
        Path parentManifest = writeText(tmpDir.resolve("discovery-two/deal.json"),
            VALID_MANIFEST);
        CompilerDiagnostic multiple = locateE2010(nestedEntry);
        if (multiple != null) {
            check(multiple.range().origin() == RangeOrigin.SYNTHETIC,
                "multiple-manifest E2010 uses the synthetic shape");
            List<String> candidateNotes = new ArrayList<>();
            for (DiagnosticNote note : multiple.notes()) {
                if (note.message().startsWith("candidate manifest: ")) {
                    candidateNotes.add(note.message().substring(
                        "candidate manifest: ".length()));
                }
            }
            check(candidateNotes.size() == 2,
                "two candidate notes, got " + candidateNotes);
            if (candidateNotes.size() == 2) {
                Path nestedReal = nested.resolve("deal.json").toRealPath();
                Path parentReal = parentManifest.toRealPath();
                check(candidateNotes.get(0).equals(nestedReal.toString())
                        && candidateNotes.get(1).equals(parentReal.toString()),
                    "candidates reported nearest-first: " + candidateNotes);
            }
        }

        // Three manifests: the walk continues past the second hit.
        Path deep = fixture("discovery-three/a/b");
        Path deepEntry = writeEntry(deep, "main.deal");
        writeText(deep.resolve("deal.json"), VALID_MANIFEST);
        writeText(tmpDir.resolve("discovery-three/a/deal.json"), VALID_MANIFEST);
        writeText(tmpDir.resolve("discovery-three/deal.json"), VALID_MANIFEST);
        CompilerDiagnostic three = locateE2010(deepEntry);
        if (three != null) {
            long candidateCount = three.notes().stream().filter(note ->
                note.message().startsWith("candidate manifest: ")).count();
            check(candidateCount == 3, "three candidate notes, got " + candidateCount);
        }
    }

    // =========================================================================
    // Strict decode with real byte fixtures (verification 2)
    // =========================================================================

    private static final String DECODE_BASE =
        "{\n  \"languageVersion\": \"1.2\",\n  \"output\": \"build/lua\",\n"
            + "  \"moduleRoots\": [\"src\"]\n}\n";

    private static byte[] corrupted(byte[] base, int offset, byte... injected) {
        byte[] result = new byte[base.length + injected.length];
        System.arraycopy(base, 0, result, 0, offset);
        System.arraycopy(injected, 0, result, offset, injected.length);
        System.arraycopy(base, offset, result, offset + injected.length,
            base.length - offset);
        return result;
    }

    private static void checkDecodeFailure(byte[] manifestBytes, int malformedOffset,
                                           String kind) throws Exception {
        Path dir = fixture("decode-" + kind + "-" + malformedOffset);
        Path entry = writeEntry(dir, "main.deal");
        Path manifestFile = writeBytes(dir.resolve("deal.json"), manifestBytes);
        CompilerDiagnostic diagnostic = locateE2010(entry);
        if (diagnostic == null) {
            return;
        }
        String prefix = new String(manifestBytes, 0, malformedOffset, StandardCharsets.UTF_8);
        ScalarSourceCursor cursor = new ScalarSourceCursor(prefix);
        while (!cursor.atEnd()) {
            cursor.advance();
        }
        SourceScalarRange expected = new SourceScalarRange(cursor.line(), cursor.column(),
            cursor.line(), cursor.column() + 1, cursor.scalarOffset(),
            cursor.scalarOffset() + 1);
        checkRange(diagnostic, manifestFile.toRealPath().toString(), expected);
        check(diagnostic.range().scalarLength() == 1,
            "decode E2010 span length 1 for " + kind);
        check(diagnostic.message().contains("UTF-8"),
            "decode E2010 message names UTF-8: " + diagnostic.message());
        check(diagnostic.message().contains("byte offset " + malformedOffset),
            "decode E2010 message names the byte offset: " + diagnostic.message());
    }

    private static void testStrictDecodeByteFixtures() throws Exception {
        System.out.println("-- Strict UTF-8 decode byte fixtures");

        byte[] base = DECODE_BASE.getBytes(StandardCharsets.UTF_8);

        // 1. Invalid continuation byte: 0xE2 followed by 0x41.
        int offset = 12;
        checkDecodeFailure(corrupted(base, offset, (byte) 0xE2, (byte) 0x41),
            offset, "invalid-continuation");

        // 2. Truncated final sequence: a lone 0xC3 at end of input.
        int endOffset = base.length;
        checkDecodeFailure(corrupted(base, endOffset, (byte) 0xC3),
            endOffset, "truncated-final");

        // 3. Overlong form: 0xC0 0x80.
        checkDecodeFailure(corrupted(base, offset, (byte) 0xC0, (byte) 0x80),
            offset, "overlong");

        // 4. CESU-8 surrogate-range encoding: 0xED 0xA0 0x80.
        checkDecodeFailure(corrupted(base, offset, (byte) 0xED, (byte) 0xA0, (byte) 0x80),
            offset, "cesu-8");

        // Each is exactly one E2010 before any schema check: the base
        // content is schema-valid, so the failure must be the decode.
        // (The locateE2010 helper asserts exactly-one-failure per call.)

        // A malformed byte in the middle of a later line: line/column
        // anchor derivation across a newline.
        int thirdLine = DECODE_BASE.indexOf("moduleRoots");
        checkDecodeFailure(corrupted(base, thirdLine, (byte) 0xED, (byte) 0xA0, (byte) 0x80),
            thirdLine, "cesu-8-line3");
    }

    private static void testStrictDecodeValidMultibyteAndLiteralUfffd() throws Exception {
        System.out.println("-- Strict decode: valid multibyte and literal \\uFFFD");

        String manifest = "{\n  \"languageVersion\": \"1.2\",\n"
            + "  \"output\": \"\u00E4\u00F6\u00FC/\uD83D\uDE00\",\n"
            + "  \"moduleRoots\": [\"s\u0155c\"]\n}\n";
        Path entry = entryWithManifest("decode-valid-multibyte", manifest);
        ProjectContext context = locateOk(entry);
        if (context != null) {
            check(context.outputPath().decodedText().equals(
                    "\u00E4\u00F6\u00FC/\uD83D\uDE00"),
                "multi-byte UTF-8 scalars decode correctly into the output value");
            check(context.configuredModuleRoots().size() == 1
                    && context.configuredModuleRoots().get(0).configuredText()
                        .equals("s\u0155c"),
                "multi-byte UTF-8 scalars decode correctly into the root text");
        }

        // A literal \uFFFD escape is valid and decodes to exactly one
        // U+FFFD; no replacement character is produced anywhere.
        String literal = "{\n  \"languageVersion\": \"1.2\",\n"
            + "  \"output\": \"\\uFFFD-ok\"\n}\n";
        Path literalEntry = entryWithManifest("decode-literal-ufffd", literal);
        ProjectContext literalContext = locateOk(literalEntry);
        if (literalContext != null) {
            String value = literalContext.outputPath().decodedText();
            check(value.equals("\uFFFD-ok"),
                "literal \\uFFFD escape decodes to one U+FFFD, got: " + value);
            check(value.indexOf('\uFFFD') == 0
                    && value.indexOf('\uFFFD', 1) < 0,
                "exactly one U+FFFD produced; no replacement anywhere");
        }
    }

    private static void testBomFails() throws Exception {
        System.out.println("-- Leading UTF-8 BOM fails the strict pass");

        byte[] bom = new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] content = VALID_MANIFEST.getBytes(StandardCharsets.UTF_8);
        byte[] withBom = corrupted(content, 0, bom);
        Path dir = fixture("bom");
        Path entry = writeEntry(dir, "main.deal");
        Path manifestFile = writeBytes(dir.resolve("deal.json"), withBom);
        CompilerDiagnostic diagnostic = locateE2010(entry);
        if (diagnostic != null) {
            check(diagnostic.range().origin() == RangeOrigin.SOURCE,
                "BOM E2010 is a SOURCE range in the manifest");
            check(diagnostic.range().file().equals(manifestFile.toRealPath().toString()),
                "BOM E2010 anchors in the manifest file");
            check(diagnostic.range().startScalarOffset() == 0,
                "BOM E2010 anchors at the first scalar");
        }
    }

    // =========================================================================
    // Override rules (verification 3)
    // =========================================================================

    private static final String OVERRIDE_MANIFEST =
        "{\n  \"languageVersion\": \"1.2\",\n  \"backend\": \"jvm\",\n"
            + "  \"output\": \"manifest-out\"\n}\n";

    private static void testOverrideRules() throws Exception {
        System.out.println("-- Override rules");

        Path entry = entryWithManifest("overrides", OVERRIDE_MANIFEST);

        ProjectLocator.LocateResult lua = ProjectLocator.locate(entry.toString(),
            new CliOverrides("lua", null));
        check(lua.context() != null && lua.context().backend().equals("luajit"),
            "CLI alias 'lua' overrides the manifest backend to luajit");
        check(lua.context() != null
                && lua.context().outputPath().decodedText().equals("manifest-out"),
            "manifest output still wins when only the backend is overridden");

        ProjectLocator.LocateResult mixedCase = ProjectLocator.locate(entry.toString(),
            new CliOverrides(" LuaJIT ", null));
        check(mixedCase.context() != null && mixedCase.context().backend().equals("luajit"),
            "CLI alias is trim + lowercase");

        ProjectLocator.LocateResult jvm = ProjectLocator.locate(entry.toString(),
            new CliOverrides("jvm", null));
        check(jvm.context() != null && jvm.context().backend().equals("jvm"),
            "CLI alias 'jvm' matches the manifest backend");

        for (String invalidAlias : new String[]{"js", "unknown", "  ", "luaa"}) {
            ProjectLocator.LocateResult invalid = ProjectLocator.locate(entry.toString(),
                new CliOverrides(invalidAlias, null));
            check(invalid.cliDiagnostic() != null && invalid.context() == null
                    && invalid.e2010() == null,
                "invalid CLI backend alias '" + invalidAlias + "' is a CliDiagnostic");
        }

        ProjectLocator.LocateResult cliOut = ProjectLocator.locate(entry.toString(),
            new CliOverrides(null, "  cli-out  "));
        check(cliOut.context() != null
                && cliOut.context().outputPath().decodedText().equals("cli-out"),
            "trimmed CLI output overrides the manifest output");
        check(cliOut.context() != null
                && cliOut.context().outputPath().source()
                    == OutputConfigResolver.Source.CLI,
            "overriding output is CLI-source");
        check(cliOut.context() != null
                && cliOut.context().outputPath().kind()
                    == OutputConfigResolver.Kind.MANIFEST_RELATIVE_PATH,
            "relative CLI output is CWD-relative (MANIFEST_RELATIVE_PATH kind)");
        if (cliOut.context() != null) {
            String expected = join(Path.of("").toAbsolutePath().toString(), "cli-out");
            ProtectedPathOps.PathResult expectedPath =
                ProtectedPathOps.normalizePrefixResolved(expected);
            check(expectedPath instanceof ProtectedPathOps.PathResult.Success
                    && ((ProtectedPathOps.PathResult.Success) expectedPath)
                        .resolvedPath().toString()
                        .equals(cliOut.context().outputPath().absoluteNormalizedPath()),
                "CLI output resolves from the process CWD");
        }

        Path absOut = tmpDir.resolve("absolute-cli-out");
        ProjectLocator.LocateResult cliAbs = ProjectLocator.locate(entry.toString(),
            new CliOverrides(null, absOut.toString()));
        check(cliAbs.context() != null
                && cliAbs.context().outputPath().kind()
                    == OutputConfigResolver.Kind.ABSOLUTE_PATH,
            "leading-/ CLI output is ABSOLUTE_PATH");

        for (String invalidOutput : new String[]{"", "   ", "\t\n"}) {
            ProjectLocator.LocateResult invalid = ProjectLocator.locate(entry.toString(),
                new CliOverrides(null, invalidOutput));
            check(invalid.cliDiagnostic() != null && invalid.context() == null,
                "empty/whitespace-only CLI output override is a CliDiagnostic");
        }
        ProjectLocator.LocateResult nulOut = ProjectLocator.locate(entry.toString(),
            new CliOverrides(null, "a\u0000b"));
        check(nulOut.cliDiagnostic() != null && nulOut.context() == null,
            "NUL-bearing CLI output override is a CliDiagnostic");
        ProjectLocator.LocateResult surrogateOut = ProjectLocator.locate(entry.toString(),
            new CliOverrides(null, "a\uD800b"));
        check(surrogateOut.cliDiagnostic() != null && surrogateOut.context() == null,
            "unpaired-surrogate CLI output override is a CliDiagnostic");

        // A malformed manifest fails E2010 before any override is
        // consulted — valid and invalid overrides alike.
        Path malformedEntry = entryWithManifest("overrides-malformed",
            "{\n  \"languageVersion\": \"1.1\"\n}\n");
        ProjectLocator.LocateResult malformedValidOverride =
            ProjectLocator.locate(malformedEntry.toString(),
                new CliOverrides("lua", "cli-out"));
        check(malformedValidOverride.e2010() != null && malformedValidOverride.context() == null
                && malformedValidOverride.cliDiagnostic() == null,
            "a malformed manifest fails E2010 even with valid overrides");
        ProjectLocator.LocateResult malformedInvalidOverride =
            ProjectLocator.locate(malformedEntry.toString(),
                new CliOverrides("js", ""));
        check(malformedInvalidOverride.e2010() != null
                && malformedInvalidOverride.cliDiagnostic() == null,
            "a malformed manifest fails E2010 before any override is consulted");
    }

    private static String join(String base, String relative) {
        if (base.isEmpty() || base.endsWith("/")) {
            return base + relative;
        }
        return base + "/" + relative;
    }

    // =========================================================================
    // Step 4(a): roots (verification 4)
    // =========================================================================

    private static void testRoots() throws Exception {
        System.out.println("-- Roots: conversion, non-existence, no implicit root");

        Path dir = fixture("roots");
        Files.createDirectories(dir.resolve("src"));
        writeEntry(dir, "main.deal");
        Path manifestFile = writeText(dir.resolve("deal.json"),
            "{\n  \"languageVersion\": \"1.2\",\n"
                + "  \"moduleRoots\": [\"src\", \"lib\"]\n}\n");
        ProjectContext context = locateOk(dir.resolve("main.deal"));
        if (context != null) {
            check(context.configuredModuleRoots().size() == 2,
                "two configured roots published");
            if (context.configuredModuleRoots().size() == 2) {
                ConfiguredModuleRoot src = context.configuredModuleRoots().get(0);
                ConfiguredModuleRoot lib = context.configuredModuleRoots().get(1);
                check(src.configuredText().equals("src"),
                    "first root text is the decoded manifest spelling");
                String srcReal = dir.resolve("src").toRealPath().toString();
                check(src.absoluteNormalizedPath().equals(srcReal),
                    "existing root resolves fully: " + src.absoluteNormalizedPath());
                check(src.sourceRange() != null, "root carries its value range");
                check(lib.configuredText().equals("lib"),
                    "second root text is the decoded manifest spelling");
                check(lib.absoluteNormalizedPath().equals(
                        manifestFile.getParent().toRealPath().resolve("lib").toString()),
                    "non-existent root is prefix-resolved without failure: "
                        + lib.absoluteNormalizedPath());
                check(lib.sourceRange() != null, "non-existent root carries its range");
            }
        }

        // No implicit root: an absent moduleRoots publishes an empty list.
        Path noRootsEntry = entryWithManifest("roots-no-implicit", VALID_MANIFEST);
        ProjectContext noRoots = locateOk(noRootsEntry);
        if (noRoots != null) {
            check(noRoots.configuredModuleRoots().isEmpty(),
                "no implicit root is added when moduleRoots is absent");
            check(noRoots.configuredModuleRoots().stream().noneMatch(root ->
                    root.absoluteNormalizedPath().equals(noRoots.manifestDirectory())),
                "the manifest directory is never an implicit root");
        }
    }

    private static void testRootDuplicateDetection() throws Exception {
        System.out.println("-- Roots: normalized-duplicate detection");

        // Exact duplicate spelling.
        String manifest = "{\n  \"languageVersion\": \"1.2\",\n"
            + "  \"moduleRoots\": [\"src\", \"src\"]\n}\n";
        Path dir = fixture("roots-dup");
        Files.createDirectories(dir.resolve("src"));
        writeEntry(dir, "main.deal");
        Path manifestFile = writeText(dir.resolve("deal.json"), manifest);
        CompilerDiagnostic diagnostic = locateE2010(dir.resolve("main.deal"));
        if (diagnostic != null) {
            checkRange(diagnostic, manifestFile.toRealPath().toString(),
                rangeOfOccurrence(manifest, "\"src\"", 2));
        }

        // Symlink-alias spelling: both roots resolve to one directory.
        String aliasManifest = "{\n  \"languageVersion\": \"1.2\",\n"
            + "  \"moduleRoots\": [\"src\", \"alias\"]\n}\n";
        Path aliasDir = fixture("roots-dup-symlink");
        Files.createDirectories(aliasDir.resolve("src"));
        Files.createSymbolicLink(aliasDir.resolve("alias"),
            Path.of("src"));
        writeEntry(aliasDir, "main.deal");
        Path aliasManifestFile = writeText(aliasDir.resolve("deal.json"), aliasManifest);
        CompilerDiagnostic aliasDiagnostic = locateE2010(aliasDir.resolve("main.deal"));
        if (aliasDiagnostic != null) {
            checkRange(aliasDiagnostic, aliasManifestFile.toRealPath().toString(),
                rangeOfOccurrence(aliasManifest, "\"alias\"", 1));
        }

        // Lexical-normalization spelling: "sub/../src" equals "src".
        String lexicalManifest = "{\n  \"languageVersion\": \"1.2\",\n"
            + "  \"moduleRoots\": [\"src\", \"sub/../src\"]\n}\n";
        Path lexicalDir = fixture("roots-dup-lexical");
        Files.createDirectories(lexicalDir.resolve("src"));
        Files.createDirectories(lexicalDir.resolve("sub"));
        writeEntry(lexicalDir, "main.deal");
        Path lexicalManifestFile = writeText(lexicalDir.resolve("deal.json"), lexicalManifest);
        CompilerDiagnostic lexicalDiagnostic = locateE2010(lexicalDir.resolve("main.deal"));
        if (lexicalDiagnostic != null) {
            checkRange(lexicalDiagnostic, lexicalManifestFile.toRealPath().toString(),
                rangeOfOccurrence(lexicalManifest, "\"sub/../src\"", 1));
        }

        // Non-existent duplicate spellings are duplicates too (existence
        // is never required).
        String ghostManifest = "{\n  \"languageVersion\": \"1.2\",\n"
            + "  \"moduleRoots\": [\"ghost\", \"ghost\"]\n}\n";
        Path ghostDir = fixture("roots-dup-ghost");
        writeEntry(ghostDir, "main.deal");
        Path ghostManifestFile = writeText(ghostDir.resolve("deal.json"), ghostManifest);
        CompilerDiagnostic ghostDiagnostic = locateE2010(ghostDir.resolve("main.deal"));
        if (ghostDiagnostic != null) {
            checkRange(ghostDiagnostic, ghostManifestFile.toRealPath().toString(),
                rangeOfOccurrence(ghostManifest, "\"ghost\"", 2));
        }
    }

    // =========================================================================
    // Step 4(b): externals declarations (verification 4)
    // =========================================================================

    private static void testExternalsDeclarationValidation() throws Exception {
        System.out.println("-- Externals: declaration validation");

        Path dir = fixture("externals");
        writeEntry(dir, "main.deal");
        Path declaration = writeText(dir.resolve("decl.d.deal"),
            "export function ping(): null\n");
        Files.createDirectories(dir.resolve("libs"));
        writeText(dir.resolve("libs/libhost.so"), "lib");
        Path manifestFile = writeText(dir.resolve("deal.json"),
            "{\n  \"languageVersion\": \"1.2\",\n"
                + "  \"externals\": {\n"
                + "    \"host\": {\n"
                + "      \"declaration\": \"decl.d.deal\",\n"
                + "      \"nativeLibrary\": \"libs/libhost.so\"\n"
                + "    }\n"
                + "  }\n}\n");
        ProjectContext context = locateOk(dir.resolve("main.deal"));
        if (context != null) {
            check(context.externals().size() == 1
                    && context.externals().containsKey("host"),
                "externals maps the raw import specifier to the validated entry");
            ExternalEntry entry = context.externals().get("host");
            if (entry != null) {
                check(entry.rawImportSpecifier().equals("host"),
                    "raw import specifier preserved exactly");
                check(entry.declarationPath().absoluteNormalizedPath().equals(
                        declaration.toRealPath().toString()),
                    "declaration path is fully symlink-resolved");
                check(entry.declarationPath().sourceRange() != null,
                    "declaration path carries its value range");
                check(entry.nativeLibrary() != null
                        && entry.nativeLibrary().kind()
                            == NativeLibraryRef.Kind.MANIFEST_RELATIVE_PATH
                        && entry.nativeLibrary().loaderText().equals("libs/libhost.so"),
                    "manifest-relative nativeLibrary classification preserved");
                check(entry.sourceRange() != null, "entry carries its value range");
            }
        }

        // Missing declaration file: E2010 at the declaration value range.
        String missingManifest = "{\n  \"languageVersion\": \"1.2\",\n"
            + "  \"externals\": {\n"
            + "    \"host\": {\"declaration\": \"missing.d.deal\"}\n"
            + "  }\n}\n";
        Path missingDir = fixture("externals-missing");
        writeEntry(missingDir, "main.deal");
        Path missingManifestFile = writeText(missingDir.resolve("deal.json"), missingManifest);
        CompilerDiagnostic missing = locateE2010(missingDir.resolve("main.deal"));
        if (missing != null) {
            checkRange(missing, missingManifestFile.toRealPath().toString(),
                rangeOfOccurrence(missingManifest, "\"missing.d.deal\"", 1));
        }

        // Declaration is a directory: E2010 at the declaration range.
        String dirDeclarationManifest = "{\n  \"languageVersion\": \"1.2\",\n"
            + "  \"externals\": {\n"
            + "    \"host\": {\"declaration\": \"as-dir.d.deal\"}\n"
            + "  }\n}\n";
        Path dirDeclarationDir = fixture("externals-dir-decl");
        Files.createDirectories(dirDeclarationDir.resolve("as-dir.d.deal"));
        writeEntry(dirDeclarationDir, "main.deal");
        Path dirDeclarationManifestFile = writeText(dirDeclarationDir.resolve("deal.json"),
            dirDeclarationManifest);
        CompilerDiagnostic dirDeclaration = locateE2010(dirDeclarationDir.resolve("main.deal"));
        if (dirDeclaration != null) {
            checkRange(dirDeclaration, dirDeclarationManifestFile.toRealPath().toString(),
                rangeOfOccurrence(dirDeclarationManifest, "\"as-dir.d.deal\"", 1));
        }

        // Unreadable declaration file: E2010 at the declaration range.
        Path unreadableDir = fixture("externals-unreadable");
        writeEntry(unreadableDir, "main.deal");
        Path unreadableDeclaration = writeText(unreadableDir.resolve("locked.d.deal"),
            "export function x(): null\n");
        Path unreadableManifestFile = writeText(unreadableDir.resolve("deal.json"),
            "{\n  \"languageVersion\": \"1.2\",\n"
                + "  \"externals\": {\n"
                + "    \"host\": {\"declaration\": \"locked.d.deal\"}\n"
                + "  }\n}\n");
        if (makeUnreadable(unreadableDeclaration)) {
            CompilerDiagnostic unreadable = locateE2010(unreadableDir.resolve("main.deal"));
            if (unreadable != null) {
                checkRange(unreadable, unreadableManifestFile.toRealPath().toString(),
                    rangeOfOccurrence("{\n  \"languageVersion\": \"1.2\",\n"
                            + "  \"externals\": {\n"
                            + "    \"host\": {\"declaration\": \"locked.d.deal\"}\n"
                            + "  }\n}\n",
                        "\"locked.d.deal\"", 1));
            }
        } else {
            System.out.println("NOTE: unreadable-declaration assertion skipped"
                + " (running as root)");
        }
        makeWritable(unreadableDeclaration);

        // A symlinked declaration spelling resolves to the target file.
        Path linkDir = fixture("externals-link");
        writeEntry(linkDir, "main.deal");
        Path target = writeText(linkDir.resolve("target.d.deal"), "export function t(): null\n");
        Files.createSymbolicLink(linkDir.resolve("link.d.deal"), Path.of("target.d.deal"));
        writeText(linkDir.resolve("deal.json"),
            "{\n  \"languageVersion\": \"1.2\",\n"
                + "  \"externals\": {\n"
                + "    \"host\": {\"declaration\": \"link.d.deal\"}\n"
                + "  }\n}\n");
        ProjectContext linkContext = locateOk(linkDir.resolve("main.deal"));
        if (linkContext != null) {
            ExternalEntry entry = linkContext.externals().get("host");
            check(entry != null && entry.declarationPath().absoluteNormalizedPath().equals(
                    target.toRealPath().toString()),
                "symlinked declaration spelling resolves to the target canonical path");
        }

        // Externals member order is preserved in the published map.
        Path orderDir = fixture("externals-order");
        writeEntry(orderDir, "main.deal");
        writeText(orderDir.resolve("a.d.deal"), "export function a(): null\n");
        writeText(orderDir.resolve("b.d.deal"), "export function b(): null\n");
        writeText(orderDir.resolve("deal.json"),
            "{\n  \"languageVersion\": \"1.2\",\n"
                + "  \"externals\": {\n"
                + "    \"first\": {\"declaration\": \"a.d.deal\"},\n"
                + "    \"second\": {\"declaration\": \"b.d.deal\"}\n"
                + "  }\n}\n");
        ProjectContext orderContext = locateOk(orderDir.resolve("main.deal"));
        if (orderContext != null) {
            check(new ArrayList<>(orderContext.externals().keySet())
                    .equals(List.of("first", "second")),
                "externals map preserves member order");
        }

        // The completed entry preserves an absolute nativeLibrary too.
        Path absDir = fixture("externals-abs-native");
        writeEntry(absDir, "main.deal");
        writeText(absDir.resolve("d.d.deal"), "export function d(): null\n");
        writeText(absDir.resolve("deal.json"),
            "{\n  \"languageVersion\": \"1.2\",\n"
                + "  \"externals\": {\n"
                + "    \"host\": {\"declaration\": \"d.d.deal\","
                + " \"nativeLibrary\": \"/abs/lib.so\"}\n"
                + "  }\n}\n");
        ProjectContext absContext = locateOk(absDir.resolve("main.deal"));
        if (absContext != null) {
            ExternalEntry entry = absContext.externals().get("host");
            check(entry != null && entry.nativeLibrary() != null
                    && entry.nativeLibrary().kind() == NativeLibraryRef.Kind.ABSOLUTE_PATH,
                "absolute nativeLibrary classification preserved");
        }
    }

    private static void testExternalsDuplicateDeclarations() throws Exception {
        System.out.println("-- Externals: cross-entry duplicate declarations");

        // Two lexical spellings of one file.
        String manifest = "{\n  \"languageVersion\": \"1.2\",\n"
            + "  \"externals\": {\n"
            + "    \"a\": {\"declaration\": \"decl.d.deal\"},\n"
            + "    \"b\": {\"declaration\": \"sub/../decl.d.deal\"}\n"
            + "  }\n}\n";
        Path dir = fixture("externals-dup");
        writeEntry(dir, "main.deal");
        Files.createDirectories(dir.resolve("sub"));
        writeText(dir.resolve("decl.d.deal"), "export function d(): null\n");
        Path manifestFile = writeText(dir.resolve("deal.json"), manifest);
        CompilerDiagnostic diagnostic = locateE2010(dir.resolve("main.deal"));
        if (diagnostic != null) {
            checkRange(diagnostic, manifestFile.toRealPath().toString(),
                rangeOfOccurrence(manifest, "\"sub/../decl.d.deal\"", 1));
            check(diagnostic.message().contains("'a'")
                    && diagnostic.message().contains("'b'"),
                "duplicate message names both specifiers: " + diagnostic.message());
        }

        // A symlinked spelling of one file.
        String linkManifest = "{\n  \"languageVersion\": \"1.2\",\n"
            + "  \"externals\": {\n"
            + "    \"a\": {\"declaration\": \"decl.d.deal\"},\n"
            + "    \"b\": {\"declaration\": \"link.d.deal\"}\n"
            + "  }\n}\n";
        Path linkDir = fixture("externals-dup-link");
        writeEntry(linkDir, "main.deal");
        writeText(linkDir.resolve("decl.d.deal"), "export function d(): null\n");
        Files.createSymbolicLink(linkDir.resolve("link.d.deal"), Path.of("decl.d.deal"));
        Path linkManifestFile = writeText(linkDir.resolve("deal.json"), linkManifest);
        CompilerDiagnostic linkDiagnostic = locateE2010(linkDir.resolve("main.deal"));
        if (linkDiagnostic != null) {
            checkRange(linkDiagnostic, linkManifestFile.toRealPath().toString(),
                rangeOfOccurrence(linkManifest, "\"link.d.deal\"", 1));
        }
    }

    private static void testStdlibOverlapRejection() throws Exception {
        System.out.println("-- Externals: stdlib-overlap rejection");

        // Project-local std/ with all six spec-listed files; an externals
        // declaration naming std/console.d.deal directly is E2010 with
        // the module-naming note.
        String manifest = "{\n  \"languageVersion\": \"1.2\",\n"
            + "  \"externals\": {\n"
            + "    \"host\": {\"declaration\": \"std/console.d.deal\"}\n"
            + "  }\n}\n";
        Path dir = fixture("stdlib-overlap-direct");
        createSixStdlibFiles(dir);
        writeEntry(dir, "main.deal");
        Path manifestFile = writeText(dir.resolve("deal.json"), manifest);
        CompilerDiagnostic direct = locateE2010(dir.resolve("main.deal"));
        if (direct != null) {
            checkRange(direct, manifestFile.toRealPath().toString(),
                rangeOfOccurrence(manifest, "\"std/console.d.deal\"", 1));
            boolean moduleNote = direct.notes().stream().anyMatch(note ->
                note.message().contains("console")
                    && note.message().contains("docs/spec-v1.2.md:1890")
                    && note.message().contains("language distribution"));
            check(moduleNote, "stdlib-overlap note names the module and the"
                + " language-distribution rule");
        }

        // A symlinked spelling of the same pinned file fires too.
        String linkManifest = "{\n  \"languageVersion\": \"1.2\",\n"
            + "  \"externals\": {\n"
            + "    \"host\": {\"declaration\": \"alias-console.d.deal\"}\n"
            + "  }\n}\n";
        Path linkDir = fixture("stdlib-overlap-link");
        createSixStdlibFiles(linkDir);
        Files.createSymbolicLink(linkDir.resolve("alias-console.d.deal"),
            Path.of("std/console.d.deal"));
        writeEntry(linkDir, "main.deal");
        Path linkManifestFile = writeText(linkDir.resolve("deal.json"), linkManifest);
        CompilerDiagnostic linked = locateE2010(linkDir.resolve("main.deal"));
        if (linked != null) {
            checkRange(linked, linkManifestFile.toRealPath().toString(),
                rangeOfOccurrence(linkManifest, "\"alias-console.d.deal\"", 1));
            boolean moduleNote = linked.notes().stream().anyMatch(note ->
                note.message().contains("console")
                    && note.message().contains("docs/spec-v1.2.md:1890"));
            check(moduleNote, "symlinked stdlib-overlap carries the module note");
        }

        // A non-spec-listed .d.deal physically inside std/ is NOT an
        // overlap: locate succeeds.
        String otherManifest = "{\n  \"languageVersion\": \"1.2\",\n"
            + "  \"externals\": {\n"
            + "    \"host\": {\"declaration\": \"std/other.d.deal\"}\n"
            + "  }\n}\n";
        Path otherDir = fixture("stdlib-overlap-other");
        createSixStdlibFiles(otherDir);
        writeText(otherDir.resolve("std/other.d.deal"), "export function o(): null\n");
        writeEntry(otherDir, "main.deal");
        writeText(otherDir.resolve("deal.json"), otherManifest);
        ProjectContext otherContext = locateOk(otherDir.resolve("main.deal"));
        if (otherContext != null) {
            check(otherContext.externals().containsKey("host"),
                "a non-spec-listed std/*.d.deal declaration is not an overlap");
        }
    }

    /** Creates the six spec-listed stdlib declaration files under dir/std. */
    private static void createSixStdlibFiles(Path dir) throws IOException {
        for (String module : ProjectLocator.SPEC_STDLIB_MODULES) {
            writeText(dir.resolve("std").resolve(module + ".d.deal"),
                "// pinned stdlib declaration stub for " + module + "\n");
        }
    }

    // =========================================================================
    // Output resolution (verification 4)
    // =========================================================================

    private static void testOutputResolution() throws Exception {
        System.out.println("-- Output resolution");

        // Default per effective backend; the fresh project's default path
        // does not exist at locate and no directory is created.
        Path luaDir = fixture("output-default-lua");
        Path luaEntry = entryWithManifest("output-default-lua-proj", VALID_MANIFEST);
        ProjectContext luaContext = locateOk(luaEntry);
        if (luaContext != null) {
            OutputConfigResolver.OutputRef luaOutput = luaContext.outputPath();
            check(luaOutput.decodedText().equals("build/lua"),
                "LuaJIT default output text");
            check(luaOutput.kind() == OutputConfigResolver.Kind.MANIFEST_RELATIVE_PATH,
                "default output is manifest-relative");
            check(luaOutput.source() == OutputConfigResolver.Source.MANIFEST,
                "default output is MANIFEST-source");
            check(luaOutput.absoluteNormalizedPath().equals(
                    luaContext.manifestDirectory() + "/build/lua"),
                "default output resolves from the manifest directory");
            check(!Files.exists(Path.of(luaContext.manifestDirectory()).resolve("build")),
                "no build directory was created during locate");
        }

        Path jvmEntry = entryWithManifest("output-default-jvm-proj",
            "{\n  \"languageVersion\": \"1.2\",\n  \"backend\": \"jvm\"\n}\n");
        ProjectContext jvmContext = locateOk(jvmEntry);
        if (jvmContext != null) {
            check(jvmContext.backend().equals("jvm")
                    && jvmContext.outputPath().decodedText().equals("build/jvm"),
                "JVM default output text per effective backend");
        }

        Path cliJvmEntry = entryWithManifest("output-default-cli-jvm-proj", VALID_MANIFEST);
        ProjectLocator.LocateResult cliJvm = ProjectLocator.locate(cliJvmEntry.toString(),
            new CliOverrides("jvm", null));
        check(cliJvm.context() != null && cliJvm.context().backend().equals("jvm")
                && cliJvm.context().outputPath().decodedText().equals("build/jvm"),
            "CLI backend override selects the jvm default output");

        // Manifest output: relative, dot, parent, and absolute forms.
        Path outDir = fixture("output-forms");
        Path outEntry = writeEntry(outDir, "main.deal");
        writeText(outDir.resolve("deal.json"),
            "{\n  \"languageVersion\": \"1.2\",\n  \"output\": \"dist/out\"\n}\n");
        ProjectContext outContext = locateOk(outEntry);
        if (outContext != null) {
            check(outContext.outputPath().decodedText().equals("dist/out"),
                "manifest output text preserved");
            check(outContext.outputPath().absoluteNormalizedPath().equals(
                    outDir.toRealPath() + "/dist/out"),
                "manifest-relative output is prefix-resolved from the manifest dir");
            check(outContext.outputPath().sourceRange() != null,
                "manifest output carries its value range");
        }

        Path dotDir = fixture("output-dot");
        Path dotEntry = writeEntry(dotDir, "main.deal");
        writeText(dotDir.resolve("deal.json"),
            "{\n  \"languageVersion\": \"1.2\",\n  \"output\": \"./rel\"\n}\n");
        ProjectContext dotContext = locateOk(dotEntry);
        if (dotContext != null) {
            check(dotContext.outputPath().absoluteNormalizedPath().equals(
                    dotDir.toRealPath() + "/rel"),
                "./ output form resolves lexically from the manifest dir");
        }

        Path parentDir = fixture("output-parent/sub");
        Path parentEntry = writeEntry(parentDir, "main.deal");
        writeText(parentDir.resolve("deal.json"),
            "{\n  \"languageVersion\": \"1.2\",\n  \"output\": \"../rel\"\n}\n");
        ProjectContext parentContext = locateOk(parentEntry);
        if (parentContext != null) {
            Path expected = parentDir.toRealPath().getParent().resolve("rel");
            check(parentContext.outputPath().absoluteNormalizedPath()
                    .equals(expected.toString()),
                "../ output form resolves lexically from the manifest dir");
        }

        Path absManifestDir = fixture("output-abs");
        Path absManifestEntry = writeEntry(absManifestDir, "main.deal");
        Path absTarget = tmpDir.resolve("absolute-output-target");
        writeText(absManifestDir.resolve("deal.json"),
            "{\n  \"languageVersion\": \"1.2\",\n  \"output\": \""
                + absTarget.toString().replace("\\", "\\\\") + "\"\n}\n");
        ProjectContext absContext = locateOk(absManifestEntry);
        if (absContext != null) {
            check(absContext.outputPath().kind() == OutputConfigResolver.Kind.ABSOLUTE_PATH,
                "leading-/ manifest output is ABSOLUTE_PATH");
            check(absContext.outputPath().absoluteNormalizedPath().equals(
                    normalizePrefixResolvedText(absTarget.toString())),
                "absolute output is prefix-resolved");
        }

        // A symlinked output prefix resolves on its longest existing
        // directory prefix.
        Path linkOutDir = fixture("output-link");
        Path linkTarget = fixture("output-link-target");
        Files.createSymbolicLink(linkOutDir.resolve("outlink"), linkTarget);
        Path linkEntry = writeEntry(linkOutDir, "main.deal");
        writeText(linkOutDir.resolve("deal.json"),
            "{\n  \"languageVersion\": \"1.2\",\n  \"output\": \"outlink/lua\"\n}\n");
        ProjectContext linkContext = locateOk(linkEntry);
        if (linkContext != null) {
            check(linkContext.outputPath().absoluteNormalizedPath().equals(
                    linkTarget.toRealPath() + "/lua"),
                "symlinked output prefix resolves on the longest existing"
                    + " directory prefix");
        }

        // The D1 step-5 mapping seam (the residual conversion failure is
        // filesystem-exotic for schema-validated values, so the mapping
        // is verified directly).
        SourceScalarRange outRange = new SourceScalarRange(3, 4, 3, 9, 12, 17);
        ProjectLocator.LocateResult manifestFailure = ProjectLocator.mapOutputFailure(
            new OutputConfigResolver.OutputPathFailure(
                OutputConfigResolver.Source.MANIFEST, "conversion-reason", outRange),
            "/entry.deal", "/proj/deal.json");
        check(manifestFailure.e2010() != null && manifestFailure.context() == null
                && manifestFailure.cliDiagnostic() == null,
            "MANIFEST-source output failure maps to E2010");
        if (manifestFailure.e2010() != null) {
            checkRange(manifestFailure.e2010(), "/proj/deal.json", outRange);
        }

        ProjectLocator.LocateResult defaultFailure = ProjectLocator.mapOutputFailure(
            new OutputConfigResolver.OutputPathFailure(
                OutputConfigResolver.Source.MANIFEST, "default-reason", null),
            "/entry.deal", "/proj/deal.json");
        check(defaultFailure.e2010() != null
                && defaultFailure.e2010().range().origin() == RangeOrigin.SYNTHETIC
                && defaultFailure.e2010().range().file().equals("/proj/deal.json"),
            "rangeless MANIFEST-source failure maps to the synthetic E2010 shape");

        ProjectLocator.LocateResult cliFailure = ProjectLocator.mapOutputFailure(
            new OutputConfigResolver.OutputPathFailure(
                OutputConfigResolver.Source.CLI, "cli-reason", null),
            "/entry.deal", "/proj/deal.json");
        check(cliFailure.cliDiagnostic() != null && cliFailure.context() == null
                && cliFailure.e2010() == null,
            "CLI-source output failure maps to a CliDiagnostic");
        check(cliFailure.cliDiagnostic() != null
                && cliFailure.cliDiagnostic().range().file().equals("/entry.deal"),
            "CLI output failure anchors at the entry-file value");
    }

    private static String normalizePrefixResolvedText(String text) {
        ProtectedPathOps.PathResult result = ProtectedPathOps.normalizePrefixResolved(text);
        if (result instanceof ProtectedPathOps.PathResult.Success success) {
            return success.resolvedPath().toString();
        }
        throw new IllegalStateException("unexpected conversion failure for " + text);
    }

    // =========================================================================
    // Stdlib surface (verification 6)
    // =========================================================================

    private static void testStdlibSurface() throws Exception {
        System.out.println("-- Stdlib surface selection");

        // Project-local std/ wins even when the process CWD also has one.
        Path localDir = fixture("surface-local");
        writeEntry(localDir, "main.deal");
        writeText(localDir.resolve("std/console.d.deal"), "// console\n");
        writeText(localDir.resolve("deal.json"), VALID_MANIFEST);
        ProjectContext localContext = locateOk(localDir.resolve("main.deal"));
        if (localContext != null) {
            Path expected = localDir.resolve("std").toRealPath();
            check(expected.toString().equals(localContext.stdlibSurfacePath()),
                "project-local std/ wins: " + localContext.stdlibSurfacePath());
        }

        // Without a project-local std/, the language-distribution surface
        // (<processCWD>/std) is selected; the test JVM runs from the repo
        // root, whose std/ exists.
        Path fallbackEntry = entryWithManifest("surface-cwd", VALID_MANIFEST);
        ProjectContext fallbackContext = locateOk(fallbackEntry);
        if (fallbackContext != null) {
            java.util.Optional<Path> probed = ProtectedPathOps.probeDirectory(
                Path.of("std").toAbsolutePath());
            if (probed.isPresent()) {
                check(probed.get().toString().equals(
                        fallbackContext.stdlibSurfacePath()),
                    "without project-local std/, the process-CWD surface is selected: "
                        + fallbackContext.stdlibSurfacePath());
            } else {
                fail("repo-root std/ should exist as a directory for the fallback test");
            }
        }

        // A project-local std that is a FILE is not a directory surface:
        // the probe falls back to the CWD surface.
        Path fileStdDir = fixture("surface-file-std");
        writeEntry(fileStdDir, "main.deal");
        writeText(fileStdDir.resolve("std"), "not a directory");
        writeText(fileStdDir.resolve("deal.json"), VALID_MANIFEST);
        ProjectContext fileStdContext = locateOk(fileStdDir.resolve("main.deal"));
        if (fileStdContext != null) {
            java.util.Optional<Path> probed = ProtectedPathOps.probeDirectory(
                Path.of("std").toAbsolutePath());
            String expected = probed.map(Path::toString).orElse(null);
            check(expected == null
                    ? fileStdContext.stdlibSurfacePath() == null
                    : expected.equals(fileStdContext.stdlibSurfacePath()),
                "a file at std/ is not a directory surface");
        }

        // Absent surface (no project std, no CWD std): locate succeeds
        // with an absent stdlibSurfacePath — proven in a subprocess with
        // a std-free working directory.
        Path absentDir = fixture("surface-absent");
        Path absentEntry = writeEntry(absentDir, "main.deal");
        writeText(absentDir.resolve("deal.json"), VALID_MANIFEST);
        String subOutput = subLocate(absentEntry.toString());
        check(subOutput.contains("SURFACE=absent"),
            "absent surface: locate succeeds with an absent stdlibSurfacePath ("
                + subOutput + ")");

        // With an absent surface the stdlib-overlap check cannot fire.
        Path noSurfaceOverlapDir = fixture("surface-absent-overlap");
        Path noSurfaceEntry = writeEntry(noSurfaceOverlapDir, "main.deal");
        writeText(noSurfaceOverlapDir.resolve("decl.d.deal"), "export function d(): null\n");
        writeText(noSurfaceOverlapDir.resolve("deal.json"),
            "{\n  \"languageVersion\": \"1.2\",\n"
                + "  \"externals\": {\n"
                + "    \"host\": {\"declaration\": \"decl.d.deal\"}\n"
                + "  }\n}\n");
        String overlapSubOutput = subLocate(noSurfaceEntry.toString());
        check(overlapSubOutput.contains("SURFACE=absent"),
            "absent surface: an externals declaration never fires the overlap check ("
                + overlapSubOutput + ")");
    }

    /** Runs a single locate in a subprocess with a std-free working dir. */
    private static String subLocate(String entryPath) throws Exception {
        String javaHome = System.getProperty("java.home");
        String classPath = System.getProperty("java.class.path");
        StringBuilder absoluteCp = new StringBuilder();
        for (String part : classPath.split(Pattern.quote(File.pathSeparator))) {
            if (absoluteCp.length() > 0) {
                absoluteCp.append(File.pathSeparator);
            }
            absoluteCp.append(Path.of(part).toAbsolutePath());
        }
        List<String> command = List.of(javaHome + "/bin/java", "-ea", "-cp",
            absoluteCp.toString(), ProjectLocatorTest.class.getName(),
            "--sub-locate", entryPath);
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(emptyCwdDir.toFile());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(),
            StandardCharsets.UTF_8);
        int exit = process.waitFor();
        return "exit=" + exit + " output=" + output.trim();
    }

    // =========================================================================
    // Deployment identity (verification 7)
    // =========================================================================

    private static void testDeploymentIdentity() throws Exception {
        System.out.println("-- Deployment identity");

        Path dir = fixture("identity");
        Path entry = writeEntry(dir, "main.deal");
        Path manifestFile = writeText(dir.resolve("deal.json"), VALID_MANIFEST);
        byte[] manifestBytes = Files.readAllBytes(manifestFile);
        ProjectContext context = locateOk(entry);
        if (context != null) {
            ProjectDeploymentIdentity identity = context.projectDeploymentIdentity();
            check(identity.validatedManifestContentDigest().equals(
                    sha256Hex(manifestBytes)),
                "digest is SHA-256 over the exact manifest bytes");
            check(identity.validatedManifestContentDigest().length() == 64
                    && identity.validatedManifestContentDigest()
                        .equals(identity.validatedManifestContentDigest().toLowerCase())
                    && identity.validatedManifestContentDigest().matches("[0-9a-f]{64}"),
                "digest is 64 lowercase hex chars");
            String expectedUri = manifestFile.toRealPath().toUri().toString();
            check(identity.canonicalManifestUri().equals(expectedUri),
                "canonicalManifestUri is the symlink-resolved file: URI, got: "
                    + identity.canonicalManifestUri());
            check(identity.canonicalManifestUri().startsWith("file:"),
                "canonicalManifestUri has the file: scheme");
        }

        // A whitespace-only byte change changes the digest (and the
        // deployment identity).
        writeBytes(manifestFile,
            (VALID_MANIFEST + "\n").getBytes(StandardCharsets.UTF_8));
        ProjectContext changed = locateOk(entry);
        if (changed != null && context != null) {
            check(!changed.projectDeploymentIdentity()
                    .validatedManifestContentDigest()
                    .equals(context.projectDeploymentIdentity()
                        .validatedManifestContentDigest()),
                "a whitespace-only manifest byte change changes the digest");
            check(!changed.equals(context),
                "a manifest byte change changes the published context");
        }
        // Restore the original bytes for later fixture reuse.
        writeBytes(manifestFile, manifestBytes);

        // Two symlinked spellings of one manifest yield one URI.
        Path directDir = fixture("identity-direct");
        Path directEntry = writeEntry(directDir, "main.deal");
        Path directManifest = writeText(directDir.resolve("deal.json"), VALID_MANIFEST);
        Path aliasDir = fixture("identity-alias");
        Path aliasEntry = writeEntry(aliasDir, "main.deal");
        Files.createSymbolicLink(aliasDir.resolve("deal.json"), directManifest.toAbsolutePath());
        ProjectContext directContext = locateOk(directEntry);
        ProjectContext aliasContext = locateOk(aliasEntry);
        if (directContext != null && aliasContext != null) {
            check(aliasContext.manifestPath().equals(directContext.manifestPath()),
                "symlinked manifest spellings resolve to one manifest path");
            check(aliasContext.projectDeploymentIdentity().canonicalManifestUri().equals(
                    directContext.projectDeploymentIdentity().canonicalManifestUri()),
                "symlinked manifest spellings yield one canonical URI");
            check(aliasContext.projectDeploymentIdentity()
                    .validatedManifestContentDigest()
                    .equals(directContext.projectDeploymentIdentity()
                        .validatedManifestContentDigest()),
                "symlinked manifest spellings yield one digest");
        }
    }

    // =========================================================================
    // Privacy invariant (verification 7)
    // =========================================================================

    private static void testPrivacyInvariant() throws Exception {
        System.out.println("-- Privacy invariant: no digest/URI text in diagnostics");

        // A failing configuration read never reports the deployment
        // digest or a file: URI in messages or notes.
        Path zeroDir = fixture("privacy-zero");
        Path zeroEntry = writeEntry(zeroDir, "main.deal");
        if (environmentClean) {
            CompilerDiagnostic zero = locateE2010(zeroEntry);
            checkNoPrivateIdentityText(zero, "zero-manifest E2010");
        }

        Path decodeDir = fixture("privacy-decode");
        Path decodeEntry = writeEntry(decodeDir, "main.deal");
        byte[] base = VALID_MANIFEST.getBytes(StandardCharsets.UTF_8);
        writeBytes(decodeDir.resolve("deal.json"),
            corrupted(base, 5, (byte) 0xED, (byte) 0xA0, (byte) 0x80));
        CompilerDiagnostic decode = locateE2010(decodeEntry);
        checkNoPrivateIdentityText(decode, "decode E2010");

        Path dupDir = fixture("privacy-dup");
        Path dupEntry = writeEntry(dupDir, "main.deal");
        writeText(dupDir.resolve("deal.json"),
            "{\n  \"languageVersion\": \"1.2\",\n"
                + "  \"moduleRoots\": [\"src\", \"src\"]\n}\n");
        CompilerDiagnostic duplicate = locateE2010(dupEntry);
        checkNoPrivateIdentityText(duplicate, "duplicate-root E2010");
    }

    // =========================================================================
    // Determinism (verification 7)
    // =========================================================================

    private static void testDeterminism() throws Exception {
        System.out.println("-- Determinism");

        Path dir = fixture("determinism");
        Path entry = writeEntry(dir, "main.deal");
        writeText(dir.resolve("deal.json"),
            "{\n  \"languageVersion\": \"1.2\",\n"
                + "  \"moduleRoots\": [\"src\"],\n"
                + "  \"externals\": {\"host\": {\"declaration\": \"decl.d.deal\"}}\n}\n");
        Files.createDirectories(dir.resolve("src"));
        writeText(dir.resolve("decl.d.deal"), "export function d(): null\n");
        ProjectContext first = locateOk(entry);
        ProjectContext second = locateOk(entry);
        if (first != null && second != null) {
            check(first.equals(second), "identical inputs publish equal contexts");
            check(first.projectDeploymentIdentity().equals(
                    second.projectDeploymentIdentity()),
                "identical inputs publish equal deployment identities");
            check(first.outputPath().equals(second.outputPath()),
                "identical inputs publish equal output refs");
        }

        // A symlinked entry spelling resolves to the same real entry and
        // publishes the same context.
        Path aliasDir = fixture("determinism-alias");
        Files.createSymbolicLink(aliasDir.resolve("alias.deal"), entry.toAbsolutePath());
        ProjectContext aliasContext = locateOk(aliasDir.resolve("alias.deal"));
        if (aliasContext != null && first != null) {
            check(aliasContext.equals(first),
                "symlinked entry spellings publish the same context");
        }
    }

    // =========================================================================
    // No artifact creation
    // =========================================================================

    private static void testNoArtifactCreation() throws Exception {
        System.out.println("-- No artifact creation");

        // Success with the default output: nothing is created.
        Path okDir = fixture("no-artifact-ok");
        Path okEntry = entryWithManifest("no-artifact-ok-proj", VALID_MANIFEST);
        locateOk(okEntry);
        check(!Files.exists(okDir.resolve("build")),
            "no output directory is created by a successful locate");

        // Failure with an output-less valid manifest: nothing is created.
        Path failDir = fixture("no-artifact-fail");
        Path failEntry = writeEntry(failDir, "main.deal");
        writeText(failDir.resolve("deal.json"),
            "{\n  \"languageVersion\": \"1.2\",\n"
                + "  \"moduleRoots\": [\"a\", \"a\"]\n}\n");
        locateE2010(failEntry);
        Set<String> names = new HashSet<>();
        try (var stream = Files.list(failDir)) {
            stream.forEach(path -> names.add(path.getFileName().toString()));
        }
        check(names.equals(new HashSet<>(Set.of("main.deal", "deal.json"))),
            "a failed locate creates no directory or artifact");
    }

    // =========================================================================
    // Combined end-to-end fixture (T1+T2+T3 dependency gate)
    // =========================================================================

    private static void testCombinedEndToEnd() throws Exception {
        System.out.println("-- Combined end-to-end fixture");

        Path project = fixture("combined/project");
        Files.createDirectories(project.resolve("src"));
        Files.createDirectories(project.resolve("lib"));
        createSixStdlibFiles(project);
        writeEntry(project, "main.deal");
        writeText(project.resolve("decl.d.deal"), "export function d(): null\n");
        Path manifestFile = writeText(project.resolve("deal.json"),
            "{\n"
                + "  \"languageVersion\": \"1.2\",\n"
                + "  \"moduleRoots\": [\"src\", \"lib\"],\n"
                + "  \"output\": \"dist/lua\",\n"
                + "  \"backend\": \"jvm\",\n"
                + "  \"stdlib\": \"1.2\",\n"
                + "  \"dependencies\": {\"dep\": {\"x\": 1}},\n"
                + "  \"externals\": {\n"
                + "    \"host\": {\"declaration\": \"decl.d.deal\","
                + " \"nativeLibrary\": \"libs/libhost.so\"}\n"
                + "  }\n"
                + "}\n");

        ProjectContext context = locateOk(project.resolve("main.deal"));
        if (context == null) {
            return;
        }
        Path projectReal = project.toRealPath();
        check(context.manifestPath().equals(
                projectReal.resolve("deal.json").toRealPath().toString()),
            "manifestPath is the resolved manifest");
        check(context.projectRoot().equals(projectReal.toString())
                && context.manifestDirectory().equals(projectReal.toString()),
            "projectRoot == manifestDirectory == resolved manifest dir");
        check(context.languageVersion().equals("1.2"), "languageVersion 1.2");
        check(context.configuredModuleRoots().size() == 2,
            "two configured roots");
        if (context.configuredModuleRoots().size() == 2) {
            check(context.configuredModuleRoots().get(0).configuredText().equals("src")
                    && context.configuredModuleRoots().get(0).absoluteNormalizedPath()
                        .equals(projectReal.resolve("src").toRealPath().toString()),
                "root 'src' resolved");
            check(context.configuredModuleRoots().get(1).configuredText().equals("lib")
                    && context.configuredModuleRoots().get(1).absoluteNormalizedPath()
                        .equals(projectReal.resolve("lib").toRealPath().toString()),
                "root 'lib' resolved");
        }
        check(context.outputPath().decodedText().equals("dist/lua")
                && context.outputPath().kind()
                    == OutputConfigResolver.Kind.MANIFEST_RELATIVE_PATH
                && context.outputPath().source() == OutputConfigResolver.Source.MANIFEST
                && context.outputPath().absoluteNormalizedPath().equals(
                    projectReal + "/dist/lua"),
            "output classified and converted");
        check(context.outputPath().sourceRange() != null,
            "output carries its manifest value range");
        check(context.backend().equals("jvm"), "effective backend jvm");
        check(context.externals().size() == 1 && context.externals().containsKey("host"),
            "externals completed");
        ExternalEntry entry = context.externals().get("host");
        if (entry != null) {
            check(entry.declarationPath().absoluteNormalizedPath().equals(
                    projectReal.resolve("decl.d.deal").toRealPath().toString()),
                "externals declaration resolved");
            check(entry.nativeLibrary() != null
                    && entry.nativeLibrary().kind()
                        == NativeLibraryRef.Kind.MANIFEST_RELATIVE_PATH
                    && entry.nativeLibrary().loaderText().equals("libs/libhost.so"),
                "externals nativeLibrary classified");
            check(entry.sourceRange() != null, "externals entry range present");
        }
        check(context.stdlibVersion().equals("1.2"), "stdlibVersion 1.2");
        check(context.stdlibSurfacePath() != null && context.stdlibSurfacePath().equals(
                projectReal.resolve("std").toRealPath().toString()),
            "stdlibSurfacePath is the project-local surface");
        ProjectDeploymentIdentity identity = context.projectDeploymentIdentity();
        check(identity.canonicalManifestUri().equals(
                manifestFile.toRealPath().toUri().toString()),
            "canonical manifest URI derived");
        check(identity.validatedManifestContentDigest().equals(
                sha256Hex(Files.readAllBytes(manifestFile))),
            "deployment digest over the exact bytes");
        check(!Files.exists(project.resolve("dist")),
            "the manifest output directory was not created at locate");

        // Mutate one manifest byte and re-locate: the new deployment
        // digest differs and the mutated field is re-read.
        String mutated = "{\n"
            + "  \"languageVersion\": \"1.2\",\n"
            + "  \"moduleRoots\": [\"src\", \"lib\"],\n"
            + "  \"output\": \"out2/lua\",\n"
            + "  \"backend\": \"jvm\",\n"
            + "  \"stdlib\": \"1.2\",\n"
            + "  \"dependencies\": {\"dep\": {\"x\": 1}},\n"
            + "  \"externals\": {\n"
            + "    \"host\": {\"declaration\": \"decl.d.deal\","
            + " \"nativeLibrary\": \"libs/libhost.so\"}\n"
            + "  }\n"
            + "}\n";
        writeBytes(manifestFile, mutated.getBytes(StandardCharsets.UTF_8));
        ProjectContext mutatedContext = locateOk(project.resolve("main.deal"));
        if (mutatedContext != null) {
            check(!mutatedContext.projectDeploymentIdentity()
                    .validatedManifestContentDigest()
                    .equals(identity.validatedManifestContentDigest()),
                "a one-byte manifest mutation changes the deployment digest");
            check(mutatedContext.outputPath().decodedText().equals("out2/lua"),
                "the mutated manifest byte is re-read");
            check(!mutatedContext.equals(context),
                "the mutated manifest changes the published context");
        }
    }
}
