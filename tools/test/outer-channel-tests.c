/*
 * DEALPG4 outer nested control-channel state machine component
 * acceptance suite (tools/test/outer-channel-tests.c).
 *
 * The component-level cases of ISSUE-0297 Verification, run against
 * tools/src/outer.c + supervisor.c + monotonic.c + protocol.c + fi.c +
 * selftest.c + drain.c compiled with the pinned flags (tools/test/
 * run-outer-channel-tests.sh). Permanent and re-runnable; lives
 * outside the tools/src/ build glob so the pinned artifact build is
 * unchanged. The suite drives the real core (T1's preamble, loop,
 * write-side discipline, exit-status mapping + T2's coordinator
 * machinery + T3's broker socket/auth channel + T4's registry/live
 * phase/DONE trigger) with a scripted coordinator peer (the suite
 * binary re-execs itself), scaled OuterLimits, and in-process
 * spawn-seam compositions whose forked children run the scripted
 * nested-channel scenarios — real socketpair reads/writes, real
 * getpgid/getsid/kill//proc verification, real POLLOUT queueing, no
 * canned classifications.
 *
 * Case groups (each case runs in its own capture child — the core's
 * entry preamble owns setsid, once per process):
 *  1. Per-state expectation sets + relay rules: STUB_FORKED enters
 *     STUB_BLOCKED without broker relay; the no-STARTED schedule's
 *     CLEAN relays verbatim and terminates the record.
 *  2. ACK relay + RELEASED-at-write-completion: the validated ACK
 *     reaches the scripted child byte-for-byte with the registry
 *     nonce; STARTED (accepted only in RELEASED) then relays — the
 *     completion observation; the record history carries exactly
 *     FORKING -> STUB_BLOCKED -> TARGET_PUBLISHED -> RELEASED ->
 *     CLEAN; ack_applied survives an applied ACK.
 *  3. STUB_READY double-verification failures: each mismatch (wrong
 *     pgid, wrong sid, wrong nonce, dead stub pid, stubPid
 *     colliding with the supervisor pid, wrong invocation id) ->
 *     nothing forwarded, nothing answered, the record stays
 *     STUB_BLOCKED, and the nested T1 FAILED STARTUP_TIMEOUT
 *     relays verbatim as the single terminal answer; a wrong-nonce
 *     ACK naming a TARGET_PUBLISHED record is answered REJECT
 *     AUTH_FAILED (broker open, record untouched, no relay).
 *  4. Queued-write discard on terminality (the conforming
 *     intermediate path): FI_CONGEST_NESTED_CTRL keeps the ACK
 *     queued-but-unwritten; the child's own CLEAN success relays
 *     verbatim, the queued ACK is discarded without completing —
 *     no RELEASED, no fallback, no wedge, ack_applied cleared.
 *  5. Validated CANCEL application: the CANCEL reaches the child
 *     with the exact registry nonce; CANCELLING entered with the
 *     pre-cancel state recovered from the history (TARGET_PUBLISHED
 *     when the CANCEL lands after the forwarded STUB_READY); the
 *     cancel-path CLEAN cancelled relays and terminates; a
 *     validated CANCEL drops the queued-but-unwritten ACK and the
 *     child's own CLEAN cancelled then discards the queued CANCEL
 *     write (no RELEASED, no fallback, no wedge).
 *  6. CANCELLING expectation set: from RELEASED the post-release
 *     relays (OUT/OUT_END/REPORT) continue verbatim; from FORKING
 *     STUB_FORKED is accepted outer-internal (stubPid retained, no
 *     state change, never forwarded); STUB_READY during CANCELLING
 *     is accepted outer-internal only (never forwarded, never
 *     re-verified, no state change); a nested REJECT is consumed,
 *     never forwarded, no state change.
 *  7. Relay-verbatim battery: STARTED/EXEC_FAILED/OUT/OUT_END/
 *     REPORT/CLEAN arrive at the peer unchanged; the OUT 1 MiB
 *     per-stream relay cap drops the overflow with the truncation
 *     consequence (the stall rule never fires).
 *  8. Nested-channel PROTOCOL_ERROR battery: a state-unexpected
 *     record, a CR framing defect, and an endless oversize stream
 *     each close the record's channel and initiate that state's
 *     death fallback; the fallback executions complete the records
 *     CLEAN cancelled (the synthesized single terminal answers);
 *     the named token is gate-fatal.
 *  9. Death observation (the single-terminal-answer switch): a
 *     scripted supervisor dies after a verified STUB_READY while a
 *     surviving descendant holds the channel; the outer consumes
 *     the supervisor's own buffered records first, then switches
 *     the channel drain-only — the holder's late CLEAN/FAILED/
 *     REPORT records are consumed but never relayed; no fallback
 *     runs on a terminal record (the integration case).
 * 10. Outer-synthesized terminal records: the fallback-termination
 *     surface (dealpg4_outer_fallback_begin/_synthesize) drives two
 *     records — CLEAN <id> cancelled and FAILED <id> GROUP_SURVIVOR —
 *     each exactly once ahead of DONE with every nested-origin
 *     record consumed-not-relayed (no REPORT is ever synthesized,
 *     no nested-origin terminal appears).
 * 11. Integration (T1+T2+T3+T4+T5): the real serve core runs a real
 *     stub dance for sh -c 'echo hello'; INVOKE -> INVOKED ->
 *     STUB_READY (double-verified, forwarded) -> ACK (validated,
 *     relayed, RELEASED at write completion) -> STARTED -> OUT ->
 *     OUT_END/OUT_END -> REPORT -> CLEAN success — all observed by
 *     the peer in order — then DONE clean at the scaled cutoff with
 *     the CLEAN before it; the peer BYEs and exits 0; the outer
 *     runs the final proof and exits 0.
 * 12. D6 seam-catalog completion: the delay-site determinism
 *     (outer-pre-invoke-fork / outer-pre-ack-write /
 *     outer-pre-cancel-write — each sleeps exactly the scripted
 *     400 ms before its named step, measured by the peer across the
 *     causal round trip, the production 0 ms path unchanged) and
 *     the FI_OUTER_DEATH kill-equivalent death (the forked scenario
 *     process exits the scripted code 55 with no cleanup; the
 *     PDEATHSIG cascades kill the coordinator peer, the scripted
 *     nested supervisor, and the released target stand-in — three
 *     CLD_KILLED 9 reaps by the subreaper suite, the stale broker
 *     path abandoned as pinned).
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
#include <time.h>
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

/* Scaled OuterLimits: nestedStopMs 17000 keeps the delivered nested
 * budget T >= 15000 for the first ~2 s (the forked-record scenarios);
 * total 18000 bounds the slow live-record runs; brokerStallMs 200 for
 * the normal scenarios and 5000 for the relay-cap flood (the stall
 * rule must not fire while the queue accumulates — the peer starts
 * reading long before the 5 s bound). */
static const OuterLimits LIVE_LIMITS = {18000, 500, 17000, 1000, 200};
static const OuterLimits FLOOD_LIMITS = {18000, 500, 17000, 1000, 5000};
/* The DONE scenarios: the cutoff (T0o + nestedStopMs) must land before
 * the pinned escalation deadline (total - killAndProofReserveMs =
 * total - 5000), so the healthy post-DONE coordinator is never
 * signaled: cleanupReserveMs 8000 puts the escalation at T0o + 20000,
 * 3000 ms after the cutoff (T0o + 17000). */
static const OuterLimits DONE_LIMITS = {25000, 500, 17000, 8000, 200};

/* Monotonic now in ms (the peer-side delay measurements). */
static uint64_t peer_now_ms(void)
{
    struct timespec ts;

    if (clock_gettime(CLOCK_MONOTONIC, &ts) != 0)
        return 0;
    return (uint64_t)ts.tv_sec * 1000u + (uint64_t)(ts.tv_nsec / 1000000);
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

/* The stub stand-in: setsid()s (pid == pgid == sid), confirms the
 * session establishment to its parent through the ready pipe (so the
 * outer's re-verification can never race the setsid), and stays alive
 * for the verification window, then exits. */
static void chan_stub_runner(int ready_fd)
{
    if (setsid() == -1)
        _exit(3);
    (void)!write(ready_fd, "x", 1);
    sleep(3);
    _exit(0);
}

/* Fork the stub stand-in; waits for the child's setsid confirmation.
 * Retries a failed fork briefly (the suite forks many children); a
 * persistent failure exits the scripted child (the scenario is
 * invalid without a real stub identity). Returns its pid. */
static pid_t chan_fork_stub(void)
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
            chan_stub_runner(ready[1]);
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
            _exit(9); /* the stub died before confirming */
        }
    }
    close(ready[0]);
    return p;
}

/* The FI_OUTER_DEATH cascade stand-in stub: arms its own
 * PR_SET_PDEATHSIG = SIGKILL against the scripted serve child (the
 * composition double skips the production prctl), rechecks the
 * parent, setsid (pid == pgid == sid for the outer's double
 * verification), confirms, and then holds forever — it dies only by
 * the armed cascade when the serve child dies. */
static void chan_stub_runner_death(int ready_fd)
{
    pid_t parent = getppid();

    if (prctl(PR_SET_PDEATHSIG, SIGKILL) != 0)
        _exit(3);
    if (getppid() != parent)
        _exit(2);
    if (setsid() == -1)
        _exit(3);
    (void)!write(ready_fd, "x", 1);
    for (;;)
        sleep_ms(1000);
}

/* Fork the death-cascade stub stand-in (the chan_fork_stub retry
 * pattern with the armed runner). */
static pid_t chan_fork_stub_death(void)
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
            chan_stub_runner_death(ready[1]);
        }
        if (p > 0)
            break;
        sleep_ms(10);
    }
    close(ready[1]);
    if (p <= 0) {
        fprintf(stderr, "CHILD FAIL death-stub-fork errno=%d\n", errno);
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
            fprintf(stderr,
                    "CHILD FAIL death-stub-confirm r=%zd errno=%d\n", r,
                    errno);
            _exit(9);
        }
    }
    close(ready[0]);
    return p;
}

/* Build "DEALPG4 ACK <id> <nonce>\n" / "DEALPG4 CANCEL <id> <nonce>\n"
 * (the exact relay shape the outer must deliver). */
static void chan_expect_ctl(char *out, size_t cap, const char *type,
                            int64_t id, const char *nonce)
{
    snprintf(out, cap, "DEALPG4 %s %lld %s\n", type, (long long)id,
             nonce);
}

/* Verify the relayed ACK/CANCEL byte-for-byte (the exact registry
 * nonce); exits nonzero on any deviation. */
static void chan_verify_ctl(int fd, const char *type, int64_t id,
                            const char *nonce)
{
    char buf[128];
    char want[128];
    int rc = chan_read_line(fd, buf, sizeof buf, 5000);

    if (rc != 0) {
        fprintf(stderr, "CHILD FAIL ctl-read %s rc=%d\n", type, rc);
        _exit(10);
    }
    chan_expect_ctl(want, sizeof want, type, id, nonce);
    if (strcmp(buf, want) != 0) {
        fprintf(stderr, "CHILD FAIL ctl-bytes %s got=[%s] want=[%s]\n",
                type, buf, want);
        _exit(11);
    }
}

/* One scripted nested-channel scenario (the serve-surface child).
 * Returns 0 on success (the child's own assertions); the outer reaps
 * the exit status as belt-and-braces — the record's terminality never
 * depends on it. */
