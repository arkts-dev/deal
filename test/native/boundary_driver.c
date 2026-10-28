/*
 * test/native/boundary_driver.c — the R2/R4/R5 boundary acceptance.
 *
 * A strict-C11 consumer of the published runtime: the canonical descriptor atom
 * parser and its interning, the descriptor-driven check at every closed check
 * boundary kind, the scalar boundary helpers, class construction validation and
 * the arm-table materialization of `deal_error` values, the E8002 array-position
 * projections, the canonical floating-point text of a numeric arm actual, the
 * host-authored text channel and the unchanged admission of a carried Error.
 *
 * The driver prints one canonical, tab-separated line per observation and the
 * Java suite derives the expected line from the shared failure-projection
 * authority (`FailureContractRegistry`, `BoundaryExecutor` and the closed
 * projections), so no printed expectation is its own authority:
 *
 *   CHECK      <descriptor> <boundary-kind> <index> <kind> <payload>
 *              <code> <expected> <actual> <line> <column> <message>
 *   ARM        <arm-id> <parameter-count> (<name> <value>)... <code>
 *              <expected> <actual> <message>
 *   CONSTRUCT  <case> <code> <expected> <actual> <tag> <file:line:column>
 *              <message>
 *
 * An absent field prints `-`. `-DDEAL_BOUNDARY_CASE=n` selects one closed
 * negative: each terminates with exactly one `DEAL_RUNTIME_FAULT: <reason>`
 * stderr line and exit 2 instead of publishing a partial result.
 */
#include "deal/native/container.h"
#include "deal/native/deal_rt.h"
#include "deal/native/descriptor.h"
#include "deal/native/entry.h"
#include "deal/native/error.h"
#include "deal/native/memory.h"
#include "deal/native/platform.h"
#include "deal/native/text.h"
#include "deal/native/value.h"

#include <math.h>
#include <stdio.h>
#include <string.h>

#ifndef DEAL_BOUNDARY_CASE
#define DEAL_BOUNDARY_CASE 0
#endif

/* The driver installs two emitted-style element layouts before any array. */
#define TEST_LAYOUT_NUMBER    1024u
#define TEST_LAYOUT_REFERENCE 1025u



#if DEAL_BOUNDARY_CASE == 0

static const char *const class_user = "@src/app/user.deal/User";
static const char *const class_other = "@src/app/user.deal/Other";

static const deal_origin test_origin = { "native/boundary.deal", 41, 7 };

static const uint32_t test_reference_offsets[] = { 0u };

static const char *text_or_absent(const char *text) {
    return text == NULL ? "-" : text;
}

static const deal_layout test_layouts[] = {
    { TEST_LAYOUT_NUMBER, 0u, 8u, NULL, 0u },
    { TEST_LAYOUT_REFERENCE, 0u, 8u, test_reference_offsets, 1u }
};

static int failures;

static void check(int condition, const char *what) {
    if (condition) {
        return;
    }
    printf("FAIL: %s\n", what);
    failures += 1;
}

/* =========================================================================
 * The descriptor atoms
 * ========================================================================= */

static void check_atoms(void) {
    static const char *const documents[] = {
        "null", "undefined", "boolean", "number", "string", "record", "unknown",
        "Error", "@src/app/user.deal/User", "[number]", "[[number]]",
        "?string", "!string", "?!number", "null|undefined",
        "?[@src/app/user.deal/User]", "[?@src/app/user.deal/User]",
        "(number,string)->boolean", "()->null", "async(number)->string",
        "()->void", "async()->void", "?[!number]",
        "(@src/app/user.deal/User)->[?record]"
    };
    for (size_t index = 0;
            index < sizeof(documents) / sizeof(documents[0]); index += 1u) {
        deal_descriptor *atom = deal_descriptor_intern(documents[index]);
        check(strcmp(deal_descriptor_text(atom), documents[index]) == 0,
              "an interned atom keeps its canonical text");
        check(deal_descriptor_intern(documents[index]) == atom,
              "identical descriptor text answers the identical atom");
        check(deal_descriptor_reference(atom) != 0u,
              "an atom carries a stable non-zero reference");
        check(deal_descriptor_of_reference(deal_descriptor_reference(atom)) == atom,
              "an atom reference resolves back to its atom");
    }
    deal_descriptor *number = deal_descriptor_intern("number");
    deal_descriptor *string = deal_descriptor_intern("string");
    deal_descriptor *record = deal_descriptor_intern("record");
    deal_descriptor *unknown = deal_descriptor_intern("unknown");
    deal_descriptor *user = deal_descriptor_intern(class_user);
    deal_descriptor *error_atom = deal_descriptor_intern("Error");
    deal_descriptor *array = deal_descriptor_intern("[number]");
    deal_descriptor *multiple = deal_descriptor_intern("[[number]]");
    deal_descriptor *nullable = deal_descriptor_intern("?string");
    deal_descriptor *undefined_only = deal_descriptor_intern("!string");
    deal_descriptor *both = deal_descriptor_intern("?!number");
    deal_descriptor *nullish_union = deal_descriptor_intern("null|undefined");
    deal_descriptor *function = deal_descriptor_intern("(number,string)->boolean");
    deal_descriptor *async_function = deal_descriptor_intern("async(number)->string");
    deal_descriptor *void_result = deal_descriptor_intern("()->void");

    check(deal_descriptor_intern("number") != deal_descriptor_intern("string"),
          "distinct descriptor texts answer distinct atoms");
    check(number != nullable, "a nullish atom is distinct from its base");
    check(deal_descriptor_type(number) == DEAL_DESCRIPTOR_NUMBER,
          "number interns as the number kind");
    check(deal_descriptor_type(record) == DEAL_DESCRIPTOR_RECORD,
          "record interns as the record kind");
    check(deal_descriptor_type(unknown) == DEAL_DESCRIPTOR_UNKNOWN,
          "unknown interns as the unknown kind");
    check(deal_descriptor_type(user) == DEAL_DESCRIPTOR_CLASS,
          "a nominal atom interns as the class kind");
    check(deal_descriptor_type(error_atom) == DEAL_DESCRIPTOR_CLASS,
          "the builtin Error atom interns as the class kind");
    check(deal_descriptor_type(nullable) == DEAL_DESCRIPTOR_NULLISH,
          "a nullish prefix interns as the nullish kind");
    check(deal_descriptor_type(nullish_union) == DEAL_DESCRIPTOR_NULLISH,
          "null|undefined interns as the nullish kind");
    check(deal_descriptor_type(function) == DEAL_DESCRIPTOR_FUNCTION,
          "a signature interns as the function kind");
    check(deal_descriptor_type(void_result) == DEAL_DESCRIPTOR_FUNCTION,
          "a void result is admitted inside a signature");
    check(strcmp(deal_descriptor_class_atom(error_atom), "Error") == 0,
          "the builtin class atom is the bare Error");
    check(strcmp(deal_descriptor_class_atom(user), class_user) == 0,
          "a class atom keeps its canonical text");
    check(deal_descriptor_element(array) == number,
          "an array atom answers its element atom");
    check(deal_descriptor_element(multiple) == array,
          "a nested array answers its own element atom");
    check(deal_descriptor_inner(nullable) == string,
          "a nullish atom answers its base atom");
    check(deal_descriptor_admits_null(nullable) == 1
              && deal_descriptor_admits_undefined(nullable) == 0,
          "?string admits null only");
    check(deal_descriptor_admits_null(undefined_only) == 0
              && deal_descriptor_admits_undefined(undefined_only) == 1,
          "!string admits undefined only");
    check(deal_descriptor_admits_null(both) == 1
              && deal_descriptor_admits_undefined(both) == 1,
          "?!number admits both");
    check(deal_descriptor_admits_null(nullish_union) == 1
              && deal_descriptor_admits_undefined(nullish_union) == 0,
          "null|undefined admits null over the undefined base");
    check(deal_descriptor_inner(nullish_union) == deal_descriptor_intern("undefined"),
          "null|undefined carries the undefined base");
    /* The closed kind vocabulary (P2 items 1 and 3). */
    check(strcmp(deal_descriptor_kind_text(number), "number") == 0,
          "number projects the number kind text");
    check(strcmp(deal_descriptor_kind_text(record), "table") == 0,
          "a record projects the table kind text");
    check(strcmp(deal_descriptor_kind_token(user), "class") == 0,
          "a class projects the class token");
    check(strcmp(deal_descriptor_kind_text(user), "class instance") == 0,
          "a class projects the class-instance kind text");
    check(strcmp(deal_descriptor_kind_text(nullable), "string") == 0,
          "a nullish atom projects its base kind text");
    check(strcmp(deal_descriptor_kind_text(void_result), "function") == 0,
          "a signature projects the function kind text");
    check(strcmp(deal_descriptor_kind_text(array), "array") == 0,
          "an array projects the array kind text");
    check(strcmp(deal_descriptor_kind_token(async_function), "function") == 0,
          "an async signature projects the function token");
    check(strcmp(deal_descriptor_kind_token(error_atom), "class") == 0,
          "the builtin Error atom projects the class token");
}

