/*
 * DEALPG4 outer broker component acceptance suite
 * (tools/test/outer-broker-tests.c).
 *
 * The component-level cases of ISSUE-0295 Verification, run against
 * tools/src/outer.c + monotonic.c + protocol.c + fi.c + selftest.c +
 * drain.c compiled with the pinned flags (tools/test/
 * run-outer-broker-tests.sh). Permanent and re-runnable; lives
 * outside the tools/src/ build glob so the pinned artifact build is
 * unchanged. The suite drives the real core (T1's preamble, loop,
 * write-side discipline, and exit-status mapping + T2's coordinator
 * machinery) with scripted coordinator argv (the suite binary
 * re-execs itself as the broker peer) and scaled OuterLimits — real
 * socket/bind/listen/accept/SO_PEERCRED/nonce machinery only, no
 * canned frame responses.
 *
 * Case groups:
 *  1. socket mechanics (the real bind-path surface): first-run
 *     clean — the 0700 bind dir is created by the bind surface
 *     itself on a scratch name (asserted, then removed), and the
 *     stale-pre-bind dir is created in-setup so a clean checkout
 *     passes without depending on a prior run's leftover state; a
 *     pre-created stale socket file at the same path is unlinked
 *     before bind (bind succeeds), the socket path mode is 0600 and
 *     the dir 0700 after bind, the listen socket is O_NONBLOCK|
 *     FD_CLOEXEC, the fork+exec fd probe reports both the listen
 *     socket and the accepted connection closed across exec
 *     (CLOEXEC), SO_PEERCRED accepts the real connector's pid/uid
 *     and rejects a pid or uid mismatch with EACCES before any
 *     record;
 *  2. full handshake success through the core: the scripted
 *     coordinator connects, asserts the socket/dir modes from the
 *     coordinator side, HELLO -> HELLO_OK 4 31 -> FEATURE_READY ->
 *     READY_ACK <nonce> -> close, exits 0 — the outer exits 0, no
 *     escalation, the final proof completes, the socket path is
 *     unlinked, no survivor;
 *  3. authentication: a helper child that is not the coordinator
 *     connects -> SO_PEERCRED pid mismatch -> AUTH_FAILED close, gate
 *     nonzero; a wrong HELLO nonce -> AUTH_FAILED;
 *  4. handshake channel state: a non-HELLO record in AWAIT_HELLO,
 *     FEATURE_READY before HELLO_OK, a FEATURE_READY nonce mismatch,
 *     and BYE in BROKER_LIVE -> PROTOCOL_ERROR close (the live-phase
 *     INVOKE/ACK/CANCEL rows landed with the registry child);
 *  5. uniform framing-level split: bad hex, odd-length hex,
 *     field-count violations, CR, unknown record types, a bad prefix,
 *     an oversize HELLO line, and a >131072-byte unterminated stream
 *     each -> PROTOCOL_ERROR close (the size caps enforced per the
 *     grammar);
 *  6. second connection after the first is rejected: the first
 *     channel keeps its handshake; the second connection is accepted
 *     and closed without a read;
 *  7. stall plumbing (scaled brokerStallMs 200): FI_CONGEST_BROKER /
 *     BROKER_WRITE_STALL (always-on) — the queued HELLO_OK cannot be
 *     written, the stall deadline arms at now + brokerStallMs
 *     (observed), firing with data pending -> BROKER_STALLED, the D8
 *     escalation terminates the coordinator, gate nonzero, the final
 *     proof completes; the oneshot composition — the peer resumes
 *     reading, the queue drains, the stall timer disarms without
 *     firing, clean exit 0;
 *  8. PROTOCOL_ERROR aftermath: the broker closes, the coordinator
 *     reaps on its own (exit 0) or is terminated at the pinned
 *     escalation deadline; gate nonzero; final proof completes;
 *     socket unlinked; no survivor;
 *  9. AUTH_FAILED aftermath: the ppoll loop keeps running until the
 *     coordinator reaps on its own or is terminated at the pinned
 *     escalation deadline;
 * 10. FI_OUTER_BIND: the broker bind fails -> BROKER_BIND_FAILED, no
 *     coordinator fork, the final proof passes;
 * 11. integration (T1+T2+T3): the real mode entry of the committed
 *     launcher binary runs a scripted coordinator (the suite re-exec)
 *     that reads DEALPG4_BROKER_PATH / DEALPG4_NONCE from its
 *     environment, completes the handshake, closes with zero records,
 *     and exits 0 — the outer reaps status 0, runs the final proof,
 *     unlinks the socket path, and exits 0.
 *
 * Every group's internal assertion failures propagate through the
 * helper child's exit status and the captured stderr — the suite
 * gates on the propagated results; the multi-process cases (groups
 * 3/6/11) run the core in a dedicated child and transport the result
 * view through a RESULT line over a pipe.
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
static const char *g_suite_argv0; /* argv[0] of the suite binary — the
                                     scripted coordinator/peer re-exec
                                     and the fd probes use it */

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

/* The scaled limits every deadline-driven case uses: overall 10000,
 * readiness 500, nestedStop 8000, cleanup 2000, brokerStallMs 200 —
 * the stall deadline lands at now + 200 and the pinned escalation
 * deadline at T0o + 10000 - 5000 = T0o + 5000 with the 2000 ms grace
 * completing inside the budget. */
static const OuterLimits SCALED = {10000, 500, 8000, 2000, 200};

/* HOLD_LIMITS: the two cases that deliberately hold the broker
 * channel pre-handshake (the second-connection hold and the stall
 * disarm pause) run under a 4000 ms readiness window — the pinned
 * FEATURE_READY bound (D5/D9) would otherwise fire READINESS_TIMEOUT
 * at T0o + 500 while the scenario is still holding the handshake.
 * The escalation deadline (10000 - 5000 = T0o + 5000) stays beyond
 * the ~1 s handshake completion, so both runs end in the clean exit
 * before the pinned deadline. */
static const OuterLimits HOLD_LIMITS = {10000, 4000, 8000, 2000, 200};

/* Socket-path scan: the outer's broker socket path prefix under the
 * scratch socket dir ("build" relative to the runner CWD). Returns the
 * count of matching entries (0 when the directory does not exist). */
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

/* No-child assertion: waitid(P_ALL) must reach ECHILD — the process
 * has no children, so the tested path forked nothing (or reaped
 * everything). */
static void check_no_children(void)
{
    siginfo_t si;

    memset(&si, 0, sizeof si);
    CHECK(waitid(P_ALL, 0, &si, WEXITED | WNOHANG | WNOWAIT) == -1
          && errno == ECHILD);
}

/* Monotonic-bounded sleep (a timeout never extends anything). */
static void sleep_ms(unsigned ms)
{
    struct timespec ts;
    struct timespec rem;

    ts.tv_sec = (time_t)(ms / 1000);
    ts.tv_nsec = (long)(ms % 1000) * 1000000L;
    while (nanosleep(&ts, &rem) != 0 && errno == EINTR)
        ts = rem;
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
        return -1; /* EPIPE = the outer closed early */
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
            return -1; /* EOF */
        }
        if (errno == ECONNRESET) {
            buf[off] = '\0';
            return -1; /* the peer closed with unread data (the
                          AUTH_FAILED / second-connection rejection
                          close): the channel is closed */
        }
        if (errno == EINTR)
            continue;
        return -5;
    }
}

/* The ok handshake sequence (with the coordinator-side socket/dir mode
 * assertions when stat_check is set). */
