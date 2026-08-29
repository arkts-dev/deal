/*
 * DEALPG4 outer registry / live-phase / DONE component acceptance
 * suite (tools/test/outer-registry-tests.c).
 *
 * The component-level cases of ISSUE-0296 Verification, run against
 * tools/src/outer.c + monotonic.c + protocol.c + fi.c + selftest.c +
 * drain.c compiled with the pinned flags (tools/test/
 * run-outer-registry-tests.sh). Permanent and re-runnable; lives
 * outside the tools/src/ build glob so the pinned artifact build is
 * unchanged. The suite drives the real core (T1's preamble, loop,
 * write-side discipline, and exit-status mapping + T2's coordinator
 * machinery + T3's broker socket/auth channel) with scripted
 * coordinator argv (the suite binary re-execs itself as the broker
 * peer), scaled OuterLimits, and in-process spawn-seam compositions —
 * real socket/bind/listen/accept/SO_PEERCRED/nonce/registry machinery
 * only, no canned frame responses.
 *
 * Case groups:
 *  1. INVOKE semantic split over the live broker peer: a well-formed
 *     INVOKE with an argc/argv-count mismatch, an empty argv (argc 0
 *     or argc > 0 with no fields), an empty cwd, a NUL-containing
 *     cwd, or an invalid-UTF-8 cwd is answered REJECT <id> <tag>
 *     MALFORMED_INVOKE with an immediate terminal FAILED record — the
 *     spawn composition records no fork call, the record has no
 *     channels and no pid, and the broker stays open; framing-level
 *     INVOKE defects (non-hex argv, odd-length hex, field-count
 *     violations, CR, an oversize unterminated stream) close the
 *     broker with PROTOCOL_ERROR.
 *  2. Pre-fork rejection battery (scaled OuterLimits through the
 *     in-process core): T-floor (scaled nestedStopMs so T < 15000) ->
 *     REJECT BUDGET_EXHAUSTED + terminal FAILED record, no fork;
 *     REGISTRY_FULL at 128 live records -> REJECT REGISTRY_FULL +
 *     terminal record, no fork for the 129th; FORK_FAILED via a
 *     spawn-seam composition whose fork_nested returns -1, via a
 *     scripted socketpair failure (FI_OUTER_SOCKETPAIR), and via the
 *     production fork_nested with FI_OUTER_FORK armed; NONCE_FAILED
 *     via FI_OUTER_INVOKE_NONCE (the production nonce path stays
 *     unchanged when the site is unscripted).
 *  3. Register-before-fork: a checker composition runs inside
 *     fork_nested and proves the record exists (inserted) with the
 *     exact fork parameters (nonce, budget_t == deadline_ms within
 *     [15000, 45000], invocation_id, control fd, tag, FORKING state,
 *     no pid attached yet) and the exact serve argv surface
 *     ([self, serve, decodedCwd, --, target...]); INVOKED <id> <tag>
 *     is received with the record's id; a broker EOF injected
 *     mid-INVOKE still registers + forks, then the caller-loss mark
 *     applies — never the reverse.
 *  4. ACK/CANCEL record-level validation over a live peer: an ACK
 *     with an unknown invocationId, a nonce mismatch, or naming a
 *     live record in FORKING (including the matching-nonce FORKING
 *     case — the outer-side state check) is answered REJECT <id>
 *     <tag|-> AUTH_FAILED — broker open, record untouched, no relay;
 *     a CANCEL with a wrong nonce, an unknown id, or a terminal
 *     record is answered REJECT <id> <tag|-> CANCEL_AUTH_FAILED —
 *     broker open, record untouched, every other live record
 *     unaffected; a validated CANCEL produces no answer and leaves
 *     the record untouched (the application is the channel-machine
 *     child's); BYE in BROKER_LIVE is PROTOCOL_ERROR.
 *  5. The INVOKE cutoff and the cutoff-anchored DONE emission
 *     trigger (scaled nestedStopMs/cleanupReserveMs): with a
 *     fully-terminal registry from pre-fork rejection records, no
 *     DONE is ever queued before the cutoff (the peer receives the
 *     REJECT answers, the live phase stays open for the repeated
 *     dispatch, and DONE arrives only after the cutoff with the
 *     REJECT answers preceding it in the byte stream); DONE failed is
 *     queued exactly once; a post-DONE INVOKE receives REJECT <id>
 *     <tag> BUDGET_EXHAUSTED + immediate terminal FAILED record over
 *     the still-open broker — never PROTOCOL_ERROR, never a close,
 *     no second DONE — and BYE is then accepted (EOF after BYE); a
 *     post-cutoff pre-DONE INVOKE gets the same deterministic answer
 *     with DONE queued right behind it; with an empty registry at
 *     the cutoff no DONE is ever queued and the peer's close + exit
 *     0 is processed as the clean exit.
 *  6. Integration (T1+T2+T3+T4): (a) the real committed launcher's
 *     outer mode entry runs a scripted coordinator that completes the
 *     handshake, sends a well-formed INVOKE with an argc/argv-count
 *     mismatch, receives REJECT <id> <tag> MALFORMED_INVOKE over the
 *     still-open channel, closes, and exits 0 — the outer exits
 *     nonzero (the FAILED record) with the final report listing the
 *     record and the final proof passing; (b) a zero-record peer
 *     through the real mode entry observes no DONE and the clean
 *     exit 0.
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
#include <limits.h>
#include <poll.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
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
#define BAD_NONCE "ffffffffffffffffffffffffffffffff"

/* Scaled OuterLimits per case group:
 *  - FAST: rejection / DONE-trigger cases (nestedStop 2000 — the
 *    cutoff at T0o + 2000, cleanup 7000 so the pinned escalation
 *    deadline total - killAndProofReserveMs = T0o + 4000 lands after
 *    the ~2.6 s coordinator exit of the DONE scenarios — a healthy
 *    post-DONE coordinator is never signaled);
 *  - LIVE: live-record cases (nestedStop 17000 — the delivered
 *    T = min(45000, 17000 - elapsed) stays >= 15000 for the first
 *    ~2 s; total at T0o + 18000);
 *  - FLOOR: the T-floor case (nestedStop 8000 — T < 15000 for every
 *    pre-cutoff INVOKE). */
static const OuterLimits FAST_LIMITS = {9000, 500, 2000, 7000, 200};
static const OuterLimits LIVE_LIMITS = {18000, 500, 17000, 1000, 200};
static const OuterLimits FLOOR_LIMITS = {10000, 500, 8000, 2000, 200};

/* A well-formed valid INVOKE: tag `tag`, cwd "/" (2f), argc 1, argv
 * ["true"]. */
#define VALID_INVOKE "DEALPG4 INVOKE tag 2f 1 74727565\n"
/* A semantically malformed INVOKE: argc 2, one argv field. */
#define MALFORMED_ARGC_INVOKE "DEALPG4 INVOKE tag 2f 2 74727565\n"

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

/* No-child assertion. */
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

/* === Spawn-seam compositions (in-process) ============================== */

#define REC_MODE_FORK     0 /* record + fake pid */
#define REC_MODE_CHECK    1 /* fork-time deep checks */
#define REC_MODE_FAIL     2 /* return -1 (fork failure) */
#define REC_MODE_NONCE    3 /* record + publish id/nonce lines */

typedef struct spawn_recorder {
    int mode;
    int fork_calls;
    int64_t last_id;
    char last_nonce[DEALPG4_NONCE_HEX_CHARS + 1];
    int64_t last_budget;
    int last_control_fd;
    int fail_errno;
    /* Captured serve argv surface. */
    char serve_self[PATH_MAX];
    char serve_cwd[PATH_MAX];
    char serve_arg0[256];
    int serve_argc;
} spawn_recorder;

static int recorder_fork_nested(const dealpg4_outer_spawn *self,
                                char *const serve_argv[],
                                const char nonce[33],
                                int64_t budget_t, int64_t invocation_id,
                                int child_control_fd)
{
    spawn_recorder *rec = (spawn_recorder *)self->opaque;
    int argc = 0;

    rec->fork_calls++;
    rec->last_id = invocation_id;
    memcpy(rec->last_nonce, nonce, sizeof rec->last_nonce);
    rec->last_budget = budget_t;
    rec->last_control_fd = child_control_fd;
    if (serve_argv != NULL) {
        while (serve_argv[argc] != NULL)
            argc++;
        if (serve_argv[0] != NULL)
            snprintf(rec->serve_self, sizeof rec->serve_self, "%s",
                     serve_argv[0]);
        if (argc >= 3 && serve_argv[2] != NULL)
            snprintf(rec->serve_cwd, sizeof rec->serve_cwd, "%s",
                     serve_argv[2]);
        if (argc >= 5 && serve_argv[4] != NULL)
            snprintf(rec->serve_arg0, sizeof rec->serve_arg0, "%s",
                     serve_argv[4]);
    }
    rec->serve_argc = argc;

    if (rec->mode == REC_MODE_FAIL) {
        errno = rec->fail_errno;
        return -1;
    }
    if (rec->mode == REC_MODE_CHECK) {
        /* Register-before-fork (D3): the record exists — inserted
         * with its nonce, deadline, tag, and FORKING state — before
         * the fork syscall, with no pid attached yet and the control
         * fd not yet recorded. */
        size_t n = dealpg4_outer_registry_count();
        dealpg4_outer_record_view v;
        uint64_t now_fork = dealpg4_now_ms();

        CHECK(n == 1);
        CHECK(dealpg4_outer_registry_record(0, &v) == 0);
        CHECK(v.invocation_id == invocation_id);
        CHECK(strcmp(v.nonce, nonce) == 0);
        CHECK(v.deadline_ms == budget_t);
        CHECK(budget_t >= DEALPG4_PROBE_SELFTEST_LIMIT_MS_FLOOR);
        CHECK(budget_t <= DEALPG4_LAUNCHER_OVERALL_TIMEOUT_MS);
        CHECK(v.deadline_abs_ms >= v.deadline_ms);
        CHECK((uint64_t)v.deadline_abs_ms - (uint64_t)v.deadline_ms
              <= now_fork);
        CHECK((uint64_t)v.deadline_abs_ms - (uint64_t)v.deadline_ms
              + 5000 >= now_fork);
        CHECK(v.state == DEALPG4_OUTER_REC_FORKING);
        CHECK(v.supervisor_pid == -1); /* attached only after fork
                                          returns */
        CHECK(v.control_fd == -1);
        CHECK(v.ack_applied == 0);
        CHECK(strcmp(v.client_tag, "tag") == 0);
        CHECK(v.history_count == 1);
        CHECK(v.history[0].state == DEALPG4_OUTER_REC_FORKING);
        CHECK(child_control_fd >= 0);
        /* The D3 serve surface: [self, serve, decodedCwd, --,
         * target-argv...]. */
        CHECK(argc == 5);
        CHECK(serve_argv[0] != NULL && serve_argv[0][0] != '\0');
        CHECK(serve_argv[1] != NULL
              && strcmp(serve_argv[1], "serve") == 0);
        CHECK(serve_argv[2] != NULL && strcmp(serve_argv[2], "/") == 0);
        CHECK(serve_argv[3] != NULL
              && strcmp(serve_argv[3], "--") == 0);
        CHECK(serve_argv[4] != NULL
              && strcmp(serve_argv[4], "true") == 0);
        CHECK(serve_argv[5] == NULL);
    }
    if (rec->mode == REC_MODE_NONCE) {
        /* Publish the invocation id + nonce for the coordinator peer
         * (the peer is a separate process; the composition runs
         * in-process) — the ACK/CANCEL validation scenarios need the
         * exact registry nonce. */
        FILE *f = fopen("build/.outer-rec-nonce", "a");

        if (f != NULL) {
            fprintf(f, "%lld %s\n", (long long)invocation_id, nonce);
            fclose(f);
        }
    }
    return 999999; /* a non-child pid: nothing signals or reaps it at
                      this stage (the fallback child owns that
                      machinery) */
}

