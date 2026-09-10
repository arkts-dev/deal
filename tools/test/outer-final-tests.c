/*
 * DEALPG4 outer final-sequence / coordinator-death-classification
 * component acceptance suite (tools/test/outer-final-tests.c).
 *
 * The component-level cases of ISSUE-0299 Verification (epic
 * Sequencing step 7: coordinator death classification, DONE/BYE
 * completion, split immediate escalation scopes, the strictly ordered
 * D8 final sequence, and the final report with the full exit-status
 * matrix), run against tools/src/outer.c + monotonic.c + protocol.c +
 * fi.c + selftest.c + drain.c compiled with the pinned flags
 * (tools/test/run-outer-final-tests.sh). Permanent and re-runnable;
 * lives outside the tools/src/ build glob so the pinned artifact
 * build is unchanged. The suite drives the real core (T1's preamble,
 * loop, write-side discipline, and exit-status mapping + T2's
 * coordinator machinery + T3's broker socket/auth channel + T4's
 * registry/live-phase/DONE trigger + T5's nested channel machine +
 * T6's fallback/total-cancel + T7's classification/report) with
 * scripted coordinator argv (the suite binary re-execs itself as the
 * broker peer), scaled OuterLimits, and in-process spawn-seam
 * compositions — real fork/exec/pipe/signal/waitid/socket/
 * SO_PEERCRED/nonce/registry machinery only, no canned responses.
 *
 * Case groups:
 *  1. Full-success end-to-end (scaled limits): a multi-record
 *     coordinator dispatches record 1 sequentially (INVOKE after the
 *     full STUB_READY/ACK/STARTED/CLEAN cycle), then records 2 and 3
 *     concurrently; every pre-cutoff INVOKE is answered INVOKED; no
 *     DONE arrives before the cutoff (the peer's quiet window spans
 *     the pre-cutoff gap); at the cutoff with the registry non-empty
 *     and fully terminal DONE clean is queued exactly once with every
 *     terminal answer before it; the peer sends BYE after DONE and
 *     exits 0 — the outer never signals a healthy post-DONE
 *     coordinator, runs the final proof, and exits 0; the final
 *     report lists every record CLEAN success with the proof result
 *     and the reap/adoption counts; the socket path is unlinked and
 *     no survivor/zombie/drainer remains.
 *  2. Clean-exit variants: (a) a peer that closes and exits 0
 *     pre-cutoff after its record is terminal — clean exit, final
 *     proof, exit 0, no DONE queued; (b) a zero-record peer —
 *     clean, no DONE; (c) a peer that receives DONE, closes without
 *     BYE, and exits 0 — the EOF-without-BYE discrimination is the
 *     clean exit.
 *  3. COORDINATOR_LOST: (a) a peer that exits 0 without BYE while a
 *     record is live — broker EOF with live records, every live
 *     record CANCELLING, the total-cancel path completes (the
 *     scripted child's CLEAN cancelled), D8 steps 1-2 skipped (no
 *     escalation — the coordinator reaped on its own), step 3 and
 *     the full proof run, gate nonzero with the token; (b) a
 *     coordinator killed with a signal (nonzero reaped status) while
 *     a record is live — same classification; both end with an empty
 *     registry and no survivor.
 *  4. READINESS_TIMEOUT (the split immediate escalation scope): a
 *     coordinator that execs successfully (COORD_READY verified,
 *     cross-checked) and then hangs without connecting fails at the
 *     readiness deadline; the full D8 bounded escalation runs against
 *     the verified group (liveness check kill(-pgid, 0) == 0 with
 *     getpgrp() != pgid, TERM -pgid, grace, KILL -pgid re-verified,
 *     reap, adopted scan) inside the scaled reserve; a TERM-ignoring
 *     coordinator is KILLed and reaped and the proof still completes.
 *  5. COORDINATOR_STARTUP_FAILED (exec-fail): by-pid termination with
 *     steps 1-2 skipped — no group liveness check against the
 *     unverified pgid, the coordinator reaped (status 127), the
 *     group-absent item discharged via reap-to-ECHILD +
 *     adopted-scan + stream-EOF, gate nonzero.
 *  6. COORDINATOR_HANG: (a) a post-DONE hang — DONE queued first,
 *     then the escalation at exactly totalDeadline -
 *     killAndProofReserveMs, never before DONE; TERM -pgid trapped ->
 *     grace -> KILL -pgid -> reap; (b) the D8 precondition deferral —
 *     a record that stays live past the escalation deadline: the
 *     escalation starts the moment the record turns terminal (the
 *     TERM timestamp is never before the record's terminality),
 *     bounded by the total deadline.
 *  7. Exit-status matrix: 2 (usage), 3 (CONFIG_INVALID), 4
 *     (capability-class refusal), plus the 0/1 classes asserted by
 *     groups 1 and 3.
 *  8. Integration (T1-T7) through the real mode entry: the committed
 *     launcher's outer mode runs a scripted coordinator that
 *     dispatches a real INVOKE (the production serve surface forks
 *     the real nested supervisor, which verifies a real stub and
 *     runs target "true") through STUB_READY/ACK/STARTED/OUT_END/
 *     REPORT/CLEAN, closes pre-cutoff, and exits 0 — the clean exit,
 *     the final proof, the record line, and the counts line land in
 *     the report.
 *
 * Every group's internal assertion failures propagate through the
 * helper child's exit status and the captured stderr; the peer's own
 * failures propagate through its exit status (the coordinator reap)
 * and the retained stderr drain — the suite gates on both.
 */
#define _POSIX_C_SOURCE 200809L

#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <sys/wait.h>
#include <unistd.h>

#include "../src/drain.h"
#include "../src/monotonic.h"
#include "../src/outer.h"
#include "../src/protocol.h"
#include "../src/selftest.h"

static int g_checks;
static int g_failures;
static const char *g_suite_argv0;

