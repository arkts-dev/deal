#include "host/probe.h"
#include "native_hostile.h"
static deal_host_result mutate(deal_async_token token, const deal_value *args, size_t argc) {
    (void)argc; fixture_class_field(args[0], "x", text("bad"), 1);
    return complete(token, undefined_value());
}
const deal_host_export deal_host_exports_probe[] = {
    {"mutate", "async(unknown)->void", DEAL_HOST_EXPORT_ASYNC_FUNCTION, 1, NULL, mutate, NULL}
};
const deal_host_module deal_host_module_probe = {DEAL_HOST_SPECIFIER_PROBE, deal_host_exports_probe, 1u, NULL, 0u, NULL};
