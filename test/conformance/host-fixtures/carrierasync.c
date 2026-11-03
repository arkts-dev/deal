#include "host/carrierasync.h"
#include "native_helpers.h"
#include <stdio.h>
static deal_value endpoint(const char *path){deal_value v;checked(deal_host_carrierasync_Endpoint_new(&v));checked(deal_field_set(v,"path",text(path)));return v;}
static deal_value config(double port,const char *path,const char *first,const char *second){deal_value v,xs=array("string",2);checked(deal_host_carrierasync_ServerConfig_new(&v));checked(deal_field_set(v,"port",number(port)));checked(deal_field_set(v,"endpoint",endpoint(path)));checked(deal_array_set(xs,0,text(first)));checked(deal_array_set(xs,1,text(second)));checked(deal_field_set(v,"parts",xs));checked(deal_field_set(v,"note",null_value()));return v;}
static void string_copy(deal_value value,char *out,size_t capacity){const void *bytes;size_t n;checked(deal_as_string(value,&bytes,&n));if(n>=capacity)abort();memcpy(out,bytes,n);out[n]=0;}
static void array_text(deal_value value,char *out,size_t capacity){deal_value_kind kind;checked(deal_kind_of(value,&kind));if(kind!=DEAL_KIND_ARRAY){if(capacity<7u)abort();memcpy(out,"absent",7u);return;}int64_t n;checked(deal_array_length(value,&n));char first[256];string_copy(at(value,0),first,sizeof first);int size=snprintf(out,capacity,"%lld:%s",(long long)n,first);if(size<0||(size_t)size>=capacity)abort();}
static deal_host_result impl_describeAsync(deal_async_token token,const deal_value *args,size_t argc){(void)argc;char path[256],parts[300],tags[300],note[256],g[300],buffer[1400];string_copy(field(field(args[0],"endpoint"),"path"),path,sizeof path);array_text(field(args[0],"parts"),parts,sizeof parts);array_text(field(args[0],"tags"),tags,sizeof tags);deal_value nv=field(args[0],"note");deal_value_kind kind;checked(deal_kind_of(nv,&kind));if(kind==DEAL_KIND_NULL)memcpy(note,"null",5u);else if(kind==DEAL_KIND_UNDEFINED||kind==DEAL_KIND_MISSING)memcpy(note,"absent",7u);else string_copy(nv,note,sizeof note);deal_value arg=number(41);deal_host_result applied=apply(args[1],&arg,1u);if(applied.failed){const void *code;size_t n;checked(deal_as_string(field(applied.value,"code"),&code,&n));if(n+7u>=sizeof g)abort();memcpy(g,"false:",6u);memcpy(g+6u,code,n);g[6u+n]=0;checked(deal_failure_discard(applied.failure.error));}else snprintf(g,sizeof g,"true:%.17g",as_number(applied.value));int n=snprintf(buffer,sizeof buffer,"SC|%.17g|%s|%s|%s|%s|%s",as_number(field(args[0],"port")),path,parts,tags,note,g);if(n<0||(size_t)n>=sizeof buffer)abort();return complete(token,text(buffer));}
static deal_host_result impl_make(deal_async_token token,const deal_value *args,size_t argc){(void)args;(void)argc;return complete(token,config(4242,"/made","a","b"));}
static deal_host_result impl_makeAll(const deal_value *args,size_t argc){(void)args;(void)argc;deal_value v=config(11,"/one","x","y"),xs=array(DEAL_HOST_CLASS_ATOM_CARRIERASYNC_SERVERCONFIG,2);checked(deal_array_set(xs,0,v));checked(deal_array_set(xs,1,v));return result(xs);}
static deal_host_result impl_makeFarm(const deal_value *args,size_t argc){(void)args;(void)argc;deal_value v,xs=array(DEAL_HOST_CLASS_ATOM_CARRIERASYNC_SERVERCONFIG,1);checked(deal_host_carrierasync_Farm_new(&v));checked(deal_field_set(v,"name",text("farm")));checked(deal_array_set(xs,0,config(12,"/two","x","y")));checked(deal_field_set(v,"servers",xs));checked(deal_field_set(v,"primary",endpoint("/p")));return result(v);}

