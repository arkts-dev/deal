/*
 * deal/native/host.c — the `host` service group implementation.
 *
 * The specifier lookup, declaration validation, crossings, host value table,
 * handle table and error-token table land with the host-sync child.
 * `deal_host_binding` storage is platform memory (`deal_platform_allocate`),
 * created once at module initialization and never managed.
 */
#include "deal/native/host.h"
#include "deal/native/platform.h"
