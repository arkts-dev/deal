/*
 * deal/native/memory.h — the `memory` service group.
 *
 * The precise non-moving incremental mark/sweep collector of
 * `llvm-native-memory-management` M1-M8: object headers and per-layout precise
 * reference maps, the allocation entry and its safepoint, the mark state and
 * bounded gray work list, the atomic-phase root rescan, the sweep cursor and
 * size-class free lists, the insertion barrier, captured-binding cells, the
 * scoped runtime-root API, the registered root sources, the static root table
 * and the two observation entries.
 *
 * Object model (M1). Every managed object is one allocation whose payload is
 * preceded by a runtime-private header carrying the layout identity, the mark
 * state, the payload capacity and the size class. Addresses never move, compact
 * or forward, so identity, keys, handles and interior pointers stay valid. The
 * precise reference map comes from the layout: `deal_layout` names the payload
 * size and the byte offsets of the managed-reference slots, emitted once per
 * layout. `deal_object_trace` walks exactly those slots; a layout whose payload
 * holds managed references outside that map registers its own resumable walk
 * (`deal_layout_trace_register`), so `deal_array` element storage is walked
 * through the `container` group's element layout metadata and the collector
 * never derives another group's layout (page
 * `llvm-native-runtime-value-model` C3).
 *
 * Allocation (M1, M8). Objects are carved from page-allocated blocks and reused
 * from one free list per size class. The size class is derived from the
 * object's *capacity* — the aligned payload slot the object owns — and never
 * from a request size: a payload at or below `DEAL_ALLOC_SMALL_MAX_BYTES` has
 * an exact 16-byte class, a larger payload a geometric class of at most 12.5%
 * slack, so one class holds objects of one capacity and taking a free object is
 * a bounded head pop instead of a scan. A request takes the smallest class that
 * fits it, so a freed larger slot serves a smaller request and keeps its own
 * capacity; the collector never rewrites a slot's capacity with a request size.
 * A block whose objects are all unreachable again is unmapped once a cycle's
 * sweep ends, so churn returns page storage to the operating system instead of
 * retaining every arena a program ever used.
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
 * Collection (M1, M3, M7). Exactly one collection configuration exists; there is
 * no staged or selectable non-collecting mode. A cycle has three phases:
 * incremental marking, the atomic phase, and incremental sweeping. Marking work
 * is bounded by *reference visits*, never by objects, and a trace spanning more
 * references than one slice is resumable: the safepoint spends at most
 * `DEAL_GC_MARK_BUDGET_REFERENCES` visits per call across the fixed reference
 * maps and the registered layout walks, so one wide array or one long chain
 * cannot turn a slice into an unbounded pause, and the next safepoint resumes
 * the walk at the recorded position. The atomic phase is entered when the gray
 * set is empty: every root source is rescanned and the newly shaded closure is
 * then traced by further bounded slices — the rescan of the roots is the pause,
 * and the sweep is entered only by a rescan that shaded nothing new, so no
 * object reachable at sweep time is unmarked. Sweeping then inspects a bounded
 * number of objects from the sweep cursor per safepoint, unmarked ones being
 * freed into their size class. Objects allocated during a cycle are marked and
 * are therefore never reclaimed by that cycle, and the sweep visits them so
 * their mark state is clean again when the cycle ends. A full cycle can never
 * fail: exhaustion of the collector's own platform storage is the R10 resource
 * fault before partial mutation. `deal_gc_collect_full` is the one entry that
 * runs a whole cycle to completion; the incremental safepoint keeps every slice
 * bounded.
 *
 * Reclaiming an object first runs the layout's registered reclamation hook
 * (`deal_layout_release_register`) while its payload is still intact, so a
 * representation that owns platform storage beside its managed payload — the
 * Error record's copied text and frame labels — releases that storage with the
 * object instead of stranding it. A layout without a hook owns no storage
 * outside the managed heap.
 *
 * Only one mutator thread exists (M3). The atomic phase therefore needs no
 * synchronization: no other thread can allocate, mutate, read or free a managed
 * object, and the completion-queue posters touch platform storage only.
 *
 * The collection trigger is one internal growth policy, not a source-visible
 * heap limit or pause target (M8): a cycle starts at a safepoint once the
 * allocation *footprint* consumed since the last completed cycle — the header
 * and the capacity of every allocation, not its payload alone — reaches the
 * larger of a fixed minimum and the footprint that survived that cycle. Charging
 * the footprint is what makes pressure real: a program allocating many
 * zero-payload objects still consumes headers and arena storage, and it must
 * collect exactly as one allocating payload bytes does. A program that keeps
 * allocating collects without an explicit call, and uncollected garbage can
 * never raise the bar that would reclaim it. `DEAL_GC_STRESS=1` (M7) performs
 * one full collection at every safepoint and changes timing only; a behavioral
 * difference under it is a defect. `deal_gc_collect_full` and
 * `deal_gc_statistics` are the only observation surface; the statistics keep
 * their payload accounting (`live_bytes` and `allocated_bytes` are payload
 * bytes), while the growth policy counts the footprint.
 *
 * Sanitizer builds. The collector is compiled by the same pinned C11 flags in
 * every build. When the translation unit is compiled with AddressSanitizer, the
 * sweep poisons a reclaimed payload region and the allocation entry unpoisons
 * the region it hands out again, so a stale access into a reused arena slot is
 * a reported use-after-free instead of a silent read of a still-mapped address
 * (an ordinary AddressSanitizer build cannot see reuse inside one mapping).
 * Every other build fills a reclaimed payload with one fixed byte pattern and
 * poisons nothing; neither behaviour is observable through a live object.
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
#define DEAL_LAYOUT_ELEMENT_DATUM      8u   /* one boxed persistent datum slot    */
#define DEAL_LAYOUT_ELEMENT_REFERENCE  9u   /* one managed reference element slot */
#define DEAL_LAYOUT_ELEMENT_NUMBER    10u   /* one binary64 element slot          */
#define DEAL_LAYOUT_ELEMENT_BOOLEAN   11u   /* one 0/1 boolean element slot       */
/* The two scalar-nullish element slots (C3): one `{ tag, base }` record each,
 * the storages the emitted module's own element access reads. They live here
 * because both sides write them: the emitted array construction and the runtime
 * JSON decode walk, whose decoded array must carry the very storage the emitted
 * element access reads (R2, R6). */
