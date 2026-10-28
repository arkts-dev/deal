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
 * occupy no representation at all (an empty answer, projected through
 * {@code deal_outcome} and reconstructed by the runtime per C4), and a
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
        "deal_json_read_field");

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

    /** The representation of a statically resolvable function identity. */
    public static final String CODE_POINTER = "i8*";

    /**
     * The staged representation of one descriptor, or empty when the value
     * carries no representation at all (the {@code null}/{@code undefined}
     * singletons) or its representation is managed (C3) and therefore outside
     * the staged subset.
     */
    public static Optional<String> representationOf(RuntimeDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor must not be null");
        return switch (descriptor) {
            case RuntimeDescriptor.Number ignored -> Optional.of(NUMBER);
            case RuntimeDescriptor.Boolean ignored -> Optional.of(BOOLEAN_SSA);
            case RuntimeDescriptor.Func ignored -> Optional.of(CODE_POINTER);
            default -> Optional.empty();
        };
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
            default -> throw new IllegalArgumentException(
                "no staged outcome shape for " + unsupported(returnDescriptor));
        };
    }

    /**
     * The ABI parameter type of one function parameter (C3): {@code boolean}
     * crosses as the 0/1 byte, {@code number} as binary64.
     */
    public static String abiParameterType(RuntimeDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor must not be null");
        return switch (descriptor) {
            case RuntimeDescriptor.Number ignored -> NUMBER;
            case RuntimeDescriptor.Boolean ignored -> BOOLEAN_ABI;
            default -> throw new IllegalArgumentException(
                "no staged ABI parameter type for " + unsupported(descriptor));
        };
    }
}
