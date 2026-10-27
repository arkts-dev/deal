/*
 * deal/native/std.h — the `std` service group.
 *
 * Exactly the 18 closed operations of `StdlibFunctionId`
 * (`llvm-native-runtime-value-model` R7): console log and error, the string
 * operations over Unicode scalar values, table keys, JSON parse and stringify,
 * the four `math` operations and `time.nowMillis`. Generated code dispatches
 * from the emitted `STDLIB_CALL` operation id, and parameter and return
 * crossings use the `STDLIB_PARAMETER` and `STDLIB_RETURN` boundaries.
 *
 * The six spec-listed stdlib modules are realized by this one closed operation
 * surface; the spec-listed `std/` declaration modules stay the compile-time type authority. The operation
 * implementations land with the runtime-json-stdlib child, which extends this
 * header.
 */
#ifndef DEAL_NATIVE_STD_H
#define DEAL_NATIVE_STD_H

#include "deal/native/deal_rt.h"

/* One `STDLIB_CALL`: the closed operation id, the already checked argument
 * carriers and the op origin. */
deal_outcome_value deal_std_call(uint32_t operation_id,
                                const deal_value *arguments,
                                uint32_t argument_count,
                                const deal_origin *origin);

/* `std/time.nowMillis`: binary64 epoch milliseconds from the platform realtime
 * clock (`deal_platform_realtime_ns`), never a fixed timestamp. */
double deal_std_now_millis(void);

#endif /* DEAL_NATIVE_STD_H */
