package deal.codegen.llvm;

import deal.semantic.ir.ClassId;
import deal.semantic.ir.ClassLayout;
import deal.semantic.ir.RuntimeDescriptor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The emitted per-layout class and JSON plan surface of the native backend
 * ({@code llvm-native-codegen-cfg-ssa} C2/C3, {@code llvm-native-runtime-value-
 * model} R2/R4/R6, {@code ConstructClass}, {@code JsonFromClass}/
 * {@code JsonToClass}).
 *
 * <p>For every declared class this member emits, once per layout:</p>
 * <ul>
 *   <li>the typed instance record of C3 — a class atom, one presence byte per
 *       optional field and one typed slot per declared field;</li>
 *   <li>the published {@code deal_layout} metadata with the payload size and
 *       the ascending, 8-aligned byte offsets of its managed-reference members
 *       — the pointer slot itself, or a persistent datum's own
 *       {@code reference} member — installed through {@code deal_layout_install}
 *       before any allocation (M1);</li>
 *   <li>the published {@code deal_class_layout} — the canonical class atom and
 *       the declaration-order fields — whose descriptor members are the
 *       interned atoms of the {@code descriptor} group, filled before any
 *       construction or JSON walk;</li>
 *   <li>the two construction seams of the emitted JSON plan family: the
 *       {@code construct} seam (already validated fields &rarr; one tagged
 *       instance) and the {@code read_field} seam (one instance field &rarr;
 *       its closed check presentation), and the plan record that names
 *       them;</li>
 *   <li>the plan lookup the runtime JSON group drives by canonical class
 *       atom.</li>
 * </ul>
 *
 * <p>Every name, order and offset derives from the declaration order of the
 * project's class layouts (sorted by canonical class text), so two emissions of
 * one project are byte-identical (C9). The runtime keeps owning the
 * declaration-order walk, the extra-key and required-presence rules, the
 * recursion, the cycle check and every failure projection: the emitted module
 * supplies the layout and the two seams, never a second JSON policy (R6).</p>
 */
final class LlvmClassPlans {

    /** One declared field with its emitted slot facts. */
    record Field(ClassLayout.FieldLayout layout, String slotType, int offset,
                 int structIndex, int presenceIndex) {
    }

    /** One declared class with its emitted instance-layout facts. */
    record ClassDef(ClassId classId, ClassLayout layout, int index, int layoutId,
                    String recordName, String atomText, List<Field> fields,
                    int optionalCount, int size, List<Integer> referenceOffsets) {

        /** The module symbol of the class's emitted instance-layout metadata. */
        String layoutSymbol() {
            return "deal.class." + index + ".layout";
        }

        /** The module symbol of the class's published construction layout. */
        String constructionSymbol() {
            return "deal.class." + index + ".class";
        }

        /** The module symbol of the class's mutable declared-field array. */
        String fieldsSymbol() {
            return "deal.class." + index + ".fields";
        }

        /** The module symbol of the class's {@code deal_json_class_plan}. */
        String planSymbol() {
            return "deal.class." + index + ".plan";
        }

        /** The index of one declared field, or -1. */
        int indexOf(String name) {
            for (int position = 0; position < fields.size(); position++) {
                if (fields.get(position).layout().name().equals(name)) {
                    return position;
                }
            }
            return -1;
        }
    }

    /** The declared classes of one project in canonical class-text order. */
    static List<ClassDef> definitions(List<ClassLayout> layouts) {
        List<ClassLayout> ordered = new ArrayList<>(layouts);
        ordered.sort(java.util.Comparator.comparing(
            layout -> layout.classId().text()));
        List<ClassDef> defs = new ArrayList<>();
        for (int index = 0; index < ordered.size(); index++) {
            ClassLayout layout = ordered.get(index);
            if (layout.classId().equals(ClassId.ERROR)) {
                // The builtin Error instance is the runtime's own record
                // (C3: `deal_error*`), so the module emits no layout for it.
                continue;
            }
            defs.add(define(layout, defs.size()));
        }
        return List.copyOf(defs);
    }

