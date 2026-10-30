/*
 * test/native/gc_driver.c — the M1-M8 collector acceptance.
 *
 * A strict-C11 consumer of the published runtime tree. Case 0 exercises the
 * precise non-moving incremental mark/sweep collector through the delivered
 * entries only:
 *
 *   - the statistics surface (`deal_gc_statistics`) and its exact accounting
 *     of live payload bytes, live objects, cumulative allocation and reclaimed
 *     objects; a fully live full collection reclaims nothing;
 *   - cycle reclamation of a table/entry/key cycle through
 *     `deal_gc_collect_full`, with the exact object/byte/sweep deltas, and the
 *     survival of one live copy with unchanged identity and address;
 *   - the barrier store path of M2: referents allocated while an incremental
 *     cycle is in flight are stored into an already marked cell, array element
 *     slot and record value slot and survive with their payload, and later give
 *     way to a number, to `null` and to `undefined` without the collector ever
 *     reading a scalar or a nullish payload as a heap address;
 *   - the barrier's shade path of M2, isolated: referents that exist *before*
 *     the cycle starts and are reachable only from the driver's C locals are
 *     stored into an already marked and already traced cell, array element slot
 *     and record value slot while the cycle is marking, and survive with exact
 *     object/byte/sweep deltas that are one object per referent too large when
 *     `deal_store_barrier` or `deal_store_barrier_data` does nothing;
 *   - the internal growth policy that starts a cycle without an explicit call,
 *     driven by allocation volume and advanced by bounded slices, so a
 *     non-stress run completes a bounded number of cycles while the
 *     `DEAL_GC_STRESS=1` run collects at every safepoint;
 *   - the allocation-time mark of M1, isolated: the growth trigger is filled
 *     exactly with dropped objects, an explicit safepoint starts the cycle and
 *     passes its atomic phase at once, and the node allocated next — while the
 *     cycle is already sweeping — is written into a root slot only afterwards,
 *     so nothing but the allocation-time mark can keep it; without that mark
 *     the exact deltas are one object, one payload and one swept object lower
 *     and higher respectively;
 *   - root-source coverage: generated root frames, scoped runtime roots, the
 *     crossing value table (which roots its referents for exactly the
 *     crossing's lifetime), a driver-registered source — the shape the handle
 *     table and the open-operation table register, enumerated exactly once by
 *     the cycle-start walk and once by the atomic-phase rescan of one complete
 *     cycle — the static root table, and the Error record graph with its cause
 *     slot;
 *   - root-state restoration: the root depth returns to its caller's depth after
 *     every exit, repeated caught-Error loops do not grow the root stack, and a
 *     finished crossing leaves no scope and no root source behind;
 *   - queued completion frames: producers on other threads allocate, copy,
 *     post and reuse their input while the mutator allocates and collects; a
 *     collection with frames queued reclaims nothing (a frame roots no managed
 *     object), delivery stays on the mutator, every copied payload is intact
 *     and every frame is released exactly once; the delivered bytes then
 *     materialize a managed value that survives a later collection.
 *
 * The driver prints only its two result lines, so the non-stress and the stress
 * run of the same level must be byte-identical. Where the internal policy makes
 * a count mode-dependent (the number of completed cycles under
 * `DEAL_GC_STRESS=1`), the driver asserts the bound of the mode it detects
 * through `deal_platform_environment` and keeps the printed output identical.
 *
 * `-DDEAL_GC_CASE=<n>` selects one deterministic invariant-fault case: each must
 * terminate with one deterministic stderr line and exit 2 instead of returning
 * to the caller.
 */
#define _POSIX_C_SOURCE 200809L

#include "deal/native/container.h"
#include "deal/native/entry.h"
#include "deal/native/error.h"
#include "deal/native/memory.h"
#include "deal/native/platform.h"
#include "deal/native/text.h"
#include "deal/native/value.h"

#include <stdio.h>
#include <string.h>
#include <threads.h>
#include <unistd.h>

#ifndef DEAL_GC_CASE
#define DEAL_GC_CASE 0
#endif

/* Emitted layout identities (the module's per-layout metadata starts above the
 * runtime-reserved ids). */
#define TEST_LAYOUT_NODE 1024u   /* 16-byte payload, one managed slot */
#define TEST_LAYOUT_BULK 1025u   /* 1024-byte payload, no managed slot */
#define TEST_LAYOUT_WIDE 1026u   /* wide payload, no fixed map; resumable walk */
#define TEST_LAYOUT_EMPTY 1027u  /* zero-byte payload, no managed slot */

#define TEST_NODE_BYTES 16u
#define TEST_BULK_BYTES 1024u

/* Allocation volume that starts a cycle in the driver's pressure loops: more
 * than the collector's minimum trigger, so the policy is reached without an
 * explicit call. */
#define TEST_BULK_PRESSURE 700

/* Enough safepoints to complete every slice of a cycle in flight. */
#define TEST_SAFEPOINT_DRAIN 400

#if DEAL_GC_CASE == 0

typedef struct {
    void *link;
    uint64_t mark;
} test_node;

typedef struct {
    uint32_t token;
    char text[28];
} test_frame_record;

#define TEST_PRODUCER_COUNT 2
#define TEST_FRAMES_PER_PRODUCER 24

static const uint32_t test_node_offsets[] = {0u};

static const deal_layout test_layouts[] = {
    {TEST_LAYOUT_NODE, 0u, TEST_NODE_BYTES, test_node_offsets, 1u},
    {TEST_LAYOUT_BULK, 0u, TEST_BULK_BYTES, NULL, 0u},
    {TEST_LAYOUT_WIDE, 0u, 0u, NULL, 0u},
    {TEST_LAYOUT_EMPTY, 0u, 0u, NULL, 0u}
};

static int failures;
static int stress_mode;

static void check(int condition, const char *what) {
    if (!condition) {
        printf("FAIL: %s\n", what);
        failures += 1;
    }
}

/* One managed-reference store into the driver's own payload: the insertion
 * barrier runs first, exactly like a generated typed slot store (M2). */
static void node_set_link(void *node, void *referent) {
    deal_store_barrier(node, referent);
    ((test_node *)node)->link = referent;
}

static void node_set_mark(void *node, uint64_t mark) {
    ((test_node *)node)->mark = mark;
}

static uint64_t node_mark(const void *node) {
    return ((const test_node *)node)->mark;
}

static void *node_link(const void *node) {
    return ((const test_node *)node)->link;
}

static void read_statistics(deal_gc_statistics_record *out) {
    deal_gc_statistics(out);
}

/*
 * The process's resident set size in bytes, read from `/proc/self/statm`. The
 * backend targets Linux x86-64 only (D4), and this is the only way to observe
 * that a reclaimed arena returned its pages instead of only decrementing a
 * counter. It answers 0 when the measurement is unavailable, and it is not used
 * under AddressSanitizer, whose shadow mapping and free quarantine make the
 * resident size of the process no evidence about the collector.
 */
#if defined(__has_feature)
#  if __has_feature(address_sanitizer)
#    define DEAL_GC_DRIVER_ASAN 1
#  endif
#endif
#if !defined(DEAL_GC_DRIVER_ASAN) && defined(__SANITIZE_ADDRESS__)
#  define DEAL_GC_DRIVER_ASAN 1
#endif

static size_t resident_bytes(void) {
#if defined(DEAL_GC_DRIVER_ASAN)
    return 0u;
#else
    FILE *file = fopen("/proc/self/statm", "r");
    if (file == NULL) {
        return 0u;
    }
    unsigned long long total = 0u;
    unsigned long long resident = 0u;
    int read = fscanf(file, "%llu %llu", &total, &resident);
    (void)fclose(file);
    if (read != 2) {
        return 0u;
    }
    long page = sysconf(_SC_PAGESIZE);
    if (page <= 0) {
        return 0u;
    }
    return (size_t)resident * (size_t)page;
#endif
}

/* One bounded run of safepoints: it advances an in-flight cycle to its end
 * without changing any observable value. */
static void drain_safepoints(void) {
    for (int index = 0; index < TEST_SAFEPOINT_DRAIN; index += 1) {
        deal_safepoint();
    }
}

/* =========================================================================
 * 1. The statistics surface and a fully live collection (M7)
 * ========================================================================= */
static void check_statistics_and_live_collection(void) {
    deal_gc_statistics_record before;
    deal_gc_statistics_record after;

    deal_root_frame_enter(2u);
    deal_gc_collect_full();
    read_statistics(&before);
    check(before.object_count == 0u && before.live_bytes == 0u,
          "a normalized heap holds no live object");

    /* Every managed value this driver keeps across another allocating call is
     * rooted first (M4), exactly as generated code roots its temporaries: a
     * collection at the next allocation's safepoint may otherwise reclaim it,
     * which is what DEAL_GC_STRESS plus the sanitizer poison makes observable. */
    void *node = deal_alloc(TEST_LAYOUT_NODE, TEST_NODE_BYTES);
    deal_root_frame_write(0u, node);
    void *bulk = deal_alloc(TEST_LAYOUT_BULK, TEST_BULK_BYTES);
    deal_root_frame_write(1u, bulk);
    node_set_mark(node, 11u);
    node_set_link(node, bulk);
    read_statistics(&before);

    deal_gc_collect_full();
    read_statistics(&after);

    check(after.object_count == before.object_count,
          "a fully live full collection reclaims no object");
    check(after.live_bytes == before.live_bytes,
          "a fully live full collection changes no live byte");
    check(after.swept_objects == before.swept_objects,
          "a fully live full collection sweeps no object");
    check(after.collections > before.collections,
          "the full collection completes a cycle");
    check(after.allocated_bytes == before.allocated_bytes,
          "collection does not allocate");
    check(node_mark(node) == 11u && node_link(node) == bulk,
          "the surviving referents keep identity, payload and address");
    check(deal_root_frame_depth() == 1u && deal_root_scope_depth() == 0u,
          "the live-collection case restores the root depth");
    deal_root_frame_leave();
    check(deal_root_frame_depth() == 0u,
          "the live-collection case leaves its frame");
}

/* =========================================================================
 * 2. Cycle reclamation with exact deltas (M1, M3)
 * ========================================================================= */
static void check_cycle_reclamation(void) {
    deal_gc_statistics_record before;
    deal_gc_statistics_record built;
    deal_gc_statistics_record after;

    deal_root_frame_enter(3u);
    deal_gc_collect_full();
    read_statistics(&before);
    check(before.object_count == 0u && before.live_bytes == 0u,
          "the reclamation case starts from an empty heap");

    deal_table *first = deal_container_table_new();
    deal_root_frame_write(0u, first);
    deal_table *second = deal_container_table_new();
    deal_root_frame_write(1u, second);
    deal_string *key_second = deal_text_new("b", 1u);
    deal_root_frame_write(2u, key_second);
    deal_data second_datum = deal_data_of_reference(DEAL_DATA_TABLE, second);
    deal_container_table_write(first, key_second, &second_datum);
    deal_string *key_first = deal_text_new("a", 1u);
    deal_root_frame_write(2u, key_first);
    deal_data first_datum = deal_data_of_reference(DEAL_DATA_TABLE, first);
    deal_container_table_write(second, key_first, &first_datum);
    deal_root_frame_write(2u, NULL);
    read_statistics(&built);

    check(built.object_count > before.object_count,
          "the cycle's objects are accounted while it is live");
    check(built.live_bytes > before.live_bytes,
          "the cycle's payload bytes are accounted while it is live");
    check(deal_container_table_count(first) == 1u
              && deal_container_table_count(second) == 1u,
          "the two records reference each other");
    uint64_t cycle_objects = built.object_count - before.object_count;
    uint64_t cycle_bytes = built.live_bytes - before.live_bytes;
    uint64_t swept_before = built.swept_objects;

    /* Only the record references keep the cycle alive; dropping both root slots
     * makes the whole cycle unreachable. */
    deal_root_frame_write(0u, NULL);
    deal_root_frame_write(1u, NULL);
    deal_gc_collect_full();
    read_statistics(&after);

    check(after.object_count == before.object_count,
          "the unreachable cycle is reclaimed exactly");
    check(after.live_bytes == before.live_bytes,
          "every payload byte of the unreachable cycle is reclaimed");
    check(after.swept_objects == swept_before + cycle_objects,
          "the sweep reclaims exactly the cycle's objects");
    check(cycle_bytes != 0u,
          "the live cycle accounted payload bytes before it was reclaimed");
    check(deal_root_frame_depth() == 1u,
          "the reclamation case restores the root depth");
    deal_root_frame_leave();
}

