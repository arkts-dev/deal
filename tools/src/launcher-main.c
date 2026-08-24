/*
 * DEALPG4 launcher entry point (stage placeholder).
 *
 * ISSUE-0198 artifact set skeleton: minimal compilable main that prints a
 * usage line on stderr and exits nonzero. The mode dispatch child replaces
 * this placeholder with the five-mode dispatch (probe|run|serve|outer|selftest).
 */
#include <stdio.h>

int main(int argc, char **argv)
{
    (void)argc;
    (void)argv;
    fprintf(stderr,
            "usage: tools/deal-process-launcher-linux-x86_64 <mode> [args]\n");
    return 1;
}
