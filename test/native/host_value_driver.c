/*
 * test/native/host_value_driver.c — the host value surface, the handle table
 * and the DEAL-origin failure channel (`llvm-native-host-abi-and-async` H2, H3,
 * `llvm-native-runtime-value-model` R11).
 *
 * The driver registers one host module and drives every case through the one
 * public entry a host and the emitted module share: `deal_host_load` answers the
 * validated binding and `deal_host_invoke_sync` runs one crossing. Every
 * observation is made inside the crossing by the registered function, which
 * answers a `deal_host_result`; the driver asserts the closed statuses and the
 * exact value the crossing admits.
 *
 * Cases:
 *
 *   0  construction and access: a record with a number, a present-`undefined`
 *      and a present-`null` member, a typed array with two slots naming one
 *      host-built instance, and the `same` relation of the two slots;
 *   1  handles: `deal_retain` roots a constructed record, `deal_handle_value`
 *      re-validates it against its descriptor and `deal_release` removes the
 *      root, after which both `deal_handle_value` and `deal_release` answer
 *      `DEAL_STATUS_STALE_HANDLE`;
 *   2  the DEAL-origin failure relay: one `deal_error` is relayed through
 *      `deal_host_relay_error`, and the crossing admits that very Error (the
 *      driver observes its own code and message);
 *   3  the discard path: the same token is discarded with
 *      `deal_failure_discard`, so the host's own value propagates and no token
 *      stays open;
 *   4  neither relay nor discard: the crossing reports the deterministic
 *      protocol violation (one stderr line, exit 2) before the return value is
 *      checked.
 *   5  an untyped array carrier built from the impossible length 2^60: the
 *      slot product (2^60 slots of a 16-byte slot record) cannot exist, so the
 *      construction is the deterministic R10 resource fault (one stderr line,
 *      exit 2) before any storage is published;
 *   6  the same impossible length through `deal_array_new_typed`, so the
 *      checked size precedes the persistent element descriptor's use too.
 *   7  the function-valued positions of the value surface (H2/H4/R4): a
 *      retained function value re-validated against its own canonical function
 *      descriptor, a mismatched signature and a mismatched kind answering the
 *      closed `kind` status, the checked value applying through the boxed thunk,
 *      a typed array whose persistent element descriptor is that function type
 *      materializing and accepting a view store of a declared function element
 *      (a wrong-kind and a wrong-signature element rejected with `kind`), all
 *      without an invariant fault.
 */
#include "deal/native/host.h"
#include "deal/native/error.h"
#include "deal/native/entry.h"
#include "deal/native/platform.h"

#include <string.h>

#ifndef DEAL_HOST_VALUE_CASE
#define DEAL_HOST_VALUE_CASE 0
#endif

static const deal_origin probe_origin = {"host_value_driver.c", 1, 1};

/* The driver's own failure signal: a host-authored failure carrying the closed
 * status name of a rejected observation. */
static deal_host_result fail(const char *message) {
    deal_host_result result;
    result.failed = 1;
    result.failure.kind = DEAL_FAILURE_HOST_TEXT;
    result.failure.code = "E_PROBE";
    result.failure.message = message;
    result.failure.expected = NULL;
    result.failure.actual = NULL;
    result.failure.error = 0;
    result.value.slot = 0;
    result.value.generation = 0;
    return result;
}

static deal_host_result number_result(double value) {
    deal_host_result result;
    result.failed = 0;
    result.failure.kind = DEAL_FAILURE_HOST_TEXT;
    result.failure.code = NULL;
    result.failure.message = NULL;
    result.failure.expected = NULL;
    result.failure.actual = NULL;
    result.failure.error = 0;
    if (deal_number(value, &result.value) != DEAL_OK) {
        return fail("number construction failed");
    }
    return result;
}

/* -- case 0: construction and access ------------------------------------- */

