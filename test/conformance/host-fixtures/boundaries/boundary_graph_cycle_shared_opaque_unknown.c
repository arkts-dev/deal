#include "host/probe.h"
#include "native_helpers.h"
static deal_host_result f(const deal_value *args, size_t argc) {
    (void)args; (void)argc;
    deal_value record, child, atom, opaque;
    checked(deal_table_new(&record)); checked(deal_table_new(&child));
    set(child, "v", number(1));
    checked(deal_class_atom("@host/Opaque", &atom)); checked(deal_class_new(atom, &opaque));
    set(record, "self", record); set(record, "a", child); set(record, "b", child);
    set(record, "bad", opaque); return result(record);
}
const deal_host_export deal_host_exports_probe[] = {
    {"f", "()->unknown", DEAL_HOST_EXPORT_FUNCTION, 0, f, NULL, NULL}
};
const deal_host_module deal_host_module_probe = {DEAL_HOST_SPECIFIER_PROBE, deal_host_exports_probe, 1u, NULL, 0u, NULL};
