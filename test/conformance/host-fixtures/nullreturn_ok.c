#include "host/nullreturn_ok.h"
#include "native_helpers.h"
static deal_host_result impl_ping(const deal_value *args, size_t argc) { (void)args; (void)argc; return result(null_value()); }

const deal_host_export deal_host_exports_nullreturn_ok[] = {
  {"ping", "()->null", DEAL_HOST_EXPORT_FUNCTION, 0, impl_ping, NULL, NULL},
};
const deal_host_module deal_host_module_nullreturn_ok = {DEAL_HOST_SPECIFIER_NULLRETURN_OK, deal_host_exports_nullreturn_ok, sizeof deal_host_exports_nullreturn_ok / sizeof deal_host_exports_nullreturn_ok[0], NULL, 0u, NULL};
