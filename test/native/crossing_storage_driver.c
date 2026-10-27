/*
 * test/native/crossing_storage_driver.c — the R3/R11 crossing value table.
 *
 * A strict-C11 consumer of the published runtime: one crossing scope's
 * (slot, generation, referent) table with issuance, resolution, foreign
 * rejection, supplemental rooting and crossing-scoped release. Each carrier
 * names its referent unchanged; a carrier issued by a finished crossing or by a
 * different crossing answers `DEAL_STATUS_FOREIGN_VALUE` and is never
 * dereferenced; the crossing scope is a registered root source while it is
 * active and roots exactly its managed referents — a view of a nullish
 * singleton resolves its payload yet roots nothing, because the scan is
 * tag-aware (M5).
 *
 * `-DDEAL_CROSSING_CASE=<n>` selects one invariant-fault negative: resolving
 * without out-parameter storage, ending a non-active crossing and ending no
 * crossing. Crossing orchestration, carriers, handles and error tokens are the
 * host child's.
 */
#include "deal/native/deal_rt.h"
#include "deal/native/memory.h"
#include "deal/native/platform.h"
#include "deal/native/value.h"

#include <stdio.h>

#ifndef DEAL_CROSSING_CASE
#define DEAL_CROSSING_CASE 0
#endif

#if DEAL_CROSSING_CASE == 0

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

