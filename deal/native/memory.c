/*
 * deal/native/memory.c — the `memory` service group implementation.
 *
 * The M1 object model (header, layout registry, allocation entry, precise
 * reference-map walk and the layout reclamation hooks), the M2 insertion
 * barrier, the M3 incremental cycle (mark state, bounded gray work list,
 * atomic-phase root rescan, sweep cursor), the M4 root frames, the M5 scoped
 * roots, registered root sources and static root table, the M6 captured-binding
 * cells, and the M7 collection and statistics entries with the
 * `DEAL_GC_STRESS` switch.
 */
#include "deal/native/memory.h"

#include <stddef.h>
#include <string.h>

#include "deal/native/platform.h"
#include "deal/native/value.h"

/* =========================================================================
 * The managed object header (M1, runtime-private)
 * ========================================================================= */

/*
 * Every managed object is one allocation whose payload is preceded by this
 * header: the layout identity, the size class the sweep's free lists use, the
 * mark state the collector owns, the exact payload size and the free-list /
 * live-list link. No published header declares it, so no consumer can inspect
 * or size an object header. The header keeps the payload 16-byte aligned.
 */
typedef struct deal_object_header {
    uint32_t layout;
    uint32_t size_class;
    uint64_t mark;
    size_t size;
    struct deal_object_header *link;
} deal_object_header;

#define DEAL_OBJECT_ALIGNMENT 16u

_Static_assert(sizeof(deal_object_header) == 32u,
               "the managed object header is 32 bytes");
_Static_assert(sizeof(deal_object_header) % DEAL_OBJECT_ALIGNMENT == 0u,
               "the managed payload stays 16-byte aligned");

/*
 * The collector's own entries the allocation and store paths call; the
 * implementation follows the root-source section because its atomic phase
 * rescans every registered source (M3, M5).
 */
static int deal_gc_marking(void);
static int deal_gc_cycle_active(void);
static void deal_gc_shade(void *referent);
static void deal_gc_note_allocation(size_t size);

/* =========================================================================
 * Root frames (M4)
 * ========================================================================= */

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

