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
import deal.semantic.SemanticOracle;
import deal.semantic.SemanticRequirementManifest;
import deal.semantic.SemanticRuntimeModel;
import deal.semantic.ir.AdaptSourceRef;
import deal.semantic.ir.BindingGeneration;
import deal.semantic.ir.LoweredFunction;
import deal.semantic.ir.OperationContractSnapshot;
import deal.semantic.ir.RawOp;
import deal.semantic.ir.RawBinding;
import deal.semantic.ir.RawOrigin;
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
import deal.semantic.ir.FunctionId;
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
import deal.semantic.ir.RawUnitMaterializer;
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
import java.util.function.UnaryOperator;

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


    /**
     * The same-spelled provider pair of distinct module identities (the
     * ISSUE-0809 acceptance): two modules declare a class named
     * {@code Point} with different declared fields; the consuming module
     * imports both and constructs, reads, writes and deletes through the
     * exact provider each spelling names.
     */
    private static final String SAME_SPELLED_ALPHA_SOURCE = """
        export class Point {
          x: int = 0;
          nick?: string;
        }
        """;

    private static final String SAME_SPELLED_BETA_SOURCE = """
        export class Point {
          y: string = "";
        }
        """;

    private static final String SAME_SPELLED_APP_SOURCE = """
        import * as alpha from "./alpha/util";
        import * as beta from "./beta/util";

        export function main(): null {
          let a: alpha.Point = { x: 1 };
          a.x = 5;
          a.nick = "n";
          let hadNick: boolean = has(a.nick);
          delete a.nick;
          if (a.x !== 5) { throw { code: "WRONG_X", message: "x" }; }
          if (!hadNick || has(a.nick)) {
            throw { code: "WRONG_PRESENCE", message: "nick" };
          }
          let b: beta.Point = { y: "s" };
          let xs: beta.Point[] = [{ y: "a" }];
          if (xs[0].y !== "a") { throw { code: "WRONG_ARRAY", message: "array" }; }
          let n: alpha.Point | null = { x: 2 };
          if (n === null) { throw { code: "WRONG_NULLABLE", message: "n" }; }
          return null;
        }
        """;

    /**
     * The same-directory sibling pair of one canonical class identity:
     * {@code src/m/ir.deal} and {@code src/m/codec.deal} both export
     * {@code Ir} with different defaults, so the recorded contextual
     * provider — never an identity-first match — must select the exact
     * declaring provider of every construction.
     */
    private static final String SIBLING_IR_SOURCE = """
        export class Ir { a: int = 7; }
        export function useIr(x: Ir): int { return x.a; }
        """;

    private static final String SIBLING_CODEC_SOURCE = """
        export class Ir { a: int = 93; }
        export class Codec { b: int = 9; }
        export function useCodec(y: Codec): int { return y.b; }
        """;

    private static final String SIBLING_APP_SOURCE = """
        import * as ir from "./m/ir";
        import * as codec from "./m/codec";

        class Holder {
          inner: ir.Ir = {};
          items: ir.Ir[] = [{}];
        }

        export function main(): null {
          let direct: ir.Ir = {};
          if (direct.a !== 7) { throw { code: "WRONG_DIRECT", message: "a" }; }
          if (ir.useIr({}) !== 7) { throw { code: "WRONG_CALL", message: "a" }; }
          let h: Holder = {};
          if (h.inner.a !== 7) { throw { code: "WRONG_NESTED", message: "a" }; }
          if (h.items[0].a !== 7) { throw { code: "WRONG_ARRAY", message: "a" }; }
          let xs: ir.Ir[] = [{}];
          if (xs[0].a !== 7) { throw { code: "WRONG_LITERAL_ARRAY", message: "a" }; }
          return null;
        }
        """;

    /**
     * The malformed-provider negative fixture: the entry writes a field
     * of an imported class whose implementation owner is removed from the
     * lowering closure (the checker-valid source stays).
     */
    private static final String IMPORTED_FIELD_WRITE_APP_SOURCE = """
        import * as u from "util";

        export function poke(p: u.Point): null {
          p.x = 1;
          return null;
        }

        export function main(): null {
          return null;
        }
        """;

    private static final ClassId SEGMENT = new ClassId("src", "Segment");

    /**
     * The binding/class common-admission fixture: a module-level class with
     * generated JSON defaults, an ordinary closure with two generation-pinned
     * captures, a host function value materialized at its producing crossing,
     * and parameter/import initializations.
     */
    private static final String BINDING_CLASS_UTIL_SOURCE = """
        // @jsonable
        export class Point {
          x: int = 4;
          label: string = "p";
          note?: string;
        }

        export function makeCounter(start: int, step: int): () => int {
          let n: int = start;
          function bump(): int {
            n = n + step;
            return n;
          }
          return bump;
        }
        """;

    /** The host declaration of the binding/class fixture: a function-valued return. */
    private static final String BINDING_CLASS_DECLARATION = """
        export function pick(): (x: int) => int;
        """;

    private static final String BINDING_CLASS_APP_SOURCE = """
        import * as u from "util";
        import * as cfg from "host/cfg";

        function twice(x: int): int {
          return x + x;
        }

        export function main(): null {
          let p: u.Point = {x: 5};
          let c: () => int = u.makeCounter(1, 2);
          let a: (x: int, y: int) => int = twice;
          let h: (x: int) => int = cfg.pick();
          let n: int = c();
          return null;
        }
        """;

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

    private static SemanticIrValidator.ComparisonFacts comparisonFacts(RealProject project) {
        return new SemanticIrValidator.ComparisonFacts(project.index().interfaceIndexDigest(),
            SemanticProfile.DEAL_V1_3_INT32, invocation().capabilityRegistryHash());
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
                comparisonFacts(project);
            check(SemanticIrValidator.validate(result.project(), facts).isEmpty(),
                "the project passes the project-form closed gate");
            for (LoweredModuleUnit unit : result.project().modules().values()) {
                StructuredBodyTable table = result.tableOf(unit.moduleId());
                check(table != null, "the module's produced table is carried");
                // The closed 14 rules and the composed correlations of the
                // unit's own facts; the standalone entrypoint additionally
                // rejects a unit that requires the project's provider context
                // (the required foreign links of a cross-module unit).
                check(SemanticIrValidator.validateUnitHead(unit, facts).isEmpty(),
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
                comparisonFacts(project);
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


    private static void testSameSpelledProviderResolution() throws Exception {
        System.out.println("-- same-spelled classes of distinct providers: the "
            + "construction, field, default and JSON links resolve the exact "
            + "declared provider --");
        RealProject project = compileProject(SAME_SPELLED_APP_SOURCE,
            Map.of("src/alpha/util.deal", SAME_SPELLED_ALPHA_SOURCE,
                "src/beta/util.deal", SAME_SPELLED_BETA_SOURCE), Map.of());
        try {
            SemanticLowerer.ProjectLoweringResult result = lower(project, invocation());
            check(!result.hasErrors() && result.project() != null,
                "the same-spelled provider project lowers through the one project "
                    + "entry: " + result.diagnostics());
            if (result.project() == null) {
                return;
            }
            ExecutableLoweredProject lowered = result.project();
            ModuleId alpha = new ModuleId("alpha.util");
            ModuleId beta = new ModuleId("beta.util");
            ClassId alphaPoint = new ClassId("src/alpha", "Point");
            ClassId betaPoint = new ClassId("src/beta", "Point");
            LoweredModuleUnit alphaUnit = lowered.modules().get(alpha);
            LoweredModuleUnit betaUnit = lowered.modules().get(beta);
            LoweredModuleUnit app = lowered.modules().get(APP);
            check(alphaUnit != null && betaUnit != null && app != null,
                "the closure carries both providers and the consumer");
            if (alphaUnit == null || betaUnit == null || app == null) {
                return;
            }
            check(alphaUnit.classLayouts().containsKey(alphaPoint)
                    && betaUnit.classLayouts().containsKey(betaPoint)
                    && !alphaPoint.equals(betaPoint),
                "the two providers keep distinct layouts under distinct class ids");
            ClassFactoryId alphaEntry = factoryEntryOf(alphaUnit, alphaPoint);
            ClassFactoryId betaEntry = factoryEntryOf(betaUnit, betaPoint);
            check(alphaEntry != null && betaEntry != null
                    && !alphaEntry.equals(betaEntry),
                "the two providers register distinct construction entries");

            Map<ClassId, KindPayload.ClassNewPayload> constructions =
                new LinkedHashMap<>();
            for (SemanticOp op : app.ops()) {
                if (op.kind() == SemanticOpKind.CLASS_NEW
                        && op.payload() instanceof KindPayload.ClassNewPayload payload) {
                    constructions.put(payload.classId(), payload);
                }
            }
            KindPayload.ClassNewPayload alphaLiteral = constructions.get(alphaPoint);
            KindPayload.ClassNewPayload betaLiteral = constructions.get(betaPoint);
            check(alphaLiteral != null && betaLiteral != null,
                "the consumer lowers one CLASS_NEW per distinct provider");
            if (alphaLiteral == null || betaLiteral == null) {
                return;
            }
            checkEq(DefaultOwner.SHARED_FACTORY, alphaLiteral.defaultOwner(),
                "the imported alpha literal carries SHARED_FACTORY");
            checkEq(alphaEntry, alphaLiteral.classFactoryRef(),
                "the alpha literal references the alpha provider's construction entry");
            checkEq(DefaultOwner.SHARED_FACTORY, betaLiteral.defaultOwner(),
                "the imported beta literal carries SHARED_FACTORY");
            checkEq(betaEntry, betaLiteral.classFactoryRef(),
                "the beta literal references the beta provider's construction entry");
            checkEq(alphaUnit.classLayouts().get(alphaPoint), alphaLiteral.layout(),
                "the alpha literal carries the alpha provider's layout");
            checkEq(betaUnit.classLayouts().get(betaPoint), betaLiteral.layout(),
                "the beta literal carries the beta provider's layout");

            // The field operations select the declaring field's descriptor
            // from the provider layout.
            List<SemanticOp> writes = new ArrayList<>();
            List<SemanticOp> deletes = new ArrayList<>();
            for (SemanticOp op : app.ops()) {
                if (op.kind() == SemanticOpKind.FIELD_WRITE) {
                    writes.add(op);
                }
                if (op.kind() == SemanticOpKind.FIELD_DELETE) {
                    deletes.add(op);
                }
            }
            checkEq(2, writes.size(), "the consumer lowers two FIELD_WRITEs");
            checkEq(1, deletes.size(), "the consumer lowers one FIELD_DELETE");
            for (SemanticOp write : writes) {
                KindPayload.FieldWritePayload payload =
                    (KindPayload.FieldWritePayload) write.payload();
                checkEq(alphaPoint, payload.classId(),
                    "every FIELD_WRITE names the alpha provider's class");
                checkEq("src/alpha", payload.classId().modulePath(),
                    "the field write's class id carries the provider's module path");
            }
            if (!deletes.isEmpty()
                    && deletes.get(0).payload()
                        instanceof KindPayload.FieldDeletePayload deletePayload) {
                checkEq("nick", deletePayload.field(),
                    "the FIELD_DELETE names the optional provider field");
            }

            // The lowered project admits on both project surfaces and its
            // own records reassemble from the dump.
            SemanticIrValidator.ComparisonFacts facts =
                new SemanticIrValidator.ComparisonFacts(
                    project.index().interfaceIndexDigest(),
                    SemanticProfile.DEAL_V1_3_INT32,
                    invocation().capabilityRegistryHash());
            check(SemanticIrValidator.validate(lowered, facts).isEmpty(),
                "the same-spelled provider project passes the typed project surface");
            String text = SemanticIrValidator.toProjectText(lowered);
            check(SemanticIrValidator.validateText(text, facts).isEmpty(),
                "the same-spelled provider project passes the text project surface");
            SemanticIrDumper.ProjectDump dump = SemanticIrDumper.dumpProject(lowered);
            checkEq(ContractSnapshotCanonicalizer.parseProject(objOf(
                    text.getBytes(StandardCharsets.UTF_8))),
                SemanticIrDumper.reassembleProject(dump.manifest(),
                    documentsOf(dump)),
                "the same-spelled provider project reassembles exactly");

            // The Oracle executes the probe: the per-construction defaults,
            // the provider field write and delete, the array and nullable
            // constructions all hold, so the terminal is the probe's success.
            SemanticRuntimeModel.ConsumerRun run =
                SemanticOracle.executeProjectInits(lowered, result.tables(),
                    result.registries(), null);
            check(run.terminal() instanceof SemanticRuntimeModel.Terminal.Success,
                "the oracle completes the same-spelled provider probe: "
                    + run.comparisonReport());
        } finally {
            deleteRecursively(project.root());
        }
    }

    private static void testSameNameSiblingProviderResolution() throws Exception {
        System.out.println("-- same-named sibling providers of one canonical "
            + "identity: the recorded contextual provider selects the exact "
            + "declaring declaration --");
        RealProject project = compileProject(SIBLING_APP_SOURCE,
            Map.of("src/m/ir.deal", SIBLING_IR_SOURCE,
                "src/m/codec.deal", SIBLING_CODEC_SOURCE), Map.of());
        try {
            SemanticLowerer.ProjectLoweringResult result = lower(project, invocation());
            check(!result.hasErrors() && result.project() != null,
                "the same-named sibling project lowers through the one project "
                    + "entry: " + result.diagnostics());
            if (result.project() == null) {
                return;
            }
            ExecutableLoweredProject lowered = result.project();
            ModuleId ir = new ModuleId("m.ir");
            ModuleId codec = new ModuleId("m.codec");
            LoweredModuleUnit irUnit = lowered.modules().get(ir);
            LoweredModuleUnit codecUnit = lowered.modules().get(codec);
            LoweredModuleUnit app = lowered.modules().get(APP);
            check(irUnit != null && codecUnit != null && app != null,
                "the closure carries both siblings and the consumer");
            if (irUnit == null || codecUnit == null || app == null) {
                return;
            }
            ClassId irId = new ClassId("src/m", "Ir");
            ClassFactoryId irEntry = factoryEntryOf(irUnit, irId);
            ClassFactoryId codecEntry = factoryEntryOf(codecUnit, irId);
            check(irEntry != null && codecEntry != null
                    && !irEntry.equals(codecEntry),
                "the sibling declarations carry distinct construction entries");
            check(lowered.classSeeds().registrationFor(irId) == null,
                "the implementation sibling pair acquires no declaration seed");

            Map<ClassFactoryId, Integer> constructionCounts = new LinkedHashMap<>();
            int irConstructions = 0;
            for (SemanticOp op : app.ops()) {
                if (op.kind() == SemanticOpKind.CLASS_NEW
                        && op.payload() instanceof KindPayload.ClassNewPayload payload
                        && payload.classId().equals(irId)) {
                    irConstructions++;
                    checkEq(DefaultOwner.SHARED_FACTORY, payload.defaultOwner(),
                        "every sibling construction transfers to the provider factory");
                    if (payload.classFactoryRef() != null) {
                        constructionCounts.merge(payload.classFactoryRef(), 1,
                            Integer::sum);
                    }
                }
            }
            checkEq(Set.of(irEntry), constructionCounts.keySet(),
                "every app construction references the recorded ir sibling's "
                    + "construction entry (never the codec sibling's)");
            checkEq(5, irConstructions,
                "all five recorded contexts construct the ir sibling "
                    + "(literal, call argument, nested field default, nested array "
                    + "default, literal array)");

            SemanticIrValidator.ComparisonFacts facts =
                new SemanticIrValidator.ComparisonFacts(
                    project.index().interfaceIndexDigest(),
                    SemanticProfile.DEAL_V1_3_INT32,
                    invocation().capabilityRegistryHash());
            check(SemanticIrValidator.validate(lowered, facts).isEmpty(),
                "the same-named sibling project passes the typed project surface");
            String text = SemanticIrValidator.toProjectText(lowered);
            check(SemanticIrValidator.validateText(text, facts).isEmpty(),
                "the same-named sibling project passes the text project surface");
            SemanticIrDumper.ProjectDump dump = SemanticIrDumper.dumpProject(lowered);
            checkEq(ContractSnapshotCanonicalizer.parseProject(objOf(
                    text.getBytes(StandardCharsets.UTF_8))),
                SemanticIrDumper.reassembleProject(dump.manifest(),
                    documentsOf(dump)),
                "the same-named sibling project reassembles exactly");

            SemanticRuntimeModel.ConsumerRun run =
                SemanticOracle.executeProjectInits(lowered, result.tables(),
                    result.registries(), null);
            check(run.terminal() instanceof SemanticRuntimeModel.Terminal.Success,
                "the oracle completes the sibling probe (the demanded default 7, "
                    + "never 93): " + run.comparisonReport());
        } finally {
            deleteRecursively(project.root());
        }
    }

    private static void testImportedFieldWriteProviderDeferral() throws Exception {
        System.out.println("-- the malformed-provider negative: a field write "
            + "whose imported owner carries no facts fails closed with a named "
            + "E6005 and no partial project --");
        RealProject project = compileProject(IMPORTED_FIELD_WRITE_APP_SOURCE,
            Map.of("src/util.deal", IMPORTED_CLASS_UTIL_SOURCE), Map.of());
        try {
            // The same real project with the owner module removed from the
            // closure and the index: the checked type remains, but no
            // lowered provider layout covers the imported class.
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
                "the stripped provider produces no project: "
                    + result.diagnostics());
            check(result.tables().isEmpty() && result.registries().isEmpty()
                    && result.seeds() == null && result.namespaces() == null,
                "the stripped provider produces no tables, no registries, no "
                    + "seeds, and no registrations");
            if (!result.diagnostics().isEmpty()) {
                CompilerDiagnostic diagnostic = result.diagnostics().get(0);
                check("E6005".equals(diagnostic.code())
                        && diagnostic.message().contains("CONSTRUCT_UNLOWERED"),
                    "the first E6005 is the named CONSTRUCT_UNLOWERED class-fact "
                        + "failure; got " + diagnostic.message());
                check(diagnostic.range() != null
                        && diagnostic.range().file() != null
                        && diagnostic.range().file().endsWith("app.deal")
                        && diagnostic.range().startLine() == 4,
                    "the failure keeps the field write's own source origin; got "
                        + diagnostic.range());
            }
        } finally {
            deleteRecursively(project.root());
        }
    }

    /**
     * The construction entry the unit's factory registry binds to the
     * declared class of one class id, or null.
     */
    private static ClassFactoryId factoryEntryOf(LoweredModuleUnit unit,
                                                 ClassId classId) {
        for (Map.Entry<ClassFactoryId, OpId> binding
                : unit.classFactories().factories().entrySet()) {
            SemanticOp factory = opOf(unit, binding.getValue());
            if (factory != null
                    && factory.payload() instanceof KindPayload.ClassFactoryPayload payload
                    && payload.classId().equals(classId)) {
                return binding.getKey();
            }
        }
        return null;
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

            // ISSUE-0821: the host declaration construction requires the
            // compilation's class registration seeds — the standalone
            // surfaces return E6005 at the referring CLASS_NEW instead of
            // inventing the provider context, and the complete project
            // supplies exactly that context.
            SemanticIrValidator.ComparisonFacts standaloneFacts =
                comparisonFacts(project);
            Optional<CompilerDiagnostic> standalone =
                SemanticIrValidator.validate(app, standaloneFacts);
            assertMutationRule("the standalone host construction", standalone,
                ClassConstructionValidator.CONSTRUCTION_COHERENCE,
                "no provider context", "defaultOwner HOST_DEFAULTS");
            check(standalone.isPresent() && java.util.Objects.equals(
                    RawOrigin.of(classNew.origin()).diagnosticRange(),
                    standalone.get().range()),
                "the standalone rejection carries the referring CLASS_NEW's own "
                    + "origin");
            check(SemanticIrValidator.validate(result.project(), standaloneFacts)
                    .isEmpty(),
                "the complete project admits the host construction");

            // A supplied seed view that differs from the admitted registration
            // rejects through E6005 before any success (the class owner's
            // fail-closed seed fact), never by fabricating a registration.
            Optional<CompilerDiagnostic> seedView =
                SemanticLowerer.validateProjectUnit(app, app.bodyTable(),
                    standaloneFacts, app.classBindingFacts(), result.registryOf(APP),
                    app.jsonDefaults(), project.index().modules().get(APP), Map.of(),
                    Map.of());
            check(seedView.isPresent()
                    && seedView.get().message().contains(
                        ClassConstructionValidator.CONSTRUCTION_COHERENCE),
                "a differing supplied seed view rejects through E6005: "
                    + seedView.map(CompilerDiagnostic::message).orElse("admitted"));
            testProjectSeedModuleJoinAndAdmission(new LoweredProject(project, result));
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

            // ISSUE-0821: the builtin Error owner is compiler-owned, so the
            // standalone surface admits the unit without any provider context
            // (the boundary of the required-link rejection).
            check(SemanticIrValidator.validate(app,
                    new SemanticIrValidator.ComparisonFacts(
                        project.index().interfaceIndexDigest(),
                        SemanticProfile.DEAL_V1_3_INT32,
                        invocation().capabilityRegistryHash())).isEmpty(),
                "the builtin Error construction needs no provider context and "
                    + "admits standalone");
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

            // ISSUE-0821: the C-struct construction requires the extern-C
            // plan seed — the standalone surfaces reject it at the referring
            // CLASS_NEW; the complete project supplies the seed.
            SemanticIrValidator.ComparisonFacts standaloneFacts =
                comparisonFacts(project);
            assertMutationRule("the standalone C-struct construction",
                SemanticIrValidator.validate(app, standaloneFacts),
                ClassConstructionValidator.CONSTRUCTION_COHERENCE,
                "no provider context", "defaultOwner FFI_PLAN");
            check(SemanticIrValidator.validate(result.project(), standaloneFacts)
                    .isEmpty(),
                "the complete project admits the C-struct construction");
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
                missing, comparisonFacts(project));
            check(failure.isPresent(),
                "the project with the missing callee module fails the project-form "
                    + "gate");
            if (failure.isEmpty()) {
                return;
            }
            check("E6005".equals(failure.get().code())
                    && failure.get().message().contains(
                        SemanticIrValidator.R_IDENTITY_LINK)
                    && failure.get().message().contains("implementation provider")
                    && failure.get().message().contains("carries no unit for it"),
                "the missing module returns the first E6005 R-IDENTITY-LINK naming "
                    + "the absent required implementation provider; got "
                    + failure.get().message());
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

    private static RawUnit assertUnitTransport(LoweredModuleUnit unit, String text) {
        RawUnit reparsed = ContractSnapshotCanonicalizer.parseUnit(
            (CanonicalJson.Obj) CanonicalJson.parse(text));
        assertUnitFacts(unit, reparsed);
        checkEq(text, CanonicalJson.serializeText(ContractSnapshotCanonicalizer.toJson(reparsed)),
            unit.moduleId() + ": canonical text is byte-stable");
        return reparsed;
    }

    private static void assertUnitFacts(LoweredModuleUnit unit, RawUnit reparsed) {
        String what = unit.moduleId() + ": ";
        checkEq(RawUnit.fromTyped(unit), reparsed, what + "every unit fact round-trips");
        checkEq(unit.bodyTable(), reparsed.bodyTable(), what + "both membership directions survive");
        checkEq(unit.classFactories(), reparsed.classFactories(), what + "factory registry survives");
        checkEq(unit.jsonDefaults(), reparsed.jsonDefaults(), what + "JSON-default rows survive");
        checkEq(unit.classBindingFacts(), reparsed.classBindingFacts(),
            what + "parameter/import facts survive");
        checkEq(unit, RawUnitMaterializer.materialize(reparsed),
            what + "transport materializes to the exact typed unit");
    }

    private static void assertStandaloneAdmission(LoweredModuleUnit unit, String text,
            RawUnit reparsed, SemanticIrValidator.ComparisonFacts facts) {
        boolean needsProvider = requiresProviderContext(reparsed);
        checkEq(needsProvider, SemanticIrValidator.validate(unit, facts).isPresent(),
            unit.moduleId() + ": standalone typed admission requires exactly the provider context");
        checkEq(needsProvider, SemanticIrValidator.validateText(text, facts).isPresent(),
            unit.moduleId() + ": standalone text admission requires exactly the provider context");
    }

    private static ContractSnapshotCanonicalizer.ParsedProject assertProjectTransport(
            ExecutableLoweredProject lowered, SemanticIrValidator.ComparisonFacts facts) {
        String text = SemanticIrValidator.toProjectText(lowered);
        ContractSnapshotCanonicalizer.ParsedProject parsed =
            ContractSnapshotCanonicalizer.parseProject((CanonicalJson.Obj) CanonicalJson.parse(text));
        checkEq(lowered.interfaceIndex(), parsed.interfaceIndex(), "project transport keeps the index");
        checkEq(lowered.classSeeds(), parsed.classSeeds(), "project transport keeps the seeds");
        check(SemanticIrValidator.validate(lowered, facts).isEmpty(), "typed project admits");
        check(SemanticIrValidator.validateText(text, facts).isEmpty(), "text project admits");
        return parsed;
    }

    private static void testCompleteUnitTransport(RealProject project,
            ExecutableLoweredProject lowered) {
        SemanticIrValidator.ComparisonFacts facts = comparisonFacts(project);
        boolean sawCaptures = false;
        boolean sawImportAliases = false;
        boolean sawClassLayout = false;
        for (LoweredModuleUnit unit : lowered.modules().values()) {
            String text = SemanticIrValidator.toUnitText(unit);
            checkEq(SemanticIrDumper.dumpModuleText(unit), text,
                unit.moduleId() + ": dump uses canonical unit mapping");
            RawUnit reparsed = assertUnitTransport(unit, text);
            assertStandaloneAdmission(unit, text, reparsed, facts);
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
     * ISSUE-0821: the standalone surfaces never approve a required foreign
     * link, the complete project admission resolves exactly those links
     * from the supplied closure — the shared-factory join names the
     * provider's owning module and operation tag, never the class-id
     * namespace — the composed production admission consumes exactly the
     * unit's owned companion records, and the project framing admission
     * validates the module keys, index coverage and classification and the
     * dependency order.
     */
    private static void testStandaloneProviderContextAndExactViews(
            LoweredProject imported, LoweredProject baseline) throws Exception {
        System.out.println("-- standalone vs complete admission: required foreign links, "
            + "the provider join and the exact companion views (ISSUE-0821) --");
        RealProject project = imported.project();
        ExecutableLoweredProject lowered = imported.result().project();
        check(lowered != null,
            "the imported-class fixture lowers: " + imported.result().diagnostics());
        if (lowered == null) {
            return;
        }
        SemanticIrValidator.ComparisonFacts facts = comparisonFacts(project);
        LoweredModuleUnit util = lowered.modules().get(UTIL);
        LoweredModuleUnit app = lowered.modules().get(APP);
        check(util != null && app != null,
            "the closure carries the class provider and the constructing entry module");
        if (util == null || app == null) {
            return;
        }

        // (1) The provider module's own facts are locally complete: it
        // carries no foreign link, so the standalone surfaces admit it.
        check(SemanticIrValidator.validate(util, facts).isEmpty(),
            "the class provider unit carries no foreign link and admits standalone");
        check(SemanticIrValidator.validateText(
                SemanticIrValidator.toUnitText(util), facts).isEmpty(),
            "the class provider unit text carries no foreign link");

        // (2) The consumer's cross-module call refers to the provider
        // module's recorded EXTERNAL_ENTRY: standalone admission returns
        // E6005 at the referring CALL (its own retained origin), never a
        // fabricated provider; the imported construction's owner facts are
        // the same missing provider context.
        boolean sharedConstruction = false;
        SemanticOp foreignCall = null;
        for (SemanticOp op : app.ops()) {
            if (op.kind() == SemanticOpKind.CLASS_NEW
                    && op.payload() instanceof KindPayload.ClassNewPayload payload
                    && payload.defaultOwner() == DefaultOwner.SHARED_FACTORY) {
                sharedConstruction = true;
            }
            if (op.kind() == SemanticOpKind.CALL
                    && op.payload() instanceof KindPayload.CallPayload call
                    && call.externalEntryRef() != null
                    && call.externalEntryRef().module().equals(UTIL)) {
                foreignCall = op;
            }
        }
        check(sharedConstruction,
            "the consumer carries the imported CLASS_NEW(SHARED_FACTORY)");
        check(foreignCall != null,
            "the consumer carries the cross-module CALL and its externalEntryRef");
        if (foreignCall != null) {
            Optional<CompilerDiagnostic> typed = SemanticIrValidator.validate(app, facts);
            Optional<CompilerDiagnostic> text = SemanticIrValidator.validateText(
                SemanticIrValidator.toUnitText(app), facts);
            assertMutationRule("the standalone foreign provider link", typed,
                SemanticIrValidator.R_EXTERNAL_ENTRY, "externalEntryRef",
                "no implementation closure");
            check(text.isPresent() && typed.isPresent()
                    && text.get().message().equals(typed.get().message())
                    && text.get().range().equals(typed.get().range()),
                "the standalone unit surfaces report the identical first failure "
                    + "and range for the required foreign link");
            check(typed.isPresent() && java.util.Objects.equals(
                    RawOrigin.of(foreignCall.origin()).diagnosticRange(),
                    typed.get().range()),
                "the standalone rejection carries the referring CALL's own origin");
        }

        // (3) The complete project supplies the missing context: exactly those
        // links resolve and both project surfaces admit.
        check(SemanticIrValidator.validate(lowered, facts).isEmpty(),
            "the complete project admits the imported construction (typed)");
        check(SemanticIrValidator.validateText(
                SemanticIrValidator.toProjectText(lowered), facts).isEmpty(),
            "the complete project admits the imported construction (text)");

        // (4) The exact provider join: the registry's owning module and the
        // factory operation's module tag — the class-id namespace is @src,
        // never the owning module 'util'.
        ExternalModuleInterface utilInterface = project.index().modules().get(UTIL);
        ClassInterface pointEntry = null;
        for (ClassInterface candidate : utilInterface.classes()) {
            if (candidate.classId().equals(POINT)) {
                pointEntry = candidate;
            }
        }
        check(pointEntry != null, "the provider's interface entry carries the class");
        if (pointEntry != null) {
            OpId joined = ClassFactoryRegistry.ownerFactoryOp(
                imported.result().registries(), pointEntry.constructionEntry());
            check(joined != null && joined.module().equals(UTIL),
                "the shared-factory join names the owning module through the "
                    + "registry and the operation module tag, never the class-id "
                    + "namespace " + POINT.modulePath());
            SemanticOp factoryOp = opOf(util, joined);
            check(factoryOp != null && factoryOp.kind() == SemanticOpKind.CLASS_FACTORY
                    && factoryOp.result() instanceof ValueId,
                "the join names the owner's actual CLASS_FACTORY result");
        }

        // (5) The baseline consumer refers to another module's external
        // function: the standalone binding surface rejects it, the complete
        // project resolves the callee's recorded EXTERNAL_ENTRY.
        ExecutableLoweredProject baselineLowered = baseline.result().project();
        check(baselineLowered != null, "the baseline project lowers: "
            + baseline.result().diagnostics());
        if (baselineLowered == null) {
            return;
        }
        SemanticIrValidator.ComparisonFacts baselineFacts =
            new SemanticIrValidator.ComparisonFacts(
                baseline.project().index().interfaceIndexDigest(),
                SemanticProfile.DEAL_V1_3_INT32, invocation().capabilityRegistryHash());
        LoweredModuleUnit baselineApp = baselineLowered.modules().get(APP);
        check(baselineApp != null, "the baseline entry module is in the closure");
        if (baselineApp == null) {
            return;
        }
        assertMutationRule("the standalone cross-module binding",
            SemanticIrValidator.validate(baselineApp, baselineFacts),
            SemanticIrValidator.R_EXTERNAL_ENTRY, "outside the unit",
            "no implementation closure");
        check(SemanticIrValidator.validate(baselineLowered, baselineFacts).isEmpty(),
            "the complete project resolves the cross-module binding");

        // (6) The composed production admission consumes exactly the unit's
        // owned companion records: a differing supplied view rejects through
        // E6005 before any success, and content-equal detached copies are
        // accepted (the owned records are consumed, never the copies).
        ExternalModuleInterface appInterface =
            baseline.project().index().modules().get(APP);
        check(appInterface != null, "the entry module carries its index entry");
        Map<ClassId, SharedFactoryFacts> noSharedFactories = Map.of();
        Map<ClassId, ClassRegistrationSeeds.ClassRegistration> seeds =
            baselineLowered.classSeeds().registrations();
        check(SemanticLowerer.validateProjectUnit(baselineApp,
                baselineApp.bodyTable(), baselineFacts,
                baselineApp.classBindingFacts(), baselineApp.classFactories(),
                baselineApp.jsonDefaults(), appInterface, noSharedFactories, seeds)
                .isEmpty(),
            "the exact owned companion views admit through the composed production "
                + "admission");
        check(SemanticLowerer.validateProjectUnit(baselineApp,
                new StructuredBodyTable(baselineApp.bodyTable().blockOps(),
                    baselineApp.bodyTable().opBlocks()),
                baselineFacts,
                new ClassBindingFacts(baselineApp.classBindingFacts().parameters(),
                    baselineApp.classBindingFacts().importAliases()),
                new ClassFactoryRegistry(baselineApp.classFactories().factories()),
                new JsonDefaultChildTable(baselineApp.jsonDefaults().defaultChildren()),
                appInterface, noSharedFactories, seeds).isEmpty(),
            "content-equal detached companion views admit (the unit's owned records "
                + "are consumed)");

        check(!baselineApp.bodyTable().blockOps().isEmpty(),
            "the view fixture carries a non-empty body table");
        Set<BindingId> parameters = new LinkedHashSet<>(baselineApp.classBindingFacts().parameters());
        parameters.add(new BindingId(9_000_001));
        Map<ClassFactoryId, OpId> factories = new LinkedHashMap<>(baselineApp.classFactories().factories());
        factories.put(new ClassFactoryId(9_000_001), new OpId(APP, 9_000_001));
        Map<OpId, List<OpId>> defaults = new LinkedHashMap<>(baselineApp.jsonDefaults().defaultChildren());
        defaults.put(new OpId(APP, 9_000_002), List.of());
        record ViewCase(String name, StructuredBodyTable table, ClassBindingFacts bindings,
                        ClassFactoryRegistry factories, JsonDefaultChildTable defaults) { }
        for (ViewCase row : List.of(
                new ViewCase("body table", new StructuredBodyTable(Map.of(), Map.of()),
                    baselineApp.classBindingFacts(), baselineApp.classFactories(), baselineApp.jsonDefaults()),
                new ViewCase("binding facts", baselineApp.bodyTable(),
                    new ClassBindingFacts(parameters, baselineApp.classBindingFacts().importAliases()),
                    baselineApp.classFactories(), baselineApp.jsonDefaults()),
                new ViewCase("class-factory registry", baselineApp.bodyTable(),
                    baselineApp.classBindingFacts(), new ClassFactoryRegistry(factories), baselineApp.jsonDefaults()),
                new ViewCase("JSON-default table", baselineApp.bodyTable(),
                    baselineApp.classBindingFacts(), baselineApp.classFactories(), new JsonDefaultChildTable(defaults)))) {
            Optional<CompilerDiagnostic> mismatch = SemanticLowerer.validateProjectUnit(baselineApp,
                row.table(), baselineFacts, row.bindings(), row.factories(), row.defaults(),
                appInterface, noSharedFactories, seeds);
            expectProjectAdmissionFailure(mismatch, SemanticLowerer.PROJECT_VIEW_MISMATCH, row.name(),
                "a differing supplied " + row.name() + " view rejects through E6005");
        }

        // (7) The project framing admission: a module-map key that disagrees
        // with the contained unit's identity, a closure module without its
        // index entry, a misclassified provider, an inverted dependency order
        // and a foreign carried profile all reject; the coherent
        // dependency-ordered project (with its repeated references to the
        // one provider) admits.
        Map<ModuleId, LoweredModuleUnit> renamedKeys = new LinkedHashMap<>();
        renamedKeys.put(new ModuleId("util.alias"), lowered.modules().get(UTIL));
        renamedKeys.put(APP, lowered.modules().get(APP));
        Map<ModuleId, LoweredModuleUnit> reordered = new LinkedHashMap<>();
        reordered.put(APP, lowered.modules().get(APP));
        reordered.put(UTIL, lowered.modules().get(UTIL));
        record FramingCase(String detail, ProjectInterfaceIndex index,
                           Map<ModuleId, LoweredModuleUnit> modules) { }
        for (FramingCase row : List.of(
                new FramingCase("module-map key", lowered.interfaceIndex(), renamedKeys),
                new FramingCase("no module silently disappears",
                    withoutIndexModule(lowered.interfaceIndex(), UTIL), lowered.modules()),
                new FramingCase("classified",
                    withImportKind(lowered.interfaceIndex(), APP, "u", ExternalModuleKind.HOST), lowered.modules()),
                new FramingCase("a closure unit is an implementation provider",
                    withIndexKind(lowered.interfaceIndex(), UTIL, ExternalModuleKind.HOST), lowered.modules()),
                new FramingCase("dependency order", lowered.interfaceIndex(), reordered))) {
            expectProjectAdmissionFailure(SemanticIrValidator.validate(
                    new ExecutableLoweredProject(lowered.semanticProfile(), row.index(),
                        row.modules(), lowered.entryModule(), lowered.classSeeds()), facts),
                SemanticIrValidator.R_IDENTITY_LINK, row.detail(),
                "the typed project rejects invalid framing: " + row.detail());
        }

        CanonicalJson.Obj projectObj = (CanonicalJson.Obj) CanonicalJson.parse(
            SemanticIrValidator.toProjectText(lowered));
        String foreignProfile = CanonicalJson.serializeText(withEntry(projectObj,
            "semanticProfile", CanonicalJson.str("DEAL_V1_2_INT32")));
        expectProjectAdmissionFailure(SemanticIrValidator.validateText(foreignProfile, facts),
            SemanticIrValidator.R_PROFILE, "project semanticProfile",
            "the project text rejects a carried profile that is not the supplied one");
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
        SemanticIrValidator.ComparisonFacts facts = comparisonFacts(baseline.project());

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
        check(text.contains("\"semanticProfile\":\"DEAL_V1_3_INT32\""),
            "the project text carries the owner profile explicitly");
        check(!text.contains("DEAL_V1_2_INT32") && !text.contains("LEGACY_SAFE_INT"),
            "no removed profile value appears in the project transport");
        for (LoweredModuleUnit carried : lowered.modules().values()) {
            checkEq(SemanticProfile.DEAL_V1_3_INT32, carried.semanticProfile(),
                "unit " + carried.moduleId() + " carries the owner profile");
            check(SemanticIrValidator.toUnitText(carried)
                    .contains("\"semanticProfile\":\"DEAL_V1_3_INT32\""),
                "unit " + carried.moduleId() + " transports the owner profile");
        }
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
            assertUnitFacts(units.get(i), parsed.modules().get(i));
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
        SemanticLowerer.ProjectLoweringResult rebuilt = lower(baseline.project(), invocation());
        check(rebuilt.project() != null, "the dump determinism fixture lowers independently");
        if (rebuilt.project() == null) {
            return;
        }
        SemanticIrDumper.ProjectDump repeated = SemanticIrDumper.dumpProject(rebuilt.project());
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
        check(!manifestText.contains("DEAL_V1_2_INT32")
                && !manifestText.contains("LEGACY_SAFE_INT"),
            "no removed profile value appears in the dump manifest");
        for (SemanticIrDumper.ModuleDump module : dump.modules()) {
            check(!module.text().contains("DEAL_V1_2_INT32")
                    && !module.text().contains("LEGACY_SAFE_INT"),
                "no removed profile value appears in the dump of module "
                    + module.moduleId());
            check(module.text().contains("\"semanticProfile\":\"DEAL_V1_3_INT32\""),
                "the dump of module " + module.moduleId()
                    + " carries the owner profile explicitly");
        }
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
        // The reassembled document set admits as one project: the manifest and
        // the module records are not independent authorities, and only their
        // agreement exposes a project.
        Map<ModuleId, LoweredModuleUnit> reassembledModules = new LinkedHashMap<>();
        for (RawUnit raw : reassembled.modules()) {
            reassembledModules.put(new ModuleId(raw.modulePath()),
                RawUnitMaterializer.materialize(raw));
        }
        Optional<CompilerDiagnostic> reassembledAdmission = SemanticIrValidator.validate(
            new ExecutableLoweredProject(SemanticProfile.DEAL_V1_3_INT32,
                reassembled.interfaceIndex(), reassembledModules,
                new ModuleId(reassembled.entryModule()), reassembled.classSeeds()), facts);
        check(reassembledAdmission.isEmpty(),
            "the reassembled dump documents admit as one project: "
                + reassembledAdmission.map(CompilerDiagnostic::message).orElse(""));
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
            assertUnitFacts(units.get(i), reassembled.modules().get(i));
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
    private static void testProjectSeedModuleJoinAndAdmission(LoweredProject fixture) {
        System.out.println("-- the project seed/module join: the checked reassembly "
            + "and the typed/text admission of the carried class facts --");
        RealProject project = fixture.project();
        {
            SemanticLowerer.ProjectLoweringResult result = fixture.result();
            check(!result.hasErrors() && result.project() != null,
                "the host-class transport project lowers: " + result.diagnostics());
            if (result.project() == null) {
                return;
            }
            ExecutableLoweredProject lowered = result.project();
            SemanticIrValidator.ComparisonFacts facts =
                comparisonFacts(project);

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
            // validation owner's rule, never silently admitted. The composed
            // per-module class owner runs before the project-level seed joins,
            // so the first failure is its resolved-layout disagreement with the
            // referring CLASS_NEW; the seed joins still reject the same records
            // at checked reassembly (assertion (1)).
            expectProjectAdmissionFailure(SemanticIrValidator.validateText(
                    asText(manifestWithSeedOwnerAndFieldOwners(
                        text.getBytes(StandardCharsets.UTF_8), "Endpoint", "FFI_PLAN")),
                    facts),
                ClassConstructionValidator.CONSTRUCTION_COHERENCE,
                "carries a layout that differs from the resolved layout",
                "the text project surface rejects the seed owner that contradicts "
                    + "the module's referring CLASS_NEW");
            expectProjectAdmissionFailure(SemanticIrValidator.validateText(
                    asText(manifestWithSeedFieldDescriptor(
                        text.getBytes(StandardCharsets.UTF_8), "Endpoint", "path", "int")),
                    facts),
                ClassConstructionValidator.CONSTRUCTION_COHERENCE,
                "carries a layout that differs from the resolved layout",
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
                ClassConstructionValidator.CONSTRUCTION_COHERENCE,
                "carries a layout that differs from the resolved layout",
                "the typed project surface rejects the seed owner that contradicts "
                    + "the module's referring CLASS_NEW");

            // (4) The carried interface index is consumed on both surfaces:
            // a mutation changes its digest, so the carried digest no longer
            // equals the invocation's supplied digest.
            ProjectInterfaceIndex renamedIndex = withIndexExportType(
                lowered.interfaceIndex(),
                lowered.interfaceIndex().modules().keySet().iterator().next(),
                "(i32) => i32");
            check(!renamedIndex.interfaceIndexDigest()
                    .equals(lowered.interfaceIndex().interfaceIndexDigest()),
                "the mutated index carries another digest");
            expectProjectAdmissionFailure(SemanticIrValidator.validateText(
                    asText(manifestWithIndexExportType(
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
                comparisonFacts(project);
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
                    ClassConstructionValidator.FIELD_OPERATION_SHAPE, "Hidden.secret",
                    "text admission rejects private seed/read disagreement");
                expectProjectAdmissionFailure(SemanticIrValidator.validate(
                    new ExecutableLoweredProject(project.semanticProfile(), project.interfaceIndex(),
                        project.modules(), project.entryModule(), withSeedFieldDescriptor(
                            project.classSeeds(), HOST_HIDDEN, "secret", RuntimeDescriptor.Int.INSTANCE)), facts),
                    ClassConstructionValidator.FIELD_OPERATION_SHAPE, "Hidden.secret",
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

    /**
     * The interface index with one module entry's export list rewritten:
     * only a declared-type text changes, so the digest moves while every
     * module identity, kind and resolved import of the framing stays
     * structurally coherent (the carried-digest comparison is then the
     * first failure).
     */
    private static ProjectInterfaceIndex withIndexExportType(
            ProjectInterfaceIndex index, ModuleId moduleId, String declaredType) {
        return mutateIndexModule(index, moduleId, original -> {
            List<ExportInterface> exports = new ArrayList<>(original.exports());
            if (!exports.isEmpty()) {
                exports.set(0, new ExportInterface(exports.get(0).name(), declaredType));
            }
            return new ExternalModuleInterface(original.moduleId(), original.kind(),
                original.imports(), exports, original.classes(), original.initialization());
        });
    }

    private static ProjectInterfaceIndex mutateIndexModule(ProjectInterfaceIndex index,
            ModuleId moduleId, UnaryOperator<ExternalModuleInterface> mutation) {
        Map<ModuleId, ExternalModuleInterface> modules = new LinkedHashMap<>(index.modules());
        modules.put(moduleId, mutation.apply(modules.get(moduleId)));
        return new ProjectInterfaceIndex(ProjectInterfaceIndex.FORMAT_VERSION, modules);
    }

    /** The interface index without one module's entry. */
    private static ProjectInterfaceIndex withoutIndexModule(
            ProjectInterfaceIndex index, ModuleId moduleId) {
        Map<ModuleId, ExternalModuleInterface> modules = new LinkedHashMap<>(index.modules());
        modules.remove(moduleId);
        return new ProjectInterfaceIndex(ProjectInterfaceIndex.FORMAT_VERSION, modules);
    }

    /** The interface index with one module's named import reclassified. */
    private static ProjectInterfaceIndex withImportKind(ProjectInterfaceIndex index,
            ModuleId moduleId, String alias, ExternalModuleKind kind) {
        return mutateIndexModule(index, moduleId, original -> {
            List<ResolvedImport> imports = original.imports().stream()
                .map(importFact -> importFact.alias().equals(alias)
                    ? new ResolvedImport(importFact.alias(), importFact.modulePath(),
                        importFact.resolvedModuleId(), kind) : importFact).toList();
            return new ExternalModuleInterface(original.moduleId(), original.kind(),
                imports, original.exports(), original.classes(), original.initialization());
        });
    }

    /** The interface index with one module entry's kind reclassified. */
    private static ProjectInterfaceIndex withIndexKind(ProjectInterfaceIndex index,
            ModuleId moduleId, ExternalModuleKind kind) {
        return mutateIndexModule(index, moduleId, original -> new ExternalModuleInterface(
            original.moduleId(), kind, original.imports(), original.exports(),
            original.classes(), original.initialization()));
    }

    /**
     * Whether one complete raw unit carries a fact that requires the
     * complete project closure: a SHARED_BODY external function of another
     * module, a CALL externalEntryRef naming another module, or a
     * {@code CLASS_NEW} under an imported/declared default owner. The
     * standalone surfaces never admit such content; the complete project
     * surfaces resolve exactly these links from the supplied closure.
     */
    private static boolean requiresProviderContext(RawUnit unit) {
        for (RawBinding binding : unit.bindings()) {
            if ("externalFunction".equals(binding.shape())
                    && "SHARED_BODY".equals(binding.executionOwner())
                    && binding.modulePath() != null
                    && !unit.modulePath().equals(binding.modulePath())) {
                return true;
            }
        }
        for (RawOp op : unit.ops()) {
            if (SemanticOpKind.CALL.name().equals(op.kind())) {
                CanonicalJson.Value ref = payloadField(op.payload(), "externalEntryRef");
                if (ref instanceof CanonicalJson.Obj refObj) {
                    CanonicalJson.Value module = payloadField(refObj, "module");
                    if (module instanceof CanonicalJson.Obj moduleObj
                            && !unit.modulePath().equals(
                                modulePathOf(moduleObj))) {
                        return true;
                    }
                }
            }
            if (SemanticOpKind.CLASS_NEW.name().equals(op.kind())) {
                CanonicalJson.Value owner = payloadField(op.payload(), "defaultOwner");
                if (owner instanceof CanonicalJson.Str str
                        && (DefaultOwner.SHARED_FACTORY.name().equals(str.value())
                            || DefaultOwner.HOST_DEFAULTS.name().equals(str.value())
                            || DefaultOwner.FFI_PLAN.name().equals(str.value()))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** One payload member of a raw operation payload, or null. */
    private static CanonicalJson.Value payloadField(CanonicalJson.Obj payload, String key) {
        return at(payload, key);
    }

    /** The module path of a canonical module-identity object, or null. */
    private static String modulePathOf(CanonicalJson.Obj moduleId) {
        return at(moduleId, "path") instanceof CanonicalJson.Str str ? str.value() : null;
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

    /** The manifest with one module entry's first export declared type replaced. */
    private static byte[] manifestWithIndexExportType(byte[] manifest) {
        CanonicalJson.Obj root = objOf(manifest);
        CanonicalJson.Obj index = (CanonicalJson.Obj) at(root, "interfaceIndex");
        CanonicalJson.Arr modules = (CanonicalJson.Arr) at(index, "modules");
        List<CanonicalJson.Value> entries = new ArrayList<>();
        for (int i = 0; i < modules.items().size(); i++) {
            CanonicalJson.Obj entry = (CanonicalJson.Obj) modules.items().get(i);
            CanonicalJson.Arr exports = (CanonicalJson.Arr) at(entry, "exports");
            if (i == 0 && !exports.items().isEmpty()) {
                List<CanonicalJson.Value> updated = new ArrayList<>(exports.items());
                CanonicalJson.Obj first = (CanonicalJson.Obj) updated.get(0);
                updated.set(0, withEntry(first, "declaredType",
                    CanonicalJson.str("(i32) => i32")));
                entry = withEntry(entry, "exports", CanonicalJson.arr(updated));
            }
            entries.add(entry);
        }
        return CanonicalJson.serializeBytes(withEntry(root, "interfaceIndex",
            withEntry(index, "modules", CanonicalJson.arr(entries))));
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
        return mutateSeed(manifest, className,
            row -> withEntry(row, "owner", CanonicalJson.str(owner)));
    }

    private static byte[] mutateSeed(byte[] manifest, String className,
            UnaryOperator<CanonicalJson.Obj> mutation) {
        CanonicalJson.Obj root = objOf(manifest);
        CanonicalJson.Arr seeds = (CanonicalJson.Arr) at(root, "classSeeds");
        List<CanonicalJson.Value> rows = new ArrayList<>();
        for (CanonicalJson.Value item : seeds.items()) {
            CanonicalJson.Obj row = (CanonicalJson.Obj) item;
            CanonicalJson.Obj key = (CanonicalJson.Obj) at(row, "key");
            CanonicalJson.Obj updated = className.equals(((CanonicalJson.Str) at(key, "name")).value())
                ? mutation.apply(row) : row;
            if (updated != null) {
                rows.add(updated);
            }
        }
        return CanonicalJson.serializeBytes(withEntry(root, "classSeeds", CanonicalJson.arr(rows)));
    }

    private static CanonicalJson.Obj mutateSeedFields(CanonicalJson.Obj row,
            UnaryOperator<CanonicalJson.Obj> mutation) {
        CanonicalJson.Obj layout = (CanonicalJson.Obj) at(row, "layout");
        CanonicalJson.Arr fields = (CanonicalJson.Arr) at(layout, "fields");
        List<CanonicalJson.Value> updated = new ArrayList<>();
        for (CanonicalJson.Value field : fields.items()) {
            updated.add(mutation.apply((CanonicalJson.Obj) field));
        }
        return withEntry(row, "layout", withEntry(layout, "fields", CanonicalJson.arr(updated)));
    }

    /**
     * The manifest/project with the named class seed's registration owner
     * member and every field owner replaced: the mutated registration stays
     * one owner per class (a representable registration), so the seed/index
     * rule cannot see the disagreement and only the module join can.
     */
    private static byte[] manifestWithSeedOwnerAndFieldOwners(byte[] manifest,
            String className, String owner) {
        return mutateSeed(manifest, className, row -> mutateSeedFields(
            withEntry(row, "owner", CanonicalJson.str(owner)),
            field -> withEntry(field, "defaultOwner", CanonicalJson.str(owner))));
    }

    /** The manifest with one seed field's canonical descriptor text replaced. */
    private static byte[] manifestWithSeedFieldDescriptor(byte[] manifest,
            String className, String fieldName, String descriptor) {
        return mutateSeed(manifest, className, row -> mutateSeedFields(row,
            field -> fieldName.equals(((CanonicalJson.Str) at(field, "name")).value())
                ? withEntry(field, "descriptor", CanonicalJson.str(descriptor)) : field));
    }

    /** The manifest with one seed's registration key renamed. */
    private static byte[] manifestWithSeedKey(byte[] manifest, String className,
            String newName) {
        return mutateSeed(manifest, className, row -> withEntry(row, "key",
            withEntry((CanonicalJson.Obj) at(row, "key"), "name", CanonicalJson.str(newName))));
    }

    /** The manifest with the named class's registration removed. */
    private static byte[] manifestWithoutSeed(byte[] manifest, String className) {
        return mutateSeed(manifest, className, row -> null);
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

    /**
     * The combined common-admission dependency check (ISSUE-0819, T1-T4):
     * the real project is lowered once (T1), every dependency-ordered unit
     * and the project travel typed -> raw -> text -> raw (T2/T3), the
     * unchanged content admits on both surfaces, and one module's membership
     * mutation then rejects with the identical first E6005 on the typed unit,
     * the unit text, the typed project, the project text and the composed
     * production admission, while the dumped module text (T4) still shows the
     * mutation.
     */
    private static void testCommonAdmissionMembershipParity(LoweredProject baseline) {
        System.out.println("-- the common admission over the real transported project: "
            + "typed/text membership parity (ISSUE-0819) --");
        SemanticLowerer.ProjectLoweringResult result = baseline.result();
        ExecutableLoweredProject lowered = result.project();
        check(lowered != null, "the common-admission fixture lowers: "
            + result.diagnostics());
        if (lowered == null) {
            return;
        }
        SemanticIrValidator.ComparisonFacts facts = comparisonFacts(baseline.project());

        // T1/T2: the dependency-ordered units travel typed -> raw -> text -> raw
        // and the unchanged content admits on both surfaces.
        for (LoweredModuleUnit unit : lowered.modules().values()) {
            String text = SemanticIrValidator.toUnitText(unit);
            assertStandaloneAdmission(unit, text, assertUnitTransport(unit, text), facts);
        }
        // T3: the project transport keeps the complete project and admits on
        // both surfaces.
        assertProjectTransport(lowered, facts);

        // The membership mutation: the entry module loses one inverse row
        // while its block list keeps the op.
        LoweredModuleUnit targetUnit = lowered.modules().get(APP);
        check(targetUnit != null, "the entry module is in the closure");
        if (targetUnit == null) {
            return;
        }
        OpId last = null;
        for (OpId op : targetUnit.bodyTable().opBlocks().keySet()) {
            last = op;
        }
        final OpId removed = last;
        check(removed != null, "the entry module carries membership rows");
        if (removed == null) {
            return;
        }
        Map<OpId, BlockId> inverse = new LinkedHashMap<>(targetUnit.bodyTable().opBlocks());
        inverse.remove(removed);
        StructuredBodyTable mutatedTable = new StructuredBodyTable(
            targetUnit.bodyTable().blockOps(), inverse);
        LoweredModuleUnit mutated = new LoweredModuleUnit(targetUnit.formatVersion(),
            targetUnit.semanticProfile(), targetUnit.moduleId(), targetUnit.interfaceHash(),
            targetUnit.loweringContextHash(), targetUnit.requiredCapabilities(),
            targetUnit.constructCoverage(), targetUnit.classLayouts(), targetUnit.functions(),
            targetUnit.moduleInit(), targetUnit.exportPlan(), targetUnit.functionBindings(),
            targetUnit.ops(), mutatedTable, targetUnit.classFactories(),
            targetUnit.jsonDefaults(), targetUnit.classBindingFacts());

        String mutatedText = SemanticIrValidator.toUnitText(mutated);
        RawUnit mutatedReparsed = ContractSnapshotCanonicalizer.parseUnit(
            (CanonicalJson.Obj) CanonicalJson.parse(mutatedText));
        checkEq(RawUnit.fromTyped(mutated), mutatedReparsed,
            "the membership mutation survives typed -> raw -> text -> raw unchanged");
        check(!mutatedReparsed.bodyTable().opBlocks().containsKey(removed)
                && mutatedReparsed.bodyTable().blockOps().values().stream()
                    .anyMatch(ids -> ids.contains(removed)),
            "the transported mutation keeps the block row and drops the inverse row");

        expectBindingGraphMutationRejects("a missing inverse membership row", lowered, facts,
            mutated, "CONTROL_BLOCK_TREE", "no inverse membership entry");

        // T4: the module dump still shows the mutation, and the unmutated dump
        // differs from it.
        String dumped = SemanticIrDumper.dumpModuleText(mutated);
        RawUnit dumpedUnit = ContractSnapshotCanonicalizer.parseUnit(
            (CanonicalJson.Obj) CanonicalJson.parse(dumped));
        check(!dumpedUnit.bodyTable().opBlocks().containsKey(removed)
                && dumpedUnit.bodyTable().blockOps().values().stream()
                    .anyMatch(ids -> ids.contains(removed)),
            "the dumped module text still shows the membership mutation");
        check(!dumped.equals(SemanticIrDumper.dumpModuleText(targetUnit)),
            "the dumped module text differs from the unmutated dump");
    }

    /**
     * The combined common-admission dependency check (ISSUE-0820, T1-T5):
     * the real project lowers once (T1) with every complete binding variant,
     * a module-level class with generated JSON defaults, an imported class
     * factory, an ordinary closure with two generation-pinned captures and
     * parameter/import initializations; every unit and the project travel
     * typed -> raw -> text -> raw (T2/T3) unchanged and admit on both
     * surfaces with the result views equal to the owned facts; then one-record
     * binding and class mutations reject identically on both surfaces with no
     * partial project, and remain visible in the transported text (T4/T5).
     */
    private static void testCommonAdmissionBindingAndClassParity() throws Exception {
        System.out.println("-- the common admission over the real transported project: "
            + "functions, captures, bindings and class construction parity (ISSUE-0820) --");
        RealProject project = compileProject(BINDING_CLASS_APP_SOURCE,
            Map.of("src/util.deal", BINDING_CLASS_UTIL_SOURCE,
                "src/cfg.d.deal", BINDING_CLASS_DECLARATION), Map.of());
        try {
            SemanticLowerer.ProjectLoweringResult result = lower(project, invocation());
            check(!result.hasErrors() && result.project() != null,
                "the binding/class fixture lowers: " + result.diagnostics());
            if (result.project() == null) {
                return;
            }
            ExecutableLoweredProject lowered = result.project();
            SemanticIrValidator.ComparisonFacts facts = comparisonFacts(project);
            LoweredModuleUnit util = lowered.modules().get(UTIL);
            LoweredModuleUnit app = lowered.modules().get(APP);
            check(util != null && app != null,
                "the closure carries the class module and the constructing entry module");
            if (util == null || app == null) {
                return;
            }

            // T1/T2: the dependency-ordered units travel typed -> raw -> text ->
            // raw and the unchanged content admits on both surfaces; the
            // transported graph carries every complete binding variant.
            Set<String> variants = new LinkedHashSet<>();
            LoweredFunction twoCapture = null;
            for (LoweredModuleUnit unit : lowered.modules().values()) {
                String transport = SemanticIrValidator.toUnitText(unit);
                assertStandaloneAdmission(unit, transport, assertUnitTransport(unit, transport), facts);
                for (FunctionExecutionBinding binding : unit.functionBindings().values()) {
                    variants.add(bindingName(binding));
                }
                for (LoweredFunction function : unit.functions().values()) {
                    if (function.captures().size() > 1) {
                        twoCapture = function;
                    }
                }
            }
            checkEq(Set.of("loweredBody", "adapter", "hostFunction", "hostFunctionValue",
                    "externalFunction", "intrinsicFunction", "dynamicFunctionValue"),
                variants, "the transported project carries every complete binding variant");
            check(twoCapture != null, "the fixture carries the two-capture ordinary closure");
            if (twoCapture == null) {
                return;
            }
            checkEq(closureCaptures(util, twoCapture.functionId()), twoCapture.captures(),
                "the closure payload and the function record carry the same ordered "
                    + "generation-pinned captures");
            check(util.classFactories().factories().size() == 1
                    && util.jsonDefaults().defaultChildren().size() == 1,
                "the class module carries its factory registry and its JSON-default children");
            boolean sharedFactory = false;
            for (SemanticOp op : app.ops()) {
                if (op.payload() instanceof KindPayload.ClassNewPayload newPayload
                        && newPayload.defaultOwner() == DefaultOwner.SHARED_FACTORY) {
                    sharedFactory = true;
                }
            }
            check(sharedFactory, "the imported class factory construction is transported");
            check(!util.classBindingFacts().parameters().isEmpty()
                    && !app.classBindingFacts().importAliases().isEmpty(),
                "the parameter and import-alias initialization facts are transported");

            // T3: the project transport keeps the complete project and admits
            // on both surfaces; the result views are the owned records.
            assertProjectTransport(lowered, facts);
            check(lowered.classSeeds() == result.seeds(),
                "result.seeds() is exactly project.classSeeds()");
            for (LoweredModuleUnit unit : lowered.modules().values()) {
                check(result.tableOf(unit.moduleId()) == unit.bodyTable()
                        && result.registryOf(unit.moduleId()) == unit.classFactories(),
                    unit.moduleId() + ": the result views are the unit's own records");
            }

            // Diagnostic-only metadata never becomes a rejection: a unit
            // text whose operation origin carries an absent source id, an
            // unusable position and an open kind still admits, and the
            // materialized admission does not throw.
            {
                String doctored = withFirstOpOrigin(SemanticIrValidator.toUnitText(util),
                    CanonicalJson.obj(
                        CanonicalJson.e("sourceId", CanonicalJson.nullValue()),
                        CanonicalJson.e("span", CanonicalJson.obj(
                            CanonicalJson.e("file", CanonicalJson.str("doctored.deal")),
                            CanonicalJson.e("startLine", CanonicalJson.intValue(0)),
                            CanonicalJson.e("startColumn", CanonicalJson.intValue(0)),
                            CanonicalJson.e("endLine", CanonicalJson.intValue(0)),
                            CanonicalJson.e("endColumn", CanonicalJson.intValue(0)),
                            CanonicalJson.e("startScalarOffset",
                                CanonicalJson.intValue(-1)),
                            CanonicalJson.e("endScalarOffset",
                                CanonicalJson.intValue(-1)))),
                        CanonicalJson.e("kind", CanonicalJson.str("NOT_A_KIND")),
                        CanonicalJson.e("anchorId", CanonicalJson.obj(
                            CanonicalJson.e("id", CanonicalJson.intValue(7)),
                            CanonicalJson.e("type", CanonicalJson.str("anchor")))),
                        CanonicalJson.e("parentOpId", CanonicalJson.nullValue())));
                Optional<CompilerDiagnostic> admitted;
                try {
                    admitted = SemanticIrValidator.validateText(doctored, facts);
                } catch (RuntimeException e) {
                    check(false, "a doctored diagnostic-only origin must not change "
                        + "admission or throw: " + e);
                    admitted = null;
                }
                check(admitted != null && admitted.isEmpty(),
                    "a doctored diagnostic-only origin leaves admission unchanged: "
                        + (admitted == null ? "threw"
                            : admitted.map(CompilerDiagnostic::message).orElse("admitted")));
            }

            // Fail closed: a decodable unit text whose payload loses a
            // mandatory recognized position passes the transport framing and
            // rejects as the closed-position E6005 of the materialized
            // admission, never as an unchecked failure.
            {
                String utilText = SemanticIrValidator.toUnitText(util);
                String dropped = withoutPayloadKey(utilText, "mutable");
                check(!dropped.equals(utilText),
                    "the dropped payload position is a real text mutation");
                Optional<CompilerDiagnostic> failure = null;
                try {
                    failure = SemanticIrValidator.validateText(dropped, facts);
                } catch (RuntimeException e) {
                    check(false, "a decodable dropped payload position must fail closed, "
                        + "never throw: " + e);
                }
                assertMutationRule("a dropped payload position", failure, "R-ENUM",
                    "payload position", "is absent");
            }

            List<BindingGeneration> captures = twoCapture.captures();
            BindingGeneration first = captures.get(0);
            List<BindingGeneration> changed = new ArrayList<>(captures);
            changed.set(0, new BindingGeneration(first.binding(), first.generation() + 1));
            List<BindingGeneration> duplicated = new ArrayList<>(captures);
            duplicated.add(first);
            record CaptureCase(String name, List<BindingGeneration> values,
                               boolean payloadToo, String detail) { }
            for (CaptureCase row : List.of(
                    new CaptureCase("a dropped capture", List.of(first), true, "outside the captures list"),
                    new CaptureCase("a duplicated capture binding", duplicated, true, "more than once"),
                    new CaptureCase("a changed capture generation", changed, false, "publishes captures"),
                    new CaptureCase("a reordered capture list",
                        List.of(captures.get(1), first), false, "publishes captures"),
                    new CaptureCase("a stale capture generation", changed, true, "names generation"))) {
                LoweredModuleUnit mutated = withFunction(util, twoCapture, row.values());
                if (row.payloadToo()) {
                    mutated = withClosureCaptures(mutated, twoCapture.functionId(), row.values());
                }
                expectBindingGraphMutationRejects(row.name(), lowered, facts,
                    mutated, "CAPTURE_RESOLUTION", row.detail());
            }
            // Mutation (5): break the function body link (the record's body
            // block differs from the closure's registered body).
            {
                BlockId otherBody = otherBlockOf(util, twoCapture.body());
                Map<FunctionId, LoweredFunction> functions =
                    new LinkedHashMap<>(util.functions());
                functions.put(twoCapture.functionId(), new LoweredFunction(
                    twoCapture.functionId(), twoCapture.descriptor(), twoCapture.captures(),
                    otherBody, twoCapture.sourceName()));
                LoweredModuleUnit mutated = withFunctions(util, functions);
                // The control owner runs before the bindings owner and owns the
                // detached RETURN whose recorded function no longer has that
                // body block.
                expectBindingGraphMutationRejects("a retargeted function body link",
                    lowered, facts, mutated, "CONTROL_EXIT", "names function");
            }
            // Mutation (6): retarget the loweredBody registration's block
            // (the registry entry alone).
            {
                FunctionAllocationIdentity key = allocationOf(
                    util, twoCapture.functionId());
                FunctionExecutionBinding.LoweredBody body =
                    (FunctionExecutionBinding.LoweredBody) util.functionBindings().get(key);
                Map<FunctionAllocationIdentity, FunctionExecutionBinding> bindings =
                    new LinkedHashMap<>(util.functionBindings());
                bindings.put(key, new FunctionExecutionBinding.LoweredBody(
                    body.functionId(), otherBlockOf(util, body.blockId())));
                LoweredModuleUnit mutated = withBindings(util, bindings);
                expectBindingGraphMutationRejects("a retargeted loweredBody block",
                    lowered, facts, mutated, "REGISTRY_ONE_TO_ONE",
                    "does not match its LoweredBody payload");
            }
            FunctionAllocationIdentity adapterId = adapterKey(app);
            FunctionExecutionBinding.AdapterBinding adapter =
                (FunctionExecutionBinding.AdapterBinding) app.functionBindings().get(adapterId);
            AdaptSourceRef.SharedCell shared = (AdaptSourceRef.SharedCell) adapter.sourceRef();
            record AdapterCase(String name, AdaptSourceRef source,
                               RuntimeDescriptor.Func target, String detail) { }
            for (AdapterCase row : List.of(
                    new AdapterCase("signature", adapter.sourceRef(),
                        new RuntimeDescriptor.Func(Collections.nCopies(3, RuntimeDescriptor.Int.INSTANCE),
                            RuntimeDescriptor.Int.INSTANCE), "registration carries signatures"),
                    new AdapterCase("sourceRef",
                        new AdaptSourceRef.SharedCell(shared.binding(), shared.generation() + 1),
                        adapter.targetSignature(), "registration carries mode"))) {
                Map<FunctionAllocationIdentity, FunctionExecutionBinding> bindings =
                    new LinkedHashMap<>(app.functionBindings());
                bindings.put(adapterId, new FunctionExecutionBinding.AdapterBinding(
                    adapter.adaptOpId(), adapter.captureMode(), row.source(),
                    adapter.sourceSignature(), row.target()));
                expectBindingGraphMutationRejects("a retargeted adapter " + row.name(),
                    lowered, facts, withBindings(app, bindings), "REGISTRY_ONE_TO_ONE", row.detail());
            }
            // Mutation (9): mis-resolve the host-function-value materializing
            // boundary op (the registry alone).
            {
                FunctionAllocationIdentity key = hostValueKey(app);
                FunctionExecutionBinding.HostFunctionValue value =
                    (FunctionExecutionBinding.HostFunctionValue)
                        app.functionBindings().get(key);
                OpId notACrossing = firstConstOp(app);
                check(notACrossing != null, "the fixture carries a non-crossing op");
                if (notACrossing != null) {
                    Map<FunctionAllocationIdentity, FunctionExecutionBinding> bindings =
                        new LinkedHashMap<>(app.functionBindings());
                    bindings.put(key, new FunctionExecutionBinding.HostFunctionValue(
                        value.hostModuleId(), notACrossing, value.descriptor()));
                    LoweredModuleUnit mutated = withBindings(app, bindings);
                    expectBindingGraphMutationRejects(
                        "a mis-resolved materializing boundary op", lowered, facts,
                        mutated, "R-FUNCTION-BINDING", "does not resolve");
                }
            }
            BindingId local = null;
            for (BindingGeneration capture : captures) {
                if (!util.classBindingFacts().parameters().contains(capture.binding())
                        && !util.classBindingFacts().importAliases().contains(capture.binding())) {
                    local = capture.binding();
                }
            }
            check(local != null, "the fixture carries a captured local declaration cell");
            record ParameterCase(String name, BindingId binding, String detail) { }
            for (ParameterCase row : List.of(
                    new ParameterCase("a contradicted parameter initialization fact", local, "targets a parameter cell"),
                    new ParameterCase("a parameter fact naming no allocation", new BindingId(9001),
                        "names no producing allocation"))) {
                if (row.binding() != null) {
                    Set<BindingId> parameters = new LinkedHashSet<>(util.classBindingFacts().parameters());
                    parameters.add(row.binding());
                    LoweredModuleUnit mutated = withClassBindingFacts(util,
                        new ClassBindingFacts(parameters, util.classBindingFacts().importAliases()));
                    expectBindingGraphMutationRejects(row.name(), lowered, facts, mutated,
                        "BINDING_INIT_ONCE", row.detail());
                }
            }
            // Mutation (11): retarget a factory registration to a non-factory
            // op (the class facts need the carried interface context, so the
            // project surfaces reject).
            {
                ClassFactoryId entry = util.classFactories().factories().keySet()
                    .iterator().next();
                OpId classDefault = null;
                for (SemanticOp op : util.ops()) {
                    if (op.kind() == SemanticOpKind.CLASS_DEFAULT) {
                        classDefault = op.opId();
                    }
                }
                check(classDefault != null, "the fixture carries a CLASS_DEFAULT op");
                if (classDefault != null) {
                    Map<ClassFactoryId, OpId> factories = new LinkedHashMap<>(
                        util.classFactories().factories());
                    factories.put(entry, classDefault);
                    LoweredModuleUnit mutated = withFactories(util,
                        new ClassFactoryRegistry(factories));
                    expectClassGraphMutationRejects("a retargeted factory registration",
                        lowered, facts, mutated, "FACTORY_COHERENCE", "not CLASS_FACTORY");
                }
            }
            // Mutation (12a): reorder the CLASS_FACTORY payload's default
            // children with a recomputed snapshot digest: a recomputed digest
            // is no proof that the factory link is valid.
            {
                ClassFactoryId entry = util.classFactories().factories().keySet()
                    .iterator().next();
                OpId factoryOpId = util.classFactories().factoryFor(entry);
                SemanticOp factoryOp = opOf(util, factoryOpId);
                KindPayload.ClassFactoryPayload factoryPayload =
                    (KindPayload.ClassFactoryPayload) factoryOp.payload();
                List<OpId> reordered =
                    new ArrayList<>(factoryPayload.classDefaultOpIds());
                java.util.Collections.reverse(reordered);
                LoweredModuleUnit mutated = replaceOpWith(util, factoryOp,
                    rebuildOp(factoryOp, new KindPayload.ClassFactoryPayload(
                        factoryPayload.classId(), reordered,
                        factoryPayload.callerOpRef())));
                SemanticOp rebuilt = opOf(mutated, factoryOpId);
                checkEq(ContractSnapshotCanonicalizer.digest(rebuilt.contract()),
                    rebuilt.contract().canonicalDigest(),
                    "the retargeted factory op carries its recomputed snapshot digest");
                expectClassGraphMutationRejects("a reordered factory default child",
                    lowered, facts, mutated, "FACTORY_COHERENCE", "not @src/Point.x");
            }
            // Mutation (12): reorder a JSON-default row's class-default
            // children (the class facts).
            {
                OpId jsonFromClass = util.jsonDefaults().defaultChildren().keySet()
                    .iterator().next();
                List<OpId> children =
                    util.jsonDefaults().childrenOf(jsonFromClass);
                Map<OpId, List<OpId>> rows = new LinkedHashMap<>(
                    util.jsonDefaults().defaultChildren());
                List<OpId> reordered = new ArrayList<>(children);
                java.util.Collections.swap(reordered, 0, reordered.size() - 1);
                rows.put(jsonFromClass, reordered);
                LoweredModuleUnit mutated = withJsonDefaults(util,
                    new JsonDefaultChildTable(rows));
                expectClassGraphMutationRejects("a reordered JSON-default child row",
                    lowered, facts, mutated, "JSON_LAYOUT_COHERENCE",
                    "not @src/Point.x");
            }
            // Mutation (13): make the carried constructionEntry disagree with
            // the registry (the class facts).
            {
                ClassFactoryId entry = util.classFactories().factories().keySet()
                    .iterator().next();
                Map<ClassFactoryId, OpId> factories = new LinkedHashMap<>(
                    util.classFactories().factories());
                OpId factoryOp = factories.remove(entry);
                factories.put(new ClassFactoryId(entry.id() + 100), factoryOp);
                LoweredModuleUnit mutated = withFactories(util,
                    new ClassFactoryRegistry(factories));
                expectClassGraphMutationRejects(
                    "a constructionEntry that disagrees with the registry", lowered, facts,
                    mutated, "FACTORY_COHERENCE",
                    "no factory registered under constructionEntry");
            }
            // Mutation (14): the builtin Error registration contradicts the
            // 0787-owned compiler descriptor/layout contract (a project-level
            // carried record: no equivalent unit mutation exists).
            {
                Map<ClassId, ClassRegistrationSeeds.ClassRegistration> registrations =
                    new LinkedHashMap<>(lowered.classSeeds().registrations());
                registrations.put(ClassId.ERROR,
                    new ClassRegistrationSeeds.ClassRegistration(
                        new ClassLayout(ClassId.ERROR, List.of(
                            new ClassLayout.FieldLayout("code",
                                RuntimeDescriptor.String.INSTANCE, true,
                                DefaultOwner.BUILTIN_DEFAULTS))),
                        DefaultOwner.BUILTIN_DEFAULTS));
                ExecutableLoweredProject mutatedProject = new ExecutableLoweredProject(
                    lowered.semanticProfile(), lowered.interfaceIndex(),
                    lowered.modules(), lowered.entryModule(),
                    new ClassRegistrationSeeds(registrations));
                Optional<CompilerDiagnostic> typedProject =
                    SemanticIrValidator.validate(mutatedProject, facts);
                Optional<CompilerDiagnostic> textProject = SemanticIrValidator.validateText(
                    SemanticIrValidator.toProjectText(mutatedProject), facts);
                assertMutationRule("the builtin Error contract (typed project)",
                    typedProject, "CONSTRUCTION_COHERENCE", "compiler-owned");
                assertMutationRule("the builtin Error contract (project text)",
                    textProject, "CONSTRUCTION_COHERENCE", "compiler-owned");
                if (typedProject.isPresent() && textProject.isPresent()) {
                    checkEq(typedProject.get().message(), textProject.get().message(),
                        "the project surfaces report the identical builtin Error failure");
                }
            }
        } finally {
            deleteRecursively(project.root());
        }
    }

    /** The unit text with its first operation's origin replaced. */
    private static String withFirstOpOrigin(String unitText, CanonicalJson.Obj origin) {
        CanonicalJson.Obj root = objOf(unitText.getBytes(
            java.nio.charset.StandardCharsets.UTF_8));
        CanonicalJson.Arr ops = (CanonicalJson.Arr) at(root, "ops");
        List<CanonicalJson.Value> items = new ArrayList<>(ops.items());
        CanonicalJson.Obj first = (CanonicalJson.Obj) items.get(0);
        items.set(0, withEntry(first, "origin", origin));
        return CanonicalJson.serializeText(withEntry(root, "ops", CanonicalJson.arr(items)));
    }

    /** The unit rebuilt with one operation record replaced. */
    private static LoweredModuleUnit replaceOpWith(LoweredModuleUnit unit,
            SemanticOp target, SemanticOp replacement) {
        List<SemanticOp> ops = new ArrayList<>();
        for (SemanticOp op : unit.ops()) {
            ops.add(op == target ? replacement : op);
        }
        return withOperationList(unit, ops);
    }

    /** The unit text with one payload member removed from its BINDING_ALLOC op. */
    private static String withoutPayloadKey(String unitText, String key) {
        CanonicalJson.Obj root = objOf(unitText.getBytes(
            java.nio.charset.StandardCharsets.UTF_8));
        CanonicalJson.Arr ops = (CanonicalJson.Arr) at(root, "ops");
        List<CanonicalJson.Value> items = new ArrayList<>(ops.items());
        for (int i = 0; i < items.size(); i++) {
            CanonicalJson.Obj op = (CanonicalJson.Obj) items.get(i);
            if (!"BINDING_ALLOC".equals(((CanonicalJson.Str) at(op, "kind")).value())) {
                continue;
            }
            CanonicalJson.Obj payload = (CanonicalJson.Obj) at(op, "payload");
            List<CanonicalJson.Entry> entries = new ArrayList<>();
            for (CanonicalJson.Entry entry : payload.entries()) {
                if (!entry.key().equals(key)) {
                    entries.add(entry);
                }
            }
            items.set(i, withEntry(op, "payload", CanonicalJson.obj(entries)));
            break;
        }
        return CanonicalJson.serializeText(withEntry(root, "ops", CanonicalJson.arr(items)));
    }

    /** The binding variant name of one complete registration. */
    private static String bindingName(FunctionExecutionBinding binding) {
        return switch (binding) {
            case FunctionExecutionBinding.LoweredBody ignored -> "loweredBody";
            case FunctionExecutionBinding.AdapterBinding ignored -> "adapter";
            case FunctionExecutionBinding.HostFunction ignored -> "hostFunction";
            case FunctionExecutionBinding.HostFunctionValue ignored -> "hostFunctionValue";
            case FunctionExecutionBinding.ExternalFunction ignored -> "externalFunction";
            case FunctionExecutionBinding.IntrinsicFunction ignored -> "intrinsicFunction";
            case FunctionExecutionBinding.DynamicFunctionValue ignored -> "dynamicFunctionValue";
        };
    }

    /** The closure payload's captures for one function id. */
    private static List<BindingGeneration> closureCaptures(LoweredModuleUnit unit,
            FunctionId functionId) {
        for (SemanticOp op : unit.ops()) {
            if (op.payload() instanceof KindPayload.ClosureNewPayload closure
                    && closure.function().equals(functionId)) {
                return closure.captures();
            }
        }
        return null;
    }

    /** The unit rebuilt with one function record replaced. */
    private static LoweredModuleUnit withFunction(LoweredModuleUnit unit,
            LoweredFunction function, List<BindingGeneration> captures) {
        Map<FunctionId, LoweredFunction> functions =
            new LinkedHashMap<>(unit.functions());
        functions.put(function.functionId(), new LoweredFunction(function.functionId(),
            function.descriptor(), captures, function.body(), function.sourceName()));
        return withFunctions(unit, functions);
    }

    /** The unit rebuilt with one closure payload's captures replaced. */
    private static LoweredModuleUnit withClosureCaptures(LoweredModuleUnit unit,
            FunctionId functionId, List<BindingGeneration> captures) {
        List<SemanticOp> ops = new ArrayList<>();
        for (SemanticOp op : unit.ops()) {
            if (op.payload() instanceof KindPayload.ClosureNewPayload closure
                    && closure.function().equals(functionId)) {
                ops.add(rebuildOp(op, new KindPayload.ClosureNewPayload(closure.function(),
                    closure.signature(), captures, closure.binding())));
            } else {
                ops.add(op);
            }
        }
        return withOperationList(unit, ops);
    }

    /** The same op with another payload and its recomputed contract. */
    private static SemanticOp rebuildOp(SemanticOp op, KindPayload payload) {
        OperationContractSnapshot draft = new OperationContractSnapshot(
            OperationContractSnapshot.VERSION, op.kind(), op.resultType(),
            op.operandTypes(), payload instanceof KindPayload.SelectorCarrying carrying
                ? carrying.selector() : null,
            payload, op.failurePolicy(), op.contract().referencedSemanticIds(), "draft");
        OperationContractSnapshot contract = new OperationContractSnapshot(
            OperationContractSnapshot.VERSION, op.kind(), op.resultType(),
            op.operandTypes(), draft.selector(), payload, op.failurePolicy(),
            op.contract().referencedSemanticIds(),
            ContractSnapshotCanonicalizer.digest(draft));
        return new SemanticOp(op.opId(), op.kind(), op.origin(), op.result(),
            op.resultType(), op.operands(), op.operandTypes(), payload, op.failurePolicy(),
            contract);
    }

    /** The unit rebuilt with another operation list. */
    private static LoweredModuleUnit withOperationList(LoweredModuleUnit unit,
            List<SemanticOp> ops) {
        return withOps(unit, ops);
    }

    /** The unit rebuilt with another function registry. */
    private static LoweredModuleUnit withFunctions(LoweredModuleUnit unit,
            Map<FunctionId, LoweredFunction> functions) {
        return new LoweredModuleUnit(unit.formatVersion(), unit.semanticProfile(),
            unit.moduleId(), unit.interfaceHash(), unit.loweringContextHash(),
            unit.requiredCapabilities(), unit.constructCoverage(), unit.classLayouts(),
            functions, unit.moduleInit(), unit.exportPlan(), unit.functionBindings(),
            unit.ops(), unit.bodyTable(), unit.classFactories(), unit.jsonDefaults(),
            unit.classBindingFacts());
    }

    /** The unit rebuilt with another binding registry. */
    private static LoweredModuleUnit withBindings(LoweredModuleUnit unit,
            Map<FunctionAllocationIdentity, FunctionExecutionBinding> bindings) {
        return new LoweredModuleUnit(unit.formatVersion(), unit.semanticProfile(),
            unit.moduleId(), unit.interfaceHash(), unit.loweringContextHash(),
            unit.requiredCapabilities(), unit.constructCoverage(), unit.classLayouts(),
            unit.functions(), unit.moduleInit(), unit.exportPlan(), bindings, unit.ops(),
            unit.bodyTable(), unit.classFactories(), unit.jsonDefaults(),
            unit.classBindingFacts());
    }

    /** The unit rebuilt with another class-factory registry. */
    private static LoweredModuleUnit withFactories(LoweredModuleUnit unit,
            ClassFactoryRegistry factories) {
        return new LoweredModuleUnit(unit.formatVersion(), unit.semanticProfile(),
            unit.moduleId(), unit.interfaceHash(), unit.loweringContextHash(),
            unit.requiredCapabilities(), unit.constructCoverage(), unit.classLayouts(),
            unit.functions(), unit.moduleInit(), unit.exportPlan(), unit.functionBindings(),
            unit.ops(), unit.bodyTable(), factories, unit.jsonDefaults(),
            unit.classBindingFacts());
    }

    /** The unit rebuilt with another JSON-default child table. */
    private static LoweredModuleUnit withJsonDefaults(LoweredModuleUnit unit,
            JsonDefaultChildTable jsonDefaults) {
        return new LoweredModuleUnit(unit.formatVersion(), unit.semanticProfile(),
            unit.moduleId(), unit.interfaceHash(), unit.loweringContextHash(),
            unit.requiredCapabilities(), unit.constructCoverage(), unit.classLayouts(),
            unit.functions(), unit.moduleInit(), unit.exportPlan(), unit.functionBindings(),
            unit.ops(), unit.bodyTable(), unit.classFactories(), jsonDefaults,
            unit.classBindingFacts());
    }

    /** The unit rebuilt with another parameter/import initialization fact set. */
    private static LoweredModuleUnit withClassBindingFacts(LoweredModuleUnit unit,
            ClassBindingFacts facts) {
        return new LoweredModuleUnit(unit.formatVersion(), unit.semanticProfile(),
            unit.moduleId(), unit.interfaceHash(), unit.loweringContextHash(),
            unit.requiredCapabilities(), unit.constructCoverage(), unit.classLayouts(),
            unit.functions(), unit.moduleInit(), unit.exportPlan(), unit.functionBindings(),
            unit.ops(), unit.bodyTable(), unit.classFactories(), unit.jsonDefaults(), facts);
    }

    /** Another block of the unit's membership table. */
    private static BlockId otherBlockOf(LoweredModuleUnit unit, BlockId block) {
        for (BlockId candidate : unit.bodyTable().blockOps().keySet()) {
            if (!candidate.equals(block)) {
                return candidate;
            }
        }
        return block;
    }

    /** The allocation identity of one registered function id. */
    private static FunctionAllocationIdentity allocationOf(LoweredModuleUnit unit,
            FunctionId functionId) {
        for (Map.Entry<FunctionAllocationIdentity, FunctionExecutionBinding> entry
                : unit.functionBindings().entrySet()) {
            if (entry.getValue() instanceof FunctionExecutionBinding.LoweredBody body
                    && body.functionId().equals(functionId)) {
                return entry.getKey();
            }
        }
        return null;
    }

    /** The adapter registration's allocation identity. */
    private static FunctionAllocationIdentity adapterKey(LoweredModuleUnit unit) {
        for (Map.Entry<FunctionAllocationIdentity, FunctionExecutionBinding> entry
                : unit.functionBindings().entrySet()) {
            if (entry.getValue() instanceof FunctionExecutionBinding.AdapterBinding) {
                return entry.getKey();
            }
        }
        return null;
    }

    /** The host-function-value registration's allocation identity. */
    private static FunctionAllocationIdentity hostValueKey(LoweredModuleUnit unit) {
        for (Map.Entry<FunctionAllocationIdentity, FunctionExecutionBinding> entry
                : unit.functionBindings().entrySet()) {
            if (entry.getValue() instanceof FunctionExecutionBinding.HostFunctionValue) {
                return entry.getKey();
            }
        }
        return null;
    }

    /** The first CONST op's id. */
    private static OpId firstConstOp(LoweredModuleUnit unit) {
        for (SemanticOp op : unit.ops()) {
            if (op.kind() == SemanticOpKind.CONST) {
                return op.opId();
            }
        }
        return null;
    }

    /**
     * One binding mutation: the mutation survives typed -> raw -> text -> raw,
     * the typed and text unit admissions, the typed and text project
     * admissions and the composed production admission all report the same
     * first E6005 (rule, message and range), and the mutation stays visible in
     * the transported text; no project or partial companion is exposed.
     */
    private static void expectBindingGraphMutationRejects(String what,
            ExecutableLoweredProject lowered, SemanticIrValidator.ComparisonFacts facts,
            LoweredModuleUnit mutated, String rule, String... contains) {
        expectGraphMutationRejects(what, lowered, facts, mutated, false, rule, contains);
    }

    /**
     * One class-fact mutation: a standalone unit carries no provider context,
     * so its applicable predicates admit; the typed and text project
     * admissions and the composed production admission reject with the same
     * first E6005, and the mutation stays visible in the transported text.
     */
    private static void expectClassGraphMutationRejects(String what,
            ExecutableLoweredProject lowered, SemanticIrValidator.ComparisonFacts facts,
            LoweredModuleUnit mutated, String rule, String... contains) {
        expectGraphMutationRejects(what, lowered, facts, mutated, true, rule, contains);
    }

    private static void expectGraphMutationRejects(String what,
            ExecutableLoweredProject lowered, SemanticIrValidator.ComparisonFacts facts,
            LoweredModuleUnit mutated, boolean standaloneAdmits, String rule, String... contains) {
        String text = SemanticIrValidator.toUnitText(mutated);
        RawUnit reparsed = assertUnitTransport(mutated, text);
        check(!text.equals(SemanticIrValidator.toUnitText(
                lowered.modules().get(mutated.moduleId()))),
            what + ": mutation stays visible in transported text");
        for (RawOp op : reparsed.ops()) {
            checkEq(op.canonicalDigest(),
                ContractSnapshotCanonicalizer.recomputeSnapshotDigest(op.snapshot()),
                what + ": op " + op.opId() + " keeps a valid contract digest");
        }
        Optional<CompilerDiagnostic> typedUnit = SemanticIrValidator.validate(mutated, facts);
        Optional<CompilerDiagnostic> textUnit = SemanticIrValidator.validateText(text, facts);
        Optional<CompilerDiagnostic> typedProject = projectAdmission(lowered, mutated, facts);
        Optional<CompilerDiagnostic> textProject = SemanticIrValidator.validateText(
            SemanticIrValidator.toProjectText(mutatedProject(lowered, mutated)), facts);
        if (standaloneAdmits) {
            check(typedUnit.isEmpty(), what + ": standalone typed predicates pass without provider context");
            check(textUnit.isEmpty(), what + ": standalone text predicates pass without provider context");
        } else {
            assertMutationRule(what + " (typed unit)", typedUnit, rule, contains);
            assertMutationRule(what + " (unit text)", textUnit, rule, contains);
            assertSameFailure(what + " unit surfaces", typedUnit, textUnit);
            assertSameFailure(what + " unit/project surfaces", typedUnit, typedProject);
        }
        assertMutationRule(what + " (typed project)", typedProject, rule, contains);
        assertMutationRule(what + " (project text)", textProject, rule, contains);
        assertSameFailure(what + " project surfaces", typedProject, textProject);
        Optional<CompilerDiagnostic> composed = SemanticLowerer.validateProjectUnit(mutated,
            mutated.bodyTable(), facts, mutated.classBindingFacts(), mutated.classFactories(),
            mutated.jsonDefaults(), lowered.interfaceIndex().modules().get(mutated.moduleId()),
            Map.of(), lowered.classSeeds().registrations());
        assertSameFailure(what + " composed production admission", typedProject, composed);
    }

    private static void assertSameFailure(String what, Optional<CompilerDiagnostic> expected,
            Optional<CompilerDiagnostic> actual) {
        check(expected.isPresent() && actual.isPresent(), what + ": both reject");
        if (expected.isPresent() && actual.isPresent()) {
            checkEq(expected.get().code(), actual.get().code(), what + ": same code");
            checkEq(expected.get().message(), actual.get().message(), what + ": identical first failure");
            checkEq(expected.get().range(), actual.get().range(), what + ": identical origin range");
        }
    }

    private static ExecutableLoweredProject mutatedProject(ExecutableLoweredProject lowered,
            LoweredModuleUnit mutated) {
        Map<ModuleId, LoweredModuleUnit> modules = new LinkedHashMap<>(lowered.modules());
        modules.put(mutated.moduleId(), mutated);
        return new ExecutableLoweredProject(lowered.semanticProfile(),
            lowered.interfaceIndex(), modules, lowered.entryModule(), lowered.classSeeds());
    }

    private static Optional<CompilerDiagnostic> projectAdmission(
            ExecutableLoweredProject lowered, LoweredModuleUnit mutated,
            SemanticIrValidator.ComparisonFacts facts) {
        return SemanticIrValidator.validate(mutatedProject(lowered, mutated), facts);
    }

    private static void assertMutationRule(String what,
            Optional<CompilerDiagnostic> failure, String rule, String... contains) {
        check(failure.isPresent(), what + " is rejected with E6005");
        if (failure.isEmpty()) {
            return;
        }
        CompilerDiagnostic diagnostic = failure.get();
        check("E6005".equals(diagnostic.code()),
            what + " carries the canonical E6005 code");
        check(diagnostic.message().contains(rule)
                && diagnostic.message().contains("validatorRule " + rule + ","),
            what + " is named as the validator rule " + rule + "; got "
                + diagnostic.message());
        for (String fragment : contains) {
            check(diagnostic.message().contains(fragment),
                what + " names \"" + fragment + "\"; got " + diagnostic.message());
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
            testCommonAdmissionMembershipParity(baseline);
            testCommonAdmissionBindingAndClassParity();
            testInProjectClassArm();
            importedProject = compileProject(IMPORTED_CLASS_APP_SOURCE,
                Map.of("src/util.deal", IMPORTED_CLASS_UTIL_SOURCE), Map.of());
            LoweredProject imported = new LoweredProject(importedProject,
                lower(importedProject, invocation()));
            testImportedInProjectClassResolution(imported);
            testStandaloneProviderContextAndExactViews(imported, baseline);
            testInconsistentFactSeed(imported);
            testDeclarationClassConstruction();
            testBuiltinErrorConstruction();
            testExternCDeclarationClassConstruction();
            testProjectSeedNominalClassReference();
            testSeedImplementationReferenceAndPrivateFieldRead();
            testSameSpelledProviderResolution();
            testSameNameSiblingProviderResolution();
            testImportedFieldWriteProviderDeferral();
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
