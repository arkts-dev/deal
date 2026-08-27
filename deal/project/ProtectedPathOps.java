package deal.project;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The single protected host-path boundary of the strict v1.2 project layer
 * (ISSUE-0262, design {@code strict-project-context-resolution-identity}
 * D4). Every host-path conversion of the project/configuration pipeline
 * flows through this class; every operation returns a structured result
 * and no raw path, encoding, or I/O exception escapes any public surface.
 *
 * <p>The pinned per-path-class conversion matrix is:</p>
 *
 * <ul>
 *   <li><b>Entry file</b>, <b>discovered manifest file</b>,
 *       <b>externals declaration files</b>, and <b>import candidates</b>
 *       require existence as a regular, readable file after full symlink
 *       resolution — {@link #canonicalizeExisting(String)}. The caller maps
 *       the structured failure to its domain diagnostic (CliDiagnostic,
 *       E2010, or E2003).</li>
 *   <li><b>{@code moduleRoots} entries</b> and <b>output paths</b> never
 *       require existence — {@link #normalizePrefixResolved(String)}
 *       resolves the longest existing directory prefix through symlinks
 *       and appends the remaining suffix lexically. A not-yet-existing
 *       output directory is a normal value, never a failure.</li>
 *   <li><b>The stdlib surface directory</b> is probed and absence is a
 *       plain value, never a failure — {@link #probeDirectory(Path)}
 *       returns {@link Optional#empty()} for any absent or non-directory
 *       input.</li>
 * </ul>
 *
 * <p>Input validation (NUL and host-representability) runs on every input
 * string before any conversion: {@link #validateInput(String)} rejects
 * NUL (U+0000) and unpaired UTF-16 surrogates, which is exactly the set
 * of strings whose strict UTF-8 materialization as a host path would fail
 * or require a replacement character on Linux. A Linux backslash is an
 * ordinary filename character and passes validation unchanged.</p>
 *
 * <p>The class also owns the shared length-prefixed UTF-8 serialization
 * helper — {@link #lengthPrefixedUtf8(String)} and its round-trip partner
 * {@link #decodeLengthPrefixedUtf8(byte[])} — the sole serialization used
 * by every identity digest in this epic (deployment digest,
 * {@code deploymentModuleId} inputs). Serialization is deterministic:
 * identical inputs produce identical bytes, and no address, timestamp,
 * ordinal, or process state enters any output.</p>
 *
 * <p>This module depends only on the JDK. It holds no mutable state; all
 * operations are pure functions of their inputs plus the actual filesystem
 * state at call time, so symlink resolution reflects the filesystem as it
 * exists when the call runs. Failure mapping (CliDiagnostic / E2010 /
 * E2003-E2009 / OutputPathFailure) is each caller's job, never this
 * module's.</p>
 */
public final class ProtectedPathOps {

    private ProtectedPathOps() {
    }

    /** Number of bytes of the big-endian length prefix. */
    public static final int LENGTH_PREFIX_BYTES = 8;

    // =========================================================================
    // Input validation
    // =========================================================================

    /**
     * A failed input-validation check: the offending input string and the
     * pinned deterministic reason.
     *
     * @param offendingInput the input string that failed validation (the
     *                       literal {@code "<null>"} for a null input)
     * @param reason         the pinned reason text
     */
    public record InputValidationFailure(String offendingInput, String reason) {
        public InputValidationFailure {
            Objects.requireNonNull(reason, "reason");
        }
    }

    /**
     * The shared NUL + host-representability gate for every input string
     * of this boundary: an input is valid iff it contains no NUL (U+0000)
     * and no unpaired UTF-16 surrogate. Every other string — including
     * empty strings, supplementary-plane scalar pairs, and Linux
     * backslashes — is valid.
     *
     * <p>This predicate is exactly the set of strings whose strict UTF-8
     * materialization as a host path succeeds without replacement
     * characters on Linux. It performs no filesystem access, so
     * content-only parsers may reuse it for their value-level NUL and
     * scalar checks.</p>
     *
     * @param input the input string (a null input is invalid)
     * @return {@link Optional#empty()} when the input is valid; otherwise
     *         the offending input and reason
     */
    public static Optional<InputValidationFailure> validateInput(String input) {
        if (input == null) {
            return Optional.of(new InputValidationFailure("<null>", "input is null"));
        }
        int nul = input.indexOf('\u0000');
        if (nul >= 0) {
            return Optional.of(new InputValidationFailure(input,
                "input contains NUL (U+0000) at UTF-16 index " + nul));
        }
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= input.length()
                        || !Character.isLowSurrogate(input.charAt(i + 1))) {
                    return Optional.of(new InputValidationFailure(input,
                        "input contains an unpaired high surrogate (U+"
                            + hex4(c) + ") at UTF-16 index " + i));
                }
                i++; // the low surrogate belongs to this pair
            } else if (Character.isLowSurrogate(c)) {
                return Optional.of(new InputValidationFailure(input,
                    "input contains an unpaired low surrogate (U+"
                        + hex4(c) + ") at UTF-16 index " + i));
            }
        }
        return Optional.empty();
    }

    private static String hex4(char c) {
        String hex = Integer.toHexString(c).toUpperCase();
        return "0000".substring(hex.length()) + hex;
    }

    // =========================================================================
    // Path conversion results
    // =========================================================================

    /**
     * The structured result of a protected path conversion: either a
     * resolved absolute path or a classified failure. A conversion never
     * throws.
     */
    public sealed interface PathResult permits PathResult.Success, PathResult.Failure {

        /**
         * Distinguishes the five pinned failure classes of
         * {@link Failure}.
         */
        enum FailureKind {
            /** NUL or unpaired-surrogate input (never a valid host path). */
            INVALID_INPUT,
            /** The path does not exist (including a dangling symlink). */
            NOT_FOUND,
            /** The path exists but is not a regular file. */
            NOT_REGULAR,
            /** The path is a regular file but is not readable. */
            NOT_READABLE,
            /** An I/O error interrupted resolution or verification. */
            IO_ERROR
        }

        /**
         * A successful conversion: the absolute, lexically normalized,
         * symlink-resolved path.
         */
        record Success(Path resolvedPath) implements PathResult {
            public Success {
                Objects.requireNonNull(resolvedPath, "resolvedPath");
            }
        }

        /**
         * A failed conversion: the classified failure kind, the offending
         * input string, and the pinned deterministic reason.
         */
        record Failure(FailureKind kind, String offendingInput, String reason)
                implements PathResult {
            public Failure {
                Objects.requireNonNull(kind, "kind");
                Objects.requireNonNull(reason, "reason");
            }
        }
    }

    // =========================================================================
    // Conversion: existing-file classes (entry, manifest, declaration, import)
    // =========================================================================

    /**
     * Converts one existing-file-class path (entry file, discovered
     * manifest file, externals declaration file, or import candidate):
     * absolute + lexical normalization, then full symlink resolution, then
     * regular-file and readability verification.
     *
     * <ul>
     *   <li>A path that does not exist — including a dangling symlink —
     *       is {@link PathResult.FailureKind#NOT_FOUND}.</li>
     *   <li>A directory (or any non-regular file) is
     *       {@link PathResult.FailureKind#NOT_REGULAR}.</li>
     *   <li>An existing regular file without read permission is
     *       {@link PathResult.FailureKind#NOT_READABLE}.</li>
     *   <li>NUL or unpaired-surrogate input is
     *       {@link PathResult.FailureKind#INVALID_INPUT}.</li>
     *   <li>Any other I/O failure (including a symlink loop) is
     *       {@link PathResult.FailureKind#IO_ERROR}.</li>
     * </ul>
     *
     * <p>A relative input is made absolute against the process working
     * directory (JDK path semantics); the caller resolves explicit bases
     * (manifest directory, importer directory, process CWD) before calling
     * when the base is not the process CWD. An empty input likewise
     * denotes the process CWD (and therefore fails as NOT_REGULAR here);
     * non-emptiness is a schema concern of the callers, not of this
     * boundary. Equivalent spellings of one file — including symlinked
     * spellings — yield one resolved path.</p>
     *
     * @param path the input path string
     * @return the resolved regular readable file, or the classified failure
     */
    public static PathResult canonicalizeExisting(String path) {
        Optional<InputValidationFailure> invalid = validateInput(path);
        if (invalid.isPresent()) {
            InputValidationFailure f = invalid.get();
            return new PathResult.Failure(PathResult.FailureKind.INVALID_INPUT,
                f.offendingInput(), f.reason());
        }
        Path absolute;
        try {
            absolute = Path.of(path).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            return new PathResult.Failure(PathResult.FailureKind.INVALID_INPUT, path,
                "path cannot be materialized: " + e.getMessage());
        }
        try {
            Path real = absolute.toRealPath();
            if (!Files.isRegularFile(real)) {
                return new PathResult.Failure(PathResult.FailureKind.NOT_REGULAR, path,
                    "path does not name a regular file after full symlink resolution: "
                        + real);
            }
            if (!Files.isReadable(real)) {
                return new PathResult.Failure(PathResult.FailureKind.NOT_READABLE, path,
                    "file is not readable: " + real);
            }
            return new PathResult.Success(real);
        } catch (NoSuchFileException e) {
            return new PathResult.Failure(PathResult.FailureKind.NOT_FOUND, path,
                "path does not exist: " + absolute);
        } catch (IOException e) {
            return new PathResult.Failure(PathResult.FailureKind.IO_ERROR, path,
                "cannot resolve " + absolute + ": " + e.getClass().getSimpleName()
                    + (e.getMessage() != null ? ": " + e.getMessage() : ""));
        }
    }

    // =========================================================================
    // Conversion: roots and output paths (existence never required)
    // =========================================================================

    /**
     * Converts one never-required path ({@code moduleRoots} entry or
     * output path): absolute + lexical normalization, then symlink
     * resolution of the <em>longest existing directory prefix</em> — the
     * longest prefix of the absolute path whose resolution through
     * symlinks yields an existing directory — with the remaining suffix
     * appended lexically and resolved no further.
     *
     * <ul>
     *   <li>Existence is not required: a path that does not exist is a
     *       normal value, and a not-yet-existing output directory is never
     *       a failure.</li>
     *   <li>A path that already names an existing directory has an empty
     *       suffix and resolves fully.</li>
     *   <li>A dangling-symlink prefix (or any prefix component that does
     *       not resolve through symlinks to an existing directory) stops
     *       resolution: the component is not resolved and the suffix
     *       continues from the lexical absolute path.</li>
     *   <li>Only NUL / unpaired-surrogate input
     *       ({@link PathResult.FailureKind#INVALID_INPUT}) and an I/O
     *       failure while resolving the confirmed existing prefix
     *       ({@link PathResult.FailureKind#IO_ERROR}) are failures.</li>
     * </ul>
     *
     * <p>As with {@link #canonicalizeExisting(String)}, a relative input
     * is made absolute against the process CWD and an empty input denotes
     * the process CWD (an existing directory, so it resolves fully);
     * non-emptiness is a schema concern of the callers.</p>
     *
     * @param path the input path string
     * @return the prefix-resolved absolute path, or the classified failure
     */
    public static PathResult normalizePrefixResolved(String path) {
        Optional<InputValidationFailure> invalid = validateInput(path);
        if (invalid.isPresent()) {
            InputValidationFailure f = invalid.get();
            return new PathResult.Failure(PathResult.FailureKind.INVALID_INPUT,
                f.offendingInput(), f.reason());
        }
        Path absolute;
        try {
            absolute = Path.of(path).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            return new PathResult.Failure(PathResult.FailureKind.INVALID_INPUT, path,
                "path cannot be materialized: " + e.getMessage());
        }
        // Walk components from the root; every component that resolves
        // (through symlinks) to an existing directory extends the resolved
        // prefix. The first component that does not — missing, dangling
        // symlink, symlink to a file, plain file — stops the walk and
        // starts the lexical suffix.
        Path prefix = absolute.getRoot();
        List<String> suffix = new ArrayList<>();
        boolean stopped = false;
        for (Path element : absolute) {
            if (stopped) {
                suffix.add(element.toString());
                continue;
            }
            Path candidate = prefix.resolve(element);
            if (isExistingDirectory(candidate)) {
                prefix = candidate;
            } else {
                stopped = true;
                suffix.add(element.toString());
            }
        }
        Path resolvedPrefix;
        try {
            resolvedPrefix = prefix.toRealPath();
        } catch (IOException e) {
            return new PathResult.Failure(PathResult.FailureKind.IO_ERROR, path,
                "cannot resolve existing directory prefix " + prefix + ": "
                    + e.getClass().getSimpleName()
                    + (e.getMessage() != null ? ": " + e.getMessage() : ""));
        }
        Path result = resolvedPrefix;
        for (String element : suffix) {
            result = result.resolve(element);
        }
        return new PathResult.Success(result);
    }

    /**
     * True iff the candidate resolves through symlinks to an existing
     * directory. Never throws: a probe that cannot confirm a directory
     * (missing component, dangling symlink, symlink to a non-directory,
     * I/O failure) is simply false, matching the dangling-symlink rule
     * "resolution stops before it".
     */
    private static boolean isExistingDirectory(Path candidate) {
        try {
            return Files.isDirectory(candidate);
        } catch (SecurityException e) {
            return false;
        }
    }

    // =========================================================================
    // Probe: stdlib surface directory (absence is a value)
    // =========================================================================

    /**
     * Probes the stdlib-surface directory class: the directory is resolved
     * through symlinks when present, and absence is a plain value —
     * {@link Optional#empty()} — never a failure.
     *
     * <p>The probe has no failure mode by design: its input is constructed
     * from already-protected paths ({@code <manifestDirectory>/std} or
     * {@code <processCWD>/std}), a {@link Path} cannot carry a NUL
     * component, and every condition that is not a resolvable directory
     * (absent path, regular file, dangling symlink, unreadable probe) is
     * the single {@link Optional#empty()} result. This method never
     * throws.</p>
     *
     * @param directory the directory path to probe (may be relative; a
     *                  null input is absent)
     * @return the fully symlink-resolved directory when one exists,
     *         otherwise {@link Optional#empty()}
     */
    public static Optional<Path> probeDirectory(Path directory) {
        if (directory == null) {
            return Optional.empty();
        }
        Path absolute = directory.isAbsolute()
            ? directory.normalize()
            : directory.toAbsolutePath().normalize();
        if (!isExistingDirectory(absolute)) {
            return Optional.empty();
        }
        try {
            Path real = absolute.toRealPath();
            return isExistingDirectory(real) ? Optional.of(real) : Optional.empty();
        } catch (IOException | SecurityException e) {
            return Optional.empty();
        }
    }

    // =========================================================================
    // URI derivation
    // =========================================================================

    /**
     * The structured result of {@link ProtectedPathOps#toFileUri(Path)}.
     */
    public sealed interface UriResult permits UriResult.Success, UriResult.Failure {

        /** A derived {@code file:} URI. */
        record Success(URI uri) implements UriResult {
            public Success {
                Objects.requireNonNull(uri, "uri");
            }
        }

        /** A failed derivation: the offending path text and the reason. */
        record Failure(String offendingPath, String reason) implements UriResult {
            public Failure {
                Objects.requireNonNull(reason, "reason");
            }
        }
    }

    /**
     * Derives the normalized, byte-stable {@code file:} URI of a resolved
     * absolute path. The derivation itself performs no filesystem access:
     * the input must already be the product of a protected conversion, and
     * equivalent spellings of one file yield one URI because full symlink
     * resolution has already collapsed them to one real path.
     *
     * @param resolvedPath a protected-resolved absolute path
     * @return the derived URI, or a structured failure for a null or
     *         relative input (or a provider that cannot derive a URI)
     */
    public static UriResult toFileUri(Path resolvedPath) {
        if (resolvedPath == null) {
            return new UriResult.Failure("<null>", "path is null");
        }
        if (!resolvedPath.isAbsolute()) {
            return new UriResult.Failure(resolvedPath.toString(),
                "path must be absolute: " + resolvedPath);
        }
        try {
            return new UriResult.Success(resolvedPath.normalize().toUri());
        } catch (IllegalArgumentException | UnsupportedOperationException e) {
            return new UriResult.Failure(resolvedPath.toString(),
                "cannot derive a file: URI: " + e.getClass().getSimpleName()
                    + (e.getMessage() != null ? ": " + e.getMessage() : ""));
        }
    }

    // =========================================================================
    // Length-prefixed UTF-8 serialization (the identity-digest helper)
    // =========================================================================

    /**
     * The structured result of
     * {@link ProtectedPathOps#lengthPrefixedUtf8(String)}.
     */
    public sealed interface ByteResult permits ByteResult.Success, ByteResult.Failure {

        /**
         * The framed bytes: an 8-byte big-endian byte length followed by
         * the exact UTF-8 bytes of the input.
         */
        record Success(byte[] bytes) implements ByteResult {
            public Success {
                Objects.requireNonNull(bytes, "bytes");
            }
        }

        /**
         * A failed serialization: the offending input string (the literal
         * {@code "<null>"} for a null input) and the reason.
         */
        record Failure(String offendingInput, String reason) implements ByteResult {
            public Failure {
                Objects.requireNonNull(reason, "reason");
            }
        }
    }

    /**
     * The structured result of
     * {@link ProtectedPathOps#decodeLengthPrefixedUtf8(byte[])}.
     */
    public sealed interface DecodedResult
            permits DecodedResult.Success, DecodedResult.Failure {

        /** The strictly decoded scalar sequence. */
        record Success(String value) implements DecodedResult {
            public Success {
                Objects.requireNonNull(value, "value");
            }
        }

        /** A failed decode: the pinned deterministic reason. */
        record Failure(String reason) implements DecodedResult {
            public Failure {
                Objects.requireNonNull(reason, "reason");
            }
        }
    }

    /**
     * The shared length-prefixed UTF-8 serialization: the 8-byte
     * big-endian byte length of the strict UTF-8 encoding, followed by the
     * exact UTF-8 bytes. This is the sole serialization used by every
     * identity digest in this epic (deployment digest,
     * {@code deploymentModuleId} inputs).
     *
     * <p>Encoding is strict: an input containing an unpaired UTF-16
     * surrogate is not a Unicode scalar sequence and fails with a
     * structured {@link ByteResult.Failure} — never a replacement
     * character, never an exception. U+0000 is a valid scalar and encodes
     * normally. Supplementary-plane scalars encode as their four UTF-8
     * bytes. Identical input always produces identical bytes; no address,
     * timestamp, ordinal, or process state enters the output.</p>
     *
     * @param value the scalar sequence to frame (a null input fails)
     * @return the framed bytes, or the structured failure
     */
    public static ByteResult lengthPrefixedUtf8(String value) {
        if (value == null) {
            return new ByteResult.Failure("<null>", "input is null");
        }
        byte[] utf8;
        try {
            utf8 = strictUtf8Encode(value);
        } catch (CharacterCodingException e) {
            return new ByteResult.Failure(value,
                "input contains an unpaired UTF-16 surrogate and is not a"
                    + " Unicode scalar sequence");
        }
        byte[] framed = new byte[LENGTH_PREFIX_BYTES + utf8.length];
        ByteBuffer.wrap(framed).putLong(utf8.length);
        System.arraycopy(utf8, 0, framed, LENGTH_PREFIX_BYTES, utf8.length);
        return new ByteResult.Success(framed);
    }

    /**
     * The strict round-trip partner of
     * {@link #lengthPrefixedUtf8(String)}: reads the 8-byte big-endian
     * byte length and strictly UTF-8-decodes exactly the following payload
     * bytes. Malformed framing — too-short input, a negative length, a
     * length exceeding the available payload, or a payload that is not
     * valid UTF-8 — is a structured {@link DecodedResult.Failure}, never
     * a replacement character, never an exception.
     *
     * @param bytes the framed bytes (a null input fails)
     * @return the decoded scalar sequence, or the structured failure
     */
    public static DecodedResult decodeLengthPrefixedUtf8(byte[] bytes) {
        if (bytes == null) {
            return new DecodedResult.Failure("input is null");
        }
        if (bytes.length < LENGTH_PREFIX_BYTES) {
            return new DecodedResult.Failure("input is shorter than the "
                + LENGTH_PREFIX_BYTES + "-byte length prefix: " + bytes.length
                + " bytes");
        }
        long length = ByteBuffer.wrap(bytes, 0, LENGTH_PREFIX_BYTES).getLong();
        if (length < 0) {
            return new DecodedResult.Failure("length prefix is negative: " + length);
        }
        if (length > bytes.length - LENGTH_PREFIX_BYTES) {
            return new DecodedResult.Failure("length prefix " + length
                + " exceeds the available payload of "
                + (bytes.length - LENGTH_PREFIX_BYTES) + " bytes");
        }
        byte[] payload = new byte[(int) length];
        System.arraycopy(bytes, LENGTH_PREFIX_BYTES, payload, 0, (int) length);
        try {
            return new DecodedResult.Success(strictUtf8Decode(payload));
        } catch (CharacterCodingException e) {
            return new DecodedResult.Failure("payload is not strictly valid UTF-8: "
                + e.getClass().getSimpleName());
        }
    }

    /**
     * Strict UTF-8 encoding (REPORT for malformed input): produces the
     * exact UTF-8 bytes of a valid Unicode scalar sequence and fails for
     * an unpaired surrogate. A fresh encoder is created per call so no
     * shared mutable encoder state exists.
     */
    private static byte[] strictUtf8Encode(String value) throws CharacterCodingException {
        CharsetEncoder encoder = StandardCharsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
        ByteBuffer buffer = encoder.encode(CharBuffer.wrap(value));
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return bytes;
    }

    /**
     * Strict UTF-8 decoding (REPORT for malformed input): fails for any
     * malformed byte sequence instead of substituting a replacement
     * character. A fresh decoder is created per call so no shared mutable
     * decoder state exists.
     */
    private static String strictUtf8Decode(byte[] bytes) throws CharacterCodingException {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
        return decoder.decode(ByteBuffer.wrap(bytes)).toString();
    }
}
