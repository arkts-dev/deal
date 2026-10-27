/*
 * deal/native/memory.h — the `memory` service group.
 *
 * The precise non-moving collector of `llvm-native-memory-management` (M1-M8)
 * minus collection, which is the collector child's: object headers and
 * per-layout precise reference maps, the allocation entry, the insertion
 * barrier, the safepoint seam, captured-binding cells, the scoped runtime-root
 * API, the registered root sources and the static root table.
 *
 * Object model (M1). Every managed object is one allocation whose payload is
 * preceded by a runtime-private header carrying the layout identity, the mark
 * state and the size class. Addresses never move, compact or forward, so
 * identity, keys, handles and interior pointers stay valid. The precise
 * reference map comes from the layout: `deal_layout` names the payload size and
 * the byte offsets of the managed-reference slots, emitted once per layout.
 * `deal_object_trace` walks exactly those slots; `deal_array` element storage is
 * reached through the `container` group's element layout metadata instead
 * (page `llvm-native-runtime-value-model` C3).
 *
 * Rooting is precise and explicit (M4, M5). Each generated function owns one
 * root frame with a statically known slot count: the prologue enters the frame,
 * the function writes each managed-typed temporary that can be live across an
 * allocating call into its own slot before that call, and every exit path
 * (return, thrown, caught, propagated) leaves the frame. Slots are
 * zero-initialized on enter, so no uninitialized or dead slot ever holds a stale
 * pointer, and the root depth after any exit equals the caller's depth. A
 * tagged datum is stored through `deal_root_frame_write_data`, which writes only
 * a managed payload, so no scan ever reads scalar bits, a nullish singleton or a
 * crossing handle as a heap address. Runtime helpers root their own
 * intermediates through the one scoped push/hold/release/pop API below, and
 * every root source registers one enumeration callback the collector rescans in
 * its atomic phase. An off-depth frame/scoped-root leave, a slot write outside
 * the active frame and a malformed layout are runtime invariant faults: one
 * deterministic stderr line and exit 2 (`deal_platform_fault`).
 *
 * Staged merge boundary (recorded): before the collector child, allocation
 * initializes valid headers and layouts and performs no collection, and the
 * barrier is inert because marking cannot run. This is not a second production
 * collection mode: the collector child completes the same entries and removes
 * the gap before any managed op family depends on them.
 */
#ifndef DEAL_NATIVE_MEMORY_H
#define DEAL_NATIVE_MEMORY_H

#include "deal/native/deal_rt.h"

/* -- root frames (M4) ---------------------------------------------------- */

/* Registers a frame with `slot_count` zero-initialized slots. */
void deal_root_frame_enter(uint32_t slot_count);

/* Stores one managed reference into slot `slot` of the current frame. */
void deal_root_frame_write(uint32_t slot, void *value);

/*
 * Stores the managed payload of one tagged datum into slot `slot` of the
 * current frame (M4). A scalar, a nullish singleton and a crossing handle store
 * no referent at all, so a tag-aware slot never presents non-pointer payload
 * bits as a heap address.
 */
void deal_root_frame_write_data(uint32_t slot, const deal_data *value);

/* Unregisters the current frame; its slots stop being roots. */
void deal_root_frame_leave(void);

/* The number of registered root frames (0 outside every activation). */
uint32_t deal_root_frame_depth(void);

/* -- managed layouts and allocation (M1) --------------------------------- */

/*
 * Runtime-reserved layout identities. The runtime's own representations use the
 * low ids and the emitted module's per-layout class metadata starts at
 * `DEAL_LAYOUT_EMITTED_BASE`, so the two sets can never collide.
 */
#define DEAL_LAYOUT_STRING        1u   /* immutable UTF-8 buffer (text)      */
#define DEAL_LAYOUT_CELL          2u   /* captured-binding cell (M6)         */
#define DEAL_LAYOUT_ARRAY         3u   /* zero-based typed array (container) */
#define DEAL_LAYOUT_ARRAY_BLOCK   4u   /* an array's element storage         */
#define DEAL_LAYOUT_TABLE         5u   /* insertion-ordered record           */
#define DEAL_LAYOUT_TABLE_ENTRY   6u   /* one record member                  */
#define DEAL_LAYOUT_ERROR         7u   /* the builtin Error instance         */
#define DEAL_LAYOUT_EMITTED_BASE  1024u

/*
 * One emitted per-layout metadata record (M1): the layout identity, a reserved
 * flag word that must be 0, the exact payload size of a fixed object (`0` for a
 * variable-payload layout, whose allocation size is authoritative), and the
 * precise reference map — the ascending byte offsets, relative to the payload,
 * of the payload's managed-reference slots.
 */
typedef struct {
  uint32_t id;
  uint32_t flags;
  size_t size;
  const uint32_t *reference_offsets;
  size_t reference_count;
} deal_layout;

/*
 * Installs the emitted layout table once, before any allocation (M1). Every
 * record is validated first: an emitted id below
 * `DEAL_LAYOUT_EMITTED_BASE`, a duplicate id, a non-zero reserved flag, an
 * unsorted or unaligned reference offset, an offset outside the payload or a
 * reference map on a variable-payload layout is an invariant fault, so a
 * malformed layout table never reaches an allocation.
 */
void deal_layout_install(const deal_layout *layouts, size_t count);