/* =========================================================================
 * The check matrix
 * ========================================================================= */

static void print_check(const char *descriptor, const char *boundary,
                        const char *index, const char *kind, const char *payload,
                        deal_error *error) {
    if (error == NULL) {
        printf("CHECK\t%s\t%s\t%s\t%s\t%s\t-\t-\t-\t-\t-\t-\n", descriptor,
            boundary, index, kind, payload);
        return;
    }
    const char *code = NULL;
    const char *expected = NULL;
    const char *actual = NULL;
    const char *message = NULL;
    int32_t line = 0;
    int32_t column = 0;
    (void)deal_error_code(error, &code);
    (void)deal_error_expected(error, &expected);
    (void)deal_error_actual(error, &actual);
    (void)deal_error_message(error, &message);
    (void)deal_error_line(error, &line);
    (void)deal_error_column(error, &column);
    printf("CHECK\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%d\t%d\t%s\n", descriptor,
        boundary, index, kind, payload, text_or_absent(code),
        text_or_absent(expected), text_or_absent(actual), (int)line, (int)column,
        text_or_absent(message));
}

static void run_check(const char *descriptor, uint32_t boundary_kind,
                      int32_t parameter_index, const char *kind,
                      const char *payload, deal_check_input input) {
    static const char *const boundary_names[] = {
        "type-descriptor", "function-signature", "host-parameter",
        "host-sync-return", "async-completion"
    };
    deal_check_context context;
    context.origin = &test_origin;
    context.parameter_index = parameter_index;
    char index_text[8];
    snprintf(index_text, sizeof(index_text), "%d", (int)parameter_index);
    deal_outcome outcome = deal_check_value(deal_descriptor_intern(descriptor),
        input, boundary_kind, &context);
    print_check(descriptor, boundary_names[boundary_kind], index_text, kind,
        payload, outcome.error);
}

/* One check input of a persistent tagged datum. */
static deal_check_input datum_input(deal_data datum) {
    deal_check_input input;
    input.presentation = DEAL_PRESENT_DATUM;
    input.unicode_defect = DEAL_UNICODE_MALFORMED;
    input.datum = datum;
    input.class_atom = NULL;
    input.signature = NULL;
    return input;
}

/* One check input of a non-data presentation. */
static deal_check_input plain_input(uint32_t presentation, uint32_t unicode_defect) {
    deal_check_input input;
    input.presentation = presentation;
    input.unicode_defect = unicode_defect;
    input.datum = deal_data_of_undefined();
    input.class_atom = NULL;
    input.signature = NULL;
    return input;
}

