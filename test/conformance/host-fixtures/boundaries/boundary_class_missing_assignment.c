#include "host/probe.h"
#include "native_hostile.h"
static deal_host_result f(const deal_value *args, size_t argc) {
    (void)argc; fixture_class_field(args[0], "x", undefined_value(), 0);
    return result(undefined_value());
}
const deal_host_export deal_host_exports_probe[] = {
    {"f", "(unknown)->void", DEAL_HOST_EXPORT_FUNCTION, 0, f, NULL, NULL}
};
const deal_host_module deal_host_module_probe = {DEAL_HOST_SPECIFIER_PROBE, deal_host_exports_probe, 1u, NULL, 0u, NULL};
