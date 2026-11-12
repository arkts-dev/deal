#include "host/probe.h"
#include "native_hostile.h"
static deal_handle saved;
static deal_array *source(void) {
    deal_check_input input; deal_error *cause=NULL;
    checked(deal_host_value_presentation(retained(saved,NULL),&input,&cause));
    if(cause || input.datum.tag!=DEAL_DATA_ARRAY) abort();
    return input.datum.reference;
}
static deal_host_result keep(const deal_value *args,size_t argc) {
    (void)argc; if(saved) checked(deal_release(saved)); checked(deal_retain(args[0],&saved));
    return result(undefined_value());
}
static deal_host_result replace(const deal_value *args,size_t argc) {
    (void)argc; double index; checked(deal_as_number(args[0],&index));
    deal_array *array=source(); deal_check_input input; deal_error *cause=NULL;
    checked(deal_host_value_presentation(args[1],&input,&cause));
    if(cause || input.presentation!=DEAL_PRESENT_DATUM
        || deal_container_array_element_layout(array)!=DEAL_LAYOUT_ELEMENT_REFERENCE
        || index<0 || index>=(double)deal_container_array_count(array)) abort();
    /* Strings, null and undefined use the reference slot's actual managed identity. */
    void *reference=input.datum.reference;
    if(input.datum.tag==DEAL_DATA_NULL) reference=deal_null_singleton;
    else if(input.datum.tag==DEAL_DATA_UNDEFINED) reference=deal_undefined_singleton;
    else if(input.datum.tag!=DEAL_DATA_STRING) abort();
    deal_container_array_store(array,(size_t)index,&reference);
    return result(undefined_value());
}
static deal_host_result append(const deal_value *args,size_t argc) {
    (void)argc; checked(deal_array_set(retained(saved,NULL),(int64_t)deal_container_array_count(source()),args[0]));
    return result(undefined_value());
}
static deal_host_result size(const deal_value *args,size_t argc) {
    (void)args;(void)argc;return result(number((double)deal_container_array_count(source())));
}
const deal_host_export deal_host_exports_probe[]={
    {"keep","(unknown)->void",DEAL_HOST_EXPORT_FUNCTION,0,keep,NULL,NULL},
    {"replace","(number,unknown)->void",DEAL_HOST_EXPORT_FUNCTION,0,replace,NULL,NULL},
    {"append","(string)->void",DEAL_HOST_EXPORT_FUNCTION,0,append,NULL,NULL},
    {"size","()->number",DEAL_HOST_EXPORT_FUNCTION,0,size,NULL,NULL}
};
const deal_host_module deal_host_module_probe={DEAL_HOST_SPECIFIER_PROBE,deal_host_exports_probe,4u,NULL,0u,NULL};
