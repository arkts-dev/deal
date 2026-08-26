/*
 * DEALPG4 nested supervisor: serve/run mode-entry surfaces and the
 * in-process core entry (ISSUE-0243, epic Sequencing steps 2-3).
 *
 * See supervisor.h for the pinned surfaces and the stage invariants.
 * This child owns exactly the containment engine: subreaper set +
 * read-back before any fork, entry capability refusal, the blocked
 * stub state machine (parent D3 steps 1-8), the status/release/stream
 * pipes, STUB_FORKED through the minimal non-blocking pending slot,
 * the single-threaded ppoll event loop, the phase timerfd/signalfd
 * engine, the bounded drains, the release write, and the STARTED
 * three-condition exec-evidence classification. The record surface
 * (REPORT/CLEAN/FAILED/OUT relay, the channel machine, the proof
 * loop) lands with the channel machine (epic Sequencing step 4), so
 * after the stage's classification the invocation ends with the
 * temporary nonzero stage-terminal status with the stub reaped, the
 * drains at EOF, and every supervisor fd closed.
 *
 * Release authorization at this stage: run mode releases on the
 * verified STUB_IDENTITY nonce ownership (the CLI nonce); serve mode
 * has no ACK handling yet and never releases — serve ends at T1 with
 * the STARTUP_TIMEOUT classification.
 */
#define _GNU_SOURCE
#define _POSIX_C_SOURCE 200809L

#include "supervisor.h"

#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <poll.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/signalfd.h>
#include <sys/wait.h>
#include <unistd.h>

#include "monotonic.h"
#include "protocol.h"
#include "selftest.h"

/* === Local surface constants ============================================ */

/* Environment keys of the pinned serve-mode surface (D2/D3): budget,
 * nonce, and invocation id all delivered by the outer at the same fork;
 * the core re-exports the budget and the nonce into the stub fork
 * environment in both modes (D4/D9). */
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

/* Internal core-refusal status for the timerfd_create entry failure
 * (D8): the serve entry maps it to the capability-class exit 4 and the
 * run entry prints the named token and exits 2. It is not a process
 * exit status of its own. */
#define DEALPG4_SUPERVISOR_ENTRY_TIMER_FAILED 5

/* Temporary stage-terminal status for a classified invocation:
 * nonzero, distinct from the run usage status (2) and the entry
 * refusals (3/4/5), and not part of the final exit-status map — the
 * channel machine replaces it with the record-bearing statuses (D3/D4)
 * in the next sequencing step. */
#define DEALPG4_SUPERVISOR_STAGE_TERMINAL 1

/* Status-pipe line buffer: the raw stub records are catalog records
 * capped at DEALPG4_MAX_LINE_OTHER_BYTES including the LF, so the
 * stored content (before the LF) never exceeds the cap minus one. */
#define DEALPG4_SUPERVISOR_STATUS_BUF_BYTES DEALPG4_MAX_LINE_OTHER_BYTES

/* STUB_FORKED pending-slot line buffer: "DEALPG4 STUB_FORKED" + a
 * 19-digit decimal id + a 10-digit decimal pid + LF fits comfortably. */
#define DEALPG4_SUPERVISOR_CHANNEL_LINE_BYTES 96

/* === Exec-hygiene capture (D8) ========================================== */

/* The process signal mask observed at core entry, retained for the
 * stub's immediate pre-execvp restore: the engine blocks SIGCHLD for
 * the signalfd loop, the block is inherited across fork(2) and
 * preserved across execve(2), so the stub restores exactly this
 * captured entry mask before its single execvp — the exec'd target
 * runs with the mask the process was invoked with. Captured by the
 * core entry after validation and before the SIGCHLD block. */
static sigset_t dealpg4_supervisor_entry_mask;

/* === In-process observability (selftest battery, scratch drivers) ======= */

static dealpg4_drain_ctx dealpg4_supervisor_drain_stdout_ctx;
static dealpg4_drain_ctx dealpg4_supervisor_drain_stderr_ctx;
static dealpg4_supervise_result dealpg4_supervisor_last_result;

void dealpg4_supervise_last_result(dealpg4_supervise_result *out)
{
    if (out != NULL)
        *out = dealpg4_supervisor_last_result;
}

void dealpg4_supervise_drain_state(const dealpg4_drain_ctx **stdout_ctx,
                                   const dealpg4_drain_ctx **stderr_ctx)
{
    if (stdout_ctx != NULL)
        *stdout_ctx = &dealpg4_supervisor_drain_stdout_ctx;
    if (stderr_ctx != NULL)
        *stderr_ctx = &dealpg4_supervisor_drain_stderr_ctx;
}

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
 * overflow refused) for DEALPG4_BUDGET_MS, DEALPG4_INVOCATION_ID, and
 * the stub's own DEALPG4_BUDGET_MS read. Returns 0 with *out set, or
 * -1 on a missing input, an empty string, a non-digit character, or a
 * value > INT64_MAX. */
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
 * child's exit paths). */
static void dealpg4_run_restore_stdio_flags(int stdout_flags,
                                            int stderr_flags)
{
    if (stdout_flags != -1)
        (void)fcntl(1, F_SETFL, stdout_flags);
    if (stderr_flags != -1)
        (void)fcntl(2, F_SETFL, stderr_flags);
}

/* === Stub-side machinery (parent D3 steps 1-8) ========================== */

/* Everything the forked stub needs. The stub closes every inherited end
 * it does not use immediately after fork, then runs the canonical
 * eight steps; dealpg4_stub_run never returns. */
typedef struct dealpg4_stub_cfg {
    const char **argv;         /* target argv, argv[0] = program */
    const char *cwd;           /* step-7 chdir directory */
    int status_fd;             /* status-pipe write end (the stub's) */
    int status_read_fd;        /* inherited status-pipe read end: closed */
    int release_fd;            /* release-pipe read end (the stub's) */
    int release_write_fd;      /* inherited release-pipe write end: closed */
    int stdout_write_fd;       /* stream write end -> target stdout */
    int stdout_read_fd;        /* inherited stream read end: closed */
    int stderr_write_fd;       /* stream write end -> target stderr */
    int stderr_read_fd;        /* inherited stream read end: closed */
    pid_t supervisor_pid;      /* pre-fork recorded supervisor pid */
} dealpg4_stub_cfg;

/* Guaranteed-delivery stub write (parent D3): STUB_FAILED and
 * STUB_EXEC_FAILED are retried until written. Blocking on EAGAIN is
 * safe: the supervisor keeps the status-pipe read end open until EOF
 * and the stub writes at most a few small records, so the pipe cannot
 * fill. EPIPE is unreachable before EOF; any other failure stops the
 * loop (the record is still paired with the exit in every reachable
 * case). */
static void dealpg4_stub_write_guaranteed(int fd, const char *line,
                                          size_t len)
{
    size_t off = 0;

    while (off < len) {
        ssize_t r = write(fd, line + off, len - off);

        if (r > 0) {
            off += (size_t)r;
            continue;
        }
        if (r < 0 && errno == EINTR)
            continue;
        if (r < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) {
            struct pollfd pfd;

            pfd.fd = fd;
            pfd.events = POLLOUT;
            pfd.revents = 0;
            (void)poll(&pfd, 1, -1);
            continue;
        }
        break; /* EPIPE or any other error: stop */
    }
}

/* The stub's own startup deadline T1s = stub-entry +
 * min(startupTimeoutMs, T - cleanupTotalMs), computed from the
 * embedded LauncherLimits constants and the DEALPG4_BUDGET_MS value
 * inherited from the supervisor fork environment (parent D4). Returns
 * the deadline in ms (never negative). */