/* =========================================================================
 * 2b. One live copy of a cyclic graph survives with unchanged identity
 * ========================================================================= */
static void check_live_cycle_survives(void) {
    deal_gc_statistics_record before;
    deal_gc_statistics_record after;

    deal_root_frame_enter(2u);
    deal_gc_collect_full();
    read_statistics(&before);
    check(before.object_count == 0u,
          "the live-cycle case starts from an empty heap");

    void *self = deal_alloc(TEST_LAYOUT_NODE, TEST_NODE_BYTES);
    node_set_mark(self, 12u);
    node_set_link(self, self);
    deal_root_frame_write(0u, self);
    read_statistics(&before);

    deal_gc_collect_full();
    read_statistics(&after);

    check(after.object_count == before.object_count,
          "a self-referencing live node survives at its own address");
    check(after.live_bytes == before.live_bytes,
          "the surviving node keeps its payload accounting");
    check(node_link(self) == self && node_mark(self) == 12u,
          "the surviving node keeps its identity and payload");

    deal_root_frame_write(0u, NULL);
    deal_gc_collect_full();
    read_statistics(&after);
    check(after.object_count == 0u && after.live_bytes == 0u,
          "the self reference is reclaimed once the root is dropped");
    deal_root_frame_leave();
}

/* =========================================================================
 * 3. The barrier store path and the growth-triggered incremental cycle (M2, M3)
 * ========================================================================= */
static void check_barrier_and_incremental_cycle(void) {
    deal_gc_statistics_record before;
    deal_gc_statistics_record pressure;
    deal_gc_statistics_record after;

    deal_root_frame_enter(5u);
    deal_gc_collect_full();
    read_statistics(&before);
    check(before.object_count == 0u && before.live_bytes == 0u,
          "the barrier case starts from an empty heap");

    void *cell = deal_cell_alloc_generation(NULL, 5u);
    deal_root_frame_write(0u, cell);
    deal_array *array = deal_container_array_new(TEST_LAYOUT_NODE, 1u);
    deal_root_frame_write(1u, array);
    deal_table *table = deal_container_table_new();
    deal_root_frame_write(2u, table);

    /* Dropped allocations reach the collection trigger, so a cycle runs without
     * any explicit collection call. */
    for (int index = 0; index < TEST_BULK_PRESSURE; index += 1) {
        (void)deal_alloc(TEST_LAYOUT_BULK, TEST_BULK_BYTES);
    }
    read_statistics(&pressure);
    if (stress_mode == 0) {
        uint64_t cycles = pressure.collections - before.collections;
        check(cycles >= 1u && cycles <= 3u,
              "the non-stress run advances by bounded slices, not one "
              "collection per allocation");
    } else {
        check(pressure.collections - before.collections > 2u,
              "DEAL_GC_STRESS collects at every safepoint");
    }

    /* The containers are marked by now. The referents below are allocated while
     * the cycle is in flight, so the allocation itself marks them and the
     * barrier's shade is a no-op for them (M1): this case proves that the store
     * path publishes a referent into a marked container and that the container
     * keeps it. The shade path of the barrier — a still-unmarked referent that
     * only the barrier can keep alive — is case 3b. */
    void *cell_node = deal_alloc(TEST_LAYOUT_NODE, TEST_NODE_BYTES);
    deal_root_frame_write(3u, cell_node);
    node_set_mark(cell_node, 21u);
    deal_cell_store(cell, cell_node);
    deal_root_frame_write(3u, NULL);

    void *array_node = deal_alloc(TEST_LAYOUT_NODE, TEST_NODE_BYTES);
    deal_root_frame_write(3u, array_node);
    node_set_mark(array_node, 22u);
    test_node element;
    element.link = array_node;
    element.mark = 22u;
    deal_container_array_store(array, 0u, &element);
    deal_root_frame_write(3u, NULL);

    void *table_node = deal_alloc(TEST_LAYOUT_NODE, TEST_NODE_BYTES);
    deal_root_frame_write(3u, table_node);
    node_set_mark(table_node, 23u);
    deal_string *key = deal_text_new("k", 1u);
    deal_root_frame_write(4u, key);
    deal_data table_datum = deal_data_of_reference(DEAL_DATA_CLASS, table_node);
    deal_container_table_write(table, key, &table_datum);
    deal_root_frame_write(4u, NULL);
    deal_root_frame_write(3u, NULL);

    drain_safepoints();
    deal_gc_collect_full();
    read_statistics(&after);

    check(deal_cell_read(cell) == cell_node && node_mark(cell_node) == 21u,
          "the fresh cell referent survives with its identity and payload");
    const test_node *stored =
        (const test_node *)deal_container_array_element(array, 0u);
    check(stored != NULL && stored->link == array_node && stored->mark == 22u,
          "the fresh array element referent survives with its payload");
    deal_string *stored_key = deal_container_table_key_at(table, 0u);
    check(stored_key != NULL, "the record keeps its key");
    deal_data read_back = deal_container_table_read(table, stored_key);
    check(read_back.tag == (uint32_t)DEAL_DATA_CLASS
              && read_back.reference == table_node,
          "the fresh record referent survives with its identity");
    check(deal_cell_generation(cell) == 5u,
          "the surviving cell keeps its binding generation");

    /* The pressure objects were unreachable and are reclaimed; the three
     * surviving referents and their containers stay accounted. */
    check(after.swept_objects == before.swept_objects
              + (uint64_t)TEST_BULK_PRESSURE,
          "the sweep reclaims exactly the dropped pressure objects");
    check(after.object_count == 9u,
          "the live set is exactly the containers and their three referents");
    check(after.live_bytes < 4096u,
          "the reclaimed pressure payload leaves the live byte count small");

    check(deal_root_frame_depth() == 1u && deal_root_scope_depth() == 0u,
          "the barrier case restores the root depth");
    deal_root_frame_leave();
}

/* =========================================================================
 * 3b. The barrier's shade path: a referent that is white when it is stored
 *     into an already marked, already traced container (M2)
 * ========================================================================= */

/*
 * The internal growth policy's minimum trigger (the collector starts a cycle at
 * a safepoint once the allocation footprint consumed since the last completed
 * cycle reaches the larger of this minimum and the footprint that survived that
 * cycle). The driver keeps its setup far below it and crosses it with one single
 * dropped allocation, whose own safepoint runs before that allocation is
 * charged; the explicit safepoint below — never an allocation of the driver's
 * own setup — is therefore the statement that starts the cycle.
 */
#define TEST_BARRIER_TRIGGER (256u * 1024u)

/*
 * A wide rooted chain: one marking slice traces a bounded part of it, so the
 * cycle is still marking (never sweeping) when the stores below run.
 */
#define TEST_BARRIER_CHAIN 600u

/*
 * The case that isolates the insertion barrier. Every referent here exists
 * *before* the cycle starts and is reachable only from the driver's own C
 * locals, so the atomic phase's rescan of every root source cannot mark it. The
 * chain's root takes the first frame slot and the three containers the three
 * after it, so the gray work list — filled in slot order and popped
 * last-in-first-out — pops and traces the containers before anything else: each
 * container is already marked *and already traced* when the store into it runs.
 * Only `deal_store_barrier` (the cell store and the array element store) and
 * `deal_store_barrier_data` (the record value slot) can then keep the referents
 * alive, and the count deltas asserted below are one object per referent too
 * large when either entry does nothing.
 */
