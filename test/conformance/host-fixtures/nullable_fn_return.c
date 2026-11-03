#include "host/nullable_fn_return.h"
#include "native_helpers.h"
static deal_host_result raw(const deal_value *args,size_t argc){(void)argc;return result(args[0]);}
static deal_host_result impl_getCallback(const deal_value *args,size_t argc){(void)argc;const void *p;size_t n;checked(deal_as_string(args[0],&p,&n));if(n==3u&&!memcmp(p,"bad",3u)){deal_value fn;checked(deal_function_new(raw,"()->number",&fn));return result(fn);}return result(null_value());}
const deal_host_export deal_host_exports_nullable_fn_return[] = {
  {"getCallback", "(string)->?(number)->number", DEAL_HOST_EXPORT_FUNCTION, 0, impl_getCallback, NULL, NULL},
};
const deal_host_module deal_host_module_nullable_fn_return = {DEAL_HOST_SPECIFIER_NULLABLE_FN_RETURN, deal_host_exports_nullable_fn_return, sizeof deal_host_exports_nullable_fn_return / sizeof deal_host_exports_nullable_fn_return[0], NULL, 0u, NULL};
