/*
 * test/native/arm_probe.c — the failure-arm-table transport.
 *
 * A driver-shape process entry that installs the module's emitted table from the
 * start call and then asserts the accepted table: the install entry returns 1,
 * the installed table is the module's own table (one definition, stored for the
 * materialization entry), the table carries the complete closed arm repertoire
 * — DEAL_ARM_REPERTOIRE_COUNT records in canonical arm order, each with its own
 * id, code, template, declared parameter names, render scope and declared
 * expected/actual fields — and re-installing it is idempotent. The record/module
 * stub is `module_stub.c` case 0.
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
    check(installed->count == DEAL_ARM_REPERTOIRE_COUNT,
          "the accepted table carries the complete closed arm repertoire");
    check(installed->records != NULL, "the accepted table carries its records");
    if (installed->count == DEAL_ARM_REPERTOIRE_COUNT && installed->records != NULL) {
        for (uint32_t index = 0; index < installed->count; index += 1U) {
            check(installed->records[index].id == index,
                  "every record keeps its canonical arm id");
        }
        const deal_arm_record *kind = &installed->records[0];
        check(strcmp(kind->code, "E8001") == 0, "record 0 keeps its code");
        check(strcmp(kind->template_text, "expected {kind}") == 0,
              "record 0 keeps its template verbatim");
        check(kind->parameter_names != NULL && kind->parameter_count == 1U
                  && strcmp(kind->parameter_names[0], "kind") == 0,
              "record 0 keeps its declared named parameter");
        check(kind->render_scope == DEAL_ARM_SCOPE_TOP_LEVEL,
              "record 0 is a top-level arm");
        check(kind->has_expected == 1U && kind->has_actual == 1U,
              "record 0 declares both fields");
        const deal_arm_record *unicode = &installed->records[1];
        check(unicode->parameter_count == 0U && unicode->parameter_names == NULL,
              "record 1 declares no parameter");
        const deal_arm_record *utf8 = &installed->records[3];
        check(utf8->render_scope == DEAL_ARM_SCOPE_INNER_ONLY,
              "record 3 is an inner-only arm");
        check(strcmp(utf8->template_text,
                     "expected string, got invalid UTF-8 encoding") == 0,
              "record 3 keeps its template verbatim");
        const deal_arm_record *bounds = &installed->records[6];
        check(strcmp(bounds->code, "E8002") == 0, "record 6 keeps its code");
        check(strcmp(bounds->template_text, "array index out of bounds") == 0,
              "record 6 keeps its template verbatim");
        check(bounds->has_expected == 0U && bounds->has_actual == 0U,
              "record 6 declares no field");
        const deal_arm_record *metadata = &installed->records[21];
        check(metadata->parameter_count == 3U
                  && strcmp(metadata->parameter_names[2], "defaults|fields") == 0,
              "record 21 keeps its declared parameter names");
        const deal_arm_record *sqrt = &installed->records[28];
        check(strcmp(sqrt->code, "E8001") == 0
                  && sqrt->has_expected == 0U && sqrt->has_actual == 1U,
              "record 28 declares its actual field only");
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
