package deal.test;

import deal.checker.BuiltinErrorDeclaration;
import deal.codegen.jvm.JvmNames;
import deal.diagnostics.CompilerDiagnostic;
import deal.module.CompilationOrchestrator;
import deal.project.CliOverrides;
import deal.project.ProjectLocator;
import deal.semantic.CheckedProjectBuildResult;
import deal.semantic.CompilerInvocation;
import deal.semantic.CompilerProfileProvider;
import deal.semantic.ControlFlowValidator;
import deal.semantic.ReleaseConfiguration;
import deal.semantic.RequirementManifestResult;
import deal.semantic.SemanticLowerer;
import deal.semantic.SemanticRuntimeModel;
import deal.semantic.ir.AsyncLinkKind;
import deal.semantic.ir.AsyncStartSource;
import deal.semantic.ir.AsyncTokenId;
import deal.semantic.ir.AsyncTokenOwner;
import deal.semantic.ir.BindingGeneration;
import deal.semantic.ir.BoundaryKind;
import deal.semantic.ir.CallMode;
import deal.semantic.ir.ClassFactoryRegistry;
import deal.semantic.ir.ExecutableLoweredProject;
import deal.semantic.ir.ExternalAsyncLink;
import deal.semantic.ir.ExternalExecutionOwner;
import deal.semantic.ir.FunctionAllocationIdentity;
import deal.semantic.ir.FunctionExecutionBinding;
import deal.semantic.ir.FunctionId;
import deal.semantic.ir.IntrinsicKind;
import deal.semantic.ir.KindPayload;
import deal.semantic.ir.LoweredFunction;
import deal.semantic.ir.LoweredModuleUnit;
import deal.semantic.ir.ModuleId;
import deal.semantic.ir.OpId;
import deal.semantic.ir.ParameterBoundaryMode;
import deal.semantic.ir.RuntimeDescriptor;
import deal.semantic.ir.SemanticOp;
import deal.semantic.ir.SemanticOpKind;
import deal.semantic.ir.SemanticIrDumper;
import deal.semantic.ir.StructuredBodyTable;
import deal.semantic.ir.ValueId;
import deal.test.conformance.JvmCompilation;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * ISSUE-0757 acceptance remediation of ISSUE-0699: the declared body's
 * creation-site carrier ({@code residual-carrier-shapes-production-realization}
 * R4 and the local declared-body contract;
 * {@code luajit-jvm-single-lowering-production-cutover} C10/K12).
 *
 * <p>The reported defect: a declared-name call of a non-group declaration
 * reconstructed the callee's factory at the invocation site
 * ({@code CallCallee.Static}) and re-resolved the declaration's captures in
 * the calling function's frame. For a declaration whose creation site is an
 * enclosing invocation — the acyclic sibling shape {@code make(base)}
 * containing {@code target()} reading {@code base} and {@code caller()}
 * calling {@code target()} — the caller does not itself capture
 * {@code base}, so the reconstruction read the module-level cell slot and the
 * escaped closure failed at execution ({@code attempt to index upvalue ...
 * (a nil value)} under LuaJIT). The same restriction held in the await arm.
 * The fix routes every capture-carrying declared-name invocation through the
 * declaration's published closure carrier ({@code CallCallee.Indirect}),
 * preserving the declaration's own closure identity, reserved call-site
 * identity, and single return cell.</p>
 *
 * <p>The second reported integration gap: a forward module function alias —
 * {@code let alias = f} before {@code f}'s declaration — failed the
 * lowering with E6005 {@code CONSTRUCT_UNLOWERED} ("identity ... has no
 * registered FunctionExecutionBinding") because the body's execution
 * binding registered only at the target's own declaration arm, after the
 * referring body's walk. Module-level declared bodies now register at the
 * hoist, so the forward alias (and the capture-carrying declared-name call)
 * resolves the body from the hoist on. An exported declaration's reserved
 * invocation shape is its {@code EXTERNAL_ENTRY}: a same-module reference —
 * the direct declared-name call or await, or any value-carried alias of the
 * declaration — realizes that entry over the shared body, preserving the
 * declaration's body identity and single return cell; the closed two-shape
 * guard stays in force and the reservation is never re-shaped into a source
 * call or source async start. The same resolution must hold across two
 * separate module recursive groups in either declaration order: every
 * module-level group member's execution binding and pending-capture fact
 * register at the hoist, before any group body walk, and every module-level
 * group's member cell is created at module-init top (the oracle prepares it
 * when the module init starts; both project emitters create it before any
 * module-init op executes), so an earlier group's closure captures the very
 * cell the later group's publication writes into. A nested group still
 * creates its member cells per execution.</p>
 *
 * <p>The third integration gap — the activation state of the exported-entry
 * realization: every activation of an exported async declaration aliased
 * the declaration's single canonical entry task token, so a re-entrant
 * activation (a recursive awaited activation of a forward alias or of the
 * direct declared name) replaced its starter's still-running task record
 * and the starter's await read the nested completion. Each activation now
 * runs an isolated task instance and the awaiting token resolves its own
 * instance; the declaration's reserved {@code EXTERNAL_ENTRY}/body/return
 * cell identity is preserved and the static entry token is never a
 * replaceable task slot during re-entry.</p>
 *
 * <p>The fourth integration gap — the exported declaration's
 * arity-extension adapter: an alias whose target type extends the
 * declaration's parameter list ({@code let alias: (n: int, extra: int) =&gt;
 * int = f}, docs/spec-v1.2.md, Function type compatibility) resolves the D15
 * {@code FUNCTION_ADAPT} over the declaration's pre-allocated body identity.
 * The adapter's statically resolved body source still assigned
 * {@code SOURCE_CALL}/{@code SOURCE_ASYNC} over the declaration's reserved
 * {@code EXTERNAL_ENTRY} context, so the closed two-shape guard failed the
 * lowering with E6005 and the valid program published no LuaJIT/JVM
 * artifact. The adapter invocation now keeps the declaration's reserved
 * shape: the synchronous call runs the indirect D15 protocol over the
 * published closure (the xN target-signature {@code FUNCTION_PARAMETER}
 * cells and the leading-argument projection are the adapter's own) and
 * records the entry-owned {@code EXTERNAL_RETURN} cell, and the awaited
 * adapter's nested source {@code ASYNC_START} realizes the declaration's own
 * async entry — the {@code ELIDED_BY_ADAPTER} leading-M operands, zero
 * parameter boundaries, the entry's canonical body-task token through the
 * {@code ExternalAsyncLink}, and zero nested return boundaries — while the
 * outer adapter start keeps the xN target-signature
 * {@code FUNCTION_PARAMETER} cells.</p>
 *
 * <p>The fifth reported integration gap — the arity-extension adapter over a
 * capture-carrying nested source declaration: the escaped shape {@code make}
 * declares {@code target} (reading the parameter {@code base}) and
 * {@code caller} holds the wider-typed adapter {@code wide} over
 * {@code target}; {@code caller} captures {@code target} but not
 * {@code base}. The awaited adapter's nested source start reconstructed
 * {@code target}'s factory ({@code CallCallee.Static}) at the await site, so
 * it re-resolved {@code target}'s creation-site capture of {@code base} from
 * {@code caller}'s frame (the module-level slot) and the escaped closure
 * failed at execution. The nested source start is now value-carried over the
 * loaded D15 source carrier — {@code CallCallee.Indirect} over the source
 * declaration's published closure identity — so the nested body task installs
 * the source declaration's own creation-site captured cells, with the source
 * body's own single return cell and the {@code DEAL_BODY_TASK} token owner
 * unchanged.</p>
 *
 * <p>Per case, the measured surfaces:</p>
 *
 * <ol>
 *   <li><b>The release-owned production lanes.</b> The program compiles on
 *       the LuaJIT and the JVM lane with zero E6005 (no
 *       {@code CONSTRUCT_UNLOWERED}/{@code RETAINED_ABI_DEFERRED}/
 *       {@code SHARED_EMITTER_COVERAGE}), emits exactly one project artifact,
 *       stages it, and the staged JVM artifact compiles under {@code javac
 *       --release 25 -proc:none}.</li>
 *   <li><b>The declared-body carrier battery.</b> The control-flow validator
 *       admits the unit; every declaration materializes exactly one
 *       {@code CLOSURE_NEW}; no capture-carrying declared body is invoked as
 *       a call-site factory reconstruction ({@code Static}); every
 *       value-carried invocation resolves a registered {@code LoweredBody}
 *       through the declaration's own closure identity and records the
 *       body's single return cell; an arity-extension adapter over an
 *       exported declaration keeps the reserved entry protocol (the
 *       entry-owned return cell, or the nested entry source start on the
 *       awaited path) rather than a second invocation shape.</li>
 *   <li><b>The three-consumer differential matrix.</b> The semantic oracle,
 *       the shared LuaJIT artifact, and the shared JVM artifact execute the
 *       program to the pinned success terminal and the pinned console
 *       effects and agree event-for-event.</li>
 *   <li><b>The staged production drive.</b> The staged LuaJIT chunk and the
 *       staged JVM class execute the case's own entry surface (the sync
 *       {@code main}, or the async export through the production async-entry
 *       surface) and publish the pinned effects and result.</li>
 * </ol>
 */
public class DeclaredBodyCarrierProductionTest {

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

    private static final String DEAL_JSON =
        "{\n  \"languageVersion\": \"1.2\",\n  \"moduleRoots\": [\"src\"],\n"
            + "  \"output\": \"out\",\n  \"backend\": \"luajit\"\n}\n";

    /** The release-owned production invocation of the compile path. */
    private static CompilerInvocation productionInvocation() {
        return CompilerProfileProvider.resolve(
            ReleaseConfiguration.CURRENT_RELEASE_STATE,
            ReleaseConfiguration.releaseCapabilityRegistry());
    }

    /**
     * One declared-body program: the source, the pinned console effects, the
     * driven async export (null for the sync-entry cases), the pinned result
     * atom of the differential terminal, the minimum number of
     * carrier-carried declared-body invocations the unit must record, the
     * minimum number of same-module exported-entry realizations, whether
     * the program recurses (a recursive program re-enters its own invocation
     * ops, whose trace shape the differential harness accepts as the two
     * re-entry pairing notices), the sibling module sources written into
     * {@code src/} beside the entry, and whether the three-consumer shared
     * async-entry matrix applies (it drives single-module projects: the
     * shared per-module async-entry harness runs only the driven module's
     * init walk, so a multi-module case is driven by the staged production
     * artifacts only).
     */
    private record Case(String name, String source, List<String> effects,
                        String asyncExport, String resultAtom,
                        int minCarrierInvocations, int minEntryInvocations,
                        boolean recursive, Map<String, String> extraSources,
                        boolean sharedMatrix, boolean arityExtensionAdapter) {

        Case {
            effects = List.copyOf(effects);
            extraSources = Map.copyOf(extraSources);
        }

        /** The sync-entry case: the {@code main} drive and the null terminal. */
        private Case(String name, String source, List<String> effects,
                     int minCarrierInvocations) {
            this(name, source, effects, null, "null", minCarrierInvocations, 0, false,
                Map.of(), true, false);
        }

        /** The async-entry case without a same-module exported entry. */
        private Case(String name, String source, List<String> effects,
                     String asyncExport, String resultAtom,
                     int minCarrierInvocations) {
            this(name, source, effects, asyncExport, resultAtom,
                minCarrierInvocations, 0, false, Map.of(), true, false);
        }

        /** The non-recursive async-entry case with a same-module exported entry. */
        private Case(String name, String source, List<String> effects,
                     String asyncExport, String resultAtom,
                     int minCarrierInvocations, int minEntryInvocations) {
            this(name, source, effects, asyncExport, resultAtom,
                minCarrierInvocations, minEntryInvocations, false, Map.of(), true,
                false);
        }

        /** The recursive async-entry case (no sibling modules). */
        private Case(String name, String source, List<String> effects,
                     String asyncExport, String resultAtom,
                     int minCarrierInvocations, int minEntryInvocations,
                     boolean recursive) {
            this(name, source, effects, asyncExport, resultAtom,
                minCarrierInvocations, minEntryInvocations, recursive, Map.of(), true,
                false);
        }

        /** The multi-module case (the shared async-entry matrix is inapplicable). */
        private Case(String name, String source, List<String> effects,
                     String asyncExport, String resultAtom,
                     int minCarrierInvocations, int minEntryInvocations,
                     Map<String, String> extraSources) {
            this(name, source, effects, asyncExport, resultAtom,
                minCarrierInvocations, minEntryInvocations, false, extraSources, false,
                false);
        }

        /**
         * The exported arity-extension adapter case: the program's single
         * source invocation is the D15 {@code FUNCTION_ADAPT} over an
         * exported declaration's pre-allocated body identity, so the
         * arity-extension adapter battery
         * ({@link #checkArityExtensionAdapters}) pins the adapter protocol
         * against the body's reserved {@code EXTERNAL_ENTRY}.
         */
        private Case(String name, String source, List<String> effects,
                     String asyncExport, String resultAtom,
                     boolean arityExtensionAdapter) {
            this(name, source, effects, asyncExport, resultAtom, 0,
                asyncExport == null ? 0 : 1, false, Map.of(), true,
                arityExtensionAdapter);
        }
    }

