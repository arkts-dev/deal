package deal.test;

import deal.checker.BuiltinErrorDeclaration;
import deal.codegen.Backend;
import deal.distribution.DistributionHome;
import deal.ffi.FfiBindingState;
import deal.ffi.FfiForwardBindings;
import deal.ffi.FfiGeneratedModule;
import deal.ffi.FfiImportedFunctionReference;
import deal.identity.CanonicalModuleIdentity;
import deal.module.CompilationOrchestrator;
import deal.module.ProductionProjectEmission;
import deal.publication.PublicationStager;
import deal.semantic.CapabilityRegistry;
import deal.semantic.CheckedModuleInput;
import deal.semantic.CheckedProjectBuildResult;
import deal.semantic.CheckedProjectInput;
import deal.semantic.CompilerInvocation;
import deal.semantic.CompilerProfileProvider;
import deal.semantic.HostDeclarationSurface;
import deal.semantic.ReleaseConfiguration;
import deal.semantic.RequirementManifestResult;
import deal.semantic.SemanticLowerer;
import deal.semantic.SemanticOracle;
import deal.semantic.SemanticRequirementManifest;
import deal.semantic.SemanticRuntimeModel;
import deal.semantic.SharedStdlibSemantics;
import deal.semantic.ir.BoundaryKind;
import deal.semantic.ir.ExternalModuleInterface;
import deal.semantic.ir.IntrinsicKind;
import deal.semantic.ir.ModuleId;
import deal.semantic.ir.ProjectInterfaceIndex;
import deal.semantic.ir.ReleaseState;
import deal.semantic.ir.SemanticProfile;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * ISSUE-0702: the fail-closed guard retargeting and the live negative-seed
 * coverage (design source {@code dispatched-corpus-production-realization}
 * R8 and the producer guard contract; read-only
 * {@code conformance-cutover-guards-pins-and-production-source-removal}
 * HR4(a); {@code luajit-jvm-single-lowering-production-cutover} C4).
 *
 * <ol>
 *   <li><b>The three guards stay landed.</b> {@code CONSTRUCT_UNLOWERED},
 *       {@code RETAINED_ABI_DEFERRED}, and {@code SHARED_EMITTER_COVERAGE}
 *       (with its {@code HOST_MODULE_IMPORT} and {@code FFI_PROVIDER_GAP}
 *       detail tokens) keep their current identifiers; none is deleted,
 *       renamed, or bypassed. The retargeted reserved boundary names stay
 *       the two C-FFI crossings and the closed {@code BoundaryKind} set
 *       keeps its 27 members in the pinned order.</li>
 *   <li><b>One live negative seed per guard, from R8's closed candidate
 *       set, over the production targets.</b> The hand-built inconsistent
 *       fact of a cross-module callee whose module is neither lowered nor
 *       recorded fails {@code CONSTRUCT_UNLOWERED} through the missing
 *       callee fact; the hand-built inconsistent fact of a class literal
 *       whose owner is neither lowered nor declaration-typed fails
 *       {@code RETAINED_ABI_DEFERRED}. Both run through production
 *       emission on LuaJIT and JVM; the corrupted extern-C fact (one alias
 *       bound to two provider modules) fails
 *       {@code SHARED_EMITTER_COVERAGE}/{@code FFI_PROVIDER_GAP} on the
 *       LuaJIT extern-C emission (the JVM extern-C rejection is its own
 *       sanctioned E6006 contract, not substituted here). Every outcome is
 *       exactly one named E6005 that stages nothing and leaves the
 *       previous artifact set byte-identical (the atomic-publication
 *       contract); each seed carries the intact-fact control that
 *       emits.</li>
 *   <li><b>The realized-construct probes.</b> The construct families this
 *       epic realized — bytes value semantics, the {@code @jsonable}
 *       helper operations (same-module and cross-module export), a
 *       residual carrier shape (the local self-recursive declared body),
 *       the per-iteration closure capture, and the loop-target transfer
 *       protocol — compile through the release-owned production
 *       invocation, lower and run in the semantic oracle (terminal plus
 *       console effects), and execute under both real toolchains
 *       ({@code luajit}; {@code javac --release 25 -proc:none} plus
 *       {@code java}). Each artifact's status, stdout, and stderr are
 *       asserted against the oracle transcript, with no guard outcome, so
 *       the guards are unreachable for checker-valid input of those
 *       families. The JVM execution puts the generated classes ahead of
 *       every inherited classpath entry and carries a live collision
 *       control (a decoy {@code Main} that is selected only when the
 *       generated entrypoint does not come first). The {@code @jsonable}
 *       helper probe requires the landed helper family's successful
 *       compilation and oracle-agreed executed transcript on both targets
 *       (the ISSUE-0698 realization), so a regression fails this child by
 *       name.</li>
 * </ol>
 *
 * <p>The historical source-scanning framework and its synthetic parser
 * controls are not part of this suite: the guard contract is carried by
 * the executable seeds and the real production probes above, not by a
 * source inventory.</p>
 */
public class FailClosedGuardRetargetingTest {

    private static int passed = 0;
    private static int failed = 0;

    private static void check(boolean condition, String message) {
        if (condition) {
            passed++;
        } else {
            failed++;
            System.err.println("FAIL: " + message);
        }
    }

    private static void checkEq(Object expected, Object actual, String message) {
        check(java.util.Objects.equals(expected, actual),
            message + " (expected " + expected + ", got " + actual + ")");
    }

    // =========================================================================
    // Fixtures
    // =========================================================================

    private static final ModuleId NATIVE_PROBE = new ModuleId("native.probe");
    private static final ModuleId HOST_HELPER = new ModuleId("host.helper");

    /**
     * The cross-module-callee fixture of the undeclared-external-entry
     * seed (R8 candidate (c)): the caller's call records the callee export
     * fact; removing the callee module from the closure removes the
     * recorded {@code EXTERNAL_ENTRY}.
     */
    private static final String CALLEE_LIB_SOURCE = """
        export function add(a: int, b: int): int {
          return a + b;
        }
        """;

    private static final String CROSS_MODULE_CALL_SOURCE = """
        import * as lib from "./lib"

        export function main(): null {
          let x: int = lib.add(1, 2)
          return null
        }
        """;

    /** The imported-class owner of the RETAINED_ABI_DEFERRED seed. */
    private static final String UTIL_SOURCE = """
        export class Point {
          x: int = 0;
          y: int = 1;
        }
        """;

    /**
     * The class literal whose owner the hand-built inconsistent fact
     * removes from the closure, the index, and the manifests (R8 candidate
     * (d): a class literal whose owner is neither lowered nor
     * declaration-typed).
     */
    private static final String IMPORTED_LITERAL_SOURCE = """
        import * as u from "util"

        export function main(): null {
          let p: u.Point = {x: 5}
          return null
        }
        """;

