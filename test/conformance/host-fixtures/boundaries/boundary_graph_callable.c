#include "host/probe.h"
#include "native_hostile.h"

static deal_handle saved;
static deal_host_result corrupt(const deal_value *args, size_t argc) {
    (void)argc;
    if (saved != 0u) checked(deal_release(saved));
    checked(deal_retain(args[0], &saved));
    deal_value callback;
    checked(deal_function_new(fixture_seven, "()->number", &callback));
    /* Admit the valid callable into the original Record, then remove only its
     * executable payload. This must reach the later DEAL graph boundary. */
    set(args[0], "bad", callback);
    fixture_remove_function_payload(callback);
    return result(undefined_value());
}
static deal_host_result repair(const deal_value *args, size_t argc) {
    (void)args; (void)argc;
    deal_value table;
    checked(deal_handle_value(saved, NULL, &table));
    checked(deal_table_delete(table, "bad", 3u));
    checked(deal_release(saved));
    saved = 0u;
    return result(undefined_value());
}
const deal_host_export deal_host_exports_probe[] = {
    {"corrupt", "(record)->void", DEAL_HOST_EXPORT_FUNCTION, 0, corrupt, NULL, NULL},
    {"repair", "()->void", DEAL_HOST_EXPORT_FUNCTION, 0, repair, NULL, NULL},
};
const deal_host_module deal_host_module_probe = {
    DEAL_HOST_SPECIFIER_PROBE, deal_host_exports_probe,
    sizeof deal_host_exports_probe / sizeof deal_host_exports_probe[0], NULL, 0u, NULL
};
