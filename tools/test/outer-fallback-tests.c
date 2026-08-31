/*
 * DEALPG4 outer per-state fallback / wedge rule / total-cancel
 * component acceptance suite (tools/test/outer-fallback-tests.c).
 *
 * The component-level cases of ISSUE-0298 Verification, run against
 * tools/src/outer.c + supervisor.c + monotonic.c + protocol.c + fi.c +
 * selftest.c + drain.c compiled with the pinned flags (tools/test/
 * run-outer-fallback-tests.sh). Permanent and re-runnable; lives
 * outside the tools/src/ build glob so the pinned artifact build is
 * unchanged. The suite drives the complete core (T1's preamble, loop,
 * write-side discipline + T2's coordinator machinery + T3's broker +
 * T4's registry/live phase/DONE + T5's channel machine + T6's
 * fallback executions) with a scripted coordinator peer (the suite
 * binary re-execs itself), scaled OuterLimits, and in-process
 * spawn-seam compositions whose forked children run the scripted
 * nested-channel scenarios — real setsid'd stubs with real
 * PID/PGID/session identities, real socketpair reads/writes, real
 * TERM/grace/KILL signals, real reaps to waitid, real /proc survivor
 * scans, no canned classifications.
 *
 * Case groups:
 *  1. Kill during STUB_BLOCKED (STUB_FORKED published, no verified
 *     STUB_READY): the outer closes the channels, TERM/grace/KILLs
 *     the retained stubPid, reaps everything — the record's single
 *     terminal answer is the synthesized CLEAN cancelled; RELEASED
 *     never entered; zero survivors; the command never started.
 *  2. Kill during TARGET_PUBLISHED (STUB_READY forwarded and
 *     verified): TERM/grace/KILL of the verified -pgid + the retained
 *     stubPid, group/session absence proven; synthesized terminal
 *     answer; zero survivors.
 *  3. Kill during RELEASED (the ACK write completed, a real released
 *     target runs with an escaped setsid descendant): the
 *     retained-identity takeover — verified TERM/KILL -pgid, the
 *     proof loop, the adopted-descendant scan with per-pid
 *     TERM-then-KILL, cleanupAcknowledged set; synthesized terminal
 *     answer; zero survivors (the escapee included).
 *  4. Kill mid-cancel (CANCELLING): the pre-cancel state's death
 *     fallback (STUB_BLOCKED), recovered from the ordered history.
 *  5. The wedge rule: a scripted child that never publishes a
 *     terminal record and never exits holds its record live to the
 *     per-record deadline; the outer verifies kill(supervisorPid, 0),
 *     TERMs by pid, graces termGraceMs, KILLs (re-verified), reaps to
 *     waitid, and completes the record through the FORKING death
 *     fallback — synthesized terminal answer, zero survivors, DONE
 *     queued at/after the cutoff.
 *  6. CANCEL validation over the live broker peer: wrong nonce /
 *     unknown id / terminal record each answered REJECT
 *     CANCEL_AUTH_FAILED with the broker open and the record
 *     untouched; a validated CANCEL relays with the exact registry
 *     nonce and the scripted child observes it byte-for-byte and
 *     applies it (nested-origin CLEAN cancelled relayed verbatim).
 *  7. The nested REJECT hold: a scripted child answering REJECT
 *     CANCEL_AUTH_FAILED to the fan-out CANCEL holds the record to
 *     its per-record deadline — the wedge applies there.
 *  8. Total-cancel — coordinator kill: the peer SIGKILLs itself while
 *     two records are live; both records turn CANCELLING in parallel,
 *     complete within the scaled reserve, zero survivors, gate
 *     nonzero (COORDINATOR_LOST).
 *  9. Total-cancel — the INVOKE cutoff: the live record is marked
 *     CANCELLING at T0o + nestedStopMs; the cutoff fan-out and the
 *     per-record deadline (identical by the D9 recipe) force the
 *     wedge completion; DONE clean is queued at/after the cutoff with
 *     the terminal answer preceding it; the peer BYEs; exit 0 (a
 *     clean cancelled record + the clean-exit discrimination).
 * 10. Total-cancel — shell loss (orphaned outer): the periodic
 *     getppid() check fires; the live record turns CANCELLING with
 *     the fan-out; the record completes; the deferred D8 escalation
 *     (the TERM waits for the record's terminality — the parent-D8
 *     precondition) terminates the hanging coordinator; zero
 *     survivors; gate nonzero (SHELL_LOST).
 * 11. Total-cancel — the closed report stdout (EPIPE at the final
 *     report): the run completes records through the cutoff wedge,
 *     escalates the hanging coordinator at the pinned escalation
 *     deadline, and the best-effort final report flips the gate on
 *     the EPIPE (SHELL_LOST).
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
#include "../src/supervisor.h"

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

/* Scaled OuterLimits. FB_LIMITS: nestedStopMs 17000 keeps the
 * delivered budget T >= 15000 for the first ~2 s; the per-record
 * deadline (registration + T) lands exactly at the cutoff (the D9
 * recipe) — the wedge trigger fires there and the 2 s TERM grace +
 * the proof complete inside the 21 s total (cleanupReserve 4000).
 * FB_CUT_LIMITS: a shorter cutoff (15500) with a 7500 ms reserve —
 * the escalation deadline (total - killAndProofReserveMs = 18000)
 * lands after the cutoff completion (~17.6 s), so the hanging
 * coordinator is TERMed only at the pinned COORDINATOR_HANG trigger.
 */
/* The wedge-dependent runs need the double grace window (the wedge
 * TERM->grace->KILL + the death fallback's TERM->grace->KILL = 4 s
 * after the per-record deadline) inside the total: cleanupReserve 8000
 * puts the hard bound at 25000 > 17000 + 4000 + proof. */
static const OuterLimits FB_LIMITS = {25000, 500, 17000, 8000, 200};
static const OuterLimits FB_CUT_LIMITS = {23000, 500, 15500, 7500, 200};

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

/* === Scripted nested-channel child ===================================== */

/* One complete record line to the control fd (blocking writes; the
 * outer's read side is non-blocking and always draining). */
static void chan_write_line(int fd, const char *line)
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
 * timeout. */
static int chan_read_line(int fd, char *buf, size_t cap, int timeout_ms)
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

/* Verify a relayed ACK/CANCEL byte-for-byte (the exact registry
 * nonce); exits nonzero on any deviation. */
static void chan_verify_ctl(int fd, const char *type, int64_t id,
                            const char *nonce)
{
    char buf[128];
    char want[128];
    int rc = chan_read_line(fd, buf, sizeof buf, 30000);

    if (rc != 0) {
        fprintf(stderr, "CHILD FAIL ctl-read-%s rc=%d\n", type, rc);
        _exit(10);
    }
    snprintf(want, sizeof want, "DEALPG4 %s %lld %s\n", type,
             (long long)id, nonce);
    if (strcmp(buf, want) != 0) {
        fprintf(stderr, "CHILD FAIL ctl-bytes-%s got=[%s] want=[%s]\n",
                type, buf, want);
        _exit(11);
    }
}

/* Fork a real setsid'd stub stand-in. With pdeathsig the stub sets
 * PR_SET_PDEATHSIG=SIGKILL (the production-faithful kernel cascade);
 * without it the stub survives its parent's death (the defective-stub
 * case the outer's retained-identity TERM/KILL exists for). With
 * release_fd >= 0 the stub blocks on the release byte and then, as
 * the released target root, forks one setsid escapee and stays alive
 * (the RELEASED-takeover scenario). Reports its pid/pgid/sid through
 * the identity pipe once setsid is confirmed. */
static pid_t fb_fork_stub(pid_t *pgid_out, pid_t *sid_out, int pdeathsig,
                          int release_fd)
{
    int idp[2];
    pid_t p;
    char buf[64];
    char *nl;

    if (pipe(idp) != 0)
        _exit(9);
    p = fork();
    if (p < 0)
        _exit(9);
    if (p == 0) {
        close(idp[0]);
        if (pdeathsig && prctl(PR_SET_PDEATHSIG, SIGKILL) != 0)
            _exit(3);
        if (setsid() == -1)
            _exit(3);
        if (getsid(0) != getpid() || getpgid(0) != getpid())
            _exit(3);
        snprintf(buf, sizeof buf, "%d %d %d\n", getpid(), getpgid(0),
                 getsid(0));
        chan_write_line(idp[1], buf);
        close(idp[1]);
        if (release_fd >= 0) {
            char c;

            for (;;) {
                ssize_t r = read(release_fd, &c, 1);

                if (r == 1)
                    break;
                if (r < 0 && errno == EINTR)
                    continue;
                _exit(5); /* pre-release EOF/error */
            }
            /* The released target root: fork one setsid escapee
             * (it survives the target root's own death — no
             * PDEATHSIG of its own) and stay alive. */
            {
                pid_t e = fork();

                if (e == 0) {
                    if (setsid() == -1)
                        _exit(3);
                    for (;;)
                        pause();
                }
            }
        }
        for (;;)
            pause(); /* the blocked stub / the released target */
    }
    close(idp[1]);
    for (;;) {
        ssize_t r = read(idp[0], buf, sizeof buf - 1);

        if (r > 0) {
            buf[r] = '\0';
            break;
        }
        if (r < 0 && errno == EINTR)
            continue;
        _exit(9); /* the stub died before confirming */
    }
    close(idp[0]);
    nl = strchr(buf, '\n');
    if (nl == NULL)
        _exit(9);
    *nl = '\0';
    if (sscanf(buf, "%d %d %d", (int *)&p, (int *)pgid_out,
               (int *)sid_out) != 3)
        _exit(9);
    return p;
}

