/*
 * test/native/json_stdlib_driver.c — the ISSUE-0908 acceptance driver of the
 * published `json` and `std` service groups.
 *
 * It links the published runtime tree and prints one canonical tab-separated
 * observation per case; the Java suite (`deal.test.NativeJsonStdlibTest`)
 * derives every expectation from the shared authorities — the merged v1.5
 * `SharedStdlibSemantics` (JSON parse/encode and the string, table, math and
 * time operations) and `FailureContractRegistry` (arm text) — and never from
 * the observation itself. The class walks' expectations are authored from
 * docs/spec-v1.5.md's `@jsonable` rules.
 *
 * The driver is also the emitted module for the runtime: it defines the six
 * export-surface functions and the closed arm table, installs the table from
 * `deal_project_start`, and stands in for the emitted per-class JSON plans with
 * two test plans over a record-shaped instance (a driver-side stand-in for the
 * emitted per-layout struct; no published runtime symbol is replaced).
 *
 * `DEAL_JSON_CASE=<n>` in the environment selects one deterministic producer or
 * invariant defect (n >= 1) instead of the acceptance run; it must terminate
 * with one stderr line and exit 2. The selector is a run-time environment value
 * so one build serves the acceptance run and every defect.
 *
 * `DEAL_JSON_DEEP=<depth>` bounds the process address space and round-trips one
 * deeply nested finite document instead. The cap makes the encoder's scratch
 * growth observable: a linear encoder re-encodes the fixture inside it while a
 * per-frame concatenated walk position (quadratic in depth) cannot allocate.
 *
 * `DEAL_JSON_LOCALE=1` selects the process's ambient locale (`setlocale` from
 * `LC_ALL`) and then runs the whole acceptance matrix again: the numeric decode
 * and encode entries must be independent of it, so the observations must be
 * byte-identical to the C-locale run. The mode refuses to run under a locale
 * that keeps the '.' decimal point, because that locale cannot observe the
 * comma-decimal defect the mode exists to rule out.
 */
#define _POSIX_C_SOURCE 200809L
#include <locale.h>
#include <stdio.h>
#include <string.h>
#include <sys/resource.h>

#include "deal/native/container.h"
#include "deal/native/descriptor.h"
#include "deal/native/entry.h"
#include "deal/native/error.h"
#include "deal/native/json.h"
#include "deal/native/memory.h"
#include "deal/native/platform.h"
#include "deal/native/std.h"
#include "deal/native/text.h"
#include "deal/native/value.h"

/* =========================================================================
 * The emitted module surface: the closed arm table and the six exports
 * ========================================================================= */

static const char *const arm_parameters_0[] = {"kind"};
static const char *const arm_parameters_2[] = {"expected", "actual"};
static const char *const arm_parameters_5[] = {"oneBasedIndex"};
static const char *const arm_parameters_10[] = {"index", "inner"};
static const char *const arm_parameters_11[] = {"expected"};
static const char *const arm_parameters_12[] = {"inner"};
static const char *const arm_parameters_13[] = {"expected"};
static const char *const arm_parameters_14[] = {"actual"};
static const char *const arm_parameters_15[] = {"module", "reason"};
static const char *const arm_parameters_17[] = {"module"};
static const char *const arm_parameters_18[] = {"name", "module"};
static const char *const arm_parameters_19[] =
    {"name", "module", "expected", "actual"};
static const char *const arm_parameters_21[] =
    {"name", "module", "defaults|fields"};
static const char *const arm_parameters_22[] = {"field", "classId"};
static const char *const arm_parameters_23[] =
    {"oneBasedByteOffset", "reason"};
static const char *const arm_parameters_24[] = {"fieldPath", "actual"};
static const char *const arm_parameters_25[] = {"actual"};

static const deal_arm_record arm_records[] = {
    {0U, "E8001", "expected {kind}", arm_parameters_0, 1U,
     DEAL_ARM_SCOPE_TOP_LEVEL, 1U, 1U},
    {1U, "E8001", "expected string, got invalid Unicode scalar encoding", NULL,
     0U, DEAL_ARM_SCOPE_TOP_LEVEL, 1U, 1U},
    {2U, "E8001", "expected instance of {expected}, got {actual}",
     arm_parameters_2, 2U, DEAL_ARM_SCOPE_TOP_LEVEL, 1U, 1U},
    {3U, "E8001", "expected string, got invalid UTF-8 encoding", NULL, 0U,
     DEAL_ARM_SCOPE_INNER_ONLY, 0U, 0U},
    {4U, "E8001", "expected string, got UTF-16 surrogate code point", NULL, 0U,
     DEAL_ARM_SCOPE_INNER_ONLY, 0U, 0U},
    {5U, "E8003", "array element {oneBasedIndex} type mismatch",
     arm_parameters_5, 1U, DEAL_ARM_SCOPE_TOP_LEVEL, 1U, 1U},
    {6U, "E8002", "array index out of bounds", NULL, 0U,
     DEAL_ARM_SCOPE_TOP_LEVEL, 0U, 0U},
    {7U, "E8002", "array index out of bounds", NULL, 0U,
     DEAL_ARM_SCOPE_TOP_LEVEL, 0U, 0U},
    {8U, "E8002", "array index out of bounds", NULL, 0U,
     DEAL_ARM_SCOPE_TOP_LEVEL, 0U, 0U},
    {9U, "E8010", "function signature mismatch: expected {expected}, got {actual}",
     arm_parameters_2, 2U, DEAL_ARM_SCOPE_TOP_LEVEL, 1U, 1U},
    {10U, "E8010", "parameter {index} type mismatch: {inner}",
     arm_parameters_10, 2U, DEAL_ARM_SCOPE_TOP_LEVEL, 1U, 1U},
    {11U, "E8010", "return value 1 type mismatch: expected {expected}, got nothing",
     arm_parameters_11, 1U, DEAL_ARM_SCOPE_TOP_LEVEL, 1U, 1U},
    {12U, "E8010", "return value 1 type mismatch: {inner}",
     arm_parameters_12, 1U, DEAL_ARM_SCOPE_TOP_LEVEL, 1U, 1U},
    {13U, "E8001", "expected {expected}", arm_parameters_13, 1U,
     DEAL_ARM_SCOPE_TOP_LEVEL, 1U, 1U},
    {14U, "E8010", "host async function must return an async operation, got {actual}",
     arm_parameters_14, 1U, DEAL_ARM_SCOPE_TOP_LEVEL, 1U, 1U},
    {15U, "E8011", "failed to load host module '{module}': {reason}",
     arm_parameters_15, 2U, DEAL_ARM_SCOPE_TOP_LEVEL, 0U, 0U},
    {16U, "E8011", "host module did not return a module object", NULL, 0U,
     DEAL_ARM_SCOPE_TOP_LEVEL, 1U, 1U},
    {17U, "E8011", "host module '{module}' did not return a module object",
     arm_parameters_17, 1U, DEAL_ARM_SCOPE_TOP_LEVEL, 0U, 0U},
    {18U, "E8011", "missing host export '{name}' in module '{module}'",
     arm_parameters_18, 2U, DEAL_ARM_SCOPE_TOP_LEVEL, 0U, 0U},
    {19U, "E8011", "host export '{name}' in module '{module}' has signature"
     " mismatch: expected {expected}, got {actual}", arm_parameters_19, 4U,
     DEAL_ARM_SCOPE_TOP_LEVEL, 0U, 0U},
    {20U, "E8011", "host class '{name}' in module '{module}' has identity"
     " mismatch: expected {expected}, got {actual}", arm_parameters_19, 4U,
     DEAL_ARM_SCOPE_TOP_LEVEL, 0U, 0U},
    {21U, "E8011", "host class '{name}' in module '{module}' has invalid"
     " {defaults|fields} metadata", arm_parameters_21, 3U,
     DEAL_ARM_SCOPE_TOP_LEVEL, 0U, 0U},
    {22U, "E8007", "extra field '{field}' in class '{classId}'",
     arm_parameters_22, 2U, DEAL_ARM_SCOPE_TOP_LEVEL, 0U, 0U},
    {23U, "E8001", "JSON parse error at position {oneBasedByteOffset}: {reason}",
     arm_parameters_23, 2U, DEAL_ARM_SCOPE_TOP_LEVEL, 0U, 0U},
    {24U, "E8001", "value at {fieldPath} is not JSON serializable: {actual}",
     arm_parameters_24, 2U, DEAL_ARM_SCOPE_TOP_LEVEL, 0U, 1U},
    {25U, "E8001", "unsupported type for JSON encoding: {actual}",
     arm_parameters_25, 1U, DEAL_ARM_SCOPE_TOP_LEVEL, 1U, 1U},
    {26U, "E8001", "cyclic value cannot be encoded as JSON", NULL, 0U,
     DEAL_ARM_SCOPE_TOP_LEVEL, 0U, 0U},
    {27U, "E8001", "substring bounds must be finite integers", NULL, 0U,
     DEAL_ARM_SCOPE_TOP_LEVEL, 0U, 0U},
    {28U, "E8001", "sqrt of negative number", NULL, 0U,
     DEAL_ARM_SCOPE_TOP_LEVEL, 0U, 1U}
};

const deal_arm_table deal_project_arm_table = {DEAL_ARM_TABLE_VERSION,
    DEAL_ARM_REPERTOIRE_COUNT, arm_records};

deal_outcome deal_project_start(void) {
    deal_outcome outcome;
    outcome.error = NULL;
    (void)deal_arm_table_install(&deal_project_arm_table);
    return outcome;
}

deal_outcome deal_project_run_main(void) {
    deal_outcome outcome;
    outcome.error = NULL;
    return outcome;
}

deal_module *deal_project_exports(const char *module_path) {
    (void)module_path;
    return NULL;
}

deal_export_entry *deal_export_lookup(deal_module *module, const char *name) {
    (void)module;
    (void)name;
    return NULL;
}