static int64_t dealpg4_stub_t1s_ms(void)
{
    const char *budget_env = getenv(DEALPG4_ENV_BUDGET_MS);
    int64_t t = DEALPG4_LAUNCHER_OVERALL_TIMEOUT_MS;
    int64_t cleanup_total = DEALPG4_LAUNCHER_TERM_GRACE_MS
                          + DEALPG4_LAUNCHER_KILL_AND_PROOF_RESERVE_MS
                          + DEALPG4_LAUNCHER_FINALIZATION_RESERVE_MS;
    int64_t t1s = DEALPG4_LAUNCHER_STARTUP_TIMEOUT_MS;

    if (budget_env != NULL)
        (void)dealpg4_parse_decimal_int64(budget_env, &t);
    if (t - cleanup_total < t1s)
        t1s = t - cleanup_total;
    if (t1s < 0)
        t1s = 0;
    return t1s;
}

/* Step 6: block on the release pipe until exactly one release byte,
 * the stub's own T1s deadline, or EOF/error (parent D3). Timeout ->
 * _exit(4); EOF/error -> _exit(5). On the single release byte: disarm
 * the deadline (implicit — the poll returned) and write one
 * DEALPG4 RELEASE_RECV <pid> line to the status pipe (single
 * non-blocking attempt; on EPIPE/EAGAIN the stub proceeds without
 * retry). */
static void dealpg4_stub_release_poll(int release_fd, int status_fd,
                                      pid_t pid)
{
    int64_t t1s = dealpg4_stub_t1s_ms();
    uint64_t deadline = dealpg4_now_ms() + (uint64_t)t1s;
    struct pollfd pfd;
    char byte;

    pfd.fd = release_fd;
    pfd.events = POLLIN;
    for (;;) {
        uint64_t now = dealpg4_now_ms();
        uint64_t remaining = deadline > now ? deadline - now : 0;
        int rc;

        pfd.revents = 0;
        rc = poll(&pfd, 1,
                  remaining > (uint64_t)INT_MAX ? INT_MAX
                                                : (int)remaining);
        if (rc > 0)
            break;
        if (rc == 0) {
            /* EINTR-free timeout: re-check the absolute deadline. */
            if (dealpg4_now_ms() >= deadline)
                _exit(4);
            continue;
        }
        if (errno == EINTR)
            continue;
        _exit(5); /* poll error */
    }
    if (read(release_fd, &byte, 1) != 1)
        _exit(5); /* EOF/error before the release byte */
    /* Release consumed; the step-6 deadline is disarmed. Single-attempt
     * optional publication. */
    {
        char line[48];
        int n = snprintf(line, sizeof line, "DEALPG4 RELEASE_RECV %ld\n",
                         (long)pid);

        if (n > 0 && (size_t)n < sizeof line) {
            ssize_t wr = write(status_fd, line, (size_t)n);

            (void)wr; /* single attempt; proceed on EPIPE/EAGAIN */
        }
    }
}

/* The blocked stub's canonical state machine (parent D3 steps 1-8,
 * verbatim ordering). Never returns. */
static void dealpg4_stub_run(const dealpg4_stub_cfg *cfg)
    __attribute__((noreturn));

static void dealpg4_stub_run(const dealpg4_stub_cfg *cfg)
{
    pid_t pid = getpid();
    char line[96];
    int n;

    /* Close every inherited end the stub does not use (the release-pipe
     * write end immediately after fork, the status-pipe read end, the
     * stream read ends). */
    close(cfg->release_write_fd);
    close(cfg->status_read_fd);
    close(cfg->stdout_read_fd);
    close(cfg->stderr_read_fd);

    /* Step 1: parent-death cascade. A prctl failure removes the layered
     * pre-ACK safety — fail closed with the reserved silent exit 2
     * (pre-release, no write, no exec). */
    if (prctl(PR_SET_PDEATHSIG, SIGKILL) != 0)
        _exit(2);

    /* Step 2: parent recheck against the pre-fork recorded supervisor
     * pid; mismatch -> _exit(2) (no write, no exec). */
    if (getppid() != cfg->supervisor_pid)
        _exit(2);

    /* Step 3: setsid; failure -> STUB_FAILED (guaranteed delivery) +
     * _exit(3). */
    if (setsid() == -1) {
        int err = errno;

        n = snprintf(line, sizeof line, "DEALPG4 STUB_FAILED %ld %d\n",
                     (long)pid, err);
        if (n > 0 && (size_t)n < sizeof line)
            dealpg4_stub_write_guaranteed(cfg->status_fd, line, (size_t)n);
        _exit(3);
    }

    /* Step 4: identity self-check; mismatch -> STUB_FAILED (guaranteed
     * delivery) + _exit(3). */
    if (getsid(0) != pid || getpgid(0) != pid) {
        n = snprintf(line, sizeof line, "DEALPG4 STUB_FAILED %ld %d\n",
                     (long)pid, EPERM);
        if (n > 0 && (size_t)n < sizeof line)
            dealpg4_stub_write_guaranteed(cfg->status_fd, line, (size_t)n);
        _exit(3);
    }

    /* Step 5: one STUB_IDENTITY line (single attempt; the nonce from
     * the inherited DEALPG4_NONCE env — the supervisor setenv'd it in
     * the fork environment). A missing nonce drops the publication:
     * loss of evidence only, never a false STARTED. */
    {
        const char *nonce_env = getenv(DEALPG4_ENV_NONCE);

        if (nonce_env != NULL) {
            n = snprintf(line, sizeof line,
                         "DEALPG4 STUB_IDENTITY %ld %ld %ld %s\n",
                         (long)pid, (long)pid, (long)pid, nonce_env);
            if (n > 0 && (size_t)n < sizeof line) {
                ssize_t wr = write(cfg->status_fd, line, (size_t)n);

                (void)wr; /* single attempt; loss removes evidence only */
            }
        }
    }

    /* Step 6: blocked release poll with the stub's own T1s deadline. */
    dealpg4_stub_release_poll(cfg->release_fd, cfg->status_fd, pid);

    /* Step 7: chdir; failure -> STUB_FAILED (guaranteed delivery) +
     * _exit(6). */
    if (chdir(cfg->cwd) != 0) {
        int err = errno;

        n = snprintf(line, sizeof line, "DEALPG4 STUB_FAILED %ld %d\n",
                     (long)pid, err);
        if (n > 0 && (size_t)n < sizeof line)
            dealpg4_stub_write_guaranteed(cfg->status_fd, line, (size_t)n);
        _exit(6);
    }

    /* Step 8: exactly one execvp, only after the release byte. The
     * stream write ends replace stdout/stderr (dup2 clears FD_CLOEXEC
     * on the target fds; the O_CLOEXEC originals are closed). Exec
     * hygiene (D8): SIGPIPE SIG_DFL and the captured-entry-mask
     * restore immediately before execvp, so the exec'd target's
     * dispositions and mask are untouched. */
    (void)dup2(cfg->stdout_write_fd, 1);
    (void)dup2(cfg->stderr_write_fd, 2);
    close(cfg->stdout_write_fd);
    close(cfg->stderr_write_fd);

    (void)signal(SIGPIPE, SIG_DFL);
    (void)sigprocmask(SIG_SETMASK, &dealpg4_supervisor_entry_mask, NULL);

    execvp(cfg->argv[0], (char *const *)cfg->argv);
    /* Failure: STUB_EXEC_FAILED (guaranteed delivery) + _exit(127). */
    {
        int err = errno;

        n = snprintf(line, sizeof line, "DEALPG4 STUB_EXEC_FAILED %ld %d\n",
                     (long)pid, err);
        if (n > 0 && (size_t)n < sizeof line)
            dealpg4_stub_write_guaranteed(cfg->status_fd, line, (size_t)n);
        _exit(127);
    }
}

/* === Supervisor state and phases ======================================== */

typedef enum dealpg4_supervisor_phase {
    DEALPG4_PHASE_STARTUP = 0, /* waiting for the release; deadline T1 */
    DEALPG4_PHASE_RUN,         /* released; deadline T2 (execution cutoff) */
    DEALPG4_PHASE_TERM,        /* TERM issued; deadline T3 (KILL) */
    DEALPG4_PHASE_KILL,        /* KILL issued; deadline T4 */
    DEALPG4_PHASE_FINALIZE     /* deadline T5 */
} dealpg4_supervisor_phase;

