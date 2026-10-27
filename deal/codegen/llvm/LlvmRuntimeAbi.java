package deal.codegen.llvm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The single Java mirror of the published C11 runtime declaration surface
 * ({@code deal/native/deal_rt.h}, {@code entry.h} and every service-group
 * header) and of the generated project surface the emitted module defines
 * ({@code llvm-native-codegen-cfg-ssa} C2, {@code llvm-native-runtime-value-model}
 * R9, {@code llvm-native-host-abi-and-async} H2/H6).
 *
 * <p>The mirror carries every published entry once, in the published
 * declaration order, with its C spelling exactly as the header declares it.
 * {@link #declarations()} is the closed set; {@link #declaration(String)}
 * answers one entry; {@link Declaration#llvmDeclaration()} renders the
 * textual LLVM declaration the emitted module may reference, with the C
 * ABI type of every parameter and of the return spelled through
 * {@link LlvmTypeMapper#llvmTypeOfCType(String)} (the one C-to-IR type
 * mapping).</p>
 *
 * <p>The four generated record layouts the module itself owns
 * ({@code deal_module}, {@code deal_export_entry} and the two export-surface
 * record families) stay inside the emitted module (C2/C3/H6 opacity): no
 * published header declares their members, so this class carries them as
 * named IR type spellings only, beside the runtime-side records
 * ({@code deal_arm_record}, {@code deal_arm_table}, {@code deal_origin},
 * {@code deal_outcome}) whose field order this mirror also pins for the
 * emitted static data.</p>
 *
 * <p>A drift between this mirror and the published headers is a defect the
 * parity test ({@code test/LlvmRuntimeAbiMirrorTest.java}) reports against
 * the real header bytes, name and C type for name.</p>
 */
public final class LlvmRuntimeAbi {

    private LlvmRuntimeAbi() {
        // Static mirror only; no instances.
    }

    // =========================================================================
    // The published declaration set
    // =========================================================================

    private static final String DECLARATION_TEXT = """
deal_status deal_null(deal_value *out)
deal_status deal_undefined(deal_value *out)
deal_status deal_nothing(deal_value *out)
deal_status deal_absent(deal_value *out)
deal_status deal_boolean(int truth, deal_value *out)
deal_status deal_number(double value, deal_value *out)
deal_status deal_string_new(const void *bytes, size_t length, deal_value *out)
deal_status deal_array_new(int64_t length, deal_value *out)
deal_status deal_array_new_typed(int64_t length, const char *elementDescriptor, deal_value *out)
deal_status deal_table_new(deal_value *out)
deal_status deal_class_atom(const char *descriptorText, deal_value *out)
deal_status deal_class_new(deal_value atom, deal_value *out)
deal_status deal_function_new(deal_host_function fn, const char *signatureDescriptor, deal_value *out)
deal_status deal_async_operation(deal_async_token token, deal_value *out)
deal_status deal_kind_of(deal_value value, deal_value_kind *out)
deal_status deal_as_boolean(deal_value value, int *out)
deal_status deal_as_number(deal_value value, double *out)
deal_status deal_as_string(deal_value value, const void **bytes, size_t *length)
deal_status deal_same(deal_value a, deal_value b, int *same)
deal_status deal_array_length(deal_value array, int64_t *out)
deal_status deal_array_get(deal_value array, int64_t index, deal_value *out)
deal_status deal_array_set(deal_value array, int64_t index, deal_value element)
deal_status deal_table_get(deal_value table, const void *key, size_t keyLength, deal_value *out)
deal_status deal_table_set(deal_value table, const void *key, size_t keyLength, deal_value value)
deal_status deal_table_has(deal_value table, const void *key, size_t keyLength, int *present)
deal_status deal_table_delete(deal_value table, const void *key, size_t keyLength)
deal_status deal_class_identity(deal_value value, const char **descriptorText)
deal_status deal_field_get(deal_value object, const char *field, deal_value *out)
deal_status deal_field_set(deal_value object, const char *field, deal_value value)
deal_status deal_field_has(deal_value object, const char *field, int *present)
deal_status deal_field_delete(deal_value object, const char *field)
deal_status deal_function_signature(deal_value value, const char **descriptorText)
deal_status deal_apply(deal_value function, const deal_value *args, size_t argc, deal_host_result *out)
deal_status deal_retain(deal_value value, deal_handle *out)
deal_status deal_handle_value(deal_handle handle, const char *descriptorText, deal_value *out)
deal_status deal_release(deal_handle handle)
deal_status deal_failure_discard(deal_error_token token)
deal_status deal_error_from_handle(deal_handle handle, deal_host_failure *out)
deal_status deal_async_complete_value(deal_async_token token, deal_value value)
deal_status deal_async_complete_error(deal_async_token token, const deal_host_failure *failure)
deal_status deal_async_post(deal_async_token token, const deal_host_completion *completion)
deal_completion_frame * deal_platform_frame_alloc(void)
void deal_platform_frame_free(deal_completion_frame *frame)
void deal_platform_post(deal_completion_frame *frame)
deal_completion_frame * deal_platform_take(void)
void deal_platform_wait_ns(int64_t interval_ns)
unsigned char * deal_platform_frame_payload(deal_completion_frame *frame, size_t size)
unsigned char * deal_platform_frame_append(deal_completion_frame *frame, size_t additional)
size_t deal_platform_frame_payload_size(const deal_completion_frame *frame)
const unsigned char * deal_platform_frame_bytes(const deal_completion_frame *frame)
int64_t deal_platform_monotonic_ns(void)
int64_t deal_platform_realtime_ns(void)
void deal_platform_write_stdout(const void *bytes, size_t length)
void deal_platform_write_stderr(const void *bytes, size_t length)
void deal_platform_flush_stdout(void)
void deal_platform_flush_stderr(void)
const char * deal_platform_environment(const char *name)
void deal_platform_exit(int status)
void * deal_platform_page_alloc(size_t bytes)
void deal_platform_page_free(void *base, size_t bytes)
void * deal_platform_allocate(size_t bytes)
void deal_platform_release(void *memory)
char * deal_platform_duplicate(const char *text)
void deal_platform_fault(const char *reason)
int deal_value_is_null(const void *reference)
int deal_value_is_undefined(const void *reference)
void deal_root_frame_enter(uint32_t slot_count)
void deal_root_frame_write(uint32_t slot, void *value)
void deal_root_frame_leave(void)
uint32_t deal_root_frame_depth(void)
void * deal_alloc(uint32_t layout, size_t size)
void deal_store_barrier(void *container, void *value)
void * deal_cell_alloc(void *value)
void deal_cell_store(void *cell, void *value)
void deal_safepoint(void)
void deal_gc_collect_full(void)
void deal_gc_statistics(deal_gc_statistics_record *out)
deal_string * deal_text_new(const void *bytes, size_t length)
int deal_text_compare(deal_string *a, deal_string *b)
const unsigned char * deal_text_bytes(const deal_string *string)
size_t deal_text_byte_length(const deal_string *string)
size_t deal_text_scalar_length(const deal_string *string)
uint64_t deal_text_hash(const deal_string *string)
deal_array * deal_container_array_new(uint32_t element_layout, size_t length)
size_t deal_container_array_count(const deal_array *array)
void * deal_container_array_element(const deal_array *array, size_t index)
void deal_container_array_store(deal_array *array, size_t index, void *element)
void deal_container_array_append(deal_array *array, void *element)
deal_table * deal_container_table_new(void)
size_t deal_container_table_count(const deal_table *table)
deal_value deal_container_table_read(const deal_table *table, deal_string *key)
void deal_container_table_write(deal_table *table, deal_string *key, deal_value value)
int deal_container_table_has(const deal_table *table, deal_string *key)
void deal_container_table_delete(deal_table *table, deal_string *key)
deal_descriptor * deal_descriptor_intern(const char *descriptor_text)
deal_outcome_reference deal_check_value(deal_descriptor *expected, deal_check_input input, uint32_t boundary_kind, const deal_origin *origin)
int deal_arm_table_install(const deal_arm_table *table)
const deal_arm_table * deal_arm_table_installed(void)
void deal_call_frame_push(const char *label)
void deal_call_frame_pop(void)
uint32_t deal_call_frame_depth(void)
deal_error * deal_error_create(const char *code, const char *message, const deal_origin *origin)
void deal_error_set_expected(deal_error *error, const char *expected)
void deal_error_set_actual(deal_error *error, const char *actual)
void deal_error_set_cause(deal_error *error, deal_error *cause)
char * deal_error_snapshot(const deal_error *error)
void deal_error_snapshot_release(char *text)
deal_outcome_value deal_json_parse(deal_string *text, const deal_origin *origin)
deal_outcome_string deal_json_stringify(deal_value value, const deal_origin *origin)
deal_outcome_value deal_std_call(uint32_t operation_id, const deal_value *arguments, uint32_t argument_count, const deal_origin *origin)
double deal_std_now_millis(void)
deal_outcome deal_host_load(const deal_host_declared_module *declared, const deal_origin *origin, const deal_host_binding **out)
deal_outcome deal_project_start(void)
deal_outcome deal_project_run_main(void)
deal_module * deal_project_exports(const char *module_path)
deal_export_entry * deal_export_lookup(deal_module *module, const char *name)
deal_outcome deal_export_invoke(deal_export_entry *entry)
deal_outcome deal_async_export_invoke(deal_export_entry *entry)
int deal_project_main(void)
int deal_error_code(deal_error *error, const char **out)
int deal_error_message(deal_error *error, const char **out)
int deal_error_source_file(deal_error *error, const char **out)
int deal_error_line(deal_error *error, int32_t *out)
int deal_error_column(deal_error *error, int32_t *out)
int deal_error_expected(deal_error *error, const char **out)
int deal_error_actual(deal_error *error, const char **out)
int deal_error_frame_count(deal_error *error, int32_t *out)
int deal_error_frame(deal_error *error, int32_t index, const char **out)
int deal_error_cause_code(deal_error *error, const char **out)
""";

    /** One published parameter: its C type text and its declared name. */
    public record Parameter(String cType, String name) {
        public Parameter {
            Objects.requireNonNull(cType, "cType must not be null");
            Objects.requireNonNull(name, "name must not be null");
        }

        /** The canonical C spelling of this parameter. */
        public String cSpelling() {
            return cType + (cType.endsWith("*") ? "" : " ") + name;
        }
    }

    /** One published declaration: return type, name and parameters. */
    public record Declaration(String returnType, String name,
                              List<Parameter> parameters) {
        public Declaration {
            Objects.requireNonNull(returnType, "returnType must not be null");
            Objects.requireNonNull(name, "name must not be null");
            parameters = List.copyOf(parameters);
        }

        /** The canonical C spelling {@code <ret> <name>(<params>)}. */
        public String cSpelling() {
            StringBuilder text = new StringBuilder();
            text.append(returnType).append(' ').append(name).append('(');
            for (int i = 0; i < parameters.size(); i++) {
                if (i > 0) {
                    text.append(", ");
                }
                text.append(parameters.get(i).cSpelling());
            }
            return text.append(')').toString();
        }

        /** The IR return type of this entry. */
        public String llvmReturnType() {
            return LlvmTypeMapper.llvmTypeOfCType(returnType);
        }

        /** The IR parameter types, in declaration order. */
        public List<String> llvmParameterTypes() {
            List<String> types = new ArrayList<>(parameters.size());
            for (Parameter parameter : parameters) {
                types.add(LlvmTypeMapper.llvmTypeOfCType(parameter.cType()));
            }
            return List.copyOf(types);
        }

        /**
         * The textual LLVM declaration of this entry:
         * {@code declare <ret> @<name>(<types>)}.
         */
        public String llvmDeclaration() {
            StringBuilder text = new StringBuilder("declare ");
            text.append(llvmReturnType()).append(" @").append(name).append('(');
            List<String> types = llvmParameterTypes();
            for (int i = 0; i < types.size(); i++) {
                if (i > 0) {
                    text.append(", ");
                }
                text.append(types.get(i));
            }
            return text.append(')').toString();
        }
    }

    private static final List<Declaration> DECLARATIONS = parse(DECLARATION_TEXT);

    private static final Map<String, Declaration> BY_NAME = index(DECLARATIONS);

    private static List<Declaration> parse(String text) {
        List<Declaration> parsed = new ArrayList<>();
        for (String line : text.strip().split("\n")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            parsed.add(parseOne(trimmed));
        }
        return List.copyOf(parsed);
    }

    private static Declaration parseOne(String text) {
        int open = text.indexOf('(');
        int close = text.lastIndexOf(')');
        if (open < 0 || close < open) {
            throw new IllegalStateException(
                "malformed published declaration: " + text);
        }
        String head = text.substring(0, open).strip();
        int space = head.lastIndexOf(' ');
        if (space < 0) {
            throw new IllegalStateException(
                "malformed published declaration head: " + text);
        }
        String returnType = head.substring(0, space).strip();
        String name = head.substring(space + 1).strip();
        String parameterText = text.substring(open + 1, close).strip();
        List<Parameter> parameters = new ArrayList<>();
        if (!parameterText.isEmpty() && !parameterText.equals("void")) {
            for (String part : parameterText.split(",")) {
                String parameter = part.strip();
                int lastSpace = parameter.lastIndexOf(' ');
                if (lastSpace < 0) {
                    throw new IllegalStateException(
                        "malformed published parameter: " + part);
                }
                String type = parameter.substring(0, lastSpace).strip();
                String declarator = parameter.substring(lastSpace + 1).strip();
                int stars = 0;
                while (stars < declarator.length()
                        && declarator.charAt(stars) == '*') {
                    stars++;
                }
                if (stars > 0) {
                    type = type + " " + "*".repeat(stars);
                    declarator = declarator.substring(stars);
                }
                if (declarator.isEmpty()) {
                    throw new IllegalStateException(
                        "malformed published parameter: " + part);
                }
                parameters.add(new Parameter(type, declarator));
            }
        }
        return new Declaration(returnType, name, parameters);
    }

    private static Map<String, Declaration> index(List<Declaration> list) {
        Map<String, Declaration> indexed = new LinkedHashMap<>();
        for (Declaration declaration : list) {
            Declaration previous = indexed.put(declaration.name(), declaration);
            if (previous != null && !previous.equals(declaration)) {
                throw new IllegalStateException(
                    "two published declarations name " + declaration.name());
            }
        }
        return Map.copyOf(indexed);
    }

    /** The complete published declaration set, in published order. */
    public static List<Declaration> declarations() {
        return DECLARATIONS;
    }

    /** One published declaration by name, or empty when unpublished. */
    public static Optional<Declaration> declaration(String name) {
        Objects.requireNonNull(name, "name must not be null");
        return Optional.ofNullable(BY_NAME.get(name));
    }

    /**
     * The textual LLVM declaration of one published entry the emitted
     * module references; an unpublished name is a producer defect.
     */
    public static String llvmDeclarationOf(String name) {
        return declaration(name).orElseThrow(() -> new IllegalArgumentException(
            "the runtime ABI publishes no entry named " + name))
            .llvmDeclaration();
    }

    // =========================================================================
    // The generated project surface (C2, H6, R9)
    // =========================================================================

    /**
     * The six export-surface functions the emitted module defines, in
     * {@code entry.h} declaration order (C2).
     */
    public static final List<String> EXPORT_SURFACE = List.of(
        "deal_project_start",
        "deal_project_run_main",
        "deal_project_exports",
        "deal_export_lookup",
        "deal_export_invoke",
        "deal_async_export_invoke");

    /**
     * The runtime-owned entry-surface symbols the emitted module never
     * defines: the process entry, the terminal projection and the error
     * accessors ({@code entry.h}, R9). Each has exactly one owner in a
     * runtime translation unit.
     */
    public static final List<String> RUNTIME_OWNED = List.of(
        "deal_project_main",
        "deal_error_code",
        "deal_error_message",
        "deal_error_source_file",
        "deal_error_line",
        "deal_error_column",
        "deal_error_expected",
        "deal_error_actual",
        "deal_error_frame_count",
        "deal_error_frame",
        "deal_error_cause_code");

    // =========================================================================
    // The published record families the emitted static data follows
    // =========================================================================

    /**
     * The IR layout of {@code deal_arm_record} ({@code error.h}): id, code,
     * template text, parameter-name array, parameter count, render scope,
     * expected flag, actual flag — in the published field order.
     */
    public static final String ARM_RECORD_LAYOUT =
        "{ i32, i8*, i8*, i8**, i32, i8, i8, i8 }";

    /** The IR layout of {@code deal_arm_table} ({@code error.h}). */
    public static final String ARM_TABLE_LAYOUT = "{ i32, i32, ARM_RECORD* }";

    /** The IR layout of {@code deal_origin} ({@code deal_rt.h}). */
    public static final String ORIGIN_LAYOUT = "{ i8*, i32, i32 }";

    /** The error-first outcome family ({@code deal_rt.h} C4). */
    public static final String OUTCOME_LAYOUT = "{ deal_error* }";

    /** {@code deal_outcome_number}: error first, binary64 value second. */
    public static final String OUTCOME_NUMBER_LAYOUT = "{ deal_error*, double }";

    /** {@code deal_outcome_boolean}: error first, 0/1 byte second. */
    public static final String OUTCOME_BOOLEAN_LAYOUT = "{ deal_error*, i8 }";

    /** The supported arm-table version ({@code error.h}). */
    public static final int ARM_TABLE_VERSION = 1;

    /** The published render scope of a top-level arm. */
    public static final int ARM_SCOPE_TOP_LEVEL = 0;

    /** The published render scope of an inner-only arm. */
    public static final int ARM_SCOPE_INNER_ONLY = 1;
}
