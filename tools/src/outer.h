/*
 * DEALPG4 outer feature supervisor (stage placeholder).
 *
 * The outer entry body lands in the outer child; this stage keeps only
 * the mode-entry placeholder surface: MODE_NOT_IMPLEMENTED to stderr and
 * a nonzero exit, with no fork, no exec, and no channel.
 * MODE_NOT_IMPLEMENTED is a stage placeholder, not an integrity token
 * (dealpg4-launcher-core mode dispatch contract).
 *
 * Dispatch delegates outer to this entry after the dispatch-owned
 * argument handling (none for outer at this stage — its argument shape is
 * defined by the outer child) and the mode-entry embedded-limits ordering
 * validation.
 */
#ifndef DEALPG4_OUTER_H
#define DEALPG4_OUTER_H

/*
 * outer mode entry.
 *
 * argc/argv are the process arguments exactly as dispatch received them
 * (argv[0] = program path, argv[1] = mode name): dispatch performs no
 * argument interpretation for run/serve/outer. The outer child replaces
 * this placeholder body in the same binary.
 */
int dealpg4_outer_entry(int argc, char **argv);

#endif