const deal_host_field deal_host_fields_carrierasync_Endpoint[] = {
  {"path", "string", 0},
};
const deal_host_class deal_host_class_carrierasync_Endpoint = {DEAL_HOST_CLASS_ATOM_CARRIERASYNC_ENDPOINT, deal_host_fields_carrierasync_Endpoint, 1u};
const deal_host_field deal_host_fields_carrierasync_ServerConfig[] = {
  {"port", "number", 0},
  {"endpoint", DEAL_HOST_CLASS_ATOM_CARRIERASYNC_ENDPOINT, 0},
  {"parts", "[string]", 0},
  {"tags", "[string]", 1},
  {"note", "?string", 1},
};
const deal_host_class deal_host_class_carrierasync_ServerConfig = {DEAL_HOST_CLASS_ATOM_CARRIERASYNC_SERVERCONFIG, deal_host_fields_carrierasync_ServerConfig, 5u};
const deal_host_field deal_host_fields_carrierasync_Farm[] = {
  {"name", "string", 0},
  {"servers", "[" DEAL_HOST_CLASS_ATOM_CARRIERASYNC_SERVERCONFIG "]", 0},
  {"primary", DEAL_HOST_CLASS_ATOM_CARRIERASYNC_ENDPOINT, 0},
};
const deal_host_class deal_host_class_carrierasync_Farm = {DEAL_HOST_CLASS_ATOM_CARRIERASYNC_FARM, deal_host_fields_carrierasync_Farm, 3u};
const deal_host_class deal_host_classes_carrierasync[] = {
  {DEAL_HOST_CLASS_ATOM_CARRIERASYNC_ENDPOINT, deal_host_fields_carrierasync_Endpoint, 1u},
  {DEAL_HOST_CLASS_ATOM_CARRIERASYNC_SERVERCONFIG, deal_host_fields_carrierasync_ServerConfig, 5u},
  {DEAL_HOST_CLASS_ATOM_CARRIERASYNC_FARM, deal_host_fields_carrierasync_Farm, 3u},
};
const deal_host_export deal_host_exports_carrierasync[] = {
  {"Endpoint", DEAL_HOST_CLASS_ATOM_CARRIERASYNC_ENDPOINT, DEAL_HOST_EXPORT_CLASS, 0, NULL, NULL, &deal_host_classes_carrierasync[0]},
  {"ServerConfig", DEAL_HOST_CLASS_ATOM_CARRIERASYNC_SERVERCONFIG, DEAL_HOST_EXPORT_CLASS, 0, NULL, NULL, &deal_host_classes_carrierasync[1]},
  {"Farm", DEAL_HOST_CLASS_ATOM_CARRIERASYNC_FARM, DEAL_HOST_EXPORT_CLASS, 0, NULL, NULL, &deal_host_classes_carrierasync[2]},
  {"describeAsync", "async(" DEAL_HOST_CLASS_ATOM_CARRIERASYNC_SERVERCONFIG ",(number)->number)->string", DEAL_HOST_EXPORT_ASYNC_FUNCTION, 1, NULL, impl_describeAsync, NULL},
  {"make", "async()->" DEAL_HOST_CLASS_ATOM_CARRIERASYNC_SERVERCONFIG, DEAL_HOST_EXPORT_ASYNC_FUNCTION, 1, NULL, impl_make, NULL},
  {"makeAll", "()->[" DEAL_HOST_CLASS_ATOM_CARRIERASYNC_SERVERCONFIG "]", DEAL_HOST_EXPORT_FUNCTION, 0, impl_makeAll, NULL, NULL},
  {"makeFarm", "()->" DEAL_HOST_CLASS_ATOM_CARRIERASYNC_FARM, DEAL_HOST_EXPORT_FUNCTION, 0, impl_makeFarm, NULL, NULL},
};
const deal_host_module deal_host_module_carrierasync = {DEAL_HOST_SPECIFIER_CARRIERASYNC, deal_host_exports_carrierasync, sizeof deal_host_exports_carrierasync / sizeof deal_host_exports_carrierasync[0], deal_host_classes_carrierasync, 3u, NULL};
