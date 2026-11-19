#include "host/never_completion.h"
#include "native_helpers.h"
static deal_host_result bad(const deal_value *args, size_t argc) {
    (void)args; (void)argc; return result(number(42));
}
static deal_host_result nothing(const deal_value *args, size_t argc) {
    (void)args; (void)argc; deal_value value; checked(deal_nothing(&value)); return result(value);
}
static deal_host_result bad_async(deal_async_token token, const deal_value *args, size_t argc) {
    (void)args; (void)argc; return complete(token, number(42));
}
const deal_host_export deal_host_exports_never_completion[] = {
    {"bad", "()->never", DEAL_HOST_EXPORT_FUNCTION, 0, bad, NULL, NULL},
    {"nothing", "()->never", DEAL_HOST_EXPORT_FUNCTION, 0, nothing, NULL, NULL},
    {"badAsync", "async()->never", DEAL_HOST_EXPORT_ASYNC_FUNCTION, 1, NULL, bad_async, NULL}
};
const deal_host_module deal_host_module_never_completion = {
    DEAL_HOST_SPECIFIER_NEVER_COMPLETION, deal_host_exports_never_completion, 3u, NULL, 0u, NULL
};
