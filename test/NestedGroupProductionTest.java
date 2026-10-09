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
import deal.semantic.ir.BlockId;
import deal.semantic.ir.ClassFactoryRegistry;
import deal.semantic.ir.ExecutableLoweredProject;
import deal.semantic.ir.FunctionAllocationIdentity;
import deal.semantic.ir.FunctionExecutionBinding;
import deal.semantic.ir.FunctionId;
import deal.semantic.ir.IntrinsicKind;
import deal.semantic.ir.KindPayload;
import deal.semantic.ir.LoweredFunction;
import deal.semantic.ir.LoweredModuleUnit;
import deal.semantic.ir.ModuleId;
import deal.semantic.ir.OpId;
import deal.semantic.ir.SemanticOp;
import deal.semantic.ir.SemanticOpKind;
import deal.semantic.ir.SourceOriginKind;
import deal.semantic.ir.StructuredBodyTable;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * ISSUE-0731 acceptance remediation: the nested recursive-group member's body
 * invocation identity and return cell ({@code
 * residual-carrier-shapes-production-realization} D3 and the local and async
 * declared-function contract; {@code
 * luajit-jvm-single-lowering-production-cutover} C10/K12; {@code
 * dispatched-corpus-production-realization} R4 item 2).
 *
 * <p>A recursive group declared inside a function body is a nested group:
 * every member owns its body block, its {@code FunctionId}, its single return
 * boundary, its reserved invocation identity, and its resolvable execution
 * binding, all materialized before any member body walks. The reported defect
 * was that {@code lowerGroup} reserved and pushed a {@code FunctionContext}
 * only for module-level members, so an explicit {@code return} in a nested
 * member lowered against the <em>enclosing</em> function's context: the RETURN
 * op named the enclosing function while its block belonged to the member, and
 * {@code ControlFlowValidator.checkReturnTarget} rejected it with E6005
 * {@code CONTROL_EXIT}. A sibling direct call also failed closed because the
 * member had no registered context.</p>
 *
 * <p>The review's follow-up defect was that each member's
 * {@code LoweredBody} execution binding was registered only after every member
 * body walk, so a sibling reference through an ordinary function value (a
 * {@code let} alias, then a call of that alias) still failed closed with E6005
 * {@code CONSTRUCT_UNLOWERED} inside the member walk. The member's execution
 * binding is now registered in the preallocation phase — exactly once per
 * pre-assigned member identity, before the first member body walk — and the
 * member's {@code LoweredFunction} record is finalized the moment its own walk
 * ends, so an alias invocation resolved while the target's capture list is
 * still open is classified value-carried (the alias holds the member's
 * published closure, whose creation-site captures travel with the carrier).
 * Every member of a size&gt;=2 reference SCC references a sibling, so a
 * member's final capture list is never empty and the pending classification
 * is the exact one.</p>
 *
 * <p>Three measured surfaces per case:</p>
 *
 * <ol>
 *   <li><b>The release-owned production invocation.</b> The program compiles
 *       on the LuaJIT lane and the JVM lane with zero E6005 (no
 *       {@code CONSTRUCT_UNLOWERED}/{@code RETAINED_ABI_DEFERRED}/
 *       {@code SHARED_EMITTER_COVERAGE}), emits exactly one project artifact,
 *       and stages it; the staged JVM artifact compiles under {@code javac
 *       --release 25 -proc:none}.</li>
 *   <li><b>The lowered unit's member ownership.</b> Exactly one
 *       {@code RECURSIVE_GROUP_INIT} publishes the members; every member is a
 *       lowered function of the unit with its own body block; every RETURN op
 *       in a member names that member (never the enclosing function) and
 *       names exactly one return-boundary op; the enclosing function carries
 *       exactly one RETURN op. The member's reserved invocation identity is
 *       materialized by the group op when the group is never called, and by
 *       the member's first CALL when it is.</li>
 *   <li><b>The three-consumer differential matrix.</b> The semantic oracle,
 *       the shared LuaJIT artifact, and the shared JVM artifact execute the
 *       program to the pinned success terminal and the pinned console effects
 *       and agree event-for-event. A recursively re-entered call op produces
 *       the harness's two known re-entry pairing notices on each consumer (the
 *       accepted recursion shape of the direct-recursion fixtures); every
 *       other failure fails the case.</li>
 *
 *   <li><b>The production async drive.</b> The <em>staged</em> LuaJIT chunk
 *       and the <em>staged</em> JVM class additionally execute the async
 *       export through their production async-entry surface (no trace
 *       instrumentation) and publish the pinned console effects and the
 *       pinned result — the production emission's value-carried async start
 *       end to end, not only the shared trace emission.</li>
 *   <li><b>The called sibling alias.</b> A member invoked through an ordinary
 *       function-value alias (nested, capture-carrying, later-declared target,
 *       and module-level variants) compiles on both production lanes, its
 *       invocation identity is materialized by the alias's value-carried call,
 *       and the three consumers agree with the pinned terminal and effects.</li>
 *
 *   <li><b>The escaped nested group.</b> {@code make} returns one member and
 *       the entry creates two independent groups; every nested member call is
 *       the value-carried (indirect) callee shape resolving the member's
 *       published carrier — a factory reconstruction at the call site would
 *       re-resolve the target's captures from the calling member's frame and
 *       let the first creation observe the second creation's cells — and the
 *       two returned groups execute to their own pinned results on the oracle
 *       and both production artifacts.</li>
 *
 *   <li><b>The declared-parameter failure origin.</b> A nested-member call
 *       with a contextual table member argument fails at the callee's
 *       declared parameter annotation on the oracle and both artifacts — the
 *       value-carried routing keeps the declared-callee contextual-argument
 *       deferral; the argument read's own origin is not admissible.</li>
 *
 *   <li><b>The async escaped nested group.</b> The same two-instance shape
 *       with async members awaited through the published carriers: every
 *       awaited member invocation is the value-carried (indirect) callee
 *       shape, each task keeps the member's own return cell, and the two
 *       creations complete to their own pinned values on the oracle and both
 *       artifacts. The sibling-alias variant awaits the member
 *       through an ordinary function-value alias ({@code let sibling = g}):
 *       the tracked alias preserves the member identity but owns no lowering
 *       context, so the resolved {@code LoweredBody} must still take the
 *       carrier — a static reconstruction at the await site re-resolves the
 *       target's captures from the calling member's frame. Both declaration
 *       orders are covered: the alias target's capture list is still pending
 *       when the awaiting member walks first, and already final when the
 *       target walks first.</li>
 *
 *   <li><b>The captured member cells.</b> A closure inside a member makes
 *       the member's parameter and local shared cells; the resumed
 *       invocation reads their own module-level cell references after the
 *       awaited recursion (declared sibling name and typed alias alike).
 *       The async state save/restore covers the shared-cell references the
 *       member body itself allocates — saving and restoring the reference,
 *       never the cell's contents — so the two creations keep their own
 *       incarnations while in-place commits through a shared cell stay
 *       visible.</li>
 *
 *   <li><b>The synchronous post-recursion member state.</b> A nested member
 *       that reads its own parameter and local after a recursive sibling
 *       call keeps its invocation's private state when the nested
 *       invocation returns. The synchronous save/restore is unconditional
 *       (exactly like the async body task's): the escaped entry — a dynamic
 *       dispatch of the returned carrier — never sets the active marker, so
 *       a marker-gated restore would let the deepest re-entry's slots
 *       answer the resumed invocation. The declared-name and typed-alias
 *       shapes (the latter with captured, shared-cell parameters and
 *       locals) execute to their own pinned results on the oracle and both
 *       staged production artifacts.</li>
 * </ol>
 */
public class NestedGroupProductionTest {

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
     * One nested-group program: its name, its source, the pinned console
     * effect texts, whether its execution recursively re-enters a call op,
     * whether each member (in declaration order) is invoked by a call site,
     * whether the member returns are the implicit trailing null returns
     * (rather than explicit source returns), and whether the source invokes
     * a member through an ordinary function-value alias. A failure pin turns
     * the differential drive into the pinned three-consumer failure
     * projection (the terminal code and the exact origin computed from the
     * source anchor); an async drive invokes the named async export through
     * the async-entry matrix instead of the init-only project matrix; a
     * staged sync drive additionally executes the staged production
     * artifacts' sync entry on both release-owned lanes.
     */
    private record Case(String name, String source, List<String> effects,
                        boolean recursive, List<Boolean> memberCalled,
                        boolean implicitReturns, boolean aliasInvocation,
                        FailurePin failure, AsyncDrive asyncDrive,
                        String resultAtom, boolean stagedSync) {

        /** The init-only success case with the pinned null terminal. */
        private Case(String name, String source, List<String> effects,
                     boolean recursive, List<Boolean> memberCalled,
                     boolean implicitReturns, boolean aliasInvocation) {
            this(name, source, effects, recursive, memberCalled, implicitReturns,
                aliasInvocation, null, null, "null", false);
        }

        /**
         * The case shape of the earlier coordinates (the failure pin or the
         * async drive set explicitly), with the staged sync drive off.
         */
        private Case(String name, String source, List<String> effects,
                     boolean recursive, List<Boolean> memberCalled,
                     boolean implicitReturns, boolean aliasInvocation,
                     FailurePin failure, AsyncDrive asyncDrive,
                     String resultAtom) {
            this(name, source, effects, recursive, memberCalled, implicitReturns,
                aliasInvocation, failure, asyncDrive, resultAtom, false);
        }

        /** The staged sync drive case: the pinned null terminal. */
        private Case(String name, String source, List<String> effects,
                     boolean recursive, List<Boolean> memberCalled,
                     boolean implicitReturns, boolean aliasInvocation,
                     boolean stagedSync) {
            this(name, source, effects, recursive, memberCalled, implicitReturns,
                aliasInvocation, null, null, "null", stagedSync);
        }
    }

    /**
     * The pinned failure projection: the terminal code and the source anchor
     * whose end is the exact origin (the declared parameter annotation the
     * {@code FUNCTION_PARAMETER} cell reports).
     */
    private record FailurePin(String code, String anchor) {
    }

    /** The async-entry drive: the invoked async export of the case program. */
    private record AsyncDrive(String exportName) {
    }

    /** The called, mutually recursive, explicit-return group (a computed value). */
    private static final Case CALLED_EXPLICIT = new Case(
        "called-explicit",
        """
        import * as console from "std/console"

        export function main(): null {
          function f(n: int): int {
            if (n <= 0) {
              return 0;
            }
            return n + g(n - 1);
          }
          function g(n: int): int {
            if (n <= 0) {
              return 0;
            }
            return n + f(n - 1);
          }
          if (f(4) === 10) {
            console.log("nested-group-called-explicit-ok");
          } else {
            console.log("nested-group-called-explicit-bad");
          }
          return null;
        }
        """,
        List.of("nested-group-called-explicit-ok"), true, List.of(true, true), false,
        false);

    /** The called, mutually recursive, implicit-null-return group. */
    private static final Case CALLED_IMPLICIT = new Case(
        "called-implicit",
        """
        import * as console from "std/console"

        export function main(): null {
          function f(n: int): null {
            if (n > 0) {
              g(n - 1);
            }
          }
          function g(n: int): null {
            if (n > 1) {
              f(n - 1);
            }
          }
          f(4);
          console.log("nested-group-called-implicit-ok");
          return null;
        }
        """,
        List.of("nested-group-called-implicit-ok"), true, List.of(true, true), true,
        false);

    /** The called group whose SCC edge is a value reference, not a call. */
    private static final Case CALLED_VALUE_REFERENCE = new Case(
        "called-value-reference",
        """
        import * as console from "std/console"

        export function main(): null {
          function f(n: int): int {
            if (n <= 0) {
              return 0;
            }
            return n + g(n - 1);
          }
          function g(n: int): int {
            let fRef: (n: int) => int = f;
            return n;
          }
          if (f(4) === 7) {
            console.log("nested-group-called-value-reference-ok");
          } else {
            console.log("nested-group-called-value-reference-bad");
          }
          return null;
        }
        """,
        List.of("nested-group-called-value-reference-ok"), false, List.of(true, true),
        false, false);

    /**
     * The review's called sibling-alias seed: {@code g} invokes {@code f}
     * through an ordinary function-value alias ({@code let fRef = f}), so
     * {@code f}'s reserved invocation identity is materialized by the
     * alias's value-carried call inside {@code g}'s body. Before the fix,
     * the member's execution binding was registered only after every
     * member body walk, so this checker-valid shape failed closed with
     * E6005 {@code CONSTRUCT_UNLOWERED}.
     */
    private static final Case CALLED_ALIAS = new Case(
        "called-alias",
        """
        import * as console from "std/console"

        export function main(): null {
          function f(n: int): int {
            if (n <= 0) {
              return 0;
            }
            return n + g(n - 1);
          }
          function g(n: int): int {
            let fRef: (n: int) => int = f;
            return fRef(n);
          }
          if (f(4) === 10) {
            console.log("nested-group-alias-ok");
          } else {
            console.log("nested-group-alias-bad");
          }
          return null;
        }
        """,
        List.of("nested-group-alias-ok"), true, List.of(true, true), false, true);

    /**
     * The capture-carrying sibling alias: the aliased member reads an
     * enclosing local, so the alias invocation must stay value-carried —
     * the closure carrier holds the creation-site captured cell (the
     * invocation itself proves the value the alias holds reaches the
     * member body; the captured-ok terminal is only produced when the
     * captured {@code base} is observed).
     */
    private static final Case CALLED_ALIAS_CAPTURED = new Case(
        "called-alias-captured",
        """
        import * as console from "std/console"

        export function main(): null {
          let base: int = 10;
          function f(n: int): int {
            if (n <= 0) {
              return base;
            }
            return g(n - 1);
          }
          function g(n: int): int {
            let fRef: (n: int) => int = f;
            return fRef(n);
          }
          if (f(3) === 10) {
            console.log("nested-group-alias-captured-ok");
          } else {
            console.log("nested-group-alias-captured-bad");
          }
          return null;
        }
        """,
        List.of("nested-group-alias-captured-ok"), true, List.of(true, true), false,
        true);

    /**
     * The sibling alias whose target is declared <em>after</em> the
     * calling member: the target's capture list is not final while the
     * caller's body walk is open, so the pending classification must
     * already be value-carried (the alias holds the member's published
     * closure).
     */
    private static final Case CALLED_ALIAS_LATER = new Case(
        "called-alias-later",
        """
        import * as console from "std/console"

        export function main(): null {
          function g(n: int): int {
            let fRef: (n: int) => int = f;
            return fRef(n);
          }
          function f(n: int): int {
            if (n <= 0) {
              return 0;
            }
            return n + g(n - 1);
          }
          if (f(4) === 10) {
            console.log("nested-group-alias-later-ok");
          } else {
            console.log("nested-group-alias-later-bad");
          }
          return null;
        }
        """,
        List.of("nested-group-alias-later-ok"), true, List.of(true, true), false, true);

    /**
     * The module-level sibling alias: the same SCC-through-a-value shape at
     * module scope, where the group lowers at module-init top before the
     * member bodies walk. The module-level member had the identical
     * missing-registration defect before the fix.
     */
    private static final Case MODULE_GROUP_ALIAS = new Case(
        "module-group-alias",
        """
        import * as console from "std/console"

        function f(n: int): int {
          if (n <= 0) {
            return 0;
          }
          return n + g(n - 1);
        }
        function g(n: int): int {
          let fRef: (n: int) => int = f;
          return fRef(n);
        }

        export function main(): null {
          if (f(4) === 10) {
            console.log("module-group-alias-ok");
          } else {
            console.log("module-group-alias-bad");
          }
          return null;
        }
        """,
        List.of("module-group-alias-ok"), true, List.of(true, true), false, true);

    /**
     * The escaped nested group: {@code make} returns one member closure,
     * and {@code main} creates <em>two</em> independent groups (base 10
     * and base 20) and invokes each returned closure. The sibling calls
     * inside the members must be value-carried: the group publication
     * writes each member's closure carrier — holding that creation's
     * captured cells (the sibling cell and {@code base}) — into the member
     * cell, and a factory reconstruction at the call site re-resolves the
     * target's captures from the <em>calling</em> member's frame, which
     * cannot name the first creation's cells once a second creation
     * overwrote the shared cell slots. Both returned groups must observe
     * their own {@code base}.
     */
    private static final Case ESCAPED_TWO_GROUPS = new Case(
        "escaped-two-groups",
        """
        import * as console from "std/console"

        export function main(): null {
          let a: (n: int) => int = make(10);
          let b: (n: int) => int = make(20);
          if (a(3) === 10) {
            console.log("escaped-group-a-ok");
          } else {
            console.log("escaped-group-a-bad");
          }
          if (b(3) === 20) {
            console.log("escaped-group-b-ok");
          } else {
            console.log("escaped-group-b-bad");
          }
          return null;
        }

        function make(base: int): (n: int) => int {
          function f(n: int): int {
            if (n <= 0) {
              return base;
            }
            return g(n - 1);
          }
          function g(n: int): int {
            return f(n);
          }
          return f;
        }
        """,
        List.of("escaped-group-a-ok", "escaped-group-b-ok"), true,
        List.of(true, true), false, false);

    /**
     * The synchronous escaped group's post-recursion state (the review's
     * cycle-3 seed): the same two-instance {@code make}/{@code f}/{@code g}
     * shape, but the awaiting member reads its own parameter and a local
     * <em>after</em> the recursive sibling call returns ({@code g(n - 1);
     * return base + n + m;}). The recursive re-entry writes the member's
     * module-level slots, so the enclosing invocation's private state must
     * be restored when the nested invocation returns. Before the fix the
     * synchronous restore was gated on the {@code __bodyActive} marker, and
     * the escaped entry (the dynamic dispatch of the returned carrier in
     * {@code main}) never set that marker: the first re-entry treated the
     * already-live member as inactive, so {@code f(3)} resumed with the
     * deepest invocation's {@code n}/{@code m} and both creations printed
     * their bad effects (15/25 instead of 17/27). The staged production
     * artifacts execute the sync entry on both lanes, and the differential
     * matrix proves oracle agreement.
     */
    private static final Case ESCAPED_TWO_GROUPS_RECURSION = new Case(
        "escaped-two-groups-recursion",
        """
        import * as console from "std/console"

        export function main(): null {
          let a: (n: int) => int = make(10);
          let b: (n: int) => int = make(20);
          if (a(3) === 17) {
            console.log("escaped-group-recursion-a-ok");
          } else {
            console.log("escaped-group-recursion-a-bad");
          }
          if (b(3) === 27) {
            console.log("escaped-group-recursion-b-ok");
          } else {
            console.log("escaped-group-recursion-b-bad");
          }
          return null;
        }

        function make(base: int): (n: int) => int {
          function f(n: int): int {
            if (n <= 0) {
              return base;
            }
            let m: int = n + 1;
            g(n - 1);
            return base + n + m;
          }
          function g(n: int): int {
            return f(n);
          }
          return f;
        }
        """,
        List.of("escaped-group-recursion-a-ok", "escaped-group-recursion-b-ok"),
        true, List.of(true, true), false, false, true);

    /**
     * The awaited sibling-alias variant of the post-recursion state
     * regression: {@code f} calls the later-declared sibling through an
     * ordinary typed alias ({@code let sibling: (n: int) => int = g}), and
     * a closure makes {@code f}'s parameter {@code n} and local {@code m}
     * shared cells while the member still reads them directly after the
     * recursion — the shared-cell incarnation references the member body
     * itself publishes through the module-level slots are saved and
     * restored alongside its value slots. The value-carried alias resolves
     * the published carrier (the target's capture list is still pending
     * when {@code f} walks), so the invocation keeps the creation's own
     * cells on top of the restored private state.
     */
    private static final Case ESCAPED_TWO_GROUPS_ALIAS_RECURSION = new Case(
        "escaped-two-groups-alias-recursion",
        """
        import * as console from "std/console"

        export function main(): null {
          let a: (n: int) => int = make(10);
          let b: (n: int) => int = make(20);
          if (a(3) === 17) {
            console.log("escaped-group-alias-recursion-a-ok");
          } else {
            console.log("escaped-group-alias-recursion-a-bad");
          }
          if (b(3) === 27) {
            console.log("escaped-group-alias-recursion-b-ok");
          } else {
            console.log("escaped-group-alias-recursion-b-bad");
          }
          return null;
        }

        function make(base: int): (n: int) => int {
          function f(n: int): int {
            if (n <= 0) {
              return base;
            }
            let m: int = n + 1;
            let cell: () => int = function(): int { return n + m; };
            let sibling: (n: int) => int = g;
            sibling(n - 1);
            return base + n + m;
          }
          function g(n: int): int {
            return f(n);
          }
          return f;
        }
        """,
        List.of("escaped-group-alias-recursion-a-ok",
            "escaped-group-alias-recursion-b-ok"),
        true, List.of(true, true), false, true, true);

    /** The review's seed: a never-called nested group with explicit null returns. */
    private static final Case NEVER_CALLED_EXPLICIT_NULL = new Case(
        "never-called-explicit-null",
        """
        export function main(): null {
          function f(): null {
            let gRef: () => null = g;
            return null;
          }
          function g(): null {
            let fRef: () => null = f;
            return null;
          }
          return null;
        }
        """,
        List.of(), false, List.of(false, false), false, false);

    /** A never-called nested group with explicit non-null returns. */
    private static final Case NEVER_CALLED_EXPLICIT_INT = new Case(
        "never-called-explicit-int",
        """
        export function main(): null {
          function f(): int {
            let gRef: () => int = g;
            return 0;
          }
          function g(): int {
            let fRef: () => int = f;
            return 1;
          }
          return null;
        }
        """,
        List.of(), false, List.of(false, false), false, false);

    /** A never-called nested group with implicit trailing null returns. */
    private static final Case NEVER_CALLED_IMPLICIT = new Case(
        "never-called-implicit",
        """
        export function main(): null {
          function f(): null {
            let gRef: () => null = g;
          }
          function g(): null {
            let fRef: () => null = f;
          }
          return null;
        }
        """,
        List.of(), false, List.of(false, false), true, false);

    /** A called nested group whose members capture an enclosing local. */
    private static final Case CAPTURED_CALLED = new Case(
        "captured-called",
        """
        import * as console from "std/console"

        export function main(): null {
          let base: int = 10;
          function f(n: int): int {
            if (n <= 0) {
              return base;
            }
            return g(n - 1);
          }
          function g(n: int): int {
            return f(n);
          }
          if (f(3) === 10) {
            console.log("nested-group-captured-ok");
          } else {
            console.log("nested-group-captured-bad");
          }
          return null;
        }
        """,
        List.of("nested-group-captured-ok"), true, List.of(true, true), false,
        false);

    /** A called nested group declared inside a nested function declaration. */
    private static final Case NESTED_INSIDE_FUNCTION = new Case(
        "nested-inside-function",
        """
        import * as console from "std/console"

        export function main(): null {
          function outer(): int {
            function f(n: int): int {
              if (n <= 0) {
                return 0;
              }
              return g(n - 1);
            }
            function g(n: int): int {
              return f(n);
            }
            return f(2);
          }
          if (outer() === 0) {
            console.log("nested-group-inside-function-ok");
          } else {
            console.log("nested-group-inside-function-bad");
          }
          return null;
        }
        """,
        List.of("nested-group-inside-function-ok"), true, List.of(true, true), false,
        false);

    /**
     * The declared parameter-origin regression (the review's cycle-3 seed):
     * a synchronous nested-group identifier call with a contextual table
     * member argument. The argument's kind check must run at the callee's
     * declared parameter cell — origin {@code f}'s {@code int} annotation —
     * exactly like every other declared-callee call, never at the argument
     * read: routing the nested-member call through the value-carried carrier
     * must keep {@code lowerCallArgument}'s deferral. Before the fix the
     * read composed its own {@code CONTEXTUAL_TABLE_READ} cell and reported
     * the read's origin instead of the annotation on all three consumers.
     */
    private static final Case PARAM_ORIGIN_CONTEXTUAL = new Case(
        "param-origin-contextual",
        """
        import * as console from "std/console"

        export function main(): null {
          function f(n: int): int {
            let gRef: (n: int) => int = g;
            return n;
          }
          function g(n: int): int {
            let fRef: (n: int) => int = f;
            return n;
          }
          let t: table = { x: "wrong" };
          f(t.x);
          return null;
        }
        """,
        List.of(), false, List.of(true, false), false, false,
        new FailurePin("E8001", "function f(n: "), null, "null");

    /**
     * The async escaped nested group (the review's cycle-3 async seed):
     * {@code make} declares mutually recursive async members, {@code f}
     * returns the creation's {@code base} and otherwise awaits sibling
     * {@code g}, {@code g} awaits {@code f}, and the exported async driver
     * creates two independent groups and awaits each returned closure. The
     * awaited sibling calls must be value-carried: a static factory
     * reconstruction at the await site re-resolves the target's captures
     * from the calling member's frame, so the first creation observed the
     * second creation's cells ({@code a-bad}, {@code b-ok} before the fix);
     * the published carrier holds its own creation's cells.
     */
    private static final Case ASYNC_ESCAPED_TWO_GROUPS = new Case(
        "async-escaped-two-groups",
        """
        import * as console from "std/console"

        export async function drive(): int {
          let a: async (n: int) => int = make(10);
          let b: async (n: int) => int = make(20);
          let av: int = await a(3);
          if (av === 10) {
            console.log("async-escaped-group-a-ok");
          } else {
            console.log("async-escaped-group-a-bad");
          }
          let bv: int = await b(3);
          if (bv === 20) {
            console.log("async-escaped-group-b-ok");
          } else {
            console.log("async-escaped-group-b-bad");
          }
          return av + bv;
        }

        function make(base: int): async (n: int) => int {
          async function f(n: int): int {
            if (n <= 0) {
              return base;
            }
            return await g(n - 1);
          }
          async function g(n: int): int {
            return await f(n);
          }
          return f;
        }

        export function main(): null {
          return null;
        }
        """,
        List.of("async-escaped-group-a-ok", "async-escaped-group-b-ok"), true,
        List.of(true, true), false, false, null, new AsyncDrive("drive"),
        "int:30");

    /**
     * The awaited nested-member declared-parameter origin (the cycle-3
     * finding's async-origin requirement): {@code await g(bad.x)} carries a
     * contextual table member argument, and its kind check must run at
     * {@code g}'s declared parameter annotation — the value-carried async
     * start keeps {@code lowerCallArgument}'s deferral, exactly like the
     * synchronous declared-callee arm, instead of composing the argument
     * read's own {@code CONTEXTUAL_TABLE_READ} cell. The read's origin is not
     * admissible on any of the three consumers.
     */
    private static final Case ASYNC_PARAM_ORIGIN = new Case(
        "async-param-origin",
        """
        export async function drive(): int {
          let bad: table = { x: "wrong" };
          let a: async (n: int) => int = make(10, bad);
          return await a(3);
        }

        function make(base: int, bad: table): async (n: int) => int {
          async function f(n: int): int {
            if (n <= 0) {
              return base;
            }
            return await g(bad.x);
          }
          async function g(n: int): int {
            return await f(n);
          }
          return f;
        }

        export function main(): null {
          return null;
        }
        """,
        List.of(), true, List.of(true, true), false, false,
        new FailurePin("E8001", "async function g(n: "), new AsyncDrive("drive"),
        "null");

    /**
     * The awaited sibling <em>alias</em> (the review's cycle-4 seed): the
     * two-instance async group's {@code f} awaits sibling {@code g} through
     * an ordinary function-value alias ({@code let sibling: async (n: int) =>
     * int = g}) instead of the declared name. {@code make} returns member
     * {@code f}; the exported {@code drive} creates {@code make(10)} and
     * {@code make(20)} and awaits each returned closure. The tracked alias
     * preserves the member's allocation identity, so the resolved binding is
     * the member's {@code LoweredBody} — but the alias binding owns no
     * lowering context and no group membership, so the resolved body would
     * reach the static async arm: the await site reconstructs the member's
     * factory and re-resolves the target's captures from the calling member's
     * frame, so the first creation observes the second creation's cells
     * ({@code a-bad}, {@code b-ok} and result 40 before the fix) and the
     * oracle fails on the uninitialized member binding. The alias carries the
     * member's published closure, so the awaited invocation must be the
     * value-carried (indirect) callee shape. Target {@code g} is declared
     * after the awaiting member, so its capture list is still pending during
     * {@code f}'s walk — the classification must cover the pending member
     * too.
     */
    private static final Case ASYNC_ESCAPED_TWO_GROUPS_ALIAS = new Case(
        "async-escaped-two-groups-alias",
        """
        import * as console from "std/console"

        export async function drive(): int {
          let a: async (n: int) => int = make(10);
          let b: async (n: int) => int = make(20);
          let av: int = await a(3);
          if (av === 10) {
            console.log("async-escaped-group-alias-a-ok");
          } else {
            console.log("async-escaped-group-alias-a-bad");
          }
          let bv: int = await b(3);
          if (bv === 20) {
            console.log("async-escaped-group-alias-b-ok");
          } else {
            console.log("async-escaped-group-alias-b-bad");
          }
          return av + bv;
        }

        function make(base: int): async (n: int) => int {
          async function f(n: int): int {
            if (n <= 0) {
              return base;
            }
            let sibling: async (n: int) => int = g;
            return await sibling(n - 1);
          }
          async function g(n: int): int {
            return await f(n);
          }
          return f;
        }

        export function main(): null {
          return null;
        }
        """,
        List.of("async-escaped-group-alias-a-ok", "async-escaped-group-alias-b-ok"),
        true, List.of(true, true), false, false, null, new AsyncDrive("drive"),
        "int:30");

    /**
     * The awaited sibling alias with the reverse declaration order: the
     * awaited target ({@code g}) is declared <em>before</em> the awaiting
     * member ({@code f}), so the target's {@code LoweredFunction} record and
     * its capture list are final when {@code f}'s body walks. The
     * value-carried classification then comes from the recorded captures
     * (the target references a sibling, so the list is non-empty) instead of
     * the pending-member arm — the other half of the carrier selection. The
     * two created groups must still observe their own {@code base}.
     */
    private static final Case ASYNC_ESCAPED_TWO_GROUPS_ALIAS_LATER = new Case(
        "async-escaped-two-groups-alias-later",
        """
        import * as console from "std/console"

        export async function drive(): int {
          let a: async (n: int) => int = make(10);
          let b: async (n: int) => int = make(20);
          let av: int = await a(3);
          if (av === 10) {
            console.log("async-escaped-group-alias-later-a-ok");
          } else {
            console.log("async-escaped-group-alias-later-a-bad");
          }
          let bv: int = await b(3);
          if (bv === 20) {
            console.log("async-escaped-group-alias-later-b-ok");
          } else {
            console.log("async-escaped-group-alias-later-b-bad");
          }
          return av + bv;
        }

        function make(base: int): async (n: int) => int {
          async function g(n: int): int {
            return await f(n);
          }
          async function f(n: int): int {
            if (n <= 0) {
              return base;
            }
            let sibling: async (n: int) => int = g;
            return await sibling(n - 1);
          }
          return f;
        }

        export function main(): null {
          return null;
        }
        """,
        List.of("async-escaped-group-alias-later-a-ok",
            "async-escaped-group-alias-later-b-ok"),
        true, List.of(true, true), false, false, null, new AsyncDrive("drive"),
        "int:30");

    /**
     * The awaited member's post-await parameter use (the review's cycle-1
     * seed), through the declared sibling name: {@code f} awaits sibling
     * {@code g} as a statement and then returns {@code base + n}. Each
     * recursive async invocation re-entered the same body while the
     * enclosing invocation's private slots were still live, so the resumed
     * invocation read the deepest re-entry's {@code n} (0) and both
     * creations published {@code base} instead of {@code base + n} (13/23,
     * total 36). The async body-task arm must save the callee body's private
     * state before a re-entrant body execution and restore it on the success
     * and failure paths — the async counterpart of the synchronous
     * invocation's save/restore protocol. The oracle, the trace artifacts,
     * and the staged production artifacts must all observe the pinned
     * per-creation results.
     */
    private static final Case ASYNC_ESCAPED_TWO_GROUPS_PARAM = new Case(
        "async-escaped-two-groups-param",
        """
        import * as console from "std/console"

        export async function drive(): int {
          let a: async (n: int) => int = make(10);
          let b: async (n: int) => int = make(20);
          let av: int = await a(3);
          if (av === 13) {
            console.log("async-escaped-group-param-a-ok");
          } else {
            console.log("async-escaped-group-param-a-bad");
          }
          let bv: int = await b(3);
          if (bv === 23) {
            console.log("async-escaped-group-param-b-ok");
          } else {
            console.log("async-escaped-group-param-b-bad");
          }
          return av + bv;
        }

        function make(base: int): async (n: int) => int {
          async function f(n: int): int {
            if (n <= 0) {
              return base;
            }
            await g(n - 1);
            return base + n;
          }
          async function g(n: int): int {
            return await f(n);
          }
          return f;
        }

        export function main(): null {
          return null;
        }
        """,
        List.of("async-escaped-group-param-a-ok", "async-escaped-group-param-b-ok"),
        true, List.of(true, true), false, false, null, new AsyncDrive("drive"),
        "int:36");

    /**
     * The same post-await parameter use through an ordinary typed sibling
     * alias ({@code let sibling: async (n: int) => int = g}): the value-carried
     * async arm resolves the alias to the member's {@code LoweredBody}, so the
     * invocation must keep the published capture carrier while it saves and
     * restores the member's private state around the re-entrant body
     * execution. The target {@code g} is declared after the awaiting member,
     * so its capture list is still pending when {@code f} walks.
     */
    private static final Case ASYNC_ESCAPED_TWO_GROUPS_ALIAS_PARAM = new Case(
        "async-escaped-two-groups-alias-param",
        """
        import * as console from "std/console"

        export async function drive(): int {
          let a: async (n: int) => int = make(10);
          let b: async (n: int) => int = make(20);
          let av: int = await a(3);
          if (av === 13) {
            console.log("async-escaped-group-alias-param-a-ok");
          } else {
            console.log("async-escaped-group-alias-param-a-bad");
          }
          let bv: int = await b(3);
          if (bv === 23) {
            console.log("async-escaped-group-alias-param-b-ok");
          } else {
            console.log("async-escaped-group-alias-param-b-bad");
          }
          return av + bv;
        }

        function make(base: int): async (n: int) => int {
          async function f(n: int): int {
            if (n <= 0) {
              return base;
            }
            let sibling: async (n: int) => int = g;
            await sibling(n - 1);
            return base + n;
          }
          async function g(n: int): int {
            return await f(n);
          }
          return f;
        }

        export function main(): null {
          return null;
        }
        """,
        List.of("async-escaped-group-alias-param-a-ok",
            "async-escaped-group-alias-param-b-ok"),
        true, List.of(true, true), false, false, null, new AsyncDrive("drive"),
        "int:36");

    /**
     * The post-await read of a <em>captured</em> member parameter and local
     * (the review's cycle-2 seed) through the declared sibling name: the
     * closure {@code get} makes both {@code n} and {@code m} shared cells,
     * and {@code f} reads their module-level cell slots directly after the
     * awaited recursion. The async invocation's state save/restore must
     * cover the shared-cell references the member itself allocates — not
     * only its {@code DIRECT} cells and value slots — because every
     * recursive re-entry replaces those module-level references with its
     * own incarnation. Without the reference restore the resumed invocation
     * reads the deepest re-entry's cells (the pre-fix artifacts published
     * 11/21, total 32, where the oracle pins 16/26, total 42), and merely
     * adding the closure changes the enclosing member's result. Restoring a
     * cell <em>reference</em> (never its contents) keeps in-place commits
     * through the shared cell visible.
     */
    private static final Case ASYNC_ESCAPED_TWO_GROUPS_CAPTURED = new Case(
        "async-escaped-two-groups-captured",
        """
        import * as console from "std/console"

        export async function drive(): int {
          let a: async (n: int) => int = make(10);
          let b: async (n: int) => int = make(20);
          let av: int = await a(3);
          if (av === 16) {
            console.log("async-escaped-group-captured-a-ok");
          } else {
            console.log("async-escaped-group-captured-a-bad");
          }
          let bv: int = await b(3);
          if (bv === 26) {
            console.log("async-escaped-group-captured-b-ok");
          } else {
            console.log("async-escaped-group-captured-b-bad");
          }
          return av + bv;
        }

        function make(base: int): async (n: int) => int {
          async function f(n: int): int {
            if (n <= 0) {
              return base;
            }
            let m: int = n;
            let get: () => int = function(): int { return n + m; };
            await g(n - 1);
            return base + n + m;
          }
          async function g(n: int): int {
            return await f(n);
          }
          return f;
        }

        export function main(): null {
          return null;
        }
        """,
        List.of("async-escaped-group-captured-a-ok",
            "async-escaped-group-captured-b-ok"),
        true, List.of(true, true), false, false, null, new AsyncDrive("drive"),
        "int:42");

    /**
     * The same post-await read of the captured parameter and local through
     * an ordinary typed sibling alias: the value-carried async arm resolves
     * the alias to the member's {@code LoweredBody} and must keep the
     * published capture carrier while it saves and restores the member's
     * own shared-cell references around the re-entrant body execution. The
     * target {@code g} is declared after the awaiting member, so its capture
     * list is still pending when {@code f} walks.
     */
    private static final Case ASYNC_ESCAPED_TWO_GROUPS_ALIAS_CAPTURED = new Case(
        "async-escaped-two-groups-alias-captured",
        """
        import * as console from "std/console"

        export async function drive(): int {
          let a: async (n: int) => int = make(10);
          let b: async (n: int) => int = make(20);
          let av: int = await a(3);
          if (av === 16) {
            console.log("async-escaped-group-alias-captured-a-ok");
          } else {
            console.log("async-escaped-group-alias-captured-a-bad");
          }
          let bv: int = await b(3);
          if (bv === 26) {
            console.log("async-escaped-group-alias-captured-b-ok");
          } else {
            console.log("async-escaped-group-alias-captured-b-bad");
          }
          return av + bv;
        }

        function make(base: int): async (n: int) => int {
          async function f(n: int): int {
            if (n <= 0) {
              return base;
            }
            let m: int = n;
            let get: () => int = function(): int { return n + m; };
            let sibling: async (n: int) => int = g;
            await sibling(n - 1);
            return base + n + m;
          }
          async function g(n: int): int {
            return await f(n);
          }
          return f;
        }

        export function main(): null {
          return null;
        }
        """,
        List.of("async-escaped-group-alias-captured-a-ok",
            "async-escaped-group-alias-captured-b-ok"),
        true, List.of(true, true), false, false, null, new AsyncDrive("drive"),
        "int:42");

    private static final List<Case> CASES = List.of(
        CALLED_EXPLICIT, CALLED_IMPLICIT, CALLED_VALUE_REFERENCE,
        CALLED_ALIAS, CALLED_ALIAS_CAPTURED, CALLED_ALIAS_LATER, MODULE_GROUP_ALIAS,
        NEVER_CALLED_EXPLICIT_NULL, NEVER_CALLED_EXPLICIT_INT, NEVER_CALLED_IMPLICIT,
        CAPTURED_CALLED, NESTED_INSIDE_FUNCTION, ESCAPED_TWO_GROUPS,
        ESCAPED_TWO_GROUPS_RECURSION, ESCAPED_TWO_GROUPS_ALIAS_RECURSION,
        PARAM_ORIGIN_CONTEXTUAL, ASYNC_ESCAPED_TWO_GROUPS, ASYNC_PARAM_ORIGIN,
        ASYNC_ESCAPED_TWO_GROUPS_ALIAS, ASYNC_ESCAPED_TWO_GROUPS_ALIAS_LATER,
        ASYNC_ESCAPED_TWO_GROUPS_PARAM, ASYNC_ESCAPED_TWO_GROUPS_ALIAS_PARAM,
        ASYNC_ESCAPED_TWO_GROUPS_CAPTURED,
        ASYNC_ESCAPED_TWO_GROUPS_ALIAS_CAPTURED);

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
        Path project = Files.createTempDirectory("nested-group-" + testCase.name() + "-");
        try {
            Path src = project.resolve("src");
            Files.createDirectories(src);
            Files.writeString(src.resolve("main.deal"), testCase.source(),
                StandardCharsets.UTF_8);
            Files.writeString(project.resolve("deal.json"), DEAL_JSON,
                StandardCharsets.UTF_8);
            Path entry = src.resolve("main.deal");

            // Surface 1: both release-owned production lanes.
            CompilationOrchestrator luaCompilation =
                compileLane(testCase, project, entry, "luajit");
            compileLane(testCase, project, entry, "jvm");

            // Surface 2: the one lowering and the member ownership.
            Lowered lowered = lower(testCase, luaCompilation);
            if (lowered == null) {
                return;
            }

            // Surface 3: the three-consumer differential matrix.
            differential(testCase, lowered, entry);

            // Surface 4: the staged production artifacts execute the async
            // export on both release-owned lanes (the trace matrix above
            // proves oracle agreement; this drive proves production emission).
            // A failure-pin case is driven by the three-consumer matrix only:
            // the staged production artifacts report the code, not the trace
            // projection the failure assertion pins.
            if (testCase.asyncDrive() != null && testCase.failure() == null) {
                driveProductionAsync(testCase, project, lowered);
            }

            // Surface 5: the staged production artifacts execute the sync
            // entry on both release-owned lanes through their production
            // entry surface (no trace instrumentation); the differential
            // matrix above proves oracle agreement, this drive proves the
            // production emission end to end.
            if (testCase.stagedSync() && testCase.failure() == null) {
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
            if (Files.isRegularFile(artifact)) {
                compileStagedJvm(testCase, out, artifact);
            }
        }
        return orchestrator;
    }

    /** The parent epic's JVM criterion on the staged artifact. */
    private static void compileStagedJvm(Case testCase, Path out, Path artifact)
            throws Exception {
        Path classes = out.resolve("classes");
        Files.createDirectories(classes);
        ProcessBuilder javac = new ProcessBuilder("javac", "--release", "25",
            "-proc:none", "-cp", classes + File.pathSeparator + absoluteClasspath(),
            "-d", classes.toString(), artifact.toString());
        javac.directory(out.toFile());
        javac.redirectErrorStream(true);
        Process compile = javac.start();
        String compileOut = new String(compile.getInputStream().readAllBytes(),
            StandardCharsets.UTF_8);
        int exit = compile.waitFor();
        check(exit == 0, testCase.name() + " [jvm]: the staged artifact compiles under "
            + "javac --release 25 -proc:none: " + compileOut);
    }

    /**
     * Surface 4: the staged production artifacts execute the case's async
     * export on both release-owned lanes through their production async-entry
     * surface (a success case; the trace matrix owns the failure pins). The
     * LuaJIT probe loads the staged chunk, runs module initialization and
     * {@code main()} exactly once, and calls the staged {@code __asyncEntries}
     * entry; the JVM probe runs {@code Main.dealMain()} and the staged
     * {@code Main.ae<id>} entry. Both must publish exactly the case's pinned
     * console effects and the pinned result, with an empty stderr and exit 0.
     */
    private static void driveProductionAsync(Case testCase, Path project, Lowered lowered)
            throws Exception {
        String export = testCase.asyncDrive().exportName();
        String marker = "NESTED_GROUP_ASYNC_RESULT:";
        String expectedResult = productionResultText(testCase.resultAtom());
        List<String> expectedStdout = new ArrayList<>(testCase.effects());
        expectedStdout.add(marker + expectedResult);

        Path luaOut = project.resolve("out-luajit");
        Path chunk = luaOut.resolve("main.lua");
        check(Files.isRegularFile(chunk), testCase.name()
            + " [luajit]: the staged chunk carries the async case: " + chunk);
        if (Files.isRegularFile(chunk)) {
            Path probe = luaOut.resolve("__nested_group_async_probe.lua");
            Files.writeString(probe,
                "dofile(\"" + luaString(chunk.toAbsolutePath().normalize().toString())
                    + "\")\n"
                    + "local __ok, __err = __dealMain()\n"
                    + "if not __ok then error(__err, 0) end\n"
                    + "local __okA, __resA = pcall(__asyncEntries[\""
                    + luaString(lowered.project().entryModule().path() + "#" + export)
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
        Long entryId = asyncEntryIdOf(lowered.unit(), export);
        check(entryId != null, testCase.name() + " [jvm]: the unit records the async "
            + "export's EXTERNAL_ENTRY over the production entry surface");
        Path classes = jvmOut.resolve("classes");
        boolean artifactCompiled = Files.isRegularFile(
            classes.resolve(JvmNames.classNameFor("main") + ".class"));
        check(artifactCompiled, testCase.name()
            + " [jvm]: the staged artifact is compiled for the async drive");
        if (Files.isRegularFile(artifact) && entryId != null && artifactCompiled) {
            String probeClass = "NestedGroupAsyncProbe_"
                + UUID.randomUUID().toString().replace("-", "");
            Path probe = jvmOut.resolve(probeClass + ".java");
            Files.writeString(probe,
                "public final class " + probeClass + " {\n"
                    + "  public static void main(String[] args) {\n"
                    + "    Main.dealMain();\n"
                    + "    Object result = Main.ae" + entryId
                    + "(\"-\", true, new Object[]{});\n"
                    + "    System.out.println(\"" + marker + "\" + result);\n"
                    + "    System.exit(0);\n"
                    + "  }\n"
                    + "}\n",
                StandardCharsets.UTF_8);
            ProcessBuilder javac = new ProcessBuilder("javac", "--release", "25",
                "-proc:none", "-sourcepath", "", "-cp",
                classes + File.pathSeparator + absoluteClasspath(),
                "-d", classes.toString(), probe.toString());
            javac.directory(jvmOut.toFile());
            ProcessOutput compile = runProcess(javac);
            check(compile.exit() == 0, testCase.name() + " [jvm]: the async probe "
                + "compiles against the staged classes under javac --release 25 -proc:none: "
                + compile.describe());
            if (compile.exit() == 0) {
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
    }

    /**
     * Surface 5: the staged production artifacts execute the case's sync
     * entry on both release-owned lanes through their production entry
     * surface (no trace instrumentation). The LuaJIT probe loads the staged
     * chunk and the module-init walk runs {@code main()} exactly once; the
     * JVM probe runs the staged class's production {@code main}. Both must
     * publish exactly the case's pinned console effects, with an empty
     * stderr and exit 0.
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
        Process process = builder.start();
        String out = new String(process.getInputStream().readAllBytes(),
            StandardCharsets.UTF_8);
        String err = new String(process.getErrorStream().readAllBytes(),
            StandardCharsets.UTF_8);
        int exit = process.waitFor();
        return new ProcessOutput(exit, splitLines(out), splitLines(err));
    }

    private static List<String> splitLines(String text) {
        if (text.isEmpty()) {
            return List.of();
        }
        List<String> lines = new ArrayList<>(List.of(text.split("\n", -1)));
        if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        List<String> normalized = new ArrayList<>();
        for (String line : lines) {
            normalized.add(line.endsWith("\r")
                ? line.substring(0, line.length() - 1) : line);
        }
        return normalized;
    }

    /** One Lua short-string literal body (no long strings, no interpolation). */
    private static String luaString(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // =========================================================================
    // Surface 2: the one lowering and the member ownership
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
        SemanticLowerer.ProjectLoweringResult result = SemanticLowerer.lowerProject(
            productionInvocation(), built.input(), built.index(),
            manifests.manifests(), orchestrator.hostDeclarationSurface(),
            Map.of(), Map.of(),
            BuiltinErrorDeclaration.synthesized(
                built.input().modules().get(0).ast().span()),
            List.of(IntrinsicKind.INT_CONVERT, IntrinsicKind.NUMBER_CONVERT,
                IntrinsicKind.BYTES_NEW),
            Set.of());
        check(result.project() != null && result.diagnostics().isEmpty(),
            testCase.name() + ": the nested group lowers with zero diagnostics (no "
                + "CONSTRUCT_UNLOWERED/CONTROL_EXIT): " + result.diagnostics());
        if (result.project() == null) {
            return null;
        }
        ModuleId entryModule = result.project().entryModule();
        LoweredModuleUnit unit = result.project().modules().get(entryModule);
        StructuredBodyTable table = result.tables().get(entryModule);
        check(unit != null && table != null, testCase.name()
            + ": the lowering produced the entry unit and its block table");
        if (unit == null || table == null) {
            return null;
        }
        checkMemberOwnership(testCase, unit, table);
        return new Lowered(result.project(), result.tables(), result.registries(),
            unit, table);
    }

    /**
     * The member ownership battery: the group publication, the per-member
     * return cell, the per-member invocation identity, and (for a nested
     * group) the value-carried member calls.
     */
    private static void checkMemberOwnership(Case testCase, LoweredModuleUnit unit,
                                             StructuredBodyTable table) {
        // The exact rule that rejected the pre-fix unit: a member's RETURN
        // named the enclosing function while its block belonged to the member
        // (CONTROL_EXIT).
        java.util.Optional<CompilerDiagnostic> admission =
            ControlFlowValidator.validate(unit, table);
        check(admission.isEmpty(), testCase.name()
            + ": the control-flow validator admits the nested members: " + admission);
        List<SemanticOp> groupOps = new ArrayList<>();
        Map<OpId, SemanticOp> byId = new LinkedHashMap<>();
        for (SemanticOp op : unit.ops()) {
            byId.put(op.opId(), op);
            if (op.kind() == SemanticOpKind.RECURSIVE_GROUP_INIT) {
                groupOps.add(op);
            }
        }
        check(groupOps.size() == 1, testCase.name()
            + ": exactly one RECURSIVE_GROUP_INIT publishes the nested group; got "
            + groupOps.size());
        if (groupOps.size() != 1) {
            return;
        }
        SemanticOp groupOp = groupOps.get(0);
        if (!(groupOp.payload()
                instanceof KindPayload.RecursiveGroupInitPayload group)) {
            fail(testCase.name() + ": the group op carries the group payload");
            return;
        }
        check(group.functions().size() == 2 && group.bindings().size() == 2,
            testCase.name() + ": the group op publishes both members (functions="
                + group.functions() + " bindings=" + group.bindings() + ")");

        boolean hasEnclosing = unit.functions().keySet().stream()
            .anyMatch(function -> !group.functions().contains(function));
        check(hasEnclosing, testCase.name()
            + ": the enclosing function is a lowered function of the unit");

        Map<FunctionId, List<SemanticOp>> returnsByFunction = new LinkedHashMap<>();
        for (SemanticOp op : unit.ops()) {
            if (op.kind() != SemanticOpKind.RETURN) {
                continue;
            }
            KindPayload.ReturnPayload returned = (KindPayload.ReturnPayload) op.payload();
            returnsByFunction.computeIfAbsent(returned.function(),
                ignored -> new ArrayList<>()).add(op);
        }
        // The defect's exact signature: a member's explicit return named the
        // enclosing function, so the enclosing function carried the members'
        // RETURN ops. Every non-member function still owns a return op, and
        // the validator above rejects any RETURN whose block is not rooted at
        // the named function's body.
        for (FunctionId function : unit.functions().keySet()) {
            if (group.functions().contains(function)) {
                continue;
            }
            check(!returnsByFunction.getOrDefault(function, List.of()).isEmpty(),
                testCase.name() + ": the non-member function " + function
                    + " owns a RETURN op");
        }

        for (int memberIndex = 0; memberIndex < group.functions().size();
                memberIndex++) {
            FunctionId member = group.functions().get(memberIndex);
            boolean memberCalled = memberIndex < testCase.memberCalled().size()
                && Boolean.TRUE.equals(testCase.memberCalled().get(memberIndex));
            LoweredFunction lowered = unit.functions().get(member);
            check(lowered != null, testCase.name() + ": member " + member
                + " is a lowered function of the unit");
            if (lowered == null) {
                continue;
            }
            List<SemanticOp> memberReturns = returnsByFunction.getOrDefault(member,
                List.of());
            check(!memberReturns.isEmpty(), testCase.name() + ": member " + member
                + " owns at least one RETURN op");
            Set<OpId> boundaries = new LinkedHashSet<>();
            for (SemanticOp op : memberReturns) {
                KindPayload.ReturnPayload returned =
                    (KindPayload.ReturnPayload) op.payload();
                boundaries.add(returned.returnBoundaryOpId());
                check(byId.containsKey(returned.returnBoundaryOpId()), testCase.name()
                    + ": member " + member + "'s return boundary "
                    + returned.returnBoundaryOpId() + " is an op of the unit");
                check(table.opBlocks().get(op.opId()) != null, testCase.name()
                    + ": member " + member + "'s RETURN " + op.opId()
                    + " is a member of a block");
                if (testCase.implicitReturns()) {
                    check(op.origin().kind() == SourceOriginKind.SYNTHETIC,
                        testCase.name() + ": member " + member
                            + "'s implicit return is the synthetic trailing return");
                } else {
                    check(op.origin().kind() == SourceOriginKind.USER, testCase.name()
                        + ": member " + member
                        + "'s explicit return carries the source origin");
                }
                if (memberCalled) {
                    SemanticOp invocation = byId.get(returned.enclosingInvocationOpId());
                    check(invocation != null
                            && isInvocationOf(invocation, member, unit),
                        testCase.name() + ": member " + member
                            + "'s invocation identity is its own CALL/ASYNC_START op; got "
                            + returned.enclosingInvocationOpId());
                } else {
                    check(returned.enclosingInvocationOpId().equals(groupOp.opId()),
                        testCase.name() + ": the never-called member " + member
                            + "'s invocation identity is materialized by the group op "
                            + groupOp.opId() + "; got "
                            + returned.enclosingInvocationOpId());
                }
            }
            check(boundaries.size() == 1, testCase.name() + ": member " + member
                + " owns exactly one return boundary; got " + boundaries);
        }
        if (testCase.aliasInvocation()) {
            checkAliasInvocation(testCase, unit, group);
        }
        BlockId groupBlock = table.opBlocks().get(groupOp.opId());
        if (groupBlock != null
                && !groupBlock.equals(unit.moduleInit().initBlock())) {
            // A nested group: every member-resolving call must be the
            // value-carried (indirect) callee shape, because only the
            // published carrier holds the group instance's creation-site
            // cells. A module-level group keeps the landed static shape
            // (its captures are single-incarnation module cells).
            checkValueCarriedMemberCalls(testCase, unit, group);
        }
    }

    /**
     * The nested-group carrier-preservation battery: no call or async start
     * of a capture-carrying member body may be a factory reconstruction at
     * the call site. The escaped groups are the discriminating cases — a
     * static reconstruction of the sibling would re-resolve the target's
     * captures from the calling member's frame and observe the latest
     * creation's cells; the published carrier holds its own creation's
     * cells.
     */
    private static void checkValueCarriedMemberCalls(Case testCase,
            LoweredModuleUnit unit, KindPayload.RecursiveGroupInitPayload group) {
        boolean anyMemberCalled = testCase.memberCalled().stream()
            .anyMatch(Boolean::booleanValue);
        int memberCalls = 0;
        int staticCaptureCalls = 0;
        for (SemanticOp op : unit.ops()) {
            KindPayload.CallCallee callee = invocationCallee(op);
            if (callee == null) {
                continue;
            }
            FunctionExecutionBinding resolved = switch (callee) {
                case KindPayload.CallCallee.Static stat -> stat.binding();
                case KindPayload.CallCallee.Indirect indirect ->
                    unit.functionBindings().get(
                        new FunctionAllocationIdentity(indirect.callee().id()));
                case KindPayload.CallCallee.Dynamic ignored -> null;
            };
            if (!(resolved instanceof FunctionExecutionBinding.LoweredBody body)
                    || !group.functions().contains(body.functionId())) {
                continue;
            }
            memberCalls++;
            LoweredFunction target = unit.functions().get(body.functionId());
            boolean captures = target != null && !target.captures().isEmpty();
            if (captures
                    && !(callee instanceof KindPayload.CallCallee.Indirect)) {
                staticCaptureCalls++;
                fail(testCase.name() + ": the member invocation " + op.opId()
                    + " of capture-carrying member " + body.functionId()
                    + " is a factory reconstruction at the call site; the "
                    + "value-carried carrier is required");
            }
        }
        check(staticCaptureCalls == 0, testCase.name()
            + ": every capture-carrying member invocation is value-carried; got "
            + staticCaptureCalls + " static reconstruction(s)");
        check(memberCalls >= 1 || !anyMemberCalled, testCase.name()
            + ": the group's member references are materialized as invocations; got "
            + memberCalls);
    }

    /**
     * The sibling-alias battery: a call op resolves a member through an
     * ordinary function value (the {@code Indirect} callee shape — the
     * value-carried invocation), and the target member carries creation-site
     * captures (the group's sibling cells), which is exactly why the
     * invocation is value-carried rather than re-resolved at the call site.
     */
    private static void checkAliasInvocation(Case testCase, LoweredModuleUnit unit,
                                             KindPayload.RecursiveGroupInitPayload group) {
        int aliasCalls = 0;
        for (SemanticOp op : unit.ops()) {
            if (op.kind() != SemanticOpKind.CALL
                    || !(op.payload() instanceof KindPayload.CallPayload payload)
                    || !(payload.callee()
                        instanceof KindPayload.CallCallee.Indirect indirect)) {
                continue;
            }
            FunctionExecutionBinding binding = unit.functionBindings().get(
                new FunctionAllocationIdentity(indirect.callee().id()));
            if (!(binding instanceof FunctionExecutionBinding.LoweredBody body)
                    || !group.functions().contains(body.functionId())) {
                continue;
            }
            aliasCalls++;
            LoweredFunction target = unit.functions().get(body.functionId());
            check(target != null && !target.captures().isEmpty(), testCase.name()
                + ": the sibling alias to member " + body.functionId()
                + " is value-carried with the target's creation-site captures; got "
                + (target == null ? "no lowered function" : target.captures()));
        }
        check(aliasCalls >= 1, testCase.name() + ": the sibling alias materializes a "
            + "member invocation through the value-carried callee shape; got "
            + aliasCalls);
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

    /** Whether one CALL/ASYNC_START op resolves to an invocation of the given body. */
    private static boolean isInvocationOf(SemanticOp invocation, FunctionId member,
                                          LoweredModuleUnit unit) {
        KindPayload.CallCallee callee = invocationCallee(invocation);
        if (callee == null) {
            return false;
        }
        FunctionExecutionBinding binding = switch (callee) {
            case KindPayload.CallCallee.Static stat -> stat.binding();
            case KindPayload.CallCallee.Indirect indirect -> unit.functionBindings().get(
                new FunctionAllocationIdentity(indirect.callee().id()));
            case KindPayload.CallCallee.Dynamic ignored -> null;
        };
        return binding instanceof FunctionExecutionBinding.LoweredBody body
            && body.functionId().equals(member);
    }

    // =========================================================================
    // Surface 3: the three-consumer differential matrix
    // =========================================================================

    private static void differential(Case testCase, Lowered lowered, Path entry)
            throws Exception {
        Path workspace = Files.createTempDirectory(
            "nested-group-matrix-" + testCase.name() + "-");
        try {
            String failureOrigin = testCase.failure() == null ? null
                : originTextAt(testCase.source(), testCase.failure().anchor(), entry);
            check(testCase.failure() == null || failureOrigin != null, testCase.name()
                + ": the failure pin's origin anchor is present in the source");
            SemanticDifferentialHarness.Expectation expectation = testCase.failure() == null
                ? SemanticDifferentialHarness.Expectation.success(testCase.name(),
                    testCase.effects(), testCase.resultAtom())
                : SemanticDifferentialHarness.Expectation.failure(testCase.name(),
                    testCase.effects(), testCase.failure().code(), failureOrigin);
            SemanticDifferentialHarness.Verdict verdict = testCase.asyncDrive() == null
                ? SemanticDifferentialHarness.runProject(lowered.project(),
                    lowered.tables(), lowered.registries(), expectation, workspace)
                : SemanticDifferentialHarness.runAsyncEntry(lowered.project(),
                    lowered.tables(), testCase.asyncDrive().exportName(), List.of(),
                    expectation, workspace, null);
            check(verdict.runs().size() == 3, testCase.name()
                + ": the differential matrix produced the three consumers: "
                + verdict.failures());
            List<String> unexplained = new ArrayList<>();
            for (String failure : verdict.failures()) {
                if (!isRecursivePairingNotice(failure)) {
                    unexplained.add(failure);
                }
            }
            String allowed = testCase.recursive()
                ? " (only the recursive re-entry pairing notices allowed)"
                : "";
            check(unexplained.isEmpty(), testCase.name() + ": the oracle and both "
                + "shared artifacts agree event-for-event with the pinned terminal "
                + "and effects" + allowed + ": " + unexplained);
            if (!testCase.recursive()) {
                check(verdict.pass(), testCase.name()
                    + ": a non-recursive case carries no accepted failure: "
                    + verdict.failures());
            }
            for (SemanticRuntimeModel.ConsumerRun run : verdict.runs()) {
                if (testCase.failure() != null) {
                    checkFailureProjection(testCase, run, failureOrigin);
                    continue;
                }
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
                    + run.consumer() + "]: the pinned console effects " + testCase.effects()
                    + "; got " + texts);
            }
        } finally {
            deleteRecursively(workspace);
        }
    }

    /**
     * The pinned failure projection of one failing consumer: the exact
     * terminal code and origin (never the argument read's origin), the
     * closed kind arm's message, and its expected/actual tokens.
     */
    private static void checkFailureProjection(Case testCase,
            SemanticRuntimeModel.ConsumerRun run, String failureOrigin) {
        if (!(run.terminal() instanceof SemanticRuntimeModel.Terminal.DealFailure failed)) {
            fail(testCase.name() + " [" + run.consumer()
                + "]: the pinned failure terminal; got " + run.terminal());
            return;
        }
        SemanticRuntimeModel.ErrorSnapshot error = failed.error();
        check(testCase.failure().code().equals(error.code()), testCase.name() + " ["
            + run.consumer() + "]: the pinned failure code " + testCase.failure().code()
            + "; got " + error.code());
        check(failureOrigin != null && failureOrigin.equals(error.origin()),
            testCase.name() + " [" + run.consumer()
                + "]: the declared parameter annotation origin " + failureOrigin
                + "; got " + error.origin());
        check("expected int".equals(error.message()), testCase.name() + " ["
            + run.consumer() + "]: the kind arm's suffix-less text; got "
            + error.message());
        check("int".equals(error.expected()), testCase.name() + " [" + run.consumer()
            + "]: the pinned expected token; got " + error.expected());
        check("string".equals(error.actual()), testCase.name() + " [" + run.consumer()
            + "]: the pinned actual token; got " + error.actual());
    }

    /**
     * The exact origin text of the source offset following one anchor: the
     * line and column of the anchor's end, in the entry module's source id
     * (the located source path the production compile records).
     */
    private static String originTextAt(String source, String anchor, Path entry) {
        int anchorIndex = source.indexOf(anchor);
        if (anchorIndex < 0) {
            return null;
        }
        int offset = anchorIndex + anchor.length();
        int line = 1;
        int column = 1;
        for (int i = 0; i < offset; i++) {
            if (source.charAt(i) == '\n') {
                line++;
                column = 1;
            } else {
                column++;
            }
        }
        return entry.toAbsolutePath().normalize() + ":" + line + ":" + column;
    }

    /**
     * The harness's two re-entry pairing notices: a recursively re-entered
     * call op starts again before its previous terminal and therefore also
     * terminates without a matching open START. The direct-recursion fixtures
     * carry exactly this accepted shape; every other failure is a real
     * disagreement.
     */
    private static boolean isRecursivePairingNotice(String failure) {
        return failure.contains("starts again before its previous terminal")
            || failure.contains("terminates without an open START");
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
        System.out.println("=== Nested Group Production Test (ISSUE-0731) ===\n");
        for (Case testCase : CASES) {
            runCase(testCase);
        }
        System.out.println("\nPassed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }
}