deal_outcome deal_export_invoke(deal_export_entry *entry) {
    (void)entry;
    deal_outcome outcome;
    outcome.error = NULL;
    return outcome;
}

deal_outcome deal_async_export_invoke(deal_export_entry *entry) {
    return deal_export_invoke(entry);
}

/* =========================================================================
 * Observation helpers
 * ========================================================================= */

static int failures;

static void check(int condition, const char *what) {
    if (!condition) {
        printf("FAIL: %s\n", what);
        failures += 1;
    }
}

/* The canonical number observation: `NaN` for every NaN (its payload and sign
 * are not language-visible) and the exact 64-bit pattern otherwise, so `-0`
 * stays distinguishable from `0`. */
static void print_number(double value) {
    union { double number; uint64_t bits; } representation;
    representation.number = value;
    if (value != value) {
        printf("NaN");
        return;
    }
    printf("%016llx", (unsigned long long)representation.bits);
}

static const char *text_or_absent(const char *text) {
    return text == NULL ? "-" : text;
}

/* One failure's closed projection: code, message, expected, actual, origin and
 * frame count, tab separated. */
static void print_failure(deal_error *error) {
    const char *code = NULL;
    const char *message = NULL;
    const char *expected = NULL;
    const char *actual = NULL;
    const char *file = NULL;
    int32_t line = 0;
    int32_t column = 0;
    int32_t frames = 0;
    check(deal_error_code(error, &code) == 1, "the Error carries its code");
    check(deal_error_message(error, &message) == 1, "the Error carries its message");
    (void)deal_error_expected(error, &expected);
    (void)deal_error_actual(error, &actual);
    (void)deal_error_source_file(error, &file);
    (void)deal_error_line(error, &line);
    (void)deal_error_column(error, &column);
    (void)deal_error_frame_count(error, &frames);
    printf("%s\t%s\t%s\t%s\t%s\t%d\t%d\t%d", text_or_absent(code),
           text_or_absent(message), text_or_absent(expected),
           text_or_absent(actual), text_or_absent(file), (int)line,
           (int)column, (int)frames);
}

/* One string value held in the active root scope and its persistent datum. */
static deal_data text_datum(const char *text) {
    deal_string *value = deal_text_new(text, strlen(text));
    (void)deal_root_scope_hold(value);
    return deal_data_of_reference(DEAL_DATA_STRING, value);
}

/* One managed string's exact bytes. */
static void write_text(const deal_string *text) {
    printf("%.*s", (int)deal_text_byte_length(text), deal_text_bytes(text));
}

/* The emitted call origin every observation is rendered at. */
static const deal_origin call_origin = {"native/json-stdlib.deal", 12, 5};

/* =========================================================================
 * The parsed-value and encoder observations
 * ========================================================================= */

static void observe_roundtrip(const char *label, const char *json) {
    deal_string *text = deal_text_new(json, strlen(json));
    deal_root_scope_push();
    (void)deal_root_scope_hold(text);
    deal_outcome_data parsed = deal_json_parse(text, &call_origin);
    if (parsed.error != NULL) {
        printf("RT\t%s\tERROR\t", label);
        print_failure(parsed.error);
        printf("\n");
        deal_root_scope_pop();
        return;
    }
    (void)deal_root_scope_hold_data(&parsed.value);
    deal_outcome_data encoded = deal_json_stringify(parsed.value, &call_origin);
    if (encoded.error != NULL) {
        printf("RT\t%s\tERROR\t", label);
        print_failure(encoded.error);
        printf("\n");
    } else if (encoded.value.tag == DEAL_DATA_UNDEFINED) {
        printf("RT\t%s\tUNDEFINED\n", label);
    } else {
        printf("RT\t%s\tTEXT\t", label);
        write_text((const deal_string *)encoded.value.reference);
        printf("\n");
    }
    deal_root_scope_pop();
}

static void observe_number_token(const char *token) {
    deal_string *text = deal_text_new(token, strlen(token));
    deal_root_scope_push();
    (void)deal_root_scope_hold(text);
    deal_outcome_data parsed = deal_json_parse(text, &call_origin);
    if (parsed.error != NULL || parsed.value.tag != DEAL_DATA_NUMBER) {
        printf("NUM\t%s\tERROR\n", token);
    } else {
        printf("NUM\t%s\t", token);
        print_number(parsed.value.number);
        printf("\n");
    }
    deal_root_scope_pop();
}

/* One deeply nested finite document: `depth` nested arrays round-trip exactly,
 * which is the stack-safe traversal's own observation (no nesting cap). */
static void observe_depth(const char *label, size_t depth) {
    size_t text_length = depth * 2u;
    unsigned char *json = deal_platform_allocate(text_length + 1u);
    if (json == NULL) {
        deal_platform_fault("json: depth fixture allocation failed");
    }
    for (size_t index = 0; index < depth; index += 1u) {
        json[index] = '[';
        json[text_length - 1u - index] = ']';
    }
    json[text_length] = 0;
    deal_string *text = deal_text_new(json, text_length);
    deal_platform_release(json);
    deal_root_scope_push();
    (void)deal_root_scope_hold(text);
    deal_outcome_data parsed = deal_json_parse(text, &call_origin);
    printf("DEEP\t%s\t%u\t", label, (unsigned)depth);
    if (parsed.error != NULL) {
        print_failure(parsed.error);
        printf("\n");
        deal_root_scope_pop();
        return;
    }
    (void)deal_root_scope_hold_data(&parsed.value);
    deal_outcome_data encoded = deal_json_stringify(parsed.value, &call_origin);
    if (encoded.error != NULL) {
        printf("ERROR\t");
        print_failure(encoded.error);
        printf("\n");
    } else {
        const deal_string *output = (const deal_string *)encoded.value.reference;
        size_t length = deal_text_byte_length(output);
        int identical = length == text_length
            && memcmp(deal_text_bytes(output), deal_text_bytes(text), length) == 0;
        printf("%u\t%s\n", (unsigned)length, identical ? "match" : "mismatch");
    }
    deal_root_scope_pop();
}

/* The linear-scratch observation: with the process address space bounded, one
 * deeply nested finite document decodes and re-encodes exactly. The corrected
 * encoder keeps one parent link and one segment per open frame, so its scratch
 * stays linear in depth and fits; a frame storing its concatenated walk
 * position would need a quadratic amount and cannot allocate inside the cap. */
static void run_deep_scratch(size_t depth) {
    struct rlimit limit;
    limit.rlim_cur = 512u * 1024u * 1024u;
    limit.rlim_max = 512u * 1024u * 1024u;
    if (setrlimit(RLIMIT_AS, &limit) != 0) {
        deal_platform_fault("json: address-space bound failed");
    }
    observe_depth("linear-scratch", depth);
    deal_platform_flush_stdout();
}

static void observe_parse_failure(const char *label, const char *json) {
    deal_string *text = deal_text_new(json, strlen(json));
    deal_root_scope_push();
    (void)deal_root_scope_hold(text);
    deal_outcome_data parsed = deal_json_parse(text, &call_origin);
    printf("PARSE\t%s\t", label);
    if (parsed.error == NULL) {
        printf("NO-ERROR\n");
    } else {
        printf("ERROR\t");
        print_failure(parsed.error);
        printf("\n");
    }
    deal_root_scope_pop();
}

static void observe_encode(const char *label, deal_data value) {
    deal_root_scope_push();
    (void)deal_root_scope_hold_data(&value);
    deal_outcome_data encoded = deal_json_stringify(value, &call_origin);
    printf("ENC\t%s\t", label);
    if (encoded.error != NULL) {
        printf("ERROR\t");
        print_failure(encoded.error);
        printf("\n");
    } else if (encoded.value.tag == DEAL_DATA_UNDEFINED) {
        printf("UNDEFINED\n");
    } else {
        printf("TEXT\t");
        write_text((const deal_string *)encoded.value.reference);
        printf("\n");
    }
    deal_root_scope_pop();
}

/* An empty record datum held in the caller's scope. */
static deal_data empty_record(void) {
    deal_table *table = deal_container_table_new();
    (void)deal_root_scope_hold(table);
    return deal_data_of_reference(DEAL_DATA_TABLE, table);
}

/* One record with one member, held in the caller's scope. */
static deal_table *record_with(const char *key, deal_data value) {
    deal_table *table = deal_container_table_new();
    (void)deal_root_scope_hold(table);
    deal_string *name = deal_text_new(key, strlen(key));
    (void)deal_root_scope_hold(name);
    deal_container_table_write(table, name, &value);
    return table;
}

/* One record with one string member. */
static deal_table *record_with_text(const char *key, const char *text) {
    deal_string *value = deal_text_new(text, strlen(text));
    (void)deal_root_scope_hold(value);
    return record_with(key, deal_data_of_reference(DEAL_DATA_STRING, value));
}