    /**
     * The acyclic sibling shape of the reported counterexample: {@code make}
     * declares {@code target} (reading the parameter {@code base}) and
     * {@code caller} (calling {@code target}), and returns {@code caller}.
     * {@code caller} captures {@code target} but not {@code base}, so the
     * static reconstruction of {@code target} at the call site reads the
     * module-level {@code base} slot and the escaped closure fails at
     * execution. The carrier invocation of {@code target} reads
     * {@code target}'s own creation-site capture of {@code base}.
     */
    private static final Case ACYCLIC_SIBLING = new Case(
        "acyclic-sibling",
        """
        import * as console from "std/console"

        export function main(): null {
          let c: () => int = make(10);
          let v: int = c();
          if (v === 10) {
            console.log("declared-body-sibling-ok");
          } else {
            console.log("declared-body-sibling-bad");
          }
          return null;
        }

        function make(base: int): () => int {
          function target(): int {
            return base;
          }
          function caller(): int {
            return target();
          }
          return caller;
        }
        """,
        List.of("declared-body-sibling-ok"), 1);

    /**
     * Two distinct escaped instances of the same shape: {@code make(10)} and
     * {@code make(20)} each publish their own {@code caller}/{@code target}
     * carriers, and each returned closure must observe its own creation's
     * {@code base}. A call-site factory reconstruction of {@code target}
     * resolves {@code base} from whichever frame is current (or from the
     * stale module-level slot after {@code make} returned), so both closures
     * would observe one value.
     */
    private static final Case TWO_ESCAPED_INSTANCES = new Case(
        "two-escaped-instances",
        """
        import * as console from "std/console"

        export function main(): null {
          let a: () => int = make(10);
          let b: () => int = make(20);
          let av: int = a();
          if (av === 10) {
            console.log("declared-body-instance-a-ok");
          } else {
            console.log("declared-body-instance-a-bad");
          }
          let bv: int = b();
          if (bv === 20) {
            console.log("declared-body-instance-b-ok");
          } else {
            console.log("declared-body-instance-b-bad");
          }
          return null;
        }

        function make(base: int): () => int {
          function target(): int {
            return base;
          }
          function caller(): int {
            return target();
          }
          return caller;
        }
        """,
        List.of("declared-body-instance-a-ok", "declared-body-instance-b-ok"), 1);

    /**
     * The awaited equivalent of the acyclic sibling shape: an async
     * declaration awaited by name. The await arm has the same restriction as
     * the synchronous call arm, so the awaited call must carry the
     * declaration's published closure value ({@code ASYNC_START} with the
     * {@code Indirect} callee) instead of reconstructing the body's factory
     * at the await site.
     */
    private static final Case ACYCLIC_SIBLING_ASYNC = new Case(
        "acyclic-sibling-async",
        """
        import * as console from "std/console"

        export async function drive(): int {
          let c: async () => int = make(10);
          let v: int = await c();
          if (v === 10) {
            console.log("declared-body-sibling-async-ok");
          } else {
            console.log("declared-body-sibling-async-bad");
          }
          return v;
        }

        export function main(): null {
          return null;
        }

        function make(base: int): async () => int {
          async function target(): int {
            return base;
          }
          async function caller(): int {
            return await target();
          }
          return caller;
        }
        """,
        List.of("declared-body-sibling-async-ok"), "drive", "int:10", 1);

    /** Two distinct escaped async instances of the awaited sibling shape. */
    private static final Case TWO_ESCAPED_INSTANCES_ASYNC = new Case(
        "two-escaped-instances-async",
        """
        import * as console from "std/console"

        export async function drive(): int {
          let a: async () => int = make(10);
          let b: async () => int = make(20);
          let av: int = await a();
          if (av === 10) {
            console.log("declared-body-async-instance-a-ok");
          } else {
            console.log("declared-body-async-instance-a-bad");
          }
          let bv: int = await b();
          if (bv === 20) {
            console.log("declared-body-async-instance-b-ok");
          } else {
            console.log("declared-body-async-instance-b-bad");
          }
          return av + bv;
        }

        export function main(): null {
          return null;
        }

        function make(base: int): async () => int {
          async function target(): int {
            return base;
          }
          async function caller(): int {
            return await target();
          }
          return caller;
        }
        """,
        List.of("declared-body-async-instance-a-ok",
            "declared-body-async-instance-b-ok"), "drive", "int:30", 1);

    /**
     * The forward module function alias: {@code g} holds a function-typed
     * alias of the later-declared, capture-carrying {@code f} and calls it.
     * Before the fix the alias load preserved {@code f}'s pre-allocated
     * identity while the registry held no registration for it yet (the
     * declaration arm registers the body before its own walk, after
     * {@code g}'s walk), so the checker-valid program failed closed with
     * E6005 {@code CONSTRUCT_UNLOWERED}. {@code f}'s own call of the
     * later-declared {@code helper} is the same forward shape through the
     * declared name.
     */
    private static final Case FORWARD_ALIAS = new Case(
        "forward-alias",
        """
        import * as console from "std/console"

        export function main(): null {
          let v: int = g();
          if (v === 5) {
            console.log("declared-body-forward-alias-ok");
          } else {
            console.log("declared-body-forward-alias-bad");
          }
          return null;
        }

        function g(): int {
          let alias: () => int = f;
          return alias();
        }

        function f(): int {
          return helper();
        }

        function helper(): int {
          return 5;
        }
        """,
        List.of("declared-body-forward-alias-ok"), 0);

    /** The awaited forward alias: the async twin of {@link #FORWARD_ALIAS}. */
    private static final Case FORWARD_ALIAS_ASYNC = new Case(
        "forward-alias-async",
        """
        import * as console from "std/console"

        export async function drive(): int {
          let v: int = await g();
          if (v === 5) {
            console.log("declared-body-forward-alias-async-ok");
          } else {
            console.log("declared-body-forward-alias-async-bad");
          }
          return v;
        }

        export function main(): null {
          return null;
        }

        async function g(): int {
          let alias: async () => int = f;
          return await alias();
        }

        async function f(): int {
          return await helper();
        }

        async function helper(): int {
          return 5;
        }
        """,
        List.of("declared-body-forward-alias-async-ok"), "drive", "int:5", 0);

    /**
     * The forward module function alias whose target is an exported
     * declaration: {@code g} — placed before {@code f} — holds and calls an
     * alias of the later-declared {@code export function f}. An exported
     * declaration's reserved invocation shape is its
     * {@code EXTERNAL_ENTRY}, so the value-carried invocation realizes that
     * entry over the shared body (the declaration's body identity and
     * single return cell) instead of re-shaping the reservation into a
     * {@code SOURCE_CALL}; the closed two-shape guard stays in force. The
     * declared parameter exercises the entry's {@code EXTERNAL_PARAMETER}
     * cells.
     */
    private static final Case FORWARD_ALIAS_EXPORTED = new Case(
        "forward-alias-exported",
        """
        import * as console from "std/console"

        export function main(): null {
          let v: int = g();
          if (v === 5) {
            console.log("declared-body-forward-alias-exported-ok");
          } else {
            console.log("declared-body-forward-alias-exported-bad");
          }
          return null;
        }

        function g(): int {
          let alias: (n: int) => int = f;
          return alias(5);
        }

        export function f(n: int): int {
          return n;
        }
        """,
        List.of("declared-body-forward-alias-exported-ok"), null, "null", 0, 1);

    /**
     * The awaited forward alias whose target is an exported async
     * declaration: the awaited twin of {@link #FORWARD_ALIAS_EXPORTED}. The
     * await realizes the export's reserved async {@code EXTERNAL_ENTRY} (the
     * caller token aliases the entry's canonical {@code DEAL_BODY_TASK}
     * token) instead of re-shaping the reservation into a
     * {@code SOURCE_ASYNC} body task.
     */
    private static final Case FORWARD_ALIAS_EXPORTED_ASYNC = new Case(
        "forward-alias-exported-async",
        """
        import * as console from "std/console"

        export async function drive(): int {
          let v: int = await g();
          if (v === 5) {
            console.log("declared-body-forward-alias-exported-async-ok");
          } else {
            console.log("declared-body-forward-alias-exported-async-bad");
          }
          return v;
        }

        export function main(): null {
          return null;
        }

        async function g(): int {
          let alias: async () => int = f;
          return await alias();
        }

        async function helper(): int {
          return 5;
        }

        export async function f(): int {
          return await helper();
        }
        """,
        List.of("declared-body-forward-alias-exported-async-ok"), "drive",
        "int:5", 0, 1);

    /**
     * The direct awaited reference to a later-declared exported async
     * declaration: {@code drive} awaits {@code f} by name. The direct
     * declared-name await realizes the export's reserved async
     * {@code EXTERNAL_ENTRY} exactly like the value-carried alias above; a
     * {@code SOURCE_ASYNC} re-shape of the reservation fails closed.
     */
    private static final Case EXPORTED_DIRECT_AWAIT_ASYNC = new Case(
        "exported-direct-await-async",
        """
        import * as console from "std/console"

        export async function drive(): int {
          let v: int = await f();
          if (v === 5) {
            console.log("declared-body-exported-direct-await-ok");
          } else {
            console.log("declared-body-exported-direct-await-bad");
          }
          return v;
        }

        export function main(): null {
          return null;
        }

        export async function f(): int {
          return 5;
        }
        """,
        List.of("declared-body-exported-direct-await-ok"), "drive", "int:5", 0, 1);

    /**
     * The recursive activation of the forwarded exported alias: the
     * required regression of the reported activation-state defect. Every
     * activation of an exported async declaration used to alias the same
     * canonical entry task token, so the nested {@code await f(n - 1)}
     * replaced the still-running caller's task record and the outer await
     * read the inner completion: {@code f(1)} and {@code drive} returned
     * {@code int:0} instead of {@code int:1}. Each activation now runs its
     * own task instance and the awaiting token resolves that instance, so
     * the nested activation completes into the nested task and the outer
     * await keeps the outer activation's declared result.
     */
    private static final Case FORWARD_ALIAS_EXPORTED_ASYNC_RECURSIVE = new Case(
        "forward-alias-exported-async-recursive",
        """
        import * as console from "std/console"

        export async function drive(): int {
          let alias: async (n: int) => int = f;
          let v: int = await alias(1);
          if (v === 1) {
            console.log("declared-body-recursive-alias-ok");
          } else {
            console.log("declared-body-recursive-alias-bad");
          }
          return v;
        }

        export function main(): null {
          return null;
        }

        export async function f(n: int): int {
          let result: int = 0;
          if (n > 0) {
            let nested: int = await f(n - 1);
            result = nested + 1;
          }
          return result;
        }
        """,
        List.of("declared-body-recursive-alias-ok"), "drive", "int:1", 0, 2, true);