/* One scripted nested-channel scenario (the serve-surface child).
 * Returns 0 on success (the child's own assertions); the outer reaps
 * the exit status as belt-and-braces — the record's terminality never
 * depends on it. */
static int fb_script_child(const char *scenario, int fd, int64_t id,
                           const char *nonce)
{
    char line[DEALPG4_MAX_LINE_OUT_BYTES + 64];
    pid_t stub;
    pid_t pgid;
    pid_t sid;

    if (scenario == NULL)
        return 1;

    if (strcmp(scenario, "hang") == 0) {
        /* Never publishes anything, never exits: the record holds
         * live until its per-record deadline (the wedge rule). */
        for (;;)
            pause();
    }

    if (strcmp(scenario, "die-after-stub-forked") == 0) {
        /* STUB_BLOCKED death: the stub (no PDEATHSIG — a defective
         * stub) survives the supervisor's death; the outer's
         * STUB_BLOCKED fallback TERM/grace/KILLs the retained
         * stubPid. */
        stub = fb_fork_stub(&pgid, &sid, 0 /* survives */, -1);
        snprintf(line, sizeof line, "DEALPG4 STUB_FORKED %lld %d\n",
                 (long long)id, (int)stub);
        chan_write_line(fd, line);
        return 0; /* the supervisor dies */
    }

    if (strcmp(scenario, "die-after-stub-ready") == 0) {
        /* TARGET_PUBLISHED death: the double-verified STUB_READY is
         * forwarded; the stub (no PDEATHSIG) survives; the outer's
         * TARGET_PUBLISHED fallback TERM/grace/KILLs the verified
         * -pgid + stubPid and proves group/session absence. */
        stub = fb_fork_stub(&pgid, &sid, 0 /* survives */, -1);
        snprintf(line, sizeof line, "DEALPG4 STUB_FORKED %lld %d\n",
                 (long long)id, (int)stub);
        chan_write_line(fd, line);
        snprintf(line, sizeof line,
                 "DEALPG4 STUB_READY %lld %d %d %d %s\n", (long long)id,
                 (int)stub, (int)pgid, (int)sid, nonce);
        chan_write_line(fd, line);
        sleep_ms(400); /* the forwarded STUB_READY reaches the peer */
        return 0;      /* the supervisor dies */
    }

    if (strcmp(scenario, "release-then-die") == 0) {
        /* RELEASED takeover: the stub (with PDEATHSIG — the
         * production-faithful kernel cascade) blocks on the release
         * byte; the ACK relay releases it; the released target root
         * forks one setsid escapee; STARTED publishes; then the
         * supervisor dies mid-cleanup — the target root dies via
         * PDEATHSIG, the escapee survives, and the outer's RELEASED
         * fallback (verified TERM/KILL -pgid + the adopted scan)
         * cleans both. */
        int rel[2];

        if (pipe(rel) != 0)
            _exit(9);
        stub = fb_fork_stub(&pgid, &sid, 1 /* PDEATHSIG */, rel[0]);
        close(rel[0]);
        snprintf(line, sizeof line, "DEALPG4 STUB_FORKED %lld %d\n",
                 (long long)id, (int)stub);
        chan_write_line(fd, line);
        snprintf(line, sizeof line,
                 "DEALPG4 STUB_READY %lld %d %d %d %s\n", (long long)id,
                 (int)stub, (int)pgid, (int)sid, nonce);
        chan_write_line(fd, line);
        /* The validated ACK relay (the exact registry nonce). */
        chan_verify_ctl(fd, "ACK", id, nonce);
        (void)!write(rel[1], "x", 1); /* release */
        close(rel[1]);
        snprintf(line, sizeof line, "DEALPG4 STARTED %lld\n",
                 (long long)id);
        chan_write_line(fd, line);
        return 0; /* the supervisor dies mid-cleanup */
    }

    if (strcmp(scenario, "cancel-then-die") == 0) {
        /* CANCELLING death: the record entered CANCELLING with the
         * pre-cancel state STUB_BLOCKED; the child observes the
         * relayed CANCEL (exact nonce) and dies without a terminal
         * record — the pre-cancel state's death fallback applies. */
        stub = fb_fork_stub(&pgid, &sid, 0 /* survives */, -1);
        snprintf(line, sizeof line, "DEALPG4 STUB_FORKED %lld %d\n",
                 (long long)id, (int)stub);
        chan_write_line(fd, line);
        chan_verify_ctl(fd, "CANCEL", id, nonce);
        return 0;
    }

    if (strcmp(scenario, "apply-cancel") == 0) {
        /* The conforming cancel path: the child applies the relayed
         * CANCEL (exact nonce) and publishes its own CLEAN cancelled
         * — relayed verbatim as the record's single terminal
         * answer. */
        stub = fb_fork_stub(&pgid, &sid, 1 /* PDEATHSIG */, -1);
        snprintf(line, sizeof line, "DEALPG4 STUB_FORKED %lld %d\n",
                 (long long)id, (int)stub);
        chan_write_line(fd, line);
        chan_verify_ctl(fd, "CANCEL", id, nonce);
        snprintf(line, sizeof line, "DEALPG4 CLEAN %lld cancelled\n",
                 (long long)id);
        chan_write_line(fd, line);
        return 0;
    }

    if (strcmp(scenario, "reject-then-hang") == 0) {
        /* The nested REJECT (a CANCEL_AUTH_FAILED answer to the
         * fan-out CANCEL) indicates supervisor defect: consumed,
         * never forwarded, no state change — the record is held to
         * its per-record deadline (the wedge applies there). */
        stub = fb_fork_stub(&pgid, &sid, 0 /* survives */, -1);
        snprintf(line, sizeof line, "DEALPG4 STUB_FORKED %lld %d\n",
                 (long long)id, (int)stub);
        chan_write_line(fd, line);
        chan_verify_ctl(fd, "CANCEL", id, nonce);
        snprintf(line, sizeof line,
                 "DEALPG4 REJECT %lld - CANCEL_AUTH_FAILED\n",
                 (long long)id);
        chan_write_line(fd, line);
        for (;;)
            pause();
    }

    fprintf(stderr, "CHILD FAIL unknown-scenario %s\n",
            scenario != NULL ? scenario : "?");
    return 1;
}

/* === Spawn-seam composition ============================================ */

typedef struct fb_spawn {
    int fork_calls;
    int64_t last_id;
    char last_nonce[DEALPG4_NONCE_HEX_CHARS + 1];
    pid_t last_pid;
    int last_control_fd;
} fb_spawn;

static int fb_fork_nested(const dealpg4_outer_spawn *self,
                          char *const serve_argv[],
                          const char nonce[33], int64_t budget_t,
                          int64_t invocation_id, int child_control_fd)
{
    fb_spawn *sp = (fb_spawn *)self->opaque;
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
    /* Peer scenarios that CANCEL need the exact registry nonce: the
     * composition (running in-process at fork time) publishes it. */
    if (scenario != NULL) {
        FILE *f = fopen("build/.fb-rec-nonce", "a");

        if (f != NULL) {
            fprintf(f, "%lld %s %s\n", (long long)invocation_id, nonce,
                    scenario);
            fclose(f);
        }
    }
    pid = fork();
    if (pid < 0) {
        errno = EAGAIN;
        return -1;
    }
    if (pid == 0) {
        /* The production child's exec hygiene (the serve exec closes
         * every CLOEXEC fd): this stand-in never execs, so it closes
         * everything except the control channel — otherwise its
         * inherited copies of the broker connection/listen fds would
         * keep the connection alive past the outer's close. */
        long maxfd = sysconf(_SC_OPEN_MAX);
        int cfd;

        (void)prctl(PR_SET_PDEATHSIG, SIGKILL);
        for (cfd = 3; maxfd > 0 && cfd < maxfd; cfd++) {
            if (cfd != child_control_fd)
                close(cfd);
        }
        _exit(fb_script_child(scenario, child_control_fd, invocation_id,
                              nonce)
                  != 0
                  ? 7
                  : 0);
    }
    sp->last_pid = pid;
    return pid;
}

static dealpg4_outer_spawn make_fb_spawn(fb_spawn *sp)
{
    dealpg4_outer_spawn out;

    memset(sp, 0, sizeof *sp);
    out.fork_nested = fb_fork_nested;
    out.opaque = sp;
    return out;
}