static int chan_script_child(const char *scenario, int fd, int64_t id,
                             const char *nonce, int64_t budget)
{
    char line[DEALPG4_MAX_LINE_OUT_BYTES + 64];
    pid_t stub;
    (void)budget;

    if (scenario == NULL)
        return 1;

    if (strcmp(scenario, "forked") == 0) {
        /* FORKING -> STUB_BLOCKED via STUB_FORKED (outer-internal,
         * never forwarded); then the no-STARTED schedule's CLEAN
         * terminates the record. */
        stub = chan_fork_stub();
        snprintf(line, sizeof line,
                 "DEALPG4 STUB_FORKED %lld %d\n", (long long)id,
                 (int)stub);
        chan_write_line(fd, line);
        snprintf(line, sizeof line, "DEALPG4 CLEAN %lld success\n",
                 (long long)id);
        chan_write_line(fd, line);
        return 0;
    }

    if (strcmp(scenario, "ready-ok") == 0) {
        /* The real identity publication: the stub did setsid, so
         * pid == pgid == sid; the nonce is the record's. The ACK
         * relay must arrive byte-for-byte; then STARTED + CLEAN. */
        stub = chan_fork_stub();
        snprintf(line, sizeof line,
                 "DEALPG4 STUB_FORKED %lld %d\n", (long long)id,
                 (int)stub);
        chan_write_line(fd, line);
        snprintf(line, sizeof line,
                 "DEALPG4 STUB_READY %lld %d %d %d %s\n", (long long)id,
                 (int)stub, (int)stub, (int)stub, nonce);
        chan_write_line(fd, line);
        chan_verify_ctl(fd, "ACK", id, nonce);
        snprintf(line, sizeof line, "DEALPG4 STARTED %lld\n",
                 (long long)id);
        chan_write_line(fd, line);
        snprintf(line, sizeof line, "DEALPG4 CLEAN %lld success\n",
                 (long long)id);
        chan_write_line(fd, line);
        return 0;
    }

    if (strcmp(scenario, "read-clean") == 0) {
        /* The conforming intermediate path (the congestion seam keeps
         * the ACK queued-but-unwritten): the child never reads its
         * channel; its own CLEAN success is the single terminal
         * answer. */
        stub = chan_fork_stub();
        snprintf(line, sizeof line,
                 "DEALPG4 STUB_FORKED %lld %d\n", (long long)id,
                 (int)stub);
        chan_write_line(fd, line);
        snprintf(line, sizeof line,
                 "DEALPG4 STUB_READY %lld %d %d %d %s\n", (long long)id,
                 (int)stub, (int)stub, (int)stub, nonce);
        chan_write_line(fd, line);
        sleep_ms(400); /* the ACK is queued-but-unwritten by now */
        snprintf(line, sizeof line, "DEALPG4 CLEAN %lld success\n",
                 (long long)id);
        chan_write_line(fd, line);
        return 0;
    }

    if (strcmp(scenario, "cancel-drop-ack") == 0) {
        /* Same as read-clean, but the peer additionally sends a
         * validated CANCEL while the ACK is queued-but-unwritten:
         * the ACK is dropped at the CANCEL application, the CANCEL
         * write itself stays queued (congested), and the child's own
         * CLEAN cancelled discards it without completing. */
        stub = chan_fork_stub();
        snprintf(line, sizeof line,
                 "DEALPG4 STUB_FORKED %lld %d\n", (long long)id,
                 (int)stub);
        chan_write_line(fd, line);
        snprintf(line, sizeof line,
                 "DEALPG4 STUB_READY %lld %d %d %d %s\n", (long long)id,
                 (int)stub, (int)stub, (int)stub, nonce);
        chan_write_line(fd, line);
        sleep_ms(600); /* the ACK and the CANCEL are both queued */
        snprintf(line, sizeof line, "DEALPG4 CLEAN %lld cancelled\n",
                 (long long)id);
        chan_write_line(fd, line);
        return 0;
    }

    if (strcmp(scenario, "cancel-read") == 0) {
        /* A validated CANCEL must reach the child with the exact
         * registry nonce; the cancel-path CLEAN cancelled terminates
         * the record. */
        stub = chan_fork_stub();
        snprintf(line, sizeof line,
                 "DEALPG4 STUB_FORKED %lld %d\n", (long long)id,
                 (int)stub);
        chan_write_line(fd, line);
        snprintf(line, sizeof line,
                 "DEALPG4 STUB_READY %lld %d %d %d %s\n", (long long)id,
                 (int)stub, (int)stub, (int)stub, nonce);
        chan_write_line(fd, line);
        chan_verify_ctl(fd, "CANCEL", id, nonce);
        snprintf(line, sizeof line, "DEALPG4 CLEAN %lld cancelled\n",
                 (long long)id);
        chan_write_line(fd, line);
        return 0;
    }

    if (strcmp(scenario, "cancel-cycle") == 0) {
        /* CANCELLING-from-RELEASED: the post-release relays continue
         * verbatim on the cancel path. */
        stub = chan_fork_stub();
        snprintf(line, sizeof line,
                 "DEALPG4 STUB_FORKED %lld %d\n", (long long)id,
                 (int)stub);
        chan_write_line(fd, line);
        snprintf(line, sizeof line,
                 "DEALPG4 STUB_READY %lld %d %d %d %s\n", (long long)id,
                 (int)stub, (int)stub, (int)stub, nonce);
        chan_write_line(fd, line);
        chan_verify_ctl(fd, "ACK", id, nonce);
        snprintf(line, sizeof line, "DEALPG4 STARTED %lld\n",
                 (long long)id);
        chan_write_line(fd, line);
        chan_verify_ctl(fd, "CANCEL", id, nonce);
        /* Post-release relays after the CANCEL landed (the
         * CANCELLING expectation set). */
        snprintf(line, sizeof line, "DEALPG4 OUT %lld out aabb\n",
                 (long long)id);
        chan_write_line(fd, line);
        snprintf(line, sizeof line, "DEALPG4 OUT_END %lld out\n",
                 (long long)id);
        chan_write_line(fd, line);
        snprintf(line, sizeof line,
                 "DEALPG4 REPORT 0 0 12 5 3 2 1 1 1 1 0 2 0 0 0 1 1 1"
                 " -\n");
        chan_write_line(fd, line);
        snprintf(line, sizeof line, "DEALPG4 CLEAN %lld cancelled\n",
                 (long long)id);
        chan_write_line(fd, line);
        return 0;
    }

    if (strcmp(scenario, "death-cascade") == 0) {
        /* The FI_OUTER_DEATH cascade stand-in: the serve child arms
         * its own PR_SET_PDEATHSIG = SIGKILL against the outer (the
         * composition double skips the production prctl), publishes a
         * real stub identity, consumes the validated ACK, publishes
         * STARTED, and then holds forever with the armed stub
         * stand-in alive — when the outer's scripted death fires the
         * kernel cascade kills the coordinator, this nested
         * supervisor, and the released target stand-in. */
        if (prctl(PR_SET_PDEATHSIG, SIGKILL) != 0)
            return 1;
        stub = chan_fork_stub_death();
        snprintf(line, sizeof line,
                 "DEALPG4 STUB_FORKED %lld %d\n", (long long)id,
                 (int)stub);
        chan_write_line(fd, line);
        snprintf(line, sizeof line,
                 "DEALPG4 STUB_READY %lld %d %d %d %s\n", (long long)id,
                 (int)stub, (int)stub, (int)stub, nonce);
        chan_write_line(fd, line);
        chan_verify_ctl(fd, "ACK", id, nonce);
        snprintf(line, sizeof line, "DEALPG4 STARTED %lld\n",
                 (long long)id);
        chan_write_line(fd, line);
        for (;;)
            sleep_ms(1000);
    }

    if (strcmp(scenario, "cancel-from-forking") == 0) {
        /* The CANCEL landed while the record was FORKING: the
         * STUB_FORKED arriving later is accepted outer-internal
         * (stubPid retained, no state change, never forwarded); the
         * cancel-path CLEAN cancelled terminates the record. */
        sleep_ms(600); /* the peer CANCELs right after INVOKED */
        stub = chan_fork_stub();
        snprintf(line, sizeof line,
                 "DEALPG4 STUB_FORKED %lld %d\n", (long long)id,
                 (int)stub);
        chan_write_line(fd, line);
        snprintf(line, sizeof line, "DEALPG4 CLEAN %lld cancelled\n",
                 (long long)id);
        chan_write_line(fd, line);
        return 0;
    }

    if (strcmp(scenario, "stub-ready-cancelling") == 0) {
        /* STUB_READY arriving after a pre-release CANCEL: accepted
         * outer-internal only — never forwarded, never re-verified,
         * no state change (a CANCELLING record never enters
         * TARGET_PUBLISHED). */
        stub = chan_fork_stub();
        snprintf(line, sizeof line,
                 "DEALPG4 STUB_FORKED %lld %d\n", (long long)id,
                 (int)stub);
        chan_write_line(fd, line);
        chan_verify_ctl(fd, "CANCEL", id, nonce);
        snprintf(line, sizeof line,
                 "DEALPG4 STUB_READY %lld %d %d %d %s\n", (long long)id,
                 (int)stub, (int)stub, (int)stub, nonce);
        chan_write_line(fd, line);
        snprintf(line, sizeof line, "DEALPG4 CLEAN %lld cancelled\n",
                 (long long)id);
        chan_write_line(fd, line);
        return 0;
    }

    if (strcmp(scenario, "cancel-then-die") == 0) {
        /* The record enters CANCELLING with the pre-cancel state
         * STUB_BLOCKED; the child then dies without a terminal
         * record — the outer observes the death and initiates the
         * pre-cancel state's death fallback (the fallback execution
         * is the fallback child's). */
        stub = chan_fork_stub();
        snprintf(line, sizeof line,
                 "DEALPG4 STUB_FORKED %lld %d\n", (long long)id,
                 (int)stub);
        chan_write_line(fd, line);
        chan_verify_ctl(fd, "CANCEL", id, nonce);
        return 0; /* die without a terminal record */
    }

    if (strcmp(scenario, "reject-nested") == 0) {
        /* A nested REJECT (the CANCEL_AUTH_FAILED answer) is
         * supervisor defect: consumed, never forwarded, no state
         * change — the record is held to its per-record deadline (the
         * fallback child's wedge rule); the child then terminates the
         * record itself. */
        stub = chan_fork_stub();
        snprintf(line, sizeof line,
                 "DEALPG4 STUB_FORKED %lld %d\n", (long long)id,
                 (int)stub);
        chan_write_line(fd, line);
        snprintf(line, sizeof line,
                 "DEALPG4 REJECT %lld - CANCEL_AUTH_FAILED\n",
                 (long long)id);
        chan_write_line(fd, line);
        snprintf(line, sizeof line, "DEALPG4 CLEAN %lld cancelled\n",
                 (long long)id);
        chan_write_line(fd, line);
        return 0;
    }

    if (strcmp(scenario, "verify-ok-then-clean") == 0) {
        /* Double verification passes; the record enters
         * TARGET_PUBLISHED; no ACK ever arrives (the peer ACKs with a
         * wrong nonce — the record-level AUTH_FAILED rejection keeps
         * the record untouched); the nested T1 expiry then arrives as
         * FAILED STARTUP_TIMEOUT and relays verbatim as the single
         * terminal answer. */
        stub = chan_fork_stub();
        snprintf(line, sizeof line,
                 "DEALPG4 STUB_FORKED %lld %d\n", (long long)id,
                 (int)stub);
        chan_write_line(fd, line);
        snprintf(line, sizeof line,
                 "DEALPG4 STUB_READY %lld %d %d %d %s\n", (long long)id,
                 (int)stub, (int)stub, (int)stub, nonce);
        chan_write_line(fd, line);
        sleep_ms(500);
        snprintf(line, sizeof line,
                 "DEALPG4 FAILED %lld STARTUP_TIMEOUT\n", (long long)id);
        chan_write_line(fd, line);
        return 0;
    }

    if (strcmp(scenario, "verify-fail-wrong-pgid") == 0
        || strcmp(scenario, "verify-fail-wrong-sid") == 0
        || strcmp(scenario, "verify-fail-wrong-nonce") == 0
        || strcmp(scenario, "verify-fail-dead-pid") == 0
        || strcmp(scenario, "verify-fail-self-pid") == 0
        || strcmp(scenario, "verify-fail-wrong-id") == 0) {
        /* A failed re-verification: nothing forwarded, nothing
         * answered; the record stays STUB_BLOCKED and the nested
         * T1 expiry (FAILED STARTUP_TIMEOUT) relays verbatim. */
        int64_t pid = 0;
        int64_t pgid = 0;
        int64_t sid = 0;
        const char *n = nonce;
        int64_t rid = id;

        stub = chan_fork_stub();
        pid = (int64_t)stub;
        pgid = (int64_t)stub;
        sid = (int64_t)stub;
        if (strcmp(scenario, "verify-fail-wrong-pgid") == 0)
            pgid = (int64_t)stub + 1;
        if (strcmp(scenario, "verify-fail-wrong-sid") == 0)
            sid = (int64_t)stub + 1;
        if (strcmp(scenario, "verify-fail-wrong-nonce") == 0)
            n = BAD_NONCE;
        if (strcmp(scenario, "verify-fail-dead-pid") == 0)
            pid = 4190000; /* no such task: kill(stubPid, 0) fails */
        if (strcmp(scenario, "verify-fail-self-pid") == 0)
            pid = (int64_t)getpid(); /* == the supervisor pid: the
                                        collision check */
        if (strcmp(scenario, "verify-fail-wrong-id") == 0)
            rid = id + 1;
        snprintf(line, sizeof line,
                 "DEALPG4 STUB_FORKED %lld %d\n", (long long)id,
                 (int)stub);
        chan_write_line(fd, line);
        snprintf(line, sizeof line,
                 "DEALPG4 STUB_READY %lld %lld %lld %lld %s\n",
                 (long long)rid, (long long)pid, (long long)pgid,
                 (long long)sid, n);
        chan_write_line(fd, line);
        sleep_ms(500); /* the re-verification is synchronous; the
                          sleep widens the no-answer window */
        snprintf(line, sizeof line,
                 "DEALPG4 FAILED %lld STARTUP_TIMEOUT\n", (long long)id);
        chan_write_line(fd, line);
        return 0;
    }

    if (strcmp(scenario, "relay-verbatim") == 0) {
        /* The RELEASED relay battery: every post-release record
         * reaches the peer unchanged. */
        stub = chan_fork_stub();
        snprintf(line, sizeof line,
                 "DEALPG4 STUB_FORKED %lld %d\n", (long long)id,
                 (int)stub);
        chan_write_line(fd, line);
        snprintf(line, sizeof line,
                 "DEALPG4 STUB_READY %lld %d %d %d %s\n", (long long)id,
                 (int)stub, (int)stub, (int)stub, nonce);
        chan_write_line(fd, line);
        chan_verify_ctl(fd, "ACK", id, nonce);
        snprintf(line, sizeof line, "DEALPG4 STARTED %lld\n",
                 (long long)id);
        chan_write_line(fd, line);
        snprintf(line, sizeof line, "DEALPG4 EXEC_FAILED %lld 2\n",
                 (long long)id);
        chan_write_line(fd, line);
        snprintf(line, sizeof line, "DEALPG4 OUT %lld out deadbeef\n",
                 (long long)id);
        chan_write_line(fd, line);
        snprintf(line, sizeof line, "DEALPG4 OUT_END %lld out\n",
                 (long long)id);
        chan_write_line(fd, line);
        snprintf(line, sizeof line,
                 "DEALPG4 REPORT 1 0 20 6 4 2 1 1 1 1 0 4 0 0 0 1 1 1"
                 " -\n");
        chan_write_line(fd, line);
        snprintf(line, sizeof line, "DEALPG4 CLEAN %lld success\n",
                 (long long)id);
        chan_write_line(fd, line);
        return 0;
    }

    if (strcmp(scenario, "flood") == 0) {
        /* > 1 MiB of OUT payload on one stream: the relay queue drops
         * the overflow with the truncation consequence (the peer
         * asserts the cap). */
        static char chunk[DEALPG4_OUT_MAX_HEX_CHARS + 1];
        char prefix[64];
        int i;

        stub = chan_fork_stub();
        snprintf(line, sizeof line,
                 "DEALPG4 STUB_FORKED %lld %d\n", (long long)id,
                 (int)stub);
        chan_write_line(fd, line);
        snprintf(line, sizeof line,
                 "DEALPG4 STUB_READY %lld %d %d %d %s\n", (long long)id,
                 (int)stub, (int)stub, (int)stub, nonce);
        chan_write_line(fd, line);
        chan_verify_ctl(fd, "ACK", id, nonce);
        memset(chunk, 'a', sizeof chunk - 1);
        chunk[sizeof chunk - 1] = '\0';
        snprintf(prefix, sizeof prefix, "DEALPG4 OUT %lld out ",
                 (long long)id);
        for (i = 0; i < 70; i++) {
            size_t plen = strlen(prefix);

            memcpy(line, prefix, plen);
            memcpy(line + plen, chunk, DEALPG4_OUT_MAX_HEX_CHARS);
            line[plen + DEALPG4_OUT_MAX_HEX_CHARS] = '\n';
            chan_write_line(fd, line);
        }
        snprintf(line, sizeof line, "DEALPG4 OUT_END %lld out\n",
                 (long long)id);
        chan_write_line(fd, line);
        snprintf(line, sizeof line, "DEALPG4 CLEAN %lld success\n",
                 (long long)id);
        chan_write_line(fd, line);
        return 0;
    }

    if (strcmp(scenario, "defect-state") == 0) {
        /* STUB_BLOCKED then STARTED: state-unexpected ->
         * PROTOCOL_ERROR (the channel closes, the death fallback is
         * initiated — the fallback execution is the fallback
         * child's). */
        stub = chan_fork_stub();
        snprintf(line, sizeof line,
                 "DEALPG4 STUB_FORKED %lld %d\n", (long long)id,
                 (int)stub);
        chan_write_line(fd, line);
        snprintf(line, sizeof line, "DEALPG4 STARTED %lld\n",
                 (long long)id);
        chan_write_line(fd, line);
        sleep_ms(200);
        return 0;
    }

    if (strcmp(scenario, "defect-cr") == 0) {
        /* A CR anywhere: framing defect -> PROTOCOL_ERROR. */
        snprintf(line, sizeof line, "DEALPG4 STUB_FORKED %lld 1\r\n",
                 (long long)id);
        chan_write_line(fd, line);
        sleep_ms(200);
        return 0;
    }

    if (strcmp(scenario, "defect-oversize") == 0) {
        /* An endless stream without LF (beyond the largest
         * nested-origin record cap): the read-side bound is the
         * oversize framing defect -> PROTOCOL_ERROR. */
        static char filler[70000];

        memset(filler, 'a', sizeof filler);
        {
            size_t off = 0;

            while (off < sizeof filler) {
                ssize_t w = write(fd, filler + off, sizeof filler - off);

                if (w > 0) {
                    off += (size_t)w;
                    continue;
                }
                if (w < 0 && errno == EINTR)
                    continue;
                break; /* the outer closed (EPIPE/ECONNRESET) */
            }
        }
        sleep_ms(200);
        return 0;
    }

    if (strcmp(scenario, "death") == 0) {
        /* The supervisor publishes a verified STUB_READY, then dies
         * while a surviving holder descendant keeps the channel
         * write end open: the outer observes the reap, switches the
         * channel drain-only, and the holder's late CLEAN/FAILED/
         * REPORT records are consumed but never relayed. */
        pid_t holder;

        stub = chan_fork_stub();
        snprintf(line, sizeof line,
                 "DEALPG4 STUB_FORKED %lld %d\n", (long long)id,
                 (int)stub);
        chan_write_line(fd, line);
        snprintf(line, sizeof line,
                 "DEALPG4 STUB_READY %lld %d %d %d %s\n", (long long)id,
                 (int)stub, (int)stub, (int)stub, nonce);
        chan_write_line(fd, line);
        holder = fork();
        if (holder < 0)
            return 1;
        if (holder == 0) {
            /* The holder: keeps the write end open, waits for the
             * outer to observe the supervisor's death, then writes
             * the late records and closes. */
            sleep_ms(1500);
            snprintf(line, sizeof line, "DEALPG4 CLEAN %lld success\n",
                     (long long)id);
            chan_write_line(fd, line);
            snprintf(line, sizeof line, "DEALPG4 FAILED %lld X\n",
                     (long long)id);
            chan_write_line(fd, line);
            snprintf(line, sizeof line,
                     "DEALPG4 REPORT 0 0 1 0 0 0 0 0 0 0 0 0 0 0 0 0 0"
                     " 0 -\n");
            chan_write_line(fd, line);
            _exit(0);
        }
        sleep_ms(700); /* the peer reads the forwarded STUB_READY */
        return 0;      /* the supervisor dies */
    }

    if (strcmp(scenario, "synth-child") == 0) {
        /* Records published before the outer-side termination began
         * (the composition calls fallback_begin/_synthesize after
         * this child exits): all of them are consumed but never
         * relayed — the synthesized record is the single terminal
         * answer. */
        stub = chan_fork_stub();
        snprintf(line, sizeof line,
                 "DEALPG4 STUB_FORKED %lld %d\n", (long long)id,
                 (int)stub);
        chan_write_line(fd, line);
        snprintf(line, sizeof line,
                 "DEALPG4 STUB_READY %lld %d %d %d %s\n", (long long)id,
                 (int)stub, (int)stub, (int)stub, nonce);
        chan_write_line(fd, line);
        snprintf(line, sizeof line, "DEALPG4 CLEAN %lld success\n",
                 (long long)id);
        chan_write_line(fd, line);
        snprintf(line, sizeof line, "DEALPG4 FAILED %lld X\n",
                 (long long)id);
        chan_write_line(fd, line);
        snprintf(line, sizeof line,
                 "DEALPG4 REPORT 0 0 1 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0"
                 " -\n");
        chan_write_line(fd, line);
        return 0;
    }

    fprintf(stderr, "CHILD FAIL unknown-scenario %s\n",
            scenario != NULL ? scenario : "?");
    return 1;
}