void deal_root_frame_write_data(uint32_t slot, const deal_data *value) {
    if (value == NULL) {
        deal_platform_fault("root frame: null tagged datum");
    }
    deal_root_frame_write(slot, deal_data_is_managed(value)
                              ? deal_data_referent(value) : NULL);
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

/* =========================================================================
 * The layout registry (M1)
 * ========================================================================= */

/* One defined runtime representation's own layout. */
#define DEAL_RUNTIME_LAYOUT_MAX 16U

static const deal_layout *deal_runtime_layouts[DEAL_RUNTIME_LAYOUT_MAX];
static size_t deal_runtime_layout_count;

/* The emitted layout table, installed once at module initialization. */
static const deal_layout *deal_emitted_layouts;
static size_t deal_emitted_layout_count;

/* Validates one layout record; `minimum_id` bars a table from claiming a
 * runtime-reserved identity (or a runtime group from claiming a emitted one). */
static void deal_layout_validate(const deal_layout *layout, uint32_t minimum_id) {
    if (layout->id < minimum_id) {
        deal_platform_fault("layout: identity outside its reserved range");
    }
    if (layout->flags != 0u) {
        deal_platform_fault("layout: reserved flags are set");
    }
    if (layout->reference_count == 0u) {
        return;
    }
    if (layout->reference_offsets == NULL) {
        deal_platform_fault("layout: reference map without offsets");
    }
    if (layout->size == 0u) {
        deal_platform_fault("layout: reference map on a variable-payload layout");
    }
    for (size_t index = 0; index < layout->reference_count; index += 1u) {
        uint32_t offset = layout->reference_offsets[index];
        if (offset % (uint32_t)sizeof(void *) != 0u) {
            deal_platform_fault("layout: unaligned reference slot");
        }
        if (index > 0u
                && offset <= layout->reference_offsets[index - 1u]) {
            deal_platform_fault("layout: reference map is not ascending");
        }
        if ((size_t)offset + sizeof(void *) > layout->size) {
            deal_platform_fault("layout: reference slot outside the payload");
        }
    }
}

void deal_layout_define(const deal_layout *layout) {
    if (layout == NULL) {
        deal_platform_fault("layout: null definition");
    }
    deal_layout_validate(layout, DEAL_LAYOUT_STRING);
    if (layout->id >= DEAL_LAYOUT_EMITTED_BASE) {
        deal_platform_fault("layout: identity outside its reserved range");
    }
    for (size_t index = 0; index < deal_runtime_layout_count; index += 1u) {
        const deal_layout *existing = deal_runtime_layouts[index];
        if (existing->id != layout->id) {
            continue;
        }
        if (existing != layout) {
            deal_platform_fault("layout: identity defined twice");
        }
        return;
    }
    if (deal_runtime_layout_count == DEAL_RUNTIME_LAYOUT_MAX) {
        deal_platform_fault("layout: runtime layout limit exceeded");
    }
    deal_runtime_layouts[deal_runtime_layout_count] = layout;
    deal_runtime_layout_count += 1u;
}

void deal_layout_install(const deal_layout *layouts, size_t count) {
    if (count == 0u) {
        if (layouts != NULL) {
            deal_platform_fault("layout: count and records disagree");
        }
        return;
    }
    if (layouts == NULL) {
        deal_platform_fault("layout: count and records disagree");
    }
    if (deal_emitted_layouts != NULL && deal_emitted_layouts != layouts) {
        deal_platform_fault("layout: replaced after install");
    }
    for (size_t index = 0; index < count; index += 1u) {
        deal_layout_validate(&layouts[index], DEAL_LAYOUT_EMITTED_BASE);
        for (size_t other = 0; other < index; other += 1u) {
            if (layouts[other].id == layouts[index].id) {
                deal_platform_fault("layout: duplicate layout id");
            }
        }
        for (size_t other = 0; other < deal_runtime_layout_count; other += 1u) {
            if (deal_runtime_layouts[other]->id == layouts[index].id) {
                deal_platform_fault("layout: duplicate layout id");
            }
        }
    }
    deal_emitted_layouts = layouts;
    deal_emitted_layout_count = count;
}

const deal_layout *deal_layout_lookup(uint32_t layout) {
    for (size_t index = 0; index < deal_runtime_layout_count; index += 1u) {
        if (deal_runtime_layouts[index]->id == layout) {
            return deal_runtime_layouts[index];
        }
    }
    for (size_t index = 0; index < deal_emitted_layout_count; index += 1u) {
        if (deal_emitted_layouts[index].id == layout) {
            return &deal_emitted_layouts[index];
        }
    }
    return NULL;
}

/* =========================================================================
 * Allocation (M1, `Allocate`)
 * ========================================================================= */

/* The bump region size of one allocation block. */
#define DEAL_ALLOC_BLOCK_BYTES (64u * 1024u)

/* The sweep's free-list granularity; every payload is rounded up to it. */
#define DEAL_ALLOC_SIZE_CLASS_BYTES 16u
#define DEAL_ALLOC_SIZE_CLASS_MAX 1024u

typedef struct deal_alloc_block {
    struct deal_alloc_block *next;
    unsigned char *base;
    size_t capacity;
    size_t used;
} deal_alloc_block;

static deal_alloc_block *deal_alloc_blocks;
static deal_alloc_block *deal_alloc_current;

/*
 * The live-object list the sweep walks (M1, M3) and the size-class free lists
 * the sweep fills. An object is on exactly one of the two: the live list while
 * it is allocated, one free-list bucket after the sweep reclaimed it. A new
 * object is appended at the tail, so a sweep already in flight still visits
 * every object of the cycle (its mark state is cleared again) and never misreads
 * a freed one.
 */
static deal_object_header *deal_heap_head;
static deal_object_header *deal_heap_tail;
static deal_object_header *deal_heap_free[DEAL_ALLOC_SIZE_CLASS_MAX + 1u];

/* The size class of one aligned allocation (M1's sweep bucket). */
static uint32_t deal_alloc_size_class(size_t aligned) {
    size_t size_class = deal_platform_checked_add(
        aligned, DEAL_ALLOC_SIZE_CLASS_BYTES - 1u)
        / DEAL_ALLOC_SIZE_CLASS_BYTES;
    if (size_class == 0u) {
        size_class = 1u;
    }
    if (size_class > DEAL_ALLOC_SIZE_CLASS_MAX) {
        size_class = DEAL_ALLOC_SIZE_CLASS_MAX;
    }
    return (uint32_t)size_class;
}

/* One allocation's total size, aligned for its header (M1). The rounding runs
 * through the shared checked entry, so a total that cannot be aligned is the
 * R10 invariant fault instead of a wrapped region the payload store would
 * overrun. */
static size_t deal_alloc_aligned(size_t total) {
    return deal_platform_checked_round(total, (size_t)DEAL_OBJECT_ALIGNMENT);
}

static unsigned char *deal_alloc_carve(size_t aligned) {
    if (deal_alloc_current == NULL
            || deal_alloc_current->capacity - deal_alloc_current->used < aligned) {
        size_t block_bytes = aligned > DEAL_ALLOC_BLOCK_BYTES
            ? aligned : DEAL_ALLOC_BLOCK_BYTES;
        deal_alloc_block *block = deal_platform_allocate(sizeof(*block));
        if (block == NULL) {
            deal_platform_fault("allocation bookkeeping failed");
        }
        block->base = deal_platform_page_alloc(block_bytes);
        block->capacity = block_bytes;
        block->used = 0;
        block->next = deal_alloc_blocks;
        deal_alloc_blocks = block;
        deal_alloc_current = block;
    }
    unsigned char *storage = deal_alloc_current->base + deal_alloc_current->used;
    deal_alloc_current->used += aligned;
    return storage;
}

/* Appends one live object at the tail of the live list (M3). */
static void deal_heap_append(deal_object_header *header) {
    header->link = NULL;
    if (deal_heap_tail == NULL) {
        deal_heap_head = header;
    } else {
        deal_heap_tail->link = header;
    }
    deal_heap_tail = header;
}

/*
 * Takes one free object of `size_class` whose payload holds at least `size`
 * bytes, or NULL when the bucket holds no usable object. Sizes inside one
 * bucket differ by less than the size-class granularity, so the search is one
 * short walk of the bucket.
 */
static deal_object_header *deal_heap_take_free(uint32_t size_class,
                                               size_t size) {
    deal_object_header **link = &deal_heap_free[size_class];
    while (*link != NULL) {
        deal_object_header *header = *link;
        if (header->size >= size) {
            *link = header->link;
            return header;
        }
        link = &header->link;
    }
    return NULL;
}

/* Frees one swept object into its size class with a clean mark (M1, M3). */
static void deal_heap_push_free(deal_object_header *header) {
    header->mark = 0u;
    header->link = deal_heap_free[header->size_class];
    deal_heap_free[header->size_class] = header;
}

void *deal_alloc(uint32_t layout, size_t size) {
    const deal_layout *record = deal_layout_lookup(layout);
    if (record == NULL) {
        deal_platform_fault("alloc: unknown layout");
    }
    if (size < record->size) {
        deal_platform_fault("alloc: size below the layout payload size");
    }
    /* The header-and-payload total and its alignment are computed before the
     * safepoint: a size that cannot exist is the R10 invariant fault and leaves
     * the heap exactly as it was. */
    size_t aligned = deal_alloc_aligned(
        deal_platform_checked_add(sizeof(deal_object_header), size));
    uint32_t size_class = deal_alloc_size_class(aligned);
    /* The safepoint runs before this object exists, so a bounded marking or
     * sweeping slice (or the whole collection under DEAL_GC_STRESS) completes
     * against the objects that exist already (M3, M7). */
    deal_safepoint();
    deal_object_header *header = deal_heap_take_free(size_class, size);
    if (header == NULL) {
        header = (deal_object_header *)(void *)deal_alloc_carve(aligned);
    }
    header->layout = layout;
    header->size_class = size_class;
    /* An object allocated during a cycle is marked and therefore excluded from
     * that cycle's sweep (M1); the sweep still visits it, so its mark state is
     * clean again when the cycle ends. */
    header->mark = deal_gc_cycle_active() != 0 ? 1u : 0u;
    header->size = size;
    deal_heap_append(header);
    unsigned char *payload = (unsigned char *)(void *)header + sizeof(*header);
    if (size != 0u) {
        /* A fresh allocation is zero-initialized (Allocate); reused size-class
         * storage is zeroed here too. */
        memset(payload, 0, size);
    }
    deal_gc_note_allocation(size);
    return payload;
}

/* The header of one managed object; the nullish singletons are not objects. */
static const deal_object_header *deal_object_header_of(const void *object) {
    if (object == NULL || deal_value_is_null(object)
            || deal_value_is_undefined(object)) {
        deal_platform_fault("object: not a managed object");
    }
    return (const deal_object_header *)(const void *)
        ((const unsigned char *)object - sizeof(deal_object_header));
}

uint32_t deal_object_layout(const void *object) {
    return deal_object_header_of(object)->layout;
}

size_t deal_object_size(const void *object) {
    return deal_object_header_of(object)->size;
}

/* One root/reference-map visit: only a managed referent is a heap address. */
static void deal_visit_reference(void *referent, deal_reference_visit visit,
                                 void *context) {
    if (referent == NULL || deal_value_is_null(referent)
            || deal_value_is_undefined(referent)) {
        return;
    }
    visit(referent, context);
}

void deal_object_trace(const void *object, deal_reference_visit visit,
                       void *context) {
    if (visit == NULL) {
        deal_platform_fault("object trace: null visitor");
    }
    const deal_object_header *header = deal_object_header_of(object);
    const deal_layout *record = deal_layout_lookup(header->layout);
    if (record == NULL) {
        deal_platform_fault("object trace: unknown layout");
    }
    for (size_t index = 0; index < record->reference_count; index += 1u) {
        uint32_t offset = record->reference_offsets[index];
        void *referent = NULL;
        memcpy(&referent, (const unsigned char *)object + offset,
               sizeof(referent));
        deal_visit_reference(referent, visit, context);
    }
}

/* =========================================================================
 * The insertion barrier (M2)
 * ========================================================================= */

void deal_store_barrier(void *container, void *value) {
    /* M2 shades `value` when the collector is marking and the referent is a
     * managed object that is not marked yet, so a referent reachable only
     * through an already-marked container survives the cycle. Outside marking
     * the barrier is a no-op, and a scalar payload and a nullish singleton are
     * never shaded. It allocates no managed object and changes no stored value;
     * shading a live referent grows the gray work list, whose platform storage
     * is the R10 resource fault. */
    (void)container;
    if (deal_gc_marking() == 0) {
        return;
    }
    deal_gc_shade(value);
}

void deal_store_barrier_data(void *container, const deal_data *value) {
    if (value == NULL) {
        deal_platform_fault("barrier: null tagged datum");
    }
    if (deal_data_is_managed(value)) {
        deal_store_barrier(container, deal_data_referent(value));
    }
}

/* =========================================================================
 * Captured-binding cells (C5, M6)
 * ========================================================================= */

/*
 * One captured binding generation: one value slot and the generation. The
 * closure captures the cell pointer, so every alias observes one identity and
 * one value; the value slot is a managed-reference slot in the layout's precise
 * map, so every store runs the barrier.
 */
typedef struct {
    uint32_t generation;
    uint32_t reserved;
    void *value;
} deal_cell;

static const uint32_t deal_cell_reference_offsets[] = {
    (uint32_t)offsetof(deal_cell, value)
};

static const deal_layout deal_cell_layout = {
    DEAL_LAYOUT_CELL, 0u, sizeof(deal_cell),
    deal_cell_reference_offsets, 1u
};

void *deal_cell_alloc_generation(void *value, uint32_t generation) {
    deal_layout_define(&deal_cell_layout);
    deal_cell *cell = deal_alloc(DEAL_LAYOUT_CELL, sizeof(*cell));
    cell->generation = generation;
    cell->reserved = 0u;
    cell->value = NULL;
    if (value != NULL) {
        deal_store_barrier(cell, value);
        cell->value = value;
    }
    return cell;
}

void *deal_cell_alloc(void *value) {
    return deal_cell_alloc_generation(value, 0u);
}

/* The payload of one cell; a non-cell pointer is an invariant fault. */
static deal_cell *deal_cell_of(const void *cell) {
    if (cell == NULL || deal_object_layout(cell) != DEAL_LAYOUT_CELL) {
        deal_platform_fault("cell: not a captured-binding cell");
    }
    return (deal_cell *)(void *)cell;
}

void *deal_cell_read(const void *cell) {
    return deal_cell_of(cell)->value;
}

uint32_t deal_cell_generation(const void *cell) {
    return deal_cell_of(cell)->generation;
}

void deal_cell_store(void *cell, void *value) {
    deal_cell *payload = deal_cell_of(cell);
    if (value != NULL) {
        deal_store_barrier(payload, value);
    }
    payload->value = value;
}

/* =========================================================================
 * Scoped runtime roots (M5)
 * ========================================================================= */

typedef struct {
    void **slots;
    size_t capacity;
    size_t used;
} deal_root_scope_record;

static deal_root_scope_record *deal_root_scopes;
static size_t deal_root_scopes_capacity;
static uint32_t deal_root_scope_depth_value;

static void deal_root_scope_reserve_table(void) {
    if (deal_root_scope_depth_value < deal_root_scopes_capacity) {
        return;
    }
    size_t capacity = deal_root_scopes_capacity == 0
        ? 16u : deal_root_scopes_capacity * 2u;
    deal_root_scope_record *grown =
        deal_platform_allocate(capacity * sizeof(*grown));
    if (grown == NULL) {
        deal_platform_fault("root scope: table allocation failed");
    }
    if (deal_root_scopes != NULL) {
        memcpy(grown, deal_root_scopes,
               (size_t)deal_root_scope_depth_value * sizeof(*grown));
        deal_platform_release(deal_root_scopes);
    }
    deal_root_scopes = grown;
    deal_root_scopes_capacity = capacity;
}

void deal_root_scope_push(void) {
    deal_root_scope_reserve_table();
    deal_root_scope_record *scope =
        &deal_root_scopes[deal_root_scope_depth_value];
    scope->slots = NULL;
    scope->capacity = 0u;
    scope->used = 0u;
    deal_root_scope_depth_value += 1u;
}

/* The current scope; a hold/release/pop without one is an invariant fault. */
static deal_root_scope_record *deal_root_scope_current(void) {
    if (deal_root_scope_depth_value == 0u) {
        deal_platform_fault("root scope: no active scope");
    }
    return &deal_root_scopes[deal_root_scope_depth_value - 1u];
}

uint32_t deal_root_scope_hold(void *referent) {
    deal_root_scope_record *scope = deal_root_scope_current();
    if (scope->used == scope->capacity) {
        size_t capacity = scope->capacity == 0
            ? 8u : scope->capacity * 2u;
        void **grown = deal_platform_allocate(capacity * sizeof(void *));
        if (grown == NULL) {
            deal_platform_fault("root scope: slot allocation failed");
        }
        if (scope->slots != NULL) {
            memcpy(grown, scope->slots, scope->used * sizeof(void *));
            deal_platform_release(scope->slots);
        }
        scope->slots = grown;
        scope->capacity = capacity;
    }
    scope->slots[scope->used] = referent;
    scope->used += 1u;
    return (uint32_t)(scope->used - 1u);
}

uint32_t deal_root_scope_hold_data(const deal_data *value) {
    if (value == NULL) {
        deal_platform_fault("root scope: null tagged datum");
    }
    return deal_root_scope_hold(deal_data_is_managed(value)
                                    ? deal_data_referent(value) : NULL);
}

void deal_root_scope_release(uint32_t slot) {
    deal_root_scope_record *scope = deal_root_scope_current();
    if ((size_t)slot >= scope->used) {
        deal_platform_fault("root scope: release outside the active scope");
    }
    scope->slots[slot] = NULL;
}

void deal_root_scope_pop(void) {
    deal_root_scope_record *scope = deal_root_scope_current();
    deal_platform_release(scope->slots);
    scope->slots = NULL;
    scope->capacity = 0u;
    scope->used = 0u;
    deal_root_scope_depth_value -= 1u;
}

uint32_t deal_root_scope_depth(void) {
    return deal_root_scope_depth_value;
}

/* =========================================================================
 * Registered root sources and the static root table (M5)
 * ========================================================================= */

typedef struct {
    deal_root_source_scan scan;
    void *context;
} deal_root_source_record;

static deal_root_source_record *deal_root_sources;
static size_t deal_root_sources_count;
static size_t deal_root_sources_capacity;

static void ***deal_static_root_slots;
static size_t deal_static_root_count_value;
static size_t deal_static_root_capacity;

void deal_root_source_register(deal_root_source_scan scan, void *context) {
    if (scan == NULL) {
        deal_platform_fault("root source: null scan");
    }
    if (deal_root_sources_count == deal_root_sources_capacity) {
        size_t capacity = deal_root_sources_capacity == 0
            ? 8u : deal_root_sources_capacity * 2u;
        deal_root_source_record *grown =
            deal_platform_allocate(capacity * sizeof(*grown));
        if (grown == NULL) {
            deal_platform_fault("root source: table allocation failed");
        }
        if (deal_root_sources != NULL) {
            memcpy(grown, deal_root_sources,
                   deal_root_sources_count * sizeof(*grown));
            deal_platform_release(deal_root_sources);
        }
        deal_root_sources = grown;
        deal_root_sources_capacity = capacity;
    }
    deal_root_sources[deal_root_sources_count].scan = scan;
    deal_root_sources[deal_root_sources_count].context = context;
    deal_root_sources_count += 1u;
}

void deal_root_source_unregister(deal_root_source_scan scan, void *context) {
    if (scan == NULL) {
        deal_platform_fault("root source: null scan");
    }
    for (size_t index = deal_root_sources_count; index > 0u; index -= 1u) {
        deal_root_source_record *record = &deal_root_sources[index - 1u];
        if (record->scan != scan || record->context != context) {
            continue;
        }
        for (size_t move = index; move < deal_root_sources_count; move += 1u) {
            deal_root_sources[move - 1u] = deal_root_sources[move];
        }
        deal_root_sources_count -= 1u;
        return;
    }
    deal_platform_fault("root source: unknown source");
}

void deal_root_sources_enumerate(deal_reference_visit visit, void *context) {
    if (visit == NULL) {
        deal_platform_fault("root sources: null visitor");
    }
    for (size_t index = 0; index < deal_root_slots_used; index += 1u) {
        deal_visit_reference(deal_root_slots[index], visit, context);
    }
    for (uint32_t scope = 0; scope < deal_root_scope_depth_value; scope += 1u) {
        deal_root_scope_record *record = &deal_root_scopes[scope];
        for (size_t index = 0; index < record->used; index += 1u) {
            deal_visit_reference(record->slots[index], visit, context);
        }
    }
    for (size_t index = 0; index < deal_root_sources_count; index += 1u) {
        deal_root_sources[index].scan(visit, context,
                                      deal_root_sources[index].context);
    }
    for (size_t index = 0; index < deal_static_root_count_value; index += 1u) {
        deal_visit_reference(*deal_static_root_slots[index], visit, context);
    }
}

void deal_static_root_register(void **slot) {
    if (slot == NULL) {
        deal_platform_fault("static root: null slot");
    }
    if (deal_static_root_count_value == deal_static_root_capacity) {
        size_t capacity = deal_static_root_capacity == 0
            ? 8u : deal_static_root_capacity * 2u;
        void ***grown = deal_platform_allocate(capacity * sizeof(*grown));
        if (grown == NULL) {
            deal_platform_fault("static root: table allocation failed");
        }
        if (deal_static_root_slots != NULL) {
            memcpy(grown, deal_static_root_slots,
                   deal_static_root_count_value * sizeof(*grown));
            deal_platform_release(deal_static_root_slots);
        }
        deal_static_root_slots = grown;
        deal_static_root_capacity = capacity;
    }
    deal_static_root_slots[deal_static_root_count_value] = slot;
    deal_static_root_count_value += 1u;
}

void deal_static_root_unregister(void **slot) {
    for (size_t index = deal_static_root_count_value; index > 0u; index -= 1u) {
        if (deal_static_root_slots[index - 1u] != slot) {
            continue;
        }
        for (size_t move = index; move < deal_static_root_count_value;
                move += 1u) {
            deal_static_root_slots[move - 1u] = deal_static_root_slots[move];
        }
        deal_static_root_count_value -= 1u;
        return;
    }
    deal_platform_fault("static root: unknown slot");
}

size_t deal_static_root_count(void) {
    return deal_static_root_count_value;
}

/* =========================================================================
 * The supplemental layout walks (M1)
 * ========================================================================= */

/*
 * A layout whose payload holds managed references outside its fixed map
 * registers its own walk at that representation's first use: an array's element
 * storage is walked through the `container` group's element layout metadata, so
 * the collector never derives another group's stride and never guesses.
 */
#define DEAL_LAYOUT_TRACE_MAX 8U

static uint32_t deal_layout_trace_ids[DEAL_LAYOUT_TRACE_MAX];
static deal_layout_trace deal_layout_traces[DEAL_LAYOUT_TRACE_MAX];
static size_t deal_layout_trace_count;

void deal_layout_trace_register(uint32_t layout, deal_layout_trace trace) {
    if (trace == NULL) {
        deal_platform_fault("layout tracer: null trace");
    }
    for (size_t index = 0; index < deal_layout_trace_count; index += 1u) {
        if (deal_layout_trace_ids[index] != layout) {
            continue;
        }
        if (deal_layout_traces[index] != trace) {
            deal_platform_fault("layout tracer: identity traced twice");
        }
        return;
    }
    if (deal_layout_trace_count == DEAL_LAYOUT_TRACE_MAX) {
        deal_platform_fault("layout tracer: table limit exceeded");
    }
    deal_layout_trace_ids[deal_layout_trace_count] = layout;
    deal_layout_traces[deal_layout_trace_count] = trace;
    deal_layout_trace_count += 1u;
}

/* The registered walk of one layout, or NULL when its fixed map is complete. */
static deal_layout_trace deal_layout_trace_of(uint32_t layout) {
    for (size_t index = 0; index < deal_layout_trace_count; index += 1u) {
        if (deal_layout_trace_ids[index] == layout) {
            return deal_layout_traces[index];
        }
    }
    return NULL;
}

/* =========================================================================
 * The layout reclamation hooks (M1)
 * ========================================================================= */

/*
 * A layout whose payload owns platform storage beside the managed heap
 * registers the hook that releases it at that representation's first use: the
 * Error record's copied text and frame labels are platform memory, so the sweep
 * runs the hook while the payload is still intact. A layout without a hook owns
 * no storage outside the managed heap, which is every other runtime layout.
 */
#define DEAL_LAYOUT_RELEASE_MAX 8U

static uint32_t deal_layout_release_ids[DEAL_LAYOUT_RELEASE_MAX];
static deal_layout_release deal_layout_releases[DEAL_LAYOUT_RELEASE_MAX];
static size_t deal_layout_release_count;

void deal_layout_release_register(uint32_t layout, deal_layout_release release) {
    if (release == NULL) {
        deal_platform_fault("layout release: null release");
    }
    for (size_t index = 0; index < deal_layout_release_count; index += 1u) {
        if (deal_layout_release_ids[index] != layout) {
            continue;
        }
        if (deal_layout_releases[index] != release) {
            deal_platform_fault("layout release: identity released twice");
        }
        return;
    }
    if (deal_layout_release_count == DEAL_LAYOUT_RELEASE_MAX) {
        deal_platform_fault("layout release: table limit exceeded");
    }
    deal_layout_release_ids[deal_layout_release_count] = layout;
    deal_layout_releases[deal_layout_release_count] = release;
    deal_layout_release_count += 1u;
}

/* The registered hook of one layout, or NULL when it owns no platform
 * storage. */
static deal_layout_release deal_layout_release_of(uint32_t layout) {
    for (size_t index = 0; index < deal_layout_release_count; index += 1u) {
        if (deal_layout_release_ids[index] == layout) {
            return deal_layout_releases[index];
        }
    }
    return NULL;
}

/* =========================================================================
 * The collector (M1, M3, M7)
 * ========================================================================= */

/* One bounded marking or sweeping slice per safepoint (M3). */
#define DEAL_GC_MARK_SLICE 64U
#define DEAL_GC_SWEEP_SLICE 64U

/*
 * The minimum allocation volume that starts a cycle. The trigger is one
 * internal growth policy — a cycle starts once the bytes allocated since the
 * last completed cycle reach the larger of this minimum and the payload that
 * survived the last completed cycle — so no source-visible heap limit or pause
 * target exists (M8). The surviving payload is the post-sweep live byte count,
 * never the allocated-but-not-yet-swept count, so uncollected garbage can never
 * raise the bar that would reclaim it.
 */
#define DEAL_GC_MIN_TRIGGER_BYTES (256U * 1024U)

typedef enum {
    DEAL_GC_IDLE = 0,
    DEAL_GC_MARKING = 1,
    DEAL_GC_SWEEPING = 2
} deal_gc_phase;

static deal_gc_phase deal_gc_phase_value = DEAL_GC_IDLE;

/* The gray work list: marked objects whose children are not traced yet (M1). */
static void **deal_gc_gray;
static size_t deal_gc_gray_count;
static size_t deal_gc_gray_capacity;

/* The sweep cursor: the next object to inspect and the last one kept (M3). */
static deal_object_header *deal_gc_sweep_previous;
static deal_object_header *deal_gc_sweep_next;

/* The one diagnostic switch (M7): -1 unread, 0 off, 1 on. */
static int deal_gc_stress = -1;

/* 1 while a collection runs, so a re-entrant safepoint cannot start a cycle. */
static int deal_gc_in_collection;

/* The product-level counters (M7). */
static uint64_t deal_gc_live_bytes;
static uint64_t deal_gc_object_count;
static uint64_t deal_gc_allocated_bytes;
static uint64_t deal_gc_allocated_since_cycle;
static uint64_t deal_gc_collections;
static uint64_t deal_gc_swept_objects;

/* The allocation volume that starts the next cycle (M8's internal policy). */
static uint64_t deal_gc_trigger_bytes = (uint64_t)DEAL_GC_MIN_TRIGGER_BYTES;

static int deal_gc_marking(void) {
    return deal_gc_phase_value == DEAL_GC_MARKING ? 1 : 0;
}

static int deal_gc_cycle_active(void) {
    return deal_gc_phase_value != DEAL_GC_IDLE ? 1 : 0;
}

/* 1 when the datum names a managed object and is not a nullish singleton. */
static int deal_gc_reference_is_managed(const void *referent) {
    return referent != NULL && !deal_value_is_null(referent)
        && !deal_value_is_undefined(referent);
}

static void deal_gc_note_allocation(size_t size) {
    deal_gc_live_bytes += (uint64_t)size;
    deal_gc_object_count += 1u;
    deal_gc_allocated_bytes += (uint64_t)size;
    deal_gc_allocated_since_cycle += (uint64_t)size;
}

static int deal_gc_stress_enabled(void) {
    if (deal_gc_stress < 0) {
        const char *value = deal_platform_environment("DEAL_GC_STRESS");
        deal_gc_stress = value != NULL && value[0] == '1' && value[1] == '\0'
            ? 1 : 0;
    }
    return deal_gc_stress;
}

static int deal_gc_trigger_reached(void) {
    return deal_gc_allocated_since_cycle >= deal_gc_trigger_bytes ? 1 : 0;
}

/* Appends one marked object to the gray work list (M1) in platform memory. */
static void deal_gc_gray_push(void *referent) {
    if (deal_gc_gray_count == deal_gc_gray_capacity) {
        size_t capacity = deal_gc_gray_capacity == 0u
            ? 256u : deal_gc_gray_capacity * 2u;
        void **grown = deal_platform_allocate(capacity * sizeof(void *));
        if (grown == NULL) {
            deal_platform_fault("gc: gray work list allocation failed");
        }
        if (deal_gc_gray != NULL) {
            memcpy(grown, deal_gc_gray,
                   deal_gc_gray_count * sizeof(void *));
            deal_platform_release(deal_gc_gray);
        }
        deal_gc_gray = grown;
        deal_gc_gray_capacity = capacity;
    }
    deal_gc_gray[deal_gc_gray_count] = referent;
    deal_gc_gray_count += 1u;
}

/*
 * Marks one managed referent and enqueues it (M1). Idempotent, so a referent
 * that several paths reach is traced once. A nullish singleton, a scalar payload
 * and a crossing handle carry no heap address and are never marked.
 */
static void deal_gc_shade(void *referent) {
    if (deal_gc_reference_is_managed(referent) == 0) {
        return;
    }
    deal_object_header *header = (deal_object_header *)(void *)
        ((unsigned char *)referent - sizeof(deal_object_header));
    if (header->mark != 0u) {
        return;
    }
    header->mark = 1u;
    deal_gc_gray_push(referent);
}

static void deal_gc_visit_reference(void *referent, void *context) {
    (void)context;
    deal_gc_shade(referent);
}

/* Traces one marked object: its fixed map plus its registered layout walk. */
static void deal_gc_trace_object(void *object) {
    deal_object_trace(object, deal_gc_visit_reference, NULL);
    deal_layout_trace trace = deal_layout_trace_of(deal_object_layout(object));
    if (trace != NULL) {
        trace(object, deal_gc_visit_reference, NULL);
    }
}

/* Releases one reclaimed object's platform-owned members through its layout's
 * registered hook, while the payload is still intact (M1). */
static void deal_gc_release_object(void *object) {
    deal_layout_release release =
        deal_layout_release_of(deal_object_layout(object));
    if (release != NULL) {
        release(object);
    }
}

/* Pops one gray object and traces it; 1 when one was traced. */
static int deal_gc_trace_one(void) {
    if (deal_gc_gray_count == 0u) {
        return 0;
    }
    deal_gc_gray_count -= 1u;
    deal_gc_trace_object(deal_gc_gray[deal_gc_gray_count]);
    return 1;
}

/* Drains the gray work list to empty (the atomic phase's own bound). */
static void deal_gc_drain(void) {
    while (deal_gc_trace_one() != 0) {
        /* the queue populates itself as objects reveal children */
    }
}

/* Ends one cycle: the sweep reached the end of the live list (M3, M7). */
static void deal_gc_finish_cycle(void) {
    deal_gc_phase_value = DEAL_GC_IDLE;
    deal_gc_gray_count = 0u;
    deal_gc_sweep_previous = NULL;
    deal_gc_sweep_next = NULL;
    deal_gc_allocated_since_cycle = 0u;
    deal_gc_collections += 1u;
    /* What survived this cycle is the payload the next cycle has to preserve. */
    deal_gc_trigger_bytes =
        deal_gc_live_bytes < (uint64_t)DEAL_GC_MIN_TRIGGER_BYTES
            ? (uint64_t)DEAL_GC_MIN_TRIGGER_BYTES : deal_gc_live_bytes;
}

/*
 * The atomic phase (M3): the gray set is empty, so every root source is
 * rescanned — the generated root frames, the scoped runtime roots, every
 * registered source (host crossing scopes, the handle table, the
 * open-operation table) and the static root table of export tables, class
 * plans and descriptor atoms — the gray set is drained to empty, and the cycle
 * enters its sweep. Exactly one mutator thread exists, so no other thread can
 * allocate, mutate, read or free a managed object during the phase.
 */
static void deal_gc_atomic_phase(void) {
    deal_root_sources_enumerate(deal_gc_visit_reference, NULL);
    deal_gc_drain();
    deal_gc_phase_value = DEAL_GC_SWEEPING;
    deal_gc_sweep_previous = NULL;
    deal_gc_sweep_next = deal_heap_head;
    if (deal_gc_sweep_next == NULL) {
        deal_gc_finish_cycle();
    }
}

/* One bounded marking slice; an emptied gray set enters the atomic phase. */
static void deal_gc_mark_slice(size_t budget) {
    while (budget > 0u && deal_gc_trace_one() != 0) {
        budget -= 1u;
    }
    if (deal_gc_phase_value == DEAL_GC_MARKING && deal_gc_gray_count == 0u) {
        deal_gc_atomic_phase();
    }
}

/* One bounded sweeping slice; the end of the live list ends the cycle. */
static void deal_gc_sweep_slice(size_t budget) {
    if (deal_gc_phase_value != DEAL_GC_SWEEPING) {
        return;
    }
    while (deal_gc_sweep_next != NULL && budget > 0u) {
        deal_object_header *header = deal_gc_sweep_next;
        deal_gc_sweep_next = header->link;
        if (header->mark == 0u) {
            /* Unreachable: unlink it from the live list and free it into its
             * size class (M1, M3). Addresses of the survivors never change. */
            if (deal_gc_sweep_previous == NULL) {
                deal_heap_head = deal_gc_sweep_next;
            } else {
                deal_gc_sweep_previous->link = deal_gc_sweep_next;
            }
            if (deal_gc_sweep_next == NULL) {
                deal_heap_tail = deal_gc_sweep_previous;
            }
            deal_gc_live_bytes -= (uint64_t)header->size;
            deal_gc_object_count -= 1u;
            deal_gc_swept_objects += 1u;
            /* The payload is still intact: its layout's reclamation hook
             * releases the platform storage the object owns (M1). */
            deal_gc_release_object(
                (unsigned char *)(void *)header + sizeof(*header));
            deal_heap_push_free(header);
        } else {
            /* Reachable: it survives and its mark state is clean for the next
             * cycle. */
            header->mark = 0u;
            deal_gc_sweep_previous = header;
        }
        budget -= 1u;
    }
    if (deal_gc_sweep_next == NULL) {
        deal_gc_finish_cycle();
    }
}

/* Starts one cycle by marking from every root source (M1, M3). */
static void deal_gc_start_cycle(void) {
    deal_gc_phase_value = DEAL_GC_MARKING;
    deal_gc_gray_count = 0u;
    deal_gc_allocated_since_cycle = 0u;
    deal_gc_sweep_previous = NULL;
    deal_gc_sweep_next = NULL;
    deal_root_sources_enumerate(deal_gc_visit_reference, NULL);
}

/* Completes the cycle in flight, whatever phase it reached (M3). */
static void deal_gc_complete_cycle(void) {
    if (deal_gc_phase_value == DEAL_GC_MARKING) {
        deal_gc_drain();
        deal_gc_atomic_phase();
    }
    if (deal_gc_phase_value == DEAL_GC_SWEEPING) {
        deal_gc_sweep_slice(SIZE_MAX);
    }
}

/* =========================================================================
 * The safepoint (M3)
 * ========================================================================= */

/*
 * The allocation and loop-back-edge safepoint (M3, M7): one bounded marking or
 * sweeping slice, the atomic-phase root rescan when the gray set empties, and
 * the `DEAL_GC_STRESS` full collection. A re-entrant safepoint is impossible
 * (the collector allocates only platform storage), and one reached from inside
 * a collection is a no-op rather than a second cycle.
 */
void deal_safepoint(void) {
    if (deal_gc_in_collection != 0) {
        return;
    }
    if (deal_gc_stress_enabled() != 0) {
        deal_gc_collect_full();
        return;
    }
    switch (deal_gc_phase_value) {
    case DEAL_GC_IDLE:
        if (deal_gc_trigger_reached() == 0) {
            return;
        }
        deal_gc_start_cycle();
        deal_gc_mark_slice(DEAL_GC_MARK_SLICE);
        return;
    case DEAL_GC_MARKING:
        deal_gc_mark_slice(DEAL_GC_MARK_SLICE);
        return;
    default:
        deal_gc_sweep_slice(DEAL_GC_SWEEP_SLICE);
        return;
    }
}

void deal_gc_collect_full(void) {
    if (deal_gc_in_collection != 0) {
        deal_platform_fault("gc: nested full collection");
    }
    deal_gc_in_collection = 1;
    /* A cycle already in flight is completed first, so the fresh complete cycle
     * below observes every object that exists now. */
    deal_gc_complete_cycle();
    deal_gc_start_cycle();
    deal_gc_drain();
    deal_gc_atomic_phase();
    deal_gc_complete_cycle();
    deal_gc_in_collection = 0;
}

void deal_gc_statistics(deal_gc_statistics_record *out) {
    if (out == NULL) {
        deal_platform_fault("gc statistics: null out-parameter");
    }
    out->live_bytes = deal_gc_live_bytes;
    out->object_count = deal_gc_object_count;
    out->allocated_bytes = deal_gc_allocated_bytes;
    out->collections = deal_gc_collections;
    out->swept_objects = deal_gc_swept_objects;
}
