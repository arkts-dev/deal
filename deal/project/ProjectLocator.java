package deal.project;

import deal.diagnostics.CompilerDiagnostic;
import deal.diagnostics.DiagnosticCode;
import deal.diagnostics.DiagnosticNote;
import deal.diagnostics.DiagnosticRange;
import deal.diagnostics.RangeOrigin;
import deal.distribution.DistributionHome;
import deal.lexer.CompilerDirective;
import deal.lexer.DirectiveName;
import deal.lexer.LexResult;
import deal.lexer.Lexer;
import deal.source.ScalarSourceCursor;
import deal.source.SourceScalarRange;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The exact-v1.2 project locator (design source
 * {@code strict-project-context-resolution-identity} D1,
 * {@code deal-v1.2-directives-and-c-ffi-declarations} D5/D10/D11,
 * {@code deal-v1.2-int32-and-bytes-architecture} D5):
 * {@code locate(entryFile, cliOverrides) -> ProjectContext | E2010 |
 * CliDiagnostic} — one immutable {@link ProjectContext} or exactly one
 * structured failure.
 *
 * <p>The eight-step pipeline (D1):</p>
 * <ol>
 *   <li><b>Entry validation</b> —
 *       {@link ProtectedPathOps#validateEntry(String)}: non-empty,
 *       NUL-free, host-representable, symlink-resolved to a regular
 *       readable file. Any failure is a {@link CliDiagnostic} (a
 *       CLI-supplied value), never E2010.</li>
 *   <li><b>Ancestor discovery</b> — walk the symlink-resolved parent
 *       chain of the validated entry up to and including the filesystem
 *       root and collect every regular {@code deal.json} (regular after
 *       resolution). Zero → one E2010 with a v1.2-manifest note; two or
 *       more → one E2010 with candidate-path notes. The walk continues to
 *       the root even after a hit, so the multiple case reports every
 *       candidate; the compiler never guesses. Discovery E2010s use the
 *       pinned synthetic range {@code (entryFile,1,1,1,1,0,0,0,SYNTHETIC)}
 *       with candidate/manifest notes.</li>
 *   <li><b>Read + strict decode, then strict parse, then overrides</b> —
 *       the discovered manifest's exact bytes are read once and decoded
 *       with a strict UTF-8 decoder in REPORT mode (malformed and
 *       unmappable input report). Any malformed byte sequence — invalid
 *       continuation, truncated final sequence, overlong form, or
 *       CESU-8 surrogate-range encoding — is one E2010 with a SOURCE
 *       range in the manifest file at the position where the malformed
 *       sequence begins (the UTF-16 index equal to the length of the
 *       strictly decoded valid prefix), span length 1. No replacement
 *       character is produced: U+FFFD reaches the strict pass only
 *       through a literal {@code \uFFFD} escape (valid per D2). A leading
 *       UTF-8 BOM is not stripped and fails the strict pass as an
 *       out-of-alphabet scalar. The strictly decoded text is the sole
 *       parser input ({@link StrictManifestParser} performs no byte
 *       decoding). A malformed manifest fails E2010 before any override
 *       is consulted. On a valid manifest the overrides are validated: a
 *       valid CLI backend alias {@code lua|luajit|jvm|js} (trim +
 *       lowercase) overrides the manifest backend; a valid CLI output
 *       string
 *       (trimmed, non-empty, scalar-valid, NUL-free, host-representable)
 *       overrides the manifest output, with the trimmed value winning. An
 *       empty/whitespace-only or otherwise invalid override is a
 *       {@link CliDiagnostic}; overrides can never bypass a malformed
 *       manifest.</li>
 *   <li><b>Roots and externals declarations</b> — (a) every
 *       {@code moduleRoots} entry resolves from the manifest directory
 *       through the D4 root conversion (no existence requirement;
 *       absolute + lexical normalization + symlink resolution of the
 *       longest existing directory prefix) into a
 *       {@link ConfiguredModuleRoot}; two entries with equal converted
 *       {@code absoluteNormalizedPath}s are E2010 at the second member's
 *       value range (symlink-alias spellings included). No implicit root
 *       is added. (b) every externals entry's declaration file must exist
 *       as a regular readable file, fully symlink-resolved, completing
 *       each {@link ExternalEntrySpec} into an {@link ExternalEntry};
 *       missing/unreadable is E2010 at the declaration value range. An
 *       entry whose declaration resolves to one of the six spec-listed
 *       stdlib declaration files under the pinned stdlib surface is E2010
 *       at the declaration value range with a note naming the stdlib
 *       module (stdlib-overlap rejection — standard library modules are
 *       language-distribution modules, not project externals). An entry
 *       whose declaration file lexes with an {@code @extern-c} directive
 *       event (the production Lexer's structured events — never a
 *       textual scan) and whose {@code nativeLibrary} member is absent
 *       is E2010 at the entry object's value range (the extern-C
 *       nativeLibrary policy, spec {@code docs/spec-v1.2.md:1891}; a
 *       present-but-invalid nativeLibrary is already the
 *       {@link StrictManifestParser}/{@link ProjectConfigValidator}
 *       E2010, and a present nativeLibrary never fires the check — the
 *       scan is best-effort and backend-independent). All per-entry
 *       checks run in member order; two entries whose declaration files
 *       resolve to the same canonical path are E2010 at the second
 *       entry's declaration value range, checked after the per-entry
 *       checks.</li>
 *   <li><b>Output and backend</b> — the effective backend follows the D3
 *       backend-selection rule, then output classification/conversion is
 *       delegated to {@link OutputConfigResolver}. A MANIFEST-source
 *       output failure is E2010; a CLI-source failure is
 *       {@link CliDiagnostic}. No directory is created during locate and
 *       the conversion never requires the path to exist.</li>
 *   <li><b>Stdlib surface</b> — the pinned three-tier resolution of
 *       {@link DistributionHome}
 *       ({@code release-distribution-packaging-and-discovery} D3):
 *       {@code <manifestDirectory>/std} when it exists as a directory
 *       (the pinned v1.2 project-local override surface), else the
 *       language distribution (the classpath-resource {@code std/}
 *       directory when a pinned declaration resource materializes,
 *       then the {@code DEAL_HOME}/{@code deal.home} filesystem
 *       {@code std/} directory), else {@code <processCWD>/std} when
 *       that exists as a directory, else absent (absence is not an
 *       error). The six spec-listed files under the surface are
 *       canonicalized once (fully symlink-resolved; only existing
 *       regular resolvable files contribute) and published as the
 *       context's {@code stdlibDeclarationFiles}; the same canonical
 *       paths supply step 4(b)'s stdlib-overlap check (a symlinked
 *       spec-listed file keeps its pinned identity for its resolved
 *       target).</li>
 *   <li><b>Deployment identity</b> — {@link ProjectDeploymentIdentity}
 *       per D4: the symlink-resolved {@code file:} URI via
 *       {@link ProtectedPathOps#toFileUri(Path)} after protected
 *       validation, and SHA-256 (64 lowercase hex chars) over the exact
 *       manifest bytes that passed the strict decode.</li>
 *   <li><b>Publication</b> — one immutable {@link ProjectContext}. Any
 *       failure publishes no context, creates no directory or artifact,
 *       and loads no native code.</li>
 * </ol>
 *
 * <p><b>Fail-fast:</b> exactly one E2010 per failed configuration read in
 * the pinned order — strict UTF-8 decode (first offending byte) → T2
 * scan-order → T2 post-walk canonical order → step 4 (root conversion
 * order, normalized-root duplicates, externals declaration
 * existence/readability in member order, per-entry stdlib-overlap in
 * member order, per-entry extern-C nativeLibrary policy in member
 * order, cross-entry duplicate declarations) → step 5 output
 * conversion → later steps. Failure classification is pinned: E2010 for
 * manifest discovery, byte-level decode, schema, duplicates, roots,
 * externals declarations (including stdlib-overlap), and manifest
 * output; {@link CliDiagnostic} for malformed entry and malformed CLI
 * overrides; E2003/E2009 are the import-resolution surface of later
 * tasks; post-validation artifact write failures are deterministic
 * compiler I/O diagnostics. A missing stdlib surface is not a
 * configuration error. Every emitted diagnostic carries a complete
 * {@link DiagnosticRange} per the pinned shapes (SOURCE scalar ranges for
 * anchored sites; the canonical synthetic shape plus anchor notes for
 * anchorless sites).</p>
 *
 * <p>This class depends only on the JDK, {@code deal.source} (the
 * {@link ScalarSourceCursor} position arithmetic),
 * {@code deal.diagnostics} (the range carrier), and {@code deal.lexer}
 * (the single directive-recognition authority for the step 4(b)
 * extern-C scan; the lexer imports nothing from
 * {@code deal.project}). No raw path, encoding,
 * JSON, or I/O exception escapes {@link #locate(String, CliOverrides)}:
 * every failure is a structured result.</p>
 */
public final class ProjectLocator {

    private ProjectLocator() {
    }

    /** The exact v1.2 language version every published context carries. */
    public static final String LANGUAGE_VERSION = "1.2";

    /** The six spec-listed stdlib modules, in pinned order. */
    public static final List<String> SPEC_STDLIB_MODULES = List.of(
        "console", "string", "table", "json", "math", "time");

    /** The valid CLI backend aliases of D1 step 3 (trim + lowercase):
     * {@code lua}/{@code luajit} (LuaJIT), {@code jvm}, and {@code js}. */
    public static final Set<String> CLI_BACKEND_ALIASES = Set.of(
        "lua", "luajit", "jvm", "js");

    // =========================================================================
    // The result channel
    // =========================================================================

    /**
     * The sole result of {@link #locate(String, CliOverrides)}: exactly
     * one of {@code context} (success), {@code e2010} (manifest
     * configuration failure), and {@code cliDiagnostic} (CLI-supplied
     * value failure) is non-null. A failure publishes no context.
     */
    public record LocateResult(ProjectContext context, CompilerDiagnostic e2010,
                               CliDiagnostic cliDiagnostic) {
        public LocateResult {
            int nonNull = 0;
            if (context != null) {
                nonNull++;
            }
            if (e2010 != null) {
                nonNull++;
            }
            if (cliDiagnostic != null) {
                nonNull++;
            }
            if (nonNull != 1) {
                throw new IllegalArgumentException(
                    "exactly one of context, e2010, cliDiagnostic must be non-null");
            }
        }
    }

    // =========================================================================
    // locate
    // =========================================================================

    /**
     * Locates, strictly reads/decodes/parses, converts, and validates the
     * exact-v1.2 project configuration of {@code entryFile} and publishes
     * one immutable {@link ProjectContext} — or exactly one structured
     * failure.
     *
     * <p>Synchronous, deterministic, and side-effect-free apart from
     * filesystem reads: no directory or artifact is created, no native
     * code is loaded, and no override is applied on failure.</p>
     *
     * @param entryFile    the CLI-supplied entry-file value (may be any
     *                     string; null and empty are
     *                     {@link CliDiagnostic}s)
     * @param cliOverrides the raw CLI overrides (backend/output), or null
     *                     for no overrides
     * @return one immutable context, or exactly one E2010 /
     *         {@link CliDiagnostic}; never null, never throws
     */
    public static LocateResult locate(String entryFile, CliOverrides cliOverrides) {
        CliOverrides overrides = cliOverrides == null
            ? new CliOverrides(null, null)
            : cliOverrides;

        // ---- Step 1: entry validation (CliDiagnostic domain). ----------
        ProtectedPathOps.PathResult entry =
            ProtectedPathOps.validateEntry(entryFile);
        if (entry instanceof ProtectedPathOps.PathResult.Failure entryFailure) {
            return cliFailure("deal: entry file "
                + describe(entryFile) + " is not a valid source file: "
                + entryFailure.reason(), entryFile);
        }
        String entryRealText =
            ((ProtectedPathOps.PathResult.Success) entry).resolvedPath().toString();

        // ---- Step 2: ancestor discovery (E2010 domain). ----------------
        List<String> candidates = discoverManifests(entryRealText);
        if (candidates.size() != 1) {
            return discoveryFailure(entryFile, candidates);
        }
        String manifestCandidate = candidates.get(0);

        // The D4 manifest row: regular + readable + full symlink
        // resolution (re-verified after discovery; an unreadable manifest
        // is an E2010 in the discovery domain).
        ProtectedPathOps.PathResult manifestPath =
            ProtectedPathOps.canonicalizeExisting(manifestCandidate);
        if (manifestPath instanceof ProtectedPathOps.PathResult.Failure manifestFailure) {
            return e2010("deal.json '" + manifestCandidate
                    + "' is not a regular readable file: " + manifestFailure.reason(),
                DiagnosticRange.synthetic(entryFile),
                List.of(anchorNote(entryFile),
                    new DiagnosticNote("candidate manifest: " + manifestCandidate, null)));
        }
        String manifestPathText =
            ((ProtectedPathOps.PathResult.Success) manifestPath).resolvedPath().toString();

        // ---- Step 3: read once + strict REPORT-mode UTF-8 decode. ------
        byte[] manifestBytes;
        try {
            manifestBytes = Files.readAllBytes(Path.of(manifestPathText));
        } catch (IOException e) {
            return e2010("deal: cannot read deal.json '" + manifestPathText + "': "
                    + e.getClass().getSimpleName()
                    + (e.getMessage() != null ? ": " + e.getMessage() : ""),
                DiagnosticRange.synthetic(entryFile),
                List.of(anchorNote(entryFile),
                    new DiagnosticNote("candidate manifest: " + manifestCandidate, null)));
        }
        StrictDecode decode = strictUtf8Decode(manifestBytes);
        if (decode.malformedOffset() >= 0) {
            return decodeFailure(manifestPathText, decode);
        }
        String decodedText = decode.text();

        // ---- Step 3 (cont.): strict parse, then overrides. -------------
        StrictManifestParser.StrictManifestParseResult parsed =
            StrictManifestParser.parse(manifestPathText, decodedText);
        if (parsed.failure() != null) {
            return new LocateResult(null, parsed.failure(), null);
        }
        ProjectManifest manifest = parsed.manifest();

        // Overrides are validated only on a valid manifest (a malformed
        // manifest fails E2010 before any override is consulted).
        String cliBackendAlias = null;
        if (overrides.backend() != null) {
            String alias = overrides.backend().trim().toLowerCase(Locale.ROOT);
            if (!CLI_BACKEND_ALIASES.contains(alias)) {
                return cliFailure("deal: unknown backend alias '"
                    + overrides.backend()
                    + "'; supported aliases: lua, luajit, jvm, js", entryFile);
            }
            cliBackendAlias = alias;
        }
        String cliOutput = null;
        if (overrides.output() != null) {
            String trimmed = overrides.output().trim();
            if (trimmed.isEmpty()) {
                return cliFailure("deal: output override must be a non-empty"
                    + " string after trimming", entryFile);
            }
            Optional<ProtectedPathOps.InputValidationFailure> invalid =
                ProtectedPathOps.validateInput(trimmed);
            if (invalid.isPresent()) {
                return cliFailure("deal: output override '" + overrides.output()
                    + "' is invalid: " + invalid.get().reason(), entryFile);
            }
            cliOutput = trimmed;
        }

        // The stdlib-surface pure probe (step 6) supplies step 4(b)'s
        // stdlib-overlap check and the published canonical declaration
        // files. The probe has no failure mode — absence is a value — so
        // consulting it here cannot reorder or duplicate any diagnostic.
        String manifestDirectoryText =
            Path.of(manifestPathText).getParent().toString();
        String stdlibSurface = probeStdlibSurface(manifestDirectoryText);
        List<String> stdlibDeclarationFiles =
            resolveStdlibDeclarationFiles(stdlibSurface);

        // ---- Step 4(a): roots (conversion order, then duplicates). -----
        List<ConfiguredModuleRoot> convertedRoots = new ArrayList<>();
        for (ManifestString root : manifest.moduleRoots()) {
            String absoluteText = joinPathText(manifestDirectoryText, root.value());
            ProtectedPathOps.PathResult converted =
                ProtectedPathOps.normalizePrefixResolved(absoluteText);
            if (converted instanceof ProtectedPathOps.PathResult.Failure rootFailure) {
                return e2010("deal.json: 'moduleRoots' entry '" + root.value()
                        + "' cannot be converted: " + rootFailure.reason(),
                    sourceRange(manifestPathText, root.sourceRange()), null);
            }
            convertedRoots.add(new ConfiguredModuleRoot(root.value(),
                ((ProtectedPathOps.PathResult.Success) converted).resolvedPath().toString(),
                root.sourceRange()));
        }
        Map<String, String> seenRootPaths = new LinkedHashMap<>();
        for (ConfiguredModuleRoot root : convertedRoots) {
            String first = seenRootPaths.get(root.absoluteNormalizedPath());
            if (first != null) {
                return e2010("deal.json: 'moduleRoots' entries '" + first + "' and '"
                        + root.configuredText()
                        + "' resolve to the same directory '"
                        + root.absoluteNormalizedPath() + "'",
                    sourceRange(manifestPathText, root.sourceRange()), null);
            }
            seenRootPaths.put(root.absoluteNormalizedPath(), root.configuredText());
        }

        // ---- Step 4(b): externals declarations. ------------------------
        // Per-entry checks in member order (existence/readability, then
        // stdlib-overlap); cross-entry duplicate detection afterwards.
        List<ExternalEntry> completedExternals = new ArrayList<>();
        for (ExternalEntrySpec spec : manifest.externals().values()) {
            String declarationText = spec.declaration().value();
            String absoluteText = declarationText.startsWith("/")
                ? declarationText
                : joinPathText(manifestDirectoryText, declarationText);
            ProtectedPathOps.PathResult converted =
                ProtectedPathOps.canonicalizeExisting(absoluteText);
            if (converted instanceof ProtectedPathOps.PathResult.Failure
                    declarationFailure) {
                return e2010("deal.json: externals entry '" + spec.rawImportSpecifier()
                        + "': declaration '" + declarationText
                        + "' is not an existing regular readable file: "
                        + declarationFailure.reason(),
                    sourceRange(manifestPathText, spec.declaration().sourceRange()), null);
            }
            String declarationPathText =
                ((ProtectedPathOps.PathResult.Success) converted).resolvedPath().toString();
            String overlapped = stdlibModuleOf(stdlibDeclarationFiles,
                declarationPathText);
            if (overlapped != null) {
                return e2010("deal.json: externals entry '" + spec.rawImportSpecifier()
                        + "': declaration '" + declarationText
                        + "' resolves to the pinned standard library declaration file"
                        + " 'std/" + overlapped + ".d.deal'",
                    sourceRange(manifestPathText, spec.declaration().sourceRange()),
                    List.of(new DiagnosticNote("stdlib module '" + overlapped
                        + "' is resolved as part of the language distribution rather"
                        + " than as a project external host module"
                        + " (docs/spec-v1.2.md:1890)", null)));
            }
            if (spec.nativeLibrary() == null
                    && declaresExternC(declarationPathText)) {
                return e2010("deal.json: externals entry '" + spec.rawImportSpecifier()
                        + "': declaration '" + declarationText
                        + "' is an extern-C declaration without a nativeLibrary:"
                        + " a C FFI entry must include nativeLibrary"
                        + " (docs/spec-v1.2.md:1891)",
                    sourceRange(manifestPathText, spec.sourceRange()), null);
            }
            completedExternals.add(new ExternalEntry(spec.rawImportSpecifier(),
                new NormalizedDeclarationPath(declarationPathText,
                    spec.declaration().sourceRange()),
                spec.nativeLibrary(), spec.sourceRange()));
        }
        Map<String, String> seenDeclarationPaths = new LinkedHashMap<>();
        for (ExternalEntry completedEntry : completedExternals) {
            String path = completedEntry.declarationPath().absoluteNormalizedPath();
            String first = seenDeclarationPaths.get(path);
            if (first != null) {
                return e2010("deal.json: externals entries '" + first + "' and '"
                        + completedEntry.rawImportSpecifier()
                        + "' declare the same file '" + path + "'",
                    sourceRange(manifestPathText,
                        completedEntry.declarationPath().sourceRange()), null);
            }
            seenDeclarationPaths.put(path, completedEntry.rawImportSpecifier());
        }

        // ---- Step 5: effective backend and output (D3). ----------------
        OutputConfigResolver.Resolution resolution = OutputConfigResolver.resolve(
            manifest.backend(), manifest.output(), cliBackendAlias, cliOutput,
            Path.of(manifestDirectoryText), Path.of("").toAbsolutePath());
        if (resolution.failure() != null) {
            return mapOutputFailure(resolution.failure(), entryFile, manifestPathText);
        }

        // ---- Step 7: deployment identity (D4). -------------------------
        ProtectedPathOps.UriResult uri =
            ProtectedPathOps.toFileUri(Path.of(manifestPathText));
        if (uri instanceof ProtectedPathOps.UriResult.Failure uriFailure) {
            return e2010("deal: cannot derive the canonical file: URI of deal.json '"
                    + manifestPathText + "': " + uriFailure.reason(),
                DiagnosticRange.synthetic(manifestPathText),
                List.of(anchorNote(manifestPathText)));
        }
        String digest = sha256Hex(manifestBytes);

        // ---- Step 8: publication. --------------------------------------
        Map<String, ExternalEntry> externals = new LinkedHashMap<>();
        for (ExternalEntry completedEntry : completedExternals) {
            externals.put(completedEntry.rawImportSpecifier(), completedEntry);
        }
        ProjectContext context = new ProjectContext(
            manifestPathText,
            manifestDirectoryText,
            manifestDirectoryText,
            LANGUAGE_VERSION,
            convertedRoots,
            resolution.output(),
            resolution.effectiveBackend(),
            externals,
            manifest.stdlib(),
            stdlibSurface,
            stdlibDeclarationFiles,
            new ProjectDeploymentIdentity(
                ((ProtectedPathOps.UriResult.Success) uri).uri().toString(), digest));
        return new LocateResult(context, null, null);
    }

    // =========================================================================
    // Step 2: ancestor discovery
    // =========================================================================

    /**
     * Walks the symlink-resolved parent chain of the validated entry up to
     * and including the filesystem root, collecting every regular
     * {@code deal.json} (regular after symlink resolution) in nearest-
     * ancestor-first order. The walk continues to the root even after a
     * hit, so the multiple-manifest case reports every candidate; a
     * non-regular or unresolvable {@code deal.json} is simply not a
     * candidate (the compiler never guesses a different file).
     *
     * <p>Never throws: a {@code deal.json} that cannot be fully resolved
     * (e.g. a dangling symlink, a symlink loop, or an I/O race) is not
     * "regular after resolution" and is skipped.</p>
     *
     * @param entryRealText the symlink-resolved entry path text
     * @return the candidate manifest paths (fully resolved), nearest first
     */
    private static List<String> discoverManifests(String entryRealText) {
        List<String> candidates = new ArrayList<>();
        Path dir = Path.of(entryRealText).getParent();
        while (dir != null) {
            Path candidate = dir.resolve("deal.json");
            if (Files.isRegularFile(candidate)) {
                try {
                    candidates.add(candidate.toRealPath().toString());
                } catch (IOException ignored) {
                    // Not regular after full resolution: not a candidate.
                }
            }
            dir = dir.getParent();
        }
        return candidates;
    }

    /**
     * The pinned discovery E2010: the canonical synthetic range anchored
     * at the entry-file value plus the v1.2-manifest note (zero
     * candidates) or one candidate-path note per discovered manifest
     * (multiple candidates), nearest ancestor first.
     */
    private static LocateResult discoveryFailure(String entryFile,
                                                 List<String> candidates) {
        List<DiagnosticNote> notes = new ArrayList<>();
        notes.add(anchorNote(entryFile));
        String message;
        if (candidates.isEmpty()) {
            message = "deal: no deal.json project manifest found among the ancestor"
                + " directories of entry file '" + describe(entryFile) + "'";
            notes.add(new DiagnosticNote("a v1.2 project requires exactly one ancestor"
                + " deal.json with languageVersion \"1.2\"", null));
        } else {
            message = "deal: multiple deal.json project manifests found among the"
                + " ancestor directories of entry file '" + describe(entryFile)
                + "'; exactly one ancestor manifest may govern a project";
            for (String candidate : candidates) {
                notes.add(new DiagnosticNote("candidate manifest: " + candidate, null));
            }
        }
        return e2010(message, DiagnosticRange.synthetic(entryFile), notes);
    }

    // =========================================================================
    // Step 3: strict UTF-8 decode (REPORT mode)
    // =========================================================================

    /**
     * The strict-decode outcome: either the fully decoded scalar text
     * ({@code malformedOffset == -1}) or the decoded prefix up to — and
     * excluding — the first malformed byte sequence, plus the byte offset
     * where that sequence begins and its length.
     */
    private record StrictDecode(String text, int malformedOffset, int malformedLength,
                                boolean malformed) {
        private StrictDecode {
            Objects.requireNonNull(text, "text");
            if ((malformedOffset >= 0) != (malformedLength > 0)) {
                throw new IllegalArgumentException(
                    "malformedOffset and malformedLength must agree");
            }
        }
    }

    /**
     * Strict UTF-8 decoding in REPORT mode: every malformed byte sequence
     * — invalid continuation, truncated final sequence, overlong form, or
     * CESU-8 surrogate-range encoding — stops the decode. No replacement
     * character is ever produced.
     *
     * <p>The failure reports the byte offset where the malformed sequence
     * <b>begins</b> (the JDK decoder rewinds its input position to the
     * sequence start, verified for all four malformed classes) and the
     * text strictly decoded before that offset; the UTF-16 length of that
     * text is the pinned anchor position ("the UTF-16 index equal to the
     * length of the strictly decoded valid prefix").</p>
     *
     * @param bytes the exact manifest bytes
     * @return the decoded text, or the decoded prefix plus the first
     *         malformed sequence's start offset and length
     */
    private static StrictDecode strictUtf8Decode(byte[] bytes) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
        ByteBuffer input = ByteBuffer.wrap(bytes);
        StringBuilder out = new StringBuilder();
        CharBuffer chunk = CharBuffer.allocate(4096);
        while (true) {
            CoderResult result = decoder.decode(input, chunk, true);
            if (result.isError()) {
                chunk.flip();
                out.append(chunk);
                return new StrictDecode(out.toString(), input.position(), result.length(),
                    result.isMalformed());
            }
            if (result.isOverflow()) {
                chunk.flip();
                out.append(chunk);
                chunk.clear();
                continue;
            }
            CoderResult flushed = decoder.flush(chunk);
            chunk.flip();
            out.append(chunk);
            chunk.clear();
            if (flushed.isUnderflow()) {
                return new StrictDecode(out.toString(), -1, 0, true);
            }
            if (flushed.isError()) {
                return new StrictDecode(out.toString(), input.position(), flushed.length(),
                    flushed.isMalformed());
            }
            // Flush overflow: loop; decode returns underflow and flush
            // resumes where it left off.
        }
    }

    /**
     * The pinned decode E2010: a SOURCE range in the manifest file at the
     * position where the first malformed byte sequence begins — the
     * decoded-scalar position after the strictly decoded valid prefix
     * (whose UTF-16 index is exactly the prefix length) — with span
     * length 1. The range is computed before any schema check.
     */
    private static LocateResult decodeFailure(String manifestPathText,
                                              StrictDecode decode) {
        String prefix = decode.text();
        ScalarSourceCursor cursor = new ScalarSourceCursor(prefix);
        while (!cursor.atEnd()) {
            cursor.advance();
        }
        DiagnosticRange range = new DiagnosticRange(manifestPathText,
            cursor.line(), cursor.column(), cursor.line(), cursor.column() + 1,
            cursor.scalarOffset(), cursor.scalarOffset() + 1, 1, RangeOrigin.SOURCE);
        String kind = decode.malformed() ? "malformed" : "unmappable";
        return e2010("deal.json '" + manifestPathText + "' is not strictly valid UTF-8: "
                + kind + " byte sequence of length " + decode.malformedLength()
                + " begins at byte offset " + decode.malformedOffset()
                + " (a replacement character is never substituted)",
            range, null);
    }

    // =========================================================================
    // Step 4(b): stdlib surface and overlap
    // =========================================================================

    /**
     * The stdlib-surface probe (step 6), resolved through the pinned
     * three-tier {@link DistributionHome} order
     * ({@code release-distribution-packaging-and-discovery} D3): the
     * project-local {@code <manifestDirectory>/std} directory first
     * (the pinned v1.2 override surface), then the language
     * distribution (the materialized classpath-resource {@code std/}
     * directory, then the {@code DEAL_HOME}/{@code deal.home}
     * filesystem {@code std/} directory), then the checkout
     * {@code <processCWD>/std} dev fallback, else absent
     * ({@code null}). Absence is a plain value, never a failure; the
     * returned path is fully symlink-resolved when present (the pinned
     * step-6 shape).
     */
    private static String probeStdlibSurface(String manifestDirectoryText) {
        Optional<DistributionHome.ResolvedSurface> surface =
            DistributionHome.forManifestDirectory(manifestDirectoryText)
                .resolveStdlibSurface();
        if (surface.isEmpty()) {
            return null;
        }
        Optional<Path> resolved = ProtectedPathOps.probeDirectory(
            Path.of(surface.get().pathText()));
        return resolved.map(Path::toString).orElse(null);
    }

    /**
     * The six spec-listed stdlib declaration files under the pinned
     * surface, each fully symlink-resolved (D1 step 6 / D6 (1)): for
     * each module in {@link #SPEC_STDLIB_MODULES} (pinned order),
     * {@code <surface>/<module>.d.deal} contributes its canonical
     * {@code toRealPath} text when the file exists as a regular, fully
     * resolvable file. Missing, non-regular, or unresolvable files (a
     * dangling symlink, a symlink loop, an I/O race) are omitted: they
     * have no canonical path, and a source can never resolve to them (an
     * import of a missing spec-listed file is E2003 at the import span).
     * With an absent surface the list is empty and no source carries
     * {@code BuiltinModule}.
     *
     * <p>This is the canonical-file keying of the stdlib predicate: the
     * resolved target of a symlinked spec-listed file is the canonical
     * path both here and in the classifier's canonical source URIs, so a
     * symlinked spec-listed file under a project-local surface keeps its
     * pinned {@code BuiltinModule} classification.</p>
     *
     * @param stdlibSurface the pinned surface directory path text, or
     *                      {@code null} when absent
     * @return the canonical declaration-file path texts in pinned module
     *         order (never null; empty when the surface is absent)
     */
    private static List<String> resolveStdlibDeclarationFiles(String stdlibSurface) {
        List<String> files = new ArrayList<>();
        if (stdlibSurface == null) {
            return files;
        }
        for (String module : SPEC_STDLIB_MODULES) {
            Path pinned = Path.of(stdlibSurface).resolve(module + ".d.deal");
            if (!Files.isRegularFile(pinned)) {
                continue;
            }
            try {
                files.add(pinned.toRealPath().toString());
            } catch (IOException ignored) {
                // Not a fully resolvable regular file: no canonical path
                // exists, so no resolved source can equal it.
            }
        }
        return files;
    }

    /**
     * The stdlib-overlap predicate: returns the stdlib module name when
     * {@code declarationPathText} equals one of the published canonical
     * (fully symlink-resolved) stdlib declaration file paths, otherwise
     * {@code null}. The files are computed once per locate by
     * {@link #resolveStdlibDeclarationFiles(String)} in pinned module
     * order, so the list index identifies the module; when the surface
     * is absent the list is empty and the check cannot fire.
     */
    private static String stdlibModuleOf(List<String> stdlibDeclarationFiles,
                                         String declarationPathText) {
        for (int i = 0; i < stdlibDeclarationFiles.size(); i++) {
            if (stdlibDeclarationFiles.get(i).equals(declarationPathText)) {
                return SPEC_STDLIB_MODULES.get(i);
            }
        }
        return null;
    }

    /**
     * The best-effort extern-C classification scan (the extern-C
     * nativeLibrary policy, design source
     * {@code production-project-graph-fixtures} D6/D7): reads and
     * strictly decodes the declaration file and runs the production
     * Lexer; any read, decode, or lex failure yields no extern-C signal
     * and no locate failure from this check — locate stays config-only
     * and the compile phase owns the file's own diagnostics. The scan
     * uses the Lexer's structured directive events (the single
     * directive-recognition authority), never a textual scan, and is
     * stateless: one scan per externals entry, no caching, no retries.
     *
     * @param declarationPathText the fully symlink-resolved declaration
     *                            path (existence/readability already
     *                            verified by the step 4(b) loop)
     * @return true iff the declaration lexes with at least one
     *         {@code EXTERN_C} directive event
     */
    private static boolean declaresExternC(String declarationPathText) {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(Path.of(declarationPathText));
        } catch (IOException readFailure) {
            return false;
        }
        StrictDecode decode = strictUtf8Decode(bytes);
        if (decode.malformedOffset() >= 0) {
            return false;
        }
        LexResult lexed = new Lexer(decode.text(), declarationPathText).tokenize();
        for (CompilerDirective event : lexed.directiveEvents()) {
            if (event.name() == DirectiveName.EXTERN_C) {
                return true;
            }
        }
        return false;
    }

    // =========================================================================
    // Step 5: output-failure mapping (the D3 residual boundary)
    // =========================================================================

    /**
     * Maps a classified output-conversion failure per D1 step 5: a
     * MANIFEST-source failure is E2010 (anchored at the manifest output
     * value range, or the canonical synthetic shape for the derived
     * backend-dependent default, which has no source range); a CLI-source
     * failure is a {@link CliDiagnostic}. Both publish no context.
     *
     * <p>Package-private as the D1 step-5 mapping seam: the residual
     * conversion failure (NUL/host-representability re-check, or an I/O
     * failure while resolving the confirmed existing prefix) is
     * filesystem-exotic for schema-validated values, so the mapping is
     * directly verified against crafted failures.</p>
     */
    static LocateResult mapOutputFailure(OutputConfigResolver.OutputPathFailure failure,
                                         String entryFile, String manifestPathText) {
        if (failure.source() == OutputConfigResolver.Source.MANIFEST) {
            DiagnosticRange range = failure.sourceRange() == null
                ? DiagnosticRange.synthetic(manifestPathText)
                : sourceRange(manifestPathText, failure.sourceRange());
            List<DiagnosticNote> notes = failure.sourceRange() == null
                ? List.of(anchorNote(manifestPathText))
                : null;
            return e2010("deal.json: 'output' cannot be converted: " + failure.reason(),
                range, notes);
        }
        return cliFailure("deal: output override cannot be converted: "
            + failure.reason(), entryFile);
    }

    // =========================================================================
    // Diagnostic helpers
    // =========================================================================

    /** The pinned synthetic anchor note naming the missing source anchor. */
    private static DiagnosticNote anchorNote(String file) {
        return new DiagnosticNote("missing anchor: " + file + ":1:1", null);
    }

    /** A SOURCE diagnostic range in the manifest file from a scalar range. */
    private static DiagnosticRange sourceRange(String manifestPathText,
                                               SourceScalarRange range) {
        return new DiagnosticRange(manifestPathText,
            range.startLine(), range.startColumn(), range.endLine(), range.endColumn(),
            range.startScalarOffset(), range.endScalarOffset(), range.scalarLength(),
            RangeOrigin.SOURCE);
    }

    private static LocateResult e2010(String message, DiagnosticRange range,
                                      List<DiagnosticNote> notes) {
        return new LocateResult(null, new CompilerDiagnostic(
            DiagnosticCode.E2010.code(), "error", message, range, notes,
            DiagnosticCode.E2010), null);
    }

    private static LocateResult cliFailure(String message, String entryFile) {
        return new LocateResult(null, null, new CliDiagnostic(message,
            DiagnosticRange.synthetic(entryFile)));
    }

    /** The user-facing rendering of a CLI-supplied entry-file value. */
    private static String describe(String entryFile) {
        return entryFile == null ? "<null>" : "'" + entryFile + "'";
    }

    // =========================================================================
    // Shared text helpers
    // =========================================================================

    /** Joins a relative text onto an absolute base with exactly one separator. */
    private static String joinPathText(String baseText, String relativeText) {
        if (baseText.isEmpty() || baseText.endsWith("/")) {
            return baseText + relativeText;
        }
        return baseText + "/" + relativeText;
    }

    /**
     * SHA-256 over the exact input bytes, rendered as 64 lowercase hex
     * characters. SHA-256 is a guaranteed JDK algorithm; its absence is
     * an environment defect, not a content failure.
     */
    private static String sha256Hex(byte[] bytes) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
        byte[] hash = digest.digest(bytes);
        StringBuilder hex = new StringBuilder(64);
        for (byte b : hash) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16));
            hex.append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }
}
