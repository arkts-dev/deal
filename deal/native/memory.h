/*
 * deal/native/memory.h — the `memory` service group.
 *
 * The precise non-moving incremental mark-and-sweep collector of
 * `llvm-native-memory-management` (M1-M8). This header publishes the entries
 * the emitted module and the other groups call; the collector itself, the
 * barrier, the captured cells, the safepoint and the statistics entries arrive
 * with the runtime-collector child, which extends this header.
 *
 * Root frames (M4, `RootFrame`): each generated function owns one root frame
 * with a statically known slot count. The prologue enters the frame, the
 * function writes each managed-typed temporary that can be live across an
 * allocating call into its own slot before that call, and every exit path
 * (return, thrown, caught, propagated) leaves the frame. Slots are
 * zero-initialized on enter, so no uninitialized or dead slot ever holds a
 * stale pointer, and the root depth after any exit equals the caller's depth.
 * An off-depth leave or slot write is a runtime invariant fault: one
 * deterministic stderr line and exit 2 (`deal_platform_fault`).
 */
#ifndef DEAL_NATIVE_MEMORY_H
#define DEAL_NATIVE_MEMORY_H

#include "deal/native/deal_rt.h"

/* -- root frames (M4) ---------------------------------------------------- */

/* Registers a frame with `slot_count` zero-initialized slots. */
void deal_root_frame_enter(uint32_t slot_count);

/* Stores one managed reference into slot `slot` of the current frame. */
void deal_root_frame_write(uint32_t slot, void *value);

/* Unregisters the current frame; its slots stop being roots. */
void deal_root_frame_leave(void);

/* The number of registered root frames (0 outside every activation). */
uint32_t deal_root_frame_depth(void);

/* -- the collector (M1-M8; implemented by the runtime-collector child) ---- */

/* A zero-initialized managed object of `layout` and `size` (M1, Allocate). */
void *deal_alloc(uint32_t layout, size_t size);

/* The insertion barrier for a store into `container` (M2). */
void deal_store_barrier(void *container, void *value);

/* One captured-binding cell holding `value` (C5, CellStore). */
void *deal_cell_alloc(void *value);

/* Stores through the barrier into a captured-binding cell (C5, CellStore). */
void deal_cell_store(void *cell, void *value);

/* The allocation/loop-back-edge safepoint: a bounded marking/sweeping slice. */
void deal_safepoint(void);

/* One complete incremental cycle, including its atomic phase and sweep (M7). */
void deal_gc_collect_full(void);

/* The product-level collection statistics (M7). */
typedef struct {
    uint64_t live_bytes;
    uint64_t object_count;
    uint64_t allocated_bytes;
    uint64_t collections;
    uint64_t swept_objects;
} deal_gc_statistics_record;

void deal_gc_statistics(deal_gc_statistics_record *out);

#endif /* DEAL_NATIVE_MEMORY_H */
