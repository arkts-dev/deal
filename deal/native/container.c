/*
 * deal/native/container.c — the `container` service group implementation.
 *
 * Typed array storage with its recorded element layout, insertion-ordered
 * record storage with distinct absence / present-undefined / present-null
 * states, the checked discrete-position conversion, the insertion barrier of
 * every managed store and the precise element walk of `container.h`
 * (`llvm-native-runtime-value-model` R2, `llvm-native-memory-management` M1/M2).
 */
#include "deal/native/container.h"

#include <stddef.h>
#include <string.h>

#include "deal/native/memory.h"
#include "deal/native/platform.h"
#include "deal/native/text.h"

/* =========================================================================
 * Discrete positions (R2)
 * ========================================================================= */

/* The largest position a binary64 value can name exactly. */
#define DEAL_CONTAINER_MAX_EXACT_POSITION 9007199254740991.0

int deal_container_position(double position, size_t length, int allow_append,
                            size_t *offset) {
    if (offset == NULL) {
        deal_platform_fault("container: null position out-parameter");
    }
    /* Finiteness first: NaN and both infinities are never positions. */
    if (!(position >= -DEAL_CONTAINER_MAX_EXACT_POSITION
            && position <= DEAL_CONTAINER_MAX_EXACT_POSITION)) {
        return 0;
    }
    /* Negative zero satisfies both comparisons and converts to offset 0. */
    if (position < 0.0) {
        return 0;
    }
    /* Integrality without a libm call: the exact conversion must round-trip. */
    uint64_t whole = (uint64_t)position;
    if ((double)whole != position) {
        return 0;
    }
    size_t limit = length;
    if (!allow_append) {
        if (length == 0u) {
            return 0;
        }
        limit = length - 1u;
    }
    if (whole > (uint64_t)limit) {
        return 0;
    }
    *offset = (size_t)whole;
    return 1;
}

/* =========================================================================
 * Arrays (R2, C3)
 * ========================================================================= */

/*
 * One typed array: the recorded element layout, the exact element count, the
 * capacity of the element storage and the managed element block. The block is a
 * managed-reference slot in the layout's precise map; the elements are walked
 * through the recorded element layout instead of a fixed map.
 */
struct deal_array {
    uint32_t element_layout;
    uint32_t reserved;
    size_t length;
    size_t capacity;
    void *block;
};

static const uint32_t deal_array_reference_offsets[] = {
    (uint32_t)offsetof(struct deal_array, block)
};

static const deal_layout deal_array_layout = {
    DEAL_LAYOUT_ARRAY, 0u, sizeof(struct deal_array),
    deal_array_reference_offsets, 1u
};

/* One array's element-storage object: raw bytes, no managed slots of its own. */
static const deal_layout deal_array_block_layout = {
    DEAL_LAYOUT_ARRAY_BLOCK, 0u, 0u, NULL, 0u
};

/* The buffer is zero-filled for a fresh capacity; a reused one is untouched. */
#define DEAL_CONTAINER_ARRAY_MIN_CAPACITY 4u

/*
 * 1 when one reference slot holds a collector-managed referent (M1, M2, C3).
 * A reference slot legitimately holds `deal_null_singleton` or
 * `deal_undefined_singleton` (a nullable element type), and neither singleton
 * is heap storage: the walk never visits one and the barrier never shades one.
 * This is the same managed-referent predicate `memory.c` applies to a precise
 * map and to every root source.
 */
static int deal_container_reference_is_managed(const void *referent) {
    return referent != NULL && !deal_value_is_null(referent)
        && !deal_value_is_undefined(referent);
}

/* The element stride of one element layout (an emitted layout's payload size). */
static size_t deal_container_element_stride(uint32_t element_layout) {
    const deal_layout *record = deal_layout_lookup(element_layout);
    if (record == NULL) {
        deal_platform_fault("container: unknown array element layout");
    }
    if (record->size == 0u) {
        deal_platform_fault("container: array element layout has no size");
    }
    return record->size;
}

/* One array payload; a non-array pointer is an invariant fault. */
static struct deal_array *deal_container_array_of(const deal_array *array) {
    if (array == NULL
            || deal_object_layout(array) != DEAL_LAYOUT_ARRAY) {
        deal_platform_fault("container: not an array");
    }
    return (struct deal_array *)(void *)array;
}

deal_array *deal_container_array_new(uint32_t element_layout, size_t length) {
    size_t stride = deal_container_element_stride(element_layout);
    if (length > SIZE_MAX / stride) {
        deal_platform_fault("container: array size overflow");
    }
    deal_layout_define(&deal_array_layout);
    deal_layout_define(&deal_array_block_layout);
    struct deal_array *array =
        deal_alloc(DEAL_LAYOUT_ARRAY, sizeof(*array));
    array->element_layout = element_layout;
    array->reserved = 0u;
    array->length = length;
    array->capacity = length;
    array->block = NULL;
    if (length != 0u) {
        /* The array is this helper's own intermediate across the element-block
         * allocation, so one scoped root keeps it alive at that allocation
         * safepoint (M5). Faults exit the process, so the scope cannot leak. */
        deal_root_scope_push();
        uint32_t slot = deal_root_scope_hold(array);
        array->block = deal_alloc(DEAL_LAYOUT_ARRAY_BLOCK, length * stride);
        deal_store_barrier(array, array->block);
        deal_root_scope_release(slot);
        deal_root_scope_pop();
    }
    return array;
}

