package deal.test;

import deal.project.ProtectedPathOps;
import deal.project.ProtectedPathOps.ByteResult;
import deal.project.ProtectedPathOps.DecodedResult;
import deal.project.ProtectedPathOps.InputValidationFailure;
import deal.project.ProtectedPathOps.PathResult;
import deal.project.ProtectedPathOps.PathResult.FailureKind;
import deal.project.ProtectedPathOps.UriResult;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Unit tests for {@link deal.project.ProtectedPathOps} (ISSUE-0262,
 * design strict-project-context-resolution-identity D4): the single
 * protected host-path boundary of the strict v1.2 project layer.
 *
 * <p>The suite covers every row of the pinned per-path-class conversion
 * matrix with real temp-directory fixtures — existing regular files,
 * existing directories, symlinked files/directories (one-level and
 * chained), dangling symlinks, non-existent paths, unreadable files where
 * the platform permits, relative inputs, NUL-bearing inputs,
 * unpaired-surrogate inputs, and Linux backslashes as ordinary filename
 * characters — plus the symlinked-spelling equivalence of
 * {@code canonicalizeExisting} and {@code toFileUri}, the length-prefixed
 * UTF-8 serialization round-trip (including supplementary-plane scalars)
 * and its byte stability, and the invariant that no operation throws for
 * any error input (failures are structured results only). JDK-only: the
 * test uses the repository's plain main-method runner convention.</p>
 */
public class ProtectedPathOpsTest {

    private static int passed = 0;
    private static int failed = 0;
    private static Path tmpDir;
    /** {@code tmpDir.toRealPath()} — the symlink-resolved expectation base. */
    private static Path realTmp;

    public static void main(String[] args) throws Exception {
        tmpDir = Files.createTempDirectory("deal-protected-path-ops-");
        try {
            realTmp = tmpDir.toRealPath();
            testInputValidation();
            testCanonicalizeExistingMatrixRows();
            testNormalizePrefixResolvedMatrixRows();
            testProbeDirectoryStdlibRow();
            testToFileUri();
            testLengthPrefixedUtf8Serialization();
            testNoThrowInvariant();
        } finally {
            cleanup();
        }
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

    private static void fail(String message) {
        failed++;
        System.err.println("FAIL: " + message);
    }

    /** Asserts the operation does not throw and returns some result. */
    private static void checkNoThrow(Supplier<?> op, String message) {
        try {
            op.get();
            passed++;
        } catch (Throwable t) {
            failed++;
            System.err.println("FAIL: " + message + " (threw " + t + ")");
        }
    }

    private static Path successOrFail(PathResult r, String message) {
        if (r instanceof PathResult.Success s) {
            passed++;
            return s.resolvedPath();
        }
        failed++;
        System.err.println("FAIL: " + message + " (expected Success, got " + r + ")");
        return Path.of("__no_path__");
    }

    private static PathResult.Failure failureOrFail(PathResult r, String message) {
        if (r instanceof PathResult.Failure f) {
            passed++;
            return f;
        }
        failed++;
        System.err.println("FAIL: " + message + " (expected Failure, got " + r + ")");
        return new PathResult.Failure(FailureKind.IO_ERROR, "<none>", "unreachable");
    }

    private static byte[] byteSuccessOrFail(ByteResult r, String message) {
        if (r instanceof ByteResult.Success s) {
            passed++;
            return s.bytes();
        }
        failed++;
        System.err.println("FAIL: " + message + " (expected ByteResult.Success, got " + r + ")");
        return new byte[0];
    }

    private static String decodedSuccessOrFail(DecodedResult r, String message) {
        if (r instanceof DecodedResult.Success s) {
            passed++;
            return s.value();
        }
        failed++;
        System.err.println("FAIL: " + message + " (expected DecodedResult.Success, got " + r + ")");
        return "__no_value__";
    }

    private static Path newFile(String rel) throws IOException {
        Path p = tmpDir.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.write(p, ("content:" + rel).getBytes(StandardCharsets.UTF_8));
        return p;
    }

    private static Path newDir(String rel) throws IOException {
        Path p = tmpDir.resolve(rel);
        Files.createDirectories(p);
        return p;
    }

    /** True iff the platform supports symbolic-link creation here. */
    private static boolean symlinksSupported() {
        Path target = tmpDir.resolve("symlink-probe-target");
        Path link = tmpDir.resolve("symlink-probe-link");
        try {
            Files.write(target, new byte[] {1});
            Files.createSymbolicLink(link, Path.of("symlink-probe-target"));
            Files.deleteIfExists(link);
            Files.deleteIfExists(target);
            return true;
        } catch (IOException | UnsupportedOperationException e) {
            return false;
        }
    }

    /**
     * True iff the platform and current user permit making a file
     * unreadable (POSIX permissions; running as root defeats the check).
     */
    private static boolean canMakeUnreadable() {
        Path p = tmpDir.resolve("perm-probe");
        try {
            Files.write(p, new byte[] {1});
            Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("--x------"));
            boolean result = !Files.isReadable(p);
            Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rw-------"));
            Files.deleteIfExists(p);
            return result;
        } catch (IOException | UnsupportedOperationException e) {
            return false;
        }
    }