static void observe_encode_rules(void) {
    /* A record with an undefined-valued and a function-valued member: both are
     * omitted while the remaining keys keep their creation order, and an
     * array-index key precedes them (v1.5 own-key order). */
    deal_root_scope_push();
    deal_table *table = deal_container_table_new();
    (void)deal_root_scope_hold(table);
    deal_string *kept = deal_text_new("kept", 4u);
    (void)deal_root_scope_hold(kept);
    deal_data kept_value = deal_data_of_number(1.0);
    deal_container_table_write(table, kept, &kept_value);
    deal_string *dropped = deal_text_new("dropped", 7u);
    (void)deal_root_scope_hold(dropped);
    deal_data undefined_value = deal_data_of_undefined();
    deal_container_table_write(table, dropped, &undefined_value);
    deal_string *hidden = deal_text_new("hidden", 6u);
    (void)deal_root_scope_hold(hidden);
    deal_data function_value = deal_data_of_reference(DEAL_DATA_FUNCTION, table);
    deal_container_table_write(table, hidden, &function_value);
    deal_string *index_key = deal_text_new("0", 1u);
    (void)deal_root_scope_hold(index_key);
    deal_data zero_value = deal_data_of_number(0.0);
    deal_container_table_write(table, index_key, &zero_value);
    observe_encode("record-omission", deal_data_of_reference(DEAL_DATA_TABLE, table));
    deal_root_scope_pop();

    /* The array substitution rule: an undefined or function element encodes as
     * null, a present null stays null. */
    deal_root_scope_push();
    deal_array *array = deal_container_array_new_typed(
        DEAL_LAYOUT_ELEMENT_DATUM,
        deal_descriptor_reference(deal_descriptor_intern("unknown")), 0u);
    (void)deal_root_scope_hold(array);
    deal_data first = deal_data_of_number(1.0);
    deal_container_array_append(array, &first);
    deal_data second = deal_data_of_undefined();
    deal_container_array_append(array, &second);
    deal_data third = deal_data_of_reference(DEAL_DATA_FUNCTION, array);
    deal_container_array_append(array, &third);
    deal_data fourth = deal_data_of_null();
    deal_container_array_append(array, &fourth);
    observe_encode("array-substitution", deal_data_of_reference(DEAL_DATA_ARRAY, array));
    deal_root_scope_pop();

    /* A path-local cycle is the closed cycle arm; a shared acyclic child is
     * encoded twice instead. */
    deal_root_scope_push();
    deal_table *cyclic = deal_container_table_new();
    (void)deal_root_scope_hold(cyclic);
    deal_string *self = deal_text_new("self", 4u);
    (void)deal_root_scope_hold(self);
    deal_data cyclic_value = deal_data_of_reference(DEAL_DATA_TABLE, cyclic);
    deal_container_table_write(cyclic, self, &cyclic_value);
    observe_encode("record-cycle", cyclic_value);
    deal_root_scope_pop();

    deal_root_scope_push();
    deal_table *shared = record_with("v", deal_data_of_number(2.0));
    deal_array *holder = deal_container_array_new_typed(
        DEAL_LAYOUT_ELEMENT_DATUM,
        deal_descriptor_reference(deal_descriptor_intern("unknown")), 0u);
    (void)deal_root_scope_hold(holder);
    deal_data child = deal_data_of_reference(DEAL_DATA_TABLE, shared);
    deal_container_array_append(holder, &child);
    deal_container_array_append(holder, &child);
    observe_encode("shared-children", deal_data_of_reference(DEAL_DATA_ARRAY, holder));
    deal_root_scope_pop();

    /* A class instance has no generic representation: the closed std/json
     * rejection arm with the value's carrier-kind token. */
    deal_root_scope_push();
    deal_table *stand_in = deal_container_table_new();
    (void)deal_root_scope_hold(stand_in);
    observe_encode("class-unsupported",
                   deal_data_of_reference(DEAL_DATA_CLASS, stand_in));
    deal_root_scope_pop();

    /* The v1.5 root rules: an undefined or function root yields undefined. */
    observe_encode("root-undefined", deal_data_of_undefined());
    deal_root_scope_push();
    deal_table *root_holder = deal_container_table_new();
    (void)deal_root_scope_hold(root_holder);
    observe_encode("root-function",
                   deal_data_of_reference(DEAL_DATA_FUNCTION, root_holder));
    deal_root_scope_pop();
}

/* =========================================================================
 * The stdlib observations
 * ========================================================================= */

static deal_check_input string_argument(deal_string *text) {
    deal_check_input input;
    memset(&input, 0, sizeof(input));
    input.presentation = DEAL_PRESENT_DATUM;
    input.datum = deal_data_of_reference(DEAL_DATA_STRING, text);
    return input;
}

static deal_check_input number_argument(double value) {
    deal_check_input input;
    memset(&input, 0, sizeof(input));
    input.presentation = DEAL_PRESENT_DATUM;
    input.datum = deal_data_of_number(value);
    return input;
}

static deal_check_input datum_argument(deal_data value) {
    deal_check_input input;
    memset(&input, 0, sizeof(input));
    input.presentation = DEAL_PRESENT_DATUM;
    input.datum = value;
    return input;
}

/* One std array result: each element as its own observation line. */
static void print_string_array(const char *label, const deal_array *array) {
    size_t count = deal_container_array_count(array);
    printf("ARRAY\t%u\n", (unsigned)count);
    for (size_t index = 0; index < count; index += 1u) {
        const deal_string **slot =
            (const deal_string **)deal_container_array_element(array, index);
        printf("ARR\t%s\t%u\t", label, (unsigned)index);
        write_text(*slot);
        printf("\n");
    }
}

/* One std call's result observation. */
static void observe_std(const char *label, uint32_t operation_id,
                        const deal_check_input *arguments, uint32_t count) {
    deal_root_scope_push();
    deal_outcome_data outcome = deal_std_call(operation_id, arguments, count,
                                             &call_origin);
    /* A console effect is published before the observation that reports the
     * call's completion, so the two stream buffers never interleave. */
    deal_platform_flush_stdout();
    deal_platform_flush_stderr();
    printf("STD\t%s\t", label);
    if (outcome.error != NULL) {
        printf("ERROR\t");
        print_failure(outcome.error);
        printf("\n");
        deal_root_scope_pop();
        return;
    }
    deal_data value = outcome.value;
    switch ((deal_data_tag)value.tag) {
    case DEAL_DATA_STRING:
        printf("TEXT\t");
        write_text((const deal_string *)value.reference);
        printf("\n");
        break;
    case DEAL_DATA_NUMBER:
        printf("NUMBER\t");
        print_number(value.number);
        printf("\n");
        break;
    case DEAL_DATA_BOOLEAN:
        printf("BOOLEAN\t%s\n", value.number != 0.0 ? "true" : "false");
        break;
    case DEAL_DATA_UNDEFINED:
        printf("UNDEFINED\n");
        break;
    case DEAL_DATA_NULL:
        printf("NULL\n");
        break;
    case DEAL_DATA_ARRAY:
        print_string_array(label, (const deal_array *)value.reference);
        break;
    case DEAL_DATA_TABLE: {
        /* A record result is observed as its own JSON text, exactly the shape
         * the declared `record` return publishes. */
        deal_outcome_data encoded = deal_json_stringify(value, &call_origin);
        if (encoded.error != NULL) {
            printf("TABLE-ERROR\t");
            print_failure(encoded.error);
            printf("\n");
        } else {
            printf("TABLE\t");
            write_text((const deal_string *)encoded.value.reference);
            printf("\n");
        }
        break;
    }
    default:
        printf("OTHER\t%u\n", (unsigned)value.tag);
        break;
    }
    deal_root_scope_pop();
}

/* One std string operation with string arguments. */
static void observe_string_operation(const char *label, uint32_t operation_id,
                                     const char *first, const char *second,
                                     const char *third) {
    deal_root_scope_push();
    deal_string *a = deal_text_new(first, strlen(first));
    (void)deal_root_scope_hold(a);
    deal_string *b = NULL;
    if (second != NULL) {
        b = deal_text_new(second, strlen(second));
        (void)deal_root_scope_hold(b);
    }
    deal_string *c = NULL;
    if (third != NULL) {
        c = deal_text_new(third, strlen(third));
        (void)deal_root_scope_hold(c);
    }
    deal_check_input arguments[3];
    uint32_t count = 0u;
    arguments[count++] = string_argument(a);
    if (b != NULL) {
        arguments[count++] = string_argument(b);
    }
    if (c != NULL) {
        arguments[count++] = string_argument(c);
    }
    observe_std(label, operation_id, arguments, count);
    deal_root_scope_pop();
}

/* One std numeric operation. */
static void observe_number_operation(const char *label, uint32_t operation_id,
                                     double value) {
    deal_check_input arguments[1];
    arguments[0] = number_argument(value);
    observe_std(label, operation_id, arguments, 1u);
}

/* One `substring` call. */
static void observe_substring(const char *label, const char *text,
                              double start, double end) {
    deal_string *value = deal_text_new(text, strlen(text));
    deal_root_scope_push();
    (void)deal_root_scope_hold(value);
    deal_check_input arguments[3];
    arguments[0] = string_argument(value);
    arguments[1] = number_argument(start);
    arguments[2] = number_argument(end);
    observe_std(label, DEAL_STDLIB_STRING_SUBSTRING, arguments, 3u);
    deal_root_scope_pop();
}

