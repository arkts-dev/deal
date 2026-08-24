/*
 * DEALPG4 nested supervisor (stage placeholder).
 *
 * The serve/run entry bodies land in the supervisor child; this stage
 * keeps only the mode-entry placeholder surface: each entry prints
 * MODE_NOT_IMPLEMENTED to stderr and returns nonzero with no fork, no
 * exec, and no channel. MODE_NOT_IMPLEMENTED is a stage placeholder, not
 * an integrity token (dealpg4-launcher-core mode dispatch contract).
 *
 * Dispatch delegates run/serve to these entries after the dispatch-owned
 * argument handling (none for these modes at this stage — their argument
 * shapes are defined by the supervisor child) and the mode-entry
 * embedded-limits ordering validation.
 */
#ifndef DEALPG4_SUPERVISOR_H
#define DEALPG4_SUPERVISOR_H

/*
 * run/serve mode entries.
 *
 * argc/argv are the process arguments exactly as dispatch received them
 * (argv[0] = program path, argv[1] = mode name): dispatch performs no
 * argument interpretation for run/serve/outer. The supervisor child
 * replaces these placeholder bodies in the same binary.
 */
int dealpg4_run_entry(int argc, char **argv);
int dealpg4_serve_entry(int argc, char **argv);

#endif
