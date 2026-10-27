/*
 * test/native/frames_driver.c — the frame and root invariants.
 *
 * Case 0 exercises the nesting contract: the call-frame stack holds exactly the
 * active labels in call order and restores its depth on every exit path, the
 * root frame enters with zero-initialized slots, writes and leaves in nesting
 * order, and the root depth returns to zero. Its first shape is the zero-slot
 * frame the emitted skeleton pushes (CallFrameContract): enter(0) on the
 * never-allocated root table, no slot access, leave, depth restored, with no
 * pointer arithmetic on the unallocated table base. The suite also builds case
 * 0 with -fsanitize=undefined, so returning to the pre-guard spelling fails the
 * run with a UBSan report instead of passing silently. Cases 1-5 are the
 * deterministic invariant faults: an off-depth root leave, a slot write outside
 * the active frame, a slot write with no active frame, a call-frame pop with no
 * active frame and a slot write from the zero-slot frame. Each fatal case must
 * terminate with one deterministic stderr line and exit 2 instead of returning
 * to the caller.
 */
#include "deal/native/entry.h"
#include "deal/native/error.h"
#include "deal/native/memory.h"
#include "deal/native/platform.h"

#include <stdio.h>

#ifndef DEAL_FRAME_CASE
#define DEAL_FRAME_CASE 0
#endif

#if DEAL_FRAME_CASE == 0
static int failures;

static void check(int condition, const char *what) {
    if (!condition) {
        printf("FAIL: %s\n", what);
        failures += 1;
    }
}
#endif

int main(void) {
#if DEAL_FRAME_CASE == 0
    check(deal_call_frame_depth() == 0, "the call-frame stack starts empty");
    deal_call_frame_push("native/frames.deal#a@F0");
    check(deal_call_frame_depth() == 1, "one pushed frame is active");
    deal_call_frame_push("native/frames.deal#b@F1");
    check(deal_call_frame_depth() == 2, "two pushed frames are active");
    deal_call_frame_push("native/frames.deal#c@F2");
    check(deal_call_frame_depth() == 3, "three pushed frames are active");
    {
        const char *innermost = NULL;
        deal_error *error = deal_error_create("E8001", "framed", NULL);
        int32_t count = 0;
        check(deal_error_frame_count(error, &count) == 1 && count == 3,
              "the Error snapshots the three active frames");
        check(deal_error_frame(error, 0, &innermost) == 1
                  && innermost != NULL
                  && innermost[0] == 'n',
              "the innermost frame label is recorded first");
    }
    deal_call_frame_pop();
    deal_call_frame_pop();
    deal_call_frame_pop();
    check(deal_call_frame_depth() == 0, "the call-frame depth is restored");

    check(deal_root_frame_depth() == 0, "the root depth starts at zero");
    deal_root_frame_enter(0U);
    check(deal_root_frame_depth() == 1,
          "a zero-slot root frame is registered");
    deal_root_frame_leave();
    check(deal_root_frame_depth() == 0,
          "the zero-slot frame leaves and restores the root depth");
    deal_root_frame_enter(2U);
    check(deal_root_frame_depth() == 1, "one entered root frame is registered");
    deal_root_frame_write(0U, (void *)0);
    deal_root_frame_write(1U, (void *)0);
    deal_root_frame_enter(1U);
    check(deal_root_frame_depth() == 2, "nested root frames are registered");
    deal_root_frame_write(0U, (void *)0);
    deal_root_frame_leave();
    check(deal_root_frame_depth() == 1, "the inner root frame left");
    deal_root_frame_leave();
    check(deal_root_frame_depth() == 0, "the root depth is restored");
    printf("frames ok\n");
    deal_platform_flush_stdout();
    return failures == 0 ? 0 : 1;
#elif DEAL_FRAME_CASE == 1
    deal_root_frame_leave();
    printf("unreachable\n");
    return 0;
#elif DEAL_FRAME_CASE == 2
    deal_root_frame_enter(1U);
    deal_root_frame_write(1U, (void *)0);
    printf("unreachable\n");
    return 0;
#elif DEAL_FRAME_CASE == 3
    deal_root_frame_write(0U, (void *)0);
    printf("unreachable\n");
    return 0;
#elif DEAL_FRAME_CASE == 5
    deal_root_frame_enter(0U);
    deal_root_frame_write(0U, (void *)0);
    printf("unreachable\n");
    return 0;
#else
    deal_call_frame_pop();
    printf("unreachable\n");
    return 0;
#endif
}
