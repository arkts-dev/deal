#include "host/probe.h"
#include "native_helpers.h"
static deal_host_result impl_mutate(deal_async_token token,const deal_value *args,size_t argc){(void)argc;set(args[0],"bad",number(3));return complete(token,undefined_value());}
const deal_host_export deal_host_exports_probe[] = {
  {"mutate", "async(record)->void", DEAL_HOST_EXPORT_ASYNC_FUNCTION, 1, NULL, impl_mutate, NULL},
};
const deal_host_module deal_host_module_probe = {DEAL_HOST_SPECIFIER_PROBE, deal_host_exports_probe, sizeof deal_host_exports_probe / sizeof deal_host_exports_probe[0], NULL, 0u, NULL};
