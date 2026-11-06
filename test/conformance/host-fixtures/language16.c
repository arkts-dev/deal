#include "host/language16.h"
#include "native_helpers.h"

static deal_host_result echo(const deal_value *args, size_t argc) {
    (void)argc; return result(args[0]);
}
static int same(deal_value a, deal_value b) {
    int equal; checked(deal_same(a, b, &equal)); return equal;
}
static int text_equal(deal_value value, const char *bytes, size_t length) {
    const void *actual; size_t actual_length;
    checked(deal_as_string(value, &actual, &actual_length));
    return actual_length == length && memcmp(actual, bytes, length) == 0;
}
static deal_host_result inspect_text(const deal_value *args, size_t argc) {
    (void)argc;
    deal_value root = args[0], a = get(root, "a"), b = get(root, "b");
    deal_value lone; checked(deal_table_get(root, "\xf0\x9f\x98\x80", 4u, &lone));
    int valid = same(get(root, "self"), root) && same(a, b)
        && text_equal(lone, "\xed\xa0\x80", 3u)
        && text_equal(at(a, 0), "\xf0\x9f\x98\x80", 4u)
        && text_equal(at(a, 1), "\xed\xa0\x80", 3u);
    return result(boolean(valid));
}
static deal_host_result absent(const deal_value *args, size_t argc) {
    (void)args; (void)argc; return result(undefined_value());
}
static deal_host_result nothing(const deal_value *args, size_t argc) {
    (void)args; (void)argc;
    deal_value value; checked(deal_nothing(&value)); return result(value);
}
static deal_host_result mutate(const deal_value *args, size_t argc) {
    (void)argc; checked(deal_array_set(args[0], 0, number(9))); return result(args[0]);
}
static deal_host_result fresh(const deal_value *args, size_t argc) {
    (void)args; (void)argc;
    deal_value value = array("number", 1);
    checked(deal_array_set(value, 0, number(4))); return result(value);
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
static deal_host_result nested(const deal_value *args, size_t argc) {
    (void)args; (void)argc;
    deal_value root, child;
    checked(deal_table_new(&root)); checked(deal_table_new(&child));
    set(child, "bad", text("\xed\xa0\x80")); set(root, "child", child);
    return result(root);
}
static deal_host_result opaque(const deal_value *args, size_t argc) {
    (void)args; (void)argc;
    static const unsigned char identity = 0u;
    deal_value value; checked(deal_opaque(&identity, &value)); return result(value);
}
static deal_host_result graph(const deal_value *args, size_t argc) {
    (void)args; (void)argc;
    deal_value root, child;
    checked(deal_table_new(&root)); checked(deal_table_new(&child));
    set(root, "self", root); set(root, "a", child); set(root, "b", child);
    return result(root);
}
const deal_host_export deal_host_exports_language16[] = {
    {"echo", "(unknown)->unknown", DEAL_HOST_EXPORT_FUNCTION, 0, echo, NULL, NULL},
    {"inspectText", "(record)->boolean", DEAL_HOST_EXPORT_FUNCTION, 0, inspect_text, NULL, NULL},
    {"undefinedValue", "()->undefined", DEAL_HOST_EXPORT_FUNCTION, 0, absent, NULL, NULL},
    {"nothing", "()->void", DEAL_HOST_EXPORT_FUNCTION, 0, nothing, NULL, NULL},
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
    {"invalidNested", "()->record", DEAL_HOST_EXPORT_FUNCTION, 0, nested, NULL, NULL},
    {"opaque", "()->unknown", DEAL_HOST_EXPORT_FUNCTION, 0, opaque, NULL, NULL},
    {"graph", "()->unknown", DEAL_HOST_EXPORT_FUNCTION, 0, graph, NULL, NULL},
};
const deal_host_module deal_host_module_language16 = {
    DEAL_HOST_SPECIFIER_LANGUAGE16, deal_host_exports_language16,
    sizeof deal_host_exports_language16 / sizeof deal_host_exports_language16[0], NULL, 0u, NULL
};