/* === Spawn-seam compositions =========================================== */

#define CHAN_SPAWN_SCRIPT 0  /* fork the scripted child */
#define CHAN_SPAWN_REAL   1  /* fork the real serve core (integration) */
#define CHAN_SPAWN_SYNTH  2  /* scripted child + wait + begin/synthesize */

typedef struct chan_spawn {
    int mode;
    int fork_calls;
    int64_t last_id;
    char last_nonce[DEALPG4_NONCE_HEX_CHARS + 1];
    int last_control_fd;
    int begin_rc;
    int synth_rc;
} chan_spawn;

static int chan_fork_nested(const dealpg4_outer_spawn *self,
                            char *const serve_argv[],
                            const char nonce[33], int64_t budget_t,
                            int64_t invocation_id, int child_control_fd)
{
    chan_spawn *sp = (chan_spawn *)self->opaque;
    const char *scenario = NULL;
    pid_t pid;
    int status;

    sp->fork_calls++;
    sp->last_id = invocation_id;
    memcpy(sp->last_nonce, nonce, DEALPG4_NONCE_HEX_CHARS);
    sp->last_nonce[DEALPG4_NONCE_HEX_CHARS] = '\0';
    sp->last_control_fd = child_control_fd;
    if (serve_argv != NULL && serve_argv[4] != NULL)
        scenario = serve_argv[4];
    /* Peer scenarios that CANCEL without ever receiving a
     * STUB_READY need the exact registry nonce: the composition
     * (running in-process at fork time) publishes it. */
    if (scenario != NULL
        && (strcmp(scenario, "stub-ready-cancelling") == 0
            || strcmp(scenario, "cancel-from-forking") == 0
            || strcmp(scenario, "cancel-then-die") == 0)) {
        FILE *f = fopen("build/.chan-rec-nonce", "w");

        if (f != NULL) {
            fprintf(f, "%s\n", nonce);
            fclose(f);
        }
    }

    pid = fork();
    if (pid < 0) {
        errno = EAGAIN;
        return -1;
    }
    if (pid == 0) {
        int rc;

        if (sp->mode == CHAN_SPAWN_REAL) {
            /* The real serve core: fd 0 = the per-invocation control
             * channel, stdio -> /dev/null, the D3 surface. */
            int devnull;

            if (child_control_fd != 0) {
                if (dup2(child_control_fd, 0) == -1)
                    _exit(3);
                close(child_control_fd);
            }
            /* The serve-surface fd discipline (supervisor-engine D3 —
             * exactly what dealpg4_serve_entry applies before the
             * core): O_NONBLOCK|FD_CLOEXEC on the control channel. */
            {
                int fl = fcntl(0, F_GETFL);
                int fs = fcntl(0, F_GETFD);

                if (fl == -1 || fs == -1
                    || fcntl(0, F_SETFL, fl | O_NONBLOCK) == -1
                    || fcntl(0, F_SETFD, fs | FD_CLOEXEC) == -1)
                    _exit(3);
            }
            devnull = open("/dev/null", O_WRONLY);
            if (devnull >= 0) {
                (void)dup2(devnull, 1);
                (void)dup2(devnull, 2);
                close(devnull);
            }
            rc = dealpg4_supervise_core(
                (const char **)&serve_argv[4], serve_argv[2], nonce,
                invocation_id, budget_t, 0, 0);
            _exit(rc);
        }
        rc = chan_script_child(scenario, child_control_fd, invocation_id,
                               nonce, budget_t);
        _exit(rc != 0 ? 7 : 0);
    }

    if (sp->mode == CHAN_SPAWN_SYNTH) {
        /* The outer-side fallback-termination surface: wait for the
         * scripted child's records to be fully buffered, then begin
         * the termination (drain-only switch + queued-write discard)
         * and synthesize the record's single terminal answer (the
         * first record CLEAN cancelled, the second FAILED
         * GROUP_SURVIVOR). */
        while (waitpid(pid, &status, 0) != pid)
            ;
        sp->begin_rc = dealpg4_outer_fallback_begin(invocation_id);
        sp->synth_rc = dealpg4_outer_fallback_synthesize(
            invocation_id, sp->fork_calls == 1, "GROUP_SURVIVOR");
    }
    return pid;
}