#define CHECK(cond)                                                     \
    do {                                                                \
        g_checks++;                                                     \
        if (!(cond)) {                                                  \
            g_failures++;                                               \
            fprintf(stderr, "FAIL %s:%d: %s\n", __FILE__, __LINE__,     \
                    #cond);                                             \
        }                                                               \
    } while (0)

#define NONCE "0123456789abcdef0123456789abcdef"

/* Scaled OuterLimits per case group:
 *  - SUCCESS_LIMITS: the full-success and post-DONE-no-BYE runs
 *    (nestedStop 17000 — the delivered T = min(45000, 17000 -
 *    elapsed) stays >= 15000 for the first ~2 s; the cutoff fires at
 *    T0o + 17000 with DONE right behind the last CLEAN; the pinned
 *    escalation deadline T0o + 20000 stays beyond the ~17.1 s
 *    coordinator exit, so a healthy post-DONE coordinator is never
 *    signaled);
 *  - HANG_LIMITS: the post-DONE hang (cutoff T0o + 20000 < the
 *    escalation deadline T0o + 25000, so DONE precedes the
 *    escalation; the grace completes inside the 30000 total);
 *  - DEFER_LIMITS: the precondition deferral (the escalation
 *    deadline T0o + 17000 passes while the record is still live —
 *    the catch-up escalation fires at the record's terminality, the
 *    TERM death lands well before the T0o + 18500 cutoff, so no
 *    DONE is ever queued, all inside the 22000 total);
 *  - LIVE_LIMITS: the clean-exit variants and the COORDINATOR_LOST
 *    runs (the cutoff never fires — every scenario completes within
 *    ~2 s);
 *  - READY_LIMITS: the readiness/startup and exit-status runs. */
static const OuterLimits SUCCESS_LIMITS = {25000, 1000, 17000, 8000, 200};
static const OuterLimits HANG_LIMITS = {30000, 1000, 20000, 10000, 200};
static const OuterLimits DEFER_LIMITS = {22000, 1000, 18500, 3500, 200};
static const OuterLimits LIVE_LIMITS = {18000, 500, 17000, 1000, 200};
static const OuterLimits READY_LIMITS = {10000, 500, 8000, 2000, 200};

/* An invalid limits set (nestedStopMs + cleanupReserveMs > overall):
 * the CONFIG_INVALID exit class. */
static const OuterLimits INVALID_LIMITS = {10000, 500, 8000, 3000, 200};

/* Socket-path scan (the broker socket path prefix under the scratch
 * socket dir "build"). */
static int outer_socket_path_count(const char *dir)
{
    DIR *d = opendir(dir);
    struct dirent *e;
    int count = 0;

    if (d == NULL)
        return 0;
    while ((e = readdir(d)) != NULL) {
        if (strncmp(e->d_name, ".dealpg4-broker-", 16) == 0)
            count++;
    }
    closedir(d);
    return count;
}

/* No-child assertion: waitid(P_ALL) must reach ECHILD. */
static void check_no_children(void)
{
    siginfo_t si;

    memset(&si, 0, sizeof si);
    CHECK(waitid(P_ALL, 0, &si, WEXITED | WNOHANG | WNOWAIT) == -1
          && errno == ECHILD);
}

/* Monotonic-bounded sleep. */
static void sleep_ms(unsigned ms)
{
    struct timespec ts;
    struct timespec rem;

    ts.tv_sec = (time_t)(ms / 1000);
    ts.tv_nsec = (long)(ms % 1000) * 1000000L;
    while (nanosleep(&ts, &rem) != 0 && errno == EINTR)
        ts = rem;
}

static int has_token(const dealpg4_outer_result *v, const char *token)
{
    size_t i;

    for (i = 0; i < v->ntokens; i++) {
        if (strcmp(v->tokens[i], token) == 0)
            return 1;
    }
    return 0;
}

static const char *find_in_retained(const dealpg4_drain_ctx *d,
                                    const char *needle)
{
    size_t nlen = strlen(needle);
    size_t i;
    size_t j;

    if (d->retained_len < nlen)
        return NULL;
    for (i = 0; i + nlen <= d->retained_len; i++) {
        for (j = 0; j < nlen; j++) {
            if (d->retained[i + j] != (unsigned char)needle[j])
                break;
        }
        if (j == nlen)
            return (const char *)d->retained + i;
    }
    return NULL;
}

/* === Scripted nested-channel child (the spawn composition) ============ */

/* One complete record line to the control fd (blocking writes). */
static void child_write_line(int fd, const char *line)
{
    size_t len = strlen(line);
    size_t off = 0;

    while (off < len) {
        ssize_t w = write(fd, line + off, len - off);

        if (w > 0) {
            off += (size_t)w;
            continue;
        }
        if (w < 0 && errno == EINTR)
            continue;
        _exit(9);
    }
}

/* One bounded line read from the control fd (the child side is
 * blocking): 0 = a complete LF-terminated line, -1 = EOF, -2 =
 * timeout, -3 = machinery failure. */
static int child_read_line(int fd, char *buf, size_t cap, int timeout_ms)
{
    size_t off = 0;

    for (;;) {
        struct pollfd pfd;
        int rc;
        char c;
        ssize_t r;

        if (off + 1 >= cap)
            return -3;
        pfd.fd = fd;
        pfd.events = POLLIN;
        pfd.revents = 0;
        rc = poll(&pfd, 1, timeout_ms);
        if (rc < 0 && errno == EINTR)
            continue;
        if (rc < 0)
            return -3;
        if (rc == 0)
            return -2;
        r = read(fd, &c, 1);
        if (r == 1) {
            buf[off++] = c;
            if (c == '\n') {
                buf[off] = '\0';
                return 0;
            }
            continue;
        }
        if (r == 0) {
            buf[off] = '\0';
            return -1;
        }
        if (errno == EINTR)
            continue;
        return -3;
    }
}

/* The stub stand-in: setsid()s (pid == pgid == sid), confirms the
 * session establishment to its parent through the ready pipe, and
 * waits — the parent-death kill (PR_SET_PDEATHSIG, SIGKILL) reaps it
 * the moment the scripted child exits, so no stray task ever
 * outlives its record. */
static void final_stub_runner(int ready_fd)
{
    (void)prctl(PR_SET_PDEATHSIG, SIGKILL);
    if (setsid() == -1)
        _exit(3);
    (void)!write(ready_fd, "x", 1);
    for (;;)
        pause();
}

/* Fork the stub stand-in; waits for the child's setsid confirmation.
 * Returns its pid (a failure exits the scripted child — the scenario
 * is invalid without a real stub identity). */
static pid_t final_fork_stub(void)
{
    int ready[2];
    pid_t p;
    int attempt;

    if (pipe(ready) != 0)
        _exit(9);
    for (attempt = 0; attempt < 20; attempt++) {
        p = fork();
        if (p == 0) {
            close(ready[0]);
            final_stub_runner(ready[1]);
        }
        if (p > 0)
            break;
        sleep_ms(10);
    }
    close(ready[1]);
    if (p <= 0) {
        fprintf(stderr, "CHILD FAIL stub-fork errno=%d\n", errno);
        _exit(9);
    }
    {
        char c;

        for (;;) {
            ssize_t r = read(ready[0], &c, 1);

            if (r == 1)
                break;
            if (r < 0 && errno == EINTR)
                continue;
            fprintf(stderr, "CHILD FAIL stub-confirm r=%zd errno=%d\n", r,
                    errno);
            _exit(9);
        }
    }
    close(ready[0]);
    return p;
}

/* One scripted nested-channel scenario (the serve-surface child).
 * Returns 0 on success (the child's own assertions); the outer reaps
 * the exit status as belt-and-braces — the record's terminality never
 * depends on it. */
static int final_child_run(const char *scenario, int fd, int64_t id,
                           const char *nonce)
{
    char line[DEALPG4_MAX_LINE_OTHER_BYTES + 64];

    if (scenario == NULL)
        return 1;

    if (strcmp(scenario, "final-ok") == 0) {
        /* The full live-phase protocol: real stub identity, verified
         * STUB_READY, the byte-exact ACK relay, STARTED, CLEAN. */
        pid_t stub = final_fork_stub();

        snprintf(line, sizeof line, "DEALPG4 STUB_FORKED %lld %d\n",
                 (long long)id, (int)stub);
        child_write_line(fd, line);
        snprintf(line, sizeof line,
                 "DEALPG4 STUB_READY %lld %d %d %d %s\n", (long long)id,
                 (int)stub, (int)stub, (int)stub, nonce);
        child_write_line(fd, line);
        {
            char want[128];

            snprintf(want, sizeof want, "DEALPG4 ACK %lld %s\n",
                     (long long)id, nonce);
            if (child_read_line(fd, line, sizeof line, 8000) != 0
                || strcmp(line, want) != 0)
                return 2;
        }
        snprintf(line, sizeof line, "DEALPG4 STARTED %lld\n",
                 (long long)id);
        child_write_line(fd, line);
        snprintf(line, sizeof line, "DEALPG4 CLEAN %lld success\n",
                 (long long)id);
        child_write_line(fd, line);
        return 0;
    }

    if (strcmp(scenario, "final-hold") == 0) {
        /* A live record holder (no stub): waits for the validated
         * CANCEL fan-out and replies CLEAN cancelled — the
         * total-cancel completion of the COORDINATOR_LOST runs. */
        char want[128];

        snprintf(want, sizeof want, "DEALPG4 CANCEL %lld %s\n",
                 (long long)id, nonce);
        if (child_read_line(fd, line, sizeof line, 15000) != 0
            || strcmp(line, want) != 0)
            return 2;
        snprintf(line, sizeof line, "DEALPG4 CLEAN %lld cancelled\n",
                 (long long)id);
        child_write_line(fd, line);
        return 0;
    }

    if (strcmp(scenario, "final-late-clean") == 0) {
        /* The precondition-deferral holder: STUB_BLOCKED for ~17.3 s
         * (past the scaled escalation deadline at T0o + 17000, well
         * before its own T0o + 18500 per-record deadline), then its
         * own CLEAN — the D8 escalation may only start after this
         * terminality. */
        pid_t stub = final_fork_stub();

        snprintf(line, sizeof line, "DEALPG4 STUB_FORKED %lld %d\n",
                 (long long)id, (int)stub);
        child_write_line(fd, line);
        sleep_ms(17300);
        snprintf(line, sizeof line, "DEALPG4 CLEAN %lld success\n",
                 (long long)id);
        child_write_line(fd, line);
        return 0;
    }

    fprintf(stderr, "CHILD FAIL unknown-scenario %s\n", scenario);
    return 1;
}

/* === Spawn-seam composition =========================================== */

typedef struct final_spawn {
    int mode;
    int fork_calls;
    int64_t last_id;
    char last_nonce[DEALPG4_NONCE_HEX_CHARS + 1];
    int last_control_fd;
} final_spawn;

static int final_fork_nested(const dealpg4_outer_spawn *self,
                             char *const serve_argv[],
                             const char nonce[33], int64_t budget_t,
                             int64_t invocation_id, int child_control_fd)
{
    final_spawn *sp = (final_spawn *)self->opaque;
    const char *scenario = NULL;
    pid_t pid;

    (void)budget_t;
    sp->fork_calls++;
    sp->last_id = invocation_id;
    memcpy(sp->last_nonce, nonce, DEALPG4_NONCE_HEX_CHARS);
    sp->last_nonce[DEALPG4_NONCE_HEX_CHARS] = '\0';
    sp->last_control_fd = child_control_fd;
    if (serve_argv != NULL && serve_argv[4] != NULL)
        scenario = serve_argv[4];

    pid = fork();
    if (pid < 0) {
        errno = EAGAIN;
        return -1;
    }
    if (pid == 0) {
        int rc;

        (void)prctl(PR_SET_PDEATHSIG, SIGKILL);
        rc = final_child_run(scenario, child_control_fd, invocation_id,
                             nonce);
        _exit(rc != 0 ? 7 : 0);
    }
    return pid;
}

static dealpg4_outer_spawn make_spawn(final_spawn *sp)
{
    dealpg4_outer_spawn s;

    s.fork_nested = final_fork_nested;
    s.opaque = sp;
    return s;
}

/* === Scripted-peer machinery (the suite re-execs as the
 * coordinator) ======================================================== */

static int peer_connect(const char *path)
{
    struct sockaddr_un sun;
    int fd = socket(AF_UNIX, SOCK_STREAM, 0);

    if (fd < 0)
        return -1;
    memset(&sun, 0, sizeof sun);
    sun.sun_family = AF_UNIX;
    if (strlen(path) >= sizeof sun.sun_path) {
        close(fd);
        return -1;
    }
    strcpy(sun.sun_path, path);
    if (connect(fd, (struct sockaddr *)&sun, sizeof sun) != 0) {
        close(fd);
        return -1;
    }
    return fd;
}

static int peer_write_all(int fd, const char *data, size_t len)
{
    size_t off = 0;

    while (off < len) {
        ssize_t w = write(fd, data + off, len - off);

        if (w > 0) {
            off += (size_t)w;
            continue;
        }
        if (w < 0 && errno == EINTR)
            continue;
        return -1;
    }
    return 0;
}

/* One bounded line read: 0 = a complete LF-terminated line, -1 = EOF,
 * -2 = timeout, -3 = machinery failure. */
static int peer_read_line(int fd, char *buf, size_t cap, int timeout_ms)
{
    size_t off = 0;

    for (;;) {
        struct pollfd pfd;
        int rc;
        char c;
        ssize_t r;

        if (off + 1 >= cap)
            return -3;
        pfd.fd = fd;
        pfd.events = POLLIN;
        pfd.revents = 0;
        rc = poll(&pfd, 1, timeout_ms);
        if (rc < 0 && errno == EINTR)
            continue;
        if (rc < 0)
            return -3;
        if (rc == 0)
            return -2;
        r = read(fd, &c, 1);
        if (r == 1) {
            buf[off++] = c;
            if (c == '\n') {
                buf[off] = '\0';
                return 0;
            }
            continue;
        }
        if (r == 0) {
            buf[off] = '\0';
            return -1;
        }
        if (errno == EINTR)
            continue;
        return -3;
    }
}

static int peer_do_handshake(int fd, const char *nonce)
{
    char line[256];
    char cmd[128];
    int n;

    n = snprintf(cmd, sizeof cmd, "DEALPG4 HELLO %s\n", nonce);
    if (n <= 0 || (size_t)n >= sizeof cmd
        || peer_write_all(fd, cmd, (size_t)n) != 0)
        return 1;
    if (peer_read_line(fd, line, sizeof line, 5000) != 0
        || strcmp(line, "DEALPG4 HELLO_OK 4 63\n") != 0)
        return 1;
    n = snprintf(cmd, sizeof cmd, "DEALPG4 FEATURE_READY %s\n", nonce);
    if (n <= 0 || (size_t)n >= sizeof cmd
        || peer_write_all(fd, cmd, (size_t)n) != 0)
        return 1;
    if (peer_read_line(fd, line, sizeof line, 5000) != 0
        || strlen(line) != 18 + 32 + 1
        || memcmp(line, "DEALPG4 READY_ACK ", 18) != 0
        || memcmp(line + 18, nonce, 32) != 0)
        return 1;
    return 0;
}

static void hex_encode(const char *in, char *out, size_t out_cap)
{
    static const char digits[] = "0123456789abcdef";
    size_t i;

    for (i = 0; in[i] != '\0' && 2 * i + 2 < out_cap; i++) {
        unsigned char c = (unsigned char)in[i];

        out[2 * i] = digits[c >> 4];
        out[2 * i + 1] = digits[c & 0xf];
    }
    out[2 * i] = '\0';
}

/* Build one well-formed INVOKE with a single-argv scenario target. */
static void peer_build_invoke(char *buf, size_t cap, const char *tag,
                              const char *scenario)
{
    char hex[128];

    hex_encode(scenario, hex, sizeof hex);
    snprintf(buf, cap, "DEALPG4 INVOKE %s 2f 1 %s\n", tag, hex);
}

/* Parse "DEALPG4 INVOKED <id> <tag>\n": 0 on success. */
static int peer_parse_invoked(const char *line, int64_t *id)
{
    long long v = 0;
    char tag[64];

    if (sscanf(line, "DEALPG4 INVOKED %lld %63s", &v, tag) != 2)
        return 1;
    if (line[strlen(line) - 1] != '\n')
        return 1;
    *id = (int64_t)v;
    return 0;
}

/* Parse "DEALPG4 STUB_READY <id> <pid> <pgid> <sid> <nonce>\n". */
static int peer_parse_stub_ready(const char *line, int64_t *id,
                                 char nonce[DEALPG4_NONCE_HEX_CHARS + 1])
{
    long long v = 0;
    long long pid = 0;
    long long pgid = 0;
    long long sid = 0;
    char n[64];

    if (sscanf(line, "DEALPG4 STUB_READY %lld %lld %lld %lld %63s", &v,
               &pid, &pgid, &sid, n) != 5)
        return 1;
    if (line[strlen(line) - 1] != '\n')
        return 1;
    if (strlen(n) != DEALPG4_NONCE_HEX_CHARS)
        return 1;
    *id = (int64_t)v;
    memcpy(nonce, n, DEALPG4_NONCE_HEX_CHARS);
    nonce[DEALPG4_NONCE_HEX_CHARS] = '\0';
    return 0;
}

static int peer_send_ack(int fd, int64_t id, const char *nonce)
{
    char cmd[128];
    int n;

    n = snprintf(cmd, sizeof cmd, "DEALPG4 ACK %lld %s\n",
                 (long long)id, nonce);
    if (n <= 0 || (size_t)n >= sizeof cmd)
        return 1;
    return peer_write_all(fd, cmd, (size_t)n) != 0;
}

/* Parse "DEALPG4 STARTED <id>\n" / "DEALPG4 CLEAN <id> success\n". */
static int peer_parse_started(const char *line, int64_t *id)
{
    long long v = 0;

    if (sscanf(line, "DEALPG4 STARTED %lld\n", &v) != 1)
        return 1;
    *id = (int64_t)v;
    return 0;
}

static int peer_parse_clean(const char *line, int64_t *id)
{
    long long v = 0;
    char verdict[16];

    if (sscanf(line, "DEALPG4 CLEAN %lld %15s", &v, verdict) != 2)
        return 1;
    if (line[strlen(line) - 1] != '\n')
        return 1;
    if (strcmp(verdict, "success") != 0)
        return 1;
    *id = (int64_t)v;
    return 0;
}

/* Dispatch one record to full completion: INVOKE -> INVOKED ->
 * STUB_READY -> ACK -> STARTED -> CLEAN. The expected id is checked
 * on every answer. */
static int peer_run_record(int fd, const char *tag, const char *target,
                           int64_t want_id)
{
    char inv[256];
    char line[256];
    char nonce[DEALPG4_NONCE_HEX_CHARS + 1];
    int64_t id = 0;

    peer_build_invoke(inv, sizeof inv, tag, target);
    if (peer_write_all(fd, inv, strlen(inv)) != 0)
        return 1;
    if (peer_read_line(fd, line, sizeof line, 8000) != 0
        || peer_parse_invoked(line, &id) != 0 || id != want_id)
        return 1;
    if (peer_read_line(fd, line, sizeof line, 8000) != 0
        || peer_parse_stub_ready(line, &id, nonce) != 0
        || id != want_id)
        return 1;
    if (peer_send_ack(fd, id, nonce) != 0)
        return 1;
    if (peer_read_line(fd, line, sizeof line, 8000) != 0
        || peer_parse_started(line, &id) != 0 || id != want_id)
        return 1;
    if (peer_read_line(fd, line, sizeof line, 8000) != 0
        || peer_parse_clean(line, &id) != 0 || id != want_id)
        return 1;
    return 0;
}

/* One scripted coordinator scenario. Returns the exit status (the
 * peer's own assertions). */
static int final_peer_main(const char *scenario, int fd,
                           const char *nonce)
{
    char line[512];
    char inv[256];
    char nonce_a[DEALPG4_NONCE_HEX_CHARS + 1];
    char nonce_b[DEALPG4_NONCE_HEX_CHARS + 1];
    int64_t id = 0;
    int64_t id2 = 0;
    int rc;
    int64_t a_started;
    int64_t b_started;
    int64_t a_clean;
    int64_t b_clean;
    uint64_t peer_start = dealpg4_now_ms();

    if (strcmp(scenario, "zero") == 0) {
        /* No records: nothing is ever queued after the handshake (a
         * bounded read times out); the clean path is the
         * peer-initiated close + exit 0. */
        if (peer_read_line(fd, line, sizeof line, 400) != -2)
            return 1;
        close(fd);
        printf("PEER zero\n");
        fflush(stdout);
        return 0;
    }

    if (strcmp(scenario, "success") == 0) {
        /* Record 1 sequential (after its full CLEAN cycle), then
         * records 2 and 3 concurrent. */
        if (peer_run_record(fd, "tag", "final-ok", 1) != 0)
            return 1;
        peer_build_invoke(inv, sizeof inv, "tag", "final-ok");
        if (peer_write_all(fd, inv, strlen(inv)) != 0
            || peer_read_line(fd, line, sizeof line, 8000) != 0
            || peer_parse_invoked(line, &id) != 0 || id != 2
            || peer_read_line(fd, line, sizeof line, 8000) != 0
            || peer_parse_stub_ready(line, &id2, nonce_a) != 0
            || id2 != 2)
            return 1;
        if (peer_write_all(fd, inv, strlen(inv)) != 0
            || peer_read_line(fd, line, sizeof line, 8000) != 0
            || peer_parse_invoked(line, &id) != 0 || id != 3
            || peer_read_line(fd, line, sizeof line, 8000) != 0
            || peer_parse_stub_ready(line, &id2, nonce_b) != 0
            || id2 != 3)
            return 1;
        if (peer_send_ack(fd, 2, nonce_a) != 0
            || peer_send_ack(fd, 3, nonce_b) != 0)
            return 1;
        /* The concurrent completions: per id STARTED precedes its
         * CLEAN; both CLEANs precede DONE (every terminal answer
         * precedes DONE). */
        a_started = 0;
        b_started = 0;
        a_clean = 0;
        b_clean = 0;
        while (!a_clean || !b_clean) {
            rc = peer_read_line(fd, line, sizeof line, 8000);
            if (rc != 0)
                return 1;
            if (peer_parse_started(line, &id) == 0) {
                if (id == 2 && !a_started && !a_clean)
                    a_started = 1;
                else if (id == 3 && !b_started && !b_clean)
                    b_started = 1;
                else
                    return 1;
                continue;
            }
            if (peer_parse_clean(line, &id) == 0) {
                if (id == 2 && a_started && !a_clean)
                    a_clean = 1;
                else if (id == 3 && b_started && !b_clean)
                    b_clean = 1;
                else
                    return 1;
                continue;
            }
            return 1;
        }
        /* No DONE before the cutoff: the next line must be DONE and
         * it must arrive only at/after the scaled cutoff (T0o +
         * 17000; the peer's clock started at ~T0o). */
        rc = peer_read_line(fd, line, sizeof line, 30000);
        if (rc != 0 || strcmp(line, "DEALPG4 DONE clean\n") != 0)
            return 1;
        if (dealpg4_now_ms() - peer_start < 16000)
            return 1;
        if (peer_write_all(fd, "DEALPG4 BYE\n", 12) != 0)
            return 1;
        close(fd);
        printf("PEER success\n");
        fflush(stdout);
        return 0;
    }

    if (strcmp(scenario, "success-nobye") == 0) {
        if (peer_run_record(fd, "tag", "final-ok", 1) != 0)
            return 1;
        rc = peer_read_line(fd, line, sizeof line, 30000);
        if (rc != 0 || strcmp(line, "DEALPG4 DONE clean\n") != 0)
            return 1;
        close(fd); /* EOF without BYE after DONE: the D8
                      discrimination must still classify the clean
                      exit */
        printf("PEER success-nobye\n");
        fflush(stdout);
        return 0;
    }

    if (strcmp(scenario, "precutoff-close") == 0) {
        if (peer_run_record(fd, "tag", "final-ok", 1) != 0)
            return 1;
        /* Close + exit 0 well before the cutoff (T0o + 17000): the
         * clean exit with no DONE queued, none needed. */
        close(fd);
        printf("PEER precutoff-close\n");
        fflush(stdout);
        return 0;
    }

    if (strcmp(scenario, "lost-exit0") == 0) {
        /* One live record, then close + exit 0 without BYE: broker
         * EOF with live records is COORDINATOR_LOST (the caller-loss
         * total-cancel). */
        peer_build_invoke(inv, sizeof inv, "tag", "final-hold");
        if (peer_write_all(fd, inv, strlen(inv)) != 0
            || peer_read_line(fd, line, sizeof line, 8000) != 0
            || peer_parse_invoked(line, &id) != 0 || id != 1)
            return 1;
        close(fd);
        printf("PEER lost-exit0\n");
        fflush(stdout);
        return 0;
    }

    if (strcmp(scenario, "lost-kill") == 0) {
        /* One live record, then self-kill: the nonzero reaped status
         * and the broker EOF are the COORDINATOR_LOST facts. */
        peer_build_invoke(inv, sizeof inv, "tag", "final-hold");
        if (peer_write_all(fd, inv, strlen(inv)) != 0
            || peer_read_line(fd, line, sizeof line, 8000) != 0
            || peer_parse_invoked(line, &id) != 0 || id != 1)
            return 1;
        printf("PEER lost-kill\n");
        fflush(stdout);
        (void)raise(SIGKILL);
        pause();
        return 1; /* unreachable */
    }

    if (strcmp(scenario, "postdone-hang") == 0) {
        if (peer_run_record(fd, "tag", "final-ok", 1) != 0)
            return 1;
        rc = peer_read_line(fd, line, sizeof line, 30000);
        if (rc != 0 || strcmp(line, "DEALPG4 DONE clean\n") != 0)
            return 1;
        printf("PEER postdone-hang\n");
        fflush(stdout);
        /* Hang after DONE with the broker still open: the D8
         * escalation runs only at the pinned escalation deadline
         * (totalDeadline - killAndProofReserveMs). TERM is trapped,
         * the re-verified KILL reaps this peer. */
        (void)signal(SIGTERM, SIG_IGN);
        for (;;)
            pause();
    }

    if (strcmp(scenario, "defer") == 0) {
        /* One record that stays live past the escalation deadline
         * (the scripted child's CLEAN lands at ~16 s): the
         * escalation must not fire before every record is terminal —
         * it starts the moment the precondition holds. */
        peer_build_invoke(inv, sizeof inv, "tag", "final-late-clean");
        if (peer_write_all(fd, inv, strlen(inv)) != 0
            || peer_read_line(fd, line, sizeof line, 8000) != 0
            || peer_parse_invoked(line, &id) != 0 || id != 1)
            return 1;
        printf("PEER defer\n");
        fflush(stdout);
        /* Hang with the default TERM disposition: the catch-up
         * escalation's TERM -pgid kills this peer at the record's
         * terminality (the TERM-death path — the run ends before the
         * cutoff). */
        for (;;)
            pause();
    }

    if (strcmp(scenario, "real-invoke") == 0) {
        /* The production serve surface (real nested supervisor fork,
         * real stub, target "true"): STARTED, the two OUT_END lines,
         * REPORT, CLEAN success — the full T1-T7 frame through the
         * mode entry. */
        char a[16];
        char done = 0;

        hex_encode("true", a, sizeof a);
        snprintf(inv, sizeof inv, "DEALPG4 INVOKE tag 2f 1 %s\n", a);
        if (peer_write_all(fd, inv, strlen(inv)) != 0
            || peer_read_line(fd, line, sizeof line, 15000) != 0
            || peer_parse_invoked(line, &id) != 0 || id != 1
            || peer_read_line(fd, line, sizeof line, 15000) != 0
            || peer_parse_stub_ready(line, &id, nonce_a) != 0
            || id != 1)
            return 1;
        if (peer_send_ack(fd, 1, nonce_a) != 0)
            return 1;
        for (;;) {
            rc = peer_read_line(fd, line, sizeof line, 15000);
            if (rc != 0)
                return 1;
            if (strncmp(line, "DEALPG4 STARTED ", 16) == 0
                || strncmp(line, "DEALPG4 OUT ", 13) == 0
                || strncmp(line, "DEALPG4 OUT_END ", 16) == 0
                || strncmp(line, "DEALPG4 REPORT ", 15) == 0)
                continue;
            if (peer_parse_clean(line, &id) == 0 && id == 1) {
                done = 1;
                break;
            }
            return 1;
        }
        if (!done)
            return 1;
        close(fd); /* pre-cutoff clean exit (no DONE on this path) */
        printf("PEER real-invoke\n");
        fflush(stdout);
        return 0;
    }

    fprintf(stderr, "PEER FAIL unknown-scenario %s\n", scenario);
    return 1;
}

/* The scripted-peer re-exec entry. */
static int final_peer_entry(int argc, char **argv)
{
    const char *path = getenv("DEALPG4_BROKER_PATH");
    const char *nonce = getenv("DEALPG4_NONCE");
    int fd;

    (void)signal(SIGPIPE, SIG_IGN);
    if (argc < 3 || path == NULL || nonce == NULL) {
        fprintf(stderr, "PEER FAIL missing-env\n");
        return 1;
    }
    fd = peer_connect(path);
    if (fd < 0) {
        fprintf(stderr, "PEER FAIL connect\n");
        return 1;
    }
    if (peer_do_handshake(fd, nonce) != 0) {
        fprintf(stderr, "PEER FAIL handshake\n");
        return 1;
    }
    return final_peer_main(argv[2], fd, nonce);
}

/* The never-connecting coordinator (the READINESS_TIMEOUT scenarios
 * re-exec the suite binary as the coordinator): mode "1" traps TERM
 * (the grace -> KILL path); mode "0" keeps the default disposition
 * (the TERM-death path). */
static int final_hang_entry(int argc, char **argv)
    __attribute__((noreturn));

static int final_hang_entry(int argc, char **argv)
{
    if (argc >= 3 && strcmp(argv[2], "1") == 0)
        (void)signal(SIGTERM, SIG_IGN);
    for (;;)
        pause();
}

/* === Helper-child machinery =========================================== */

typedef int (*outer_test_fn)(void);

static int run_capture_child(outer_test_fn fn, char *errbuf,
                             size_t errcap, int *status)
{
    int pipefd[2];
    pid_t pid;
    size_t off = 0;

    if (pipe(pipefd) != 0)
        return -1;
    pid = fork();
    if (pid < 0) {
        close(pipefd[0]);
        close(pipefd[1]);
        return -1;
    }
    if (pid == 0) {
        int r;

        close(pipefd[0]);
        if (dup2(pipefd[1], 2) == -1)
            _exit(125);
        close(pipefd[1]);
        g_checks = 0;
        g_failures = 0;
        r = fn();
        _exit(r != 0 ? 1 : 0);
    }
    close(pipefd[1]);
    for (;;) {
        ssize_t r = read(pipefd[0], errbuf + off, errcap - off - 1);

        if (r > 0) {
            off += (size_t)r;
            if (off >= errcap - 1)
                break;
            continue;
        }
        if (r < 0 && errno == EINTR)
            continue;
        break;
    }
    close(pipefd[0]);
    errbuf[off] = '\0';
    if (waitpid(pid, status, 0) != pid)
        return -1;
    return 0;
}

static void gate_group(const char *name, const char *errbuf, int status)
{
    int child_ok = (WIFEXITED(status) && WEXITSTATUS(status) == 0);
    int no_fail_lines = (strstr(errbuf, "FAIL ") == NULL);

    CHECK(child_ok);
    CHECK(no_fail_lines);
    if (!child_ok)
        fprintf(stderr,
                "group %s: helper child propagated failures "
                "(status 0x%x, WIFEXITED=%d)\n",
                name, status, WIFEXITED(status) != 0);
    if (!no_fail_lines)
        fprintf(stderr, "group %s: captured child stderr:\n%s",
                name, errbuf);
}

/* One full-core call in a pipe-backed report fd with a spawn
 * composition. Returns the core status. */
static int core_with_spawn(const OuterLimits *limits,
                           char *const coordinator_argv[],
                           const dealpg4_outer_spawn *spawn,
                           char *report, size_t report_cap)
{
    int pipefd[2];
    ssize_t total = 0;
    int status;

    CHECK(pipe(pipefd) == 0);
    status = dealpg4_outer_core(limits, NONCE, coordinator_argv,
                                "build", pipefd[1], spawn);
    close(pipefd[1]);
    for (;;) {
        ssize_t r = read(pipefd[0], report + total,
                         report_cap - (size_t)total - 1);

        if (r > 0) {
            total += r;
            continue;
        }
        if (r < 0 && errno == EINTR)
            continue;
        break;
    }
    close(pipefd[0]);
    report[total] = '\0';
    return status;
}

static void peer_argv(char *argv[8], const char *scenario)
{
    size_t n = 0;

    argv[n++] = (char *)g_suite_argv0;
    argv[n++] = (char *)"--final-peer";
    argv[n++] = (char *)scenario;
    argv[n] = NULL;
}

/* Common per-run assertions: the peer exited clean (its own
 * assertions held), no peer failure line in the stderr drain, the
 * broker surface closed and unlinked, no children. */
static void assert_common(const dealpg4_outer_result *view)
{
    const dealpg4_drain_ctx *derr = NULL;

    dealpg4_outer_drain_state(NULL, &derr);
    CHECK(view->coordinator_exited_0 == 1
          || view->coordinator_reaped == 1);
    if (derr != NULL) {
        int no_peer_fail = (derr->retained_len == 0
                            || find_in_retained(derr, "PEER FAIL")
                                   == NULL);

        CHECK(no_peer_fail);
        if (!no_peer_fail) {
            fprintf(stderr, "DIAG peer-stderr[%.*s]\n",
                    (int)derr->retained_len,
                    (const char *)derr->retained);
        }
    }
    CHECK(view->broker_closed == 1);
    CHECK(view->broker_socket_unlinked == 1);
}

/* Run one spawn-composition scenario. */
static void run_final_case(const OuterLimits *limits,
                           const char *peer_scenario, char *report,
                           size_t report_cap, int *status,
                           dealpg4_outer_result *view)
{
    final_spawn sp;
    dealpg4_outer_spawn spawn = make_spawn(&sp);
    char *argv[8];

    memset(&sp, 0, sizeof sp);
    peer_argv(argv, peer_scenario);
    *status = core_with_spawn(limits, argv, &spawn, report, report_cap);
    memset(view, 0, sizeof *view);
    dealpg4_outer_last_result(view);
    if (*status != 0) {
        const dealpg4_drain_ctx *derr = NULL;
        size_t ti;

        fprintf(stderr, "DIAG %s status=%d records=%d clean=%d "
                "failed=%d live=%d done=%d reaped=%d code=%d st=%d "
                "escal_term=%d fork_calls=%d\n", peer_scenario, *status,
                view->records_total, view->records_clean,
                view->records_failed, view->records_live,
                view->done_queued, view->coordinator_reaped,
                view->coordinator_si_code, view->coordinator_si_status,
                view->escalation_term_issued, sp.fork_calls);
        for (ti = 0; ti < view->ntokens; ti++)
            fprintf(stderr, "DIAG token %s\n", view->tokens[ti]);
        dealpg4_outer_drain_state(NULL, &derr);
        if (derr != NULL && derr->retained_len > 0)
            fprintf(stderr, "DIAG peer-stderr[%.*s]\n",
                    (int)derr->retained_len,
                    (const char *)derr->retained);
        fprintf(stderr, "DIAG report[%s]\n", report);
    }
    assert_common(view);
}

/* === Case groups ====================================================== */

/* Group 1: full-success end-to-end (multi-record sequential +
 * concurrent dispatch, DONE clean exactly once after the cutoff,
 * BYE, exit 0, the final report). */
static int case_success_fn(void)
{
    char report[2048];
    dealpg4_outer_result view;
    dealpg4_outer_record_view rv;
    uint64_t before;
    uint64_t after;
    int status;

    before = dealpg4_now_ms();
    run_final_case(&SUCCESS_LIMITS, "success", report, sizeof report,
                   &status, &view);
    after = dealpg4_now_ms();

    CHECK(status == 0);
    CHECK(view.exit_status == 0);
    CHECK(after - before >= 16500); /* DONE only after the cutoff */
    CHECK(after - before < 24000);
    CHECK(view.done_queued == 1); /* exactly once */
    CHECK(view.done_clean == 1);
    CHECK(view.done_ms >= 16500); /* at/after the cutoff */
    CHECK(view.records_total == 3);
    CHECK(view.records_clean == 3);
    CHECK(view.records_failed == 0);
    CHECK(view.records_live == 0);
    CHECK(view.stub_ready_forwarded == 3);
    CHECK(view.ack_write_completions == 3);
    CHECK(view.nested_terminal_relays == 3);
    CHECK(view.ntokens == 0);
    /* A healthy coordinator that exits 0 after DONE is never
     * signaled. */
    CHECK(view.escalation_term_issued == 0);
    CHECK(view.escalation_kill_issued == 0);
    CHECK(view.coordinator_exited_0 == 1);
    CHECK(view.coordinator_si_code == CLD_EXITED);
    CHECK(view.coordinator_si_status == 0);
    CHECK(view.proof_passed == 1);
    CHECK(view.proof_reap_echild == 1);
    CHECK(view.proof_adopted_clean == 1);
    CHECK(view.proof_group_clean == 1);
    CHECK(view.proof_streams_eof == 1);
    CHECK(view.proof_broker_clean == 1);
    CHECK(view.proof_registry_clean == 1);

    /* The registry retains every record's full path. */
    CHECK(dealpg4_outer_registry_record(0, &rv) == 0);
    CHECK(rv.state == DEALPG4_OUTER_REC_CLEAN);
    CHECK(rv.clean_final == 1);
    CHECK(rv.history_count == 5); /* FORKING -> STUB_BLOCKED ->
                                     TARGET_PUBLISHED -> RELEASED ->
                                     CLEAN */
    CHECK(dealpg4_outer_registry_record(1, &rv) == 0);
    CHECK(rv.state == DEALPG4_OUTER_REC_CLEAN);
    CHECK(dealpg4_outer_registry_record(2, &rv) == 0);
    CHECK(rv.state == DEALPG4_OUTER_REC_CLEAN);

    /* The final report: every record's terminal state, the proof
     * result, the reap/adoption counts, the elapsed time, no gate
     * token. */
    CHECK(strstr(report, "OUTER final 0 ") != NULL);
    CHECK(strstr(report, "OUTER proof ok\n") != NULL);
    CHECK(strstr(report, "OUTER counts reaped=") != NULL);
    CHECK(strstr(report, "OUTER record 1 CLEAN tag success\n") != NULL);
    CHECK(strstr(report, "OUTER record 2 CLEAN tag success\n") != NULL);
    CHECK(strstr(report, "OUTER record 3 CLEAN tag success\n") != NULL);
    CHECK(strstr(report, "OUTER token ") == NULL);

    check_no_children();
    CHECK(outer_socket_path_count("build") == 0);
    return (g_failures > 0) ? 1 : 0;
}

/* Group 2a: the pre-cutoff close + exit 0 after the record's
 * terminality — clean exit, no DONE queued. */
static int case_precutoff_close_fn(void)
{
    char report[1024];
    dealpg4_outer_result view;
    uint64_t before;
    uint64_t after;
    int status;

    before = dealpg4_now_ms();
    run_final_case(&LIVE_LIMITS, "precutoff-close", report,
                   sizeof report, &status, &view);
    after = dealpg4_now_ms();

    CHECK(status == 0);
    CHECK(after - before < 8000);
    CHECK(view.done_queued == 0); /* no DONE was queued, none needed */
    CHECK(view.records_clean == 1);
    CHECK(view.records_live == 0);
    CHECK(view.ntokens == 0);
    CHECK(view.escalation_term_issued == 0);
    CHECK(view.coordinator_exited_0 == 1);
    CHECK(view.proof_passed == 1);
    CHECK(strstr(report, "OUTER record 1 CLEAN tag success\n") != NULL);

    check_no_children();
    CHECK(outer_socket_path_count("build") == 0);
    return (g_failures > 0) ? 1 : 0;
}

/* Group 2b: the zero-record coordinator — clean, no DONE. */
static int case_zero_record_fn(void)
{
    char report[1024];
    dealpg4_outer_result view;
    uint64_t before;
    uint64_t after;
    int status;

    before = dealpg4_now_ms();
    run_final_case(&LIVE_LIMITS, "zero", report, sizeof report, &status,
                   &view);
    after = dealpg4_now_ms();

    CHECK(status == 0);
    CHECK(after - before < 8000);
    CHECK(view.records_total == 0);
    CHECK(view.done_queued == 0); /* never on the empty registry */
    CHECK(view.ntokens == 0);
    CHECK(view.coordinator_exited_0 == 1);
    CHECK(view.proof_passed == 1);
    CHECK(strstr(report, "OUTER token ") == NULL);

    check_no_children();
    CHECK(outer_socket_path_count("build") == 0);
    return (g_failures > 0) ? 1 : 0;
}

/* Group 2c: exit 0 without BYE after DONE (the EOF-without-BYE
 * discrimination is the clean exit). */
static int case_nobye_after_done_fn(void)
{
    char report[1024];
    dealpg4_outer_result view;
    uint64_t before;
    uint64_t after;
    int status;

    before = dealpg4_now_ms();
    run_final_case(&SUCCESS_LIMITS, "success-nobye", report,
                   sizeof report, &status, &view);
    after = dealpg4_now_ms();

    CHECK(status == 0);
    CHECK(after - before >= 16500);
    CHECK(after - before < 24000);
    CHECK(view.done_queued == 1);
    CHECK(view.done_clean == 1);
    CHECK(view.broker_eof == 1); /* EOF without BYE observed */
    CHECK(view.records_clean == 1);
    CHECK(view.records_live == 0);
    CHECK(view.ntokens == 0);
    CHECK(view.escalation_term_issued == 0);
    CHECK(view.coordinator_exited_0 == 1);
    CHECK(view.proof_passed == 1);

    check_no_children();
    CHECK(outer_socket_path_count("build") == 0);
    return (g_failures > 0) ? 1 : 0;
}

/* Group 3a: COORDINATOR_LOST — exit 0 without BYE while a record is
 * live. */
static int case_lost_exit0_fn(void)
{
    char report[1024];
    dealpg4_outer_result view;
    dealpg4_outer_record_view rv;
    uint64_t before;
    uint64_t after;
    int status;

    before = dealpg4_now_ms();
    run_final_case(&LIVE_LIMITS, "lost-exit0", report, sizeof report,
                   &status, &view);
    after = dealpg4_now_ms();

    CHECK(status == DEALPG4_OUTER_EXIT_GATE_FAILURE);
    CHECK(view.exit_status == 1);
    CHECK(after - before < 8000);
    CHECK(has_token(&view, "COORDINATOR_LOST"));
    CHECK(view.caller_loss_marked == 1);
    CHECK(view.cancel_fanout_writes == 1); /* the total-cancel fan-out */
    /* The total-cancel path completed: the record is terminal (clean
     * cancelled), the registry ends empty. */
    CHECK(view.records_clean == 1);
    CHECK(view.records_live == 0);
    CHECK(view.records_failed == 0);
    CHECK(view.done_queued == 0);
    /* Steps 1-2 skipped: the coordinator reaped on its own — no
     * escalation ever ran. */
    CHECK(view.escalation_term_issued == 0);
    CHECK(view.coordinator_reaped == 1);
    CHECK(view.coordinator_exited_0 == 1);
    CHECK(view.coordinator_si_code == CLD_EXITED);
    CHECK(view.coordinator_si_status == 0);
    /* Step 3 and the full final proof ran. */
    CHECK(view.proof_passed == 1);
    CHECK(view.proof_reap_echild == 1);
    CHECK(view.proof_adopted_clean == 1);
    CHECK(view.proof_streams_eof == 1);
    CHECK(dealpg4_outer_registry_record(0, &rv) == 0);
    CHECK(rv.state == DEALPG4_OUTER_REC_CLEAN);
    CHECK(rv.clean_final == 0); /* cancelled */
    CHECK(strstr(report, "OUTER token COORDINATOR_LOST\n") != NULL);
    CHECK(strstr(report, "OUTER record 1 CLEAN tag cancelled\n")
          != NULL);

    check_no_children();
    CHECK(outer_socket_path_count("build") == 0);
    return (g_failures > 0) ? 1 : 0;
}

/* Group 3b: COORDINATOR_LOST — the coordinator killed with a signal
 * (nonzero reaped status) while a record is live. */
static int case_lost_kill_fn(void)
{
    char report[1024];
    dealpg4_outer_result view;
    uint64_t before;
    uint64_t after;
    int status;

    before = dealpg4_now_ms();
    run_final_case(&LIVE_LIMITS, "lost-kill", report, sizeof report,
                   &status, &view);
    after = dealpg4_now_ms();

    CHECK(status == DEALPG4_OUTER_EXIT_GATE_FAILURE);
    CHECK(after - before < 8000);
    CHECK(has_token(&view, "COORDINATOR_LOST"));
    CHECK(view.caller_loss_marked == 1);
    CHECK(view.records_clean == 1); /* total-cancel completed */
    CHECK(view.records_live == 0);
    CHECK(view.escalation_term_issued == 0);
    CHECK(view.coordinator_reaped == 1);
    CHECK(view.coordinator_si_code == CLD_KILLED);
    CHECK(view.coordinator_si_status == SIGKILL);
    CHECK(view.proof_passed == 1);

    check_no_children();
    CHECK(outer_socket_path_count("build") == 0);
    return (g_failures > 0) ? 1 : 0;
}

/* Group 4a: READINESS_TIMEOUT — the coordinator execs (COORD_READY
 * verified) and hangs without connecting; the full group escalation
 * TERMs it at the readiness deadline (the TERM-death path). */
static int case_readiness_timeout_fn(const char *mode)
{
    char *argv[4];
    char report[1024];
    dealpg4_outer_result view;
    uint64_t before;
    uint64_t after;
    int status;
    int trap_term = (mode != NULL && strcmp(mode, "1") == 0);

    argv[0] = (char *)g_suite_argv0;
    argv[1] = (char *)"--final-hang";
    argv[2] = (char *)mode;
    argv[3] = NULL;

    before = dealpg4_now_ms();
    status = core_with_spawn(&READY_LIMITS, argv, NULL, report,
                             sizeof report);
    after = dealpg4_now_ms();
    memset(&view, 0, sizeof view);
    dealpg4_outer_last_result(&view);

    CHECK(status == DEALPG4_OUTER_EXIT_GATE_FAILURE);
    CHECK(has_token(&view, "READINESS_TIMEOUT"));
    CHECK(view.readiness_timeout_fired == 1);
    CHECK(view.readiness_fired == 1);
    /* COORD_READY was verified: the group is the pipe-published
     * cross-checked identity. */
    CHECK(view.ready_verified == 1);
    CHECK(view.coordinator_pgid == view.coordinator_pid);
    CHECK(view.coordinator_sid == view.coordinator_pid);
    /* The immediate full D8 escalation: step-1 liveness check on the
     * verified group, TERM -pgid at the readiness deadline. */
    CHECK(view.escalation_term_issued == 1);
    CHECK(view.escalation_term_sent == 1);
    CHECK(view.escalation_group_scope == 1);
    CHECK(view.group_liveness_checked == 1);
    CHECK(view.escalation_term_ms >= 400);
    CHECK(view.escalation_term_ms < 1500);
    CHECK(view.coordinator_reaped == 1);
    CHECK(view.proof_passed == 1);
    CHECK(view.proof_group_clean == 1);
    CHECK(view.proof_reap_echild == 1);
    CHECK(view.proof_streams_eof == 1);
    if (trap_term) {
        /* TERM trapped: grace -> the re-verified KILL -pgid reaps
         * the coordinator; the proof still completes inside the
         * reserve. */
        CHECK(view.escalation_kill_issued == 1);
        CHECK(view.escalation_kill_sent == 1);
        CHECK(view.escalation_kill_ms
              >= view.escalation_term_ms + 1900);
        CHECK(view.coordinator_si_code == CLD_KILLED);
        CHECK(view.coordinator_si_status == SIGKILL);
        CHECK(after - before < 5000);
    } else {
        /* The default TERM disposition: the TERM-death path before
         * the grace expiry — no KILL step ran. */
        CHECK(view.escalation_kill_issued == 0);
        CHECK(view.escalation_kill_sent == 0);
        CHECK(view.coordinator_si_code == CLD_KILLED);
        CHECK(view.coordinator_si_status == SIGTERM);
        CHECK(after - before < 3000);
    }
    CHECK(strstr(report, "OUTER token READINESS_TIMEOUT\n") != NULL);

    check_no_children();
    CHECK(outer_socket_path_count("build") == 0);
    return (g_failures > 0) ? 1 : 0;
}

static int case_readiness_term_fn(void)
{
    return case_readiness_timeout_fn("0");
}

static int case_readiness_kill_fn(void)
{
    return case_readiness_timeout_fn("1");
}

/* Group 5: COORDINATOR_STARTUP_FAILED (exec-fail) — by-pid
 * termination with steps 1-2 skipped; the unverified group identity
 * is never used. */
static int case_exec_fail_fn(void)
{
    char *argv[] = {(char *)"/nonexistent/dealpg4-final-helper", NULL};
    char report[1024];
    dealpg4_outer_result view;
    uint64_t before;
    uint64_t after;
    int status;

    before = dealpg4_now_ms();
    status = core_with_spawn(&READY_LIMITS, argv, NULL, report,
                             sizeof report);
    after = dealpg4_now_ms();
    memset(&view, 0, sizeof view);
    dealpg4_outer_last_result(&view);

    CHECK(status == DEALPG4_OUTER_EXIT_GATE_FAILURE);
    CHECK(has_token(&view, "COORDINATOR_STARTUP_FAILED"));
    CHECK(after - before < 6000);
    CHECK(view.coord_exec_failed == 1);
    CHECK(view.ready_verified == 0);
    CHECK(view.coordinator_pgid == 0); /* never verified */
    CHECK(view.coordinator_sid == 0);
    /* No group liveness check against the unverified pgid is ever
     * attempted; the by-pid scope is the only escalation form. */
    CHECK(view.group_liveness_checked == 0);
    CHECK(view.escalation_group_scope == 0);
    CHECK(view.coordinator_reaped == 1);
    CHECK(view.coordinator_si_code == CLD_EXITED);
    CHECK(view.coordinator_si_status == 127);
    /* The unverified-group discharge: reap-to-ECHILD +
     * adopted-descendant scan + stream EOF hold. */
    CHECK(view.proof_passed == 1);
    CHECK(view.proof_reap_echild == 1);
    CHECK(view.proof_adopted_clean == 1);
    CHECK(view.proof_group_clean == 1);
    CHECK(view.proof_streams_eof == 1);

    check_no_children();
    CHECK(outer_socket_path_count("build") == 0);
    return (g_failures > 0) ? 1 : 0;
}

/* Group 6a: COORDINATOR_HANG — the post-DONE hang is escalated only
 * at the pinned escalation deadline, never before DONE. */
static int case_postdone_hang_fn(void)
{
    char report[1024];
    dealpg4_outer_result view;
    uint64_t before;
    uint64_t after;
    int status;

    before = dealpg4_now_ms();
    run_final_case(&HANG_LIMITS, "postdone-hang", report, sizeof report,
                   &status, &view);
    after = dealpg4_now_ms();

    CHECK(status == DEALPG4_OUTER_EXIT_GATE_FAILURE);
    CHECK(has_token(&view, "COORDINATOR_HANG"));
    /* DONE first, the escalation only at the pinned deadline
     * (totalDeadline - killAndProofReserveMs = T0o + 25000). */
    CHECK(view.done_queued == 1);
    CHECK(view.done_clean == 1);
    CHECK(view.done_ms >= 19500);
    CHECK(view.escalation_term_issued == 1);
    CHECK(view.escalation_term_ms >= view.done_ms);
    CHECK(view.escalation_term_ms >= 24500);
    CHECK(view.escalation_term_ms < 26500);
    CHECK(view.escalation_term_sent == 1);
    CHECK(view.escalation_group_scope == 1);
    CHECK(view.group_liveness_checked == 1);
    /* TERM -pgid trapped -> grace -> the re-verified KILL -pgid. */
    CHECK(view.escalation_kill_issued == 1);
    CHECK(view.escalation_kill_sent == 1);
    CHECK(view.escalation_kill_ms
          >= view.escalation_term_ms + 1900);
    CHECK(view.coordinator_reaped == 1);
    CHECK(view.coordinator_si_code == CLD_KILLED);
    CHECK(view.coordinator_si_status == SIGKILL);
    CHECK(view.total_fired == 0);
    CHECK(view.proof_passed == 1);
    CHECK(after - before >= 26500);
    CHECK(after - before < 29500);

    check_no_children();
    CHECK(outer_socket_path_count("build") == 0);
    return (g_failures > 0) ? 1 : 0;
}

/* Group 6b: the D8 precondition deferral — the escalation deadline
 * passes while a record is still live; the escalation starts the
 * moment the record turns terminal (the TERM timestamp is never
 * before the record's terminality). */
static int case_deferral_fn(void)
{
    char report[1024];
    dealpg4_outer_result view;
    dealpg4_outer_record_view rv;
    uint64_t before;
    uint64_t after;
    int status;

    before = dealpg4_now_ms();
    run_final_case(&DEFER_LIMITS, "defer", report, sizeof report,
                   &status, &view);
    after = dealpg4_now_ms();

    CHECK(status == DEALPG4_OUTER_EXIT_GATE_FAILURE);
    CHECK(has_token(&view, "COORDINATOR_HANG"));
    CHECK(view.records_clean == 1);
    CHECK(view.records_live == 0);
    /* The run ended at the TERM death (~17.4 s) — before the cutoff
     * (T0o + 18500), so no DONE was ever queued. */
    CHECK(view.done_queued == 0);
    /* The escalation fired only once the record was terminal — the
     * TERM timestamp is never before the record's terminality (the
     * catch-up starts at the record's CLEAN transition, past the
     * T0o + 17000 pinned escalation deadline). */
    CHECK(view.escalation_term_issued == 1);
    CHECK(view.escalation_term_ms >= 17000);
    CHECK(view.escalation_term_ms < 18000);
    CHECK(dealpg4_outer_registry_record(0, &rv) == 0);
    CHECK(rv.state == DEALPG4_OUTER_REC_CLEAN);
    CHECK(rv.clean_final == 1);
    CHECK(rv.history_count == 3);
    CHECK(rv.history[2].state == DEALPG4_OUTER_REC_CLEAN);
    CHECK(view.escalation_term_ms
          >= rv.history[2].at_ms - view.t0o);
    CHECK(view.escalation_term_sent == 1);
    CHECK(view.escalation_group_scope == 1);
    CHECK(view.group_liveness_checked == 1);
    /* The TERM-death path: the peer died before the grace expiry, so
     * no KILL step ran. */
    CHECK(view.escalation_kill_issued == 0);
    CHECK(view.escalation_kill_sent == 0);
    CHECK(view.coordinator_reaped == 1);
    CHECK(view.coordinator_si_code == CLD_KILLED);
    CHECK(view.coordinator_si_status == SIGTERM);
    CHECK(view.total_fired == 0);
    CHECK(view.proof_passed == 1);
    CHECK(after - before < 18500);

    check_no_children();
    CHECK(outer_socket_path_count("build") == 0);
    return (g_failures > 0) ? 1 : 0;
}

/* Group 7: the exit-status matrix (2 usage / 3 CONFIG_INVALID / 4
 * capability refusal; the 0/1 classes are asserted by groups 1 and
 * 3). */
static int case_exit_matrix_fn(void)
{
    char *usage_argv[] = {(char *)"launcher", (char *)"outer", NULL};
    char *coord_argv[] = {(char *)"/bin/true", NULL};
    dealpg4_fi_script_fail fails[1];
    dealpg4_fi_script script;
    static const int final_fail_sites[] = {
        FI_OUTER_SUBREAPER, FI_OUTER_TIMERFD, FI_OUTER_SIGNALFD,
        FI_OUTER_NONCE, FI_OUTER_PIPE, FI_COORD_READY_MISMATCH,
        FI_OUTER_BIND, FI_OUTER_SOCKETPAIR, FI_OUTER_FORK,
        FI_OUTER_ENTRY_NONCE
    };
    static const dealpg4_fi_catalog catalog = {
        NULL, 0, final_fail_sites, 10, NULL, 0, NULL, 0
    };
    int devnull;
    int status;

    /* 2: usage (bad argv shape, no fork, no socket). */
    status = dealpg4_outer_entry(2, usage_argv);
    CHECK(status == 2);

    /* 3: CONFIG_INVALID (limits ordering violation). */
    devnull = open("/dev/null", O_WRONLY);
    CHECK(devnull >= 0);
    status = dealpg4_outer_core(&INVALID_LIMITS, NONCE, coord_argv,
                                "build", devnull, NULL);
    close(devnull);
    CHECK(status == DEALPG4_EXIT_CONFIG_INVALID);

    /* 4: capability-class refusal (the subreaper prctl fails). */
    fails[0].site = FI_OUTER_SUBREAPER;
    fails[0].value = EINVAL;
    fails[0].oneshot = 1;
    script.delays = NULL;
    script.ndelays = 0;
    script.fails = fails;
    script.nfails = 1;
    script.congests = NULL;
    script.ncongests = 0;
    CHECK(dealpg4_fi_install_overrides(&script, &catalog) == 0);
    devnull = open("/dev/null", O_WRONLY);
    CHECK(devnull >= 0);
    status = dealpg4_outer_core(&READY_LIMITS, NONCE, coord_argv,
                                "build", devnull, NULL);
    close(devnull);
    dealpg4_fi_restore_defaults();
    CHECK(status == DEALPG4_EXIT_CAPABILITY_MISSING);
    CHECK(dealpg4_fi_hooks.fail(FI_OUTER_SUBREAPER) == 0);

    check_no_children();
    CHECK(outer_socket_path_count("build") == 0);
    return (g_failures > 0) ? 1 : 0;
}

/* Group 8: integration (T1-T7) through the real mode entry — a real
 * INVOKE over the production serve surface, the clean pre-cutoff
 * exit, the record line and the counts line in the report. */
static int case_integration_fn(void)
{
    char *launcher_argv[] = {
        (char *)"./deal-process-launcher-linux-x86_64",
        (char *)"outer", (char *)NONCE, (char *)"--",
        (char *)g_suite_argv0, (char *)"--final-peer",
        (char *)"real-invoke", NULL};
    int pipefd[2];
    pid_t pid;
    char out[8192];
    size_t off = 0;
    uint64_t deadline;
    uint64_t start;
    int st = -1;
    int reaped = 0;

    CHECK(pipe(pipefd) == 0);
    pid = fork();
    CHECK(pid >= 0);
    if (pid == 0) {
        close(pipefd[0]);
        if (dup2(pipefd[1], 1) == -1)
            _exit(125);
        close(pipefd[1]);
        execv(launcher_argv[0], launcher_argv);
        fprintf(stderr, "INTEGRATION FAIL execv errno=%d\n", errno);
        _exit(126);
    }
    close(pipefd[1]);
    start = dealpg4_now_ms();
    deadline = start + 60000;
    for (;;) {
        struct pollfd pfd;
        int rc;

        pfd.fd = pipefd[0];
        pfd.events = POLLIN;
        pfd.revents = 0;
        rc = poll(&pfd, 1, 100);
        if (rc < 0 && errno == EINTR)
            continue;
        if (rc > 0) {
            ssize_t r = read(pipefd[0], out + off, sizeof out - off - 1);

            if (r > 0) {
                off += (size_t)r;
                continue;
            }
            if (r < 0 && errno == EINTR)
                continue;
            break; /* EOF: the launcher exited */
        }
        if (dealpg4_now_ms() > deadline) {
            /* The embedded-limits run must never hang the suite: a
             * regression is killed and fails the case. */
            kill(pid, SIGKILL);
            out[off] = '\0';
            close(pipefd[0]);
            CHECK(0 && "integration case exceeded the 60 s bound");
            return (g_failures > 0) ? 1 : 0;
        }
        {
            pid_t w = waitpid(pid, &st, WNOHANG);

            if (w == pid) {
                reaped = 1;
                break;
            }
        }
    }
    out[off] = '\0';
    close(pipefd[0]);
    if (!reaped)
        CHECK(waitpid(pid, &st, 0) == pid);
    CHECK(WIFEXITED(st) && WEXITSTATUS(st) == 0);
    CHECK(dealpg4_now_ms() - start < 60000);
    if (!(WIFEXITED(st) && WEXITSTATUS(st) == 0))
        fprintf(stderr, "DIAG integration st=0x%x out[%.800s]\n", st,
                out);

    /* The real mode entry: the clean exit, the record's terminal
     * state, the proof result, the reap/adoption counts, no gate
     * token, and the socket path unlinked. */
    CHECK(strstr(out, "OUTER final 0 ") != NULL);
    CHECK(strstr(out, "OUTER record 1 CLEAN tag success\n") != NULL);
    CHECK(strstr(out, "OUTER proof ok\n") != NULL);
    CHECK(strstr(out, "OUTER counts reaped=") != NULL);
    CHECK(strstr(out, "OUTER token ") == NULL);

    check_no_children();
    CHECK(outer_socket_path_count("build") == 0);
    return (g_failures > 0) ? 1 : 0;
}

/* === Main ============================================================= */

static void run_case_child(const char *name, outer_test_fn fn)
{
    char errbuf[1024];
    int status;

    errbuf[0] = '\0';
    if (run_capture_child(fn, errbuf, sizeof errbuf, &status) != 0) {
        fprintf(stderr, "FAIL: helper child machinery broke\n");
        g_failures++;
        return;
    }
    gate_group(name, errbuf, status);
}

int main(int argc, char **argv)
{
    g_suite_argv0 = argv[0];
    /* The D3 serve-surface argv[0] capture (the outer's own argv[0]):
     * the spawn-composition INVOKEs fork the serve surface under the
     * suite binary's argv[0]. */
    dealpg4_outer_note_process_argv0(argv[0]);
    if (argc >= 2 && strcmp(argv[1], "--final-peer") == 0)
        return final_peer_entry(argc, argv);
    if (argc >= 2 && strcmp(argv[1], "--final-hang") == 0)
        return final_hang_entry(argc, argv);

    /* Group 1: full success (~17.2 s). */
    run_case_child("full success", case_success_fn);
    /* Group 2: clean-exit variants (~17.2 s for the post-DONE
     * no-BYE run; the pre-cutoff and zero-record runs are ~1 s). */
    run_case_child("pre-cutoff close", case_precutoff_close_fn);
    run_case_child("zero-record", case_zero_record_fn);
    run_case_child("exit 0 without BYE after DONE",
                   case_nobye_after_done_fn);
    /* Group 3: COORDINATOR_LOST. */
    run_case_child("COORDINATOR_LOST exit 0", case_lost_exit0_fn);
    run_case_child("COORDINATOR_LOST signal", case_lost_kill_fn);
    /* Group 4: READINESS_TIMEOUT. */
    run_case_child("READINESS_TIMEOUT TERM", case_readiness_term_fn);
    run_case_child("READINESS_TIMEOUT KILL", case_readiness_kill_fn);
    /* Group 5: COORDINATOR_STARTUP_FAILED. */
    run_case_child("COORDINATOR_STARTUP_FAILED", case_exec_fail_fn);
    /* Group 6: COORDINATOR_HANG (~27.2 s + ~18.4 s). */
    run_case_child("post-DONE hang", case_postdone_hang_fn);
    run_case_child("D8 precondition deferral", case_deferral_fn);
    /* Group 7: the exit-status matrix. */
    run_case_child("exit-status matrix", case_exit_matrix_fn);
    /* Group 8: the real-mode integration (~3 s). */
    run_case_child("real-mode integration", case_integration_fn);

    if (g_failures > 0) {
        fprintf(stderr, "outer-final-tests: %d/%d checks failed\n",
                g_failures, g_checks);
        return 1;
    }
    printf("outer-final-tests: %d checks passed\n", g_checks);
    return 0;
}
