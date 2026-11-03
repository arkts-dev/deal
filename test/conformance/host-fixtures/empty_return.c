#include "host/empty_return.h"
#include "native_helpers.h"
static deal_host_result impl_ping(const deal_value *args, size_t argc) { (void)args; (void)argc; deal_value value; checked(deal_nothing(&value)); return result(value); }

const deal_host_export deal_host_exports_empty_return[] = {
  {"ping", "()->number", DEAL_HOST_EXPORT_FUNCTION, 0, impl_ping, NULL, NULL},
};
const deal_host_module deal_host_module_empty_return = {DEAL_HOST_SPECIFIER_EMPTY_RETURN, deal_host_exports_empty_return, sizeof deal_host_exports_empty_return / sizeof deal_host_exports_empty_return[0], NULL, 0u, NULL};
