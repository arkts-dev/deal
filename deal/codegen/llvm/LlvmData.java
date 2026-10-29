package deal.codegen.llvm;

import deal.semantic.ir.FailureArm;
import deal.semantic.ir.FailureContractRegistry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The emitted static data of the native backend
 * ({@code llvm-native-codegen-cfg-ssa} C2/C8, {@code llvm-native-runtime-value-
 * model} R5): the string pool, the frame labels, the {@code deal_origin}
 * records, the closed failure-arm table in the published
 * {@code deal_arm_record}/{@code deal_arm_table} layout, and the per-unit
 * {@code deal_module}/export records the emitted module itself owns.
 *
 * <p>Every name is a deterministic function of the IR facts (indexes in
 * collection order), every string is interned once, and no value depends on
 * the host, the clock or the output path (C9). The arm text is carried as
 * data from {@link FailureContractRegistry} in its canonical order — this
 * class composes no template and authors no message (R5).</p>
 */
public final class LlvmData {

    /** One published export of one unit: name, descriptor, async marker. */
    public record UnitExport(String name, String descriptor, long functionId,
                             boolean isAsync) {
        public UnitExport {
            Objects.requireNonNull(name, "name must not be null");
            Objects.requireNonNull(descriptor, "descriptor must not be null");
        }

        public UnitExport(String name, String descriptor, long functionId) {
            this(name, descriptor, functionId, false);
        }
    }

    /** One unit's published surface: its module-id path and its exports. */
    public record UnitModule(String modulePath, List<UnitExport> exports) {
        public UnitModule {
            Objects.requireNonNull(modulePath, "modulePath must not be null");
            exports = List.copyOf(exports);
        }
    }

    /** One interned C string constant. */
    public record StringConstant(String globalName, int length) {
    }

    private final Map<String, StringConstant> strings = new LinkedHashMap<>();
    private final Map<String, String> origins = new LinkedHashMap<>();
    private final List<String> extraDefinitions = new ArrayList<>();
    private final Map<String, String> globalSlots = new LinkedHashMap<>();
    private final Map<String, String> globalSlotTypes = new LinkedHashMap<>();
    private List<UnitModule> units = List.of();
    private boolean armTableDeclared;
    private boolean nullPointerDeclared;

    /** Interns one NUL-terminated C string and answers its global name. */
    public StringConstant intern(String text) {
        Objects.requireNonNull(text, "text must not be null");
        StringConstant existing = strings.get(text);
        if (existing != null) {
            return existing;
        }
        byte[] payload = LlvmIrPrinter.utf8(text);
        StringConstant constant = new StringConstant(
            "deal.str." + strings.size(), payload.length + 1);
        strings.put(text, constant);
        return constant;
    }

    /**
     * The exact byte count of one interned string: {@link StringConstant#length}
     * counts the trailing NUL the static array carries, and a DEAL string value
     * is exactly its bytes (never NUL-terminated).
     */
    public int byteCount(StringConstant constant) {
        Objects.requireNonNull(constant, "constant must not be null");
        return constant.length() - 1;
    }

    /**
     * The typed {@code i8*} constant operand of one interned string. Every
     * call site uses this whole spelling, so no double type prefix can occur.
     */
    public String operand(StringConstant constant) {
        return LlvmIrPrinter.stringConstant(constant.globalName(),
            constant.length());
    }

    /**
     * Interns one frame label ({@code <modulePath>#<sourceName>@F<id>}, the
     * emitted activation label) and answers its constant.
     */
    public StringConstant frameLabel(String label) {
        Objects.requireNonNull(label, "label must not be null");
        return intern(label);
    }

    /**
     * One {@code deal_origin} record: the compiled file text (the
     * project-relative display name), the one-based line and the one-based
     * column, exactly as the op's origin records them.
     */
    public String origin(String displayFile, int line, int column) {
        Objects.requireNonNull(displayFile, "displayFile must not be null");
        String key = displayFile + "\u0000" + line + "\u0000" + column;
        String existing = origins.get(key);
        if (existing != null) {
            return existing;
        }
        StringConstant file = intern(displayFile);
        String name = "deal.origin." + origins.size();
        extraDefinitions.add("@" + name + " = private unnamed_addr constant "
            + "%struct.deal_origin { " + operand(file) + ", i32 "
            + line + ", i32 " + column + " }");
        origins.put(key, name);
        return name;
    }

    /**
     * The module-level storage of one module-scope binding incarnation: an
     * internal global of the binding's own representation, zero-initialized,
     * so the unit's initialization function writes it and every generated
     * function of the unit reads it (the C5 cell of an immutable
     * module-scope binding is observationally this one static slot).
     */
    public String globalSlot(deal.semantic.ir.BindingId binding, long generation,
                             String type) {
        String key = binding.id() + "_" + generation;
        String existing = globalSlots.get(key);
        if (existing != null) {
            return existing;
        }
        String name = "deal.slot." + binding.id() + "." + generation;
        extraDefinitions.add("@" + name + " = internal global " + type
            + " zeroinitializer");
        globalSlots.put(key, name);
        globalSlotTypes.put(name, type);
        return name;
    }

