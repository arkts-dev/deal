/*
 * DEALPG4 selftest surface (stage placeholder).
 *
 * The probe batteries, selftest bound machinery, and fault-injection
 * override installation land in the selftest child; this stage keeps only
 * the mode-entry placeholder surface: MODE_NOT_IMPLEMENTED on stderr and
 * a nonzero exit, with no fork, no exec, and no channel.
 * MODE_NOT_IMPLEMENTED is a stage placeholder, not an integrity token.
 */
#include "selftest.h"

#include <stdio.h>

int dealpg4_probe_entry(int64_t limit_ms)
{
    (void)limit_ms;
    fprintf(stderr, "MODE_NOT_IMPLEMENTED\n");
    return 1;
}

int dealpg4_selftest_entry(int64_t limit_ms)
{
    (void)limit_ms;
    fprintf(stderr, "MODE_NOT_IMPLEMENTED\n");
    return 1;
}