    private static ClassDef define(ClassLayout layout, int index) {
        int optionalCount = 0;
        for (ClassLayout.FieldLayout field : layout.fields()) {
            if (!field.required()) {
                optionalCount++;
            }
        }
        int offset = 8;                       // the class atom pointer
        offset += optionalCount;              // one presence byte per optional field
        List<Field> fields = new ArrayList<>();
        List<Integer> references = new ArrayList<>();
        int presence = 0;
        for (int position = 0; position < layout.fields().size(); position++) {
            ClassLayout.FieldLayout field = layout.fields().get(position);
            String slotType = LlvmTypeMapper.slotTypeOf(field.descriptor());
            int alignment = LlvmTypeMapper.alignmentOfIrType(slotType);
            int remainder = offset % alignment;
            if (remainder != 0) {
                offset += alignment - remainder;
            }
            int structIndex = 1 + optionalCount + position;
            fields.add(new Field(field, slotType, offset, structIndex,
                field.required() ? -1 : presence));
            int referenceOffset = managedReferenceOffset(slotType,
                field.descriptor(), offset);
            if (referenceOffset >= 0) {
                references.add(referenceOffset);
            }
            if (!field.required()) {
                presence++;
            }
            offset += LlvmTypeMapper.sizeOfIrType(slotType);
        }
        int alignment = 8;
        int remainder = offset % alignment;
        int size = remainder == 0 ? offset : offset + (alignment - remainder);
        return new ClassDef(layout.classId(), layout, index,
            LlvmSession.EMITTED_LAYOUT_BASE + index,
            LlvmTypeMapper.classRecordName(layout.classId()),
            layout.classId().text(), List.copyOf(fields), optionalCount, size,
            List.copyOf(references));
    }

    /**
     * The payload byte offset of one slot's managed-reference member, or -1
     * when the slot holds no managed reference (M1/M4). A persistent datum
     * ({@code unknown}) holds its referent in its own {@code reference} member,
     * so the reference map names that member and never the tag word the slot
     * starts with; every other managed slot is one pointer at the slot start.
     */
    private static int managedReferenceOffset(String slotType,
                                              RuntimeDescriptor descriptor,
                                              int slotOffset) {
        if (descriptor instanceof RuntimeDescriptor.Unknown) {
            return slotOffset + LlvmTypeMapper.dataReferenceOffset();
        }
        if (LlvmTypeMapper.isManagedPointer(descriptor)) {
            return slotOffset;
        }
        return LlvmTypeMapper.REFERENCE_NULLISH.equals(slotType)
            ? slotOffset : -1;
    }

    /**
     * Whether one slot holds a managed reference the M2 barrier must shade:
     * a pointer slot shades its referent through {@code deal_store_barrier} and
     * a persistent datum through the tag-aware {@code deal_store_barrier_data}.
     */
    static boolean holdsManagedReference(Field field) {
        return managedReferenceOffset(field.slotType(),
            field.layout().descriptor(), field.offset()) >= 0;
    }

    /** Whether one slot is a persistent tagged datum ({@code unknown}, C3). */
    static boolean isTaggedDatum(Field field) {
        return field.layout().descriptor() instanceof RuntimeDescriptor.Unknown;
    }

    private final LlvmSession session;

    LlvmClassPlans(LlvmSession session) {
        this.session = session;
    }

    // =========================================================================
    // Record types, static data and the initialization entry
    // =========================================================================

    /** The emitted instance record of every declared class. */
    List<String> recordDefinitions() {
        List<String> definitions = new ArrayList<>();
        for (ClassDef def : session.classes()) {
            List<String> members = new ArrayList<>();
            members.add("i8*");
            for (int presence = 0; presence < def.optionalCount(); presence++) {
                members.add("i8");
            }
            for (Field field : def.fields()) {
                members.add(field.slotType());
            }
            definitions.add(def.recordName() + " = type { "
                + String.join(", ", members) + " }");
        }
        return List.copyOf(definitions);
    }

