#include "host/nullable_fn_return.h"
#include "native_hostile.h"
static deal_host_result raw(const deal_value *args, size_t argc) {
    (void)argc; return result(args[0]);
}
static deal_host_result get_callback(const deal_value *args, size_t argc) {
    (void)argc;
    const void *bytes; size_t length;
    checked(deal_as_string(args[0], &bytes, &length));
    if (length == 3u && !memcmp(bytes, "bad", 3u))
        return result(fixture_raw_function(raw));
    return result(null_value());
}
const deal_host_export deal_host_exports_nullable_fn_return[] = {
    {"getCallback", "(string)->?(number)->number", DEAL_HOST_EXPORT_FUNCTION, 0, get_callback, NULL, NULL}
};
const deal_host_module deal_host_module_nullable_fn_return = {
    DEAL_HOST_SPECIFIER_NULLABLE_FN_RETURN, deal_host_exports_nullable_fn_return, 1u, NULL, 0u, NULL
};
