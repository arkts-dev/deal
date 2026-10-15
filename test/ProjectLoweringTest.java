package deal.test;

import deal.ast.ClassField;
import deal.checker.BuiltinErrorDeclaration;
import deal.codegen.Backend;
import deal.diagnostics.CompilerDiagnostic;
import deal.diagnostics.DiagnosticRange;
import deal.diagnostics.RangeOrigin;
import deal.ffi.FfiGeneratedModule;
import deal.identity.CanonicalModuleIdentity;
import deal.module.CompilationOrchestrator;
import deal.semantic.CapabilityRegistry;
import deal.semantic.CheckedModuleInput;
import deal.semantic.CheckedProjectBuildResult;
import deal.semantic.CheckedProjectInput;
import deal.semantic.ClassConstructionValidator;
import deal.semantic.ClassRegistrationSeeds;
import deal.semantic.CompilerInvocation;
import deal.semantic.CompilerProfileProvider;
import deal.semantic.HostDeclarationSurface;
import deal.semantic.RequirementManifestResult;
import deal.semantic.SemanticLowerer;
import deal.semantic.SemanticRequirementManifest;
import deal.semantic.ir.AddressChainProtocol;
import deal.semantic.ir.AnchorId;
import deal.semantic.ir.BindingId;
import deal.semantic.ir.BindingCellKind;
import deal.semantic.ir.BlockId;
import deal.semantic.ir.BoundaryKind;
import deal.semantic.ir.ClassFactoryId;
import deal.semantic.ir.CanonicalJson;
import deal.semantic.ir.ClassBindingFacts;
import deal.semantic.ir.ClassFactoryRegistry;
import deal.semantic.ir.ClassId;
import deal.semantic.ir.ClassInterface;
import deal.semantic.ir.ClassLayout;
import deal.semantic.ir.ContractSnapshotCanonicalizer;
import deal.semantic.ir.DefaultOwner;
import deal.semantic.ir.ExecutableLoweredProject;
import deal.semantic.ir.ExportPlan;
import deal.semantic.ir.ExternalModuleInterface;
import deal.semantic.ir.ExportInterface;
import deal.semantic.ir.ExternalModuleKind;
import deal.semantic.ir.FieldInterface;
import deal.semantic.ir.FailureContractRegistry;
import deal.semantic.ir.FunctionAllocationIdentity;
import deal.semantic.ir.FunctionExecutionBinding;
import deal.semantic.ir.IntrinsicKind;
import deal.semantic.ir.JsonDefaultChildTable;
import deal.semantic.ir.KindPayload;
import deal.semantic.ir.LoweredModuleUnit;
import deal.semantic.ir.LoweringContextHash;
import deal.semantic.ir.LoweringFailureDetail;
import deal.semantic.ir.ModuleId;
import deal.semantic.ir.ModuleImportKind;
import deal.semantic.ir.ModuleInitPlan;
import deal.semantic.ir.NamespaceRegistration;
import deal.semantic.ir.NamespaceRegistrations;
import deal.semantic.ir.OpId;
import deal.semantic.ir.ProjectInterfaceIndex;
import deal.semantic.ir.RawUnit;
import deal.semantic.ir.ResolvedImport;
import deal.semantic.ir.ReleaseState;
import deal.semantic.ir.RuntimeDescriptor;
import deal.semantic.ir.SemanticCapability;
import deal.semantic.ir.SemanticIdAllocator;
import deal.semantic.ir.SemanticIrDumper;
import deal.semantic.ir.SemanticIrTextDecodeException;
import deal.semantic.ir.SemanticIrValidator;
import deal.semantic.ir.SemanticOp;
import deal.semantic.ir.SemanticOpKind;
import deal.semantic.ir.SemanticProfile;
import deal.semantic.ir.SourceOrigin;
import deal.semantic.ir.SourceOriginKind;
import deal.semantic.ir.SourceSpan;
import deal.semantic.ir.SharedFactoryFacts;
import deal.semantic.ir.StructuredBodyTable;
import deal.semantic.ir.ValueId;
import deal.types.Type;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class ProjectLoweringTest {

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

    private static void fail(String message) {
        failed++;
        System.err.println("FAIL: " + message);
    }

    private static void checkEq(Object expected, Object actual, String message) {
        check(java.util.Objects.equals(expected, actual),
            message + " (expected " + expected + ", got " + actual + ")");
    }

    /**
     * Asserts that the action rejects with an
     * {@link IllegalArgumentException} — the producer-defect contract of
     * the lowering-result boundaries.
     */
    private static void expectIllegalArgument(Runnable action, String message) {
        try {
            action.run();
            fail(message + " (expected IllegalArgumentException, got no failure)");
        } catch (IllegalArgumentException expected) {
            passed++;
        } catch (RuntimeException other) {
            fail(message + " (expected IllegalArgumentException, got "
                + other.getClass().getSimpleName() + ": " + other.getMessage()
                + ")");
        }
    }

    // =========================================================================
    // Fixtures
    // =========================================================================

    private static final ModuleId UTIL = new ModuleId("util");
    private static final ModuleId APP = new ModuleId("app");
    private static final ModuleId HOST_CFG = new ModuleId("host.cfg");
    private static final CanonicalModuleIdentity HOST_CFG_IDENTITY =
        new CanonicalModuleIdentity.ExternalModule("host/cfg");
    private static final String HOST_CFG_SPECIFIER = "host/cfg";
    private static final ClassId ENDPOINT = new ClassId("$external/host/cfg",
        "Endpoint");
    private static final ClassId HOST_HIDDEN = new ClassId("$external/host/cfg",
        "Hidden");
    private static final ClassId BUILTIN_ERROR = ClassId.ERROR;

    private static final String UTIL_SOURCE = """
        export function add(a: int, b: int): int {
          return a + b;
        }

        export function twice(x: int): int {
          return x * 2;
        }

        // A module-level recursive group (the recursive-group arm's closed
        // member-body shape: value references only, never invoked from
        // source).
        function left(): null {
          let rightRef: () => null = right;
          return null;
        }
        function right(): null {
          let leftRef: () => null = left;
          return null;
        }
        """;

    private static final String HOST_DECLARATION = """
        export class Endpoint {
          path: string;
          port?: int;
        }

        export function version(): int;
        """;

    /** The main fixture: source calls, an adapter, cross-module calls, and a host import. */
    private static final String APP_SOURCE = """
        import * as u from "util";
        import * as cfg from "host/cfg";

        function inc(x: int): int {
          return x + 1;
        }

        function helper(x: int): int {
          return u.add(x, 1);
        }

        export function main(): null {
          let f: (x: int) => int = inc;
          let h: (a: int, b: int) => int = f;
          let total: int = helper(2);
          let extended: int = h(3, 9);
          let doubled: int = u.twice(3);
          return null;
        }
        """;

    /** The host-declaration-class construction (the fail-closed acceptance). */
    private static final String HOST_CLASS_APP_SOURCE = """
        import * as u from "util";
        import * as cfg from "host/cfg";

        export function main(): null {
          let e: cfg.Endpoint = {path: "p"};
          return null;
        }
        """;

    /**
     * The host declaration with a class-typed field: {@code Endpoint.peer}
     * names the exported declaration class {@code Hidden} through its exact
     * carried class identity, so the seed descriptor's external spelling is
     * the only fact that distinguishes it from a same-rendering alternate.
     */
    private static final String HOST_CLASS_REFERENCE_DECLARATION = """
        export class Hidden {
          secret: string;
        }

        export class Endpoint {
          path: string;
          peer: Hidden;
        }

        export function version(): int;
        """;

    /**
     * The class-reference construction: only {@code Hidden} is constructed, so
     * the mutated {@code Endpoint.peer} seed reference has no referring
     * {@code CLASS_NEW} and only the seed/interface coherence rule can reject
     * it (the reviewer's exact reproducer).
     */
    private static final String HOST_CLASS_REFERENCE_APP_SOURCE = """
        import * as cfg from "host/cfg";

        export function main(): null {
          let h: cfg.Hidden = {secret: "s"};
          return null;
        }
        """;

    /** The builtin-Error construction (the production-outcome acceptance). */
    private static final String ERROR_APP_SOURCE = """
        import * as cfg from "host/cfg";

        export function main(): null {
          let e: Error = {code: "E1", message: "boom"};
          return null;
        }
        """;

    /**
     * The in-project class arm: an implementation module declares an
     * exported class and a local class and constructs both, so the unified
     * walk's class-declaration arm, its class-literal arm, and the exported
     * variant's {@code CLASS_FACTORY} run in one session with the call and
     * closure arms.
     */
    private static final String CLASS_UTIL_SOURCE = """
        export class Point {
          x: int = 0;
          y: int = 1;
        }

        class Hidden {
          label: string = "hidden";
        }

        export function makePoint(x: int): Point {
          let p: Point = {x: x};
          let h: Hidden = {label: "local"};
          return p;
        }
        """;

    private static final String CLASS_APP_SOURCE = """
        import * as u from "util";

        export function main(): null {
          let p: u.Point = u.makePoint(4);
          return null;
        }
        """;

    private static final String IMPORTED_CLASS_UTIL_SOURCE = """
        export class Point {
          x: int = 0;
          y: int = 1;
        }

        export class Segment {
          length: int = 0;
        }

        export function makePoint(x: int): Point {
          return {x: x};
        }
        """;

    private static final String IMPORTED_CLASS_APP_SOURCE = """
        import * as u from "util";

        export function main(): null {
          let p: u.Point = {x: 5};
          let s: u.Segment = {length: 3};
          let q: u.Point = u.makePoint(7);
          return null;
        }
        """;

    private static final ClassId SEGMENT = new ClassId("src", "Segment");

    private static final ModuleId MID = new ModuleId("mid");

    private static final String OWNERSHIP_UTIL_SOURCE = """
        export class Point {
          x: int = 0;
          y: int = 1;
        }

        export function makeCounter(start: int): () => int {
          let n: int = start;
          function bump(): int {
            n = n + 1;
            return n;
          }
          return bump;
        }
        """;

    private static final String OWNERSHIP_MID_SOURCE = """
        export function answer(): int {
          return 42;
        }
        """;

    private static final String OWNERSHIP_APP_SOURCE = """
        import * as u from "util";
        import * as m from "mid";

        export function main(): null {
          let p: u.Point = {x: 4};
          let c: () => int = u.makeCounter(1);
          let v: int = m.answer();
          return null;
        }
        """;

    // The compiler's canonical module path of a root-contained module is
    // the configured root text plus the source's relative directory
    // components (the file name is omitted), so both classes of the
    // fixture — declared in the flat `src` root — carry `@src/<Name>`:
    // the identity the checker, the declaration surface, the checked
    // project's interface index, and the legacy emission all produce.
    private static final ClassId POINT = new ClassId("src", "Point");
    private static final ClassId HIDDEN = new ClassId("src", "Hidden");

    /**
     * The extern-C declaration module of the fail-closed acceptance (the
     * {@code FFI_PLAN} seed owner): a C-struct class with its phase-3.9
     * generated plan and a declared C function.
     */
    private static final String NATIVE_DECLARATION = """
        // @extern-c

        // @c-struct
        export class Vec2 {
          x: number = 0.0;
          y: number = 0.0;
        }

        export function ffi_pair_sum(value: Vec2): number;
        """;

    private static final ModuleId NATIVE = new ModuleId("native.math");
    private static final String NATIVE_SPECIFIER = "native/math";
    private static final ClassId NATIVE_VEC2 =
        new ClassId("$external/native/math", "Vec2");

    /** The extern-C declaration-class construction (the fail-closed acceptance). */
    private static final String EXTERN_C_CLASS_APP_SOURCE = """
        import * as native from "native/math";

        export function main(): null {
          let v: native.Vec2 = {x: 1.0, y: 2.0};
          return null;
        }
        """;

    // =========================================================================
    // The real-project harness (the production frontend + the project entry)
    // =========================================================================

    private record RealProject(
        Path root,
        CompilationOrchestrator orchestrator,
        CheckedProjectInput checkedProject,
        ProjectInterfaceIndex index,
        List<SemanticRequirementManifest> manifests,
        HostDeclarationSurface surface,
        Map<ModuleId, FfiGeneratedModule> externCModules,
        Map<ModuleId, CanonicalModuleIdentity> declarationIdentities) {
    }

    private static RealProject compileProject(String appSource) throws Exception {
        return compileProject(appSource, Map.of(), Map.of());
    }

    /**
     * The generalized fixture harness: {@code extraSources} writes further
     * (or replacement) module files relative to the project root and
     * {@code extraExternals} adds externals declarations
     * (raw specifier &rarr; project-relative declaration path).
     */
    private static RealProject compileProject(String appSource,
            Map<String, String> extraSources,
            Map<String, String> extraExternals) throws Exception {
        Path proj = Files.createTempDirectory("project-lowering");
        writeFileIn(proj, "src/cfg.d.deal", HOST_DECLARATION);
        writeFileIn(proj, "src/util.deal", UTIL_SOURCE);
        writeFileIn(proj, "src/app.deal", appSource);
        for (Map.Entry<String, String> extra : extraSources.entrySet()) {
            writeFileIn(proj, extra.getKey(), extra.getValue());
        }
        Path entry = proj.resolve("src/app.deal").toAbsolutePath();
        Path output = proj.resolve("out").toAbsolutePath();
        Map<String, String> externals = new LinkedHashMap<>();
        externals.put(HOST_CFG_SPECIFIER,
            proj.resolve("src/cfg.d.deal").toAbsolutePath().toString());
        for (Map.Entry<String, String> extra : extraExternals.entrySet()) {
            externals.put(extra.getKey(),
                proj.resolve(extra.getValue()).toAbsolutePath().toString());
        }

        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            entry, output, false, false, false, false, Backend.LUAJIT, externals,
            List.of(proj.resolve("src").toAbsolutePath()),
            Path.of(".").toAbsolutePath().normalize(), null,
            ConformanceHarnessMetadata.invocation());
        boolean compiled = orchestrator.compile();
        check(compiled, "the fixture project compiles through the production "
            + "pipeline: " + orchestrator.diagnostics());
        CheckedProjectBuildResult built = orchestrator.checkedProject();
        check(built != null && !built.hasErrors() && built.input() != null
                && built.index() != null,
            "the production checked-project builder produced the closure and the "
                + "index: " + (built == null ? "null" : built.diagnostics()));
        RequirementManifestResult manifests = orchestrator.requirementManifests();
        if (built == null || built.hasErrors() || built.input() == null
                || built.index() == null || manifests == null
                || manifests.manifests() == null) {
            deleteRecursively(proj);
            throw new IllegalStateException("the fixture project did not build");
        }
        Map<ModuleId, FfiGeneratedModule> externC = new LinkedHashMap<>();
        for (Map.Entry<String, FfiGeneratedModule> generated
                : orchestrator.ffiGenerations().entrySet()) {
            externC.put(new ModuleId(generated.getKey()), generated.getValue());
        }
        Map<ModuleId, CanonicalModuleIdentity> identities = new LinkedHashMap<>();
        for (ModuleId declarationModule : orchestrator.hostDeclarationSurface()
                .moduleIds()) {
            // An externals-listed declaration module's public identity is
            // the externals map key exactly as written (the module-identity
            // layer's pinned rule); a project-classified declaration module
            // carries the standalone identity of its dotted module path.
            String specifier = null;
            for (String candidate : externals.keySet()) {
                if (candidate.replace('/', '.').equals(declarationModule.path())) {
                    specifier = candidate;
                    break;
                }
            }
            identities.put(declarationModule, specifier == null
                ? IdentityTestFixtures.moduleIdentityOf(declarationModule.path())
                : new CanonicalModuleIdentity.ExternalModule(specifier));
        }
        return new RealProject(proj, orchestrator, built.input(), built.index(),
            manifests.manifests(), orchestrator.hostDeclarationSurface(), externC,
            identities);
    }

    private record LoweredProject(RealProject project,
                                  SemanticLowerer.ProjectLoweringResult result) {
    }

    private static CompilerInvocation invocation() {
        return CompilerProfileProvider.resolve(ReleaseState.V1_3_ACTIVE,
            CapabilityRegistry.releaseRegistry());
    }

    /** One {@link SemanticLowerer#lowerProject} call over the real project. */
    private static SemanticLowerer.ProjectLoweringResult lower(
            RealProject project, CompilerInvocation invocation) {
        return SemanticLowerer.lowerProject(invocation, project.checkedProject(),
            project.index(), project.manifests(), project.surface(),
            project.declarationIdentities(), project.externCModules(),
            BuiltinErrorDeclaration.synthesized(
                project.checkedProject().modules().get(0).ast().span()),
            List.of(IntrinsicKind.INT_CONVERT, IntrinsicKind.NUMBER_CONVERT,
                IntrinsicKind.BYTES_NEW),
            Set.of());
    }

    // =========================================================================
    // 1. The one project lowering and determinism
    // =========================================================================

    private static void testOneProjectLoweringAndDeterminism(
            LoweredProject baseline) throws Exception {
        System.out.println("-- the one project lowering: closure, entry, one "
            + "allocator, byte-identical repeats --");
        RealProject project = baseline.project();
        {
            checkEq(List.of(UTIL, APP),
                project.checkedProject().modules().stream()
                    .map(CheckedModuleInput::moduleId).toList(),
                "the checked closure carries the implementation modules in "
                    + "dependency order");

            SemanticLowerer.ProjectLoweringResult first = baseline.result();
            SemanticLowerer.ProjectLoweringResult second = lower(project, invocation());
            check(!first.hasErrors() && first.project() != null,
                "the project lowers to exactly one project: " + first.diagnostics());
            check(second.project() != null, "the repeated lowering lowers too");
            if (first.project() == null || second.project() == null) {
                return;
            }
            ExecutableLoweredProject lowered = first.project();
            checkEq(SemanticProfile.DEAL_V1_3_INT32, lowered.semanticProfile(),
                "the project carries the DEAL_V1_3_INT32 profile");
            checkEq(List.of(UTIL, APP), List.copyOf(lowered.modules().keySet()),
                "the project's module insertion order is the dependency order");
            checkEq(APP, lowered.entryModule(), "the entry module is present");
            check(lowered.modules().containsKey(lowered.entryModule()),
                "the entry module is a member of the closure");

            // One table and one registry per module; nothing else escapes.
            checkEq(Set.of(UTIL, APP), first.tables().keySet(),
                "the result carries one block-membership table per module");
            checkEq(Set.of(UTIL, APP), first.registries().keySet(),
                "the result carries one class-factory registry per module");

            // Globally unique ids from one allocator.
            Set<Long> opIds = new LinkedHashSet<>();
            int ops = 0;
            for (LoweredModuleUnit unit : lowered.modules().values()) {
                for (SemanticOp op : unit.ops()) {
                    ops++;
                    check(op.opId().module().equals(unit.moduleId()),
                        "every op id carries its producing module");
                    if (!opIds.add(op.opId().id())) {
                        fail("the op id " + op.opId() + " is issued twice");
                    }
                }
            }
            check(ops > 0 && opIds.size() == ops,
                "the ids are issued once per compilation and globally unique (" + ops
                    + " ops, " + opIds.size() + " distinct ids)");

            // Byte-identical repeated project dumps.
            SemanticIrDumper.ProjectDump firstDump = SemanticIrDumper.dumpProject(lowered);
            SemanticIrDumper.ProjectDump secondDump =
                SemanticIrDumper.dumpProject(second.project());
            check(java.util.Arrays.equals(firstDump.manifest(), secondDump.manifest()),
                "the repeated project manifests are byte-identical");
            check(firstDump.modules().size() == secondDump.modules().size(),
                "both dumps carry every module");
            for (int i = 0; i < firstDump.modules().size(); i++) {
                check(firstDump.modules().get(i).moduleId()
                        .equals(secondDump.modules().get(i).moduleId())
                        && java.util.Arrays.equals(
                            firstDump.modules().get(i).bytes(),
                            secondDump.modules().get(i).bytes()),
                    "the repeated dump of module "
                        + firstDump.modules().get(i).moduleId() + " is byte-identical");
            }
        }
    }

    // =========================================================================
    // 2. The seeds, the intrinsic bindings, and the namespace registrations
    // =========================================================================

    private static void testSeedsIntrinsicsAndNamespaces(LoweredProject baseline)
            throws Exception {
        System.out.println("-- the seeds, the intrinsic bindings, and the "
            + "namespace registrations --");
        RealProject project = baseline.project();
        {
            SemanticLowerer.ProjectLoweringResult result = baseline.result();
            if (result.project() == null) {
                fail("the project lowers: " + result.diagnostics());
                return;
            }
            // The class registration seeds: the compiler-owned builtin
            // Error plus one layout per declared class of every declaration
            // module.
            check(result.seeds() != null, "the result carries the class "
                + "registration seeds");
            if (result.seeds() == null) {
                return;
            }
            ClassLayout errorLayout = result.seeds().registrationFor(BUILTIN_ERROR)
                == null ? null : result.seeds().registrationFor(BUILTIN_ERROR).layout();
            check(errorLayout != null
                    && result.seeds().registrationFor(BUILTIN_ERROR).owner()
                        == DefaultOwner.BUILTIN_DEFAULTS,
                "the seeds carry the compiler-owned builtin Error layout under "
                    + "BUILTIN_DEFAULTS");
            checkEq(2, result.seeds().registrations().size(),
                "the seeds carry exactly one layout per declared class of every "
                    + "declaration module plus the builtin Error entry");
            // The seeds are the layout resolution context, never merged into
            // a unit's own declared layouts.
            for (LoweredModuleUnit unit : result.project().modules().values()) {
                check(unit.classLayouts().isEmpty(),
                    "module " + unit.moduleId() + " carries no seeded layout in its "
                        + "own classLayouts map (the seeds are never merged into it)");
            }
            if (errorLayout != null) {
                checkEq(ClassLayout.BUILTIN_ERROR, errorLayout,
                    "the seeds' builtin Error registration is exactly the "
                        + "compiler-owned ClassLayout.BUILTIN_ERROR constant (the "
                        + "layout is identical in every unit's context)");
                checkEq(List.of("code", "message"),
                    errorLayout.fields().stream().map(ClassLayout.FieldLayout::name)
                        .toList(),
                    "the builtin Error layout carries code/message in declaration "
                        + "order");
                check(errorLayout.fields().stream()
                        .allMatch(field -> field.required()
                            && field.defaultOwner() == DefaultOwner.BUILTIN_DEFAULTS),
                    "both builtin Error fields are required-present under "
                        + "BUILTIN_DEFAULTS");
            }
            ClassLayout endpointLayout = result.seeds().registrationFor(ENDPOINT)
                == null ? null : result.seeds().registrationFor(ENDPOINT).layout();
            check(endpointLayout != null
                    && result.seeds().registrationFor(ENDPOINT).owner()
                        == DefaultOwner.HOST_DEFAULTS,
                "the declared host class carries its layout under HOST_DEFAULTS "
                    + "(the seed proves the declaration identity classification)");
            if (endpointLayout != null) {
                checkEq(List.of("path", "port"),
                    endpointLayout.fields().stream().map(ClassLayout.FieldLayout::name)
                        .toList(),
                    "the declared host class fields keep declaration order");
                check(endpointLayout.fields().get(0).required()
                        && !endpointLayout.fields().get(1).required(),
                    "required-present is the declaration's optional-negation");
            }

            // The closed IntrinsicFunction bindings of the three seeded
            // intrinsics, per unit, with their declared signatures.
            for (LoweredModuleUnit unit : result.project().modules().values()) {
                List<FunctionExecutionBinding.IntrinsicFunction> intrinsics =
                    new ArrayList<>();
                for (FunctionExecutionBinding binding
                        : unit.functionBindings().values()) {
                    if (binding
                            instanceof FunctionExecutionBinding.IntrinsicFunction
                            intrinsic) {
                        intrinsics.add(intrinsic);
                    }
                }
                check(intrinsics.size() == 3,
                    "module " + unit.moduleId() + " carries exactly the three closed "
                        + "intrinsic bindings; got " + intrinsics.size());
                Set<IntrinsicKind> kinds = new LinkedHashSet<>();
                for (FunctionExecutionBinding.IntrinsicFunction intrinsic
                        : intrinsics) {
                    kinds.add(intrinsic.kind());
                    check(intrinsic.descriptor()
                            .equals(intrinsic.kind().declaredSignature()),
                        "the intrinsic binding of " + intrinsic.kind()
                            + " carries its pinned declared signature");
                }
                checkEq(Set.of(IntrinsicKind.INT_CONVERT,
                        IntrinsicKind.NUMBER_CONVERT, IntrinsicKind.BYTES_NEW), kinds,
                    "the three seeded intrinsics are registered");
                // The dump carries the registration: the closed shape tag and
                // the alias cells of the module's completions.
                String dump = new String(
                    SemanticIrDumper.dumpModule(unit), StandardCharsets.UTF_8);
                check(dump.contains("intrinsicFunction"),
                    "the dump of module " + unit.moduleId()
                        + " records the closed intrinsicFunction binding shape");
                if (unit.ops().stream().anyMatch(op ->
                        op.kind() == SemanticOpKind.MODULE_IMPORT)) {
                    check(dump.contains("aliasCells"),
                        "the dump of module " + unit.moduleId()
                            + " carries the MODULE_IMPORT aliasCells list");
                }
            }

            // The namespace registrations: exactly one entry per distinct
            // imported module of the closure.
            check(result.namespaces() != null,
                "the result carries the namespace registrations");
            if (result.namespaces() == null) {
                return;
            }
            Map<ModuleId, BindingId> dumpedCells = new LinkedHashMap<>();
            Map<ModuleId, Integer> dumpedCompletions = new LinkedHashMap<>();
            for (LoweredModuleUnit unit : result.project().modules().values()) {
                for (SemanticOp op : unit.ops()) {
                    if (op.kind() != SemanticOpKind.MODULE_IMPORT
                            || !(op.payload()
                                instanceof KindPayload.ModuleImportPayload payload)) {
                        continue;
                    }
                    dumpedCompletions.merge(payload.resolvedModule(), 1,
                        Integer::sum);
                    check(payload.aliasCells().size() == 1,
                        "each import completion names its one alias cell; got "
                            + payload.aliasCells());
                    if (payload.aliasCells().size() == 1) {
                        dumpedCells.put(payload.resolvedModule(),
                            payload.aliasCells().get(0));
                    }
                }
            }
            checkEq(Set.of(UTIL, HOST_CFG), result.namespaces().registrations().keySet(),
                "exactly one registration exists per distinct imported module");
            NamespaceRegistration cfg = result.namespaces()
                .registrationFor(HOST_CFG);
            check(cfg != null && cfg.kind() == deal.semantic.ir.ModuleImportKind.HOST,
                "the host import's registration carries the HOST kind");
            if (cfg != null) {
                checkEq(List.of(dumpedCells.get(HOST_CFG)), cfg.aliasCells(),
                    "the entry's alias cells equal the cells its completion names");
            }
            NamespaceRegistration compiledModule = result.namespaces()
                .registrationFor(UTIL);
            check(compiledModule != null
                    && compiledModule.kind()
                        == deal.semantic.ir.ModuleImportKind.COMPILED,
                "the compiled implementation import's registration carries the "
                    + "COMPILED kind");
            if (compiledModule != null) {
                checkEq(List.of(dumpedCells.get(UTIL)), compiledModule.aliasCells(),
                    "the compiled import's cells equal the cells its completion "
                        + "names");
            }
            // The dumped MODULE_IMPORT payload names the same cells.
            List<BindingId> dumpedHostCells = new ArrayList<>();
            for (LoweredModuleUnit unit : result.project().modules().values()) {
                for (SemanticOp op : unit.ops()) {
                    if (op.kind() == SemanticOpKind.MODULE_IMPORT
                            && op.payload()
                                instanceof KindPayload.ModuleImportPayload payload
                            && payload.resolvedModule().equals(HOST_CFG)) {
                        dumpedHostCells.addAll(payload.aliasCells());
                    }
                }
            }
            checkEq(cfg == null ? List.of("missing") : cfg.aliasCells(),
                dumpedHostCells,
                "the dumped MODULE_IMPORT payloads carry the registered cells");
        }
    }

    private static void testUnitProjectCompanionOwnership() throws Exception {
        System.out.println("-- the complete unit/project companion ownership: the "
            + "unit owns bodyTable/classFactories/jsonDefaults/classBindingFacts, "
            + "the project owns classSeeds, and the result accessors are those "
            + "exact records --");
        RealProject project = compileProject(OWNERSHIP_APP_SOURCE,
            Map.of("src/util.deal", OWNERSHIP_UTIL_SOURCE,
                "src/mid.deal", OWNERSHIP_MID_SOURCE), Map.of());
        try {
            SemanticLowerer.ProjectLoweringResult result = lower(project, invocation());
            check(!result.hasErrors() && result.project() != null,
                "the ownership fixture lowers: " + result.diagnostics());
            if (result.project() == null) {
                return;
            }
            ExecutableLoweredProject lowered = result.project();
            check(lowered.classSeeds() != null,
                "the executable project owns its class registration seeds");
            check(lowered.classSeeds() == result.seeds(),
                "result.seeds() is exactly project.classSeeds(), never a detached "
                    + "copy");
            checkEq(Set.of(UTIL, MID, APP), result.tables().keySet(),
                "the result carries one body table per closure module");
            checkEq(Set.of(UTIL, MID, APP), result.registries().keySet(),
                "the result carries one class-factory registry per closure module");

            boolean sawClassModule = false;
            boolean sawPlainModule = false;
            boolean sawImportingModule = false;
            for (LoweredModuleUnit unit : lowered.modules().values()) {
                ModuleId module = unit.moduleId();
                StructuredBodyTable body = unit.bodyTable();
                ClassFactoryRegistry factories = unit.classFactories();
                JsonDefaultChildTable defaults = unit.jsonDefaults();
                ClassBindingFacts bindingFacts = unit.classBindingFacts();
                check(body != null && factories != null && defaults != null
                        && bindingFacts != null,
                    "module " + module + " carries every mandatory companion as a "
                        + "present record, never null");
                if (body == null || factories == null || defaults == null
                        || bindingFacts == null) {
                    continue;
                }
                // The retained result accessors are the unit's own records.
                check(result.tableOf(module) == body
                        && result.tables().get(module) == body,
                    "result.tableOf(" + module + ") is the unit's own bodyTable, "
                        + "not a detached copy");
                check(result.registryOf(module) == factories
                        && result.registries().get(module) == factories,
                    "result.registryOf(" + module + ") is the unit's own "
                        + "classFactories, not a detached copy");
                // Both membership directions are the exact inverses over the
                // unit's own operations.
                for (Map.Entry<BlockId, List<OpId>> entry : body.blockOps().entrySet()) {
                    for (OpId member : entry.getValue()) {
                        checkEq(entry.getKey(), body.opBlocks().get(member),
                            "the block table's opBlocks is the exact inverse of "
                                + "blockOps at " + member + " of " + module);
                    }
                }
                for (Map.Entry<OpId, BlockId> entry : body.opBlocks().entrySet()) {
                    check(body.blockOps().getOrDefault(entry.getValue(), List.of())
                            .contains(entry.getKey()),
                        "the block table's blockOps lists " + entry.getKey()
                            + " of " + module);
                }
                // Every registered factory names a produced CLASS_FACTORY of
                // this unit, and every JSON-default child a produced op.
                for (Map.Entry<ClassFactoryId, OpId> entry
                        : factories.factories().entrySet()) {
                    SemanticOp factory = opOf(unit, entry.getValue());
                    check(factory != null
                            && factory.kind() == SemanticOpKind.CLASS_FACTORY
                            && factory.opId().module().equals(module),
                        "the registry entry " + entry.getKey() + " of " + module
                            + " names this unit's CLASS_FACTORY op");
                }
                for (Map.Entry<OpId, List<OpId>> entry
                        : defaults.defaultChildren().entrySet()) {
                    check(opOf(unit, entry.getKey()) != null,
                        "the JSON-default site " + entry.getKey() + " of " + module
                            + " is a produced op");
                    for (OpId child : entry.getValue()) {
                        check(opOf(unit, child) != null,
                            "the JSON-default child " + child + " of " + module
                                + " is a produced op");
                    }
                }
                // Every parameter/import fact names a produced BINDING_ALLOC
                // of this unit.
                Set<BindingId> allocated = new LinkedHashSet<>();
                for (SemanticOp op : unit.ops()) {
                    if (op.payload() instanceof KindPayload.BindingAllocPayload alloc) {
                        allocated.add(alloc.binding());
                    }
                }
                for (BindingId parameter : bindingFacts.parameters()) {
                    check(allocated.contains(parameter),
                        "the parameter fact " + parameter + " of " + module
                            + " names a produced BINDING_ALLOC");
                }
                for (BindingId alias : bindingFacts.importAliases()) {
                    check(allocated.contains(alias),
                        "the import-alias fact " + alias + " of " + module
                            + " names a produced BINDING_ALLOC");
                }

                if (module.equals(UTIL)) {
                    sawClassModule = true;
                    check(unit.classLayouts().containsKey(POINT),
                        "the class module's own layout is in its classLayouts");
                    check(unit.classLayouts().get(POINT).classId().equals(POINT),
                        "the own layout's classId agrees with its registry key");
                    check(!factories.factories().isEmpty(),
                        "the class module's registry carries the exported "
                            + "CLASS_FACTORY registration");
                    check(lowered.classSeeds().registrationFor(POINT) == null,
                        "an in-project class is owned by the unit's own classLayouts, "
                            + "never merged into the declaration seeds");
                    check(lowered.classSeeds().registrationFor(BUILTIN_ERROR) != null,
                        "the project's class seeds carry the compiler-owned builtin "
                            + "Error registration");
                    check((Object) unit.classFactories()
                            != (Object) lowered.classSeeds(),
                        "the unit registry and the project seeds stay separate "
                            + "records");
                }
                if (module.equals(MID)) {
                    sawPlainModule = true;
                    // A module without class content and without parameters or
                    // imports carries present-empty companions.
                    check(unit.classLayouts().isEmpty(),
                        "the plain module carries no own class layout");
                    check(factories.factories().isEmpty(),
                        "the plain module carries an empty class-factory "
                            + "registry");
                    checkEq(new JsonDefaultChildTable(Map.of()), defaults,
                        "the plain module carries an empty JSON-default table");
                    checkEq(ClassBindingFacts.empty(), bindingFacts,
                        "the plain module carries empty parameter/import facts");
                }
                if (module.equals(APP)) {
                    sawImportingModule = true;
                    check(unit.classLayouts().isEmpty(),
                        "the importing module owns no local class layout");
                    check(factories.factories().isEmpty(),
                        "the importing module's registry is present and empty");
                    check(!bindingFacts.importAliases().isEmpty(),
                        "the importing module's import-alias facts are recorded "
                            + "in the unit");
                    check(!unit.classLayouts().containsKey(POINT),
                        "the imported class's seed layout never merges into the "
                            + "consumer's classLayouts");
                }
            }
            check(sawClassModule && sawPlainModule && sawImportingModule,
                "the closure carries the class, plain and importing modules");
            testResultBoundaryViewOwnership(lowered, result.namespaces());
            testCompleteUnitTransport(project, lowered);
        } finally {
            deleteRecursively(project.root());
        }
    }

    private static void testResultBoundaryViewOwnership(
            ExecutableLoweredProject lowered,
            NamespaceRegistrations namespaces) {
        System.out.println("-- the lowering-result boundary: missing, extra and "
            + "differing companion views reject, content-equal detached views are "
            + "replaced by the project's owned records, and the failure path "
            + "carries no companions --");
        Map<ModuleId, LoweredModuleUnit> modules = lowered.modules();
        ModuleId classModule = null;
        for (ModuleId module : modules.keySet()) {
            classModule = module;
            break;
        }
        LoweredModuleUnit unit = modules.get(classModule);
        StructuredBodyTable ownedTable = unit.bodyTable();
        ClassFactoryRegistry ownedRegistry = unit.classFactories();
        check(!ownedTable.blockOps().isEmpty(),
            "the boundary fixture's first module owns a non-empty body table");
        check(!ownedRegistry.factories().isEmpty(),
            "the boundary fixture's first module owns a non-empty factory "
                + "registry");
        check(!lowered.classSeeds().registrations().isEmpty(),
            "the boundary fixture's project owns its class registration seeds");

        // (1) The exact owned views and content-equal detached copies are
        // accepted; the retained accessors are the project's own records.
        Map<ModuleId, StructuredBodyTable> copiedTables = new LinkedHashMap<>();
        Map<ModuleId, ClassFactoryRegistry> copiedRegistries = new LinkedHashMap<>();
        for (Map.Entry<ModuleId, LoweredModuleUnit> entry : modules.entrySet()) {
            StructuredBodyTable body = entry.getValue().bodyTable();
            ClassFactoryRegistry registry = entry.getValue().classFactories();
            copiedTables.put(entry.getKey(),
                new StructuredBodyTable(body.blockOps(), body.opBlocks()));
            copiedRegistries.put(entry.getKey(),
                new ClassFactoryRegistry(registry.factories()));
        }
        SemanticLowerer.ProjectLoweringResult canonical =
            new SemanticLowerer.ProjectLoweringResult(lowered, copiedTables,
                copiedRegistries,
                new ClassRegistrationSeeds(lowered.classSeeds().registrations()),
                namespaces, List.of());
        for (Map.Entry<ModuleId, LoweredModuleUnit> entry : modules.entrySet()) {
            check(canonical.tables().get(entry.getKey())
                    == entry.getValue().bodyTable(),
                "the result table of " + entry.getKey() + " is the unit's owned "
                    + "bodyTable even when a content-equal copy was supplied");
            check(canonical.registries().get(entry.getKey())
                    == entry.getValue().classFactories(),
                "the result registry of " + entry.getKey() + " is the unit's "
                    + "owned classFactories even when a content-equal copy was "
                    + "supplied");
        }
        check(canonical.seeds() == lowered.classSeeds(),
            "the result seeds are the project's owned classSeeds even when a "
                + "content-equal copy was supplied");

        for (String defect : List.of("missing", "extra", "differing")) {
            Map<ModuleId, StructuredBodyTable> tables = new LinkedHashMap<>(copiedTables);
            Map<ModuleId, ClassFactoryRegistry> registries = new LinkedHashMap<>(copiedRegistries);
            switch (defect) {
                case "missing" -> {
                    tables.remove(classModule);
                    registries.remove(classModule);
                }
                case "extra" -> {
                    tables.put(new ModuleId("extra"), new StructuredBodyTable(Map.of(), Map.of()));
                    registries.put(new ModuleId("extra"), new ClassFactoryRegistry(Map.of()));
                }
                case "differing" -> {
                    tables.put(classModule, new StructuredBodyTable(Map.of(), ownedTable.opBlocks()));
                    registries.put(classModule, new ClassFactoryRegistry(Map.of()));
                }
            }
            expectIllegalArgument(() -> new SemanticLowerer.ProjectLoweringResult(
                    lowered, tables, copiedRegistries, lowered.classSeeds(), namespaces, List.of()),
                defect + " table view rejects");
            expectIllegalArgument(() -> new SemanticLowerer.ProjectLoweringResult(
                    lowered, copiedTables, registries, lowered.classSeeds(), namespaces, List.of()),
                defect + " registry view rejects");
        }

        // (4) Missing and differing seed views reject.
        expectIllegalArgument(() -> new SemanticLowerer.ProjectLoweringResult(
                lowered, copiedTables, copiedRegistries, null, namespaces,
                List.of()),
            "a result without the project's seeds rejects");
        expectIllegalArgument(() -> new SemanticLowerer.ProjectLoweringResult(
                lowered, copiedTables, copiedRegistries,
                new ClassRegistrationSeeds(Map.of()), namespaces, List.of()),
            "a seed view differing from the project's classSeeds rejects");

        // (5) A successful result carrying diagnostics rejects.
        CompilerDiagnostic diagnostic = FailureContractRegistry.e6005(
            new LoweringFailureDetail(APP.path(),
                SemanticCapability.FOUNDATION_VALUES, "RESULT_BOUNDARY_TEST",
                SemanticProfile.DEAL_V1_3_INT32, LoweredModuleUnit.FORMAT_VERSION,
                "ProjectLoweringTest"));
        expectIllegalArgument(() -> new SemanticLowerer.ProjectLoweringResult(
                lowered, copiedTables, copiedRegistries, lowered.classSeeds(),
                namespaces, List.of(diagnostic)),
            "a successful project lowering carrying diagnostics rejects");

        // (6) The failure path still carries exactly the first E6005 and no
        // project, tables, registries, seeds or namespace registrations.
        SemanticLowerer.ProjectLoweringResult failure =
            new SemanticLowerer.ProjectLoweringResult(null, Map.of(), Map.of(),
                null, null, List.of(diagnostic));
        check(failure.hasErrors() && failure.project() == null
                && failure.tables().isEmpty() && failure.registries().isEmpty()
                && failure.seeds() == null && failure.namespaces() == null,
            "a failed project lowering exposes no project, tables, registries, "
                + "seeds or namespace registrations");
        expectIllegalArgument(() -> new SemanticLowerer.ProjectLoweringResult(null,
                copiedTables, Map.of(), null, null, List.of(diagnostic)),
            "a failed project lowering carrying tables rejects");
        expectIllegalArgument(() -> new SemanticLowerer.ProjectLoweringResult(null,
                Map.of(), copiedRegistries, null, null, List.of(diagnostic)),
            "a failed project lowering carrying registries rejects");
        expectIllegalArgument(() -> new SemanticLowerer.ProjectLoweringResult(null,
                Map.of(), Map.of(), lowered.classSeeds(), null,
                List.of(diagnostic)),
            "a failed project lowering carrying seeds rejects");
        expectIllegalArgument(() -> new SemanticLowerer.ProjectLoweringResult(null,
                Map.of(), Map.of(), null, namespaces, List.of(diagnostic)),
            "a failed project lowering carrying namespace registrations "
                + "rejects");
        expectIllegalArgument(() -> new SemanticLowerer.ProjectLoweringResult(null,
                Map.of(), Map.of(), null, null, List.of()),
            "a failed project lowering without a diagnostic rejects");

        // (7) The single-module result exposes the unit's own table.
        expectIllegalArgument(() -> new SemanticLowerer.LoweringResult(unit,
                new StructuredBodyTable(Map.of(), ownedTable.opBlocks()), List.of()),
            "a single-module result table disagreeing with the unit rejects");
        SemanticLowerer.LoweringResult single = new SemanticLowerer.LoweringResult(
            unit, new StructuredBodyTable(ownedTable.blockOps(),
                ownedTable.opBlocks()), List.of());
        check(single.table() == ownedTable,
            "the single-module result table is the unit's owned bodyTable even "
                + "when a content-equal copy was supplied");
        expectIllegalArgument(() -> new SemanticLowerer.LoweringResult(unit, null,
                List.of()),
            "a single-module result without the unit's table rejects");
    }

    /**
     * The namespace-recording failure origins (ISSUE-0750 D3): every
     * completion-specific recording rejection carries the offending
     * {@code MODULE_IMPORT} operation's own exact source range — a
     * conflict detected on a second completion carries that second
     * completion's range, never the first naming op's — while the
     * aggregate rejection that names only an uncompleted alias allocation
     * (no produced completion exists to identify) keeps the canonical
     * empty-file synthetic range. Messages, predicates, and first-failure
     * order are unchanged.
     */
    private static void testNamespaceRegistrationFailureOrigins(
            LoweredProject baseline) throws Exception {
        System.out.println("-- the namespace-registration failure origins: the "
            + "offending MODULE_IMPORT completion's own range, and the canonical "
            + "synthetic range of the aggregate uncompleted-alias failure --");
        RealProject project = baseline.project();
        SemanticLowerer.ProjectLoweringResult result = baseline.result();
        if (result.project() == null) {
            fail("the project lowers: " + result.diagnostics());
            return;
        }
        LoweredModuleUnit app = result.project().modules().get(APP);
        SemanticOp utilImport = app == null ? null : moduleImportOf(app, UTIL);
        check(app != null && utilImport != null,
            "the entry unit records the MODULE_IMPORT completion of util");
        if (app == null || utilImport == null) {
            return;
        }
        String appFile = project.root().resolve("src/app.deal").toAbsolutePath()
            .normalize().toString();
        DiagnosticRange completionRange = utilImport.origin().span().range();
        check(completionRange.origin() == RangeOrigin.SOURCE
                && completionRange.scalarLength() > 0,
            "the offending completion carries a real SOURCE span: "
                + completionRange);

        // (a) The kind mismatch: the completion's own exact SOURCE range.
        Optional<CompilerDiagnostic> mismatchFailure =
            new NamespaceRegistrations.Recorder(Map.of(UTIL, ModuleImportKind.HOST))
                .record(app);
        check(mismatchFailure.isPresent(),
            "the pre-registered HOST entry kind rejects the COMPILED completion");
        if (mismatchFailure.isPresent()) {
            CompilerDiagnostic diagnostic = mismatchFailure.get();
            checkEq("E6005", diagnostic.code(),
                "the kind mismatch is one E6005");
            checkEq("Common semantic lowering failed: module 'app', capability"
                    + " MODULES, validatorRule NAMESPACE_REGISTRATION,"
                    + " semanticProfile DEAL_V1_3_INT32, irVersion"
                    + " deal.semantic-ir/2, origin NamespaceRegistrations record"
                    + " (the MODULE_IMPORT completion " + utilImport.opId()
                    + " of util carries the kind COMPILED but the module's"
                    + " registration entry kind is HOST (a completion's"
                    + " ModuleImportKind equals its entry's kind))",
                diagnostic.message(),
                "the kind-mismatch message is the unchanged pinned text");
            checkEq(completionRange, diagnostic.range(),
                "the kind-mismatch diagnostic carries the offending completion's"
                    + " own exact range");
            checkEq(appFile, diagnostic.range().file(),
                "the kind-mismatch range names the offending file");
            checkEq(1, diagnostic.range().startLine(),
                "the kind-mismatch range starts at the import statement's line");
            checkEq(1, diagnostic.range().startColumn(),
                "the kind-mismatch range starts at the import statement's column");
            check(!diagnostic.range().isCanonicalSynthetic(),
                "the kind-mismatch range is never the canonical synthetic anchor");
            check(diagnostic.notes().isEmpty(),
                "the well-formed completion origin adds no note: "
                    + diagnostic.notes());
        }

        // (b) A conflict detected on a second completion: the detecting
        // completion's own range, never the first naming op's.
        SourceSpan doctoredSpan = new SourceSpan(appFile, 9, 4, 9, 10, 100, 106);
        SourceOrigin doctoredOrigin = new SourceOrigin(
            utilImport.origin().sourceId(), doctoredSpan, SourceOriginKind.USER,
            new AnchorId(9999), utilImport.origin().parentOpId());
        SemanticOp duplicated = new SemanticOp(
            new OpId(APP, 999999), utilImport.kind(), doctoredOrigin,
            utilImport.result(), utilImport.resultType(), utilImport.operands(),
            utilImport.operandTypes(), utilImport.payload(),
            utilImport.failurePolicy(), utilImport.contract());
        List<SemanticOp> doubled = new ArrayList<>(app.ops());
        doubled.add(duplicated);
        Optional<CompilerDiagnostic> duplicateFailure =
            new NamespaceRegistrations.Recorder(Map.of()).record(
                withOps(app, doubled));
        check(duplicateFailure.isPresent(),
            "the second naming of the same alias cell is rejected");
        if (duplicateFailure.isPresent()) {
            CompilerDiagnostic diagnostic = duplicateFailure.get();
            checkEq("E6005", diagnostic.code(),
                "the duplicate naming is one E6005");
            check(diagnostic.message().contains("the cell "
                    + ((KindPayload.ModuleImportPayload) utilImport.payload())
                        .aliasCells().get(0)
                    + " is named by two MODULE_IMPORT completions")
                    && diagnostic.message().contains(
                        "NamespaceRegistrations record"),
                "the duplicate-naming message is the unchanged text: "
                    + diagnostic.message());
            checkEq(doctoredSpan.range(), diagnostic.range(),
                "the duplicate-naming diagnostic carries the detecting"
                    + " completion's own exact range");
            check(!diagnostic.range().equals(completionRange),
                "the duplicate-naming range is never the first naming op's range");
            checkEq(9, diagnostic.range().startLine(),
                "the duplicate-naming range starts at the detecting completion's"
                    + " line");
            checkEq(4, diagnostic.range().startColumn(),
                "the duplicate-naming range starts at the detecting completion's"
                    + " column");
            checkEq(11, diagnostic.range().endColumn(),
                "the duplicate-naming range keeps the half-open end conversion");
            checkEq(100, diagnostic.range().startScalarOffset(),
                "the duplicate-naming range carries the recorded start offset");
            checkEq(106, diagnostic.range().endScalarOffset(),
                "the duplicate-naming range carries the recorded end offset");
            checkEq(6, diagnostic.range().scalarLength(),
                "the duplicate-naming range covers the recorded span");
            check(diagnostic.range().origin() == RangeOrigin.SOURCE,
                "the duplicate-naming range is SOURCE, got "
                    + diagnostic.range().origin());
        }

        // (c) The aggregate failure: the alias allocation the unit leaves
        // uncompleted has no produced completion to identify, so the
        // canonical empty-file synthetic range stays.
        List<SemanticOp> withoutUtil = new ArrayList<>();
        for (SemanticOp op : app.ops()) {
            if (!op.opId().equals(utilImport.opId())) {
                withoutUtil.add(op);
            }
        }
        Optional<CompilerDiagnostic> uncompletedFailure =
            new NamespaceRegistrations.Recorder(Map.of()).record(
                withOps(app, withoutUtil));
        check(uncompletedFailure.isPresent(),
            "the uncompleted alias allocation is rejected");
        if (uncompletedFailure.isPresent()) {
            CompilerDiagnostic diagnostic = uncompletedFailure.get();
            checkEq("E6005", diagnostic.code(),
                "the uncompleted alias is one E6005");
            check(diagnostic.message().contains(
                    "is named by no MODULE_IMPORT completion"),
                "the uncompleted-alias message is the unchanged aggregate text: "
                    + diagnostic.message());
            check(diagnostic.range().isCanonicalSynthetic(),
                "the aggregate failure keeps the canonical synthetic range, got "
                    + diagnostic.range());
            checkEq("", diagnostic.range().file(),
                "the aggregate failure borrows no source file");
            check(diagnostic.range().origin() == RangeOrigin.SYNTHETIC,
                "the aggregate failure stays SYNTHETIC, got "
                    + diagnostic.range().origin());
            checkEq(0, diagnostic.range().scalarLength(),
                "the aggregate failure keeps the zero-length anchor");
            check(diagnostic.notes().isEmpty(),
                "the originless aggregate adds no note: " + diagnostic.notes());
        }
    }

    /** The unit's completion of the given resolved module, or null. */
    private static SemanticOp moduleImportOf(LoweredModuleUnit unit,
            ModuleId module) {
        for (SemanticOp op : unit.ops()) {
            if (op.kind() == SemanticOpKind.MODULE_IMPORT
                    && op.payload() instanceof KindPayload.ModuleImportPayload payload
                    && payload.resolvedModule().equals(module)) {
                return op;
            }
        }
        return null;
    }

    // =========================================================================
    // 3. The composed chain over the unified units
    // =========================================================================

    private static void testComposedChainOverUnifiedUnits(LoweredProject baseline)
            throws Exception {
        System.out.println("-- the composed chain accepts the E7-armed unified "
            + "units --");
        RealProject project = baseline.project();
        {
            SemanticLowerer.ProjectLoweringResult result = baseline.result();
            if (result.project() == null) {
                fail("the project lowers: " + result.diagnostics());
                return;
            }
            SemanticIrValidator.ComparisonFacts facts =
                new SemanticIrValidator.ComparisonFacts(
                    project.index().interfaceIndexDigest(),
                    SemanticProfile.DEAL_V1_3_INT32,
                    invocation().capabilityRegistryHash());
            check(SemanticIrValidator.validate(result.project(), facts).isEmpty(),
                "the project passes the project-form closed gate");
            for (LoweredModuleUnit unit : result.project().modules().values()) {
                StructuredBodyTable table = result.tableOf(unit.moduleId());
                check(table != null, "the module's produced table is carried");
                check(SemanticIrValidator.validate(unit, facts).isEmpty(),
                    "module " + unit.moduleId() + " passes the closed 14 rules");
                check(AddressChainProtocol.validate(unit).isEmpty(),
                    "module " + unit.moduleId()
                        + " passes the address-chain protocol");
                check(deal.semantic.ControlFlowValidator.validate(unit, table)
                        .isEmpty(),
                    "module " + unit.moduleId() + " passes the control-flow "
                        + "validator");
                check(deal.semantic.ClassConstructionValidator.validate(unit,
                        table, result.registryOf(unit.moduleId()),
                        new JsonDefaultChildTable(Map.of()),
                        project.index().modules().get(unit.moduleId()),
                        Map.of()).isEmpty(),
                    "module " + unit.moduleId() + " passes the class-construction "
                        + "validator");
                // The unified session really carried the E7 arms: the
                // cross-module call produced the caller's EXTERNAL_ENTRY
                // reference and the callee's entry.
                if (unit.moduleId().equals(APP)) {
                    check(unit.ops().stream().anyMatch(op ->
                            op.kind() == SemanticOpKind.CALL
                                && op.payload() instanceof KindPayload.CallPayload
                                    call && call.externalEntryRef() != null),
                        "the entry module's cross-module call carries its "
                            + "externalEntryRef");
                }
                if (unit.moduleId().equals(UTIL)) {
                    check(unit.ops().stream().anyMatch(op ->
                            op.kind() == SemanticOpKind.EXTERNAL_ENTRY),
                        "the callee module records its EXTERNAL_ENTRY");
                    // The recursive-group arm is active in the same session:
                    // exactly one RECURSIVE_GROUP_INIT with both members.
                    List<SemanticOp> groups = new ArrayList<>();
                    for (SemanticOp op : unit.ops()) {
                        if (op.kind() == SemanticOpKind.RECURSIVE_GROUP_INIT) {
                            groups.add(op);
                        }
                    }
                    check(groups.size() == 1,
                        "the unified session produced exactly one "
                            + "RECURSIVE_GROUP_INIT; got " + groups.size());
                    if (groups.size() == 1
                            && groups.get(0).payload()
                                instanceof KindPayload.RecursiveGroupInitPayload group) {
                        checkEq(2, group.functions().size(),
                            "the group op publishes both members");
                    }
                }
                if (unit.moduleId().equals(APP)) {
                    // The adapter arm is active in the same session.
                    check(unit.ops().stream().anyMatch(op ->
                            op.kind() == SemanticOpKind.FUNCTION_ADAPT),
                        "the unified session produced the adapter");
                }
            }
        }
    }

    // =========================================================================
    // 4. The unified walk's class arm (in-project classes)
    // =========================================================================

    private static void testInProjectClassArm() throws Exception {
        System.out.println("-- the unified walk's class arm: an in-project class "
            + "declared and constructed in one session --");
        RealProject project = compileProject(CLASS_APP_SOURCE,
            Map.of("src/util.deal", CLASS_UTIL_SOURCE), Map.of());
        try {
            SemanticLowerer.ProjectLoweringResult result = lower(project, invocation());
            check(!result.hasErrors() && result.project() != null,
                "the class-declaring project lowers through the composed chain: "
                    + result.diagnostics());
            if (result.project() == null) {
                return;
            }
            LoweredModuleUnit util = result.project().modules().get(UTIL);
            check(util != null, "the class-declaring module is in the closure");
            if (util == null) {
                return;
            }
            // The class-declaration arm produced the unit's own layouts (the
            // exported class and the local one); the seeds stay out of them.
            ClassLayout pointLayout = util.classLayouts().get(POINT);
            ClassLayout hiddenLayout = util.classLayouts().get(HIDDEN);
            check(pointLayout != null && hiddenLayout != null,
                "the unified walk produced the unit's own classLayouts entries: "
                    + util.classLayouts().keySet());
            check(!util.classLayouts().containsKey(ENDPOINT)
                    && !util.classLayouts().containsKey(BUILTIN_ERROR),
                "the class registration seeds stay out of the unit's own "
                    + "classLayouts map");
            if (pointLayout == null || hiddenLayout == null) {
                return;
            }
            checkEq(List.of("x", "y"),
                pointLayout.fields().stream().map(ClassLayout.FieldLayout::name)
                    .toList(),
                "the exported class layout carries its fields in declaration order");
            check(pointLayout.fields().stream().allMatch(field -> field.required()
                    && field.defaultOwner() == DefaultOwner.LOCAL),
                "the in-project class fields carry the LOCAL owner member");
            checkEq(List.of("label"),
                hiddenLayout.fields().stream().map(ClassLayout.FieldLayout::name)
                    .toList(),
                "the local class layout carries its field in declaration order");

            // The class-literal arm in the same session: one LOCAL CLASS_NEW
            // per construction, with the provided field as a literal boundary
            // and the omitted defaulted field as a default boundary.
            List<SemanticOp> constructions = new ArrayList<>();
            for (SemanticOp op : util.ops()) {
                if (op.kind() == SemanticOpKind.CLASS_NEW) {
                    constructions.add(op);
                }
            }
            checkEq(2, constructions.size(),
                "the unified walk produced one CLASS_NEW per class literal");
            boolean sawPoint = false;
            boolean sawHidden = false;
            for (SemanticOp op : constructions) {
                KindPayload.ClassNewPayload payload =
                    (KindPayload.ClassNewPayload) op.payload();
                check(payload.defaultOwner() == DefaultOwner.LOCAL
                        && payload.classFactoryRef() == null,
                    "the same-module literal carries LOCAL with no factory ref");
                if (payload.classId().equals(POINT)) {
                    sawPoint = true;
                    checkEq(pointLayout, payload.layout(),
                        "the exported class literal carries the unit's own layout");
                    checkEq(1, payload.classDefaultOpIds().size(),
                        "the omitted defaulted y field lists its CLASS_DEFAULT child");
                    checkEq(List.of("x", "y"),
                        payload.fieldBoundaries().stream()
                            .map(KindPayload.FieldBoundary::field).toList(),
                        "the construction boundaries run in declaration order");
                    check(payload.fieldBoundaries().get(0).kind()
                            == BoundaryKind.CLASS_LITERAL_FIELD
                            && payload.fieldBoundaries().get(1).kind()
                                == BoundaryKind.CLASS_DEFAULT_FIELD,
                        "the provided field is a literal boundary and the omitted "
                            + "defaulted field a default boundary");
                }
                if (payload.classId().equals(HIDDEN)) {
                    sawHidden = true;
                    checkEq(hiddenLayout, payload.layout(),
                        "the local class literal carries the unit's own layout");
                    check(payload.classDefaultOpIds().isEmpty()
                            && payload.fieldBoundaries().size() == 1
                            && payload.fieldBoundaries().get(0).kind()
                                == BoundaryKind.CLASS_LITERAL_FIELD,
                        "the fully provided local literal carries no default child");
                }
            }
            check(sawPoint && sawHidden,
                "both the exported and the local class literal lowered CLASS_NEW");

            // The exported variant's CLASS_FACTORY registration; the local
            // class never gets one.
            ExternalModuleInterface utilInterface =
                project.index().modules().get(UTIL);
            ClassInterface pointInterface = null;
            if (utilInterface != null) {
                for (ClassInterface candidate : utilInterface.classes()) {
                    if (candidate.classId().equals(POINT)) {
                        pointInterface = candidate;
                    }
                }
            }
            check(pointInterface != null,
                "the exported class carries its interface entry");
            ClassFactoryRegistry produced = result.registryOf(UTIL);
            check(produced != null,
                "the class-declaring module carries its class-factory registry");
            ClassFactoryRegistry registry = produced == null
                ? new ClassFactoryRegistry(Map.of()) : produced;
            if (pointInterface != null) {
                ClassFactoryId entry = pointInterface.constructionEntry();
                OpId factoryOpId = registry.factoryFor(entry);
                check(factoryOpId != null,
                    "the exported class's CLASS_FACTORY is registered under its "
                        + "constructionEntry");
                SemanticOp factory = null;
                for (SemanticOp op : util.ops()) {
                    if (op.opId().equals(factoryOpId)) {
                        factory = op;
                    }
                }
                check(factory != null
                        && factory.kind() == SemanticOpKind.CLASS_FACTORY
                        && factory.payload()
                            instanceof KindPayload.ClassFactoryPayload factoryPayload
                        && factoryPayload.classId().equals(POINT),
                    "the registered id names the exported class's CLASS_FACTORY op");
            }
            boolean hiddenFactory = false;
            for (SemanticOp op : util.ops()) {
                if (op.kind() == SemanticOpKind.CLASS_FACTORY
                        && op.payload()
                            instanceof KindPayload.ClassFactoryPayload factory
                        && factory.classId().equals(HIDDEN)) {
                    hiddenFactory = true;
                }
            }
            check(!hiddenFactory,
                "the local class never gets a CLASS_FACTORY (a non-exported "
                    + "class carries no factory id)");

            // The composed chain re-runs over the class-carrying unit.
            SemanticIrValidator.ComparisonFacts facts =
                new SemanticIrValidator.ComparisonFacts(
                    project.index().interfaceIndexDigest(),
                    SemanticProfile.DEAL_V1_3_INT32,
                    invocation().capabilityRegistryHash());
            check(SemanticIrValidator.validate(util, facts).isEmpty(),
                "the class-carrying unit passes the closed 14 rules");
            check(AddressChainProtocol.validate(util).isEmpty(),
                "the class-carrying unit passes the address-chain protocol");
            check(deal.semantic.ControlFlowValidator.validate(util,
                    result.tableOf(UTIL)).isEmpty(),
                "the class-carrying unit passes the control-flow validator");
            check(ClassConstructionValidator.validate(util, result.tableOf(UTIL),
                    registry, new JsonDefaultChildTable(Map.of()), utilInterface,
                    Map.of()).isEmpty(),
                "the class-carrying unit passes the class-construction validator "
                    + "(the composed chain's class arm)");
        } finally {
            deleteRecursively(project.root());
        }
    }

    private static void testImportedInProjectClassResolution(LoweredProject imported)
            throws Exception {
        System.out.println("-- the in-project imported-class construction: "
            + "CLASS_NEW(SHARED_FACTORY) with the owner's factory reference --");
        RealProject project = imported.project();
        {
            SemanticLowerer.ProjectLoweringResult result = imported.result();
            check(!result.hasErrors() && result.project() != null,
                "the imported in-project class literal lowers through the one "
                    + "project entry: " + result.diagnostics());
            boolean retainedAbiDeferred = false;
            for (CompilerDiagnostic diagnostic : result.diagnostics()) {
                if (diagnostic.message().contains("RETAINED_ABI_DEFERRED")) {
                    retainedAbiDeferred = true;
                }
            }
            check(!retainedAbiDeferred,
                "the checker-valid in-project input reports zero "
                    + "RETAINED_ABI_DEFERRED");
            if (result.project() == null) {
                return;
            }
            LoweredModuleUnit util = result.project().modules().get(UTIL);
            LoweredModuleUnit app = result.project().modules().get(APP);
            check(util != null && app != null,
                "both the owner and the dependent module are in the closure");
            if (util == null || app == null) {
                return;
            }
            // The in-project records of the owner: the interface entry from
            // the project index, the owner unit's layout, the owner
            // registry's factory op id for the interface constructionEntry,
            // and the factory op's result ValueId.
            ExternalModuleInterface utilInterface = project.index().modules().get(UTIL);
            ClassInterface pointEntry = null;
            if (utilInterface != null) {
                for (ClassInterface candidate : utilInterface.classes()) {
                    if (candidate.classId().equals(POINT)) {
                        pointEntry = candidate;
                    }
                }
            }
            ClassLayout ownerLayout = util.classLayouts().get(POINT);
            ClassFactoryRegistry ownerRegistry = result.registryOf(UTIL);
            check(pointEntry != null && ownerLayout != null && ownerRegistry != null,
                "the owner's interface entry, layout, and registry resolve");
            if (pointEntry == null || ownerLayout == null || ownerRegistry == null) {
                return;
            }
            OpId factoryOpId = ownerRegistry.factoryFor(pointEntry.constructionEntry());
            SemanticOp factoryOp = factoryOpId == null ? null : opOf(util, factoryOpId);
            check(factoryOpId != null && factoryOp != null
                    && factoryOp.kind() == SemanticOpKind.CLASS_FACTORY
                    && factoryOp.result() instanceof ValueId,
                "the owner's CLASS_FACTORY is registered under the interface "
                    + "constructionEntry with a result value");
            if (factoryOpId == null || factoryOp == null
                    || !(factoryOp.result() instanceof ValueId factoryResult)) {
                return;
            }
            SharedFactoryFacts facts = new SharedFactoryFacts(POINT, pointEntry,
                ownerLayout, factoryOpId, factoryResult);

            // The dependent module's imported literals: one CLASS_NEW per
            // constructed imported class, each with defaultOwner
            // SHARED_FACTORY, the owner's constructionEntry as the factory
            // reference, an empty CLASS_DEFAULT child list, the provided
            // fields, and the declaration-order boundary list.
            Map<ClassId, KindPayload.ClassNewPayload> constructions =
                new LinkedHashMap<>();
            for (SemanticOp op : app.ops()) {
                if (op.kind() == SemanticOpKind.CLASS_NEW
                        && op.payload() instanceof KindPayload.ClassNewPayload candidate) {
                    constructions.put(candidate.classId(), candidate);
                }
            }
            checkEq(Set.of(POINT, SEGMENT), constructions.keySet(),
                "the dependent module lowers one CLASS_NEW per constructed imported "
                    + "in-project class");
            KindPayload.ClassNewPayload payload = constructions.get(POINT);
            check(payload != null, "the imported Point literal lowered CLASS_NEW");
            if (payload == null) {
                return;
            }
            checkEq(DefaultOwner.SHARED_FACTORY, payload.defaultOwner(),
                "the imported literal carries defaultOwner SHARED_FACTORY");
            checkEq(facts.interfaceEntry().constructionEntry(),
                payload.classFactoryRef(),
                "the literal carries the owner's interface constructionEntry as its "
                    + "factory reference");
            check(payload.classDefaultOpIds().isEmpty(),
                "the SHARED_FACTORY literal carries an empty classDefaultOpIds list "
                    + "(the defaults transfer to the owner's factory)");
            checkEq(ownerLayout, payload.layout(),
                "the payload layout is the owner unit's layout, resolved through the "
                    + "in-project interface facts");
            checkEq(List.of("x"), payload.providedFields().stream()
                    .map(KindPayload.ProvidedField::name).toList(),
                "the provided fields stay in literal order");
            checkEq(List.of("x", "y"), payload.fieldBoundaries().stream()
                    .map(KindPayload.FieldBoundary::field).toList(),
                "the field boundaries run in declaration order");
            checkEq(List.of(BoundaryKind.CLASS_LITERAL_FIELD,
                    BoundaryKind.CLASS_DEFAULT_FIELD),
                payload.fieldBoundaries().stream()
                    .map(KindPayload.FieldBoundary::kind).toList(),
                "the provided field is a literal boundary and the omitted defaulted "
                    + "field a default boundary");
            SemanticOp defaultBoundary = opOf(app,
                payload.fieldBoundaries().get(1).boundaryOpId());
            check(defaultBoundary != null
                    && defaultBoundary.payload()
                        instanceof KindPayload.BoundaryPayload boundary
                    && boundary.input().equals(facts.factoryResult()),
                "the omitted defaulted field's boundary wires the owner factory op's "
                    + "result ValueId (the in-project factory reference)");

            // Repeats are deterministic: the same accumulation and walk
            // produce the byte-identical dependent unit.
            SemanticLowerer.ProjectLoweringResult repeat = lower(project, invocation());
            check(repeat.project() != null
                    && java.util.Arrays.equals(
                        SemanticIrDumper.dumpModule(app),
                        SemanticIrDumper.dumpModule(
                            repeat.project().modules().get(APP))),
                "the repeated lowering of the imported in-project class project is "
                    + "byte-identical");

            // Every exported class of the lowered owner accumulates its own
            // fact (the loop, not one fixture class): the second class
            // constructs SHARED_FACTORY with its own constructionEntry and
            // its own owner layout.
            ClassInterface segmentEntry = null;
            if (utilInterface != null) {
                for (ClassInterface candidate : utilInterface.classes()) {
                    if (candidate.classId().equals(SEGMENT)) {
                        segmentEntry = candidate;
                    }
                }
            }
            ClassLayout segmentLayout = util.classLayouts().get(SEGMENT);
            OpId segmentFactoryOpId = segmentEntry == null ? null
                : ownerRegistry.factoryFor(segmentEntry.constructionEntry());
            SemanticOp segmentFactoryOp = segmentFactoryOpId == null ? null
                : opOf(util, segmentFactoryOpId);
            KindPayload.ClassNewPayload segment = constructions.get(SEGMENT);
            check(segment != null && segmentEntry != null && segmentLayout != null
                    && segment.defaultOwner() == DefaultOwner.SHARED_FACTORY
                    && segment.classFactoryRef().equals(
                        segmentEntry.constructionEntry())
                    && segment.classDefaultOpIds().isEmpty()
                    && segment.layout().equals(segmentLayout)
                    && segmentFactoryOp != null
                    && segmentFactoryOp.kind() == SemanticOpKind.CLASS_FACTORY,
                "every exported class of the lowered owner accumulates its own fact "
                    + "(the second class constructs SHARED_FACTORY with its own "
                    + "constructionEntry and layout)");

            // The composed chain re-runs over the dependent unit with the
            // in-project facts of both classes (the class-construction arm's
            // imported path).
            Map<ClassId, SharedFactoryFacts> inProjectFacts = new LinkedHashMap<>();
            inProjectFacts.put(POINT, facts);
            if (segmentEntry != null && segmentLayout != null
                    && segmentFactoryOpId != null && segmentFactoryOp != null
                    && segmentFactoryOp.result() instanceof ValueId segmentResult) {
                inProjectFacts.put(SEGMENT, new SharedFactoryFacts(SEGMENT,
                    segmentEntry, segmentLayout, segmentFactoryOpId, segmentResult));
            }
            check(ClassConstructionValidator.validate(app, result.tableOf(APP),
                    result.registryOf(APP), new JsonDefaultChildTable(Map.of()),
                    project.index().modules().get(APP),
                    inProjectFacts).isEmpty(),
                "the dependent unit passes the class-construction validator with the "
                    + "in-project facts");

            // The combined behavior: the dependent module's cross-module call
            // resolves through the accumulated EXTERNAL_ENTRY records.
            KindPayload.CallPayload externalCall = null;
            for (SemanticOp op : app.ops()) {
                if (op.kind() == SemanticOpKind.CALL
                        && op.payload() instanceof KindPayload.CallPayload call
                        && call.callee()
                            instanceof KindPayload.CallCallee.Static staticCallee
                        && staticCallee.binding()
                            instanceof FunctionExecutionBinding.ExternalFunction external
                        && external.moduleId().equals(UTIL)) {
                    externalCall = call;
                    check(external.executionOwner()
                            == deal.semantic.ir.ExternalExecutionOwner.SHARED_BODY,
                        "the cross-module callee is a SHARED_BODY external (no "
                            + "retained-ABI owner enters the project lowering)");
                }
            }
            check(externalCall != null,
                "the dependent module's cross-module call carries the in-project "
                    + "external binding");
            if (externalCall != null) {
                OpId entryRef = externalCall.externalEntryRef();
                SemanticOp entry = entryRef == null ? null : opOf(util, entryRef);
                check(entry != null
                        && entry.kind() == SemanticOpKind.EXTERNAL_ENTRY
                        && entry.payload()
                            instanceof KindPayload.ExternalEntryPayload entryPayload
                        && entryPayload.exportName().equals("makePoint"),
                    "the call's externalEntryRef names the owner's recorded "
                        + "EXTERNAL_ENTRY (the accumulated in-project record)");
            }
        }
    }

    private static void testInconsistentFactSeed(LoweredProject imported)
            throws Exception {
        System.out.println("-- the inconsistent-fact negative: an owner outside the "
            + "closure and outside the declaration set still defers --");
        RealProject project = imported.project();
        {
            // The same real project with the owner module removed from the
            // closure and the index: the checker-resolved class identity
            // stays, but no lowered implementation module, no declaration
            // surface entry, and no registration seed covers it — the
            // inconsistent-fact case the deferral remains for.
            List<CheckedModuleInput> modules = new ArrayList<>();
            for (CheckedModuleInput module : project.checkedProject().modules()) {
                if (!module.moduleId().equals(UTIL)) {
                    modules.add(module);
                }
            }
            Map<ModuleId, ExternalModuleInterface> indexModules = new LinkedHashMap<>();
            for (Map.Entry<ModuleId, ExternalModuleInterface> entry
                    : project.index().modules().entrySet()) {
                if (!entry.getKey().equals(UTIL)) {
                    indexModules.put(entry.getKey(), entry.getValue());
                }
            }
            ProjectInterfaceIndex strippedIndex = new ProjectInterfaceIndex(
                ProjectInterfaceIndex.FORMAT_VERSION, indexModules);
            List<SemanticRequirementManifest> manifests = new ArrayList<>();
            for (SemanticRequirementManifest manifest : project.manifests()) {
                if (!manifest.moduleId().equals(UTIL)) {
                    manifests.add(manifest);
                }
            }
            CompilerInvocation invocation = invocation();
            CheckedProjectInput stripped = new CheckedProjectInput(invocation,
                project.checkedProject().entryModule(), modules,
                invocation.releaseStateHash());
            SemanticLowerer.ProjectLoweringResult result =
                SemanticLowerer.lowerProject(invocation, stripped, strippedIndex,
                    manifests, project.surface(), project.declarationIdentities(),
                    project.externCModules(),
                    BuiltinErrorDeclaration.synthesized(
                        modules.get(0).ast().span()),
                    List.of(IntrinsicKind.INT_CONVERT,
                        IntrinsicKind.NUMBER_CONVERT, IntrinsicKind.BYTES_NEW),
                    Set.of());
            check(result.hasErrors() && result.project() == null,
                "the inconsistent-fact seed produces no project: "
                    + result.diagnostics());
            check(result.tables().isEmpty() && result.registries().isEmpty()
                    && result.seeds() == null && result.namespaces() == null,
                "the inconsistent-fact seed produces no tables, no registries, no "
                    + "seeds, and no registrations");
            if (!result.diagnostics().isEmpty()) {
                CompilerDiagnostic diagnostic = result.diagnostics().get(0);
                check("E6005".equals(diagnostic.code())
                        && diagnostic.message().contains("RETAINED_ABI_DEFERRED"),
                    "the inconsistent-fact seed returns the first E6005 "
                        + "RETAINED_ABI_DEFERRED; got " + diagnostic.message());
            }
        }
    }

    // =========================================================================
    // 5. The declaration-class construction acceptance
    // =========================================================================

    private static void testDeclarationClassConstruction() throws Exception {
        System.out.println("-- a host declaration class literal resolves to the "
            + "seed and lowers CLASS_NEW(HOST_DEFAULTS) through the one project "
            + "entry --");
        RealProject project = compileProject(HOST_CLASS_APP_SOURCE, Map.of(),
            Map.of());
        try {
            SemanticLowerer.ProjectLoweringResult result = lower(project, invocation());
            check(!result.hasErrors() && result.project() != null,
                "the host declaration class literal lowers through the one project "
                    + "entry with zero diagnostics: " + result.diagnostics());
            boolean deferred = false;
            for (CompilerDiagnostic diagnostic : result.diagnostics()) {
                if (diagnostic.message().contains("RETAINED_ABI_DEFERRED")) {
                    deferred = true;
                }
            }
            check(!deferred,
                "checker-valid host-class input reports zero RETAINED_ABI_DEFERRED");
            if (result.project() == null) {
                return;
            }
            check(result.seeds().registrationFor(ENDPOINT) != null
                    && result.seeds().registrationFor(ENDPOINT).owner()
                        == DefaultOwner.HOST_DEFAULTS,
                "the declared host class resolves in the seeds under HOST_DEFAULTS");
            LoweredModuleUnit app = result.project().modules().get(APP);
            check(app != null, "the app unit is in the closure");
            if (app == null) {
                return;
            }
            SemanticOp classNew = null;
            for (SemanticOp op : app.ops()) {
                if (op.kind() == SemanticOpKind.CLASS_NEW) {
                    classNew = op;
                }
            }
            check(classNew != null, "the unit carries the host CLASS_NEW");
            if (classNew == null) {
                return;
            }
            KindPayload.ClassNewPayload payload =
                (KindPayload.ClassNewPayload) classNew.payload();
            checkEq(ENDPOINT, payload.classId(),
                "the CLASS_NEW classId is the declared host class identity");
            checkEq(DefaultOwner.HOST_DEFAULTS, payload.defaultOwner(),
                "the CLASS_NEW defaultOwner is HOST_DEFAULTS");
            check(payload.classFactoryRef() == null,
                "the host construction carries the null factory ref");
            check(payload.classDefaultOpIds().isEmpty(),
                "the host construction carries empty classDefaultOpIds");
            checkEq(result.seeds().registrationFor(ENDPOINT).layout(),
                payload.layout(),
                "the payload's layout is the registered declaration layout");
            checkEq(List.of("path"),
                payload.fieldBoundaries().stream()
                    .map(KindPayload.FieldBoundary::field).toList(),
                "the field boundaries are the provided fields in literal order");
            check(payload.fieldBoundaries().stream().allMatch(entry ->
                    entry.kind() == BoundaryKind.CLASS_LITERAL_FIELD),
                "every field boundary is a CLASS_LITERAL_FIELD (the loaded "
                    + "defaults are data, never in-project default expressions)");
            check(!payload.layout().fields().isEmpty()
                    && payload.layout().fields().stream().allMatch(field ->
                        field.defaultOwner() == DefaultOwner.HOST_DEFAULTS),
                "the registered declaration layout carries the HOST_DEFAULTS "
                    + "field owner");

            // The composed per-unit chain re-run with the seeds: the unified
            // unit passes every validator (the class arm included).
            Optional<CompilerDiagnostic> failure =
                SemanticLowerer.validateProjectUnit(app, result.tableOf(APP),
                    new SemanticIrValidator.ComparisonFacts(
                        project.index().interfaceIndexDigest(),
                        SemanticProfile.DEAL_V1_3_INT32,
                        invocation().capabilityRegistryHash()),
                    app.classBindingFacts(),
                    result.registryOf(APP),
                    new JsonDefaultChildTable(Map.of()),
                    project.index().modules().get(APP), Map.of(),
                    result.seeds().registrations());
            check(failure.isEmpty(),
                "the composed chain accepts the host construction unit: "
                    + failure.map(CompilerDiagnostic::message).orElse(""));
        } finally {
            deleteRecursively(project.root());
        }
    }

    private static void testBuiltinErrorConstruction() throws Exception {
        System.out.println("-- a builtin Error literal constructs through the one "
            + "project entry: CLASS_NEW(BUILTIN_DEFAULTS) over the compiler-owned "
            + "layout --");
        RealProject project = compileProject(ERROR_APP_SOURCE, Map.of(), Map.of());
        try {
            SemanticLowerer.ProjectLoweringResult result = lower(project, invocation());
            check(!result.hasErrors() && result.project() != null,
                "the builtin Error literal lowers through the one project entry "
                    + "with zero diagnostics: " + result.diagnostics());
            boolean deferred = false;
            for (CompilerDiagnostic diagnostic : result.diagnostics()) {
                if (diagnostic.message().contains("RETAINED_ABI_DEFERRED")) {
                    deferred = true;
                }
            }
            check(!deferred,
                "checker-valid builtin Error input reports zero "
                    + "RETAINED_ABI_DEFERRED");
            if (result.project() == null) {
                return;
            }
            LoweredModuleUnit app = result.project().modules().get(APP);
            check(app != null, "the app unit is in the closure");
            if (app == null) {
                return;
            }
            SemanticOp classNew = null;
            for (SemanticOp op : app.ops()) {
                if (op.kind() == SemanticOpKind.CLASS_NEW) {
                    classNew = op;
                }
            }
            check(classNew != null, "the unit carries the Error CLASS_NEW");
            if (classNew == null) {
                return;
            }
            checkEq(ClassId.ERROR, ((KindPayload.ClassNewPayload) classNew.payload())
                    .classId(), "the CLASS_NEW classId is the builtin Error class");
            KindPayload.ClassNewPayload payload =
                (KindPayload.ClassNewPayload) classNew.payload();
            checkEq(DefaultOwner.BUILTIN_DEFAULTS, payload.defaultOwner(),
                "the CLASS_NEW defaultOwner is BUILTIN_DEFAULTS");
            check(payload.classFactoryRef() == null,
                "the builtin Error construction carries the null factory ref");
            check(payload.classDefaultOpIds().isEmpty(),
                "the builtin Error construction carries empty classDefaultOpIds");
            checkEq(ClassLayout.BUILTIN_ERROR, payload.layout(),
                "the payload's layout is the compiler-owned builtin Error layout");
            checkEq(List.of("code", "message"),
                payload.providedFields().stream()
                    .map(KindPayload.ProvidedField::name).toList(),
                "the provided fields keep literal order (code, message)");
            checkEq(List.of("code", "message"),
                payload.fieldBoundaries().stream()
                    .map(KindPayload.FieldBoundary::field).toList(),
                "the field boundaries are the declared order (code, message)");
            check(payload.fieldBoundaries().stream().allMatch(entry ->
                    entry.kind() == BoundaryKind.CLASS_LITERAL_FIELD),
                "every field boundary is a CLASS_LITERAL_FIELD (no "
                    + "CLASS_DEFAULT_FIELD: the builtin defaults are compiler "
                    + "constants)");

            // The composed per-unit chain re-run: the unified unit passes
            // every validator with the project's own factory records and the
            // in-project facts (the class arm included).
            Optional<CompilerDiagnostic> failure =
                SemanticLowerer.validateProjectUnit(app, result.tableOf(APP),
                    new SemanticIrValidator.ComparisonFacts(
                        project.index().interfaceIndexDigest(),
                        SemanticProfile.DEAL_V1_3_INT32,
                        invocation().capabilityRegistryHash()),
                    app.classBindingFacts(),
                    result.registryOf(APP),
                    new JsonDefaultChildTable(Map.of()),
                    project.index().modules().get(APP), Map.of());
            check(failure.isEmpty(),
                "the composed chain accepts the builtin Error unit: "
                    + failure.map(CompilerDiagnostic::message).orElse(""));
        } finally {
            deleteRecursively(project.root());
        }
    }

    private static void testExternCDeclarationClassConstruction() throws Exception {
        System.out.println("-- an extern-C declaration class literal resolves to "
            + "the FFI_PLAN seed and lowers CLASS_NEW(FFI_PLAN) through the one "
            + "project entry --");
        RealProject project = compileProject(EXTERN_C_CLASS_APP_SOURCE,
            Map.of("src/native.d.deal", NATIVE_DECLARATION),
            Map.of(NATIVE_SPECIFIER, "src/native.d.deal"));
        try {
            check(project.externCModules().containsKey(NATIVE),
                "the extern-C declaration module carries the real phase-3.9 "
                    + "generated metadata: " + project.externCModules().keySet());
            check(project.surface().moduleIds().contains(NATIVE)
                    && project.surface().require(NATIVE).kind()
                        == HostDeclarationSurface.DeclarationKind.EXTERN_C,
                "the declaration surface classifies the module extern-C: "
                    + project.surface().moduleIds());
            SemanticLowerer.ProjectLoweringResult result = lower(project, invocation());
            check(!result.hasErrors() && result.project() != null,
                "the extern-C declaration class literal lowers through the one "
                    + "project entry with zero diagnostics: " + result.diagnostics());
            boolean deferred = false;
            for (CompilerDiagnostic diagnostic : result.diagnostics()) {
                if (diagnostic.message().contains("RETAINED_ABI_DEFERRED")) {
                    deferred = true;
                }
            }
            check(!deferred,
                "checker-valid extern-C C-struct input reports zero "
                    + "RETAINED_ABI_DEFERRED");
            if (result.project() == null) {
                return;
            }
            check(result.seeds().registrationFor(NATIVE_VEC2) != null
                    && result.seeds().registrationFor(NATIVE_VEC2).owner()
                        == DefaultOwner.FFI_PLAN,
                "the declared extern-C class resolves in the seeds under "
                    + "FFI_PLAN");
            LoweredModuleUnit app = result.project().modules().get(APP);
            check(app != null, "the app unit is in the closure");
            if (app == null) {
                return;
            }
            SemanticOp classNew = null;
            for (SemanticOp op : app.ops()) {
                if (op.kind() == SemanticOpKind.CLASS_NEW) {
                    classNew = op;
                }
            }
            check(classNew != null, "the unit carries the C-struct CLASS_NEW");
            if (classNew == null) {
                return;
            }
            KindPayload.ClassNewPayload payload =
                (KindPayload.ClassNewPayload) classNew.payload();
            checkEq(NATIVE_VEC2, payload.classId(),
                "the CLASS_NEW classId is the declared extern-C class identity");
            checkEq(DefaultOwner.FFI_PLAN, payload.defaultOwner(),
                "the CLASS_NEW defaultOwner is FFI_PLAN");
            check(payload.classFactoryRef() == null,
                "the C-struct construction carries the null factory ref");
            check(payload.classDefaultOpIds().isEmpty(),
                "the C-struct construction carries empty classDefaultOpIds");
            checkEq(result.seeds().registrationFor(NATIVE_VEC2).layout(),
                payload.layout(),
                "the payload's layout is the registered C-struct layout");
            check(payload.layout().fields().stream().allMatch(field ->
                    field.required()
                        && field.defaultOwner() == DefaultOwner.FFI_PLAN),
                "every registered struct field is required-present under "
                    + "FFI_PLAN");
            checkEq(List.of("x", "y"),
                payload.fieldBoundaries().stream()
                    .map(KindPayload.FieldBoundary::field).toList(),
                "the field boundaries are the provided fields in literal order");
            check(payload.fieldBoundaries().stream().allMatch(entry ->
                    entry.kind() == BoundaryKind.CLASS_LITERAL_FIELD),
                "every field boundary is a CLASS_LITERAL_FIELD (no "
                    + "CLASS_DEFAULT_FIELD: the omitted fields are the loaded "
                    + "plan's)");
            check(!app.classLayouts().containsKey(NATIVE_VEC2),
                "the seed layout is never merged into the unit's own "
                    + "classLayouts");

            // The composed per-unit chain re-run with the seeds: the unified
            // unit passes every validator (the class arm included).
            Optional<CompilerDiagnostic> failure =
                SemanticLowerer.validateProjectUnit(app, result.tableOf(APP),
                    new SemanticIrValidator.ComparisonFacts(
                        project.index().interfaceIndexDigest(),
                        SemanticProfile.DEAL_V1_3_INT32,
                        invocation().capabilityRegistryHash()),
                    app.classBindingFacts(),
                    result.registryOf(APP),
                    new JsonDefaultChildTable(Map.of()),
                    project.index().modules().get(APP), Map.of(),
                    result.seeds().registrations());
            check(failure.isEmpty(),
                "the composed chain accepts the C-struct construction unit: "
                    + failure.map(CompilerDiagnostic::message).orElse(""));
        } finally {
            deleteRecursively(project.root());
        }
    }

    private static void assertFailsClosed(String appSource,
            Map<String, String> extraSources, Map<String, String> extraExternals,
            String what, String expectedClassText) throws Exception {
        RealProject project = compileProject(appSource, extraSources, extraExternals);
        try {
            checkFailsClosed(lower(project, invocation()), what, expectedClassText);
        } finally {
            deleteRecursively(project.root());
        }
    }

    /**
     * The four fail-closed assertions of a seeded-owner construction (the
     * intended intermediate state): the first E6005 from the
     * class-construction validator, capability {@code CLASSES}, naming the
     * seeded class identity, with no project, no tables, no registries, no
     * seeds, and no namespace registrations.
     */
    private static void checkFailsClosed(SemanticLowerer.ProjectLoweringResult result,
            String what, String expectedClassText) {
        check(result.hasErrors() && result.project() == null,
            what + " fails closed with no project: " + result.diagnostics());
        check(result.tables().isEmpty() && result.registries().isEmpty()
                && result.seeds() == null && result.namespaces() == null,
            what + " fails closed with no tables, no registries, no seeds, and "
                + "no registrations");
        if (result.diagnostics().isEmpty()) {
            return;
        }
        CompilerDiagnostic diagnostic = result.diagnostics().get(0);
        check("E6005".equals(diagnostic.code()),
            what + " fails with E6005; got " + diagnostic.code());
        check(diagnostic.message().contains(
                ClassConstructionValidator.CONSTRUCTION_COHERENCE),
            what + " fails at the class-construction validator; got "
                + diagnostic.message());
        check(diagnostic.message().contains("capability CLASSES"),
            what + " carries capability CLASSES; got " + diagnostic.message());
        if (expectedClassText != null) {
            check(diagnostic.message().contains(expectedClassText),
                what + " names the seeded class identity " + expectedClassText
                    + " (the seed lookup resolved it); got "
                    + diagnostic.message());
        }
    }

    // =========================================================================
    // 6. The negative seeds
    // =========================================================================

    private static void testCorruptedUnitSeed(LoweredProject baseline)
            throws Exception {
        System.out.println("-- the corrupted-unit negative: a wrong boundary "
            + "triple fails the unit chain --");
        RealProject project = baseline.project();
        {
            SemanticLowerer.ProjectLoweringResult result = baseline.result();
            if (result.project() == null) {
                fail("the project lowers: " + result.diagnostics());
                return;
            }
            LoweredModuleUnit app = result.project().modules().get(APP);
            LoweredModuleUnit corrupted = corruptReturnBoundary(app);
            check(corrupted != null, "the corrupted unit is built (a RETURN names "
                + "an unknown return boundary)");
            if (corrupted == null) {
                return;
            }
            Optional<CompilerDiagnostic> failure =
                SemanticLowerer.validateProjectUnit(corrupted,
                    result.tableOf(APP),
                    new SemanticIrValidator.ComparisonFacts(
                        project.index().interfaceIndexDigest(),
                        SemanticProfile.DEAL_V1_3_INT32,
                        invocation().capabilityRegistryHash()),
                    corrupted.classBindingFacts(),
                    new ClassFactoryRegistry(Map.of()),
                    new JsonDefaultChildTable(Map.of()),
                    project.index().modules().get(APP), Map.of());
            check(failure.isPresent(),
                "the corrupted unit fails the composed chain");
            if (failure.isEmpty()) {
                return;
            }
            check("E6005".equals(failure.get().code())
                    && failure.get().message().contains(
                        SemanticIrValidator.R_BOUNDARY_TRIPLE),
                "the corrupted unit returns the first E6005 R-BOUNDARY-TRIPLE; got "
                    + failure.get().message());
        }
    }

    /**
     * Rebuilds a lowered unit with the {@code RETURN}'s return boundary op
     * dropped from the produced op list — the dangling return identity of
     * the corrupted-unit negative (a hand-built closed-rule violation; the
     * op payloads stay untouched, so the op contract digests stay valid and
     * the closed gate is the rejecting member).
     */
    private static LoweredModuleUnit corruptReturnBoundary(LoweredModuleUnit unit) {
        List<SemanticOp> ops = new ArrayList<>(unit.ops());
        for (SemanticOp op : unit.ops()) {
            if (op.kind() == SemanticOpKind.RETURN
                    && op.payload() instanceof KindPayload.ReturnPayload payload) {
                List<SemanticOp> corrupted = new ArrayList<>();
                boolean dropped = false;
                for (SemanticOp candidate : ops) {
                    if (!dropped
                            && candidate.opId().equals(payload.returnBoundaryOpId())) {
                        dropped = true;
                        continue;
                    }
                    corrupted.add(candidate);
                }
                if (!dropped) {
                    return null;
                }
                return withOps(unit, corrupted);
            }
        }
        return null;
    }

    /** One lowered unit with the given op list (the corruption surface). */
    private static LoweredModuleUnit withOps(LoweredModuleUnit unit,
                                             List<SemanticOp> ops) {
        return new LoweredModuleUnit(unit.formatVersion(), unit.semanticProfile(),
            unit.moduleId(), unit.interfaceHash(), unit.loweringContextHash(),
            unit.requiredCapabilities(), unit.constructCoverage(),
            unit.classLayouts(), unit.functions(), unit.moduleInit(),
            unit.exportPlan(), unit.functionBindings(), ops, unit.bodyTable(),
            unit.classFactories(), unit.jsonDefaults(), unit.classBindingFacts());
    }

    private static void testMissingModuleSeed(LoweredProject baseline)
            throws Exception {
        System.out.println("-- the missing-module negative: a reference outside "
            + "the closure fails the project-form R-EXTERNAL-ENTRY arm --");
        RealProject project = baseline.project();
        {
            SemanticLowerer.ProjectLoweringResult result = baseline.result();
            if (result.project() == null) {
                fail("the project lowers: " + result.diagnostics());
                return;
            }
            Map<ModuleId, LoweredModuleUnit> shortened = new LinkedHashMap<>();
            for (Map.Entry<ModuleId, LoweredModuleUnit> entry
                    : result.project().modules().entrySet()) {
                if (!entry.getKey().equals(UTIL)) {
                    shortened.put(entry.getKey(), entry.getValue());
                }
            }
            ExecutableLoweredProject missing = new ExecutableLoweredProject(
                SemanticProfile.DEAL_V1_3_INT32, project.index(), shortened, APP,
                result.project().classSeeds());
            Optional<CompilerDiagnostic> failure = SemanticIrValidator.validate(
                missing, new SemanticIrValidator.ComparisonFacts(
                    project.index().interfaceIndexDigest(),
                    SemanticProfile.DEAL_V1_3_INT32,
                    invocation().capabilityRegistryHash()));
            check(failure.isPresent(),
                "the project with the missing callee module fails the project-form "
                    + "gate");
            if (failure.isEmpty()) {
                return;
            }
            check("E6005".equals(failure.get().code())
                    && failure.get().message().contains(
                        SemanticIrValidator.R_EXTERNAL_ENTRY)
                    && failure.get().message().contains("outside the implementation "
                        + "closure"),
                "the missing module returns the first E6005 R-EXTERNAL-ENTRY naming "
                    + "the closure violation; got " + failure.get().message());
        }
    }

    private static void testUnresolvedAliasSeed(LoweredProject baseline)
            throws Exception {
        System.out.println("-- the unresolved-alias negative: an alias without a "
            + "resolved import fact fails the import arm --");
        RealProject project = baseline.project();
        {
            List<CheckedModuleInput> modules = new ArrayList<>();
            for (CheckedModuleInput module : project.checkedProject().modules()) {
                modules.add(module.moduleId().equals(APP)
                    ? new CheckedModuleInput(module.moduleId(), module.sourceId(),
                        module.sourcePath(), module.ast(), module.checks(),
                        List.of(), module.exports(), module.kind())
                    : module);
            }
            CompilerInvocation invocation = invocation();
            CheckedProjectInput stripped = new CheckedProjectInput(invocation,
                project.checkedProject().entryModule(), modules,
                invocation.releaseStateHash());
            SemanticLowerer.ProjectLoweringResult result = SemanticLowerer.lowerProject(
                invocation, stripped, project.index(), project.manifests(),
                project.surface(), project.declarationIdentities(),
                project.externCModules(),
                BuiltinErrorDeclaration.synthesized(
                    project.checkedProject().modules().get(0).ast().span()),
                List.of(IntrinsicKind.INT_CONVERT, IntrinsicKind.NUMBER_CONVERT,
                IntrinsicKind.BYTES_NEW),
                Set.of());
            check(result.hasErrors() && result.project() == null,
                "the unresolved alias produces no project");
            check(result.tables().isEmpty() && result.registries().isEmpty()
                    && result.seeds() == null && result.namespaces() == null,
                "the unresolved alias produces no tables, no registries, no seeds, "
                    + "and no registrations");
            if (!result.diagnostics().isEmpty()) {
                CompilerDiagnostic diagnostic = result.diagnostics().get(0);
                check("E6005".equals(diagnostic.code())
                        && diagnostic.message().contains(
                            SemanticLowerer.CONSTRUCT_UNLOWERED)
                        && diagnostic.message().contains(
                            "without a resolved import fact"),
                    "the unresolved alias fails the import arm with "
                        + "CONSTRUCT_UNLOWERED; got " + diagnostic.message());
            }
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

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
                try (var walk = Files.walk(path)) {
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

    /** The unit's op named by the id, or {@code null}. */
    private static SemanticOp opOf(LoweredModuleUnit unit, OpId opId) {
        for (SemanticOp op : unit.ops()) {
            if (op.opId().equals(opId)) {
                return op;
            }
        }
        return null;
    }

    private static void testCompleteUnitTransport(RealProject project,
            ExecutableLoweredProject lowered) {
        SemanticIrValidator.ComparisonFacts facts = new SemanticIrValidator.ComparisonFacts(
            project.index().interfaceIndexDigest(), SemanticProfile.DEAL_V1_3_INT32,
            invocation().capabilityRegistryHash());
        boolean sawCaptures = false;
        boolean sawImportAliases = false;
        boolean sawClassLayout = false;
        for (LoweredModuleUnit unit : lowered.modules().values()) {
            String text = SemanticIrValidator.toUnitText(unit);
            checkEq(SemanticIrDumper.dumpModuleText(unit), text,
                unit.moduleId() + ": dump uses canonical unit mapping");
            RawUnit reparsed = ContractSnapshotCanonicalizer.parseUnit(
                (CanonicalJson.Obj) CanonicalJson.parse(text));
            checkEq(RawUnit.fromTyped(unit), reparsed,
                unit.moduleId() + ": every unit fact round-trips");
            checkEq(unit.bodyTable(), reparsed.bodyTable(),
                unit.moduleId() + ": both membership directions survive");
            checkEq(unit.classFactories(), reparsed.classFactories(),
                unit.moduleId() + ": factory registry survives");
            checkEq(unit.jsonDefaults(), reparsed.jsonDefaults(),
                unit.moduleId() + ": JSON-default rows survive");
            checkEq(unit.classBindingFacts(), reparsed.classBindingFacts(),
                unit.moduleId() + ": parameter/import facts survive");
            checkEq(text, CanonicalJson.serializeText(ContractSnapshotCanonicalizer.toJson(reparsed)),
                unit.moduleId() + ": canonical text is byte-stable");
            Optional<CompilerDiagnostic> readmitted = SemanticIrValidator.validateText(text, facts);
            check(readmitted.isEmpty(), unit.moduleId() + ": text re-admits: " + readmitted);
            sawCaptures |= reparsed.functions().stream().anyMatch(f -> !f.captures().isEmpty());
            sawClassLayout |= !reparsed.classLayouts().isEmpty();
            sawImportAliases |= !reparsed.classBindingFacts().importAliases().isEmpty();
            for (RawUnit.RawClassLayout entry : reparsed.classLayouts()) {
                checkEq(entry.key(), entry.layout().classId(),
                    unit.moduleId() + ": layout key matches contained identity");
            }
        }
        check(sawClassLayout && sawCaptures && sawImportAliases,
            "real transport exercises layouts, closure generations and import aliases");
    }


    /**
     * The complete project transport, the interface index and the class
     * seeds (ISSUE-0818): the project text round-trips the exact project —
     * the {@code deal.semantic-ir/2} envelope, the profile, the entry
     * module, the complete {@code deal.semantic-interface/1} index, the
     * class registration seeds and every dependency-ordered unit through
     * the complete unit mapping — and a dumped manifest plus its ordered
     * module documents reassembles to that same project. Missing, extra,
     * duplicate, misordered and mismatched document sets reject before any
     * project record exists, and so does a manifest whose class
     * registration seeds contradict its own interface index (the two are
     * two representations of one declaration surface, never independent
     * class authorities).
     */
    private static void testCompleteProjectTransportAndDumpReassembly(LoweredProject baseline)
            throws Exception {
        System.out.println("-- the complete /2 project transport, the interface "
            + "index and the seeds, and the checked dump reassembly --");
        SemanticLowerer.ProjectLoweringResult result = baseline.result();
        ExecutableLoweredProject lowered = result.project();
        check(lowered != null,
            "the project-transport fixture lowers: " + result.diagnostics());
        if (lowered == null) {
            return;
        }
        SemanticIrValidator.ComparisonFacts facts = new SemanticIrValidator.ComparisonFacts(
            baseline.project().index().interfaceIndexDigest(),
            SemanticProfile.DEAL_V1_3_INT32, invocation().capabilityRegistryHash());

        // ----- the project text: the complete framing and the unit mapping -----
        String text = SemanticIrValidator.toProjectText(lowered);
        check(text.contains("\"formatVersion\":\"deal.semantic-ir/2\""),
            "the project text carries the deal.semantic-ir/2 envelope");
        check(text.contains("\"entryModule\":\"app\""),
            "the project entry module stays a plain string");
        check(text.contains("\"interfaceIndex\":{\"formatVersion\":"
                + "\"deal.semantic-interface/1\""),
            "the project text carries the interface index under its unchanged format");
        check(text.contains("\"classSeeds\":[{\"key\":"),
            "the project text carries the class registration seeds");
        ContractSnapshotCanonicalizer.ParsedProject parsed =
            ContractSnapshotCanonicalizer.parseProject(
                (CanonicalJson.Obj) CanonicalJson.parse(text));
        checkEq("deal.semantic-ir/2", parsed.formatVersion(),
            "the parsed project keeps the /2 envelope");
        checkEq("DEAL_V1_3_INT32", parsed.semanticProfile(),
            "the parsed project keeps the explicit semantic profile");
        checkEq("app", parsed.entryModule(),
            "the parsed project keeps the entry module");
        checkEq(List.of("util", "app"), parsed.modulePaths(),
            "the parsed project keeps the dependency-ordered module paths");
        checkEq(lowered.interfaceIndex(), parsed.interfaceIndex(),
            "the interface index inverse retains every module/interface/import/class/field "
                + "fact");
        checkEq(lowered.classSeeds(), parsed.classSeeds(),
            "the class seeds inverse keeps the registration keys, layouts and owners");
        checkEq(baseline.project().index().interfaceIndexDigest(),
            parsed.interfaceIndex().interfaceIndexDigest(),
            "the reparsed index keeps its digest");
        for (Map.Entry<ModuleId, ExternalModuleInterface> entry
                : parsed.interfaceIndex().modules().entrySet()) {
            checkEq(entry.getKey(), entry.getValue().moduleId(),
                "the index inverse keeps the " + entry.getKey() + " entry identity");
        }
        ExternalModuleInterface appEntry = parsed.interfaceIndex().modules().get(APP);
        check(appEntry != null, "the index inverse carries the entry module entry");
        if (appEntry != null) {
            checkEq(List.of("u", "cfg"),
                appEntry.imports().stream().map(ResolvedImport::alias).toList(),
                "the index inverse keeps the resolved import aliases in source order");
            checkEq(List.of(ExternalModuleKind.IMPLEMENTATION, ExternalModuleKind.HOST),
                appEntry.imports().stream().map(ResolvedImport::kind).toList(),
                "the index inverse keeps the resolved import kinds");
            checkEq(List.of("main"),
                appEntry.exports().stream().map(ExportInterface::name).toList(),
                "the index inverse keeps the export entries");
        }
        ExternalModuleInterface hostEntry = parsed.interfaceIndex().modules().get(HOST_CFG);
        check(hostEntry != null && hostEntry.kind() == ExternalModuleKind.HOST,
            "the index inverse keeps the host declaration entry");
        ClassInterface endpoint = hostEntry == null || hostEntry.classes().isEmpty()
            ? null : hostEntry.classes().get(0);
        check(endpoint != null
                && endpoint.classId().equals(new ClassId(HOST_CFG.path(), "Endpoint")),
            "the index inverse keeps the declared class identity");
        if (endpoint != null) {
            checkEq(List.of("path", "port"),
                endpoint.fields().stream().map(FieldInterface::name).toList(),
                "the index inverse keeps the declared field order");
            check(endpoint.fields().get(1).optional() && !endpoint.fields().get(1).nullable(),
                "the index inverse keeps the declared optional/nullable field facts");
        }
        ClassRegistrationSeeds.ClassRegistration endpointSeed =
            parsed.classSeeds().registrationFor(ENDPOINT);
        check(endpointSeed != null && endpointSeed.owner() == DefaultOwner.HOST_DEFAULTS,
            "the seeds inverse keeps the declared class registration and its owner");
        ClassRegistrationSeeds.ClassRegistration errorSeed =
            parsed.classSeeds().registrationFor(BUILTIN_ERROR);
        check(errorSeed != null && errorSeed.owner() == DefaultOwner.BUILTIN_DEFAULTS
                && errorSeed.layout().equals(ClassLayout.BUILTIN_ERROR),
            "the seeds inverse keeps the compiler-owned builtin Error registration");
        List<LoweredModuleUnit> units = List.copyOf(lowered.modules().values());
        checkEq(units.size(), parsed.modules().size(),
            "every project module is carried");
        for (int i = 0; i < Math.min(units.size(), parsed.modules().size()); i++) {
            checkEq(RawUnit.fromTyped(units.get(i)), parsed.modules().get(i),
                "module " + units.get(i).moduleId()
                    + " round-trips every complete unit fact");
            checkEq(units.get(i).bodyTable(), parsed.modules().get(i).bodyTable(),
                "module " + units.get(i).moduleId() + " keeps both membership directions");
            checkEq(units.get(i).classFactories(), parsed.modules().get(i).classFactories(),
                "module " + units.get(i).moduleId() + " keeps its factory registry");
            checkEq(units.get(i).jsonDefaults(), parsed.modules().get(i).jsonDefaults(),
                "module " + units.get(i).moduleId() + " keeps its JSON-default rows");
            checkEq(units.get(i).classBindingFacts(),
                parsed.modules().get(i).classBindingFacts(),
                "module " + units.get(i).moduleId()
                    + " keeps its parameter/import facts");
        }
        checkEq(text, CanonicalJson.serializeText(
                ContractSnapshotCanonicalizer.toJson(parsed)),
            "the reparsed project re-serializes byte-identically");
        Optional<CompilerDiagnostic> readmitted =
            SemanticIrValidator.validateText(text, facts);
        check(readmitted.isEmpty(),
            "the project text re-admits through the text surface: "
                + readmitted.map(CompilerDiagnostic::message).orElse(""));

        // ----- the dump manifest and the ordered module documents -----
        SemanticIrDumper.ProjectDump dump = SemanticIrDumper.dumpProject(lowered);
        SemanticIrDumper.ProjectDump repeated = SemanticIrDumper.dumpProject(lowered);
        check(java.util.Arrays.equals(dump.manifest(), repeated.manifest()),
            "repeated project manifests are byte-identical");
        checkEq(dump.modules().size(), repeated.modules().size(),
            "both repeats carry every module document");
        for (int i = 0; i < Math.min(dump.modules().size(), repeated.modules().size()); i++) {
            check(java.util.Arrays.equals(dump.modules().get(i).bytes(),
                    repeated.modules().get(i).bytes()),
                "the repeated dump of module " + dump.modules().get(i).moduleId()
                    + " is byte-identical");
        }
        String manifestText = dump.manifestText();
        check(manifestText.contains("\"formatVersion\":\"deal.semantic-ir/2\"")
                && manifestText.contains("\"entryModule\":\"app\"")
                && manifestText.contains("\"semanticProfile\":\"DEAL_V1_3_INT32\""),
            "the manifest carries the project's formatVersion, entryModule and profile");
        check(manifestText.contains("\"interfaceIndex\":{\"formatVersion\":"
                + "\"deal.semantic-interface/1\"")
                && manifestText.contains("\"classSeeds\":[{\"key\":"),
            "the manifest carries the complete interface index and the class seeds");
        check(manifestText.contains("\"modules\":[\"util\",\"app\"]"),
            "the manifest modules field stays the ordered module-path list");
        CanonicalJson.Obj manifest = objOf(dump.manifest());
        for (String unitFact : List.of("bodyTable", "classLayouts", "functions", "ops",
                "classFactories", "jsonDefaults", "classBindingFacts", "moduleInit",
                "exportPlan", "functionBindings")) {
            check(at(manifest, unitFact) == null,
                "the manifest carries no unit fact (" + unitFact + ")");
        }
        ContractSnapshotCanonicalizer.ParsedProject reassembled =
            SemanticIrDumper.reassembleProject(dump);
        checkEq(parsed, reassembled,
            "the manifest plus the ordered module documents reassemble to the exact "
                + "project (modules and order, entry, profile, index, seeds and units)");
        checkEq(lowered.semanticProfile().name(), reassembled.semanticProfile(),
            "the reassembled project carries the typed project's profile");
        checkEq(lowered.entryModule().path(), reassembled.entryModule(),
            "the reassembled project carries the typed project's entry module");
        checkEq(lowered.interfaceIndex(), reassembled.interfaceIndex(),
            "the reassembled interface index is the typed project's index");
        checkEq(lowered.classSeeds(), reassembled.classSeeds(),
            "the reassembled class seeds are the typed project's seeds");
        checkEq(List.of("util", "app"), reassembled.modulePaths(),
            "the reassembled module order is the dependency order");
        List<byte[]> documents = documentsOf(dump);
        for (int i = 0; i < Math.min(units.size(), reassembled.modules().size()); i++) {
            checkEq(RawUnit.fromTyped(units.get(i)), reassembled.modules().get(i),
                "module " + units.get(i).moduleId()
                    + " reassembles every complete unit fact");
            checkEq(units.get(i).bodyTable(), reassembled.modules().get(i).bodyTable(),
                "module " + units.get(i).moduleId()
                    + " reassembles both membership directions");
            checkEq(units.get(i).classFactories(),
                reassembled.modules().get(i).classFactories(),
                "module " + units.get(i).moduleId() + " reassembles its factory registry");
            checkEq(units.get(i).jsonDefaults(),
                reassembled.modules().get(i).jsonDefaults(),
                "module " + units.get(i).moduleId()
                    + " reassembles its JSON-default rows");
            checkEq(units.get(i).classBindingFacts(),
                reassembled.modules().get(i).classBindingFacts(),
                "module " + units.get(i).moduleId()
                    + " reassembles its parameter/import facts");
        }

        // The written dump (existing file naming and LF framing) reassembles too.
        Path dumpRoot = Files.createTempDirectory("semantic-project-dump");
        try {
            SemanticIrDumper.dumpProjectTo(dumpRoot, lowered);
            byte[] manifestFile = Files.readAllBytes(
                dumpRoot.resolve(SemanticIrDumper.MANIFEST_FILE_NAME));
            check(java.util.Arrays.equals(withLf(dump.manifest()), manifestFile),
                "the written manifest is the canonical document plus exactly one LF");
            List<byte[]> fileDocuments = new ArrayList<>();
            for (ModuleId module : lowered.modules().keySet()) {
                fileDocuments.add(Files.readAllBytes(dumpRoot
                    .resolve(SemanticIrDumper.MODULES_DIR_NAME)
                    .resolve(SemanticIrDumper.moduleFileName(module))));
            }
            checkEq(parsed, SemanticIrDumper.reassembleProject(manifestFile, fileDocuments),
                "the written dump reassembles to the exact project");
        } finally {
            deleteRecursively(dumpRoot);
        }

        // ----- the rejected document sets start no admission -----
        List<byte[]> missing = new ArrayList<>(documents);
        missing.remove(0);
        expectReassemblyFailure(dump.manifest(), missing,
            "a missing module document rejects");
        List<byte[]> extra = new ArrayList<>(documents);
        extra.add(documents.get(0));
        expectReassemblyFailure(dump.manifest(), extra,
            "an extra module document rejects");
        List<byte[]> duplicated = new ArrayList<>();
        duplicated.add(documents.get(0));
        duplicated.add(documents.get(0));
        expectReassemblyFailure(dump.manifest(), duplicated,
            "a duplicated module document rejects");
        List<byte[]> misordered = new ArrayList<>(documents);
        java.util.Collections.reverse(misordered);
        expectReassemblyFailure(dump.manifest(), misordered,
            "misordered module documents reject");
        List<byte[]> foreign = new ArrayList<>(documents);
        foreign.set(foreign.size() - 1, withModulePath(
            documents.get(foreign.size() - 1), "foreign"));
        expectReassemblyFailure(dump.manifest(), foreign,
            "a document carrying a foreign module identity rejects");
        expectReassemblyFailure(manifestWithModulePaths(dump.manifest(), "util", "utilx"),
            documents, "a manifest module path that no document carries rejects");
        expectReassemblyFailure(manifestWithModulePaths(dump.manifest(), "util", "util"),
            documents, "a duplicate manifest module path rejects");
        expectReassemblyFailure(manifestWithModulePaths(dump.manifest(), "util"),
            documents, "a manifest module list shorter than the documents rejects");
        expectReassemblyFailure(manifestWithoutModule(dump.manifest(), "app"), documents,
            "a manifest without its entry module rejects");
        expectReassemblyFailure(manifestWithDuplicatedIndexModule(dump.manifest()), documents,
            "a duplicate interface index module identity rejects");
        expectReassemblyFailure(manifestWithDuplicatedSeed(dump.manifest()), documents,
            "a duplicate class-seed registration key rejects");
        expectReassemblyFailure(manifestWithMutatedIndexModule(dump.manifest()), documents,
            "a manifest index that no longer agrees with the documents rejects");
        expectReassemblyFailure(manifestWithSeedOwner(dump.manifest(), "Endpoint", "LOCAL"),
            documents,
            "a manifest seed whose owner disagrees with its field owners rejects");
        expectReassemblyFailure(manifestWithSeedOwner(dump.manifest(), "Endpoint",
            "NOT_AN_OWNER"), documents,
            "a manifest seed carrying an unknown owner rejects");
        expectReassemblyFailure(manifestWithSeedFieldDescriptor(dump.manifest(),
            "Endpoint", "path", "int"), documents,
            "a manifest seed whose field descriptor contradicts the interface's "
                + "declared type rejects");
        expectReassemblyFailure(manifestWithSeedFieldDescriptor(dump.manifest(),
            "Endpoint", "path", "?int"), documents,
            "a manifest seed whose nullable field descriptor contradicts the "
                + "interface's declared type rejects");
        expectReassemblyFailure(manifestWithSeedFieldDescriptor(dump.manifest(),
            "Endpoint", "port", "string"), documents,
            "a manifest seed field descriptor mutation on a non-defaulted field "
                + "rejects");
        for (String truncated : List.of("(", "(int")) {
            expectReassemblyFailure(manifestWithSeedFieldDescriptor(dump.manifest(),
                "Endpoint", "path", truncated), documents,
                "a truncated seed descriptor \"" + truncated + "\" rejects as a "
                    + "transport decode failure, never an unchecked parser failure");
        }
        expectReassemblyFailure(manifestWithSeedKey(dump.manifest(), "Endpoint",
            "EndpointElsewhere"), documents,
            "a manifest seed whose registration key disagrees with its contained "
                + "layout identity rejects");
        expectReassemblyFailure(manifestWithoutSeed(dump.manifest(), "Endpoint"),
            documents,
            "a manifest that drops an exported declaration class's seed rejects");
        expectReassemblyFailure(withNewEntry(dump.manifest(), "bodyTable",
            CanonicalJson.obj()), documents,
            "a manifest that gains a unit fact rejects");
        expectReassemblyFailure(dump.manifest(), List.of(),
            "an empty document list rejects");
    }

    /**
     * The project-level seed/module join and the common admission of the
     * transported class facts (ISSUE-0818 review): the class registration
     * seeds and the module documents are records of one declaration/
     * construction surface, so a manifest whose registration owner is
     * internally consistent (the owner member and every field owner agree)
     * yet contradicts the referring {@code CLASS_NEW}'s default owner is
     * rejected at checked reassembly; the same disagreement and the seed
     * field-descriptor disagreement reject on both project admission
     * surfaces; a mutated carried interface index rejects through the
     * carried index digest. Each rejected case yields no project record
     * and, on admission, exactly the first responsible E6005.
     */
    private static void testProjectSeedModuleJoinAndAdmission() throws Exception {
        System.out.println("-- the project seed/module join: the checked reassembly "
            + "and the typed/text admission of the carried class facts --");
        RealProject project = compileProject(HOST_CLASS_APP_SOURCE, Map.of(), Map.of());
        try {
            SemanticLowerer.ProjectLoweringResult result = lower(project, invocation());
            check(!result.hasErrors() && result.project() != null,
                "the host-class transport project lowers: " + result.diagnostics());
            if (result.project() == null) {
                return;
            }
            ExecutableLoweredProject lowered = result.project();
            SemanticIrValidator.ComparisonFacts facts =
                new SemanticIrValidator.ComparisonFacts(
                    project.index().interfaceIndexDigest(),
                    SemanticProfile.DEAL_V1_3_INT32,
                    invocation().capabilityRegistryHash());

            // The unmutated project admits on both surfaces.
            check(SemanticIrValidator.validate(lowered, facts).isEmpty(),
                "the host-class project passes the typed project surface");
            String text = SemanticIrValidator.toProjectText(lowered);
            check(SemanticIrValidator.validateText(text, facts).isEmpty(),
                "the host-class project passes the text project surface");

            // (1) Checked reassembly: the internally consistent seed-owner
            // mutation (the registration owner and every field owner) passes
            // the index/seed rule, yet the module document's referring
            // CLASS_NEW still carries HOST_DEFAULTS, so the join rejects.
            SemanticIrDumper.ProjectDump dump = SemanticIrDumper.dumpProject(lowered);
            List<byte[]> documents = documentsOf(dump);
            check(ClassConstructionValidator.checkProjectSeedCoherence(
                    lowered.interfaceIndex(), lowered.classSeeds()).isEmpty(),
                "the unmutated index and seeds cohere under the index/seed rule");
            expectReassemblyFailure(manifestWithSeedOwnerAndFieldOwners(dump.manifest(),
                "Endpoint", "FFI_PLAN"), documents,
                "a manifest whose seed registration owner and field owners are both "
                    + "mutated to another declaration owner rejects against the "
                    + "module document's referring CLASS_NEW");

            // (2) Text admission: the same internally consistent mutation and
            // the seed field-descriptor mutation are rejected by the class
            // validation owner's rule, never silently admitted.
            expectProjectAdmissionFailure(SemanticIrValidator.validateText(
                    asText(manifestWithSeedOwnerAndFieldOwners(
                        text.getBytes(StandardCharsets.UTF_8), "Endpoint", "FFI_PLAN")),
                    facts),
                ClassConstructionValidator.CONSTRUCTION_COHERENCE, "defaultOwner",
                "the text project surface rejects the seed owner that contradicts "
                    + "the module's referring CLASS_NEW");
            expectProjectAdmissionFailure(SemanticIrValidator.validateText(
                    asText(manifestWithSeedFieldDescriptor(
                        text.getBytes(StandardCharsets.UTF_8), "Endpoint", "path", "int")),
                    facts),
                ClassConstructionValidator.CONSTRUCTION_COHERENCE, "path",
                "the text project surface rejects the manifest seed descriptor that "
                    + "contradicts the interface's declared field type");

            // (3) Typed admission: a mutated seed set for the same project
            // (the registration owner and every field owner) is rejected by
            // the same rule over the module's referring class fact.
            ExecutableLoweredProject ownerMutated = new ExecutableLoweredProject(
                lowered.semanticProfile(), lowered.interfaceIndex(), lowered.modules(),
                lowered.entryModule(),
                withSeedOwner(lowered.classSeeds(), ENDPOINT, DefaultOwner.FFI_PLAN));
            expectProjectAdmissionFailure(SemanticIrValidator.validate(ownerMutated, facts),
                ClassConstructionValidator.CONSTRUCTION_COHERENCE, "defaultOwner",
                "the typed project surface rejects the seed owner that contradicts "
                    + "the module's referring CLASS_NEW");

            // (4) The carried interface index is consumed on both surfaces:
            // a mutation changes its digest, so the carried digest no longer
            // equals the invocation's supplied digest.
            ProjectInterfaceIndex renamedIndex = withRenamedIndexModule(
                lowered.interfaceIndex(), lowered.interfaceIndex().modules().keySet()
                    .iterator().next(), "renamed.module");
            check(!renamedIndex.interfaceIndexDigest()
                    .equals(lowered.interfaceIndex().interfaceIndexDigest()),
                "the renamed index carries another digest");
            expectProjectAdmissionFailure(SemanticIrValidator.validateText(
                    asText(manifestWithMutatedIndexModule(
                        text.getBytes(StandardCharsets.UTF_8))),
                    facts),
                SemanticIrValidator.R_PROFILE, "interface index digest",
                "the text project surface rejects a mutated carried interface index");
            expectProjectAdmissionFailure(SemanticIrValidator.validate(
                    new ExecutableLoweredProject(lowered.semanticProfile(), renamedIndex,
                        lowered.modules(), lowered.entryModule(), lowered.classSeeds()),
                    facts),
                SemanticIrValidator.R_PROFILE, "interface index digest",
                "the typed project surface rejects a mutated carried interface index");
        } finally {
            deleteRecursively(project.root());
        }
    }

    /**
     * The project seed nominal class reference (ISSUE-0818 review): a seed
     * field descriptor names a declaration class through its exact carried
     * class identity. Moving only the external spelling of the reference
     * (the dotted rendering stays identical) must not admit an unregistered
     * class: the class validation owner resolves the reference through the
     * carried registration at checked reassembly and on both project
     * admission surfaces. A malformed seed descriptor text is a transport
     * decode failure there as well, never an unchecked parser crash.
     */
    private static void testProjectSeedNominalClassReference() throws Exception {
        System.out.println("-- the project seed nominal class reference: the exact "
            + "carried identity, never the dotted rendering --");
        RealProject project = compileProject(HOST_CLASS_REFERENCE_APP_SOURCE,
            Map.of("src/cfg.d.deal", HOST_CLASS_REFERENCE_DECLARATION), Map.of());
        try {
            SemanticLowerer.ProjectLoweringResult result = lower(project, invocation());
            check(!result.hasErrors() && result.project() != null,
                "the class-reference transport project lowers: " + result.diagnostics());
            if (result.project() == null) {
                return;
            }
            ExecutableLoweredProject lowered = result.project();
            SemanticIrValidator.ComparisonFacts facts =
                new SemanticIrValidator.ComparisonFacts(
                    project.index().interfaceIndexDigest(),
                    SemanticProfile.DEAL_V1_3_INT32,
                    invocation().capabilityRegistryHash());
            check(SemanticIrValidator.validate(lowered, facts).isEmpty(),
                "the class-reference project passes the typed project surface");
            String text = SemanticIrValidator.toProjectText(lowered);
            check(SemanticIrValidator.validateText(text, facts).isEmpty(),
                "the class-reference project passes the text project surface");
            check(lowered.classSeeds().registrationFor(HOST_HIDDEN) != null,
                "the declaration class Hidden carries its registration seed");
            ClassRegistrationSeeds.ClassRegistration endpoint =
                lowered.classSeeds().registrationFor(ENDPOINT);
            check(endpoint != null && endpoint.layout().fields().stream()
                    .anyMatch(field -> new RuntimeDescriptor.Class(HOST_HIDDEN)
                        .equals(field.descriptor())),
                "the Endpoint registration carries the class-typed peer descriptor "
                    + "'" + HOST_HIDDEN.text() + "'");

            SemanticIrDumper.ProjectDump dump = SemanticIrDumper.dumpProject(lowered);
            List<byte[]> documents = documentsOf(dump);
            checkEq(ContractSnapshotCanonicalizer.parseProject(
                    objOf(text.getBytes(StandardCharsets.UTF_8))),
                SemanticIrDumper.reassembleProject(dump.manifest(), documents),
                "the class-reference manifest and its documents reassemble to the "
                    + "exact project");

            // The alternate external spelling renders the interface's declared
            // dotted text but names no carried registration: identity is the
            // exact carried registration, never the rendering.
            String alternate = "$external/host.cfg/Hidden";
            expectReassemblyFailure(manifestWithSeedFieldDescriptor(dump.manifest(),
                "Endpoint", "peer", "@" + alternate), documents,
                "a manifest seed field reference to an unregistered external "
                    + "spelling of the declared class rejects");
            expectProjectAdmissionFailure(SemanticIrValidator.validateText(
                    asText(manifestWithSeedFieldDescriptor(
                        text.getBytes(StandardCharsets.UTF_8), "Endpoint", "peer",
                        "@" + alternate)),
                    facts),
                ClassConstructionValidator.CONSTRUCTION_COHERENCE, "peer",
                "the text project surface rejects the unregistered external spelling "
                    + "of the declared class reference");
            expectProjectAdmissionFailure(SemanticIrValidator.validate(
                    new ExecutableLoweredProject(lowered.semanticProfile(),
                        lowered.interfaceIndex(), lowered.modules(),
                        lowered.entryModule(), withSeedFieldDescriptor(
                            lowered.classSeeds(), ENDPOINT, "peer",
                            new RuntimeDescriptor.Class(new ClassId(alternate,
                                "Hidden")))),
                    facts),
                ClassConstructionValidator.CONSTRUCTION_COHERENCE, "peer",
                "the typed project surface rejects the unregistered external spelling "
                    + "of the declared class reference");

            // A truncated function descriptor is a transport decode failure on
            // the reassembly and text admission surfaces alike.
            for (String truncated : List.of("(", "(int")) {
                expectReassemblyFailure(manifestWithSeedFieldDescriptor(dump.manifest(),
                    "Endpoint", "peer", truncated), documents,
                    "a truncated seed descriptor \"" + truncated + "\" rejects at "
                        + "reassembly as a transport decode failure");
                try {
                    SemanticIrValidator.validateText(asText(manifestWithSeedFieldDescriptor(
                        text.getBytes(StandardCharsets.UTF_8), "Endpoint", "peer",
                        truncated)), facts);
                    fail("a truncated seed descriptor \"" + truncated + "\" is a "
                        + "decode failure on the validation surface, never an "
                        + "unchecked parser failure");
                } catch (SemanticIrTextDecodeException expected) {
                    passed++;
                }
            }
        } finally {
            deleteRecursively(project.root());
        }
    }

    /** Imported implementation classes require interfaces, not declaration seeds. */
    private static void testSeedImplementationReferenceAndPrivateFieldRead() throws Exception {
        RealProject imported = compileProject("""
            import * as cfg from "host/cfg";
            export function main(): null { return null; }
            """, Map.of("src/util.deal", "export class Point { x: int = 0; }",
                "src/cfg.d.deal", """
                    import * as u from "util";
                    export class Endpoint { point: u.Point; }
                    """), Map.of());
        try {
            SemanticLowerer.ProjectLoweringResult result = lower(imported, invocation());
            check(!result.hasErrors() && result.project() != null,
                "an imported implementation-class declaration field lowers: " + result.diagnostics());
            if (result.project() != null) {
                ExecutableLoweredProject project = result.project();
                check(project.classSeeds().registrationFor(POINT) == null,
                    "implementation Point does not acquire a declaration seed");
                SemanticIrValidator.ComparisonFacts facts = new SemanticIrValidator.ComparisonFacts(
                    imported.index().interfaceIndexDigest(), project.semanticProfile(),
                    invocation().capabilityRegistryHash());
                check(SemanticIrValidator.validate(project, facts).isEmpty(),
                    "imported implementation-class field admits as typed project");
                check(SemanticIrValidator.validateText(SemanticIrValidator.toProjectText(project),
                    facts).isEmpty(), "imported implementation-class field admits as text");
                SemanticIrDumper.ProjectDump dump = SemanticIrDumper.dumpProject(project);
                checkEq(ContractSnapshotCanonicalizer.parseProject(objOf(
                        SemanticIrValidator.toProjectText(project).getBytes(StandardCharsets.UTF_8))),
                    SemanticIrDumper.reassembleProject(dump.manifest(), documentsOf(dump)),
                    "imported implementation-class declaration field reassembles exactly");
            }
        } finally { deleteRecursively(imported.root()); }

        for (boolean optional : List.of(false, true)) {
            RealProject hidden = compileProject("""
                import * as cfg from "host/cfg";
                export function main(): null {
                  let h = cfg.get();
                """ + (optional ? "let s: string | null = h.secret;" : "let s: string = h.secret;")
                    + " return null; }", Map.of("src/cfg.d.deal",
                        "class Hidden { secret" + (optional ? "?" : "")
                            + ": string; } export function get(): Hidden;"), Map.of());
            try {
                SemanticLowerer.ProjectLoweringResult result = lower(hidden, invocation());
                check(!result.hasErrors() && result.project() != null,
                    "private declaration field-read fixture lowers: " + result.diagnostics());
                if (result.project() == null) { continue; }
                ExecutableLoweredProject project = result.project();
                SemanticIrValidator.ComparisonFacts facts = new SemanticIrValidator.ComparisonFacts(
                    hidden.index().interfaceIndexDigest(), project.semanticProfile(),
                    invocation().capabilityRegistryHash());
                check(SemanticIrValidator.validate(project, facts).isEmpty(),
                    "unchanged private declaration read admits, optional=" + optional);
                String text = SemanticIrValidator.toProjectText(project);
                check(SemanticIrValidator.validateText(text, facts).isEmpty(),
                    "unchanged private declaration read text admits, optional=" + optional);
                SemanticIrDumper.ProjectDump dump = SemanticIrDumper.dumpProject(project);
                checkEq(ContractSnapshotCanonicalizer.parseProject(objOf(
                        text.getBytes(StandardCharsets.UTF_8))),
                    SemanticIrDumper.reassembleProject(dump.manifest(), documentsOf(dump)),
                    "unchanged private declaration read reassembles");
                expectReassemblyFailure(manifestWithSeedFieldDescriptor(dump.manifest(),
                    "Hidden", "secret", "int"), documentsOf(dump),
                    "private seed/read disagreement rejects without CLASS_NEW");
                expectProjectAdmissionFailure(SemanticIrValidator.validateText(asText(
                    manifestWithSeedFieldDescriptor(text.getBytes(StandardCharsets.UTF_8),
                        "Hidden", "secret", "int")), facts),
                    ClassConstructionValidator.CONSTRUCTION_COHERENCE, "secret",
                    "text admission rejects private seed/read disagreement");
                expectProjectAdmissionFailure(SemanticIrValidator.validate(
                    new ExecutableLoweredProject(project.semanticProfile(), project.interfaceIndex(),
                        project.modules(), project.entryModule(), withSeedFieldDescriptor(
                            project.classSeeds(), HOST_HIDDEN, "secret", RuntimeDescriptor.Int.INSTANCE)), facts),
                    ClassConstructionValidator.CONSTRUCTION_COHERENCE, "secret",
                    "typed admission rejects private seed/read disagreement");
            } finally { deleteRecursively(hidden.root()); }
        }
    }

    /**
     * The class registration seeds with one field's descriptor replaced: the
     * mutated registration stays representable (one owner per class), so the
     * descriptor's exact declared-type rendering is the only disagreement.
     */
    private static ClassRegistrationSeeds withSeedFieldDescriptor(
            ClassRegistrationSeeds seeds, ClassId classId, String fieldName,
            RuntimeDescriptor descriptor) {
        Map<ClassId, ClassRegistrationSeeds.ClassRegistration> registrations =
            new LinkedHashMap<>(seeds.registrations());
        ClassRegistrationSeeds.ClassRegistration registration =
            registrations.get(classId);
        List<ClassLayout.FieldLayout> fields = new ArrayList<>();
        for (ClassLayout.FieldLayout field : registration.layout().fields()) {
            fields.add(field.name().equals(fieldName)
                ? new ClassLayout.FieldLayout(field.name(), descriptor, field.required(),
                    field.defaultOwner())
                : field);
        }
        registrations.put(classId, new ClassRegistrationSeeds.ClassRegistration(
            new ClassLayout(registration.layout().classId(), fields),
            registration.owner()));
        return new ClassRegistrationSeeds(registrations);
    }

    /** The canonical document's UTF-8 text (the admission surfaces consume text). */
    private static String asText(byte[] canonicalJson) {
        return new String(canonicalJson, StandardCharsets.UTF_8);
    }

    /**
     * Asserts that one project admission rejects with exactly the expected
     * first E6005 rule and detail: a rejected project exposes no project
     * record and starts no admission of a companion.
     */
    private static void expectProjectAdmissionFailure(
            Optional<CompilerDiagnostic> failure, String rule, String contains,
            String message) {
        check(failure.isPresent(), message + " (the admission returned a project)");
        if (failure.isEmpty()) {
            return;
        }
        CompilerDiagnostic diagnostic = failure.get();
        check("E6005".equals(diagnostic.code())
                && diagnostic.message().contains(rule)
                && diagnostic.message().contains(contains),
            message + ": expected E6005 " + rule + " naming \"" + contains
                + "\"; got " + diagnostic.message());
    }

    /**
     * The class registration seeds with one registration's owner member and
     * every field owner replaced: a registration fact stays one owner per
     * class, so the mutation is internally consistent and reaches the
     * seed/module join.
     */
    private static ClassRegistrationSeeds withSeedOwner(ClassRegistrationSeeds seeds,
            ClassId classId, DefaultOwner owner) {
        Map<ClassId, ClassRegistrationSeeds.ClassRegistration> registrations =
            new LinkedHashMap<>(seeds.registrations());
        ClassRegistrationSeeds.ClassRegistration registration =
            registrations.get(classId);
        List<ClassLayout.FieldLayout> fields = new ArrayList<>();
        for (ClassLayout.FieldLayout field : registration.layout().fields()) {
            fields.add(new ClassLayout.FieldLayout(field.name(), field.descriptor(),
                field.required(), owner));
        }
        registrations.put(classId, new ClassRegistrationSeeds.ClassRegistration(
            new ClassLayout(registration.layout().classId(), fields), owner));
        return new ClassRegistrationSeeds(registrations);
    }

    /** The interface index with one module entry's identity renamed. */
    private static ProjectInterfaceIndex withRenamedIndexModule(
            ProjectInterfaceIndex index, ModuleId moduleId, String newPath) {
        Map<ModuleId, ExternalModuleInterface> modules = new LinkedHashMap<>();
        for (Map.Entry<ModuleId, ExternalModuleInterface> entry
                : index.modules().entrySet()) {
            if (entry.getKey().equals(moduleId)) {
                ExternalModuleInterface original = entry.getValue();
                ModuleId renamed = new ModuleId(newPath);
                modules.put(renamed, new ExternalModuleInterface(renamed,
                    original.kind(), original.imports(), original.exports(),
                    original.classes(), original.initialization()));
            } else {
                modules.put(entry.getKey(), entry.getValue());
            }
        }
        return new ProjectInterfaceIndex(ProjectInterfaceIndex.FORMAT_VERSION, modules);
    }

    // ---- manifest/document mutation helpers (checked reassembly negatives) ----

    private static CanonicalJson.Obj objOf(byte[] canonicalJson) {
        return (CanonicalJson.Obj) CanonicalJson.parse(canonicalJson);
    }

    private static CanonicalJson.Value at(CanonicalJson.Obj obj, String key) {
        for (CanonicalJson.Entry entry : obj.entries()) {
            if (entry.key().equals(key)) {
                return entry.value();
            }
        }
        return null;
    }

    private static CanonicalJson.Obj withEntry(CanonicalJson.Obj obj, String key,
            CanonicalJson.Value value) {
        List<CanonicalJson.Entry> entries = new ArrayList<>();
        for (CanonicalJson.Entry entry : obj.entries()) {
            entries.add(entry.key().equals(key) ? CanonicalJson.e(key, value) : entry);
        }
        return CanonicalJson.obj(entries);
    }

    /** The ordered module documents of a dump, as the reassembly consumes them. */
    private static List<byte[]> documentsOf(SemanticIrDumper.ProjectDump dump) {
        List<byte[]> documents = new ArrayList<>();
        for (SemanticIrDumper.ModuleDump module : dump.modules()) {
            documents.add(module.bytes());
        }
        return documents;
    }

    /** One module document whose carried module identity is renamed. */
    private static byte[] withModulePath(byte[] document, String modulePath) {
        return CanonicalJson.serializeBytes(withEntry(objOf(document), "moduleId",
            CanonicalJson.str(modulePath)));
    }

    /** The manifest with its ordered module-path list replaced. */
    private static byte[] manifestWithModulePaths(byte[] manifest, String... paths) {
        CanonicalJson.Obj root = objOf(manifest);
        List<CanonicalJson.Value> items = new ArrayList<>();
        for (String path : paths) {
            items.add(CanonicalJson.str(path));
        }
        return CanonicalJson.serializeBytes(
            withEntry(root, "modules", CanonicalJson.arr(items)));
    }

    /** The manifest with the first index module entry repeated (a duplicate identity). */
    private static byte[] manifestWithDuplicatedIndexModule(byte[] manifest) {
        CanonicalJson.Obj root = objOf(manifest);
        CanonicalJson.Obj index = (CanonicalJson.Obj) at(root, "interfaceIndex");
        CanonicalJson.Arr modules = (CanonicalJson.Arr) at(index, "modules");
        List<CanonicalJson.Value> entries = new ArrayList<>(modules.items());
        entries.add(entries.get(0));
        return CanonicalJson.serializeBytes(withEntry(root, "interfaceIndex",
            withEntry(index, "modules", CanonicalJson.arr(entries))));
    }

    /** The manifest with its first class-seed row repeated (a duplicate registration key). */
    private static byte[] manifestWithDuplicatedSeed(byte[] manifest) {
        CanonicalJson.Obj root = objOf(manifest);
        CanonicalJson.Arr seeds = (CanonicalJson.Arr) at(root, "classSeeds");
        List<CanonicalJson.Value> rows = new ArrayList<>(seeds.items());
        rows.add(rows.get(0));
        return CanonicalJson.serializeBytes(
            withEntry(root, "classSeeds", CanonicalJson.arr(rows)));
    }

    /** The manifest with one module path removed from its ordered list. */
    private static byte[] manifestWithoutModule(byte[] manifest, String modulePath) {
        CanonicalJson.Obj root = objOf(manifest);
        CanonicalJson.Arr modules = (CanonicalJson.Arr) at(root, "modules");
        List<CanonicalJson.Value> items = new ArrayList<>();
        for (CanonicalJson.Value item : modules.items()) {
            if (!((CanonicalJson.Str) item).value().equals(modulePath)) {
                items.add(item);
            }
        }
        return CanonicalJson.serializeBytes(
            withEntry(root, "modules", CanonicalJson.arr(items)));
    }

    /** The manifest with the first index entry's module identity renamed. */
    private static byte[] manifestWithMutatedIndexModule(byte[] manifest) {
        CanonicalJson.Obj root = objOf(manifest);
        CanonicalJson.Obj index = (CanonicalJson.Obj) at(root, "interfaceIndex");
        CanonicalJson.Arr modules = (CanonicalJson.Arr) at(index, "modules");
        List<CanonicalJson.Value> entries = new ArrayList<>(modules.items());
        CanonicalJson.Obj first = (CanonicalJson.Obj) entries.get(0);
        CanonicalJson.Obj moduleId = (CanonicalJson.Obj) at(first, "moduleId");
        entries.set(0, withEntry(first, "moduleId", withEntry(moduleId, "path",
            CanonicalJson.str(((CanonicalJson.Str) at(moduleId, "path")).value() + "x"))));
        return CanonicalJson.serializeBytes(withEntry(root, "interfaceIndex",
            withEntry(index, "modules", CanonicalJson.arr(entries))));
    }

    /** The manifest with the named class seed's owner member replaced. */
    private static byte[] manifestWithSeedOwner(byte[] manifest, String className,
            String owner) {
        CanonicalJson.Obj root = objOf(manifest);
        CanonicalJson.Arr seeds = (CanonicalJson.Arr) at(root, "classSeeds");
        List<CanonicalJson.Value> rows = new ArrayList<>();
        for (CanonicalJson.Value item : seeds.items()) {
            CanonicalJson.Obj row = (CanonicalJson.Obj) item;
            CanonicalJson.Obj key = (CanonicalJson.Obj) at(row, "key");
            if (className.equals(((CanonicalJson.Str) at(key, "name")).value())) {
                row = withEntry(row, "owner", CanonicalJson.str(owner));
            }
            rows.add(row);
        }
        return CanonicalJson.serializeBytes(
            withEntry(root, "classSeeds", CanonicalJson.arr(rows)));
    }

    /**
     * The manifest/project with the named class seed's registration owner
     * member and every field owner replaced: the mutated registration stays
     * one owner per class (a representable registration), so the seed/index
     * rule cannot see the disagreement and only the module join can.
     */
    private static byte[] manifestWithSeedOwnerAndFieldOwners(byte[] manifest,
            String className, String owner) {
        CanonicalJson.Obj root = objOf(manifest);
        CanonicalJson.Arr seeds = (CanonicalJson.Arr) at(root, "classSeeds");
        List<CanonicalJson.Value> rows = new ArrayList<>();
        for (CanonicalJson.Value item : seeds.items()) {
            CanonicalJson.Obj row = (CanonicalJson.Obj) item;
            CanonicalJson.Obj key = (CanonicalJson.Obj) at(row, "key");
            if (className.equals(((CanonicalJson.Str) at(key, "name")).value())) {
                row = withEntry(row, "owner", CanonicalJson.str(owner));
                CanonicalJson.Obj layout = (CanonicalJson.Obj) at(row, "layout");
                CanonicalJson.Arr fields = (CanonicalJson.Arr) at(layout, "fields");
                List<CanonicalJson.Value> updated = new ArrayList<>();
                for (CanonicalJson.Value fieldItem : fields.items()) {
                    updated.add(withEntry((CanonicalJson.Obj) fieldItem, "defaultOwner",
                        CanonicalJson.str(owner)));
                }
                row = withEntry(row, "layout", withEntry(layout, "fields",
                    CanonicalJson.arr(updated)));
            }
            rows.add(row);
        }
        return CanonicalJson.serializeBytes(
            withEntry(root, "classSeeds", CanonicalJson.arr(rows)));
    }

    /** The manifest with one seed field's canonical descriptor text replaced. */
    private static byte[] manifestWithSeedFieldDescriptor(byte[] manifest,
            String className, String fieldName, String descriptor) {
        CanonicalJson.Obj root = objOf(manifest);
        CanonicalJson.Arr seeds = (CanonicalJson.Arr) at(root, "classSeeds");
        List<CanonicalJson.Value> rows = new ArrayList<>();
        for (CanonicalJson.Value item : seeds.items()) {
            CanonicalJson.Obj row = (CanonicalJson.Obj) item;
            CanonicalJson.Obj key = (CanonicalJson.Obj) at(row, "key");
            if (className.equals(((CanonicalJson.Str) at(key, "name")).value())) {
                CanonicalJson.Obj layout = (CanonicalJson.Obj) at(row, "layout");
                CanonicalJson.Arr fields = (CanonicalJson.Arr) at(layout, "fields");
                List<CanonicalJson.Value> updated = new ArrayList<>();
                for (CanonicalJson.Value fieldItem : fields.items()) {
                    CanonicalJson.Obj field = (CanonicalJson.Obj) fieldItem;
                    if (fieldName.equals(
                            ((CanonicalJson.Str) at(field, "name")).value())) {
                        field = withEntry(field, "descriptor",
                            CanonicalJson.str(descriptor));
                    }
                    updated.add(field);
                }
                row = withEntry(row, "layout", withEntry(layout, "fields",
                    CanonicalJson.arr(updated)));
            }
            rows.add(row);
        }
        return CanonicalJson.serializeBytes(
            withEntry(root, "classSeeds", CanonicalJson.arr(rows)));
    }

    /** The manifest with one seed's registration key renamed. */
    private static byte[] manifestWithSeedKey(byte[] manifest, String className,
            String newName) {
        CanonicalJson.Obj root = objOf(manifest);
        CanonicalJson.Arr seeds = (CanonicalJson.Arr) at(root, "classSeeds");
        List<CanonicalJson.Value> rows = new ArrayList<>();
        for (CanonicalJson.Value item : seeds.items()) {
            CanonicalJson.Obj row = (CanonicalJson.Obj) item;
            CanonicalJson.Obj key = (CanonicalJson.Obj) at(row, "key");
            if (className.equals(((CanonicalJson.Str) at(key, "name")).value())) {
                row = withEntry(row, "key",
                    withEntry(key, "name", CanonicalJson.str(newName)));
            }
            rows.add(row);
        }
        return CanonicalJson.serializeBytes(
            withEntry(root, "classSeeds", CanonicalJson.arr(rows)));
    }

    /** The manifest with the named class's registration removed. */
    private static byte[] manifestWithoutSeed(byte[] manifest, String className) {
        CanonicalJson.Obj root = objOf(manifest);
        CanonicalJson.Arr seeds = (CanonicalJson.Arr) at(root, "classSeeds");
        List<CanonicalJson.Value> rows = new ArrayList<>();
        for (CanonicalJson.Value item : seeds.items()) {
            CanonicalJson.Obj row = (CanonicalJson.Obj) item;
            CanonicalJson.Obj key = (CanonicalJson.Obj) at(row, "key");
            if (!className.equals(((CanonicalJson.Str) at(key, "name")).value())) {
                rows.add(row);
            }
        }
        return CanonicalJson.serializeBytes(
            withEntry(root, "classSeeds", CanonicalJson.arr(rows)));
    }

    /** The canonical document with one unknown framing member added. */
    private static byte[] withNewEntry(byte[] canonicalJson, String key,
            CanonicalJson.Value value) {
        CanonicalJson.Obj obj = objOf(canonicalJson);
        List<CanonicalJson.Entry> entries = new ArrayList<>(obj.entries());
        entries.add(CanonicalJson.e(key, value));
        return CanonicalJson.serializeBytes(CanonicalJson.obj(entries));
    }

    /** The canonical document terminated by exactly one LF (the file framing). */
    private static byte[] withLf(byte[] bytes) {
        byte[] result = new byte[bytes.length + 1];
        System.arraycopy(bytes, 0, result, 0, bytes.length);
        result[bytes.length] = '\n';
        return result;
    }

    /**
     * Asserts that the reassembly rejects a manifest/document pair as a
     * transport decode failure: the framing precedes admission, so a
     * rejected document set yields no project record and starts no
     * admission.
     */
    private static void expectReassemblyFailure(byte[] manifest, List<byte[]> documents,
            String message) {
        try {
            SemanticIrDumper.reassembleProject(manifest, documents);
            fail(message + " (the reassembly returned a project)");
        } catch (SemanticIrTextDecodeException expected) {
            passed++;
        } catch (RuntimeException other) {
            fail(message + " (expected SemanticIrTextDecodeException, got "
                + other.getClass().getSimpleName() + ": " + other.getMessage() + ")");
        }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== Project Lowering Entry Tests (ISSUE-0634) ===\n");
        RealProject project = null;
        RealProject importedProject = null;
        try {
            project = compileProject(APP_SOURCE);
            LoweredProject baseline = new LoweredProject(project,
                lower(project, invocation()));
            testOneProjectLoweringAndDeterminism(baseline);
            testSeedsIntrinsicsAndNamespaces(baseline);
            testUnitProjectCompanionOwnership();
            testNamespaceRegistrationFailureOrigins(baseline);
            testComposedChainOverUnifiedUnits(baseline);
            testCompleteProjectTransportAndDumpReassembly(baseline);
            testInProjectClassArm();
            importedProject = compileProject(IMPORTED_CLASS_APP_SOURCE,
                Map.of("src/util.deal", IMPORTED_CLASS_UTIL_SOURCE), Map.of());
            LoweredProject imported = new LoweredProject(importedProject,
                lower(importedProject, invocation()));
            testImportedInProjectClassResolution(imported);
            testInconsistentFactSeed(imported);
            testDeclarationClassConstruction();
            testBuiltinErrorConstruction();
            testExternCDeclarationClassConstruction();
            testProjectSeedModuleJoinAndAdmission();
            testProjectSeedNominalClassReference();
            testSeedImplementationReferenceAndPrivateFieldRead();
            testCorruptedUnitSeed(baseline);
            testMissingModuleSeed(baseline);
            testUnresolvedAliasSeed(baseline);
        } finally {
            deleteRecursively(importedProject == null ? null : importedProject.root());
            deleteRecursively(project == null ? null : project.root());
        }
        System.out.println("\nPassed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }
}
