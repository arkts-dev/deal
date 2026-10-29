/*
 * deal/native/value.c — the `value` service group implementation.
 *
 * The immutable null and undefined singletons, the persistent tagged datum of
 * an `unknown` position, the scalar-nullish tag projection and the crossing
 * value table of `value.h` (`llvm-native-runtime-value-model` R2/R3/R11,
 * `llvm-native-memory-management` M5). The data `===` relation needs the one
 * Unicode scalar-sequence comparison, so it lives in the `text` group, which
 * depends on this group and never the other way round.
 */
#include "deal/native/value.h"

#include <string.h>

#include "deal/native/platform.h"

/* Two addresses that are unique in the process and never heap-allocated. */
static const unsigned char deal_null_singleton_storage = 0;
static const unsigned char deal_undefined_singleton_storage = 0;

void *const deal_null_singleton = (void *)&deal_null_singleton_storage;
void *const deal_undefined_singleton = (void *)&deal_undefined_singleton_storage;

int deal_value_is_null(const void *reference) {
    return reference == (const void *)deal_null_singleton;
}

int deal_value_is_undefined(const void *reference) {
    return reference == (const void *)deal_undefined_singleton;
}

/* =========================================================================
 * The persistent tagged datum (C3)
 * ========================================================================= */

/* 1 when `tag` is one of the two nullish data tags. */
static int deal_data_tag_is_nullish(deal_data_tag tag) {
    return tag == DEAL_DATA_UNDEFINED || tag == DEAL_DATA_NULL;
}

/*
 * 1 when `tag` names a collector-managed object: every reference tag does, and
 * the scalar and nullish tags name no object at all.
 */
static int deal_data_tag_is_managed(deal_data_tag tag) {
    return tag == DEAL_DATA_STRING || tag == DEAL_DATA_ARRAY
        || tag == DEAL_DATA_TABLE || tag == DEAL_DATA_CLASS
        || tag == DEAL_DATA_FUNCTION || tag == DEAL_DATA_ERROR;
}

/* Rejects a tag outside the closed persistent data set. */
static void deal_data_require_tag(deal_data_tag tag) {
    if ((int)tag < (int)DEAL_DATA_UNDEFINED || (int)tag > (int)DEAL_DATA_ERROR) {
        deal_platform_fault("data: tag outside the closed set");
    }
}

static deal_data deal_data_make(deal_data_tag tag, double number,
                                void *reference) {
    deal_data value;
    value.tag = (uint32_t)tag;
    value.reserved = 0u;
    value.number = number;
    value.reference = reference;
    return value;
}

/* The datum behind one published pointer; a null pointer is a defect. */
static const deal_data *deal_data_of(const deal_data *value) {
    if (value == NULL) {
        deal_platform_fault("data: null datum");
    }
    deal_data_require_tag((deal_data_tag)value->tag);
    return value;
}

deal_data deal_data_of_undefined(void) {
    return deal_data_make(DEAL_DATA_UNDEFINED, 0.0, NULL);
}

deal_data deal_data_of_null(void) {
    return deal_data_make(DEAL_DATA_NULL, 0.0, NULL);
}

deal_data deal_data_of_boolean(int truth) {
    return deal_data_make(DEAL_DATA_BOOLEAN, truth != 0 ? 1.0 : 0.0, NULL);
}

deal_data deal_data_of_number(double value) {
    return deal_data_make(DEAL_DATA_NUMBER, value, NULL);
}

deal_data deal_data_of_reference(deal_data_tag tag, void *referent) {
    deal_data_require_tag(tag);
    if (!deal_data_tag_is_managed(tag)) {
        deal_platform_fault("data: reference datum with a nullish or scalar tag");
    }
    if (referent == NULL) {
        deal_platform_fault("data: reference datum without a referent");
    }
    return deal_data_make(tag, 0.0, referent);
}

int deal_data_is_nullish(const deal_data *value) {
    return deal_data_tag_is_nullish((deal_data_tag)deal_data_of(value)->tag);
}

int deal_data_is_managed(const deal_data *value) {
    return deal_data_tag_is_managed((deal_data_tag)deal_data_of(value)->tag);
}

void *deal_data_referent(const deal_data *value) {
    const deal_data *datum = deal_data_of(value);
    if (!deal_data_tag_is_managed((deal_data_tag)datum->tag)) {
        return NULL;
    }
    return datum->reference;
}

deal_nullish_tag deal_nullish_of_data(const deal_data *value) {
    switch ((deal_data_tag)deal_data_of(value)->tag) {
    case DEAL_DATA_NULL:
        return DEAL_NULLISH_NULL;
    case DEAL_DATA_UNDEFINED:
        return DEAL_NULLISH_UNDEFINED;
    default:
        return DEAL_NULLISH_BASE;
    }
}

