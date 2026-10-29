package deal.codegen.llvm;

import deal.semantic.ir.RuntimeDescriptor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The one descriptor/ABI-to-IR type mapping of the native backend
 * ({@code llvm-native-codegen-cfg-ssa} C3, {@code llvm-native-runtime-value-model}
 * R2/R4) and the single pointer-type constructor every emitted pointer
 * spelling goes through.
 *
 * <p>{@link #representationOf(RuntimeDescriptor)} answers the staged native
 * representation of one DEAL value: {@code number} is {@code double},
 * {@code boolean} is {@code i1} in SSA and {@code i8} across the C ABI,
 * {@code null} and {@code undefined} are the runtime singletons and therefore
 * carry no <em>value</em> representation at all (an empty answer, projected
 * through {@code deal_outcome} and reconstructed by the runtime per C4; a
 * declared field, element or binding slot of those types is the singleton
 * pointer, which {@link #slotTypeOf(RuntimeDescriptor)} answers), and a
 * statically resolvable function identity is the static code pointer
 * {@code i8*}. Every other descriptor is the managed representation of C3 and
 * is not part of the staged subset: the answer is empty-failure
 * ({@link #unsupported(RuntimeDescriptor)}) and the classifier rejects the
 * producing or consuming operation with its own origin (C9).</p>
 *
 * <p>{@link #llvmTypeOfCType(String)} is the published C type to IR type
 * mapping {@link LlvmRuntimeAbi} renders every declaration through, so the
 * module's declaration set and the published headers can never disagree about
 * a parameter or return type.</p>
 */
public final class LlvmTypeMapper {

    private LlvmTypeMapper() {
        // Static mapping only; no instances.
    }

    // =========================================================================
    // The single pointer-type constructor
    // =========================================================================

    /**
     * The one pointer-type constructor: {@code T*} for the mapped pointee
     * {@code T}. C {@code void} and {@code char} pointees have no IR type of
     * their own and are the byte {@code i8}, so {@code void*},
     * {@code char*} and {@code unsigned char*} all spell {@code i8*}.
     */
    public static String pointerTo(String pointee) {
        Objects.requireNonNull(pointee, "pointee must not be null");
        String element = switch (pointee) {
            case "void", "char", "unsigned char", "i8" -> "i8";
            default -> pointee;
        };
        if (element.isBlank() || element.equals("void")) {
            throw new IllegalArgumentException(
                "no IR pointer type exists for pointee " + pointee);
        }
        return element + "*";
    }

    // =========================================================================
    // C type mapping (the published ABI declarations)
    // =========================================================================

    /** The closed scalar C spelling to IR type table. */
    private static final Map<String, String> SCALARS = scalars();

    private static Map<String, String> scalars() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("int", "i32");
        map.put("unsigned int", "i32");
        map.put("uint8_t", "i8");
        map.put("int32_t", "i32");
        map.put("uint32_t", "i32");
        map.put("int64_t", "i64");
        map.put("uint64_t", "i64");
        map.put("size_t", "i64");
        map.put("double", "double");
        map.put("float", "float");
        map.put("deal_handle", "i64");
        map.put("deal_async_token", "i64");
        map.put("deal_error_token", "i64");
        return java.util.Collections.unmodifiableMap(
            new java.util.LinkedHashMap<>(map));
    }

    /**
     * The published C enum types; every one crosses the ABI as a 32-bit
     * integer ({@code deal_rt.h}).
     */
    private static final List<String> C_ENUMS = List.of(
        "deal_status",
        "deal_value_kind",
        "deal_failure_kind",
        "deal_host_completion_kind",
        "deal_host_export_kind",
        "deal_data_tag",
        "deal_nullish_tag",
        "deal_presence",
        "deal_descriptor_kind",
        "deal_presentation",
        "deal_unicode_defect");

    /**
     * The published function-pointer typedefs; each is one code pointer,
     * so it has no IR type of its own and is the byte pointer.
     */
    private static final List<String> C_FUNCTION_POINTERS = List.of(
        "deal_host_function",
        "deal_host_async_function",
        "deal_host_service",
        "deal_reference_visit",
        "deal_root_source_scan",
        "deal_layout_trace",
        "deal_layout_release",
        "deal_json_plan_lookup",
        "deal_json_construct",
        "deal_json_read_field",
        "deal_fn_thunk");

    /**
     * The named record layouts this backend declares: the published runtime
     * records whose members the emitted static data follows, and the opaque
     * runtime representations of C3 (opaque because every published
     * declaration reaches them through a pointer only).
     */
    private static final Map<String, String> RECORDS = records();

    private static Map<String, String> records() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("deal_value", "{ i32, i32 }");
        map.put("deal_data", "{ i32, i32, double, i8* }");
        map.put("deal_layout", "{ i32, i32, i64, i32*, i64 }");
        map.put("deal_origin", LlvmRuntimeAbi.ORIGIN_LAYOUT);
        map.put("deal_arm_record", LlvmRuntimeAbi.ARM_RECORD_LAYOUT);
        map.put("deal_arm_table", "{ i32, i32, %struct.deal_arm_record* }");
        map.put("deal_outcome", "{ i8* }");
        map.put("deal_outcome_value", "{ i8*, { i32, i32 } }");
        map.put("deal_outcome_number", "{ i8*, double }");
        map.put("deal_outcome_boolean", "{ i8*, i8 }");
        map.put("deal_outcome_string", "{ i8*, i8* }");
        map.put("deal_outcome_data", "{ i8*, %struct.deal_data }");
        map.put("deal_outcome_reference", "{ i8*, i8* }");
        map.put("deal_check_input", "{ i32, i32, %struct.deal_data, i8*, i8* }");
        map.put("deal_check_context", "{ %struct.deal_origin*, i32 }");
        map.put("deal_class_field", "{ i8*, i8*, i32 }");
        map.put("deal_class_layout", "{ i8*, %struct.deal_class_field*, i64 }");
        map.put("deal_json_class_plan",
            "{ %struct.deal_class_layout*, i8*, i8*, i8* }");
        map.put("deal_class_supplied",
            "{ i8*, %struct.deal_check_input, %struct.deal_origin }");
        map.put("deal_arm_parameter", "{ i8*, i8* }");
        map.put("deal_failure_admission", "{ i32, i8*, i8*, i8*, i8*, i8* }");
        map.put("deal_host_span", "{ i8*, i64 }");
        map.put("deal_host_error_text", "{ i8*, i8*, i8*, i8* }");
        map.put("deal_host_failure", "{ i32, i8*, i8*, i8*, i8*, i64 }");
        map.put("deal_host_result",
            "{ i32, { i32, i32 }, { i32, i8*, i8*, i8*, i8*, i64 } }");
        // C2/C3/H6 opacity: the emitted project module owns both layouts, so
        // the mirror names them and never declares a member.
        map.put("deal_module", "opaque");
        map.put("deal_export_entry", "opaque");
        for (String opaque : List.of(
                "deal_string", "deal_array", "deal_table", "deal_object",
                "deal_fn", "deal_error", "deal_async_op",
                "deal_descriptor", "deal_completion_frame",
                "deal_value_crossing",
                "deal_host_completion", "deal_host_field", "deal_host_class",
                "deal_host_export", "deal_host_module",
                "deal_host_registry_entry", "deal_gc_statistics_record",
                "deal_host_declared_module", "deal_host_binding")) {
            map.put(opaque, "opaque");
        }
        return java.util.Collections.unmodifiableMap(
            new java.util.LinkedHashMap<>(map));
    }

    /**
     * The IR spelling of one published C type: scalars, the closed enum set,
     * the function-pointer typedefs, the named record layouts, and pointers
     * through {@link #pointerTo(String)}. An unknown spelling is a mirror
     * defect and fails closed.
     */
    public static String llvmTypeOfCType(String cType) {
        Objects.requireNonNull(cType, "cType must not be null");
        String normalized = normalizeCType(cType);
        if (normalized.equals("void")) {
            return "void";
        }
        if (normalized.endsWith("*")) {
            String pointee = normalizeCType(
                normalized.substring(0, normalized.length() - 1));
            if (pointee.equals("void") || pointee.equals("char")
                    || pointee.equals("unsigned char")) {
                return pointerTo("i8");
            }
            return pointerTo(llvmTypeOfCType(pointee));
        }
        String scalar = SCALARS.get(normalized);
        if (scalar != null) {
            return scalar;
        }
        if (C_ENUMS.contains(normalized)) {
            return "i32";
        }
        if (C_FUNCTION_POINTERS.contains(normalized)) {
            return pointerTo("i8");
        }
        String record = RECORDS.get(normalized);
        if (record != null) {
            return "%struct." + normalized;
        }
        throw new IllegalArgumentException(
            "the runtime ABI mirror declares no IR type for C type '"
                + cType + "'");
    }

    /**
     * The IR name of one published C record type that has a real layout (not
     * the opaque C3 representations), or empty. This is what the C ABI needs
     * to know about a record: a record parameter crosses by pointer with
     * {@code byval} and a record return crosses through a hidden
     * {@code sret} parameter, exactly as a C compiler lowers the published
     * prototype.
     */
    public static Optional<String> recordTypeOf(String cType) {
        Objects.requireNonNull(cType, "cType must not be null");
        String normalized = normalizeCType(cType);
        String layout = RECORDS.get(normalized);
        if (layout == null || layout.equals("opaque")) {
            return Optional.empty();
        }
        return Optional.of("%struct." + normalized);
    }

    /**
     * Whether one published C record type crosses a call boundary in memory:
     * the system ABI passes and returns a record larger than two eightbytes
     * through memory (a hidden {@code sret} pointer for a return, a
     * {@code byval} pointer for a parameter). Every record this mirror
     * publishes is either a run of eightbyte-sized fields or a small
     * aggregate, so the plain size rule of the x86-64 ABI applies.
     */
    public static boolean crossesInMemory(String cType) {
        Objects.requireNonNull(cType, "cType must not be null");
        String normalized = normalizeCType(cType);
        String layout = RECORDS.get(normalized);
        if (layout == null || layout.equals("opaque")) {
            return false;
        }
        return recordSize(normalized) > 16;
    }

    /** The x86-64 size of one published record, in bytes. */
    private static int recordSize(String record) {
        return sizeOfLayout(RECORDS.get(record));
    }

    /**
     * The x86-64 byte offset of one member of a published record layout, that
     * is {@code offsetof(<record>, <member>)} for the field order this mirror
     * pins from {@code deal/native/deal_rt.h}. It is the one arithmetic an
     * emitted reference-map slot names a member with (M1/M4).
     */
    public static int recordFieldOffset(String cType, int member) {
        Objects.requireNonNull(cType, "cType must not be null");
        String normalized = normalizeCType(cType);
        String layout = RECORDS.get(normalized);
        if (layout == null || layout.equals("opaque")) {
            throw new IllegalArgumentException(
                "no published layout for the record " + normalized);
        }
        List<String> fields = splitFields(layout.strip().substring(1,
            layout.strip().length() - 1));
        if (member < 0 || member >= fields.size()) {
            throw new IllegalArgumentException("the record " + normalized
                + " declares " + fields.size() + " members; " + member
                + " is outside them");
        }
        int offset = 0;
        for (int index = 0; index < member; index++) {
            String field = fields.get(index);
            offset = roundUp(offset, alignmentOf(field)) + sizeOfType(field);
        }
        return roundUp(offset, alignmentOf(fields.get(member)));
    }

    /**
     * The member index of {@code deal_data.reference} in the published record
     * layout ({@code tag, reserved, number, reference}).
     */
    private static final int DATA_REFERENCE_MEMBER = 3;

    /**
     * {@code offsetof(deal_data, reference)}: the one member of a persistent
     * tagged datum a precise reference-map slot and a tag-aware root name
     * (C3, M1/M4). The datum's tag, reserved and number words never hold a
     * heap address.
     */
    public static int dataReferenceOffset() {
        return recordFieldOffset("deal_data", DATA_REFERENCE_MEMBER);
    }

    /** The size of one IR layout body ({@code { ... }}), field by field. */
    private static int sizeOfLayout(String layout) {
        String body = layout.strip();
        if (!body.startsWith("{") || !body.endsWith("}")) {
            throw new IllegalStateException(
                "not a record layout: " + layout);
        }
        int offset = 0;
        int alignment = 1;
        for (String field : splitFields(body.substring(1,
                body.length() - 1))) {
            int fieldAlignment = alignmentOf(field);
            int fieldSize = sizeOfType(field);
            alignment = Math.max(alignment, fieldAlignment);
            offset = roundUp(offset, fieldAlignment) + fieldSize;
        }
        return roundUp(offset, alignment);
    }

    /** The fields of one layout body, split at depth zero. */
    private static List<String> splitFields(String body) {
        List<String> fields = new ArrayList<>();
        int depth = 0;
        StringBuilder current = new StringBuilder();
        for (int index = 0; index < body.length(); index++) {
            char character = body.charAt(index);
            if (character == '{') {
                depth++;
            } else if (character == '}') {
                depth--;
            }
            if (character == ',' && depth == 0) {
                fields.add(current.toString().strip());
                current.setLength(0);
                continue;
            }
            current.append(character);
        }
        if (!current.toString().isBlank()) {
            fields.add(current.toString().strip());
        }
        return fields;
    }

    /**
     * The module-owned scalar-nullish record layouts of C3: they exist only
     * inside the emitted module, so the record-size rule reads them here.
     */
    private static final Map<String, String> STAGED_LAYOUTS = java.util.Map.of(
        "deal_nullish_number", "{ i32, double }",
        "deal_nullish_boolean", "{ i32, i1 }",
        "deal_outcome_nullish_number",
            "{ i8*, %struct.deal_nullish_number }",
        "deal_outcome_nullish_boolean",
            "{ i8*, %struct.deal_nullish_boolean }");

    /** The ABI alignment of one IR field type. */
    private static int alignmentOf(String type) {
        if (type.strip().endsWith("*")) {
            return 8;
        }
        String staged = type.startsWith("%struct.")
            ? STAGED_LAYOUTS.get(type.substring("%struct.".length())) : null;
        if (staged != null) {
            return alignmentOfLayout(staged);
        }
        return switch (type) {
            case "i1", "i8" -> 1;
            case "i16" -> 2;
            case "i32", "float" -> 4;
            default -> 8;
        };
    }

    /** The alignment of one IR layout body ({@code { ... }}). */
    private static int alignmentOfLayout(String layout) {
        String body = layout.strip();
        int alignment = 1;
        for (String field : splitFields(body.substring(1, body.length() - 1))) {
            alignment = Math.max(alignment, alignmentOf(field));
        }
        return alignment;
    }

    /** The x86-64 size of one IR field type. */
    private static int sizeOfType(String type) {
        String text = type.strip();
        if (text.endsWith("*")) {
            return 8;
        }
        if (text.startsWith("%struct.")) {
            String record = text.substring("%struct.".length());
            String layout = STAGED_LAYOUTS.containsKey(record)
                ? STAGED_LAYOUTS.get(record) : RECORDS.get(record);
            if (layout == null || layout.equals("opaque")) {
                throw new IllegalStateException(
                    "no published layout for the record " + record);
            }
            if (STAGED_LAYOUTS.containsKey(record)) {
                return sizeOfLayout(layout);
            }
            return recordSize(record);
        }
        if (text.startsWith("{")) {
            return sizeOfLayout(text);
        }
        return switch (text) {
            case "i1", "i8" -> 1;
            case "i16" -> 2;
            case "i32", "float" -> 4;
            case "i64", "double" -> 8;
            default -> {
                if (text.endsWith("*")) {
                    yield 8;
                }
                throw new IllegalStateException(
                    "no x86-64 size for the IR type " + type);
            }
        };
    }

    private static int roundUp(int value, int alignment) {
        int remainder = value % alignment;
        return remainder == 0 ? value : value + (alignment - remainder);
    }

    /**
     * The emitted record layout of one published C record type, or empty when
     * the mirror declares none: the parity suite compares it against the
     * published header's field order, so a record's members can never drift
     * silently between the header and the emitted IR.
     */
    public static java.util.Optional<String> recordLayout(String cType) {
        Objects.requireNonNull(cType, "cType must not be null");
        return java.util.Optional.ofNullable(RECORDS.get(cType));
    }

    /** Strips qualifiers and collapses whitespace of one C type spelling. */
    private static String normalizeCType(String cType) {
        String text = " " + cType.strip() + " ";
        text = text.replace("const ", " ").replace("volatile ", " ")
            .replaceAll("\\s+", " ");
        return text.strip();
    }

    /**
     * The named record type definitions this backend emits, in the order the
     * textual module declares them: the C3 runtime representations and the
     * C2 project records. Every definition the mirror references is present.
     */
    public static List<String> recordDefinitions() {
        List<String> definitions = new ArrayList<>();
        for (Map.Entry<String, String> entry : RECORDS.entrySet()) {
            if (MODULE_OWNED_RECORDS.contains(entry.getKey())) {
                continue;
            }
            definitions.add("%struct." + entry.getKey() + " = type "
                + entry.getValue());
        }
        return List.copyOf(definitions);
    }

    /**
     * The records the emitted project module itself declares (C2/H6): their
     * members exist only inside the module, so this mapping never publishes a
     * layout for them and only names the types.
     */
    public static final List<String> MODULE_OWNED_RECORDS = List.of(
        "deal_module", "deal_export_entry");

    /**
     * The opaque declarations of the module-owned records: the form a
     * consumer that only holds pointers (the ABI mirror probe) compiles
     * against.
     */
    public static List<String> moduleOwnedOpaqueDefinitions() {
        List<String> definitions = new ArrayList<>();
        for (String record : MODULE_OWNED_RECORDS) {
            definitions.add("%struct." + record + " = type opaque");
        }
        return List.copyOf(definitions);
    }

    // =========================================================================
    // DEAL descriptor mapping (C3)
    // =========================================================================

    /** The SSA representation of the {@code boolean} descriptor. */
    public static final String BOOLEAN_SSA = "i1";

    /** The C ABI representation of the {@code boolean} descriptor (C3). */
    public static final String BOOLEAN_ABI = "i8";

    /** The representation of the {@code number} descriptor. */
    public static final String NUMBER = "double";

    /** The managed function value of C3/C5 ({@code deal_fn*}). */
    public static final String FN_POINTER = "%struct.deal_fn*";

    /** The managed {@code string} representation, {@code deal_string*} (C3). */
    public static final String STRING = "%struct.deal_string*";

    /** The managed {@code T[]} representation, {@code deal_array*} (C3). */
    public static final String ARRAY = "%struct.deal_array*";

    /**
     * The managed {@code Record<string, unknown>} representation,
     * {@code deal_table*} (C3: the internal C name is retained and the value is
     * never a source {@code table} type).
     */
    public static final String TABLE = "%struct.deal_table*";

    /**
     * The persistent tagged datum of one {@code unknown} position (C3, R3):
     * a structural record, never a crossing-scoped {@code deal_value}.
     */
    public static final String DATA = "%struct.deal_data";

    /** The managed builtin {@code Error} instance ({@code deal_error*}). */
    public static final String ERROR_POINTER = "%struct.deal_error*";

    /**
     * The scalar nullish representation (C3): the closed tag
     * {@code deal_nullish_tag} followed by the declared base value, so the
     * stored base never collides with a nullish tag.
     */
    public static final String NULLISH_NUMBER = "%struct.deal_nullish_number";
    public static final String NULLISH_BOOLEAN = "%struct.deal_nullish_boolean";

    /**
     * The reference nullish representation (C3): the base pointer, or the
     * distinct admitted null/undefined singleton — one pointer, no presence
     * metadata.
     */
    public static final String REFERENCE_NULLISH = "i8*";

    /**
     * The published {@code deal_presentation} discriminant of one checked
     * datum ({@code deal_rt.h}, R3/R4).
     */
    public static final int PRESENTATION_DATUM = 0;

    /** The published {@code deal_nullish_tag} discriminants ({@code deal_rt.h}). */
    public static final int NULLISH_BASE = 0;
    public static final int NULLISH_NULL = 1;
    public static final int NULLISH_UNDEFINED = 2;

    /** The published {@code deal_data_tag} discriminants used by this stage. */
    public static final int DATA_UNDEFINED = 0;
    public static final int DATA_NULL = 1;
    public static final int DATA_BOOLEAN = 2;
    public static final int DATA_NUMBER = 3;
    public static final int DATA_STRING = 4;
    public static final int DATA_ARRAY = 5;
    public static final int DATA_TABLE = 6;
    public static final int DATA_CLASS = 7;
    public static final int DATA_FUNCTION = 8;
    public static final int DATA_ERROR = 9;

    /**
     * The staged representation of one descriptor, or empty when the value
     * carries no representation at all (the {@code null}/{@code undefined}
     * singletons) or its representation is outside the staged set (the
     * containers and non-builtin classes of the data/class stage).
     */
    public static Optional<String> representationOf(RuntimeDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor must not be null");
        return switch (descriptor) {
            case RuntimeDescriptor.Number ignored -> Optional.of(NUMBER);
            case RuntimeDescriptor.Boolean ignored -> Optional.of(BOOLEAN_SSA);
            case RuntimeDescriptor.String ignored -> Optional.of(STRING);
            case RuntimeDescriptor.Unknown ignored -> Optional.of(DATA);
            case RuntimeDescriptor.Func ignored -> Optional.of(FN_POINTER);
            case RuntimeDescriptor.Table ignored -> Optional.of(TABLE);
            case RuntimeDescriptor.Array ignored -> Optional.of(ARRAY);
            case RuntimeDescriptor.Class classType ->
                Optional.of(classType.classId().equals(
                    deal.semantic.ir.ClassId.ERROR)
                    ? ERROR_POINTER : classRepresentation(classType.classId()));
            case RuntimeDescriptor.Nullable nullable ->
                nullableRepresentation(nullable);
            default -> Optional.empty();
        };
    }

    /**
     * The emitted per-layout class instance record of one nominal class
     * (C3): the runtime header, the optional-field presence bytes and the typed
     * field slots, all owned by the emitted module. The name derives from the
     * canonical class text alone, so two emissions of one project spell the
     * same type and two distinct class texts never share one (C9).
     */
    public static String classRecordName(deal.semantic.ir.ClassId classId) {
        Objects.requireNonNull(classId, "classId must not be null");
        String text = classId.text();
        StringBuilder sanitized = new StringBuilder();
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            boolean plain = (character >= 'a' && character <= 'z')
                || (character >= 'A' && character <= 'Z')
                || (character >= '0' && character <= '9');
            sanitized.append(plain ? character : '_');
        }
        long hash = 0xcbf29ce484222325L;
        for (byte unit : text.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            hash ^= (unit & 0xff);
            hash *= 0x100000001b3L;
        }
        return "%struct.deal_class." + sanitized + "."
            + String.format("%016x", hash);
    }

    /** The {@code deal_object*} representation of one nominal class (C3). */
    public static String classRepresentation(deal.semantic.ir.ClassId classId) {
        return classRecordName(classId) + "*";
    }

    /**
     * The IR type of one typed field slot or array element slot of the given
     * descriptor: the SSA representation, except that a {@code boolean} slot is
     * the published 0/1 byte so a stored slot never relies on the one-bit
     * in-memory layout and a declared {@code null}/{@code undefined} position
     * is the C3 singleton pointer (its data position always holds one of the
     * two distinct immutable singletons, never C {@code NULL}).
     */
    public static String slotTypeOf(RuntimeDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor must not be null");
        if (descriptor instanceof RuntimeDescriptor.Boolean) {
            return BOOLEAN_ABI;
        }
        if (isStaticSingleton(descriptor)) {
            return REFERENCE_NULLISH;
        }
        return representationOf(descriptor).orElseThrow(() ->
            new IllegalArgumentException(
                "no slot type for " + unsupported(descriptor)));
    }

    /**
     * The representation of one nullish descriptor (C3): a reference base is
     * carried as the base pointer or the admitted singleton, a scalar base as
     * the {@code {tag, value}} struct, and {@code null|undefined} as the two
     * distinct singletons (one pointer, never presence metadata).
     */
    private static Optional<String> nullableRepresentation(
            RuntimeDescriptor.Nullable nullable) {
        RuntimeDescriptor inner = nullable.inner();
        if (inner instanceof RuntimeDescriptor.Undefined) {
            return Optional.of(REFERENCE_NULLISH);
        }
        if (inner instanceof RuntimeDescriptor.Number) {
            return Optional.of(NULLISH_NUMBER);
        }
        if (inner instanceof RuntimeDescriptor.Boolean) {
            return Optional.of(NULLISH_BOOLEAN);
        }
        if (isManagedPointer(inner)
                || inner instanceof RuntimeDescriptor.Func) {
            return Optional.of(REFERENCE_NULLISH);
        }
        return Optional.empty();
    }

    /**
     * Whether one descriptor's representation holds a collector-managed
     * reference and therefore owns a root-frame slot when it is live across an
     * allocating call (M4): the {@code string} pointer, the builtin Error
     * pointer, the persistent {@code unknown} datum and every reference
     * nullish position (its base is a managed object or a nullish singleton).
     * A code pointer, a scalar and a scalar-nullish struct never do.
     */
    public static boolean isManagedValue(RuntimeDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor must not be null");
        if (descriptor instanceof RuntimeDescriptor.Unknown
                || descriptor instanceof RuntimeDescriptor.Func
                || isManagedPointer(descriptor)) {
            return true;
        }
        if (descriptor instanceof RuntimeDescriptor.Nullable nullable) {
            return representationOf(nullable)
                .filter(REFERENCE_NULLISH::equals).isPresent();
        }
        return false;
    }

    /**
     * Whether one descriptor's representation is a managed object pointer
     * (string, array, record, class instance, builtin Error instance): the
     * values a precise root slot and a reference map root (C3, M4).
     */
    public static boolean isManagedPointer(RuntimeDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor must not be null");
        return descriptor instanceof RuntimeDescriptor.String
            || descriptor instanceof RuntimeDescriptor.Table
            || descriptor instanceof RuntimeDescriptor.Array
            || descriptor instanceof RuntimeDescriptor.Class;
    }

    /**
     * Whether the descriptor's value is a static singleton: {@code null} or
     * {@code undefined}, whose C4 protocol omits the value slot and
     * reconstructs the singleton.
     */
    public static boolean isStaticSingleton(RuntimeDescriptor descriptor) {
        return descriptor instanceof RuntimeDescriptor.Null
            || descriptor instanceof RuntimeDescriptor.Undefined;
    }

    /**
     * Whether one recorded actual descriptor has a persistent
     * {@code deal_data} form (C3/R3): every supported DEAL datum. A function
     * value is the managed {@code deal_fn*} closure carrier of C3/C5, so its
     * datum carries the carrier as the managed {@code reference} of the
     * function tag. A nullish union counts through its base (its own tags name
     * the singleton arms); {@code void} has no data position at all.
     */
    public static boolean hasDatumForm(RuntimeDescriptor actual) {
        Objects.requireNonNull(actual, "actual must not be null");
        if (actual instanceof RuntimeDescriptor.Nullable nullable) {
            return hasDatumForm(nullable.inner());
        }
        return !(actual instanceof RuntimeDescriptor.Void);
    }

    /**
     * Whether one declared descriptor statically admits a value of the
     * recorded actual descriptor: equality, a {@code nullish} member, or a
     * supported data value at an {@code unknown} position. An admitted value
     * needs no runtime check; the staged boundary renders the identity of the
     * value (with the representation change of C3), never a guess.
     */
    public static boolean admits(RuntimeDescriptor declared,
                                 RuntimeDescriptor actual) {
        Objects.requireNonNull(declared, "declared must not be null");
        Objects.requireNonNull(actual, "actual must not be null");
        if (declared.equals(actual)) {
            return true;
        }
        if (declared instanceof RuntimeDescriptor.Unknown) {
            return hasDatumForm(actual)
                && !(actual instanceof RuntimeDescriptor.Nullable);
        }
        if (declared instanceof RuntimeDescriptor.Nullable nullable) {
            if (actual instanceof RuntimeDescriptor.Null) {
                return admitsNull(nullable);
            }
            if (actual instanceof RuntimeDescriptor.Undefined) {
                return admitsUndefined(nullable);
            }
            return admits(nullable.inner(), actual);
        }
        return false;
    }

    /**
     * Whether one nullish descriptor admits the language {@code null} member
     * (C3): its own flag, or a base atom that is the null value itself. The
     * canonical spelling carries a member through the base atom whenever the
     * flag would be redundant, exactly as the shared type relation reads it.
     */
    public static boolean admitsNull(RuntimeDescriptor.Nullable nullable) {
        Objects.requireNonNull(nullable, "nullable must not be null");
        return nullable.admitsNull()
            || nullable.inner() instanceof RuntimeDescriptor.Null;
    }

    /**
     * Whether one nullish descriptor admits the language {@code undefined}
     * member (C3): its own flag, or the canonical {@code null|undefined} whose
     * base is the undefined atom. Both readings of that atom are the one value,
     * so the absence projection and every admission proof must see the member.
     */
    public static boolean admitsUndefined(RuntimeDescriptor.Nullable nullable) {
        Objects.requireNonNull(nullable, "nullable must not be null");
        return nullable.admitsUndefined()
            || nullable.inner() instanceof RuntimeDescriptor.Undefined;
    }

    /**
     * Whether one declared descriptor admits a value of the recorded actual
     * descriptor without a runtime check: one of the {@link #admits} proofs,
     * or a pure representation change of C3 — a supported data value (a
     * nullish union included) boxed into a persistent datum at an
     * {@code unknown} position, and a singleton or a base value placed into
     * its nullish representation. No branch of this relation can fail, so a
     * boundary it covers renders the value's identity and never a check.
     */
    public static boolean convertible(RuntimeDescriptor declared,
                                      RuntimeDescriptor actual) {
        Objects.requireNonNull(declared, "declared must not be null");
        Objects.requireNonNull(actual, "actual must not be null");
        if (admits(declared, actual)) {
            return true;
        }
        if (declared instanceof RuntimeDescriptor.Unknown) {
            return hasDatumForm(actual);
        }
        if (declared instanceof RuntimeDescriptor.Nullable nullable) {
            if (actual instanceof RuntimeDescriptor.Null) {
                return admitsNull(nullable);
            }
            if (actual instanceof RuntimeDescriptor.Undefined) {
                return admitsUndefined(nullable);
            }
            if (actual instanceof RuntimeDescriptor.Nullable) {
                return actual.equals(declared);
            }
            return admits(nullable.inner(), actual);
        }
        return false;
    }

    /**
     * Whether the descriptor is inside the staged native representation set
     * (a native scalar, a static singleton, or a statically resolvable
     * function identity).
     */
    public static boolean isStagedRepresentation(RuntimeDescriptor descriptor) {
        return isStaticSingleton(descriptor)
            || representationOf(descriptor).isPresent();
    }

    /**
     * The message of an unsupported representation; the classifier carries it
     * into the origin-bearing E6005.
     */
    public static String unsupported(RuntimeDescriptor descriptor) {
        return "the descriptor '" + descriptor.canonicalSpecText()
            + "' has a managed runtime representation outside the staged"
            + " native skeleton";
    }

    // =========================================================================
    // C4 outcome shapes
    // =========================================================================

    /**
     * The ABI return type of one generated function or of one export surface
     * entry (C4): error-first, with the value slot exactly for a staged
     * non-singleton scalar result and none for {@code null},
     * {@code undefined} and result-only {@code void}.
     */
    public static String outcomeType(RuntimeDescriptor returnDescriptor) {
        Objects.requireNonNull(returnDescriptor, "returnDescriptor must not be null");
        if (isStaticSingleton(returnDescriptor)
                || returnDescriptor instanceof RuntimeDescriptor.Void) {
            return "%struct.deal_outcome";
        }
        return switch (returnDescriptor) {
            case RuntimeDescriptor.Number ignored -> "%struct.deal_outcome_number";
            case RuntimeDescriptor.Boolean ignored -> "%struct.deal_outcome_boolean";
            case RuntimeDescriptor.String ignored -> "%struct.deal_outcome_string";
            case RuntimeDescriptor.Unknown ignored -> "%struct.deal_outcome_data";
            case RuntimeDescriptor.Func ignored -> "%struct.deal_outcome_reference";
            case RuntimeDescriptor.Table ignored -> "%struct.deal_outcome_reference";
            case RuntimeDescriptor.Array ignored -> "%struct.deal_outcome_reference";
            case RuntimeDescriptor.Class ignored -> "%struct.deal_outcome_reference";
            case RuntimeDescriptor.Nullable nullable -> switch (
                    representationOf(nullable).orElseThrow(() ->
                        new IllegalArgumentException("no staged outcome shape for "
                            + unsupported(returnDescriptor)))) {
                case NULLISH_NUMBER -> "%struct.deal_outcome_nullish_number";
                case NULLISH_BOOLEAN -> "%struct.deal_outcome_nullish_boolean";
                default -> "%struct.deal_outcome_reference";
            };
            default -> throw new IllegalArgumentException(
                "no staged outcome shape for " + unsupported(returnDescriptor));
        };
    }

    /**
     * The ABI parameter type of one function parameter (C3): {@code boolean}
     * crosses as the 0/1 byte, {@code number} as binary64, and every managed
     * or nullish position as its staged representation.
     */
    public static String abiParameterType(RuntimeDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor must not be null");
        if (descriptor instanceof RuntimeDescriptor.Boolean) {
            return BOOLEAN_ABI;
        }
        return representationOf(descriptor).orElseThrow(() ->
            new IllegalArgumentException(
                "no staged ABI parameter type for " + unsupported(descriptor)));
    }

    /**
     * Whether one published record IR type crosses a call boundary in memory
     * (the x86-64 ABI's hidden {@code sret}/{@code byval} pointer rule).
     */
    public static boolean recordCrossesInMemory(String irRecordType) {
        Objects.requireNonNull(irRecordType, "irRecordType must not be null");
        String name = irRecordType.startsWith("%struct.")
            ? irRecordType.substring("%struct.".length()) : irRecordType;
        return crossesInMemory(name);
    }

    /** The x86-64 size of one IR type name, for the emitted record offsets. */
    public static int sizeOfIrType(String type) {
        return sizeOfType(type);
    }

    /** The x86-64 ABI alignment of one IR type name. */
    public static int alignmentOfIrType(String type) {
        return alignmentOf(type);
    }

    /**
     * The module-owned record layouts this stage needs beside the published
     * ones: the two scalar nullish representations and their outcome shapes.
     * They exist only inside the emitted module (no runtime entry receives or
     * returns one), so they are declared with the C2 project records.
     */
    public static List<String> stagedRecordDefinitions() {
        return List.of(
            "%struct.deal_nullish_number = type { i32, double }",
            "%struct.deal_nullish_boolean = type { i32, i1 }",
            "%struct.deal_outcome_nullish_number = type { i8*,"
                + " %struct.deal_nullish_number }",
            "%struct.deal_outcome_nullish_boolean = type { i8*,"
                + " %struct.deal_nullish_boolean }");
    }
}
