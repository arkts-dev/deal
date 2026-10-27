/*
 * test/native/nullish_probe.c — the C3 v1.5 nullish representation authority.
 *
 * A strict-C11 consumer of the published runtime: `null` and `undefined` are
 * two distinct immutable singletons, neither of them C `NULL`, so no reference
 * position, optional slot or record member can confuse `null`, `undefined` and
 * absence. Each singleton answers only its own test.
 *
 * The unit defines the process entry (driver shape): the runtime minus
 * `deal/native/main.c` is linked with it.
 */
#include "deal/native/deal_rt.h"
#include "deal/native/value.h"

#include <stdio.h>

int main(void) {
    if (deal_null_singleton == NULL || deal_undefined_singleton == NULL) {
        printf("FAIL: a nullish singleton is C NULL\n");
        return 1;
    }
    if (deal_null_singleton == deal_undefined_singleton) {
        printf("FAIL: null and undefined share one singleton\n");
        return 1;
    }
    if (deal_value_is_null(deal_null_singleton) != 1
            || deal_value_is_null(deal_undefined_singleton) != 0) {
        printf("FAIL: the null test does not separate the two singletons\n");
        return 1;
    }
    if (deal_value_is_undefined(deal_undefined_singleton) != 1
            || deal_value_is_undefined(deal_null_singleton) != 0) {
        printf("FAIL: the undefined test does not separate the two singletons\n");
        return 1;
    }
    if (deal_value_is_null(NULL) != 0 || deal_value_is_undefined(NULL) != 0) {
        printf("FAIL: C NULL is not a DEAL value\n");
        return 1;
    }
    printf("nullish ok\n");
    return 0;
}