/* =========================================================================
 * The crossing value table (R3, R11, M5)
 * ========================================================================= */

/* One issued carrier: its slot, its generation and its referent. */
typedef struct {
    uint32_t slot;
    uint32_t generation;
    void *referent;
    uint32_t managed;
    uint32_t reserved;
} deal_value_entry;

struct deal_value_crossing {
    deal_value_entry *entries;
    size_t count;
    size_t capacity;
    void **roots;
    size_t root_count;
    size_t root_capacity;
};

/* The crossing scope stack; the last pushed crossing is the active one. */
static deal_value_crossing **deal_value_crossing_stack;
static size_t deal_value_crossing_depth;
static size_t deal_value_crossing_stack_capacity;

/*
 * The issuance generation (R11, M5): every issued carrier takes the next value,
 * so a carrier of a finished crossing can never coincide with a live slot of a
 * later crossing, even when the new crossing reuses the same slot index.
 */
static uint32_t deal_value_generation_counter;

/* Grows one platform-owned array, twice its size, preserving its entries. */
static void *deal_value_grow(void *storage, size_t *capacity, size_t needed,
                             size_t element_size, const char *reason) {
    if (needed < *capacity) {
        return storage;
    }
    size_t grown_capacity = *capacity == 0 ? 4u : *capacity * 2u;
    while (grown_capacity <= needed) {
        grown_capacity *= 2u;
    }
    void *grown = deal_platform_allocate(grown_capacity * element_size);
    if (grown == NULL) {
        deal_platform_fault(reason);
    }
    if (storage != NULL && needed != 0u) {
        memcpy(grown, storage, needed * element_size);
        deal_platform_release(storage);
    }
    *capacity = grown_capacity;
    return grown;
}

/*
 * One issued carrier's referent is a heap address only for a managed view: a
 * nullish singleton, an absent referent and an opaque crossing-scoped payload
 * are never traced as managed references (M1, M5). The crossing scope is one
 * registered root source, so its scan applies the same tag-aware predicate as
 * every other root source.
 */
static int deal_value_reference_is_managed(const void *referent) {
    return referent != NULL && !deal_value_is_null(referent)
        && !deal_value_is_undefined(referent);
}

/* The scan of every root the active crossing scope owns (M5). */
static void deal_value_crossing_scan(deal_reference_visit visit,
                                     void *visit_context,
                                     void *source_context) {
    deal_value_crossing *crossing = source_context;
    if (crossing == NULL) {
        return;
    }
    for (size_t index = 0; index < crossing->count; index += 1u) {
        if (crossing->entries[index].managed != 0u
                && deal_value_reference_is_managed(
                    crossing->entries[index].referent)) {
            visit(crossing->entries[index].referent, visit_context);
        }
    }
    for (size_t index = 0; index < crossing->root_count; index += 1u) {
        if (deal_value_reference_is_managed(crossing->roots[index])) {
            visit(crossing->roots[index], visit_context);
        }
    }
}

deal_value_crossing *deal_value_crossing_active(void) {
    if (deal_value_crossing_depth == 0u) {
        return NULL;
    }
    return deal_value_crossing_stack[deal_value_crossing_depth - 1u];
}

deal_value_crossing *deal_value_crossing_begin(void) {
    deal_value_crossing_stack = deal_value_grow(deal_value_crossing_stack,
        &deal_value_crossing_stack_capacity, deal_value_crossing_depth,
        sizeof(*deal_value_crossing_stack),
        "crossing value table: scope stack allocation failed");
    deal_value_crossing *crossing = deal_platform_allocate(sizeof(*crossing));
    if (crossing == NULL) {
        deal_platform_fault("crossing value table: scope allocation failed");
    }
    crossing->entries = NULL;
    crossing->count = 0u;
    crossing->capacity = 0u;
    crossing->roots = NULL;
    crossing->root_count = 0u;
    crossing->root_capacity = 0u;
    deal_value_crossing_stack[deal_value_crossing_depth] = crossing;
    deal_value_crossing_depth += 1u;
    deal_root_source_register(deal_value_crossing_scan, crossing);
    return crossing;
}

void deal_value_crossing_end(deal_value_crossing *crossing) {
    if (crossing == NULL || crossing != deal_value_crossing_active()) {
        deal_platform_fault("crossing value table: end of a non-active crossing");
    }
    deal_root_source_unregister(deal_value_crossing_scan, crossing);
    deal_platform_release(crossing->entries);
    deal_platform_release(crossing->roots);
    deal_platform_release(crossing);
    deal_value_crossing_depth -= 1u;
    deal_value_crossing_stack[deal_value_crossing_depth] = NULL;
}

size_t deal_value_crossing_count(void) {
    deal_value_crossing *crossing = deal_value_crossing_active();
    return crossing == NULL ? 0u : crossing->count;
}

