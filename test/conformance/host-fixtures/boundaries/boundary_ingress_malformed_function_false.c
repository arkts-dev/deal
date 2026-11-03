#include "host/probe.h"
#include "native_hostile.h"
static deal_host_result impl_f(const deal_value *args, size_t argc) {
    (void)args; (void)argc;
    return result(fixture_bad_function(0));
}
const deal_host_export deal_host_exports_probe[] = {
    {"f", "()->unknown", DEAL_HOST_EXPORT_FUNCTION, 0, impl_f, NULL, NULL},
};
const deal_host_module deal_host_module_probe = {
    DEAL_HOST_SPECIFIER_PROBE, deal_host_exports_probe,
    sizeof deal_host_exports_probe / sizeof deal_host_exports_probe[0], NULL, 0u, NULL
};
