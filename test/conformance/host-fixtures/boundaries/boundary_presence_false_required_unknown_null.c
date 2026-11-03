#include "host/probe.h"
#include "native_helpers.h"
static deal_host_result impl_mutate(const deal_value *args,size_t argc){(void)argc;checked(deal_field_set(args[0],"x",null_value()));return result(undefined_value());}
const deal_host_export deal_host_exports_probe[] = {
  {"mutate", "(unknown)->void", DEAL_HOST_EXPORT_FUNCTION, 0, impl_mutate, NULL, NULL},
};
const deal_host_module deal_host_module_probe = {DEAL_HOST_SPECIFIER_PROBE, deal_host_exports_probe, sizeof deal_host_exports_probe / sizeof deal_host_exports_probe[0], NULL, 0u, NULL};
