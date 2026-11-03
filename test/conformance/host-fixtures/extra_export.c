#include "host/extra_export.h"
#include "native_helpers.h"
static deal_host_result impl_ping(const deal_value *args, size_t argc) { (void)args; (void)argc; return result(text("pong")); }

const deal_host_export deal_host_exports_extra_export[] = {
  {"ping", "()->string", DEAL_HOST_EXPORT_FUNCTION, 0, impl_ping, NULL, NULL},
};
const deal_host_module deal_host_module_extra_export = {DEAL_HOST_SPECIFIER_EXTRA_EXPORT, deal_host_exports_extra_export, sizeof deal_host_exports_extra_export / sizeof deal_host_exports_extra_export[0], NULL, 0u, NULL};