static deal_host_result construct_case(const deal_value *args, size_t argc) {
    (void)args;
    (void)argc;
    deal_value record;
    deal_value undefined_value;
    deal_value null_value;
    deal_value total;
    if (deal_table_new(&record) != DEAL_OK
            || deal_number(42.0, &total) != DEAL_OK
            || deal_undefined(&undefined_value) != DEAL_OK
            || deal_null(&null_value) != DEAL_OK) {
        return fail("record construction failed");
    }
    if (deal_table_set(record, "total", 5, total) != DEAL_OK
            || deal_table_set(record, "maybe", 5, undefined_value) != DEAL_OK
            || deal_table_set(record, "nothing", 7, null_value) != DEAL_OK) {
        return fail("record member store failed");
    }
    deal_value read;
    double number = 0.0;
    deal_value_kind kind = DEAL_KIND_NULL;
    if (deal_table_get(record, "total", 5, &read) != DEAL_OK
            || deal_as_number(read, &number) != DEAL_OK || number != 42.0) {
        return fail("the number member did not round-trip");
    }
    if (deal_table_get(record, "maybe", 5, &read) != DEAL_OK
            || deal_kind_of(read, &kind) != DEAL_OK
            || kind != DEAL_KIND_UNDEFINED) {
        return fail("the present-undefined member was not preserved");
    }
    if (deal_table_get(record, "nothing", 7, &read) != DEAL_OK
            || deal_kind_of(read, &kind) != DEAL_OK
            || kind != DEAL_KIND_NULL) {
        return fail("the present-null member was not preserved");
    }
    int present = 1;
    if (deal_table_has(record, "absent", 6, &present) != DEAL_OK || present != 0) {
        return fail("the absent member is not absent");
    }
    /* Pair spellings and lone units are equal data before and after graph
     * materialization. Malformed transport never enters a key decoder. */
    static const char pair[] = "\xF0\x9F\x98\x80";
    static const char units[] = "\xED\xA0\xBD\xED\xB8\x80";
    static const char lone[] = "\xED\xA0\x80";
    static const char malformed[] = "\xED\xA0";
    deal_value pair_value;
    deal_value unit_value;
    deal_value lone_value;
    int equal = 0;
    if (deal_string_new(pair, 4u, &pair_value) != DEAL_OK
            || deal_string_new(units, 6u, &unit_value) != DEAL_OK
            || deal_string_new(lone, 3u, &lone_value) != DEAL_OK
            || deal_same(pair_value, unit_value, &equal) != DEAL_OK || !equal
            || deal_table_set(record, pair, 4u, total) != DEAL_OK
            || deal_table_get(record, units, 6u, &read) != DEAL_OK
            || deal_as_number(read, &number) != DEAL_OK || number != 42.0
            || deal_table_set(record, units, 6u, null_value) != DEAL_OK
            || deal_table_get(record, pair, 4u, &read) != DEAL_OK
            || deal_kind_of(read, &kind) != DEAL_OK || kind != DEAL_KIND_NULL
            || deal_table_has(record, units, 6u, &present) != DEAL_OK || !present
            || deal_table_delete(record, units, 6u) != DEAL_OK
            || deal_table_has(record, pair, 4u, &present) != DEAL_OK || present
            || deal_table_set(record, pair, 4u, pair_value) != DEAL_OK
            || deal_table_set(record, lone, 3u, lone_value) != DEAL_OK
            || deal_table_get(record, malformed, 2u, &read) != DEAL_STATUS_KIND
            || deal_table_set(record, malformed, 2u, total) != DEAL_STATUS_KIND
            || deal_table_has(record, malformed, 2u, &present) != DEAL_STATUS_KIND
            || deal_table_delete(record, malformed, 2u) != DEAL_STATUS_KIND) {
        return fail("UTF-16 host strings or boxed keys differ");
    }
    /* A fresh key written through a managed view must survive the entry
     * allocation safepoint. Use scalar values to isolate key lifetime from
     * graph materialization, and check replacement and deletion too. */
    deal_handle record_handle = 0;
    deal_value view;
    if (deal_retain(record, &record_handle) != DEAL_OK
            || deal_handle_value(record_handle, "record", &view) != DEAL_OK
            || deal_table_set(view, "fresh", 5, total) != DEAL_OK
            || deal_table_set(view, "second", 6, total) != DEAL_OK
            || deal_table_get(view, "fresh", 5, &read) != DEAL_OK
            || deal_as_number(read, &number) != DEAL_OK || number != 42.0
            || deal_table_get(view, "second", 6, &read) != DEAL_OK
            || deal_as_number(read, &number) != DEAL_OK || number != 42.0
            || deal_table_set(view, "fresh", 5, null_value) != DEAL_OK
            || deal_table_get(view, "fresh", 5, &read) != DEAL_OK
            || deal_kind_of(read, &kind) != DEAL_OK || kind != DEAL_KIND_NULL
            || deal_table_delete(view, "fresh", 5) != DEAL_OK
            || deal_table_has(view, "fresh", 5, &present) != DEAL_OK
            || present != 0 || deal_release(record_handle) != DEAL_OK) {
        return fail("managed record keys did not survive publication");
    }
    if (deal_table_get(view, units, 6u, &read) != DEAL_OK
            || deal_same(read, unit_value, &equal) != DEAL_OK || !equal
            || deal_table_get(view, lone, 3u, &read) != DEAL_OK
            || deal_same(read, lone_value, &equal) != DEAL_OK || !equal
            || deal_table_set(view, units, 6u, total) != DEAL_OK
            || deal_table_get(view, pair, 4u, &read) != DEAL_OK
            || deal_as_number(read, &number) != DEAL_OK || number != 42.0
            || deal_table_delete(view, pair, 4u) != DEAL_OK
            || deal_table_has(view, units, 6u, &present) != DEAL_OK || present) {
        return fail("UTF-16 managed keys differ from host keys");
    }
    /* A typed array with two slots naming one host-built record. */
    deal_value array;
    deal_value element;
    if (deal_array_new_typed(2, "@$external/host/value/Box", &array) != DEAL_OK
            || deal_number(7.0, &element) != DEAL_OK) {
        return fail("array construction failed");
    }
    if (deal_array_set(array, 0, element) != DEAL_OK
            || deal_array_set(array, 1, element) != DEAL_OK) {
        return fail("array slot store failed");
    }
    deal_value first;
    deal_value second;
    int same = 0;
    if (deal_array_get(array, 0, &first) != DEAL_OK
            || deal_array_get(array, 1, &second) != DEAL_OK
            || deal_same(first, second, &same) != DEAL_OK || same != 1) {
        return fail("two slots naming one carrier are not the same value");
    }
    int64_t length = 0;
    if (deal_array_length(array, &length) != DEAL_OK || length != 2) {
        return fail("the array length is not exact");
    }
    /* A status is a host-boundary signal, never a DEAL error. */
    if (deal_array_get(array, 2, &first) != DEAL_STATUS_BOUNDS) {
        return fail("an out-of-range read is not a bounds status");
    }
    if (deal_table_get(array, "total", 5, &first) != DEAL_STATUS_KIND) {
        return fail("a record access on an array is not a kind status");
    }
    return number_result(1.0);
}

/* -- case 1: handles ---------------------------------------------------- */