static dealpg4_outer_spawn make_chan_spawn(chan_spawn *sp, int mode)
{
    dealpg4_outer_spawn out;

    memset(sp, 0, sizeof *sp);
    sp->mode = mode;
    out.fork_nested = chan_fork_nested;
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
    if (strcmp(line, "DEALPG4 HELLO_OK 4 63\n") != 0)
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

/* Build the integration INVOKE: argv ["sh", "-c", "echo hello"]. */
static void build_invoke_sh(char *buf, size_t cap, const char *tag)
{
    char a[64];
    char b[64];
    char c[64];

    hex_encode("sh", a, sizeof a);
    hex_encode("-c", b, sizeof b);
    hex_encode("echo hello", c, sizeof c);
    snprintf(buf, cap, "DEALPG4 INVOKE %s 2f 3 %s %s %s\n", tag, a, b, c);
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

    if (sscanf(line,
               "DEALPG4 STUB_READY %lld %lld %lld %lld %63s", &v, &pid,
               &pgid, &sid, n) != 5)
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

/* Expect an exact line (0 = matched; 1 = mismatch; -1 = EOF/timeout). */
static int peer_expect_line(int fd, const char *want)
{
    char line[DEALPG4_MAX_LINE_OUT_BYTES + 64];
    int rc = peer_read_line(fd, line, sizeof line, 20000);

    if (rc != 0)
        return -1;
    if (strcmp(line, want) != 0) {
        fprintf(stderr, "PEER FAIL line got=[%s] want=[%s]\n", line,
                want);
        return 1;
    }
    return 0;
}

/* Expect a bounded read timeout (nothing pending at the peer). */
static int peer_expect_quiet(int fd, int timeout_ms)
{
    char line[512];
    int rc = peer_read_line(fd, line, sizeof line, timeout_ms);

    if (rc == -2)
        return 0;
    if (rc == 0) {
        fprintf(stderr, "PEER FAIL unexpected-line %s\n", line);
        return 1;
    }
    return 1;
}

/* One scripted coordinator scenario. Returns the exit status (the
 * peer's own assertions). */
static int chan_peer_main(const char *scenario)
{
    const char *path = getenv("DEALPG4_BROKER_PATH");
    const char *nonce = getenv("DEALPG4_NONCE");
    char line[DEALPG4_MAX_LINE_OUT_BYTES + 64];
    char inv[DEALPG4_MAX_LINE_OTHER_BYTES];
    char rec_nonce[DEALPG4_NONCE_HEX_CHARS + 1];
    int64_t id = 0;
    int64_t id2 = 0;
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

    if (strcmp(scenario, "forked") == 0) {
        /* STUB_FORKED is outer-internal (never forwarded); the
         * no-STARTED schedule's CLEAN relays verbatim. */
        build_invoke(inv, sizeof inv, "tag", "forked");
        if (peer_invoke_one(fd, inv, &id) != 0
            || peer_expect_line(fd, "DEALPG4 CLEAN 1 success\n") != 0) {
            fprintf(stderr, "PEER FAIL forked\n");
            return 1;
        }
        close(fd);
        printf("PEER forked\n");
        fflush(stdout);
        return 0;
    }

    if (strcmp(scenario, "ready-ok") == 0) {
        /* The double-verified STUB_READY forward, the validated ACK
         * relay (RELEASED entered at write completion — STARTED is
         * accepted only in RELEASED), the verbatim STARTED/CLEAN. */
        build_invoke(inv, sizeof inv, "tag", "ready-ok");
        if (peer_invoke_one(fd, inv, &id) != 0)
            return 1;
        rc = peer_read_line(fd, line, sizeof line, 5000);
        if (rc != 0 || peer_parse_stub_ready(line, &id2, rec_nonce) != 0
            || id2 != id) {
            fprintf(stderr, "PEER FAIL stub-ready %s\n", line);
            return 1;
        }
        if (peer_send_ack(fd, id, rec_nonce) != 0
            || peer_expect_line(fd, "DEALPG4 STARTED 1\n") != 0
            || peer_expect_line(fd, "DEALPG4 CLEAN 1 success\n") != 0) {
            fprintf(stderr, "PEER FAIL ready-ok-cycle\n");
            return 1;
        }
        close(fd);
        printf("PEER ready-ok\n");
        fflush(stdout);
        return 0;
    }

    if (strcmp(scenario, "delay-invoke") == 0) {
        /* outer-pre-invoke-fork (400 ms scripted): the INVOKED answer
         * is queued only after the nested fork, so the measured
         * INVOKE -> INVOKED round trip carries the delay. */
        uint64_t t0 = peer_now_ms();

        build_invoke(inv, sizeof inv, "tag", "ready-ok");
        if (peer_invoke_one(fd, inv, &id) != 0)
            return 1;
        if (peer_now_ms() - t0 < 350) {
            fprintf(stderr, "PEER FAIL delay-invoke too fast\n");
            return 1;
        }
        rc = peer_read_line(fd, line, sizeof line, 5000);
        if (rc != 0 || peer_parse_stub_ready(line, &id2, rec_nonce) != 0
            || id2 != id) {
            fprintf(stderr, "PEER FAIL delay-invoke-ready %s\n", line);
            return 1;
        }
        if (peer_send_ack(fd, id, rec_nonce) != 0
            || peer_expect_line(fd, "DEALPG4 STARTED 1\n") != 0
            || peer_expect_line(fd, "DEALPG4 CLEAN 1 success\n") != 0) {
            fprintf(stderr, "PEER FAIL delay-invoke-cycle\n");
            return 1;
        }
        close(fd);
        printf("PEER delay-invoke\n");
        fflush(stdout);
        return 0;
    }

    if (strcmp(scenario, "delay-ack") == 0) {
        /* outer-pre-ack-write (400 ms scripted): STARTED arrives only
         * after the delayed ACK relay write completes and the child
         * publishes it — the measured ACK -> STARTED gap carries the
         * delay. */
        uint64_t t0;

        build_invoke(inv, sizeof inv, "tag", "ready-ok");
        if (peer_invoke_one(fd, inv, &id) != 0)
            return 1;
        rc = peer_read_line(fd, line, sizeof line, 5000);
        if (rc != 0 || peer_parse_stub_ready(line, &id2, rec_nonce) != 0
            || id2 != id) {
            fprintf(stderr, "PEER FAIL delay-ack-ready %s\n", line);
            return 1;
        }
        t0 = peer_now_ms();
        if (peer_send_ack(fd, id, rec_nonce) != 0
            || peer_expect_line(fd, "DEALPG4 STARTED 1\n") != 0) {
            fprintf(stderr, "PEER FAIL delay-ack-start\n");
            return 1;
        }
        if (peer_now_ms() - t0 < 350) {
            fprintf(stderr, "PEER FAIL delay-ack too fast\n");
            return 1;
        }
        if (peer_expect_line(fd, "DEALPG4 CLEAN 1 success\n") != 0) {
            fprintf(stderr, "PEER FAIL delay-ack-clean\n");
            return 1;
        }
        close(fd);
        printf("PEER delay-ack\n");
        fflush(stdout);
        return 0;
    }

    if (strcmp(scenario, "delay-cancel") == 0) {
        /* outer-pre-cancel-write (400 ms scripted): the cancel-path
         * CLEAN cancelled arrives only after the delayed CANCEL
         * fan-out write completes and the child consumes it — the
         * measured CANCEL -> CLEAN gap carries the delay. */
        uint64_t t0;

        build_invoke(inv, sizeof inv, "tag", "cancel-read");
        if (peer_invoke_one(fd, inv, &id) != 0)
            return 1;
        rc = peer_read_line(fd, line, sizeof line, 5000);
        if (rc != 0 || peer_parse_stub_ready(line, &id2, rec_nonce) != 0
            || id2 != id) {
            fprintf(stderr, "PEER FAIL delay-cancel-ready %s\n", line);
            return 1;
        }
        t0 = peer_now_ms();
        if (peer_send_cancel(fd, id, rec_nonce) != 0
            || peer_expect_line(fd, "DEALPG4 CLEAN 1 cancelled\n") != 0) {
            fprintf(stderr, "PEER FAIL delay-cancel-clean\n");
            return 1;
        }
        if (peer_now_ms() - t0 < 350) {
            fprintf(stderr, "PEER FAIL delay-cancel too fast\n");
            return 1;
        }
        close(fd);
        printf("PEER delay-cancel\n");
        fflush(stdout);
        return 0;
    }

    if (strcmp(scenario, "death-hold") == 0) {
        /* FI_OUTER_DEATH: the record reaches RELEASED (the ACK write
         * completed) and the outer then dies at the end of the same
         * batch — the peer stays connected and alive after STARTED so
         * the coordinator layer of the PDEATHSIG cascade is
         * observable (the armed SIGKILL kills it when the outer's
         * death reparents it). */
        build_invoke(inv, sizeof inv, "tag", "death-cascade");
        if (peer_invoke_one(fd, inv, &id) != 0)
            return 1;
        rc = peer_read_line(fd, line, sizeof line, 5000);
        if (rc != 0 || peer_parse_stub_ready(line, &id2, rec_nonce) != 0
            || id2 != id) {
            fprintf(stderr, "PEER FAIL death-hold-ready %s\n", line);
            return 1;
        }
        if (peer_send_ack(fd, id, rec_nonce) != 0
            || peer_expect_line(fd, "DEALPG4 STARTED 1\n") != 0) {
            fprintf(stderr, "PEER FAIL death-hold-start\n");
            return 1;
        }
        for (;;)
            sleep_ms(1000);
    }

    if (strcmp(scenario, "verify-then-reject-ack") == 0) {
        /* STUB_READY forwarded; then a wrong-nonce ACK is rejected
         * record-level (broker open, record untouched); the nested
         * T1 expiry FAILED STARTUP_TIMEOUT relays verbatim. */
        build_invoke(inv, sizeof inv, "tag", "verify-ok-then-clean");
        if (peer_invoke_one(fd, inv, &id) != 0)
            return 1;
        rc = peer_read_line(fd, line, sizeof line, 5000);
        if (rc != 0 || peer_parse_stub_ready(line, &id2, rec_nonce) != 0
            || id2 != id) {
            fprintf(stderr, "PEER FAIL verify-ready %s\n", line);
            return 1;
        }
        if (peer_send_ack(fd, id, BAD_NONCE) != 0) {
            fprintf(stderr, "PEER FAIL bad-ack-send\n");
            return 1;
        }
        /* The record-level AUTH_FAILED rejection, then the nested T1
         * expiry relayed verbatim. */
        {
            char want[128];

            snprintf(want, sizeof want,
                     "DEALPG4 REJECT %lld tag AUTH_FAILED\n",
                     (long long)id);
            if (peer_expect_line(fd, want) != 0) {
                fprintf(stderr, "PEER FAIL auth-reject\n");
                return 1;
            }
        }
        {
            char want[128];

            snprintf(want, sizeof want,
                     "DEALPG4 FAILED %lld STARTUP_TIMEOUT\n",
                     (long long)id);
            if (peer_expect_line(fd, want) != 0) {
                fprintf(stderr, "PEER FAIL t1-relay\n");
                return 1;
            }
        }
        close(fd);
        printf("PEER verify-then-reject-ack\n");
        fflush(stdout);
        return 0;
    }

    if (strncmp(scenario, "verify-fail", 11) == 0) {
        /* A failed double re-verification: no STUB_READY is ever
         * forwarded (the peer observes quiet), and the nested T1
         * expiry FAILED STARTUP_TIMEOUT relays verbatim. */
        build_invoke(inv, sizeof inv, "tag", scenario);
        if (peer_invoke_one(fd, inv, &id) != 0)
            return 1;
        /* The failed re-verification is silent: no STUB_READY is
         * forwarded (the child's own T1 FAILED lands at ~500 ms,
         * after this quiet window). */
        if (peer_expect_quiet(fd, 300) != 0) {
            fprintf(stderr, "PEER FAIL verify-quiet %s\n", scenario);
            return 1;
        }
        {
            char want[128];

            snprintf(want, sizeof want,
                     "DEALPG4 FAILED %lld STARTUP_TIMEOUT\n",
                     (long long)id);
            if (peer_expect_line(fd, want) != 0) {
                fprintf(stderr, "PEER FAIL t1-relay %s\n", scenario);
                return 1;
            }
        }
        close(fd);
        printf("PEER %s\n", scenario);
        fflush(stdout);
        return 0;
    }

    if (strcmp(scenario, "discard") == 0) {
        /* The queued-write discard on terminality: the ACK is
         * queued-but-unwritten (the congestion seam); the child's
         * own CLEAN success relays verbatim as the single terminal
         * answer — no RELEASED, no fallback, no wedge. */
        build_invoke(inv, sizeof inv, "tag", "read-clean");
        if (peer_invoke_one(fd, inv, &id) != 0)
            return 1;
        rc = peer_read_line(fd, line, sizeof line, 5000);
        if (rc != 0 || peer_parse_stub_ready(line, &id2, rec_nonce) != 0
            || id2 != id) {
            fprintf(stderr, "PEER FAIL discard-ready %s\n", line);
            return 1;
        }
        if (peer_send_ack(fd, id, rec_nonce) != 0
            || peer_expect_line(fd, "DEALPG4 CLEAN 1 success\n") != 0) {
            fprintf(stderr, "PEER FAIL discard-clean\n");
            return 1;
        }
        close(fd);
        printf("PEER discard\n");
        fflush(stdout);
        return 0;
    }

    if (strcmp(scenario, "cancel-drop-ack") == 0) {
        /* A validated CANCEL drops the queued-but-unwritten ACK; the
         * child's own CLEAN cancelled then discards the queued CANCEL
         * write — the nested-origin terminal record is the single
         * terminal answer (no RELEASED, no fallback, no wedge). */
        build_invoke(inv, sizeof inv, "tag", "cancel-drop-ack");
        if (peer_invoke_one(fd, inv, &id) != 0)
            return 1;
        rc = peer_read_line(fd, line, sizeof line, 5000);
        if (rc != 0 || peer_parse_stub_ready(line, &id2, rec_nonce) != 0
            || id2 != id) {
            fprintf(stderr, "PEER FAIL cad-ready %s\n", line);
            return 1;
        }
        if (peer_send_ack(fd, id, rec_nonce) != 0
            || peer_send_cancel(fd, id, rec_nonce) != 0
            || peer_expect_line(fd, "DEALPG4 CLEAN 1 cancelled\n") != 0) {
            fprintf(stderr, "PEER FAIL cad-clean\n");
            return 1;
        }
        close(fd);
        printf("PEER cancel-drop-ack\n");
        fflush(stdout);
        return 0;
    }

    if (strcmp(scenario, "cancel-read") == 0) {
        /* The validated CANCEL relay (exact registry nonce — the
         * child verifies it byte-for-byte) and the cancel-path CLEAN
         * cancelled. */
        build_invoke(inv, sizeof inv, "tag", "cancel-read");
        if (peer_invoke_one(fd, inv, &id) != 0)
            return 1;
        rc = peer_read_line(fd, line, sizeof line, 5000);
        if (rc != 0 || peer_parse_stub_ready(line, &id2, rec_nonce) != 0
            || id2 != id) {
            fprintf(stderr, "PEER FAIL cr-ready %s\n", line);
            return 1;
        }
        if (peer_send_cancel(fd, id, rec_nonce) != 0
            || peer_expect_line(fd, "DEALPG4 CLEAN 1 cancelled\n") != 0) {
            fprintf(stderr, "PEER FAIL cr-clean\n");
            return 1;
        }
        close(fd);
        printf("PEER cancel-read\n");
        fflush(stdout);
        return 0;
    }

    if (strcmp(scenario, "cancel-cycle") == 0) {
        /* CANCELLING-from-RELEASED: STARTED relays before the CANCEL
         * lands; the post-release relays continue verbatim after it;
         * the cancel-path CLEAN cancelled terminates the record. */
        build_invoke(inv, sizeof inv, "tag", "cancel-cycle");
        if (peer_invoke_one(fd, inv, &id) != 0)
            return 1;
        rc = peer_read_line(fd, line, sizeof line, 5000);
        if (rc != 0 || peer_parse_stub_ready(line, &id2, rec_nonce) != 0
            || id2 != id) {
            fprintf(stderr, "PEER FAIL cc-ready %s\n", line);
            return 1;
        }
        if (peer_send_ack(fd, id, rec_nonce) != 0
            || peer_expect_line(fd, "DEALPG4 STARTED 1\n") != 0
            || peer_send_cancel(fd, id, rec_nonce) != 0
            || peer_expect_line(fd, "DEALPG4 OUT 1 out aabb\n") != 0
            || peer_expect_line(fd, "DEALPG4 OUT_END 1 out\n") != 0
            || peer_expect_line(fd,
                                "DEALPG4 REPORT 0 0 12 5 3 2 1 1 1 1 0"
                                " 2 0 0 0 1 1 1 -\n") != 0
            || peer_expect_line(fd, "DEALPG4 CLEAN 1 cancelled\n") != 0) {
            fprintf(stderr, "PEER FAIL cc-cycle\n");
            return 1;
        }
        close(fd);
        printf("PEER cancel-cycle\n");
        fflush(stdout);
        return 0;
    }

    if (strcmp(scenario, "cancel-from-forking") == 0) {
        /* The CANCEL lands while the record is FORKING; the late
         * STUB_FORKED is accepted outer-internal (retained, never
         * forwarded); the cancel-path CLEAN cancelled terminates. */
        build_invoke(inv, sizeof inv, "tag", "cancel-from-forking");
        if (peer_invoke_one(fd, inv, &id) != 0)
            return 1;
        /* The record's nonce is published by the composition
         * (this child never publishes a STUB_READY). */
        {
            FILE *f = fopen("build/.chan-rec-nonce", "r");
            char n[DEALPG4_NONCE_HEX_CHARS + 1] = "";

            if (f != NULL) {
                if (fscanf(f, "%32s", n) != 1)
                    n[0] = '\0';
                fclose(f);
            }
            snprintf(rec_nonce, sizeof rec_nonce, "%s", n);
        }
        if (rec_nonce[0] == '\0'
            || peer_send_cancel(fd, id, rec_nonce) != 0
            || peer_expect_line(fd, "DEALPG4 CLEAN 1 cancelled\n") != 0) {
            fprintf(stderr, "PEER FAIL cff-clean\n");
            return 1;
        }
        close(fd);
        printf("PEER cancel-from-forking\n");
        fflush(stdout);
        return 0;
    }

    if (strcmp(scenario, "stub-ready-cancelling") == 0) {
        /* STUB_READY after a pre-release CANCEL: outer-internal only
         * — never forwarded, never re-verified, no state change. */
        build_invoke(inv, sizeof inv, "tag", "stub-ready-cancelling");
        if (peer_invoke_one(fd, inv, &id) != 0)
            return 1;
        /* The record nonce is published by the composition (the peer
         * cannot read it from a STUB_READY that must never arrive). */
        {
            FILE *f = fopen("build/.chan-rec-nonce", "r");
            char n[DEALPG4_NONCE_HEX_CHARS + 1] = "";

            if (f != NULL) {
                if (fscanf(f, "%32s", n) != 1)
                    n[0] = '\0';
                fclose(f);
            }
            snprintf(rec_nonce, sizeof rec_nonce, "%s", n);
        }
        /* Let the child's STUB_FORKED land first (the cancel then
         * applies to STUB_BLOCKED — the pre-release cancel of the D4
         * table). The next line must be exactly the CLEAN: a
         * forwarded STUB_READY would arrive first and fail the exact
         * match (STUB_READY during CANCELLING is outer-internal
         * only). */
        sleep_ms(300);
        if (rec_nonce[0] == '\0'
            || peer_send_cancel(fd, id, rec_nonce) != 0
            || peer_expect_line(fd, "DEALPG4 CLEAN 1 cancelled\n") != 0) {
            fprintf(stderr, "PEER FAIL src-cycle\n");
            return 1;
        }
        close(fd);
        printf("PEER stub-ready-cancelling\n");
        fflush(stdout);
        return 0;
    }

    if (strcmp(scenario, "cancel-then-die") == 0) {
        /* The record's nonce is published by the composition (this
         * child never publishes a STUB_READY); the STUB_FORKED lands
         * first, so the CANCEL applies to STUB_BLOCKED (the
         * pre-cancel state). The peer then closes: the child dies
         * without a terminal record and the outer initiates the
         * pre-cancel state's death fallback. */
        build_invoke(inv, sizeof inv, "tag", "cancel-then-die");
        if (peer_invoke_one(fd, inv, &id) != 0) {
            fprintf(stderr, "PEER FAIL ctd-invoke\n");
            return 1;
        }
        sleep_ms(300);
        {
            FILE *f = fopen("build/.chan-rec-nonce", "r");
            char n[DEALPG4_NONCE_HEX_CHARS + 1] = "";

            if (f != NULL) {
                if (fscanf(f, "%32s", n) != 1)
                    n[0] = '\0';
                fclose(f);
            }
            snprintf(rec_nonce, sizeof rec_nonce, "%s", n);
        }
        if (rec_nonce[0] == '\0'
            || peer_send_cancel(fd, id, rec_nonce) != 0) {
            fprintf(stderr, "PEER FAIL ctd-cancel\n");
            return 1;
        }
        close(fd);
        printf("PEER cancel-then-die\n");
        fflush(stdout);
        return 0;
    }

    if (strcmp(scenario, "reject-nested") == 0) {
        /* The nested REJECT is consumed, never forwarded; the CLEAN
         * cancelled relays verbatim. */
        build_invoke(inv, sizeof inv, "tag", "reject-nested");
        if (peer_invoke_one(fd, inv, &id) != 0)
            return 1;
        /* The next line must be exactly the CLEAN: a forwarded nested
         * REJECT would arrive first and fail the exact match (the
         * relay rules never forward a nested REJECT). */
        if (peer_expect_line(fd, "DEALPG4 CLEAN 1 cancelled\n") != 0) {
            fprintf(stderr, "PEER FAIL reject-nested\n");
            return 1;
        }
        close(fd);
        printf("PEER reject-nested\n");
        fflush(stdout);
        return 0;
    }

    if (strcmp(scenario, "relay-verbatim") == 0) {
        /* The RELEASED relay battery arrives unchanged. */
        build_invoke(inv, sizeof inv, "tag", "relay-verbatim");
        if (peer_invoke_one(fd, inv, &id) != 0)
            return 1;
        rc = peer_read_line(fd, line, sizeof line, 5000);
        if (rc != 0 || peer_parse_stub_ready(line, &id2, rec_nonce) != 0
            || id2 != id) {
            fprintf(stderr, "PEER FAIL rv-ready %s\n", line);
            return 1;
        }
        if (peer_send_ack(fd, id, rec_nonce) != 0
            || peer_expect_line(fd, "DEALPG4 STARTED 1\n") != 0
            || peer_expect_line(fd, "DEALPG4 EXEC_FAILED 1 2\n") != 0
            || peer_expect_line(fd, "DEALPG4 OUT 1 out deadbeef\n") != 0
            || peer_expect_line(fd, "DEALPG4 OUT_END 1 out\n") != 0
            || peer_expect_line(fd,
                                "DEALPG4 REPORT 1 0 20 6 4 2 1 1 1 1 0"
                                " 4 0 0 0 1 1 1 -\n") != 0
            || peer_expect_line(fd, "DEALPG4 CLEAN 1 success\n") != 0) {
            fprintf(stderr, "PEER FAIL rv-cycle\n");
            return 1;
        }
        close(fd);
        printf("PEER relay-verbatim\n");
        fflush(stdout);
        return 0;
    }

    if (strcmp(scenario, "flood") == 0) {
        /* The 1 MiB per-stream relay cap: the peer reads slowly, the
         * queue accumulates past the cap and drops the overflow with
         * the truncation consequence (asserted from the result
         * view). Each OUT line is exactly
         *   18 + <id digits> + 65536 + 1 bytes (the LF) = 65556
         * bytes with id 1; the peer must observe fewer than the 70
         * sent chunks. */
        int chunks = 0;
        int done = 0;

        build_invoke(inv, sizeof inv, "tag", "flood");
        if (peer_invoke_one(fd, inv, &id) != 0)
            return 1;
        rc = peer_read_line(fd, line, sizeof line, 5000);
        if (rc != 0 || peer_parse_stub_ready(line, &id2, rec_nonce) != 0
            || id2 != id) {
            fprintf(stderr, "PEER FAIL flood-ready %s\n", line);
            return 1;
        }
        if (peer_send_ack(fd, id, rec_nonce) != 0) {
            fprintf(stderr, "PEER FAIL flood-ack\n");
            return 1;
        }
        sleep_ms(1500); /* the child floods while the peer is quiet */
        for (;;) {
            rc = peer_read_line(fd, line, sizeof line, 10000);
            if (rc != 0) {
                fprintf(stderr, "PEER FAIL flood-read rc=%d\n", rc);
                return 1;
            }
            if (strncmp(line, "DEALPG4 OUT ", 12) == 0) {
                chunks++;
                continue;
            }
            if (strcmp(line, "DEALPG4 OUT_END 1 out\n") == 0) {
                done = 1;
                break;
            }
            fprintf(stderr, "PEER FAIL flood-line %s", line);
            return 1;
        }
        /* The 1 MiB queue cap plus the socket buffer absorbed the
         * first chunks; the exact delivered count is socket-buffer
         * dependent — the deterministic facts are the bound (well
         * under the 70 sent) and the queue's overflow consequence
         * (asserted from the result view). */
        if (!done || chunks < 1 || chunks >= 70) {
            fprintf(stderr, "PEER FAIL flood-count %d\n", chunks);
            return 1;
        }
        if (peer_expect_line(fd, "DEALPG4 CLEAN 1 success\n") != 0) {
            fprintf(stderr, "PEER FAIL flood-clean\n");
            return 1;
        }
        close(fd);
        printf("PEER flood chunks=%d\n", chunks);
        fflush(stdout);
        return 0;
    }

    if (strcmp(scenario, "defects") == 0) {
        /* Three records, three defect classes; the peer only ever
         * receives the INVOKEDs (no record is relayed). */
        build_invoke(inv, sizeof inv, "tag", "defect-state");
        if (peer_invoke_one(fd, inv, &id) != 0)
            return 1;
        build_invoke(inv, sizeof inv, "tag", "defect-cr");
        if (peer_invoke_one(fd, inv, &id2) != 0)
            return 1;
        build_invoke(inv, sizeof inv, "tag", "defect-oversize");
        if (peer_invoke_one(fd, inv, &id2) != 0)
            return 1;
        /* Each defect classifies in the state its record held when it
         * arrived (the caller-loss mark must not race it). */
        sleep_ms(800);
        close(fd);
        printf("PEER defects\n");
        fflush(stdout);
        return 0;
    }

    if (strcmp(scenario, "death") == 0) {
        /* The verified STUB_READY forward; then nothing more may ever
         * arrive (the holder's late records are consumed-not-
         * relayed). */
        build_invoke(inv, sizeof inv, "tag", "death");
        if (peer_invoke_one(fd, inv, &id) != 0)
            return 1;
        rc = peer_read_line(fd, line, sizeof line, 5000);
        if (rc != 0 || peer_parse_stub_ready(line, &id2, rec_nonce) != 0
            || id2 != id) {
            fprintf(stderr, "PEER FAIL death-ready %s\n", line);
            return 1;
        }
        if (peer_expect_quiet(fd, 2500) != 0) {
            fprintf(stderr, "PEER FAIL death-quiet\n");
            return 1;
        }
        close(fd);
        printf("PEER death\n");
        fflush(stdout);
        return 0;
    }

    if (strcmp(scenario, "synth") == 0) {
        /* The synthesized terminal records, each exactly once, ahead
         * of DONE; every nested-origin record consumed-not-relayed
         * (no STUB_FORKED/STUB_READY/REPORT/nested CLEAN anywhere). */
        /* The synthesized terminal answer of each record is queued
         * during the fork (before the INVOKED answer), so it precedes
         * INVOKED in the broker byte stream. */
        build_invoke(inv, sizeof inv, "tag", "synth-child");
        if (peer_write_all(fd, inv, strlen(inv)) != 0
            || peer_expect_line(fd, "DEALPG4 CLEAN 1 cancelled\n") != 0
            || peer_expect_line(fd, "DEALPG4 INVOKED 1 tag\n") != 0) {
            fprintf(stderr, "PEER FAIL synth-1\n");
            return 1;
        }
        if (peer_write_all(fd, inv, strlen(inv)) != 0
            || peer_expect_line(fd, "DEALPG4 FAILED 2 GROUP_SURVIVOR\n")
                   != 0
            || peer_expect_line(fd, "DEALPG4 INVOKED 2 tag\n") != 0) {
            fprintf(stderr, "PEER FAIL synth-2\n");
            return 1;
        }
        /* DONE failed at the scaled cutoff (the verdict computed at
         * the queueing moment), then BYE, then close. */
        if (peer_expect_line(fd, "DEALPG4 DONE failed\n") != 0) {
            fprintf(stderr, "PEER FAIL synth-done\n");
            return 1;
        }
        if (peer_write_all(fd, "DEALPG4 BYE\n", 12) != 0) {
            fprintf(stderr, "PEER FAIL synth-bye\n");
            return 1;
        }
        close(fd);
        printf("PEER synth\n");
        fflush(stdout);
        return 0;
    }

    if (strcmp(scenario, "integration") == 0) {
        /* The full T1-T5 record: INVOKE over the real broker, the
         * real serve core's STUB_FORKED + verified STUB_READY, the
         * ACK relay, STARTED/OUT/OUT_END/OUT_END/REPORT/CLEAN in
         * order, DONE clean at the cutoff with the CLEAN before it,
         * BYE, close, exit 0. */
        build_invoke_sh(inv, sizeof inv, "tag");
        if (peer_invoke_one(fd, inv, &id) != 0) {
            fprintf(stderr, "PEER FAIL int-invoke\n");
            return 1;
        }
        rc = peer_read_line(fd, line, sizeof line, 10000);
        if (rc != 0 || peer_parse_stub_ready(line, &id2, rec_nonce) != 0
            || id2 != id) {
            fprintf(stderr, "PEER FAIL int-ready %s\n", line);
            return 1;
        }
        if (peer_send_ack(fd, id, rec_nonce) != 0) {
            fprintf(stderr, "PEER FAIL int-ack\n");
            return 1;
        }
        /* STARTED, then the stdout relay (OUT chunk + OUT_END out +
         * OUT_END err), REPORT, CLEAN — the exact catalog order of
         * the real supervisor's finalization. */
        if (peer_expect_line(fd, "DEALPG4 STARTED 1\n") != 0
            || peer_expect_line(fd, "DEALPG4 OUT 1 out 68656c6c6f0a\n")
                   != 0
            || peer_expect_line(fd, "DEALPG4 OUT_END 1 out\n") != 0
            || peer_expect_line(fd, "DEALPG4 OUT_END 1 err\n") != 0) {
            fprintf(stderr, "PEER FAIL int-relays\n");
            return 1;
        }
        /* The REPORT line precedes the terminal record in the catalog
         * order (the supervisor's own 19 fields; bounded shape check:
         * the prefix). */
        rc = peer_read_line(fd, line, sizeof line, 10000);
        if (rc != 0 || strncmp(line, "DEALPG4 REPORT ", 15) != 0
            || line[strlen(line) - 1] != '\n') {
            fprintf(stderr, "PEER FAIL int-report %s\n", line);
            return 1;
        }
        if (peer_expect_line(fd, "DEALPG4 CLEAN 1 success\n") != 0) {
            fprintf(stderr, "PEER FAIL int-clean\n");
            return 1;
        }
        /* DONE clean at the scaled cutoff (the CLEAN preceded it). */
        if (peer_expect_line(fd, "DEALPG4 DONE clean\n") != 0) {
            fprintf(stderr, "PEER FAIL int-done\n");
            return 1;
        }
        if (peer_write_all(fd, "DEALPG4 BYE\n", 12) != 0) {
            fprintf(stderr, "PEER FAIL int-bye\n");
            return 1;
        }
        close(fd);
        printf("PEER integration\n");
        fflush(stdout);
        return 0;
    }

    fprintf(stderr, "PEER FAIL unknown-scenario %s\n", scenario);
    return 1;
}

/* The scripted-peer re-exec entry. */
static int chan_peer_entry(int argc, char **argv)
{
    signal(SIGPIPE, SIG_IGN);
    if (argc < 3) {
        fprintf(stderr, "PEER FAIL missing-scenario\n");
        return 1;
    }
    return chan_peer_main(argv[2]);
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

static void peer_argv(char *argv[8], const char *scenario)
{
    size_t n = 0;

    argv[n++] = (char *)g_suite_argv0;
    argv[n++] = (char *)"--channel-peer";
    argv[n++] = (char *)scenario;
    argv[n] = NULL;
}

/* Common per-run assertions: the peer exited clean (its own
 * assertions held), no peer failure line in the stderr drain, the
 * broker surface closed and unlinked, no children. */
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
            if (derr != NULL && derr->retained_len > 0)
                fprintf(stderr, "\nDIAG peer-stderr[%.*s]\n",
                        (int)derr->retained_len,
                        (const char *)derr->retained);
            {
                size_t i;

                for (i = 0; i < view->ntokens; i++)
                    fprintf(stderr, " %s", view->tokens[i]);
            }
            fprintf(stderr, "\n");
        }
    }
    CHECK(view->broker_closed == 1);
    CHECK(view->broker_socket_unlinked == 1);
}

