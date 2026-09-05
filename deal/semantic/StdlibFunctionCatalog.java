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

/**
 * The single closed mapping from {@code (resolved stdlib module path,
 * export name)} to {@link StdlibFunctionId}
 * ({@code stdlib-operations-and-time-lock} D1, Contracts
 * §{@code StdlibFunctionCatalog}): exactly the 20 declared exports of
 * {@code std/console}, {@code std/string}, {@code std/table},
 * {@code std/json}, and {@code std/math}, each carrying its declared
 * parameter and return descriptors. No other stdlib call surface exists:
 * no consumer may interpret a module/name pair as a stdlib algorithm
 * outside this catalog and the closed checked-fact recognition predicate
 * ({@link StdlibCallRecognition}).
 *
 * <p><b>Closed set (D1, exact).</b> The {@link #ENTRIES} list is the
 * pinned declaration order of the D1 table — 20 rows, one per
 * {@code StdlibFunctionId} value, none added, removed, or renamed:</p>
 *
 * <pre>{@code
 * std.console: log → CONSOLE_LOG (string) → null; error → CONSOLE_ERROR (string) → null
 * std.string:  length → STRING_LENGTH (string) → int;
 *              substring → STRING_SUBSTRING (string,int,int) → string;
 *              contains → STRING_CONTAINS (string,string) → boolean;
 *              startsWith → STRING_STARTS_WITH (string,string) → boolean;
 *              endsWith → STRING_ENDS_WITH (string,string) → boolean;
 *              replace → STRING_REPLACE (string,string,string) → string;
 *              split → STRING_SPLIT (string,string) → [string];
 *              trim → STRING_TRIM (string) → string
 * std.table:   keys → TABLE_KEYS (table) → [string]
 * std.json:    parse → JSON_PARSE (string) → table;
 *              stringify → JSON_STRINGIFY (table) → string
 * std.math:    floor → MATH_FLOOR (number) → number; ceil → MATH_CEIL (number) → number;
 *              sqrt → MATH_SQRT (number) → number;
 *              absInt → MATH_ABS_INT (int) → int; absNumber → MATH_ABS_NUMBER (number) → number;
 *              minInt → MATH_MIN_INT (int,int) → int; maxInt → MATH_MAX_INT (int,int) → int
 * }</pre>
 *
 * <p><b>Time lock (D8).</b> {@code std/time} has no entry for any
 * member: {@code TIME_NOW_MILLIS} stays a reserved selector name, invalid
 * in {@code deal.semantic-ir/1}, and this catalog never names it.
 * {@link StdlibFunctionId#RESERVED_NAMES} is untouched.</p>
 *
 * <p><b>Declared descriptors (D1).</b> Every parameter/return descriptor
 * is a canonical structural descriptor in the landed
 * {@link DescriptorService} domain — produced by
 * {@link DescriptorService#describe(Type)} at class initialization, so
 * each value is structurally identical to the one the lowering arm
 * stamps onto {@code STDLIB_CALL} operands and
 * {@code STDLIB_PARAMETER}/{@code STDLIB_RETURN} boundaries.</p>
 *
 * <p><b>Lookup contract.</b> {@link #lookup} is a pure, immutable, static
 * function over {@code (resolved module path, export name)}: an absent
 * module or member returns {@code Optional.empty()} — "not a stdlib
 * call", never an error — with no state, retry, or timeout. Null
 * arguments are absent inputs, never errors.</p>
 *
 * <p><b>Production closed-set guard.</b> Class initialization verifies
 * that the 20 rows cover the closed 20-value {@link StdlibFunctionId}
 * set exactly once and that no {@code (modulePath, exportName)} pair
 * repeats; a drifted catalog fails at class load ({@link
 * IllegalStateException} — a production defect, never a runtime state).</p>
 */
public final class StdlibFunctionCatalog {

    private StdlibFunctionCatalog() {
        // Static surface only; no instances and no state.
    }

    /**
     * One closed catalog row: the {@link StdlibFunctionId}, the resolved
     * stdlib module path and export name, and the declared parameter and
     * return descriptors (D1). Immutable.
     *
     * @param function            the closed stdlib function id; non-null
     * @param modulePath          the resolved stdlib module path — the
     *                            dotted module id of the resolved import
     *                            fact (e.g. {@code "std.string"}); non-null
     * @param exportName          the declared export name (e.g.
     *                            {@code "length"}); non-null
     * @param parameterDescriptors the declared parameter descriptors in
     *                            one-based declaration order; non-null
     * @param returnDescriptor    the declared return descriptor (the
     *                            {@code null} descriptor for the console
     *                            ids); non-null
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
    // The closed 20-row table (D1 order, verbatim)
    // =========================================================================

    /**
     * The closed 20 entries in the pinned D1 declaration order. Immutable
     * ({@code List.of}); an added, removed, or renamed row fails the
     * class-load closed-set guard below.
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
            List.of(INT, INT), INT)
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
                        + " in the catalog (producer defect: TIME_NOW_MILLIS is reserved "
                        + "and never a catalog entry)");
            }
        }
        if (!functions.equals(EnumSet.allOf(StdlibFunctionId.class))) {
            throw new IllegalStateException(
                "the stdlib catalog does not cover the closed StdlibFunctionId set "
                    + "(producer defect: exactly the 20 ids, one entry each)");
        }
        return Map.copyOf(byModule);
    }

    // =========================================================================
    // Static surface
    // =========================================================================

    /**
     * The closed entry table in the pinned D1 declaration order (20 rows).
     *
     * @return an immutable view of {@link #ENTRIES}
     */
    public static List<Entry> entries() {
        return ENTRIES;
    }

    /**
     * The pure catalog lookup: the entry for {@code (resolved stdlib
     * module path, export name)}, or {@code Optional.empty()} — "not a
     * stdlib call" — when the module or member is absent. Never an
     * error, never a state change; null inputs are absent inputs. An
     * absent entry carries no default: a {@code std/time} member, an
     * unknown member of a known stdlib module, and a user/host module
     * all return empty.
     *
     * @param resolvedModulePath the resolved stdlib module path — the
     *                           dotted module id of the resolved import
     *                           fact (e.g. {@code "std.string"}, the
     *                           {@code ResolvedImport.resolvedModuleId}
     *                           form); may be null (absent module)
     * @param exportName         the export name (e.g.
     *                           {@code "length"}); may be null (absent
     *                           member)
     * @return the matching closed entry, or empty when absent
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
