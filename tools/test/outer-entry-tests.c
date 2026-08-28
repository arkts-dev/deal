/*
 * DEALPG4 outer entry component acceptance suite
 * (tools/test/outer-entry-tests.c).
 *
 * The component-level cases of ISSUE-0293 Verification, run against
 * tools/src/outer.c + monotonic.c + protocol.c + fi.c + selftest.c +
 * drain.c compiled with the pinned flags (tools/test/
 * run-outer-entry-tests.sh). Permanent and re-runnable; lives outside
 * the tools/src/ build glob so the pinned artifact build is unchanged.
 *
 * Case groups:
 *  1. mode-surface usage errors (direct dealpg4_outer_entry call in a
 *     fresh helper child): a missing "--", an empty coordinator argv,
 *     and malformed nonces (wrong length, non-hex, uppercase) each
 *     exit 2 with the usage message on stderr, fork nothing
 *     (waitid(P_ALL) reaches ECHILD in the caller), and create no
 *     socket path under the scratch socket dir.
 *  2. core-entry validation (direct dealpg4_outer_core call):
 *     limits-ordering violations (nestedStop + cleanupReserve >
 *     overallTimeoutMs) and each >= 1 minimum violation, a bad nonce,
 *     an empty coordinator argv, and an empty socket_dir each return
 *     CONFIG_INVALID (3) with no fork, no socket, no channel.
 *  3. preamble observation: a helper child calls the core with scaled
 *     OuterLimits and a scripted coordinator argv; the preamble
 *     completes (session leader via getsid(0) == getpgid(0) ==
 *     getpid(), subreaper read-back == 1, shellPid recorded, T0o
 *     monotonic) and the run terminates at the scaled total deadline
 *     with the report-fd flags restored and the OVERALL_TIMEOUT
 *     gate-failure report.
 *  4. entry refusals via the fault-injection catalog (installed
 *     through the selftest-child installer): FI_OUTER_SUBREAPER /
 *     FI_OUTER_SIGNALFD -> exit 4 with CAPABILITY_MISSING,
 *     FI_OUTER_TIMERFD -> exit 4 with TIMER_FAILED, FI_OUTER_NONCE ->
 *     exit 1 with NONCE_FAILED; each forks nothing and creates no
 *     socket; restoring the defaults restores the real path and an
 *     unknown site installs nothing.
 *  5. write-side discipline: the relay queue against a non-reading
 *     consumer — O_NONBLOCK writes, queued payload never exceeds the
 *     1 MiB per-stream cap, overflow dropped with the truncation flag,
 *     control records never dropped by pressure, the flush returns
 *     EAGAIN without blocking, and a scaled deadline armed through
 *     the canonical machinery fires on time while the queue stays
 *     pending (no write suspends a deadline); plus the drained-queue
 *     positive case.
 *  6. exit-status mapping: the D1 table over outcome facts.
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
#include <sys/wait.h>
#include <unistd.h>

#include "../src/monotonic.h"
#include "../src/outer.h"
#include "../src/selftest.h"

static int g_checks;
static int g_failures;

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
 * has no children, so the tested path forked nothing. */