/* Run one scenario against the scripted composition. */
static void run_chan_case(const OuterLimits *limits,
                          const char *peer_scenario, int spawn_mode,
                          char *report, size_t report_cap, int *status,
                          dealpg4_outer_result *view)
{
    chan_spawn sp;
    dealpg4_outer_spawn spawn = make_chan_spawn(&sp, spawn_mode);
    char *argv[8];
    const dealpg4_drain_ctx *dout = NULL;
    const dealpg4_drain_ctx *derr = NULL;

    peer_argv(argv, peer_scenario);
    *status = core_with_spawn(limits, argv, &spawn, report, report_cap);
    memset(view, 0, sizeof *view);
    dealpg4_outer_last_result(view);
    dealpg4_outer_drain_state(&dout, &derr);
    assert_common(view, derr);
    (void)dout;
}

/* The fault-injection catalog (the nested-channel congestion site and
 * the completed D6 delay/fail tags of this file's children). */
static const char *const chan_delay_sites[] = {
    DEALPG4_FI_DELAY_OUTER_PRE_COORD_FORK,
    DEALPG4_FI_DELAY_COORD_POST_FORK,
    DEALPG4_FI_DELAY_COORD_PRE_READY_WRITE,
    DEALPG4_FI_DELAY_OUTER_PRE_INVOKE_FORK,
    DEALPG4_FI_DELAY_OUTER_PRE_ACK_WRITE,
    DEALPG4_FI_DELAY_OUTER_PRE_CANCEL_WRITE
};
static const int chan_fail_sites[] = {
    FI_OUTER_SUBREAPER, FI_OUTER_TIMERFD, FI_OUTER_SIGNALFD,
    FI_OUTER_NONCE, FI_OUTER_PIPE, FI_COORD_READY_MISMATCH,
    FI_OUTER_BIND, FI_OUTER_SOCKETPAIR, FI_OUTER_FORK,
    FI_OUTER_ENTRY_NONCE, FI_OUTER_DEATH
};
static const int chan_congest_targets[] = {
    FI_CONGEST_BROKER, FI_CONGEST_NESTED_CTRL
};
static const int chan_congest_modes[] = {
    BROKER_WRITE_STALL, NESTED_WRITE_STALL
};
static const dealpg4_fi_catalog chan_catalog = {
    chan_delay_sites, 6, chan_fail_sites, 11, chan_congest_targets, 2,
    chan_congest_modes, 2
};

