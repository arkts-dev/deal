package deal.semantic;

import deal.semantic.ir.RuntimeDescriptor;
import deal.semantic.ir.StdlibFunctionId;
import deal.types.Type;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class StdlibFunctionCatalog {

    private StdlibFunctionCatalog() {
        // Static surface only; no instances and no state.
    }

    /**
     * One closed catalog row: the {@link StdlibFunctionId}, the resolved
     * stdlib module path and export name, and the declared parameter and
     * return descriptors (D1). Immutable.
     *
     */
    public record Entry(StdlibFunctionId function, String modulePath, String exportName,
                        List<RuntimeDescriptor> parameterDescriptors,
                        RuntimeDescriptor returnDescriptor) {

        public Entry {
            Objects.requireNonNull(function, "function must not be null");
            Objects.requireNonNull(modulePath, "modulePath must not be null");
            Objects.requireNonNull(exportName, "exportName must not be null");
            Objects.requireNonNull(returnDescriptor, "returnDescriptor must not be null");
            parameterDescriptors = List.copyOf(parameterDescriptors);
        }

        /**
         * The row's declared function descriptor —
         * {@code Func(parameterDescriptors(), returnDescriptor())} built
         * from the row's declared parts. It is the STDLIB-kind
         * import-member read's own descriptor
         * ({@code import-member-read-arm-lowering-and-registration} R2):
         * the closed catalog row is the resolution authority and its
         * declared signature is the read's carried signature, assembled
         * here beside the row data it belongs to.
         *
         */
        public RuntimeDescriptor.Func declaredDescriptor() {
            return new RuntimeDescriptor.Func(parameterDescriptors, returnDescriptor);
        }
    }

    // =========================================================================
    // Declared descriptors (DescriptorService domain by construction, D1)
    // =========================================================================

    private static final RuntimeDescriptor STRING =
        DescriptorService.describe(Type.String.INSTANCE);
    private static final RuntimeDescriptor INT =
        DescriptorService.describe(Type.Int.INSTANCE);
    private static final RuntimeDescriptor NUMBER =
        DescriptorService.describe(Type.Number.INSTANCE);
    private static final RuntimeDescriptor BOOLEAN =
        DescriptorService.describe(Type.Boolean.INSTANCE);
    private static final RuntimeDescriptor TABLE =
        DescriptorService.describe(Type.Table.INSTANCE);
    private static final RuntimeDescriptor NULL =
        DescriptorService.describe(Type.Null.INSTANCE);
    private static final RuntimeDescriptor STRING_ARRAY =
        DescriptorService.describe(new Type.Array(Type.String.INSTANCE));

    // =========================================================================
    // The closed 21-row table (D1 order + the K7 std.time row, verbatim)
    // =========================================================================

    /**
     * The closed 21 entries in the pinned declaration order: the D1 rows
     * in order, then the K7 {@code std.time}/{@code nowMillis} row as the
     * sole {@code std.time} group. Immutable ({@code List.of}); an added,
     * removed, or renamed row fails the class-load closed-set guard below.
     */
    private static final List<Entry> ENTRIES = List.of(
        // std.console
        new Entry(StdlibFunctionId.CONSOLE_LOG, "std.console", "log",
            List.of(STRING), NULL),
        new Entry(StdlibFunctionId.CONSOLE_ERROR, "std.console", "error",
            List.of(STRING), NULL),
        // std.string
        new Entry(StdlibFunctionId.STRING_LENGTH, "std.string", "length",
            List.of(STRING), INT),
        new Entry(StdlibFunctionId.STRING_SUBSTRING, "std.string", "substring",
            List.of(STRING, INT, INT), STRING),
        new Entry(StdlibFunctionId.STRING_CONTAINS, "std.string", "contains",
            List.of(STRING, STRING), BOOLEAN),
        new Entry(StdlibFunctionId.STRING_STARTS_WITH, "std.string", "startsWith",
            List.of(STRING, STRING), BOOLEAN),
        new Entry(StdlibFunctionId.STRING_ENDS_WITH, "std.string", "endsWith",
            List.of(STRING, STRING), BOOLEAN),
        new Entry(StdlibFunctionId.STRING_REPLACE, "std.string", "replace",
            List.of(STRING, STRING, STRING), STRING),
        new Entry(StdlibFunctionId.STRING_SPLIT, "std.string", "split",
            List.of(STRING, STRING), STRING_ARRAY),
        new Entry(StdlibFunctionId.STRING_TRIM, "std.string", "trim",
            List.of(STRING), STRING),
        // std.table
        new Entry(StdlibFunctionId.TABLE_KEYS, "std.table", "keys",
            List.of(TABLE), STRING_ARRAY),
        // std.json
        new Entry(StdlibFunctionId.JSON_PARSE, "std.json", "parse",
            List.of(STRING), TABLE),
        new Entry(StdlibFunctionId.JSON_STRINGIFY, "std.json", "stringify",
            List.of(TABLE), STRING),
        // std.math
        new Entry(StdlibFunctionId.MATH_FLOOR, "std.math", "floor",
            List.of(NUMBER), NUMBER),
        new Entry(StdlibFunctionId.MATH_CEIL, "std.math", "ceil",
            List.of(NUMBER), NUMBER),
        new Entry(StdlibFunctionId.MATH_SQRT, "std.math", "sqrt",
            List.of(NUMBER), NUMBER),
        new Entry(StdlibFunctionId.MATH_ABS_INT, "std.math", "absInt",
            List.of(INT), INT),
        new Entry(StdlibFunctionId.MATH_ABS_NUMBER, "std.math", "absNumber",
            List.of(NUMBER), NUMBER),
        new Entry(StdlibFunctionId.MATH_MIN_INT, "std.math", "minInt",
            List.of(INT, INT), INT),
        new Entry(StdlibFunctionId.MATH_MAX_INT, "std.math", "maxInt",
            List.of(INT, INT), INT),
        // std.time (K7)
        new Entry(StdlibFunctionId.TIME_NOW_MILLIS, "std.time", "nowMillis",
            List.of(), INT)
    );

    /**
     * The lookup index keyed by resolved module path, then export name —
     * built once at class load from the closed {@link #ENTRIES} table.
     * Immutable.
     */
    private static final Map<String, Map<String, Entry>> BY_MODULE = index();

    private static Map<String, Map<String, Entry>> index() {
        Map<String, Map<String, Entry>> byModule = new LinkedHashMap<>();
        Set<StdlibFunctionId> functions = EnumSet.noneOf(StdlibFunctionId.class);
        for (Entry entry : ENTRIES) {
            Map<String, Entry> module = byModule.computeIfAbsent(entry.modulePath(),
                ignored -> new LinkedHashMap<>());
            if (module.putIfAbsent(entry.exportName(), entry) != null) {
                throw new IllegalStateException(
                    "duplicate stdlib catalog row for " + entry.modulePath() + "."
                        + entry.exportName() + " (producer defect: the closed table has "
                        + "exactly one entry per (module path, export name))");
            }
            if (!functions.add(entry.function())) {
                throw new IllegalStateException(
                    "duplicate stdlib catalog row for " + entry.function()
                        + " (producer defect: the closed table has exactly one entry "
                        + "per StdlibFunctionId)");
            }
            if (StdlibFunctionId.isReservedName(entry.function().name())) {
                throw new IllegalStateException(
                    "reserved stdlib selector name " + entry.function().name()
                        + " in the catalog (producer defect: a reserved selector name "
                        + "is never a catalog entry)");
            }
        }
        if (!functions.equals(EnumSet.allOf(StdlibFunctionId.class))) {
            throw new IllegalStateException(
                "the stdlib catalog does not cover the closed StdlibFunctionId set "
                    + "(producer defect: exactly the 21 ids, one entry each)");
        }
        return Map.copyOf(byModule);
    }

    // =========================================================================
    // Static surface
    // =========================================================================

    /**
     * The closed entry table in the pinned declaration order (21 rows).
     *
     */
    public static List<Entry> entries() {
        return ENTRIES;
    }

    /**
     * The pure catalog lookup: the entry for {@code (resolved stdlib
     * module path, export name)}, or {@code Optional.empty()} — "not a
     * stdlib call" — when the module or member is absent. Never an
     * error, never a state change; null inputs are absent inputs. An
     * absent entry carries no default: an unknown member of a known
     * stdlib module, and a user/host module all return empty.
     *
     */
    public static Optional<Entry> lookup(String resolvedModulePath, String exportName) {
        if (resolvedModulePath == null || exportName == null) {
            return Optional.empty();
        }
        Map<String, Entry> module = BY_MODULE.get(resolvedModulePath);
        if (module == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(module.get(exportName));
    }
}
