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
     * resolved export map through this exact rule set; nullish flags
     * and Promise result notation match declaration annotations;
     * {@link Type.Error} has no rendering and raises {@link Defect}.
     *
     */
    public static String render(Type type) {
        Objects.requireNonNull(type, "type must not be null");
        return switch (type) {
            case Type.Null ignored -> "null";
            case Type.Undefined ignored -> "undefined";
            case Type.Unknown ignored -> "unknown";
            case Type.Void ignored -> "void";
            case Type.Boolean ignored -> "boolean";

            case Type.Number ignored -> "number";
            case Type.String ignored -> "string";
            case Type.Table ignored -> "Record<string, unknown>";
            case Type.Error ignored -> throw new Defect(
                "Type.Error reached a declared-type position: the internal checker "
                    + "sentinel has no canonical rendering and is excluded from the index");
            case Type.Array array -> {
                String element = render(array.element());
                yield wrapInArrayPosition(element,
                    isFunctionForm(array.element()), isNullableForm(array.element()));
            }
            case Type.Nullable nullable -> {
                if (nullable.inner() == Type.Undefined.INSTANCE && nullable.admitsNull()) {
                    yield "null | undefined";
                }
                String inner = render(nullable.inner());
                yield (isFunctionForm(nullable.inner()) ? "(" + inner + ")" : inner)
                    + (nullable.admitsNull() ? " | null" : "")
                    + (nullable.admitsUndefined() ? " | undefined" : "");
            }
            case Type.Class cls -> {
                String path = DescriptorService.semanticModulePath(cls.identity());
                // The builtin module carries the empty module path; its only
                // class is the intrinsic Error whose canonical text is the
                // bare atom (the one class text without the leading '@').
                yield path.isEmpty()
                    ? cls.name()
                    : "@" + path + "/" + cls.name();
            }
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
            case NullableType nullable -> renderNullish(nullable, context);
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
     * {@code (T1, …, TN) => R} or {@code (T1, …, TN) => Promise<R>} — from
     * the declaration's parameter annotations, return annotation, and
     * async marker (the pinned declaration-entry derivation of F2).
     *
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
        return "(" + String.join(", ", params) + ") => "
            + (isAsync ? "Promise<" + returnText + ">" : returnText);
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

    private static String renderNullish(NullableType node, Context context) {
        boolean admitsNull = false;
        boolean admitsUndefined = false;
        TypeNode base = node;
        while (base instanceof NullableType member) {
            if (member.undefinedMember()) {
                if (admitsUndefined) throw new Defect("Duplicate undefined member in nullish annotation");
                admitsUndefined = true;
            } else {
                if (admitsNull) throw new Defect("Duplicate null member in nullish annotation");
                admitsNull = true;
            }
            base = member.innerType();
        }
        if (base instanceof NamedType named) {
            if (named.name().equals("null")) {
                if (admitsNull) throw new Defect("Duplicate null member in nullish annotation");
                return "null | undefined";
            }
            if (named.name().equals("undefined")) {
                if (admitsUndefined) throw new Defect("Duplicate undefined member in nullish annotation");
                return "null | undefined";
            }
        }
        String inner = render(base, context);
        return (isFunctionForm(base) ? "(" + inner + ")" : inner)
            + (admitsNull ? " | null" : "") + (admitsUndefined ? " | undefined" : "");
    }

    private static String renderNamed(String name, Context context) {
        return switch (name) {
            case "null", "undefined", "unknown", "void", "boolean", "number", "string", "Record<string, unknown>" -> name;
            case "Error" -> "Error";
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
