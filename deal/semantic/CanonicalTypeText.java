package deal.semantic;

import deal.ast.ArrayType;
import deal.ast.FunctionType;
import deal.ast.NamedType;
import deal.ast.NullableType;
import deal.ast.QualifiedType;
import deal.ast.TypeNode;
import deal.semantic.ir.ModuleId;
import deal.types.Type;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The canonical type text of {@code deal.semantic-interface/1} (foundation
 * F2, one inseparable component): the closed structural rendering of the
 * declared-variant forms —
 *
 * <pre>{@code
 * null | boolean | int | number | string | table | bytes | T[] | T | null
 * | @modulePath/Name | (T1, …, TN) => R | async (T1, …, TN) => R
 * }</pre>
 *
 * <p>One rendering rule set is pinned twice — over checked {@link Type}
 * values (implementation entries render the module's Phase-3-corrected
 * resolved export map) and over {@link TypeNode} values (STDLIB/HOST
 * declaration entries and every field interface render the declaration
 * AST) — so both produce byte-identical forms. The eleven declared
 * checked variants render as twelve canonical forms ({@code Type.Func}
 * as both the sync and async function forms, {@link Type.Bytes} as the
 * {@code bytes} primitive name).</p>
 *
 * <p><b>Shared parenthesization rule (the one rule both renderers use):</b>
 * a function form or a nullable form is wrapped in parentheses when it is
 * an array element ({@code (…)[]}) or the inner of a nullable
 * ({@code (…) | null}); all other positions render unwrapped —
 * {@code int[]}, {@code int[][]}, {@code int[] | null},
 * {@code ((int) => int)[]}, {@code ((int) => int) | null},
 * {@code (int | null)[]}, {@code (() => null)[]}, {@code bytes[]},
 * {@code bytes[] | null}, {@code (bytes) => bytes}.</p>
 *
 * <p>The pinned {@code TypeNode → CanonicalTypeText} grammar:</p>
 * <ul>
 *   <li>{@code NamedType(n)}: {@code n ∈ {null, boolean, int, number,
 *       string, table, bytes}} → the literal name; {@code n = "Error"} →
 *       {@code @/Error} (the builtin Error class reference); any other
 *       {@code n} (a class declared in the declaring module) →
 *       {@code @<moduleId>/<n>} with {@code moduleId} = the entry's
 *       module path.</li>
 *   <li>{@code ArrayType(elem)} → {@code E[]} with {@code E = "(…)"} when
 *       the element renders as a function or nullable form, else the
 *       plain element rendering.</li>
 *   <li>{@code NullableType(inner)} → {@code I | null} with
 *       {@code I = "(…)"} when the inner renders as a function form, else
 *       the plain inner rendering.</li>
 *   <li>{@code FunctionType(params, returnType, isAsync)} →
 *       {@code (T1, …, TN) => R} or {@code async (T1, …, TN) => R} with
 *       {@code Ti}/{@code R} rendered recursively.</li>
 *   <li>{@code QualifiedType(alias, name)} →
 *       {@code @<resolvedModulePath>/<name>} where the alias resolves
 *       through the declaring module's import records joined to the
 *       orchestrator's resolved imports.</li>
 * </ul>
 *
 * <p><b>Failure contract (F2):</b> {@link Type.Error} — the internal
 * checker sentinel — has <em>no rendering</em>, and any TypeNode shape
 * outside the grammar in an index position (an unresolvable qualified-type
 * alias, a chained {@code T | null | null}, or a {@code NamedType} that is
 * neither a primitive nor a class declared in the module) is a fact
 * defect. {@link Type.Bytes} — the v1.2 bytes primitive — renders as the
 * {@code bytes} primitive name: the structural-descriptors reservation
 * held only "until the type and value semantics exist" (the bytes type
 * and the JS carrier/helper surface landed with the bytes epic), so the
 * bytes-bearing closure's module-boundary positions
 * (js-v12-int32-bytes D5: imports/exports) render byte-identically in
 * both rule sets. All out-of-grammar shapes raise {@link Defect} —
 * never an invented rendering, never a crash;
 * {@link CheckedProjectBuilder} converts the defect into E6005 through
 * the failure contract registry with
 * {@code validatorRule: INDEX_INTERNAL_ERROR_SENTINEL}.</p>
 */
