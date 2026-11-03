#include "host/probe.h"
#include "native_hostile.h"
static deal_host_result mutate(const deal_value *args, size_t argc) {
    (void)argc; fixture_undefined_field_to_null(args[0], "x"); return result(undefined_value());
}
const deal_host_field deal_host_fields_probe_C[] = {{"x", "undefined", 0}};
const deal_host_class deal_host_class_probe_C = {DEAL_HOST_CLASS_ATOM_PROBE_C, deal_host_fields_probe_C, 1u};
const deal_host_class deal_host_classes_probe[] = {{DEAL_HOST_CLASS_ATOM_PROBE_C, deal_host_fields_probe_C, 1u}};
const deal_host_export deal_host_exports_probe[] = {
    {"C", DEAL_HOST_CLASS_ATOM_PROBE_C, DEAL_HOST_EXPORT_CLASS, 0, NULL, NULL, deal_host_classes_probe},
    {"mutate", "(unknown)->void", DEAL_HOST_EXPORT_FUNCTION, 0, mutate, NULL, NULL}
};
const deal_host_module deal_host_module_probe = {
    DEAL_HOST_SPECIFIER_PROBE, deal_host_exports_probe, 2u, deal_host_classes_probe, 1u, NULL
};
