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

import java.util.ArrayDeque;
import java.util.ArrayList;
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
 * JVM code generator (ISSUE-0091 skeleton, ISSUE-0092 first semantic
 * slice — while loops and template literals): a small but real
 * end-to-end JVM backend.
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
 * <p>Semantic slice scope (ISSUE-0091 skeleton + ISSUE-0092 slice):
 * functions, {@code let} locals, module fields, literals,
 * int/number/boolean/string arithmetic and comparisons, {@code if}/
 * {@code else}, {@code while} loops, {@code return}, assignment, direct
 * calls, template literals (lowered to string concatenation), the
 * {@code int()}/{@code number()} conversion intrinsics, and {@code std/console}
 * output ({@code console.log}/{@code console.error} → {@code System.out}/
 * {@code System.err}). Anything outside this scope — modules (any import
 * other than {@code std/console}, rejected at the import statement itself
 * even when unused), classes, arrays, tables, stdlib modules other than
 * {@code std/console}, async, host ABI, {@code @jsonable}, for/for-of
 * loops, break/continue, try/throw — is rejected with a backend
 * {@code E6000} diagnostic, never silently miscompiled.
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
 * ({@code docs/spec-v1.1.md} §JVM value mapping / §JVM backend contract —
 * the current normative spec; {@code docs/spec-v1.2.md} is a future
 * draft whose grammar is not normative for this backend):
 * {@code int → long},
 * {@code number → double}, {@code boolean → boolean}, {@code string → String},
 * {@code null → void}/{@code Void}. The JVM's static type system proves typed
 * boundaries redundant, which the spec explicitly permits ("The JVM backend
 * may use JVM primitive types, final classes, verifier-checked bytecode …
 * to prove typed-boundary checks redundant"); the int safe range is still
 * enforced at runtime because it is observable behavior (E8004) that the
 * type system cannot prove.
 *
 * <p>The emitted class has no {@code main}: the artifact is a module class.
 * The conformance adapter compiles it together with a small runner class that
 * auto-invokes the zero-arity exported functions in declaration order (the
 * Lua harness iterates {@code pairs()} — an unspecified order — so fixtures
 * must not depend on cross-backend invocation order) and prints
 * non-{@code null} results.
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
    private final List<Diagnostic> diagnostics = new ArrayList<>();
    private final StringBuilder out = new StringBuilder();
    private int indent = 0;

    /** Import alias → raw module path (e.g. {@code console → std/console}).
     * Only {@code std/console} aliases are recorded: every other import is
     * rejected with E6000 at the import statement (ISSUE-0091 rework). */
    private final Map<String, String> importAliases = new LinkedHashMap<>();

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
     */
    private final Deque<Map<String, String>> localScopes = new ArrayDeque<>();

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

    private JvmBackend(Map<ExpressionNode, Type> typeMap, SymbolTable symbols,
                       String sourcePath, String modulePath) {
        this.typeMap = typeMap;
        this.symbols = symbols;
        this.sourcePath = sourcePath;
        this.modulePath = modulePath;
        localScopes.push(new LinkedHashMap<>());
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
        return generate(program, result, sourcePath, sourcePath);
    }

    /**
     * Generates Java source for a checked module with an explicit module path.
     * The class name is derived from the full module path (collision-safe:
     * {@code app/main} and {@code sub/main} derive {@code AppMain} and
     * {@code SubMain}).
     */
    public static JvmCodegenResult generate(ProgramNode program, CheckResult result,
                                            String sourcePath, String modulePath) {
        JvmBackend backend = new JvmBackend(result.typeMap(), result.symbolTable(),
            sourcePath, modulePath);
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
        Map.entry("loopCond", List.of("boolean")));

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
                // ISSUE-0091 rework: any import other than std/console is
                // out of scope and rejected AT THE IMPORT STATEMENT itself —
                // even when unused — because the imported module's load-time
                // side effects run under LuaJIT (require time) but could
                // never run in a skeleton JVM build, and multi-module
                // projects must fail loudly rather than silently dropping
                // module code.
                if ("std/console".equals(imp.modulePath())) {
                    importAliases.put(imp.alias(), imp.modulePath());
                } else {
                    unsupported("module imports other than std/console ('"
                        + imp.modulePath() + "')", imp.span());
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
            }
        }
        computeTransitiveFieldReads();
        computeForwardFieldViolations();

        String className = classNameFor(modulePath);
        emitLine("// Generated by DEAL compiler — JVM backend (skeleton). DO NOT EDIT.");
        emitLine("// Source: " + sourcePath);
        emitLine("// Module: " + modulePath);
        emitLine();
        emitLine("public final class " + className + " {");
        indent++;
        emitRuntimeSupport();

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

        indent--;
        emitLine("}");

        return new JvmCodegenResult(className, out.toString(), diagnostics);
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
        for (Map.Entry<String, FunctionDeclaration> e : moduleFunctions.entrySet()) {
            Set<String> reads = new LinkedHashSet<>();
            Set<String> calls = new LinkedHashSet<>();
            collectBodyReferences(e.getValue(), reads, calls);
            directReads.put(e.getKey(), reads);
            directCalls.put(e.getKey(), calls);
        }
        for (String name : moduleFunctions.keySet()) {
            transitiveFieldReads.put(name, closureReads(name, directReads,
                directCalls, new LinkedHashMap<>(), new HashSet<>()));
            transitiveFunctionCalls.put(name, closureCalls(name, directCalls,
                new LinkedHashMap<>(), new HashSet<>()));
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
            Set<String> fieldReads, Set<String> calledFunctions) {
        Deque<Set<String>> locals = new ArrayDeque<>();
        Set<String> params = new LinkedHashSet<>();
        for (Parameter p : fd.params()) params.add(p.name());
        locals.push(params);
        collectStatementListRefs(fd.body().statements(), locals,
            fieldReads, calledFunctions);
    }

    private void collectStatementListRefs(List<StatementNode> stmts,
            Deque<Set<String>> locals, Set<String> fieldReads,
            Set<String> calledFunctions) {
        for (StatementNode stmt : stmts) {
            collectStatementRefs(stmt, locals, fieldReads, calledFunctions);
        }
    }

    private void collectStatementRefs(StatementNode stmt,
            Deque<Set<String>> locals, Set<String> fieldReads,
            Set<String> calledFunctions) {
        switch (stmt) {
            case VariableDeclaration vd -> {
                collectExprRefs(vd.initializer(), locals, fieldReads,
                    calledFunctions);
                locals.peek().add(vd.name());
            }
            case ReturnStatement rs -> rs.expr().ifPresent(
                e -> collectExprRefs(e, locals, fieldReads, calledFunctions));
            case ExpressionStatement es ->
                collectExprRefs(es.expr(), locals, fieldReads, calledFunctions);
            case IfStatement is -> {
                collectExprRefs(is.condition(), locals, fieldReads,
                    calledFunctions);
                collectBlockRefs(is.thenBlock(), locals, fieldReads,
                    calledFunctions);
                if (is.elseBranch().isPresent()) {
                    switch (is.elseBranch().get()) {
                        case Either.Left<IfStatement, Block> left ->
                            collectStatementRefs(left.value(), locals,
                                fieldReads, calledFunctions);
                        case Either.Right<IfStatement, Block> right ->
                            collectBlockRefs(right.value(), locals,
                                fieldReads, calledFunctions);
                    }
                }
            }
            case WhileStatement ws -> {
                collectExprRefs(ws.condition(), locals, fieldReads,
                    calledFunctions);
                collectBlockRefs(ws.body(), locals, fieldReads,
                    calledFunctions);
            }
            case Block b -> collectBlockRefs(b, locals, fieldReads,
                calledFunctions);
            // Unsupported statement kinds (for/for-of, try, nested
            // functions, classes, …) are rejected with E6000 when emitted;
            // nothing to walk here.
            default -> { }
        }
    }

    private void collectBlockRefs(Block b, Deque<Set<String>> locals,
            Set<String> fieldReads, Set<String> calledFunctions) {
        locals.push(new LinkedHashSet<>());
        collectStatementListRefs(b.statements(), locals, fieldReads,
            calledFunctions);
        locals.pop();
    }

    private void collectExprRefs(ExpressionNode e, Deque<Set<String>> locals,
            Set<String> fieldReads, Set<String> calledFunctions) {
        switch (e) {
            case IdentifierExpr id -> {
                if (!isLocallyBound(locals, id.name())
                        && symbols.resolve(id.name()) instanceof Symbol.VariableSymbol
                        && moduleFieldIndices.containsKey(id.name())) {
                    fieldReads.add(id.name());
                }
            }
            case BinaryExpr bin -> {
                collectExprRefs(bin.left(), locals, fieldReads, calledFunctions);
                collectExprRefs(bin.right(), locals, fieldReads, calledFunctions);
            }
            case UnaryExpr u ->
                collectExprRefs(u.expr(), locals, fieldReads, calledFunctions);
            case CallExpr call -> {
                if (call.callee() instanceof IdentifierExpr id
                        && symbols.resolve(id.name()) instanceof Symbol.FunctionSymbol
                        && moduleFunctions.containsKey(id.name())) {
                    calledFunctions.add(id.name());
                } else {
                    collectExprRefs(call.callee(), locals, fieldReads,
                        calledFunctions);
                }
                for (ExpressionNode arg : call.args()) {
                    collectExprRefs(arg, locals, fieldReads, calledFunctions);
                }
            }
            // Assignment targets are writes, not reads; LuaJIT and Java
            // agree on write order (the write happens, then the later field
            // initializer overwrites), so only the value side is walked.
            case AssignmentExpr ae ->
                collectExprRefs(ae.value(), locals, fieldReads, calledFunctions);
            // Template interpolations are value positions; the literal
            // parts carry no references.
            case TemplateLiteralExpr tl -> {
                for (ExpressionNode part : tl.parts()) {
                    collectExprRefs(part, locals, fieldReads, calledFunctions);
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
                }
            }
            case MemberAccessExpr mae ->
                walkDominanceExpr(mae.object(), locals, written, fnDeclIdx,
                    readViolations, writeViolations);
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
        emitLine();
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
            case ImportDeclaration id -> { /* recorded in the pre-scan; nothing to emit */ }
            case ExportDeclaration ed -> emitExport(ed);
            case ClassDeclaration cd ->
                unsupported("class declarations", cd.span());
            case WhileStatement ws -> emitWhile(ws);
            case ForStatement fs -> unsupported("for loops", fs.span());
            case ForOfStatement fos -> unsupported("for-of loops", fos.span());
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
            unsupported("class declarations", cd.span());
        } else {
            unsupported("this export form", ed.span());
        }
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
        String initializer = emitExpression(vd.initializer());
        String visibility = moduleLevel ? "static " : "";
        String javaVar = declareLocal(vd.name());
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
        if (fd.isAsync()) {
            unsupported("async functions", fd.span());
            return;
        }
        if (fd.restParam().isPresent()) {
            unsupported("rest parameters", fd.restParam().get().span());
            return;
        }
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

        StringBuilder sig = new StringBuilder();
        if (exported) sig.append("public ");
        sig.append("static ").append(javaReturn).append(' ')
            .append(javaFn).append('(');
        for (int i = 0; i < fd.params().size(); i++) {
            if (i > 0) sig.append(", ");
            sig.append(paramTypes.get(i)).append(' ')
                .append(javaName(fd.params().get(i).name()));
        }
        sig.append(") {");
        emitLine(sig.toString());
        indent++;

        Map<String, String> paramScope = new LinkedHashMap<>();
        localScopes.push(paramScope);
        for (Parameter p : fd.params()) {
            declareLocal(p.name());
        }
        boolean savedModuleLevel = moduleLevel;
        int savedModuleIndex = currentModuleStatementIndex;
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
        currentModuleStatementIndex = savedModuleIndex;
        moduleLevel = savedModuleLevel;
        localScopes.pop();

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
        if (t instanceof Type.Null) {
            if (isBareNullLiteral(e) || e instanceof IdentifierExpr) {
                // The null literal and a null-typed variable read have no
                // observable side effects — and a bare identifier is not a
                // valid Java expression statement.
                emitLine("return;");
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
            emitLine("return;");
            return;
        }
        String value = emitExpression(e);
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

    private void emitWhile(WhileStatement ws) {
        String condition = emitExpression(ws.condition());
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
        // Any other standalone expression statement (e.g. `x + 1;`) is
        // checker-accepted and LuaJIT evaluates it — an int overflow there
        // is an observable E8004. Lower it to a dummy-local declaration so
        // it is genuinely evaluated instead of being rejected or discarded.
        String value = emitExpression(es.expr());
        flushPreStatements();
        Type t = typeOf(es.expr());
        String javaType = javaLocalType(t, es.span());
        if (javaType == null) return; // diagnostic already recorded
        emitLine(javaType + " " + nextIgnoredName() + " = " + value + ";");
    }

    // =========================================================================
    // Expressions
    // =========================================================================

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
            case IndexExpr idx -> {
                unsupported("array indexing", idx.span());
                yield "null";
            }
            case ArrayLiteralExpr al -> {
                unsupported("array literals", al.span());
                yield "null";
            }
            case ObjectLiteralExpr ol -> {
                unsupported("object literals", ol.span());
                yield "null";
            }
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
                unsupported("await", aw.span());
                yield "null";
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
     * name. Shadowing declarations (a name already visible in an enclosing
     * scope) get a collision-free {@code $n} suffix, because Java rejects
     * redeclaring a visible local.
     */
    private String declareLocal(String name) {
        String base = javaName(name);
        String mapped = base;
        int n = 1;
        while (localJavaName(mapped) != null) {
            mapped = base + "$" + (n++);
        }
        localScopes.peek().put(name, mapped);
        return mapped;
    }

    private String emitIdentifier(IdentifierExpr id) {
        String mapped = localJavaName(id.name());
        if (mapped != null) {
            return mapped;
        }
        Symbol sym = symbols.resolve(id.name());
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
        // so the emitted operands are null here.
        if (leftType instanceof Type.Null && rightType instanceof Type.Null) {
            return switch (op) {
                case EQ -> "(" + left + " == " + right + ")";
                case NEQ -> "(" + left + " != " + right + ")";
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
     * Emits a boolean {@code &&}/{@code ||} whose right operand may hoist
     * side-effecting statements. When the right operand's emission hoisted
     * statements, they are guarded by the left operand so Java's
     * short-circuit semantics hold (the right operand must not be evaluated
     * when the left operand already decides the result), and the result is
     * carried in a fresh temporary:
     * <pre>
     *   boolean __sc0 = false;              // true for ||
     *   if (LEFT) { helper(); __sc0 = RIGHT; }   // if (!(LEFT)) for ||
     * </pre>
     * When the right operand has no hoisted statements, the plain
     * {@code (left && right)} form is emitted.
     */
    private String emitShortCircuit(BinaryExpr bin, boolean isAnd) {
        String left = emitExpression(bin.left());
        int mark = preStatements.size();
        String right = emitExpression(bin.right());
        if (preStatements.size() == mark) {
            return isAnd ? "(" + left + " && " + right + ")"
                         : "(" + left + " || " + right + ")";
        }
        // Extract the statements hoisted by the right operand and re-add
        // them inside a guard over the left operand, preserving their
        // relative order and evaluation order.
        List<PreLine> guarded = new ArrayList<>(
            preStatements.subList(mark, preStatements.size()));
        preStatements.subList(mark, preStatements.size()).clear();
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
     * ("g-ran" first). When operand j hoists, every earlier operand whose
     * emitted code is not pure after emission (an inline call, an inline
     * assignment, checked int arithmetic — even inside a nested
     * combination that hoisted other parts of itself) is materialized
     * into a fresh temporary assigned immediately before j's hoisted
     * statements; operands after a hoist stay inline (the flush already
     * precedes them), and operands whose emitted code is inert (literals,
     * reads, fully hoisted calls) are left alone. The declarations
     * reference no user-controlled names ({@code __t<n>} is unreachable from
     * {@link #javaName}), and {@link #preStatementsDeclareTemps} is set so
     * a module-level field initializer referencing a materialized
     * temporary is routed through a static-block assignment (a class-body
     * initializer cannot see a block-local declaration).
     */
    private List<String> emitOperandsInOrder(List<ExpressionNode> nodes) {
        List<String> codes = new ArrayList<>(nodes.size());
        List<Integer> hoistStarts = new ArrayList<>(nodes.size());
        for (int i = 0; i < nodes.size(); i++) {
            int before = preStatements.size();
            codes.add(emitExpression(nodes.get(i)));
            hoistStarts.add(preStatements.size() > before ? before : -1);
        }
        // Process hoisting operands right to left: insertions for operand j
        // land at the start of j's hoisted statements, so later operands
        // are rewritten first and earlier insertions (smaller indices) only
        // shift them rightward — never reorder them. Every earlier operand
        // whose emitted code is not pure after emission is materialized —
        // including an operand that hoisted itself but still carries an
        // inline call after its own hoisted statements (a nested
        // combination), whose inline effects would otherwise run after
        // operand j's hoisted statements.
        boolean[] materialized = new boolean[nodes.size()];
        for (int j = nodes.size() - 1; j >= 0; j--) {
            if (hoistStarts.get(j) < 0) continue;
            int insertAt = hoistStarts.get(j);
            for (int i = 0; i < j; i++) {
                if (materialized[i]) continue;
                if (isPureAfterEmission(nodes.get(i))) continue;
                Type t = typeOf(nodes.get(i));
                String javaType = javaLocalType(t, nodes.get(i).span());
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

    private String emitUnary(UnaryExpr u) {
        return switch (u.op()) {
            case NOT -> "(!" + emitExpression(u.expr()) + ")";
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
            }
            List<String> argCodes = emitOperandsInOrder(call.args());
            StringBuilder sb = new StringBuilder(javaName(id.name())).append('(');
            for (int i = 0; i < argCodes.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(argCodes.get(i));
            }
            return sb.append(')').toString();
        }
        unsupported("calls through non-identifier callees", call.span());
        return "null";
    }

    /** {@code console.log(x)} / {@code console.error(x)} on a std/console alias. */
    private String emitMemberAccessCall(MemberAccessExpr mae, CallExpr call) {
        if (!(mae.object() instanceof IdentifierExpr id)) {
            unsupported("member access on non-identifier objects", mae.span());
            return "null";
        }
        String module = importAliases.get(id.name());
        if (module == null) {
            unsupported("member access (only std/console output is supported)",
                mae.span());
            return "null";
        }
        if (!"std/console".equals(module)) {
            unsupported("module imports other than std/console", mae.span());
            return "null";
        }
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

    /** A member access used as a value (not a call) — unsupported in the skeleton. */
    private String emitMemberAccessValue(MemberAccessExpr mae) {
        unsupported("member access as a value", mae.span());
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
                unsupported("int() on " + typeName(argType), call.span());
                yield "0L";
            }
            case "number" -> {
                if (argType instanceof Type.Int) yield "numberFromInt(" + emitted + ")";
                if (argType instanceof Type.Number) yield emitted;
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
            return target + " = " + emitExpression(ae.value());
        }
        unsupported("assignment to non-variable targets", ae.span());
        return "null";
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
            // is walked here.
            case AssignmentExpr ae -> undeclaredUseIn(ae.value());
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
     * (classes, arrays, nullables, function types, tables) record an E6000
     * diagnostic and return {@code Type.Error.INSTANCE}.
     */
    private Type resolveTypeNode(TypeNode typeNode) {
        return switch (typeNode) {
            case NamedType nt -> switch (nt.name()) {
                case "null" -> Type.Null.INSTANCE;
                case "boolean" -> Type.Boolean.INSTANCE;
                case "int" -> Type.Int.INSTANCE;
                case "number" -> Type.Number.INSTANCE;
                case "string" -> Type.String.INSTANCE;
                case "table" -> {
                    unsupported("table types", nt.span());
                    yield Type.Error.INSTANCE;
                }
                default -> {
                    unsupported("type '" + nt.name() + "' (classes are not supported)",
                        nt.span());
                    yield Type.Error.INSTANCE;
                }
            };
            case QualifiedType qt -> {
                unsupported("qualified type '" + qt.moduleName() + "." + qt.typeName()
                    + "' (classes are not supported)", qt.span());
                yield Type.Error.INSTANCE;
            }
            case ArrayType at -> {
                unsupported("array types", at.span());
                yield Type.Error.INSTANCE;
            }
            case NullableType nt -> {
                unsupported("nullable types", nt.span());
                yield Type.Error.INSTANCE;
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
            case Type.Error ignored -> null;
            default -> {
                unsupported("values of type " + typeName(t), span);
                yield null;
            }
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
