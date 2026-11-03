#include "host/container_mutation.h"
#include "native_helpers.h"
static deal_host_result replace(const deal_value *args,size_t index){
 deal_value v=get(args[0],"slots"),r=get(args[1],"value");deal_check_input input,replacement;deal_error *cause=NULL;
 checked(deal_host_value_presentation(v,&input,&cause));checked(deal_host_value_presentation(r,&replacement,&cause));
 if(input.datum.tag!=DEAL_DATA_ARRAY||replacement.datum.tag!=DEAL_DATA_ARRAY)abort();
 deal_array *a=input.datum.reference,*b=replacement.datum.reference;
 if(deal_container_array_element_layout(a)!=deal_container_array_element_layout(b))abort();
 deal_container_array_store(a,index,deal_container_array_element(b,index));return result(boolean(1));
}
static deal_host_result impl_replaceFirst(const deal_value *args,size_t argc){(void)argc;return replace(args,0);}
static deal_host_result impl_replaceAt(const deal_value *args,size_t argc){(void)argc;return replace(args,(size_t)as_number(args[2]));}

const deal_host_export deal_host_exports_container_mutation[] = {
  {"replaceFirst", "(record,record)->boolean", DEAL_HOST_EXPORT_FUNCTION, 0, impl_replaceFirst, NULL, NULL},
  {"replaceAt", "(record,record,number)->boolean", DEAL_HOST_EXPORT_FUNCTION, 0, impl_replaceAt, NULL, NULL},
};
const deal_host_module deal_host_module_container_mutation = {DEAL_HOST_SPECIFIER_CONTAINER_MUTATION, deal_host_exports_container_mutation, sizeof deal_host_exports_container_mutation / sizeof deal_host_exports_container_mutation[0], NULL, 0u, NULL};
