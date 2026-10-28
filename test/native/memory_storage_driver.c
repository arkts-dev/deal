/*
 * test/native/memory_storage_driver.c — the M1/M4/M5/M6 storage acceptance.
 *
 * A strict-C11 consumer of the published runtime: the emitted per-layout
 * metadata install and lookup, the allocation entry with its zero-initialized
 * payload and header facts, the precise reference-map walk (including its
 * tag-aware treatment of the nullish singletons), the Error record's layout
 * with the cause as its one managed-reference slot, the M4 root frames, the M5
 * scoped roots, registered root sources and static root table, and the M6
 * captured-binding cells with their generations. Every case restores the root
 * depth before it returns.
 *
 * `-DDEAL_MEMORY_CASE=<n>` selects one deterministic invariant-fault case for
 * the negative run: each must terminate with one deterministic stderr line and
 * exit 2 instead of returning to the caller. The collector's own behavior, the
 * barriers and the stress switch are the acceptance driver `gc_driver.c`'s.
 */
#include "deal/native/deal_rt.h"
#include "deal/native/entry.h"
#include "deal/native/error.h"
#include "deal/native/memory.h"
#include "deal/native/platform.h"
#include "deal/native/value.h"

#include <stdio.h>
#include <string.h>

#ifndef DEAL_MEMORY_CASE
#define DEAL_MEMORY_CASE 0
#endif

#define TEST_LAYOUT_NUMBER 1024u
#define TEST_LAYOUT_PAIR   1025u
#define TEST_LAYOUT_SECOND 1026u

#if DEAL_MEMORY_CASE == 0 || DEAL_MEMORY_CASE == 1
static const uint32_t test_pair_offsets[] = { 0u, 8u };

static const deal_layout test_layouts[] = {
    { TEST_LAYOUT_NUMBER, 0u, 8u, NULL, 0u },
    { TEST_LAYOUT_PAIR, 0u, 16u, test_pair_offsets, 2u }
};
#endif

#if DEAL_MEMORY_CASE == 0

#define COLLECT_LIMIT 16u

static int failures;
static void *collected[COLLECT_LIMIT];
static size_t collected_count;

static void collect_reference(void *referent, void *context) {
    (void)context;
    if (collected_count < COLLECT_LIMIT) {
        collected[collected_count] = referent;
    }
    collected_count += 1u;
}

static void check(int condition, const char *what) {
    if (!condition) {
        printf("FAIL: %s\n", what);
        failures += 1;
    }
}

static void reset_collected(void) {
    collected_count = 0u;
}

static int all_zero(const void *bytes, size_t length) {
    const unsigned char *cursor = bytes;
    for (size_t index = 0; index < length; index += 1u) {
        if (cursor[index] != 0u) {
            return 0;
        }
    }
    return 1;
}

/* One root source the driver registers like the host groups do. */
static void *driver_source_referent;
static size_t driver_source_scans;

static void driver_root_source(deal_reference_visit visit,
                               void *visit_context, void *source_context) {
    driver_source_scans += 1u;
    (void)source_context;
    if (driver_source_referent != NULL) {
        visit(driver_source_referent, visit_context);
    }
}

static void check_layouts(void) {
    deal_layout_install(test_layouts,
                        sizeof(test_layouts) / sizeof(test_layouts[0]));
    const deal_layout *number = deal_layout_lookup(TEST_LAYOUT_NUMBER);
    const deal_layout *pair = deal_layout_lookup(TEST_LAYOUT_PAIR);
    check(number != NULL && number->id == TEST_LAYOUT_NUMBER,
          "the installed numeric layout is found by identity");
    check(number != NULL && number->size == 8u
              && number->reference_count == 0u
              && number->reference_offsets == NULL,
          "the numeric layout carries its size and an empty reference map");
    check(pair != NULL && pair->size == 16u && pair->reference_count == 2u,
          "the two-reference layout carries its size and map");
    check(pair != NULL && pair->reference_offsets[0] == 0u
              && pair->reference_offsets[1] == 8u,
          "the two-reference map keeps the emitted offsets in order");
    check(pair != NULL && pair->flags == 0u,
          "the reserved layout flags stay zero");
    check(deal_layout_lookup(TEST_LAYOUT_SECOND) == NULL,
          "an uninstalled layout identity is not found");
}