    /**
     * The static data of the class and JSON plan surface: the layout table the
     * emitted start function installs, the mutable declared-field arrays, the
     * published construction layouts and the plan records.
     */
    List<String> dataDefinitions() {
        List<ClassDef> classes = session.classes();
        List<String> lines = new ArrayList<>();
        for (ClassDef def : classes) {
            String references = def.referenceOffsets().isEmpty()
                ? "i32* null, i64 0"
                : "i32* getelementptr ([" + def.referenceOffsets().size()
                    + " x i32], [" + def.referenceOffsets().size()
                    + " x i32]* @" + def.layoutSymbol() + ".refs, i64 0, i64 0)"
                    + ", i64 " + def.referenceOffsets().size();
            if (!def.referenceOffsets().isEmpty()) {
                List<String> offsets = new ArrayList<>();
                for (Integer reference : def.referenceOffsets()) {
                    offsets.add("i32 " + reference);
                }
                lines.add("@" + def.layoutSymbol() + ".refs = private unnamed_addr"
                    + " constant [" + def.referenceOffsets().size() + " x i32] ["
                    + String.join(", ", offsets) + "]");
            }
            lines.add("@" + def.layoutSymbol()
                + " = internal constant %struct.deal_layout { i32 "
                + def.layoutId() + ", i32 0, i64 " + def.size() + ", "
                + references + " }");
            lines.add("@" + def.fieldsSymbol() + " = internal global ["
                + def.fields().size() + " x %struct.deal_class_field]"
                + " zeroinitializer");
            lines.add("@" + def.constructionSymbol()
                + " = internal constant %struct.deal_class_layout { "
                + session.data.operand(session.data.intern(def.atomText()))
                + ", %struct.deal_class_field* "
                + (def.fields().isEmpty() ? "null"
                    : "getelementptr ([" + def.fields().size()
                        + " x %struct.deal_class_field], [" + def.fields().size()
                        + " x %struct.deal_class_field]* @" + def.fieldsSymbol()
                        + ", i64 0, i64 0)")
                + ", i64 " + def.fields().size() + " }");
            lines.add("@" + def.planSymbol()
                + " = internal constant %struct.deal_json_class_plan {"
                + " %struct.deal_class_layout* @" + def.constructionSymbol()
                + ", i8* bitcast (%struct.deal_outcome_data"
                + " (%struct.deal_class_layout*, %struct.deal_data*, i8*,"
                + " i8*)* @"
                + constructName(def) + " to i8*)"
                + ", i8* bitcast (i32 (%struct.deal_data*, i64,"
                + " %struct.deal_check_input*, i8*, i8*)* @"
                + readName(def) + " to i8*)"
                + ", i8* null }");
        }
        // The emitted layouts: one record per declared class. Every array
        // element storage layout is runtime-owned (C3), so a decoded array and
        // an emitted array construction record the same element layout.
        List<String> copies = new ArrayList<>();
        for (ClassDef def : classes) {
            copies.add(layoutLiteral(def));
        }
        if (!copies.isEmpty()) {
            lines.add("@deal.class.layouts = internal constant ["
                + copies.size() + " x %struct.deal_layout] ["
                + String.join(", ", copies) + "]");
        }
        return List.copyOf(lines);
    }

    /** One class layout record copy for the emit-visible install array. */
    private String layoutLiteral(ClassDef def) {
        String references = def.referenceOffsets().isEmpty()
            ? "i32* null, i64 0"
            : "i32* getelementptr ([" + def.referenceOffsets().size()
                + " x i32], [" + def.referenceOffsets().size()
                + " x i32]* @" + def.layoutSymbol() + ".refs, i64 0, i64 0)"
                + ", i64 " + def.referenceOffsets().size();
        return "%struct.deal_layout { i32 " + def.layoutId() + ", i32 0, i64 "
            + def.size() + ", " + references + " }";
    }