/* === Scripted coordinator peer ========================================= */

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

/* Hex-encode one argv element (even-length lowercase hex). */
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

/* Build one well-formed INVOKE with a single-argv scenario. */
static void build_invoke(char *buf, size_t cap, const char *tag,
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

/* Parse "DEALPG4 REJECT <id> <tag> <token>\n": 0 on success. */
static int peer_parse_reject(const char *line, int64_t *id, char tag[64],
                             char token[64])
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

/* Parse "DEALPG4 STUB_READY <id> <pid> <pgid> <sid> <nonce>\n";
 * returns the pgid/sid through the out parameters (the zero-survivor
 * scans assert the group/session absence). */
static int peer_parse_stub_ready(const char *line, int64_t *id,
                                 pid_t *pgid, pid_t *sid,
                                 char nonce[DEALPG4_NONCE_HEX_CHARS + 1])
{
    long long v = 0;
    long long pid = 0;
    long long pgrp = 0;
    long long ssn = 0;
    char n[64];

    if (sscanf(line, "DEALPG4 STUB_READY %lld %lld %lld %lld %63s", &v,
               &pid, &pgrp, &ssn, n) != 5)
        return 1;
    if (line[strlen(line) - 1] != '\n')
        return 1;
    if (strlen(n) != DEALPG4_NONCE_HEX_CHARS)
        return 1;
    *id = (int64_t)v;
    *pgid = (pid_t)pgrp;
    *sid = (pid_t)ssn;
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

static int peer_send_cancel(int fd, int64_t id, const char *nonce)
{
    char cmd[128];
    int n;

    n = snprintf(cmd, sizeof cmd, "DEALPG4 CANCEL %lld %s\n",
                 (long long)id, nonce);
    if (n <= 0 || (size_t)n >= sizeof cmd)
        return 1;
    return peer_write_all(fd, cmd, (size_t)n) != 0;
}

/* One record INVOKE helper: send, expect INVOKED, return the id. */
static int peer_invoke_one(int fd, const char *invoke_line, int64_t *id)
{
    char line[512];

    if (peer_write_all(fd, invoke_line, strlen(invoke_line)) != 0
        || peer_read_line(fd, line, sizeof line, 3000) != 0)
        return 1;
    if (peer_parse_invoked(line, id) != 0)
        return 1;
    return 0;
}

/* Read a REJECT answer, skipping interleaved terminal records
 * (CLEAN/FAILED relays of the fallback executions). */
static int peer_read_reject_skipping(int fd, char *line, size_t cap,
                                     int64_t *id, char tag[64],
                                     char token[64], int timeout_ms)
{
    for (;;) {
        char type[32];
        long long rid = 0;

        if (peer_read_line(fd, line, cap, timeout_ms) != 0)
            return -1;
        if (peer_parse_reject(line, id, tag, token) == 0)
            return 0;
        if (sscanf(line, "DEALPG4 %31s %lld", type, &rid) == 2
            && (strcmp(type, "CLEAN") == 0
                || strcmp(type, "FAILED") == 0))
            continue;
        return -1;
    }
}

/* The peer's registered-record nonce lookup (published by the
 * composition to build/.fb-rec-nonce as "<id> <nonce> <scenario>"
 * lines). */
static int peer_lookup_nonce(int64_t id, char nonce[DEALPG4_NONCE_HEX_CHARS
                                                   + 1])
{
    uint64_t deadline = dealpg4_now_ms() + 3000;

    for (;;) {
        FILE *f = fopen("build/.fb-rec-nonce", "r");

        if (f != NULL) {
            long long fid = 0;
            char fn[DEALPG4_NONCE_HEX_CHARS + 1];
            char fs[64];

            while (fscanf(f, "%lld %32s %63s", &fid, fn, fs) == 3) {
                if (fid == id) {
                    snprintf(nonce, DEALPG4_NONCE_HEX_CHARS + 1, "%s",
                             fn);
                    fclose(f);
                    return 0;
                }
            }
            fclose(f);
        }
        if (dealpg4_now_ms() >= deadline)
            return -1;
        sleep_ms(10);
    }
}

/* Continuously drain the broker until EOF (a hanging peer must never
 * stop reading — the stall rule must not fire). */
static void peer_drain_forever(int fd)
{
    char buf[4096];

    for (;;) {
        struct pollfd pfd;
        ssize_t r;

        pfd.fd = fd;
        pfd.events = POLLIN;
        pfd.revents = 0;
        if (poll(&pfd, 1, 100) < 0 && errno != EINTR)
            return;
        if (!(pfd.revents & (POLLIN | POLLHUP)))
            continue;
        r = read(fd, buf, sizeof buf);
        if (r <= 0) {
            if (r < 0 && (errno == EINTR || errno == EAGAIN))
                continue;
            return; /* EOF/error: the outer closed (or we died) */
        }
    }
}

/* One scripted coordinator scenario. Returns the exit status (the
 * peer's own assertions). */
static int fb_peer_main(const char *scenario)
{
    const char *path = getenv("DEALPG4_BROKER_PATH");
    const char *nonce = getenv("DEALPG4_NONCE");
    char line[DEALPG4_MAX_LINE_OUT_BYTES + 64];
    char inv[DEALPG4_MAX_LINE_OTHER_BYTES];
    char rec_nonce[DEALPG4_NONCE_HEX_CHARS + 1];
    int64_t id = 0;
    int64_t id2 = 0;
    int64_t rid = 0;
    char tag[64];
    char token[64];
    int fd;
    int rc;

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

    if (strcmp(scenario, "die-stub") == 0) {
        /* One INVOKE whose scripted supervisor dies at the scenario's
         * point; the peer exits 0 immediately (the broker EOF with a
         * live record is the caller-loss total-cancel trigger). */
        build_invoke(inv, sizeof inv, "tag", "die-after-stub-forked");
        if (peer_invoke_one(fd, inv, &id) != 0) {
            fprintf(stderr, "PEER FAIL die-stub-invoked\n");
            return 1;
        }
        sleep_ms(500); /* the outer processes the child's STUB_FORKED
                          first: the record enters STUB_BLOCKED before
                          the caller-loss mark */
        close(fd);
        printf("PEER die-stub\n");
        fflush(stdout);
        return 0;
    }

    if (strcmp(scenario, "die-published") == 0) {
        /* INVOKE; the forwarded STUB_READY (double-verified) is
         * observed and the pgid/sid recorded for the zero-survivor
         * scan; the peer exits 0 (caller loss). */
        pid_t pgid = 0;
        pid_t sid = 0;
        FILE *f;

        build_invoke(inv, sizeof inv, "tag", "die-after-stub-ready");
        if (peer_invoke_one(fd, inv, &id) != 0)
            return 1;
        rc = peer_read_line(fd, line, sizeof line, 5000);
        if (rc != 0
            || peer_parse_stub_ready(line, &id2, &pgid, &sid,
                                     rec_nonce) != 0
            || id2 != id) {
            fprintf(stderr, "PEER FAIL die-published-ready %s\n", line);
            return 1;
        }
        f = fopen("build/.fb-peer-pgid", "w");
        if (f != NULL) {
            fprintf(f, "%d %d\n", (int)pgid, (int)sid);
            fclose(f);
        }
        close(fd);
        printf("PEER die-published\n");
        fflush(stdout);
        return 0;
    }

    if (strcmp(scenario, "release") == 0) {
        /* The RELEASED path: INVOKED -> STUB_READY -> ACK (validated,
         * relayed — RELEASED at write completion) -> STARTED; the
         * supervisor then dies mid-cleanup (the takeover scenario);
         * the peer exits 0. */
        pid_t pgid = 0;
        pid_t sid = 0;
        FILE *f;

        build_invoke(inv, sizeof inv, "tag", "release-then-die");
        if (peer_invoke_one(fd, inv, &id) != 0)
            return 1;
        rc = peer_read_line(fd, line, sizeof line, 5000);
        if (rc != 0
            || peer_parse_stub_ready(line, &id2, &pgid, &sid,
                                     rec_nonce) != 0
            || id2 != id) {
            fprintf(stderr, "PEER FAIL release-ready %s\n", line);
            return 1;
        }
        if (peer_send_ack(fd, id, rec_nonce) != 0) {
            fprintf(stderr, "PEER FAIL release-ack\n");
            return 1;
        }
        rc = peer_read_line(fd, line, sizeof line, 5000);
        if (rc != 0) {
            fprintf(stderr, "PEER FAIL release-started %s\n", line);
            return 1;
        }
        {
            long long v = 0;

            if (sscanf(line, "DEALPG4 STARTED %lld", &v) != 1)
                rid = -1;
            else
                rid = (int64_t)v;
        }
        if (rid != id || line[strlen(line) - 1] != '\n') {
            fprintf(stderr, "PEER FAIL release-started-shape %s\n",
                    line);
            return 1;
        }
        f = fopen("build/.fb-peer-pgid", "w");
        if (f != NULL) {
            fprintf(f, "%d %d\n", (int)pgid, (int)sid);
            fclose(f);
        }
        close(fd);
        printf("PEER release\n");
        fflush(stdout);
        return 0;
    }

    if (strcmp(scenario, "cancel-die") == 0) {
        /* A validated CANCEL (live record + exact registry nonce)
         * then exit 0: the scripted child observes the CANCEL and
         * dies (or answers REJECT then hangs — the hold case). */
        build_invoke(inv, sizeof inv, "tag", "cancel-then-die");
        if (peer_invoke_one(fd, inv, &id) != 0)
            return 1;
        if (peer_lookup_nonce(id, rec_nonce) != 0) {
            fprintf(stderr, "PEER FAIL cancel-die-nonce\n");
            return 1;
        }
        sleep_ms(500); /* the child's STUB_FORKED is processed first:
                          the pre-cancel state is STUB_BLOCKED */
        if (peer_send_cancel(fd, id, rec_nonce) != 0) {
            fprintf(stderr, "PEER FAIL cancel-die-send\n");
            return 1;
        }
        close(fd);
        printf("PEER cancel-die\n");
        fflush(stdout);
        return 0;
    }

    if (strcmp(scenario, "wedge") == 0) {
        /* INVOKE the never-publishing child, exit 0: caller loss
         * marks the record CANCELLING; the per-record deadline wedge
         * force-terminates it. */
        build_invoke(inv, sizeof inv, "tag", "hang");
        if (peer_invoke_one(fd, inv, &id) != 0) {
            fprintf(stderr, "PEER FAIL wedge-invoked\n");
            return 1;
        }
        close(fd);
        printf("PEER wedge\n");
        fflush(stdout);
        return 0;
    }

    if (strcmp(scenario, "cancel-matrix") == 0) {
        /* Two live records: the CANCEL validation matrix — wrong
         * nonce / unknown id answered REJECT CANCEL_AUTH_FAILED (the
         * record untouched); a validated CANCEL for id2 (the child
         * applies it — CLEAN cancelled relays verbatim); a CANCEL
         * for the now-terminal id2 is answered REJECT again; a
         * validated CANCEL for id1 completes it; clean exit. */
        char nonce1[DEALPG4_NONCE_HEX_CHARS + 1] = "";
        char nonce2[DEALPG4_NONCE_HEX_CHARS + 1] = "";
        uint64_t deadline = dealpg4_now_ms() + 3000;

        build_invoke(inv, sizeof inv, "taga", "apply-cancel");
        if (peer_invoke_one(fd, inv, &id) != 0) {
            fprintf(stderr, "PEER FAIL matrix-invoke1 [%s]\n", inv);
            return 1;
        }
        build_invoke(inv, sizeof inv, "tagb", "apply-cancel");
        if (peer_invoke_one(fd, inv, &id2) != 0) {
            fprintf(stderr, "PEER FAIL matrix-invoke2 [%s]\n", inv);
            return 1;
        }
        for (;;) {
            if (peer_lookup_nonce(id, nonce1) == 0
                && peer_lookup_nonce(id2, nonce2) == 0)
                break;
            if (dealpg4_now_ms() >= deadline) {
                fprintf(stderr, "PEER FAIL matrix-nonces\n");
                return 1;
            }
            sleep_ms(10);
        }
        /* Wrong nonce: REJECT CANCEL_AUTH_FAILED, record untouched. */
        if (peer_send_cancel(fd, id, BAD_NONCE) != 0
            || peer_read_reject_skipping(fd, line, sizeof line, &rid,
                                         tag, token, 3000) != 0
            || rid != id || strcmp(tag, "taga") != 0
            || strcmp(token, "CANCEL_AUTH_FAILED") != 0) {
            fprintf(stderr, "PEER FAIL matrix-bad-nonce %s\n", line);
            return 1;
        }
        /* Unknown invocationId. */
        if (peer_send_cancel(fd, 999999, nonce1) != 0
            || peer_read_reject_skipping(fd, line, sizeof line, &rid,
                                         tag, token, 3000) != 0
            || rid != 999999 || strcmp(tag, "-") != 0
            || strcmp(token, "CANCEL_AUTH_FAILED") != 0) {
            fprintf(stderr, "PEER FAIL matrix-unknown %s\n", line);
            return 1;
        }
        /* A validated CANCEL for id2: no broker answer; the child
         * applies it — the CLEAN cancelled relays verbatim. */
        if (peer_send_cancel(fd, id2, nonce2) != 0) {
            fprintf(stderr, "PEER FAIL matrix-cancel2-send\n");
            return 1;
        }
        {
            char want[128];

            snprintf(want, sizeof want, "DEALPG4 CLEAN %lld cancelled\n",
                     (long long)id2);
            if (peer_read_line(fd, line, sizeof line, 5000) != 0
                || strcmp(line, want) != 0) {
                fprintf(stderr, "PEER FAIL matrix-clean2 got=[%s]\n",
                        line);
                return 1;
            }
        }
        /* A CANCEL for the terminal id2: REJECT, the record
         * untouched. */
        if (peer_send_cancel(fd, id2, nonce2) != 0
            || peer_read_reject_skipping(fd, line, sizeof line, &rid,
                                         tag, token, 3000) != 0
            || rid != id2 || strcmp(token, "CANCEL_AUTH_FAILED") != 0) {
            fprintf(stderr, "PEER FAIL matrix-terminal %s\n", line);
            return 1;
        }
        /* A validated CANCEL for id1 completes the second record. */
        if (peer_send_cancel(fd, id, nonce1) != 0) {
            fprintf(stderr, "PEER FAIL matrix-cancel1-send\n");
            return 1;
        }
        {
            char want[128];

            snprintf(want, sizeof want, "DEALPG4 CLEAN %lld cancelled\n",
                     (long long)id);
            if (peer_read_line(fd, line, sizeof line, 5000) != 0
                || strcmp(line, want) != 0) {
                fprintf(stderr, "PEER FAIL matrix-clean1 got=[%s]\n",
                        line);
                return 1;
            }
        }
        close(fd);
        printf("PEER cancel-matrix\n");
        fflush(stdout);
        return 0;
    }

    if (strcmp(scenario, "reject-hold") == 0) {
        /* INVOKE the child that answers REJECT CANCEL_AUTH_FAILED to
         * the fan-out CANCEL and stays alive; the close fires the
         * caller-loss total-cancel (the fan-out relay carries the
         * exact registry nonce — the child verifies it); the record
         * holds to its per-record deadline (the wedge). */
        build_invoke(inv, sizeof inv, "tag", "reject-then-hang");
        if (peer_invoke_one(fd, inv, &id) != 0) {
            fprintf(stderr, "PEER FAIL hold-invoked\n");
            return 1;
        }
        sleep_ms(500); /* the child's STUB_FORKED is processed first:
                          the pre-cancel state is STUB_BLOCKED */
        close(fd);
        printf("PEER reject-hold\n");
        fflush(stdout);
        return 0;
    }

    if (strcmp(scenario, "kill-self") == 0) {
        /* Two live records, then the coordinator SIGKILLs itself:
         * the nonzero reap + the broker EOF are the COORDINATOR_LOST
         * total-cancel triggers; the children apply the fan-out
         * CANCELs and complete in parallel. */
        build_invoke(inv, sizeof inv, "taga", "apply-cancel");
        if (peer_invoke_one(fd, inv, &id) != 0) {
            fprintf(stderr, "PEER FAIL matrix-invoke1 [%s]\n", inv);
            return 1;
        }
        build_invoke(inv, sizeof inv, "tagb", "apply-cancel");
        if (peer_invoke_one(fd, inv, &id2) != 0) {
            fprintf(stderr, "PEER FAIL matrix-invoke2 [%s]\n", inv);
            return 1;
        }
        printf("PEER kill-self-invoked\n");
        fflush(stdout);
        (void)raise(SIGKILL);
        for (;;)
            pause();
    }

    if (strcmp(scenario, "cutoff") == 0) {
        /* The INVOKE cutoff total-cancel: the peer stays alive past
         * the cutoff, then reads the record's terminal answer and
         * DONE clean, sends BYE, observes the close, exits 0 (the
         * clean-exit discrimination: exit 0, no live records). */
        build_invoke(inv, sizeof inv, "tag", "hang");
        if (peer_invoke_one(fd, inv, &id) != 0) {
            fprintf(stderr, "PEER FAIL cutoff-invoked\n");
            return 1;
        }
        sleep_ms(17000); /* past the cutoff (15500) + the wedge
                            grace + the proof */
        /* The record's terminal answer precedes DONE. */
        {
            char want[128];

            snprintf(want, sizeof want, "DEALPG4 CLEAN %lld cancelled\n",
                     (long long)id);
            if (peer_read_line(fd, line, sizeof line, 8000) != 0
                || strcmp(line, want) != 0) {
                fprintf(stderr, "PEER FAIL cutoff-clean got=[%s]\n",
                        line);
                return 1;
            }
        }
        if (peer_read_line(fd, line, sizeof line, 5000) != 0
            || strcmp(line, "DEALPG4 DONE clean\n") != 0) {
            fprintf(stderr, "PEER FAIL cutoff-done got=[%s]\n", line);
            return 1;
        }
        if (peer_write_all(fd, "DEALPG4 BYE\n", 12) != 0
            || peer_read_line(fd, line, sizeof line, 3000) != -1) {
            fprintf(stderr, "PEER FAIL cutoff-bye got=[%s]\n", line);
            return 1;
        }
        close(fd);
        printf("PEER cutoff\n");
        fflush(stdout);
        return 0;
    }

    if (strcmp(scenario, "hang") == 0) {
        /* A hanging coordinator (the shell-loss / EPIPE cases): one
         * live record, then drain the broker forever — the stall
         * rule must never fire (the peer keeps reading). */
        build_invoke(inv, sizeof inv, "tag", "apply-cancel");
        if (peer_invoke_one(fd, inv, &id) != 0) {
            fprintf(stderr, "PEER FAIL hang-invoked\n");
            return 1;
        }
        peer_drain_forever(fd);
        printf("PEER hang-eof\n");
        fflush(stdout);
        return 0;
    }

    fprintf(stderr, "PEER FAIL unknown-scenario %s\n",
            scenario != NULL ? scenario : "?");
    return 1;
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
        ssize_t r = read(pipefd[0], report + total, report_cap - total);

        if (r > 0) {
            total += r;
            if (total >= (ssize_t)report_cap)
                break;
            continue;
        }
        if (r < 0 && errno == EINTR)
            continue;
        break;
    }
    close(pipefd[0]);
    report[total < (ssize_t)report_cap ? total : report_cap - 1] = '\0';
    return status;
}

static void peer_argv(char *argv[8], const char *scenario)
{
    size_t n = 0;

    argv[n++] = (char *)g_suite_argv0;
    argv[n++] = (char *)"--fallback-peer";
    argv[n++] = (char *)scenario;
    argv[n] = NULL;
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

/* Find one byte pattern in the drain's retained content. */
static const char *find_in_retained(const dealpg4_drain_ctx *d,
                                    const char *needle)
{
    size_t nlen = strlen(needle);
    size_t i;
    size_t j;

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

/* One /proc identity read (ppid/pgrp/session). */
static int proc_stat_identity(pid_t pid, pid_t *ppid, pid_t *pgrp,
                              pid_t *session)
{
    char path[64];
    FILE *f;
    char comm[256];
    char state;
    long long f1;
    long long f4;
    long long f5;
    long long f6;

    snprintf(path, sizeof path, "/proc/%d/stat", (int)pid);
    f = fopen(path, "r");
    if (f == NULL)
        return -1;
    if (fscanf(f, "%lld %255s %c %lld %lld %lld", &f1, comm, &state, &f4,
               &f5, &f6) != 6) {
        fclose(f);
        return -1;
    }
    fclose(f);
    if (ppid != NULL)
        *ppid = (pid_t)f4;
    if (pgrp != NULL)
        *pgrp = (pid_t)f5;
    if (session != NULL)
        *session = (pid_t)f6;
    return 0;
}

/* The zero-survivor assertion: no waitable child (waitid ECHILD), no
 * /proc task with ppid == the capture child, and no task in the
 * recorded target group/session (when > 0). */
static void check_zero_survivors(pid_t pgid, pid_t sid)
{
    siginfo_t si;
    DIR *dir;
    struct dirent *ent;
    pid_t me = getpid();

    memset(&si, 0, sizeof si);
    CHECK(waitid(P_ALL, 0, &si, WEXITED | WNOHANG | WNOWAIT) == -1
          && errno == ECHILD);
    dir = opendir("/proc");
    if (dir != NULL) {
        while ((ent = readdir(dir)) != NULL) {
            const char *name = ent->d_name;
            pid_t pid = 0;
            size_t i;
            pid_t ppid = 0;
            pid_t pgrp = 0;
            pid_t session = 0;

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
            if (name[i] != '\0' || pid <= 0 || pid == me)
                continue;
            if (proc_stat_identity(pid, &ppid, &pgrp, &session) != 0)
                continue;
            if (ppid == me) {
                fprintf(stderr, "FAIL adopted-survivor pid=%d\n",
                        (int)pid);
                g_failures++;
            }
            if (pgid > 0 && pgrp == pgid) {
                fprintf(stderr, "FAIL group-survivor pid=%d pgid=%d\n",
                        (int)pid, (int)pgid);
                g_failures++;
            }
            if (sid > 0 && session == sid) {
                fprintf(stderr,
                        "FAIL session-survivor pid=%d sid=%d\n",
                        (int)pid, (int)sid);
                g_failures++;
            }
        }
        closedir(dir);
    }
}

/* === Case groups ====================================================== */

typedef struct run_out {
    int status;
    dealpg4_outer_result view;
    char report[8192];
} run_out;

static void run_fb_case(const OuterLimits *limits, const char *peer_sc,
                        const char *child_sc, const char *tag,
                        int peer_may_die, run_out *out)
{
    fb_spawn sp;
    dealpg4_outer_spawn spawn;
    char *argv[8];
    const dealpg4_drain_ctx *derr = NULL;

    memset(&sp, 0, sizeof sp);
    spawn = make_fb_spawn(&sp);
    (void)unlink("build/.fb-rec-nonce");
    (void)unlink("build/.fb-peer-pgid");
    peer_argv(argv, peer_sc);
    (void)child_sc;
    (void)tag;
    out->status = core_with_spawn(limits, argv, &spawn, out->report,
                                  sizeof out->report);
    memset(&out->view, 0, sizeof out->view);
    dealpg4_outer_last_result(&out->view);
    /* The peer's own assertions propagate through the coordinator
     * reap and the retained stderr drain. */
    if (!peer_may_die)
        CHECK(out->view.coordinator_exited_0 == 1);
    dealpg4_outer_drain_state(NULL, &derr);
    if (derr != NULL && derr->retained_len > 0) {
        CHECK(find_in_retained(derr, "PEER FAIL") == NULL);
        if (find_in_retained(derr, "PEER FAIL") != NULL) {
            fprintf(stderr, "DIAG peer stderr: %.*s\n",
                    (int)derr->retained_len,
                    (const char *)derr->retained);
        }
    }
}

/* 1. Kill during STUB_BLOCKED. */
static int case_stub_blocked_death_fn(void)
{
    run_out out;

    run_fb_case(&FB_LIMITS, "die-stub", "die-after-stub-forked", "tag",
                0, &out);
    CHECK(out.status == DEALPG4_OUTER_EXIT_GATE_FAILURE);
    CHECK(has_token(&out.view, "COORDINATOR_LOST"));
    CHECK(!has_token(&out.view, "OVERALL_TIMEOUT"));
    CHECK(out.view.records_total == 1);
    CHECK(out.view.records_live == 0);
    CHECK(out.view.records_clean == 1);
    CHECK(out.view.nested_deaths_observed == 1);
    CHECK(out.view.stub_ready_forwarded == 0);
    CHECK(out.view.synthesized_terminals == 1);
    CHECK(out.view.fallback_completions == 1);
    CHECK(out.view.wedge_terminations == 0);
    CHECK(out.view.ack_write_completions == 0); /* RELEASED never
                                                   entered */
    CHECK(out.view.caller_loss_marked == 1);
    CHECK(out.view.done_queued == 0); /* the cutoff never passed */
    CHECK(out.view.proof_passed == 1);
    {
        dealpg4_outer_record_view rv;

        CHECK(dealpg4_outer_registry_record(0, &rv) == 0);
        CHECK(rv.state == DEALPG4_OUTER_REC_CLEAN);
        CHECK(rv.clean_final == 0);
        CHECK(rv.cleanup_acknowledged == 1);
        CHECK(rv.fallback_initiated == 1);
        CHECK(rv.fallback_wedge == 0);
        CHECK(rv.fallback_state == DEALPG4_OUTER_REC_STUB_BLOCKED);
        CHECK(rv.fallback_step == DEALPG4_OUTER_FB_DONE);
        CHECK(rv.stub_pid > 0); /* retained for the fallback */
        CHECK(rv.target_pgid == 0); /* never published */
        CHECK(rv.history_count == 4);
        CHECK(rv.history[0].state == DEALPG4_OUTER_REC_FORKING);
        CHECK(rv.history[1].state == DEALPG4_OUTER_REC_STUB_BLOCKED);
        CHECK(rv.history[2].state == DEALPG4_OUTER_REC_CANCELLING);
        CHECK(rv.history[3].state == DEALPG4_OUTER_REC_CLEAN);
    }
    CHECK(strstr(out.report, "OUTER record 1 CLEAN tag cancelled\n")
          != NULL);
    check_zero_survivors(0, 0);
    return (g_failures > 0) ? 1 : 0;
}

/* 2. Kill during TARGET_PUBLISHED. */
static int case_target_published_death_fn(void)
{
    run_out out;
    pid_t pgid = 0;
    pid_t sid = 0;

    run_fb_case(&FB_LIMITS, "die-published", "die-after-stub-ready",
                "tag", 0, &out);
    CHECK(out.status == DEALPG4_OUTER_EXIT_GATE_FAILURE);
    CHECK(has_token(&out.view, "COORDINATOR_LOST"));
    CHECK(out.view.records_total == 1);
    CHECK(out.view.records_live == 0);
    CHECK(out.view.records_clean == 1);
    CHECK(out.view.stub_ready_forwarded == 1); /* forwarded before the
                                                  death */
    CHECK(out.view.stub_verify_failures == 0);
    CHECK(out.view.synthesized_terminals == 1);
    CHECK(out.view.fallback_completions == 1);
    CHECK(out.view.ack_write_completions == 0); /* no ACK ever
                                                   applied */
    CHECK(out.view.proof_passed == 1);
    {
        dealpg4_outer_record_view rv;

        CHECK(dealpg4_outer_registry_record(0, &rv) == 0);
        CHECK(rv.state == DEALPG4_OUTER_REC_CLEAN);
        CHECK(rv.clean_final == 0);
        CHECK(rv.cleanup_acknowledged == 1);
        CHECK(rv.fallback_state == DEALPG4_OUTER_REC_TARGET_PUBLISHED);
        CHECK(rv.stub_pid > 0);
        CHECK(rv.target_pgid > 0); /* the verified group */
        CHECK(rv.target_session_id > 0);
        CHECK(rv.history_count == 5);
        CHECK(rv.history[0].state == DEALPG4_OUTER_REC_FORKING);
        CHECK(rv.history[1].state == DEALPG4_OUTER_REC_STUB_BLOCKED);
        CHECK(rv.history[2].state == DEALPG4_OUTER_REC_TARGET_PUBLISHED);
        CHECK(rv.history[3].state == DEALPG4_OUTER_REC_CANCELLING);
        CHECK(rv.history[4].state == DEALPG4_OUTER_REC_CLEAN);
        pgid = rv.target_pgid;
        sid = rv.target_session_id;
    }
    check_zero_survivors(pgid, sid); /* group/session absence proven */
    return (g_failures > 0) ? 1 : 0;
}

/* 3. Kill during RELEASED (the retained-identity takeover). */
static int case_released_takeover_fn(void)
{
    run_out out;
    pid_t pgid = 0;
    pid_t sid = 0;

    run_fb_case(&FB_LIMITS, "release", "release-then-die", "tag", 0,
                &out);
    CHECK(out.status == DEALPG4_OUTER_EXIT_GATE_FAILURE);
    CHECK(has_token(&out.view, "COORDINATOR_LOST"));
    CHECK(out.view.records_total == 1);
    CHECK(out.view.records_live == 0);
    CHECK(out.view.records_clean == 1);
    CHECK(out.view.ack_write_completions == 1); /* RELEASED entered
                                                   exactly when the
                                                   ACK write
                                                   completed */
    CHECK(out.view.nested_deaths_observed == 1);
    CHECK(out.view.synthesized_terminals == 1);
    CHECK(out.view.fallback_completions == 1);
    CHECK(out.view.proof_passed == 1);
    {
        dealpg4_outer_record_view rv;

        CHECK(dealpg4_outer_registry_record(0, &rv) == 0);
        CHECK(rv.state == DEALPG4_OUTER_REC_CLEAN);
        CHECK(rv.clean_final == 0);
        CHECK(rv.cleanup_acknowledged == 1); /* the takeover proof */
        CHECK(rv.fallback_state == DEALPG4_OUTER_REC_RELEASED);
        CHECK(rv.target_pgid > 0);
        CHECK(rv.target_session_id > 0);
        CHECK(rv.history_count == 6);
        CHECK(rv.history[0].state == DEALPG4_OUTER_REC_FORKING);
        CHECK(rv.history[1].state == DEALPG4_OUTER_REC_STUB_BLOCKED);
        CHECK(rv.history[2].state == DEALPG4_OUTER_REC_TARGET_PUBLISHED);
        CHECK(rv.history[3].state == DEALPG4_OUTER_REC_RELEASED);
        CHECK(rv.history[4].state == DEALPG4_OUTER_REC_CANCELLING);
        CHECK(rv.history[5].state == DEALPG4_OUTER_REC_CLEAN);
        pgid = rv.target_pgid;
        sid = rv.target_session_id;
    }
    check_zero_survivors(pgid, sid); /* the escapee included */
    return (g_failures > 0) ? 1 : 0;
}

/* 4. Kill mid-cancel: the pre-cancel state's death fallback. */
static int case_cancel_then_die_fn(void)
{
    run_out out;

    run_fb_case(&FB_LIMITS, "cancel-die", "cancel-then-die", "tag",
                0, &out);
    CHECK(out.status == DEALPG4_OUTER_EXIT_GATE_FAILURE);
    CHECK(has_token(&out.view, "COORDINATOR_LOST"));
    CHECK(out.view.records_total == 1);
    CHECK(out.view.records_live == 0);
    CHECK(out.view.records_clean == 1);
    CHECK(out.view.nested_deaths_observed == 1);
    CHECK(out.view.nested_terminal_relays == 0); /* died without a
                                                    terminal record */
    CHECK(out.view.nested_rejects == 0);
    CHECK(out.view.synthesized_terminals == 1);
    CHECK(out.view.fallback_completions == 1);
    CHECK(out.view.proof_passed == 1);
    {
        dealpg4_outer_record_view rv;

        CHECK(dealpg4_outer_registry_record(0, &rv) == 0);
        CHECK(rv.state == DEALPG4_OUTER_REC_CLEAN);
        CHECK(rv.clean_final == 0);
        CHECK(rv.cleanup_acknowledged == 1);
        CHECK(rv.pre_cancel_state == DEALPG4_OUTER_REC_STUB_BLOCKED);
        CHECK(rv.fallback_state == DEALPG4_OUTER_REC_STUB_BLOCKED);
        CHECK(rv.stub_pid > 0); /* retained for the fallback */
        CHECK(rv.history_count == 4);
        CHECK(rv.history[0].state == DEALPG4_OUTER_REC_FORKING);
        CHECK(rv.history[1].state == DEALPG4_OUTER_REC_STUB_BLOCKED);
        CHECK(rv.history[2].state == DEALPG4_OUTER_REC_CANCELLING);
        CHECK(rv.history[3].state == DEALPG4_OUTER_REC_CLEAN);
    }
    check_zero_survivors(0, 0);
    return (g_failures > 0) ? 1 : 0;
}

/* 5. The wedge rule (the never-publishing child). */
static int case_wedge_fn(void)
{
    run_out out;
    uint64_t before = dealpg4_now_ms();
    uint64_t after;

    run_fb_case(&FB_LIMITS, "wedge", "hang", "tag", 0, &out);
    after = dealpg4_now_ms();
    CHECK(out.status == DEALPG4_OUTER_EXIT_GATE_FAILURE);
    CHECK(has_token(&out.view, "COORDINATOR_LOST"));
    CHECK(!has_token(&out.view, "OVERALL_TIMEOUT"));
    CHECK(after - before >= 15000); /* the record held live to its
                                       per-record deadline (== the
                                       cutoff, the D9 recipe) */
    CHECK(after - before < 25000);
    CHECK(out.view.records_total == 1);
    CHECK(out.view.records_live == 0);
    CHECK(out.view.records_clean == 1);
    CHECK(out.view.wedge_terminations == 1); /* TERM by pid at the
                                                deadline */
    CHECK(out.view.synthesized_terminals == 1);
    CHECK(out.view.fallback_completions == 1);
    CHECK(out.view.nested_terminal_relays == 0);
    CHECK(out.view.caller_loss_marked == 1);
    CHECK(out.view.cancel_fanout_writes == 1); /* the caller-loss
                                                  fan-out */
    CHECK(out.view.cutoff_cancelled == 1);
    CHECK(out.view.done_queued == 1); /* at/after the cutoff, fully
                                         terminal */
    CHECK(out.view.done_clean == 1);
    CHECK(out.view.proof_passed == 1);
    {
        dealpg4_outer_record_view rv;

        CHECK(dealpg4_outer_registry_record(0, &rv) == 0);
        CHECK(rv.state == DEALPG4_OUTER_REC_CLEAN);
        CHECK(rv.clean_final == 0);
        CHECK(rv.cleanup_acknowledged == 1);
        CHECK(rv.fallback_wedge == 1);
        CHECK(rv.fallback_state == DEALPG4_OUTER_REC_FORKING);
        CHECK(rv.history_count == 3);
        CHECK(rv.history[0].state == DEALPG4_OUTER_REC_FORKING);
        CHECK(rv.history[1].state == DEALPG4_OUTER_REC_CANCELLING);
        CHECK(rv.history[2].state == DEALPG4_OUTER_REC_CLEAN);
    }
    check_zero_survivors(0, 0);
    return (g_failures > 0) ? 1 : 0;
}

/* 6. CANCEL validation over the live broker peer. */
static int case_cancel_matrix_fn(void)
{
    run_out out;

    run_fb_case(&FB_LIMITS, "cancel-matrix", "apply-cancel", "tag2",
                0, &out);
    CHECK(out.status == 0); /* both records CLEAN cancelled + the
                               clean-exit discrimination */
    CHECK(out.view.records_total == 2);
    CHECK(out.view.records_live == 0);
    CHECK(out.view.records_clean == 2);
    CHECK(out.view.nested_terminal_relays == 2); /* the children
                                                    applied the
                                                    validated
                                                    CANCELs */
    CHECK(out.view.nested_rejects == 0);
    CHECK(out.view.synthesized_terminals == 0);
    CHECK(out.view.wedge_terminations == 0);
    CHECK(out.view.done_queued == 0); /* the run ended before the
                                         cutoff */
    CHECK(out.view.proof_passed == 1);
    CHECK(out.view.ntokens == 0);
    check_zero_survivors(0, 0);
    return (g_failures > 0) ? 1 : 0;
}

/* 7. The nested REJECT hold -> the wedge. */
static int case_reject_hold_fn(void)
{
    run_out out;
    uint64_t before = dealpg4_now_ms();
    uint64_t after;

    run_fb_case(&FB_LIMITS, "reject-hold", "reject-then-hang", "tag",
                0, &out);
    after = dealpg4_now_ms();
    CHECK(out.status == DEALPG4_OUTER_EXIT_GATE_FAILURE);
    CHECK(has_token(&out.view, "COORDINATOR_LOST"));
    CHECK(after - before >= 15000); /* held to the per-record
                                       deadline */
    CHECK(after - before < 28000); /* the wedge grace + the death
                                      fallback grace (~21 s) */
    CHECK(out.view.records_total == 1);
    CHECK(out.view.records_live == 0);
    CHECK(out.view.records_clean == 1);
    CHECK(out.view.nested_rejects == 1); /* the supervisor-defect
                                            answer: consumed, never
                                            forwarded */
    CHECK(out.view.nested_terminal_relays == 0);
    CHECK(out.view.wedge_terminations == 1); /* the wedge applies at
                                                the deadline */
    CHECK(out.view.synthesized_terminals == 1);
    CHECK(out.view.proof_passed == 1);
    {
        dealpg4_outer_record_view rv;

        CHECK(dealpg4_outer_registry_record(0, &rv) == 0);
        CHECK(rv.state == DEALPG4_OUTER_REC_CLEAN);
        CHECK(rv.clean_final == 0);
        CHECK(rv.fallback_wedge == 1);
        CHECK(rv.pre_cancel_state == DEALPG4_OUTER_REC_STUB_BLOCKED);
        CHECK(rv.fallback_state == DEALPG4_OUTER_REC_STUB_BLOCKED);
    }
    check_zero_survivors(0, 0);
    return (g_failures > 0) ? 1 : 0;
}

/* 8. Total-cancel — the coordinator SIGKILLs itself mid-run. */
static int case_coord_kill_fn(void)
{
    run_out out;

    run_fb_case(&FB_LIMITS, "kill-self", "apply-cancel", "tag1", 1,
                &out);
    CHECK(out.status == DEALPG4_OUTER_EXIT_GATE_FAILURE);
    CHECK(has_token(&out.view, "COORDINATOR_LOST"));
    CHECK(!has_token(&out.view, "OVERALL_TIMEOUT"));
    CHECK(out.view.records_total == 2);
    CHECK(out.view.records_live == 0);
    CHECK(out.view.records_clean == 2);
    CHECK(out.view.caller_loss_marked == 1);
    CHECK(out.view.cancel_fanout_writes == 2); /* parallel
                                                  cancellation */
    CHECK(out.view.nested_terminal_relays == 2); /* the children
                                                    applied the
                                                    fan-out CANCELs */
    CHECK(out.view.wedge_terminations == 0);
    CHECK(out.view.proof_passed == 1);
    {
        size_t i;

        for (i = 0; i < 2; i++) {
            dealpg4_outer_record_view rv;
            size_t h;
            int saw_cancelling = 0;

            CHECK(dealpg4_outer_registry_record(i, &rv) == 0);
            CHECK(rv.state == DEALPG4_OUTER_REC_CLEAN);
            CHECK(rv.clean_final == 0);
            for (h = 0; h < rv.history_count; h++) {
                if (rv.history[h].state == DEALPG4_OUTER_REC_CANCELLING)
                    saw_cancelling = 1;
            }
            CHECK(saw_cancelling);
        }
    }
    check_zero_survivors(0, 0);
    return (g_failures > 0) ? 1 : 0;
}

/* 9. Total-cancel — the INVOKE cutoff (DONE clean + BYE + exit 0). */
static int case_cutoff_fn(void)
{
    run_out out;
    uint64_t before = dealpg4_now_ms();
    uint64_t after;

    run_fb_case(&FB_CUT_LIMITS, "cutoff", "hang", "tag", 0, &out);
    after = dealpg4_now_ms();
    CHECK(out.status == 0); /* the clean cancelled record + the
                               clean-exit discrimination */
    CHECK(out.view.records_total == 1);
    CHECK(out.view.records_live == 0);
    CHECK(out.view.records_clean == 1);
    CHECK(out.view.cutoff_cancelled == 1);
    CHECK(out.view.caller_loss_marked == 1); /* the BYE close marks the
                                                 slot (vacuous — every
                                                 record was terminal) */
    CHECK(after - before >= 15000);
    CHECK(after - before < 25000);
    CHECK(out.view.cancel_fanout_writes == 1); /* the cutoff fan-out */
    CHECK(out.view.wedge_terminations == 1); /* the per-record
                                                deadline (== the
                                                cutoff) TERMs the
                                                child before its
                                                CLEAN lands — the
                                                synthesized record is
                                                the single terminal
                                                answer */
    CHECK(out.view.synthesized_terminals == 1);
    CHECK(out.view.nested_terminal_relays == 0);
    CHECK(out.view.done_queued == 1);
    CHECK(out.view.done_clean == 1);
    CHECK(out.view.proof_passed == 1);
    CHECK(out.view.ntokens == 0);
    check_zero_survivors(0, 0);
    return (g_failures > 0) ? 1 : 0;
}

/* 10. Total-cancel — shell loss (the orphaned outer). The grandchild
 * runs the core against a hanging coordinator while its parent exits
 * mid-run. */
static int orphan_grandchild_fn(int result_fd)
{
    char *argv[8];
    fb_spawn sp;
    dealpg4_outer_spawn spawn;
    char report[4096];
    char line[256];
    dealpg4_outer_result view;
    int devnull;
    int n;
    int status;

    memset(&sp, 0, sizeof sp);
    spawn = make_fb_spawn(&sp);
    (void)unlink("build/.fb-rec-nonce");
    peer_argv(argv, "hang");
    devnull = open("/dev/null", O_WRONLY);
    if (devnull == -1)
        return 125;
    status = dealpg4_outer_core(&FB_LIMITS, NONCE, argv, "build",
                                devnull, &spawn);
    close(devnull);
    memset(&view, 0, sizeof view);
    dealpg4_outer_last_result(&view);
    n = snprintf(line, sizeof line,
                 "status=%d shell=%d token=%d proof=%d reaped=%d "
                 "term=%d live=%d clean=%d synth=%d relay=%d\\n",
                 status, view.shell_lost, has_token(&view, "SHELL_LOST"),
                 view.proof_passed, view.coordinator_reaped,
                 view.escalation_term_sent, view.records_live,
                 view.records_clean, view.synthesized_terminals,
                 view.nested_terminal_relays);
    if (n > 0 && (size_t)n < sizeof line) {
        ssize_t r = write(result_fd, line, (size_t)n);

        (void)r;
    }
    (void)report;
    return status;
}

static int orphan_helper_fn(int done_wr)
{
    pid_t pid;
    int devnull;

    devnull = open("/dev/null", O_RDONLY);
    if (devnull == -1)
        return 125;
    pid = fork();
    if (pid < 0)
        return 125;
    if (pid == 0) {
        close(devnull);
        return orphan_grandchild_fn(done_wr) & 0xff;
    }
    close(devnull);
    /* The grandchild records shellPid at core entry (~ms); exiting
     * 500 ms later makes the mismatch deterministic. */
    {
        struct timespec ts = {0, 500 * 1000 * 1000};

        while (nanosleep(&ts, &ts) != 0 && errno == EINTR)
            ;
    }
    return 0;
}

static int case_orphan_fn(void)
{
    int done_pipe[2];
    char buf[256];
    size_t off = 0;
    int status;

    CHECK(pipe(done_pipe) == 0);
    {
        pid_t pid = fork();

        CHECK(pid >= 0);
        if (pid == 0) {
            close(done_pipe[0]);
            _exit(orphan_helper_fn(done_pipe[1]) & 0xff);
        }
        close(done_pipe[1]);
        CHECK(waitpid(pid, &status, 0) == pid);
        CHECK(WIFEXITED(status) && WEXITSTATUS(status) == 0);
    }
    for (;;) {
        ssize_t r = read(done_pipe[0], buf + off, sizeof buf - off - 1);

        if (r > 0) {
            off += (size_t)r;
            continue;
        }
        if (r < 0 && errno == EINTR)
            continue;
        break; /* EOF: the grandchild exited */
    }
    close(done_pipe[0]);
    buf[off] = '\0';

    /* The trigger fired deterministically: SHELL_LOST, the live
     * record turned CANCELLING with the fan-out and completed via
     * the child's own cancel path (the nested CLEAN relay), the
     * deferred D8 escalation TERMed the hanging coordinator only
     * after the record was terminal, and the proof passed. */
    CHECK(strstr(buf, "status=1 ") != NULL);
    CHECK(strstr(buf, "shell=1 ") != NULL);
    CHECK(strstr(buf, "token=1 ") != NULL);
    CHECK(strstr(buf, "proof=1 ") != NULL);
    CHECK(strstr(buf, "reaped=1 ") != NULL);
    CHECK(strstr(buf, "term=1 ") != NULL); /* the escalation TERM was
                                              dispatched */
    CHECK(strstr(buf, "live=0 ") != NULL);
    CHECK(strstr(buf, "clean=1 ") != NULL);
    CHECK(strstr(buf, "synth=0 ") != NULL); /* the child applied the
                                               fan-out CANCEL itself */
    if (strstr(buf, "relay=1") == NULL)
        fprintf(stderr, "DIAG orphan result: %s", buf);
    CHECK(strstr(buf, "relay=1") != NULL);
    return 0;
}

/* 11. Total-cancel — the closed report stdout (EPIPE at the final
 * report): the cutoff wedge completes the record, the hanging
 * coordinator is escalated at the pinned escalation deadline, and the
 * best-effort report flips the gate on the EPIPE. */
static int epipe_grandchild_fn(int result_fd)
{
    char *argv[8];
    fb_spawn sp;
    dealpg4_outer_spawn spawn;
    char line[256];
    dealpg4_outer_result view;
    int pipefd[2];
    int n;
    int status;

    memset(&sp, 0, sizeof sp);
    spawn = make_fb_spawn(&sp);
    (void)unlink("build/.fb-rec-nonce");
    peer_argv(argv, "hang");
    CHECK(pipe(pipefd) == 0);
    close(pipefd[0]); /* the read end closed before the run: the
                         final report write fails with EPIPE */
    status = dealpg4_outer_core(&FB_CUT_LIMITS, NONCE, argv, "build",
                                pipefd[1], &spawn);
    close(pipefd[1]);
    memset(&view, 0, sizeof view);
    dealpg4_outer_last_result(&view);
    n = snprintf(line, sizeof line,
                 "status=%d shell=%d hang=%d live=%d clean=%d "
                 "wedge=%d proof=%d cutoff=%d done=%d\\n",
                 status, view.shell_lost,
                 has_token(&view, "COORDINATOR_HANG"),
                 view.records_live, view.records_clean,
                 view.wedge_terminations, view.proof_passed,
                 view.cutoff_cancelled, view.done_queued);
    if (n > 0 && (size_t)n < sizeof line) {
        ssize_t r = write(result_fd, line, (size_t)n);

        (void)r;
    }
    return status;
}

static int case_epipe_fn(void)
{
    int done_pipe[2];
    char buf[256];
    size_t off = 0;
    int status;
    uint64_t before = dealpg4_now_ms();
    uint64_t after;

    CHECK(pipe(done_pipe) == 0);
    {
        pid_t pid = fork();

        CHECK(pid >= 0);
        if (pid == 0) {
            close(done_pipe[0]);
            (void)epipe_grandchild_fn(done_pipe[1]);
            _exit(0); /* the parent's assertions consume the result
                         line */
        }
        close(done_pipe[1]);
        CHECK(waitpid(pid, &status, 0) == pid);
        CHECK(WIFEXITED(status) && WEXITSTATUS(status) == 0);
    }
    for (;;) {
        ssize_t r = read(done_pipe[0], buf + off, sizeof buf - off - 1);

        if (r > 0) {
            off += (size_t)r;
            continue;
        }
        if (r < 0 && errno == EINTR)
            continue;
        break;
    }
    close(done_pipe[0]);
    buf[off] = '\0';
    after = dealpg4_now_ms();

    CHECK(strstr(buf, "status=1 ") != NULL);
    CHECK(strstr(buf, "shell=1 ") != NULL); /* the EPIPE flip */
    CHECK(strstr(buf, "hang=1 ") != NULL); /* the pinned escalation
                                              deadline trigger */
    CHECK(strstr(buf, "live=0 ") != NULL);
    CHECK(strstr(buf, "clean=1 ") != NULL);
    CHECK(strstr(buf, "wedge=1 ") != NULL); /* the cutoff wedge */
    CHECK(strstr(buf, "proof=1 ") != NULL);
    CHECK(strstr(buf, "cutoff=1 ") != NULL);
    if (strstr(buf, "done=1") == NULL)
        fprintf(stderr, "DIAG epipe result: [%s] status=0x%x\n", buf,
                status);
    CHECK(strstr(buf, "done=1") != NULL);
    CHECK(after - before >= 15000);
    CHECK(after - before < 30000);
    return 0;
}

/* === Main ============================================================== */

/* Each case runs in its own capture child: the core's entry preamble
 * owns setsid() (a second in-process core call refuses with
 * CAPABILITY_MISSING — the preamble is once per process). */
static void run_case_child(const char *name, outer_test_fn fn)
{
    char errbuf[16384];
    int status;

    if (run_capture_child(fn, errbuf, sizeof errbuf, &status) != 0) {
        g_failures++;
        fprintf(stderr, "case %s: helper machinery broke\n", name);
        return;
    }
    gate_group(name, errbuf, status);
}

int main(int argc, char **argv)
{
    g_suite_argv0 = argv[0];
    dealpg4_outer_note_process_argv0(argv[0]);
    signal(SIGPIPE, SIG_IGN);
    if (argc >= 3 && strcmp(argv[1], "--fallback-peer") == 0)
        return fb_peer_main(argv[2]);
    if (argc >= 3 && strcmp(argv[1], "--case") == 0) {
        if (strcmp(argv[2], "stub-blocked") == 0)
            return case_stub_blocked_death_fn() != 0;
        if (strcmp(argv[2], "target-published") == 0)
            return case_target_published_death_fn() != 0;
        if (strcmp(argv[2], "released") == 0)
            return case_released_takeover_fn() != 0;
        if (strcmp(argv[2], "cancel-die") == 0)
            return case_cancel_then_die_fn() != 0;
        if (strcmp(argv[2], "wedge") == 0)
            return case_wedge_fn() != 0;
        if (strcmp(argv[2], "matrix") == 0)
            return case_cancel_matrix_fn() != 0;
        if (strcmp(argv[2], "reject-hold") == 0)
            return case_reject_hold_fn() != 0;
        if (strcmp(argv[2], "coord-kill") == 0)
            return case_coord_kill_fn() != 0;
        if (strcmp(argv[2], "cutoff") == 0)
            return case_cutoff_fn() != 0;
        if (strcmp(argv[2], "orphan") == 0)
            return case_orphan_fn() != 0;
        if (strcmp(argv[2], "epipe") == 0)
            return case_epipe_fn() != 0;
        return 1;
    }

    run_case_child("kill during STUB_BLOCKED", case_stub_blocked_death_fn);
    run_case_child("kill during TARGET_PUBLISHED",
                   case_target_published_death_fn);
    run_case_child("kill during RELEASED (takeover)",
                   case_released_takeover_fn);
    run_case_child("kill mid-cancel (pre-cancel fallback)",
                   case_cancel_then_die_fn);
    run_case_child("wedge rule (per-record deadline)",
                   case_wedge_fn);
    run_case_child("CANCEL validation over the live broker",
                   case_cancel_matrix_fn);
    run_case_child("nested REJECT hold -> wedge", case_reject_hold_fn);
    run_case_child("total-cancel: coordinator kill",
                   case_coord_kill_fn);
    run_case_child("total-cancel: INVOKE cutoff", case_cutoff_fn);
    run_case_child("total-cancel: orphaned outer (shell loss)",
                   case_orphan_fn);
    run_case_child("total-cancel: closed report stdout (EPIPE)",
                   case_epipe_fn);

    /* The core calls forked the suite binary as the coordinator: no
     * child may remain. */
    {
        siginfo_t si;

        memset(&si, 0, sizeof si);
        if (!(waitid(P_ALL, 0, &si, WEXITED | WNOHANG | WNOWAIT) == -1
              && errno == ECHILD))
            g_failures++;
    }
    if (g_failures > 0) {
        fprintf(stderr, "outer-fallback-tests: %d failures in %d checks\n",
                g_failures, g_checks);
        return 1;
    }
    fprintf(stderr, "outer-fallback-tests: %d checks passed\n", g_checks);
    return 0;
}