    /**
     * The extern-C declaration whose {@code @c-struct} class default names
     * the host provider {@code h.ping()} (the corrupted-fact seed's
     * baseline).
     */
    private static final String EXTERN_C_DECLARATION = """
        import * as h from "host/helper"

        // @extern-c

        // @c-struct
        export class Pair {
          left: int = h.ping();
        }

        export function probe(): int;
        """;

    /** The extern-C provider's host declaration. */
    private static final String HOST_PROVIDER_DECLARATION = """
        export function ping(): int;
        """;

    /** The extern-C consumer: the declaration import is never called. */
    private static final String EXTERN_C_APP_SOURCE = """
        import * as h from "host/helper"
        import * as native from "native/probe"

        export function main(): null {
          return null
        }
        """;

    private record Fixture(
        Path root,
        Path sourceRoot,
        CheckedProjectInput checkedProject,
        ProjectInterfaceIndex index,
        List<SemanticRequirementManifest> manifests,
        HostDeclarationSurface surface,
        Map<ModuleId, FfiGeneratedModule> externCModules,
        Map<ModuleId, CanonicalModuleIdentity> declarationIdentities,
        DistributionHome distributionHome) {
    }

    private static CompilerInvocation harnessInvocation() {
        return CompilerProfileProvider.resolveCommonShadow(
            SemanticProfile.DEAL_V1_2_INT32, ReleaseState.V1_2_ACTIVE,
            CapabilityRegistry.releaseRegistry());
    }

    private static CompilerInvocation productionInvocation() {
        return CompilerProfileProvider.resolve(
            ReleaseConfiguration.CURRENT_RELEASE_STATE,
            ReleaseConfiguration.releaseCapabilityRegistry());
    }

    /**
     * Compiles one fixture project through the frontend with the harness
     * invocation (P10 item 3: the fixture inputs stay buildable while the
     * unit under test runs the release-owned production invocation).
     */
    private static Fixture compileFixture(Map<String, String> sources,
            Map<String, String> externals) throws Exception {
        Path root = Files.createTempDirectory("guard-retarget-fixture");
        Path src = root.resolve("src");
        Files.createDirectories(src);
        for (Map.Entry<String, String> source : sources.entrySet()) {
            writeFileIn(root, source.getKey(), source.getValue());
        }
        Map<String, String> resolvedExternals = new LinkedHashMap<>();
        for (Map.Entry<String, String> external : externals.entrySet()) {
            resolvedExternals.put(external.getKey(),
                root.resolve(external.getValue()).toAbsolutePath().toString());
        }
        Path entry = src.resolve("main.deal").toAbsolutePath();
        Path output = root.resolve("out").toAbsolutePath();
        CompilationOrchestrator orchestrator = new CompilationOrchestrator(entry,
            output, false, false, false, false, Backend.LUAJIT, resolvedExternals,
            List.of(src.toAbsolutePath()), null, null, harnessInvocation());
        boolean compiled = orchestrator.compile();
        CheckedProjectBuildResult built = orchestrator.checkedProject();
        RequirementManifestResult manifests = orchestrator.requirementManifests();
        if (!compiled || built == null || built.input() == null
                || built.index() == null || built.hasErrors() || manifests == null
                || manifests.manifests() == null
                || orchestrator.hostDeclarationSurface() == null) {
            String detail = built == null ? "no checked project"
                : String.valueOf(built.diagnostics());
            deleteRecursively(root);
            throw new IllegalStateException("the guard fixture did not build: "
                + detail + " / " + orchestrator.diagnostics());
        }
        Map<ModuleId, FfiGeneratedModule> externCModules = new LinkedHashMap<>();
        for (Map.Entry<String, FfiGeneratedModule> generated
                : orchestrator.ffiGenerations().entrySet()) {
            externCModules.put(new ModuleId(generated.getKey()), generated.getValue());
        }
        Map<ModuleId, CanonicalModuleIdentity> identities = new LinkedHashMap<>();
        for (ModuleId declarationModule
                : orchestrator.hostDeclarationSurface().moduleIds()) {
            String specifier = null;
            for (String candidate : resolvedExternals.keySet()) {
                if (candidate.replace('/', '.').equals(declarationModule.path())) {
                    specifier = candidate;
                    break;
                }
            }
            identities.put(declarationModule, specifier == null
                ? IdentityTestFixtures.moduleIdentityOf(declarationModule.path())
                : new CanonicalModuleIdentity.ExternalModule(specifier));
        }
        return new Fixture(root, src, built.input(), built.index(),
            manifests.manifests(), orchestrator.hostDeclarationSurface(),
            externCModules, identities,
            DistributionHome.forManifestDirectory(src.toString()));
    }

    private static Fixture importedLiteralFixture() throws Exception {
        return compileFixture(new LinkedHashMap<>(Map.of(
                "src/util.deal", UTIL_SOURCE,
                "src/main.deal", IMPORTED_LITERAL_SOURCE)), Map.of());
    }

    private static Fixture crossModuleCallFixture() throws Exception {
        return compileFixture(new LinkedHashMap<>(Map.of(
                "src/lib.deal", CALLEE_LIB_SOURCE,
                "src/main.deal", CROSS_MODULE_CALL_SOURCE)), Map.of());
    }

    private static Fixture externCProviderFixture() throws Exception {
        return compileFixture(new LinkedHashMap<>(Map.of(
                "src/probe.d.deal", EXTERN_C_DECLARATION,
                "src/helper.d.deal", HOST_PROVIDER_DECLARATION,
                "src/main.deal", EXTERN_C_APP_SOURCE)),
            new LinkedHashMap<>(Map.of(
                "native/probe", "src/probe.d.deal",
                "host/helper", "src/helper.d.deal")));
    }

    // =========================================================================
    // The unit driver
    // =========================================================================

    private static ProductionProjectEmission.Result emit(Fixture fixture,
            CheckedProjectInput project, ProjectInterfaceIndex index,
            List<SemanticRequirementManifest> manifests,
            Map<ModuleId, FfiGeneratedModule> externCModules,
            PublicationStager stager, Backend backend) throws Exception {
        return ProductionProjectEmission.run(productionInvocation(), project,
            index, manifests, fixture.surface(), fixture.declarationIdentities(),
            externCModules, fixture.distributionHome().manifestDirectoryText(),
            BuiltinErrorDeclaration.synthesized(
                project.modules().get(0).ast().span()),
            List.of(IntrinsicKind.INT_CONVERT, IntrinsicKind.NUMBER_CONVERT,
                IntrinsicKind.BYTES_NEW),
            Set.of(), backend, false, fixture.distributionHome(), stager);
    }