static dealpg4_outer_spawn make_spawn(spawn_recorder *rec)
{
    dealpg4_outer_spawn sp;

    sp.fork_nested = recorder_fork_nested;
    sp.opaque = rec;
    return sp;
}

/* === Scripted peer machinery (the suite re-execs as the
 * coordinator) ========================================================= */

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
 * -2 = timeout, -3/-4/-5 = machinery failure. */
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
            return -4;
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
        if (errno == ECONNRESET) {
            buf[off] = '\0';
            return -1;
        }
        if (errno == EINTR)
            continue;
        return -5;
    }
}

/* The ok handshake sequence. */
static int peer_do_handshake(int fd, const char *nonce)
{
    char line[256];
    char cmd[128];
    int n;

    n = snprintf(cmd, sizeof cmd, "DEALPG4 HELLO %s\n", nonce);
    if (n <= 0 || (size_t)n >= sizeof cmd)
        return 1;
    if (peer_write_all(fd, cmd, (size_t)n) != 0)
        return 1;
    if (peer_read_line(fd, line, sizeof line, 3000) != 0)
        return 1;
    if (strcmp(line, "DEALPG4 HELLO_OK 4 31\n") != 0)
        return 1;
    n = snprintf(cmd, sizeof cmd, "DEALPG4 FEATURE_READY %s\n", nonce);
    if (n <= 0 || (size_t)n >= sizeof cmd)
        return 1;
    if (peer_write_all(fd, cmd, (size_t)n) != 0)
        return 1;
    if (peer_read_line(fd, line, sizeof line, 3000) != 0)
        return 1;
    if (memcmp(line, "DEALPG4 READY_ACK ", 18) != 0
        || strlen(line) != 18 + 32 + 1
        || memcmp(line + 18, nonce, 32) != 0 || line[18 + 32] != '\n')
        return 1;
    return 0;
}

/* Parse "DEALPG4 INVOKED <id> <tag>\n": 0 on success. */
static int peer_parse_invoked(const char *line, int64_t *id,
                              char tag[64])
{
    long long v = 0;

    if (sscanf(line, "DEALPG4 INVOKED %lld %63s", &v, tag) != 2)
        return 1;
    if (line[strlen(line) - 1] != '\n')
        return 1;
    *id = (int64_t)v;
    return 0;
}

/* Parse "DEALPG4 REJECT <id> <tag> <token>\n": 0 on success. */
static int peer_parse_reject(const char *line, int64_t *id,
                             char tag[64], char token[64])
{
    long long v = 0;

    if (sscanf(line, "DEALPG4 REJECT %lld %63s %63s", &v, tag, token)
        != 3)
        return 1;
    if (line[strlen(line) - 1] != '\n')
        return 1;
    *id = (int64_t)v;
    return 0;
}

/* One scripted coordinator scenario. Returns the exit status (the
 * peer's own assertions). */
