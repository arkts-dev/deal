/*
 * deal/native/memory.c — the `memory` service group implementation.
 *
 * The root-frame entries of M4 are implemented here so the emitted skeleton can
 * push a frame, write its slots and leave it on every exit path before any
 * managed op family exists. The collector entries declared by memory.h land with
 * the runtime-collector child, which extends this translation unit without
 * redefining the root-frame entries.
 */
#include "deal/native/memory.h"

#include <string.h>

#include "deal/native/platform.h"

/* The maximum number of simultaneously active root frames (one per active
 * generated-function activation). Reaching it is an invariant fault. */
#define DEAL_ROOT_FRAME_MAX 4096U

static void **deal_root_slots;
static size_t deal_root_slots_capacity;
static size_t deal_root_slots_used;
static uint32_t deal_root_frame_starts[DEAL_ROOT_FRAME_MAX];
static uint32_t deal_root_frame_counts[DEAL_ROOT_FRAME_MAX];
static uint32_t deal_root_frame_depth_value;

static void deal_root_frame_reserve(size_t slots) {
    if (slots <= deal_root_slots_capacity) {
        return;
    }
    size_t capacity = deal_root_slots_capacity == 0
        ? 64U : deal_root_slots_capacity;
    while (capacity < slots) {
        capacity *= 2U;
    }
    void **grown = deal_platform_page_alloc(capacity * sizeof(void *));
    if (deal_root_slots != NULL) {
        memcpy(grown, deal_root_slots,
               deal_root_slots_used * sizeof(void *));
        deal_platform_page_free(deal_root_slots,
                                deal_root_slots_capacity * sizeof(void *));
    }
    deal_root_slots = grown;
    deal_root_slots_capacity = capacity;
}

void deal_root_frame_enter(uint32_t slot_count) {
    if (deal_root_frame_depth_value == DEAL_ROOT_FRAME_MAX) {
        deal_platform_fault("root frame: depth limit exceeded");
    }
    size_t required = deal_root_slots_used + (size_t)slot_count;
    deal_root_frame_reserve(required);
    /* A zero-slot frame is the skeleton's documented first shape and the shape
     * of every activation with no slot live across an allocation. It performs
     * no slot access at all: a zero-length memset on the (still unallocated)
     * table base would form a null pointer sum and pass null to a nonnull
     * parameter, both undefined behavior in C11. */
    if (slot_count != 0U) {
        memset(deal_root_slots + deal_root_slots_used, 0,
               (size_t)slot_count * sizeof(void *));
    }
    deal_root_frame_starts[deal_root_frame_depth_value] =
        (uint32_t)deal_root_slots_used;
    deal_root_frame_counts[deal_root_frame_depth_value] = slot_count;
    deal_root_slots_used = required;
    deal_root_frame_depth_value += 1U;
}

void deal_root_frame_write(uint32_t slot, void *value) {
    if (deal_root_frame_depth_value == 0) {
        deal_platform_fault("root frame: slot write with no active frame");
    }
    uint32_t current = deal_root_frame_depth_value - 1U;
    if (slot >= deal_root_frame_counts[current]) {
        deal_platform_fault("root frame: slot write outside the active frame");
    }
    deal_root_slots[deal_root_frame_starts[current] + slot] = value;
}

void deal_root_frame_leave(void) {
    if (deal_root_frame_depth_value == 0) {
        deal_platform_fault("root frame: leave with no active frame");
    }
    uint32_t current = deal_root_frame_depth_value - 1U;
    uint32_t start = deal_root_frame_starts[current];
    uint32_t count = deal_root_frame_counts[current];
    if (count != 0U) {
        memset(deal_root_slots + start, 0, (size_t)count * sizeof(void *));
    }
    deal_root_slots_used = start;
    deal_root_frame_depth_value = current;
}

uint32_t deal_root_frame_depth(void) {
    return deal_root_frame_depth_value;
}
