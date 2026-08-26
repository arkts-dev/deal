/*
 * DEALPG4 nested supervisor: serve/run mode-entry surfaces and the
 * in-process core entry (ISSUE-0242, epic Sequencing step 1).
 *
 * See supervisor.h for the pinned surfaces
 * (dealpg4-supervisor-engine D1-D4, D8). This child owns exactly the
 * mode-entry surfaces and the fail-closed core-entry validation; the
 * engine (subreaper set + read-back, stub fork, ppoll loop, record
 * surface) lands with the next sequencing step. Stage invariants: no
 * fork, no exec, no channel records, no process creation anywhere in
 * this file; validation happens before any side effect; entry failures
 * always exit with the pinned status.
 */
#define _POSIX_C_SOURCE 200809L

#include "supervisor.h"

#include <fcntl.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "monotonic.h"
#include "protocol.h"
#include "selftest.h"

/* === Local surface constants ============================================ */

/* Environment keys of the pinned serve-mode surface (D2/D3): budget,
 * nonce, and invocation id all delivered by the outer at the same fork. */
#define DEALPG4_ENV_BUDGET_MS      "DEALPG4_BUDGET_MS"
#define DEALPG4_ENV_NONCE          "DEALPG4_NONCE"
#define DEALPG4_ENV_INVOCATION_ID  "DEALPG4_INVOCATION_ID"

/* Dispatch's usage exit status (launcher-main.c owns the same value as
 * DEALPG4_EXIT_USAGE); the run entry shares it for its usage errors,
 * and the run containment-failure exit is the same value (D4: exit 2). */
#define DEALPG4_EXIT_USAGE 2

/* The literal "--" argv separator of the pinned serve/run shapes (D3/D4). */
#define DEALPG4_ARGV_SEPARATOR "--"

/* The run-mode --ready-frame flag, valid only at argv[4] (D4). */
#define DEALPG4_RUN_READY_FRAME_FLAG "--ready-frame"

/* Temporary stage refusal for a validated core invocation: nonzero,
 * distinct from the run usage status (2), and not part of the final
 * exit-status map — the engine replaces this body in the next
 * sequencing step. */
#define DEALPG4_SUPERVISOR_STAGE_NOOP_REFUSAL 1

/* === Entry validation helpers =========================================== */

/* Nonce class: exactly DEALPG4_NONCE_HEX_CHARS (32) lowercase hex
 * characters followed by NUL (native-supervisor-containment D9). */
static int dealpg4_nonce_is_valid(const char *s)
{
    size_t i;

    if (s == NULL)
        return 0;
    for (i = 0; i < DEALPG4_NONCE_HEX_CHARS; i++) {
        char c = s[i];

        if (c == '\0')
            return 0;
        if ((c < '0' || c > '9') && (c < 'a' || c > 'f'))
            return 0;
    }
    return s[DEALPG4_NONCE_HEX_CHARS] == '\0';
}

/* Strict decimal int64 parse (1*DIGIT, no sign, no surrounding space,
 * overflow refused) for DEALPG4_BUDGET_MS and DEALPG4_INVOCATION_ID.
 * Returns 0 with *out set, or -1 on a missing input, an empty string,
 * a non-digit character, or a value > INT64_MAX. */
static int dealpg4_parse_decimal_int64(const char *s, int64_t *out)
{
    int64_t value = 0;

    if (s == NULL || *s == '\0')
        return -1;
    for (; *s != '\0'; s++) {
        int digit = *s - '0';

        if (digit < 0 || digit > 9)
            return -1;
        if (value > (INT64_MAX - digit) / 10)
            return -1;
        value = value * 10 + digit;
    }
    *out = value;
    return 0;
}

/* Run-mode usage message (D4): usage errors print it on stderr and exit
 * 2, no fork. The program path matches dispatch's usage text. */
static void dealpg4_run_print_usage(void)
{
    fprintf(stderr,
            "usage: tools/deal-process-launcher-linux-x86_64 run "
            "<nonce> <cwd> [--ready-frame] -- <argv...>\n"
            "<nonce> = exactly 32 lowercase hex characters\n");
}

