/*
 * DEALPG4 supervisor fault-injection seam catalog acceptance suite
 * (tools/test/fi-seam-tests.c, ISSUE-0245 — epic Sequencing step 6).
 *
 * Proves the dealpg4-supervisor-engine D6 seam catalog end to end
 * through the real engine surface, per the ISSUE-0245 verification
 * (T1-T4):
 *
 *  T1 — catalog shape and installer determinism (in-process, through
 *       dealpg4_fi_install_overrides against
 *       dealpg4_supervisor_fi_catalog): the eleven delay sites, ten
 *       fail sites, four congest targets, and nine congestion modes;
 *       a scripted delay at each site sleeps exactly the scripted ms
 *       (CLOCK_MONOTONIC delta) with oneshot firing exactly once and
 *       always-on firing on every matching call; each fail site
 *       returns exactly the scripted value; each congest site returns
 *       exactly the scripted mode; an unknown site/target/mode, a NULL
 *       site, a oneshot outside {0,1}, congestion mode tag 0, a NULL
 *       script/catalog, or an oversized script -> -1 and nothing
 *       installed (the hook table untouched); with no script the delay
 *       sites take ~0 ms and no failure/congestion occurs.
 *  T2 — capability refusals (serve + run entries): FI_SUP_SUBREAPER ->
 *       CAPABILITY_MISSING (serve exit 4 with channel EOF and no
 *       records; run: the named token on stderr + exit 2);
 *       FI_SUP_TIMERFD -> TIMER_FAILED (same statuses);
 *       FI_SUP_SIGNALFD -> CAPABILITY_MISSING (same statuses) — no
 *       fork, no records.
 *  T3 — stub failure paths: FI_STUB_SETSID / FI_STUB_IDENTITY_SELFCHECK
 *       / FI_STUB_CHDIR -> FAILED STUB_BOOTSTRAP_FAILED with the reaped
 *       stub exits 3/3/6; FI_STUB_EXEC -> EXEC_FAILED relay with the
 *       scripted errno and REPORT.exitCode 127; FI_SUP_FORK -> FAILED
 *       STUB_BOOTSTRAP_FAILED with REPORT.exitCode == 0 and
 *       termSignal == 0; FI_SUP_PIPE -> FAILED CLEANUP_FAILED — all
 *       never STARTED, never CLEAN, zero survivors, no exec.
 *       Identity malformation: ID_MALFORMED_PID/PGID/SID/NONCE -> the
 *       STUB_IDENTITY cross-verification fails -> FAILED AUTH_FAILED,
 *       never STUB_READY, zero survivors, no command start.
 *  T4 — the seam-driven classification scenarios: the release-write
 *       races (sup-pre-release-write + stub-pre-release-poll; the
 *       success-into-buffer race and the EPIPE race -> FAILED
 *       STARTUP_TIMEOUT with REPORT.exitCode 4, never STARTED); the
 *       post-release-before-execvp wedges (stub-post-release +
 *       cancel-path TERM -> CALLER_LOST; + T2 deadline TERM ->
 *       EXECUTION_TIMEOUT; termSignal in REPORT, never STARTED); the
 *       post-ACK-pre-release freeze (sup-pre-release-write + a CANCEL
 *       or a channel close inside the delay -> the release byte is
 *       never written, no exec, zero survivors, exactly one REPORT +
 *       one terminal record with the CLEAN cancelled / FAILED
 *       CALLER_LOST disjunction); FI_SUP_DEATH (the supervisor exits
 *       the scripted code with no cleanup and the stub dies via the
 *       PDEATHSIG cascade); the parent-mismatch _exit(2) and the
 *       PDEATHSIG-cascade deaths (stub-post-fork / stub-pre-ppid-recheck
 *       delays + a harness-side kill); status-pipe congestion
 *       (STATUS_LOSS drops STUB_IDENTITY -> no STUB_READY + a pre-ACK
 *       failure, and drops RELEASE_RECV -> the loss removes evidence
 *       only, never a false STARTED; STATUS_CONGESTED -> the
 *       guaranteed-delivery retry loop delivers after the congestion
 *       clears / a wedged stub is escalated by T1 with no deadlock);
 *       channel congestion (CTRL_WRITE_STALL -> deadlines still fire
 *       on time and the record terminates by its own deadline with the
 *       queue bounded; CTRL_READ_STALL -> a deterministic missing-ACK
 *       STARTUP_TIMEOUT); STREAM_NO_EOF -> the proof loop's drain
 *       check never passes -> exactly one REPORT with drainEof == 0
 *       and failureToken == DRAIN_FAILED + exactly one terminal FAILED
 *       record, no OUT_END for the never-EOF'd streams, no OUT_END
 *       after REPORT — plus the production-inertness regression (no
 *       script installed: the serve success sequence, the run surface,
 *       the missing-ACK deadline path).
 *
 * Composition (the battery contract of dealpg4-supervisor-engine D6):
 * the scratch harness forks one scenario process per case; the
 * scenario process installs the scripted overrides in-process through
 * the installer and runs the core/serve/run entry; the harness is the
 * scripted channel peer and a subreaper, and asserts the bounded
 * record stream, the exit statuses, the side-effect absence (no exec),
 * and zero survivors.
 *
 * The suite lives outside the tools/src/ build glob (the pinned
 * artifact build is unchanged); the test binary is a scratch file and
 * never enters the repository.
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

/* === Infra ============================================================== */

static int g_checks;
static int g_failures;
static const char *g_ctx = "";