    /**
     * The direct awaited recursive reference: {@code drive} awaits the
     * exported recursive declaration by name and the declaration awaits
     * itself. Both the direct declared-name await and the recursive
     * declared-name await realize the entry's reserved identity, and each
     * activation must keep its own task instance.
     */
    private static final Case EXPORTED_DIRECT_AWAIT_ASYNC_RECURSIVE = new Case(
        "exported-direct-await-async-recursive",
        """
        import * as console from "std/console"

        export async function drive(): int {
          let v: int = await f(1);
          if (v === 1) {
            console.log("declared-body-direct-recursive-ok");
          } else {
            console.log("declared-body-direct-recursive-bad");
          }
          return v;
        }

        export function main(): null {
          return null;
        }

        export async function f(n: int): int {
          let result: int = 0;
          if (n > 0) {
            let nested: int = await f(n - 1);
            result = nested + 1;
          }
          return result;
        }
        """,
        List.of("declared-body-direct-recursive-ok"), "drive", "int:1", 0, 2, true);

    /**
     * The recursive activation that reads its enclosing parameter and local
     * after the nested await: every activation of an exported async
     * declaration shares the emitted class's per-body static slots, so a
     * nested activation that leaves its own values in place corrupts the
     * enclosing activation's reads — {@code f(3)} returned the deepest
     * activation's parameter ({@code int:0}) instead of the declared
     * {@code int:12}. The entry task's body execution must save the
     * enclosing activation's private state and restore it on every path,
     * exactly like the synchronous external call and the lowered-body task
     * arms.
     */
    private static final Case EXPORTED_RECURSIVE_ENCLOSING_STATE = new Case(
        "exported-recursive-enclosing-state",
        """
        import * as console from "std/console"

        export async function drive(): int {
          let v: int = await f(3);
          if (v === 12) {
            console.log("declared-body-enclosing-state-ok");
          } else {
            console.log("declared-body-enclosing-state-bad");
          }
          return v;
        }

        export function main(): null {
          return null;
        }

        export async function f(n: int): int {
          let step: int = n;
          if (n <= 0) {
            return 0;
          }
          let nested: int = await f(n - 1);
          return nested + n + step;
        }
        """,
        List.of("declared-body-enclosing-state-ok"), "drive", "int:12", 0, 2, true);

    /**
     * The forwarded exported alias over two recursive activations: the
     * nested chain re-enters the same declared call site twice, so the
     * per-activation task instances must nest (the LIFO discipline of the
     * awaiting-token slots) and every activation must complete with its
     * own declared result — a shared static task slot returns the deepest
     * activation's value ({@code int:1} instead of {@code int:3}).
     */
    private static final Case FORWARD_ALIAS_EXPORTED_ASYNC_DEEP = new Case(
        "forward-alias-exported-async-deep",
        """
        import * as console from "std/console"

        export async function drive(): int {
          let alias: async (n: int) => int = f;
          let v: int = await alias(3);
          if (v === 3) {
            console.log("declared-body-recursive-deep-ok");
          } else {
            console.log("declared-body-recursive-deep-bad");
          }
          return v;
        }

        export function main(): null {
          return null;
        }

        export async function f(n: int): int {
          let result: int = 0;
          if (n > 0) {
            let nested: int = await f(n - 1);
            result = nested + 1;
          }
          return result;
        }
        """,
        List.of("declared-body-recursive-deep-ok"), "drive", "int:3", 0, 2, true);

    /**
     * The adapter-over-async composition: an imported async function value
     * adapted by arity extension ({@code Lib.plus} stored under the exact
     * signature, then the wider target signature) awaits through an
     * {@code ADAPTER_INNER} token over the nested external source. The
     * activation-instance resolution must follow the adapter token to the
     * nested source's instance instead of leaving the nested entry task
     * unbound; a nested source that owns no instance (a DEAL body, host
     * operation, or intrinsic) keeps its static task key.
     */
    private static final Case ADAPTER_OVER_ASYNC_EXTERNAL = new Case(
        "adapter-over-async-external",
        """
        import * as console from "std/console"
        import * as Lib from "./adapter_lib"

        export async function drive(): int {
          let src: async (x: int) => int = Lib.plus;
          let f: async (a: int, b: int) => int = src;
          let v: int = await f(1, 2);
          if (v === 2) {
            console.log("declared-body-adapter-external-ok");
          } else {
            console.log("declared-body-adapter-external-bad");
          }
          return v;
        }

        export function main(): null {
          return null;
        }
        """,
        List.of("declared-body-adapter-external-ok"), "drive", "int:2", 0, 0,
        Map.of("adapter_lib.deal", """
            export async function plus(x: int): int {
              return x + 1;
            }
            """));

    /**
     * The escaped nested-source arity-extension adapter, awaited: {@code make}
     * declares the capture-carrying async {@code target} (reading the
     * parameter {@code base}) and {@code caller} declares the wider-typed
     * {@code wide} over {@code target} and awaits {@code wide(0)}. The
     * adapter's source is a nested declared body whose creation-site capture
     * of {@code base} lives in {@code target}'s own creation instance, so the
     * nested source {@code ASYNC_START} must invoke through the loaded source
     * carrier (the {@code Indirect} callee over the source's closure identity)
     * instead of reconstructing {@code target}'s factory from
     * {@code caller}'s capture context — {@code caller} captures
     * {@code target} but not {@code base}. After {@code make(10)} returns,
     * awaiting the escaped {@code caller} must observe {@code target}'s own
     * creation-site {@code base} (10); the call-site reconstruction reads the
     * module-level {@code base} slot instead.
     */
    private static final Case ESCAPED_NESTED_SOURCE_ADAPTER_ASYNC = new Case(
        "escaped-nested-source-adapter-async",
        """
        import * as console from "std/console"

        export async function drive(): int {
          let c: async () => int = make(10);
          let v: int = await c();
          if (v === 10) {
            console.log("declared-body-nested-source-adapter-ok");
          } else {
            console.log("declared-body-nested-source-adapter-bad");
          }
          return v;
        }

        export function main(): null {
          return null;
        }

        function make(base: int): async () => int {
          async function target(): int {
            return base;
          }
          async function caller(): int {
            let wide: async (extra: int) => int = target;
            return await wide(0);
          }
          return caller;
        }
        """,
        List.of("declared-body-nested-source-adapter-ok"), "drive", "int:10", 1);

    /**
     * Two distinct escaped instances of
     * {@link #ESCAPED_NESTED_SOURCE_ADAPTER_ASYNC}: {@code make(10)} and
     * {@code make(20)} each publish their own {@code target}/{@code caller}
     * carriers, and each awaited adapter must invoke its own creation's
     * {@code target} closure. A shared reconstruction slot would let the
     * second instance's target displace the first instance's source.
     */
    private static final Case TWO_ESCAPED_NESTED_SOURCE_ADAPTERS_ASYNC = new Case(
        "two-escaped-nested-source-adapters-async",
        """
        import * as console from "std/console"

        export async function drive(): int {
          let a: async () => int = make(10);
          let b: async () => int = make(20);
          let av: int = await a();
          if (av === 10) {
            console.log("declared-body-nested-adapter-instance-a-ok");
          } else {
            console.log("declared-body-nested-adapter-instance-a-bad");
          }
          let bv: int = await b();
          if (bv === 20) {
            console.log("declared-body-nested-adapter-instance-b-ok");
          } else {
            console.log("declared-body-nested-adapter-instance-b-bad");
          }
          return av + bv;
        }

        export function main(): null {
          return null;
        }

        function make(base: int): async () => int {
          async function target(): int {
            return base;
          }
          async function caller(): int {
            let wide: async (extra: int) => int = target;
            return await wide(0);
          }
          return caller;
        }
        """,
        List.of("declared-body-nested-adapter-instance-a-ok",
            "declared-body-nested-adapter-instance-b-ok"), "drive", "int:30", 1);

    /**
     * The forward arity-extension alias of an exported declaration, sync:
     * the alias type ({@code (n: int, extra: int) => int}) extends the
     * exported declaration's parameter list, so the assignment resolves the
     * D15 arity-extension adapter and the invocation runs the adapter
     * protocol over the declaration's pre-allocated body identity. Before
     * the fix the adapter's statically resolved body source assigned
     * {@code SOURCE_CALL} over the declaration's reserved
     * {@code EXTERNAL_ENTRY} context, so the closed two-shape guard failed
     * the lowering with E6005 and the valid program published no artifact.
     * The adapter call now records the entry-owned return cell (the closed
     * adapter table admits the {@code EXTERNAL_RETURN}) while the target
     * parameters keep their {@code FUNCTION_PARAMETER} cells and the
     * adapter drops the extra target argument. The result value pins the
     * leading-argument selection: the extra argument ({@code 7}) would
     * yield {@code 70} if it displaced the leading one.
     */
    private static final Case FORWARD_ALIAS_EXPORTED_ADAPTER = new Case(
        "forward-alias-exported-adapter",
        """
        import * as console from "std/console"

        export function main(): null {
          let alias: (n: int, extra: int) => int = f;
          if (alias(1, 7) === 10) {
            console.log("declared-body-forward-alias-exported-adapter-ok");
          } else {
            console.log("declared-body-forward-alias-exported-adapter-bad");
          }
          return null;
        }

        export function f(n: int): int {
          return n * 10;
        }
        """,
        List.of("declared-body-forward-alias-exported-adapter-ok"),
        null, "null", true);

    /** The reverse declaration order of
     *  {@link #FORWARD_ALIAS_EXPORTED_ADAPTER}. */
    private static final Case FORWARD_ALIAS_EXPORTED_ADAPTER_REVERSE = new Case(
        "forward-alias-exported-adapter-reverse",
        """
        import * as console from "std/console"

        export function f(n: int): int {
          return n * 10;
        }

        export function main(): null {
          let alias: (n: int, extra: int) => int = f;
          if (alias(1, 7) === 10) {
            console.log("declared-body-forward-alias-exported-adapter-reverse-ok");
          } else {
            console.log("declared-body-forward-alias-exported-adapter-reverse-bad");
          }
          return null;
        }
        """,
        List.of("declared-body-forward-alias-exported-adapter-reverse-ok"),
        null, "null", true);

    /**
     * The awaited forward arity-extension alias of an exported async
     * declaration: the async twin of
     * {@link #FORWARD_ALIAS_EXPORTED_ADAPTER}. The awaited adapter source
     * assigned {@code SOURCE_ASYNC} over the declaration's reserved
     * {@code EXTERNAL_ENTRY} context before the fix; its nested source
     * {@code ASYNC_START} now realizes the declaration's own entry — the
     * {@code ELIDED_BY_ADAPTER} leading-argument operands, the entry's
     * canonical body-task token through the {@code ExternalAsyncLink}, and
     * zero nested return boundaries — while the outer adapter start keeps
     * the xN target-signature {@code FUNCTION_PARAMETER} cells.
     */
    private static final Case FORWARD_ALIAS_EXPORTED_ADAPTER_ASYNC = new Case(
        "forward-alias-exported-adapter-async",
        """
        import * as console from "std/console"

        export async function drive(): int {
          let alias: async (n: int, extra: int) => int = f;
          let v: int = await alias(1, 7);
          if (v === 10) {
            console.log("declared-body-forward-alias-exported-adapter-async-ok");
          } else {
            console.log("declared-body-forward-alias-exported-adapter-async-bad");
          }
          return v;
        }

        export function main(): null {
          return null;
        }

        export async function f(n: int): int {
          return n * 10;
        }
        """,
        List.of("declared-body-forward-alias-exported-adapter-async-ok"),
        "drive", "int:10", true);