/* Restore the captured F_GETFL flag sets on stdout/stderr (D8 fd-flag
 * discipline: the originals are restored before every exit on this
 * child's exit paths; later stages keep the discipline). */
static void dealpg4_run_restore_stdio_flags(int stdout_flags,
                                            int stderr_flags)
{
    if (stdout_flags != -1)
        (void)fcntl(1, F_SETFL, stdout_flags);
    if (stderr_flags != -1)
        (void)fcntl(2, F_SETFL, stderr_flags);
}

/* === Exec-hygiene capture (D8) ========================================== */

/* The process signal mask observed at core entry, retained for the
 * stub's immediate pre-execvp restore: the engine blocks SIGCHLD for
 * the signalfd loop, the block is inherited across fork(2) and
 * preserved across execve(2), so the stub restores exactly this
 * captured entry mask before its single execvp — the exec'd target
 * runs with the mask the process was invoked with. Captured by the
 * core entry after validation and before any mask mutation (the
 * SIGCHLD block lands with the loop in the next sequencing step). */
static sigset_t dealpg4_supervisor_entry_mask;

/* === Core entry ========================================================= */

int dealpg4_supervise_core(const char **argv, const char *cwd,
                           const char nonce[33], int64_t invocation_id,
                           int64_t budget_t, int control_fd,
                           int emit_ready_frame)
{
    /* The engine consumes emit_ready_frame (run --ready-frame: print
     * "DEALPG4 STARTED <nonce>" once exec is confirmed); no behavior
     * exists at this stage. */
    (void)emit_ready_frame;

    /* Fail-closed core-entry validation, before any side effect (D1).
     * The mode entries validate the same shapes on their surfaces; the
     * core re-validates so in-process callers (the selftest battery)
     * get the same guarantees. */
    if (argv == NULL || argv[0] == NULL || cwd == NULL)
        return DEALPG4_EXIT_CONFIG_INVALID;
    if (budget_t < DEALPG4_PROBE_SELFTEST_LIMIT_MS_FLOOR
        || budget_t > DEALPG4_LAUNCHER_OVERALL_TIMEOUT_MS)
        return DEALPG4_EXIT_CONFIG_INVALID;
    if (dealpg4_nonce_is_valid(nonce) == 0)
        return DEALPG4_EXIT_CONFIG_INVALID;
    if (control_fd >= 0) {
        if (invocation_id < 1)
            return DEALPG4_EXIT_CONFIG_INVALID;
    } else if (control_fd == -1) {
        if (invocation_id != 0)
            return DEALPG4_EXIT_CONFIG_INVALID;
    } else {
        /* control_fd < -1 is no valid binding. */
        return DEALPG4_EXIT_CONFIG_INVALID;
    }

    /* Write-side signal discipline (D8): SIGPIPE ignored before any
     * write-capable fd is used, so every supervisor-side write(2)
     * observes peer loss as an EPIPE error return — channel loss,
     * never process death. Repeated here (idempotent) so in-process
     * core callers get the same guarantee as the mode entries. */
    (void)signal(SIGPIPE, SIG_IGN);

    /* Exec-hygiene entry-mask capture (D8), before SIGCHLD is ever
     * blocked (that block lands with the signalfd loop). With a NULL
     * set the call only queries the current mask and cannot fail. */
    (void)sigprocmask(SIG_SETMASK, NULL, &dealpg4_supervisor_entry_mask);

    /* The engine lands here (subreaper set + read-back, stub fork,
     * ppoll loop, records). Until then the validated core refuses with
     * the temporary stage status: no fork, no channel writes, no
     * records, no process creation. */
    return DEALPG4_SUPERVISOR_STAGE_NOOP_REFUSAL;
}

/* === Serve entry ======================================================== */

