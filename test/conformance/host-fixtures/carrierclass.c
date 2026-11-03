#include "host/carrierclass.h"
#include "native_helpers.h"
static deal_value config(void){deal_value v;checked(deal_host_carrierclass_Config_new(&v));checked(deal_field_set(v,"value",number(42)));return v;}
static deal_host_result impl_describeAsync(deal_async_token token,const deal_value *args,size_t argc){(void)argc;return complete(token,field(args[0],"value"));}
static deal_host_result impl_makeAsync(deal_async_token token,const deal_value *args,size_t argc){(void)args;(void)argc;return complete(token,config());}
static deal_host_result impl_makeAll(const deal_value *args,size_t argc){(void)args;(void)argc;deal_value v=config(),a=array("@$external/host/carrierclass/Config",2);checked(deal_array_set(a,0,v));checked(deal_array_set(a,1,v));return result(a);}
static deal_host_result impl_maybeMake(const deal_value *args,size_t argc){(void)argc;return result(as_boolean(args[0])?config():null_value());}
static deal_host_result impl_describeOrNull(const deal_value *args,size_t argc){(void)argc;deal_value_kind k;checked(deal_kind_of(args[0],&k));return result(k==DEAL_KIND_NULL||k==DEAL_KIND_UNDEFINED?number(-1):field(args[0],"value"));}
static deal_host_result impl_maybeMakeAsync(deal_async_token token,const deal_value *args,size_t argc){(void)argc;return complete(token,as_boolean(args[0])?config():null_value());}
static deal_host_result impl_makeHolder(const deal_value *args,size_t argc){(void)args;(void)argc;deal_value v;checked(deal_host_carrierclass_Holder_new(&v));checked(deal_field_set(v,"label",text("holder")));checked(deal_field_set(v,"primary",config()));checked(deal_field_set(v,"optional",null_value()));deal_value a=array("@$external/host/carrierclass/Config",1);checked(deal_array_set(a,0,config()));checked(deal_field_set(v,"items",a));return result(v);}
static deal_host_result impl_consume(const deal_value *args,size_t argc){(void)argc;deal_value a=config();deal_host_result r=apply(args[0],&a,1u);if(r.failed){if(r.failure.kind==DEAL_FAILURE_DEAL_ERROR)checked(deal_failure_discard(r.failure.error));return result(number(-4));}return result(field(r.value,"value"));}
static deal_host_result impl_makeWrongElement(const deal_value *args,size_t argc){(void)args;(void)argc;deal_value a=array("@$external/host/carrierclass/Config",2);checked(deal_array_set(a,0,config()));checked(deal_array_set(a,1,text("junk")));return result(a);}
static deal_host_result impl_makeSparse(const deal_value *args,size_t argc){(void)args;(void)argc;deal_value a=array("@$external/host/carrierclass/Config",3);checked(deal_array_set(a,0,config()));checked(deal_array_set(a,2,config()));return result(a);}
static deal_host_result impl_makeMap(const deal_value *args,size_t argc){(void)args;(void)argc;deal_value v;checked(deal_table_new(&v));set(v,"alpha",config());return result(v);}
static deal_host_result impl_makeSync(const deal_value *args, size_t argc) { (void)args; (void)argc; return result(config()); }
static deal_host_result impl_makeWrongKind(const deal_value *args, size_t argc) { (void)args; (void)argc; return result(number(42)); }
static deal_host_result impl_makeNullClass(const deal_value *args, size_t argc) { (void)args; (void)argc; return result(null_value()); }