static void check_barrier_shades_a_white_referent(void) {
    deal_gc_statistics_record before;
    deal_gc_statistics_record prepared;
    deal_gc_statistics_record stored;
    deal_gc_statistics_record after;
    size_t top_up = 0u;
    void *chain = NULL;
    void *cell_referent = NULL;
    void *array_referent = NULL;
    void *table_referent = NULL;
    int referents_live = 0;

    deal_root_frame_enter(8u);
    deal_gc_collect_full();
    read_statistics(&before);
    check(before.object_count == 0u && before.live_bytes == 0u,
          "the white-referent barrier case starts from an empty heap");

    for (uint32_t index = 0; index < TEST_BARRIER_CHAIN; index += 1u) {
        void *link = chain;
        chain = deal_alloc(TEST_LAYOUT_NODE, TEST_NODE_BYTES);
        node_set_link(chain, link);
        deal_root_frame_write(0u, chain);
    }

    /* The containers take the three slots after the chain's, so the collector
     * pops and traces them before it traces the chain. */
    void *cell = deal_cell_alloc_generation(NULL, 9u);
    deal_root_frame_write(1u, cell);
    deal_array *array = deal_container_array_new(TEST_LAYOUT_NODE, 1u);
    deal_root_frame_write(2u, array);
    deal_table *table = deal_container_table_new();
    deal_root_frame_write(3u, table);

    /* The record member that carries the third referent is created before the
     * cycle starts, so the store below allocates nothing: no safepoint can
     * intervene between the cycle's first marking slice and the three stores,
     * and the collector is still marking (one slice of a 600-node chain) when
     * they run. */
    deal_string *key = deal_text_new("w", 1u);
    deal_root_frame_write(7u, key);
    deal_data placeholder = deal_data_of_number(0.0);
    deal_container_table_write(table, key, &placeholder);

    /* The three referents exist before the cycle starts. A stress run collects
     * at every safepoint, so they stay rooted until after the stores there;
     * the non-stress run clears their slots below, before the cycle's root
     * rescan can see them. */
    cell_referent = deal_alloc(TEST_LAYOUT_NODE, TEST_NODE_BYTES);
    node_set_mark(cell_referent, 71u);
    deal_root_frame_write(4u, cell_referent);
    array_referent = deal_alloc(TEST_LAYOUT_NODE, TEST_NODE_BYTES);
    node_set_mark(array_referent, 72u);
    deal_root_frame_write(5u, array_referent);
    table_referent = deal_alloc(TEST_LAYOUT_NODE, TEST_NODE_BYTES);
    node_set_mark(table_referent, 73u);
    deal_root_frame_write(6u, table_referent);
    read_statistics(&prepared);

    /* One dropped allocation whose payload alone crosses the growth policy's
     * trigger: its own safepoint ran before it was charged, so the cycle below
     * is started by the explicit safepoint and not by this allocation. */
    uint64_t accumulated = prepared.allocated_bytes - before.allocated_bytes;
    check(accumulated < (uint64_t)TEST_BARRIER_TRIGGER,
          "the white-referent case keeps its setup payload below the "
          "collection trigger");
    top_up = (size_t)TEST_BARRIER_TRIGGER;
    check(accumulated < (uint64_t)top_up,
          "the pressure object alone crosses the collection trigger");
    (void)deal_alloc(TEST_LAYOUT_BULK, top_up);

    if (stress_mode == 0) {
        /* From here the referents are named by these C locals only: the cycle
         * the next safepoint starts cannot mark them from any root source. */
        deal_root_frame_write(4u, NULL);
        deal_root_frame_write(5u, NULL);
        deal_root_frame_write(6u, NULL);
    }
    /* Starts the incremental cycle (the trigger was reached), one bounded
     * marking slice, which pops and traces the three containers first. */
    deal_safepoint();

    /* The still-white referents are stored into the already marked containers
     * while the cycle is marking: the cell value slot, the reference-kind
     * element slot and the record value slot (M2). */
    deal_cell_store(cell, cell_referent);
    test_node element;
    element.link = array_referent;
    element.mark = 72u;
    deal_container_array_store(array, 0u, &element);
    deal_data table_datum =
        deal_data_of_reference(DEAL_DATA_CLASS, table_referent);
    deal_container_table_write(table, key, &table_datum);
    deal_root_frame_write(7u, NULL);
    if (stress_mode != 0) {
        /* A stress safepoint completed a cycle instead of starting one, so the
         * stores ran with the collector idle; the referents now hang off the
         * containers only. */
        deal_root_frame_write(4u, NULL);
        deal_root_frame_write(5u, NULL);
        deal_root_frame_write(6u, NULL);
    }
    read_statistics(&stored);

    deal_gc_collect_full();
    read_statistics(&after);

    /* The only object the cycle may reclaim is the dropped pressure object; a
     * referent the barrier failed to shade is one more reclaimed object, one
     * more swept object and one more reclaimed payload. */
    if (stress_mode == 0) {
        check(after.object_count + 1u == stored.object_count,
              "the cycle reclaims exactly the dropped pressure object");
        check(after.swept_objects == stored.swept_objects + 1u,
              "the sweep reclaims exactly the dropped pressure object");
        check(after.live_bytes + (uint64_t)top_up == stored.live_bytes,
              "the cycle reclaims no stored referent's payload byte");
        referents_live = after.object_count + 1u == stored.object_count;
    } else {
        check(after.object_count == stored.object_count,
              "a stress run keeps every stored referent reachable through its "
              "container");
        check(after.swept_objects == stored.swept_objects,
              "a stress run reclaims nothing after the stores");
        check(after.live_bytes == stored.live_bytes,
              "a stress run reclaims no stored referent's payload byte");
        referents_live = after.object_count == stored.object_count;
    }

    check(deal_cell_read(cell) == cell_referent,
          "the cell keeps the referent stored while the container was marked");
    check(deal_cell_generation(cell) == 9u,
          "the container keeps its own identity across the cycle");
    const test_node *stored_element =
        (const test_node *)deal_container_array_element(array, 0u);
    check(stored_element != NULL && stored_element->link == array_referent,
          "the element slot keeps the referent stored while the array was "
          "marked");
    check(deal_container_table_count(table) == 1u,
          "the record keeps the stored member");
    check(deal_container_table_key_at(table, 0u) == key,
          "the record keeps the key it was stored under");
    deal_data read_back = deal_container_table_read(table, key);
    check(read_back.tag == (uint32_t)DEAL_DATA_CLASS
              && read_back.reference == table_referent,
          "the record value slot keeps the referent stored while the record "
          "was marked");
    if (referents_live != 0) {
        check(node_mark(cell_referent) == 71u
                  && node_mark(array_referent) == 72u
                  && node_mark(table_referent) == 73u,
              "every stored referent keeps its payload after the cycle");
    }

    deal_root_frame_write(0u, NULL);
    deal_root_frame_write(1u, NULL);
    deal_root_frame_write(2u, NULL);
    deal_root_frame_write(3u, NULL);
    deal_gc_collect_full();
    read_statistics(&after);
    check(after.object_count == 0u && after.live_bytes == 0u,
          "the white-referent barrier case leaves no managed object behind");
    check(deal_root_frame_depth() == 1u && deal_root_scope_depth() == 0u,
          "the white-referent barrier case restores the root depth");
    deal_root_frame_leave();
}

/* =========================================================================
 * 3c. The record-deletion barrier: the rewired list links (M2)
 * ========================================================================= */

/*
 * The member count this case inserts. The first bounded marking slice traces
 * the tail part of the list, so the cycle is still marking when the deletions
 * below run and the members they rewire are marked or traced already.
 */
#define TEST_DELETE_ENTRIES 100u

/*
 * The deleted members: the head, whose removal republishes the record's own
 * `head` slot, and the range [TEST_DELETE_FIRST, TEST_DELETE_LAST], which
 * contains the boundary between the traced tail of the list and the untraced
 * prefix and whose removal republishes the neighbours' `next` and `previous`
 * links. Deleting the head and the boundary range together severs every path
 * from a marked member to the prefix, so a rewiring store that skips the
 * insertion barrier strands the whole surviving prefix and its keys.
 */
#define TEST_DELETE_FIRST 10u
#define TEST_DELETE_LAST  90u

/* The removed members: the head plus the deleted range. */
#define TEST_DELETE_COUNT (1u + (size_t)(TEST_DELETE_LAST - TEST_DELETE_FIRST + 1u))

/* The members that survive every deletion. */
#define TEST_DELETE_SURVIVORS (TEST_DELETE_ENTRIES - TEST_DELETE_COUNT)

/* 1 when the record's member at `index` still carries `text` as its key. */
static int table_key_is(deal_table *table, size_t index, const char *text) {
    deal_string *key = deal_container_table_key_at(table, index);
    if (key == NULL) {
        return 0;
    }
    deal_root_scope_push();
    deal_string *expected = deal_text_new(text, strlen(text));
    (void)deal_root_scope_hold(expected);
    int same = deal_text_compare(key, expected) == 0;
    deal_root_scope_pop();
    return same;
}

/* The managed referent stored under `text`, or NULL. */
static void *table_referent(deal_table *table, const char *text) {
    deal_root_scope_push();
    deal_string *key = deal_text_new(text, strlen(text));
    (void)deal_root_scope_hold(key);
    deal_data datum = deal_container_table_read(table, key);
    deal_root_scope_pop();
    return datum.reference;
}

/*
 * The record-deletion case that isolates the list rewiring of
 * `deal_container_table_delete`. The record and one value node per member are
 * built before the cycle starts and their key pointers are kept in the driver's
 * own array; the explicit safepoint then runs one bounded marking slice, and
 * the deletions below only rewire links — every key is already in hand, so
 * nothing allocates and no safepoint advances the cycle out of its marking
 * phase.
 * Deleting a member republishes its neighbours' `next`, `previous`, `head` and
 * `tail` links, and each of those is a managed-reference store into an already
 * marked container: only the insertion barrier keeps a still-white neighbour
 * that the rewiring alone names alive for the cycle's sweep.
 */
static void check_record_deletion_barrier(void) {
    deal_gc_statistics_record before;
    deal_gc_statistics_record prepared;
    deal_gc_statistics_record stored;
    deal_gc_statistics_record after;
    deal_string *keys[TEST_DELETE_ENTRIES];
    size_t top_up = 0u;
    int survivors_live = 0;

    deal_root_frame_enter(3u);
    deal_gc_collect_full();
    read_statistics(&before);
    check(before.object_count == 0u && before.live_bytes == 0u,
          "the record-deletion case starts from an empty heap");

    deal_table *table = deal_container_table_new();
    deal_root_frame_write(0u, table);
    for (uint32_t index = 0; index < TEST_DELETE_ENTRIES; index += 1u) {
        char label[8];
        (void)snprintf(label, sizeof(label), "k%u", (unsigned)index);
        deal_string *key = deal_text_new(label, strlen(label));
        deal_root_frame_write(1u, key);
        void *value = deal_alloc(TEST_LAYOUT_NODE, TEST_NODE_BYTES);
        node_set_mark(value, index);
        deal_root_frame_write(2u, value);
        deal_data datum = deal_data_of_reference(DEAL_DATA_CLASS, value);
        deal_container_table_write(table, key, &datum);
        keys[index] = key;
        deal_root_frame_write(1u, NULL);
        deal_root_frame_write(2u, NULL);
    }
    check(deal_container_table_count(table) == TEST_DELETE_ENTRIES,
          "the record-deletion case inserts every member it deletes");
    read_statistics(&prepared);

    /* Fill the remaining trigger volume with one dropped object, so the explicit
     * safepoint below — and not an allocation of the setup — starts the cycle. */
    uint64_t accumulated = prepared.allocated_bytes - before.allocated_bytes;
    check(accumulated < (uint64_t)TEST_BARRIER_TRIGGER,
          "the record-deletion case keeps its setup below the collection "
          "trigger");
    top_up = accumulated < (uint64_t)TEST_BARRIER_TRIGGER
        ? (size_t)((uint64_t)TEST_BARRIER_TRIGGER - accumulated)
        : (size_t)TEST_BULK_BYTES;
    check(top_up >= TEST_BULK_BYTES,
          "the pressure object fills the remaining trigger volume");
    (void)deal_alloc(TEST_LAYOUT_BULK, top_up);

    /* One bounded marking slice: the record and the tail part of the list it
     * holds are marked and traced, and the cycle stays in its marking phase. */
    deal_safepoint();

    /* The deletions: the head, then the range around the traced boundary. No
     * step allocates, so the cycle stays exactly where that slice left it. */
    deal_container_table_delete(table, keys[0]);
    for (uint32_t index = TEST_DELETE_FIRST; index <= TEST_DELETE_LAST;
            index += 1u) {
        deal_container_table_delete(table, keys[index]);
    }
    check(deal_container_table_count(table) == TEST_DELETE_SURVIVORS,
          "the record keeps exactly the members the case did not delete");
    read_statistics(&stored);

    deal_gc_collect_full();
    read_statistics(&after);

    /* The only objects the cycle may reclaim are the deleted members with their
     * keys and values and the dropped pressure object. A survivor the rewiring
     * failed to shade is one entry, one key and one value per member more, and
     * the whole prefix that hangs off it. */
    check(after.swept_objects == stored.swept_objects + 3u * TEST_DELETE_COUNT
              + (stress_mode == 0 ? 1u : 0u),
          "the cycle reclaims exactly the deleted members and the dropped "
          "pressure object");
    survivors_live = after.swept_objects == stored.swept_objects
        + 3u * TEST_DELETE_COUNT + (stress_mode == 0 ? 1u : 0u);
    check(after.object_count == 1u + 3u * TEST_DELETE_SURVIVORS,
          "the record and every surviving member survive the deletion cycle");
    check(deal_container_table_count(table) == TEST_DELETE_SURVIVORS,
          "the surviving record keeps its member count");

    /* The survivors keep their first-insertion order, their keys and their
     * values: no rewired neighbour was reclaimed out of the list. */
    check(table_key_is(table, 0u, "k1"),
          "the surviving list starts at the member that followed the deleted "
          "head");
    check(table_key_is(table, 8u, "k9"),
          "the surviving list holds the members below the deleted range");
    check(table_key_is(table, 9u, "k91"),
          "the surviving list closes the deleted range");
    check(table_key_is(table, TEST_DELETE_SURVIVORS - 1u, "k99"),
          "the surviving list still ends at its last member");
    if (survivors_live != 0) {
        check(node_mark(table_referent(table, "k1")) == 1u,
              "the member below the deleted head keeps its value");
        check(node_mark(table_referent(table, "k9")) == 9u,
              "the member below the deleted range keeps its value");
        check(node_mark(table_referent(table, "k99")) == 99u,
              "the last member keeps its value");
    }

    deal_root_frame_write(0u, NULL);
    deal_root_frame_write(1u, NULL);
    deal_root_frame_write(2u, NULL);
    deal_gc_collect_full();
    read_statistics(&after);
    check(after.object_count == 0u && after.live_bytes == 0u,
          "the record-deletion case leaves no managed object behind");
    check(deal_root_frame_depth() == 1u && deal_root_scope_depth() == 0u,
          "the record-deletion case restores the root depth");
    deal_root_frame_leave();
}

