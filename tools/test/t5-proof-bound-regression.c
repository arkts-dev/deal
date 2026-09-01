/*
 * Post-T5 cleanup-bound regression (ISSUE-0436 remediation, MR-0322
 * review finding): the past-T5 escalation catch-up must terminate
 * even when the proof can never complete.
 *
 * Trigger (the reviewer's reproduction): the committed D6 seam
 * FI_CONGEST_STREAM STREAM_NO_EOF is installed in-process, so the
 * drains never advance and never reach EOF — the proof loop's drain
 * check can never pass. Budget 15000 (T1=T2=T0+5000, T3=T0+7000,
 * T4=T0+12000, T5=T0+15000). The harness ACKs and releases normally,
 * SIGSTOPs the supervisor before T2, and SIGCONTs it past T5
 * (T0+15500). Pre-fix, the T5 catch-up ran the TERM/KILL escalation
 * correctly but then looped on the 5 ms proof cadence forever: no
 * REPORT, no terminal record, no exit (the supervisor was still alive
 * 5 s past T5). Post-fix, the first T5 sighting arms the bounded
 * post-T5 proof window (DEALPG4_SUP_T5_PROOF_BOUND_MS), and on expiry
 * the terminal classification runs regardless of proof completion:
 * exactly one REPORT (failureToken OVERALL_TIMEOUT, drainEof 0,
 * termMs/killMs recording the caught-up escalation) and exactly one
 * terminal FAILED record publish, the serve core exits 2, the
 * never-EOF streams never get an OUT_END, and zero survivors remain.
 *
 * Assertions:
 *   - the child exits by t0 + 20000 (the pre-fix build hangs well
 *     past that) with serve exit 2;
 *   - exactly one REPORT with failureToken OVERALL_TIMEOUT,
 *     drainEof 0, termMs != 0, killMs != 0, elapsedMs >= 15000,
 *     groupProof/sessionProof 1/1 (the killed tree was reaped);
 *   - exactly one terminal FAILED <id> OVERALL_TIMEOUT after REPORT
 *     with no record of any kind after it (no OUT_END for the
 *     never-EOF streams — no OUT_END is ever queued after REPORT);
 *   - zero survivors: no /proc task with the target pgid or session
 *     after the supervisor exits (the harness is a subreaper and
 *     reaps every reparented child, so the check is deterministic).
 *
 * Compile: gcc -std=c11 -O2 -Wall -Werror -fno-ident \
 *     -ffile-prefix-map=$PWD=. -Wl,--build-id=none -o <bin> \
 *     test/t5-proof-bound-regression.c src/protocol.c src/monotonic.c \
 *     src/drain.c src/fi.c src/selftest.c
 * Run via tools/test/run-t5-proof-bound-regression.sh from the
 * repository root (takes about 18 s).
 */
#define _GNU_SOURCE
#define _POSIX_C_SOURCE 200809L

#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/socket.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#include "../src/supervisor.c"

/* === Harness parameters ================================================ */

static const char *g_nonce = "cdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcd";
static const int64_t g_budget = 15000;
static const int64_t g_inv_id = 1;
static const char *const g_target[2] = { "/bin/sleep", "30" };

static const int64_t STOP_AT_MS = 4000;   /* after STARTED, before T2 */
static const int64_t CONT_AT_MS = 15500;  /* past T5 = 15000 */
static const int64_t EXIT_BOUND_MS = 20000; /* the pre-fix build is still
                                               alive at 20000 */
static const int64_t HARD_BOUND_MS = 25000;

/* === Small harness helpers ============================================= */

static int g_failures;

