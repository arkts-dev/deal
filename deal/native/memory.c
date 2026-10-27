/*
 * deal/native/memory.c — the `memory` service group implementation.
 *
 * The M1 object model (header, layout registry, allocation entry and precise
 * reference-map walk), the M2 barrier seam, the M3 safepoint seam, the M4 root
 * frames, the M5 scoped roots, registered root sources and static root table,
 * and the M6 captured-binding cells. Collection itself, the size-class free
 * lists the sweep fills and the statistics entries land with the
 * runtime-collector child, which extends this translation unit without
 * redefining any entry delivered here.
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
 * mark state the collector owns, the exact payload size and one reserved word
 * that keeps the payload 16-byte aligned. No published header declares it, so
 * no consumer can inspect or size an object header.
 */
typedef struct {
    uint32_t layout;
    uint32_t size_class;
    uint64_t mark;
    size_t size;
    uint64_t reserved;
} deal_object_header;

#define DEAL_OBJECT_ALIGNMENT 16u

_Static_assert(sizeof(deal_object_header) == 32u,
               "the managed object header is 32 bytes");
_Static_assert(sizeof(deal_object_header) % DEAL_OBJECT_ALIGNMENT == 0u,
               "the managed payload stays 16-byte aligned");

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
#define DEAL_RUNTIME_LAYOUT_MAX 8U

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

/* The size class of one rounded allocation (M1's sweep bucket). */
static uint32_t deal_alloc_size_class(size_t total) {
    size_t size_class = (total + DEAL_ALLOC_SIZE_CLASS_BYTES - 1u)
        / DEAL_ALLOC_SIZE_CLASS_BYTES;
    if (size_class == 0u) {
        size_class = 1u;
    }
    if (size_class > DEAL_ALLOC_SIZE_CLASS_MAX) {
        size_class = DEAL_ALLOC_SIZE_CLASS_MAX;
    }
    return (uint32_t)size_class;
}

static unsigned char *deal_alloc_carve(size_t total) {
    size_t aligned = (total + (DEAL_OBJECT_ALIGNMENT - 1u))
        & ~(size_t)(DEAL_OBJECT_ALIGNMENT - 1u);
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

void *deal_alloc(uint32_t layout, size_t size) {
    const deal_layout *record = deal_layout_lookup(layout);
    if (record == NULL) {
        deal_platform_fault("alloc: unknown layout");
    }
    if (size < record->size) {
        deal_platform_fault("alloc: size below the layout payload size");
    }
    if (size > SIZE_MAX - sizeof(deal_object_header)) {
        deal_platform_fault("alloc: size overflow");
    }
    size_t total = sizeof(deal_object_header) + size;
    unsigned char *storage = deal_alloc_carve(total);
    deal_object_header *header = (deal_object_header *)(void *)storage;
    header->layout = layout;
    header->size_class = deal_alloc_size_class(
        (total + (DEAL_OBJECT_ALIGNMENT - 1u))
            & ~(size_t)(DEAL_OBJECT_ALIGNMENT - 1u));
    header->mark = 0u;
    header->size = size;
    header->reserved = 0u;
    unsigned char *payload = storage + sizeof(deal_object_header);
    if (size != 0u) {
        /* A fresh allocation is zero-initialized (Allocate); the collector's
         * reused size-class storage is zeroed here too. */
        memset(payload, 0, size);
    }
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
    /* M2 shades `value` when the collector is marking and the referent is not
     * yet marked. The collector child owns marking, so no cycle can be in
     * flight here and the barrier is the recorded staged no-op: the entry, its
     * signature and every call site are already the production ones. */
    (void)container;
    (void)value;
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
 * The safepoint (M3)
 * ========================================================================= */

void deal_safepoint(void) {
    /* The collector's bounded marking/sweeping slice belongs to the collector
     * child. Before it no cycle is in flight, so reaching a safepoint changes
     * no observable behavior. */
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
