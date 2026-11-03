#include "host/cfg.h"
#include "native_helpers.h"
#include <stdio.h>
static deal_host_result impl_describe(const deal_value *args,size_t argc){
 (void)argc;deal_value ep=field(args[0],"endpoint");const void *p;size_t n;
 checked(deal_as_string(field(ep,"path"),&p,&n)); char *s=malloc(n+40u);if(s==NULL)abort();
 memcpy(s,p,n);snprintf(s+n,40u,":%.0f",as_number(field(args[0],"port")));deal_value v=text(s);free(s);return result(v);
}
const deal_host_field deal_host_fields_cfg_Endpoint[] = {
  {"path", "string", 0},
};
const deal_host_class deal_host_class_cfg_Endpoint = {"@$external/host/cfg/Endpoint", deal_host_fields_cfg_Endpoint, 1u};
const deal_host_field deal_host_fields_cfg_ServerConfig[] = {
  {"port", "number", 0},
  {"endpoint", "@$external/host/cfg/Endpoint", 0},
  {"tags", "[string]", 1},
  {"note", "?string", 1},
};
const deal_host_class deal_host_class_cfg_ServerConfig = {"@$external/host/cfg/ServerConfig", deal_host_fields_cfg_ServerConfig, 4u};
const deal_host_class deal_host_classes_cfg[] = {
  {"@$external/host/cfg/Endpoint", deal_host_fields_cfg_Endpoint, 1u},
  {"@$external/host/cfg/ServerConfig", deal_host_fields_cfg_ServerConfig, 4u},
};
const deal_host_export deal_host_exports_cfg[] = {
  {"Endpoint", "@$external/host/cfg/Endpoint", DEAL_HOST_EXPORT_CLASS, 0, NULL, NULL, &deal_host_classes_cfg[0]},
  {"ServerConfig", "@$external/host/cfg/ServerConfig", DEAL_HOST_EXPORT_CLASS, 0, NULL, NULL, &deal_host_classes_cfg[1]},
  {"describe", "(@$external/host/cfg/ServerConfig)->string", DEAL_HOST_EXPORT_FUNCTION, 0, impl_describe, NULL, NULL},
};
const deal_host_module deal_host_module_cfg = {DEAL_HOST_SPECIFIER_CFG, deal_host_exports_cfg, sizeof deal_host_exports_cfg / sizeof deal_host_exports_cfg[0], deal_host_classes_cfg, 2u, NULL};
