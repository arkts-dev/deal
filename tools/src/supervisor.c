/*
 * DEALPG4 nested supervisor (stage placeholder).
 *
 * The serve/run entry bodies land in the supervisor child; this stage
 * keeps only the mode-entry placeholder surface: MODE_NOT_IMPLEMENTED on
 * stderr and a nonzero exit, with no fork, no exec, and no channel.
 * MODE_NOT_IMPLEMENTED is a stage placeholder, not an integrity token.
 */
#include "supervisor.h"

#include <stdio.h>

int dealpg4_run_entry(int argc, char **argv)
{
    (void)argc;
    (void)argv;
    fprintf(stderr, "MODE_NOT_IMPLEMENTED\n");
    return 1;
}

int dealpg4_serve_entry(int argc, char **argv)
{
    (void)argc;
    (void)argv;
    fprintf(stderr, "MODE_NOT_IMPLEMENTED\n");
    return 1;
}