    /**
     * {@code deal_class_plans_init}: installs the emitted layout table before
     * any allocation (M1) and fills every declared-field entry with the
     * interned descriptor atom of its declared type, so the published
     * construction layout and the JSON walks never read an unset member. It
     * runs from {@code deal_project_start} after the arm table and before every
     * unit's initialization.
     */
    String initFunction() {
        StringBuilder out = new StringBuilder();
        out.append("define internal void @deal_class_plans_init() {\n");
        out.append("Lentry:\n");
        List<ClassDef> classes = session.classes();
        int layoutCount = classes.size();
        if (layoutCount != 0) {
            session.referenceRuntimeEntry("deal_layout_install");
            out.append(LlvmIrPrinter.indent(1))
                .append("call void @deal_layout_install(%struct.deal_layout* ")
                .append(arrayElementPointer(layoutCount))
                .append(", i64 ").append(layoutCount).append(")\n");
        }
        int counter = 0;
        for (ClassDef def : classes) {
            for (int index = 0; index < def.fields().size(); index++) {
                Field field = def.fields().get(index);
                session.referenceRuntimeEntry("deal_descriptor_intern");
                String name = "%f" + (counter++);
                out.append(LlvmIrPrinter.indent(1)).append(name)
                    .append(" = getelementptr [")
                    .append(def.fields().size())
                    .append(" x %struct.deal_class_field], [")
                    .append(def.fields().size())
                    .append(" x %struct.deal_class_field]* @")
                    .append(def.fieldsSymbol())
                    .append(", i64 0, i64 ").append(index).append("\n");
                String names = "%fn" + (counter++);
                out.append(LlvmIrPrinter.indent(1)).append(names)
                    .append(" = getelementptr %struct.deal_class_field,"
                        + " %struct.deal_class_field* ").append(name)
                    .append(", i32 0, i32 0\n");
                out.append(LlvmIrPrinter.indent(1)).append("store ")
                    .append(session.data.operand(
                        session.data.intern(field.layout().name())))
                    .append(", i8** ").append(names).append('\n');
                String atom = "%fa" + (counter++);
                out.append(LlvmIrPrinter.indent(1)).append(atom)
                    .append(" = call %struct.deal_descriptor*"
                        + " @deal_descriptor_intern(")
                    .append(session.data.operand(session.data.intern(
                        field.layout().descriptor().canonicalSpecText())))
                    .append(")\n");
                String descriptors = "%fd" + (counter++);
                out.append(LlvmIrPrinter.indent(1)).append(descriptors)
                    .append(" = getelementptr %struct.deal_class_field,"
                        + " %struct.deal_class_field* ").append(name)
                    .append(", i32 0, i32 1\n");
                String reduced = "%fr" + (counter++);
                out.append(LlvmIrPrinter.indent(1)).append(reduced)
                    .append(" = bitcast %struct.deal_descriptor* ").append(atom)
                    .append(" to i8*\n");
                out.append(LlvmIrPrinter.indent(1)).append("store i8* ")
                    .append(reduced).append(", i8** ").append(descriptors)
                    .append('\n');
                String optional = "%fo" + (counter++);
                out.append(LlvmIrPrinter.indent(1)).append(optional)
                    .append(" = getelementptr %struct.deal_class_field,"
                        + " %struct.deal_class_field* ").append(name)
                    .append(", i32 0, i32 2\n");
                out.append(LlvmIrPrinter.indent(1)).append("store i32 ")
                    .append(field.layout().required() ? 0 : 1).append(", i32* ")
                    .append(optional).append('\n');
            }
        }
        out.append(LlvmIrPrinter.indent(1)).append("ret void\n}\n");
        return out.toString();
    }

    /** The {@code %struct.deal_layout*} operand of the install table. */
    private String arrayElementPointer(int count) {
        return "getelementptr ([" + count + " x %struct.deal_layout], ["
            + count + " x %struct.deal_layout]* @deal.class.layouts,"
            + " i64 0, i64 0)";
    }

    // =========================================================================
    // The emitted seams and the plan lookup
    // =========================================================================

