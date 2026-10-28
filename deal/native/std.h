/*
 * deal/native/std.h — the `std` service group.
 *
 * Exactly the 18 closed operations of `StdlibFunctionId`
 * (`llvm-native-runtime-value-model` R7/R8): console log and error, the string
 * operations over Unicode scalar values, record keys, JSON parse and stringify,
 * the four `math` operations and `time.nowMillis`. Generated code dispatches
 * from the emitted `STDLIB_CALL` operation id, and this group owns the
 * operation's `STDLIB_PARAMETER` and `STDLIB_RETURN` crossings.
 *
 * The six spec-listed stdlib modules are realized by this one closed operation
 * surface; the `std/` declaration modules stay the compile-time type authority
 * and the descriptor texts below are exactly their declared signatures.
 *
 * Crossings. Every parameter is checked left to right through the delivered
 * `deal_check_value` at the descriptor-kind rule's typed boundary before the
 * operation runs, and the operation's result passes the same rule before it is
 * published, so this group authors no failure text, no expected/actual
 * projection and no second check authority: a crossing failure is the installed
 * typed-boundary arm at the call origin. Console effects commit before the
 * result is checked and no operation mutates an argument.
 *
 * String operations are defined over Unicode scalar values (R8): `length` is
 * the exact scalar count, `substring` validates both bounds through the
 * `SUBSTRING_BOUNDS` arm before clamping to `0..scalar count` and yields the
 * empty string for a reversed range, and `split`/`trim` follow R8 exactly.
 * `nowMillis` reads the platform realtime clock as binary64 epoch
 * milliseconds; it is never a fixed timestamp.
 */
#ifndef DEAL_NATIVE_STD_H
#define DEAL_NATIVE_STD_H

#include "deal/native/deal_rt.h"

/*
 * The closed operation ids: the `StdlibFunctionId` ordinals of the merged v1.5
 * registry, in canonical order. The emitted `STDLIB_CALL` payload carries one of
 * them and an id outside the set is a producer defect.
 */
#define DEAL_STDLIB_CONSOLE_LOG        0u
#define DEAL_STDLIB_CONSOLE_ERROR      1u
#define DEAL_STDLIB_STRING_LENGTH      2u
#define DEAL_STDLIB_STRING_SUBSTRING   3u
#define DEAL_STDLIB_STRING_CONTAINS    4u
#define DEAL_STDLIB_STRING_STARTS_WITH 5u
#define DEAL_STDLIB_STRING_ENDS_WITH   6u
#define DEAL_STDLIB_STRING_REPLACE     7u
#define DEAL_STDLIB_STRING_SPLIT       8u
#define DEAL_STDLIB_STRING_TRIM        9u
#define DEAL_STDLIB_TABLE_KEYS        10u
#define DEAL_STDLIB_JSON_PARSE        11u
#define DEAL_STDLIB_JSON_STRINGIFY    12u
#define DEAL_STDLIB_MATH_FLOOR        13u
#define DEAL_STDLIB_MATH_CEIL         14u
#define DEAL_STDLIB_MATH_SQRT         15u
#define DEAL_STDLIB_MATH_ABS_NUMBER   16u
#define DEAL_STDLIB_TIME_NOW_MILLIS   17u
#define DEAL_STDLIB_OPERATION_COUNT   18u

/*
 * One `STDLIB_CALL`: the closed operation id, the already evaluated argument
 * presentations in declared order (each one the call's `STDLIB_PARAMETER`
 * input, so the closed presentation set reaches the crossing) and the op
 * origin. Answers the operation's result as persistent tagged data: the
 * declared result value, the `undefined` datum of a result-only `void`
 * completion (a console call's result protocol), or the typed-boundary arm at
 * `origin` when a parameter or the result fails its crossing. An operation id
 * outside the closed set, an argument count that differs from the declared
 * arity, a missing argument array and a missing origin are producer defects,
 * reported deterministically.
 */
deal_outcome_data deal_std_call(uint32_t operation_id,
                                const deal_check_input *arguments,
                                uint32_t argument_count,
                                const deal_origin *origin);

/* `std/time.nowMillis`: binary64 epoch milliseconds from the platform realtime
 * clock (`deal_platform_realtime_ns`), never a fixed timestamp. */
double deal_std_now_millis(void);

#endif /* DEAL_NATIVE_STD_H */
