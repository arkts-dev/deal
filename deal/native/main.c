/*
 * deal/native/main.c — the product build's process-entry translation unit
 * (`llvm-native-host-abi-and-async` H6, `llvm-native-backend-integration` D4).
 *
 * This unit belongs to no service group and holds exactly the process entry.
 * A driver build omits it and supplies its own entry through the seam's
 * generated driver source.
 */
#include "deal/native/entry.h"

int main(void) { return deal_project_main(); }