typedef struct dealpg4_supervisor_state {
    /* Invocation surface. */
    const char **argv;
    const char *cwd;
    char nonce[DEALPG4_NONCE_HEX_CHARS + 1];
    int64_t invocation_id;
    int64_t budget_t;
    int control_fd;

    /* Monotonic engine. */
    int64_t t0;
    dealpg4_invocation_deadlines dl;
    dealpg4_deadline_ctx timer;
    int sig_fd;
    dealpg4_supervisor_phase phase;

    /* Escalation record. */
    int term_issued;
    int kill_issued;
    int signals_issued_to_target; /* a supervisor-issued TERM/KILL was
                                     actually delivered */

    /* Stub / identity. */
    pid_t stub_pid;
    pid_t stub_pgid;  /* 0 until a verified identity */
    pid_t stub_sid;
    int identity_seen;
    int identity_verified;
    int identity_failed;

    /* Release. */
    int release_fd; /* supervisor write end */
    int release_write_done;
    int release_write_ok; /* the single write(2) returned 1 */
    int release_recv_pid_match;

    /* Status pipe (supervisor read end) and the raw record line reader. */
    int status_fd;
    char status_buf[DEALPG4_SUPERVISOR_STATUS_BUF_BYTES];
    size_t status_buf_len;
    int status_discarding; /* an overlong raw line: discard until LF */
    int status_eof;
    int stub_failed_seen;
    int stub_exec_failed_seen;

    /* Exec evidence. */
    int eof_liveness; /* 3(i): stub alive and nothing waitable at the
                         first EOF read */

    /* Reaping. */
    int stub_reaped;
    int stub_si_code;
    int stub_si_status;

    /* Drains. */
    dealpg4_drain_ctx *drain_out;
    dealpg4_drain_ctx *drain_err;
    int stream_out_fd;
    int stream_err_fd;

    /* Control channel (serve): the STUB_FORKED minimal pending slot. */
    char ch_line[DEALPG4_SUPERVISOR_CHANNEL_LINE_BYTES];
    size_t ch_len;
    size_t ch_off;
    int ch_pending;
    int channel_lost; /* observed; no cancel semantics at this stage */

    /* Classification and termination. */
    dealpg4_supervise_class classification;
    int done;
} dealpg4_supervisor_state;

/* === Channel write (STUB_FORKED minimal pending slot) =================== */

/* Write the STUB_FORKED record immediately after fork returns (parent
 * D3), non-blocking through the single minimal pending slot: on EAGAIN
 * the record stays pending and the loop flushes it on POLLOUT (the
 * full bounded per-record queue lands with the channel machine). The
 * write never blocks. */
static void dealpg4_supervisor_channel_flush(dealpg4_supervisor_state *state)
{
    if (!state->ch_pending)
        return;
    for (;;) {
        ssize_t r = write(state->control_fd, state->ch_line + state->ch_off,
                          state->ch_len - state->ch_off);

        if (r > 0) {
            state->ch_off += (size_t)r;
            if (state->ch_off >= state->ch_len) {
                state->ch_pending = 0;
                return;
            }
            continue;
        }
        if (r < 0 && errno == EINTR)
            continue;
        if (r < 0 && (errno == EAGAIN || errno == EWOULDBLOCK))
            return; /* keep pending; POLLOUT retries */
        /* EPIPE or any other error: stage-temporary drop (the
         * channel-loss write semantics land with the channel machine);
         * the invocation continues bounded under its own deadlines. */
        state->ch_pending = 0;
        return;
    }
}

static void dealpg4_supervisor_publish_stub_forked(
    dealpg4_supervisor_state *state)
{
    char idbuf[32];
    char pidbuf[32];
    dealpg4_field_value fields[2];
    size_t written = 0;

    if (state->control_fd < 0)
        return; /* run mode: no channel, no id-bearing records */
    snprintf(idbuf, sizeof idbuf, "%lld", (long long)state->invocation_id);
    snprintf(pidbuf, sizeof pidbuf, "%ld", (long)state->stub_pid);
    fields[0].data = idbuf;
    fields[0].len = strlen(idbuf);
    fields[1].data = pidbuf;
    fields[1].len = strlen(pidbuf);
    if (dealpg4_serialize(DEALPG4_REC_STUB_FORKED, fields, 2,
                          state->ch_line, sizeof state->ch_line,
                          &written) != 0)
        return;
    state->ch_len = written;
    state->ch_off = 0;
    state->ch_pending = 1;
    dealpg4_supervisor_channel_flush(state);
}

/* === Identity cross-verification and the release write ================== */

/* /proc/<pid>/stat fields 4/5/6 = ppid/pgrp/session (parent D9
 * cross-verification evidence). */
static int dealpg4_proc_stat_identity(pid_t pid, pid_t *ppid, pid_t *pgrp,
                                      pid_t *session)
{
    char path[64];
    char buf[512];
    char *close_paren;
    char state;
    int pp = 0;
    int pg = 0;
    int se = 0;
    int fd;
    ssize_t n;

    snprintf(path, sizeof path, "/proc/%d/stat", (int)pid);
    fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0)
        return -1;
    n = read(fd, buf, sizeof(buf) - 1);
    close(fd);
    if (n <= 0)
        return -1;
    buf[n] = '\0';
    close_paren = strrchr(buf, ')');
    if (close_paren == NULL)
        return -1;
    if (sscanf(close_paren + 1, " %c %d %d %d", &state, &pp, &pg, &se)
        != 4)
        return -1;
    (void)state;
    *ppid = (pid_t)pp;
    *pgrp = (pid_t)pg;
    *session = (pid_t)se;
    return 0;
}

/* The single release write (parent D3/D9): exactly one write(2) of one
 * byte on the O_NONBLOCK write end, only after the verified identity
 * and only before T1. The observed-success fact (write returned 1) is
 * recorded; any other result is classified by the EOF-before-release
 * rule. The successful write ends the startup phase. */
static void dealpg4_supervisor_release(dealpg4_supervisor_state *state)
{
    char byte = 'R';
    ssize_t r;

    if (state->release_write_done)
        return; /* at most once, by construction */
    state->release_write_done = 1;
    r = write(state->release_fd, &byte, 1);
    if (r == 1) {
        state->release_write_ok = 1;
        state->phase = DEALPG4_PHASE_RUN;
        (void)dealpg4_deadline_arm(&state->timer,
                                   (uint64_t)state->dl.t2);
    }
}

/* STUB_IDENTITY cross-verification (parent D9): nonce echo equality,
 * getpgid/getsid agreement, /proc/<pid>/stat fields 4/5/6 agreement,
 * reserved PID/PGID/session rejection, and pid == stubPid (setsid
 * makes pgid == sid == stubPid). Only a verified identity may precede
 * the release. A failed verification terminates the invocation
 * AUTH_FAILED with the retained stub killed pre-release (TERM now,
 * KILL at the absolute T3). */
