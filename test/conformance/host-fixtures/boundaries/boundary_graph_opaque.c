#include "host/probe.h"
#include "native_hostile.h"
static deal_handle saved;
static deal_host_result corrupt(const deal_value *args, size_t argc) {
    (void)argc; if (saved) checked(deal_release(saved)); checked(deal_retain(args[0], &saved));
    fixture_record_opaque(args[0]);
    deal_value untouched = number(123), before = untouched;
    if (deal_table_get(args[0], "bad", 3u, &untouched) != DEAL_STATUS_KIND
            || memcmp(&untouched, &before, sizeof(before)) != 0) abort();
    return result(undefined_value());
}
static deal_host_result repair(const deal_value *args, size_t argc) {
    (void)args; (void)argc; checked(deal_table_delete(retained(saved, NULL), "bad", 3u));
    checked(deal_release(saved)); saved = 0u; return result(undefined_value());
}
const deal_host_export deal_host_exports_probe[] = {
    {"corrupt", "(record)->void", DEAL_HOST_EXPORT_FUNCTION, 0, corrupt, NULL, NULL},
    {"repair", "()->void", DEAL_HOST_EXPORT_FUNCTION, 0, repair, NULL, NULL}
};
const deal_host_module deal_host_module_probe = {DEAL_HOST_SPECIFIER_PROBE, deal_host_exports_probe, 2u, NULL, 0u, NULL};
