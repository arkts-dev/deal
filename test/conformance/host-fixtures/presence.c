#include "host/presence.h"
#include "native_helpers.h"
static deal_host_result impl_ping(const deal_value *args, size_t argc) { (void)args; (void)argc; return result(text("pong")); }

const deal_host_field deal_host_fields_presence_Config[] = {
  {"port", "number", 0},
  {"fallback", "?" DEAL_HOST_CLASS_ATOM_PRESENCE_CONFIG, 1},
  {"peers", "[" DEAL_HOST_CLASS_ATOM_PRESENCE_CONFIG "]", 1},
};
const deal_host_class deal_host_class_presence_Config = {DEAL_HOST_CLASS_ATOM_PRESENCE_CONFIG, deal_host_fields_presence_Config, 3u};
const deal_host_class deal_host_classes_presence[] = {
  {DEAL_HOST_CLASS_ATOM_PRESENCE_CONFIG, deal_host_fields_presence_Config, 3u},
};
const deal_host_export deal_host_exports_presence[] = {
  {"Config", DEAL_HOST_CLASS_ATOM_PRESENCE_CONFIG, DEAL_HOST_EXPORT_CLASS, 0, NULL, NULL, &deal_host_classes_presence[0]},
  {"ping", "()->string", DEAL_HOST_EXPORT_FUNCTION, 0, impl_ping, NULL, NULL},
};
const deal_host_module deal_host_module_presence = {DEAL_HOST_SPECIFIER_PRESENCE, deal_host_exports_presence, sizeof deal_host_exports_presence / sizeof deal_host_exports_presence[0], deal_host_classes_presence, 1u, NULL};
