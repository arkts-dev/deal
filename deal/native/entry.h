/*
 * deal/native/entry.h — the one embedding surface of the DEAL C11 runtime.
 *
 * Declares the whole entry surface once (`llvm-native-runtime-value-model` R9,
 * `llvm-native-host-abi-and-async` H6), and every symbol has exactly one owner:
 *
 *   - the emitted project module defines the six export-surface functions
 *     `deal_project_start`, `deal_project_run_main`, `deal_project_exports`,
 *     `deal_export_lookup`, `deal_export_invoke` and
 *     `deal_async_export_invoke`, because it owns the module walk and the
 *     per-unit export tables those functions read
 *     (`llvm-native-codegen-cfg-ssa` C2). `deal_module` and `deal_export_entry`
 *     are opaque here; their layout exists only in the emitted module.
 *   - `deal/native/entry.c` defines `deal_project_main` and the terminal
 *     projection, and reads no module structure;
 *   - `deal/native/error.c` defines the error accessors;
 *   - `deal/native/main.c` defines the product build's process entry.
 *
 * The accessors have one uniform shape: each returns a C presence flag and
 * writes its value through a typed out-parameter, so an absent field is
 * reported as absent and never as an empty string.
 *
 * Terminal projection (`Terminal`, R9): success exits 0 with no terminal
 * output; an uncaught DEAL error writes exactly `DEAL_ERROR_CODE: <code>`
 * followed by `DEAL_ERROR_SNAPSHOT: <canonical JSON>` on stdout, flushes and
 * exits 1; a resource or invariant fault writes one deterministic stderr line
 * and exits 2. `DEAL_DEFER_MAIN=1` withholds only the entry invoke, so a driver
 * that owns module loading can call the start surface alone.
 */
#ifndef DEAL_NATIVE_ENTRY_H
#define DEAL_NATIVE_ENTRY_H

#include "deal/native/deal_rt.h"

/* -- defined by the emitted project module (C2, H6) ---------------------- */

/* Runs the module walk (module initialization, host loads) once. */
deal_outcome deal_project_start(void);

/* Invokes the project's entry point. */
deal_outcome deal_project_run_main(void);

/* The export table of one successfully initialized module, or NULL. */
deal_module *deal_project_exports(const char *module_path);

/* One export entry by name, or NULL when the name is absent. */
deal_export_entry *deal_export_lookup(deal_module *module, const char *name);

/* Invokes a synchronous zero-arity export once. */
deal_outcome deal_export_invoke(deal_export_entry *entry);

/* Invokes an async export, pumped to completion. */
deal_outcome deal_async_export_invoke(deal_export_entry *entry);

/* -- defined by entry.c: the process-level terminal projection ----------- */

/* start; unless DEAL_DEFER_MAIN=1, run main; then the terminal projection. */
int deal_project_main(void);

/* -- defined by error.c: the error accessors (R9) ------------------------ */

int deal_error_code(deal_error *error, const char **out);          /* 1 = present */
int deal_error_message(deal_error *error, const char **out);
int deal_error_source_file(deal_error *error, const char **out);
int deal_error_line(deal_error *error, int32_t *out);
int deal_error_column(deal_error *error, int32_t *out);
int deal_error_expected(deal_error *error, const char **out);
int deal_error_actual(deal_error *error, const char **out);
int deal_error_frame_count(deal_error *error, int32_t *out);
int deal_error_frame(deal_error *error, int32_t index, const char **out);
int deal_error_cause_code(deal_error *error, const char **out);

#endif /* DEAL_NATIVE_ENTRY_H */