    /** The typed {@code i8*} operand of one interned class atom or name. */
    private String stringPointer(String text) {
        return session.data.operand(session.data.intern(text));
    }

    static String constructName(ClassDef def) {
        return "deal.class." + def.index() + ".construct";
    }

    static String readName(ClassDef def) {
        return "deal.class." + def.index() + ".read";
    }

    /** Every generated function of the class and JSON plan surface. */
    Map<String, String> functions() {
        Map<String, String> functions = new LinkedHashMap<>();
        for (ClassDef def : session.classes()) {
            functions.put("deal.class." + def.index() + ".construct",
                constructSeam(def));
            functions.put("deal.class." + def.index() + ".read", readSeam(def));
        }
        if (!session.classes().isEmpty()) {
            functions.put(LOOKUP_NAME, lookupFunction());
        }
        return functions;
    }

    static final String LOOKUP_NAME = "deal_class_plan_lookup";

    /** The {@code deal_json_class_plan} IR type of one plan record. */
    static final String PLAN_TYPE = "%struct.deal_json_class_plan";

    /**
     * The {@code construct} seam of {@code JsonFromClass}: the runtime hands
     * the already validated and decoded field data of one plan, and this seam
     * materializes exactly one instance of the emitted typed record, tags it
     * with the class atom and roots nothing itself (the runtime roots the
     * decoded field data across this call).
     */
    private String constructSeam(ClassDef def) {
        Ir ir = new Ir();
        String instance = ir.temp("call i8* @deal_alloc(i32 " + def.layoutId()
            + ", i64 " + def.size() + ")");
        session.referenceRuntimeEntry("deal_alloc");
        String typed = ir.temp("bitcast i8* " + instance + " to "
            + def.recordName() + "*");
        String atomPointer = ir.temp("getelementptr " + def.recordName() + ", "
            + def.recordName() + "* " + typed + ", i32 0, i32 0");
        ir.line("store " + stringPointer(def.atomText())
            + ", i8** " + atomPointer);
        for (int index = 0; index < def.fields().size(); index++) {
            Field field = def.fields().get(index);
            String indexValue = ir.temp("getelementptr %struct.deal_data,"
                + " %struct.deal_data* %values, i64 " + index);
            String datum = ir.temp("load %struct.deal_data,"
                + " %struct.deal_data* " + indexValue);
            String native_ = LlvmDataValues.unbox(session, ir,
                field.layout().descriptor(), datum);
            if (field.layout().descriptor()
                    instanceof RuntimeDescriptor.Boolean) {
                native_ = ir.temp("zext i1 " + native_ + " to i8");
            }
            String slot = ir.temp("getelementptr " + def.recordName() + ", "
                + def.recordName() + "* " + typed + ", i32 0, i32 "
                + field.structIndex());
            ir.line("store " + field.slotType() + " " + native_ + ", "
                + field.slotType() + "* " + slot);
            if (holdsManagedReference(field)) {
                storeBarrier(ir, field, instance, slot, native_);
            }
            if (field.presenceIndex() >= 0) {
                // The runtime's presence array is indexed by the field's
                // declaration position (R6), never by its optional ordinal.
                String presence = ir.temp("getelementptr i8, i8* %presence,"
                    + " i64 " + index);
                String flag = ir.temp("load i8, i8* " + presence);
                String presenceSlot = ir.temp("getelementptr " + def.recordName()
                    + ", " + def.recordName() + "* " + typed + ", i32 0, i32 "
                    + (1 + field.presenceIndex()));
                ir.line("store i8 " + flag + ", i8* " + presenceSlot);
            }
        }
        String outcome = ir.temp("insertvalue %struct.deal_outcome_data"
            + " zeroinitializer, i32 " + LlvmTypeMapper.DATA_CLASS + ", 1, 0");
        outcome = ir.temp("insertvalue %struct.deal_outcome_data " + outcome
            + ", i8* " + instance + ", 1, 3");
        ir.line("ret %struct.deal_outcome_data " + outcome);
        return function("deal.class." + def.index() + ".construct",
            "%struct.deal_outcome_data", List.of(
                "%struct.deal_class_layout* %layout",
                "%struct.deal_data* %values",
                "i8* %presence",
                "i8* %context"), ir);
    }