static int peer_do_handshake(int fd, const char *path, const char *nonce,
                             int stat_check)
{
    char line[256];
    char cmd[128];
    int n;

    if (stat_check) {
        struct stat sb;
        char dir[PATH_MAX];
        const char *slash = strrchr(path, '/');
        size_t dlen = slash != NULL ? (size_t)(slash - path) : 0;

        if (dlen == 0 || dlen >= sizeof dir)
            return 1;
        memcpy(dir, path, dlen);
        dir[dlen] = '\0';
        if (stat(path, &sb) != 0 || !S_ISSOCK(sb.st_mode)
            || (sb.st_mode & 0777) != 0600)
            return 1;
        if (stat(dir, &sb) != 0 || !S_ISDIR(sb.st_mode)
            || (sb.st_mode & 0777) != 0700)
            return 1;
        printf("PEER stat sock=%o dir=%o\n", 0600, 0700);
        fflush(stdout);
    }

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

/* One scripted coordinator scenario. Returns the exit status. */
static int broker_peer_main(const char *scenario, const char *arg)
{
    const char *path = getenv("DEALPG4_BROKER_PATH");
    const char *nonce = getenv("DEALPG4_NONCE");
    char line[256];
    char cmd[256];
    int fd;
    int n;

    if (strcmp(scenario, "absent-sleep") == 0) {
        /* Never connects: sleeps, exits 0 (the auth cases use it as
         * the coordinator while a helper connector owns the broker
         * attempt). */
        sleep_ms(2000);
        return 0;
    }
    if (path == NULL || nonce == NULL) {
        fprintf(stderr, "PEER FAIL missing-env\n");
        return 1;
    }
    if (strcmp(scenario, "ok") == 0 || strcmp(scenario, "hold-ok") == 0
        || strcmp(scenario, "stall-ok") == 0) {
        fd = peer_connect(path);
        if (fd < 0) {
            fprintf(stderr, "PEER FAIL connect\n");
            return 1;
        }
        if (strcmp(scenario, "hold-ok") == 0) {
            /* Hold the channel for 1000 ms before the handshake (the
             * second-connection case). The marker file proves the
             * connect(2) completed, so the connector's connection is
             * deterministically second in the accept backlog. */
            int marker = creat("build/.dealpg4-hold-peer-connected", 0600);

            if (marker >= 0)
                close(marker);
            sleep_ms(1000);
        }
        if (strcmp(scenario, "stall-ok") == 0) {
            /* HELLO, then pause before reading — the oneshot
             * congestion keeps the HELLO_OK queued-but-unwritten for
             * one flush attempt; the queue drains once the seam
             * falls through, and the stall timer disarms without
             * firing. */
            n = snprintf(cmd, sizeof cmd, "DEALPG4 HELLO %s\n", nonce);
            if (n <= 0 || (size_t)n >= sizeof cmd
                || peer_write_all(fd, cmd, (size_t)n) != 0) {
                fprintf(stderr, "PEER FAIL hello\n");
                return 1;
            }
            sleep_ms(500);
            if (peer_read_line(fd, line, sizeof line, 3000) != 0
                || strcmp(line, "DEALPG4 HELLO_OK 4 31\n") != 0) {
                fprintf(stderr, "PEER FAIL hello-ok\n");
                return 1;
            }
            n = snprintf(cmd, sizeof cmd, "DEALPG4 FEATURE_READY %s\n",
                         nonce);
            if (n <= 0 || (size_t)n >= sizeof cmd
                || peer_write_all(fd, cmd, (size_t)n) != 0
                || peer_read_line(fd, line, sizeof line, 3000) != 0
                || memcmp(line, "DEALPG4 READY_ACK ", 18) != 0
                || memcmp(line + 18, nonce, 32) != 0) {
                fprintf(stderr, "PEER FAIL ready-ack\n");
                return 1;
            }
            close(fd);
            printf("PEER ok\n");
            fflush(stdout);
            return 0;
        }
        if (peer_do_handshake(fd, path, nonce, 1) != 0) {
            fprintf(stderr, "PEER FAIL handshake\n");
            return 1;
        }
        close(fd);
        printf("PEER ok\n");
        fflush(stdout);
        return 0;
    }
    if (strcmp(scenario, "silent-close") == 0) {
        fd = peer_connect(path);
        if (fd < 0) {
            fprintf(stderr, "PEER FAIL connect\n");
            return 1;
        }
        close(fd); /* no HELLO: EOF as the channel event */
        printf("PEER closed\n");
        fflush(stdout);
        return 0;
    }
    if (strcmp(scenario, "wrong-hello") == 0
        || strcmp(scenario, "wrong-hello-hang") == 0) {
        fd = peer_connect(path);
        if (fd < 0) {
            fprintf(stderr, "PEER FAIL connect\n");
            return 1;
        }
        n = snprintf(cmd, sizeof cmd, "DEALPG4 HELLO %s\n", BAD_NONCE);
        if (n <= 0 || (size_t)n >= sizeof cmd
            || peer_write_all(fd, cmd, (size_t)n) != 0
            || peer_read_line(fd, line, sizeof line, 3000) != -1) {
            fprintf(stderr, "PEER FAIL no-eof\n");
            return 1;
        }
        close(fd);
        printf("PEER eof\n");
        fflush(stdout);
        if (strcmp(scenario, "wrong-hello-hang") == 0) {
            /* The AUTH_FAILED aftermath: the outer keeps the ppoll
             * loop; the hanging coordinator is terminated at the
             * pinned escalation deadline. */
            sleep_ms(30000);
        }
        return 0;
    }
    if (strcmp(scenario, "non-hello") == 0) {
        fd = peer_connect(path);
        if (fd < 0) {
            fprintf(stderr, "PEER FAIL connect\n");
            return 1;
        }
        if (peer_write_all(fd, "DEALPG4 BYE\n", 12) != 0
            || peer_read_line(fd, line, sizeof line, 3000) != -1) {
            fprintf(stderr, "PEER FAIL no-eof\n");
            return 1;
        }
        close(fd);
        printf("PEER eof\n");
        fflush(stdout);
        return 0;
    }
    if (strcmp(scenario, "ready-first") == 0
        || strcmp(scenario, "proto-exit") == 0
        || strcmp(scenario, "proto-hang") == 0) {
        fd = peer_connect(path);
        if (fd < 0) {
            fprintf(stderr, "PEER FAIL connect\n");
            return 1;
        }
        n = snprintf(cmd, sizeof cmd, "DEALPG4 FEATURE_READY %s\n",
                     nonce);
        if (n <= 0 || (size_t)n >= sizeof cmd
            || peer_write_all(fd, cmd, (size_t)n) != 0
            || peer_read_line(fd, line, sizeof line, 3000) != -1) {
            fprintf(stderr, "PEER FAIL no-eof\n");
            return 1;
        }
        printf("PEER saw-eof\n");
        fflush(stdout);
        if (strcmp(scenario, "proto-hang") == 0)
            sleep_ms(30000); /* the PROTOCOL_ERROR aftermath: escalated
                                at the pinned escalation deadline */
        close(fd);
        return 0;
    }
    if (strcmp(scenario, "ready-bad-nonce") == 0) {
        fd = peer_connect(path);
        if (fd < 0) {
            fprintf(stderr, "PEER FAIL connect\n");
            return 1;
        }
        /* Half handshake: HELLO -> HELLO_OK leaves the channel in
         * BROKER_AWAIT_READY — the bad-nonce FEATURE_READY then
         * arrives in that state and is PROTOCOL_ERROR. */
        n = snprintf(cmd, sizeof cmd, "DEALPG4 HELLO %s\n", nonce);
        if (n <= 0 || (size_t)n >= sizeof cmd
            || peer_write_all(fd, cmd, (size_t)n) != 0
            || peer_read_line(fd, line, sizeof line, 3000) != 0
            || strcmp(line, "DEALPG4 HELLO_OK 4 31\n") != 0) {
            fprintf(stderr, "PEER FAIL hello-ok\n");
            return 1;
        }
        n = snprintf(cmd, sizeof cmd, "DEALPG4 FEATURE_READY %s\n",
                     BAD_NONCE);
        if (n <= 0 || (size_t)n >= sizeof cmd
            || peer_write_all(fd, cmd, (size_t)n) != 0
            || peer_read_line(fd, line, sizeof line, 3000) != -1) {
            fprintf(stderr, "PEER FAIL no-eof\n");
            return 1;
        }
        close(fd);
        printf("PEER eof\n");
        fflush(stdout);
        return 0;
    }
    if (strcmp(scenario, "framing") == 0) {
        fd = peer_connect(path);
        if (fd < 0) {
            fprintf(stderr, "PEER FAIL connect\n");
            return 1;
        }
        /* Half handshake only: HELLO -> HELLO_OK leaves the channel
         * in BROKER_AWAIT_READY — the scripted framing defect then
         * arrives in that state and must close the channel. */
        n = snprintf(cmd, sizeof cmd, "DEALPG4 HELLO %s\n", nonce);
        if (n <= 0 || (size_t)n >= sizeof cmd
            || peer_write_all(fd, cmd, (size_t)n) != 0
            || peer_read_line(fd, line, sizeof line, 3000) != 0
            || strcmp(line, "DEALPG4 HELLO_OK 4 31\n") != 0) {
            fprintf(stderr, "PEER FAIL hello-ok\n");
            return 1;
        }
        if (arg == NULL || peer_write_all(fd, arg, strlen(arg)) != 0
            || peer_read_line(fd, line, sizeof line, 3000) != -1) {
            fprintf(stderr, "PEER FAIL no-eof\n");
            return 1;
        }
        close(fd);
        printf("PEER eof\n");
        fflush(stdout);
        return 0;
    }
    if (strcmp(scenario, "live-record") == 0) {
        fd = peer_connect(path);
        if (fd < 0) {
            fprintf(stderr, "PEER FAIL connect\n");
            return 1;
        }
        if (peer_do_handshake(fd, path, nonce, 0) != 0) {
            fprintf(stderr, "PEER FAIL handshake\n");
            return 1;
        }
        /* BROKER_LIVE expects INVOKE / ACK / CANCEL: BYE is
         * state-unexpected (the frame pins DONE -> BYE) and closes
         * per the D5 PROTOCOL_ERROR rule. */
        if (peer_write_all(fd, "DEALPG4 BYE\n", 12) != 0
            || peer_read_line(fd, line, sizeof line, 3000) != -1) {
            fprintf(stderr, "PEER FAIL no-eof\n");
            return 1;
        }
        close(fd);
        printf("PEER eof\n");
        fflush(stdout);
        return 0;
    }
    if (strcmp(scenario, "oversize-hello") == 0) {
        static char big[DEALPG4_MAX_LINE_OTHER_BYTES + 2048];
        size_t len;

        fd = peer_connect(path);
        if (fd < 0) {
            fprintf(stderr, "PEER FAIL connect\n");
            return 1;
        }
        memcpy(big, "DEALPG4 HELLO ", 14);
        memset(big + 14, 'a', DEALPG4_MAX_LINE_OTHER_BYTES + 1024);
        big[14 + DEALPG4_MAX_LINE_OTHER_BYTES + 1024] = '\n';
        len = 14 + DEALPG4_MAX_LINE_OTHER_BYTES + 1024 + 1;
        if (peer_write_all(fd, big, len) != 0
            || peer_read_line(fd, line, sizeof line, 3000) != -1) {
            fprintf(stderr, "PEER FAIL no-eof\n");
            return 1;
        }
        close(fd);
        printf("PEER eof\n");
        fflush(stdout);
        return 0;
    }
    if (strcmp(scenario, "oversize-stream") == 0) {
        static char big[DEALPG4_MAX_LINE_INVOKE_BYTES + 70000];

        fd = peer_connect(path);
        if (fd < 0) {
            fprintf(stderr, "PEER FAIL connect\n");
            return 1;
        }
        memset(big, 'a', sizeof big);
        (void)peer_write_all(fd, big, sizeof big); /* may EPIPE once
                                                      the outer closes
                                                      at the read-side
                                                      cap */
        if (peer_read_line(fd, line, sizeof line, 3000) != -1) {
            fprintf(stderr, "PEER FAIL no-eof\n");
            return 1;
        }
        close(fd);
        printf("PEER eof\n");
        fflush(stdout);
        return 0;
    }
    if (strcmp(scenario, "stall-hang") == 0) {
        fd = peer_connect(path);
        if (fd < 0) {
            fprintf(stderr, "PEER FAIL connect\n");
            return 1;
        }
        n = snprintf(cmd, sizeof cmd, "DEALPG4 HELLO %s\n", nonce);
        if (n <= 0 || (size_t)n >= sizeof cmd
            || peer_write_all(fd, cmd, (size_t)n) != 0) {
            fprintf(stderr, "PEER FAIL hello\n");
            return 1;
        }
        /* Never read again: with the always-on BROKER_WRITE_STALL
         * congestion the outer's HELLO_OK stays queued-but-unwritten,
         * the stall deadline fires, and the D8 escalation TERMs this
         * peer (the default TERM disposition kills it). */
        sleep_ms(30000);
        return 0;
    }
    fprintf(stderr, "PEER FAIL unknown-scenario %s\n", scenario);
    return 1;
}

/* The scripted-peer re-exec entry (the coordinator argv shape is
 * "--broker-peer <scenario> [arg]"). */
static int broker_peer_entry(int argc, char **argv)
{
    /* SIGPIPE ignored: a write racing the outer's close (the
     * oversize-stream case closes at the read-side cap while the peer
     * is still writing) surfaces as EPIPE — never a signal death. */
    signal(SIGPIPE, SIG_IGN);
    if (argc < 3) {
        fprintf(stderr, "PEER FAIL missing-scenario\n");
        return 1;
    }
    return broker_peer_main(argv[2], argc >= 4 ? argv[3] : NULL);
}

/* The fork+exec fd probe: "--fd-probe <fd> <closed|open>" — reports
 * whether the fd survived the exec (CLOEXEC closes it) and exits 0
 * iff the observation matches the expectation. */
static int fd_probe_entry(int argc, char **argv)
{
    int fd;
    int open_;

    if (argc < 4) {
        fprintf(stderr, "FD_PROBE FAIL shape\n");
        return 1;
    }
    fd = atoi(argv[2]);
    open_ = (fcntl(fd, F_GETFD) != -1);
    printf("FD_PROBE fd=%d open=%d\n", fd, open_);
    fflush(stdout);
    if (strcmp(argv[3], "closed") == 0 && !open_)
        return 0;
    if (strcmp(argv[3], "open") == 0 && open_)
        return 0;
    return 1;
}

/* === Helper-child machinery ============================================ */

typedef int (*outer_test_fn)(void);

/* Run fn in a fresh helper child with its stderr captured into
 * errbuf; the child's exit status lands in *status. Returns 0 on
 * success, -1 on pipe/fork failure. The helper child is not a process
 * group leader, so the core's preamble setsid() succeeds. */
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
        break; /* EOF (or error): the child exited */
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

/* One full-core call in a pipe-backed report fd: run the core in the
 * current (helper) process with a fresh report pipe, drain it, and
 * fill *report. Returns the core status. */
static int core_with_report(const OuterLimits *limits,
                            char *const coordinator_argv[],
                            char *report, size_t report_cap)
{
    int pipefd[2];
    ssize_t total = 0;
    int status;

    CHECK(pipe(pipefd) == 0);
    status = dealpg4_outer_core(limits, NONCE, coordinator_argv,
                                "build", pipefd[1], NULL);
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

/* Find needle inside the drain's retained window (the window is not
 * NUL-terminated; the search is bounded by retained_len). */
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

/* The peer argv of one scripted scenario. */
static void peer_argv(char *argv[8], const char *scenario,
                      const char *arg)
{
    size_t n = 0;

    argv[n++] = (char *)g_suite_argv0;
    argv[n++] = (char *)"--broker-peer";
    argv[n++] = (char *)scenario;
    if (arg != NULL)
        argv[n++] = (char *)arg;
    argv[n] = NULL;
}

/* === Core-child machinery (multi-process cases) ======================== */

/* Run the core in this (child) process with a pipe-backed report fd
 * and emit one RESULT line with the observable facts over
 * result_fd. Returns the core status. */
static int core_child(const OuterLimits *limits, char *const argv[],
                      int result_fd)
{
    int devnull = open("/dev/null", O_WRONLY);
    dealpg4_outer_result view;
    char toks[DEALPG4_OUTER_MAX_TOKENS * (DEALPG4_OUTER_TOKEN_BYTES + 1)];
    char line[1024];
    size_t i;
    int status;
    int n;

    if (devnull == -1)
        return 125;
    status = dealpg4_outer_core(limits, NONCE, argv, "build", devnull,
                                NULL);
    close(devnull);

    memset(&view, 0, sizeof view);
    dealpg4_outer_last_result(&view);
    toks[0] = '\0';
    for (i = 0; i < view.ntokens; i++) {
        if (i > 0)
            strncat(toks, ",",
                    sizeof toks - strlen(toks) - 1);
        strncat(toks, view.tokens[i],
                sizeof toks - strlen(toks) - 1);
    }
    if (view.ntokens == 0)
        strcpy(toks, "-");
    n = snprintf(line, sizeof line,
                 "RESULT status=%d state=%d peer=%d conn=%d hello=%d "
                 "ready=%d eof=%d closed=%d unlinked=%d rejected=%d "
                 "created=%d stall_armed=%d stall_fired=%d "
                 "stall_arm_ms=%lld stall_deadline_ms=%lld proof=%d "
                 "escal_term=%d escal_group=%d reaped=%d exited0=%d "
                 "si_code=%d si_status=%d tokens=%s\n",
                 status, view.broker_state, view.broker_peer_verified,
                 view.broker_conn_accepted, view.broker_hello_ok_sent,
                 view.broker_ready_acked, view.broker_eof,
                 view.broker_closed, view.broker_socket_unlinked,
                 view.broker_conns_rejected, view.broker_created,
                 view.broker_stall_armed, view.broker_stall_fired,
                 (long long)view.broker_stall_arm_ms,
                 (long long)view.broker_stall_deadline_ms,
                 view.proof_passed, view.escalation_term_issued,
                 view.escalation_group_scope, view.coordinator_reaped,
                 view.coordinator_exited_0, view.coordinator_si_code,
                 view.coordinator_si_status, toks);
    if (n > 0 && (size_t)n < sizeof line) {
        ssize_t w = write(result_fd, line, (size_t)n);

        (void)w;
    }
    /* The peer's stderr (the coordinator stream drain) — diagnosis
     * surface for the multi-process cases. */
    {
        const dealpg4_drain_ctx *dout = NULL;
        const dealpg4_drain_ctx *derr = NULL;

        dealpg4_outer_drain_state(&dout, &derr);
        (void)dout;
        if (derr != NULL && derr->retained_len > 0) {
            ssize_t w = write(result_fd, derr->retained,
                              derr->retained_len);

            (void)w;
        }
    }
    return status & 0xff;
}

/* Parse one int field of a RESULT line ("key=<value>"). */
static int res_int(const char *line, const char *key, int *out)
{
    char pat[64];
    const char *p;

    snprintf(pat, sizeof pat, "%s=", key);
    p = strstr(line, pat);
    if (p == NULL)
        return 0;
    *out = atoi(p + strlen(pat));
    return 1;
}

/* 1 when the RESULT line's token list holds token. */
static int res_has_token(const char *line, const char *token)
{
    const char *p = strstr(line, "tokens=");
    const char *q;
    size_t tlen = strlen(token);

    if (p == NULL)
        return 0;
    p += strlen("tokens=");
    for (;;) {
        q = strchr(p, ',');
        if (q == NULL)
            return strncmp(p, token, tlen) == 0
                   && (p[tlen] == '\0' || p[tlen] == '\n');
        if ((size_t)(q - p) == tlen && strncmp(p, token, tlen) == 0)
            return 1;
        p = q + 1;
    }
}

/* Fork a core child (RESULT over a pipe) without waiting — the
 * multi-process cases run a concurrent helper connector between the
 * fork and the reap. */
static pid_t fork_core_child(const OuterLimits *limits,
                             char *const argv[], int *res_rd)
{
    int pipefd[2];
    pid_t pid;

    CHECK(pipe(pipefd) == 0);
    pid = fork();
    CHECK(pid >= 0);
    if (pid == 0) {
        close(pipefd[0]);
        _exit(core_child(limits, argv, pipefd[1]) & 0xff);
    }
    close(pipefd[1]);
    *res_rd = pipefd[0];
    return pid;
}

/* Reap a forked core child and fill the result line + status. */
static void reap_core_child(pid_t pid, int rd, char *res,
                            size_t res_cap, int *status)
{
    size_t off = 0;

    for (;;) {
        ssize_t r = read(rd, res + off, res_cap - off - 1);

        if (r > 0) {
            off += (size_t)r;
            continue;
        }
        if (r < 0 && errno == EINTR)
            continue;
        break;
    }
    close(rd);
    res[off] = '\0';
    CHECK(waitpid(pid, status, 0) == pid);
    *status = WIFEXITED(*status) ? WEXITSTATUS(*status) : -1;
}

/* The helper connector (a child of the suite main process, never of
 * the core-running process): scans "build" for the broker socket path
 * (bounded), waits delay_ms after finding it, connects, optionally
 * sends HELLO, then expects EOF within 2000 ms. Reports over
 * report_fd. */
static int connector_child(int report_fd, int delay_ms, int send_hello,
                          const char *hold_marker)
{
    char path[PATH_MAX];
    uint64_t deadline = dealpg4_now_ms() + 5000;
    int found = 0;
    int fd;

    /* SIGPIPE ignored: a write racing the outer's close (the
     * pid-mismatch AUTH_FAILED close happens without a read) is
     * observed as EPIPE — the closed-channel outcome — never a signal
     * death. */
    signal(SIGPIPE, SIG_IGN);
    while (dealpg4_now_ms() < deadline) {
        DIR *d = opendir("build");
        struct dirent *e;

        if (d != NULL) {
            while ((e = readdir(d)) != NULL) {
                if (strncmp(e->d_name, ".dealpg4-broker-", 16) == 0) {
                    snprintf(path, sizeof path, "build/%s", e->d_name);
                    found = 1;
                    break;
                }
            }
            closedir(d);
        }
        if (found)
            break;
        sleep_ms(10);
    }
    if (!found) {
        dprintf(report_fd, "CONN fail=no-path\n");
        return 1;
    }
    /* The second-connection case waits for the coordinator peer's
     * connect marker: the peer's connection is then already in the
     * accept backlog, so the connector's connection is deterministically
     * the second one. */
    if (hold_marker != NULL) {
        while (access(hold_marker, F_OK) != 0
               && dealpg4_now_ms() < deadline)
            sleep_ms(10);
        if (access(hold_marker, F_OK) != 0) {
            dprintf(report_fd, "CONN fail=no-marker\n");
            return 1;
        }
    }
    if (delay_ms > 0)
        sleep_ms((unsigned)delay_ms);
    fd = peer_connect(path);
    if (fd < 0) {
        dprintf(report_fd, "CONN fail=connect\n");
        return 1;
    }
    if (send_hello) {
        char cmd[64];
        int n = snprintf(cmd, sizeof cmd, "DEALPG4 HELLO %s\n", NONCE);

        if (n <= 0 || (size_t)n >= sizeof cmd) {
            dprintf(report_fd, "CONN fail=write\n");
            return 1;
        }
        if (peer_write_all(fd, cmd, (size_t)n) != 0) {
            /* EPIPE: the outer closed the channel before the write
             * landed — the rejection surface the pid-mismatch case
             * asserts (AUTH_FAILED decides without a read). Any other
             * write failure is a machinery break. */
            if (errno == EPIPE) {
                dprintf(report_fd, "CONN eof=1\n");
                close(fd);
                return 0;
            }
            dprintf(report_fd, "CONN fail=write\n");
            return 1;
        }
    }
    {
        struct pollfd pfd;
        int rc;
        char c;
        ssize_t r;

        pfd.fd = fd;
        pfd.events = POLLIN;
        pfd.revents = 0;
        for (;;) {
            rc = poll(&pfd, 1, 2000);
            if (rc < 0 && errno == EINTR)
                continue;
            break;
        }
        if (rc <= 0) {
            dprintf(report_fd, "CONN fail=timeout\n");
            return 1;
        }
        r = read(fd, &c, 1);
        if (r == 0 || (r < 0 && errno == ECONNRESET)) {
            /* EOF (FIN) or the rejection reset (the outer closed
             * without draining — the AUTH_FAILED / second-connection
             * close): the channel is closed. */
            dprintf(report_fd, "CONN eof=1\n");
            close(fd);
            return 0;
        }
        dprintf(report_fd, "CONN fail=data\n");
        return 1;
    }
}

/* Fork the connector, read its report, and gate it. */
static void run_connector_case(int delay_ms, int send_hello,
                               const char *hold_marker)
{
    int pipefd[2];
    pid_t pid;
    char buf[128];
    size_t off = 0;
    int st;

    CHECK(pipe(pipefd) == 0);
    pid = fork();
    CHECK(pid >= 0);
    if (pid == 0) {
        close(pipefd[0]);
        _exit(connector_child(pipefd[1], delay_ms, send_hello,
                              hold_marker) & 0xff);
    }
    close(pipefd[1]);
    for (;;) {
        ssize_t r = read(pipefd[0], buf + off, sizeof buf - off - 1);

        if (r > 0) {
            off += (size_t)r;
            continue;
        }
        if (r < 0 && errno == EINTR)
            continue;
        break;
    }
    close(pipefd[0]);
    buf[off] = '\0';
    CHECK(waitpid(pid, &st, 0) == pid);
    if (!(WIFEXITED(st) && WEXITSTATUS(st) == 0)
        || strstr(buf, "CONN eof=1") == NULL)
        fprintf(stderr, "connector report: %s (status 0x%x)\n", buf, st);
    CHECK(WIFEXITED(st) && WEXITSTATUS(st) == 0);
    CHECK(strstr(buf, "CONN eof=1") != NULL);
}

/* === Group 1: socket mechanics (the real bind-path surface) ============ */

/* The fork+exec fd probe: the exec'd child reports whether the fd
 * survived (CLOEXEC closes it) and exits 0 iff it matches want. */
static void fd_probe(int fd, const char *want)
{
    int pipefd[2];
    pid_t pid;
    char buf[128];
    char fdstr[32];
    size_t off = 0;
    int st;

    CHECK(pipe(pipefd) == 0);
    snprintf(fdstr, sizeof fdstr, "%d", fd);
    pid = fork();
    CHECK(pid >= 0);
    if (pid == 0) {
        close(pipefd[0]);
        if (dup2(pipefd[1], 1) == -1)
            _exit(125);
        close(pipefd[1]);
        execl(g_suite_argv0, g_suite_argv0, "--fd-probe", fdstr, want,
              NULL);
        _exit(127);
    }
    close(pipefd[1]);
    for (;;) {
        ssize_t r = read(pipefd[0], buf + off, sizeof buf - off - 1);

        if (r > 0) {
            off += (size_t)r;
            continue;
        }
        if (r < 0 && errno == EINTR)
            continue;
        break;
    }
    close(pipefd[0]);
    buf[off] = '\0';
    CHECK(waitpid(pid, &st, 0) == pid);
    CHECK(WIFEXITED(st) && WEXITSTATUS(st) == 0);
    CHECK(strstr(buf, "FD_PROBE ") != NULL);
    CHECK(strstr(buf, strcmp(want, "closed") == 0
                          ? " open=0"
                          : " open=1") != NULL);
}

/* Fork a hold connector (connects to path, sleeps hold_ms, exits). */
static pid_t fork_hold_connector(const char *path, unsigned hold_ms)
{
    pid_t pid = fork();

    CHECK(pid >= 0);
    if (pid == 0) {
        int fd = peer_connect(path);

        if (fd < 0)
            _exit(1);
        sleep_ms(hold_ms);
        close(fd);
        _exit(0);
    }
    return pid;
}

/* Accept-with-retry: the connector needs a beat to land in the
 * backlog. Returns the accept_peer rc of the first non-EAGAIN
 * attempt. */
static int accept_peer_retry(int listen_fd, pid_t want_pid,
                             uid_t want_uid, int *conn_fd)
{
    uint64_t deadline = dealpg4_now_ms() + 2000;
    int rc = -1;

    for (;;) {
        rc = dealpg4_outer_broker_accept_peer(listen_fd, want_pid,
                                              want_uid, conn_fd);
        if (rc == 0)
            return 0;
        if (errno == EAGAIN || errno == EWOULDBLOCK) {
            if (dealpg4_now_ms() >= deadline)
                break;
            sleep_ms(5);
            continue;
        }
        break; /* EACCES or a genuine accept failure */
    }
    return rc;
}

static int socket_mechanics_fn(void)
{
    char path[256];
    char fresh_dir[64];
    char fresh_path[256];
    int listen_fd = -1;
    int fresh_fd = -1;
    int conn_fd = -1;
    struct stat sb;
    pid_t conn_pid;
    int st;
    int fl;

    /* Fresh-dir creation: the bind surface must create the 0700
     * socket dir itself when it does not exist. This group runs first
     * on a clean checkout and the main path below pre-creates "build"
     * for the stale pre-bind, so the creation path is asserted here
     * on a scratch dir name (removed afterwards — no leftover state).
     */
    {
        snprintf(fresh_dir, sizeof fresh_dir, "build-fresh-%d",
                 (int)getpid());
        CHECK(stat(fresh_dir, &sb) != 0 && errno == ENOENT);
        CHECK(dealpg4_outer_broker_bind_path(fresh_dir, NONCE,
                                             fresh_path,
                                             sizeof fresh_path,
                                             &fresh_fd) == 0);
        CHECK(stat(fresh_dir, &sb) == 0 && S_ISDIR(sb.st_mode));
        CHECK((sb.st_mode & 0777) == 0700);
        CHECK(stat(fresh_path, &sb) == 0 && S_ISSOCK(sb.st_mode));
        CHECK((sb.st_mode & 0777) == 0600);
        close(fresh_fd);
        CHECK(unlink(fresh_path) == 0);
        CHECK(rmdir(fresh_dir) == 0);
    }

    /* The stale same-name socket: pre-created at the exact path the
     * bind surface will use — bind must unlink it first and succeed.
     * First-run cleanliness (the suite must pass 90/90 on a clean
     * checkout without depending on a prior run's leftover state):
     * the bind dir does not exist yet, so it is created 0700 before
     * the stale pre-bind — the bind surface then takes its documented
     * EEXIST-tolerant path and chmods the dir 0700 itself. */
    {
        struct sockaddr_un sun;
        int stale;

        CHECK(mkdir("build", 0700) == 0 || errno == EEXIST);
        stale = socket(AF_UNIX, SOCK_STREAM, 0);
        CHECK(stale >= 0);
        memset(&sun, 0, sizeof sun);
        sun.sun_family = AF_UNIX;
        snprintf(sun.sun_path, sizeof sun.sun_path,
                 "build/.dealpg4-broker-%s.sock", NONCE);
        CHECK(bind(stale, (struct sockaddr *)&sun, sizeof sun) == 0);
        close(stale);
    }

    CHECK(dealpg4_outer_broker_bind_path("build", NONCE, path,
                                         sizeof path, &listen_fd) == 0);
    CHECK(strcmp(path, "build/.dealpg4-broker-"
                        "0123456789abcdef0123456789abcdef.sock") == 0);
    /* The stale socket was unlinked and rebound: the path is a live
     * socket with mode 0600 and the dir is 0700. */
    CHECK(stat(path, &sb) == 0);
    CHECK(S_ISSOCK(sb.st_mode));
    CHECK((sb.st_mode & 0777) == 0600);
    CHECK(stat("build", &sb) == 0 && S_ISDIR(sb.st_mode));
    CHECK((sb.st_mode & 0777) == 0700);
    /* The listen socket: O_NONBLOCK (accept never blocks) and
     * FD_CLOEXEC (never inherited across an exec). */
    fl = fcntl(listen_fd, F_GETFL);
    CHECK(fl != -1 && (fl & O_NONBLOCK) != 0);
    CHECK((fcntl(listen_fd, F_GETFD) & FD_CLOEXEC) != 0);
    fd_probe(listen_fd, "closed");

    /* The real connector: SO_PEERCRED accepts pid == connectorPid and
     * uid == getuid() before any record. */
    conn_pid = fork_hold_connector(path, 600);
    CHECK(accept_peer_retry(listen_fd, conn_pid, getuid(), &conn_fd)
          == 0);
    fl = fcntl(conn_fd, F_GETFL);
    CHECK(fl != -1 && (fl & O_NONBLOCK) != 0);
    CHECK((fcntl(conn_fd, F_GETFD) & FD_CLOEXEC) != 0);
    fd_probe(conn_fd, "closed");
    close(conn_fd);
    CHECK(waitpid(conn_pid, &st, 0) == conn_pid);
    CHECK(WIFEXITED(st) && WEXITSTATUS(st) == 0);

    /* EAGAIN when nothing is pending (the normal non-blocking
     * outcome). */
    conn_fd = -1;
    CHECK(dealpg4_outer_broker_accept_peer(listen_fd, 1, getuid(),
                                           &conn_fd) == -1
          && errno == EAGAIN);

    /* A pid mismatch: EACCES before any record. */
    conn_pid = fork_hold_connector(path, 400);
    conn_fd = -1;
    CHECK(accept_peer_retry(listen_fd, conn_pid + 1, getuid(), &conn_fd)
          == -1
          && errno == EACCES);
    CHECK(waitpid(conn_pid, &st, 0) == conn_pid);

    /* A uid mismatch: EACCES before any record. */
    conn_pid = fork_hold_connector(path, 400);
    conn_fd = -1;
    CHECK(accept_peer_retry(listen_fd, conn_pid, getuid() + 1, &conn_fd)
          == -1
          && errno == EACCES);
    CHECK(waitpid(conn_pid, &st, 0) == conn_pid);

    /* Argument validation: refuse bad surfaces without side effects. */
    CHECK(dealpg4_outer_broker_bind_path(NULL, NONCE, NULL, 0, &listen_fd)
          == -1
          && errno == EINVAL);
    CHECK(dealpg4_outer_broker_bind_path("", NONCE, NULL, 0, &listen_fd)
          == -1
          && errno == EINVAL);
    {
        char bad_nonce[33] = "zz";

        CHECK(dealpg4_outer_broker_bind_path("build", bad_nonce, NULL, 0,
                                             &listen_fd) == -1
              && errno == EINVAL);
    }
    CHECK(dealpg4_outer_broker_accept_peer(-1, 1, getuid(), &conn_fd)
          == -1
          && errno == EINVAL);

    /* Cleanup: the path is unlinked. */
    close(listen_fd);
    CHECK(unlink(path) == 0);
    CHECK(stat(path, &sb) != 0 && errno == ENOENT);
    return (g_failures > 0) ? 1 : 0;
}

/* === Group 2: full handshake success through the core ================== */

static int core_success_fn(void)
{
    char *argv[8];
    char report[1024];
    dealpg4_outer_result view;
    const dealpg4_drain_ctx *dout = NULL;
    const dealpg4_drain_ctx *derr = NULL;
    uint64_t before;
    uint64_t after;
    int status;

    peer_argv(argv, "ok", "0");
    before = dealpg4_now_ms();
    status = core_with_report(&SCALED, argv, report, sizeof report);
    after = dealpg4_now_ms();

    CHECK(status == 0);
    CHECK(after - before < 8000);

    memset(&view, 0, sizeof view);
    dealpg4_outer_last_result(&view);
    CHECK(view.gate_failure == 0);
    CHECK(view.exit_status == 0);
    CHECK(view.ntokens == 0);
    /* The real channel: bound, accepted, verified, handshake
     * completed, EOF observed, closed, unlinked. */
    CHECK(view.broker_created == 1);
    CHECK(view.broker_conn_accepted == 1);
    CHECK(view.broker_peer_verified == 1);
    CHECK(view.broker_hello_ok_sent == 1);
    CHECK(view.broker_ready_acked == 1);
    CHECK(view.broker_state == DEALPG4_OUTER_BROKER_CLOSED);
    CHECK(view.broker_eof == 1);
    CHECK(view.broker_closed == 1);
    CHECK(view.broker_socket_unlinked == 1);
    CHECK(view.broker_conns_rejected == 0);
    CHECK(view.broker_stall_armed == 0); /* never congested */
    CHECK(view.broker_stall_fired == 0);
    /* Clean exit: no escalation, status 0, the final proof. */
    CHECK(view.coordinator_reaped == 1);
    CHECK(view.coordinator_exited_0 == 1);
    CHECK(view.escalation_term_issued == 0);
    CHECK(view.proof_passed == 1);
    CHECK(view.proof_broker_clean == 1);

    /* The coordinator-side mode assertions landed in the retained
     * stdout drain. */
    dealpg4_outer_drain_state(&dout, &derr);
    CHECK(dout != NULL && derr != NULL);
    CHECK(dout->eof == 1 && dout->failed == 0);
    CHECK(find_in_retained(dout, "PEER stat sock=600 dir=700\n")
          != NULL);
    CHECK(find_in_retained(dout, "PEER ok\n") != NULL);

    CHECK(strstr(report, "OUTER final 0 ") != NULL);
    CHECK(strstr(report, "OUTER proof ok\n") != NULL);
    CHECK(strstr(report, "OUTER token ") == NULL);

    check_no_children();
    CHECK(outer_socket_path_count("build") == 0);
    return (g_failures > 0) ? 1 : 0;
}

/* === Group 3: authentication rejections ================================ */

/* SO_PEERCRED pid mismatch: the coordinator never connects; a helper
 * connector (forked from the suite main, not the core child) sends a
 * correct HELLO — the peer pid check fails first -> AUTH_FAILED. */
static int pid_mismatch_case(void)
{
    char *argv[8];
    char res[1024];
    int v = -1;
    int status;

    peer_argv(argv, "absent-sleep", NULL);
    {
        pid_t pid;
        int rd;

        pid = fork_core_child(&SCALED, argv, &rd);
        run_connector_case(0, 1 /* send HELLO */, NULL);
        reap_core_child(pid, rd, res, sizeof res, &status);
    }

    if (status != DEALPG4_OUTER_EXIT_GATE_FAILURE
        || !res_has_token(res, "AUTH_FAILED"))
        fprintf(stderr, "pid-mismatch RESULT: %s", res);
    CHECK(status == DEALPG4_OUTER_EXIT_GATE_FAILURE);
    CHECK(res_has_token(res, "AUTH_FAILED"));
    CHECK(res_int(res, "peer", &v) && v == 0);
    CHECK(res_int(res, "conn", &v) && v == 1);
    CHECK(res_int(res, "hello", &v) && v == 0);
    CHECK(res_int(res, "state", &v)
          && v == DEALPG4_OUTER_BROKER_CLOSED);
    CHECK(res_int(res, "closed", &v) && v == 1);
    CHECK(res_int(res, "unlinked", &v) && v == 1);
    CHECK(res_int(res, "proof", &v) && v == 1);
    /* The coordinator exited 0 on its own: never signaled. */
    CHECK(res_int(res, "escal_term", &v) && v == 0);
    CHECK(res_int(res, "exited0", &v) && v == 1);
    return 0;
}

/* Wrong HELLO nonce (the peer IS the coordinator: the SO_PEERCRED
 * check passes, the nonce fails). */
static int wrong_hello_fn(void)
{
    char *argv[8];
    char report[1024];
    dealpg4_outer_result view;
    uint64_t before;
    uint64_t after;
    int status;

    peer_argv(argv, "wrong-hello", NULL);
    before = dealpg4_now_ms();
    status = core_with_report(&SCALED, argv, report, sizeof report);
    after = dealpg4_now_ms();

    CHECK(status == DEALPG4_OUTER_EXIT_GATE_FAILURE);
    CHECK(after - before < 8000);

    memset(&view, 0, sizeof view);
    dealpg4_outer_last_result(&view);
    CHECK(has_token(&view, "AUTH_FAILED"));
    CHECK(view.broker_conn_accepted == 1);
    CHECK(view.broker_peer_verified == 1); /* pid/uid held */
    CHECK(view.broker_hello_ok_sent == 0);
    CHECK(view.broker_state == DEALPG4_OUTER_BROKER_CLOSED);
    CHECK(view.broker_closed == 1);
    CHECK(view.broker_socket_unlinked == 1);
    CHECK(view.coordinator_exited_0 == 1); /* the peer exited 0 */
    CHECK(view.escalation_term_issued == 0);
    CHECK(view.proof_passed == 1);
    CHECK(strstr(report, "OUTER token AUTH_FAILED\n") != NULL);

    check_no_children();
    CHECK(outer_socket_path_count("build") == 0);
    return (g_failures > 0) ? 1 : 0;
}

/* AUTH_FAILED aftermath: the hanging coordinator is terminated at the
 * pinned escalation deadline (the ppoll loop keeps running). */
static int wrong_hello_hang_fn(void)
{
    char *argv[8];
    char report[1024];
    dealpg4_outer_result view;
    uint64_t before;
    uint64_t after;
    int status;

    peer_argv(argv, "wrong-hello-hang", NULL);
    before = dealpg4_now_ms();
    status = core_with_report(&SCALED, argv, report, sizeof report);
    after = dealpg4_now_ms();

    CHECK(status == DEALPG4_OUTER_EXIT_GATE_FAILURE);
    CHECK(after - before >= 4900); /* the pinned escalation deadline */
    CHECK(after - before < 8000);

    memset(&view, 0, sizeof view);
    dealpg4_outer_last_result(&view);
    CHECK(has_token(&view, "AUTH_FAILED"));
    CHECK(has_token(&view, "COORDINATOR_HANG")); /* the pinned-deadline
                                                    escalation */
    CHECK(view.broker_closed == 1);
    CHECK(view.escalation_term_issued == 1);
    CHECK(view.escalation_group_scope == 1);
    CHECK(view.coordinator_reaped == 1);
    CHECK(view.coordinator_si_code == CLD_KILLED);
    CHECK(view.coordinator_si_status == SIGTERM);
    CHECK(view.proof_passed == 1);
    CHECK(view.broker_socket_unlinked == 1);

    check_no_children();
    CHECK(outer_socket_path_count("build") == 0);
    return (g_failures > 0) ? 1 : 0;
}

/* === Group 4: handshake channel-state rejections ======================= */

/* One PROTOCOL_ERROR case (one core call per fresh capture child):
 * the scripted peer observes the close and exits 0; the gate is the
 * PROTOCOL_ERROR token. */
static int protocol_error_case(const char *scenario, const char *arg,
                               int hello_expected, int ready_expected)
{
    char *argv[8];
    char report[1024];
    dealpg4_outer_result view;
    uint64_t before;
    uint64_t after;
    int status;

    peer_argv(argv, scenario, arg);
    before = dealpg4_now_ms();
    status = core_with_report(&SCALED, argv, report, sizeof report);
    after = dealpg4_now_ms();

    CHECK(status == DEALPG4_OUTER_EXIT_GATE_FAILURE);
    CHECK(after - before < 8000);

    memset(&view, 0, sizeof view);
    dealpg4_outer_last_result(&view);
    CHECK(has_token(&view, "PROTOCOL_ERROR"));
    CHECK(view.broker_peer_verified == 1);
    CHECK(view.broker_state == DEALPG4_OUTER_BROKER_CLOSED);
    CHECK(view.broker_closed == 1);
    CHECK(view.broker_socket_unlinked == 1);
    CHECK(view.broker_hello_ok_sent == (hello_expected ? 1 : 0));
    CHECK(view.broker_ready_acked == (ready_expected ? 1 : 0));
    CHECK(view.coordinator_exited_0 == 1);
    CHECK(view.escalation_term_issued == 0); /* reaped on its own */
    CHECK(view.proof_passed == 1);
    CHECK(strstr(report, "OUTER token PROTOCOL_ERROR\n") != NULL);

    check_no_children();
    CHECK(outer_socket_path_count("build") == 0);
    return (g_failures > 0) ? 1 : 0;
}

/* The channel-state cases, one capture child per core call (each
 * fresh process gets its own preamble setsid()). */
typedef struct protocol_case_desc {
    const char *scenario;
    const char *arg;
    int hello_expected;
    int ready_expected;
    const char *name;
} protocol_case_desc;

static const protocol_case_desc channel_state_cases[] = {
    /* A well-formed non-HELLO record in BROKER_AWAIT_HELLO. */
    {"non-hello", NULL, 0, 0, "non-HELLO in AWAIT_HELLO"},
    /* FEATURE_READY before HELLO_OK. */
    {"ready-first", NULL, 0, 0, "FEATURE_READY before HELLO_OK"},
    /* FEATURE_READY with a wrong nonce after HELLO_OK. */
    {"ready-bad-nonce", NULL, 1, 0, "FEATURE_READY nonce mismatch"},
    /* BYE in BROKER_LIVE (state-unexpected — the live phase expects
     * INVOKE / ACK / CANCEL and the frame pins DONE -> BYE; the
     * INVOKE live-phase rows landed with the registry child). */
    {"live-record", NULL, 1, 1, "BYE in BROKER_LIVE"},
};

/* === Group 5: the uniform framing-level split ========================== */

/* The per-case capture-child dispatcher: the case is selected through
 * the global below before each run_capture_child fork (the child
 * inherits the selection). */
static const protocol_case_desc *g_current_case;

static int protocol_error_case_entry(void)
{
    if (g_current_case == NULL)
        return 1;
    return protocol_error_case(g_current_case->scenario,
                               g_current_case->arg,
                               g_current_case->hello_expected,
                               g_current_case->ready_expected);
}

/* The framing cases, one capture child per core call. */
static const protocol_case_desc framing_cases[] = {
    {"framing", "DEALPG4 BOGUS\n", 1, 0, "unknown record type"},
    {"framing",
     "DEALPG4 HELLO zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz\n", 1, 0,
     "non-hex nonce"},
    {"framing", "DEALPG4 HELLO abc\n", 1, 0, "odd-length hex"},
    {"framing", "DEALPG4 HELLO 0123456789abcdef0123456789abcdef "
                "extra\n", 1, 0, "field-count violation"},
    {"framing", "DEALPG4 HELLO 0123456789abcdef0123456789abcdef\r\n",
     1, 0, "CR anywhere"},
    {"framing", "GARBAGE\n", 1, 0, "bad prefix"},
    /* The per-type size cap (HELLO <= 8192 bytes). */
    {"oversize-hello", NULL, 0, 0, "oversize HELLO line"},
    /* The global record bound on the receive path: a > 131072-byte
     * unterminated stream closes without buffering past the cap. */
    {"oversize-stream", NULL, 0, 0, "oversize unterminated stream"},
};

/* === Group 6: the second connection is rejected ======================== */

static int second_connection_case(void)
{
    char *argv[8];
    char res[1024];
    int v = -1;
    int status;

    /* The coordinator peer holds the one connection for 1000 ms and
     * then completes the handshake; the helper connector connects
     * after a 400 ms settle (the peer's connection is first) and must
     * be rejected — accepted and closed without a read. */
    peer_argv(argv, "hold-ok", NULL);
    {
        pid_t pid;
        int rd;

        pid = fork_core_child(&HOLD_LIMITS, argv, &rd);
        run_connector_case(0, 0 /* no HELLO — rejected immediately */,
                           "build/.dealpg4-hold-peer-connected");
        reap_core_child(pid, rd, res, sizeof res, &status);
        (void)unlink("build/.dealpg4-hold-peer-connected");
    }

    if (status != 0 || !res_has_token(res, "-"))
        fprintf(stderr, "second-connection RESULT: %s", res);
    CHECK(status == 0); /* the first channel completed cleanly */
    CHECK(res_int(res, "rejected", &v) && v >= 1);
    CHECK(res_int(res, "ready", &v) && v == 1);
    CHECK(res_int(res, "proof", &v) && v == 1);
    CHECK(res_int(res, "unlinked", &v) && v == 1);
    CHECK(!res_has_token(res, "AUTH_FAILED"));
    CHECK(!res_has_token(res, "PROTOCOL_ERROR"));
    return 0;
}

/* === Group 7: the stall rule =========================================== */

static const int congest_targets[] = { FI_CONGEST_BROKER };
static const int congest_modes[] = { BROKER_WRITE_STALL };
static const int broker_fail_sites[] = {
    FI_OUTER_SUBREAPER, FI_OUTER_TIMERFD, FI_OUTER_SIGNALFD,
    FI_OUTER_NONCE, FI_OUTER_PIPE, FI_COORD_READY_MISMATCH,
    FI_OUTER_BIND
};
static const dealpg4_fi_catalog broker_catalog = {
    NULL, 0, broker_fail_sites, 7, congest_targets, 1, congest_modes, 1
};

/* The always-on congestion: the queued HELLO_OK can never be written;
 * the stall deadline arms at now + brokerStallMs and fires with data
 * still pending -> BROKER_STALLED, the D8 escalation terminates the
 * coordinator, gate nonzero, the final proof completes. */
static int stall_fire_fn(void)
{
    dealpg4_fi_script_congest congest[1];
    dealpg4_fi_script script;
    char *argv[8];
    char report[1024];
    dealpg4_outer_result view;
    uint64_t before;
    uint64_t after;
    int status;

    congest[0].target = FI_CONGEST_BROKER;
    congest[0].mode = BROKER_WRITE_STALL;
    congest[0].oneshot = 0;
    script.delays = NULL;
    script.ndelays = 0;
    script.fails = NULL;
    script.nfails = 0;
    script.congests = congest;
    script.ncongests = 1;
    CHECK(dealpg4_fi_install_overrides(&script, &broker_catalog) == 0);

    peer_argv(argv, "stall-hang", NULL);
    before = dealpg4_now_ms();
    status = core_with_report(&SCALED, argv, report, sizeof report);
    after = dealpg4_now_ms();
    dealpg4_fi_restore_defaults();

    CHECK(status == DEALPG4_OUTER_EXIT_GATE_FAILURE);
    CHECK(after - before >= 150); /* the stall deadline elapsed */
    CHECK(after - before < 4000);

    memset(&view, 0, sizeof view);
    dealpg4_outer_last_result(&view);
    CHECK(has_token(&view, "BROKER_STALLED"));
    CHECK(view.broker_stall_armed == 1);
    CHECK(view.broker_stall_fired == 1);
    /* The armed deadline is exactly now + brokerStallMs (observed). */
    CHECK(view.broker_stall_deadline_ms
          == view.broker_stall_arm_ms + SCALED.brokerStallMs);
    /* The D8 escalation terminated the coordinator (the verified
     * group scope), the proof completed, nothing fired the total
     * deadline. */
    CHECK(view.escalation_term_issued == 1);
    CHECK(view.escalation_group_scope == 1);
    CHECK(view.coordinator_reaped == 1);
    CHECK(view.coordinator_si_code == CLD_KILLED);
    CHECK(view.coordinator_si_status == SIGTERM);
    CHECK(view.proof_passed == 1);
    CHECK(view.total_fired == 0);
    CHECK(view.broker_closed == 1);
    CHECK(view.broker_socket_unlinked == 1);
    CHECK(strstr(report, "OUTER token BROKER_STALLED\n") != NULL);

    CHECK(dealpg4_fi_hooks.congest(FI_CONGEST_BROKER, BROKER_WRITE_STALL,
                                   NULL) == 0); /* defaults restored */

    check_no_children();
    CHECK(outer_socket_path_count("build") == 0);
    return (g_failures > 0) ? 1 : 0;
}

/* The oneshot composition: the first flush attempt stalls (the stall
 * deadline arms), the seam then falls through, the peer resumes
 * reading, the queue drains, and the stall timer disarms without
 * firing — clean exit 0. */
static int stall_disarm_fn(void)
{
    dealpg4_fi_script_congest congest[1];
    dealpg4_fi_script script;
    char *argv[8];
    char report[1024];
    dealpg4_outer_result view;
    uint64_t before;
    uint64_t after;
    int status;

    congest[0].target = FI_CONGEST_BROKER;
    congest[0].mode = BROKER_WRITE_STALL;
    congest[0].oneshot = 1;
    script.delays = NULL;
    script.ndelays = 0;
    script.fails = NULL;
    script.nfails = 0;
    script.congests = congest;
    script.ncongests = 1;
    CHECK(dealpg4_fi_install_overrides(&script, &broker_catalog) == 0);

    peer_argv(argv, "stall-ok", NULL);
    before = dealpg4_now_ms();
    status = core_with_report(&HOLD_LIMITS, argv, report, sizeof report);
    after = dealpg4_now_ms();
    dealpg4_fi_restore_defaults();

    CHECK(status == 0);
    CHECK(after - before < 8000);

    memset(&view, 0, sizeof view);
    dealpg4_outer_last_result(&view);
    CHECK(view.ntokens == 0);
    CHECK(view.broker_stall_armed == 1); /* armed, then disarmed */
    CHECK(view.broker_stall_fired == 0);
    CHECK(view.broker_ready_acked == 1);
    CHECK(view.coordinator_exited_0 == 1);
    CHECK(view.escalation_term_issued == 0);
    CHECK(view.proof_passed == 1);
    CHECK(view.broker_socket_unlinked == 1);

    check_no_children();
    CHECK(outer_socket_path_count("build") == 0);
    return (g_failures > 0) ? 1 : 0;
}

/* === Group 8: PROTOCOL_ERROR aftermath ================================= */

/* The coordinator reaps on its own (exit 0) after the close. */
static int proto_exit_fn(void)
{
    char *argv[8];
    char report[1024];
    dealpg4_outer_result view;
    const dealpg4_drain_ctx *dout = NULL;
    uint64_t before;
    uint64_t after;
    int status;

    peer_argv(argv, "proto-exit", NULL);
    before = dealpg4_now_ms();
    status = core_with_report(&SCALED, argv, report, sizeof report);
    after = dealpg4_now_ms();

    CHECK(status == DEALPG4_OUTER_EXIT_GATE_FAILURE);
    CHECK(after - before < 8000);

    memset(&view, 0, sizeof view);
    dealpg4_outer_last_result(&view);
    CHECK(has_token(&view, "PROTOCOL_ERROR"));
    CHECK(view.coordinator_exited_0 == 1);
    CHECK(view.escalation_term_issued == 0); /* reaped on its own */
    CHECK(view.proof_passed == 1);
    CHECK(view.broker_closed == 1);
    CHECK(view.broker_socket_unlinked == 1);

    dealpg4_outer_drain_state(&dout, NULL);
    CHECK(dout != NULL);
    CHECK(find_in_retained(dout, "PEER saw-eof\n") != NULL);

    check_no_children();
    CHECK(outer_socket_path_count("build") == 0);
    return (g_failures > 0) ? 1 : 0;
}

/* The coordinator hangs after the close: terminated at the pinned
 * escalation deadline, gate nonzero, the final proof completes. */
static int proto_hang_fn(void)
{
    char *argv[8];
    char report[1024];
    dealpg4_outer_result view;
    uint64_t before;
    uint64_t after;
    int status;

    peer_argv(argv, "proto-hang", NULL);
    before = dealpg4_now_ms();
    status = core_with_report(&SCALED, argv, report, sizeof report);
    after = dealpg4_now_ms();

    CHECK(status == DEALPG4_OUTER_EXIT_GATE_FAILURE);
    CHECK(after - before >= 4900);
    CHECK(after - before < 8000);

    memset(&view, 0, sizeof view);
    dealpg4_outer_last_result(&view);
    CHECK(has_token(&view, "PROTOCOL_ERROR"));
    CHECK(has_token(&view, "COORDINATOR_HANG")); /* the escalation at
                                                    the pinned
                                                    deadline */
    CHECK(view.escalation_term_issued == 1);
    CHECK(view.escalation_group_scope == 1);
    CHECK(view.coordinator_reaped == 1);
    CHECK(view.coordinator_si_code == CLD_KILLED);
    CHECK(view.coordinator_si_status == SIGTERM);
    CHECK(view.proof_passed == 1);
    CHECK(view.total_fired == 0);
    CHECK(view.broker_closed == 1);
    CHECK(view.broker_socket_unlinked == 1);

    check_no_children();
    CHECK(outer_socket_path_count("build") == 0);
    return (g_failures > 0) ? 1 : 0;
}

/* === Group 9: FI_OUTER_BIND ============================================ */

static int bind_failure_fn(void)
{
    dealpg4_fi_script_fail fails[1];
    dealpg4_fi_script script;
    char *argv[] = {(char *)"/bin/true", NULL};
    char report[1024];
    dealpg4_outer_result view;
    uint64_t before;
    uint64_t after;
    int status;

    fails[0].site = FI_OUTER_BIND;
    fails[0].value = EADDRINUSE;
    fails[0].oneshot = 1;
    script.delays = NULL;
    script.ndelays = 0;
    script.fails = fails;
    script.nfails = 1;
    script.congests = NULL;
    script.ncongests = 0;
    CHECK(dealpg4_fi_install_overrides(&script, &broker_catalog) == 0);

    before = dealpg4_now_ms();
    status = core_with_report(&SCALED, argv, report, sizeof report);
    after = dealpg4_now_ms();
    dealpg4_fi_restore_defaults();

    CHECK(status == DEALPG4_OUTER_EXIT_GATE_FAILURE);
    CHECK(after - before < 3000);

    memset(&view, 0, sizeof view);
    dealpg4_outer_last_result(&view);
    CHECK(has_token(&view, "BROKER_BIND_FAILED"));
    /* No fork: the coordinator pid was never attached, the broker was
     * never created, the final proof still passes. */
    CHECK(view.coordinator_pid == -1);
    CHECK(view.coordinator_reaped == 0);
    CHECK(view.broker_created == 0);
    CHECK(view.proof_passed == 1);
    CHECK(view.proof_broker_clean == 1);

    CHECK(strstr(report, "OUTER token BROKER_BIND_FAILED\n") != NULL);

    CHECK(dealpg4_fi_hooks.fail(FI_OUTER_BIND) == 0);

    check_no_children();
    CHECK(outer_socket_path_count("build") == 0);
    return (g_failures > 0) ? 1 : 0;
}

/* === Group 10: EOF before any record (the D8 discrimination slot) ====== */

/* A zero-record coordinator connects, closes without HELLO, and exits
 * 0: EOF is the accepted channel event; with no live records the
 * reaped status 0 proceeds through the final proof (the full
 * clean-exit/COORDINATOR_LOST classification lands with the
 * final-sequence child). */
static int silent_close_fn(void)
{
    char *argv[8];
    char report[1024];
    dealpg4_outer_result view;
    uint64_t before;
    uint64_t after;
    int status;

    peer_argv(argv, "silent-close", NULL);
    before = dealpg4_now_ms();
    status = core_with_report(&SCALED, argv, report, sizeof report);
    after = dealpg4_now_ms();

    CHECK(status == 0);
    CHECK(after - before < 8000);

    memset(&view, 0, sizeof view);
    dealpg4_outer_last_result(&view);
    CHECK(view.ntokens == 0);
    CHECK(view.broker_conn_accepted == 1);
    CHECK(view.broker_peer_verified == 1);
    CHECK(view.broker_eof == 1);
    CHECK(view.broker_closed == 1);
    CHECK(view.broker_socket_unlinked == 1);
    CHECK(view.coordinator_exited_0 == 1);
    CHECK(view.escalation_term_issued == 0);
    CHECK(view.proof_passed == 1);

    check_no_children();
    CHECK(outer_socket_path_count("build") == 0);
    return (g_failures > 0) ? 1 : 0;
}

/* === Group 11: integration (T1+T2+T3 through the real mode entry) ====== */

static int integration_case(void)
{
    char *launcher_argv[] = {
        (char *)"./deal-process-launcher-linux-x86_64",
        (char *)"outer", (char *)NONCE, (char *)"--",
        (char *)g_suite_argv0, (char *)"--broker-peer", (char *)"ok",
        (char *)"0", NULL};
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
                break; /* the launcher closed its report stdout */
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
            /* The embedded-limits run must never hang the suite: a
             * regression is killed and fails the case. */
            kill(pid, SIGKILL);
            (void)waitpid(pid, &st, 0);
            out[off] = '\0';
            CHECK(0 && "integration case exceeded the 30 s bound");
            close(pipefd[0]);
            return 1;
        }
    }
    close(pipefd[0]);
    out[off] = '\0';
    if (!reaped)
        CHECK(waitpid(pid, &st, 0) == pid);
    CHECK(WIFEXITED(st) && WEXITSTATUS(st) == 0);
    CHECK(dealpg4_now_ms() - start < 30000);

    /* The real mode entry: the clean exit, the final proof, no gate
     * token, and the socket path unlinked. */
    CHECK(strstr(out, "OUTER final 0 ") != NULL);
    CHECK(strstr(out, "OUTER proof ok\n") != NULL);
    CHECK(strstr(out, "OUTER token ") == NULL);
    CHECK(outer_socket_path_count("build") == 0);
    check_no_children();
    return 0;
}

