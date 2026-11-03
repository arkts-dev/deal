#include "host/probe.h"
#include "native_helpers.h"

const deal_host_field deal_host_fields_probe_N[] = {
  {"x", "number", 0},
};
const deal_host_class deal_host_class_probe_N = {"@$external/host/probe/N", deal_host_fields_probe_N, 1u};
const deal_host_class deal_host_classes_probe[] = {
  {"@$external/host/probe/N", deal_host_fields_probe_N, 1u},
};
const deal_host_export deal_host_exports_probe[] = {
  {"N", "@$external/host/probe/N", DEAL_HOST_EXPORT_CLASS, 0, NULL, NULL, &deal_host_classes_probe[0]},
};
const deal_host_module deal_host_module_probe = {DEAL_HOST_SPECIFIER_PROBE, deal_host_exports_probe, sizeof deal_host_exports_probe / sizeof deal_host_exports_probe[0], deal_host_classes_probe, 1u, NULL};
