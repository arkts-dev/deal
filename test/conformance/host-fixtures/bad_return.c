#include "host/bad_return.h"
#include "native_helpers.h"
static deal_host_result impl_getNumber(const deal_value *args, size_t argc) { (void)args; (void)argc; return result(text("not a number")); }

const deal_host_export deal_host_exports_bad_return[] = {
  {"getNumber", "()->number", DEAL_HOST_EXPORT_FUNCTION, 0, impl_getNumber, NULL, NULL},
};
const deal_host_module deal_host_module_bad_return = {DEAL_HOST_SPECIFIER_BAD_RETURN, deal_host_exports_bad_return, sizeof deal_host_exports_bad_return / sizeof deal_host_exports_bad_return[0], NULL, 0u, NULL};