    /** The two production targets the non-FFI negative seeds cover. */
    private static final List<Backend> NEGATIVE_TARGETS =
        List.of(Backend.LUAJIT, Backend.JVM);

    // =========================================================================
    // 1. The three guards stay landed
    // =========================================================================

    private static void testGuardIdentifiersLanded() {
        System.out.println("-- the three guard identifiers, the detail tokens, "
            + "and the retargeted reserved boundary names stay landed --");

        checkEq("CONSTRUCT_UNLOWERED", SemanticLowerer.CONSTRUCT_UNLOWERED,
            "the unlowered-construct rule identifier");
        checkEq("RETAINED_ABI_DEFERRED", SemanticLowerer.RETAINED_ABI_DEFERRED,
            "the imported-class deferral rule identifier");
        checkEq("SHARED_EMITTER_COVERAGE",
            ProductionProjectEmission.SHARED_EMITTER_COVERAGE,
            "the emitter-coverage rule identifier");
        checkEq("HOST_MODULE_IMPORT",
            ProductionProjectEmission.HOST_MODULE_IMPORT,
            "the declaration-import guard detail token");
        checkEq("FFI_PROVIDER_GAP",
            ProductionProjectEmission.FFI_PROVIDER_GAP,
            "the FFI provider-gap detail token");

        // The retargeted reserved-name seed: BYTE_ELEMENT_READ and
        // BYTE_ELEMENT_ASSIGNMENT are members; the two C-FFI crossings stay
        // the reserved pair, and the closed set keeps its 27 members in the
        // pinned order.
        checkEq(List.of(
                "VARIABLE_DECLARATION", "VARIABLE_ASSIGNMENT",
                "CLASS_FIELD_ASSIGNMENT", "ARRAY_ELEMENT_ASSIGNMENT",
                "BYTE_ELEMENT_ASSIGNMENT", "ARRAY_ELEMENT_READ",
                "BYTE_ELEMENT_READ", "ARRAY_ELEMENT_DELETE",
                "ARRAY_LITERAL_ELEMENT", "FUNCTION_PARAMETER",
                "FUNCTION_RETURN", "ASYNC_COMPLETION", "CLASS_LITERAL_FIELD",
                "CLASS_DEFAULT_FIELD", "UNTYPED_CLASS_INPUT",
                "OPTIONAL_FIELD_READ", "CONTEXTUAL_TABLE_READ",
                "IMPORTED_MEMBER_READ", "MODULE_EXPORT", "HOST_TO_DEAL",
                "DEAL_TO_HOST", "STDLIB_PARAMETER", "STDLIB_RETURN",
                "EXTERNAL_PARAMETER", "EXTERNAL_RETURN", "JSON_FROM_FIELD",
                "JSON_TO_FIELD"),
            Arrays.stream(BoundaryKind.values()).map(Enum::name).toList(),
            "the closed BoundaryKind set keeps its 27 members in the pinned "
                + "order");
        checkEq(List.of("C_FFI_TO_DEAL", "DEAL_TO_C_FFI"),
            BoundaryKind.RESERVED_NAMES,
            "the reserved boundary names are the two C-FFI crossings");
        check(!BoundaryKind.isReservedName("BYTE_ELEMENT_ASSIGNMENT")
                && !BoundaryKind.isReservedName("BYTE_ELEMENT_READ"),
            "the realized bytes element cells are never reserved names");
        for (String reserved : BoundaryKind.RESERVED_NAMES) {
            check(BoundaryKind.isReservedName(reserved),
                reserved + " stays a reserved name");
        }
    }

    // =========================================================================
    // 2. One live negative seed per guard (+ the atomic-staging property)
    // =========================================================================

    /** One hand-built inconsistent fact: the closure without one owner. */
    private record StrippedClosure(CheckedProjectInput project,
        ProjectInterfaceIndex index, List<SemanticRequirementManifest> manifests) {
    }

    /**
     * Removes one module from the closure, the interface index, and the
     * manifests (the hand-built inconsistent fact of R8 candidates (c)
     * and (d)): the checker-resolved facts stay, the lowered/declared
     * owner does not.
     */
    private static StrippedClosure stripModule(Fixture fixture, ModuleId owner) {
        List<CheckedModuleInput> modules = new ArrayList<>();
        for (CheckedModuleInput module : fixture.checkedProject().modules()) {
            if (!module.moduleId().equals(owner)) {
                modules.add(module);
            }
        }
        Map<ModuleId, ExternalModuleInterface> indexModules = new LinkedHashMap<>();
        for (Map.Entry<ModuleId, ExternalModuleInterface> entry
                : fixture.index().modules().entrySet()) {
            if (!entry.getKey().equals(owner)) {
                indexModules.put(entry.getKey(), entry.getValue());
            }
        }
        List<SemanticRequirementManifest> manifests = new ArrayList<>();
        for (SemanticRequirementManifest manifest : fixture.manifests()) {
            if (!manifest.moduleId().equals(owner)) {
                manifests.add(manifest);
            }
        }
        CompilerInvocation invocation = productionInvocation();
        return new StrippedClosure(
            new CheckedProjectInput(invocation,
                fixture.checkedProject().entryModule(), modules,
                invocation.releaseStateHash()),
            new ProjectInterfaceIndex(ProjectInterfaceIndex.FORMAT_VERSION,
                indexModules),
            manifests);
    }

    private static void testConstructUnloweredSeed() throws Exception {
        System.out.println("-- R8(c): the cross-module callee whose module is "
            + "neither lowered nor recorded fails CONSTRUCT_UNLOWERED, "
            + "stages nothing, and preserves the previous artifact set on "
            + "both production targets --");
        Fixture fixture = crossModuleCallFixture();
        try {
            StrippedClosure stripped = stripModule(fixture, new ModuleId("lib"));
            for (Backend backend : NEGATIVE_TARGETS) {
                String target = backend.name();
                // The control: the intact fixture emits, so the stripped fact
                // is the seed's cause.
                PublicationStager intactStager = PublicationStager.forRoot(
                    fixture.root().resolve("out-intact-" + target));
                try {
                    ProductionProjectEmission.Result intact = emit(fixture,
                        fixture.checkedProject(), fixture.index(),
                        fixture.manifests(), fixture.externCModules(),
                        intactStager, backend);
                    check(intact.emitted(),
                        target + ": the intact cross-module call emits (the "
                            + "seed's control): " + intact.diagnostics());
                } finally {
                    intactStager.discard();
                }
                Path out = fixture.root().resolve("out-arm-" + target);
                Map<String, String> before = seedPreviousArtifactSet(out);
                PublicationStager stager = PublicationStager.forRoot(out);
                ProductionProjectEmission.Result result;
                try {
                    result = emit(fixture, stripped.project(), stripped.index(),
                        stripped.manifests(), fixture.externCModules(), stager,
                        backend);
                    check(stager.stagedSet().relativePaths().isEmpty(),
                        target + ": the undeclared-external-entry seed stages "
                            + "nothing");
                } finally {
                    stager.discard();
                }
                check(!result.emitted(),
                    target + ": the undeclared-external-entry seed fails "
                        + "closed");
                checkEq(1, result.diagnostics().size(),
                    target + ": exactly one diagnostic: " + result.diagnostics());
                String message = result.firstDiagnostic() == null ? ""
                    : result.firstDiagnostic().message();
                check("E6005".equals(result.firstDiagnostic() == null ? ""
                        : result.firstDiagnostic().code())
                        && message.contains("validatorRule CONSTRUCT_UNLOWERED")
                        && (message.contains("no recorded EXTERNAL_ENTRY")
                            || message.contains("no callee-route record")),
                    target + ": the seed names E6005 CONSTRUCT_UNLOWERED with "
                        + "the missing callee fact: " + result.diagnostics());
                checkEq(before, snapshotTree(out),
                    target + ": the failing lowering leaves the previous "
                        + "artifact set byte-identical");
            }
        } finally {
            deleteRecursively(fixture.root());
        }
    }