static void check_value_matrix(void) {
    /* Every managed intermediate is written into a root slot before the next
     * allocating entry, so a collection at any safepoint keeps it (M4/M5). */
    deal_root_frame_enter(8u);
    uint32_t slot = 0u;
    deal_string *text_string = deal_text_new("alpha", 5u);
    deal_root_frame_write(slot++, text_string);
    deal_table *table_object = deal_container_table_new();
    deal_root_frame_write(slot++, table_object);
    uint32_t number_descriptor = deal_descriptor_reference(
        deal_descriptor_intern("number"));
    uint32_t string_descriptor = deal_descriptor_reference(
        deal_descriptor_intern("string"));
    deal_array *array_object = deal_container_array_new_typed(TEST_LAYOUT_NUMBER,
        number_descriptor, 0u);
    deal_root_frame_write(slot++, array_object);
    deal_array *strings_object = deal_container_array_new_typed(TEST_LAYOUT_NUMBER,
        string_descriptor, 0u);
    deal_root_frame_write(slot++, strings_object);
    deal_array *untyped_object = deal_container_array_new(TEST_LAYOUT_NUMBER, 0u);
    deal_root_frame_write(slot++, untyped_object);
    deal_error *carried_error = deal_error_create("E9001", "carried", NULL);
    deal_root_frame_write(slot++, carried_error);
    deal_error *builtin_error = deal_error_create("E9003", "builtin", NULL);
    deal_root_frame_write(slot++, builtin_error);
    deal_string *function_referent = deal_text_new("f", 1u);
    deal_root_frame_write(slot++, function_referent);
    deal_data text = deal_data_of_reference(DEAL_DATA_STRING, text_string);
    deal_data table = deal_data_of_reference(DEAL_DATA_TABLE, table_object);
    deal_data number = deal_data_of_number(1.5);
    deal_data boolean = deal_data_of_boolean(1);
    deal_data null_value = deal_data_of_null();
    deal_data undefined_value = deal_data_of_undefined();
    deal_data array = deal_data_of_reference(DEAL_DATA_ARRAY, array_object);
    deal_data strings = deal_data_of_reference(DEAL_DATA_ARRAY, strings_object);
    deal_data untyped = deal_data_of_reference(DEAL_DATA_ARRAY, untyped_object);
    deal_data error_datum = deal_data_of_reference(DEAL_DATA_ERROR, builtin_error);
    deal_data function_datum = deal_data_of_reference(DEAL_DATA_FUNCTION,
        function_referent);

    /* The plain kind rows. */
    run_check("number", DEAL_BOUNDARY_TYPE_DESCRIPTOR, 0, "string", "-",
        datum_input(text));
    run_check("string", DEAL_BOUNDARY_TYPE_DESCRIPTOR, 0, "number", "-",
        datum_input(number));
    run_check("boolean", DEAL_BOUNDARY_TYPE_DESCRIPTOR, 0, "number", "-",
        datum_input(number));
    run_check("record", DEAL_BOUNDARY_TYPE_DESCRIPTOR, 0, "array", "-",
        datum_input(array));
    run_check("null", DEAL_BOUNDARY_TYPE_DESCRIPTOR, 0, "undefined", "-",
        datum_input(undefined_value));
    run_check("undefined", DEAL_BOUNDARY_TYPE_DESCRIPTOR, 0, "null", "-",
        datum_input(null_value));
    run_check("null", DEAL_BOUNDARY_TYPE_DESCRIPTOR, 0, "null", "-",
        datum_input(null_value));
    run_check("undefined", DEAL_BOUNDARY_TYPE_DESCRIPTOR, 0, "undefined", "-",
        datum_input(undefined_value));
    run_check("number", DEAL_BOUNDARY_TYPE_DESCRIPTOR, 0, "number", "-",
        datum_input(number));
    run_check("boolean", DEAL_BOUNDARY_TYPE_DESCRIPTOR, 0, "boolean", "-",
        datum_input(boolean));
    run_check("string", DEAL_BOUNDARY_TYPE_DESCRIPTOR, 0, "string", "-",
        datum_input(text));
    run_check("record", DEAL_BOUNDARY_TYPE_DESCRIPTOR, 0, "table", "-",
        datum_input(table));
    /* The absent read is undefined before the check (R2). */
    run_check("number", DEAL_BOUNDARY_TYPE_DESCRIPTOR, 0, "missing", "-",
        plain_input(DEAL_PRESENT_MISSING, 0u));
    run_check("undefined", DEAL_BOUNDARY_TYPE_DESCRIPTOR, 0, "missing", "-",
        plain_input(DEAL_PRESENT_MISSING, 0u));
    run_check("unknown", DEAL_BOUNDARY_TYPE_DESCRIPTOR, 0, "missing", "-",
        plain_input(DEAL_PRESENT_MISSING, 0u));
    /* The closed non-data presentations are never unknown data. */
    run_check("unknown", DEAL_BOUNDARY_TYPE_DESCRIPTOR, 0, "async-operation", "-",
        plain_input(DEAL_PRESENT_ASYNC_OPERATION, 0u));
    run_check("unknown", DEAL_BOUNDARY_TYPE_DESCRIPTOR, 0, "nothing", "-",
        plain_input(DEAL_PRESENT_NOTHING, 0u));
    /* The pinned invalid-unicode arm and the host string inner reasons. */
    run_check("string", DEAL_BOUNDARY_TYPE_DESCRIPTOR, 0, "invalid-unicode",
        "malformed",
        plain_input(DEAL_PRESENT_INVALID_UNICODE, DEAL_UNICODE_MALFORMED));
    run_check("string", DEAL_BOUNDARY_HOST_PARAMETER, 3, "invalid-unicode",
        "surrogate",
        plain_input(DEAL_PRESENT_INVALID_UNICODE, DEAL_UNICODE_SURROGATE));
    run_check("string", DEAL_BOUNDARY_HOST_PARAMETER, 5, "invalid-unicode",
        "malformed",
        plain_input(DEAL_PRESENT_INVALID_UNICODE, DEAL_UNICODE_MALFORMED));
    run_check("string", DEAL_BOUNDARY_HOST_SYNC_RETURN, 0, "invalid-unicode",
        "surrogate",
        plain_input(DEAL_PRESENT_INVALID_UNICODE, DEAL_UNICODE_SURROGATE));
    /* The nullish members and their non-nesting forms. */
    run_check("?string", DEAL_BOUNDARY_TYPE_DESCRIPTOR, 0, "null", "-",
        datum_input(null_value));
    run_check("?string", DEAL_BOUNDARY_TYPE_DESCRIPTOR, 0, "undefined", "-",
        datum_input(undefined_value));
    run_check("!string", DEAL_BOUNDARY_TYPE_DESCRIPTOR, 0, "undefined", "-",
        datum_input(undefined_value));
    run_check("!string", DEAL_BOUNDARY_TYPE_DESCRIPTOR, 0, "null", "-",
        datum_input(null_value));
    run_check("?!number", DEAL_BOUNDARY_TYPE_DESCRIPTOR, 0, "null", "-",
        datum_input(null_value));
    run_check("null|undefined", DEAL_BOUNDARY_TYPE_DESCRIPTOR, 0, "boolean", "-",
        datum_input(boolean));
    run_check("?[number]", DEAL_BOUNDARY_TYPE_DESCRIPTOR, 0, "null", "-",
        datum_input(null_value));
    /* Unknown admission keeps every supported data kind. */
    run_check("unknown", DEAL_BOUNDARY_TYPE_DESCRIPTOR, 0, "table", "-",
        datum_input(table));
    run_check("unknown", DEAL_BOUNDARY_TYPE_DESCRIPTOR, 0, "array", "number",
        datum_input(array));
    run_check("unknown", DEAL_BOUNDARY_TYPE_DESCRIPTOR, 0, "number", "-",
        datum_input(number));
    run_check("unknown", DEAL_BOUNDARY_TYPE_DESCRIPTOR, 0, "boolean", "-",
        datum_input(boolean));
    /* The persistent array element descriptor decides compatibility (R4). */
    run_check("[number]", DEAL_BOUNDARY_TYPE_DESCRIPTOR, 0, "array", "number",
        datum_input(array));
    run_check("[number]", DEAL_BOUNDARY_TYPE_DESCRIPTOR, 0, "array", "string",
        datum_input(strings));
    run_check("[number]", DEAL_BOUNDARY_TYPE_DESCRIPTOR, 0, "array", "-",
        datum_input(untyped));
    run_check("[string]", DEAL_BOUNDARY_TYPE_DESCRIPTOR, 0, "number", "-",
        datum_input(number));
    /* Nominal class identity, the builtin Error class included. */
    deal_check_input user_input = datum_input(
        deal_data_of_reference(DEAL_DATA_CLASS, carried_error));
    user_input.class_atom = class_user;
    run_check(class_user, DEAL_BOUNDARY_TYPE_DESCRIPTOR, 0, "class", class_user,
        user_input);
    deal_check_input other_input = datum_input(
        deal_data_of_reference(DEAL_DATA_CLASS, carried_error));
    other_input.class_atom = class_other;
    run_check(class_user, DEAL_BOUNDARY_TYPE_DESCRIPTOR, 0, "class", class_other,
        other_input);
    deal_check_input error_input = datum_input(error_datum);
    run_check("Error", DEAL_BOUNDARY_TYPE_DESCRIPTOR, 0, "error", "-", error_input);
    run_check(class_user, DEAL_BOUNDARY_TYPE_DESCRIPTOR, 0, "error", "-",
        error_input);
    run_check("number", DEAL_BOUNDARY_TYPE_DESCRIPTOR, 0, "class", class_user,
        user_input);
    /* Function signatures are compared with their carried canonical text. */
    deal_check_input function_input = datum_input(function_datum);
    function_input.signature = "(number)->number";
    run_check("(number)->string", DEAL_BOUNDARY_FUNCTION_SIGNATURE, 0, "function",
        "(number)->number", function_input);
    function_input.signature = "(number)->string";
    run_check("(number)->string", DEAL_BOUNDARY_FUNCTION_SIGNATURE, 0, "function",
        "(number)->string", function_input);
    run_check("(number)->string", DEAL_BOUNDARY_FUNCTION_SIGNATURE, 0, "number", "-",
        datum_input(number));
    /* The host parameter and host return cells. */
    run_check("number", DEAL_BOUNDARY_HOST_PARAMETER, 2, "string", "-",
        datum_input(text));
    run_check("number", DEAL_BOUNDARY_HOST_PARAMETER, 1, "number", "-",
        datum_input(number));
    run_check(class_user, DEAL_BOUNDARY_HOST_PARAMETER, 4, "class", class_other,
        other_input);
    run_check("unknown", DEAL_BOUNDARY_HOST_PARAMETER, 6, "async-operation", "-",
        plain_input(DEAL_PRESENT_ASYNC_OPERATION, 0u));
    run_check("number", DEAL_BOUNDARY_HOST_SYNC_RETURN, 0, "nothing", "-",
        plain_input(DEAL_PRESENT_NOTHING, 0u));
    run_check("string", DEAL_BOUNDARY_HOST_SYNC_RETURN, 0, "string", "-",
        datum_input(text));
    run_check("number", DEAL_BOUNDARY_HOST_SYNC_RETURN, 0, "string", "-",
        datum_input(text));
    run_check("?string", DEAL_BOUNDARY_HOST_SYNC_RETURN, 0, "null", "-",
        datum_input(null_value));
    /* The host cell arms declare CELL_DESCRIPTOR, so their expected field and
     * (for the nothing arm) the {expected} parameter carry the boundary's own
     * declared descriptor text, never the recursed base descriptor (R4/R5). */
    run_check("?string", DEAL_BOUNDARY_HOST_PARAMETER, 2, "number", "-",
        datum_input(number));
    run_check("!string", DEAL_BOUNDARY_HOST_PARAMETER, 2, "number", "-",
        datum_input(number));
    run_check("null|undefined", DEAL_BOUNDARY_HOST_PARAMETER, 3, "number", "-",
        datum_input(number));
    run_check("?!number", DEAL_BOUNDARY_HOST_PARAMETER, 3, "string", "-",
        datum_input(text));
    run_check("?[number]", DEAL_BOUNDARY_HOST_PARAMETER, 3, "string", "-",
        datum_input(text));
    run_check("?@src/app/user.deal/User", DEAL_BOUNDARY_HOST_PARAMETER, 3,
        "class", class_other, other_input);
    run_check("?string", DEAL_BOUNDARY_HOST_PARAMETER, 4, "nothing", "-",
        plain_input(DEAL_PRESENT_NOTHING, 0u));
    run_check("!string", DEAL_BOUNDARY_HOST_PARAMETER, 4, "nothing", "-",
        plain_input(DEAL_PRESENT_NOTHING, 0u));
    run_check("null|undefined", DEAL_BOUNDARY_HOST_PARAMETER, 4, "nothing", "-",
        plain_input(DEAL_PRESENT_NOTHING, 0u));
    run_check("?!number", DEAL_BOUNDARY_HOST_PARAMETER, 5, "nothing", "-",
        plain_input(DEAL_PRESENT_NOTHING, 0u));
    run_check("?[number]", DEAL_BOUNDARY_HOST_PARAMETER, 5, "nothing", "-",
        plain_input(DEAL_PRESENT_NOTHING, 0u));
    run_check("?@src/app/user.deal/User", DEAL_BOUNDARY_HOST_PARAMETER, 5,
        "nothing", "-", plain_input(DEAL_PRESENT_NOTHING, 0u));
    run_check("?string", DEAL_BOUNDARY_HOST_SYNC_RETURN, 0, "number", "-",
        datum_input(number));
    run_check("!string", DEAL_BOUNDARY_HOST_SYNC_RETURN, 0, "number", "-",
        datum_input(number));
    run_check("null|undefined", DEAL_BOUNDARY_HOST_SYNC_RETURN, 0, "number", "-",
        datum_input(number));
    run_check("?!number", DEAL_BOUNDARY_HOST_SYNC_RETURN, 0, "string", "-",
        datum_input(text));
    run_check("?[number]", DEAL_BOUNDARY_HOST_SYNC_RETURN, 0, "string", "-",
        datum_input(text));
    run_check("?@src/app/user.deal/User", DEAL_BOUNDARY_HOST_SYNC_RETURN, 0,
        "class", class_other, other_input);
    run_check("?string", DEAL_BOUNDARY_HOST_SYNC_RETURN, 0, "nothing", "-",
        plain_input(DEAL_PRESENT_NOTHING, 0u));
    run_check("!string", DEAL_BOUNDARY_HOST_SYNC_RETURN, 0, "nothing", "-",
        plain_input(DEAL_PRESENT_NOTHING, 0u));
    run_check("null|undefined", DEAL_BOUNDARY_HOST_SYNC_RETURN, 0, "nothing", "-",
        plain_input(DEAL_PRESENT_NOTHING, 0u));
    run_check("?!number", DEAL_BOUNDARY_HOST_SYNC_RETURN, 0, "nothing", "-",
        plain_input(DEAL_PRESENT_NOTHING, 0u));
    run_check("?[number]", DEAL_BOUNDARY_HOST_SYNC_RETURN, 0, "nothing", "-",
        plain_input(DEAL_PRESENT_NOTHING, 0u));
    run_check("?@src/app/user.deal/User", DEAL_BOUNDARY_HOST_SYNC_RETURN, 0,
        "nothing", "-", plain_input(DEAL_PRESENT_NOTHING, 0u));
    /* The awaited completion cell. */
    run_check("string", DEAL_BOUNDARY_ASYNC_COMPLETION, 0, "number", "-",
        datum_input(number));
    run_check("string", DEAL_BOUNDARY_ASYNC_COMPLETION, 0, "invalid-unicode",
        "malformed",
        plain_input(DEAL_PRESENT_INVALID_UNICODE, DEAL_UNICODE_MALFORMED));
    run_check(class_user, DEAL_BOUNDARY_ASYNC_COMPLETION, 0, "class", class_other,
        other_input);
    run_check("(number)->string", DEAL_BOUNDARY_ASYNC_COMPLETION, 0, "number", "-",
        datum_input(number));
    deal_root_frame_leave();
    check(deal_root_frame_depth() == 0u && deal_root_scope_depth() == 0u,
          "the check entries leave the root depth restored");
}

