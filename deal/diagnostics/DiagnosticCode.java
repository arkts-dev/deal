package deal.diagnostics;

import java.util.Map;
import java.util.HashMap;
import java.util.Optional;

/**
 * Single source of truth for all DEAL diagnostic codes.
 *
 * <p>Phase classification follows the spec's code ranges:
 * <ul>
 *   <li>{@code E1xxx} — lexical / parse errors → {@link Phase#FRONTEND}</li>
 *   <li>{@code E2xxx} — name resolution and module resolution → {@link Phase#FRONTEND}</li>
 *   <li>{@code E3xxx} — type checking → {@link Phase#FRONTEND}</li>
 *   <li>{@code E4xxx} — class shape validation → {@link Phase#FRONTEND}</li>
 *   <li>{@code E5xxx} — function signature and call validation → {@link Phase#FRONTEND}</li>
 *   <li>{@code E6xxx} — backend lowering and ABI errors → {@link Phase#BACKEND_LOWERING}</li>
 *   <li>{@code E7xxx} — declaration-file errors → {@link Phase#FRONTEND}</li>
 *   <li>{@code E8xxx} — runtime errors → {@link Phase#RUNTIME}</li>
 * </ul>
 */
public enum DiagnosticCode {

    // =========================================================================
    // E1xxx — lexical / parse errors (FRONTEND)
    // =========================================================================

    /** Unrecognized character. */
    E1001(Phase.FRONTEND, "Unrecognized character"),
    /** Malformed number literal. */
    E1002(Phase.FRONTEND, "Malformed number literal"),
    /** Unterminated string literal. */
    E1003(Phase.FRONTEND, "Unterminated string literal"),
    /** Unterminated multi-line comment. */
    E1004(Phase.FRONTEND, "Unterminated multi-line comment"),
    /** Expected '{'. */
    E1005(Phase.FRONTEND, "Expected '{'"),
    /** Expected '}'. */
    E1006(Phase.FRONTEND, "Expected '}'"),
    /** Expected identifier. */
    E1007(Phase.FRONTEND, "Expected identifier"),
    /** Expected ':'. */
    E1008(Phase.FRONTEND, "Expected ':'"),
    /** Expected '(' after function name. */
    E1009(Phase.FRONTEND, "Expected '(' after function name"),
    /** Expected ')' after function parameters. */
    E1010(Phase.FRONTEND, "Expected ')' after function parameters"),
    /** Expected ':' return type annotation. */
    E1011(Phase.FRONTEND, "Expected ':' return type annotation"),
    /** Expected '{' for function body. */
    E1012(Phase.FRONTEND, "Expected '{' for function body"),
    /** Expected '=' initializer. */
    E1013(Phase.FRONTEND, "Expected '=' initializer"),
    /** Expected '(' after control-flow keyword. */
    E1014(Phase.FRONTEND, "Expected '(' after control-flow keyword"),
    /** Expected ')' after expression. */
    E1015(Phase.FRONTEND, "Expected ')' after expression"),
    /** Expected 'if' or '{' after 'else'. */
    E1016(Phase.FRONTEND, "Expected 'if' or '{' after 'else'"),
    /** Invalid for-loop header. */
    E1017(Phase.FRONTEND, "Invalid for-loop header"),
    /** Expected ';' after for-loop clause. */
    E1018(Phase.FRONTEND, "Expected ';' after for-loop clause"),
    /** Expected '*' after 'import'. */
    E1019(Phase.FRONTEND, "Expected '*' after 'import'"),
    /** Expected 'as' after '*' in import. */
    E1020(Phase.FRONTEND, "Expected 'as' after '*' in import"),
    /** Expected import alias name. */
    E1021(Phase.FRONTEND, "Expected import alias name"),
    /** Expected 'from' after import alias. */
    E1022(Phase.FRONTEND, "Expected 'from' after import alias"),
    /** Expected string literal module path. */
    E1023(Phase.FRONTEND, "Expected string literal module path"),
    /** 'export' must be followed by 'function' or 'class'. */
    E1024(Phase.FRONTEND, "'export' must be followed by 'function' or 'class'"),
    /** Body expected after 'export'. */
    E1025(Phase.FRONTEND, "Body expected after 'export'"),
    /** Expected 'catch' after try block. */
    E1026(Phase.FRONTEND, "Expected 'catch' after try block"),
    /** Expected '(' after 'catch'. */
    E1027(Phase.FRONTEND, "Expected '(' after 'catch'"),
    /** Expected catch variable name. */
    E1028(Phase.FRONTEND, "Expected catch variable name"),
    /** Expected parameter name. */
    E1029(Phase.FRONTEND, "Expected parameter name"),
    /** Expected 'null' after '|' in nullable type. */
    E1030(Phase.FRONTEND, "Expected 'null' after '|' in nullable type"),
    /** Expected ']' in array type. */
    E1031(Phase.FRONTEND, "Expected ']' in array type"),
    /** Expected type name. */
    E1032(Phase.FRONTEND, "Expected type name"),
    /** Invalid assignment target. */
    E1033(Phase.FRONTEND, "Invalid assignment target"),
    /** Expected field name in member access. */
    E1034(Phase.FRONTEND, "Expected field name in member access"),
    /** Expected ']' to close bracket. */
    E1035(Phase.FRONTEND, "Expected ']' to close bracket"),
    /** Numeric literal out of range. */
    E1036(Phase.FRONTEND, "Numeric literal out of range"),
    /** Expected expression. */
    E1037(Phase.FRONTEND, "Expected expression"),
    /** Expected property name or '}' in object literal. */
    E1038(Phase.FRONTEND, "Expected property name or '}' in object literal"),
    /** Expected '(' after 'has'. */
    E1039(Phase.FRONTEND, "Expected '(' after 'has'"),
    /** Expected member access in has() expression. */
    E1040(Phase.FRONTEND, "Expected member access (obj.field) in has() expression"),
    /** Unexpected '}'. */
    E1041(Phase.FRONTEND, "Unexpected '}'"),
    /** Invalid template literal escape or malformed interpolation. */
    E1042(Phase.FRONTEND, "Invalid template literal"),

