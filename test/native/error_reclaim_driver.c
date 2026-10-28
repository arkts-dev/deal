/*
 * test/native/error_reclaim_driver.c — platform-storage reclamation of a
 * reclaimed Error (ISSUE-0906; `llvm-native-memory-management` M1/M5;
 * `llvm-native-runtime-value-model` R5, R9).
 *
 * An Error record owns three kinds of storage. The managed
 * `DEAL_LAYOUT_ERROR` object belongs to the collector; the copied code,
 * message and origin text, the optional expected and actual text, and the
 * frames array with one duplicated label per active call frame are platform
 * storage that only the record can reach. The sweep therefore runs the
 * layout's registered reclamation hook (`deal_layout_release_register`,
 * memory.h) while the payload is still intact, so reclaiming an unreachable
 * Error releases its whole footprint: leaving that storage behind would leak
 * every Error a program creates and drops, and would end in the R10 resource
 * fault instead of a reclamation.
 *
 * `deal_gc_statistics` counts managed payload only, so this fixture observes
 * the platform half directly. The product is linked with
 * `-Wl,--wrap=malloc -Wl,--wrap=free`, which redirects the runtime's only two
 * system-allocator calls — `deal_platform_allocate` and
 * `deal_platform_release` in `platform.c` — to the counting wrappers below.
 * No published runtime symbol is replaced, replaced entry or redefined layout
 * is introduced, and the published tree stays byte-identical; the wrappers see
 * exactly the platform storage the record owns, and the fixture fails when the
 * release hook does not run (the count never returns to its baseline) or runs
 * for a live record (the read-backs below are released storage).
 *
 * The acceptance is mode-uniform: every record created here is rooted until
 * the statement that drops it, so `DEAL_GC_STRESS=1` — which completes a cycle
 * at every safepoint — reclaims the same records and the fixture prints the
 * same single line in both modes.
 */
#include "deal/native/entry.h"
#include "deal/native/error.h"
#include "deal/native/memory.h"
#include "deal/native/platform.h"

#include <stdio.h>
#include <string.h>

/* =========================================================================
 * The platform-allocation counter (link-time interposition)
 * ========================================================================= */

static size_t platform_outstanding;

void *__real_malloc(size_t size);
void __real_free(void *memory);

void *__wrap_malloc(size_t size) {
    void *memory = __real_malloc(size);
    if (memory != NULL) {
        platform_outstanding += 1u;
    }
    return memory;
}

void __wrap_free(void *memory) {
    if (memory != NULL) {
        platform_outstanding -= 1u;
    }
    __real_free(memory);
}

static size_t platform_live(void) {
    return platform_outstanding;
}

/* =========================================================================
 * The Error shape under test
 * ========================================================================= */

enum {
    LIVE_ERRORS = 8,
    FRAME_LABELS = 2,
    ERROR_SLOTS = LIVE_ERRORS + 2
};

/*
 * The platform allocations one full-shape record owns: the code, the message,
 * the origin's source file, the expected and actual text, the frames array and
 * one duplicated label per active frame.
 */
#define FULL_ERROR_PLATFORM_ALLOCATIONS \
    (5u + 1u + (size_t)FRAME_LABELS)

/* The platform allocations one bare record owns: its code and its message. */
#define BARE_ERROR_PLATFORM_ALLOCATIONS 2u

static const char *const frame_labels[FRAME_LABELS] = {
    "error_reclaim#outer@F1",
    "error_reclaim#inner@F2"
};

static int failures;

static void check(int condition, const char *what) {
    if (!condition) {
        printf("FAIL: %s\n", what);
        failures += 1;
    }
}

/* One text read-back of a record; `field` names the field that answered
 * wrong, so a failure names the released or wrong member. */
static void check_text(int condition, const char *what, const char *field) {
    if (!condition) {
        printf("FAIL: %s (%s)\n", what, field);
        failures += 1;
    }
}

static void read_statistics(deal_gc_statistics_record *out) {
    deal_gc_statistics(out);
}

static const char *frame_label(uint32_t index) {
    return frame_labels[index];
}

/* One full-shape record: an origin, both optional text fields and one frame
 * label per active call frame. */
