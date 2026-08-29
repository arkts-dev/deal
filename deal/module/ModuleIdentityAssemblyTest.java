package deal.module;

import deal.ast.ClassDeclaration;
import deal.ast.ExportDeclaration;
import deal.ast.Span;
import deal.ast.StatementNode;
import deal.diagnostics.CompilerDiagnostic;
import deal.diagnostics.DiagnosticNote;
import deal.diagnostics.RangeOrigin;
import deal.identity.CanonicalClassIdentity;
import deal.identity.CanonicalClassIdentityIndex;
import deal.identity.CanonicalModuleIdentity;
import deal.identity.ProjectModuleIdentity;
import deal.lexer.Lexer;
import deal.lexer.LexResult;
import deal.parser.Parser;
import deal.parser.ParseResult;
import deal.project.ConfiguredModuleRoot;
import deal.project.ExternalEntry;
import deal.project.NormalizedDeclarationPath;
import deal.project.OutputConfigResolver;
import deal.project.ProjectContext;
import deal.project.ProjectDeploymentIdentity;
import deal.project.ProjectLocator;
import deal.source.SourceScalarRange;

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
 * The test battery for {@link ModuleIdentityAssembly} (ISSUE-0268 T7,
 * design source {@code strict-project-context-resolution-identity} D6,
 * {@code deal-v1.2-int32-and-bytes-architecture} D6/D7, verification
 * items 5–7 of the identity epic plus the combined T1+T4+T6 dependency
 * gate):
 *
 * <ul>
 *   <li>Intrinsic {@code Error} synthesis: with no resolved source, the
 *       assembly synthesizes {@code CanonicalClassIdentity(BuiltinModule,
 *       "Error")} projecting {@code @$builtin/Error}, registered in the
 *       index both ways.</li>
 *   <li>Project form over real resolved sources (locate + T6): roots
 *       {@code src}, {@code lib}, {@code lib/utils} project distinct
 *       descriptor texts per most-specific root; the empty relative
 *       module path projects {@code @<rootText>/<ClassName>}; the
 *       index is keyed by {@code (moduleIdentity, className)} and by
 *       byte-for-byte descriptor text.</li>
 *   <li>Unrepresentable project identities: reserved
 *       {@code $external} first component, descriptor metacharacters,
 *       whitespace, contiguous {@code ->}, {@code .}/{@code ..}
 *       components, and unrepresentable relative path components are
 *       E2010 at the class-name span when required, while class-free
 *       code in the same sources resolves without error.</li>
 *   <li>Externals form: a class in an externals-listed declaration
 *       reached by externals lookup, bare root search, and relative
 *       import all project {@code @$external/<rawImportSpecifier>/<ClassName>}
 *       through one memoized location; an unrepresentable specifier is
 *       E2010 when required with the externals-entry note carrying the
 *       entry's manifest value range; the canonical declaration file is
 *       never a pinned stdlib file (T4-validated contexts).</li>
 *   <li>Builtin form: a required builtin class identity other than
 *       {@code Error} is E2010 when required with the missing-projection
 *       note; a required builtin identity named {@code Error} yields the
 *       intrinsic synthesis; the real stdlib surface is class-free.</li>
 *   <li>Unconditional rule (d): a class in an out-of-root relative
 *       source, in a rooted non-externals {@code .d.deal}, and in a
 *       non-spec-listed {@code .d.deal} inside the std directory each
 *       produce E2010 at the class-name span at the declaration (and
 *       identically when required) with no index registration, while
 *       class-free variants produce no identity demand and no error.</li>
 *   <li>Defensive states: the equal-root tie and the stdlib/externals
 *       overlap are E2010 only when identity is required.</li>
 *   <li>Diagnostic ranges: every eligibility E2010 carries a complete
 *       SOURCE scalar range at the class declaration span.</li>
 *   <li>Privacy: no diagnostic message or projection contains a private
 *       {@code file:} identity URI.</li>
 *   <li>Determinism and idempotence: identical
 *       {@code (ProjectContext, resolved-source stream)} inputs produce
 *       identical identities and index contents; one registered instance
 *       per identity.</li>
 *   <li>Combined T1+T4+T6 chain on a temp deployment: locate, resolve,
 *       then the assembly gates — the unconditional out-of-root E2010,
 *       the externals projection, and the {@code Error} synthesis.</li>
 * </ul>
 *
 * <p>Runs via main() using the repository's plain check()-helper
 * convention; exits non-zero on failure.</p>
 */
public final class ModuleIdentityAssemblyTest {

    private ModuleIdentityAssemblyTest() {
    }

    private static int passed = 0;
    private static int failed = 0;
    private static Path tmpDir;
    /** {@code tmpDir.toRealPath()} — the symlink-resolved expectation base. */
    private static Path realTmp;
    /** True when no ancestor of realTmp has a deal.json. */
    private static boolean environmentClean = true;

    /** Every eligibility diagnostic asserted by this battery (privacy scan). */
    private static final List<CompilerDiagnostic> ALL_DIAGNOSTICS =
        new ArrayList<>();
    /** Every successful projection asserted by this battery (privacy scan). */
    private static final List<String> ALL_DESCRIPTOR_TEXTS = new ArrayList<>();

    /** The shared located main fixture (built lazily). */
    private static MainFixture mainFixture;

    /** One located main fixture: context + resolver + assembly. */
    private record MainFixture(Path dir, ProjectContext context,
                               SourceModuleResolver resolver,
                               ModuleIdentityAssembly assembly) {
    }

    // =========================================================================
    // Test runner
    // =========================================================================

