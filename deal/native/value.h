/*
 * deal/native/value.h — the `value` service group.
 *
 * The value group owns the C3 representations (`null`, `undefined`, boolean,
 * number, string, array, record, class instance, function value, Error), the
 * allocation entry of the managed heap and the one host value table of each
 * active crossing (`llvm-native-runtime-value-model` R2, R3, R11).
 *
 * This task publishes the representation authority the emitted module needs
 * first: the two immutable nullish singletons and their tests. `null` and
 * `undefined` are distinct data values (never C `NULL`, never each other), and
 * absence is only presence metadata, so a slot, an optional field and a
 * record member must not encode absence as either singleton. The host value
 * table entries, the boxed-carrier resolution and the managed
 * allocation/deallocation entries arrive with the runtime-value child, which
 * extends this header; the boxed carrier itself (`deal_value`, R3) is declared
 * in `deal_rt.h` and its host-visible constructors/accessors are the H2 entries
 * of `deal_rt.h`.
 */
#ifndef DEAL_NATIVE_VALUE_H
#define DEAL_NATIVE_VALUE_H

#include "deal/native/deal_rt.h"

/*
 * The one immutable runtime singleton for language `null` (C3). Generated code
 * stores this pointer at a reference position for `null` and never C `NULL`, so
 * `null` is distinguishable from an absent slot, an absent optional field and
 * the internal missing of a comparison-operand read.
 *
 * The spelling is deliberately not `deal_null`: that name is H2's boxed-carrier
 * construction entry of `deal_rt.h`.
 */
extern void *const deal_null_singleton;

/*
 * The one immutable runtime singleton for language `undefined` (C3). Generated
 * code stores this pointer at a reference position for `undefined`; it is a
 * distinct value from the null singleton and from C `NULL`.
 *
 * The spelling is deliberately not `deal_undefined`: that name is H2's
 * boxed-carrier construction entry of `deal_rt.h`.
 */
extern void *const deal_undefined_singleton;

/* 1 when `reference` is the null singleton, 0 otherwise (never C NULL). */
int deal_value_is_null(const void *reference);

/* 1 when `reference` is the undefined singleton, 0 otherwise. */
int deal_value_is_undefined(const void *reference);

#endif /* DEAL_NATIVE_VALUE_H */
