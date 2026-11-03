#include "host/probe.h"
#include "native_helpers.h"
static deal_handle saved;
static deal_host_result impl_keep(const deal_value *args, size_t argc) {
    (void)argc;
    if (saved) checked(deal_release(saved));
    checked(deal_retain(args[0], &saved));
    return result(undefined_value());
}
static deal_host_result impl_effect(const deal_value *args, size_t argc) {
    (void)args; (void)argc;
    checked(deal_array_set(retained(saved, "[number]"), 1, number(2)));
    return result(undefined_value());
}
static deal_host_result impl_echo(const deal_value *args, size_t argc) {
    (void)argc; return result(args[0]);
}
const deal_host_export deal_host_exports_probe[] = {
    {"keep", "(unknown)->void", DEAL_HOST_EXPORT_FUNCTION, 0, impl_keep, NULL, NULL},
    {"effect", "()->void", DEAL_HOST_EXPORT_FUNCTION, 0, impl_effect, NULL, NULL},
    {"echo", "(unknown)->unknown", DEAL_HOST_EXPORT_FUNCTION, 0, impl_echo, NULL, NULL},
};
const deal_host_module deal_host_module_probe = {
    DEAL_HOST_SPECIFIER_PROBE, deal_host_exports_probe,
    sizeof deal_host_exports_probe / sizeof deal_host_exports_probe[0], NULL, 0u, NULL
};
