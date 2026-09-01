package deal.module;

import deal.ast.Span;
import deal.diagnostics.CompilerDiagnostic;
import deal.diagnostics.DiagnosticCode;
import deal.identity.CanonicalModuleIdentity;
import deal.project.ConfiguredModuleRoot;
import deal.project.ExternalEntry;
import deal.project.ProjectContext;
import deal.project.ProjectDeploymentIdentity;
import deal.project.ProtectedPathOps;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Importer-relative-first source resolution under one immutable
 * {@link ProjectContext} with the pinned five rules (design source
 * {@code strict-project-context-resolution-identity} D5,
 * {@code deal-v1.2-int32-and-bytes-architecture} D5/D6).
 *
 * <p><b>Resolution rules, pinned:</b></p>
 * <ol>
 *   <li>A bare specifier listed in {@code ProjectContext.externals}
 *       resolves to its validated {@link deal.project.NormalizedDeclarationPath}
 *       (the manifest declaration is authoritative; on-disk candidates
 *       are not consulted). Missing/unreadable declaration files were
 *       already E2010 at locate, so no missing-declaration import case
 *       remains.</li>
 *   <li>{@code ./} and {@code ../} specifiers resolve from the importer's
 *       directory and may leave every configured root and the manifest
 *       directory; no containment check exists for relative resolution.
 *       Classification is by canonical file (D6).</li>
 *   <li>Bare specifiers search the configured roots in manifest order,
 *       then the pinned stdlib surface ({@code
 *       ProjectContext.stdlibSurfacePath}) with the existing 6-module
 *       filter. A bare {@code std/...} import outside the filter is
 *       E2003 at the import span; a missing surface or a surface missing
 *       a spec-listed file makes the corresponding stdlib import E2003
 *       (never a {@code RuntimeException}); a non-spec-listed
 *       {@code .d.deal} physically inside the stdlib directory is not
 *       resolvable through bare lookup and is reachable only by relative
 *       import. The stdlib-surface lookup consults the single pinned
 *       candidate {@code <surface>/<module>.d.deal} only — the generic
 *       four-candidate walk never applies under the surface. There is
 *       no CWD module fallback and no importer-relative fallback for
 *       bare lookup; the stdlib surface is the only {@code std/} source
 *       authority.</li>
 *   <li>Candidates per root are {@code S.deal}, {@code S/index.deal},
 *       {@code S.d.deal}, {@code S/index.d.deal} (spec order). A bare
 *       import whose resolution lands on a non-stdlib {@code .d.deal}
 *       whose canonical path equals an externals entry's declaration
 *       path carries {@code ExternalModule(that key)} (file-keyed) even
 *       though rule 1 did not match; a bare import whose resolution
 *       lands on a non-stdlib {@code .d.deal} whose canonical path
 *       matches no externals entry is E2009 at the import span;
 *       resolution failure is E2003 at the import span.</li>
 *   <li>Nested {@code deal.json} files under the entry project are never
 *       rediscovered and are not errors: the entry's one
 *       {@code ProjectContext} governs the graph.</li>
 * </ol>
 *
 * <p><b>Import-candidate handling</b> follows the T1 per-path-class
 * matrix: every existing candidate is fully symlink-resolved and
 * verified regular + readable ({@link
 * ProtectedPathOps#canonicalizeExisting(String)}); an unreadable module
 * is E2003, a not-found candidate advances to the next candidate, and a
 * total miss is E2003 — always at the import span, never a raw
 * exception. Every resolution diagnostic carries a complete
 * {@link deal.diagnostics.DiagnosticRange} (a SOURCE range from a span
 * with computed scalar offsets; the pinned synthetic shape plus anchor
 * note for an anchorless span).</p>
 *
 * <p><b>Identity assignment.</b> Every successfully resolved source
 * receives {@link SemanticModuleIdentity} built from the context's
 * private {@link ProjectDeploymentIdentity} and the protected-resolved
 * canonical {@code file:} URI. The resolver memoizes by canonical source
 * URI: equivalent import spellings ({@code ./x}, {@code ../a/x}, a root
 * spelling, symlinked spellings) of one resolved source yield one
 * semantic identity, and failed resolution registers nothing. When
 * publishing each {@link SourceModuleLocation} the resolver invokes
 * {@link ModuleIdentityResolver#classify(ProjectContext, String)} exactly
 * once per resolved source: the result is the provenance of
 * {@code projectIdentity} and the module-level classification recorded
 * on the location; {@code CanonicalClassIdentity} assembly stays
 * eligibility-gated at consumer time ({@link ModuleIdentityAssembly}). A bare import
 * additionally evaluates the classification before memoization to apply
 * the file-keyed E2009 gate, so a bare import of a previously
 * relatively-resolved undeclared declaration file still fails E2009.</p>
 *
 * <p><b>{@code deploymentModuleId}</b> is deterministic:
 * {@code "m" +} the first 16 lowercase hex chars of
 * SHA-256(length-prefixed deployment digest ‖ length-prefixed canonical
 * source URI) using {@link ProtectedPathOps#lengthPrefixedUtf8(String)}.
 * It is opaque, byte-stable for unchanged inputs, and never descriptor
 * text or an export key.</p>
 *
 * <p><b>Privacy invariant.</b> Private identity URIs, digests, and
 * {@code deploymentModuleId}s never appear in this module's diagnostic
 * messages or any descriptor-like string; they are exposed only through
 * the compiler-internal records.</p>
 *
 * <p><b>Determinism.</b> Identical {@code (ProjectContext, importer,
 * specifier, on-disk files)} inputs produce identical locations and
 * identities; classification is a pure function of the resolved
 * canonical file, never of the import spelling or the resolution
 * order.</p>
 */
public final class SourceModuleResolver {

    /** The pinned {@code deploymentModuleId} prefix. */
    public static final String DEPLOYMENT_MODULE_ID_PREFIX = "m";

    /** The pinned number of digest hex chars of a {@code deploymentModuleId}. */
    public static final int DEPLOYMENT_MODULE_ID_HEX_CHARS = 16;

    private final ProjectContext context;

    /**
     * The memoization table: canonical resolved source URI → published
     * location, in first-resolution order. Equivalent spellings of one
     * resolved source reuse the one published location.
     */
    private final Map<String, SourceModuleLocation> byCanonicalUri =
        new LinkedHashMap<>();

    /**
     * Creates a resolver over one validated project context.
     *
     * @param context the immutable validated project context (never null)
     */
    public SourceModuleResolver(ProjectContext context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    /**
     * The project context this resolver governs.
     */
    public ProjectContext context() {
        return context;
    }

    /**
     * The locations published so far, keyed by canonical resolved source
     * URI, in first-resolution order. Unmodifiable; failed resolutions
     * register nothing.
     */
    public Map<String, SourceModuleLocation> resolvedLocations() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(byCanonicalUri));
    }

    /**
     * The structured resolution result: exactly one resolved location or
     * exactly one import diagnostic (E2003/E2009), never a raw exception.
     */
    public sealed interface ResolveResult
            permits ResolveResult.Resolved, ResolveResult.Failure {

        /** A successfully resolved and published source location. */
        record Resolved(SourceModuleLocation location) implements ResolveResult {
            public Resolved {
                Objects.requireNonNull(location, "location");
            }
        }

        /**
         * A failed resolution: one E2003 or E2009 anchored at the import
         * span with a complete diagnostic range.
         */
        record Failure(CompilerDiagnostic diagnostic) implements ResolveResult {
            public Failure {
                Objects.requireNonNull(diagnostic, "diagnostic");
            }
        }
    }

    /**
     * Resolves one import with the pinned five rules and publishes the
     * resolved source's location (memoized by canonical URI). The
     * importer path is the importing source file's absolute path text;
     * relative specifiers resolve from its directory.
     *
     * <p>Anchorless callers (no import declaration span available) use
     * {@link #resolve(String, String)}; with a span, diagnostics anchor
     * exactly at the import declaration span.</p>
     *
     * @param importerPath the importing source file's absolute path text
     *                     (never null)
     * @param specifier    the import specifier text exactly as written
     *                     (never null)
     * @return the resolved location or the single import diagnostic
     */
    public ResolveResult resolve(String importerPath, String specifier) {
        Objects.requireNonNull(importerPath, "importerPath");
        Objects.requireNonNull(specifier, "specifier");
        return resolve(importerPath, specifier, Span.synthetic(importerPath));
    }

    /**
     * Resolves one import with the pinned five rules and publishes the
     * resolved source's location (memoized by canonical URI). The
     * importer path is the importing source file's absolute path text;
     * relative specifiers resolve from its directory.
     *
     * @param importerPath the importing source file's absolute path text
     *                     (never null)
     * @param specifier    the import specifier text exactly as written
     *                     (never null)
     * @param importSpan   the import declaration span the diagnostic
     *                     anchors at (never null); a span without
     *                     computed scalar offsets yields the pinned
     *                     synthetic range with an anchor note
     * @return the resolved location or the single import diagnostic
     */
    public ResolveResult resolve(String importerPath, String specifier, Span importSpan) {
        Objects.requireNonNull(importerPath, "importerPath");
        Objects.requireNonNull(specifier, "specifier");
        Objects.requireNonNull(importSpan, "importSpan");
        if (specifier.startsWith("./") || specifier.startsWith("../")) {
            return resolveRelative(importerPath, specifier, importSpan);
        }
        return resolveBare(specifier, importSpan);
    }

    // =========================================================================
    // The entry-file seam (orchestrator consumption, ISSUE-0269)
    // =========================================================================

    /**
     * Publishes the compilation's entry file as a resolved source: the
     * entry is not reached through an import declaration, so the
     * orchestrator obtains its {@link SourceModuleLocation} (and thereby
     * its private semantic identity, {@code deploymentModuleId}, and
     * module classification) through this seam. The conversion follows
     * the T1 entry-row matrix: the file must exist as a regular,
     * readable file with full symlink resolution
     * ({@link ProtectedPathOps#canonicalizeExisting(String)}); a missing
     * or unreadable entry is E2003 at the given anchor (the canonical
     * synthetic shape plus anchor note for an anchorless span). The
     * published location's {@code normalizedSourcePath} is the entry's
     * lexical absolute normalized path — the same key the orchestrator
     * uses for the entry module.
     *
     * @param entryPathText the entry file's absolute path text (never
     *                      null)
     * @param entrySpan     the anchor for a failure diagnostic (never
     *                      null)
     * @return the published location or the single E2003; never null
     */
    public ResolveResult resolveEntryFile(String entryPathText, Span entrySpan) {
        Objects.requireNonNull(entryPathText, "entryPathText");
        Objects.requireNonNull(entrySpan, "entrySpan");
        Path lexical;
        try {
            lexical = Path.of(entryPathText).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            return e2003("Module not found: '" + entryPathText
                + "'. The entry path cannot be materialized: "
                + e.getMessage(), entrySpan);
        }
        ProtectedPathOps.PathResult converted =
            ProtectedPathOps.canonicalizeExisting(entryPathText);
        if (converted instanceof ProtectedPathOps.PathResult.Failure failure) {
            if (failure.kind() == ProtectedPathOps.PathResult.FailureKind.NOT_FOUND) {
                return e2003("Module not found: " + entryPathText, entrySpan);
            }
            return e2003("Cannot read module: " + entryPathText + " ("
                + failure.reason() + ")", entrySpan);
        }
        ProtectedPathOps.PathResult.Success success =
            (ProtectedPathOps.PathResult.Success) converted;
        return publish(lexical, success.resolvedPath(), entrySpan, null);
    }

    // =========================================================================
    // Rule 2: importer-relative resolution
    // =========================================================================

    private ResolveResult resolveRelative(String importerPath, String specifier,
                                          Span importSpan) {
        Path importerDirectory;
        try {
            importerDirectory =
                Path.of(importerPath).toAbsolutePath().normalize().getParent();
        } catch (InvalidPathException e) {
            return e2003("Module not found: '" + specifier + "'. The importer path"
                + " cannot be materialized: " + e.getMessage(), importSpan);
        }
        if (importerDirectory == null) {
            return e2003("Module not found: '" + specifier + "'. The importer path"
                + " has no parent directory: '" + importerPath + "'", importSpan);
        }
        Path base;
        try {
            base = importerDirectory.resolve(specifier).normalize();
        } catch (InvalidPathException e) {
            return e2003("Module not found: '" + specifier + "'. The import"
                + " specifier cannot be materialized: " + e.getMessage(), importSpan);
        }
        List<String> attempted = new ArrayList<>();
        ResolveResult hit = searchCandidates(specifier, base, attempted, importSpan, false);
        if (hit != null) {
            return hit;
        }
        return e2003(notFoundMessage(specifier, attempted), importSpan);
    }

    // =========================================================================
    // Rules 1, 3, 4: bare resolution
    // =========================================================================

    private ResolveResult resolveBare(String specifier, Span importSpan) {
        // Rule 1: the externals declaration is authoritative for its key.
        ExternalEntry entry = context.externals().get(specifier);
        if (entry != null) {
            return publishExternals(entry, importSpan);
        }

        // The 6-module filter: a bare std/... import outside the filter is
        // E2003 before any search (the stdlib surface is the only std/
        // source authority).
        if (specifier.startsWith("std/")
                && !StdlibModuleResolver.SPEC_STDLIB_MODULES.contains(specifier)) {
            return e2003("Module not found: '" + specifier + "'. Not a spec-listed"
                + " stdlib module (spec-listed: "
                + String.join(", ", StdlibModuleResolver.SPEC_STDLIB_MODULES) + ")",
                importSpan);
        }

        // Rules 3+4: configured roots in manifest order, then the pinned
        // stdlib surface. No CWD module fallback and no importer-relative
        // fallback for bare lookup.
        List<String> attempted = new ArrayList<>();
        for (ConfiguredModuleRoot root : context.configuredModuleRoots()) {
            Path base = safeResolve(root.absoluteNormalizedPath(), specifier);
            if (base == null) {
                continue;
            }
            ResolveResult hit =
                searchCandidates(specifier, base, attempted, importSpan, true);
            if (hit != null) {
                return hit;
            }
        }
        if (specifier.startsWith("std/")) {
            return resolveStdlibSurface(specifier, importSpan);
        }
        return e2003(notFoundMessage(specifier, attempted), importSpan);
    }

    /**
     * Rule 3 stdlib-surface lookup: exactly one pinned candidate,
     * {@code <surface>/<module>.d.deal}. The generic four-candidate walk
     * is deliberately not used under the surface — the six spec-listed
     * files under the resolved surface are the only stdlib sources, so
     * {@code S.deal}, {@code S/index.deal}, and {@code S/index.d.deal}
     * are never consulted and can never satisfy a bare std import. The
     * pinned candidate is fully symlink-resolved and verified regular +
     * readable through T1's protected conversion ({@link
     * ProtectedPathOps#canonicalizeExisting(String)}); a missing or
     * unreadable pinned file is E2003 at the import span (a surface
     * missing a spec-listed file — never a {@code RuntimeException}).
     * The stdlib branch always concludes the bare search.
     */
    private ResolveResult resolveStdlibSurface(String specifier, Span importSpan) {
        String surface = context.stdlibSurfacePath();
        if (surface == null) {
            return e2003("Module not found: '" + specifier + "'. The stdlib"
                + " surface is absent (no project-local std/ directory and no"
                + " language-distribution std/ directory)", importSpan);
        }
        String moduleName = specifier.substring("std/".length());
        Path base = safeResolve(surface, moduleName);
        if (base == null) {
            return e2003("Module not found: '" + specifier + "'. The pinned"
                + " stdlib candidate path cannot be materialized from surface '"
                + surface + "'", importSpan);
        }
        Path candidate = pinnedStdlibCandidate(base, moduleName);
        if (!probeExists(candidate)) {
            return e2003("Module not found: '" + specifier + "'. The stdlib"
                + " surface is missing the spec-listed declaration file '"
                + candidate + "'", importSpan);
        }
        ProtectedPathOps.PathResult converted =
            ProtectedPathOps.canonicalizeExisting(candidate.toString());
        if (converted instanceof ProtectedPathOps.PathResult.Success success) {
            return publishOrGate(candidate, success.resolvedPath(), specifier,
                importSpan, true);
        }
        ProtectedPathOps.PathResult.Failure failure =
            (ProtectedPathOps.PathResult.Failure) converted;
        return e2003("Module not found: '" + specifier + "'. The spec-listed"
            + " stdlib declaration file '" + candidate + "' is not a readable"
            + " module: " + failure.reason(), importSpan);
    }

    /**
     * The single pinned stdlib candidate of one base path:
     * {@code <surface>/<module>.d.deal} (the third spec candidate, taken
     * alone — the only stdlib source shape).
     */
    private static Path pinnedStdlibCandidate(Path base, String moduleName) {
        Path fileName = base.getFileName();
        String name = fileName == null ? "" : fileName.toString();
        String candidateName = name.isEmpty() ? moduleName + ".d.deal"
            : name + ".d.deal";
        return base.resolveSibling(candidateName);
    }

    /**
     * Rule 1: publishes the externals entry's validated declaration path.
     * ProjectLocator step 4 already required the file to exist as a
     * regular readable file with full symlink resolution, so the resolved
     * declaration path is the source directly — on-disk candidates are
     * not consulted.
     */
    private ResolveResult publishExternals(ExternalEntry entry, Span importSpan) {
        Path resolved;
        try {
            resolved = Path.of(entry.declarationPath().absoluteNormalizedPath())
                .toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            return e2003("Module not found: '" + entry.rawImportSpecifier()
                + "'. The externals declaration path cannot be materialized: "
                + e.getMessage(), importSpan);
        }
        return publish(resolved, resolved, importSpan, null);
    }

    /**
     * Walks the four spec candidates of one base path in the pinned order
     * {@code S.deal}, {@code S/index.deal}, {@code S.d.deal},
     * {@code S/index.d.deal}, appending each attempted candidate. A
     * candidate that exists is fully symlink-resolved and verified
     * regular + readable (the T1 import-candidate matrix); a candidate
     * that is missing advances to the next, and any other candidate
     * failure (not regular, not readable, I/O error, invalid input) is
     * E2003 at the import span immediately. Returns {@code null} when no
     * candidate resolved (the caller continues the search or emits the
     * final E2003).
     */
    private ResolveResult searchCandidates(String specifier, Path base,
                                           List<String> attempted, Span importSpan,
                                           boolean bare) {
        for (Path candidate : candidatesOf(base)) {
            attempted.add(candidate.toString());
            // A candidate under a file-component prefix can never exist
            // (e.g. S/index.deal when S.deal is a regular file): the
            // existence probe returns false for it and the search advances,
            // matching "not found" in the T1 import-candidate matrix.
            if (!probeExists(candidate)) {
                continue;
            }
            ProtectedPathOps.PathResult converted =
                ProtectedPathOps.canonicalizeExisting(candidate.toString());
            if (converted instanceof ProtectedPathOps.PathResult.Success success) {
                return publishOrGate(candidate, success.resolvedPath(), specifier,
                    importSpan, bare);
            }
            ProtectedPathOps.PathResult.Failure failure =
                (ProtectedPathOps.PathResult.Failure) converted;
            if (failure.kind() == ProtectedPathOps.PathResult.FailureKind.NOT_FOUND) {
                continue;
            }
            return e2003("Module not found: '" + specifier + "'. Candidate '"
                + candidate + "' is not a readable module: " + failure.reason(),
                importSpan);
        }
        return null;
    }

    /**
     * The existence pre-probe for one import candidate: false when the
     * candidate cannot exist (a missing candidate or a candidate whose
     * existing ancestor prefix is a regular file — in which case
     * resolving the candidate would fail with a not-a-directory error,
     * and the candidate is "not found" per the T1 matrix). A probe whose
     * outcome cannot be determined reports true and lets the protected
     * conversion classify the candidate. Never throws.
     */
    private static boolean probeExists(Path candidate) {
        try {
            return Files.exists(candidate);
        } catch (SecurityException e) {
            return true;
        }
    }

    /**
     * The pinned spec candidate order of one base path:
     * {@code S.deal}, {@code S/index.deal}, {@code S.d.deal},
     * {@code S/index.d.deal}.
     */
    private static List<Path> candidatesOf(Path base) {
        // A base with no file-name component (e.g. a root-relative base
        // from an empty specifier) uses the empty name: the candidates
        // derive from the directory itself and the search still yields
        // the pinned four candidate paths — never a raw exception.
        Path fileName = base.getFileName();
        String name = fileName == null ? "" : fileName.toString();
        return List.of(
            base.resolveSibling(name + ".deal"),
            base.resolve("index.deal"),
            base.resolveSibling(name + ".d.deal"),
            base.resolve("index.d.deal"));
    }

    /**
     * A successful candidate landing: the file-keyed E2009 gate fires for
     * a bare import whose resolved file is a non-stdlib {@code .d.deal}
     * whose canonical path matches no externals entry — i.e. the file
     * carries neither {@code BuiltinModule} nor {@code ExternalModule}
     * classification. Every other landing publishes normally. The
     * classification computed here is the single classifier invocation
     * for the published location (or the per-import gate evaluation for a
     * memoized bare landing); it is passed through to
     * {@link #publish(Path, Path, Span, ModuleIdentityResolver.ModuleClassification)} so a
     * non-memoized landing never classifies twice.
     */
    private ResolveResult publishOrGate(Path lexicalCandidate, Path resolvedReal,
                                        String specifier, Span importSpan,
                                        boolean bare) {
        String canonicalUri = uriOf(resolvedReal);
        if (canonicalUri == null) {
            return e2003("Module not found: '" + specifier + "'. Cannot derive"
                + " the canonical file: URI of '" + resolvedReal + "'", importSpan);
        }
        ModuleIdentityResolver.ModuleClassification classification =
            ModuleIdentityResolver.classify(context, canonicalUri);
        if (bare && isUndeclaredNonStdlibDeclaration(classification.moduleIdentity(),
                resolvedReal)) {
            return e2009("Import of external host module '" + specifier
                + "' is not declared in deal.json externals (resolved declaration"
                + " file: '" + resolvedReal + "')", importSpan);
        }
        return publish(lexicalCandidate, resolvedReal, importSpan, classification);
    }

    /**
     * The file-keyed E2009 predicate: a non-stdlib declaration file (a
     * {@code .d.deal} carrying neither {@code BuiltinModule} nor
     * {@code ExternalModule} classification) reached by a bare import.
     */
    private static boolean isUndeclaredNonStdlibDeclaration(
            CanonicalModuleIdentity classification, Path resolvedReal) {
        if (classification instanceof CanonicalModuleIdentity.BuiltinModule
                || classification instanceof CanonicalModuleIdentity.ExternalModule) {
            return false;
        }
        return resolvedReal.getFileName().toString().endsWith(".d.deal");
    }

    // =========================================================================
    // Publication (identity assignment and memoization)
    // =========================================================================

    /**
     * Publishes one successfully resolved source: derives the canonical
     * protected-resolved URI, reuses the memoized location when the
     * canonical URI was already published (equivalent spellings yield one
     * semantic identity), otherwise assigns the private
     * {@link SemanticModuleIdentity}, the deterministic
     * {@code deploymentModuleId}, and the classifier result — the
     * precomputed {@code classification} when the caller already evaluated
     * it (a bare landing's E2009 gate), otherwise exactly one classifier
     * invocation — whose result is the location's
     * {@code projectIdentity} provenance and module-level classification.
     * Failed resolution registers nothing.
     */
    private ResolveResult publish(Path lexicalCandidate, Path resolvedReal,
                                  Span importSpan,
                                  ModuleIdentityResolver.ModuleClassification classification) {
        String lexicalPath = lexicalCandidate.toAbsolutePath().normalize().toString();
        String canonicalUri = uriOf(resolvedReal);
        if (canonicalUri == null) {
            return e2003("Module not found: cannot derive the canonical file: URI"
                + " of '" + resolvedReal + "'", importSpan);
        }
        SourceModuleLocation existing = byCanonicalUri.get(canonicalUri);
        if (existing != null) {
            return new ResolveResult.Resolved(existing);
        }
        if (classification == null) {
            classification = ModuleIdentityResolver.classify(context, canonicalUri);
        }
        SemanticModuleIdentity semanticIdentity =
            new SemanticModuleIdentity(context.projectDeploymentIdentity(), canonicalUri);
        String deploymentModuleId = deploymentModuleIdOf(canonicalUri);
        SourceModuleLocation location = new SourceModuleLocation(
            lexicalPath, semanticIdentity, deploymentModuleId,
            classification.projectIdentity(), classification.moduleIdentity());
        byCanonicalUri.put(canonicalUri, location);
        return new ResolveResult.Resolved(location);
    }

    /**
     * The canonical {@code file:} URI of a protected-resolved absolute
     * path (T1's URI derivation); {@code null} when derivation fails
     * (unreachable for a protected-resolved file — handled, never a raw
     * exception).
     */
    private static String uriOf(Path resolvedReal) {
        ProtectedPathOps.UriResult result = ProtectedPathOps.toFileUri(resolvedReal);
        return result instanceof ProtectedPathOps.UriResult.Success success
            ? success.uri().toString()
            : null;
    }

    /**
     * The pinned {@code deploymentModuleId} derivation:
     * {@code "m"} + the first 16 lowercase hex chars of
     * SHA-256(length-prefixed deployment digest ‖ length-prefixed
     * canonical source URI), each part framed with T1's shared
     * length-prefixed UTF-8 serialization. Opaque and byte-stable for
     * unchanged inputs; never descriptor text or an export key.
     */
    private String deploymentModuleIdOf(String canonicalUri) {
        byte[] digestPart = framed(
            context.projectDeploymentIdentity().validatedManifestContentDigest());
        byte[] uriPart = framed(canonicalUri);
        String hash = IdentityDigests.sha256Hex(
            IdentityDigests.concat(digestPart, uriPart));
        return DEPLOYMENT_MODULE_ID_PREFIX
            + hash.substring(0, DEPLOYMENT_MODULE_ID_HEX_CHARS);
    }

    /**
     * Frames one identity input with T1's length-prefixed UTF-8
     * serialization. Both identity inputs (a 64-hex digest and a
     * canonical {@code file:} URI) are ASCII scalar sequences, so a
     * serialization failure is an internal invariant violation — it is
     * reported as such, never as a silently wrong digest.
     */
    private static byte[] framed(String value) {
        ProtectedPathOps.ByteResult framed = ProtectedPathOps.lengthPrefixedUtf8(value);
        if (framed instanceof ProtectedPathOps.ByteResult.Success success) {
            return success.bytes();
        }
        throw new IllegalStateException("identity input cannot be serialized: "
            + ((ProtectedPathOps.ByteResult.Failure) framed).reason());
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Resolves {@code specifier} against a base path text with no
     * existence requirement; {@code null} when the base cannot be
     * materialized (then the search simply cannot produce candidates
     * from that base).
     */
    private static Path safeResolve(String baseText, String specifier) {
        try {
            return Path.of(baseText).resolve(specifier).normalize();
        } catch (InvalidPathException e) {
            return null;
        }
    }

    /** One E2003 anchored at the import span. */
    private static ResolveResult e2003(String message, Span importSpan) {
        return new ResolveResult.Failure(
            CompilerDiagnostic.error(DiagnosticCode.E2003, message, importSpan));
    }

    /** One E2009 anchored at the import span. */
    private static ResolveResult e2009(String message, Span importSpan) {
        return new ResolveResult.Failure(
            CompilerDiagnostic.error(DiagnosticCode.E2009, message, importSpan));
    }

    /** The pinned deterministic E2003 miss message with the attempted list. */
    private static String notFoundMessage(String specifier, List<String> attempted) {
        return "Module not found: '" + specifier + "'. Attempted: "
            + String.join(", ", attempted);
    }
}