static deal_host_result handle_case(const deal_value *args, size_t argc) {
    (void)args;
    (void)argc;
    deal_value record;
    if (deal_table_new(&record) != DEAL_OK) {
        return fail("record construction failed");
    }
    deal_value total;
    if (deal_number(11.0, &total) != DEAL_OK
            || deal_table_set(record, "total", 5, total) != DEAL_OK) {
        return fail("record member store failed");
    }
    deal_handle handle = 0;
    if (deal_retain(record, &handle) != DEAL_OK || handle == 0) {
        return fail("retain did not answer a live handle");
    }
    deal_value restored;
    if (deal_handle_value(handle, "record", &restored) != DEAL_OK) {
        return fail("the handle did not resolve in the current crossing");
    }
    deal_value_kind kind = DEAL_KIND_NULL;
    if (deal_kind_of(restored, &kind) != DEAL_OK || kind != DEAL_KIND_TABLE) {
        return fail("the retained value is not the record it rooted");
    }
    /* Two resolutions of one handle name one managed value, so the host-visible
     * `===` holds for them. */
    deal_value again;
    int same = 0;
    if (deal_handle_value(handle, "record", &again) != DEAL_OK
            || deal_same(restored, again, &same) != DEAL_OK || same != 1) {
        return fail("the handle did not preserve the value's identity");
    }
    if (deal_handle_value(handle, "number", &restored) != DEAL_STATUS_KIND) {
        return fail("a mismatched descriptor is not a kind status");
    }
    if (deal_release(handle) != DEAL_OK) {
        return fail("release did not remove the root");
    }
    if (deal_handle_value(handle, "record", &restored)
            != DEAL_STATUS_STALE_HANDLE) {
        return fail("a released handle is not stale");
    }
    if (deal_release(handle) != DEAL_STATUS_STALE_HANDLE) {
        return fail("releasing a released handle is not a stale status");
    }
    if (deal_handle_value(handle + 1, "record", &restored)
            != DEAL_STATUS_STALE_HANDLE) {
        return fail("an unknown handle is not a stale status");
    }
    return number_result(1.0);
}

#if DEAL_HOST_VALUE_CASE == 2 || DEAL_HOST_VALUE_CASE == 3 || DEAL_HOST_VALUE_CASE == 4
/* One distinguishable DEAL Error the relay cases transport. */
static deal_error *relay_error(void) {
    return deal_error_create("E_RELAY", "the relayed error", &probe_origin);
}

#if DEAL_HOST_VALUE_CASE == 2
static deal_host_result relay_case(const deal_value *args, size_t argc) {
    (void)args;
    (void)argc;
    deal_error *error = relay_error();
    deal_host_result result;
    if (deal_host_relay_error(error, &result) != DEAL_OK) {
        return fail("the relay entry rejected the error");
    }
    return result;
}
#endif

#if DEAL_HOST_VALUE_CASE == 4
/* The host issues a DEAL-origin failure token and then ignores the record,
 * returning its own value: neither relay nor discard (H2). */
static deal_host_result unconsumed_case(const deal_value *args, size_t argc) {
    (void)args;
    (void)argc;
    deal_error *error = relay_error();
    deal_host_result ignored;
    if (deal_host_relay_error(error, &ignored) != DEAL_OK) {
        return fail("the relay entry rejected the error");
    }
    return number_result(9.0);
}
#endif

#if DEAL_HOST_VALUE_CASE == 3
static deal_host_result discard_case(const deal_value *args, size_t argc) {
    (void)args;
    (void)argc;
    deal_error *error = relay_error();
    deal_host_result result;
    if (deal_host_relay_error(error, &result) != DEAL_OK) {
        return fail("the relay entry rejected the error");
    }
    if (deal_failure_discard(result.failure.error) != DEAL_OK) {
        return fail("the discard entry rejected the token");
    }
    if (deal_failure_discard(result.failure.error) != DEAL_STATUS_PROTOCOL) {
        return fail("a consumed token is not a protocol status");
    }
    return number_result(2.0);
}
#endif
#endif

#if DEAL_HOST_VALUE_CASE == 7
/* One host function value the surface cases carry; it answers its argument plus
 * forty, so an applied value is distinguishable from its argument. */
static deal_host_result inner_entry(const deal_value *args, size_t argc) {
    double argument = 0.0;
    if (argc != 1 || deal_as_number(args[0], &argument) != DEAL_OK) {
        return fail("the host function's argument");
    }
    return number_result(argument + 40.0);
}

/* The declared function positions of the value surface. Every descriptor here is
 * a canonical function descriptor, so every check belongs to the
 * `FUNCTION_SIGNATURE` boundary; reaching the typed boundary's invariant fault
 * is the defect under test, and it terminates the process (never a status). */