/* =========================================================================
 * 3d. Collection without an explicit collection call (the growth policy)
 * ========================================================================= */
static void check_automatic_collection(void) {
    deal_gc_statistics_record before;
    deal_gc_statistics_record after;

    deal_root_frame_enter(1u);
    deal_gc_collect_full();
    read_statistics(&before);
    check(before.object_count == 0u,
          "the growth-policy case starts from an empty heap");

    /* The driver never calls deal_gc_collect_full here: the allocation volume
     * alone starts and advances a cycle, so no non-collecting production mode
     * exists. */
    for (int index = 0; index < 800; index += 1) {
        (void)deal_alloc(TEST_LAYOUT_BULK, TEST_BULK_BYTES);
    }
    drain_safepoints();
    read_statistics(&after);

    check(after.collections > before.collections,
          "a cycle completes without an explicit collection call");
    check(after.swept_objects - before.swept_objects >= 256u,
          "the growth policy reclaims dropped objects on its own");
    check(after.allocated_bytes == before.allocated_bytes
              + 800u * (uint64_t)TEST_BULK_BYTES,
          "the allocation counter accounts every allocated payload byte");
    check(deal_root_frame_depth() == 1u && deal_root_scope_depth() == 0u,
          "the growth-policy case restores the root depth");
    deal_root_frame_leave();
    deal_gc_collect_full();
}

/* =========================================================================
 * 3e. The allocation-time mark of an object allocated inside an in-flight
 *     incremental cycle (M1)
 * ========================================================================= */

/*
 * The internal growth policy's minimum trigger, mirrored like the barrier case
 * mirrors it: a cycle starts at a safepoint once the allocation footprint
 * consumed since the last completed cycle reaches the larger of this minimum and
 * the footprint that survived that cycle. The dropped pressure below stays under
 * it and one single dropped allocation crosses it, its own safepoint having run
 * before that allocation was charged, so the explicit safepoint — never an
 * allocation of this case's own setup — is the statement that starts the cycle.
 */
#define TEST_WINDOW_TRIGGER (256u * 1024u)
#define TEST_WINDOW_PRESSURE \
    ((int)(((size_t)TEST_WINDOW_TRIGGER / 2u) / TEST_BULK_BYTES))

/*
 * The case that isolates the allocation-time mark (M1). The pressure objects are
 * unreachable and unmarked, so the cycle's atomic phase — which finds no root at
 * all — completes at once and leaves the collector sweeping. The node allocated
 * next is therefore allocated while the cycle is in flight, and it is written
 * into a root slot only after the atomic phase, which no later rescan revisits;
 * the barrier cannot help either, because the collector is sweeping rather than
 * marking, and a store into a root slot is exactly the store M2 leaves to the
 * atomic phase. Only `header->mark = 1` at allocation keeps the node alive, and
 * a collector without it reclaims the node as one object, one payload and one
 * swept object more (M1).
 */
static void check_allocation_mark_in_inflight_cycle(void) {
    deal_gc_statistics_record before;
    deal_gc_statistics_record pressure;
    deal_gc_statistics_record started;
    deal_gc_statistics_record after;

    deal_root_frame_enter(1u);
    deal_gc_collect_full();
    read_statistics(&before);
    check(before.object_count == 0u && before.live_bytes == 0u,
          "the allocation-mark case starts from an empty heap");
    check((uint64_t)TEST_WINDOW_PRESSURE * TEST_BULK_BYTES
              < (uint64_t)TEST_WINDOW_TRIGGER,
          "the dropped pressure payload stays below the collector's minimum "
          "trigger");

    /* The dropped pressure stays below the trigger and one single dropped
     * allocation crosses it: the collector is still idle after both, so no
     * allocation of this case started the cycle. */
    for (int index = 0; index < TEST_WINDOW_PRESSURE; index += 1) {
        (void)deal_alloc(TEST_LAYOUT_BULK, TEST_BULK_BYTES);
    }
    (void)deal_alloc(TEST_LAYOUT_BULK, (size_t)TEST_WINDOW_TRIGGER);
    read_statistics(&pressure);
    if (stress_mode == 0) {
        check(pressure.collections == before.collections,
              "the dropped pressure stops just below the collection trigger");
        check(pressure.object_count == before.object_count
                  + (uint64_t)TEST_WINDOW_PRESSURE + 1u,
              "no pressure object is reclaimed before the cycle starts");
    }

    /* Starts the cycle; the atomic phase finds no root, so the collector is
     * already sweeping when the next statement runs. */
    deal_safepoint();
    read_statistics(&started);
    if (stress_mode == 0) {
        check(started.collections == before.collections,
              "the cycle started by the explicit safepoint is still in "
              "flight");
        check(started.swept_objects == before.swept_objects,
              "the started cycle has swept nothing yet");
        check(started.object_count == pressure.object_count
                  && started.live_bytes == pressure.live_bytes,
              "the started cycle leaves every pressure object in the live set");
    }

    /* The fresh node exists only inside the in-flight cycle and is rooted only
     * after the atomic phase has passed. */
    void *node = deal_alloc(TEST_LAYOUT_NODE, TEST_NODE_BYTES);
    node_set_mark(node, 81u);
    deal_root_frame_write(0u, node);
    drain_safepoints();
    read_statistics(&after);

    check(after.object_count == 1u,
          "the object allocated while the cycle was in flight survives it");
    check(after.live_bytes == TEST_NODE_BYTES,
          "the surviving object keeps its payload accounting");
    check(after.swept_objects == before.swept_objects
              + (uint64_t)TEST_WINDOW_PRESSURE + 1u,
          "the cycle reclaims exactly the dropped pressure objects");
    if (stress_mode == 0) {
        check(after.collections == before.collections + 1u,
              "the pressure volume and the fresh node complete one cycle");
    }
    if (after.object_count == 1u) {
        check(node_mark(node) == 81u,
              "the surviving object keeps its identity and payload");
    }

    deal_root_frame_write(0u, NULL);
    deal_gc_collect_full();
    read_statistics(&after);
    check(after.object_count == 0u && after.live_bytes == 0u,
          "the object allocated inside the cycle is reclaimed once its root is "
          "dropped");
    check(deal_root_frame_depth() == 1u && deal_root_scope_depth() == 0u,
          "the allocation-mark case restores the root depth");
    deal_root_frame_leave();
}

/* =========================================================================
 * 4. Number and nullish replacement of a managed slot (M2, M3)
 * ========================================================================= */
static void check_scalar_and_nullish_replacement(void) {
    deal_gc_statistics_record before;
    deal_gc_statistics_record stored;
    deal_gc_statistics_record after;

    deal_root_frame_enter(3u);
    deal_gc_collect_full();
    read_statistics(&before);
    check(before.object_count == 0u && before.live_bytes == 0u,
          "the replacement case starts from an empty heap");

    deal_table *table = deal_container_table_new();
    deal_root_frame_write(0u, table);
    deal_string *key = deal_text_new("k", 1u);
    deal_root_frame_write(1u, key);
    void *referent = deal_alloc(TEST_LAYOUT_NODE, TEST_NODE_BYTES);
    deal_root_frame_write(2u, referent);
    node_set_mark(referent, 31u);
    deal_data managed = deal_data_of_reference(DEAL_DATA_CLASS, referent);
    deal_container_table_write(table, key, &managed);
    deal_root_frame_write(2u, NULL);
    read_statistics(&stored);
    check(stored.object_count == before.object_count + 4u,
          "the record, its key, its member and the stored object are live");
    uint64_t swept_before = stored.swept_objects;
    uint64_t bytes_before = stored.live_bytes;

    /* Replacing the managed payload with a number leaves the object unreachable
     * while the scalar bits are stored where a pointer used to be. */
    deal_data number = deal_data_of_number(42.5);
    deal_container_table_write(table, key, &number);
    deal_gc_collect_full();
    read_statistics(&after);
    check(after.object_count == stored.object_count - 1u,
          "the replaced referent is reclaimed exactly");
    check(after.live_bytes == bytes_before - TEST_NODE_BYTES,
          "the replaced referent's payload bytes are reclaimed exactly");
    check(after.swept_objects == swept_before + 1u,
          "the replacement sweeps exactly the replaced referent");
    check(deal_container_table_has(table, key) == 1,
          "the key stays present with a scalar value");
    deal_data number_read = deal_container_table_read(table, key);
    check(number_read.tag == (uint32_t)DEAL_DATA_NUMBER
              && number_read.number == 42.5,
          "the stored number is read back unchanged");
    check(number_read.reference == NULL,
          "a scalar datum carries no managed referent");

    /* The null and undefined data values are present values, never absence and
     * never heap addresses. */
    deal_data null_datum = deal_data_of_null();
    deal_container_table_write(table, key, &null_datum);
    deal_gc_collect_full();
    read_statistics(&after);
    check(after.object_count == stored.object_count - 1u
              && after.swept_objects == swept_before + 1u,
          "a null value reclaims nothing further");
    deal_data null_read = deal_container_table_read(table, key);
    check(null_read.tag == (uint32_t)DEAL_DATA_NULL
              && deal_container_table_has(table, key) == 1,
          "null is a present value, never absence");

    deal_data undefined_datum = deal_data_of_undefined();
    deal_container_table_write(table, key, &undefined_datum);
    deal_gc_collect_full();
    read_statistics(&after);
    check(after.object_count == stored.object_count - 1u
              && after.swept_objects == swept_before + 1u,
          "an undefined value reclaims nothing further");
    deal_data undefined_read = deal_container_table_read(table, key);
    check(undefined_read.tag == (uint32_t)DEAL_DATA_UNDEFINED
              && deal_container_table_has(table, key) == 1,
          "undefined is a present value, distinct from absence");

    deal_root_frame_write(1u, NULL);
    deal_root_frame_write(0u, NULL);
    deal_gc_collect_full();
    read_statistics(&after);
    check(after.object_count == 0u && after.live_bytes == 0u,
          "the record and its key are reclaimed once the root is dropped");
    deal_root_frame_leave();
}

/* =========================================================================
 * 5. Error graph rooting and reclamation (M5, R5)
 * ========================================================================= */
static void check_error_rooting(void) {
    deal_gc_statistics_record before;
    deal_gc_statistics_record after;
    const char *text = NULL;

    deal_root_frame_enter(2u);
    deal_gc_collect_full();
    read_statistics(&before);
    check(before.object_count == 0u,
          "the Error case starts from an empty heap");

    deal_error *cause = deal_error_create("E9002", "cause text", NULL);
    deal_root_frame_write(0u, cause);
    deal_error *outer = deal_error_create("E8010", "outer text", NULL);
    deal_root_frame_write(1u, outer);
    deal_error_set_cause(outer, cause);
    deal_root_frame_write(0u, NULL);
    (void)deal_alloc(TEST_LAYOUT_BULK, TEST_BULK_BYTES);
    read_statistics(&before);

    deal_gc_collect_full();
    read_statistics(&after);
    check(after.object_count == before.object_count - 1u
              && after.live_bytes == before.live_bytes - TEST_BULK_BYTES,
          "the dropped object is reclaimed while the Error graph survives");
    check(deal_error_code(outer, &text) == 1 && strcmp(text, "E8010") == 0,
          "the surviving Error keeps its code");
    text = NULL;
    check(deal_error_cause_code(outer, &text) == 1
              && strcmp(text, "E9002") == 0,
          "the cause is reached only through the surviving Error");

    deal_root_frame_write(1u, NULL);
    deal_gc_collect_full();
    read_statistics(&after);
    check(after.object_count == 0u && after.live_bytes == 0u,
          "the dropped Error graph is reclaimed with its cause");
    deal_root_frame_leave();
}

