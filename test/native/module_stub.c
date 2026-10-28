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
 *   0  the complete closed arm table, installed from the start function
 *   1  unsupported table version
 *   2  count and records disagree
 *   3  a null record text pointer
 *   4  a render scope outside the closed set
 *   5  a duplicate arm id
 *   6  the start call returns a carried Error (module-init failure)
 *   7  the entry invoke returns a fully carrying Error
 *   8  an unknown arm id in the table
 *   9  a missing arm
 *  10  a declared parameter-shape mismatch
 *  11  a declared expected/actual field-shape mismatch
 *
 * The table of every installing case carries the complete closed arm
 * repertoire — one record per DEAL-visible arm, in canonical arm order, with
 * the arm's own code, its template, its declared parameter names, its render
 * scope and its declared expected/actual fields — because the install entry
 * validates the supplied table against the runtime's compiled repertoire and
 * fails closed on an unknown arm, a missing arm and a parameter-shape,
 * render-scope or declared-field mismatch. Cases 1-5 each carry one structural
 * defect that the delivered structural rules reject first; cases 8-11 differ
 * from the accepted table in exactly one record or in the record count.
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

#if DEAL_STUB_CASE >= 1 && DEAL_STUB_CASE <= 5

/* The structural negatives: each one violates one delivered structural rule,
 * which the install entry rejects before any repertoire rule runs. */
#if DEAL_STUB_CASE != 2
/* The count/records negative carries no record at all. */
static const char *const arm_parameter_name[] = {"kind"};
#endif

#if DEAL_STUB_CASE == 1
static const deal_arm_record arm_records[] = {
    {0U, "E8001", "expected {kind}", arm_parameter_name, 1U,
     DEAL_ARM_SCOPE_TOP_LEVEL, 1U, 1U}
};
const deal_arm_table deal_project_arm_table = {DEAL_ARM_TABLE_VERSION + 1U, 1U,
                                            arm_records};
#elif DEAL_STUB_CASE == 2
const deal_arm_table deal_project_arm_table = {DEAL_ARM_TABLE_VERSION, 1U, NULL};
#elif DEAL_STUB_CASE == 3
static const deal_arm_record arm_records[] = {
    {0U, "E8001", NULL, arm_parameter_name, 1U,
     DEAL_ARM_SCOPE_TOP_LEVEL, 1U, 1U}
};
const deal_arm_table deal_project_arm_table = {DEAL_ARM_TABLE_VERSION, 1U,
                                            arm_records};
#elif DEAL_STUB_CASE == 4
static const deal_arm_record arm_records[] = {
    {0U, "E8001", "expected {kind}", arm_parameter_name, 1U, 2U, 1U, 1U}
};
const deal_arm_table deal_project_arm_table = {DEAL_ARM_TABLE_VERSION, 1U,
                                            arm_records};
#else
static const deal_arm_record arm_records[] = {
    {0U, "E8001", "expected {kind}", arm_parameter_name, 1U,
     DEAL_ARM_SCOPE_TOP_LEVEL, 1U, 1U},
    {0U, "E8001", "expected {kind}", arm_parameter_name, 1U,
     DEAL_ARM_SCOPE_TOP_LEVEL, 1U, 1U}
};
const deal_arm_table deal_project_arm_table = {DEAL_ARM_TABLE_VERSION, 2U,
                                            arm_records};
#endif

#else

/* The complete closed arm table, in canonical arm order. The first record is
 * parameterized so the repertoire negatives differ from the accepted table in
 * exactly one fact. */
#if DEAL_STUB_CASE != 10
/* The parameter-shape negative declares its first arm with a different
 * parameter array, so this one is absent there. */
static const char *const arm_parameters_0[] = {"kind"};
#endif
static const char *const arm_parameters_2[] = {"expected", "actual"};
static const char *const arm_parameters_5[] = {"oneBasedIndex"};
static const char *const arm_parameters_9[] = {"expected", "actual"};
static const char *const arm_parameters_10[] = {"index", "inner"};
static const char *const arm_parameters_11[] = {"expected"};
static const char *const arm_parameters_12[] = {"inner"};
static const char *const arm_parameters_13[] = {"expected"};
static const char *const arm_parameters_14[] = {"actual"};
static const char *const arm_parameters_15[] = {"module", "reason"};
static const char *const arm_parameters_17[] = {"module"};
static const char *const arm_parameters_18[] = {"name", "module"};
static const char *const arm_parameters_19[] =
    {"name", "module", "expected", "actual"};