static deal_host_result function_surface_case(const deal_value *args,
                                             size_t argc) {
    (void)args;
    (void)argc;
    /* An executable raw host callable is not a wrapped DEAL function. It is
     * classified as function but fails data/signature admission without ever
     * invoking its entry or publishing a retained/aggregate identity. */
    deal_value raw;
    if (deal_function_raw(NULL, &raw) != DEAL_STATUS_PROTOCOL
            || deal_function_raw(inner_entry, &raw) != DEAL_OK) {
        return fail("raw function construction statuses");
    }
    deal_value_kind raw_kind = DEAL_KIND_NULL;
    const char *raw_signature = "unchanged";
    deal_handle raw_handle = 0;
    deal_host_result raw_result;
    if (deal_kind_of(raw, &raw_kind) != DEAL_OK || raw_kind != DEAL_KIND_FUNCTION
            || deal_function_signature(raw, &raw_signature) != DEAL_STATUS_KIND
            || strcmp(raw_signature, "unchanged") != 0
            || deal_retain(raw, &raw_handle) != DEAL_STATUS_KIND || raw_handle != 0
            || deal_apply(raw, NULL, 0u, &raw_result) != DEAL_STATUS_KIND) {
        return fail("raw callable must not become a DEAL function");
    }
    deal_check_input raw_input;
    deal_error *raw_cause = NULL;
    if (deal_host_value_presentation(raw, &raw_input, &raw_cause) != DEAL_OK
            || raw_cause != NULL || raw_input.presentation != DEAL_PRESENT_RAW_FUNCTION
            || strcmp(deal_check_token(&raw_input), "function") != 0) {
        return fail("raw callable presentation");
    }
    deal_check_context raw_context = {&probe_origin, 0};
    const char *raw_descriptors[] = {"unknown", "(number)->number", "?(number)->number"};
    for (size_t index = 0u; index < sizeof raw_descriptors / sizeof raw_descriptors[0]; index++) {
        deal_outcome rejected = deal_check_value(deal_descriptor_intern(raw_descriptors[index]),
            raw_input, DEAL_BOUNDARY_HOST_SYNC_RETURN, &raw_context);
        const char *code = NULL, *actual = NULL, *expected = NULL, *message = NULL;
        if (rejected.error == NULL
                || !deal_error_code(rejected.error, &code) || strcmp(code, "E8010") != 0
                || !deal_error_actual(rejected.error, &actual) || strcmp(actual, "function") != 0
                || !deal_error_expected(rejected.error, &expected) || strcmp(expected, raw_descriptors[index]) != 0
                || !deal_error_message(rejected.error, &message) || strcmp(message, index == 0u
                    ? "return value 1 type mismatch: expected unknown"
                    : "return value 1 type mismatch: expected function") != 0) {
            return fail("raw callable exact rejection");
        }
    }
    static const unsigned char opaque_identity = 0u, other_identity = 0u;
    deal_value opaque, opaque_alias, opaque_other;
    int opaque_same = 0;
    deal_check_input opaque_input;
    deal_error *opaque_cause = NULL;
    if (deal_opaque(NULL, &opaque) != DEAL_STATUS_PROTOCOL
            || deal_opaque(&opaque_identity, &opaque) != DEAL_OK
            || deal_opaque(&opaque_identity, &opaque_alias) != DEAL_OK
            || deal_opaque(&other_identity, &opaque_other) != DEAL_OK
            || deal_same(opaque, opaque_alias, &opaque_same) != DEAL_OK || !opaque_same
            || deal_same(opaque, opaque_other, &opaque_same) != DEAL_OK || opaque_same
            || deal_kind_of(opaque, &raw_kind) != DEAL_OK || raw_kind != DEAL_KIND_TABLE
            || deal_retain(opaque, &raw_handle) != DEAL_STATUS_KIND || raw_handle != 0
            || deal_host_value_presentation(opaque, &opaque_input, &opaque_cause) != DEAL_OK
            || opaque_cause != NULL || opaque_input.presentation != DEAL_PRESENT_OPAQUE) {
        return fail("opaque identity and nondata statuses");
    }
    const char *opaque_descriptors[] = {"unknown", "record", "?(number)->number"};
    for (size_t index = 0u; index < sizeof opaque_descriptors / sizeof opaque_descriptors[0]; index++) {
        deal_outcome rejected = deal_check_value(deal_descriptor_intern(opaque_descriptors[index]),
            opaque_input, DEAL_BOUNDARY_HOST_SYNC_RETURN, &raw_context);
        const char *code = NULL, *actual = NULL, *expected = NULL, *message = NULL;
        const char *messages[] = {"return value 1 type mismatch: expected unknown",
            "return value 1 type mismatch: expected table", "return value 1 type mismatch: expected function"};
        if (rejected.error == NULL || !deal_error_code(rejected.error, &code)
                || strcmp(code, "E8010") != 0 || !deal_error_actual(rejected.error, &actual)
                || strcmp(actual, "table") != 0 || !deal_error_expected(rejected.error, &expected)
                || strcmp(expected, opaque_descriptors[index]) != 0
                || !deal_error_message(rejected.error, &message) || strcmp(message, messages[index]) != 0) {
            return fail("opaque exact rejection");
        }
    }
    /* The precise managed storage of a hostile retained leaf still carries a
     * nondata presentation. Never reinterpret it as a table or DEAL closure. */
    deal_root_scope_push();
    void *opaque_storage = deal_alloc(DEAL_LAYOUT_HOST_OPAQUE, sizeof(void *));
    deal_root_scope_hold(opaque_storage);
    void *raw_storage = deal_alloc(DEAL_LAYOUT_HOST_RAW_FUNCTION, sizeof(deal_host_function));
    deal_root_scope_hold(raw_storage);
    const void *view_identity = &opaque_identity;
    deal_host_function raw_entry = inner_entry;
    memcpy(opaque_storage, &view_identity, sizeof(view_identity));
    memcpy(raw_storage, &raw_entry, sizeof(raw_entry));
    deal_value opaque_view, raw_view, ignored_value;
    int ignored_presence = 0;
    if (deal_value_crossing_issue(deal_value_crossing_active(), opaque_storage, &opaque_view) != DEAL_OK
            || deal_value_crossing_issue(deal_value_crossing_active(), raw_storage, &raw_view) != DEAL_OK
            || deal_kind_of(opaque_view, &raw_kind) != DEAL_OK || raw_kind != DEAL_KIND_TABLE
            || deal_same(opaque_view, opaque, &opaque_same) != DEAL_OK || !opaque_same
            || deal_same(opaque_alias, opaque_view, &opaque_same) != DEAL_OK || !opaque_same
            || deal_same(opaque_view, opaque_other, &opaque_same) != DEAL_OK || opaque_same
            || deal_table_get(opaque_view, "x", 1u, &ignored_value) != DEAL_STATUS_KIND
            || deal_table_set(opaque_view, "x", 1u, raw) != DEAL_STATUS_KIND
            || deal_table_has(opaque_view, "x", 1u, &ignored_presence) != DEAL_STATUS_KIND
            || deal_table_delete(opaque_view, "x", 1u) != DEAL_STATUS_KIND
            || deal_retain(opaque_view, &raw_handle) != DEAL_STATUS_KIND || raw_handle != 0
            || deal_kind_of(raw_view, &raw_kind) != DEAL_OK || raw_kind != DEAL_KIND_FUNCTION
            || deal_function_signature(raw_view, &raw_signature) != DEAL_STATUS_KIND
            || strcmp(raw_signature, "unchanged") != 0
            || deal_apply(raw_view, NULL, 0u, &raw_result) != DEAL_STATUS_KIND
            || deal_retain(raw_view, &raw_handle) != DEAL_STATUS_KIND || raw_handle != 0) {
        return fail("managed nondata views retain closed statuses");
    }
    deal_check_input managed_nondata;
    deal_error *managed_cause = NULL;
    if (deal_host_value_presentation(raw_view, &managed_nondata, &managed_cause) != DEAL_OK
            || managed_cause != NULL || managed_nondata.presentation != DEAL_PRESENT_RAW_FUNCTION
            || deal_host_value_presentation(opaque_view, &managed_nondata, &managed_cause) != DEAL_OK
            || managed_cause != NULL || managed_nondata.presentation != DEAL_PRESENT_OPAQUE) {
        return fail("managed nondata views retain closed presentations");
    }
    /* The JSON entry and its nested positions must classify these private
     * layouts before ordinary function omission or Record traversal. A raw
     * callable is not a signed DEAL function; an opaque object is no Record. */
    deal_data nondata[] = {
        deal_data_of_reference(DEAL_DATA_FUNCTION, raw_storage),
        deal_data_of_reference(DEAL_DATA_TABLE, opaque_storage)
    };
    for (size_t kind = 0u; kind < 2u; kind++) {
        deal_array *json_array = deal_container_array_new_typed(
            DEAL_LAYOUT_ELEMENT_DATUM,
            deal_descriptor_reference(deal_descriptor_intern("unknown")), 0u);
        deal_root_scope_hold(json_array);
        deal_container_array_append(json_array, &nondata[kind]);
        deal_table *json_record = deal_container_table_new();
        deal_root_scope_hold(json_record);
        deal_string *key = deal_text_new("unread", 6u);
        deal_root_scope_hold(key);
        deal_container_table_write(json_record, key, &nondata[kind]);
        /* A host-retained reference slot must preserve the same private
         * presentation as datum storage, never infer signed function/Record
         * authority from its persistent descriptor. */
        deal_array *json_references = deal_container_array_new_typed(
            DEAL_LAYOUT_ELEMENT_REFERENCE,
            deal_descriptor_reference(deal_descriptor_intern(
                kind == 0u ? "(number)->number" : "record")), 0u);
        deal_root_scope_hold(json_references);
        void *json_reference = nondata[kind].reference;
        deal_container_array_append(json_references, &json_reference);
        deal_data positions[] = {nondata[kind],
            deal_data_of_reference(DEAL_DATA_ARRAY, json_array),
            deal_data_of_reference(DEAL_DATA_TABLE, json_record),
            deal_data_of_reference(DEAL_DATA_ARRAY, json_references)};
        for (size_t position = 0u; position < 4u; position++) {
            deal_outcome_data encoded = deal_json_stringify(positions[position], &probe_origin);
            const char *code = NULL, *actual = NULL, *expected = NULL, *message = NULL;
            if (encoded.error == NULL || !deal_error_code(encoded.error, &code)
                    || strcmp(code, "E8001") != 0
                    || !deal_error_actual(encoded.error, &actual)
                    || strcmp(actual, kind == 0u ? "function" : "table") != 0
                    || !deal_error_expected(encoded.error, &expected)
                    || strcmp(expected, "string, number, boolean, or table") != 0
                    || !deal_error_message(encoded.error, &message)
                    || strcmp(message, kind == 0u
                        ? "unsupported type for JSON encoding: function"
                        : "unsupported type for JSON encoding: table") != 0) {
                return fail("JSON must reject private nondata before omission or traversal");
            }
        }
        if (*(void *const *)deal_container_array_element(json_references, 0u)
                != nondata[kind].reference)
            return fail("JSON rejection preserves private reference storage");
        void *null_reference = (void *)deal_null_singleton;
        deal_container_array_store(json_references, 0u, &null_reference);
        deal_outcome_data reference_retry = deal_json_stringify(positions[3], &probe_origin);
        if (reference_retry.error != NULL || reference_retry.value.tag != DEAL_DATA_STRING
                || deal_text_byte_length(reference_retry.value.reference) != 6u
                || memcmp(deal_text_bytes(reference_retry.value.reference), "[null]", 6u) != 0)
            return fail("JSON repaired private reference array must encode on the same identity");
        deal_data repaired = deal_data_of_number(7.0);
        deal_container_array_store(json_array, 0u, &repaired);
        deal_container_table_write(json_record, key, &repaired);
        for (size_t position = 1u; position < 3u; position++) {
            deal_outcome_data encoded = deal_json_stringify(positions[position], &probe_origin);
            if (encoded.error != NULL || encoded.value.tag != DEAL_DATA_STRING)
                return fail("JSON repaired aggregate must encode");
            deal_root_scope_hold(encoded.value.reference);
            deal_outcome_data decoded = deal_json_parse(encoded.value.reference, &probe_origin);
            if (decoded.error != NULL || decoded.value.tag != positions[position].tag)
                return fail("JSON repaired aggregate must decode structurally");
            deal_data leaf;
            if (position == 1u) {
                if (deal_container_array_count(decoded.value.reference) != 1u)
                    return fail("JSON repaired array count");
                leaf = *(const deal_data *)deal_container_array_element(decoded.value.reference, 0u);
            } else {
                if (deal_container_table_count(decoded.value.reference) != 1u)
                    return fail("JSON repaired Record count");
                leaf = deal_container_table_read(decoded.value.reference, key);
            }
            if (leaf.tag != DEAL_DATA_NUMBER || leaf.number != 7.0)
                return fail("JSON repaired aggregate value");
        }
    }
    deal_value managed_record;
    if (deal_table_new(&managed_record) != DEAL_OK
            || deal_table_set(managed_record, "unread", 6u, opaque_view) != DEAL_OK
            || deal_retain(managed_record, &raw_handle) != DEAL_STATUS_KIND || raw_handle != 0
            || deal_table_set(managed_record, "unread", 6u, raw_view) != DEAL_OK
            || deal_retain(managed_record, &raw_handle) != DEAL_STATUS_KIND || raw_handle != 0
            || deal_table_delete(managed_record, "unread", 6u) != DEAL_OK
            || deal_retain(managed_record, &raw_handle) != DEAL_OK
            || deal_release(raw_handle) != DEAL_OK) {
        return fail("managed nondata Record leaves reject before publication");
    }
    raw_handle = 0;
    deal_gc_collect_full();
    deal_root_scope_pop();
    deal_value opaque_record;
    if (deal_table_new(&opaque_record) != DEAL_OK
            || deal_table_set(opaque_record, "unread", 6u, opaque) != DEAL_OK
            || deal_retain(opaque_record, &raw_handle) != DEAL_STATUS_KIND || raw_handle != 0
            || deal_table_set(opaque_record, "unread", 6u, opaque_other) != DEAL_OK
            || deal_retain(opaque_record, &raw_handle) != DEAL_STATUS_KIND || raw_handle != 0
            || deal_table_delete(opaque_record, "unread", 6u) != DEAL_OK
            || deal_retain(opaque_record, &raw_handle) != DEAL_OK || raw_handle == 0
            || deal_release(raw_handle) != DEAL_OK) {
        return fail("opaque unread Record rejection and retry");
    }
    raw_handle = 0;
    /* A key is independently typed: legal lone text or a raw callable hidden
     * beneath a boolean key cannot change which first rejection owns it. */
    deal_value key_record, false_key, lone_text, key_marker, key_read;
    static const unsigned char lone_bytes[] = {0xed, 0xa0, 0x80};
    int key_present = 1;
    if (deal_table_new(&key_record) != DEAL_OK
            || deal_boolean(0, &false_key) != DEAL_OK
            || deal_string_new(lone_bytes, sizeof lone_bytes, &lone_text) != DEAL_OK
            || deal_number(19.0, &key_marker) != DEAL_OK
            || deal_table_set(key_record, "good", 4u, key_marker) != DEAL_OK
            || deal_table_set_key(key_record, false_key, lone_text) != DEAL_OK
            || deal_table_has(key_record, "", 0u, &key_present) != DEAL_OK || key_present
            || deal_table_get(key_record, "good", 4u, &key_read) != DEAL_OK
            || deal_same(key_marker, key_read, &opaque_same) != DEAL_OK || !opaque_same) {
        return fail("nonstring key must retain unrelated entries without coercion");
    }
    for (int hidden = 0; hidden < 2; hidden++) {
        deal_check_input key_input;
        deal_error *key_cause = NULL;
        const char *code = NULL, *actual = NULL, *expected = NULL, *message = NULL;
        if (hidden && deal_table_set_key(key_record, false_key, raw) != DEAL_OK)
            return fail("replace same nonstring key");
        if (deal_host_value_presentation(key_record, &key_input, &key_cause) != DEAL_STATUS_KIND
                || key_cause == NULL || !deal_error_code(key_cause, &code)
                || strcmp(code, "E8010") != 0
                || !deal_error_actual(key_cause, &actual) || strcmp(actual, "boolean") != 0
                || !deal_error_expected(key_cause, &expected) || strcmp(expected, "string") != 0
                || !deal_error_message(key_cause, &message)
                || strcmp(message, "return value 1 type mismatch: expected string") != 0
                || deal_retain(key_record, &raw_handle) != DEAL_STATUS_KIND || raw_handle != 0) {
            return fail("nonstring key rejects before hidden value without publication");
        }
    }
    deal_value live_record;
    if (deal_table_new(&live_record) != DEAL_OK
            || deal_table_set_key(live_record, lone_text, key_marker) != DEAL_OK
            || deal_retain(live_record, &raw_handle) != DEAL_OK || raw_handle == 0
            || deal_table_set_key(live_record, false_key, raw) != DEAL_STATUS_KIND
            || deal_table_get(live_record, lone_bytes, sizeof lone_bytes, &key_read) != DEAL_OK
            || deal_same(key_read, key_marker, &opaque_same) != DEAL_OK || !opaque_same
            || deal_release(raw_handle) != DEAL_OK) {
        return fail("string key carrier transport and checked live key rejection");
    }
    raw_handle = 0;
    deal_value raw_record;
    if (deal_table_new(&raw_record) != DEAL_OK
            || deal_table_set(raw_record, "unread", 6u, raw) != DEAL_OK
            || deal_retain(raw_record, &raw_handle) != DEAL_STATUS_KIND
            || raw_handle != 0) {
        return fail("unread raw callable must reject before record publication");
    }
    deal_value function;
    if (deal_function_new(inner_entry, "(number)->number", &function)
            != DEAL_OK) {
        return fail("function value construction failed");
    }
    /* Repair the very same uncommitted Record and publish it only after all
     * descendants are data. Failed admission must not poison the identity memo. */
    deal_value repaired_record;
    int same_record = 0;
    if (deal_table_set(raw_record, "unread", 6u, function) != DEAL_OK
            || deal_retain(raw_record, &raw_handle) != DEAL_OK || raw_handle == 0
            || deal_handle_value(raw_handle, "record", &repaired_record) != DEAL_OK
            || deal_same(raw_record, repaired_record, &same_record) != DEAL_OK
            || !same_record || deal_release(raw_handle) != DEAL_OK) {
        return fail("raw Record repair must retain its original identity");
    }
    raw_handle = 0;
    deal_value rejected_array;
    deal_check_input array_input;
    deal_error *array_cause = NULL;
    const char *array_code = NULL, *array_message = NULL, *leaf_code = NULL;
    if (deal_array_new_typed(1u, "(number)->number", &rejected_array) != DEAL_OK
            || deal_array_set(rejected_array, 0u, raw) != DEAL_OK
            || deal_host_value_presentation(rejected_array, &array_input, &array_cause)
                != DEAL_STATUS_KIND
            || array_cause == NULL || !deal_error_code(array_cause, &array_code)
            || strcmp(array_code, "E8010") != 0
            || !deal_error_message(array_cause, &array_message)
            || strcmp(array_message, "return value 1 type mismatch: array element 1 type mismatch") != 0) {
        return fail("raw array element exact outer rejection");
    }
    if (!deal_error_cause_code(array_cause, &leaf_code)
            || strcmp(leaf_code, "E8003") != 0) {
        return fail("raw array element wrapper cause");
    }
    if (deal_array_set(rejected_array, 0u, function) != DEAL_OK
            || deal_retain(rejected_array, &raw_handle) != DEAL_OK || raw_handle == 0
            || deal_release(raw_handle) != DEAL_OK) {
        return fail("raw array element must reject with its own arm and remain repairable");
    }
    /* A retained function value is re-validated against its own canonical
     * descriptor inside the current crossing (H3). */
    deal_handle handle = 0;
    if (deal_retain(function, &handle) != DEAL_OK || handle == 0) {
        return fail("the function value did not retain");
    }
    deal_value restored;
    if (deal_handle_value(handle, "(number)->number", &restored) != DEAL_OK) {
        return fail("the retained function value was not re-validated against"
            " its own descriptor");
    }
    deal_value_kind kind = DEAL_KIND_NULL;
    const char *signature = NULL;
    if (deal_kind_of(restored, &kind) != DEAL_OK
            || kind != DEAL_KIND_FUNCTION) {
        return fail("the retained function value's carrier kind");
    }
    if (deal_function_signature(restored, &signature) != DEAL_OK
            || signature == NULL
            || strcmp(signature, "(number)->number") != 0) {
        return fail("the retained function value's signature");
    }
    if (deal_handle_value(handle, "()->number", &restored)
            != DEAL_STATUS_KIND) {
        return fail("a mismatched function signature is not a kind status");
    }
    deal_value number;
    if (deal_number(4.0, &number) != DEAL_OK) {
        return fail("number construction failed");
    }
    if (deal_handle_value(handle, "number", &restored) != DEAL_STATUS_KIND) {
        return fail("a non-function descriptor at a function value is not a"
            " kind status");
    }
    /* The checked function value applies through the boxed thunk, which runs the
     * declared parameter and result boundaries (H4). */
    deal_value viewed;
    if (deal_handle_value(handle, "(number)->number", &viewed) != DEAL_OK) {
        return fail("the function value did not resolve for the apply");
    }
    deal_host_result applied;
    double answer = 0.0;
    if (deal_apply(viewed, &number, 1, &applied) != DEAL_OK
            || applied.failed != 0
            || deal_as_number(applied.value, &answer) != DEAL_OK
            || answer != 44.0) {
        return fail("the applied function value did not answer its result");
    }
    if (deal_apply(viewed, NULL, 0, &applied) != DEAL_STATUS_PROTOCOL) {
        return fail("an undeclared argument count is not a protocol status");
    }
    /* A typed array whose persistent element descriptor is the same function
     * type: its materialization checks the element at the function position
     * (H2/R11), and a view store into it validates against that descriptor. */
    deal_value functionArray;
    if (deal_array_new_typed(1, "(number)->number", &functionArray)
            != DEAL_OK
            || deal_array_set(functionArray, 0, function) != DEAL_OK) {
        return fail("function-element array construction failed");
    }
    deal_handle arrayHandle = 0;
    if (deal_retain(functionArray, &arrayHandle) != DEAL_OK
            || arrayHandle == 0) {
        return fail("the function-element array did not retain");
    }
    deal_value arrayView;
    if (deal_handle_value(arrayHandle, "[(number)->number]", &arrayView)
            != DEAL_OK) {
        return fail("the managed function-element array was not re-validated"
            " against its descriptor");
    }
    if (deal_array_set(arrayView, 0, function) != DEAL_OK) {
        return fail("the view store of the declared function element");
    }
    if (deal_array_set(arrayView, 0, number) != DEAL_STATUS_KIND) {
        return fail("a wrong-kind element is not a kind status");
    }
    deal_value wrongSignature;
    if (deal_function_new(inner_entry, "()->number", &wrongSignature)
            != DEAL_OK
            || deal_array_set(arrayView, 0, wrongSignature)
                != DEAL_STATUS_KIND) {
        return fail("a wrong-signature element is not a kind status");
    }
    deal_value element;
    deal_host_result elementApplied;
    double elementAnswer = 0.0;
    if (deal_array_get(arrayView, 0, &element) != DEAL_OK
            || deal_apply(element, &number, 1, &elementApplied) != DEAL_OK
            || elementApplied.failed != 0
            || deal_as_number(elementApplied.value, &elementAnswer) != DEAL_OK
            || elementAnswer != 44.0) {
        return fail("the committed function element does not apply");
    }
    if (deal_release(arrayHandle) != DEAL_OK
            || deal_release(handle) != DEAL_OK) {
        return fail("the function values did not release");
    }
    return number_result(1.0);
}
#endif