    /**
     * The M2 insertion barrier of one class-field store, run on the committed
     * slot: a persistent datum slot shades the datum's managed payload through
     * the tag-aware entry, and every other managed-reference slot shades the
     * just-stored referent.
     */
    private void storeBarrier(Ir ir, Field field, String instance,
                              String slot, String value) {
        if (isTaggedDatum(field)) {
            session.referenceRuntimeEntry("deal_store_barrier_data");
            ir.line("call void @deal_store_barrier_data(i8* " + instance
                + ", " + LlvmTypeMapper.DATA + "* " + slot + ")");
            return;
        }
        session.referenceRuntimeEntry("deal_store_barrier");
        String referent = LlvmTypeMapper.REFERENCE_NULLISH.equals(
            field.slotType())
            ? value
            : ir.temp("bitcast " + field.slotType() + " " + value
                + " to i8*");
        ir.line("call void @deal_store_barrier(i8* " + instance + ", i8* "
            + referent + ")");
    }

    /**
     * The {@code read_field} seam of {@code JsonToClass}: one declaration-order
     * field of the instance as its closed check presentation, with the carried
     * class atom of a class-typed field, or 0 when the datum is not an instance
     * of the plan's class. An absent optional field answers presence 0.
     */
    private String readSeam(ClassDef def) {
        Ir ir = new Ir();
        String tagPointer = ir.temp("getelementptr %struct.deal_data,"
            + " %struct.deal_data* %instance, i32 0, i32 0");
        ir.line("%tag = load i32, i32* " + tagPointer);
        ir.line("%isclass = icmp eq i32 %tag, " + LlvmTypeMapper.DATA_CLASS);
        ir.line("br i1 %isclass, label %payload, label %mismatch");
        ir.label("payload");
        String referencePointer = ir.temp("getelementptr %struct.deal_data,"
            + " %struct.deal_data* %instance, i32 0, i32 3");
        ir.line("%referent = load i8*, i8** " + referencePointer);
        ir.line("%typed = bitcast i8* %referent to " + def.recordName() + "*");
        String atomPointer = ir.temp("getelementptr " + def.recordName() + ", "
            + def.recordName() + "* %typed, i32 0, i32 0");
        ir.line("%atom = load i8*, i8** " + atomPointer);
        ir.line("%same = call i1 @deal_text_equals(i8* %atom, "
            + stringPointer(def.atomText()) + ")");
        ir.line("br i1 %same, label %scan, label %mismatch");
        ir.label("mismatch");
        ir.line("ret i32 0");
        ir.label("scan");
        ir.line("switch i64 %index, label %mismatch [ "
            + fieldSwitchArms(def) + "]");
        for (int index = 0; index < def.fields().size(); index++) {
            Field field = def.fields().get(index);
            ir.label("field" + index);
            if (field.presenceIndex() >= 0) {
                String presence = ir.temp("load i8, i8* " + ir.temp(
                    "getelementptr " + def.recordName() + ", "
                        + def.recordName() + "* %typed, i32 0, i32 "
                        + (1 + field.presenceIndex())));
                String present = ir.temp("icmp ne i8 " + presence + ", 0");
                ir.line("br i1 " + present + ", label %present" + index
                    + ", label %absent" + index);
                ir.label("absent" + index);
                ir.line("store i8 0, i8* %present");
                ir.line("ret i32 1");
                ir.label("present" + index);
            }
            String slot = ir.temp("load " + field.slotType() + ", "
                + field.slotType() + "* " + ir.temp("getelementptr "
                    + def.recordName() + ", " + def.recordName()
                    + "* %typed, i32 0, i32 " + field.structIndex()));
            String native_ = slot;
            if (field.layout().descriptor()
                    instanceof RuntimeDescriptor.Boolean) {
                native_ = ir.temp("trunc i8 " + slot + " to i1");
            }
            String datum = LlvmDataValues.box(session, ir,
                field.layout().descriptor(), native_);
            ir.line("store i8 1, i8* %present");
            ir.line("store i32 " + LlvmTypeMapper.PRESENTATION_DATUM
                + ", i32* " + ir.temp("getelementptr"
                    + " %struct.deal_check_input, %struct.deal_check_input*"
                    + " %value, i32 0, i32 0"));
            ir.line("store %struct.deal_data " + datum
                + ", %struct.deal_data* " + ir.temp("getelementptr"
                    + " %struct.deal_check_input, %struct.deal_check_input*"
                    + " %value, i32 0, i32 2"));
            if (field.layout().descriptor()
                    instanceof RuntimeDescriptor.Class classType) {
                ir.line("store " + stringPointer(
                        classType.classId().text())
                    + ", i8** " + ir.temp("getelementptr"
                        + " %struct.deal_check_input, %struct.deal_check_input*"
                        + " %value, i32 0, i32 3"));
            }
            ir.line("ret i32 1");
        }
        return function(readName(def), "i32", List.of(
            "%struct.deal_data* %instance",
            "i64 %index",
            "%struct.deal_check_input* %value",
            "i8* %present",
            "i8* %context"), ir);
    }

