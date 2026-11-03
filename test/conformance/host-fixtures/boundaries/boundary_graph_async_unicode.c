#include "host/probe.h"
#include "native_hostile.h"

static deal_host_result mutate(deal_async_token token, const deal_value *args, size_t argc) {
    (void)argc;
    deal_value string = text("bad");
    set(args[0], "bad", string);
    fixture_surrogate_string(get(args[0], "bad"));
    return complete(token, undefined_value());
}
const deal_host_export deal_host_exports_probe[] = {
    {"mutate", "async(record)->void", DEAL_HOST_EXPORT_ASYNC_FUNCTION, 1, NULL, mutate, NULL},
};
const deal_host_module deal_host_module_probe = {
    DEAL_HOST_SPECIFIER_PROBE, deal_host_exports_probe,
    sizeof deal_host_exports_probe / sizeof deal_host_exports_probe[0], NULL, 0u, NULL
};