static void check_no_children(void)
{
    siginfo_t si;

    memset(&si, 0, sizeof si);
    CHECK(waitid(P_ALL, 0, &si, WEXITED | WNOHANG | WNOWAIT) == -1
          && errno == ECHILD);
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
        close(pipefd[0]);
        if (dup2(pipefd[1], 2) == -1)
            _exit(125);
        close(pipefd[1]);
        _exit(fn() & 0xff);
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

/* === Group 1: mode-surface usage errors ================================ */

/* One usage case: build the argv shape and expect exit 2 + usage
 * message + no fork + no socket. */
static int usage_case(int argc0, char **argv0)
{
    int status;

    status = dealpg4_outer_entry(argc0, argv0);
    CHECK(status == 2);
    check_no_children();
    CHECK(outer_socket_path_count("build") == 0);
    return 0;
}

static int usage_all_fn(void)
{
    {
        char *argv[] = {(char *)"launcher", (char *)"outer", (char *)NONCE,
                        NULL};
        usage_case(3, argv);
    }
    {
        char *argv[] = {(char *)"launcher", (char *)"outer", (char *)NONCE,
                        (char *)"/bin/true", NULL};
        usage_case(4, argv); /* argv[3] is not "--" */
    }
    {
        char *argv[] = {(char *)"launcher", (char *)"outer", (char *)NONCE,
                        (char *)"--", NULL};
        usage_case(4, argv); /* empty coordinator argv */
    }
    {
        char *argv[] = {(char *)"launcher", (char *)"outer",
                        (char *)"0123456789abcdef0123456789abcde", NULL};
        usage_case(3, argv); /* 31-char nonce */
    }
    {
        char *argv[] = {(char *)"launcher", (char *)"outer",
                        (char *)"0123456789abcdef0123456789abcdef0", NULL};
        usage_case(3, argv); /* 33-char nonce */
    }
    {
        char *argv[] = {(char *)"launcher", (char *)"outer",
                        (char *)"zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz",
                        (char *)"--", (char *)"/bin/true", NULL};
        usage_case(5, argv); /* non-hex nonce */
    }
    {
        char *argv[] = {(char *)"launcher", (char *)"outer",
                        (char *)"0123456789ABCDEF0123456789ABCDEF",
                        (char *)"--", (char *)"/bin/true", NULL};
        usage_case(5, argv); /* uppercase nonce */
    }
    return 0;
}

/* === Group 2: core-entry validation ==================================== */

static int core_validation_fn(void)
{
    OuterLimits good = {4000, 500, 2000, 2000, 200};
    OuterLimits bad;
    char *argv[] = {(char *)"coordinator", NULL};
    char *empty_argv[] = {NULL};
    char *argv_null_first[] = {NULL};
    int status;

    /* Valid inputs would run the 4s stage: every case below must be
     * refused before that, so no case here reaches the loop. */

    bad = good;
    bad.nestedStopMs = 3000; /* nestedStop + cleanup (3000+2000) > 4000 */
    status = dealpg4_outer_core(&bad, NONCE, argv, "build", 1, NULL);
    CHECK(status == DEALPG4_EXIT_CONFIG_INVALID);

    bad = good;
    bad.readinessTimeoutMs = 0;
    status = dealpg4_outer_core(&bad, NONCE, argv, "build", 1, NULL);
    CHECK(status == DEALPG4_EXIT_CONFIG_INVALID);

    bad = good;
    bad.nestedStopMs = 0;
    status = dealpg4_outer_core(&bad, NONCE, argv, "build", 1, NULL);
    CHECK(status == DEALPG4_EXIT_CONFIG_INVALID);

    bad = good;
    bad.cleanupReserveMs = 0;
    status = dealpg4_outer_core(&bad, NONCE, argv, "build", 1, NULL);
    CHECK(status == DEALPG4_EXIT_CONFIG_INVALID);

    bad = good;
    bad.brokerStallMs = 0;
    status = dealpg4_outer_core(&bad, NONCE, argv, "build", 1, NULL);
    CHECK(status == DEALPG4_EXIT_CONFIG_INVALID);

    {
        char short_nonce[33] = "0123456789abcdef0123456789abcde";

        status = dealpg4_outer_core(&good, short_nonce, argv, "build",
                                    1, NULL);
        CHECK(status == DEALPG4_EXIT_CONFIG_INVALID); /* 31-char nonce */
    }

    {
        char upper_nonce[33] = "0123456789ABCDEF0123456789ABCDEF";

        status = dealpg4_outer_core(&good, upper_nonce, argv, "build",
                                    1, NULL);
        CHECK(status == DEALPG4_EXIT_CONFIG_INVALID); /* uppercase nonce */
    }

    status = dealpg4_outer_core(&good, NONCE, NULL, "build", 1, NULL);
    CHECK(status == DEALPG4_EXIT_CONFIG_INVALID); /* NULL argv */

    status = dealpg4_outer_core(&good, NONCE, empty_argv, "build", 1,
                                NULL);
    CHECK(status == DEALPG4_EXIT_CONFIG_INVALID); /* argv[0] == NULL */

    status = dealpg4_outer_core(&good, NONCE, argv_null_first, "build",
                                1, NULL);
    CHECK(status == DEALPG4_EXIT_CONFIG_INVALID);

    status = dealpg4_outer_core(&good, NONCE, argv, "", 1, NULL);
    CHECK(status == DEALPG4_EXIT_CONFIG_INVALID); /* empty socket_dir */

    status = dealpg4_outer_core(&good, NONCE, argv, NULL, 1, NULL);
    CHECK(status == DEALPG4_EXIT_CONFIG_INVALID); /* NULL socket_dir */

    status = dealpg4_outer_core(NULL, NONCE, argv, "build", 1, NULL);
    CHECK(status == DEALPG4_EXIT_CONFIG_INVALID); /* NULL limits */

    /* No fork, no socket, no channel anywhere above. */
    check_no_children();
    CHECK(outer_socket_path_count("build") == 0);
    return 0;
}

/* === Group 3: preamble observation ===================================== */

static int preamble_observation_fn(void)
{
    OuterLimits limits = {4000, 500, 2000, 2000, 200};
    char *argv[] = {(char *)"coordinator-script", NULL};
    int pipefd[2];
    uint64_t before;
    uint64_t after;
    pid_t shell;
    dealpg4_outer_result view;
    char report[512];
    ssize_t total = 0;
    int status;
    int is_subreaper = 0;
    int i;

    CHECK(pipe(pipefd) == 0);

    shell = getppid();
    before = dealpg4_now_ms();
    status = dealpg4_outer_core(&limits, NONCE, argv, "build",
                                pipefd[1], NULL);
    after = dealpg4_now_ms();
    close(pipefd[1]);

    /* The run terminated at the scaled total deadline (4000 ms) with
     * the stage gate failure. */
    CHECK(status == DEALPG4_OUTER_EXIT_GATE_FAILURE);
    CHECK(after - before >= 3900);
    CHECK(after - before < 30000);

    /* Preamble completed: session leader, subreaper read-back. */
    CHECK(getsid(0) == getpgid(0) && getpgid(0) == getpid());
    CHECK(prctl(PR_GET_CHILD_SUBREAPER, &is_subreaper) == 0
          && is_subreaper == 1);

    /* Observability view. */
    memset(&view, 0, sizeof view);
    dealpg4_outer_last_result(&view);
    CHECK(view.shell_pid == shell);
    CHECK((uint64_t)view.t0o >= before && (uint64_t)view.t0o <= after);
    CHECK(view.total_deadline == view.t0o + limits.overallTimeoutMs);
    CHECK(view.setsid_ok == 1);
    CHECK(view.subreaper_ok == 1);
    CHECK(view.timerfd_ok == 1);
    CHECK(view.signalfd_ok == 1);
    CHECK(view.readiness_fired == 1);
    CHECK(view.cutoff_fired == 1);
    CHECK(view.total_fired == 1);
    CHECK(view.gate_failure == 1);
    CHECK(view.exit_status == 1);
    CHECK(view.ntokens == 1);
    CHECK(strcmp(view.tokens[0], "OVERALL_TIMEOUT") == 0);
    CHECK(view.report_flags_captured == 1);
    CHECK(view.report_flags_restored == 1);

    /* The outerNonce: exactly 32 lowercase hex (getrandom-backed). */
    for (i = 0; i < 32; i++) {
        char c = view.outer_nonce[i];

        CHECK((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'));
    }
    CHECK(view.outer_nonce[32] == '\0');

    /* No fork (no coordinator machinery at this stage), no socket. */
    check_no_children();
    CHECK(outer_socket_path_count("build") == 0);

    /* The final report on the report fd: header + token line. */
    for (;;) {
        ssize_t r = read(pipefd[0], report + total,
                         sizeof report - (size_t)total - 1);

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
    CHECK(strstr(report, "OUTER final 1 ") != NULL);
    CHECK(strstr(report, " records=0\n") != NULL);
    CHECK(strstr(report, "OUTER token OVERALL_TIMEOUT\n") != NULL);
    return 0;
}

/* === Group 4: entry refusals via the fault-injection catalog =========== */

static const int outer_fail_sites[] = {
    FI_OUTER_SUBREAPER, FI_OUTER_TIMERFD, FI_OUTER_SIGNALFD, FI_OUTER_NONCE
};

static const dealpg4_fi_catalog outer_catalog = {
    NULL, 0, outer_fail_sites, 4, NULL, 0, NULL, 0
};

/* One entry refusal: install a single-shot fail override for the
 * site in a fresh grandchild (every core call runs the preamble
 * setsid(), which a previous call in the same process would make
 * fail), drive the mode entry, and expect the pinned status + token,
 * no fork (the grandchild encodes a waitid ECHILD re-check in its
 * exit status), and no socket. */
static void entry_refusal_case(int site, int want_status,
                               const char *want_token)
{
    dealpg4_fi_script_fail fails[1];
    dealpg4_fi_script script;
    char *argv[] = {(char *)"launcher", (char *)"outer", (char *)NONCE,
                    (char *)"--", (char *)"/bin/true", NULL};
    int pipefd[2];
    pid_t pid;
    char errbuf[256];
    size_t off = 0;
    int st;

    fails[0].site = site;
    fails[0].value = EPERM;
    fails[0].oneshot = 1;
    script.delays = NULL;
    script.ndelays = 0;
    script.fails = fails;
    script.nfails = 1;
    script.congests = NULL;
    script.ncongests = 0;

    CHECK(pipe(pipefd) == 0);
    pid = fork();
    CHECK(pid >= 0);
    if (pid == 0) {
        siginfo_t si;
        int status;

        close(pipefd[0]);
        if (dup2(pipefd[1], 2) == -1)
            _exit(125);
        close(pipefd[1]);
        if (dealpg4_fi_install_overrides(&script, &outer_catalog) != 0)
            _exit(126);
        status = dealpg4_outer_entry(5, argv);
        /* The refusal path forked nothing: waitid reaches ECHILD
         * (the grandchild has no children of its own). A failure is
         * encoded so the parent's status check trips. */
        memset(&si, 0, sizeof si);
        if (!(waitid(P_ALL, 0, &si, WEXITED | WNOHANG | WNOWAIT) == -1
              && errno == ECHILD))
            status = 100 + site;
        _exit(status & 0xff);
    }
    close(pipefd[1]);
    for (;;) {
        ssize_t r = read(pipefd[0], errbuf + off,
                         sizeof errbuf - off - 1);

        if (r > 0) {
            off += (size_t)r;
            continue;
        }
        if (r < 0 && errno == EINTR)
            continue;
        break; /* EOF: the grandchild exited */
    }
    close(pipefd[0]);
    errbuf[off] = '\0';
    CHECK(waitpid(pid, &st, 0) == pid);
    CHECK(WIFEXITED(st) && WEXITSTATUS(st) == want_status);
    CHECK(strstr(errbuf, want_token) != NULL);
    CHECK(outer_socket_path_count("build") == 0);
    dealpg4_fi_restore_defaults();
    /* Production defaults restored: every site reports the real
     * path (fail == 0). */
    CHECK(dealpg4_fi_hooks.fail(site) == 0);
}

/* Group 4 wrapper: the four entry refusals run sequentially (each in
 * its own grandchild, so every case gets a fresh non-leader process
 * for the preamble setsid()), followed by the unknown-site
 * installs-nothing check. Runs in one helper child. */
static int entry_refusals_fn(void)
{
    entry_refusal_case(FI_OUTER_SUBREAPER, DEALPG4_EXIT_CAPABILITY_MISSING,
                       "CAPABILITY_MISSING");
    entry_refusal_case(FI_OUTER_TIMERFD, DEALPG4_EXIT_CAPABILITY_MISSING,
                       "TIMER_FAILED");
    entry_refusal_case(FI_OUTER_SIGNALFD, DEALPG4_EXIT_CAPABILITY_MISSING,
                       "CAPABILITY_MISSING");
    entry_refusal_case(FI_OUTER_NONCE, DEALPG4_OUTER_EXIT_GATE_FAILURE,
                       "NONCE_FAILED");

    /* An unknown site installs nothing: the installer refuses the
     * script and the hook table stays at the production defaults. */
    {
        dealpg4_fi_script_fail fails[1];
        dealpg4_fi_script script;
        int unknown_site = 999;

        fails[0].site = unknown_site;
        fails[0].value = EPERM;
        fails[0].oneshot = 1;
        script.delays = NULL;
        script.ndelays = 0;
        script.fails = fails;
        script.nfails = 1;
        script.congests = NULL;
        script.ncongests = 0;
        CHECK(dealpg4_fi_install_overrides(&script, &outer_catalog) == -1);
        CHECK(dealpg4_fi_hooks.fail(FI_OUTER_SUBREAPER) == 0);
        CHECK(dealpg4_fi_hooks.fail(FI_OUTER_TIMERFD) == 0);
        CHECK(dealpg4_fi_hooks.fail(FI_OUTER_SIGNALFD) == 0);
        CHECK(dealpg4_fi_hooks.fail(FI_OUTER_NONCE) == 0);
    }
    return 0;
}

/* === Group 5: write-side discipline ==================================== */

static void write_side_discipline(void)
{
    int sv[2];
    dealpg4_outer_writeq q;
    static unsigned char arena[DEALPG4_OUTER_WRITEQ_ARENA_BYTES];
    char chunk[32768];
    int i;
    int snd = 4096;

    CHECK(socketpair(AF_UNIX, SOCK_STREAM, 0, sv) == 0);
    CHECK(fcntl(sv[1], F_SETFL, fcntl(sv[1], F_GETFL, 0) | O_NONBLOCK)
          != -1);
    (void)setsockopt(sv[1], SOL_SOCKET, SO_SNDBUF, &snd, sizeof snd);
    dealpg4_outer_writeq_init(&q, sv[1], arena, sizeof arena);
    /* A payload chunk is a serialized-record-shaped line: the queue
     * refuses bytes without the trailing LF (caller defect), so the
     * last byte is the LF. */
    memset(chunk, 'x', sizeof chunk);
    chunk[sizeof chunk - 1] = '\n';

    /* The 1 MiB per-stream payload cap: 32 x 32768 == 1 MiB accepted
     * exactly; the 33rd chunk is dropped with the truncation flag. */
    for (i = 0; i < 32; i++)
        CHECK(dealpg4_outer_writeq_queue(&q, chunk, sizeof chunk, 0, 0) == 0);
    CHECK(dealpg4_outer_writeq_payload_bytes(&q)
          == DEALPG4_RELAY_QUEUE_CAP_BYTES);
    CHECK(dealpg4_outer_writeq_queue(&q, chunk, sizeof chunk, 0, 0) == -1);
    CHECK(dealpg4_outer_writeq_overflow(&q) == 1);
    CHECK(dealpg4_outer_writeq_dropped_bytes(&q) == sizeof chunk);
    CHECK(dealpg4_outer_writeq_payload_bytes(&q)
          == DEALPG4_RELAY_QUEUE_CAP_BYTES);

    /* Catalog-bounded control records are never dropped by payload
     * pressure (the queue accepts one at the payload cap). */
    CHECK(dealpg4_outer_writeq_queue(&q, "DEALPG4 DONE clean\n", 19, 1, 0)
          == 0);

    /* Non-blocking flush against a consumer that never reads: the
     * socket send buffer fills and the flush returns EAGAIN (1)
     * without blocking or suspending anything. */
    CHECK(dealpg4_outer_writeq_flush(&q) == 1);
    CHECK(!dealpg4_outer_writeq_empty(&q));

    /* A scaled deadline armed through the canonical machinery fires
     * on time while the queue stays pending: the write path never
     * suspends a native deadline. */
    {
        dealpg4_deadline_ctx timer;
        uint64_t start = dealpg4_now_ms();
        uint64_t deadline;
        int fired = 0;

        CHECK(dealpg4_deadline_open(&timer) == 0);
        deadline = dealpg4_now_ms() + 1500;
        CHECK(dealpg4_deadline_arm(&timer, deadline) == 0);
        for (;;) {
            struct pollfd pfds[2];
            uint64_t remaining = dealpg4_deadline_remaining_ms(&timer);
            int rc;

            pfds[0].fd = timer.fd;
            pfds[0].events = POLLIN;
            pfds[0].revents = 0;
            pfds[1].fd = sv[1];
            pfds[1].events = POLLOUT;
            pfds[1].revents = 0;
            rc = poll(pfds, 2, remaining > 2000 ? 2000 : (int)remaining);
            if (rc < 0 && errno == EINTR)
                continue;
            CHECK(rc >= 0);
            if (pfds[0].revents & POLLIN) {
                uint64_t expirations = 0;

                CHECK(dealpg4_deadline_drain(&timer, &expirations) > 0);
                fired = 1;
                break;
            }
            if (pfds[1].revents & POLLOUT) {
                /* The consumer still never reads: EAGAIN again — the
                 * queue stays pending, the deadline keeps firing. */
                CHECK(dealpg4_outer_writeq_flush(&q) == 1);
            }
        }
        CHECK(fired == 1);
        CHECK(dealpg4_now_ms() - start >= 1400); /* on time, no hang */
        CHECK(!dealpg4_outer_writeq_empty(&q));
        dealpg4_deadline_close(&timer);
    }

    /* The positive case: a reading consumer drains the queue to empty
     * with the payload accounting returned to zero. */
    {
        int sv2[2];
        dealpg4_outer_writeq q2;
        static unsigned char arena2[DEALPG4_OUTER_WRITEQ_ARENA_BYTES];
        char buf[64];

        CHECK(socketpair(AF_UNIX, SOCK_STREAM, 0, sv2) == 0);
        CHECK(fcntl(sv2[1], F_SETFL, fcntl(sv2[1], F_GETFL, 0) | O_NONBLOCK)
              != -1);
        dealpg4_outer_writeq_init(&q2, sv2[1], arena2, sizeof arena2);
        CHECK(dealpg4_outer_writeq_queue(&q2, "DEALPG4 DONE clean\n", 19,
                                         1, 0) == 0);
        CHECK(dealpg4_outer_writeq_queue(&q2, chunk, sizeof chunk, 0, 0) == 0);
        CHECK(dealpg4_outer_writeq_flush(&q2) == 0);
        CHECK(dealpg4_outer_writeq_empty(&q2));
        CHECK(dealpg4_outer_writeq_payload_bytes(&q2) == 0);
        CHECK(dealpg4_outer_writeq_queued_bytes(&q2) == 0);
        CHECK(dealpg4_outer_writeq_overflow(&q2) == 0);
        CHECK(read(sv2[0], buf, sizeof buf) > 0);
        close(sv2[0]);
        close(sv2[1]);
    }

    close(sv[0]);
    close(sv[1]);
}

/* === Group 6: exit-status mapping ====================================== */

static void exit_mapping_cases(void)
{
    dealpg4_outer_outcome o;

    memset(&o, 0, sizeof o);
    o.coordinator_reaped = 1;
    o.coordinator_exited_0 = 1;
    o.records_total = 2;
    o.records_clean = 2;
    o.proof_passed = 1;
    CHECK(dealpg4_outer_map_exit(&o) == 0); /* the only clean shape */

    o.gate_failure = 1;
    CHECK(dealpg4_outer_map_exit(&o) == DEALPG4_OUTER_EXIT_GATE_FAILURE);
    o.gate_failure = 0;

    o.coordinator_reaped = 0;
    CHECK(dealpg4_outer_map_exit(&o) == DEALPG4_OUTER_EXIT_GATE_FAILURE);
    o.coordinator_reaped = 1;

    o.coordinator_exited_0 = 0; /* nonzero status or killed */
    CHECK(dealpg4_outer_map_exit(&o) == DEALPG4_OUTER_EXIT_GATE_FAILURE);
    o.coordinator_exited_0 = 1;

    o.records_live = 1; /* exit 0 with live records = COORDINATOR_LOST */
    CHECK(dealpg4_outer_map_exit(&o) == DEALPG4_OUTER_EXIT_GATE_FAILURE);
    o.records_live = 0;

    o.records_failed = 1; /* any FAILED record */
    CHECK(dealpg4_outer_map_exit(&o) == DEALPG4_OUTER_EXIT_GATE_FAILURE);
    o.records_failed = 0;

    o.proof_passed = 0; /* the final proof did not pass */
    CHECK(dealpg4_outer_map_exit(&o) == DEALPG4_OUTER_EXIT_GATE_FAILURE);

    CHECK(dealpg4_outer_map_exit(NULL) == DEALPG4_OUTER_EXIT_GATE_FAILURE);
}

/* === Main ============================================================== */

int main(void)
{
    char errbuf[1024];
    int status;

    /* Group 1: mode-surface usage errors (fresh child per group so
     * the no-children assertion is exact). */
    errbuf[0] = '\0';
    if (run_capture_child(usage_all_fn, errbuf, sizeof errbuf, &status)
        != 0) {
        fprintf(stderr, "FAIL: helper child machinery broke\n");
        return 1;
    }
    CHECK(WIFEXITED(status) && WEXITSTATUS(status) == 0);
    CHECK(strstr(errbuf, "usage:") != NULL);

    /* Group 2: core-entry validation. */
    errbuf[0] = '\0';
    if (run_capture_child(core_validation_fn, errbuf, sizeof errbuf,
                          &status) != 0) {
        fprintf(stderr, "FAIL: helper child machinery broke\n");
        return 1;
    }
    CHECK(WIFEXITED(status) && WEXITSTATUS(status) == 0);

    /* Group 3: preamble observation (runs ~4 s at the scaled total
     * deadline). */
    errbuf[0] = '\0';
    if (run_capture_child(preamble_observation_fn, errbuf, sizeof errbuf,
                          &status) != 0) {
        fprintf(stderr, "FAIL: helper child machinery broke\n");
        return 1;
    }
    CHECK(WIFEXITED(status) && WEXITSTATUS(status) == 0);

    /* Group 4: entry refusals via the fault-injection catalog (each
     * refusal case runs in the same helper child — every case
     * restores the defaults afterwards). */
    errbuf[0] = '\0';
    if (run_capture_child(entry_refusals_fn, errbuf, sizeof errbuf,
                          &status) != 0) {
        fprintf(stderr, "FAIL: helper child machinery broke\n");
        return 1;
    }
    CHECK(WIFEXITED(status) && WEXITSTATUS(status) == 0);

    /* Group 5: write-side discipline (pure userspace; runs in the
     * main process). */
    write_side_discipline();

    /* Group 6: exit-status mapping table. */
    exit_mapping_cases();

    if (g_failures > 0) {
        fprintf(stderr, "outer-entry-tests: %d/%d checks failed\n",
                g_failures, g_checks);
        return 1;
    }
    printf("outer-entry-tests: %d checks passed\n", g_checks);
    return 0;
}