    /** The reverse declaration order of
     *  {@link #FORWARD_ALIAS_EXPORTED_ADAPTER_ASYNC}. */
    private static final Case FORWARD_ALIAS_EXPORTED_ADAPTER_ASYNC_REVERSE = new Case(
        "forward-alias-exported-adapter-async-reverse",
        """
        import * as console from "std/console"

        export async function f(n: int): int {
          return n * 10;
        }

        export async function drive(): int {
          let alias: async (n: int, extra: int) => int = f;
          let v: int = await alias(1, 7);
          if (v === 10) {
            console.log(
              "declared-body-forward-alias-exported-adapter-async-reverse-ok");
          } else {
            console.log(
              "declared-body-forward-alias-exported-adapter-async-reverse-bad");
          }
          return v;
        }

        export function main(): null {
          return null;
        }
        """,
        List.of("declared-body-forward-alias-exported-adapter-async-reverse-ok"),
        "drive", "int:10", true);

    /**
     * The forward alias across two separate module recursive groups, sync:
     * {@code f}/{@code g} and {@code h}/{@code j} are two distinct
     * module-level SCCs and the earlier group's {@code f} aliases the
     * later-declared {@code h} through an ordinary function value. The
     * module-level execution registration reserves every hoisted member
     * before any group body walk, so the alias resolves the later group's
     * member regardless of the groups' declaration order. The runtime
     * counterpart is the module-init-top group cell: {@code f}'s factory
     * captures {@code h}'s member cell before the later group's publication
     * op runs, so the cell must already exist at module-init top or the
     * staged artifacts fail with a nil cell (LuaJIT {@code attempt to
     * index upvalue ... (a nil value)}, JVM {@code Cannot load from object
     * array}). The program's single source invocation is the alias.
     */
    private static final Case MODULE_GROUP_FORWARD_ALIAS = new Case(
        "module-group-forward-alias",
        """
        import * as console from "std/console"

        export function main(): null {
          if (f(1) === 42) {
            console.log("declared-body-module-group-forward-alias-ok");
          } else {
            console.log("declared-body-module-group-forward-alias-bad");
          }
          return null;
        }

        function f(n: int): int {
          let alias: (n: int) => int = h;
          if (n <= 0) {
            return alias(0);
          }
          return g(n - 1);
        }

        function g(n: int): int {
          return f(n);
        }

        function h(n: int): int {
          if (n <= 0) {
            return 42;
          }
          return j(n - 1);
        }

        function j(n: int): int {
          return h(n);
        }
        """,
        List.of("declared-body-module-group-forward-alias-ok"),
        null, "null", 1, 0, true);

    /**
     * The group-carrier production drive: the same two-group forward
     * program, but {@code main} invokes the earlier group's member
     * {@code f} through an ordinary function value, so the staged
     * artifacts invoke {@code f}'s group-published carrier. That carrier
     * captures {@code h}'s member cell at the earlier group's creation
     * time, before the later group's op runs — the module-init-top cell
     * creation is what makes the captured cell the one the later op
     * publishes into.
     */
    private static final Case MODULE_GROUP_FORWARD_CARRIER_ALIAS = new Case(
        "module-group-forward-carrier-alias",
        """
        import * as console from "std/console"

        export function main(): null {
          let drive: (n: int) => int = f;
          if (drive(1) === 42) {
            console.log("declared-body-module-group-forward-carrier-alias-ok");
          } else {
            console.log("declared-body-module-group-forward-carrier-alias-bad");
          }
          return null;
        }

        function f(n: int): int {
          let alias: (n: int) => int = h;
          if (n <= 0) {
            return alias(0);
          }
          return g(n - 1);
        }

        function g(n: int): int {
          return f(n);
        }

        function h(n: int): int {
          if (n <= 0) {
            return 42;
          }
          return j(n - 1);
        }

        function j(n: int): int {
          return h(n);
        }
        """,
        List.of("declared-body-module-group-forward-carrier-alias-ok"),
        null, "null", 2, 0, true);

    /**
     * The reverse declaration order of {@link #MODULE_GROUP_FORWARD_ALIAS}:
     * the referenced {@code h}/{@code j} group is declared before its
     * caller's {@code f}/{@code g} group, so execution registration and
     * cell identity must not depend on the groups' declaration order.
     */
    private static final Case MODULE_GROUP_REVERSE_ALIAS = new Case(
        "module-group-reverse-alias",
        """
        import * as console from "std/console"

        export function main(): null {
          if (f(1) === 42) {
            console.log("declared-body-module-group-reverse-alias-ok");
          } else {
            console.log("declared-body-module-group-reverse-alias-bad");
          }
          return null;
        }

        function h(n: int): int {
          if (n <= 0) {
            return 42;
          }
          return j(n - 1);
        }

        function j(n: int): int {
          return h(n);
        }

        function f(n: int): int {
          let alias: (n: int) => int = h;
          if (n <= 0) {
            return alias(0);
          }
          return g(n - 1);
        }

        function g(n: int): int {
          return f(n);
        }
        """,
        List.of("declared-body-module-group-reverse-alias-ok"),
        null, "null", 1, 0, true);

    /** The awaited twin of {@link #MODULE_GROUP_FORWARD_ALIAS}. */
    private static final Case MODULE_GROUP_FORWARD_ALIAS_ASYNC = new Case(
        "module-group-forward-alias-async",
        """
        import * as console from "std/console"

        export async function drive(): int {
          let v: int = await f(1);
          if (v === 42) {
            console.log("declared-body-module-group-forward-alias-async-ok");
          } else {
            console.log("declared-body-module-group-forward-alias-async-bad");
          }
          return v;
        }

        export function main(): null {
          return null;
        }

        async function f(n: int): int {
          let alias: async (n: int) => int = h;
          if (n <= 0) {
            return await alias(0);
          }
          return await g(n - 1);
        }

        async function g(n: int): int {
          return await f(n);
        }

        async function h(n: int): int {
          if (n <= 0) {
            return 42;
          }
          return await j(n - 1);
        }

        async function j(n: int): int {
          return await h(n);
        }
        """,
        List.of("declared-body-module-group-forward-alias-async-ok"),
        "drive", "int:42", 1, 0, true);

    /** The reverse declaration order of {@link #MODULE_GROUP_FORWARD_ALIAS_ASYNC}. */
    private static final Case MODULE_GROUP_REVERSE_ALIAS_ASYNC = new Case(
        "module-group-reverse-alias-async",
        """
        import * as console from "std/console"

        export async function drive(): int {
          let v: int = await f(1);
          if (v === 42) {
            console.log("declared-body-module-group-reverse-alias-async-ok");
          } else {
            console.log("declared-body-module-group-reverse-alias-async-bad");
          }
          return v;
        }

        export function main(): null {
          return null;
        }

        async function h(n: int): int {
          if (n <= 0) {
            return 42;
          }
          return await j(n - 1);
        }

        async function j(n: int): int {
          return await h(n);
        }

        async function f(n: int): int {
          let alias: async (n: int) => int = h;
          if (n <= 0) {
            return await alias(0);
          }
          return await g(n - 1);
        }

        async function g(n: int): int {
          return await f(n);
        }
        """,
        List.of("declared-body-module-group-reverse-alias-async-ok"),
        "drive", "int:42", 1, 0, true);

    /**
     * The reverse declaration order of {@link #FORWARD_ALIAS_EXPORTED}: the
     * exported target is declared before its value-carried caller, so the
     * same-module entry realization must not depend on the declaration
     * order either.
     */
    private static final Case FORWARD_ALIAS_EXPORTED_REVERSE = new Case(
        "forward-alias-exported-reverse",
        """
        import * as console from "std/console"

        export function f(n: int): int {
          return n;
        }

        export function main(): null {
          let v: int = g();
          if (v === 5) {
            console.log("declared-body-forward-alias-exported-reverse-ok");
          } else {
            console.log("declared-body-forward-alias-exported-reverse-bad");
          }
          return null;
        }

        function g(): int {
          let alias: (n: int) => int = f;
          return alias(5);
        }
        """,
        List.of("declared-body-forward-alias-exported-reverse-ok"), null, "null",
        0, 1);

    /**
     * The reverse declaration order of {@link #FORWARD_ALIAS_EXPORTED_ASYNC}:
     * the exported async target is declared before its awaited value-carried
     * caller.
     */
    private static final Case FORWARD_ALIAS_EXPORTED_ASYNC_REVERSE = new Case(
        "forward-alias-exported-async-reverse",
        """
        import * as console from "std/console"

        export async function f(): int {
          return await helper();
        }

        async function helper(): int {
          return 5;
        }

        async function g(): int {
          let alias: async () => int = f;
          return await alias();
        }

        export function main(): null {
          return null;
        }

        export async function drive(): int {
          let v: int = await g();
          if (v === 5) {
            console.log("declared-body-forward-alias-exported-async-reverse-ok");
          } else {
            console.log("declared-body-forward-alias-exported-async-reverse-bad");
          }
          return v;
        }
        """,
        List.of("declared-body-forward-alias-exported-async-reverse-ok"),
        "drive", "int:5", 0, 1);

    private static final List<Case> CASES = List.of(
        ACYCLIC_SIBLING, TWO_ESCAPED_INSTANCES, ACYCLIC_SIBLING_ASYNC,
        TWO_ESCAPED_INSTANCES_ASYNC, FORWARD_ALIAS, FORWARD_ALIAS_ASYNC,
        FORWARD_ALIAS_EXPORTED, FORWARD_ALIAS_EXPORTED_ASYNC,
        FORWARD_ALIAS_EXPORTED_REVERSE, FORWARD_ALIAS_EXPORTED_ASYNC_REVERSE,
        EXPORTED_DIRECT_AWAIT_ASYNC, FORWARD_ALIAS_EXPORTED_ASYNC_RECURSIVE,
        EXPORTED_DIRECT_AWAIT_ASYNC_RECURSIVE, EXPORTED_RECURSIVE_ENCLOSING_STATE,
        FORWARD_ALIAS_EXPORTED_ASYNC_DEEP,
        MODULE_GROUP_FORWARD_ALIAS, MODULE_GROUP_FORWARD_CARRIER_ALIAS,
        MODULE_GROUP_REVERSE_ALIAS, MODULE_GROUP_FORWARD_ALIAS_ASYNC,
        MODULE_GROUP_REVERSE_ALIAS_ASYNC,
        FORWARD_ALIAS_EXPORTED_ADAPTER, FORWARD_ALIAS_EXPORTED_ADAPTER_REVERSE,
        FORWARD_ALIAS_EXPORTED_ADAPTER_ASYNC,
        FORWARD_ALIAS_EXPORTED_ADAPTER_ASYNC_REVERSE,
        ADAPTER_OVER_ASYNC_EXTERNAL,
        ESCAPED_NESTED_SOURCE_ADAPTER_ASYNC,
        TWO_ESCAPED_NESTED_SOURCE_ADAPTERS_ASYNC);

    // =========================================================================
    // The case driver
    // =========================================================================

    private record Lowered(ExecutableLoweredProject project,
                           Map<ModuleId, StructuredBodyTable> tables,
                           Map<ModuleId, ClassFactoryRegistry> registries,
                           LoweredModuleUnit unit, StructuredBodyTable table) {
    }