    private static void testRetainedAbiDeferredSeed() throws Exception {
        System.out.println("-- R8(d): a hand-built inconsistent fact (the class "
            + "literal whose owner is neither lowered nor declaration-typed) "
            + "fails RETAINED_ABI_DEFERRED, stages nothing, and preserves the "
            + "previous artifact set on both production targets --");
        Fixture fixture = importedLiteralFixture();
        try {
            // The hand-built inconsistency: the owner module leaves the
            // closure, the interface index, and the manifests, so the
            // checker-resolved class id has no lowered implementation and
            // no declaration-typed owner.
            StrippedClosure stripped = stripModule(fixture, new ModuleId("util"));
            for (Backend backend : NEGATIVE_TARGETS) {
                String target = backend.name();
                // The control: the intact in-project class literal emits, so
                // the stripped owner is the seed's cause.
                PublicationStager intactStager = PublicationStager.forRoot(
                    fixture.root().resolve("out-intact-" + target));
                try {
                    ProductionProjectEmission.Result intact = emit(fixture,
                        fixture.checkedProject(), fixture.index(),
                        fixture.manifests(), fixture.externCModules(),
                        intactStager, backend);
                    check(intact.emitted(),
                        target + ": the intact imported-class literal emits "
                            + "(the seed's control): " + intact.diagnostics());
                } finally {
                    intactStager.discard();
                }
                Path out = fixture.root().resolve("out-arm-" + target);
                Map<String, String> before = seedPreviousArtifactSet(out);
                PublicationStager stager = PublicationStager.forRoot(out);
                ProductionProjectEmission.Result result;
                try {
                    result = emit(fixture, stripped.project(), stripped.index(),
                        stripped.manifests(), fixture.externCModules(), stager,
                        backend);
                    check(stager.stagedSet().relativePaths().isEmpty(),
                        target + ": the RETAINED_ABI_DEFERRED seed stages "
                            + "nothing");
                } finally {
                    stager.discard();
                }
                check(!result.emitted(),
                    target + ": the RETAINED_ABI_DEFERRED seed fails closed");
                checkEq(1, result.diagnostics().size(),
                    target + ": exactly one diagnostic: " + result.diagnostics());
                check(result.firstDiagnostic() != null
                        && "E6005".equals(result.firstDiagnostic().code())
                        && result.firstDiagnostic().message()
                            .contains("validatorRule RETAINED_ABI_DEFERRED"),
                    target + ": the seed names E6005 RETAINED_ABI_DEFERRED: "
                        + result.diagnostics());
                checkEq(before, snapshotTree(out),
                    target + ": the failing lowering leaves the previous "
                        + "artifact set byte-identical");
            }
        } finally {
            deleteRecursively(fixture.root());
        }
    }

    private static void testSharedEmitterCoverageSeed() throws Exception {
        System.out.println("-- R8(c): a corrupted extern-C fact (one alias bound "
            + "to two provider modules) fails "
            + "SHARED_EMITTER_COVERAGE/FFI_PROVIDER_GAP, stages nothing, and "
            + "preserves the previous artifact set (the LuaJIT extern-C "
            + "emission; the JVM extern-C rejection is its own sanctioned "
            + "E6006 contract and is not substituted here) --");
        Fixture fixture = externCProviderFixture();
        try {
            check(fixture.externCModules().containsKey(NATIVE_PROBE)
                    && fixture.surface().moduleIds().contains(HOST_HELPER),
                "the fixture publishes the extern-C metadata and the host "
                    + "provider declaration");
            FfiGeneratedModule original = fixture.externCModules().get(NATIVE_PROBE);
            check(original != null && !original.bindings().importedFunctions().isEmpty(),
                "the generated metadata carries the provider reference");
            if (original == null || original.bindings().importedFunctions().isEmpty()) {
                return;
            }
            FfiImportedFunctionReference first =
                original.bindings().importedFunctions().get(0);
            // The corruption: the same alias is bound to a second provider
            // module (one alias resolves exactly one provider).
            FfiImportedFunctionReference conflicting =
                new FfiImportedFunctionReference(first.importAlias(),
                    first.exportName(), "other.provider",
                    first.canonicalDescriptor(), first.providerContractDigest(),
                    first.graphOrder(), first.sourceRange());
            FfiForwardBindings doctoredBindings = new FfiForwardBindings(
                original.bindings().moduleKey(), FfiBindingState.UNBOUND,
                original.bindings().cells(), List.of(first, conflicting), List.of());
            FfiGeneratedModule doctored = new FfiGeneratedModule(
                original.modulePath(), original.descriptor(),
                original.cdefBundle(), original.plans(), doctoredBindings);
            Map<ModuleId, FfiGeneratedModule> corrupted = new LinkedHashMap<>();
            corrupted.put(NATIVE_PROBE, doctored);

            // The control: the intact extern-C fact emits, so the corrupted
            // alias binding is the seed's cause.
            PublicationStager intactStager = PublicationStager.forRoot(
                fixture.root().resolve("out-intact"));
            try {
                ProductionProjectEmission.Result intact = emit(fixture,
                    fixture.checkedProject(), fixture.index(),
                    fixture.manifests(), fixture.externCModules(), intactStager,
                    Backend.LUAJIT);
                check(intact.emitted(),
                    "the intact extern-C fact emits (the seed's control): "
                        + intact.diagnostics());
            } finally {
                intactStager.discard();
            }

            Path out = fixture.root().resolve("out-arm");
            Map<String, String> before = seedPreviousArtifactSet(out);
            PublicationStager stager = PublicationStager.forRoot(out);
            ProductionProjectEmission.Result result;
            try {
                result = emit(fixture, fixture.checkedProject(), fixture.index(),
                    fixture.manifests(), corrupted, stager, Backend.LUAJIT);
                check(stager.stagedSet().relativePaths().isEmpty(),
                    "the SHARED_EMITTER_COVERAGE seed stages nothing");
            } finally {
                stager.discard();
            }
            check(!result.emitted(),
                "the SHARED_EMITTER_COVERAGE seed fails closed");
            checkEq(1, result.diagnostics().size(),
                "exactly one diagnostic: " + result.diagnostics());
            String message = result.firstDiagnostic() == null ? ""
                : result.firstDiagnostic().message();
            check("E6005".equals(result.firstDiagnostic() == null ? ""
                    : result.firstDiagnostic().code())
                    && message.contains("validatorRule SHARED_EMITTER_COVERAGE")
                    && message.contains(ProductionProjectEmission.FFI_PROVIDER_GAP)
                    && message.contains("other.provider"),
                "the seed names the provider-gap projection: "
                    + result.diagnostics());
            checkEq(before, snapshotTree(out),
                "the failing emission leaves the previous artifact set "
                    + "byte-identical");
        } finally {
            deleteRecursively(fixture.root());
        }
    }