static void check_allocation(void) {
    void *number = deal_alloc(TEST_LAYOUT_NUMBER, 8u);
    void *pair = deal_alloc(TEST_LAYOUT_PAIR, 16u);
    check(deal_object_layout(number) == TEST_LAYOUT_NUMBER,
          "the header carries the allocated layout identity");
    check(deal_object_size(number) == 8u,
          "the header carries the exact payload size");
    check(deal_object_layout(pair) == TEST_LAYOUT_PAIR
              && deal_object_size(pair) == 16u,
          "a second layout keeps its own identity and size");
    check(all_zero(number, 8u) && all_zero(pair, 16u),
          "a fresh allocation is zero-initialized");
    /* an over-sized allocation of a fixed layout stays valid (variable tail) */
    void *grown = deal_alloc(TEST_LAYOUT_NUMBER, 32u);
    check(deal_object_layout(grown) == TEST_LAYOUT_NUMBER
              && deal_object_size(grown) == 32u,
          "an allocation may extend a fixed layout's payload");
    check(all_zero(grown, 32u), "the extended payload is zero-initialized");
}

static void check_reference_map(void) {
    void *pair = deal_alloc(TEST_LAYOUT_PAIR, 16u);
    void *first = deal_alloc(TEST_LAYOUT_NUMBER, 8u);
    void *second = deal_alloc(TEST_LAYOUT_NUMBER, 8u);
    void *undefined_singleton = deal_undefined_singleton;
    void *null_singleton = deal_null_singleton;

    *(void **)pair = first;
    *(void **)((unsigned char *)pair + 8) = second;
    reset_collected();
    deal_object_trace(pair, collect_reference, NULL);
    check(collected_count == 2u, "the map walk visits both reference slots");
    check(collected[0] == first && collected[1] == second,
          "the map walk visits ascending offset order");

    /* a singleton is data, never a heap address: the scan skips it. */
    *(void **)pair = undefined_singleton;
    reset_collected();
    deal_object_trace(pair, collect_reference, NULL);
    check(collected_count == 1u && collected[0] == second,
          "the map walk skips the undefined singleton");

    *(void **)((unsigned char *)pair + 8) = null_singleton;
    reset_collected();
    deal_object_trace(pair, collect_reference, NULL);
    check(collected_count == 0u, "the map walk skips both nullish singletons");

    /* a cell's value slot is one precise reference slot of its own layout. */
    void *cell = deal_cell_alloc_generation(first, 3u);
    reset_collected();
    deal_object_trace(cell, collect_reference, NULL);
    check(collected_count == 1u && collected[0] == first,
          "the cell's value slot is one precise reference slot");
}

/* The Error record is one managed object with a precise reference map (M1).
 * Its single managed slot is the cause, so a cause reachable only through its
 * parent Error is traced, and an absent cause contributes no referent. */
