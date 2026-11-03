#include "host/async_bad.h"
#include "native_helpers.h"
static deal_host_result impl_fetchValue(deal_async_token token, const deal_value *args, size_t argc) { (void)args; (void)argc; return complete(token,number(42)); }

const deal_host_export deal_host_exports_async_bad[] = {
  {"fetchValue", "async()->string", DEAL_HOST_EXPORT_ASYNC_FUNCTION, 1, NULL, impl_fetchValue, NULL},
};
const deal_host_module deal_host_module_async_bad = {DEAL_HOST_SPECIFIER_ASYNC_BAD, deal_host_exports_async_bad, sizeof deal_host_exports_async_bad / sizeof deal_host_exports_async_bad[0], NULL, 0u, NULL};