static deal_error *create_full_error(void) {
    const deal_origin origin = {
        "test/native/error_reclaim_driver.c", 11, 3
    };
    for (uint32_t index = 0; index < FRAME_LABELS; index += 1u) {
        deal_call_frame_push(frame_label(index));
    }
    deal_error *error = deal_error_create("E8010", "reclaim me", &origin);
    deal_error_set_expected(error, "expected text");
    deal_error_set_actual(error, "actual text");
    for (uint32_t index = 0; index < FRAME_LABELS; index += 1u) {
        deal_call_frame_pop();
    }
    return error;
}

/* Reads one record's whole text surface back: only a live record answers with
 * its own text, because a released record's fields are already freed. */
static void check_full_error_text(deal_error *error, const char *what) {
    const char *text = NULL;

    check_text(deal_error_code(error, &text) == 1
                   && strcmp(text, "E8010") == 0,
               what, "code");
    text = NULL;
    check_text(deal_error_message(error, &text) == 1
                   && strcmp(text, "reclaim me") == 0,
               what, "message");
    text = NULL;
    check_text(deal_error_source_file(error, &text) == 1
                   && strcmp(text, "test/native/error_reclaim_driver.c") == 0,
               what, "source file");
    text = NULL;
    check_text(deal_error_expected(error, &text) == 1
                   && strcmp(text, "expected text") == 0,
               what, "expected");
    text = NULL;
    check_text(deal_error_actual(error, &text) == 1
                   && strcmp(text, "actual text") == 0,
               what, "actual");
    int32_t line = 0;
    int32_t column = 0;
    check_text(deal_error_line(error, &line) == 1 && line == 11,
               what, "line");
    check_text(deal_error_column(error, &column) == 1 && column == 3,
               what, "column");
    int32_t frames = 0;
    check_text(deal_error_frame_count(error, &frames) == 1
                   && frames == (int32_t)FRAME_LABELS,
               what, "frame count");
    /* The snapshot is innermost-first, so the label pushed last is index 0. */
    for (uint32_t index = 0; index < FRAME_LABELS; index += 1u) {
        text = NULL;
        const char *expected = frame_label(FRAME_LABELS - 1u - index);
        check_text(deal_error_frame(error, (int32_t)index, &text) == 1
                       && strcmp(text, expected) == 0,
                   what, "frame label");
    }
}

/* =========================================================================
 * The cases
 * ========================================================================= */