#define CHECK(cond)                                                     \
    do {                                                                \
        g_checks++;                                                     \
        if (!(cond)) {                                                  \
            g_failures++;                                               \
            fprintf(stderr, "FAIL [%s] %s:%d: %s\n", g_ctx, __FILE__,   \
                    __LINE__, #cond);                                   \
        }                                                               \
    } while (0)

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

/* === Script storage (set by the harness, installed by the child) ======= */

static dealpg4_fi_script_delay g_script_delays[DEALPG4_FI_SCRIPT_MAX_ENTRIES];
static dealpg4_fi_script_fail g_script_fails[DEALPG4_FI_SCRIPT_MAX_ENTRIES];
static dealpg4_fi_script_congest
    g_script_congests[DEALPG4_FI_SCRIPT_MAX_ENTRIES];
static dealpg4_fi_script g_script;

static void script_reset(void)
{
    g_script.delays = g_script_delays;
    g_script.fails = g_script_fails;
    g_script.congests = g_script_congests;
    g_script.ndelays = 0;
    g_script.nfails = 0;
    g_script.ncongests = 0;
}

static void script_delay(const char *site, unsigned ms, int oneshot)
{
    dealpg4_fi_script_delay *e;

    if (g_script.ndelays >= DEALPG4_FI_SCRIPT_MAX_ENTRIES) {
        CHECK(0 && "script delay overflow");
        return;
    }
    e = &g_script_delays[g_script.ndelays++];
    e->site = site;
    e->duration_ms = ms;
    e->oneshot = oneshot;
}

static void script_fail(int site, int value, int oneshot)
{
    dealpg4_fi_script_fail *e;

    if (g_script.nfails >= DEALPG4_FI_SCRIPT_MAX_ENTRIES) {
        CHECK(0 && "script fail overflow");
        return;
    }
    e = &g_script_fails[g_script.nfails++];
    e->site = site;
    e->value = value;
    e->oneshot = oneshot;
}

static void script_congest(int target, int mode, int oneshot)
{
    dealpg4_fi_script_congest *e;

    if (g_script.ncongests >= DEALPG4_FI_SCRIPT_MAX_ENTRIES) {
        CHECK(0 && "script congest overflow");
        return;
    }
    e = &g_script_congests[g_script.ncongests++];
    e->target = target;
    e->mode = mode;
    e->oneshot = oneshot;
}

static void check_hooks_default(void)
{
    CHECK(dealpg4_fi_hooks.delay_ms == dealpg4_fi_default_delay_ms);
    CHECK(dealpg4_fi_hooks.fail == dealpg4_fi_default_fail);
    CHECK(dealpg4_fi_hooks.congest == dealpg4_fi_default_congest);
}

/* === Record reader (bounded line reader over the parent socket end) ==== */

typedef struct rr {
    int fd;
    char buf[DEALPG4_MAX_LINE_OTHER_BYTES];
    size_t len;
} rr;

/* The stable line buffer: rr_line copies each complete line here
 * before returning it, so the field slices of a parsed record stay
 * valid after the reader consumes the line (dealpg4_parse keeps
 * slices into the line bytes). The copy is overwritten by the next
 * rr_line/rr_expect call — every caller consumes the parsed record
 * before the next read. */
static char g_rr_line_copy[DEALPG4_MAX_LINE_OTHER_BYTES];

/* 1 = line (LF-terminated, or a partial tail at EOF), 0 = EOF,
 * -1 = timeout/error. */
static int rr_line(rr *r, int timeout_ms, const char **line, size_t *linelen)
{
    for (;;) {
        char *nl = memchr(r->buf, '\n', r->len);

        if (nl != NULL) {
            memcpy(g_rr_line_copy, r->buf,
                   (size_t)(nl - r->buf) + 1);
            *line = g_rr_line_copy;
            *linelen = (size_t)(nl - r->buf) + 1;
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
            if (n == 0) {
                if (r->len > 0) {
                    memcpy(g_rr_line_copy, r->buf, r->len);
                    *line = g_rr_line_copy;
                    *linelen = r->len;
                    return 1; /* partial tail: the parser rejects it */
                }
                return 0;
            }
            if (errno == EINTR)
                continue;
            if (errno == ECONNRESET || errno == EPIPE)
                return 0; /* channel close with unread peer data */
            return -1;
        }
    }
}

static void rr_consume(rr *r, size_t len)
{
    if (len >= r->len) {
        r->len = 0;
        return;
    }
    memmove(r->buf, r->buf + len, r->len - len);
    r->len -= len;
}

static int rr_expect(rr *r, dealpg4_record_type want, dealpg4_parsed *p,
                     int timeout_ms)
{
    const char *line;
    size_t len;

    if (rr_line(r, timeout_ms, &line, &len) != 1) {
        fprintf(stderr,
                "FAIL [%s] %s:%d: expected %s, got EOF/timeout/error\n",
                g_ctx, __FILE__, __LINE__,
                dealpg4_record_type_name(want));
        g_failures++;
        return -1;
    }
    if (dealpg4_parse(line, len, p) != DEALPG4_PARSE_OK
        || p->type != want) {
        fprintf(stderr, "FAIL [%s] %s:%d: expected %s, got %.*s\n", g_ctx,
                __FILE__, __LINE__, dealpg4_record_type_name(want),
                (int)len, line);
        g_failures++;
        return -1;
    }
    rr_consume(r, len);
    return 0;
}

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

/* The REPORT fields this suite pins: exitCode (0), termSignal (1),
 * failureToken (18), drainEof (17). */
static void check_report(const dealpg4_parsed *p, int64_t exit_code,
                         int64_t term_signal, const char *token,
                         int drain_eof)
{
    int64_t v;

    CHECK(pfi(p, 0, &v) && v == exit_code);
    CHECK(pfi(p, 1, &v) && v == term_signal);
    CHECK(ptok(p, 18, token));
    CHECK(pfi(p, 17, &v) && v == drain_eof);
}

static int send_rec(int fd, dealpg4_record_type type,
                    const dealpg4_field_value *f, size_t n)
{
    char line[DEALPG4_MAX_LINE_OTHER_BYTES];
    size_t written = 0;
    size_t off = 0;

    if (dealpg4_serialize(type, f, n, line, sizeof line, &written) != 0) {
        fprintf(stderr, "FAIL [%s] %s:%d: serialize refused\n", g_ctx,
                __FILE__, __LINE__);
        g_failures++;
        return -1;
    }
    while (off < written) {
        ssize_t r = write(fd, line + off, written - off);

        if (r > 0) {
            off += (size_t)r;
            continue;
        }
        if (r < 0 && errno == EINTR)
            continue;
        fprintf(stderr, "FAIL [%s] %s:%d: send failed: %s\n", g_ctx,
                __FILE__, __LINE__, strerror(errno));
        g_failures++;
        return -1;
    }
    return 0;
}

static int send_ack(int fd, const char *nonce)
{
    char idbuf[16];
    dealpg4_field_value f[2];

    snprintf(idbuf, sizeof idbuf, "1");
    f[0].data = idbuf;
    f[0].len = 1;
    f[1].data = nonce;
    f[1].len = DEALPG4_NONCE_HEX_CHARS;
    return send_rec(fd, DEALPG4_REC_ACK, f, 2);
}

static int send_cancel(int fd, const char *nonce)
{
    char idbuf[16];
    dealpg4_field_value f[2];

    snprintf(idbuf, sizeof idbuf, "1");
    f[0].data = idbuf;
    f[0].len = 1;
    f[1].data = nonce;
    f[1].len = DEALPG4_NONCE_HEX_CHARS;
    return send_rec(fd, DEALPG4_REC_CANCEL, f, 2);
}

/* === Child processes and reaping ======================================= */

typedef enum child_kind {
    CHILD_CORE = 0, /* dealpg4_supervise_core */
    CHILD_SERVE,    /* dealpg4_serve_entry (fd 0 = the channel) */
    CHILD_RUN       /* dealpg4_run_entry */
} child_kind;

typedef struct child_spec {
    child_kind kind;
    int ctrl_fd;              /* CORE: the control fd (-1 = none);
                                 SERVE: the channel fd dup'd onto fd 0 */
    const char *cwd;
    const char *nonce;
    int64_t invocation_id;
    int64_t budget;
    const char *const *targv; /* target argv, targc elements */
    int targc;
    int ready_frame;          /* RUN only */
    int stdout_fd;            /* RUN only (-1 = inherit) */
    int stderr_fd;            /* RUN only (-1 = inherit) */
    int peer_fd;              /* the harness's channel end: the child
                                 closes it after fork so channel EOF
                                 reflects the harness's close only */
} child_spec;

static pid_t fork_child(const child_spec *spec)
{
    pid_t pid = fork();

    if (pid < 0) {
        fprintf(stderr, "FAIL [%s] %s:%d: fork: %s\n", g_ctx, __FILE__,
                __LINE__, strerror(errno));
        g_failures++;
        return -1;
    }
    if (pid == 0) {
        int status = 90;

        /* Bind the channel fd exactly like the serve entry (D3/D8):
         * O_NONBLOCK|FD_CLOEXEC before the core/entry runs — the
         * supervisor's control-channel read side and the release
         * re-read (D5(a)) both assume the non-blocking channel
         * contract (parent D1), and CLOEXEC keeps the channel from
         * reaching an exec'd target. */
        if (spec->kind != CHILD_RUN && spec->ctrl_fd >= 0) {
            int fd_flags = fcntl(spec->ctrl_fd, F_GETFL);
            int fd_status = fcntl(spec->ctrl_fd, F_GETFD);

            if (fd_flags >= 0 && fd_status >= 0) {
                (void)fcntl(spec->ctrl_fd, F_SETFL,
                            fd_flags | O_NONBLOCK);
                (void)fcntl(spec->ctrl_fd, F_SETFD,
                            fd_status | FD_CLOEXEC);
            }
        }
        /* Drop the inherited copy of the harness's channel end: the
         * supervisor's read side must observe EOF exactly when the
         * harness closes its end (the channel-loss scenarios). */
        if (spec->peer_fd >= 0)
            close(spec->peer_fd);

        /* Install the harness script in-process (the battery
         * composition); an empty script restores the production
         * defaults. */
        if (g_script.ndelays + g_script.nfails + g_script.ncongests > 0) {
            if (dealpg4_fi_install_overrides(
                    &g_script, &dealpg4_supervisor_fi_catalog) != 0)
                _exit(90);
        } else {
            dealpg4_fi_restore_defaults();
        }
        switch (spec->kind) {
        case CHILD_CORE: {
            const char *tav[8];
            int i;

            if (spec->targc > 7)
                _exit(91);
            for (i = 0; i < spec->targc; i++)
                tav[i] = spec->targv[i];
            tav[spec->targc] = NULL;
            status = dealpg4_supervise_core(tav, spec->cwd, spec->nonce,
                                            spec->invocation_id,
                                            spec->budget, spec->ctrl_fd,
                                            0);
            break;
        }
        case CHILD_SERVE: {
            char budget_buf[32];
            char id_buf[32];
            char *av[9];
            int ai = 0;
            int i;

            snprintf(budget_buf, sizeof budget_buf, "%lld",
                     (long long)spec->budget);
            snprintf(id_buf, sizeof id_buf, "%lld",
                     (long long)spec->invocation_id);
            setenv("DEALPG4_BUDGET_MS", budget_buf, 1);
            setenv("DEALPG4_NONCE", spec->nonce, 1);
            setenv("DEALPG4_INVOCATION_ID", id_buf, 1);
            if (spec->ctrl_fd != 0) {
                dup2(spec->ctrl_fd, 0);
                if (spec->ctrl_fd > 2)
                    close(spec->ctrl_fd);
            }
            av[ai++] = "launcher";
            av[ai++] = "serve";
            av[ai++] = (char *)spec->cwd;
            av[ai++] = "--";
            if (ai + spec->targc > 9)
                _exit(91);
            for (i = 0; i < spec->targc; i++)
                av[ai++] = (char *)spec->targv[i];
            status = dealpg4_serve_entry(ai, av);
            break;
        }
        case CHILD_RUN: {
            char *av[10];
            int ai = 0;
            int i;

            if (spec->stdout_fd >= 0) {
                dup2(spec->stdout_fd, 1);
                if (spec->stdout_fd > 2)
                    close(spec->stdout_fd);
            }
            if (spec->stderr_fd >= 0) {
                dup2(spec->stderr_fd, 2);
                if (spec->stderr_fd > 2)
                    close(spec->stderr_fd);
            }
            av[ai++] = "launcher";
            av[ai++] = "run";
            av[ai++] = (char *)spec->nonce;
            av[ai++] = (char *)spec->cwd;
            if (spec->ready_frame)
                av[ai++] = "--ready-frame";
            av[ai++] = "--";
            if (ai + spec->targc > 10)
                _exit(91);
            for (i = 0; i < spec->targc; i++)
                av[ai++] = (char *)spec->targv[i];
            status = dealpg4_run_entry(ai, av);
            break;
        }
        }
        _exit(status);
    }
    return pid;
}

static int wait_child(pid_t pid, int *status, int timeout_ms)
{
    uint64_t deadline = now_ms() + (uint64_t)timeout_ms;

    for (;;) {
        int st = 0;
        pid_t r = waitpid(pid, &st, WNOHANG);

        if (r == pid) {
            *status = st;
            return 0;
        }
        if (r < 0)
            return -1;
        if (now_ms() >= deadline)
            return -1;
        msleep(5);
    }
}

/* Reap one waitable child of the harness (the subreaper): 1 = reaped
 * (facts in *si), 0 = nothing waitable, -1 = error. */
static int reap_one(siginfo_t *si)
{
    memset(si, 0, sizeof *si);
    if (waitid(P_ALL, 0, si, WEXITED | WNOHANG) != 0) {
        if (errno == ECHILD)
            return 0;
        return -1;
    }
    return si->si_pid != 0 ? 1 : 0;
}

static void assert_no_survivors(int timeout_ms)
{
    uint64_t deadline = now_ms() + (uint64_t)timeout_ms;

    for (;;) {
        siginfo_t si;
        int rc = reap_one(&si);

        if (rc == 0)
            return;
        if (rc < 0) {
            CHECK(0 && "reap error");
            return;
        }
        fprintf(stderr,
                "FAIL [%s] %s:%d: survivor pid %d (si_code %d status %d)\n",
                g_ctx, __FILE__, __LINE__, (int)si.si_pid, si.si_code,
                si.si_status);
        g_failures++;
        if (now_ms() >= deadline)
            return;
        msleep(5);
    }
}

/* Reap exactly one reparented child (the stub of a killed supervisor)
 * and return its waitid facts. */
static int reap_extra(int *code_out, int *status_out, int timeout_ms)
{
    uint64_t deadline = now_ms() + (uint64_t)timeout_ms;

    for (;;) {
        siginfo_t si;
        int rc = reap_one(&si);

        if (rc == 1) {
            *code_out = si.si_code;
            *status_out = si.si_status;
            return 1;
        }
        if (rc < 0)
            return -1;
        if (now_ms() >= deadline)
            return 0;
        msleep(5);
    }
}

/* === Channel scenario scaffold ========================================= */

typedef struct chan_scene {
    pid_t pid;
    int sock; /* the parent socket end */
    rr reader;
    uint64_t t0;
} chan_scene;

/* The exec side-effect marker path (owned by marker_setup below;
 * forward-declared so chan_start clears it per scenario). */
static char g_marker_path[128];

static int chan_start(chan_scene *s, child_kind kind, const char *cwd,
                      const char *nonce, int64_t invocation_id,
                      int64_t budget, const char *const *targv, int targc)
{
    int sv[2];
    child_spec spec;

    memset(s, 0, sizeof *s);
    /* Each channel scenario owns its marker assertions: clear any
     * side-effect file a previous scenario left, so a marker hit is
     * attributed to this scenario alone. */
    unlink(g_marker_path);
    if (socketpair(AF_UNIX, SOCK_STREAM, 0, sv) != 0) {
        fprintf(stderr, "FAIL [%s] %s:%d: socketpair: %s\n", g_ctx,
                __FILE__, __LINE__, strerror(errno));
        g_failures++;
        return -1;
    }
    memset(&spec, 0, sizeof spec);
    spec.kind = kind;
    spec.ctrl_fd = sv[0];
    spec.cwd = cwd;
    spec.nonce = nonce;
    spec.invocation_id = invocation_id;
    spec.budget = budget;
    spec.targv = targv;
    spec.targc = targc;
    spec.peer_fd = sv[1];
    s->t0 = now_ms();
    s->pid = fork_child(&spec);
    close(sv[0]);
    if (s->pid < 0) {
        close(sv[1]);
        return -1;
    }
    s->sock = sv[1];
    s->reader.fd = sv[1];
    s->reader.len = 0;
    return 0;
}

static void chan_finish(chan_scene *s, int expect_status, int timeout_ms)
{
    int status = -1;

    if (wait_child(s->pid, &status, timeout_ms) != 0) {
        kill(s->pid, SIGKILL);
        (void)wait_child(s->pid, &status, 5000);
        CHECK(0 && "child did not exit in time");
    } else {
        CHECK(WIFEXITED(status) && WEXITSTATUS(status) == expect_status);
    }
    close(s->sock);
    assert_no_survivors(5000);
}

static void chan_finish_range(chan_scene *s, int lo, int hi, int timeout_ms)
{
    int status = -1;

    if (wait_child(s->pid, &status, timeout_ms) != 0) {
        kill(s->pid, SIGKILL);
        (void)wait_child(s->pid, &status, 5000);
        CHECK(0 && "child did not exit in time");
    } else {
        CHECK(WIFEXITED(status) && WEXITSTATUS(status) >= lo
              && WEXITSTATUS(status) <= hi);
    }
    close(s->sock);
    assert_no_survivors(5000);
}

/* Expect two OUT_END records (out and err, either order) and no other
 * record type. */
static void expect_both_out_ends(rr *r)
{
    int seen_out = 0;
    int seen_err = 0;
    int i;

    for (i = 0; i < 2; i++) {
        dealpg4_parsed p;

        if (rr_expect(r, DEALPG4_REC_OUT_END, &p, 8000) != 0)
            return;
        if (ptok(&p, 1, "out"))
            seen_out++;
        else if (ptok(&p, 1, "err"))
            seen_err++;
        else
            CHECK(0 && "OUT_END with a bad stream tag");
    }
    CHECK(seen_out == 1);
    CHECK(seen_err == 1);
}

/* === Scenario constants ================================================= */

static const char *g_nonce = "0123456789abcdef0123456789abcdef";
static char g_touch_cmd[192];
static const char *g_noexec_target[3];
static const char *g_true_target[1] = { "/bin/true" };
static const char *g_exit5_target[3] = { "/bin/sh", "-c", "exit 5" };
static const char *g_exit5_slow_target[3] = { "/bin/sh", "-c",
                                              "sleep 0.1; exit 5" };

static void marker_setup(void)
{
    snprintf(g_marker_path, sizeof g_marker_path,
             "/tmp/dealpg4-fi-seam-proof.%d", (int)getpid());
    snprintf(g_touch_cmd, sizeof g_touch_cmd, "touch %s", g_marker_path);
    g_noexec_target[0] = "/bin/sh";
    g_noexec_target[1] = "-c";
    g_noexec_target[2] = g_touch_cmd;
    unlink(g_marker_path);
}

static int marker_exists(void)
{
    return access(g_marker_path, F_OK) == 0;
}

/* ================================================================
 * T1: catalog shape, installer validation, hook determinism
 * ================================================================ */

static int str_tag_in(const char *const *tags, size_t n, const char *tag)
{
    size_t i;

    if (tag == NULL)
        return 0;
    for (i = 0; i < n; i++) {
        if (tags[i] != NULL && strcmp(tags[i], tag) == 0)
            return 1;
    }
    return 0;
}

static int int_tag_in(const int *tags, size_t n, int tag)
{
    size_t i;

    for (i = 0; i < n; i++) {
        if (tags[i] == tag)
            return 1;
    }
    return 0;
}

static void t1_catalog_shape(void)
{
    const dealpg4_fi_catalog *c = &dealpg4_supervisor_fi_catalog;
    static const char *const delay_sites[11] = {
        "sup-pre-fork", "sup-pre-release-write", "stub-post-fork",
        "stub-pre-ppid-recheck", "stub-pre-setsid",
        "stub-pre-identity-selfcheck", "stub-pre-identity-write",
        "stub-pre-release-poll", "stub-post-release", "stub-pre-chdir",
        "stub-pre-execvp"
    };
    static const int fail_sites[10] = {
        FI_SUP_SUBREAPER, FI_SUP_TIMERFD, FI_SUP_SIGNALFD, FI_SUP_FORK,
        FI_SUP_PIPE, FI_STUB_SETSID, FI_STUB_IDENTITY_SELFCHECK,
        FI_STUB_CHDIR, FI_STUB_EXEC, FI_SUP_DEATH
    };
    static const int targets[4] = {
        FI_CONGEST_STATUS_PIPE, FI_CONGEST_CTRL, FI_CONGEST_STREAM,
        FI_CONGEST_IDENTITY
    };
    static const int modes[9] = {
        STATUS_LOSS, STATUS_CONGESTED, CTRL_WRITE_STALL, CTRL_READ_STALL,
        STREAM_NO_EOF, ID_MALFORMED_PID, ID_MALFORMED_PGID,
        ID_MALFORMED_SID, ID_MALFORMED_NONCE
    };
    size_t i;

    g_ctx = "T1 catalog shape";
    CHECK(c->ndelay_sites == 11);
    CHECK(c->nfail_sites == 10);
    CHECK(c->ntargets == 4);
    CHECK(c->nmodes == 9);
    for (i = 0; i < 11; i++)
        CHECK(str_tag_in(c->delay_sites, c->ndelay_sites, delay_sites[i]));
    for (i = 0; i < 10; i++)
        CHECK(int_tag_in(c->fail_sites, c->nfail_sites, fail_sites[i]));
    for (i = 0; i < 4; i++)
        CHECK(int_tag_in(c->targets, c->ntargets, targets[i]));
    for (i = 0; i < 9; i++)
        CHECK(int_tag_in(c->modes, c->nmodes, modes[i]));
    /* The tags the call sites use are exactly the catalog tags. */
    CHECK(strcmp(DEALPG4_FI_DELAY_SUP_PRE_FORK, "sup-pre-fork") == 0);
    CHECK(strcmp(DEALPG4_FI_DELAY_SUP_PRE_RELEASE_WRITE,
                 "sup-pre-release-write")
          == 0);
    CHECK(strcmp(DEALPG4_FI_DELAY_STUB_POST_FORK, "stub-post-fork") == 0);
    CHECK(strcmp(DEALPG4_FI_DELAY_STUB_PRE_PPID_RECHECK,
                 "stub-pre-ppid-recheck")
          == 0);
    CHECK(strcmp(DEALPG4_FI_DELAY_STUB_PRE_SETSID, "stub-pre-setsid")
          == 0);
    CHECK(strcmp(DEALPG4_FI_DELAY_STUB_PRE_IDENTITY_SELFCHECK,
                 "stub-pre-identity-selfcheck")
          == 0);
    CHECK(strcmp(DEALPG4_FI_DELAY_STUB_PRE_IDENTITY_WRITE,
                 "stub-pre-identity-write")
          == 0);
    CHECK(strcmp(DEALPG4_FI_DELAY_STUB_PRE_RELEASE_POLL,
                 "stub-pre-release-poll")
          == 0);
    CHECK(strcmp(DEALPG4_FI_DELAY_STUB_POST_RELEASE, "stub-post-release")
          == 0);
    CHECK(strcmp(DEALPG4_FI_DELAY_STUB_PRE_CHDIR, "stub-pre-chdir") == 0);
    CHECK(strcmp(DEALPG4_FI_DELAY_STUB_PRE_EXECVP, "stub-pre-execvp")
          == 0);
}

static void t1_installer_negatives(void)
{
    dealpg4_fi_script s0;
    size_t i;

    g_ctx = "T1 installer validation";
    script_reset();
    memset(&s0, 0, sizeof s0);
    CHECK(dealpg4_fi_install_overrides(NULL, &dealpg4_supervisor_fi_catalog)
          == -1);
    CHECK(dealpg4_fi_install_overrides(&s0, NULL) == -1);
    check_hooks_default();

    /* Unknown delay site. */
    script_reset();
    script_delay("sup-bogus-site", 1, 1);
    CHECK(dealpg4_fi_install_overrides(&g_script,
                                       &dealpg4_supervisor_fi_catalog)
          == -1);
    check_hooks_default();

    /* NULL site string. */
    script_reset();
    script_delay(DEALPG4_FI_DELAY_SUP_PRE_FORK, 1, 1);
    g_script_delays[g_script.ndelays - 1].site = NULL;
    CHECK(dealpg4_fi_install_overrides(&g_script,
                                       &dealpg4_supervisor_fi_catalog)
          == -1);
    check_hooks_default();

    /* Oneshot outside {0,1}. */
    script_reset();
    script_delay(DEALPG4_FI_DELAY_SUP_PRE_FORK, 1, 2);
    CHECK(dealpg4_fi_install_overrides(&g_script,
                                       &dealpg4_supervisor_fi_catalog)
          == -1);
    check_hooks_default();

    /* Unknown fail site. */
    script_reset();
    script_fail(9999, 1, 1);
    CHECK(dealpg4_fi_install_overrides(&g_script,
                                       &dealpg4_supervisor_fi_catalog)
          == -1);
    check_hooks_default();

    /* Fail oneshot outside {0,1}. */
    script_reset();
    script_fail(FI_SUP_FORK, 1, 2);
    CHECK(dealpg4_fi_install_overrides(&g_script,
                                       &dealpg4_supervisor_fi_catalog)
          == -1);
    check_hooks_default();

    /* Unknown congest target. */
    script_reset();
    script_congest(9999, STATUS_LOSS, 1);
    CHECK(dealpg4_fi_install_overrides(&g_script,
                                       &dealpg4_supervisor_fi_catalog)
          == -1);
    check_hooks_default();

    /* Unknown congest mode. */
    script_reset();
    script_congest(FI_CONGEST_CTRL, 9999, 1);
    CHECK(dealpg4_fi_install_overrides(&g_script,
                                       &dealpg4_supervisor_fi_catalog)
          == -1);
    check_hooks_default();

    /* Congestion mode tag 0 (the production no-congestion report). */
    script_reset();
    script_congest(FI_CONGEST_CTRL, 0, 1);
    CHECK(dealpg4_fi_install_overrides(&g_script,
                                       &dealpg4_supervisor_fi_catalog)
          == -1);
    check_hooks_default();

    /* Congest oneshot outside {0,1}. */
    script_reset();
    script_congest(FI_CONGEST_CTRL, CTRL_WRITE_STALL, 2);
    CHECK(dealpg4_fi_install_overrides(&g_script,
                                       &dealpg4_supervisor_fi_catalog)
          == -1);
    check_hooks_default();

    /* Oversized script (one entry over the bound): the count check
     * rejects before any scan, so the storage is filled to the bound
     * and the count is set one over it — no entry is ever read past
     * the bound. */
    script_reset();
    for (i = 0; i < DEALPG4_FI_SCRIPT_MAX_ENTRIES; i++)
        script_delay(DEALPG4_FI_DELAY_SUP_PRE_FORK, 1, 1);
    g_script.ndelays = DEALPG4_FI_SCRIPT_MAX_ENTRIES + 1;
    CHECK(dealpg4_fi_install_overrides(&g_script,
                                       &dealpg4_supervisor_fi_catalog)
          == -1);
    check_hooks_default();
}

static void t1_delay_determinism(void)
{
    const dealpg4_fi_catalog *c = &dealpg4_supervisor_fi_catalog;
    size_t i;

    for (i = 0; i < c->ndelay_sites; i++) {
        const char *site = c->delay_sites[i];
        uint64_t t0;
        uint64_t dt;

        /* Oneshot: fires exactly once with the exact scripted
         * duration; the second call falls through to production. */
        script_reset();
        script_delay(site, 60, 1);
        CHECK(dealpg4_fi_install_overrides(&g_script,
                                           &dealpg4_supervisor_fi_catalog)
              == 0);
        t0 = now_ms();
        CHECK(dealpg4_fi_hooks.delay_ms(0, site) == 0);
        dt = now_ms() - t0;
        CHECK(dt >= 50 && dt <= 1500);
        t0 = now_ms();
        (void)dealpg4_fi_hooks.delay_ms(0, site);
        dt = now_ms() - t0;
        CHECK(dt < 40); /* the single shot is spent */

        /* Always-on: fires on every matching call. */
        script_reset();
        script_delay(site, 30, 0);
        CHECK(dealpg4_fi_install_overrides(&g_script,
                                           &dealpg4_supervisor_fi_catalog)
              == 0);
        t0 = now_ms();
        CHECK(dealpg4_fi_hooks.delay_ms(0, site) == 0);
        dt = now_ms() - t0;
        CHECK(dt >= 20 && dt <= 1500);
        t0 = now_ms();
        CHECK(dealpg4_fi_hooks.delay_ms(0, site) == 0);
        dt = now_ms() - t0;
        CHECK(dt >= 20 && dt <= 1500);
        dealpg4_fi_restore_defaults();
    }
}

static void t1_fail_determinism(void)
{
    const dealpg4_fi_catalog *c = &dealpg4_supervisor_fi_catalog;
    size_t i;

    for (i = 0; i < c->nfail_sites; i++) {
        int site = c->fail_sites[i];
        int other = c->fail_sites[(i + 1) % c->nfail_sites];

        /* Oneshot: exactly the scripted value once, then 0. */
        script_reset();
        script_fail(site, 1000 + site, 1);
        CHECK(dealpg4_fi_install_overrides(&g_script,
                                           &dealpg4_supervisor_fi_catalog)
              == 0);
        CHECK(dealpg4_fi_hooks.fail(site) == 1000 + site);
        CHECK(dealpg4_fi_hooks.fail(site) == 0);
        if (other != site)
            CHECK(dealpg4_fi_hooks.fail(other) == 0);

        /* Always-on: every matching call reports the scripted value. */
        script_reset();
        script_fail(site, 2000 + site, 0);
        CHECK(dealpg4_fi_install_overrides(&g_script,
                                           &dealpg4_supervisor_fi_catalog)
              == 0);
        CHECK(dealpg4_fi_hooks.fail(site) == 2000 + site);
        CHECK(dealpg4_fi_hooks.fail(site) == 2000 + site);
        dealpg4_fi_restore_defaults();
    }
}

static void t1_congest_determinism(void)
{
    static const struct {
        int target;
        int mode;
    } pairs[9] = {
        { FI_CONGEST_STATUS_PIPE, STATUS_LOSS },
        { FI_CONGEST_STATUS_PIPE, STATUS_CONGESTED },
        { FI_CONGEST_CTRL, CTRL_WRITE_STALL },
        { FI_CONGEST_CTRL, CTRL_READ_STALL },
        { FI_CONGEST_STREAM, STREAM_NO_EOF },
        { FI_CONGEST_IDENTITY, ID_MALFORMED_PID },
        { FI_CONGEST_IDENTITY, ID_MALFORMED_PGID },
        { FI_CONGEST_IDENTITY, ID_MALFORMED_SID },
        { FI_CONGEST_IDENTITY, ID_MALFORMED_NONCE }
    };
    size_t i;

    for (i = 0; i < 9; i++) {
        int target = pairs[i].target;
        int mode = pairs[i].mode;
        int other = pairs[(i + 1) % 9].mode;

        /* Oneshot: exactly the scripted mode once, then 0; a
         * different mode on the same target never matches. */
        script_reset();
        script_congest(target, mode, 1);
        CHECK(dealpg4_fi_install_overrides(&g_script,
                                           &dealpg4_supervisor_fi_catalog)
              == 0);
        CHECK(dealpg4_fi_hooks.congest(target, mode, NULL) == mode);
        CHECK(dealpg4_fi_hooks.congest(target, mode, NULL) == 0);
        CHECK(dealpg4_fi_hooks.congest(target, other, NULL) == 0);

        /* Always-on: every matching call reports the scripted mode. */
        script_reset();
        script_congest(target, mode, 0);
        CHECK(dealpg4_fi_install_overrides(&g_script,
                                           &dealpg4_supervisor_fi_catalog)
              == 0);
        CHECK(dealpg4_fi_hooks.congest(target, mode, NULL) == mode);
        CHECK(dealpg4_fi_hooks.congest(target, mode, NULL) == mode);
        dealpg4_fi_restore_defaults();
    }
}

static void t1_production_inertness(void)
{
    const dealpg4_fi_catalog *c = &dealpg4_supervisor_fi_catalog;
    size_t i;

    g_ctx = "T1 production inertness";
    dealpg4_fi_restore_defaults();
    check_hooks_default();
    for (i = 0; i < c->ndelay_sites; i++) {
        uint64_t t0 = now_ms();
        uint64_t dt;

        CHECK(dealpg4_fi_hooks.delay_ms(0, c->delay_sites[i]) == 0);
        dt = now_ms() - t0;
        CHECK(dt < 100); /* ~0 ms with the production defaults */
    }
    for (i = 0; i < c->nfail_sites; i++)
        CHECK(dealpg4_fi_hooks.fail(c->fail_sites[i]) == 0);
    CHECK(dealpg4_fi_hooks.congest(FI_CONGEST_STATUS_PIPE, STATUS_LOSS,
                                   NULL)
          == 0);
    CHECK(dealpg4_fi_hooks.congest(FI_CONGEST_STATUS_PIPE, STATUS_CONGESTED,
                                   NULL)
          == 0);
    CHECK(dealpg4_fi_hooks.congest(FI_CONGEST_CTRL, CTRL_WRITE_STALL, NULL)
          == 0);
    CHECK(dealpg4_fi_hooks.congest(FI_CONGEST_CTRL, CTRL_READ_STALL, NULL)
          == 0);
    CHECK(dealpg4_fi_hooks.congest(FI_CONGEST_STREAM, STREAM_NO_EOF, NULL)
          == 0);
    CHECK(dealpg4_fi_hooks.congest(FI_CONGEST_IDENTITY, ID_MALFORMED_PID,
                                   NULL)
          == 0);
    CHECK(dealpg4_fi_hooks.congest(FI_CONGEST_IDENTITY, ID_MALFORMED_PGID,
                                   NULL)
          == 0);
    CHECK(dealpg4_fi_hooks.congest(FI_CONGEST_IDENTITY, ID_MALFORMED_SID,
                                   NULL)
          == 0);
    CHECK(dealpg4_fi_hooks.congest(FI_CONGEST_IDENTITY, ID_MALFORMED_NONCE,
                                   NULL)
          == 0);
}

/* ================================================================
 * T2: capability refusals (serve + run)
 * ================================================================ */

static void t2_serve_refusal(int fail_site, int want_status,
                             const char *ctx)
{
    chan_scene s;
    const char *line;
    size_t len;
    int rc;

    g_ctx = ctx;
    script_reset();
    script_fail(fail_site, EPERM, 1);
    if (chan_start(&s, CHILD_SERVE, "/tmp", g_nonce, 1, 45000,
                   g_true_target, 1)
        != 0)
        return;
    rc = rr_line(&s.reader, 3000, &line, &len);
    CHECK(rc == 0); /* channel EOF with no records */
    if (rc != 0)
        rr_consume(&s.reader, len);
    chan_finish(&s, want_status, 5000);
}

static void t2_run_refusal(int fail_site, const char *want_stderr,
                           const char *ctx)
{
    int sp[2];
    int ep[2];
    child_spec spec;
    pid_t pid;
    int status = -1;
    char buf[256];
    size_t len = 0;

    g_ctx = ctx;
    script_reset();
    script_fail(fail_site, EPERM, 1);
    if (pipe(sp) != 0 || pipe(ep) != 0) {
        CHECK(0 && "pipe");
        return;
    }
    memset(&spec, 0, sizeof spec);
    spec.kind = CHILD_RUN;
    spec.ctrl_fd = -1;
    spec.cwd = "/tmp";
    spec.nonce = g_nonce;
    spec.invocation_id = 0;
    spec.budget = 45000;
    spec.targv = g_true_target;
    spec.targc = 1;
    spec.ready_frame = 0;
    spec.stdout_fd = sp[1];
    spec.stderr_fd = ep[1];
    pid = fork_child(&spec);
    close(sp[1]);
    close(ep[1]);
    if (pid < 0) {
        close(sp[0]);
        close(ep[0]);
        return;
    }
    if (wait_child(pid, &status, 5000) != 0) {
        kill(pid, SIGKILL);
        (void)wait_child(pid, &status, 5000);
        CHECK(0 && "run child did not exit in time");
    } else {
        CHECK(WIFEXITED(status) && WEXITSTATUS(status) == 2);
    }
    {
        uint64_t deadline = now_ms() + 3000;

        while (len < sizeof(buf) - 1) {
            ssize_t r = read(ep[0], buf + len, sizeof(buf) - 1 - len);

            if (r > 0) {
                len += (size_t)r;
                continue;
            }
            if (r == 0)
                break;
            if (errno == EINTR)
                continue;
            if (now_ms() >= deadline)
                break;
            msleep(5);
        }
        buf[len] = '\0';
    }
    CHECK(strcmp(buf, want_stderr) == 0);
    close(sp[0]);
    close(ep[0]);
    assert_no_survivors(5000);
}

static void t2_capability_refusals(void)
{
    t2_serve_refusal(FI_SUP_SUBREAPER, 4, "T2 FI_SUP_SUBREAPER serve");
    t2_run_refusal(FI_SUP_SUBREAPER, "CAPABILITY_MISSING\n",
                   "T2 FI_SUP_SUBREAPER run");
    t2_serve_refusal(FI_SUP_TIMERFD, 4, "T2 FI_SUP_TIMERFD serve");
    t2_run_refusal(FI_SUP_TIMERFD, "TIMER_FAILED\n",
                   "T2 FI_SUP_TIMERFD run");
    t2_serve_refusal(FI_SUP_SIGNALFD, 4, "T2 FI_SUP_SIGNALFD serve");
    t2_run_refusal(FI_SUP_SIGNALFD, "CAPABILITY_MISSING\n",
                   "T2 FI_SUP_SIGNALFD run");
}

/* ================================================================
 * T3: stub failure paths and identity malformation
 * ================================================================ */

static void t3_stub_failures(void)
{
    chan_scene s;
    dealpg4_parsed p;
    const char *line;
    size_t len;
    int64_t v;

    /* FI_STUB_SETSID -> STUB_FAILED + _exit(3). */
    g_ctx = "T3 FI_STUB_SETSID";
    script_reset();
    script_fail(FI_STUB_SETSID, EPERM, 1);
    if (chan_start(&s, CHILD_CORE, "/tmp", g_nonce, 1, 45000,
                   g_noexec_target, 3)
        == 0) {
        CHECK(rr_expect(&s.reader, DEALPG4_REC_STUB_FORKED, &p, 5000)
              == 0);
        CHECK(rr_expect(&s.reader, DEALPG4_REC_REPORT, &p, 5000) == 0);
        check_report(&p, 3, 0, "STUB_BOOTSTRAP_FAILED", 1);
        CHECK(rr_expect(&s.reader, DEALPG4_REC_FAILED, &p, 5000) == 0);
        CHECK(ptok(&p, 1, "STUB_BOOTSTRAP_FAILED"));
        CHECK(rr_line(&s.reader, 2000, &line, &len) == 0);
        chan_finish(&s, 2, 5000);
    }
    CHECK(!marker_exists());

    /* FI_STUB_IDENTITY_SELFCHECK -> STUB_FAILED + _exit(3). */
    g_ctx = "T3 FI_STUB_IDENTITY_SELFCHECK";
    script_reset();
    script_fail(FI_STUB_IDENTITY_SELFCHECK, EPERM, 1);
    if (chan_start(&s, CHILD_CORE, "/tmp", g_nonce, 1, 45000,
                   g_noexec_target, 3)
        == 0) {
        CHECK(rr_expect(&s.reader, DEALPG4_REC_STUB_FORKED, &p, 5000)
              == 0);
        CHECK(rr_expect(&s.reader, DEALPG4_REC_REPORT, &p, 5000) == 0);
        check_report(&p, 3, 0, "STUB_BOOTSTRAP_FAILED", 1);
        CHECK(rr_expect(&s.reader, DEALPG4_REC_FAILED, &p, 5000) == 0);
        CHECK(ptok(&p, 1, "STUB_BOOTSTRAP_FAILED"));
        CHECK(rr_line(&s.reader, 2000, &line, &len) == 0);
        chan_finish(&s, 2, 5000);
    }
    CHECK(!marker_exists());

    /* FI_STUB_CHDIR (post-release): the ACK releases, the step-7 chdir
     * fails -> STUB_FAILED + _exit(6). */
    g_ctx = "T3 FI_STUB_CHDIR";
    script_reset();
    script_fail(FI_STUB_CHDIR, ENOENT, 1);
    if (chan_start(&s, CHILD_CORE, "/tmp", g_nonce, 1, 45000,
                   g_noexec_target, 3)
        == 0) {
        CHECK(rr_expect(&s.reader, DEALPG4_REC_STUB_FORKED, &p, 5000)
              == 0);
        CHECK(rr_expect(&s.reader, DEALPG4_REC_STUB_READY, &p, 5000)
              == 0);
        CHECK(send_ack(s.sock, g_nonce) == 0);
        CHECK(rr_expect(&s.reader, DEALPG4_REC_REPORT, &p, 5000) == 0);
        check_report(&p, 6, 0, "STUB_BOOTSTRAP_FAILED", 1);
        CHECK(rr_expect(&s.reader, DEALPG4_REC_FAILED, &p, 5000) == 0);
        CHECK(ptok(&p, 1, "STUB_BOOTSTRAP_FAILED"));
        CHECK(rr_line(&s.reader, 2000, &line, &len) == 0);
        chan_finish(&s, 2, 5000);
    }
    CHECK(!marker_exists());

    /* FI_STUB_EXEC: EXEC_FAILED relayed with the scripted errno,
     * REPORT.exitCode 127, never STARTED, never CLEAN. */
    g_ctx = "T3 FI_STUB_EXEC";
    script_reset();
    script_fail(FI_STUB_EXEC, ENOENT, 1);
    if (chan_start(&s, CHILD_CORE, "/tmp", g_nonce, 1, 45000,
                   g_noexec_target, 3)
        == 0) {
        CHECK(rr_expect(&s.reader, DEALPG4_REC_STUB_FORKED, &p, 5000)
              == 0);
        CHECK(rr_expect(&s.reader, DEALPG4_REC_STUB_READY, &p, 5000)
              == 0);
        CHECK(send_ack(s.sock, g_nonce) == 0);
        CHECK(rr_expect(&s.reader, DEALPG4_REC_EXEC_FAILED, &p, 5000)
              == 0);
        CHECK(pfi(&p, 1, &v) && v == (int64_t)ENOENT);
        CHECK(rr_expect(&s.reader, DEALPG4_REC_REPORT, &p, 5000) == 0);
        check_report(&p, 127, 0, "EXEC_FAILED", 1);
        CHECK(rr_expect(&s.reader, DEALPG4_REC_FAILED, &p, 5000) == 0);
        CHECK(ptok(&p, 1, "EXEC_FAILED"));
        CHECK(rr_line(&s.reader, 2000, &line, &len) == 0);
        chan_finish(&s, 2, 5000);
    }
    CHECK(!marker_exists());

    /* FI_SUP_FORK: no stub ever existed — REPORT.exitCode == 0,
     * termSignal == 0, never STARTED, never CLEAN. */
    g_ctx = "T3 FI_SUP_FORK";
    script_reset();
    script_fail(FI_SUP_FORK, EAGAIN, 1);
    if (chan_start(&s, CHILD_CORE, "/tmp", g_nonce, 1, 45000,
                   g_noexec_target, 3)
        == 0) {
        CHECK(rr_expect(&s.reader, DEALPG4_REC_REPORT, &p, 5000) == 0);
        check_report(&p, 0, 0, "STUB_BOOTSTRAP_FAILED", 1);
        CHECK(rr_expect(&s.reader, DEALPG4_REC_FAILED, &p, 5000) == 0);
        CHECK(ptok(&p, 1, "STUB_BOOTSTRAP_FAILED"));
        CHECK(rr_line(&s.reader, 2000, &line, &len) == 0);
        chan_finish(&s, 2, 5000);
    }
    CHECK(!marker_exists());

    /* FI_SUP_PIPE: FAILED CLEANUP_FAILED. */
    g_ctx = "T3 FI_SUP_PIPE";
    script_reset();
    script_fail(FI_SUP_PIPE, EMFILE, 1);
    if (chan_start(&s, CHILD_CORE, "/tmp", g_nonce, 1, 45000,
                   g_noexec_target, 3)
        == 0) {
        CHECK(rr_expect(&s.reader, DEALPG4_REC_REPORT, &p, 5000) == 0);
        check_report(&p, 0, 0, "CLEANUP_FAILED", 1);
        CHECK(rr_expect(&s.reader, DEALPG4_REC_FAILED, &p, 5000) == 0);
        CHECK(ptok(&p, 1, "CLEANUP_FAILED"));
        CHECK(rr_line(&s.reader, 2000, &line, &len) == 0);
        chan_finish(&s, 2, 5000);
    }
    CHECK(!marker_exists());
}

static void t3_identity_malformation(void)
{
    static const struct {
        int mode;
        const char *name;
    } cases[4] = {
        { ID_MALFORMED_PID, "ID_MALFORMED_PID" },
        { ID_MALFORMED_PGID, "ID_MALFORMED_PGID" },
        { ID_MALFORMED_SID, "ID_MALFORMED_SID" },
        { ID_MALFORMED_NONCE, "ID_MALFORMED_NONCE" }
    };
    int i;

    for (i = 0; i < 4; i++) {
        chan_scene s;
        dealpg4_parsed p;
        const char *line;
        size_t len;

        g_ctx = cases[i].name;
        script_reset();
        script_congest(FI_CONGEST_IDENTITY, cases[i].mode, 1);
        if (chan_start(&s, CHILD_CORE, "/tmp", g_nonce, 1, 45000,
                       g_noexec_target, 3)
            != 0)
            continue;
        CHECK(rr_expect(&s.reader, DEALPG4_REC_STUB_FORKED, &p, 5000)
              == 0);
        /* Never STUB_READY: the cross-verification fails -> AUTH_FAILED
         * (the REPORT exitCode is the stub's reaped status — the
         * release-EOF exit 5 or the AUTH_FAILED TERM death — not
         * pinned). */
        CHECK(rr_expect(&s.reader, DEALPG4_REC_REPORT, &p, 5000) == 0);
        CHECK(ptok(&p, 18, "AUTH_FAILED"));
        CHECK(rr_expect(&s.reader, DEALPG4_REC_FAILED, &p, 5000) == 0);
        CHECK(ptok(&p, 1, "AUTH_FAILED"));
        CHECK(rr_line(&s.reader, 2000, &line, &len) == 0);
        chan_finish(&s, 2, 5000);
        CHECK(!marker_exists());
    }
}

/* ================================================================
 * T4: seam-driven classification scenarios
 * ================================================================ */

/* The release-write races: sup-pre-release-write delays the write past
 * the stub's stub-entry-anchored T1s (the stub-pre-release-poll delay
 * keeps the stub inside its pre-poll delay, so the write lands after
 * the already-expired poll deadline and succeeds into the pipe buffer
 * while the stub still lives — or, with a longer write delay, fails
 * EPIPE once the stub fully exited). Both classify FAILED
 * STARTUP_TIMEOUT with REPORT.exitCode 4, never STARTED, never CLEAN. */
static void t4_release_races(void)
{
    chan_scene s;
    dealpg4_parsed p;
    const char *line;
    size_t len;

    /* Success-into-buffer: the write (5900 ms) precedes the stub's
     * pre-poll-delay end (6000 ms); the stub enters the poll after its
     * expired deadline, exits 4 without consuming the byte, and no
     * RELEASE_RECV exists. */
    g_ctx = "T4 release-write race (success into buffer)";
    script_reset();
    script_delay(DEALPG4_FI_DELAY_SUP_PRE_RELEASE_WRITE, 5900, 1);
    script_delay(DEALPG4_FI_DELAY_STUB_PRE_RELEASE_POLL, 6000, 1);
    if (chan_start(&s, CHILD_CORE, "/tmp", g_nonce, 1, 45000,
                   g_noexec_target, 3)
        == 0) {
        CHECK(rr_expect(&s.reader, DEALPG4_REC_STUB_FORKED, &p, 8000)
              == 0);
        CHECK(rr_expect(&s.reader, DEALPG4_REC_STUB_READY, &p, 8000)
              == 0);
        CHECK(send_ack(s.sock, g_nonce) == 0);
        CHECK(rr_expect(&s.reader, DEALPG4_REC_REPORT, &p, 15000) == 0);
        check_report(&p, 4, 0, "STARTUP_TIMEOUT", 1);
        CHECK(rr_expect(&s.reader, DEALPG4_REC_FAILED, &p, 8000) == 0);
        CHECK(ptok(&p, 1, "STARTUP_TIMEOUT"));
        CHECK(rr_line(&s.reader, 2000, &line, &len) == 0);
        chan_finish(&s, 2, 8000);
    }
    CHECK(!marker_exists());

    /* EPIPE race: the write (6900 ms) lands after the stub exited 4
     * (6000 ms) — write(2) fails EPIPE, the EOF-before-release rule
     * classifies STARTUP_TIMEOUT from the reaped exit 4. */
    g_ctx = "T4 release-write race (EPIPE)";
    script_reset();
    script_delay(DEALPG4_FI_DELAY_SUP_PRE_RELEASE_WRITE, 6900, 1);
    script_delay(DEALPG4_FI_DELAY_STUB_PRE_RELEASE_POLL, 6000, 1);
    if (chan_start(&s, CHILD_CORE, "/tmp", g_nonce, 1, 45000,
                   g_noexec_target, 3)
        == 0) {
        CHECK(rr_expect(&s.reader, DEALPG4_REC_STUB_FORKED, &p, 8000)
              == 0);
        CHECK(rr_expect(&s.reader, DEALPG4_REC_STUB_READY, &p, 8000)
              == 0);
        CHECK(send_ack(s.sock, g_nonce) == 0);
        CHECK(rr_expect(&s.reader, DEALPG4_REC_REPORT, &p, 15000) == 0);
        check_report(&p, 4, 0, "STARTUP_TIMEOUT", 1);
        CHECK(rr_expect(&s.reader, DEALPG4_REC_FAILED, &p, 8000) == 0);
        CHECK(ptok(&p, 1, "STARTUP_TIMEOUT"));
        CHECK(rr_line(&s.reader, 2000, &line, &len) == 0);
        chan_finish(&s, 2, 8000);
    }
    CHECK(!marker_exists());
}

/* Post-release-before-execvp wedges: stub-post-release keeps the stub
 * between the release byte and the RELEASE_RECV write. The cancel-path
 * TERM ends it CALLER_LOST; the T2 deadline TERM ends it
 * EXECUTION_TIMEOUT — termSignal in REPORT, never STARTED. */
static void t4_post_release_wedges(void)
{
    chan_scene s;
    dealpg4_parsed p;
    const char *line;
    size_t len;

    g_ctx = "T4 post-release wedge (cancel TERM)";
    script_reset();
    script_delay(DEALPG4_FI_DELAY_STUB_POST_RELEASE, 1000, 1);
    if (chan_start(&s, CHILD_CORE, "/tmp", g_nonce, 1, 45000,
                   g_noexec_target, 3)
        == 0) {
        CHECK(rr_expect(&s.reader, DEALPG4_REC_STUB_FORKED, &p, 5000)
              == 0);
        CHECK(rr_expect(&s.reader, DEALPG4_REC_STUB_READY, &p, 5000)
              == 0);
        CHECK(send_ack(s.sock, g_nonce) == 0);
        msleep(300);
        CHECK(send_cancel(s.sock, g_nonce) == 0);
        CHECK(rr_expect(&s.reader, DEALPG4_REC_REPORT, &p, 8000) == 0);
        check_report(&p, 143, 15, "CALLER_LOST", 1);
        CHECK(rr_expect(&s.reader, DEALPG4_REC_FAILED, &p, 8000) == 0);
        CHECK(ptok(&p, 1, "CALLER_LOST"));
        CHECK(rr_line(&s.reader, 2000, &line, &len) == 0);
        chan_finish(&s, 2, 8000);
    }
    CHECK(!marker_exists());

    /* T = 15000 -> T2 = 5000 < the 6000 ms wedge: the T2 TERM kills
     * the wedged stub pre-exec. */
    g_ctx = "T4 post-release wedge (T2 TERM)";
    script_reset();
    script_delay(DEALPG4_FI_DELAY_STUB_POST_RELEASE, 6000, 1);
    if (chan_start(&s, CHILD_CORE, "/tmp", g_nonce, 1, 15000,
                   g_noexec_target, 3)
        == 0) {
        CHECK(rr_expect(&s.reader, DEALPG4_REC_STUB_FORKED, &p, 5000)
              == 0);
        CHECK(rr_expect(&s.reader, DEALPG4_REC_STUB_READY, &p, 5000)
              == 0);
        CHECK(send_ack(s.sock, g_nonce) == 0);
        CHECK(rr_expect(&s.reader, DEALPG4_REC_REPORT, &p, 15000) == 0);
        check_report(&p, 143, 15, "EXECUTION_TIMEOUT", 1);
        CHECK(rr_expect(&s.reader, DEALPG4_REC_FAILED, &p, 8000) == 0);
        CHECK(ptok(&p, 1, "EXECUTION_TIMEOUT"));
        CHECK(rr_line(&s.reader, 2000, &line, &len) == 0);
        chan_finish(&s, 2, 8000);
    }
    CHECK(!marker_exists());
}

/* The post-ACK-pre-release freeze (D5(a)): the sup-pre-release-write
 * delay wedges the release; a CANCEL (or a channel close) that arrives
 * during the delay is processed before the write decision, so the
 * release byte is never written — the already-applied ACK no longer
 * releases. Exactly one REPORT + exactly one terminal record with the
 * CLEAN final=cancelled / FAILED CALLER_LOST disjunction. */
static void t4_post_ack_freeze(void)
{
    chan_scene s;
    const char *line;
    size_t len;

    g_ctx = "T4 post-ACK-pre-release freeze (CANCEL)";
    script_reset();
    script_delay(DEALPG4_FI_DELAY_SUP_PRE_RELEASE_WRITE, 2000, 1);
    if (chan_start(&s, CHILD_CORE, "/tmp", g_nonce, 1, 45000,
                   g_noexec_target, 3)
        == 0) {
        dealpg4_parsed p;
        int reports = 0;
        int terminals = 0;
        int report_exit = 0;
        int report_term = 0;
        int terminal_kind = 0; /* 1 = CLEAN cancelled, 2 = FAILED */
        char report_token[32] = "";

        CHECK(rr_expect(&s.reader, DEALPG4_REC_STUB_FORKED, &p, 5000)
              == 0);
        CHECK(rr_expect(&s.reader, DEALPG4_REC_STUB_READY, &p, 5000)
              == 0);
        CHECK(send_ack(s.sock, g_nonce) == 0);
        msleep(500);
        CHECK(send_cancel(s.sock, g_nonce) == 0);
        for (;;) {
            int rc = rr_line(&s.reader, 8000, &line, &len);

            if (rc == 0)
                break;
            if (rc != 1) {
                CHECK(rc == 1);
                break;
            }
            CHECK(dealpg4_parse(line, len, &p) == DEALPG4_PARSE_OK);
            rr_consume(&s.reader, len);
            if (p.type == DEALPG4_REC_REPORT) {
                int64_t v;

                reports++;
                if (pfi(&p, 0, &v))
                    report_exit = (int)v;
                if (pfi(&p, 1, &v))
                    report_term = (int)v;
                {
                    const dealpg4_field_slice *f =
                        dealpg4_parsed_field(&p, 18);

                    if (f != NULL) {
                        snprintf(report_token, sizeof report_token,
                                 "%.*s", (int)f->len, f->p);
                    }
                }
            } else if (p.type == DEALPG4_REC_CLEAN) {
                terminals++;
                terminal_kind = 1;
                CHECK(ptok(&p, 1, "cancelled"));
            } else if (p.type == DEALPG4_REC_FAILED) {
                terminals++;
                terminal_kind = 2;
                CHECK(ptok(&p, 1, "CALLER_LOST"));
            } else {
                CHECK(0 && "unexpected record after the cancel");
            }
        }
        CHECK(reports == 1);
        CHECK(terminals == 1);
        CHECK((terminal_kind == 1 && report_exit == 5
               && strcmp(report_token, "-") == 0)
              || (terminal_kind == 2 && report_exit == 143
                  && report_term == 15
                  && strcmp(report_token, "CALLER_LOST") == 0));
        chan_finish_range(&s, 1, 2, 8000);
    }
    CHECK(!marker_exists());

    /* Channel-close variant: the peer closes during the delay — the
     * same freeze, observed through the exit status (the channel is
     * closed, no records are readable). */
    g_ctx = "T4 post-ACK-pre-release freeze (channel close)";
    script_reset();
    script_delay(DEALPG4_FI_DELAY_SUP_PRE_RELEASE_WRITE, 2000, 1);
    if (chan_start(&s, CHILD_CORE, "/tmp", g_nonce, 1, 45000,
                   g_noexec_target, 3)
        == 0) {
        dealpg4_parsed p;

        CHECK(rr_expect(&s.reader, DEALPG4_REC_STUB_FORKED, &p, 5000)
              == 0);
        CHECK(rr_expect(&s.reader, DEALPG4_REC_STUB_READY, &p, 5000)
              == 0);
        CHECK(send_ack(s.sock, g_nonce) == 0);
        msleep(500);
        close(s.sock);
        s.reader.fd = -1;
        chan_finish_range(&s, 1, 2, 8000);
    }
    CHECK(!marker_exists());
}

/* FI_SUP_DEATH: the supervisor exits the scripted code with no
 * cleanup at the first batch end after the stub's STUB_IDENTITY was
 * processed (the seam's pinned fire gate) — the stub is past step 1
 * (PDEATHSIG armed) and the stub-pre-release-poll delay keeps it in
 * the pre-step-6 window when the supervisor dies, so the kernel
 * cascade deterministically delivers the armed SIGKILL at the
 * reparenting and the harness observes CLD_KILLED with si_status 9. */
static void t4_supervisor_death(void)
{
    chan_scene s;
    int status = -1;
    int code = -1;
    int st = -1;

    g_ctx = "T4 FI_SUP_DEATH";
    script_reset();
    script_fail(FI_SUP_DEATH, 42, 1);
    script_delay(DEALPG4_FI_DELAY_STUB_PRE_RELEASE_POLL, 1000, 1);
    if (chan_start(&s, CHILD_CORE, "/tmp", g_nonce, 1, 45000,
                   g_noexec_target, 3)
        != 0)
        return;
    CHECK(wait_child(s.pid, &status, 8000) == 0);
    CHECK(WIFEXITED(status) && WEXITSTATUS(status) == 42);
    close(s.sock);
    CHECK(reap_extra(&code, &st, 8000) == 1);
    CHECK(code == CLD_KILLED && st == 9);
    assert_no_survivors(3000);
    CHECK(!marker_exists());
}

/* stub-post-fork + a harness-side kill: the stub is reparented before
 * step 1's prctl runs, so step 1 arms PDEATHSIG against the new parent
 * and step 2's getppid() recheck deterministically observes the
 * reparenting -> _exit(2), no write, no exec. */
static void t4_parent_mismatch(void)
{
    chan_scene s;
    int status = -1;
    int code = -1;
    int st = -1;

    g_ctx = "T4 parent mismatch -> _exit(2)";
    script_reset();
    script_delay(DEALPG4_FI_DELAY_STUB_POST_FORK, 800, 1);
    if (chan_start(&s, CHILD_CORE, "/tmp", g_nonce, 1, 45000,
                   g_noexec_target, 3)
        != 0)
        return;
    msleep(200);
    CHECK(kill(s.pid, SIGKILL) == 0);
    CHECK(wait_child(s.pid, &status, 8000) == 0);
    CHECK(WIFSIGNALED(status) && WTERMSIG(status) == SIGKILL);
    close(s.sock);
    CHECK(reap_extra(&code, &st, 8000) == 1);
    CHECK(code == CLD_EXITED && st == 2);
    assert_no_survivors(3000);
    CHECK(!marker_exists());
}

/* stub-pre-ppid-recheck + a harness-side kill: step 1 already armed
 * PR_SET_PDEATHSIG = SIGKILL against the supervisor, so the kernel
 * delivers the armed SIGKILL at the reparenting — the stub dies
 * CLD_KILLED with si_status 9 inside the delay and the step-2 recheck
 * never runs. */
static void t4_pdeathsig_cascade(void)
{
    chan_scene s;
    int status = -1;
    int code = -1;
    int st = -1;

    g_ctx = "T4 PDEATHSIG cascade";
    script_reset();
    script_delay(DEALPG4_FI_DELAY_STUB_PRE_PPID_RECHECK, 800, 1);
    if (chan_start(&s, CHILD_CORE, "/tmp", g_nonce, 1, 45000,
                   g_noexec_target, 3)
        != 0)
        return;
    msleep(200);
    CHECK(kill(s.pid, SIGKILL) == 0);
    CHECK(wait_child(s.pid, &status, 8000) == 0);
    CHECK(WIFSIGNALED(status) && WTERMSIG(status) == SIGKILL);
    close(s.sock);
    CHECK(reap_extra(&code, &st, 8000) == 1);
    CHECK(code == CLD_KILLED && st == 9);
    assert_no_survivors(3000);
    CHECK(!marker_exists());
}

/* Status-pipe congestion. */
static void t4_status_congestion(void)
{
    chan_scene s;
    dealpg4_parsed p;
    const char *line;
    size_t len;

    /* STATUS_LOSS drops STUB_IDENTITY: no STUB_READY, and without an
     * ACK the invocation ends in the pre-ACK STARTUP_TIMEOUT failure
     * (never STARTED, zero survivors). */
    g_ctx = "T4 STATUS_LOSS identity drop";
    script_reset();
    script_congest(FI_CONGEST_STATUS_PIPE, STATUS_LOSS, 1);
    if (chan_start(&s, CHILD_CORE, "/tmp", g_nonce, 1, 45000,
                   g_noexec_target, 3)
        == 0) {
        CHECK(rr_expect(&s.reader, DEALPG4_REC_STUB_FORKED, &p, 5000)
              == 0);
        CHECK(rr_expect(&s.reader, DEALPG4_REC_REPORT, &p, 9000) == 0);
        CHECK(ptok(&p, 18, "STARTUP_TIMEOUT"));
        CHECK(rr_expect(&s.reader, DEALPG4_REC_FAILED, &p, 5000) == 0);
        CHECK(ptok(&p, 1, "STARTUP_TIMEOUT"));
        CHECK(rr_line(&s.reader, 2000, &line, &len) == 0);
        chan_finish(&s, 2, 9000);
    }
    CHECK(!marker_exists());

    /* STATUS_LOSS drops RELEASE_RECV: with the release otherwise
     * successful and the target exiting the reserved code 5, the lost
     * publication removes evidence only — the 3(i) liveness at the
     * exec-time EOF (the slow target is alive then) still publishes
     * STARTED and the invocation ends CLEAN success with the real exit
     * code. The opposite direction (no positive evidence + a reserved
     * exit + no RELEASE_RECV -> the STUB_PRE_RELEASE_EXIT false
     * failure) is white-box covered by evaluate-startup-ms-tests.c.
     * A lost RELEASE_RECV can never create a false STARTED. */
    g_ctx = "T4 STATUS_LOSS RELEASE_RECV drop";
    script_reset();
    script_congest(FI_CONGEST_STATUS_PIPE, STATUS_LOSS, 0);
    if (chan_start(&s, CHILD_CORE, "/tmp", g_nonce, 1, 45000,
                   g_exit5_slow_target, 3)
        == 0) {
        CHECK(rr_expect(&s.reader, DEALPG4_REC_STUB_FORKED, &p, 5000)
              == 0);
        /* The identity was dropped: no STUB_READY — the ACK still
         * releases (parent D9). */
        CHECK(send_ack(s.sock, g_nonce) == 0);
        CHECK(rr_expect(&s.reader, DEALPG4_REC_STARTED, &p, 8000) == 0);
        expect_both_out_ends(&s.reader);
        CHECK(rr_expect(&s.reader, DEALPG4_REC_REPORT, &p, 8000) == 0);
        check_report(&p, 5, 0, "-", 1);
        CHECK(rr_expect(&s.reader, DEALPG4_REC_CLEAN, &p, 5000) == 0);
        CHECK(ptok(&p, 1, "success"));
        CHECK(rr_line(&s.reader, 2000, &line, &len) == 0);
        chan_finish(&s, 0, 8000);
    }

    /* STATUS_CONGESTED (oneshot): a STUB_FAILED guaranteed-delivery
     * write retries until the congestion clears and is then delivered
     * (observable as the STUB_BOOTSTRAP_FAILED classification). */
    g_ctx = "T4 STATUS_CONGESTED delivery";
    script_reset();
    script_congest(FI_CONGEST_STATUS_PIPE, STATUS_CONGESTED, 1);
    script_fail(FI_STUB_SETSID, EPERM, 1);
    if (chan_start(&s, CHILD_CORE, "/tmp", g_nonce, 1, 45000,
                   g_noexec_target, 3)
        == 0) {
        CHECK(rr_expect(&s.reader, DEALPG4_REC_STUB_FORKED, &p, 5000)
              == 0);
        CHECK(rr_expect(&s.reader, DEALPG4_REC_REPORT, &p, 8000) == 0);
        check_report(&p, 3, 0, "STUB_BOOTSTRAP_FAILED", 1);
        CHECK(rr_expect(&s.reader, DEALPG4_REC_FAILED, &p, 5000) == 0);
        CHECK(ptok(&p, 1, "STUB_BOOTSTRAP_FAILED"));
        CHECK(rr_line(&s.reader, 2000, &line, &len) == 0);
        chan_finish(&s, 2, 8000);
    }
    CHECK(!marker_exists());

    /* STATUS_CONGESTED (always-on): the wedged guaranteed-delivery
     * write never completes — no deadlock: the supervisor's T1
     * escalation TERMs the wedged stub and the invocation terminates
     * FAILED STARTUP_TIMEOUT with zero survivors. */
    g_ctx = "T4 STATUS_CONGESTED wedge";
    script_reset();
    script_congest(FI_CONGEST_STATUS_PIPE, STATUS_CONGESTED, 0);
    script_fail(FI_STUB_SETSID, EPERM, 1);
    if (chan_start(&s, CHILD_CORE, "/tmp", g_nonce, 1, 45000,
                   g_noexec_target, 3)
        == 0) {
        CHECK(rr_expect(&s.reader, DEALPG4_REC_STUB_FORKED, &p, 5000)
              == 0);
        CHECK(rr_expect(&s.reader, DEALPG4_REC_REPORT, &p, 9000) == 0);
        check_report(&p, 143, 15, "STARTUP_TIMEOUT", 1);
        CHECK(rr_expect(&s.reader, DEALPG4_REC_FAILED, &p, 5000) == 0);
        CHECK(ptok(&p, 1, "STARTUP_TIMEOUT"));
        CHECK(rr_line(&s.reader, 2000, &line, &len) == 0);
        chan_finish(&s, 2, 9000);
    }
    CHECK(!marker_exists());
}

/* Control-channel congestion. */
static void t4_ctrl_congestion(void)
{
    chan_scene s;
    dealpg4_parsed p;
    const char *line;
    size_t len;

    /* CTRL_WRITE_STALL (oneshot): one stalled poll batch, then the
     * full success sequence is delivered. */
    g_ctx = "T4 CTRL_WRITE_STALL oneshot";
    script_reset();
    script_congest(FI_CONGEST_CTRL, CTRL_WRITE_STALL, 1);
    if (chan_start(&s, CHILD_CORE, "/tmp", g_nonce, 1, 45000,
                   g_true_target, 1)
        == 0) {
        CHECK(rr_expect(&s.reader, DEALPG4_REC_STUB_FORKED, &p, 5000)
              == 0);
        CHECK(rr_expect(&s.reader, DEALPG4_REC_STUB_READY, &p, 5000)
              == 0);
        CHECK(send_ack(s.sock, g_nonce) == 0);
        CHECK(rr_expect(&s.reader, DEALPG4_REC_STARTED, &p, 8000) == 0);
        expect_both_out_ends(&s.reader);
        CHECK(rr_expect(&s.reader, DEALPG4_REC_REPORT, &p, 8000) == 0);
        check_report(&p, 0, 0, "-", 1);
        CHECK(rr_expect(&s.reader, DEALPG4_REC_CLEAN, &p, 5000) == 0);
        CHECK(ptok(&p, 1, "success"));
        CHECK(rr_line(&s.reader, 2000, &line, &len) == 0);
        chan_finish(&s, 0, 8000);
    }

    /* CTRL_WRITE_STALL (always-on): POLLOUT never ready — every
     * deadline still fires on the supervisor's own clock and the
     * record terminates by its own deadline (T5 = 15000); the bounded
     * queue never suspends a deadline and nothing is delivered. */
    g_ctx = "T4 CTRL_WRITE_STALL always-on";
    script_reset();
    script_congest(FI_CONGEST_CTRL, CTRL_WRITE_STALL, 0);
    if (chan_start(&s, CHILD_CORE, "/tmp", g_nonce, 1, 15000,
                   g_true_target, 1)
        == 0) {
        int status = -1;

        msleep(100);
        CHECK(send_ack(s.sock, g_nonce) == 0);
        CHECK(wait_child(s.pid, &status, 25000) == 0);
        CHECK(WIFEXITED(status) && WEXITSTATUS(status) == 0);
        CHECK(now_ms() - s.t0 >= 14000); /* the own deadline fired */
        CHECK(rr_line(&s.reader, 1000, &line, &len) == 0);
        close(s.sock);
        assert_no_survivors(3000);
    }

    /* CTRL_READ_STALL (always-on): POLLIN never ready — the ACK is
     * never read and the invocation ends in the deterministic
     * missing-ACK STARTUP_TIMEOUT at T1. */
    g_ctx = "T4 CTRL_READ_STALL missing ACK";
    script_reset();
    script_congest(FI_CONGEST_CTRL, CTRL_READ_STALL, 0);
    if (chan_start(&s, CHILD_CORE, "/tmp", g_nonce, 1, 45000,
                   g_noexec_target, 3)
        == 0) {
        CHECK(rr_expect(&s.reader, DEALPG4_REC_STUB_FORKED, &p, 5000)
              == 0);
        CHECK(rr_expect(&s.reader, DEALPG4_REC_STUB_READY, &p, 5000)
              == 0);
        msleep(100);
        CHECK(send_ack(s.sock, g_nonce) == 0); /* never read */
        CHECK(rr_expect(&s.reader, DEALPG4_REC_REPORT, &p, 9000) == 0);
        CHECK(ptok(&p, 18, "STARTUP_TIMEOUT"));
        CHECK(rr_expect(&s.reader, DEALPG4_REC_FAILED, &p, 5000) == 0);
        CHECK(ptok(&p, 1, "STARTUP_TIMEOUT"));
        CHECK(rr_line(&s.reader, 2000, &line, &len) == 0);
        chan_finish(&s, 2, 9000);
    }
    CHECK(!marker_exists());
}

/* STREAM_NO_EOF: the drains never advance, the proof loop's drain
 * check never passes, and the record terminates FAILED DRAIN_FAILED at
 * T4 — exactly one REPORT with drainEof == 0 and failureToken ==
 * DRAIN_FAILED, exactly one terminal FAILED record, no OUT_END for the
 * never-EOF'd streams, no OUT_END after REPORT. */
static void t4_stream_no_eof(void)
{
    chan_scene s;
    dealpg4_parsed p;
    const char *line;
    size_t len;
    int64_t v;

    g_ctx = "T4 STREAM_NO_EOF";
    script_reset();
    script_congest(FI_CONGEST_STREAM, STREAM_NO_EOF, 0);
    if (chan_start(&s, CHILD_CORE, "/tmp", g_nonce, 1, 15000,
                   g_true_target, 1)
        == 0) {
        CHECK(rr_expect(&s.reader, DEALPG4_REC_STUB_FORKED, &p, 5000)
              == 0);
        CHECK(rr_expect(&s.reader, DEALPG4_REC_STUB_READY, &p, 5000)
              == 0);
        CHECK(send_ack(s.sock, g_nonce) == 0);
        CHECK(rr_expect(&s.reader, DEALPG4_REC_STARTED, &p, 8000) == 0);
        CHECK(rr_expect(&s.reader, DEALPG4_REC_REPORT, &p, 25000) == 0);
        CHECK(now_ms() - s.t0 >= 10000); /* T4 = 12000 fired */
        CHECK(ptok(&p, 18, "DRAIN_FAILED"));
        CHECK(pfi(&p, 17, &v) && v == 0); /* drainEof = 0 */
        CHECK(pfi(&p, 11, &v) && v == 0); /* stdoutBytes: never drained */
        CHECK(pfi(&p, 12, &v) && v == 0);
        CHECK(pfi(&p, 13, &v) && v == 0); /* truncation flags: D7 only */
        CHECK(pfi(&p, 14, &v) && v == 0);
        /* No OUT_END was ever queued (the drains never reached EOF),
         * and the terminal FAILED follows the REPORT directly. */
        CHECK(rr_expect(&s.reader, DEALPG4_REC_FAILED, &p, 5000) == 0);
        CHECK(ptok(&p, 1, "DRAIN_FAILED"));
        CHECK(rr_line(&s.reader, 2000, &line, &len) == 0);
        chan_finish(&s, 2, 8000);
    }
}

/* Production-inertness regression (no script installed). */
static void t4_production_regression(void)
{
    chan_scene s;
    dealpg4_parsed p;
    const char *line;
    size_t len;

    /* Serve success sequence vs /bin/true. */
    g_ctx = "T4 production serve success";
    script_reset();
    if (chan_start(&s, CHILD_CORE, "/tmp", g_nonce, 1, 45000,
                   g_true_target, 1)
        == 0) {
        CHECK(rr_expect(&s.reader, DEALPG4_REC_STUB_FORKED, &p, 5000)
              == 0);
        CHECK(rr_expect(&s.reader, DEALPG4_REC_STUB_READY, &p, 5000)
              == 0);
        CHECK(send_ack(s.sock, g_nonce) == 0);
        CHECK(rr_expect(&s.reader, DEALPG4_REC_STARTED, &p, 8000) == 0);
        expect_both_out_ends(&s.reader);
        CHECK(rr_expect(&s.reader, DEALPG4_REC_REPORT, &p, 8000) == 0);
        check_report(&p, 0, 0, "-", 1);
        CHECK(rr_expect(&s.reader, DEALPG4_REC_CLEAN, &p, 5000) == 0);
        CHECK(ptok(&p, 1, "success"));
        CHECK(rr_line(&s.reader, 2000, &line, &len) == 0);
        chan_finish(&s, 0, 8000);
    }

    /* Run surface: /bin/true with --ready-frame -> exit 0, the ready
     * frame on stdout, the REPORT on stderr. */
    g_ctx = "T4 production run success";
    {
        int sp[2];
        int ep[2];
        child_spec spec;
        pid_t pid;
        int status = -1;
        char out[256];
        char err[DEALPG4_MAX_LINE_OTHER_BYTES];
        size_t outlen = 0;
        size_t errlen = 0;

        script_reset();
        if (pipe(sp) != 0 || pipe(ep) != 0) {
            CHECK(0 && "pipe");
            return;
        }
        memset(&spec, 0, sizeof spec);
        spec.kind = CHILD_RUN;
        spec.ctrl_fd = -1;
        spec.cwd = "/tmp";
        spec.nonce = g_nonce;
        spec.invocation_id = 0;
        spec.budget = 45000;
        spec.targv = g_true_target;
        spec.targc = 1;
        spec.ready_frame = 1;
        spec.stdout_fd = sp[1];
        spec.stderr_fd = ep[1];
        pid = fork_child(&spec);
        close(sp[1]);
        close(ep[1]);
        if (pid >= 0) {
            if (wait_child(pid, &status, 8000) != 0) {
                kill(pid, SIGKILL);
                (void)wait_child(pid, &status, 5000);
                CHECK(0 && "run child did not exit in time");
            } else {
                CHECK(WIFEXITED(status) && WEXITSTATUS(status) == 0);
            }
            {
                uint64_t deadline = now_ms() + 3000;

                while (outlen < sizeof(out) - 1) {
                    ssize_t r = read(sp[0], out + outlen,
                                     sizeof(out) - 1 - outlen);

                    if (r > 0) {
                        outlen += (size_t)r;
                        continue;
                    }
                    if (r == 0)
                        break;
                    if (errno == EINTR)
                        continue;
                    if (now_ms() >= deadline)
                        break;
                    msleep(5);
                }
                out[outlen] = '\0';
                deadline = now_ms() + 3000;
                while (errlen < sizeof(err) - 1) {
                    ssize_t r = read(ep[0], err + errlen,
                                     sizeof(err) - 1 - errlen);

                    if (r > 0) {
                        errlen += (size_t)r;
                        continue;
                    }
                    if (r == 0)
                        break;
                    if (errno == EINTR)
                        continue;
                    if (now_ms() >= deadline)
                        break;
                    msleep(5);
                }
                err[errlen] = '\0';
            }
            {
                char want[96];

                snprintf(want, sizeof want, "DEALPG4 STARTED %s\n",
                         g_nonce);
                CHECK(strcmp(out, want) == 0);
            }
            CHECK(dealpg4_parse(err, errlen, &p) == DEALPG4_PARSE_OK
                  && p.type == DEALPG4_REC_REPORT);
            check_report(&p, 0, 0, "-", 1);
            close(sp[0]);
            close(ep[0]);
            assert_no_survivors(5000);
        } else {
            close(sp[0]);
            close(ep[0]);
        }
    }

    /* Run surface exit-code map: a clean containment with a nonzero
     * target exit -> 1 with the real exit code in REPORT. */
    g_ctx = "T4 production run exit 5";
    {
        int sp[2];
        int ep[2];
        child_spec spec;
        pid_t pid;
        int status = -1;
        char err[DEALPG4_MAX_LINE_OTHER_BYTES];
        size_t errlen = 0;

        script_reset();
        if (pipe(sp) != 0 || pipe(ep) != 0) {
            CHECK(0 && "pipe");
            return;
        }
        memset(&spec, 0, sizeof spec);
        spec.kind = CHILD_RUN;
        spec.ctrl_fd = -1;
        spec.cwd = "/tmp";
        spec.nonce = g_nonce;
        spec.invocation_id = 0;
        spec.budget = 45000;
        spec.targv = g_exit5_target;
        spec.targc = 3;
        spec.ready_frame = 0;
        spec.stdout_fd = sp[1];
        spec.stderr_fd = ep[1];
        pid = fork_child(&spec);
        close(sp[1]);
        close(ep[1]);
        if (pid >= 0) {
            if (wait_child(pid, &status, 8000) != 0) {
                kill(pid, SIGKILL);
                (void)wait_child(pid, &status, 5000);
                CHECK(0 && "run child did not exit in time");
            } else {
                CHECK(WIFEXITED(status) && WEXITSTATUS(status) == 1);
            }
            {
                uint64_t deadline = now_ms() + 3000;

                while (errlen < sizeof(err) - 1) {
                    ssize_t r = read(ep[0], err + errlen,
                                     sizeof(err) - 1 - errlen);

                    if (r > 0) {
                        errlen += (size_t)r;
                        continue;
                    }
                    if (r == 0)
                        break;
                    if (errno == EINTR)
                        continue;
                    if (now_ms() >= deadline)
                        break;
                    msleep(5);
                }
                err[errlen] = '\0';
            }
            CHECK(dealpg4_parse(err, errlen, &p) == DEALPG4_PARSE_OK
                  && p.type == DEALPG4_REC_REPORT);
            check_report(&p, 5, 0, "-", 1);
            close(sp[0]);
            close(ep[0]);
            assert_no_survivors(5000);
        } else {
            close(sp[0]);
            close(ep[0]);
        }
    }

    /* Serve missing-ACK deadline path: STARTUP_TIMEOUT at T1. */
    g_ctx = "T4 production missing ACK";
    script_reset();
    if (chan_start(&s, CHILD_CORE, "/tmp", g_nonce, 1, 45000,
                   g_noexec_target, 3)
        == 0) {
        CHECK(rr_expect(&s.reader, DEALPG4_REC_STUB_FORKED, &p, 5000)
              == 0);
        CHECK(rr_expect(&s.reader, DEALPG4_REC_STUB_READY, &p, 5000)
              == 0);
        CHECK(rr_expect(&s.reader, DEALPG4_REC_REPORT, &p, 9000) == 0);
        CHECK(ptok(&p, 18, "STARTUP_TIMEOUT"));
        CHECK(rr_expect(&s.reader, DEALPG4_REC_FAILED, &p, 5000) == 0);
        CHECK(ptok(&p, 1, "STARTUP_TIMEOUT"));
        CHECK(rr_line(&s.reader, 2000, &line, &len) == 0);
        chan_finish(&s, 2, 9000);
    }
    CHECK(!marker_exists());
}

int main(void)
{
    int is_subreaper = 0;

    marker_setup();
    script_reset();

    /* The harness is a subreaper for every scenario: any descendant
     * that outlives its supervisor reparents here and is reaped — the
     * zero-survivor assertion of every case. */
    CHECK(prctl(PR_SET_CHILD_SUBREAPER, 1) == 0);
    CHECK(prctl(PR_GET_CHILD_SUBREAPER, &is_subreaper) == 0
          && is_subreaper == 1);

    /* T1: catalog shape, installer validation, hook determinism. */
    g_ctx = "T1";
    t1_catalog_shape();
    t1_installer_negatives();
    t1_delay_determinism();
    t1_fail_determinism();
    t1_congest_determinism();
    t1_production_inertness();
    dealpg4_fi_restore_defaults();

    /* T2: capability refusals. */
    t2_capability_refusals();

    /* T3: stub failure paths and identity malformation. */
    t3_stub_failures();
    t3_identity_malformation();

    /* T4: seam-driven classification scenarios + production
     * regression. */
    t4_release_races();
    t4_post_release_wedges();
    t4_post_ack_freeze();
    t4_supervisor_death();
    t4_parent_mismatch();
    t4_pdeathsig_cascade();
    t4_status_congestion();
    t4_ctrl_congestion();
    t4_stream_no_eof();
    t4_production_regression();

    assert_no_survivors(3000);

    if (g_failures != 0) {
        fprintf(stderr,
                "fi-seam-tests: %d of %d checks failed\n", g_failures,
                g_checks);
        return 1;
    }
    printf("fi-seam-tests: all %d checks passed\n", g_checks);
    return 0;
}
