/*
 * test/native/arm_probe.c — the failure-arm-table transport.
 *
 * A driver-shape process entry that installs the module's emitted table from the
 * start call and then asserts the accepted table: the install entry returns 1,
 * the installed table is the module's own table (one definition, stored for the
 * later materialization entry), and every record field arrives unchanged in the
 * pinned record order. The record/module stub is `module_stub.c` case 0.
 */
#include "deal/native/entry.h"
#include "deal/native/error.h"
#include "deal/native/platform.h"

#include <stdio.h>
#include <string.h>

static int failures;

static void check(int condition, const char *what) {
    if (!condition) {
        printf("FAIL: %s\n", what);
        failures += 1;
    }
}

int main(void) {
    deal_outcome started = deal_project_start();
    if (started.error != NULL) {
        const char *code = NULL;
        (void)deal_error_code(started.error, &code);
        printf("FAIL: the start call carried an error: %s\n",
               code != NULL ? code : "?");
        return 1;
    }
    const deal_arm_table *installed = deal_arm_table_installed();
    check(installed == &deal_project_arm_table,
          "the installed table is the module's own table");
    if (installed == NULL) {
        printf("arm FAILED: %d\n", failures);
        return 1;
    }
    check(installed->version == DEAL_ARM_TABLE_VERSION,
          "the accepted table version is the supported one");
    check(installed->count == 2U, "the accepted table carries both records");
    check(installed->records != NULL, "the accepted table carries its records");
    if (installed->count == 2U && installed->records != NULL) {
        const deal_arm_record *first = &installed->records[0];
        const deal_arm_record *second = &installed->records[1];
        check(first->id == 0U, "record 0 keeps its id");
        check(strcmp(first->code, "E8001") == 0, "record 0 keeps its code");
        check(strcmp(first->template_text, "cannot store {name}") == 0,
              "record 0 keeps its template verbatim");
        check(first->parameter_names != NULL
                  && first->parameter_count == 1U
                  && strcmp(first->parameter_names[0], "name") == 0,
              "record 0 keeps its declared named parameter");
        check(first->render_scope == DEAL_ARM_SCOPE_TOP_LEVEL,
              "record 0 is a top-level arm");
        check(first->has_expected == 1U && first->has_actual == 0U,
              "record 0 declares expected but not actual");
        check(second->id == 1U, "record 1 keeps its id");
        check(strcmp(second->code, "E8002") == 0, "record 1 keeps its code");
        check(second->render_scope == DEAL_ARM_SCOPE_INNER_ONLY,
              "record 1 is an inner-only arm");
        check(second->has_expected == 0U && second->has_actual == 1U,
              "record 1 declares actual but not expected");
    }
    check(deal_arm_table_install(&deal_project_arm_table) == 1,
          "re-installing the accepted table is idempotent");
    if (failures != 0) {
        printf("arm FAILED: %d\n", failures);
        return 1;
    }
    printf("arm ok\n");
    deal_platform_flush_stdout();
    return 0;
}