static void check_error_reference_map(void) {
    deal_error *cause = deal_error_create("E9002", "cause", NULL);
    deal_error *outer = deal_error_create("E8010", "outer", NULL);
    const deal_layout *record = deal_layout_lookup(DEAL_LAYOUT_ERROR);
    const char *code = NULL;

    check(record != NULL, "the Error layout is defined by its first use");
    check(record != NULL && record->size == deal_object_size(outer),
          "the Error layout carries the exact payload size");
    check(record != NULL && record->reference_count == 1u,
          "the Error layout declares exactly its one managed slot");
    check(record != NULL && record->reference_offsets != NULL
              && record->reference_offsets[0] % (uint32_t)sizeof(void *) == 0u
              && (size_t)record->reference_offsets[0] + sizeof(void *)
                  <= record->size,
          "the Error layout's one reference slot lies inside its payload");
    check(deal_object_layout(outer) == DEAL_LAYOUT_ERROR,
          "an Error is a managed object of the Error layout");

    reset_collected();
    deal_object_trace(outer, collect_reference, NULL);
    check(collected_count == 0u, "an Error without a cause traces no referent");

    deal_error_set_cause(outer, cause);
    reset_collected();
    deal_object_trace(outer, collect_reference, NULL);
    check(collected_count == 1u && collected[0] == cause,
          "the Error's precise map traces its cause referent");
    check(deal_error_cause_code(outer, &code) == 1
              && strcmp(code, "E9002") == 0,
          "the traced cause keeps its own code");

    /* a replacing store leaves exactly one referent in the slot (M2). */
    deal_error *replacement = deal_error_create("E9003", "replacement", NULL);
    deal_error_set_cause(outer, replacement);
    reset_collected();
    deal_object_trace(outer, collect_reference, NULL);
    check(collected_count == 1u && collected[0] == replacement,
          "a replaced cause traces as the stored referent");

    /* a nested cause is traced from the cause's own Error. */
    deal_error *deepest = deal_error_create("E9004", "deepest", NULL);
    deal_error_set_cause(cause, deepest);
    reset_collected();
    deal_object_trace(cause, collect_reference, NULL);
    check(collected_count == 1u && collected[0] == deepest,
          "a nested cause is traced from its own Error");

    /* clearing the cause returns the one slot to no referent. */
    deal_error_set_cause(outer, NULL);
    reset_collected();
    deal_object_trace(outer, collect_reference, NULL);
    check(collected_count == 0u, "a cleared cause traces no referent");
    code = "unchanged";
    check(deal_error_cause_code(outer, &code) == 0
              && strcmp(code, "unchanged") == 0,
          "a cleared cause is absent and writes no code");
    check(deal_root_frame_depth() == 0u && deal_root_scope_depth() == 0u,
          "the Error reference-map case leaves the root depth restored");
}

static void check_root_frames(void) {
    void *referent = deal_alloc(TEST_LAYOUT_NUMBER, 8u);
    deal_error *error = deal_error_create("E9001", "framed", NULL);
    deal_data scalar = deal_data_of_number(1.0);
    deal_data reference = deal_data_of_reference(DEAL_DATA_CLASS, referent);
    deal_data error_datum = deal_data_of_reference(DEAL_DATA_ERROR, error);

    check(deal_root_frame_depth() == 0u, "the root depth starts at zero");
    deal_root_frame_enter(2u);
    check(deal_root_frame_depth() == 1u, "one root frame is registered");
    deal_root_frame_write_data(0u, &scalar);
    deal_root_frame_write_data(1u, &reference);
    reset_collected();
    deal_root_sources_enumerate(collect_reference, NULL);
    check(collected_count == 1u && collected[0] == referent,
          "a tag-aware root write roots only the managed payload");
    /* an Error carried through a tagged root slot is a managed referent. */
    deal_root_frame_write_data(0u, &error_datum);
    reset_collected();
    deal_root_sources_enumerate(collect_reference, NULL);
    check(collected_count == 2u,
          "a carried Error is rooted beside the other managed referent");
    check(collected[0] == error && collected[1] == referent,
          "the carried Error is rooted as the value it names");
    deal_root_frame_write(0u, deal_undefined_singleton);
    reset_collected();
    deal_root_sources_enumerate(collect_reference, NULL);
    check(collected_count == 1u,
          "a nullish singleton in a root slot is never visited");
    deal_root_frame_leave();
    check(deal_root_frame_depth() == 0u, "the root depth is restored");
    reset_collected();
    deal_root_sources_enumerate(collect_reference, NULL);
    check(collected_count == 0u, "a left frame stops being a root");
}

