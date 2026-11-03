/*
 * test/native/export_probe.c — a driver that drives the whole export surface.
 *
 * It calls all export-surface functions of `deal/native/entry.h` (C2, H6) in
 * one process, so a link proves the emitted module must define every one of
 * them, and so the missing-definition negative names them all.
 */
#include "deal/native/entry.h"
#include "deal/native/platform.h"

#include <stdio.h>

int main(void) {
    deal_outcome started = deal_project_start();
    if (started.error != NULL) {
        return 3;
    }
    deal_module *module = deal_project_exports("native/main.deal");
    deal_export_entry *entry = deal_export_lookup(module, "probe");
    deal_outcome invoked = deal_export_invoke(entry);
    if (invoked.error != NULL) {
        return 4;
    }
    deal_outcome asynchronously = deal_async_export_invoke(entry);
    if (asynchronously.error != NULL) {
        return 5;
    }
    deal_data value;
    deal_outcome completed = deal_async_export_invoke_value(entry, &value);
    if (completed.error != NULL || value.tag != DEAL_DATA_NUMBER
            || value.number != 42.0) return 7;
    deal_outcome ran = deal_project_run_main();
    if (ran.error != NULL) {
        return 6;
    }
    printf("exports ok\n");
    deal_platform_flush_stdout();
    return 0;
}