/* The scalar boundary helpers project the native payload of one datum. */
static void check_scalar_helpers(void) {
    deal_check_context context;
    context.origin = &test_origin;
    context.parameter_index = 0;
    deal_root_frame_enter(1u);
    deal_data number = deal_data_of_number(-2.5);
    deal_outcome_number number_outcome = deal_check_number(&number, &context);
    check(number_outcome.error == NULL && number_outcome.value == -2.5,
          "the number helper projects the binary64 payload");
    deal_string *payload = deal_text_new("payload", 7u);
    deal_root_frame_write(0u, payload);
    deal_data text = deal_data_of_reference(DEAL_DATA_STRING, payload);
    deal_outcome_string string_outcome = deal_check_string(&text, &context);
    check(string_outcome.error == NULL && string_outcome.value == payload,
          "the string helper projects the string referent");
    deal_data boolean = deal_data_of_boolean(1);
    deal_outcome_boolean boolean_outcome = deal_check_boolean(&boolean, &context);
    check(boolean_outcome.error == NULL && boolean_outcome.value == 1u,
          "the boolean helper projects the 0/1 payload");
    deal_outcome_number rejected = deal_check_number(&text, &context);
    const char *code = NULL;
    const char *message = NULL;
    if (rejected.error != NULL) {
        (void)deal_error_code(rejected.error, &code);
        (void)deal_error_message(rejected.error, &message);
    }
    check(rejected.error != NULL && code != NULL && strcmp(code, "E8001") == 0,
          "the number helper publishes the typed-boundary arm");
    check(message != NULL && strcmp(message, "expected number") == 0,
          "the number helper publishes the arm's own message text");
    deal_outcome_string rejected_string = deal_check_string(&number, &context);
    check(rejected_string.error != NULL && rejected_string.value == NULL,
          "the string helper publishes a failure and no value");
    deal_outcome_boolean rejected_boolean = deal_check_boolean(&number, &context);
    check(rejected_boolean.error != NULL,
          "the boolean helper publishes a failure");
    deal_root_frame_leave();
}

