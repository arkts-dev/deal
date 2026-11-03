#include "host/array_return.h"
#include "native_helpers.h"
static deal_host_result impl_split(const deal_value *args,size_t argc){(void)args;(void)argc;deal_value a=array("string",3);checked(deal_array_set(a,0,text("a")));checked(deal_array_set(a,1,text("b")));checked(deal_array_set(a,2,text("c")));return result(a);}
const deal_host_export deal_host_exports_array_return[] = {
  {"split", "(string)->[string]", DEAL_HOST_EXPORT_FUNCTION, 0, impl_split, NULL, NULL},
};
const deal_host_module deal_host_module_array_return = {DEAL_HOST_SPECIFIER_ARRAY_RETURN, deal_host_exports_array_return, sizeof deal_host_exports_array_return / sizeof deal_host_exports_array_return[0], NULL, 0u, NULL};
