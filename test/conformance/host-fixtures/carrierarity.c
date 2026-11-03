#include "host/carrierarity.h"
#include "native_helpers.h"
#include <stdio.h>
static deal_host_result impl_describeAsync(deal_async_token token,const deal_value *args,size_t argc){
 const void *p;size_t n;checked(deal_as_string(field(args[0],"name"),&p,&n));
 deal_value a=number(41);deal_host_result r=apply(args[1],&a,1u);if(r.failed)return r;
 char *s=malloc(n+100u);if(s==NULL)abort();memcpy(s,"T|",2u);memcpy(s+2u,p,n);
 snprintf(s+2u+n,98u,"|true:%.17g|n=%zu",as_number(r.value),argc);deal_value v=text(s);free(s);return complete(token,v);
}
const deal_host_field deal_host_fields_carrierarity_Target[] = {
  {"name", "string", 0},
};
const deal_host_class deal_host_class_carrierarity_Target = {"@$external/host/carrierarity/Target", deal_host_fields_carrierarity_Target, 1u};
const deal_host_class deal_host_classes_carrierarity[] = {
  {"@$external/host/carrierarity/Target", deal_host_fields_carrierarity_Target, 1u},
};
const deal_host_export deal_host_exports_carrierarity[] = {
  {"Target", "@$external/host/carrierarity/Target", DEAL_HOST_EXPORT_CLASS, 0, NULL, NULL, &deal_host_classes_carrierarity[0]},
  {"describeAsync", "async(@$external/host/carrierarity/Target,(number)->number)->string", DEAL_HOST_EXPORT_ASYNC_FUNCTION, 1, NULL, impl_describeAsync, NULL},
};
const deal_host_module deal_host_module_carrierarity = {DEAL_HOST_SPECIFIER_CARRIERARITY, deal_host_exports_carrierarity, sizeof deal_host_exports_carrierarity / sizeof deal_host_exports_carrierarity[0], deal_host_classes_carrierarity, 1u, NULL};