/* =========================================================================
 * Class construction
 * ========================================================================= */

static void print_construct(const char *name, deal_error *error, const char *tag,
                            const char *file, int32_t line, int32_t column) {
    if (error == NULL) {
        printf("CONSTRUCT\t%s\t-\t-\t-\t%s\t-\t-\n", name, text_or_absent(tag));
        return;
    }
    const char *code = NULL;
    const char *expected = NULL;
    const char *actual = NULL;
    const char *message = NULL;
    (void)deal_error_code(error, &code);
    (void)deal_error_expected(error, &expected);
    (void)deal_error_actual(error, &actual);
    (void)deal_error_message(error, &message);
    char origin_text[96];
    if (file != NULL) {
        snprintf(origin_text, sizeof(origin_text), "%s:%d:%d", file, (int)line,
            (int)column);
    } else {
        snprintf(origin_text, sizeof(origin_text), "-");
    }
    printf("CONSTRUCT\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n", name, text_or_absent(code),
        text_or_absent(expected), text_or_absent(actual), text_or_absent(tag),
        origin_text, text_or_absent(message));
}

/* One construction attempt: the outcome, its origin and its published tag. */
static void construct_case(const char *name, const deal_class_layout *layout,
                           const deal_class_supplied *supplied,
                           size_t supplied_count, const deal_origin *origin) {
    const char *tag = NULL;
    deal_outcome outcome = deal_class_construct(layout, supplied, supplied_count,
        origin, &tag);
    const char *file = NULL;
    int32_t line = 0;
    int32_t column = 0;
    if (outcome.error != NULL) {
        (void)deal_error_source_file(outcome.error, &file);
        (void)deal_error_line(outcome.error, &line);
        (void)deal_error_column(outcome.error, &column);
    }
    print_construct(name, outcome.error, tag, file, line, column);
}

static void check_class_construction(void) {
    deal_root_frame_enter(3u);
    deal_descriptor *string_type = deal_descriptor_intern("string");
    deal_descriptor *number_type = deal_descriptor_intern("number");
    deal_descriptor *nullable_string = deal_descriptor_intern("?string");
    deal_descriptor *optional_undefined = deal_descriptor_intern("!string");
    const deal_class_field fields[] = {
        { "name", string_type, 0 },
        { "age", number_type, 1 },
        { "nick", nullable_string, 1 },
        { "note", optional_undefined, 1 }
    };
    const deal_class_layout layout = { class_user, fields, 4u };
    const deal_origin name_origin = { "native/boundary.deal", 12, 5 };
    const deal_origin age_origin = { "native/boundary.deal", 13, 5 };
    const deal_origin literal_origin = { "native/boundary.deal", 11, 3 };
    deal_string *name_string = deal_text_new("Ada", 3u);
    deal_root_frame_write(0u, name_string);
    deal_string *age_string = deal_text_new("old", 3u);
    deal_root_frame_write(1u, age_string);
    deal_string *handler_string = deal_text_new("h", 1u);
    deal_root_frame_write(2u, handler_string);
    deal_data name_value = deal_data_of_reference(DEAL_DATA_STRING, name_string);
    deal_data age_value = deal_data_of_number(36.0);
    deal_data number_as_name = deal_data_of_number(7.0);
    deal_data string_as_age = deal_data_of_reference(DEAL_DATA_STRING,
        age_string);
    deal_data undefined_value = deal_data_of_undefined();
    deal_data null_value = deal_data_of_null();

    {
        const deal_class_supplied supplied[] = {
            { "name", datum_input(name_value), name_origin },
            { "age", datum_input(age_value), age_origin }
        };
        construct_case("valid", &layout, supplied, 2u, &literal_origin);
    }
    {
        const deal_class_supplied supplied[] = {
            { "name", datum_input(name_value), name_origin }
        };
        construct_case("optional-absent", &layout, supplied, 1u, &literal_origin);
    }
    {
        const deal_class_supplied supplied[] = {
            { "name", datum_input(name_value), name_origin },
            { "nick", datum_input(null_value), age_origin }
        };
        construct_case("present-null", &layout, supplied, 2u, &literal_origin);
    }
    {
        const deal_class_supplied supplied[] = {
            { "name", datum_input(name_value), name_origin },
            { "nick", datum_input(undefined_value), age_origin }
        };
        construct_case("optional-undefined", &layout, supplied, 2u,
            &literal_origin);
    }
    {
        const deal_class_supplied supplied[] = {
            { "name", datum_input(name_value), name_origin },
            { "note", datum_input(undefined_value), age_origin }
        };
        construct_case("present-undefined", &layout, supplied, 2u,
            &literal_origin);
    }
    {
        const deal_class_supplied supplied[] = {
            { "name", datum_input(name_value), name_origin },
            { "extra", datum_input(name_value), age_origin }
        };
        construct_case("extra-field", &layout, supplied, 2u, &literal_origin);
    }
    {
        /* The extra field precedes every field boundary (R2). */
        const deal_class_supplied supplied[] = {
            { "extra", datum_input(name_value), age_origin },
            { "name", datum_input(number_as_name), name_origin }
        };
        construct_case("extra-first", &layout, supplied, 2u, &literal_origin);
    }
    {
        /* The first declaration-order field decides the failure. */
        const deal_class_supplied supplied[] = {
            { "age", datum_input(string_as_age), age_origin },
            { "name", datum_input(number_as_name), name_origin }
        };
        construct_case("declaration-order", &layout, supplied, 2u,
            &literal_origin);
    }
    {
        /* A field's declared descriptor may itself be a signature. */
        deal_descriptor *signature = deal_descriptor_intern("(number)->string");
        const deal_class_field handler_fields[] = {
            { "handler", signature, 0 }
        };
        const deal_class_layout handler_layout = { class_other, handler_fields, 1u };
        deal_data carried = deal_data_of_reference(DEAL_DATA_FUNCTION,
            handler_string);
        deal_check_input handler = datum_input(carried);
        handler.signature = "(number)->number";
        const deal_class_supplied supplied[] = {
            { "handler", handler, name_origin }
        };
        construct_case("signature-field", &handler_layout, supplied, 1u,
            &literal_origin);
    }
    deal_root_frame_leave();
    check(deal_root_frame_depth() == 0u && deal_root_scope_depth() == 0u,
          "the construction entries leave the root depth restored");
}

/* =========================================================================
 * The arm materialization matrix
 * ========================================================================= */

typedef struct {
    uint32_t arm_id;
    const deal_arm_parameter *parameters;
    size_t parameter_count;
    const char *expected;
    const char *actual;
    const char *cause_code;
} deal_arm_case;

static void print_arm(const deal_arm_case *test, deal_error *error) {
    printf("ARM\t%u\t%u", test->arm_id, (unsigned)test->parameter_count);
    for (size_t index = 0; index < test->parameter_count; index += 1u) {
        printf("\t%s\t%s", test->parameters[index].name,
            test->parameters[index].value);
    }
    if (error == NULL) {
        printf("\t-\t-\t-\t-\n");
        return;
    }
    const char *code = NULL;
    const char *expected = NULL;
    const char *actual = NULL;
    const char *message = NULL;
    (void)deal_error_code(error, &code);
    (void)deal_error_expected(error, &expected);
    (void)deal_error_actual(error, &actual);
    (void)deal_error_message(error, &message);
    printf("\t%s\t%s\t%s\t%s\n", text_or_absent(code), text_or_absent(expected),
        text_or_absent(actual), text_or_absent(message));
}

