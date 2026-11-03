#include "host/nullable_return.h"
#include "native_helpers.h"
static deal_host_result impl_find(const deal_value *args,size_t argc){(void)argc;const void *p;size_t n;checked(deal_as_string(args[0],&p,&n));if(n==8u&&!memcmp(p,"__NULL__",8u))return result(null_value());if(n==7u&&!memcmp(p,"__BAD__",7u))return result(number(42));return result(args[0]);}
const deal_host_export deal_host_exports_nullable_return[] = {
  {"find", "(string)->?string", DEAL_HOST_EXPORT_FUNCTION, 0, impl_find, NULL, NULL},
};
const deal_host_module deal_host_module_nullable_return = {DEAL_HOST_SPECIFIER_NULLABLE_RETURN, deal_host_exports_nullable_return, sizeof deal_host_exports_nullable_return / sizeof deal_host_exports_nullable_return[0], NULL, 0u, NULL};