static void observe_stdlib(void) {
    /* console: the exact bytes plus one newline on the channel. */
    printf("BEFORE\tconsole-log\n");
    deal_platform_flush_stdout();
    observe_string_operation("console-log", DEAL_STDLIB_CONSOLE_LOG,
                             "hello\tworld \xc3\xa9", NULL, NULL);
    deal_platform_flush_stdout();
    printf("AFTER\tconsole-log\n");
    printf("BEFORE\tconsole-error\n");
    deal_platform_flush_stdout();
    observe_string_operation("console-error", DEAL_STDLIB_CONSOLE_ERROR,
                             "failure line", NULL, NULL);
    deal_platform_flush_stdout();
    printf("AFTER\tconsole-error\n");

    /* std/string length over BMP, astral and empty strings. */
    for (unsigned index = 0; index < 4u; index += 1u) {
        static const char *const cases[4] = {"", "abc", "\xc3\xa9", ("\xf0\x9d\x84\x9e" "ab")};
        static const char *const labels[4] = {"length-empty", "length-ascii",
                                             "length-bmp", "length-astral"};
        observe_string_operation(labels[index], DEAL_STDLIB_STRING_LENGTH,
                                 cases[index], NULL, NULL);
    }

    /* std/string substring: clamps, reversed ranges, scalar slicing and the
     * two bound failures. */
    observe_substring("substring-basic", "hello", 1.0, 3.0);
    observe_substring("substring-negative-start", "hello", -5.0, 3.0);
    observe_substring("substring-negative-end", "hello", 3.0, -1.0);
    observe_substring("substring-reversed", "hello", 4.0, 2.0);
    observe_substring("substring-scalars", "h\xc3\xa9llo", 0.0, 2.0);
    observe_substring("substring-astral", "\xf0\x9d\x84\x9e" "ab", 1.0, 3.0);
    observe_substring("substring-clamp-high", "hello", 2.0, 99.0);
    observe_substring("substring-empty", "", 0.0, 0.0);
    observe_substring("substring-fractional", "hello", 1.5, 3.0);
    observe_substring("substring-nan", "hello", 0.0 / 0.0, 3.0);
    observe_substring("substring-infinite", "hello", 0.0, 1.0 / 0.0);

    /* Integrality holds over the whole finite binary64 domain: every value of
     * magnitude at least 2^52 is integral, so a bound above 2^53 clamps
     * instead of failing (R8, SharedStdlibSemantics.stringSubstring). */
    observe_substring("substring-two-to-53", "hello", 9007199254740992.0, 3.0);
    observe_substring("substring-above-two-to-53", "hello", 9007199254740994.0,
                      3.0);
    observe_substring("substring-huge-start", "hello", 1e18, 3.0);
    observe_substring("substring-huge-end", "hello", 1.0, 1e18);
    observe_substring("substring-huge-both", "hello", 1e300, 2e300);
    observe_substring("substring-negative-huge-start", "hello", -1e300, 3.0);
    observe_substring("substring-negative-huge-end", "\xf0\x9d\x84\x9e" "ab",
                      1.0, -1e300);

    /* std/string contains / startsWith / endsWith. */
    observe_string_operation("contains-hit", DEAL_STDLIB_STRING_CONTAINS,
                             "hello", "ell", NULL);
    observe_string_operation("contains-miss", DEAL_STDLIB_STRING_CONTAINS,
                             "hello", "z", NULL);
    observe_string_operation("contains-empty", DEAL_STDLIB_STRING_CONTAINS,
                             "hello", "", NULL);
    observe_string_operation("contains-astral", DEAL_STDLIB_STRING_CONTAINS,
                             "\xf0\x9d\x84\x9e" "ab", "ab", NULL);
    observe_string_operation("startswith-hit", DEAL_STDLIB_STRING_STARTS_WITH,
                             "hello", "he", NULL);
    observe_string_operation("startswith-miss", DEAL_STDLIB_STRING_STARTS_WITH,
                             "hello", "lo", NULL);
    observe_string_operation("startswith-empty", DEAL_STDLIB_STRING_STARTS_WITH,
                             "hello", "", NULL);
    observe_string_operation("endswith-hit", DEAL_STDLIB_STRING_ENDS_WITH,
                             "hello", "lo", NULL);
    observe_string_operation("endswith-miss", DEAL_STDLIB_STRING_ENDS_WITH,
                             "hello", "he", NULL);
    observe_string_operation("endswith-empty", DEAL_STDLIB_STRING_ENDS_WITH,
                             "hello", "", NULL);

    /* std/string replace: non-overlapping, literal, empty search returns the
     * input and an empty replacement deletes. */
    observe_string_operation("replace-all", DEAL_STDLIB_STRING_REPLACE,
                             "aaaa", "aa", "b");
    observe_string_operation("replace-delete", DEAL_STDLIB_STRING_REPLACE,
                             "abc", "b", "");
    observe_string_operation("replace-empty-search", DEAL_STDLIB_STRING_REPLACE,
                             "aaa", "", "x");
    observe_string_operation("replace-literal", DEAL_STDLIB_STRING_REPLACE,
                             "a.b.c", ".", "-");

    /* std/string split: empty input, empty separator, preserved empty parts and
     * a scalar split. */
    observe_string_operation("split-empty-input", DEAL_STDLIB_STRING_SPLIT,
                             "", ",", NULL);
    observe_string_operation("split-basic", DEAL_STDLIB_STRING_SPLIT,
                             "a,b", ",", NULL);
    observe_string_operation("split-empty-parts", DEAL_STDLIB_STRING_SPLIT,
                             ",a,", ",", NULL);
    observe_string_operation("split-scalars", DEAL_STDLIB_STRING_SPLIT,
                             "abc", "", NULL);
    observe_string_operation("split-astral", DEAL_STDLIB_STRING_SPLIT,
                             "\xf0\x9d\x84\x9e" "a", "", NULL);

    /* std/string trim: the closed set only. */
    observe_string_operation("trim-closed-set", DEAL_STDLIB_STRING_TRIM,
                             " \t\n\r\v\f x \t", NULL, NULL);
    observe_string_operation("trim-nbsp", DEAL_STDLIB_STRING_TRIM,
                             "\xc2\xa0x\xc2\xa0", NULL, NULL);
    observe_string_operation("trim-all", DEAL_STDLIB_STRING_TRIM,
                             "   ", NULL, NULL);

    /* std/table keys: present keys in the recorded first-insertion order (R2),
     * null- and undefined-valued keys included and a deleted key absent. */
    {
        deal_root_scope_push();
        deal_table *table = deal_container_table_new();
        (void)deal_root_scope_hold(table);
        deal_string *first = deal_text_new("b", 1u);
        (void)deal_root_scope_hold(first);
        deal_string *second = deal_text_new("a", 1u);
        (void)deal_root_scope_hold(second);
        deal_string *third = deal_text_new("z", 1u);
        (void)deal_root_scope_hold(third);
        deal_string *fourth = deal_text_new("mid", 3u);
        (void)deal_root_scope_hold(fourth);
        deal_data null_value = deal_data_of_null();
        deal_data undefined_value = deal_data_of_undefined();
        deal_data number_value = deal_data_of_number(1.0);
        deal_container_table_write(table, first, &null_value);
        deal_container_table_write(table, second, &undefined_value);
        deal_container_table_write(table, third, &number_value);
        deal_container_table_write(table, fourth, &number_value);
        deal_string *deleted = deal_text_new("gone", 4u);
        (void)deal_root_scope_hold(deleted);
        deal_container_table_write(table, deleted, &number_value);
        deal_container_table_delete(table, deleted);
        deal_check_input arguments[1];
        arguments[0] = datum_argument(deal_data_of_reference(DEAL_DATA_TABLE, table));
        observe_std("table-keys", DEAL_STDLIB_TABLE_KEYS, arguments, 1u);
        deal_root_scope_pop();
    }

    /* std/json parse: the record root and the two closed return-boundary
     * failures. */
    {
        static const struct { const char *label; const char *json; } cases[] = {
            {"parse-record", "{\"a\":1}"},
            {"parse-array-root", "[1]"},
            {"parse-number-root", "1"},
            {"parse-null-root", "null"}
        };
        for (unsigned index = 0; index < 4u; index += 1u) {
            deal_root_scope_push();
            deal_string *text = deal_text_new(cases[index].json,
                                              strlen(cases[index].json));
            (void)deal_root_scope_hold(text);
            deal_check_input arguments[1];
            arguments[0] = string_argument(text);
            observe_std(cases[index].label, DEAL_STDLIB_JSON_PARSE, arguments, 1u);
            deal_root_scope_pop();
        }
    }

    /* std/json stringify through the closed operation. */
    {
        deal_root_scope_push();
        deal_table *table = record_with_text("k", "v");
        deal_check_input arguments[1];
        arguments[0] = datum_argument(deal_data_of_reference(DEAL_DATA_TABLE, table));
        observe_std("stringify-record", DEAL_STDLIB_JSON_STRINGIFY, arguments, 1u);
        deal_root_scope_pop();

        deal_check_input undefined_argument[1];
        undefined_argument[0] = datum_argument(deal_data_of_undefined());
        observe_std("stringify-undefined", DEAL_STDLIB_JSON_STRINGIFY,
                    undefined_argument, 1u);

        deal_check_input number_arguments[1];
        number_arguments[0] = number_argument(1.5);
        observe_std("stringify-number", DEAL_STDLIB_JSON_STRINGIFY,
                    number_arguments, 1u);
    }

    /* std/math. */
    observe_number_operation("floor-positive", DEAL_STDLIB_MATH_FLOOR, 1.5);
    observe_number_operation("floor-negative", DEAL_STDLIB_MATH_FLOOR, -0.5);
    observe_number_operation("floor-integral", DEAL_STDLIB_MATH_FLOOR, 3.0);
    observe_number_operation("floor-negative-zero", DEAL_STDLIB_MATH_FLOOR, -0.0);
    observe_number_operation("floor-nan", DEAL_STDLIB_MATH_FLOOR, 0.0 / 0.0);
    observe_number_operation("floor-infinite", DEAL_STDLIB_MATH_FLOOR, 1.0 / 0.0);
    observe_number_operation("ceil-positive", DEAL_STDLIB_MATH_CEIL, 1.5);
    observe_number_operation("ceil-negative", DEAL_STDLIB_MATH_CEIL, -0.5);
    observe_number_operation("ceil-integral", DEAL_STDLIB_MATH_CEIL, 3.0);
    observe_number_operation("ceil-negative-zero", DEAL_STDLIB_MATH_CEIL, -0.0);
    observe_number_operation("ceil-nan", DEAL_STDLIB_MATH_CEIL, 0.0 / 0.0);
    observe_number_operation("ceil-infinite", DEAL_STDLIB_MATH_CEIL, -1.0 / 0.0);
    observe_number_operation("sqrt-two", DEAL_STDLIB_MATH_SQRT, 2.0);
    observe_number_operation("sqrt-exact", DEAL_STDLIB_MATH_SQRT, 6.25);
    observe_number_operation("sqrt-nine", DEAL_STDLIB_MATH_SQRT, 9.0);
    observe_number_operation("sqrt-zero", DEAL_STDLIB_MATH_SQRT, 0.0);
    observe_number_operation("sqrt-negative-zero", DEAL_STDLIB_MATH_SQRT, -0.0);
    observe_number_operation("sqrt-nan", DEAL_STDLIB_MATH_SQRT, 0.0 / 0.0);
    observe_number_operation("sqrt-infinite", DEAL_STDLIB_MATH_SQRT, 1.0 / 0.0);
    observe_number_operation("sqrt-large", DEAL_STDLIB_MATH_SQRT, 1e300);
    observe_number_operation("sqrt-smallest-subnormal", DEAL_STDLIB_MATH_SQRT,
                             4.9406564584124654e-324);
    observe_number_operation("sqrt-rounding", DEAL_STDLIB_MATH_SQRT,
                             2.0e-308);
    observe_number_operation("sqrt-negative", DEAL_STDLIB_MATH_SQRT, -1.0);
    observe_number_operation("abs-negative-zero", DEAL_STDLIB_MATH_ABS_NUMBER, -0.0);
    observe_number_operation("abs-negative", DEAL_STDLIB_MATH_ABS_NUMBER, -2.5);
    observe_number_operation("abs-nan", DEAL_STDLIB_MATH_ABS_NUMBER, 0.0 / 0.0);
    observe_number_operation("abs-infinite", DEAL_STDLIB_MATH_ABS_NUMBER, -1.0 / 0.0);

    /* std/time.nowMillis: a positive binary64 epoch reading. */
    {
        deal_outcome_data outcome = deal_std_call(DEAL_STDLIB_TIME_NOW_MILLIS,
                                                  NULL, 0u, &call_origin);
        printf("STD\tnow-millis\t");
        if (outcome.error != NULL) {
            printf("ERROR\t");
            print_failure(outcome.error);
            printf("\n");
        } else {
            printf("NUMBER\t");
            print_number(outcome.value.number);
            printf("\n");
        }
    }

    /* A stdlib parameter crossing failure and a result crossing failure. */
    {
        deal_check_input arguments[1];
        arguments[0] = number_argument(1.0);
        observe_std("length-kind-failure", DEAL_STDLIB_STRING_LENGTH, arguments, 1u);
    }
    {
        deal_check_input arguments[1];
        arguments[0] = datum_argument(deal_data_of_undefined());
        observe_std("stringify-nothing-failure", DEAL_STDLIB_JSON_PARSE,
                    arguments, 1u);
    }
}