size_t deal_container_array_count(const deal_array *array) {
    return deal_container_array_of(array)->length;
}

uint32_t deal_container_array_element_layout(const deal_array *array) {
    return deal_container_array_of(array)->element_layout;
}

void *deal_container_array_element(const deal_array *array, size_t index) {
    struct deal_array *container = deal_container_array_of(array);
    if (index >= container->length) {
        deal_platform_fault("container: element outside the element count");
    }
    size_t stride = deal_container_element_stride(container->element_layout);
    return (unsigned char *)container->block + index * stride;
}

/* Runs the insertion barrier for every managed reference of one element. */
static void deal_container_array_barrier(struct deal_array *array,
                                         const void *element) {
    const deal_layout *record =
        deal_layout_lookup(array->element_layout);
    if (record == NULL) {
        deal_platform_fault("container: unknown array element layout");
    }
    for (size_t index = 0; index < record->reference_count; index += 1u) {
        void *referent = NULL;
        memcpy(&referent,
               (const unsigned char *)element + record->reference_offsets[index],
               sizeof(referent));
        if (deal_container_reference_is_managed(referent)) {
            deal_store_barrier(array, referent);
        }
    }
}

/* Copies one element into an already grown slot. */
static void deal_container_array_write(struct deal_array *array, size_t index,
                                       const void *element) {
    size_t stride = deal_container_element_stride(array->element_layout);
    deal_container_array_barrier(array, element);
    memcpy((unsigned char *)array->block + index * stride, element, stride);
}

void deal_container_array_store(deal_array *array, size_t index,
                                const void *element) {
    struct deal_array *container = deal_container_array_of(array);
    if (element == NULL) {
        deal_platform_fault("container: null element");
    }
    if (index >= container->length) {
        deal_platform_fault("container: store outside the element count");
    }
    deal_container_array_write(container, index, element);
}

void deal_container_array_append(deal_array *array, const void *element) {
    struct deal_array *container = deal_container_array_of(array);
    if (element == NULL) {
        deal_platform_fault("container: null element");
    }
    size_t stride = deal_container_element_stride(container->element_layout);
    if (container->length == container->capacity) {
        size_t capacity = container->capacity == 0u
            ? DEAL_CONTAINER_ARRAY_MIN_CAPACITY : container->capacity * 2u;
        if (capacity > SIZE_MAX / stride) {
            deal_platform_fault("container: array size overflow");
        }
        void *block = deal_alloc(DEAL_LAYOUT_ARRAY_BLOCK, capacity * stride);
        if (container->length != 0u) {
            memcpy(block, container->block, container->length * stride);
        }
        /* The new block replaces the old one; the old block keeps every byte
         * it held and is reclaimed when nothing references it (M1). */
        deal_store_barrier(container, block);
        container->block = block;
        container->capacity = capacity;
    }
    deal_container_array_write(container, container->length, element);
    container->length += 1u;
}

void deal_container_array_trace(const deal_array *array,
                                deal_reference_visit visit, void *context) {
    if (visit == NULL) {
        deal_platform_fault("container: null reference visitor");
    }
    const struct deal_array *container = deal_container_array_of(array);
    const deal_layout *record = deal_layout_lookup(container->element_layout);
    if (record == NULL) {
        deal_platform_fault("container: unknown array element layout");
    }
    size_t stride = record->size;
    for (size_t index = 0; index < container->length; index += 1u) {
        const unsigned char *element =
            (const unsigned char *)container->block + index * stride;
        for (size_t reference = 0; reference < record->reference_count;
                reference += 1u) {
            void *referent = NULL;
            memcpy(&referent, element + record->reference_offsets[reference],
                   sizeof(referent));
            if (deal_container_reference_is_managed(referent)) {
                visit(referent, context);
            }
        }
    }
}

/* =========================================================================
 * Records (R2, C3)
 * ========================================================================= */

/*
 * One record member: the key, the boxed persistent data value and the two
 * insertion-order links. Every member is its own managed object, so the
 * layout's fixed reference map covers the key, the datum's managed payload and
 * both links, and the whole record graph is traced precisely (M1).
 */
typedef struct deal_table_entry {
    deal_string *key;
    deal_data value;
    struct deal_table_entry *next;
    struct deal_table_entry *previous;
} deal_table_entry;

/* One insertion-ordered record: the present count and both ends of the order. */
struct deal_table {
    size_t count;
    deal_table_entry *head;
    deal_table_entry *tail;
};

