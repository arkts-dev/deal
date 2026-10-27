/*
 * test/native/registry_empty.c — the count-zero generated registry sentinel.
 *
 * The build seam generates one scratch registry translation unit per build
 * (`llvm-native-backend-integration` D4). With no supplied host unit it defines
 * exactly the count-zero sentinel shape of `llvm-native-host-abi-and-async` H2:
 * one entry whose members are NULL, never looked up, and a count of zero. This
 * fixture is that unit, so the published registry externs link in every build
 * shape this task exercises.
 */
#include "deal/native/deal_rt.h"

const deal_host_registry_entry deal_host_registry[1] = {{NULL, NULL}};
const size_t deal_host_registry_count = 0;
