package deal.module;

import deal.diagnostics.CompilerDiagnostic;
import deal.diagnostics.DiagnosticNote;
import deal.diagnostics.DiagnosticRange;
import deal.diagnostics.RangeOrigin;
import deal.ast.Span;
import deal.identity.CanonicalModuleIdentity;
import deal.identity.ProjectModuleIdentity;
import deal.project.ConfiguredModuleRoot;
import deal.project.ExternalEntry;
import deal.project.NormalizedDeclarationPath;
import deal.project.OutputConfigResolver;
import deal.project.ProjectContext;
import deal.project.ProjectDeploymentIdentity;
import deal.project.ProjectLocator;
import deal.project.ProtectedPathOps;
import deal.source.SourceScalarRange;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The test battery for {@link SourceModuleResolver} (ISSUE-0267 T6,
 * design source {@code strict-project-context-resolution-identity}
 * D5/D6, {@code deal-v1.2-int32-and-bytes-architecture} D5/D6,
 * verification items 5-7 and the combined T1+T4+T5 dependency gate):
 * the pinned five resolution rules over real temp-project fixtures
 * located through production {@link ProjectLocator} (roots, externals
 * declarations, project-local stdlib surfaces); importer-relative
 * resolution outside all roots and the manifest directory; bare lookup
 * over configured roots in manifest order plus the pinned stdlib
 * surface only (a module reachable only via the old CWD fallback is
 * E2003, proven in a subprocess with a controlled working directory);
 * the 6-module filter; a missing surface or a surface missing a
 * spec-listed file is E2003 (never a RuntimeException); file-keyed
 * stdlib and externals classification across spellings; E2003/E2009 at
 * import spans with complete DiagnosticRanges; nested deal.json files
 * ignored; memoized SourceModuleLocation/SemanticModuleIdentity per
 * canonical URI (equivalent and symlinked spellings yield one
 * identity); manifest-byte-change and source-relocation identity
 * changes; the pinned deploymentModuleId formula; the
 * providerContractDigest domain and determinism; the privacy invariant;
 * the pure classifier matrix (containment, most-specific selection,
 * defensive EQUAL_ROOT_TIE and STDLIB_EXTERNAL_OVERLAP reports, purity
 * over fabricated URIs); and the resource-identity carrier shapes.
 *
 * <p>Runs via main() using the repository's plain check()-helper
 * convention; exits non-zero on failure. Sub-mode
 * {@code --sub-cwd-fallback <dir>} runs a single bare resolution in a
 * fresh JVM whose working directory is {@code <dir>} and prints
 * {@code E2003} or {@code RESOLVED:<path>} (the no-CWD-fallback
 * proof).</p>
 */
public final class SourceModuleResolverTest {

    private SourceModuleResolverTest() {
    }

    private static int passed = 0;
    private static int failed = 0;
    private static Path tmpDir;
    /** {@code tmpDir.toRealPath()} — the symlink-resolved expectation base. */
    private static Path realTmp;
    /** True when no ancestor of realTmp outside the fixtures has a deal.json. */
    private static boolean environmentClean = true;

    // =========================================================================
    // Test runner
    // =========================================================================