int dealpg4_serve_entry(int argc, char **argv)
{
    const char *budget_env;
    const char *nonce_env;
    const char *id_env;
    int64_t budget_t;
    int64_t invocation_id;
    int fd_flags;
    int fd_status_flags;

    /* D8 write-side discipline first: no write in this entry — and no
     * write the stub later inherits — may die on SIGPIPE. */
    (void)signal(SIGPIPE, SIG_IGN);

    /* argv shape (D3): "launcher serve <cwd> -- <argv...>".
     * argv[0] = program path, argv[1] = "serve", argv[2] = cwd,
     * argv[3] = "--", argv[4..] = target argv (argv[0] = the program).
     * A missing "--" (argv[3] mismatch), an empty target argv
     * (argc < 5), or an empty cwd is a CONFIG_INVALID-class entry
     * failure: exit 3, no fork, no records, nothing written to the
     * channel or to the supervisor's own stdio. */
    if (argc < 5)
        return DEALPG4_EXIT_CONFIG_INVALID;
    if (argv[2][0] == '\0')
        return DEALPG4_EXIT_CONFIG_INVALID;
    if (strcmp(argv[3], DEALPG4_ARGV_SEPARATOR) != 0)
        return DEALPG4_EXIT_CONFIG_INVALID;

    /* env (D2-D4): budget, nonce, and invocation id — all three
     * validated before any further side effect. */
    budget_env = getenv(DEALPG4_ENV_BUDGET_MS);
    if (budget_env == NULL
        || dealpg4_parse_decimal_int64(budget_env, &budget_t) != 0
        || budget_t < DEALPG4_PROBE_SELFTEST_LIMIT_MS_FLOOR
        || budget_t > DEALPG4_LAUNCHER_OVERALL_TIMEOUT_MS)
        return DEALPG4_EXIT_CONFIG_INVALID;
    nonce_env = getenv(DEALPG4_ENV_NONCE);
    if (dealpg4_nonce_is_valid(nonce_env) == 0)
        return DEALPG4_EXIT_CONFIG_INVALID;
    id_env = getenv(DEALPG4_ENV_INVOCATION_ID);
    if (id_env == NULL
        || dealpg4_parse_decimal_int64(id_env, &invocation_id) != 0
        || invocation_id < 1)
        return DEALPG4_EXIT_CONFIG_INVALID;

    /* fd binding (D3): fd 0 is the inherited per-invocation control
     * channel; O_NONBLOCK|FD_CLOEXEC are set at entry, before the core
     * runs. A failure to bind the surface is a CONFIG_INVALID-class
     * entry failure (exit 3, no records) — the outer observes
     * control-channel EOF before any record. */
    fd_flags = fcntl(0, F_GETFL);
    if (fd_flags == -1)
        return DEALPG4_EXIT_CONFIG_INVALID;
    if (fcntl(0, F_SETFL, fd_flags | O_NONBLOCK) == -1)
        return DEALPG4_EXIT_CONFIG_INVALID;
    fd_status_flags = fcntl(0, F_GETFD);
    if (fd_status_flags == -1)
        return DEALPG4_EXIT_CONFIG_INVALID;
    if (fcntl(0, F_SETFD, fd_status_flags | FD_CLOEXEC) == -1)
        return DEALPG4_EXIT_CONFIG_INVALID;

    /* Delegate to the core: target argv starts at argv[4] (argv[0] =
     * the program), cwd = argv[2], nonce from the environment, the
     * parsed invocation id and budget, control_fd = 0 (fd 0), and
     * serve mode has no ready frame. The returned status is the serve
     * exit status: CONFIG_INVALID-class 3; the capability-class
     * statuses map by passthrough (exit 4,
     * DEALPG4_EXIT_CAPABILITY_MISSING) once the engine's entry
     * capability checks land. */
    return dealpg4_supervise_core((const char **)&argv[4], argv[2],
                                  nonce_env, invocation_id, budget_t,
                                  0, 0);
}

/* === Run entry ========================================================== */