static void check_scoped_roots(void) {
    void *first = deal_alloc(TEST_LAYOUT_NUMBER, 8u);
    void *second = deal_alloc(TEST_LAYOUT_NUMBER, 8u);
    void *third = deal_alloc(TEST_LAYOUT_NUMBER, 8u);
    deal_data scalar = deal_data_of_boolean(1);
    deal_data second_datum = deal_data_of_reference(DEAL_DATA_CLASS, second);
    uint32_t first_slot = 0u;

    check(deal_root_scope_depth() == 0u, "the scoped-root depth starts at zero");
    deal_root_scope_push();
    check(deal_root_scope_depth() == 1u, "one scope is active");
    first_slot = deal_root_scope_hold(first);
    (void)deal_root_scope_hold_data(&scalar);
    (void)deal_root_scope_hold_data(&second_datum);
    (void)deal_root_scope_hold(third);
    reset_collected();
    deal_root_sources_enumerate(collect_reference, NULL);
    check(collected_count == 3u,
          "scoped roots visit exactly the managed referents");
    check(collected[0] == first && collected[1] == second && collected[2] == third,
          "scoped roots keep their hold order");
    deal_root_scope_release(first_slot);
    reset_collected();
    deal_root_sources_enumerate(collect_reference, NULL);
    check(collected_count == 2u, "a released slot stops being a root");
    deal_root_scope_push();
    check(deal_root_scope_depth() == 2u, "a nested scope is active");
    deal_root_scope_pop();
    check(deal_root_scope_depth() == 1u, "the nested scope popped");
    reset_collected();
    deal_root_sources_enumerate(collect_reference, NULL);
    check(collected_count == 2u, "the outer scope keeps its roots");
    deal_root_scope_pop();
    check(deal_root_scope_depth() == 0u, "the scoped-root depth is restored");
    reset_collected();
    deal_root_sources_enumerate(collect_reference, NULL);
    check(collected_count == 0u, "a popped scope stops being a root");
}

static void check_static_and_registered_roots(void) {
    void *referent = deal_alloc(TEST_LAYOUT_NUMBER, 8u);
    void *source_referent = deal_alloc(TEST_LAYOUT_NUMBER, 8u);
    void *slot = NULL;

    check(deal_static_root_count() == 0u, "the static root table starts empty");
    deal_static_root_register(&slot);
    check(deal_static_root_count() == 1u, "one static root slot is registered");
    slot = referent;
    reset_collected();
    deal_root_sources_enumerate(collect_reference, NULL);
    check(collected_count == 1u && collected[0] == referent,
          "a written static root slot is a root");
    slot = NULL;
    reset_collected();
    deal_root_sources_enumerate(collect_reference, NULL);
    check(collected_count == 0u, "an empty static root slot is not a root");

    driver_source_referent = source_referent;
    driver_source_scans = 0u;
    deal_root_source_register(driver_root_source, NULL);
    reset_collected();
    deal_root_sources_enumerate(collect_reference, NULL);
    check(driver_source_scans == 1u, "a registered root source is scanned");
    check(collected_count == 1u && collected[0] == source_referent,
          "a registered root source contributes its referent");
    deal_root_source_unregister(driver_root_source, NULL);
    reset_collected();
    deal_root_sources_enumerate(collect_reference, NULL);
    check(collected_count == 0u, "an unregistered source is not scanned");
    deal_static_root_unregister(&slot);
    check(deal_static_root_count() == 0u, "the static root slot is registered once");
}

static void check_cells(void) {
    void *first = deal_alloc(TEST_LAYOUT_NUMBER, 8u);
    void *second = deal_alloc(TEST_LAYOUT_NUMBER, 8u);
    void *cell = deal_cell_alloc_generation(first, 7u);
    void *plain = deal_cell_alloc(first);

    check(deal_object_layout(cell) == DEAL_LAYOUT_CELL,
          "a cell is a managed object of the cell layout");
    check(deal_cell_generation(cell) == 7u,
          "a cell keeps its binding generation");
    check(deal_cell_read(cell) == first, "a cell reads its captured value");
    deal_cell_store(cell, second);
    check(deal_cell_read(cell) == second, "a cell store replaces the value");
    check(deal_cell_generation(cell) == 7u,
          "a cell store keeps the generation");
    check(deal_cell_generation(plain) == 0u,
          "the plain cell allocation is generation zero");
    check(deal_cell_read(plain) == first, "the plain cell holds its value");
    check(cell != plain, "two cells are distinct objects");
    check(deal_cell_read(cell) == second && deal_cell_read(plain) == first,
          "each cell keeps its own value");
    /* per-iteration generations never share one cell. */
    void *first_iteration = deal_cell_alloc_generation(first, 1u);
    void *second_iteration = deal_cell_alloc_generation(first, 2u);
    check(first_iteration != second_iteration,
          "each iteration generation allocates a fresh cell");
    check(deal_cell_generation(first_iteration) == 1u
              && deal_cell_generation(second_iteration) == 2u,
          "the iteration cells keep their own generations");
    deal_cell_store(first_iteration, second);
    check(deal_cell_read(second_iteration) == first,
          "one generation's store is invisible to the other");
}