/* === Main ============================================================== */

int main(int argc, char **argv)
{
    char errbuf[1024];
    int status;

    g_suite_argv0 = argv[0];
    if (argc >= 2 && strcmp(argv[1], "--broker-peer") == 0)
        return broker_peer_entry(argc, argv);
    if (argc >= 2 && strcmp(argv[1], "--fd-probe") == 0)
        return fd_probe_entry(argc, argv);

    /* Group 1: socket mechanics (the real bind-path surface). */
    errbuf[0] = '\0';
    if (run_capture_child(socket_mechanics_fn, errbuf, sizeof errbuf,
                          &status) != 0) {
        fprintf(stderr, "FAIL: helper child machinery broke\n");
        return 1;
    }
    gate_group("socket mechanics", errbuf, status);

    /* Group 2: full handshake success through the core. */
    errbuf[0] = '\0';
    if (run_capture_child(core_success_fn, errbuf, sizeof errbuf,
                          &status) != 0) {
        fprintf(stderr, "FAIL: helper child machinery broke\n");
        return 1;
    }
    gate_group("full handshake success", errbuf, status);

    /* Group 3a: SO_PEERCRED pid mismatch (multi-process). */
    if (pid_mismatch_case() != 0) {
        fprintf(stderr, "FAIL: pid mismatch case machinery broke\n");
        return 1;
    }

    /* Group 3b: wrong HELLO nonce. */
    errbuf[0] = '\0';
    if (run_capture_child(wrong_hello_fn, errbuf, sizeof errbuf,
                          &status) != 0) {
        fprintf(stderr, "FAIL: helper child machinery broke\n");
        return 1;
    }
    gate_group("wrong HELLO nonce", errbuf, status);

    /* Group 3c: AUTH_FAILED aftermath (~5.1 s). */
    errbuf[0] = '\0';
    if (run_capture_child(wrong_hello_hang_fn, errbuf, sizeof errbuf,
                          &status) != 0) {
        fprintf(stderr, "FAIL: helper child machinery broke\n");
        return 1;
    }
    gate_group("AUTH_FAILED aftermath", errbuf, status);

    /* Group 4: handshake channel-state rejections (one capture child
     * per case — each fresh process runs its own preamble setsid()). */
    {
        size_t ci;

        for (ci = 0;
             ci < sizeof channel_state_cases / sizeof channel_state_cases[0];
             ci++) {
            errbuf[0] = '\0';
            g_current_case = &channel_state_cases[ci];
            if (run_capture_child(protocol_error_case_entry, errbuf,
                                  sizeof errbuf, &status) != 0) {
                fprintf(stderr, "FAIL: helper child machinery broke\n");
                return 1;
            }
            gate_group(g_current_case->name, errbuf, status);
        }
        g_current_case = NULL;
    }

    /* Group 5: the uniform framing-level split (one capture child per
     * case). */
    {
        size_t ci;

        for (ci = 0;
             ci < sizeof framing_cases / sizeof framing_cases[0];
             ci++) {
            errbuf[0] = '\0';
            g_current_case = &framing_cases[ci];
            if (run_capture_child(protocol_error_case_entry, errbuf,
                                  sizeof errbuf, &status) != 0) {
                fprintf(stderr, "FAIL: helper child machinery broke\n");
                return 1;
            }
            gate_group(g_current_case->name, errbuf, status);
        }
        g_current_case = NULL;
    }

    /* Group 6: the second connection is rejected (multi-process,
     * ~1.2 s). */
    if (second_connection_case() != 0) {
        fprintf(stderr, "FAIL: second connection case machinery broke\n");
        return 1;
    }

    /* Group 7a: the stall rule firing (~0.3 s). */
    errbuf[0] = '\0';
    if (run_capture_child(stall_fire_fn, errbuf, sizeof errbuf,
                          &status) != 0) {
        fprintf(stderr, "FAIL: helper child machinery broke\n");
        return 1;
    }
    gate_group("stall rule firing", errbuf, status);

    /* Group 7b: the stall disarm leg (~0.6 s). */
    errbuf[0] = '\0';
    if (run_capture_child(stall_disarm_fn, errbuf, sizeof errbuf,
                          &status) != 0) {
        fprintf(stderr, "FAIL: helper child machinery broke\n");
        return 1;
    }
    gate_group("stall disarm", errbuf, status);

    /* Group 8: PROTOCOL_ERROR aftermath (reap + escalation legs). */
    errbuf[0] = '\0';
    if (run_capture_child(proto_exit_fn, errbuf, sizeof errbuf,
                          &status) != 0) {
        fprintf(stderr, "FAIL: helper child machinery broke\n");
        return 1;
    }
    gate_group("PROTOCOL_ERROR aftermath (reap)", errbuf, status);

    errbuf[0] = '\0';
    if (run_capture_child(proto_hang_fn, errbuf, sizeof errbuf,
                          &status) != 0) {
        fprintf(stderr, "FAIL: helper child machinery broke\n");
        return 1;
    }
    gate_group("PROTOCOL_ERROR aftermath (escalation)", errbuf, status);

    /* Group 9: FI_OUTER_BIND. */
    errbuf[0] = '\0';
    if (run_capture_child(bind_failure_fn, errbuf, sizeof errbuf,
                          &status) != 0) {
        fprintf(stderr, "FAIL: helper child machinery broke\n");
        return 1;
    }
    gate_group("FI_OUTER_BIND", errbuf, status);

    /* Group 10: EOF before any record. */
    errbuf[0] = '\0';
    if (run_capture_child(silent_close_fn, errbuf, sizeof errbuf,
                          &status) != 0) {
        fprintf(stderr, "FAIL: helper child machinery broke\n");
        return 1;
    }
    gate_group("EOF before any record", errbuf, status);

    /* Group 11: integration through the real mode entry (T1+T2+T3). */
    if (integration_case() != 0) {
        fprintf(stderr, "FAIL: integration case machinery broke\n");
        return 1;
    }

    if (g_failures > 0) {
        fprintf(stderr, "outer-broker-tests: %d/%d checks failed\n",
                g_failures, g_checks);
        return 1;
    }
    printf("outer-broker-tests: %d checks passed\n", g_checks);
    return 0;
}
