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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * JVM code generator (ISSUE-0091): a small but real end-to-end JVM backend
 * skeleton.
 *
 * <p>Walks the typed AST (the compiler's IR — see {@code deal-compiler-architecture-v1})
 * and emits a self-contained Java class whose static methods implement the
 * module's functions. The Java source is a real JVM artifact: it is compiled
 * by {@code javac} and executed by {@code java} in a subprocess by the
 * conformance adapter ({@code test/BackendConformanceTest.java}) and by
 * {@code CompilationOrchestrator} phase 4 when the selected backend is
 * {@link deal.codegen.Backend#JVM}.
 *
 * <p>Skeleton scope (per ISSUE-0091): functions, {@code let} locals, module
 * fields, literals, int/number/boolean/string arithmetic and comparisons,
 * {@code if}/{@code else}, {@code return}, assignment, direct calls, the
 * {@code int()}/{@code number()} conversion intrinsics, and {@code std/console}
 * output ({@code console.log}/{@code console.error} → {@code System.out}/
 * {@code System.err}). Anything outside this scope — modules, classes, arrays,
 * tables, stdlib modules other than {@code std/console}, async, host ABI,
 * {@code @jsonable}, loops, try/throw — is rejected with a backend
 * {@code E6000} diagnostic, never silently miscompiled.
 *
 * <p>JVM value mapping follows the spec's JVM backend contract
 * ({@code docs/spec-v1.1.md} §JVM value mapping): {@code int → long},
 * {@code number → double}, {@code boolean → boolean}, {@code string → String},
 * {@code null → void}/{@code Void}. The JVM's static type system proves typed
 * boundaries redundant, which the spec explicitly permits ("The JVM backend
 * may use JVM primitive types, final classes, verifier-checked bytecode …
 * to prove typed-boundary checks redundant").
 *
 * <p>The emitted class has no {@code main}: the artifact is a module class.
 * The conformance adapter compiles it together with a small runner class that
 * auto-invokes the zero-arity exported functions (mirroring the Lua harness's
 * auto-invocation of exported functions) and prints non-{@code null} results.
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

    /** Import alias → raw module path (e.g. {@code console → std/console}). */
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

    private boolean moduleLevel = false;

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
     * The module path's last segment names the generated class.
     */
    public static JvmCodegenResult generate(ProgramNode program, CheckResult result,
                                            String sourcePath, String modulePath) {
        JvmBackend backend = new JvmBackend(result.typeMap(), result.symbolTable(),
            sourcePath, modulePath);
        return backend.generateProgram(program);
    }

    /**
     * Derives the public Java class name for a module path: the last path
     * segment, sanitized to a Java identifier and capitalized. Falls back to
     * {@code "Main"} for empty or invalid segments.
     */
    public static String classNameFor(String modulePath) {
        String segment = modulePath == null ? "" : modulePath;
        int lastSep = Math.max(segment.lastIndexOf('/'), segment.lastIndexOf('.'));
        if (lastSep >= 0) segment = segment.substring(lastSep + 1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            boolean ok = Character.isJavaIdentifierPart(c);
            if (i == 0) ok = ok && Character.isJavaIdentifierStart(c);
            sb.append(ok ? c : '_');
        }
        String cleaned = sb.toString();
        if (cleaned.isEmpty() || !Character.isJavaIdentifierStart(cleaned.charAt(0))) {
            cleaned = "Main";
        }
        String result = Character.toUpperCase(cleaned.charAt(0)) + cleaned.substring(1);
        return JAVA_RESERVED.contains(result) ? result + "_" : result;
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
        for (StatementNode stmt : program.statements()) {
            if (stmt instanceof ImportDeclaration imp) {
                importAliases.put(imp.alias(), imp.modulePath());
            }
        }

        String className = classNameFor(modulePath);
        emitLine("// Generated by DEAL compiler — JVM backend (skeleton). DO NOT EDIT.");
        emitLine("// Source: " + sourcePath);
        emitLine("// Module: " + modulePath);
        emitLine();
        emitLine("public final class " + className + " {");
        indent++;
        emitRuntimeSupport();
        moduleLevel = true;
        for (StatementNode stmt : program.statements()) {
            emitStatement(stmt);
        }
        moduleLevel = false;
        indent--;
        emitLine("}");

        return new JvmCodegenResult(className, out.toString(), diagnostics);
    }

    // =========================================================================
    // Runtime support (emitted once per class)
    // =========================================================================

    private void emitRuntimeSupport() {
        emitLine("// ---- DEAL JVM skeleton runtime support ----");
        emitLine("/** DEAL runtime error: code per §Diagnostics (E8xxx). */");
        emitLine("static final class DealError extends RuntimeException {");
        emitLine("    final String code;");
        emitLine("    DealError(String code, String message) {");
        emitLine("        super(message);");
        emitLine("        this.code = code;");
        emitLine("    }");
        emitLine("}");
        emitLine("// int arithmetic: E8004 overflow, E8005 division by zero, E8006 negative exponent.");
        emitLine("static long intAdd(long a, long b) { try { return Math.addExact(a, b); } catch (ArithmeticException e) { throw new DealError(\"E8004\", \"int out of safe range\"); } }");
        emitLine("static long intSub(long a, long b) { try { return Math.subtractExact(a, b); } catch (ArithmeticException e) { throw new DealError(\"E8004\", \"int out of safe range\"); } }");
        emitLine("static long intMul(long a, long b) { try { return Math.multiplyExact(a, b); } catch (ArithmeticException e) { throw new DealError(\"E8004\", \"int out of safe range\"); } }");
        emitLine("static long intDiv(long a, long b) { if (b == 0L) throw new DealError(\"E8005\", \"integer division by zero\"); try { return a / b; } catch (ArithmeticException e) { throw new DealError(\"E8004\", \"int out of safe range\"); } }");
        emitLine("static long intMod(long a, long b) { if (b == 0L) throw new DealError(\"E8005\", \"integer division by zero\"); return a % b; }");
        emitLine("static long intPow(long a, long b) { if (b < 0L) throw new DealError(\"E8006\", \"integer exponent must be non-negative\"); double p = Math.pow((double) a, (double) b); if (p >= 9.223372036854776E18 || p < -9.223372036854776E18) throw new DealError(\"E8004\", \"int out of safe range\"); return (long) p; }");
        emitLine("static long intNeg(long a) { try { return Math.negateExact(a); } catch (ArithmeticException e) { throw new DealError(\"E8004\", \"int out of safe range\"); } }");
        emitLine("// number %: Lua-style floored modulo (a - floor(a/b)*b), unlike Java's truncated %.");
        emitLine("static double numMod(double a, double b) { return a - Math.floor(a / b) * b; }");
        emitLine("// int(v) / number(v) conversion intrinsics (E8001 bad value, E8004 out of range).");
        emitLine("static long intFromNumber(double v) { if (Double.isNaN(v)) throw new DealError(\"E8001\", \"expected int, got NaN\"); if (Double.isInfinite(v)) throw new DealError(\"E8001\", \"expected int, got infinity\"); if (v != Math.floor(v)) throw new DealError(\"E8001\", \"expected int, got non-integer number\"); if (v >= 9.223372036854776E18 || v < -9.223372036854776E18) throw new DealError(\"E8004\", \"int out of safe range\"); return (long) v; }");
        emitLine("static double numberFromInt(long v) { return (double) v; }");
        emitLine();
    }

    // =========================================================================
    // Statements
    // =========================================================================

    private void emitStatement(StatementNode stmt) {
        switch (stmt) {
            case VariableDeclaration vd -> emitVariable(vd);
            case FunctionDeclaration fd -> emitFunction(fd, false);
            case ReturnStatement rs -> emitReturn(rs);
            case IfStatement is -> emitIf(is, false);
            case Block b -> emitBlock(b);
            case ExpressionStatement es -> emitExpressionStatement(es);
            case ImportDeclaration id -> { /* recorded in the pre-scan; nothing to emit */ }
            case ExportDeclaration ed -> emitExport(ed);
            case ClassDeclaration cd ->
                unsupported("class declarations", cd.span());
            case WhileStatement ws -> unsupported("while loops", ws.span());
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

        String visibility = moduleLevel ? "static " : "";
        String javaVar = declareLocal(vd.name());
        emitLine(visibility + javaType + " " + javaVar + " = "
            + emitExpression(vd.initializer()) + ";");
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

        StringBuilder sig = new StringBuilder();
        if (exported) sig.append("public ");
        sig.append("static ").append(javaReturn).append(' ')
            .append(javaName(fd.name())).append('(');
        boolean ok = true;
        for (int i = 0; i < fd.params().size(); i++) {
            Parameter p = fd.params().get(i);
            Type pt = resolveTypeNode(p.type());
            String jt = javaLocalType(pt, p.type().span());
            if (jt == null) { ok = false; break; }
            if (i > 0) sig.append(", ");
            sig.append(jt).append(' ').append(javaName(p.name()));
        }
        if (!ok) return;
        sig.append(") {");
        emitLine(sig.toString());
        indent++;

        Map<String, String> paramScope = new LinkedHashMap<>();
        localScopes.push(paramScope);
        for (Parameter p : fd.params()) {
            declareLocal(p.name());
        }
        boolean savedModuleLevel = moduleLevel;
        moduleLevel = false;
        for (StatementNode stmt : fd.body().statements()) {
            emitStatement(stmt);
        }
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
        Type t = typeOf(rs.expr().get());
        if (t instanceof Type.Null) {
            emitLine("return;");
            return;
        }
        emitLine("return " + emitExpression(rs.expr().get()) + ";");
    }

    private void emitIf(IfStatement is, boolean leadingElse) {
        emitLine((leadingElse ? "else if (" : "if (")
            + emitExpression(is.condition()) + ") {");
        indent++;
        emitScopedBlock(is.thenBlock());
        indent--;
        if (is.elseBranch().isPresent()) {
            switch (is.elseBranch().get()) {
                case Either.Left<IfStatement, Block> left -> {
                    // Java requires the head block to be closed before "else".
                    emitLine("} else if (" + emitExpression(left.value().condition()) + ") {");
                    indent++;
                    emitScopedBlock(left.value().thenBlock());
                    indent--;
                    emitIfContinuation(left.value());
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
                    emitLine("} else if (" + emitExpression(left.value().condition()) + ") {");
                    indent++;
                    emitScopedBlock(left.value().thenBlock());
                    indent--;
                    emitIfContinuation(left.value());
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

    private void emitBlock(Block b) {
        emitLine("{");
        indent++;
        emitScopedBlock(b);
        indent--;
        emitLine("}");
    }

    private void emitScopedBlock(Block b) {
        localScopes.push(new LinkedHashMap<>());
        for (StatementNode stmt : b.statements()) {
            emitStatement(stmt);
        }
        localScopes.pop();
    }

    private void emitExpressionStatement(ExpressionStatement es) {
        if (es.expr() instanceof CallExpr call) {
            emitLine(emitCall(call) + ";");
            return;
        }
        if (es.expr() instanceof AssignmentExpr ae) {
            // Parenthesized assignment is not a Java statement.
            emitLine(emitAssignmentCore(ae) + ";");
            return;
        }
        unsupported("expression statements of this form", es.span());
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
            case CallExpr call -> emitCall(call);
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
            case TemplateLiteralExpr tl -> {
                unsupported("template literals", tl.span());
                yield "\"\"";
            }
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
            case LiteralValue.IntLiteral i -> i.value() + "L";
            case LiteralValue.NumberLiteral n -> javaDoubleLiteral(n.value());
            case LiteralValue.StringLiteral s -> quoteJavaString(s.value());
        };
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
        String left = emitExpression(bin.left());
        String right = emitExpression(bin.right());
        BinaryOp op = bin.op();

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
                case POW -> "Math.pow(" + left + ", " + right + ")";
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

        // Boolean logic and equality.
        if (leftType instanceof Type.Boolean && rightType instanceof Type.Boolean) {
            return switch (op) {
                case AND -> "(" + left + " && " + right + ")";
                case OR -> "(" + left + " || " + right + ")";
                case EQ -> "(" + left + " == " + right + ")";
                case NEQ -> "(" + left + " != " + right + ")";
                default -> {
                    unsupported("operator " + op + " on booleans", bin.span());
                    yield "false";
                }
            };
        }

        // String equality and ordering. Ordering is UTF-16 code-unit order;
        // LuaJIT orders bytewise — identical for the ASCII subset.
        if (leftType instanceof Type.String && rightType instanceof Type.String) {
            return switch (op) {
                case EQ -> "(" + left + ".equals(" + right + "))";
                case NEQ -> "(!" + left + ".equals(" + right + "))";
                case LT -> "(" + left + ".compareTo(" + right + ") < 0)";
                case LTE -> "(" + left + ".compareTo(" + right + ") <= 0)";
                case GT -> "(" + left + ".compareTo(" + right + ") > 0)";
                case GTE -> "(" + left + ".compareTo(" + right + ") >= 0)";
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
            StringBuilder sb = new StringBuilder(javaName(id.name())).append('(');
            for (int i = 0; i < call.args().size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(emitExpression(call.args().get(i)));
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
            case "log" -> "System.out";
            case "error" -> "System.err";
            default -> {
                unsupported("export '" + mae.field() + "' of std/console", mae.span());
                yield null;
            }
        };
        if (target == null) return "null";
        StringBuilder sb = new StringBuilder(target).append(".println(");
        for (int i = 0; i < call.args().size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(emitExpression(call.args().get(i)));
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
            String target = mapped != null ? mapped : javaName(id.name());
            return target + " = " + emitExpression(ae.value());
        }
        unsupported("assignment to non-variable targets", ae.span());
        return "null";
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
            case Type.String ignored -> "String";
            case Type.Null ignored -> "Void";
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

    /** Renders a double as a Java double literal. */
    private static String javaDoubleLiteral(double v) {
        // Lexer-produced numbers are finite; Java parses Double.toString output.
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
