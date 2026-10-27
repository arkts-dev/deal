/*
 * test/native/opacity_sizeof.c — the negative half of the type-opacity proof.
 *
 * `sizeof(deal_module)` must not compile: the layout exists only in the emitted
 * project module, never in a published runtime header or a runtime translation
 * unit (C2/C3, H6).
 */
#include "deal/native/entry.h"

int main(void) {
    return (int)sizeof(deal_module);
}