    /**
     * One private global holding a null pointer: the branchless class-atom
     * lookup reads through it instead of a datum whose reference is null, so
     * no emitted load ever dereferences a null pointer.
     */
    public String nullPointerSlot() {
        if (!nullPointerDeclared) {
            nullPointerDeclared = true;
            extraDefinitions.add("@deal.nullpointer = private constant i8* null");
        }
        return "deal.nullpointer";
    }

    /** Declares the per-unit module records of the project, in project order. */
    public void defineUnits(List<UnitModule> unitModules) {
        if (!units.isEmpty()) {
            throw new IllegalStateException("the unit records are declared once");
        }
        units = List.copyOf(unitModules);
    }

    /** The global name of one unit's {@code deal_module} record. */
    public static String moduleRecord(long index) {
        return "deal.module." + index;
    }

    /**
     * The module-level binding slots whose value is a managed reference: a
     * captured-binding cell pointer or a managed value. Every one is registered
     * as a static root before user code, so a module-scope binding survives
     * every collection (M5). A scalar or persistent-datum slot is no managed
     * reference and is not registered.
     */
    public List<GlobalSlot> managedGlobalSlots() {
        List<GlobalSlot> slots = new ArrayList<>();
        for (Map.Entry<String, String> entry : globalSlotTypes.entrySet()) {
            if (isManagedPointerType(entry.getValue())) {
                slots.add(new GlobalSlot(entry.getKey(), entry.getValue()));
            }
        }
        return List.copyOf(slots);
    }

    /** One module-level binding slot and its IR representation. */
    public record GlobalSlot(String name, String type) {
    }

    /** The IR spelling of one managed pointer representation. */
    public static boolean isManagedPointerType(String type) {
        return type.equals("i8*") || type.equals("%struct.deal_fn*")
            || type.equals("%struct.deal_string*")
            || type.equals("%struct.deal_error*")
            || type.equals("%struct.deal_array*")
            || type.equals("%struct.deal_table*")
            || type.equals("%struct.deal_object*");
    }

    /**
     * The per-unit carrier slot of one stdlib function value: created once in
     * the unit's initialization and a static root, so two reads of the same
     * stdlib function are the same value identity.
     */
    public String stdlibSlot(long unitIndex, deal.semantic.ir.StdlibFunctionId function) {
        String name = "deal.std." + unitIndex + "." + function.ordinal();
        if (globalSlotTypes.containsKey(name)) {
            return name;
        }
        extraDefinitions.add("@" + name
            + " = internal global %struct.deal_fn* null");
        globalSlotTypes.put(name, "%struct.deal_fn*");
        return name;
    }

    /** The global name of one unit's export-entry array. */
    public static String exportTable(long index) {
        return "deal.exports." + index;
    }

    // =========================================================================
    // Rendering
    // =========================================================================

    /**
     * Every static definition of this emission, in deterministic order: the
     * interned strings, the origin records, the failure-arm table and the
     * per-unit module records. The non-string sections are assembled first so
     * that every string they intern is part of the emitted string pool.
     */
    public List<String> render() {
        return render(List.of());
    }