#if DEAL_HOST_VALUE_CASE == 5 || DEAL_HOST_VALUE_CASE == 6
/* 2^60 slots of a 16-byte slot record is 2^64 bytes: the unchecked product is
 * zero, which used to allocate one byte and record 2^60 elements. */
#define DEAL_HOST_VALUE_IMPOSSIBLE_LENGTH INT64_C(1152921504606846976)

/* The impossible array length must fault the process before any storage exists,
 * so reaching the end of this function at all is the defect under test. */
static deal_host_result overflow_array_case(const deal_value *args,
                                            size_t argc) {
    (void)args;
    (void)argc;
    deal_value array;
#if DEAL_HOST_VALUE_CASE == 5
    if (deal_array_new(DEAL_HOST_VALUE_IMPOSSIBLE_LENGTH, &array) != DEAL_OK) {
        return fail("the impossible array length answered a status");
    }
#else
    if (deal_array_new_typed(DEAL_HOST_VALUE_IMPOSSIBLE_LENGTH, "number",
            &array) != DEAL_OK) {
        return fail("the impossible typed array length answered a status");
    }
#endif
    return fail("the impossible array length was admitted");
}
#endif

static const deal_host_export exports[] = {
    {"construct", "()->number", DEAL_HOST_EXPORT_FUNCTION, 0, construct_case,
     NULL, NULL},
    {"handles", "()->number", DEAL_HOST_EXPORT_FUNCTION, 0, handle_case,
     NULL, NULL},
#if DEAL_HOST_VALUE_CASE == 2
    {"relay", "()->number", DEAL_HOST_EXPORT_FUNCTION, 0, relay_case, NULL,
     NULL},
#elif DEAL_HOST_VALUE_CASE == 3
    {"discard", "()->number", DEAL_HOST_EXPORT_FUNCTION, 0, discard_case, NULL,
     NULL},
#elif DEAL_HOST_VALUE_CASE == 4
    {"unconsumed", "()->number", DEAL_HOST_EXPORT_FUNCTION, 0, unconsumed_case,
     NULL, NULL},
#elif DEAL_HOST_VALUE_CASE == 5
    {"overflowArray", "()->number", DEAL_HOST_EXPORT_FUNCTION, 0,
     overflow_array_case, NULL, NULL},
#elif DEAL_HOST_VALUE_CASE == 6
    {"overflowTypedArray", "()->number", DEAL_HOST_EXPORT_FUNCTION, 0,
     overflow_array_case, NULL, NULL},
#elif DEAL_HOST_VALUE_CASE == 7
    {"functionSurface", "()->number", DEAL_HOST_EXPORT_FUNCTION, 0,
     function_surface_case, NULL, NULL},
#endif
};