/* =========================================================================
 * 5b. Captured-binding cells across a collection (M6, C5)
 * ========================================================================= */
static void check_cells_under_collection(void) {
    enum { ITERATIONS = 8 };
    deal_gc_statistics_record before;
    deal_gc_statistics_record live;
    deal_gc_statistics_record after;
    void *cells[ITERATIONS];
    void *nodes[ITERATIONS];

    deal_root_frame_enter(ITERATIONS + 1);
    deal_gc_collect_full();
    read_statistics(&before);
    check(before.object_count == 0u,
          "the cell case starts from an empty heap");

    /* Each iteration allocates its own binding generation, so no two iterations
     * share a cell (C5, M6); every cell is rooted in its own frame slot. */
    for (int index = 0; index < ITERATIONS; index += 1) {
        nodes[index] = deal_alloc(TEST_LAYOUT_NODE, TEST_NODE_BYTES);
        deal_root_frame_write(ITERATIONS, nodes[index]);
        node_set_mark(nodes[index], (uint64_t)(60 + index));
        cells[index] = deal_cell_alloc_generation(nodes[index],
                                                  (uint32_t)index + 1u);
        deal_root_frame_write((uint32_t)index, cells[index]);
        deal_root_frame_write(ITERATIONS, NULL);
    }
    read_statistics(&live);
    check(live.object_count == (uint64_t)(2 * ITERATIONS),
          "every iteration's cell and value are live");
    deal_gc_collect_full();
    read_statistics(&after);
    check(after.object_count == live.object_count
              && after.live_bytes == live.live_bytes,
          "a collection keeps every captured generation and its value");
    check(deal_cell_generation(cells[0]) == 1u
              && deal_cell_generation(cells[ITERATIONS - 1]) == ITERATIONS,
          "the fresh-cell rule keeps one generation per iteration");
    check(deal_cell_read(cells[0]) == nodes[0]
              && deal_cell_read(cells[ITERATIONS - 1]) == nodes[ITERATIONS - 1],
          "each generation keeps its own captured value");

    /* Two aliases of one cell observe one binding identity: a store through one
     * alias is visible through both, and the other generations keep their own
     * values. */
    void *alias = cells[0];
    deal_cell_store(alias, nodes[3]);
    check(deal_cell_read(cells[0]) == nodes[3],
          "a store through one alias is visible through the other");
    check(deal_cell_read(cells[1]) == nodes[1],
          "another generation keeps its own binding");
    check(deal_cell_generation(alias) == 1u,
          "a store through an alias keeps the binding generation");

    /* A tag-aware root slot carries a scalar datum here: the collector traces
     * only managed payloads, so this slot contributes no referent. */
    deal_data number = deal_data_of_number(7.0);
    deal_root_frame_write_data(ITERATIONS, &number);
    deal_gc_collect_full();
    check(deal_cell_read(alias) == nodes[3],
          "the mutated binding survives a collection beside a scalar root slot");
    deal_root_frame_write(ITERATIONS, NULL);

    for (int index = 0; index < ITERATIONS; index += 1) {
        deal_root_frame_write((uint32_t)index, NULL);
    }
    deal_gc_collect_full();
    read_statistics(&after);
    check(after.object_count == 0u && after.live_bytes == 0u,
          "the dropped generations are reclaimed with their values");
    check(deal_root_frame_depth() == 1u && deal_root_scope_depth() == 0u,
          "the cell case restores the root depth");
    deal_root_frame_leave();
}

/* =========================================================================
 * 6. Registered root sources and the static root table (M5)
 * ========================================================================= */
static void *driver_source_referent;
static size_t driver_source_scans;

static void driver_root_source(deal_reference_visit visit, void *visit_context,
                               void *source_context) {
    (void)source_context;
    driver_source_scans += 1u;
    if (driver_source_referent != NULL) {
        visit(driver_source_referent, visit_context);
    }
}

static void check_registered_sources(void) {
    deal_gc_statistics_record before;
    deal_gc_statistics_record prior;
    deal_gc_statistics_record after;
    void *static_slot = NULL;

    deal_root_frame_enter(2u);
    deal_gc_collect_full();
    read_statistics(&before);
    check(before.object_count == 0u,
          "the root-source case starts from an empty heap");
    check(deal_static_root_count() == 0u,
          "the static root table starts empty");

    /* A driver-registered source has exactly the shape the handle table and the
     * open-operation table register: one enumeration of managed referents. */
    void *sourced = deal_alloc(TEST_LAYOUT_NODE, TEST_NODE_BYTES);
    deal_root_frame_write(0u, sourced);
    node_set_mark(sourced, 41u);
    driver_source_referent = sourced;
    driver_source_scans = 0u;
    deal_root_source_register(driver_root_source, NULL);
    deal_root_frame_write(0u, NULL);

    /* One complete cycle from an idle collector enumerates the registered
     * source exactly twice: the cycle-start walk and the atomic-phase rescan
     * (M3, `SafepointAndCollect`). Deleting either enumeration fails this case,
     * so the phase-boundary guarantee is observed rather than merely reached. */
    read_statistics(&prior);
    deal_gc_collect_full();
    read_statistics(&after);
    check(driver_source_scans == 2u,
          "one cycle enumerates a registered root source once at its start and "
          "once in the atomic phase");
    check(after.collections == prior.collections + 1u,
          "that one complete cycle is one completed collection");
    check(after.object_count == before.object_count + 1u
              && node_mark(sourced) == 41u,
          "a registered root source keeps its referent alive");

    if (stress_mode == 0) {
        /* Below the growth trigger no cycle runs, so an idle period must not
         * enumerate any source at all. */
        driver_source_scans = 0u;
        (void)deal_alloc(TEST_LAYOUT_BULK, TEST_BULK_BYTES);
        drain_safepoints();
        check(driver_source_scans == 0u,
              "an idle period enumerates no registered root source");
        read_statistics(&after);
        check(after.collections == prior.collections + 1u,
              "the idle period completes no cycle");
    }

    deal_root_source_unregister(driver_root_source, NULL);
    driver_source_referent = NULL;
    deal_gc_collect_full();
    read_statistics(&after);
    check(after.object_count == before.object_count,
          "an unregistered source roots nothing");

    /* The static root table is the module's export-table, class-plan and
     * descriptor-atom surface; a written slot roots its referent. */
    void *static_referent = deal_alloc(TEST_LAYOUT_NODE, TEST_NODE_BYTES);
    deal_root_frame_write(0u, static_referent);
    node_set_mark(static_referent, 42u);
    deal_static_root_register(&static_slot);
    static_slot = static_referent;
    deal_root_frame_write(0u, NULL);
    deal_gc_collect_full();
    read_statistics(&after);
    check(after.object_count == before.object_count + 1u
              && node_mark(static_referent) == 42u,
          "a written static root slot keeps its referent alive");
    static_slot = NULL;
    deal_static_root_unregister(&static_slot);
    deal_gc_collect_full();
    read_statistics(&after);
    check(after.object_count == before.object_count,
          "an emptied and unregistered static slot roots nothing");
    check(deal_static_root_count() == 0u,
          "the static root table is empty again");
    check(deal_root_frame_depth() == 1u && deal_root_scope_depth() == 0u,
          "the root-source case restores the root depth");
    deal_root_frame_leave();
}

/* =========================================================================
 * 7. Crossing-scope lifetime and root-state restoration (M5)
 * ========================================================================= */
static void check_crossing_scope(void) {
    deal_gc_statistics_record before;
    deal_gc_statistics_record after;
    void *resolved = NULL;

    deal_root_frame_enter(2u);
    deal_gc_collect_full();
    read_statistics(&before);
    check(before.object_count == 0u,
          "the crossing case starts from an empty heap");
    check(deal_value_crossing_active() == NULL,
          "no crossing scope is active before the case");

    deal_value_crossing *crossing = deal_value_crossing_begin();
    void *node = deal_alloc(TEST_LAYOUT_NODE, TEST_NODE_BYTES);
    deal_root_frame_write(0u, node);
    node_set_mark(node, 51u);
    deal_value carrier;
    check(deal_value_crossing_issue(crossing, node, &carrier) == DEAL_OK,
          "the crossing issues one view carrier");
    deal_root_frame_write(0u, NULL);
    (void)deal_alloc(TEST_LAYOUT_BULK, TEST_BULK_BYTES);

    deal_gc_collect_full();
    check(node_mark(node) == 51u,
          "the crossing value table roots its referent for the crossing");

    check(deal_value_crossing_resolve(crossing, carrier, &resolved) == DEAL_OK
              && resolved == node,
          "the rooted carrier still resolves to the same object");
    deal_root_frame_write(0u, NULL);
    deal_value_crossing_end(crossing);
    check(deal_value_crossing_active() == NULL
              && deal_value_crossing_count() == 0u,
          "the finished crossing leaves no open scope");
    deal_gc_collect_full();
    read_statistics(&after);
    check(after.object_count == before.object_count
              && after.live_bytes == before.live_bytes,
          "a finished crossing leaves no root source behind");
    check(after.swept_objects == before.swept_objects + 2u,
          "the crossing referent and the dropped pressure object are reclaimed");
    check(deal_root_frame_depth() == 1u && deal_root_scope_depth() == 0u,
          "the crossing case restores the root depth");
    deal_root_frame_leave();
}

static void check_root_state_restoration(void) {
    uint32_t outer_depth = 0u;

    deal_root_frame_enter(1u);
    outer_depth = deal_root_frame_depth();
    check(outer_depth == 1u, "the restoration case enters one frame");

    /* A caught Error inside a loop, with nested scoped roots: every exit path
     * restores its caller's depth, and repeated cycles do not grow the stack. */
    for (int index = 0; index < 40; index += 1) {
        deal_root_frame_enter(2u);
        deal_error *inner = deal_error_create("E9002", "caught", NULL);
        deal_root_frame_write(0u, inner);
        deal_root_scope_push();
        uint32_t slot = deal_root_scope_hold(inner);
        check(slot == 0u, "the scoped root takes the first slot");
        deal_root_scope_push();
        (void)deal_root_scope_hold(inner);
        deal_root_scope_pop();
        deal_root_scope_release(slot);
        deal_root_scope_pop();
        check(deal_root_frame_depth() == outer_depth + 1u,
              "the caught-Error frame depth is the handler's depth");
        check(deal_root_scope_depth() == 0u,
              "the caught-Error block leaves no scope open");
        deal_root_frame_leave();
        check(deal_root_frame_depth() == outer_depth,
              "the repeated caught-Error loop does not grow the root stack");
    }
    check(deal_root_scope_depth() == 0u,
          "the restoration case leaves no scope open");
    deal_root_frame_leave();
    check(deal_root_frame_depth() == 0u,
          "the restoration case restores the root depth");
}

/* =========================================================================
 * 8. Queued completion frames across allocation and collection (R1, M5)
 * ========================================================================= */
static void frame_record_init(test_frame_record *record, uint32_t token) {
    record->token = token;
    for (int index = 0; index < 27; index += 1) {
        record->text[index] =
            (char)('a' + (int)((token + (uint32_t)index) % 26U));
    }
    record->text[27] = 0;
}

static void frame_record_expected(uint32_t token, char *out) {
    for (int index = 0; index < 27; index += 1) {
        out[index] = (char)('a' + (int)((token + (uint32_t)index) % 26U));
    }
    out[27] = 0;
}

