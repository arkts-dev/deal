#include "host/async_ok.h"
#include "native_helpers.h"
static deal_host_result impl_fetchValue(deal_async_token token, const deal_value *args, size_t argc) { (void)args; (void)argc; return complete(token,text("fetched")); }

const deal_host_export deal_host_exports_async_ok[] = {
  {"fetchValue", "async()->string", DEAL_HOST_EXPORT_ASYNC_FUNCTION, 1, NULL, impl_fetchValue, NULL},
};
const deal_host_module deal_host_module_async_ok = {DEAL_HOST_SPECIFIER_ASYNC_OK, deal_host_exports_async_ok, sizeof deal_host_exports_async_ok / sizeof deal_host_exports_async_ok[0], NULL, 0u, NULL};
