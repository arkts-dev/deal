#include "host/probe.h"
#include "native_helpers.h"
static deal_host_result impl_get(const deal_value *args, size_t argc) {
    (void)args; (void)argc;
    deal_value values = array("?number", 2);
    checked(deal_array_set(values, 0, null_value()));
    checked(deal_array_set(values, 1, number(1)));
    return result(values);
}
const deal_host_export deal_host_exports_probe[] = {
    {"get", "()->[?number]", DEAL_HOST_EXPORT_FUNCTION, 0, impl_get, NULL, NULL},
};
const deal_host_module deal_host_module_probe = {
    DEAL_HOST_SPECIFIER_PROBE, deal_host_exports_probe,
    sizeof deal_host_exports_probe / sizeof deal_host_exports_probe[0], NULL, 0u, NULL
};
