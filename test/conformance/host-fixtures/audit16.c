#include "host/audit16.h"
#include "native_hostile.h"

#define C_ATOM "@$external/host/audit16/C"
static deal_handle kept, growing, callback, unknown;
static void keep_handle(deal_handle *slot, deal_value value) {
    if (*slot) checked(deal_release(*slot));
    checked(deal_retain(value, slot));
}
static deal_host_result echo(const deal_value *args, size_t argc) {
    (void)argc; return result(args[0]);
}
static deal_host_result bad_class(const deal_value *args, size_t argc) {
    (void)argc; deal_value value, atom; checked(deal_class_atom(C_ATOM, &atom));
    checked(deal_class_new(atom, &value));
    double kind = as_number(args[0]);
    if (kind != 2) checked(deal_field_set(value, "x", kind == 0 ? null_value()
        : kind == 1 ? text("\xed\xa0\x80") : number(1)));
    return result(value);
}
static deal_host_result absent(const deal_value *args, size_t argc) {
    (void)args; (void)argc; return result(undefined_value());
}
static deal_host_result replace(const deal_value *args, size_t argc) {
    (void)argc; checked(deal_array_set(args[0], 0, number(2))); return result(undefined_value());
}
static deal_host_result keep(const deal_value *args, size_t argc) {
    (void)argc; keep_handle(&kept, args[0]); return result(undefined_value());
}
static deal_host_result sees_undefined(const deal_value *args, size_t argc) {
    (void)args; (void)argc; deal_value_kind kind;
    checked(deal_kind_of(at(retained(kept, NULL), 0), &kind));
    return result(boolean(kind == DEAL_KIND_UNDEFINED));
}
static deal_host_result fresh(const deal_value *args, size_t argc) {
    (void)args; (void)argc; deal_value value = array("number", 1);
    checked(deal_array_set(value, 0, number(1))); keep_handle(&growing, value); return result(value);
}
static deal_host_result grow(const deal_value *args, size_t argc) {
    (void)args; (void)argc; deal_value value = retained(growing, NULL); int64_t length;
    checked(deal_array_length(value, &length)); checked(deal_array_set(value, length, number(2)));
    return result(undefined_value());
}
static deal_host_result conflict(const deal_value *args, size_t argc) {
    (void)args; (void)argc; deal_value value = array("number", 1);
    checked(deal_array_set(value, 0, number(1))); return result(value);
}
static deal_host_result callbacks(const deal_value *args, size_t argc) {
    (void)argc; int same, previous = 1; checked(deal_same(args[0], args[1], &same));
    if (callback) checked(deal_same(retained(callback, NULL), args[0], &previous));
    keep_handle(&callback, args[0]); return result(boolean(same && previous));
}
static deal_host_result native_failure(const deal_value *args, size_t argc) {
    (void)args; (void)argc; return host_error("E_NATIVE", "native");
}
static deal_host_result callable_failure(const deal_value *args, size_t argc) {
    (void)args; (void)argc; return host_error("E_NATIVE", "callable");
}
static deal_host_result native_callable(const deal_value *args, size_t argc) {
    (void)args; (void)argc; deal_value value;
    checked(deal_function_new(callable_failure, "()->void", &value)); return result(value);
}
static deal_host_result callback_failure(const deal_value *args, size_t argc) {
    (void)argc; return apply(args[0], NULL, 0);
}
static deal_host_result async_failure(deal_async_token token, const deal_value *args, size_t argc) {
    (void)args; (void)argc; deal_host_result failed = host_error("E_NATIVE", "native async");
    checked(deal_async_complete_error(token, &failed.failure)); deal_value operation;
    checked(deal_async_operation(token, &operation)); return result(operation);
}
static deal_host_result async_callback(deal_async_token token, const deal_value *args, size_t argc) {
    (void)argc; deal_host_result called = apply(args[0], NULL, 0);
    if (!called.failed) return complete(token, undefined_value());
    checked(deal_async_complete_error(token, &called.failure)); deal_value operation;
    checked(deal_async_operation(token, &operation)); return result(operation);
}
static deal_host_result echo_error(const deal_value *args, size_t argc) {
    (void)argc; deal_check_input input; deal_error *cause = NULL;
    checked(deal_host_value_presentation(args[0], &input, &cause));
    if (cause || input.datum.tag != DEAL_DATA_ERROR) abort();
    deal_host_result failed; checked(deal_host_relay_error(input.datum.reference, &failed)); return failed;
}
static deal_host_result keep_unknown(const deal_value *args, size_t argc) {
    (void)argc; keep_handle(&unknown, args[0]); return result(undefined_value());
}
static deal_host_result raw_entry(const deal_value *args, size_t argc) {
    (void)args; (void)argc; return result(number(0));
}
static deal_host_result corrupt(const deal_value *args, size_t argc) {
    (void)argc; deal_value value = retained(unknown, NULL);
    if (as_number(args[0]) == 0) checked(deal_array_set(value, 0, text("\xed\xa0\x80")));
    else {
        deal_value raw; checked(deal_function_raw(raw_entry, &raw));
        if (deal_array_set(value, 0, raw) != DEAL_STATUS_KIND) abort();
        fixture_box *box = fixture_box_of(raw);
        deal_check_input input; deal_error *cause = NULL;
        checked(deal_host_value_presentation(value, &input, &cause));
        if (cause || input.datum.tag != DEAL_DATA_ARRAY) abort();
        deal_container_array_store(input.datum.reference, 0u, &box->managed);
    }
    return result(undefined_value());
}
static deal_host_result opaque_array(const deal_value *args, size_t argc) {
    (void)args; (void)argc; static int identity;
    deal_value opaque; checked(deal_opaque(&identity, &opaque)); deal_value value = raw_array(1);
    checked(deal_array_set(value, 0, opaque)); return result(value);
}
const deal_host_field deal_host_fields_audit16_C[] = {{"x", "string", 0}};
const deal_host_class deal_host_class_audit16_C = {C_ATOM, deal_host_fields_audit16_C, 1u};
const deal_host_class deal_host_classes_audit16[] = {{C_ATOM, deal_host_fields_audit16_C, 1u}};
#define SYNC(name, descriptor, entry) {name, descriptor, DEAL_HOST_EXPORT_FUNCTION, 0, entry, NULL, NULL}
#define ASYNC(name, entry) {name, "async()->void", DEAL_HOST_EXPORT_ASYNC_FUNCTION, 1, NULL, entry, NULL}
const deal_host_export deal_host_exports_audit16[] = {
    {"C", C_ATOM, DEAL_HOST_EXPORT_CLASS, 0, NULL, NULL, &deal_host_classes_audit16[0]},
    SYNC("badClass", "(number)->" C_ATOM, bad_class),
    SYNC("nullish", "()->null|undefined", absent),
    SYNC("echoArray", "([unknown])->[unknown]", echo),
    SYNC("replace", "([unknown])->void", replace),
    SYNC("numbers", "([!number])->[!number]", echo),
    SYNC("booleans", "([!boolean])->[!boolean]", echo),
    SYNC("strings", "([!string])->[!string]", echo),
    SYNC("keep", "([!number])->void", keep),
    SYNC("seesUndefined", "()->boolean", sees_undefined),
    SYNC("fresh", "()->[number]", fresh), SYNC("grow", "()->void", grow),
    SYNC("conflict", "()->[unknown]", conflict),
    SYNC("callbacks", "(()->void,()->void)->boolean", callbacks),
    SYNC("echoFunction", "(()->void)->()->void", echo),
    SYNC("nativeFailure", "()->void", native_failure),
    SYNC("nativeCallable", "()->()->void", native_callable),
    SYNC("callbackFailure", "(()->void)->void", callback_failure),
    ASYNC("asyncFailure", async_failure),
    {"asyncCallback", "async(()->void)->void", DEAL_HOST_EXPORT_ASYNC_FUNCTION, 1, NULL, async_callback, NULL},
    SYNC("echoError", "(Error)->void", echo_error),
    SYNC("keepUnknown", "([unknown])->void", keep_unknown),
    SYNC("corrupt", "(number)->void", corrupt), SYNC("opaqueArray", "()->[unknown]", opaque_array),
};
const deal_host_module deal_host_module_audit16 = {DEAL_HOST_SPECIFIER_AUDIT16,
    deal_host_exports_audit16, sizeof deal_host_exports_audit16 / sizeof deal_host_exports_audit16[0],
    deal_host_classes_audit16, 1u, NULL};