static void dealpg4_supervisor_identity(dealpg4_supervisor_state *state,
                                        const dealpg4_parsed *p)
{
    int64_t pid = 0;
    int64_t pgid = 0;
    int64_t sid = 0;
    const dealpg4_field_slice *f;
    int ok = 1;

    f = dealpg4_parsed_field(p, 0);
    if (f == NULL || dealpg4_field_decimal(f, &pid) == 0)
        ok = 0;
    f = dealpg4_parsed_field(p, 1);
    if (f == NULL || dealpg4_field_decimal(f, &pgid) == 0)
        ok = 0;
    f = dealpg4_parsed_field(p, 2);
    if (f == NULL || dealpg4_field_decimal(f, &sid) == 0)
        ok = 0;
    f = dealpg4_parsed_field(p, 3);

    if (ok && (pid < 1 || pgid < 1 || sid < 1))
        ok = 0; /* reserved PID/PGID/session values */
    if (ok && (pid != (int64_t)state->stub_pid
               || pgid != (int64_t)state->stub_pid
               || sid != (int64_t)state->stub_pid))
        ok = 0; /* setsid: pgid == sid == stubPid */
    if (ok && (getpgid(state->stub_pid) != (pid_t)pgid
               || getsid(state->stub_pid) != (pid_t)sid))
        ok = 0;
    if (ok) {
        pid_t ppid = 0;
        pid_t pgrp = 0;
        pid_t session = 0;

        if (dealpg4_proc_stat_identity(state->stub_pid, &ppid, &pgrp,
                                       &session) != 0
            || ppid != getpid() || pgrp != (pid_t)pgid
            || session != (pid_t)sid)
            ok = 0;
    }
    if (ok && (f == NULL || f->len != DEALPG4_NONCE_HEX_CHARS
               || memcmp(f->p, state->nonce, DEALPG4_NONCE_HEX_CHARS)
                  != 0))
        ok = 0;

    if (!ok) {
        state->identity_failed = 1;
        if (state->classification == DEALPG4_SUP_CLASS_NONE) {
            state->classification = DEALPG4_SUP_CLASS_AUTH_FAILED;
            if (!state->stub_reaped
                && kill(state->stub_pid, SIGTERM) == 0)
                state->signals_issued_to_target = 1;
        }
        return;
    }

    state->identity_verified = 1;
    state->stub_pgid = (pid_t)pgid;
    state->stub_sid = (pid_t)sid;

    /* Release authorization at this stage: run mode (control_fd == -1)
     * releases on the verified identity nonce ownership; serve mode has
     * no ACK handling and never releases. Never a release byte after
     * T1 without an observed successful release write. */
    if (state->control_fd == -1
        && state->phase == DEALPG4_PHASE_STARTUP
        && state->classification == DEALPG4_SUP_CLASS_NONE
        && (int64_t)dealpg4_now_ms() < state->dl.t1)
        dealpg4_supervisor_release(state);
}

/* === Status-pipe raw records and the exec-confirmation evaluation ======= */

static void dealpg4_supervisor_evaluate(dealpg4_supervisor_state *state);

static void dealpg4_supervisor_status_line(dealpg4_supervisor_state *state,
                                           const char *line, size_t len)
{
    dealpg4_parsed p;

    if (dealpg4_parse(line, len, &p) != DEALPG4_PARSE_OK)
        return; /* malformed raw record: loss of evidence only */
    switch (p.type) {
    case DEALPG4_REC_STUB_IDENTITY:
        if (!state->identity_seen) {
            state->identity_seen = 1;
            dealpg4_supervisor_identity(state, &p);
        }
        break;
    case DEALPG4_REC_STUB_FAILED:
        state->stub_failed_seen = 1;
        if (state->classification == DEALPG4_SUP_CLASS_NONE)
            state->classification = DEALPG4_SUP_CLASS_STUB_BOOTSTRAP_FAILED;
        break;
    case DEALPG4_REC_STUB_EXEC_FAILED:
        state->stub_exec_failed_seen = 1;
        if (state->classification == DEALPG4_SUP_CLASS_NONE)
            state->classification = DEALPG4_SUP_CLASS_EXEC_FAILED;
        break;
    case DEALPG4_REC_RELEASE_RECV: {
        int64_t pid = 0;
        const dealpg4_field_slice *f = dealpg4_parsed_field(&p, 0);

        if (f != NULL && dealpg4_field_decimal(f, &pid) != 0
            && pid == (int64_t)state->stub_pid)
            state->release_recv_pid_match = 1;
        /* A mismatched pid is ignored: treated as absent. */
        break;
    }
    default:
        break; /* any other raw record: loss of evidence only */
    }
}

/* Feed status-pipe bytes into the line reader; every complete raw
 * record line is consumed as it arrives. */
static void dealpg4_supervisor_status_feed(dealpg4_supervisor_state *state,
                                           const char *data, size_t n)
{
    size_t i;

    for (i = 0; i < n; i++) {
        char c = data[i];

        if (state->status_discarding) {
            if (c == '\n') {
                state->status_discarding = 0;
                state->status_buf_len = 0;
            }
            continue;
        }
        if (c == '\n') {
            /* Store the LF as the line terminator (the parser requires
             * the complete record including its LF); the buffer holds
             * at most cap-1 content bytes plus the terminator. */
            state->status_buf[state->status_buf_len] = '\n';
            dealpg4_supervisor_status_line(
                state, state->status_buf, state->status_buf_len + 1);
            state->status_buf_len = 0;
            continue;
        }
        if (state->status_buf_len + 1 >= sizeof(state->status_buf)) {
            state->status_discarding = 1; /* overlong line */
            state->status_buf_len = 0;
            continue;
        }
        state->status_buf[state->status_buf_len++] = c;
    }
}

/* Status-pipe EOF: the terminal observation that wakes the loop and
 * triggers the exec-confirmation evaluation (parent D3). The 3(i)
 * liveness check — kill(stubPid, 0) == 0 while waitid(P_PID, stubPid,
 * WEXITED|WNOHANG) reports nothing waitable — runs immediately at the
 * first EOF read, before any pending SIGCHLD for the stub pid is
 * reaped. */
static void dealpg4_supervisor_status_eof(dealpg4_supervisor_state *state)
{
    state->status_eof = 1;
    if (state->status_fd >= 0)
        close(state->status_fd);
    state->status_fd = -1;

    if (state->release_write_ok && !state->stub_failed_seen
        && !state->stub_exec_failed_seen && !state->stub_reaped
        && state->classification == DEALPG4_SUP_CLASS_NONE) {
        siginfo_t si;
        int alive;
        int nothing_waitable;

        memset(&si, 0, sizeof(si));
        alive = (kill(state->stub_pid, 0) == 0);
        nothing_waitable = (waitid(P_PID, state->stub_pid, &si,
                                   WEXITED | WNOHANG) == 0
                            && si.si_pid == 0);
        state->eof_liveness = alive && nothing_waitable;
        /* WNOHANG does not prevent the reap: when the stub had already
         * exited at the EOF read, this waitid reaps it and reports its
         * status. That status is the positive exec evidence the D3
         * conditions 3(ii)/3(iii) consume, so it must be retained
         * exactly like the reap loop does — discarding it would strand
         * the classification until the phase deadlines fire with no
         * stub left to reap (the pending SIGCHLD would then drain
         * against ECHILD). */
        if (!state->eof_liveness && si.si_pid == state->stub_pid) {
            state->stub_reaped = 1;
            state->stub_si_code = si.si_code;
            state->stub_si_status = si.si_status;
        }
    }
    dealpg4_supervisor_evaluate(state);
}

static void dealpg4_supervisor_read_status(dealpg4_supervisor_state *state)
{
    char chunk[512];

    if (state->status_fd < 0)
        return;
    for (;;) {
        ssize_t r = read(state->status_fd, chunk, sizeof chunk);

        if (r > 0) {
            dealpg4_supervisor_status_feed(state, chunk, (size_t)r);
            continue;
        }
        if (r == 0) {
            dealpg4_supervisor_status_eof(state);
            return;
        }
        if (errno == EINTR)
            continue;
        return; /* EAGAIN */
    }
}

/* === Reaping and classification ========================================= */

/* SIGCHLD events drive waitid(P_ALL, WEXITED|WNOHANG) until ECHILD /
 * nothing waitable (parent D1/D6): direct and adopted waitable
 * children are reaped, the stub's waitid status is retained for the
 * D3 classification, and no zombie of this supervisor remains. */
static void dealpg4_supervisor_reap(dealpg4_supervisor_state *state)
{
    for (;;) {
        siginfo_t si;

        memset(&si, 0, sizeof(si));
        if (waitid(P_ALL, 0, &si, WEXITED | WNOHANG) != 0)
            break; /* ECHILD */
        if (si.si_pid == 0)
            break; /* nothing waitable */
        if (si.si_pid == state->stub_pid && !state->stub_reaped) {
            state->stub_reaped = 1;
            state->stub_si_code = si.si_code;
            state->stub_si_status = si.si_status;
        }
        /* Any other child (adopted descendant) is reaped here; its
         * status is not part of the stage classification. */
    }
    dealpg4_supervisor_evaluate(state);
}

