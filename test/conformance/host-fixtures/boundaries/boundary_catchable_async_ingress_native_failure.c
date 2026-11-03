#include "host/probe.h"
#include "native_helpers.h"
static deal_host_result impl_f(deal_async_token token,const deal_value *args,size_t argc){(void)args;(void)argc;deal_host_result failure=host_error("HOST_NATIVE","native");checked(deal_async_complete_error(token,&failure.failure));deal_value op;checked(deal_async_operation(token,&op));return result(op);}
const deal_host_export deal_host_exports_probe[] = {
  {"f", "async()->unknown", DEAL_HOST_EXPORT_ASYNC_FUNCTION, 1, NULL, impl_f, NULL},
};
const deal_host_module deal_host_module_probe = {DEAL_HOST_SPECIFIER_PROBE, deal_host_exports_probe, sizeof deal_host_exports_probe / sizeof deal_host_exports_probe[0], NULL, 0u, NULL};
