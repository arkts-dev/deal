#include "host/async_shape_bad.h"
#include "native_helpers.h"
static deal_host_result impl_fetchValue(deal_async_token token,const deal_value *args,size_t argc){(void)token;(void)args;(void)argc;return result(number(42));}
const deal_host_export deal_host_exports_async_shape_bad[] = {
  {"fetchValue", "async()->string", DEAL_HOST_EXPORT_ASYNC_FUNCTION, 1, NULL, impl_fetchValue, NULL},
};
const deal_host_module deal_host_module_async_shape_bad = {DEAL_HOST_SPECIFIER_ASYNC_SHAPE_BAD, deal_host_exports_async_shape_bad, sizeof deal_host_exports_async_shape_bad / sizeof deal_host_exports_async_shape_bad[0], NULL, 0u, NULL};
