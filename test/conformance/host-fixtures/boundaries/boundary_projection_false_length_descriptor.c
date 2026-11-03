#include "host/probe.h"
#include "native_helpers.h"

/* C has no JavaScript property descriptor object. Compare the public live
 * length projection with the backing container's current length instead. */
static deal_host_result impl_f(const deal_value *args, size_t argc) {
    (void)argc;
    int64_t projected;
    checked(deal_array_length(args[0], &projected));
    deal_check_input input;
    deal_error *cause = NULL;
    checked(deal_host_value_presentation(args[0], &input, &cause));
    if (cause != NULL || input.datum.tag != DEAL_DATA_ARRAY) abort();
    size_t stored = deal_container_array_count(input.datum.reference);
    if (projected >= 0 && (uint64_t)projected == stored)
        deal_platform_write_stdout("COHERENT\n", 9u);
    else deal_platform_write_stdout("INCONSISTENT\n", 13u);
    return result(undefined_value());
}
const deal_host_export deal_host_exports_probe[] = {
    {"f", "([unknown])->void", DEAL_HOST_EXPORT_FUNCTION, 0, impl_f, NULL, NULL},
};
const deal_host_module deal_host_module_probe = {
    DEAL_HOST_SPECIFIER_PROBE, deal_host_exports_probe,
    sizeof deal_host_exports_probe / sizeof deal_host_exports_probe[0], NULL, 0u, NULL
};