/* The D3 exec-confirmation evaluation (release write success, EOF
 * without STUB_FAILED/STUB_EXEC_FAILED, positive exec evidence) and
 * the non-STARTED classification matrix, exactly per parent D3. */
static void dealpg4_supervisor_evaluate(dealpg4_supervisor_state *state)
{
    if (state->classification == DEALPG4_SUP_CLASS_STARTED
        && state->stub_reaped) {
        /* Exec was confirmed; the reaped status is the exec'd target's. */
        switch (state->stub_si_code) {
        case CLD_EXITED:
            state->classification = DEALPG4_SUP_CLASS_SUCCESS;
            break;
        case CLD_KILLED:
        case CLD_DUMPED:
            if (state->signals_issued_to_target)
                state->classification =
                    DEALPG4_SUP_CLASS_EXECUTION_TIMEOUT;
            else
                state->classification =
                    DEALPG4_SUP_CLASS_UNVERIFIED_TARGET_DEATH;
            break;
        default:
            state->classification =
                DEALPG4_SUP_CLASS_UNVERIFIED_TARGET_DEATH;
            break;
        }
        return;
    }
    if (state->classification != DEALPG4_SUP_CLASS_NONE)
        return; /* already classified at a record sighting / deadline */

    if (!state->release_write_ok) {
        /* EOF observed before the release write, or the release write
         * failed: classified from supervisor state plus the reaped
         * stub status (parent D3). The cancel path does not exist at
         * this stage, so exit 5 keeps the STUB_PRE_RELEASE_EXIT
         * meaning. */
        if (!state->status_eof || !state->stub_reaped)
            return;
        switch (state->stub_si_code) {
        case CLD_EXITED:
            if (state->stub_si_status == 4)
                state->classification =
                    DEALPG4_SUP_CLASS_STARTUP_TIMEOUT;
            else
                state->classification =
                    DEALPG4_SUP_CLASS_STUB_PRE_RELEASE_EXIT;
            break;
        case CLD_KILLED:
        case CLD_DUMPED:
            if (state->signals_issued_to_target)
                /* The supervisor's own T1 pre-release TERM. */
                state->classification =
                    DEALPG4_SUP_CLASS_STARTUP_TIMEOUT;
            else
                state->classification =
                    DEALPG4_SUP_CLASS_STUB_PRE_RELEASE_EXIT;
            break;
        default:
            state->classification = DEALPG4_SUP_CLASS_STUB_PRE_RELEASE_EXIT;
            break;
        }
        return;
    }

    /* release_write_ok: condition 1 holds. */
    if (state->stub_failed_seen || state->stub_exec_failed_seen)
        return; /* classified at the record sighting */
    if (!state->status_eof)
        return;
    /* Condition 2 holds: EOF without STUB_FAILED/STUB_EXEC_FAILED. */
    if (state->eof_liveness) {
        /* 3(i): stub liveness at the EOF read. */
        state->classification = DEALPG4_SUP_CLASS_STARTED;
        return;
    }
    if (!state->stub_reaped)
        return; /* no evidence yet: wait for the reaped status */
    switch (state->stub_si_code) {
    case CLD_EXITED:
        if (state->stub_si_status < 2 || state->stub_si_status > 6) {
            /* 3(ii): exit code outside the reserved set (including 127,
             * valid only because condition 2 holds). STARTED holds and
             * the reaped target exited cleanly: final = success. */
            state->classification = DEALPG4_SUP_CLASS_SUCCESS;
        } else if (state->release_recv_pid_match) {
            /* 3(iii): reserved code plus RELEASE_RECV with
             * pid == stubPid before the EOF. */
            state->classification = DEALPG4_SUP_CLASS_SUCCESS;
        } else {
            /* Reserved code without RELEASE_RECV: the pre-release
             * meaning (4 -> the stub's own step-6 deadline, including
             * the release-write race). */
            if (state->stub_si_status == 4)
                state->classification =
                    DEALPG4_SUP_CLASS_STARTUP_TIMEOUT;
            else
                state->classification =
                    DEALPG4_SUP_CLASS_STUB_PRE_RELEASE_EXIT;
        }
        break;
    case CLD_KILLED:
    case CLD_DUMPED:
        if (state->signals_issued_to_target)
            state->classification = DEALPG4_SUP_CLASS_EXECUTION_TIMEOUT;
        else
            state->classification =
                DEALPG4_SUP_CLASS_UNVERIFIED_TARGET_DEATH;
        break;
    default:
        state->classification = DEALPG4_SUP_CLASS_UNVERIFIED_TARGET_DEATH;
        break;
    }
}

/* === Deadlines and signaling =========================================== */

/* Signals target a verified negative PGID (parent D5): getpgrp() !=
 * targetPgid and kill(-targetPgid, 0) == 0 before each signal;
 * unverifiable targets are never signaled. Pre-release the retained
 * stubPid is signaled directly. The signals_issued_to_target record
 * disambiguates the D3 classification (cancel-path TERM / T2-T3
 * deadline signals / no supervisor-issued signal). */
static void dealpg4_supervisor_signal_target(dealpg4_supervisor_state *state,
                                             int sig)
{
    if (state->release_write_ok && state->stub_pgid > 0) {
        pid_t pgid = state->stub_pgid;

        if (getpgrp() != pgid && kill(-pgid, 0) == 0) {
            if (kill(-pgid, sig) == 0)
                state->signals_issued_to_target = 1;
        }
        return; /* unverifiable: never signaled */
    }
    if (state->stub_pid > 0 && kill(state->stub_pid, sig) == 0)
        state->signals_issued_to_target = 1;
}

/* The next applicable absolute phase deadline from the invocation
 * recipe state (monotonic.h). The loop computes its ppoll timeout from
 * this recipe state, never from the timerfd context alone: a failed
 * dealpg4_deadline_arm leaves the previous context state unchanged and
 * an idle context reports 0 remaining, so the recipe-state absolute
 * deadlines remain the escalation's wakeup source (the D8
 * TIMER_FAILED wakeup pattern; the record path itself lands with the
 * channel machine). */
static int64_t dealpg4_supervisor_next_deadline(
    const dealpg4_supervisor_state *state)
{
    switch (state->phase) {
    case DEALPG4_PHASE_STARTUP:
        return state->dl.t1;
    case DEALPG4_PHASE_RUN:
        return state->dl.t2;
    case DEALPG4_PHASE_TERM:
        return state->dl.t3;
    case DEALPG4_PHASE_KILL:
        return state->dl.t4;
    case DEALPG4_PHASE_FINALIZE:
    default:
        return state->dl.t5;
    }
}

/* Phase escalation per the parent D4 recipe. T1 pre-release:
 * STARTUP_TIMEOUT classification and the retained stub killed
 * pre-release (TERM now, KILL at the absolute T3) — never a release
 * byte after T1 without an observed successful release write (the
 * successful write already ended the startup phase). T2: TERM against
 * the verified negative PGID. T3: KILL. T4: the proof deadline — at
 * this stage the full proof loop lands with the channel machine, so an
 * invocation not yet terminated (stub reaped, both drains at EOF)
 * classifies PROOF_TIMEOUT and ends. T5: OVERALL_TIMEOUT. */
