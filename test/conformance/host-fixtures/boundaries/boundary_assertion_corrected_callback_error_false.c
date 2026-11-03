#include "host/probe.h"
#include "native_helpers.h"
static deal_host_result impl_invoke(const deal_value *args,size_t argc){(void)args;(void)argc;deal_host_result r=apply(args[0],NULL,0);if(r.failed)return r;deal_value v=undefined_value();return result(v);}
const deal_host_export deal_host_exports_probe[] = {
  {"invoke", "(()->void)->void", DEAL_HOST_EXPORT_FUNCTION, 0, impl_invoke, NULL, NULL},
};
const deal_host_module deal_host_module_probe = {DEAL_HOST_SPECIFIER_PROBE, deal_host_exports_probe, sizeof deal_host_exports_probe / sizeof deal_host_exports_probe[0], NULL, 0u, NULL};
