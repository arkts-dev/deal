package deal.semantic.ir;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The closed capability-requirement catalog of
 * {@code deal.semantic-ir/1} (schema S4): maps each closed
 * {@link SemanticCapability} to its required-operation evidence, exactly
 * the S4 mapping, reproduced verbatim. The catalog is closed data owned
 * here, never reinterpreted; the validator consumes it for R-CAPABILITY
 * (a claimed capability without a required operation in the unit is
 * rejected).
 *
 * <ul>
 *   <li>{@code FOUNDATION_VALUES} → {@code {CONST, UNARY, BINARY, STRING_CONCAT}}</li>
 *   <li>{@code SIGNED_INT32} → {@code {CONST(Int), UNARY(INT32_*), BINARY(INT32_*), INTRINSIC_CALL(INT_CONVERT), BOUNDARY(int descriptor)}}</li>
 *   <li>{@code CONTAINERS_AND_STRINGS} → {@code {ARRAY_NEW, TABLE_NEW, ARRAY_LENGTH, MEMBER_*, INDEX_*, OPTIONAL_READ, HAS_FIELD, FOR_EACH}}</li>
 *   <li>{@code DESCRIPTORS} → {@code {BOUNDARY}}</li>
 *   <li>{@code BOUNDARIES} → {@code {BOUNDARY}}</li>
 *   <li>{@code EVALUATION_ORDER} → {@code {BRANCH, LOOP, DISCARD}}</li>
 *   <li>{@code BINDINGS} → {@code {BINDING_ALLOC, BINDING_INIT, BINDING_LOAD, BINDING_STORE, RECURSIVE_GROUP_INIT, CLOSURE_NEW}}</li>
 *   <li>{@code CALLS} → {@code {CALL, CALLBACK_INVOKE, EXTERNAL_ENTRY, ASYNC_START, AWAIT, RETURN, ENTRY_INVOKE, FUNCTION_ADAPT}}</li>
 *   <li>{@code STDLIB_SEMANTICS} → {@code {STDLIB_CALL}}</li>
 *   <li>{@code STDLIB_TIME_CONFLICT} → {@code {}} (never valid IR — a routing marker only)</li>
 *   <li>{@code CLASSES} → {@code {CLASS_NEW, CLASS_FACTORY, CLASS_DEFAULT, FIELD_READ, FIELD_WRITE, FIELD_DELETE, JSON_FROM_CLASS, JSON_TO_CLASS}}</li>
 *   <li>{@code MODULES} → {@code {MODULE_INIT, MODULE_IMPORT, EXPORT_READ, EXPORT_PUBLISH}}</li>
 * </ul>
 *
 * <p>The catalog covers every capability in {@link SemanticCapability}
 * declaration order; an out-of-set capability cannot be expressed because
 * the enum is closed. Wildcard family entries ({@code MEMBER_*},
 * {@code INDEX_*}) are recorded with their verbatim S4 text and expanded
 * to the concrete covered kinds in {@link RequiredOperation#kinds()}.</p>
 */
public final class CapabilityRequirementCatalog {

    private CapabilityRequirementCatalog() { /* closed data table */ }

    /**
     * One required-operation evidence entry: the verbatim S4 text and the
     * concrete operation kinds it covers (wildcards expanded; a
     * specialized entry covers its base kind with the specialization noted
     * in the verbatim text).
     */
    public record RequiredOperation(String verbatim, List<SemanticOpKind> kinds) {

        public RequiredOperation(String verbatim, List<SemanticOpKind> kinds) {
            this.verbatim = Objects.requireNonNull(verbatim, "verbatim must not be null");
            this.kinds = List.copyOf(kinds);
        }

        private static RequiredOperation of(String verbatim, SemanticOpKind... kinds) {
            return new RequiredOperation(verbatim, List.of(kinds));
        }
    }

    private static final Map<SemanticCapability, List<RequiredOperation>> CATALOG = build();

    private static Map<SemanticCapability, List<RequiredOperation>> build() {
        Map<SemanticCapability, List<RequiredOperation>> catalog =
            new EnumMap<>(SemanticCapability.class);

        catalog.put(SemanticCapability.FOUNDATION_VALUES, List.of(
            RequiredOperation.of("CONST", SemanticOpKind.CONST),
            RequiredOperation.of("UNARY", SemanticOpKind.UNARY),
            RequiredOperation.of("BINARY", SemanticOpKind.BINARY),
            RequiredOperation.of("STRING_CONCAT", SemanticOpKind.STRING_CONCAT)));

        catalog.put(SemanticCapability.SIGNED_INT32, List.of(
            RequiredOperation.of("CONST(Int)", SemanticOpKind.CONST),
            RequiredOperation.of("UNARY(INT32_*)", SemanticOpKind.UNARY),
            RequiredOperation.of("BINARY(INT32_*)", SemanticOpKind.BINARY),
            RequiredOperation.of("INTRINSIC_CALL(INT_CONVERT)", SemanticOpKind.INTRINSIC_CALL),
            RequiredOperation.of("BOUNDARY(int descriptor)", SemanticOpKind.BOUNDARY)));

        catalog.put(SemanticCapability.CONTAINERS_AND_STRINGS, List.of(
            RequiredOperation.of("ARRAY_NEW", SemanticOpKind.ARRAY_NEW),
            RequiredOperation.of("TABLE_NEW", SemanticOpKind.TABLE_NEW),
            RequiredOperation.of("ARRAY_LENGTH", SemanticOpKind.ARRAY_LENGTH),
            RequiredOperation.of("MEMBER_*", SemanticOpKind.MEMBER_READ,
                SemanticOpKind.MEMBER_WRITE, SemanticOpKind.MEMBER_DELETE),
            RequiredOperation.of("INDEX_*", SemanticOpKind.INDEX_NORMALIZE,
                SemanticOpKind.INDEX_READ, SemanticOpKind.INDEX_WRITE, SemanticOpKind.INDEX_DELETE),
            RequiredOperation.of("OPTIONAL_READ", SemanticOpKind.OPTIONAL_READ),
            RequiredOperation.of("HAS_FIELD", SemanticOpKind.HAS_FIELD),
            RequiredOperation.of("FOR_EACH", SemanticOpKind.FOR_EACH)));

        catalog.put(SemanticCapability.DESCRIPTORS, List.of(
            RequiredOperation.of("BOUNDARY", SemanticOpKind.BOUNDARY)));

        catalog.put(SemanticCapability.BOUNDARIES, List.of(
            RequiredOperation.of("BOUNDARY", SemanticOpKind.BOUNDARY)));

        catalog.put(SemanticCapability.EVALUATION_ORDER, List.of(
            RequiredOperation.of("BRANCH", SemanticOpKind.BRANCH),
            RequiredOperation.of("LOOP", SemanticOpKind.LOOP),
            RequiredOperation.of("DISCARD", SemanticOpKind.DISCARD)));

        catalog.put(SemanticCapability.BINDINGS, List.of(
            RequiredOperation.of("BINDING_ALLOC", SemanticOpKind.BINDING_ALLOC),
            RequiredOperation.of("BINDING_INIT", SemanticOpKind.BINDING_INIT),
            RequiredOperation.of("BINDING_LOAD", SemanticOpKind.BINDING_LOAD),
            RequiredOperation.of("BINDING_STORE", SemanticOpKind.BINDING_STORE),
            RequiredOperation.of("RECURSIVE_GROUP_INIT", SemanticOpKind.RECURSIVE_GROUP_INIT),
            RequiredOperation.of("CLOSURE_NEW", SemanticOpKind.CLOSURE_NEW)));

        catalog.put(SemanticCapability.CALLS, List.of(
            RequiredOperation.of("CALL", SemanticOpKind.CALL),
            RequiredOperation.of("CALLBACK_INVOKE", SemanticOpKind.CALLBACK_INVOKE),
            RequiredOperation.of("EXTERNAL_ENTRY", SemanticOpKind.EXTERNAL_ENTRY),
            RequiredOperation.of("ASYNC_START", SemanticOpKind.ASYNC_START),
            RequiredOperation.of("AWAIT", SemanticOpKind.AWAIT),
            RequiredOperation.of("RETURN", SemanticOpKind.RETURN),
            RequiredOperation.of("ENTRY_INVOKE", SemanticOpKind.ENTRY_INVOKE),
            RequiredOperation.of("FUNCTION_ADAPT", SemanticOpKind.FUNCTION_ADAPT)));

        catalog.put(SemanticCapability.STDLIB_SEMANTICS, List.of(
            RequiredOperation.of("STDLIB_CALL", SemanticOpKind.STDLIB_CALL)));

        // STDLIB_TIME_CONFLICT → {}: never valid IR — a routing marker only.
        catalog.put(SemanticCapability.STDLIB_TIME_CONFLICT, List.of());

        catalog.put(SemanticCapability.CLASSES, List.of(
            RequiredOperation.of("CLASS_NEW", SemanticOpKind.CLASS_NEW),
            RequiredOperation.of("CLASS_FACTORY", SemanticOpKind.CLASS_FACTORY),
            RequiredOperation.of("CLASS_DEFAULT", SemanticOpKind.CLASS_DEFAULT),
            RequiredOperation.of("FIELD_READ", SemanticOpKind.FIELD_READ),
            RequiredOperation.of("FIELD_WRITE", SemanticOpKind.FIELD_WRITE),
            RequiredOperation.of("FIELD_DELETE", SemanticOpKind.FIELD_DELETE),
            RequiredOperation.of("JSON_FROM_CLASS", SemanticOpKind.JSON_FROM_CLASS),
            RequiredOperation.of("JSON_TO_CLASS", SemanticOpKind.JSON_TO_CLASS)));

        catalog.put(SemanticCapability.MODULES, List.of(
            RequiredOperation.of("MODULE_INIT", SemanticOpKind.MODULE_INIT),
            RequiredOperation.of("MODULE_IMPORT", SemanticOpKind.MODULE_IMPORT),
            RequiredOperation.of("EXPORT_READ", SemanticOpKind.EXPORT_READ),
            RequiredOperation.of("EXPORT_PUBLISH", SemanticOpKind.EXPORT_PUBLISH)));

        if (!catalog.keySet().equals(java.util.EnumSet.allOf(SemanticCapability.class))) {
            throw new IllegalStateException(
                "capability catalog must cover every SemanticCapability in declaration order");
        }
        return catalog;
    }

    /** The closed catalog: every {@link SemanticCapability} mapped to its S4 evidence rows. */
    public static Map<SemanticCapability, List<RequiredOperation>> catalog() {
        return CATALOG;
    }

    /** The S4 required-operation evidence rows of one capability (unmodifiable). */
    public static List<RequiredOperation> requiredOperations(SemanticCapability capability) {
        Objects.requireNonNull(capability, "capability must not be null");
        return CATALOG.get(capability);
    }
}
