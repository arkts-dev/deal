#include "host/probe.h"
#include "native_hostile.h"
static deal_handle saved;
static deal_host_result keep(const deal_value *args, size_t argc) {
    (void)argc; if (saved) checked(deal_release(saved)); checked(deal_retain(args[0], &saved));
    return result(undefined_value());
}
static deal_host_result effect(const deal_value *args, size_t argc) {
    (void)args; (void)argc; deal_value array = retained(saved, NULL);
    fixture_number_element(array, 0u, text("bad")); fixture_number_element(array, 0u, number(3));
    return result(undefined_value());
}
static deal_host_result echo(const deal_value *args, size_t argc) { (void)argc; return result(args[0]); }
const deal_host_export deal_host_exports_probe[] = {
    {"keep", "(unknown)->void", DEAL_HOST_EXPORT_FUNCTION, 0, keep, NULL, NULL},
    {"effect", "()->void", DEAL_HOST_EXPORT_FUNCTION, 0, effect, NULL, NULL},
    {"echo", "(unknown)->unknown", DEAL_HOST_EXPORT_FUNCTION, 0, echo, NULL, NULL}
};
const deal_host_module deal_host_module_probe = {DEAL_HOST_SPECIFIER_PROBE, deal_host_exports_probe, 3u, NULL, 0u, NULL};
