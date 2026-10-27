/*
 * deal/native/value.c — the `value` service group implementation.
 *
 * The group's representation authority: the immutable null and undefined
 * singletons (C3). The host value table, the boxed-carrier resolution and the
 * managed heap entry points arrive with the runtime-value child, which extends
 * this translation unit without redefining a singleton or its test.
 */
#include "deal/native/value.h"

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
