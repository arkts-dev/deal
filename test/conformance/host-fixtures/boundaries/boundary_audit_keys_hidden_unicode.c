#include "host/probe.h"
#include "native_hostile.h"
static deal_host_result get_record(const deal_value *args, size_t argc) {
    (void)args; (void)argc; deal_value record, invalid; checked(deal_table_new(&record));
    checked(deal_string_new("\xed\xa0\x80", 3u, &invalid));
    set(record, "raw", invalid); fixture_record_raw_key(record, boolean(0)); return result(record);
}
const deal_host_export deal_host_exports_probe[] = {{"get", "()->record", DEAL_HOST_EXPORT_FUNCTION, 0, get_record, NULL, NULL}};
const deal_host_module deal_host_module_probe = {DEAL_HOST_SPECIFIER_PROBE, deal_host_exports_probe, 1u, NULL, 0u, NULL};