    /** @jsonable directive is only valid on 'export class'. */
    E1043(Phase.FRONTEND, "Invalid @jsonable directive placement"),

    // =========================================================================
    // E2xxx — name resolution and module resolution (FRONTEND)
    // =========================================================================

    /** break/continue outside loop. */
    E2000(Phase.FRONTEND, "'break' or 'continue' outside loop"),
    /** Undeclared identifier. */
    E2001(Phase.FRONTEND, "Undeclared identifier"),
    /** Redeclaration in same scope. */
    E2002(Phase.FRONTEND, "Redeclaration in same scope"),
    /** Module not found. */
    E2003(Phase.FRONTEND, "Module not found"),
    /** Export not found in module. */
    E2004(Phase.FRONTEND, "Export not found in module"),
    /** Circular import with runtime dependency. */
    E2005(Phase.FRONTEND, "Circular import with runtime dependency"),
    /** Import-shadowed by earlier declaration. */
    E2006(Phase.FRONTEND, "Import-shadowed by earlier declaration"),
    /** Declaration shadowed by earlier import. */
    E2007(Phase.FRONTEND, "Declaration shadowed by earlier import"),
    /** User-declared identifiers must not contain '$'. */
    E2008(Phase.FRONTEND, "User-declared identifiers must not contain '$'"),

    // =========================================================================
    // E3xxx — type checking (FRONTEND)
    // =========================================================================

    /** Type mismatch. */
    E3001(Phase.FRONTEND, "Type mismatch"),
    /** Cannot infer type of empty literal. */
    E3002(Phase.FRONTEND, "Cannot infer type of empty literal"),
    /** Table field read requires contextual target type. */
    E3003(Phase.FRONTEND, "Table field read requires contextual target type"),
    /** Unknown type/class/module. */
    E3004(Phase.FRONTEND, "Unknown type"),
    /** Invalid nullable type. */
    E3005(Phase.FRONTEND, "Invalid nullable type"),
    /** Incomparable types. */
    E3006(Phase.FRONTEND, "Incomparable types"),
    /** Invalid operand types. */
    E3007(Phase.FRONTEND, "Invalid operand types"),
    /** Not callable. */
    E3008(Phase.FRONTEND, "Not callable"),
    /** Call argument count mismatch. */
    E3009(Phase.FRONTEND, "Call argument count mismatch"),
    /** Invalid operand types for '+'. */
    E3010(Phase.FRONTEND, "Invalid operand types for '+'"),
    /** Array element type mismatch. */
    E3011(Phase.FRONTEND, "Array element type mismatch"),
    /** 'await' is only allowed inside an async function. */
    E3012(Phase.FRONTEND, "'await' outside async function"),
    /** 'await' must be applied to an async function call. */
    E3013(Phase.FRONTEND, "'await' on non-async call"),
    /** Direct call to async function requires 'await'. */
    E3014(Phase.FRONTEND, "Async call without 'await'"),
    /** For-of iterable must be an array or string. */
    E3015(Phase.FRONTEND, "Invalid for-of iterable type"),
    /** Template literal interpolation must be string. */
    E3016(Phase.FRONTEND, "Template literal interpolation type mismatch"),
    /** Array length is read-only (assignment/delete to array .length). */
    E3017(Phase.FRONTEND, "Array length is read-only"),

    // =========================================================================
    // E4xxx — class shape validation (FRONTEND)
    // =========================================================================