static int frame_producer(void *argument) {
    uint32_t base = *(const uint32_t *)argument;
    for (uint32_t index = 0; index < TEST_FRAMES_PER_PRODUCER; index += 1) {
        uint32_t token = base * TEST_FRAMES_PER_PRODUCER + index;
        test_frame_record source;
        frame_record_init(&source, token);
        deal_completion_frame *frame = deal_platform_frame_alloc();
        unsigned char *payload =
            deal_platform_frame_payload(frame, sizeof(source));
        memcpy(payload, &source, sizeof(source));
        deal_platform_post(frame);
        /* The posting caller owns its input until post returns; reusing it must
         * not affect the copied frame bytes. */
        memset(&source, 0, sizeof(source));
    }
    return 0;
}

static void check_queued_frames_under_collection(void) {
    deal_gc_statistics_record before;
    deal_gc_statistics_record after;
    thrd_t producers[TEST_PRODUCER_COUNT];
    uint32_t bases[TEST_PRODUCER_COUNT];
    int seen[TEST_PRODUCER_COUNT * TEST_FRAMES_PER_PRODUCER];
    size_t delivered = 0u;

    memset(seen, 0, sizeof(seen));
    deal_root_frame_enter(2u);
    deal_gc_collect_full();
    read_statistics(&before);
    check(before.object_count == 0u,
          "the completion case starts from an empty heap");

    for (int index = 0; index < TEST_PRODUCER_COUNT; index += 1) {
        bases[index] = (uint32_t)index;
        check(thrd_create(&producers[index], frame_producer, &bases[index])
                  == thrd_success,
              "the completion case starts a producer thread");
    }

    /* The mutator keeps allocating and collecting while the producers post:
     * a queued frame roots no managed object and touches no managed state. */
    for (int index = 0; index < 32; index += 1) {
        (void)deal_alloc(TEST_LAYOUT_BULK, TEST_BULK_BYTES);
        if (index % 8 == 0) {
            deal_gc_collect_full();
        }
    }
    for (int index = 0; index < TEST_PRODUCER_COUNT; index += 1) {
        int status = 0;
        check(thrd_join(producers[index], &status) == thrd_success
                  && status == 0,
              "the producer thread finishes its posts");
    }

    /* The mutator's own dropped allocations are reclaimed first, so the next
     * collection isolates the queued frames. */
    deal_gc_collect_full();
    read_statistics(&before);
    deal_gc_collect_full();
    read_statistics(&after);
    check(after.object_count == before.object_count,
          "a collection with frames queued reclaims nothing the frames own");
    check(after.live_bytes == before.live_bytes,
          "a queued frame roots no managed byte");

    for (;;) {
        deal_completion_frame *frame = deal_platform_take();
        if (frame == NULL) {
            break;
        }
        size_t size = deal_platform_frame_payload_size(frame);
        check(size == sizeof(test_frame_record),
              "the taken frame keeps its copied payload size");
        if (size == sizeof(test_frame_record)) {
            test_frame_record record;
            memcpy(&record, deal_platform_frame_bytes(frame), sizeof(record));
            char expected[28];
            frame_record_expected(record.token, expected);
            check(strcmp(record.text, expected) == 0,
                  "the taken frame keeps its copied payload bytes");
            check(record.token
                      < (uint32_t)(TEST_PRODUCER_COUNT
                          * TEST_FRAMES_PER_PRODUCER),
                  "the taken frame carries a runtime-allocated token");
            if (record.token
                    < (uint32_t)(TEST_PRODUCER_COUNT
                        * TEST_FRAMES_PER_PRODUCER)) {
                check(seen[record.token] == 0,
                      "every frame is delivered exactly once");
                seen[record.token] = 1;
            }
            if (delivered == 0u) {
                /* The delivered bytes materialize one managed value on the
                 * mutator; it must survive the next collection. */
                deal_string *text =
                    deal_text_new(record.text, strlen(record.text));
                deal_root_frame_write(0u, text);
                deal_gc_collect_full();
                check(deal_text_byte_length(text) == strlen(record.text),
                      "the materialized completion value survives a collection");
                check(memcmp(deal_text_bytes(text), record.text,
                             strlen(record.text)) == 0,
                      "the materialized completion value keeps its bytes");
                deal_root_frame_write(0u, NULL);
            }
        }
        delivered += 1u;
        deal_platform_frame_free(frame);
    }
    check(delivered
              == (size_t)(TEST_PRODUCER_COUNT * TEST_FRAMES_PER_PRODUCER),
          "every posted frame is delivered to the mutator");
    for (size_t index = 0;
            index < (size_t)(TEST_PRODUCER_COUNT * TEST_FRAMES_PER_PRODUCER);
            index += 1) {
        check(seen[index] == 1, "every posted token is delivered exactly once");
    }

    deal_root_frame_write(0u, NULL);
    deal_root_frame_write(1u, NULL);
    deal_gc_collect_full();
    read_statistics(&after);
    check(after.object_count == 0u && after.live_bytes == 0u,
          "the completion case leaves no managed object behind");
    check(deal_root_frame_depth() == 1u && deal_root_scope_depth() == 0u,
          "the completion case restores the root depth");
    deal_root_frame_leave();
}

/* =========================================================================
 * 9. Allocation-footprint pressure: zero-payload objects collect (M8, M7)
 * ========================================================================= */

/*
 * The growth policy charges the allocation footprint — the header and the
 * payload capacity of every object — not the payload alone. A program that
 * allocates objects with no payload still consumes headers and arena storage,
 * so it must reach the collection trigger exactly like one that allocates
 * payload bytes. The pressure below allocates zero-payload objects only, and the
 * statistics keep their payload accounting: `allocated_bytes` must not move
 * while collections advance and every object is swept.
 */
#define TEST_EMPTY_PRESSURE 100000

static void check_footprint_pressure(void) {
    deal_gc_statistics_record before;
    deal_gc_statistics_record pressure;
    deal_gc_statistics_record after;

    deal_root_frame_enter(1u);
    deal_gc_collect_full();
    read_statistics(&before);
    check(before.object_count == 0u && before.live_bytes == 0u,
          "the footprint-pressure case starts from an empty heap");

    void *last_empty = NULL;
    for (int index = 0; index < TEST_EMPTY_PRESSURE; index += 1) {
        last_empty = deal_alloc(TEST_LAYOUT_EMPTY, 0u);
    }
    check(deal_object_size(last_empty) == 0u,
          "a zero-payload allocation reports a zero payload size");
    read_statistics(&pressure);

    check(pressure.allocated_bytes == before.allocated_bytes,
          "a zero-payload allocation charges no payload byte");
    check(pressure.collections > before.collections,
          "zero-payload allocations still charge the allocation footprint and "
          "reach the collection trigger");
    if (stress_mode == 0) {
        check(pressure.object_count <= (uint64_t)TEST_EMPTY_PRESSURE,
              "zero-payload pressure keeps at most its own objects live");
    }

    drain_safepoints();
    deal_gc_collect_full();
    read_statistics(&after);
    check(after.object_count == before.object_count
              && after.live_bytes == before.live_bytes,
          "the zero-payload pressure leaves no live object");
    check(after.swept_objects == before.swept_objects
              + (uint64_t)TEST_EMPTY_PRESSURE,
          "the sweep reclaims every zero-payload object");
    check(deal_root_frame_depth() == 1u && deal_root_scope_depth() == 0u,
          "the footprint-pressure case restores the root depth");
    deal_root_frame_leave();
}

/* =========================================================================
 * 10. Bounded marking: resumable tracing by references, not objects (M3)
 * ========================================================================= */

/*
 * A wide driver-owned layout: its payload holds `count` managed reference slots
 * outside its fixed map, so its registered resumable walk stands in for the
 * array's element walk. The counter makes the collector's per-safepoint work
 * observable: one slice may visit at most the published reference budget.
 */
typedef struct {
    size_t count;
    void *slots[];
} test_wide;

#define TEST_WIDE_SLOTS 20000

static size_t wide_slice_visits;
static size_t wide_slice_total;
static int wide_slice_overruns;

static size_t test_wide_trace(const void *object, size_t position,
                              size_t *budget, deal_reference_visit visit,
                              void *context) {
    const test_wide *wide = (const test_wide *)object;
    while (position < wide->count && *budget > 0u) {
        void *referent = wide->slots[position];
        position += 1u;
        *budget -= 1u;
        wide_slice_visits += 1u;
        wide_slice_total += 1u;
        if (referent != NULL && referent != deal_null_singleton
                && referent != deal_undefined_singleton) {
            visit(referent, context);
        }
    }
    return position >= wide->count ? DEAL_TRACE_DONE : position;
}

static void *wide_slot_at(const void *wide, size_t index) {
    return ((const test_wide *)wide)->slots[index];
}

static uint64_t wide_slot_mark(const void *wide, size_t index) {
    return node_mark(wide_slot_at(wide, index));
}

/*
 * The pause bound of one cycle (M3). The wide object's walk spans far more
 * references than one slice allows, so work must be spread across slices:
 *
 *   - no single safepoint may make more than DEAL_GC_MARK_BUDGET_REFERENCES
 *     reference visits, measured through the registered walk itself;
 *   - the walk must be resumed where the last slice stopped, so one complete
 *     cycle visits each of the wide object's slots exactly once (a walk that
 *     restarts from position 0 visits more, and one that reports completion
 *     early visits fewer);
 *   - the cycle therefore needs at least `slots / budget` safepoints, and every
 *     one of the wide object's referents survives it with its payload.
 */