    public static void main(String[] args) throws Exception {
        if (args.length > 0) {
            System.exit(runSubMode(args));
        }
        tmpDir = Files.createTempDirectory("deal-resolver-");
        try {
            realTmp = tmpDir.toRealPath();
            environmentClean = !ancestorsHaveDealJson(realTmp);
            if (!environmentClean) {
                System.out.println("NOTE: a deal.json exists above the fixture root;"
                    + " locate-based assertions are skipped.");
            }
            testCarrierShapes();
            testClassifierMatrix();
            testClassifierDefensiveReports();
            testClassifierPurity();
            if (environmentClean) {
                testResolutionMatrix();
                testStdlibSurfaceRules();
                testStdlibSurfaceSinglePinnedCandidate();
                testExternalsAuthorityAndFileKeyedClassification();
                testE2009AndRelativeExemption();
                testRootOrder();
                testNestedDealJsonIgnored();
                testUnreadableCandidate();
                testDiagnosticRanges();
                testIdentityMemoization();
                testSymlinkSpellings();
                testSymlinkedPinnedStdlibFile();
                testDeploymentMutation();
                testDeploymentModuleIdFormula();
                testPrivacyInvariant();
                testNoCwdFallbackSubprocess();
            }
        } finally {
            cleanup();
        }
        System.out.println();
        System.out.println("Passed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.err.println("SourceModuleResolverTest FAILED: " + failed
                + " failure(s)");
            System.exit(1);
        }
    }

    // =========================================================================
    // Sub-mode: no-CWD-fallback proof (controlled working directory)
    // =========================================================================

    private static int runSubMode(String[] args) throws Exception {
        if (args.length != 2 || !args[0].equals("--sub-cwd-fallback")) {
            System.err.println("usage: SourceModuleResolverTest --sub-cwd-fallback <dir>");
            return 2;
        }
        Path dir = Path.of(args[1]).toAbsolutePath().normalize();
        Files.createDirectories(dir.resolve("rootsrc"));
        writeText(dir.resolve("cwdonly.deal"), "export function f(): null {}\n");
        ProjectContext context = syntheticContext(dir, List.of("rootsrc"),
            Map.of(), null);
        SourceModuleResolver resolver = new SourceModuleResolver(context);
        String importer = dir.resolve("rootsrc/main.deal").toString();
        SourceModuleResolver.ResolveResult result = resolver.resolve(importer, "cwdonly");
        if (result instanceof SourceModuleResolver.ResolveResult.Failure failure) {
            System.out.println("E2003:" + failure.diagnostic().code());
            return 0;
        }
        SourceModuleLocation location =
            ((SourceModuleResolver.ResolveResult.Resolved) result).location();
        System.out.println("RESOLVED:" + location.normalizedSourcePath());
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
    }

    private static Path writeText(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private static Path fixture(String name) {
        return tmpDir.resolve(name);
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

    private static void makeWritable(Path file) throws IOException {
        Files.setPosixFilePermissions(file, Set.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
    }

    /**
     * Locates a fixture through production {@link ProjectLocator} and
     * returns the published context, or fails when locate does not
     * succeed.
     */
    private static ProjectContext locateOrFail(Path dir, String entryName)
            throws IOException {
        Path entry = writeText(dir.resolve(entryName),
            "export function main(): null {}\n");
        ProjectLocator.LocateResult result = ProjectLocator.locate(entry.toString(), null);
        if (result.context() == null) {
            String failure = result.e2010() != null
                ? result.e2010().message()
                : result.cliDiagnostic().message();
            fail("locate of fixture " + dir + " failed: " + failure);
            return null;
        }
        return result.context();
    }

    /**
     * Builds a validated-shape {@link ProjectContext} directly (for cases
     * that do not need discovery or the stdlib surface probe). Root paths
     * are protected prefix-resolved (T1), externals declaration paths are
     * fully resolved (T1), the deployment identity digest is a fixed
     * deterministic 64-hex value, and the stdlib surface is the given
     * path (or absent).
     */
    private static ProjectContext syntheticContext(Path dir, List<String> rootTexts,
                                                   Map<String, String> externalDecls,
                                                   String stdlibSurface) {
        List<ConfiguredModuleRoot> roots = new ArrayList<>();
        for (String rootText : rootTexts) {
            ProtectedPathOps.PathResult converted =
                ProtectedPathOps.normalizePrefixResolved(dir.resolve(rootText).toString());
            String resolved = converted instanceof ProtectedPathOps.PathResult.Success success
                ? success.resolvedPath().toString()
                : dir.resolve(rootText).normalize().toString();
            roots.add(new ConfiguredModuleRoot(rootText, resolved, dummyRange()));
        }
        Map<String, ExternalEntry> externals = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : externalDecls.entrySet()) {
            ProtectedPathOps.PathResult converted =
                ProtectedPathOps.canonicalizeExisting(
                    dir.resolve(entry.getValue()).toString());
            String resolved = converted instanceof ProtectedPathOps.PathResult.Success success
                ? success.resolvedPath().toString()
                : dir.resolve(entry.getValue()).normalize().toString();
            externals.put(entry.getKey(), new ExternalEntry(entry.getKey(),
                new NormalizedDeclarationPath(resolved, dummyRange()), null, dummyRange()));
        }
        OutputConfigResolver.OutputRef output = new OutputConfigResolver.OutputRef(
            OutputConfigResolver.Source.MANIFEST,
            OutputConfigResolver.Kind.MANIFEST_RELATIVE_PATH,
            "build/lua", dir.resolve("build/lua").normalize().toString(), null);
        // The pinned six-file set mirrors ProjectLocator step 6: the
        // canonical (fully symlink-resolved) path of each spec-listed
        // file that exists as a regular file under the surface.
        List<String> pinnedStdlibFiles = new ArrayList<>();
        if (stdlibSurface != null) {
            for (String module : ProjectLocator.SPEC_STDLIB_MODULES) {
                Path pinned = Path.of(stdlibSurface).resolve(module + ".d.deal");
                if (Files.isRegularFile(pinned)) {
                    try {
                        pinnedStdlibFiles.add(pinned.toRealPath().toString());
                    } catch (IOException ignored) {
                        // Not a fully resolvable regular file: cannot
                        // equal a resolved source.
                    }
                }
            }
        }
        ProjectDeploymentIdentity deployment = new ProjectDeploymentIdentity(
            dir.resolve("deal.json").normalize().toUri().toString(),
            "0".repeat(64));
        return new ProjectContext(
            dir.resolve("deal.json").normalize().toString(),
            dir.normalize().toString(),
            dir.normalize().toString(),
            "1.2",
            roots,
            output,
            "luajit",
            externals,
            "1.2",
            stdlibSurface,
            pinnedStdlibFiles,
            deployment);
    }

    private static SourceScalarRange dummyRange() {
        return new SourceScalarRange(1, 1, 1, 1, 0, 0);
    }

    /** The protected prefix-resolved path text (T1) with a lexical fallback. */
    private static String prefixResolvedText(String path) {
        ProtectedPathOps.PathResult converted =
            ProtectedPathOps.normalizePrefixResolved(path);
        return converted instanceof ProtectedPathOps.PathResult.Success success
            ? success.resolvedPath().toString()
            : Path.of(path).toAbsolutePath().normalize().toString();
    }

    /**
     * The main resolution-matrix fixture: roots src, lib, lib/utils,
     * decl; externals host → decl/host.d.deal; a project-local std/
     * surface with console.d.deal (pinned) and custom.d.deal (non-spec);
     * on-disk host.deal/host.d.deal shadowing under src; a nested
     * project directory with its own deal.json; out-of-manifest files at
     * the temp root; and a symlinked spelling inside src pointing outside
     * every root. Returns the located context.
     */
    private static ProjectContext buildMatrixFixture() throws IOException {
        Path dir = fixture("matrix");
        deleteTree(dir);
        writeText(dir.resolve("deal.json"),
            "{\n  \"languageVersion\": \"1.2\",\n"
                + "  \"moduleRoots\": [\"src\", \"lib\", \"lib/utils\", \"decl\"],\n"
                + "  \"externals\": {\n"
                + "    \"host\": {\"declaration\": \"decl/host.d.deal\"}\n"
                + "  }\n}\n");
        writeText(dir.resolve("entry.deal"), "export function main(): null {}\n");
        // Rooted sources.
        writeText(dir.resolve("src/main.deal"), "// importer\n");
        writeText(dir.resolve("src/util.deal"), "export function util(): null {}\n");
        writeText(dir.resolve("src/dup.deal"), "export function dupA(): null {}\n");
        writeText(dir.resolve("src/nested/inner.deal"), "// second importer\n");
        writeText(dir.resolve("src/nested/deep.deal"),
            "export function deepA(): null {}\n");
        // Shadowing candidates for the externals key "host".
        writeText(dir.resolve("src/host.deal"), "export function shadow(): null {}\n");
        writeText(dir.resolve("src/host.d.deal"), "export function shadowD(): null\n");
        writeText(dir.resolve("lib/helpers.deal"), "export function helper(): null {}\n");
        writeText(dir.resolve("lib/dup.deal"), "export function dupB(): null {}\n");
        writeText(dir.resolve("lib/utils/deep.deal"),
            "export function deepB(): null {}\n");
        // Externals declarations.
        writeText(dir.resolve("decl/host.d.deal"),
            "export function hostFn(): null\n");
        writeText(dir.resolve("decl/other.d.deal"),
            "export function otherFn(): null\n");
        // Same-named stdlib file OUTSIDE the pinned surface.
        writeText(dir.resolve("decl/console.d.deal"),
            "export function notBuiltinConsole(): null\n");
        // Project-local stdlib surface: one pinned file, one non-spec file.
        writeText(dir.resolve("std/console.d.deal"),
            "export function consoleLog(x: string): null\n");
        writeText(dir.resolve("std/custom.d.deal"),
            "export function customFn(): null\n");
        // Nested project: its own deal.json must be ignored (rule 5).
        writeText(dir.resolve("nestedproj/deal.json"),
            "{\"languageVersion\": \"1.2\"}\n");
        writeText(dir.resolve("nestedproj/mod.deal"),
            "export function nestedMod(): null {}\n");
        // Out-of-manifest sources (outside all roots and the manifest dir).
        writeText(tmpDir.resolve("sharedOuter.deal"),
            "export function outer(): null {}\n");
        writeText(tmpDir.resolve("sharedOuterDecl.d.deal"),
            "export function outerDecl(): null\n");
        // A real file outside every root plus a symlinked spelling inside
        // src pointing at it.
        writeText(tmpDir.resolve("realfiles/sym-shared.deal"),
            "export function symShared(): null {}\n");
        Files.createSymbolicLink(dir.resolve("src/link.deal"),
            tmpDir.resolve("realfiles/sym-shared.deal"));
        return locateOrFail(dir, "entry.deal");
    }

    private static Path matrixImporter() {
        return fixture("matrix").resolve("src/main.deal");
    }

    private static Path matrixSecondImporter() {
        return fixture("matrix").resolve("src/nested/inner.deal");
    }

    // =========================================================================
    // Resolution-rule matrix (combined T1+T4+T5 gate)
    // =========================================================================

    private static void testResolutionMatrix() throws Exception {
        System.out.println("-- Resolution matrix (locate + five rules + classification)");
        ProjectContext context = buildMatrixFixture();
        SourceModuleResolver resolver = new SourceModuleResolver(context);

        // Rule 2: ./ resolves from the importer's directory; ../ may leave
        // every configured root and the manifest directory.
        SourceModuleResolver.ResolveResult relative =
            resolver.resolve(matrixImporter().toString(), "./util");
        check(relative instanceof SourceModuleResolver.ResolveResult.Resolved,
            "relative ./util resolves");
        SourceModuleLocation util = ((SourceModuleResolver.ResolveResult.Resolved) relative)
            .location();
        check(util.normalizedSourcePath().endsWith("src/util.deal"),
            "relative ./util lands on src/util.deal ("
                + util.normalizedSourcePath() + ")");

        SourceModuleResolver.ResolveResult up =
            resolver.resolve(matrixImporter().toString(), "../nestedproj/mod");
        check(up instanceof SourceModuleResolver.ResolveResult.Resolved,
            "relative ../nestedproj/mod resolves (outside the importer dir)");

        SourceModuleResolver.ResolveResult outer =
            resolver.resolve(matrixImporter().toString(), "../../sharedOuter");
        check(outer instanceof SourceModuleResolver.ResolveResult.Resolved,
            "relative ../../sharedOuter resolves outside all roots and the"
                + " manifest directory");
        SourceModuleLocation outerLocation =
            ((SourceModuleResolver.ResolveResult.Resolved) outer).location();
        check(outerLocation.projectIdentity() == null
                && outerLocation.moduleClassification() == null,
            "out-of-root relative .deal carries private identity only"
                + " (classification: " + outerLocation.moduleClassification() + ")");
        check(!outerLocation.normalizedSourcePath().startsWith(
                context.manifestDirectory() + "/"),
            "out-of-root source's lexical path is outside the manifest directory ("
                + outerLocation.normalizedSourcePath() + ")");

        SourceModuleResolver.ResolveResult outerDecl =
            resolver.resolve(matrixImporter().toString(), "../../sharedOuterDecl");
        check(outerDecl instanceof SourceModuleResolver.ResolveResult.Resolved,
            "relative ../../sharedOuterDecl resolves (unlisted declaration"
                + " matching no externals entry, not a pinned stdlib file)");
        SourceModuleLocation outerDeclLocation =
            ((SourceModuleResolver.ResolveResult.Resolved) outerDecl).location();
        check(outerDeclLocation.moduleClassification() == null
                && outerDeclLocation.projectIdentity() == null,
            "out-of-root relative .d.deal carries private identity only");

        // Rule 3+4: bare lookup searches configured roots in manifest
        // order, then the pinned stdlib surface only.
        SourceModuleResolver.ResolveResult bareUtil =
            resolver.resolve(matrixImporter().toString(), "util");
        check(bareUtil instanceof SourceModuleResolver.ResolveResult.Resolved,
            "bare util resolves via a configured root");
        SourceModuleLocation bareUtilLocation =
            ((SourceModuleResolver.ResolveResult.Resolved) bareUtil).location();
        check(bareUtilLocation.semanticModuleIdentity().equals(
                util.semanticModuleIdentity()),
            "root spelling and ./ spelling of one resolved source yield one"
                + " semantic identity");

        // stdlib: bare std/console resolves from the project-local surface
        // (the only std/ source authority).
        SourceModuleResolver.ResolveResult console =
            resolver.resolve(matrixImporter().toString(), "std/console");
        check(console instanceof SourceModuleResolver.ResolveResult.Resolved,
            "bare std/console resolves (6-module filter)");
        SourceModuleLocation consoleLocation =
            ((SourceModuleResolver.ResolveResult.Resolved) console).location();
        check(consoleLocation.moduleClassification()
                instanceof CanonicalModuleIdentity.BuiltinModule,
            "bare std/console carries BuiltinModule");
        check(consoleLocation.semanticModuleIdentity().canonicalResolvedSourceUri()
                .endsWith("/std/console.d.deal"),
            "std/console resolves to the pinned surface file ("
                + consoleLocation.semanticModuleIdentity().canonicalResolvedSourceUri()
                + ")");

        // A relative spelling of the same canonical file carries the same
        // BuiltinModule classification and the same identity (mutual
        // exclusion end-to-end: no externals entry can reclassify it).
        SourceModuleResolver.ResolveResult consoleRelative =
            resolver.resolve(matrixImporter().toString(), "../std/console");
        check(consoleRelative instanceof SourceModuleResolver.ResolveResult.Resolved,
            "relative ../std/console resolves (extensionless spelling)");
        SourceModuleLocation consoleRelativeLocation =
            ((SourceModuleResolver.ResolveResult.Resolved) consoleRelative).location();
        check(consoleRelativeLocation == consoleLocation,
            "bare and relative spellings of the pinned stdlib file share one"
                + " memoized location");
        check(consoleRelativeLocation.moduleClassification()
                instanceof CanonicalModuleIdentity.BuiltinModule,
            "relative spelling of the pinned stdlib file carries BuiltinModule"
                + " (file-keyed)");

        // A non-spec .d.deal physically inside the std directory resolves
        // class-free with private identity only.
        SourceModuleResolver.ResolveResult custom =
            resolver.resolve(matrixImporter().toString(), "../std/custom");
        check(custom instanceof SourceModuleResolver.ResolveResult.Resolved,
            "relative ../std/custom resolves (reachable only by relative import)");
        SourceModuleLocation customLocation =
            ((SourceModuleResolver.ResolveResult.Resolved) custom).location();
        check(customLocation.moduleClassification() == null
                && customLocation.projectIdentity() == null,
            "non-spec .d.deal inside the stdlib directory carries private"
                + " identity only");

        // A same-named .d.deal outside the pinned surface is not builtin.
        SourceModuleResolver.ResolveResult declConsole =
            resolver.resolve(matrixImporter().toString(), "../decl/console");
        check(declConsole instanceof SourceModuleResolver.ResolveResult.Resolved,
            "relative ../decl/console resolves");
        SourceModuleLocation declConsoleLocation =
            ((SourceModuleResolver.ResolveResult.Resolved) declConsole).location();
        check(declConsoleLocation.moduleClassification() == null,
            "same-named .d.deal outside the pinned surface is not builtin");

        // Rule 1: the externals declaration is authoritative; on-disk
        // candidates shadowing the key are not consulted.
        SourceModuleResolver.ResolveResult host =
            resolver.resolve(matrixImporter().toString(), "host");
        check(host instanceof SourceModuleResolver.ResolveResult.Resolved,
            "externals-listed bare specifier resolves");
        SourceModuleLocation hostLocation =
            ((SourceModuleResolver.ResolveResult.Resolved) host).location();
        String expectedHostUri = context.externals().get("host")
            .declarationPath().absoluteNormalizedPath();
        check(hostLocation.semanticModuleIdentity().canonicalResolvedSourceUri()
                .equals(Path.of(expectedHostUri).normalize().toUri().toString()),
            "externals bare specifier resolves to the validated declaration path"
                + " (on-disk shadowing candidates not consulted): "
                + hostLocation.semanticModuleIdentity().canonicalResolvedSourceUri());
        check(hostLocation.moduleClassification()
                instanceof CanonicalModuleIdentity.ExternalModule external
                    && external.rawImportSpecifier().equals("host"),
            "externals-listed source carries ExternalModule('host')");

        // Rule 2 classification: a relative import landing on the externals
        // declaration keeps the externals identity (file-keyed).
        SourceModuleResolver.ResolveResult hostRelative =
            resolver.resolve(matrixImporter().toString(), "../decl/host");
        check(hostRelative instanceof SourceModuleResolver.ResolveResult.Resolved,
            "relative ../decl/host resolves");
        SourceModuleLocation hostRelativeLocation =
            ((SourceModuleResolver.ResolveResult.Resolved) hostRelative).location();
        check(hostRelativeLocation == hostLocation,
            "relative spelling of the externals declaration shares the memoized"
                + " location");
        check(hostRelativeLocation.moduleClassification()
                instanceof CanonicalModuleIdentity.ExternalModule external
                    && external.rawImportSpecifier().equals("host"),
            "relative spelling of the externals declaration keeps the externals"
                + " identity (file-keyed)");

        // Rule 4 E2009: a bare import whose resolution lands on a non-stdlib
        // .d.deal matching no externals entry is E2009 at the import span.
        SourceModuleResolver.ResolveResult other =
            resolver.resolve(matrixImporter().toString(), "other");
        check(other instanceof SourceModuleResolver.ResolveResult.Failure failure
                && failure.diagnostic().code().equals("E2009"),
            "bare other (rooted non-externals .d.deal) is E2009");
        check(resolver.resolvedLocations().size() == 8,
            "failed resolutions register nothing (resolved count: "
                + resolver.resolvedLocations().size() + ")");

        // Non-spec std/... bare import is E2003 outside the 6-module filter.
        SourceModuleResolver.ResolveResult nonSpec =
            resolver.resolve(matrixImporter().toString(), "std/io");
        check(nonSpec instanceof SourceModuleResolver.ResolveResult.Failure failure
                && failure.diagnostic().code().equals("E2003"),
            "non-spec std/io bare import is E2003");

        // A surface missing a spec-listed file is E2003 at the import span
        // (never a RuntimeException).
        SourceModuleResolver.ResolveResult missingSpec =
            resolver.resolve(matrixImporter().toString(), "std/table");
        check(missingSpec instanceof SourceModuleResolver.ResolveResult.Failure failure
                && failure.diagnostic().code().equals("E2003"),
            "std/table with a surface missing the spec-listed file is E2003");
        if (missingSpec instanceof SourceModuleResolver.ResolveResult.Failure failure) {
            check(failure.diagnostic().message().contains("table.d.deal"),
                "the miss message names the attempted surface candidate ("
                    + failure.diagnostic().message() + ")");
        }
    }

    private static void testRootOrder() throws Exception {
        System.out.println("-- Bare lookup searches configured roots in manifest order");
        ProjectContext context = buildMatrixFixture();
        SourceModuleResolver resolver = new SourceModuleResolver(context);
        SourceModuleResolver.ResolveResult dup =
            resolver.resolve(matrixImporter().toString(), "dup");
        check(dup instanceof SourceModuleResolver.ResolveResult.Resolved,
            "bare dup resolves");
        SourceModuleLocation dupLocation =
            ((SourceModuleResolver.ResolveResult.Resolved) dup).location();
        check(dupLocation.normalizedSourcePath().endsWith("src/dup.deal"),
            "first configured root wins (src before lib): "
                + dupLocation.normalizedSourcePath());
        check(dupLocation.moduleClassification()
                instanceof CanonicalModuleIdentity.ProjectModule project
                    && project.projectIdentity().configuredRootText().equals("src"),
            "dup carries the src project identity");
    }

    private static void testStdlibSurfaceRules() throws Exception {
        System.out.println("-- Stdlib surface rules");
        // Project-local surface first (covered by the matrix fixture):
        // the context's surface is <manifestDirectory>/std.
        ProjectContext matrix = buildMatrixFixture();
        check(matrix.stdlibSurfacePath().equals(
                matrix.manifestDirectory() + "/std"),
            "project-local <manifestDirectory>/std is the pinned surface ("
                + matrix.stdlibSurfacePath() + ")");

        // Language-distribution surface: a fixture without project-local
        // std/ falls back to <processCWD>/std (the repository's std/).
        Path dir = fixture("dist-surface");
        writeText(dir.resolve("deal.json"),
            "{\n  \"languageVersion\": \"1.2\",\n"
                + "  \"moduleRoots\": [\"src\"]\n}\n");
        writeText(dir.resolve("src/main.deal"), "// importer\n");
        ProjectContext dist = locateOrFail(dir, "entry.deal");
        check(dist.stdlibSurfacePath() != null
                && !dist.stdlibSurfacePath().startsWith(dist.manifestDirectory() + "/"),
            "without project-local std/ the language-distribution surface is"
                + " pinned (" + dist.stdlibSurfacePath() + ")");
        SourceModuleResolver resolver = new SourceModuleResolver(dist);
        SourceModuleResolver.ResolveResult console =
            resolver.resolve(dir.resolve("src/main.deal").toString(), "std/console");
        check(console instanceof SourceModuleResolver.ResolveResult.Resolved,
            "bare std/console resolves against the language-distribution surface");
        if (console instanceof SourceModuleResolver.ResolveResult.Resolved resolved) {
            check(resolved.location().moduleClassification()
                    instanceof CanonicalModuleIdentity.BuiltinModule,
                "the language-distribution std/console carries BuiltinModule");
        }

        // Missing surface: a bare std/... import is E2003 (no surface),
        // never a RuntimeException.
        Path empty = fixture("no-surface");
        ProjectContext noSurface = syntheticContext(empty, List.of("src"),
            Map.of(), null);
        SourceModuleResolver noSurfaceResolver = new SourceModuleResolver(noSurface);
        SourceModuleResolver.ResolveResult miss =
            noSurfaceResolver.resolve(empty.resolve("src/main.deal").toString(),
                "std/console");
        check(miss instanceof SourceModuleResolver.ResolveResult.Failure failure
                && failure.diagnostic().code().equals("E2003")
                && failure.diagnostic().message().contains("absent"),
            "a missing stdlib surface is E2003 (never a RuntimeException): "
                + describe(miss));
    }

    private static void testStdlibSurfaceSinglePinnedCandidate()
            throws Exception {
        System.out.println("-- Stdlib surface: the single pinned .d.deal candidate only");
        Path dir = fixture("single-pinned");
        writeText(dir.resolve("deal.json"),
            "{\n  \"languageVersion\": \"1.2\",\n"
                + "  \"moduleRoots\": [\"src\"]\n}\n");
        writeText(dir.resolve("src/main.deal"), "// importer\n");
        // Non-spec candidate shapes under the surface must never satisfy
        // a bare std/... import: console.d.deal is absent while the
        // generic S.deal candidate exists.
        writeText(dir.resolve("std/console.deal"),
            "export function notSpecConsole(): null {}\n");
        // The pinned .d.deal candidate of a spec-listed module resolves.
        writeText(dir.resolve("std/math.d.deal"),
            "export function mathAbs(x: number): number\n");
        ProjectContext context = locateOrFail(dir, "entry.deal");
        SourceModuleResolver resolver = new SourceModuleResolver(context);
        Path importer = dir.resolve("src/main.deal");

        SourceModuleResolver.ResolveResult console =
            resolver.resolve(importer.toString(), "std/console");
        check(console instanceof SourceModuleResolver.ResolveResult.Failure failure
                && failure.diagnostic().code().equals("E2003"),
            "std/console with only std/console.deal under the surface is E2003"
                + " (the non-spec .deal candidate is never consulted): "
                + describe(console));
        if (console instanceof SourceModuleResolver.ResolveResult.Failure failure) {
            check(failure.diagnostic().message().contains("console.d.deal"),
                "the miss names the single pinned candidate console.d.deal ("
                    + failure.diagnostic().message() + ")");
        }

        // The index.deal variant: console.d.deal absent, only
        // std/console/index.deal present.
        Files.delete(dir.resolve("std/console.deal"));
        writeText(dir.resolve("std/console/index.deal"),
            "export function notSpecIndex(): null {}\n");
        SourceModuleResolver.ResolveResult indexVariant =
            resolver.resolve(importer.toString(), "std/console");
        check(indexVariant instanceof SourceModuleResolver.ResolveResult.Failure failure
                && failure.diagnostic().code().equals("E2003"),
            "std/console with only std/console/index.deal under the surface is"
                + " E2003: " + describe(indexVariant));

        // Positive control: the single pinned candidate resolves and
        // carries BuiltinModule.
        SourceModuleResolver.ResolveResult math =
            resolver.resolve(importer.toString(), "std/math");
        check(math instanceof SourceModuleResolver.ResolveResult.Resolved,
            "bare std/math resolves through the single pinned .d.deal"
                + " candidate: " + describe(math));
        if (math instanceof SourceModuleResolver.ResolveResult.Resolved resolved) {
            check(resolved.location().moduleClassification()
                        instanceof CanonicalModuleIdentity.BuiltinModule,
                "std/math carries BuiltinModule through the pinned candidate");
            check(resolved.location().semanticModuleIdentity()
                        .canonicalResolvedSourceUri().endsWith("/std/math.d.deal"),
                "std/math resolves to the pinned surface file ("
                    + resolved.location().semanticModuleIdentity()
                        .canonicalResolvedSourceUri() + ")");
        }
        check(resolver.resolvedLocations().size() == 1,
            "only the pinned-candidate resolution registered a location");

        // Unreadable pinned candidate → E2003 (never a raw exception).
        if (makeUnreadable(dir.resolve("std/math.d.deal"))) {
            SourceModuleResolver.ResolveResult unreadable =
                resolver.resolve(importer.toString(), "std/math");
            check(unreadable instanceof SourceModuleResolver.ResolveResult.Failure
                        failure
                    && failure.diagnostic().code().equals("E2003")
                    && failure.diagnostic().message()
                        .contains("not a readable module"),
                "unreadable pinned stdlib file is E2003 at the import span: "
                    + describe(unreadable));
            check(resolver.resolvedLocations().size() == 1,
                "the unreadable-candidate failure registers nothing");
        } else {
            System.out.println("  SKIP: platform reports the pinned file readable"
                + " (running as root?)");
        }
        makeWritable(dir.resolve("std/math.d.deal"));
    }

    private static void testExternalsAuthorityAndFileKeyedClassification()
            throws Exception {
        System.out.println("-- File-keyed externals classification via bare root search");
        Path dir = fixture("rootlanding");
        writeText(dir.resolve("deal.json"),
            "{\n  \"languageVersion\": \"1.2\",\n"
                + "  \"moduleRoots\": [\".\"],\n"
                + "  \"externals\": {\n"
                + "    \"host\": {\"declaration\": \"decl/host.d.deal\"}\n"
                + "  }\n}\n");
        writeText(dir.resolve("decl/host.d.deal"),
            "export function hostFn(): null\n");
        ProjectContext context = locateOrFail(dir, "entry.deal");
        SourceModuleResolver resolver = new SourceModuleResolver(context);
        // Rule 1 did not match ("decl/host" is not an externals key), but
        // the root search lands on the externals entry's declaration file:
        // file-keyed classification still carries ExternalModule("host").
        SourceModuleResolver.ResolveResult landing =
            resolver.resolve(dir.resolve("entry.deal").toString(), "decl/host");
        check(landing instanceof SourceModuleResolver.ResolveResult.Resolved,
            "bare decl/host resolves via root search");
        if (landing instanceof SourceModuleResolver.ResolveResult.Resolved resolved) {
            check(resolved.location().moduleClassification()
                    instanceof CanonicalModuleIdentity.ExternalModule external
                        && external.rawImportSpecifier().equals("host"),
                "bare root-search landing on the externals declaration carries"
                    + " ExternalModule('host') even though rule 1 did not match");
        }
    }

    private static void testE2009AndRelativeExemption() throws Exception {
        System.out.println("-- E2009 scope and the relative-import exemption");
        ProjectContext context = buildMatrixFixture();
        SourceModuleResolver resolver = new SourceModuleResolver(context);
        SourceModuleResolver.ResolveResult other =
            resolver.resolve(matrixImporter().toString(), "other");
        check(other instanceof SourceModuleResolver.ResolveResult.Failure failure
                && failure.diagnostic().code().equals("E2009"),
            "bare rooted non-externals .d.deal is E2009");
        // The same canonical file through a relative import resolves
        // class-free (relative exemption preserved).
        SourceModuleResolver.ResolveResult otherRelative =
            resolver.resolve(matrixImporter().toString(), "../decl/other");
        check(otherRelative instanceof SourceModuleResolver.ResolveResult.Resolved,
            "relative import (extensionless spelling) of the same declaration"
                + " resolves class-free");
        if (otherRelative instanceof SourceModuleResolver.ResolveResult.Resolved resolved) {
            check(resolved.location().moduleClassification() == null
                    && resolved.location().projectIdentity() == null,
                "relative .d.deal matching no entry carries private identity only");
        }
    }

    private static void testNestedDealJsonIgnored() throws Exception {
        System.out.println("-- Rule 5: nested deal.json is never rediscovered");
        ProjectContext context = buildMatrixFixture();
        SourceModuleResolver resolver = new SourceModuleResolver(context);
        SourceModuleResolver.ResolveResult nested =
            resolver.resolve(matrixImporter().toString(), "../nestedproj/mod");
        check(nested instanceof SourceModuleResolver.ResolveResult.Resolved,
            "a module under a nested project resolves through the entry"
                + " project's one context (nested deal.json ignored, no error)");
    }

    private static void testUnreadableCandidate() throws Exception {
        System.out.println("-- Unreadable candidate → E2003 (never a raw exception)");
        ProjectContext context = buildMatrixFixture();
        Path locked = fixture("matrix").resolve("src/locked.deal");
        writeText(locked, "export function locked(): null {}\n");
        if (!makeUnreadable(locked)) {
            System.out.println("  SKIP: platform reports the file readable"
                + " (running as root?)");
            return;
        }
        SourceModuleResolver resolver = new SourceModuleResolver(context);
        SourceModuleResolver.ResolveResult result =
            resolver.resolve(matrixImporter().toString(), "locked");
        check(result instanceof SourceModuleResolver.ResolveResult.Failure failure
                && failure.diagnostic().code().equals("E2003")
                && failure.diagnostic().message().contains("not a readable module"),
            "unreadable candidate is E2003 at the import span: " + describe(result));
        makeWritable(locked);
    }

    // =========================================================================
    // Diagnostic ranges (E1 complete-range carrier)
    // =========================================================================

    private static void testDiagnosticRanges() throws Exception {
        System.out.println("-- E2003/E2009 complete DiagnosticRanges");
        ProjectContext context = buildMatrixFixture();
        SourceModuleResolver resolver = new SourceModuleResolver(context);
        Path importer = matrixImporter();

        Span span = new Span(importer.toString(), 3, 9, 3, 26, 42, 59);
        SourceModuleResolver.ResolveResult miss =
            resolver.resolve(importer.toString(), "no-such-module-zzz", span);
        check(miss instanceof SourceModuleResolver.ResolveResult.Failure failure
                && failure.diagnostic().code().equals("E2003"),
            "unresolved bare import is E2003");
        if (miss instanceof SourceModuleResolver.ResolveResult.Failure failure) {
            DiagnosticRange range = failure.diagnostic().range();
            check(range.origin() == RangeOrigin.SOURCE
                    && range.startScalarOffset() == 42
                    && range.endScalarOffset() == 59
                    && range.scalarLength() == 17,
                "E2003 carries the complete SOURCE scalar range [42,59)");
            check(range.startLine() == 3 && range.startColumn() == 9
                    && range.endLine() == 3 && range.endColumn() == 27,
                "E2003 range positions match the span's half-open translation");
        }

        Span e2009Span = new Span(importer.toString(), 4, 5, 4, 20, 70, 85);
        SourceModuleResolver.ResolveResult undeclared =
            resolver.resolve(importer.toString(), "other", e2009Span);
        check(undeclared instanceof SourceModuleResolver.ResolveResult.Failure failure
                && failure.diagnostic().code().equals("E2009"),
            "undeclared bare .d.deal is E2009");
        if (undeclared instanceof SourceModuleResolver.ResolveResult.Failure failure) {
            DiagnosticRange range = failure.diagnostic().range();
            check(range.origin() == RangeOrigin.SOURCE
                    && range.startScalarOffset() == 70
                    && range.endScalarOffset() == 85,
                "E2009 carries the complete SOURCE scalar range [70,85)");
        }

        // Anchorless: the pinned synthetic shape plus an anchor note.
        SourceModuleResolver.ResolveResult anchorless =
            resolver.resolve(importer.toString(), "no-such-module-yyy");
        check(anchorless instanceof SourceModuleResolver.ResolveResult.Failure failure
                && failure.diagnostic().range().isCanonicalSynthetic(),
            "anchorless E2003 uses the canonical synthetic range");
        if (anchorless instanceof SourceModuleResolver.ResolveResult.Failure failure) {
            boolean hasAnchor = failure.diagnostic().notes().stream()
                .map(DiagnosticNote::message)
                .anyMatch(message -> message.startsWith("missing anchor:"));
            check(hasAnchor, "anchorless E2003 carries the anchor note");
        }
    }

    // =========================================================================
    // Identity assignment, memoization, determinism
    // =========================================================================

    private static void testIdentityMemoization() throws Exception {
        System.out.println("-- One semantic identity per canonical URI");
        ProjectContext context = buildMatrixFixture();
        SourceModuleResolver resolver = new SourceModuleResolver(context);

        SourceModuleResolver.ResolveResult spelling1 =
            resolver.resolve(matrixImporter().toString(), "./util");
        SourceModuleResolver.ResolveResult spelling2 =
            resolver.resolve(matrixImporter().toString(), "util");
        SourceModuleResolver.ResolveResult spelling3 =
            resolver.resolve(matrixSecondImporter().toString(), "../util");
        check(spelling1 instanceof SourceModuleResolver.ResolveResult.Resolved
                && spelling2 instanceof SourceModuleResolver.ResolveResult.Resolved
                && spelling3 instanceof SourceModuleResolver.ResolveResult.Resolved,
            "all three spellings (./util, util, ../util) resolve");
        SourceModuleLocation location1 =
            ((SourceModuleResolver.ResolveResult.Resolved) spelling1).location();
        SourceModuleLocation location2 =
            ((SourceModuleResolver.ResolveResult.Resolved) spelling2).location();
        SourceModuleLocation location3 =
            ((SourceModuleResolver.ResolveResult.Resolved) spelling3).location();
        check(location1 == location2 && location1 == location3,
            "equivalent spellings share one memoized location instance");
        check(location1.semanticModuleIdentity().projectDeploymentIdentity()
                == context.projectDeploymentIdentity(),
            "the semantic identity embeds the context's deployment identity");
        check(location1.deploymentModuleId().startsWith("m")
                && location1.deploymentModuleId().length() == 17,
            "deploymentModuleId has the pinned m+16-hex shape");

        // Most-specific root selection via the classifier invoked at
        // publication.
        SourceModuleResolver.ResolveResult deep =
            resolver.resolve(matrixImporter().toString(), "utils/deep");
        check(deep instanceof SourceModuleResolver.ResolveResult.Resolved,
            "bare utils/deep resolves");
        if (deep instanceof SourceModuleResolver.ResolveResult.Resolved resolved) {
            ProjectModuleIdentity identity = resolved.location().projectIdentity();
            check(identity != null && identity.configuredRootText().equals("lib/utils"),
                "lib/utils/deep.deal selects the most-specific root lib/utils ("
                    + (identity == null ? "none" : identity.configuredRootText()) + ")");
            if (identity != null) {
                check(identity.relativeModuleComponents().isEmpty(),
                    "a file directly inside lib/utils has empty relative components");
            }
        }
        SourceModuleResolver.ResolveResult nested =
            resolver.resolve(matrixImporter().toString(), "nested/deep");
        check(nested instanceof SourceModuleResolver.ResolveResult.Resolved,
            "bare nested/deep resolves");
        if (nested instanceof SourceModuleResolver.ResolveResult.Resolved resolved) {
            ProjectModuleIdentity identity = resolved.location().projectIdentity();
            check(identity != null && identity.configuredRootText().equals("src")
                    && identity.relativeModuleComponents().equals(List.of("nested")),
                "src/nested/deep.deal selects src with relative components [nested] ("
                    + identity + ")");
        }
        SourceModuleResolver.ResolveResult helpers =
            resolver.resolve(matrixImporter().toString(), "helpers");
        check(helpers instanceof SourceModuleResolver.ResolveResult.Resolved,
            "bare helpers resolves");
        if (helpers instanceof SourceModuleResolver.ResolveResult.Resolved resolved) {
            ProjectModuleIdentity identity = resolved.location().projectIdentity();
            check(identity != null && identity.configuredRootText().equals("lib")
                    && identity.relativeModuleComponents().isEmpty(),
                "lib/helpers.deal selects lib with empty relative components");
        }
    }

    private static void testSymlinkSpellings() throws Exception {
        System.out.println("-- Symlinked spellings share one identity");
        ProjectContext context = buildMatrixFixture();
        SourceModuleResolver resolver = new SourceModuleResolver(context);
        SourceModuleResolver.ResolveResult viaLink =
            resolver.resolve(matrixImporter().toString(), "./link");
        SourceModuleResolver.ResolveResult direct =
            resolver.resolve(matrixImporter().toString(),
                "../../realfiles/sym-shared");
        check(viaLink instanceof SourceModuleResolver.ResolveResult.Resolved
                && direct instanceof SourceModuleResolver.ResolveResult.Resolved,
            "both the symlinked spelling and the direct spelling resolve");
        SourceModuleLocation viaLinkLocation =
            ((SourceModuleResolver.ResolveResult.Resolved) viaLink).location();
        SourceModuleLocation directLocation =
            ((SourceModuleResolver.ResolveResult.Resolved) direct).location();
        check(viaLinkLocation == directLocation,
            "symlinked and direct spellings of one file share one memoized location");
        check(viaLinkLocation.semanticModuleIdentity().canonicalResolvedSourceUri()
                .endsWith("realfiles/sym-shared.deal"),
            "the canonical URI is the fully symlink-resolved target ("
                + viaLinkLocation.semanticModuleIdentity()
                    .canonicalResolvedSourceUri() + ")");
        check(viaLinkLocation.projectIdentity() == null
                && viaLinkLocation.moduleClassification() == null,
            "a source whose file is a symlink pointing outside its lexical root"
                + " receives no project identity");
    }

    /**
     * Regression (review finding): a spec-listed stdlib declaration file
     * that is itself a symlink must still classify as BuiltinModule for
     * every spelling. ProjectLocator derives the canonical six-file set
     * at locate (each existing file fully symlink-resolved) and
     * publishes it on ProjectContext.pinnedStdlibFiles; the pure
     * classifier compares the canonical source URI against that set, so
     * the bare std/console import does not fall through to the file-keyed
     * E2009 gate and the relative spelling does not lose BuiltinModule.
     */
    private static void testSymlinkedPinnedStdlibFile() throws Exception {
        System.out.println("-- Symlinked pinned stdlib file (file-keyed BuiltinModule)");
        Path dir = fixture("symlinked-stdlib");
        writeText(dir.resolve("deal.json"),
            "{\n  \"languageVersion\": \"1.2\",\n"
                + "  \"moduleRoots\": [\"src\"]\n}\n");
        writeText(dir.resolve("entry.deal"), "export function main(): null {}\n");
        writeText(dir.resolve("src/main.deal"), "// importer\n");
        // The pinned file is itself a symlink to a real file outside the
        // surface and outside every configured root.
        Path realFile = writeText(tmpDir.resolve("realstdlib/console.d.deal"),
            "export function consoleLog(x: string): null\n");
        Files.createDirectories(dir.resolve("std"));
        Files.createSymbolicLink(dir.resolve("std/console.d.deal"),
            realFile.toAbsolutePath());
        ProjectContext context = locateOrFail(dir, "entry.deal");

        String realPath = realFile.toRealPath().toString();
        check(context.pinnedStdlibFiles().equals(List.of(realPath)),
            "locate publishes the fully symlink-resolved pinned file path ("
                + context.pinnedStdlibFiles() + ")");

        SourceModuleResolver resolver = new SourceModuleResolver(context);
        Path importer = dir.resolve("src/main.deal");
        SourceModuleResolver.ResolveResult bare =
            resolver.resolve(importer.toString(), "std/console");
        check(bare instanceof SourceModuleResolver.ResolveResult.Resolved,
            "bare std/console through the symlinked pinned file resolves"
                + " (no bogus E2009): " + describe(bare));
        SourceModuleResolver.ResolveResult relative =
            resolver.resolve(importer.toString(), "../std/console");
        check(relative instanceof SourceModuleResolver.ResolveResult.Resolved,
            "relative ../std/console of the same canonical file resolves: "
                + describe(relative));
        if (bare instanceof SourceModuleResolver.ResolveResult.Resolved bareHit
                && relative instanceof SourceModuleResolver.ResolveResult.Resolved
                    relativeHit) {
            check(bareHit.location().moduleClassification()
                        instanceof CanonicalModuleIdentity.BuiltinModule
                    && bareHit.location().projectIdentity() == null,
                "bare std/console through the symlinked pinned file carries"
                    + " BuiltinModule");
            check(relativeHit.location().moduleClassification()
                        instanceof CanonicalModuleIdentity.BuiltinModule
                    && relativeHit.location().projectIdentity() == null,
                "the relative spelling of the same canonical file carries"
                    + " BuiltinModule (file-keyed, spelling-independent)");
            check(bareHit.location() == relativeHit.location(),
                "both spellings share the one memoized location (one semantic"
                    + " identity per canonical URI)");
        }
        check(resolver.resolvedLocations().size() == 1,
            "exactly one location published for the one canonical file");
    }

    private static void testDeploymentMutation() throws Exception {
        System.out.println("-- Manifest-content and source-relocation identity changes");
        ProjectContext before = buildMatrixFixture();
        SourceModuleResolver beforeResolver = new SourceModuleResolver(before);
        SourceModuleResolver.ResolveResult utilBefore =
            beforeResolver.resolve(matrixImporter().toString(), "./util");
        check(utilBefore instanceof SourceModuleResolver.ResolveResult.Resolved,
            "initial ./util resolves");
        SemanticModuleIdentity identityBefore =
            ((SourceModuleResolver.ResolveResult.Resolved) utilBefore).location()
                .semanticModuleIdentity();

        // A manifest byte change (whitespace only) changes the deployment
        // identity and therefore every derived semantic identity.
        Path manifest = fixture("matrix").resolve("deal.json");
        writeText(manifest, Files.readString(manifest) + "\n");
        ProjectContext after = locateOrFail(fixture("matrix"), "entry.deal");
        check(!after.projectDeploymentIdentity().equals(
                before.projectDeploymentIdentity()),
            "a manifest byte change changes the deployment identity");
        SourceModuleResolver afterResolver = new SourceModuleResolver(after);
        SourceModuleResolver.ResolveResult utilAfter =
            afterResolver.resolve(matrixImporter().toString(), "./util");
        check(utilAfter instanceof SourceModuleResolver.ResolveResult.Resolved,
            "./util still resolves after the manifest change");
        SemanticModuleIdentity identityAfter =
            ((SourceModuleResolver.ResolveResult.Resolved) utilAfter).location()
                .semanticModuleIdentity();
        check(!identityAfter.equals(identityBefore)
                && identityAfter.canonicalResolvedSourceUri().equals(
                    identityBefore.canonicalResolvedSourceUri()),
            "a manifest byte change changes every semantic identity while the"
                + " canonical source URI stays fixed");

        // A source relocation changes that source's identity.
        Path util = fixture("matrix").resolve("src/util.deal");
        Path relocated = fixture("matrix").resolve("src/relocated.deal");
        Files.move(util, relocated);
        SourceModuleResolver relocatedResolver = new SourceModuleResolver(after);
        SourceModuleResolver.ResolveResult relocatedResult =
            relocatedResolver.resolve(matrixImporter().toString(), "./relocated");
        check(relocatedResult instanceof SourceModuleResolver.ResolveResult.Resolved,
            "the relocated source resolves under its new path");
        SemanticModuleIdentity relocatedIdentity =
            ((SourceModuleResolver.ResolveResult.Resolved) relocatedResult).location()
                .semanticModuleIdentity();
        check(!relocatedIdentity.canonicalResolvedSourceUri().equals(
                identityAfter.canonicalResolvedSourceUri()),
            "a source relocation changes that source's canonical URI");
        check(!relocatedIdentity.equals(identityAfter),
            "a source relocation changes that source's semantic identity");
        Files.move(relocated, util);
    }

    private static void testDeploymentModuleIdFormula() throws Exception {
        System.out.println("-- deploymentModuleId pinned formula and byte stability");
        ProjectContext context = buildMatrixFixture();
        SourceModuleResolver resolver = new SourceModuleResolver(context);
        SourceModuleResolver.ResolveResult result =
            resolver.resolve(matrixImporter().toString(), "./util");
        check(result instanceof SourceModuleResolver.ResolveResult.Resolved,
            "./util resolves");
        SourceModuleLocation location =
            ((SourceModuleResolver.ResolveResult.Resolved) result).location();
        String expected = expectedDeploymentModuleId(
            context.projectDeploymentIdentity().validatedManifestContentDigest(),
            location.semanticModuleIdentity().canonicalResolvedSourceUri());
        check(location.deploymentModuleId().equals(expected),
            "deploymentModuleId matches the pinned formula (" + expected + ")");

        SourceModuleResolver secondResolver = new SourceModuleResolver(context);
        SourceModuleResolver.ResolveResult second =
            secondResolver.resolve(matrixImporter().toString(), "./util");
        check(second instanceof SourceModuleResolver.ResolveResult.Resolved
                && ((SourceModuleResolver.ResolveResult.Resolved) second).location()
                    .deploymentModuleId().equals(location.deploymentModuleId()),
            "deploymentModuleId is byte-stable across resolver instances");
    }

    private static void testProviderContractDigestDomain() throws Exception {
        System.out.println("-- providerContractDigest domain and determinism");
        String digest1 = RuntimeResourceReference.providerContractDigestOf(
            "canonical provider content");
        String digest2 = RuntimeResourceReference.providerContractDigestOf(
            "canonical provider content");
        check(digest1.equals(digest2),
            "identical canonical provider content yields the identical digest");
        byte[] framed = lengthPrefixed("canonical provider content");
        check(digest1.equals(sha256Hex(framed)),
            "the digest is SHA-256 over the length-prefixed content");
        String different = RuntimeResourceReference.providerContractDigestOf(
            "canonical provider content!");
        check(!digest1.equals(different),
            "different provider content yields a different digest");
        boolean rejectedSurrogate = false;
        try {
            RuntimeResourceReference.providerContractDigestOf(
                "unpaired high surrogate: \uD800");
        } catch (IllegalArgumentException expected) {
            rejectedSurrogate = true;
        }
        check(rejectedSurrogate,
            "non-scalar provider content is rejected, never silently replaced");
    }

    private static void testPrivacyInvariant() throws Exception {
        System.out.println("-- Privacy invariant: no identity text in diagnostics");
        ProjectContext context = buildMatrixFixture();
        SourceModuleResolver resolver = new SourceModuleResolver(context);
        String digest = context.projectDeploymentIdentity()
            .validatedManifestContentDigest();
        List<CompilerDiagnostic> diagnostics = new ArrayList<>();
        diagnostics.add(failureOf(resolver, "no-such-module-qqq"));
        diagnostics.add(failureOf(resolver, "std/io"));
        diagnostics.add(failureOf(resolver, "other"));
        diagnostics.add(failureOf(resolver, "std/console"));
        for (CompilerDiagnostic diagnostic : diagnostics) {
            if (diagnostic == null) {
                continue;
            }
            check(!diagnostic.message().contains("file:/")
                    && !diagnostic.message().contains("file://"),
                "no diagnostic message leaks a file: URI ("
                    + diagnostic.message() + ")");
            check(!diagnostic.message().contains(digest),
                "no diagnostic message leaks the deployment digest");
            for (SourceModuleLocation location : resolver.resolvedLocations().values()) {
                check(!diagnostic.message().contains(
                        location.deploymentModuleId()),
                    "no diagnostic message leaks a deploymentModuleId");
                check(!diagnostic.message().contains(
                        location.semanticModuleIdentity().canonicalResolvedSourceUri()),
                    "no diagnostic message leaks a canonical source URI");
            }
        }
        check(context.projectDeploymentIdentity().canonicalManifestUri()
                .startsWith("file:"),
            "the deployment identity carries the canonical manifest file: URI"
                + " only as a compiler-internal field");
    }

    /** The failure diagnostic of a resolution, or null when it resolved. */
    private static CompilerDiagnostic failureOf(SourceModuleResolver resolver,
                                                String specifier) {
        SourceModuleResolver.ResolveResult result =
            resolver.resolve(matrixImporter().toString(), specifier);
        return result instanceof SourceModuleResolver.ResolveResult.Failure failure
            ? failure.diagnostic()
            : null;
    }

    private static void testNoCwdFallbackSubprocess() throws Exception {
        System.out.println("-- No CWD module fallback for bare lookup (subprocess)");
        Path dir = fixture("cwd-fallback");
        Files.createDirectories(dir);
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
            absoluteCp.toString(), SourceModuleResolverTest.class.getName(),
            "--sub-cwd-fallback", dir.toString());
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(dir.toFile());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(),
            StandardCharsets.UTF_8);
        int exit = process.waitFor();
        check(exit == 0 && output.trim().startsWith("E2003:"),
            "a bare module reachable only from the process CWD is E2003 (the old"
                + " CWD fallback is gone): exit=" + exit + " output=" + output.trim());
    }

    // =========================================================================
    // Classifier matrix (the T5 dependency exercised end-to-end)
    // =========================================================================

    private static void testClassifierMatrix() throws Exception {
        System.out.println("-- Classifier matrix (containment, classification, precedence)");
        ProjectContext context = buildMatrixFixture();
        Path dir = fixture("matrix").toRealPath();

        // Pinned stdlib file → BuiltinModule.
        ModuleClassification stdlib = ModuleIdentityResolver.classify(context,
            dir.resolve("std/console.d.deal").toUri().toString());
        check(stdlib.moduleIdentity() instanceof CanonicalModuleIdentity.BuiltinModule
                && stdlib.projectIdentity() == null
                && stdlib.issue() == ModuleClassification.Issue.NONE,
            "pinned stdlib file → BuiltinModule");

        // Same-named .d.deal outside the pinned surface → not builtin.
        ModuleClassification sameNamed = ModuleIdentityResolver.classify(context,
            dir.resolve("decl/console.d.deal").toUri().toString());
        check(sameNamed.moduleIdentity() == null
                && sameNamed.issue() == ModuleClassification.Issue.NONE,
            "same-named .d.deal outside the pinned surface is not builtin");

        // Externals declaration → ExternalModule(that key).
        ModuleClassification external = ModuleIdentityResolver.classify(context,
            dir.resolve("decl/host.d.deal").toUri().toString());
        check(external.moduleIdentity()
                    instanceof CanonicalModuleIdentity.ExternalModule module
                        && module.rawImportSpecifier().equals("host"),
            "externals declaration file → ExternalModule('host')");

        // Rooted .deal → ProjectModule with the exact derived identity.
        ModuleClassification rooted = ModuleIdentityResolver.classify(context,
            dir.resolve("src/nested/deep.deal").toUri().toString());
        check(rooted.moduleIdentity()
                    instanceof CanonicalModuleIdentity.ProjectModule module
                        && module.projectIdentity().configuredRootText().equals("src")
                        && module.projectIdentity().relativeModuleComponents()
                            .equals(List.of("nested")),
            "rooted .deal → ProjectModule(src, [nested])");
        check(rooted.projectIdentity() != null
                && rooted.projectIdentity().equals(
                    ((CanonicalModuleIdentity.ProjectModule)
                        rooted.moduleIdentity()).projectIdentity()),
            "the classifier result carries the projectIdentity provenance");

        // Most-specific selection across nested roots.
        ModuleClassification nestedRoot = ModuleIdentityResolver.classify(context,
            dir.resolve("lib/utils/deep.deal").toUri().toString());
        check(nestedRoot.moduleIdentity()
                    instanceof CanonicalModuleIdentity.ProjectModule module
                        && module.projectIdentity().configuredRootText().equals("lib/utils"),
            "lib/utils/deep.deal selects the most-specific root lib/utils");

        // Rooted non-externals .d.deal → no public identity.
        ModuleClassification rootedDecl = ModuleIdentityResolver.classify(context,
            dir.resolve("decl/other.d.deal").toUri().toString());
        check(rootedDecl.moduleIdentity() == null
                && rootedDecl.projectIdentity() == null,
            "rooted non-externals .d.deal → no public module identity");

        // Non-spec .d.deal inside the stdlib directory → no public identity.
        ModuleClassification custom = ModuleIdentityResolver.classify(context,
            dir.resolve("std/custom.d.deal").toUri().toString());
        check(custom.moduleIdentity() == null,
            "non-spec .d.deal inside the stdlib directory → no public identity");

        // Out-of-root .deal → no public identity.
        ModuleClassification outOfRoot = ModuleIdentityResolver.classify(context,
            tmpDir.toRealPath().resolve("sharedOuter.deal").toUri().toString());
        check(outOfRoot.moduleIdentity() == null
                && outOfRoot.projectIdentity() == null,
            "out-of-root .deal → no public identity");

        // A symlink target outside its lexical root → no public identity.
        ModuleClassification escaped = ModuleIdentityResolver.classify(context,
            tmpDir.toRealPath().resolve("realfiles/sym-shared.deal").toUri()
                .toString());
        check(escaped.moduleIdentity() == null,
            "symlink target outside its lexical root → no public identity");

        // Absent surface → no source carries BuiltinModule.
        ProjectContext noSurface = syntheticContext(fixture("classifier-nosurf"),
            List.of("src"), Map.of(), null);
        Path nosurfDir = fixture("classifier-nosurf");
        Files.createDirectories(nosurfDir);
        ModuleClassification absent = ModuleIdentityResolver.classify(noSurface,
            nosurfDir.toRealPath()
                .resolve("std/console.d.deal").toUri().toString());
        check(absent.moduleIdentity() == null,
            "with an absent surface no source carries BuiltinModule");

        // Null, malformed, and non-file URIs classify to none without
        // throwing (totality).
        check(ModuleIdentityResolver.classify(context, null).moduleIdentity() == null,
            "null URI classifies to none");
        check(ModuleIdentityResolver.classify(context, "not a uri::%%%")
                .moduleIdentity() == null,
            "malformed URI classifies to none");
        check(ModuleIdentityResolver.classify(context, "http://example.test/x.deal")
                .moduleIdentity() == null,
            "non-file URI classifies to none");
    }

    private static void testClassifierDefensiveReports() throws Exception {
        System.out.println("-- Classifier defensive issue reports");
        Path dir = fixture("classifier-defensive");
        Files.createDirectories(dir.resolve("src"));
        Path realDir = dir.toRealPath();

        // Equal-root tie: two distinct configured texts, one normalized path.
        List<ConfiguredModuleRoot> tieRoots = List.of(
            new ConfiguredModuleRoot("a",
                prefixResolvedText(dir.resolve("src").toString()),
                dummyRange()),
            new ConfiguredModuleRoot("b",
                prefixResolvedText(dir.resolve("src").toString()),
                dummyRange()));
        ProjectContext tieContext = new ProjectContext(
            dir.resolve("deal.json").normalize().toString(),
            dir.normalize().toString(), dir.normalize().toString(), "1.2",
            tieRoots,
            new OutputConfigResolver.OutputRef(OutputConfigResolver.Source.MANIFEST,
                OutputConfigResolver.Kind.MANIFEST_RELATIVE_PATH, "build/lua",
                dir.resolve("build/lua").normalize().toString(), null),
            "luajit", Map.of(), "1.2", null, List.of(),
            new ProjectDeploymentIdentity(
                dir.resolve("deal.json").normalize().toUri().toString(),
                "0".repeat(64)));
        ModuleClassification tie = ModuleIdentityResolver.classify(tieContext,
            realDir.resolve("src/x.deal").toUri().toString());
        check(tie.issue() == ModuleClassification.Issue.EQUAL_ROOT_TIE
                && tie.moduleIdentity() == null && tie.projectIdentity() == null,
            "equal-root containment reports EQUAL_ROOT_TIE with no identity"
                + " published");

        // Both-match state (unreachable for a valid context): an externals
        // entry whose declaration equals a pinned stdlib file reports
        // STDLIB_EXTERNAL_OVERLAP with no identity published.
        Path overlapDir = fixture("classifier-overlap");
        Files.createDirectories(overlapDir.resolve("std"));
        Path pinned = writeText(overlapDir.resolve("std/console.d.deal"),
            "export function c(): null\n");
        String pinnedPath = pinned.toRealPath().toString();
        Map<String, ExternalEntry> overlapExternals = new LinkedHashMap<>();
        overlapExternals.put("console", new ExternalEntry("console",
            new NormalizedDeclarationPath(pinnedPath, dummyRange()), null,
            dummyRange()));
        ProjectContext overlapContext = syntheticContext(overlapDir, List.of(),
            Map.of(), overlapDir.resolve("std").toRealPath().toString());
        // Rebuild with the overlapping externals map (syntheticContext takes
        // declaration texts; rebuild the map directly).
        overlapContext = new ProjectContext(
            overlapContext.manifestPath(), overlapContext.projectRoot(),
            overlapContext.manifestDirectory(), overlapContext.languageVersion(),
            overlapContext.configuredModuleRoots(), overlapContext.outputPath(),
            overlapContext.backend(), overlapExternals,
            overlapContext.stdlibVersion(), overlapContext.stdlibSurfacePath(),
            overlapContext.pinnedStdlibFiles(),
            overlapContext.projectDeploymentIdentity());
        ModuleClassification overlap = ModuleIdentityResolver.classify(
            overlapContext, Path.of(pinnedPath).toUri().toString());
        check(overlap.issue() == ModuleClassification.Issue.STDLIB_EXTERNAL_OVERLAP
                && overlap.moduleIdentity() == null,
            "the both-match state reports STDLIB_EXTERNAL_OVERLAP with no"
                + " identity published");
    }

    private static void testClassifierPurity() throws Exception {
        System.out.println("-- Classifier purity (no filesystem access, determinism)");
        Path dir = fixture("classifier-purity");
        Files.createDirectories(dir);
        Path realDir = dir.toRealPath();
        // The context's paths need not exist: classification over fabricated
        // URIs is pure and deterministic.
        ProjectContext context = syntheticContext(dir, List.of("src"), Map.of(),
            realDir.resolve("std").toString());
        String fabricated = realDir.resolve("src/alpha/beta.deal").toUri().toString();
        ModuleClassification first = ModuleIdentityResolver.classify(context,
            fabricated);
        ModuleClassification second = ModuleIdentityResolver.classify(context,
            fabricated);
        check(first.equals(second),
            "equal inputs produce equal classification results");
        check(first.moduleIdentity()
                    instanceof CanonicalModuleIdentity.ProjectModule module
                        && module.projectIdentity().configuredRootText().equals("src")
                        && module.projectIdentity().relativeModuleComponents()
                            .equals(List.of("alpha")),
            "fabricated URIs classify without any filesystem existence");

        // Structural purity: the classifier source performs no Files.* call.
        String source = Files.readString(Path.of(
            "deal/module/ModuleIdentityResolver.java"), StandardCharsets.UTF_8);
        check(!source.contains("Files."),
            "the classifier production source performs no filesystem access");
    }

    // =========================================================================
    // Resource-identity carriers
    // =========================================================================

    private static void testCarrierShapes() throws Exception {
        System.out.println("-- Resource-identity carrier shapes");
        Path dir = fixture("carriers");
        ProjectContext context = syntheticContext(dir, List.of("src"), Map.of(),
            null);
        SourceModuleResolver resolver = new SourceModuleResolver(context);
        SourceModuleResolver.ResolveResult resolved =
            resolver.resolve(dir.resolve("src/main.deal").toString(), "missing");
        check(resolved instanceof SourceModuleResolver.ResolveResult.Failure,
            "carrier fixture needs no resolution (sanity)");

        DiagnosticRange range = new DiagnosticRange("mod.deal", 2, 5, 2, 14,
            10, 19, 9, RangeOrigin.SOURCE);
        LexicalDeclarationIdentity lexical = new LexicalDeclarationIdentity(
            LexicalDeclarationIdentity.DeclarationKind.FUNCTION, "f",
            "mod", range);
        check(lexical.declaredName().equals("f")
                && lexical.enclosingLexicalDeclarationPath().equals("mod")
                && lexical.sourceScalarRange().equals(range),
            "LexicalDeclarationIdentity pins the canonical shape");
        SemanticModuleIdentity moduleIdentity = new SemanticModuleIdentity(
            context.projectDeploymentIdentity(), "file:///tmp/example/mod.deal");
        SemanticResourceIdentity resource = new SemanticResourceIdentity(
            moduleIdentity, LexicalDeclarationIdentity.DeclarationKind.FUNCTION,
            lexical);
        check(resource.semanticModuleIdentity().equals(moduleIdentity)
                && resource.resourceKind()
                    == LexicalDeclarationIdentity.DeclarationKind.FUNCTION
                && resource.lexicalDeclarationIdentity().equals(lexical),
            "SemanticResourceIdentity pins the canonical shape");
        RuntimeResourceReference reference = new RuntimeResourceReference(
            RuntimeResourceReference.Kind.IMPORTED_FUNCTION_WRAPPER, resource,
            RuntimeResourceReference.providerContractDigestOf("content"), range);
        check(reference.kind()
                    == RuntimeResourceReference.Kind.IMPORTED_FUNCTION_WRAPPER
                && reference.semanticResourceIdentity().equals(resource)
                && reference.sourceRange().equals(range),
            "RuntimeResourceReference pins the canonical shape");

        // Immutability of the identity shapes.
        List<String> mutableComponents = new ArrayList<>(List.of("a"));
        ProjectModuleIdentity identity = new ProjectModuleIdentity("src",
            "/tmp/src", mutableComponents);
        mutableComponents.add("b");
        check(identity.relativeModuleComponents().equals(List.of("a")),
            "ProjectModuleIdentity defensively copies its components list");
    }

    // =========================================================================
    // Independent formula helpers (the tests pin the digest inputs)
    // =========================================================================

    /** Independent length-prefixed UTF-8 serialization (the pinned framing). */
    private static byte[] lengthPrefixed(String value) {
        byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
        byte[] framed = new byte[8 + utf8.length];
        ByteBuffer.wrap(framed).putLong(utf8.length);
        System.arraycopy(utf8, 0, framed, 8, utf8.length);
        return framed;
    }

    /** Independent SHA-256 hex (lowercase). */
    private static String sha256Hex(byte[] bytes) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        byte[] hash = digest.digest(bytes);
        StringBuilder hex = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16));
            hex.append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }

    /** The pinned deploymentModuleId formula, independently recomputed. */
    private static String expectedDeploymentModuleId(String deploymentDigest,
                                                     String canonicalUri) {
        byte[] digestPart = lengthPrefixed(deploymentDigest);
        byte[] uriPart = lengthPrefixed(canonicalUri);
        byte[] combined = new byte[digestPart.length + uriPart.length];
        System.arraycopy(digestPart, 0, combined, 0, digestPart.length);
        System.arraycopy(uriPart, 0, combined, digestPart.length, uriPart.length);
        return "m" + sha256Hex(combined).substring(0, 16);
    }

    /** Describes a resolution result for failure messages. */
    private static String describe(SourceModuleResolver.ResolveResult result) {
        if (result instanceof SourceModuleResolver.ResolveResult.Failure failure) {
            return failure.diagnostic().code() + ": "
                + failure.diagnostic().message();
        }
        return "resolved";
    }
}
