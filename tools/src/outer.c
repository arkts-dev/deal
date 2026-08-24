/*
 * DEALPG4 outer feature supervisor (stage placeholder).
 *
 * The outer entry body lands in the outer child; this stage keeps only
 * the mode-entry placeholder surface: MODE_NOT_IMPLEMENTED on stderr and
 * a nonzero exit, with no fork, no exec, and no channel.
 * MODE_NOT_IMPLEMENTED is a stage placeholder, not an integrity token.
 */
#include "outer.h"

#include <stdio.h>

int dealpg4_outer_entry(int argc, char **argv)
{
    (void)argc;
    (void)argv;
    fprintf(stderr, "MODE_NOT_IMPLEMENTED\n");
    return 1;
}