#define DEAL_LAYOUT_ELEMENT_NULLISH_NUMBER  12u /* one `{ tag, binary64 }` slot  */
#define DEAL_LAYOUT_ELEMENT_NULLISH_BOOLEAN 13u /* one `{ tag, 0/1 }` slot       */

/* The function carrier of C3/C5 and its capture-cell array. */
#define DEAL_LAYOUT_FUNCTION          14u   /* one function value (C5)            */
#define DEAL_LAYOUT_FUNCTION_ENV      15u   /* one closure capture cell array     */

#define DEAL_LAYOUT_EMITTED_BASE  1024u

/*
 * The largest payload that owns an exact 16-byte size class (M1). At or below
 * this size a freed object of the same class has exactly the capacity the next
 * request of that class needs, so a free-list take is a head pop; above it the
 * collector uses geometric classes of at most 12.5% slack so the same head pop
 * still answers a request that fits the class.
 */
#define DEAL_ALLOC_SMALL_MAX_BYTES 4096u

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
 * One supplemental precise reference walk of a layout whose payload holds
 * managed references outside its fixed map (M1): an array's element storage is
 * walked by the owning `container` group's element metadata, so the collector
 * never guesses an element stride and never derives another group's layout.
 *
 * The walk is resumable and budgeted so marking work is bounded by references
 * rather than by objects. It visits the managed references from `position`
 * onward, consuming one unit of `*budget` for every reference slot it inspects
 * and stopping when the budget is spent, and it returns the position to resume
 * at, or `DEAL_TRACE_DONE` when every reference was visited. `position` 0 starts
 * the walk, and a walk must return the same references for the same object and
 * position, so a resumed walk continues exactly where the slice ended.
 */
#define DEAL_TRACE_DONE ((size_t)-1)

typedef size_t (*deal_layout_trace)(const void *object, size_t position,
                                    size_t *budget,
                                    deal_reference_visit visit, void *context);

/*
 * Registers the resumable walk of one layout (M1). The owning group registers
 * it at that representation's first use, before any object of the layout can be
 * allocated. One layout has at most one walk: registering the same function
 * again is the same registration, and a different function for the same layout
 * identity is an invariant fault.
 */
void deal_layout_trace_register(uint32_t layout, deal_layout_trace trace);

/*
 * One reclamation hook of a layout whose payload owns platform storage beside
 * the managed heap (M1): the Error record's copied code, message and origin
 * text, its optional expected/actual text and its frames array with one
 * duplicated label per active frame are platform memory, so the owning group
 * registers the hook that releases exactly that storage when the collector
 * reclaims the record. The sweep runs the hook while the payload is still
 * intact: the hook releases platform storage only, allocates nothing, does not
 * call back into the collector and leaves the payload's fields pointing at
 * either released-but-cleared or managed storage.
 */
typedef void (*deal_layout_release)(void *object);

/*
 * Registers the reclamation hook of one layout (M1). The owning group registers
 * it at that representation's first use, before any object of the layout can be
 * reclaimed. One layout has at most one hook: registering the same function
 * again is the same registration, and a different function for the same layout
 * identity is an invariant fault.
 */
