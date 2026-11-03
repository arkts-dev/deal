#include "host/signature_mutation.h"
#include "native_helpers.h"
/* Fixture-only mirror: mutate the admitted carrier, not a replacement. */
struct signature_carrier {deal_fn_thunk thunk;deal_descriptor *signature;void *environment;uint32_t capture_count,is_async;};
static deal_host_result impl_replaceSignature(const deal_value *args,size_t argc){
 (void)argc;deal_value v=get(args[0],"value");deal_check_input input;deal_error *cause=NULL;
 if(deal_host_value_presentation(v,&input,&cause)!=DEAL_OK||input.datum.tag!=DEAL_DATA_FUNCTION)return result(boolean(0));
 const void *p;size_t n;checked(deal_as_string(args[1],&p,&n));char *s=malloc(n+1u);if(s==NULL)abort();memcpy(s,p,n);s[n]=0;
 ((struct signature_carrier*)input.datum.reference)->signature=deal_descriptor_intern(s);free(s);return result(boolean(1));
}
const deal_host_export deal_host_exports_signature_mutation[] = {
  {"replaceSignature", "(record,string)->boolean", DEAL_HOST_EXPORT_FUNCTION, 0, impl_replaceSignature, NULL, NULL},
};
const deal_host_module deal_host_module_signature_mutation = {DEAL_HOST_SPECIFIER_SIGNATURE_MUTATION, deal_host_exports_signature_mutation, sizeof deal_host_exports_signature_mutation / sizeof deal_host_exports_signature_mutation[0], NULL, 0u, NULL};
