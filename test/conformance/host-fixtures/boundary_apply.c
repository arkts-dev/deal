#include "host/boundary_apply.h"
#include "native_helpers.h"
static deal_host_result impl_apply(const deal_value *args,size_t argc){(void)argc;deal_host_result r=apply(args[0],&args[1],1u);if(!r.failed)r.value=number(as_number(r.value)+100);return r;}
const deal_host_export deal_host_exports_boundary_apply[] = {
  {"apply", "((number)->number,number)->number", DEAL_HOST_EXPORT_FUNCTION, 0, impl_apply, NULL, NULL},
};
const deal_host_module deal_host_module_boundary_apply = {DEAL_HOST_SPECIFIER_BOUNDARY_APPLY, deal_host_exports_boundary_apply, sizeof deal_host_exports_boundary_apply / sizeof deal_host_exports_boundary_apply[0], NULL, 0u, NULL};