void deal_layout_release_register(uint32_t layout, deal_layout_release release);

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
 * invariant fault. Exhaustion follows R10 before partial publication. The
 * allocation runs the safepoint first, so a bounded marking or sweeping slice
 * (or, under `DEAL_GC_STRESS`, one full collection) completes before this
 * object exists; the new object is marked while a cycle is in flight and is
 * therefore never reclaimed by that cycle.
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
 * `deal_container_array_trace`. This is the whole-object walk a group or a
 * consumer uses directly; the collector's own marking walks the same map
 * through its bounded slice cursor.
 */
void deal_object_trace(const void *object, deal_reference_visit visit,
                       void *context);

/* -- the insertion barrier (M2) ----------------------------------------- */

/*
 * The insertion (incremental-update) barrier for a store into `container` (M2).
 * It shades `value` exactly when the collector is marking and the referent is a
 * managed object that is not marked yet, so a referent reachable only through an
 * already-marked container survives the cycle. It allocates no managed object
 * and never changes a stored value. It is a no-op outside marking and for a
 * scalar payload or a nullish singleton; shading a live referent may grow the
 * collector's gray work list, whose platform-storage exhaustion is the R10
 * resource fault. A store into a root slot needs no barrier because the atomic
 * phase rescans every root source.
 */
void deal_store_barrier(void *container, void *value);

/*
 * The tag-aware insertion barrier of one boxed store (M2): the barrier runs
 * exactly when the datum's payload is managed, so an unknown or nullish slot
 * never shades scalar bits or a singleton.
 */
void deal_store_barrier_data(void *container, const deal_data *value);

/* -- the safepoint (M3) -------------------------------------------------- */

/*
 * The marking budget of one safepoint (M3): the maximum number of reference
 * visits — fixed reference-map slots and resumable layout-walk positions — one
 * slice performs. Marking work is therefore bounded by references, never by
 * objects: one object with a wide reference range is traced across several
 * slices instead of one unbounded trace, and a program holding a wide array or
 * a long chain cannot lengthen a single pause beyond this bound. The atomic
 * phase adds the root rescan, which visits exactly the registered root slots.
 */
#define DEAL_GC_MARK_BUDGET_REFERENCES 256u

/*
 * The allocation and loop-back-edge safepoint (M3, M7): one marking or sweeping
 * slice bounded by `DEAL_GC_MARK_BUDGET_REFERENCES` reference visits (a resumed
 * walk included), the atomic-phase root rescan to its fixpoint when the gray set
 * empties, and the `DEAL_GC_STRESS` full collection. Under stress it performs
 * one complete cycle to completion; otherwise it continues an in-flight cycle
 * and starts one when the collection trigger is reached. It changes collection
 * timing only, so no observable value, diagnostic or identity depends on it.
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

/*
 * The datum form of a captured-binding cell (C5, M4): one generation-pinned
 * cell whose value is the persistent tagged datum of the captured binding, so
 * a captured scalar keeps its payload beside a tag-aware managed reference and
 * no precise scan ever reads scalar bits as a heap address. The cell's pointer
 * entries and its datum entry are independent: a value cell stores
 * `deal_cell_store`/`deal_cell_read`, a datum cell stores
 * `deal_cell_store_data`/`deal_cell_read_data`, and each form leaves the other
 * empty.
 */
void *deal_cell_alloc_data(const deal_data *value, uint32_t generation);

/* The cell's current datum (absent-initialized as `undefined`). */
deal_data deal_cell_read_data(const void *cell);

/* Stores one datum through the tag-aware insertion barrier (C5/M2). */
void deal_cell_store_data(void *cell, const deal_data *value);

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

/* -- the collector (M1-M8) ----------------------------------------------- */

/*
 * One complete incremental cycle to its end, including the atomic phase and the
 * sweep (M7). A cycle already in flight is completed first, so the call always
 * observes and reclaims every object that was unreachable before it. Addresses
 * never change and queued platform storage is untouched; collection itself
 * never fails.
 */
void deal_gc_collect_full(void);

/*
 * The product-level collection statistics (M7). `live_bytes` is the sum of the
 * payload sizes of the currently allocated managed objects, `object_count` their
 * number, `allocated_bytes` the cumulative payload bytes handed out by
 * `deal_alloc`, `collections` the number of cycles completed including their
 * sweep, and `swept_objects` the number of objects reclaimed so far. The growth
 * policy counts the allocation footprint (header and capacity of every object)
 * rather than these payload bytes, so a program that allocates many zero-payload
 * objects still reaches the collection trigger. A null out-parameter is an
 * invariant fault.
 */
typedef struct {
    uint64_t live_bytes;
    uint64_t object_count;
    uint64_t allocated_bytes;
    uint64_t collections;
    uint64_t swept_objects;
} deal_gc_statistics_record;

void deal_gc_statistics(deal_gc_statistics_record *out);

#endif /* DEAL_NATIVE_MEMORY_H */
