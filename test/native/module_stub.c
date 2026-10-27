/*
 * test/native/module_stub.c — a stand-in for the emitted project module.
 *
 * It defines exactly the six export-surface functions of `deal/native/entry.h`
 * and the module-defined `deal_project_arm_table` (C2, H6), so the runtime's
 * entry, terminal, accessor and transport symbols are exercised by a real link
 * in both build shapes. `DEAL_STUB_CASE` selects the behaviour under test; the
 * project module the emitter will produce replaces only this fixture, never a
 * runtime symbol.
 *
 *   0  a structurally valid arm table; install from the start function
 *   1  unsupported table version
 *   2  count and records disagree
 *   3  a null record text pointer
 *   4  a render scope outside the closed set
 *   5  a duplicate arm id
 *   6  the start call returns a carried Error (module-init failure)
 *   7  the entry invoke returns a fully carrying Error
 */
#include "deal/native/entry.h"
#include "deal/native/error.h"
#include "deal/native/platform.h"

#ifndef DEAL_STUB_CASE
#define DEAL_STUB_CASE 0
#endif

/* =========================================================================
 * The failure-arm table
 * ========================================================================= */

#if DEAL_STUB_CASE != 2
static const char *const arm_parameters_one[] = {"name"};
#endif

#if DEAL_STUB_CASE == 0
static const deal_arm_record arm_records[] = {
    {0U, "E8001", "cannot store {name}", arm_parameters_one, 1U,
     DEAL_ARM_SCOPE_TOP_LEVEL, 1U, 0U},
    {1U, "E8002", "array index {index}", arm_parameters_one, 1U,
     DEAL_ARM_SCOPE_INNER_ONLY, 0U, 1U}
};
const deal_arm_table deal_project_arm_table = {DEAL_ARM_TABLE_VERSION, 2U,
                                            arm_records};
#elif DEAL_STUB_CASE == 1
static const deal_arm_record arm_records[] = {
    {0U, "E8001", "cannot store {name}", arm_parameters_one, 1U,
     DEAL_ARM_SCOPE_TOP_LEVEL, 1U, 0U}
};
const deal_arm_table deal_project_arm_table = {DEAL_ARM_TABLE_VERSION + 1U, 1U,
                                            arm_records};
#elif DEAL_STUB_CASE == 2
const deal_arm_table deal_project_arm_table = {DEAL_ARM_TABLE_VERSION, 1U, NULL};
#elif DEAL_STUB_CASE == 3
static const deal_arm_record arm_records[] = {
    {0U, "E8001", NULL, arm_parameters_one, 1U,
     DEAL_ARM_SCOPE_TOP_LEVEL, 1U, 0U}
};
const deal_arm_table deal_project_arm_table = {DEAL_ARM_TABLE_VERSION, 1U,
                                            arm_records};
#elif DEAL_STUB_CASE == 4
static const deal_arm_record arm_records[] = {
    {0U, "E8001", "cannot store {name}", arm_parameters_one, 1U, 2U, 1U, 0U}
};
const deal_arm_table deal_project_arm_table = {DEAL_ARM_TABLE_VERSION, 1U,
                                            arm_records};
#elif DEAL_STUB_CASE == 5
static const deal_arm_record arm_records[] = {
    {7U, "E8001", "cannot store {name}", arm_parameters_one, 1U,
     DEAL_ARM_SCOPE_TOP_LEVEL, 1U, 0U},
    {7U, "E8002", "array index {index}", arm_parameters_one, 1U,
     DEAL_ARM_SCOPE_INNER_ONLY, 0U, 1U}
};
const deal_arm_table deal_project_arm_table = {DEAL_ARM_TABLE_VERSION, 2U,
                                            arm_records};
#else
static const deal_arm_record arm_records[] = {
    {0U, "E8001", "cannot store {name}", arm_parameters_one, 1U,
     DEAL_ARM_SCOPE_TOP_LEVEL, 1U, 0U}
};
const deal_arm_table deal_project_arm_table = {DEAL_ARM_TABLE_VERSION, 1U,
                                            arm_records};
#endif

/* =========================================================================
 * The carried-Error fixtures (cases 6 and 7)
 * ========================================================================= */

#if DEAL_STUB_CASE == 7
#define DEAL_FIXTURE_MESSAGE \
    "bad \"quoted\" text\\ with\ttab \001unit and \303\251 scalar"
#endif

#if DEAL_STUB_CASE == 6 || DEAL_STUB_CASE == 7
static const deal_origin fixture_origin = {
    "native/terminal-fixture.deal", 7, 3
};
#endif

#if DEAL_STUB_CASE == 6
static deal_error *deal_stub_start_error(void) {
    return deal_error_create("E8011", "module initialization failed",
                             &fixture_origin);
}
#elif DEAL_STUB_CASE == 7
static deal_error *deal_stub_raise(void) {
    deal_error *cause = deal_error_create("E9002", "cause text", NULL);
    deal_error *error = deal_error_create("E8010", DEAL_FIXTURE_MESSAGE,
                                          &fixture_origin);
    deal_error_set_expected(error, "string");
    deal_error_set_actual(error, "number");
    deal_error_set_cause(error, cause);
    return error;
}
#endif

/* =========================================================================
 * The export surface
 * ========================================================================= */

deal_outcome deal_project_start(void) {
    deal_outcome outcome;
    outcome.error = NULL;
#if DEAL_STUB_CASE == 6
    outcome.error = deal_stub_start_error();
    return outcome;
#else
    (void)deal_arm_table_install(&deal_project_arm_table);
    return outcome;
#endif
}

deal_outcome deal_project_run_main(void) {
    deal_outcome outcome;
    outcome.error = NULL;
#if DEAL_STUB_CASE == 7
    deal_call_frame_push("native/main.deal#entry@F0");
    deal_call_frame_push("native/main.deal#run@F1");
    deal_call_frame_push("native/main.deal#deep@F2");
    outcome.error = deal_stub_raise();
    deal_call_frame_pop();
    deal_call_frame_pop();
    deal_call_frame_pop();
    return outcome;
#else
    const char *print = deal_platform_environment("DEAL_STUB_PRINT_MAIN");
    if (print != NULL && print[0] == '1') {
        deal_platform_write_stdout("MAIN_RAN\n", 9U);
        deal_platform_flush_stdout();
    }
    return outcome;
#endif
}

deal_module *deal_project_exports(const char *module_path) {
    (void)module_path;
    return NULL;
}

deal_export_entry *deal_export_lookup(deal_module *module, const char *name) {
    (void)module;
    (void)name;
    return NULL;
}

deal_outcome deal_export_invoke(deal_export_entry *entry) {
    (void)entry;
    deal_outcome outcome;
    outcome.error = NULL;
    return outcome;
}

deal_outcome deal_async_export_invoke(deal_export_entry *entry) {
    return deal_export_invoke(entry);
}