static const char *const arm_parameters_20[] =
    {"name", "module", "expected", "actual"};
static const char *const arm_parameters_21[] =
    {"name", "module", "defaults|fields"};
static const char *const arm_parameters_22[] = {"field", "classId"};
static const char *const arm_parameters_23[] =
    {"oneBasedByteOffset", "reason"};
static const char *const arm_parameters_24[] = {"fieldPath", "actual"};
static const char *const arm_parameters_25[] = {"actual"};

#define DEAL_STUB_ARM_RECORDS(ARM0_ID, ARM0_PARAMETERS, ARM0_EXPECTED, \
                              ARM0_ACTUAL) \
    {ARM0_ID, "E8001", "expected {kind}", ARM0_PARAMETERS, 1U, \
     DEAL_ARM_SCOPE_TOP_LEVEL, ARM0_EXPECTED, ARM0_ACTUAL}, \
    {1U, "E8001", "expected string, got invalid Unicode scalar encoding", NULL, 0U, \
     DEAL_ARM_SCOPE_TOP_LEVEL, 1U, 1U}, \
    {2U, "E8001", "expected instance of {expected}, got {actual}", arm_parameters_2, 2U, \
     DEAL_ARM_SCOPE_TOP_LEVEL, 1U, 1U}, \
    {3U, "E8001", "expected string, got invalid UTF-8 encoding", NULL, 0U, \
     DEAL_ARM_SCOPE_INNER_ONLY, 0U, 0U}, \
    {4U, "E8001", "expected string, got UTF-16 surrogate code point", NULL, 0U, \
     DEAL_ARM_SCOPE_INNER_ONLY, 0U, 0U}, \
    {5U, "E8003", "array element {oneBasedIndex} type mismatch", arm_parameters_5, 1U, \
     DEAL_ARM_SCOPE_TOP_LEVEL, 1U, 1U}, \
    {6U, "E8002", "array index out of bounds", NULL, 0U, \
     DEAL_ARM_SCOPE_TOP_LEVEL, 0U, 0U}, \
    {7U, "E8002", "array index out of bounds", NULL, 0U, \
     DEAL_ARM_SCOPE_TOP_LEVEL, 0U, 0U}, \
    {8U, "E8002", "array index out of bounds", NULL, 0U, \
     DEAL_ARM_SCOPE_TOP_LEVEL, 0U, 0U}, \
    {9U, "E8010", "function signature mismatch: expected {expected}, got {actual}", arm_parameters_9, 2U, \
     DEAL_ARM_SCOPE_TOP_LEVEL, 1U, 1U}, \
    {10U, "E8010", "parameter {index} type mismatch: {inner}", arm_parameters_10, 2U, \
     DEAL_ARM_SCOPE_TOP_LEVEL, 1U, 1U}, \
    {11U, "E8010", "return value 1 type mismatch: expected {expected}, got nothing", arm_parameters_11, 1U, \
     DEAL_ARM_SCOPE_TOP_LEVEL, 1U, 1U}, \
    {12U, "E8010", "return value 1 type mismatch: {inner}", arm_parameters_12, 1U, \
     DEAL_ARM_SCOPE_TOP_LEVEL, 1U, 1U}, \
    {13U, "E8001", "expected {expected}", arm_parameters_13, 1U, \
     DEAL_ARM_SCOPE_TOP_LEVEL, 1U, 1U}, \
    {14U, "E8010", "host async function must return an async operation, got {actual}", arm_parameters_14, 1U, \
     DEAL_ARM_SCOPE_TOP_LEVEL, 1U, 1U}, \
    {15U, "E8011", "failed to load host module '{module}': {reason}", arm_parameters_15, 2U, \
     DEAL_ARM_SCOPE_TOP_LEVEL, 0U, 0U}, \
    {16U, "E8011", "host module did not return a module object", NULL, 0U, \
     DEAL_ARM_SCOPE_TOP_LEVEL, 1U, 1U}, \
    {17U, "E8011", "host module '{module}' did not return a module object", arm_parameters_17, 1U, \
     DEAL_ARM_SCOPE_TOP_LEVEL, 0U, 0U}, \
    {18U, "E8011", "missing host export '{name}' in module '{module}'", arm_parameters_18, 2U, \
     DEAL_ARM_SCOPE_TOP_LEVEL, 0U, 0U}, \
    {19U, "E8011", "host export '{name}' in module '{module}' has signature mismatch: expected {expected}, got {actual}", arm_parameters_19, 4U, \
     DEAL_ARM_SCOPE_TOP_LEVEL, 0U, 0U}, \
    {20U, "E8011", "host class '{name}' in module '{module}' has identity mismatch: expected {expected}, got {actual}", arm_parameters_20, 4U, \
     DEAL_ARM_SCOPE_TOP_LEVEL, 0U, 0U}, \
    {21U, "E8011", "host class '{name}' in module '{module}' has invalid {defaults|fields} metadata", arm_parameters_21, 3U, \
     DEAL_ARM_SCOPE_TOP_LEVEL, 0U, 0U}, \
    {22U, "E8007", "extra field '{field}' in class '{classId}'", arm_parameters_22, 2U, \
     DEAL_ARM_SCOPE_TOP_LEVEL, 0U, 0U}, \
    {23U, "E8001", "JSON parse error at position {oneBasedByteOffset}: {reason}", arm_parameters_23, 2U, \
     DEAL_ARM_SCOPE_TOP_LEVEL, 0U, 0U}, \
    {24U, "E8001", "value at {fieldPath} is not JSON serializable: {actual}", arm_parameters_24, 2U, \
     DEAL_ARM_SCOPE_TOP_LEVEL, 0U, 1U}, \
    {25U, "E8001", "unsupported type for JSON encoding: {actual}", arm_parameters_25, 1U, \
     DEAL_ARM_SCOPE_TOP_LEVEL, 1U, 1U}, \
    {26U, "E8001", "cyclic value cannot be encoded as JSON", NULL, 0U, \
     DEAL_ARM_SCOPE_TOP_LEVEL, 0U, 0U}, \
    {27U, "E8001", "substring bounds must be finite integers", NULL, 0U, \
     DEAL_ARM_SCOPE_TOP_LEVEL, 0U, 0U}, \
    {28U, "E8001", "sqrt of negative number", NULL, 0U, \
     DEAL_ARM_SCOPE_TOP_LEVEL, 0U, 1U},

