package deal.module;

import deal.project.CliOverrides;
import deal.project.ConfiguredModuleRoot;
import deal.project.ExternalEntry;
import deal.project.NormalizedDeclarationPath;
import deal.project.OutputConfigResolver;
import deal.project.ProjectContext;
import deal.project.ProjectDeploymentIdentity;
import deal.project.ProjectLocator;
import deal.project.ProtectedPathOps;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The test battery for {@link ModuleIdentityResolver}'s pure classifier
 * and representability predicates (ISSUE-0266 T5, design source
 * {@code strict-project-context-resolution-identity} D6, verification
 * items 5–6 plus the combined T1+T4 dependency gate):
 *
 * <ul>
 *   <li>Shapes: {@link ProjectModuleIdentity} immutability and field
 *       semantics; the three {@link CanonicalModuleIdentity} forms with
 *       structural value equality; the
 *       {@link ModuleIdentityResolver.ModuleClassification} provenance
 *       invariant.</li>
 *   <li>Containment (fabricated, no filesystem): roots {@code src},
 *       {@code lib}, {@code lib/utils} — sources at each depth select
 *       the longest contained root; a file at {@code src/lib/x.deal}
 *       selects {@code src/lib} under nested roots; a file outside all
 *       roots selects none; component-wise prefixing (no
 *       {@code srcx}-style false containment); a symlink target outside
 *       its lexical root is not contained; exact
 *       {@code relativeModuleComponents}.</li>
 *   <li>Most-specific selection and the defensive equal-root tie:
 *       strictly maximal containment wins; two contained roots with
 *       equal containment report
 *       {@link ModuleIdentityResolver.Issue#EQUAL_ROOT_TIE} and publish
 *       no identity (never a silent pick).</li>
 *   <li>Classification precedence: pinned stdlib file → BuiltinModule;
 *       absent surface → never builtin; same-named {@code .d.deal}
 *       elsewhere → none; externals declaration URI →
 *       ExternalModule(that key) even under a configured root; rooted
 *       {@code .deal} → ProjectModule; rooted non-externals
 *       {@code .d.deal} → none; relative non-externals {@code .d.deal}
 *       → none; non-spec-listed {@code .d.deal} inside the std
 *       directory → none; spelling-independence at the canonical-URI
 *       level; the both-match state reports
 *       {@link ModuleIdentityResolver.Issue#STDLIB_EXTERNAL_OVERLAP}
 *       and publishes no identity.</li>
 *   <li>Purity: classification over fabricated URIs whose context paths
 *       do not exist (no filesystem access, results derived lexically);
 *       equal inputs → equal results; non-{@code file:} and malformed
 *       URIs classify as none without throwing.</li>
 *   <li>Representability: every pinned allowed and forbidden character
 *       class per component; {@code %}, non-reserved {@code $}, and
 *       backslash valid; {@code $external}/{@code $builtin} reserved as
 *       exact first components of a root only; contiguous {@code ->};
 *       {@code .}/{@code ..}/empty components; identifier-shaped class
 *       names per the lexer grammar
 *       {@code [a-zA-Z_$][a-zA-Z0-9_$]*}.</li>
 *   <li>Combined T1+T4 gate: a real temp deployment located through
 *       {@link ProjectLocator} with roots {@code src}, {@code lib},
 *       {@code lib/utils}, an externals entry, and a project-local
 *       {@code std/} — real resolved files classified per every rule,
 *       a symlink-outside-root source receiving no project identity,
 *       disjointness of the externals and stdlib predicates across the
 *       published context, and the overlap fixture rejected at locate
 *       (E2010, T4 step 4(b)) before the classifier ever sees it.</li>
 * </ul>
 *
 * <p>Runs via main() using the repository's plain check()-helper
 * convention; exits non-zero on failure.</p>
 */
public final class ModuleIdentityResolverTest {

    private ModuleIdentityResolverTest() {
    }

    private static int passed = 0;
    private static int failed = 0;
    private static Path tmpDir;
    /** {@code tmpDir.toRealPath()} — the symlink-resolved expectation base. */
    private static Path realTmp;
    /** True when no ancestor of realTmp has a deal.json. */
    private static boolean environmentClean = true;

    // =========================================================================
    // Test runner
    // =========================================================================

    public static void main(String[] args) throws Exception {
        tmpDir = Files.createTempDirectory("deal-identity-classifier-");
        try {
            realTmp = tmpDir.toRealPath();
            environmentClean = !ancestorsHaveDealJson(realTmp);
            if (!environmentClean) {
                System.out.println("NOTE: a deal.json exists above the fixture root;"
                    + " the combined locate gate is skipped.");
            }
            testShapes();
            testContainmentSelection();
            testContainmentSymlinkTargetOutsideLexicalRoot();
            testEqualRootTie();
            testClassificationStdlib();
            testClassificationExternals();
            testClassificationProjectAndNone();
            testSpellingIndependence();
            testBothMatchInvariant();
            testPurity();
            testRepresentabilityComponents();
            testRepresentabilityRootText();
            testRepresentabilityRelativeAndExternal();
            testIdentifierShapedClassNames();
            if (environmentClean) {
                testCombinedRealDeployment();
            }
        } finally {
            cleanup();
        }
        System.out.println();
        System.out.println("Passed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.err.println("ModuleIdentityResolverTest FAILED: " + failed
                + " failure(s)");
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

    private static void checkThrowsIllegalArgument(Runnable action, String message) {
        try {
            action.run();
            fail(message + " (no exception thrown)");
        } catch (IllegalArgumentException | NullPointerException expected) {
            passed++;
        }
    }

    // =========================================================================
    // Fabricated-context fixtures (pure, no filesystem state required)
    // =========================================================================

    /** A fabricated validated context over arbitrary (possibly non-existent)
     * paths — the classifier must derive its result purely lexically. */
    private static ProjectContext fabricatedContext(List<ConfiguredModuleRoot> roots,
                                                    Map<String, ExternalEntry> externals,
                                                    String stdlibSurfacePath) {
        return new ProjectContext(
            "/proj/deal.json",
            "/proj",
            "/proj",
            "1.2",
            roots,
            new OutputConfigResolver.OutputRef(OutputConfigResolver.Source.MANIFEST,
                OutputConfigResolver.Kind.MANIFEST_RELATIVE_PATH,
                "build/lua", "/proj/build/lua", null),
            "luajit",
            externals,
            "1.2",
            stdlibSurfacePath,
            new ProjectDeploymentIdentity("file:///proj/deal.json",
                "0".repeat(64)));
    }

    private static ConfiguredModuleRoot root(String configuredText,
                                             String normalizedPath) {
        return new ConfiguredModuleRoot(configuredText, normalizedPath, null);
    }

    private static ExternalEntry externalEntry(String rawImportSpecifier,
                                               String declarationPath) {
        return new ExternalEntry(rawImportSpecifier,
            new NormalizedDeclarationPath(declarationPath, null), null, null);
    }

    /** The file: URI text of an absolute path (no filesystem access). */
    private static String uriOf(String absolutePath) {
        return Path.of(absolutePath).normalize().toUri().toString();
    }

    private static ModuleIdentityResolver.ModuleClassification classify(
            ProjectContext context, String uri) {
        ModuleIdentityResolver.ModuleClassification result =
            ModuleIdentityResolver.classify(context, uri);
        check(result != null, "classify returns a non-null result for " + uri);
        return result;
    }

    private static void checkNone(ModuleIdentityResolver.ModuleClassification result,
                                  String message) {
        check(result.moduleIdentity() == null && result.projectIdentity() == null
                && result.issue() == ModuleIdentityResolver.Issue.NONE,
            message + " (got identity=" + result.moduleIdentity()
                + ", issue=" + result.issue() + ")");
    }

    private static void checkProject(
            ModuleIdentityResolver.ModuleClassification result,
            String configuredRootText, String normalizedRootPath,
            List<String> relativeComponents, String message) {
        check(result.issue() == ModuleIdentityResolver.Issue.NONE,
            message + ": issue NONE, got " + result.issue());
        if (result.moduleIdentity()
                instanceof CanonicalModuleIdentity.ProjectModule project) {
            ProjectModuleIdentity identity = project.projectIdentity();
            check(identity.configuredRootText().equals(configuredRootText),
                message + ": configuredRootText '" + configuredRootText + "', got '"
                    + identity.configuredRootText() + "'");
            check(identity.normalizedRootPath().equals(normalizedRootPath),
                message + ": normalizedRootPath '" + normalizedRootPath + "', got '"
                    + identity.normalizedRootPath() + "'");
            check(identity.relativeModuleComponents().equals(relativeComponents),
                message + ": relativeModuleComponents " + relativeComponents
                    + ", got " + identity.relativeModuleComponents());
            check(result.projectIdentity() != null
                    && result.projectIdentity().equals(identity),
                message + ": projectIdentity provenance equals the ProjectModule"
                    + " identity");
        } else {
            fail(message + ": expected ProjectModule, got " + result.moduleIdentity());
        }
    }

    private static void checkExternal(
            ModuleIdentityResolver.ModuleClassification result,
            String rawImportSpecifier, String message) {
        check(result.issue() == ModuleIdentityResolver.Issue.NONE,
            message + ": issue NONE, got " + result.issue());
        if (result.moduleIdentity()
                instanceof CanonicalModuleIdentity.ExternalModule external) {
            check(external.rawImportSpecifier().equals(rawImportSpecifier),
                message + ": rawImportSpecifier '" + rawImportSpecifier + "', got '"
                    + external.rawImportSpecifier() + "'");
            check(result.projectIdentity() == null,
                message + ": no projectIdentity provenance for an external module");
        } else {
            fail(message + ": expected ExternalModule, got " + result.moduleIdentity());
        }
    }

    private static void checkBuiltin(
            ModuleIdentityResolver.ModuleClassification result, String message) {
        check(result.issue() == ModuleIdentityResolver.Issue.NONE,
            message + ": issue NONE, got " + result.issue());
        check(result.moduleIdentity() instanceof CanonicalModuleIdentity.BuiltinModule,
            message + ": expected BuiltinModule, got " + result.moduleIdentity());
        check(result.projectIdentity() == null,
            message + ": no projectIdentity provenance for a builtin module");
    }

    // =========================================================================
    // Shapes (verification: pinned record shapes)
    // =========================================================================

    private static void testShapes() {
        System.out.println("-- Shapes");

        List<String> mutable = new ArrayList<>(List.of("a", "b"));
        ProjectModuleIdentity identity =
            new ProjectModuleIdentity("src", "/p/src", mutable);
        check(identity.configuredRootText().equals("src"),
            "configuredRootText is the decoded manifest spelling");
        check(identity.normalizedRootPath().equals("/p/src"),
            "normalizedRootPath is the converted root path");
        check(identity.relativeModuleComponents().equals(List.of("a", "b")),
            "relativeModuleComponents carry the directory components");
        mutable.add("c");
        check(identity.relativeModuleComponents().equals(List.of("a", "b")),
            "ProjectModuleIdentity defensively copies the component list");

        check(new ProjectModuleIdentity("src", "/p/src", List.of())
                .equals(new ProjectModuleIdentity("src", "/p/src", List.of())),
            "ProjectModuleIdentity equality is structural");
        checkThrowsIllegalArgument(() ->
                new ProjectModuleIdentity(null, "/p/src", List.of()),
            "ProjectModuleIdentity rejects a null configuredRootText");

        CanonicalModuleIdentity.ProjectModule projectA =
            new CanonicalModuleIdentity.ProjectModule(identity);
        CanonicalModuleIdentity.ProjectModule projectB =
            new CanonicalModuleIdentity.ProjectModule(
                new ProjectModuleIdentity("src", "/p/src", List.of("a", "b")));
        check(projectA.equals(projectB), "ProjectModule equality is structural");
        check(new CanonicalModuleIdentity.ExternalModule("host/x")
                .equals(new CanonicalModuleIdentity.ExternalModule("host/x")),
            "ExternalModule equality is structural");
        check(!new CanonicalModuleIdentity.ExternalModule("host/x")
                .equals(new CanonicalModuleIdentity.ExternalModule("host/y")),
            "ExternalModule distinguishes raw import specifiers");
        check(new CanonicalModuleIdentity.BuiltinModule()
                .equals(new CanonicalModuleIdentity.BuiltinModule()),
            "BuiltinModule equality is structural");
        checkThrowsIllegalArgument(() ->
                new CanonicalModuleIdentity.ExternalModule(null),
            "ExternalModule rejects a null raw import specifier");

        // The classification provenance invariant: projectIdentity is
        // present exactly for the ProjectModule form and must equal it.
        checkThrowsIllegalArgument(() ->
                new ModuleIdentityResolver.ModuleClassification(
                    new CanonicalModuleIdentity.BuiltinModule(), identity,
                    ModuleIdentityResolver.Issue.NONE),
            "ModuleClassification rejects a projectIdentity without ProjectModule");
        checkThrowsIllegalArgument(() ->
                new ModuleIdentityResolver.ModuleClassification(
                    new CanonicalModuleIdentity.ProjectModule(identity),
                    new ProjectModuleIdentity("lib", "/p/lib", List.of()),
                    ModuleIdentityResolver.Issue.NONE),
            "ModuleClassification rejects a mismatched projectIdentity");
        checkThrowsIllegalArgument(() ->
                new ModuleIdentityResolver.ModuleClassification(null, null, null),
            "ModuleClassification rejects a null issue");
    }

    // =========================================================================
    // Containment and most-specific selection (fabricated paths)
    // =========================================================================

    private static void testContainmentSelection() {
        System.out.println("-- Containment and most-specific selection");

        // The pinned roots src, lib, lib/utils (exact names and paths).
        ProjectContext context = fabricatedContext(List.of(
                root("src", "/p/src"),
                root("lib", "/p/lib"),
                root("lib/utils", "/p/lib/utils")),
            Map.of(), null);

        checkProject(classify(context, uriOf("/p/src/x.deal")),
            "src", "/p/src", List.of(),
            "src/x.deal selects src with empty relative components");
        checkProject(classify(context, uriOf("/p/src/lib/x.deal")),
            "src", "/p/src", List.of("lib"),
            "src/lib/x.deal is contained by src only (lib is a sibling root)");
        checkProject(classify(context, uriOf("/p/src/a/b/x.deal")),
            "src", "/p/src", List.of("a", "b"),
            "relativeModuleComponents are the exact directory components");
        checkProject(classify(context, uriOf("/p/lib/x.deal")),
            "lib", "/p/lib", List.of(),
            "lib/x.deal selects lib");
        checkProject(classify(context, uriOf("/p/lib/utils/y.deal")),
            "lib/utils", "/p/lib/utils", List.of(),
            "lib/utils/y.deal selects the most-specific root lib/utils");
        checkProject(classify(context, uriOf("/p/lib/utils/deep/y.deal")),
            "lib/utils", "/p/lib/utils", List.of("deep"),
            "lib/utils/deep/y.deal selects lib/utils with component deep");
        checkNone(classify(context, uriOf("/p/other/x.deal")),
            "a file outside all roots selects none");
        checkNone(classify(context, uriOf("/p/srcx/x.deal")),
            "component-wise prefixing: srcx is not contained by src");
        checkNone(classify(context, uriOf("/p/x.deal")),
            "a file above all roots selects none");
        checkNone(classify(context, uriOf("/p/src/x.d.deal")),
            "a rooted non-externals .d.deal selects none");

        // Nested roots: a file at src/lib/x.deal selects the most
        // specific root src/lib (strictly maximal containment).
        ProjectContext nested = fabricatedContext(List.of(
                root("src", "/p/src"),
                root("src/lib", "/p/src/lib")),
            Map.of(), null);
        checkProject(classify(nested, uriOf("/p/src/lib/x.deal")),
            "src/lib", "/p/src/lib", List.of(),
            "src/lib/x.deal selects the most-specific nested root src/lib");
        checkProject(classify(nested, uriOf("/p/src/x.deal")),
            "src", "/p/src", List.of(),
            "src/x.deal selects src when no more specific root contains it");

        // A directory path (no .deal suffix) is never a .deal source.
        checkNone(classify(nested, uriOf("/p/src")),
            "a directory path is never a .deal source");

        // The pinned containment rule names the equality case: a source
        // whose canonical URI path equals the root's normalized path is
        // contained. Defensive — unreachable in filesystem reality for a
        // directory root — but exercised with a root whose path itself
        // carries the .deal suffix.
        ProjectContext equality = fabricatedContext(List.of(
                root("x.deal", "/p/x.deal")),
            Map.of(), null);
        checkProject(classify(equality, uriOf("/p/x.deal")),
            "x.deal", "/p/x.deal", List.of(),
            "equality with the root path is containment (defensive)");
    }

    private static void testContainmentSymlinkTargetOutsideLexicalRoot() {
        System.out.println("-- Containment: symlink target outside the lexical root");

        ProjectContext context = fabricatedContext(List.of(
                root("src", "/p/src")),
            Map.of(), null);
        // The canonical URI is the symlink-resolved target, which lies
        // outside the root prefix: not contained, no project identity.
        checkNone(classify(context, uriOf("/p/elsewhere/x.deal")),
            "a source whose resolved URI lies outside its lexical root"
                + " selects none");
    }

    private static void testEqualRootTie() {
        System.out.println("-- Defensive equal-root tie");

        // Two distinct configured roots with equal containment — for a
        // valid manifest this means duplicate normalized roots, already
        // E2010 at locate; the classifier reports the tie and never
        // silently picks one.
        ProjectContext context = fabricatedContext(List.of(
                root("src", "/p/src"),
                root("src-alias", "/p/src")),
            Map.of(), null);
        ModuleIdentityResolver.ModuleClassification result =
            classify(context, uriOf("/p/src/x.deal"));
        check(result.moduleIdentity() == null && result.projectIdentity() == null,
            "an equal-root tie publishes no project identity");
        check(result.issue() == ModuleIdentityResolver.Issue.EQUAL_ROOT_TIE,
            "an equal-root tie is reported through the result, got "
                + result.issue());

        // A strictly more specific root is not a tie.
        ModuleIdentityResolver.ModuleClassification specific = classify(
            fabricatedContext(List.of(
                root("src", "/p/src"),
                root("src/lib", "/p/src/lib")),
                Map.of(), null),
            uriOf("/p/src/lib/x.deal"));
        check(specific.issue() == ModuleIdentityResolver.Issue.NONE
                && specific.moduleIdentity() != null,
            "strictly maximal containment is not a tie");
    }

    // =========================================================================
    // Classification precedence (fabricated paths)
    // =========================================================================

    private static void testClassificationStdlib() {
        System.out.println("-- Classification: stdlib predicate");

        ProjectContext context = fabricatedContext(List.of(),
            Map.of(), "/p/proj/std");

        checkBuiltin(classify(context, uriOf("/p/proj/std/console.d.deal")),
            "the pinned std/console.d.deal under the surface is BuiltinModule");
        for (String module : List.of("string", "table", "json", "math", "time")) {
            checkBuiltin(classify(context, uriOf("/p/proj/std/" + module + ".d.deal")),
                "the pinned std/" + module + ".d.deal is BuiltinModule");
        }
        checkNone(classify(context, uriOf("/p/proj/std/io.d.deal")),
            "a non-spec-listed .d.deal inside the std directory selects none");
        checkNone(classify(context, uriOf("/p/other/console.d.deal")),
            "a same-named .d.deal in any other directory is not builtin");
        checkNone(classify(context, uriOf("/p/langdist/std/console.d.deal")),
            "a language-distribution file outside the pinned surface is not"
                + " builtin");

        // Absent surface: no source carries BuiltinModule.
        ProjectContext absent = fabricatedContext(List.of(), Map.of(), null);
        checkNone(classify(absent, uriOf("/p/proj/std/console.d.deal")),
            "an absent stdlib surface carries no BuiltinModule");

        // A pinned stdlib file whose directory is the pinned surface of a
        // different context is not builtin for that context.
        ProjectContext otherSurface = fabricatedContext(List.of(),
            Map.of(), "/p/otherstd");
        checkNone(classify(otherSurface, uriOf("/p/proj/std/console.d.deal")),
            "the stdlib predicate is fixed by the pinned surface");
    }

    private static void testClassificationExternals() {
        System.out.println("-- Classification: externals predicate");

        Map<String, ExternalEntry> externals = new LinkedHashMap<>();
        externals.put("host/extra", externalEntry("host/extra", "/p/decl/extra.d.deal"));
        externals.put("solo", externalEntry("solo", "/p/decl/solo.d.deal"));
        ProjectContext context = fabricatedContext(List.of(), externals, null);

        checkExternal(classify(context, uriOf("/p/decl/extra.d.deal")),
            "host/extra", "an externals declaration URI carries that entry's key");
        checkExternal(classify(context, uriOf("/p/decl/solo.d.deal")),
            "solo", "a single-component externals key is carried as written");
        checkNone(classify(context, uriOf("/p/decl/other.d.deal")),
            "a .d.deal matching no externals entry selects none");
        checkNone(classify(context, uriOf("/p/decl/extra.deal")),
            "a .deal file with a similar name is not an externals declaration");

        // Externals classification precedes project containment: a
        // declaration file lying under a configured root still carries
        // ExternalModule (D6 (2) before (3)).
        ProjectContext rooted = fabricatedContext(List.of(
                root("src", "/p/src")),
            Map.of("host/under", externalEntry("host/under", "/p/src/under.d.deal")),
            null);
        checkExternal(classify(rooted, uriOf("/p/src/under.d.deal")),
            "host/under",
            "an externals declaration under a configured root carries"
                + " ExternalModule, never ProjectModule");
    }

    private static void testClassificationProjectAndNone() {
        System.out.println("-- Classification: project form and none");

        ProjectContext context = fabricatedContext(List.of(
                root("src", "/p/src")),
            Map.of(), null);

        checkProject(classify(context, uriOf("/p/src/a/x.deal")),
            "src", "/p/src", List.of("a"),
            "a rooted .deal source is a ProjectModule");
        checkNone(classify(context, uriOf("/p/out/x.deal")),
            "a relative out-of-root .deal source selects none");
        checkNone(classify(context, uriOf("/p/src/x.d.deal")),
            "a rooted non-externals .d.deal selects none");
        checkNone(classify(context, uriOf("/p/out/x.d.deal")),
            "a relative .d.deal matching no entry selects none");
        checkNone(classify(context, uriOf("/p/src/x.deal.js")),
            "a non-.deal file selects none");
        checkNone(classify(context, uriOf("/p/src/noextension")),
            "a file without the .deal suffix selects none");
        checkProject(classify(context, uriOf("/p/src/.deal")),
            "src", "/p/src", List.of(),
            "the .deal suffix rule applies literally to a file named .deal");

        // A .d.deal does not match the .deal project form (suffix rule:
        // .deal only, never .d.deal).
        checkNone(classify(context, uriOf("/p/src/x.d.deal")),
            "the project form never applies to a .d.deal source");
    }

    private static void testSpellingIndependence() {
        System.out.println("-- Spelling-independence at the canonical-URI level");

        // The classifier consumes only canonical resolved-source URIs:
        // every import spelling of one file (relative, bare, symlinked)
        // is collapsed to one URI by the resolver, so equal URIs always
        // classify identically. Two different lexically-spelled URIs of
        // distinct files classify independently.
        ProjectContext context = fabricatedContext(List.of(),
            Map.of("host/extra", externalEntry("host/extra", "/p/decl/extra.d.deal")),
            "/p/proj/std");

        ModuleIdentityResolver.ModuleClassification first =
            classify(context, uriOf("/p/decl/extra.d.deal"));
        ModuleIdentityResolver.ModuleClassification second =
            classify(context, uriOf("/p/decl/extra.d.deal"));
        check(first.equals(second) && second.issue()
                == ModuleIdentityResolver.Issue.NONE
                && second.moduleIdentity()
                    instanceof CanonicalModuleIdentity.ExternalModule,
            "equal canonical URIs yield equal classifications");

        ModuleIdentityResolver.ModuleClassification stdlib =
            classify(context, uriOf("/p/proj/std/console.d.deal"));
        check(stdlib.moduleIdentity() instanceof CanonicalModuleIdentity.BuiltinModule,
            "the stdlib spelling classifies BuiltinModule");
        ModuleIdentityResolver.ModuleClassification sameUriAgain =
            classify(context, uriOf("/p/proj/std/console.d.deal"));
        check(stdlib.equals(sameUriAgain),
            "the same canonical stdlib file classifies identically again"
                + " (spelling-independence)");
    }

    private static void testBothMatchInvariant() {
        System.out.println("-- Both-match invariant violation (unreachable for valid"
            + " contexts)");

        // A fabricated context violating T4's stdlib-overlap rejection:
        // an externals declaration naming a pinned stdlib file. The
        // classifier implements the fixed order and reports the state as
        // an internal invariant violation — never silently picks a form.
        Map<String, ExternalEntry> externals = new LinkedHashMap<>();
        externals.put("bad/console",
            externalEntry("bad/console", "/p/proj/std/console.d.deal"));
        ProjectContext context = fabricatedContext(List.of(), externals,
            "/p/proj/std");
        ModuleIdentityResolver.ModuleClassification result =
            classify(context, uriOf("/p/proj/std/console.d.deal"));
        check(result.issue() == ModuleIdentityResolver.Issue.STDLIB_EXTERNAL_OVERLAP,
            "the both-match state reports STDLIB_EXTERNAL_OVERLAP, got "
                + result.issue());
        check(result.moduleIdentity() == null && result.projectIdentity() == null,
            "the both-match state publishes no module identity");
    }

    private static void testPurity() {
        System.out.println("-- Purity (no filesystem access; fabricated URIs over"
            + " non-existent paths)");

        // Every path in this context does not exist on disk: a correct
        // purely lexical classifier still derives the expected result,
        // proving no filesystem access.
        ProjectContext context = fabricatedContext(List.of(
                root("src", "/no-such-deal-dir/proj/src"),
                root("lib", "/no-such-deal-dir/proj/lib")),
            Map.of("host/x",
                externalEntry("host/x", "/no-such-deal-dir/proj/decl/x.d.deal")),
            "/no-such-deal-dir/proj/std");

        ModuleIdentityResolver.ModuleClassification rooted = classify(context,
            uriOf("/no-such-deal-dir/proj/src/a/b.deal"));
        check(rooted.moduleIdentity() instanceof CanonicalModuleIdentity.ProjectModule
                && ((CanonicalModuleIdentity.ProjectModule) rooted.moduleIdentity())
                    .projectIdentity().relativeModuleComponents().equals(List.of("a")),
            "classification over non-existent paths derives the project form"
                + " lexically");
        ModuleIdentityResolver.ModuleClassification external = classify(context,
            uriOf("/no-such-deal-dir/proj/decl/x.d.deal"));
        check(external.moduleIdentity()
                instanceof CanonicalModuleIdentity.ExternalModule,
            "classification over non-existent paths derives the externals form"
                + " lexically");
        ModuleIdentityResolver.ModuleClassification builtin = classify(context,
            uriOf("/no-such-deal-dir/proj/std/console.d.deal"));
        check(builtin.moduleIdentity() instanceof CanonicalModuleIdentity.BuiltinModule,
            "classification over non-existent paths derives the stdlib form"
                + " lexically");

        // Equal inputs → equal results (record equality), and two equal
        // but distinct context instances yield equal results too.
        ProjectContext equalContext = fabricatedContext(List.of(
                root("src", "/no-such-deal-dir/proj/src"),
                root("lib", "/no-such-deal-dir/proj/lib")),
            Map.of("host/x",
                externalEntry("host/x", "/no-such-deal-dir/proj/decl/x.d.deal")),
            "/no-such-deal-dir/proj/std");
        check(context.equals(equalContext),
            "fixture: two fabricated contexts with equal inputs are equal");
        check(classify(context, uriOf("/no-such-deal-dir/proj/src/a/b.deal"))
                .equals(classify(equalContext,
                    uriOf("/no-such-deal-dir/proj/src/a/b.deal"))),
            "equal (context, URI) inputs produce equal results");

        // Defensive totality: non-file URIs and unmaterializable paths
        // classify as none without throwing.
        checkNone(classify(context, "http://host/x.deal"),
            "a non-file URI classifies as none");
        checkNone(classify(context, "not a uri"),
            "a malformed URI classifies as none");
        checkNone(classify(context, "file:///%00"),
            "an unmaterializable file URI classifies as none");
        checkNone(classify(context, "file:///no-such-deal-dir/proj/x.deal"),
            "a non-existent source still classifies by pure path rules");

        // Defensive totality for inputs outside the pinned domain: a null
        // root path or a null declaration path is skipped, never a crash.
        ProjectContext nullRootPath = fabricatedContext(List.of(
                new ConfiguredModuleRoot("src", null, null)),
            Map.of(), null);
        checkNone(classify(nullRootPath, uriOf("/p/src/x.deal")),
            "a root with a null normalized path is skipped, never a crash");
        Map<String, ExternalEntry> nullDeclaration = new LinkedHashMap<>();
        nullDeclaration.put("host/x",
            new ExternalEntry("host/x",
                new NormalizedDeclarationPath(null, null), null, null));
        checkNone(classify(
                fabricatedContext(List.of(), nullDeclaration, null),
                uriOf("/p/x.d.deal")),
            "an externals entry with a null declaration path is skipped,"
                + " never a crash");
    }

    // =========================================================================
    // Representability predicates
    // =========================================================================

    private static void testRepresentabilityComponents() {
        System.out.println("-- Representability: per-component character classes");

        // Allowed: ordinary text, %, non-reserved $, Linux backslash,
        // Unicode letters, supplementary-plane scalars.
        for (String allowed : new String[]{
                "a", "src", "a-b", "a.b",
                "%", "a%b", "$", "$x", "a$b", "x$", "$externalx", "$builtinx",
                "a\\b", "\\", "caf\u00e9", "a\uD83D\uDE00b"}) {
            check(ModuleIdentityResolver.isRepresentableDescriptorComponent(allowed),
                "allowed component '" + allowed + "' is representable");
        }

        // Forbidden: empty, . , .. , NUL, C0 controls, DEL, Unicode
        // whitespace, metacharacters, contiguous ->.
        for (String forbidden : new String[]{
                "", ".", "..", "a\u0000b", "a\u0001b", "a\u001Fb", "a\u007Fb",
                "a b", "a\tb", "a\nb", "a\u00A0b", "a\u1680b", "a\u2028b",
                "a@b", "a[b", "a]b", "a?b", "a(b", "a)b", "a,b", "->", "a->",
                "->b", "a->b", "a->b->c"}) {
            check(!ModuleIdentityResolver.isRepresentableDescriptorComponent(forbidden),
                "forbidden component '" + forbidden + "' is not representable");
        }
        check(!ModuleIdentityResolver.isRepresentableDescriptorComponent(null),
            "a null component is not representable");
    }

    private static void testRepresentabilityRootText() {
        System.out.println("-- Representability: configured root text");

        check(ModuleIdentityResolver.isRepresentableConfiguredRootText("src"),
            "a plain root text is representable");
        check(ModuleIdentityResolver.isRepresentableConfiguredRootText("lib/utils"),
            "a multi-component root text is representable");
        check(ModuleIdentityResolver.isRepresentableConfiguredRootText("a%b/$x"),
            "%, non-reserved $ components are representable");
        check(ModuleIdentityResolver.isRepresentableConfiguredRootText("a\\b/c"),
            "a Linux backslash remains byte-identical and valid");
        check(ModuleIdentityResolver.isRepresentableConfiguredRootText("$externalx"),
            "$externalx is not the reserved exact first component");

        check(!ModuleIdentityResolver.isRepresentableConfiguredRootText(null),
            "a null root text is not representable");
        check(!ModuleIdentityResolver.isRepresentableConfiguredRootText(""),
            "an empty root text is not representable");
        check(!ModuleIdentityResolver.isRepresentableConfiguredRootText("/src"),
            "a leading slash produces an empty first component");
        check(!ModuleIdentityResolver.isRepresentableConfiguredRootText("src/"),
            "a trailing slash produces an empty last component");
        check(!ModuleIdentityResolver.isRepresentableConfiguredRootText("src//lib"),
            "an empty interior component is not representable");
        check(!ModuleIdentityResolver.isRepresentableConfiguredRootText("src/./lib"),
            "a . component is not representable");
        check(!ModuleIdentityResolver.isRepresentableConfiguredRootText("src/../lib"),
            "a .. component is not representable");
        check(!ModuleIdentityResolver.isRepresentableConfiguredRootText("$external"),
            "$external as the exact first component is reserved");
        check(!ModuleIdentityResolver.isRepresentableConfiguredRootText("$builtin"),
            "$builtin as the exact first component is reserved");
        check(!ModuleIdentityResolver.isRepresentableConfiguredRootText(
                "$external/lib"),
            "$external as the first component of a multi-component root is"
                + " reserved");
        check(!ModuleIdentityResolver.isRepresentableConfiguredRootText("src->x"),
            "contiguous -> inside a root component is not representable");
        check(!ModuleIdentityResolver.isRepresentableConfiguredRootText("a@b/c"),
            "a metacharacter inside a root component is not representable");

        // The reservation is exact-first-component only: a later
        // component may be named $external.
        check(ModuleIdentityResolver.isRepresentableConfiguredRootText(
                "src/$external"),
            "the reservation applies only to the exact first component");
    }

    private static void testRepresentabilityRelativeAndExternal() {
        System.out.println("-- Representability: relative module path and externals"
            + " specifier");

        check(ModuleIdentityResolver.isRepresentableRelativeModuleComponents(
                List.of()),
            "an empty relative module path (file directly in its root) is"
                + " representable");
        check(ModuleIdentityResolver.isRepresentableRelativeModuleComponents(
                List.of("a", "b")),
            "ordinary relative components are representable");
        check(ModuleIdentityResolver.isRepresentableRelativeModuleComponents(
                List.of("a%b", "$x", "c\\d")),
            "%, non-reserved $, and backslash stay valid in relative components");
        check(ModuleIdentityResolver.isRepresentableRelativeModuleComponents(
                List.of("$external")),
            "the reservation does not apply to relative components (never the"
                + " first descriptor component)");
        check(!ModuleIdentityResolver.isRepresentableRelativeModuleComponents(null),
            "a null relative component list is not representable");
        check(!ModuleIdentityResolver.isRepresentableRelativeModuleComponents(
                List.of("a", "")),
            "an empty relative component is not representable");
        check(!ModuleIdentityResolver.isRepresentableRelativeModuleComponents(
                List.of("a", "..")),
            "a .. relative component is not representable");
        check(!ModuleIdentityResolver.isRepresentableRelativeModuleComponents(
                List.of("a", "x->y")),
            "contiguous -> in a relative component is not representable");

        check(ModuleIdentityResolver.isRepresentableExternalSpecifier("host/x"),
            "an externals raw import specifier is representable");
        check(ModuleIdentityResolver.isRepresentableExternalSpecifier("solo"),
            "a single-component specifier is representable");
        check(ModuleIdentityResolver.isRepresentableExternalSpecifier(
                "host/a%b/$x"),
            "%, non-reserved $ stay valid in externals specifiers");
        check(ModuleIdentityResolver.isRepresentableExternalSpecifier(
                "$builtin/x"),
            "the reservation does not apply to externals specifiers (never the"
                + " first descriptor component)");
        check(!ModuleIdentityResolver.isRepresentableExternalSpecifier(null),
            "a null externals specifier is not representable");
        check(!ModuleIdentityResolver.isRepresentableExternalSpecifier(""),
            "an empty externals specifier is not representable");
        check(!ModuleIdentityResolver.isRepresentableExternalSpecifier("host//x"),
            "an empty externals component is not representable");
        check(!ModuleIdentityResolver.isRepresentableExternalSpecifier("host/./x"),
            "a . externals component is not representable");
        check(!ModuleIdentityResolver.isRepresentableExternalSpecifier("host/../x"),
            "a .. externals component is not representable");
        check(!ModuleIdentityResolver.isRepresentableExternalSpecifier("host/a,b"),
            "a comma in an externals component is not representable");

        ProjectModuleIdentity representable =
            new ProjectModuleIdentity("lib/utils", "/p/lib/utils", List.of("a"));
        check(ModuleIdentityResolver.isRepresentableProjectModuleIdentity(
                representable),
            "a representable project identity is representable");
        ProjectModuleIdentity badRoot =
            new ProjectModuleIdentity("$external", "/p/$external", List.of());
        check(!ModuleIdentityResolver.isRepresentableProjectModuleIdentity(badRoot),
            "a reserved first root component makes the project identity"
                + " unrepresentable");
        ProjectModuleIdentity badComponent =
            new ProjectModuleIdentity("src", "/p/src", List.of("a@b"));
        check(!ModuleIdentityResolver.isRepresentableProjectModuleIdentity(
                badComponent),
            "an unrepresentable relative component makes the project identity"
                + " unrepresentable");
        check(!ModuleIdentityResolver.isRepresentableProjectModuleIdentity(null),
            "a null project identity is not representable");
    }

    private static void testIdentifierShapedClassNames() {
        System.out.println("-- Representability: identifier-shaped class names");

        for (String valid : new String[]{
                "Error", "main", "ClassName", "_private", "$dollar", "a1",
                "A_$1", "class"}) {
            check(ModuleIdentityResolver.isIdentifierShapedClassName(valid),
                "identifier-shaped class name '" + valid + "' is valid");
        }
        for (String invalid : new String[]{
                "", "1a", "a-b", "a.b", "a b", "a\u00e9", "a\uD83D\uDE00",
                "a,b"}) {
            check(!ModuleIdentityResolver.isIdentifierShapedClassName(invalid),
                "class name '" + invalid + "' is not identifier-shaped");
        }
        check(!ModuleIdentityResolver.isIdentifierShapedClassName(null),
            "a null class name is not identifier-shaped");
    }

    // =========================================================================
    // Combined T1+T4 gate: a real located deployment
    // =========================================================================

    private static void testCombinedRealDeployment() throws Exception {
        System.out.println("-- Combined T1+T4: real located project");

        Path dir = Files.createDirectories(tmpDir.resolve("combined"));
        Files.createDirectories(dir.resolve("src/sub"));
        Files.createDirectories(dir.resolve("lib"));
        Files.createDirectories(dir.resolve("lib/utils/deep"));
        Files.createDirectories(dir.resolve("decl"));
        Files.createDirectories(dir.resolve("std"));
        Path outside = Files.createDirectories(tmpDir.resolve("combined-outside"));

        writeText(dir.resolve("main.deal"), "export function main(): null { }\n");
        writeText(dir.resolve("src/rooted.deal"), "export function f(): null { }\n");
        writeText(dir.resolve("src/sub/thing.deal"), "export function g(): null { }\n");
        writeText(dir.resolve("src/lib/nested.deal"), "export function h(): null { }\n");
        writeText(dir.resolve("lib/other.deal"), "export function i(): null { }\n");
        writeText(dir.resolve("lib/utils/deep/util.deal"),
            "export function j(): null { }\n");
        writeText(dir.resolve("lib/hostonly.d.deal"),
            "export function hostFn(x: int): int;\n");
        writeText(dir.resolve("decl/extra.d.deal"),
            "export function extra(x: int): int;\n");
        writeText(dir.resolve("std/console.d.deal"),
            "export function log(s: string): null;\n");
        writeText(dir.resolve("std/string.d.deal"),
            "export function len(s: string): int;\n");
        writeText(dir.resolve("std/table.d.deal"),
            "export function size(t: any): int;\n");
        writeText(dir.resolve("std/json.d.deal"),
            "export function parse(s: string): any;\n");
        writeText(dir.resolve("std/math.d.deal"),
            "export function abs(x: int): int;\n");
        writeText(dir.resolve("std/time.d.deal"),
            "export function now(): int;\n");
        writeText(dir.resolve("std/io.d.deal"), "export function read(): string;\n");
        writeText(outside.resolve("shared.deal"), "export function k(): null { }\n");
        writeText(outside.resolve("shared.d.deal"),
            "export function relDecl(x: int): int;\n");

        // A symlink inside src pointing outside: the resolved URI of
        // src/link/shared.deal lies outside the root.
        Files.createSymbolicLink(dir.resolve("src/link"), outside);

        String manifest = "{\n  \"languageVersion\": \"1.2\",\n"
            + "  \"moduleRoots\": [\"src\", \"lib\", \"lib/utils\"],\n"
            + "  \"externals\": {\n"
            + "    \"host/extra\": { \"declaration\": \"decl/extra.d.deal\" }\n"
            + "  }\n}\n";
        writeText(dir.resolve("deal.json"), manifest);

        ProjectLocator.LocateResult located =
            ProjectLocator.locate(dir.resolve("main.deal").toString(), null);
        if (located.context() == null) {
            fail("combined locate should succeed: " + describeFailure(located));
            return;
        }
        ProjectContext context = located.context();
        check(context.configuredModuleRoots().size() == 3,
            "combined context publishes three configured roots");
        check(context.stdlibSurfacePath() != null
                && context.stdlibSurfacePath().equals(
                    dir.resolve("std").toRealPath().toString()),
            "combined context pins the project-local std surface");

        // Real resolved files: canonical URIs via the T1 protected
        // boundary (full symlink resolution), classified by rule.
        ModuleIdentityResolver.ModuleClassification rootedMain =
            classify(context, canonicalUriOf(dir.resolve("src/rooted.deal")));
        checkProject(rootedMain, "src", rootReal(dir.resolve("src")), List.of(),
            "real src/rooted.deal selects src");
        checkProject(classify(context, canonicalUriOf(dir.resolve("src/sub/thing.deal"))),
            "src", rootReal(dir.resolve("src")), List.of("sub"),
            "real src/sub/thing.deal selects src with component sub");
        checkProject(classify(context, canonicalUriOf(dir.resolve("src/lib/nested.deal"))),
            "src", rootReal(dir.resolve("src")), List.of("lib"),
            "real src/lib/nested.deal is contained by src only");
        checkProject(classify(context, canonicalUriOf(dir.resolve("lib/other.deal"))),
            "lib", rootReal(dir.resolve("lib")), List.of(),
            "real lib/other.deal selects lib");
        checkProject(classify(context,
                canonicalUriOf(dir.resolve("lib/utils/deep/util.deal"))),
            "lib/utils", rootReal(dir.resolve("lib/utils")), List.of("deep"),
            "real lib/utils/deep/util.deal selects the most-specific root"
                + " lib/utils");

        checkExternal(classify(context,
                canonicalUriOf(dir.resolve("decl/extra.d.deal"))),
            "host/extra", "real externals declaration carries its key");
        checkBuiltin(classify(context,
                canonicalUriOf(dir.resolve("std/console.d.deal"))),
            "real pinned stdlib file is BuiltinModule");
        checkNone(classify(context, canonicalUriOf(dir.resolve("std/io.d.deal"))),
            "real non-spec-listed .d.deal inside std selects none");
        checkNone(classify(context,
                canonicalUriOf(dir.resolve("lib/hostonly.d.deal"))),
            "real rooted non-externals .d.deal selects none");
        checkNone(classify(context, canonicalUriOf(outside.resolve("shared.deal"))),
            "real out-of-root .deal source selects none");
        checkNone(classify(context, canonicalUriOf(outside.resolve("shared.d.deal"))),
            "real relative .d.deal matching no entry selects none");

        // The symlink: the canonical URI of src/link/shared.deal equals
        // the canonical URI of outside/shared.deal and classifies none.
        String throughLink = canonicalUriOf(dir.resolve("src/link/shared.deal"));
        check(throughLink.equals(canonicalUriOf(outside.resolve("shared.deal"))),
            "the symlinked spelling resolves to the outside canonical URI");
        checkNone(classify(context, throughLink),
            "a source whose file symlinks outside its lexical root selects none");

        // Spelling-independence on real files: distinct lexical spellings
        // collapse to one canonical URI and one classification.
        String viaAlternateSpelling =
            canonicalUriOf(dir.resolve("decl/../decl/./extra.d.deal"));
        check(viaAlternateSpelling.equals(
                canonicalUriOf(dir.resolve("decl/extra.d.deal"))),
            "alternate lexical spellings collapse to one canonical URI");
        checkExternal(classify(context, viaAlternateSpelling), "host/extra",
            "an alternate spelling of the externals declaration carries the"
                + " same ExternalModule");

        // Disjointness across the published context: no externals
        // declaration equals a pinned stdlib file, and no classified real
        // file satisfies both predicates.
        List<String> realFiles = List.of(
            canonicalUriOf(dir.resolve("src/rooted.deal")),
            canonicalUriOf(dir.resolve("src/sub/thing.deal")),
            canonicalUriOf(dir.resolve("src/lib/nested.deal")),
            canonicalUriOf(dir.resolve("lib/other.deal")),
            canonicalUriOf(dir.resolve("lib/utils/deep/util.deal")),
            canonicalUriOf(dir.resolve("decl/extra.d.deal")),
            canonicalUriOf(dir.resolve("std/console.d.deal")),
            canonicalUriOf(dir.resolve("std/io.d.deal")),
            canonicalUriOf(dir.resolve("lib/hostonly.d.deal")),
            canonicalUriOf(outside.resolve("shared.deal")),
            canonicalUriOf(outside.resolve("shared.d.deal")),
            throughLink);
        for (String uri : realFiles) {
            ModuleIdentityResolver.ModuleClassification result =
                classify(context, uri);
            check(result.issue() != ModuleIdentityResolver.Issue.STDLIB_EXTERNAL_OVERLAP,
                "no real source hits the both-match invariant: " + uri);
        }
        for (ExternalEntry entry : context.externals().values()) {
            Path declarationReal = Path.of(
                entry.declarationPath().absoluteNormalizedPath()).toRealPath();
            for (String module : ModuleIdentityResolver.SPEC_STDLIB_MODULE_NAMES) {
                Path pinnedReal = dir.resolve("std").resolve(module + ".d.deal")
                    .toRealPath();
                check(!declarationReal.equals(pinnedReal),
                    "externals entry '" + entry.rawImportSpecifier()
                        + "' is disjoint from the pinned stdlib file '"
                        + module + "'");
            }
        }

        // A broken T4: the overlap fixture is rejected at locate (E2010
        // at the declaration value range) before the classifier can ever
        // see the both-match state.
        Path overlapDir = Files.createDirectories(tmpDir.resolve("combined-overlap"));
        writeText(overlapDir.resolve("main.deal"),
            "export function main(): null { }\n");
        writeText(overlapDir.resolve("deal.json"),
            "{\n  \"languageVersion\": \"1.2\",\n"
                + "  \"externals\": {\n"
                + "    \"bad/console\": { \"declaration\": \"std/console.d.deal\" }\n"
                + "  }\n}\n");
        ProjectLocator.LocateResult overlap = ProjectLocator.locate(
            overlapDir.resolve("main.deal").toString(), null);
        check(overlap.e2010() != null && overlap.context() == null,
            "the stdlib-overlap externals entry is rejected at locate (E2010)");
    }

    /** The fully symlink-resolved canonical file: URI text of a real file. */
    private static String canonicalUriOf(Path file) throws IOException {
        ProtectedPathOps.PathResult resolved =
            ProtectedPathOps.canonicalizeExisting(file.toString());
        if (!(resolved instanceof ProtectedPathOps.PathResult.Success success)) {
            fail("canonicalizeExisting should resolve " + file + ": " + resolved);
            return "";
        }
        ProtectedPathOps.UriResult uri = ProtectedPathOps.toFileUri(
            success.resolvedPath());
        if (!(uri instanceof ProtectedPathOps.UriResult.Success uriSuccess)) {
            fail("toFileUri should derive a URI for " + file + ": " + uri);
            return "";
        }
        return uriSuccess.uri().toString();
    }

    private static String rootReal(Path dir) throws IOException {
        return dir.toRealPath().toString();
    }

    private static String describeFailure(ProjectLocator.LocateResult result) {
        if (result.context() != null) {
            return "success";
        }
        if (result.e2010() != null) {
            return "E2010: " + result.e2010().message();
        }
        if (result.cliDiagnostic() != null) {
            return "CliDiagnostic: " + result.cliDiagnostic().message();
        }
        return "<empty>";
    }

    // =========================================================================
    // Fixture helpers
    // =========================================================================

    private static Path writeText(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, text.getBytes(StandardCharsets.UTF_8));
        return file;
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
}
