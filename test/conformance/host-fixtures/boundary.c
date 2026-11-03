#include "host/boundary.h"
#include "native_helpers.h"
static double counter;
static deal_host_result echo(const deal_value *args, size_t argc) {
    (void)argc; return result(args[0]);
}
static deal_host_result next(const deal_value *args, size_t argc) {
    (void)args; (void)argc; return result(number(++counter));
}
const deal_host_export deal_host_exports_boundary[] = {
    {"echoBoolean", "(boolean)->boolean", DEAL_HOST_EXPORT_FUNCTION, 0, echo, NULL, NULL},
    {"echoInt", "(number)->number", DEAL_HOST_EXPORT_FUNCTION, 0, echo, NULL, NULL},
    {"echoNumber", "(number)->number", DEAL_HOST_EXPORT_FUNCTION, 0, echo, NULL, NULL},
    {"echoString", "(string)->string", DEAL_HOST_EXPORT_FUNCTION, 0, echo, NULL, NULL},
    {"nextValue", "()->number", DEAL_HOST_EXPORT_FUNCTION, 0, next, NULL, NULL},
    {"nullableInt", "(?number)->?number", DEAL_HOST_EXPORT_FUNCTION, 0, echo, NULL, NULL}
};
const deal_host_module deal_host_module_boundary = {
    DEAL_HOST_SPECIFIER_BOUNDARY, deal_host_exports_boundary, 6u, NULL, 0u, NULL
};