/* =========================================================================
 * The class plans and the two class walks
 * ========================================================================= */

static const char *const outer_atom = "@src/model.deal/Outer";
static const char *const inner_atom = "@src/model.deal/Inner";

/* The driver-side instance marker: the class atom of the instance. */
static const char *const instance_atom_key = "\001atom";

static deal_class_field outer_fields[8];
static deal_class_field inner_fields[2];
static deal_class_layout outer_layout;
static deal_class_layout inner_layout;
static deal_json_class_plan outer_plan;
static deal_json_class_plan inner_plan;

/* The recursive plan: `class Node { child?: ?Node; list?: ?[Node] }`. Its graph
 * names itself, so the *input's* nesting depth decides the decode walk depth
 * and the walk must stay stack-safe (R6). */
static const char *const node_atom = "@src/model.deal/Node";
static deal_class_field node_fields[2];
static deal_class_layout node_layout;
static deal_json_class_plan node_plan;

/* 1 when the instance carries exactly `atom`. */
static int instance_is(const deal_data *instance, const char *atom) {
    if (instance->tag != DEAL_DATA_CLASS || instance->reference == NULL) {
        return 0;
    }
    deal_table *fields = (deal_table *)instance->reference;
    deal_string *key = deal_text_new(instance_atom_key,
        strlen(instance_atom_key));
    (void)deal_root_scope_hold(key);
    if (!deal_container_table_has(fields, key)) {
        return 0;
    }
    deal_data recorded = deal_container_table_read(fields, key);
    if (recorded.tag != DEAL_DATA_STRING || recorded.reference == NULL) {
        return 0;
    }
    const deal_string *carried = (const deal_string *)recorded.reference;
    size_t length = strlen(atom);
    if (deal_text_byte_length(carried) != length) {
        return 0;
    }
    return memcmp(deal_text_bytes(carried), atom, length) == 0;
}

/* The test construction seam: one record-shaped instance of the plan's class. */
static deal_outcome_data test_construct(const deal_class_layout *layout,
                                        const deal_data *values,
                                        const uint8_t *presence,
                                        void *context) {
    (void)context;
    deal_outcome_data outcome;
    memset(&outcome, 0, sizeof(outcome));
    deal_table *fields = deal_container_table_new();
    (void)deal_root_scope_hold(fields);
    deal_string *atom_key = deal_text_new(instance_atom_key,
        strlen(instance_atom_key));
    (void)deal_root_scope_hold(atom_key);
    deal_string *atom = deal_text_new(layout->class_atom,
        strlen(layout->class_atom));
    (void)deal_root_scope_hold(atom);
    deal_data atom_value = deal_data_of_reference(DEAL_DATA_STRING, atom);
    deal_container_table_write(fields, atom_key, &atom_value);
    for (size_t index = 0; index < layout->field_count; index += 1u) {
        if (!presence[index]) {
            continue;
        }
        deal_string *name = deal_text_new(layout->fields[index].name,
            strlen(layout->fields[index].name));
        (void)deal_root_scope_hold(name);
        deal_container_table_write(fields, name, &values[index]);
    }
    outcome.value = deal_data_of_reference(DEAL_DATA_CLASS, fields);
    return outcome;
}

/* The test field seam: one field of one record-shaped instance. */
static int test_read_field(const deal_data *instance, size_t index,
                           deal_check_input *value, uint8_t *present,
                           void *context) {
    const deal_class_layout *layout = (const deal_class_layout *)context;
    if (layout == NULL || index >= layout->field_count
            || !instance_is(instance, layout->class_atom)) {
        return 0;
    }
    deal_table *fields = (deal_table *)instance->reference;
    deal_string *name = deal_text_new(layout->fields[index].name,
        strlen(layout->fields[index].name));
    (void)deal_root_scope_hold(name);
    memset(value, 0, sizeof(*value));
    value->presentation = DEAL_PRESENT_DATUM;
    *present = 0u;
    if (!deal_container_table_has(fields, name)) {
        return 1;
    }
    value->datum = deal_container_table_read(fields, name);
    *present = 1u;
    if (value->datum.tag == DEAL_DATA_CLASS) {
        /* The nested instance's own canonical atom, which the two test plans
         * carry as static text. */
        value->class_atom = instance_is(&value->datum, inner_atom)
            ? inner_atom : outer_atom;
    }
    return 1;
}

/* The test plan registry: one plan per @jsonable class atom. */
static const deal_json_class_plan *test_lookup(const char *class_atom,
                                               void *context) {
    (void)context;
    if (strcmp(class_atom, outer_atom) == 0) {
        return &outer_plan;
    }
    if (strcmp(class_atom, inner_atom) == 0) {
        return &inner_plan;
    }
    if (strcmp(class_atom, node_atom) == 0) {
        return &node_plan;
    }
    return NULL;
}

static void define_plans(void) {
    /* Outer: name: string; nick?: string; bio: ?string; tags: [string];
     * props: record; extra?: ?!string; items: [Inner]; label!: ?!string.
     * `label` is required and admits undefined, which is the v1.5
     * required-undefined asymmetry: it must be present in a decoded document
     * and is omitted from the encoding when present-undefined. */
    outer_fields[0].name = "name";
    outer_fields[0].descriptor = deal_descriptor_intern("string");
    outer_fields[0].optional = 0;
    outer_fields[1].name = "nick";
    outer_fields[1].descriptor = deal_descriptor_intern("string");
    outer_fields[1].optional = 1;
    outer_fields[2].name = "bio";
    outer_fields[2].descriptor = deal_descriptor_intern("?string");
    outer_fields[2].optional = 0;
    outer_fields[3].name = "tags";
    outer_fields[3].descriptor = deal_descriptor_intern("[string]");
    outer_fields[3].optional = 0;
    outer_fields[4].name = "props";
    outer_fields[4].descriptor = deal_descriptor_intern("record");
    outer_fields[4].optional = 0;
    outer_fields[5].name = "extra";
    outer_fields[5].descriptor = deal_descriptor_intern("?!string");
    outer_fields[5].optional = 1;
    outer_fields[6].name = "items";
    outer_fields[6].descriptor =
        deal_descriptor_intern("[@src/model.deal/Inner]");
    outer_fields[6].optional = 0;
    outer_fields[7].name = "label";
    outer_fields[7].descriptor = deal_descriptor_intern("?!string");
    outer_fields[7].optional = 0;
    outer_layout.class_atom = outer_atom;
    outer_layout.fields = outer_fields;
    outer_layout.field_count = 8u;
    outer_plan.layout = &outer_layout;
    outer_plan.construct = test_construct;
    outer_plan.read_field = test_read_field;
    outer_plan.context = &outer_layout;

    /* Inner: id: number; note?: string. */
    inner_fields[0].name = "id";
    inner_fields[0].descriptor = deal_descriptor_intern("number");
    inner_fields[0].optional = 0;
    inner_fields[1].name = "note";
    inner_fields[1].descriptor = deal_descriptor_intern("string");
    inner_fields[1].optional = 1;
    inner_layout.class_atom = inner_atom;
    inner_layout.fields = inner_fields;
    inner_layout.field_count = 2u;
    inner_plan.layout = &inner_layout;
    inner_plan.construct = test_construct;
    inner_plan.read_field = test_read_field;
    inner_plan.context = &inner_layout;

    /* Node: child?: ?Node; list?: ?[Node]. */
    node_fields[0].name = "child";
    node_fields[0].descriptor = deal_descriptor_intern("?@src/model.deal/Node");
    node_fields[0].optional = 1;
    node_fields[1].name = "list";
    node_fields[1].descriptor =
        deal_descriptor_intern("?[@src/model.deal/Node]");
    node_fields[1].optional = 1;
    node_layout.class_atom = node_atom;
    node_layout.fields = node_fields;
    node_layout.field_count = 2u;
    node_plan.layout = &node_layout;
    node_plan.construct = test_construct;
    node_plan.read_field = test_read_field;
    node_plan.context = &node_layout;
}

