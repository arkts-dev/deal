#include "host/retained_default.h"
#include "native_helpers.h"
static deal_handle shared;static int calls;static char order[1024];static size_t used;
static deal_value shared_value(void){if(shared==0){deal_value v=array("number",2);checked(deal_array_set(v,0,number(1)));checked(deal_array_set(v,1,number(2)));checked(deal_retain(v,&shared));}return retained(shared,"[number]");}
static deal_host_result impl_provideFirst(const deal_value *args,size_t argc){(void)args;(void)argc;calls++;order[used++]='A';return result(shared_value());}
static deal_host_result impl_provideSecond(const deal_value *args,size_t argc){(void)args;(void)argc;calls++;order[used++]='B';return result(shared_value());}
static deal_host_result impl_bump(const deal_value *args,size_t argc){(void)args;(void)argc;calls++;return result(null_value());}
static deal_host_result impl_retained(const deal_value *args, size_t argc) { (void)args; (void)argc; return result(shared_value()); }
static deal_host_result impl_calls(const deal_value *args, size_t argc) { (void)args; (void)argc; return result(number(calls)); }
static deal_host_result impl_order(const deal_value *args, size_t argc) { (void)args; (void)argc; return result(text(order)); }

const deal_host_export deal_host_exports_retained_default[] = {
  {"retained", "()->[number]", DEAL_HOST_EXPORT_FUNCTION, 0, impl_retained, NULL, NULL},
  {"provideFirst", "()->[number]", DEAL_HOST_EXPORT_FUNCTION, 0, impl_provideFirst, NULL, NULL},
  {"provideSecond", "()->[number]", DEAL_HOST_EXPORT_FUNCTION, 0, impl_provideSecond, NULL, NULL},
  {"bump", "()->null", DEAL_HOST_EXPORT_FUNCTION, 0, impl_bump, NULL, NULL},
  {"calls", "()->number", DEAL_HOST_EXPORT_FUNCTION, 0, impl_calls, NULL, NULL},
  {"order", "()->string", DEAL_HOST_EXPORT_FUNCTION, 0, impl_order, NULL, NULL},
};
const deal_host_module deal_host_module_retained_default = {DEAL_HOST_SPECIFIER_RETAINED_DEFAULT, deal_host_exports_retained_default, sizeof deal_host_exports_retained_default / sizeof deal_host_exports_retained_default[0], NULL, 0u, NULL};
