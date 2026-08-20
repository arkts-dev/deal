package deal.codegen.jvm;

import deal.ast.*;
import deal.ast.ArrayType;
import deal.ast.FunctionType;
import deal.ast.NullableType;
import deal.checker.CheckResult;
import deal.checker.Symbol;
import deal.checker.SymbolTable;
import deal.diagnostics.DiagnosticCode;
import deal.lexer.Diagnostic;
import deal.types.Type;
import deal.types.Types;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * JVM code generator (ISSUE-0091 skeleton, ISSUE-0092 semantic slice —
 * while loops and template literals, ISSUE-0093 functions and direct
 * calls slice, ISSUE-0094 primitive-array slice — array literals,
 * indexing, element assignment, and {@code .length} reads for
 * {@code int[]}, {@code number[]}, {@code string[]}, and
 * {@code boolean[]}, ISSUE-0096 modules/imports/exports slice): a small
 * but real end-to-end JVM backend.
 *
 * <p>Walks the typed AST (the compiler's IR — see {@code deal-compiler-architecture-v1})
 * and emits a self-contained Java class whose static methods implement the
 * module's functions. The Java source is a real JVM artifact: the
 * conformance adapter ({@code test/BackendConformanceTest.java}) compiles
 * it with {@code javac} and executes it with {@code java} in subprocesses;
 * {@code CompilationOrchestrator} phase 4 (backend {@link
 * deal.codegen.Backend#JVM}) writes the {@code .java} source (it does not
 * run {@code javac}/{@code java}, which is exactly why the backend
 * guarantees every artifact it emits is valid Java).
 *
 * <p>Semantic slice scope (ISSUE-0091 skeleton + ISSUE-0092 slice +
 * ISSUE-0093 slice + ISSUE-0094 slice + ISSUE-0095 classes +
 * ISSUE-0096 slice + ISSUE-0097 stdlib-boundary slice + ISSUE-0108
 * nullable slice + ISSUE-0100 host ABI slice):
 * functions, {@code let} locals, module fields, literals,
 * int/number/boolean/string arithmetic and comparisons, {@code if}/
 * {@code else}, {@code while} loops, {@code return}, assignment, direct
 * calls, template literals (lowered to string concatenation), the
 * {@code int()}/{@code number()} conversion intrinsics, {@code std/console}
 * output ({@code console.log}/{@code console.error} → {@code System.out}/
 * {@code System.err}), local {@code class} declarations (generated nested
 * static classes with primitive and nullable fields), object-literal
 * class construction, primitive/nullable field reads/writes, minimal
 * {@code table} values, the same-module nominal runtime checks that table
 * boundary reads require (see the ISSUE-0095 paragraph below), primitive
 * arrays — {@code int[]}, {@code number[]}, {@code string[]},
 * {@code boolean[]} — as literals, index reads, element writes, and
 * {@code .length} reads, the nullable value slice (ISSUE-0108):
 * {@code T | null} for the four primitives and local classes in locals,
 * module fields, class fields, parameters, and returns (boxed
 * {@code java.lang.Long}/{@code Double}/{@code Boolean} for the numeric
 * primitives, plain references otherwise), null-check branch narrowing
 * (narrowed reads unbox through the sound {@code !== null} guard),
 * {@code T[] | null} and {@code (T | null)[]} for the primitive and
 * local-class element types, class arrays {@code C[]}, the nullable
 * table-read boundary checks (null passes, a value of the inner type
 * passes, anything else → E8001), and the {@code int}/{@code number}
 * nullable conversion overloads (null → E8001), multi-module
 * compilation (ISSUE-0096): namespace imports
 * ({@code import * as alias from "./lib"}) of compiled project modules,
 * exported functions, and imported direct calls ({@code alias.fn(args)} →
 * a static call on the imported module's emitted class), imported
 * classes and cross-module nominal identity (ISSUE-0109):
 * {@code export class} emits the same generated nested class as a local
 * one (the export only adds the class to the module type), imported
 * class-typed values map to the DECLARING module's generated class
 * ({@code Lib.$C_C} for {@code lib.C}), imported-class construction
 * emits {@code new Lib.$C_<Name>(args)} with declared literal defaults
 * applied inline and provided fields reordered to declaration order,
 * imported qualified type annotations ({@code let p: lib.Point})
 * resolve through the import alias, and a class-typed table read runs
 * the DECLARING module's nominal-check helper ({@code Lib.$checkC(...)})
 * whose {@code $identityOf} unwrap reports the actual module-qualified
 * identity of a foreign instance ({@code expected instance of
 * @modelb/Item, got @modela/Item} — E8001), and the stdlib
 * modules whose declared functions use only those prerequisite value
 * types (ISSUE-0097): {@code std/console} (already the trusted builtin),
 * {@code std/string}, {@code std/math}, and {@code std/time} — every
 * function of those four modules executes with the LuaJIT reference
 * semantics of {@code std/*.lua} for search/replace/{@code trim} and
 * with the spec-v1.2 Unicode scalar-value positions for
 * {@code length}/{@code substring}/{@code split} (ISSUE-0106),
 * {@code sqrt} of a negative number → {@code E8001},
 * {@code nowMillis()} → second-truncated epoch milliseconds like
 * {@code os.time() * 1000}). {@code std/table} and
 * {@code std/json} stay rejected with {@code E6000} at the import
 * statement: their only functions take or return a {@code table}, a
 * value type the slice still does not support as a function parameter
 * or return. Anything outside this scope — optional/array/class/
 * table-typed (non-nullable) class fields, nested class declarations,
 * table reads with primitive (non-nullable)/function target types,
 * table field writes, non-literal default expressions on imported
 * classes (their defaults evaluate in the declaring module's scope
 * under LuaJIT), arrays of non-primitive non-class elements,
 * {@code table | null} values, nullable tables, nested
 * (multi-dimensional) arrays, function arrays, stdlib imports other
 * than the four supported
 * modules, {@code @jsonable}, array for-of loops, for loops,
 * break/continue, try/throw — is rejected with a backend {@code E6000}
 * diagnostic, never silently miscompiled. The ISSUE-0100 host ABI
 * slice lifts declaration/host-module imports and async/await from
 * that list: a host-module import (a declaration file that is not a
 * spec stdlib module, supplied through the orchestrator's hostModules
 * map — the same classification the LuaJIT use site builds,
 * host-module-abi D5) emits per-alias wrapper methods whose load-time
 * presence check raises E8011 for a missing host class or a missing
 * declared export (extra host exports are ignored), whose sync calls
 * runtime-check the host return against the declared descriptor
 * (E8010 wrong kind, including Java null crossing a non-nullable
 * boundary; E8004 out-of-safe-range int; Java null is the DEAL null
 * sentinel for {@code T | null}), and whose async calls require a
 * {@code java.util.concurrent.CompletableFuture} operation (E8010
 * otherwise) whose completion value is checked at the await site
 * (E8001). Async function DECLARATIONS emit as plain blocking methods
 * (the spec-permitted JVM async lowering); await of a host async call
 * blocks on the operation. Host class exports and
 * array/table/function-typed host parameters and returns stay E6000 at
 * the import statement. ISSUE-0106 adds Unicode scalar-value STRING
 * for-of — one string containing exactly one scalar value per
 * iteration, a fresh binding per iteration (spec-v1.2 §For-of) — and
 * moves the std/string length/substring/split helpers to scalar-value
 * positions (spec-v1.2 §std/string: lengths and positions are
 * measured in Unicode scalar values).
 *
 * <p>Stdlib calls (ISSUE-0097) emit either an inline Java-library
 * expression (plain-text {@code contains}/{@code startsWith}/
 * {@code endsWith} on the mapped {@code java.lang.String};
 * {@code java.lang.Math.floor/ceil/abs/min/max}; the second-truncated
 * {@code System.currentTimeMillis()} form for {@code nowMillis}) or a
 * call to an emitted {@code __str*}/{@code __mathSqrt} runtime helper
 * (Unicode scalar-value {@code length}/{@code substring}/{@code split}
 * — ISSUE-0106: {@code length} counts code points,
 * {@code substring} positions are scalar values, and the
 * empty-separator {@code split} yields one part per scalar value;
 * plain-text {@code replace} with the empty-{@code old} guard, the
 * Lua-whitespace {@code trim}, and the negative-input {@code E8001}
 * check of {@code sqrt}). Every helper name starts with {@code __},
 * which {@link #javaName} can never produce (each DEAL underscore
 * escapes to {@code $u}), so a user function can never collide with a
 * stdlib helper. Java's own left-to-right argument evaluation preserves
 * the spec's evaluation order: the helpers are plain static calls whose
 * Java arguments evaluate left to right before the helper body runs,
 * exactly where LuaJIT evaluates the stdlib wrapper's arguments.
 *
 * <p>Module imports (ISSUE-0096) emit a load-time initialization trigger:
 * the import statement becomes {@code static { <ImportedClass>.__init$();
 * }} at the import's source position, and invoking a static method of a
 * class triggers that class's initialization (JLS §12.4.1), which runs the
 * imported module's load-time statements (its own static initializers and
 * field initializers) exactly where LuaJIT runs {@code require} —
 * depth-first in import order, before the importing module's later
 * load-time statements. The trigger is emitted even for an
 * imported-but-unused alias, so the imported module's load-time side
 * effects are never silently dropped. Java's at-most-once class
 * initialization matches LuaJIT's "modules initialize at most once per
 * runtime instance", and runtime import cycles are rejected before codegen
 * by the orchestrator (E2005), so no Java initialization cycle can occur
 * (declaration-only cycles only ever call each other's empty {@code
 * __init$}, which Java's in-progress initialization rule handles without
 * deadlock).
 *
 * <p>Primitive arrays (ISSUE-0094) map to emitted mutable wrapper
 * classes ({@code __IntArray}/{@code __NumberArray}/{@code __StringArray}/
 * {@code __BooleanArray} holding a primitive Java array), the spec's
 * "specialized primitive array wrapper" JVM representation. The wrapper
 * identity is stable across appends — writing at {@code i == length}
 * grows the wrapped storage in place — so aliases observe every write
 * exactly like LuaJIT's shared 1-based table ({@code let b: int[] = a;}
 * then {@code b[0] = 9;} is visible through {@code a}). Array reads
 * check the index: negative → {@code E8002} (LuaJIT's emitted
 * negative-index check), past the end → {@code E8001} "expected
 * &lt;T&gt;, got null" (LuaJIT reads nil there, and the read site's
 * typed boundary raises {@code E8001} — the JVM read reproduces the
 * boundary failure directly, since a primitive Java array cannot yield
 * nil). Array writes check {@code 0 &lt;= i &lt;= length}
 * ({@code E8002} otherwise), append at {@code i == length}, and
 * runtime-check the stored value against the element type — int
 * elements route through {@code checkInt} ({@code E8004}); the JVM
 * static type system proves the number/string/boolean element checks
 * redundant, which spec-v1.2 §JVM backend contract permits. Evaluation
 * order follows spec-v1.2 §Operational semantics: the receiver, the
 * index, and the assignment RHS all evaluate (left to right) before the
 * LHS write check — the emitted write is a helper call whose Java
 * arguments evaluate left to right before the helper performs the
 * bounds check, element check, and store, and {@code emitOperandsInOrder}
 * keeps that order when an operand hoists side-effecting pre-statements.
 *
 * <p>While loops (ISSUE-0092) emit plain Java {@code while} loops. The
 * condition routes through the emitted {@code loopCond} identity helper so
 * javac never sees a constant-expression condition: per JLS §14.21 a
 * constant-true condition would make statements after the loop
 * unreachable (a javac error after the CLI reported success) and a
 * constant-false condition would make the loop body unreachable. A
 * condition whose evaluation hoisted side-effecting pre-statements (a
 * null-typed call) is emitted as {@code while (true) { pre; if
 * (!loopCond(cond)) break; body }} so the pre-statements — and therefore
 * the whole condition — re-evaluate on every iteration, exactly where
 * LuaJIT re-evaluates the condition each time. The while body is its own
 * scope (block-local {@code let}s match LuaJIT's per-body scope), and
 * {@code while (false)} bodies are emitted (reachable to javac because of
 * {@code loopCond}) and simply never run, matching LuaJIT. Module-level
 * while loops run inside the load-time {@code static} initializer, and a
 * {@code return} nested anywhere inside a module-level while body is
 * rejected with E6000 (Java initializers cannot return).
 *
 * <p>Template literals (ISSUE-0092) emit Java string concatenation over
 * the literal and interpolated parts; interpolated expressions are
 * string-typed by the checker (E3016 otherwise), so no runtime conversion
 * exists — the emitted form is {@code ("a" + expr + "b")}, with empty
 * literal parts elided and single-part templates emitted as the literal
 * itself.
 *
 * <p>Load-time semantics are preserved: non-declaration module-level
 * statements are emitted into {@code static} initializer blocks interleaved
 * in source order with field initializers, exactly where LuaJIT executes
 * them. Null-typed expressions with side effects (e.g.
 * {@code let z: null = console.log("x")}, {@code return helper()},
 * {@code x = console.log("y")} where {@code x: null}) are evaluated for
 * their observable behavior — never discarded — by hoisting the void call
 * into a pre-statement emitted right before the containing statement
 * ({@code System.out.println("x"); Void z = null;} semantics). When a
 * hoisted call has an earlier inline side-effecting sibling in the same
 * statement, that sibling is materialized into a fresh {@code __t<n>}
 * temporary assigned immediately before the hoisted statement, so
 * DEAL/LuaJIT's left-to-right evaluation order holds in every
 * combination position ({@code f(g(), console.log("x"))} runs
 * {@code g()} before the print — including operands that can raise,
 * which must raise before the hoisted call runs). No lambdas are ever
 * emitted, so the artifact stays valid Java even when the call captures
 * locals or parameters that are reassigned later in their scope (a
 * lambda capture of a non-effectively-final local is a javac error).
 * Hoisted side effects inside a non-leading {@code &&}/{@code ||} operand
 * are guarded by the left operand (Java's short-circuit semantics — LuaJIT
 * skips the right operand when the left already decides the result) with
 * a boolean temporary, so {@code false && helper() === null} never calls
 * {@code helper()}. Module-level (load-time) value uses of a variable
 * before its own declaration with no enclosing binding (LuaJIT reads the
 * not-yet-declared global value there — nil unless a prior write
 * established it), forward references to later-declared module fields
 * (Java's illegal-forward-reference rule), writes to a later-declared
 * function-local with no enclosing binding ({@code x = 5; let x: int = 1}
 * inside a function — LuaJIT writes the enclosing scope; Java rejects the
 * forward reference; module-level writes to later-declared fields stay
 * allowed, see below), module-level calls
 * to functions whose bodies (transitively) read a module field declared
 * later than the call site (LuaJIT fails at load with a nil read; Java
 * would silently read the field's default value), and module-level calls
 * that reach a function declared at or after the call site — directly,
 * transitively, or from a field initializer (LuaJIT assigns each function
 * value at its declaration point in source order and fails at load with a
 * nil read; Java hoists methods and would silently run) — are rejected
 * with {@code E6000} so the artifact is always valid Java and never
 * silently miscompiled. Function-body access to a module field declared
 * AFTER the function — read or write — is rejected with E6000 by the
 * write-dominance analysis (see {@link #computeForwardFieldViolations}):
 * LuaJIT does not capture the module-local in a pre-declaration function
 * (the local does not exist when the function value is created), so
 * <em>every</em> access binds to the GLOBAL of the same name at call
 * time. A read without a dominating write inside the function reads the
 * global nil and fails (E8001) while Java would silently read the
 * initialized static field; a write targets LuaJIT's global — the
 * module-local is untouched, so later readers observe the initializer
 * value — while Java would write the static field and pollute every later
 * reader. The write-then-read shape ({@code x = 5; return x;}) is
 * included: the function's own read observes the global write under
 * LuaJIT, but the polluted Java field remains observable by later
 * readers. Reads are governed by dominance along every execution path
 * (a write inside a called function or a taken-only branch does not
 * establish dominance — conservative). Writes to module fields declared
 * BEFORE the function remain the upvalue/static-field write with full
 * parity (pinned by a cross-backend fixture), and module-level writes to
 * later-declared fields stay allowed: LuaJIT's global write is
 * overwritten by the initializer, Java's static-field write is
 * overwritten by the field initializer, and every observer of the
 * intermediate value (module-level pre-declaration reads, pre-declaration
 * function accesses) is already E6000, so the final value is identical on
 * both backends. Dead code after a statement that
 * cannot complete
 * normally — a {@code return}, or an {@code if}/{@code else} whose
 * branches all cannot complete normally (mirroring JLS §14.21) — is never
 * emitted: LuaJIT never executes it and javac rejects it as unreachable,
 * so skipping keeps the artifact valid Java for every checker-accepted
 * program.
 * Standalone non-call/non-assignment expression statements (e.g.
 * {@code x + 1;}) are lowered to a dummy-local declaration so they are
 * evaluated exactly like LuaJIT evaluates them (an int overflow there is
 * an observable E8004), {@code null === null} / {@code z === null}
 * compare with Java's {@code ==}/{@code !=} (all null-typed values are the
 * DEAL null value; spec §Value equality defines {@code null === null} as
 * true), number literals that overflow to ±Infinity render as
 * {@code Double.POSITIVE_INFINITY}/{@code Double.NEGATIVE_INFINITY}
 * (mirroring the Lua backend's {@code (1/0)} — Java has no literal
 * spelling for Infinity), and string ordering compares Unicode scalar
 * values (LuaJIT orders bytewise in UTF-8, which is scalar-value order),
 * including supplementary characters.
 * The DEAL int safe range ±(2^53-1) is enforced at every int-producing
 * site — the emitted {@code checkInt} helper wraps the results of
 * {@code intAdd}/{@code intSub}/{@code intMul}/{@code intDiv}/{@code intMod}/
 * {@code intNeg}/{@code intPow}/{@code intFromNumber} exactly like
 * {@code __rt.check_int} (deal/runtime.lua) wraps {@code __rt.int_add} etc.,
 * and an int literal outside the safe range is checked at its point of use
 * ({@code return 9223372036854775807;} raises E8004, matching the LuaJIT
 * return-boundary check; LuaJIT would silently round such a literal to a
 * double inside arithmetic like {@code 9223372036854775807 % 2}, which the
 * JVM backend refuses to reproduce — it raises E8004 instead of silently
 * computing with a value that is not a valid DEAL int). All {@code java.lang}
 * references in generated code are fully qualified ({@code java.lang.System},
 * {@code java.lang.Math}, {@code java.lang.Double}, {@code java.lang.String},
 * {@code java.lang.Void}, …): DEAL identifiers may be named
 * {@code System}/{@code Math}/{@code Double}/{@code String}/{@code Void}
 * (non-reserved names pass {@link #javaName} unchanged), and an unqualified
 * reference would bind to the user's field or local instead of
 * {@code java.lang}, producing an artifact javac rejects. Use-before-
 * declaration detection walks every condition of an {@code if}/{@code
 * else if} chain (the follow-on conditions are emitted directly by
 * {@code emitIfContinuation}, bypassing the per-statement guard), so a
 * later-declared variable in an {@code else if} condition is rejected
 * with E6000 instead of emitting an illegal forward reference.
 *
 * <p>JVM value mapping follows the spec's JVM backend contract
 * ({@code docs/spec-v1.2.md} §JVM value mapping / §JVM backend contract —
 * the normative v1.2 spec):
 * {@code int → long} (the v1.1 backend-supported safe range; the
 * ISSUE-0111 signed-int32 migration owns the representation switch),
 * {@code number → double}, {@code boolean → boolean}, {@code string →
 * String} (a {@code java.lang.String} with no unpaired UTF-16 surrogate
 * code units — scalar-string boundaries validate the encoding, ISSUE-0106),
 * {@code null → void}/{@code Void}. The JVM's static type system proves
 * typed boundaries redundant, which the spec explicitly permits ("The JVM
 * backend may use JVM primitive types, final classes, verifier-checked
 * bytecode … to prove typed-boundary checks redundant"); the int safe
 * range is still enforced at runtime because it is observable behavior
 * (E8004) that the type system cannot prove.
 *
 * <p>The emitted class is a module class; when the compilation selected
 * this module as the entry module, it additionally carries the JVM entry
 * point {@code public static void main(String[] args)} that invokes the
 * DEAL {@code main} export (spec-v1.2 §No user-defined globals — see
 * {@link #generate(ProgramNode, CheckResult, String, String, Map, Map,
 * boolean)}). The conformance adapter compiles it together with a small
 * runner class that auto-invokes the zero-arity exported functions in
 * declaration order (the Lua harness iterates {@code pairs()} — an
 * unspecified order — so fixtures must not depend on cross-backend
 * invocation order) and prints non-{@code null} results.
 *
 * <p>Local classes and nominal checks (ISSUE-0095) add the DEAL v1.1
 * nominal record class surface: module-level {@code class} declarations,
 * object-literal construction in class-typed contexts (defaults applied
 * per construction), primitive field reads/writes, and same-module
 * nominal runtime checks. Each DEAL class emits a generated nested
 * static class ({@code $C_<name>}, extending the emitted {@code $Base}
 * identity holder); spec-v1.2 classes are sealed records with no
 * methods and no constructors, so there is no method surface beyond the
 * module functions the earlier slices already support. The only in-slice
 * untyped boundary is the DEAL table ({@code table} read in a contextual
 * target type — the checker types such reads with the expected target),
 * so tables emit a minimal ordered string-key map ({@code $T}) and a
 * class-typed table read is the one site where a runtime nominal check
 * cannot be proven redundant: {@code $check<C>} verifies the value is a
 * {@code C} instance and raises E8001 otherwise (mirroring LuaJIT's
 * {@code __rt.check_type} class branch: "expected instance of @mod/C,
 * got …" for a wrong-class value, "expected class instance" for a
 * non-class value). The nullable slice (ISSUE-0108) extends this
 * boundary: a nullable-typed table read ({@code C | null}, {@code
 * int | null}, {@code T[] | null}) passes the DEAL null through and
 * otherwise runs the inner check — the nullable nominal class check, a
 * nullable primitive check, or the per-wrapper array gate (all unified
 * behind the {@code $check(descriptor, value)} seam by ISSUE-0110 —
 * see below). Every other class/nullable-typed
 * boundary in the slice
 * (locals, parameters, returns, field reads/writes, construction) is
 * provably typed by the JVM's static type system, which spec-v1.2
 * §JVM backend contract explicitly permits to make typed-boundary checks
 * redundant.
 *
 * <p>Imported classes and cross-module nominal identity (ISSUE-0109)
 * extend the same machinery across module boundaries: an exported class
 * emits the same nested class and check helper as a local one, and the
 * importing module references them through the declaring module's
 * emitted class ({@code Lib.$C_<name>}, {@code Lib.$check<Name>}). The
 * generated nested classes are package-private and every emitted
 * artifact shares the default package, so those references are exactly
 * what javac compiles. Locality is always decided from the
 * {@code Type.Class} MODULE PATH, never the bare class name — a
 * same-named local class never satisfies the guard for a foreign path.
 * Imported construction applies declared literal defaults inline (a
 * non-literal default is E6000: it evaluates in the declaring module's
 * scope under LuaJIT). A wrong-module instance failing a nominal check
 * is not an instance of the checking module's own {@code $Base}, so the
 * check helper reads the actual identity through the emitted
 * {@code $identityOf} unwrap (structural read of the spec ClassDescriptor
 * every {@code $Base} carries) and reports
 * "expected instance of @modelb/Item, got @modela/Item" — exactly the
 * module-qualified identity LuaJIT's {@code actual_class} reports.
 * Out of slice: optional/array/class/table-typed (non-nullable)
 * class fields, nested class declarations, table reads with primitive
 * (non-nullable)/function target types, table field writes, non-literal
 * defaults on imported classes — all E6000, never silently miscompiled.
 *
 * <p>Descriptor and cross-feature integration join (ISSUE-0110) unifies
 * every runtime check the prerequisite slices introduced behind ONE
 * shared descriptor-driven seam: {@link #typeDescriptor(Type)} is the
 * single JVM type-descriptor emitter (spec {@code RuntimeTypeDescriptor}
 * format — primitives, {@code ?T}, {@code [T]}, {@code @module/Name},
 * function and {@code async} operation types),
 * and every untyped table-read boundary emits one call to the single
 * emitted helper {@code $check(descriptor, value)} whose branches carry
 * the exact acceptance semantics the retired per-feature helpers pinned
 * ({@code check_int}/{@code check_nullable}/{@code check_array} parity,
 * the module-qualified nominal messages, and the deterministic
 * {@code "expected <descriptor>, got …"} mismatch shape for anything
 * else). Local classes dispatch in-module; imported classes dispatch on
 * the DECLARING module's seam ({@code Lib.$check("@lib/C", v)}), so the
 * nominal identity machinery stays exactly where ISSUE-0109 placed it.
 * Class identity strings, the seam's branch keys, and the call-site
 * descriptor literals all flow from the same emitter — one spelling, no
 * drift. Function and async-operation descriptors are spelled by the
 * emitter for the later function-value (ISSUE-0098) and async
 * (ISSUE-0099) slices to consume; no untyped function-value boundary
 * exists in this slice yet.
 */
public final class JvmBackend {

    /**
     * Result of JVM code generation: the public class name (the
     * {@code .java} file name is {@code className + ".java"}), the generated
     * Java source, and any backend diagnostics. {@link #hasErrors()} gates
     * compilation of the artifact.
     */
    public record JvmCodegenResult(String className, String source,
                                   List<Diagnostic> diagnostics) {
        public JvmCodegenResult {
            Objects.requireNonNull(className, "className must not be null");
            Objects.requireNonNull(source, "source must not be null");
            diagnostics = List.copyOf(diagnostics);
        }

        /** True when at least one error-level diagnostic was recorded. */
        public boolean hasErrors() {
            return diagnostics.stream()
                .anyMatch(d -> "error".equals(d.severity()));
        }
    }

    // =========================================================================
    // State
    // =========================================================================

    private final Map<ExpressionNode, Type> typeMap;
    private final SymbolTable symbols;
    private final String sourcePath;
    private final String modulePath;
    private final boolean isEntry;
    private final List<Diagnostic> diagnostics = new ArrayList<>();
    private final StringBuilder out = new StringBuilder();
    private int indent = 0;

    /**
     * Import alias → module path of the imported module ({@code console →
     * std/console}, {@code str → std/string}, {@code math → std/math},
     * {@code time → std/time} for the supported stdlib builtins,
     * {@code lib → lib} for a compiled project module). The pre-scan
     * records an alias only for the supported stdlib modules
     * ({@link #SUPPORTED_STDLIB_MODULES}) and for imports present in
     * {@link #importResolutions}; every other import — a spec stdlib
     * module whose functions need {@code table} values
     * ({@code std/table}, {@code std/json}) or a declaration/host module
     * — is rejected with E6000 at the import statement (ISSUE-0091
     * rework, ISSUE-0096, ISSUE-0097).
     */
    private final Map<String, String> importAliases = new LinkedHashMap<>();

    /**
     * The stdlib modules whose declared functions the JVM slice can
     * execute (ISSUE-0097): every export of these modules is typed only
     * with prerequisite value types the slice already supports (int,
     * number, boolean, string, string[]). {@code std/console} was the
     * trusted builtin since ISSUE-0091; {@code std/string},
     * {@code std/math}, and {@code std/time} join it.
     */
    private static final Set<String> SUPPORTED_STDLIB_MODULES = Set.of(
        "std/console", "std/string", "std/math", "std/time");

    /**
     * The spec-listed stdlib modules whose only functions take or return
     * a {@code table} — a value type the JVM slice does not support
     * (tables are E6000). Importing one is rejected at the import
     * statement with E6000, even when unused: none of its functions can
     * ever execute in this slice, and its require-time module object has
     * no JVM equivalent.
     */
    private static final Set<String> TABLE_BOUNDARY_STDLIB_MODULES = Set.of(
        "std/table", "std/json");

    /**
     * Raw import path → module path of the imported compiled module
     * ({@code "./lib" → lib}), supplied by {@code CompilationOrchestrator}
     * phase 4 — the same mapping the LuaJIT use site builds. Only imports
     * present here are accepted as project-module imports; declaration
     * files and host modules are never entries and stay E6000 at the
     * import statement.
     */
    private final Map<String, String> importResolutions;

    /**
     * Imported module path → (class name → class declaration) for every
     * compiled project module this module imports (ISSUE-0109): the
     * imported module's {@code ClassDeclaration}s carry the field order,
     * field shapes, and default expressions that imported-class
     * construction needs. Supplied by {@code CompilationOrchestrator}
     * phase 4 (the same discovery pass that builds
     * {@link #importResolutions}); empty for the single-module harness
     * overloads, where no imported class can exist.
     */
    private final Map<String, Map<String, ClassDeclaration>> importedClasses;

    /**
     * Raw import path → declared export map (export name → declared
     * {@link Type}) for every host module this module imports (ISSUE-0100).
     * Supplied by {@code CompilationOrchestrator} phase 4, exactly like the
     * LuaJIT use site's hostModules map (host-module-abi D4/D5): imports of
     * declaration files that are not spec stdlib modules are host modules.
     * A raw path present here is a host import: its alias maps to a Java
     * host module class (named {@link #classNameFor} of the raw path) whose
     * static methods implement the declared function exports. Declaration
     * and host-module imports previously stayed E6000 at the import
     * statement; the host ABI slice supports declared function exports with
     * the primitive/string/nullable parameter and return shapes below.
     */
    private final Map<String, Map<String, Type>> hostModules;

    /**
     * Import alias → raw import path of a host-module import
     * ({@code http → host/http}), recorded by the pre-scan for every
     * import present in {@link #hostModules} whose declared exports the
     * slice supports. Member calls through the alias emit calls to the
     * per-alias wrapper methods ({@code __host$<alias>$<fn>}); the import
     * statement emits a {@code static { __hostLoad$<alias>(); }} block
     * whose load-time reflection presence check raises E8011 for a missing
     * host module class or a missing declared export.
     */
    private final Map<String, String> hostAliases = new LinkedHashMap<>();

    /**
     * Stack of visible local-variable bindings (DEAL name → emitted Java
     * name). The bottom scope is the module scope (module-level {@code let}s,
     * recorded in declaration order — the checker resolves them
     * sequentially); function bodies push a scope with their parameters;
     * blocks push/pop scopes. Mirrors the NameResolver's hierarchical symbol
     * table for the constructs the skeleton supports, so an identifier is
     * classified as a variable (vs. a hoisted function or intrinsic) exactly
     * when the checker would.
     *
     * <p>Shadowing locals get disambiguated names ({@code x$1}, {@code x$2},
     * …): DEAL allows lexical shadowing, but Java rejects redeclaring a
     * visible local. The {@code $n} suffix can never collide with a
     * translation ({@link #javaName} escapes every {@code $} as {@code $d}).
     * The disambiguation compares EMITTED Java names across every visible
     * scope (ISSUE-0093 fix): a parameter shadowing a module field is
     * declared as {@code x$1} in BOTH the method signature and the body
     * (the signature previously emitted the raw translation while the body
     * read the disambiguated name — an artifact javac rejected), and a
     * shadowing declaration never reuses an enclosing binding's emitted
     * name, so {@code let x: int = x + 1} over a parameter reads the
     * parameter, exactly like LuaJIT's {@code local x = x + 1}.
     */
    private final Deque<Map<String, String>> localScopes = new ArrayDeque<>();

    /**
     * Declared DEAL type per binding scope, parallel to {@link
     * #localScopes}: every entry records the declared type of the
     * binding of the same DEAL name in the corresponding scope map.
     * Nullable-slice reads consult this stack to adapt narrowed
     * identifier reads to the binding's boxed (nullable) Java
     * representation (ISSUE-0108).
     */
    private final Deque<Map<String, Type>> localTypeScopes = new ArrayDeque<>();

    /** The declared return type of the function currently being emitted
     * ({@code null} at module level). {@link #emitReturn} uses it to
     * emit {@code return null;} for {@code null}-typed return expressions
     * inside nullable-returning functions (ISSUE-0108). */
    private Type currentReturnType = null;

    /**
     * Every emitted Java binding name declared inside the current function
     * (parameters + function locals, in declaration order): one set per
     * function, pushed together with the function's parameter scope and
     * popped together with it. {@link #declareLocal} OVERWRITES a scope-map
     * key when a function-body top-level {@code let} shadows a parameter of
     * the same DEAL name (parameters and body-top lets share the function's
     * scope map), and the overwritten value — the parameter's emitted name,
     * still in Java scope for the rest of the method (JLS §6.4: an inner
     * block may not redeclare a method parameter) — must remain reserved
     * for collision purposes even though no scope map holds it any more.
     * {@link #emittedNameVisible} consults these sets in addition to the
     * current scope values, so a deeper shadow never reuses it: in the
     * triple-deep chain <pre>let g: int = 1;
     * function f(g: int): int {
     *   let g: int = g + 10;
     *   { let g: int = g + 1; }
     *   return g;
     * }</pre> the inner block used to emit {@code g$1} over the
     * parameter's {@code g$1} — an artifact javac rejected ("variable g$1
     * is already defined in method f(long)") after the CLI reported
     * success (ISSUE-0093 rework). Nested function declarations are
     * rejected before this machinery runs, so at most one set is ever on
     * the deque.
     */
    private final Deque<Set<String>> functionBindingNames = new ArrayDeque<>();

    /** True while emitting direct module-body statements (class members). */
    private boolean moduleLevel = false;

    /**
     * Statements hoisted out of value positions (null-typed void calls that
     * must run for their observable side effects). They are emitted in
     * evaluation order right before the containing statement, preserving
     * DEAL's left-to-right evaluation order without ever emitting a lambda
     * (a lambda capturing a later-reassigned local would make javac reject
     * the artifact).
     */
    private final List<PreLine> preStatements = new ArrayList<>();

    /**
     * One hoisted pre-statement line. {@code extraIndent} is the relative
     * indentation beyond the flush site's indent (0 for a plain statement;
     * 1 for a line inside a guarded {@code if} block).
     */
    private record PreLine(String text, int extraIndent) {}

    /**
     * True while {@link #preStatements} contains a temporary declaration
     * that the containing expression references (a guarded
     * {@code &&}/{@code ||} lowering). A module-level field initializer
     * cannot reference a local of a separate static block, so
     * {@link #emitVariable} routes such initializers through a
     * static-block assignment instead. Reset by
     * {@link #flushPreStatements()}.
     */
    private boolean preStatementsDeclareTemps = false;

    /** Counter for dummy-locals that force evaluation of standalone
     * expression statements ({@code __ignored}, {@code __ignored1}, …).
     * The {@code __} prefix can never collide with a translation:
     * {@link #javaName} maps every leading underscore to {@code $u}. */
    private int ignoredCounter = 0;

    /** Counter for short-circuit temporaries ({@code __sc0}, {@code __sc1},
     * …). Unreachable from {@link #javaName} for the same reason. */
    private int shortCircuitCounter = 0;

    /** Counter for evaluation-order temporaries ({@code __t0}, {@code __t1},
     * …): inline operands materialized before a later hoisted
     * pre-statement so DEAL's left-to-right evaluation order holds.
     * Unreachable from {@link #javaName} for the same reason. */
    private int evalTempCounter = 0;

    /** Counter for string for-of loop temporaries ({@code __iter0},
     * {@code __i0}, {@code __cp0}, …). Sequential sibling loops share the
     * enclosing Java block scope, so every loop's temporaries need a
     * unique name; the {@code __} prefix is unreachable from
     * {@link #javaName}. */
    private int forOfCounter = 0;

    /** Module-level function declarations by name (exports included), in
     * declaration order. */
    private final Map<String, FunctionDeclaration> moduleFunctions =
        new LinkedHashMap<>();

    /** Module-level function declaration indices by name (exports
     * included) → statement index in the module body. */
    private final Map<String, Integer> moduleFunctionIndices =
        new LinkedHashMap<>();

    /** Module-level variable declarations by name → statement index in the
     * module body. */
    private final Map<String, Integer> moduleFieldIndices =
        new LinkedHashMap<>();

    /** Module-level (non-exported) class declarations by name, in
     * declaration order ({@code export class} stays E6000 — the module ABI
     * surface is a later slice). Registered in the pre-scan, before any
     * emission, so construction sites before the declaration resolve. */
    private final Map<String, ClassDeclaration> moduleClasses =
        new LinkedHashMap<>();

    /**
     * One Java branch per declared class for the shared descriptor-driven
     * runtime-check seam (ISSUE-0110): each {@code emitClass} run appends
     * the {@code descriptor.equals(...)} branches for its class descriptor
     * ({@code @module/Name}), its array descriptor ({@code [@module/Name]}),
     * and its nullable-element array descriptor ({@code [?@module/Name]});
     * {@link #emitSharedCheckSeam} emits them inside the single emitted
     * {@code $check(descriptor, value)} helper, which is emitted AFTER the
     * module body so every declared class contributes its branches.
     */
    private final List<String> classCheckBranches = new ArrayList<>();

    /** Function name → module fields read by its body, transitively through
     * calls to other module functions (use-before-declaration detection for
     * module-level calls). */
    private final Map<String, Set<String>> transitiveFieldReads =
        new LinkedHashMap<>();

    /** Function name → all module functions reachable from its body
     * through calls, including the function itself (use-before-declaration
     * detection for module-level calls). */
    private final Map<String, Set<String>> transitiveFunctionCalls =
        new LinkedHashMap<>();

    /** Function name → a module field declared after the function that its
     * body READS without a dominating write inside the function
     * (write-dominance analysis; see
     * {@link #computeForwardFieldViolations}). Emitting such a function is
     * an E6000: LuaJIT fails at call time reading the global nil (E8001)
     * while Java would silently read the initialized static field. */
    private final Map<String, String> forwardReadViolations =
        new LinkedHashMap<>();

    /**
     * Function name → set of import aliases its body references
     * transitively (through same-module calls). Used to reject a
     * module-level call of a function whose body reaches an import
     * declared at or after the call site: LuaJIT has not run the require
     * yet and fails at load, while Java would silently initialize the
     * imported class (ISSUE-0096).
     */
    private final Map<String, Set<String>> transitiveImportReads =
        new LinkedHashMap<>();

    /** Function name → a module field declared after the function that its
     * body WRITES (write-dominance analysis; see
     * {@link #computeForwardFieldViolations}). Emitting such a function is
     * an E6000: LuaJIT binds the pre-declaration write to the GLOBAL of
     * the same name — the module-local does not exist when the function
     * value is created — leaving the module-local untouched, so later
     * readers (functions declared after the field, exported functions)
     * observe the initializer value; Java would write the static field
     * and pollute every later reader. The write-then-read shape
     * ({@code x = 5; return x;}) is included: the function's own read
     * observes the global write under LuaJIT, but the polluted Java field
     * remains observable by later readers. */
    private final Map<String, String> forwardWriteViolations =
        new LinkedHashMap<>();

    /** Statement index of the module-level statement currently being
     * emitted ({@code -1} inside function bodies). Used to detect
     * module-level calls that transitively read later-declared fields. */
    private int currentModuleStatementIndex = -1;

    /** Import alias → statement index of its import statement (project
     * modules only; {@code std/console} has no load-time trigger and no
     * entry). Used to reject module-level uses of an alias before its
     * import statement: LuaJIT emits the {@code require} at the import's
     * source position, so an earlier use reads the not-yet-required
     * global and fails at load, while Java would silently initialize the
     * imported class (ISSUE-0096). */
    private final Map<String, Integer> importAliasStatementIndices =
        new LinkedHashMap<>();

    private JvmBackend(Map<ExpressionNode, Type> typeMap, SymbolTable symbols,
                       String sourcePath, String modulePath,
                       Map<String, String> importResolutions,
                       Map<String, Map<String, ClassDeclaration>> importedClasses,
                       Map<String, Map<String, Type>> hostModules,
                       boolean isEntry) {
        this.typeMap = typeMap;
        this.symbols = symbols;
        this.sourcePath = sourcePath;
        this.modulePath = modulePath;
        this.isEntry = isEntry;
        this.importResolutions = importResolutions == null
            ? Map.of() : Map.copyOf(importResolutions);
        this.importedClasses = importedClasses == null
            ? Map.of() : Map.copyOf(importedClasses);
        this.hostModules = hostModules == null
            ? Map.of() : Map.copyOf(hostModules);
        localScopes.push(new LinkedHashMap<>());
        localTypeScopes.push(new LinkedHashMap<>());
    }

    // =========================================================================
    // Static entry points
    // =========================================================================

    /**
     * Generates Java source for a checked module. The module path defaults to
     * the source path.
     */
    public static JvmCodegenResult generate(ProgramNode program, CheckResult result,
                                            String sourcePath) {
        return generate(program, result, sourcePath, sourcePath, Map.of());
    }

    /**
     * Generates Java source for a checked module with an explicit module path.
     * The class name is derived from the full module path (collision-safe:
     * {@code app/main} and {@code sub/main} derive {@code AppMain} and
     * {@code SubMain}).
     */
    public static JvmCodegenResult generate(ProgramNode program, CheckResult result,
                                            String sourcePath, String modulePath) {
        return generate(program, result, sourcePath, modulePath, Map.of());
    }

    /**
     * Generates Java source for a checked module with an explicit module
     * path and the orchestrator's import resolution map (ISSUE-0096).
     *
     * @param importResolutions raw import path → module path of the
     *                          imported compiled module (e.g. {@code "./lib"
     *                          → lib}); an import whose raw path is absent
     *                          is rejected with E6000 at the import
     *                          statement
     */
    public static JvmCodegenResult generate(ProgramNode program, CheckResult result,
                                            String sourcePath, String modulePath,
                                            Map<String, String> importResolutions) {
        return generate(program, result, sourcePath, modulePath,
            importResolutions, Map.of());
    }

    /**
     * Generates Java source for a checked module with the orchestrator's
     * import resolution map and imported class declarations (ISSUE-0109).
     *
     * @param importResolutions raw import path → module path of the
     *                          imported compiled module (e.g. {@code "./lib"
     *                          → lib}); an import whose raw path is absent
     *                          is rejected with E6000 at the import
     *                          statement
     * @param importedClasses   imported module path → (class name → class
     *                          declaration); supplies the field order and
     *                          default expressions for imported-class
     *                          construction and the defensive locality
     *                          guard for imported class-typed values
     */
    public static JvmCodegenResult generate(ProgramNode program, CheckResult result,
                                            String sourcePath, String modulePath,
                                            Map<String, String> importResolutions,
                                            Map<String, Map<String, ClassDeclaration>> importedClasses) {
        return generate(program, result, sourcePath, modulePath,
            importResolutions, importedClasses, Map.of(), false);
    }

    /**
     * Generates Java source for a checked module with the orchestrator's
     * import resolution map, imported class declarations, host-module
     * declarations (ISSUE-0100), and entry status (ISSUE-0106).
     *
     * @param hostModules    raw import path → declared export map
     *                       (export name → declared {@link Type}) of the
     *                       host modules this module imports; an import
     *                       whose raw path is present here is a host-module
     *                       import (declaration files that are not spec
     *                       stdlib modules, mirroring the LuaJIT use site's
     *                       hostModules map — host-module-abi D4/D5).
     *                       Declared function exports with supported
     *                       parameter/return shapes emit per-alias wrapper
     *                       methods with load-time presence checks (E8011)
     *                       and call-time boundary checks (E8010/E8001);
     *                       unsupported declarations are E6000 at the
     *                       import statement, never silently miscompiled
     * @param isEntry        true when this module is the selected entry
     *                       module of the compilation (the orchestrator
     *                       passes this for {@code entryFile});
     *                       spec-v1.2 §No user-defined globals: when a
     *                       compiler invocation selects an entry module,
     *                       the backend invokes {@code main()} from that
     *                       module. The emitted entry module class
     *                       therefore gets a real JVM entry point —
     *                       {@code public static void main(String[] args)}
     *                       — that calls the module's emitted DEAL
     *                       {@code main} export (a {@code static void
     *                       main()} method; the checker has verified the
     *                       non-async {@code (): null} signature when the
     *                       compilation ran through the entry gate). The
     *                       DEAL {@code main} function name survives
     *                       {@link #javaName} unchanged, so the entry
     *                       method overloads the plain {@code main()}
     *                       without collision. Non-entry modules emit a
     *                       plain module class with no JVM entry point
     */
    public static JvmCodegenResult generate(ProgramNode program, CheckResult result,
                                            String sourcePath, String modulePath,
                                            Map<String, String> importResolutions,
                                            Map<String, Map<String, ClassDeclaration>> importedClasses,
                                            Map<String, Map<String, Type>> hostModules,
                                            boolean isEntry) {
        JvmBackend backend = new JvmBackend(result.typeMap(), result.symbolTable(),
            sourcePath, modulePath, importResolutions, importedClasses,
            hostModules, isEntry);
        return backend.generateProgram(program);
    }

    /**
     * Derives the public Java class name for a module path. Every path
     * segment contributes a capitalized, sanitized segment (ISSUE-0091
     * rework): {@code main → Main}, {@code app/main → AppMain},
     * {@code app.sub.main → AppSubMain}. This keeps distinct module paths
     * collision-free instead of silently overwriting one another's
     * artifacts (e.g. {@code app/main} and {@code sub/main} no longer both
     * derive {@code Main}). Falls back to {@code "Main"} for empty input.
     */
    public static String classNameFor(String modulePath) {
        String path = modulePath == null ? "" : modulePath;
        StringBuilder sb = new StringBuilder();
        for (String segment : path.split("[/.]")) {
            String cleaned = sanitizeSegment(segment);
            if (cleaned.isEmpty()) continue;
            sb.append(Character.toUpperCase(cleaned.charAt(0)))
                .append(cleaned.substring(1));
        }
        String result = sb.toString();
        if (result.isEmpty()) result = "Main";
        return JAVA_RESERVED.contains(result) ? result + "_" : result;
    }

    /** Sanitizes one module-path segment to a Java identifier. */
    private static String sanitizeSegment(String segment) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            boolean ok = Character.isJavaIdentifierPart(c);
            if (i == 0) ok = ok && Character.isJavaIdentifierStart(c);
            sb.append(ok ? c : '_');
        }
        return sb.toString();
    }

    // =========================================================================
    // Identifier translation
    // =========================================================================

    /**
     * Java reserved words. DEAL keywords are not identifiers, so this list is
     * exactly the Java keywords that can appear as DEAL identifiers.
     */
    private static final Set<String> JAVA_RESERVED = Set.of(
        "abstract", "assert", "boolean", "break", "byte", "case", "catch",
        "char", "class", "const", "continue", "default", "do", "double",
        "else", "enum", "extends", "final", "finally", "float", "for",
        "goto", "if", "implements", "import", "instanceof", "int",
        "interface", "long", "native", "new", "package", "private",
        "protected", "public", "return", "short", "static", "strictfp",
        "super", "switch", "synchronized", "this", "throw", "throws",
        "transient", "try", "void", "volatile", "while", "_",
        "true", "false", "null");

    /**
     * Emitted runtime-helper methods, by translated name → mapped Java
     * parameter types. A DEAL function whose translated name and mapped
     * parameter types match a helper exactly would emit a duplicate Java
     * method; such declarations are rejected with E6000 instead.
     */
    private static final Map<String, List<String>> RUNTIME_HELPER_SIGNATURES = Map.ofEntries(
        Map.entry("intAdd", List.of("long", "long")),
        Map.entry("intSub", List.of("long", "long")),
        Map.entry("intMul", List.of("long", "long")),
        Map.entry("intDiv", List.of("long", "long")),
        Map.entry("intMod", List.of("long", "long")),
        Map.entry("intPow", List.of("long", "long")),
        Map.entry("intNeg", List.of("long")),
        Map.entry("numMod", List.of("double", "double")),
        Map.entry("intFromNumber", List.of("double")),
        Map.entry("numberFromInt", List.of("long")),
        Map.entry("scalarCompare", List.of("java.lang.String", "java.lang.String")),
        Map.entry("checkInt", List.of("long")),
        Map.entry("__hasUnpairedSurrogate", List.of("java.lang.String")),
        Map.entry("loopCond", List.of("boolean")),
        Map.entry("booleanNotNull", List.of("java.lang.Boolean")),
        Map.entry("intFromNullable", List.of("java.lang.Long")),
        Map.entry("numberFromNullable", List.of("java.lang.Double")));

    /**
     * Translates a DEAL identifier to a Java identifier. The encoding is
     * injective and collision-free: {@code $} → {@code $d} and {@code _} →
     * {@code $u} first (escaped names never start with {@code _}), then Java
     * reserved words are prefixed with {@code _} (reserved-prefixed names
     * always start with {@code _}). The two output sets are disjoint, so a
     * genuine DEAL identifier can never collide with a translated reserved
     * word.
     */
    public static String javaName(String dealIdentifier) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < dealIdentifier.length(); i++) {
            char c = dealIdentifier.charAt(i);
            if (c == '$') sb.append("$d");
            else if (c == '_') sb.append("$u");
            else sb.append(c);
        }
        String encoded = sb.toString();
        if (JAVA_RESERVED.contains(encoded)) {
            return "_" + encoded;
        }
        return encoded;
    }

    // =========================================================================
    // Program emission
    // =========================================================================

    private JvmCodegenResult generateProgram(ProgramNode program) {
        List<StatementNode> statements = program.statements();
        for (int i = 0; i < statements.size(); i++) {
            StatementNode stmt = statements.get(i);
            if (stmt instanceof ImportDeclaration imp) {
                // ISSUE-0096: imports of compiled project modules are
                // supported — the alias maps to the imported module's
                // module path, and alias.fn(args) emits a static call on
                // the imported module's emitted class. ISSUE-0097: the
                // stdlib modules whose functions use only supported value
                // types (std/console, std/string, std/math, std/time) are
                // accepted as builtins. Any other import — a spec stdlib
                // module whose functions need table values (std/table,
                // std/json), a declaration/host module (never an
                // importResolutions entry: the orchestrator skips
                // declaration files in JVM codegen) — is out of scope and
                // rejected AT THE IMPORT STATEMENT itself, even when
                // unused, because the imported module's require-time side
                // effects have no JVM slice equivalent and must fail
                // loudly rather than be silently dropped.
                if (SUPPORTED_STDLIB_MODULES.contains(imp.modulePath())) {
                    importAliases.put(imp.alias(), imp.modulePath());
                    continue;
                }
                // ISSUE-0100 host ABI slice: an import whose raw path is a
                // key of the orchestrator's hostModules map (a declaration
                // file that is not a spec stdlib module — host-module-abi
                // D5, the same classification the LuaJIT use site builds)
                // is a host-module import. Its declared function exports
                // emit per-alias wrapper methods with load-time presence
                // checks and call-time boundary checks; declared exports
                // with shapes the slice does not support (classes,
                // arrays, tables, function values, rest parameters) are
                // E6000 at the import statement, never silently
                // miscompiled.
                Map<String, Type> hostExports = hostModules.get(imp.modulePath());
                if (hostExports != null) {
                    if (validateHostExports(imp.modulePath(), hostExports,
                            imp.span())) {
                        hostAliases.put(imp.alias(), imp.modulePath());
                        importAliases.put(imp.alias(),
                            imp.modulePath().replace('/', '.'));
                        importAliasStatementIndices.put(imp.alias(), i);
                    }
                    continue;
                }
                String resolved = importResolutions.get(imp.modulePath());
                if (resolved != null) {
                    importAliases.put(imp.alias(), resolved);
                    importAliasStatementIndices.put(imp.alias(), i);
                } else if (TABLE_BOUNDARY_STDLIB_MODULES.contains(
                        imp.modulePath())) {
                    unsupported("import of '" + imp.modulePath() + "' "
                        + "(its functions require table values, which the "
                        + "JVM slice does not support yet)", imp.span());
                } else {
                    unsupported("module imports other than compiled "
                        + "project modules and the supported stdlib "
                        + "modules (std/console, std/string, std/math, "
                        + "std/time) and host modules listed in the "
                        + "orchestrator's host-module map ('" + imp.modulePath() + "')",
                        imp.span());
                }
            } else if (stmt instanceof VariableDeclaration vd) {
                moduleFieldIndices.putIfAbsent(vd.name(), i);
            } else if (stmt instanceof FunctionDeclaration fd) {
                moduleFunctions.putIfAbsent(fd.name(), fd);
                moduleFunctionIndices.putIfAbsent(fd.name(), i);
            } else if (stmt instanceof ExportDeclaration ed
                    && ed.declaration() instanceof FunctionDeclaration fd) {
                moduleFunctions.putIfAbsent(fd.name(), fd);
                moduleFunctionIndices.putIfAbsent(fd.name(), i);
            } else if (stmt instanceof ClassDeclaration cd) {
                moduleClasses.putIfAbsent(cd.name(), cd);
            } else if (stmt instanceof ExportDeclaration ed
                    && ed.declaration() instanceof ClassDeclaration cd) {
                // ISSUE-0109: an exported class emits the same generated
                // nested class as a local one; the export only adds it to
                // the module type, which importers reference through the
                // emitting module's class.
                moduleClasses.putIfAbsent(cd.name(), cd);
            }
        }
        computeTransitiveFieldReads();
        computeForwardFieldViolations();

        String className = classNameFor(modulePath);
        // Defensive: a generated DEAL class name must never collide with the
        // module class name (reachable only for a module path whose derived
        // class name starts with the $C_ prefix, e.g. a path segment
        // "$C_Box"); an artifact with two same-named class declarations is
        // one javac rejects after the CLI reported success.
        for (Map.Entry<String, ClassDeclaration> ce : moduleClasses.entrySet()) {
            if (classNameForClass(ce.getKey()).equals(className)) {
                unsupported("class '" + ce.getKey() + "' whose generated "
                    + "class name collides with the module class name '"
                    + className + "'", ce.getValue().span());
            }
        }
        emitLine("// Generated by DEAL compiler — JVM backend (skeleton). DO NOT EDIT.");
        emitLine("// Source: " + sourcePath);
        emitLine("// Module: " + modulePath);
        emitLine();
        emitLine("public final class " + className + " {");
        indent++;
        emitRuntimeSupport();
        emitHostBindings();

        // Declarations (fields, functions, imports, exports) are emitted as
        // class members; every run of non-declaration module-level statements
        // is wrapped in a static initializer so it executes at class
        // initialization in source order — LuaJIT executes module-level
        // statements at load time. Interleaving members and static blocks
        // preserves the relative order of side-effecting initializers.
        moduleLevel = true;
        for (int i = 0; i < statements.size(); i++) {
            currentModuleStatementIndex = i;
            if (isModuleLevelDeclaration(statements.get(i))) {
                emitStatement(statements.get(i));
            } else {
                emitLine("static {");
                indent++;
                moduleLevel = false;
                while (i < statements.size()
                        && !isModuleLevelDeclaration(statements.get(i))) {
                    currentModuleStatementIndex = i;
                    StatementNode stmt = statements.get(i);
                    if (containsModuleReturn(stmt)) {
                        unsupported("module-level return (Java initializers cannot return)",
                            stmt.span());
                    } else {
                        emitStatement(stmt);
                        if (!statementCompletesNormally(stmt)) {
                            // Dead code after a non-completing statement:
                            // LuaJIT never executes it and javac rejects it
                            // as unreachable (JLS §14.21) — skip the rest
                            // of this static-block run. (Unreachable today:
                            // module-level returns are rejected above, but
                            // the guard keeps the invariant by
                            // construction.)
                            while (i < statements.size()
                                    && !isModuleLevelDeclaration(statements.get(i))) {
                                i++;
                            }
                            i--;
                            break;
                        }
                    }
                    i++;
                }
                i--;
                moduleLevel = true;
                indent--;
                emitLine("}");
            }
        }
        currentModuleStatementIndex = -1;
        moduleLevel = false;

        // ISSUE-0110: the shared descriptor-driven runtime-check seam is
        // emitted AFTER the module body so every declared class has
        // appended its descriptor branches to classCheckBranches.
        emitSharedCheckSeam();

        if (isEntry) {
            emitEntryPoint(program, className);
        }

        indent--;
        emitLine("}");

        return new JvmCodegenResult(className, out.toString(), diagnostics);
    }

    /**
     * Emits the JVM entry point for a selected entry module (spec-v1.2
     * §No user-defined globals): {@code public static void main(String[]
     * args)} invoking the module's DEAL {@code main} export. The frontend
     * entry gate (E2010/E2011, the compilation orchestrator's v1.2
     * selected-entry rule) guarantees an exported non-async
     * {@code main(): null}; this defensive scan keeps the artifact valid
     * Java when the backend is driven directly (unit tests) without that
     * gate — a missing or mismatched {@code main} is an E6004 diagnostic
     * (the same backend gate the Lua backend raises) instead of a silent
     * broken artifact.
     */
    private void emitEntryPoint(ProgramNode program, String className) {
        FunctionDeclaration mainDecl = null;
        boolean foundAnyMain = false;
        for (StatementNode stmt : program.statements()) {
            if (stmt instanceof ExportDeclaration ed
                    && ed.declaration() instanceof FunctionDeclaration fd) {
                if (fd.name().equals("main")) {
                    foundAnyMain = true;
                    if (fd.params().isEmpty()
                            && !fd.isAsync()
                            && resolveTypeNode(fd.returnType()) instanceof Type.Null) {
                        mainDecl = fd;
                    }
                }
            }
        }
        if (mainDecl == null) {
            diagnostics.add(Diagnostic.error(DiagnosticCode.E6004,
                "entry module must export non-async main(): null; found "
                + (foundAnyMain
                    ? "main with a different signature or an async marker"
                    : "no main export"),
                program.span().file(), program.span().startLine(),
                program.span().startColumn()));
            return;
        }
        emitLine();
        emitLine("// Entry-module invocation (spec-v1.2 §No user-defined globals): the");
        emitLine("// backend invokes main() from the selected entry module.");
        emitLine("public static void main(java.lang.String[] args) {");
        indent++;
        emitLine(javaName(mainDecl.name()) + "();");
        indent--;
        emitLine("}");
    }

    /** Class-body members at module level (vs. load-time statements). */
    private static boolean isModuleLevelDeclaration(StatementNode stmt) {
        return stmt instanceof VariableDeclaration
            || stmt instanceof FunctionDeclaration
            || stmt instanceof ExportDeclaration
            || stmt instanceof ImportDeclaration
            || stmt instanceof ClassDeclaration;
    }

    /** True when stmt — or a nested block/if (not a nested function body) —
     * contains a return statement. Java initializers cannot contain return,
     * so module-level returns are rejected instead of emitting invalid Java. */
    private static boolean containsModuleReturn(StatementNode stmt) {
        return switch (stmt) {
            case ReturnStatement rs -> true;
            case Block b -> {
                boolean found = false;
                for (StatementNode s : b.statements()) {
                    if (containsModuleReturn(s)) { found = true; break; }
                }
                yield found;
            }
            case IfStatement is -> {
                boolean found = containsModuleReturn(is.thenBlock());
                if (!found && is.elseBranch().isPresent()) {
                    Either<IfStatement, Block> branch = is.elseBranch().get();
                    if (branch instanceof Either.Left<IfStatement, Block> left) {
                        found = containsModuleReturn(left.value());
                    } else {
                        found = containsModuleReturn(
                            ((Either.Right<IfStatement, Block>) branch).value());
                    }
                }
                yield found;
            }
            case WhileStatement ws -> containsModuleReturn(ws.body());
            default -> false;
        };
    }

    // =========================================================================
    // Module-level call / later-field detection
    // =========================================================================

    /**
     * Computes, for every module-level function, (a) the set of module
     * fields its body reads and (b) the set of module functions its body
     * reaches by calls — both transitively, through calls to other
     * module-level functions. Used to reject module-level calls whose
     * (transitive) body reads a field, or reaches a function, declared
     * later than the call site: LuaJIT fails at load for such reads (a
     * field is nil and a function value is unassigned until its
     * declaration runs) while Java would silently read the field's
     * default value or run the hoisted method.
     */
    private void computeTransitiveFieldReads() {
        Map<String, Set<String>> directReads = new LinkedHashMap<>();
        Map<String, Set<String>> directCalls = new LinkedHashMap<>();
        Map<String, Set<String>> directImportReads = new LinkedHashMap<>();
        for (Map.Entry<String, FunctionDeclaration> e : moduleFunctions.entrySet()) {
            Set<String> reads = new LinkedHashSet<>();
            Set<String> calls = new LinkedHashSet<>();
            Set<String> imports = new LinkedHashSet<>();
            collectBodyReferences(e.getValue(), reads, calls, imports);
            directReads.put(e.getKey(), reads);
            directCalls.put(e.getKey(), calls);
            directImportReads.put(e.getKey(), imports);
        }
        for (String name : moduleFunctions.keySet()) {
            transitiveFieldReads.put(name, closureReads(name, directReads,
                directCalls, new LinkedHashMap<>(), new HashSet<>()));
            transitiveFunctionCalls.put(name, closureCalls(name, directCalls,
                new LinkedHashMap<>(), new HashSet<>()));
            transitiveImportReads.put(name, closureReads(name, directImportReads,
                directCalls, new LinkedHashMap<>(), new HashSet<>()));
        }
    }

    /** Transitive closure over the module-level call graph: the function
     * itself plus every module function reachable through its body's
     * calls (cycles handled by the in-progress guard). */
    private static Set<String> closureCalls(String name,
            Map<String, Set<String>> directCalls,
            Map<String, Set<String>> memo, Set<String> inProgress) {
        Set<String> cached = memo.get(name);
        if (cached != null) return cached;
        Set<String> result = new LinkedHashSet<>();
        result.add(name);
        if (inProgress.add(name)) {
            for (String callee : directCalls.getOrDefault(name, Set.of())) {
                result.addAll(closureCalls(callee, directCalls, memo,
                    inProgress));
            }
            inProgress.remove(name);
        }
        memo.put(name, result);
        return result;
    }

    /** Transitive closure over the module-level call graph (cycles handled
     * by the in-progress guard). */
    private static Set<String> closureReads(String name,
            Map<String, Set<String>> directReads,
            Map<String, Set<String>> directCalls,
            Map<String, Set<String>> memo, Set<String> inProgress) {
        Set<String> cached = memo.get(name);
        if (cached != null) return cached;
        Set<String> result = new LinkedHashSet<>(
            directReads.getOrDefault(name, Set.of()));
        if (inProgress.add(name)) {
            for (String callee : directCalls.getOrDefault(name, Set.of())) {
                result.addAll(closureReads(callee, directReads, directCalls,
                    memo, inProgress));
            }
            inProgress.remove(name);
        }
        memo.put(name, result);
        return result;
    }

    /**
     * Walks a function body collecting (a) module-field names read in value
     * positions, excluding identifiers shadowed by parameters or locals (a
     * function-local shadow of a module field is not a field read), and
     * (b) module-level functions called by name. Locals are tracked
     * scope-by-scope; nested function declarations are separate scopes and
     * unsupported by the backend (rejected later), so they are not walked.
     */
    private void collectBodyReferences(FunctionDeclaration fd,
            Set<String> fieldReads, Set<String> calledFunctions,
            Set<String> importReads) {
        Deque<Set<String>> locals = new ArrayDeque<>();
        Set<String> params = new LinkedHashSet<>();
        for (Parameter p : fd.params()) params.add(p.name());
        locals.push(params);
        collectStatementListRefs(fd.body().statements(), locals,
            fieldReads, calledFunctions, importReads);
    }

    private void collectStatementListRefs(List<StatementNode> stmts,
            Deque<Set<String>> locals, Set<String> fieldReads,
            Set<String> calledFunctions, Set<String> importReads) {
        for (StatementNode stmt : stmts) {
            collectStatementRefs(stmt, locals, fieldReads, calledFunctions,
                importReads);
        }
    }

    private void collectStatementRefs(StatementNode stmt,
            Deque<Set<String>> locals, Set<String> fieldReads,
            Set<String> calledFunctions, Set<String> importReads) {
        switch (stmt) {
            case VariableDeclaration vd -> {
                collectExprRefs(vd.initializer(), locals, fieldReads,
                    calledFunctions, importReads);
                locals.peek().add(vd.name());
            }
            case ReturnStatement rs -> rs.expr().ifPresent(
                e -> collectExprRefs(e, locals, fieldReads, calledFunctions,
                    importReads));
            case ExpressionStatement es ->
                collectExprRefs(es.expr(), locals, fieldReads,
                    calledFunctions, importReads);
            case IfStatement is -> {
                collectExprRefs(is.condition(), locals, fieldReads,
                    calledFunctions, importReads);
                collectBlockRefs(is.thenBlock(), locals, fieldReads,
                    calledFunctions, importReads);
                if (is.elseBranch().isPresent()) {
                    switch (is.elseBranch().get()) {
                        case Either.Left<IfStatement, Block> left ->
                            collectStatementRefs(left.value(), locals,
                                fieldReads, calledFunctions, importReads);
                        case Either.Right<IfStatement, Block> right ->
                            collectBlockRefs(right.value(), locals,
                                fieldReads, calledFunctions, importReads);
                    }
                }
            }
            case WhileStatement ws -> {
                collectExprRefs(ws.condition(), locals, fieldReads,
                    calledFunctions, importReads);
                collectBlockRefs(ws.body(), locals, fieldReads,
                    calledFunctions, importReads);
            }
            case Block b -> collectBlockRefs(b, locals, fieldReads,
                calledFunctions, importReads);
            // Unsupported statement kinds (for/for-of, try, nested
            // functions, classes, …) are rejected with E6000 when emitted;
            // nothing to walk here.
            default -> { }
        }
    }

    private void collectBlockRefs(Block b, Deque<Set<String>> locals,
            Set<String> fieldReads, Set<String> calledFunctions,
            Set<String> importReads) {
        locals.push(new LinkedHashSet<>());
        collectStatementListRefs(b.statements(), locals, fieldReads,
            calledFunctions, importReads);
        locals.pop();
    }

    private void collectExprRefs(ExpressionNode e, Deque<Set<String>> locals,
            Set<String> fieldReads, Set<String> calledFunctions,
            Set<String> importReads) {
        switch (e) {
            case IdentifierExpr id -> {
                if (!isLocallyBound(locals, id.name())
                        && symbols.resolve(id.name()) instanceof Symbol.VariableSymbol
                        && moduleFieldIndices.containsKey(id.name())) {
                    fieldReads.add(id.name());
                }
                // An import alias in a value position is E6000 at emission;
                // record it anyway so a module-level call of a function
                // that reaches an import declared after the call site is
                // rejected (LuaJIT reads the not-yet-required global).
                if (importAliasStatementIndices.containsKey(id.name())) {
                    importReads.add(id.name());
                }
            }
            case BinaryExpr bin -> {
                collectExprRefs(bin.left(), locals, fieldReads,
                    calledFunctions, importReads);
                collectExprRefs(bin.right(), locals, fieldReads,
                    calledFunctions, importReads);
            }
            case UnaryExpr u ->
                collectExprRefs(u.expr(), locals, fieldReads, calledFunctions,
                    importReads);
            case CallExpr call -> {
                if (call.callee() instanceof IdentifierExpr id
                        && symbols.resolve(id.name()) instanceof Symbol.FunctionSymbol
                        && moduleFunctions.containsKey(id.name())) {
                    calledFunctions.add(id.name());
                } else {
                    collectExprRefs(call.callee(), locals, fieldReads,
                        calledFunctions, importReads);
                }
                for (ExpressionNode arg : call.args()) {
                    collectExprRefs(arg, locals, fieldReads, calledFunctions,
                        importReads);
                }
            }
            // The member-access object is the import alias for an imported
            // direct call (lib.add(...)) — walked so the alias lands in
            // importReads.
            case MemberAccessExpr mae ->
                collectExprRefs(mae.object(), locals, fieldReads,
                    calledFunctions, importReads);
            // Assignment targets are writes, not reads; LuaJIT and Java
            // agree on write order (the write happens, then the later field
            // initializer overwrites), so only the value side is walked.
            // An INDEX target's array and index expressions are value
            // positions (reads) — a later-declared field read there is a
            // load-time nil read under LuaJIT (attempt to index nil) and a
            // forward static-field reference under Java — so they are
            // walked like any other read.
            case AssignmentExpr ae -> {
                collectExprRefs(ae.value(), locals, fieldReads, calledFunctions,
                    importReads);
                if (ae.target() instanceof IndexExpr idx) {
                    collectExprRefs(idx.array(), locals, fieldReads,
                        calledFunctions, importReads);
                    collectExprRefs(idx.index(), locals, fieldReads,
                        calledFunctions, importReads);
                }
            }
            // Array reads/literals/length are value positions (ISSUE-0094):
            // the array, the index, and every element are reads. Without
            // these walks a module-level call whose transitive body reads a
            // later-declared field through an index or an array literal
            // would slip past the load-time guard and emit a Java forward
            // reference (LuaJIT fails at load with a nil read).
            case IndexExpr idx -> {
                collectExprRefs(idx.array(), locals, fieldReads,
                    calledFunctions, importReads);
                collectExprRefs(idx.index(), locals, fieldReads,
                    calledFunctions, importReads);
            }
            case ArrayLiteralExpr al -> {
                for (ExpressionNode elem : al.elements()) {
                    collectExprRefs(elem, locals, fieldReads, calledFunctions,
                        importReads);
                }
            }
            // Object literals: property values are value positions (class
            // construction provided values and table literal values —
            // ISSUE-0095); class-construction defaults are value positions
            // too (evaluated per construction at the construction site).
            case ObjectLiteralExpr ol -> {
                for (Property prop : ol.properties()) {
                    collectExprRefs(prop.value(), locals, fieldReads,
                        calledFunctions, importReads);
                }
                if (typeOf(ol) instanceof Type.Class cls
                        && isLocalClassType(cls)) {
                    ClassDeclaration cd = moduleClasses.get(cls.name());
                    if (cd != null) {
                        for (ClassField cf : cd.fields()) {
                            cf.defaultExpr().ifPresent(
                                d -> collectExprRefs(d, locals, fieldReads,
                                    calledFunctions, importReads));
                        }
                    }
                }
            }
            // Template interpolations are value positions; the literal
            // parts carry no references.
            case TemplateLiteralExpr tl -> {
                for (ExpressionNode part : tl.parts()) {
                    collectExprRefs(part, locals, fieldReads, calledFunctions,
                        importReads);
                }
            }
            // Literals and unsupported forms (rejected later) are not walked.
            default -> { }
        }
    }

    private static boolean isLocallyBound(Deque<Set<String>> locals, String name) {
        for (Set<String> scope : locals) {
            if (scope.contains(name)) return true;
        }
        return false;
    }

    /** The name of a module field declared at or after {@code callIndex}
     * that {@code functionName}'s body reads transitively, or
     * {@code null}. {@code >=} also catches a field's own initializer
     * calling a function that reads the field being initialized
     * ({@code let x: int = f()} with {@code f} reading {@code x}: LuaJIT
     * reads nil there and fails at load, Java would read the default). */
    private String laterFieldRead(String functionName, int callIndex) {
        for (String field : transitiveFieldReads.getOrDefault(functionName,
                Set.of())) {
            Integer idx = moduleFieldIndices.get(field);
            if (idx != null && idx >= callIndex) return field;
        }
        return null;
    }

    /** The name of an import alias declared at or after {@code callIndex}
     * that {@code functionName}'s body references transitively, or {@code
     * null}. LuaJIT emits the {@code require} at the import's source
     * position, so a module-level call of such a function before that
     * position reads the not-yet-required global and fails at load, while
     * Java would silently initialize the imported class (ISSUE-0096). */
    private String laterImportRead(String functionName, int callIndex) {
        for (String alias : transitiveImportReads.getOrDefault(functionName,
                Set.of())) {
            Integer idx = importAliasStatementIndices.get(alias);
            if (idx != null && idx > callIndex) return alias;
        }
        return null;
    }

    /** The name of a module function declared at or after {@code callIndex}
     * that {@code functionName}'s body reaches transitively (the callee
     * itself included), or {@code null}. LuaJIT assigns each function
     * value at its declaration point in source order, so any module-level
     * call that reaches a not-yet-declared function fails at load with a
     * nil read; Java hoists methods and would silently run. */
    private String laterFunctionCall(String functionName, int callIndex) {
        for (String reached : transitiveFunctionCalls.getOrDefault(
                functionName, Set.of(functionName))) {
            Integer idx = moduleFunctionIndices.get(reached);
            if (idx != null && idx >= callIndex) return reached;
        }
        return null;
    }

    // =========================================================================
    // Function-body access to later-declared module fields (reads and
    // writes — write-dominance analysis)
    // =========================================================================

    /**
     * Computes, for every module-level function, the first module field
     * declared AFTER the function that its body accesses without full
     * parity, recording reads without a dominating write in
     * {@link #forwardReadViolations} and writes in
     * {@link #forwardWriteViolations}. LuaJIT: a function declared before
     * a field does not capture the module-local — the local does not
     * exist when the function value is created — so every access in its
     * body binds to the GLOBAL of the same name at call time.
     * <ul>
     * <li>A READ of a later-declared field without a dominating write
     * inside the function reads the global nil at call time and fails
     * (E8001) unless a prior write established it, while Java silently
     * reads the initialized static field — rejected rather than silently
     * diverging. Writes inside called functions do not establish
     * dominance (conservative: a callee's writes may be conditional),
     * and a write inside a taken-only branch does not dominate reads
     * after the branch (LuaJIT would read the global nil when the
     * branch is not taken) — both shapes are rejected.</li>
     * <li>A WRITE to a later-declared field is rejected outright (every
     * position: statement, block, if/else branch, return value, call
     * argument): LuaJIT writes the GLOBAL, leaving the module-local
     * untouched so later readers observe the initializer value, while
     * Java would write the static field and pollute every later reader.
     * The write-then-read shape ({@code x = 5; return x;}) is included —
     * the function's own read observes the global write under LuaJIT,
     * but the polluted Java field remains observable by later readers.
     * Writes to fields declared BEFORE the function stay allowed (the
     * upvalue/static-field write — full parity, pinned by a
     * cross-backend fixture).</li>
     * </ul>
     */
    private void computeForwardFieldViolations() {
        for (Map.Entry<String, FunctionDeclaration> e : moduleFunctions.entrySet()) {
            Integer declIdx = moduleFunctionIndices.get(e.getKey());
            if (declIdx == null) continue;
            Set<String> readViolations = new LinkedHashSet<>();
            Set<String> writeViolations = new LinkedHashSet<>();
            Deque<Set<String>> locals = new ArrayDeque<>();
            Set<String> params = new LinkedHashSet<>();
            for (Parameter p : e.getValue().params()) params.add(p.name());
            locals.push(params);
            walkDominanceList(e.getValue().body().statements(), locals,
                new LinkedHashSet<>(), declIdx, readViolations, writeViolations);
            if (!readViolations.isEmpty()) {
                forwardReadViolations.put(e.getKey(),
                    readViolations.iterator().next());
            }
            if (!writeViolations.isEmpty()) {
                forwardWriteViolations.put(e.getKey(),
                    writeViolations.iterator().next());
            }
        }
    }

    /** Straight-line dominance walk: writes persist across blocks, so a
     * write inside a block stays visible to following statements (LuaJIT
     * agreement: a global write inside a block persists after it). */
    private void walkDominanceList(List<StatementNode> stmts,
            Deque<Set<String>> locals, Set<String> written, int fnDeclIdx,
            Set<String> readViolations, Set<String> writeViolations) {
        for (StatementNode stmt : stmts) {
            walkDominanceStmt(stmt, locals, written, fnDeclIdx,
                readViolations, writeViolations);
        }
    }

    private void walkDominanceStmt(StatementNode stmt, Deque<Set<String>> locals,
            Set<String> written, int fnDeclIdx, Set<String> readViolations,
            Set<String> writeViolations) {
        switch (stmt) {
            case VariableDeclaration vd -> {
                walkDominanceExpr(vd.initializer(), locals, written,
                    fnDeclIdx, readViolations, writeViolations);
                locals.peek().add(vd.name());
            }
            case ReturnStatement rs -> rs.expr().ifPresent(
                e -> walkDominanceExpr(e, locals, written, fnDeclIdx,
                    readViolations, writeViolations));
            case ExpressionStatement es ->
                walkDominanceExpr(es.expr(), locals, written, fnDeclIdx,
                    readViolations, writeViolations);
            case IfStatement is -> {
                walkDominanceExpr(is.condition(), locals, written,
                    fnDeclIdx, readViolations, writeViolations);
                Set<String> thenWritten = new LinkedHashSet<>(written);
                locals.push(new LinkedHashSet<>());
                walkDominanceList(is.thenBlock().statements(), locals,
                    thenWritten, fnDeclIdx, readViolations, writeViolations);
                locals.pop();
                if (is.elseBranch().isPresent()) {
                    Set<String> elseWritten = new LinkedHashSet<>(written);
                    switch (is.elseBranch().get()) {
                        case Either.Left<IfStatement, Block> left -> {
                            locals.push(new LinkedHashSet<>());
                            walkDominanceStmt(left.value(), locals, elseWritten,
                                fnDeclIdx, readViolations, writeViolations);
                            locals.pop();
                        }
                        case Either.Right<IfStatement, Block> right -> {
                            locals.push(new LinkedHashSet<>());
                            walkDominanceList(right.value().statements(), locals,
                                elseWritten, fnDeclIdx, readViolations,
                                writeViolations);
                            locals.pop();
                        }
                    }
                    // Definitely written after the if/else: written before
                    // the branch plus the intersection of both branches.
                    Set<String> merged = new LinkedHashSet<>(written);
                    for (String f : thenWritten) {
                        if (elseWritten.contains(f)) merged.add(f);
                    }
                    written.clear();
                    written.addAll(merged);
                }
                // else: an if without an else branch writes nothing
                // definitely — the then-writes stay unmerged (conservative:
                // LuaJIT takes the branch at runtime, but the not-taken
                // path reads the global nil, so no dominance is claimed).
            }
            case Block b -> {
                locals.push(new LinkedHashSet<>());
                walkDominanceList(b.statements(), locals, written,
                    fnDeclIdx, readViolations, writeViolations);
                locals.pop();
            }
            case WhileStatement ws -> {
                walkDominanceExpr(ws.condition(), locals, written,
                    fnDeclIdx, readViolations, writeViolations);
                Set<String> bodyWritten = new LinkedHashSet<>(written);
                locals.push(new LinkedHashSet<>());
                walkDominanceList(ws.body().statements(), locals, bodyWritten,
                    fnDeclIdx, readViolations, writeViolations);
                locals.pop();
                // Writes inside the loop body do not dominate anything
                // after the loop: the body may execute zero times, so a
                // later read would still hit LuaJIT's global nil on the
                // not-taken path. bodyWritten is deliberately discarded
                // (conservative, like the taken-only-branch rule).
            }
            // Unsupported statement kinds (for/for-of, try, nested
            // functions, classes, …) are rejected with E6000 when emitted;
            // nothing to walk here.
            default -> { }
        }
    }

    /**
     * Walks an expression collecting reads of later-declared module fields
     * not dominated by a write (violations) and adding field writes to
     * {@code written}. Identifiers shadowed by locals/parameters are local
     * uses, not field uses (mirrors {@code collectExprRefs}); call
     * arguments are walked left to right, so a write in an earlier
     * argument is visible to a read in a later one (LuaJIT agreement).
     */
    private void walkDominanceExpr(ExpressionNode e, Deque<Set<String>> locals,
            Set<String> written, int fnDeclIdx, Set<String> readViolations,
            Set<String> writeViolations) {
        switch (e) {
            case IdentifierExpr id -> {
                String name = id.name();
                if (!isLocallyBound(locals, name)
                        && symbols.resolve(name) instanceof Symbol.VariableSymbol
                        && moduleFieldIndices.containsKey(name)) {
                    Integer idx = moduleFieldIndices.get(name);
                    if (idx != null && idx > fnDeclIdx && !written.contains(name)) {
                        readViolations.add(name);
                    }
                }
            }
            case BinaryExpr bin -> {
                walkDominanceExpr(bin.left(), locals, written, fnDeclIdx,
                    readViolations, writeViolations);
                walkDominanceExpr(bin.right(), locals, written, fnDeclIdx,
                    readViolations, writeViolations);
            }
            case UnaryExpr u ->
                walkDominanceExpr(u.expr(), locals, written, fnDeclIdx,
                    readViolations, writeViolations);
            case CallExpr call -> {
                // Callee identifiers are hoisted function names (a field
                // callee would be a function-valued field — unsupported);
                // walk the arguments only.
                for (ExpressionNode arg : call.args()) {
                    walkDominanceExpr(arg, locals, written, fnDeclIdx,
                        readViolations, writeViolations);
                }
            }
            case AssignmentExpr ae -> {
                walkDominanceExpr(ae.value(), locals, written, fnDeclIdx,
                    readViolations, writeViolations);
                if (ae.target() instanceof IdentifierExpr id) {
                    String name = id.name();
                    if (!isLocallyBound(locals, name)
                            && symbols.resolve(name) instanceof Symbol.VariableSymbol
                            && moduleFieldIndices.containsKey(name)) {
                        Integer idx = moduleFieldIndices.get(name);
                        // A write to a field declared after the function is
                        // rejected outright (global-vs-static-field
                        // divergence), in every position the assignment can
                        // appear: statement, block, if/else branch, return
                        // value, call argument.
                        if (idx != null && idx > fnDeclIdx) {
                            writeViolations.add(name);
                        }
                        written.add(name);
                    }
                } else if (ae.target() instanceof IndexExpr idx) {
                    // An INDEX target's array and index expressions are
                    // value positions (ISSUE-0094): a later-declared field
                    // read there binds to LuaJIT's global nil at call time
                    // (attempt to index nil) while Java would read the
                    // initialized static field — a read violation unless a
                    // write inside the function dominates it.
                    walkDominanceExpr(idx.array(), locals, written, fnDeclIdx,
                        readViolations, writeViolations);
                    walkDominanceExpr(idx.index(), locals, written, fnDeclIdx,
                        readViolations, writeViolations);
                }
            }
            case MemberAccessExpr mae ->
                walkDominanceExpr(mae.object(), locals, written, fnDeclIdx,
                    readViolations, writeViolations);
            case IndexExpr idx -> {
                walkDominanceExpr(idx.array(), locals, written, fnDeclIdx,
                    readViolations, writeViolations);
                walkDominanceExpr(idx.index(), locals, written, fnDeclIdx,
                    readViolations, writeViolations);
            }
            case ArrayLiteralExpr al -> {
                for (ExpressionNode elem : al.elements()) {
                    walkDominanceExpr(elem, locals, written, fnDeclIdx,
                        readViolations, writeViolations);
                }
            }
            // Object literal property values (class construction provided
            // values and table literal values) and class-construction
            // defaults are value positions: a later-declared module field
            // read there is a forward-field violation exactly like a plain
            // read (ISSUE-0095).
            case ObjectLiteralExpr ol -> {
                for (Property prop : ol.properties()) {
                    walkDominanceExpr(prop.value(), locals, written, fnDeclIdx,
                        readViolations, writeViolations);
                }
                if (typeOf(ol) instanceof Type.Class cls
                        && isLocalClassType(cls)) {
                    ClassDeclaration cd = moduleClasses.get(cls.name());
                    if (cd != null) {
                        for (ClassField cf : cd.fields()) {
                            cf.defaultExpr().ifPresent(
                                d -> walkDominanceExpr(d, locals, written,
                                    fnDeclIdx, readViolations, writeViolations));
                        }
                    }
                }
            }
            case TemplateLiteralExpr tl -> {
                for (ExpressionNode part : tl.parts()) {
                    walkDominanceExpr(part, locals, written, fnDeclIdx,
                        readViolations, writeViolations);
                }
            }
            // Literals and unsupported forms (rejected later) are not walked.
            default -> { }
        }
    }

    // =========================================================================
    // Runtime support (emitted once per class)
    // =========================================================================

    private void emitRuntimeSupport() {
        emitLine("// ---- DEAL JVM skeleton runtime support ----");
        // Every java.lang reference is fully qualified: DEAL identifiers may
        // be named System, Math, Double, String, Void, Integer, Character,
        // RuntimeException, ArithmeticException, … (javaName passes
        // non-reserved names through unchanged), and an unqualified
        // reference would bind to the user's field/local instead of
        // java.lang, producing an artifact javac rejects.
        emitLine("/** DEAL runtime error: code per §Diagnostics (E8xxx). */");
        emitLine("static final class DealError extends java.lang.RuntimeException {");
        emitLine("    final java.lang.String code;");
        emitLine("    DealError(java.lang.String code, java.lang.String message) {");
        emitLine("        super(message);");
        emitLine("        this.code = code;");
        emitLine("    }");
        emitLine("}");
        emitLine("// DEAL int safe range: ±(2^53-1), mirroring deal/runtime.lua's");
        emitLine("// check_int (v < -9007199254740991 or v > 9007199254740991 raises");
        emitLine("// E8004). Every int-producing operation checks its result, exactly");
        emitLine("// like LuaJIT's int_add = check_int(a + b) family.");
        emitLine("static long checkInt(long v) { if (v > 9007199254740991L || v < -9007199254740991L) throw new DealError(\"E8004\", \"int out of safe range\"); return v; }");
        emitLine("// int arithmetic: E8004 out of safe range, E8005 division by zero, E8006 negative exponent.");
        emitLine("static long intAdd(long a, long b) { try { return checkInt(java.lang.Math.addExact(a, b)); } catch (java.lang.ArithmeticException e) { throw new DealError(\"E8004\", \"int out of safe range\"); } }");
        emitLine("static long intSub(long a, long b) { try { return checkInt(java.lang.Math.subtractExact(a, b)); } catch (java.lang.ArithmeticException e) { throw new DealError(\"E8004\", \"int out of safe range\"); } }");
        emitLine("static long intMul(long a, long b) { try { return checkInt(java.lang.Math.multiplyExact(a, b)); } catch (java.lang.ArithmeticException e) { throw new DealError(\"E8004\", \"int out of safe range\"); } }");
        emitLine("static long intDiv(long a, long b) { if (b == 0L) throw new DealError(\"E8005\", \"integer division by zero\"); try { return checkInt(a / b); } catch (java.lang.ArithmeticException e) { throw new DealError(\"E8004\", \"int out of safe range\"); } }");
        emitLine("static long intMod(long a, long b) { if (b == 0L) throw new DealError(\"E8005\", \"integer division by zero\"); try { return checkInt(a % b); } catch (java.lang.ArithmeticException e) { throw new DealError(\"E8004\", \"int out of safe range\"); } }");
        // NaN/Infinity first, matching deal/runtime.lua's check_int (LuaJIT
        // "expected int, got infinity" for e.g. `10 ** 400`); only finite
        // values outside the int safe range report E8004.
        emitLine("static long intPow(long a, long b) { if (b < 0L) throw new DealError(\"E8006\", \"integer exponent must be non-negative\"); double p = java.lang.Math.pow((double) a, (double) b); if (java.lang.Double.isNaN(p)) throw new DealError(\"E8001\", \"expected int, got NaN\"); if (java.lang.Double.isInfinite(p)) throw new DealError(\"E8001\", \"expected int, got infinity\"); if (p > 9007199254740991.0 || p < -9007199254740991.0) throw new DealError(\"E8004\", \"int out of safe range\"); return (long) p; }");
        emitLine("static long intNeg(long a) { try { return checkInt(java.lang.Math.negateExact(a)); } catch (java.lang.ArithmeticException e) { throw new DealError(\"E8004\", \"int out of safe range\"); } }");
        emitLine("// number %: Lua-style floored modulo (a - floor(a/b)*b), unlike Java's truncated %.");
        emitLine("static double numMod(double a, double b) { return a - java.lang.Math.floor(a / b) * b; }");
        emitLine("// int(v) / number(v) conversion intrinsics (E8001 bad value, E8004 out of range).");
        emitLine("static long intFromNumber(double v) { if (java.lang.Double.isNaN(v)) throw new DealError(\"E8001\", \"expected int, got NaN\"); if (java.lang.Double.isInfinite(v)) throw new DealError(\"E8001\", \"expected int, got infinity\"); if (v != java.lang.Math.floor(v)) throw new DealError(\"E8001\", \"expected int, got non-integer number\"); if (v > 9007199254740991.0 || v < -9007199254740991.0) throw new DealError(\"E8004\", \"int out of safe range\"); return (long) v; }");
        emitLine("static double numberFromInt(long v) { return (double) v; }");
        emitLine("// string ordering: Unicode scalar-value order. LuaJIT orders bytewise in");
        emitLine("// UTF-8, which is scalar-value order — including supplementary characters");
        emitLine("// (String.compareTo's UTF-16 code-unit order diverges there).");
        emitLine("static int scalarCompare(java.lang.String a, java.lang.String b) { int i = 0; int j = 0; while (i < a.length() && j < b.length()) { int ca = a.codePointAt(i); int cb = b.codePointAt(j); if (ca != cb) { return java.lang.Integer.compare(ca, cb); } i += java.lang.Character.charCount(ca); j += java.lang.Character.charCount(cb); } return java.lang.Integer.compare(a.length() - i, b.length() - j); }");
        emitLine("// while conditions route through this identity helper so javac never sees a");
        emitLine("// constant-expression condition (JLS §14.21): a constant-true condition");
        emitLine("// would make statements after the loop unreachable and a constant-false");
        emitLine("// condition would make the loop body unreachable — both javac errors.");
        emitLine("static boolean loopCond(boolean v) { return v; }");
        emitLine("// Import initialization trigger (ISSUE-0096): importers invoke this");
        emitLine("// no-op so the imported class initializes (JLS §12.4.1) exactly where");
        emitLine("// LuaJIT runs require. The name contains '$', which DEAL identifiers");
        emitLine("// cannot contain, so it can never collide with a user function.");
        emitLine("static void __init$() {}");
        emitLine("// ---- JVM host ABI runtime support (ISSUE-0100) ----");
        emitLine("// Load-time presence check for one declared host export: the host");
        emitLine("// module class's static method must exist with the descriptor-derived");
        emitLine("// parameter classes (spec-v1.2 \u00a7Host ABI and interoperability: the host module runtime");
        emitLine("// object must expose every declared export; a missing declared export");
        emitLine("// is a load-time error). Extra host methods are never looked up.");
        emitLine("static java.lang.reflect.Method __hostMethod(java.lang.Class<?> h, java.lang.String module, java.lang.String name, java.lang.String desc, java.lang.Class<?>[] params) {");
        emitLine("    try { return h.getDeclaredMethod(name, params); }");
        emitLine("    catch (java.lang.NoSuchMethodException e) {");
        emitLine("        throw new DealError(\"E8011\", \"host export '\" + name + \"' in module '\" + module + \"' missing or has signature mismatch: expected \" + desc);");
        emitLine("    }");
        emitLine("}");
        emitLine("// Reflective invocation of a checked host export: DEAL errors the host");
        emitLine("// raises propagate as-is; other causes surface as E8010 (the host");
        emitLine("// boundary never leaks a raw foreign exception into DEAL code).");
        emitLine("static java.lang.Object __hostInvoke(java.lang.reflect.Method m, java.lang.Object[] args) {");
        emitLine("    try { return m.invoke(null, args); }");
        emitLine("    catch (java.lang.reflect.InvocationTargetException e) {");
        emitLine("        java.lang.Throwable c = e.getCause();");
        emitLine("        if (c instanceof RuntimeException rr) throw rr;");
        emitLine("        if (c instanceof Error er) throw er;");
        emitLine("        throw new DealError(\"E8010\", \"host function raised: \" + c);");
        emitLine("    }");
        emitLine("    catch (java.lang.IllegalAccessException | java.lang.IllegalArgumentException e) {");
        emitLine("        throw new DealError(\"E8010\", \"host function invocation failed: \" + e);");
        emitLine("    }");
        emitLine("}");
        emitLine("// Host-boundary return check: validates the dynamic value that crossed");
        emitLine("// the untyped host boundary against the declared return descriptor.");
        emitLine("// Sync returns raise E8010 on a mismatch (host-module-abi D3 case 2);");
        emitLine("// async completion values raise E8001 at the await site (the LuaJIT");
        emitLine("// await-site completion check). ISSUE-0106 (v1.2 boundary string");
        emitLine("// validation): the string branch also scans for unpaired UTF-16");
        emitLine("// surrogate code units (spec-v1.2 \u00a7JVM value mapping) and raises");
        emitLine("// the branch's error code (E8010 sync, E8001 completion) with the");
        emitLine("// seam's rejection message. Java null is the DEAL null sentinel");
        emitLine("// (spec-v1.2 \u00a7JVM value mapping): it passes only where the declared");
        emitLine("// descriptor permits it (?T or null), and every other context rejects");
        emitLine("// it — the spec forbids exposing Java null as DEAL null across an");
        emitLine("// untyped boundary without validation.");
        emitLine("static java.lang.Object __hostCheck(java.lang.String desc, java.lang.Object v, java.lang.String fn, boolean completion) {");
        emitLine("    java.lang.String d = desc;");
        emitLine("    while (d.startsWith(\"?\")) {");
        emitLine("        if (v == null) return null;");
        emitLine("        d = d.substring(1);");
        emitLine("    }");
        emitLine("    switch (d) {");
        emitLine("        case \"int\":");
        emitLine("            if (v instanceof java.lang.Long l) return checkInt(l.longValue());");
        emitLine("            break;");
        emitLine("        case \"number\":");
        emitLine("            if (v instanceof java.lang.Double dd) return dd;");
        emitLine("            break;");
        emitLine("        case \"boolean\":");
        emitLine("            if (v instanceof java.lang.Boolean b) return b;");
        emitLine("            break;");
        emitLine("        case \"string\":");
        emitLine("            if (v instanceof java.lang.String s) { if (__hasUnpairedSurrogate(s)) throw new DealError(completion ? \"E8001\" : \"E8010\", \"expected string, got string with unpaired surrogate code units\"); return s; }");
        emitLine("            break;");
        emitLine("        case \"null\":");
        emitLine("            if (v == null) return null;");
        emitLine("            break;");
        emitLine("        default:");
        emitLine("            throw new DealError(\"E8001\", \"unsupported host boundary descriptor \" + desc);");
        emitLine("    }");
        emitLine("    if (completion) throw new DealError(\"E8001\", \"expected \" + desc + \", got \" + $describe(v));");
        emitLine("    throw new DealError(\"E8010\", \"host function '\" + fn + \"' return value 1 type mismatch: expected \" + desc + \", got \" + $describe(v));");
        emitLine("}");
        emitLine("// ---- DEAL classes and tables (ISSUE-0095) ----");
        emitLine("// Nominal identity base: every generated DEAL class extends $Base and");
        emitLine("// carries its spec ClassDescriptor (@<modulePath>/<Name>). The shared");
        emitLine("// runtime-check seam $check(descriptor, value) (ISSUE-0110, emitted");
        emitLine("// after the module body) dispatches the nominal checks on those");
        emitLine("// descriptor strings; javaName translates user identifiers without");
        emitLine("// raw '$', so no user function or class can collide with the fixed");
        emitLine("// $check helper.");
        emitLine("static class $Base {");
        emitLine("    final java.lang.String $identity;");
        emitLine("    $Base(java.lang.String identity) { this.$identity = identity; }");
        emitLine("}");
        emitLine("// Minimal DEAL table: ordered string-key map over java.lang.Object");
        emitLine("// values. Tables are the only untyped boundary of this slice — a table");
        emitLine("// field read in a class-typed contextual target is where the runtime");
        emitLine("// nominal check must run (the read is Object-typed, so no static proof");
        emitLine("// exists). Property values box: DEAL ints are Long, numbers Double,");
        emitLine("// booleans Boolean, strings String, classes the generated class");
        emitLine("// instances, nested tables $T.");
        emitLine("static final class $T {");
        emitLine("    private final java.util.LinkedHashMap<java.lang.String, java.lang.Object> entries = new java.util.LinkedHashMap<>();");
        emitLine("    $T() {}");
        emitLine("    $T put(java.lang.String k, java.lang.Object v) { entries.put(k, v); return this; }");
        emitLine("    java.lang.Object get(java.lang.String k) { return entries.get(k); }");
        emitLine("}");
        emitLine("// Human-readable DEAL identity of a dynamic value for E8001 messages");
        emitLine("// (mirrors LuaJIT's actual_class reporting in check_type).");
        emitLine("static java.lang.String $describe(java.lang.Object v) {");
        emitLine("    if (v instanceof $Base b) return b.$identity;");
        emitLine("    if (v == null) return \"null\";");
        emitLine("    if (v instanceof $T) return \"table\";");
        emitLine("    return v.getClass().getSimpleName();");
        emitLine("}");
        emitLine("// The DEAL identity of any generated class instance, ACROSS modules");
        emitLine("// (ISSUE-0109): each emitted module declares its own nested $Base,");
        emitLine("// so a foreign module's instances are not instanceof this module's");
        emitLine("// $Base. Every $Base carries the package-private field $identity");
        emitLine("// holding the spec ClassDescriptor (@<modulePath>/<Name>);");
        emitLine("// $identityOf reads it structurally so a wrong-module nominal check");
        emitLine("// reports the actual module-qualified identity exactly like");
        emitLine("// LuaJIT's actual_class reporting in check_type. The '$' in the");
        emitLine("// field name is unspellable in DEAL (javaName escapes '$'), so no");
        emitLine("// user-declared field can ever match it.");
        emitLine("static java.lang.String $identityOf(java.lang.Object v) {");
        emitLine("    if (v instanceof $Base b) return b.$identity;");
        emitLine("    if (v == null) return null;");
        emitLine("    java.lang.Class<?> k = v.getClass();");
        emitLine("    while (k != null && k != java.lang.Object.class) {");
        emitLine("        try {");
        emitLine("            java.lang.reflect.Field f = k.getDeclaredField(\"$identity\");");
        emitLine("            f.setAccessible(true);");
        emitLine("            return java.lang.String.valueOf(f.get(v));");
        emitLine("        } catch (java.lang.NoSuchFieldException e) {");
        emitLine("            k = k.getSuperclass();");
        emitLine("        } catch (java.lang.ReflectiveOperationException e) {");
        emitLine("            return null;");
        emitLine("        }");
        emitLine("    }");
        emitLine("    return null;");
        emitLine("}");
        emitLine("// The table-typed boundary check now lives in the shared");
        emitLine("// descriptor-driven seam (ISSUE-0110): table reads emit");
        emitLine("// $check(\"table\", v) and the seam raises E8001 \"expected table,");
        emitLine("// got ...\" for a non-table value — the same shape the retired");
        emitLine("// per-kind table-boundary helper raised.");
        emitLine();
        emitLine("// ---- DEAL primitive array runtime support (ISSUE-0094) ----");
        emitLine("// int[]/number[]/string[]/boolean[] map to mutable wrapper classes — the");
        emitLine("// spec's specialized primitive array wrapper (spec-v1.2 §JVM value mapping).");
        emitLine("// The wrapper identity is stable across appends (writing at i == length grows");
        emitLine("// the wrapped storage in place), so aliases observe every write exactly like");
        emitLine("// LuaJIT's shared 1-based table. Every name here uses the __ prefix, which is");
        emitLine("// unreachable from javaName's translation (each DEAL underscore escapes to");
        emitLine("// $u), so no user binding, method, or field can collide with it.");
        emitLine("static final class __IntArray { long[] data; __IntArray(long[] data) { this.data = data; } }");
        emitLine("static final class __NumberArray { double[] data; __NumberArray(double[] data) { this.data = data; } }");
        emitLine("static final class __StringArray { java.lang.String[] data; __StringArray(java.lang.String[] data) { this.data = data; } }");
        emitLine("static final class __BooleanArray { boolean[] data; __BooleanArray(boolean[] data) { this.data = data; } }");
        emitLine("// array reads: a negative index is E8002 (LuaJIT's emitted negative-index");
        emitLine("// check); an index past the end is E8001 \"expected <T>, got null\" — LuaJIT");
        emitLine("// reads nil there and the read site's typed boundary fails with exactly that");
        emitLine("// shape (spec §Bounds and nil behavior: `let x: int = xs[99]` → nil is not");
        emitLine("// int). A primitive Java array cannot yield nil, so the JVM read raises the");
        emitLine("// boundary failure directly.");
        emitLine("static long __intArrayRead(__IntArray a, long i) { if (i < 0L) throw new DealError(\"E8002\", \"negative array index\"); if (i >= (long) a.data.length) throw new DealError(\"E8001\", \"expected int, got null\"); return a.data[(int) i]; }");
        emitLine("static double __numberArrayRead(__NumberArray a, long i) { if (i < 0L) throw new DealError(\"E8002\", \"negative array index\"); if (i >= (long) a.data.length) throw new DealError(\"E8001\", \"expected number, got null\"); return a.data[(int) i]; }");
        emitLine("static java.lang.String __stringArrayRead(__StringArray a, long i) { if (i < 0L) throw new DealError(\"E8002\", \"negative array index\"); if (i >= (long) a.data.length) throw new DealError(\"E8001\", \"expected string, got null\"); return a.data[(int) i]; }");
        emitLine("static boolean __booleanArrayRead(__BooleanArray a, long i) { if (i < 0L) throw new DealError(\"E8002\", \"negative array index\"); if (i >= (long) a.data.length) throw new DealError(\"E8001\", \"expected boolean, got null\"); return a.data[(int) i]; }");
        emitLine("// boxed array reads for === / !== operand positions (ISSUE-0094 rework):");
        emitLine("// the read-site contract (spec §Bounds and nil behavior) applies no typed");
        emitLine("// boundary to a comparison operand, so LuaJIT reads nil past the end and");
        emitLine("// computes nil === v (false) / nil !== v (true) / nil === nil (true) on");
        emitLine("// that value instead of raising E8001. A primitive Java read cannot yield");
        emitLine("// nil, so the comparison position boxes the read: null past the end (the");
        emitLine("// LuaJIT nil), the element value otherwise. A negative index still raises");
        emitLine("// E8002 — LuaJIT emits that check unconditionally at the read, whatever");
        emitLine("// the surrounding position.");
        emitLine("static java.lang.Long __intArrayReadBoxed(__IntArray a, long i) { if (i < 0L) throw new DealError(\"E8002\", \"negative array index\"); if (i >= (long) a.data.length) return null; return java.lang.Long.valueOf(a.data[(int) i]); }");
        emitLine("static java.lang.Double __numberArrayReadBoxed(__NumberArray a, long i) { if (i < 0L) throw new DealError(\"E8002\", \"negative array index\"); if (i >= (long) a.data.length) return null; return java.lang.Double.valueOf(a.data[(int) i]); }");
        emitLine("static java.lang.String __stringArrayReadBoxed(__StringArray a, long i) { if (i < 0L) throw new DealError(\"E8002\", \"negative array index\"); if (i >= (long) a.data.length) return null; return a.data[(int) i]; }");
        emitLine("static java.lang.Boolean __booleanArrayReadBoxed(__BooleanArray a, long i) { if (i < 0L) throw new DealError(\"E8002\", \"negative array index\"); if (i >= (long) a.data.length) return null; return java.lang.Boolean.valueOf(a.data[(int) i]); }");
        emitLine("// boolean boundary check for nil-aware && / || results (ISSUE-0094 rework):");
        emitLine("// a past-end boolean[] read in an and/or operand is Lua's nil — the");
        emitLine("// operand is falsy and the Lua result can itself be nil (nil and x yields");
        emitLine("// nil, nil or x yields x, true and nil yields nil). emitShortCircuit lowers such");
        emitLine("// expressions to boxed java.lang.Boolean temporaries (null = the Lua nil),");
        emitLine("// and every typed boolean boundary (declaration initializer, if/while");
        emitLine("// condition, return, argument, assignment, element write/literal) converts");
        emitLine("// with this helper: null fails exactly where LuaJIT's check_boolean(nil)");
        emitLine("// fails — E8001 \"expected boolean, got null\" (LuaJIT: E8001 \"expected");
        emitLine("// boolean\").");
        emitLine("static boolean booleanNotNull(java.lang.Boolean v) { if (v == null) throw new DealError(\"E8001\", \"expected boolean, got null\"); return v; }");
        emitLine("// array writes: 0 <= i <= length (E8002 otherwise); i == length appends one");
        emitLine("// element (spec §Array writes); the stored value is runtime-checked against the");
        emitLine("// element type — int elements route through checkInt (E8004, like LuaJIT's");
        emitLine("// check_int at the write), while the JVM static type system proves the");
        emitLine("// number/string/boolean element checks redundant (spec-v1.2 §JVM backend");
        emitLine("// contract). The write check runs after the value expression has been");
        emitLine("// evaluated — the helper call's Java arguments evaluate left to right before");
        emitLine("// the bounds check, per spec-v1.2 §Operational semantics rule 3.");
        emitLine("static long __intArrayWrite(__IntArray a, long i, long v) { if (i < 0L || i > (long) a.data.length) throw new DealError(\"E8002\", \"array index out of bounds\"); v = checkInt(v); if (i == (long) a.data.length) { long[] nd = new long[a.data.length + 1]; java.lang.System.arraycopy(a.data, 0, nd, 0, a.data.length); nd[nd.length - 1] = v; a.data = nd; } else { a.data[(int) i] = v; } return v; }");
        emitLine("static double __numberArrayWrite(__NumberArray a, long i, double v) { if (i < 0L || i > (long) a.data.length) throw new DealError(\"E8002\", \"array index out of bounds\"); if (i == (long) a.data.length) { double[] nd = new double[a.data.length + 1]; java.lang.System.arraycopy(a.data, 0, nd, 0, a.data.length); nd[nd.length - 1] = v; a.data = nd; } else { a.data[(int) i] = v; } return v; }");
        emitLine("static java.lang.String __stringArrayWrite(__StringArray a, long i, java.lang.String v) { if (i < 0L || i > (long) a.data.length) throw new DealError(\"E8002\", \"array index out of bounds\"); if (i == (long) a.data.length) { java.lang.String[] nd = new java.lang.String[a.data.length + 1]; java.lang.System.arraycopy(a.data, 0, nd, 0, a.data.length); nd[nd.length - 1] = v; a.data = nd; } else { a.data[(int) i] = v; } return v; }");
        emitLine("static boolean __booleanArrayWrite(__BooleanArray a, long i, boolean v) { if (i < 0L || i > (long) a.data.length) throw new DealError(\"E8002\", \"array index out of bounds\"); if (i == (long) a.data.length) { boolean[] nd = new boolean[a.data.length + 1]; java.lang.System.arraycopy(a.data, 0, nd, 0, a.data.length); nd[nd.length - 1] = v; a.data = nd; } else { a.data[(int) i] = v; } return v; }");
        emitLine();
        emitLine("// ---- DEAL nullable and nullable-array runtime support (ISSUE-0108) ----");
        emitLine("// (T | null)[] maps to a wrapper over BOXED elements (java.lang.Long[],");
        emitLine("// java.lang.Double[], java.lang.String[], java.lang.Boolean[]): Java null");
        emitLine("// is the DEAL null element, exactly like LuaJIT's table storage where nil/");
        emitLine("// __NULL is the null element. Reads yield null past the end (the LuaJIT");
        emitLine("// nil), and a negative index still raises E8002 (LuaJIT emits that check");
        emitLine("// unconditionally at the read). Writes accept null (check_nullable");
        emitLine("// permits it) and check only the index bounds.");
        emitLine("static final class __IntOrNullArray { java.lang.Long[] data; __IntOrNullArray(java.lang.Long[] data) { this.data = data; } }");
        emitLine("static final class __NumberOrNullArray { java.lang.Double[] data; __NumberOrNullArray(java.lang.Double[] data) { this.data = data; } }");
        emitLine("static final class __StringOrNullArray { java.lang.String[] data; __StringOrNullArray(java.lang.String[] data) { this.data = data; } }");
        emitLine("static final class __BooleanOrNullArray { java.lang.Boolean[] data; __BooleanOrNullArray(java.lang.Boolean[] data) { this.data = data; } }");
        emitLine("static java.lang.Long __intOrNullArrayWrite(__IntOrNullArray a, long i, java.lang.Long v) { if (i < 0L || i > (long) a.data.length) throw new DealError(\"E8002\", \"array index out of bounds\"); if (i == (long) a.data.length) { java.lang.Long[] nd = new java.lang.Long[a.data.length + 1]; java.lang.System.arraycopy(a.data, 0, nd, 0, a.data.length); nd[nd.length - 1] = v; a.data = nd; } else { a.data[(int) i] = v; } return v; }");
        emitLine("static java.lang.Double __numberOrNullArrayWrite(__NumberOrNullArray a, long i, java.lang.Double v) { if (i < 0L || i > (long) a.data.length) throw new DealError(\"E8002\", \"array index out of bounds\"); if (i == (long) a.data.length) { java.lang.Double[] nd = new java.lang.Double[a.data.length + 1]; java.lang.System.arraycopy(a.data, 0, nd, 0, a.data.length); nd[nd.length - 1] = v; a.data = nd; } else { a.data[(int) i] = v; } return v; }");
        emitLine("static java.lang.String __stringOrNullArrayWrite(__StringOrNullArray a, long i, java.lang.String v) { if (i < 0L || i > (long) a.data.length) throw new DealError(\"E8002\", \"array index out of bounds\"); if (i == (long) a.data.length) { java.lang.String[] nd = new java.lang.String[a.data.length + 1]; java.lang.System.arraycopy(a.data, 0, nd, 0, a.data.length); nd[nd.length - 1] = v; a.data = nd; } else { a.data[(int) i] = v; } return v; }");
        emitLine("static java.lang.Boolean __booleanOrNullArrayWrite(__BooleanOrNullArray a, long i, java.lang.Boolean v) { if (i < 0L || i > (long) a.data.length) throw new DealError(\"E8002\", \"array index out of bounds\"); if (i == (long) a.data.length) { java.lang.Boolean[] nd = new java.lang.Boolean[a.data.length + 1]; java.lang.System.arraycopy(a.data, 0, nd, 0, a.data.length); nd[nd.length - 1] = v; a.data = nd; } else { a.data[(int) i] = v; } return v; }");
        emitLine("// (T | null)[] reads: null past the end (the LuaJIT nil — a valid");
        emitLine("// nullable element, so NO E8001 at the read) and the stored boxed");
        emitLine("// element otherwise; a negative index still raises E8002 (LuaJIT");
        emitLine("// emits that check unconditionally at the read).");
        emitLine("static java.lang.Long __intOrNullArrayRead(__IntOrNullArray a, long i) { if (i < 0L) throw new DealError(\"E8002\", \"negative array index\"); if (i >= (long) a.data.length) return null; return a.data[(int) i]; }");
        emitLine("static java.lang.Double __numberOrNullArrayRead(__NumberOrNullArray a, long i) { if (i < 0L) throw new DealError(\"E8002\", \"negative array index\"); if (i >= (long) a.data.length) return null; return a.data[(int) i]; }");
        emitLine("static java.lang.String __stringOrNullArrayRead(__StringOrNullArray a, long i) { if (i < 0L) throw new DealError(\"E8002\", \"negative array index\"); if (i >= (long) a.data.length) return null; return a.data[(int) i]; }");
        emitLine("static java.lang.Boolean __booleanOrNullArrayRead(__BooleanOrNullArray a, long i) { if (i < 0L) throw new DealError(\"E8002\", \"negative array index\"); if (i >= (long) a.data.length) return null; return a.data[(int) i]; }");
        emitLine("// Class arrays (C[], (C | null)[]) map to __RefArray holding");
        emitLine("// java.lang.Object[]; every class declaration also emits a per-class");
        emitLine("// subclass ($Array$<C>) so instanceof proves the element type, plus");
        emitLine("// per-class read/write helpers with the nominal E8001 checks.");
        emitLine("static class __RefArray { java.lang.Object[] data; __RefArray(java.lang.Object[] data) { this.data = data; } }");
        emitLine("// The untyped-boundary checks the nullable slice needs (check_nullable");
        emitLine("// semantics for ?int/?number/?boolean/?string, the per-wrapper array");
        emitLine("// gates for [int]/[number]/[string]/[boolean] and their [?T]");
        emitLine("// widening forms) all live in the single shared descriptor-driven");
        emitLine("// $check(descriptor, value) seam now (ISSUE-0110, emitted after the");
        emitLine("// module body). The acceptance semantics are byte-for-byte the ones");
        emitLine("// the retired per-kind helpers carried: a Long crosses into number |");
        emitLine("// null, an integral in-range Double crosses into int | null (NaN/");
        emitLine("// infinity/non-integral E8001, out-of-safe-range E8004 — check_int");
        emitLine("// parity), null passes check_nullable, a plain T[] wrapper passes a");
        emitLine("// [?T] gate with the boxed copy, and a wrong wrapper raises E8001");
        emitLine("// \"expected array, got ...\".");
        emitLine("// int(v: int | null) / number(v: number | null) conversion intrinsics:");
        emitLine("// null fails at runtime with E8001 (runtime.lua's int_convert/");
        emitLine("// number_convert \"cannot convert null to ...\" shapes).");
        emitLine("static long intFromNullable(java.lang.Long v) { if (v == null) throw new DealError(\"E8001\", \"cannot convert null to int\"); return v; }");
        emitLine("static double numberFromNullable(java.lang.Double v) { if (v == null) throw new DealError(\"E8001\", \"cannot convert null to number\"); return v; }");
        emitLine();
        emitLine("// ---- DEAL stdlib support (ISSUE-0097, ISSUE-0106 v1.2 scalar strings): std/string, std/math, std/time ----");
        emitLine("// DEAL strings are sequences of Unicode scalar values (spec-v1.2 §String");
        emitLine("// escapes and Unicode), and std/string length/substring positions are");
        emitLine("// measured in Unicode scalar values. The JVM mapping (java.lang.String,");
        emitLine("// UTF-16 code units) therefore converts scalar positions to code-unit");
        emitLine("// offsets with codePointCount/offsetByCodePoints, so supplementary");
        emitLine("// characters count as ONE scalar value. Search/replace/trim operate on");
        emitLine("// whole strings, where scalar-value and code-unit semantics coincide.");
        emitLine("static long __strLength(java.lang.String s) { return (long) s.codePointCount(0, s.length()); }");
        emitLine("// v1.2 reference semantics (std/string.lua): positions are Unicode");
        emitLine("// scalar values; a negative start behaves as 0, a negative end yields");
        emitLine("// the empty string, an end beyond the string clamps to n, and");
        emitLine("// start >= end yields the empty string. The scalar bounds convert to");
        emitLine("// UTF-16 offsets via offsetByCodePoints.");
        emitLine("static java.lang.String __strSubstring(java.lang.String s, long start, long end) {");
        emitLine("    long n = (long) s.codePointCount(0, s.length());");
        emitLine("    int from = s.offsetByCodePoints(0, (int) java.lang.Math.min(java.lang.Math.max(start, 0L), n));");
        emitLine("    int to = s.offsetByCodePoints(0, (int) java.lang.Math.min(java.lang.Math.max(end, 0L), n));");
        emitLine("    if (to < from) return \"\";");
        emitLine("    return s.substring(from, to);");
        emitLine("}");
        emitLine("// Plain-text replace of every occurrence; an empty old returns s unchanged");
        emitLine("// (LuaJIT guards before gsub, which cannot match an empty pattern).");
        emitLine("static java.lang.String __strReplace(java.lang.String s, java.lang.String old, java.lang.String to) { return old.isEmpty() ? s : s.replace(old, to); }");
        emitLine("// Plain-text split: an empty s yields the empty array (whatever the");
        emitLine("// separator); an empty separator splits into individual Unicode scalar");
        emitLine("// values (spec-v1.2 §String lengths and positions are measured in");
        emitLine("// Unicode scalar values); otherwise every occurrence of sep delimits a");
        emitLine("// part, with the trailing remainder (even empty) appended.");
        emitLine("static __StringArray __strSplit(java.lang.String s, java.lang.String sep) {");
        emitLine("    java.util.ArrayList<java.lang.String> parts = new java.util.ArrayList<>();");
        emitLine("    if (s.isEmpty()) return new __StringArray(parts.toArray(new java.lang.String[0]));");
        emitLine("    if (sep.isEmpty()) {");
        emitLine("        int i = 0;");
        emitLine("        while (i < s.length()) {");
        emitLine("            int cp = s.codePointAt(i);");
        emitLine("            parts.add(new java.lang.String(java.lang.Character.toChars(cp)));");
        emitLine("            i += java.lang.Character.charCount(cp);");
        emitLine("        }");
        emitLine("        return new __StringArray(parts.toArray(new java.lang.String[0]));");
        emitLine("    }");
        emitLine("    int start = 0;");
        emitLine("    while (true) {");
        emitLine("        int found = s.indexOf(sep, start);");
        emitLine("        if (found < 0) { parts.add(s.substring(start)); break; }");
        emitLine("        parts.add(s.substring(start, found));");
        emitLine("        start = found + sep.length();");
        emitLine("    }");
        emitLine("    return new __StringArray(parts.toArray(new java.lang.String[0]));");
        emitLine("}");
        emitLine("// Lua pattern %s whitespace set: space, tab, newline, vertical tab, form feed,");
        emitLine("// carriage return (exactly the std/string.lua trim contract).");
        emitLine("static boolean __strIsTrimSpace(char c) { return c == ' ' || c == '\\t' || c == '\\n' || c == '\\u000b' || c == '\\f' || c == '\\r'; }");
        emitLine("static java.lang.String __strTrim(java.lang.String s) {");
        emitLine("    int st = 0;");
        emitLine("    int en = s.length();");
        emitLine("    while (st < en && __strIsTrimSpace(s.charAt(st))) st++;");
        emitLine("    while (en > st && __strIsTrimSpace(s.charAt(en - 1))) en--;");
        emitLine("    return s.substring(st, en);");
        emitLine("}");
        emitLine("// std/math.sqrt rejects negative inputs with E8001 (std/math.lua); NaN passes");
        emitLine("// through to NaN like LuaJIT's x < 0 guard and math.sqrt. floor/ceil/abs/min/max");
        emitLine("// map to java.lang.Math directly (same IEEE 754 semantics).");
        emitLine("static double __mathSqrt(double x) { if (x < 0.0) throw new DealError(\"E8001\", \"sqrt of negative number\"); return java.lang.Math.sqrt(x); }");
        emitLine();
    }

    /**
     * Emits the single shared descriptor-driven runtime-check seam
     * (ISSUE-0110) at the end of the generated class (after the module
     * body, so every declared class has appended its branches): ONE
     * emitted {@code $check(descriptor, value)} helper that every typed
     * boundary the JVM type system cannot prove routes through, with the
     * expected type spelled as its spec {@code RuntimeTypeDescriptor}
     * ({@code docs/spec-v1.2.md} §Runtime type descriptor format). A
     * {@code ?} prefix applies {@code check_nullable} (the DEAL null
     * passes through); the primitive branches carry the exact acceptance
     * semantics of the retired per-kind helpers (check_int parity for
     * {@code int} — a Long passes through {@code checkInt}, an integral
     * in-range Double converts, NaN/infinity/non-integral raise E8001,
     * out-of-safe-range raises E8004; {@code number} accepts every Long
     * and Double like check_number); {@code string} additionally runs the
     * v1.2 boundary string validation — an unpaired UTF-16 surrogate code
     * unit raises E8001 (spec-v1.2 §JVM value mapping); the array branches
     * gate on the
     * emitted wrappers with the {@code [?T]} widening forms (a plain
     * {@code T[]} wrapper passes a {@code [?T]} gate — the boxed copy /
     * shared-storage conversions the retired gates performed); the class
     * branches (collected per declared class during {@link #emitClass})
     * carry the module-qualified nominal checks; and every other
     * descriptor raises the deterministic mismatch shape
     * {@code "expected <descriptor>, got <$describe(v)>"} — the same
     * E8001 code and message shapes the per-feature helpers pinned, now
     * driven by one descriptor spelling.
     *
     * <p>The name {@code $check} is unreachable from {@link #javaName}
     * output (user {@code $} escapes to {@code $d}), so no DEAL function,
     * class, or field can collide with it — the retired
     * {@code $check<C>}/{@code $check$Table} collision dance disappears
     * with the per-kind helpers.
     */
    private void emitSharedCheckSeam() {
        emitLine();
        emitLine("// ---- Shared descriptor-driven runtime-check seam (ISSUE-0110) ----");
        emitLine("// One helper, one descriptor convention (spec RuntimeTypeDescriptor,");
        emitLine("// docs/spec-v1.2.md): every boundary the JVM type system cannot");
        emitLine("// prove routes through $check(descriptor, value). A ? prefix is");
        emitLine("// check_nullable: the DEAL null (Java null here) passes through.");
        emitLine("// ISSUE-0106 (v1.2 boundary string validation): the \"string\"");
        emitLine("// branch also scans for unpaired UTF-16 surrogate code units — the");
        emitLine("// JVM string representation contract (spec-v1.2 §JVM value mapping:");
        emitLine("// \"java.lang.String with no unpaired surrogate code units\"). A");
        emitLine("// string with a lone high or low surrogate raises E8001.");
        emitLine("static boolean __hasUnpairedSurrogate(java.lang.String s) {");
        emitLine("    for (int i = 0; i < s.length(); i++) {");
        emitLine("        char c = s.charAt(i);");
        emitLine("        if (java.lang.Character.isHighSurrogate(c)) {");
        emitLine("            if (i + 1 >= s.length() || !java.lang.Character.isLowSurrogate(s.charAt(i + 1))) return true;");
        emitLine("            i++;");
        emitLine("        } else if (java.lang.Character.isLowSurrogate(c)) {");
        emitLine("            return true;");
        emitLine("        }");
        emitLine("    }");
        emitLine("    return false;");
        emitLine("}");
        emitLine("static java.lang.Object $check(java.lang.String descriptor, java.lang.Object v) {");
        indent++;
        emitLine("if (descriptor.startsWith(\"?\")) {");
        indent++;
        emitLine("if (v == null) return null;");
        emitLine("descriptor = descriptor.substring(1);");
        indent--;
        emitLine("}");
        emitLine("if (descriptor.equals(\"table\")) { if (v instanceof $T t) return t; throw new DealError(\"E8001\", \"expected table, got \" + $describe(v)); }");
        emitLine("if (descriptor.equals(\"boolean\")) { if (v instanceof java.lang.Boolean b) return b; throw new DealError(\"E8001\", \"expected boolean, got \" + $describe(v)); }");
        emitLine("if (descriptor.equals(\"string\")) { if (v instanceof java.lang.String s) { if (__hasUnpairedSurrogate(s)) throw new DealError(\"E8001\", \"expected string, got string with unpaired surrogate code units\"); return s; } throw new DealError(\"E8001\", \"expected string, got \" + $describe(v)); }");
        emitLine("if (descriptor.equals(\"int\")) { if (v instanceof java.lang.Long l) return checkInt(l); if (v instanceof java.lang.Double d) { if (d.isNaN()) throw new DealError(\"E8001\", \"expected int, got NaN\"); if (d.isInfinite()) throw new DealError(\"E8001\", \"expected int, got infinity\"); if (d % 1.0 != 0.0) throw new DealError(\"E8001\", \"expected int, got non-integer number\"); return checkInt((long) (double) d); } throw new DealError(\"E8001\", \"expected int, got \" + $describe(v)); }");
        emitLine("if (descriptor.equals(\"number\")) { if (v instanceof java.lang.Long l) return (double) l; if (v instanceof java.lang.Double d) return d; throw new DealError(\"E8001\", \"expected number, got \" + $describe(v)); }");
        emitLine("if (descriptor.equals(\"[int]\")) { if (v instanceof __IntArray a) return a; throw new DealError(\"E8001\", \"expected array, got \" + $describe(v)); }");
        emitLine("if (descriptor.equals(\"[number]\")) { if (v instanceof __NumberArray a) return a; throw new DealError(\"E8001\", \"expected array, got \" + $describe(v)); }");
        emitLine("if (descriptor.equals(\"[string]\")) { if (v instanceof __StringArray a) return a; throw new DealError(\"E8001\", \"expected array, got \" + $describe(v)); }");
        emitLine("if (descriptor.equals(\"[boolean]\")) { if (v instanceof __BooleanArray a) return a; throw new DealError(\"E8001\", \"expected array, got \" + $describe(v)); }");
        emitLine("if (descriptor.equals(\"[?int]\")) { if (v instanceof __IntOrNullArray a) return a; if (v instanceof __IntArray a) { java.lang.Long[] nd = new java.lang.Long[a.data.length]; for (int i = 0; i < a.data.length; i++) nd[i] = java.lang.Long.valueOf(a.data[i]); return new __IntOrNullArray(nd); } throw new DealError(\"E8001\", \"expected array, got \" + $describe(v)); }");
        emitLine("if (descriptor.equals(\"[?number]\")) { if (v instanceof __NumberOrNullArray a) return a; if (v instanceof __NumberArray a) { java.lang.Double[] nd = new java.lang.Double[a.data.length]; for (int i = 0; i < a.data.length; i++) nd[i] = java.lang.Double.valueOf(a.data[i]); return new __NumberOrNullArray(nd); } throw new DealError(\"E8001\", \"expected array, got \" + $describe(v)); }");
        emitLine("if (descriptor.equals(\"[?string]\")) { if (v instanceof __StringOrNullArray a) return a; if (v instanceof __StringArray a) { java.lang.String[] nd = new java.lang.String[a.data.length]; java.lang.System.arraycopy(a.data, 0, nd, 0, a.data.length); return new __StringOrNullArray(nd); } throw new DealError(\"E8001\", \"expected array, got \" + $describe(v)); }");
        emitLine("if (descriptor.equals(\"[?boolean]\")) { if (v instanceof __BooleanOrNullArray a) return a; if (v instanceof __BooleanArray a) { java.lang.Boolean[] nd = new java.lang.Boolean[a.data.length]; for (int i = 0; i < a.data.length; i++) nd[i] = java.lang.Boolean.valueOf(a.data[i]); return new __BooleanOrNullArray(nd); } throw new DealError(\"E8001\", \"expected array, got \" + $describe(v)); }");
        for (String branch : classCheckBranches) {
            emitLine(branch);
        }
        emitLine("throw new DealError(\"E8001\", \"expected \" + descriptor + \", got \" + $describe(v));");
        indent--;
        emitLine("}");
    }

    // =========================================================================
    // Statements
    // =========================================================================

    private void emitStatement(StatementNode stmt) {
        // Reject value uses of a variable before its own declaration (and
        // forward references to later-declared module fields) — LuaJIT reads
        // nil for such uses and fails at runtime; a Java forward reference
        // would make javac reject an artifact the CLI reported as
        // successful. Only the statement's own value positions are checked;
        // nested statements are checked individually when emitted, so
        // sequential declarations inside a block stay clean.
        String undeclared = undeclaredVariableUse(stmt);
        if (undeclared != null) {
            unsupported("use of '" + undeclared + "' before its declaration "
                + "with no enclosing binding (Java rejects the forward "
                + "reference; LuaJIT reads the not-yet-declared global value "
                + "here, which is nil unless a prior write established it)",
                stmt.span());
            return;
        }

        switch (stmt) {
            case VariableDeclaration vd -> emitVariable(vd);
            case FunctionDeclaration fd -> emitFunction(fd, false);
            case ReturnStatement rs -> emitReturn(rs);
            case IfStatement is -> emitIf(is);
            case Block b -> emitBlock(b);
            case ExpressionStatement es -> emitExpressionStatement(es);
            case ImportDeclaration id -> emitImportTrigger(id);
            case ExportDeclaration ed -> emitExport(ed);
            case ClassDeclaration cd -> {
                if (moduleLevel) emitClass(cd);
                else unsupported("class declarations nested inside functions "
                    + "or blocks", cd.span());
            }
            case WhileStatement ws -> emitWhile(ws);
            case ForStatement fs -> unsupported("for loops", fs.span());
            case ForOfStatement fos -> emitForOf(fos);
            case BreakStatement bs -> unsupported("break", bs.span());
            case ContinueStatement cs -> unsupported("continue", cs.span());
            case DeleteStatement ds -> unsupported("delete", ds.span());
            case TryStatement ts -> unsupported("try/catch", ts.span());
            case ThrowStatement th -> unsupported("throw", th.span());
        }
    }

    private void emitExport(ExportDeclaration ed) {
        if (ed.declaration() instanceof FunctionDeclaration fd) {
            emitFunction(fd, true);
        } else if (ed.declaration() instanceof ClassDeclaration cd) {
            // ISSUE-0109: an exported class emits the same generated
            // nested class and nominal-check helper as a local class
            // (spec-v1.2 §Export forms: "Exporting a class exports class
            // metadata, not a constructor" — the metadata is the class's
            // generated Java type plus its module-qualified identity
            // string, which importers consume directly).
            emitClass(cd);
        } else {
            unsupported("this export form", ed.span());
        }
    }

    /**
     * Emits the load-time trigger for a project-module import (ISSUE-0096):
     * a {@code static { <Class>.__init$(); }} block at the import
     * statement's source position. Invoking a static method of a class
     * triggers that class's initialization (JLS §12.4.1), which runs the
     * imported module's load-time statements — exactly where LuaJIT runs
     * {@code require} for the import. The trigger is emitted even when the
     * alias is never used, so the imported module's load-time side effects
     * are never silently dropped. Stdlib imports have no trigger (the
     * builtins have no require-time side effects in the slice — ISSUE-0097
     * extends this from {@code std/console} to the other supported stdlib
     * modules).
     */
    private void emitImportTrigger(ImportDeclaration id) {
        // ISSUE-0100: a host-module import emits a load-time
        // {@code static { __hostLoad$<alias>(); }} block at the import
        // statement's source position — the JVM analog of LuaJIT's
        // {@code __rt.load_host("<raw path>", ...)} require. The block
        // runs the reflection presence check for every declared export
        // (missing host class or missing declared export → E8011, a
        // load-time error), exactly where LuaJIT raises the load-time
        // E8011 for a missing declared export.
        String raw = hostAliases.get(id.alias());
        if (raw != null) {
            emitLine("static {");
            indent++;
            emitLine(hostLoadMethodName(id.alias()) + "();");
            indent--;
            emitLine("}");
            return;
        }
        String module = importAliases.get(id.alias());
        if (module == null || SUPPORTED_STDLIB_MODULES.contains(module)) {
            return; // stdlib builtins: no load-time trigger
        }
        String className = classNameFor(module);
        emitLine("static {");
        indent++;
        emitLine(className + ".__init$();");
        indent--;
        emitLine("}");
    }

    // =========================================================================
    // Host modules (ISSUE-0100): the JVM host ABI slice
    // =========================================================================

    /**
     * Emits the host-module binding section once per generated class,
     * right after the runtime support: per-alias load methods (the
     * load-time presence checks the import triggers run), the per-export
     * {@code java.lang.reflect.Method} static fields, and the per-export
     * wrapper methods that DEAL member calls route through. Every emitted
     * name starts with {@code __host} or {@code $host}, which
     * {@link #javaName} can never produce (DEAL identifiers cannot
     * contain {@code $}, and every underscore escapes to {@code $u}), so
     * no user binding can collide with them.
     */
    private void emitHostBindings() {
        if (hostAliases.isEmpty()) return;
        emitLine("// ---- Host module bindings (ISSUE-0100 JVM host ABI slice) ----");
        emitLine("// A host module is a Java class named by the module path");
        emitLine("// (host/http -> HostHttp) whose static methods implement the");
        emitLine("// declared function exports (spec-v1.2 §JVM value mapping:");
        emitLine("// int -> long, number -> double, boolean -> boolean, string ->");
        emitLine("// java.lang.String, T | null -> the boxed reference, null -> Java");
        emitLine("// null). Return values arrive as java.lang.Object across the");
        emitLine("// untyped host boundary and are runtime-checked against the");
        emitLine("// declared return descriptor on every call (E8010 mismatch; the");
        emitLine("// spec forbids exposing Java null as DEAL null without");
        emitLine("// validation). Async exports must return a");
        emitLine("// java.util.concurrent.CompletableFuture (E8010 otherwise); the");
        emitLine("// await site joins it and checks the completion value (E8001).");
        for (Map.Entry<String, String> e : hostAliases.entrySet()) {
            String alias = e.getKey();
            String raw = e.getValue();
            Map<String, Type> exports = hostModules.get(raw);
            // The Method field per export is written by the load method's
            // presence check before any wrapper can run (the import's
            // static block precedes every later module-level use, and
            // function bodies run after module load).
            for (Map.Entry<String, Type> ex : exports.entrySet()) {
                emitLine("static java.lang.reflect.Method "
                    + hostMethodFieldName(alias, ex.getKey()) + ";");
            }
            emitHostLoadMethod(alias, raw, exports);
        }
        for (Map.Entry<String, String> e : hostAliases.entrySet()) {
            String alias = e.getKey();
            String raw = e.getValue();
            Map<String, Type> exports = hostModules.get(raw);
            for (Map.Entry<String, Type> ex : exports.entrySet()) {
                emitHostWrapperMethod(alias, raw, ex.getKey(),
                    (Type.Func) ex.getValue());
            }
        }
    }

    /** Emitted name of the load-time presence-check method for a host
     * import alias ({@code __hostLoad$<alias>}). */
    private String hostLoadMethodName(String alias) {
        return "__hostLoad$" + javaName(alias);
    }

    /** Emitted name of the cached {@code java.lang.reflect.Method} field
     * for one declared host export ({@code $host$<alias>$<fn>$m}). */
    private String hostMethodFieldName(String alias, String exportName) {
        return "$host$" + javaName(alias) + "$" + javaName(exportName) + "$m";
    }

    /** Emitted name of the wrapper method for one declared host export
     * ({@code __host$<alias>$<fn>}); DEAL calls {@code alias.fn(args)}
     * route through it. */
    private String hostWrapperName(String alias, String exportName) {
        return "__host$" + javaName(alias) + "$" + javaName(exportName);
    }

    /**
     * Emits the load-time presence-check method for one host import alias
     * (ISSUE-0100, spec §Host ABI): the host module runtime object must
     * expose every declared export (missing → load-time error), and extra
     * host exports are ignored. The JVM host module object is a Java class
     * (named {@link #classNameFor} of the module path) whose static
     * methods are the exports: {@code Class.forName} loads it (missing
     * class → E8011), and {@code getDeclaredMethod} with the
     * descriptor-derived parameter classes validates each declared export
     * (missing or signature-mismatched method → E8011). Only declared
     * exports are ever looked up, so extra methods on the host class are
     * structurally dropped. The cached {@code Method} values make the
     * wrapper calls re-execute the load-time validation result without
     * repeating the lookup.
     */
    private void emitHostLoadMethod(String alias, String raw,
                                    Map<String, Type> exports) {
        String clsName = classNameFor(raw);
        emitLine("// Load-time validation of host module '" + raw
            + "' (declared exports must exist; extras are ignored).");
        emitLine("static void " + hostLoadMethodName(alias) + "() {");
        indent++;
        emitLine("java.lang.Class<?> __h;");
        emitLine("try { __h = java.lang.Class.forName(\"" + clsName + "\"); }"
            + " catch (java.lang.ClassNotFoundException e) {"
            + " throw new DealError(\"E8011\", \"host module '" + raw
            + "' not found (class " + clsName + ")\"); }");
        for (Map.Entry<String, Type> ex : exports.entrySet()) {
            Type.Func f = (Type.Func) ex.getValue();
            StringBuilder pcs = new StringBuilder();
            for (int i = 0; i < f.paramTypes().size(); i++) {
                if (i > 0) pcs.append(", ");
                pcs.append(hostParamClassLiteral(f.paramTypes().get(i)));
            }
            emitLine(hostMethodFieldName(alias, ex.getKey())
                + " = __hostMethod(__h, \"" + raw + "\", "
                + quoteJavaString(ex.getKey()) + ", "
                + quoteJavaString(typeDescriptor(f)) + ","
                + " new java.lang.Class[]{ "
                + pcs + " });");
        }
        indent--;
        emitLine("}");
    }

    /**
     * Emits the wrapper method for one declared host function export
     * (ISSUE-0100). The wrapper's Java signature is the declared DEAL
     * signature's JVM mapping (spec §JVM value mapping), so DEAL call
     * sites pass statically-typed arguments — the JVM backend contract
     * permits method signatures to prove DEAL→host parameter checks
     * redundant. The host method is invoked reflectively, which keeps the
     * artifact independent of the host class's compile-time presence (a
     * missing host class is a LOAD-TIME E8011, never a javac failure) and
     * yields the host return as {@code java.lang.Object} — the untyped
     * boundary the return check validates:
     * <ul>
     *   <li>sync returns: {@code __hostCheck} validates the dynamic value
     *       against the declared return descriptor — wrong runtime kind →
     *       E8010, Java null for a non-nullable return → E8010 (the spec's
     *       "must not expose Java null as DEAL null without validation"),
     *       {@code T | null} accepts Java null as the DEAL null (the JVM
     *       null sentinel), an out-of-safe-range int → E8004;</li>
     *   <li>async exports: the host must return a
     *       {@code java.util.concurrent.CompletableFuture} — the backend
     *       async operation the await lowering accepts (spec §Host ABI
     *       and §Async/await: "A JVM backend may implement async
     *       lowering with ... blocking calls") — anything else → E8010;
     *       the wrapper joins it (the blocking await lowering) and checks
     *       the completion value against the declared return descriptor →
     *       E8001 at the await site, matching LuaJIT's await-site
     *       completion check.</li>
     * </ul>
     */
    private void emitHostWrapperMethod(String alias, String raw,
                                       String exportName, Type.Func f) {
        Type ret = f.returnType();
        String javaRet = hostReturnJavaType(ret);
        StringBuilder sig = new StringBuilder("static ").append(javaRet)
            .append(' ').append(hostWrapperName(alias, exportName))
            .append('(');
        List<String> argNames = new ArrayList<>();
        for (int i = 0; i < f.paramTypes().size(); i++) {
            if (i > 0) sig.append(", ");
            sig.append(hostParamJavaType(f.paramTypes().get(i)))
                .append(" __a").append(i);
            argNames.add("__a" + i);
        }
        sig.append(") {");
        emitLine(sig.toString());
        indent++;
        StringBuilder args = new StringBuilder();
        for (int i = 0; i < argNames.size(); i++) {
            if (i > 0) args.append(", ");
            args.append(argNames.get(i));
        }
        emitLine("java.lang.Object __r = __hostInvoke("
            + hostMethodFieldName(alias, exportName)
            + ", new java.lang.Object[]{ " + args + " });");
        String fn = raw + "." + exportName;
        if (f.isAsync()) {
            // Shape check at the call site (LuaJIT's E8010 "host async
            // function must return an async operation"), then the blocking
            // join; a failed operation propagates DEAL errors and wraps
            // other causes in E8010.
            emitLine("if (!(__r instanceof java.util.concurrent.CompletableFuture))"
                + " throw new DealError(\"E8010\", \"host async function '"
                + fn + "' must return an async operation, got \" + $describe(__r));");
            emitLine("java.lang.Object __v;");
            emitLine("try { __v = ((java.util.concurrent.CompletableFuture) __r).join(); }"
                + " catch (java.util.concurrent.CompletionException e) {"
                + " java.lang.Throwable __c = e.getCause();"
                + " if (__c instanceof RuntimeException rr) throw rr;"
                + " if (__c instanceof Error er) throw er;"
                + " throw new DealError(\"E8010\", \"host async function '"
                + fn + "' operation failed: \" + __c); }");
            emitLine(hostReturnStatement("__v", ret, fn, true));
        } else {
            emitLine(hostReturnStatement("__r", ret, fn, false));
        }
        indent--;
        emitLine("}");
    }

    /**
     * The return statement of a host wrapper: {@code null} returns run the
     * check and return nothing (a {@code void} method), value returns cast
     * the checked {@code __hostCheck} result to the boxed JVM mapping.
     * {@code completion} selects the error code — E8001 at the await site
     * for async completion values (LuaJIT's await-site check), E8010 for
     * sync returns (host-module-abi D3 case 2).
     */
    private String hostReturnStatement(String valueCode, Type ret, String fn,
                                       boolean completion) {
        if (ret instanceof Type.Null) {
            return "__hostCheck(\"null\", " + valueCode + ", "
                + quoteJavaString(fn) + ", " + completion + ");";
        }
        String cast = switch (ret) {
            case Type.Int ignored -> "java.lang.Long";
            case Type.Number ignored -> "java.lang.Double";
            case Type.Boolean ignored -> "java.lang.Boolean";
            case Type.String ignored -> "java.lang.String";
            case Type.Nullable n -> switch (n.inner()) {
                case Type.Int ignored -> "java.lang.Long";
                case Type.Number ignored -> "java.lang.Double";
                case Type.Boolean ignored -> "java.lang.Boolean";
                case Type.String ignored -> "java.lang.String";
                default -> "java.lang.Object";
            };
            default -> "java.lang.Object";
        };
        return "return (" + cast + ") __hostCheck("
            + quoteJavaString(typeDescriptor(ret)) + ", " + valueCode + ", "
            + quoteJavaString(fn) + ", " + completion + ");";
    }

    /**
     * Validates every declared export of a host module against the slice's
     * supported shapes (ISSUE-0100). Supported: function exports with
     * parameter types from int/number/boolean/string and their nullable
     * forms, and return types from the same set plus {@code null} (sync or
     * async). Everything else — class exports, array/table/function-typed
     * parameters or returns, nullable-of-unsupported, function-typed
     * returns — is an E6000 at the import statement, never a silently
     * miscompiled artifact. Returns true when every declared export is
     * supported.
     */
    private boolean validateHostExports(String raw, Map<String, Type> exports,
                                        Span span) {
        boolean ok = true;
        for (Map.Entry<String, Type> e : exports.entrySet()) {
            String name = e.getKey();
            Type t = e.getValue();
            if (t instanceof Type.Func f) {
                for (Type pt : f.paramTypes()) {
                    if (hostParamJavaType(pt) == null) {
                        unsupported("host export '" + name + "' of module '"
                            + raw + "' declares unsupported parameter type '"
                            + typeName(pt) + "' (the JVM host ABI slice "
                            + "supports int, number, boolean, string, and "
                            + "their nullable forms as parameters)", span);
                        ok = false;
                    }
                }
                Type ret = f.returnType();
                if (!hostReturnSupported(ret)) {
                    unsupported("host export '" + name + "' of module '"
                        + raw + "' declares unsupported return type '"
                        + typeName(ret) + "' (the JVM host ABI slice "
                        + "supports int, number, boolean, string, null, and "
                        + "their nullable forms as returns)", span);
                    ok = false;
                }
            } else {
                unsupported("host export '" + name + "' of module '" + raw
                    + "' (only function exports are in the JVM host ABI "
                    + "slice; host class exports are not supported yet)",
                    span);
                ok = false;
            }
        }
        return ok;
    }

    /** True when the declared return type of a host function is one the
     * slice's return boundary checks can validate. */
    private boolean hostReturnSupported(Type ret) {
        if (ret instanceof Type.Null) return true;
        if (ret instanceof Type.Nullable n) return hostReturnSupported(n.inner());
        return switch (ret) {
            case Type.Int ignored -> true;
            case Type.Number ignored -> true;
            case Type.Boolean ignored -> true;
            case Type.String ignored -> true;
            default -> false;
        };
    }

    /** Java parameter type for a host function parameter of the given
     * declared DEAL type; {@code null} when unsupported. */
    private String hostParamJavaType(Type t) {
        return switch (t) {
            case Type.Int ignored -> "long";
            case Type.Number ignored -> "double";
            case Type.Boolean ignored -> "boolean";
            case Type.String ignored -> "java.lang.String";
            case Type.Nullable n -> switch (n.inner()) {
                case Type.Int ignored -> "java.lang.Long";
                case Type.Number ignored -> "java.lang.Double";
                case Type.Boolean ignored -> "java.lang.Boolean";
                case Type.String ignored -> "java.lang.String";
                default -> null;
            };
            default -> null;
        };
    }

    /** Java {@code Class} literal for a host function parameter of the
     * given declared DEAL type (the load-time
     * {@code getDeclaredMethod} signature check). */
    private String hostParamClassLiteral(Type t) {
        return switch (t) {
            case Type.Int ignored -> "long.class";
            case Type.Number ignored -> "double.class";
            case Type.Boolean ignored -> "boolean.class";
            case Type.String ignored -> "java.lang.String.class";
            case Type.Nullable n -> switch (n.inner()) {
                case Type.Int ignored -> "java.lang.Long.class";
                case Type.Number ignored -> "java.lang.Double.class";
                case Type.Boolean ignored -> "java.lang.Boolean.class";
                case Type.String ignored -> "java.lang.String.class";
                default -> "java.lang.Object.class";
            };
            default -> "java.lang.Object.class";
        };
    }

    /** Java return type of a host wrapper method ({@code null} declares a
     * {@code void} method, mirroring {@link #javaReturnType}). */
    private String hostReturnJavaType(Type t) {
        if (t instanceof Type.Null) return "void";
        return switch (t) {
            case Type.Nullable n -> hostParamJavaType(n);
            default -> hostParamJavaType(t);
        };
    }

    // Host-boundary descriptors reuse the canonical ISSUE-0110 static
    // {@link #typeDescriptor(Type)} emitter — the spec
    // {@code RuntimeTypeDescriptor} spelling used for load-time
    // validation messages and the call-time dispatch inside
    // {@code __hostCheck} (the same descriptor conventions the LuaJIT
    // host ABI uses).

    // =========================================================================
    // Classes (ISSUE-0095: local classes and nominal checks)
    // =========================================================================

    /** The emitted Java name of the generated class for DEAL class
     * {@code name}. The {@code $C_} prefix is unreachable from
     * {@link #javaName} (every user {@code $} escapes to {@code $d}), so a
     * generated name can never collide with a translated user identifier or
     * with another generated name (javaName is injective and DEAL class
     * names are unique per module). */
    private String classNameForClass(String name) {
        return "$C_" + javaName(name);
    }

    /** The runtime identity string of a local class: the spec's
     * {@code ClassDescriptor} ({@code @<modulePath>/<name>}, bare name when
     * the module path is empty), mirroring the LuaJIT backend's
     * {@code qualifiedClassName} (runtime-class-identity D2(0)): every
     * producer and consumer in the module uses the backend-held module
     * path, never the checker's {@code Type.Class} module path (the
     * conformance adapter checks with the filename while codegen runs with
     * {@code Main}). */
    private String classIdentity(String name) {
        return (modulePath == null || modulePath.isEmpty())
            ? name : "@" + modulePath + "/" + name;
    }

    // =========================================================================
    // The shared JVM type-descriptor emitter and runtime-check seam
    // (ISSUE-0110)
    // =========================================================================

    /**
     * The ONE JVM type-descriptor emitter (ISSUE-0110), public and static
     * so the later function-value (ISSUE-0098) and async (ISSUE-0099)
     * slices — and the unit tests — consume exactly one spelling. Maps a
     * {@link Type} to the spec's {@code RuntimeTypeDescriptor} string
     * ({@code docs/spec-v1.2.md} §Runtime type descriptor format):
     * {@code ?T} nullables, {@code [T]} arrays, {@code @module/Name}
     * classes (bare name only for an empty module path — the same
     * spelling {@code IrDumper} produces), {@code (params)->ret} function
     * types with the {@code async} prefix, and the primitive/table/Error
     * forms (DEAL v1.2 has no rest parameters).
     */
    public static String typeDescriptor(Type t) {
        if (t == null) return "null";
        return switch (t) {
            case Type.Null ignored -> "null";
            case Type.Boolean ignored -> "boolean";
            case Type.Int ignored -> "int";
            case Type.Number ignored -> "number";
            case Type.String ignored -> "string";
            case Type.Table ignored -> "table";
            case Type.Error ignored -> "Error";
            case Type.Array arr -> "[" + typeDescriptor(arr.element()) + "]";
            case Type.Nullable n -> "?" + typeDescriptor(n.inner());
            case Type.Class cls -> {
                if (cls.modulePath() != null && !cls.modulePath().isEmpty()) {
                    yield "@" + cls.modulePath() + "/" + cls.name();
                }
                yield cls.name();
            }
            case Type.Func f -> {
                StringBuilder sb = new StringBuilder();
                if (f.isAsync()) sb.append("async");
                sb.append("(");
                for (int i = 0; i < f.paramTypes().size(); i++) {
                    if (i > 0) sb.append(",");
                    sb.append(typeDescriptor(f.paramTypes().get(i)));
                }
                sb.append(")->").append(typeDescriptor(f.returnType()));
                yield sb.toString();
            }
        };
    }

    /**
     * The seam-aligned descriptor for a runtime-check site: identical to
     * {@link #typeDescriptor(Type)} for every non-class type, and the
     * identity-aligned {@link #classCheckDescriptor} spelling for classes
     * (so the call-site descriptor always equals the branch key the
     * generated class appended to the seam — see
     * {@link #classCheckDescriptor}).
     */
    private String runtimeTypeDescriptor(Type t) {
        if (t instanceof Type.Class cls) return classCheckDescriptor(cls);
        return typeDescriptor(t);
    }

    /**
     * The spec {@code ClassDescriptor} string the runtime-check seam
     * dispatches on for class {@code cls}: the backend-held
     * module-qualified identity ({@link #classIdentity}) for a LOCAL
     * class — the exact string the generated class carries — and
     * {@code @<declaringModulePath>/<name>} for an imported class (the
     * checker records the declaring module path in {@code Type.Class},
     * and the declaring module's emitted seam branches on its own
     * {@code classIdentity}, which equals that path under the
     * orchestrator). Both spellings are the spec's
     * {@code ClassDescriptor} form; the locality split exists because the
     * single-module conformance harness checks with the source filename
     * while codegen runs with the backend-held module path — the same
     * alignment {@link #isLocalClassType} already encodes.
     */
    private String classCheckDescriptor(Type.Class cls) {
        if (isLocalClassType(cls)) return classIdentity(cls.name());
        return "@" + cls.modulePath() + "/" + cls.name();
    }

    /**
     * The element descriptor a table-read array target dispatches on
     * ({@code [int]}, {@code [string]}, {@code [?int]},
     * {@code [@module/Name]}, {@code [?@module/Name]}, …), or {@code null}
     * for an element type the slice does not support at the untyped
     * boundary (nested arrays, function arrays, table elements — the
     * caller records the E6000). Mirrors the element-type surface of the
     * retired per-wrapper gates exactly.
     */
    private String elementCheckDescriptor(Type element) {
        return switch (element) {
            case Type.Int ignored -> "int";
            case Type.Number ignored -> "number";
            case Type.String ignored -> "string";
            case Type.Boolean ignored -> "boolean";
            case Type.Nullable ne -> switch (ne.inner()) {
                case Type.Int ignored -> "?int";
                case Type.Number ignored -> "?number";
                case Type.String ignored -> "?string";
                case Type.Boolean ignored -> "?boolean";
                case Type.Class c -> "?" + classCheckDescriptor(c);
                default -> null;
            };
            case Type.Class c -> classCheckDescriptor(c);
            default -> null;
        };
    }

    /**
     * The emitted Java class name of the module declaring the imported
     * class {@code cls} ({@code "lib" → "Lib"}), or {@code null} with an
     * E6000 recorded when the module is not an imported compiled project
     * module of this module or its class declaration is unavailable
     * (ISSUE-0109). The module path recorded in {@code Type.Class} is
     * the checker's declaring-module path — the same value
     * {@link #importAliases} and the orchestrator's
     * {@code importedClasses} map carry — so the guard is exact, never
     * a bare-name guess.
     */
    private String importedClassModuleRef(Type.Class cls, Span span) {
        String module = cls.modulePath();
        Map<String, ClassDeclaration> decls = importedClasses.get(module);
        if (!importAliases.containsValue(module)
                || decls == null || !decls.containsKey(cls.name())) {
            unsupported("values of imported class type '" + cls.name()
                + "' from module '" + module + "' (the module is not an "
                + "imported compiled project module of this module, or "
                + "its class declaration is unavailable)", span);
            return null;
        }
        return classNameFor(module);
    }

    /** The emitted Java name of the per-class array wrapper for DEAL
     * class {@code name} ({@code $Array$<Name>}, extending the emitted
     * {@code __RefArray} so {@code instanceof} proves the element type).
     * The {@code $Array$} prefix is unreachable from {@link #javaName}
     * output for the same reason as {@code $C_} (every user {@code $}
     * escapes to {@code $d}). */
    private String classArrayWrapperName(String name) {
        return "$Array$" + javaName(name);
    }

    /** The emitted Java name of the per-class NULLABLE-element array
     * wrapper for DEAL class {@code name}
     * ({@code $ArrayOrNull$<Name>}, extending {@code __RefArray} like
     * {@link #classArrayWrapperName}). Distinct from the plain
     * {@code C[]} wrapper so a null-containing {@code (C | null)[]}
     * can never pass a {@code C[]}-typed boundary gate (LuaJIT's
     * check_array rejects its null element with E8003; the JVM raises
     * the documented storage-analog E8001 wrong-wrapper failure). */
    private String classOrNullArrayWrapperName(String name) {
        return "$ArrayOrNull$" + javaName(name);
    }

    /** Per-class array read helper (C[] reads): negative index → E8002,
     * past the end → E8001 "expected instance of &lt;identity&gt;, got
     * null" (LuaJIT reads nil there and the read site's typed class
     * boundary fails), otherwise the nominal-checked element. */
    private String classArrayReadName(String name) {
        return "$classArrayRead$" + javaName(name);
    }

    /** Per-class nullable-array read helper ((C | null)[] reads): like
     * {@link #classArrayReadName} but past the end yields the DEAL null
     * (a valid {@code C | null} element — LuaJIT's nil, no boundary
     * failure at the read). */
    private String classOrNullArrayReadName(String name) {
        return "$classOrNullArrayRead$" + javaName(name);
    }

    /** Per-class array write helper (C[] writes): index bounds → E8002,
     * a non-{@code C} value → E8001 (LuaJIT's check_type class branch). */
    private String classArrayWriteName(String name) {
        return "$classArrayWrite$" + javaName(name);
    }

    /** Per-class nullable-array write helper ((C | null)[] writes): null
     * passes (check_nullable permits it), a non-{@code C} non-null value
     * → E8001. */
    private String classOrNullArrayWriteName(String name) {
        return "$classOrNullArrayWrite$" + javaName(name);
    }

    /** True when a checker-inferred {@code Type.Class} refers to a class
     * of THIS module — the only classes whose generated Java types and
     * nominal-check helpers exist in the emitted artifact. The checker
     * records the declaring module path in the type; a local class's
     * path is the backend-held {@code modulePath} in the orchestrator,
     * the {@code sourcePath} in the single-module harnesses (where the
     * checker types with the source filename while codegen runs with
     * {@code Main}), or empty for the builtin {@code Error} class. Any
     * other path is an IMPORTED class (ISSUE-0109: mapped to the
     * declaring module's emitted class) even when
     * a same-named local class exists — the bare-name
     * {@code moduleClasses} lookup alone would silently claim a foreign
     * value for the local generated class (a broken artifact or a
     * nominal-identity corruption). */
    private boolean isLocalClassType(Type.Class cls) {
        String mp = cls.modulePath();
        return mp.isEmpty() || mp.equals(modulePath) || mp.equals(sourcePath);
    }

    /**
     * Emits a module-level (local or exported — ISSUE-0109) DEAL class
     * declaration: a
     * generated nested static class carrying the declared primitive fields
     * plus a runtime nominal-check helper. Spec v1.1 classes are sealed
     * records with no methods and no constructors — the only callables in
     * the module are the functions the earlier slices emit, so a class body
     * contributes no method surface. Required-present primitive
     * fields ({@code int}/{@code number}/{@code boolean}/{@code string}
     * with defaults) and required-present nullable primitive/class
     * fields ({@code f: T | null}, defaulting to the DEAL null) are in
     * scope; optional fields (nullable reads and presence checks), and
     * array/class/table-typed (non-nullable) fields are E6000.
     */
    private void emitClass(ClassDeclaration cd) {
        if (cd.isJsonable()) {
            unsupported("@jsonable classes", cd.span());
            return;
        }
        String gen = classNameForClass(cd.name());
        String identity = classIdentity(cd.name());
        // A default expression reading a module field declared AFTER the
        // class is E6000: LuaJIT evaluates the defaults table at the class
        // declaration (load time), where the later local does not exist yet
        // — the read binds to the global nil and fails with E8001 — while a
        // construction-time inline default would silently read the
        // initialized static field. Defaults reading already-declared
        // fields stay allowed (the inline per-construction evaluation the
        // spec's §Construction requires).
        for (ClassField cf : cd.fields()) {
            if (cf.defaultExpr().isPresent()) {
                String undeclared = undeclaredUseIn(cf.defaultExpr().get());
                if (undeclared != null) {
                    unsupported("class field default of '" + cd.name() + "."
                        + cf.name() + "' reading the module field '"
                        + undeclared + "' declared after the class "
                        + "(LuaJIT evaluates the defaults table at the class "
                        + "declaration and fails at load reading the global "
                        + "nil; Java would silently read the initialized "
                        + "static field)", cf.span());
                    return;
                }
            }
        }
        List<String> fieldTypes = new ArrayList<>();
        List<String> fieldNames = new ArrayList<>();
        for (ClassField cf : cd.fields()) {
            if (cf.optional()) {
                unsupported("optional class fields (their reads produce "
                    + "nullable values and presence checks)", cf.span());
                return;
            }
            Type fieldType = resolveTypeNode(cf.type());
            if (fieldType == Type.Error.INSTANCE) return;
            if (cf.nullable()) {
                // f: T | null — required-present nullable field
                // (ISSUE-0108). The parser keeps the WHOLE `T | null`
                // annotation as the field's type node and sets the
                // nullable flag, so the resolved type is already
                // Nullable(T). The inner type must be a primitive or a
                // LOCAL class (the nullable slice scope); the default is
                // the DEAL null (LuaJIT's __NULL defaults entry), which
                // the boxed Java reference holds natively.
                if (!(fieldType instanceof Type.Nullable nn)) {
                    unsupported("nullable class fields of type "
                        + typeName(fieldType), cf.span());
                    return;
                }
                boolean innerOk = nn.inner() instanceof Type.Int
                    || nn.inner() instanceof Type.Number
                    || nn.inner() instanceof Type.Boolean
                    || nn.inner() instanceof Type.String
                    || (nn.inner() instanceof Type.Class cls
                        && isLocalClassType(cls));
                if (!innerOk) {
                    unsupported("class fields of type " + typeName(fieldType)
                        + " (only primitive and local class nullable"
                        + " fields are supported)", cf.span());
                    return;
                }
            } else if (!(fieldType instanceof Type.Int)
                    && !(fieldType instanceof Type.Number)
                    && !(fieldType instanceof Type.Boolean)
                    && !(fieldType instanceof Type.String)) {
                unsupported("class fields of type " + typeName(fieldType)
                    + " (only primitive fields and nullable primitive/"
                    + "class fields are supported)", cf.span());
                return;
            }
            String javaType = javaLocalType(fieldType, cf.span());
            if (javaType == null) return;
            fieldTypes.add(javaType);
            fieldNames.add(javaName(cf.name()));
        }

        emitLine("// DEAL class " + cd.name() + " — identity " + identity);
        emitLine("static final class " + gen + " extends $Base {");
        indent++;
        for (int i = 0; i < fieldNames.size(); i++) {
            emitLine(fieldTypes.get(i) + " " + fieldNames.get(i) + ";");
        }
        StringBuilder params = new StringBuilder();
        for (int i = 0; i < fieldNames.size(); i++) {
            if (i > 0) params.append(", ");
            params.append(fieldTypes.get(i)).append(' ')
                .append(fieldNames.get(i));
        }
        emitLine(gen + "(" + params + ") {");
        indent++;
        emitLine("super(" + quoteJavaString(identity) + ");");
        for (String fieldName : fieldNames) {
            emitLine("this." + fieldName + " = " + fieldName + ";");
        }
        indent--;
        emitLine("}");
        indent--;
        emitLine("}");

        // The runtime nominal check now lives in the shared
        // descriptor-driven seam (ISSUE-0110): this class appends its
        // descriptor branches — @<identity> (wrong-class values report
        // "expected instance of <identity>, got <actual identity>",
        // non-class values "expected class instance", E8001 in both
        // shapes — the same semantics the retired per-class $check<C>
        // helper carried) — to the single emitted
        // $check(descriptor, value) helper.
        classCheckBranches.add("if (descriptor.equals("
            + quoteJavaString(identity) + ")) {");
        classCheckBranches.add("    if (v instanceof " + gen + " b) return b;");
        classCheckBranches.add("    java.lang.String actualIdentity = $identityOf(v);");
        classCheckBranches.add("    if (actualIdentity != null) throw new DealError(\"E8001\", \"expected instance of "
            + identity + ", got \" + actualIdentity);");
        classCheckBranches.add("    throw new DealError(\"E8001\", \"expected class instance, got \" + $describe(v));");
        classCheckBranches.add("}");

        // Class arrays (ISSUE-0108): C[] maps to a per-class __RefArray
        // subclass ($Array$<C>) and (C | null)[] maps to a DISTINCT
        // per-class subclass ($ArrayOrNull$<C>) so the two array types
        // are never the same wrapper — a null-containing (C | null)[]
        // crossing a C[]-typed boundary must fail the gate (LuaJIT's
        // check_array raises E8003 "array element N type mismatch" on
        // the null element; the JVM raises the documented
        // storage-analog E8001 wrong-wrapper failure instead of letting
        // the null element cross unvalidated). Both share the
        // __RefArray Object[] storage and the per-class read/write
        // helpers carrying the nominal E8001 checks. The identity
        // strings mirror the nominal check helper above.
        emitLine("static final class " + classArrayWrapperName(cd.name())
            + " extends __RefArray {");
        indent++;
        emitLine(classArrayWrapperName(cd.name())
            + "(java.lang.Object[] data) { super(data); }");
        indent--;
        emitLine("}");
        emitLine("static final class " + classOrNullArrayWrapperName(cd.name())
            + " extends __RefArray {");
        indent++;
        emitLine(classOrNullArrayWrapperName(cd.name())
            + "(java.lang.Object[] data) { super(data); }");
        indent--;
        emitLine("}");
        // The per-class array gates also live in the shared seam
        // (ISSUE-0110): [@<identity>] passes only THIS class's ARRAY
        // wrapper (the wrapper's Object[] storage plus the write-time
        // nominal checks already prove every element), and [?@<identity>]
        // additionally passes a plain C[] wrapper with SHARED Object[]
        // storage — LuaJIT's check_array validates every element against
        // check_nullable(C) and returns the SAME table, exactly like the
        // retired per-class gates. Anything else raises E8001 "expected
        // array, got ...".
        classCheckBranches.add("if (descriptor.equals("
            + quoteJavaString("[" + identity + "]") + ")) {");
        classCheckBranches.add("    if (v instanceof " + classArrayWrapperName(cd.name())
            + " a) return a;");
        classCheckBranches.add("    throw new DealError(\"E8001\", \"expected array, got \" + $describe(v));");
        classCheckBranches.add("}");
        classCheckBranches.add("if (descriptor.equals("
            + quoteJavaString("[?" + identity + "]") + ")) {");
        classCheckBranches.add("    if (v instanceof " + classOrNullArrayWrapperName(cd.name())
            + " a) return a;");
        classCheckBranches.add("    if (v instanceof " + classArrayWrapperName(cd.name())
            + " a) return new " + classOrNullArrayWrapperName(cd.name())
            + "(a.data);");
        classCheckBranches.add("    throw new DealError(\"E8001\", \"expected array, got \" + $describe(v));");
        classCheckBranches.add("}");
        emitLine("static " + gen + " " + classArrayReadName(cd.name())
            + "(__RefArray a, long i) {");
        indent++;
        emitLine("if (i < 0L) throw new DealError(\"E8002\", \"negative array index\");");
        emitLine("if (i >= (long) a.data.length) throw new DealError(\"E8001\", "
            + "\"expected instance of " + identity + ", got null\");");
        emitLine("java.lang.Object v = a.data[(int) i];");
        emitLine("if (v instanceof " + gen + " c) return c;");
        emitLine("throw new DealError(\"E8001\", \"expected instance of "
            + identity + ", got \" + $describe(v));");
        indent--;
        emitLine("}");
        emitLine("static " + gen + " " + classOrNullArrayReadName(cd.name())
            + "(__RefArray a, long i) {");
        indent++;
        emitLine("if (i < 0L) throw new DealError(\"E8002\", \"negative array index\");");
        emitLine("if (i >= (long) a.data.length) return null;");
        emitLine("java.lang.Object v = a.data[(int) i];");
        emitLine("if (v == null) return null;");
        emitLine("if (v instanceof " + gen + " c) return c;");
        emitLine("throw new DealError(\"E8001\", \"expected instance of "
            + identity + ", got \" + $describe(v));");
        indent--;
        emitLine("}");
        emitLine("static " + gen + " " + classArrayWriteName(cd.name())
            + "(__RefArray a, long i, java.lang.Object v) {");
        indent++;
        emitLine("if (i < 0L || i > (long) a.data.length) throw new DealError(\"E8002\", \"array index out of bounds\");");
        emitLine("if (!(v instanceof " + gen + " c)) throw new DealError(\"E8001\", \"expected instance of " + identity + ", got \" + $describe(v));");
        emitLine("if (i == (long) a.data.length) { java.lang.Object[] nd = new java.lang.Object[a.data.length + 1]; java.lang.System.arraycopy(a.data, 0, nd, 0, a.data.length); nd[nd.length - 1] = c; a.data = nd; } else { a.data[(int) i] = c; }");
        emitLine("return c;");
        indent--;
        emitLine("}");
        emitLine("static " + gen + " " + classOrNullArrayWriteName(cd.name())
            + "(__RefArray a, long i, java.lang.Object v) {");
        indent++;
        emitLine("if (i < 0L || i > (long) a.data.length) throw new DealError(\"E8002\", \"array index out of bounds\");");
        emitLine("if (v != null && !(v instanceof " + gen + " c)) throw new DealError(\"E8001\", \"expected instance of " + identity + ", got \" + $describe(v));");
        emitLine("if (v instanceof " + gen + " c) {");
        indent++;
        emitLine("if (i == (long) a.data.length) { java.lang.Object[] nd = new java.lang.Object[a.data.length + 1]; java.lang.System.arraycopy(a.data, 0, nd, 0, a.data.length); nd[nd.length - 1] = c; a.data = nd; } else { a.data[(int) i] = c; }");
        emitLine("return c;");
        indent--;
        emitLine("}");
        emitLine("if (i == (long) a.data.length) { java.lang.Object[] nd = new java.lang.Object[a.data.length + 1]; java.lang.System.arraycopy(a.data, 0, nd, 0, a.data.length); nd[nd.length - 1] = null; a.data = nd; } else { a.data[(int) i] = null; }");
        emitLine("return null;");
        indent--;
        emitLine("}");
    }

    private void emitVariable(VariableDeclaration vd) {
        Type declaredType = vd.typeAnnotation().isPresent()
            ? resolveTypeNode(vd.typeAnnotation().get())
            : typeOf(vd.initializer());
        String javaType = javaLocalType(declaredType, vd.span());
        if (javaType == null) return;

        // ISSUE-0091 rework: emit the initializer BEFORE declaring the
        // local. The checker binds a self-referencing RHS identifier
        // (`let x: int = x + 1` in an inner scope) to the ENCLOSING binding
        // — LuaJIT's `local x = x + 1` reads the outer x — so the RHS must
        // be emitted in the pre-declaration scope; only then is the
        // (disambiguated) name registered. A self-reference with no
        // enclosing binding was already rejected by emitStatement (E6000).
        String initializer = emitExpressionFor(vd.initializer(), declaredType);
        if (needsBooleanBoundary(vd.initializer(), declaredType)) {
            initializer = "booleanNotNull(" + initializer + ")";
        }
        initializer = coerceNullValueCode(initializer, vd.initializer(),
            javaType, vd.span());
        String visibility = moduleLevel ? "static " : "";
        String javaVar = declareLocal(vd.name(), declaredType);
        if (moduleLevel) {
            if (preStatementsDeclareTemps) {
                // The initializer references a temporary declared by the
                // hoisted pre-statements (a guarded && / || lowering); a
                // class-body initializer cannot reference a local of a
                // separate static block. Declare the field uninitialized
                // and assign it inside the same static block, in source
                // order.
                emitLine(visibility + javaType + " " + javaVar + ";");
                emitLine("static {");
                indent++;
                flushPreStatements();
                emitLine(javaVar + " = " + initializer + ";");
                indent--;
                emitLine("}");
                return;
            }
            // Hoisted side effects of a field initializer (e.g.
            // `let z: null = console.log("x")`) cannot stand bare in the
            // class body; wrap them in a static initializer emitted before
            // the field declaration, preserving source order.
            if (!preStatements.isEmpty()) {
                emitLine("static {");
                indent++;
                flushPreStatements();
                indent--;
                emitLine("}");
            }
        } else {
            // Emit the hoisted side effects before the declaration line so
            // they still see the pre-declaration scope (a shadowed
            // initializer's RHS binds to the enclosing variable).
            flushPreStatements();
        }
        emitLine(visibility + javaType + " " + javaVar + " = " + initializer + ";");
    }

    private void emitFunction(FunctionDeclaration fd, boolean exported) {
        // ISSUE-0100: async function declarations are supported through the
        // blocking lowering the spec permits for JVM backends (spec-v1.2
        // §Async operation semantics: "A JVM backend may implement async
        // lowering with virtual threads, blocking calls, futures, or
        // explicit state machines"). The body emits as a plain static
        // method: every DEAL async call is awaited immediately (the checker
        // rejects un-awaited async calls), and a host async call's wrapper
        // blocks on the returned CompletableFuture, so the body is
        // synchronous Java with DEAL's observable semantics. Async
        // function EXPRESSIONS stay E6000 (function values are out of the
        // slice).
        if (!moduleLevel) {
            unsupported("nested function declarations", fd.span());
            return;
        }
        Type returnType = resolveTypeNode(fd.returnType());
        String javaReturn = javaReturnType(returnType, fd.returnType().span());
        if (javaReturn == null) return;

        List<String> paramTypes = new ArrayList<>();
        boolean ok = true;
        for (Parameter p : fd.params()) {
            Type pt = resolveTypeNode(p.type());
            String jt = javaLocalType(pt, p.type().span());
            if (jt == null) { ok = false; break; }
            paramTypes.add(jt);
        }
        if (!ok) return;

        // A DEAL function whose name and mapped signature collide with an
        // emitted runtime helper would produce a duplicate Java method.
        String javaFn = javaName(fd.name());
        List<String> helperSignature = RUNTIME_HELPER_SIGNATURES.get(javaFn);
        if (helperSignature != null && helperSignature.equals(paramTypes)) {
            unsupported("function '" + fd.name() + "' whose signature "
                + "collides with the emitted runtime helper '" + javaFn + "'",
                fd.span());
            return;
        }

        // A pre-declaration function accessing a later-declared module
        // field is rejected (write-dominance analysis):
        //  - a WRITE binds to LuaJIT's GLOBAL of the same name (the
        //    module-local does not exist when the function value is
        //    created), leaving the module-local untouched so later readers
        //    observe the initializer value, while Java would write the
        //    static field and pollute every later reader — the
        //    write-then-read shape (`x = 5; return x;`) is included: the
        //    function's own read observes the global write under LuaJIT,
        //    but the polluted Java field remains observable by later
        //    readers;
        //  - a READ without a dominating write inside the function reads
        //    the global nil at call time and fails (E8001) while Java
        //    would silently read the initialized static field (writes via
        //    called functions and taken-only branches do not establish
        //    dominance — conservative).
        String forwardWriteViolation = forwardWriteViolations.get(fd.name());
        if (forwardWriteViolation != null) {
            unsupported("function '" + fd.name() + "' writing the module "
                + "field '" + forwardWriteViolation + "' declared after the "
                + "function (LuaJIT binds the pre-declaration write to the "
                + "GLOBAL of the same name — the module-local does not exist "
                + "when the function value is created — leaving the "
                + "module-local untouched so later readers observe the "
                + "initializer value; Java would write the static field and "
                + "pollute every later reader; the write-then-read shape is "
                + "included)", fd.span());
            return;
        }
        String forwardReadViolation = forwardReadViolations.get(fd.name());
        if (forwardReadViolation != null) {
            unsupported("function '" + fd.name() + "' reading the module "
                + "field '" + forwardReadViolation + "' declared after the "
                + "function without a dominating write inside the function "
                + "(LuaJIT reads the global nil at call time and fails with "
                + "E8001; Java would silently read the initialized static "
                + "field — a write via a called function does not establish "
                + "dominance, conservatively)", fd.span());
            return;
        }

        // Declare the parameters in a fresh scope BEFORE building the
        // signature: the signature must use each parameter's DECLARED
        // (possibly disambiguated) Java name, not the raw {@link #javaName}
        // translation. A parameter that shadows a module field (or another
        // visible binding) gets a {@code $n} suffix from
        // {@link #declareLocal}; emitting the raw name in the signature
        // while the body reads the suffixed name produced an artifact javac
        // rejected after the CLI reported success ({@code static long
        // f(long x) { return intAdd(x$1, 1L); }} — cannot find symbol
        // x$1).
        Map<String, String> paramScope = new LinkedHashMap<>();
        Map<String, Type> paramTypeScope = new LinkedHashMap<>();
        localScopes.push(paramScope);
        localTypeScopes.push(paramTypeScope);
        functionBindingNames.push(new LinkedHashSet<>());
        List<String> paramNames = new ArrayList<>();
        for (Parameter p : fd.params()) {
            Type pt = resolveTypeNode(p.type());
            paramNames.add(declareLocal(p.name(), pt));
        }

        StringBuilder sig = new StringBuilder();
        if (exported) sig.append("public ");
        sig.append("static ").append(javaReturn).append(' ')
            .append(javaFn).append('(');
        for (int i = 0; i < fd.params().size(); i++) {
            if (i > 0) sig.append(", ");
            sig.append(paramTypes.get(i)).append(' ')
                .append(paramNames.get(i));
        }
        sig.append(") {");
        emitLine(sig.toString());
        indent++;
        boolean savedModuleLevel = moduleLevel;
        int savedModuleIndex = currentModuleStatementIndex;
        Type savedReturnType = currentReturnType;
        currentReturnType = returnType;
        currentModuleStatementIndex = -1;
        moduleLevel = false;
        for (StatementNode stmt : fd.body().statements()) {
            emitStatement(stmt);
            if (!statementCompletesNormally(stmt)) {
                // Dead code after a non-completing statement: LuaJIT never
                // executes it and javac rejects it as unreachable
                // (JLS §14.21) — skip the rest of the body.
                flushPreStatements(); // defensive: empty at a statement boundary
                break;
            }
        }
        currentReturnType = savedReturnType;
        currentModuleStatementIndex = savedModuleIndex;
        moduleLevel = savedModuleLevel;
        localScopes.pop();
        localTypeScopes.pop();
        functionBindingNames.pop();

        indent--;
        emitLine("}");
    }

    private void emitReturn(ReturnStatement rs) {
        if (rs.expr().isEmpty()) {
            emitLine("return;");
            return;
        }
        ExpressionNode e = rs.expr().get();
        Type t = typeOf(e);
        boolean retNullable = currentReturnType instanceof Type.Nullable;
        if (t instanceof Type.Null) {
            if (isBareNullLiteral(e) || e instanceof IdentifierExpr) {
                // The null literal and a null-typed variable read have no
                // observable side effects — and a bare identifier is not a
                // valid Java expression statement. In a nullable-returning
                // function the DEAL null is the Java null reference (a
                // bare `return;` would make javac reject a non-void
                // method).
                emitLine(retNullable ? "return null;" : "return;");
                return;
            }
            // Any other null-typed return expression is a side-effecting
            // call or assignment (console.log/console.error, a
            // null-returning function) — LuaJIT evaluates it before
            // returning. Evaluate it first, then return; discarding it
            // would silently drop its output. Assignments must be emitted
            // without parentheses: a parenthesized assignment is not a
            // valid Java expression statement (JLS §14.8). Calls are
            // hoisted into pre-statements by emitExpression.
            if (e instanceof AssignmentExpr ae) {
                String core = emitAssignmentCore(ae);
                flushPreStatements();
                emitLine(core + ";");
            } else {
                emitExpression(e);
                flushPreStatements();
            }
            emitLine(retNullable ? "return null;" : "return;");
            return;
        }
        String value = emitExpressionFor(e, currentReturnType);
        if (needsBooleanBoundary(e, currentReturnType)) {
            // The boundary keys on the DECLARED return type: a
            // nil-capable boolean result crossing into a
            // `boolean | null` return is the DEAL null (LuaJIT's
            // check_nullable stores it, no failure), while a `boolean`
            // return fails with E8001 exactly where LuaJIT's
            // check_boolean fails.
            value = "booleanNotNull(" + value + ")";
        }
        flushPreStatements();
        emitLine("return " + value + ";");
    }

    /**
     * True when {@code stmt} can complete normally — i.e. execution can
     * fall through to the next statement. Mirrors JLS §14.21's
     * reachability rule (and Lua's actual execution): a {@code return}
     * cannot complete normally; an {@code if}/{@code else} whose branches
     * all cannot complete normally cannot complete normally (an
     * {@code if} without {@code else} always can); a block cannot
     * complete normally when its last statement cannot. Statements after
     * a non-completing statement are dead code — LuaJIT never executes
     * them and javac rejects them as unreachable — so emitters skip them
     * instead of producing an artifact the CLI would report as success.
     */
    private static boolean statementCompletesNormally(StatementNode stmt) {
        return switch (stmt) {
            case ReturnStatement rs -> false;
            case Block b -> {
                List<StatementNode> body = b.statements();
                yield body.isEmpty()
                    || statementCompletesNormally(body.get(body.size() - 1));
            }
            case IfStatement is -> {
                if (statementCompletesNormally(is.thenBlock())) {
                    yield true;
                }
                if (is.elseBranch().isEmpty()) {
                    yield true;
                }
                yield switch (is.elseBranch().get()) {
                    case Either.Left<IfStatement, Block> left ->
                        statementCompletesNormally(left.value());
                    case Either.Right<IfStatement, Block> right ->
                        statementCompletesNormally(right.value());
                };
            }
            // The emitted loop routes its condition through the loopCond
            // helper, so javac never sees a constant-expression condition:
            // per JLS §14.21 the statement can complete normally (and
            // statements after it stay reachable) unless the condition is
            // constant-true — which the helper wrapper makes impossible.
            case WhileStatement ws -> true;
            // Every other statement kind the skeleton emits completes
            // normally; unsupported kinds are rejected with E6000 when
            // emission reaches them.
            default -> true;
        };
    }

    private void emitIf(IfStatement is) {
        String condition = emitExpression(is.condition());
        if (needsBooleanBoundary(is.condition(), Type.Boolean.INSTANCE)) {
            condition = "booleanNotNull(" + condition + ")";
        }
        flushPreStatements();
        emitLine("if (" + condition + ") {");
        indent++;
        emitScopedBlock(is.thenBlock());
        indent--;
        if (is.elseBranch().isPresent()) {
            switch (is.elseBranch().get()) {
                case Either.Left<IfStatement, Block> left -> {
                    // Java requires the head block to be closed before "else".
                    String elseCondition = emitExpression(left.value().condition());
                    if (needsBooleanBoundary(left.value().condition(),
                            Type.Boolean.INSTANCE)) {
                        elseCondition = "booleanNotNull(" + elseCondition + ")";
                    }
                    if (preStatements.isEmpty()) {
                        emitLine("} else if (" + elseCondition + ") {");
                        indent++;
                        emitScopedBlock(left.value().thenBlock());
                        indent--;
                        emitIfContinuation(left.value());
                    } else {
                        // A condition with hoisted side effects cannot place
                        // statements between `}` and `else`; nest the chain
                        // in a plain else block instead.
                        emitLine("} else {");
                        indent++;
                        flushPreStatements();
                        emitLine("if (" + elseCondition + ") {");
                        indent++;
                        emitScopedBlock(left.value().thenBlock());
                        indent--;
                        emitIfContinuation(left.value());
                        indent--;
                        emitLine("}");
                    }
                }
                case Either.Right<IfStatement, Block> right -> {
                    emitLine("} else {");
                    indent++;
                    emitScopedBlock(right.value());
                    indent--;
                    emitLine("}");
                }
            }
        } else {
            emitLine("}");
        }
    }

    /** Emits the tail of an else-if chain whose head was already opened. */
    private void emitIfContinuation(IfStatement is) {
        if (is.elseBranch().isPresent()) {
            switch (is.elseBranch().get()) {
                case Either.Left<IfStatement, Block> left -> {
                    String condition = emitExpression(left.value().condition());
                    if (needsBooleanBoundary(left.value().condition(),
                            Type.Boolean.INSTANCE)) {
                        condition = "booleanNotNull(" + condition + ")";
                    }
                    if (preStatements.isEmpty()) {
                        emitLine("} else if (" + condition + ") {");
                        indent++;
                        emitScopedBlock(left.value().thenBlock());
                        indent--;
                        emitIfContinuation(left.value());
                    } else {
                        // Hoisted condition side effects: nest the chain
                        // (no statements may sit between `}` and `else`).
                        emitLine("} else {");
                        indent++;
                        flushPreStatements();
                        emitLine("if (" + condition + ") {");
                        indent++;
                        emitScopedBlock(left.value().thenBlock());
                        indent--;
                        emitIfContinuation(left.value());
                        indent--;
                        emitLine("}");
                    }
                }
                case Either.Right<IfStatement, Block> right -> {
                    emitLine("} else {");
                    indent++;
                    emitScopedBlock(right.value());
                    indent--;
                    emitLine("}");
                }
            }
        } else {
            emitLine("}");
        }
    }

    /**
     * Emits a for-of loop (ISSUE-0106 v1.2 slice). Only string iterables
     * are supported: the loop iterates Unicode scalar values (spec-v1.2
     * §For-of — one string containing exactly one scalar value per
     * iteration, in order), advancing by {@code Character.charCount}
     * code units per step so a supplementary character yields ONE
     * iteration. The loop variable is a fresh Java binding per iteration,
     * scoped to the loop body like LuaJIT's per-iteration local. Array
     * for-of stays rejected with E6000 (documented slice boundary).
     */
    private void emitForOf(ForOfStatement fos) {
        Type iterableType = typeOf(fos.iterable());
        if (!(iterableType instanceof Type.String)) {
            unsupported("for-of over arrays (this slice supports string "
                + "for-of only)", fos.span());
            return;
        }
        String iterable = emitExpression(fos.iterable());
        int n = forOfCounter++;
        String iterVar = "__iter" + n;
        String idxVar = "__i" + n;
        String cpVar = "__cp" + n;
        // The iterated expression evaluates exactly once, before the loop
        // (LuaJIT evaluates the for-of expression once). Hoisted side
        // effects of its evaluation run before the materialization line.
        flushPreStatements();
        emitLine("java.lang.String " + iterVar + " = " + iterable + ";");
        emitLine("for (long " + idxVar + " = 0L; " + idxVar + " < "
            + iterVar + ".length(); ) {");
        indent++;
        emitLine("int " + cpVar + " = " + iterVar + ".codePointAt((int) "
            + idxVar + ");");
        localScopes.push(new LinkedHashMap<>());
        localTypeScopes.push(new LinkedHashMap<>());
        String loopVar = declareLocal(fos.varName(), Type.String.INSTANCE);
        emitLine("java.lang.String " + loopVar
            + " = new java.lang.String(java.lang.Character.toChars("
            + cpVar + "));");
        emitLine(idxVar + " += (long) java.lang.Character.charCount("
            + cpVar + ");");
        emitScopedBlock(fos.body());
        localScopes.pop();
        localTypeScopes.pop();
        indent--;
        emitLine("}");
    }

    private void emitWhile(WhileStatement ws) {
        String condition = emitExpression(ws.condition());
        if (needsBooleanBoundary(ws.condition(), Type.Boolean.INSTANCE)) {
            condition = "booleanNotNull(" + condition + ")";
        }
        if (preStatements.isEmpty()) {
            // Plain form. The condition routes through the emitted loopCond
            // identity helper so javac never sees a constant-expression
            // condition (JLS §14.21): `while (true)` would make statements
            // after the loop unreachable (LuaJIT never executes them, but
            // javac rejects them), and `while (false)` would make the body
            // unreachable (LuaJIT accepts it and skips the body).
            emitLine("while (loopCond(" + condition + ")) {");
            indent++;
            emitScopedBlock(ws.body());
            indent--;
            emitLine("}");
            return;
        }
        // A condition whose evaluation hoisted side-effecting statements
        // (a null-typed call): LuaJIT re-evaluates the condition on every
        // iteration, so the hoisted statements must run inside the loop
        // before the condition test — never once before the loop. The
        // `while (true)` head plus the reachable non-constant `if
        // (!loopCond(...)) break;` keeps the statement completing normally
        // per JLS §14.21 (the break is reachable because loopCond(...) is
        // not a constant expression).
        emitLine("while (true) {");
        indent++;
        flushPreStatements();
        emitLine("if (!loopCond(" + condition + ")) { break; }");
        emitScopedBlock(ws.body());
        indent--;
        emitLine("}");
    }

    private void emitBlock(Block b) {
        emitLine("{");
        indent++;
        emitScopedBlock(b);
        indent--;
        emitLine("}");
    }

    private void emitScopedBlock(Block b) {
        localScopes.push(new LinkedHashMap<>());
        localTypeScopes.push(new LinkedHashMap<>());
        List<StatementNode> body = b.statements();
        for (int i = 0; i < body.size(); i++) {
            emitStatement(body.get(i));
            if (!statementCompletesNormally(body.get(i))) {
                // Dead code after a non-completing statement (a return,
                // or an if/else whose branches all return): LuaJIT never
                // executes it and javac rejects it as unreachable
                // (JLS §14.21) — skip the rest of the block.
                break;
            }
        }
        localScopes.pop();
        localTypeScopes.pop();
    }

    private void emitExpressionStatement(ExpressionStatement es) {
        if (es.expr() instanceof CallExpr call) {
            if (typeOf(call) instanceof Type.Null) {
                // A null-typed call (console.log/console.error, a
                // null-returning function) is hoisted into a pre-statement
                // by emitExpression; its void Java result is not a value.
                emitExpression(call);
                flushPreStatements();
            } else {
                String raw = emitCall(call);
                flushPreStatements();
                emitLine(raw + ";");
            }
            return;
        }
        if (es.expr() instanceof AssignmentExpr ae) {
            // Parenthesized assignment is not a Java statement.
            String core = emitAssignmentCore(ae);
            flushPreStatements();
            emitLine(core + ";");
            return;
        }
        if (isPrimitiveArrayRead(es.expr())) {
            // A standalone array read's value is discarded: LuaJIT's
            // emitted read is dropped (nil past the end never crosses a
            // typed boundary, so no E8001 — the spec read-site contract
            // applies no boundary to a discarded value), while a negative
            // index still raises E8002 (LuaJIT raises that unconditionally
            // at the read). The boxed read yields null past the end, and
            // the dummy-local discard genuinely evaluates the receiver,
            // the index, and the helper call.
            IndexExpr idx = (IndexExpr) es.expr();
            Type element = ((Type.Array) typeOf(idx.array())).element();
            String boxed = emitBoxedReadTemp(idx);
            flushPreStatements();
            emitLine(arrayBoxedJavaType(element) + " " + nextIgnoredName()
                + " = " + boxed + ";");
            return;
        }
        // Any other standalone expression statement (e.g. `x + 1;`) is
        // checker-accepted and LuaJIT evaluates it — an int overflow there
        // is an observable E8004. Lower it to a dummy-local declaration so
        // it is genuinely evaluated instead of being rejected or discarded.
        String value = emitExpression(es.expr());
        flushPreStatements();
        Type t = typeOf(es.expr());
        // A nil-aware && / || result is a boxed Boolean temporary (null =
        // the Lua nil); the dummy discard must not unbox it (a null would
        // NPE where LuaJIT silently drops the nil).
        String javaType = canYieldNil(es.expr())
            ? "java.lang.Boolean"
            : javaLocalType(t, es.span());
        if (javaType == null) return; // diagnostic already recorded
        emitLine(javaType + " " + nextIgnoredName() + " = " + value + ";");
    }

    // =========================================================================
    // Expressions
    // =========================================================================

    /**
     * Emits {@code e} with the read-site contextual target {@code target}
     * when {@code e} is a direct index read (see
     * {@link #emitIndexRead}); every other node shape emits normally — an
     * operator or call nested between the boundary and the read consumes
     * the read at ITS OWN typed operand position, where the element-typed
     * read (E8001 on the past-end nil) is the correct behavior. The
     * target only affects a DIRECT index-read child, exactly like
     * LuaJIT's check at the consuming boundary.
     */
    private String emitExpressionFor(ExpressionNode e, Type target) {
        if (e instanceof IndexExpr idx) return emitIndexRead(idx, target);
        return emitExpression(e);
    }

    /**
     * True when a read of {@code idx} (whose array element is the
     * non-nullable {@code T}) is consumed at a {@code T | null} target —
     * the read site's contextual target accepts the past-end nil
     * (LuaJIT's check_nullable), so the read must yield the DEAL null
     * instead of raising the element-typed E8001 (spec §Bounds and nil
     * behavior: the read result is checked against the TARGET type).
     */
    private boolean isNilYieldingReadTarget(IndexExpr idx, Type target) {
        if (!(typeOf(idx.array()) instanceof Type.Array arr)) return false;
        Type element = arr.element();
        if (element instanceof Type.Nullable) return false;
        if (!(target instanceof Type.Nullable nn)) return false;
        Type inner = nn.inner();
        if (element instanceof Type.Class eCls) {
            if (!(inner instanceof Type.Class tCls)) return false;
            // Class identity by name + locality: the checker types the
            // element with the SOURCE path while the backend resolves the
            // annotation with the MODULE path — both name the same local
            // class. Imported classes keep their module path in both.
            if (!eCls.name().equals(tCls.name())) return false;
            if (isLocalClassType(eCls) || isLocalClassType(tCls)) {
                return true;
            }
            return eCls.modulePath().equals(tCls.modulePath());
        }
        return Types.equals(inner, element);
    }

    private String emitExpression(ExpressionNode e) {
        return switch (e) {
            case LiteralExpr lit -> emitLiteral(lit);
            case IdentifierExpr id -> emitIdentifier(id);
            case BinaryExpr bin -> emitBinary(bin);
            case UnaryExpr u -> emitUnary(u);
            case CallExpr call -> {
                String raw = emitCall(call);
                if (typeOf(call) instanceof Type.Null) {
                    // Null-typed calls (console.log/console.error,
                    // null-returning functions) are void Java expressions.
                    // Hoist the call into a pre-statement emitted before the
                    // containing statement and yield the DEAL null value.
                    // Never wrap it in a lambda: a lambda capturing a local
                    // or parameter that is reassigned anywhere in its
                    // enclosing scope is a javac error, and DEAL locals and
                    // parameters are freely reassignable.
                    preStatements.add(new PreLine(raw + ";", 0));
                    yield "null";
                }
                yield raw;
            }
            case AssignmentExpr ae -> emitAssignment(ae);
            case MemberAccessExpr mae -> emitMemberAccessValue(mae);
            case IndexExpr idx -> emitIndexRead(idx, null);
            case ArrayLiteralExpr al -> emitArrayLiteral(al);
            case ObjectLiteralExpr ol -> emitObjectLiteral(ol);
            case FunctionExpr fe -> {
                unsupported("function expressions", fe.span());
                yield "null";
            }
            case HasExpr he -> {
                unsupported("has()", he.span());
                yield "false";
            }
            case TemplateLiteralExpr tl -> emitTemplateLiteral(tl);
            case AwaitExpression aw -> {
                // ISSUE-0100 blocking await lowering: the awaited call is a
                // direct async function call (the checker enforces both),
                // so emitting the callee call computes the completion value
                // synchronously — a DEAL async function's body already
                // blocked on ITS inner awaits and returns R directly, and a
                // HOST async function's wrapper validated the operation
                // shape (E8010), joined the CompletableFuture, and checked
                // the completion value against the declared return
                // descriptor (E8001 at this await site). No suspension
                // point exists in the emitted Java, so the checker's
                // after-await narrowing invalidation needs no codegen
                // counterpart (the type map already holds the un-narrowed
                // types here).
                yield emitExpression(aw.callee());
            }
        };
    }

    private String emitLiteral(LiteralExpr lit) {
        return switch (lit.value()) {
            case LiteralValue.NullLiteral() -> "null";
            case LiteralValue.BooleanLiteral b -> String.valueOf(b.value());
            case LiteralValue.IntLiteral i -> {
                long v = i.value();
                if (v > 9007199254740991L || v < -9007199254740991L) {
                    // Outside the DEAL int safe range ±(2^53-1): not a valid
                    // DEAL int value. LuaJIT silently rounds such literals
                    // to doubles before arithmetic and only fails when one
                    // crosses a check_int boundary; Java would silently
                    // compute with the exact long. Raise E8004 at the point
                    // of use — rejecting, never silently miscomputing.
                    yield "checkInt(" + v + "L)";
                }
                yield v + "L";
            }
            case LiteralValue.NumberLiteral n -> javaDoubleLiteral(n.value());
            case LiteralValue.StringLiteral s -> quoteJavaString(s.value());
        };
    }

    // =========================================================================
    // Object literals: class construction and tables (ISSUE-0095)
    // =========================================================================

    /** An object literal is either a class construction (class-typed
     * contextual target — the checker's {@code checkClassConstruction}),
     * a table literal ({@code table} target or no target), or out of
     * slice (E6000). */
    private String emitObjectLiteral(ObjectLiteralExpr ol) {
        Type t = typeOf(ol);
        if (t instanceof Type.Class cls) {
            return emitClassConstruction(cls, ol);
        }
        if (t instanceof Type.Table) {
            return emitTableLiteral(ol);
        }
        unsupported("object literals without a class or table target type",
            ol.span());
        return "null";
    }

    /**
     * Emits a class construction ({@code new $C_<Name>(args)} for a local
     * class, {@code new <ModuleClass>.$C_<Name>(args)} for an imported
     * class — ISSUE-0109). Provided field values are evaluated
     * left-to-right in literal order (LuaJIT evaluates the
     * provided-fields table in literal order), defaults are evaluated per
     * construction exactly as spec-v1.2 §Construction requires (inline,
     * at the construction site — never shared). The constructor argument
     * order is field declaration order, with provided values materialized
     * first so a side-effecting provided value runs in literal order
     * regardless of declaration order. The checker guarantees provided
     * names are declared fields and required fields are present; the
     * defensive branch only ever fires for a program the checker already
     * rejected.
     */
    private String emitClassConstruction(Type.Class cls, ObjectLiteralExpr obj) {
        // Locality is decided from the Type.Class MODULE PATH, then the
        // name-keyed lookup — a same-named local class must not satisfy
        // the guard for a foreign path (the literal would otherwise be
        // tagged with the LOCAL module identity — a silent
        // nominal-identity corruption).
        if (!isLocalClassType(cls)) {
            return emitImportedClassConstruction(cls, obj);
        }
        ClassDeclaration cd = moduleClasses.get(cls.name());
        if (cd == null) {
            unsupported("construction of class '" + cls.name()
                + "' (only local module-level classes are supported)",
                obj.span());
            return "null";
        }
        return emitClassConstructorCall(cd, classNameForClass(cd.name()),
            obj, true);
    }

    /**
     * Emits construction of an IMPORTED class (ISSUE-0109):
     * {@code new <ModuleClass>.$C_<Name>(args)} — the declaring module's
     * generated nested class, whose constructor takes every field in
     * declaration order (the same order the declaring module emitted).
     * Only required-present primitive fields are in scope (the same
     * class-shape restriction as local classes), and defaults are
     * restricted to literal constants: a non-literal default evaluates
     * in the DECLARING module's scope under LuaJIT (the defaults table
     * is built there at load time), so re-emitting it inline in the
     * importing module's scope would silently bind different
     * identifiers — E6000, never a silent miscompile.
     */
    private String emitImportedClassConstruction(Type.Class cls,
                                                 ObjectLiteralExpr obj) {
        Map<String, ClassDeclaration> decls = importedClasses.get(cls.modulePath());
        ClassDeclaration cd = decls == null ? null : decls.get(cls.name());
        if (cd == null) {
            unsupported("construction of imported class '" + cls.name()
                + "' from module '" + cls.modulePath() + "' (its "
                + "declaration is unavailable — the module is not an "
                + "imported compiled project module of this module, or "
                + "the class is not module-level)", obj.span());
            return "null";
        }
        if (!importAliases.containsValue(cls.modulePath())) {
            unsupported("construction of imported class '" + cls.name()
                + "' from module '" + cls.modulePath() + "' (the "
                + "declaring module is not imported)", obj.span());
            return "null";
        }
        for (ClassField cf : cd.fields()) {
            if (cf.optional()) {
                unsupported("optional fields of imported class '"
                    + cd.name() + "' (their reads produce nullable "
                    + "values)", cf.span());
                return "null";
            }
            if (cf.nullable()) {
                unsupported("nullable fields of imported class '"
                    + cd.name() + "'", cf.span());
                return "null";
            }
            Type fieldType = resolveTypeNode(cf.type());
            if (fieldType == Type.Error.INSTANCE) return "null";
            if (!(fieldType instanceof Type.Int)
                    && !(fieldType instanceof Type.Number)
                    && !(fieldType instanceof Type.Boolean)
                    && !(fieldType instanceof Type.String)) {
                unsupported("fields of imported class '" + cd.name()
                    + "' of type " + typeName(fieldType)
                    + " (only primitive fields are supported)", cf.span());
                return "null";
            }
        }
        for (ClassField cf : cd.fields()) {
            if (cf.defaultExpr().isPresent()
                    && !(cf.defaultExpr().get() instanceof LiteralExpr)) {
                unsupported("construction of imported class '" + cd.name()
                    + "' whose field '" + cf.name() + "' has a "
                    + "non-literal default (imported defaults evaluate "
                    + "in the declaring module's scope under LuaJIT, so "
                    + "re-emitting them in the importing module's scope "
                    + "would bind different identifiers)",
                    cf.defaultExpr().get().span());
                return "null";
            }
        }
        String moduleClass = classNameFor(cls.modulePath());
        return emitClassConstructorCall(cd,
            moduleClass + "." + classNameForClass(cd.name()), obj, false);
    }

    /**
     * Shared constructor-call emission for local and imported class
     * constructions (see {@link #emitClassConstruction}).
     *
     * @param cd            the class declaration (local or imported)
     * @param ctorExpr      the emitted constructor reference without
     *                      arguments ({@code "$C_Point"} or
     *                      {@code "Lib.$C_Point"})
     * @param obj           the object literal
     * @param localDefaults true for a local class, whose default
     *                      expressions were checked by this module's
     *                      checker (types in the typeMap, boolean
     *                      boundaries enforced); false for an imported
     *                      class, whose defaults are literal constants
     *                      validated by {@code emitImportedClassConstruction}
     */
    private String emitClassConstructorCall(ClassDeclaration cd, String ctorExpr,
                                            ObjectLiteralExpr obj,
                                            boolean localDefaults) {
        List<ExpressionNode> valueNodes = new ArrayList<>();
        List<Type> valueTargets = new ArrayList<>();
        for (Property prop : obj.properties()) {
            valueNodes.add(prop.value());
            // The read-site target of a provided value is the FIELD's
            // declared type: a direct T[] read provided to a T | null
            // field yields the DEAL null past the end (LuaJIT's
            // check_nullable at the construction boundary). Unknown
            // fields keep the element-typed read (the checker-gated
            // defensive path).
            Type fieldType = null;
            for (ClassField cf : cd.fields()) {
                if (cf.name().equals(prop.name())) {
                    fieldType = classFieldDeclaredType(cd, cf);
                    break;
                }
            }
            valueTargets.add(fieldType);
        }
        List<String> codes = emitOperandsInOrder(valueNodes, valueTargets);
        // The constructor arguments run in field DECLARATION order, but
        // LuaJIT evaluates the provided-fields table in LITERAL order — an
        // effectful provided value whose literal position differs from its
        // declaration position would otherwise run out of order. Every
        // still-inline effectful provided value is materialized into a
        // fresh temporary appended after the hoisted statements (emitOperands
        // InOrder already materialized every effectful value that precedes a
        // hoisting sibling, so the remaining ones all sit after the last
        // hoist and keep their relative literal order); pure values stay
        // inline.
        for (int i = 0; i < valueNodes.size(); i++) {
            String code = codes.get(i);
            if (code.startsWith("__t")) continue; // already materialized
            if (isPureAfterEmission(valueNodes.get(i))) continue;
            // The temporary's Java type follows the EMITTED code shape,
            // not just the static type: a null-typed effectful value
            // materializes into an Object temporary (javaLocalType(null)
            // is java.lang.Void and javac rejects
            // `java.lang.Void __t0 = (m = null);`), a nil-yielding read
            // (a direct T[] read at a T | null field) carries the boxed
            // element type, and a nil-aware && / || result stays boxed
            // (a `boolean` declaration would auto-unbox and NPE on null
            // before the constructor's booleanNotNull boundary could
            // raise E8001) — exactly like emitOperandsInOrder's own
            // materialization below.
            String javaType = materializationTempType(valueNodes.get(i),
                valueTargets.get(i));
            if (javaType == null) continue; // diagnostic already recorded
            String temp = nextEvalTempName();
            preStatements.add(new PreLine(
                javaType + " " + temp + " = " + code + ";", 0));
            preStatementsDeclareTemps = true;
            codes.set(i, temp);
        }
        Map<String, ExpressionNode> providedNodes = new LinkedHashMap<>();
        Map<String, String> provided = new LinkedHashMap<>();
        for (int i = 0; i < obj.properties().size(); i++) {
            providedNodes.put(obj.properties().get(i).name(),
                valueNodes.get(i));
            provided.put(obj.properties().get(i).name(), codes.get(i));
        }
        List<String> args = new ArrayList<>();
        for (ClassField cf : cd.fields()) {
            String code = provided.get(cf.name());
            ExpressionNode valueNode = providedNodes.get(cf.name());
            if (code == null && cf.defaultExpr().isPresent()) {
                valueNode = cf.defaultExpr().get();
                if (localDefaults) {
                    if (typeOf(valueNode) == Type.Error.INSTANCE) {
                        // The checker records every default subexpression's
                        // type (ISSUE-0095 rework); Type.Error here means the
                        // frontend reported errors — record an honest E6000,
                        // never emit an artifact javac would reject.
                        unsupported("default expression of field '" + cf.name()
                            + "' of class '" + cd.name()
                            + "' (unresolved default-expression type)",
                            valueNode.span());
                        code = zeroValueFor(cf.type());
                    } else {
                        code = emitExpressionFor(valueNode,
                            classFieldDeclaredType(cd, cf));
                    }
                } else {
                    // Imported defaults are literal constants (validated
                    // by emitImportedClassConstruction); they emit
                    // standalone and carry no nil-aware boolean shape.
                    code = emitExpression(valueNode);
                }
            }
            if (code != null && valueNode != null
                    && (localDefaults || providedNodes.containsKey(cf.name()))
                    && needsBooleanBoundary(valueNode,
                        classFieldDeclaredType(cd, cf))) {
                // The boundary keys on the FIELD's declared type: a
                // nil-capable boolean construction value provided to a
                // `boolean | null` field stores the DEAL null
                // (LuaJIT's check_nullable, no failure), while a
                // `boolean` field raises E8001 exactly where LuaJIT's
                // check_boolean fails. (The imported-default guard above
                // is ISSUE-0109: imported defaults are validated literal
                // constants and emit standalone.)
                code = "booleanNotNull(" + code + ")";
            }
            if (code == null) {
                // Checker-guaranteed unreachable (E4001 missing required
                // field); defensive E6000 keeps the artifact contract.
                unsupported("construction omitting the required-present field '"
                    + cf.name() + "' of class '" + cd.name() + "'", obj.span());
                code = zeroValueFor(cf.type());
            }
            if (valueNode != null) {
                Type declaredFieldType = classFieldDeclaredType(cd, cf);
                String fieldJava = declaredFieldType == null ? null
                    : javaLocalType(declaredFieldType, cf.span());
                code = coerceNullValueCode(code, valueNode, fieldJava,
                    valueNode.span());
            }
            args.add(code);
        }
        return "new " + ctorExpr + "(" + String.join(", ", args) + ")";
    }

    /** A placeholder for the defensive missing-required-field branch (the
     * artifact is discarded anyway — hasErrors gates compilation). */
    private static String zeroValueFor(TypeNode typeNode) {
        if (typeNode instanceof NamedType nt) {
            return switch (nt.name()) {
                case "int" -> "0L";
                case "number" -> "0.0";
                case "boolean" -> "false";
                case "string" -> "";
                default -> "null";
            };
        }
        return "null";
    }

    /**
     * Emits a table literal as {@code new $T().put(k1, v1).put(k2, v2)}.
     * Property values evaluate left-to-right in literal order (chained
     * {@code put} calls: each target evaluates before the next value
     * argument), with the hoisting machinery preserving order when a value
     * hoists a side-effecting null-typed call. Keys are the DEAL property
     * names verbatim (the spec's identifier-only property names), matching
     * LuaJIT's string-keyed table.
     */
    private String emitTableLiteral(ObjectLiteralExpr ol) {
        List<ExpressionNode> valueNodes = new ArrayList<>();
        for (Property prop : ol.properties()) valueNodes.add(prop.value());
        List<String> codes = emitOperandsInOrder(valueNodes);
        StringBuilder sb = new StringBuilder("new $T()");
        for (int i = 0; i < ol.properties().size(); i++) {
            sb.append(".put(")
                .append(quoteJavaString(ol.properties().get(i).name()))
                .append(", ").append(codes.get(i)).append(')');
        }
        return sb.toString();
    }

    /**
     * Lowers a template literal to Java string concatenation. The parser
     * alternates string-literal parts (even indices) with interpolated
     * expressions (odd indices); the checker types every interpolation as
     * {@code string} (E3016 otherwise) and the whole template as
     * {@code string}, so no runtime conversion is involved. Empty literal
     * parts are elided (Lua's {@code .. ""} concatenations contribute
     * nothing), and a template with no interpolations is just its literal.
     */
    private String emitTemplateLiteral(TemplateLiteralExpr tl) {
        List<ExpressionNode> parts = tl.parts();
        if (parts.size() == 1) {
            return emitExpression(parts.get(0));
        }
        StringBuilder sb = new StringBuilder("(");
        boolean first = true;
        for (int i = 0; i < parts.size(); i++) {
            String emitted = emitExpression(parts.get(i));
            if (i % 2 == 0 && emitted.equals("\"\"")) {
                continue; // empty literal part contributes nothing
            }
            if (!first) sb.append(" + ");
            sb.append(emitted);
            first = false;
        }
        sb.append(")");
        return sb.toString();
    }

    /** The emitted Java name bound to {@code name} in the nearest visible
     * scope, or {@code null} when {@code name} is not a variable. */
    private String localJavaName(String name) {
        for (Map<String, String> scope : localScopes) {
            String mapped = scope.get(name);
            if (mapped != null) return mapped;
        }
        return null;
    }

    /**
     * Declares {@code name} in the current scope and returns the emitted Java
     * name. Shadowing declarations (an emitted name already visible in an
     * enclosing scope) get a collision-free {@code $n} suffix, because Java
     * rejects redeclaring a visible local. The collision test compares
     * EMITTED Java names (the scope values) across every visible scope, not
     * DEAL names (the scope keys): a parameter or local already mapped to
     * {@code g$1} makes the emitted name {@code g$1} unavailable for a new
     * shadowing {@code g} (the old key-based test missed this — it asked
     * whether {@code g$1} was a KEY, but it is a VALUE, so the new binding
     * silently reused the taken name and its initializer's enclosing read
     * then referred to the uninitialized new binding instead of the outer
     * one). Module fields participate: their emitted static-field names are
     * visible to the whole module, and disambiguating against them keeps
     * the shadowed-initializer lowering (`long x$1 = x + 1;` — the RHS `x`
     * resolves to the field, since a Java local's scope starts at its own
     * initializer) legal.
     */
    private String declareLocal(String name, Type declaredType) {
        String base = javaName(name);
        String mapped = base;
        int n = 1;
        while (emittedNameVisible(mapped)) {
            mapped = base + "$" + (n++);
        }
        localScopes.peek().put(name, mapped);
        localTypeScopes.peek().put(name, declaredType);
        // Parameters and function-body top-level lets share ONE scope map,
        // so this put() can overwrite an earlier mapping of the same DEAL
        // name (the parameter's). The overwritten emitted name is still in
        // Java scope for the rest of the method (JLS §6.4) and must stay
        // reserved for collision purposes: record it in the current
        // function's emitted-name set, which outlives the map overwrite.
        if (!functionBindingNames.isEmpty()) {
            functionBindingNames.peek().add(mapped);
        }
        return mapped;
    }

    /** True when {@code emittedName} is already used as the emitted Java
     * name of any binding in a visible scope (module fields, enclosing
     * locals, parameters) or of any binding still in Java scope inside the
     * current function (the per-function {@link #functionBindingNames}
     * sets — including names whose scope-map key was overwritten by a
     * shadowing {@code let}, e.g. a shadowed parameter). Java forbids two
     * visible locals with the same name, so the new binding must take a
     * fresh one. */
    private boolean emittedNameVisible(String emittedName) {
        for (Set<String> names : functionBindingNames) {
            if (names.contains(emittedName)) return true;
        }
        for (Map<String, String> scope : localScopes) {
            if (scope.containsValue(emittedName)) return true;
        }
        return false;
    }

    private String emitIdentifier(IdentifierExpr id) {
        Type readType = typeOf(id);
        String mapped = localJavaName(id.name());
        if (mapped != null) {
            Type declared = declaredTypeForBinding(id.name());
            return adaptNarrowedRead(mapped, declared, readType);
        }
        Symbol sym = symbols.resolve(id.name());
        if (sym instanceof Symbol.VariableSymbol vs) {
            // A module field read: its declared type comes from the
            // module-scope symbol (a narrowed read of a nullable module
            // field inside a function unboxes / null-adapts like a
            // local).
            return adaptNarrowedRead(javaName(id.name()), vs.type(), readType);
        }
        if (sym instanceof Symbol.IntrinsicSymbol) {
            unsupported("conversion intrinsics used as first-class values", id.span());
            return "null";
        }
        if (sym instanceof Symbol.FunctionSymbol) {
            unsupported("functions used as first-class values", id.span());
            return "null";
        }
        if (sym instanceof Symbol.ModuleSymbol) {
            unsupported("module aliases used as values", id.span());
            return "null";
        }
        return javaName(id.name());
    }

    /** The declared type of the visible binding of {@code name}: nearest
     * local/parameter scope first (the {@link #localTypeScopes} stack,
     * parallel to {@link #localScopes}), then the module-scope symbol. */
    private Type declaredTypeForBinding(String name) {
        for (Map<String, Type> scope : localTypeScopes) {
            Type t = scope.get(name);
            if (t != null) return t;
        }
        Symbol sym = symbols.resolve(name);
        if (sym instanceof Symbol.VariableSymbol vs) return vs.type();
        return null;
    }

    /**
     * Adapts a read of a binding whose declared type is {@code T | null}
     * (stored as a boxed reference) to the read's checked type
     * (ISSUE-0108):
     * <ul>
     *   <li>a narrowed-to-{@code int}/{@code number}/{@code boolean} read
     *       unboxes ({@code n.longValue()} etc. — the narrowing is sound,
     *       so the boxed value is never null there);</li>
     *   <li>a read whose checked type is {@code null} is the DEAL null
     *       value — the plain {@code null} literal — REGARDLESS of the
     *       declared type: a narrowed nullable read and a binding
     *       DECLARED {@code null} ({@code let z: null}, a {@code z: null}
     *       parameter, a {@code null} module field) both read back as
     *       {@code null}, never as their emitted {@code java.lang.Void}
     *       name, so they flow into every nullable target
     *       ({@code int | null}, {@code string | null}, a class, an
     *       array) whose Java type javac would otherwise reject
     *       ({@code Void} cannot be converted to {@code Long});</li>
     *   <li>any other read (still nullable, or narrowed to a reference
     *       type — string/class/array) keeps the boxed reference, which
     *       already has the right Java type.</li>
     * </ul>
     */
    private String adaptNarrowedRead(String code, Type declared, Type read) {
        if (read instanceof Type.Null) return "null";
        if (!(declared instanceof Type.Nullable)) return code;
        return switch (read) {
            case Type.Int ignored -> code + ".longValue()";
            case Type.Number ignored -> code + ".doubleValue()";
            case Type.Boolean ignored -> code + ".booleanValue()";
            default -> code;
        };
    }

    /**
     * Coerces a null-typed ASSIGNMENT expression's emitted code into the
     * Java type of the surrounding position (ISSUE-0108). The emitted
     * code of {@code (m = null)} carries the assignment TARGET's Java
     * type (a boxed {@code java.lang.Long} when {@code m: int | null}),
     * while the expression's DEAL type is {@code null} — the checker
     * lets it flow into any nullable or null target, whose Java type can
     * differ ({@code java.lang.Void}, {@code java.lang.String}, a class
     * reference, …). Every OTHER null-typed shape already emits the bare
     * {@code null} literal (a null-typed call is hoisted into a
     * pre-statement and a null-typed identifier read emits {@code null}
     * via {@link #adaptNarrowedRead}), which Java accepts for every
     * reference target — only the assignment carries a mismatched static
     * type. The runtime value of a null-typed assignment is the DEAL
     * null, so the Object-mediated cast is sound for every reference
     * target and evaluates the assignment exactly once.
     */
    private String coerceNullValueCode(String code, ExpressionNode e,
                                       String targetJavaType, Span span) {
        if (targetJavaType == null) return code;
        if (e instanceof AssignmentExpr
                && typeOf(e) instanceof Type.Null) {
            return "(" + targetJavaType + ") (java.lang.Object) " + code;
        }
        return code;
    }

    private String emitBinary(BinaryExpr bin) {
        Type leftType = typeOf(bin.left());
        Type rightType = typeOf(bin.right());
        BinaryOp op = bin.op();

        // Boolean && / || short-circuit: the right operand is only
        // evaluated when the left operand does not already decide the
        // result (LuaJIT's `and`/`or` semantics). A null-typed
        // side-effecting call inside the right operand is hoisted into
        // pre-statements by emitExpression; flushing those statements
        // unconditionally would run the call even when the operand is
        // skipped (`false && helper() === null` must NOT call helper()).
        // The hoisted statements are guarded by the left operand and the
        // result is carried in a temporary instead.
        if ((op == BinaryOp.AND || op == BinaryOp.OR)
                && leftType instanceof Type.Boolean
                && rightType instanceof Type.Boolean) {
            return emitShortCircuit(bin, op == BinaryOp.AND);
        }

        // Array reads in === / !== operand positions (ISSUE-0094 rework,
        // ISSUE-0108 class-array extension):
        // the read-site contract (spec §Bounds and nil behavior) applies
        // no typed boundary to a comparison operand, so LuaJIT reads nil
        // past the end and computes the comparison on the nil value
        // (`nil === v` → false, `nil !== v` → true, `nil === nil` →
        // true) instead of raising the boundary failure. A primitive Java
        // read cannot yield nil; the comparison position is emitted with
        // nullable boxed reads and LuaJIT's nil semantics instead of the
        // typed read helpers (see emitArrayReadComparison) — local class
        // array reads (C[]) use the same lowering with the per-class
        // nullable read and reference identity.
        if (op == BinaryOp.EQ || op == BinaryOp.NEQ) {
            boolean leftRead = isNilCapableArrayRead(bin.left());
            boolean rightRead = isNilCapableArrayRead(bin.right());
            if (leftRead || rightRead || canYieldNil(bin.left())
                    || canYieldNil(bin.right())) {
                return emitArrayReadComparison(bin, op == BinaryOp.EQ);
            }
            // Nullable comparisons (ISSUE-0108): T | null ===/!== null,
            // null ===/!== T | null, and T | null ===/!== T | null with
            // LuaJIT's null semantics (null === null → true, null === v →
            // false). The checker enforces identical operand types, so at
            // most one side can be null-typed and both-nullable operands
            // share the same inner type.
            if (leftType instanceof Type.Nullable
                    || rightType instanceof Type.Nullable) {
                return emitNullableComparison(bin, op == BinaryOp.EQ);
            }
            // Reference identity comparisons: T[] === T[] and C === C are
            // value-equality by identity (LuaJIT's `==` on tables —
            // array wrappers and class instances are the same shape there),
            // exactly like the Lua backend's plain `(a == b)` fallthrough.
            if (leftType instanceof Type.Array
                    || leftType instanceof Type.Class) {
                List<String> idOps = emitOperandsInOrder(
                    List.of(bin.left(), bin.right()));
                return op == BinaryOp.EQ
                    ? "(" + idOps.get(0) + " == " + idOps.get(1) + ")"
                    : "(" + idOps.get(0) + " != " + idOps.get(1) + ")";
            }
        }

        List<String> operands = emitOperandsInOrder(
            List.of(bin.left(), bin.right()));
        String left = operands.get(0);
        String right = operands.get(1);

        // String concatenation: both operands must be string (checker-enforced).
        if (op == BinaryOp.ADD && leftType instanceof Type.String
                && rightType instanceof Type.String) {
            return "(" + left + " + " + right + ")";
        }

        // Integer arithmetic — always checked, with DEAL error codes.
        if (leftType instanceof Type.Int && rightType instanceof Type.Int) {
            return switch (op) {
                case ADD -> "intAdd(" + left + ", " + right + ")";
                case SUB -> "intSub(" + left + ", " + right + ")";
                case MUL -> "intMul(" + left + ", " + right + ")";
                case DIV -> "intDiv(" + left + ", " + right + ")";
                case MOD -> "intMod(" + left + ", " + right + ")";
                case POW -> "intPow(" + left + ", " + right + ")";
                case EQ -> "(" + left + " == " + right + ")";
                case NEQ -> "(" + left + " != " + right + ")";
                case LT -> "(" + left + " < " + right + ")";
                case LTE -> "(" + left + " <= " + right + ")";
                case GT -> "(" + left + " > " + right + ")";
                case GTE -> "(" + left + " >= " + right + ")";
                case AND -> "(" + left + " && " + right + ")";
                case OR -> "(" + left + " || " + right + ")";
            };
        }

        // Number arithmetic (mixed int/number widens like LuaJIT's number ops).
        if (leftType instanceof Type.Number || rightType instanceof Type.Number) {
            return switch (op) {
                case ADD -> "(" + left + " + " + right + ")";
                case SUB -> "(" + left + " - " + right + ")";
                case MUL -> "(" + left + " * " + right + ")";
                case DIV -> "(" + left + " / " + right + ")";
                case MOD -> "numMod(" + left + ", " + right + ")";
                case POW -> "java.lang.Math.pow(" + left + ", " + right + ")";
                case EQ -> "(" + left + " == " + right + ")";
                case NEQ -> "(" + left + " != " + right + ")";
                case LT -> "(" + left + " < " + right + ")";
                case LTE -> "(" + left + " <= " + right + ")";
                case GT -> "(" + left + " > " + right + ")";
                case GTE -> "(" + left + " >= " + right + ")";
                case AND -> "(" + left + " && " + right + ")";
                case OR -> "(" + left + " || " + right + ")";
            };
        }

        // Null equality: every null-typed value is the DEAL null value;
        // `null === null` is true (spec §Value equality). Operand side
        // effects were already hoisted into pre-statements by emitExpression,
        // so the emitted operands are null here. A null-typed ASSIGNMENT's
        // emitted code carries the assignment target's Java type (e.g.
        // `(m = null)` where `m: int | null` is Long-typed), so it is
        // widened to Object to compare against a Void-typed null operand.
        if (leftType instanceof Type.Null && rightType instanceof Type.Null) {
            String lc = bin.left() instanceof AssignmentExpr
                ? "(java.lang.Object) " + left : left;
            String rc = bin.right() instanceof AssignmentExpr
                ? "(java.lang.Object) " + right : right;
            return switch (op) {
                case EQ -> "(" + lc + " == " + rc + ")";
                case NEQ -> "(" + lc + " != " + rc + ")";
                default -> {
                    unsupported("operator " + op + " on null values", bin.span());
                    yield "null";
                }
            };
        }

        // Boolean equality (&& / || were handled by the short-circuit
        // branch above, which guards hoisted right-operand side effects).
        if (leftType instanceof Type.Boolean && rightType instanceof Type.Boolean) {
            return switch (op) {
                case EQ -> "(" + left + " == " + right + ")";
                case NEQ -> "(" + left + " != " + right + ")";
                default -> {
                    unsupported("operator " + op + " on booleans", bin.span());
                    yield "false";
                }
            };
        }

        // String equality and ordering. Ordering compares Unicode scalar
        // values (via the emitted scalarCompare helper): LuaJIT orders
        // bytewise in UTF-8, which is scalar-value order — including
        // supplementary characters, where String.compareTo's UTF-16
        // code-unit order diverges.
        if (leftType instanceof Type.String && rightType instanceof Type.String) {
            return switch (op) {
                case EQ -> "(" + left + ".equals(" + right + "))";
                case NEQ -> "(!" + left + ".equals(" + right + "))";
                case LT -> "(scalarCompare(" + left + ", " + right + ") < 0)";
                case LTE -> "(scalarCompare(" + left + ", " + right + ") <= 0)";
                case GT -> "(scalarCompare(" + left + ", " + right + ") > 0)";
                case GTE -> "(scalarCompare(" + left + ", " + right + ") >= 0)";
                default -> {
                    unsupported("operator " + op + " on strings", bin.span());
                    yield "\"\"";
                }
            };
        }

        unsupported("operator " + op + " on operand types "
            + typeName(leftType) + " and " + typeName(rightType), bin.span());
        return "null";
    }

    /**
     * Emits {@code ===}/{@code !==} where at least one operand has a
     * nullable declared type (ISSUE-0108), with LuaJIT's null semantics:
     * {@code T | null === null} → {@code l == null} (a null-typed
     * operand's value is the DEAL null, and its observable side effects
     * were already hoisted into pre-statements or materialized here);
     * {@code T | null === T | null} compares the unboxed values only when
     * both sides are non-null. Every operand that is NOT pure after
     * emission (an inline call, an assignment, checked int arithmetic) is
     * materialized into a fresh temporary first — left then right, so the
     * evaluation order stays strict left-to-right — because the lowered
     * comparison references each operand MORE THAN ONCE: an inline
     * effectful operand inside the ternary would otherwise run twice in
     * interleaved l,r,l,r order ({@code mark("l",1) === mark("r",2)}
     * printed l r l r), diverging from LuaJIT, which evaluates each
     * operand exactly once, strictly. The materialized form references
     * only inert temporaries, so Java's short-circuit inside the ternary
     * can never skip a DEAL-visible effect and never re-evaluates one
     * (LuaJIT evaluates both {@code ===} operands strictly). String
     * inners compare with {@code equals}, numeric inners unbox, and
     * class/array inners compare by identity (LuaJIT's `==` on tables).
     */
    private String emitNullableComparison(BinaryExpr bin, boolean eq) {
        Type leftType = typeOf(bin.left());
        Type rightType = typeOf(bin.right());
        boolean leftNullable = leftType instanceof Type.Nullable;
        boolean rightNullable = rightType instanceof Type.Nullable;
        List<String> codes = emitOperandsInOrder(
            List.of(bin.left(), bin.right()));
        String l = codes.get(0);
        String r = codes.get(1);
        if (leftNullable && rightNullable) {
            Type inner = ((Type.Nullable) leftType).inner();
            l = materializeIfEffectful(l, bin.left());
            r = materializeIfEffectful(r, bin.right());
            if (eq) {
                return "((" + l + " == null ? (" + r + " == null) : ("
                    + r + " != null && " + nullableEq(l, r, inner) + ")))";
            }
            return "((" + l + " == null ? (" + r + " != null) : ("
                + r + " == null || " + nullableNe(l, r, inner) + ")))";
        }
        if (leftNullable) {
            // The right side is a null-typed value: its observable side
            // effects (an inline assignment) must run before the
            // comparison — materialize it when it is not inert. The left
            // nullable operand is materialized FIRST (an inline effectful
            // left operand must run before the right operand's
            // materialized evaluation — LuaJIT evaluates left to right),
            // and the comparison itself then reads only inert values.
            l = materializeIfEffectful(l, bin.left());
            r = materializeIfEffectful(r, bin.right());
            return eq ? "(" + l + " == null)" : "(" + l + " != null)";
        }
        // Right nullable, left null-typed: the left operand's effects (a
        // hoisted call or an inline assignment) run before the right
        // operand, which the comparison references exactly once.
        l = materializeIfEffectful(l, bin.left());
        return eq ? "(" + r + " == null)" : "(" + r + " != null)";
    }

    /** Value equality of two non-null boxed nullable operands (the caller
     * guarded the null cases). */
    private String nullableEq(String l, String r, Type inner) {
        return switch (inner) {
            case Type.Int ignored -> l + ".longValue() == " + r + ".longValue()";
            case Type.Number ignored -> l + ".doubleValue() == " + r + ".doubleValue()";
            case Type.Boolean ignored -> l + ".booleanValue() == " + r + ".booleanValue()";
            case Type.String ignored -> l + ".equals(" + r + ")";
            default -> "(" + l + " == " + r + ")";
        };
    }

    /** Value inequality of two non-null boxed nullable operands. */
    private String nullableNe(String l, String r, Type inner) {
        return switch (inner) {
            case Type.Int ignored -> l + ".longValue() != " + r + ".longValue()";
            case Type.Number ignored -> l + ".doubleValue() != " + r + ".doubleValue()";
            case Type.Boolean ignored -> l + ".booleanValue() != " + r + ".booleanValue()";
            case Type.String ignored -> "(!" + l + ".equals(" + r + "))";
            default -> "(" + l + " != " + r + ")";
        };
    }

    /**
     * Emits a boolean {@code &&}/{@code ||} whose right operand may hoist
     * side-effecting statements, or whose operand can carry the Lua nil
     * of a past-end {@code boolean[]} read (see {@link #canYieldNil}).
     * When the right operand's emission hoisted statements, they are
     * guarded by the left operand so Java's short-circuit semantics hold
     * (the right operand must not be evaluated when the left operand
     * already decides the result), and the result is carried in a fresh
     * temporary:
     * <pre>
     *   boolean __sc0 = false;              // true for ||
     *   if (LEFT) { helper(); __sc0 = RIGHT; }   // if (!(LEFT)) for ||
     * </pre>
     * When the right operand has no hoisted statements, the plain
     * {@code (left && right)} form is emitted.
     *
     * <p>When either operand can yield nil, the result is a boxed
     * {@code java.lang.Boolean} temporary (null = the Lua nil): the
     * nil-capable operand is emitted as a nullable boxed temporary (a
     * boxed read helper call, or a nested nil-aware short-circuit temp),
     * the guard coerces the nil to falsy ({@code x != null &&
     * x.booleanValue()} — Lua's truthiness), and the result follows
     * Lua's {@code and}/{@code or}: {@code nil and x} → nil,
     * {@code nil or x} → x, {@code true and nil} → nil, {@code false or
     * nil} → nil. A plain inline-effectful left operand is materialized
     * once (the initializer and the guard both reference it), and the
     * right operand's hoisted statements — including a right-side boxed
     * read's helper call — stay inside the guard so a skipped right
     * operand never evaluates.
     */
    private String emitShortCircuit(BinaryExpr bin, boolean isAnd) {
        boolean leftNil = canYieldNil(bin.left());
        boolean rightNil = canYieldNil(bin.right());
        String left = emitBooleanNullableOperand(bin.left());
        int mark = preStatements.size();
        String right = emitBooleanNullableOperand(bin.right());
        boolean rightHoisted = preStatements.size() > mark;
        if (!leftNil && !rightNil && !rightHoisted) {
            return isAnd ? "(" + left + " && " + right + ")"
                         : "(" + left + " || " + right + ")";
        }
        // Extract the statements hoisted by the right operand and re-add
        // them inside a guard over the left operand, preserving their
        // relative order and evaluation order.
        List<PreLine> guarded = new ArrayList<>(
            preStatements.subList(mark, preStatements.size()));
        preStatements.subList(mark, preStatements.size()).clear();
        if (!leftNil && !rightNil) {
            // Plain boolean operands, hoisted right side effects only.
            String temp = nextShortCircuitName();
            preStatements.add(new PreLine(
                "boolean " + temp + " = " + (isAnd ? "false" : "true") + ";", 0));
            preStatements.add(new PreLine(
                isAnd ? "if (" + left + ") {" : "if (!" + left + ") {", 0));
            for (PreLine line : guarded) {
                preStatements.add(new PreLine(line.text(), line.extraIndent() + 1));
            }
            preStatements.add(new PreLine(temp + " = " + right + ";", 1));
            preStatements.add(new PreLine("}", 0));
            preStatementsDeclareTemps = true;
            return temp;
        }
        // Nil-aware lowering (ISSUE-0094 rework): a past-end boolean[]
        // read operand is Lua's nil — the operand is falsy and the Lua
        // result can itself be nil (`nil and x` → nil, `nil or x` → x,
        // `true and nil` → nil). The result is carried in a boxed
        // java.lang.Boolean temporary (null = the Lua nil) so a typed
        // boolean boundary can fail exactly where LuaJIT's
        // check_boolean(nil) fails, while `!`, nested && / ||, === / !==,
        // and discards consume the boxed value with Lua's nil semantics.
        // The left operand's own hoisted statements already precede the
        // guard (it always evaluates); the right operand's hoisted
        // statements — including a right-side boxed read's helper call —
        // stay inside the guard so Java's short-circuit skips them when
        // the left operand already decides the result, exactly like
        // LuaJIT's `and`/`or`.
        String leftCode = left;
        if (!leftNil && !isPureAfterEmission(bin.left())) {
            // A plain inline-effectful left operand (e.g. a call) is
            // referenced twice below (initializer + guard) — materialize
            // it once so its effects run exactly once.
            String lTemp = nextEvalTempName();
            preStatements.add(new PreLine(
                "boolean " + lTemp + " = " + left + ";", 0));
            preStatementsDeclareTemps = true;
            leftCode = lTemp;
        }
        String guard = leftNil
            ? "(" + leftCode + " != null && " + leftCode + ".booleanValue())"
            : leftCode;
        String init = leftNil ? leftCode
            : "java.lang.Boolean.valueOf(" + leftCode + ")";
        String temp = nextShortCircuitName();
        preStatements.add(new PreLine(
            "java.lang.Boolean " + temp + " = " + init + ";", 0));
        preStatements.add(new PreLine(
            isAnd ? "if (" + guard + ") {" : "if (!" + guard + ") {", 0));
        for (PreLine line : guarded) {
            preStatements.add(new PreLine(line.text(), line.extraIndent() + 1));
        }
        preStatements.add(new PreLine(temp + " = " + right + ";", 1));
        preStatements.add(new PreLine("}", 0));
        preStatementsDeclareTemps = true;
        return temp;
    }

    /** A fresh short-circuit temporary ({@code __sc0}, {@code __sc1}, …). */
    private String nextShortCircuitName() {
        String name = "__sc" + shortCircuitCounter;
        shortCircuitCounter++;
        return name;
    }

    /** A fresh evaluation-order temporary ({@code __t0}, {@code __t1}, …). */
    private String nextEvalTempName() {
        return "__t" + evalTempCounter++;
    }

    /**
     * True when the code emitted for {@code e} can no longer have an
     * observable effect at evaluation time (all of it is either inert or
     * already sequenced into {@link #preStatements}): literals and
     * identifier reads are inert; a null-typed call is always hoisted into
     * a pre-statement, so its emitted code is the inert {@code null}; a
     * non-null call, an assignment (its inline target write), checked int
     * arithmetic (E8004/E8005/E8006 can raise at evaluation time), and any
     * combination containing such a sub-expression stay effectful. When a
     * later sibling hoists, every earlier operand that is NOT pure after
     * emission must be materialized into a temporary before the hoisted
     * statements — otherwise its inline effects would run after them,
     * inverting LuaJIT's strict left-to-right evaluation (including the
     * nested case {@code f(g() + k(console.log("y")), console.log("x"))},
     * where the inline {@code k(…)} call inside the first operand must
     * still run before the second operand's hoisted print).
     */
    private boolean isPureAfterEmission(ExpressionNode e) {
        return switch (e) {
            case LiteralExpr lit -> true;
            case IdentifierExpr id -> true;
            case CallExpr call -> typeOf(call) instanceof Type.Null;
            case AssignmentExpr ae -> false;
            case BinaryExpr bin -> {
                if (typeOf(bin) instanceof Type.Int
                        && (bin.op() == BinaryOp.ADD || bin.op() == BinaryOp.SUB
                            || bin.op() == BinaryOp.MUL || bin.op() == BinaryOp.DIV
                            || bin.op() == BinaryOp.MOD || bin.op() == BinaryOp.POW)) {
                    yield false;
                }
                yield isPureAfterEmission(bin.left())
                    && isPureAfterEmission(bin.right());
            }
            case UnaryExpr u -> {
                if (u.op() == UnaryOp.NEG && typeOf(u) instanceof Type.Int) {
                    yield false;
                }
                yield isPureAfterEmission(u.expr());
            }
            // An array read can raise at evaluation time (E8002 negative
            // index / E8001 past the end), so it is never pure after
            // emission — a later hoisting operand must not run first.
            case IndexExpr idx -> false;
            // An array literal is inert apart from its elements: the
            // allocation has no observable effect in scope (array
            // equality is out of scope), so purity follows the elements.
            case ArrayLiteralExpr al -> {
                boolean pure = true;
                for (ExpressionNode elem : al.elements()) {
                    pure &= isPureAfterEmission(elem);
                }
                yield pure;
            }
            // Unsupported forms record an E6000 and emit no side effects,
            // but their emitted code is a placeholder — treat as effectful
            // so ordering never depends on them.
            default -> false;
        };
    }

    /**
     * Emits {@code nodes} (in evaluation order) and returns their inline
     * code strings, preserving DEAL's left-to-right evaluation order when
     * any operand hoists a side-effecting pre-statement. Hoisted
     * statements are flushed before the containing statement, so an
     * earlier <em>inline</em> side-effecting operand would otherwise run
     * after them — {@code f(g(), console.log("x"))} printed "x" before
     * {@code g()} ran, while LuaJIT evaluates arguments left to right
     * ("g-ran" first). Every operand that is followed by a hoisting
     * operand and whose emitted code is not pure after emission (an
     * inline call, an inline assignment, checked int arithmetic — even
     * inside a nested combination that hoisted other parts of itself) is
     * materialized into a fresh temporary assigned at the earliest hoist
     * start among the operands AFTER it — the start of the next hoisting
     * operand's pre-statement segment, i.e. immediately after the
     * operand's own evaluation (its own hoisted statements, when it
     * hoisted itself, already precede that point) and before the first
     * hoisted statement of every later operand. Operands after the last
     * hoist stay inline (the flush already precedes them), and operands
     * whose emitted code is inert (literals, reads, fully hoisted calls)
     * are left alone. The declarations
     * reference no user-controlled names ({@code __t<n>} is unreachable from
     * {@link #javaName}), and {@link #preStatementsDeclareTemps} is set so
     * a module-level field initializer referencing a materialized
     * temporary is routed through a static-block assignment (a class-body
     * initializer cannot see a block-local declaration).
     */
    private List<String> emitOperandsInOrder(List<ExpressionNode> nodes) {
        return emitOperandsInOrder(nodes, null);
    }

    /**
     * {@code emitOperandsInOrder} with a per-operand read-site target
     * type (see {@link #emitExpressionFor}): {@code targets.get(i)} is
     * the contextual target of a DIRECT index-read operand {@code i} (a
     * call parameter type, an assignment RHS target, an array-literal
     * element type, a class-construction field type), or {@code null}
     * for no nullable target. Only direct reads consume the target — an
     * operator nested between the boundary and the read types the read
     * at its own operand position, so the element-typed read stays.
     */
    private List<String> emitOperandsInOrder(List<ExpressionNode> nodes,
                                             List<Type> targets) {
        List<String> codes = new ArrayList<>(nodes.size());
        List<Integer> hoistStarts = new ArrayList<>(nodes.size());
        for (int i = 0; i < nodes.size(); i++) {
            int before = preStatements.size();
            codes.add(emitExpressionFor(nodes.get(i),
                targets == null ? null : targets.get(i)));
            hoistStarts.add(preStatements.size() > before ? before : -1);
        }
        // Process the hoisting operands right to left in contiguous
        // groups: for a hoisting operand j, the operands materialized
        // before j's first hoisted statement are exactly the earlier
        // operands from the previous hoisting operand (inclusive) up to
        // j — the previous hoisting operand's own inline remainder still
        // needs materialization immediately after its hoisted statements,
        // and no earlier operand may be anchored later than that earliest
        // boundary. Each materialization lands at the start of j's
        // hoisted statements, i.e. the earliest hoist start among the
        // operands AFTER the materialized operand: immediately after the
        // operand's own evaluation (its hoisted statements, when it
        // hoisted itself, already precede that point) and before the
        // first hoisted statement of every later operand. Anchoring at
        // the LAST hoisting operand's start instead would run the
        // operand's inline effects after an intermediate operand's
        // hoisted side effects (the
        // getArr("a", xs)[pick("i", console.log("b"))] = … miscompilation:
        // printed b, a instead of a, b). Within a group the
        // materializations are inserted consecutively in operand order,
        // so operand i's temporary assignment precedes operand i + 1's.
        // Processing groups right to left makes later insertions land
        // first and earlier insertions (smaller indices) only shift them
        // rightward — never reorder them. Every operand whose emitted
        // code is not pure after emission is materialized — including an
        // operand that hoisted itself but still carries an inline call
        // after its own hoisted statements (a nested combination), whose
        // inline effects would otherwise run after operand j's hoisted
        // statements.
        boolean[] materialized = new boolean[nodes.size()];
        List<Integer> hoistOps = new ArrayList<>(nodes.size());
        for (int i = 0; i < nodes.size(); i++) {
            if (hoistStarts.get(i) >= 0) hoistOps.add(i);
        }
        for (int g = hoistOps.size() - 1; g >= 0; g--) {
            int j = hoistOps.get(g);
            int first = g == 0 ? 0 : hoistOps.get(g - 1);
            int insertAt = hoistStarts.get(j);
            for (int i = first; i < j; i++) {
                if (materialized[i]) continue;
                if (isPureAfterEmission(nodes.get(i))) continue;
                // The temporary's Java type follows the EMITTED code
                // shape, not just the static type — see
                // materializationTempType.
                String javaType = materializationTempType(nodes.get(i),
                    targets == null ? null : targets.get(i));
                if (javaType == null) continue; // diagnostic already recorded
                String temp = nextEvalTempName();
                preStatements.add(insertAt++, new PreLine(
                    javaType + " " + temp + " = " + codes.get(i) + ";", 0));
                codes.set(i, temp);
                materialized[i] = true;
                preStatementsDeclareTemps = true;
            }
        }
        return codes;
    }

    /**
     * The Java type of a materialized operand temporary. The temporary
     * must match the EMITTED code shape, never just the static type:
     * <ul>
     * <li>a null-typed effectful operand (an assignment like
     * {@code (m = null)} whose emitted code carries the assignment
     * TARGET's boxed Java type) materializes into an Object temporary —
     * {@code javaLocalType(null)} is {@code java.lang.Void} and javac
     * rejects {@code java.lang.Void __t0 = (m = null);} after the CLI
     * reported success;</li>
     * <li>a nil-yielding read (a direct {@code T[]} read emitted at a
     * {@code T | null} target) carries the boxed element type
     * ({@code java.lang.Long} for an int read), never the unboxed
     * storage type;</li>
     * <li>a nil-aware {@code &&}/{@code ||} operand's emitted code is a
     * boxed {@code java.lang.Boolean} temporary (null = the Lua nil) —
     * a {@code boolean} declaration would auto-unbox and NPE on
     * null.</li>
     * </ul>
     */
    private String materializationTempType(ExpressionNode node,
                                           Type target) {
        Type t = typeOf(node);
        if (t instanceof Type.Null) return "java.lang.Object";
        if (node instanceof IndexExpr idx
                && isNilYieldingReadTarget(idx, target)) {
            Type element = ((Type.Array) typeOf(idx.array())).element();
            String boxed = boxedArrayJavaType(element, idx.span());
            if (boxed != null) return boxed;
        }
        if (canYieldNil(node) && !isPrimitiveArrayRead(node)) {
            return "java.lang.Boolean";
        }
        return javaLocalType(t, node.span());
    }

    private String emitUnary(UnaryExpr u) {
        return switch (u.op()) {
            case NOT -> {
                // LuaJIT's `not` coerces the operand's nil (a past-end
                // boolean[] read, or a nil-aware && / || result) to true
                // (`not nil` is true), so no E8001 is raised in this
                // position — the operand is emitted as a nullable boxed
                // temporary (always a temporary: a boxed read temp or a
                // short-circuit temp, so referencing it twice evaluates
                // it once) and the coercion is `(x == null ||
                // !x.booleanValue())`: not nil → true, not true → false,
                // not false → true.
                ExpressionNode operand = u.expr();
                if (canYieldNil(operand)) {
                    String code = emitBooleanNullableOperand(operand);
                    yield "(" + code + " == null || !" + code
                        + ".booleanValue())";
                }
                yield "(!" + emitExpression(operand) + ")";
            }
            case NEG -> {
                Type t = typeOf(u.expr());
                if (t instanceof Type.Int) yield "intNeg(" + emitExpression(u.expr()) + ")";
                if (t instanceof Type.Number) yield "(-" + emitExpression(u.expr()) + ")";
                unsupported("unary - on " + typeName(t), u.span());
                yield "0L";
            }
        };
    }

    private String emitCall(CallExpr call) {
        if (call.callee() instanceof MemberAccessExpr mae) {
            return emitMemberAccessCall(mae, call);
        }
        if (call.callee() instanceof IdentifierExpr id) {
            if (localJavaName(id.name()) != null) {
                unsupported("calls through non-function values", call.span());
                return "null";
            }
            Symbol sym = symbols.resolve(id.name());
            if (sym instanceof Symbol.IntrinsicSymbol) {
                return emitIntrinsicCall(id.name(), call);
            }
            if (!(sym instanceof Symbol.FunctionSymbol)) {
                unsupported("calls through non-function values", call.span());
                return "null";
            }
            // Module-level call to a module function whose body
            // (transitively) reads a module field declared later than the
            // call site: LuaJIT fails at load for such reads (the field is
            // nil until its declaration runs) while Java would silently
            // read the field's default value — reject instead of
            // miscompiling.
            if (currentModuleStatementIndex >= 0
                    && moduleFunctions.containsKey(id.name())) {
                String later = laterFieldRead(id.name(),
                    currentModuleStatementIndex);
                if (later != null) {
                    unsupported("module-level call of '" + id.name()
                        + "' whose body (transitively) reads the module "
                        + "field '" + later + "' declared at or after the "
                        + "call site (LuaJIT fails at load with a nil read; "
                        + "Java would silently read the default value)",
                        call.span());
                    return "null";
                }
                String laterFn = laterFunctionCall(id.name(),
                    currentModuleStatementIndex);
                if (laterFn != null) {
                    unsupported("module-level call of '" + id.name()
                        + "' reaching function '" + laterFn
                        + "' declared at or after the call site (LuaJIT "
                        + "assigns function values at their declaration "
                        + "point and fails at load with a nil read; Java "
                        + "hoists methods and would silently run)",
                        call.span());
                    return "null";
                }
                String laterImport = laterImportRead(id.name(),
                    currentModuleStatementIndex);
                if (laterImport != null) {
                    unsupported("module-level call of '" + id.name()
                        + "' whose body (transitively) uses the import '"
                        + laterImport + "' declared at or after the call "
                        + "site (LuaJIT has not run the require yet and "
                        + "fails at load; Java would silently initialize "
                        + "the imported class)", call.span());
                    return "null";
                }
            }
            List<Type> argTargets = new ArrayList<>(call.args().size());
            for (int i = 0; i < call.args().size(); i++) {
                argTargets.add(paramDeclaredType(id.name(), i));
            }
            List<String> argCodes = emitOperandsInOrder(call.args(), argTargets);
            StringBuilder sb = new StringBuilder(javaName(id.name())).append('(');
            for (int i = 0; i < argCodes.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(boundaryArgCode(call.args().get(i),
                    argCodes.get(i), paramDeclaredType(id.name(), i)));
            }
            return sb.append(')').toString();
        }
        unsupported("calls through non-identifier callees", call.span());
        return "null";
    }

    /**
     * A member access used as a direct call: {@code alias.fn(args)}.
     * {@code std/console} aliases map {@code log}/{@code error} to
     * {@code System.out}/{@code System.err}; the supported stdlib aliases
     * (ISSUE-0097) map to inline Java-library expressions or to emitted
     * {@code __str*}/{@code __mathSqrt} helpers; project-module aliases
     * (ISSUE-0096) map to a static call on the imported module's emitted
     * class ({@code lib.add(a, b)} → {@code Lib.add(a, b)}), which also
     * triggers the imported module's class initialization exactly where
     * LuaJIT's require has already run it (the import statement's trigger).
     */
    private String emitMemberAccessCall(MemberAccessExpr mae, CallExpr call) {
        if (!(mae.object() instanceof IdentifierExpr id)) {
            unsupported("member access on non-identifier objects", mae.span());
            return "null";
        }
        String module = importAliases.get(id.name());
        if (module == null) {
            unsupported("member access (only module function calls on "
                + "imported project modules, the supported stdlib "
                + "modules — std/console output, std/string, std/math, "
                + "std/time — and host modules are supported)", mae.span());
            return "null";
        }
        // ISSUE-0100: a member call through a host-module alias routes to
        // the per-alias wrapper method emitted by emitHostBindings. The
        // wrapper runs the load-time-validated reflective call plus the
        // declared-return boundary check; the checker already verified the
        // export exists (E2004) and typed every argument, so the emitted
        // static call is guaranteed to match the wrapper's signature.
        String hostRaw = hostAliases.get(id.name());
        if (hostRaw != null) {
            if (currentModuleStatementIndex >= 0) {
                Integer importIdx = importAliasStatementIndices.get(id.name());
                if (importIdx != null && importIdx > currentModuleStatementIndex) {
                    unsupported("module-level use of import '" + id.name()
                        + "' before its import statement (LuaJIT loads the "
                        + "host module at the import's source position and "
                        + "fails at load for an earlier use; Java would "
                        + "silently skip the load-time presence check)",
                        mae.span());
                    return "null";
                }
            }
            Map<String, Type> exports = hostModules.get(hostRaw);
            Type exportType = exports.get(mae.field());
            if (!(exportType instanceof Type.Func f)) {
                unsupported("host export '" + mae.field() + "' of module '"
                    + hostRaw + "'", mae.span());
                return "null";
            }
            // The wrapper's Java parameter types are the declared DEAL
            // parameter types' JVM mapping (visible to this backend), so
            // each argument routes through the same boundaryArgCode
            // adaptation every direct call uses — including the
            // Object-mediated coercion of null-typed assignment arguments
            // into the wrapper parameter's Java type.
            List<Type> argTargets = new ArrayList<>(call.args().size());
            for (int i = 0; i < call.args().size(); i++) {
                argTargets.add(f.paramTypes().get(i));
            }
            List<String> argCodes = emitOperandsInOrder(call.args(), argTargets);
            StringBuilder sb = new StringBuilder(
                hostWrapperName(id.name(), mae.field())).append('(');
            for (int i = 0; i < argCodes.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(boundaryArgCode(call.args().get(i),
                    argCodes.get(i), f.paramTypes().get(i)));
            }
            return sb.append(')').toString();
        }
        if ("std/console".equals(module)) {
            String target = switch (mae.field()) {
                case "log" -> "java.lang.System.out";
                case "error" -> "java.lang.System.err";
                default -> {
                    unsupported("export '" + mae.field() + "' of std/console", mae.span());
                    yield null;
                }
            };
            if (target == null) return "null";
            List<String> argCodes = emitOperandsInOrder(call.args());
            StringBuilder sb = new StringBuilder(target).append(".println(");
            for (int i = 0; i < argCodes.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(argCodes.get(i));
            }
            return sb.append(')').toString();
        }
        if ("std/string".equals(module)) {
            return emitStdlibStringMemberCall(mae, call);
        }
        if ("std/math".equals(module)) {
            return emitStdlibMathMemberCall(mae, call);
        }
        if ("std/time".equals(module)) {
            return emitStdlibTimeMemberCall(mae, call);
        }
        // Project-module import (ISSUE-0096): a static call on the imported
        // module's emitted class. The checker has already verified the
        // export exists (E2004) and typed the member as the exported
        // function, so the emitted method is guaranteed to be declared by
        // the imported module's artifact.
        if (currentModuleStatementIndex >= 0) {
            Integer importIdx = importAliasStatementIndices.get(id.name());
            if (importIdx != null && importIdx > currentModuleStatementIndex) {
                unsupported("module-level use of import '" + id.name()
                    + "' before its import statement (LuaJIT reads the "
                    + "not-yet-required global at load and fails; Java "
                    + "would silently initialize the imported class)",
                    mae.span());
                return "null";
            }
        }
        String className = classNameFor(module);
        List<String> argCodes = emitOperandsInOrder(call.args());
        StringBuilder sb = new StringBuilder(className).append('.')
            .append(javaName(mae.field())).append('(');
        for (int i = 0; i < argCodes.size(); i++) {
            if (i > 0) sb.append(", ");
            // A null-typed assignment argument carries the assignment
            // TARGET's Java type, and the imported module's parameter
            // Java type is not visible to this backend instance — the
            // shape could mismatch across modules (imports stay out of
            // the nullable slice, ISSUE-0108), so reject instead of
            // emitting an artifact javac would reject.
            if (call.args().get(i) instanceof AssignmentExpr ae
                    && typeOf(ae) instanceof Type.Null) {
                unsupported("null-typed assignment argument to an "
                    + "imported function call (the imported parameter's "
                    + "Java type is not visible to this backend)",
                    ae.span());
                return "null";
            }
            sb.append(argCodes.get(i));
        }
        return sb.append(')').toString();
    }

    /**
     * Emits a {@code std/string} member call (ISSUE-0097, ISSUE-0106 v1.2
     * scalar strings). The checker has already verified the export exists
     * (E2004) and typed every argument, so arity and types are
     * guaranteed. Plain-text search predicates map to the mapped {@code
     * java.lang.String} directly (whole-string matching coincides between
     * the scalar-value and UTF-16 views); {@code length}/{@code
     * substring}/{@code split} route through the emitted helpers whose
     * lengths and positions are measured in Unicode scalar values
     * (spec-v1.2 \u00a7String lengths and positions are measured in
     * Unicode scalar values), and {@code replace}/{@code trim} route
     * through helpers for the empty-{@code old} guard and the Lua
     * {@code %s} whitespace set.
     * The helper call's Java arguments evaluate left to right before the
     * helper body runs, preserving the spec's evaluation order.
     */
    private String emitStdlibStringMemberCall(MemberAccessExpr mae, CallExpr call) {
        List<String> argCodes = emitOperandsInOrder(call.args());
        String a0 = argCodes.get(0);
        StringBuilder sb = new StringBuilder();
        switch (mae.field()) {
            case "length" -> sb.append("__strLength(").append(a0).append(')');
            case "substring" -> sb.append("__strSubstring(").append(a0).append(", ")
                .append(argCodes.get(1)).append(", ").append(argCodes.get(2))
                .append(')');
            case "contains" -> sb.append('(').append(a0).append(").contains(")
                .append(argCodes.get(1)).append(')');
            case "startsWith" -> sb.append('(').append(a0).append(").startsWith(")
                .append(argCodes.get(1)).append(')');
            case "endsWith" -> sb.append('(').append(a0).append(").endsWith(")
                .append(argCodes.get(1)).append(')');
            case "replace" -> sb.append("__strReplace(").append(a0).append(", ")
                .append(argCodes.get(1)).append(", ").append(argCodes.get(2))
                .append(')');
            case "split" -> sb.append("__strSplit(").append(a0).append(", ")
                .append(argCodes.get(1)).append(')');
            case "trim" -> sb.append("__strTrim(").append(a0).append(')');
            default -> {
                unsupported("export '" + mae.field() + "' of std/string",
                    mae.span());
                return "null";
            }
        }
        return sb.toString();
    }

    /**
     * Emits a {@code std/math} member call (ISSUE-0097). {@code floor}/
     * {@code ceil}/{@code absNumber}/{@code minInt}/{@code maxInt} map to
     * {@code java.lang.Math} (the same IEEE 754 semantics as LuaJIT's
     * {@code math.*} over doubles); {@code absInt} re-checks its result
     * with {@code checkInt} exactly like LuaJIT's {@code
     * check_int(math.abs(x))}; {@code sqrt} routes through {@code
     * __mathSqrt} for the negative-input E8001 (std/math.lua). All
     * {@code java.lang} references are fully qualified so a user binding
     * named {@code Math} can never shadow them.
     */
    private String emitStdlibMathMemberCall(MemberAccessExpr mae, CallExpr call) {
        List<String> argCodes = emitOperandsInOrder(call.args());
        String a0 = argCodes.isEmpty() ? null : argCodes.get(0);
        return switch (mae.field()) {
            case "floor" -> "java.lang.Math.floor(" + a0 + ")";
            case "ceil" -> "java.lang.Math.ceil(" + a0 + ")";
            case "sqrt" -> "__mathSqrt(" + a0 + ")";
            case "absInt" -> "checkInt(java.lang.Math.abs(" + a0 + "))";
            case "absNumber" -> "java.lang.Math.abs(" + a0 + ")";
            case "minInt" -> "java.lang.Math.min(" + a0 + ", "
                + argCodes.get(1) + ")";
            case "maxInt" -> "java.lang.Math.max(" + a0 + ", "
                + argCodes.get(1) + ")";
            default -> {
                unsupported("export '" + mae.field() + "' of std/math",
                    mae.span());
                yield "null";
            }
        };
    }

    /**
     * Emits a {@code std/time} member call (ISSUE-0097). {@code
     * nowMillis()} reproduces LuaJIT's {@code os.time() * 1000}: the
     * current epoch milliseconds truncated to whole seconds — never the
     * raw {@code System.currentTimeMillis()}, whose sub-second precision
     * would diverge from the reference implementation.
     */
    private String emitStdlibTimeMemberCall(MemberAccessExpr mae, CallExpr call) {
        if (!"nowMillis".equals(mae.field())) {
            unsupported("export '" + mae.field() + "' of std/time", mae.span());
            return "null";
        }
        return "(java.lang.System.currentTimeMillis() / 1000L) * 1000L";
    }

    /**
     * A member access used as a value (not a call): the array
     * {@code .length} intrinsic (ISSUE-0094), a declared class field
     * read (spec-v1.2 §Field access — the checker guarantees the field is
     * declared and returns its declared type), or a table field read in a
     * contextual target type — the slice's one untyped boundary, where the
     * runtime nominal check runs (see {@link #emitTableRead}). Everything
     * else is E6000.
     */
    private String emitMemberAccessValue(MemberAccessExpr mae) {
        Type objType = typeOf(mae.object());
        if (objType instanceof Type.Array && "length".equals(mae.field())) {
            String obj = emitExpression(mae.object());
            return "((long) " + obj + ".data.length)";
        }
        if (objType instanceof Type.Class) {
            String obj = emitExpression(mae.object());
            return "(" + obj + ")." + javaName(mae.field());
        }
        if (objType instanceof Type.Table) {
            return emitTableRead(mae);
        }
        unsupported("member access as a value", mae.span());
        return "null";
    }

    // =========================================================================
    // Primitive arrays (ISSUE-0094): literals, index reads, element writes
    // =========================================================================

    /**
     * Emits a read of {@code array[index]} where {@code array: T[]} and
     * {@code T} is one of the four primitive element types, a local
     * class, or a nullable element form. The receiver
     * and the index are emitted with {@link #emitOperandsInOrder} (the
     * spec's strict left-to-right evaluation order holds even when one of
     * them hoists side-effecting pre-statements), and the emitted helper
     * call performs the read-site checks: negative index → E8002 (LuaJIT's
     * emitted negative-index check), index past the end → E8001
     * "expected &lt;T&gt;, got null" for the non-nullable elements
     * (LuaJIT reads nil there and the read
     * site's typed boundary fails with that shape — spec §Bounds and nil
     * behavior), while {@code (T | null)[]} reads yield the DEAL null
     * past the end (a valid nullable element, no boundary failure) and
     * {@code C[]} reads run the per-class nominal check. Comparison
     * positions ({@code ===}/{@code !==} operands)
     * do not route through this method: their read site applies no typed
     * boundary, so {@link #emitArrayReadComparison} boxes the read and
     * computes LuaJIT's nil-comparison semantics instead. Tables and
     * other indexable forms stay out of scope.
     *
     * <p>The {@code target} parameter is the read site's contextual
     * target type — the type the IMMEDIATE consumer expects (a
     * declaration initializer's declared type, an assignment RHS target,
     * a call parameter, a return type, a class field, an array-literal
     * element, a class-construction value). The spec checks the read
     * result against the TARGET type at the read site (spec §Bounds and
     * nil behavior — "Optional nullable arrays follow target typing"), so
     * a past-end read of a non-nullable {@code T[]} consumed at a
     * {@code T | null} target must YIELD the DEAL null: LuaJIT reads nil
     * there and the target's {@code check_nullable} accepts it (the
     * reviewer's probes: {@code let n: int | null = xs[9]} prints 11
     * under LuaJIT while the pre-fix JVM raised the element-typed
     * E8001). The nil-yielding boxed read (the per-class nullable read
     * for {@code C[]}) still raises E8002 for a negative index, and a
     * read whose target does not match exactly keeps the element-typed
     * helper (a non-nullable target's boundary fails on the nil with
     * E8001, which the element helper raises directly).
     */
    private String emitIndexRead(IndexExpr idx, Type target) {
        Type arrayType = typeOf(idx.array());
        if (!(arrayType instanceof Type.Array arr)) {
            unsupported("indexing of " + typeName(arrayType), idx.span());
            return "null";
        }
        Type element = arr.element();
        if (isNilYieldingReadTarget(idx, target)) {
            // The read site's target type is Nullable(T) for this T[]
            // element — the target's check_nullable accepts the past-end
            // nil, so the read yields the DEAL null instead of raising
            // the element-typed E8001.
            String nilHelper = null;
            if (element instanceof Type.Class cls) {
                // Only a LOCAL class has the per-class nullable read
                // helper in this artifact; an imported class falls
                // through to the element-typed chain below, which
                // records the E6000 (imported class arrays stay out of
                // slice) — never an unqualified helper javac would
                // reject.
                if (isLocalClassType(cls)
                        && moduleClasses.containsKey(cls.name())) {
                    nilHelper = classOrNullArrayReadName(cls.name());
                }
            } else {
                nilHelper = boxedArrayReadHelper(element);
            }
            if (nilHelper != null) {
                List<String> codes = emitOperandsInOrder(
                    List.of(idx.array(), idx.index()));
                return nilHelper + "(" + codes.get(0) + ", "
                    + codes.get(1) + ")";
            }
            // Local-only class guard failed: fall through to the
            // element-typed chain below, which records the E6000.
        }
        String helper = null;
        if (element instanceof Type.Nullable ne) {
            // (T | null)[] reads: the boxed read yields the DEAL null
            // past the end (a valid nullable element — LuaJIT's nil, no
            // boundary failure at the read); a negative index still
            // raises E8002. Nullable class elements use the per-class
            // helper with the nominal check.
            helper = orNullArrayReadHelper(ne.inner());
            if (helper == null && ne.inner() instanceof Type.Class cls
                    && localClassJavaType(cls, idx.span()) != null) {
                helper = classOrNullArrayReadName(cls.name());
            }
        } else if (element instanceof Type.Class cls) {
            if (localClassJavaType(cls, idx.span()) != null) {
                helper = classArrayReadName(cls.name());
            }
        } else {
            helper = arrayReadHelper(element);
        }
        if (helper == null) {
            // Unsupported element type (nested/function/… arrays):
            // record the E6000 and emit the inert placeholder.
            javaArrayElementType(element, idx.span());
            return "null";
        }
        List<String> codes = emitOperandsInOrder(List.of(idx.array(), idx.index()));
        return helper + "(" + codes.get(0) + ", " + codes.get(1) + ")";
    }

    /**
     * True when {@code e} is an index read of one of the four supported
     * primitive array types — together with the local class reads of
     * {@link #isClassArrayRead}, the in-scope read shapes that can yield
     * the LuaJIT nil past the end. Table indexing and out-of-slice
     * element types are not affected (they are E6000 elsewhere).
     */
    private boolean isPrimitiveArrayRead(ExpressionNode e) {
        if (!(e instanceof IndexExpr idx)) return false;
        if (!(typeOf(idx.array()) instanceof Type.Array arr)) return false;
        return arrayReadHelper(arr.element()) != null;
    }

    /** {@code true} when {@code e} is an index read of one of the four
     * supported primitive array types whose element type is boolean. */
    private boolean isPrimitiveBooleanArrayRead(ExpressionNode e) {
        if (!isPrimitiveArrayRead(e)) return false;
        return ((Type.Array) typeOf(((IndexExpr) e).array())).element()
            instanceof Type.Boolean;
    }

    /** {@code true} when {@code e} is an index read of a LOCAL class
     * array ({@code C[]}) — the nil-capable read shape of the class
     * slice (the same past-end nil semantics as the primitive reads;
     * imported class arrays stay E6000 at the read). Side-effect-free:
     * the locality guard must not record the E6000 a second time (the
     * read emission records it once). */
    private boolean isClassArrayRead(ExpressionNode e) {
        if (!(e instanceof IndexExpr idx)) return false;
        if (!(typeOf(idx.array()) instanceof Type.Array arr)) return false;
        if (!(arr.element() instanceof Type.Class cls)) return false;
        return isLocalClassType(cls) && moduleClasses.containsKey(cls.name());
    }

    /** {@code true} when {@code e} is an index read whose array element
     * is a non-nullable primitive or local class — the read shapes that
     * yield the LuaJIT nil past the end with no boundary at the read
     * site ({@code (T | null)[]} reads yield the nil through their own
     * nullable element type and route through the nullable comparison
     * lowering instead). */
    private boolean isNilCapableArrayRead(ExpressionNode e) {
        return isPrimitiveArrayRead(e) || isClassArrayRead(e);
    }

    /**
     * True when the LuaJIT value of {@code e} can be the Lua nil — a
     * boolean-typed expression whose evaluation can read a
     * {@code boolean[]} element past the end (LuaJIT's read yields nil
     * there; spec §Bounds and nil behavior) with no typed boundary in
     * between. The only nil sources are {@code boolean[]} reads (the
     * other three element types can only reach {@code ===}/{@code !==}
     * operands, which never propagate nil) and {@code &&}/{@code ||}
     * results built from them (Lua's {@code and}/{@code or} pass nil
     * through: {@code nil and x} → nil, {@code nil or x} → x,
     * {@code true and nil} → nil — conservatively reported when EITHER
     * operand can yield nil). Lua's {@code not nil} is {@code true}, so
     * {@code !} always produces a real boolean and stops propagation.
     */
    private boolean canYieldNil(ExpressionNode e) {
        return switch (e) {
            case IndexExpr idx -> isPrimitiveBooleanArrayRead(idx);
            case BinaryExpr bin -> {
                Type t = typeOf(bin);
                yield (t instanceof Type.Boolean)
                    && (bin.op() == BinaryOp.AND || bin.op() == BinaryOp.OR)
                    && (canYieldNil(bin.left()) || canYieldNil(bin.right()));
            }
            default -> false;
        };
    }

    /** True when a typed boolean boundary consuming {@code e} must convert
     * the emitted nullable boxed code with {@code booleanNotNull}: only
     * when {@code e} is NOT a direct read (a direct read's typed helper
     * already raises E8001 at the read — the boundary failure) and its
     * Lua value can be nil (a nil-aware {@code &&}/{@code ||} result). */
    private boolean needsBooleanBoundary(ExpressionNode e, Type t) {
        return t instanceof Type.Boolean
            && canYieldNil(e)
            && !isPrimitiveArrayRead(e);
    }

    /**
     * Emits the boxed read helper call for a primitive array read into a
     * fresh pre-statement temporary and returns the temporary name: the
     * boxed read yields {@code null} past the end (the LuaJIT nil) and
     * still raises E8002 for a negative index (LuaJIT raises that
     * unconditionally at the read). The receiver and the index are
     * emitted with {@link #emitOperandsInOrder} first, so the read's
     * evaluation order holds even when one of them hoists side-effecting
     * pre-statements.
     */
    private String emitBoxedReadTemp(IndexExpr idx) {
        Type element = ((Type.Array) typeOf(idx.array())).element();
        List<String> ops = emitOperandsInOrder(
            List.of(idx.array(), idx.index()));
        String n = nextEvalTempName();
        preStatements.add(new PreLine(boxedArrayJavaType(element, idx.span())
            + " " + n + " = " + boxedArrayReadHelper(element)
            + "(" + ops.get(0) + ", " + ops.get(1) + ");", 0));
        preStatementsDeclareTemps = true;
        return n;
    }

    /** True when a {@code ===}/{@code !==} operand carries LuaJIT nil
     * semantics — a primitive or local-class array read past the end or
     * a nil-aware {@code &&}/{@code ||} result (see
     * {@link #canYieldNil}). */
    private boolean isNilCapableOperand(ExpressionNode e) {
        return isNilCapableArrayRead(e) || canYieldNil(e);
    }

    /**
     * Emits a nil-capable comparison operand as nullable boxed code: a
     * direct primitive array read becomes a boxed read temporary, and
     * any other shape emits normally (a nil-aware {@code &&}/{@code ||}
     * already lowered itself to a boxed temporary). The returned code
     * can be {@code null} at runtime when {@link #isNilCapableOperand}
     * reported true.
     */
    private String emitNilCapableOperand(ExpressionNode e) {
        if (isNilCapableArrayRead(e)) {
            return emitBoxedReadTemp((IndexExpr) e);
        }
        return emitExpression(e);
    }

    /** The comparison element type: the read side's element when one
     * operand is a primitive array read, otherwise boolean (the only
     * nil-capable non-read operands are boolean-typed {@code &&}/
     * {@code ||} results). */
    private Type comparisonElement(BinaryExpr bin) {
        if (isNilCapableArrayRead(bin.left())) {
            return ((Type.Array) typeOf(
                ((IndexExpr) bin.left()).array())).element();
        }
        if (isNilCapableArrayRead(bin.right())) {
            return ((Type.Array) typeOf(
                ((IndexExpr) bin.right()).array())).element();
        }
        return Type.Boolean.INSTANCE;
    }

    /**
     * Emits {@code ===}/{@code !==} where at least one operand carries
     * LuaJIT nil semantics — a primitive or local-class array read or a
     * nil-aware {@code &&}/{@code ||} result. The spec's read-site
     * contract (spec
     * §Bounds and nil behavior) applies no typed boundary to a comparison
     * operand, so a read past the end must NOT raise E8001 here: LuaJIT
     * reads nil and computes the comparison on that value —
     * {@code nil === v} → {@code false}, {@code nil !== v} →
     * {@code true}, {@code nil === nil} → {@code true} (the reviewer's
     * four-type probes: {@code xs[99] === 5} → {@code neq}). Each
     * nil-capable operand is therefore emitted as a nullable boxed
     * temporary (the boxed read helper still raises E8002 for a negative
     * index — LuaJIT raises that unconditionally at the read) and the
     * comparison evaluates the boxed values with the nil semantics.
     * Operand evaluation stays strict left-to-right: the left operand is
     * emitted completely — including a left read's boxed helper call —
     * before the right operand is even emitted, so a left read's E8002
     * always raises before any right-operand hoisted side effect
     * (appending both helper pre-statements after a single four-operand
     * {@code emitOperandsInOrder} call placed the right operand's
     * hoisted println first: {@code ys[-1] === makeArr("made",
     * console.log("h"))[0]} printed "h" before the E8002 while LuaJIT
     * raises with no output). An effectful non-nil operand is
     * materialized into a pre-statement temporary so the final
     * comparison references only inert values and its Java {@code &&}/
     * {@code ||} short-circuit can never skip a DEAL-visible effect
     * (LuaJIT evaluates both {@code ===} operands strictly) — a plain
     * effectful LEFT operand is materialized right after its emission,
     * before the right operand is emitted at all, so a right read's
     * E8002 helper call can never run first (a late materialization
     * inverted the order: mark("lhs", 5) === xs[-1] raised the read's
     * E8002 before printing "lhs", and (9007199254740991 + 1) ===
     * xs[-1] raised the read's E8002 where LuaJIT raises the left
     * arithmetic's E8004 first).
     */
    private String emitArrayReadComparison(BinaryExpr bin, boolean eq) {
        Type element = comparisonElement(bin);
        if (boxedArrayReadHelper(element) == null
                || boxedArrayJavaType(element, bin.span()) == null) {
            unsupported("array comparison on " + typeName(element)
                + " elements", bin.span());
            return "false";
        }
        boolean leftNil = isNilCapableOperand(bin.left());
        boolean rightNil = isNilCapableOperand(bin.right());
        String l = emitNilCapableOperand(bin.left());
        if (!leftNil) {
            // A plain non-nil left operand is emitted as inline code.
            // Materialize any inline effect IMMEDIATELY — before the
            // right operand is even emitted — because the right
            // operand's boxed read temp appends its helper
            // pre-statement (and its receiver/index operands' hoisted
            // side effects) to preStatements, and a late
            // materialization would land the left operand's evaluation
            // after them, inverting the spec's strict left-to-right
            // order (§Operational semantics): mark("lhs", 5) === xs[-1]
            // ran the right read's E8002 before the left call printed
            // "lhs", and (9007199254740991 + 1) === xs[-1] raised the
            // read's E8002 where LuaJIT raises the left arithmetic's
            // E8004 first.
            l = materializeIfEffectful(l, bin.left());
        }
        String r = emitNilCapableOperand(bin.right());
        if (leftNil && rightNil) {
            // Both operands boxed nullable values: nil === nil is true
            // and nil !== nil is false, otherwise the unboxed values
            // compare. Neither side short-circuits an operand evaluation
            // (both are already materialized temporaries or inert code).
            if (eq) {
                return "((" + l + " == null && " + r + " == null) || ("
                    + l + " != null && " + r + " != null && "
                    + boxedEq(l, r, element) + "))";
            }
            return "((" + l + " == null) != (" + r + " == null) || ("
                + l + " != null && " + r + " != null && "
                + boxedNe(l, r, element) + "))";
        }
        // One nil-capable boxed side, one plain value side (the checker
        // enforces identical operand types, so the mixed shapes pair a
        // boolean read/&& || with a boolean value, an int read with an
        // int value, etc.).
        if (leftNil) {
            r = materializeIfEffectful(r, bin.right());
            if (eq) return "(" + l + " != null && "
                + boxedEqValue(l, r, element) + ")";
            return "(" + l + " == null || "
                + boxedNeValue(l, r, element) + ")";
        }
        // rightNil: the plain left operand was already materialized
        // before the right operand was emitted, so the comparison
        // references only inert code and its Java &&/|| short-circuit
        // can never skip a DEAL-visible effect.
        if (eq) return "(" + r + " != null && "
            + valueBoxedEq(l, r, element) + ")";
        return "(" + r + " == null || " + valueBoxedNe(l, r, element) + ")";
    }

    /**
     * Materializes the already-emitted plain comparison operand code
     * into a fresh temporary when the operand's emitted code can still
     * have an observable effect (an inline call, an assignment, checked
     * int arithmetic — anything {@link #isPureAfterEmission} flags), so
     * the surrounding nil-aware comparison expression only references
     * inert values and a Java {@code &&}/{@code ||} short-circuit can
     * never skip a DEAL-visible effect. Returns the code to use in the
     * comparison (the temporary or the inert inline code).
     */
    private String materializeIfEffectful(String code, ExpressionNode v) {
        if (isPureAfterEmission(v)) return code;
        // A null-typed effectful value (an assignment like `(m = null)`
        // whose emitted code carries the boxed target's Java type)
        // materializes into an Object temporary: the DEAL null fits any
        // reference and the temp is consumed only for its evaluation
        // effects.
        String javaType = typeOf(v) instanceof Type.Null
            ? "java.lang.Object" : javaLocalType(typeOf(v), v.span());
        if (javaType == null) return code; // diagnostic already recorded
        String temp = nextEvalTempName();
        preStatements.add(new PreLine(
            javaType + " " + temp + " = " + code + ";", 0));
        preStatementsDeclareTemps = true;
        return temp;
    }

    /**
     * Emits the boolean operand {@code e} whose Lua value can be nil
     * (see {@link #canYieldNil}) as nullable boxed code: a direct
     * {@code boolean[]} read becomes a boxed read temporary, and any
     * other shape emits normally (a nested {@code &&}/{@code ||}
     * already lowered itself to a boxed temporary). The returned code
     * can be {@code null} at runtime — always a temporary, so callers
     * may reference it more than once.
     */
    private String emitBooleanNullableOperand(ExpressionNode e) {
        if (isPrimitiveArrayRead(e)) return emitBoxedReadTemp((IndexExpr) e);
        return emitExpression(e);
    }

    /** The declared type of the {@code index}-th parameter of the local
     * module function {@code name}, or {@code null} when the function or
     * parameter is unknown (checker-gated unreachable). */
    private Type paramDeclaredType(String name, int index) {
        FunctionDeclaration fd = moduleFunctions.get(name);
        if (fd == null || index >= fd.params().size()) return null;
        return resolveTypeNode(fd.params().get(index).type());
    }

    /** The call-argument code for {@code arg}: a boolean-typed nil-aware
     * {@code &&}/{@code ||} result is converted at the parameter boundary
     * (LuaJIT's callee prologue checks the parameter and fails on nil
     * with E8001), every other argument keeps its emitted code. The
     * boundary keys on the parameter's DECLARED type: a {@code
     * boolean | null} parameter accepts the DEAL null (check_nullable),
     * a {@code boolean} parameter fails with E8001. */
    private String boundaryArgCode(ExpressionNode arg, String code,
                                   Type paramType) {
        if (needsBooleanBoundary(arg, paramType)) {
            return "booleanNotNull(" + code + ")";
        }
        if (paramType == null && needsBooleanBoundary(arg, typeOf(arg))) {
            return "booleanNotNull(" + code + ")";
        }
        if (paramType != null) {
            String paramJava = javaLocalType(paramType, arg.span());
            return coerceNullValueCode(code, arg, paramJava, arg.span());
        }
        return code;
    }
    /** Equality of two boxed read values ({@code null} handled by the
     * caller): strings via {@code equals}, the other primitives unboxed. */
    private String boxedEq(String a, String b, Type element) {
        return element instanceof Type.String
            ? a + ".equals(" + b + ")"
            : boxedUnbox(a, element) + " == " + boxedUnbox(b, element);
    }

    /** Inequality of two boxed read values ({@code null} handled by the
     * caller). */
    private String boxedNe(String a, String b, Type element) {
        return element instanceof Type.String
            ? "(!" + a + ".equals(" + b + "))"
            : boxedUnbox(a, element) + " != " + boxedUnbox(b, element);
    }

    /** Equality of a boxed read value with a plain value operand. */
    private String boxedEqValue(String boxed, String value, Type element) {
        return element instanceof Type.String
            ? boxed + ".equals(" + value + ")"
            : boxedUnbox(boxed, element) + " == " + value;
    }

    /** Inequality of a boxed read value with a plain value operand. */
    private String boxedNeValue(String boxed, String value, Type element) {
        return element instanceof Type.String
            ? "(!" + boxed + ".equals(" + value + "))"
            : boxedUnbox(boxed, element) + " != " + value;
    }

    /** Equality of a plain value operand with a boxed read value. */
    private String valueBoxedEq(String value, String boxed, Type element) {
        return element instanceof Type.String
            ? value + ".equals(" + boxed + ")"
            : value + " == " + boxedUnbox(boxed, element);
    }

    /** Inequality of a plain value operand with a boxed read value. */
    private String valueBoxedNe(String value, String boxed, Type element) {
        return element instanceof Type.String
            ? "(!" + value + ".equals(" + boxed + "))"
            : value + " != " + boxedUnbox(boxed, element);
    }

    /** Java unboxing accessor for a boxed read temporary. */
    private String boxedUnbox(String boxed, Type element) {
        return switch (element) {
            case Type.Int ignored -> boxed + ".longValue()";
            case Type.Number ignored -> boxed + ".doubleValue()";
            case Type.Boolean ignored -> boxed + ".booleanValue()";
            default -> boxed;
        };
    }

    /**
     * Emits an array literal {@code [e1, …, eN]} for the four primitive
     * element types as {@code new __IntArray(new long[]{…})} (and the
     * empty form {@code new long[]{} — an empty literal is
     * only checker-accepted with a contextual array type, which
     * {@link #typeOf} carries}); nullable-element and class-element
     * literals emit the corresponding or-null/per-class wrapper with
     * boxed/upcast storage. Elements are emitted with
     * {@link #emitOperandsInOrder}, so left-to-right element evaluation
     * holds even when an element hoists side-effecting pre-statements
     * (an earlier inline element is materialized into a temporary before
     * the hoisted statements, exactly like LuaJIT's per-element
     * evaluation order).
     */
    private String emitArrayLiteral(ArrayLiteralExpr al) {
        Type arrayType = typeOf(al);
        if (!(arrayType instanceof Type.Array arr)) {
            unsupported("array literals without an array type", al.span());
            return "null";
        }
        String wrapper = arrayWrapperName(arr.element());
        String elemJava = javaArrayElementType(arr.element(), al.span());
        if (wrapper == null || elemJava == null) return "null";
        // Element codes flow through Java's assignment conversion inside
        // the initializer, which boxes unboxed primitive elements for the
        // nullable-element wrappers (java.lang.Long[]{5L, null}) and
        // upcasts class instances into __RefArray storage.
        List<Type> elementTargets = new ArrayList<>(al.elements().size());
        for (int i = 0; i < al.elements().size(); i++) {
            elementTargets.add(arr.element());
        }
        List<String> codes = emitOperandsInOrder(al.elements(), elementTargets);
        StringBuilder sb = new StringBuilder("new ").append(wrapper)
            .append("(new ").append(elemJava).append("[]{");
        for (int i = 0; i < codes.size(); i++) {
            if (i > 0) sb.append(", ");
            String code = codes.get(i);
            if (needsBooleanBoundary(al.elements().get(i), arr.element())) {
                code = "booleanNotNull(" + code + ")";
            }
            sb.append(code);
        }
        return sb.append("})").toString();
    }

    /**
     * A table field read whose contextual target type comes from the
     * checker's type map. Every supported target now routes through the
     * ONE shared descriptor-driven runtime-check seam (ISSUE-0110): the
     * read emits {@code $check("<spec RuntimeTypeDescriptor>", value)}
     * with the descriptor spelled by {@link #runtimeTypeDescriptor} —
     * class-typed targets run the nominal check of the class descriptor
     * ({@code @module/Name}; imported classes dispatch on the DECLARING
     * module's seam via {@code Lib.$check(...)}, whose {@code
     * instanceof} test and module-qualified identity strings compare
     * against the declaring module's generated class, E8001 for a
     * wrong-class or non-class value — never a silent cast),
     * table-typed targets check {@code "table"}, nullable targets spell
     * {@code ?T} and pass the DEAL null through (check_nullable),
     * array targets spell {@code [T]}/{@code [?T]} and gate on the
     * emitted wrapper. Primitive (non-nullable) and function target
     * types stay out of slice (E6000) — never a silent miscompile.
     */
    private String emitTableRead(MemberAccessExpr mae) {
        String obj = emitExpression(mae.object());
        Type target = typeOf(mae);
        String get = "(" + obj + ").get(" + quoteJavaString(mae.field()) + ")";
        if (target instanceof Type.Class cls) {
            // Locality is decided from the Type.Class MODULE PATH, then
            // the name-keyed lookup — a same-named local class must not
            // satisfy the guard for a foreign path (the read would run
            // the LOCAL nominal check against a lib.C value, corrupting
            // the nominal identity). An imported class (ISSUE-0109)
            // dispatches on the DECLARING module's shared seam
            // ({@code Lib.$check("@lib/C", v)}): its {@code instanceof}
            // test and module-qualified identity string compare against
            // the declaring module's generated class, so a genuine
            // instance passes and a same-name sibling from another
            // module reports E8001 "expected instance of @lib/C, got
            // @other/C".
            if (!isLocalClassType(cls)) {
                String importedModule = importedClassModuleRef(cls, mae.span());
                if (importedModule == null) return "null";
                return "((" + importedModule + "." + classNameForClass(cls.name())
                    + ") " + importedModule + ".$check("
                    + quoteJavaString(classCheckDescriptor(cls)) + ", "
                    + get + "))";
            }
            if (!moduleClasses.containsKey(cls.name())) {
                unsupported("class-typed table read for class '"
                    + cls.name() + "' (only local module-level classes "
                    + "are supported)", mae.span());
                return "null";
            }
            return "((" + classNameForClass(cls.name()) + ") $check("
                + quoteJavaString(classCheckDescriptor(cls)) + ", "
                + get + "))";
        }
        if (target instanceof Type.Table) {
            return "(($T) $check(\"table\", " + get + "))";
        }
        if (target instanceof Type.String) {
            // ISSUE-0106 (v1.2 boundary string validation): a string
            // crossing the table boundary must be a java.lang.String with
            // no unpaired surrogate code units — the seam's "string"
            // branch runs the surrogate scan.
            return "((java.lang.String) $check(\"string\", " + get + "))";
        }
        if (target instanceof Type.Nullable nn) {
            // Nullable boundary checks (ISSUE-0108, unified ISSUE-0110):
            // the read materializes the table lookup into an Object
            // temporary (the receiver must evaluate exactly once) and the
            // seam's ?T prefix applies check_nullable — null passes as
            // the DEAL null, a value of the inner type passes, anything
            // else raises E8001.
            String temp = nextEvalTempName();
            preStatements.add(new PreLine(
                "java.lang.Object " + temp + " = " + get + ";", 0));
            preStatementsDeclareTemps = true;
            Type inner = nn.inner();
            if (inner instanceof Type.Class cls) {
                if (!isLocalClassType(cls) || !moduleClasses.containsKey(cls.name())) {
                    unsupported("class-typed table read for class '"
                        + cls.name() + "' (only local module-level classes "
                        + "are supported)", mae.span());
                    return "null";
                }
                return "((" + classNameForClass(cls.name()) + ") $check("
                    + quoteJavaString("?" + classCheckDescriptor(cls))
                    + ", " + temp + "))";
            }
            if (inner instanceof Type.Array arr
                    && elementCheckDescriptor(arr.element()) != null
                    && arrayWrapperName(arr.element()) != null) {
                return "((" + arrayWrapperName(arr.element()) + ") $check("
                    + quoteJavaString("?" + "["
                        + elementCheckDescriptor(arr.element()) + "]")
                    + ", " + temp + "))";
            }
            if (inner instanceof Type.Int || inner instanceof Type.Number
                    || inner instanceof Type.Boolean
                    || inner instanceof Type.String) {
                // The retired $checkNullable<primitive> helpers accepted
                // exactly these four inner types; anything else falls
                // through to the single E6000 below (the pre-join gate
                // order, so the diagnostic surface stays identical).
                return "((" + nullableJavaType(inner, mae.span()) + ") $check("
                    + quoteJavaString(runtimeTypeDescriptor(target))
                    + ", " + temp + "))";
            }
            unsupported("table field reads with target type "
                + typeName(target), mae.span());
            return "null";
        }
        if (target instanceof Type.Array arr) {
            String elementDesc = elementCheckDescriptor(arr.element());
            if (elementDesc == null) {
                unsupported("table field reads with target type "
                    + typeName(target), mae.span());
                return "null";
            }
            String temp = nextEvalTempName();
            preStatements.add(new PreLine(
                "java.lang.Object " + temp + " = " + get + ";", 0));
            preStatementsDeclareTemps = true;
            return "((" + arrayWrapperName(arr.element()) + ") $check("
                + quoteJavaString("[" + elementDesc + "]")
                + ", " + temp + "))";
        }
        unsupported("table field reads with target type " + typeName(target)
            + " (this slice checks class, nullable, array, and table "
            + "targets only)", mae.span());
        return "null";
    }

    private String emitIntrinsicCall(String name, CallExpr call) {
        if (call.args().size() != 1) {
            // Checker enforces arity; defensive backend diagnostic.
            unsupported("intrinsic '" + name + "' with " + call.args().size()
                + " arguments", call.span());
            return "null";
        }
        ExpressionNode arg = call.args().get(0);
        Type argType = typeOf(arg);
        String emitted = emitExpression(arg);
        return switch (name) {
            case "int" -> {
                if (argType instanceof Type.Number) yield "intFromNumber(" + emitted + ")";
                if (argType instanceof Type.Int) yield emitted;
                if (argType instanceof Type.Nullable nn
                        && nn.inner() instanceof Type.Int) {
                    // int(x: int | null) — null fails at runtime with
                    // E8001 (runtime.lua's int_convert "cannot convert
                    // null to int").
                    yield "intFromNullable(" + emitted + ")";
                }
                unsupported("int() on " + typeName(argType), call.span());
                yield "0L";
            }
            case "number" -> {
                if (argType instanceof Type.Int) yield "numberFromInt(" + emitted + ")";
                if (argType instanceof Type.Number) yield emitted;
                if (argType instanceof Type.Nullable nn
                        && nn.inner() instanceof Type.Number) {
                    yield "numberFromNullable(" + emitted + ")";
                }
                unsupported("number() on " + typeName(argType), call.span());
                yield "0.0";
            }
            default -> {
                unsupported("intrinsic '" + name + "'", call.span());
                yield "null";
            }
        };
    }

    private String emitAssignment(AssignmentExpr ae) {
        return "(" + emitAssignmentCore(ae) + ")";
    }

    /** {@code target = value} without parentheses (valid as a Java statement). */
    private String emitAssignmentCore(AssignmentExpr ae) {
        if (ae.target() instanceof IdentifierExpr id) {
            String mapped = localJavaName(id.name());
            if (mapped == null && !moduleFieldIndices.containsKey(id.name())) {
                // A write with no visible local binding and no module field:
                // the only checker-accepted such program is a write to a
                // later-declared function-local (`x = 5; let x: int = 1` —
                // the checker resolves the target contextually, so the
                // module-scope symbol table reports null). LuaJIT writes
                // the enclosing scope and then the later `local` shadows it
                // (observable only through a same-named outer binding,
                // which would be a visible local here); Java rejects the
                // forward reference, so emit E6000 instead of an artifact
                // javac would reject. Module-level writes to later-declared
                // fields (legal in Java, JLS §8.3.3 forward-reference LHS
                // exception, same final value as LuaJIT) and function-body
                // writes to a module field declared BEFORE the function
                // (LuaJIT's upvalue write — full parity) are allowed and
                // keep using the static field name. Function-body writes
                // to a field declared AFTER the function were already
                // rejected in emitFunction (forwardWriteViolations:
                // LuaJIT binds them to the GLOBAL, the Java static field
                // would pollute later readers).
                unsupported("assignment to '" + id.name() + "' before its "
                    + "declaration with no enclosing binding (LuaJIT writes "
                    + "the enclosing scope; Java rejects the forward "
                    + "reference)", ae.span());
                return "null";
            }
            String target = mapped != null ? mapped : javaName(id.name());
            Type targetType = declaredTypeForBinding(id.name());
            // The read-site target for a direct index-read RHS is the
            // assignment target's declared type: a T[] read assigned to a
            // T | null binding yields the DEAL null past the end
            // (LuaJIT's check_nullable at the assignment boundary), while
            // a non-nullable target keeps the element-typed E8001 read.
            String value = emitExpressionFor(ae.value(), targetType);
            if (needsBooleanBoundary(ae.value(), targetType)) {
                // The boundary keys on the TARGET's declared type: a
                // nil-capable boolean result assigned into a
                // `boolean | null` binding is the DEAL null (LuaJIT's
                // check_nullable stores it, no failure), while a
                // `boolean` binding fails with E8001 exactly where
                // LuaJIT's check_boolean fails.
                value = "booleanNotNull(" + value + ")";
            }
            if (targetType == null) targetType = typeOf(ae.value());
            String targetJava = javaLocalType(targetType, ae.span());
            value = coerceNullValueCode(value, ae.value(), targetJava, ae.span());
            return target + " = " + value;
        }
        if (ae.target() instanceof IndexExpr idx) {
            // Array element write `xs[i] = v` (ISSUE-0094). The checker
            // enforces int indexes and element-type assignability
            // (E3007/E3001), so only the four primitive element arrays
            // reach this branch.
            Type indexType = typeOf(idx);
            if (indexType instanceof Type.Table) {
                unsupported("table indexing", idx.span());
                return "null";
            }
            if (indexType instanceof Type.Error) {
                unsupported("array element assignment to an errored type",
                    ae.span());
                return "null";
            }
            String writeHelper = arrayWriteHelper(indexType);
            if (writeHelper == null) {
                // (T | null)[] and C[] writes route through the
                // or-null/class helpers; any other element type records
                // the E6000.
                if (indexType instanceof Type.Nullable ne) {
                    writeHelper = orNullArrayWriteHelper(ne.inner());
                    if (writeHelper == null && ne.inner() instanceof Type.Class cls
                            && localClassJavaType(cls, ae.span()) != null) {
                        writeHelper = classOrNullArrayWriteName(cls.name());
                    }
                } else if (indexType instanceof Type.Class cls
                        && localClassJavaType(cls, ae.span()) != null) {
                    writeHelper = classArrayWriteName(cls.name());
                }
            }
            if (writeHelper == null) {
                javaArrayElementType(indexType, ae.span());
                return "null";
            }
            // Spec §Operational semantics rule 3: the receiver, the index,
            // and the assignment RHS all evaluate (left to right) BEFORE
            // the LHS write check. emitOperandsInOrder keeps that order
            // when any operand hoists side-effecting pre-statements, and
            // the emitted helper call's Java arguments evaluate left to
            // right before the helper performs the bounds check, the
            // element value check, and the store. The helper returns the
            // stored value, so the assignment expression keeps its DEAL
            // value in value positions (`return xs[0] = 5;`,
            // `f(xs[0] = 5)`).
            List<String> codes = emitOperandsInOrder(
                List.of(idx.array(), idx.index(), ae.value()),
                Arrays.asList(null, null, indexType));
            String rhs = codes.get(2);
            if (needsBooleanBoundary(ae.value(), indexType)) {
                rhs = "booleanNotNull(" + rhs + ")";
            }
            // A null-typed assignment value (`(m = null)`) carries the
            // boxed target's Java type; coerce it to the element's
            // storage type (the write helper's parameter type).
            rhs = coerceNullValueCode(rhs, ae.value(),
                javaArrayElementType(indexType, ae.span()), ae.span());
            return writeHelper + "(" + codes.get(0) + ", " + codes.get(1)
                + ", " + rhs + ")";
        }
        if (ae.target() instanceof MemberAccessExpr mae) {
            Type objType = typeOf(mae.object());
            if (objType instanceof Type.Class) {
                // A declared class field write (spec-v1.2 §Class assignment
                // semantics): the checker guarantees the field is declared
                // and the value matches its type. Spec §Operational
                // semantics rule 1 makes evaluation strict and
                // left-to-right: the receiver expression runs before the
                // assignment RHS. emitOperandsInOrder keeps that order
                // when the value hoists side-effecting pre-statements (a
                // null-typed call argument, a boxed nil-aware read),
                // materializing the receiver into a temporary assigned
                // before the hoisted statements — the same hazard the
                // array-write branch handles. The emitted assignment
                // expression keeps its DEAL value in value positions
                // (`return p.x = 5;`, `f(p.x = 5)`).
                Type fieldType = declaredFieldType(
                    (Type.Class) objType, mae.field());
                // The read-site target for a direct index-read RHS is the
                // declared field type (a T[] read assigned to a T | null
                // field yields the DEAL null past the end); an imported
                // class's unresolvable field falls back to the value's
                // own type below, keeping the element-typed read.
                List<String> codes = emitOperandsInOrder(
                    List.of(mae.object(), ae.value()),
                    Arrays.asList(null, fieldType));
                String value = codes.get(1);
                if (fieldType == null) fieldType = typeOf(ae.value());
                if (needsBooleanBoundary(ae.value(), fieldType)) {
                    // Same target-type boundary rule as the identifier
                    // branch: a `boolean | null` field stores the DEAL
                    // null, a `boolean` field raises E8001.
                    value = "booleanNotNull(" + value + ")";
                }
                if (fieldType != null) {
                    String fieldJava = javaLocalType(fieldType, ae.span());
                    value = coerceNullValueCode(value, ae.value(),
                        fieldJava, ae.span());
                }
                return "(" + codes.get(0) + ")." + javaName(mae.field())
                    + " = " + value;
            }
            unsupported("assignment to table fields", ae.span());
            return "null";
        }
        unsupported("assignment to non-variable targets", ae.span());
        return "null";
    }

    /** The declared internal type of a class field (its annotation type,
     * wrapped in {@code Nullable} for a {@code f: T | null} field). The
     * type resolution records an E6000 only for an unsupported annotation,
     * which the class emission already rejected — so a supported field's
     * re-resolution is diagnostic-free. */
    private Type classFieldDeclaredType(ClassDeclaration cd, ClassField cf) {
        Type t = resolveTypeNode(cf.type());
        if (t == Type.Error.INSTANCE) return null;
        if (cf.nullable()) {
            // The parser keeps the whole `T | null` annotation as the
            // field's type node; the resolved type is already Nullable(T).
            if (!(t instanceof Type.Nullable)) return null;
        }
        return t;
    }

    /** The declared type of a local class's field, or {@code null} when
     * the class or field is unknown (checker-gated unreachable). */
    private Type declaredFieldType(Type.Class cls, String fieldName) {
        ClassDeclaration cd = moduleClasses.get(cls.name());
        if (cd == null) return null;
        for (ClassField cf : cd.fields()) {
            if (cf.name().equals(fieldName)) {
                return classFieldDeclaredType(cd, cf);
            }
        }
        return null;
    }

    // =========================================================================
    // Use-before-declaration detection
    // =========================================================================

    /**
     * Returns the name of a value-position identifier use in {@code stmt}
     * that binds to no enclosing variable and resolves to nothing usable at
     * module scope — i.e. a use of a variable before its own declaration (or
     * a self-reference in its initializer) with no outer binding to fall
     * back to, or a module-level (load-time) forward reference to a
     * later-declared module field. LuaJIT reads the not-yet-declared global
     * value for such uses (nil unless a prior write established it), and
     * emitting a Java forward reference would make javac reject an artifact
     * the CLI reported as successful. Returns {@code null} when the
     * statement is clean.
     *
     * <p>Function-body reads of <em>declared</em> module fields are NOT
     * flagged even when the field is declared later: method bodies may
     * legally reference later-declared static fields, and post-load reads
     * match LuaJIT (see {@link #isUndeclaredVariableUse}).
     *
     * <p>Only the statement's own value positions are checked; nested
     * statements are checked individually when they are emitted, so a block
     * that declares a variable and then uses it stays clean.
     */
    private String undeclaredVariableUse(StatementNode stmt) {
        return switch (stmt) {
            case VariableDeclaration vd -> undeclaredUseIn(vd.initializer());
            // Every condition of the if/else-if chain: emitIf and
            // emitIfContinuation emit the follow-on conditions directly via
            // emitExpression (no statement-level guard runs for them), so
            // the head statement must walk the whole chain — a
            // later-declared variable in an else-if condition would
            // otherwise emit an illegal forward reference (module level)
            // or a cannot-find-symbol reference (function body) that javac
            // rejects after the CLI reported success.
            case IfStatement is -> undeclaredUseInIfChain(is);
            // The while condition is emitted directly by emitWhile (no
            // per-statement guard runs for it) — a later-declared variable
            // there would emit an illegal forward reference (module level)
            // or a cannot-find-symbol reference (function body). Body
            // statements are checked individually when emitted.
            case WhileStatement ws -> undeclaredUseIn(ws.condition());
            case ReturnStatement rs -> rs.expr().map(this::undeclaredUseIn).orElse(null);
            case ExpressionStatement es -> undeclaredUseIn(es.expr());
            default -> null; // functions/imports/exports: separate scopes or no
                              // value uses; unsupported kinds rejected elsewhere
        };
    }

    /** Walks the conditions of an {@code if}/{@code else if} chain. */
    private String undeclaredUseInIfChain(IfStatement is) {
        String r = undeclaredUseIn(is.condition());
        if (r != null) return r;
        Optional<Either<IfStatement, Block>> branch = is.elseBranch();
        if (branch.isPresent()
                && branch.get() instanceof Either.Left<IfStatement, Block> left) {
            return undeclaredUseInIfChain(left.value());
        }
        // then/else BLOCK statements are checked individually when emitted.
        return null;
    }

    private String undeclaredUseIn(ExpressionNode e) {
        return switch (e) {
            case IdentifierExpr id ->
                isUndeclaredVariableUse(id.name()) ? id.name() : null;
            case BinaryExpr bin ->
                firstNonNull(undeclaredUseIn(bin.left()), undeclaredUseIn(bin.right()));
            case UnaryExpr u -> undeclaredUseIn(u.expr());
            case CallExpr call -> {
                String r = null;
                if (call.callee() instanceof IdentifierExpr id
                        && isUndeclaredVariableUse(id.name())) {
                    r = id.name();
                }
                for (ExpressionNode arg : call.args()) {
                    if (r == null) r = undeclaredUseIn(arg);
                }
                yield r;
            }
            // Assignment target (write) positions get their own guard in
            // emitAssignmentCore: a write to a later-declared local with
            // no enclosing binding is E6000 there, so only the value side
            // is walked here. An INDEX target's array and index
            // expressions are value positions (reads): a later-declared
            // identifier there is a forward reference the emitted Java
            // would reject (or — at module level — an illegal static-field
            // forward reference), so they are walked like any other read.
            case AssignmentExpr ae -> {
                String r = undeclaredUseIn(ae.value());
                if (r != null) yield r;
                if (ae.target() instanceof IndexExpr idx) {
                    r = undeclaredUseIn(idx.array());
                    if (r == null) r = undeclaredUseIn(idx.index());
                }
                yield r;
            }
            case MemberAccessExpr mae -> undeclaredUseIn(mae.object());
            case IndexExpr idx ->
                firstNonNull(undeclaredUseIn(idx.array()), undeclaredUseIn(idx.index()));
            case ArrayLiteralExpr al -> firstNonNullIn(al.elements());
            case ObjectLiteralExpr ol ->
                firstNonNullIn(ol.properties().stream().map(Property::value).toList());
            case HasExpr he -> undeclaredUseIn(he.object());
            case TemplateLiteralExpr tl -> firstNonNullIn(tl.parts());
            case AwaitExpression aw -> undeclaredUseIn(aw.callee());
            case FunctionExpr fe -> null; // nested scope of its own
            case LiteralExpr lit -> null;
        };
    }

    private String firstNonNullIn(List<ExpressionNode> exprs) {
        for (ExpressionNode e : exprs) {
            String r = undeclaredUseIn(e);
            if (r != null) return r;
        }
        return null;
    }

    private static String firstNonNull(String a, String b) {
        return a != null ? a : b;
    }

    /**
     * True when a value-position use of {@code name} has no enclosing
     * variable binding at emission time: either the name resolves to nothing
     * at module scope (a later-declared function-local, or a use the checker
     * only accepted because it binds to a variable declared later in an
     * enclosing scope) or it resolves to a module variable that has not been
     * emitted yet (a forward field reference or a module-level
     * self-reference).
     */
    private boolean isUndeclaredVariableUse(String name) {
        if (localJavaName(name) != null) return false;
        Symbol sym = symbols.resolve(name);
        if (sym == null) return true;
        if (sym instanceof Symbol.VariableSymbol) {
            // A value-position read of a module field from inside a
            // FUNCTION body is legal Java (method bodies may reference
            // later-declared static fields; the illegal-forward-reference
            // rule of JLS §8.3.3 covers only initializers). Reads of a
            // field declared BEFORE the function are the module-local
            // upvalue read — full parity. Reads of a field declared
            // AFTER the function never reach this point: they are
            // rejected by the write-dominance analysis in emitFunction
            // (LuaJIT binds them to the GLOBAL at call time — nil unless
            // a prior write established it — while Java would silently
            // read the initialized static field). At module level (load
            // time) a later-declared field is genuinely
            // not-yet-declared under LuaJIT (nil unless written) and an
            // illegal forward reference in Java — keep rejecting there.
            if (moduleFieldIndices.containsKey(name)
                    && currentModuleStatementIndex < 0) {
                return false;
            }
            return true;
        }
        return false;
    }

    // =========================================================================
    // Type mapping
    // =========================================================================

    private Type typeOf(ExpressionNode e) {
        Type t = typeMap.get(e);
        return t != null ? t : Type.Error.INSTANCE;
    }

    /**
     * Resolves a type annotation to the internal type. Unsupported forms
     * (qualified types, function types, tables, and the unsupported
     * named/inner forms) record an E6000 diagnostic and return
     * {@code Type.Error.INSTANCE}; nullable and array wrappers resolve
     * their inner forms and delegate support checks to the Java type
     * mappers.
     */
    private Type resolveTypeNode(TypeNode typeNode) {
        return switch (typeNode) {
            case NamedType nt -> switch (nt.name()) {
                case "null" -> Type.Null.INSTANCE;
                case "boolean" -> Type.Boolean.INSTANCE;
                case "int" -> Type.Int.INSTANCE;
                case "number" -> Type.Number.INSTANCE;
                case "string" -> Type.String.INSTANCE;
                case "table" -> Type.Table.INSTANCE;
                default -> {
                    // A local module-level class (the checker's hoisted
                    // ClassSymbol). The builtin Error stays out of slice
                    // (Error values cannot be produced — throw/catch is
                    // E6000); imported classes resolve through QUALIFIED
                    // annotations (alias.C, ISSUE-0109), never a bare
                    // name.
                    Symbol sym = symbols.resolve(nt.name());
                    if (sym instanceof Symbol.ClassSymbol
                            && moduleClasses.containsKey(nt.name())) {
                        yield new Type.Class(nt.name(), modulePath);
                    }
                    unsupported("type '" + nt.name() + "' (only local classes "
                        + "are supported)", nt.span());
                    yield Type.Error.INSTANCE;
                }
            };
            case QualifiedType qt -> {
                // ISSUE-0109: `alias.Class` annotations name the imported
                // module's exported class. The alias maps through the
                // same importAliases table every imported member call
                // uses; the declaration must come from the orchestrator's
                // imported-class map (stdlib aliases export no classes).
                String module = importAliases.get(qt.moduleName());
                Map<String, ClassDeclaration> decls =
                    module == null ? null : importedClasses.get(module);
                if (decls == null || !decls.containsKey(qt.typeName())) {
                    unsupported("qualified type '" + qt.moduleName() + "."
                        + qt.typeName() + "' (only classes of imported "
                        + "compiled project modules are supported)",
                        qt.span());
                    yield Type.Error.INSTANCE;
                }
                yield new Type.Class(qt.typeName(), module);
            }
            case ArrayType at -> {
                Type elem = resolveTypeNode(at.elementType());
                if (elem == Type.Error.INSTANCE) {
                    // Inner resolution already recorded its E6000 (e.g. a
                    // nested array, a function element, or an unsupported
                    // named type); do not double-report.
                    yield Type.Error.INSTANCE;
                }
                if (javaArrayElementType(elem, at.span()) == null) {
                    yield Type.Error.INSTANCE;
                }
                yield new Type.Array(elem);
            }
            case NullableType nt -> {
                Type inner = resolveTypeNode(nt.innerType());
                if (inner == Type.Error.INSTANCE) {
                    // Inner resolution already recorded its E6000; do not
                    // double-report.
                    yield Type.Error.INSTANCE;
                }
                if (inner instanceof Type.Null
                        || inner instanceof Type.Nullable) {
                    // `null | null` and `(T | null) | null` are frontend
                    // errors (spec §Type grammar); the defensive gate keeps
                    // the Type.Nullable invariant (inner is never null or
                    // nullable).
                    unsupported("nullable type '" + typeName(inner)
                        + " | null'", nt.span());
                    yield Type.Error.INSTANCE;
                }
                yield new Type.Nullable(inner);
            }
            case FunctionType ft -> {
                unsupported("function types", ft.span());
                yield Type.Error.INSTANCE;
            }
        };
    }

    /** Java type for a local/parameter/field. {@code null} when unsupported. */
    private String javaLocalType(Type t, Span span) {
        return switch (t) {
            case Type.Int ignored -> "long";
            case Type.Number ignored -> "double";
            case Type.Boolean ignored -> "boolean";
            case Type.String ignored -> "java.lang.String";
            case Type.Null ignored -> "java.lang.Void";
            case Type.Array a -> {
                if (javaArrayElementType(a.element(), span) == null) {
                    yield null;
                }
                yield arrayWrapperName(a.element());
            }
            case Type.Table ignored -> "$T";
            case Type.Class c -> {
                // Locality is decided from the Type.Class MODULE PATH —
                // a same-named local class must not satisfy the guard
                // for a foreign path (lib.C would then be declared as
                // the LOCAL $C_C and javac would reject the incompatible
                // assignment). A local class maps to this module's
                // generated nested class; an imported class (ISSUE-0109)
                // maps to the declaring module's emitted class
                // ({@code Lib.$C_C}): the generated nested classes are
                // package-private, and every emitted artifact shares the
                // default package, so the cross-module reference is
                // exactly what javac compiles.
                if (isLocalClassType(c)) {
                    if (!moduleClasses.containsKey(c.name())) {
                        unsupported("values of class type '" + c.name()
                            + "' (only local module-level classes are "
                            + "supported)", span);
                        yield null;
                    }
                    yield classNameForClass(c.name());
                }
                String importedModule = importedClassModuleRef(c, span);
                if (importedModule == null) yield null;
                yield importedModule + "." + classNameForClass(c.name());
            }
            case Type.Nullable n -> {
                // T | null maps to the boxed reference representation
                // (spec-v1.2 §JVM value mapping: "nullable JVM reference
                // or tagged nullable wrapper for primitives"): boxed
                // java.lang.Long/Double/Boolean for the numeric
                // primitives, the (already-nullable) java.lang.String
                // reference, the generated class reference, and the
                // emitted array wrapper reference for T[] | null.
                yield nullableJavaType(n.inner(), span);
            }
            case Type.Error ignored -> null;
            default -> {
                unsupported("values of type " + typeName(t), span);
                yield null;
            }
        };
    }

    /** Java type of a LOCAL class value: only local module-level classes
     * have emitted Java types (see {@link #isLocalClassType}). */
    private String localClassJavaType(Type.Class c, Span span) {
        // Only LOCAL module-level classes have emitted Java types. A
        // checker-inferred class type can name an IMPORTED class (an
        // annotation-less declaration like `let c = lib.getC()`):
        // emitting its generated class reference without the class would
        // leave a symbol javac rejects after the CLI reported success.
        // Locality is decided from the Type.Class MODULE PATH — a
        // same-named local class must not satisfy the guard for a foreign
        // path (lib.C would then be declared as the LOCAL $C_C and javac
        // would reject the incompatible assignment). Imported classes /
        // cross-module nominal identity are deferred to ISSUE-0109 —
        // E6000, never a broken artifact.
        if (!isLocalClassType(c)) {
            unsupported("values of imported class type '" + c.name()
                + "' (imported classes / cross-module nominal identity "
                + "are deferred to ISSUE-0109)", span);
            return null;
        }
        if (!moduleClasses.containsKey(c.name())) {
            unsupported("values of class type '" + c.name()
                + "' (only local module-level classes are supported)",
                span);
            return null;
        }
        return classNameForClass(c.name());
    }

    /** Java type of a {@code T | null} value (ISSUE-0108): the boxed
     * reference for primitive {@code T}, the plain reference for
     * string/class/array {@code T}. {@code null} (with an E6000 recorded)
     * for any out-of-slice inner type. */
    private String nullableJavaType(Type inner, Span span) {
        return switch (inner) {
            case Type.Int ignored -> "java.lang.Long";
            case Type.Number ignored -> "java.lang.Double";
            case Type.Boolean ignored -> "java.lang.Boolean";
            case Type.String ignored -> "java.lang.String";
            case Type.Class c -> localClassJavaType(c, span);
            case Type.Array a -> {
                if (javaArrayElementType(a.element(), span) == null) {
                    yield null;
                }
                yield arrayWrapperName(a.element());
            }
            case Type.Null ignored -> {
                unsupported("nullable type 'null | null'", span);
                yield null;
            }
            default -> {
                unsupported("values of type " + typeName(inner) + " | null"
                    + " (only primitive, local class, and supported array"
                    + " inner types)", span);
                yield null;
            }
        };
    }

    // =========================================================================
    // Primitive array type mapping (ISSUE-0094)
    // =========================================================================

    /**
     * Java storage element type for a supported array element type, or
     * {@code null} (with an E6000 diagnostic recorded) for any other
     * element type: the four primitive elements, their nullable forms
     * (boxed storage for {@code (T | null)[]}), and local class elements
     * ({@code java.lang.Object} storage inside the per-class
     * {@code $Array$<C>} wrapper) are in scope — nested arrays, function
     * arrays, and nullable-table elements are rejected, never silently
     * miscompiled.
     */
    private String javaArrayElementType(Type element, Span span) {
        return switch (element) {
            case Type.Int ignored -> "long";
            case Type.Number ignored -> "double";
            case Type.String ignored -> "java.lang.String";
            case Type.Boolean ignored -> "boolean";
            case Type.Nullable ne -> switch (ne.inner()) {
                case Type.Int ignored -> "java.lang.Long";
                case Type.Number ignored -> "java.lang.Double";
                case Type.String ignored -> "java.lang.String";
                case Type.Boolean ignored -> "java.lang.Boolean";
                case Type.Class c -> {
                    if (localClassJavaType(c, span) == null) yield null;
                    yield "java.lang.Object";
                }
                default -> {
                    unsupported("arrays with element type "
                        + typeName(element)
                        + " (only int[], number[], string[], boolean[], "
                        + "local class arrays, and their nullable-element "
                        + "forms are supported)", span);
                    yield null;
                }
            };
            case Type.Class c -> {
                if (localClassJavaType(c, span) == null) yield null;
                yield "java.lang.Object";
            }
            default -> {
                unsupported("arrays with element type " + typeName(element)
                    + " (only int[], number[], string[], boolean[], local "
                    + "class arrays, and their nullable-element forms are "
                    + "supported)", span);
                yield null;
            }
        };
    }

    /** Emitted wrapper class name for a supported primitive element type.
     * Callers gate on {@link #javaArrayElementType} first, so a
     * {@code null} here only ever accompanies an already-recorded E6000.
     * The {@code __} prefix is unreachable from {@link #javaName} (every
     * DEAL underscore escapes to {@code $u}), so no user binding can
     * collide with the emitted class. */
    private String arrayWrapperName(Type element) {
        return switch (element) {
            case Type.Int ignored -> "__IntArray";
            case Type.Number ignored -> "__NumberArray";
            case Type.String ignored -> "__StringArray";
            case Type.Boolean ignored -> "__BooleanArray";
            case Type.Nullable ne -> switch (ne.inner()) {
                case Type.Int ignored -> "__IntOrNullArray";
                case Type.Number ignored -> "__NumberOrNullArray";
                case Type.String ignored -> "__StringOrNullArray";
                case Type.Boolean ignored -> "__BooleanOrNullArray";
                case Type.Class c -> classOrNullArrayWrapperName(c.name());
                default -> null;
            };
            case Type.Class c -> classArrayWrapperName(c.name());
            default -> null;
        };
    }

    /** Emitted read-helper method name for a supported primitive element
     * type (same {@code null}-on-unsupported contract as
     * {@link #arrayWrapperName}). */
    private String arrayReadHelper(Type element) {
        return switch (element) {
            case Type.Int ignored -> "__intArrayRead";
            case Type.Number ignored -> "__numberArrayRead";
            case Type.String ignored -> "__stringArrayRead";
            case Type.Boolean ignored -> "__booleanArrayRead";
            default -> null;
        };
    }

    /** Emitted boxed read-helper method name for a supported primitive
     * element type (same {@code null}-on-unsupported contract as
     * {@link #arrayReadHelper}). The boxed helper yields {@code null}
     * past the end (the LuaJIT nil) instead of raising E8001, for the
     * comparison positions whose read site applies no typed boundary
     * (spec §Bounds and nil behavior); a negative index still raises
     * E8002, which LuaJIT emits unconditionally at the read. */
    private String arrayReadBoxedHelper(Type element) {
        return switch (element) {
            case Type.Int ignored -> "__intArrayReadBoxed";
            case Type.Number ignored -> "__numberArrayReadBoxed";
            case Type.String ignored -> "__stringArrayReadBoxed";
            case Type.Boolean ignored -> "__booleanArrayReadBoxed";
            default -> null;
        };
    }

    /** Java reference type of a boxed read temporary for a supported
     * primitive element type (nullable, unlike the storage types). */
    private String arrayBoxedJavaType(Type element) {
        return switch (element) {
            case Type.Int ignored -> "java.lang.Long";
            case Type.Number ignored -> "java.lang.Double";
            case Type.String ignored -> "java.lang.String";
            case Type.Boolean ignored -> "java.lang.Boolean";
            default -> null;
        };
    }

    /** The nil-yielding boxed read helper for a supported primitive or
     * local class element type: the primitive boxed helpers (null past
     * the end, E8002 for a negative index), and the per-class
     * {@code $classOrNullArrayRead$<C>} helper for a local class
     * element ({@code C[]} reads in nil-accepting positions — a
     * {@code C | null} target or a comparison operand — yield the DEAL
     * null past the end with the same negative-index E8002). The caller
     * gates class locality before requesting the class helper. */
    private String boxedArrayReadHelper(Type element) {
        String boxed = arrayReadBoxedHelper(element);
        if (boxed != null) return boxed;
        if (element instanceof Type.Class cls) {
            return classOrNullArrayReadName(cls.name());
        }
        return null;
    }

    /** Java reference type of a nil-yielding read temporary for a
     * supported primitive or local class element type (the boxed
     * wrapper types; the generated class reference for a class element
     * — all nullable, unlike the storage types). */
    private String boxedArrayJavaType(Type element, Span span) {
        String boxed = arrayBoxedJavaType(element);
        if (boxed != null) return boxed;
        if (element instanceof Type.Class cls) {
            return localClassJavaType(cls, span);
        }
        return null;
    }

    /** Emitted write-helper method name for a supported primitive element
     * type (same {@code null}-on-unsupported contract as
     * {@link #arrayWrapperName}). */
    private String arrayWriteHelper(Type element) {
        return switch (element) {
            case Type.Int ignored -> "__intArrayWrite";
            case Type.Number ignored -> "__numberArrayWrite";
            case Type.String ignored -> "__stringArrayWrite";
            case Type.Boolean ignored -> "__booleanArrayWrite";
            default -> null;
        };
    }

    /** Emitted write-helper method name for a supported primitive inner
     * type of a nullable element ({@code (T | null)[]} writes accept the
     * DEAL null element — check_nullable permits it); {@code null} for
     * non-primitive inners (class inners route through the per-class
     * {@code $classOrNullArrayWrite$} helper). */
    private String orNullArrayWriteHelper(Type inner) {
        return switch (inner) {
            case Type.Int ignored -> "__intOrNullArrayWrite";
            case Type.Number ignored -> "__numberOrNullArrayWrite";
            case Type.String ignored -> "__stringOrNullArrayWrite";
            case Type.Boolean ignored -> "__booleanOrNullArrayWrite";
            default -> null;
        };
    }

    /** Emitted read-helper method name for a supported primitive inner
     * type of a nullable element ({@code (T | null)[]} reads yield the
     * DEAL null past the end); {@code null} for non-primitive inners
     * (class inners route through the per-class
     * {@code $classOrNullArrayRead$} helper). */
    private String orNullArrayReadHelper(Type inner) {
        return switch (inner) {
            case Type.Int ignored -> "__intOrNullArrayRead";
            case Type.Number ignored -> "__numberOrNullArrayRead";
            case Type.String ignored -> "__stringOrNullArrayRead";
            case Type.Boolean ignored -> "__booleanOrNullArrayRead";
            default -> null;
        };
    }

    /** Java return type for a function. {@code null} when unsupported. */
    private String javaReturnType(Type t, Span span) {
        if (t instanceof Type.Null) return "void";
        return javaLocalType(t, span);
    }

    private static String typeName(Type t) {
        if (t == null) return "<unknown>";
        return switch (t) {
            case Type.Null ignored -> "null";
            case Type.Boolean ignored -> "boolean";
            case Type.Int ignored -> "int";
            case Type.Number ignored -> "number";
            case Type.String ignored -> "string";
            case Type.Table ignored -> "table";
            case Type.Error ignored -> "error";
            case Type.Array a -> "array of " + typeName(a.element());
            case Type.Nullable n -> typeName(n.inner()) + " | null";
            case Type.Class c -> c.name();
            case Type.Func f -> "function";
        };
    }

    // =========================================================================
    // Literal rendering
    // =========================================================================

    /** True when e is exactly the null literal (no side effects, no evaluation). */
    private static boolean isBareNullLiteral(ExpressionNode e) {
        return e instanceof LiteralExpr lit
            && lit.value() instanceof LiteralValue.NullLiteral;
    }

    /**
     * Renders a double as a Java double literal. Non-finite values render
     * as the {@code Double} constants: the parser accepts e.g.
     * {@code 1e999} as Infinity ({@code Double.parseDouble} with no range
     * check) and the Lua backend emits {@code (1/0)} for it — Java has no
     * literal spelling for Infinity/NaN, so a bare {@code Infinity}
     * identifier would make javac reject an artifact the CLI reported as
     * successful.
     */
    private static String javaDoubleLiteral(double v) {
        if (Double.isNaN(v)) return "java.lang.Double.NaN";
        if (v == Double.POSITIVE_INFINITY) return "java.lang.Double.POSITIVE_INFINITY";
        if (v == Double.NEGATIVE_INFINITY) return "java.lang.Double.NEGATIVE_INFINITY";
        return Double.toString(v);
    }

    /** Renders a DEAL string as a Java string literal (UTF-8 source). */
    private static String quoteJavaString(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    // =========================================================================
    // Emission helpers and diagnostics
    // =========================================================================

    private void emitLine() {
        out.append('\n');
    }

    /** Emits every hoisted pre-statement (in evaluation order) and clears
     * the buffer. Called at each statement boundary, after all expression
     * emission for that statement is complete, so hoisted side effects run
     * exactly where Java's left-to-right evaluation would run them. */
    private void flushPreStatements() {
        for (PreLine line : preStatements) {
            if (!line.text().isEmpty()) {
                out.append("    ".repeat(indent + line.extraIndent()));
            }
            out.append(line.text()).append('\n');
        }
        preStatements.clear();
        preStatementsDeclareTemps = false;
    }

    /** A fresh dummy-local name for a forced evaluation ({@code __ignored},
     * {@code __ignored1}, …). The {@code __} prefix is unreachable from
     * {@link #javaName} (underscores escape to {@code $u}), so it can never
     * collide with a translated user identifier. */
    private String nextIgnoredName() {
        String name = ignoredCounter == 0
            ? "__ignored" : "__ignored" + ignoredCounter;
        ignoredCounter++;
        return name;
    }

    private void emitLine(String s) {
        if (!s.isEmpty()) out.append("    ".repeat(indent));
        out.append(s).append('\n');
    }

    /** Records an E6000 backend diagnostic for an out-of-scope construct. */
    private void unsupported(String what, Span span) {
        diagnostics.add(Diagnostic.error(DiagnosticCode.E6000,
            "JVM backend (skeleton) does not support " + what + " yet",
            span.file(), span.startLine(), span.startColumn()));
    }
}
