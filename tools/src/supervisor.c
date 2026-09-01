/*
 * DEALPG4 nested supervisor: serve/run mode-entry surfaces, the
 * in-process core entry, the nested control-channel state machine,
 * the serve-mode OUT/OUT_END stream relay, REPORT/CLEAN/FAILED, the
 * TERM/KILL/adoption/proof escalation, and the run-mode passthrough
 * surface (ISSUE-0244, epic Sequencing steps 4-5).
 *
 * See supervisor.h for the pinned surfaces and semantics
 * (dealpg4-supervisor-engine D5/D7/D8, native-supervisor-containment
 * D1-D10). This child completes the engine on top of the steps-2-3
 * machinery: subreaper set + read-back, the blocked stub state
 * machine, the status/release/stream pipes, the single-threaded ppoll
 * loop, the phase recipe, the bounded drains, the release write, and
 * the STARTED three-condition exec-evidence classification — plus
 * everything the channel machine owns: ACK/CANCEL with the canonical
 * rejection split, per-state cancel/channel-loss/PROTOCOL_ERROR
 * semantics, the OUT/OUT_END stream relay with the conditional
 * OUT_END rule, the bounded write-side queue, the canonical REPORT,
 * the terminal records, the proof loop, the mid-run TIMER_FAILED
 * path, and the run-mode passthrough surface.
 */
#define _GNU_SOURCE
#define _POSIX_C_SOURCE 200809L

#include "supervisor.h"

#include <dirent.h>
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

/* Status-pipe line buffer: the raw stub records are catalog records
 * capped at DEALPG4_MAX_LINE_OTHER_BYTES including the LF, so the
 * stored content (before the LF) never exceeds the cap minus one. */
#define DEALPG4_SUPERVISOR_STATUS_BUF_BYTES DEALPG4_MAX_LINE_OTHER_BYTES

/* Control-channel read buffer: one complete record line. A record
 * longer than the largest catalog cap (the INVOKE line cap) is an
 * oversize framing defect — the reader aborts PROTOCOL_ERROR before
 * the buffer overruns. */
#define DEALPG4_SUPERVISOR_CTRL_BUF_BYTES (DEALPG4_MAX_LINE_INVOKE_BYTES + 1)

/* The bounded post-T5 proof window (ISSUE-0436 remediation, MR-0322
 * review finding): once the overall deadline classified the invocation
 * OVERALL_TIMEOUT and the catch-up TERM/KILL/reap escalation ran, the
 * proof loop is allowed this window to complete the zero-survivor
 * cleanup before the terminal classification. A tree that cannot be
 * cleaned — a drain that never reaches EOF, a D-state survivor, or a
 * drain read failure — can never complete the proof, so the window is
 * the terminal bound: on expiry the supervisor performs the terminal
 * classification (OVERALL_TIMEOUT, the owning T5 token, with the
 * pinned REPORT.drainEof = 0 consequence applied at finalize) and
 * finalizes/exits regardless of proof completion — exactly one REPORT
 * and exactly one terminal FAILED record publish, and the record
 * terminates by its own deadline. The window is 200 proof-throttle
 * cadences (5 ms) and 100 confirming-pass windows (10 ms); a killable
 * tree completes the proof in a few cadences, so the window never
 * misfires on the killable catch-up path. */
#define DEALPG4_SUP_T5_PROOF_BOUND_MS 1000

/* === Fault-injection seam catalog (dealpg4-supervisor-engine D6) =======
 * The named catalog the selftest battery (ISSUE-0184) passes to
 * dealpg4_fi_install_overrides: the eleven delay site tags (one
 * pre-step site for every stub step plus the two supervisor-side
 * sites), the ten fail-site tags, the four congest targets, and the
 * nine congestion-mode tags. All sites resolve to the production
 * defaults (fi.h) — production modes behave identically to a seam-free
 * build; the supervisor never installs overrides and no CLI/env key
 * activates injection. */
static const char *const dealpg4_supervisor_delay_sites[] = {
    DEALPG4_FI_DELAY_SUP_PRE_FORK,
    DEALPG4_FI_DELAY_SUP_PRE_RELEASE_WRITE,
    DEALPG4_FI_DELAY_STUB_POST_FORK,
    DEALPG4_FI_DELAY_STUB_PRE_PPID_RECHECK,
    DEALPG4_FI_DELAY_STUB_PRE_SETSID,
    DEALPG4_FI_DELAY_STUB_PRE_IDENTITY_SELFCHECK,
    DEALPG4_FI_DELAY_STUB_PRE_IDENTITY_WRITE,
    DEALPG4_FI_DELAY_STUB_PRE_RELEASE_POLL,
    DEALPG4_FI_DELAY_STUB_POST_RELEASE,
    DEALPG4_FI_DELAY_STUB_PRE_CHDIR,
    DEALPG4_FI_DELAY_STUB_PRE_EXECVP
};

static const int dealpg4_supervisor_fail_sites[] = {
    FI_SUP_SUBREAPER,
    FI_SUP_TIMERFD,
    FI_SUP_SIGNALFD,
    FI_SUP_FORK,
    FI_SUP_PIPE,
    FI_STUB_SETSID,
    FI_STUB_IDENTITY_SELFCHECK,
    FI_STUB_CHDIR,
    FI_STUB_EXEC,
    FI_SUP_DEATH
};

static const int dealpg4_supervisor_congest_targets[] = {
    FI_CONGEST_STATUS_PIPE,
    FI_CONGEST_CTRL,
    FI_CONGEST_STREAM,
    FI_CONGEST_IDENTITY
};

static const int dealpg4_supervisor_congest_modes[] = {
    STATUS_LOSS,
    STATUS_CONGESTED,
    CTRL_WRITE_STALL,
    CTRL_READ_STALL,
    STREAM_NO_EOF,
    ID_MALFORMED_PID,
    ID_MALFORMED_PGID,
    ID_MALFORMED_SID,
    ID_MALFORMED_NONCE
};

const struct dealpg4_fi_catalog dealpg4_supervisor_fi_catalog = {
    dealpg4_supervisor_delay_sites,
    sizeof(dealpg4_supervisor_delay_sites)
        / sizeof(dealpg4_supervisor_delay_sites[0]),
    dealpg4_supervisor_fail_sites,
    sizeof(dealpg4_supervisor_fail_sites)
        / sizeof(dealpg4_supervisor_fail_sites[0]),
    dealpg4_supervisor_congest_targets,
    sizeof(dealpg4_supervisor_congest_targets)
        / sizeof(dealpg4_supervisor_congest_targets[0]),
    dealpg4_supervisor_congest_modes,
    sizeof(dealpg4_supervisor_congest_modes)
        / sizeof(dealpg4_supervisor_congest_modes[0])
};

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
 * discipline: the originals are restored before every exit on the run
 * entry's exit paths). */
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

    /* FI_CONGEST_STATUS_PIPE STATUS_CONGESTED: stub-side POLLOUT never
     * ready — the guaranteed-delivery retry loop keeps its bounded
     * ~10 ms cadence until the congestion clears (the record is
     * delivered then; no deadlock — the supervisor keeps the
     * status-pipe read end open until EOF, and its own deadlines
     * escalate a still-wedged stub). */
    while (dealpg4_fi_hooks.congest(FI_CONGEST_STATUS_PIPE,
                                    STATUS_CONGESTED, NULL)
           == STATUS_CONGESTED)
        (void)poll(NULL, 0, 10);

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

/* The stub's own T1s absolute deadline, anchored at the stub entry
 * read (parent D4: T1s = stub-entry + min(startupTimeoutMs,
 * T - cleanupTotalMs)). Anchoring at entry — not at the step-6 poll
 * entry — keeps the D6 delay sites (stub-post-fork ..
 * stub-pre-release-poll) consuming the deadline instead of shifting
 * it: a scripted pre-poll delay past T1s makes the step-6 poll
 * observe an already-expired deadline and _exit(4), the deterministic
 * release-write race driver (dealpg4-supervisor-engine D6,
 * native-supervisor-containment D3). Production behavior is unchanged
 * (the entry and the poll entry differ by the pre-poll bootstrap steps
 * only). */
static int64_t dealpg4_stub_t1s_deadline_ms(void)
{
    return (int64_t)dealpg4_now_ms() + dealpg4_stub_t1s_ms();
}

/* Step 6: block on the release pipe until exactly one release byte,
 * the stub's own T1s deadline, or EOF/error (parent D3). Timeout ->
 * _exit(4); EOF/error -> _exit(5). On the single release byte: disarm
 * the deadline (implicit — the poll returned), then write one
 * DEALPG4 RELEASE_RECV <pid> line to the status pipe (single
 * non-blocking attempt; on EPIPE/EAGAIN the stub proceeds without
 * retry). */
