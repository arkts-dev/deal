#include "host/nullable_fn.h"
#include "native_helpers.h"
static deal_host_result impl_register(const deal_value *args,size_t argc){(void)argc;deal_value_kind kind;checked(deal_kind_of(args[0],&kind));if(kind==DEAL_KIND_NULL||kind==DEAL_KIND_UNDEFINED)return result(number(0));deal_value a=number(41);return apply(args[0],&a,1u);}
const deal_host_export deal_host_exports_nullable_fn[] = {
  {"register", "(?(number)->number)->number", DEAL_HOST_EXPORT_FUNCTION, 0, impl_register, NULL, NULL},
};
const deal_host_module deal_host_module_nullable_fn = {DEAL_HOST_SPECIFIER_NULLABLE_FN, deal_host_exports_nullable_fn, sizeof deal_host_exports_nullable_fn / sizeof deal_host_exports_nullable_fn[0], NULL, 0u, NULL};