static void check_arm_matrix(void) {
    static const deal_arm_parameter kind_number[] = { {"kind", "number"} };
    static const deal_arm_parameter kind_table[] = { {"kind", "table"} };
    static const deal_arm_parameter kind_class[] = { {"kind", "class instance"} };
    static const deal_arm_parameter identity[] = {
        {"expected", class_user}, {"actual", class_other} };
    static const deal_arm_parameter element_index[] = { {"oneBasedIndex", "3"} };
    static const deal_arm_parameter signature[] = {
        {"expected", "(number)->string"}, {"actual", "(number)->number"} };
    static const deal_arm_parameter host_parameter[] = {
        {"index", "2"}, {"inner", "expected number"} };
    static const deal_arm_parameter host_nothing[] = { {"expected", "number"} };
    static const deal_arm_parameter host_cell[] = { {"inner", "expected number"} };
    static const deal_arm_parameter completion[] = { {"expected", "string"} };
    static const deal_arm_parameter async_shape[] = { {"actual", "number"} };
    static const deal_arm_parameter load_failed[] = {
        {"module", "hostmod"}, {"reason", "not found"} };
    static const deal_arm_parameter load_module[] = { {"module", "hostmod"} };
    static const deal_arm_parameter load_export[] = {
        {"name", "greet"}, {"module", "hostmod"} };
    static const deal_arm_parameter load_signature[] = {
        {"name", "greet"}, {"module", "hostmod"},
        {"expected", "(string)->string"}, {"actual", "(number)->number"} };
    static const deal_arm_parameter load_identity[] = {
        {"name", "User"}, {"module", "hostmod"},
        {"expected", class_user}, {"actual", class_other} };
    static const deal_arm_parameter load_metadata[] = {
        {"name", "User"}, {"module", "hostmod"}, {"defaults|fields", "fields"} };
    static const deal_arm_parameter extra_field[] = {
        {"field", "extra"}, {"classId", class_user} };
    static const deal_arm_parameter parse_error[] = {
        {"oneBasedByteOffset", "12"}, {"reason", "unexpected token"} };
    static const deal_arm_parameter walk[] = {
        {"fieldPath", "value.name"}, {"actual", "function"} };
    static const deal_arm_parameter stringify[] = { {"actual", "function"} };

    char sqrt_text[DEAL_ARM_NUMBER_TEXT_CAPACITY];
    const char *sqrt_actual = deal_arm_number_text(-4.0, sqrt_text,
        sizeof(sqrt_text));

    const deal_arm_case matrix[] = {
        { DEAL_ARM_ID_TYPED_BOUNDARY_KIND, kind_number, 1u, "number", "string",
          NULL },
        { DEAL_ARM_ID_TYPED_BOUNDARY_KIND, kind_table, 1u, "table", "array",
          NULL },
        { DEAL_ARM_ID_TYPED_BOUNDARY_KIND, kind_class, 1u, "class", class_other,
          NULL },
        { DEAL_ARM_ID_TYPED_BOUNDARY_INVALID_UNICODE, NULL, 0u, "string",
          "invalid-unicode", NULL },
        { DEAL_ARM_ID_CLASS_IDENTITY, identity, 2u, class_user, class_other,
          NULL },
        { DEAL_ARM_ID_ARRAY_ELEMENT_KIND, element_index, 1u, "number", "string",
          "E8001" },
        { DEAL_ARM_ID_ARRAY_READ_BOUNDS, NULL, 0u, NULL, NULL, NULL },
        { DEAL_ARM_ID_ARRAY_WRITE_BOUNDS, NULL, 0u, NULL, NULL, NULL },
        { DEAL_ARM_ID_ARRAY_DELETE_BOUNDS, NULL, 0u, NULL, NULL, NULL },
        { DEAL_ARM_ID_FUNCTION_SIGNATURE_MISMATCH, signature, 2u,
          "(number)->string", "(number)->number", NULL },
        { DEAL_ARM_ID_HOST_PARAMETER_CELL, host_parameter, 2u, "number", "string",
          NULL },
        { DEAL_ARM_ID_HOST_SYNC_RETURN_NOTHING, host_nothing, 1u, "number",
          "nothing", NULL },
        { DEAL_ARM_ID_HOST_SYNC_RETURN_CELL, host_cell, 1u, "number", "string",
          NULL },
        { DEAL_ARM_ID_ASYNC_COMPLETION_KIND, completion, 1u, "string", "number",
          NULL },
        { DEAL_ARM_ID_ASYNC_SHAPE, async_shape, 1u, "async operation", "number",
          NULL },
        { DEAL_ARM_ID_HOST_LOAD_FAILED, load_failed, 2u, NULL, NULL, NULL },
        { DEAL_ARM_ID_HOST_LOAD_RAW_SHAPE, NULL, 0u, "table", "string", NULL },
        { DEAL_ARM_ID_HOST_LOAD_NOT_A_MODULE, load_module, 1u, NULL, NULL, NULL },
        { DEAL_ARM_ID_HOST_LOAD_MISSING_EXPORT, load_export, 2u, NULL, NULL,
          NULL },
        { DEAL_ARM_ID_HOST_LOAD_SIGNATURE_MISMATCH, load_signature, 4u, NULL,
          NULL, NULL },
        { DEAL_ARM_ID_HOST_LOAD_IDENTITY_MISMATCH, load_identity, 4u, NULL, NULL,
          NULL },
        { DEAL_ARM_ID_HOST_LOAD_INVALID_METADATA, load_metadata, 3u, NULL, NULL,
          NULL },
        { DEAL_ARM_ID_CLASS_EXTRA_FIELD, extra_field, 2u, NULL, NULL, NULL },
        { DEAL_ARM_ID_JSON_PARSE_ERROR, parse_error, 2u, NULL, NULL, NULL },
        { DEAL_ARM_ID_JSON_TO_WALK, walk, 2u, NULL, "function", NULL },
        { DEAL_ARM_ID_JSON_STRINGIFY_UNSUPPORTED, stringify, 1u,
          "string, number, boolean, or table", "function", NULL },
        { DEAL_ARM_ID_JSON_TO_WALK_CYCLE, NULL, 0u, NULL, NULL, NULL },
        { DEAL_ARM_ID_SUBSTRING_BOUNDS, NULL, 0u, NULL, NULL, NULL },
        { DEAL_ARM_ID_SQRT_NEGATIVE, NULL, 0u, NULL, sqrt_actual, NULL }
    };
    for (size_t index = 0; index < sizeof(matrix) / sizeof(matrix[0]); index += 1u) {
        deal_error *cause = matrix[index].cause_code == NULL ? NULL
            : deal_error_create(matrix[index].cause_code, "leaf cause", NULL);
        deal_error *error = deal_error_materialize(matrix[index].arm_id,
            matrix[index].parameters, matrix[index].parameter_count,
            matrix[index].expected, matrix[index].actual, &test_origin, cause);
        print_arm(&matrix[index], error);
        const char *cause_code = NULL;
        if (matrix[index].cause_code != NULL) {
            check(deal_error_cause_code(error, &cause_code) == 1
                      && cause_code != NULL
                      && strcmp(cause_code, matrix[index].cause_code) == 0,
                  "an E8003 leaf cause survives the materialization");
        } else {
            check(deal_error_cause_code(error, &cause_code) == 0,
                  "an arm without a cause carries none");
        }
        const char *file = NULL;
        int32_t line = 0;
        int32_t column = 0;
        check(deal_error_source_file(error, &file) == 1 && file != NULL
                  && strcmp(file, test_origin.source_file) == 0,
              "the materialized origin keeps the supplied file text");
        check(deal_error_line(error, &line) == 1 && line == test_origin.line
                  && deal_error_column(error, &column) == 1
                  && column == test_origin.column,
              "the materialized origin keeps the supplied line and column");
        deal_error_snapshot_release(deal_error_snapshot(error));
    }
    check(deal_root_frame_depth() == 0u && deal_root_scope_depth() == 0u,
          "the materialization entries leave the root depth restored");
}