static void dealpg4_supervisor_advance_deadlines(
    dealpg4_supervisor_state *state)
{
    uint64_t now = dealpg4_now_ms();
    int64_t n = (int64_t)now;

    if (state->done)
        return;
    if (n >= state->dl.t5) {
        if (state->classification != DEALPG4_SUP_CLASS_OVERALL_TIMEOUT)
            state->classification = DEALPG4_SUP_CLASS_OVERALL_TIMEOUT;
        state->done = 1;
        return;
    }
    if (n >= state->dl.t4) {
        if (!(state->stub_reaped
              && dealpg4_drain_eof(state->drain_out)
              && dealpg4_drain_eof(state->drain_err)))
            state->classification = DEALPG4_SUP_CLASS_PROOF_TIMEOUT;
        state->done = 1;
        return;
    }
    if (n >= state->dl.t3 && state->phase == DEALPG4_PHASE_TERM) {
        state->kill_issued = 1;
        if (!state->stub_reaped)
            dealpg4_supervisor_signal_target(state, SIGKILL);
        state->phase = DEALPG4_PHASE_KILL;
        (void)dealpg4_deadline_arm(&state->timer,
                                   (uint64_t)state->dl.t4);
        return;
    }
    if (n >= state->dl.t2 && state->phase == DEALPG4_PHASE_RUN) {
        state->term_issued = 1;
        if (!state->stub_reaped)
            dealpg4_supervisor_signal_target(state, SIGTERM);
        state->phase = DEALPG4_PHASE_TERM;
        (void)dealpg4_deadline_arm(&state->timer,
                                   (uint64_t)state->dl.t3);
        return;
    }
    if (n >= state->dl.t1 && state->phase == DEALPG4_PHASE_STARTUP) {
        if (!state->release_write_ok
            && state->classification == DEALPG4_SUP_CLASS_NONE) {
            state->classification = DEALPG4_SUP_CLASS_STARTUP_TIMEOUT;
            state->term_issued = 1;
            if (!state->stub_reaped
                && kill(state->stub_pid, SIGTERM) == 0)
                state->signals_issued_to_target = 1;
        }
        state->phase = DEALPG4_PHASE_TERM;
        (void)dealpg4_deadline_arm(&state->timer,
                                   (uint64_t)state->dl.t3);
        return;
    }
}

/* === Event-loop fd roles and handlers =================================== */

typedef enum dealpg4_loop_role {
    DEALPG4_ROLE_TIMER = 0,
    DEALPG4_ROLE_SIGCHLD,
    DEALPG4_ROLE_STATUS,
    DEALPG4_ROLE_STREAM_OUT,
    DEALPG4_ROLE_STREAM_ERR,
    DEALPG4_ROLE_CTRL_READ,
    DEALPG4_ROLE_CTRL_WRITE
} dealpg4_loop_role;

/* Pump one drain on readability (parent D1/D7): repeated non-blocking
 * reads through the drain context until EAGAIN; on EOF (or a read
 * error, stage-temporary) the read end is closed and leaves the poll
 * set. Draining continues past the cap so a saturated target can never
 * deadlock on a full pipe. */
static void dealpg4_supervisor_pump_stream(dealpg4_supervisor_state *state,
                                           int *fd, dealpg4_drain_ctx *drain)
{
    if (*fd < 0)
        return;
    for (;;) {
        dealpg4_drain_status s = dealpg4_drain_pump(drain, *fd);

        if (s == DEALPG4_DRAIN_AGAIN)
            return;
        close(*fd);
        *fd = -1;
        return; /* EOF or error: the read side is done */
    }
}

/* Read the signalfd until EAGAIN, then run the reap loop (parent
 * D1/D6). */
static void dealpg4_supervisor_read_sigchld(dealpg4_supervisor_state *state)
{
    struct signalfd_siginfo fdsi;

    for (;;) {
        ssize_t r = read(state->sig_fd, &fdsi, sizeof fdsi);

        if (r == (ssize_t)sizeof fdsi)
            continue;
        if (r < 0 && errno == EINTR)
            continue;
        break; /* EAGAIN */
    }
    dealpg4_supervisor_reap(state);
}

/* Control-channel read side (serve): stage-temporary read-and-discard —
 * the channel machine (ACK/CANCEL, per-state cancel/caller-loss
 * semantics) lands with epic Sequencing step 4. Reading keeps the fd in
 * the D1 poll set without a POLLIN busy-loop; EOF/HUP is observed but
 * carries no cancel semantics at this stage, so the invocation always
 * continues under its own absolute deadlines. After EOF/HUP the read
 * side leaves the poll set (a peer-closed socket keeps POLLIN ready
 * forever) and the invocation continues under its own deadlines. */
static void dealpg4_supervisor_read_control(dealpg4_supervisor_state *state)
{
    char discard[256];

    if (state->control_fd < 0)
        return;
    for (;;) {
        ssize_t r = read(state->control_fd, discard, sizeof discard);

        if (r > 0)
            continue;
        if (r == 0) {
            state->channel_lost = 1;
            return;
        }
        if (errno == EINTR)
            continue;
        return; /* EAGAIN */
    }
}

/* The stage-done test: a terminal classification, the stub reaped, and
 * both drains at EOF (EOF is mandatory; the full proof loop lands with
 * the channel machine). */
static int dealpg4_supervisor_stage_done(
    const dealpg4_supervisor_state *state)
{
    if (state->classification == DEALPG4_SUP_CLASS_NONE
        || state->classification == DEALPG4_SUP_CLASS_STARTED)
        return 0;
    return state->stub_reaped
        && dealpg4_drain_eof(state->drain_out)
        && dealpg4_drain_eof(state->drain_err);
}

/* The single-threaded ppoll event loop (parent D1): timerfd,
 * signalfd(SIGCHLD), both target stream pipes, the stub status-pipe
 * read fd, the control-channel read side, and the control-channel
 * write side whenever the pending record exists. No write path blocks;
 * ppoll always blocks with the earliest applicable recipe-state
 * deadline. The status pipe is handled before the reap loop so the
 * 3(i) liveness check runs at the first EOF read before any pending
 * SIGCHLD for the stub pid is reaped. */
static void dealpg4_supervisor_loop(dealpg4_supervisor_state *state)
{
    for (;;) {
        struct pollfd fds[7];
        dealpg4_loop_role roles[7];
        nfds_t nfds = 0;
        nfds_t i;
        uint64_t now = dealpg4_now_ms();
        int64_t deadline = dealpg4_supervisor_next_deadline(state);
        int64_t remaining = deadline > (int64_t)now
                                ? deadline - (int64_t)now
                                : 0;
        struct timespec ts;
        int rc;

        dealpg4_supervisor_advance_deadlines(state);
        if (state->done)
            return;
        if (dealpg4_supervisor_stage_done(state))
            return;

        dealpg4_ms_to_timespec(
            remaining > (uint64_t)INT_MAX ? (uint64_t)INT_MAX
                                          : (uint64_t)remaining,
            &ts);

        fds[nfds].fd = state->timer.fd;
        fds[nfds].events = POLLIN;
        fds[nfds].revents = 0;
        roles[nfds] = DEALPG4_ROLE_TIMER;
        nfds++;
        /* The status pipe precedes the signalfd in the processing
         * order: the 3(i) liveness check runs at the first EOF read
         * before any pending SIGCHLD for the stub pid is reaped
         * (parent D3). */
        if (state->status_fd >= 0) {
            fds[nfds].fd = state->status_fd;
            fds[nfds].events = POLLIN;
            fds[nfds].revents = 0;
            roles[nfds] = DEALPG4_ROLE_STATUS;
            nfds++;
        }
        fds[nfds].fd = state->sig_fd;
        fds[nfds].events = POLLIN;
        fds[nfds].revents = 0;
        roles[nfds] = DEALPG4_ROLE_SIGCHLD;
        nfds++;
        if (state->stream_out_fd >= 0) {
            fds[nfds].fd = state->stream_out_fd;
            fds[nfds].events = POLLIN;
            fds[nfds].revents = 0;
            roles[nfds] = DEALPG4_ROLE_STREAM_OUT;
            nfds++;
        }
        if (state->stream_err_fd >= 0) {
            fds[nfds].fd = state->stream_err_fd;
            fds[nfds].events = POLLIN;
            fds[nfds].revents = 0;
            roles[nfds] = DEALPG4_ROLE_STREAM_ERR;
            nfds++;
        }
        if (state->control_fd >= 0) {
            if (state->ch_pending) {
                fds[nfds].fd = state->control_fd;
                fds[nfds].events = POLLOUT;
                fds[nfds].revents = 0;
                roles[nfds] = DEALPG4_ROLE_CTRL_WRITE;
                nfds++;
            }
            /* The read side leaves the poll set once channel EOF/HUP
             * was observed: a peer-closed socket keeps POLLIN ready
             * forever, and polling it would busy-spin the loop.
             * Channel loss carries no cancel semantics at this stage
             * (the channel machine owns them), so the invocation
             * continues under its own absolute deadlines. */
            if (!state->channel_lost) {
                fds[nfds].fd = state->control_fd;
                fds[nfds].events = POLLIN;
                fds[nfds].revents = 0;
                roles[nfds] = DEALPG4_ROLE_CTRL_READ;
                nfds++;
            }
        }

        rc = ppoll(fds, nfds, &ts, NULL);
        if (rc < 0) {
            if (errno == EINTR) {
                /* EINTR recompute via the monotonic helpers: re-arm the
                 * identical absolute deadline, never a later one. */
                uint64_t rem = 0;

                (void)dealpg4_deadline_recompute(&state->timer, &rem);
            }
            continue;
        }
        if (rc == 0)
            continue; /* recipe-state timeout: the advance applies it */

        for (i = 0; i < nfds; i++) {
            switch (roles[i]) {
            case DEALPG4_ROLE_TIMER:
                if (fds[i].revents & POLLIN) {
                    uint64_t expirations = 0;

                    (void)dealpg4_deadline_drain(&state->timer,
                                                 &expirations);
                }
                break;
            case DEALPG4_ROLE_STATUS:
                if (fds[i].revents & (POLLIN | POLLHUP))
                    dealpg4_supervisor_read_status(state);
                break;
            case DEALPG4_ROLE_STREAM_OUT:
                if (fds[i].revents & (POLLIN | POLLHUP))
                    dealpg4_supervisor_pump_stream(
                        state, &state->stream_out_fd, state->drain_out);
                break;
            case DEALPG4_ROLE_STREAM_ERR:
                if (fds[i].revents & (POLLIN | POLLHUP))
                    dealpg4_supervisor_pump_stream(
                        state, &state->stream_err_fd, state->drain_err);
                break;
            case DEALPG4_ROLE_SIGCHLD:
                if (fds[i].revents & POLLIN)
                    dealpg4_supervisor_read_sigchld(state);
                break;
            case DEALPG4_ROLE_CTRL_READ:
                if (fds[i].revents & (POLLIN | POLLHUP))
                    dealpg4_supervisor_read_control(state);
                break;
            case DEALPG4_ROLE_CTRL_WRITE:
                if (fds[i].revents & POLLOUT)
                    dealpg4_supervisor_channel_flush(state);
                break;
            default:
                break;
            }
        }
    }
}

