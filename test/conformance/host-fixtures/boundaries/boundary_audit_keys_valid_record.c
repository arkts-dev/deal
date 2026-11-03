#include "host/probe.h"
#include "native_helpers.h"
static deal_host_result impl_get(const deal_value *args,size_t argc){(void)args;(void)argc;deal_value v;checked(deal_table_new(&v));set(v,"good",number(1));set(v,"legal",number(2));return result(v);}
const deal_host_export deal_host_exports_probe[] = {
  {"get", "()->record", DEAL_HOST_EXPORT_FUNCTION, 0, impl_get, NULL, NULL},
};
const deal_host_module deal_host_module_probe = {DEAL_HOST_SPECIFIER_PROBE, deal_host_exports_probe, sizeof deal_host_exports_probe / sizeof deal_host_exports_probe[0], NULL, 0u, NULL};