static int registry_peer_main(const char *scenario, const char *arg)
{
    const char *path = getenv("DEALPG4_BROKER_PATH");
    const char *nonce = getenv("DEALPG4_NONCE");
    char line[512];
    char cmd[256];
    int fd;
    int n;

    if (path == NULL || nonce == NULL) {
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

    if (strcmp(scenario, "ok-no-done") == 0) {
        /* Zero records: no DONE is ever queued on an empty registry
         * — nothing is pending right after the handshake (a bounded
         * read must time out); the clean path is the peer-initiated
         * close + exit 0 (the outer observes EOF). */
        if (peer_read_line(fd, line, sizeof line, 400) != -2) {
            fprintf(stderr, "PEER FAIL unexpected-line %s\n", line);
            return 1;
        }
        close(fd);
        printf("PEER ok-no-done\n");
        fflush(stdout);
        return 0;
    }

    if (strcmp(scenario, "reject") == 0) {
        /* A well-formed INVOKE with an argc/argv-count mismatch:
         * record-level REJECT <id> <tag> MALFORMED_INVOKE over the
         * open channel. */
        int64_t id = 0;
        char tag[64];
        char token[64];

        if (peer_write_all(fd, MALFORMED_ARGC_INVOKE,
                           strlen(MALFORMED_ARGC_INVOKE)) != 0
            || peer_read_line(fd, line, sizeof line, 3000) != 0) {
            fprintf(stderr, "PEER FAIL reject-read\n");
            return 1;
        }
        if (peer_parse_reject(line, &id, tag, token) != 0
            || strcmp(tag, "tag") != 0
            || strcmp(token, "MALFORMED_INVOKE") != 0 || id < 1) {
            fprintf(stderr, "PEER FAIL reject-shape %s", line);
            return 1;
        }
        /* The channel stays open after the record-level rejection:
         * a second malformed INVOKE gets the same deterministic
         * answer (the live phase stays open for every pre-cutoff
         * INVOKE the objective accepts). */
        if (peer_write_all(fd, MALFORMED_ARGC_INVOKE,
                           strlen(MALFORMED_ARGC_INVOKE)) != 0
            || peer_read_line(fd, line, sizeof line, 3000) != 0) {
            fprintf(stderr, "PEER FAIL reject-read-2\n");
            return 1;
        }
        if (peer_parse_reject(line, &id, tag, token) != 0
            || strcmp(token, "MALFORMED_INVOKE") != 0) {
            fprintf(stderr, "PEER FAIL reject-shape-2 %s", line);
            return 1;
        }
        close(fd);
        printf("PEER rejected\n");
        fflush(stdout);
        return 0;
    }

    if (strcmp(scenario, "malformed") == 0) {
        /* One scripted semantically-malformed INVOKE (arg = the
         * record text): REJECT <id> <tag> MALFORMED_INVOKE. */
        int64_t id = 0;
        char tag[64];
        char token[64];

        if (arg == NULL)
            return 1;
        if (peer_write_all(fd, arg, strlen(arg)) != 0
            || peer_read_line(fd, line, sizeof line, 3000) != 0) {
            fprintf(stderr, "PEER FAIL reject-read\n");
            return 1;
        }
        if (peer_parse_reject(line, &id, tag, token) != 0
            || strcmp(token, "MALFORMED_INVOKE") != 0 || id < 1) {
            fprintf(stderr, "PEER FAIL malformed-shape %s", line);
            return 1;
        }
        close(fd);
        printf("PEER malformed\n");
        fflush(stdout);
        return 0;
    }

    if (strcmp(scenario, "framing") == 0) {
        /* One scripted framing-level INVOKE defect (arg = the record
         * text): PROTOCOL_ERROR closes the broker. */
        if (arg == NULL
            || peer_write_all(fd, arg, strlen(arg)) != 0
            || peer_read_line(fd, line, sizeof line, 3000) != -1) {
            fprintf(stderr, "PEER FAIL no-eof\n");
            return 1;
        }
        close(fd);
        printf("PEER framing-eof\n");
        fflush(stdout);
        return 0;
    }

    if (strcmp(scenario, "framing-big") == 0) {
        /* An oversize unterminated INVOKE stream: the outer closes at
         * the read-side cap while the peer is still writing (EPIPE on
         * the write is the expected outcome). */
        static char big[DEALPG4_MAX_LINE_INVOKE_BYTES + 70000];

        memset(big, 'a', sizeof big);
        (void)peer_write_all(fd, big, sizeof big);
        if (peer_read_line(fd, line, sizeof line, 3000) != -1) {
            fprintf(stderr, "PEER FAIL big-no-eof\n");
            return 1;
        }
        close(fd);
        printf("PEER framing-big-eof\n");
        fflush(stdout);
        return 0;
    }

    if (strcmp(scenario, "floor") == 0) {
        /* The T-floor: a valid INVOKE under scaled limits with
         * T < 15000 -> REJECT BUDGET_EXHAUSTED, no fork. */
        int64_t id = 0;
        char tag[64];
        char token[64];

        if (peer_write_all(fd, VALID_INVOKE, strlen(VALID_INVOKE)) != 0
            || peer_read_line(fd, line, sizeof line, 3000) != 0) {
            fprintf(stderr, "PEER FAIL floor-read\n");
            return 1;
        }
        if (peer_parse_reject(line, &id, tag, token) != 0
            || strcmp(tag, "tag") != 0
            || strcmp(token, "BUDGET_EXHAUSTED") != 0 || id < 1) {
            fprintf(stderr, "PEER FAIL floor-shape %s", line);
            return 1;
        }
        close(fd);
        printf("PEER floor\n");
        fflush(stdout);
        return 0;
    }

    if (strcmp(scenario, "reject-token") == 0) {
        /* One scripted pre-fork rejection (arg = the expected
         * token): a valid INVOKE answered REJECT <id> <tag> <token>
         * with an immediate terminal FAILED record. */
        int64_t id = 0;
        char tag[64];
        char token[64];

        if (peer_write_all(fd, VALID_INVOKE, strlen(VALID_INVOKE)) != 0
            || peer_read_line(fd, line, sizeof line, 3000) != 0) {
            fprintf(stderr, "PEER FAIL token-read\n");
            return 1;
        }
        if (peer_parse_reject(line, &id, tag, token) != 0
            || strcmp(tag, "tag") != 0 || strcmp(token, arg) != 0) {
            fprintf(stderr, "PEER FAIL token-shape %s want %s\n", line,
                    arg != NULL ? arg : "?");
            return 1;
        }
        close(fd);
        printf("PEER rejected-%s\n", arg != NULL ? arg : "?");
        fflush(stdout);
        return 0;
    }

    if (strcmp(scenario, "invoke-ok") == 0) {
        /* A valid INVOKE: INVOKED <id> <tag> after registration. */
        int64_t id = 0;
        char tag[64];

        if (peer_write_all(fd, VALID_INVOKE, strlen(VALID_INVOKE)) != 0
            || peer_read_line(fd, line, sizeof line, 3000) != 0) {
            fprintf(stderr, "PEER FAIL invoked-read\n");
            return 1;
        }
        if (peer_parse_invoked(line, &id, tag) != 0
            || strcmp(tag, "tag") != 0 || id != 1) {
            fprintf(stderr, "PEER FAIL invoked-shape %s", line);
            return 1;
        }
        close(fd);
        printf("PEER invoked %lld\n", (long long)id);
        fflush(stdout);
        return 0;
    }

    if (strcmp(scenario, "invoke-eof") == 0) {
        /* The broker EOF arriving mid-INVOKE: the accepted INVOKE is
         * still registered + forked, then the caller-loss mark
         * applies — never the reverse. The peer sends the INVOKE and
         * closes without reading the answer. */
        if (peer_write_all(fd, VALID_INVOKE, strlen(VALID_INVOKE)) != 0) {
            fprintf(stderr, "PEER FAIL invoke-write\n");
            return 1;
        }
        close(fd);
        printf("PEER closed-mid-invoke\n");
        fflush(stdout);
        return 0;
    }

    if (strcmp(scenario, "full") == 0) {
        /* 128 valid INVOKEs -> 128 INVOKEDs (ids strictly
         * increasing); the 129th -> REJECT REGISTRY_FULL with no
         * fork. */
        int64_t id = 0;
        int64_t prev = 0;
        char tag[64];
        char token[64];
        int i;

        for (i = 0; i < 128; i++) {
            if (peer_write_all(fd, VALID_INVOKE,
                               strlen(VALID_INVOKE)) != 0
                || peer_read_line(fd, line, sizeof line, 3000) != 0) {
                fprintf(stderr, "PEER FAIL full-invoked-%d\n", i);
                return 1;
            }
            if (peer_parse_invoked(line, &id, tag) != 0
                || strcmp(tag, "tag") != 0 || id != prev + 1) {
                fprintf(stderr, "PEER FAIL full-shape-%d %s\n", i,
                        line);
                return 1;
            }
            prev = id;
        }
        if (peer_write_all(fd, VALID_INVOKE, strlen(VALID_INVOKE)) != 0
            || peer_read_line(fd, line, sizeof line, 3000) != 0) {
            fprintf(stderr, "PEER FAIL full-reject-read\n");
            return 1;
        }
        if (peer_parse_reject(line, &id, tag, token) != 0
            || strcmp(token, "REGISTRY_FULL") != 0
            || id != prev + 1) {
            fprintf(stderr, "PEER FAIL full-reject %s\n", line);
            return 1;
        }
        close(fd);
        printf("PEER full\n");
        fflush(stdout);
        return 0;
    }

    if (strcmp(scenario, "ackcancel") == 0) {
        /* Two live records + one terminal rejection record; the
         * ACK/CANCEL record-level validation matrix. */
        int64_t id1 = 0;
        int64_t id2 = 0;
        int64_t rid = 0;
        char tag[64];
        char token[64];
        char nonce1[DEALPG4_NONCE_HEX_CHARS + 1] = "";
        char nonce2[DEALPG4_NONCE_HEX_CHARS + 1] = "";
        uint64_t deadline = dealpg4_now_ms() + 3000;

        if (peer_write_all(fd, VALID_INVOKE, strlen(VALID_INVOKE)) != 0
            || peer_read_line(fd, line, sizeof line, 3000) != 0) {
            fprintf(stderr, "PEER FAIL ac-invoked-1\n");
            return 1;
        }
        if (peer_parse_invoked(line, &id1, tag) != 0) {
            fprintf(stderr, "PEER FAIL ac-shape-1 %s", line);
            return 1;
        }
        if (peer_write_all(fd, VALID_INVOKE, strlen(VALID_INVOKE)) != 0
            || peer_read_line(fd, line, sizeof line, 3000) != 0) {
            fprintf(stderr, "PEER FAIL ac-invoked-2\n");
            return 1;
        }
        if (peer_parse_invoked(line, &id2, tag) != 0) {
            fprintf(stderr, "PEER FAIL ac-shape-2 %s", line);
            return 1;
        }
        /* One terminal record (the MALFORMED_INVOKE rejection). */
        if (peer_write_all(fd, MALFORMED_ARGC_INVOKE,
                           strlen(MALFORMED_ARGC_INVOKE)) != 0
            || peer_read_line(fd, line, sizeof line, 3000) != 0) {
            fprintf(stderr, "PEER FAIL ac-reject-read\n");
            return 1;
        }
        if (peer_parse_reject(line, &rid, tag, token) != 0) {
            fprintf(stderr, "PEER FAIL ac-reject-shape %s", line);
            return 1;
        }
        /* The registry nonces (published by the composition). */
        for (;;) {
            FILE *f = fopen("build/.outer-rec-nonce", "r");

            if (f != NULL) {
                long long fid = 0;
                char fn[DEALPG4_NONCE_HEX_CHARS + 1];

                while (fscanf(f, "%lld %32s", &fid, fn) == 2) {
                    if (fid == id1)
                        snprintf(nonce1, sizeof nonce1, "%s", fn);
                    if (fid == id2)
                        snprintf(nonce2, sizeof nonce2, "%s", fn);
                }
                fclose(f);
            }
            if (nonce1[0] != '\0' && nonce2[0] != '\0')
                break;
            if (dealpg4_now_ms() >= deadline) {
                fprintf(stderr, "PEER FAIL no-nonces\n");
                return 1;
            }
            sleep_ms(10);
        }

        /* ACK with an unknown invocationId (rid + 1: past every
         * registered id): tag "-". */
        {
            int64_t aid = 0;

            n = snprintf(cmd, sizeof cmd, "DEALPG4 ACK %lld %s\n",
                         (long long)(rid + 1), nonce1);
            if (n <= 0 || (size_t)n >= sizeof cmd
                || peer_write_all(fd, cmd, (size_t)n) != 0
                || peer_read_line(fd, line, sizeof line, 3000) != 0
                || peer_parse_reject(line, &aid, tag, token) != 0
                || aid != rid + 1 || strcmp(tag, "-") != 0
                || strcmp(token, "AUTH_FAILED") != 0) {
                fprintf(stderr, "PEER FAIL ack-unknown %s\n", line);
                return 1;
            }
        }
        /* ACK with a nonce mismatch. */
        {
            int64_t aid = 0;

            n = snprintf(cmd, sizeof cmd, "DEALPG4 ACK %lld %s\n",
                         (long long)id1, BAD_NONCE);
            if (n <= 0 || (size_t)n >= sizeof cmd
                || peer_write_all(fd, cmd, (size_t)n) != 0
                || peer_read_line(fd, line, sizeof line, 3000) != 0
                || peer_parse_reject(line, &aid, tag, token) != 0
                || aid != id1 || strcmp(tag, "tag") != 0
                || strcmp(token, "AUTH_FAILED") != 0) {
                fprintf(stderr, "PEER FAIL ack-nonce %s\n", line);
                return 1;
            }
        }
        /* ACK naming a live record in FORKING with the matching
         * nonce: the outer-side state check rejects it (the full
         * parent-D6 condition requires TARGET_PUBLISHED). */
        {
            int64_t aid = 0;

            n = snprintf(cmd, sizeof cmd, "DEALPG4 ACK %lld %s\n",
                         (long long)id1, nonce1);
            if (n <= 0 || (size_t)n >= sizeof cmd
                || peer_write_all(fd, cmd, (size_t)n) != 0
                || peer_read_line(fd, line, sizeof line, 3000) != 0
                || peer_parse_reject(line, &aid, tag, token) != 0
                || aid != id1 || strcmp(tag, "tag") != 0
                || strcmp(token, "AUTH_FAILED") != 0) {
                fprintf(stderr, "PEER FAIL ack-forking %s\n", line);
                return 1;
            }
        }
        /* ACK naming the terminal record (the MALFORMED_INVOKE
         * rejection): a terminal record rejects the ACK at the
         * record level — REJECT <id> <tag> AUTH_FAILED, the broker
         * stays open, the record untouched. */
        {
            int64_t aid = 0;

            n = snprintf(cmd, sizeof cmd, "DEALPG4 ACK %lld %s\n",
                         (long long)rid, nonce1);
            if (n <= 0 || (size_t)n >= sizeof cmd
                || peer_write_all(fd, cmd, (size_t)n) != 0
                || peer_read_line(fd, line, sizeof line, 3000) != 0
                || peer_parse_reject(line, &aid, tag, token) != 0
                || aid != rid || strcmp(tag, "tag") != 0
                || strcmp(token, "AUTH_FAILED") != 0) {
                fprintf(stderr, "PEER FAIL ack-terminal %s\n", line);
                return 1;
            }
        }
        /* CANCEL with a wrong nonce. */
        {
            int64_t cid = 0;

            n = snprintf(cmd, sizeof cmd, "DEALPG4 CANCEL %lld %s\n",
                         (long long)id1, BAD_NONCE);
            if (n <= 0 || (size_t)n >= sizeof cmd
                || peer_write_all(fd, cmd, (size_t)n) != 0
                || peer_read_line(fd, line, sizeof line, 3000) != 0
                || peer_parse_reject(line, &cid, tag, token) != 0
                || cid != id1 || strcmp(tag, "tag") != 0
                || strcmp(token, "CANCEL_AUTH_FAILED") != 0) {
                fprintf(stderr, "PEER FAIL cancel-nonce %s\n", line);
                return 1;
            }
        }
        /* CANCEL with an unknown invocationId (rid + 1). */
        {
            int64_t cid = 0;

            n = snprintf(cmd, sizeof cmd, "DEALPG4 CANCEL %lld %s\n",
                         (long long)(rid + 1), nonce1);
            if (n <= 0 || (size_t)n >= sizeof cmd
                || peer_write_all(fd, cmd, (size_t)n) != 0
                || peer_read_line(fd, line, sizeof line, 3000) != 0
                || peer_parse_reject(line, &cid, tag, token) != 0
                || cid != rid + 1 || strcmp(tag, "-") != 0
                || strcmp(token, "CANCEL_AUTH_FAILED") != 0) {
                fprintf(stderr, "PEER FAIL cancel-unknown %s\n", line);
                return 1;
            }
        }
        /* CANCEL for a terminal record. */
        {
            int64_t term_id = rid;

            n = snprintf(cmd, sizeof cmd, "DEALPG4 CANCEL %lld %s\n",
                         (long long)term_id, nonce1);
            if (n <= 0 || (size_t)n >= sizeof cmd
                || peer_write_all(fd, cmd, (size_t)n) != 0
                || peer_read_line(fd, line, sizeof line, 3000) != 0
                || peer_parse_reject(line, &rid, tag, token) != 0
                || rid != term_id
                || strcmp(token, "CANCEL_AUTH_FAILED") != 0) {
                fprintf(stderr, "PEER FAIL cancel-terminal %s\n",
                        line);
                return 1;
            }
        }
        /* A validated CANCEL (live record + exact nonce): no answer,
         * the record untouched (the application is the
         * channel-machine child's). A read must time out. */
        n = snprintf(cmd, sizeof cmd, "DEALPG4 CANCEL %lld %s\n",
                     (long long)id1, nonce1);
        if (n <= 0 || (size_t)n >= sizeof cmd
            || peer_write_all(fd, cmd, (size_t)n) != 0) {
            fprintf(stderr, "PEER FAIL cancel-valid-write\n");
            return 1;
        }
        if (peer_read_line(fd, line, sizeof line, 800) != -2) {
            fprintf(stderr, "PEER FAIL cancel-valid-answer %s\n", line);
            return 1;
        }
        /* Every other live record unaffected: id2's ACK still gets
         * the FORKING state rejection. */
        {
            int64_t aid = 0;

            n = snprintf(cmd, sizeof cmd, "DEALPG4 ACK %lld %s\n",
                         (long long)id2, nonce2);
            if (n <= 0 || (size_t)n >= sizeof cmd
                || peer_write_all(fd, cmd, (size_t)n) != 0
                || peer_read_line(fd, line, sizeof line, 3000) != 0
                || peer_parse_reject(line, &aid, tag, token) != 0
                || aid != id2 || strcmp(tag, "tag") != 0
                || strcmp(token, "AUTH_FAILED") != 0) {
                fprintf(stderr, "PEER FAIL ack-id2 %s\n", line);
                return 1;
            }
        }
        close(fd);
        printf("PEER ackcancel\n");
        fflush(stdout);
        return 0;
    }

    if (strcmp(scenario, "bye-live") == 0) {
        /* BYE in BROKER_LIVE: PROTOCOL_ERROR closes the broker. */
        int64_t id = 0;
        char tag[64];

        if (peer_write_all(fd, VALID_INVOKE, strlen(VALID_INVOKE)) != 0
            || peer_read_line(fd, line, sizeof line, 3000) != 0) {
            fprintf(stderr, "PEER FAIL bye-invoked\n");
            return 1;
        }
        if (peer_parse_invoked(line, &id, tag) != 0) {
            fprintf(stderr, "PEER FAIL bye-shape %s", line);
            return 1;
        }
        if (peer_write_all(fd, "DEALPG4 BYE\n", 12) != 0
            || peer_read_line(fd, line, sizeof line, 3000) != -1) {
            fprintf(stderr, "PEER FAIL bye-no-eof\n");
            return 1;
        }
        close(fd);
        printf("PEER bye-eof\n");
        fflush(stdout);
        return 0;
    }

    if (strcmp(scenario, "post-cutoff") == 0) {
        /* Sleep past the cutoff (T0o + 2000 under FAST_LIMITS), then
         * a well-formed INVOKE: REJECT BUDGET_EXHAUSTED + immediate
         * terminal FAILED record, and — the cutoff passed with the
         * registry fully terminal — DONE failed queued right behind
         * the answer. */
        int64_t id = 0;
        char tag[64];
        char token[64];

        sleep_ms(2500);
        if (peer_write_all(fd, VALID_INVOKE, strlen(VALID_INVOKE)) != 0
            || peer_read_line(fd, line, sizeof line, 3000) != 0) {
            fprintf(stderr, "PEER FAIL pc-reject-read\n");
            return 1;
        }
        if (peer_parse_reject(line, &id, tag, token) != 0
            || strcmp(tag, "tag") != 0
            || strcmp(token, "BUDGET_EXHAUSTED") != 0) {
            fprintf(stderr, "PEER FAIL pc-reject %s\n", line);
            return 1;
        }
        if (peer_read_line(fd, line, sizeof line, 3000) != 0
            || strcmp(line, "DEALPG4 DONE failed\n") != 0) {
            fprintf(stderr, "PEER FAIL pc-done %s\n", line);
            return 1;
        }
        if (peer_write_all(fd, "DEALPG4 BYE\n", 12) != 0
            || peer_read_line(fd, line, sizeof line, 3000) != -1) {
            fprintf(stderr, "PEER FAIL pc-bye\n");
            return 1;
        }
        close(fd);
        printf("PEER post-cutoff\n");
        fflush(stdout);
        return 0;
    }

    if (strcmp(scenario, "done-cycle") == 0) {
        /* The cutoff-anchored DONE trigger: two pre-cutoff REJECT
         * answers (the live phase stays open for the repeated
         * dispatch), no DONE before the cutoff, then exactly one
         * "DONE failed" after the cutoff with every terminal answer
         * preceding it; a post-DONE INVOKE keeps the unconditional
         * post-cutoff answer over the still-open broker — never
         * PROTOCOL_ERROR, never a close, no second DONE; BYE is then
         * accepted (EOF after BYE). */
        int64_t id = 0;
        char tag[64];
        char token[64];
        uint64_t rej_ms;
        uint64_t done_ms;

        if (peer_write_all(fd, MALFORMED_ARGC_INVOKE,
                           strlen(MALFORMED_ARGC_INVOKE)) != 0
            || peer_read_line(fd, line, sizeof line, 3000) != 0) {
            fprintf(stderr, "PEER FAIL dc-rej1-read\n");
            return 1;
        }
        if (peer_parse_reject(line, &id, tag, token) != 0
            || strcmp(token, "MALFORMED_INVOKE") != 0) {
            fprintf(stderr, "PEER FAIL dc-rej1 %s\n", line);
            return 1;
        }
        rej_ms = dealpg4_now_ms();
        /* The second pre-cutoff INVOKE: the live phase stays open. */
        if (peer_write_all(fd, MALFORMED_ARGC_INVOKE,
                           strlen(MALFORMED_ARGC_INVOKE)) != 0
            || peer_read_line(fd, line, sizeof line, 3000) != 0) {
            fprintf(stderr, "PEER FAIL dc-rej2-read\n");
            return 1;
        }
        if (peer_parse_reject(line, &id, tag, token) != 0
            || strcmp(token, "MALFORMED_INVOKE") != 0) {
            fprintf(stderr, "PEER FAIL dc-rej2 %s\n", line);
            return 1;
        }
        /* DONE arrives only after the cutoff (nestedStopMs 2000): at
         * least ~1 s after the pre-cutoff answers, exactly once. */
        if (peer_read_line(fd, line, sizeof line, 5000) != 0
            || strcmp(line, "DEALPG4 DONE failed\n") != 0) {
            fprintf(stderr, "PEER FAIL dc-done %s\n", line);
            return 1;
        }
        done_ms = dealpg4_now_ms();
        if (done_ms - rej_ms < 1000) {
            fprintf(stderr, "PEER FAIL dc-done-early %llu\n",
                    (unsigned long long)(done_ms - rej_ms));
            return 1;
        }
        /* A post-DONE INVOKE: REJECT BUDGET_EXHAUSTED + immediate
         * terminal FAILED record over the open broker. */
        if (peer_write_all(fd, VALID_INVOKE, strlen(VALID_INVOKE)) != 0
            || peer_read_line(fd, line, sizeof line, 3000) != 0) {
            fprintf(stderr, "PEER FAIL dc-post-read\n");
            return 1;
        }
        if (peer_parse_reject(line, &id, tag, token) != 0
            || strcmp(tag, "tag") != 0
            || strcmp(token, "BUDGET_EXHAUSTED") != 0) {
            fprintf(stderr, "PEER FAIL dc-post %s\n", line);
            return 1;
        }
        /* No second DONE: a read must time out. */
        if (peer_read_line(fd, line, sizeof line, 500) != -2) {
            fprintf(stderr, "PEER FAIL dc-second-done %s\n", line);
            return 1;
        }
        /* BYE is accepted after DONE: the outer closes its end. */
        if (peer_write_all(fd, "DEALPG4 BYE\n", 12) != 0
            || peer_read_line(fd, line, sizeof line, 3000) != -1) {
            fprintf(stderr, "PEER FAIL dc-bye\n");
            return 1;
        }
        close(fd);
        printf("PEER done-cycle\n");
        fflush(stdout);
        return 0;
    }

    fprintf(stderr, "PEER FAIL unknown-scenario %s\n", scenario);
    return 1;
}

/* The scripted-peer re-exec entry. */
static int registry_peer_entry(int argc, char **argv)
{
    signal(SIGPIPE, SIG_IGN);
    if (argc < 3) {
        fprintf(stderr, "PEER FAIL missing-scenario\n");
        return 1;
    }
    return registry_peer_main(argv[2], argc >= 4 ? argv[3] : NULL);
}

/* === Helper-child machinery ============================================ */

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

/* One full-core call in a pipe-backed report fd with an optional
 * spawn composition. Returns the core status. */
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

static int has_token(const dealpg4_outer_result *v, const char *token)
{
    size_t i;

    for (i = 0; i < v->ntokens; i++) {
        if (strcmp(v->tokens[i], token) == 0)
            return 1;
    }
    return 0;
}

static void peer_argv(char *argv[8], const char *scenario,
                      const char *arg)
{
    size_t n = 0;

    argv[n++] = (char *)g_suite_argv0;
    argv[n++] = (char *)"--registry-peer";
    argv[n++] = (char *)scenario;
    if (arg != NULL)
        argv[n++] = (char *)arg;
    argv[n] = NULL;
}

/* Common per-run assertions: the peer exited clean (its own
 * assertions held), no peer failure line in the stderr drain, the
 * broker surface closed and unlinked, no children, no socket path
 * leftover. */
static void assert_common(const dealpg4_outer_result *view,
                          const dealpg4_drain_ctx *derr)
{
    int peer_ok = (view->coordinator_exited_0 == 1);

    CHECK(peer_ok);
    if (derr != NULL) {
        int no_peer_fail = (derr->retained_len == 0
                            || find_in_retained(derr, "PEER FAIL")
                                   == NULL);

        CHECK(no_peer_fail);
        if (!peer_ok || !no_peer_fail) {
            fprintf(stderr,
                    "DIAG coordinator reaped=%d exited0=%d code=%d "
                    "status=%d tokens:", view->coordinator_reaped,
                    view->coordinator_exited_0, view->coordinator_si_code,
                    view->coordinator_si_status);
            for (size_t i = 0; i < view->ntokens; i++)
                fprintf(stderr, " %s", view->tokens[i]);
            fprintf(stderr, "\n");
            if (derr->retained_len > 0) {
                fprintf(stderr, "DIAG peer stderr: %.*s\n",
                        (int)derr->retained_len,
                        (const char *)derr->retained);
            }
        }
    }
    CHECK(view->broker_closed == 1);
    CHECK(view->broker_socket_unlinked == 1);
    CHECK(view->broker_state == DEALPG4_OUTER_BROKER_CLOSED);
    check_no_children();
    CHECK(outer_socket_path_count("build") == 0);
}

/* One full core run + view + common assertions. */
static void run_case(const OuterLimits *limits, const char *scenario,
                     const char *arg, const dealpg4_outer_spawn *spawn,
                     char *report, size_t report_cap, int *status,
                     dealpg4_outer_result *view)
{
    char *argv[8];

    peer_argv(argv, scenario, arg);
    *status = core_with_spawn(limits, argv, spawn, report, report_cap);
    memset(view, 0, sizeof *view);
    dealpg4_outer_last_result(view);
    {
        const dealpg4_drain_ctx *dout = NULL;
        const dealpg4_drain_ctx *derr = NULL;

        dealpg4_outer_drain_state(&dout, &derr);
        (void)dout;
        assert_common(view, derr);
    }
}

/* === Group 1: the INVOKE semantic split =============================== */

typedef struct semantic_case {
    const char *text;
    const char *name;
} semantic_case;

static const semantic_case *g_current_semantic;

static int semantic_split_case(void)
{
    spawn_recorder rec;
    dealpg4_outer_spawn sp;
    char *argv[8];
    char report[1024];
    dealpg4_outer_result view;
    dealpg4_outer_record_view recview;
    int status;

    memset(&rec, 0, sizeof rec);
    rec.mode = REC_MODE_FORK;
    sp = make_spawn(&rec);
    peer_argv(argv, "malformed", g_current_semantic->text);
    status = core_with_spawn(&FAST_LIMITS, argv, &sp, report,
                             sizeof report);
    memset(&view, 0, sizeof view);
    dealpg4_outer_last_result(&view);

    CHECK(status == DEALPG4_OUTER_EXIT_GATE_FAILURE);
    CHECK(rec.fork_calls == 0); /* no fork for a rejected INVOKE */
    CHECK(view.records_total == 1);
    CHECK(view.records_live == 0);
    CHECK(view.records_failed == 1);
    CHECK(view.records_clean == 0);
    CHECK(view.next_invocation_id == 2);
    CHECK(view.proof_passed == 1);
    CHECK(view.done_queued == 0); /* the cutoff never passed */
    CHECK(view.cutoff_cancelled == 0);
    CHECK(dealpg4_outer_registry_count() == 1);
    CHECK(dealpg4_outer_registry_record(0, &recview) == 0);
    CHECK(recview.state == DEALPG4_OUTER_REC_FAILED);
    CHECK(recview.supervisor_pid == -1);
    CHECK(recview.control_fd == -1);
    CHECK(recview.stub_pid == -1);
    CHECK(recview.nonce[0] == '\0');
    CHECK(recview.deadline_ms == 0);
    CHECK(recview.deadline_abs_ms == 0);
    CHECK(recview.history_count == 1);
    CHECK(recview.history[0].state == DEALPG4_OUTER_REC_FAILED);
    CHECK(strcmp(recview.failure_token, "MALFORMED_INVOKE") == 0);
    CHECK(strstr(report, "OUTER record 1 FAILED tag MALFORMED_INVOKE\n")
          != NULL);
    assert_common(&view, NULL);
    return (g_failures > 0) ? 1 : 0;
}

static int semantic_split_fn(void)
{
    static const semantic_case cases[] = {
        {MALFORMED_ARGC_INVOKE, "argc/argv-count mismatch"},
        {"DEALPG4 INVOKE tag 2f 0\n", "argc 0 (empty argv)"},
        {"DEALPG4 INVOKE tag 2f 1\n", "argc 1 with no argv fields"},
        {"DEALPG4 INVOKE tag  1 74727565\n", "empty cwd"},
        {"DEALPG4 INVOKE tag 00 1 74727565\n", "NUL-containing cwd"},
        {"DEALPG4 INVOKE tag ff 1 74727565\n", "invalid-UTF-8 cwd"},
    };
    size_t i;

    for (i = 0; i < sizeof cases / sizeof cases[0]; i++) {
        char errbuf[2048];
        int status;

        g_current_semantic = &cases[i];
        if (run_capture_child(semantic_split_case, errbuf,
                              sizeof errbuf, &status) != 0) {
            fprintf(stderr, "FAIL: helper machinery broke\n");
            return 1;
        }
        gate_group(cases[i].name, errbuf, status);
    }
    return (g_failures > 0) ? 1 : 0;
}

/* === Group 1b: the INVOKE framing split =============================== */

typedef struct framing_case {
    const char *text; /* NULL = the oversize unterminated stream */
    const char *name;
} framing_case;

static const framing_case *g_current_framing;

static int framing_split_case(void)
{
    spawn_recorder rec;
    dealpg4_outer_spawn sp;
    char *argv[8];
    char report[1024];
    dealpg4_outer_result view;
    int status;

    memset(&rec, 0, sizeof rec);
    rec.mode = REC_MODE_FORK;
    sp = make_spawn(&rec);
    if (g_current_framing->text != NULL)
        peer_argv(argv, "framing", g_current_framing->text);
    else
        peer_argv(argv, "framing-big", NULL);
    status = core_with_spawn(&FAST_LIMITS, argv, &sp, report,
                             sizeof report);
    memset(&view, 0, sizeof view);
    dealpg4_outer_last_result(&view);

    CHECK(status == DEALPG4_OUTER_EXIT_GATE_FAILURE);
    CHECK(has_token(&view, "PROTOCOL_ERROR"));
    CHECK(rec.fork_calls == 0);
    CHECK(view.records_total == 0); /* framing defects never register */
    CHECK(view.proof_passed == 1);
    CHECK(dealpg4_outer_registry_count() == 0);
    assert_common(&view, NULL);
    return (g_failures > 0) ? 1 : 0;
}

static int framing_split_fn(void)
{
    static const framing_case cases[] = {
        {"DEALPG4 INVOKE tag 2f 1 zz\n", "non-hex argv"},
        {"DEALPG4 INVOKE tag 2f 1 0\n", "odd-length hex"},
        {"DEALPG4 INVOKE tag 2f\n", "field-count violation"},
        {"DEALPG4 INVOKE tag 2f 1 74727565\r\n", "CR anywhere"},
        {NULL, "oversize unterminated INVOKE stream"},
    };
    size_t i;

    for (i = 0; i < sizeof cases / sizeof cases[0]; i++) {
        char errbuf[2048];
        int status;

        g_current_framing = &cases[i];
        if (run_capture_child(framing_split_case, errbuf,
                              sizeof errbuf, &status) != 0) {
            fprintf(stderr, "FAIL: helper machinery broke\n");
            return 1;
        }
        gate_group(cases[i].name, errbuf, status);
    }
    return (g_failures > 0) ? 1 : 0;
}

/* === Group 2: the pre-fork rejection battery ========================== */

/* The T-floor: nestedStopMs scaled so T < 15000 -> REJECT
 * BUDGET_EXHAUSTED + terminal FAILED record, no fork, no pid, no
 * channels. */
static int floor_case_fn(void)
{
    spawn_recorder rec;
    dealpg4_outer_spawn sp;
    char report[1024];
    dealpg4_outer_result view;
    dealpg4_outer_record_view recview;
    int status;

    memset(&rec, 0, sizeof rec);
    rec.mode = REC_MODE_FORK;
    sp = make_spawn(&rec);
    run_case(&FLOOR_LIMITS, "floor", NULL, &sp, report, sizeof report,
             &status, &view);

    CHECK(status == DEALPG4_OUTER_EXIT_GATE_FAILURE);
    CHECK(rec.fork_calls == 0);
    CHECK(view.records_total == 1);
    CHECK(view.records_failed == 1);
    CHECK(view.proof_passed == 1);
    CHECK(dealpg4_outer_registry_record(0, &recview) == 0);
    CHECK(recview.state == DEALPG4_OUTER_REC_FAILED);
    CHECK(recview.supervisor_pid == -1);
    CHECK(recview.control_fd == -1);
    CHECK(strcmp(recview.failure_token, "BUDGET_EXHAUSTED") == 0);
    CHECK(recview.history_count == 1);
    return (g_failures > 0) ? 1 : 0;
}

/* FORK_FAILED via a spawn-seam composition whose fork_nested returns
 * -1 (the record was already inserted — register-before-fork held). */
static int fork_fail_composition_fn(void)
{
    spawn_recorder rec;
    dealpg4_outer_spawn sp;
    char report[1024];
    dealpg4_outer_result view;
    dealpg4_outer_record_view recview;
    int status;

    memset(&rec, 0, sizeof rec);
    rec.mode = REC_MODE_FAIL;
    rec.fail_errno = EAGAIN;
    sp = make_spawn(&rec);
    run_case(&LIVE_LIMITS, "reject-token", "FORK_FAILED", &sp, report,
             sizeof report, &status, &view);

    CHECK(status == DEALPG4_OUTER_EXIT_GATE_FAILURE);
    CHECK(rec.fork_calls == 1);
    CHECK(view.records_total == 1);
    CHECK(view.records_live == 0);
    CHECK(view.records_failed == 1);
    CHECK(view.proof_passed == 1);
    CHECK(dealpg4_outer_registry_record(0, &recview) == 0);
    CHECK(recview.state == DEALPG4_OUTER_REC_FAILED);
    CHECK(recview.supervisor_pid == -1);
    CHECK(recview.control_fd == -1);
    CHECK(recview.nonce[0] != '\0'); /* the nonce was generated at
                                        registration */
    CHECK(strcmp(recview.nonce, rec.last_nonce) == 0);
    CHECK(recview.deadline_ms == rec.last_budget);
    CHECK(recview.deadline_ms >= DEALPG4_PROBE_SELFTEST_LIMIT_MS_FLOOR);
    CHECK(strcmp(recview.failure_token, "FORK_FAILED") == 0);
    /* The FORKING -> FAILED path (register-before-fork held). */
    CHECK(recview.history_count == 2);
    CHECK(recview.history[0].state == DEALPG4_OUTER_REC_FORKING);
    CHECK(recview.history[1].state == DEALPG4_OUTER_REC_FAILED);
    CHECK(strstr(report, "OUTER record 1 FAILED tag FORK_FAILED\n")
          != NULL);
    return (g_failures > 0) ? 1 : 0;
}

/* === Group 2 fault-injection sites ==================================== */

static const int registry_fail_sites[] = {
    FI_OUTER_SUBREAPER, FI_OUTER_TIMERFD, FI_OUTER_SIGNALFD,
    FI_OUTER_NONCE, FI_OUTER_PIPE, FI_COORD_READY_MISMATCH,
    FI_OUTER_BIND, FI_OUTER_SOCKETPAIR, FI_OUTER_FORK,
    FI_OUTER_INVOKE_NONCE
};
static const dealpg4_fi_catalog registry_catalog = {
    NULL, 0, registry_fail_sites, 10, NULL, 0, NULL, 0
};

static void install_fail(int site, int value)
{
    dealpg4_fi_script_fail fails[1];
    dealpg4_fi_script script;

    fails[0].site = site;
    fails[0].value = value;
    fails[0].oneshot = 0;
    script.delays = NULL;
    script.ndelays = 0;
    script.fails = fails;
    script.nfails = 1;
    script.congests = NULL;
    script.ncongests = 0;
    CHECK(dealpg4_fi_install_overrides(&script, &registry_catalog)
          == 0);
}

/* One pre-fork rejection driven by an injected site: the REJECT
 * carries the expected token, the record is terminal FAILED with the
 * same token, no fork, no pid, no channels, no fallback work. */
static int injected_reject_case(int site, const char *token,
                                int expect_fork_call)
{
    spawn_recorder rec;
    dealpg4_outer_spawn sp;
    char report[1024];
    dealpg4_outer_result view;
    dealpg4_outer_record_view recview;
    int status;

    memset(&rec, 0, sizeof rec);
    rec.mode = REC_MODE_FORK;
    sp = make_spawn(&rec);
    install_fail(site, 1);
    run_case(&LIVE_LIMITS, "reject-token", token, &sp, report,
             sizeof report, &status, &view);
    dealpg4_fi_restore_defaults();

    CHECK(status == DEALPG4_OUTER_EXIT_GATE_FAILURE);
    CHECK(rec.fork_calls == (expect_fork_call ? 1 : 0));
    CHECK(view.records_total == 1);
    CHECK(view.records_live == 0);
    CHECK(view.records_failed == 1);
    CHECK(view.proof_passed == 1);
    CHECK(dealpg4_outer_registry_record(0, &recview) == 0);
    CHECK(recview.state == DEALPG4_OUTER_REC_FAILED);
    CHECK(recview.supervisor_pid == -1);
    CHECK(recview.control_fd == -1);
    CHECK(recview.stub_pid == -1);
    CHECK(strcmp(recview.failure_token, token) == 0);
    return (g_failures > 0) ? 1 : 0;
}

/* NONCE_FAILED via the registration-time nonce site: the production
 * nonce path (dealpg4_nonce_hex) is unchanged when the site is
 * unscripted — the unscripted run's record carries a real 32-hex
 * nonce. */
static int nonce_failed_case_fn(void)
{
    int rc;

    rc = injected_reject_case(FI_OUTER_INVOKE_NONCE, "NONCE_FAILED",
                              0 /* no fork call */);
    /* The record of the injected run carries no nonce. */
    {
        dealpg4_outer_record_view recview;

        CHECK(dealpg4_outer_registry_record(0, &recview) == 0);
        CHECK(recview.nonce[0] == '\0');
    }
    return rc;
}

/* FORK_FAILED via the scripted socketpair failure: the record was
 * already inserted (register-before-fork held) — FORKING -> FAILED,
 * no fork call, no channels. */
static int socketpair_fail_case_fn(void)
{
    int rc;

    rc = injected_reject_case(FI_OUTER_SOCKETPAIR, "FORK_FAILED",
                              0 /* no fork call */);
    {
        dealpg4_outer_record_view recview;

        CHECK(dealpg4_outer_registry_record(0, &recview) == 0);
        CHECK(recview.history_count == 2);
        CHECK(recview.history[0].state == DEALPG4_OUTER_REC_FORKING);
        CHECK(recview.history[1].state == DEALPG4_OUTER_REC_FAILED);
    }
    return rc;
}

/* FORK_FAILED via FI_OUTER_FORK through the production fork_nested
 * (spawn = NULL): the injected fork failure never forks. */
static int fork_fail_site_case_fn(void)
{
    char *argv[8];
    char report[1024];
    dealpg4_outer_result view;
    dealpg4_outer_record_view recview;
    int status;

    install_fail(FI_OUTER_FORK, EAGAIN);
    peer_argv(argv, "reject-token", "FORK_FAILED");
    status = core_with_spawn(&LIVE_LIMITS, argv, NULL /* production */,
                             report, sizeof report);
    dealpg4_fi_restore_defaults();
    memset(&view, 0, sizeof view);
    dealpg4_outer_last_result(&view);
    assert_common(&view, NULL);

    CHECK(status == DEALPG4_OUTER_EXIT_GATE_FAILURE);
    CHECK(view.records_total == 1);
    CHECK(view.records_live == 0);
    CHECK(view.records_failed == 1);
    CHECK(view.proof_passed == 1);
    CHECK(dealpg4_outer_registry_record(0, &recview) == 0);
    CHECK(recview.state == DEALPG4_OUTER_REC_FAILED);
    CHECK(recview.supervisor_pid == -1);
    CHECK(strcmp(recview.failure_token, "FORK_FAILED") == 0);
    CHECK(recview.history_count == 2);
    return (g_failures > 0) ? 1 : 0;
}

/* REGISTRY_FULL at 128 live records: the 129th INVOKE gets REJECT
 * REGISTRY_FULL + a terminal FAILED record with no fork. The run
 * completes at the total deadline (the live records' cancellation
 * execution lands with the fallback child). */
static int registry_full_case_fn(void)
{
    spawn_recorder rec;
    dealpg4_outer_spawn sp;
    char report[16384];
    dealpg4_outer_result view;
    dealpg4_outer_record_view recview;
    int status;
    uint64_t before;
    uint64_t after;

    memset(&rec, 0, sizeof rec);
    rec.mode = REC_MODE_FORK;
    sp = make_spawn(&rec);
    before = dealpg4_now_ms();
    run_case(&LIVE_LIMITS, "full", NULL, &sp, report, sizeof report,
             &status, &view);
    after = dealpg4_now_ms();

    CHECK(status == DEALPG4_OUTER_EXIT_GATE_FAILURE);
    CHECK(after - before >= 15000); /* the run held to the total
                                       deadline with live records */
    CHECK(after - before < 25000);
    CHECK(rec.fork_calls == 128);
    CHECK(view.records_total == 129);
    CHECK(view.records_live == 128); /* all CANCELLING (caller loss)
                                        — the cancellation execution
                                        lands with the fallback
                                        child */
    CHECK(view.records_failed == 1);
    CHECK(view.next_invocation_id == 130);
    CHECK(view.cutoff_cancelled == 1);
    CHECK(view.caller_loss_marked == 1);
    CHECK(view.done_queued == 0); /* live records: DONE waits */
    CHECK(has_token(&view, "OVERALL_TIMEOUT"));
    CHECK(view.proof_passed == 0);
    CHECK(dealpg4_outer_registry_count() == 129);
    CHECK(dealpg4_outer_registry_record(128, &recview) == 0);
    CHECK(recview.state == DEALPG4_OUTER_REC_FAILED);
    CHECK(recview.supervisor_pid == -1);
    CHECK(recview.control_fd == -1);
    CHECK(strcmp(recview.failure_token, "REGISTRY_FULL") == 0);
    CHECK(strstr(report, "OUTER record 129 FAILED tag REGISTRY_FULL\n")
          != NULL);
    return (g_failures > 0) ? 1 : 0;
}

/* === Group 3: register-before-fork and the serve surface ============== */

static int register_before_fork_fn(void)
{
    spawn_recorder rec;
    dealpg4_outer_spawn sp;
    char report[1024];
    dealpg4_outer_result view;
    dealpg4_outer_record_view recview;
    int status;
    uint64_t before;
    uint64_t after;

    memset(&rec, 0, sizeof rec);
    rec.mode = REC_MODE_CHECK;
    sp = make_spawn(&rec);
    before = dealpg4_now_ms();
    run_case(&LIVE_LIMITS, "invoke-ok", NULL, &sp, report,
             sizeof report, &status, &view);
    after = dealpg4_now_ms();

    CHECK(status == DEALPG4_OUTER_EXIT_GATE_FAILURE);
    CHECK(after - before >= 15000); /* the live record holds the run
                                       to the total deadline */
    CHECK(after - before < 25000);
    CHECK(rec.fork_calls == 1);
    CHECK(view.records_total == 1);
    CHECK(view.records_live == 1);
    CHECK(view.next_invocation_id == 2);
    CHECK(view.cutoff_cancelled == 1);
    CHECK(view.caller_loss_marked == 1);
    CHECK(has_token(&view, "OVERALL_TIMEOUT"));
    CHECK(dealpg4_outer_registry_record(0, &recview) == 0);
    CHECK(recview.invocation_id == 1);
    CHECK(recview.state == DEALPG4_OUTER_REC_CANCELLING);
    CHECK(recview.supervisor_pid == 999999); /* attached at fork
                                                return */
    CHECK(recview.nonce[0] != '\0');
    CHECK(strcmp(recview.nonce, rec.last_nonce) == 0);
    CHECK(recview.deadline_ms == rec.last_budget);
    CHECK(recview.deadline_ms >= DEALPG4_PROBE_SELFTEST_LIMIT_MS_FLOOR);
    CHECK(recview.deadline_ms <= DEALPG4_LAUNCHER_OVERALL_TIMEOUT_MS);
    CHECK(recview.deadline_abs_ms > recview.deadline_ms);
    CHECK(recview.ack_applied == 0);
    CHECK(strcmp(recview.client_tag, "tag") == 0);
    CHECK(recview.history_count == 2);
    CHECK(recview.history[0].state == DEALPG4_OUTER_REC_FORKING);
    CHECK(recview.history[1].state == DEALPG4_OUTER_REC_CANCELLING);
    /* The captured serve argv surface (checked in-fork by the
     * composition; re-checked here): self + serve + cwd + -- +
     * target. */
    CHECK(rec.serve_argc == 5);
    CHECK(rec.serve_self[0] != '\0');
    CHECK(strcmp(rec.serve_cwd, "/") == 0);
    CHECK(strcmp(rec.serve_arg0, "true") == 0);
    return (g_failures > 0) ? 1 : 0;
}

/* The broker EOF arriving mid-INVOKE: the accepted INVOKE is
 * registered + forked, then the caller-loss mark applies — never the
 * reverse. */
static int mid_invoke_eof_fn(void)
{
    spawn_recorder rec;
    dealpg4_outer_spawn sp;
    char report[1024];
    dealpg4_outer_result view;
    dealpg4_outer_record_view recview;
    int status;
    uint64_t before;
    uint64_t after;

    memset(&rec, 0, sizeof rec);
    rec.mode = REC_MODE_FORK;
    sp = make_spawn(&rec);
    before = dealpg4_now_ms();
    run_case(&LIVE_LIMITS, "invoke-eof", NULL, &sp, report,
             sizeof report, &status, &view);
    after = dealpg4_now_ms();

    CHECK(status == DEALPG4_OUTER_EXIT_GATE_FAILURE);
    CHECK(after - before >= 15000);
    CHECK(after - before < 25000);
    CHECK(rec.fork_calls == 1); /* register + fork ran before the
                                   caller-loss mark */
    CHECK(view.records_total == 1);
    CHECK(view.records_live == 1);
    CHECK(view.caller_loss_marked == 1);
    CHECK(has_token(&view, "OVERALL_TIMEOUT"));
    CHECK(dealpg4_outer_registry_record(0, &recview) == 0);
    CHECK(recview.state == DEALPG4_OUTER_REC_CANCELLING);
    CHECK(recview.history[0].state == DEALPG4_OUTER_REC_FORKING);
    CHECK(recview.history[1].state == DEALPG4_OUTER_REC_CANCELLING);
    return (g_failures > 0) ? 1 : 0;
}

/* === Group 4: ACK/CANCEL record-level validation ====================== */

static int ack_cancel_validation_fn(void)
{
    spawn_recorder rec;
    dealpg4_outer_spawn sp;
    char report[16384];
    dealpg4_outer_result view;
    dealpg4_outer_record_view recview;
    int status;
    uint64_t before;
    uint64_t after;

    (void)unlink("build/.outer-rec-nonce");
    memset(&rec, 0, sizeof rec);
    rec.mode = REC_MODE_NONCE;
    sp = make_spawn(&rec);
    before = dealpg4_now_ms();
    run_case(&LIVE_LIMITS, "ackcancel", NULL, &sp, report,
             sizeof report, &status, &view);
    after = dealpg4_now_ms();
    (void)unlink("build/.outer-rec-nonce");

    CHECK(status == DEALPG4_OUTER_EXIT_GATE_FAILURE);
    CHECK(after - before >= 15000);
    CHECK(after - before < 25000);
    CHECK(rec.fork_calls == 2);
    CHECK(view.records_total == 3);
    CHECK(view.records_live == 2);
    CHECK(view.records_failed == 1);
    CHECK(view.next_invocation_id == 4);
    CHECK(has_token(&view, "OVERALL_TIMEOUT"));
    CHECK(!has_token(&view, "PROTOCOL_ERROR"));
    CHECK(dealpg4_outer_registry_record(0, &recview) == 0);
    CHECK(recview.state == DEALPG4_OUTER_REC_CANCELLING);
    CHECK(recview.ack_applied == 0); /* every ACK was rejected — the
                                        record is untouched */
    CHECK(dealpg4_outer_registry_record(2, &recview) == 0);
    CHECK(recview.state == DEALPG4_OUTER_REC_FAILED);
    CHECK(strcmp(recview.failure_token, "MALFORMED_INVOKE") == 0);
    return (g_failures > 0) ? 1 : 0;
}

/* BYE in BROKER_LIVE is PROTOCOL_ERROR. */
static int bye_in_live_fn(void)
{
    spawn_recorder rec;
    dealpg4_outer_spawn sp;
    char report[1024];
    dealpg4_outer_result view;
    dealpg4_outer_record_view recview;
    int status;
    uint64_t before;
    uint64_t after;

    memset(&rec, 0, sizeof rec);
    rec.mode = REC_MODE_FORK;
    sp = make_spawn(&rec);
    before = dealpg4_now_ms();
    run_case(&LIVE_LIMITS, "bye-live", NULL, &sp, report,
             sizeof report, &status, &view);
    after = dealpg4_now_ms();

    CHECK(status == DEALPG4_OUTER_EXIT_GATE_FAILURE);
    CHECK(after - before >= 15000);
    CHECK(after - before < 25000);
    CHECK(rec.fork_calls == 1);
    CHECK(has_token(&view, "PROTOCOL_ERROR"));
    CHECK(has_token(&view, "OVERALL_TIMEOUT"));
    CHECK(view.records_total == 1);
    CHECK(view.records_live == 1);
    CHECK(view.caller_loss_marked == 1);
    CHECK(dealpg4_outer_registry_record(0, &recview) == 0);
    CHECK(recview.state == DEALPG4_OUTER_REC_CANCELLING);
    return (g_failures > 0) ? 1 : 0;
}

/* === Group 5: the cutoff-anchored DONE trigger ======================== */

/* The full DONE cycle over the live peer: pre-cutoff REJECT answers,
 * DONE failed exactly once after the cutoff with the answers
 * preceding it, the post-DONE INVOKE answer, no second DONE, BYE
 * accepted. */
static int done_cycle_fn(void)
{
    spawn_recorder rec;
    dealpg4_outer_spawn sp;
    char report[1024];
    dealpg4_outer_result view;
    dealpg4_outer_record_view recview;
    int status;
    uint64_t before;
    uint64_t after;

    memset(&rec, 0, sizeof rec);
    rec.mode = REC_MODE_FORK;
    sp = make_spawn(&rec);
    before = dealpg4_now_ms();
    run_case(&FAST_LIMITS, "done-cycle", NULL, &sp, report,
             sizeof report, &status, &view);
    after = dealpg4_now_ms();

    CHECK(status == DEALPG4_OUTER_EXIT_GATE_FAILURE);
    CHECK(after - before >= 1800); /* DONE waited for the cutoff */
    CHECK(after - before < 8000);
    CHECK(rec.fork_calls == 0);
    CHECK(view.done_queued == 1);
    CHECK(view.done_clean == 0); /* FAILED records: verdict failed */
    CHECK(view.done_ms >= view.cutoff_mark_ms
          && view.cutoff_mark_ms > 0);
    CHECK(view.cutoff_cancelled == 1);
    CHECK(view.records_total == 3);
    CHECK(view.records_live == 0);
    CHECK(view.records_failed == 3);
    CHECK(view.next_invocation_id == 4);
    CHECK(view.proof_passed == 1);
    CHECK(!has_token(&view, "OVERALL_TIMEOUT"));
    CHECK(!has_token(&view, "PROTOCOL_ERROR"));
    CHECK(dealpg4_outer_registry_record(2, &recview) == 0);
    CHECK(recview.state == DEALPG4_OUTER_REC_FAILED);
    CHECK(strcmp(recview.failure_token, "BUDGET_EXHAUSTED") == 0);
    CHECK(strstr(report, "OUTER record 1 FAILED tag MALFORMED_INVOKE\n")
          != NULL);
    return (g_failures > 0) ? 1 : 0;
}

/* A post-cutoff INVOKE arriving before DONE: the same deterministic
 * REJECT BUDGET_EXHAUSTED answer, then DONE failed queued right
 * behind it (the trigger fires on the terminal transition). */
static int post_cutoff_invoke_fn(void)
{
    spawn_recorder rec;
    dealpg4_outer_spawn sp;
    char report[1024];
    dealpg4_outer_result view;
    dealpg4_outer_record_view recview;
    int status;
    uint64_t before;
    uint64_t after;

    memset(&rec, 0, sizeof rec);
    rec.mode = REC_MODE_FORK;
    sp = make_spawn(&rec);
    before = dealpg4_now_ms();
    run_case(&FAST_LIMITS, "post-cutoff", NULL, &sp, report,
             sizeof report, &status, &view);
    after = dealpg4_now_ms();

    CHECK(status == DEALPG4_OUTER_EXIT_GATE_FAILURE);
    CHECK(after - before >= 2300); /* the peer slept past the cutoff */
    CHECK(after - before < 8000);
    CHECK(rec.fork_calls == 0);
    CHECK(view.done_queued == 1);
    CHECK(view.done_clean == 0);
    CHECK(view.cutoff_cancelled == 1);
    CHECK(view.records_total == 1);
    CHECK(view.records_failed == 1);
    CHECK(view.next_invocation_id == 2);
    CHECK(view.proof_passed == 1);
    CHECK(!has_token(&view, "OVERALL_TIMEOUT"));
    CHECK(dealpg4_outer_registry_record(0, &recview) == 0);
    CHECK(recview.state == DEALPG4_OUTER_REC_FAILED);
    CHECK(strcmp(recview.failure_token, "BUDGET_EXHAUSTED") == 0);
    CHECK(strstr(report, "OUTER record 1 FAILED tag BUDGET_EXHAUSTED\n")
          != NULL);
    return (g_failures > 0) ? 1 : 0;
}

/* An empty registry at the cutoff: DONE is never queued; the peer's
 * close + exit 0 is the clean exit. */
static int empty_registry_fn(void)
{
    spawn_recorder rec;
    dealpg4_outer_spawn sp;
    char report[1024];
    dealpg4_outer_result view;
    int status;
    uint64_t before;
    uint64_t after;

    memset(&rec, 0, sizeof rec);
    rec.mode = REC_MODE_FORK;
    sp = make_spawn(&rec);
    before = dealpg4_now_ms();
    run_case(&FAST_LIMITS, "ok-no-done", NULL, &sp, report,
             sizeof report, &status, &view);
    after = dealpg4_now_ms();

    CHECK(status == 0);
    CHECK(after - before < 8000);
    CHECK(rec.fork_calls == 0);
    CHECK(view.done_queued == 0);
    CHECK(view.records_total == 0);
    CHECK(view.records_live == 0);
    CHECK(view.records_failed == 0);
    CHECK(view.ntokens == 0);
    CHECK(view.proof_passed == 1);
    CHECK(dealpg4_outer_registry_count() == 0);
    CHECK(strstr(report, "OUTER final 0 ") != NULL);
    CHECK(strstr(report, "OUTER record ") == NULL);
    return (g_failures > 0) ? 1 : 0;
}

/* === Group 6: integration through the real mode entry ================= */

/* Run the committed launcher's outer mode entry with the scripted
 * peer; assert the exit status, the report content, and the socket
 * cleanup. */
static int real_mode_entry_case(const char *scenario, int want_status,
                                const char *want_record,
                                const char *want_final)
{
    char *launcher_argv[] = {
        (char *)"./deal-process-launcher-linux-x86_64",
        (char *)"outer", (char *)NONCE, (char *)"--",
        (char *)g_suite_argv0, (char *)"--registry-peer",
        (char *)scenario, NULL};
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
        _exit(127);
    }
    close(pipefd[1]);

    start = dealpg4_now_ms();
    deadline = start + 30000;
    for (;;) {
        struct pollfd pfd;
        int rc;
        ssize_t r;

        pfd.fd = pipefd[0];
        pfd.events = POLLIN;
        pfd.revents = 0;
        rc = poll(&pfd, 1, 200);
        if (rc > 0 && (pfd.revents & (POLLIN | POLLHUP))) {
            r = read(pipefd[0], out + off, sizeof out - off - 1);
            if (r > 0) {
                off += (size_t)r;
                continue;
            }
            if (r == 0)
                break;
            if (errno == EINTR)
                continue;
            break;
        }
        if (rc < 0 && errno == EINTR)
            continue;
        if (!reaped) {
            pid_t w = waitpid(pid, &st, WNOHANG);

            if (w == pid)
                reaped = 1;
        }
        if (dealpg4_now_ms() >= deadline) {
            kill(pid, SIGKILL);
            (void)waitpid(pid, &st, 0);
            out[off] = '\0';
            CHECK(0 && "real-mode-entry case exceeded the 30 s bound");
            close(pipefd[0]);
            return 1;
        }
    }
    close(pipefd[0]);
    out[off] = '\0';
    if (!reaped)
        CHECK(waitpid(pid, &st, 0) == pid);
    CHECK(WIFEXITED(st) && WEXITSTATUS(st) == want_status);
    CHECK(dealpg4_now_ms() - start < 30000);
    CHECK(strstr(out, want_final) != NULL);
    if (want_record != NULL)
        CHECK(strstr(out, want_record) != NULL);
    CHECK(strstr(out, "OUTER proof ok\n") != NULL);
    CHECK(outer_socket_path_count("build") == 0);
    check_no_children();
    return (g_failures > 0) ? 1 : 0;
}