static const deal_host_module value_module = {
    "host/value", exports, sizeof exports / sizeof exports[0], NULL, 0u, NULL};

const deal_host_registry_entry deal_host_registry[1] = {
    {"host/value", &value_module}};
const size_t deal_host_registry_count = 1;

static const deal_host_export declared[] = {
    {"construct", "()->number", DEAL_HOST_EXPORT_FUNCTION, 0, NULL, NULL, NULL},
    {"handles", "()->number", DEAL_HOST_EXPORT_FUNCTION, 0, NULL, NULL, NULL},
#if DEAL_HOST_VALUE_CASE == 2
    {"relay", "()->number", DEAL_HOST_EXPORT_FUNCTION, 0, NULL, NULL, NULL},
#elif DEAL_HOST_VALUE_CASE == 3
    {"discard", "()->number", DEAL_HOST_EXPORT_FUNCTION, 0, NULL, NULL, NULL},
#elif DEAL_HOST_VALUE_CASE == 4
    {"unconsumed", "()->number", DEAL_HOST_EXPORT_FUNCTION, 0, NULL, NULL, NULL},
#elif DEAL_HOST_VALUE_CASE == 5
    {"overflowArray", "()->number", DEAL_HOST_EXPORT_FUNCTION, 0, NULL, NULL,
     NULL},
#elif DEAL_HOST_VALUE_CASE == 6
    {"overflowTypedArray", "()->number", DEAL_HOST_EXPORT_FUNCTION, 0, NULL,
     NULL, NULL},
#elif DEAL_HOST_VALUE_CASE == 7
    {"functionSurface", "()->number", DEAL_HOST_EXPORT_FUNCTION, 0, NULL, NULL,
     NULL},
#endif
};