static void dealpg4_stub_release_poll(int release_fd, int status_fd,
                                      pid_t pid, int64_t t1s_deadline)
{
    struct pollfd pfd;
    char byte;

    pfd.fd = release_fd;
    pfd.events = POLLIN;
    for (;;) {
        uint64_t now = dealpg4_now_ms();
        uint64_t remaining = (uint64_t)t1s_deadline > now
                                 ? (uint64_t)t1s_deadline - now
                                 : 0;
        int rc;

        /* The absolute T1s deadline governs step 6: past it no release
         * byte is ever consumed. The check precedes the poll so a byte
         * that was already buffered when the (scripted) pre-poll delay
         * overran the deadline is refused exactly like a byte that
         * never arrived — the release write that raced the stub's
         * deadline lands in the pipe buffer while the stub still
         * lives, the stub exits 4 without consuming it, and no
         * RELEASE_RECV exists (the deterministic D3 race driver). */
        if (now >= (uint64_t)t1s_deadline)
            _exit(4);
        pfd.revents = 0;
        rc = poll(&pfd, 1,
                  remaining > (uint64_t)INT_MAX ? INT_MAX
                                                : (int)remaining);
        if (rc > 0)
            break;
        if (rc == 0) {
            /* EINTR-free timeout: re-check the absolute deadline. */
            if (dealpg4_now_ms() >= (uint64_t)t1s_deadline)
                _exit(4);
            continue;
        }
        if (errno == EINTR)
            continue;
        _exit(5); /* poll error */
    }
    if (read(release_fd, &byte, 1) != 1)
        _exit(5); /* EOF/error before the release byte */
    /* stub-post-release: after the release byte was consumed, before
     * the RELEASE_RECV write — the wedged post-release-before-execvp
     * window (a scripted delay here drives the post-release cancel /
     * deadline signal deaths with no RELEASE_RECV on the pipe). */
    (void)dealpg4_fi_hooks.delay_ms(0,
                                    DEALPG4_FI_DELAY_STUB_POST_RELEASE);
    /* Release consumed; the step-6 deadline is disarmed. Single-attempt
     * optional publication; FI_CONGEST_STATUS_PIPE STATUS_LOSS makes it
     * vanish (loss of evidence only, never a false STARTED). */
    if (dealpg4_fi_hooks.congest(FI_CONGEST_STATUS_PIPE, STATUS_LOSS,
                                 NULL)
        != STATUS_LOSS) {
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
    /* Stub-entry anchor (parent D4): the step-6 T1s deadline is read
     * here, before any seam delay — the D6 delay sites consume this
     * absolute deadline (an injection never extends one). */
    int64_t t1s_deadline = dealpg4_stub_t1s_deadline_ms();

    /* Close every inherited end the stub does not use (the release-pipe
     * write end immediately after fork, the status-pipe read end, the
     * stream read ends). */
    close(cfg->release_write_fd);
    close(cfg->status_read_fd);
    close(cfg->stdout_read_fd);
    close(cfg->stderr_read_fd);

    /* stub-post-fork: immediately after fork, pinned to precede stub
     * step 1 (the prctl(PR_SET_PDEATHSIG, SIGKILL) call). A scripted
     * delay here lengthens the pre-PDEATHSIG window (the deterministic
     * parent-mismatch driver). */
    (void)dealpg4_fi_hooks.delay_ms(0, DEALPG4_FI_DELAY_STUB_POST_FORK);

    /* Step 1: parent-death cascade. A prctl failure removes the layered
     * pre-ACK safety — fail closed with the reserved silent exit 2
     * (pre-release, no write, no exec). */
    if (prctl(PR_SET_PDEATHSIG, SIGKILL) != 0)
        _exit(2);

    /* stub-pre-ppid-recheck: before step 2 (the getppid() parent-death
     * recheck — a scripted delay lengthens the window in which a
     * parent-death injection lands before the recheck, driving the
     * mismatch -> _exit(2) path; with step 1 already armed the
     * reparenting delivers the PDEATHSIG cascade instead). */
    (void)dealpg4_fi_hooks.delay_ms(
        0, DEALPG4_FI_DELAY_STUB_PRE_PPID_RECHECK);

    /* Step 2: parent recheck against the pre-fork recorded supervisor
     * pid; mismatch -> _exit(2) (no write, no exec). */
    if (getppid() != cfg->supervisor_pid)
        _exit(2);

    /* stub-pre-setsid: before step 3. */
    (void)dealpg4_fi_hooks.delay_ms(0, DEALPG4_FI_DELAY_STUB_PRE_SETSID);

    /* Step 3: setsid; failure -> STUB_FAILED (guaranteed delivery) +
     * _exit(3). FI_STUB_SETSID scripts the failure with the scripted
     * value as errno (0 runs the real setsid). */
    {
        int fi = dealpg4_fi_hooks.fail(FI_STUB_SETSID);
        int err;

        if (fi != 0)
            err = fi;
        else if (setsid() == -1)
            err = errno;
        else
            err = 0;
        if (err != 0) {
            n = snprintf(line, sizeof line, "DEALPG4 STUB_FAILED %ld %d\n",
                         (long)pid, err);
            if (n > 0 && (size_t)n < sizeof line)
                dealpg4_stub_write_guaranteed(cfg->status_fd, line,
                                              (size_t)n);
            _exit(3);
        }
    }

    /* stub-pre-identity-selfcheck: before step 4. */
    (void)dealpg4_fi_hooks.delay_ms(
        0, DEALPG4_FI_DELAY_STUB_PRE_IDENTITY_SELFCHECK);

    /* Step 4: identity self-check; mismatch -> STUB_FAILED (guaranteed
     * delivery) + _exit(3). FI_STUB_IDENTITY_SELFCHECK scripts the
     * mismatch with the scripted value as the reported errno (0 runs
     * the real self-check). */
    {
        int fi = dealpg4_fi_hooks.fail(FI_STUB_IDENTITY_SELFCHECK);

        if (fi != 0 || getsid(0) != pid || getpgid(0) != pid) {
            int err = fi != 0 ? fi : EPERM;

            n = snprintf(line, sizeof line, "DEALPG4 STUB_FAILED %ld %d\n",
                         (long)pid, err);
            if (n > 0 && (size_t)n < sizeof line)
                dealpg4_stub_write_guaranteed(cfg->status_fd, line,
                                              (size_t)n);
            _exit(3);
        }
    }

    /* stub-pre-identity-write: before step 5. */
    (void)dealpg4_fi_hooks.delay_ms(
        0, DEALPG4_FI_DELAY_STUB_PRE_IDENTITY_WRITE);

    /* Step 5: one STUB_IDENTITY line (single attempt; the nonce from
     * the inherited DEALPG4_NONCE env — the supervisor setenv'd it in
     * the fork environment). A missing nonce drops the publication:
     * loss of evidence only, never a false STARTED.
     * FI_CONGEST_IDENTITY scripts a malformed STUB_IDENTITY line — the
     * named field outside its cross-verification class (pid/pgid/sid =
     * 0, the reserved value; nonce = the real nonce with the first hex
     * character flipped, still 32 lowercase hex but mismatching) — the
     * stub continues to step 6 and the supervisor's cross-verification
     * fails -> FAILED <id> AUTH_FAILED.
     * FI_CONGEST_STATUS_PIPE STATUS_LOSS makes the publication vanish
     * (single-attempt policy: loss of evidence only). */
    {
        const char *nonce_env = getenv(DEALPG4_ENV_NONCE);

        if (nonce_env != NULL) {
            int malformed_pid =
                dealpg4_fi_hooks.congest(FI_CONGEST_IDENTITY,
                                         ID_MALFORMED_PID, NULL)
                == ID_MALFORMED_PID;
            int malformed_pgid =
                dealpg4_fi_hooks.congest(FI_CONGEST_IDENTITY,
                                         ID_MALFORMED_PGID, NULL)
                == ID_MALFORMED_PGID;
            int malformed_sid =
                dealpg4_fi_hooks.congest(FI_CONGEST_IDENTITY,
                                         ID_MALFORMED_SID, NULL)
                == ID_MALFORMED_SID;
            int malformed_nonce =
                dealpg4_fi_hooks.congest(FI_CONGEST_IDENTITY,
                                         ID_MALFORMED_NONCE, NULL)
                == ID_MALFORMED_NONCE;
            char nonce_out[DEALPG4_NONCE_HEX_CHARS + 1];

            memcpy(nonce_out, nonce_env, DEALPG4_NONCE_HEX_CHARS + 1);
            if (malformed_nonce)
                /* In-class (32 lowercase hex) but mismatching: the
                 * supervisor's cross-verification rejects it. */
                nonce_out[0] = nonce_out[0] == '0' ? '1' : '0';
            n = snprintf(line, sizeof line,
                         "DEALPG4 STUB_IDENTITY %ld %ld %ld %s\n",
                         (long)(malformed_pid ? 0 : pid),
                         (long)(malformed_pgid ? 0 : pid),
                         (long)(malformed_sid ? 0 : pid), nonce_out);
            if (n > 0 && (size_t)n < sizeof line
                && dealpg4_fi_hooks.congest(FI_CONGEST_STATUS_PIPE,
                                            STATUS_LOSS, NULL)
                       != STATUS_LOSS) {
                ssize_t wr = write(cfg->status_fd, line, (size_t)n);

                (void)wr; /* single attempt; loss removes evidence only */
            }
        }
    }

    /* stub-pre-release-poll: before the step-6 poll (drives the T1s
     * race and release-EOF cases against the stub-entry-anchored
     * deadline). */
    (void)dealpg4_fi_hooks.delay_ms(
        0, DEALPG4_FI_DELAY_STUB_PRE_RELEASE_POLL);

    /* Step 6: blocked release poll with the stub's own T1s deadline. */
    dealpg4_stub_release_poll(cfg->release_fd, cfg->status_fd, pid,
                              t1s_deadline);

    /* stub-pre-chdir: before step 7. */
    (void)dealpg4_fi_hooks.delay_ms(0, DEALPG4_FI_DELAY_STUB_PRE_CHDIR);

    /* Step 7: chdir; failure -> STUB_FAILED (guaranteed delivery) +
     * _exit(6). FI_STUB_CHDIR scripts the failure with the scripted
     * value as errno (0 runs the real chdir). */
    {
        int fi = dealpg4_fi_hooks.fail(FI_STUB_CHDIR);
        int err;

        if (fi != 0)
            err = fi;
        else if (chdir(cfg->cwd) != 0)
            err = errno;
        else
            err = 0;
        if (err != 0) {
            n = snprintf(line, sizeof line, "DEALPG4 STUB_FAILED %ld %d\n",
                         (long)pid, err);
            if (n > 0 && (size_t)n < sizeof line)
                dealpg4_stub_write_guaranteed(cfg->status_fd, line,
                                              (size_t)n);
            _exit(6);
        }
    }

    /* stub-pre-execvp: before step 8 — the wedged
     * post-release-before-execvp window. */
    (void)dealpg4_fi_hooks.delay_ms(0, DEALPG4_FI_DELAY_STUB_PRE_EXECVP);

    /* Step 8: exactly one execvp, only after the release byte. The
     * stream write ends replace stdout/stderr (dup2 clears FD_CLOEXEC
     * on the target fds; the O_CLOEXEC originals are closed). Exec
     * hygiene (D8): SIGPIPE SIG_DFL and the captured-entry-mask
     * restore immediately before execvp, so the exec'd target's
     * dispositions and mask are untouched. FI_STUB_EXEC scripts the
     * execvp failure with the scripted value as errno (0 runs the
     * real execvp). */
    (void)dup2(cfg->stdout_write_fd, 1);
    (void)dup2(cfg->stderr_write_fd, 2);
    close(cfg->stdout_write_fd);
    close(cfg->stderr_write_fd);

    (void)signal(SIGPIPE, SIG_DFL);
    (void)sigprocmask(SIG_SETMASK, &dealpg4_supervisor_entry_mask, NULL);

    {
        int fi = dealpg4_fi_hooks.fail(FI_STUB_EXEC);
        int err;

        if (fi == 0) {
            execvp(cfg->argv[0], (char *const *)cfg->argv);
            err = errno;
        } else {
            err = fi;
        }
        /* Failure: STUB_EXEC_FAILED (guaranteed delivery) +
         * _exit(127). */
        n = snprintf(line, sizeof line, "DEALPG4 STUB_EXEC_FAILED %ld %d\n",
                     (long)pid, err);
        if (n > 0 && (size_t)n < sizeof line)
            dealpg4_stub_write_guaranteed(cfg->status_fd, line,
                                          (size_t)n);
        _exit(127);
    }
}

/* === Bounded per-record pending queue (parent D1 write-side) ============ */

/* The per-record pending queue of the control channel: one FIFO of
 * serialized record lines. OUT chunk lines count toward the 1 MiB
 * relay-queue cap (DEALPG4_RELAY_QUEUE_CAP_BYTES == the drain
 * retention cap); catalog-bounded control records (STUB_FORKED,
 * STUB_READY, STARTED, EXEC_FAILED, OUT_END, REPORT, CLEAN/FAILED,
 * REJECT — at most a bounded handful per invocation, each <= 8192
 * bytes) never count toward the cap and are never dropped by queue
 * pressure. The arena is static (one invocation per core call); items
 * may wrap the arena end as two fragments. */
#define DEALPG4_SUP_QUEUE_CTRL_RESERVE_BYTES 131072
/* The queue holds at most the D7 retention budget per stream — 1 MiB of
 * retained raw payload per stream (hex-encoded into OUT lines: 2 chars
 * per raw byte plus the per-record prefix) plus catalog-bounded control
 * records. "Queued bytes never exceed retained bytes" (drain.h): the cap
 * is per-stream raw payload, so a stream's queued OUT payload never
 * exceeds what its drain context retained. */
#define DEALPG4_SUP_QUEUE_ARENA_BYTES \
    (2 * (2 * DEALPG4_DRAIN_CAP_BYTES + 2 * DEALPG4_MAX_LINE_OTHER_BYTES) \
     + DEALPG4_SUP_QUEUE_CTRL_RESERVE_BYTES)
#define DEALPG4_SUP_QUEUE_MAX_ITEMS 96

typedef struct dealpg4_sup_qfrag {
    size_t start;
    size_t len;
} dealpg4_sup_qfrag;

typedef struct dealpg4_sup_qitem {
    dealpg4_sup_qfrag frags[2];
    size_t off;   /* bytes already written of this item */
    int kind;     /* 0 = OUT line (counts toward the stream's raw cap),
                     1 = control record (never dropped by pressure) */
    int stream;   /* OUT: 0 = stdout, 1 = stderr; ctrl: -1 */
    size_t raw;   /* OUT: the chunk's raw payload bytes */
} dealpg4_sup_qitem;

typedef struct dealpg4_supervisor_queue {
    unsigned char *arena;
    dealpg4_sup_qitem items[DEALPG4_SUP_QUEUE_MAX_ITEMS];
    int head;              /* item slot of the oldest item */
    int tail;              /* item slot of the next free item */
    int n_items;
    size_t n_bytes;        /* total line bytes queued */
    size_t data_raw[2];    /* queued OUT raw payload per stream (the
                              per-stream retention-budget cap) */
    size_t free_start;     /* arena offset where the next item starts */
} dealpg4_supervisor_queue;

static unsigned char
    dealpg4_supervisor_queue_arena[DEALPG4_SUP_QUEUE_ARENA_BYTES];

static void dealpg4_supervisor_queue_init(dealpg4_supervisor_queue *q)
{
    q->arena = dealpg4_supervisor_queue_arena;
    q->head = 0;
    q->tail = 0;
    q->n_items = 0;
    q->n_bytes = 0;
    q->data_raw[0] = 0;
    q->data_raw[1] = 0;
    q->free_start = 0;
}

static int dealpg4_supervisor_queue_empty(
    const dealpg4_supervisor_queue *q)
{
    return q->n_items == 0;
}

/* Append one serialized record line. OUT lines (kind 0) are refused —
 * and the caller drops the payload with the stream's truncation
 * consequence — when the payload would push the stream's queued raw
 * payload past its cap (the stream's retained bytes: the D7 retention
 * budget, at most 1 MiB plus the truncation marker per stream).
 * Control records (kind 1) fit by construction (bounded count x 8192
 * <= the reserve). Returns 0 on success, -1 on refusal or a structural
 * overflow (defect-bounded). */
static int dealpg4_supervisor_queue_append(dealpg4_supervisor_queue *q,
                                           const char *line, size_t len,
                                           int kind, int stream,
                                           size_t raw_len,
                                           size_t raw_cap)
{
    dealpg4_sup_qitem *item;
    size_t start0;
    size_t len0;
    size_t len1;

    if (q->n_items >= DEALPG4_SUP_QUEUE_MAX_ITEMS)
        return -1;
    if (len > DEALPG4_SUP_QUEUE_ARENA_BYTES - q->n_bytes)
        return -1;
    if (kind == 0
        && q->data_raw[stream] + raw_len > raw_cap)
        return -1; /* the stream's cap reached: payload dropped */
    item = &q->items[q->tail];
    start0 = q->free_start;
    if (start0 + len <= DEALPG4_SUP_QUEUE_ARENA_BYTES) {
        len0 = len;
        len1 = 0;
    } else {
        len0 = DEALPG4_SUP_QUEUE_ARENA_BYTES - start0;
        len1 = len - len0;
    }
    memcpy(q->arena + start0, line, len0);
    if (len1 > 0)
        memcpy(q->arena, line + len0, len1);
    item->frags[0].start = start0;
    item->frags[0].len = len0;
    item->frags[1].start = 0;
    item->frags[1].len = len1;
    item->off = 0;
    item->kind = kind;
    item->stream = kind == 0 ? stream : -1;
    item->raw = kind == 0 ? raw_len : 0;
    q->tail = (q->tail + 1) % DEALPG4_SUP_QUEUE_MAX_ITEMS;
    q->n_items++;
    q->n_bytes += len;
    if (kind == 0)
        q->data_raw[stream] += raw_len;
    /* == (start0 + len) mod the arena size: len1 when the item wrapped
     * the arena end, start0 + len otherwise. */
    q->free_start = (start0 + len) % DEALPG4_SUP_QUEUE_ARENA_BYTES;
    return 0;
}

/* Drop every undelivered record (channel close / loss / abort). */
static void dealpg4_supervisor_queue_clear(dealpg4_supervisor_queue *q)
{
    q->head = 0;
    q->tail = 0;
    q->n_items = 0;
    q->n_bytes = 0;
    q->data_raw[0] = 0;
    q->data_raw[1] = 0;
    q->free_start = 0;
}

/* Flush queued records to the fd with non-blocking writes (the
 * write-side contract: a write never blocks). Returns 0 when the
 * queue drained, 1 when a write hit EAGAIN (the owner polls POLLOUT),
 * -1 on EPIPE or any other write error (channel loss). */
static int dealpg4_supervisor_queue_flush(dealpg4_supervisor_queue *q,
                                          int fd)
{
    while (q->n_items > 0) {
        dealpg4_sup_qitem *item = &q->items[q->head];
        size_t total = item->frags[0].len + item->frags[1].len;
        size_t off = item->off;
        size_t base = 0;
        size_t fi;

        if (off >= total) {
            /* fully written: pop */
            if (item->kind == 0)
                q->data_raw[item->stream] -= item->raw;
            q->n_bytes -= total;
            q->head = (q->head + 1) % DEALPG4_SUP_QUEUE_MAX_ITEMS;
            q->n_items--;
            if (q->n_items == 0) {
                q->head = 0;
                q->tail = 0;
                q->free_start = 0;
            }
            continue;
        }
        for (fi = 0; fi < 2; fi++) {
            if (off < base + item->frags[fi].len)
                break;
            base += item->frags[fi].len;
        }
        if (fi >= 2) {
            /* Unreachable: off < total implies a matching fragment. */
            item->off = total;
            continue;
        }
        for (;;) {
            ssize_t r = write(fd,
                              q->arena + item->frags[fi].start
                                  + (off - base),
                              item->frags[fi].len - (off - base));

            if (r > 0) {
                item->off += (size_t)r;
                if (item->off >= total)
                    break;
                continue;
            }
            if (r < 0 && errno == EINTR)
                continue;
            if (r < 0 && (errno == EAGAIN || errno == EWOULDBLOCK))
                return 1;
            return -1; /* EPIPE or any other error: channel loss */
        }
    }
    return 0;
}

/* === Supervisor state =================================================== */

typedef enum dealpg4_supervisor_phase {
    DEALPG4_PHASE_STARTUP = 0, /* waiting for the release; deadline T1 */
    DEALPG4_PHASE_RUN,         /* released; deadline T2 (execution cutoff) */
    DEALPG4_PHASE_TERM,        /* TERM issued; deadline T3 (KILL) */
    DEALPG4_PHASE_KILL,        /* KILL issued; deadline T4 */
    DEALPG4_PHASE_FINALIZE     /* deadline T5 */
} dealpg4_supervisor_phase;

/* Channel states (dealpg4-supervisor-engine D5): PRE_RELEASE expects
 * {ACK, CANCEL} from STUB_FORKED until the release write; RELEASED
 * expects {CANCEL} from the observed successful release write until
 * the terminal record is queued; TERMINAL expects {CANCEL} while the
 * terminal record is queued until delivery and channel close. */
typedef enum dealpg4_channel_state {
    DEALPG4_CHAN_PRE_RELEASE = 0,
    DEALPG4_CHAN_RELEASED,
    DEALPG4_CHAN_TERMINAL
} dealpg4_channel_state;

/* Per-stream relay state (serve mode). */
typedef struct dealpg4_supervisor_relay {
    dealpg4_drain_ctx *drain;
    size_t relay_off;       /* retained bytes already queued as OUT */
    int out_end_queued;     /* the single OUT_END queued (drain EOF) */
    int queue_dropped;      /* an OUT payload was dropped at the queue
                               cap: the stream's truncation consequence */
    int is_out;             /* 1 = stdout tag "out", 0 = "err" */
    uint64_t chunks;        /* OUT chunks queued (in-process view) */
} dealpg4_supervisor_relay;

/* Bounded survivor view from one /proc scan pass. */
typedef struct dealpg4_sup_survivors {
    pid_t pids[DEALPG4_SUP_SURVIVOR_VIEW_MAX];
    size_t count;
    int group_found;
    int session_found;
    int adopted_found;
    int ok;   /* the /proc scan itself completed (no opendir failure) */
} dealpg4_sup_survivors;

typedef struct dealpg4_supervisor_state {
    /* Invocation surface. */
    const char **argv;
    const char *cwd;
    char nonce[DEALPG4_NONCE_HEX_CHARS + 1];
    int64_t invocation_id;
    int64_t budget_t;
    int control_fd;
    int serve_mode;   /* the invocation entered with a control channel:
                         the exit-status map and stdio discipline stay
                         serve-mode even after channel loss closes the fd */
    int emit_ready_frame;

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
    int cancel_signals_issued;  /* a supervisor-issued TERM/KILL on the
                                   cancel path: set only when
                                   dealpg4_supervisor_apply_cancel itself
                                   issues the signal — deadline-issued
                                   signals never set it, so a death by
                                   the T2/T3 escalation classifies
                                   EXECUTION_TIMEOUT even when a cancel
                                   arrived mid-escalation (D3/D5(c)) */

    /* Stub / identity. */
    pid_t stub_pid;
    pid_t stub_pgid;  /* 0 until a verified identity */
    pid_t stub_sid;
    int identity_seen;
    int identity_verified;

    /* Release. */
    int release_fd; /* supervisor write end */
    int release_write_done;
    int release_write_ok; /* the single write(2) returned 1 */
    int release_recv_pid_match;
    int release_frozen;   /* the release path is frozen (cancel / AUTH_FAILED
                             / TIMER_FAILED pre-release): never a byte */

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
    int64_t reap_count;
    int64_t adopt_count;

    /* Drains. */
    dealpg4_drain_ctx *drain_out;
    dealpg4_drain_ctx *drain_err;
    int stream_out_fd;
    int stream_err_fd;
    int drains_active; /* the stream pipes exist and are drained */

    /* Channel machine. */
    dealpg4_channel_state channel_state;
    dealpg4_expectation_set expect;
    int ack_applied;
    int cancel_requested;  /* a valid CANCEL or channel loss applied the
                              cancel path */
    int channel_lost;
    int protocol_aborted;
    char ctrl_buf[DEALPG4_SUPERVISOR_CTRL_BUF_BYTES];
    size_t ctrl_buf_len;

    /* Write-side queue + relay. */
    dealpg4_supervisor_queue queue;
    dealpg4_supervisor_relay relay_out;
    dealpg4_supervisor_relay relay_err;

    /* Proof. */
    int proof_first_pass;
    int proof_done;
    int64_t proof_first_pass_ms;
    int64_t proof_next_pass_ms; /* bounded proof-pass throttle */
    int64_t t5_proof_bound_ms;  /* absolute CLOCK_MONOTONIC bound of the
                                   post-T5 cleanup window (0 = unset):
                                   armed when the T5 branch classifies
                                   OVERALL_TIMEOUT; on expiry the
                                   terminal classification runs even
                                   though the proof cannot complete */
    int group_clean;
    int session_clean;
    int drain_ok;
    int proof_failed_class; /* DRAIN_FAILED/PROOF_TIMEOUT/OVERALL_TIMEOUT
                               or a survivor token: REPORT.drainEof = 0 */

    /* REPORT timings. */
    int64_t startup_ms;
    int64_t exec_ms;
    int64_t term_ms;
    int64_t kill_ms;
    int64_t proof_ms;
    int64_t final_ms;

    /* Run-mode passthrough + ready frame + REPORT line. */
    size_t pass_out_off;
    size_t pass_err_off;
    int pass_out_lost;
    int pass_err_lost;
    char ready_line[64];
    size_t ready_len;
    size_t ready_off;
    int ready_pending;
    char report_line[DEALPG4_MAX_LINE_OTHER_BYTES];
    size_t report_len;
    size_t report_off;
    int report_pending;
    int report_lead;        /* a leading LF keeps the canonical REPORT a
                               line of its own when the stderr
                               passthrough tail lacked one */
    int report_sep_done;    /* the lead decision was made (one-shot) */

    /* Terminal records. */
    dealpg4_supervise_terminal final_kind;
    char failure_token[32];
    int terminal_queued;
    int started_published;
    /* TERMINAL close linger: after the per-record queue drains, the
     * channel stays open for one bounded quiet pass so a terminal
     * CANCEL the outer sent upon observing the terminal record is
     * still read — its REJECT queues behind the terminal record and
     * flushes before the channel close (D5 flush-before-close). The
     * linger is consumed inside T5 (never an extension) and ends at
     * channel loss / T5 / the outer's close. */
    int close_lingering;
    int64_t close_linger_deadline_ms;
    dealpg4_supervise_class classification;
    int done;

    /* Survivor identity list (bounded, in-process view). */
    pid_t survivor_pids[DEALPG4_SUP_SURVIVOR_VIEW_MAX];
    size_t survivor_count;
} dealpg4_supervisor_state;


/* Forward declarations (definition order: publication, relay, release,
 * identity, status, reaping, deadlines, proof, channel, report). */
static void dealpg4_supervisor_timer_failed(dealpg4_supervisor_state *state);
static void dealpg4_supervisor_read_control(
    dealpg4_supervisor_state *state);
static void dealpg4_supervisor_finalize(dealpg4_supervisor_state *state);
static int dealpg4_supervisor_proof_pending(
    const dealpg4_supervisor_state *state);
static void dealpg4_supervisor_proof_deadline(
    dealpg4_supervisor_state *state);
static void dealpg4_supervisor_t5_proof_expiry(
    dealpg4_supervisor_state *state);

/* === Record publication ================================================= */

/* Publish one catalog-bounded control record on the control channel
 * (serve mode only; suppressed after a PROTOCOL_ERROR close). The
 * record is serialized and appended to the bounded queue — control
 * records are never dropped by queue pressure. A serialization
 * refusal is a defect: no malformed line is ever emitted. */
static void dealpg4_supervisor_publish(dealpg4_supervisor_state *state,
                                       dealpg4_record_type type,
                                       const dealpg4_field_value *fields,
                                       size_t nfields)
{
    char line[DEALPG4_MAX_LINE_OTHER_BYTES];
    size_t written = 0;

    if (state->control_fd < 0 || state->protocol_aborted)
        return;
    if (dealpg4_serialize(type, fields, nfields, line, sizeof line,
                          &written) != 0)
        return;
    (void)dealpg4_supervisor_queue_append(&state->queue, line, written,
                                          1, -1, 0, 0);
}

/* Two-decimal-field records (STUB_FORKED, EXEC_FAILED, OUT_END tag
 * variants handled separately). */
static void dealpg4_supervisor_publish_two_decimal(
    dealpg4_supervisor_state *state, dealpg4_record_type type,
    int64_t a, int64_t b)
{
    char abuf[32];
    char bbuf[32];
    dealpg4_field_value fields[2];

    snprintf(abuf, sizeof abuf, "%lld", (long long)a);
    snprintf(bbuf, sizeof bbuf, "%lld", (long long)b);
    fields[0].data = abuf;
    fields[0].len = strlen(abuf);
    fields[1].data = bbuf;
    fields[1].len = strlen(bbuf);
    dealpg4_supervisor_publish(state, type, fields, 2);
}

/* STUB_FORKED <invocationId> <stubPid> — queued immediately after fork
 * returns (parent D3). */
static void dealpg4_supervisor_publish_stub_forked(
    dealpg4_supervisor_state *state)
{
    dealpg4_supervisor_publish_two_decimal(
        state, DEALPG4_REC_STUB_FORKED, state->invocation_id,
        (int64_t)state->stub_pid);
}

/* STUB_READY <invocationId> <stubPid> <pgid> <sid> <nonce> — published
 * only after the identity cross-verification passed (parent D9). */
static void dealpg4_supervisor_publish_stub_ready(
    dealpg4_supervisor_state *state)
{
    char idbuf[32];
    char pidbuf[32];
    char pgidbuf[32];
    char sidbuf[32];
    dealpg4_field_value fields[5];

    snprintf(idbuf, sizeof idbuf, "%lld", (long long)state->invocation_id);
    snprintf(pidbuf, sizeof pidbuf, "%ld", (long)state->stub_pid);
    snprintf(pgidbuf, sizeof pgidbuf, "%ld", (long)state->stub_pgid);
    snprintf(sidbuf, sizeof sidbuf, "%ld", (long)state->stub_sid);
    fields[0].data = idbuf;
    fields[0].len = strlen(idbuf);
    fields[1].data = pidbuf;
    fields[1].len = strlen(pidbuf);
    fields[2].data = pgidbuf;
    fields[2].len = strlen(pgidbuf);
    fields[3].data = sidbuf;
    fields[3].len = strlen(sidbuf);
    fields[4].data = state->nonce;
    fields[4].len = DEALPG4_NONCE_HEX_CHARS;
    dealpg4_supervisor_publish(state, DEALPG4_REC_STUB_READY, fields, 5);
}

/* STARTED <invocationId> — only under the parent D3 three-condition
 * exec-confirmation rule (published once, never retracted). */
static void dealpg4_supervisor_publish_started(
    dealpg4_supervisor_state *state)
{
    char idbuf[32];
    dealpg4_field_value fields[1];

    snprintf(idbuf, sizeof idbuf, "%lld", (long long)state->invocation_id);
    fields[0].data = idbuf;
    fields[0].len = strlen(idbuf);
    dealpg4_supervisor_publish(state, DEALPG4_REC_STARTED, fields, 1);
}

/* OUT_END <invocationId> out|err — exactly one per stream, only at that
 * stream's drain EOF. */
static void dealpg4_supervisor_publish_out_end(
    dealpg4_supervisor_state *state, const dealpg4_supervisor_relay *relay)
{
    char idbuf[32];
    char tag[4];
    dealpg4_field_value fields[2];

    snprintf(idbuf, sizeof idbuf, "%lld", (long long)state->invocation_id);
    memcpy(tag, relay->is_out ? "out" : "err", 4);
    fields[0].data = idbuf;
    fields[0].len = strlen(idbuf);
    fields[1].data = tag;
    fields[1].len = 3;
    dealpg4_supervisor_publish(state, DEALPG4_REC_OUT_END, fields, 2);
}

/* REJECT <invocationId> - CANCEL_AUTH_FAILED — the record-level answer
 * to a mismatched or terminal CANCEL (channel stays open, invocation
 * untouched). The invocation id is the CANCEL record's own id field. */
static void dealpg4_supervisor_publish_reject(
    dealpg4_supervisor_state *state, int64_t record_id)
{
    char idbuf[32];
    static const char dash[] = "-";
    static const char reason[] = "CANCEL_AUTH_FAILED";
    dealpg4_field_value fields[3];

    snprintf(idbuf, sizeof idbuf, "%lld", (long long)record_id);
    fields[0].data = idbuf;
    fields[0].len = strlen(idbuf);
    fields[1].data = dash;
    fields[1].len = 1;
    fields[2].data = reason;
    fields[2].len = sizeof(reason) - 1;
    dealpg4_supervisor_publish(state, DEALPG4_REC_REJECT, fields, 3);
}

/* === Stream relay (serve mode, D5(d)) =================================== */

static void dealpg4_supervisor_relay_stream(
    dealpg4_supervisor_state *state, dealpg4_supervisor_relay *relay);

/* Exec confirmation established (parent D3 condition 3): publish
 * STARTED exactly once and begin the stream relay with the bytes
 * drained so far; the run-mode ready frame (if requested) is queued on
 * stdout before any target passthrough. execMs anchors here — the
 * status-pipe EOF read for condition 3(i), the reaped-status
 * classification for 3(ii)/3(iii). */
static void dealpg4_supervisor_note_started(dealpg4_supervisor_state *state)
{
    if (state->started_published)
        return;
    state->started_published = 1;
    if (state->exec_ms == 0)
        state->exec_ms = (int64_t)dealpg4_now_ms() - state->t0;
    if (state->serve_mode) {
        dealpg4_supervisor_publish_started(state);
    } else if (state->emit_ready_frame) {
        int n = snprintf(state->ready_line, sizeof state->ready_line,
                         "DEALPG4 STARTED %s\n", state->nonce);

        if (n > 0 && (size_t)n < sizeof state->ready_line) {
            state->ready_len = (size_t)n;
            state->ready_off = 0;
            state->ready_pending = 1;
        }
    }
    dealpg4_supervisor_relay_stream(state, &state->relay_out);
    dealpg4_supervisor_relay_stream(state, &state->relay_err);
}

/* Relay one stream's drained retained content as OUT chunks (hex
 * encoded, <= DEALPG4_OUT_MAX_HEX_CHARS hex chars = 32768 raw bytes
 * per chunk, stream tag never mixed) and the single OUT_END at that
 * stream's drain EOF. Bytes drained before STARTED remain in the drain
 * context and are relayed when the relay begins. A chunk whose payload
 * cannot be queued at the 1 MiB cap is dropped — the stream's
 * truncation consequence (D5(d)/D7) — and the relay advances past it;
 * draining continues past the cap until EOF or the terminal
 * classification. The relay freezes at the terminal classification
 * (finalize's relay catch-up runs before terminal_queued is set): on a
 * DRAIN_FAILED/PROOF_TIMEOUT/OVERALL_TIMEOUT/survivor-token path a late
 * stream EOF or new bytes never queue OUT chunks or the previously
 * withheld OUT_END behind the already-queued REPORT and terminal record
 * — the relayed chunk sequence simply ends there, and no OUT_END is
 * ever queued after REPORT (D5(d)). */
static void dealpg4_supervisor_relay_stream(
    dealpg4_supervisor_state *state, dealpg4_supervisor_relay *relay)
{
    static const char hexdigits[16] = "0123456789abcdef";
    dealpg4_drain_ctx *drain = relay->drain;
    char line[DEALPG4_MAX_LINE_OUT_BYTES];
    char hexbuf[DEALPG4_OUT_MAX_HEX_CHARS];
    char idbuf[32];
    char tag[4];

    if (state->control_fd < 0 || state->protocol_aborted
        || !state->started_published || state->terminal_queued)
        return;

    snprintf(idbuf, sizeof idbuf, "%lld", (long long)state->invocation_id);
    memcpy(tag, relay->is_out ? "out" : "err", 4);

    while (relay->relay_off < drain->retained_len) {
        size_t chunk = drain->retained_len - relay->relay_off;
        size_t i;
        dealpg4_field_value fields[3];
        size_t written = 0;

        if (chunk > DEALPG4_OUT_MAX_HEX_CHARS / 2)
            chunk = DEALPG4_OUT_MAX_HEX_CHARS / 2;
        for (i = 0; i < chunk; i++) {
            unsigned char b = drain->retained[relay->relay_off + i];

            hexbuf[2 * i] = hexdigits[b >> 4];
            hexbuf[2 * i + 1] = hexdigits[b & 0x0f];
        }
        fields[0].data = idbuf;
        fields[0].len = strlen(idbuf);
        fields[1].data = tag;
        fields[1].len = 3;
        fields[2].data = hexbuf;
        fields[2].len = 2 * chunk;
        if (dealpg4_serialize(DEALPG4_REC_OUT, fields, 3, line,
                              sizeof line, &written) != 0)
            return; /* defect: a malformed OUT line is never emitted */
        relay->relay_off += chunk;
        relay->chunks++;
        if (dealpg4_supervisor_queue_append(
                &state->queue, line, written, 0, relay->is_out ? 0 : 1,
                chunk, drain->retained_len) != 0)
            relay->queue_dropped = 1;
    }
    if (drain->eof && relay->relay_off >= drain->retained_len
        && !relay->out_end_queued) {
        relay->out_end_queued = 1;
        dealpg4_supervisor_publish_out_end(state, relay);
    }
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

static void dealpg4_supervisor_release(dealpg4_supervisor_state *state);

/* The single release write (parent D3/D9): exactly one write(2) of one
 * byte on the O_NONBLOCK write end. The observed-success fact (write
 * returned 1) is recorded; any other result is classified by the
 * EOF-before-release rule. The successful write ends the startup phase
 * and moves the channel to RELEASED. A frozen release path (pre-release
 * cancel / AUTH_FAILED / TIMER_FAILED) never writes the byte.
 *
 * sup-pre-release-write (D6): the injected delay sleeps immediately
 * before the write and consumes the enclosing startup deadline (never
 * an extension). The T1 check already passed at the release point, so
 * a scripted delay past T1 drives the release-write race: the write
 * lands after the stub's own T1s poll already timed out (write(2)
 * succeeds into the pipe buffer while the stub is still in its
 * pre-poll delay, or fails EPIPE once the stub fully exited). After
 * the delay the pending control records are processed first — the
 * D5(a) freeze re-check: a valid CANCEL, a second ACK, or a channel
 * close that arrived during the delay freezes the release path and
 * the byte is never written. */
static void dealpg4_supervisor_release(dealpg4_supervisor_state *state)
{
    char byte = 'R';
    ssize_t r;

    if (state->release_write_done || state->release_frozen
        || state->cancel_requested || state->protocol_aborted)
        return; /* at most once, and never after the freeze */
    if (state->release_fd < 0)
        return;
    (void)dealpg4_fi_hooks.delay_ms(
        0, DEALPG4_FI_DELAY_SUP_PRE_RELEASE_WRITE);
    /* Records the outer sent while the delay slept are processed
     * before the write decision (single-threaded: nothing else ran
     * during the sleep). */
    if (state->control_fd >= 0 && !state->protocol_aborted)
        dealpg4_supervisor_read_control(state);
    if (state->release_write_done || state->release_frozen
        || state->cancel_requested || state->protocol_aborted)
        return; /* the D5(a) freeze holds: never a release byte */
    if (state->release_fd < 0)
        return;
    state->release_write_done = 1;
    r = write(state->release_fd, &byte, 1);
    if (r == 1) {
        state->release_write_ok = 1;
        state->phase = DEALPG4_PHASE_RUN;
        state->startup_ms = (int64_t)dealpg4_now_ms() - state->t0;
        state->channel_state = DEALPG4_CHAN_RELEASED;
        dealpg4_expectation_set_init(&state->expect);
        dealpg4_expectation_set_add(&state->expect, DEALPG4_REC_CANCEL);
        if (dealpg4_deadline_arm(&state->timer, (uint64_t)state->dl.t2)
            != 0)
            dealpg4_supervisor_timer_failed(state);
    }
}

/* The release point: the end of one event-processing batch. The ACK
 * application and the release write are deliberately separate so a
 * second ACK (or a CANCEL) processed in the same batch observes the
 * still-pre-release state — the D5(a) post-ACK-pre-release window. */
static void dealpg4_supervisor_maybe_release(dealpg4_supervisor_state *state)
{
    if (state->release_write_done || state->release_frozen
        || state->cancel_requested || state->protocol_aborted)
        return;
    if (state->classification != DEALPG4_SUP_CLASS_NONE)
        return;
    if (state->phase != DEALPG4_PHASE_STARTUP)
        return;
    if ((int64_t)dealpg4_now_ms() >= state->dl.t1)
        return; /* never a release byte past T1 */
    if (state->control_fd >= 0) {
        if (state->ack_applied)
            dealpg4_supervisor_release(state);
    } else if (state->identity_verified) {
        dealpg4_supervisor_release(state);
    }
}

static void dealpg4_supervisor_classify_auth_failed(
    dealpg4_supervisor_state *state);

/* STUB_IDENTITY cross-verification (parent D9): nonce echo equality,
 * getpgid/getsid agreement, /proc/<pid>/stat fields 4/5/6 agreement,
 * reserved PID/PGID/session rejection, and pid == stubPid (setsid
 * makes pgid == sid == stubPid). Only a verified identity may precede
 * the release; serve publishes STUB_READY only after this verification.
 * A failed verification is the record-level AUTH_FAILED path: the
 * release path freezes, the release pipe closes (the stub exits
 * pre-exec on EOF), the retained stub is TERM'd (KILL at the absolute
 * T3), reaped, and proven clean — never a release byte. */
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
        dealpg4_supervisor_classify_auth_failed(state);
        return;
    }

    state->identity_verified = 1;
    state->stub_pgid = (pid_t)pgid;
    state->stub_sid = (pid_t)sid;

    /* STUB_READY is published only after this verification (serve);
     * run mode has no channel. The release itself happens at the
     * release point of the event batch. */
    if (state->control_fd >= 0)
        dealpg4_supervisor_publish_stub_ready(state);
}

