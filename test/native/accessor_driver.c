/*
 * test/native/accessor_driver.c — the R9 presence-returning accessors.
 *
 * Exercises every accessor on a fully carrying Error and on a minimal Error: a
 * present field reports present with its value, an absent field reports absent
 * and leaves the out-parameter untouched (an absent field is never an empty
 * string), frames are innermost-first with a 0-based index that rejects an
 * out-of-range read, and the cause is the cause Error's code.
 *
 * Every accessor call is bound to a local in a statement of its own before the
 * observation reads the out-parameter. C11 6.5.2.2p10 leaves the evaluations of
 * a call's arguments indeterminately sequenced, so an accessor call written as
 * a sibling argument of the read it feeds would be order-dependent: the read
 * could run before the accessor wrote the out-parameter, and the fixture would
 * report false failures under any other argument evaluation order.
 */
#include "deal/native/entry.h"
#include "deal/native/error.h"
#include "deal/native/platform.h"

#include <stdio.h>
#include <string.h>

static int failures;

static void check(int condition, const char *what) {
    if (!condition) {
        printf("FAIL: %s\n", what);
        failures += 1;
    }
}

/* One observed accessor result: the presence flag plus its out-parameter. */
typedef struct {
    int present;
    const char *text;
    int32_t integer;
} observed;

static observed observe_text(int present, const char *text) {
    observed result;
    result.present = present;
    result.text = text;
    result.integer = 0;
    return result;
}

static observed observe_integer(int present, int32_t integer) {
    observed result;
    result.present = present;
    result.text = NULL;
    result.integer = integer;
    return result;
}

static void check_text(observed result, const char *expected, const char *what) {
    check(result.present, what);
    if (result.present) {
        check(result.text != NULL && strcmp(result.text, expected) == 0, what);
    }
}

int main(void) {
    const deal_origin origin = {"native/accessors.deal", 12, 5};
    const char *text = "sentinel";
    int32_t integer = -1;
    observed result;
    int present;

    /* A minimal Error: required fields only, no origin, no frames. */
    deal_error *minimal = deal_error_create("E8001", "minimal", NULL);

    present = deal_error_code(minimal, &text);
    result = observe_text(present, text);
    check_text(result, "E8001", "code present");
    present = deal_error_message(minimal, &text);
    result = observe_text(present, text);
    check_text(result, "minimal", "message present");

    text = "sentinel";
    present = deal_error_source_file(minimal, &text);
    result = observe_text(present, text);
    check(result.present == 0,
          "an originless Error reports the source file absent");
    check(strcmp(text, "sentinel") == 0,
          "an absent text field leaves the out-parameter untouched");

    integer = -7;
    present = deal_error_line(minimal, &integer);
    result = observe_integer(present, integer);
    check(result.present == 0 && result.integer == -7,
          "an originless Error reports the line absent and untouched");
    integer = -7;
    present = deal_error_column(minimal, &integer);
    result = observe_integer(present, integer);
    check(result.present == 0 && result.integer == -7,
          "an originless Error reports the column absent and untouched");

    present = deal_error_expected(minimal, &text);
    result = observe_text(present, text);
    check(result.present == 0, "an absent expected field reports absent");
    present = deal_error_actual(minimal, &text);
    result = observe_text(present, text);
    check(result.present == 0, "an absent actual field reports absent");

    integer = -1;
    present = deal_error_frame_count(minimal, &integer);
    result = observe_integer(present, integer);
    check(result.present == 1 && result.integer == 0,
          "an Error with no active frames carries a zero frame count");

    present = deal_error_frame(minimal, 0, &text);
    result = observe_text(present, text);
    check(result.present == 0, "an out-of-range frame index reports absent");
    present = deal_error_cause_code(minimal, &text);
    result = observe_text(present, text);
    check(result.present == 0, "an Error with no cause reports the cause code absent");

    /* A fully carrying Error: origin, both optional fields, a cause, frames. */
    deal_call_frame_push("native/accessors.deal#outer@F0");
    deal_call_frame_push("native/accessors.deal#middle@F1");
    deal_call_frame_push("native/accessors.deal#inner@F2");
    deal_error *cause = deal_error_create("E9002", "root cause", NULL);
    /* The cause is a runtime intermediate across the outer Error's allocation,
     * so it is rooted through the scoped root API (M5). */
    deal_root_scope_push();
    uint32_t cause_slot = deal_root_scope_hold(cause);
    deal_error *error = deal_error_create("E8010", "carrying", &origin);
    deal_error_set_expected(error, "string");
    deal_error_set_actual(error, "number");
    deal_error_set_cause(error, cause);
    deal_root_scope_release(cause_slot);
    deal_root_scope_pop();

    present = deal_error_source_file(error, &text);
    result = observe_text(present, text);
    check_text(result, "native/accessors.deal",
               "the origin source file is stored unchanged");
    integer = -1;
    present = deal_error_line(error, &integer);
    result = observe_integer(present, integer);
    check(result.present == 1 && result.integer == 12,
          "the origin line is the compiled one-based line");
    integer = -1;
    present = deal_error_column(error, &integer);
    result = observe_integer(present, integer);
    check(result.present == 1 && result.integer == 5,
          "the origin column is the compiled one-based column");
    present = deal_error_expected(error, &text);
    result = observe_text(present, text);
    check_text(result, "string", "the expected field is present");
    present = deal_error_actual(error, &text);
    result = observe_text(present, text);
    check_text(result, "number", "the actual field is present");
    present = deal_error_cause_code(error, &text);
    result = observe_text(present, text);
    check_text(result, "E9002", "the cause is reported through its code");

    integer = -1;
    present = deal_error_frame_count(error, &integer);
    result = observe_integer(present, integer);
    check(result.present == 1 && result.integer == 3,
          "the frame count is the active frame count");

    present = deal_error_frame(error, 0, &text);
    result = observe_text(present, text);
    check_text(result, "native/accessors.deal#inner@F2",
               "frame 0 is the innermost active frame");
    present = deal_error_frame(error, 1, &text);
    result = observe_text(present, text);
    check_text(result, "native/accessors.deal#middle@F1",
               "frame 1 is the next outer frame");
    present = deal_error_frame(error, 2, &text);
    result = observe_text(present, text);
    check_text(result, "native/accessors.deal#outer@F0",
               "frame 2 is the outermost active frame");
    present = deal_error_frame(error, 3, &text);
    result = observe_text(present, text);
    check(result.present == 0, "one past the frame count reports absent");
    present = deal_error_frame(error, -1, &text);
    result = observe_text(present, text);
    check(result.present == 0, "a negative frame index reports absent");
    deal_call_frame_pop();
    deal_call_frame_pop();
    deal_call_frame_pop();

    /* A later Error sees only the frames active at its materialization. */
    deal_call_frame_push("native/accessors.deal#later@F3");
    deal_error *later = deal_error_create("E8002", "later", &origin);
    integer = -1;
    present = deal_error_frame_count(later, &integer);
    result = observe_integer(present, integer);
    check(result.present == 1 && result.integer == 1,
          "the frame snapshot is taken at materialization");
    present = deal_error_frame(later, 0, &text);
    result = observe_text(present, text);
    check_text(result, "native/accessors.deal#later@F3",
               "the snapshot starts at the innermost frame of that moment");
    deal_call_frame_pop();
    check(deal_call_frame_depth() == 0, "the call-frame stack unwinds");

    if (failures != 0) {
        printf("accessors FAILED: %d\n", failures);
        return 1;
    }
    printf("accessors ok\n");
    deal_platform_flush_stdout();
    return 0;
}