    // =========================================================================
    // 3. The realized-construct probes (both production targets)
    // =========================================================================

    private static final String MANIFEST = """
        {
          "languageVersion": "1.2",
          "moduleRoots": ["src"],
          "output": "out",
          "backend": "luajit"
        }
        """;

    private static final String BYTES_PROBE = """
        import * as console from "std/console"

        export function main(): null {
          let b: bytes = bytes(4);
          b[0] = 7;
          b[3] = 250;
          let n: int = b[0] + b[3] + b.length;
          if (n === 261) {
            console.log("PROBE|BYTES|ok");
          } else {
            console.log("PROBE|BYTES|bad");
          }
          return null;
        }
        """;

    /**
     * The residual carrier probe (R4 mechanism 1): the self-recursive
     * function declaration lives <em>inside</em> the executing body — the
     * residual local-declaration mechanism — and the probe asserts its
     * terminal result and call count on both production targets (the
     * captured counter proves the local body executed once per call).
     */
    private static final String RESIDUAL_PROBE = """
        import * as console from "std/console"

        export function main(): null {
          let calls: int = 0;
          function down(n: int): int {
            calls = calls + 1;
            if (n === 0) {
              return 0;
            }
            return down(n - 1);
          }
          let r: int = down(4);
          if (r === 0 && calls === 5) {
            console.log("PROBE|RESIDUAL|ok");
          } else {
            console.log("PROBE|RESIDUAL|bad");
          }
          return null;
        }
        """;

    private static final String CAPTURE_PROBE = """
        import * as console from "std/console"

        export function main(): null {
          let fs: (() => int)[] = [function(): int { return -1; }, function(): int { return -1; }, function(): int { return -1; }, function(): int { return -1; }];
          for (let i: int = 0; i < 4; i = i + 1) {
            fs[i] = function(): int { return i; };
          }
          let total: int = fs[0]() + fs[1]() + fs[2]() + fs[3]();
          if (total === 6) {
            console.log("PROBE|CAPTURE|ok");
          } else {
            console.log("PROBE|CAPTURE|bad");
          }
          return null;
        }
        """;

    /**
     * The {@code @jsonable} helper probe: a checker-valid same-module
     * {@code Person$toJson}/{@code Person$fromJson} reference pair (the
     * corpus shapes {@code jsonable/jsonable-roundtrip.deal} and
     * {@code lua-abi-structural/jsonable-helper-export-keys.deal} carry).
     * The same-module reference form is the landed production path
     * (ISSUE-0698; the callable generated helper binding); the probe
     * compiles and executes it on both targets.
     */
    private static final String JSONABLE_PROBE = """
        import * as console from "std/console"

        // @jsonable
        export class Person {
          name: string = "";
          score: int = 0;
        }

        export function main(): null {
          let p: Person = { name: "Carol", score: 7 };
          let s: string = Person$toJson(p);
          let q: Person | null = Person$fromJson(s);
          if (q !== null) {
            if (q.name === "Carol" && q.score === 7) {
              console.log("PROBE|JSONABLE|ok");
            } else {
              console.log("PROBE|JSONABLE|bad");
            }
          } else {
            console.log("PROBE|JSONABLE|bad");
          }
          return null;
        }
        """;

    private static final String JSONABLE_CROSS_MODULE_LIB = """
        // @jsonable
        export class Widget {
          label: string = "";
          count: int = 0;
        }
        """;

    /**
     * The cross-module {@code @jsonable} helper-export probe (R3 items
     * (a)/(c)): the declaring module publishes each generated helper
     * through its export surface and records its {@code EXTERNAL_ENTRY},
     * and an importing module calls it through the landed
     * {@code EXPORT_READ} + {@code CALL(EXTERNAL)} shape.
     */
    private static final String JSONABLE_CROSS_MODULE_PROBE = """
        import * as console from "std/console"
        import * as Lib from "./lib"

        export function main(): null {
          let w: Lib.Widget = { label: "a", count: 1 };
          let s: string = Lib.Widget$toJson(w);
          let q: Lib.Widget | null = Lib.Widget$fromJson(s);
          if (q !== null) {
            if (q.label === "a" && q.count === 1) {
              console.log("PROBE|JSONABLE-XMOD|ok");
            } else {
              console.log("PROBE|JSONABLE-XMOD|bad");
            }
          } else {
            console.log("PROBE|JSONABLE-XMOD|bad");
          }
          return null;
        }
        """;

    private static final String TRANSFER_PROBE = """
        import * as console from "std/console"

        export function main(): null {
          let vals: int[] = [1, 2, 3, 4, 5];
          let sum: int = 0;
          for (let x: int of vals) {
            if (x === 2) {
              continue;
            }
            if (x === 4) {
              break;
            }
            sum = sum + x;
          }
          let found: int = 0;
          for (let i: int = 0; i < 5; i = i + 1) {
            try {
              if (i === 3) {
                break;
              }
            } catch (e) {
              found = -1;
            }
            found = found + 1;
          }
          if (sum === 4 && found === 3) {
            console.log("PROBE|TRANSFER|ok");
          } else {
            console.log("PROBE|TRANSFER|bad");
          }
          return null;
        }
        """;