static const uint32_t deal_table_reference_offsets[] = {
    (uint32_t)offsetof(struct deal_table, head),
    (uint32_t)offsetof(struct deal_table, tail)
};

static const uint32_t deal_table_entry_reference_offsets[] = {
    (uint32_t)offsetof(deal_table_entry, key),
    (uint32_t)offsetof(deal_table_entry, value)
        + (uint32_t)offsetof(deal_data, reference),
    (uint32_t)offsetof(deal_table_entry, next),
    (uint32_t)offsetof(deal_table_entry, previous)
};

static const deal_layout deal_table_layout = {
    DEAL_LAYOUT_TABLE, 0u, sizeof(struct deal_table),
    deal_table_reference_offsets, 2u
};

static const deal_layout deal_table_entry_layout = {
    DEAL_LAYOUT_TABLE_ENTRY, 0u, sizeof(deal_table_entry),
    deal_table_entry_reference_offsets, 4u
};

/* One record payload; a non-record pointer is an invariant fault. */
static struct deal_table *deal_container_table_of(const deal_table *table) {
    if (table == NULL || deal_object_layout(table) != DEAL_LAYOUT_TABLE) {
        deal_platform_fault("container: not a record");
    }
    return (struct deal_table *)(void *)table;
}

/* One keyed member, or NULL. Key equality is the scalar-sequence comparison. */
static deal_table_entry *deal_container_table_find(const struct deal_table *table,
                                                   deal_string *key) {
    for (deal_table_entry *entry = table->head; entry != NULL;
            entry = entry->next) {
        if (deal_text_compare(entry->key, key) == 0) {
            return entry;
        }
    }
    return NULL;
}

deal_table *deal_container_table_new(void) {
    deal_layout_define(&deal_table_layout);
    deal_layout_define(&deal_table_entry_layout);
    struct deal_table *table =
        deal_alloc(DEAL_LAYOUT_TABLE, sizeof(*table));
    table->count = 0u;
    table->head = NULL;
    table->tail = NULL;
    return table;
}

size_t deal_container_table_count(const deal_table *table) {
    return deal_container_table_of(table)->count;
}

deal_string *deal_container_table_key_at(const deal_table *table,
                                         size_t index) {
    const struct deal_table *record = deal_container_table_of(table);
    if (index >= record->count) {
        return NULL;
    }
    deal_table_entry *entry = record->head;
    for (size_t position = 0; position < index; position += 1u) {
        entry = entry->next;
    }
    return entry->key;
}

deal_data deal_container_table_read(const deal_table *table, deal_string *key) {
    const struct deal_table *record = deal_container_table_of(table);
    if (key == NULL) {
        deal_platform_fault("container: null record key");
    }
    const deal_table_entry *entry = deal_container_table_find(record, key);
    if (entry == NULL) {
        return deal_data_of_undefined();
    }
    return entry->value;
}

int deal_container_table_has(const deal_table *table, deal_string *key) {
    const struct deal_table *record = deal_container_table_of(table);
    if (key == NULL) {
        deal_platform_fault("container: null record key");
    }
    return deal_container_table_find(record, key) != NULL ? 1 : 0;
}

void deal_container_table_write(deal_table *table, deal_string *key,
                                const deal_data *value) {
    struct deal_table *record = deal_container_table_of(table);
    if (key == NULL) {
        deal_platform_fault("container: null record key");
    }
    if (value == NULL) {
        deal_platform_fault("container: null record value");
    }
    deal_table_entry *entry = deal_container_table_find(record, key);
    if (entry != NULL) {
        /* A repeated write keeps the first-insertion position (R2); the barrier
         * runs before the stored value is published (M2). */
        deal_store_barrier_data(entry, value);
        entry->value = *value;
        return;
    }
    entry = deal_alloc(DEAL_LAYOUT_TABLE_ENTRY, sizeof(*entry));
    entry->key = key;
    deal_store_barrier(entry, key);
    entry->value = *value;
    deal_store_barrier_data(entry, &entry->value);
    entry->previous = record->tail;
    entry->next = NULL;
    if (record->tail == NULL) {
        record->head = entry;
    } else {
        record->tail->next = entry;
        deal_store_barrier(record->tail, entry);
    }
    record->tail = entry;
    deal_store_barrier(record, entry);
    record->count += 1u;
}

void deal_container_table_delete(deal_table *table, deal_string *key) {
    struct deal_table *record = deal_container_table_of(table);
    if (key == NULL) {
        deal_platform_fault("container: null record key");
    }
    deal_table_entry *entry = deal_container_table_find(record, key);
    if (entry == NULL) {
        return;
    }
    if (entry->previous == NULL) {
        record->head = entry->next;
    } else {
        entry->previous->next = entry->next;
    }
    if (entry->next == NULL) {
        record->tail = entry->previous;
    } else {
        entry->next->previous = entry->previous;
    }
    entry->next = NULL;
    entry->previous = NULL;
    entry->key = NULL;
    record->count -= 1u;
}