    private static void runCase(Case testCase) throws Exception {
        System.out.println("-- " + testCase.name() + " --");
        Path project = Files.createTempDirectory(
            "declared-body-carrier-" + testCase.name() + "-");
        try {
            Path src = project.resolve("src");
            Files.createDirectories(src);
            Files.writeString(src.resolve("main.deal"), testCase.source(),
                StandardCharsets.UTF_8);
            for (Map.Entry<String, String> extra : testCase.extraSources().entrySet()) {
                Path sibling = src.resolve(extra.getKey());
                Files.createDirectories(sibling.getParent());
                Files.writeString(sibling, extra.getValue(), StandardCharsets.UTF_8);
            }
            Files.writeString(project.resolve("deal.json"), DEAL_JSON,
                StandardCharsets.UTF_8);
            Path entry = src.resolve("main.deal");

            // Surface 1: both release-owned production lanes.
            CompilationOrchestrator luaCompilation =
                compileLane(testCase, project, entry, "luajit");
            CompilationOrchestrator jvmCompilation =
                compileLane(testCase, project, entry, "jvm");
            Path jvmOut = project.resolve("out-jvm");
            Path jvmArtifact = jvmOut.resolve(JvmNames.classNameFor("main") + ".java");
            String probeClass = null;
            if (Files.isRegularFile(jvmArtifact)) {
                Path probe = testCase.asyncExport() == null ? null
                    : prepareProductionAsyncProbe(testCase, jvmOut, jvmCompilation);
                probeClass = probe == null ? null
                    : probe.getFileName().toString().replace(".java", "");
                compileStagedJvm(testCase, jvmOut, jvmArtifact, probe);
            }

            // Surface 2: the one lowering and the carrier battery.
            Lowered lowered = lower(testCase, luaCompilation);
            if (lowered == null) {
                return;
            }

            // Surface 3: the three-consumer differential matrix.
            if (testCase.sharedMatrix()) {
                differential(testCase, lowered);
            }

            // Surface 4: the staged production artifacts execute the case's
            // own entry surface.
            if (testCase.asyncExport() != null) {
                driveProductionAsync(testCase, project, lowered, probeClass);
            } else {
                driveProductionSync(testCase, project);
            }
        } finally {
            deleteRecursively(project);
        }
    }

    // =========================================================================
    // Surface 1: the release-owned production invocation on both lanes
    // =========================================================================