/* One fromJson observation: the produced instance re-encoded, or `null`. */
static void observe_from_class(const char *label, const char *json) {
    deal_string *text = deal_text_new(json, strlen(json));
    deal_root_scope_push();
    (void)deal_root_scope_hold(text);
    deal_outcome_data decoded = deal_json_from_class(text, &outer_plan,
        test_lookup, NULL, &call_origin);
    printf("FROM\t%s\t", label);
    if (decoded.error != NULL) {
        printf("ERROR\t");
        print_failure(decoded.error);
        printf("\n");
        deal_root_scope_pop();
        return;
    }
    if (decoded.value.tag == DEAL_DATA_NULL) {
        printf("NULL\n");
        deal_root_scope_pop();
        return;
    }
    (void)deal_root_scope_hold_data(&decoded.value);
    deal_outcome_string encoded = deal_json_to_class(&outer_plan, &decoded.value,
        test_lookup, NULL, &call_origin);
    if (encoded.error != NULL) {
        printf("ERROR\t");
        print_failure(encoded.error);
        printf("\n");
    } else {
        printf("TEXT\t");
        write_text(encoded.value);
        printf("\n");
    }
    deal_root_scope_pop();
}

/* One toJson observation over a driver-built instance. */
static void observe_to_class(const char *label, deal_data instance) {
    deal_root_scope_push();
    (void)deal_root_scope_hold_data(&instance);
    deal_outcome_string encoded = deal_json_to_class(&outer_plan, &instance,
        test_lookup, NULL, &call_origin);
    printf("TO\t%s\t", label);
    if (encoded.error != NULL) {
        printf("ERROR\t");
        print_failure(encoded.error);
        printf("\n");
    } else {
        printf("TEXT\t");
        write_text(encoded.value);
        printf("\n");
    }
    deal_root_scope_pop();
}

/* One driver-built instance of `layout`; the caller owns the root scope. */
static deal_data build_instance(const deal_class_layout *layout) {
    deal_table *fields = deal_container_table_new();
    (void)deal_root_scope_hold(fields);
    deal_string *atom_key = deal_text_new(instance_atom_key,
        strlen(instance_atom_key));
    (void)deal_root_scope_hold(atom_key);
    deal_string *atom = deal_text_new(layout->class_atom,
        strlen(layout->class_atom));
    (void)deal_root_scope_hold(atom);
    deal_data atom_value = deal_data_of_reference(DEAL_DATA_STRING, atom);
    deal_container_table_write(fields, atom_key, &atom_value);
    deal_data instance;
    memset(&instance, 0, sizeof(instance));
    instance = deal_data_of_reference(DEAL_DATA_CLASS, fields);
    return instance;
}

/* Stores one field of one driver-built instance. */
static void put_field(deal_data instance, const char *name, deal_data value) {
    deal_table *fields = (deal_table *)instance.reference;
    deal_string *key = deal_text_new(name, strlen(name));
    (void)deal_root_scope_hold(key);
    deal_container_table_write(fields, key, &value);
}

/* A `[string]` array of the given texts. */
static deal_data string_array(const char *first, const char *second) {
    deal_array *array = deal_container_array_new_typed(
        DEAL_LAYOUT_ELEMENT_REFERENCE,
        deal_descriptor_reference(deal_descriptor_intern("string")), 0u);
    (void)deal_root_scope_hold(array);
    const char *const texts[2] = {first, second};
    for (unsigned index = 0; index < 2u; index += 1u) {
        if (texts[index] == NULL) {
            continue;
        }
        deal_string *element = deal_text_new(texts[index], strlen(texts[index]));
        (void)deal_root_scope_hold(element);
        deal_container_array_append(array, &element);
    }
    return deal_data_of_reference(DEAL_DATA_ARRAY, array);
}

/* An `[Inner]` array of one instance with the given id. */
static deal_data inner_array(double first_id, double second_id) {
    deal_array *array = deal_container_array_new_typed(
        DEAL_LAYOUT_ELEMENT_DATUM,
        deal_descriptor_reference(
            deal_descriptor_intern("@src/model.deal/Inner")), 0u);
    (void)deal_root_scope_hold(array);
    const double ids[2] = {first_id, second_id};
    for (unsigned index = 0; index < 2u; index += 1u) {
        if (!(ids[index] == ids[index])) {
            continue;
        }
        deal_data instance = build_instance(&inner_layout);
        put_field(instance, "id", deal_data_of_number(ids[index]));
        deal_container_array_append(array, &instance);
    }
    return deal_data_of_reference(DEAL_DATA_ARRAY, array);
}