/* === Stage termination ================================================== */

/* Bounded post-state at the stage's terminal classification: close the
 * release pipe first (a pre-release stub still polled on it observes
 * EOF and exits 5), reap every waitable child (waitid to ECHILD — no
 * zombie of this supervisor), best-effort drain to EOF, close every
 * supervisor fd, restore the captured entry signal mask (in-process
 * composition: the caller's signal mask is exactly as it was before
 * the core blocked SIGCHLD), and record the observability result. No records are
 * published — the record surface lands with the channel machine. */
static void dealpg4_supervisor_terminate(dealpg4_supervisor_state *state)
{
    if (state->release_fd >= 0) {
        close(state->release_fd);
        state->release_fd = -1;
    }
    dealpg4_supervisor_reap(state);

    if (state->stream_out_fd >= 0) {
        (void)dealpg4_drain_pump(state->drain_out, state->stream_out_fd);
        close(state->stream_out_fd);
        state->stream_out_fd = -1;
    }
    if (state->stream_err_fd >= 0) {
        (void)dealpg4_drain_pump(state->drain_err, state->stream_err_fd);
        close(state->stream_err_fd);
        state->stream_err_fd = -1;
    }
    if (state->status_fd >= 0) {
        close(state->status_fd);
        state->status_fd = -1;
    }
    if (state->sig_fd >= 0) {
        close(state->sig_fd);
        state->sig_fd = -1;
    }
    dealpg4_deadline_close(&state->timer);

    (void)sigprocmask(SIG_SETMASK, &dealpg4_supervisor_entry_mask, NULL);

    dealpg4_supervisor_last_result.stub_pid = state->stub_pid;
    dealpg4_supervisor_last_result.stub_pgid = state->stub_pgid;
    dealpg4_supervisor_last_result.stub_sid = state->stub_sid;
    dealpg4_supervisor_last_result.classification = state->classification;
    dealpg4_supervisor_last_result.reaped_si_code = state->stub_si_code;
    dealpg4_supervisor_last_result.reaped_si_status = state->stub_si_status;
}

/* Close both ends of a pipe (refusal paths). */
static void dealpg4_supervisor_close_pipe(int fds[2])
{
    if (fds[0] >= 0)
        close(fds[0]);
    if (fds[1] >= 0)
        close(fds[1]);
    fds[0] = -1;
    fds[1] = -1;
}

/* === Core entry ========================================================= */

