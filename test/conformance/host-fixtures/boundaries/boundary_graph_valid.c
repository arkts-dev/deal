#include "host/probe.h"
#include "native_helpers.h"
static deal_handle saved;
static deal_host_result impl_corrupt(const deal_value *args,size_t argc){(void)argc;if(saved)checked(deal_release(saved));checked(deal_retain(args[0],&saved));set(args[0],"bad",number(3));return result(undefined_value());}
static deal_host_result impl_repair(const deal_value *args,size_t argc){(void)args;(void)argc;checked(deal_table_delete(retained(saved,"record"),"bad",3u));return result(undefined_value());}
const deal_host_export deal_host_exports_probe[] = {
  {"corrupt", "(record)->void", DEAL_HOST_EXPORT_FUNCTION, 0, impl_corrupt, NULL, NULL},
  {"repair", "()->void", DEAL_HOST_EXPORT_FUNCTION, 0, impl_repair, NULL, NULL},
};
const deal_host_module deal_host_module_probe = {DEAL_HOST_SPECIFIER_PROBE, deal_host_exports_probe, sizeof deal_host_exports_probe / sizeof deal_host_exports_probe[0], NULL, 0u, NULL};
