#include "host/language15.h"
#include "native_helpers.h"

static deal_host_result echo(const deal_value *args, size_t argc) {
    (void)argc; return result(args[0]);
}
static deal_host_result absent(const deal_value *args, size_t argc) {
    (void)args; (void)argc; return result(undefined_value());
}
static deal_host_result mutate(const deal_value *args, size_t argc) {
    (void)argc; checked(deal_array_set(args[0], 0, number(9))); return result(args[0]);
}
static deal_host_result fresh(const deal_value *args, size_t argc) {
    (void)args; (void)argc; deal_value values = array("number", 1);
    checked(deal_array_set(values, 0, number(4))); return result(values);
}
static deal_host_result mutate_class(const deal_value *args, size_t argc) {
    (void)argc; checked(deal_field_set(args[0], "n", number(8))); return result(args[0]);
}
static deal_host_result class_number(const deal_value *args, size_t argc) {
    (void)argc; return result(field(args[0], "n"));
}
static deal_host_result callback(const deal_value *args, size_t argc) {
    (void)argc; return apply(args[0], NULL, 0u);
}
static deal_host_result async_absent(deal_async_token token, const deal_value *args, size_t argc) {
    (void)args; (void)argc; return complete(token, undefined_value());
}
static deal_host_result invalid_nested(const deal_value *args, size_t argc) {
    (void)args; (void)argc; deal_value bad, child, value;
    checked(deal_string_new("\xed\xa0\x80", 3u, &bad));
    checked(deal_table_new(&child)); checked(deal_table_new(&value));
    set(child, "bad", bad); set(value, "child", child); return result(value);
}
static deal_host_result opaque(const deal_value *args, size_t argc) {
    (void)args; (void)argc;
    /* An undeclared host-native object, not a no-value or malformed string. */
    deal_value atom, value; checked(deal_class_atom("@host/Opaque", &atom));
    checked(deal_class_new(atom, &value)); return result(value);
}
static deal_host_result graph(const deal_value *args, size_t argc) {
    (void)args; (void)argc; deal_value value, child;
    checked(deal_table_new(&value)); checked(deal_table_new(&child));
    set(value, "self", value); set(value, "a", child); set(value, "b", child); return result(value);
}
const deal_host_export deal_host_exports_language15[] = {
    {"echo", "(unknown)->unknown", DEAL_HOST_EXPORT_FUNCTION, 0, echo, NULL, NULL},
    {"undefinedValue", "()->undefined", DEAL_HOST_EXPORT_FUNCTION, 0, absent, NULL, NULL},
    {"nothing", "()->void", DEAL_HOST_EXPORT_FUNCTION, 0, absent, NULL, NULL},
    {"maybeNumber", "(!number)->!number", DEAL_HOST_EXPORT_FUNCTION, 0, echo, NULL, NULL},
    {"maybeString", "(!string)->!string", DEAL_HOST_EXPORT_FUNCTION, 0, echo, NULL, NULL},
    {"maybeBoolean", "(!boolean)->!boolean", DEAL_HOST_EXPORT_FUNCTION, 0, echo, NULL, NULL},
    {"mutate", "([number])->[number]", DEAL_HOST_EXPORT_FUNCTION, 0, mutate, NULL, NULL},
    {"fresh", "()->[number]", DEAL_HOST_EXPORT_FUNCTION, 0, fresh, NULL, NULL},
    {"mutateClass", "(unknown)->unknown", DEAL_HOST_EXPORT_FUNCTION, 0, mutate_class, NULL, NULL},
    {"classNumber", "(unknown)->number", DEAL_HOST_EXPORT_FUNCTION, 0, class_number, NULL, NULL},
    {"callback", "(()->void)->void", DEAL_HOST_EXPORT_FUNCTION, 0, callback, NULL, NULL},
    {"asyncUnknown", "async()->unknown", DEAL_HOST_EXPORT_ASYNC_FUNCTION, 1, NULL, async_absent, NULL},
    {"asyncUndefined", "async()->undefined", DEAL_HOST_EXPORT_ASYNC_FUNCTION, 1, NULL, async_absent, NULL},
    {"asyncVoid", "async()->void", DEAL_HOST_EXPORT_ASYNC_FUNCTION, 1, NULL, async_absent, NULL},
    {"invalidNested", "()->record", DEAL_HOST_EXPORT_FUNCTION, 0, invalid_nested, NULL, NULL},
    {"opaque", "()->unknown", DEAL_HOST_EXPORT_FUNCTION, 0, opaque, NULL, NULL},
    {"graph", "()->unknown", DEAL_HOST_EXPORT_FUNCTION, 0, graph, NULL, NULL}
};
const deal_host_module deal_host_module_language15 = {
    DEAL_HOST_SPECIFIER_LANGUAGE15, deal_host_exports_language15,
    sizeof deal_host_exports_language15 / sizeof deal_host_exports_language15[0], NULL, 0u, NULL
};