    /** Class field without default is not valid. */
    E4001(Phase.FRONTEND, "Class field without default is not valid"),
    /** Field not declared in class. */
    E4002(Phase.FRONTEND, "Field not declared in class"),
    /** Field type mismatch in class literal. */
    E4003(Phase.FRONTEND, "Field type mismatch in class literal"),
    /** Cannot delete required field. */
    E4004(Phase.FRONTEND, "Cannot delete required field"),
    /** Invalid has() argument. */
    E4005(Phase.FRONTEND, "Invalid has() argument"),

    /** Cannot declare class 'Error': it is a built-in type. */
    E4006(Phase.FRONTEND, "Cannot declare class Error"),

    /** Non-jsonable field type in @jsonable class. */
    E4007(Phase.FRONTEND, "Non-jsonable field type"),
    /** Circular @jsonable class dependency between same-module classes. */
    E4008(Phase.FRONTEND, "Circular @jsonable class dependency"),

    // =========================================================================
    // E5xxx — function signature and call validation (FRONTEND)
    // =========================================================================

    /** Function argument count/type mismatch. */
    E5001(Phase.FRONTEND, "Function argument mismatch"),
    /** Function missing return on some path. */
    E5002(Phase.FRONTEND, "Missing return on some path"),
    /** Function return type mismatch. */
    E5003(Phase.FRONTEND, "Return type mismatch"),
    /** Reverse arity (spread call argument count mismatch). */
    E5004(Phase.FRONTEND, "Spread call argument count mismatch"),

    // =========================================================================
    // E6xxx — backend lowering and ABI errors (BACKEND_LOWERING)
    // =========================================================================

    /** Unsupported statement type during code generation. */
    E6000(Phase.BACKEND_LOWERING, "Unsupported statement type"),
    /** Continue outside loop (detected during codegen). */
    E6001(Phase.BACKEND_LOWERING, "Continue outside loop"),
    /** Historical code — no longer emitted (try/break/continue, ISSUE-0011). */
    E6002(Phase.BACKEND_LOWERING, "Cannot break/continue across try boundary"),

    // =========================================================================
    // E7xxx — declaration-file errors (FRONTEND)
    // =========================================================================

    /** Declaration file cannot contain executable statement. */
    E7001(Phase.FRONTEND, "Declaration file cannot contain executable statement"),

    // =========================================================================
    // E8xxx — runtime errors (RUNTIME) — emitted from runtime.lua
    // =========================================================================

    /** Type check failed at runtime. */
    E8001(Phase.RUNTIME, "Type check failed"),
    /** Array index out of bounds. */
    E8002(Phase.RUNTIME, "Array index out of bounds"),
    /** Array element type check failed. */
    E8003(Phase.RUNTIME, "Array element type check failed"),
    /** Integer out of safe range. */
    E8004(Phase.RUNTIME, "Integer out of safe range"),
    /** Integer division by zero. */
    E8005(Phase.RUNTIME, "Integer division by zero"),
    /** Integer exponent must be non-negative. */
    E8006(Phase.RUNTIME, "Integer exponent must be non-negative"),
    /** Extra field in class instance. */
    E8007(Phase.RUNTIME, "Extra field in class instance"),
    /** Function signature mismatch at runtime. */
    E8010(Phase.RUNTIME, "Function signature mismatch"),
    /** Missing or invalid host export at module load. */
    E8011(Phase.RUNTIME, "Missing or invalid host export");

    // =========================================================================
    // Enum definition
    // =========================================================================

    /** The compiler phase in which this diagnostic is emitted. */
    public enum Phase {
        FRONTEND,
        BACKEND_LOWERING,
        RUNTIME
    }

    private static final Map<String, DiagnosticCode> BY_CODE = new HashMap<>();

    static {
        for (DiagnosticCode dc : values()) {
            BY_CODE.put(dc.name(), dc);
        }
    }

    private final Phase phase;
    private final String messageTemplate;

    DiagnosticCode(Phase phase, String messageTemplate) {
        this.phase = phase;
        this.messageTemplate = messageTemplate;
    }

    /** The phase classification for this diagnostic code. */
    public Phase phase() {
        return phase;
    }

    /** The diagnostic code string (e.g. "E1001"). */
    public String code() {
        return name();
    }

    /** A short human-readable message template describing the error category. */
    public String messageTemplate() {
        return messageTemplate;
    }

    /**
     * Looks up a DiagnosticCode by its code string.
     *
     * @param code the code string (e.g. "E1001")
     * @return the matching DiagnosticCode, or null if not found
     */
    public static DiagnosticCode fromCode(String code) {
        return BY_CODE.get(code);
    }

    /**
     * Checks whether a code string is a registered diagnostic code.
     */
    public static boolean isRegistered(String code) {
        return BY_CODE.containsKey(code);
    }
}
