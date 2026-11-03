#include "host/prewrapped_ok.h"
#include "native_helpers.h"
static deal_host_result impl_ping(const deal_value *args, size_t argc) { (void)args; (void)argc; return result(null_value()); }
static deal_host_result impl_greet(const deal_value *args, size_t argc) {
 (void)argc; const void *p; size_t n; checked(deal_as_string(args[0],&p,&n));
 char *s=malloc(n+7u); if(s==NULL)abort(); memcpy(s,"hello ",6u);memcpy(s+6u,p,n);s[n+6u]=0;
 deal_value v=text(s);free(s);return result(v);
}
const deal_host_export deal_host_exports_prewrapped_ok[] = {
  {"greet", "(string)->string", DEAL_HOST_EXPORT_FUNCTION, 0, impl_greet, NULL, NULL},
  {"ping", "()->null", DEAL_HOST_EXPORT_FUNCTION, 0, impl_ping, NULL, NULL},
};
const deal_host_module deal_host_module_prewrapped_ok = {DEAL_HOST_SPECIFIER_PREWRAPPED_OK, deal_host_exports_prewrapped_ok, sizeof deal_host_exports_prewrapped_ok / sizeof deal_host_exports_prewrapped_ok[0], NULL, 0u, NULL};
