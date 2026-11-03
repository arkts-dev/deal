#include "host/probe.h"
#include "native_helpers.h"
static deal_host_result impl_mutate(const deal_value *args,size_t argc){(void)argc;checked(deal_field_set(args[0],"x",number(2)));return result(undefined_value());}
const deal_host_field deal_host_fields_probe_Node[] = {
  {"x", "unknown", 0},
  {"next", "?@$external/host/probe/Node", 0},
};
const deal_host_class deal_host_class_probe_Node = {"@$external/host/probe/Node", deal_host_fields_probe_Node, 2u};
const deal_host_class deal_host_classes_probe[] = {
  {"@$external/host/probe/Node", deal_host_fields_probe_Node, 2u},
};
const deal_host_export deal_host_exports_probe[] = {
  {"Node", "@$external/host/probe/Node", DEAL_HOST_EXPORT_CLASS, 0, NULL, NULL, &deal_host_classes_probe[0]},
  {"mutate", "(@$external/host/probe/Node)->void", DEAL_HOST_EXPORT_FUNCTION, 0, impl_mutate, NULL, NULL},
};
const deal_host_module deal_host_module_probe = {DEAL_HOST_SPECIFIER_PROBE, deal_host_exports_probe, sizeof deal_host_exports_probe / sizeof deal_host_exports_probe[0], deal_host_classes_probe, 1u, NULL};