/* Appends one entry to the active crossing; NULL on a non-active crossing. */
static deal_value_entry *deal_value_crossing_append(deal_value_crossing *crossing,
                                                    void *referent,
                                                    int managed) {
    if (crossing == NULL || crossing != deal_value_crossing_active()) {
        return NULL;
    }
    crossing->entries = deal_value_grow(crossing->entries, &crossing->capacity,
        crossing->count, sizeof(*crossing->entries),
        "crossing value table: entry table allocation failed");
    deal_value_entry *entry = &crossing->entries[crossing->count];
    entry->slot = (uint32_t)crossing->count;
    deal_value_generation_counter += 1u;
    if (deal_value_generation_counter == 0u) {
        deal_value_generation_counter = 1u;
    }
    entry->generation = deal_value_generation_counter;
    entry->referent = referent;
    entry->managed = managed != 0 ? 1u : 0u;
    entry->reserved = 0u;
    crossing->count += 1u;
    return entry;
}

/* Publishes one appended entry as a carrier; the caller wrote the entry. */
static deal_status deal_value_crossing_publish(deal_value_entry *entry,
                                               deal_value *out) {
    if (entry == NULL) {
        return DEAL_STATUS_OUT_OF_SCOPE;
    }
    if (out != NULL) {
        out->slot = entry->slot;
        out->generation = entry->generation;
    }
    return DEAL_OK;
}

deal_status deal_value_crossing_issue(deal_value_crossing *crossing,
                                      void *referent, deal_value *out) {
    if (crossing == NULL || crossing != deal_value_crossing_active()) {
        return DEAL_STATUS_OUT_OF_SCOPE;
    }
    if (referent == NULL) {
        return DEAL_STATUS_KIND;
    }
    return deal_value_crossing_publish(
        deal_value_crossing_append(crossing, referent, 1), out);
}

deal_status deal_value_crossing_issue_opaque(deal_value_crossing *crossing,
                                             void *payload, deal_value *out) {
    if (crossing == NULL || crossing != deal_value_crossing_active()) {
        return DEAL_STATUS_OUT_OF_SCOPE;
    }
    return deal_value_crossing_publish(
        deal_value_crossing_append(crossing, payload, 0), out);
}

deal_status deal_value_crossing_root(deal_value_crossing *crossing,
                                     void *referent) {
    if (crossing == NULL || crossing != deal_value_crossing_active()) {
        return DEAL_STATUS_OUT_OF_SCOPE;
    }
    if (referent == NULL) {
        return DEAL_STATUS_KIND;
    }
    crossing->roots = deal_value_grow(crossing->roots, &crossing->root_capacity,
        crossing->root_count, sizeof(*crossing->roots),
        "crossing value table: root table allocation failed");
    crossing->roots[crossing->root_count] = referent;
    crossing->root_count += 1u;
    return DEAL_OK;
}

deal_status deal_value_crossing_resolve(deal_value_crossing *crossing,
                                        deal_value value, void **out) {
    if (out == NULL) {
        deal_platform_fault("crossing value table: null out-parameter");
    }
    if (crossing == NULL || crossing != deal_value_crossing_active()) {
        return DEAL_STATUS_FOREIGN_VALUE;
    }
    if ((size_t)value.slot >= crossing->count) {
        return DEAL_STATUS_FOREIGN_VALUE;
    }
    deal_value_entry *entry = &crossing->entries[value.slot];
    if (entry->generation != value.generation) {
        return DEAL_STATUS_FOREIGN_VALUE;
    }
    *out = entry->referent;
    return DEAL_OK;
}

/* =========================================================================
 * The function value (C3, C5)
 * ========================================================================= */

/*
 * One captured-binding cell array (C5): the cells of one function value's
 * generation-pinned capture list, in capture order. It is a variable-payload
 * managed object of its own layout, so the collector walks exactly the cells
 * the creating `CLOSURE_NEW` captured and nothing else.
 */
typedef struct {
    uint32_t count;
    uint32_t reserved;
    void *cells[1];
} deal_fn_environment;

/*
 * One function value (C3/C5): the boxed apply thunk, the interned canonical
 * signature atom, the captured-cell array (NULL when the function captures
 * nothing), the async marker and the capture count. Its only managed
 * reference is the environment; the signature atom is a process-lifetime
 * static root of the `descriptor` group.
 */
typedef struct deal_fn {
    deal_fn_thunk thunk;
    deal_descriptor *signature;
    deal_fn_environment *environment;
    uint32_t capture_count;
    uint32_t is_async;
} deal_fn;

static const uint32_t deal_fn_reference_offsets[] = {
    (uint32_t)offsetof(deal_fn, environment)
};