static const deal_host_declared_module declared_module = {
    "host/value", "$external/host/value", declared,
    sizeof declared / sizeof declared[0], NULL, 0u};

static void report(const char *text) {
    deal_platform_write_stderr(text, strlen(text));
    deal_platform_flush_stderr();
}

int main(void) {
    deal_outcome started = deal_project_start();
    if (started.error != NULL) {
        report("the arm table did not install\n");
        return 1;
    }
    const deal_host_binding *binding = NULL;
    deal_outcome loaded = deal_host_load(&declared_module, &probe_origin,
        &binding);
    if (loaded.error != NULL) {
        report("the host load rejected a conforming registration\n");
        return 1;
    }
#if DEAL_HOST_VALUE_CASE == 2 || DEAL_HOST_VALUE_CASE == 4
    /* The relay cases invoke the one export that issues the failure token. */
    const size_t first = binding->export_count - 1u;
#elif DEAL_HOST_VALUE_CASE == 5 || DEAL_HOST_VALUE_CASE == 6
    /* The resource-fault cases invoke only the constructing export. */
    const size_t first = binding->export_count - 1u;
#elif DEAL_HOST_VALUE_CASE == 7
    /* The function-surface case invokes only its own export. */
    const size_t first = binding->export_count - 1u;
#else
    const size_t first = 0u;
#endif
    /* Every selected export runs once, in declaration order; the export record's
     * own declared canonical signature is the crossing's declaration. */
    for (size_t index = first; index < binding->export_count; index += 1u) {
        deal_outcome_data result = deal_host_invoke_sync(binding->exports[index],
            NULL, 0, binding->exports[index]->descriptor, &probe_origin);
#if DEAL_HOST_VALUE_CASE == 2
        /* The relay case admits the identical Error the host issued. */
        if (result.error == NULL) {
            report("the relayed failure was not carried\n");
            return 1;
        }
        const char *code = NULL;
        const char *message = NULL;
        if (!deal_error_code(result.error, &code) || code == NULL
                || strcmp(code, "E_RELAY") != 0) {
            report("the relayed failure lost its code\n");
            return 1;
        }
        if (!deal_error_message(result.error, &message) || message == NULL
                || strcmp(message, "the relayed error") != 0) {
            report("the relayed failure lost its message\n");
            return 1;
        }
#else
        if (result.error != NULL) {
            const char *code = NULL;
            deal_error_code(result.error, &code);
            report("the crossing answered a failure: ");
            report(code == NULL ? "" : code);
            const char *message = NULL;
            if (deal_error_message(result.error, &message) && message != NULL) {
                report(": ");
                report(message);
            }
            report("\n");
            return 1;
        }
        if (result.value.tag != DEAL_DATA_NUMBER) {
            report("the crossing admitted no observation value\n");
            return 1;
        }
#endif
    }
#if DEAL_HOST_VALUE_CASE == 4
    report("the unconsumed token was not reported\n");
    return 1;
#else
    deal_platform_write_stdout("host value ok\n", 14);
    deal_platform_flush_stdout();
    return 0;
#endif
}
