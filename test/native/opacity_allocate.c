/*
 * test/native/opacity_allocate.c — the negative half of the type-opacity proof.
 *
 * Allocating, filling or reading a `deal_export_entry` must not compile: no
 * published header declares its members (C2/C3, H6).
 */
#include "deal/native/entry.h"

int main(void) {
    deal_export_entry entry;
    entry.name = "probe";
    return entry.name == NULL ? 1 : 0;
}