    public static void main(String[] args) throws Exception {
        tmpDir = Files.createTempDirectory("deal-identity-assembly-");
        try {
            realTmp = tmpDir.toRealPath();
            environmentClean = !ancestorsHaveDealJson(realTmp);
            if (!environmentClean) {
                System.out.println("NOTE: a deal.json exists above the fixture root;"
                    + " locate-based assertions are skipped.");
            }
            testIntrinsicErrorSynthesis();
            testDefensiveTieAndOverlap();
            if (environmentClean) {
                testProjectedRootedClasses();
                testIndexContract();
                testUnrepresentableProjectForm();
                testExternalsForm();
                testBuiltinForm();
                testUnconditionalNoIdentity();
                testIdempotenceAndDeterminism();
                testCombinedChain();
            }
            testRealStdlibSurfaceClassFree();
            testPrivacyInvariant();
        } finally {
            cleanup();
        }
        System.out.println();
        System.out.println("Passed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.err.println("ModuleIdentityAssemblyTest FAILED: " + failed
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

    private static void checkThrowsIllegalState(Runnable action, String message) {
        try {
            action.run();
            fail(message + " (no exception thrown)");
        } catch (IllegalStateException expected) {
            passed++;
        }
    }

    /** Asserts the pinned E2010 shape: code, SOURCE origin, file, known
     * non-empty scalar offsets at the class declaration span; records the
     * diagnostic for the privacy scan. */
    private static void checkClassSpanE2010(CompilerDiagnostic diagnostic,
                                            String expectedFile) {
        if (diagnostic == null) {
            fail("expected an E2010 diagnostic, got null");
            return;
        }
        check(diagnostic.code().equals("E2010"),
            "E2010 code, got " + diagnostic.code());
        check("error".equals(diagnostic.severity()),
            "error severity, got " + diagnostic.severity());
        check(diagnostic.range().origin() == RangeOrigin.SOURCE,
            "SOURCE origin, got " + diagnostic.range().origin());
        check(diagnostic.range().file().equals(expectedFile),
            "range file '" + expectedFile + "', got '"
                + diagnostic.range().file() + "'");
        check(diagnostic.range().hasScalarOffsets(),
            "known scalar offsets, got " + diagnostic.range().startScalarOffset()
                + ".." + diagnostic.range().endScalarOffset());
        check(diagnostic.range().scalarLength() > 0,
            "non-empty scalar length, got " + diagnostic.range().scalarLength());
        check(diagnostic.range().scalarLength()
                == diagnostic.range().endScalarOffset()
                    - diagnostic.range().startScalarOffset(),
            "range length equals the offset span");
        recordDiagnostic(diagnostic);
    }

    private static void recordDiagnostic(CompilerDiagnostic diagnostic) {
        ALL_DIAGNOSTICS.add(diagnostic);
    }

    private static void recordDescriptorText(String descriptorText) {
        ALL_DESCRIPTOR_TEXTS.add(descriptorText);
    }

    /** Asserts a declaration-gate E2010 at the class span and returns it. */
    private static CompilerDiagnostic expectDeclarationFailure(
            ModuleIdentityAssembly.DeclarationResult result, String file,
            String message) {
        if (result instanceof ModuleIdentityAssembly.DeclarationResult.Failure f) {
            checkClassSpanE2010(f.diagnostic(), file);
            return f.diagnostic();
        }
        fail(message + " (no failure returned)");
        return null;
    }

    /** Asserts a required-identity-gate E2010 at the class span; returns it. */
    private static CompilerDiagnostic expectRequireFailure(
            ModuleIdentityAssembly.ClassIdentityResult result, String file,
            String message) {
        if (result instanceof ModuleIdentityAssembly.ClassIdentityResult.Failure f) {
            checkClassSpanE2010(f.diagnostic(), file);
            return f.diagnostic();
        }
        fail(message + " (no failure returned)");
        return null;
    }

    /** Asserts a successful identity registration with the pinned text and
     * returns the identity (the single registered instance). */
    private static CanonicalClassIdentity expectIdentity(
            ModuleIdentityAssembly.ClassIdentityResult result,
            String expectedText, String message) {
        if (result instanceof ModuleIdentityAssembly.ClassIdentityResult.Identity id) {
            check(id.descriptorText().equals(expectedText),
                message + ": text '" + expectedText + "', got '"
                    + id.descriptorText() + "'");
            check(id.identity() != null
                    && !id.identity().className().isEmpty(),
                message + ": identity carries a non-empty class name");
            recordDescriptorText(id.descriptorText());
            return id.identity();
        }
        ModuleIdentityAssembly.ClassIdentityResult.Failure f =
            (ModuleIdentityAssembly.ClassIdentityResult.Failure) result;
        fail(message + ": expected Identity, got failure: "
            + f.diagnostic().message());
        return null;
    }

    /** Resolves one specifier through T6 and returns the location. */
    private static SourceModuleLocation resolved(SourceModuleResolver resolver,
                                                 String importerPath,
                                                 String specifier) {
        SourceModuleResolver.ResolveResult result =
            resolver.resolve(importerPath, specifier);
        if (result instanceof SourceModuleResolver.ResolveResult.Resolved r) {
            return r.location();
        }
        fail("resolution failed: " + specifier + " -> "
            + ((SourceModuleResolver.ResolveResult.Failure) result)
                .diagnostic().message());
        return null;
    }

    /** Asserts no registration exists for the identity (failure leaves no
     * index entry). */
    private static void checkNoRegistration(ModuleIdentityAssembly assembly,
                                            CanonicalClassIdentity identity,
                                            String message) {
        checkThrowsIllegalState(() -> assembly.index().descriptorTextFor(identity),
            message);
    }

    // =========================================================================
    // Intrinsic Error synthesis (no resolved source)
    // =========================================================================

    private static void testIntrinsicErrorSynthesis() {
        System.out.println("-- Intrinsic Error synthesis");

        check(ModuleIdentityAssembly.INTRINSIC_ERROR_CLASS_NAME.equals("Error"),
            "the pinned intrinsic class name is Error");
        check(ModuleIdentityAssembly.INTRINSIC_ERROR_DESCRIPTOR_TEXT
                .equals("@$builtin/Error"),
            "the pinned intrinsic projection is @$builtin/Error");

        ModuleIdentityAssembly assembly =
            new ModuleIdentityAssembly(fabricatedContext(List.of(), Map.of(),
                null));
        CanonicalClassIdentity errorIdentity =
            assembly.intrinsicErrorIdentity();
        check(errorIdentity.equals(new CanonicalClassIdentity(
                CanonicalModuleIdentity.BuiltinModule.INSTANCE, "Error")),
            "the synthesis is CanonicalClassIdentity(BuiltinModule, \"Error\")");
        check(assembly.intrinsicErrorIdentity().equals(errorIdentity),
            "repeated synthesis returns an equal identity");

        CanonicalClassIdentityIndex index = assembly.index();
        check(index.descriptorTextFor(errorIdentity)
                .equals("@$builtin/Error"),
            "the index projects @$builtin/Error for the intrinsic identity");
        check(index.identityForDescriptorText("@$builtin/Error")
                .equals(errorIdentity),
            "the index reverse-lookup returns the intrinsic identity");
        check(index.identityForDescriptorText("@$builtin/Error")
                == errorIdentity,
            "the reverse lookup returns the exact registered instance (no"
                + " reconstruction)");

        // Absent-lookup contract: unregistered keys are invariant violations.
        checkThrowsIllegalState(
            () -> index.descriptorTextFor(new CanonicalClassIdentity(
                CanonicalModuleIdentity.BuiltinModule.INSTANCE, "Other")),
            "an unregistered builtin identity is absent from the index");
        checkThrowsIllegalState(
            () -> index.identityForDescriptorText("@$builtin/Other"),
            "an unregistered descriptor text is absent from the index");
        checkThrowsIllegalState(
            () -> index.identityForDescriptorText("@$builtin/Error "),
            "byte-for-byte text keys: a trailing space is absent");

        recordDescriptorText("@$builtin/Error");
    }

    // =========================================================================
    // Defensive tie and overlap states (fabricated contexts + real files)
    // =========================================================================

    private static void testDefensiveTieAndOverlap() throws Exception {
        System.out.println("-- Defensive equal-root tie and stdlib/externals overlap");

        // Tie: two distinct configured roots with equal normalized paths
        // (unreachable via locate: duplicate normalized roots are E2010 at
        // locate). T6 publishes the location with no classification; the
        // assembly re-derives the tie purely and fails only when required.
        Path tieDir = Files.createDirectories(tmpDir.resolve("tie"));
        Files.createDirectories(tieDir.resolve("src"));
        writeText(tieDir.resolve("src/Thing.deal"),
            "export class Thing { n: int; }\n");
        writeText(tieDir.resolve("src/free.deal"),
            "export function free(): null {}\n");
        String srcReal = tieDir.resolve("src").toRealPath().toString();
        ProjectContext tieContext = fabricatedContext(List.of(
                new ConfiguredModuleRoot("src", srcReal, null),
                new ConfiguredModuleRoot("src-alias", srcReal, null)),
            Map.of(), null);
        SourceModuleResolver tieResolver = new SourceModuleResolver(tieContext);
        SourceModuleLocation tied =
            resolved(tieResolver, tieDir.resolve("src/imp.deal").toString(),
                "Thing");
        check(tied.moduleClassification() == null
                && tied.projectIdentity() == null,
            "the tied source publishes no module classification");

        ModuleIdentityAssembly tieAssembly =
            new ModuleIdentityAssembly(tieContext);
        check(tieAssembly.gateClassDeclaration(tied, "Thing",
                classSpanOf(tieDir.resolve("src/Thing.deal"), "Thing"))
                instanceof ModuleIdentityAssembly.DeclarationResult.Ok,
            "the tie is admitted at the declaration (E2010 only when"
                + " required)");
        CompilerDiagnostic tieFailure = expectRequireFailure(
            tieAssembly.requireClassIdentity(tied, "Thing",
                classSpanOf(tieDir.resolve("src/Thing.deal"), "Thing")),
            tieDir.resolve("src/Thing.deal").toString(),
            "a required identity on a tied source is E2010");
        check(tieFailure.message().contains("ambiguous"),
            "the tie message names the ambiguity: " + tieFailure.message());
        checkNoRegistration(tieAssembly,
            new CanonicalClassIdentity(
                CanonicalModuleIdentity.BuiltinModule.INSTANCE, "Thing"),
            "a failed required identity registers nothing");

        // Overlap: an externals declaration naming a pinned stdlib file
        // (unreachable via locate: the stdlib-overlap rejection). The
        // assembly re-derives the overlap purely and fails only when
        // required.
        Path overlapDir = Files.createDirectories(tmpDir.resolve("overlap"));
        Files.createDirectories(overlapDir.resolve("std"));
        writeText(overlapDir.resolve("std/console.d.deal"),
            "export class C { }\n");
        String consoleReal = overlapDir.resolve("std/console.d.deal")
            .toRealPath().toString();
        Map<String, ExternalEntry> overlapExternals = new LinkedHashMap<>();
        overlapExternals.put("bad/console", new ExternalEntry("bad/console",
            new NormalizedDeclarationPath(consoleReal, null), null,
            dummyRange()));
        ProjectContext overlapContext = fabricatedContext(List.of(),
            overlapExternals, overlapDir.resolve("std").toRealPath().toString());
        SourceModuleResolver overlapResolver =
            new SourceModuleResolver(overlapContext);
        SourceModuleLocation overlapped = resolved(overlapResolver,
            overlapDir.resolve("main.deal").toString(), "bad/console");
        check(overlapped.moduleClassification() == null,
            "the overlapped source publishes no module classification");

        ModuleIdentityAssembly overlapAssembly =
            new ModuleIdentityAssembly(overlapContext);
        check(overlapAssembly.gateClassDeclaration(overlapped, "C",
                classSpanOf(overlapDir.resolve("std/console.d.deal"), "C"))
                instanceof ModuleIdentityAssembly.DeclarationResult.Ok,
            "the overlap is admitted at the declaration (E2010 only when"
                + " required)");
        CompilerDiagnostic overlapFailure = expectRequireFailure(
            overlapAssembly.requireClassIdentity(overlapped, "C",
                classSpanOf(overlapDir.resolve("std/console.d.deal"), "C")),
            overlapDir.resolve("std/console.d.deal").toString(),
            "a required identity on an overlapped source is E2010");
        check(overlapFailure.message().contains("overlap"),
            "the overlap message names the overlap: "
                + overlapFailure.message());
    }

    // =========================================================================
    // The shared main fixture (locate + T6, roots src/lib/lib/utils/decl)
    // =========================================================================

    private static MainFixture mainFixture() throws IOException {
        if (mainFixture != null) {
            return mainFixture;
        }
        Path dir = Files.createDirectories(tmpDir.resolve("main"));
        Files.createDirectories(dir.resolve("src/models"));
        Files.createDirectories(dir.resolve("lib"));
        Files.createDirectories(dir.resolve("lib/utils/deep"));
        Files.createDirectories(dir.resolve("decl"));
        String manifest = "{\"languageVersion\": \"1.2\", \"moduleRoots\":"
            + " [\"src\", \"lib\", \"lib/utils\", \"decl\"], \"externals\":"
            + " {\"host/extra\": {\"declaration\": \"decl/extra.d.deal\"},"
            + " \"host/a,b\": {\"declaration\": \"decl/comma.d.deal\"}}}";
        writeText(dir.resolve("deal.json"), manifest);
        writeText(dir.resolve("main.deal"), "export function main(): null {}\n");
        writeText(dir.resolve("src/imp.deal"), "// importer\n");
        writeText(dir.resolve("src/User.deal"),
            "export class User { name: string; }\n");
        writeText(dir.resolve("src/models/Thing.deal"),
            "export class Thing { n: int; }\n");
        writeText(dir.resolve("src/Top.deal"), "export class Top { }\n");
        writeText(dir.resolve("src/free.deal"),
            "export function free(): null {}\n");
        writeText(dir.resolve("lib/Widget.deal"), "export class Widget { }\n");
        writeText(dir.resolve("lib/utils/deep/Gadget.deal"),
            "export class Gadget { }\n");
        writeText(dir.resolve("decl/extra.d.deal"),
            "export class Host { }\n");
        writeText(dir.resolve("decl/comma.d.deal"),
            "export class Comma { }\n");
        ProjectContext context = locateOrFail(dir, "main.deal");
        SourceModuleResolver resolver = new SourceModuleResolver(context);
        ModuleIdentityAssembly assembly = new ModuleIdentityAssembly(context);
        mainFixture = new MainFixture(dir, context, resolver, assembly);
        return mainFixture;
    }

    /** The entry-file resolver importer path of the main fixture. */
    private static String mainEntry() throws IOException {
        return mainFixture().dir().resolve("main.deal").toString();
    }

    /** The src/imp.deal importer path of the main fixture. */
    private static String mainSrcImporter() throws IOException {
        return mainFixture().dir().resolve("src/imp.deal").toString();
    }

    // =========================================================================
    // Project form: most-specific roots and the pinned projections
    // =========================================================================

    private static void testProjectedRootedClasses() throws Exception {
        System.out.println("-- Project form: most-specific roots and projections");

        MainFixture fixture = mainFixture();
        SourceModuleResolver resolver = fixture.resolver();
        ModuleIdentityAssembly assembly = fixture.assembly();

        SourceModuleLocation user =
            resolved(resolver, mainEntry(), "User");
        SourceModuleLocation thing =
            resolved(resolver, mainEntry(), "models/Thing");
        SourceModuleLocation top =
            resolved(resolver, mainEntry(), "Top");
        SourceModuleLocation widget =
            resolved(resolver, mainEntry(), "Widget");
        SourceModuleLocation gadget =
            resolved(resolver, mainEntry(), "utils/deep/Gadget");

        check(user.moduleClassification()
                instanceof CanonicalModuleIdentity.ProjectModule
                && user.projectIdentity() != null,
            "src/User.deal carries a ProjectModule classification");
        check(user.projectIdentity().configuredRootText().equals("src")
                && user.projectIdentity().relativeModuleComponents()
                    .equals(List.of()),
            "src/User.deal projects from root src with an empty relative path");

        CanonicalClassIdentity userIdentity = expectIdentity(
            assembly.requireClassIdentity(user, "User",
                classSpanOf(fixture.dir().resolve("src/User.deal"), "User")),
            "@src/User",
            "the empty-relative-path projection is @src/User");
        check(userIdentity.equals(new CanonicalClassIdentity(
                new CanonicalModuleIdentity.ProjectModule(
                    user.projectIdentity()), "User")),
            "the assembled identity is CanonicalClassIdentity(ProjectModule,"
                + " className)");

        expectIdentity(
            assembly.requireClassIdentity(thing, "Thing",
                classSpanOf(fixture.dir().resolve("src/models/Thing.deal"),
                    "Thing")),
            "@src/models/Thing",
            "src/models/Thing.deal projects @src/models/Thing");
        expectIdentity(
            assembly.requireClassIdentity(top, "Top",
                classSpanOf(fixture.dir().resolve("src/Top.deal"), "Top")),
            "@src/Top",
            "src/Top.deal projects @src/Top (file directly in its root)");
        expectIdentity(
            assembly.requireClassIdentity(widget, "Widget",
                classSpanOf(fixture.dir().resolve("lib/Widget.deal"),
                    "Widget")),
            "@lib/Widget",
            "lib/Widget.deal projects @lib/Widget");
        expectIdentity(
            assembly.requireClassIdentity(gadget, "Gadget",
                classSpanOf(fixture.dir().resolve("lib/utils/deep/Gadget.deal"),
                    "Gadget")),
            "@lib/utils/deep/Gadget",
            "lib/utils/deep/Gadget.deal selects the most-specific root"
                + " lib/utils and projects @lib/utils/deep/Gadget");

        // Distinct descriptor projections per most-specific root.
        List<String> projections = List.of("@src/User", "@src/models/Thing",
            "@src/Top", "@lib/Widget", "@lib/utils/deep/Gadget");
        for (int i = 0; i < projections.size(); i++) {
            for (int j = i + 1; j < projections.size(); j++) {
                check(!projections.get(i).equals(projections.get(j)),
                    "distinct projections: '" + projections.get(i) + "' vs '"
                        + projections.get(j) + "'");
            }
        }

        // Class-free code in a rooted source demands no identity.
        SourceModuleLocation free =
            resolved(resolver, mainEntry(), "free");
        check(free.moduleClassification()
                instanceof CanonicalModuleIdentity.ProjectModule,
            "class-free src/free.deal still carries its ProjectModule"
                + " classification");
        check(assembly.gateClassDeclaration(free, "Unused",
                Span.synthetic(free.normalizedSourcePath()))
                instanceof ModuleIdentityAssembly.DeclarationResult.Ok,
            "a rooted class-free source admits declarations");
    }

    // =========================================================================
    // The index contract (main fixture registrations)
    // =========================================================================

    private static void testIndexContract() throws Exception {
        System.out.println("-- Index contract: (moduleIdentity, className) and"
            + " descriptor-text keys");

        MainFixture fixture = mainFixture();
        ModuleIdentityAssembly assembly = fixture.assembly();
        CanonicalClassIdentityIndex index = assembly.index();
        SourceModuleLocation user =
            resolved(fixture.resolver(), mainEntry(), "User");
        CanonicalClassIdentity userIdentity = expectIdentity(
            assembly.requireClassIdentity(user, "User",
                classSpanOf(fixture.dir().resolve("src/User.deal"), "User")),
            "@src/User", "re-require of src/User");

        // Keyed by (moduleIdentity, className): a structurally equal
        // instance (never registered) looks up the same text.
        CanonicalClassIdentity userClone = new CanonicalClassIdentity(
            new CanonicalModuleIdentity.ProjectModule(user.projectIdentity()),
            "User");
        check(index.descriptorTextFor(userClone).equals("@src/User"),
            "the index is keyed by the structural (moduleIdentity,"
                + " className) pair");

        // Keyed by descriptor text byte-for-byte, both directions.
        check(index.identityForDescriptorText("@src/User").equals(userIdentity),
            "the text key returns the registered identity");
        check(index.identityForDescriptorText("@src/User") == userIdentity,
            "the text lookup returns the exact registered instance (descriptor"
                + " text round-trips through the index keys only)");
        String roundTrip = index.descriptorTextFor(userIdentity);
        check(index.identityForDescriptorText(roundTrip) == userIdentity
                && roundTrip.equals("@src/User"),
            "descriptor text round-trips through the index keys byte-for-byte");
        checkThrowsIllegalState(() -> index.identityForDescriptorText("@src/user"),
            "byte-for-byte text keys: case differs -> absent");
        checkThrowsIllegalState(
            () -> index.identityForDescriptorText("@src/User/"),
            "byte-for-byte text keys: trailing slash -> absent");
        checkThrowsIllegalState(
            () -> index.identityForDescriptorText("@src/User/Extra"),
            "byte-for-byte text keys: extra component -> absent");
        checkThrowsIllegalState(
            () -> index.descriptorTextFor(new CanonicalClassIdentity(
                new CanonicalModuleIdentity.ProjectModule(
                    new ProjectModuleIdentity("src",
                        user.projectIdentity().normalizedRootPath(),
                        List.of())),
                "Missing")),
            "an unregistered identity is absent from the index");

        // No reverse parsing: the reverse lookup returns the registered
        // instance for every text this assembly produced (registration is
        // identity→text only; text→identity is index keys only).
        SourceModuleLocation thing =
            resolved(fixture.resolver(), mainEntry(), "models/Thing");
        CanonicalClassIdentity thingIdentity = expectIdentity(
            assembly.requireClassIdentity(thing, "Thing",
                classSpanOf(fixture.dir().resolve("src/models/Thing.deal"),
                    "Thing")),
            "@src/models/Thing",
            "the index contract registers models/Thing");
        check(index.identityForDescriptorText("@src/models/Thing")
                == thingIdentity,
            "the models text key returns the exact registered instance");
    }

    // =========================================================================
    // Unrepresentable project identities (E2010 when required)
    // =========================================================================

    private static void testUnrepresentableProjectForm() throws Exception {
        System.out.println("-- Project form: unrepresentable identities are E2010"
            + " when required");

        UnrepresentableCase[] cases = new UnrepresentableCase[] {
            new UnrepresentableCase("unrep-ext", "$external", "Thing.deal",
                "$external"),
            new UnrepresentableCase("unrep-at", "a@b", "Thing.deal", "a@b"),
            new UnrepresentableCase("unrep-space", "a b", "Thing.deal", "a b"),
            new UnrepresentableCase("unrep-arrow", "a->b", "Thing.deal",
                "a->b"),
            new UnrepresentableCase("unrep-component", "src", "a->b/Thing.deal",
                "a->b"),
        };
        for (UnrepresentableCase unrepresentable : cases) {
            runUnrepresentableRootCase(unrepresentable);
        }

        // Root "..": a component that is exactly ".." is unrepresentable.
        Path dotdot = Files.createDirectories(
            tmpDir.resolve("unrep-dotdot/proj"));
        writeText(dotdot.resolve("deal.json"),
            "{\"languageVersion\": \"1.2\", \"moduleRoots\": [\"..\"]}");
        writeText(dotdot.resolve("main.deal"),
            "export function main(): null {}\n");
        writeText(tmpDir.resolve("unrep-dotdot/Thing.deal"),
            "export class Thing { n: int; }\n");
        writeText(tmpDir.resolve("unrep-dotdot/free.deal"),
            "export function free(): null {}\n");
        ProjectContext dotdotContext = locateOrFail(dotdot, "main.deal");
        SourceModuleResolver dotdotResolver =
            new SourceModuleResolver(dotdotContext);
        SourceModuleLocation dotdotThing =
            resolved(dotdotResolver, dotdot.resolve("main.deal").toString(),
                "Thing");
        ModuleIdentityAssembly dotdotAssembly =
            new ModuleIdentityAssembly(dotdotContext);
        check(dotdotAssembly.gateClassDeclaration(dotdotThing, "Thing",
                classSpanOf(tmpDir.resolve("unrep-dotdot/Thing.deal"), "Thing"))
                instanceof ModuleIdentityAssembly.DeclarationResult.Ok,
            "root '..' is admitted at the declaration");
        CompilerDiagnostic dotdotFailure = expectRequireFailure(
            dotdotAssembly.requireClassIdentity(dotdotThing, "Thing",
                classSpanOf(tmpDir.resolve("unrep-dotdot/Thing.deal"), "Thing")),
            tmpDir.resolve("unrep-dotdot/Thing.deal").toString(),
            "root '..' is E2010 when required");
        check(dotdotFailure.message().contains("'..'"),
            "the '..' message names the component: "
                + dotdotFailure.message());
        checkNoRegistration(dotdotAssembly,
            new CanonicalClassIdentity(new CanonicalModuleIdentity.ProjectModule(
                dotdotThing.projectIdentity()), "Thing"),
            "root '..' registers nothing on failure");
        check(resolved(dotdotResolver, dotdot.resolve("main.deal").toString(),
                "free").moduleClassification()
                instanceof CanonicalModuleIdentity.ProjectModule,
            "class-free code under root '..' resolves without error");
    }

    /** One unrepresentable-root fixture case. */
    private record UnrepresentableCase(String name, String rootText,
                                       String sourceWithinRoot,
                                       String failingText) {
    }

    private static void runUnrepresentableRootCase(UnrepresentableCase c)
            throws Exception {
        Path dir = Files.createDirectories(tmpDir.resolve(c.name()));
        writeText(dir.resolve("deal.json"),
            "{\"languageVersion\": \"1.2\", \"moduleRoots\": [\""
                + c.rootText() + "\"]}");
        writeText(dir.resolve("main.deal"), "export function main(): null {}\n");
        Path source = writeText(
            dir.resolve(c.rootText()).resolve(c.sourceWithinRoot()),
            "export class Thing { n: int; }\n");
        writeText(dir.resolve(c.rootText()).resolve("free.deal"),
            "export function free(): null {}\n");
        ProjectContext context = locateOrFail(dir, "main.deal");
        SourceModuleResolver resolver = new SourceModuleResolver(context);
        String specifier = c.sourceWithinRoot();
        if (specifier.endsWith(".deal")) {
            specifier = specifier.substring(0,
                specifier.length() - ".deal".length());
        }
        SourceModuleLocation location = resolved(resolver,
            dir.resolve("main.deal").toString(), specifier);
        check(location.moduleClassification()
                instanceof CanonicalModuleIdentity.ProjectModule,
            c.name() + ": the class source carries a ProjectModule"
                + " classification");
        ModuleIdentityAssembly assembly = new ModuleIdentityAssembly(context);
        check(assembly.gateClassDeclaration(location, "Thing",
                classSpanOf(source, "Thing"))
                instanceof ModuleIdentityAssembly.DeclarationResult.Ok,
            c.name() + ": the declaration is admitted (E2010 only when"
                + " required)");
        CompilerDiagnostic failure = expectRequireFailure(
            assembly.requireClassIdentity(location, "Thing",
                classSpanOf(source, "Thing")),
            source.toString(),
            c.name() + ": the required identity is E2010");
        check(failure.message().contains(c.failingText()),
            c.name() + ": the message names the offending text '"
                + c.failingText() + "': " + failure.message());
        checkNoRegistration(assembly,
            new CanonicalClassIdentity(new CanonicalModuleIdentity.ProjectModule(
                location.projectIdentity()), "Thing"),
            c.name() + ": a failed required identity registers nothing");
        check(resolved(resolver, dir.resolve("main.deal").toString(), "free")
                .moduleClassification()
                instanceof CanonicalModuleIdentity.ProjectModule,
            c.name() + ": class-free code in the same source resolves"
                + " without error");
    }

    // =========================================================================
    // Externals form (file-keyed, all three spellings, the entry note)
    // =========================================================================

    private static void testExternalsForm() throws Exception {
        System.out.println("-- Externals form: file-keyed identity and the"
            + " externals-entry note");

        MainFixture fixture = mainFixture();
        SourceModuleResolver resolver = fixture.resolver();
        ModuleIdentityAssembly assembly = fixture.assembly();
        Span hostSpan = classSpanOf(fixture.dir().resolve("decl/extra.d.deal"),
            "Host");

        SourceModuleLocation viaExternals = resolved(resolver, mainEntry(),
            "host/extra");
        SourceModuleLocation viaRootSearch = resolved(resolver, mainEntry(),
            "extra");
        SourceModuleLocation viaRelative = resolved(resolver, mainSrcImporter(),
            "../decl/extra");
        check(viaExternals == viaRootSearch && viaExternals == viaRelative,
            "all three spellings memoize to one published location");
        check(viaExternals.moduleClassification()
                instanceof CanonicalModuleIdentity.ExternalModule external
                && external.rawImportSpecifier().equals("host/extra"),
            "the location carries ExternalModule('host/extra')");

        CanonicalClassIdentity hostIdentity = expectIdentity(
            assembly.requireClassIdentity(viaExternals, "Host", hostSpan),
            "@$external/host/extra/Host",
            "the externals-lookup spelling projects @$external/host/extra/Host");
        expectIdentity(
            assembly.requireClassIdentity(viaRootSearch, "Host", hostSpan),
            "@$external/host/extra/Host",
            "the bare root-search spelling projects the same externals form");
        ModuleIdentityAssembly.ClassIdentityResult relativeResult =
            assembly.requireClassIdentity(viaRelative, "Host", hostSpan);
        check(relativeResult instanceof
                ModuleIdentityAssembly.ClassIdentityResult.Identity relative
                && relative.identity() == hostIdentity,
            "the relative spelling yields the exact registered instance"
                + " (file-keyed, resolution-order-independent)");

        // The declaration gate admits an externals class (no identity
        // demanded at the declaration).
        check(assembly.gateClassDeclaration(viaExternals, "Host", hostSpan)
                instanceof ModuleIdentityAssembly.DeclarationResult.Ok,
            "an externals class is admitted at the declaration");

        // Unrepresentable specifier: 'host/a,b' (comma is a descriptor
        // metacharacter). E2010 when required with the externals-entry
        // note carrying the entry's manifest value range.
        SourceModuleLocation comma = resolved(resolver, mainEntry(),
            "host/a,b");
        Span commaSpan = classSpanOf(fixture.dir().resolve("decl/comma.d.deal"),
            "Comma");
        check(assembly.gateClassDeclaration(comma, "Comma", commaSpan)
                instanceof ModuleIdentityAssembly.DeclarationResult.Ok,
            "an unrepresentable-specifier class is admitted at the"
                + " declaration (E2010 only when required)");
        CompilerDiagnostic commaFailure = expectRequireFailure(
            assembly.requireClassIdentity(comma, "Comma", commaSpan),
            fixture.dir().resolve("decl/comma.d.deal").toString(),
            "the unrepresentable specifier is E2010 when required");
        check(commaFailure.message().contains("'host/a,b'"),
            "the specifier message names the raw specifier: "
                + commaFailure.message());
        DiagnosticNote entryNote = null;
        for (DiagnosticNote note : commaFailure.notes()) {
            if (note.message().contains("externals entry")) {
                entryNote = note;
            }
        }
        check(entryNote != null,
            "the externals-entry note is present");
        if (entryNote != null) {
            check(entryNote.message().contains("'host/a,b'"),
                "the note names the entry: " + entryNote.message());
            check(entryNote.range() != null
                    && entryNote.range().origin() == RangeOrigin.SOURCE,
                "the note carries a SOURCE range");
            check(entryNote.range() != null
                    && entryNote.range().file().equals(
                        fixture.context().manifestPath()),
                "the note range is in the manifest file");
            String manifest = Files.readString(
                fixture.dir().resolve("deal.json"));
            int keyStart = manifest.indexOf("\"host/a,b\"");
            int colon = manifest.indexOf(':', keyStart);
            int brace = manifest.indexOf('{', colon);
            int entryEnd = manifest.indexOf('}', brace) + 1;
            check(entryNote.range().startScalarOffset() == brace
                    && entryNote.range().endScalarOffset() == entryEnd
                    && entryNote.range().scalarLength() == entryEnd - brace
                    && entryNote.range().startLine() == 1
                    && entryNote.range().startColumn() == brace + 1
                    && entryNote.range().endColumn() == entryEnd + 1,
                "the note carries the entry object's exact value range ["
                    + entryNote.range().startScalarOffset() + ","
                    + entryNote.range().endScalarOffset() + "), expected ["
                    + brace + "," + entryEnd + ")");
        }
        checkNoRegistration(assembly,
            new CanonicalClassIdentity(new CanonicalModuleIdentity.ExternalModule(
                "host/a,b"), "Comma"),
            "a failed externals identity registers nothing");

        // The canonical externals declaration is never a pinned stdlib
        // file (T4 step 4(b) mutual exclusion, re-verified on the
        // published context).
        for (ExternalEntry entry : fixture.context().externals().values()) {
            for (String pinnedFile : fixture.context().stdlibDeclarationFiles()) {
                check(!entry.declarationPath().absoluteNormalizedPath()
                        .equals(pinnedFile),
                    "externals entry '" + entry.rawImportSpecifier()
                        + "' is disjoint from the pinned stdlib file '"
                        + pinnedFile + "'");
            }
        }
    }

    // =========================================================================
    // Builtin form (missing-projection note and the intrinsic Error)
    // =========================================================================

    private static void testBuiltinForm() throws Exception {
        System.out.println("-- Builtin form: missing projection and the"
            + " intrinsic Error");

        Path dir = Files.createDirectories(tmpDir.resolve("builtin"));
        Files.createDirectories(dir.resolve("std"));
        writeText(dir.resolve("deal.json"),
            "{\"languageVersion\": \"1.2\"}");
        writeText(dir.resolve("main.deal"), "export function main(): null {}\n");
        writeText(dir.resolve("std/console.d.deal"), "export class C { }\n");
        ProjectContext context = locateOrFail(dir, "main.deal");
        check(context.stdlibSurfacePath() != null
                && context.stdlibDeclarationFiles().contains(
                    dir.resolve("std/console.d.deal").toRealPath().toString()),
            "the fixture pins the project-local stdlib surface with"
                + " console.d.deal");
        SourceModuleResolver resolver = new SourceModuleResolver(context);
        ModuleIdentityAssembly assembly = new ModuleIdentityAssembly(context);

        SourceModuleLocation bare = resolved(resolver,
            dir.resolve("main.deal").toString(), "std/console");
        SourceModuleLocation relative = resolved(resolver,
            dir.resolve("main.deal").toString(), "./std/console");
        check(bare == relative
                && bare.moduleClassification()
                    instanceof CanonicalModuleIdentity.BuiltinModule,
            "the bare and relative spellings memoize to one BuiltinModule"
                + " location (file-keyed)");

        Span cSpan = classSpanOf(dir.resolve("std/console.d.deal"), "C");
        check(assembly.gateClassDeclaration(bare, "C", cSpan)
                instanceof ModuleIdentityAssembly.DeclarationResult.Ok,
            "a builtin-module class is admitted at the declaration (E2010"
                + " only when required)");
        CompilerDiagnostic missingProjection = expectRequireFailure(
            assembly.requireClassIdentity(bare, "C", cSpan),
            dir.resolve("std/console.d.deal").toString(),
            "a required builtin class identity other than Error is E2010");
        check(missingProjection.message().contains("no descriptor projection"),
            "the missing-projection message is pinned: "
                + missingProjection.message());
        boolean hasMissingProjectionNote = false;
        for (DiagnosticNote note : missingProjection.notes()) {
            if (note.message().contains("missing projection")) {
                hasMissingProjectionNote = true;
            }
        }
        check(hasMissingProjectionNote,
            "the missing-projection note is present");

        // A required builtin identity named exactly Error yields the
        // intrinsic synthesis.
        CanonicalClassIdentity errorIdentity = expectIdentity(
            assembly.requireClassIdentity(relative, "Error", cSpan),
            "@$builtin/Error",
            "a builtin-module class named Error yields the intrinsic identity");
        check(errorIdentity == assembly.intrinsicErrorIdentity(),
            "the builtin-module Error identity is the synthesized intrinsic"
                + " instance");
        check(assembly.gateClassDeclaration(bare, "Error", cSpan)
                instanceof ModuleIdentityAssembly.DeclarationResult.Ok,
            "a builtin-module class named Error is admitted at the"
                + " declaration (E4006 is unchanged and outside the"
                + " assembly)");
    }

    // =========================================================================
    // Unconditional rule (d)
    // =========================================================================

    private static void testUnconditionalNoIdentity() throws Exception {
        System.out.println("-- Unconditional rule (d): no public module"
            + " identity");

        Path dir = Files.createDirectories(tmpDir.resolve("outr"));
        Files.createDirectories(dir.resolve("src"));
        Files.createDirectories(dir.resolve("std"));
        writeText(dir.resolve("deal.json"),
            "{\"languageVersion\": \"1.2\", \"moduleRoots\": [\"src\"]}");
        writeText(dir.resolve("main.deal"), "export function main(): null {}\n");
        writeText(dir.resolve("src/imp.deal"), "// importer\n");
        writeText(dir.resolve("src/hostonly.d.deal"),
            "export class HostOnly { }\n");
        writeText(dir.resolve("src/hostfree.d.deal"),
            "export function g(): null;\n");
        writeText(dir.resolve("std/console.d.deal"),
            "export function log(s: string): null;\n");
        writeText(dir.resolve("std/custom.d.deal"), "export class Custom { }\n");
        writeText(dir.resolve("std/customfree.d.deal"),
            "export function cf(): null;\n");
        writeText(tmpDir.resolve("outr-outside.deal"),
            "export class Outer { }\n");
        writeText(tmpDir.resolve("outr-outsidefree.deal"),
            "export function of(): null {}\n");
        ProjectContext context = locateOrFail(dir, "main.deal");
        SourceModuleResolver resolver = new SourceModuleResolver(context);
        ModuleIdentityAssembly assembly = new ModuleIdentityAssembly(context);

        // A rooted non-externals .d.deal source.
        SourceModuleLocation hostOnly = resolved(resolver,
            dir.resolve("src/imp.deal").toString(), "./hostonly");
        check(hostOnly.moduleClassification() == null,
            "the rooted non-externals .d.deal carries no public module"
                + " identity");
        CompilerDiagnostic hostOnlyFailure = expectDeclarationFailure(
            assembly.gateClassDeclaration(hostOnly, "HostOnly",
                classSpanOf(dir.resolve("src/hostonly.d.deal"), "HostOnly")),
            dir.resolve("src/hostonly.d.deal").toString(),
            "a class in a rooted non-externals .d.deal is E2010 at the"
                + " declaration");
        check(hostOnlyFailure.message().contains("no public module identity"),
            "the unconditional message names the missing identity: "
                + hostOnlyFailure.message());
        expectRequireFailure(
            assembly.requireClassIdentity(hostOnly, "HostOnly",
                classSpanOf(dir.resolve("src/hostonly.d.deal"), "HostOnly")),
            dir.resolve("src/hostonly.d.deal").toString(),
            "the required gate fails identically (defensive re-gate)");
        checkNoRegistration(assembly,
            new CanonicalClassIdentity(
                CanonicalModuleIdentity.BuiltinModule.INSTANCE, "HostOnly"),
            "the failed declaration registered nothing");

        // A non-spec-listed .d.deal inside the std directory.
        SourceModuleLocation custom = resolved(resolver,
            dir.resolve("src/imp.deal").toString(), "../std/custom");
        check(custom.moduleClassification() == null,
            "the non-spec .d.deal inside std carries no public module"
                + " identity");
        expectDeclarationFailure(
            assembly.gateClassDeclaration(custom, "Custom",
                classSpanOf(dir.resolve("std/custom.d.deal"), "Custom")),
            dir.resolve("std/custom.d.deal").toString(),
            "a class in a non-spec .d.deal inside std is E2010 at the"
                + " declaration");
        expectRequireFailure(
            assembly.requireClassIdentity(custom, "Custom",
                classSpanOf(dir.resolve("std/custom.d.deal"), "Custom")),
            dir.resolve("std/custom.d.deal").toString(),
            "the required gate fails identically for the non-spec std"
                + " declaration");

        // An out-of-root relative source.
        SourceModuleLocation outer = resolved(resolver,
            dir.resolve("src/imp.deal").toString(), "../../outr-outside");
        check(outer.moduleClassification() == null,
            "the out-of-root relative source carries no public module"
                + " identity");
        expectDeclarationFailure(
            assembly.gateClassDeclaration(outer, "Outer",
                classSpanOf(tmpDir.resolve("outr-outside.deal"), "Outer")),
            tmpDir.resolve("outr-outside.deal").toString(),
            "a class in an out-of-root relative source is E2010 at the"
                + " declaration");
        expectRequireFailure(
            assembly.requireClassIdentity(outer, "Outer",
                classSpanOf(tmpDir.resolve("outr-outside.deal"), "Outer")),
            tmpDir.resolve("outr-outside.deal").toString(),
            "the required gate fails identically for the out-of-root source");

        // Class-free variants of the same sources produce no identity
        // demand and no error.
        SourceModuleLocation hostFree = resolved(resolver,
            dir.resolve("src/imp.deal").toString(), "./hostfree");
        SourceModuleLocation customFree = resolved(resolver,
            dir.resolve("src/imp.deal").toString(), "../std/customfree");
        SourceModuleLocation outerFree = resolved(resolver,
            dir.resolve("src/imp.deal").toString(), "../../outr-outsidefree");
        check(hostFree.moduleClassification() == null
                && customFree.moduleClassification() == null
                && outerFree.moduleClassification() == null,
            "the class-free variants carry no public module identity and"
                + " resolve without error");
    }

    // =========================================================================
    // Idempotence and determinism
    // =========================================================================

    private static void testIdempotenceAndDeterminism() throws Exception {
        System.out.println("-- Idempotence and determinism");

        MainFixture fixture = mainFixture();
        SourceModuleLocation user =
            resolved(fixture.resolver(), mainEntry(), "User");
        Span userSpan = classSpanOf(fixture.dir().resolve("src/User.deal"),
            "User");
        ModuleIdentityAssembly.ClassIdentityResult first =
            fixture.assembly().requireClassIdentity(user, "User", userSpan);
        ModuleIdentityAssembly.ClassIdentityResult second =
            fixture.assembly().requireClassIdentity(user, "User", userSpan);
        check(first.equals(second),
            "equal gate calls return equal results");
        if (first instanceof ModuleIdentityAssembly.ClassIdentityResult.Identity
                a && second
                    instanceof ModuleIdentityAssembly.ClassIdentityResult.Identity
                b) {
            check(a.identity() == b.identity(),
                "one registered instance per (moduleIdentity, className)");
        } else {
            fail("repeated require of src/User should succeed");
        }

        // A second assembly over the identical context replayed with the
        // identical resolved-source stream produces identical identities
        // and index contents.
        ModuleIdentityAssembly other = new ModuleIdentityAssembly(
            fixture.context());
        SourceModuleLocation host =
            resolved(fixture.resolver(), mainEntry(), "host/extra");
        Span hostSpan = classSpanOf(fixture.dir().resolve("decl/extra.d.deal"),
            "Host");
        ModuleIdentityAssembly.ClassIdentityResult otherUser =
            other.requireClassIdentity(user, "User", userSpan);
        ModuleIdentityAssembly.ClassIdentityResult otherHost =
            other.requireClassIdentity(host, "Host", hostSpan);
        check(otherUser.equals(first),
            "identical inputs produce an identical User identity result");
        if (otherUser instanceof ModuleIdentityAssembly.ClassIdentityResult.Identity
                id) {
            check(id.descriptorText().equals("@src/User")
                    && other.index().descriptorTextFor(id.identity())
                        .equals("@src/User"),
                "identical inputs produce identical index contents for User");
        } else {
            fail("the second assembly should register src/User");
        }
        if (otherHost instanceof ModuleIdentityAssembly.ClassIdentityResult.Identity
                id) {
            check(id.descriptorText().equals("@$external/host/extra/Host")
                    && other.index().descriptorTextFor(id.identity())
                        .equals("@$external/host/extra/Host"),
                "identical inputs produce identical index contents for Host");
        } else {
            fail("the second assembly should register the externals Host");
        }
        CanonicalClassIdentity otherError = other.intrinsicErrorIdentity();
        check(otherError.equals(new CanonicalClassIdentity(
                CanonicalModuleIdentity.BuiltinModule.INSTANCE, "Error"))
                && other.index().descriptorTextFor(otherError)
                    .equals("@$builtin/Error"),
            "identical inputs produce the identical intrinsic Error index"
                + " entry");
    }

    // =========================================================================
    // Combined T1+T4+T6 chain
    // =========================================================================

    private static void testCombinedChain() throws Exception {
        System.out.println("-- Combined chain: locate + resolve + assembly gates");

        Path dir = Files.createDirectories(tmpDir.resolve("combined"));
        Files.createDirectories(dir.resolve("src"));
        Files.createDirectories(dir.resolve("decl"));
        writeText(dir.resolve("deal.json"),
            "{\"languageVersion\": \"1.2\", \"moduleRoots\": [\"src\"],"
                + " \"externals\": {\"host/chain\": {\"declaration\":"
                + " \"decl/chain.d.deal\"}}}");
        writeText(dir.resolve("main.deal"), "export function main(): null {}\n");
        writeText(dir.resolve("src/imp.deal"), "// importer\n");
        writeText(dir.resolve("decl/chain.d.deal"), "export class Chain { }\n");
        writeText(tmpDir.resolve("combined-outside.deal"),
            "export class Side { }\n");

        // T4/T1 locate.
        ProjectContext context = locateOrFail(dir, "main.deal");
        // T6 resolve.
        SourceModuleResolver resolver = new SourceModuleResolver(context);
        // T7 assembly gates.
        ModuleIdentityAssembly assembly = new ModuleIdentityAssembly(context);

        // The unconditional out-of-root E2010.
        SourceModuleLocation outside = resolved(resolver,
            dir.resolve("src/imp.deal").toString(), "../../combined-outside");
        CompilerDiagnostic outsideFailure = expectDeclarationFailure(
            assembly.gateClassDeclaration(outside, "Side",
                classSpanOf(tmpDir.resolve("combined-outside.deal"), "Side")),
            tmpDir.resolve("combined-outside.deal").toString(),
            "the chain yields the unconditional out-of-root E2010");
        check(outsideFailure.message().contains("no public module identity"),
            "the unconditional message names the missing identity: "
                + outsideFailure.message());

        // The externals projection.
        SourceModuleLocation external = resolved(resolver,
            dir.resolve("main.deal").toString(), "host/chain");
        expectIdentity(
            assembly.requireClassIdentity(external, "Chain",
                classSpanOf(dir.resolve("decl/chain.d.deal"), "Chain")),
            "@$external/host/chain/Chain",
            "the chain yields the externals projection");

        // The Error synthesis.
        check(assembly.intrinsicErrorIdentity().equals(
                new CanonicalClassIdentity(
                    CanonicalModuleIdentity.BuiltinModule.INSTANCE, "Error"))
                && assembly.index().descriptorTextFor(
                    assembly.intrinsicErrorIdentity())
                    .equals("@$builtin/Error"),
            "the chain yields the intrinsic Error synthesis");
    }

    // =========================================================================
    // Real stdlib surface class-freeness and the privacy invariant
    // =========================================================================

    private static void testRealStdlibSurfaceClassFree() throws Exception {
        System.out.println("-- Real stdlib surface is class-free");
        for (String module : ModuleIdentityResolver.SPEC_STDLIB_MODULE_NAMES) {
            Path declaration = Path.of("std", module + ".d.deal");
            if (!Files.isRegularFile(declaration)) {
                System.out.println("NOTE: " + declaration
                    + " missing; class-free check skipped for it.");
                continue;
            }
            check(!hasExportedClassDeclaration(declaration),
                "std/" + module + ".d.deal declares no class");
        }
    }

    private static void testPrivacyInvariant() {
        System.out.println("-- Privacy: identity URIs never appear in"
            + " projections or diagnostic text");
        check(!ALL_DIAGNOSTICS.isEmpty(),
            "the battery produced eligibility diagnostics to scan");
        for (CompilerDiagnostic diagnostic : ALL_DIAGNOSTICS) {
            check(!diagnostic.message().contains("file:"),
                "no diagnostic message contains a file: URI: "
                    + diagnostic.message());
            check(!diagnostic.message().contains("file://"),
                "no diagnostic message contains a file:// URI: "
                    + diagnostic.message());
            for (DiagnosticNote note : diagnostic.notes()) {
                check(!note.message().contains("file:"),
                    "no note message contains a file: URI: "
                        + note.message());
            }
        }
        check(!ALL_DESCRIPTOR_TEXTS.isEmpty(),
            "the battery produced projections to scan");
        for (String text : ALL_DESCRIPTOR_TEXTS) {
            check(text.startsWith("@"), "every projection starts with '@': "
                + text);
            check(!text.contains("file:"),
                "no projection contains a file: URI: " + text);
            check(!text.contains("file://"),
                "no projection contains a file:// URI: " + text);
        }
    }

    // =========================================================================
    // Fixture helpers
    // =========================================================================

    private static ProjectContext locateOrFail(Path dir, String entryName)
            throws IOException {
        Path entry = writeText(dir.resolve(entryName),
            "export function main(): null {}\n");
        ProjectLocator.LocateResult result =
            ProjectLocator.locate(entry.toString(), null);
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
     * A fabricated validated-shape context over the given roots/externals
     * (for the defensive states locate can never publish). Root and
     * declaration paths are the caller's already-resolved real paths; the
     * deployment identity digest is the fixed deterministic 64-hex value.
     */
    private static ProjectContext fabricatedContext(
            List<ConfiguredModuleRoot> roots,
            Map<String, ExternalEntry> externals, String stdlibSurface) {
        List<String> stdlibDeclarationFiles = new ArrayList<>();
        if (stdlibSurface != null) {
            for (String module : ModuleIdentityResolver.SPEC_STDLIB_MODULE_NAMES) {
                Path pinned = Path.of(stdlibSurface).resolve(module + ".d.deal");
                if (Files.isRegularFile(pinned)) {
                    try {
                        stdlibDeclarationFiles.add(
                            pinned.toRealPath().toString());
                    } catch (IOException ignored) {
                        // Not a fully resolvable regular file: cannot
                        // equal a resolved source.
                    }
                }
            }
        }
        return new ProjectContext(
            realTmp.resolve("deal.json").normalize().toString(),
            realTmp.toString(),
            realTmp.toString(),
            "1.2",
            roots,
            new OutputConfigResolver.OutputRef(
                OutputConfigResolver.Source.MANIFEST,
                OutputConfigResolver.Kind.MANIFEST_RELATIVE_PATH,
                "build/lua", realTmp.resolve("build/lua").normalize().toString(),
                null),
            "luajit",
            externals,
            "1.2",
            stdlibSurface,
            stdlibDeclarationFiles,
            new ProjectDeploymentIdentity(
                realTmp.resolve("deal.json").normalize().toUri().toString(),
                "0".repeat(64)));
    }

    private static SourceScalarRange dummyRange() {
        return new SourceScalarRange(1, 1, 1, 1, 0, 0);
    }

    /**
     * Parses a fixture source with the production lexer/parser and returns
     * the exported class declaration's span (carrying computed scalar
     * offsets — the class-name diagnostic anchor).
     */
    private static Span classSpanOf(Path file, String className)
            throws IOException {
        String source = Files.readString(file);
        Lexer lexer = new Lexer(source, file.toString());
        LexResult lexed = lexer.tokenize();
        ParseResult parsed = new Parser(lexed.tokens(), file.toString()).parse();
        for (StatementNode statement : parsed.program().statements()) {
            if (statement instanceof ExportDeclaration export
                    && export.declaration() instanceof ClassDeclaration cd
                    && cd.name().equals(className)) {
                return cd.span();
            }
        }
        fail("no exported class '" + className + "' in " + file);
        return Span.synthetic(file.toString());
    }

    /** True when a parsed source exports a class declaration. */
    private static boolean hasExportedClassDeclaration(Path file)
            throws IOException {
        String source = Files.readString(file);
        LexResult lexed = new Lexer(source, file.toString()).tokenize();
        ParseResult parsed =
            new Parser(lexed.tokens(), file.toString()).parse();
        for (StatementNode statement : parsed.program().statements()) {
            if (statement instanceof ExportDeclaration export
                    && export.declaration() instanceof ClassDeclaration) {
                return true;
            }
        }
        return false;
    }

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