public final class CanonicalTypeText {

    /**
     * The rendering-context facts of one declaring module: the entry's
     * module path (for {@code @<moduleId>/<n>} forms), the classes
     * declared in the module (the only non-primitive {@code NamedType}
     * names inside the grammar), and the import-alias → resolved-module-path
     * join (for qualified types) — the same join the signature-extraction
     * pass performs over the module's import records.
     */
    public record Context(ModuleId moduleId, Set<String> declaredClasses,
                          Map<String, String> importModulePaths) {

        public Context {
            Objects.requireNonNull(moduleId, "moduleId must not be null");
            declaredClasses = Set.copyOf(declaredClasses);
            importModulePaths = Map.copyOf(importModulePaths);
        }
    }

    /**
     * A canonical-type-text fact defect: {@link Type.Error} reached a
     * declared-type position, or a TypeNode shape fell outside the pinned
     * grammar. The builder owns the conversion into E6005
     * ({@code INDEX_INTERNAL_ERROR_SENTINEL}); this exception is internal
     * control flow, never a crash and never a rendered fallback.
     */
    public static final class Defect extends RuntimeException {

        public Defect(String message) {
            super(message);
        }
    }

    private CanonicalTypeText() {
        // Static rendering surface only; no instances.
    }

    // =========================================================================
    // Checked-Type rendering (implementation entries)
    // =========================================================================

    /**
     * Renders a checked {@link Type} value into its canonical form —
     * implementation entries render the module's Phase-3-corrected
     * resolved export map through this exact rule set. The eleven
     * declared variants render as twelve canonical forms;
     * {@link Type.Error} has no rendering and raises {@link Defect}.
     *
     * @param type the checked type; non-null
     * @return the canonical declared-type text
     * @throws Defect when {@code type} is {@link Type.Error} (the internal
     *         checker sentinel is excluded from the index by contract)
     */
    public static String render(Type type) {
        Objects.requireNonNull(type, "type must not be null");
        return switch (type) {
            case Type.Null ignored -> "null";
            case Type.Boolean ignored -> "boolean";
            case Type.Int ignored -> "int";
            case Type.Number ignored -> "number";
            case Type.String ignored -> "string";
            case Type.Table ignored -> "table";
            case Type.Bytes ignored -> "bytes";
            case Type.Error ignored -> throw new Defect(
                "Type.Error reached a declared-type position: the internal checker "
                    + "sentinel has no canonical rendering and is excluded from the index");
            case Type.Array array -> {
                String element = render(array.element());
                yield wrapInArrayPosition(element,
                    isFunctionForm(array.element()), isNullableForm(array.element()));
            }
            case Type.Nullable nullable -> {
                String inner = render(nullable.inner());
                yield isFunctionForm(nullable.inner())
                    ? "(" + inner + ") | null"
                    : inner + " | null";
            }
            case Type.Class cls -> "@" + cls.modulePath() + "/" + cls.name();
            case Type.Func func -> renderFunctionForm(
                func.paramTypes().stream().map(CanonicalTypeText::render).toList(),
                render(func.returnType()), func.isAsync());
        };
    }

    // =========================================================================
    // TypeNode rendering (declaration entries + FieldInterface)
    // =========================================================================

