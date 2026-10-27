/*
 * test/native/opacity_pointer.c — the positive half of the type-opacity proof.
 *
 * A consumer may name `deal_module` and `deal_export_entry` through pointers and
 * pass them back to the entry surface; both are incomplete types, so the helper
 * functions below can never size, allocate, fill or read them
 * (`llvm-native-codegen-cfg-ssa` C2/C3, `llvm-native-host-abi-and-async` H6).
 */
#include "deal/native/entry.h"

#include <stdio.h>

static int deal_probe_lookup(deal_module *module) {
    deal_export_entry *entry = deal_export_lookup(module, "probe");
    return entry == NULL ? 1 : 0;
}

int main(void) {
    deal_module *module = deal_project_exports("native/main.deal");
    if (module != NULL) {
        printf("opacity probe: unexpected module\n");
        return 1;
    }
    if (!deal_probe_lookup(module)) {
        printf("opacity probe: unexpected export\n");
        return 1;
    }
    printf("opacity ok\n");
    return 0;
}
