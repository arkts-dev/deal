#include "host/audit15.h"
#include "native_hostile.h"

static deal_handle kept, growing, previous_callback, unknown;
static void remember(deal_handle *slot, deal_value value) {
    if (*slot) checked(deal_release(*slot));
    checked(deal_retain(value, slot));
}
static deal_host_result bad_class(const deal_value *args, size_t argc) {
    (void)argc; deal_value value; checked(deal_host_audit15_C_new(&value));
    double kind = as_number(args[0]);
    if (kind == 0) checked(deal_field_set(value, "x", null_value()));
    else if (kind == 1) {
        deal_value bad; checked(deal_string_new("\xed\xa0\x80", 3u, &bad));
        checked(deal_field_set(value, "x", bad));
    } else if (kind != 2) checked(deal_field_set(value, "x", number(1)));
    return result(value);
}
static deal_host_result nullish(const deal_value *args, size_t argc) {
    (void)args; (void)argc; return result(undefined_value());
}
static deal_host_result echo(const deal_value *args, size_t argc) {
    (void)argc; return result(args[0]);
}
static deal_host_result replace(const deal_value *args, size_t argc) {
    (void)argc; checked(deal_array_set(args[0], 0, number(2))); return result(undefined_value());
}
static deal_host_result keep(const deal_value *args, size_t argc) {
    (void)argc; remember(&kept, args[0]); return result(undefined_value());
}
static deal_host_result sees_undefined(const deal_value *args, size_t argc) {
    (void)args; (void)argc; deal_value_kind kind;
    checked(deal_kind_of(at(retained(kept, "[!number]"), 0), &kind));
    return result(boolean(kind == DEAL_KIND_UNDEFINED));
}
static deal_host_result fresh(const deal_value *args, size_t argc) {
    (void)args; (void)argc; deal_value values = array("number", 1);
    checked(deal_array_set(values, 0, number(1))); remember(&growing, values); return result(values);
}
static deal_host_result grow(const deal_value *args, size_t argc) {
    (void)args; (void)argc;
    checked(deal_array_set(retained(growing, "[number]"), 1, number(2))); return result(undefined_value());
}
static deal_host_result conflict(const deal_value *args, size_t argc) {
    (void)args; (void)argc; deal_value values = array("number", 1);
    checked(deal_array_set(values, 0, number(1))); return result(values);
}
static deal_host_result callbacks(const deal_value *args, size_t argc) {
    (void)argc; int same, previous = 1;
    checked(deal_same(args[0], args[1], &same));
    if (previous_callback) checked(deal_same(retained(previous_callback, "()->void"), args[0], &previous));
    remember(&previous_callback, args[0]); return result(boolean(same && previous));
}
static deal_host_result native_failure(const deal_value *args, size_t argc) {
    (void)args; (void)argc; return host_error("NATIVE", "native");
}
static deal_host_result callable_failure(const deal_value *args, size_t argc) {
    (void)args; (void)argc; return host_error("NATIVE", "callable");
}
static deal_host_result native_callable(const deal_value *args, size_t argc) {
    (void)args; (void)argc; deal_value value;
    checked(deal_function_new(callable_failure, "()->void", &value)); return result(value);
}
static deal_host_result callback_failure(const deal_value *args, size_t argc) {
    (void)argc; return apply(args[0], NULL, 0u);
}
static deal_host_result async_failure(deal_async_token token, const deal_value *args, size_t argc) {
    (void)args; (void)argc; deal_host_result failure = host_error("NATIVE", "native async");
    checked(deal_async_complete_error(token, &failure.failure));
    deal_value operation; checked(deal_async_operation(token, &operation)); return result(operation);
}
static deal_host_result async_callback(deal_async_token token, const deal_value *args, size_t argc) {
    (void)argc; deal_host_result answer = apply(args[0], NULL, 0u);
    if (answer.failed) checked(deal_async_complete_error(token, &answer.failure));
    else checked(deal_async_complete_value(token, answer.value));
    deal_value operation; checked(deal_async_operation(token, &operation)); return result(operation);
}
static deal_host_result echo_error(const deal_value *args, size_t argc) {
    (void)argc; deal_handle error; checked(deal_retain(args[0], &error));
    deal_host_result answer = {0}; answer.failed = 1; answer.value = args[0];
    checked(deal_error_from_handle(error, &answer.failure)); checked(deal_release(error)); return answer;
}
static deal_host_result keep_unknown(const deal_value *args, size_t argc) {
    (void)argc; remember(&unknown, args[0]); return result(undefined_value());
}
static deal_host_result raw_zero(const deal_value *args, size_t argc) {
    (void)args; (void)argc; return result(number(0));
}
static deal_host_result corrupt(const deal_value *args, size_t argc) {
    (void)argc; deal_value values = retained(unknown, "[unknown]");
    if (as_number(args[0]) == 0) {
        checked(deal_array_set(values, 0, text("abc")));
        fixture_surrogate_string(at(values, 0));
    } else {
        deal_value fn; checked(deal_function_new(raw_zero, "()->number", &fn));
        checked(deal_array_set(values, 0, fn)); fixture_remove_function_signature(at(values, 0));
    }
    return result(undefined_value());
}
static deal_host_result opaque_array(const deal_value *args, size_t argc) {
    (void)args; (void)argc; deal_value atom, opaque;
    checked(deal_class_atom("@host/Opaque", &atom)); checked(deal_class_new(atom, &opaque));
    deal_value values = raw_array(1); checked(deal_array_set(values, 0, opaque)); return result(values);
}
const deal_host_field deal_host_fields_audit15_C[] = {{"x", "string", 0}};
const deal_host_class deal_host_class_audit15_C = {DEAL_HOST_CLASS_ATOM_AUDIT15_C, deal_host_fields_audit15_C, 1u};
const deal_host_class deal_host_classes_audit15[] = {{DEAL_HOST_CLASS_ATOM_AUDIT15_C, deal_host_fields_audit15_C, 1u}};
const deal_host_export deal_host_exports_audit15[] = {
    {"C", DEAL_HOST_CLASS_ATOM_AUDIT15_C, DEAL_HOST_EXPORT_CLASS, 0, NULL, NULL, deal_host_classes_audit15},
    {"badClass", "(number)->" DEAL_HOST_CLASS_ATOM_AUDIT15_C, DEAL_HOST_EXPORT_FUNCTION, 0, bad_class, NULL, NULL},
    {"nullish", "()->null|undefined", DEAL_HOST_EXPORT_FUNCTION, 0, nullish, NULL, NULL},
    {"echoArray", "([unknown])->[unknown]", DEAL_HOST_EXPORT_FUNCTION, 0, echo, NULL, NULL},
    {"replace", "([unknown])->void", DEAL_HOST_EXPORT_FUNCTION, 0, replace, NULL, NULL},
    {"numbers", "([!number])->[!number]", DEAL_HOST_EXPORT_FUNCTION, 0, echo, NULL, NULL},
    {"booleans", "([!boolean])->[!boolean]", DEAL_HOST_EXPORT_FUNCTION, 0, echo, NULL, NULL},
    {"strings", "([!string])->[!string]", DEAL_HOST_EXPORT_FUNCTION, 0, echo, NULL, NULL},
    {"keep", "([!number])->void", DEAL_HOST_EXPORT_FUNCTION, 0, keep, NULL, NULL},
    {"seesUndefined", "()->boolean", DEAL_HOST_EXPORT_FUNCTION, 0, sees_undefined, NULL, NULL},
    {"fresh", "()->[number]", DEAL_HOST_EXPORT_FUNCTION, 0, fresh, NULL, NULL},
    {"grow", "()->void", DEAL_HOST_EXPORT_FUNCTION, 0, grow, NULL, NULL},
    {"conflict", "()->[unknown]", DEAL_HOST_EXPORT_FUNCTION, 0, conflict, NULL, NULL},
    {"callbacks", "(()->void,()->void)->boolean", DEAL_HOST_EXPORT_FUNCTION, 0, callbacks, NULL, NULL},
    {"echoFunction", "(()->void)->()->void", DEAL_HOST_EXPORT_FUNCTION, 0, echo, NULL, NULL},
    {"nativeFailure", "()->void", DEAL_HOST_EXPORT_FUNCTION, 0, native_failure, NULL, NULL},
    {"nativeCallable", "()->()->void", DEAL_HOST_EXPORT_FUNCTION, 0, native_callable, NULL, NULL},
    {"callbackFailure", "(()->void)->void", DEAL_HOST_EXPORT_FUNCTION, 0, callback_failure, NULL, NULL},
    {"asyncFailure", "async()->void", DEAL_HOST_EXPORT_ASYNC_FUNCTION, 1, NULL, async_failure, NULL},
    {"asyncCallback", "async(()->void)->void", DEAL_HOST_EXPORT_ASYNC_FUNCTION, 1, NULL, async_callback, NULL},
    {"echoError", "(Error)->void", DEAL_HOST_EXPORT_FUNCTION, 0, echo_error, NULL, NULL},
    {"keepUnknown", "([unknown])->void", DEAL_HOST_EXPORT_FUNCTION, 0, keep_unknown, NULL, NULL},
    {"corrupt", "(number)->void", DEAL_HOST_EXPORT_FUNCTION, 0, corrupt, NULL, NULL},
    {"opaqueArray", "()->[unknown]", DEAL_HOST_EXPORT_FUNCTION, 0, opaque_array, NULL, NULL}
};
const deal_host_module deal_host_module_audit15 = {
    DEAL_HOST_SPECIFIER_AUDIT15, deal_host_exports_audit15,
    sizeof deal_host_exports_audit15 / sizeof deal_host_exports_audit15[0], deal_host_classes_audit15, 1u, NULL
};