/* The always-on nested-channel congestion: the queued ACK/CANCEL write
 * can never complete (the conforming intermediate paths and the
 * queued-write discard cases). */
static void install_nested_congest(void)
{
    dealpg4_fi_script_congest congest[1];
    dealpg4_fi_script script;

    congest[0].target = FI_CONGEST_NESTED_CTRL;
    congest[0].mode = NESTED_WRITE_STALL;
    congest[0].oneshot = 0;
    script.delays = NULL;
    script.ndelays = 0;
    script.fails = NULL;
    script.nfails = 0;
    script.congests = congest;
    script.ncongests = 1;
    CHECK(dealpg4_fi_install_overrides(&script, &chan_catalog) == 0);
}

/* The always-on delay at a named delay site (the D6 delay-seam
 * determinism cases: an injected delay sleeps exactly the scripted ms
 * before the named step and consumes the enclosing deadline). */
static void install_delay(const char *site, unsigned ms)
{
    dealpg4_fi_script_delay delays[1];
    dealpg4_fi_script script;

    delays[0].site = site;
    delays[0].duration_ms = ms;
    delays[0].oneshot = 0;
    script.delays = delays;
    script.ndelays = 1;
    script.fails = NULL;
    script.nfails = 0;
    script.congests = NULL;
    script.ncongests = 0;
    CHECK(dealpg4_fi_install_overrides(&script, &chan_catalog) == 0);
}

/* The always-on FI_OUTER_DEATH fail override (scripted exit code). */
static void install_outer_death(int value)
{
    dealpg4_fi_script_fail fails[1];
    dealpg4_fi_script script;

    fails[0].site = FI_OUTER_DEATH;
    fails[0].value = value;
    fails[0].oneshot = 0;
    script.delays = NULL;
    script.ndelays = 0;
    script.fails = fails;
    script.nfails = 1;
    script.congests = NULL;
    script.ncongests = 0;
    CHECK(dealpg4_fi_install_overrides(&script, &chan_catalog) == 0);
}

/* === Case groups ======================================================= */

/* Group 1: STUB_FORKED outer-internal handling + the no-STARTED
 * schedule's CLEAN relay. */
static int case_forked_fn(void)
{
    char report[2048];
    dealpg4_outer_result view;
    dealpg4_outer_record_view rv;
    int status;

    run_chan_case(&LIVE_LIMITS, "forked", CHAN_SPAWN_SCRIPT, report,
                  sizeof report, &status, &view);
    CHECK(status == 0); /* CLEAN success + clean exit */
    CHECK(view.records_total == 1);
    CHECK(view.records_clean == 1);
    CHECK(view.records_live == 0);
    CHECK(view.proof_passed == 1);
    CHECK(view.nested_terminal_relays == 1);
    CHECK(view.stub_ready_forwarded == 0);
    CHECK(view.ack_write_completions == 0);
    CHECK(dealpg4_outer_registry_record(0, &rv) == 0);
    CHECK(rv.state == DEALPG4_OUTER_REC_CLEAN);
    CHECK(rv.clean_final == 1);
    CHECK(rv.stub_pid > 0); /* STUB_FORKED retained the stubPid */
    CHECK(rv.channel_open == 0); /* drained to EOF and closed */
    CHECK(rv.channel_drain_only == 1); /* the post-terminal drop rule */
    CHECK(rv.fallback_initiated == 0); /* no fallback on a terminal
                                          record */
    /* The transition path: FORKING -> STUB_BLOCKED -> CLEAN (no
     * TARGET_PUBLISHED, no RELEASED). */
    CHECK(rv.history_count == 3);
    CHECK(rv.history[0].state == DEALPG4_OUTER_REC_FORKING);
    CHECK(rv.history[1].state == DEALPG4_OUTER_REC_STUB_BLOCKED);
    CHECK(rv.history[2].state == DEALPG4_OUTER_REC_CLEAN);
    return 0;
}

/* Group 2: the ACK relay + RELEASED-at-write-completion. */
static int case_ready_ok_fn(void)
{
    char report[2048];
    dealpg4_outer_result view;
    dealpg4_outer_record_view rv;
    int status;

    run_chan_case(&LIVE_LIMITS, "ready-ok", CHAN_SPAWN_SCRIPT, report,
                  sizeof report, &status, &view);
    CHECK(status == 0);
    CHECK(view.stub_ready_forwarded == 1);
    CHECK(view.stub_verify_failures == 0);
    CHECK(view.ack_write_completions == 1);
    CHECK(view.queued_write_discards == 0);
    CHECK(view.nested_terminal_relays == 1);
    CHECK(view.proof_passed == 1);
    CHECK(dealpg4_outer_registry_record(0, &rv) == 0);
    CHECK(rv.state == DEALPG4_OUTER_REC_CLEAN);
    CHECK(rv.clean_final == 1);
    CHECK(rv.ack_applied == 1); /* an applied ACK survives */
    CHECK(rv.target_pgid == rv.stub_pid);
    CHECK(rv.target_session_id == rv.stub_pid);
    /* FORKING -> STUB_BLOCKED -> TARGET_PUBLISHED -> RELEASED ->
     * CLEAN: RELEASED entered exactly at the ACK-write completion. */
    CHECK(rv.history_count == 5);
    CHECK(rv.history[0].state == DEALPG4_OUTER_REC_FORKING);
    CHECK(rv.history[1].state == DEALPG4_OUTER_REC_STUB_BLOCKED);
    CHECK(rv.history[2].state == DEALPG4_OUTER_REC_TARGET_PUBLISHED);
    CHECK(rv.history[3].state == DEALPG4_OUTER_REC_RELEASED);
    CHECK(rv.history[4].state == DEALPG4_OUTER_REC_CLEAN);
    return 0;
}

/* Group 3: STUB_READY double-verification failures (one capture child
 * per variant — the core's entry preamble owns setsid, so every core
 * call runs in a fresh process). */
static int case_verify_fail_one_fn(const char *variant)
{
    char report[2048];
    dealpg4_outer_result view;
    dealpg4_outer_record_view rv;
    int status;

    run_chan_case(&LIVE_LIMITS, variant, CHAN_SPAWN_SCRIPT, report,
                  sizeof report, &status, &view);
    CHECK(status == DEALPG4_OUTER_EXIT_GATE_FAILURE);
    CHECK(view.stub_ready_forwarded == 0);
    CHECK(view.stub_verify_failures == 1);
    CHECK(view.nested_terminal_relays == 1); /* the nested T1
                                                FAILED
                                                STARTUP_TIMEOUT */
    CHECK(view.proof_passed == 1);
    CHECK(dealpg4_outer_registry_record(0, &rv) == 0);
    CHECK(rv.state == DEALPG4_OUTER_REC_FAILED);
    CHECK(strcmp(rv.failure_token, "STARTUP_TIMEOUT") == 0);
    /* The record stayed STUB_BLOCKED through the failed
     * re-verification (never TARGET_PUBLISHED). */
    CHECK(rv.history_count == 3);
    CHECK(rv.history[0].state == DEALPG4_OUTER_REC_FORKING);
    CHECK(rv.history[1].state == DEALPG4_OUTER_REC_STUB_BLOCKED);
    CHECK(rv.history[2].state == DEALPG4_OUTER_REC_FAILED);
    CHECK(rv.ack_applied == 0);
    CHECK(rv.channel_open == 0);
    CHECK(rv.fallback_initiated == 0);
    return 0;
}

static int case_verify_fail_wrong_pgid_fn(void)
{
    return case_verify_fail_one_fn("verify-fail-wrong-pgid");
}

static int case_verify_fail_wrong_sid_fn(void)
{
    return case_verify_fail_one_fn("verify-fail-wrong-sid");
}

static int case_verify_fail_wrong_nonce_fn(void)
{
    return case_verify_fail_one_fn("verify-fail-wrong-nonce");
}

static int case_verify_fail_dead_pid_fn(void)
{
    return case_verify_fail_one_fn("verify-fail-dead-pid");
}

static int case_verify_fail_self_pid_fn(void)
{
    return case_verify_fail_one_fn("verify-fail-self-pid");
}

static int case_verify_fail_wrong_id_fn(void)
{
    return case_verify_fail_one_fn("verify-fail-wrong-id");
}

/* The record-level AUTH_FAILED split: the wrong-nonce ACK names a
 * TARGET_PUBLISHED record — REJECT, broker open, record untouched,
 * no relay; the nested T1 expiry then terminates the record. */
static int case_ack_reject_fn(void)
{
    char report[2048];
    dealpg4_outer_result view;
    dealpg4_outer_record_view rv;
    int status;

    run_chan_case(&LIVE_LIMITS, "verify-then-reject-ack",
                  CHAN_SPAWN_SCRIPT, report, sizeof report, &status,
                  &view);
    CHECK(status == DEALPG4_OUTER_EXIT_GATE_FAILURE);
    CHECK(view.stub_ready_forwarded == 1); /* the forward itself held */
    CHECK(view.ack_write_completions == 0); /* nothing was relayed */
    CHECK(view.nested_terminal_relays == 1);
    CHECK(dealpg4_outer_registry_record(0, &rv) == 0);
    CHECK(rv.state == DEALPG4_OUTER_REC_FAILED);
    CHECK(strcmp(rv.failure_token, "STARTUP_TIMEOUT") == 0);
    CHECK(rv.ack_applied == 0); /* the record was untouched */
    return 0;
}

/* Group 4: the queued-write discard on terminality (the conforming
 * intermediate path — the congestion seam keeps the ACK queued). */
static int case_discard_fn(void)
{
    char report[2048];
    dealpg4_outer_result view;
    dealpg4_outer_record_view rv;
    int status;

    install_nested_congest();
    run_chan_case(&LIVE_LIMITS, "discard", CHAN_SPAWN_SCRIPT, report,
                  sizeof report, &status, &view);
    dealpg4_fi_restore_defaults();
    CHECK(status == 0); /* CLEAN success + clean exit */
    CHECK(view.stub_ready_forwarded == 1);
    CHECK(view.ack_write_completions == 0); /* the ACK write never
                                               completed */
    CHECK(view.queued_write_discards == 1); /* the queued ACK was
                                               discarded without
                                               completing */
    CHECK(view.nested_terminal_relays == 1);
    CHECK(view.proof_passed == 1);
    CHECK(dealpg4_outer_registry_record(0, &rv) == 0);
    CHECK(rv.state == DEALPG4_OUTER_REC_CLEAN);
    CHECK(rv.clean_final == 1);
    CHECK(rv.ack_applied == 0); /* the terminality discard cleared it */
    CHECK(rv.queued_ack == 0);
    /* No RELEASED was ever entered on the terminal record. */
    CHECK(rv.history_count == 4);
    CHECK(rv.history[0].state == DEALPG4_OUTER_REC_FORKING);
    CHECK(rv.history[1].state == DEALPG4_OUTER_REC_STUB_BLOCKED);
    CHECK(rv.history[2].state == DEALPG4_OUTER_REC_TARGET_PUBLISHED);
    CHECK(rv.history[3].state == DEALPG4_OUTER_REC_CLEAN);
    return 0;
}

/* Group 5a: the validated-CANCEL relay with the exact registry
 * nonce. */
static int case_cancel_read_fn(void)
{
    char report[2048];
    dealpg4_outer_result view;
    dealpg4_outer_record_view rv;
    int status;

    run_chan_case(&LIVE_LIMITS, "cancel-read", CHAN_SPAWN_SCRIPT,
                  report, sizeof report, &status, &view);
    CHECK(status == 0); /* CLEAN cancelled + clean exit */
    CHECK(view.stub_ready_forwarded == 1);
    CHECK(view.nested_terminal_relays == 1);
    CHECK(view.proof_passed == 1);
    CHECK(dealpg4_outer_registry_record(0, &rv) == 0);
    CHECK(rv.state == DEALPG4_OUTER_REC_CLEAN);
    CHECK(rv.clean_final == 0);
    /* The peer CANCELs only after reading the forwarded STUB_READY,
     * so the cancel lands on TARGET_PUBLISHED: the pre-cancel state
     * recovered from the ordered history is TARGET_PUBLISHED. */
    CHECK(rv.pre_cancel_state == DEALPG4_OUTER_REC_TARGET_PUBLISHED);
    CHECK(rv.history_count == 5);
    CHECK(rv.history[0].state == DEALPG4_OUTER_REC_FORKING);
    CHECK(rv.history[1].state == DEALPG4_OUTER_REC_STUB_BLOCKED);
    CHECK(rv.history[2].state == DEALPG4_OUTER_REC_TARGET_PUBLISHED);
    CHECK(rv.history[3].state == DEALPG4_OUTER_REC_CANCELLING);
    CHECK(rv.history[4].state == DEALPG4_OUTER_REC_CLEAN);
    CHECK(rv.fallback_initiated == 0);
    return 0;
}

/* Group 5b: a validated CANCEL drops the queued-but-unwritten ACK; the
 * child's own CLEAN cancelled then discards the queued CANCEL write
 * (two discards, no RELEASED, no fallback, no wedge). */