static void check_bounded_marking_slices(void) {
    deal_gc_statistics_record before;
    deal_gc_statistics_record after;
    size_t steps = 0u;
    int completed = 0;

    deal_root_frame_enter(2u);
    deal_gc_collect_full();
    read_statistics(&before);
    check(before.object_count == 0u && before.live_bytes == 0u,
          "the bounded-marking case starts from an empty heap");

    deal_layout_trace_register(TEST_LAYOUT_WIDE, test_wide_trace);
    void *wide = deal_alloc(TEST_LAYOUT_WIDE,
                            sizeof(test_wide)
                                + (size_t)TEST_WIDE_SLOTS * sizeof(void *));
    deal_root_frame_write(0u, wide);
    /* Cover the null-slot walk once explicitly, rather than repeat unused
     * capacity scans at every stress allocation during graph construction. */
    ((test_wide *)wide)->count = TEST_WIDE_SLOTS;
    deal_gc_collect_full(); /* Finish any allocation-triggered cycle first. */
    size_t empty_visits = wide_slice_total;
    deal_gc_collect_full();
    check(wide_slice_total - empty_visits == (size_t)TEST_WIDE_SLOTS,
          "the empty wide graph traces every null slot");
    /* Every stress allocation still traces all initialized live children.
     * The measured cycle below retains the complete 20,000-reference graph. */
    ((test_wide *)wide)->count = 0u;
    for (size_t index = 0; index < (size_t)TEST_WIDE_SLOTS; index += 1u) {
        void *child = deal_alloc(TEST_LAYOUT_NODE, TEST_NODE_BYTES);
        node_set_mark(child, (uint64_t)index + 1u);
        node_set_link(child, NULL);
        deal_root_frame_write(1u, child);
        deal_store_barrier(wide, child);
        ((test_wide *)wide)->slots[index] = child;
        ((test_wide *)wide)->count = index + 1u;
        deal_root_frame_write(1u, NULL);
    }
    check(((test_wide *)wide)->count == (size_t)TEST_WIDE_SLOTS,
          "the measured wide graph contains every initialized reference");
    /* Normalize: every object of the wide graph is marked now, so the cycle
     * observed below must trace the whole walk again from position 0. */
    deal_gc_collect_full();
    read_statistics(&before);

    wide_slice_visits = 0u;
    wide_slice_total = 0u;
    wide_slice_overruns = 0;
    while (completed == 0 && steps < (size_t)TEST_SAFEPOINT_DRAIN * 64u) {
        (void)deal_alloc(TEST_LAYOUT_BULK, TEST_BULK_BYTES);
        wide_slice_visits = 0u;
        deal_safepoint();
        read_statistics(&after);
        if (stress_mode == 0
                && wide_slice_visits > (size_t)DEAL_GC_MARK_BUDGET_REFERENCES) {
            wide_slice_overruns += 1;
        }
        if (after.collections > before.collections) {
            completed = 1;
        }
        steps += 1u;
    }
    check(completed != 0,
          "the wide object's cycle completes under incremental safepoints");
    if (stress_mode == 0) {
        check(wide_slice_overruns == 0,
              "no safepoint visits more than the published reference budget");
        check(wide_slice_total == (size_t)TEST_WIDE_SLOTS,
              "the resumable walk visits every slot exactly once, so each slice "
              "resumes where the last one stopped");
        check(steps >= (size_t)TEST_WIDE_SLOTS
                          / (size_t)DEAL_GC_MARK_BUDGET_REFERENCES,
              "the wide object is traced across at least one slice per budget");
    }

    deal_gc_collect_full();
    read_statistics(&after);
    for (size_t index = 0; index < (size_t)TEST_WIDE_SLOTS; index += 1u) {
        if (wide_slot_at(wide, index) == NULL
                || wide_slot_mark(wide, index) != (uint64_t)index + 1u) {
            check(0, "every wide referent survives the sliced marking");
            break;
        }
    }
    check(after.object_count == (uint64_t)TEST_WIDE_SLOTS + 1u,
          "the wide graph is exactly the wide object and its referents");

    deal_root_frame_write(0u, NULL);
    deal_gc_collect_full();
    read_statistics(&after);
    check(after.object_count == 0u && after.live_bytes == 0u,
          "the wide graph is reclaimed once its root is dropped");
    check(deal_root_frame_depth() == 1u && deal_root_scope_depth() == 0u,
          "the bounded-marking case restores the root depth");
    deal_root_frame_leave();
}

/* =========================================================================
 * 10b. The array walk itself is resumable (M1, M3)
 * ========================================================================= */

/*
 * The container group's element walk is the runtime's one large reference walk:
 * an array of reference-kind elements is traced through
 * `deal_container_array_trace` at one reference slot per unit of the safepoint
 * budget. The array below has many reference slots but only two referents, so
 * the sweep's per-object work stays tiny and the marking work is what the
 * completion time measures: a walk that scans the whole element storage inside
 * one object finishes the cycle in a handful of safepoints, while a walk bounded
 * by the reference budget needs at least one safepoint per budget of slots.
 *
 * The two halves of the array point to two different referents, and the walk
 * reaches the second half only by resuming where the previous slice stopped: a
 * walk that restarted from slot 0 would leave the second referent unmarked, and
 * its reclaim is then observable in the payload check and the live-object count.
 */
#define TEST_ARRAY_SLOTS 8000

static void check_container_array_trace_is_sliced(void) {
    deal_gc_statistics_record before;
    deal_gc_statistics_record after;
    size_t steps = 0u;
    int completed = 0;

    deal_root_frame_enter(3u);
    deal_gc_collect_full();
    read_statistics(&before);
    check(before.object_count == 0u && before.live_bytes == 0u,
          "the array-slice case starts from an empty heap");

    deal_array *references = deal_container_array_new(
        DEAL_LAYOUT_ELEMENT_REFERENCE, TEST_ARRAY_SLOTS);
    deal_root_frame_write(0u, references);
    void *first_half = deal_alloc(TEST_LAYOUT_NODE, TEST_NODE_BYTES);
    deal_root_frame_write(1u, first_half);
    node_set_mark(first_half, 21u);
    node_set_link(first_half, NULL);
    void *second_half = deal_alloc(TEST_LAYOUT_NODE, TEST_NODE_BYTES);
    deal_root_frame_write(2u, second_half);
    node_set_mark(second_half, 22u);
    node_set_link(second_half, NULL);
    for (size_t index = 0; index < (size_t)TEST_ARRAY_SLOTS; index += 1u) {
        void *element = index < (size_t)TEST_ARRAY_SLOTS / 2u
            ? first_half : second_half;
        deal_container_array_store(references, index, &element);
    }
    deal_root_frame_write(1u, NULL);
    deal_root_frame_write(2u, NULL);
    deal_gc_collect_full();
    read_statistics(&before);

    /* Cross the growth policy's trigger with one dropped allocation whose own
     * safepoint ran before it was charged, so the explicit safepoint below
     * starts the cycle and every counted step belongs to it. */
    for (int index = 0; index < TEST_WINDOW_PRESSURE; index += 1) {
        (void)deal_alloc(TEST_LAYOUT_BULK, TEST_BULK_BYTES);
    }
    (void)deal_alloc(TEST_LAYOUT_BULK, (size_t)TEST_WINDOW_TRIGGER);
    deal_safepoint();
    steps = 1u;
    while (completed == 0 && steps < (size_t)TEST_SAFEPOINT_DRAIN * 16u) {
        deal_safepoint();
        read_statistics(&after);
        if (after.collections > before.collections) {
            completed = 1;
        }
        steps += 1u;
    }
    check(completed != 0,
          "the reference array's cycle completes under incremental safepoints");
    if (stress_mode == 0) {
        check(steps >= (size_t)TEST_ARRAY_SLOTS
                          / (size_t)DEAL_GC_MARK_BUDGET_REFERENCES,
              "the element walk is spread over at least one safepoint per "
              "reference budget instead of scanning the whole array in one");
    }

    size_t wrong = 0u;
    for (size_t index = 0; index < (size_t)TEST_ARRAY_SLOTS; index += 1u) {
        void *element =
            *(void **)deal_container_array_element(references, index);
        uint64_t expected = index < (size_t)TEST_ARRAY_SLOTS / 2u ? 21u : 22u;
        if (element == NULL || node_mark(element) != expected) {
            wrong += 1u;
        }
    }
    check(wrong == 0u,
          "every element of the sliced walk survives with its payload, the "
          "second half reached only by resuming the walk");
    read_statistics(&after);
    check(after.object_count == 4u,
          "the reference array keeps exactly the array, its block and its two "
          "referents");

    deal_root_frame_write(0u, NULL);
    deal_gc_collect_full();
    read_statistics(&after);
    check(after.object_count == 0u && after.live_bytes == 0u,
          "the reference array is reclaimed once its root is dropped");
    check(deal_root_frame_depth() == 1u && deal_root_scope_depth() == 0u,
          "the array-slice case restores the root depth");
    deal_root_frame_leave();
}

/* =========================================================================
 * 11. Capacity-aware reuse and arena release (M1, M8)
 * ========================================================================= */

/* The non-monotone request sizes of the capacity case: all three fit the slot
 * the first one carves, and the last one is larger than the second. */
#define TEST_CAPACITY_FIRST 100000u
#define TEST_CAPACITY_SECOND 92000u
#define TEST_CAPACITY_THIRD 98000u

/*
 * A reused slot keeps the capacity it was carved with. The first allocation
 * carves one arena of its own, which stays the current bump arena, so its slot
 * stays in the free list across the collection that reclaims the object; the
 * second request reuses it and the third — *larger* than the second but inside
 * the slot's original capacity — must reuse it again. A slot that recorded the
 * request size instead of its capacity cannot answer the third request and
 * carves new storage.
 */
static void check_capacity_reuse(void) {
    deal_gc_statistics_record before;
    deal_gc_statistics_record after;

    deal_root_frame_enter(1u);
    deal_gc_collect_full();
    read_statistics(&before);
    check(before.object_count == 0u && before.live_bytes == 0u,
          "the capacity case starts from an empty heap");

    void *first = deal_alloc(TEST_LAYOUT_BULK, TEST_CAPACITY_FIRST);
    deal_root_frame_write(0u, first);
    check(deal_object_size(first) == TEST_CAPACITY_FIRST,
          "the first request reports its own payload size");
    deal_root_frame_write(0u, NULL);
    deal_gc_collect_full();

    void *second = deal_alloc(TEST_LAYOUT_BULK, TEST_CAPACITY_SECOND);
    deal_root_frame_write(0u, second);
    check(second == first,
          "a freed larger slot serves a smaller request");
    check(deal_object_size(second) == TEST_CAPACITY_SECOND,
          "the reused slot reports the smaller request's payload size");
    deal_root_frame_write(0u, NULL);
    deal_gc_collect_full();

    void *third = deal_alloc(TEST_LAYOUT_BULK, TEST_CAPACITY_THIRD);
    deal_root_frame_write(0u, third);
    check(third == first,
          "the reused slot keeps its original capacity for a larger request");
    check(deal_object_size(third) == TEST_CAPACITY_THIRD,
          "the reused slot reports the larger request's payload size");
    deal_root_frame_write(0u, NULL);
    deal_gc_collect_full();
    read_statistics(&after);
    check(after.object_count == before.object_count
              && after.live_bytes == before.live_bytes,
          "the capacity case leaves no live object");
    check(after.swept_objects == before.swept_objects + 3u,
          "the sweep reclaims exactly the three capacity-case objects");
    check(deal_root_frame_depth() == 1u && deal_root_scope_depth() == 0u,
          "the capacity case restores the root depth");
    deal_root_frame_leave();
}

/* The churn shape of the reclamation case: sizes across the exact classes, the
 * geometric classes and the large single-object arenas. */
#define TEST_CHURN_ROUNDS 8
#define TEST_CHURN_OBJECTS 160
#define TEST_CHURN_BASE 2000u
#define TEST_CHURN_STEP 1237u

/* One resident-set slack: the arena of the current bump block plus the
 * driver's own growth stay well inside it, while retained churn arenas would
 * exceed it many times over. */
#define TEST_CHURN_SLACK (4u * 1024u * 1024u)

/*
 * Reclamation beyond the logical counters (M1, M8): a round allocates and drops
 * a churn set spanning every size class, then collects. The counters must be
 * exact after every round — which they were before this change too — and the
 * process must give the arena pages back, so the resident set after the churn
 * stays within one small slack of the baseline instead of growing with every
 * round. Without arena release the same loop retains every page it ever carved
 * and the resident set grows by the whole churn volume.
 */
static void check_arena_release_under_churn(void) {
    deal_gc_statistics_record before;
    deal_gc_statistics_record after;
    size_t baseline = 0u;
    size_t retained = 0u;

    deal_root_frame_enter(1u);
    deal_gc_collect_full();
    read_statistics(&before);
    check(before.object_count == 0u && before.live_bytes == 0u,
          "the churn case starts from an empty heap");
    baseline = resident_bytes();

    for (int round = 0; round < TEST_CHURN_ROUNDS; round += 1) {
        for (int index = 0; index < TEST_CHURN_OBJECTS; index += 1) {
            size_t size = (size_t)TEST_CHURN_BASE
                + (size_t)index * (size_t)TEST_CHURN_STEP;
            (void)deal_alloc(TEST_LAYOUT_BULK, size);
        }
        deal_gc_collect_full();
        read_statistics(&after);
        check(after.object_count == before.object_count
                  && after.live_bytes == before.live_bytes,
              "a churn round leaves no live object behind");
    }
    uint64_t churn_payload = (uint64_t)TEST_CHURN_ROUNDS
        * ((uint64_t)TEST_CHURN_OBJECTS * (uint64_t)TEST_CHURN_BASE
           + (uint64_t)TEST_CHURN_STEP
               * ((uint64_t)TEST_CHURN_OBJECTS
                   * (uint64_t)(TEST_CHURN_OBJECTS - 1) / 2u));
    check(after.allocated_bytes == before.allocated_bytes + churn_payload,
          "the payload accounting covers every churn object");

    retained = resident_bytes();
    if (baseline != 0u && retained != 0u) {
        check(retained <= baseline + (size_t)TEST_CHURN_SLACK,
              "churn returns the reclaimed arena pages instead of retaining "
              "them");
    }
    check(deal_root_frame_depth() == 1u && deal_root_scope_depth() == 0u,
          "the churn case restores the root depth");
    deal_root_frame_leave();
}

