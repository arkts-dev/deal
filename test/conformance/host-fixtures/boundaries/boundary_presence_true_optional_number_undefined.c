#include "host/probe.h"
#include "native_helpers.h"
static deal_host_result impl_mutate(const deal_value *args,size_t argc){(void)argc;checked(deal_field_set(args[0],"x",undefined_value()));return result(undefined_value());}
const deal_host_field deal_host_fields_probe_C[] = {
  {"x", "number", 1},
};
const deal_host_class deal_host_class_probe_C = {"@$external/host/probe/C", deal_host_fields_probe_C, 1u};
const deal_host_class deal_host_classes_probe[] = {
  {"@$external/host/probe/C", deal_host_fields_probe_C, 1u},
};
const deal_host_export deal_host_exports_probe[] = {
  {"C", "@$external/host/probe/C", DEAL_HOST_EXPORT_CLASS, 0, NULL, NULL, &deal_host_classes_probe[0]},
  {"mutate", "(unknown)->void", DEAL_HOST_EXPORT_FUNCTION, 0, impl_mutate, NULL, NULL},
};
const deal_host_module deal_host_module_probe = {DEAL_HOST_SPECIFIER_PROBE, deal_host_exports_probe, sizeof deal_host_exports_probe / sizeof deal_host_exports_probe[0], deal_host_classes_probe, 1u, NULL};
