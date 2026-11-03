#include "host/probe.h"
#include "native_hostile.h"
static deal_handle saved;
static char *original;
static char invalid[] = "\xed\xa0\x80";
static deal_host_result impl_edit(const deal_value *args, size_t argc) {
    (void)argc;
    if (saved != 0u) abort();
    checked(deal_retain(args[0], &saved));
    original = fixture_error_text_replace(args[0], 1, invalid);
    return result(undefined_value());
}
static deal_host_result impl_repair(const deal_value *args, size_t argc) {
    (void)args; (void)argc;
    deal_value value;
    checked(deal_handle_value(saved, NULL, &value));
    (void)fixture_error_text_replace(value, 1, original);
    original = NULL;
    checked(deal_field_set(value, "message", text("repaired")));
    checked(deal_release(saved)); saved = 0u;
    return result(undefined_value());
}
const deal_host_export deal_host_exports_probe[] = {
    {"edit", "(Error)->void", DEAL_HOST_EXPORT_FUNCTION, 0, impl_edit, NULL, NULL},
    {"repair", "()->void", DEAL_HOST_EXPORT_FUNCTION, 0, impl_repair, NULL, NULL},
};
const deal_host_module deal_host_module_probe = {
    DEAL_HOST_SPECIFIER_PROBE, deal_host_exports_probe, 2u, NULL, 0u, NULL
};