    private static CompilationOrchestrator compileLane(Case testCase, Path project,
                                                        Path entry, String lane)
            throws Exception {
        String outName = "out-" + lane;
        ProjectLocator.LocateResult located = ProjectLocator.locate(entry.toString(),
            new CliOverrides(lane, project.resolve(outName).toString()));
        check(located.context() != null, testCase.name() + " [" + lane
            + "]: the generated deal.json locates strictly");
        if (located.context() == null) {
            return null;
        }
        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            located.context(), entry, false, false, false, false, null,
            productionInvocation());
        boolean compiled = orchestrator.compile();
        List<String> errors = new ArrayList<>();
        for (CompilerDiagnostic diagnostic : orchestrator.diagnostics()) {
            if ("error".equals(diagnostic.severity())) {
                errors.add(diagnostic.code() + ": " + diagnostic.message());
            }
        }
        boolean e6005 = errors.stream().anyMatch(error -> error.startsWith("E6005"));
        check(compiled && !e6005, testCase.name() + " [" + lane
            + "]: zero E6005 over the release-owned production invocation: " + errors);
        check(orchestrator.semanticEmissionCount() == 1, testCase.name() + " [" + lane
            + "]: exactly one project artifact: semantic="
            + orchestrator.semanticEmissionCount());
        if (!compiled) {
            return orchestrator;
        }
        Path out = project.resolve(outName);
        if ("luajit".equals(lane)) {
            check(Files.isRegularFile(out.resolve("main.lua")), testCase.name()
                + " [luajit]: the project artifact is staged: " + out.resolve("main.lua"));
        } else {
            Path artifact = out.resolve(JvmNames.classNameFor("main") + ".java");
            check(Files.isRegularFile(artifact), testCase.name()
                + " [jvm]: the project artifact is staged: " + artifact);
        }
        return orchestrator;
    }

    /** The parent epic's JVM criterion on the staged artifact. */
    private static void compileStagedJvm(Case testCase, Path out, Path artifact,
                                         Path probe) throws Exception {
        Path classes = out.resolve("classes");
        Files.createDirectories(classes);
        List<String> command = new ArrayList<>(List.of("javac", "--release", "25",
            "-proc:none", "-encoding", "UTF-8", "-sourcepath", "", "-cp",
            classes + File.pathSeparator + absoluteClasspath(),
            "-d", classes.toString(), artifact.toString()));
        if (probe != null) {
            command.add(probe.toString());
        }
        JvmCompilation.Result compile = JvmCompilation.compile(command, out);
        check(compile.exitCode() == 0, testCase.name()
            + " [jvm]: the staged artifact and any async probe compile under "
            + "javac --release 25 -proc:none: " + compile.stdout() + compile.stderr());
    }

    private static Path prepareProductionAsyncProbe(Case testCase, Path out,
            CompilationOrchestrator compilation) throws Exception {
        ExecutableLoweredProject produced = compilation == null ? null
            : compilation.producedProject();
        Long entryId = produced == null ? null
            : asyncEntryIdOf(produced.modules().get(produced.entryModule()),
                testCase.asyncExport());
        check(entryId != null, testCase.name()
            + " [jvm]: the produced project records the async export's entry");
        if (entryId == null) {
            return null;
        }
        String probeClass = "DeclaredBodyAsyncProbe_"
            + UUID.randomUUID().toString().replace("-", "");
        Path probe = out.resolve(probeClass + ".java");
        Files.writeString(probe,
            "public final class " + probeClass + " {\n"
                + "  public static void main(String[] args) {\n"
                + "    Main.dealMain();\n"
                + "    Object result = Main.ae" + entryId
                + "(\"-\", true, new Object[]{});\n"
                + "    System.out.println(\"DECLARED_BODY_ASYNC_RESULT:\" + result);\n"
                + "    System.exit(0);\n"
                + "  }\n"
                + "}\n", StandardCharsets.UTF_8);
        return probe;
    }

    // =========================================================================
    // Surface 2: the one lowering and the declared-body carrier battery
    // =========================================================================

    private static Lowered lower(Case testCase, CompilationOrchestrator orchestrator) {
        check(orchestrator != null, testCase.name()
            + ": the oracle closure locates strictly");
        if (orchestrator == null) {
            return null;
        }
        CheckedProjectBuildResult built = orchestrator.checkedProject();
        RequirementManifestResult manifests = orchestrator.requirementManifests();
        check(built != null && built.input() != null && built.index() != null
                && !built.hasErrors() && manifests != null
                && manifests.manifests() != null,
            testCase.name() + ": the oracle closure compiles: "
                + (built == null ? "no checked project" : built.diagnostics()));
        if (built == null || built.input() == null || built.index() == null
                || built.hasErrors() || manifests == null
                || manifests.manifests() == null) {
            return null;
        }
        ExecutableLoweredProject produced = orchestrator.producedProject();
        check(produced != null, testCase.name()
            + ": the production compile exposes its read-only lowered project");
        if (produced == null) {
            return null;
        }
        boolean noDeclarationSeeds = orchestrator.hostDeclarationSurface().moduleIds().isEmpty();
        check(noDeclarationSeeds,
            testCase.name() + ": the fixture has no host declaration or FFI seeds");
        if (!noDeclarationSeeds) {
            return null;
        }
        SemanticLowerer.ProjectLoweringResult result = SemanticLowerer.lowerProject(
            orchestrator.invocation(), built.input(), built.index(),
            manifests.manifests(), orchestrator.hostDeclarationSurface(),
            Map.of(), Map.of(),
            BuiltinErrorDeclaration.synthesized(built.input().modules().stream()
                .filter(module -> module.moduleId().equals(built.input().entryModule()))
                .findFirst().orElseThrow().ast().span()),
            List.of(IntrinsicKind.INT_CONVERT, IntrinsicKind.NUMBER_CONVERT,
                IntrinsicKind.BYTES_NEW),
            Set.of());
        check(result.project() != null && result.diagnostics().isEmpty(),
            testCase.name() + ": the declared-body program lowers with zero "
                + "diagnostics (no CONSTRUCT_UNLOWERED/CONTROL_EXIT): "
                + result.diagnostics());
        if (result.project() == null) {
            return null;
        }
        boolean sameClosure = produced.entryModule().equals(result.project().entryModule())
            && produced.modules().keySet().equals(result.project().modules().keySet());
        check(sameClosure,
            testCase.name() + ": independent lowering preserves the production closure");
        boolean deterministic = sameClosure;
        for (Map.Entry<ModuleId, LoweredModuleUnit> module : produced.modules().entrySet()) {
            LoweredModuleUnit independent = result.project().modules().get(module.getKey());
            boolean sameUnit = independent != null
                && SemanticIrDumper.dumpModuleText(module.getValue())
                    .equals(SemanticIrDumper.dumpModuleText(independent));
            check(sameUnit, testCase.name() + ": independent lowering is deterministic for "
                + module.getKey());
            deterministic &= sameUnit;
        }
        if (!deterministic) {
            return null;
        }
        ModuleId entryModule = produced.entryModule();
        LoweredModuleUnit unit = produced.modules().get(entryModule);
        StructuredBodyTable table = result.tables().get(entryModule);
        check(unit != null && table != null, testCase.name()
            + ": the lowering produced the entry unit and its block table");
        if (unit == null || table == null) {
            return null;
        }
        checkDeclaredBodyCarriers(testCase, unit, table);
        return new Lowered(produced, result.tables(), result.registries(),
            unit, table);
    }

    private record Inspection(Map<OpId, SemanticOp> ops,
                              Map<OpId, SemanticOp> nestedStarts,
                              Map<BindingGeneration, KindPayload.BindingAllocPayload> allocations,
                              Map<FunctionId, List<FunctionAllocationIdentity>> identities) {

        private static Inspection of(LoweredModuleUnit unit) {
            Map<OpId, SemanticOp> ops = new LinkedHashMap<>();
            Map<OpId, SemanticOp> nestedStarts = new LinkedHashMap<>();
            Map<BindingGeneration, KindPayload.BindingAllocPayload> allocations =
                new LinkedHashMap<>();
            Map<FunctionId, List<FunctionAllocationIdentity>> identities =
                new LinkedHashMap<>();
            for (SemanticOp op : unit.ops()) {
                ops.putIfAbsent(op.opId(), op);
                if (op.kind() == SemanticOpKind.ASYNC_START
                        && op.origin().parentOpId() != null) {
                    nestedStarts.put(op.origin().parentOpId(), op);
                }
                if (op.payload() instanceof KindPayload.BindingAllocPayload alloc) {
                    allocations.putIfAbsent(
                        new BindingGeneration(alloc.binding(), alloc.generation()), alloc);
                }
            }
            for (Map.Entry<FunctionAllocationIdentity, FunctionExecutionBinding> entry
                    : unit.functionBindings().entrySet()) {
                if (entry.getValue() instanceof FunctionExecutionBinding.LoweredBody body) {
                    identities.computeIfAbsent(body.functionId(), key -> new ArrayList<>())
                        .add(entry.getKey());
                }
            }
            return new Inspection(ops, nestedStarts, allocations, identities);
        }
    }

    private static void checkDeclaredBodyCarriers(Case testCase, LoweredModuleUnit unit,
                                                  StructuredBodyTable table) {
        Inspection inspection = Inspection.of(unit);
        Optional<CompilerDiagnostic> admission = ControlFlowValidator.validate(unit, table);
        check(admission.isEmpty(), testCase.name()
            + ": the control-flow validator admits the unit: " + admission);

        Map<FunctionId, Integer> closureNews = new LinkedHashMap<>();
        Map<Long, SemanticOp> closureByResult = new LinkedHashMap<>();
        Map<Long, FunctionId> groupMemberIdentities = new LinkedHashMap<>();
        Map<FunctionId, OpId> returnBoundaries = new LinkedHashMap<>();
        Map<String, SemanticOp> recordedEntries = new LinkedHashMap<>();
        Map<FunctionId, List<KindPayload.ReturnPayload>> returnsByFunction =
            new LinkedHashMap<>();
        Set<FunctionId> entryDelegated = new java.util.LinkedHashSet<>();
        for (SemanticOp op : unit.ops()) {
            if (op.kind() == SemanticOpKind.CLOSURE_NEW
                    && op.payload() instanceof KindPayload.ClosureNewPayload closure) {
                closureNews.merge(closure.function(), 1, Integer::sum);
                if (op.result() instanceof ValueId value) {
                    closureByResult.put(value.id(), op);
                }
            }
            if (op.kind() == SemanticOpKind.RECURSIVE_GROUP_INIT
                    && op.payload()
                        instanceof KindPayload.RecursiveGroupInitPayload group) {
                // The group-published member identities: a module-level
                // group member has no CLOSURE_NEW of its own — the
                // RECURSIVE_GROUP_INIT publication writes the member's
                // pre-assigned allocation identity into the member cell,
                // and the registration of that identity is the member's
                // own LoweredBody.
                for (FunctionId member : group.functions()) {
                    for (FunctionAllocationIdentity identity
                            : inspection.identities().getOrDefault(member, List.of())) {
                        groupMemberIdentities.put(identity.id(), member);
                    }
                }
            }
            if (op.kind() == SemanticOpKind.RETURN
                    && op.payload() instanceof KindPayload.ReturnPayload returned) {
                returnBoundaries.put(returned.function(),
                    returned.returnBoundaryOpId());
                returnsByFunction.computeIfAbsent(returned.function(),
                    key -> new ArrayList<>()).add(returned);
            }
            if (op.kind() == SemanticOpKind.EXTERNAL_ENTRY
                    && op.payload()
                        instanceof KindPayload.ExternalEntryPayload entryPayload) {
                recordedEntries.put(entryPayload.exportName(), op);
            }
            if (op.payload() instanceof KindPayload.EntryInvokePayload entry) {
                // The runtime entry delegation is emitted by the entry
                // surface, not by a source reference: its static invocation
                // of the entry body reconstructs the module-level
                // declaration's single-incarnation captures exactly (the
                // entry op runs when every declaration cell is initialized).
                entryDelegated.add(entry.mainFunction());
            }
        }
        for (Map.Entry<FunctionId, Integer> entry : closureNews.entrySet()) {
            check(entry.getValue() == 1, testCase.name() + ": exactly one CLOSURE_NEW "
                + "materializes function " + entry.getKey() + " (exactly-once closure "
                + "creation); got " + entry.getValue());
            check(unit.functions().containsKey(entry.getKey()), testCase.name()
                + ": function " + entry.getKey() + " carries its LoweredFunction record");
        }

        int carrierInvocations = 0;
        int staticCaptureInvocations = 0;
        int moduleEntryInvocations = 0;
        for (SemanticOp op : unit.ops()) {
            KindPayload.CallCallee callee = invocationCallee(op);
            if (callee == null) {
                continue;
            }
            if (callee instanceof KindPayload.CallCallee.Static stat
                    && stat.binding()
                        instanceof FunctionExecutionBinding.ExternalFunction external
                    && external.executionOwner() == ExternalExecutionOwner.SHARED_BODY
                    && external.moduleId().equals(unit.moduleId())) {
                // The same-module exported-entry realization: the
                // invocation records the declaration's reserved
                // EXTERNAL_ENTRY identity and its single return cell, with
                // the body's RETURN naming that entry — the reservation is
                // never re-shaped into a source call.
                moduleEntryInvocations++;
                SemanticOp entry = recordedEntries.get(external.exportName());
                check(entry != null, testCase.name() + ": the invocation "
                    + op.opId() + " realizes the recorded EXTERNAL_ENTRY of export '"
                    + external.exportName() + "'");
                if (entry != null) {
                    KindPayload.ExternalEntryPayload entryPayload =
                        (KindPayload.ExternalEntryPayload) entry.payload();
                    check(entryPayload.async()
                            == (op.kind() == SemanticOpKind.ASYNC_START),
                        testCase.name() + ": the invocation " + op.opId()
                            + " realizes the " + (entryPayload.async() ? "async" : "sync")
                            + " entry " + entry.opId());
                    OpId recordedEntryRef = invocationEntryRef(op);
                    check(entry.opId().equals(recordedEntryRef), testCase.name()
                        + ": the invocation " + op.opId()
                        + " records the declaration's reserved entry " + entry.opId()
                        + "; got " + recordedEntryRef);
                    check(unit.functions().containsKey(entryPayload.function()),
                        testCase.name() + ": the entry " + entry.opId()
                            + " names the declaration's lowered function "
                            + entryPayload.function());
                    List<KindPayload.ReturnPayload> returns =
                        returnsByFunction.getOrDefault(entryPayload.function(),
                            List.of());
                    check(!returns.isEmpty(), testCase.name() + ": the declaration "
                        + entryPayload.function() + " carries its own RETURN");
                    for (KindPayload.ReturnPayload returned : returns) {
                        check(entry.opId().equals(returned.enclosingInvocationOpId()),
                            testCase.name() + ": the RETURN of " + entryPayload.function()
                                + " names the reserved entry " + entry.opId()
                                + "; got " + returned.enclosingInvocationOpId());
                        check(entryPayload.returnBoundaryOpId()
                                .equals(returned.returnBoundaryOpId()),
                            testCase.name() + ": the RETURN of "
                                + entryPayload.function() + " runs the entry's single"
                                + " return cell " + entryPayload.returnBoundaryOpId()
                                + "; got " + returned.returnBoundaryOpId());
                    }
                }
                continue;
            }
            FunctionExecutionBinding resolved = switch (callee) {
                case KindPayload.CallCallee.Static stat -> stat.binding();
                case KindPayload.CallCallee.Indirect indirect ->
                    unit.functionBindings().get(
                        new FunctionAllocationIdentity(indirect.callee().id()));
                case KindPayload.CallCallee.Dynamic ignored -> null;
            };
            if (!(resolved instanceof FunctionExecutionBinding.LoweredBody body)) {
                continue;
            }
            LoweredFunction target = unit.functions().get(body.functionId());
            check(target != null, testCase.name() + ": the invocation " + op.opId()
                + " resolves the body's LoweredFunction record");
            // Every declared-body invocation records the declaring body's own
            // single return cell — the pre-allocated identity is resolvable
            // from the hoist on, so a forward reference names it too.
            OpId bodyBoundary = returnBoundaries.get(body.functionId());
            OpId invocationBoundary = invocationReturnBoundary(op);
            check(bodyBoundary != null && bodyBoundary.equals(invocationBoundary),
                testCase.name() + ": the invocation " + op.opId()
                    + " records the declaring body's own return cell " + bodyBoundary
                    + "; got " + invocationBoundary);
            if (callee instanceof KindPayload.CallCallee.Indirect indirect) {
                carrierInvocations++;
                FunctionId memberIdentity =
                    groupMemberIdentities.get(indirect.callee().id());
                check(closureByResult.containsKey(indirect.callee().id())
                        || memberIdentity != null, testCase.name()
                    + ": the carrier invocation " + op.opId() + " reads the "
                    + "declaration's own closure identity " + indirect.callee()
                    + " (the published CLOSURE_NEW result or the group-published"
                    + " member identity); got a value outside the unit's closure"
                    + " identities");
            } else if (target != null && !target.captures().isEmpty()
                    && !entryDelegated.contains(body.functionId())
                    && !moduleStableCaptures(unit, target, inspection)) {
                staticCaptureInvocations++;
                System.err.println("FAIL: " + testCase.name() + ": the invocation "
                    + op.opId() + " of capture-carrying function " + body.functionId()
                    + " is a factory reconstruction at the call site; the "
                    + "value-carried carrier is required");
            }
        }
        check(staticCaptureInvocations == 0, testCase.name()
            + ": no capture-carrying declared body is invoked as a call-site factory "
            + "reconstruction; got " + staticCaptureInvocations);
        check(carrierInvocations >= testCase.minCarrierInvocations(), testCase.name()
            + ": the unit records at least " + testCase.minCarrierInvocations()
            + " carrier-carried declared-body invocation(s); got " + carrierInvocations);
        check(moduleEntryInvocations >= testCase.minEntryInvocations(), testCase.name()
            + ": the unit records at least " + testCase.minEntryInvocations()
            + " same-module exported-entry realization(s); got "
            + moduleEntryInvocations);
        checkAdapterOverAsyncNestedSources(testCase, unit, returnBoundaries, inspection);
        if (testCase.arityExtensionAdapter()) {
            checkArityExtensionAdapters(testCase, unit, inspection);
        }
    }

    /**
     * The adapter-over-async nested source start (the ISSUE-0766 gap): when
     * the adapter's statically fixed source is a declared body (the
     * non-entry shape), the nested source {@code ASYNC_START} is
     * value-carried — {@code CallCallee.Indirect} over the source
     * declaration's published closure identity — and keeps the source body's
     * own single return cell and the {@code DEAL_BODY_TASK} canonical token
     * owner. A captured nested source declaration is never reconstructed as
     * a call-site factory; the exported-entry, external, host, and intrinsic
     * source resolutions keep their static/entry shapes.
     */
    private static void checkAdapterOverAsyncNestedSources(Case testCase,
            LoweredModuleUnit unit, Map<FunctionId, OpId> returnBoundaries,
            Inspection inspection) {
        for (SemanticOp outer : unit.ops()) {
            if (outer.kind() != SemanticOpKind.ASYNC_START
                    || !(outer.payload() instanceof KindPayload.AsyncStartPayload started)
                    || started.parameterBoundaryMode() != ParameterBoundaryMode.RUN) {
                continue;
            }
            FunctionExecutionBinding outerBinding = switch (started.callee()) {
                case KindPayload.CallCallee.Static stat -> stat.binding();
                case KindPayload.CallCallee.Indirect indirect ->
                    unit.functionBindings().get(
                        new FunctionAllocationIdentity(indirect.callee().id()));
                case KindPayload.CallCallee.Dynamic ignored -> null;
            };
            if (!(outerBinding instanceof FunctionExecutionBinding.AdapterBinding)) {
                continue;
            }
            SemanticOp nested = inspection.nestedStarts().get(outer.opId());
            check(nested != null, testCase.name()
                + ": the adapter-over-async start " + outer.opId()
                + " carries its nested source ASYNC_START");
            if (nested == null) {
                continue;
            }
            KindPayload.AsyncStartPayload inner =
                (KindPayload.AsyncStartPayload) nested.payload();
            FunctionExecutionBinding nestedBinding = switch (inner.callee()) {
                case KindPayload.CallCallee.Static stat -> stat.binding();
                case KindPayload.CallCallee.Indirect indirect ->
                    unit.functionBindings().get(
                        new FunctionAllocationIdentity(indirect.callee().id()));
                case KindPayload.CallCallee.Dynamic ignored -> null;
            };
            if (!(nestedBinding instanceof FunctionExecutionBinding.LoweredBody body)) {
                // The exported-entry, external, host, and intrinsic source
                // resolutions keep their own static/entry shapes.
                continue;
            }
            check(inner.callee() instanceof KindPayload.CallCallee.Indirect,
                testCase.name() + ": the nested source start " + nested.opId()
                    + " of the adapter-over-async task " + outer.opId()
                    + " is value-carried over the declared source's published"
                    + " closure identity (never a call-site factory"
                    + " reconstruction); got " + inner.callee());
            check(inner.parameterBoundaryMode()
                        == ParameterBoundaryMode.ELIDED_BY_ADAPTER
                    && inner.parameterBoundaryOpIds().isEmpty(), testCase.name()
                + ": the nested source start " + nested.opId()
                + " runs zero parameter boundaries (ELIDED_BY_ADAPTER); got "
                + inner.parameterBoundaryMode() + " with "
                + inner.parameterBoundaryOpIds().size() + " cell(s)");
            OpId bodyCell = returnBoundaries.get(body.functionId());
            check(bodyCell != null && bodyCell.equals(inner.returnBoundaryOpId()),
                testCase.name() + ": the nested source start " + nested.opId()
                    + " records the declared source body's own return cell "
                    + bodyCell + "; got " + inner.returnBoundaryOpId());
            check(nested.result() instanceof AsyncTokenId.Canonical token
                    && token.owner() == AsyncTokenOwner.DEAL_BODY_TASK,
                testCase.name() + ": the nested source start " + nested.opId()
                    + " keeps the DEAL_BODY_TASK canonical token owner; got "
                    + nested.result());
        }
    }

    /**
     * The exported arity-extension adapter battery (the ISSUE-0758
     * review-cycle-3 gap): an alias whose target type extends the source's
     * parameter list resolves the D15 {@code FUNCTION_ADAPT} over the
     * exported declaration's pre-allocated body identity, so the invocation
     * must not re-shape the declaration's reserved {@code EXTERNAL_ENTRY}.
     * The synchronous adapter call keeps the indirect protocol over the
     * published closure, runs its xN target-signature
     * {@code FUNCTION_PARAMETER} cells, and records the entry-owned return
     * cell; the awaited adapter's nested source {@code ASYNC_START}
     * realizes the entry protocol — the {@code ELIDED_BY_ADAPTER}
     * leading-M-argument operands, zero parameter boundaries, zero nested
     * return boundaries, and the {@code ExternalAsyncLink} naming the
     * entry's canonical body-task token — and the outer
     * {@code ADAPTER_INNER} alias token resolves through the nested source
     * start's own activation-bound token. Exactly one adapter invocation
     * exists (the program's single source invocation), and the declaration's
     * body RETURN keeps naming the entry and its single return cell.
     */
    private static void checkArityExtensionAdapters(Case testCase,
                                                    LoweredModuleUnit unit,
                                                    Inspection inspection) {
        Map<OpId, String> entryReturnCells = new LinkedHashMap<>();
        for (SemanticOp op : unit.ops()) {
            if (op.kind() == SemanticOpKind.EXTERNAL_ENTRY
                    && op.payload()
                        instanceof KindPayload.ExternalEntryPayload entry) {
                entryReturnCells.put(entry.returnBoundaryOpId(), entry.exportName());
            }
        }
        int syncAdapters = 0;
        int asyncAdapters = 0;
        for (SemanticOp op : unit.ops()) {
            if (op.kind() == SemanticOpKind.CALL
                    && op.payload() instanceof KindPayload.CallPayload call) {
                FunctionExecutionBinding.AdapterBinding adapter =
                    arityExtensionAdapter(call.callee());
                if (adapter == null) {
                    continue;
                }
                syncAdapters++;
                check(call.mode() == CallMode.INDIRECT && call.externalEntryRef() == null
                        && call.bodyBlock() == null, testCase.name()
                    + ": the sync arity-extension adapter call " + op.opId()
                    + " runs the indirect protocol over the exported declaration's"
                    + " published closure; got mode " + call.mode() + " and entry ref "
                    + call.externalEntryRef());
                checkTargetParameterCells(testCase, unit, op,
                    call.parameterBoundaryOpIds(), adapter, inspection);
                SemanticOp cell = inspection.ops().get(call.returnBoundaryOpId());
                check(cell != null && cell.kind() == SemanticOpKind.BOUNDARY
                        && cell.payload()
                            instanceof KindPayload.BoundaryPayload boundary
                        && boundary.kind() == BoundaryKind.EXTERNAL_RETURN
                        && entryReturnCells.containsKey(call.returnBoundaryOpId()),
                    testCase.name() + ": the sync arity-extension adapter call "
                        + op.opId() + " records the entry-owned EXTERNAL_RETURN cell"
                        + " of the exported declaration " + entryReturnCells
                        + "; got " + call.returnBoundaryOpId());
            } else if (op.kind() == SemanticOpKind.ASYNC_START
                    && op.payload() instanceof KindPayload.AsyncStartPayload started
                    && started.parameterBoundaryMode() == ParameterBoundaryMode.RUN) {
                FunctionExecutionBinding.AdapterBinding adapter =
                    arityExtensionAdapter(started.callee());
                if (adapter == null) {
                    continue;
                }
                asyncAdapters++;
                check(started.returnBoundaryOpId() == null, testCase.name()
                    + ": the outer adapter-over-async start " + op.opId()
                    + " runs zero return boundaries of its own");
                checkTargetParameterCells(testCase, unit, op,
                    started.parameterBoundaryOpIds(), adapter, inspection);
                checkNestedEntrySource(testCase, unit, op, adapter, inspection);
            }
        }
        check(syncAdapters + asyncAdapters == 1, testCase.name()
            + ": exactly one arity-extension adapter invocation; got "
            + syncAdapters + " sync and " + asyncAdapters + " async");
    }

    /**
     * The awaited arity-extension adapter's nested source start: the
     * exported declaration's own entry protocol, bound to the nested start's
     * activation token.
     */
    private static void checkNestedEntrySource(Case testCase, LoweredModuleUnit unit,
                                                SemanticOp outer,
                                                FunctionExecutionBinding.AdapterBinding adapter,
                                                Inspection inspection) {
        SemanticOp nested = inspection.nestedStarts().get(outer.opId());
        check(nested != null, testCase.name()
            + ": the adapter-over-async start " + outer.opId()
            + " carries its nested source ASYNC_START");
        if (nested == null) {
            return;
        }
        KindPayload.AsyncStartPayload inner =
            (KindPayload.AsyncStartPayload) nested.payload();
        check(inner.source() == AsyncStartSource.EXTERNAL
                && inner.parameterBoundaryMode() == ParameterBoundaryMode.ELIDED_BY_ADAPTER
                && inner.parameterBoundaryOpIds().isEmpty()
                && inner.returnBoundaryOpId() == null, testCase.name()
            + ": the nested source start " + nested.opId() + " realizes the entry"
            + " protocol (EXTERNAL, ELIDED_BY_ADAPTER, zero parameter boundaries, zero"
            + " return boundaries); got source " + inner.source() + ", mode "
            + inner.parameterBoundaryMode() + ", "
            + inner.parameterBoundaryOpIds().size() + " parameter cell(s), return "
            + inner.returnBoundaryOpId());
        int leading = adapter.sourceSignature().paramTypes().size();
        check(nested.operands().equals(outer.operands().subList(0, leading)),
            testCase.name() + ": the nested source start " + nested.opId()
                + " passes exactly the leading " + leading
                + " argument operand(s); got " + nested.operands() + " of "
                + outer.operands());
        FunctionExecutionBinding nestedBinding = inner.callee()
                instanceof KindPayload.CallCallee.Static stat ? stat.binding() : null;
        check(nestedBinding instanceof FunctionExecutionBinding.ExternalFunction external
                && external.executionOwner() == ExternalExecutionOwner.SHARED_BODY
                && external.moduleId().equals(unit.moduleId()), testCase.name()
            + ": the nested source start's callee is the module's own SHARED_BODY"
            + " external record; got " + nestedBinding);
        ExternalAsyncLink link = inner.externalAsyncLink();
        check(link != null && link.calleeModuleId().equals(unit.moduleId())
                && link.calleeTokenId() instanceof AsyncTokenId.Canonical canonical
                && canonical.owner() == AsyncTokenOwner.DEAL_BODY_TASK, testCase.name()
            + ": the nested source start's ExternalAsyncLink names the entry's"
            + " canonical body-task token in the same module; got " + link);
        SemanticOp entry = link == null ? null
            : inspection.ops().get(new OpId(unit.moduleId(), link.calleeTokenId().tokenId()));
        check(entry != null && entry.kind() == SemanticOpKind.EXTERNAL_ENTRY
                && entry.payload() instanceof KindPayload.ExternalEntryPayload payload
                && payload.async(), testCase.name()
            + ": the nested source link's token names the exported declaration's"
            + " reserved async EXTERNAL_ENTRY; got " + entry);
        check(outer.result() instanceof AsyncTokenId outerToken
                && outerToken instanceof AsyncTokenId.Alias outerAlias
                && outerAlias.linkKind() == AsyncLinkKind.ADAPTER_INNER
                && outerAlias.referent().equals(nested.result()), testCase.name()
            + ": the outer ADAPTER_INNER alias " + outer.result()
            + " resolves the nested source start's own activation-bound token; got "
            + nested.result());
    }

    /** The adapter's xN target-signature {@code FUNCTION_PARAMETER} cells. */
    private static void checkTargetParameterCells(Case testCase, LoweredModuleUnit unit,
            SemanticOp invocation, List<OpId> boundaryIds,
            FunctionExecutionBinding.AdapterBinding adapter, Inspection inspection) {
        List<RuntimeDescriptor> targetParams =
            adapter.targetSignature().paramTypes();
        check(boundaryIds.size() == targetParams.size(), testCase.name()
            + ": the adapter invocation " + invocation.opId() + " runs one"
            + " FUNCTION_PARAMETER cell per target parameter; got " + boundaryIds.size());
        for (int i = 0; i < boundaryIds.size(); i++) {
            SemanticOp boundary = inspection.ops().get(boundaryIds.get(i));
            check(boundary != null && boundary.kind() == SemanticOpKind.BOUNDARY
                    && boundary.payload() instanceof KindPayload.BoundaryPayload payload
                    && payload.kind() == BoundaryKind.FUNCTION_PARAMETER
                    && i < targetParams.size()
                    && targetParams.get(i).equals(payload.descriptor()),
                testCase.name() + ": the adapter invocation " + invocation.opId()
                    + " boundary " + boundaryIds.get(i) + " is target parameter "
                    + (i + 1) + "'s FUNCTION_PARAMETER cell");
        }
    }

    /** The arity-extension adapter binding of one callee, or null. */
    private static FunctionExecutionBinding.AdapterBinding arityExtensionAdapter(
            KindPayload.CallCallee callee) {
        if (!(callee instanceof KindPayload.CallCallee.Static stat)
                || !(stat.binding()
                    instanceof FunctionExecutionBinding.AdapterBinding adapter)) {
            return null;
        }
        return adapter.sourceSignature().paramTypes().size()
                < adapter.targetSignature().paramTypes().size()
            ? adapter : null;
    }

    /**
     * Whether every capture of one declaration resolves to a module-init
     * incarnation: a module-level declaration's single-incarnation cells, so
     * a call-site factory reconstruction of that body passes exactly the
     * cells its creation site passed. A capture allocated in a body block is
     * per-invocation and only the published carrier names the right one.
     */
    private static boolean moduleStableCaptures(LoweredModuleUnit unit,
                                                LoweredFunction function,
                                                Inspection inspection) {
        for (BindingGeneration capture : function.captures()) {
            KindPayload.BindingAllocPayload alloc = inspection.allocations().get(capture);
            if (alloc == null || !alloc.scope().equals(unit.moduleInit().initBlock())) {
                return false;
            }
        }
        return true;
    }

    /** The callee reference of one invocation op (CALL or ASYNC_START). */
    private static KindPayload.CallCallee invocationCallee(SemanticOp invocation) {
        return switch (invocation.kind()) {
            case CALL -> invocation.payload()
                instanceof KindPayload.CallPayload call ? call.callee() : null;
            case ASYNC_START -> invocation.payload()
                instanceof KindPayload.AsyncStartPayload start ? start.callee() : null;
            default -> null;
        };
    }

    /** The recorded return boundary of one invocation op (CALL or ASYNC_START). */
    private static OpId invocationReturnBoundary(SemanticOp invocation) {
        return switch (invocation.kind()) {
            case CALL -> invocation.payload()
                instanceof KindPayload.CallPayload call ? call.returnBoundaryOpId() : null;
            case ASYNC_START -> invocation.payload()
                instanceof KindPayload.AsyncStartPayload start
                    ? start.returnBoundaryOpId() : null;
            default -> null;
        };
    }

    /** The recorded callee entry op of one invocation op (CALL or ASYNC_START). */
    private static OpId invocationEntryRef(SemanticOp invocation) {
        return switch (invocation.kind()) {
            case CALL -> invocation.payload()
                instanceof KindPayload.CallPayload call ? call.externalEntryRef() : null;
            case ASYNC_START -> invocation.payload()
                instanceof KindPayload.AsyncStartPayload start
                    && start.externalAsyncLink() != null
                    ? new OpId(start.externalAsyncLink().calleeModuleId(),
                        start.externalAsyncLink().calleeTokenId().tokenId())
                    : null;
            default -> null;
        };
    }

    // =========================================================================
    // Surface 3: the three-consumer differential matrix
    // =========================================================================

    private static void differential(Case testCase, Lowered lowered) throws Exception {
        Path workspace = Files.createTempDirectory("declared-body-matrix-"
            + testCase.name() + "-");
        try {
            SemanticDifferentialHarness.Expectation expectation =
                SemanticDifferentialHarness.Expectation.success(testCase.name(),
                    testCase.effects(), testCase.resultAtom());
            SemanticDifferentialHarness.Verdict verdict = testCase.asyncExport() == null
                ? SemanticDifferentialHarness.runProject(lowered.project(),
                    lowered.tables(), lowered.registries(), expectation, workspace)
                : SemanticDifferentialHarness.runAsyncEntry(lowered.project(),
                    lowered.tables(), testCase.asyncExport(), List.of(), expectation,
                    workspace, null);
            check(verdict.runs().size() == 3, testCase.name()
                + ": the differential matrix produced the three consumers: "
                + verdict.failures());
            List<String> unexplained = new ArrayList<>();
            for (String failure : verdict.failures()) {
                if (!testCase.recursive() || !isRecursivePairingNotice(failure)) {
                    unexplained.add(failure);
                }
            }
            check(unexplained.isEmpty(), testCase.name() + ": the oracle and both "
                + "shared artifacts agree event-for-event with the pinned terminal and "
                + "effects: " + unexplained);
            check(testCase.recursive() || verdict.pass(), testCase.name()
                + ": the matrix carries no accepted failure: " + verdict.failures());
            for (SemanticRuntimeModel.ConsumerRun run : verdict.runs()) {
                check(run.terminal()
                        instanceof SemanticRuntimeModel.Terminal.Success success
                        && testCase.resultAtom().equals(success.resultAtom()),
                    testCase.name() + " [" + run.consumer()
                        + "]: the pinned success terminal " + testCase.resultAtom()
                        + "; got " + run.terminal());
                List<String> texts = new ArrayList<>();
                for (SemanticRuntimeModel.EffectEvent effect : run.effects()) {
                    texts.add(effect.text());
                }
                check(texts.equals(testCase.effects()), testCase.name() + " ["
                    + run.consumer() + "]: the pinned console effects "
                    + testCase.effects() + "; got " + texts);
            }
        } finally {
            deleteRecursively(workspace);
        }
    }

    /**
     * The differential harness's two re-entry pairing notices: a recursively
     * re-entered op starts again before its previous terminal and therefore
     * also terminates without a matching open START. Every other failure is a
     * real disagreement; the terminal, effect, and cross-consumer trace
     * equality checks stay in force for the recursive cases.
     */
    private static boolean isRecursivePairingNotice(String failure) {
        return failure.contains("starts again before its previous terminal")
            || failure.contains("terminates without an open START");
    }

    // =========================================================================
    // Surface 4: the staged production drives
    // =========================================================================

    /**
     * The staged production artifacts execute the case's async export on both
     * release-owned lanes through their production async-entry surface and
     * publish the pinned console effects and the pinned result.
     */
    private static void driveProductionAsync(Case testCase, Path project, Lowered lowered,
                                             String probeClass) throws Exception {
        String marker = "DECLARED_BODY_ASYNC_RESULT:";
        String expectedResult = productionResultText(testCase.resultAtom());
        List<String> expectedStdout = new ArrayList<>(testCase.effects());
        expectedStdout.add(marker + expectedResult);

        Path luaOut = project.resolve("out-luajit");
        Path chunk = luaOut.resolve("main.lua");
        check(Files.isRegularFile(chunk), testCase.name()
            + " [luajit]: the staged chunk carries the async case: " + chunk);
        if (Files.isRegularFile(chunk)) {
            Path probe = luaOut.resolve("__declared_body_async_probe.lua");
            Files.writeString(probe,
                "dofile(\"" + luaString(chunk.toAbsolutePath().normalize().toString())
                    + "\")\n"
                    + "local __ok, __err = __dealMain()\n"
                    + "if not __ok then error(__err, 0) end\n"
                    + "local __okA, __resA = pcall(__asyncEntries[\""
                    + luaString(lowered.project().entryModule().path() + "#"
                        + testCase.asyncExport())
                    + "\"], \"-\", true)\n"
                    + "if not __okA then error(__resA, 0) end\n"
                    + "print(\"" + marker + "\" .. tostring(__resA))\n",
                StandardCharsets.UTF_8);
            ProcessBuilder builder = new ProcessBuilder("luajit",
                probe.getFileName().toString());
            builder.directory(luaOut.toFile());
            builder.environment().put("DEAL_DEFER_MAIN", "1");
            ProcessOutput run = runProcess(builder);
            check(run.exit() == 0 && run.stderr().isEmpty(), testCase.name()
                + " [luajit]: the staged chunk executes the async case: " + run.describe());
            check(run.stdout().equals(expectedStdout), testCase.name()
                + " [luajit]: the staged chunk publishes the pinned effects and result "
                + expectedStdout + "; got " + run.stdout());
        }

        Path jvmOut = project.resolve("out-jvm");
        Path artifact = jvmOut.resolve(JvmNames.classNameFor("main") + ".java");
        check(Files.isRegularFile(artifact), testCase.name()
            + " [jvm]: the staged artifact carries the async case: " + artifact);
        Long entryId = asyncEntryIdOf(lowered.unit(), testCase.asyncExport());
        check(entryId != null, testCase.name() + " [jvm]: the unit records the async "
            + "export's EXTERNAL_ENTRY over the production entry surface");
        Path classes = jvmOut.resolve("classes");
        boolean artifactCompiled = Files.isRegularFile(
            classes.resolve(JvmNames.classNameFor("main") + ".class"));
        check(artifactCompiled, testCase.name()
            + " [jvm]: the staged artifact is compiled for the async drive");
        boolean probeCompiled = probeClass != null
            && Files.isRegularFile(classes.resolve(probeClass + ".class"));
        check(probeCompiled, testCase.name() + " [jvm]: the async probe compiled"
            + " together with the staged artifact");
        if (Files.isRegularFile(artifact) && entryId != null && artifactCompiled
                && probeCompiled) {
            ProcessBuilder java = new ProcessBuilder("java", "-cp",
                classes + File.pathSeparator + absoluteClasspath(), probeClass);
            java.directory(jvmOut.toFile());
            ProcessOutput run = runProcess(java);
            check(run.exit() == 0 && run.stderr().isEmpty(), testCase.name()
                + " [jvm]: the staged artifact executes the async case: "
                + run.describe());
            check(run.stdout().equals(expectedStdout), testCase.name()
                + " [jvm]: the staged artifact publishes the pinned effects and "
                + "result " + expectedStdout + "; got " + run.stdout());
        }
    }

    /**
     * The staged production artifacts execute the case's sync entry on both
     * release-owned lanes through their production entry surface and publish
     * the pinned console effects.
     */
    private static void driveProductionSync(Case testCase, Path project)
            throws Exception {
        List<String> expectedStdout = testCase.effects();

        Path luaOut = project.resolve("out-luajit");
        Path chunk = luaOut.resolve("main.lua");
        check(Files.isRegularFile(chunk), testCase.name()
            + " [luajit]: the staged chunk carries the sync case: " + chunk);
        if (Files.isRegularFile(chunk)) {
            ProcessBuilder builder = new ProcessBuilder("luajit", "main.lua");
            builder.directory(luaOut.toFile());
            ProcessOutput run = runProcess(builder);
            check(run.exit() == 0 && run.stderr().isEmpty(), testCase.name()
                + " [luajit]: the staged chunk executes the sync case: "
                + run.describe());
            check(run.stdout().equals(expectedStdout), testCase.name()
                + " [luajit]: the staged chunk publishes the pinned effects "
                + expectedStdout + "; got " + run.stdout());
        }

        String className = JvmNames.classNameFor("main");
        Path jvmOut = project.resolve("out-jvm");
        Path classes = jvmOut.resolve("classes");
        check(Files.isRegularFile(classes.resolve(className + ".class")),
            testCase.name() + " [jvm]: the staged artifact is compiled for the "
                + "sync drive: " + classes.resolve(className + ".class"));
        if (Files.isRegularFile(classes.resolve(className + ".class"))) {
            ProcessBuilder java = new ProcessBuilder("java", "-cp",
                classes + File.pathSeparator + absoluteClasspath(), className);
            java.directory(jvmOut.toFile());
            ProcessOutput run = runProcess(java);
            check(run.exit() == 0 && run.stderr().isEmpty(), testCase.name()
                + " [jvm]: the staged artifact executes the sync case: "
                + run.describe());
            check(run.stdout().equals(expectedStdout), testCase.name()
                + " [jvm]: the staged artifact publishes the pinned effects "
                + expectedStdout + "; got " + run.stdout());
        }
    }

    /** The printed form of the pinned result atom (descriptor prefix removed). */
    private static String productionResultText(String atom) {
        int colon = atom.indexOf(':');
        return colon < 0 ? atom : atom.substring(colon + 1);
    }

    /** The async EXTERNAL_ENTRY op id of the entry module's export, or null. */
    private static Long asyncEntryIdOf(LoweredModuleUnit unit, String export) {
        for (SemanticOp op : unit.ops()) {
            if (op.kind() == SemanticOpKind.EXTERNAL_ENTRY
                    && op.payload() instanceof KindPayload.ExternalEntryPayload payload
                    && payload.async() && export.equals(payload.exportName())) {
                return op.opId().id();
            }
        }
        return null;
    }

    /** The captured outcome of one real-toolchain process. */
    private record ProcessOutput(int exit, List<String> stdout, List<String> stderr) {

        String describe() {
            return "exit=" + exit + " stdout=" + stdout + " stderr=" + stderr;
        }
    }

    private static ProcessOutput runProcess(ProcessBuilder builder) throws Exception {
        BoundedProcess.BoundedResult captured = BoundedProcess.runCaptured(
            builder.command(), builder.directory() == null ? null
                : builder.directory().toPath(), builder.environment());
        if (captured.stdoutTruncated() || captured.stderrTruncated()) {
            throw new AssertionError("runtime process output was truncated: "
                + builder.command());
        }
        return new ProcessOutput(captured.exitCode(), splitLines(captured.stdout()),
            splitLines(captured.stderr()));
    }

    private static List<String> splitLines(String text) {
        List<String> lines = new ArrayList<>();
        for (String line : text.split("\n", -1)) {
            if (!line.isEmpty()) {
                lines.add(line);
            }
        }
        return lines;
    }

    private static String luaString(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // =========================================================================
    // Helpers
    // =========================================================================

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

    private static void deleteRecursively(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(path, FileVisitOption.values())) {
            walk.sorted(Comparator.reverseOrder()).forEach(candidate -> {
                try {
                    Files.deleteIfExists(candidate);
                } catch (Exception ignored) {
                    // best effort
                }
            });
        } catch (Exception ignored) {
            // best effort
        }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== Declared-Body Carrier Production Test (ISSUE-0757) ===\n");
        for (Case testCase : CASES) {
            runCase(testCase);
        }
        System.out.println("\nPassed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }
}