    private static void cleanup() {
        try {
            Files.walk(tmpDir)
                .sorted(Comparator.reverseOrder())
                .forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                        // best-effort temp cleanup
                    }
                });
        } catch (IOException ignored) {
            // best-effort temp cleanup
        }
    }

    // =========================================================================
    // Input validation
    // =========================================================================

    private static void testInputValidation() {
        System.out.println("-- Input validation: NUL and host-representability --");

        check(ProtectedPathOps.validateInput("").isEmpty(),
            "empty string is valid input");
        check(ProtectedPathOps.validateInput("src/main.deal").isEmpty(),
            "ordinary path text is valid input");
        check(ProtectedPathOps.validateInput("weird\\name.deal").isEmpty(),
            "Linux backslash is an ordinary filename character (valid input)");
        check(ProtectedPathOps.validateInput("emoji-\uD83D\uDE00.deal").isEmpty(),
            "a surrogate pair (supplementary-plane scalar) is valid input");
        check(ProtectedPathOps.validateInput("a/b/../c").isEmpty(),
            "dot components are valid input (lexical normalization is the conversion's job)");

        Optional<InputValidationFailure> nul =
            ProtectedPathOps.validateInput("a\u0000b");
        check(nul.isPresent()
                && nul.get().offendingInput().equals("a\u0000b")
                && nul.get().reason().contains("NUL"),
            "NUL input fails with the offending input and a NUL reason");
        Optional<InputValidationFailure> nulAt = ProtectedPathOps.validateInput("ab\u0000cd");
        check(nulAt.isPresent() && nulAt.get().reason().contains("UTF-16 index 2"),
            "NUL failure reason pins the first offending index");

        Optional<InputValidationFailure> high =
            ProtectedPathOps.validateInput("x\uD83Dy");
        check(high.isPresent()
                && high.get().offendingInput().equals("x\uD83Dy")
                && high.get().reason().contains("high surrogate"),
            "unpaired high surrogate fails with the offending input and reason");
        Optional<InputValidationFailure> low = ProtectedPathOps.validateInput("x\uDC00y");
        check(low.isPresent() && low.get().reason().contains("low surrogate"),
            "unpaired low surrogate fails");

        Optional<InputValidationFailure> nullInput = ProtectedPathOps.validateInput(null);
        check(nullInput.isPresent() && nullInput.get().reason().contains("null"),
            "null input fails validation with a reason");

        checkNoThrow(() -> ProtectedPathOps.validateInput("a\u0000b"),
            "validateInput: NUL input does not throw");
        checkNoThrow(() -> ProtectedPathOps.validateInput("x\uD83Dy"),
            "validateInput: unpaired-surrogate input does not throw");
        checkNoThrow(() -> ProtectedPathOps.validateInput(null),
            "validateInput: null input does not throw");
    }

    // =========================================================================
    // Matrix rows: entry file / discovered manifest / externals declaration /
    // import candidate (canonicalizeExisting — existence required)
    // =========================================================================

    private static void testCanonicalizeExistingMatrixRows() throws Exception {
        System.out.println("-- Matrix rows: entry file / manifest file / externals declaration /"
            + " import candidate (canonicalizeExisting) --");
        String[] rows = {
            "entry file",
            "discovered manifest file",
            "externals declaration file",
            "import candidate"
        };

        // 1. Existing regular file fully resolves (all four rows).
        Path file = newFile("rows/plain.deal");
        for (String row : rows) {
            Path got = successOrFail(
                ProtectedPathOps.canonicalizeExisting(file.toString()),
                row + ": existing regular file is a Success");
            check(got.equals(realTmp.resolve("rows/plain.deal")),
                row + ": existing regular file resolves to its real path");
        }

        // 2. Relative input resolves identically to the absolute spelling.
        Path cwd = Path.of("").toAbsolutePath();
        Path rel = cwd.relativize(file);
        Path absGot = successOrFail(
            ProtectedPathOps.canonicalizeExisting(file.toString()),
            "canonicalizeExisting: absolute input succeeds");
        Path relGot = successOrFail(
            ProtectedPathOps.canonicalizeExisting(rel.toString()),
            "canonicalizeExisting: relative input succeeds");
        check(absGot.equals(relGot),
            "canonicalizeExisting: relative input resolves identically to the absolute spelling");

        // 3. Non-existent path -> NOT_FOUND.
        Path missing = tmpDir.resolve("rows/missing.deal");
        PathResult.Failure notFound = failureOrFail(
            ProtectedPathOps.canonicalizeExisting(missing.toString()),
            "canonicalizeExisting: non-existent path is a structured failure");
        check(notFound.kind() == FailureKind.NOT_FOUND,
            "non-existent path failure kind is NOT_FOUND");
        check(notFound.offendingInput().equals(missing.toString()),
            "NOT_FOUND failure carries the offending input");

        // 4. Directory -> NOT_REGULAR.
        Path dir = newDir("rows/a-directory");
        PathResult.Failure notRegular = failureOrFail(
            ProtectedPathOps.canonicalizeExisting(dir.toString()),
            "canonicalizeExisting: directory is a structured failure");
        check(notRegular.kind() == FailureKind.NOT_REGULAR,
            "directory failure kind is NOT_REGULAR");

        // 5. Linux backslash is an ordinary filename character.
        Path backslashFile = newFile("rows\\backslash.deal");
        Path backslashGot = successOrFail(
            ProtectedPathOps.canonicalizeExisting(backslashFile.toString()),
            "canonicalizeExisting: backslash-named file succeeds");
        check(backslashGot.equals(realTmp.resolve("rows\\backslash.deal")),
            "backslash is preserved byte-identically as an ordinary filename character");

        // 6. Empty string denotes the process CWD (a directory) -> NOT_REGULAR.
        PathResult.Failure empty = failureOrFail(
            ProtectedPathOps.canonicalizeExisting(""),
            "canonicalizeExisting: empty input (CWD directory) is a structured failure");
        check(empty.kind() == FailureKind.NOT_REGULAR,
            "empty input (CWD is a directory) failure kind is NOT_REGULAR");

        if (symlinksSupported()) {
            // 7. One-level symlinked file -> target's real path.
            Path target = newFile("sym/file-target.deal");
            Path link = Files.createSymbolicLink(tmpDir.resolve("sym/file-link.deal"),
                Path.of("file-target.deal"));
            Path linkGot = successOrFail(
                ProtectedPathOps.canonicalizeExisting(link.toString()),
                "canonicalizeExisting: one-level symlinked file succeeds");
            check(linkGot.equals(realTmp.resolve("sym/file-target.deal")),
                "one-level symlinked file resolves to the target's real path");

            // 8. Chained symlinks -> final target's real path.
            Path link2 = Files.createSymbolicLink(tmpDir.resolve("sym/file-link2.deal"),
                Path.of("file-link.deal"));
            Path chainGot = successOrFail(
                ProtectedPathOps.canonicalizeExisting(link2.toString()),
                "canonicalizeExisting: chained symlinks succeed");
            check(chainGot.equals(realTmp.resolve("sym/file-target.deal")),
                "chained symlinks resolve to the final target's real path");

            // 9. Equivalent spellings of one file yield one result and one URI.
            Path spelling1 = successOrFail(
                ProtectedPathOps.canonicalizeExisting(target.toString()),
                "canonicalizeExisting: direct spelling succeeds");
            check(spelling1.equals(linkGot),
                "two symlinked spellings of one file yield the same canonicalizeExisting result");
            ProtectedPathOps.UriResult u1 = ProtectedPathOps.toFileUri(spelling1);
            ProtectedPathOps.UriResult u2 = ProtectedPathOps.toFileUri(linkGot);
            if (u1 instanceof UriResult.Success s1 && u2 instanceof UriResult.Success s2) {
                check(s1.uri().equals(s2.uri()),
                    "two symlinked spellings of one file yield the same toFileUri URI");
            } else {
                fail("toFileUri on resolved paths should succeed, got " + u1 + " / " + u2);
            }

            // 10. Symlink to a directory -> resolves, then NOT_REGULAR.
            Path dirTarget = newDir("sym/dir-target");
            Path dirLink = Files.createSymbolicLink(tmpDir.resolve("sym/dir-link"),
                Path.of("dir-target"));
            PathResult.Failure linkToDir = failureOrFail(
                ProtectedPathOps.canonicalizeExisting(dirLink.toString()),
                "canonicalizeExisting: symlink-to-directory is a structured failure");
            check(linkToDir.kind() == FailureKind.NOT_REGULAR,
                "symlink-to-directory failure kind is NOT_REGULAR");

            // 11. Dangling symlink -> NOT_FOUND.
            Path dangling = Files.createSymbolicLink(tmpDir.resolve("sym/dangling.deal"),
                Path.of("does-not-exist.deal"));
            PathResult.Failure danglingFailure = failureOrFail(
                ProtectedPathOps.canonicalizeExisting(dangling.toString()),
                "canonicalizeExisting: dangling symlink is a structured failure");
            check(danglingFailure.kind() == FailureKind.NOT_FOUND,
                "dangling symlink failure kind is NOT_FOUND");
        } else {
            System.out.println("  (skipping symlink cases: symlinks unsupported on this platform)");
        }

        // 12. Unreadable file -> NOT_READABLE (where the platform permits).
        if (canMakeUnreadable()) {
            Path unreadable = newFile("rows/unreadable.deal");
            Files.setPosixFilePermissions(unreadable,
                PosixFilePermissions.fromString("--x------"));
            PathResult.Failure notReadable = failureOrFail(
                ProtectedPathOps.canonicalizeExisting(unreadable.toString()),
                "canonicalizeExisting: unreadable file is a structured failure");
            check(notReadable.kind() == FailureKind.NOT_READABLE,
                "unreadable file failure kind is NOT_READABLE");
            check(notReadable.offendingInput().equals(unreadable.toString()),
                "NOT_READABLE failure carries the offending input");
            Files.setPosixFilePermissions(unreadable,
                PosixFilePermissions.fromString("rw-------"));
        } else {
            System.out.println("  (skipping unreadable-file case: platform/user cannot make files unreadable)");
        }

        // 13. Determinism: identical input, identical result.
        PathResult first = ProtectedPathOps.canonicalizeExisting(file.toString());
        PathResult second = ProtectedPathOps.canonicalizeExisting(file.toString());
        check(first.equals(second),
            "canonicalizeExisting is deterministic for identical input");

        // 14. Invalid inputs are structured failures that never throw.
        checkNoThrow(() -> ProtectedPathOps.canonicalizeExisting("a\u0000b"),
            "canonicalizeExisting: NUL input does not throw");
        checkNoThrow(() -> ProtectedPathOps.canonicalizeExisting("x\uD83Dy"),
            "canonicalizeExisting: unpaired-surrogate input does not throw");
        checkNoThrow(() -> ProtectedPathOps.canonicalizeExisting(null),
            "canonicalizeExisting: null input does not throw");
        PathResult.Failure nulFailure = failureOrFail(
            ProtectedPathOps.canonicalizeExisting("a\u0000b"),
            "canonicalizeExisting: NUL input is a structured failure");
        check(nulFailure.kind() == FailureKind.INVALID_INPUT
                && nulFailure.offendingInput().equals("a\u0000b"),
            "NUL input failure kind is INVALID_INPUT with the offending input");
        PathResult.Failure surrogateFailure = failureOrFail(
            ProtectedPathOps.canonicalizeExisting("x\uD83Dy"),
            "canonicalizeExisting: unpaired-surrogate input is a structured failure");
        check(surrogateFailure.kind() == FailureKind.INVALID_INPUT,
            "unpaired-surrogate input failure kind is INVALID_INPUT");
        PathResult.Failure nullFailure = failureOrFail(
            ProtectedPathOps.canonicalizeExisting(null),
            "canonicalizeExisting: null input is a structured failure");
        check(nullFailure.kind() == FailureKind.INVALID_INPUT,
            "null input failure kind is INVALID_INPUT");
    }

    // =========================================================================
    // Matrix rows: moduleRoots entries / output path
    // (normalizePrefixResolved — existence never required)
    // =========================================================================

    private static void testNormalizePrefixResolvedMatrixRows() throws Exception {
        System.out.println("-- Matrix rows: moduleRoots entries / output path (normalizePrefixResolved) --");

        // 1. Non-existent path succeeds with lexical components after the prefix.
        Path missing = tmpDir.resolve("roots/nonexistent/src");
        Path got = successOrFail(
            ProtectedPathOps.normalizePrefixResolved(missing.toString()),
            "normalizePrefixResolved: non-existent root path succeeds");
        check(got.equals(realTmp.resolve("roots/nonexistent/src")),
            "non-existent root keeps lexical components after the resolved prefix");

        // 2. A not-yet-existing output directory is never a failure.
        Path freshOutput = tmpDir.resolve("fresh/build/lua");
        check(!Files.exists(freshOutput), "fixture sanity: fresh output path does not exist");
        Path freshGot = successOrFail(
            ProtectedPathOps.normalizePrefixResolved(freshOutput.toString()),
            "normalizePrefixResolved: not-yet-existing output directory succeeds");
        check(freshGot.equals(realTmp.resolve("fresh/build/lua")),
            "not-yet-existing output directory resolves on its longest existing prefix");
        check(!Files.exists(freshOutput),
            "conversion created no directory (side-effect-free)");

        // 3. Existing directory resolves fully (empty suffix).
        Path existingDir = newDir("roots/existing-dir");
        Path dirGot = successOrFail(
            ProtectedPathOps.normalizePrefixResolved(existingDir.toString()),
            "normalizePrefixResolved: existing directory succeeds");
        check(dirGot.equals(existingDir.toRealPath()),
            "existing directory resolves fully (empty suffix)");

        // 4. Existing regular file: parent resolves, filename appended lexically.
        Path outFile = newFile("out/out.file");
        Path fileGot = successOrFail(
            ProtectedPathOps.normalizePrefixResolved(outFile.toString()),
            "normalizePrefixResolved: existing file path succeeds");
        check(fileGot.equals(realTmp.resolve("out/out.file")),
            "existing file path keeps its filename in the lexical suffix");

        // 5. Root "/" resolves to itself.
        Path rootGot = successOrFail(
            ProtectedPathOps.normalizePrefixResolved("/"),
            "normalizePrefixResolved: filesystem root succeeds");
        check(rootGot.equals(Path.of("/")),
            "filesystem root resolves to itself");

        // 6. Empty string denotes the process CWD (an existing directory) and
        // resolves fully.
        Path cwdGot = successOrFail(
            ProtectedPathOps.normalizePrefixResolved(""),
            "normalizePrefixResolved: empty input succeeds (CWD)");
        check(cwdGot.equals(Path.of("").toAbsolutePath().toRealPath()),
            "empty input resolves to the real process CWD");

        // 7. Relative input resolves identically to the absolute spelling.
        Path cwd = Path.of("").toAbsolutePath();
        Path rel = cwd.relativize(missing);
        Path absGot = successOrFail(
            ProtectedPathOps.normalizePrefixResolved(missing.toString()),
            "normalizePrefixResolved: absolute spelling succeeds");
        Path relGot = successOrFail(
            ProtectedPathOps.normalizePrefixResolved(rel.toString()),
            "normalizePrefixResolved: relative spelling succeeds");
        check(absGot.equals(relGot),
            "normalizePrefixResolved: relative input resolves identically to the absolute spelling");

        // 8. Linux backslash in a non-existent component stays byte-identical.
        Path backslashGot = successOrFail(
            ProtectedPathOps.normalizePrefixResolved(tmpDir.resolve("bck\\name").toString()),
            "normalizePrefixResolved: backslash-named non-existent component succeeds");
        check(backslashGot.equals(realTmp.resolve("bck\\name")),
            "backslash component is preserved as an ordinary filename character");

        if (symlinksSupported()) {
            // 9. One-level symlinked directory prefix resolves through the link.
            Path dirTarget = newDir("roots/real-root");
            Files.write(dirTarget.resolve("marker"), new byte[] {1});
            Path dirLink = Files.createSymbolicLink(tmpDir.resolve("roots/link-root"),
                Path.of("real-root"));
            Path linkGot = successOrFail(
                ProtectedPathOps.normalizePrefixResolved(dirLink.resolve("sub").toString()),
                "normalizePrefixResolved: symlinked directory prefix succeeds");
            check(linkGot.equals(dirTarget.toRealPath().resolve("sub")),
                "one-level symlinked directory prefix resolves through the link");

            // 10. Chained symlinked directory prefix resolves fully.
            Path dirLink2 = Files.createSymbolicLink(tmpDir.resolve("roots/link-root2"),
                Path.of("link-root"));
            Path chainGot = successOrFail(
                ProtectedPathOps.normalizePrefixResolved(dirLink2.resolve("sub").toString()),
                "normalizePrefixResolved: chained symlinked directory prefix succeeds");
            check(chainGot.equals(dirTarget.toRealPath().resolve("sub")),
                "chained symlinked directory prefix resolves to the final target");

            // 11. Dangling symlink prefix stops resolution; the suffix continues
            // lexically from the absolute path.
            Path dangling = Files.createSymbolicLink(tmpDir.resolve("roots/dangling"),
                Path.of("does-not-exist"));
            Path danglingGot = successOrFail(
                ProtectedPathOps.normalizePrefixResolved(dangling.resolve("sub").toString()),
                "normalizePrefixResolved: dangling symlink prefix is a normal value");
            check(danglingGot.equals(realTmp.resolve("roots/dangling/sub")),
                "dangling symlink is not resolved; the suffix continues lexically");
            check(!Files.exists(danglingGot),
                "the dangling spelling is preserved verbatim (not replaced by a target)");

            // 12. Symlink-to-file as the final component is not resolved
            // (only directory prefixes resolve).
            Path fileTarget = newFile("roots/file-real");
            Path fileLink = Files.createSymbolicLink(tmpDir.resolve("roots/file-link"),
                Path.of("file-real"));
            Path fileLinkGot = successOrFail(
                ProtectedPathOps.normalizePrefixResolved(fileLink.toString()),
                "normalizePrefixResolved: symlink-to-file final component succeeds");
            check(fileLinkGot.equals(realTmp.resolve("roots/file-link")),
                "a symlink-to-file final component stays in the lexical suffix (not resolved)");
            check(!fileLinkGot.equals(fileTarget.toRealPath()),
                "the file symlink is not resolved to its target");
        } else {
            System.out.println("  (skipping symlink cases: symlinks unsupported on this platform)");
        }

        // 13. Determinism.
        PathResult first = ProtectedPathOps.normalizePrefixResolved(missing.toString());
        PathResult second = ProtectedPathOps.normalizePrefixResolved(missing.toString());
        check(first.equals(second),
            "normalizePrefixResolved is deterministic for identical input");

        // 14. Invalid inputs are structured failures that never throw.
        checkNoThrow(() -> ProtectedPathOps.normalizePrefixResolved("a\u0000b"),
            "normalizePrefixResolved: NUL input does not throw");
        checkNoThrow(() -> ProtectedPathOps.normalizePrefixResolved("x\uD83Dy"),
            "normalizePrefixResolved: unpaired-surrogate input does not throw");
        checkNoThrow(() -> ProtectedPathOps.normalizePrefixResolved(null),
            "normalizePrefixResolved: null input does not throw");
        PathResult.Failure nulFailure = failureOrFail(
            ProtectedPathOps.normalizePrefixResolved("a\u0000b"),
            "normalizePrefixResolved: NUL input is a structured failure");
        check(nulFailure.kind() == FailureKind.INVALID_INPUT,
            "NUL input failure kind is INVALID_INPUT");
        PathResult.Failure surrogateFailure = failureOrFail(
            ProtectedPathOps.normalizePrefixResolved("x\uD83Dy"),
            "normalizePrefixResolved: unpaired-surrogate input is a structured failure");
        check(surrogateFailure.kind() == FailureKind.INVALID_INPUT,
            "unpaired-surrogate input failure kind is INVALID_INPUT");
        PathResult.Failure nullFailure = failureOrFail(
            ProtectedPathOps.normalizePrefixResolved(null),
            "normalizePrefixResolved: null input is a structured failure");
        check(nullFailure.kind() == FailureKind.INVALID_INPUT,
            "null input failure kind is INVALID_INPUT");
    }

    // =========================================================================
    // Matrix row: stdlib surface directory probe (absence is a value)
    // =========================================================================

    private static void testProbeDirectoryStdlibRow() throws Exception {
        System.out.println("-- Matrix row: stdlib surface directory probe (absence is a value) --");

        // 1. Existing directory resolves fully.
        Path dir = newDir("stdlib/real");
        Optional<Path> present = ProtectedPathOps.probeDirectory(dir);
        check(present.isPresent() && present.get().equals(dir.toRealPath()),
            "probeDirectory: existing directory is present and fully resolved");

        // 2. Relative input probes against the CWD.
        Path cwd = Path.of("").toAbsolutePath();
        Path rel = cwd.relativize(dir);
        Optional<Path> presentRel = ProtectedPathOps.probeDirectory(rel);
        check(presentRel.isPresent() && presentRel.get().equals(dir.toRealPath()),
            "probeDirectory: relative directory resolves identically");

        // 3. Absent path is a plain value.
        check(ProtectedPathOps.probeDirectory(tmpDir.resolve("stdlib/absent")).isEmpty(),
            "probeDirectory: absent directory is Optional.empty() (a value, never a failure)");

        // 4. A regular file is not a directory surface.
        Path file = newFile("stdlib/not-a-dir");
        check(ProtectedPathOps.probeDirectory(file).isEmpty(),
            "probeDirectory: regular file is Optional.empty()");

        // 5. Null input is absent.
        check(ProtectedPathOps.probeDirectory(null).isEmpty(),
            "probeDirectory: null input is Optional.empty()");

        if (symlinksSupported()) {
            // 6. Symlinked directory resolves to its target.
            Path target = newDir("stdlib/link-target");
            Path link = Files.createSymbolicLink(tmpDir.resolve("stdlib/link"),
                Path.of("link-target"));
            Optional<Path> linked = ProtectedPathOps.probeDirectory(link);
            check(linked.isPresent() && linked.get().equals(target.toRealPath()),
                "probeDirectory: symlinked directory resolves to its target");

            // 7. Dangling symlink is absent.
            Path dangling = Files.createSymbolicLink(tmpDir.resolve("stdlib/dangling"),
                Path.of("does-not-exist"));
            check(ProtectedPathOps.probeDirectory(dangling).isEmpty(),
                "probeDirectory: dangling symlink is Optional.empty()");
        } else {
            System.out.println("  (skipping symlink cases: symlinks unsupported on this platform)");
        }

        // 8. The probe never throws.
        checkNoThrow(() -> ProtectedPathOps.probeDirectory(tmpDir.resolve("stdlib/absent")),
            "probeDirectory: absent path does not throw");
        checkNoThrow(() -> ProtectedPathOps.probeDirectory(null),
            "probeDirectory: null input does not throw");
    }

    // =========================================================================
    // toFileUri
    // =========================================================================

    private static void testToFileUri() {
        System.out.println("-- toFileUri: normalized byte-stable file: URI derivation --");

        // 1. Absolute resolved path derives the exact JDK file URI (percent
        // encoding included) and is byte-stable across calls.
        Path p = realTmp.resolve("uri space.deal");
        ProtectedPathOps.UriResult r = ProtectedPathOps.toFileUri(p);
        if (r instanceof UriResult.Success s) {
            passed++;
            check(s.uri().toString().equals(p.toUri().toString()),
                "toFileUri derives the exact file: URI of the resolved path");
            check(s.uri().toString().startsWith("file:"),
                "toFileUri result is a file: URI");
            check(s.uri().toString().contains("%20"),
                "toFileUri percent-encodes non-URI characters (space)");
            ProtectedPathOps.UriResult r2 = ProtectedPathOps.toFileUri(p);
            check(r2 instanceof UriResult.Success s2 && s2.uri().equals(s.uri()),
                "toFileUri is byte-stable for identical input");
        } else {
            fail("toFileUri on an absolute resolved path should succeed, got " + r);
        }

        // 2. Relative input is a structured failure.
        ProtectedPathOps.UriResult rel = ProtectedPathOps.toFileUri(Path.of("relative/x"));
        check(rel instanceof UriResult.Failure f && f.offendingPath().equals("relative/x")
                && f.reason().contains("absolute"),
            "toFileUri: relative input is a structured failure with the offending path");

        // 3. Null input is a structured failure.
        ProtectedPathOps.UriResult nul = ProtectedPathOps.toFileUri(null);
        check(nul instanceof UriResult.Failure f && f.reason().contains("null"),
            "toFileUri: null input is a structured failure");

        // 4. Never throws.
        checkNoThrow(() -> ProtectedPathOps.toFileUri(Path.of("relative/x")),
            "toFileUri: relative input does not throw");
        checkNoThrow(() -> ProtectedPathOps.toFileUri(null),
            "toFileUri: null input does not throw");
    }

    // =========================================================================
    // Length-prefixed UTF-8 serialization
    // =========================================================================

    private static void testLengthPrefixedUtf8Serialization() {
        System.out.println("-- Length-prefixed UTF-8 serialization (identity-digest helper) --");

        // 1. Exact framing: 8-byte big-endian byte length + exact UTF-8 payload.
        byte[] hello = byteSuccessOrFail(ProtectedPathOps.lengthPrefixedUtf8("hello"),
            "lengthPrefixedUtf8: \"hello\" serializes");
        check(hello.length == 13, "\"hello\" framing is 8 + 5 bytes");
        check(ByteBuffer.wrap(hello, 0, 8).getLong() == 5L,
            "\"hello\" length prefix is the big-endian 8-byte value 5");
        check(Arrays.equals(Arrays.copyOfRange(hello, 8, 13),
                "hello".getBytes(StandardCharsets.UTF_8)),
            "\"hello\" payload is the exact UTF-8 bytes");

        // 2. Empty string frames to a zero length with no payload.
        byte[] empty = byteSuccessOrFail(ProtectedPathOps.lengthPrefixedUtf8(""),
            "lengthPrefixedUtf8: empty string serializes");
        check(empty.length == 8 && ByteBuffer.wrap(empty, 0, 8).getLong() == 0L,
            "empty string frames to a zero length with no payload");

        // 3. Supplementary-plane scalar: U+1F600 encodes as F0 9F 98 80.
        String astral = "\uD83D\uDE00";
        byte[] astralBytes = byteSuccessOrFail(ProtectedPathOps.lengthPrefixedUtf8(astral),
            "lengthPrefixedUtf8: supplementary-plane scalar serializes");
        check(astralBytes.length == 12
                && ByteBuffer.wrap(astralBytes, 0, 8).getLong() == 4L,
            "U+1F600 framing is 8 + 4 bytes");
        check(Arrays.equals(Arrays.copyOfRange(astralBytes, 8, 12),
                new byte[] {(byte) 0xF0, (byte) 0x9F, (byte) 0x98, (byte) 0x80}),
            "U+1F600 payload is the exact four UTF-8 bytes");

        // 4. Mixed scalar widths, including U+0000 (a valid scalar here).
        String mixed = "A\u0000\u007F\u0080\u07FF\u0800\uFFFF\uD83D\uDE00";
        byte[] mixedBytes = byteSuccessOrFail(ProtectedPathOps.lengthPrefixedUtf8(mixed),
            "lengthPrefixedUtf8: mixed scalar widths serialize");
        check(ByteBuffer.wrap(mixedBytes, 0, 8).getLong() == 17L,
            "mixed scalar string byte length is 17 (1+1+1+2+2+3+3+4)");
        check(Arrays.equals(Arrays.copyOfRange(mixedBytes, 8, mixedBytes.length),
                mixed.getBytes(StandardCharsets.UTF_8)),
            "mixed scalar payload matches strict UTF-8");

        // 5. Round-trip: decode(encode(x)) == x for every width, including the
        // supplementary plane and U+0000.
        for (String value : new String[] {"", "hello", astral, mixed, "line1\nline2\ttab"}) {
            byte[] framed = byteSuccessOrFail(ProtectedPathOps.lengthPrefixedUtf8(value),
                "lengthPrefixedUtf8: round-trip input serializes");
            String decoded = decodedSuccessOrFail(
                ProtectedPathOps.decodeLengthPrefixedUtf8(framed),
                "decodeLengthPrefixedUtf8: round-trip payload decodes");
            check(decoded.equals(value),
                "round-trip preserves the exact scalar sequence: \""
                    + value.replace("\n", "\\n").replace("\t", "\\t") + "\"");
        }

        // 6. Byte stability across repeated runs: identical input, identical
        // bytes; encode -> decode -> encode is a fixed point.
        byte[] run1 = byteSuccessOrFail(ProtectedPathOps.lengthPrefixedUtf8(mixed),
            "lengthPrefixedUtf8: first run serializes");
        byte[] run2 = byteSuccessOrFail(ProtectedPathOps.lengthPrefixedUtf8(mixed),
            "lengthPrefixedUtf8: second run serializes");
        check(Arrays.equals(run1, run2),
            "serialization is byte-stable across runs (no state, timestamps, or ordinals)");
        String decodedMixed = decodedSuccessOrFail(
            ProtectedPathOps.decodeLengthPrefixedUtf8(run1),
            "decodeLengthPrefixedUtf8: fixed-point decode succeeds");
        byte[] run3 = byteSuccessOrFail(ProtectedPathOps.lengthPrefixedUtf8(decodedMixed),
            "lengthPrefixedUtf8: fixed-point re-encode serializes");
        check(Arrays.equals(run1, run3),
            "encode -> decode -> encode is a byte-identical fixed point");

        // 7. Unpaired surrogates are structured failures, never replacements.
        ProtectedPathOps.ByteResult high = ProtectedPathOps.lengthPrefixedUtf8("x\uD83Dy");
        check(high instanceof ByteResult.Failure f
                && f.offendingInput().equals("x\uD83Dy")
                && f.reason().contains("scalar"),
            "unpaired high surrogate is a structured ByteResult.Failure with the offending input");
        ProtectedPathOps.ByteResult low = ProtectedPathOps.lengthPrefixedUtf8("x\uDC00y");
        check(low instanceof ByteResult.Failure,
            "unpaired low surrogate is a structured ByteResult.Failure");
        ProtectedPathOps.ByteResult nullEncode = ProtectedPathOps.lengthPrefixedUtf8(null);
        check(nullEncode instanceof ByteResult.Failure f && f.reason().contains("null"),
            "null input is a structured ByteResult.Failure");
        checkNoThrow(() -> ProtectedPathOps.lengthPrefixedUtf8("x\uD83Dy"),
            "lengthPrefixedUtf8: unpaired-surrogate input does not throw");
        checkNoThrow(() -> ProtectedPathOps.lengthPrefixedUtf8(null),
            "lengthPrefixedUtf8: null input does not throw");

        // 8. Malformed framing is a structured decode failure, never a throw.
        ProtectedPathOps.DecodedResult tooShort =
            ProtectedPathOps.decodeLengthPrefixedUtf8(new byte[7]);
        check(tooShort instanceof DecodedResult.Failure f
                && f.reason().contains("shorter"),
            "decodeLengthPrefixedUtf8: 7-byte input is a structured failure");
        ProtectedPathOps.DecodedResult negative =
            ProtectedPathOps.decodeLengthPrefixedUtf8(ByteBuffer.allocate(8).putLong(-1L).array());
        check(negative instanceof DecodedResult.Failure f && f.reason().contains("negative"),
            "decodeLengthPrefixedUtf8: negative length prefix is a structured failure");
        byte[] truncated = ByteBuffer.allocate(9).putLong(5L).put((byte) 'a').array();
        ProtectedPathOps.DecodedResult trunc =
            ProtectedPathOps.decodeLengthPrefixedUtf8(truncated);
        check(trunc instanceof DecodedResult.Failure f && f.reason().contains("exceeds"),
            "decodeLengthPrefixedUtf8: truncated payload is a structured failure");
        byte[] badUtf8 = ByteBuffer.allocate(10).putLong(2L)
            .put((byte) 0xFF).put((byte) 0xFE).array();
        ProtectedPathOps.DecodedResult bad =
            ProtectedPathOps.decodeLengthPrefixedUtf8(badUtf8);
        check(bad instanceof DecodedResult.Failure f && f.reason().contains("UTF-8"),
            "decodeLengthPrefixedUtf8: invalid UTF-8 payload is a structured failure");
        ProtectedPathOps.DecodedResult nullDecode =
            ProtectedPathOps.decodeLengthPrefixedUtf8(null);
        check(nullDecode instanceof DecodedResult.Failure f && f.reason().contains("null"),
            "decodeLengthPrefixedUtf8: null input is a structured failure");
        checkNoThrow(() -> ProtectedPathOps.decodeLengthPrefixedUtf8(new byte[7]),
            "decodeLengthPrefixedUtf8: short input does not throw");
        checkNoThrow(() -> ProtectedPathOps.decodeLengthPrefixedUtf8(null),
            "decodeLengthPrefixedUtf8: null input does not throw");
    }

    // =========================================================================
    // No-throw invariant across every matrix error input
    // =========================================================================

    private static void testNoThrowInvariant() {
        System.out.println("-- Invariant: no operation throws for any error input --");
        String nul = "a\u0000b";
        String high = "x\uD83Dy";
        String low = "x\uDC00y";

        checkNoThrow(() -> ProtectedPathOps.validateInput(nul),
            "validateInput: NUL input is a structured result");
        checkNoThrow(() -> ProtectedPathOps.validateInput(high),
            "validateInput: unpaired high surrogate is a structured result");
        checkNoThrow(() -> ProtectedPathOps.validateInput(low),
            "validateInput: unpaired low surrogate is a structured result");
        checkNoThrow(() -> ProtectedPathOps.validateInput(null),
            "validateInput: null is a structured result");

        for (String input : new String[] {nul, high, low, null}) {
            checkNoThrow(() -> ProtectedPathOps.canonicalizeExisting(input),
                "canonicalizeExisting: error input " + input + " is a structured result");
            checkNoThrow(() -> ProtectedPathOps.normalizePrefixResolved(input),
                "normalizePrefixResolved: error input " + input + " is a structured result");
        }
        checkNoThrow(() -> ProtectedPathOps.canonicalizeExisting(tmpDir.resolve("absent/x").toString()),
            "canonicalizeExisting: missing path is a structured result");
        checkNoThrow(() -> ProtectedPathOps.normalizePrefixResolved(tmpDir.resolve("absent/x").toString()),
            "normalizePrefixResolved: missing path is a structured result");

        checkNoThrow(() -> ProtectedPathOps.probeDirectory(tmpDir.resolve("absent")),
            "probeDirectory: absent directory is a structured result");
        checkNoThrow(() -> ProtectedPathOps.probeDirectory(null),
            "probeDirectory: null is a structured result");
        checkNoThrow(() -> ProtectedPathOps.toFileUri(Path.of("relative")),
            "toFileUri: relative path is a structured result");
        checkNoThrow(() -> ProtectedPathOps.toFileUri(null),
            "toFileUri: null is a structured result");
        checkNoThrow(() -> ProtectedPathOps.lengthPrefixedUtf8(high),
            "lengthPrefixedUtf8: unpaired surrogate is a structured result");
        checkNoThrow(() -> ProtectedPathOps.decodeLengthPrefixedUtf8(new byte[0]),
            "decodeLengthPrefixedUtf8: empty bytes are a structured result");
    }
}