    /**
     * Every static definition of this emission, in deterministic order, with
     * the further sections an owning member contributes (the emitted class and
     * JSON plan data, whose names and strings are interned here so they join
     * the one string pool).
     */
    public List<String> render(List<String> extraSections) {
        Objects.requireNonNull(extraSections, "extraSections must not be null");
        List<String> sections = new ArrayList<>(extraSections);
        defineArmTable(sections);
        defineModuleRecords(sections);
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, StringConstant> entry : strings.entrySet()) {
            StringConstant constant = entry.getValue();
            lines.add("@" + constant.globalName()
                + " = private unnamed_addr constant [" + constant.length()
                + " x i8] c\"" + LlvmIrPrinter.cString(
                    LlvmIrPrinter.utf8(entry.getKey())) + "\\00\"");
        }
        lines.addAll(extraDefinitions);
        lines.addAll(sections);
        return List.copyOf(lines);
    }

    /**
     * The closed failure-arm table ({@code R5}): one parameter-name array per
     * arm, then the record array in the registry's canonical order, then the
     * table the emitted start function installs.
     */
    private void defineArmTable(List<String> lines) {
        if (armTableDeclared) {
            return;
        }
        armTableDeclared = true;
        List<FailureArm> arms = List.copyOf(
            FailureContractRegistry.arms().values());
        for (int index = 0; index < arms.size(); index++) {
            FailureArm arm = arms.get(index);
            List<String> parameters = arm.parameters();
            if (parameters.isEmpty()) {
                continue;
            }
            List<String> operands = new ArrayList<>();
            for (String parameter : parameters) {
                operands.add(operand(intern(parameter)));
            }
            lines.add("@deal.arm." + index + ".params = private unnamed_addr"
                + " constant [" + parameters.size() + " x i8*] ["
                + String.join(", ", operands) + "]");
        }
        List<String> records = new ArrayList<>();
        for (int index = 0; index < arms.size(); index++) {
            records.add(armRecordLiteral(index, arms.get(index)));
        }
        lines.add("@deal.arm.records = private unnamed_addr constant ["
            + arms.size() + " x %struct.deal_arm_record] ["
            + String.join(", ", records) + "]");
        lines.add("@deal_project_arm_table = dso_local constant "
            + "%struct.deal_arm_table { i32 " + LlvmRuntimeAbi.ARM_TABLE_VERSION
            + ", i32 " + arms.size()
            + ", %struct.deal_arm_record* getelementptr (["
            + arms.size() + " x %struct.deal_arm_record], ["
            + arms.size() + " x %struct.deal_arm_record]* @deal.arm.records,"
            + " i64 0, i64 0) }");
    }

    private String armRecordLiteral(int index, FailureArm arm) {
        String parameters = arm.parameters().isEmpty()
            ? "i8** null"
            : "i8** getelementptr ([" + arm.parameters().size() + " x i8*], ["
                + arm.parameters().size() + " x i8*]* @deal.arm." + index
                + ".params, i64 0, i64 0)";
        return "%struct.deal_arm_record { i32 " + arm.id().ordinal()
            + ", " + operand(intern(FailureContractRegistry.codeOf(arm)))
            + ", " + operand(intern(arm.template()))
            + ", " + parameters
            + ", i32 " + arm.parameters().size()
            + ", i8 " + renderScope(arm)
            + ", i8 " + (arm.expectedSource()
                == FailureArm.ExpectedSource.NONE ? 0 : 1)
            + ", i8 " + (arm.actualProjection()
                == FailureArm.ActualProjection.NONE ? 0 : 1)
            + " }";
    }

    private static int renderScope(FailureArm arm) {
        return arm.scope() == FailureArm.RenderScope.INNER_ONLY
            ? LlvmRuntimeAbi.ARM_SCOPE_INNER_ONLY
            : LlvmRuntimeAbi.ARM_SCOPE_TOP_LEVEL;
    }

    /**
     * The per-unit {@code deal_module} records and their export-entry arrays.
     * The records are the emitted module's own layout (C2): neither
     * {@code deal_module} nor {@code deal_export_entry} has a published
     * member. A unit without a published export carries the count-zero form
     * with a null entry pointer.
     */
    private void defineModuleRecords(List<String> lines) {
        for (int index = 0; index < units.size(); index++) {
            UnitModule unit = units.get(index);
            String path = operand(intern(unit.modulePath()));
            if (unit.exports().isEmpty()) {
                lines.add("@" + exportTable(index)
                    + " = internal global [0 x %struct.deal_export_entry] []");
            } else {
                List<String> entries = new ArrayList<>();
                for (UnitExport export : unit.exports()) {
                    entries.add(exportEntryLiteral(export));
                }
                lines.add("@" + exportTable(index)
                    + " = internal global [" + entries.size()
                    + " x %struct.deal_export_entry] ["
                    + String.join(", ", entries) + "]");
            }
            lines.add("@" + moduleRecord(index)
                + " = internal global %struct.deal_module { " + path
                + ", i32 0, i32 " + unit.exports().size()
                + ", %struct.deal_export_entry* "
                + (unit.exports().isEmpty()
                    ? "null"
                    : "getelementptr ([" + unit.exports().size()
                        + " x %struct.deal_export_entry], ["
                        + unit.exports().size()
                        + " x %struct.deal_export_entry]* @"
                        + exportTable(index) + ", i64 0, i64 0)")
                + ", i8* null }");
        }
    }

    /**
     * One export record: the published name, its canonical descriptor, the
     * async marker, a reserved zero and the managed function-value slot the
     * unit's initialization fills after the export boundary succeeded
     * (C2/C3). The record is mutable so the value is published only after
     * successful initialization, and the zero-arity entry surface invokes that
     * carrier through its boxed apply thunk.
     */
    private String exportEntryLiteral(UnitExport export) {
        return "%struct.deal_export_entry { "
            + operand(intern(export.name())) + ", "
            + operand(intern(export.descriptor())) + ", i32 "
            + (export.isAsync() ? 1 : 0) + ", i32 0,"
            + " %struct.deal_fn* null }";
    }
}