/*
 * Registers one runtime representation's own layout, at its owning group's
 * first use (`text`, `container` and this group define theirs; the emitted
 * module never does). A repeated definition of one id is the same layout; a
 * different definition of the same id is an invariant fault.
 */
void deal_layout_define(const deal_layout *layout);

/* The layout record of `layout`, or NULL when no such layout is defined. */
const deal_layout *deal_layout_lookup(uint32_t layout);

/*
 * A zero-initialized managed object of `layout` whose payload is `size` bytes
 * (M1, `Allocate`): the header carries the layout identity, the mark state and
 * the size class, addresses never change, and `size` is never smaller than the
 * layout's fixed payload size. An unknown layout or a short size is an
 * invariant fault. Exhaustion follows R10 before partial publication; the
 * allocation may run a bounded collection slice once the collector exists.
 */
void *deal_alloc(uint32_t layout, size_t size);

/* The header facts of one managed object (M1): its layout and payload size. */
uint32_t deal_object_layout(const void *object);
size_t deal_object_size(const void *object);

/*
 * Walks the precise reference map of one fixed-layout managed object in
 * ascending offset order (M1), visiting exactly the non-null managed referents
 * its layout declares. A nullish singleton, a scalar payload and a crossing
 * handle are never visited. An object of a variable-payload layout contributes
 * only its fixed map; an array's element storage is walked by
 * `deal_container_array_trace`.
 */
void deal_object_trace(const void *object, deal_reference_visit visit,
                       void *context);

/* -- the insertion barrier (M2) ----------------------------------------- */

/* The insertion barrier for a store into `container` (M2). */
void deal_store_barrier(void *container, void *value);

/*
 * The tag-aware insertion barrier of one boxed store (M2): the barrier runs
 * exactly when the datum's payload is managed, so an unknown or nullish slot
 * never shades scalar bits or a singleton.
 */
void deal_store_barrier_data(void *container, const deal_data *value);

/* -- the safepoint (M3) -------------------------------------------------- */

/*
 * The allocation and loop-back-edge safepoint: one bounded marking/sweeping
 * slice, and the `DEAL_GC_STRESS` full collection of M7. Before the collector
 * child no cycle can be in flight, so this is the staged no-op and changes no
 * observable behavior.
 */
void deal_safepoint(void);

/* -- captured-binding cells (C5, M6) ------------------------------------- */

/* One captured-binding cell for `generation` holding `value` (C5). */
void *deal_cell_alloc_generation(void *value, uint32_t generation);

/* One captured-binding cell holding `value` for generation 0. */
void *deal_cell_alloc(void *value);

/* The cell's current value (never absent; a cell always holds one value). */
void *deal_cell_read(const void *cell);

/* The binding generation the cell was allocated for. */
uint32_t deal_cell_generation(const void *cell);

/* Stores through the barrier into a captured-binding cell (C5, `CellStore`). */
void deal_cell_store(void *cell, void *value);

/* -- scoped runtime roots (M5) ------------------------------------------- */

/* Pushes one scoped root set for the current runtime helper. */
void deal_root_scope_push(void);

/* Roots one managed referent and returns its slot; the slot stays rooted. */
uint32_t deal_root_scope_hold(void *referent);

/* Roots the managed payload of one tagged datum (a scalar holds nothing). */
uint32_t deal_root_scope_hold_data(const deal_data *value);

/* Releases one held slot of the current scope; released slots are reusable. */
void deal_root_scope_release(uint32_t slot);

/* Pops the current scope; every slot it held stops being a root. */
void deal_root_scope_pop(void);

/* The number of active scopes (0 outside every runtime helper). */
uint32_t deal_root_scope_depth(void);

/* -- registered root sources (M5) ---------------------------------------- */

/*
 * One root-source enumeration: visit every managed referent it roots, passing
 * the collector's visitor context through unchanged beside the source's own
 * registered context.
 */
typedef void (*deal_root_source_scan)(deal_reference_visit visit,
                                      void *visit_context,
                                      void *source_context);

/*
 * Registers one root source with the collector's rescan (M5). The host crossing
 * scopes, the handle table and the open-operation table each register their own
 * source context; a crossing scope registers on push and unregisters on pop, so
 * no failed crossing leaves one open.
 */
void deal_root_source_register(deal_root_source_scan scan, void *context);

/* Unregisters exactly one registered source; an unknown source is a fault. */
void deal_root_source_unregister(deal_root_source_scan scan, void *context);

/*
 * Enumerates every root source of M5 in the collector's rescan order: the
 * generated root frames, the scoped runtime roots, every registered source in
 * registration order (crossing scopes, the handle table, the open-operation
 * table), and the static root table last. Exactly the managed referents are
 * visited: a nullish singleton, a scalar payload and a crossing handle are not.
 */
void deal_root_sources_enumerate(deal_reference_visit visit, void *context);

/*
 * Registers one static root slot (M5: export tables, class plans, descriptor
 * atoms). The slot is rescanned by every atomic phase, so a table installed
 * after module initialization is a root as soon as the slot is written.
 */
void deal_static_root_register(void **slot);

/* Unregisters exactly one static root slot. */
void deal_static_root_unregister(void **slot);

/* The number of registered static root slots. */
size_t deal_static_root_count(void);

/* -- the collector (M1-M8; implemented by the runtime-collector child) ---- */

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
