/*
 * test/native/module_stub_partial.c — a module stub that omits five of the six
 * export-surface definitions of `deal/native/entry.h` (C2, H6).
 *
 * Linking it must fail with an undefined reference naming the missing
 * definitions, so the entry surface is owned by exactly one definition per
 * symbol in each build shape and never silently dropped.
 */
#include "deal/native/entry.h"
#include "deal/native/error.h"

static const deal_arm_record arm_records[] = {
    {0U, "E8001", "cannot store {name}", NULL, 0U,
     DEAL_ARM_SCOPE_TOP_LEVEL, 1U, 0U}
};

const deal_arm_table deal_project_arm_table = {DEAL_ARM_TABLE_VERSION, 1U,
                                               arm_records};

deal_outcome deal_project_start(void) {
    deal_outcome outcome;
    outcome.error = NULL;
    (void)deal_arm_table_install(&deal_project_arm_table);
    return outcome;
}