int dealpg4_supervise_core(const char **argv, const char *cwd,
                           const char nonce[33], int64_t invocation_id,
                           int64_t budget_t, int control_fd,
                           int emit_ready_frame)
{
    dealpg4_deadline_ctx timer;
    sigset_t sigchld_set;
    int sig_fd = -1;
    int64_t t0;
    dealpg4_invocation_deadlines dl;
    dealpg4_supervisor_state state;
    dealpg4_stub_cfg cfg;
    int status_pipe[2] = {-1, -1};
    int release_pipe[2] = {-1, -1};
    int out_pipe[2] = {-1, -1};
    int err_pipe[2] = {-1, -1};
    pid_t stub;
    int flags;

    /* The engine consumes emit_ready_frame (run --ready-frame: print
     * "DEALPG4 STARTED <nonce>" once exec is confirmed); the
     * publication lands with the channel machine. */
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
     * blocked. With a NULL set the call only queries the current mask
     * and cannot fail. */
    (void)sigprocmask(SIG_SETMASK, NULL, &dealpg4_supervisor_entry_mask);

    /* Observability reset: refusal paths leave a zeroed result and
     * empty drain contexts. */
    memset(&dealpg4_supervisor_last_result, 0,
           sizeof dealpg4_supervisor_last_result);
    dealpg4_supervisor_last_result.stub_pid = -1;
    dealpg4_drain_init(&dealpg4_supervisor_drain_stdout_ctx);
    dealpg4_drain_init(&dealpg4_supervisor_drain_stderr_ctx);

    /* Subreaper set + read-back before any fork, never assumed
     * inherited (parent D2): failure or unsupported capability is
     * CAPABILITY_MISSING before any command can start. */
    if (prctl(PR_SET_CHILD_SUBREAPER, 1) != 0)
        return DEALPG4_EXIT_CAPABILITY_MISSING;
    {
        int is_subreaper = 0;

        if (prctl(PR_GET_CHILD_SUBREAPER, &is_subreaper) != 0
            || is_subreaper != 1)
            return DEALPG4_EXIT_CAPABILITY_MISSING;
    }

    /* Entry capability wiring (D8): one timerfd created before any
     * fork; timerfd_create failure is the no-record TIMER_FAILED
     * refusal (distinct from the mid-invocation re-arm path). */
    if (dealpg4_deadline_open(&timer) != 0)
        return DEALPG4_SUPERVISOR_ENTRY_TIMER_FAILED;

    /* SIGCHLD is blocked in the process mask before signalfd creation
     * (signalfd(2) delivers only blocked signals); signalfd(SIGCHLD)
     * creation failure is the no-record CAPABILITY_MISSING refusal.
     * The block is inherited by the stub and restored to the captured
     * entry mask immediately before execvp (D8). */
    sigemptyset(&sigchld_set);
    sigaddset(&sigchld_set, SIGCHLD);
    if (sigprocmask(SIG_BLOCK, &sigchld_set, NULL) != 0) {
        dealpg4_deadline_close(&timer);
        return DEALPG4_EXIT_CAPABILITY_MISSING;
    }
    sig_fd = signalfd(-1, &sigchld_set, SFD_NONBLOCK | SFD_CLOEXEC);
    if (sig_fd < 0) {
        dealpg4_deadline_close(&timer);
        (void)sigprocmask(SIG_SETMASK, &dealpg4_supervisor_entry_mask,
                          NULL);
        return DEALPG4_EXIT_CAPABILITY_MISSING;
    }

    /* T0 read at supervisor entry, before any fork (parent D4); the
     * phase deadlines T1-T5 come from the canonical recipe arithmetic
     * over the embedded limits and the validated budget T. */
    t0 = (int64_t)dealpg4_now_ms();
    dealpg4_invocation_recipe(&dealpg4_embedded_launcher_limits, t0,
                              budget_t, &dl);

    memset(&state, 0, sizeof state);
    state.argv = argv;
    state.cwd = cwd;
    memcpy(state.nonce, nonce, DEALPG4_NONCE_HEX_CHARS);
    state.nonce[DEALPG4_NONCE_HEX_CHARS] = '\0';
    state.invocation_id = invocation_id;
    state.budget_t = budget_t;
    state.control_fd = control_fd;
    state.t0 = t0;
    state.dl = dl;
    state.timer = timer;
    state.sig_fd = sig_fd;
    state.phase = DEALPG4_PHASE_STARTUP;
    state.stub_pid = -1;
    state.stub_pgid = 0;
    state.stub_sid = 0;
    state.release_fd = -1;
    state.status_fd = -1;
    state.stream_out_fd = -1;
    state.stream_err_fd = -1;
    state.drain_out = &dealpg4_supervisor_drain_stdout_ctx;
    state.drain_err = &dealpg4_supervisor_drain_stderr_ctx;
    state.classification = DEALPG4_SUP_CLASS_NONE;

    (void)dealpg4_deadline_arm(&state.timer, (uint64_t)dl.t1);

    /* Re-export the budget and the invocation nonce in the stub fork
     * environment in both modes (parent D4/D9): the stub's step-6 T1s
     * uses the same T, and the stub's STUB_IDENTITY echoes the nonce
     * the supervisor cross-verifies. The nonce is copied into
     * state.nonce first (setenv may relocate the environment block).
     * A delivery failure is fail-closed before any fork. */
    {
        char budget_buf[32];
        int n = snprintf(budget_buf, sizeof budget_buf, "%lld",
                         (long long)budget_t);

        if (n <= 0 || (size_t)n >= sizeof budget_buf
            || setenv(DEALPG4_ENV_BUDGET_MS, budget_buf, 1) != 0
            || setenv(DEALPG4_ENV_NONCE, state.nonce, 1) != 0) {
            dealpg4_deadline_close(&state.timer);
            close(sig_fd);
            (void)sigprocmask(SIG_SETMASK,
                              &dealpg4_supervisor_entry_mask, NULL);
            return DEALPG4_EXIT_CAPABILITY_MISSING;
        }
    }

    /* Pipe topology (parent D3/D8): the status pipe carries the raw
     * stub records (stub write end O_NONBLOCK|FD_CLOEXEC, supervisor
     * read end O_NONBLOCK|FD_CLOEXEC); the release pipe carries the
     * single release byte (stub read end FD_CLOEXEC, supervisor write
     * end O_NONBLOCK); the two stream pipes carry the target's
     * stdout/stderr into the drains (read ends O_NONBLOCK; the write
     * ends keep the target's ordinary blocking semantics). */
    if (pipe2(status_pipe, O_NONBLOCK | O_CLOEXEC) != 0
        || pipe2(release_pipe, O_CLOEXEC) != 0
        || pipe2(out_pipe, O_CLOEXEC) != 0
        || pipe2(err_pipe, O_CLOEXEC) != 0) {
        dealpg4_supervisor_close_pipe(status_pipe);
        dealpg4_supervisor_close_pipe(release_pipe);
        dealpg4_supervisor_close_pipe(out_pipe);
        dealpg4_supervisor_close_pipe(err_pipe);
        dealpg4_deadline_close(&state.timer);
        close(sig_fd);
        (void)sigprocmask(SIG_SETMASK, &dealpg4_supervisor_entry_mask,
                          NULL);
        /* Stage-temporary fail-closed refusal (the CLEANUP_FAILED
         * record path lands with the channel machine). */
        return DEALPG4_EXIT_CAPABILITY_MISSING;
    }
    flags = fcntl(out_pipe[0], F_GETFL);
    if (flags >= 0)
        (void)fcntl(out_pipe[0], F_SETFL, flags | O_NONBLOCK);
    flags = fcntl(err_pipe[0], F_GETFL);
    if (flags >= 0)
        (void)fcntl(err_pipe[0], F_SETFL, flags | O_NONBLOCK);

    memset(&cfg, 0, sizeof cfg);
    cfg.argv = argv;
    cfg.cwd = cwd;
    cfg.status_fd = status_pipe[1];
    cfg.status_read_fd = status_pipe[0];
    cfg.release_fd = release_pipe[0];
    cfg.release_write_fd = release_pipe[1];
    cfg.stdout_write_fd = out_pipe[1];
    cfg.stdout_read_fd = out_pipe[0];
    cfg.stderr_write_fd = err_pipe[1];
    cfg.stderr_read_fd = err_pipe[0];
    cfg.supervisor_pid = getpid();

    /* Fork topology (parent D5): the supervisor stays outside the
     * target group (the stub's setsid creates its own session/group);
     * stubPid is recorded immediately. */
    stub = fork();
    if (stub < 0) {
        dealpg4_supervisor_close_pipe(status_pipe);
        dealpg4_supervisor_close_pipe(release_pipe);
        dealpg4_supervisor_close_pipe(out_pipe);
        dealpg4_supervisor_close_pipe(err_pipe);
        dealpg4_deadline_close(&state.timer);
        close(sig_fd);
        (void)sigprocmask(SIG_SETMASK, &dealpg4_supervisor_entry_mask,
                          NULL);
        /* Stage-temporary fail-closed refusal (the STUB_BOOTSTRAP_FAILED
         * record path lands with the channel machine). */
        return DEALPG4_EXIT_CAPABILITY_MISSING;
    }
    if (stub == 0)
        dealpg4_stub_run(&cfg); /* never returns */

    /* Supervisor side: close every stub-owned end, bind the release
     * write end O_NONBLOCK, and publish STUB_FORKED immediately after
     * fork returns. */
    close(status_pipe[1]);
    close(release_pipe[0]);
    close(out_pipe[1]);
    close(err_pipe[1]);
    state.stub_pid = stub;
    state.status_fd = status_pipe[0];
    state.release_fd = release_pipe[1];
    state.stream_out_fd = out_pipe[0];
    state.stream_err_fd = err_pipe[0];
    flags = fcntl(state.release_fd, F_GETFL);
    if (flags >= 0)
        (void)fcntl(state.release_fd, F_SETFL, flags | O_NONBLOCK);

    dealpg4_supervisor_publish_stub_forked(&state);

    dealpg4_supervisor_loop(&state);
    dealpg4_supervisor_terminate(&state);

    return DEALPG4_SUPERVISOR_STAGE_TERMINAL;
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
    int status;

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
     * statuses map to exit 4 (DEALPG4_EXIT_CAPABILITY_MISSING),
     * including the TIMER_FAILED entry refusal (D3/D8). */
    status = dealpg4_supervise_core((const char **)&argv[4], argv[2],
                                    nonce_env, invocation_id, budget_t,
                                    0, 0);
    if (status == DEALPG4_SUPERVISOR_ENTRY_TIMER_FAILED)
        return DEALPG4_EXIT_CAPABILITY_MISSING;
    return status;
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
     * the named token on stderr and exit 2 (containment failure). */
    if (status == DEALPG4_EXIT_CAPABILITY_MISSING) {
        fprintf(stderr, "CAPABILITY_MISSING\n");
        return DEALPG4_EXIT_USAGE;
    }
    if (status == DEALPG4_SUPERVISOR_ENTRY_TIMER_FAILED) {
        fprintf(stderr, "TIMER_FAILED\n");
        return DEALPG4_EXIT_USAGE;
    }
    return status;
}