/* =========================================================================
 * 12. Randomized incremental mutation and deletion (M1, M2, M3)
 * ========================================================================= */

#define TEST_RANDOM_SLOTS 96
#define TEST_RANDOM_ROUNDS 600
#define TEST_RANDOM_OBJECTS 512

static uint64_t random_state = 0x9E3779B97F4A7C15ull;

static uint64_t next_random(void) {
    random_state ^= random_state << 13;
    random_state ^= random_state >> 7;
    random_state ^= random_state << 17;
    return random_state;
}

/* The number of distinct objects reachable from the slot model by following the
 * nodes' own link slots. Every entry is a live pointer by construction: the case
 * only ever links to objects the slots reach. */
static size_t reachable_node_count(const void *const *slots,
                                   size_t slot_count, const void *seen[],
                                   size_t seen_capacity) {
    const void *stack[TEST_RANDOM_OBJECTS];
    size_t stack_count = 0u;
    size_t seen_count = 0u;
    for (size_t index = 0; index < slot_count; index += 1u) {
        if (slots[index] != NULL && stack_count < TEST_RANDOM_OBJECTS) {
            stack[stack_count] = slots[index];
            stack_count += 1u;
        }
    }
    while (stack_count > 0u) {
        stack_count -= 1u;
        const void *node = stack[stack_count];
        size_t known = 0u;
        while (known < seen_count && seen[known] != node) {
            known += 1u;
        }
        if (known < seen_count) {
            continue;
        }
        if (seen_count == seen_capacity) {
            break;
        }
        seen[seen_count] = node;
        seen_count += 1u;
        const void *link = node_link(node);
        if (link != NULL && stack_count < TEST_RANDOM_OBJECTS) {
            stack[stack_count] = link;
            stack_count += 1u;
        }
    }
    return seen_count;
}

/* The mark of a node is its own address, so a reclaimed and reused slot can
 * never masquerade as the node the model reaches. */
static uint64_t node_identity_mark(const void *node) {
    return (uint64_t)(uintptr_t)node;
}

/*
 * A randomized incremental-GC workload (M1, M2, M3): a rooted array of managed
 * references plus per-node links is mutated, deleted from and grown while
 * safepoints and complete collections interleave. After every complete
 * collection the model's reachable set must equal the collector's live object
 * count (the array and its element block are the only other live objects), and
 * every reachable node must still carry its own payload. A collector that
 * reclaims a reachable object fails the count; a slot reused while it still
 * holds a reachable reference fails the payload; unbarriered stores lose
 * referents and fail both. The whole case runs under AddressSanitizer in the
 * suite's sanitizer leg, where a stale read into a reclaimed arena slot is a
 * reported use-after-poison rather than a silent read.
 */
static void check_randomized_mutation(void) {
    deal_gc_statistics_record before;
    deal_gc_statistics_record after;
    void *slots[TEST_RANDOM_SLOTS];
    const void *seen[TEST_RANDOM_OBJECTS];
    size_t created = 0u;
    int failures_here = 0;

    deal_root_frame_enter(3u);
    deal_gc_collect_full();
    read_statistics(&before);
    check(before.object_count == 0u && before.live_bytes == 0u,
          "the randomized case starts from an empty heap");

    deal_array *references = deal_container_array_new(
        DEAL_LAYOUT_ELEMENT_REFERENCE, TEST_RANDOM_SLOTS);
    deal_root_frame_write(0u, references);
    for (size_t index = 0; index < (size_t)TEST_RANDOM_SLOTS; index += 1u) {
        void *node = deal_alloc(TEST_LAYOUT_NODE, TEST_NODE_BYTES);
        node_set_mark(node, node_identity_mark(node));
        node_set_link(node, NULL);
        deal_root_frame_write(1u, node);
        deal_container_array_store(references, index, &node);
        slots[index] = node;
        created += 1u;
        deal_root_frame_write(1u, NULL);
    }

    for (int round = 0; round < TEST_RANDOM_ROUNDS; round += 1u) {
        uint64_t choice = next_random();
        size_t first = (size_t)(choice % (uint64_t)TEST_RANDOM_SLOTS);
        size_t second = (size_t)((choice >> 16) % (uint64_t)TEST_RANDOM_SLOTS);
        switch (choice % 6u) {
        case 0u: {
            void *source = slots[first];
            if (source != NULL) {
                node_set_link(source, slots[second]);
            }
            break;
        }
        case 1u: {
            void *source = slots[first];
            if (source != NULL) {
                node_set_link(source, NULL);
            }
            break;
        }
        case 2u: {
            slots[first] = NULL;
            void *cleared = NULL;
            deal_container_array_store(references, first, &cleared);
            break;
        }
        case 3u: {
            if (created < (size_t)TEST_RANDOM_OBJECTS) {
                void *fresh = deal_alloc(TEST_LAYOUT_NODE, TEST_NODE_BYTES);
                node_set_mark(fresh, node_identity_mark(fresh));
                node_set_link(fresh, slots[second]);
                deal_root_frame_write(1u, fresh);
                deal_container_array_store(references, first, &fresh);
                slots[first] = fresh;
                created += 1u;
                deal_root_frame_write(1u, NULL);
            }
            break;
        }
        case 4u: {
            (void)deal_alloc(TEST_LAYOUT_BULK, TEST_BULK_BYTES);
            break;
        }
        default: {
            (void)deal_alloc(TEST_LAYOUT_NODE, TEST_NODE_BYTES);
            break;
        }
        }
        if (round % 5 == 0) {
            deal_safepoint();
        }
        if (round % 13 == 0) {
            deal_gc_collect_full();
            size_t reachable = reachable_node_count(
                (const void *const *)slots, TEST_RANDOM_SLOTS, seen,
                TEST_RANDOM_OBJECTS);
            read_statistics(&after);
            if (after.object_count != (uint64_t)reachable + 2u) {
                failures_here += 1;
            }
            for (size_t index = 0; index < (size_t)TEST_RANDOM_SLOTS;
                    index += 1u) {
                if (slots[index] == NULL) {
                    continue;
                }
                const void *node = slots[index];
                if (node_mark(node) != node_identity_mark(node)) {
                    failures_here += 1;
                    break;
                }
            }
            if (failures_here != 0) {
                break;
            }
        }
    }
    check(failures_here == 0,
          "the randomized workload keeps exactly its reachable objects alive "
          "with their payloads");

    for (size_t index = 0; index < (size_t)TEST_RANDOM_SLOTS; index += 1u) {
        slots[index] = NULL;
        void *cleared = NULL;
        deal_container_array_store(references, index, &cleared);
    }
    deal_root_frame_write(0u, NULL);
    deal_gc_collect_full();
    read_statistics(&after);
    check(after.object_count == 0u && after.live_bytes == 0u,
          "every randomized object is reclaimed once the roots are dropped");
    check(after.swept_objects > before.swept_objects,
          "the randomized workload reclaims the objects it drops");
    check(deal_root_frame_depth() == 1u && deal_root_scope_depth() == 0u,
          "the randomized case restores the root depth");
    deal_root_frame_leave();
}

/* =========================================================================
 * The driver entry
 * ========================================================================= */
int main(void) {
    const char *stress = deal_platform_environment("DEAL_GC_STRESS");
    stress_mode = stress != NULL && stress[0] == '1' && stress[1] == '\0';

    deal_layout_install(test_layouts,
                        sizeof(test_layouts) / sizeof(test_layouts[0]));
    check_statistics_and_live_collection();
    check_cycle_reclamation();
    check_live_cycle_survives();
    check_capacity_reuse();
    check_footprint_pressure();
    check_barrier_and_incremental_cycle();
    check_barrier_shades_a_white_referent();
    check_record_deletion_barrier();
    check_automatic_collection();
    check_allocation_mark_in_inflight_cycle();
    check_bounded_marking_slices();
    check_container_array_trace_is_sliced();
    check_arena_release_under_churn();
    check_randomized_mutation();
    check_scalar_and_nullish_replacement();
    check_error_rooting();
    check_cells_under_collection();
    check_registered_sources();
    check_crossing_scope();
    check_root_state_restoration();
    check_queued_frames_under_collection();
    printf(failures == 0 ? "gc collector ok\n" : "gc collector failed\n");
    return failures == 0 ? 0 : 1;
}

#elif DEAL_GC_CASE == 1
int main(void) {
    deal_gc_statistics(NULL);
    printf("unreachable\n");
    return 0;
}
#elif DEAL_GC_CASE == 2
int main(void) {
    deal_layout_trace_register(TEST_LAYOUT_NODE, NULL);
    printf("unreachable\n");
    return 0;
}
#elif DEAL_GC_CASE == 3
static size_t first_trace(const void *object, size_t position, size_t *budget,
                          deal_reference_visit visit, void *context) {
    (void)object;
    (void)position;
    (void)budget;
    (void)visit;
    (void)context;
    return DEAL_TRACE_DONE;
}

static size_t second_trace(const void *object, size_t position, size_t *budget,
                           deal_reference_visit visit, void *context) {
    (void)object;
    (void)position;
    (void)budget;
    (void)visit;
    (void)context;
    return DEAL_TRACE_DONE;
}

int main(void) {
    deal_layout_trace_register(TEST_LAYOUT_NODE, first_trace);
    deal_layout_trace_register(TEST_LAYOUT_NODE, second_trace);
    printf("unreachable\n");
    return 0;
}
#elif DEAL_GC_CASE == 4
static void nested_collection_source(deal_reference_visit visit,
                                     void *visit_context,
                                     void *source_context) {
    (void)visit;
    (void)visit_context;
    (void)source_context;
    deal_gc_collect_full();
}

int main(void) {
    deal_root_source_register(nested_collection_source, NULL);
    deal_gc_collect_full();
    printf("unreachable\n");
    return 0;
}
#elif DEAL_GC_CASE == 5
int main(void) {
    deal_layout_release_register(TEST_LAYOUT_NODE, NULL);
    printf("unreachable\n");
    return 0;
}
#elif DEAL_GC_CASE == 6
static void first_release(void *object) {
    (void)object;
}

static void second_release(void *object) {
    (void)object;
}

int main(void) {
    deal_layout_release_register(TEST_LAYOUT_NODE, first_release);
    deal_layout_release_register(TEST_LAYOUT_NODE, second_release);
    printf("unreachable\n");
    return 0;
}
#elif DEAL_GC_CASE == 7
int main(void) {
    static const deal_layout bulk_layout[] = {
        {TEST_LAYOUT_BULK, 0u, TEST_BULK_BYTES, NULL, 0u}
    };
    deal_layout_install(bulk_layout,
                        sizeof(bulk_layout) / sizeof(bulk_layout[0]));
    /* A request above the largest size class is the R10 resource fault before
     * any partial publication, never a truncated allocation. */
    (void)deal_alloc(TEST_LAYOUT_BULK, SIZE_MAX - 100u);
    printf("unreachable\n");
    return 0;
}
#else
#error "unknown DEAL_GC_CASE"
#endif