static void check_safepoint_and_barrier(void) {
    void *object = deal_alloc(TEST_LAYOUT_NUMBER, 8u);
    void *cell = deal_cell_alloc_generation(object, 0u);
    deal_data scalar = deal_data_of_number(2.0);
    deal_data reference = deal_data_of_reference(DEAL_DATA_STRING, object);
    /* The barrier shades only a managed referent while the collector is
     * marking, and the safepoint runs at most one bounded slice: neither entry
     * changes a stored value or an active root here. */
    deal_safepoint();
    deal_store_barrier(cell, object);
    deal_store_barrier_data(cell, &scalar);
    deal_store_barrier_data(cell, &reference);
    check(deal_cell_read(cell) == object,
          "the barrier entries change no stored value");
    check(deal_root_frame_depth() == 0u && deal_root_scope_depth() == 0u,
          "the storage entries leave the root depth restored");
}

int main(void) {
    check_layouts();
    check_allocation();
    check_reference_map();
    check_error_reference_map();
    check_root_frames();
    check_scoped_roots();
    check_static_and_registered_roots();
    check_cells();
    check_safepoint_and_barrier();
    printf(failures == 0 ? "memory storage ok\n" : "memory storage failed\n");
    return failures == 0 ? 0 : 1;
}

#elif DEAL_MEMORY_CASE == 1
int main(void) {
    deal_layout_install(test_layouts,
                        sizeof(test_layouts) / sizeof(test_layouts[0]));
    (void)deal_alloc(TEST_LAYOUT_NUMBER, 4u);
    printf("unreachable\n");
    return 0;
}
#elif DEAL_MEMORY_CASE == 2
int main(void) {
    (void)deal_alloc(TEST_LAYOUT_NUMBER, 8u);
    printf("unreachable\n");
    return 0;
}
#elif DEAL_MEMORY_CASE == 3
int main(void) {
    static const uint32_t test_unsorted_offsets[] = { 8u, 0u };
    static const deal_layout unsorted[] = {
        { TEST_LAYOUT_PAIR, 0u, 16u, test_unsorted_offsets, 2u }
    };
    deal_layout_install(unsorted, 1u);
    printf("unreachable\n");
    return 0;
}
#elif DEAL_MEMORY_CASE == 4
int main(void) {
    static const deal_layout reserved[] = {
        { DEAL_LAYOUT_CELL, 0u, 16u, NULL, 0u }
    };
    deal_layout_install(reserved, 1u);
    printf("unreachable\n");
    return 0;
}
#elif DEAL_MEMORY_CASE == 5
int main(void) {
    static const deal_layout duplicate[] = {
        { TEST_LAYOUT_NUMBER, 0u, 8u, NULL, 0u },
        { TEST_LAYOUT_NUMBER, 0u, 8u, NULL, 0u }
    };
    deal_layout_install(duplicate, 2u);
    printf("unreachable\n");
    return 0;
}
#elif DEAL_MEMORY_CASE == 6
int main(void) {
    static const uint32_t test_outside_offsets[] = { 0u, 16u };
    static const deal_layout outside[] = {
        { TEST_LAYOUT_PAIR, 0u, 16u, test_outside_offsets, 2u }
    };
    deal_layout_install(outside, 1u);
    printf("unreachable\n");
    return 0;
}
#elif DEAL_MEMORY_CASE == 7
int main(void) {
    (void)deal_object_layout(deal_null_singleton);
    printf("unreachable\n");
    return 0;
}
#elif DEAL_MEMORY_CASE == 8
int main(void) {
    deal_root_scope_release(0u);
    printf("unreachable\n");
    return 0;
}
#elif DEAL_MEMORY_CASE == 9
int main(void) {
    (void)deal_cell_read(deal_undefined_singleton);
    printf("unreachable\n");
    return 0;
}
#else
int main(void) {
    static const deal_layout first_cell = {
        DEAL_LAYOUT_CELL, 0u, 16u, NULL, 0u
    };
    static const deal_layout second_cell = {
        DEAL_LAYOUT_CELL, 0u, 24u, NULL, 0u
    };
    deal_layout_define(&first_cell);
    deal_layout_define(&second_cell);
    printf("unreachable\n");
    return 0;
}
#endif