    private static void testRealizedConstructProbes() throws Exception {
        System.out.println("-- the realized construct families compile through "
            + "the release-owned production invocation and execute under both "
            + "real toolchains with their oracle-agreed transcripts and no "
            + "guard outcome --");
        probe("bytes value semantics", BYTES_PROBE, "PROBE|BYTES|ok");
        probe("the residual carrier shape (a local self-recursive declared "
            + "body)", RESIDUAL_PROBE, "PROBE|RESIDUAL|ok");
        probe("the per-iteration closure capture", CAPTURE_PROBE,
            "PROBE|CAPTURE|ok");
        probe("the loop-target transfer protocol", TRANSFER_PROBE,
            "PROBE|TRANSFER|ok");
        probe("the same-module @jsonable helper operations", JSONABLE_PROBE,
            "PROBE|JSONABLE|ok");
        probeSources("the cross-module @jsonable helper exports",
            Map.of("src/main.deal", JSONABLE_CROSS_MODULE_PROBE,
                "src/lib.deal", JSONABLE_CROSS_MODULE_LIB),
            "PROBE|JSONABLE-XMOD|ok");

        // The outcome comparison's focused negative control: an artifact
        // that prints the pinned line and also leaks an unexpected stderr
        // transcript (or a nonzero status, or a divergent stdout) fails.
        check(!outcomeMatches(new ProcessOutcome(0, "PROBE|BYTES|ok\n",
                "noise\n"), null, "PROBE|BYTES|ok\n"),
            "the outcome comparison rejects an unexpected stderr transcript");
        check(!outcomeMatches(new ProcessOutcome(0, "PROBE|BYTES|ok\n", "x"),
                new OracleTranscript("PROBE|BYTES|ok\n", ""),
                "PROBE|BYTES|ok\n"),
            "the outcome comparison rejects a leaked stderr against the "
                + "oracle transcript");
        check(!outcomeMatches(new ProcessOutcome(1, "PROBE|BYTES|ok\n", ""),
                null, "PROBE|BYTES|ok\n"),
            "the outcome comparison rejects a nonzero artifact status");
        check(!outcomeMatches(new ProcessOutcome(0, "PROBE|BYTES|bad\n", ""),
                null, "PROBE|BYTES|ok\n"),
            "the outcome comparison rejects a divergent transcript");
        check(outcomeMatches(new ProcessOutcome(0, "PROBE|BYTES|ok\n", ""),
                null, "PROBE|BYTES|ok\n"),
            "the outcome comparison accepts the exact status/transcript");
    }

    private static void probe(String name, String source, String expectedLine)
            throws Exception {
        probeSources(name, Map.of("src/main.deal", source), expectedLine);
    }

    private static void probeSources(String name, Map<String, String> sources,
            String expectedLine) throws Exception {
        Path root = Files.createTempDirectory("guard-retarget-probe-");
        try {
            writeFileIn(root, "deal.json", MANIFEST);
            for (Map.Entry<String, String> source : sources.entrySet()) {
                writeFileIn(root, source.getKey(), source.getValue());
            }
            String entry = root.resolve("src/main.deal").toAbsolutePath().toString();
            String expectedStdout = expectedLine + "\n";

            // The oracle leg: the same sources, lowered by the release-owned
            // production invocation and executed by the semantic oracle, so
            // each artifact transcript below is compared against an
            // independent execution (terminal, console effects).
            OracleTranscript oracle = runOracle(name, root);
            if (oracle != null) {
                checkEq(expectedStdout, oracle.stdout(),
                    name + ": the oracle console transcript is the pinned "
                        + "probe line");
                checkEq("", oracle.stderr(),
                    name + ": the oracle records no stderr console effect");
            }

            // The decoy entrypoint of the classpath collision control: a
            // second class named Main that must lose against the generated
            // artifact whenever the generated classes come first.
            Path decoyClasses = compileDecoyMain(name, root);

            ProjectOutcome luaCompile = runProductionCli("compile", entry,
                "--backend", "lua", "--output",
                root.resolve("out-lua").toAbsolutePath().toString());
            check(luaCompile.exitCode() == 0,
                name + ": the LuaJIT production compile succeeds: "
                    + luaCompile.stderr());
            check(!luaCompile.stderr().contains("E6005")
                    && !luaCompile.stderr().contains("CONSTRUCT_UNLOWERED")
                    && !luaCompile.stderr().contains("RETAINED_ABI_DEFERRED")
                    && !luaCompile.stderr().contains("SHARED_EMITTER_COVERAGE"),
                name + ": the realized construct reports no guard outcome: "
                    + luaCompile.stderr());
            if (luaCompile.exitCode() == 0) {
                ProcessOutcome run = runProcess(
                    List.of("luajit", "main.lua"), root.resolve("out-lua"));
                assertArtifactOutcome(name + " (LuaJIT)", run, oracle,
                    expectedStdout);
            }

            Path jvmOut = root.resolve("out-jvm");
            ProjectOutcome jvmCompile = runProductionCli("compile", entry,
                "--backend", "jvm", "--output", jvmOut.toAbsolutePath().toString());
            check(jvmCompile.exitCode() == 0,
                name + ": the JVM production compile succeeds: "
                    + jvmCompile.stderr());
            check(!jvmCompile.stderr().contains("E6005"),
                name + ": the JVM realized construct reports no guard outcome: "
                    + jvmCompile.stderr());
            if (jvmCompile.exitCode() != 0) {
                return;
            }
            String classpath = absoluteClasspath();
            ProcessOutcome javac = runProcess(List.of("javac", "--release", "25",
                "-proc:none", "-cp", classpath, "-d", jvmOut.toString(),
                jvmOut.resolve("Main.java").toString()), root);
            checkEq(0, javac.exitCode(),
                name + ": the JVM artifact compiles (JLS 14.21 valid): "
                    + javac.output());
            if (javac.exitCode() != 0) {
                return;
            }
            // The generated classes precede every inherited classpath entry
            // (test/AGENTS.md), so the just-generated Main is selected.
            ProcessOutcome javaRun = runProcess(List.of("java", "-cp",
                jvmOut + File.pathSeparator + decoyClasses + File.pathSeparator
                    + classpath, "Main"), root);
            assertArtifactOutcome(name + " (JVM)", javaRun, oracle, expectedStdout);
            // The collision control is live: with the decoy first, the decoy
            // class is selected instead of the generated artifact, so the
            // assertion above is not vacuous.
            ProcessOutcome decoyFirst = runProcess(List.of("java", "-cp",
                decoyClasses + File.pathSeparator + jvmOut + File.pathSeparator
                    + classpath, "Main"), root);
            checkEq(0, decoyFirst.exitCode(),
                name + ": the collision control runs: " + decoyFirst.output());
            checkEq(DECOY_LINE + "\n", decoyFirst.stdout(),
                name + ": the collision control proves a preceding Main "
                    + "shadows the generated entrypoint");
            check(!outcomeMatches(decoyFirst, oracle, expectedStdout),
                name + ": the shadowed decoy fails the outcome comparison");
        } finally {
            deleteRecursively(root);
        }
    }

