#include "host/missing_export.h"
#include "native_helpers.h"
static deal_host_result impl_ping(const deal_value *args, size_t argc) { (void)args; (void)argc; return result(text("pong")); }

const deal_host_export deal_host_exports_missing_export[] = {
  {"ping", "()->string", DEAL_HOST_EXPORT_FUNCTION, 0, impl_ping, NULL, NULL},
};
const deal_host_module deal_host_module_missing_export = {DEAL_HOST_SPECIFIER_MISSING_EXPORT, deal_host_exports_missing_export, sizeof deal_host_exports_missing_export / sizeof deal_host_exports_missing_export[0], NULL, 0u, NULL};