const deal_host_field deal_host_fields_carrierclass_Config[] = {
  {"value", "number", 0},
};
const deal_host_class deal_host_class_carrierclass_Config = {"@$external/host/carrierclass/Config", deal_host_fields_carrierclass_Config, 1u};
const deal_host_field deal_host_fields_carrierclass_Holder[] = {
  {"label", "string", 0},
  {"primary", "@$external/host/carrierclass/Config", 0},
  {"optional", "?@$external/host/carrierclass/Config", 0},
  {"maybe", "?@$external/host/carrierclass/Config", 1},
  {"items", "[@$external/host/carrierclass/Config]", 0},
};
const deal_host_class deal_host_class_carrierclass_Holder = {"@$external/host/carrierclass/Holder", deal_host_fields_carrierclass_Holder, 5u};
const deal_host_class deal_host_classes_carrierclass[] = {
  {"@$external/host/carrierclass/Config", deal_host_fields_carrierclass_Config, 1u},
  {"@$external/host/carrierclass/Holder", deal_host_fields_carrierclass_Holder, 5u},
};
const deal_host_export deal_host_exports_carrierclass[] = {
  {"Config", "@$external/host/carrierclass/Config", DEAL_HOST_EXPORT_CLASS, 0, NULL, NULL, &deal_host_classes_carrierclass[0]},
  {"Holder", "@$external/host/carrierclass/Holder", DEAL_HOST_EXPORT_CLASS, 0, NULL, NULL, &deal_host_classes_carrierclass[1]},
  {"describeAsync", "async(@$external/host/carrierclass/Config)->number", DEAL_HOST_EXPORT_ASYNC_FUNCTION, 1, NULL, impl_describeAsync, NULL},
  {"makeAsync", "async()->@$external/host/carrierclass/Config", DEAL_HOST_EXPORT_ASYNC_FUNCTION, 1, NULL, impl_makeAsync, NULL},
  {"makeSync", "()->@$external/host/carrierclass/Config", DEAL_HOST_EXPORT_FUNCTION, 0, impl_makeSync, NULL, NULL},
  {"makeAll", "()->[@$external/host/carrierclass/Config]", DEAL_HOST_EXPORT_FUNCTION, 0, impl_makeAll, NULL, NULL},
  {"maybeMake", "(boolean)->?@$external/host/carrierclass/Config", DEAL_HOST_EXPORT_FUNCTION, 0, impl_maybeMake, NULL, NULL},
  {"describeOrNull", "(?@$external/host/carrierclass/Config)->number", DEAL_HOST_EXPORT_FUNCTION, 0, impl_describeOrNull, NULL, NULL},
  {"maybeMakeAsync", "async(boolean)->?@$external/host/carrierclass/Config", DEAL_HOST_EXPORT_ASYNC_FUNCTION, 1, NULL, impl_maybeMakeAsync, NULL},
  {"makeHolder", "()->@$external/host/carrierclass/Holder", DEAL_HOST_EXPORT_FUNCTION, 0, impl_makeHolder, NULL, NULL},
  {"consume", "((@$external/host/carrierclass/Config)->@$external/host/carrierclass/Config)->number", DEAL_HOST_EXPORT_FUNCTION, 0, impl_consume, NULL, NULL},
  {"makeWrongKind", "()->@$external/host/carrierclass/Holder", DEAL_HOST_EXPORT_FUNCTION, 0, impl_makeWrongKind, NULL, NULL},
  {"makeNullClass", "()->@$external/host/carrierclass/Holder", DEAL_HOST_EXPORT_FUNCTION, 0, impl_makeNullClass, NULL, NULL},
  {"makeWrongElement", "()->[@$external/host/carrierclass/Config]", DEAL_HOST_EXPORT_FUNCTION, 0, impl_makeWrongElement, NULL, NULL},
  {"makeSparse", "()->[@$external/host/carrierclass/Config]", DEAL_HOST_EXPORT_FUNCTION, 0, impl_makeSparse, NULL, NULL},
  {"makeMap", "()->[@$external/host/carrierclass/Config]", DEAL_HOST_EXPORT_FUNCTION, 0, impl_makeMap, NULL, NULL},
};
const deal_host_module deal_host_module_carrierclass = {DEAL_HOST_SPECIFIER_CARRIERCLASS, deal_host_exports_carrierclass, sizeof deal_host_exports_carrierclass / sizeof deal_host_exports_carrierclass[0], deal_host_classes_carrierclass, 2u, NULL};
