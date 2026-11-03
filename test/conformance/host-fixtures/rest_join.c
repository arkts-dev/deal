#include "host/rest_join.h"
#include "native_helpers.h"
static deal_host_result impl_join(const deal_value *args,size_t argc){
 (void)argc;const void *sep;size_t sn;checked(deal_as_string(args[0],&sep,&sn));int64_t count;checked(deal_array_length(args[1],&count));
 size_t length=0;for(int64_t i=0;i<count;i++){const void *p;size_t n;checked(deal_as_string(at(args[1],i),&p,&n));length+=n+(i==0?0:sn);}
 char *s=malloc(length+1u);if(s==NULL)abort();size_t offset=0;
 for(int64_t i=0;i<count;i++){const void *p;size_t n;checked(deal_as_string(at(args[1],i),&p,&n));if(i){memcpy(s+offset,sep,sn);offset+=sn;}memcpy(s+offset,p,n);offset+=n;}
 s[length]=0;deal_value v=text(s);free(s);return result(v);
}
const deal_host_export deal_host_exports_rest_join[] = {
  {"join", "(string,[string])->string", DEAL_HOST_EXPORT_FUNCTION, 0, impl_join, NULL, NULL},
};
const deal_host_module deal_host_module_rest_join = {DEAL_HOST_SPECIFIER_REST_JOIN, deal_host_exports_rest_join, sizeof deal_host_exports_rest_join / sizeof deal_host_exports_rest_join[0], NULL, 0u, NULL};