static void observe_class_walks(void) {
    /* fromJson: a complete document, decoded recursively in declaration order
     * and re-encoded in the same order; the optional field stays absent. */
    observe_from_class("complete",
        "{\"name\":\"Ada\",\"bio\":null,\"tags\":[\"x\",\"y\"],"
        "\"props\":{\"k\":[1,2]},\"items\":[{\"id\":2}],\"label\":null}");
    observe_from_class("optional-present",
        "{\"name\":\"Ada\",\"nick\":\"A\",\"bio\":\"text\",\"tags\":[],"
        "\"props\":{},\"items\":[{\"id\":2,\"note\":\"n\"}],"
        "\"label\":\"L\"}");
    observe_from_class("extra-key",
        "{\"name\":\"Ada\",\"bio\":null,\"tags\":[],\"props\":{},"
        "\"items\":[],\"naem\":\"Bob\"}");
    observe_from_class("missing-required",
        "{\"bio\":null,\"tags\":[],\"props\":{},\"items\":[]}");
    observe_from_class("missing-required-nullable",
        "{\"name\":\"Ada\",\"tags\":[],\"props\":{},\"items\":[]}");
    observe_from_class("wrong-kind",
        "{\"name\":1,\"bio\":null,\"tags\":[],\"props\":{},\"items\":[]}");
    observe_from_class("duplicate-nested-key",
        "{\"name\":\"Ada\",\"bio\":null,\"tags\":[],\"props\":{},"
        "\"items\":[{\"id\":1,\"id\":2}]}");
    observe_from_class("nested-extra-key",
        "{\"name\":\"Ada\",\"bio\":null,\"tags\":[],\"props\":{},\"items\":[],"
        "\"inner\":{\"id\":1}}");
    observe_from_class("array-element-kind",
        "{\"name\":\"Ada\",\"bio\":null,\"tags\":[1],\"props\":{},\"items\":[]}");
    observe_from_class("non-object-root", "[]");
    observe_from_class("syntax-defect",
        "{\"name\":\"Ada\",\"bio\":null}");
    observe_from_class("extra-present-undefined-member",
        "{\"name\":\"Ada\",\"nick\":null,\"bio\":null,\"tags\":[],"
        "\"props\":{},\"items\":[],\"extra\":null}");
    /* A required field whose type admits undefined must still be present: an
     * omission is the same null answer as any other missing required key. */
    observe_from_class("required-undefined-omitted",
        "{\"name\":\"Ada\",\"bio\":null,\"tags\":[],\"props\":{},"
        "\"items\":[]}");

    /* toJson: declaration order, absent optional omitted, present null kept,
     * a present undefined-valued field omitted, a non-finite number as null. */
    deal_root_scope_push();
    deal_data instance = build_instance(&outer_layout);
    put_field(instance, "name",
              text_datum("Ada"));
    put_field(instance, "bio", deal_data_of_null());
    put_field(instance, "tags", string_array("x", "y"));
    {
        deal_data props = deal_data_of_reference(DEAL_DATA_TABLE,
                                                 record_with_text("k", "v"));
        put_field(instance, "props", props);
    }
    put_field(instance, "items", inner_array(3.0, 0.0 / 0.0));
    put_field(instance, "label", deal_data_of_null());
    observe_to_class("instance-minimal", instance);
    deal_root_scope_pop();

    /* A present undefined in an undefined-admitting optional field is omitted;
     * the same value in a required `string` field is the walk arm. */
    deal_root_scope_push();
    deal_data with_undefined = build_instance(&outer_layout);
    put_field(with_undefined, "name",
              text_datum("Ada"));
    put_field(with_undefined, "bio", deal_data_of_null());
    put_field(with_undefined, "tags", string_array(NULL, NULL));
    put_field(with_undefined, "props", empty_record());
    put_field(with_undefined, "items", inner_array(0.0 / 0.0, 0.0 / 0.0));
    put_field(with_undefined, "extra", deal_data_of_undefined());
    put_field(with_undefined, "label", deal_data_of_null());
    observe_to_class("instance-omits-undefined", with_undefined);
    deal_root_scope_pop();

    deal_root_scope_push();
    deal_data missing_required = build_instance(&outer_layout);
    put_field(missing_required, "bio", deal_data_of_null());
    put_field(missing_required, "tags", string_array(NULL, NULL));
    put_field(missing_required, "props", empty_record());
    put_field(missing_required, "items", inner_array(0.0 / 0.0, 0.0 / 0.0));
    put_field(missing_required, "label", deal_data_of_null());
    observe_to_class("missing-required-field", missing_required);
    deal_root_scope_pop();

    deal_root_scope_push();
    deal_data undefined_required = build_instance(&outer_layout);
    put_field(undefined_required, "name", deal_data_of_undefined());
    put_field(undefined_required, "bio", deal_data_of_null());
    put_field(undefined_required, "tags", string_array(NULL, NULL));
    put_field(undefined_required, "props", empty_record());
    put_field(undefined_required, "items", inner_array(0.0 / 0.0, 0.0 / 0.0));
    put_field(undefined_required, "label", deal_data_of_null());
    observe_to_class("undefined-required-field", undefined_required);
    deal_root_scope_pop();

    deal_root_scope_push();
    deal_data nonfinite = build_instance(&outer_layout);
    put_field(nonfinite, "name",
              text_datum("Ada"));
    put_field(nonfinite, "bio", deal_data_of_null());
    put_field(nonfinite, "tags", string_array(NULL, NULL));
    put_field(nonfinite, "props", empty_record());
    put_field(nonfinite, "label", deal_data_of_null());
    {
        deal_array *items = deal_container_array_new_typed(
            DEAL_LAYOUT_ELEMENT_DATUM,
            deal_descriptor_reference(
                deal_descriptor_intern("@src/model.deal/Inner")), 0u);
        (void)deal_root_scope_hold(items);
        deal_data inner = build_instance(&inner_layout);
        put_field(inner, "id", deal_data_of_number(1.0 / 0.0));
        deal_container_array_append(items, &inner);
        put_field(nonfinite, "items",
                  deal_data_of_reference(DEAL_DATA_ARRAY, items));
    }
    observe_to_class("nonfinite-field", nonfinite);
    deal_root_scope_pop();

    /* A record field's content: undefined/function members omitted, a nested
     * class instance rejected by the walk arm, and a cycle by the cycle arm. */
    deal_root_scope_push();
    deal_data record_content = build_instance(&outer_layout);
    put_field(record_content, "name",
              text_datum("Ada"));
    put_field(record_content, "bio", deal_data_of_null());
    put_field(record_content, "tags", string_array(NULL, NULL));
    put_field(record_content, "items", inner_array(0.0 / 0.0, 0.0 / 0.0));
    put_field(record_content, "label", deal_data_of_null());
    {
        deal_table *props = deal_container_table_new();
        (void)deal_root_scope_hold(props);
        deal_string *omitted = deal_text_new("omitted", 7u);
        (void)deal_root_scope_hold(omitted);
        deal_data undefined_value = deal_data_of_undefined();
        deal_container_table_write(props, omitted, &undefined_value);
        deal_string *kept = deal_text_new("kept", 4u);
        (void)deal_root_scope_hold(kept);
        deal_data array_value = string_array("a", NULL);
        deal_container_table_write(props, kept, &array_value);
        put_field(record_content, "props",
                  deal_data_of_reference(DEAL_DATA_TABLE, props));
    }
    observe_to_class("record-content", record_content);
    deal_root_scope_pop();

    deal_root_scope_push();
    deal_data record_class_member = build_instance(&outer_layout);
    put_field(record_class_member, "name",
              text_datum("Ada"));
    put_field(record_class_member, "bio", deal_data_of_null());
    put_field(record_class_member, "tags", string_array(NULL, NULL));
    put_field(record_class_member, "items", inner_array(0.0 / 0.0, 0.0 / 0.0));
    put_field(record_class_member, "label", deal_data_of_null());
    {
        deal_table *props = deal_container_table_new();
        (void)deal_root_scope_hold(props);
        deal_string *key = deal_text_new("nested", 6u);
        (void)deal_root_scope_hold(key);
        deal_data nested = build_instance(&inner_layout);
        put_field(nested, "id", deal_data_of_number(1.0));
        deal_container_table_write(props, key, &nested);
        put_field(record_class_member, "props",
                  deal_data_of_reference(DEAL_DATA_TABLE, props));
    }
    observe_to_class("record-class-member", record_class_member);
    deal_root_scope_pop();

    deal_root_scope_push();
    deal_data record_cycle = build_instance(&outer_layout);
    put_field(record_cycle, "name",
              text_datum("Ada"));
    put_field(record_cycle, "bio", deal_data_of_null());
    put_field(record_cycle, "tags", string_array(NULL, NULL));
    put_field(record_cycle, "items", inner_array(0.0 / 0.0, 0.0 / 0.0));
    put_field(record_cycle, "label", deal_data_of_null());
    {
        deal_table *props = deal_container_table_new();
        (void)deal_root_scope_hold(props);
        deal_string *self = deal_text_new("self", 4u);
        (void)deal_root_scope_hold(self);
        deal_data cyclic = deal_data_of_reference(DEAL_DATA_TABLE, props);
        deal_container_table_write(props, self, &cyclic);
        put_field(record_cycle, "props",
                  deal_data_of_reference(DEAL_DATA_TABLE, props));
    }
    observe_to_class("record-cycle", record_cycle);
    deal_root_scope_pop();

    /* The v1.5 required-undefined asymmetry on the encode side: `label!: ?!string`
     * is required, so a present-undefined value is a present field whose key is
     * omitted, and the encoded text must not carry it. */
    deal_root_scope_push();
    deal_data required_undefined = build_instance(&outer_layout);
    put_field(required_undefined, "name",
              text_datum("Ada"));
    put_field(required_undefined, "bio", deal_data_of_null());
    put_field(required_undefined, "tags", string_array(NULL, NULL));
    put_field(required_undefined, "props", empty_record());
    put_field(required_undefined, "items",
              inner_array(0.0 / 0.0, 0.0 / 0.0));
    put_field(required_undefined, "label", deal_data_of_undefined());
    observe_to_class("required-undefined-present", required_undefined);
    deal_root_scope_pop();

    /* A walk failure three segments deep: only the failure projection
     * materializes the dotted/indexed spelling `props.a.b[0]`. */
    deal_root_scope_push();
    deal_data deep_member = build_instance(&outer_layout);
    put_field(deep_member, "name",
              text_datum("Ada"));
    put_field(deep_member, "bio", deal_data_of_null());
    put_field(deep_member, "tags", string_array(NULL, NULL));
    put_field(deep_member, "items", inner_array(0.0 / 0.0, 0.0 / 0.0));
    put_field(deep_member, "label", deal_data_of_null());
    {
        deal_table *inner_record = deal_container_table_new();
        (void)deal_root_scope_hold(inner_record);
        deal_string *nested_key = deal_text_new("b", 1u);
        (void)deal_root_scope_hold(nested_key);
        deal_array *nested_array = deal_container_array_new_typed(
            DEAL_LAYOUT_ELEMENT_DATUM, 0u, 0u);
        (void)deal_root_scope_hold(nested_array);
        deal_data member = build_instance(&inner_layout);
        put_field(member, "id", deal_data_of_number(1.0));
        deal_container_array_append(nested_array, &member);
        deal_data nested_value =
            deal_data_of_reference(DEAL_DATA_ARRAY, nested_array);
        deal_container_table_write(inner_record, nested_key, &nested_value);
        deal_table *props = deal_container_table_new();
        (void)deal_root_scope_hold(props);
        deal_string *key = deal_text_new("a", 1u);
        (void)deal_root_scope_hold(key);
        deal_data inner_value =
            deal_data_of_reference(DEAL_DATA_TABLE, inner_record);
        deal_container_table_write(props, key, &inner_value);
        put_field(deep_member, "props",
                  deal_data_of_reference(DEAL_DATA_TABLE, props));
    }
    observe_to_class("record-deep-member", deep_member);
    deal_root_scope_pop();
}

/* =========================================================================
 * The deep class walk (R6's stack-safe traversal)
 * ========================================================================= */

/*
 * The nesting depth one decoded document carries along one recursive field: the
 * walk of the produced instance is the driver's own and follows the same links
 * the document declared. Counting it proves the decode reached the innermost
 * level instead of stopping (or crashing) at a depth bound.
 */
static size_t count_class_depth(deal_data root, const char *field,
                                int through_array) {
    size_t depth = 0u;
    deal_root_scope_push();
    deal_string *key = deal_text_new(field, strlen(field));
    if (key == NULL) {
        deal_platform_fault("json: deep walk key allocation failed");
    }
    (void)deal_root_scope_hold(key);
    deal_data current = root;
    while (current.tag == DEAL_DATA_CLASS && current.reference != NULL) {
        deal_table *fields = (deal_table *)current.reference;
        if (!deal_container_table_has(fields, key)) {
            break;
        }
        deal_data next = deal_container_table_read(fields, key);
        if (next.tag == DEAL_DATA_NULL || next.tag == DEAL_DATA_UNDEFINED) {
            break;
        }
        if (through_array != 0) {
            if (next.tag != DEAL_DATA_ARRAY || next.reference == NULL) {
                break;
            }
            deal_array *array = (deal_array *)next.reference;
            if (deal_container_array_count(array) != 1u) {
                break;
            }
            next = *(const deal_data *)
                deal_container_array_element(array, 0u);
        }
        depth += 1u;
        current = next;
    }
    deal_root_scope_pop();
    return depth;
}

/*
 * One deeply nested class document of `depth` levels against the recursive plan:
 * every level names its child under `child` (or its one element under `list`) and
 * the innermost level names nothing. The declared type graph is one class, so
 * the depth is the input's and the decode walk must not consume one C frame per
 * level.
 */
static void observe_class_depth(const char *label, int through_array,
                                size_t depth) {
    const char *prefix = through_array != 0 ? "{\"list\":[" : "{\"child\":";
    const char *suffix = through_array != 0 ? "]}" : "}";
    size_t prefix_length = strlen(prefix);
    size_t suffix_length = strlen(suffix);
    size_t length = depth * (prefix_length + suffix_length) + 2u;
    unsigned char *json = deal_platform_allocate(length + 1u);
    if (json == NULL) {
        deal_platform_fault("json: class depth fixture allocation failed");
    }
    unsigned char *cursor = json;
    for (size_t index = 0; index < depth; index += 1u) {
        memcpy(cursor, prefix, prefix_length);
        cursor += prefix_length;
    }
    memcpy(cursor, "{}", 2u);
    cursor += 2u;
    for (size_t index = 0; index < depth; index += 1u) {
        memcpy(cursor, suffix, suffix_length);
        cursor += suffix_length;
    }
    *cursor = 0;
    deal_string *text = deal_text_new(json, length);
    deal_platform_release(json);
    deal_root_scope_push();
    (void)deal_root_scope_hold(text);
    deal_outcome_data decoded = deal_json_from_class(text, &node_plan,
        test_lookup, NULL, &call_origin);
    printf("CLASSDEEP\t%s\t%u\t", label, (unsigned)depth);
    if (decoded.error != NULL) {
        printf("ERROR\t");
        print_failure(decoded.error);
        printf("\n");
        deal_root_scope_pop();
        return;
    }
    if (decoded.value.tag != DEAL_DATA_CLASS) {
        printf("NO-INSTANCE\n");
        deal_root_scope_pop();
        return;
    }
    (void)deal_root_scope_hold_data(&decoded.value);
    printf("%u\n", (unsigned)count_class_depth(decoded.value,
        through_array != 0 ? "list" : "child", through_array));
    deal_root_scope_pop();
}

