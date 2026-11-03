#include "host/nullreturn_bad.h"
#include "native_helpers.h"
static deal_host_result impl_ping(const deal_value *args, size_t argc) { (void)args; (void)argc; return result(text("junk")); }

const deal_host_export deal_host_exports_nullreturn_bad[] = {
  {"ping", "()->null", DEAL_HOST_EXPORT_FUNCTION, 0, impl_ping, NULL, NULL},
};
const deal_host_module deal_host_module_nullreturn_bad = {DEAL_HOST_SPECIFIER_NULLRETURN_BAD, deal_host_exports_nullreturn_bad, sizeof deal_host_exports_nullreturn_bad / sizeof deal_host_exports_nullreturn_bad[0], NULL, 0u, NULL};