static int case_cancel_drop_ack_fn(void)
{
    char report[2048];
    dealpg4_outer_result view;
    dealpg4_outer_record_view rv;
    int status;

    install_nested_congest();
    run_chan_case(&LIVE_LIMITS, "cancel-drop-ack", CHAN_SPAWN_SCRIPT,
                  report, sizeof report, &status, &view);
    dealpg4_fi_restore_defaults();
    CHECK(status == 0);
    CHECK(view.ack_write_completions == 0);
    CHECK(view.queued_write_discards == 2);
    CHECK(view.nested_terminal_relays == 1);
    CHECK(view.proof_passed == 1);
    CHECK(dealpg4_outer_registry_record(0, &rv) == 0);
    CHECK(rv.state == DEALPG4_OUTER_REC_CLEAN);
    CHECK(rv.clean_final == 0);
    CHECK(rv.ack_applied == 0);
    CHECK(rv.history_count == 5);
    CHECK(rv.history[1].state == DEALPG4_OUTER_REC_STUB_BLOCKED);
    CHECK(rv.history[2].state == DEALPG4_OUTER_REC_TARGET_PUBLISHED);
    CHECK(rv.history[3].state == DEALPG4_OUTER_REC_CANCELLING);
    CHECK(rv.history[4].state == DEALPG4_OUTER_REC_CLEAN);
    CHECK(rv.pre_cancel_state == DEALPG4_OUTER_REC_TARGET_PUBLISHED);
    return 0;
}

/* Group 6: the CANCELLING expectation set (one capture child per
 * case). */

/* (a) CANCELLING-from-RELEASED: the post-release relays continue
 * verbatim. */
static int case_cancel_cycle_fn(void)
{
    char report[2048];
    dealpg4_outer_result view;
    dealpg4_outer_record_view rv;
    int status;

    run_chan_case(&LIVE_LIMITS, "cancel-cycle", CHAN_SPAWN_SCRIPT,
                  report, sizeof report, &status, &view);
    CHECK(status == 0);
    CHECK(view.ack_write_completions == 1);
    CHECK(view.nested_terminal_relays == 1);
    CHECK(dealpg4_outer_registry_record(0, &rv) == 0);
    CHECK(rv.state == DEALPG4_OUTER_REC_CLEAN);
    CHECK(rv.clean_final == 0);
    CHECK(rv.pre_cancel_state == DEALPG4_OUTER_REC_RELEASED);
    CHECK(rv.history_count == 6);
    CHECK(rv.history[0].state == DEALPG4_OUTER_REC_FORKING);
    CHECK(rv.history[1].state == DEALPG4_OUTER_REC_STUB_BLOCKED);
    CHECK(rv.history[2].state == DEALPG4_OUTER_REC_TARGET_PUBLISHED);
    CHECK(rv.history[3].state == DEALPG4_OUTER_REC_RELEASED);
    CHECK(rv.history[4].state == DEALPG4_OUTER_REC_CANCELLING);
    CHECK(rv.history[5].state == DEALPG4_OUTER_REC_CLEAN);
    return 0;
}

/* (b) CANCELLING-from-FORKING: the late STUB_FORKED is accepted
 * outer-internal (stubPid retained, never forwarded, no state
 * change). */
static int case_cancel_from_forking_fn(void)
{
    char report[2048];
    dealpg4_outer_result view;
    dealpg4_outer_record_view rv;
    int status;

    run_chan_case(&LIVE_LIMITS, "cancel-from-forking",
                  CHAN_SPAWN_SCRIPT, report, sizeof report, &status,
                  &view);
    CHECK(status == 0);
    CHECK(view.stub_ready_forwarded == 0);
    CHECK(dealpg4_outer_registry_record(0, &rv) == 0);
    CHECK(rv.state == DEALPG4_OUTER_REC_CLEAN);
    CHECK(rv.clean_final == 0);
    CHECK(rv.stub_pid > 0); /* retained for the fallback */
    CHECK(rv.pre_cancel_state == DEALPG4_OUTER_REC_FORKING);
    CHECK(rv.history_count == 3);
    CHECK(rv.history[0].state == DEALPG4_OUTER_REC_FORKING);
    CHECK(rv.history[1].state == DEALPG4_OUTER_REC_CANCELLING);
    CHECK(rv.history[2].state == DEALPG4_OUTER_REC_CLEAN);
    return 0;
}

/* (c) STUB_READY during CANCELLING: accepted outer-internal only
 * (never forwarded, never re-verified, no state change — the
 * composition publishes the exact registry nonce at fork time for the
 * peer's CANCEL). */
static int case_stub_ready_cancelling_fn(void)
{
    char report[2048];
    dealpg4_outer_result view;
    dealpg4_outer_record_view rv;
    int status;

    run_chan_case(&LIVE_LIMITS, "stub-ready-cancelling",
                  CHAN_SPAWN_SCRIPT, report, sizeof report, &status,
                  &view);
    CHECK(status == 0);
    CHECK(view.stub_ready_forwarded == 0);
    CHECK(view.stub_verify_failures == 0); /* never re-verified */
    CHECK(dealpg4_outer_registry_record(0, &rv) == 0);
    CHECK(rv.state == DEALPG4_OUTER_REC_CLEAN);
    CHECK(rv.clean_final == 0);
    CHECK(rv.pre_cancel_state == DEALPG4_OUTER_REC_STUB_BLOCKED);
    CHECK(rv.history_count == 4);
    CHECK(rv.history[1].state == DEALPG4_OUTER_REC_STUB_BLOCKED);
    CHECK(rv.history[2].state == DEALPG4_OUTER_REC_CANCELLING);
    CHECK(rv.history[3].state == DEALPG4_OUTER_REC_CLEAN);
    return 0;
}

/* (d) A nested-supervisor death while CANCELLING applies the
 * pre-cancel state's death fallback (recovered from the ordered
 * history — the slow live-record run). */
static int case_cancel_then_die_fn(void)
{
    char report[2048];
    dealpg4_outer_result view;
    dealpg4_outer_record_view rv;
    int status;

    run_chan_case(&LIVE_LIMITS, "cancel-then-die", CHAN_SPAWN_SCRIPT,
                  report, sizeof report, &status, &view);
    CHECK(status == DEALPG4_OUTER_EXIT_GATE_FAILURE);
    CHECK(view.nested_deaths_observed == 1);
    CHECK(view.nested_terminal_relays == 0); /* the child died without
                                                a terminal record */
    CHECK(view.nested_protocol_errors == 0); /* the CANCEL relay and
                                                the death are the only
                                                events */
    CHECK(view.records_live == 0);
    CHECK(view.records_clean == 1);
    CHECK(view.synthesized_terminals == 1); /* the fallback execution
                                               completed the record */
    CHECK(view.fallback_completions == 1);
    CHECK(dealpg4_outer_registry_record(0, &rv) == 0);
    CHECK(rv.state == DEALPG4_OUTER_REC_CLEAN);
    CHECK(rv.clean_final == 0); /* the synthesized CLEAN is always
                                   cancelled */
    CHECK(rv.cleanup_acknowledged == 1);
    CHECK(rv.pre_cancel_state == DEALPG4_OUTER_REC_STUB_BLOCKED);
    CHECK(rv.fallback_initiated == 1);
    /* The pre-cancel state's death fallback (D4: a nested-supervisor
     * death while CANCELLING applies the pre-cancel state's death
     * fallback) — the STUB_BLOCKED row TERM/grace/KILLs the retained
     * stub and proves clean. */
    CHECK(rv.fallback_state == DEALPG4_OUTER_REC_STUB_BLOCKED);
    CHECK(rv.fallback_step == DEALPG4_OUTER_FB_DONE);
    CHECK(rv.channel_drain_only == 1);
    CHECK(rv.channel_open == 0);
    CHECK(rv.stub_pid > 0); /* retained for the fallback */
    return 0;
}

/* (e) The nested REJECT: consumed, never forwarded, no state
 * change. */
static int case_reject_nested_fn(void)
{
    char report[2048];
    dealpg4_outer_result view;
    dealpg4_outer_record_view rv;
    int status;

    run_chan_case(&LIVE_LIMITS, "reject-nested", CHAN_SPAWN_SCRIPT,
                  report, sizeof report, &status, &view);
    CHECK(status == 0);
    CHECK(view.nested_rejects == 1);
    CHECK(view.nested_terminal_relays == 1);
    CHECK(dealpg4_outer_registry_record(0, &rv) == 0);
    CHECK(rv.state == DEALPG4_OUTER_REC_CLEAN);
    CHECK(rv.clean_final == 0);
    return 0;
}

/* Group 7: the relay-verbatim battery + the OUT relay cap (one
 * capture child per case). */

/* (a) STARTED/EXEC_FAILED/OUT/OUT_END/REPORT/CLEAN arrive unchanged. */
static int case_relay_verbatim_fn(void)
{
    char report[2048];
    dealpg4_outer_result view;
    int status;

    run_chan_case(&LIVE_LIMITS, "relay-verbatim", CHAN_SPAWN_SCRIPT,
                  report, sizeof report, &status, &view);
    CHECK(status == 0);
    CHECK(view.nested_terminal_relays == 1);
    CHECK(view.proof_passed == 1);
    CHECK(view.broker_relay_overflow == 0);
    return 0;
}

/* (b) The OUT 1 MiB per-stream relay cap with the truncation
 * consequence (the stall rule must never fire — the peer drains long
 * before the 5 s bound). */
static int case_flood_fn(void)
{
    char report[2048];
    dealpg4_outer_result view;
    dealpg4_outer_record_view rv;
    int status;

    run_chan_case(&FLOOD_LIMITS, "flood", CHAN_SPAWN_SCRIPT, report,
                  sizeof report, &status, &view);
    CHECK(status == 0);
    CHECK(view.broker_relay_overflow == 1); /* the truncation
                                               consequence */
    CHECK(view.broker_relay_dropped > 0);
    CHECK(view.broker_stall_fired == 0); /* the cap dropped the
                                            overflow long before
                                            the stall deadline */
    CHECK(dealpg4_outer_registry_record(0, &rv) == 0);
    CHECK(rv.state == DEALPG4_OUTER_REC_CLEAN);
    CHECK(rv.clean_final == 1);
    return 0;
}

/* Group 8: the nested-channel PROTOCOL_ERROR battery (the slow
 * live-record run). */
static int case_protocol_error_fn(void)
{
    char report[2048];
    dealpg4_outer_result view;
    dealpg4_outer_record_view rv0;
    dealpg4_outer_record_view rv1;
    dealpg4_outer_record_view rv2;
    int status;

    run_chan_case(&LIVE_LIMITS, "defects", CHAN_SPAWN_SCRIPT, report,
                  sizeof report, &status, &view);
    CHECK(status == DEALPG4_OUTER_EXIT_GATE_FAILURE);
    CHECK(view.nested_protocol_errors == 3);
    CHECK(has_token(&view, "PROTOCOL_ERROR"));
    CHECK(has_token(&view, "COORDINATOR_LOST")); /* the peer exited 0
                                                    while the records
                                                    were live */
    CHECK(!has_token(&view, "OVERALL_TIMEOUT")); /* the fallback
                                                    executions
                                                    completed every
                                                    record long before
                                                    the total
                                                    deadline */
    CHECK(view.nested_terminal_relays == 0); /* no nested terminal
                                                record was relayed */
    CHECK(view.records_total == 3);
    CHECK(view.records_live == 0);
    CHECK(view.records_clean == 3); /* the death fallbacks proved
                                       clean */
    CHECK(view.synthesized_terminals == 3);
    CHECK(view.fallback_completions == 3);
    CHECK(dealpg4_outer_registry_record(0, &rv0) == 0);
    CHECK(dealpg4_outer_registry_record(1, &rv1) == 0);
    CHECK(dealpg4_outer_registry_record(2, &rv2) == 0);
    /* Each record: channel closed, the death fallback executed for
     * the state the record was in at the defect, the single terminal
     * answer synthesized. */
    CHECK(rv0.channel_open == 0);
    CHECK(rv0.channel_drain_only == 1);
    CHECK(rv0.fallback_initiated == 1);
    CHECK(rv0.fallback_state == DEALPG4_OUTER_REC_STUB_BLOCKED);
    CHECK(rv0.state == DEALPG4_OUTER_REC_CLEAN);
    CHECK(rv0.clean_final == 0);
    CHECK(rv0.cleanup_acknowledged == 1);
    CHECK(rv1.channel_open == 0);
    CHECK(rv1.fallback_initiated == 1);
    CHECK(rv1.fallback_state == DEALPG4_OUTER_REC_FORKING);
    CHECK(rv1.state == DEALPG4_OUTER_REC_CLEAN);
    CHECK(rv2.channel_open == 0);
    CHECK(rv2.fallback_initiated == 1);
    CHECK(rv2.fallback_state == DEALPG4_OUTER_REC_FORKING);
    CHECK(rv2.state == DEALPG4_OUTER_REC_CLEAN);
    return 0;
}

/* Group 9: the death observation + the single-terminal-answer switch
 * (the slow live-record run). */
static int case_death_fn(void)
{
    char report[2048];
    dealpg4_outer_result view;
    dealpg4_outer_record_view rv;
    int status;

    run_chan_case(&LIVE_LIMITS, "death", CHAN_SPAWN_SCRIPT, report,
                  sizeof report, &status, &view);
    CHECK(status == DEALPG4_OUTER_EXIT_GATE_FAILURE);
    CHECK(view.stub_ready_forwarded == 1); /* forwarded before the
                                              death */
    CHECK(view.nested_deaths_observed == 1);
    CHECK(view.nested_terminal_relays == 0); /* the holder's late
                                                CLEAN/FAILED were
                                                consumed but never
                                                relayed */
    CHECK(view.nested_protocol_errors == 0); /* consumed records are
                                                never classified */
    CHECK(view.records_live == 0);
    CHECK(view.records_clean == 1);
    CHECK(view.synthesized_terminals == 1); /* the TARGET_PUBLISHED
                                               death fallback
                                               completed the record */
    CHECK(has_token(&view, "COORDINATOR_LOST"));
    CHECK(dealpg4_outer_registry_record(0, &rv) == 0);
    CHECK(rv.state == DEALPG4_OUTER_REC_CLEAN);
    CHECK(rv.clean_final == 0);
    CHECK(rv.cleanup_acknowledged == 1);
    CHECK(rv.fallback_initiated == 1);
    CHECK(rv.fallback_state == DEALPG4_OUTER_REC_TARGET_PUBLISHED);
    CHECK(rv.channel_drain_only == 1);
    CHECK(rv.channel_open == 0); /* EOF reached, then closed */
    CHECK(rv.queued_ack == 0);
    return 0;
}

/* Group 10: the outer-synthesized terminal records (the slow DONE
 * run). */
