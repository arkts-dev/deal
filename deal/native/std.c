/*
 * deal/native/std.c — the `std` service group implementation.
 *
 * The 18 closed operation implementations and their parameter and return
 * crossings land with the runtime-json-stdlib child; `nowMillis` reads
 * `deal_platform_realtime_ns` there like every other operation. This
 * translation unit is the group's compilation unit today.
 */
#include "deal/native/std.h"