    /** The {@code switch} arms of one read seam: one per declared field. */
    private static String fieldSwitchArms(ClassDef def) {
        List<String> arms = new ArrayList<>();
        for (int index = 0; index < def.fields().size(); index++) {
            arms.add("i64 " + index + ", label %field" + index);
        }
        return String.join(" ", arms) + " ";
    }

    /**
     * {@code deal_class_plan_lookup}: the emitted class atom to its plan
     * record, the one lookup the runtime JSON walks recurse through.
     */
    private String lookupFunction() {
        StringBuilder out = new StringBuilder();
        out.append("define internal ").append(PLAN_TYPE).append("* @")
            .append(LOOKUP_NAME).append("(i8* %atom, i8* %context) {\n")
            .append("Lentry:\n");
        int counter = 0;
        for (ClassDef def : session.classes()) {
            out.append(LlvmIrPrinter.indent(1)).append("%m").append(counter)
                .append(" = call i1 @deal_text_equals(i8* %atom, ")
                .append(stringPointer(def.atomText())).append(")\n");
            out.append(LlvmIrPrinter.indent(1)).append("br i1 %m")
                .append(counter).append(", label %hit").append(def.index())
                .append(", label %next").append(def.index()).append('\n');
            out.append("hit").append(def.index()).append(":\n");
            out.append(LlvmIrPrinter.indent(1)).append("ret ").append(PLAN_TYPE)
                .append("* @").append(def.planSymbol()).append('\n');
            out.append("next").append(def.index()).append(":\n");
            counter++;
        }
        out.append(LlvmIrPrinter.indent(1)).append("ret ").append(PLAN_TYPE)
            .append("* null\n}\n");
        return out.toString();
    }

    // =========================================================================
    // The textual IR builder of one generated seam
    // =========================================================================

    private static String function(String name, String result,
                                   List<String> parameters, Ir ir) {
        return "define internal " + result + " @" + name + "("
            + String.join(", ", parameters) + ") {\nLentry:\n" + ir.text()
            + "}\n";
    }

    /** One plain generated function body builder. */
    static final class Ir implements LlvmDataValues.Sink {

        private final StringBuilder out = new StringBuilder();
        private int counter;

        @Override
        public void line(String text) {
            out.append(LlvmIrPrinter.indent(1)).append(text).append('\n');
        }

        @Override
        public String temp(String instruction) {
            String name = "%t" + (counter++);
            line(name + " = " + instruction);
            return name;
        }

        /** Opens one basic block label at column zero. */
        String label(String label) {
            out.append(label).append(":\n");
            return label + ":\n";
        }

        String text() {
            return out.toString();
        }
    }
}