/* The AUTH_FAILED record-level path (failed STUB_IDENTITY
 * cross-verification, a wrong-nonce ACK, or a second pre-release ACK):
 * the invocation terminates FAILED <id> AUTH_FAILED, the release path
 * freezes (never a release byte), the release pipe closes, the retained
 * stub is killed pre-release (TERM now, KILL at the absolute T3),
 * reaped, and proven clean (parent D9). */
static void dealpg4_supervisor_classify_auth_failed(
    dealpg4_supervisor_state *state)
{
    if (state->classification != DEALPG4_SUP_CLASS_NONE
        && state->classification != DEALPG4_SUP_CLASS_AUTH_FAILED)
        return;
    if (state->classification == DEALPG4_SUP_CLASS_NONE) {
        state->classification = DEALPG4_SUP_CLASS_AUTH_FAILED;
        if (state->phase == DEALPG4_PHASE_STARTUP
            && !state->release_write_ok && state->startup_ms == 0)
            state->startup_ms = (int64_t)dealpg4_now_ms() - state->t0;
    }
    state->release_frozen = 1;
    if (state->release_fd >= 0) {
        close(state->release_fd);
        state->release_fd = -1;
    }
    if (!state->term_issued) {
        state->term_issued = 1;
        state->term_ms = (int64_t)dealpg4_now_ms() - state->t0;
        if (state->stub_pid > 0 && !state->stub_reaped
            && kill(state->stub_pid, SIGTERM) == 0)
            state->signals_issued_to_target = 1;
    }
    if (state->phase == DEALPG4_PHASE_STARTUP
        || state->phase == DEALPG4_PHASE_RUN)
        state->phase = DEALPG4_PHASE_TERM;
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
        if (state->classification == DEALPG4_SUP_CLASS_NONE) {
            state->classification =
                DEALPG4_SUP_CLASS_STUB_BOOTSTRAP_FAILED;
            if (state->phase == DEALPG4_PHASE_STARTUP
                && !state->release_write_ok && state->startup_ms == 0)
                state->startup_ms = (int64_t)dealpg4_now_ms() - state->t0;
        }
        break;
    case DEALPG4_REC_STUB_EXEC_FAILED: {
        int64_t err = 0;
        const dealpg4_field_slice *f = dealpg4_parsed_field(&p, 1);

        state->stub_exec_failed_seen = 1;
        if (f != NULL)
            (void)dealpg4_field_decimal(f, &err);
        if (state->classification == DEALPG4_SUP_CLASS_NONE) {
            state->classification = DEALPG4_SUP_CLASS_EXEC_FAILED;
            if (state->phase == DEALPG4_PHASE_STARTUP
                && !state->release_write_ok && state->startup_ms == 0)
                state->startup_ms = (int64_t)dealpg4_now_ms() - state->t0;
        }
        /* A raw STUB_EXEC_FAILED is relayed as EXEC_FAILED <id> <errno>
         * (parent D3, catalog translation). */
        dealpg4_supervisor_publish_two_decimal(
            state, DEALPG4_REC_EXEC_FAILED, state->invocation_id, err);
        break;
    }
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
         * stub left to reap. */
        if (!state->eof_liveness && si.si_pid == state->stub_pid) {
            state->stub_reaped = 1;
            state->stub_si_code = si.si_code;
            state->stub_si_status = si.si_status;
            state->reap_count++;
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

/* Reap one waitid result: the stub/target's status is retained for the
 * D3 classification; every other reaped child is an adopted descendant
 * (reapCount/adoptCount per D7). */
static void dealpg4_supervisor_reap_one(dealpg4_supervisor_state *state,
                                        const siginfo_t *si)
{
    if (si->si_pid == state->stub_pid && !state->stub_reaped) {
        state->stub_reaped = 1;
        state->stub_si_code = si->si_code;
        state->stub_si_status = si->si_status;
    } else {
        state->adopt_count++;
    }
    state->reap_count++;
}

/* SIGCHLD events drive waitid(P_ALL, WEXITED|WNOHANG) until ECHILD /
 * nothing waitable (parent D1/D6): direct and adopted waitable
 * children are reaped and no zombie of this supervisor remains. */
static void dealpg4_supervisor_reap_all(dealpg4_supervisor_state *state)
{
    for (;;) {
        siginfo_t si;

        memset(&si, 0, sizeof(si));
        if (waitid(P_ALL, 0, &si, WEXITED | WNOHANG) != 0)
            break; /* ECHILD */
        if (si.si_pid == 0)
            break; /* nothing waitable */
        dealpg4_supervisor_reap_one(state, &si);
    }
    dealpg4_supervisor_evaluate(state);
}

/* The D3 exec-confirmation evaluation (release write success, EOF
 * without STUB_FAILED/STUB_EXEC_FAILED, positive exec evidence) and
 * the non-STARTED classification matrix, exactly per parent D3 — with
 * the channel machine's cancel paths: a cancel-path pre-release
 * classification is CALLER_LOST (the final record derives CLEAN
 * final=cancelled from a clean reaped exit or FAILED CALLER_LOST from
 * a cancel-path signal death at finalization), and a post-release
 * signal death is CALLER_LOST only when the cancel path itself issued
 * the signal (cancel_signals_issued); a death by the deadline
 * escalation — a cancel arriving mid-escalation after the T2 TERM —
 * classifies EXECUTION_TIMEOUT (parent D3, D5(c); never STARTED when
 * the cancel landed before exec confirmation). Signals issued by the
 * deadline escalation never set the cancel-path flag, so the owning
 * phase token keeps its record. */
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
            if (state->cancel_requested && state->cancel_signals_issued)
                state->classification = DEALPG4_SUP_CLASS_CALLER_LOST;
            else if (state->signals_issued_to_target)
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
         * failed: classified from the supervisor's own state plus the
         * reaped stub status (parent D3). The cancel path closed the
         * release pipe -> CALLER_LOST (clean-cancelled / CALLER_LOST
         * at finalization). */
        if (!state->status_eof || !state->stub_reaped)
            return;
        /* This branch is the startup-phase terminal classification for
         * the silent pre-release stub exits and the release-write
         * races: anchor startupMs at the classification (D7 — the
         * observed successful release write, or the startup-phase
         * terminal classification; the cancel/AUTH_FAILED paths anchor
         * with the same guard, so a later classification never
         * overwrites their value). */
        if (state->phase == DEALPG4_PHASE_STARTUP
            && state->startup_ms == 0)
            state->startup_ms = (int64_t)dealpg4_now_ms() - state->t0;
        if (state->cancel_requested) {
            state->classification = DEALPG4_SUP_CLASS_CALLER_LOST;
            return;
        }
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
        dealpg4_supervisor_note_started(state);
        return;
    }
    if (!state->stub_reaped)
        return; /* no evidence yet: wait for the reaped status */
    switch (state->stub_si_code) {
    case CLD_EXITED:
        if (state->stub_si_status < 2 || state->stub_si_status > 6) {
            /* 3(ii): exit code outside the reserved set (including 127,
             * valid only because condition 2 holds). */
            state->classification = DEALPG4_SUP_CLASS_SUCCESS;
            dealpg4_supervisor_note_started(state);
        } else if (state->release_recv_pid_match) {
            /* 3(iii): reserved code plus RELEASE_RECV with
             * pid == stubPid before the EOF. */
            state->classification = DEALPG4_SUP_CLASS_SUCCESS;
            dealpg4_supervisor_note_started(state);
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
        if (state->cancel_requested && state->cancel_signals_issued)
            state->classification = DEALPG4_SUP_CLASS_CALLER_LOST;
        else if (state->signals_issued_to_target)
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

/* === Deadlines, signaling, and the TIMER_FAILED path ==================== */

/* Signals target a verified negative PGID (parent D5): getpgrp() !=
 * targetPgid and kill(-targetPgid, 0) == 0 before each signal;
 * unverifiable targets are never signaled. Pre-release the retained
 * stubPid is signaled directly. Returns 1 when a signal was actually
 * delivered. The signals_issued_to_target record disambiguates the D3
 * classification (any supervisor-issued TERM/KILL / no
 * supervisor-issued signal); the caller on the cancel path
 * (apply_cancel only) additionally records its own delivery in
 * cancel_signals_issued so a deadline-path death classifies
 * EXECUTION_TIMEOUT even when a cancel arrived mid-escalation. */
static int dealpg4_supervisor_signal_target(dealpg4_supervisor_state *state,
                                             int sig)
{
    if (state->release_write_ok && state->stub_pgid > 0) {
        pid_t pgid = state->stub_pgid;

        if (getpgrp() != pgid && kill(-pgid, 0) == 0) {
            if (kill(-pgid, sig) == 0) {
                state->signals_issued_to_target = 1;
                return 1; /* actually delivered */
            }
        }
        return 0; /* unverifiable: never signaled */
    }
    if (state->stub_pid > 0 && kill(state->stub_pid, sig) == 0) {
        state->signals_issued_to_target = 1;
        return 1; /* actually delivered */
    }
    return 0;
}

/* The next applicable absolute phase deadline from the invocation
 * recipe state (monotonic.h). The loop computes its ppoll timeout from
 * this recipe state, never from the timerfd context alone: a failed
 * dealpg4_deadline_arm leaves the previous context state unchanged and
 * an idle context reports 0 remaining, so the recipe-state absolute
 * deadlines remain the escalation's wakeup source (the D8 TIMER_FAILED
 * wakeup pattern). */
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

static void dealpg4_supervisor_finalize(dealpg4_supervisor_state *state);

/* The mid-run TIMER_FAILED path (D8): a failed deadline arm while
 * installing a phase deadline terminates the invocation
 * FAILED <id> TIMER_FAILED — TERM immediately (pre-release: the
 * retained stub killed pre-release, never a release byte), KILL at the
 * absolute T3, reap, prove clean. The escalation's wakeup is the
 * recipe-state absolute deadlines (t2/t3), never the failed timerfd
 * context: the loop's ppoll timeout is computed from the recipe state,
 * so the escalation completes by the absolute deadlines with no
 * timerfd wakeup — no busy-spin on the idle context's 0-ms remaining
 * and no blocked-past-T3 failure. */
static void dealpg4_supervisor_timer_failed(dealpg4_supervisor_state *state)
{
    int first = (state->classification
                 != DEALPG4_SUP_CLASS_TIMER_FAILED)
                && !state->terminal_queued;

    state->classification = DEALPG4_SUP_CLASS_TIMER_FAILED;
    if (!first)
        return;
    if (state->release_write_ok) {
        if (!state->term_issued) {
            state->term_issued = 1;
            state->term_ms = (int64_t)dealpg4_now_ms() - state->t0;
            if (!state->stub_reaped)
                dealpg4_supervisor_signal_target(state, SIGTERM);
        }
    } else {
        state->release_frozen = 1;
        if (state->phase == DEALPG4_PHASE_STARTUP
            && state->startup_ms == 0)
            state->startup_ms = (int64_t)dealpg4_now_ms() - state->t0;
        if (state->release_fd >= 0) {
            close(state->release_fd);
            state->release_fd = -1;
        }
        if (!state->term_issued) {
            state->term_issued = 1;
            state->term_ms = (int64_t)dealpg4_now_ms() - state->t0;
            if (state->stub_pid > 0 && !state->stub_reaped
                && kill(state->stub_pid, SIGTERM) == 0)
                state->signals_issued_to_target = 1;
        }
    }
    if (state->phase == DEALPG4_PHASE_STARTUP
        || state->phase == DEALPG4_PHASE_RUN)
        state->phase = DEALPG4_PHASE_TERM;
}

/* Phase escalation per the parent D4 recipe, applied in ascending
 * deadline order (T1, T2, T3, T4, T5) so every boundary crossed in
 * one hop is caught up in the same call: the phase-gated conditions
 * are idempotent, and a loop iteration that resumes past the T4 proof
 * deadline must never skip the T2 TERM or the T3 KILL — a stalled
 * supervisor resumes the escalation instead of abandoning the target
 * tree (D5: the supervisor never exits-and-orphans a released
 * target). T1 pre-release: STARTUP_TIMEOUT classification and the
 * retained stub killed pre-release (TERM now, KILL at the absolute
 * T3) — never a release byte after T1 without an observed successful
 * release write (the successful write already ended the startup
 * phase); a pre-release cancel freezes the release first, so a
 * cancel-path invocation is never re-classified STARTUP_TIMEOUT.
 * T2: TERM against the verified negative PGID. T3: KILL. T4 (below
 * T5 only): the proof deadline — the survivor / DRAIN_FAILED /
 * PROOF_TIMEOUT classification and the terminal records at the
 * terminal classification. T5: OVERALL_TIMEOUT — with the escalation
 * and the cleanup completed before the exit: a still-live invocation
 * at the overall deadline is classified OVERALL_TIMEOUT (termMs/
 * killMs record the catch-up escalation above) and the loop stays
 * alive until the killed tree is reaped and the proof completes
 * (finalize runs from the proof completion), so the supervisor exits
 * only with the zero-survivor post-state. The post-T5 cleanup is
 * bounded: the first T5 sighting arms the proof window
 * (DEALPG4_SUP_T5_PROOF_BOUND_MS) and on expiry the terminal
 * classification runs regardless of proof completion — a tree that
 * cannot be cleaned (a never-EOF drain, a D-state survivor, a drain
 * read failure) can never complete the proof, and the bound keeps the
 * record terminating by its own deadline instead of hanging the
 * supervisor on the proof cadence forever. */
static void dealpg4_supervisor_advance_deadlines(
    dealpg4_supervisor_state *state)
{
    uint64_t now64 = dealpg4_now_ms();
    int64_t now = (int64_t)now64;

    if (state->done)
        return;
    if (state->phase == DEALPG4_PHASE_STARTUP
        && now >= state->dl.t1) {
        if (!state->release_write_ok
            && state->classification == DEALPG4_SUP_CLASS_NONE
            && !state->cancel_requested) {
            state->classification = DEALPG4_SUP_CLASS_STARTUP_TIMEOUT;
            if (state->startup_ms == 0)
                state->startup_ms = now - state->t0;
            state->term_issued = 1;
            state->term_ms = now - state->t0;
            if (state->stub_pid > 0 && !state->stub_reaped
                && kill(state->stub_pid, SIGTERM) == 0)
                state->signals_issued_to_target = 1;
            state->release_frozen = 1;
        }
        state->phase = DEALPG4_PHASE_TERM;
        if (dealpg4_deadline_arm(&state->timer, (uint64_t)state->dl.t3)
            != 0)
            dealpg4_supervisor_timer_failed(state);
    }
    if (state->phase == DEALPG4_PHASE_RUN && now >= state->dl.t2) {
        state->phase = DEALPG4_PHASE_TERM;
        state->term_issued = 1;
        state->term_ms = now - state->t0;
        if (!state->stub_reaped)
            dealpg4_supervisor_signal_target(state, SIGTERM);
        if (dealpg4_deadline_arm(&state->timer, (uint64_t)state->dl.t3)
            != 0)
            dealpg4_supervisor_timer_failed(state);
    }
    if (state->phase == DEALPG4_PHASE_TERM && now >= state->dl.t3) {
        state->phase = DEALPG4_PHASE_KILL;
        state->kill_issued = 1;
        state->kill_ms = now - state->t0;
        if (!state->stub_reaped)
            dealpg4_supervisor_signal_target(state, SIGKILL);
        if (dealpg4_deadline_arm(&state->timer, (uint64_t)state->dl.t4)
            != 0)
            dealpg4_supervisor_timer_failed(state);
    }
    if (now >= state->dl.t4 && now < state->dl.t5) {
        /* The proof deadline classifies survivor / DRAIN_FAILED /
         * PROOF_TIMEOUT paths — including a channel-loss / protocol-
         * abort invocation whose proof still runs after the terminal
         * classification (proof_pending admits exactly that state).
         * Gated below T5: past the overall deadline the T5 branch
         * owns the classification and the exit, and a proof deadline
         * classification must not preempt the T5 cleanup. */
        if (dealpg4_supervisor_proof_pending(state))
            dealpg4_supervisor_proof_deadline(state);
    }
    if (now >= state->dl.t5) {
        if (state->terminal_queued) {
            /* The terminal record was already queued (the cleanup ran
             * to its terminal classification) or the record terminates
             * by its own deadline. */
            state->done = 1;
            return;
        }
        /* Live-unclassified at the overall deadline: the escalation
         * was caught up above in this same call (TERM at T2, KILL at
         * T3 — termMs/killMs record the issue times). Classify
         * OVERALL_TIMEOUT, the owning T5 token, and keep the loop
         * alive: SIGCHLD reaping and the proof loop complete the
         * zero-survivor cleanup (the proof pass finalizes the
         * invocation with the REPORT/FAILED records) before the
         * supervisor exits. proof_failed_class is set at finalize for
         * this token (the pinned drainEof = 0 consequence) — not
         * here, so the proof loop still runs. The cleanup itself is
         * bounded: the first T5 sighting arms the post-T5 proof
         * window (DEALPG4_SUP_T5_PROOF_BOUND_MS), and on expiry the
         * terminal classification runs regardless of proof completion
         * — a tree that cannot be cleaned (a drain that never reaches
         * EOF, a D-state survivor, or a drain read failure) can never
         * complete the proof, and without the bound the supervisor
         * would hang on the proof cadence forever with no REPORT, no
         * terminal record, and no exit. */
        state->classification = DEALPG4_SUP_CLASS_OVERALL_TIMEOUT;
        if (state->proof_ms == 0)
            state->proof_ms = now - state->t0;
        if (state->t5_proof_bound_ms == 0)
            state->t5_proof_bound_ms =
                now + DEALPG4_SUP_T5_PROOF_BOUND_MS;
        if (now >= state->t5_proof_bound_ms) {
            dealpg4_supervisor_t5_proof_expiry(state);
            return;
        }
    }
}

/* === Proof loop (parent D6) ============================================= */

/* The proof is pending from the terminal classification until the
 * proof passes or T4/T5: the stub must be reaped (or never forked for
 * the CLEANUP_FAILED / STUB_BOOTSTRAP_FAILED record paths). */
static int dealpg4_supervisor_proof_pending(
    const dealpg4_supervisor_state *state)
{
    if (state->proof_done || state->proof_failed_class)
        return 0;
    if (state->terminal_queued) {
        /* After channel loss or a protocol abort the invocation's own
         * per-state termination still completes to its terminal
         * classification (D5): the proof loop keeps running with no
         * further publication, bounded by the T4/T5 recipe state. */
        return (state->channel_lost || state->protocol_aborted) ? 1 : 0;
    }
    if (state->classification < DEALPG4_SUP_CLASS_SUCCESS)
        return 0;
    return state->stub_reaped || state->stub_pid < 0;
}

/* /proc scan: no task with pgrp == targetPgid, no task with session ==
 * targetSid, no task with ppid == supervisorPid (or an adopted
 * descendant chain) — bounded, with every found survivor's pid
 * recorded for signaling. The stub pid and the supervisor itself are
 * never survivor candidates. */
static void dealpg4_supervisor_scan_proc(dealpg4_supervisor_state *state,
                                         dealpg4_sup_survivors *surv)
{
    DIR *dir;
    struct dirent *ent;
    pid_t me = getpid();
    pid_t adopted[DEALPG4_SUP_SURVIVOR_VIEW_MAX];
    size_t nadopted = 0;
    int pass;

    memset(surv, 0, sizeof(*surv));
    surv->ok = 1;
    if (state->stub_pgid == 0 && state->stub_sid == 0)
        return; /* no verified target identity: nothing to scan for */

    dir = opendir("/proc");
    if (dir == NULL) {
        surv->ok = 0;
        return;
    }
    for (pass = 0; pass < 8; pass++) {
        int found_new = 0;

        rewinddir(dir);
        while ((ent = readdir(dir)) != NULL) {
            const char *name = ent->d_name;
            pid_t pid = 0;
            pid_t ppid = 0;
            pid_t pgrp = 0;
            pid_t session = 0;
            size_t i;
            int is_adopted = 0;

            if (name[0] < '0' || name[0] > '9')
                continue;
            for (i = 0; name[i] != '\0'; i++) {
                int d = name[i] - '0';

                if (d < 0 || d > 9)
                    break;
                if (pid > (INT_MAX - d) / 10) {
                    pid = INT_MAX;
                    break;
                }
                pid = pid * 10 + d;
            }
            if (name[i] != '\0' || pid <= 0)
                continue;
            if (pid == me || pid == state->stub_pid)
                continue;
            if (dealpg4_proc_stat_identity(pid, &ppid, &pgrp, &session)
                != 0)
                continue; /* the task raced away */
            for (i = 0; i < nadopted; i++) {
                if (ppid == adopted[i]) {
                    is_adopted = 1;
                    break;
                }
            }
            if (ppid == me)
                is_adopted = 1;
            if (pgrp == state->stub_pgid)
                surv->group_found = 1;
            if (session == state->stub_sid)
                surv->session_found = 1;
            if (is_adopted)
                surv->adopted_found = 1;
            if (surv->group_found || surv->session_found
                || surv->adopted_found) {
                if (surv->count < DEALPG4_SUP_SURVIVOR_VIEW_MAX) {
                    surv->pids[surv->count++] = pid;
                }
            }
            if (is_adopted && nadopted < DEALPG4_SUP_SURVIVOR_VIEW_MAX) {
                size_t known = 0;

                for (i = 0; i < nadopted; i++) {
                    if (adopted[i] == pid) {
                        known = 1;
                        break;
                    }
                }
                if (!known) {
                    adopted[nadopted++] = pid;
                    found_new = 1;
                }
            }
        }
        if (!found_new)
            break;
    }
    closedir(dir);
}

/* Signal every survivor found by the scan by pid: TERM at discovery,
 * KILL once the absolute T3 deadline passed (parent D5 adopted/
 * escaped-descendant signaling; group members are additionally
 * covered by the verified negative-PGID escalation). */
static void dealpg4_supervisor_signal_survivors(
    dealpg4_supervisor_state *state, const dealpg4_sup_survivors *surv)
{
    int64_t now = (int64_t)dealpg4_now_ms();
    int sig = now >= state->dl.t3 ? SIGKILL : SIGTERM;
    size_t i;

    for (i = 0; i < surv->count; i++) {
        if (surv->pids[i] > 0)
            (void)kill(surv->pids[i], sig);
    }
}

/* One proof pass: (a) the reap loop — waitid(P_ALL, WEXITED|WNOHANG)
 * until ECHILD / nothing waitable (no zombie of this supervisor); (b)
 * the /proc scans with adopted-descendant signaling; (c) both stream
 * read ends at EOF (or a drain failure). Success = all three hold in
 * one pass plus a confirming second pass after a bounded ~10 ms poll
 * (parent D6). */
static void dealpg4_supervisor_proof_pass(dealpg4_supervisor_state *state)
{
    dealpg4_sup_survivors surv;
    uint64_t now64 = dealpg4_now_ms();
    int64_t now = (int64_t)now64;
    int clean;

    if (state->proof_done)
        return;
    if (state->terminal_queued
        && !(state->channel_lost || state->protocol_aborted))
        return; /* normal terminal: the cleanup already ran */

    dealpg4_supervisor_reap_all(state);

    dealpg4_supervisor_scan_proc(state, &surv);
    if (surv.group_found || surv.session_found || surv.adopted_found)
        dealpg4_supervisor_signal_survivors(state, &surv);
    state->group_clean = surv.ok && !surv.group_found;
    state->session_clean = surv.ok && !surv.session_found;
    state->drain_ok =
        (state->drains_active
             ? (state->drain_out->eof && !state->drain_out->failed
                && state->drain_err->eof && !state->drain_err->failed)
             : 1);

    clean = state->group_clean && state->session_clean
            && surv.ok && !surv.adopted_found && state->drain_ok;
    if (clean) {
        if (!state->proof_first_pass) {
            state->proof_first_pass = 1;
            state->proof_first_pass_ms = now;
            if (state->proof_ms == 0)
                state->proof_ms = now - state->t0;
        } else if (now - state->proof_first_pass_ms >= 10) {
            state->proof_done = 1;
        }
    } else {
        state->proof_first_pass = 0;
    }
    state->proof_next_pass_ms = now + 5;

    if (state->proof_done)
        dealpg4_supervisor_finalize(state);
}

/* One waitid probe with WNOHANG: 1 when a waitable child exists (a
 * zombie of this supervisor at the evaluation instant). */
static int dealpg4_supervisor_has_waitable(
    const dealpg4_supervisor_state *state)
{
    siginfo_t si;

    (void)state;
    memset(&si, 0, sizeof(si));
    if (waitid(P_ALL, 0, &si, WEXITED | WNOHANG) != 0)
        return 0; /* ECHILD */
    return si.si_pid != 0;
}

/* The T4 proof deadline: any survivor names its token — GROUP_SURVIVOR
 * / SESSION_SURVIVOR / ADOPTED_SURVIVOR / ZOMBIE_SURVIVOR /
 * DRAIN_FAILED — otherwise PROOF_TIMEOUT (the confirming pass did not
 * complete). Every survivor the scan discovers is signaled by pid
 * first (parent D5/D6: adopted descendants are discovered by the
 * /proc ppid scan and signaled TERM then KILL — at the deadline the
 * escalation stage is KILL): in the normal flow the proof passes
 * signaled them repeatedly already, and in the T2->T4 catch-up the
 * proof passes are preempted by this check, so the check itself must
 * complete the escalation instead of classifying a never-signaled
 * escapee. REPORT and the terminal record publish at the terminal
 * classification without the incomplete stream's OUT_END (D5(d)). */
static void dealpg4_supervisor_proof_deadline(
    dealpg4_supervisor_state *state)
{
    dealpg4_sup_survivors surv;
    int64_t now = (int64_t)dealpg4_now_ms();

    dealpg4_supervisor_reap_all(state);
    dealpg4_supervisor_scan_proc(state, &surv);
    if (surv.group_found || surv.session_found || surv.adopted_found)
        dealpg4_supervisor_signal_survivors(state, &surv);
    state->group_clean = surv.ok && !surv.group_found;
    state->session_clean = surv.ok && !surv.session_found;
    state->drain_ok =
        (state->drains_active
             ? (state->drain_out->eof && !state->drain_out->failed
                && state->drain_err->eof && !state->drain_err->failed)
             : 1);

    if (surv.group_found)
        state->classification = DEALPG4_SUP_CLASS_GROUP_SURVIVOR;
    else if (surv.session_found)
        state->classification = DEALPG4_SUP_CLASS_SESSION_SURVIVOR;
    else if (surv.adopted_found)
        state->classification = DEALPG4_SUP_CLASS_ADOPTED_SURVIVOR;
    else if (dealpg4_supervisor_has_waitable(state))
        state->classification = DEALPG4_SUP_CLASS_ZOMBIE_SURVIVOR;
    else if (!state->drain_ok)
        state->classification = DEALPG4_SUP_CLASS_DRAIN_FAILED;
    else
        state->classification = DEALPG4_SUP_CLASS_PROOF_TIMEOUT;
    state->proof_failed_class = 1;
    if (state->proof_ms == 0)
        state->proof_ms = now - state->t0;

    state->survivor_count =
        surv.count < DEALPG4_SUP_SURVIVOR_VIEW_MAX
            ? surv.count
            : DEALPG4_SUP_SURVIVOR_VIEW_MAX;
    memcpy(state->survivor_pids, surv.pids,
           state->survivor_count * sizeof(surv.pids[0]));

    dealpg4_supervisor_finalize(state);
}

/* The post-T5 proof window expired: the proof could not complete —
 * a drain that never reaches EOF, a D-state survivor, or a drain
 * read failure. One final reap + /proc scan signals every survivor
 * one last time and records the final group/session/drain items for
 * the REPORT, then the terminal classification runs regardless of
 * proof completion: OVERALL_TIMEOUT — the owning T5 token (parent D4
 * phase overruns, and the classification the T5 branch already
 * applied) — with the pinned REPORT.drainEof = 0 consequence applied
 * at finalize. Exactly one REPORT and exactly one terminal FAILED
 * record publish, and the supervisor exits: the record terminates by
 * its own deadline, never an unbounded post-T5 proof loop. The
 * final-scan survivor state is recorded (groupProof/sessionProof and
 * the bounded in-process survivor view) exactly like the T4 proof
 * deadline so the REPORT stays the bounded evidence surface. */
static void dealpg4_supervisor_t5_proof_expiry(
    dealpg4_supervisor_state *state)
{
    dealpg4_sup_survivors surv;

    dealpg4_supervisor_reap_all(state);
    dealpg4_supervisor_scan_proc(state, &surv);
    if (surv.group_found || surv.session_found || surv.adopted_found)
        dealpg4_supervisor_signal_survivors(state, &surv);
    state->group_clean = surv.ok && !surv.group_found;
    state->session_clean = surv.ok && !surv.session_found;
    state->drain_ok =
        (state->drains_active
             ? (state->drain_out->eof && !state->drain_out->failed
                && state->drain_err->eof && !state->drain_err->failed)
             : 1);

    /* The reap above runs the D3 exec-confirmation evaluation, which
     * may (re-)classify from the reaped stub status; past the overall
     * deadline the owning token is OVERALL_TIMEOUT, so the terminal
     * classification restores it exactly as the T5 branch pinned it. */
    state->classification = DEALPG4_SUP_CLASS_OVERALL_TIMEOUT;
    if (state->proof_ms == 0)
        state->proof_ms = (int64_t)dealpg4_now_ms() - state->t0;

    state->survivor_count =
        surv.count < DEALPG4_SUP_SURVIVOR_VIEW_MAX
            ? surv.count
            : DEALPG4_SUP_SURVIVOR_VIEW_MAX;
    memcpy(state->survivor_pids, surv.pids,
           state->survivor_count * sizeof(surv.pids[0]));

    dealpg4_supervisor_finalize(state);
}

/* === Channel machine (D5) =============================================== */

static void dealpg4_supervisor_apply_cancel(dealpg4_supervisor_state *state)
{
    uint64_t now = dealpg4_now_ms();

    if (state->cancel_requested)
        return; /* idempotent */
    state->cancel_requested = 1;
    if (!state->release_write_done) {
        /* PRE_RELEASE (pre- or post-ACK): the cancel freezes the
         * release path — the release byte is never written (an
         * already-applied ACK no longer releases); close the release
         * pipe (the stub exits pre-exec on EOF); TERM the retained
         * stubPid; KILL at the absolute T3 if still live. The cancel
         * application ends the startup phase (D7: startupMs anchors at
         * the end of the startup phase). */
        state->release_frozen = 1;
        if (state->phase == DEALPG4_PHASE_STARTUP
            && state->startup_ms == 0)
            state->startup_ms = (int64_t)now - state->t0;
        if (state->release_fd >= 0) {
            close(state->release_fd);
            state->release_fd = -1;
        }
        if (!state->term_issued) {
            state->term_issued = 1;
            state->term_ms = (int64_t)now - state->t0;
            if (state->stub_pid > 0 && !state->stub_reaped
                && kill(state->stub_pid, SIGTERM) == 0) {
                state->signals_issued_to_target = 1;
                state->cancel_signals_issued = 1;
            }
        }
        if (state->phase == DEALPG4_PHASE_STARTUP
            || state->phase == DEALPG4_PHASE_RUN)
            state->phase = DEALPG4_PHASE_TERM;
    } else {
        /* RELEASED: TERM immediately against the verified negative
         * PGID (unverifiable targets never signaled), KILL at the
         * absolute T3 — the cancel neither extends nor shortens any
         * absolute deadline; adopted descendants are signaled TERM
         * then KILL by pid by the proof loop. */
        if (!state->term_issued) {
            state->term_issued = 1;
            state->term_ms = (int64_t)now - state->t0;
            if (!state->stub_reaped
                && dealpg4_supervisor_signal_target(state, SIGTERM))
                state->cancel_signals_issued = 1;
        }
        if (now >= (uint64_t)state->dl.t3) {
            if (!state->kill_issued) {
                state->kill_issued = 1;
                state->kill_ms = (int64_t)now - state->t0;
                if (!state->stub_reaped
                    && dealpg4_supervisor_signal_target(state, SIGKILL))
                    state->cancel_signals_issued = 1;
            }
            if (state->phase == DEALPG4_PHASE_RUN
                || state->phase == DEALPG4_PHASE_TERM)
                state->phase = DEALPG4_PHASE_KILL;
        } else if (state->phase == DEALPG4_PHASE_STARTUP
                   || state->phase == DEALPG4_PHASE_RUN) {
            state->phase = DEALPG4_PHASE_TERM;
        }
    }
}

/* Control-channel loss: read-side EOF/HUP or an irrecoverable write
 * failure (EPIPE under the D8 SIGPIPE pin — an error return, never
 * process death). Applies the per-state cancel semantics without
 * requiring a CANCEL record; in TERMINAL there is no new termination
 * work (the cleanup already ran to the terminal classification) — the
 * undelivered queued records drop with the close. */
static void dealpg4_supervisor_channel_lost(
    dealpg4_supervisor_state *state)
{
    if (state->channel_lost)
        return;
    state->channel_lost = 1;
    if (state->control_fd >= 0) {
        close(state->control_fd);
        state->control_fd = -1;
    }
    dealpg4_supervisor_queue_clear(&state->queue);
    if (state->terminal_queued)
        return; /* TERMINAL: no new termination work */
    dealpg4_supervisor_apply_cancel(state);
}

/* PROTOCOL_ERROR (framing defects, unknown types, oversize records,
 * records unexpected in the channel state — including any received
 * BYE): the supervisor closes the control channel without a FAILED
 * record and without any further record publication, then applies the
 * per-state invocation termination exactly as channel loss — the
 * supervisor never exits-and-orphans a released target. Serve exit 2
 * on this no-record aborted path (D5). */
static void dealpg4_supervisor_protocol_abort(
    dealpg4_supervisor_state *state)
{
    if (state->protocol_aborted)
        return;
    state->protocol_aborted = 1;
    if (state->control_fd >= 0) {
        close(state->control_fd);
        state->control_fd = -1;
    }
    dealpg4_supervisor_queue_clear(&state->queue);
    if (state->terminal_queued)
        return; /* TERMINAL: no new termination work (the cleanup already
                    ran to the terminal classification) — the
                    undelivered queued records drop with the close */
    dealpg4_supervisor_apply_cancel(state);
}

/* A well-formed ACK while the channel is still pre-release (the
 * release write never happened, no terminal record is queued) is
 * always a record-level operation — the canonical ACK rule (catalog;
 * parent D9): applied exactly once when the invocation can still apply
 * it, and every other pre-release ACK — a wrong invocationId or nonce,
 * a second ACK, or an ACK the frozen invocation can no longer apply
 * (an AUTH_FAILED/cancel classification or the T1 deadline already
 * fired) — takes the record-level AUTH_FAILED path, idempotent for a
 * repeated rejection. A pre-release ACK never aborts the channel, so
 * the pinned REPORT + FAILED <id> AUTH_FAILED pair is always published
 * before the close. Only after the release write (or against a
 * terminal invocation) is an ACK state-unexpected -> PROTOCOL_ERROR —
 * the RELEASED/TERMINAL expectation sets no longer admit ACK, so the
 * record dispatcher classifies those before this handler; the explicit
 * guard keeps the split local. */
static void dealpg4_supervisor_ack(dealpg4_supervisor_state *state,
                                   const dealpg4_parsed *p)
{
    const dealpg4_field_slice *idf = dealpg4_parsed_field(p, 0);
    const dealpg4_field_slice *noncef = dealpg4_parsed_field(p, 1);
    int64_t id = 0;
    dealpg4_ack_facts facts;
    dealpg4_classification cls;
    int in_apply;

    if (idf != NULL)
        (void)dealpg4_field_decimal(idf, &id);

    if (state->release_write_done || state->terminal_queued) {
        /* Post-release / terminal ACK: state-unexpected (belt-and-
         * braces — the RELEASED/TERMINAL expectation sets already
         * exclude ACK, so the dispatcher's expectation check
         * classifies these before this handler). */
        dealpg4_supervisor_protocol_abort(state);
        return;
    }
    in_apply = (state->phase == DEALPG4_PHASE_STARTUP
                && !state->release_write_done
                && state->classification == DEALPG4_SUP_CLASS_NONE
                && !state->cancel_requested
                && (int64_t)dealpg4_now_ms() < state->dl.t1);
    facts.invocation_id_known = (id == state->invocation_id);
    facts.record_in_apply_phase = in_apply;
    facts.nonce_matches = (noncef != NULL
                           && noncef->len == DEALPG4_NONCE_HEX_CHARS
                           && memcmp(noncef->p, state->nonce,
                                     DEALPG4_NONCE_HEX_CHARS) == 0);
    facts.first_ack = !state->ack_applied;
    cls = dealpg4_ack_classify(&facts);
    if (cls == DEALPG4_CLASS_OK) {
        state->ack_applied = 1;
        /* The release write happens at the release point (the end of
         * the event batch): a second ACK or a CANCEL in the same batch
         * observes the still-pre-release state (D5(a)). */
        return;
    }
    /* AUTH_FAILED: the record-level path (idempotent for a repeated
     * rejection — the channel is never aborted): publish FAILED
     * <id> AUTH_FAILED at the terminal classification (channel open
     * until delivered), stub killed pre-release, reaped, proven clean,
     * never a release byte. */
    dealpg4_supervisor_classify_auth_failed(state);
}

/* A well-formed CANCEL applies only when the invocation id and nonce
 * both match the pending invocation and the invocation is live (not
 * terminal). A mismatch or a terminal CANCEL is the record-level
 * REJECT <id> - CANCEL_AUTH_FAILED (channel open, invocation
 * untouched); in TERMINAL the REJECT queues behind the already-queued
 * terminal record and flushes before the channel close (D5). */
static void dealpg4_supervisor_cancel(dealpg4_supervisor_state *state,
                                      const dealpg4_parsed *p)
{
    const dealpg4_field_slice *idf = dealpg4_parsed_field(p, 0);
    const dealpg4_field_slice *noncef = dealpg4_parsed_field(p, 1);
    int64_t id = 0;
    dealpg4_cancel_facts facts;
    dealpg4_classification cls;

    if (idf != NULL)
        (void)dealpg4_field_decimal(idf, &id);

    facts.invocation_id_known = (id == state->invocation_id);
    facts.record_live = !state->terminal_queued;
    facts.nonce_matches = (noncef != NULL
                           && noncef->len == DEALPG4_NONCE_HEX_CHARS
                           && memcmp(noncef->p, state->nonce,
                                     DEALPG4_NONCE_HEX_CHARS) == 0);
    cls = dealpg4_cancel_classify(&facts);
    if (cls == DEALPG4_CLASS_OK) {
        dealpg4_supervisor_apply_cancel(state);
        return;
    }
    /* Record-level rejection: the invocation is untouched and
     * continues under its own absolute deadlines; the channel stays
     * open. The REJECT echoes the CANCEL record's own id. */
    dealpg4_supervisor_publish_reject(state, id);
}

/* One complete control-channel record line: framing (dealpg4_parse),
 * the channel-state expectation set, and the per-type handlers. Every
 * framing defect, unknown type, oversize record, or record unexpected
 * in the current state classifies PROTOCOL_ERROR (D5). */
static void dealpg4_supervisor_channel_record(
    dealpg4_supervisor_state *state, const char *line, size_t len)
{
    dealpg4_parsed p;
    dealpg4_classification cls;

    if (dealpg4_parse(line, len, &p) != DEALPG4_PARSE_OK) {
        dealpg4_supervisor_protocol_abort(state);
        return;
    }
    cls = dealpg4_expectation_check(&state->expect, &p);
    if (cls != DEALPG4_CLASS_OK) {
        dealpg4_supervisor_protocol_abort(state);
        return;
    }
    switch (p.type) {
    case DEALPG4_REC_ACK:
        dealpg4_supervisor_ack(state, &p);
        break;
    case DEALPG4_REC_CANCEL:
        dealpg4_supervisor_cancel(state, &p);
        break;
    default:
        /* Unreachable: the expectation set admits only ACK/CANCEL. */
        dealpg4_supervisor_protocol_abort(state);
        break;
    }
}

/* Control-channel read side: complete record lines are consumed as
 * they arrive; EOF/HUP is channel loss. A line longer than the largest
 * catalog cap (no LF within the bound) is an oversize framing defect —
 * PROTOCOL_ERROR. At EOF a buffered partial line is the same defect
 * (a record is complete only at LF). */
static void dealpg4_supervisor_read_control(dealpg4_supervisor_state *state)
{
    if (state->control_fd < 0)
        return;
    for (;;) {
        ssize_t r;

        if (state->ctrl_buf_len >= sizeof(state->ctrl_buf) - 1) {
            dealpg4_supervisor_protocol_abort(state);
            return;
        }
        r = read(state->control_fd,
                 state->ctrl_buf + state->ctrl_buf_len,
                 sizeof(state->ctrl_buf) - 1 - state->ctrl_buf_len);
        if (r > 0) {
            char *nl;

            state->ctrl_buf_len += (size_t)r;
            while ((nl = memchr(state->ctrl_buf, '\n',
                                state->ctrl_buf_len)) != NULL) {
                size_t line_len = (size_t)(nl - state->ctrl_buf) + 1;
                size_t rest = state->ctrl_buf_len - line_len;

                dealpg4_supervisor_channel_record(state,
                                                  state->ctrl_buf,
                                                  line_len);
                if (state->protocol_aborted)
                    return;
                memmove(state->ctrl_buf, nl + 1, rest);
                state->ctrl_buf_len = rest;
            }
            continue;
        }
        if (r == 0) {
            /* EOF. A buffered partial line is an incomplete record —
             * a framing defect. */
            if (state->ctrl_buf_len > 0) {
                dealpg4_supervisor_protocol_abort(state);
                return;
            }
            dealpg4_supervisor_channel_lost(state);
            return;
        }
        if (errno == EINTR)
            continue;
        if (errno == EAGAIN || errno == EWOULDBLOCK)
            return;
        /* Any other read error: channel loss. */
        dealpg4_supervisor_channel_lost(state);
        return;
    }
}

/* === REPORT and terminal records ======================================== */

static const char *dealpg4_supervisor_class_token(
    dealpg4_supervise_class cls)
{
    switch (cls) {
    case DEALPG4_SUP_CLASS_STUB_BOOTSTRAP_FAILED:
        return "STUB_BOOTSTRAP_FAILED";
    case DEALPG4_SUP_CLASS_EXEC_FAILED:
        return "EXEC_FAILED";
    case DEALPG4_SUP_CLASS_AUTH_FAILED:
        return "AUTH_FAILED";
    case DEALPG4_SUP_CLASS_STARTUP_TIMEOUT:
        return "STARTUP_TIMEOUT";
    case DEALPG4_SUP_CLASS_STUB_PRE_RELEASE_EXIT:
        return "STUB_PRE_RELEASE_EXIT";
    case DEALPG4_SUP_CLASS_CALLER_LOST:
        return "CALLER_LOST";
    case DEALPG4_SUP_CLASS_EXECUTION_TIMEOUT:
        return "EXECUTION_TIMEOUT";
    case DEALPG4_SUP_CLASS_UNVERIFIED_TARGET_DEATH:
        return "UNVERIFIED_TARGET_DEATH";
    case DEALPG4_SUP_CLASS_PROOF_TIMEOUT:
        return "PROOF_TIMEOUT";
    case DEALPG4_SUP_CLASS_OVERALL_TIMEOUT:
        return "OVERALL_TIMEOUT";
    case DEALPG4_SUP_CLASS_TIMER_FAILED:
        return "TIMER_FAILED";
    case DEALPG4_SUP_CLASS_CLEANUP_FAILED:
        return "CLEANUP_FAILED";
    case DEALPG4_SUP_CLASS_GROUP_SURVIVOR:
        return "GROUP_SURVIVOR";
    case DEALPG4_SUP_CLASS_SESSION_SURVIVOR:
        return "SESSION_SURVIVOR";
    case DEALPG4_SUP_CLASS_ADOPTED_SURVIVOR:
        return "ADOPTED_SURVIVOR";
    case DEALPG4_SUP_CLASS_ZOMBIE_SURVIVOR:
        return "ZOMBIE_SURVIVOR";
    case DEALPG4_SUP_CLASS_DRAIN_FAILED:
        return "DRAIN_FAILED";
    default:
        return "CLEANUP_FAILED"; /* unreachable: finalize is terminal */
    }
}

/* The canonical 19-field REPORT (fixed catalog order) with the D7
 * semantics: each ms field = CLOCK_MONOTONIC - T0 at its owning event
 * (execMs at the exec-confirmation classification, 0 when exec is
 * never confirmed); exitCode/termSignal per the reaped-status
 * convention; stdoutBytes/stderrBytes = the drain totals;
 * stdoutTruncated/stderrTruncated = drain-cap truncation OR the
 * serve-mode queue-overflow drop (run-mode passthrough drops never set
 * them); groupProof/sessionProof = the final scan items; drainEof = 0
 * on DRAIN_FAILED/PROOF_TIMEOUT/OVERALL_TIMEOUT/survivor paths;
 * failureToken = "-" on success and clean cancels, otherwise the named
 * token. The serialized line goes to the serve-mode queue or the
 * run-mode stderr pending slot. */
static void dealpg4_supervisor_build_report(dealpg4_supervisor_state *state)
{
    int64_t now = (int64_t)dealpg4_now_ms();
    int64_t exit_code = 0;
    int64_t term_signal = 0;
    char fbuf[DEALPG4_MAX_FIXED_FIELDS][32];
    dealpg4_field_value fields[DEALPG4_MAX_FIXED_FIELDS];
    size_t written = 0;
    int drain_eof;
    const char *token;

    if (state->stub_reaped) {
        switch (state->stub_si_code) {
        case CLD_EXITED:
            exit_code = state->stub_si_status;
            term_signal = 0;
            break;
        case CLD_KILLED:
        case CLD_DUMPED:
            exit_code = 128 + state->stub_si_status;
            term_signal = state->stub_si_status;
            break;
        default:
            exit_code = 0;
            term_signal = 0;
            break;
        }
    }
    drain_eof =
        (state->drains_active
             ? (state->drain_out->eof && !state->drain_out->failed
                && state->drain_err->eof && !state->drain_err->failed)
             : 1)
        && !state->proof_failed_class;
    token = (state->failure_token[0] != '\0') ? state->failure_token
                                              : "-";

#define DEALPG4_SUP_REPORT_SET(f, v)                                      \
    do {                                                                  \
        snprintf(fbuf[f], sizeof(fbuf[f]), "%lld", (long long)(v));       \
        fields[f].data = fbuf[f];                                         \
        fields[f].len = strlen(fbuf[f]);                                  \
    } while (0)

    DEALPG4_SUP_REPORT_SET(0, exit_code);
    DEALPG4_SUP_REPORT_SET(1, term_signal);
    DEALPG4_SUP_REPORT_SET(2, now - state->t0);
    DEALPG4_SUP_REPORT_SET(3, state->startup_ms);
    DEALPG4_SUP_REPORT_SET(4, state->exec_ms);
    DEALPG4_SUP_REPORT_SET(5, state->term_ms);
    DEALPG4_SUP_REPORT_SET(6, state->kill_ms);
    DEALPG4_SUP_REPORT_SET(7, state->proof_ms);
    DEALPG4_SUP_REPORT_SET(8, state->final_ms);
    DEALPG4_SUP_REPORT_SET(9, state->reap_count);
    DEALPG4_SUP_REPORT_SET(10, state->adopt_count);
    DEALPG4_SUP_REPORT_SET(11, (int64_t)state->drain_out->total_read);
    DEALPG4_SUP_REPORT_SET(12, (int64_t)state->drain_err->total_read);
    DEALPG4_SUP_REPORT_SET(13, (state->drain_out->truncated
                                || state->relay_out.queue_dropped)
                                   ? 1
                                   : 0);
    DEALPG4_SUP_REPORT_SET(14, (state->drain_err->truncated
                                || state->relay_err.queue_dropped)
                                   ? 1
                                   : 0);
    DEALPG4_SUP_REPORT_SET(15, state->group_clean ? 1 : 0);
    DEALPG4_SUP_REPORT_SET(16, state->session_clean ? 1 : 0);
    DEALPG4_SUP_REPORT_SET(17, drain_eof ? 1 : 0);
    fields[18].data = token;
    fields[18].len = strlen(token);
#undef DEALPG4_SUP_REPORT_SET

    if (dealpg4_serialize(DEALPG4_REC_REPORT, fields,
                          DEALPG4_MAX_FIXED_FIELDS, state->report_line,
                          sizeof state->report_line, &written) != 0)
        return; /* defect: a malformed REPORT line is never emitted */
    state->report_len = written;
    state->report_off = 0;

    /* In-process report view (battery assertions). */
    dealpg4_supervisor_last_result.report.exit_code = exit_code;
    dealpg4_supervisor_last_result.report.term_signal = term_signal;
    dealpg4_supervisor_last_result.report.elapsed_ms = now - state->t0;
    dealpg4_supervisor_last_result.report.startup_ms = state->startup_ms;
    dealpg4_supervisor_last_result.report.exec_ms = state->exec_ms;
    dealpg4_supervisor_last_result.report.term_ms = state->term_ms;
    dealpg4_supervisor_last_result.report.kill_ms = state->kill_ms;
    dealpg4_supervisor_last_result.report.proof_ms = state->proof_ms;
    dealpg4_supervisor_last_result.report.final_ms = state->final_ms;
    dealpg4_supervisor_last_result.report.reap_count = state->reap_count;
    dealpg4_supervisor_last_result.report.adopt_count = state->adopt_count;
    dealpg4_supervisor_last_result.report.stdout_bytes =
        state->drain_out->total_read;
    dealpg4_supervisor_last_result.report.stderr_bytes =
        state->drain_err->total_read;
    dealpg4_supervisor_last_result.report.stdout_truncated =
        (state->drain_out->truncated || state->relay_out.queue_dropped)
            ? 1
            : 0;
    dealpg4_supervisor_last_result.report.stderr_truncated =
        (state->drain_err->truncated || state->relay_err.queue_dropped)
            ? 1
            : 0;
    dealpg4_supervisor_last_result.report.group_proof =
        state->group_clean ? 1 : 0;
    dealpg4_supervisor_last_result.report.session_proof =
        state->session_clean ? 1 : 0;
    dealpg4_supervisor_last_result.report.drain_eof = drain_eof ? 1 : 0;

    if (state->serve_mode) {
        /* Serve: REPORT goes on the control channel, behind every
         * pending stream record (the queue is FIFO). */
        (void)dealpg4_supervisor_queue_append(&state->queue,
                                              state->report_line,
                                              state->report_len, 1, -1,
                                              0, 0);
    } else {
        /* Run: the REPORT line goes to stderr after the passthrough. */
        state->report_pending = 1;
    }
}

/* The terminal classification: derive the terminal record from the
 * classification, the cancel state, and the reaped status (D5(c)),
 * relay the final catch-up chunks and the OUT_ENDs of the EOF'd
 * streams (never an OUT_END for a stream that never reached EOF), and
 * queue REPORT + CLEAN/FAILED in catalog order. finalMs anchors at the
 * terminal record's serialization. */
static void dealpg4_supervisor_finalize(dealpg4_supervisor_state *state)
{
    dealpg4_supervise_terminal kind;
    const char *token;

    if (state->terminal_queued)
        return;
    if (state->protocol_aborted) {
        /* No records on the aborted path (D5): the invocation
         * termination already ran to its terminal classification. */
        state->terminal_queued = 1;
        state->channel_state = DEALPG4_CHAN_TERMINAL;
        return;
    }

    /* Final relay catch-up: the remaining drained bytes and each EOF'd
     * stream's OUT_END — on DRAIN_FAILED/PROOF_TIMEOUT/OVERALL_TIMEOUT
     * and survivor-token paths the incomplete stream's relayed chunk
     * sequence simply ends here, without its OUT_END (D5(d)). */
    dealpg4_supervisor_relay_stream(state, &state->relay_out);
    dealpg4_supervisor_relay_stream(state, &state->relay_err);

    /* Terminal kind + failure token. */
    if (state->cancel_requested) {
        if (state->stub_reaped && state->stub_si_code == CLD_EXITED) {
            /* Clean cancel: the stub/target exited on its own (the
             * pre-release release-EOF exit 5 or any post-release
             * CLD_EXITED). */
            kind = DEALPG4_SUP_TERMINAL_CLEAN_CANCELLED;
            token = "-";
        } else if (state->stub_reaped
                   && (state->stub_si_code == CLD_KILLED
                       || state->stub_si_code == CLD_DUMPED)
                   && state->cancel_signals_issued) {
            /* Cancel-path signal death: the cancel path itself issued
             * the TERM/KILL (D5(c)). */
            kind = DEALPG4_SUP_TERMINAL_FAILED;
            token = "CALLER_LOST";
        } else if (state->stub_reaped
                   && (state->stub_si_code == CLD_KILLED
                       || state->stub_si_code == CLD_DUMPED)
                   && state->signals_issued_to_target) {
            /* A supervisor-issued signal death on a non-cancel path:
             * the deadline escalation owns the death (the T2/T3
             * TERM/KILL, the T1 pre-release TERM, or the TIMER_FAILED
             * escalation) — the cancel that arrived mid-escalation
             * issued no signal of its own, so the owning phase token
             * classifies the record (parent D3: the T2/T3 deadline
             * path -> EXECUTION_TIMEOUT). */
            kind = DEALPG4_SUP_TERMINAL_FAILED;
            token = dealpg4_supervisor_class_token(
                state->classification);
        } else if (state->stub_reaped
                   && (state->stub_si_code == CLD_KILLED
                       || state->stub_si_code == CLD_DUMPED)) {
            /* A signal death with no supervisor-issued signal on the
             * record (parent D3). */
            kind = DEALPG4_SUP_TERMINAL_FAILED;
            token = "UNVERIFIED_TARGET_DEATH";
        } else if (state->stub_pid < 0) {
            /* Never forked: no stub/target ever existed (the
             * CLEANUP_FAILED / STUB_BOOTSTRAP_FAILED record paths
             * with a cancel in play) — clean cancelled, nothing ever
             * ran. */
            kind = DEALPG4_SUP_TERMINAL_CLEAN_CANCELLED;
            token = "-";
        } else {
            /* An un-reaped, still-live stub at the terminal
             * classification — the post-T5 proof-bound expiry of a
             * tree the escalation could not clean (a D-state
             * survivor: the pending SIGKILL bounds the eventual
             * process death but the stub was never reaped), or a T4
             * survivor-token classification: FAILED with the owning
             * token, never a false CLEAN final=cancelled while the
             * target tree still lives. A clean cancel requires the
             * stub/target to have exited on its own (the reaped
             * CLD_EXITED case above); every misclassification
             * direction stays a false failure, never a false clean
             * (D5(c), parent D3). */
            kind = DEALPG4_SUP_TERMINAL_FAILED;
            token = dealpg4_supervisor_class_token(
                state->classification);
        }
    } else if (state->classification == DEALPG4_SUP_CLASS_SUCCESS) {
        kind = DEALPG4_SUP_TERMINAL_CLEAN_SUCCESS;
        token = "-";
    } else {
        kind = DEALPG4_SUP_TERMINAL_FAILED;
        token = dealpg4_supervisor_class_token(state->classification);
    }
    state->final_kind = kind;
    snprintf(state->failure_token, sizeof state->failure_token, "%s",
             token);
    state->final_ms = (int64_t)dealpg4_now_ms() - state->t0;

    /* The OVERALL_TIMEOUT pin (D5(d)): REPORT.drainEof = 0 — the
     * streams never reached EOF before the overall deadline. The T5
     * catch-up cleanup still completes the proof before the exit (the
     * proof loop must run, so the consequence is applied here at the
     * terminal classification, never while the proof is pending). */
    if (state->classification == DEALPG4_SUP_CLASS_OVERALL_TIMEOUT)
        state->proof_failed_class = 1;

    dealpg4_supervisor_build_report(state);

    if (state->control_fd >= 0) {
        /* Terminal record in catalog order after REPORT. */
        {
            char idbuf[32];
            dealpg4_field_value fields[2];

            snprintf(idbuf, sizeof idbuf, "%lld",
                     (long long)state->invocation_id);
            fields[0].data = idbuf;
            fields[0].len = strlen(idbuf);
            if (kind == DEALPG4_SUP_TERMINAL_CLEAN_SUCCESS
                || kind == DEALPG4_SUP_TERMINAL_CLEAN_CANCELLED) {
                static const char success_text[] = "success";
                static const char cancelled_text[] = "cancelled";

                fields[1].data =
                    kind == DEALPG4_SUP_TERMINAL_CLEAN_SUCCESS
                        ? success_text
                        : cancelled_text;
                fields[1].len = strlen(fields[1].data);
                dealpg4_supervisor_publish(state, DEALPG4_REC_CLEAN,
                                           fields, 2);
            } else {
                fields[1].data = state->failure_token;
                fields[1].len = strlen(state->failure_token);
                dealpg4_supervisor_publish(state, DEALPG4_REC_FAILED,
                                           fields, 2);
            }
        }
        state->channel_state = DEALPG4_CHAN_TERMINAL;
        dealpg4_expectation_set_init(&state->expect);
        dealpg4_expectation_set_add(&state->expect, DEALPG4_REC_CANCEL);
    }
    state->terminal_queued = 1;
}

/* === Event-loop fd roles and handlers =================================== */

typedef enum dealpg4_loop_role {
    DEALPG4_ROLE_TIMER = 0,
    DEALPG4_ROLE_STATUS,
    DEALPG4_ROLE_SIGCHLD,
    DEALPG4_ROLE_STREAM_OUT,
    DEALPG4_ROLE_STREAM_ERR,
    DEALPG4_ROLE_CTRL_READ,
    DEALPG4_ROLE_CTRL_WRITE,
    DEALPG4_ROLE_STDOUT_WRITE,
    DEALPG4_ROLE_STDERR_WRITE
} dealpg4_loop_role;

/* Pump one drain on readability (parent D1/D7): repeated non-blocking
 * reads through the drain context until EAGAIN; on EOF (or a read
 * error) the read end is closed and leaves the poll set. Draining
 * continues past the cap so a saturated target can never deadlock on
 * a full pipe. In serve mode the pumped stream is relayed as OUT
 * chunks as it drains. */
static void dealpg4_supervisor_pump_stream(dealpg4_supervisor_state *state,
                                           int *fd, dealpg4_drain_ctx *drain,
                                           dealpg4_supervisor_relay *relay)
{
    if (*fd < 0)
        return;
    for (;;) {
        dealpg4_drain_status s = dealpg4_drain_pump(drain, *fd);

        if (s == DEALPG4_DRAIN_AGAIN) {
            dealpg4_supervisor_relay_stream(state, relay);
            return;
        }
        close(*fd);
        *fd = -1;
        dealpg4_supervisor_relay_stream(state, relay);
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
    dealpg4_supervisor_reap_all(state);
}

/* Run-mode stdout flush: the ready frame first (when requested), then
 * the stdout passthrough — both non-blocking. An EPIPE drops further
 * stdout writes (never process death; SIGPIPE is ignored). */
static void dealpg4_supervisor_flush_run_stdout(
    dealpg4_supervisor_state *state)
{
    if (state->serve_mode)
        return; /* serve: never writes stdio */

    if (state->ready_pending) {
        for (;;) {
            ssize_t r = write(1, state->ready_line + state->ready_off,
                              state->ready_len - state->ready_off);

            if (r > 0) {
                state->ready_off += (size_t)r;
                if (state->ready_off >= state->ready_len) {
                    state->ready_pending = 0;
                    break;
                }
                continue;
            }
            if (r < 0 && (errno == EINTR || errno == EAGAIN
                          || errno == EWOULDBLOCK))
                return;
            /* EPIPE or any other error: drop the ready frame and the
             * stdout passthrough. */
            state->ready_pending = 0;
            state->pass_out_lost = 1;
            return;
        }
    }
    if (!state->started_published)
        return;
    while (state->pass_out_off < state->drain_out->retained_len) {
        ssize_t r = write(1,
                          state->drain_out->retained
                              + state->pass_out_off,
                          state->drain_out->retained_len
                              - state->pass_out_off);

        if (r > 0) {
            state->pass_out_off += (size_t)r;
            continue;
        }
        if (r < 0 && (errno == EINTR || errno == EAGAIN
                      || errno == EWOULDBLOCK))
            return;
        /* EPIPE or any other error: drop further passthrough writes;
         * draining continues to EOF and the record terminates by its
         * own deadline. */
        state->pass_out_lost = 1;
        return;
    }
}

/* Run-mode stderr flush: the stderr passthrough first, then the final
 * REPORT line — both non-blocking; the process never waits for stdio
 * to drain. */
static void dealpg4_supervisor_flush_run_stderr(
    dealpg4_supervisor_state *state)
{
    if (state->serve_mode)
        return; /* serve: never writes stdio */

    if (state->started_published) {
        while (state->pass_err_off < state->drain_err->retained_len) {
            ssize_t r = write(2,
                              state->drain_err->retained
                                  + state->pass_err_off,
                              state->drain_err->retained_len
                                  - state->pass_err_off);

            if (r > 0) {
                state->pass_err_off += (size_t)r;
                continue;
            }
            if (r < 0 && (errno == EINTR || errno == EAGAIN
                          || errno == EWOULDBLOCK))
                return;
            state->pass_err_lost = 1;
            break;
        }
    }
    if (!state->report_pending)
        return;
    if (state->pass_err_off < state->drain_err->retained_len
        && !state->pass_err_lost)
        return; /* the passthrough completes first */
    if (state->report_off == 0 && !state->report_sep_done) {
        state->report_sep_done = 1;
        if (!state->pass_err_lost && state->pass_err_off > 0
            && state->drain_err->retained[state->pass_err_off - 1]
                   != '\n')
            state->report_lead = 1; /* keep the REPORT a line of its own */
    }
    if (state->report_lead) {
        static const char lf = '\n';
        ssize_t r = write(2, &lf, 1);

        if (r == 1) {
            state->report_lead = 0;
        } else if (r < 0 && (errno == EINTR || errno == EAGAIN
                              || errno == EWOULDBLOCK)) {
            return;
        } else {
            /* EPIPE or any other error: drop the separator and the
             * REPORT line. */
            state->report_lead = 0;
            state->report_pending = 0;
            return;
        }
    }
    for (;;) {
        ssize_t r = write(2, state->report_line + state->report_off,
                          state->report_len - state->report_off);

        if (r > 0) {
            state->report_off += (size_t)r;
            if (state->report_off >= state->report_len) {
                state->report_pending = 0;
                return;
            }
            continue;
        }
        if (r < 0 && (errno == EINTR || errno == EAGAIN
                      || errno == EWOULDBLOCK))
            return;
        /* EPIPE or any other error: the REPORT line is dropped; the
         * record terminates by its own deadline. */
        state->report_pending = 0;
        return;
    }
}

/* The run-mode output is complete: the ready frame, both passthrough
 * payloads, and the REPORT line were each written or dropped. */
static int dealpg4_supervisor_run_output_done(
    const dealpg4_supervisor_state *state)
{
    return !state->ready_pending && !state->report_pending
        && (state->pass_out_off >= state->drain_out->retained_len
            || state->pass_out_lost)
        && (state->pass_err_off >= state->drain_err->retained_len
            || state->pass_err_lost);
}

/* The loop's ppoll timeout: the earliest of the recipe-state phase
 * deadline (the TIMER_FAILED wakeup source, D8), the bounded proof
 * pass throttle, the proof confirming-pass deadline, and the TERMINAL
 * close-linger deadline (the serve-mode channel closes and the
 * process exits promptly after the terminal record and its REJECTs
 * were delivered — never blocked in ppoll until the next phase
 * deadline). */
static int64_t dealpg4_supervisor_loop_timeout_ms(
    const dealpg4_supervisor_state *state, int64_t now)
{
    int64_t deadline = dealpg4_supervisor_next_deadline(state);
    int64_t remaining = deadline > now ? deadline - now : 0;

    if (state->close_lingering) {
        int64_t linger = state->close_linger_deadline_ms;

        if (linger <= now) {
            remaining = 0;
        } else if (linger - now < remaining) {
            remaining = linger - now;
        }
    }
    if (state->t5_proof_bound_ms != 0) {
        /* The armed post-T5 cleanup window is a hard wakeup even when
         * the proof is not pending (the stub not yet reaped past T5
         * would otherwise idle on a 0-ms recipe-state timeout and
         * busy-spin the loop until SIGCHLD): the loop sleeps until
         * the bound, SIGCHLD, or a channel event — whichever is
         * earliest — and the T5 branch applies the terminal
         * classification exactly at the bound. */
        int64_t bound = state->t5_proof_bound_ms;

        if (bound <= now) {
            remaining = 0;
        } else if (remaining == 0 || bound - now < remaining) {
            remaining = bound - now;
        }
    }
    if (dealpg4_supervisor_proof_pending(state)) {
        int64_t np = state->proof_next_pass_ms;

        if (np <= now) {
            remaining = 0;
        } else if (remaining == 0 || np - now < remaining) {
            /* The proof-pass throttle is the wakeup whenever the
             * recipe-state deadline is exhausted (the post-T4/T5
             * catch-up cleanup states): the loop wakes at the proof
             * cadence instead of busy-spinning on a 0-ms remaining
             * while SIGCHLD reaping and the proof passes complete. */
            remaining = np - now;
        }
        if (state->proof_first_pass && !state->proof_done) {
            int64_t confirm = state->proof_first_pass_ms + 10;

            if (confirm <= now) {
                remaining = 0;
            } else if (remaining == 0 || confirm - now < remaining) {
                remaining = confirm - now;
            }
        }
    }
    return remaining;
}

/* The single-threaded ppoll event loop (parent D1): timerfd,
 * signalfd(SIGCHLD), both target stream pipes, the stub status-pipe
 * read fd, the control-channel read side, the control-channel write
 * side whenever the pending queue is non-empty, and the run-mode
 * stdout/stderr write sides whenever output is pending. No write path
 * blocks; ppoll always blocks with the earliest applicable
 * recipe-state deadline. The status pipe is handled before the reap
 * loop so the 3(i) liveness check runs at the first EOF read before
 * any pending SIGCHLD for the stub pid is reaped. */
static void dealpg4_supervisor_loop(dealpg4_supervisor_state *state)
{
    for (;;) {
        struct pollfd fds[9];
        dealpg4_loop_role roles[9];
        nfds_t nfds = 0;
        nfds_t i;
        uint64_t now64;
        int64_t now;
        int64_t remaining;
        struct timespec ts;
        int fi_death;
        int stream_congested;
        int ctrl_write_stalled;
        int ctrl_read_stalled;
        int rc;

        /* Seam congestion reports (once per iteration, before the poll
         * set is built — the script hooks are the only place the
         * reports change). */
        stream_congested =
            state->drains_active
            && dealpg4_fi_hooks.congest(FI_CONGEST_STREAM, STREAM_NO_EOF,
                                        NULL)
                   == STREAM_NO_EOF;
        ctrl_write_stalled = 0;
        ctrl_read_stalled = 0;
        if (state->serve_mode && state->control_fd >= 0) {
            ctrl_write_stalled =
                dealpg4_fi_hooks.congest(FI_CONGEST_CTRL, CTRL_WRITE_STALL,
                                         NULL)
                == CTRL_WRITE_STALL;
            ctrl_read_stalled =
                dealpg4_fi_hooks.congest(FI_CONGEST_CTRL, CTRL_READ_STALL,
                                         NULL)
                == CTRL_READ_STALL;
        }

        dealpg4_supervisor_advance_deadlines(state);
        if (state->done)
            return;

        now64 = dealpg4_now_ms();
        if (dealpg4_supervisor_proof_pending(state)
            && (int64_t)now64 >= state->proof_next_pass_ms) {
            dealpg4_supervisor_proof_pass(state);
            if (state->done)
                return;
        }

        if (state->terminal_queued) {
            if (state->serve_mode) {
                if (state->channel_lost || state->protocol_aborted) {
                    /* The per-state invocation termination completes
                     * before the supervisor exits (D5): after channel
                     * loss / protocol abort the proof loop runs to its
                     * terminal classification (proof pass or the T4
                     * deadline), bounded by the T5 recipe state. Until
                     * then the loop blocks in ppoll exactly like the
                     * non-aborted proof paths (the timeout computation
                     * covers the proof throttle), never busy-spinning. */
                    if (state->proof_done
                        || state->proof_failed_class) {
                        state->done = 1;
                        return;
                    }
                } else if (dealpg4_supervisor_queue_empty(
                               &state->queue)) {
                    if (!state->close_lingering) {
                        state->close_lingering = 1;
                        state->close_linger_deadline_ms =
                            (int64_t)dealpg4_now_ms() + 10;
                    } else if ((int64_t)dealpg4_now_ms()
                               >= state->close_linger_deadline_ms) {
                        state->done = 1;
                        return;
                    }
                } else {
                    state->close_lingering = 0;
                }
            } else if (dealpg4_supervisor_run_output_done(state)) {
                state->done = 1;
                return;
            }
        }

        now = (int64_t)dealpg4_now_ms();
        remaining = dealpg4_supervisor_loop_timeout_ms(state, now);
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
        /* STREAM_NO_EOF: while congested both stream read sides leave
         * the poll set — the drains never advance, never reach EOF
         * (a target stream that stays readable would otherwise keep
         * POLLIN ready forever and busy-spin the loop), and the proof
         * loop's drain check fails at T4 with DRAIN_FAILED. */
        if (!stream_congested && state->stream_out_fd >= 0) {
            fds[nfds].fd = state->stream_out_fd;
            fds[nfds].events = POLLIN;
            fds[nfds].revents = 0;
            roles[nfds] = DEALPG4_ROLE_STREAM_OUT;
            nfds++;
        }
        if (!stream_congested && state->stream_err_fd >= 0) {
            fds[nfds].fd = state->stream_err_fd;
            fds[nfds].events = POLLIN;
            fds[nfds].revents = 0;
            roles[nfds] = DEALPG4_ROLE_STREAM_ERR;
            nfds++;
        }
        if (state->serve_mode) {
            if (state->control_fd >= 0) {
                /* CTRL_WRITE_STALL: supervisor-side POLLOUT never
                 * ready — the write side leaves the poll set while
                 * congested, so no write path runs (a stalled outer
                 * never suspends a deadline; the bounded queue stays
                 * pending and the record terminates by its own
                 * deadline). */
                if (!ctrl_write_stalled
                    && !dealpg4_supervisor_queue_empty(&state->queue)) {
                    fds[nfds].fd = state->control_fd;
                    fds[nfds].events = POLLOUT;
                    fds[nfds].revents = 0;
                    roles[nfds] = DEALPG4_ROLE_CTRL_WRITE;
                    nfds++;
                }
                /* CTRL_READ_STALL: POLLIN never ready — the read side
                 * leaves the poll set while congested (a buffered
                 * record keeps POLLIN ready forever and polling it
                 * would busy-spin the loop), driving the deterministic
                 * missing-ACK scenarios to T1. The read side also
                 * leaves the poll set once the channel is closed
                 * (loss/abort closes the fd): a peer-closed socket
                 * keeps POLLIN ready forever and polling it would
                 * busy-spin the loop. */
                if (!ctrl_read_stalled) {
                    fds[nfds].fd = state->control_fd;
                    fds[nfds].events = POLLIN;
                    fds[nfds].revents = 0;
                    roles[nfds] = DEALPG4_ROLE_CTRL_READ;
                    nfds++;
                }
            }
        } else {
            /* Run mode: stdout/stderr write sides whenever output is
             * pending. */
            if (state->ready_pending
                || (state->started_published
                    && !state->pass_out_lost
                    && state->pass_out_off
                           < state->drain_out->retained_len)) {
                fds[nfds].fd = 1;
                fds[nfds].events = POLLOUT;
                fds[nfds].revents = 0;
                roles[nfds] = DEALPG4_ROLE_STDOUT_WRITE;
                nfds++;
            }
            if ((state->started_published && !state->pass_err_lost
                 && state->pass_err_off
                        < state->drain_err->retained_len)
                || state->report_pending) {
                fds[nfds].fd = 2;
                fds[nfds].events = POLLOUT;
                fds[nfds].revents = 0;
                roles[nfds] = DEALPG4_ROLE_STDERR_WRITE;
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
                        state, &state->stream_out_fd, state->drain_out,
                        &state->relay_out);
                break;
            case DEALPG4_ROLE_STREAM_ERR:
                if (fds[i].revents & (POLLIN | POLLHUP))
                    dealpg4_supervisor_pump_stream(
                        state, &state->stream_err_fd, state->drain_err,
                        &state->relay_err);
                break;
            case DEALPG4_ROLE_SIGCHLD:
                if (fds[i].revents & POLLIN)
                    dealpg4_supervisor_read_sigchld(state);
                break;
            case DEALPG4_ROLE_CTRL_READ:
                if (fds[i].revents & (POLLIN | POLLHUP | POLLERR))
                    dealpg4_supervisor_read_control(state);
                break;
            case DEALPG4_ROLE_CTRL_WRITE:
                if (fds[i].revents & (POLLOUT | POLLERR)) {
                    int rcq = dealpg4_supervisor_queue_flush(
                        &state->queue, state->control_fd);

                    if (rcq < 0)
                        dealpg4_supervisor_channel_lost(state);
                }
                break;
            case DEALPG4_ROLE_STDOUT_WRITE:
                if (fds[i].revents & (POLLOUT | POLLERR))
                    dealpg4_supervisor_flush_run_stdout(state);
                break;
            case DEALPG4_ROLE_STDERR_WRITE:
                if (fds[i].revents & (POLLOUT | POLLERR))
                    dealpg4_supervisor_flush_run_stderr(state);
                break;
            default:
                break;
            }
        }

        /* The release point: the end of one event-processing batch
         * (the post-ACK-pre-release window, D5(a)). */
        dealpg4_supervisor_maybe_release(state);

        /* FI_SUP_DEATH: scripted nonzero terminates the supervisor
         * immediately with the scripted exit code and no cleanup (a
         * kill-equivalent death — the kernel cascades apply: the
         * stub's PDEATHSIG and the outer's per-state fallback). The
         * check sits at the end of the batch, gated on the stub's
         * STUB_IDENTITY having been processed: by the time it fires
         * the stub is past step 1 (PDEATHSIG armed) and inside or
         * entering its pre-release window, so the kernel cascade
         * deterministically delivers the armed SIGKILL at the
         * reparenting instead of racing the step-2 parent recheck
         * (a pre-identity death would leave the stub unarmed and the
         * reparented stub would exit 2 — the scenario's pinned
         * CLD_KILLED 9 observation requires the armed cascade). */
        fi_death = 0;
        if (state->identity_seen)
            fi_death = dealpg4_fi_hooks.fail(FI_SUP_DEATH);
        if (fi_death != 0)
            _exit(fi_death);
    }
}

/* === Stage termination ================================================== */

/* The exit status after the invocation: serve 0/1/2 per the terminal
 * record (2 also on the no-record PROTOCOL_ERROR path); run 0/1/2 per
 * D4. */
static int dealpg4_supervisor_exit_status(
    const dealpg4_supervisor_state *state)
{
    if (state->protocol_aborted)
        return DEALPG4_EXIT_USAGE;
    if (state->serve_mode) {
        switch (state->final_kind) {
        case DEALPG4_SUP_TERMINAL_CLEAN_SUCCESS:
            return 0;
        case DEALPG4_SUP_TERMINAL_CLEAN_CANCELLED:
            return 1;
        default:
            return DEALPG4_EXIT_USAGE;
        }
    }
    switch (state->final_kind) {
    case DEALPG4_SUP_TERMINAL_CLEAN_SUCCESS:
        return state->stub_reaped && state->stub_si_code == CLD_EXITED
                   && state->stub_si_status == 0
               ? 0
               : 1;
    case DEALPG4_SUP_TERMINAL_CLEAN_CANCELLED:
        return 1; /* clean containment (unreachable without a channel) */
    default:
        return DEALPG4_EXIT_USAGE;
    }
}

/* Bounded post-state at termination: close the release pipe first (a
 * pre-release stub still polled on it observes EOF and exits 5), reap
 * every waitable child (waitid to ECHILD — no zombie of this
 * supervisor), close every supervisor fd, restore the captured entry
 * signal mask (in-process composition: the caller's signal mask is
 * exactly as it was before the core blocked SIGCHLD), and record the
 * in-process observability result. */
static void dealpg4_supervisor_terminate(dealpg4_supervisor_state *state)
{
    if (state->release_fd >= 0) {
        close(state->release_fd);
        state->release_fd = -1;
    }
    dealpg4_supervisor_reap_all(state);

    if (state->stream_out_fd >= 0) {
        close(state->stream_out_fd);
        state->stream_out_fd = -1;
    }
    if (state->stream_err_fd >= 0) {
        close(state->stream_err_fd);
        state->stream_err_fd = -1;
    }
    if (state->status_fd >= 0) {
        close(state->status_fd);
        state->status_fd = -1;
    }
    if (state->control_fd >= 0) {
        close(state->control_fd);
        state->control_fd = -1;
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
    dealpg4_supervisor_last_result.terminal_kind = state->final_kind;
    snprintf(dealpg4_supervisor_last_result.failure_token,
             sizeof dealpg4_supervisor_last_result.failure_token, "%s",
             state->failure_token);
    dealpg4_supervisor_last_result.protocol_aborted =
        state->protocol_aborted;
    dealpg4_supervisor_last_result.channel_lost = state->channel_lost;
    dealpg4_supervisor_last_result.cancel_applied = state->cancel_requested;
    dealpg4_supervisor_last_result.started_published =
        state->started_published;
    dealpg4_supervisor_last_result.out_chunks_out = state->relay_out.chunks;
    dealpg4_supervisor_last_result.out_chunks_err = state->relay_err.chunks;
    dealpg4_supervisor_last_result.out_end_out =
        state->relay_out.out_end_queued;
    dealpg4_supervisor_last_result.out_end_err =
        state->relay_err.out_end_queued;
    dealpg4_supervisor_last_result.queue_drop_out =
        state->relay_out.queue_dropped;
    dealpg4_supervisor_last_result.queue_drop_err =
        state->relay_err.queue_dropped;
    dealpg4_supervisor_last_result.survivor_count = state->survivor_count;
    if (state->survivor_count > 0) {
        memcpy(dealpg4_supervisor_last_result.survivor_pids,
               state->survivor_pids,
               state->survivor_count
                   * sizeof(dealpg4_supervisor_last_result.survivor_pids[0]));
    }
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
    int status;

    /* Observability reset, before any side effect or refusal path
     * (supervisor.h result contract): a core call refused before fork
     * — validation-class CONFIG_INVALID or capability-class — leaves
     * a zeroed result and empty drain contexts, never the previous
     * invocation's view. */
    memset(&dealpg4_supervisor_last_result, 0,
           sizeof dealpg4_supervisor_last_result);
    dealpg4_supervisor_last_result.stub_pid = -1;
    dealpg4_drain_init(&dealpg4_supervisor_drain_stdout_ctx);
    dealpg4_drain_init(&dealpg4_supervisor_drain_stderr_ctx);

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

    /* Subreaper set + read-back before any fork, never assumed
     * inherited (parent D2): failure or unsupported capability is
     * CAPABILITY_MISSING before any command can start.
     * FI_SUP_SUBREAPER scripts the failure with the scripted errno —
     * the same no-record CAPABILITY_MISSING refusal, no fork. */
    {
        int fi = dealpg4_fi_hooks.fail(FI_SUP_SUBREAPER);

        if (fi != 0) {
            errno = fi;
            return DEALPG4_EXIT_CAPABILITY_MISSING;
        }
    }
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
     * refusal (distinct from the mid-invocation re-arm record path).
     * FI_SUP_TIMERFD scripts the failure with the scripted errno — the
     * same no-record TIMER_FAILED entry refusal. */
    {
        int fi = dealpg4_fi_hooks.fail(FI_SUP_TIMERFD);

        if (fi != 0) {
            errno = fi;
            return DEALPG4_SUPERVISOR_ENTRY_TIMER_FAILED;
        }
    }
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
    /* FI_SUP_SIGNALFD scripts the signalfd(SIGCHLD) creation failure
     * with the scripted errno — the no-record CAPABILITY_MISSING
     * refusal (the timer and the signal mask are restored exactly like
     * the real failure path). */
    {
        int fi = dealpg4_fi_hooks.fail(FI_SUP_SIGNALFD);

        if (fi != 0) {
            errno = fi;
            dealpg4_deadline_close(&timer);
            (void)sigprocmask(SIG_SETMASK,
                              &dealpg4_supervisor_entry_mask, NULL);
            return DEALPG4_EXIT_CAPABILITY_MISSING;
        }
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
    state.serve_mode = (control_fd >= 0);
    state.emit_ready_frame = emit_ready_frame;
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
    state.channel_state = DEALPG4_CHAN_PRE_RELEASE;
    state.group_clean = 1;
    state.session_clean = 1;
    state.drain_ok = 1;
    state.final_kind = DEALPG4_SUP_TERMINAL_NONE;
    state.close_linger_deadline_ms = 0;
    state.relay_out.drain = &dealpg4_supervisor_drain_stdout_ctx;
    state.relay_out.is_out = 1;
    state.relay_err.drain = &dealpg4_supervisor_drain_stderr_ctx;
    state.relay_err.is_out = 0;
    dealpg4_supervisor_queue_init(&state.queue);
    dealpg4_expectation_set_init(&state.expect);
    dealpg4_expectation_set_add(&state.expect, DEALPG4_REC_ACK);
    dealpg4_expectation_set_add(&state.expect, DEALPG4_REC_CANCEL);

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
     * ends keep the target's ordinary blocking semantics). A pipe
     * creation failure is the record-bearing FAILED <id> CLEANUP_FAILED
     * path (never STARTED, never CLEAN). */
    /* FI_SUP_PIPE scripts the pipe-creation failure with the scripted
     * errno — the record-bearing FAILED <id> CLEANUP_FAILED path
     * (never STARTED, never CLEAN). */
    {
        int fi = dealpg4_fi_hooks.fail(FI_SUP_PIPE);
        int pipes_ok;

        if (fi != 0) {
            errno = fi;
            pipes_ok = 0;
        } else {
            pipes_ok = (pipe2(status_pipe, O_NONBLOCK | O_CLOEXEC) == 0
                        && pipe2(release_pipe, O_CLOEXEC) == 0
                        && pipe2(out_pipe, O_CLOEXEC) == 0
                        && pipe2(err_pipe, O_CLOEXEC) == 0);
        }
        if (!pipes_ok) {
            dealpg4_supervisor_close_pipe(status_pipe);
            dealpg4_supervisor_close_pipe(release_pipe);
            dealpg4_supervisor_close_pipe(out_pipe);
            dealpg4_supervisor_close_pipe(err_pipe);
            state.classification = DEALPG4_SUP_CLASS_CLEANUP_FAILED;
            if (state.startup_ms == 0)
                state.startup_ms =
                    (int64_t)dealpg4_now_ms() - state.t0;
            dealpg4_supervisor_loop(&state);
            dealpg4_supervisor_terminate(&state);
            return dealpg4_supervisor_exit_status(&state);
        }
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
     * stubPid is recorded immediately. A fork(2) failure is the
     * record-bearing FAILED <id> STUB_BOOTSTRAP_FAILED path (no stub
     * ever existed; REPORT.exitCode = 0, termSignal = 0 — never
     * STARTED, never CLEAN). */
    /* sup-pre-fork: the injected delay sleeps before the fork and
     * consumes the startup deadline (never an extension).
     * FI_SUP_FORK scripts the fork(2) failure with the scripted errno
     * — the record-bearing FAILED <id> STUB_BOOTSTRAP_FAILED path (no
     * stub ever existed; REPORT.exitCode = 0, termSignal = 0 — never
     * STARTED, never CLEAN). */
    (void)dealpg4_fi_hooks.delay_ms(0, DEALPG4_FI_DELAY_SUP_PRE_FORK);
    {
        int fi = dealpg4_fi_hooks.fail(FI_SUP_FORK);

        if (fi != 0) {
            errno = fi;
            stub = -1;
        } else {
            stub = fork();
        }
    }
    if (stub < 0) {
        dealpg4_supervisor_close_pipe(status_pipe);
        dealpg4_supervisor_close_pipe(release_pipe);
        dealpg4_supervisor_close_pipe(out_pipe);
        dealpg4_supervisor_close_pipe(err_pipe);
        state.classification = DEALPG4_SUP_CLASS_STUB_BOOTSTRAP_FAILED;
        if (state.startup_ms == 0)
            state.startup_ms = (int64_t)dealpg4_now_ms() - state.t0;
        dealpg4_supervisor_loop(&state);
        dealpg4_supervisor_terminate(&state);
        return dealpg4_supervisor_exit_status(&state);
    }
    if (stub == 0)
        dealpg4_stub_run(&cfg); /* never returns */

    /* Supervisor side: close every stub-owned end, bind the release
     * write end O_NONBLOCK, publish STUB_FORKED immediately after
     * fork returns, and arm the T1 deadline (an arm failure is the
     * mid-invocation TIMER_FAILED record path). */
    close(status_pipe[1]);
    close(release_pipe[0]);
    close(out_pipe[1]);
    close(err_pipe[1]);
    state.stub_pid = stub;
    state.status_fd = status_pipe[0];
    state.release_fd = release_pipe[1];
    state.stream_out_fd = out_pipe[0];
    state.stream_err_fd = err_pipe[0];
    state.drains_active = 1;
    flags = fcntl(state.release_fd, F_GETFL);
    if (flags >= 0)
        (void)fcntl(state.release_fd, F_SETFL, flags | O_NONBLOCK);

    dealpg4_supervisor_publish_stub_forked(&state);
    if (dealpg4_deadline_arm(&state.timer, (uint64_t)dl.t1) != 0)
        dealpg4_supervisor_timer_failed(&state);

    dealpg4_supervisor_loop(&state);
    dealpg4_supervisor_terminate(&state);

    status = dealpg4_supervisor_exit_status(&state);
    return status;
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
     * exit status: 0 CLEAN success, 1 CLEAN cancelled, 2 FAILED /
     * PROTOCOL_ERROR; the capability-class statuses map to exit 4
     * (DEALPG4_EXIT_CAPABILITY_MISSING), including the TIMER_FAILED
     * entry refusal (D3/D8). */
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
     * every exit. A failure to bind the stdio surface is a
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
    if (status == DEALPG4_EXIT_CONFIG_INVALID)
        return DEALPG4_EXIT_USAGE; /* unreachable: entry-validated */
    return status;
}