    /**
     * Renders a {@link TypeNode} annotation through the pinned
     * {@code TypeNode → CanonicalTypeText} grammar (foundation F2), the
     * same rendering rule set as {@link #render(Type)} with one shared
     * parenthesization rule. STDLIB/HOST declaration entries and every
     * field interface render through this exact grammar.
     *
     * @param node    the type annotation; non-null
     * @param context the declaring module's rendering context; non-null
     * @return the canonical declared-type text
     * @throws Defect for any out-of-grammar shape: an unresolvable
     *         qualified-type alias, a chained {@code T | null | null}, or
     *         a {@code NamedType} that is neither a primitive nor a class
     *         declared in the module
     */
    public static String render(TypeNode node, Context context) {
        Objects.requireNonNull(node, "node must not be null");
        Objects.requireNonNull(context, "context must not be null");
        return switch (node) {
            case NamedType named -> renderNamed(named.name(), context);
            case ArrayType array -> {
                String element = render(array.elementType(), context);
                yield wrapInArrayPosition(element,
                    isFunctionForm(array.elementType()), isNullableForm(array.elementType()));
            }
            case NullableType nullable -> {
                if (nullable.innerType() instanceof NullableType) {
                    throw new Defect(
                        "chained nullable annotation 'T | null | null' is outside the pinned "
                            + "TypeNode grammar (out-of-grammar declaration annotation)");
                }
                String inner = render(nullable.innerType(), context);
                yield isFunctionForm(nullable.innerType())
                    ? "(" + inner + ") | null"
                    : inner + " | null";
            }
            case FunctionType func -> renderFunctionForm(
                func.params().stream()
                    .map(p -> render(p.type(), context))
                    .toList(),
                render(func.returnType(), context), func.isAsync());
            case QualifiedType qualified -> {
                String resolved = context.importModulePaths().get(qualified.moduleName());
                if (resolved == null) {
                    throw new Defect(
                        "unresolvable qualified-type alias '" + qualified.moduleName()
                            + "' in '" + qualified.moduleName() + "."
                            + qualified.typeName() + "' (out-of-grammar declaration annotation)");
                }
                yield "@" + resolved + "/" + qualified.typeName();
            }
        };
    }

    /**
     * Renders the full function form of a declared function export —
     * {@code (T1, …, TN) => R} or {@code async (T1, …, TN) => R} — from
     * the declaration's parameter annotations, return annotation, and
     * async marker (the pinned declaration-entry derivation of F2).
     *
     * @param paramTypes the parameter type annotations in order; non-null
     * @param returnType the return type annotation; non-null
     * @param isAsync    the async marker
     * @param context    the declaring module's rendering context; non-null
     * @return the canonical full function form
     */
    public static String renderFunctionForm(List<TypeNode> paramTypes, TypeNode returnType,
                                            boolean isAsync, Context context) {
        Objects.requireNonNull(paramTypes, "paramTypes must not be null");
        Objects.requireNonNull(returnType, "returnType must not be null");
        Objects.requireNonNull(context, "context must not be null");
        List<String> params = new ArrayList<>(paramTypes.size());
        for (TypeNode param : paramTypes) {
            params.add(render(param, context));
        }
        return renderFunctionForm(List.copyOf(params), render(returnType, context), isAsync);
    }

    private static String renderFunctionForm(List<String> params, String returnText,
                                             boolean isAsync) {
        return (isAsync ? "async " : "") + "(" + String.join(", ", params)
            + ") => " + returnText;
    }

    // =========================================================================
    // Shared pieces
    // =========================================================================

    /**
     * The shared parenthesization rule (F2, the one rule pinned over both
     * renderers): a function form or a nullable form is wrapped in
     * parentheses when it is an array element; every other array-element
     * form renders unwrapped.
     */
    private static String wrapInArrayPosition(String rendered, boolean isFunctionForm,
                                              boolean isNullableForm) {
        return (isFunctionForm || isNullableForm)
            ? "(" + rendered + ")[]"
            : rendered + "[]";
    }

    private static String renderNamed(String name, Context context) {
        return switch (name) {
            case "null", "boolean", "int", "number", "string", "table",
                    "bytes" -> name;
            case "Error" -> "@/Error";
            default -> {
                if (!context.declaredClasses().contains(name)) {
                    throw new Defect(
                        "NamedType '" + name + "' is neither a primitive nor a class declared "
                            + "in module '" + context.moduleId().path()
                            + "' (out-of-grammar declaration annotation)");
                }
                yield "@" + context.moduleId().path() + "/" + name;
            }
        };
    }

    private static boolean isFunctionForm(Type type) {
        return type instanceof Type.Func;
    }

    private static boolean isNullableForm(Type type) {
        return type instanceof Type.Nullable;
    }

    private static boolean isFunctionForm(TypeNode node) {
        return node instanceof FunctionType;
    }

    private static boolean isNullableForm(TypeNode node) {
        return node instanceof NullableType;
    }
}
