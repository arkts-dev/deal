#include "host/probe.h"
#include "native_helpers.h"
static deal_handle saved;
static deal_host_result shared(void) {
    if (!saved) {
        deal_value values = array("!string", 1);
        checked(deal_array_set(values, 0, text("x")));
        checked(deal_retain(values, &saved));
        return result(values);
    }
    return result(retained(saved, "[!string]"));
}
static deal_host_result impl_numbers(const deal_value *args, size_t argc) {
    (void)args; (void)argc; return shared();
}
static deal_host_result impl_strings(const deal_value *args, size_t argc) {
    (void)args; (void)argc; return shared();
}
const deal_host_export deal_host_exports_probe[] = {
    {"numbers", "()->[!number]", DEAL_HOST_EXPORT_FUNCTION, 0, impl_numbers, NULL, NULL},
    {"strings", "()->[!string]", DEAL_HOST_EXPORT_FUNCTION, 0, impl_strings, NULL, NULL},
};
const deal_host_module deal_host_module_probe = {
    DEAL_HOST_SPECIFIER_PROBE, deal_host_exports_probe,
    sizeof deal_host_exports_probe / sizeof deal_host_exports_probe[0], NULL, 0u, NULL
};
