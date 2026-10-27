/*
 * deal/native/entry.c — the retained terminal projection (`Terminal`, R9).
 *
 * Calls the emitted module through the entry.h prototypes and reads no module
 * structure: it owns `deal_project_main` and the terminal lines only. The
 * process entry itself belongs to `main.c` (product build) or to a generated
 * driver unit (driver build).
 */
#include "deal/native/entry.h"
#include "deal/native/error.h"
#include "deal/native/platform.h"

#include <string.h>

/* The two pinned terminal lines and the exit status of an uncaught Error. */
static int deal_terminal_error(deal_error *error) {
    const char *code = NULL;
    if (!deal_error_code(error, &code) || code == NULL) {
        code = "";
    }
    static const char code_prefix[] = "DEAL_ERROR_CODE: ";
    static const char snapshot_prefix[] = "DEAL_ERROR_SNAPSHOT: ";
    char *snapshot = deal_error_snapshot(error);
    deal_platform_write_stdout(code_prefix, sizeof code_prefix - 1);
    deal_platform_write_stdout(code, strlen(code));
    deal_platform_write_stdout("\n", 1);
    deal_platform_write_stdout(snapshot_prefix, sizeof snapshot_prefix - 1);
    deal_platform_write_stdout(snapshot, strlen(snapshot));
    deal_platform_write_stdout("\n", 1);
    deal_platform_flush_stdout();
    deal_platform_flush_stderr();
    deal_error_snapshot_release(snapshot);
    deal_platform_exit(1);
    return 1;
}

static int deal_entry_deferred(void) {
    const char *defer = deal_platform_environment("DEAL_DEFER_MAIN");
    return defer != NULL && defer[0] == '1' && defer[1] == '\0';
}

int deal_project_main(void) {
    deal_outcome started = deal_project_start();
    if (started.error != NULL) {
        return deal_terminal_error(started.error);
    }
    if (!deal_entry_deferred()) {
        deal_outcome invoked = deal_project_run_main();
        if (invoked.error != NULL) {
            return deal_terminal_error(invoked.error);
        }
    }
    deal_platform_flush_stdout();
    deal_platform_flush_stderr();
    return 0;
}