#define CHECK(cond)                                                       \
    do {                                                                  \
        if (!(cond)) {                                                    \
            fprintf(stderr, "FAIL [%s] %s:%d: %s\n", g_ctx, __FILE__,     \
                    __LINE__, #cond);                                     \
            g_failures++;                                                 \
        }                                                                 \
    } while (0)

static const char *g_ctx = "T5 never-EOF bound";

static uint64_t now_ms(void)
{
    struct timespec ts;

    if (clock_gettime(CLOCK_MONOTONIC, &ts) != 0)
        return 0;
    return (uint64_t)ts.tv_sec * 1000u + (uint64_t)(ts.tv_nsec / 1000000);
}

static void msleep(int ms)
{
    struct timespec ts;

    ts.tv_sec = ms / 1000;
    ts.tv_nsec = (long)(ms % 1000) * 1000000L;
    while (nanosleep(&ts, &ts) != 0 && errno == EINTR) {
    }
}

/* === Seam script (installed by the core child in-process) ============== */

static dealpg4_fi_script_delay g_delays[DEALPG4_FI_SCRIPT_MAX_ENTRIES];
static dealpg4_fi_script_fail g_fails[DEALPG4_FI_SCRIPT_MAX_ENTRIES];
static dealpg4_fi_script_congest g_congests[DEALPG4_FI_SCRIPT_MAX_ENTRIES];
static dealpg4_fi_script g_script;

static void script_reset(void)
{
    g_script.delays = g_delays;
    g_script.fails = g_fails;
    g_script.congests = g_congests;
    g_script.ndelays = 0;
    g_script.nfails = 0;
    g_script.ncongests = 0;
}

static void script_congest(int target, int mode)
{
    dealpg4_fi_script_congest *e = &g_congests[g_script.ncongests++];

    e->target = target;
    e->mode = mode;
    e->oneshot = 0; /* always-on */
}

/* === Bounded line reader over the parent socket end ==================== */

typedef struct rr {
    int fd;
    char buf[DEALPG4_MAX_LINE_OTHER_BYTES];
    size_t len;
} rr;

/* 1 = one complete LF-terminated line in *line (NUL-terminated copy),
 * 0 = EOF, -1 = timeout/error. */
static int rr_line(rr *r, int timeout_ms, char *line, size_t linecap)
{
    for (;;) {
        char *nl = memchr(r->buf, '\n', r->len);

        if (nl != NULL) {
            size_t n = (size_t)(nl - r->buf) + 1;

            if (n + 1 > linecap)
                return -1;
            memcpy(line, r->buf, n);
            line[n] = '\0';
            memmove(r->buf, r->buf + n, r->len - n);
            r->len -= n;
            return 1;
        }
        if (r->len >= sizeof(r->buf) - 1)
            return -1; /* overlong line */
        {
            struct pollfd pfd;
            ssize_t n;
            int rc;

            pfd.fd = r->fd;
            pfd.events = POLLIN;
            pfd.revents = 0;
            rc = poll(&pfd, 1, timeout_ms);
            if (rc < 0) {
                if (errno == EINTR)
                    continue;
                return -1;
            }
            if (rc == 0)
                return -1; /* timeout */
            n = read(r->fd, r->buf + r->len, sizeof(r->buf) - 1 - r->len);
            if (n > 0) {
                r->len += (size_t)n;
                continue;
            }
            if (n == 0)
                return 0; /* EOF */
            if (errno == EINTR || errno == EAGAIN
                || errno == EWOULDBLOCK)
                continue;
            return -1;
        }
    }
}

/* === Send one catalog record on the channel ============================ */

static int send_ack(int fd, const char *nonce)
{
    char idbuf[16];
    char line[DEALPG4_MAX_LINE_OTHER_BYTES];
    dealpg4_field_value f[2];
    size_t written = 0;
    size_t off = 0;

    snprintf(idbuf, sizeof idbuf, "%lld", (long long)g_inv_id);
    f[0].data = idbuf;
    f[0].len = strlen(idbuf);
    f[1].data = nonce;
    f[1].len = DEALPG4_NONCE_HEX_CHARS;
    if (dealpg4_serialize(DEALPG4_REC_ACK, f, 2, line, sizeof line,
                          &written)
        != 0)
        return -1;
    while (off < written) {
        ssize_t r = write(fd, line + off, written - off);

        if (r > 0) {
            off += (size_t)r;
            continue;
        }
        if (r < 0 && errno == EINTR)
            continue;
        return -1;
    }
    return 0;
}

static int send_cancel(int fd, const char *nonce)
{
    char idbuf[16];
    char line[DEALPG4_MAX_LINE_OTHER_BYTES];
    dealpg4_field_value f[2];
    size_t written = 0;
    size_t off = 0;

    snprintf(idbuf, sizeof idbuf, "%lld", (long long)g_inv_id);
    f[0].data = idbuf;
    f[0].len = strlen(idbuf);
    f[1].data = nonce;
    f[1].len = DEALPG4_NONCE_HEX_CHARS;
    if (dealpg4_serialize(DEALPG4_REC_CANCEL, f, 2, line, sizeof line,
                          &written)
        != 0)
        return -1;
    while (off < written) {
        ssize_t r = write(fd, line + off, written - off);

        if (r > 0) {
            off += (size_t)r;
            continue;
        }
        if (r < 0 && errno == EINTR)
            continue;
        return -1; /* EPIPE after the supervisor closed the channel */
    }
    return 0;
}

/* === Field accessors over a parsed record ============================== */

static int pfi(const dealpg4_parsed *p, size_t i, int64_t *v)
{
    const dealpg4_field_slice *f = dealpg4_parsed_field(p, i);

    return f != NULL && dealpg4_field_decimal(f, v) != 0;
}

static int ptok(const dealpg4_parsed *p, size_t i, const char *s)
{
    const dealpg4_field_slice *f = dealpg4_parsed_field(p, i);
    size_t n = strlen(s);

    return f != NULL && f->len == n && memcmp(f->p, s, n) == 0;
}

/* === /proc helpers ===================================================== */

/* The /proc/<pid>/stat state char (field 3, after the ')' terminator
 * of the comm field): 'T' while the task is stopped. */
static int proc_state_is_T(pid_t pid)
{
    char path[64];
    char buf[512];
    ssize_t n;
    int fd;
    char *close_paren;

    snprintf(path, sizeof path, "/proc/%ld/stat", (long)pid);
    fd = open(path, O_RDONLY);
    if (fd < 0)
        return 0;
    n = read(fd, buf, sizeof(buf) - 1);
    close(fd);
    if (n <= 0)
        return 0;
    buf[n] = '\0';
    close_paren = strrchr(buf, ')');
    if (close_paren == NULL || close_paren[1] == '\0'
        || close_paren[2] == '\0')
        return 0;
    /* After ") " the fields are state ppid pgrp ...: the state is the
     * char at close_paren + 2. */
    return close_paren[2] == 'T';
}

/* A task with the target pgid or session still exists. */
static int any_survivor(int64_t pgid, int64_t sid)
{
    DIR *dir;
    struct dirent *ent;

    if (pgid <= 0)
        return 0;
    dir = opendir("/proc");
    if (dir == NULL)
        return 0;
    while ((ent = readdir(dir)) != NULL) {
        const char *name = ent->d_name;
        pid_t pid = 0;
        pid_t ppid = 0;
        pid_t pgrp = 0;
        pid_t session = 0;
        size_t i;

        if (name[0] < '0' || name[0] > '9')
            continue;
        for (i = 0; name[i] != '\0'; i++) {
            int d = name[i] - '0';

            if (d < 0 || d > 9)
                break;
            pid = pid * 10 + d;
        }
        if (name[i] != '\0' || pid <= 0 || pid == getpid())
            continue;
        if (dealpg4_proc_stat_identity(pid, &ppid, &pgrp, &session) != 0)
            continue;
        if (pgrp == pgid || session == sid) {
            closedir(dir);
            return 1;
        }
    }
    closedir(dir);
    return 0;
}

static void reap_round(void)
{
    for (;;) {
        siginfo_t si;

        memset(&si, 0, sizeof si);
        if (waitid(P_ALL, 0, &si, WEXITED | WNOHANG) != 0)
            return; /* ECHILD */
        if (si.si_pid == 0)
            return;
    }
}

/* === The scenario ====================================================== */

/* Record-kind order checks: stream relay (OUT/OUT_END) may precede
 * REPORT; nothing may follow the terminal FAILED except channel EOF. */
typedef struct rec_log {
    char lines[64][DEALPG4_MAX_LINE_OTHER_BYTES];
    size_t n;
} rec_log;

static void rec_log_add(rec_log *log, const char *line, size_t len)
{
    if (log->n < 64 && len + 1 < DEALPG4_MAX_LINE_OTHER_BYTES) {
        memcpy(log->lines[log->n], line, len);
        log->lines[log->n][len] = '\0';
        log->n++;
    }
}

static int parse_rec(const char *line, dealpg4_record_type *type,
                     dealpg4_parsed *p)
{
    return dealpg4_parse(line, strlen(line), p) == DEALPG4_PARSE_OK
        ? (*type = p->type, 1)
        : 0;
}

static int run_scenario(void)
{
    int sv[2];
    pid_t pid;
    uint64_t t0;
    rr reader;
    rec_log log;
    int acked = 0;
    int stopped = 0;
    int continued = 0;
    int64_t stub_pgid = 0;
    int64_t stub_sid = 0;
    int report_count = 0;
    int failed_count = 0;
    char line[DEALPG4_MAX_LINE_OTHER_BYTES];
    int exit_code = -1;
    uint64_t exit_at = 0;
    int eof = 0;

    script_reset();
    script_congest(FI_CONGEST_STREAM, STREAM_NO_EOF);

    if (socketpair(AF_UNIX, SOCK_STREAM, 0, sv) != 0) {
        CHECK(0 && "socketpair");
        return 1;
    }
    t0 = now_ms();
    pid = fork();
    if (pid < 0) {
        CHECK(0 && "fork");
        close(sv[0]);
        close(sv[1]);
        return 1;
    }
    if (pid == 0) {
        const char *tav[3];
        int status;

        close(sv[1]); /* the harness's end */
        {
            int flags = fcntl(sv[0], F_GETFL);
            int fdstat = fcntl(sv[0], F_GETFD);

            if (flags >= 0)
                (void)fcntl(sv[0], F_SETFL, flags | O_NONBLOCK);
            if (fdstat >= 0)
                (void)fcntl(sv[0], F_SETFD, fdstat | FD_CLOEXEC);
        }
        if (dealpg4_fi_install_overrides(&g_script,
                                         &dealpg4_supervisor_fi_catalog)
            != 0)
            _exit(90);
        tav[0] = g_target[0];
        tav[1] = g_target[1];
        tav[2] = NULL;
        status = dealpg4_supervise_core(tav, "/tmp", g_nonce, g_inv_id,
                                        g_budget, sv[0], 0);
        _exit(status);
    }
    close(sv[0]);
    memset(&reader, 0, sizeof reader);
    reader.fd = sv[1];
    memset(&log, 0, sizeof log);

    for (;;) {
        int64_t now = (int64_t)now_ms();
        int rc;

        if (now >= t0 + (uint64_t)HARD_BOUND_MS) {
            kill(pid, SIGKILL);
            (void)waitpid(pid, NULL, 0);
            CHECK(0 && "hard bound hit (harness hung)");
            close(sv[1]);
            return 1;
        }
        rc = rr_line(&reader, 40, line, sizeof line);
        if (rc == 1) {
            dealpg4_record_type type;
            dealpg4_parsed p;

            rec_log_add(&log, line, strlen(line));
            if (parse_rec(line, &type, &p)) {
                if (type == DEALPG4_REC_STUB_READY && !acked) {
                    int64_t v;

                    if (pfi(&p, 2, &v))
                        stub_pgid = v; /* STUB_READY <id> <stubPid>
                                          <pgid> <sid> <nonce> */
                    if (pfi(&p, 3, &v))
                        stub_sid = v;
                    if (send_ack(sv[1], g_nonce) != 0) {
                        CHECK(0 && "send_ack");
                    }
                    acked = 1;
                }
            }
        } else if (rc == 0) {
            eof = 1;
            break;
        }
        /* rc < 0: the bounded poll timed out — re-check the clock
         * transitions below and poll again. */
        if (!stopped && acked && now >= t0 + (uint64_t)STOP_AT_MS) {
            if (now > t0 + (uint64_t)STOP_AT_MS + 300) {
                kill(pid, SIGKILL);
                (void)waitpid(pid, NULL, 0);
                CHECK(0 && "SIGSTOP missed its window (harness lag)");
                close(sv[1]);
                return 1;
            }
            if (kill(pid, SIGSTOP) != 0) {
                CHECK(0 && "SIGSTOP");
                close(sv[1]);
                return 1;
            }
            stopped = 1;
            for (;;) {
                if (proc_state_is_T(pid))
                    break;
                if ((int64_t)now_ms() > t0 + (uint64_t)STOP_AT_MS + 1000) {
                    kill(pid, SIGKILL);
                    (void)waitpid(pid, NULL, 0);
                    CHECK(0 && "supervisor did not stop");
                    close(sv[1]);
                    return 1;
                }
                msleep(5);
            }
        }
        if (stopped && !continued && now >= t0 + (uint64_t)CONT_AT_MS) {
            if (kill(pid, SIGCONT) != 0) {
                CHECK(0 && "SIGCONT");
                close(sv[1]);
                return 1;
            }
            continued = 1;
        }
    }
    if (!stopped || !continued) {
        kill(pid, SIGKILL);
        (void)waitpid(pid, NULL, 0);
        CHECK(0 && "lost the stop/continue window");
        close(sv[1]);
        return 1;
    }
    {
        uint64_t deadline = t0 + (uint64_t)EXIT_BOUND_MS;
        int status = -1;

        for (;;) {
            pid_t r = waitpid(pid, &status, WNOHANG);

            if (r == pid) {
                exit_at = now_ms();
                break;
            }
            if (r < 0)
                break;
            if (now_ms() >= deadline) {
                /* The regression discriminator: the pre-fix build is
                 * still alive here (no terminal bound on the proof
                 * loop), the fixed build has already exited. */
                kill(pid, SIGKILL);
                (void)waitpid(pid, NULL, 0);
                CHECK(0 && "supervisor alive past the post-T5 proof "
                      "bound (unbounded cleanup)");
                close(sv[1]);
                return 1;
            }
            msleep(5);
        }
        if (WIFEXITED(status))
            exit_code = WEXITSTATUS(status);
        else
            exit_code = -1;
        CHECK(WIFEXITED(status));
        CHECK(exit_code == 2);
        CHECK(exit_at > t0 + (uint64_t)CONT_AT_MS);
        CHECK(exit_at <= t0 + (uint64_t)EXIT_BOUND_MS);
    }
    close(sv[1]);

    if (!eof)
        CHECK(0 && "control channel never reached EOF");

    /* Exactly one REPORT, exactly one terminal FAILED after it, and no
     * record after the FAILED; the never-EOF streams got no OUT_END
     * (no OUT_END is ever queued after REPORT). */
    {
        size_t i;
        int after_report = 0;

        for (i = 0; i < log.n; i++) {
            dealpg4_record_type type;
            dealpg4_parsed p;

            if (!parse_rec(log.lines[i], &type, &p))
                continue;
            if (type == DEALPG4_REC_REPORT) {
                int64_t v;

                report_count++;
                CHECK(ptok(&p, 18, "OVERALL_TIMEOUT"));
                CHECK(pfi(&p, 17, &v) && v == 0);  /* drainEof = 0 */
                CHECK(pfi(&p, 5, &v) && v > 0);    /* termMs caught up */
                CHECK(pfi(&p, 6, &v) && v > 0);    /* killMs caught up */
                CHECK(pfi(&p, 2, &v) && v >= g_budget);
                CHECK(pfi(&p, 15, &v) && v == 1);  /* groupProof */
                CHECK(pfi(&p, 16, &v) && v == 1);  /* sessionProof */
                after_report = 1;
            } else if (type == DEALPG4_REC_FAILED) {
                failed_count++;
                CHECK(ptok(&p, 1, "OVERALL_TIMEOUT"));
                CHECK(after_report);
            } else if (after_report) {
                CHECK(0 && "record after REPORT that is not the "
                      "terminal FAILED");
            }
        }
        CHECK(report_count == 1);
        CHECK(failed_count == 1);
    }

    /* Zero survivors: after the supervisor exits no task may remain in
     * the target group/session. The harness is a subreaper, so every
     * reparented child is reaped here and the scan is deterministic. */
    {
        uint64_t deadline = now_ms() + 3000;

        while (any_survivor(stub_pgid, stub_sid) && now_ms() < deadline) {
            reap_round();
            msleep(20);
        }
        reap_round();
        CHECK(!any_survivor(stub_pgid, stub_sid));
    }

    return g_failures == 0 ? 0 : 1;
}

/* === The terminal-CANCEL scenario (ISSUE-0440 remediation) =============
 * The post-T5 terminal must flow through the same TERMINAL drain/
 * linger logic as a pre-T5 terminal: after the terminal records are
 * flushed, the channel stays open for the bounded close-linger window
 * so a well-formed CANCEL observed while TERMINAL is answered with
 * REJECT <id> - CANCEL_AUTH_FAILED queued behind the terminal record
 * and flushed before the channel close. Pre-fix, the T5 branch set
 * done=1 as soon as terminal_queued was set past the overall deadline,
 * bypassing the queue-drain wait and the linger window: a CANCEL sent
 * after the terminal FAILED record was never read and never answered,
 * and on the proof-bound-expiry landing the queued records received
 * exactly one instantaneous POLLOUT check before the close. The same
 * STOP/CONT-past-T5 landing as the main scenario drives the
 * OVERALL_TIMEOUT terminal; the harness sends the terminal CANCEL as
 * soon as it reads the REPORT line (the invocation is terminal from
 * the finalize that queued it) and again after the terminal FAILED —
 * every successfully written CANCEL must be answered with a REJECT
 * before the channel EOF, and nothing may follow the REJECTs. */
static int run_terminal_cancel_scenario(void)
{
    int sv[2];
    pid_t pid;
    uint64_t t0;
    rr reader;
    rec_log log;
    int acked = 0;
    int stopped = 0;
    int continued = 0;
    int64_t stub_pgid = 0;
    int64_t stub_sid = 0;
    int report_count = 0;
    int failed_count = 0;
    int reject_count = 0;
    int sends_ok = 0;
    int sent_after_report = 0;
    int sent_after_failed = 0;
    char line[DEALPG4_MAX_LINE_OTHER_BYTES];
    int exit_code = -1;
    int eof = 0;

    script_reset();
    script_congest(FI_CONGEST_STREAM, STREAM_NO_EOF);

    if (socketpair(AF_UNIX, SOCK_STREAM, 0, sv) != 0) {
        CHECK(0 && "socketpair (terminal CANCEL)");
        return 1;
    }
    t0 = now_ms();
    pid = fork();
    if (pid < 0) {
        CHECK(0 && "fork (terminal CANCEL)");
        close(sv[0]);
        close(sv[1]);
        return 1;
    }
    if (pid == 0) {
        const char *tav[3];
        int status;

        close(sv[1]); /* the harness's end */
        {
            int flags = fcntl(sv[0], F_GETFL);
            int fdstat = fcntl(sv[0], F_GETFD);

            if (flags >= 0)
                (void)fcntl(sv[0], F_SETFL, flags | O_NONBLOCK);
            if (fdstat >= 0)
                (void)fcntl(sv[0], F_SETFD, fdstat | FD_CLOEXEC);
        }
        if (dealpg4_fi_install_overrides(&g_script,
                                         &dealpg4_supervisor_fi_catalog)
            != 0)
            _exit(90);
        tav[0] = g_target[0];
        tav[1] = g_target[1];
        tav[2] = NULL;
        status = dealpg4_supervise_core(tav, "/tmp", g_nonce, g_inv_id,
                                        g_budget, sv[0], 0);
        _exit(status);
    }
    close(sv[0]);
    memset(&reader, 0, sizeof reader);
    reader.fd = sv[1];
    memset(&log, 0, sizeof log);

    for (;;) {
        int64_t now = (int64_t)now_ms();
        int rc;

        if (now >= t0 + (uint64_t)HARD_BOUND_MS) {
            kill(pid, SIGKILL);
            (void)waitpid(pid, NULL, 0);
            CHECK(0 && "hard bound hit (terminal-CANCEL harness hung)");
            close(sv[1]);
            return 1;
        }
        rc = rr_line(&reader, 40, line, sizeof line);
        if (rc == 1) {
            dealpg4_record_type type;
            dealpg4_parsed p;

            rec_log_add(&log, line, strlen(line));
            if (parse_rec(line, &type, &p)) {
                if (type == DEALPG4_REC_STUB_READY && !acked) {
                    int64_t v;

                    if (pfi(&p, 2, &v))
                        stub_pgid = v;
                    if (pfi(&p, 3, &v))
                        stub_sid = v;
                    if (send_ack(sv[1], g_nonce) != 0)
                        CHECK(0 && "send_ack (terminal CANCEL)");
                    acked = 1;
                } else if (type == DEALPG4_REC_REPORT
                           && !sent_after_report) {
                    /* Terminal since finalize: the CANCEL is a
                     * terminal-CANCEL and must be answered with a
                     * REJECT before the channel close. */
                    sent_after_report = 1;
                    if (send_cancel(sv[1], g_nonce) == 0)
                        sends_ok++;
                } else if (type == DEALPG4_REC_FAILED
                           && !sent_after_failed) {
                    sent_after_failed = 1;
                    if (send_cancel(sv[1], g_nonce) == 0)
                        sends_ok++;
                }
            }
        } else if (rc == 0) {
            eof = 1;
            break;
        }
        if (!stopped && acked && now >= t0 + (uint64_t)STOP_AT_MS) {
            if (now > t0 + (uint64_t)STOP_AT_MS + 300) {
                kill(pid, SIGKILL);
                (void)waitpid(pid, NULL, 0);
                CHECK(0 && "SIGSTOP missed its window (terminal CANCEL)");
                close(sv[1]);
                return 1;
            }
            if (kill(pid, SIGSTOP) != 0) {
                CHECK(0 && "SIGSTOP (terminal CANCEL)");
                close(sv[1]);
                return 1;
            }
            stopped = 1;
            for (;;) {
                if (proc_state_is_T(pid))
                    break;
                if ((int64_t)now_ms() > t0 + (uint64_t)STOP_AT_MS + 1000) {
                    kill(pid, SIGKILL);
                    (void)waitpid(pid, NULL, 0);
                    CHECK(0 && "supervisor did not stop (terminal CANCEL)");
                    close(sv[1]);
                    return 1;
                }
                msleep(5);
            }
        }
        if (stopped && !continued && now >= t0 + (uint64_t)CONT_AT_MS) {
            if (kill(pid, SIGCONT) != 0) {
                CHECK(0 && "SIGCONT (terminal CANCEL)");
                close(sv[1]);
                return 1;
            }
            continued = 1;
        }
    }
    if (!stopped || !continued) {
        kill(pid, SIGKILL);
        (void)waitpid(pid, NULL, 0);
        CHECK(0 && "lost the stop/continue window (terminal CANCEL)");
        close(sv[1]);
        return 1;
    }
    {
        uint64_t deadline = t0 + (uint64_t)EXIT_BOUND_MS;
        int status = -1;

        for (;;) {
            pid_t r = waitpid(pid, &status, WNOHANG);

            if (r == pid)
                break;
            if (r < 0)
                break;
            if (now_ms() >= deadline) {
                kill(pid, SIGKILL);
                (void)waitpid(pid, NULL, 0);
                CHECK(0 && "supervisor alive past the post-T5 proof "
                      "bound (terminal CANCEL)");
                close(sv[1]);
                return 1;
            }
            msleep(5);
        }
        if (WIFEXITED(status))
            exit_code = WEXITSTATUS(status);
        else
            exit_code = -1;
        CHECK(WIFEXITED(status));
        CHECK(exit_code == 2);
    }
    close(sv[1]);

    if (!eof)
        CHECK(0 && "control channel never reached EOF (terminal CANCEL)");

    /* Exactly one REPORT OVERALL_TIMEOUT, exactly one terminal FAILED
     * after it, then every terminal CANCEL the channel delivered is
     * answered with REJECT <id> - CANCEL_AUTH_FAILED before the
     * channel EOF — nothing follows the REJECTs. */
    {
        size_t i;
        int after_report = 0;
        int after_failed = 0;

        for (i = 0; i < log.n; i++) {
            dealpg4_record_type type;
            dealpg4_parsed p;

            if (!parse_rec(log.lines[i], &type, &p))
                continue;
            if (type == DEALPG4_REC_REPORT) {
                int64_t v;

                report_count++;
                CHECK(ptok(&p, 18, "OVERALL_TIMEOUT"));
                CHECK(pfi(&p, 17, &v) && v == 0);  /* drainEof = 0 */
                CHECK(pfi(&p, 5, &v) && v > 0);    /* termMs caught up */
                CHECK(pfi(&p, 6, &v) && v > 0);    /* killMs caught up */
                CHECK(pfi(&p, 2, &v) && v >= g_budget);
                after_report = 1;
            } else if (type == DEALPG4_REC_FAILED) {
                failed_count++;
                CHECK(ptok(&p, 1, "OVERALL_TIMEOUT"));
                CHECK(after_report);
                after_failed = 1;
            } else if (type == DEALPG4_REC_REJECT) {
                int64_t v;

                reject_count++;
                CHECK(after_failed);
                CHECK(pfi(&p, 0, &v) && v == g_inv_id);
                CHECK(ptok(&p, 1, "-"));
                CHECK(ptok(&p, 2, "CANCEL_AUTH_FAILED"));
            } else if (after_failed) {
                CHECK(0 && "record after the terminal FAILED that is "
                      "not a terminal-CANCEL REJECT");
            }
        }
        CHECK(report_count == 1);
        CHECK(failed_count == 1);
        CHECK(sent_after_report == 1);
        CHECK(sent_after_failed == 1);
        CHECK(reject_count >= 1);
        CHECK(reject_count == sends_ok);
    }

    /* Zero survivors (the same deterministic post-state as the main
     * scenario). */
    {
        uint64_t deadline = now_ms() + 3000;

        while (any_survivor(stub_pgid, stub_sid) && now_ms() < deadline) {
            reap_round();
            msleep(20);
        }
        reap_round();
        CHECK(!any_survivor(stub_pgid, stub_sid));
    }

    return g_failures == 0 ? 0 : 1;
}

int main(void)
{
    /* The harness is the outer: SIGPIPE is ignored so a CANCEL written
     * after the supervisor closed the channel surfaces as EPIPE (the
     * harness reports the missing REJECT instead of dying by signal). */
    if (signal(SIGPIPE, SIG_IGN) == SIG_ERR) {
        fprintf(stderr, "REGRESSION FAIL: signal(SIGPIPE, SIG_IGN): %s\n",
                strerror(errno));
        return 1;
    }
    /* The harness becomes a subreaper so every process reparented when
     * the supervisor exits is adopted here and deterministically
     * reaped (PR_SET_CHILD_SUBREAPER = 36 on linux-x86_64). */
    if (prctl(PR_SET_CHILD_SUBREAPER, 1) != 0) {
        fprintf(stderr, "REGRESSION FAIL: prctl(PR_SET_CHILD_SUBREAPER): "
                "%s\n", strerror(errno));
        return 1;
    }
    if (access("/bin/sleep", X_OK) != 0) {
        fprintf(stderr, "REGRESSION FAIL: /bin/sleep missing\n");
        return 1;
    }
    if (run_scenario() != 0)
        return 1;
    if (run_terminal_cancel_scenario() != 0)
        return 1;
    printf("REGRESSION PASS: past-T5 never-EOF catch-up terminated at the "
           "post-T5 proof bound — exactly one REPORT OVERALL_TIMEOUT "
           "(drainEof 0, termMs/killMs caught up), exactly one terminal "
           "FAILED, serve exit 2, zero survivors; the post-T5 terminal "
           "flows through the TERMINAL drain/linger logic — every "
           "terminal CANCEL is answered REJECT <id> - CANCEL_AUTH_FAILED "
           "flushed before the channel close\n");
    return 0;
}