#if DEAL_STUB_CASE == 8
static const deal_arm_record arm_records[] = {
    DEAL_STUB_ARM_RECORDS(99U, arm_parameters_0, 1U, 1U)
};
const deal_arm_table deal_project_arm_table = {DEAL_ARM_TABLE_VERSION,
    DEAL_ARM_REPERTOIRE_COUNT, arm_records};
#elif DEAL_STUB_CASE == 9
static const deal_arm_record arm_records[] = {
    DEAL_STUB_ARM_RECORDS(0U, arm_parameters_0, 1U, 1U)
};
const deal_arm_table deal_project_arm_table = {DEAL_ARM_TABLE_VERSION,
    DEAL_ARM_REPERTOIRE_COUNT - 1U, arm_records};
#elif DEAL_STUB_CASE == 10
static const deal_arm_record arm_records[] = {
    DEAL_STUB_ARM_RECORDS(0U, arm_parameters_10, 1U, 1U)
};
const deal_arm_table deal_project_arm_table = {DEAL_ARM_TABLE_VERSION,
    DEAL_ARM_REPERTOIRE_COUNT, arm_records};
#elif DEAL_STUB_CASE == 11
static const deal_arm_record arm_records[] = {
    DEAL_STUB_ARM_RECORDS(0U, arm_parameters_0, 0U, 0U)
};
const deal_arm_table deal_project_arm_table = {DEAL_ARM_TABLE_VERSION,
    DEAL_ARM_REPERTOIRE_COUNT, arm_records};
#else
static const deal_arm_record arm_records[] = {
    DEAL_STUB_ARM_RECORDS(0U, arm_parameters_0, 1U, 1U)
};
const deal_arm_table deal_project_arm_table = {DEAL_ARM_TABLE_VERSION,
    DEAL_ARM_REPERTOIRE_COUNT, arm_records};
#endif

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
    /* The cause is a runtime intermediate across the next allocation, so it is
     * rooted through the scoped root API before the outer Error is created
     * (M5); the parent Error then stores it through the insertion barrier. */
    deal_root_scope_push();
    uint32_t cause_slot = deal_root_scope_hold(cause);
    deal_error *error = deal_error_create("E8010", DEAL_FIXTURE_MESSAGE,
                                          &fixture_origin);
    deal_error_set_expected(error, "string");
    deal_error_set_actual(error, "number");
    deal_error_set_cause(error, cause);
    deal_root_scope_release(cause_slot);
    deal_root_scope_pop();
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
