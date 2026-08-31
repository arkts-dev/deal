package deal.codegen.jvm;

import deal.ast.*;
import deal.ast.ArrayType;
import deal.ast.FunctionType;
import deal.ast.NullableType;
import deal.checker.CheckResult;
import deal.checker.Symbol;
import deal.checker.SymbolTable;
import deal.diagnostics.DiagnosticCode;
import deal.diagnostics.CompilerDiagnostic;
import deal.semantic.ir.SemanticProfile;
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
 * nullable slice + ISSUE-0100 host ABI slice + the @jsonable slice
 * (JSON serialization)):
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
 * {@code os.time() * 1000}), plus {@code std/table.keys}
 * (ISSUE-0102 — tables now map as first-class values).
 * {@code std/json} stays rejected with {@code E6000} at the import
 * statement (the @jsonable slice embeds its own JSON runtime instead).
 *
 * <p>ISSUE-0102 (the JVM conformance promotion gate) lifts the
 * remaining imperative surface: C-style {@code for} loops (plain and
 * hoisted-condition forms with per-iteration closure cells for the
 * loop variable), array {@code for-of} (one fresh binding per
 * iteration), {@code break}/{@code continue} (label-routed through the
 * transformed loop forms), {@code try}/{@code catch}/{@code throw} with
 * the builtin {@code Error} reified as the emitted {@code DealError}
 * (DEAL Error values map to {@code RuntimeException}, so caught errors
 * unify across module boundaries; {@code .code}/{@code .message} reads
 * dispatch through the reflective unwrap), {@code delete} and
 * {@code has()} for table fields and optional class fields (presence
 * flags), table field writes (the generic {@code $tPut}) and
 * primitive/function-typed table reads (the shared {@code $check}
 * seam), non-nullable array/table/class class fields with
 * per-construction fresh defaults, nested primitive arrays, function
 * arrays (including nullable elements), function expressions and
 * block-level functions with closure capture (captured bindings lower
 * to {@code final} cells; per-iteration loop bindings capture fresh
 * cells), nested and nullable function signature types, and
 * imported-class arrays ({@code Lib.Point[]}). Tables map to the SHARED
 * {@code $DealRt.Table} class (declared by the entry module's artifact
 * or the standalone adapter), so table values cross module boundaries
 * with shared identity. Anything outside this scope — nested class
 * declarations, {@code table | null} values, nullable tables, arrays of
 * table elements, async/await, cross-module
 * function-value flow (the per-module wrapper classes cannot cross a
 * module boundary), stdlib imports other than the five supported
 * modules — is rejected with a backend {@code E6000} diagnostic, never
 * silently miscompiled.
 *
 * <p>The @jsonable slice (JSON serialization) extends the class
 * surface: an exported {@code // @jsonable} class emits the spec's
 * generated {@code C$fromJson}/{@code C$toJson} module exports plus a
 * per-class field-descriptor table and the recursive
 * {@code $toJsonValue}/{@code $fromJsonValue} helpers on the generated
 * nested class — JSON parse/stringify run through emitted runtime
 * support ({@code __jsonParse}/{@code __jsonStringify}, mirroring
 * {@code std/json.lua}'s escaping and NaN/Infinity E8001 rejection),
 * field values validate per declared type (null on any
 * parse/validation failure — the public export never throws, even on
 * hostile deep-nesting input: the emitted parser converts its
 * StackOverflowError to the DEAL null like LuaJIT's
 * {@code pcall(__json_parse, s)}, the table-value conversion carries a
 * bounded depth guard, and the public {@code C$fromJson} wrapper
 * converts the guard throw and the nested-class recursion exhaustion
 * to the DEAL null), defaults evaluate inline per call, nullable
 * fields preserve the DEAL null, array fields (primitive,
 * nullable-element, and local-class element) roundtrip element-wise,
 * nested class fields recurse through the declaring module's
 * generated helpers (local or imported — the deserialized instance
 * carries the declaring module's module-qualified nominal identity),
 * optional fields keep their three states (absent / present null /
 * present value) through the Missing-sentinel storage and {@code
 * has()} presence checks, table fields map JSON objects to the shared
 * {@code $DealRt.Table} data (nested JSON arrays become array-mode
 * tables) with finite-acyclic JSON-shape validation in toJson, and
 * {@code std/json} imports stay E6000 (the helpers embed the JSON
 * runtime instead). Anything outside this scope — optional table
 * fields of @jsonable classes (their reads yield {@code table | null}),
 * nested class declarations, non-literal default expressions on
 * imported classes (their defaults evaluate in the declaring module's
 * scope under LuaJIT), arrays of non-primitive non-class elements,
 * {@code table | null} values, nullable tables, nested
 * (multi-dimensional) array fields, function-typed fields, stdlib
 * imports other than the five supported modules — is rejected with a
 * backend {@code E6000} diagnostic, never silently miscompiled. The
 * ISSUE-0100 host ABI
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
 * <p>Async/await (ISSUE-0099) extends the ISSUE-0100 blocking lowering
 * with the await-site completion check for DEAL async calls and lifts
 * async markers into the ISSUE-0098 function-value machinery: an
 * {@code async function} compiles to a plain static method returning
 * its declared type, and {@code await E} evaluates the direct call
 * {@code E} and applies the declared-return-type completion check at
 * the await site. The blocking call IS the internal async operation:
 * it starts when invoked, completes exactly once with the
 * declared-type value or by raising a {@code DealError} that
 * propagates at the await site (observable through the runner's
 * {@code DEAL_ERROR_CODE} contract), and evaluation before an
 * {@code await} precedes evaluation resumed after it. Int
 * completions route through {@code checkInt} (E8004 — the Java {@code
 * long} representation is wider than the DEAL int safe range); the
 * number/string/boolean/null completion checks are proven redundant by
 * the emitted Java types, which spec-v1.2 §JVM backend contract
 * permits. Async function values reuse the ISSUE-0098 wrapper
 * machinery unchanged: {@code resolveTypeNode} accepts the {@code
 * async} marker on function-type annotations, so a declared async
 * function produces the same per-signature wrapper class
 * ({@code Fn1_I_R_I} for {@code async (x: int) =&gt; int}) and
 * per-declaration wrapper instance field ({@code value$fn}) the sync
 * slice emits, typed locals/parameters of async function type hold
 * wrapper references, and awaited indirect calls
 * ({@code let f: async (x: int) =&gt; int = value; await f(6);})
 * dispatch through {@code invoke} with the same completion check.
 * Deferred to ISSUE-0110 with E6000: async function expressions and
 * function types with non-representable signatures
 * (arrays/classes/nullables/nested function types). Cross-module
 * function values stay E6000 for async signatures exactly like sync
 * ones (the per-module wrapper classes cannot cross a module
 * boundary). The frontend keeps enforcing every spec-v1.2 async rule
 * (E3012 await outside async, E3013 await on a non-async call, E3014
 * async call without await — the backend never sees a non-conforming
 * await).
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
 * <p>Function values (ISSUE-0098 slice): a function declaration produces a
 * first-class typed function value — a per-signature abstract wrapper
 * class ({@code Fn2_II_R_I} for {@code (int,int)->int}) carrying the
 * spec-convention runtime descriptor string and an {@code invoke} method
 * with the JVM-mapped signature, plus a per-declaration wrapper instance
 * field ({@code add$fn}) emitted at the declaration's source position
 * whose invoke delegates to the static method (the JVM form of the Lua
 * backend's runtime function wrappers). Typed/inferred variables,
 * parameters (callbacks), module fields, and returns of function type
 * hold wrapper references; indirect calls dispatch through
 * {@code invoke}, including callees that are call results
 * ({@code picker()(41)} → {@code __fn0 = picker(); __fn0.invoke(41L)} —
 * the callee is materialized into a single-assignment temporary at its
 * evaluation position, BEFORE any argument's hoisted pre-statements, so
 * the spec's callee-first evaluation order survives argument hoisting).
 * The int/number
 * conversion intrinsics are wrapped once per module exactly like the Lua
 * backend's top-of-chunk wrappers. Arity extension (a narrower function
 * type in a wider position — the only non-equal assignable shape) lowers
 * to a delegating adapter at variable-initializer and assignment
 * positions (extra parameters silently ignored) and to the runtime
 * signature check LuaJIT performs at callback-argument and return
 * boundaries: a wrapper of the target shape whose construction raises
 * {@code E8010} "function signature mismatch: expected …, got …" where
 * LuaJIT's parameter/return boundary check raises it. At check positions
 * the value expression is evaluated FIRST (materialized into a
 * temporary at its evaluation position) and later call arguments are
 * materialized before the raising construction, so every evaluation
 * completes exactly like LuaJIT's strict left-to-right
 * evaluate-then-check order — a side effect in the checked value is
 * never dropped. Adapters never capture: module functions delegate to
 * their static method, intrinsics to their conversion helper, module
 * fields to their static field read LIVE on every invoke (LuaJIT's
 * adapter body re-reads the binding, so a field reassigned after the
 * adapter's creation retargets the adapter), and a local/parameter to a
 * fresh effectively-final {@code __fn<n>} snapshot temporary only when
 * the enclosing function body never reassigns it (the binding's value
 * is then stable, so the snapshot equals LuaJIT's live read forever).
 * A local/parameter the enclosing body reassigns anywhere and any
 * non-identifier value expression (a call result, which LuaJIT
 * re-evaluates on every invoke) are rejected with E6000 until
 * ISSUE-0110, never a silent divergence; no lambda is ever emitted.
 * Function equality is wrapper
 * reference identity (LuaJIT's wrapper-table identity). Load-time value
 * uses of a not-yet-declared function are E6000 (LuaJIT reads the global
 * nil; Java would emit an illegal forward reference to the wrapper
 * field), and module-level indirect calls through function-typed fields
 * are guarded like module-level direct calls: the field's value set at
 * the call site must be statically known (a bare module-function or
 * intrinsic identifier initializer — followed transitively through
 * function-valued fields, with arity-adapter edges read at the call
 * site because the adapter re-reads the inner field live on every
 * invoke — and every preceding module-level assignment to it a bare
 * module-function or intrinsic identifier) and every function the
 * field may hold must not (transitively) read a later-declared field,
 * reach a later-declared function, or use a later-declared import —
 * otherwise E6000, never a silent divergence. Async markers are
 * ISSUE-0099-slice: the same wrapper classes, per-declaration wrapper
 * fields, indirect calls, adapters, and guards cover async signatures
 * unchanged (see the ISSUE-0099 paragraph above), with the
 * await-site completion check applied at every await. Function
 * expressions, nested functions, and signatures
 * containing arrays/classes/nullables/nested function types/rest arms
 * stay deferred to ISSUE-0110 and are rejected with E6000.
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
 * so tables emit a minimal ordered string-key map (the shared
 * {@code $DealRt.Table} class) and a
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
     * Java source, any backend diagnostics, and the backend-wide int mode
     * the generating backend stored (ISSUE-0374 profile plumb).
     * {@link #hasErrors()} gates compilation of the artifact.
     *
     * @param int32Mode true when the generating backend ran under the
     *                  {@code DEAL_V1_2_INT32} semantic profile (the one
     *                  backend-wide mode derived from
     *                  {@code profile == DEAL_V1_2_INT32}); the recorded
     *                  mode is the real stored backend state, never a
     *                  mirrored constant
     */
    public record JvmCodegenResult(String className, String source,
                                   List<CompilerDiagnostic> diagnostics,
                                   boolean int32Mode) {
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

    /** True when this artifact must carry the shared $DealRt table
     * class (the orchestrator's selected entry module or the standalone
     * single-module adapter). */
    private final boolean emitSharedTable;

    /**
     * The backend-wide int mode derived from the invocation's
     * project-wide semantic profile (ISSUE-0374 profile plumb): true
     * exactly when the profile passed to {@link #generate} was
     * {@link SemanticProfile#DEAL_V1_2_INT32}. One derivation per
     * backend instance — no static/global flag, no system property, and
     * no source, CLI, or environment selection surface exists. The mode
     * is recorded on the emitted {@link JvmCodegenResult} so tests
     * observe the real stored backend state; no int32 emission branches
     * off it yet, so every emitted artifact stays byte-identical to the
     * legacy emission.
     */
    private final boolean int32Mode;
    private final List<CompilerDiagnostic> diagnostics = new ArrayList<>();
    /** The generated Java source accumulator. Swapped to a temporary
     * buffer while an inline function-expression body emits (ISSUE-0102),
     * so it cannot be final. */
    private StringBuilder out = new StringBuilder();
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
        "std/console", "std/string", "std/math", "std/time", "std/table");

    /**
     * The spec-listed stdlib modules whose only functions take or return
     * a {@code table} — a value type the JVM slice does not support
     * (tables are E6000). Importing one is rejected at the import
     * statement with E6000, even when unused: none of its functions can
     * ever execute in this slice, and its require-time module object has
     * no JVM equivalent.
     */
    private static final Set<String> TABLE_BOUNDARY_STDLIB_MODULES = Set.of(
        "std/json");

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
     * inside nullable-returning functions (ISSUE-0108), and it is the
     * target type for returned function values under arity extension (a
     * wider declared return signature wraps a narrower actual function,
     * ISSUE-0098). */
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

    /** Counter for transformed-loop continue labels ({@code cont$0}, …).
     * Unreachable from {@link #javaName} for the same reason as the other
     * generated names. */
    private int loopLabelCounter = 0;

    /**
     * Per-function closure-capture state (ISSUE-0102): DEAL names of the
     * current function's locals/parameters that a nested function
     * (function expression or block-level function declaration) captures.
     * A captured binding lowers to a {@code final <T>[] <name>$c = { v }}
     * cell at its declaration; every read and write of the binding —
     * in the enclosing function body AND in every nested function body —
     * routes through {@code <name>$c[0]}, so nested functions observe
     * mutations exactly like LuaJIT's shared upvalues. Pushed and popped
     * per function in {@link #emitFunction}.
     */
    private final Deque<Set<String>> capturedNamesStack = new ArrayDeque<>();

    /**
     * Emitted Java names currently cell-ified for closure capture
     * ({@code x$c} for the mapped binding {@code x}). Kept in sync with
     * {@link #capturedNamesStack}: {@link #declareLocal} registers the
     * mapped name when the declared DEAL name is captured by a nested
     * function, and {@link #localJavaName} routes reads/writes through
     * the cell.
     */
    private final Deque<Set<String>> capturedMappedStack = new ArrayDeque<>();

    /**
     * Per-loop continue label ({@code null} when a plain Java
     * {@code continue} targets the nearest loop correctly). The
     * transformed for/while forms re-place the loop update inside the
     * body, so a DEAL {@code continue} must jump past the body to the
     * update — emitted as {@code break <label>;} on the labeled body
     * block. The checker rejects break/continue outside a loop (E2000),
     * so the stack is never empty at a break/continue emission.
     */
    private final Deque<String> loopContinueLabels = new java.util.LinkedList<>();

    /**
     * Emitted Java names whose reads temporarily bypass closure-cell
     * routing (the transformed/captured for-loop HEADER reads the plain
     * loop variable — its per-iteration cell is declared inside the
     * body).
     */
    private final Set<String> plainReadNames = new HashSet<>();

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

    /** Module-level @jsonable class declarations, in declaration order
     * (the JSON serialization slice). Registered in the pre-scan; drives
     * the conditional emission of the JSON runtime support and the
     * per-class generated {@code C$fromJson}/{@code C$toJson} helpers. */
    private final List<ClassDeclaration> jsonableClasses = new ArrayList<>();

    /** Module-level variable declarations by name (the AST nodes), for
     * the load-time indirect-call value analysis
     * ({@link #moduleIndirectCallRisk}). */
    private final Map<String, VariableDeclaration> moduleFieldDecls =
        new LinkedHashMap<>();

    /** The module body in declaration order, for the load-time
     * indirect-call guards. */
    private List<StatementNode> moduleStatements = List.of();

    /** Emitted function-wrapper shape classes (one abstract class per
     * distinct signature), accumulated during emission and spliced into
     * the class body right after the runtime support. */
    private final StringBuilder wrapperClasses = new StringBuilder();

    /** Wrapper shapes already registered (one class per shape name). */
    private final Set<String> emittedWrapperShapes = new LinkedHashSet<>();

    /** The statements of the function body currently being emitted, or
     * {@code null} at module level — the scan scope for the
     * adapter-capture reassignment check. */
    private List<StatementNode> currentFunctionBody = null;

    /** DEAL parameter names of the function body currently being emitted
     * (empty at module level) — the base bindings of the adapter-capture
     * reassignment scan. */
    private List<String> currentFunctionParams = List.of();

    /** Counter for function-value snapshot temporaries ({@code __fn0},
     * {@code __fn1}, …) that keep an arity adapter's captured value
     * effectively final without a lambda. Unreachable from
     * {@link #javaName} (the {@code __} prefix escapes to {@code $u}). */
    private int functionValueTempCounter = 0;


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

    /** Function name → module functions whose VALUES its body reads
     * transitively (through calls, the function itself included;
     * ISSUE-0099). Used to reject a module-level call of a function
     * that reaches a module function VALUE whose wrapper field is
     * assigned at a declaration point at or after the call site:
     * LuaJIT assigns function values at their declaration point and the
     * load-time read binds to the not-yet-declared global nil, while
     * Java would silently read the uninitialized static field's default
     * value. (The v1.2 module shape E1049 gate removes module-level
     * statements, so the guard is defensive like the sibling load-time
     * guards; the machinery stays documented for the same reason.) */
    private final Map<String, Set<String>> transitiveFunctionValueReads =
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

    /**
     * Function name → the module fields its body ASSIGNS, transitively
     * through direct calls to other module functions (any assignment
     * position — statements, value positions, hidden operands). Used by
     * the load-time indirect-call value-set walk: a module-level call of
     * such a function executed before the guarded field read is a
     * potential assignment source, so the field's value set is not
     * statically known and the call is rejected (LuaJIT executes the
     * assignment at load; the value-set walk must observe it).
     */
    private final Map<String, Set<String>> transitiveAssignedFields =
        new LinkedHashMap<>();

    /**
     * Function name → the function-valued bindings (module fields,
     * locals, parameters) its body invokes through INDIRECT calls,
     * transitively through direct calls to other module functions;
     * non-identifier function-typed callees contribute
     * {@link #UNKNOWN_HELD_VALUE}. Used to reject a module-level call of
     * a function whose (transitive) body contains an indirect call: the
     * invoked wrapper's value is not statically known to the load-time
     * guard — LuaJIT fails at load when the held function reaches a
     * not-yet-declared value, while Java would silently run the hoisted
     * method. Conservative until ISSUE-0110.
     */
    private final Map<String, Set<String>> transitiveIndirectCalls =
        new LinkedHashMap<>();

    /**
     * Function-typed module field name → the static superset of module
     * functions the field may hold at any load-time read: its
     * initializer (followed transitively through other function-valued
     * fields — an adapter over a field delegates to that field's value,
     * so the inner field's superset is included) plus EVERY assignment
     * to the field anywhere in the module (module-level statements and
     * every function body, hidden value positions included). A
     * non-identifier initializer/assignment value contributes
     * {@link #UNKNOWN_HELD_VALUE}. The superset over-approximates
     * (assignments in bodies that never run at load count too) and is
     * used only by the value-set walk's indirect-callee check to add a
     * conservative rejection, never to admit a shape.
     */
    private final Map<String, Set<String>> fieldValueSupersets =
        new LinkedHashMap<>();

    /** Sentinel member of the value-set analyses: the field's value (or
     * an indirect callee) is an expression that is not a bare
     * module-function/intrinsic identifier and cannot be analyzed. */
    private static final String UNKNOWN_HELD_VALUE = "<expression>";

    /** Maximum nesting depth the emitted {@code __jsonTableValue}
     * conversion recurses into (spec §JSON serialization fromJson):
     * past this bound the conversion throws, and the public
     * {@code C$fromJson} wrapper converts the throw to the DEAL null —
     * a deterministic guard against hostile deep-nesting stack
     * exhaustion instead of relying on a StackOverflowError at the
     * recursion limit. 512 levels of emitted conversion frames stay far
     * below the default JVM thread stack even with per-level iterator
     * frames. */
    private static final int JSON_TABLE_DEPTH_LIMIT = 512;

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
                       boolean isEntry, boolean emitSharedTable,
                       SemanticProfile semanticProfile) {
        this.typeMap = typeMap;
        this.symbols = symbols;
        this.sourcePath = sourcePath;
        this.modulePath = modulePath;
        this.isEntry = isEntry;
        this.emitSharedTable = emitSharedTable;
        this.int32Mode = semanticProfile == SemanticProfile.DEAL_V1_2_INT32;
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
     * Profile-aware single-module variant (ISSUE-0374 profile plumb,
     * harness profile-selection seam): the single-module conformance
     * adapter is a standalone compiled module — not an entry, but its
     * artifact emits the shared {@code $DealRt} table class — with the
     * caller's project-wide {@link SemanticProfile} deriving the emitted
     * helper bodies. The profile-less overloads above keep the
     * {@link SemanticProfile#LEGACY_SAFE_INT} default.
     */
    public static JvmCodegenResult generate(ProgramNode program, CheckResult result,
                                            String sourcePath, String modulePath,
                                            SemanticProfile semanticProfile) {
        return generate(program, result, sourcePath, modulePath, Map.of(),
            Map.of(), Map.of(), false, true, semanticProfile);
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
        // The single-module conformance adapter is a standalone compiled
        // module: it is not an entry (no main gate, no JVM entry point)
        // but its artifact must be self-contained, so it emits the
        // shared $DealRt table class (ISSUE-0102).
        return generate(program, result, sourcePath, modulePath,
            importResolutions, importedClasses, Map.of(), false, true);
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
        // The pre-plumb per-module path: only the selected ENTRY module
        // emits the shared table class (one per compiled project). The
        // unchanged signature defaults to the LEGACY_SAFE_INT semantic
        // profile (ISSUE-0374 profile plumb), so untouched direct
        // callers keep legacy behavior by construction.
        return generate(program, result, sourcePath, modulePath,
            importResolutions, importedClasses, hostModules, isEntry,
            isEntry, SemanticProfile.LEGACY_SAFE_INT);
    }

    /**
     * The profile-plumbed orchestrator per-module path (ISSUE-0374):
     * {@code CompilationOrchestrator.codegenAllJvm} passes
     * {@code invocation.semanticProfile()} here, and the backend derives
     * and stores one backend-wide int mode from
     * {@code profile == DEAL_V1_2_INT32} — recorded on the result as
     * {@link JvmCodegenResult#int32Mode()}. Only the selected ENTRY
     * module emits the shared table class (one per compiled project).
     * Source and the CLI gain no profile surface; the existing overloads
     * without a profile argument keep their signatures and default to
     * {@link SemanticProfile#LEGACY_SAFE_INT}.
     *
     * @param semanticProfile the invocation's project-wide semantic
     *                        profile; non-null
     */
    public static JvmCodegenResult generate(ProgramNode program, CheckResult result,
                                            String sourcePath, String modulePath,
                                            Map<String, String> importResolutions,
                                            Map<String, Map<String, ClassDeclaration>> importedClasses,
                                            Map<String, Map<String, Type>> hostModules,
                                            boolean isEntry,
                                            SemanticProfile semanticProfile) {
        return generate(program, result, sourcePath, modulePath,
            importResolutions, importedClasses, hostModules, isEntry,
            isEntry, semanticProfile);
    }

    /**
     * Full generate entry: {@code isEntry} gates the v1.2 entry surface
     * (E6004 main check, the JVM entry point), {@code emitSharedTable}
     * gates the shared {@code $DealRt} table class (the orchestrator's
     * selected entry module and the standalone single-module adapter).
     * The unchanged signature defaults to the {@code LEGACY_SAFE_INT}
     * semantic profile (ISSUE-0374 profile plumb).
     */
    public static JvmCodegenResult generate(ProgramNode program, CheckResult result,
                                            String sourcePath, String modulePath,
                                            Map<String, String> importResolutions,
                                            Map<String, Map<String, ClassDeclaration>> importedClasses,
                                            Map<String, Map<String, Type>> hostModules,
                                            boolean isEntry,
                                            boolean emitSharedTable) {
        return generate(program, result, sourcePath, modulePath,
            importResolutions, importedClasses, hostModules, isEntry,
            emitSharedTable, SemanticProfile.LEGACY_SAFE_INT);
    }

    /**
     * Full generate entry with the invocation's project-wide semantic
     * profile (ISSUE-0374 profile plumb): the backend stores one
     * backend-wide int mode derived from
     * {@code profile == DEAL_V1_2_INT32} and records it on the result.
     * {@code isEntry} gates the v1.2 entry surface (E6004 main check,
     * the JVM entry point), {@code emitSharedTable} gates the shared
     * {@code $DealRt} table class (the orchestrator's selected entry
     * module and the standalone single-module adapter). Every overload
     * without a profile argument defaults to
     * {@link SemanticProfile#LEGACY_SAFE_INT}.
     *
     * @param semanticProfile the invocation's project-wide semantic
     *                        profile; non-null
     */
    public static JvmCodegenResult generate(ProgramNode program, CheckResult result,
                                            String sourcePath, String modulePath,
                                            Map<String, String> importResolutions,
                                            Map<String, Map<String, ClassDeclaration>> importedClasses,
                                            Map<String, Map<String, Type>> hostModules,
                                            boolean isEntry,
                                            boolean emitSharedTable,
                                            SemanticProfile semanticProfile) {
        Objects.requireNonNull(semanticProfile,
            "semanticProfile must not be null");
        JvmBackend backend = new JvmBackend(result.typeMap(), result.symbolTable(),
            sourcePath, modulePath, importResolutions, importedClasses,
            hostModules, isEntry, emitSharedTable, semanticProfile);
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
    /**
     * Emitted runtime-helper signatures under {@code LEGACY_SAFE_INT} —
     * the pre-tree byte-identical base table (ISSUE-0375 keeps this arm
     * untouched). A DEAL function whose translated name and mapped
     * parameter types match a helper exactly would emit a duplicate Java
     * method; such declarations are rejected with E6000 instead.
     */
    private static final Map<String, List<String>> LEGACY_RUNTIME_HELPER_SIGNATURES = Map.ofEntries(
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
        Map.entry("numberFromNullable", List.of("java.lang.Double")),
        Map.entry("checkSig", List.of("java.lang.String", "java.lang.String")));

    /**
     * Emitted runtime-helper signatures under {@code DEAL_V1_2_INT32}
     * (ISSUE-0375 carrier/range switch, jvm-v12-int32-bytes D1): the
     * signed32 helper carriers ({@code int}/{@code java.lang.Integer}).
     * {@link #checkInt} still takes the wider {@code long} carrier — the
     * D3 boundary seam feeds it wider/boxed/foreign values — while every
     * other int-typed helper parameter and result is primitive {@code int}.
     */
    private static final Map<String, List<String>> INT32_RUNTIME_HELPER_SIGNATURES = Map.ofEntries(
        Map.entry("intAdd", List.of("int", "int")),
        Map.entry("intSub", List.of("int", "int")),
        Map.entry("intMul", List.of("int", "int")),
        Map.entry("intDiv", List.of("int", "int")),
        Map.entry("intMod", List.of("int", "int")),
        Map.entry("intPow", List.of("int", "int")),
        Map.entry("intNeg", List.of("int")),
        Map.entry("numMod", List.of("double", "double")),
        Map.entry("numPow", List.of("double", "double")),
        Map.entry("intFromNumber", List.of("double")),
        Map.entry("numberFromInt", List.of("int")),
        Map.entry("scalarCompare", List.of("java.lang.String", "java.lang.String")),
        Map.entry("checkInt", List.of("long")),
        Map.entry("__hasUnpairedSurrogate", List.of("java.lang.String")),
        Map.entry("loopCond", List.of("boolean")),
        Map.entry("booleanNotNull", List.of("java.lang.Boolean")),
        Map.entry("intFromNullable", List.of("java.lang.Integer")),
        Map.entry("numberFromNullable", List.of("java.lang.Double")),
        Map.entry("checkSig", List.of("java.lang.String", "java.lang.String")));

    /**
     * The emitted runtime-helper signature table for the backend's
     * backend-wide int mode (ISSUE-0375): the legacy table under
     * {@code LEGACY_SAFE_INT} (byte-identical to the pre-tree base) and
     * the signed32 table under {@code DEAL_V1_2_INT32}. The collision
     * guard keys on the table matching the helpers the artifact actually
     * emits for this backend instance.
     */
    private Map<String, List<String>> runtimeHelperSignatures() {
        return int32Mode ? INT32_RUNTIME_HELPER_SIGNATURES
                         : LEGACY_RUNTIME_HELPER_SIGNATURES;
    }

    /**
     * Translates a DEAL identifier to a Java identifier. The encoding is
     * injective and collision-free: {@code $} → {@code $d} and {@code _} →
     * {@code $u} first (escaped names never start with {@code _}), then Java
     * reserved words are prefixed with {@code _} (reserved-prefixed names
     * always start with {@code _}). The two output sets are disjoint, so a
     * genuine DEAL identifier can never collide with a translated reserved
     * word.
     */
    /**
     * The module-class-qualified reference to a static member (method or
     * field) of this module's emitted class. Every static delegation
     * inside an anonymous wrapper/adapter class body goes through this
     * helper: an UNQUALIFIED name resolves against the anonymous class
     * first, so a DEAL function named {@code invoke} recursed into the
     * wrapper's own invoke method (a stack overflow at runtime) and a
     * function named {@code descriptor} — or a field read of that name —
     * collided with the wrapper shape class's descriptor field (a javac
     * failure after the CLI reported success). Qualification pins the
     * call to the module's static method/field, whatever the DEAL name.
     */
    private String qualifiedStatic(String member) {
        return classNameFor(modulePath) + "." + member;
    }

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
                moduleFieldDecls.putIfAbsent(vd.name(), vd);
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
                if (cd.isJsonable()) jsonableClasses.add(cd);
            }
        }
        this.moduleStatements = List.copyOf(statements);
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
        if (!jsonableClasses.isEmpty()) {
            emitJsonRuntimeSupport();
        }

        // Function-wrapper shape classes are accumulated while statements
        // are emitted and spliced here, after the runtime support: the
        // class text must sit at class level, before the first member
        // that references it is ever executed.
        int wrapperInsertion = out.length();

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

        // ISSUE-0102: the entry module's artifact also declares the
        // SHARED runtime table class (a package-private top-level class
        // in the same file — every emitted artifact lives in the
        // default package, so all modules reference the one class and
        // table values cross module boundaries with shared identity).
        // The name starts with $, which classNameFor can never produce
        // (sanitizeSegment maps $ to _), so no module class can collide.
        if (emitSharedTable) {
            emitSharedTableClass();
        }

        if (wrapperClasses.length() > 0) {
            out.insert(wrapperInsertion, wrapperClasses.toString());
        }
        if (arrayHelpers.length() > 0) {
            out.insert(wrapperInsertion, arrayHelpers.toString());
        }

        return new JvmCodegenResult(className, out.toString(), diagnostics,
            int32Mode);
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
            diagnostics.add(CompilerDiagnostic.error(DiagnosticCode.E6004,
                "entry module must export non-async main(): null; found "
                + (foundAnyMain
                    ? "main with a different signature or an async marker"
                    : "no main export"),
                program.span()));
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
            case ForStatement fs -> containsModuleReturn(fs.body());
            case ForOfStatement fos -> containsModuleReturn(fos.body());
            case TryStatement ts -> containsModuleReturn(ts.tryBlock())
                || containsModuleReturn(ts.catchBlock());
            case ThrowStatement ts -> false;
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
        Map<String, Set<String>> directFieldAssigns = new LinkedHashMap<>();
        Map<String, Set<String>> directIndirectCalls = new LinkedHashMap<>();
        Map<String, Set<String>> directFnValueReads = new LinkedHashMap<>();
        for (Map.Entry<String, FunctionDeclaration> e : moduleFunctions.entrySet()) {
            Set<String> reads = new LinkedHashSet<>();
            Set<String> calls = new LinkedHashSet<>();
            Set<String> imports = new LinkedHashSet<>();
            Set<String> assigns = new LinkedHashSet<>();
            Set<String> indirect = new LinkedHashSet<>();
            Set<String> fnValues = new LinkedHashSet<>();
            collectBodyReferences(e.getValue(), reads, calls, imports, fnValues);
            collectBodyFieldAssignments(e.getValue(), assigns);
            collectBodyIndirectCalls(e.getValue(), indirect);
            directReads.put(e.getKey(), reads);
            directCalls.put(e.getKey(), calls);
            directImportReads.put(e.getKey(), imports);
            directFieldAssigns.put(e.getKey(), assigns);
            directIndirectCalls.put(e.getKey(), indirect);
            directFnValueReads.put(e.getKey(), fnValues);
        }
        for (String name : moduleFunctions.keySet()) {
            transitiveFieldReads.put(name, closureReads(name, directReads,
                directCalls, new LinkedHashMap<>(), new HashSet<>()));
            transitiveFunctionCalls.put(name, closureCalls(name, directCalls,
                new LinkedHashMap<>(), new HashSet<>()));
            transitiveImportReads.put(name, closureReads(name, directImportReads,
                directCalls, new LinkedHashMap<>(), new HashSet<>()));
            transitiveAssignedFields.put(name, closureReads(name,
                directFieldAssigns, directCalls, new LinkedHashMap<>(),
                new HashSet<>()));
            transitiveIndirectCalls.put(name, closureReads(name,
                directIndirectCalls, directCalls, new LinkedHashMap<>(),
                new HashSet<>()));
            transitiveFunctionValueReads.put(name, closureReads(name,
                directFnValueReads, directCalls, new LinkedHashMap<>(),
                new HashSet<>()));
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
            Set<String> importReads, Set<String> functionValueReads) {
        Deque<Set<String>> locals = new ArrayDeque<>();
        Set<String> params = new LinkedHashSet<>();
        for (Parameter p : fd.params()) params.add(p.name());
        locals.push(params);
        collectStatementListRefs(fd.body().statements(), locals,
            fieldReads, calledFunctions, importReads, functionValueReads);
    }

    private void collectStatementListRefs(List<StatementNode> stmts,
            Deque<Set<String>> locals, Set<String> fieldReads,
            Set<String> calledFunctions, Set<String> importReads,
            Set<String> functionValueReads) {
        for (StatementNode stmt : stmts) {
            collectStatementRefs(stmt, locals, fieldReads, calledFunctions,
                importReads, functionValueReads);
        }
    }

    private void collectStatementRefs(StatementNode stmt,
            Deque<Set<String>> locals, Set<String> fieldReads,
            Set<String> calledFunctions, Set<String> importReads,
            Set<String> functionValueReads) {
        switch (stmt) {
            case VariableDeclaration vd -> {
                collectExprRefs(vd.initializer(), locals, fieldReads,
                    calledFunctions, importReads, functionValueReads);
                locals.peek().add(vd.name());
            }
            case ReturnStatement rs -> rs.expr().ifPresent(
                e -> collectExprRefs(e, locals, fieldReads, calledFunctions,
                    importReads, functionValueReads));
            case ExpressionStatement es ->
                collectExprRefs(es.expr(), locals, fieldReads,
                    calledFunctions, importReads, functionValueReads);
            case IfStatement is -> {
                collectExprRefs(is.condition(), locals, fieldReads,
                    calledFunctions, importReads, functionValueReads);
                collectBlockRefs(is.thenBlock(), locals, fieldReads,
                    calledFunctions, importReads, functionValueReads);
                if (is.elseBranch().isPresent()) {
                    switch (is.elseBranch().get()) {
                        case Either.Left<IfStatement, Block> left ->
                            collectStatementRefs(left.value(), locals,
                                fieldReads, calledFunctions, importReads,
                                functionValueReads);
                        case Either.Right<IfStatement, Block> right ->
                            collectBlockRefs(right.value(), locals,
                                fieldReads, calledFunctions, importReads,
                                functionValueReads);
                    }
                }
            }
            case WhileStatement ws -> {
                collectExprRefs(ws.condition(), locals, fieldReads,
                    calledFunctions, importReads, functionValueReads);
                collectBlockRefs(ws.body(), locals, fieldReads,
                    calledFunctions, importReads, functionValueReads);
            }
            case Block b -> collectBlockRefs(b, locals, fieldReads,
                calledFunctions, importReads, functionValueReads);
            // Unsupported statement kinds (for/for-of, try, nested
            // functions, classes, …) are rejected with E6000 when emitted;
            // nothing to walk here.
            default -> { }
        }
    }

    private void collectBlockRefs(Block b, Deque<Set<String>> locals,
            Set<String> fieldReads, Set<String> calledFunctions,
            Set<String> importReads, Set<String> functionValueReads) {
        locals.push(new LinkedHashSet<>());
        collectStatementListRefs(b.statements(), locals, fieldReads,
            calledFunctions, importReads, functionValueReads);
        locals.pop();
    }

    private void collectExprRefs(ExpressionNode e, Deque<Set<String>> locals,
            Set<String> fieldReads, Set<String> calledFunctions,
            Set<String> importReads, Set<String> functionValueReads) {
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
                // A module function used as a VALUE (not a call callee,
                // sync or async — ISSUE-0099): the wrapper instance
                // field is assigned at the function's declaration point,
                // so a module-level call reaching such a read at or
                // after that point must be rejected (LuaJIT reads the
                // not-yet-declared global nil at load; Java would read
                // the uninitialized static field default).
                if (symbols.resolve(id.name()) instanceof Symbol.FunctionSymbol
                        && moduleFunctions.containsKey(id.name())
                        && !isLocallyBound(locals, id.name())) {
                    functionValueReads.add(id.name());
                }
            }
            case BinaryExpr bin -> {
                collectExprRefs(bin.left(), locals, fieldReads,
                    calledFunctions, importReads, functionValueReads);
                collectExprRefs(bin.right(), locals, fieldReads,
                    calledFunctions, importReads, functionValueReads);
            }
            case UnaryExpr u ->
                collectExprRefs(u.expr(), locals, fieldReads, calledFunctions,
                    importReads, functionValueReads);
            case CallExpr call -> {
                if (call.callee() instanceof IdentifierExpr id
                        && symbols.resolve(id.name()) instanceof Symbol.FunctionSymbol
                        && moduleFunctions.containsKey(id.name())) {
                    calledFunctions.add(id.name());
                } else {
                    collectExprRefs(call.callee(), locals, fieldReads,
                        calledFunctions, importReads, functionValueReads);
                }
                for (ExpressionNode arg : call.args()) {
                    collectExprRefs(arg, locals, fieldReads, calledFunctions,
                        importReads, functionValueReads);
                }
            }
            // The member-access object is the import alias for an imported
            // direct call (lib.add(...)) — walked so the alias lands in
            // importReads.
            case MemberAccessExpr mae ->
                collectExprRefs(mae.object(), locals, fieldReads,
                    calledFunctions, importReads, functionValueReads);
            // An await's callee is a direct async call — walked exactly
            // like any other call so its callee lands in calledFunctions
            // and its argument reads land in fieldReads/functionValueReads
            // (ISSUE-0099).
            case AwaitExpression aw ->
                collectExprRefs(aw.callee(), locals, fieldReads,
                    calledFunctions, importReads, functionValueReads);
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
                    importReads, functionValueReads);
                if (ae.target() instanceof IndexExpr idx) {
                    collectExprRefs(idx.array(), locals, fieldReads,
                        calledFunctions, importReads, functionValueReads);
                    collectExprRefs(idx.index(), locals, fieldReads,
                        calledFunctions, importReads, functionValueReads);
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
                    calledFunctions, importReads, functionValueReads);
                collectExprRefs(idx.index(), locals, fieldReads,
                    calledFunctions, importReads, functionValueReads);
            }
            case ArrayLiteralExpr al -> {
                for (ExpressionNode elem : al.elements()) {
                    collectExprRefs(elem, locals, fieldReads, calledFunctions,
                        importReads, functionValueReads);
                }
            }
            // Object literals: property values are value positions (class
            // construction provided values and table literal values —
            // ISSUE-0095); class-construction defaults are value positions
            // too (evaluated per construction at the construction site).
            case ObjectLiteralExpr ol -> {
                for (Property prop : ol.properties()) {
                    collectExprRefs(prop.value(), locals, fieldReads,
                        calledFunctions, importReads, functionValueReads);
                }
                if (typeOf(ol) instanceof Type.Class cls
                        && isLocalClassType(cls)) {
                    ClassDeclaration cd = moduleClasses.get(cls.name());
                    if (cd != null) {
                        for (ClassField cf : cd.fields()) {
                            cf.defaultExpr().ifPresent(
                                d -> collectExprRefs(d, locals, fieldReads,
                                    calledFunctions, importReads,
                                    functionValueReads));
                        }
                    }
                }
            }
            // Template interpolations are value positions; the literal
            // parts carry no references.
            case TemplateLiteralExpr tl -> {
                for (ExpressionNode part : tl.parts()) {
                    collectExprRefs(part, locals, fieldReads, calledFunctions,
                        importReads, functionValueReads);
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

    /**
     * Collects into {@code assigned} the module fields {@code fd}'s body
     * assigns (an assignment expression whose target is an identifier
     * resolving to a module field and not shadowed by a parameter or
     * local), in every value position (statements, call arguments,
     * object-literal property values, array-literal elements, index
     * operands, class-construction defaults) — the same shapes
     * {@link #collectExprRefs} walks. Used by
     * {@link #computeTransitiveFieldReads} for
     * {@link #transitiveAssignedFields}: a module-level call of a
     * function whose body assigns a function-typed field retargets that
     * field at load time, so the value-set walk must observe it.
     */
    private void collectBodyFieldAssignments(FunctionDeclaration fd,
            Set<String> assigned) {
        Deque<Set<String>> locals = new ArrayDeque<>();
        Set<String> params = new LinkedHashSet<>();
        for (Parameter p : fd.params()) params.add(p.name());
        locals.push(params);
        collectStatementFieldAssignments(fd.body().statements(), locals,
            assigned);
    }

    private void collectStatementFieldAssignments(List<StatementNode> stmts,
            Deque<Set<String>> locals, Set<String> assigned) {
        for (StatementNode stmt : stmts) {
            switch (stmt) {
                case VariableDeclaration vd -> {
                    collectExprFieldAssignments(vd.initializer(), locals,
                        assigned);
                    locals.peek().add(vd.name());
                }
                case ReturnStatement rs -> rs.expr().ifPresent(
                    e -> collectExprFieldAssignments(e, locals, assigned));
                case ExpressionStatement es -> collectExprFieldAssignments(
                    es.expr(), locals, assigned);
                case IfStatement is -> {
                    collectExprFieldAssignments(is.condition(), locals,
                        assigned);
                    collectBlockFieldAssignments(is.thenBlock(), locals,
                        assigned);
                    if (is.elseBranch().isPresent()) {
                        switch (is.elseBranch().get()) {
                            case Either.Left<IfStatement, Block> left ->
                                collectStatementFieldAssignments(
                                    List.of(left.value()), locals, assigned);
                            case Either.Right<IfStatement, Block> right ->
                                collectBlockFieldAssignments(right.value(),
                                    locals, assigned);
                        }
                    }
                }
                case WhileStatement ws -> {
                    collectExprFieldAssignments(ws.condition(), locals,
                        assigned);
                    collectBlockFieldAssignments(ws.body(), locals, assigned);
                }
                case Block b -> collectBlockFieldAssignments(b, locals,
                    assigned);
                // Unsupported statement kinds (for/for-of, try, nested
                // functions, classes, …) are rejected with E6000 when
                // emitted; nothing to walk here.
                default -> { }
            }
        }
    }

    private void collectBlockFieldAssignments(Block b,
            Deque<Set<String>> locals, Set<String> assigned) {
        locals.push(new LinkedHashSet<>());
        collectStatementFieldAssignments(b.statements(), locals, assigned);
        locals.pop();
    }

    private void collectExprFieldAssignments(ExpressionNode e,
            Deque<Set<String>> locals, Set<String> assigned) {
        switch (e) {
            case AssignmentExpr ae -> {
                collectExprFieldAssignments(ae.value(), locals, assigned);
                if (ae.target() instanceof IdentifierExpr id
                        && !isLocallyBound(locals, id.name())
                        && moduleFieldIndices.containsKey(id.name())) {
                    assigned.add(id.name());
                }
                // A non-identifier target's sub-expressions are value
                // positions: an assignment hidden there executes at the
                // assignment, exactly like a bare target.
                switch (ae.target()) {
                    case IndexExpr idx -> {
                        collectExprFieldAssignments(idx.array(), locals,
                            assigned);
                        collectExprFieldAssignments(idx.index(), locals,
                            assigned);
                    }
                    case MemberAccessExpr mae ->
                        collectExprFieldAssignments(mae.object(), locals,
                            assigned);
                    default -> { }
                }
            }
            case BinaryExpr bin -> {
                collectExprFieldAssignments(bin.left(), locals, assigned);
                collectExprFieldAssignments(bin.right(), locals, assigned);
            }
            case UnaryExpr u ->
                collectExprFieldAssignments(u.expr(), locals, assigned);
            case CallExpr call -> {
                collectExprFieldAssignments(call.callee(), locals, assigned);
                for (ExpressionNode arg : call.args()) {
                    collectExprFieldAssignments(arg, locals, assigned);
                }
            }
            // An await's callee is a direct async call: its argument
            // positions are value positions exactly like any other
            // call's (ISSUE-0099).
            case AwaitExpression aw ->
                collectExprFieldAssignments(aw.callee(), locals, assigned);
            case MemberAccessExpr mae ->
                collectExprFieldAssignments(mae.object(), locals, assigned);
            case IndexExpr idx -> {
                collectExprFieldAssignments(idx.array(), locals, assigned);
                collectExprFieldAssignments(idx.index(), locals, assigned);
            }
            case ArrayLiteralExpr al -> {
                for (ExpressionNode elem : al.elements()) {
                    collectExprFieldAssignments(elem, locals, assigned);
                }
            }
            case ObjectLiteralExpr ol -> {
                for (Property prop : ol.properties()) {
                    collectExprFieldAssignments(prop.value(), locals,
                        assigned);
                }
                if (typeOf(ol) instanceof Type.Class cls
                        && isLocalClassType(cls)) {
                    ClassDeclaration cd = moduleClasses.get(cls.name());
                    if (cd != null) {
                        for (ClassField cf : cd.fields()) {
                            cf.defaultExpr().ifPresent(
                                d -> collectExprFieldAssignments(d, locals,
                                    assigned));
                        }
                    }
                }
            }
            case TemplateLiteralExpr tl -> {
                for (ExpressionNode part : tl.parts()) {
                    collectExprFieldAssignments(part, locals, assigned);
                }
            }
            default -> { }
        }
    }

    /**
     * Collects into {@code indirect} the function-valued bindings
     * {@code fd}'s body invokes through indirect calls: every call whose
     * callee has function type and is not a direct module-function call,
     * a conversion-intrinsic call, or an imported-module member call
     * (those carry their own load-time analyses — later-function,
     * intrinsic, and import-hazard checks). Identifier callees contribute
     * their name; any other function-typed callee contributes
     * {@link #UNKNOWN_HELD_VALUE}. Used by
     * {@link #computeTransitiveFieldReads} for
     * {@link #transitiveIndirectCalls}: a module-level call of a function
     * whose (transitive) body invokes a wrapper is rejected — the wrapper
     * value is not statically known to the load-time guard (conservative
     * until ISSUE-0110).
     */
    private void collectBodyIndirectCalls(FunctionDeclaration fd,
            Set<String> indirect) {
        for (StatementNode stmt : fd.body().statements()) {
            collectStatementIndirectCalls(stmt, indirect);
        }
    }

    private void collectStatementIndirectCalls(StatementNode stmt,
            Set<String> indirect) {
        switch (stmt) {
            case VariableDeclaration vd ->
                collectExprIndirectCalls(vd.initializer(), indirect);
            case ReturnStatement rs -> rs.expr().ifPresent(
                e -> collectExprIndirectCalls(e, indirect));
            case ExpressionStatement es ->
                collectExprIndirectCalls(es.expr(), indirect);
            case IfStatement is -> {
                collectExprIndirectCalls(is.condition(), indirect);
                collectBlockIndirectCalls(is.thenBlock(), indirect);
                if (is.elseBranch().isPresent()) {
                    switch (is.elseBranch().get()) {
                        case Either.Left<IfStatement, Block> left ->
                            collectStatementIndirectCalls(left.value(),
                                indirect);
                        case Either.Right<IfStatement, Block> right ->
                            collectBlockIndirectCalls(right.value(), indirect);
                    }
                }
            }
            case WhileStatement ws -> {
                collectExprIndirectCalls(ws.condition(), indirect);
                collectBlockIndirectCalls(ws.body(), indirect);
            }
            case Block b -> collectBlockIndirectCalls(b, indirect);
            // Unsupported statement kinds (for/for-of, try, nested
            // functions, classes, …) are rejected with E6000 when
            // emitted; nothing to walk here.
            default -> { }
        }
    }

    private void collectBlockIndirectCalls(Block b, Set<String> indirect) {
        for (StatementNode stmt : b.statements()) {
            collectStatementIndirectCalls(stmt, indirect);
        }
    }

    private void collectExprIndirectCalls(ExpressionNode e,
            Set<String> indirect) {
        switch (e) {
            case AwaitExpression aw ->
                collectExprIndirectCalls(aw.callee(), indirect);
            case CallExpr call -> {
                ExpressionNode callee = call.callee();
                if (callee instanceof IdentifierExpr id) {
                    Symbol sym = symbols.resolve(id.name());
                    boolean direct = sym instanceof Symbol.FunctionSymbol
                        && moduleFunctions.containsKey(id.name());
                    boolean intrinsic = sym instanceof Symbol.IntrinsicSymbol;
                    if (!direct && !intrinsic
                            && typeOf(callee) instanceof Type.Func) {
                        indirect.add(id.name());
                    }
                } else if (callee instanceof MemberAccessExpr mae
                        && mae.object() instanceof IdentifierExpr oid
                        && importAliases.containsKey(oid.name())) {
                    // An imported-module member call: covered by the
                    // import-hazard analysis, not an indirect call.
                } else {
                    if (typeOf(callee) instanceof Type.Func) {
                        indirect.add(UNKNOWN_HELD_VALUE);
                    }
                    collectExprIndirectCalls(callee, indirect);
                }
                for (ExpressionNode arg : call.args()) {
                    collectExprIndirectCalls(arg, indirect);
                }
            }
            case BinaryExpr bin -> {
                collectExprIndirectCalls(bin.left(), indirect);
                collectExprIndirectCalls(bin.right(), indirect);
            }
            case UnaryExpr u ->
                collectExprIndirectCalls(u.expr(), indirect);
            case AssignmentExpr ae -> {
                collectExprIndirectCalls(ae.value(), indirect);
                switch (ae.target()) {
                    case IndexExpr idx -> {
                        collectExprIndirectCalls(idx.array(), indirect);
                        collectExprIndirectCalls(idx.index(), indirect);
                    }
                    case MemberAccessExpr mae ->
                        collectExprIndirectCalls(mae.object(), indirect);
                    default -> { }
                }
            }
            case MemberAccessExpr mae ->
                collectExprIndirectCalls(mae.object(), indirect);
            case IndexExpr idx -> {
                collectExprIndirectCalls(idx.array(), indirect);
                collectExprIndirectCalls(idx.index(), indirect);
            }
            case ArrayLiteralExpr al -> {
                for (ExpressionNode elem : al.elements()) {
                    collectExprIndirectCalls(elem, indirect);
                }
            }
            case ObjectLiteralExpr ol -> {
                for (Property prop : ol.properties()) {
                    collectExprIndirectCalls(prop.value(), indirect);
                }
                if (typeOf(ol) instanceof Type.Class cls
                        && isLocalClassType(cls)) {
                    ClassDeclaration cd = moduleClasses.get(cls.name());
                    if (cd != null) {
                        for (ClassField cf : cd.fields()) {
                            cf.defaultExpr().ifPresent(
                                d -> collectExprIndirectCalls(d, indirect));
                        }
                    }
                }
            }
            case TemplateLiteralExpr tl -> {
                for (ExpressionNode part : tl.parts()) {
                    collectExprIndirectCalls(part, indirect);
                }
            }
            default -> { }
        }
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

    /** The name of a module function whose VALUE (the wrapper instance
     * field, assigned at the function's declaration point) is read
     * transitively by {@code functionName}'s body, declared at or after
     * {@code callIndex}, or {@code null} (ISSUE-0099). A module-level
     * call reaching such a read before the declaration point fails at
     * load under LuaJIT (the function value is a global nil until the
     * declaration runs); Java would silently read the uninitialized
     * static field's default value. Defensive in the v1.2 module shape
     * (E1049 removes module-level statements), like the sibling
     * load-time guards. */
    private String laterFunctionValueRead(String functionName, int callIndex) {
        for (String fn : transitiveFunctionValueReads.getOrDefault(
                functionName, Set.of())) {
            Integer idx = moduleFunctionIndices.get(fn);
            if (idx != null && idx >= callIndex) return fn;
        }
        return null;
    }

    // =========================================================================
    // Function-body access to later-declared module fields (reads and
    // writes — write-dominance analysis)
    // =========================================================================
    // =========================================================================
    // Module-level (load-time) indirect calls through function-valued
    // fields (ISSUE-0098 slice)
    // =========================================================================

    /**
     * Returns the rejection description for a module-level indirect call
     * through the function-typed field {@code fieldName} at the current
     * module statement index, or {@code null} when the call is safe.
     * The field's value set at the call site must be statically known —
     * its initializer (followed transitively through function-valued
     * fields, including arity-adapter edges, see
     * {@link #collectPossibleHeldFunctions}) and every module-level
     * assignment to it before the call site — including assignments
     * inside the call's own top-level statement (see
     * {@link #moduleLevelAssignedFunctions}) and assignments hidden in
     * any value position (object-literal property values and
     * class-construction defaults, array-literal elements, index
     * operands — the scan mirrors {@link #collectExprRefs}) — must be
     * bare module-function or intrinsic identifiers, and every function
     * the field may hold gets the same load-time guards as a direct
     * module-level call:
     * <ul>
     * <li>its body must not (transitively) read a module field declared
     * at or after the call site — LuaJIT fails at load reading the nil
     * field, Java would silently read the field's default value;</li>
     * <li>it must not (transitively) reach a module function declared at
     * or after the call site — LuaJIT fails at load reading the
     * not-yet-assigned function value, Java would hoist the method;</li>
     * <li>it must not (transitively) use an import alias declared at or
     * after the call site — LuaJIT emits the require at the import's
     * source position and fails at load reading the not-yet-required
     * global, Java would silently initialize the imported class;</li>
     * <li>its body must not (transitively) invoke a function-valued
     * binding (a module field, local, or parameter) through an indirect
     * call — the invoked wrapper's value is not statically known to
     * this guard, so the held function's load-time hazards are not
     * analyzable: LuaJIT fails at load when the invoked function
     * reaches a not-yet-declared value, Java would silently run the
     * hoisted method (conservative until ISSUE-0110).</li>
     * </ul>
     * A module-level call of another function executed before the read
     * is a potential assignment source: its body (transitively) may
     * assign the field (see {@link #exprAssignedFunctions}), and such an
     * assignment makes the value set not statically known.
     */
    private String moduleIndirectCallRisk(String fieldName) {
        // The field's runtime value at the call site is statically known
        // only when its initializer AND every module-level assignment to
        // it before the call site are bare module-function or intrinsic
        // identifiers; every other shape (a call result, an adapter
        // expression, an unknown field) is conservatively rejected. When
        // the value set IS known, the same load-time guards as for
        // direct module-level calls apply to every function the field
        // may hold: the JVM static field mirrors LuaJIT's load-time
        // local (the static-initializer interleaving preserves source
        // order and control flow), so the last executed assignment
        // determines the value on both backends.
        int callIndex = currentModuleStatementIndex;
        Set<String> possible = new LinkedHashSet<>();
        boolean unknown = collectPossibleHeldFunctions(fieldName, callIndex,
            new HashSet<>(), possible);
        if (unknown) {
            return "module-level indirect call through '" + fieldName
                + "' whose value is not statically known (an initializer "
                + "or assignment that is not a bare module-function/"
                + "intrinsic identifier)";
        }
        for (String fn : possible) {
            String later = laterFieldRead(fn, callIndex);
            if (later != null) {
                return "module-level indirect call through '" + fieldName
                    + "' whose value ('" + fn + "') (transitively) reads the "
                    + "module field '" + later + "' declared at or after the "
                    + "call site (LuaJIT fails at load with a nil read; Java "
                    + "would silently read the default value)";
            }
            String laterFn = laterFunctionCall(fn, callIndex);
            if (laterFn != null) {
                return "module-level indirect call through '" + fieldName
                    + "' whose value ('" + fn + "') reaches function '"
                    + laterFn + "' declared at or after the call site (LuaJIT "
                    + "assigns function values at their declaration point and "
                    + "fails at load with a nil read; Java hoists methods and "
                    + "would silently run)";
            }
            String laterImport = laterImportRead(fn, callIndex);
            if (laterImport != null) {
                return "module-level indirect call through '" + fieldName
                    + "' whose value ('" + fn + "') (transitively) uses the "
                    + "import '" + laterImport + "' declared at or after the "
                    + "call site (LuaJIT has not run the require yet and "
                    + "fails at load; Java would silently initialize the "
                    + "imported class)";
            }
            String indirect = transitiveIndirectCalls
                .getOrDefault(fn, Set.of()).stream().findFirst()
                .orElse(null);
            if (indirect != null) {
                return "module-level indirect call through '" + fieldName
                    + "' whose value ('" + fn + "') (transitively) invokes "
                    + "the function value '" + indirect + "' (an indirect "
                    + "call executed at load time — the held value is not "
                    + "statically known to the load-time guard, so its "
                    + "load-time hazards cannot be checked; LuaJIT fails at "
                    + "load when the invoked function reaches a "
                    + "not-yet-declared value, Java would silently run the "
                    + "hoisted method)";
            }
        }
        return null;
    }

    /**
     * The module functions assigned to {@code fieldName} by module-level
     * statements between its declaration and the call site, INCLUDING the
     * call's own top-level statement ({@code callIndex} inclusive), or
     * {@code null} when any assignment's value is not a statically known
     * bare module-function/intrinsic identifier. The JVM
     * static-initializer interleaving mirrors LuaJIT's load-time
     * execution (source order and control flow), so every function this
     * walk observes is one the field may genuinely hold at the call site.
     * The call's own statement is walked because an assignment evaluated
     * earlier within it — a sub-expression of the call's enclosing
     * initializer/condition, or a statement inside the same while/if/block
     * body — genuinely precedes the call at load time (LuaJIT executes it
     * before the read); the walk over-approximates inside that one
     * statement (assignments after the call position count too), and the
     * over-approximation only ever adds a conservative rejection, never a
     * silent divergence. A module-level CALL of another function in the
     * walked statements is a potential assignment source: the invoked
     * body's transitive assignments to the field make the value set not
     * statically known (see {@link #exprAssignedFunctions}).
     */
    private List<String> moduleLevelAssignedFunctions(String fieldName,
            int callIndex) {
        Integer decl = moduleFieldIndices.get(fieldName);
        if (decl == null) return List.of();
        List<String> result = new ArrayList<>();
        for (int i = decl + 1; i <= callIndex && i < moduleStatements.size(); i++) {
            if (!stmtAssignedFunctions(moduleStatements.get(i), fieldName,
                    result)) {
                return null;
            }
        }
        return result;
    }

    /** True when every assignment to {@code name} inside {@code stmt}
     * assigns a bare module-function or intrinsic identifier (collecting
     * the module functions into {@code out}); false when any assigned
     * value is not statically known. */
    private boolean stmtAssignedFunctions(StatementNode stmt, String name,
            List<String> out) {
        return switch (stmt) {
            case ExpressionStatement es -> exprAssignedFunctions(es.expr(),
                name, out);
            case VariableDeclaration vd -> exprAssignedFunctions(vd.initializer(),
                name, out);
            case ReturnStatement rs -> rs.expr().isEmpty()
                || exprAssignedFunctions(rs.expr().get(), name, out);
            case IfStatement is -> exprAssignedFunctions(is.condition(), name, out)
                && stmtAssignedFunctions(is.thenBlock(), name, out)
                && (is.elseBranch().isEmpty()
                    || elseBranchAssignedFunctions(is.elseBranch().get(), name, out));
            case WhileStatement ws -> exprAssignedFunctions(ws.condition(), name, out)
                && stmtAssignedFunctions(ws.body(), name, out);
            case Block b -> blockAssignedFunctions(b, name, out);
            default -> true;
        };
    }

    private boolean elseBranchAssignedFunctions(
            Either<IfStatement, Block> branch, String name, List<String> out) {
        return switch (branch) {
            case Either.Left<IfStatement, Block> left ->
                stmtAssignedFunctions(left.value(), name, out);
            case Either.Right<IfStatement, Block> right ->
                blockAssignedFunctions(right.value(), name, out);
        };
    }

    private boolean blockAssignedFunctions(Block b, String name,
            List<String> out) {
        for (StatementNode stmt : b.statements()) {
            if (!stmtAssignedFunctions(stmt, name, out)) return false;
        }
        return true;
    }

    private boolean exprAssignedFunctions(ExpressionNode e, String name,
            List<String> out) {
        return switch (e) {
            case AssignmentExpr ae -> {
                if (ae.target() instanceof IdentifierExpr id
                        && id.name().equals(name)) {
                    yield knownFunctionValue(ae.value(), out);
                }
                boolean ok = exprAssignedFunctions(ae.value(), name, out);
                if (!ok) yield false;
                // A non-identifier target's sub-expressions (an index
                // operand or a member-access object) are value positions
                // evaluated at the assignment: `t[g = one] = v` hides the
                // same load-time assignment as `{ x: (g = one) }` does.
                yield switch (ae.target()) {
                    case IndexExpr idx -> exprAssignedFunctions(idx.array(),
                        name, out)
                        && exprAssignedFunctions(idx.index(), name, out);
                    case MemberAccessExpr mae ->
                        exprAssignedFunctions(mae.object(), name, out);
                    default -> true;
                };
            }
            case BinaryExpr bin -> exprAssignedFunctions(bin.left(), name, out)
                && exprAssignedFunctions(bin.right(), name, out);
            case UnaryExpr u -> exprAssignedFunctions(u.expr(), name, out);
            case CallExpr call -> {
                // A module-level call executed before the guarded field
                // read is a potential assignment source: the invoked
                // function's body (transitively) may assign the field —
                // LuaJIT executes the call and the assignment at load,
                // so the value set must observe it (an assignment inside
                // the called body, an indirect call whose held function
                // may assign the field, or any function-typed callee
                // whose invoked body cannot be analyzed all make the
                // value set not statically known).
                if (callMayAssignField(call, name)) yield false;
                boolean ok = exprAssignedFunctions(call.callee(), name, out);
                for (ExpressionNode arg : call.args()) {
                    if (!ok) break;
                    ok = exprAssignedFunctions(arg, name, out);
                }
                yield ok;
            }
            case MemberAccessExpr mae -> exprAssignedFunctions(mae.object(), name, out);
            case IndexExpr idx -> exprAssignedFunctions(idx.array(), name, out)
                && exprAssignedFunctions(idx.index(), name, out);
            case ArrayLiteralExpr al -> {
                boolean ok = true;
                for (ExpressionNode elem : al.elements()) {
                    if (!exprAssignedFunctions(elem, name, out)) {
                        ok = false;
                        break;
                    }
                }
                yield ok;
            }
            case ObjectLiteralExpr ol -> {
                boolean ok = true;
                for (Property prop : ol.properties()) {
                    if (!exprAssignedFunctions(prop.value(), name, out)) {
                        ok = false;
                        break;
                    }
                }
                // Class-construction defaults are value positions
                // evaluated at the construction site (mirroring
                // collectExprRefs): an assignment hidden in a default
                // expression executes at load exactly like one hidden in
                // a property value.
                if (ok && typeOf(ol) instanceof Type.Class cls
                        && isLocalClassType(cls)) {
                    ClassDeclaration cd = moduleClasses.get(cls.name());
                    if (cd != null) {
                        for (ClassField cf : cd.fields()) {
                            if (cf.defaultExpr().isPresent()
                                    && !exprAssignedFunctions(
                                        cf.defaultExpr().get(), name, out)) {
                                ok = false;
                                break;
                            }
                        }
                    }
                }
                yield ok;
            }
            case TemplateLiteralExpr tl -> {
                boolean ok = true;
                for (ExpressionNode part : tl.parts()) {
                    if (!exprAssignedFunctions(part, name, out)) {
                        ok = false;
                        break;
                    }
                }
                yield ok;
            }
            default -> true;
        };
    }

    /** True when {@code value} is a bare module-function or intrinsic
     * identifier (collecting the module function's name into {@code out});
     * false for any other value shape. */
    private boolean knownFunctionValue(ExpressionNode value, List<String> out) {
        if (!(value instanceof IdentifierExpr id)) return false;
        Symbol sym = symbols.resolve(id.name());
        if (sym instanceof Symbol.FunctionSymbol
                && moduleFunctions.containsKey(id.name())) {
            out.add(id.name());
            return true;
        }
        if (sym instanceof Symbol.IntrinsicSymbol) {
            return true; // reads no fields, reaches no module functions
        }
        return false;
    }

    /** True when a module-level call of {@code call} executed before the
     * guarded field read may assign {@code name} — a direct call of a
     * module function whose body assigns the field (transitively,
     * {@link #transitiveAssignedFields}); an indirect call through a
     * function-typed module field whose static value superset
     * ({@link #fieldValueSuperset}) contains a function that (transitively)
     * assigns the field, or whose superset is unknown; or any other
     * function-typed callee (a local, parameter, call-result, or
     * member-access expression) whose invoked body cannot be analyzed.
     * Every other callee (intrinsics, non-function values) cannot assign
     * a field. Conservative — only ever adds a rejection. */
    private boolean callMayAssignField(CallExpr call, String name) {
        ExpressionNode callee = call.callee();
        if (callee instanceof IdentifierExpr cid) {
            Symbol csym = symbols.resolve(cid.name());
            if (csym instanceof Symbol.FunctionSymbol
                    && moduleFunctions.containsKey(cid.name())) {
                return transitiveAssignedFields
                    .getOrDefault(cid.name(), Set.of()).contains(name);
            }
            if (csym instanceof Symbol.IntrinsicSymbol) {
                return false; // pure conversion — reads and writes nothing
            }
            if (csym instanceof Symbol.VariableSymbol
                    && moduleFieldIndices.containsKey(cid.name())
                    && typeOf(callee) instanceof Type.Func) {
                Set<String> superset = fieldValueSuperset(cid.name());
                if (superset.contains(UNKNOWN_HELD_VALUE)) return true;
                for (String fn : superset) {
                    if (transitiveAssignedFields.getOrDefault(fn, Set.of())
                            .contains(name)) {
                        return true;
                    }
                }
                return false;
            }
            if (typeOf(callee) instanceof Type.Func) {
                // A local/parameter function-typed binding invoked in the
                // walked statements: its held value is not tracked —
                // conservative.
                return true;
            }
            return false;
        }
        // A call-result or member-access callee with function type: the
        // invoked body cannot be analyzed — conservative.
        return typeOf(callee) instanceof Type.Func;
    }

    /** The static value superset of the function-typed module field
     * {@code name}: every module function it may hold at any load-time
     * read (memoized; see {@link #fieldValueSupersets}). */
    private Set<String> fieldValueSuperset(String name) {
        Set<String> memo = fieldValueSupersets.get(name);
        if (memo != null) return memo;
        Set<String> result = fieldValueSupersetInner(name, new HashSet<>());
        fieldValueSupersets.put(name, result);
        return result;
    }

    private Set<String> fieldValueSupersetInner(String name,
            Set<String> inProgress) {
        if (!inProgress.add(name)) {
            // A cycle in the field-initializer reference graph —
            // impossible for valid programs (module fields only reference
            // earlier fields), but stay conservative.
            return Set.of(UNKNOWN_HELD_VALUE);
        }
        Set<String> result = new LinkedHashSet<>();
        VariableDeclaration vd = moduleFieldDecls.get(name);
        if (vd != null) {
            ExpressionNode init = vd.initializer();
            if (init instanceof IdentifierExpr id) {
                Symbol sym = symbols.resolve(id.name());
                if (sym instanceof Symbol.FunctionSymbol
                        && moduleFunctions.containsKey(id.name())) {
                    result.add(id.name());
                } else if (sym instanceof Symbol.IntrinsicSymbol) {
                    // The intrinsic wrapper holds no module function.
                } else if (sym instanceof Symbol.VariableSymbol
                        && moduleFieldIndices.containsKey(id.name())) {
                    // An equal-signature snapshot or an arity adapter over
                    // another function-valued field: the invoked function
                    // is that field's value, so its superset is included.
                    result.addAll(fieldValueSupersetInner(id.name(),
                        inProgress));
                } else {
                    result.add(UNKNOWN_HELD_VALUE);
                }
            } else {
                result.add(UNKNOWN_HELD_VALUE);
            }
        }
        // EVERY assignment to the field anywhere in the module (module-level
        // statements and every function body — hidden value positions
        // included) adds its bare module-function values; any non-identifier
        // assigned value makes the superset unknown. The scan
        // over-approximates (assignments in bodies that never run at load
        // count too) — used only for conservative rejection.
        boolean unknown = false;
        for (StatementNode stmt : moduleStatements) {
            if (!assignedFieldValues(stmt, name, result)) {
                unknown = true;
                break;
            }
        }
        if (!unknown) {
            for (FunctionDeclaration fd : moduleFunctions.values()) {
                if (!assignedFieldValues(fd.body(), name, result)) {
                    unknown = true;
                    break;
                }
            }
        }
        inProgress.remove(name);
        if (unknown) result.add(UNKNOWN_HELD_VALUE);
        return result;
    }

    /** True when every assignment to the module field {@code name} inside
     * {@code stmt} assigns a bare module-function identifier (collected
     * into {@code out}); false when any assigned value is not statically
     * known. */
    private boolean assignedFieldValues(StatementNode stmt, String name,
            Set<String> out) {
        return switch (stmt) {
            case ExpressionStatement es ->
                assignedFieldValuesExpr(es.expr(), name, out);
            case VariableDeclaration vd ->
                assignedFieldValuesExpr(vd.initializer(), name, out);
            case ReturnStatement rs -> rs.expr().isEmpty()
                || assignedFieldValuesExpr(rs.expr().get(), name, out);
            case IfStatement is -> assignedFieldValuesExpr(is.condition(),
                    name, out)
                && assignedFieldValues(is.thenBlock(), name, out)
                && (is.elseBranch().isEmpty()
                    || assignedFieldValuesBranch(is.elseBranch().get(), name,
                        out));
            case WhileStatement ws -> assignedFieldValuesExpr(ws.condition(),
                    name, out)
                && assignedFieldValues(ws.body(), name, out);
            case Block b -> assignedFieldValuesList(b.statements(), name, out);
            default -> true;
        };
    }

    private boolean assignedFieldValuesBranch(
            Either<IfStatement, Block> branch, String name, Set<String> out) {
        return switch (branch) {
            case Either.Left<IfStatement, Block> left ->
                assignedFieldValues(left.value(), name, out);
            case Either.Right<IfStatement, Block> right ->
                assignedFieldValuesList(right.value().statements(), name, out);
        };
    }

    private boolean assignedFieldValuesList(List<StatementNode> stmts,
            String name, Set<String> out) {
        for (StatementNode stmt : stmts) {
            if (!assignedFieldValues(stmt, name, out)) return false;
        }
        return true;
    }

    private boolean assignedFieldValuesExpr(ExpressionNode e, String name,
            Set<String> out) {
        return switch (e) {
            case AssignmentExpr ae -> {
                boolean ok = assignedFieldValuesExpr(ae.value(), name, out);
                if (!ok) yield false;
                if (ae.target() instanceof IdentifierExpr id
                        && id.name().equals(name)) {
                    if (ae.value() instanceof IdentifierExpr vid) {
                        Symbol sym = symbols.resolve(vid.name());
                        if (sym instanceof Symbol.FunctionSymbol
                                && moduleFunctions.containsKey(vid.name())) {
                            out.add(vid.name());
                            yield ok;
                        }
                        if (sym instanceof Symbol.IntrinsicSymbol) {
                            yield ok; // holds no module function
                        }
                    }
                    yield false; // a non-identifier value — unknown
                }
                yield switch (ae.target()) {
                    case IndexExpr idx ->
                        assignedFieldValuesExpr(idx.array(), name, out)
                            && assignedFieldValuesExpr(idx.index(), name, out);
                    case MemberAccessExpr mae ->
                        assignedFieldValuesExpr(mae.object(), name, out);
                    default -> ok;
                };
            }
            case BinaryExpr bin -> assignedFieldValuesExpr(bin.left(), name, out)
                && assignedFieldValuesExpr(bin.right(), name, out);
            case UnaryExpr u -> assignedFieldValuesExpr(u.expr(), name, out);
            case CallExpr call -> {
                boolean ok = assignedFieldValuesExpr(call.callee(), name, out);
                for (ExpressionNode arg : call.args()) {
                    if (!ok) break;
                    ok = assignedFieldValuesExpr(arg, name, out);
                }
                yield ok;
            }
            case MemberAccessExpr mae ->
                assignedFieldValuesExpr(mae.object(), name, out);
            case IndexExpr idx -> assignedFieldValuesExpr(idx.array(), name, out)
                && assignedFieldValuesExpr(idx.index(), name, out);
            case ArrayLiteralExpr al -> {
                boolean ok = true;
                for (ExpressionNode elem : al.elements()) {
                    if (!assignedFieldValuesExpr(elem, name, out)) {
                        ok = false;
                        break;
                    }
                }
                yield ok;
            }
            case ObjectLiteralExpr ol -> {
                boolean ok = true;
                for (Property prop : ol.properties()) {
                    if (!assignedFieldValuesExpr(prop.value(), name, out)) {
                        ok = false;
                        break;
                    }
                }
                if (ok && typeOf(ol) instanceof Type.Class cls
                        && isLocalClassType(cls)) {
                    ClassDeclaration cd = moduleClasses.get(cls.name());
                    if (cd != null) {
                        for (ClassField cf : cd.fields()) {
                            if (cf.defaultExpr().isPresent()
                                    && !assignedFieldValuesExpr(
                                        cf.defaultExpr().get(), name, out)) {
                                ok = false;
                                break;
                            }
                        }
                    }
                }
                yield ok;
            }
            case TemplateLiteralExpr tl -> {
                boolean ok = true;
                for (ExpressionNode part : tl.parts()) {
                    if (!assignedFieldValuesExpr(part, name, out)) {
                        ok = false;
                        break;
                    }
                }
                yield ok;
            }
            default -> true;
        };
    }

    /**
     * Collects into {@code possible} every module function the
     * function-typed field {@code fieldName} may hold when it is read at
     * load-time statement index {@code readIndex} (the INCLUSIVE end of
     * the assignment walk — the call's own statement is walked, see
     * {@link #moduleLevelAssignedFunctions}): the value set of its
     * initializer at its
     * declaration (see {@link #heldFunctionsAtDeclaration}) plus every
     * statically-known module function assigned to the field between the
     * declaration and {@code readIndex}. Returns true when any value is
     * not statically known (a call result, a non-field identifier, an
     * unknown adapter value, an assignment inside a load-time-called
     * function body, or a sibling indirect call whose field may hold an
     * assigning function) — the caller conservatively rejects the
     * call. The JVM static-initializer interleaving mirrors LuaJIT's
     * load-time execution, so every function collected here is one the
     * field may genuinely hold at the read.
     */
    private boolean collectPossibleHeldFunctions(String fieldName,
            int readIndex, Set<String> inProgress, Set<String> possible) {
        if (!inProgress.add(fieldName)) {
            // A cycle in the field-reference graph. Impossible for valid
            // programs (module fields only reference earlier fields —
            // forward references are rejected), but stay conservative.
            return true;
        }
        boolean unknown = heldFunctionsAtDeclaration(fieldName,
            inProgress, possible);
        List<String> assigned = moduleLevelAssignedFunctions(fieldName,
            readIndex);
        if (assigned == null) {
            unknown = true;
        } else {
            possible.addAll(assigned);
        }
        inProgress.remove(fieldName);
        return unknown;
    }

    /**
     * The value set of {@code fieldName}'s initializer evaluated at the
     * field's declaration position, collected into {@code possible}
     * (true = not statically known). A bare module-function identifier
     * holds that function; an intrinsic identifier holds the intrinsic
     * wrapper (safe — reads no fields, reaches no module functions); an
     * identifier naming another function-valued field reads THAT field
     * at the position the lowering reads it: the outer field's
     * declaration index for an equal-signature initializer (both
     * backends read the field once at initializer time — a snapshot),
     * or the CALL SITE ({@link #currentModuleStatementIndex}) for an
     * arity-extension adapter over the field (LuaJIT's adapter body
     * re-reads the binding on every invoke, and the emitted JVM adapter
     * reads the static field live — so the inner field's assignments
     * before the call site retarget the adapter). Any other initializer
     * shape is unknown.
     */
    private boolean heldFunctionsAtDeclaration(String fieldName,
            Set<String> inProgress, Set<String> possible) {
        VariableDeclaration vd = moduleFieldDecls.get(fieldName);
        if (vd == null || !(vd.initializer() instanceof IdentifierExpr id)) {
            return true;
        }
        Symbol sym = symbols.resolve(id.name());
        if (sym instanceof Symbol.FunctionSymbol
                && moduleFunctions.containsKey(id.name())) {
            possible.add(id.name());
            return false;
        }
        if (sym instanceof Symbol.IntrinsicSymbol) {
            return false;
        }
        if (sym instanceof Symbol.VariableSymbol
                && moduleFieldIndices.containsKey(id.name())) {
            Type declared = declaredFieldType(fieldName);
            int innerReadIndex = isArityAdapter(typeOf(id), declared)
                ? currentModuleStatementIndex
                : moduleFieldIndices.get(fieldName);
            return collectPossibleHeldFunctions(id.name(), innerReadIndex,
                inProgress, possible);
        }
        return true;
    }

    /** The declared type of the module field {@code fieldName}: the
     * checker-refined symbol type (annotated or inferred), falling back
     * to the initializer's static type. */
    private Type declaredFieldType(String fieldName) {
        Symbol sym = symbols.resolve(fieldName);
        if (sym instanceof Symbol.VariableSymbol vs && vs.type() != null) {
            return vs.type();
        }
        VariableDeclaration vd = moduleFieldDecls.get(fieldName);
        return vd != null ? typeOf(vd.initializer()) : Type.Error.INSTANCE;
    }

    /** True when assigning a value of static type {@code actual} to a
     * function-typed position of declared type {@code target} lowers to
     * an arity-extension adapter (the only non-equal shape
     * {@link Types#isAssignable} admits — fewer actual parameters,
     * identical prefix and return types) instead of a plain wrapper
     * read. */
    private static boolean isArityAdapter(Type actual, Type target) {
        if (!(target instanceof Type.Func tf) || !(actual instanceof Type.Func af)) {
            return false;
        }
        return Types.isAssignable(af, tf) && !Types.equals(af, tf);
    }

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
                // Direct callee identifiers are hoisted function names
                // (FunctionSymbol — the IdentifierExpr walk skips them);
                // an INDIRECT callee identifier is a read of a
                // function-typed field and participates in the
                // later-field dominance analysis (ISSUE-0098 slice).
                if (call.callee() instanceof IdentifierExpr) {
                    walkDominanceExpr(call.callee(), locals, written,
                        fnDeclIdx, readViolations, writeViolations);
                }
                for (ExpressionNode arg : call.args()) {
                    walkDominanceExpr(arg, locals, written, fnDeclIdx,
                        readViolations, writeViolations);
                }
            }
            // An await's callee is a direct async call: its argument
            // positions are value reads exactly like any other call's
            // (ISSUE-0099).
            case AwaitExpression aw ->
                walkDominanceExpr(aw.callee(), locals, written, fnDeclIdx,
                    readViolations, writeViolations);
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
        if (int32Mode) {
            // DEAL int: signed 32-bit under DEAL_V1_2_INT32 (ISSUE-0375
            // carrier/range switch, jvm-v12-int32-bytes D1). checkInt
            // narrows any wider/boxed value crossing a declared int
            // boundary: the signed32 gate raises E8004 BEFORE the
            // narrowing, so the (int) inside checkInt is never silent.
            emitLine("// DEAL int: signed 32-bit under DEAL_V1_2_INT32 (jvm-v12-int32-bytes D1).");
            emitLine("// checkInt gates every wider/boxed value crossing a declared int boundary on");
            emitLine("// [-2147483648, 2147483647] (E8004 before the narrowing, never silent).");
            emitLine("static int checkInt(long v) { if (v > 2147483647L || v < -2147483648L) throw new DealError(\"E8004\", \"int out of safe range\"); return (int) v; }");
            emitLine("// int arithmetic: exact int32; E8004 overflow, E8005 division by zero, E8006 negative exponent.");
            emitLine("static int intAdd(int a, int b) { try { return java.lang.Math.addExact(a, b); } catch (java.lang.ArithmeticException e) { throw new DealError(\"E8004\", \"int out of safe range\"); } }");
            emitLine("static int intSub(int a, int b) { try { return java.lang.Math.subtractExact(a, b); } catch (java.lang.ArithmeticException e) { throw new DealError(\"E8004\", \"int out of safe range\"); } }");
            emitLine("static int intMul(int a, int b) { try { return java.lang.Math.multiplyExact(a, b); } catch (java.lang.ArithmeticException e) { throw new DealError(\"E8004\", \"int out of safe range\"); } }");
            // Truncating division/remainder: -2147483648 / -1 overflows the
            // signed32 range (E8004 via the pre-check); the truncated
            // remainder -2147483648 % -1 is the in-range 0 — the
            // spec-v1.2 remainder rule, identical to the retained LuaJIT
            // math.modf reference shape (ISSUE-0394).
            emitLine("static int intDiv(int a, int b) { if (b == 0) throw new DealError(\"E8005\", \"integer division by zero\"); if (a == java.lang.Integer.MIN_VALUE && b == -1) throw new DealError(\"E8004\", \"int out of safe range\"); return a / b; }");
            emitLine("static int intMod(int a, int b) { if (b == 0) throw new DealError(\"E8005\", \"integer division by zero\"); return a % b; }");
            // NaN/Infinity first (deal/runtime.lua check_int parity); only
            // finite values outside the signed32 range report E8004.
            emitLine("static int intPow(int a, int b) { if (b < 0) throw new DealError(\"E8006\", \"integer exponent must be non-negative\"); double p = java.lang.Math.pow((double) a, (double) b); if (java.lang.Double.isNaN(p)) throw new DealError(\"E8001\", \"expected int, got NaN\"); if (java.lang.Double.isInfinite(p)) throw new DealError(\"E8001\", \"expected int, got infinity\"); if (p > 2147483647.0 || p < -2147483648.0) throw new DealError(\"E8004\", \"int out of safe range\"); return (int) p; }");
            emitLine("static int intNeg(int a) { try { return java.lang.Math.negateExact(a); } catch (java.lang.ArithmeticException e) { throw new DealError(\"E8004\", \"int out of safe range\"); } }");
            emitLine("// number %: Lua-style floored modulo (a - floor(a/b)*b), unlike Java's truncated %.");
            emitLine("static double numMod(double a, double b) { return a - java.lang.Math.floor(a / b) * b; }");
            emitLine("// number **: IEEE-754 pow with the pinned v1.2 special-case table");
            emitLine("// (SharedValueSemantics.numberPow): pow(1.0, NaN) = 1.0 and");
            emitLine("// pow(±1.0, ±Infinity) = 1.0 — the two Java-vs-IEEE deviations — corrected before Math.pow.");
            emitLine("static double numPow(double a, double b) { if (a == 1.0 && java.lang.Double.isNaN(b)) return 1.0; if (java.lang.Math.abs(a) == 1.0 && java.lang.Double.isInfinite(b)) return 1.0; return java.lang.Math.pow(a, b); }");
            emitLine("// int(v) / number(v) conversion intrinsics (E8001 bad value, E8004 out of range).");
            emitLine("static int intFromNumber(double v) { if (java.lang.Double.isNaN(v)) throw new DealError(\"E8001\", \"expected int, got NaN\"); if (java.lang.Double.isInfinite(v)) throw new DealError(\"E8001\", \"expected int, got infinity\"); if (v != java.lang.Math.floor(v)) throw new DealError(\"E8001\", \"expected int, got non-integer number\"); if (v > 2147483647.0 || v < -2147483648.0) throw new DealError(\"E8004\", \"int out of safe range\"); return (int) v; }");
            emitLine("static double numberFromInt(int v) { return (double) v; }");
        } else {
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
        }
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
        emitLine(int32Mode
            ? "            if (v instanceof java.lang.Integer i) return checkInt(i);"
            : "            if (v instanceof java.lang.Long l) return checkInt(l.longValue());");
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
        emitLine("// Minimal DEAL table: the shared $DealRt.Table class (emitted by the");
        emitLine("// entry module's artifact — every module references the same class, so");
        emitLine("// table values cross module boundaries with shared identity). Property");
        emitLine("// values box: DEAL ints are Long, numbers Double, booleans Boolean,");
        emitLine("// strings String, classes the generated class instances, nested tables");
        emitLine("// $DealRt.Table.");
        emitLine("// ISSUE-0102 table field writes: $tPut stores the value and");
        emitLine("// returns it (generic, so an expression-position write keeps");
        emitLine("// its DEAL value); $tRemove deletes a dynamic key. DEAL table");
        emitLine("// keys are strings; the call site's key expression is");
        emitLine("// string-typed, so the cast is a defensive static proof.");
        emitLine("static <V> V $tPut($DealRt.Table t, java.lang.String k, V v) { t.put(k, v); return v; }");
        emitLine("static void $tRemove($DealRt.Table t, java.lang.Object k) { t.remove((java.lang.String) k); }");
        emitLine("static __StringArray __tableKeys($DealRt.Table t) { return new __StringArray(t.keys()); }");
        emitLine("// ISSUE-0102 Error support: unwrap the DEAL code of any");
        emitLine("// RuntimeException whose class is an emitted module's nested");
        emitLine("// DealError (each module declares its own, so no shared type");
        emitLine("// exists). Mirrors the runner's reportError unwrap.");
        emitLine("static java.lang.String __errorCode(java.lang.Object e) {");
        emitLine("    if (e == null || !\"DealError\".equals(e.getClass().getSimpleName())) return null;");
        emitLine("    try {");
        emitLine("        java.lang.reflect.Field f = e.getClass().getDeclaredField(\"code\");");
        emitLine("        f.setAccessible(true);");
        emitLine("        return java.lang.String.valueOf(f.get(e));");
        emitLine("    } catch (java.lang.ReflectiveOperationException ex) { return null; }");
        emitLine("}");
        emitLine("// Human-readable DEAL identity of a dynamic value for E8001 messages");
        emitLine("// (mirrors LuaJIT's actual_class reporting in check_type).");
        emitLine("static java.lang.String $describe(java.lang.Object v) {");
        emitLine("    if (v instanceof $Base b) return b.$identity;");
        emitLine("    if (v == null) return \"null\";");
        emitLine("    if (v instanceof $DealRt.Table) return \"table\";");
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
        emitLine("// Function-signature check helper (E8010): wrapper descriptors are");
        emitLine("// compared through this method so javac never proves the checking");
        emitLine("// wrapper's initializer throw constant (JLS requires an instance");
        emitLine("// initializer to be able to complete normally).");
        emitLine("static boolean checkSig(java.lang.String expected, java.lang.String actual) { return expected.equals(actual); }");
        emitLine("// ISSUE-0102: every function-value wrapper implements $FnValue so");
        emitLine("// the shared seam's function-descriptor branch can validate a");
        emitLine("// dynamically read function value against its runtime");
        emitLine("// descriptor.");
        emitLine("interface $FnValue { java.lang.String descriptor(); }");
        emitLine();
        // ---- DEAL JVM function values (ISSUE-0098 slice) ----
        // The int / number conversion intrinsics are first-class function
        // values, wrapped exactly like the Lua backend's runtime function
        // wrappers for the same seeded signatures (number)->int and
        // (int)->number. Direct intrinsic calls still route to the inline
        // intFromNumber / numberFromInt helpers; value uses (assignment,
        // callback arguments, arity adapters) flow through these wrappers.
        registerWrapperShape(new Type.Func(List.of(Type.Number.INSTANCE),
            Type.Int.INSTANCE));
        registerWrapperShape(new Type.Func(List.of(Type.Int.INSTANCE),
            Type.Number.INSTANCE));
        emitLine("static final Fn1_N_R_I _int$fn = new Fn1_N_R_I() {");
        emitLine("    @Override");
        emitLine(int32Mode
            ? "    int invoke(double p0) { return intFromNumber(p0); }"
            : "    long invoke(double p0) { return intFromNumber(p0); }");
        emitLine("};");
        emitLine("static final Fn1_I_R_N _number$fn = new Fn1_I_R_N() {");
        emitLine("    @Override");
        emitLine(int32Mode
            ? "    double invoke(int p0) { return numberFromInt(p0); }"
            : "    double invoke(long p0) { return numberFromInt(p0); }");
        emitLine("};");
        emitLine();
        emitLine("// ---- DEAL primitive array runtime support (ISSUE-0094) ----");
        emitLine("// int[]/number[]/string[]/boolean[] map to mutable wrapper classes — the");
        emitLine("// spec's specialized primitive array wrapper (spec-v1.2 §JVM value mapping).");
        emitLine("// The wrapper identity is stable across appends (writing at i == length grows");
        emitLine("// the wrapped storage in place), so aliases observe every write exactly like");
        emitLine("// LuaJIT's shared 1-based table. Every name here uses the __ prefix, which is");
        emitLine("// unreachable from javaName's translation (each DEAL underscore escapes to");
        emitLine("// $u), so no user binding, method, or field can collide with it.");
        emitLine(int32Mode
            ? "static final class __IntArray { int[] data; __IntArray(int[] data) { this.data = data; } }"
            : "static final class __IntArray { long[] data; __IntArray(long[] data) { this.data = data; } }");
        emitLine("static final class __NumberArray { double[] data; __NumberArray(double[] data) { this.data = data; } }");
        emitLine("static final class __StringArray { java.lang.String[] data; __StringArray(java.lang.String[] data) { this.data = data; } }");
        emitLine("static final class __BooleanArray { boolean[] data; __BooleanArray(boolean[] data) { this.data = data; } }");
        emitLine("// array reads: a negative index is E8002 (LuaJIT's emitted negative-index");
        emitLine("// check); an index past the end is E8001 \"expected <T>, got null\" — LuaJIT");
        emitLine("// reads nil there and the read site's typed boundary fails with exactly that");
        emitLine("// shape (spec §Bounds and nil behavior: `let x: int = xs[99]` → nil is not");
        emitLine("// int). A primitive Java array cannot yield nil, so the JVM read raises the");
        emitLine("// boundary failure directly.");
        emitLine(int32Mode
            ? "static int __intArrayRead(__IntArray a, long i) { if (i < 0L) throw new DealError(\"E8002\", \"negative array index\"); if (i >= (long) a.data.length) throw new DealError(\"E8001\", \"expected int, got null\"); return a.data[(int) i]; }"
            : "static long __intArrayRead(__IntArray a, long i) { if (i < 0L) throw new DealError(\"E8002\", \"negative array index\"); if (i >= (long) a.data.length) throw new DealError(\"E8001\", \"expected int, got null\"); return a.data[(int) i]; }");
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
        emitLine(int32Mode
            ? "static java.lang.Integer __intArrayReadBoxed(__IntArray a, long i) { if (i < 0L) throw new DealError(\"E8002\", \"negative array index\"); if (i >= (long) a.data.length) return null; return java.lang.Integer.valueOf(a.data[(int) i]); }"
            : "static java.lang.Long __intArrayReadBoxed(__IntArray a, long i) { if (i < 0L) throw new DealError(\"E8002\", \"negative array index\"); if (i >= (long) a.data.length) return null; return java.lang.Long.valueOf(a.data[(int) i]); }");
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
        emitLine(int32Mode
            ? "static int __intArrayWrite(__IntArray a, long i, int v) { if (i < 0L || i > (long) a.data.length) throw new DealError(\"E8002\", \"array index out of bounds\"); if (i == (long) a.data.length) { int[] nd = new int[a.data.length + 1]; java.lang.System.arraycopy(a.data, 0, nd, 0, a.data.length); nd[nd.length - 1] = v; a.data = nd; } else { a.data[(int) i] = v; } return v; }"
            : "static long __intArrayWrite(__IntArray a, long i, long v) { if (i < 0L || i > (long) a.data.length) throw new DealError(\"E8002\", \"array index out of bounds\"); v = checkInt(v); if (i == (long) a.data.length) { long[] nd = new long[a.data.length + 1]; java.lang.System.arraycopy(a.data, 0, nd, 0, a.data.length); nd[nd.length - 1] = v; a.data = nd; } else { a.data[(int) i] = v; } return v; }");
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
        emitLine(int32Mode
            ? "static final class __IntOrNullArray { java.lang.Integer[] data; __IntOrNullArray(java.lang.Integer[] data) { this.data = data; } }"
            : "static final class __IntOrNullArray { java.lang.Long[] data; __IntOrNullArray(java.lang.Long[] data) { this.data = data; } }");
        emitLine("static final class __NumberOrNullArray { java.lang.Double[] data; __NumberOrNullArray(java.lang.Double[] data) { this.data = data; } }");
        emitLine("static final class __StringOrNullArray { java.lang.String[] data; __StringOrNullArray(java.lang.String[] data) { this.data = data; } }");
        emitLine("static final class __BooleanOrNullArray { java.lang.Boolean[] data; __BooleanOrNullArray(java.lang.Boolean[] data) { this.data = data; } }");
        emitLine(int32Mode
            ? "static java.lang.Integer __intOrNullArrayWrite(__IntOrNullArray a, long i, java.lang.Integer v) { if (i < 0L || i > (long) a.data.length) throw new DealError(\"E8002\", \"array index out of bounds\"); if (i == (long) a.data.length) { java.lang.Integer[] nd = new java.lang.Integer[a.data.length + 1]; java.lang.System.arraycopy(a.data, 0, nd, 0, a.data.length); nd[nd.length - 1] = v; a.data = nd; } else { a.data[(int) i] = v; } return v; }"
            : "static java.lang.Long __intOrNullArrayWrite(__IntOrNullArray a, long i, java.lang.Long v) { if (i < 0L || i > (long) a.data.length) throw new DealError(\"E8002\", \"array index out of bounds\"); if (i == (long) a.data.length) { java.lang.Long[] nd = new java.lang.Long[a.data.length + 1]; java.lang.System.arraycopy(a.data, 0, nd, 0, a.data.length); nd[nd.length - 1] = v; a.data = nd; } else { a.data[(int) i] = v; } return v; }");
        emitLine("static java.lang.Double __numberOrNullArrayWrite(__NumberOrNullArray a, long i, java.lang.Double v) { if (i < 0L || i > (long) a.data.length) throw new DealError(\"E8002\", \"array index out of bounds\"); if (i == (long) a.data.length) { java.lang.Double[] nd = new java.lang.Double[a.data.length + 1]; java.lang.System.arraycopy(a.data, 0, nd, 0, a.data.length); nd[nd.length - 1] = v; a.data = nd; } else { a.data[(int) i] = v; } return v; }");
        emitLine("static java.lang.String __stringOrNullArrayWrite(__StringOrNullArray a, long i, java.lang.String v) { if (i < 0L || i > (long) a.data.length) throw new DealError(\"E8002\", \"array index out of bounds\"); if (i == (long) a.data.length) { java.lang.String[] nd = new java.lang.String[a.data.length + 1]; java.lang.System.arraycopy(a.data, 0, nd, 0, a.data.length); nd[nd.length - 1] = v; a.data = nd; } else { a.data[(int) i] = v; } return v; }");
        emitLine("static java.lang.Boolean __booleanOrNullArrayWrite(__BooleanOrNullArray a, long i, java.lang.Boolean v) { if (i < 0L || i > (long) a.data.length) throw new DealError(\"E8002\", \"array index out of bounds\"); if (i == (long) a.data.length) { java.lang.Boolean[] nd = new java.lang.Boolean[a.data.length + 1]; java.lang.System.arraycopy(a.data, 0, nd, 0, a.data.length); nd[nd.length - 1] = v; a.data = nd; } else { a.data[(int) i] = v; } return v; }");
        emitLine("// (T | null)[] reads: null past the end (the LuaJIT nil — a valid");
        emitLine("// nullable element, so NO E8001 at the read) and the stored boxed");
        emitLine("// element otherwise; a negative index still raises E8002 (LuaJIT");
        emitLine("// emits that check unconditionally at the read).");
        emitLine(int32Mode
            ? "static java.lang.Integer __intOrNullArrayRead(__IntOrNullArray a, long i) { if (i < 0L) throw new DealError(\"E8002\", \"negative array index\"); if (i >= (long) a.data.length) return null; return a.data[(int) i]; }"
            : "static java.lang.Long __intOrNullArrayRead(__IntOrNullArray a, long i) { if (i < 0L) throw new DealError(\"E8002\", \"negative array index\"); if (i >= (long) a.data.length) return null; return a.data[(int) i]; }");
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
        emitLine(int32Mode
            ? "static int intFromNullable(java.lang.Integer v) { if (v == null) throw new DealError(\"E8001\", \"cannot convert null to int\"); return v; }"
            : "static long intFromNullable(java.lang.Long v) { if (v == null) throw new DealError(\"E8001\", \"cannot convert null to int\"); return v; }");
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
        emitLine(int32Mode
            ? "static int __strLength(java.lang.String s) { return s.codePointCount(0, s.length()); }"
            : "static long __strLength(java.lang.String s) { return (long) s.codePointCount(0, s.length()); }");
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

    /** Emits the shared DEAL table runtime class (ISSUE-0102) — one
     * top-level package-private class per compiled project, declared by
     * the entry module's artifact. The ordered string-key map is the
     * JVM form of the Lua backend's table. */
    private void emitSharedTableClass() {
        emitLine("// Shared DEAL table runtime class (ISSUE-0102): one per compiled");
        emitLine("// project, declared by the entry module's artifact. Every module");
        emitLine("// references the SAME class, so table values cross module");
        emitLine("// boundaries with shared identity — exactly like LuaJIT's");
        emitLine("// single table type. The $ in the name is unreachable from");
        emitLine("// classNameFor (sanitizeSegment maps $ to _).");
        emitLine("class $DealRt {");
        emitLine("    static final class Table {");
        emitLine("        private final java.util.LinkedHashMap<java.lang.String, java.lang.Object> entries = new java.util.LinkedHashMap<>();");
        emitLine("        // Array-mode support (the @jsonable slice): non-null when the");
        emitLine("        // table is array-shaped (a 1-based element sequence) — the");
        emitLine("        // slice maps nested JSON arrays to array-mode tables so");
        emitLine("        // string-keyed reads and re-serialization keep the JSON");
        emitLine("        // array shape (DEAL cannot spell integer keys).");
        emitLine("        private final java.util.ArrayList<java.lang.Object> array;");
        emitLine("        Table() { this.array = null; }");
        emitLine("        Table(java.util.ArrayList<java.lang.Object> array) { this.array = array; }");
        emitLine("        Table put(java.lang.String k, java.lang.Object v) { entries.put(k, v); return this; }");
        emitLine("        java.lang.Object get(java.lang.String k) { return entries.get(k); }");
        emitLine("        java.lang.Object remove(java.lang.String k) { return entries.remove(k); }");
        emitLine("        boolean has(java.lang.String k) { return entries.containsKey(k); }");
        emitLine("        java.lang.String[] keys() { return entries.keySet().toArray(new java.lang.String[0]); }");
        emitLine("        java.util.ArrayList<java.lang.Object> $array() { return array; }");
        emitLine("        java.util.LinkedHashMap<java.lang.String, java.lang.Object> $entries() { return entries; }");
        emitLine("    }");
        emitLine("}");
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
        emitLine("if (descriptor.equals(\"table\")) { if (v instanceof $DealRt.Table t) return t; throw new DealError(\"E8001\", \"expected table, got \" + $describe(v)); }");
        emitLine("if (descriptor.equals(\"boolean\")) { if (v instanceof java.lang.Boolean b) return b; throw new DealError(\"E8001\", \"expected boolean, got \" + $describe(v)); }");
        emitLine("if (descriptor.equals(\"string\")) { if (v instanceof java.lang.String s) { if (__hasUnpairedSurrogate(s)) throw new DealError(\"E8001\", \"expected string, got string with unpaired surrogate code units\"); return s; } throw new DealError(\"E8001\", \"expected string, got \" + $describe(v)); }");
        emitLine(int32Mode
            ? "if (descriptor.equals(\"int\")) { if (v instanceof java.lang.Integer i) return checkInt(i); if (v instanceof java.lang.Double d) { if (d.isNaN()) throw new DealError(\"E8001\", \"expected int, got NaN\"); if (d.isInfinite()) throw new DealError(\"E8001\", \"expected int, got infinity\"); if (d % 1.0 != 0.0) throw new DealError(\"E8001\", \"expected int, got non-integer number\"); return checkInt((long) (double) d); } throw new DealError(\"E8001\", \"expected int, got \" + $describe(v)); }"
            : "if (descriptor.equals(\"int\")) { if (v instanceof java.lang.Long l) return checkInt(l); if (v instanceof java.lang.Double d) { if (d.isNaN()) throw new DealError(\"E8001\", \"expected int, got NaN\"); if (d.isInfinite()) throw new DealError(\"E8001\", \"expected int, got infinity\"); if (d % 1.0 != 0.0) throw new DealError(\"E8001\", \"expected int, got non-integer number\"); return checkInt((long) (double) d); } throw new DealError(\"E8001\", \"expected int, got \" + $describe(v)); }");
        emitLine(int32Mode
            ? "if (descriptor.equals(\"number\")) { if (v instanceof java.lang.Integer i) return (double) i; if (v instanceof java.lang.Double d) return d; throw new DealError(\"E8001\", \"expected number, got \" + $describe(v)); }"
            : "if (descriptor.equals(\"number\")) { if (v instanceof java.lang.Long l) return (double) l; if (v instanceof java.lang.Double d) return d; throw new DealError(\"E8001\", \"expected number, got \" + $describe(v)); }");
        emitLine("if (descriptor.equals(\"[int]\")) { if (v instanceof __IntArray a) return a; throw new DealError(\"E8001\", \"expected array, got \" + $describe(v)); }");
        emitLine("if (descriptor.equals(\"[number]\")) { if (v instanceof __NumberArray a) return a; throw new DealError(\"E8001\", \"expected array, got \" + $describe(v)); }");
        emitLine("if (descriptor.equals(\"[string]\")) { if (v instanceof __StringArray a) return a; throw new DealError(\"E8001\", \"expected array, got \" + $describe(v)); }");
        emitLine("if (descriptor.equals(\"[boolean]\")) { if (v instanceof __BooleanArray a) return a; throw new DealError(\"E8001\", \"expected array, got \" + $describe(v)); }");
        emitLine(int32Mode
            ? "if (descriptor.equals(\"[?int]\")) { if (v instanceof __IntOrNullArray a) return a; if (v instanceof __IntArray a) { java.lang.Integer[] nd = new java.lang.Integer[a.data.length]; for (int i = 0; i < a.data.length; i++) nd[i] = java.lang.Integer.valueOf(a.data[i]); return new __IntOrNullArray(nd); } throw new DealError(\"E8001\", \"expected array, got \" + $describe(v)); }"
            : "if (descriptor.equals(\"[?int]\")) { if (v instanceof __IntOrNullArray a) return a; if (v instanceof __IntArray a) { java.lang.Long[] nd = new java.lang.Long[a.data.length]; for (int i = 0; i < a.data.length; i++) nd[i] = java.lang.Long.valueOf(a.data[i]); return new __IntOrNullArray(nd); } throw new DealError(\"E8001\", \"expected array, got \" + $describe(v)); }");
        emitLine("if (descriptor.equals(\"[?number]\")) { if (v instanceof __NumberOrNullArray a) return a; if (v instanceof __NumberArray a) { java.lang.Double[] nd = new java.lang.Double[a.data.length]; for (int i = 0; i < a.data.length; i++) nd[i] = java.lang.Double.valueOf(a.data[i]); return new __NumberOrNullArray(nd); } throw new DealError(\"E8001\", \"expected array, got \" + $describe(v)); }");
        emitLine("if (descriptor.equals(\"[?string]\")) { if (v instanceof __StringOrNullArray a) return a; if (v instanceof __StringArray a) { java.lang.String[] nd = new java.lang.String[a.data.length]; java.lang.System.arraycopy(a.data, 0, nd, 0, a.data.length); return new __StringOrNullArray(nd); } throw new DealError(\"E8001\", \"expected array, got \" + $describe(v)); }");
        emitLine("if (descriptor.equals(\"[?boolean]\")) { if (v instanceof __BooleanOrNullArray a) return a; if (v instanceof __BooleanArray a) { java.lang.Boolean[] nd = new java.lang.Boolean[a.data.length]; for (int i = 0; i < a.data.length; i++) nd[i] = java.lang.Boolean.valueOf(a.data[i]); return new __BooleanOrNullArray(nd); } throw new DealError(\"E8001\", \"expected array, got \" + $describe(v)); }");
        emitLine("if (descriptor.startsWith(\"(\") || descriptor.startsWith(\"async(\")) {");
        indent++;
        emitLine("if (v instanceof $FnValue f && f.descriptor().equals(descriptor)) return v;");
        emitLine("throw new DealError(\"E8001\", \"expected \" + descriptor + \", got \" + $describe(v));");
        indent--;
        emitLine("}");
        for (String branch : classCheckBranches) {
            emitLine(branch);
        }
        emitLine("throw new DealError(\"E8001\", \"expected \" + descriptor + \", got \" + $describe(v));");
        indent--;
        emitLine("}");
    }

    // =========================================================================
    // Function-value wrappers (ISSUE-0098 slice)
    // =========================================================================

    /**
     * Registers the wrapper class for a function signature and returns its
     * Java class name (e.g. {@code Fn2_II_R_I} for
     * {@code (int,int)->int}). One abstract class per distinct signature
     * shape; the class carries the spec-convention runtime descriptor
     * string (the same text the Lua backend stores in its runtime
     * function wrappers — observable only at the host boundary, deferred
     * to ISSUE-0110) and an {@code invoke} method with the JVM-mapped
     * signature. The class text is accumulated and spliced into the class
     * body right after the runtime support.
     */
    private String registerWrapperShape(Type.Func f) {
        String name = fnShapeName(f);
        if (name == null) return null; // deferred shape; caller records E6000
        if (emittedWrapperShapes.add(name)) {
            StringBuilder body = new StringBuilder();
            body.append("// DEAL function-value wrapper for descriptor ")
                .append(quoteJavaString(fnDescriptor(f))).append("\n");
            body.append("static abstract class ").append(name)
                .append(" implements $FnValue {\n");
            body.append("    final java.lang.String descriptor = ")
                .append(quoteJavaString(fnDescriptor(f))).append(";\n");
            body.append("    public java.lang.String descriptor() { return descriptor; }\n");
            body.append("    abstract ").append(javaReturnType(f.returnType(), null))
                .append(" invoke(");
            for (int i = 0; i < f.paramTypes().size(); i++) {
                if (i > 0) body.append(", ");
                body.append(javaLocalType(f.paramTypes().get(i), null))
                    .append(" p").append(i);
            }
            body.append(");\n");
            body.append("}\n");
            // The class text is spliced into the class body at indent 1;
            // prefix every line with the class-body indent.
            for (String line : body.toString().split("\n", -1)) {
                if (line.isEmpty()) continue;
                wrapperClasses.append("    ").append(line).append('\n');
            }
        }
        return name;
    }

    /** The wrapper class name for a signature, or {@code null} when the
     * signature contains a deferred type (array/class/nullable/nested
     * function — the emitter records E6000 for those elsewhere). */
    private static String fnShapeName(Type.Func f) {
        StringBuilder sb = new StringBuilder("Fn").append(f.paramTypes().size());
        if (f.paramTypes().isEmpty()) {
            sb.append("_R_");
        } else {
            sb.append('_');
            for (Type p : f.paramTypes()) {
                String seg = fnShapeSegment(p);
                if (seg == null) return null;
                sb.append(seg);
            }
            sb.append("_R_");
        }
        String rc = fnShapeSegment(f.returnType());
        if (rc == null) return null;
        sb.append(rc);
        return sb.toString();
    }

    /** One signature-shape segment: the single-letter code for a
     * primitive, or the nested function shape's full wrapper name for a
     * function type (ISSUE-0102 — nested function types now emit). */
    private static String fnShapeSegment(Type t) {
        Character c = fnShapeLetter(t);
        if (c != null) return c.toString();
        if (t instanceof Type.Func nested) {
            String nestedName = fnShapeName(nested);
            if (nestedName == null) return null;
            return "F" + nestedName;
        }
        return null;
    }

    /** The one-letter signature-shape code ({@code B/I/N/S/V} for
     * boolean/int/number/string/null; {@code null} for deferred types). */
    private static Character fnShapeLetter(Type t) {
        return switch (t) {
            case Type.Boolean ignored -> 'B';
            case Type.Int ignored -> 'I';
            case Type.Number ignored -> 'N';
            case Type.String ignored -> 'S';
            case Type.Null ignored -> 'V';
            case Type.Bytes ignored -> null;
            default -> null;
        };
    }

    /** The spec-convention runtime descriptor ({@code (int,int)->int}) —
     * the same text the Lua backend stores in its runtime function
     * wrappers. Only primitive/string/null signatures reach this point,
     * so no escaping beyond the descriptor grammar is needed. */
    private static String fnDescriptor(Type.Func f) {
        StringBuilder sb = new StringBuilder();
        if (f.isAsync()) sb.append("async");
        sb.append("(");
        for (int i = 0; i < f.paramTypes().size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(singleTypeDescriptor(f.paramTypes().get(i)));
        }
        sb.append(")->").append(singleTypeDescriptor(f.returnType()));
        return sb.toString();
    }

    /** One segment of a function signature descriptor. */
    private static String singleTypeDescriptor(Type t) {
        return switch (t) {
            case Type.Int ignored -> "int";
            case Type.Number ignored -> "number";
            case Type.Boolean ignored -> "boolean";
            case Type.String ignored -> "string";
            case Type.Null ignored -> "null";
            case Type.Bytes ignored -> "bytes";
            case Type.Func nested -> fnDescriptor(nested);
            default -> "?";
        };
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
            case ForStatement fs -> emitFor(fs);
            case ForOfStatement fos -> emitForOf(fos);
            case BreakStatement bs -> emitBreak(bs);
            case ContinueStatement cs -> emitContinue(cs);
            case DeleteStatement ds -> emitDelete(ds);
            case TryStatement ts -> emitTry(ts);
            case ThrowStatement th -> emitThrow(th);
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
            case Type.Int ignored ->
                int32Mode ? "java.lang.Integer" : "java.lang.Long";
            case Type.Number ignored -> "java.lang.Double";
            case Type.Boolean ignored -> "java.lang.Boolean";
            case Type.String ignored -> "java.lang.String";
            case Type.Bytes ignored -> "java.lang.Object";
            case Type.Nullable n -> switch (n.inner()) {
                case Type.Int ignored ->
                    int32Mode ? "java.lang.Integer" : "java.lang.Long";
                case Type.Number ignored -> "java.lang.Double";
                case Type.Boolean ignored -> "java.lang.Boolean";
                case Type.String ignored -> "java.lang.String";
                case Type.Bytes ignored -> "java.lang.Object";
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
            case Type.Bytes ignored -> false;
            default -> false;
        };
    }

    /** Java parameter type for a host function parameter of the given
     * declared DEAL type; {@code null} when unsupported. */
    private String hostParamJavaType(Type t) {
        return switch (t) {
            case Type.Int ignored -> int32Mode ? "int" : "long";
            case Type.Number ignored -> "double";
            case Type.Boolean ignored -> "boolean";
            case Type.String ignored -> "java.lang.String";
            case Type.Bytes ignored -> null;
            case Type.Nullable n -> switch (n.inner()) {
                case Type.Int ignored ->
                    int32Mode ? "java.lang.Integer" : "java.lang.Long";
                case Type.Number ignored -> "java.lang.Double";
                case Type.Boolean ignored -> "java.lang.Boolean";
                case Type.String ignored -> "java.lang.String";
                case Type.Bytes ignored -> null;
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
            case Type.Int ignored -> int32Mode ? "int.class" : "long.class";
            case Type.Number ignored -> "double.class";
            case Type.Boolean ignored -> "boolean.class";
            case Type.String ignored -> "java.lang.String.class";
            case Type.Bytes ignored -> "java.lang.Object.class";
            case Type.Nullable n -> switch (n.inner()) {
                case Type.Int ignored ->
                    int32Mode ? "java.lang.Integer.class" : "java.lang.Long.class";
                case Type.Number ignored -> "java.lang.Double.class";
                case Type.Boolean ignored -> "java.lang.Boolean.class";
                case Type.String ignored -> "java.lang.String.class";
                case Type.Bytes ignored -> "java.lang.Object.class";
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
            case Type.Bytes ignored -> hostParamJavaType(t);
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
            case Type.Bytes ignored -> "bytes";
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
            case Type.Bytes ignored -> null;
            case Type.Nullable ne -> switch (ne.inner()) {
                case Type.Int ignored -> "?int";
                case Type.Number ignored -> "?number";
                case Type.String ignored -> "?string";
                case Type.Boolean ignored -> "?boolean";
                case Type.Class c -> "?" + classCheckDescriptor(c);
                case Type.Bytes ignored -> null;
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

    /** Synthetic-anchor variant of
     * {@link #importedClassModuleRef(Type.Class, Span)} (D6): same note
     * rule as {@link #jsonClassRefSynthetic(Type.Class)}. */
    private String importedClassModuleRefSynthetic(Type.Class cls) {
        String module = cls.modulePath();
        Map<String, ClassDeclaration> decls = importedClasses.get(module);
        if (!importAliases.containsValue(module)
                || decls == null || !decls.containsKey(cls.name())) {
            unsupportedSynthetic("values of imported class type '" + cls.name()
                + "' from module '" + module + "' (the module is not an "
                + "imported compiled project module of this module, or "
                + "its class declaration is unavailable)",
                "missing anchor: class declaration span for class '"
                    + cls.name() + "'");
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
    /**
     * ISSUE-0102: the emitted wrapper class name for an array of an
     * IMPORTED class — the declaring module's per-class array wrapper
     * ({@code Lib.$Array$C}), or {@code null} when the class is not an
     * imported compiled project module's class (the caller records the
     * E6000).
     */
    private String importedArrayWrapperName(Type.Class c) {
        String module = c.modulePath();
        Map<String, ClassDeclaration> decls = importedClasses.get(module);
        if (!importAliases.containsValue(module)
                || decls == null || !decls.containsKey(c.name())) {
            return null;
        }
        return classNameFor(module) + "." + classArrayWrapperName(c.name());
    }

    /**
     * The emitted read/write helper reference for a class array element
     * ({@code read} → {@code $read$C}, {@code readOrNull} →
     * {@code $readOrNull$C}, {@code write} → {@code $write$C},
     * {@code writeOrNull} → {@code $writeOrNull$C}), qualified by the
     * declaring module's class for an imported class (ISSUE-0109).
     */
    private String classArrayHelper(Type.Class cls, String kind, Span span) {
        if (isLocalClassType(cls)) {
            if (localClassJavaType(cls, span) == null) return null;
            return switch (kind) {
                case "read" -> classArrayReadName(cls.name());
                case "readOrNull" -> classOrNullArrayReadName(cls.name());
                case "write" -> classArrayWriteName(cls.name());
                default -> classOrNullArrayWriteName(cls.name());
            };
        }
        String moduleRef = importedClassModuleRef(cls, span);
        if (moduleRef == null) return null;
        return moduleRef + "." + switch (kind) {
            case "read" -> classArrayReadName(cls.name());
            case "readOrNull" -> classOrNullArrayReadName(cls.name());
            case "write" -> classArrayWriteName(cls.name());
            default -> classOrNullArrayWriteName(cls.name());
        };
    }

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
     * generated nested static class carrying the declared fields plus a
     * runtime nominal-check helper. Spec v1.1 classes are sealed
     * records with no methods and no constructors — the only callables in
     * the module are the functions the earlier slices emit, so a class body
     * contributes no method surface. Required-present primitive
     * fields ({@code int}/{@code number}/{@code boolean}/{@code string}
     * with defaults) and required-present nullable primitive/class
     * fields ({@code f: T | null}, defaulting to the DEAL null) are in
     * scope; optional fields (nullable reads and presence checks), and
     * array/class/table-typed (non-nullable) fields are E6000 — except
     * on an {@code @jsonable} class, where the JSON serialization slice
     * additionally supports optional fields (Missing-sentinel storage
     * with {@code has()} presence checks), table fields (JSON-object
     * data as {@code $DealRt.Table} values, including array-mode tables for nested
     * JSON arrays), array, nested-class (local or imported), and
     * {@code null}-typed fields, and emits the per-class
     * {@code $jsonFields} descriptor plus the {@code $toJsonValue}/
     * {@code $fromJsonValue} helpers and the public
     * {@code C$fromJson}/{@code C$toJson} exports.
     */
    private void emitClass(ClassDeclaration cd) {
        boolean jsonable = cd.isJsonable();
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
        List<Boolean> fieldOptionalFlags = new ArrayList<>();
        List<Type> fieldResolved = new ArrayList<>();
        for (ClassField cf : cd.fields()) {
            Type fieldType = resolveTypeNode(cf.type());
            if (fieldType == Type.Error.INSTANCE) return;
            if (jsonable) {
                // @jsonable field support (the JSON serialization
                // slice): the prior-slice value types plus their array,
                // nested class, table, and nullable forms. resolveTypeNode
                // and javaArrayElementType already gate the array element
                // support (nested arrays, function arrays, imported-class
                // element arrays stay E6000). An OPTIONAL field stores its
                // boxed value (or the DEAL null) in a java.lang.Object
                // slot guarded by the module's Missing sentinel
                // ($MISSING) — the spec's Missing-sentinel
                // representation, which keeps the three states of
                // {@code f?: T | null} distinguishable.
                if (!isJvmJsonableFieldType(fieldType)) {
                    unsupported("@jsonable class fields of type "
                        + typeName(fieldType) + " (the JVM slice supports "
                        + "null, boolean, int, number, string, table, "
                        + "array, class, and nullable fields)", cf.span());
                    return;
                }
                if (fieldType instanceof Type.Table) {
                    if (cf.optional()) {
                        // An OPTIONAL table field is rejected with an
                        // honest E6000: its read yields `table | null`,
                        // a value shape the slice keeps out of its typed
                        // positions (the ISSUE-0108 boundary, pinned by
                        // the nullable-table gate). The pre-fix emission
                        // stored the field as the table class and later
                        // passed the Missing sentinel into that slot,
                        // leaving an artifact javac rejected ('Object
                        // cannot be converted to the table class') after
                        // the CLI reported success — never that.
                        unsupported("optional table fields of @jsonable class '"
                            + cd.name() + "' (the read of '" + cf.name()
                            + "' yields `table | null`, which stays out of "
                            + "the slice's typed positions)", cf.span());
                        return;
                    }
                    // A plain table field stores the shared
                    // $DealRt.Table reference and maps JSON objects to
                    // string-keyed table data. The nullable table forms
                    // (`table | null`, optional or not) fall through to
                    // the general E6000 gates below: a `table | null`
                    // VALUE cannot flow through the slice's typed
                    // positions (the ISSUE-0108 boundary).
                    fieldTypes.add("$DealRt.Table");
                    fieldNames.add(javaName(cf.name()));
                    fieldResolved.add(fieldType);
                    fieldOptionalFlags.add(false);
                    continue;
                }
                if (cf.optional()) {
                    Type inner = fieldType instanceof Type.Nullable nn
                        ? nn.inner() : fieldType;
                    if (nullableJavaType(inner, cf.span()) == null) return;
                    fieldTypes.add("java.lang.Object");
                    fieldNames.add(javaName(cf.name()));
                    fieldResolved.add(fieldType);
                    fieldOptionalFlags.add(false);
                    continue;
                }
            } else if (cf.optional()) {
                // Optional field (ISSUE-0102): reads produce the
                // declared type | null (the checker wraps it), the Java
                // representation is the boxed/nullable reference of the
                // inner type plus a <name>$present flag for has().
                // Inner types: the four primitives (boxed), string,
                // local classes, arrays, and tables.
                Type inner = fieldType;
                if (fieldType instanceof Type.Nullable nn) {
                    inner = nn.inner();
                }
                String javaType = switch (inner) {
                    case Type.Int ignored ->
                        int32Mode ? "java.lang.Integer" : "java.lang.Long";
                    case Type.Number ignored -> "java.lang.Double";
                    case Type.Boolean ignored -> "java.lang.Boolean";
                    case Type.String ignored -> "java.lang.String";
                    case Type.Bytes ignored -> javaLocalType(inner, cf.span());
                    default -> javaLocalType(inner, cf.span());
                };
                if (javaType == null) return;
                fieldTypes.add(javaType);
                fieldNames.add(javaName(cf.name()));
                fieldOptionalFlags.add(true);
                fieldResolved.add(fieldType);
                continue;
            } else if (cf.nullable()) {
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
                    && !(fieldType instanceof Type.String)
                    && !(fieldType instanceof Type.Table)
                    && !(fieldType instanceof Type.Array)
                    && !(fieldType instanceof Type.Class cls
                        && isLocalClassType(cls)
                        && !isBuiltinErrorType(cls))) {
                unsupported("class fields of type " + typeName(fieldType)
                    + " (only primitive fields, nullable primitive/"
                    + "class fields, and local array/table/class fields"
                    + " are supported)", cf.span());
                return;
            }
            String javaType = javaLocalType(fieldType, cf.span());
            if (javaType == null) return;
            fieldTypes.add(javaType);
            fieldNames.add(javaName(cf.name()));
            fieldOptionalFlags.add(false);
            fieldResolved.add(fieldType);
        }

        emitLine("// DEAL class " + cd.name() + " — identity " + identity);
        emitLine("static final class " + gen + " extends $Base {");
        indent++;
        for (int i = 0; i < fieldNames.size(); i++) {
            emitLine(fieldTypes.get(i) + " " + fieldNames.get(i) + ";");
            if (fieldOptionalFlags.get(i)) {
                // Presence flag for has()/delete (ISSUE-0102). The $ in
                // the name is unreachable from javaName (DEAL identifiers
                // cannot contain $), so no user field can collide.
                emitLine("boolean " + fieldNames.get(i) + "$present;");
            }
        }
        StringBuilder params = new StringBuilder();
        for (int i = 0; i < fieldNames.size(); i++) {
            if (i > 0) params.append(", ");
            params.append(fieldTypes.get(i)).append(' ')
                .append(fieldNames.get(i));
            if (fieldOptionalFlags.get(i)) {
                params.append(", boolean ").append(fieldNames.get(i))
                    .append("$present");
            }
        }
        emitLine(gen + "(" + params + ") {");
        indent++;
        emitLine("super(" + quoteJavaString(identity) + ");");
        for (int i = 0; i < fieldNames.size(); i++) {
            emitLine("this." + fieldNames.get(i) + " = " + fieldNames.get(i)
                + ";");
            if (fieldOptionalFlags.get(i)) {
                emitLine("this." + fieldNames.get(i) + "$present = "
                    + fieldNames.get(i) + "$present;");
            }
        }
        indent--;
        emitLine("}");
        if (jsonable) {
            emitJsonableFieldDescriptor(cd, fieldResolved);
            emitJsonableToJsonValue(cd, gen, fieldResolved);
            emitJsonableFromJsonValue(cd, gen, fieldTypes, fieldResolved);
        }
        indent--;
        emitLine("}");

        // ISSUE-0102: per-optional-field write helpers — the assignment
        // keeps its DEAL value in value positions and always sets the
        // presence flag.
        for (int i = 0; i < fieldNames.size(); i++) {
            if (fieldOptionalFlags.get(i)) {
                emitLine("static <V> V __optSet$" + gen + "$"
                    + fieldNames.get(i) + "(" + gen + " obj, V v) {");
                indent++;
                emitLine("obj." + fieldNames.get(i) + " = ("
                    + fieldTypes.get(i) + ") v;");
                emitLine("obj." + fieldNames.get(i) + "$present = true;");
                emitLine("return v;");
                indent--;
                emitLine("}");
            }
        }

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
        if (jsonable) {
            emitJsonablePublicHelpers(cd, gen);
        }
    }

    // =========================================================================
    // @jsonable class helpers (JSON serialization slice)
    // =========================================================================

    /**
     * True when a resolved field type of an @jsonable class is supported by
     * the JVM slice: the prior-slice value types ({@code null}, {@code
     * boolean}, {@code int}, {@code number}, {@code string}, class) plus
     * their nullable, array, and nested-class forms. Array ELEMENT support
     * (nested arrays, function arrays, imported-class element arrays) was
     * already gated by {@code resolveTypeNode} → {@code
     * javaArrayElementType}; table-typed fields stay out of slice (their
     * JSON-object mapping and untyped JSON-shaped table data are a later
     * slice).
     */
    private boolean isJvmJsonableFieldType(Type t) {
        return switch (t) {
            case Type.Null ignored -> true;
            case Type.Boolean ignored -> true;
            case Type.Int ignored -> true;
            case Type.Number ignored -> true;
            case Type.String ignored -> true;
            case Type.Table ignored -> true;
            case Type.Class ignored -> true;
            case Type.Nullable n -> isJvmJsonableFieldType(n.inner());
            case Type.Array a -> isJvmJsonableFieldType(a.element());
            case Type.Bytes ignored -> false;
            default -> false; // function
        };
    }

    /** The jtype descriptor string of a resolved jsonable field type
     * (the same vocabulary as the Lua backend's {@code C_fields}
     * descriptor tables — {@code jsonable-v1.1}). */
    private String jsonFieldJType(Type t) {
        return switch (t) {
            case Type.Null ignored -> "null";
            case Type.Boolean ignored -> "boolean";
            case Type.Int ignored -> "int";
            case Type.Number ignored -> "number";
            case Type.String ignored -> "string";
            case Type.Nullable n -> jsonFieldJType(n.inner());
            case Type.Array ignored -> "array";
            case Type.Table ignored -> "table";
            case Type.Class ignored -> "class";
            case Type.Bytes ignored -> "unknown";
            default -> "unknown";
        };
    }

    /** The emitted generated-class reference of a resolved class type
     * ({@code $C_<C>} for a local class, {@code Lib.$C_<C>} for an
     * imported one) — the same reference {@link #javaLocalType} emits. */
    private String jsonClassRef(Type.Class c, Span span) {
        if (isLocalClassType(c)) {
            if (!moduleClasses.containsKey(c.name())) {
                unsupported("values of class type '" + c.name()
                    + "' (only local module-level classes are supported)",
                    span);
                return null;
            }
            return classNameForClass(c.name());
        }
        String importedModule = importedClassModuleRef(c, span);
        if (importedModule == null) return null;
        return importedModule + "." + classNameForClass(c.name());
    }

    /** Synthetic-anchor variant of
     * {@link #jsonClassRef(Type.Class, Span)} (D6): the jsonable
     * toJson/fromJson/boxed-type conversion sites hold only the converted
     * class, no source span, so the E6000 rejection records through the
     * explicit synthetic factory with a note naming the class. */
    private String jsonClassRefSynthetic(Type.Class c) {
        if (isLocalClassType(c)) {
            if (!moduleClasses.containsKey(c.name())) {
                unsupportedSynthetic("values of class type '" + c.name()
                    + "' (only local module-level classes are supported)",
                    "missing anchor: class declaration span for class '"
                        + c.name() + "'");
                return null;
            }
            return classNameForClass(c.name());
        }
        String importedModule = importedClassModuleRefSynthetic(c);
        if (importedModule == null) return null;
        return importedModule + "." + classNameForClass(c.name());
    }

    /** The Missing-sentinel reference for a class whose generated helpers
     * this module emits — always this module's {@code $MISSING}
     * (imported-class construction of optional fields stays E6000, so no
     * reachable site needs the declaring module's sentinel spelled
     * cross-module). */
    private String jsonableMissingRef(ClassDeclaration cd) {
        return "$MISSING";
    }

    /** The ClassDeclaration behind a class type — a local module-level
     * class or an imported compiled-module class — or {@code null} when
     * the class is unknown (checker-gated unreachable). The jsonable
     * optional-field sites (has()/reads/writes/delete) branch on the
     * result: @jsonable classes use Missing-sentinel storage, every
     * other class uses the ISSUE-0102 boxed slot plus the presence
     * flag. */
    private ClassDeclaration classDeclFor(Type.Class cls) {
        if (isLocalClassType(cls)) {
            return moduleClasses.get(cls.name());
        }
        Map<String, ClassDeclaration> decls =
            importedClasses.get(cls.modulePath());
        return decls == null ? null : decls.get(cls.name());
    }

    /** The declared field of a class type — local module-level classes
     * plus imported compiled-module classes — or {@code null} when the
     * class or field is unknown (checker-gated unreachable). */
    private ClassField backendClassField(Type.Class cls, String fieldName) {
        ClassDeclaration cd;
        if (isLocalClassType(cls)) {
            cd = moduleClasses.get(cls.name());
        } else {
            Map<String, ClassDeclaration> decls =
                importedClasses.get(cls.modulePath());
            cd = decls == null ? null : decls.get(cls.name());
        }
        if (cd == null) return null;
        for (ClassField cf : cd.fields()) {
            if (cf.name().equals(fieldName)) return cf;
        }
        return null;
    }

    /** Emits the per-class {@code $jsonFields} descriptor table
     * ({name, jtype, optional, nullable} rows) inside the generated nested
     * class. The descriptors drive {@code $fromJsonValue}'s extra-key
     * validation (a key is accepted only when a descriptor names it). */
    private void emitJsonableFieldDescriptor(ClassDeclaration cd,
                                             List<Type> types) {
        emitLine("// @jsonable field descriptors: {name, jtype, optional, nullable}.");
        emitLine("static final java.lang.String[][] $jsonFields = new java.lang.String[][] {");
        indent++;
        for (int i = 0; i < types.size(); i++) {
            ClassField cf = cd.fields().get(i);
            Type t = types.get(i);
            String comma = i < types.size() - 1 ? "," : "";
            emitLine("new java.lang.String[]{" + quoteJavaString(cf.name())
                + ", " + quoteJavaString(jsonFieldJType(t))
                + ", " + (cf.optional() ? "\"true\"" : "\"false\"")
                + ", " + (cf.nullable() ? "\"true\"" : "\"false\"")
                + "}" + comma);
        }
        indent--;
        emitLine("};");
    }

    /** Emits the per-class {@code $toJsonValue} field serializer inside
     * the generated nested class: a LinkedHashMap of declared-field JSON
     * values in declaration order (optional fields are out of slice, so
     * nothing is ever omitted), preserving the DEAL null for nullable
     * fields. */
    private void emitJsonableToJsonValue(ClassDeclaration cd, String gen,
                                         List<Type> types) {
        emitLine("// @jsonable toJson field serialization (declared-field order).");
        emitLine("static java.util.LinkedHashMap<java.lang.String, java.lang.Object> $toJsonValue("
            + gen + " v) {");
        indent++;
        emitLine("java.util.LinkedHashMap<java.lang.String, java.lang.Object> out = new java.util.LinkedHashMap<>();");
        for (int i = 0; i < types.size(); i++) {
            ClassField cf = cd.fields().get(i);
            emitToJsonField(cf, types.get(i), "v." + javaName(cf.name()), i);
        }
        emitLine("return out;");
        indent--;
        emitLine("}");
    }

    /** Emits the serialization lines of one declared field. An optional
     * field's {@code valueCode} is its Object storage reference: the
     * Missing sentinel skips the key entirely (absent state), the DEAL
     * null serializes as JSON null (present-null state), and any other
     * value serializes per the inner type. */
    private void emitToJsonField(ClassField cf, Type t, String valueCode,
                                 int idx) {
        String name = quoteJavaString(cf.name());
        boolean optional = cf.optional();
        if (optional) {
            emitLine("if (" + valueCode + " != $MISSING) {");
            indent++;
        }
        switch (t) {
            case Type.Int ignored ->
                emitLine(optional
                    ? "out.put(" + name + ", " + valueCode + ");"
                    : "out.put(" + name + ", "
                        + (int32Mode ? "java.lang.Integer" : "java.lang.Long")
                        + ".valueOf(" + valueCode + "));");
            case Type.Number ignored ->
                emitLine(optional
                    ? "out.put(" + name + ", " + valueCode + ");"
                    : "out.put(" + name + ", java.lang.Double.valueOf(" + valueCode + "));");
            case Type.Boolean ignored ->
                emitLine(optional
                    ? "out.put(" + name + ", " + valueCode + ");"
                    : "out.put(" + name + ", java.lang.Boolean.valueOf(" + valueCode + "));");
            case Type.String ignored ->
                emitLine("out.put(" + name + ", " + valueCode + ");");
            case Type.Null ignored ->
                emitLine("out.put(" + name + ", null);");
            case Type.Table ignored ->
                emitLine("out.put(" + name + ", " + valueCode + ");");
            case Type.Class c -> {
                String ref = jsonClassRef(c, cf.span());
                if (optional) {
                    emitLine("out.put(" + name + ", " + valueCode
                        + " == null ? null : " + ref + ".$toJsonValue(("
                        + ref + ") " + valueCode + "));");
                } else {
                    emitLine("out.put(" + name + ", " + ref + ".$toJsonValue("
                        + valueCode + "));");
                }
            }
            case Type.Nullable nn -> {
                if (nn.inner() instanceof Type.Array a) {
                    emitToJsonArrayField(cf.name(),
                        optional ? castJsonValueCode(valueCode, a) : valueCode,
                        a.element(), idx, true);
                } else if (nn.inner() instanceof Type.Class c) {
                    String ref = jsonClassRef(c, cf.span());
                    emitLine("out.put(" + name + ", " + valueCode
                        + " == null ? null : " + ref + ".$toJsonValue(("
                        + ref + ") " + valueCode + "));");
                } else {
                    // Boxed primitive / string / table reference: put
                    // directly (a table value encodes through
                    // __jsonAppend's $DealRt.Table branch).
                    emitLine("out.put(" + name + ", " + valueCode + ");");
                }
            }
            case Type.Array a ->
                emitToJsonArrayField(cf.name(),
                    optional ? castJsonValueCode(valueCode, a) : valueCode,
                    a.element(), idx, false);
            // bytes is not jsonable (the E4007 checker rule is a later
            // slice); mirror the default's unsupported-value handling.
            case Type.Bytes ignored ->
                emitLine("out.put(" + name + ", null);");
            default ->
                emitLine("out.put(" + name + ", null);");
        }
        if (optional) {
            indent--;
            emitLine("}");
        }
    }

    /** The array-field value code for an OPTIONAL field: the Object
     * storage slot cast to the emitted array wrapper reference (the
     * present value is never the DEAL null for the non-nullable array
     * form; the nullable-outer form null-checks before iterating). */
    private String castJsonValueCode(String valueCode, Type.Array a) {
        return "((" + arrayWrapperName(a.element()) + ") " + valueCode + ")";
    }

    /** Emits the array-field serialization lines: converts the emitted
     * wrapper's storage array into a JSON List of boxed/nested values.
     * {@code nullableOuter} handles a {@code T[] | null} field (the DEAL
     * null serializes as JSON null). */
    private void emitToJsonArrayField(String fieldName, String valueCode,
                                      Type elem, int idx,
                                      boolean nullableOuter) {
        String arrVar = "arr" + idx;
        String eVar = "e" + idx;
        String iterType = jsonArrayIterType(elem);
        emitLine("java.util.ArrayList<java.lang.Object> " + arrVar
            + (nullableOuter ? " = null;" : " = new java.util.ArrayList<>();"));
        if (nullableOuter) {
            emitLine("if (" + valueCode + " != null) {");
            indent++;
            emitLine(arrVar + " = new java.util.ArrayList<>();");
        }
        emitLine("for (" + iterType + " " + eVar + " : " + valueCode
            + ".data) {");
        indent++;
        emitToJsonArrayElement(elem, arrVar, eVar, idx);
        indent--;
        emitLine("}");
        if (nullableOuter) {
            indent--;
            emitLine("}");
        }
        emitLine("out.put(" + quoteJavaString(fieldName) + ", " + arrVar + ");");
    }

    /** Java iteration type of an array wrapper's {@code .data} storage. */
    private String jsonArrayIterType(Type elem) {
        return switch (elem) {
            case Type.Int ignored -> int32Mode ? "int" : "long";
            case Type.Number ignored -> "double";
            case Type.Boolean ignored -> "boolean";
            case Type.String ignored -> "java.lang.String";
            case Type.Nullable ne -> switch (ne.inner()) {
                case Type.Int ignored ->
                    int32Mode ? "java.lang.Integer" : "java.lang.Long";
                case Type.Number ignored -> "java.lang.Double";
                case Type.Boolean ignored -> "java.lang.Boolean";
                case Type.String ignored -> "java.lang.String";
                case Type.Bytes ignored -> "java.lang.Object";
                default -> "java.lang.Object";
            };
            case Type.Class ignored -> "java.lang.Object";
            case Type.Bytes ignored -> "java.lang.Object";
            default -> "java.lang.Object";
        };
    }

    /** Emits the one-element JSON conversion inside a toJson array loop. */
    private void emitToJsonArrayElement(Type elem, String arrVar,
                                        String eVar, int idx) {
        switch (elem) {
            case Type.Int ignored ->
                emitLine(arrVar + ".add("
                    + (int32Mode ? "java.lang.Integer" : "java.lang.Long")
                    + ".valueOf(" + eVar + "));");
            case Type.Number ignored ->
                emitLine(arrVar + ".add(java.lang.Double.valueOf(" + eVar + "));");
            case Type.Boolean ignored ->
                emitLine(arrVar + ".add(java.lang.Boolean.valueOf(" + eVar + "));");
            case Type.String ignored ->
                emitLine(arrVar + ".add(" + eVar + ");");
            case Type.Nullable ne -> {
                if (ne.inner() instanceof Type.Class c) {
                    String ref = jsonClassRefSynthetic(c);
                    emitLine(arrVar + ".add(" + eVar + " == null ? null : "
                        + ref + ".$toJsonValue((" + ref + ") " + eVar + "));");
                } else {
                    emitLine(arrVar + ".add(" + eVar + ");");
                }
            }
            case Type.Class c -> {
                String ref = jsonClassRefSynthetic(c);
                emitLine(arrVar + ".add(" + ref + ".$toJsonValue((" + ref
                    + ") " + eVar + "));");
            }
            case Type.Bytes ignored -> emitLine(arrVar + ".add(null);");
            default -> emitLine(arrVar + ".add(null);");
        }
    }

    /** Emits the per-class {@code $fromJsonValue} validator inside the
     * generated nested class: validates the parsed JSON object against the
     * {@code $jsonFields} descriptors, applies defaults (evaluated inline
     * per call — the same per-construction default freshness the
     * constructor path implements), and returns the DEAL null on ANY
     * validation failure. The table-value depth guard
     * ({@link #JSON_TABLE_DEPTH_LIMIT}) and the nested-class recursion
     * exhaustion propagate to the PUBLIC {@code C$fromJson} wrapper,
     * which converts both shapes to the DEAL null — the public
     * never-throw contract holds, the internal helper stays a plain
     * validator. */
    private void emitJsonableFromJsonValue(ClassDeclaration cd, String gen,
                                           List<String> fieldTypes,
                                           List<Type> types) {
        emitLine("// @jsonable fromJson validation (declared-field order; null on any");
        emitLine("// validation failure — the public C$fromJson wrapper converts");
        emitLine("// the depth-guard and stack-exhaustion shapes to the DEAL");
        emitLine("// null too, so the export never throws).");
        emitLine("static " + gen + " $fromJsonValue(java.lang.Object raw) {");
        indent++;
        emitLine("if (!(raw instanceof java.util.Map<?, ?> m)) return null;");
        emitLine("for (java.util.Map.Entry<?, ?> e : m.entrySet()) {");
        indent++;
        emitLine("java.lang.String key = java.lang.String.valueOf(e.getKey());");
        emitLine("boolean known = false;");
        emitLine("for (java.lang.String[] f : $jsonFields) { if (key.equals(f[0])) { known = true; break; } }");
        emitLine("if (!known) return null;");
        indent--;
        emitLine("}");
        // Defaults first, in declared-field order (LuaJIT's defaults-then-
        // overlay model); each default evaluates inline at the call. An
        // optional field starts ABSENT ($MISSING) — or present with its
        // inline-evaluated default when one is declared (LuaJIT keeps the
        // defaults-table entry for optional-with-default fields, exactly
        // like construction).
        for (int i = 0; i < types.size(); i++) {
            ClassField cf = cd.fields().get(i);
            String defaultCode;
            if (cf.optional() && cf.defaultExpr().isEmpty()) {
                defaultCode = jsonableMissingRef(cd);
            } else if (cf.defaultExpr().isPresent()) {
                ExpressionNode def = cf.defaultExpr().get();
                if (typeOf(def) == Type.Error.INSTANCE) {
                    // The checker records every default subexpression's
                    // type; Type.Error here means the frontend reported
                    // errors — record an honest E6000, never emit an
                    // artifact javac would reject.
                    unsupported("default expression of field '" + cf.name()
                        + "' of class '" + cd.name()
                        + "' (unresolved default-expression type)",
                        def.span());
                    defaultCode = zeroValueFor(cf.type());
                } else {
                    defaultCode = emitExpressionFor(def,
                        classFieldDeclaredType(cd, cf));
                }
                Type declaredType = classFieldDeclaredType(cd, cf);
                if (declaredType != null
                        && needsBooleanBoundary(def, declaredType)) {
                    defaultCode = "booleanNotNull(" + defaultCode + ")";
                }
                String fieldJava = declaredType == null ? null
                    : javaLocalType(declaredType, cf.span());
                defaultCode = coerceNullValueCode(defaultCode, def,
                    fieldJava, def.span());
                // ISSUE-0375 D3 seam: the default expression is a
                // declared int boundary — a wider (time) or boxed
                // (host) int value crosses through the signed32
                // checkInt before the typed field slot (int,
                // java.lang.Integer, or the Object optional slot),
                // so javac never sees a narrowing mismatch and an
                // out-of-range value raises exactly E8004.
                defaultCode = adaptIntBoundary(def, defaultCode,
                    declaredType);
            } else {
                // Required field with NO declared default: the reference
                // defaults table (LuaBackend.defaultValueForTypeNode)
                // applies the per-type zeroes — 0 / 0.0 / false / "" for
                // the primitives (zeroValueFor), a FRESH empty $DealRt.Table for a
                // table field, and a FRESH empty wrapper for an array
                // field (the spec's per-construction freshness). A Java
                // null in those slots would let the DEAL null cross a
                // non-nullable table/array boundary and crash the first
                // read with a raw NPE (the reviewed defect). A required
                // CLASS-typed field's {} placeholder has no Java value at
                // the typed slot; its absent key is a fromJson validation
                // failure (the guard right after this loop — the null
                // placeholder below stays unreachable past it).
                Type ft = types.get(i);
                if (ft instanceof Type.Table) {
                    defaultCode = "new $DealRt.Table()";
                } else if (ft instanceof Type.Array a) {
                    defaultCode = "new " + arrayWrapperName(a.element())
                        + "(new " + jsonArrayStorageType(a.element()) + "[0])";
                } else {
                    defaultCode = zeroValueFor(cf.type());
                }
            }
            if (!preStatements.isEmpty()) flushPreStatements();
            emitLine(fieldTypes.get(i) + " f" + i + " = " + defaultCode + ";");
        }
        flushPreStatements(); // defensive: empty at a statement boundary
        // Required class-typed fields with NO declared default: the
        // reference defaults table holds a raw {} placeholder (a plain
        // Lua table, never a class instance) which the typed Java field
        // slot cannot represent — Java null there would silently cross
        // the non-nullable class boundary (the reviewed defect: a raw
        // NPE or a silent null read where LuaJIT's check_type raises
        // E8001 at the typed read). A present key overlays a validated
        // nested instance below, so only the ABSENT key fails: a fromJson
        // validation failure (the DEAL null, the spec's never-throw
        // contract). The guard evaluates after every default so
        // default-expression side effects keep LuaJIT's
        // defaults-then-overlay order.
        for (int i = 0; i < types.size(); i++) {
            ClassField cf = cd.fields().get(i);
            if (!cf.optional() && cf.defaultExpr().isEmpty()
                    && types.get(i) instanceof Type.Class) {
                emitLine("if (!m.containsKey(" + quoteJavaString(cf.name())
                    + ")) return null;");
            }
        }
        // Overlay the present keys with validated values.
        for (int i = 0; i < types.size(); i++) {
            ClassField cf = cd.fields().get(i);
            emitFromJsonOverlay(cf, types.get(i), "f" + i, i);
        }
        // Optional fields whose overlay stayed absent keep $MISSING —
        // their three states (absent / present null / present value) are
        // exactly the storage states the constructor receives.
        StringBuilder args = new StringBuilder("new " + gen + "(");
        for (int i = 0; i < types.size(); i++) {
            if (i > 0) args.append(", ");
            args.append("f").append(i);
        }
        emitLine("return " + args + ");");
        indent--;
        emitLine("}");
    }

    /** Emits the present-key overlay lines of one field: validates the raw
     * JSON value and assigns the converted value (or returns null). */
    private void emitFromJsonOverlay(ClassField cf, Type t,
                                     String targetVar, int idx) {
        emitLine("if (m.containsKey(" + quoteJavaString(cf.name()) + ")) {");
        indent++;
        emitLine("java.lang.Object fv" + idx + " = m.get("
            + quoteJavaString(cf.name()) + ");");
        if (t instanceof Type.Null) {
            emitLine("if (fv" + idx + " != null) return null;");
        } else if (t instanceof Type.Nullable nn) {
            emitLine("if (fv" + idx + " != null) {");
            indent++;
            emitJsonConvert(nn.inner(), "fv" + idx, targetVar, idx, true);
            indent--;
            emitLine("} else {");
            indent++;
            emitLine(targetVar + " = null;");
            indent--;
            emitLine("}");
        } else {
            emitJsonConvert(t, "fv" + idx, targetVar, idx, false);
        }
        indent--;
        emitLine("}");
    }

    /** Emits the validation + assignment lines converting a raw JSON value
     * ({@code rawVar}) into {@code targetVar} (a declared field local or an
     * array slot). {@code boxed} selects the boxed reference assignment for
     * nullable fields (primitives stay boxed there). Every conversion
     * returns null from {@code $fromJsonValue} on a type mismatch. */
    private void emitJsonConvert(Type t, String rawVar, String targetVar,
                                 int idx, boolean boxed) {
        switch (t) {
            case Type.Int ignored -> {
                emitLine((int32Mode ? "java.lang.Integer" : "java.lang.Long")
                    + " cv" + idx + " = __jsonInt(" + rawVar + ");");
                emitLine("if (cv" + idx + " == null) return null;");
                emitLine(targetVar + " = " + (boxed ? "cv" + idx
                    : "cv" + idx + (int32Mode ? ".intValue()" : ".longValue()"))
                    + ";");
            }
            case Type.Number ignored -> {
                emitLine("java.lang.Double cv" + idx + " = __jsonNumber(" + rawVar + ");");
                emitLine("if (cv" + idx + " == null) return null;");
                emitLine(targetVar + " = " + (boxed ? "cv" + idx
                    : "cv" + idx + ".doubleValue()") + ";");
            }
            case Type.Boolean ignored -> {
                emitLine("java.lang.Boolean cv" + idx + " = __jsonBoolean(" + rawVar + ");");
                emitLine("if (cv" + idx + " == null) return null;");
                emitLine(targetVar + " = " + (boxed ? "cv" + idx
                    : "cv" + idx + ".booleanValue()") + ";");
            }
            case Type.String ignored -> {
                emitLine("java.lang.String cv" + idx + " = __jsonString(" + rawVar + ");");
                emitLine("if (cv" + idx + " == null) return null;");
                emitLine(targetVar + " = cv" + idx + ";");
            }
            case Type.Table ignored -> {
                // The spec's table-field contract: fromJson accepts ONLY
                // a JSON object and maps it to a DEAL table holding
                // untyped JSON-shaped data (nested objects as string-keyed
                // $DealRt.Table tables, nested arrays as array-mode $DealRt.Table tables,
                // leaves as-is). Any other JSON value is a validation
                // failure.
                emitLine("if (!(" + rawVar + " instanceof java.util.Map<?, ?> tm" + idx + ")) return null;");
                emitLine(targetVar + " = ($DealRt.Table) __jsonTableValue(" + rawVar + ", 0);");
            }
            case Type.Class c -> {
                String ref = jsonClassRefSynthetic(c);
                emitLine(ref + " cv" + idx + " = " + ref
                    + ".$fromJsonValue(" + rawVar + ");");
                emitLine("if (cv" + idx + " == null) return null;");
                emitLine(targetVar + " = cv" + idx + ";");
            }
            case Type.Array a -> {
                String storage = jsonArrayStorageType(a.element());
                emitLine("if (!(" + rawVar + " instanceof java.util.List<?> l" + idx + ")) return null;");
                emitLine(storage + "[] a" + idx + " = new " + storage
                    + "[l" + idx + ".size()];");
                emitLine("int i" + idx + " = 0;");
                emitLine("for (java.lang.Object e" + idx + " : l" + idx + ") {");
                indent++;
                emitJsonArrayElementConvert(a.element(), idx);
                indent--;
                emitLine("}");
                emitLine(targetVar + " = new " + arrayWrapperName(a.element())
                    + "(a" + idx + ");");
            }
            case Type.Bytes ignored -> {
                // bytes is not jsonable (the E4007 checker rule is a
                // later slice); fail through the jsonable conversion's
                // existing unsupported-shape handling.
                unsupportedSynthetic("@jsonable value conversion of type "
                    + typeName(t),
                    "missing anchor: source span for @jsonable value "
                        + "conversion of type '" + typeName(t) + "'");
                emitLine(targetVar + " = null;");
            }
            default -> {
                unsupportedSynthetic("@jsonable value conversion of type "
                    + typeName(t),
                    "missing anchor: source span for @jsonable value "
                        + "conversion of type '" + typeName(t) + "'");
                emitLine(targetVar + " = null;");
            }
        }
    }

    /** Java storage type of a jsonable array's element array. */
    private String jsonArrayStorageType(Type elem) {
        return switch (elem) {
            case Type.Int ignored -> int32Mode ? "int" : "long";
            case Type.Number ignored -> "double";
            case Type.Boolean ignored -> "boolean";
            case Type.String ignored -> "java.lang.String";
            case Type.Nullable ne -> switch (ne.inner()) {
                case Type.Int ignored ->
                    int32Mode ? "java.lang.Integer" : "java.lang.Long";
                case Type.Number ignored -> "java.lang.Double";
                case Type.Boolean ignored -> "java.lang.Boolean";
                case Type.String ignored -> "java.lang.String";
                case Type.Bytes ignored -> "java.lang.Object";
                default -> "java.lang.Object";
            };
            case Type.Class ignored -> "java.lang.Object";
            case Type.Bytes ignored -> "java.lang.Object";
            default -> "java.lang.Object";
        };
    }

    /** Emits the per-element conversion of a jsonable array (storage
     * {@code a<idx>}, index {@code i<idx>}, element {@code e<idx>}). */
    private void emitJsonArrayElementConvert(Type elem, int idx) {
        if (elem instanceof Type.Nullable nn) {
            emitLine(jsonBoxedType(nn.inner(), idx) + " el" + idx + " = null;");
            emitLine("if (e" + idx + " != null) {");
            indent++;
            emitJsonConvert(nn.inner(), "e" + idx, "el" + idx, idx, true);
            indent--;
            emitLine("}");
            emitLine("a" + idx + "[i" + idx + "++] = el" + idx + ";");
            return;
        }
        emitJsonConvert(elem, "e" + idx, "a" + idx + "[i" + idx + "++]",
            idx, false);
    }

    /** Java boxed type of a nullable-element array element local. */
    private String jsonBoxedType(Type inner, int idx) {
        return switch (inner) {
            case Type.Int ignored ->
                int32Mode ? "java.lang.Integer" : "java.lang.Long";
            case Type.Number ignored -> "java.lang.Double";
            case Type.Boolean ignored -> "java.lang.Boolean";
            case Type.String ignored -> "java.lang.String";
            case Type.Class c -> {
                String ref = jsonClassRefSynthetic(c);
                yield ref == null ? "java.lang.Object" : ref;
            }
            case Type.Bytes ignored -> "java.lang.Object";
            default -> "java.lang.Object";
        };
    }

    /** Emits the module-level public {@code C$fromJson}/{@code C$toJson}
     * exports (spec §JSON serialization). The Java names are the
     * {@link #javaName} translations of the DEAL names — exactly what
     * {@link #emitCall} and {@link #emitMemberAccessCall} emit for
     * {@code C$fromJson(...)} / {@code Lib.C$fromJson(...)} call sites —
     * so same-module and cross-module calls bind to these methods. */
    private void emitJsonablePublicHelpers(ClassDeclaration cd, String gen) {
        String fromJson = javaName(cd.name() + "$fromJson");
        String toJson = javaName(cd.name() + "$toJson");
        emitLine("// @jsonable generated exports (spec §JSON serialization):");
        emitLine("// " + cd.name() + "$fromJson(s) — parse + validate; the DEAL null on");
        emitLine("// any parse/validation failure, never a throw. The wrapper");
        emitLine("// converts the two exhaustion shapes to the DEAL null:");
        emitLine("// the table-value depth guard's RuntimeException (past "
            + JSON_TABLE_DEPTH_LIMIT + " nesting levels) and the");
        emitLine("// StackOverflowError of the nested-class $fromJsonValue");
        emitLine("// recursion over deeply nested JSON objects.");
        emitLine("public static " + gen + " " + fromJson
            + "(java.lang.String s) {");
        indent++;
        emitLine("try { return " + gen + ".$fromJsonValue(__jsonParse(s)); }");
        emitLine("catch (java.lang.RuntimeException e) { return null; }");
        emitLine("catch (java.lang.StackOverflowError e) { return null; }");
        indent--;
        emitLine("}");
        emitLine("// " + cd.name() + "$toJson(v) — serialize to a JSON string.");
        emitLine("public static java.lang.String " + toJson + "(" + gen
            + " v) {");
        indent++;
        emitLine("return __jsonStringify(" + gen + ".$toJsonValue(v));");
        indent--;
        emitLine("}");
    }

    /**
     * Emits the @jsonable JSON runtime support: a minimal strict JSON
     * parser ({@code __jsonParse}: LinkedHashMaps for objects, ArrayLists
     * for arrays, Double/Boolean/String leaves, the DEAL null for JSON
     * null and parse failure alike), a JSON stringifier ({@code
     * __jsonStringify}, E8001 for non-finite numbers — std/json.lua's
     * NaN/Infinity rejection), and the per-type value validators ({@code
     * __jsonInt} etc. — null on a type mismatch, the fromJson
     * validation-failure contract). Emitted only when the module declares
     * at least one @jsonable class; every name carries the {@code __}
     * prefix, unreachable from {@link #javaName}.
     */
    private void emitJsonRuntimeSupport() {
        emitLine("// ---- @jsonable JSON runtime support ----");
        emitLine("// The per-module Missing sentinel: an optional class field whose");
        emitLine("// storage holds this reference is ABSENT (the spec's Missing");
        emitLine("// sentinel representation). Distinct from the DEAL null, so");
        emitLine("// optional-nullable fields keep their three states.");
        emitLine("static final java.lang.Object $MISSING = new java.lang.Object();");
        emitLine("// Minimal strict JSON parser: LinkedHashMap for objects, ArrayList");
        emitLine("// for arrays, Double for numbers, Boolean, String, and null for");
        emitLine("// JSON null. Parse failure returns null (the DEAL null of the");
        emitLine("// C$fromJson contract — never a throw).");
        emitLine("static java.lang.Object __jsonParse(java.lang.String s) {");
        indent++;
        emitLine("try { __JsonParser p = new __JsonParser(s); java.lang.Object v = p.parseValue(); p.skipWs(); return p.atEnd() ? v : null; }");
        emitLine("catch (java.lang.RuntimeException e) { return null; }");
        emitLine("// Deeply nested JSON (hostile ~10 KB payloads) overflows the");
        emitLine("// recursive parser's stack: the StackOverflowError converts to");
        emitLine("// the DEAL null exactly like LuaJIT's pcall(__json_parse, s)");
        emitLine("// converts its stack exhaustion — C$fromJson never throws.");
        emitLine("catch (java.lang.StackOverflowError e) { return null; }");
        indent--;
        emitLine("}");
        emitLine("static final class __JsonParser {");
        indent++;
        emitLine("final java.lang.String s;");
        emitLine("int i = 0;");
        emitLine("__JsonParser(java.lang.String s) { this.s = s; }");
        emitLine("boolean atEnd() { return i >= s.length(); }");
        emitLine("char peek() { if (i >= s.length()) throw new java.lang.RuntimeException(\"unexpected end of JSON input\"); return s.charAt(i); }");
        emitLine("void expect(char c) { if (peek() != c) throw new java.lang.RuntimeException(\"unexpected character in JSON input\"); i++; }");
        emitLine("void skipWs() { while (i < s.length()) { char c = s.charAt(i); if (c != ' ' && c != '\\t' && c != '\\n' && c != '\\r') return; i++; } }");
        emitLine("java.lang.Object parseValue() {");
        indent++;
        emitLine("skipWs();");
        emitLine("char c = peek();");
        emitLine("if (c == '{') return parseObject();");
        emitLine("if (c == '[') return parseArray();");
        emitLine("if (c == '\"') return parseString();");
        emitLine("if (c == 't') { expect('t'); expect('r'); expect('u'); expect('e'); return java.lang.Boolean.TRUE; }");
        emitLine("if (c == 'f') { expect('f'); expect('a'); expect('l'); expect('s'); expect('e'); return java.lang.Boolean.FALSE; }");
        emitLine("if (c == 'n') { expect('n'); expect('u'); expect('l'); expect('l'); return null; }");
        emitLine("if (c == '-' || (c >= '0' && c <= '9')) return parseNumber();");
        emitLine("throw new java.lang.RuntimeException(\"unexpected character in JSON input\");");
        indent--;
        emitLine("}");
        emitLine("java.util.LinkedHashMap<java.lang.String, java.lang.Object> parseObject() {");
        indent++;
        emitLine("expect('{');");
        emitLine("java.util.LinkedHashMap<java.lang.String, java.lang.Object> m = new java.util.LinkedHashMap<>();");
        emitLine("skipWs();");
        emitLine("if (peek() == '}') { i++; return m; }");
        emitLine("while (true) {");
        indent++;
        emitLine("skipWs();");
        emitLine("java.lang.String k = parseString();");
        emitLine("skipWs();");
        emitLine("expect(':');");
        emitLine("m.put(k, parseValue());");
        emitLine("skipWs();");
        emitLine("char c = peek();");
        emitLine("if (c == ',') { i++; continue; }");
        emitLine("if (c == '}') { i++; return m; }");
        emitLine("throw new java.lang.RuntimeException(\"unexpected character in JSON object\");");
        indent--;
        emitLine("}");
        indent--;
        emitLine("}");
        emitLine("java.util.ArrayList<java.lang.Object> parseArray() {");
        indent++;
        emitLine("expect('[');");
        emitLine("java.util.ArrayList<java.lang.Object> list = new java.util.ArrayList<>();");
        emitLine("skipWs();");
        emitLine("if (peek() == ']') { i++; return list; }");
        emitLine("while (true) {");
        indent++;
        emitLine("list.add(parseValue());");
        emitLine("skipWs();");
        emitLine("char c = peek();");
        emitLine("if (c == ',') { i++; continue; }");
        emitLine("if (c == ']') { i++; return list; }");
        emitLine("throw new java.lang.RuntimeException(\"unexpected character in JSON array\");");
        indent--;
        emitLine("}");
        indent--;
        emitLine("}");
        emitLine("java.lang.String parseString() {");
        indent++;
        emitLine("expect('\"');");
        emitLine("java.lang.StringBuilder sb = new java.lang.StringBuilder();");
        emitLine("while (true) {");
        indent++;
        emitLine("char c = peek();");
        emitLine("if (c == '\"') { i++; java.lang.String out = sb.toString();"
            + " if (__hasUnpairedSurrogate(out)) throw new java.lang.RuntimeException(\"unpaired UTF-16 surrogate code unit in JSON string\");"
            + " return out; }");
        emitLine("// spec-v1.2 \u00a7Lexical elements: JSON strings entering the"
            + " DEAL string boundary must carry no unpaired UTF-16"
            + " surrogate code units \u2014 a lone 0xD800 / 0xDC00 escape"
            + " (or a raw lone surrogate) is a parse failure (the DEAL"
            + " null), exactly like LuaJIT's std/json.lua decoder error"
            + " inside pcall(__json_parse, s).");
        emitLine("if (c != '\\\\') { sb.append(c); i++; continue; }");
        emitLine("i++;");
        emitLine("char e = peek();");
        emitLine("switch (e) {");
        indent++;
        emitLine("case '\"': sb.append('\"'); i++; break;");
        emitLine("case '\\\\': sb.append('\\\\'); i++; break;");
        emitLine("case '/': sb.append('/'); i++; break;");
        emitLine("case 'b': sb.append('\\b'); i++; break;");
        emitLine("case 'f': sb.append('\\f'); i++; break;");
        emitLine("case 'n': sb.append('\\n'); i++; break;");
        emitLine("case 'r': sb.append('\\r'); i++; break;");
        emitLine("case 't': sb.append('\\t'); i++; break;");
        emitLine("case 'u': {");
        indent++;
        emitLine("i++;");
        emitLine("if (i + 4 > s.length()) throw new java.lang.RuntimeException(\"bad \\\\u escape\");");
        emitLine("int code = 0;");
        emitLine("for (int j = 0; j < 4; j++) { int d = java.lang.Character.digit(s.charAt(i + j), 16); if (d < 0) throw new java.lang.RuntimeException(\"bad \\\\u escape\"); code = code * 16 + d; }");
        emitLine("i += 4;");
        emitLine("sb.append((char) code);");
        emitLine("break;");
        indent--;
        emitLine("}");
        emitLine("default: throw new java.lang.RuntimeException(\"bad escape in JSON string\");");
        indent--;
        emitLine("}");
        indent--;
        emitLine("}");
        indent--;
        emitLine("}");
        emitLine("java.lang.Double parseNumber() {");
        indent++;
        emitLine("int start = i;");
        emitLine("if (peek() == '-') i++;");
        emitLine("// spec-v1.2 \u00a7JSON serialization: the strict RFC 8259 number");
        emitLine("// grammar, exactly the checks of fs/std/json.lua's parse_number.");
        emitLine("// Invalid spellings are parse failures (the DEAL null via the");
        emitLine("// __jsonParse catch), never a permissive Double.parseDouble");
        emitLine("// acceptance: a leading zero (01, -01, 00), a decimal point");
        emitLine("// without a fraction digit (1.), an exponent without digits");
        emitLine("// (1.e2, 1e), a sign without digits (1e+), and a missing");
        emitLine("// integer part (-.5) all reject exactly like the LuaJIT");
        emitLine("// reference's parse_error inside pcall(__json_parse, s).");
        emitLine("if (i < s.length() && s.charAt(i) == '0') { i++; }");
        emitLine("else {");
        indent++;
        emitLine("if (i >= s.length() || s.charAt(i) < '1' || s.charAt(i) > '9') throw new java.lang.RuntimeException(\"bad JSON number\");");
        emitLine("i++;");
        emitLine("while (i < s.length()) { char c = s.charAt(i); if (c >= '0' && c <= '9') i++; else break; }");
        indent--;
        emitLine("}");
        emitLine("if (i < s.length() && s.charAt(i) == '.') {");
        indent++;
        emitLine("i++;");
        emitLine("if (i >= s.length() || s.charAt(i) < '0' || s.charAt(i) > '9') throw new java.lang.RuntimeException(\"bad JSON number\");");
        emitLine("while (i < s.length()) { char c = s.charAt(i); if (c >= '0' && c <= '9') i++; else break; }");
        indent--;
        emitLine("}");
        emitLine("if (i < s.length() && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {");
        indent++;
        emitLine("i++;");
        emitLine("if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) i++;");
        emitLine("if (i >= s.length() || s.charAt(i) < '0' || s.charAt(i) > '9') throw new java.lang.RuntimeException(\"bad JSON number\");");
        emitLine("while (i < s.length()) { char c = s.charAt(i); if (c >= '0' && c <= '9') i++; else break; }");
        indent--;
        emitLine("}");
        emitLine("java.lang.String num = s.substring(start, i);");
        emitLine("try { return java.lang.Double.valueOf(java.lang.Double.parseDouble(num)); } catch (java.lang.NumberFormatException e) { throw new java.lang.RuntimeException(\"bad JSON number\"); }");
        indent--;
        emitLine("}");
        indent--;
        emitLine("}");
        emitLine("// Untyped JSON-shaped table data conversion (spec §JSON");
        emitLine("// serialization): a parsed JSON object becomes a string-keyed $DealRt.Table,");
        emitLine("// a parsed JSON array becomes an array-mode $DealRt.Table (DEAL tables are");
        emitLine("// string-keyed, so the array shape is preserved in the wrapper),");
        emitLine("// nested structures recurse, and leaves pass through. Integral");
        emitLine("// parsed numbers become Long so re-serialization prints \"1\"");
        emitLine("// exactly like LuaJIT's %.17g (\"1\"), never \"1.0\".");
        emitLine("// The bounded depth guard keeps hostile deep nesting from");
        emitLine("// exhausting the JVM stack: past " + JSON_TABLE_DEPTH_LIMIT);
        emitLine("// levels the conversion throws, and the public C$fromJson");
        emitLine("// wrapper converts the throw to the DEAL null (the spec's");
        emitLine("// fromJson parse/validation-failure contract) — a deterministic");
        emitLine("// guard instead of relying on a StackOverflowError at the");
        emitLine("// recursion limit.");
        emitLine("static java.lang.Object __jsonTableValue(java.lang.Object raw, int depth) {");
        indent++;
        emitLine("if (depth > " + JSON_TABLE_DEPTH_LIMIT + ") throw new java.lang.RuntimeException(\"JSON nesting too deep\");");
        emitLine("if (raw instanceof java.util.Map<?, ?> m) {");
        indent++;
        emitLine("$DealRt.Table t = new $DealRt.Table();");
        emitLine("for (java.util.Map.Entry<?, ?> e : m.entrySet()) { t.put(java.lang.String.valueOf(e.getKey()), __jsonTableValue(e.getValue(), depth + 1)); }");
        emitLine("return t;");
        indent--;
        emitLine("}");
        emitLine("if (raw instanceof java.util.List<?> l) {");
        indent++;
        emitLine("java.util.ArrayList<java.lang.Object> arr = new java.util.ArrayList<>();");
        emitLine("for (java.lang.Object e : l) { arr.add(__jsonTableValue(e, depth + 1)); }");
        emitLine("return new $DealRt.Table(arr);");
        indent--;
        emitLine("}");
        emitLine("if (raw instanceof java.lang.Double d) {");
        indent++;
        emitLine("if (d.doubleValue() == java.lang.Math.floor(d.doubleValue()) && !java.lang.Double.isInfinite(d.doubleValue())");
        indent++;
        emitLine(int32Mode
            ? "        && d.doubleValue() >= -2147483648.0 && d.doubleValue() <= 2147483647.0) { return java.lang.Integer.valueOf((int) d.longValue()); }"
            : "        && d.doubleValue() >= -9007199254740991.0 && d.doubleValue() <= 9007199254740991.0) { return java.lang.Long.valueOf(d.longValue()); }");
        indent--;
        emitLine("return d;");
        indent--;
        emitLine("}");
        emitLine("return raw;");
        indent--;
        emitLine("}");
        emitLine("// JSON stringify (std/json.lua contract): null, Boolean, String, Long");
        emitLine("// (int fields), Number (number fields), List (array fields), Map");
        emitLine("// (nested class fields), $DealRt.Table table values (string-keyed objects and");
        emitLine("// array-mode tables), and the emitted primitive-array wrappers");
        emitLine("// (DEAL arrays stored in tables are JSON-shaped array values).");
        emitLine("// NaN/Infinity raise E8001 exactly like std/json.lua's encode_value");
        emitLine("// rejection; a cycle or any other value raises E8001 (the spec's");
        emitLine("// finite-acyclic JSON-shape validation).");
        emitLine("static java.lang.String __jsonStringify(java.lang.Object v) {");
        indent++;
        emitLine("java.lang.StringBuilder sb = new java.lang.StringBuilder();");
        emitLine("__jsonAppend(sb, v, java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()));");
        emitLine("return sb.toString();");
        indent--;
        emitLine("}");
        emitLine("static void __jsonAppend(java.lang.StringBuilder sb, java.lang.Object v, java.util.Set<java.lang.Object> stack) {");
        indent++;
        emitLine("if (v == null) { sb.append(\"null\"); return; }");
        emitLine("if (v instanceof java.lang.Boolean b) { sb.append(b.booleanValue() ? \"true\" : \"false\"); return; }");
        emitLine("if (v instanceof java.lang.String s) { sb.append(__jsonQuote(s)); return; }");
        emitLine(int32Mode
            ? "if (v instanceof java.lang.Integer i) { sb.append(i.toString()); return; }"
            : "if (v instanceof java.lang.Long l) { sb.append(l.toString()); return; }");
        emitLine("if (v instanceof java.lang.Number n) {");
        indent++;
        emitLine("double d = n.doubleValue();");
        emitLine("if (java.lang.Double.isNaN(d)) throw new DealError(\"E8001\", \"cannot encode NaN as JSON\");");
        emitLine("if (java.lang.Double.isInfinite(d)) throw new DealError(\"E8001\", \"cannot encode Infinity as JSON\");");
        emitLine("sb.append(java.lang.Double.toString(d));");
        emitLine("return;");
        indent--;
        emitLine("}");
        emitLine("if (v instanceof java.util.List<?> list) {");
        indent++;
        emitLine("if (!stack.add(v)) throw new DealError(\"E8001\", \"value is not JSON-shaped\");");
        emitLine("sb.append('[');");
        emitLine("boolean first = true;");
        emitLine("for (java.lang.Object e : list) { if (!first) sb.append(','); first = false; __jsonAppend(sb, e, stack); }");
        emitLine("sb.append(']');");
        emitLine("stack.remove(v);");
        emitLine("return;");
        indent--;
        emitLine("}");
        emitLine("if (v instanceof java.util.Map<?, ?> map) {");
        indent++;
        emitLine("if (!stack.add(v)) throw new DealError(\"E8001\", \"value is not JSON-shaped\");");
        emitLine("sb.append('{');");
        emitLine("boolean first = true;");
        emitLine("for (java.util.Map.Entry<?, ?> e : map.entrySet()) { if (!first) sb.append(','); first = false; sb.append(__jsonQuote(java.lang.String.valueOf(e.getKey()))); sb.append(':'); __jsonAppend(sb, e.getValue(), stack); }");
        emitLine("sb.append('}');");
        emitLine("stack.remove(v);");
        emitLine("return;");
        indent--;
        emitLine("}");
        emitLine("if (v instanceof $DealRt.Table t) {");
        indent++;
        emitLine("if (!stack.add(v)) throw new DealError(\"E8001\", \"value is not JSON-shaped\");");
        emitLine("java.util.ArrayList<java.lang.Object> arr = t.$array();");
        emitLine("if (arr != null) {");
        indent++;
        emitLine("sb.append('[');");
        emitLine("boolean first = true;");
        emitLine("for (java.lang.Object e : arr) { if (!first) sb.append(','); first = false; __jsonAppend(sb, e, stack); }");
        emitLine("sb.append(']');");
        indent--;
        emitLine("} else {");
        indent++;
        emitLine("sb.append('{');");
        emitLine("boolean first = true;");
        emitLine("for (java.util.Map.Entry<java.lang.String, java.lang.Object> e : t.$entries().entrySet()) { if (!first) sb.append(','); first = false; sb.append(__jsonQuote(e.getKey())); sb.append(':'); __jsonAppend(sb, e.getValue(), stack); }");
        emitLine("sb.append('}');");
        indent--;
        emitLine("}");
        emitLine("stack.remove(v);");
        emitLine("return;");
        indent--;
        emitLine("}");
        emitLine("if (v instanceof __IntArray a) {");
        indent++;
        emitLine("if (!stack.add(v)) throw new DealError(\"E8001\", \"value is not JSON-shaped\");");
        emitLine("sb.append('[');");
        emitLine("boolean first = true;");
        emitLine(int32Mode
            ? "for (int e : a.data) { if (!first) sb.append(','); first = false; sb.append(java.lang.Integer.toString(e)); }"
            : "for (long e : a.data) { if (!first) sb.append(','); first = false; sb.append(java.lang.Long.toString(e)); }");
        emitLine("sb.append(']');");
        emitLine("stack.remove(v);");
        emitLine("return;");
        indent--;
        emitLine("}");
        emitLine("if (v instanceof __NumberArray a) {");
        indent++;
        emitLine("if (!stack.add(v)) throw new DealError(\"E8001\", \"value is not JSON-shaped\");");
        emitLine("sb.append('[');");
        emitLine("boolean first = true;");
        emitLine("for (double e : a.data) { if (!first) sb.append(','); first = false; if (java.lang.Double.isNaN(e)) throw new DealError(\"E8001\", \"cannot encode NaN as JSON\"); if (java.lang.Double.isInfinite(e)) throw new DealError(\"E8001\", \"cannot encode Infinity as JSON\"); sb.append(java.lang.Double.toString(e)); }");
        emitLine("sb.append(']');");
        emitLine("stack.remove(v);");
        emitLine("return;");
        indent--;
        emitLine("}");
        emitLine("if (v instanceof __StringArray a) {");
        indent++;
        emitLine("if (!stack.add(v)) throw new DealError(\"E8001\", \"value is not JSON-shaped\");");
        emitLine("sb.append('[');");
        emitLine("boolean first = true;");
        emitLine("for (java.lang.String e : a.data) { if (!first) sb.append(','); first = false; sb.append(__jsonQuote(e)); }");
        emitLine("sb.append(']');");
        emitLine("stack.remove(v);");
        emitLine("return;");
        indent--;
        emitLine("}");
        emitLine("if (v instanceof __BooleanArray a) {");
        indent++;
        emitLine("if (!stack.add(v)) throw new DealError(\"E8001\", \"value is not JSON-shaped\");");
        emitLine("sb.append('[');");
        emitLine("boolean first = true;");
        emitLine("for (boolean e : a.data) { if (!first) sb.append(','); first = false; sb.append(e ? \"true\" : \"false\"); }");
        emitLine("sb.append(']');");
        emitLine("stack.remove(v);");
        emitLine("return;");
        indent--;
        emitLine("}");
        emitLine("if (v instanceof __IntOrNullArray a) {");
        indent++;
        emitLine("if (!stack.add(v)) throw new DealError(\"E8001\", \"value is not JSON-shaped\");");
        emitLine("sb.append('[');");
        emitLine("boolean first = true;");
        emitLine(int32Mode
            ? "for (java.lang.Integer e : a.data) { if (!first) sb.append(','); first = false; if (e == null) { sb.append(\"null\"); } else { sb.append(e.toString()); } }"
            : "for (java.lang.Long e : a.data) { if (!first) sb.append(','); first = false; if (e == null) { sb.append(\"null\"); } else { sb.append(e.toString()); } }");
        emitLine("sb.append(']');");
        emitLine("stack.remove(v);");
        emitLine("return;");
        indent--;
        emitLine("}");
        emitLine("if (v instanceof __NumberOrNullArray a) {");
        indent++;
        emitLine("if (!stack.add(v)) throw new DealError(\"E8001\", \"value is not JSON-shaped\");");
        emitLine("sb.append('[');");
        emitLine("boolean first = true;");
        emitLine("for (java.lang.Double e : a.data) { if (!first) sb.append(','); first = false; if (e == null) { sb.append(\"null\"); } else { if (java.lang.Double.isNaN(e)) throw new DealError(\"E8001\", \"cannot encode NaN as JSON\"); if (java.lang.Double.isInfinite(e)) throw new DealError(\"E8001\", \"cannot encode Infinity as JSON\"); sb.append(java.lang.Double.toString(e)); } }");
        emitLine("sb.append(']');");
        emitLine("stack.remove(v);");
        emitLine("return;");
        indent--;
        emitLine("}");
        emitLine("if (v instanceof __StringOrNullArray a) {");
        indent++;
        emitLine("if (!stack.add(v)) throw new DealError(\"E8001\", \"value is not JSON-shaped\");");
        emitLine("sb.append('[');");
        emitLine("boolean first = true;");
        emitLine("for (java.lang.String e : a.data) { if (!first) sb.append(','); first = false; if (e == null) { sb.append(\"null\"); } else { sb.append(__jsonQuote(e)); } }");
        emitLine("sb.append(']');");
        emitLine("stack.remove(v);");
        emitLine("return;");
        indent--;
        emitLine("}");
        emitLine("if (v instanceof __BooleanOrNullArray a) {");
        indent++;
        emitLine("if (!stack.add(v)) throw new DealError(\"E8001\", \"value is not JSON-shaped\");");
        emitLine("sb.append('[');");
        emitLine("boolean first = true;");
        emitLine("for (java.lang.Boolean e : a.data) { if (!first) sb.append(','); first = false; if (e == null) { sb.append(\"null\"); } else { sb.append(e.booleanValue() ? \"true\" : \"false\"); } }");
        emitLine("sb.append(']');");
        emitLine("stack.remove(v);");
        emitLine("return;");
        indent--;
        emitLine("}");
        emitLine("throw new DealError(\"E8001\", \"value is not JSON-shaped\");");
        indent--;
        emitLine("}");
        emitLine("static java.lang.String __jsonQuote(java.lang.String s) {");
        indent++;
        emitLine("java.lang.StringBuilder sb = new java.lang.StringBuilder(\"\\\"\");");
        emitLine("for (int i = 0; i < s.length(); i++) {");
        indent++;
        emitLine("char c = s.charAt(i);");
        emitLine("switch (c) {");
        indent++;
        emitLine("case '\"': sb.append(\"\\\\\\\"\"); break;");
        emitLine("case '\\\\': sb.append(\"\\\\\\\\\"); break;");
        emitLine("case '\\b': sb.append(\"\\\\b\"); break;");
        emitLine("case '\\f': sb.append(\"\\\\f\"); break;");
        emitLine("case '\\n': sb.append(\"\\\\n\"); break;");
        emitLine("case '\\r': sb.append(\"\\\\r\"); break;");
        emitLine("case '\\t': sb.append(\"\\\\t\"); break;");
        emitLine("default: if (c < 0x20) sb.append(java.lang.String.format(java.util.Locale.ROOT, \"\\\\u%04x\", (int) c)); else sb.append(c);");
        indent--;
        emitLine("}");
        indent--;
        emitLine("}");
        emitLine("return sb.append('\"').toString();");
        indent--;
        emitLine("}");
        emitLine("// fromJson field-value validators: null on a type mismatch (the");
        emitLine("// fromJson validation-failure contract — never a throw).");
        emitLine(int32Mode
            ? "static java.lang.Integer __jsonInt(java.lang.Object v) { if (!(v instanceof java.lang.Number)) return null; double d = ((java.lang.Number) v).doubleValue(); if (d != java.lang.Math.floor(d) || d > 2147483647.0 || d < -2147483648.0) return null; return java.lang.Integer.valueOf((int) d); }"
            : "static java.lang.Long __jsonInt(java.lang.Object v) { if (!(v instanceof java.lang.Number)) return null; double d = ((java.lang.Number) v).doubleValue(); if (d != java.lang.Math.floor(d) || d > 9007199254740991.0 || d < -9007199254740991.0) return null; return java.lang.Long.valueOf((long) d); }");
        emitLine("static java.lang.Double __jsonNumber(java.lang.Object v) { return (v instanceof java.lang.Number) ? java.lang.Double.valueOf(((java.lang.Number) v).doubleValue()) : null; }");
        emitLine("static java.lang.String __jsonString(java.lang.Object v) { return (v instanceof java.lang.String) ? (java.lang.String) v : null; }");
        emitLine("static java.lang.Boolean __jsonBoolean(java.lang.Object v) { return (v instanceof java.lang.Boolean) ? (java.lang.Boolean) v : null; }");
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
        String initializer = emitTargeted(vd.initializer(), declaredType, false);
        if (needsBooleanBoundary(vd.initializer(), declaredType)) {
            initializer = "booleanNotNull(" + initializer + ")";
        }
        initializer = coerceNullValueCode(initializer, vd.initializer(),
            javaType, vd.span());
        initializer = adaptIntBoundary(vd.initializer(), initializer,
            declaredType);
        String visibility = moduleLevel ? "static " : "";
        String javaVar = declareLocal(vd.name(), declaredType);
        // ISSUE-0102 closure capture: a captured local lowers to a
        // final cell array at the declaration; every read/write routes
        // through <var>$c[0] (localJavaName). Module-level fields are
        // static and never cell-ified (nested functions reference them
        // directly — shared by construction).
        boolean cell = !moduleLevel && isCapturedMapped(javaVar);
        if (cell) {
            javaType = javaType + "[]";
        }
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
        if (cell) {
            emitLine(visibility + "final " + javaType + " " + javaVar
                + "$c = { " + initializer + " };");
        } else {
            emitLine(visibility + javaType + " " + javaVar + " = "
                + initializer + ";");
        }
    }

    /** True when {@code mapped} is a cell-ified binding of the current
     * function (ISSUE-0102 closure capture). */
    private boolean isCapturedMapped(String mapped) {
        for (Set<String> frame : capturedMappedStack) {
            if (frame.contains(mapped)) return true;
        }
        return false;
    }

    // =========================================================================
    // Closure capture analysis (ISSUE-0102)
    // =========================================================================

    /**
     * Collects every name a {@code let}/{@code for (let)}/{@code for-of}
     * variable or block-level function DECLARES anywhere inside
     * {@code stmts} (recursively through blocks, if/while/for/try bodies
     * — not through function bodies, which own their declarations).
     */
    private void collectLocalDeclarations(List<StatementNode> stmts,
            Set<String> out) {
        for (StatementNode stmt : stmts) {
            switch (stmt) {
                case VariableDeclaration vd -> out.add(vd.name());
                case Block b -> collectLocalDeclarations(b.statements(), out);
                case IfStatement is -> {
                    collectLocalDeclarations(is.thenBlock().statements(), out);
                    if (is.elseBranch().isPresent()) {
                        switch (is.elseBranch().get()) {
                            case Either.Left<IfStatement, Block> left ->
                                collectLocalDeclarations(
                                    List.of(left.value()), out);
                            case Either.Right<IfStatement, Block> right ->
                                collectLocalDeclarations(
                                    right.value().statements(), out);
                        }
                    }
                }
                case WhileStatement ws ->
                    collectLocalDeclarations(ws.body().statements(), out);
                case ForStatement fs -> {
                    if (fs.init().isPresent()
                            && fs.init().get() instanceof ForInit.VarDecl vd) {
                        out.add(vd.decl().name());
                    }
                    collectLocalDeclarations(fs.body().statements(), out);
                }
                case ForOfStatement fos -> {
                    out.add(fos.varName());
                    collectLocalDeclarations(fos.body().statements(), out);
                }
                case TryStatement ts -> {
                    // The catch variable DECLARES a binding (the checker
                    // scopes it to the catch block): nested functions
                    // inside the catch block that read or write it must
                    // mark it captured so emitTry cell-ifies it exactly
                    // like a local or parameter.
                    out.add(ts.catchVar());
                    collectLocalDeclarations(ts.tryBlock().statements(), out);
                    collectLocalDeclarations(ts.catchBlock().statements(), out);
                }
                case FunctionDeclaration fd -> out.add(fd.name());
                default -> { }
            }
        }
    }

    /**
     * Walks {@code stmts} for nested functions (function expressions and
     * block-level function declarations, at ANY depth — through
     * expressions and nested bodies) and adds to {@code captured} every
     * identifier they reference that {@code visibleDeclared} names. The
     * analysis is a conservative superset: a shadowed same-named binding
     * may be cell-ified too, which changes no observable behavior.
     */
    private void walkNestedFunctions(List<StatementNode> stmts,
            Set<String> visibleDeclared, Set<String> captured) {
        for (StatementNode stmt : stmts) {
            switch (stmt) {
                case VariableDeclaration vd ->
                    walkExprNestedFunctions(vd.initializer(), visibleDeclared,
                        captured);
                case ExpressionStatement es ->
                    walkExprNestedFunctions(es.expr(), visibleDeclared,
                        captured);
                case ReturnStatement rs -> {
                    if (rs.expr().isPresent()) {
                        walkExprNestedFunctions(rs.expr().get(),
                            visibleDeclared, captured);
                    }
                }
                case ThrowStatement ts ->
                    walkExprNestedFunctions(ts.expr(), visibleDeclared,
                        captured);
                case DeleteStatement ds ->
                    walkExprNestedFunctions(ds.target(), visibleDeclared,
                        captured);
                case Block b ->
                    walkNestedFunctions(b.statements(), visibleDeclared,
                        captured);
                case IfStatement is -> {
                    walkExprNestedFunctions(is.condition(), visibleDeclared,
                        captured);
                    walkNestedFunctions(is.thenBlock().statements(),
                        visibleDeclared, captured);
                    if (is.elseBranch().isPresent()) {
                        switch (is.elseBranch().get()) {
                            case Either.Left<IfStatement, Block> left -> {
                                walkExprNestedFunctions(left.value().condition(),
                                    visibleDeclared, captured);
                                walkNestedFunctions(
                                    left.value().thenBlock().statements(),
                                    visibleDeclared, captured);
                                if (left.value().elseBranch().isPresent()) {
                                    walkNestedFunctions(
                                        List.of(new IfStatement(
                                            left.value().span(),
                                            left.value().condition(),
                                            left.value().thenBlock(),
                                            left.value().elseBranch())),
                                        visibleDeclared, captured);
                                }
                            }
                            case Either.Right<IfStatement, Block> right ->
                                walkNestedFunctions(
                                    right.value().statements(),
                                    visibleDeclared, captured);
                        }
                    }
                }
                case WhileStatement ws -> {
                    walkExprNestedFunctions(ws.condition(), visibleDeclared,
                        captured);
                    walkNestedFunctions(ws.body().statements(),
                        visibleDeclared, captured);
                }
                case ForStatement fs -> {
                    if (fs.init().isPresent()
                            && fs.init().get() instanceof ForInit.AssignExpr ae) {
                        walkExprNestedFunctions(ae.expr(), visibleDeclared,
                            captured);
                    }
                    if (fs.condition().isPresent()) {
                        walkExprNestedFunctions(fs.condition().get(),
                            visibleDeclared, captured);
                    }
                    if (fs.update().isPresent()) {
                        walkExprNestedFunctions(fs.update().get(),
                            visibleDeclared, captured);
                    }
                    walkNestedFunctions(fs.body().statements(),
                        visibleDeclared, captured);
                }
                case ForOfStatement fos -> {
                    walkExprNestedFunctions(fos.iterable(), visibleDeclared,
                        captured);
                    walkNestedFunctions(fos.body().statements(),
                        visibleDeclared, captured);
                }
                case TryStatement ts -> {
                    walkNestedFunctions(ts.tryBlock().statements(),
                        visibleDeclared, captured);
                    walkNestedFunctions(ts.catchBlock().statements(),
                        visibleDeclared, captured);
                }
                case FunctionDeclaration fd ->
                    processNestedFunction(fd.params(), fd.body(),
                        visibleDeclared, captured);
                default -> { }
            }
        }
    }

    /** Expression-side of the nested-function walk: recurses through every
     * subexpression and hands each function expression to
     * {@link #processNestedFunction}. */
    private void walkExprNestedFunctions(ExpressionNode e,
            Set<String> visibleDeclared, Set<String> captured) {
        if (e instanceof FunctionExpr fe) {
            processNestedFunction(fe.params(), fe.body(), visibleDeclared,
                captured);
            return;
        }
        if (e instanceof BinaryExpr bin) {
            walkExprNestedFunctions(bin.left(), visibleDeclared, captured);
            walkExprNestedFunctions(bin.right(), visibleDeclared, captured);
        } else if (e instanceof UnaryExpr u) {
            walkExprNestedFunctions(u.expr(), visibleDeclared, captured);
        } else if (e instanceof CallExpr call) {
            walkExprNestedFunctions(call.callee(), visibleDeclared, captured);
            for (ExpressionNode arg : call.args()) {
                walkExprNestedFunctions(arg, visibleDeclared, captured);
            }
        } else if (e instanceof MemberAccessExpr mae) {
            walkExprNestedFunctions(mae.object(), visibleDeclared, captured);
        } else if (e instanceof IndexExpr idx) {
            walkExprNestedFunctions(idx.array(), visibleDeclared, captured);
            walkExprNestedFunctions(idx.index(), visibleDeclared, captured);
        } else if (e instanceof ArrayLiteralExpr al) {
            for (ExpressionNode el : al.elements()) {
                walkExprNestedFunctions(el, visibleDeclared, captured);
            }
        } else if (e instanceof ObjectLiteralExpr ol) {
            for (Property prop : ol.properties()) {
                walkExprNestedFunctions(prop.value(), visibleDeclared,
                    captured);
            }
        } else if (e instanceof HasExpr he) {
            walkExprNestedFunctions(he.object(), visibleDeclared, captured);
        } else if (e instanceof TemplateLiteralExpr tl) {
            for (ExpressionNode part : tl.parts()) {
                walkExprNestedFunctions(part, visibleDeclared, captured);
            }
        } else if (e instanceof AwaitExpression aw) {
            walkExprNestedFunctions(aw.callee(), visibleDeclared, captured);
        } else if (e instanceof AssignmentExpr ae) {
            walkExprNestedFunctions(ae.target(), visibleDeclared, captured);
            walkExprNestedFunctions(ae.value(), visibleDeclared, captured);
        }
    }

    /** One nested function: its free identifiers referencing
     * {@code visibleDeclared} bindings become captures, and its body is
     * walked recursively for deeper nesting. */
    private void processNestedFunction(List<Parameter> params, Block body,
            Set<String> visibleDeclared, Set<String> captured) {
        Set<String> own = new LinkedHashSet<>();
        collectLocalDeclarations(body.statements(), own);
        for (Parameter p : params) own.add(p.name());
        Set<String> free = new LinkedHashSet<>();
        collectIdentifierNames(body, free);
        for (String n : free) {
            if (visibleDeclared.contains(n) && !own.contains(n)) {
                captured.add(n);
            }
        }
        Set<String> deeper = new LinkedHashSet<>(visibleDeclared);
        deeper.addAll(own);
        walkNestedFunctions(body.statements(), deeper, captured);
    }

    /** Collects every identifier name appearing in the block's
     * expressions (not descending into nested function bodies — those are
     * separate nested functions). */
    private void collectIdentifierNames(Block b, Set<String> out) {
        for (StatementNode stmt : b.statements()) {
            collectStmtIdentifierNames(stmt, out);
        }
    }

    private void collectStmtIdentifierNames(StatementNode stmt,
            Set<String> out) {
        switch (stmt) {
            case VariableDeclaration vd ->
                collectExprIdentifierNames(vd.initializer(), out);
            case ExpressionStatement es ->
                collectExprIdentifierNames(es.expr(), out);
            case ReturnStatement rs -> {
                if (rs.expr().isPresent()) {
                    collectExprIdentifierNames(rs.expr().get(), out);
                }
            }
            case ThrowStatement ts ->
                collectExprIdentifierNames(ts.expr(), out);
            case DeleteStatement ds ->
                collectExprIdentifierNames(ds.target(), out);
            case Block b -> collectIdentifierNames(b, out);
            case IfStatement is -> {
                collectExprIdentifierNames(is.condition(), out);
                collectIdentifierNames(is.thenBlock(), out);
                if (is.elseBranch().isPresent()) {
                    switch (is.elseBranch().get()) {
                        case Either.Left<IfStatement, Block> left -> {
                            // Re-descend the whole else-if chain so every
                            // link's condition/body/else contributes.
                            collectStmtIdentifierNames(left.value(), out);
                        }
                        case Either.Right<IfStatement, Block> right ->
                            collectIdentifierNames(right.value(), out);
                    }
                }
            }
            case WhileStatement ws -> {
                collectExprIdentifierNames(ws.condition(), out);
                collectIdentifierNames(ws.body(), out);
            }
            case ForStatement fs -> {
                if (fs.init().isPresent()
                        && fs.init().get() instanceof ForInit.AssignExpr ae) {
                    collectExprIdentifierNames(ae.expr(), out);
                }
                if (fs.condition().isPresent()) {
                    collectExprIdentifierNames(fs.condition().get(), out);
                }
                if (fs.update().isPresent()) {
                    collectExprIdentifierNames(fs.update().get(), out);
                }
                collectIdentifierNames(fs.body(), out);
            }
            case ForOfStatement fos -> {
                collectExprIdentifierNames(fos.iterable(), out);
                collectIdentifierNames(fos.body(), out);
            }
            case TryStatement ts -> {
                collectIdentifierNames(ts.tryBlock(), out);
                collectIdentifierNames(ts.catchBlock(), out);
            }
            default -> { }
        }
    }

    private void collectExprIdentifierNames(ExpressionNode e, Set<String> out) {
        if (e instanceof FunctionExpr fe) {
            return; // a nested function owns its identifiers
        }
        if (e instanceof IdentifierExpr id) {
            out.add(id.name());
        } else if (e instanceof BinaryExpr bin) {
            collectExprIdentifierNames(bin.left(), out);
            collectExprIdentifierNames(bin.right(), out);
        } else if (e instanceof UnaryExpr u) {
            collectExprIdentifierNames(u.expr(), out);
        } else if (e instanceof CallExpr call) {
            collectExprIdentifierNames(call.callee(), out);
            for (ExpressionNode arg : call.args()) {
                collectExprIdentifierNames(arg, out);
            }
        } else if (e instanceof MemberAccessExpr mae) {
            collectExprIdentifierNames(mae.object(), out);
        } else if (e instanceof IndexExpr idx) {
            collectExprIdentifierNames(idx.array(), out);
            collectExprIdentifierNames(idx.index(), out);
        } else if (e instanceof ArrayLiteralExpr al) {
            for (ExpressionNode el : al.elements()) {
                collectExprIdentifierNames(el, out);
            }
        } else if (e instanceof ObjectLiteralExpr ol) {
            for (Property prop : ol.properties()) {
                collectExprIdentifierNames(prop.value(), out);
            }
        } else if (e instanceof HasExpr he) {
            collectExprIdentifierNames(he.object(), out);
        } else if (e instanceof TemplateLiteralExpr tl) {
            for (ExpressionNode part : tl.parts()) {
                collectExprIdentifierNames(part, out);
            }
        } else if (e instanceof AwaitExpression aw) {
            collectExprIdentifierNames(aw.callee(), out);
        } else if (e instanceof AssignmentExpr ae) {
            collectExprIdentifierNames(ae.target(), out);
            collectExprIdentifierNames(ae.value(), out);
        }
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
        // synchronous Java with DEAL's observable semantics. ISSUE-0099
        // adds the await-site completion check for DEAL async calls and
        // lifts async markers into the ISSUE-0098 wrapper machinery, so
        // async function VALUES emit the same per-declaration wrapper
        // field as sync ones (below); async function EXPRESSIONS stay
        // E6000.
        if (!moduleLevel) {
            // ISSUE-0102: a block-level function declaration is a fresh
            // first-class function value at its declaration point (a
            // cell so self-recursion works, exactly like the Lua
            // backend's local function binding).
            emitBlockFunction(fd);
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
        List<String> helperSignature = runtimeHelperSignatures().get(javaFn);
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

        // Emit the function's wrapper instance field right before the
        // method (ISSUE-0098 slice): a function declaration produces a
        // first-class typed function value (spec §Function values, calls,
        // and wrappers) whose invoke delegates to the static method —
        // the JVM form of the Lua backend's runtime function wrapper.
        // The field sits at the declaration's source position, so
        // load-time (static-initializer) reads of a later-declared
        // function's wrapper are illegal Java forward references and
        // rejected by emitIdentifier's load-time guard, exactly matching
        // LuaJIT's declaration-point assignment of function values.
        Symbol fnSym = symbols.resolve(fd.name());
        if (fnSym instanceof Symbol.FunctionSymbol fs
                && fs.funcType() != null
                && fnShapeName(fs.funcType()) != null) {
            Type.Func funcType = fs.funcType();
            String shape = registerWrapperShape(funcType);
            emitLine("static final " + shape + " " + javaFn + "$fn = new "
                + shape + "() {");
            indent++;
            emitLine("@Override");
            StringBuilder inv = new StringBuilder(javaReturn)
                .append(" invoke(");
            for (int i = 0; i < funcType.paramTypes().size(); i++) {
                if (i > 0) inv.append(", ");
                inv.append(javaLocalType(funcType.paramTypes().get(i),
                    fd.span())).append(" p").append(i);
            }
            inv.append(") { ");
            if (returnType instanceof Type.Null) {
                inv.append(qualifiedStatic(javaFn)).append("(");
            } else {
                inv.append("return ").append(qualifiedStatic(javaFn)).append("(");
            }
            for (int i = 0; i < funcType.paramTypes().size(); i++) {
                if (i > 0) inv.append(", ");
                inv.append("p").append(i);
            }
            inv.append("); }");
            emitLine(inv.toString());
            indent--;
            emitLine("};");
        }

        // ISSUE-0102 closure captures: cell-ify every local/parameter a
        // nested function references (the analysis walks all nested
        // function expressions and block-level declarations at every
        // depth). The frame must exist BEFORE the parameter declarations
        // so declareLocal registers the captured parameter names.
        Set<String> topDeclared = new LinkedHashSet<>();
        collectLocalDeclarations(fd.body().statements(), topDeclared);
        for (Parameter p : fd.params()) topDeclared.add(p.name());
        Set<String> topCaptured = new LinkedHashSet<>();
        walkNestedFunctions(fd.body().statements(), topDeclared, topCaptured);
        capturedNamesStack.push(topCaptured);
        capturedMappedStack.push(new LinkedHashSet<>());

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
        // Captured parameters lower to cells: declare the cell right
        // after the signature so every body read/write routes through
        // <name>$c[0] (localJavaName).
        for (int i = 0; i < fd.params().size(); i++) {
            if (isCapturedMapped(paramNames.get(i))) {
                emitLine("final " + paramTypes.get(i) + "[] "
                    + paramNames.get(i) + "$c = { " + paramNames.get(i)
                    + " };");
            }
        }
        boolean savedModuleLevel = moduleLevel;
        int savedModuleIndex = currentModuleStatementIndex;
        Type savedReturnType = currentReturnType;
        currentReturnType = returnType;
        currentModuleStatementIndex = -1;
        moduleLevel = false;
        List<StatementNode> savedBody = currentFunctionBody;
        List<String> savedParams = currentFunctionParams;
        currentFunctionBody = fd.body().statements();
        List<String> dealParams = new ArrayList<>();
        for (Parameter p : fd.params()) {
            dealParams.add(p.name());
        }
        currentFunctionParams = dealParams;
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
        currentFunctionBody = savedBody;
        currentFunctionParams = savedParams;
        currentReturnType = savedReturnType;
        currentModuleStatementIndex = savedModuleIndex;
        moduleLevel = savedModuleLevel;
        currentReturnType = savedReturnType;
        localScopes.pop();
        localTypeScopes.pop();
        functionBindingNames.pop();
        capturedNamesStack.pop();
        capturedMappedStack.pop();

        indent--;
        emitLine("}");
    }

    // =========================================================================
    // Function expressions and block-level functions (ISSUE-0102 closures)
    // =========================================================================

    /**
     * Block-level function declaration (ISSUE-0102): a fresh first-class
     * function value at its declaration point, bound through a
     * one-element cell so the body's self-recursion reads the binding
     * (a direct {@code final} local self-reference would be an illegal
     * forward reference in Java). Reads and writes of the binding route
     * through the cell like any other captured binding.
     */
    private void emitBlockFunction(FunctionDeclaration fd) {
        if (capturedMappedStack.isEmpty()) {
            // A module-level static block cannot hold a block-level
            // function whose binding is a method-local cell — E6000,
            // never a broken artifact. (Function-local blocks always
            // have the enclosing function's capture frame.)
            unsupported("block-level functions at module level", fd.span());
            return;
        }
        if (fd.isAsync()) {
            unsupported("async function expressions and block-level async "
                + "functions", fd.span());
            return;
        }
        Type returnType = resolveTypeNode(fd.returnType());
        String javaReturn = javaReturnType(returnType, fd.returnType().span());
        if (javaReturn == null) return;
        List<Type> paramTypes = new ArrayList<>();
        boolean ok = true;
        for (Parameter p : fd.params()) {
            Type pt = resolveTypeNode(p.type());
            if (javaLocalType(pt, p.type().span()) == null) {
                ok = false;
                break;
            }
            paramTypes.add(pt);
        }
        if (!ok) return;
        Type.Func funcType = new Type.Func(paramTypes, returnType, false);
        String shape = registerWrapperShape(funcType);
        if (shape == null) {
            unsupported("function values whose signature contains "
                + "arrays/classes/nullables/nested functions "
                + "(deferred to ISSUE-0110)", fd.span());
            return;
        }
        String mapped = declareLocal(fd.name(), funcType);
        // The block-function binding is ALWAYS a cell (self-recursion
        // needs it even when nothing else captures the name).
        capturedMappedStack.peek().add(mapped);
        String cell = mapped + "$c";
        emitLine("final " + shape + "[] " + cell + " = new " + shape
            + "[1];");
        emitLine(cell + "[0] = " + emitInlineFunctionExpr(shape, javaReturn,
            fd.params(), fd.body(), returnType, fd.span()) + ";");
    }

    /** A function expression (ISSUE-0102): an anonymous subclass of the
     * signature's wrapper class emitted inline at the expression
     * position. The body captures enclosing locals through their cells
     * (the enclosing function's analysis cell-ified every captured
     * binding), and the body's OWN captured locals (captured by deeper
     * nested functions) cell-ify in their own frame. */
    private String emitFunctionExpr(FunctionExpr fe) {
        if (fe.isAsync()) {
            unsupported("async function expressions", fe.span());
            return "null";
        }
        Type t = typeOf(fe);
        if (!(t instanceof Type.Func ft)) {
            unsupported("function expression without a function type",
                fe.span());
            return "null";
        }
        String shape = registerWrapperShape(ft);
        if (shape == null) {
            unsupported("function values whose signature contains "
                + "arrays/classes/nullables/nested functions "
                + "(deferred to ISSUE-0110)", fe.span());
            return "null";
        }
        Type returnType = ft.returnType();
        String javaReturn = javaReturnType(returnType, fe.span());
        if (javaReturn == null) return "null";
        return emitInlineFunctionExpr(shape, javaReturn, fe.params(),
            fe.body(), returnType, fe.span());
    }

    /**
     * Emits an anonymous wrapper subclass as a multi-line inline
     * expression: the {@code invoke} signature uses each parameter's
     * DECLARED (disambiguated) Java name, captured parameters lower to
     * cells, and the body statements emit into a temporary buffer at a
     * consistent indentation (hoisted pre-statements flush INSIDE the
     * body, where their temporaries belong).
     */
    private String emitInlineFunctionExpr(String shape, String javaReturn,
            List<Parameter> params, Block body, Type returnType,
            Span span) {
        String pad = "    ";
        int base = indent;
        List<String> paramTypes = new ArrayList<>();
        for (Parameter p : params) {
            String jt = javaLocalType(resolveTypeNode(p.type()), p.span());
            if (jt == null) return "null";
            paramTypes.add(jt);
        }
        // The body's own capture frame + parameter scope exist BEFORE the
        // parameter declarations, so declareLocal registers captured
        // parameters into the right frame.
        Set<String> own = new LinkedHashSet<>();
        collectLocalDeclarations(body.statements(), own);
        for (Parameter p : params) own.add(p.name());
        Set<String> capturedHere = new LinkedHashSet<>();
        walkNestedFunctions(body.statements(), own, capturedHere);
        capturedNamesStack.push(capturedHere);
        capturedMappedStack.push(new LinkedHashSet<>());
        localScopes.push(new LinkedHashMap<>());
        localTypeScopes.push(new LinkedHashMap<>());
        functionBindingNames.push(new LinkedHashSet<>());
        List<String> paramNames = new ArrayList<>();
        for (Parameter p : params) {
            paramNames.add(declareLocal(p.name(), resolveTypeNode(p.type())));
        }

        StringBuilder sb = new StringBuilder();
        sb.append("new ").append(shape).append("() {\n");
        sb.append(pad.repeat(base + 1)).append("@Override\n");
        sb.append(pad.repeat(base + 1)).append(javaReturn)
            .append(" invoke(");
        for (int i = 0; i < paramNames.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(paramTypes.get(i)).append(' ')
                .append(paramNames.get(i));
        }
        sb.append(") {\n");

        StringBuilder savedOut = out;
        int savedIndent = indent;
        Type savedReturnType = currentReturnType;
        List<StatementNode> savedBody = currentFunctionBody;
        List<String> savedParams = currentFunctionParams;
        int savedModuleIndex = currentModuleStatementIndex;
        out = sb;
        indent = base + 2;
        currentReturnType = returnType;
        currentModuleStatementIndex = -1;
        currentFunctionBody = body.statements();
        List<String> dealParams = new ArrayList<>();
        for (Parameter p : params) dealParams.add(p.name());
        currentFunctionParams = dealParams;
        for (int i = 0; i < paramNames.size(); i++) {
            if (isCapturedMapped(paramNames.get(i))) {
                emitLine("final " + paramTypes.get(i) + "[] "
                    + paramNames.get(i) + "$c = { " + paramNames.get(i)
                    + " };");
            }
        }
        for (StatementNode stmt : body.statements()) {
            emitStatement(stmt);
            if (!statementCompletesNormally(stmt)) {
                // Dead code after a non-completing statement (JLS
                // §14.21): skip the rest, exactly like emitFunction.
                flushPreStatements();
                break;
            }
        }
        currentFunctionBody = savedBody;
        currentFunctionParams = savedParams;
        currentReturnType = savedReturnType;
        currentModuleStatementIndex = savedModuleIndex;
        out = savedOut;
        indent = savedIndent;
        localScopes.pop();
        localTypeScopes.pop();
        functionBindingNames.pop();
        capturedNamesStack.pop();
        capturedMappedStack.pop();

        sb.append(pad.repeat(base + 1)).append("}\n");
        sb.append(pad.repeat(base)).append("}");
        return sb.toString();
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
        String value = emitTargeted(e, currentReturnType, true);
        value = adaptIntBoundary(e, value, currentReturnType);
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
            // ISSUE-0102: a throw cannot complete normally (JLS §14.21)
            // — statements after a try whose catch always throws are
            // unreachable to javac, exactly like the return rule.
            case ThrowStatement ts -> false;
            // break/continue transfer control and cannot complete
            // normally either (JLS §14.21).
            case BreakStatement bs -> false;
            case ContinueStatement cs -> false;
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
            // JLS §14.21: a try statement can complete normally iff the
            // try block can complete normally OR the catch block can.
            case TryStatement ts ->
                statementCompletesNormally(ts.tryBlock())
                    || statementCompletesNormally(ts.catchBlock());
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
        if (iterableType instanceof Type.String) {
            emitStringForOf(fos);
            return;
        }
        if (iterableType instanceof Type.Array arr) {
            emitArrayForOf(fos, arr);
            return;
        }
        unsupported("for-of over " + typeName(iterableType)
            + " (only arrays and strings iterate)", fos.span());
    }

    private void emitStringForOf(ForOfStatement fos) {
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
        emitForOfLoopBody(fos, loopVar);
        localScopes.pop();
        localTypeScopes.pop();
        indent--;
        emitLine("}");
    }

    /**
     * Array for-of (ISSUE-0102): iterates the array wrapper's storage
     * in index order, one fresh binding per iteration (spec-v1.2
     * §For-of — the loop variable is a fresh binding per iteration, so
     * closures captured inside the body see per-iteration values). The
     * iterated expression evaluates exactly once, before the loop; the
     * per-iteration element read runs the element-typed checks the
     * array read helpers carry (always in-bounds here, so no E8001 can
     * occur — the helpers still raise E8002 on a negative index, which
     * never happens for the 0-based scan).
     */
    private void emitArrayForOf(ForOfStatement fos, Type.Array arr) {
        String iterable = emitExpression(fos.iterable());
        String wrapper = arrayWrapperName(arr.element());
        if (wrapper == null) {
            javaArrayElementType(arr.element(), fos.span());
            return;
        }
        Type element = arr.element();
        int n = forOfCounter++;
        String iterVar = "__iter" + n;
        String idxVar = "__i" + n;
        flushPreStatements();
        emitLine(wrapper + " " + iterVar + " = " + iterable + ";");
        emitLine("for (long " + idxVar + " = 0L; " + idxVar + " < (long) "
            + iterVar + ".data.length; " + idxVar + "++) {");
        indent++;
        localScopes.push(new LinkedHashMap<>());
        localTypeScopes.push(new LinkedHashMap<>());
        String loopVar = declareLocal(fos.varName(),
            resolveTypeNode(fos.varType()));
        String read = arrayForOfReadHelper(element, fos.span());
        if (read == null) {
            // Unsupported element shape (an unsupported function
            // signature or nested shape): E6000 recorded by the helper
            // lookup; emit the inert placeholder so the artifact gate
            // still sees a diagnostic-carrying (discarded) result.
            read = "null";
        }
        String javaType = javaLocalType(element, fos.span());
        emitLine((javaType == null ? "java.lang.Object" : javaType) + " "
            + loopVar + " = " + read + "(" + iterVar + ", " + idxVar
            + ");");
        emitForOfLoopBody(fos, loopVar);
        localScopes.pop();
        localTypeScopes.pop();
        indent--;
        emitLine("}");
    }

    /**
     * Body emission shared by the string and array for-of forms: a
     * captured loop variable gets a per-iteration fresh cell declared
     * before the body (closures created in this iteration capture THIS
     * iteration's cell), and the loop's continue semantics stay plain
     * Java (the update — {@code idxVar++} — lives in the for head).
     */
    private void emitForOfLoopBody(ForOfStatement fos, String loopVar) {
        loopContinueLabels.addLast(null);
        boolean captured = isCapturedMapped(loopVar);
        if (captured) {
            String cellType = javaLocalType(resolveTypeNode(fos.varType()),
                fos.span());
            localScopes.push(new LinkedHashMap<>());
            localTypeScopes.push(new LinkedHashMap<>());
            localScopes.peek().put(fos.varName(), loopVar + "$c[0]");
            emitLine("final " + cellType + "[] " + loopVar + "$c = { "
                + loopVar + " };");
        }
        emitScopedBlock(fos.body());
        if (captured) {
            localScopes.pop();
            localTypeScopes.pop();
        }
        loopContinueLabels.removeLast();
    }

    /** The per-element read helper for an array for-of over element type
     * {@code element}, or {@code null} (with E6000 recorded) for an
     * unsupported element shape. Mirrors the emitIndexRead helper chain:
     * function elements, nullable function elements, and nested-array
     * elements route through {@link #refArrayReadHelper} (the per-shape
     * {@code __fnRead$}/{@code __fnOrNullRead$}/{@code __nestedRead$}
     * helpers with their E6000 diagnostics for unsupported signatures)
     * — never the inert placeholder without a diagnostic. */
    private String arrayForOfReadHelper(Type element, Span span) {
        if (element instanceof Type.Nullable ne) {
            String h = orNullArrayReadHelper(ne.inner());
            if (h == null && ne.inner() instanceof Type.Class cls) {
                h = classArrayHelper(cls, "readOrNull", span);
            }
            if (h == null && ne.inner() instanceof Type.Func) {
                h = refArrayReadHelper(element, span);
            }
            return h;
        }
        if (element instanceof Type.Class cls) {
            return classArrayHelper(cls, "read", span);
        }
        if (element instanceof Type.Array || element instanceof Type.Func) {
            return refArrayReadHelper(element, span);
        }
        String h = arrayReadHelper(element);
        if (h != null) return h;
        javaArrayElementType(element, span);
        return null;
    }

    /**
     * C-style for loop (ISSUE-0102). The simple form — no hoisted
     * side effects in the initializer, condition, or update — emits a
     * plain Java {@code for (init; loopCond(cond); update)} whose
     * {@code continue} semantics match DEAL natively (the update runs).
     * The transformed form re-places the condition test and update
     * inside a {@code while (true)} body with a labeled body block:
     * a DEAL {@code continue} becomes {@code break <label>;} so it jumps
     * past the body to the re-placed update exactly once. A captured
     * loop variable (closure cells) is declared in the head and gets a
     * FRESH cell per iteration inside the body (spec-v1.2 §For — each
     * iteration creates a fresh binding for closures).
     */
    private void emitFor(ForStatement fs) {
        localScopes.push(new LinkedHashMap<>());
        localTypeScopes.push(new LinkedHashMap<>());
        String initCode = null;
        String loopVarDeal = null;
        String loopVarPlain = null;
        String loopVarJavaType = null;
        if (fs.init().isPresent()) {
            if (fs.init().get() instanceof ForInit.VarDecl vd) {
                VariableDeclaration decl = vd.decl();
                Type loopType = decl.typeAnnotation().isPresent()
                    ? resolveTypeNode(decl.typeAnnotation().get())
                    : typeOf(decl.initializer());
                String javaType = javaLocalType(loopType, decl.span());
                if (javaType == null) {
                    localScopes.pop();
                    localTypeScopes.pop();
                    return;
                }
                loopVarDeal = decl.name();
                loopVarJavaType = javaType;
                loopVarPlain = declareLocal(decl.name(), loopType);
                String initVal = emitTargeted(decl.initializer(),
                    loopType, false);
                if (needsBooleanBoundary(decl.initializer(), loopType)) {
                    initVal = "booleanNotNull(" + initVal + ")";
                }
                initVal = coerceNullValueCode(initVal, decl.initializer(),
                    javaType, decl.span());
                initVal = adaptIntBoundary(decl.initializer(), initVal,
                    loopType);
                initCode = javaType + " " + loopVarPlain + " = " + initVal;
            } else if (fs.init().get() instanceof ForInit.AssignExpr ae) {
                initCode = emitAssignmentCore(ae.expr());
            }
        }
        int preAfterInit = preStatements.size();
        // The loop HEADER reads the plain loop variable: a captured
        // variable's per-iteration cell is declared inside the body, so
        // the condition and update cannot reference it.
        if (loopVarPlain != null) {
            plainReadNames.add(loopVarPlain);
        }
        String condCode = "true";
        if (fs.condition().isPresent()) {
            condCode = emitExpression(fs.condition().get());
            if (needsBooleanBoundary(fs.condition().get(),
                    Type.Boolean.INSTANCE)) {
                condCode = "booleanNotNull(" + condCode + ")";
            }
        }
        int preAfterCond = preStatements.size();
        String updateCode = null;
        if (fs.update().isPresent()) {
            ExpressionNode u = fs.update().get();
            if (u instanceof AssignmentExpr ae) {
                updateCode = emitAssignmentCore(ae);
            } else {
                updateCode = emitExpression(u);
            }
        }
        if (loopVarPlain != null) {
            plainReadNames.remove(loopVarPlain);
        }
        boolean hoisted = !preStatements.isEmpty();
        boolean capturedLoopVar = loopVarPlain != null
            && isCapturedMapped(loopVarPlain);
        if (!hoisted && !capturedLoopVar) {
            emitLine("for (" + (initCode == null ? "" : initCode)
                + "; loopCond(" + condCode + "); "
                + (updateCode == null ? "" : updateCode) + ") {");
            indent++;
            loopContinueLabels.addLast(null);
            emitScopedBlock(fs.body());
            loopContinueLabels.removeLast();
            indent--;
            emitLine("}");
            localScopes.pop();
            localTypeScopes.pop();
            return;
        }
        if (!hoisted) {
            // Captured loop variable only: the plain head keeps the
            // shared Java variable; the body declares a fresh cell per
            // iteration and re-binds the DEAL name to the cell inside
            // the body scope (header reads stay on the plain variable).
            emitLine("for (" + (initCode == null ? "" : initCode)
                + "; loopCond(" + condCode + "); "
                + (updateCode == null ? "" : updateCode) + ") {");
            indent++;
            loopContinueLabels.addLast(null);
            emitCapturedLoopVarCell(fs.body(), loopVarDeal, loopVarPlain,
                loopVarJavaType);
            loopContinueLabels.removeLast();
            indent--;
            emitLine("}");
            localScopes.pop();
            localTypeScopes.pop();
            return;
        }
        // Transformed form (hoisted side effects): pre-statements are
        // flushed at their exact evaluation points — the initializer's
        // before the init assignment, the condition's per iteration
        // before the test, the update's per iteration before the update.
        if (initCode != null) {
            flushNPreStatements(preAfterInit);
            emitLine(initCode + ";");
        }
        emitLine("while (true) {");
        indent++;
        flushNPreStatements(preAfterCond - preAfterInit);
        emitLine("if (!loopCond(" + condCode + ")) { break; }");
        String contLabel = "cont$" + loopLabelCounter++;
        loopContinueLabels.addLast(contLabel);
        emitLine(contLabel + ": {");
        indent++;
        if (capturedLoopVar) {
            emitCapturedLoopVarCell(fs.body(), loopVarDeal, loopVarPlain,
                loopVarJavaType);
        } else {
            emitScopedBlock(fs.body());
        }
        indent--;
        emitLine("}");
        loopContinueLabels.removeLast();
        flushPreStatements();
        if (updateCode != null) emitLine(updateCode + ";");
        indent--;
        emitLine("}");
        localScopes.pop();
        localTypeScopes.pop();
    }

    /** Body of a loop whose captured loop variable gets a per-iteration
     * fresh cell (shared by the for and for-of emitters). */
    private void emitCapturedLoopVarCell(Block body, String dealName,
            String plainName, String javaType) {
        localScopes.push(new LinkedHashMap<>());
        localTypeScopes.push(new LinkedHashMap<>());
        localScopes.peek().put(dealName, plainName + "$c[0]");
        emitLine("final " + javaType + "[] " + plainName + "$c = { "
            + plainName + " };");
        emitScopedBlock(body);
        localScopes.pop();
        localTypeScopes.pop();
    }

    private void emitBreak(BreakStatement bs) {
        // The checker rejects break outside a loop (E2000); the nearest
        // emitted Java loop is always the nearest DEAL loop, in both the
        // plain and transformed forms.
        emitLine("break;");
    }

    private void emitContinue(ContinueStatement cs) {
        String label = loopContinueLabels.peekLast();
        if (label == null) {
            emitLine("continue;");
        } else {
            // Transformed for form: jump past the body to the re-placed
            // update (the label wraps the body block).
            emitLine("break " + label + ";");
        }
    }

    /**
     * Throw (ISSUE-0102): an Error object literal reifies as the
     * emitted {@code DealError} (code and message evaluated
     * left-to-right in literal order); a throw of an existing Error
     * value rethrows it unchanged (its code survives across catch
     * boundaries). DEAL Error values map to {@code RuntimeException}
     * (every emitted module's DealError extends it), so caught errors
     * from ANY module unify in the catch clause.
     */
    private void emitThrow(ThrowStatement th) {
        ExpressionNode e = th.expr();
        if (e instanceof ObjectLiteralExpr ol) {
            String errorCode = emitErrorLiteral(ol);
            if (errorCode.equals("null")) {
                return; // diagnostic recorded by emitErrorLiteral
            }
            flushPreStatements();
            emitLine("throw " + errorCode + ";");
            return;
        }
        String thrown = emitExpression(e);
        flushPreStatements();
        emitLine("throw (" + thrown + ");");
    }

    /**
     * Try/catch (ISSUE-0102): the try block emits as a plain Java
     * {@code try}; the catch binds the DEAL catch variable (typed
     * {@code Error}) to {@code java.lang.RuntimeException}, so DEAL
     * errors from any module — every emitted module declares its own
     * nested DealError, and each extends RuntimeException — are caught.
     * Error values crossing the catch never leak raw foreign exceptions
     * out of DEAL code.
     */
    private void emitTry(TryStatement ts) {
        emitLine("try {");
        indent++;
        emitScopedBlock(ts.tryBlock());
        indent--;
        // The catch variable is a fresh function-level binding whose
        // Java name must be visible inside the catch block; declare it
        // in a scope pushed before the catch clause text is emitted.
        localScopes.push(new LinkedHashMap<>());
        localTypeScopes.push(new LinkedHashMap<>());
        String catchName = declareLocal(ts.catchVar(),
            Types.classType("Error", ""));
        emitLine("} catch (java.lang.RuntimeException " + catchName + ") {");
        indent++;
        // ISSUE-0102 closure capture: a catch variable captured by a
        // nested function inside the catch block (collectLocalDeclarations
        // declares it, so declareLocal registered the mapped name in the
        // capture frame) lowers to a final cell at the top of the catch
        // block, exactly like a captured local or parameter: reads and
        // writes route through <name>$c[0] (localJavaName), the closure
        // references the effectively-final cell array, and the catch
        // parameter itself is never reassigned in Java — DEAL-level
        // reassignment writes the cell, so LuaJIT's shared-upvalue
        // semantics hold without a javac effectively-final rejection.
        if (isCapturedMapped(catchName)) {
            emitLine("final java.lang.RuntimeException[] " + catchName
                + "$c = { " + catchName + " };");
        }
        emitScopedBlock(ts.catchBlock());
        indent--;
        localScopes.pop();
        localTypeScopes.pop();
        emitLine("}");
    }

    /**
     * Delete (ISSUE-0102): a table field delete removes the key; a
     * class optional-field delete clears the value and the presence
     * flag. The receiver evaluates exactly once (materialized into a
     * temporary when it is not a bare identifier read).
     */
    private void emitDelete(DeleteStatement ds) {
        if (ds.target() instanceof MemberAccessExpr mae) {
            Type objType = typeOf(mae.object());
            if (objType instanceof Type.Table) {
                String obj = emitExpression(mae.object());
                flushPreStatements();
                emitLine("(" + obj + ").remove("
                    + quoteJavaString(mae.field()) + ");");
                return;
            }
            if (objType instanceof Type.Class cls
                    && !isBuiltinErrorType(cls)) {
                String f = javaName(mae.field());
                String javaType = javaLocalType(objType, ds.span());
                String obj = emitExpression(mae.object());
                if (!isPureAfterEmission(mae.object())) {
                    // The receiver is referenced twice (value clear +
                    // presence clear): materialize a side-effecting
                    // receiver so it evaluates exactly once, before both
                    // stores (spec §Operational semantics rule 1).
                    String temp = nextEvalTempName();
                    preStatements.add(new PreLine(
                        javaType + " " + temp + " = (" + javaType + ") "
                            + obj + ";", 0));
                    preStatementsDeclareTemps = true;
                    obj = temp;
                }
                flushPreStatements();
                ClassDeclaration cd = classDeclFor(cls);
                if (cd != null && cd.isJsonable()) {
                    // An optional @jsonable field delete restores the
                    // ABSENT state: the slot stores the Missing sentinel
                    // (the DECLARING module's sentinel for an imported
                    // class — the cross-module sentinel identity, never
                    // this module's).
                    String sentinel;
                    if (isLocalClassType(cls)) {
                        sentinel = "$MISSING";
                    } else {
                        String importedModule = importedClassModuleRef(cls,
                            ds.span());
                        if (importedModule == null) return;
                        sentinel = importedModule + ".$MISSING";
                    }
                    emitLine("((" + javaType + ") " + obj + ")." + f
                        + " = " + sentinel + ";");
                } else {
                    emitLine("((" + javaType + ") " + obj + ")." + f
                        + " = null;");
                    emitLine("((" + javaType + ") " + obj + ")." + f
                        + "$present = false;");
                }
                return;
            }
            unsupported("delete of " + typeName(objType)
                + " members", ds.span());
            return;
        }
        if (ds.target() instanceof IndexExpr idx
                && typeOf(idx.array()) instanceof Type.Table) {
            List<String> codes = emitOperandsInOrder(
                List.of(idx.array(), idx.index()));
            flushPreStatements();
            emitLine("$tRemove(" + codes.get(0) + ", " + codes.get(1)
                + ");");
            return;
        }
        unsupported("delete of this target shape", ds.span());
    }

    /** True when {@code c} is the builtin {@code Error} class type (the
     * checker types throw/catch values as {@code @/Error}). */
    private static boolean isBuiltinErrorType(Type.Class c) {
        return "Error".equals(c.name()) && c.modulePath().isEmpty();
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
            // A DEAL continue inside the body targets THIS while (the
            // nearest enclosing loop), never an enclosing transformed
            // for's re-placed-update label: the null frame makes
            // emitContinue emit plain `continue;`, which Java resolves
            // to the nearest Java loop — the while.
            loopContinueLabels.addLast(null);
            emitScopedBlock(ws.body());
            loopContinueLabels.removeLast();
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
        // Same continue-target guard as the plain form: `continue;`
        // re-enters the `while (true)` head, which re-runs the hoisted
        // per-iteration pre-statements and the condition test — exactly
        // LuaJIT's re-evaluation — regardless of an enclosing
        // transformed for label below the frame.
        loopContinueLabels.addLast(null);
        emitScopedBlock(ws.body());
        loopContinueLabels.removeLast();
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
                if (int32Mode && typeOf(call) instanceof Type.Int
                        && intValueCodeIsWiderOrBoxed(call)) {
                    // ISSUE-0375 D3 seam: a discarded int-typed call whose
                    // emitted code is wider/boxed is not a valid Java
                    // expression statement (the retained time expression
                    // starts with a parenthesis). Lower it to a
                    // dummy-local declaration typed by the emitted code
                    // shape — the value crosses no declared boundary, so
                    // no checkInt gate runs (never a phantom raise).
                    emitLine(intValueEmittedJavaType(call) + " "
                        + nextIgnoredName() + " = " + raw + ";");
                } else {
                    emitLine(raw + ";");
                }
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
        // The dummy-local declaration type follows the EMITTED code
        // shape: a wider/boxed int code under int32 (the retained time
        // expression, a host call result) declares its real Java type —
        // the value is discarded with no declared boundary, so no
        // checkInt gate runs here (D3: the gate is a declared-boundary
        // seam, never a silent narrowing and never a phantom raise).
        String javaType = canYieldNil(es.expr())
            ? "java.lang.Boolean"
            : (int32Mode && t instanceof Type.Int
                && intValueCodeIsWiderOrBoxed(es.expr())
                ? intValueEmittedJavaType(es.expr())
                : javaLocalType(t, es.span()));
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
            case FunctionExpr fe -> emitFunctionExpr(fe);
            case HasExpr he -> emitHas(he);
            case TemplateLiteralExpr tl -> emitTemplateLiteral(tl);
            case AwaitExpression aw -> {
                // ISSUE-0100 blocking await lowering, extended by
                // ISSUE-0099 with the await-site completion check for
                // DEAL async calls: the awaited call is a direct async
                // function call (the checker enforces both), so emitting
                // the callee call computes the completion value
                // synchronously — a DEAL async function's body already
                // blocked on ITS inner awaits and returns R directly, and
                // a HOST async function's wrapper validated the operation
                // shape (E8010), joined the CompletableFuture, and checked
                // the completion value against the declared return
                // descriptor (E8001 at this await site). The emitted
                // await applies the one completion check the blocking
                // lowering cannot prove from Java types alone: an int
                // completion routes through checkInt (E8004 — the Java
                // long representation is wider than the DEAL int safe
                // range); number/string/boolean completions are proven by
                // the emitted Java types, which spec-v1.2 §JVM backend
                // contract permits, and a null completion (a
                // null-returning async function) hoists the void call
                // into a pre-statement and yields the DEAL null like any
                // other null-typed call. No suspension point exists in
                // the emitted Java, so the checker's after-await
                // narrowing invalidation needs no codegen counterpart
                // (the type map already holds the un-narrowed types
                // here).
                String raw = emitExpression(aw.callee());
                yield typeOf(aw) instanceof Type.Int
                    ? "checkInt(" + raw + ")"
                    : raw;
            }
        };
    }

    private String emitLiteral(LiteralExpr lit) {
        return switch (lit.value()) {
            case LiteralValue.NullLiteral() -> "null";
            case LiteralValue.BooleanLiteral b -> String.valueOf(b.value());
            case LiteralValue.IntLiteral i -> {
                long v = i.value();
                if (int32Mode) {
                    // ISSUE-0394 (retained JVM route, I5): int literal
                    // emission under DEAL_V1_2_INT32 is direct — the
                    // profile-aware parser's E1036 gate (ISSUE-0392)
                    // guarantees every literal reaching the backend is
                    // inside [-2147483648, 2147483647], so the int32
                    // branch has no point-of-use literal check.
                    yield String.valueOf(v);
                }
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
        if (t instanceof Type.Class cls && isBuiltinErrorType(cls)) {
            return emitErrorLiteral(ol);
        }
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
    /** Emits a builtin Error object literal as a DealError construction
     * (ISSUE-0102): the code and message values evaluate left-to-right
     * in literal order, exactly where LuaJIT evaluates the literal's
     * fields. */
    private String emitErrorLiteral(ObjectLiteralExpr ol) {
        List<ExpressionNode> values = new ArrayList<>();
        for (Property prop : ol.properties()) {
            values.add(prop.value());
        }
        List<String> codes = emitOperandsInOrder(values);
        String codeCode = null;
        String messageCode = null;
        for (int i = 0; i < ol.properties().size(); i++) {
            if (ol.properties().get(i).name().equals("code")) {
                codeCode = codes.get(i);
            } else if (ol.properties().get(i).name().equals("message")) {
                messageCode = codes.get(i);
            }
        }
        if (codeCode == null || messageCode == null) {
            unsupported("Error literal without both code and message "
                + "fields", ol.span());
            return "null";
        }
        return "new DealError(" + codeCode + ", " + messageCode + ")";
    }

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
            if (cf.optional() && cd.isJsonable()) {
                // An optional field of an @jsonable class contributes ONE
                // Object argument: the
                // provided value (the DEAL null for an explicit null),
                // the inline-evaluated default when one is declared, or
                // the Missing sentinel for an omitted no-default field.
                // The three states stay distinguishable: absent is the
                // sentinel reference, present-null is the DEAL null.
                if (code == null && cf.defaultExpr().isPresent()) {
                    valueNode = cf.defaultExpr().get();
                    if (localDefaults) {
                        if (typeOf(valueNode) == Type.Error.INSTANCE) {
                            unsupported("default expression of field '"
                                + cf.name() + "' of class '" + cd.name()
                                + "' (unresolved default-expression type)",
                                valueNode.span());
                            code = zeroValueFor(cf.type());
                        } else {
                            code = emitExpressionFor(valueNode,
                                classFieldDeclaredType(cd, cf));
                        }
                    } else {
                        code = emitExpression(valueNode);
                    }
                }
                if (code != null && valueNode != null
                        && (localDefaults
                            || providedNodes.containsKey(cf.name()))
                        && needsBooleanBoundary(valueNode,
                            classFieldDeclaredType(cd, cf))) {
                    code = "booleanNotNull(" + code + ")";
                }
                if (code == null) {
                    // Omitted, no default: the Missing sentinel.
                    code = jsonableMissingRef(cd);
                } else if (valueNode != null) {
                    code = coerceNullValueCode(code, valueNode,
                        "java.lang.Object", valueNode.span());
                    // ISSUE-0375 D3 seam: the optional slot is a
                    // declared int boundary — a wider (time) or
                    // boxed (host) int value (provided or defaulted)
                    // crosses through the signed32 checkInt before
                    // boxing into the Object slot, so no unchecked
                    // boxed Long is ever stored and an out-of-range
                    // value raises exactly E8004.
                    code = adaptIntBoundary(valueNode, code,
                        classFieldDeclaredType(cd, cf));
                }
                args.add(code);
                continue;
            }
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
            boolean optional = cf.optional();
            boolean presence = provided.containsKey(cf.name())
                || cf.defaultExpr().isPresent();
            if (code == null && optional && !presence) {
                // A missing optional field constructs as the DEAL null,
                // not present (has() is false, reads yield null).
                code = "null";
            } else if (code == null) {
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
                // ISSUE-0375 D3 seam: a class-construction field value
                // is a declared int boundary — a wider (time) value
                // crosses through the signed32 checkInt.
                code = adaptIntBoundary(valueNode, code, declaredFieldType);
            }
            args.add(code);
            if (cf.optional()) {
                args.add(presence ? "true" : "false");
            }
        }
        return "new " + ctorExpr + "(" + String.join(", ", args) + ")";
    }

    /** A placeholder for the defensive missing-required-field branch (the
     * artifact is discarded anyway — hasErrors gates compilation). */
    private String zeroValueFor(TypeNode typeNode) {
        if (typeNode instanceof NamedType nt) {
            return switch (nt.name()) {
                case "int" -> int32Mode ? "0" : "0L";
                case "number" -> "0.0";
                case "boolean" -> "false";
                // The two-character quoted Java literal — the bare empty
                // string here once emitted `java.lang.String f = ;`
                // (an artifact javac rejected after the CLI reported
                // success).
                case "string" -> "\"\"";
                default -> "null";
            };
        }
        return "null";
    }

    /**
     * Emits a table literal as {@code new $DealRt.Table().put(k1, v1).put(k2, v2)}.
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
        StringBuilder sb = new StringBuilder("new $DealRt.Table()");
        for (int i = 0; i < ol.properties().size(); i++) {
            // ISSUE-0375 D3 seam: the Object storage is a dynamic int
            // boundary — a wider (time) int value crosses through the
            // signed32 checkInt before boxing (Integer under int32).
            String valueCode = adaptIntBoundary(valueNodes.get(i),
                codes.get(i), typeOf(valueNodes.get(i)));
            sb.append(".put(")
                .append(quoteJavaString(ol.properties().get(i).name()))
                .append(", ").append(valueCode).append(')');
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
            if (mapped != null) {
                if (plainReadNames.contains(mapped)) {
                    // The loop HEADER reads the plain variable; its
                    // per-iteration cell is declared inside the body.
                    return mapped;
                }
                // ISSUE-0102 closure capture: a cell-ified binding reads
                // (and, through the same string, writes) its cell's
                // element — <mapped>$c[0]. The cell array declaration
                // happens at the binding's declaration site
                // (emitVariable/emitFunction's parameter cells), so the
                // element access is always in scope.
                for (Set<String> frame : capturedMappedStack) {
                    if (frame.contains(mapped)) {
                        return mapped + "$c[0]";
                    }
                }
                return mapped;
            }
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
        // ISSUE-0102 closure capture: a binding captured by a nested
        // function lowers to a cell. Register the MAPPED name (unique per
        // binding, so shadowed siblings never share a cell).
        if (!capturedNamesStack.isEmpty()
                && capturedNamesStack.peek().contains(name)) {
            capturedMappedStack.peek().add(mapped);
        }
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
            // The int/number conversion intrinsics are first-class
            // function values (ISSUE-0098 slice): value uses read the
            // per-module wrapper fields emitted with the runtime support,
            // exactly like the Lua backend's top-of-chunk wrappers.
            return switch (id.name()) {
                case "int" -> "_int$fn";
                case "number" -> "_number$fn";
                default -> {
                    unsupported("intrinsic '" + id.name() + "' used as a value",
                        id.span());
                    yield "null";
                }
            };
        }
        if (sym instanceof Symbol.FunctionSymbol fs) {
            if (!moduleFunctions.containsKey(id.name())) {
                unsupported("non-module functions used as first-class values",
                    id.span());
                return "null";
            }
            // Load-time value use of a not-yet-declared function: LuaJIT
            // assigns each function value at its declaration point in
            // source order and reads the global nil for an earlier use,
            // failing at load; Java would emit an illegal forward
            // reference to the wrapper field (a class-body initializer
            // may not reference a later static field). Reject instead of
            // miscompiling. Function-body uses are fine: methods may
            // legally reference later-declared static fields, and at call
            // time every wrapper field is initialized (LuaJIT parity).
            if (currentModuleStatementIndex >= 0
                    && moduleFunctionIndices.getOrDefault(id.name(),
                        Integer.MAX_VALUE) >= currentModuleStatementIndex) {
                unsupported("module-level use of function '" + id.name()
                    + "' as a value before its declaration (LuaJIT reads "
                    + "the global nil at load; Java rejects the forward "
                    + "reference to the wrapper field)", id.span());
                return "null";
            }
            // Deferred signature shapes (array/class/nullable/table or
            // nested-function parameters or returns): emitFunction emits
            // the per-declaration wrapper field only when the signature
            // shape is supported (fnShapeName != null), so a value use
            // must be rejected with E6000 here instead of referencing a
            // field that does not exist — an artifact javac would reject
            // after the CLI reported success. Function equality/inequality
            // is the value position that reaches emitIdentifier without a
            // typed function-value boundary in between (every other
            // position gates on the deferred shape earlier), exactly
            // mirroring the emitFunction wrapper-field gate.
            if (fs.funcType() == null || fnShapeName(fs.funcType()) == null) {
                unsupported("function values whose signature contains "
                    + "arrays/classes/nullables/nested functions "
                    + "(deferred to ISSUE-0110)", id.span());
                return "null";
            }
            return javaName(id.name()) + "$fn";
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
            case Type.Int ignored -> code
                + (int32Mode ? ".intValue()" : ".longValue()");
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
                    || leftType instanceof Type.Class
                    || leftType instanceof Type.Table) {
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
            // ISSUE-0375 D3 seam: int-typed operand positions are
            // declared int boundaries — a wider (time) or boxed (host)
            // operand crosses through the signed32 checkInt before the
            // int-parameterized helper/comparison.
            left = adaptIntBoundary(bin.left(), left, Type.Int.INSTANCE);
            right = adaptIntBoundary(bin.right(), right, Type.Int.INSTANCE);
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
                case POW -> int32Mode
                    ? "numPow(" + left + ", " + right + ")"
                    : "java.lang.Math.pow(" + left + ", " + right + ")";
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

        // Function-value equality: wrapper reference identity. Reads of
        // the same declaration's wrapper field compare equal; distinct
        // wrappers (including distinct arity adapters) compare unequal —
        // exactly LuaJIT's table identity over the runtime function
        // wrappers (ISSUE-0098 slice).
        if (leftType instanceof Type.Func && rightType instanceof Type.Func) {
            return switch (op) {
                case EQ -> "(" + left + " == " + right + ")";
                case NEQ -> "(" + left + " != " + right + ")";
                default -> {
                    unsupported("operator " + op + " on function values",
                        bin.span());
                    yield "false";
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
            case Type.Int ignored -> l + (int32Mode ? ".intValue()" : ".longValue()")
                + " == " + r + (int32Mode ? ".intValue()" : ".longValue()");
            case Type.Number ignored -> l + ".doubleValue() == " + r + ".doubleValue()";
            case Type.Boolean ignored -> l + ".booleanValue() == " + r + ".booleanValue()";
            case Type.String ignored -> l + ".equals(" + r + ")";
            case Type.Bytes ignored -> "(" + l + " == " + r + ")";
            default -> "(" + l + " == " + r + ")";
        };
    }

    /** Value inequality of two non-null boxed nullable operands. */
    private String nullableNe(String l, String r, Type inner) {
        return switch (inner) {
            case Type.Int ignored -> l + (int32Mode ? ".intValue()" : ".longValue()")
                + " != " + r + (int32Mode ? ".intValue()" : ".longValue()");
            case Type.Number ignored -> l + ".doubleValue() != " + r + ".doubleValue()";
            case Type.Boolean ignored -> l + ".booleanValue() != " + r + ".booleanValue()";
            case Type.String ignored -> "(!" + l + ".equals(" + r + "))";
            case Type.Bytes ignored -> "(" + l + " != " + r + ")";
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
     * {@link #emitOperandsInOrder(List)} with a per-operand target type:
     * {@code targets.get(i)} is the contextual target of operand
     * {@code i} — for a call argument the callee's parameter type, for
     * other operand lists a direct index-read operand's read-site target
     * (an assignment RHS target, an array-literal element type, a
     * class-construction field type) or {@code null} for no target.
     * Two target consumers exist:
     * <ul>
     * <li>an operand whose static type is a narrower function type than
     * its target parameter (only arity extension is assignable here) is
     * a call argument LuaJIT's parameter boundary check rejects with
     * E8010 — the operand's VALUE is evaluated first (materialized into
     * a temporary at the argument's evaluation position) and the emitted
     * checking wrapper's construction then raises the same error after
     * any materialized later operands (see below), exactly like
     * LuaJIT's evaluate-all-arguments-then-check order;</li>
     * <li>a DIRECT index-read operand at a nullable target is emitted
     * with {@link #emitExpressionFor}, which yields the DEAL null past
     * the end instead of raising the element-typed E8001. Only direct
     * reads consume the target — an operator nested between the
     * boundary and the read types the read at its own operand position,
     * so the element-typed read stays.</li>
     * </ul>
     */
    private List<String> emitOperandsInOrder(List<ExpressionNode> nodes,
                                             List<Type> targets) {
        List<String> codes = new ArrayList<>(nodes.size());
        List<Integer> hoistStarts = new ArrayList<>(nodes.size());
        boolean[] throwsAtEval = new boolean[nodes.size()];
        for (int i = 0; i < nodes.size(); i++) {
            int before = preStatements.size();
            Type target = targets != null && i < targets.size()
                ? targets.get(i) : null;
            if (target instanceof Type.Func tf
                    && typeOf(nodes.get(i)) instanceof Type.Func af
                    && !Types.equals(tf, af)) {
                // A call argument whose static function type is narrower
                // than the parameter: the checker admits only arity
                // extension here, and LuaJIT's parameter boundary check
                // raises E8010 when the call is made — the wrapper's
                // construction raises it, at the argument's evaluation
                // position.
                if (Types.isAssignable(af, tf)
                        && af.paramTypes().size() < tf.paramTypes().size()) {
                    codes.add(emitSignatureCheckWrapper(tf, af, nodes.get(i)));
                    throwsAtEval[i] = true;
                } else {
                    unsupported("call argument function value with a "
                        + "signature not assignable to the parameter "
                        + "(checker should have rejected it)",
                        nodes.get(i).span());
                    codes.add("null");
                }
            } else {
                codes.add(emitExpressionFor(nodes.get(i), target));
            }
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
        // statements. A check-position operand (throwsAtEval) is the
        // one exception: its inline code is the E8010 raising
        // construction, which deliberately stays at the argument
        // position (see the skip inside the loop below) — its VALUE is
        // already a pre-statement temporary at the operand's own
        // evaluation position, so nothing observable is left to anchor.
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
                // A check-position operand's inline code is the RAISING
                // wrapper construction (emitSignatureCheckWrapper), and
                // its VALUE is already materialized into an
                // actual-shape temporary at the operand's own evaluation
                // position. Materializing the raising construction here
                // would (a) type the temporary with the ACTUAL wrapper
                // shape while the code is the TARGET wrapper subclass
                // (javac rejects the artifact after the CLI reported
                // success) and (b) run the raise before operand j's
                // hoisted statements — inverting LuaJIT's
                // evaluate-then-check order. The raise must stay INLINE
                // at the argument position: all pre-statements
                // (including j's hoisted statements and j's
                // check-loop-materialized remainder) flush first, so the
                // raise happens after every later argument's evaluation.
                if (throwsAtEval[i]) continue;
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
        // A check-position operand whose evaluation raises (an arity
        // signature check) must not raise before every LATER argument's
        // evaluation completes: LuaJIT evaluates all arguments left to
        // right and only then checks the parameters at callee entry, so
        // `apply(inc, mark("x", 41))` runs mark first and raises E8010
        // after it. Every later operand that is not pure after emission
        // (an inline side-effecting call, a checked arithmetic result, or
        // a hoisted call that still carries an inline remainder) is
        // materialized into a temporary appended after its own hoisted
        // statements — its full evaluation runs before the raising
        // wrapper's construction, exactly like LuaJIT. Inert operands
        // (literals, reads, fully hoisted null-typed calls) are left
        // inline: their evaluation has no observable order.
        boolean[] materializedForCheck = new boolean[nodes.size()];
        for (int i = 0; i < nodes.size(); i++) {
            if (!throwsAtEval[i]) continue;
            for (int j = i + 1; j < nodes.size(); j++) {
                if (materializedForCheck[j]) continue;
                // A later operand that is itself a check position has
                // its VALUE already materialized into an actual-shape
                // temporary at its own evaluation position
                // (emitSignatureCheckWrapper); that pre-statement
                // precedes operand i's inline raise, so operand j's
                // value evaluates before the raise — exactly LuaJIT's
                // evaluate-all-arguments-then-check order. Its INLINE
                // code is the raising construction: materializing it
                // here would (a) type the temporary with the ACTUAL
                // shape while the code is the TARGET wrapper subclass
                // (javac rejects the artifact) and (b) raise operand
                // j's check before operand i's — inverting LuaJIT's
                // parameter-order E8010 (the FIRST mismatched parameter
                // raises).
                if (throwsAtEval[j]) continue;
                if (isPureAfterEmission(nodes.get(j))) continue;
                // The temporary's Java type follows the EMITTED code
                // shape, exactly like the main materialization loop: a
                // nil-yielding index read at a nullable target carries
                // the boxed element type, never the unboxed storage
                // type.
                String javaType = materializationTempType(nodes.get(j),
                    targets == null ? null : targets.get(j));
                if (javaType == null) continue; // diagnostic already recorded
                String temp = nextEvalTempName();
                preStatements.add(new PreLine(
                    javaType + " " + temp + " = " + codes.get(j) + ";", 0));
                codes.set(j, temp);
                materializedForCheck[j] = true;
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
     * ({@code java.lang.Long} under {@code LEGACY_SAFE_INT},
     * {@code java.lang.Integer} under {@code DEAL_V1_2_INT32} for an int
     * read), never the unboxed storage type;</li>
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
        // ISSUE-0375 D3 seam: a materialized temporary's Java type
        // follows the emitted code shape — a wider/boxed int code under
        // int32 declares its real Java type, and the consuming declared
        // boundary routes the temporary through checkInt.
        if (int32Mode && t instanceof Type.Int
                && intValueCodeIsWiderOrBoxed(node)) {
            return intValueEmittedJavaType(node);
        }
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

    /**
     * The declared-int-boundary seam (ISSUE-0375 D3): under
     * {@code DEAL_V1_2_INT32} any value crossing an int-typed declared
     * boundary (variable-declaration initializer, assignment target,
     * return, call/callback argument, await completion, array element
     * write/read, table-read int target, class-construction field,
     * array-literal element, table write/literal value, binary/unary
     * operand, array index) routes through the signed32 {@code checkInt}
     * whenever the value expression's emitted Java type is wider (the
     * retained {@code emitStdlibTimeMemberCall} long expression — the
     * D4 pin), boxed (host call results), or foreign. The range gate
     * runs BEFORE any narrowing, so the narrowing inside
     * {@code checkInt} is never silent; the emission never applies a
     * bare {@code (int)} cast at a declared boundary and never leaves a
     * narrowing mismatch for javac. Under {@code LEGACY_SAFE_INT} the
     * code passes through unchanged (byte-identical base emission).
     */
    private String adaptIntBoundary(ExpressionNode e, String code,
                                    Type target) {
        if (int32Mode && isIntBoundaryTarget(target)
                && intValueCodeIsWiderOrBoxed(e)) {
            return "checkInt(" + code + ")";
        }
        return code;
    }

    /** True when {@code target} is an int-typed declared boundary —
     * {@code int} itself or {@code int | null} (the value crossing into
     * the nullable boxed carrier is still an int value). */
    private boolean isIntBoundaryTarget(Type target) {
        if (target instanceof Type.Int) return true;
        return target instanceof Type.Nullable n
            && n.inner() instanceof Type.Int;
    }

    /**
     * True when, under {@code DEAL_V1_2_INT32}, the emitted Java code of
     * the int-typed expression {@code e} is wider or boxed relative to
     * the declared int boundary's primitive {@code int} carrier. The
     * only such producers in the emitted surface are the retained
     * {@code std/time.nowMillis} expression ({@code long} — the
     * byte-identical D4 pin) and host-module call results
     * ({@code java.lang.Integer}, boxed at the host boundary). Every
     * other int-typed expression emits exactly {@code int} code under
     * the int32 carriers (checked arithmetic, conversions, array reads,
     * literals), so it passes through without a redundant gate.
     */
    private boolean intValueCodeIsWiderOrBoxed(ExpressionNode e) {
        if (!(e instanceof CallExpr call)
                || !(call.callee() instanceof MemberAccessExpr mae)
                || !(mae.object() instanceof IdentifierExpr id)) {
            return false;
        }
        return hostAliases.containsKey(id.name())
            || "std/time".equals(importAliases.get(id.name()));
    }

    /**
     * The emitted Java type of an int-typed expression whose code is
     * wider or boxed under {@code DEAL_V1_2_INT32} ({@code long} for the
     * retained time expression, {@code java.lang.Integer} for host call
     * results) — used by materialized temporaries and dummy-local
     * discards, whose Java declaration type must follow the emitted code
     * shape, never the declared carrier (a mismatch there is exactly the
     * javac-rejected artifact D3 forbids).
     */
    private String intValueEmittedJavaType(ExpressionNode e) {
        if (e instanceof CallExpr call
                && call.callee() instanceof MemberAccessExpr mae
                && mae.object() instanceof IdentifierExpr id) {
            if (hostAliases.containsKey(id.name())) {
                return "java.lang.Integer";
            }
            if ("std/time".equals(importAliases.get(id.name()))) {
                return "long";
            }
        }
        return "int";
    }

    /**
     * Emits an expression against an optional target type. When the
     * expression's static type is a function type assignable to a
     * DIFFERENT target function type (arity extension: the actual has
     * fewer parameters than the target), the position decides the
     * lowering — exactly like the Lua backend:
     * <ul>
     * <li>variable initializers and assignments (adapter positions) wrap
     * the value in a delegating arity-extension adapter, so the produced
     * value carries the target signature and silently ignores the extra
     * parameters;</li>
     * <li>return values and call arguments (check positions) emit the
     * runtime signature check LuaJIT performs at the return/parameter
     * boundary: the wrapper's construction raises E8010 with the exact
     * "function signature mismatch" contract.</li>
     * </ul>
     */
    private String emitTargeted(ExpressionNode e, Type target,
            boolean checkPosition) {
        if (target instanceof Type.Func tf && typeOf(e) instanceof Type.Func) {
            return emitFunctionValue(tf, (Type.Func) typeOf(e), e, checkPosition);
        }
        return emitExpressionFor(e, target);
    }

    /**
     * Emits a function value of static type {@code actual} against the
     * target signature {@code target}. Equal signatures pass the plain
     * wrapper value through; arity extension (fewer actual parameters,
     * identical prefix types and return type — the only non-equal
     * assignable shape {@code Types.isAssignable} admits) lowers to the
     * delegating adapter at adapter positions and to the E8010 signature
     * check at check positions. Anything else is a checker bug —
     * defensive E6000.
     */
    private String emitFunctionValue(Type.Func target, Type.Func actual,
            ExpressionNode value, boolean checkPosition) {
        if (Types.equals(target, actual)) {
            return emitExpression(value);
        }
        if (Types.isAssignable(actual, target)
                && actual.paramTypes().size() < target.paramTypes().size()) {
            return checkPosition
                ? emitSignatureCheckWrapper(target, actual, value)
                : emitArityAdapter(target, actual, value);
        }
        unsupported("function value with a signature not assignable to the "
            + "target signature (checker should have rejected it)",
            value.span());
        return "null";
    }

    /**
     * Emits the runtime function-signature check for a check position
     * (return value or call argument) whose static function type is a
     * narrower arity-extension shape than the declared target. The value
     * expression is evaluated FIRST — materialized into a fresh
     * effectively-final temporary declared at its evaluation position
     * (for a call argument that is the argument's position in
     * {@link #emitOperandsInOrder}'s materialization order, for a return
     * value the return's evaluation) — and only then does the emitted
     * anonymous subclass of the TARGET wrapper class's instance
     * initializer raise E8010 with LuaJIT's exact "function signature
     * mismatch: expected …, got …" contract. This reproduces LuaJIT's
     * strict evaluate-then-check order (spec §Operational semantics rule
     * 2 and the return-value contract): a side effect in the checked
     * value expression always runs — never silently dropped — and the
     * raise happens where LuaJIT's parameter/return boundary check
     * raises (after any materialized later operands). The invoke body is
     * unreachable and throws to satisfy the abstract method.
     *
     * <p>The returned construction is the operand's INLINE code and must
     * stay at the argument position: {@link #emitOperandsInOrder}'s two
     * materialization loops deliberately skip check-position operands,
     * because materializing the raising construction would type a
     * temporary with the ACTUAL wrapper shape while the code is the
     * TARGET wrapper subclass (a javac-rejected artifact after the CLI
     * reported success) and would run the raise out of parameter order
     * — LuaJIT evaluates every argument's value left to right and only
     * then raises the FIRST mismatched parameter's E8010, so with two
     * arity-mismatched function arguments the first parameter's check
     * must raise and the second operand's value must still evaluate
     * first (its own value temporary, declared here, does that).</p>
     */
    private String emitSignatureCheckWrapper(Type.Func target, Type.Func actual,
            ExpressionNode value) {
        String shape = registerWrapperShape(target);
        if (shape == null) {
            unsupported("function values whose signature contains "
                + "arrays/classes/nullables/nested functions "
                + "(deferred to ISSUE-0110)", value.span());
            return "null";
        }
        String actualShape = registerWrapperShape(actual);
        if (actualShape == null) {
            unsupported("function values whose signature contains "
                + "arrays/classes/nullables/nested functions "
                + "(deferred to ISSUE-0110)", value.span());
            return "null";
        }
        // Evaluate the value expression first (strict left-to-right /
        // evaluate-then-check). The temporary's initializer runs at this
        // operand's evaluation position — before any materialized later
        // operand and before the raising construction below — exactly
        // like LuaJIT's value evaluation preceding the boundary check.
        String valueTemp = nextFunctionValueTempName();
        preStatements.add(new PreLine(actualShape + " " + valueTemp + " = "
            + emitExpression(value) + ";", 0));
        preStatementsDeclareTemps = true;
        StringBuilder sb = new StringBuilder("new ").append(shape)
            .append("() { { if (!checkSig(")
            .append(quoteJavaString(fnDescriptor(target))).append(", ")
            .append(quoteJavaString(fnDescriptor(actual)))
            .append(")) { throw new DealError(\"E8010\", ")
            .append("\"function signature mismatch: expected ")
            .append(fnDescriptor(target)).append(", got ")
            .append(fnDescriptor(actual)).append("\"); } } @Override ");
        sb.append(javaReturnType(target.returnType(), value.span()))
            .append(" invoke(");
        for (int i = 0; i < target.paramTypes().size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(javaLocalType(target.paramTypes().get(i), value.span()))
                .append(" p").append(i);
        }
        sb.append(") { throw new java.lang.AssertionError(\"unreachable\"); } }");
        return sb.toString();
    }

    /**
     * Emits the arity-extension adapter expression: an anonymous subclass
     * of the target wrapper class whose invoke accepts the target
     * parameters, discards the extra trailing ones, and delegates to the
     * actual function with the overlapping prefix. The delegation target
     * never needs a capture:
     * <ul>
     * <li>a module function — the static method is called directly;</li>
     * <li>the int/number intrinsic — the inline conversion helper is
     * called directly;</li>
     * <li>a module field — the static field is read LIVE on every
     * invoke, exactly like LuaJIT's adapter body re-reading the
     * chunk-local binding, so a field reassigned after the adapter's
     * creation retargets the adapter;</li>
     * <li>a local/parameter — its CURRENT value is snapshotted into a
     * fresh effectively-final {@code __fn<n>} temporary declared right
     * before the adapter, and this equals LuaJIT's live read only while
     * the enclosing function body never reassigns the binding (the value
     * is then stable forever). A binding the enclosing body reassigns
     * anywhere and any non-identifier value expression (a call result,
     * which LuaJIT re-evaluates on every invoke) are E6000 until
     * ISSUE-0110 — never a silent snapshot/once-only divergence.</li>
     * </ul>
     * No lambda is ever emitted: the anonymous class body references only
     * static members or a single-assignment temporary, so javac accepts
     * the artifact.
     */
    private String emitArityAdapter(Type.Func target, Type.Func actual,
            ExpressionNode value) {
        String inner;
        if (value instanceof IdentifierExpr id) {
            String mapped = localJavaName(id.name());
            Symbol sym = symbols.resolve(id.name());
            if (mapped == null && sym instanceof Symbol.FunctionSymbol
                    && moduleFunctions.containsKey(id.name())) {
                inner = qualifiedStatic(javaName(id.name())) + "("
                    + overlappingAdapterArgs(actual.paramTypes().size()) + ")";
            } else if (mapped == null && sym instanceof Symbol.IntrinsicSymbol) {
                inner = switch (id.name()) {
                    case "int" -> "intFromNumber(p0)";
                    case "number" -> "numberFromInt(p0)";
                    default -> {
                        unsupported("intrinsic '" + id.name()
                            + "' used as a value", id.span());
                        yield "null";
                    }
                };
            } else if (moduleFieldIndices.containsKey(id.name())
                    && !shadowedByFunctionLocal(id.name())) {
                // A module field holding a function value: read the static
                // field LIVE on every invoke — exactly like LuaJIT's
                // adapter body re-reading the binding — so a reassignment
                // after the adapter's creation retargets the adapter.
                inner = (mapped != null ? mapped
                        : qualifiedStatic(javaName(id.name())))
                    + ".invoke("
                    + overlappingAdapterArgs(actual.paramTypes().size()) + ")";
            } else if (mapped != null) {
                // A local or parameter binding: the snapshot equals
                // LuaJIT's live read only while the binding never changes
                // after the adapter's creation — reject any binding the
                // enclosing function body reassigns anywhere (a Java
                // anonymous class cannot capture a reassigned local, and
                // routing every read/write through a shared mutable
                // holder cell is deferred to ISSUE-0110).
                if (capturedBindingReassignedInBody(id.name())) {
                    unsupported("an arity-extension adapter over the "
                        + "function-typed local/parameter '" + id.name()
                        + "' that the enclosing function body reassigns "
                        + "(LuaJIT's adapter reads the binding live on "
                        + "every invoke; a Java anonymous class cannot "
                        + "capture a reassigned local — deferred to "
                        + "ISSUE-0110)", id.span());
                    return "null";
                }
                inner = snapshotFunctionValue(actual, value);
            } else {
                unsupported("an arity-extension adapter over the function "
                    + "value '" + id.name() + "' that is not a module "
                    + "function, intrinsic, module field, or visible local "
                    + "binding", id.span());
                return "null";
            }
        } else {
            unsupported("an arity-extension adapter over a non-identifier "
                + "function-value expression (LuaJIT re-evaluates the "
                + "expression on every invoke; the JVM slice cannot yet "
                + "emit that — deferred to ISSUE-0110)", value.span());
            return "null";
        }
        String shape = registerWrapperShape(target);
        if (shape == null) {
            unsupported("function values whose signature contains "
                + "arrays/classes/nullables/nested functions "
                + "(deferred to ISSUE-0110)", value.span());
            return "null";
        }
        StringBuilder sb = new StringBuilder("new ").append(shape)
            .append("() { @Override ");
        sb.append(javaReturnType(target.returnType(), value.span()))
            .append(" invoke(");
        for (int i = 0; i < target.paramTypes().size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(javaLocalType(target.paramTypes().get(i), value.span()))
                .append(" p").append(i);
        }
        sb.append(") { ");
        if (target.returnType() instanceof Type.Null) {
            sb.append(inner).append("; } }");
        } else {
            sb.append("return ").append(inner).append("; } }");
        }
        return sb.toString();
    }

    /** True when some non-base scope map (a function parameter or local)
     * currently binds {@code name}, i.e. a module field of the same name
     * is shadowed and the identifier refers to the local binding. The
     * base (module-level) scope map is the deque's last element. */
    private boolean shadowedByFunctionLocal(String name) {
        for (Map<String, String> scope : localScopes) {
            if (scope == localScopes.peekLast()) break;
            if (scope.containsKey(name)) return true;
        }
        return false;
    }

    /**
     * True when the body of the function currently being emitted contains
     * an assignment to a local binding named {@code name} — the captured
     * binding itself or a same-named shadowing binding (conservative).
     * LuaJIT's adapter reads the captured binding live on every invoke,
     * so any reassignment after the adapter's creation retargets it; a
     * Java anonymous class cannot capture a reassigned local, and routing
     * every read/write of the binding through a shared mutable holder
     * cell is deferred to ISSUE-0110 — reject instead of silently
     * snapshotting a value the binding may later outlive (a binding the
     * body never reassigns has a stable value, so the snapshot equals
     * LuaJIT's live read forever).
     */
    private boolean capturedBindingReassignedInBody(String name) {
        if (currentFunctionBody == null) return false;
        Deque<Set<String>> locals = new ArrayDeque<>();
        locals.push(new LinkedHashSet<>(currentFunctionParams));
        return statementsAssignLocal(currentFunctionBody, locals, name);
    }

    private boolean statementsAssignLocal(List<StatementNode> stmts,
            Deque<Set<String>> locals, String name) {
        for (StatementNode stmt : stmts) {
            if (statementAssignsLocal(stmt, locals, name)) return true;
        }
        return false;
    }

    private boolean blockAssignsLocal(Block b, Deque<Set<String>> locals,
            String name) {
        locals.push(new LinkedHashSet<>());
        boolean found = statementsAssignLocal(b.statements(), locals, name);
        locals.pop();
        return found;
    }

    private boolean statementAssignsLocal(StatementNode stmt,
            Deque<Set<String>> locals, String name) {
        return switch (stmt) {
            case VariableDeclaration vd -> {
                if (exprAssignsLocal(vd.initializer(), locals, name)) {
                    yield true;
                }
                locals.peek().add(vd.name());
                yield false;
            }
            case ReturnStatement rs -> rs.expr().isPresent()
                && exprAssignsLocal(rs.expr().get(), locals, name);
            case ExpressionStatement es ->
                exprAssignsLocal(es.expr(), locals, name);
            case IfStatement is -> {
                if (exprAssignsLocal(is.condition(), locals, name)
                        || blockAssignsLocal(is.thenBlock(), locals, name)) {
                    yield true;
                }
                if (is.elseBranch().isPresent()) {
                    switch (is.elseBranch().get()) {
                        case Either.Left<IfStatement, Block> left -> {
                            if (statementAssignsLocal(left.value(), locals, name)) {
                                yield true;
                            }
                        }
                        case Either.Right<IfStatement, Block> right -> {
                            if (blockAssignsLocal(right.value(), locals, name)) {
                                yield true;
                            }
                        }
                    }
                }
                yield false;
            }
            case WhileStatement ws -> {
                if (exprAssignsLocal(ws.condition(), locals, name)) {
                    yield true;
                }
                yield blockAssignsLocal(ws.body(), locals, name);
            }
            case Block b -> blockAssignsLocal(b, locals, name);
            // Function declarations and unsupported kinds (for/for-of,
            // try, classes, …) are separate scopes or rejected with E6000
            // when emitted; nothing to walk here.
            default -> false;
        };
    }

    private boolean exprAssignsLocal(ExpressionNode e,
            Deque<Set<String>> locals, String name) {
        return switch (e) {
            case AssignmentExpr ae -> {
                if (ae.target() instanceof IdentifierExpr id
                        && id.name().equals(name)
                        && isLocallyBound(locals, name)) {
                    yield true;
                }
                boolean found = exprAssignsLocal(ae.value(), locals, name);
                if (!found) {
                    // A non-identifier target's sub-expressions are value
                    // positions evaluated at the assignment: `t[g = dbl]
                    // = v` or `(g = dbl).x = v` reassigns the binding
                    // exactly like a bare statement assignment.
                    found = switch (ae.target()) {
                        case IndexExpr idx ->
                            exprAssignsLocal(idx.array(), locals, name)
                                || exprAssignsLocal(idx.index(), locals, name);
                        case MemberAccessExpr mae ->
                            exprAssignsLocal(mae.object(), locals, name);
                        default -> false;
                    };
                }
                yield found;
            }
            case BinaryExpr bin -> exprAssignsLocal(bin.left(), locals, name)
                || exprAssignsLocal(bin.right(), locals, name);
            case UnaryExpr u -> exprAssignsLocal(u.expr(), locals, name);
            case CallExpr call -> {
                boolean found = exprAssignsLocal(call.callee(), locals, name);
                for (ExpressionNode arg : call.args()) {
                    if (found) break;
                    found = exprAssignsLocal(arg, locals, name);
                }
                yield found;
            }
            // An await's callee is a direct async call: its argument
            // positions are value positions exactly like any other
            // call's, so an assignment hidden inside one (`apply(g =
            // two, 1)`) reassigns the binding exactly like a bare
            // statement assignment — the adapter's snapshot would
            // silently go stale if the scan missed it (ISSUE-0099).
            case AwaitExpression aw ->
                exprAssignsLocal(aw.callee(), locals, name);
            case MemberAccessExpr mae ->
                exprAssignsLocal(mae.object(), locals, name);
            case IndexExpr idx -> exprAssignsLocal(idx.array(), locals, name)
                || exprAssignsLocal(idx.index(), locals, name);
            case ArrayLiteralExpr al -> {
                boolean found = false;
                for (ExpressionNode elem : al.elements()) {
                    if (exprAssignsLocal(elem, locals, name)) {
                        found = true;
                        break;
                    }
                }
                yield found;
            }
            // Table-literal and class-construction property values are
            // value positions evaluated at the literal/construction site:
            // an assignment hidden inside one (`let t = { x: (g = dbl)
            // }`) reassigns the binding exactly like a bare statement
            // assignment — the adapter's snapshot would silently go
            // stale if the scan missed it.
            case ObjectLiteralExpr ol -> {
                boolean found = false;
                for (Property prop : ol.properties()) {
                    if (exprAssignsLocal(prop.value(), locals, name)) {
                        found = true;
                        break;
                    }
                }
                yield found;
            }
            case TemplateLiteralExpr tl -> {
                boolean found = false;
                for (ExpressionNode part : tl.parts()) {
                    if (exprAssignsLocal(part, locals, name)) {
                        found = true;
                        break;
                    }
                }
                yield found;
            }
            // Literals and unsupported forms (rejected later) hold no writes.
            default -> false;
        };
    }

    /** Snapshots a never-reassigned local/parameter function value into
     * an effectively-final temporary (declared in the pre-statements,
     * evaluated in source order at adapter-creation time) and returns the
     * temporary's {@code .invoke(...)} delegation for the adapter body.
     * Only called after {@link #capturedBindingReassignedInBody} proved
     * the enclosing function body never reassigns the binding, so the
     * snapshot equals LuaJIT's live read forever. */
    private String snapshotFunctionValue(Type.Func actual, ExpressionNode value) {
        String shape = registerWrapperShape(actual);
        if (shape == null) {
            unsupported("function values whose signature contains "
                + "arrays/classes/nullables/nested functions "
                + "(deferred to ISSUE-0110)", value.span());
            return "null";
        }
        String tmp = nextFunctionValueTempName();
        preStatements.add(new PreLine(shape + " " + tmp + " = "
            + emitExpression(value) + ";", 0));
        preStatementsDeclareTemps = true;
        return tmp + ".invoke(" + overlappingAdapterArgs(actual.paramTypes().size())
            + ")";
    }

    /** The {@code p0, p1, …} references the adapter forwards to the
     * actual function (its overlapping parameter prefix). */
    private static String overlappingAdapterArgs(int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) sb.append(", ");
            sb.append("p").append(i);
        }
        return sb.toString();
    }

    /** A fresh function-value snapshot temporary ({@code __fn0},
     * {@code __fn1}, …). Unreachable from {@link #javaName}. */
    private String nextFunctionValueTempName() {
        return "__fn" + functionValueTempCounter++;
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
                if (t instanceof Type.Int) {
                    // ISSUE-0375 fold, re-anchored for ISSUE-0392: the
                    // profile-aware parser folds the immediate-minus
                    // spelling into the combined literal
                    // IntLiteral(-2147483648), so the full v1.2 pipeline
                    // never reaches this arm with the out-of-range half.
                    // Legacy-parsed ASTs entering the int32 backend
                    // directly still carry NEG(IntLiteral 2147483648) —
                    // fold exactly this shape into the Java int literal
                    // -2147483648 (JLS 3.10.1 admits 2147483648 only as
                    // the unary-minus operand), so the gate never sees
                    // the out-of-range half. The legacy profile keeps
                    // its byte-identical intNeg(2147483648L) emission
                    // untouched.
                    if (int32Mode && u.expr() instanceof LiteralExpr lit
                            && lit.value() instanceof LiteralValue.IntLiteral i
                            && i.value() == 2147483648L) {
                        yield "-2147483648";
                    }
                    yield "intNeg("
                        + adaptIntBoundary(u.expr(), emitExpression(u.expr()),
                            Type.Int.INSTANCE) + ")";
                }
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
            String mapped = localJavaName(id.name());
            if (mapped != null) {
                // A visible variable binding (local, parameter, or module
                // field). Function-typed bindings are indirect calls
                // (ISSUE-0098 slice): they dispatch through the wrapper
                // instance's invoke method — the same `.f(...)` shape the
                // Lua backend emits. An async function-typed binding is
                // the same shape (ISSUE-0099): `await f(args)` over a
                // typed local/parameter/module field dispatches through
                // invoke, and the await site applies the completion
                // check. Anything else is not callable.
                Type calleeType = typeOf(call.callee());
                if (calleeType instanceof Type.Func f) {
                    // Module-level (load-time) indirect calls through a
                    // function-typed field get the same use-before-
                    // declaration protection as module-level direct calls:
                    // LuaJIT evaluates the field's initializer at load and
                    // would fail on a value whose function reads a
                    // later-declared field or reaches a later-declared
                    // function, while Java would silently run the
                    // initialized static field / hoisted method.
                    if (currentModuleStatementIndex >= 0
                            && moduleFieldIndices.containsKey(id.name())) {
                        String risk = moduleIndirectCallRisk(id.name());
                        if (risk != null) {
                            unsupported(risk, call.span());
                            return "null";
                        }
                    }
                    List<String> argCodes = emitOperandsInOrder(call.args(),
                        f.paramTypes());
                    for (int i = 0; i < argCodes.size(); i++) {
                        argCodes.set(i, boundaryArgCode(
                            call.args().get(i), argCodes.get(i),
                            f.paramTypes().get(i)));
                    }
                    return mapped + ".invoke("
                        + String.join(", ", argCodes) + ")";
                }
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
            if (currentModuleStatementIndex >= 0
                    && !moduleFunctions.containsKey(id.name())) {
                // The only FunctionSymbols outside moduleFunctions are the
                // compiler-generated @jsonable helpers (C$fromJson /
                // C$toJson): LuaJIT assigns them at the END of the module
                // chunk (its deferred @jsonable pass), so a load-time
                // call reads the not-yet-assigned local and fails with a
                // nil read, while Java hoists methods and would silently
                // run.
                unsupported("module-level call of the compiler-generated "
                    + "helper '" + id.name() + "' (LuaJIT assigns the "
                    + "@jsonable helpers at the end of the module chunk, "
                    + "so a load-time call reads nil and fails; Java "
                    + "would silently run the hoisted method)", call.span());
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
                // An indirect call inside a function invoked at load
                // time: the invoked wrapper's value is not statically
                // known to the load-time guards, so the held function's
                // hazards cannot be checked (LuaJIT fails at load when
                // the invoked function reaches a not-yet-declared value;
                // Java would silently run the hoisted method) —
                // conservative rejection until ISSUE-0110.
                String indirect = transitiveIndirectCalls
                    .getOrDefault(id.name(), Set.of()).stream().findFirst()
                    .orElse(null);
                if (indirect != null) {
                    unsupported("module-level call of '" + id.name()
                        + "' whose body (transitively) invokes the "
                        + "function value '" + indirect + "' (an indirect "
                        + "call executed at load time — the held value is "
                        + "not statically known to the load-time guard; "
                        + "LuaJIT fails at load when the invoked function "
                        + "reaches a not-yet-declared value, Java would "
                        + "silently run the hoisted method)", call.span());
                    return "null";
                }
                // A function VALUE read inside a function invoked at
                // load time (ISSUE-0099): the read wrapper field is
                // assigned at its declaration point, so a read of a
                // function declared at or after the call site binds to
                // the not-yet-declared global nil under LuaJIT while
                // Java would silently read the uninitialized static
                // field's default value. Defensive like the sibling
                // guards: the v1.2 module shape E1049 gate removes
                // module-level statements, so the shape cannot reach a
                // backend today.
                String laterFnValue = laterFunctionValueRead(id.name(),
                    currentModuleStatementIndex);
                if (laterFnValue != null) {
                    unsupported("module-level call of '" + id.name()
                        + "' whose body (transitively) reads the function "
                        + "value of '" + laterFnValue + "' declared at or "
                        + "after the call site (LuaJIT assigns function "
                        + "values at their declaration point and fails at "
                        + "load with a nil read; Java would silently read "
                        + "the uninitialized static field's default)",
                        call.span());
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
        // Any other function-typed callee expression (a call or
        // assignment producing a function value): the callee evaluates
        // BEFORE every argument (spec \u00a7Operational semantics rule 1 —
        // LuaJIT evaluates the callee expression completely first), so it
        // is materialized into a fresh effectively-final temporary
        // declared at its evaluation position — after its own hoisted
        // statements and BEFORE any argument's hoisted pre-statements (a
        // hoisted side-effecting argument, an E8010-checked argument's
        // value temporary) — and the dispatch goes through the temporary:
        // `picker()(41, 999)` becomes `__fn0 = picker(); __fn0.invoke(41L,
        // 999L)`, never `picker().invoke(...)` with the argument
        // pre-statements running first. No capture is introduced (the
        // temporary is single-assignment and effectively final).
        Type calleeType = typeOf(call.callee());
        if (calleeType instanceof Type.Func f) {
            // Module-level (load-time) indirect calls through a
            // non-identifier callee (a call or assignment producing a
            // function value): the produced value is not statically
            // known to the load-time guard — LuaJIT fails at load when
            // the produced function reads or reaches a not-yet-declared
            // value (the chunk-local is nil until its declaration runs),
            // while Java would silently read the uninitialized static
            // wrapper field (a bare NullPointerException) or run the
            // hoisted method. Conservative rejection until ISSUE-0110.
            if (currentModuleStatementIndex >= 0) {
                unsupported("module-level indirect calls through "
                    + "non-identifier callees (a call-result or "
                    + "assignment-produced function value — the value "
                    + "is not statically known to the load-time guard; "
                    + "LuaJIT fails at load when the produced function "
                    + "reads or reaches a not-yet-declared value, Java "
                    + "would read the uninitialized static wrapper "
                    + "field or run the hoisted method)",
                    call.callee().span());
                return "null";
            }
            String shape = registerWrapperShape(f);
            if (shape == null) {
                unsupported("function values whose signature contains "
                    + "arrays/classes/nullables/nested functions "
                    + "(deferred to ISSUE-0110)", call.callee().span());
                return "null";
            }
            String callee = emitExpression(call.callee());
            if (callee == null) return "null"; // diagnostic already recorded
            String calleeTemp = nextFunctionValueTempName();
            preStatements.add(new PreLine(shape + " " + calleeTemp + " = "
                + callee + ";", 0));
            preStatementsDeclareTemps = true;
            List<String> argCodes = emitOperandsInOrder(call.args(),
                f.paramTypes());
            for (int i = 0; i < argCodes.size(); i++) {
                argCodes.set(i, boundaryArgCode(
                    call.args().get(i), argCodes.get(i),
                    f.paramTypes().get(i)));
            }
            return calleeTemp + ".invoke(" + String.join(", ", argCodes) + ")";
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
        if ("std/table".equals(module)) {
            // ISSUE-0102: std/table.keys(t) — the JVM backend now maps
            // tables as first-class values, so the module's only export
            // (keys(t): string[]) executes with the LuaJIT reference
            // semantics (insertion-order string keys).
            if ("keys".equals(mae.field())) {
                List<String> argCodes = emitOperandsInOrder(call.args());
                return "__tableKeys(" + argCodes.get(0) + ")";
            }
            unsupported("export '" + mae.field() + "' of std/table",
                mae.span());
            return "null";
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
        // ISSUE-0098: function values cannot cross a project-module
        // boundary — the per-signature wrapper classes are emitted per
        // module as nested classes, so a caller-module wrapper is never
        // a value of the callee module's wrapper class. A Func-typed
        // argument (a callback, an arity-extension adapter value, or a
        // conversion intrinsic) would emit an artifact javac rejects
        // after the CLI reported success, and the member-call path has
        // no function parameter targets, so the parameter-boundary
        // E8010 check the same-module call path emits would be silently
        // dropped — reject with E6000 until ISSUE-0110 emits the
        // wrapper shapes as shared top-level classes.
        for (int i = 0; i < call.args().size(); i++) {
            if (typeOf(call.args().get(i)) instanceof Type.Func) {
                unsupported("function values passed to an imported "
                    + "module call (the per-module wrapper classes "
                    + "cannot cross a module boundary — cross-module "
                    + "function values are deferred to ISSUE-0110)",
                    call.args().get(i).span());
                return "null";
            }
        }
        // A function value returned from an imported module call (used
        // as an assignment value, a call-result callee, or a discarded
        // statement value) carries the DEFINING module's wrapper class
        // — the same boundary violation.
        if (typeOf(call) instanceof Type.Func) {
            unsupported("function values returned from an imported "
                + "module call (the per-module wrapper classes cannot "
                + "cross a module boundary — cross-module function "
                + "values are deferred to ISSUE-0110)", mae.span());
            return "null";
        }
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
            sb.append(adaptIntBoundary(call.args().get(i),
                argCodes.get(i), typeOf(call.args().get(i))));
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
                .append(adaptIntBoundary(call.args().get(1), argCodes.get(1),
                    Type.Int.INSTANCE)).append(", ")
                .append(adaptIntBoundary(call.args().get(2), argCodes.get(2),
                    Type.Int.INSTANCE)).append(')');
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
            case "minInt" -> "java.lang.Math.min("
                + adaptIntBoundary(call.args().get(0), a0, Type.Int.INSTANCE)
                + ", " + adaptIntBoundary(call.args().get(1), argCodes.get(1),
                    Type.Int.INSTANCE) + ")";
            case "maxInt" -> "java.lang.Math.max("
                + adaptIntBoundary(call.args().get(0), a0, Type.Int.INSTANCE)
                + ", " + adaptIntBoundary(call.args().get(1), argCodes.get(1),
                    Type.Int.INSTANCE) + ")";
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
    /**
     * Table field write (ISSUE-0102): {@code $tPut(<obj>, "f",
     * <boxed value>)} with the receiver and value evaluated
     * left-to-right. Values box to the table's Object storage (Long /
     * Double / Boolean for the numeric primitives, plain references
     * otherwise — null-typed values store Java null, the DEAL null);
     * the generic return keeps the assignment's DEAL value, and the
     * unbox suffix restores the primitive Java type the assignment
     * expression carries in value positions.
     */
    private String emitTableWrite(MemberAccessExpr mae, ExpressionNode value) {
        Type valueType = typeOf(value);
        List<String> codes = emitOperandsInOrder(
            List.of(mae.object(), value));
        // ISSUE-0375 D3 seam: the Object storage is a dynamic int
        // boundary — a wider (time) int value crosses through the
        // signed32 checkInt before boxing (Integer under int32).
        String valueCode = adaptIntBoundary(value, codes.get(1), valueType);
        String boxed = switch (valueType) {
            case Type.Int ignored ->
                (int32Mode ? "java.lang.Integer" : "java.lang.Long")
                    + ".valueOf(" + valueCode + ")";
            case Type.Number ignored ->
                "java.lang.Double.valueOf(" + codes.get(1) + ")";
            case Type.Boolean ignored ->
                "java.lang.Boolean.valueOf(" + codes.get(1) + ")";
            case Type.Null ignored -> "(java.lang.Object) null";
            case Type.Bytes ignored -> codes.get(1);
            default -> codes.get(1);
        };
        String unbox = switch (valueType) {
            case Type.Int ignored -> int32Mode ? ".intValue()" : ".longValue()";
            case Type.Number ignored -> ".doubleValue()";
            case Type.Boolean ignored -> ".booleanValue()";
            case Type.Bytes ignored -> "";
            default -> "";
        };
        return "$tPut(" + codes.get(0) + ", "
            + quoteJavaString(mae.field()) + ", " + boxed + ")" + unbox;
    }

    /**
     * has() — an optional class field's presence. A NON-@jsonable
     * class stores the ISSUE-0102 boxed slot plus a {@code
     * <name>$present} flag; an @jsonable class stores the Missing
     * sentinel, so presence is the sentinel inequality (the DEAL null
     * is PRESENT — only the sentinel is absent). The checker
     * restricts has() to optional class fields (E4005); the receiver
     * materializes into a temporary when it is not pure after
     * emission, so an effectful receiver evaluates exactly once.
     */
    private String emitHas(HasExpr he) {
        Type objType = typeOf(he.object());
        if (objType instanceof Type.Class cls
                && !isBuiltinErrorType(cls)) {
            ClassField cf = backendClassField(cls, he.field());
            if (cf == null || !cf.optional()) {
                unsupported("has() on a non-optional field", he.span());
                return "false";
            }
            String obj = emitExpression(he.object());
            String clsRef = javaLocalType(cls, he.span());
            if (clsRef == null) return "false";
            String recv;
            if (isPureAfterEmission(he.object())) {
                recv = "(" + obj + ")";
            } else {
                String tmp = nextEvalTempName();
                preStatements.add(new PreLine(clsRef + " " + tmp
                    + " = " + obj + ";", 0));
                recv = tmp;
            }
            ClassDeclaration cd = classDeclFor(cls);
            if (cd != null && cd.isJsonable()) {
                String sentinel;
                if (isLocalClassType(cls)) {
                    sentinel = "$MISSING";
                } else {
                    String importedModule = importedClassModuleRef(cls,
                        he.span());
                    if (importedModule == null) return "false";
                    sentinel = importedModule + ".$MISSING";
                }
                return "(" + recv + "." + javaName(he.field())
                    + " != " + sentinel + ")";
            }
            return "((" + clsRef + ") " + recv + ")."
                + javaName(he.field()) + "$present";
        }
        unsupported("has() on a non-class optional field", he.span());
        return "false";
    }

    private String emitMemberAccessValue(MemberAccessExpr mae) {
        Type objType = typeOf(mae.object());
        if (objType instanceof Type.Array && "length".equals(mae.field())) {
            String obj = emitExpression(mae.object());
            // ISSUE-0375 carrier switch: array length is DEAL int —
            // primitive int under DEAL_V1_2_INT32 (Java array lengths are
            // signed 32-bit, so the narrowing is exact and silent
            // truncation is impossible), the retained long carrier under
            // LEGACY_SAFE_INT.
            return int32Mode ? obj + ".data.length"
                : "((long) " + obj + ".data.length)";
        }
        if (objType instanceof Type.Class cls && isBuiltinErrorType(cls)) {
            // ISSUE-0102: Error values are RuntimeExceptions from
            // arbitrary emitted modules; .code dispatches through the
            // reflective unwrap (the same shape the runner's reportError
            // uses) and .message reads getMessage().
            String obj = emitExpression(mae.object());
            if ("code".equals(mae.field())) {
                return "__errorCode(" + obj + ")";
            }
            if ("message".equals(mae.field())) {
                return "(" + obj + ").getMessage()";
            }
            unsupported("Error member other than code/message", mae.span());
            return "null";
        }
        if (objType instanceof Type.Class cls) {
            ClassField cf = backendClassField(cls, mae.field());
            if (cf != null && cf.optional()) {
                // An optional class field read yields T | null: absent
                // reads as the DEAL null, present values read boxed
                // (the checker already typed the read as Nullable(T)).
                // The receiver materializes into a temporary when it is
                // not pure after emission, so an effectful receiver
                // evaluates exactly once (the same single-evaluation
                // convention the constructor and operand
                // materializations follow).
                String obj = emitExpression(mae.object());
                String readJava = javaLocalType(typeOf(mae), mae.span());
                if (readJava == null) return "null";
                String clsRef = javaLocalType(cls, mae.span());
                if (clsRef == null) return "null";
                String recv;
                if (isPureAfterEmission(mae.object())) {
                    recv = "(" + obj + ")";
                } else {
                    String tmp = nextEvalTempName();
                    preStatements.add(new PreLine(clsRef + " " + tmp
                        + " = " + obj + ";", 0));
                    recv = tmp;
                }
                ClassDeclaration cd = classDeclFor(cls);
                if (cd != null && cd.isJsonable()) {
                    // Missing-sentinel storage: absent is the sentinel,
                    // the DEAL null is present — absent reads as the
                    // DEAL null, present values cast from the Object
                    // slot.
                    String sentinel;
                    if (isLocalClassType(cls)) {
                        sentinel = "$MISSING";
                    } else {
                        String importedModule = importedClassModuleRef(cls,
                            mae.span());
                        if (importedModule == null) return "null";
                        sentinel = importedModule + ".$MISSING";
                    }
                    return "(" + recv + "." + javaName(mae.field())
                        + " == " + sentinel + " ? null : (" + readJava
                        + ") " + recv + "." + javaName(mae.field()) + ")";
                }
                // ISSUE-0102 boxed slot: absent is Java null already.
                return "(" + recv + ")." + javaName(mae.field());
            }
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
                // A local class uses the per-class nullable read helper
                // in this artifact; an imported class (ISSUE-0109)
                // dispatches on the DECLARING module's helper
                // (Lib.$readOrNull$C) — never an unqualified helper
                // javac would reject.
                nilHelper = classArrayHelper(cls, "readOrNull",
                    idx.span());
            } else {
                nilHelper = boxedArrayReadHelper(element);
            }
            if (nilHelper != null) {
                List<String> codes = emitOperandsInOrder(
                    List.of(idx.array(), idx.index()));
                String indexCode = adaptIntBoundary(idx.index(),
                    codes.get(1), Type.Int.INSTANCE);
                return nilHelper + "(" + codes.get(0) + ", "
                    + indexCode + ")";
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
            // helper with the nominal check; nullable function elements
            // use the per-signature helper.
            helper = orNullArrayReadHelper(ne.inner());
            if (helper == null && ne.inner() instanceof Type.Class cls) {
                helper = classArrayHelper(cls, "readOrNull", idx.span());
            }
            if (helper == null && ne.inner() instanceof Type.Func) {
                helper = refArrayReadHelper(element, idx.span());
            }
        } else if (element instanceof Type.Class cls) {
            helper = classArrayHelper(cls, "read", idx.span());
        } else if (element instanceof Type.Array
                || element instanceof Type.Func
                || (element instanceof Type.Nullable ne
                    && ne.inner() instanceof Type.Func)) {
            helper = refArrayReadHelper(element, idx.span());
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
        String indexCode = adaptIntBoundary(idx.index(), codes.get(1),
            Type.Int.INSTANCE);
        return helper + "(" + codes.get(0) + ", " + indexCode + ")";
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
        // ISSUE-0375 D3 seam: an int-typed call/callback argument is a
        // declared int boundary — a wider (time) or boxed (host) value
        // crosses through the signed32 checkInt.
        Type intTarget = paramType != null ? paramType : typeOf(arg);
        code = adaptIntBoundary(arg, code, intTarget);
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
            case Type.Int ignored -> boxed
                + (int32Mode ? ".intValue()" : ".longValue()");
            case Type.Number ignored -> boxed + ".doubleValue()";
            case Type.Boolean ignored -> boxed + ".booleanValue()";
            case Type.Bytes ignored -> boxed;
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
            code = adaptIntBoundary(al.elements().get(i), code,
                arr.element());
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
            return "(($DealRt.Table) $check(\"table\", " + get + "))";
        }
        if (target instanceof Type.Int) {
            // ISSUE-0102: primitive table reads route through the
            // descriptor-driven seam — a Long passes (checked against
            // the int safe range), an integral in-range Double
            // converts, anything else raises E8001.
            return int32Mode
                ? "((java.lang.Integer) $check(\"int\", " + get
                    + ")).intValue()"
                : "((java.lang.Long) $check(\"int\", " + get
                    + ")).longValue()";
        }
        if (target instanceof Type.Number) {
            return "((java.lang.Double) $check(\"number\", " + get
                + ")).doubleValue()";
        }
        if (target instanceof Type.Boolean) {
            return "((java.lang.Boolean) $check(\"boolean\", " + get
                + ")).booleanValue()";
        }
        if (target instanceof Type.Func f) {
            // ISSUE-0102: a function-typed table read checks the stored
            // wrapper's runtime descriptor through the shared seam's
            // function branch ($FnValue) — a non-function or
            // wrong-signature value raises E8001.
            String shape = registerWrapperShape(f);
            if (shape == null) {
                unsupported("table field reads with target type "
                    + typeName(target), mae.span());
                return "null";
            }
            return "((" + shape + ") $check("
                + quoteJavaString(typeDescriptor(f)) + ", " + get + "))";
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
                if (argType instanceof Type.Int) {
                    // ISSUE-0375 D3 seam: the int argument is a
                    // declared int boundary — a wider (time) or
                    // boxed (host) int value routes through the
                    // signed32 checkInt before numberFromInt, so the
                    // int32 carrier overload (numberFromInt(int))
                    // never meets a narrower-incompatible Java value
                    // and an out-of-range value raises exactly E8004.
                    yield "numberFromInt("
                        + adaptIntBoundary(arg, emitted,
                            Type.Int.INSTANCE) + ")";
                }
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
            // a non-nullable target keeps the element-typed E8001 read;
            // the same target selects the arity-extension adapter when a
            // narrower function value is assigned to a wider
            // function-typed binding.
            String value = emitTargeted(ae.value(), targetType, false);
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
            value = adaptIntBoundary(ae.value(), value, targetType);
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
                    if (writeHelper == null
                            && ne.inner() instanceof Type.Class cls) {
                        writeHelper = classArrayHelper(cls, "writeOrNull",
                            ae.span());
                    }
                    if (writeHelper == null
                            && ne.inner() instanceof Type.Func) {
                        writeHelper = refArrayWriteHelper(indexType,
                            ae.span());
                    }
                } else if (indexType instanceof Type.Class cls) {
                    writeHelper = classArrayHelper(cls, "write",
                        ae.span());
                } else if (indexType instanceof Type.Array
                        || indexType instanceof Type.Func
                        || (indexType instanceof Type.Nullable ne
                            && ne.inner() instanceof Type.Func)) {
                    writeHelper = refArrayWriteHelper(indexType, ae.span());
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
            // ISSUE-0375 D3 seam: the stored element value and the
            // array index are int-typed declared boundaries — a wider
            // (time) value crosses through the signed32 checkInt, and a
            // wider index code narrows the same way instead of leaving
            // javac a long→int mismatch.
            rhs = adaptIntBoundary(ae.value(), rhs, indexType);
            String indexCode = adaptIntBoundary(idx.index(), codes.get(1),
                Type.Int.INSTANCE);
            return writeHelper + "(" + codes.get(0) + ", " + indexCode
                + ", " + rhs + ")";
        }
        if (ae.target() instanceof MemberAccessExpr mae) {
            Type objType = typeOf(mae.object());
            if (objType instanceof Type.Table) {
                // ISSUE-0102 table field write: the receiver and the
                // value evaluate left-to-right before the store (spec
                // §Operational semantics rule 1), the stored value
                // boxes to the table's Object storage, and the
                // expression keeps its DEAL value in value positions
                // (the generic $tPut returns the stored value; the
                // unbox suffix restores the primitive Java type).
                return emitTableWrite(mae, ae.value());
            }
            if (objType instanceof Type.Class) {
                Type.Class ccls = (Type.Class) objType;
                ClassField wcf = backendClassField(ccls, mae.field());
                if (wcf != null && wcf.optional()) {
                    ClassDeclaration wcd = classDeclFor(ccls);
                    if (wcd != null && wcd.isJsonable()) {
                        // A write to an OPTIONAL @jsonable class field
                        // stores the boxed value (the DEAL null for an
                        // explicit null) — the presence state is
                        // implicit: the stored value is never the
                        // Missing sentinel, so the field reads present
                        // afterwards.
                        List<String> codes = emitOperandsInOrder(
                            List.of(mae.object(), ae.value()),
                            Arrays.asList(null, typeOf(mae)));
                        String value = codes.get(1);
                        if (needsBooleanBoundary(ae.value(),
                                declaredFieldType(ccls, mae.field()))) {
                            value = "booleanNotNull(" + value + ")";
                        }
                        value = coerceNullValueCode(value, ae.value(),
                            "java.lang.Object", ae.span());
                        // The boxed Object slot is a dynamic int boundary:
                        // a wider int value narrows through checkInt
                        // before boxing (Integer storage under int32).
                        value = adaptIntBoundary(ae.value(), value,
                            declaredFieldType(ccls, mae.field()));
                        return "(" + codes.get(0) + ")."
                            + javaName(mae.field()) + " = " + value;
                    }
                }

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
                    value = adaptIntBoundary(ae.value(), value, fieldType);
                }
                // ISSUE-0102: an optional field write also sets the
                // presence flag — routed through the per-class generic
                // helper so the assignment keeps its DEAL value in value
                // positions (the helper returns the stored value).
                Type.Class cls = (Type.Class) objType;
                ClassDeclaration cd = moduleClasses.get(cls.name());
                if (cd != null) {
                    for (ClassField cf : cd.fields()) {
                        if (cf.optional() && cf.name().equals(mae.field())) {
                            return "__optSet$" + classNameForClass(cd.name())
                                + "$" + javaName(cf.name()) + "("
                                + codes.get(0) + ", " + value + ")";
                        }
                    }
                } else if (!isBuiltinErrorType(cls)) {
                    Map<String, ClassDeclaration> decls =
                        importedClasses.get(cls.modulePath());
                    ClassDeclaration icd = decls == null ? null
                        : decls.get(cls.name());
                    if (icd != null
                            && hasOptionalField(icd, mae.field())) {
                        unsupported("optional field writes on imported class '"
                            + cls.name() + "'", ae.span());
                        return "null";
                    }
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
        if (cf.optional() && !(t instanceof Type.Nullable)) {
            // ISSUE-0102: an optional field reads as its declared type |
            // null (the checker wraps the read); the Java field holds the
            // boxed/nullable reference.
            return new Type.Nullable(t);
        }
        return t;
    }

    /** True when the class declaration {@code cd} declares an optional
     * field named {@code field} — consulted for the imported-class
     * optional-write guard. */
    private static boolean hasOptionalField(ClassDeclaration cd,
            String field) {
        for (ClassField cf : cd.fields()) {
            if (cf.optional() && cf.name().equals(field)) return true;
        }
        return false;
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
                    if ("Error".equals(nt.name())
                            && sym instanceof Symbol.ClassSymbol) {
                        // ISSUE-0102: the builtin Error class type maps
                        // to RuntimeException (see javaLocalType).
                        yield new Type.Class("Error", "");
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
                // Function-type annotations (ISSUE-0098 slice), with the
                // async marker lifted by ISSUE-0099: build the internal
                // Type.Func from the primitive/string/null signature
                // surface, preserving the marker so the ISSUE-0098
                // wrapper machinery (per-signature shape classes,
                // per-declaration wrapper fields, indirect calls through
                // invoke) carries async function values unchanged.
                // Arrays, classes, nullables, and nested function types
                // are deferred to ISSUE-0110 and rejected here — never
                // silently miscompiled (DEAL v1.2 function types carry
                // no rest arm).
                List<Type> paramTypes = new ArrayList<>();
                boolean ok = true;
                for (FunctionTypeParam p : ft.params()) {
                    Type pt = resolveTypeNode(p.type());
                    if (pt == Type.Error.INSTANCE) {
                        ok = false;
                        break;
                    }
                    if (!isFunctionSignatureType(pt)) {
                        unsupported("function parameter types other than "
                            + "int/number/boolean/string/null (arrays, "
                            + "classes, nullables, and nested function types "
                            + "are deferred to ISSUE-0110)", p.type().span());
                        ok = false;
                        break;
                    }
                    paramTypes.add(pt);
                }
                Type rt = resolveTypeNode(ft.returnType());
                if (rt == Type.Error.INSTANCE) {
                    ok = false;
                } else if (!isFunctionSignatureType(rt)) {
                    unsupported("function return types other than "
                        + "int/number/boolean/string/null (arrays, classes, "
                        + "nullables, and nested function types are deferred "
                        + "to ISSUE-0110)", ft.returnType().span());
                    ok = false;
                }
                yield ok ? new Type.Func(paramTypes, rt, ft.isAsync())
                         : Type.Error.INSTANCE;
            }
        };
    }

    /** True for the types a function signature may contain in this
     * slice (primitives/string/null and — ISSUE-0102 — nested function
     * types recursively; arrays, classes, and nullables stay deferred
     * to ISSUE-0110). */
    private static boolean isFunctionSignatureType(Type t) {
        if (t instanceof Type.Int || t instanceof Type.Number
                || t instanceof Type.Boolean || t instanceof Type.String
                || t instanceof Type.Null) {
            return true;
        }
        if (t instanceof Type.Func f) {
            for (Type p : f.paramTypes()) {
                if (!isFunctionSignatureType(p)) return false;
            }
            return isFunctionSignatureType(f.returnType());
        }
        return false;
    }

    /** Java type for a local/parameter/field. {@code null} when unsupported. */
    private String javaLocalType(Type t, Span span) {
        return switch (t) {
            // ISSUE-0375 carrier switch: primitive int under
            // DEAL_V1_2_INT32 (jvm-v12-int32-bytes D1), the retained
            // long carrier under LEGACY_SAFE_INT.
            case Type.Int ignored -> int32Mode ? "int" : "long";
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
            case Type.Table ignored -> "$DealRt.Table";
            case Type.Class c -> {
                // ISSUE-0102: the builtin Error class maps to
                // java.lang.RuntimeException — every emitted module's
                // nested DealError extends it, so Error values thrown,
                // caught, returned, and stored unify across module
                // boundaries. code/message reads dispatch through the
                // reflection/accessor helpers in emitMemberAccessValue.
                if (isBuiltinErrorType(c)) {
                    yield "java.lang.RuntimeException";
                }
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
            case Type.Bytes ignored -> {
                unsupported("values of type " + typeName(t)
                    + " (bytes is unsupported — ISSUE-0158 boundary)", span);
                yield null;
            }
            case Type.Error ignored -> null;
            case Type.Func f -> {
                // Function values (ISSUE-0098 slice): a per-signature
                // wrapper class whose invoke method carries the JVM-mapped
                // signature. The JVM type system proves the parameter and
                // return checks the spec's runtime wrappers perform (the
                // spec's JVM backend contract explicitly permits this);
                // the int safe range stays enforced inside the functions.
                String shape = fnShapeName(f);
                if (shape == null) {
                    unsupported("function values whose signature contains "
                        + "arrays/classes/nullables/nested functions "
                        + "(deferred to ISSUE-0110)", span);
                    yield null;
                }
                yield registerWrapperShape(f);
            }
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
            // ISSUE-0375 carrier switch: boxed Integer at dynamic
            // boundaries under DEAL_V1_2_INT32 (jvm-v12-int32-bytes D1).
            case Type.Int ignored ->
                int32Mode ? "java.lang.Integer" : "java.lang.Long";
            case Type.Number ignored -> "java.lang.Double";
            case Type.Boolean ignored -> "java.lang.Boolean";
            case Type.String ignored -> "java.lang.String";
            case Type.Class c -> {
                // A nullable imported class (C | null where C is declared
                // by an imported module) maps to the DECLARING module's
                // generated nested class — the same reference the
                // non-nullable imported-class path emits (ISSUE-0109).
                // The @jsonable slice depends on this: the spec's
                // generated {@code C$fromJson} returns {@code C | null},
                // and an imported class's helper returns the imported
                // nullable class reference here.
                if (isLocalClassType(c)) {
                    yield localClassJavaType(c, span);
                }
                String importedModule = importedClassModuleRef(c, span);
                if (importedModule == null) yield null;
                yield importedModule + "." + classNameForClass(c.name());
            }
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
            case Type.Func f -> {
                // ISSUE-0102: a nullable function value holds the
                // wrapper reference (Java null is the DEAL null).
                String shape = fnShapeName(f);
                if (shape == null) {
                    unsupported("function values whose signature contains "
                        + "arrays/classes/nullables/nested functions "
                        + "(deferred to ISSUE-0110)", span);
                    yield null;
                }
                yield registerWrapperShape(f);
            }
            case Type.Bytes ignored -> {
                unsupported("values of type " + typeName(inner) + " | null"
                    + " (bytes is unsupported — ISSUE-0158 boundary)", span);
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
            case Type.Int ignored -> int32Mode ? "int" : "long";
            case Type.Number ignored -> "double";
            case Type.String ignored -> "java.lang.String";
            case Type.Boolean ignored -> "boolean";
            case Type.Nullable ne -> switch (ne.inner()) {
                case Type.Int ignored ->
                    int32Mode ? "java.lang.Integer" : "java.lang.Long";
                case Type.Number ignored -> "java.lang.Double";
                case Type.String ignored -> "java.lang.String";
                case Type.Boolean ignored -> "java.lang.Boolean";
                case Type.Class c -> {
                    if (localClassJavaType(c, span) == null) yield null;
                    yield "java.lang.Object";
                }
                case Type.Bytes ignored -> {
                    unsupported("arrays with element type " + typeName(element)
                        + " (bytes is unsupported — ISSUE-0158 boundary)", span);
                    yield null;
                }
                case Type.Func f -> {
                    // ISSUE-0102: nullable function elements — Object
                    // storage; null is the DEAL null element.
                    if (fnShapeName(f) == null) {
                        unsupported("function arrays whose signature "
                            + "contains arrays/classes/nullables/nested "
                            + "functions (deferred to ISSUE-0110)", span);
                        yield null;
                    }
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
                if (isLocalClassType(c)) {
                    if (localClassJavaType(c, span) == null) yield null;
                } else {
                    if (importedClassModuleRef(c, span) == null) yield null;
                }
                yield "java.lang.Object";
            }
            case Type.Bytes ignored -> {
                unsupported("arrays with element type " + typeName(element)
                    + " (bytes is unsupported — ISSUE-0158 boundary)", span);
                yield null;
            }
            case Type.Array inner -> {
                // ISSUE-0102 nested arrays: Object storage; the
                // per-element-shape helpers check each element.
                if (arrayWrapperName(inner) == null) {
                    unsupported("nested arrays with element type "
                        + typeName(element), span);
                    yield null;
                }
                yield "java.lang.Object";
            }
            case Type.Func f -> {
                // ISSUE-0102 function arrays: Object storage; the
                // per-signature helpers check each element.
                if (fnShapeName(f) == null) {
                    unsupported("function arrays whose signature contains "
                        + "arrays/classes/nullables/nested functions "
                        + "(deferred to ISSUE-0110)", span);
                    yield null;
                }
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
                case Type.Func f -> "__RefArray";
                case Type.Bytes ignored -> null;
                default -> null;
            };
            case Type.Class c -> isLocalClassType(c)
                ? classArrayWrapperName(c.name())
                : importedArrayWrapperName(c);
            // ISSUE-0102: nested arrays (T[][]) and function arrays
            // ((...)=>T []) share the Object-storage __RefArray
            // wrapper; reads/writes route through the per-element-shape
            // helpers below, whose runtime checks prove every element.
            case Type.Array inner -> "__RefArray";
            case Type.Func f -> "__RefArray";
            case Type.Bytes ignored -> null;
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
            case Type.Bytes ignored -> null;
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
            case Type.Bytes ignored -> null;
            default -> null;
        };
    }

    /** Java reference type of a boxed read temporary for a supported
     * primitive element type (nullable, unlike the storage types). */
    private String arrayBoxedJavaType(Type element) {
        return switch (element) {
            case Type.Int ignored ->
                int32Mode ? "java.lang.Integer" : "java.lang.Long";
            case Type.Number ignored -> "java.lang.Double";
            case Type.String ignored -> "java.lang.String";
            case Type.Boolean ignored -> "java.lang.Boolean";
            case Type.Bytes ignored -> null;
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
            case Type.Bytes ignored -> null;
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
            case Type.Bytes ignored -> null;
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
            case Type.Bytes ignored -> null;
            default -> null;
        };
    }

    // =========================================================================
    // Nested-array and function-array helpers (ISSUE-0102)
    // =========================================================================

    /**
     * Per-shape helper text for nested arrays and function arrays,
     * accumulated on demand and spliced with the wrapper classes at
     * class-body level. Every helper name starts with {@code __} and is
     * keyed by the inner wrapper/signature name, both unreachable from
     * {@link #javaName}.
     */
    private final StringBuilder arrayHelpers = new StringBuilder();
    private final Set<String> emittedArrayHelpers = new LinkedHashSet<>();

    /** Read-helper name for a nested-array or function-array element
     * type, or {@code null} (with E6000 recorded) for any other shape.
     * The helper carries the bounds checks and the per-element runtime
     * proof. */
    private String refArrayReadHelper(Type element, Span span) {
        if (element instanceof Type.Array inner) {
            Type innerElem = inner.element();
            String innerWrapper = arrayWrapperName(innerElem);
            if (innerWrapper == null
                    || !(innerElem instanceof Type.Int
                        || innerElem instanceof Type.Number
                        || innerElem instanceof Type.String
                        || innerElem instanceof Type.Boolean)) {
                unsupported("nested arrays with element type "
                    + typeName(innerElem), span);
                return null;
            }
            String key = "nestedarr$" + innerWrapper;
            if (emittedArrayHelpers.add(key)) {
                emitNestedArrayHelpers(innerElem, innerWrapper);
            }
            return "__nestedRead$" + innerWrapper;
        }
        if (element instanceof Type.Nullable ne
                && ne.inner() instanceof Type.Func f) {
            String shape = fnShapeName(f);
            if (shape == null) {
                unsupported("function arrays whose signature contains "
                    + "arrays/classes/nullables/nested functions "
                    + "(deferred to ISSUE-0110)", span);
                return null;
            }
            registerWrapperShape(f);
            String key = "fnarr$" + shape;
            if (emittedArrayHelpers.add(key)) {
                emitFnArrayHelpers(f, shape, true);
            }
            return "__fnOrNullRead$" + shape;
        }
        if (element instanceof Type.Func f) {
            String shape = fnShapeName(f);
            if (shape == null) {
                unsupported("function arrays whose signature contains "
                    + "arrays/classes/nullables/nested functions "
                    + "(deferred to ISSUE-0110)", span);
                return null;
            }
            registerWrapperShape(f);
            String key = "fnarr$" + shape;
            if (emittedArrayHelpers.add(key)) {
                emitFnArrayHelpers(f, shape, false);
            }
            return "__fnRead$" + shape;
        }
        return null;
    }

    /** Write-helper name for a nested-array or function-array element
     * type, or {@code null} (with E6000 recorded) for any other shape. */
    private String refArrayWriteHelper(Type element, Span span) {
        if (element instanceof Type.Array inner) {
            Type innerElem = inner.element();
            String innerWrapper = arrayWrapperName(innerElem);
            if (innerWrapper == null
                    || !(innerElem instanceof Type.Int
                        || innerElem instanceof Type.Number
                        || innerElem instanceof Type.String
                        || innerElem instanceof Type.Boolean)) {
                unsupported("nested arrays with element type "
                    + typeName(innerElem), span);
                return null;
            }
            String key = "nestedarr$" + innerWrapper;
            if (emittedArrayHelpers.add(key)) {
                emitNestedArrayHelpers(innerElem, innerWrapper);
            }
            return "__nestedWrite$" + innerWrapper;
        }
        if (element instanceof Type.Nullable ne
                && ne.inner() instanceof Type.Func f) {
            String shape = fnShapeName(f);
            if (shape == null) {
                unsupported("function arrays whose signature contains "
                    + "arrays/classes/nullables/nested functions "
                    + "(deferred to ISSUE-0110)", span);
                return null;
            }
            registerWrapperShape(f);
            String key = "fnarr$" + shape;
            if (emittedArrayHelpers.add(key)) {
                emitFnArrayHelpers(f, shape, true);
            }
            return "__fnOrNullWrite$" + shape;
        }
        if (element instanceof Type.Func f) {
            String shape = fnShapeName(f);
            if (shape == null) {
                unsupported("function arrays whose signature contains "
                    + "arrays/classes/nullables/nested functions "
                    + "(deferred to ISSUE-0110)", span);
                return null;
            }
            registerWrapperShape(f);
            String key = "fnarr$" + shape;
            if (emittedArrayHelpers.add(key)) {
                emitFnArrayHelpers(f, shape, false);
            }
            return "__fnWrite$" + shape;
        }
        return null;
    }

    /** Emits the read/write helper pair for a nested primitive array
     * ({@code int[][]} etc.): bounds checks (E8002 negative / past-end
     * append rule), the per-element wrapper proof (E8003 on a wrong
     * element shape, mirroring LuaJIT's check_array element mismatch),
     * and the in-place grow-on-append storage. */
    private void emitNestedArrayHelpers(Type inner, String innerWrapper) {
        String desc = "[" + typeDescriptor(inner) + "]";
        StringBuilder body = new StringBuilder();
        body.append("// nested array ").append(desc)
            .append(" element helpers (ISSUE-0102)\n");
        body.append("static ").append(innerWrapper)
            .append(" __nestedRead$").append(innerWrapper)
            .append("(__RefArray a, long i) {\n");
        body.append("    if (i < 0L) throw new DealError(\"E8002\", \"negative array index\");\n");
        body.append("    if (i >= (long) a.data.length) throw new DealError(\"E8001\", \"expected ").append(desc).append(", got null\");\n");
        body.append("    return (").append(innerWrapper)
            .append(") a.data[(int) i];\n");
        body.append("}\n");
        body.append("static ").append(innerWrapper)
            .append(" __nestedWrite$").append(innerWrapper)
            .append("(__RefArray a, long i, java.lang.Object v) {\n");
        body.append("    if (i < 0L || i > (long) a.data.length) throw new DealError(\"E8002\", \"array index out of bounds\");\n");
        body.append("    if (!(v instanceof ").append(innerWrapper)
            .append(")) throw new DealError(\"E8003\", \"array element type mismatch: expected ").append(desc).append("\");\n");
        body.append("    if (i == (long) a.data.length) { java.lang.Object[] nd = new java.lang.Object[a.data.length + 1]; java.lang.System.arraycopy(a.data, 0, nd, 0, a.data.length); nd[nd.length - 1] = v; a.data = nd; } else { a.data[(int) i] = v; }\n");
        body.append("    return (").append(innerWrapper).append(") v;\n");
        body.append("}\n");
        for (String line : body.toString().split("\n", -1)) {
            if (line.isEmpty()) continue;
            arrayHelpers.append("    ").append(line).append('\n');
        }
    }

    /** Emits the read/write helper pair for a function array element
     * signature ({@code orNull} accepts the DEAL null element). Reads
     * and writes prove the wrapper signature; the descriptor spelling
     * matches the wrapper's runtime descriptor. */
    private void emitFnArrayHelpers(Type.Func f, String shape,
            boolean orNull) {
        String desc = fnDescriptor(f);
        StringBuilder body = new StringBuilder();
        body.append("// function array ").append(desc)
            .append(" element helpers (ISSUE-0102)\n");
        String readName = orNull ? "__fnOrNullRead$" + shape
            : "__fnRead$" + shape;
        body.append("static ").append(shape).append(' ').append(readName)
            .append("(__RefArray a, long i) {\n");
        body.append("    if (i < 0L) throw new DealError(\"E8002\", \"negative array index\");\n");
        body.append("    if (i >= (long) a.data.length) throw new DealError(\"E8001\", \"expected ").append(desc).append(", got null\");\n");
        body.append("    java.lang.Object v = a.data[(int) i];\n");
        if (orNull) {
            body.append("    if (v == null) return null;\n");
        } else {
            body.append("    if (v == null) throw new DealError(\"E8001\", \"expected ").append(desc).append(", got null\");\n");
        }
        body.append("    if (v instanceof ").append(shape)
            .append(" fv) return fv;\n");
        body.append("    throw new DealError(\"E8001\", \"expected ")
            .append(desc).append(", got \" + $describe(v));\n");
        body.append("}\n");
        String writeName = orNull ? "__fnOrNullWrite$" + shape
            : "__fnWrite$" + shape;
        body.append("static ").append(shape).append(' ').append(writeName)
            .append("(__RefArray a, long i, java.lang.Object v) {\n");
        body.append("    if (i < 0L || i > (long) a.data.length) throw new DealError(\"E8002\", \"array index out of bounds\");\n");
        if (orNull) {
            body.append("    if (v != null && !(v instanceof ").append(shape)
                .append(")) throw new DealError(\"E8001\", \"expected ")
                .append(desc).append(", got \" + $describe(v));\n");
        } else {
            body.append("    if (!(v instanceof ").append(shape)
                .append(")) throw new DealError(\"E8001\", \"expected ")
                .append(desc).append(", got \" + $describe(v));\n");
        }
        body.append("    if (i == (long) a.data.length) { java.lang.Object[] nd = new java.lang.Object[a.data.length + 1]; java.lang.System.arraycopy(a.data, 0, nd, 0, a.data.length); nd[nd.length - 1] = v; a.data = nd; } else { a.data[(int) i] = v; }\n");
        body.append("    return (").append(shape).append(") v;\n");
        body.append("}\n");
        for (String line : body.toString().split("\n", -1)) {
            if (line.isEmpty()) continue;
            arrayHelpers.append("    ").append(line).append('\n');
        }
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
            case Type.Bytes ignored -> "bytes";
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

    /** Flushes exactly the first {@code n} pending pre-statements,
     * keeping the rest (the transformed for form flushes the
     * initializer's hoisted side effects before the init assignment,
     * the condition's per iteration, and the update's per iteration).
     */
    private void flushNPreStatements(int n) {
        for (int i = 0; i < n; i++) {
            PreLine line = preStatements.get(i);
            if (!line.text().isEmpty()) {
                out.append("    ".repeat(indent + line.extraIndent()));
            }
            out.append(line.text()).append('\n');
        }
        preStatements.subList(0, n).clear();
        if (preStatements.isEmpty()) {
            preStatementsDeclareTemps = false;
        }
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

    /** Records an E6000 backend diagnostic for an out-of-scope construct
     * at a real source span (D5). */
    private void unsupported(String what, Span span) {
        diagnostics.add(CompilerDiagnostic.error(DiagnosticCode.E6000,
            "JVM backend (skeleton) does not support " + what + " yet",
            span));
    }

    /** Records an E6000 backend diagnostic with the canonical synthetic
     * shape and a construct-naming anchor note (D6): the jsonable
     * conversion sites hold only the converted class or type, no source
     * span, so the anchor is synthetic with a note naming the construct. */
    private void unsupportedSynthetic(String what, String missingAnchorNote) {
        diagnostics.add(CompilerDiagnostic.syntheticError(DiagnosticCode.E6000,
            "JVM backend (skeleton) does not support " + what + " yet",
            modulePath, missingAnchorNote));
    }
}