/* The E8002 array-position projections. */
static void check_array_position_arms(void) {
    static const uint32_t arms[] = {
        DEAL_ARM_ID_ARRAY_READ_BOUNDS,
        DEAL_ARM_ID_ARRAY_WRITE_BOUNDS,
        DEAL_ARM_ID_ARRAY_DELETE_BOUNDS
    };
    for (size_t index = 0; index < sizeof(arms) / sizeof(arms[0]); index += 1u) {
        deal_error *error = deal_error_array_position(arms[index], &test_origin);
        const char *code = NULL;
        const char *message = NULL;
        const char *expected = NULL;
        (void)deal_error_code(error, &code);
        (void)deal_error_message(error, &message);
        check(code != NULL && strcmp(code, "E8002") == 0,
              "an array-position rejection keeps the E8002 code");
        check(message != NULL && strcmp(message, "array index out of bounds") == 0,
              "an array-position rejection keeps the arm's own template");
        check(deal_error_expected(error, &expected) == 0,
              "an array-position rejection declares no expected field");
        const deal_arm_case test = { arms[index], NULL, 0u, NULL, NULL, NULL };
        print_arm(&test, error);
    }
}

/* The canonical floating-point text of one numeric arm actual. */
static void check_number_text(void) {
    static const struct {
        double value;
        const char *text;
    } cases[] = {
        { 0.0, "0x0.0p0" },
        { -0.0, "-0x0.0p0" },
        { 1.0, "0x1.0p0" },
        { 3.0, "0x1.8p1" },
        { 0.5, "0x1.0p-1" },
        { -4.0, "-0x1.0p2" },
        { 3.141592653589793, "0x1.921fb54442d18p1" },
        { 65504.0, "0x1.ffcp15" },
        { 2.2250738585072014e-308, "0x1.0p-1022" },
        { 1e-310, "0x0.012688b70e62bp-1022" },
        { 4.9406564584124654e-324, "0x0.0000000000001p-1022" },
        { 1.7976931348623157e308, "0x1.fffffffffffffp1023" }
    };
    for (size_t index = 0; index < sizeof(cases) / sizeof(cases[0]); index += 1u) {
        char buffer[DEAL_ARM_NUMBER_TEXT_CAPACITY];
        const char *text = deal_arm_number_text(cases[index].value, buffer,
            sizeof(buffer));
        check(strcmp(text, cases[index].text) == 0,
              "a finite value spells its canonical hex float");
    }
    char buffer[DEAL_ARM_NUMBER_TEXT_CAPACITY];
    check(strcmp(deal_arm_number_text(INFINITY, buffer, sizeof(buffer)),
                 "Infinity") == 0,
          "positive infinity spells Infinity");
    check(strcmp(deal_arm_number_text(-INFINITY, buffer, sizeof(buffer)),
                 "-Infinity") == 0,
          "negative infinity spells -Infinity");
    check(strcmp(deal_arm_number_text(NAN, buffer, sizeof(buffer)), "NaN") == 0,
          "NaN spells NaN");
}

/* =========================================================================
 * The host-text channel and the admission of a carried Error
 * ========================================================================= */

static void check_failure_admission(void) {
    deal_root_frame_enter(3u);
    deal_call_frame_push("native/boundary.deal#outer@F0");
    deal_error *host = deal_error_host_text("EHOST", "host message", "string",
        NULL);
    const char *code = NULL;
    const char *message = NULL;
    const char *expected = NULL;
    const char *actual = NULL;
    const char *file = NULL;
    int32_t frames = -1;
    (void)deal_error_code(host, &code);
    (void)deal_error_message(host, &message);
    (void)deal_error_expected(host, &expected);
    check(code != NULL && strcmp(code, "EHOST") == 0,
          "host text keeps the host's code");
    check(message != NULL && strcmp(message, "host message") == 0,
          "host text keeps the host's message");
    check(expected != NULL && strcmp(expected, "string") == 0,
          "host text keeps the host's expected field");
    check(deal_error_actual(host, &actual) == 0,
          "an absent host actual stays absent");
    check(deal_error_source_file(host, &file) == 0,
          "host text invents no origin");
    check(deal_error_line(host, NULL) == 0 && deal_error_column(host, NULL) == 0,
          "host text invents no line or column");
    check(deal_error_frame_count(host, &frames) == 1 && frames == 0,
          "host text invents no frames");
    check(deal_error_cause_code(host, &actual) == 0,
          "host text invents no cause");
    deal_root_frame_write(0u, host);
    deal_call_frame_pop();

    deal_call_frame_push("native/boundary.deal#outer@F0");
    deal_call_frame_push("native/boundary.deal#inner@F1");
    deal_error *carried = deal_error_create("E8001", "carried", &test_origin);
    deal_root_frame_write(1u, carried);
    deal_error_set_expected(carried, "number");
    deal_call_frame_pop();
    deal_call_frame_pop();

    /* The carried Error is admitted as the very same object, with its fields,
     * origin, frames and cause untouched. */
    deal_failure_admission relayed;
    relayed.kind = DEAL_FAILURE_DEAL_ERROR;
    relayed.error = carried;
    relayed.code = NULL;
    relayed.message = NULL;
    relayed.expected = NULL;
    relayed.actual = NULL;
    deal_error *admitted = deal_failure_admit(&relayed);
    check(admitted == carried,
          "a DEAL-origin failure is admitted as the identical object");
    (void)deal_error_code(admitted, &code);
    check(code != NULL && strcmp(code, "E8001") == 0,
          "the admitted Error keeps its code");
    (void)deal_error_frame_count(admitted, &frames);
    check(frames == 2, "the admitted Error keeps its own frame snapshot");
    (void)deal_error_expected(admitted, &expected);
    check(expected != NULL && strcmp(expected, "number") == 0,
          "the admitted Error keeps its existing field values");
    char *before = deal_error_snapshot(admitted);
    char *after = deal_error_snapshot(admitted);
    check(strcmp(before, after) == 0, "a repeated snapshot is byte-identical");
    deal_error_snapshot_release(before);
    deal_error_snapshot_release(after);

    /* The host-text channel admits a fresh builtin Error. */
    deal_failure_admission authored;
    authored.kind = DEAL_FAILURE_HOST_TEXT;
    authored.error = NULL;
    authored.code = "EHOST";
    authored.message = "host message";
    authored.expected = NULL;
    authored.actual = NULL;
    deal_error *authored_error = deal_failure_admit(&authored);
    deal_root_frame_write(2u, authored_error);
    (void)deal_error_code(authored_error, &code);
    check(code != NULL && strcmp(code, "EHOST") == 0,
          "the host-text channel materializes the host's code");
    check(deal_error_frame_count(authored_error, &frames) == 1 && frames == 0,
          "the host-text channel materializes no frames");

    /* The Error survives repeated full collections while the root frame holds
     * it, and keeps its fields. */
    deal_gc_collect_full();
    deal_gc_collect_full();
    check(deal_error_code(admitted, &code) == 1 && code != NULL
              && strcmp(code, "E8001") == 0,
          "an admitted Error survives repeated full collections");
    (void)deal_error_message(admitted, &message);
    check(message != NULL && strcmp(message, "carried") == 0,
          "the admitted Error keeps its message across a collection");
    deal_root_frame_leave();
    check(deal_root_frame_depth() == 0u && deal_root_scope_depth() == 0u,
          "the admission entries leave the root depth restored");
}

