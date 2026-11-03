#include "host/probe.h"
#include "native_helpers.h"
static deal_host_result impl_f(const deal_value *args,size_t argc){(void)args;(void)argc;deal_value v,c;checked(deal_table_new(&v));checked(deal_table_new(&c));set(c,"v",number(1));set(v,"self",v);set(v,"a",c);set(v,"b",c);deal_value bad;checked(deal_string_new("\xed\xa0\x80",3,&bad));set(v,"bad",bad);return result(v);}
const deal_host_export deal_host_exports_probe[] = {
  {"f", "()->record", DEAL_HOST_EXPORT_FUNCTION, 0, impl_f, NULL, NULL},
};
const deal_host_module deal_host_module_probe = {DEAL_HOST_SPECIFIER_PROBE, deal_host_exports_probe, sizeof deal_host_exports_probe / sizeof deal_host_exports_probe[0], NULL, 0u, NULL};
