#include "host/function_binding.h"
#include "native_helpers.h"
static char trace[1024]; static size_t count;
static deal_host_result impl_inc(const deal_value *args,size_t argc){(void)argc;trace[count++]='I';return result(number(as_number(args[0])+1));}
static deal_host_result impl_dec(const deal_value *args,size_t argc){(void)argc;trace[count++]='D';return result(number(as_number(args[0])-1));}
static deal_host_result impl_trace(const deal_value *args, size_t argc) { (void)args; (void)argc; return result(text(trace)); }

const deal_host_export deal_host_exports_function_binding[] = {
  {"inc", "(number)->number", DEAL_HOST_EXPORT_FUNCTION, 0, impl_inc, NULL, NULL},
  {"dec", "(number)->number", DEAL_HOST_EXPORT_FUNCTION, 0, impl_dec, NULL, NULL},
  {"trace", "()->string", DEAL_HOST_EXPORT_FUNCTION, 0, impl_trace, NULL, NULL},
};
const deal_host_module deal_host_module_function_binding = {DEAL_HOST_SPECIFIER_FUNCTION_BINDING, deal_host_exports_function_binding, sizeof deal_host_exports_function_binding / sizeof deal_host_exports_function_binding[0], NULL, 0u, NULL};