int dealpg4_run_entry(int argc, char **argv)
{
    int ready_frame = 0;
    int stdout_flags = -1;
    int stderr_flags = -1;
    int status;

    /* D8 write-side discipline first: the usage message below is a
     * write-capable fd use, and every later-stage write path inherits
     * the ignored disposition. */
    (void)signal(SIGPIPE, SIG_IGN);

    /* argv shape (D4): "launcher run <nonce> <cwd> [--ready-frame]
     * -- <argv...>". argv[0] = program path, argv[1] = "run",
     * argv[2] = nonce (exactly 32 lowercase hex), argv[3] = cwd
     * (non-empty), optional argv[4] == "--ready-frame" only in that
     * position, then "--", then a non-empty target argv. Every shape
     * error — including a malformed nonce — is a usage error: usage
     * message on stderr, exit 2 (dispatch's usage status), no fork. */
    if (argc < 6) {
        dealpg4_run_print_usage();
        return DEALPG4_EXIT_USAGE;
    }
    if (dealpg4_nonce_is_valid(argv[2]) == 0) {
        dealpg4_run_print_usage();
        return DEALPG4_EXIT_USAGE;
    }
    if (argv[3][0] == '\0') {
        dealpg4_run_print_usage();
        return DEALPG4_EXIT_USAGE;
    }
    if (strcmp(argv[4], DEALPG4_ARGV_SEPARATOR) == 0) {
        /* Target argv starts at argv[5]; argc >= 6 guarantees a
         * non-empty target argv. */
    } else if (strcmp(argv[4], DEALPG4_RUN_READY_FRAME_FLAG) == 0) {
        ready_frame = 1;
        if (argc < 7 || strcmp(argv[5], DEALPG4_ARGV_SEPARATOR) != 0) {
            dealpg4_run_print_usage();
            return DEALPG4_EXIT_USAGE;
        }
    } else {
        dealpg4_run_print_usage();
        return DEALPG4_EXIT_USAGE;
    }

    /* fd flags (D8): capture F_GETFL for stdout/stderr before enabling
     * O_NONBLOCK on both; the original flag sets are restored before
     * every exit on this child's exit paths (the discipline extends to
     * all later-stage exits). A failure to bind the stdio surface is a
     * capability-class entry refusal: the named token on stderr, exit
     * 2 (the pinned run entry-refusal shape). */
    stdout_flags = fcntl(1, F_GETFL);
    stderr_flags = fcntl(2, F_GETFL);
    if (stdout_flags == -1 || stderr_flags == -1) {
        fprintf(stderr, "CAPABILITY_MISSING\n");
        return DEALPG4_EXIT_USAGE;
    }
    if (fcntl(1, F_SETFL, stdout_flags | O_NONBLOCK) == -1
        || fcntl(2, F_SETFL, stderr_flags | O_NONBLOCK) == -1) {
        dealpg4_run_restore_stdio_flags(stdout_flags, stderr_flags);
        fprintf(stderr, "CAPABILITY_MISSING\n");
        return DEALPG4_EXIT_USAGE;
    }

    /* budget/env (D4): T = embedded overallTimeoutMs; any
     * DEALPG4_BUDGET_MS value present in the environment is ignored
     * (run mode never reads it). invocation_id = 0, control_fd = -1 —
     * no channel, no id-bearing records; emit_ready_frame = the parsed
     * flag. */
    status = dealpg4_supervise_core(
        (const char **)&argv[5 + ready_frame], argv[3], argv[2], 0,
        DEALPG4_LAUNCHER_OVERALL_TIMEOUT_MS, -1, ready_frame);

    /* Restore the captured flag sets before every exit. */
    dealpg4_run_restore_stdio_flags(stdout_flags, stderr_flags);

    /* Exit-status map (D4/D8): capability-class entry failures print
     * the named token on stderr and exit 2 (containment failure). The
     * entry capability checks themselves land with the engine; the
     * class mapping is complete here. */
    if (status == DEALPG4_EXIT_CAPABILITY_MISSING) {
        fprintf(stderr, "CAPABILITY_MISSING\n");
        return DEALPG4_EXIT_USAGE;
    }
    return status;
}
