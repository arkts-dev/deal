#include "host/planprobe.h"
#include "native_helpers.h"
static double calls;
static deal_host_result impl_nextValue(const deal_value *args, size_t argc) { (void)args; (void)argc; return result(number(++calls*10)); }
static deal_host_result impl_valueCount(const deal_value *args, size_t argc) { (void)args; (void)argc; return result(number(calls)); }

const deal_host_export deal_host_exports_planprobe[] = {
  {"nextValue", "()->number", DEAL_HOST_EXPORT_FUNCTION, 0, impl_nextValue, NULL, NULL},
  {"valueCount", "()->number", DEAL_HOST_EXPORT_FUNCTION, 0, impl_valueCount, NULL, NULL},
};
const deal_host_module deal_host_module_planprobe = {DEAL_HOST_SPECIFIER_PLANPROBE, deal_host_exports_planprobe, sizeof deal_host_exports_planprobe / sizeof deal_host_exports_planprobe[0], NULL, 0u, NULL};