int main(void) {
    if (deal_arm_table_installed() != NULL) {
        printf("FAIL: no arm table is installed before the start call\n");
        return 1;
    }
    deal_outcome started = deal_project_start();
    if (started.error != NULL) {
        printf("FAIL: the start call carried an error\n");
        return 1;
    }
    deal_layout_install(test_layouts,
        sizeof(test_layouts) / sizeof(test_layouts[0]));
    check_atoms();
    check_value_matrix();
    check_scalar_helpers();
    check_class_construction();
    check_arm_matrix();
    check_array_position_arms();
    check_number_text();
    check_failure_admission();
    printf(failures == 0 ? "boundary ok\n" : "boundary failed\n");
    return failures == 0 ? 0 : 1;
}

#else

/* =========================================================================
 * The closed negatives: one deterministic stderr line and exit 2
 * ========================================================================= */

#if DEAL_BOUNDARY_CASE != 1 && DEAL_BOUNDARY_CASE != 2 \
        && DEAL_BOUNDARY_CASE != 3 && DEAL_BOUNDARY_CASE != 4 \
        && DEAL_BOUNDARY_CASE != 5 && DEAL_BOUNDARY_CASE != 6
static const deal_origin test_origin = { "native/boundary.deal", 41, 7 };
#endif

#if DEAL_BOUNDARY_CASE == 13 || DEAL_BOUNDARY_CASE == 14
static const char *const class_user = "@src/app/user.deal/User";
#endif

#if DEAL_BOUNDARY_CASE == 13 || DEAL_BOUNDARY_CASE == 15 \
        || DEAL_BOUNDARY_CASE == 16
static deal_check_input datum_input(deal_data datum) {
    deal_check_input input;
    input.presentation = DEAL_PRESENT_DATUM;
    input.unicode_defect = DEAL_UNICODE_MALFORMED;
    input.datum = datum;
    input.class_atom = NULL;
    input.signature = NULL;
    return input;
}
#endif

int main(void) {
#if DEAL_BOUNDARY_CASE != 12
    /* Every negative but the missing-table one runs after the module's start
     * call installed the emitted arm table. */
    (void)deal_project_start();
#endif
#if DEAL_BOUNDARY_CASE == 1
    (void)deal_descriptor_intern("(void)->number");
#elif DEAL_BOUNDARY_CASE == 2
    (void)deal_descriptor_intern("[void]");
#elif DEAL_BOUNDARY_CASE == 3
    (void)deal_descriptor_intern("?unknown");
#elif DEAL_BOUNDARY_CASE == 4
    (void)deal_descriptor_intern("??number");
#elif DEAL_BOUNDARY_CASE == 5
    (void)deal_descriptor_intern("@User");
#elif DEAL_BOUNDARY_CASE == 6
    (void)deal_descriptor_intern("number[]");
#elif DEAL_BOUNDARY_CASE == 7
    (void)deal_error_materialize(99u, NULL, 0u, NULL, NULL, &test_origin, NULL);
#elif DEAL_BOUNDARY_CASE == 8
    (void)deal_error_materialize(DEAL_ARM_ID_HOST_STRING_INVALID_UTF8, NULL, 0u,
        NULL, NULL, &test_origin, NULL);
#elif DEAL_BOUNDARY_CASE == 9
    (void)deal_error_materialize(DEAL_ARM_ID_TYPED_BOUNDARY_KIND,
        &(const deal_arm_parameter){ "kind", "number" }, 1u, NULL, NULL,
        &test_origin, NULL);
#elif DEAL_BOUNDARY_CASE == 10
    (void)deal_error_materialize(DEAL_ARM_ID_TYPED_BOUNDARY_KIND, NULL, 0u,
        "number", "string", &test_origin, NULL);
#elif DEAL_BOUNDARY_CASE == 11
    (void)deal_error_materialize(DEAL_ARM_ID_TYPED_BOUNDARY_KIND,
        &(const deal_arm_parameter){ "unexpected", "number" }, 1u, "number",
        "string", &test_origin, NULL);
#elif DEAL_BOUNDARY_CASE == 12
    (void)deal_error_materialize(DEAL_ARM_ID_ARRAY_READ_BOUNDS, NULL, 0u, NULL,
        NULL, &test_origin, NULL);
#elif DEAL_BOUNDARY_CASE == 13
    {
        deal_descriptor *type = deal_descriptor_intern("string");
        const deal_class_field fields[] = { { "name", type, 0 } };
        const deal_class_layout layout = { class_user, fields, 1u };
        deal_data value = deal_data_of_reference(DEAL_DATA_STRING,
            deal_text_new("x", 1u));
        const deal_class_supplied supplied[] = {
            { "name", datum_input(value), test_origin },
            { "name", datum_input(value), test_origin }
        };
        const char *tag = NULL;
        (void)deal_class_construct(&layout, supplied, 2u, &test_origin, &tag);
    }
#elif DEAL_BOUNDARY_CASE == 14
    {
        deal_descriptor *type = deal_descriptor_intern("string");
        const deal_class_field fields[] = { { "name", type, 0 } };
        const deal_class_layout layout = { class_user, fields, 1u };
        const char *tag = NULL;
        (void)deal_class_construct(&layout, NULL, 0u, &test_origin, &tag);
    }
#elif DEAL_BOUNDARY_CASE == 15
    {
        deal_check_context context = { &test_origin, 0 };
        (void)deal_check_value(deal_descriptor_intern("(number)->string"),
            datum_input(deal_data_of_number(1.0)),
            DEAL_BOUNDARY_TYPE_DESCRIPTOR, &context);
    }
#elif DEAL_BOUNDARY_CASE == 16
    {
        deal_check_context context = { &test_origin, 0 };
        (void)deal_check_value(deal_descriptor_intern("number"),
            datum_input(deal_data_of_number(1.0)),
            DEAL_BOUNDARY_HOST_PARAMETER, &context);
    }
#elif DEAL_BOUNDARY_CASE == 17
    (void)deal_error_array_position(DEAL_ARM_ID_TYPED_BOUNDARY_KIND, &test_origin);
#elif DEAL_BOUNDARY_CASE == 18
    {
        deal_error *carried = deal_error_create("E8001", "carried", &test_origin);
        deal_failure_admission admission;
        admission.kind = DEAL_FAILURE_DEAL_ERROR;
        admission.error = carried;
        admission.code = "EHOST";
        admission.message = "host message";
        admission.expected = NULL;
        admission.actual = NULL;
        (void)deal_failure_admit(&admission);
    }
#else
#error "unknown DEAL_BOUNDARY_CASE"
#endif
    printf("unreachable\n");
    return 0;
}

#endif