int main(void) {
    void *first = deal_cell_alloc(NULL);
    void *second = deal_cell_alloc(NULL);
    void *third = deal_cell_alloc(NULL);
    unsigned char carrier_storage[8] = { 0u };
    deal_value first_carrier = { 0u, 0u };
    deal_value second_carrier = { 0u, 0u };
    deal_value opaque_carrier = { 0u, 0u };
    deal_value issued_again = { 0u, 0u };
    deal_value stale = { 0u, 0u };
    void *out = NULL;
    deal_value_crossing *crossing;

    check(deal_value_crossing_active() == NULL, "no crossing is active at first");
    check(deal_value_crossing_count() == 0u, "no crossing has issued a carrier");

    crossing = deal_value_crossing_begin();
    check(deal_value_crossing_active() == crossing,
          "the begun crossing is the active one");
    check(deal_value_crossing_count() == 0u,
          "a new crossing has issued nothing");

    check(deal_value_crossing_issue(crossing, first, &first_carrier) == DEAL_OK,
          "a view carrier is issued");
    check(deal_value_crossing_resolve(crossing, first_carrier, &out) == DEAL_OK
              && out == first,
          "a view carrier resolves its managed referent");
    check(deal_value_crossing_issue(crossing, second, &second_carrier) == DEAL_OK,
          "a second view carrier is issued");
    check(deal_value_crossing_issue_opaque(crossing, carrier_storage,
                                           &opaque_carrier) == DEAL_OK,
          "an opaque carrier is issued");
    out = NULL;
    check(deal_value_crossing_resolve(crossing, opaque_carrier, &out) == DEAL_OK
              && out == (void *)carrier_storage,
          "an opaque carrier resolves its crossing-scoped payload");
    check(deal_value_crossing_root(crossing, third) == DEAL_OK,
          "a supplemental root is taken");
    check(deal_value_crossing_count() == 3u,
          "the crossing counts its issued carriers");

    /* foreign and stale carriers are rejected without a write. */
    out = (void *)carrier_storage;
    {
        deal_value beyond = { 3u, 1u };
        check(deal_value_crossing_resolve(crossing, beyond, &out)
                  == DEAL_STATUS_FOREIGN_VALUE,
              "an unknown slot is foreign");
        check(out == (void *)carrier_storage,
              "a foreign rejection writes nothing");
        stale.slot = first_carrier.slot;
        stale.generation = first_carrier.generation + 1u;
        check(deal_value_crossing_resolve(crossing, stale, &out)
                  == DEAL_STATUS_FOREIGN_VALUE,
              "a stale generation is foreign");
        stale.generation = first_carrier.generation;
        stale.slot = 4294967295u;
        check(deal_value_crossing_resolve(crossing, stale, &out)
                  == DEAL_STATUS_FOREIGN_VALUE,
              "a stale slot is foreign");
    }
    check(deal_value_crossing_issue(crossing, NULL, &issued_again)
              == DEAL_STATUS_KIND,
          "a null view referent is rejected by kind");
    check(deal_value_crossing_root(crossing, NULL) == DEAL_STATUS_KIND,
          "a null supplemental root is rejected by kind");
    check(deal_value_crossing_count() == 3u,
          "a rejected issuance publishes no entry");

    /* a view of a nullish singleton resolves its payload and roots nothing:
     * the crossing scan never presents a singleton as a heap object (M5). */
    {
        deal_value null_carrier = { 0u, 0u };
        deal_value undefined_carrier = { 0u, 0u };
        check(deal_value_crossing_issue(crossing, deal_null_singleton,
                                        &null_carrier) == DEAL_OK,
              "a null-singleton view is issued");
        check(deal_value_crossing_issue(crossing, deal_undefined_singleton,
                                        &undefined_carrier) == DEAL_OK,
              "an undefined-singleton view is issued");
        out = NULL;
        check(deal_value_crossing_resolve(crossing, null_carrier, &out)
                  == DEAL_OK && out == deal_null_singleton,
              "a null-singleton view resolves its payload unchanged");
    }

    /* the crossing scope is a root source while it is active (M5). */
    reset_collected();
    deal_root_sources_enumerate(collect_reference, NULL);
    check(collected_count == 3u,
          "the crossing roots exactly its managed referents");
    check(collected[0] == first && collected[1] == second && collected[2] == third,
          "the crossing roots its view referents and its supplemental root");
    check(deal_value_crossing_count() == 5u,
          "the crossing counts its carriers beside the singleton views");
    check(deal_root_frame_depth() == 0u && deal_root_scope_depth() == 0u,
          "the crossing leaves the frame and scoped-root depths restored");

    /* a nested crossing cannot resolve the outer crossing's carrier. */
    {
        deal_value_crossing *nested = deal_value_crossing_begin();
        deal_value nested_carrier = { 0u, 0u };
        check(deal_value_crossing_active() == nested,
              "the nested crossing is the active one");
        check(deal_value_crossing_count() == 0u,
              "the nested crossing has its own empty table");
        check(deal_value_crossing_issue(nested, first, &nested_carrier) == DEAL_OK,
              "the nested crossing issues its own carrier");
        check(deal_value_crossing_resolve(nested, first_carrier, &out)
                  == DEAL_STATUS_FOREIGN_VALUE,
              "an outer carrier is foreign to the nested crossing");
        check(deal_value_crossing_issue(crossing, first, &issued_again)
                  == DEAL_STATUS_OUT_OF_SCOPE,
              "a non-active crossing issues nothing");
        check(deal_value_crossing_root(crossing, first)
                  == DEAL_STATUS_OUT_OF_SCOPE,
              "a non-active crossing roots nothing");
        deal_value_crossing_end(nested);
        check(deal_value_crossing_active() == crossing,
              "ending the nested crossing restores the outer one");
        check(deal_value_crossing_resolve(crossing, nested_carrier, &out)
                  == DEAL_STATUS_FOREIGN_VALUE,
              "a finished nested carrier is foreign to the outer crossing");
        check(deal_value_crossing_resolve(crossing, first_carrier, &out) == DEAL_OK
                  && out == first,
              "the outer carrier resolves again after the nested crossing");
    }

    /* a carrier of a finished crossing is foreign and never dereferenced. */
    deal_value_crossing_end(crossing);
    check(deal_value_crossing_active() == NULL, "no crossing is active again");
    check(deal_value_crossing_count() == 0u, "no crossing counts carriers");
    check(deal_value_crossing_resolve(crossing, first_carrier, &out)
              == DEAL_STATUS_FOREIGN_VALUE,
          "a finished crossing's carrier is foreign");
    check(deal_value_crossing_issue(crossing, first, &issued_again)
              == DEAL_STATUS_OUT_OF_SCOPE,
          "a finished crossing issues nothing");
    reset_collected();
    deal_root_sources_enumerate(collect_reference, NULL);
    check(collected_count == 0u,
          "a finished crossing roots nothing");

    /* the next crossing reuses slot zero; the generation keeps it distinct. */
    {
        deal_value_crossing *later = deal_value_crossing_begin();
        deal_value later_carrier = { 0u, 0u };
        check(deal_value_crossing_issue(later, second, &later_carrier) == DEAL_OK,
              "the later crossing issues from slot zero");
        check(later_carrier.slot == first_carrier.slot,
              "the later crossing reuses the same slot index");
        check(later_carrier.generation != first_carrier.generation,
              "every issuance takes a fresh generation");
        check(deal_value_crossing_resolve(later, first_carrier, &out)
                  == DEAL_STATUS_FOREIGN_VALUE,
              "a stale carrier of a reused slot is foreign");
        check(deal_value_crossing_resolve(later, later_carrier, &out) == DEAL_OK
                  && out == second,
              "the later crossing resolves its own carrier");
        deal_value_crossing_end(later);
    }

    check(deal_root_frame_depth() == 0u && deal_root_scope_depth() == 0u,
          "every crossing restored the root depth");
    printf(failures == 0 ? "crossing storage ok\n" : "crossing storage failed\n");
    return failures == 0 ? 0 : 1;
}

#elif DEAL_CROSSING_CASE == 1
int main(void) {
    deal_value_crossing *crossing = deal_value_crossing_begin();
    void *first = deal_cell_alloc(NULL);
    deal_value carrier = { 0u, 0u };
    (void)deal_value_crossing_issue(crossing, first, &carrier);
    (void)deal_value_crossing_resolve(crossing, carrier, NULL);
    printf("unreachable\n");
    return 0;
}
#elif DEAL_CROSSING_CASE == 2
int main(void) {
    deal_value_crossing *outer = deal_value_crossing_begin();
    deal_value_crossing *inner = deal_value_crossing_begin();
    deal_value_crossing_end(outer);
    (void)inner;
    printf("unreachable\n");
    return 0;
}
#else
int main(void) {
    deal_value_crossing_end(NULL);
    printf("unreachable\n");
    return 0;
}
#endif