int main(void) {
    deal_root_frame_enter(1u);

    /* Warm up: the first record registers the layout, its reclamation hook and
     * the collector's gray work list storage, so the baseline below is the
     * same number in both modes. */
    deal_error *warmup = create_full_error();
    deal_root_frame_write(0u, warmup);
    deal_gc_collect_full();
    deal_root_frame_write(0u, NULL);
    deal_gc_collect_full();
    deal_root_frame_leave();

    const size_t baseline = platform_live();

    /* -- 1. the live footprint of the Error graph ------------------------ */
    deal_root_frame_enter(ERROR_SLOTS);
    deal_gc_statistics_record before;
    deal_gc_statistics_record after;
    deal_gc_collect_full();
    read_statistics(&before);
    check(before.object_count == 0u && before.live_bytes == 0u,
          "the Error reclamation case starts from an empty managed heap");

    deal_error *errors[LIVE_ERRORS];
    for (uint32_t index = 0; index < (uint32_t)LIVE_ERRORS; index += 1u) {
        errors[index] = create_full_error();
        deal_root_frame_write(index, errors[index]);
    }
    deal_error *dropped = create_full_error();
    deal_root_frame_write(LIVE_ERRORS, dropped);
    deal_error *bare = deal_error_create("E0001", "bare text", NULL);
    deal_root_frame_write(LIVE_ERRORS + 1, bare);

    const size_t error_bytes = deal_object_size(errors[0]);
    check(deal_object_layout(errors[0]) == DEAL_LAYOUT_ERROR,
          "the Error record is one managed object of the Error layout");
    check(deal_object_size(dropped) == error_bytes,
          "every Error record has the same managed payload size");
    check(platform_live()
              == baseline
                  + (size_t)LIVE_ERRORS * FULL_ERROR_PLATFORM_ALLOCATIONS
                  + FULL_ERROR_PLATFORM_ALLOCATIONS
                  + BARE_ERROR_PLATFORM_ALLOCATIONS,
          "every live Error owns exactly its own platform storage: the copied "
          "code, message and origin text, the optional expected and actual "
          "text, the frames array and one duplicated label per frame");

    /* -- 2. a full collection of a fully live graph releases nothing ----- */
    deal_gc_collect_full();
    read_statistics(&after);
    check(after.object_count == before.object_count
              + (uint64_t)(LIVE_ERRORS + 2)
              && after.live_bytes
                  == before.live_bytes
                      + (uint64_t)(LIVE_ERRORS + 2) * (uint64_t)error_bytes,
          "every rooted Error record survives the full collection");
    check(platform_live()
              == baseline
                  + (size_t)LIVE_ERRORS * FULL_ERROR_PLATFORM_ALLOCATIONS
                  + FULL_ERROR_PLATFORM_ALLOCATIONS
                  + BARE_ERROR_PLATFORM_ALLOCATIONS,
          "a full collection that reclaims nothing releases no record's "
          "platform storage");
    check_full_error_text(errors[0],
                          "a surviving Error keeps its whole text surface");
    check_full_error_text(dropped,
                          "the record about to be dropped still owns its "
                          "text before reclamation");

    /* -- 3. reclaiming an Error releases its platform storage ------------ */
    deal_root_frame_write(LIVE_ERRORS, NULL);
    deal_root_frame_write(LIVE_ERRORS + 1, NULL);
    read_statistics(&before);
    deal_gc_collect_full();
    read_statistics(&after);
    check(after.object_count == before.object_count - 2u
              && after.live_bytes == before.live_bytes - 2u * (uint64_t)error_bytes
              && after.swept_objects == before.swept_objects + 2u,
          "reclaiming the unreachable Errors takes exactly their two managed "
          "payloads and two swept objects");
    check(platform_live()
              == baseline
                  + (size_t)LIVE_ERRORS * FULL_ERROR_PLATFORM_ALLOCATIONS,
          "reclaiming the unreachable Errors releases exactly their platform "
          "storage: the full-shape record's copied text, frames array and "
          "labels and the bare record's two text copies");
    check_full_error_text(errors[0],
                          "a live Error keeps its text while a sibling is "
                          "reclaimed");

    /* -- 4. the whole graph, and the exact baseline ---------------------- */
    for (uint32_t index = 0; index < (uint32_t)LIVE_ERRORS; index += 1u) {
        deal_root_frame_write(index, NULL);
    }
    read_statistics(&before);
    deal_gc_collect_full();
    read_statistics(&after);
    check(after.object_count == 0u && after.live_bytes == 0u,
          "the dropped Error graph is reclaimed");
    check(after.swept_objects
              == before.swept_objects + (uint64_t)LIVE_ERRORS,
          "every dropped Error is one swept object");
    check(platform_live() == baseline,
          "reclaiming the whole Error graph returns the platform allocation "
          "count to its baseline: no copied text, frames array or label is "
          "stranded");
    deal_root_frame_leave();

    /* -- 5. reuse: a reclaimed record's storage is reinitialized --------- */
    deal_root_frame_enter(ERROR_SLOTS);
    for (uint32_t index = 0; index < (uint32_t)LIVE_ERRORS; index += 1u) {
        errors[index] = create_full_error();
        deal_root_frame_write(index, errors[index]);
        check(deal_object_layout(errors[index]) == DEAL_LAYOUT_ERROR,
              "a reused size-class payload is an Error record again");
    }
    check(platform_live()
              == baseline
                  + (size_t)LIVE_ERRORS * FULL_ERROR_PLATFORM_ALLOCATIONS,
          "a record allocated over reclaimed storage owns fresh platform "
          "storage, never a released pointer");
    check_full_error_text(errors[LIVE_ERRORS - 1u],
                          "a record allocated over reclaimed storage reads "
                          "back its own text");
    check(errors[0] != errors[1],
          "the reused records are distinct managed objects");
    for (uint32_t index = 0; index < (uint32_t)LIVE_ERRORS; index += 1u) {
        deal_root_frame_write(index, NULL);
    }
    deal_gc_collect_full();
    check(platform_live() == baseline,
          "reclaiming the reused records releases their platform storage too, "
          "with no second release of an earlier record's storage");
    deal_root_frame_leave();

    printf(failures == 0 ? "error reclaim ok\n" : "error reclaim failed\n");
    return failures == 0 ? 0 : 1;
}
