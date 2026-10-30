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
    deal_value function;
    if (deal_function_new(inner_entry, "(number)->number", &function)
            != DEAL_OK) {
        return fail("function value construction failed");
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