/* The class-decode deep run: both recursive shapes at the requested depth. */
static void run_class_deep(size_t depth) {
    observe_class_depth("chain", 0, depth);
    observe_class_depth("array", 1, depth);
    deal_platform_flush_stdout();
}

/* =========================================================================
 * The acceptance run and the deterministic producer defects
 * ========================================================================= */

static void run_acceptance(void) {
    observe_roundtrip("object", "{\"a\":1,\"b\":[true,false,null],\"c\":\"x\"}");
    observe_roundtrip("empty-array", "[]");
    observe_roundtrip("empty-object", "{}");
    observe_roundtrip("null", "null");
    observe_roundtrip("true", "true");
    observe_roundtrip("string", "\"s\"");
    observe_roundtrip("zero", "0");
    observe_roundtrip("negative-zero", "-0");
    observe_roundtrip("negative-zero-fraction", "-0.0");
    observe_roundtrip("one-e0", "1e0");
    observe_roundtrip("exponent", "1E2");
    observe_roundtrip("tenth", "0.1");
    observe_roundtrip("binary64-boundary", "9007199254740993");
    observe_roundtrip("overflow", "1e400");
    observe_roundtrip("underflow", "1e-400");
    observe_roundtrip("precision", "123456789012345678901234567890");
    observe_roundtrip("large-exponent", "1e21");
    observe_roundtrip("small-exponent", "1e-7");
    observe_roundtrip("negative", "-2.5");
    observe_roundtrip("nested", "{\"a\":{\"b\":{\"c\":[1,[2,[3]]]}}}");
    observe_roundtrip("bmp-scalar", "\"\\u00e9\\u0041\"");
    observe_roundtrip("surrogate-pair", "\"\\ud83d\\ude00\"");
    observe_roundtrip("escapes", "\"tab\\there quote\\\" backslash\\\\\"");
    observe_roundtrip("control-scalars", "\"\\u0000\\u001f\"");
    observe_roundtrip("own-key-order", "{\"b\":1,\"2\":2,\"a\":3,\"1\":4}");
    observe_roundtrip("astral-literal", "\"\xf0\x9d\x84\x9e" "ab\"");
    observe_roundtrip("whitespace", " \t\n\r[ 1 , 2 ] \t\n");

    observe_number_token("0");
    observe_number_token("-0");
    observe_number_token("1");
    observe_number_token("1.0");
    observe_number_token("1e0");
    observe_number_token("1E2");
    observe_number_token("0.1");
    observe_number_token("9007199254740993");
    observe_number_token("4.9406564584124654e-324");
    observe_number_token("1e400");
    observe_number_token("1e-400");
    observe_number_token("1.7976931348623157e308");
    observe_number_token("123456789012345678901234567890");
    observe_number_token("-2.5");

    observe_parse_failure("unexpected-end-object", "{");
    observe_parse_failure("missing-colon", "{\"a\"");
    observe_parse_failure("unterminated-object", "{\"a\":1");
    observe_parse_failure("unterminated-array", "[1");
    observe_parse_failure("unterminated-string", "\"abc");
    observe_parse_failure("duplicate-key", "{\"a\":1,\"a\":2}");
    observe_parse_failure("duplicate-escaped-key", "{\"a\":1,\"\\u0061\":2}");
    observe_parse_failure("leading-zero", "[01]");
    observe_parse_failure("invalid-number", "[1.]");
    observe_parse_failure("unexpected-character", "[nul]");
    observe_parse_failure("missing-comma", "[1 2]");
    observe_parse_failure("trailing-content", "{\"a\":1}x");
    observe_parse_failure("invalid-escape", "\"\\q\"");
    observe_parse_failure("unpaired-surrogate", "\"\\ud800\"");
    observe_parse_failure("raw-control", "\"a\x01b\"");
    observe_parse_failure("empty-input", "");

    observe_depth("nested-2000", 2000u);

    observe_encode_rules();
    observe_stdlib();
    observe_class_walks();

    deal_platform_flush_stdout();
    deal_platform_flush_stderr();
    printf("json-stdlib ok\n");
    if (failures != 0) {
        printf("FAILURES: %d\n", failures);
        deal_platform_exit(1);
    }
}

/* One deterministic producer or invariant defect, selected at run time so one
 * product serves the acceptance run and every defect. */
static void run_defect(int selection) {
    switch (selection) {
    case 1:
        (void)deal_std_call(DEAL_STDLIB_OPERATION_COUNT, NULL, 0u, &call_origin);
        break;
    case 2: {
        deal_check_input argument = number_argument(1.0);
        (void)deal_std_call(DEAL_STDLIB_STRING_LENGTH, &argument, 2u, &call_origin);
        break;
    }
    case 3:
        (void)deal_json_parse(NULL, &call_origin);
        break;
    case 4:
        (void)deal_json_parse(deal_text_new("{}", 2u), NULL);
        break;
    case 5: {
        /* A plan without its construction seam. */
        deal_json_class_plan incomplete = inner_plan;
        incomplete.construct = NULL;
        deal_string *text = deal_text_new("{\"id\":1}", 8u);
        (void)deal_json_from_class(text, &incomplete, test_lookup, NULL,
                                   &call_origin);
        break;
    }
    case 6: {
        /* An instance whose recorded class is not the plan's class: the field
         * seam refuses it and the walk reports the invariant fault. */
        deal_root_scope_push();
        deal_data instance = build_instance(&outer_layout);
        (void)deal_json_to_class(&inner_plan, &instance, test_lookup, NULL,
                                 &call_origin);
        deal_root_scope_pop();
        break;
    }
    case 7: {
        /* An array whose element layout is not one of the runtime's boxed
         * element layouts: the element read is the invariant fault. */
        static const deal_layout emitted_layouts[] = {
            { DEAL_LAYOUT_EMITTED_BASE, 0u, 8u, NULL, 0u }
        };
        deal_layout_install(emitted_layouts, 1u);
        deal_root_scope_push();
        deal_array *array = deal_container_array_new_typed(
            DEAL_LAYOUT_EMITTED_BASE,
            deal_descriptor_reference(deal_descriptor_intern("string")), 1u);
        (void)deal_root_scope_hold(array);
        deal_string *element = deal_text_new("x", 1u);
        (void)deal_root_scope_hold(element);
        deal_container_array_store(array, 0u, &element);
        deal_data instance = build_instance(&outer_layout);
        put_field(instance, "name", text_datum("Ada"));
        put_field(instance, "bio", deal_data_of_null());
        put_field(instance, "tags", deal_data_of_reference(DEAL_DATA_ARRAY, array));
        put_field(instance, "props", empty_record());
        put_field(instance, "items", inner_array(0.0 / 0.0, 0.0 / 0.0));
        (void)deal_json_to_class(&outer_plan, &instance, test_lookup, NULL,
                                 &call_origin);
        deal_root_scope_pop();
        break;
    }
    default:
        break;
    }
}

/*
 * The ambient-locale mode (R6). The locale is selected before anything is
 * decoded or encoded, and the acceptance matrix below must publish the same
 * bytes as the C-locale run: the decimal point of the ambient locale never
 * reaches `strtod` or the encoder's decimal conversion.
 */
static void run_locale_acceptance(void) {
    const char *selected = setlocale(LC_ALL, "");
    const struct lconv *facts = localeconv();
    check(selected != NULL,
          "the delegated ambient locale is installed for the process");
    check(facts != NULL && facts->decimal_point != NULL
              && facts->decimal_point[0] != 0
              && strcmp(facts->decimal_point, ".") != 0,
          "the ambient locale uses a comma decimal point, so it can expose an "
          "ambient-locale numeric conversion");
    printf("locale ok\n");
    deal_platform_flush_stdout();
    if (failures != 0) {
        printf("FAILURES: %d\n", failures);
        deal_platform_exit(1);
    }
    run_acceptance();
}

int main(void) {
    /* The observations interleave with the console effects the runtime writes
     * through its own buffered stream, so the driver's own stream is unbuffered
     * and every console call is followed by an explicit runtime flush. */
    (void)setvbuf(stdout, NULL, _IONBF, 0);
    (void)deal_project_start();
    define_plans();
    const char *selection = deal_platform_environment("DEAL_JSON_CASE");
    if (selection != NULL && selection[0] != 0) {
        int defect = 0;
        for (const char *digit = selection; *digit >= '0' && *digit <= '9';
                digit += 1) {
            defect = defect * 10 + (*digit - '0');
        }
        run_defect(defect);
        printf("unreachable\n");
        deal_platform_flush_stdout();
        return 0;
    }
    const char *class_deep = deal_platform_environment("DEAL_JSON_CLASS_DEEP");
    if (class_deep != NULL && class_deep[0] != 0) {
        size_t depth = 0u;
        for (const char *digit = class_deep; *digit >= '0' && *digit <= '9';
                digit += 1) {
            depth = depth * 10u + (size_t)(*digit - '0');
        }
        run_class_deep(depth);
        return failures == 0 ? 0 : 1;
    }
    const char *deep = deal_platform_environment("DEAL_JSON_DEEP");
    if (deep != NULL && deep[0] != 0) {
        size_t depth = 0u;
        for (const char *digit = deep; *digit >= '0' && *digit <= '9';
                digit += 1) {
            depth = depth * 10u + (size_t)(*digit - '0');
        }
        run_deep_scratch(depth);
        return failures == 0 ? 0 : 1;
    }
    const char *locale = deal_platform_environment("DEAL_JSON_LOCALE");
    if (locale != NULL && locale[0] != 0) {
        run_locale_acceptance();
        deal_platform_flush_stdout();
        deal_platform_flush_stderr();
        return failures == 0 ? 0 : 1;
    }
    run_acceptance();
    deal_platform_flush_stdout();
    deal_platform_flush_stderr();
    return failures == 0 ? 0 : 1;
}
