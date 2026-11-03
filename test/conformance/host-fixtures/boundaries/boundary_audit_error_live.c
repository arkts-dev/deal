#include "host/probe.h"
#include "native_helpers.h"

static deal_handle saved;
static deal_host_result impl_edit(const deal_value *args, size_t argc) {
    (void)argc;
    if (saved) checked(deal_release(saved));
    checked(deal_retain(args[0], &saved));
    checked(deal_field_set(args[0], "message", text("changed")));
    return result(undefined_value());
}
static deal_host_result impl_read(const deal_value *args, size_t argc) {
    (void)argc;
    deal_check_input input;
    deal_error *cause = NULL;
    checked(deal_host_value_presentation(args[0], &input, &cause));
    deal_check_input held;
    checked(deal_host_value_presentation(retained(saved, "Error"), &held, &cause));
    if (input.datum.reference != held.datum.reference) return result(text("LOST_IDENTITY"));
    return result(field(args[0], "message"));
}
const deal_host_export deal_host_exports_probe[] = {
    {"edit", "(Error)->void", DEAL_HOST_EXPORT_FUNCTION, 0, impl_edit, NULL, NULL},
    {"read", "(Error)->string", DEAL_HOST_EXPORT_FUNCTION, 0, impl_read, NULL, NULL},
};
const deal_host_module deal_host_module_probe = {
    DEAL_HOST_SPECIFIER_PROBE, deal_host_exports_probe,
    sizeof deal_host_exports_probe / sizeof deal_host_exports_probe[0], NULL, 0u, NULL
};
