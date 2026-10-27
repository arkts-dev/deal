/*
 * deal/native/text.c — the `text` service group implementation.
 *
 * The entries declared by text.h land with the runtime-boundaries-errors child
 * (string representation, scalar validation, Unicode scalar operations and the
 * single scalar-sequence comparison). This translation unit is the group's
 * compilation unit today, so every published translation unit compiles under
 * the pinned strict C11 flag set at both optimization levels.
 */
#include "deal/native/text.h"