    /** One oracle execution's console transcripts, by channel. */
    private record OracleTranscript(String stdout, String stderr) {
    }

    /**
     * The oracle leg of one probe: the same sources compile through the
     * frontend under the release-owned production invocation, lower with
     * zero diagnostics, and execute in the semantic oracle; the run must
     * reach the null success terminal. Returns the console transcripts, or
     * {@code null} when the closure itself did not build.
     */
    private static OracleTranscript runOracle(String label, Path root)
            throws Exception {
        Path src = root.resolve("src");
        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            src.resolve("main.deal").toAbsolutePath(),
            root.resolve("out-oracle"),
            false, false, false, false, Backend.LUAJIT, Map.of(),
            List.of(src.toAbsolutePath()),
            Path.of("std").toAbsolutePath().normalize(),
            null, productionInvocation());
        boolean compiled = orchestrator.compile();
        CheckedProjectBuildResult built = orchestrator.checkedProject();
        RequirementManifestResult manifests = orchestrator.requirementManifests();
        HostDeclarationSurface surface = orchestrator.hostDeclarationSurface();
        check(compiled && built != null && built.input() != null
                && built.index() != null && !built.hasErrors()
                && manifests != null && manifests.manifests() != null
                && surface != null,
            label + ": the oracle closure compiles: "
                + (built == null ? String.valueOf(orchestrator.diagnostics())
                    : String.valueOf(built.diagnostics())));
        if (!compiled || built == null || built.input() == null
                || built.index() == null || built.hasErrors()
                || manifests == null || manifests.manifests() == null
                || surface == null) {
            return null;
        }
        SemanticLowerer.ProjectLoweringResult lowered = SemanticLowerer.lowerProject(
            productionInvocation(), built.input(), built.index(),
            manifests.manifests(), surface, Map.of(), Map.of(),
            BuiltinErrorDeclaration.synthesized(
                built.input().modules().get(0).ast().span()),
            List.of(IntrinsicKind.INT_CONVERT, IntrinsicKind.NUMBER_CONVERT,
                IntrinsicKind.BYTES_NEW),
            Set.of());
        check(lowered.project() != null,
            label + ": the oracle closure lowers with zero diagnostics: "
                + lowered.diagnostics());
        if (lowered.project() == null) {
            return null;
        }
        SemanticRuntimeModel.ConsumerRun run = SemanticOracle.executeProjectInits(
            lowered.project(), lowered.tables(), lowered.registries(), null);
        boolean success = run.terminal()
                instanceof SemanticRuntimeModel.Terminal.Success terminal
            && "null".equals(terminal.resultAtom());
        check(success, label + ": the oracle runs the probe entry to the null "
            + "terminal: " + run.terminal());
        return new OracleTranscript(
            consoleTranscript(run, SharedStdlibSemantics.Channel.STDOUT),
            consoleTranscript(run, SharedStdlibSemantics.Channel.STDERR));
    }

    /** The ordered console-effect transcript of one channel, line-per-effect. */
    private static String consoleTranscript(SemanticRuntimeModel.ConsumerRun run,
            SharedStdlibSemantics.Channel channel) {
        StringBuilder transcript = new StringBuilder();
        for (SemanticRuntimeModel.EffectEvent effect : run.effects()) {
            if (effect.kind() == SemanticRuntimeModel.EffectEvent.Kind.CONSOLE_WRITE
                    && effect.channel() == channel) {
                transcript.append(effect.text()).append('\n');
            }
        }
        return transcript.toString();
    }

    /** The decoy entrypoint line of the classpath collision control. */
    private static final String DECOY_LINE = "PROBE|DECOY|wrong-entrypoint";

    /** Compiles the decoy Main of the entrypoint collision control. */
    private static Path compileDecoyMain(String label, Path root) throws Exception {
        Path decoySource = root.resolve("decoy-src/Main.java");
        writeFileIn(root, "decoy-src/Main.java",
            "public final class Main {\n"
                + "  public static void main(String[] args) {\n"
                + "    System.out.println(\"" + DECOY_LINE + "\");\n"
                + "  }\n"
                + "}\n");
        Path decoyClasses = root.resolve("decoy-classes");
        Files.createDirectories(decoyClasses);
        ProcessOutcome decoyCompile = runProcess(List.of("javac", "--release", "25",
            "-proc:none", "-d", decoyClasses.toString(),
            decoySource.toString()), root);
        checkEq(0, decoyCompile.exitCode(),
            label + ": the collision-control decoy Main compiles: "
                + decoyCompile.output());
        return decoyClasses;
    }

    /**
     * The one artifact-outcome assertion: the status is zero, stdout equals
     * the oracle console transcript (or the pinned line when the oracle leg
     * failed), and stderr equals the oracle stderr transcript — asserted
     * separately, so an unexpected stderr transcript fails independently of
     * stdout.
     */
    private static void assertArtifactOutcome(String label, ProcessOutcome outcome,
            OracleTranscript oracle, String expectedStdout) {
        String expectedOut = oracle == null ? expectedStdout : oracle.stdout();
        String expectedErr = oracle == null ? "" : oracle.stderr();
        checkEq(0, outcome.exitCode(),
            label + ": the artifact exits 0: " + outcome.output());
        checkEq(expectedOut, outcome.stdout(),
            label + ": the artifact stdout equals the oracle console transcript");
        checkEq(expectedErr, outcome.stderr(),
            label + ": the artifact stderr equals the oracle stderr transcript");
        check(outcomeMatches(outcome, oracle, expectedStdout),
            label + ": the artifact outcome matches (status 0, oracle "
                + "transcript, empty stderr): " + outcome.output());
    }

    /** The single outcome comparison behind the artifact assertions and the
     * negative controls: status zero, the oracle transcript (or the pinned
     * line when the oracle leg failed), and an empty stderr. */
    private static boolean outcomeMatches(ProcessOutcome outcome,
            OracleTranscript oracle, String expectedStdout) {
        String expectedOut = oracle == null ? expectedStdout : oracle.stdout();
        String expectedErr = oracle == null ? "" : oracle.stderr();
        return outcome.exitCode() == 0
            && outcome.stdout().equals(expectedOut)
            && outcome.stderr().equals(expectedErr);
    }

    /**
     * The bounded-subprocess contract's focused checks: a hung child fails
     * hard with the named token after the deadline (never a skip), and a
     * child writing past the cap on both streams completes with the retained
     * prefix, the truncation marker, and the flags set.
     */
    private static void testBoundedProcessContract() throws Exception {
        System.out.println("-- the bounded test-process helper: a hung child "
            + "fails hard with BOUNDED_PROCESS_TIMEOUT and a flooded child is "
            + "capped with the truncation marker --");

        long before = System.nanoTime();
        boolean timedOut = false;
        try {
            BoundedProcess.runCaptured(List.of("luajit", "-e",
                "while true do end"), null, Map.of(), 1_000L);
        } catch (BoundedProcess.BoundedProcessTimeoutException timeout) {
            timedOut = true;
            checkEq("BOUNDED_PROCESS_TIMEOUT luajit", timeout.getMessage(),
                "the timeout path names the child and the hard token");
        }
        long elapsedMs = (System.nanoTime() - before) / 1_000_000L;
        check(timedOut, "the hung child is killed at the deadline, not "
            + "skipped");
        check(elapsedMs < 60_000L, "the timeout path returns promptly after "
            + "the deadline: " + elapsedMs + " ms");

        // A direct child that exits while a descendant keeps its inherited
        // output pipes open must not bypass the bound: the helper owns the
        // child's process group, kills the surviving member, and reports the
        // named timeout instead of waiting for the descendant.
        long descendantBefore = System.nanoTime();
        boolean descendantTimedOut = false;
        try {
            BoundedProcess.runCaptured(List.of("sh", "-c",
                "sleep 90 & sleep 0.02; exit 0"), null, Map.of(), 1_000L);
        } catch (BoundedProcess.BoundedProcessTimeoutException timeout) {
            descendantTimedOut = true;
            checkEq("BOUNDED_PROCESS_TIMEOUT sh", timeout.getMessage(),
                "the descendant-holding-pipe path names the child and the "
                    + "hard token");
        }
        long descendantElapsedMs =
            (System.nanoTime() - descendantBefore) / 1_000_000L;
        check(descendantTimedOut,
            "a child that exits while a descendant holds the output pipes "
                + "still ends with the named timeout, not success");
        check(descendantElapsedMs < 30_000L,
            "the descendant path returns promptly after terminating the "
                + "owned process group: " + descendantElapsedMs + " ms");

        BoundedProcess.BoundedResult flood = BoundedProcess.runCaptured(
            List.of("luajit", "-e",
                "io.write(string.rep('x', 2 * 1024 * 1024)); "
                    + "io.stderr:write(string.rep('y', 2 * 1024 * 1024))"));
        checkEq(0, flood.exitCode(),
            "the flooding child completes (the capped drains never deadlock "
                + "a full pipe): " + flood.stderr());
        check(flood.stdoutTruncated() && flood.stderrTruncated(),
            "both streams are flagged truncated past the cap");
        checkEq(BoundedProcess.STREAM_CAP_BYTES,
            flood.stdout().length() - BoundedProcess.TRUNCATION_MARKER.length(),
            "the retained stdout is exactly the 1 MiB cap");
        checkEq(BoundedProcess.STREAM_CAP_BYTES,
            flood.stderr().length() - BoundedProcess.TRUNCATION_MARKER.length(),
            "the retained stderr is exactly the 1 MiB cap");
        check(flood.stdout().endsWith(BoundedProcess.TRUNCATION_MARKER)
                && flood.stderr().endsWith(BoundedProcess.TRUNCATION_MARKER),
            "both streams carry the launcher's truncation marker at the "
                + "cut point");
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /** Pre-populates the live output root with a previous artifact set. */
    private static Map<String, String> seedPreviousArtifactSet(Path out)
            throws Exception {
        writeFileIn(out, "main.lua", "-- previous artifact\n");
        writeFileIn(out, "deal/runtime.lua", "-- previous runtime\n");
        Map<String, String> before = snapshotTree(out);
        check(!before.isEmpty(), "the previous artifact set is seeded");
        return before;
    }

    /** The published/previous artifact set of one tree: path → content. */
    private static Map<String, String> snapshotTree(Path root) throws Exception {
        Map<String, String> snapshot = new TreeMap<>();
        if (!Files.exists(root)) {
            return snapshot;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path file : walk.sorted().toList()) {
                if (Files.isRegularFile(file)) {
                    snapshot.put(root.relativize(file).toString().replace('\\', '/'),
                        Arrays.toString(Files.readAllBytes(file)));
                }
            }
        }
        return snapshot;
    }

    private record ProcessOutcome(int exitCode, String stdout, String stderr) {

        String output() {
            return "stdout=" + stdout.replace("\n", "\\n")
                + " stderr=" + stderr.replace("\n", "\\n");
        }
    }

    private record ProjectOutcome(int exitCode, String stderr) {
    }

    /** Runs the release-owned production CLI in-process. */
    private static ProjectOutcome runProductionCli(String... args)
            throws Exception {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        int exitCode;
        try {
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            exitCode = deal.Main.run(args);
            System.err.flush();
        } finally {
            System.setErr(originalErr);
        }
        return new ProjectOutcome(exitCode,
            err.toString(StandardCharsets.UTF_8).trim());
    }

    private static String absoluteClasspath() {
        StringBuilder resolved = new StringBuilder();
        for (String entry : System.getProperty("java.class.path", "")
                .split(File.pathSeparator)) {
            if (entry.isEmpty()) {
                continue;
            }
            if (resolved.length() > 0) {
                resolved.append(File.pathSeparator);
            }
            resolved.append(Path.of(entry).toAbsolutePath().normalize());
        }
        return resolved.toString();
    }

    private static ProcessOutcome runProcess(List<String> command, Path workDir)
            throws Exception {
        BoundedProcess.BoundedResult result = BoundedProcess.runCaptured(
            command, workDir);
        return new ProcessOutcome(result.exitCode(), result.stdout(),
            result.stderr());
    }

    private static void writeFileIn(Path root, String relative, String content)
            throws Exception {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private static void deleteRecursively(Path path) {
        if (path == null) {
            return;
        }
        try {
            if (Files.exists(path)) {
                try (Stream<Path> walk = Files.walk(path)) {
                    walk.sorted(Comparator.reverseOrder()).forEach(entry -> {
                        try {
                            Files.deleteIfExists(entry);
                        } catch (java.io.IOException ignored) {
                            // best effort
                        }
                    });
                }
            }
        } catch (java.io.IOException ignored) {
            // best effort
        }
    }

    // =========================================================================
    // Main
    // =========================================================================

    public static void main(String[] args) throws Exception {
        System.out.println("=== Fail-Closed Guard Retargeting Tests "
            + "(ISSUE-0702) ===\n");
        testGuardIdentifiersLanded();
        testConstructUnloweredSeed();
        testRetainedAbiDeferredSeed();
        testSharedEmitterCoverageSeed();
        testRealizedConstructProbes();
        testBoundedProcessContract();
        System.out.println();
        System.out.println("Passed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
        System.out.println("=== Fail-Closed Guard Retargeting Tests Passed ===");
    }
}