static int integration_reject_fn(void)
{
    /* The real mode entry: the scripted coordinator completes the
     * handshake, the malformed INVOKE gets REJECT ... MALFORMED_INVOKE
     * over the open channel, the coordinator closes and exits 0 — the
     * outer exits nonzero (the FAILED record), the final report lists
     * the record, and the final proof passes. */
    return real_mode_entry_case("reject",
                                DEALPG4_OUTER_EXIT_GATE_FAILURE,
                                "OUTER record 1 FAILED tag "
                                "MALFORMED_INVOKE\n",
                                "OUTER final 1 ");
}

static int integration_zero_fn(void)
{
    /* The real mode entry, zero records: no DONE (asserted by the
     * peer), the clean exit 0. */
    return real_mode_entry_case("ok-no-done", 0, NULL,
                                "OUTER final 0 ");
}

/* === Main ============================================================== */

static void run_group(const char *name, outer_test_fn fn)
{
    char errbuf[4096];
    int status;

    if (run_capture_child(fn, errbuf, sizeof errbuf, &status) != 0) {
        fprintf(stderr, "FAIL: helper child machinery broke (%s)\n",
                name);
        return;
    }
    gate_group(name, errbuf, status);
}

int main(int argc, char **argv)
{
    g_suite_argv0 = argv[0];
    if (argc >= 2 && strcmp(argv[1], "--registry-peer") == 0)
        return registry_peer_entry(argc, argv);

    /* Group 1: the INVOKE semantic split (per-case capture
     * children). */
    {
        char errbuf[2048];
        int status;

        if (run_capture_child(semantic_split_fn, errbuf,
                              sizeof errbuf, &status) != 0) {
            fprintf(stderr, "FAIL: helper machinery broke\n");
            return 1;
        }
        gate_group("semantic split", errbuf, status);
    }
    /* Group 1b: the INVOKE framing split. */
    {
        char errbuf[2048];
        int status;

        if (run_capture_child(framing_split_fn, errbuf, sizeof errbuf,
                              &status) != 0) {
            fprintf(stderr, "FAIL: helper machinery broke\n");
            return 1;
        }
        gate_group("framing split", errbuf, status);
    }

    /* Group 2: the pre-fork rejection battery. */
    run_group("T-floor BUDGET_EXHAUSTED", floor_case_fn);
    run_group("FORK_FAILED (composition)", fork_fail_composition_fn);
    run_group("NONCE_FAILED (site)", nonce_failed_case_fn);
    run_group("FORK_FAILED (socketpair site)",
              socketpair_fail_case_fn);
    run_group("FORK_FAILED (fork site)", fork_fail_site_case_fn);
    run_group("REGISTRY_FULL at 128 live records",
              registry_full_case_fn);

    /* Group 3: register-before-fork and the serve surface. */
    run_group("register-before-fork + serve surface",
              register_before_fork_fn);
    run_group("broker EOF mid-INVOKE", mid_invoke_eof_fn);

    /* Group 4: ACK/CANCEL record-level validation. */
    run_group("ACK/CANCEL validation", ack_cancel_validation_fn);
    run_group("BYE in BROKER_LIVE", bye_in_live_fn);

    /* Group 5: the cutoff-anchored DONE trigger. */
    run_group("DONE cycle (cutoff-anchored)", done_cycle_fn);
    run_group("post-cutoff INVOKE before DONE", post_cutoff_invoke_fn);
    run_group("empty registry: no DONE", empty_registry_fn);

    /* Group 6: integration through the real mode entry. */
    run_group("real mode entry: REJECT + report",
              integration_reject_fn);
    run_group("real mode entry: zero-record clean exit",
              integration_zero_fn);

    if (g_failures > 0) {
        fprintf(stderr, "outer-registry-tests: %d checks, %d FAILED\n",
                g_checks, g_failures);
        return 1;
    }
    printf("outer-registry-tests: %d checks passed\n", g_checks);
    return 0;
}
