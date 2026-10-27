/*
 * deal/native/async.c — the `async` service group implementation.
 *
 * The open-operation table, token allocation, the completion pump, service-hook
 * polling and the single delivery path land with the host-async child. This
 * translation unit is the group's compilation unit today.
 */
#include "deal/native/async.h"
#include "deal/native/platform.h"