static const deal_layout deal_fn_layout = {
    DEAL_LAYOUT_FUNCTION, 0u, sizeof(deal_fn),
    deal_fn_reference_offsets, 1u
};

static const deal_layout deal_fn_environment_layout = {
    DEAL_LAYOUT_FUNCTION_ENV, 0u, 0u, NULL, 0u
};

static size_t deal_fn_environment_trace(const void *object, size_t position,
                                        size_t *budget,
                                        deal_reference_visit visit,
                                        void *context) {
    if (object == NULL || budget == NULL || visit == NULL) {
        deal_platform_fault("function value: malformed environment trace");
    }
    const deal_fn_environment *environment = object;
    size_t index = position;
    while (index < (size_t)environment->count) {
        if (*budget == 0u) {
            return index;
        }
        *budget -= 1u;
        void *cell = environment->cells[index];
        if (cell != NULL) {
            visit(cell, context);
        }
        index += 1u;
    }
    return DEAL_TRACE_DONE;
}

/* The payload of one function value; a non-function pointer is a fault. */
static deal_fn *deal_fn_of(const deal_fn *function) {
    if (function == NULL
            || deal_object_layout(function) != DEAL_LAYOUT_FUNCTION) {
        deal_platform_fault("function value: not a function value");
    }
    return (deal_fn *)(void *)function;
}

deal_fn *deal_fn_create(deal_fn_thunk thunk, const void *const *cells,
                        uint32_t capture_count, const char *signature,
                        int is_async) {
    if (thunk == NULL) {
        deal_platform_fault("function value: null apply thunk");
    }
    if (signature == NULL) {
        deal_platform_fault("function value: null signature");
    }
    if (capture_count != 0u && cells == NULL) {
        deal_platform_fault("function value: null capture cell array");
    }
    deal_layout_define(&deal_fn_layout);
    deal_layout_define(&deal_fn_environment_layout);
    deal_layout_trace_register(DEAL_LAYOUT_FUNCTION_ENV,
                               deal_fn_environment_trace);
    /* The carrier and its environment are this helper's own intermediates
     * across the second allocation, so one scoped root keeps each alive at
     * that allocation safepoint (M5). Faults exit the process, so the scope
     * cannot leak. */
    deal_root_scope_push();
    uint32_t held_environment = 0u;
    deal_fn_environment *environment = NULL;
    if (capture_count != 0u) {
        size_t bytes = offsetof(deal_fn_environment, cells)
            + (size_t)capture_count * sizeof(void *);
        environment = deal_alloc(DEAL_LAYOUT_FUNCTION_ENV, bytes);
        environment->count = capture_count;
        environment->reserved = 0u;
        for (uint32_t index = 0; index < capture_count; index += 1u) {
            environment->cells[index] = (void *)cells[index];
        }
        held_environment = deal_root_scope_hold(environment);
    }
    deal_fn *function = deal_alloc(DEAL_LAYOUT_FUNCTION, sizeof(*function));
    function->thunk = thunk;
    function->signature = deal_descriptor_intern(signature);
    if (environment != NULL) {
        deal_store_barrier(function, environment);
    }
    function->environment = environment;
    function->capture_count = capture_count;
    function->is_async = is_async != 0 ? 1u : 0u;
    if (environment != NULL) {
        deal_root_scope_release(held_environment);
    }
    deal_root_scope_pop();
    return function;
}

deal_fn_thunk deal_fn_thunk_of(const deal_fn *function) {
    return deal_fn_of(function)->thunk;
}

void *deal_fn_capture(const deal_fn *function, uint32_t index) {
    deal_fn *payload = deal_fn_of(function);
    if (index >= payload->capture_count || payload->environment == NULL) {
        deal_platform_fault("function value: capture index outside the list");
    }
    return payload->environment->cells[index];
}

uint32_t deal_fn_capture_count(const deal_fn *function) {
    return deal_fn_of(function)->capture_count;
}

const char *deal_fn_signature(const deal_fn *function) {
    return deal_descriptor_text(deal_fn_of(function)->signature);
}

int deal_fn_is_async(const deal_fn *function) {
    return deal_fn_of(function)->is_async != 0u ? 1 : 0;
}

deal_outcome_data deal_fn_apply(deal_fn *function,
                                const deal_check_input *arguments,
                                uint32_t argument_count,
                                const deal_origin *origin) {
    deal_outcome_data outcome;
    outcome.error = NULL;
    outcome.value = deal_data_of_undefined();
    if (origin == NULL) {
        deal_platform_fault("function value: null apply origin");
    }
    deal_fn *payload = deal_fn_of(function);
    if (payload->thunk == NULL) {
        deal_platform_fault("function value: missing apply thunk");
    }
    return payload->thunk(payload, arguments, argument_count, origin);
}
