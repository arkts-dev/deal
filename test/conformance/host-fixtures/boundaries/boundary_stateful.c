#include "host/probe.h"
#include "native_hostile.h"
static deal_handle saved;
static deal_host_result keep(const deal_value *args, size_t argc) {
    (void)argc; if (saved) checked(deal_release(saved)); checked(deal_retain(args[0], &saved));
    return result(undefined_value());
}
static deal_host_result echo(const deal_value *args, size_t argc) { (void)argc; return result(args[0]); }
static deal_host_result mutate(const deal_value *args, size_t argc) {
    (void)argc; fixture_number_element(retained(saved, NULL), 0u, args[0]);
    return result(undefined_value());
}
static deal_host_result corrupt(const deal_value *args, size_t argc) {
    (void)args; (void)argc; fixture_number_element(retained(saved, NULL), 0u, text("bad"));
    return result(undefined_value());
}
static deal_host_result callback(const deal_value *args, size_t argc) {
    (void)argc; return apply(args[0], args + 1u, 1u);
}
const deal_host_export deal_host_exports_probe[] = {
    {"keep", "(unknown)->void", DEAL_HOST_EXPORT_FUNCTION, 0, keep, NULL, NULL},
    {"echo", "(unknown)->unknown", DEAL_HOST_EXPORT_FUNCTION, 0, echo, NULL, NULL},
    {"mutate", "(number)->void", DEAL_HOST_EXPORT_FUNCTION, 0, mutate, NULL, NULL},
    {"corrupt", "()->void", DEAL_HOST_EXPORT_FUNCTION, 0, corrupt, NULL, NULL},
    {"callback", "((number)->number,number)->number", DEAL_HOST_EXPORT_FUNCTION, 0, callback, NULL, NULL}
};
const deal_host_module deal_host_module_probe = {DEAL_HOST_SPECIFIER_PROBE, deal_host_exports_probe, 5u, NULL, 0u, NULL};