static int case_synth_fn(void)
{
    char report[2048];
    dealpg4_outer_result view;
    dealpg4_outer_record_view rv0;
    dealpg4_outer_record_view rv1;
    int status;

    run_chan_case(&DONE_LIMITS, "synth", CHAN_SPAWN_SYNTH, report,
                  sizeof report, &status, &view);
    CHECK(status == DEALPG4_OUTER_EXIT_GATE_FAILURE); /* the FAILED
                                                         record */
    CHECK(view.synthesized_terminals == 2);
    CHECK(view.nested_terminal_relays == 0); /* consumed-not-relayed */
    CHECK(view.stub_ready_forwarded == 0);
    CHECK(view.nested_protocol_errors == 0);
    CHECK(view.done_queued == 1);
    CHECK(view.done_clean == 0); /* the verdict at the queueing
                                    moment */
    CHECK(view.proof_passed == 1);
    CHECK(view.records_total == 2);
    CHECK(view.records_live == 0);
    CHECK(dealpg4_outer_registry_record(0, &rv0) == 0);
    CHECK(dealpg4_outer_registry_record(1, &rv1) == 0);
    CHECK(rv0.state == DEALPG4_OUTER_REC_CLEAN);
    CHECK(rv0.clean_final == 0); /* a synthesized CLEAN is always
                                    cancelled */
    CHECK(rv1.state == DEALPG4_OUTER_REC_FAILED);
    CHECK(strcmp(rv1.failure_token, "GROUP_SURVIVOR") == 0);
    CHECK(rv0.channel_drain_only == 1);
    CHECK(rv1.channel_drain_only == 1);
    CHECK(rv0.channel_open == 0);
    CHECK(rv1.channel_open == 0);
    /* The synthesized records are the only terminal answers: no
     * nested-origin state transitions ever applied. */
    CHECK(rv0.history_count == 2);
    CHECK(rv0.history[0].state == DEALPG4_OUTER_REC_FORKING);
    CHECK(rv0.history[1].state == DEALPG4_OUTER_REC_CLEAN);
    CHECK(rv1.history_count == 2);
    CHECK(rv1.history[0].state == DEALPG4_OUTER_REC_FORKING);
    CHECK(rv1.history[1].state == DEALPG4_OUTER_REC_FAILED);
    return 0;
}

/* Group 11: the full T1-T5 integration through the real serve core
 * (the slow DONE run). */
static int case_integration_fn(void)
{
    char report[4096];
    dealpg4_outer_result view;
    dealpg4_outer_record_view rv;
    int status;

    run_chan_case(&DONE_LIMITS, "integration", CHAN_SPAWN_REAL, report,
                  sizeof report, &status, &view);
    if (status != 0) {
        size_t dbi = 0;
        dealpg4_outer_record_view dbgv;

        fprintf(stderr,
                "DIAG integ status=%d total=%d live=%d clean=%d term=%d "
                "ackc=%d done=%d tokens=%zu reaped=%d code=%d st=%d\n",
                status, view.records_total, view.records_live,
                view.records_clean, view.nested_terminal_relays,
                view.ack_write_completions, view.done_queued,
                view.ntokens, view.coordinator_reaped,
                view.coordinator_si_code, view.coordinator_si_status);
        while (dealpg4_outer_registry_record(dbi, &dbgv) == 0) {
            size_t dbh;

            fprintf(stderr, "DIAG integ rec%zu state=%d stub=%d hist:",
                    dbi, dbgv.state, (int)dbgv.stub_pid);
            for (dbh = 0; dbh < dbgv.history_count; dbh++)
                fprintf(stderr, " %d", dbgv.history[dbh].state);
            fprintf(stderr, " open=%d drain=%d fb=%d\n",
                    dbgv.channel_open, dbgv.channel_drain_only,
                    dbgv.fallback_initiated);
            dbi++;
        }
    }
    CHECK(status == 0);
    CHECK(view.records_total == 1);
    CHECK(view.records_live == 0);
    CHECK(view.records_clean == 1);
    CHECK(view.stub_ready_forwarded == 1);
    CHECK(view.stub_verify_failures == 0);
    CHECK(view.ack_write_completions == 1); /* RELEASED at the ACK-
                                               write completion */
    CHECK(view.queued_write_discards == 0);
    CHECK(view.nested_terminal_relays == 1);
    CHECK(view.synthesized_terminals == 0);
    CHECK(view.nested_deaths_observed == 0); /* no fallback on a
                                                terminal record */
    CHECK(view.done_queued == 1);
    CHECK(view.done_clean == 1);
    CHECK(view.proof_passed == 1);
    CHECK(view.ntokens == 0);
    CHECK(dealpg4_outer_registry_record(0, &rv) == 0);
    CHECK(rv.state == DEALPG4_OUTER_REC_CLEAN);
    CHECK(rv.clean_final == 1);
    CHECK(rv.ack_applied == 1);
    CHECK(rv.fallback_initiated == 0);
    CHECK(rv.channel_open == 0);
    CHECK(rv.history_count == 5);
    CHECK(rv.history[0].state == DEALPG4_OUTER_REC_FORKING);
    CHECK(rv.history[1].state == DEALPG4_OUTER_REC_STUB_BLOCKED);
    CHECK(rv.history[2].state == DEALPG4_OUTER_REC_TARGET_PUBLISHED);
    CHECK(rv.history[3].state == DEALPG4_OUTER_REC_RELEASED);
    CHECK(rv.history[4].state == DEALPG4_OUTER_REC_CLEAN);
    return 0;
}

/* Group 12 (the D6 seam-catalog child): the delay-site determinism
 * (outer-pre-invoke-fork / outer-pre-ack-write / outer-pre-cancel-write
 * — each sleeps exactly the scripted 400 ms before its named step,
 * measured by the peer across the causal round trip) and the
 * FI_OUTER_DEATH kill-equivalent death (the forked scenario process
 * exits the scripted code with no cleanup and the PDEATHSIG cascades
 * kill the coordinator, the nested supervisor, and the released
 * target stand-in). */

static int case_delay_invoke_fn(void)
{
    char report[2048];
    dealpg4_outer_result view;
    int status;

    install_delay(DEALPG4_FI_DELAY_OUTER_PRE_INVOKE_FORK, 400);
    run_chan_case(&LIVE_LIMITS, "delay-invoke", CHAN_SPAWN_SCRIPT, report,
                  sizeof report, &status, &view);
    CHECK(status == 0);
    CHECK(view.records_total == 1);
    CHECK(view.records_clean == 1);
    CHECK(view.records_live == 0);
    CHECK(view.ack_write_completions == 1);
    CHECK(view.ntokens == 0);
    dealpg4_fi_restore_defaults();
    return 0;
}

static int case_delay_ack_fn(void)
{
    char report[2048];
    dealpg4_outer_result view;
    int status;

    install_delay(DEALPG4_FI_DELAY_OUTER_PRE_ACK_WRITE, 400);
    run_chan_case(&LIVE_LIMITS, "delay-ack", CHAN_SPAWN_SCRIPT, report,
                  sizeof report, &status, &view);
    CHECK(status == 0);
    CHECK(view.records_total == 1);
    CHECK(view.records_clean == 1);
    CHECK(view.records_live == 0);
    CHECK(view.ack_write_completions == 1);
    CHECK(view.ntokens == 0);
    dealpg4_fi_restore_defaults();
    return 0;
}

static int case_delay_cancel_fn(void)
{
    char report[2048];
    dealpg4_outer_result view;
    int status;

    install_delay(DEALPG4_FI_DELAY_OUTER_PRE_CANCEL_WRITE, 400);
    run_chan_case(&LIVE_LIMITS, "delay-cancel", CHAN_SPAWN_SCRIPT,
                  report, sizeof report, &status, &view);
    CHECK(status == 0);
    CHECK(view.records_total == 1);
    CHECK(view.records_clean == 1);
    CHECK(view.records_live == 0);
    CHECK(view.ntokens == 0);
    dealpg4_fi_restore_defaults();
    return 0;
}

/* The FI_OUTER_DEATH case runs the core in its own forked scenario
 * process: the site _exits the process with the scripted code before
 * the core returns, so the case cannot use run_chan_case (whose core
 * call returns). The scenario reaches RELEASED (the death gate), the
 * outer dies with no cleanup, and the suite process (the subreaper,
 * set in main) reaps the three PDEATHSIG-cascade victims. */
static int case_outer_death_fn(void)
{
    char report[4096];
    chan_spawn sp;
    dealpg4_outer_spawn spawn = make_chan_spawn(&sp, CHAN_SPAWN_SCRIPT);
    char *argv[8];
    int pipefd[2];
    pid_t pid;
    int status = -1;
    size_t total = 0;

    install_outer_death(55);
    peer_argv(argv, "death-hold");
    CHECK(pipe(pipefd) == 0);
    pid = fork();
    CHECK(pid >= 0);
    if (pid == 0) {
        int st;

        close(pipefd[0]);
        st = dealpg4_outer_core(&LIVE_LIMITS, NONCE, argv, "build",
                                pipefd[1], &spawn);
        (void)st;
        _exit(0); /* unreachable: FI_OUTER_DEATH _exits first */
    }
    close(pipefd[1]);
    for (;;) {
        ssize_t r = read(pipefd[0], report + total,
                         sizeof report - total - 1);

        if (r > 0) {
            total += (size_t)r;
            if (total >= sizeof report - 1)
                break;
            continue;
        }
        if (r < 0 && errno == EINTR)
            continue;
        break; /* EOF: the death closed the report pipe */
    }
    close(pipefd[0]);
    CHECK(waitpid(pid, &status, 0) == pid);
    CHECK(WIFEXITED(status) && WEXITSTATUS(status) == 55);
    /* The pinned no-cleanup consequence: the broker socket path
     * remains — remove the abandoned stale paths so the later cases
     * start clean (the production stale-unlink covers only the
     * same-nonce path). */
    {
        DIR *d = opendir("build");

        if (d != NULL) {
            struct dirent *e;

            while ((e = readdir(d)) != NULL) {
                if (strncmp(e->d_name, ".dealpg4-broker-", 16) == 0) {
                    char path[512];

                    snprintf(path, sizeof path, "build/%s", e->d_name);
                    (void)unlink(path);
                }
            }
            closedir(d);
        }
    }
    dealpg4_fi_restore_defaults();
    return 0;
}

/* === Main ============================================================== */

/* Each case runs in its own capture child: the core's entry preamble
 * owns setsid() (a second in-process core call refuses with
 * CAPABILITY_MISSING — the preamble is once per process). */
static void run_case_child(const char *name, outer_test_fn fn)
{
    char errbuf[8192];
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
    int is_subreaper = 0;

    g_suite_argv0 = argv[0];
    dealpg4_outer_note_process_argv0(argv[0]);
    signal(SIGPIPE, SIG_IGN);
    if (argc >= 3 && strcmp(argv[1], "--channel-peer") == 0)
        return chan_peer_entry(argc, argv);

    /* The suite is the subreaper for the FI_OUTER_DEATH case: when
     * the forked scenario process dies by the scripted seam, the
     * PDEATHSIG-cascade victims (the coordinator peer, the scripted
     * nested supervisor, and its stub stand-in) reparent here and are
     * reaped below. */
    CHECK(prctl(PR_SET_CHILD_SUBREAPER, 1) == 0);
    CHECK(prctl(PR_GET_CHILD_SUBREAPER, &is_subreaper) == 0
          && is_subreaper == 1);

    run_case_child("forked / no-STARTED schedule", case_forked_fn);
    run_case_child("ACK relay + RELEASED-at-write-completion",
                   case_ready_ok_fn);
    run_case_child("STUB_READY verify-fail wrong-pgid",
                   case_verify_fail_wrong_pgid_fn);
    run_case_child("STUB_READY verify-fail wrong-sid",
                   case_verify_fail_wrong_sid_fn);
    run_case_child("STUB_READY verify-fail wrong-nonce",
                   case_verify_fail_wrong_nonce_fn);
    run_case_child("STUB_READY verify-fail dead-pid",
                   case_verify_fail_dead_pid_fn);
    run_case_child("STUB_READY verify-fail self-pid",
                   case_verify_fail_self_pid_fn);
    run_case_child("STUB_READY verify-fail wrong-id",
                   case_verify_fail_wrong_id_fn);
    run_case_child("record-level ACK rejection split", case_ack_reject_fn);
    run_case_child("queued-write discard on terminality",
                   case_discard_fn);
    run_case_child("validated CANCEL relay", case_cancel_read_fn);
    run_case_child("CANCEL drops the queued ACK", case_cancel_drop_ack_fn);
    run_case_child("CANCELLING-from-RELEASED relays", case_cancel_cycle_fn);
    run_case_child("CANCELLING-from-FORKING STUB_FORKED",
                   case_cancel_from_forking_fn);
    run_case_child("STUB_READY during CANCELLING",
                   case_stub_ready_cancelling_fn);
    run_case_child("death while CANCELLING (pre-cancel fallback)",
                   case_cancel_then_die_fn);
    run_case_child("nested REJECT hold", case_reject_nested_fn);
    run_case_child("relay-verbatim battery", case_relay_verbatim_fn);
    run_case_child("OUT relay cap flood", case_flood_fn);
    run_case_child("nested-channel PROTOCOL_ERROR battery",
                   case_protocol_error_fn);
    run_case_child("death observation + consume-not-relay",
                   case_death_fn);
    run_case_child("outer-synthesized terminal records", case_synth_fn);
    run_case_child("T1-T5 integration (real serve core)",
                   case_integration_fn);
    run_case_child("outer-pre-invoke-fork delay seam",
                   case_delay_invoke_fn);
    run_case_child("outer-pre-ack-write delay seam", case_delay_ack_fn);
    run_case_child("outer-pre-cancel-write delay seam",
                   case_delay_cancel_fn);
    run_case_child("FI_OUTER_DEATH PDEATHSIG cascade",
                   case_outer_death_fn);

    /* The FI_OUTER_DEATH cascade reap: exactly the coordinator peer,
     * the scripted nested supervisor, and its stub stand-in — each
     * killed by the armed PDEATHSIG SIGKILL (si_status 9) after the
     * scenario process died with the scripted code. */
    {
        int killed = 0;
        int unexpected = 0;
        uint64_t deadline = peer_now_ms() + 3000;
        siginfo_t si;

        for (;;) {
            memset(&si, 0, sizeof si);
            if (waitid(P_ALL, 0, &si, WEXITED | WNOHANG) != 0) {
                if (errno == ECHILD)
                    break;
                continue;
            }
            if (si.si_pid == 0) {
                if (peer_now_ms() >= deadline)
                    break;
                sleep_ms(10);
                continue;
            }
            if (si.si_code == CLD_KILLED && si.si_status == 9)
                killed++;
            else
                unexpected++;
        }
        CHECK(killed == 3);
        CHECK(unexpected == 0);
    }

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
        fprintf(stderr, "outer-channel-tests: %d failures in %d checks\n",
                g_failures, g_checks);
        return 1;
    }
    fprintf(stderr, "outer-channel-tests: %d checks passed\n", g_checks);
    return 0;
}
