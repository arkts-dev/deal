/*
 * DEALPG4 outer coordinator component acceptance suite
 * (tools/test/outer-coordinator-tests.c).
 *
 * The component-level cases of ISSUE-0294 Verification, run against
 * tools/src/outer.c + monotonic.c + protocol.c + fi.c + selftest.c +
 * drain.c compiled with the pinned flags (tools/test/
 * run-outer-coordinator-tests.sh). Permanent and re-runnable; lives
 * outside the tools/src/ build glob so the pinned artifact build is
 * unchanged. The suite drives the real core (T1's preamble, loop,
 * limits, and exit-status mapping) with scripted coordinator argv and
 * scaled OuterLimits — real fork/exec/pipe/signal/waitid machinery
 * only.
 *
 * Case groups:
 *  1. success bootstrap: /bin/true publishes COORD_READY (verified),
 *     execs, exits 0 — the outer verifies the report against /proc
 *     and getpgid/getsid, drains both streams to EOF, reaps status 0,
 *     runs the final proof (ECHILD, no /proc ppid == outerPid,
 *     stream EOFs), and exits 0 with no survivor;
 *  2. exec failure: a nonexistent coordinator argv ->
 *     COORD_EXEC_FAILED -> COORDINATOR_STARTUP_FAILED: the child is
 *     reaped (status 127), the unverified group identity is never
 *     signaled (no liveness check against the unverified pgid), the
 *     group-absent item is discharged via reap-to-ECHILD +
 *     adopted-scan + stream-EOF, the proof passes, the gate nonzero;
 *  3. readiness bound: a scripted coord-pre-ready-write delay past
 *     the scaled readiness deadline -> COORDINATOR_STARTUP_FAILED
 *     with the immediate by-pid escalation (TERM by pid, steps 1-2 of
 *     the group escalation skipped — the group was never verified);
 *  4. COORDINATOR_HANG escalation: a coordinator that completes the
 *     readiness handshake (the suite re-execs itself as the broker
 *     peer) and then traps TERM and hangs past totalDeadline -
 *     killAndProofReserveMs (scaled) -> step-1 liveness check on the
 *     verified group, TERM -pgid, grace termGraceMs, KILL -pgid
 *     (re-verified), reap to ECHILD, adopted scan clean, proof
 *     passes, nonzero gate (ISSUE-0299: a never-connecting hang is
 *     now READINESS_TIMEOUT — the handshake-first shape preserves
 *     the pinned COORDINATOR_HANG trigger);
 *  5. output flood: a coordinator that floods stdout/stderr before
 *     any readiness event — each drain retains exactly the 1 MiB cap
 *     plus the truncation marker, keeps draining to EOF, never
 *     blocks, and the readiness deadline (ISSUE-0299: READINESS_
 *     TIMEOUT with the full group escalation) fires on schedule;
 *  6. shell loss (closed report stdout): the report write fails with
 *     EPIPE -> the total-cancel trigger fires deterministically
 *     (SHELL_LOST, gate nonzero) even though the coordinator facts
 *     were clean;
 *  7. shell loss (orphaned outer): the grandchild's parent exits
 *     during the run -> the periodic getppid() recheck fires the
 *     trigger, the hanging coordinator is escalated by the verified
 *     group, and the run ends with SHELL_LOST and an empty tree;
 *  8. FI_COORD_READY_MISMATCH: the child publishes a lying COORD_READY
 *     (pgid/sid shifted) -> the report//proc cross-check disagrees ->
 *     COORDINATOR_STARTUP_FAILED;
 *  9. FI_OUTER_PIPE: the pre-exec pipe fails before the coordinator
 *     fork -> no fork, gate-fatal, COORDINATOR_STARTUP_FAILED, the
 *     unverified-group discharge trivially holds, proof passes;
 * 10. drain-pipe blocking semantics: the suite binary re-execs
 *     itself as the coordinator probe (--coord-pipe-flags-probe) and
 *     asserts its fds 1/2 carry no O_NONBLOCK — the write ends of
 *     the two coordinator stream pipes keep the target's ordinary
 *     blocking semantics (the supervisor.c topology pin), with only
 *     the drain read ends O_NONBLOCK.
 *
 * Every case group runs in a forked helper child whose captured
 * stderr is inspected and whose own assertion count propagates
 * through the child's exit status: a group-internal failure can
 * never stay green. The final gate covers the parent-side checks and
 * every propagated child-side failure.
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
#include <sys/un.h>
#include <sys/wait.h>
#include <unistd.h>

#include "../src/drain.h"
#include "../src/monotonic.h"
#include "../src/outer.h"
#include "../src/selftest.h"

static int g_checks;
static int g_failures;
static const char *g_suite_argv0; /* argv[0] of the suite binary — the
                                     re-exec probe (group 10) uses it
                                     as the scripted coordinator
                                     program */

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

/* The scaled limits every deadline-driven case uses: overall 10000,
 * readiness 500, nestedStop 8000 (8000 + 2000 cleanup <= 10000), so
 * the pinned escalation deadline lands at T0o + 10000 - 5000 = T0o +
 * 5000 with the 2000 ms grace completing inside the budget. */
static const OuterLimits SCALED = {10000, 500, 8000, 2000, 200};

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
        /* Fresh per-group counters: the fork-copied parent counts
         * would make an earlier group's failure taint every later
         * group. */
        g_checks = 0;
        g_failures = 0;
        r = fn();
        /* The group's own g_failures propagate as the exit status: a
         * group-internal assertion failure can never stay green. */
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

/* Gate one capture-child group: the child's own assertion failures
 * are propagated through its exit status AND its captured stderr —
 * both are checked here (and the captured FAIL lines are echoed), so
 * a group-internal failure can never stay green. */
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

/* View helpers. */
static int has_token(const dealpg4_outer_result *v, const char *token)
{
    size_t i;

    for (i = 0; i < v->ntokens; i++) {
        if (strcmp(v->tokens[i], token) == 0)
            return 1;
    }
    return 0;
}

/* === Group 1: success bootstrap ======================================== */

static int success_bootstrap_fn(void)
{
    char *argv[] = {(char *)"/bin/true", NULL};
    char report[1024];
    dealpg4_outer_result view;
    const dealpg4_drain_ctx *dout = NULL;
    const dealpg4_drain_ctx *derr = NULL;
    uint64_t before;
    uint64_t after;
    int status;

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
    CHECK(view.coordinator_pid > 0);
    CHECK(view.ready_verified == 1);
    CHECK(view.coordinator_pgid == view.coordinator_pid);
    CHECK(view.coordinator_sid == view.coordinator_pid);
    CHECK(view.coordinator_reaped == 1);
    CHECK(view.coordinator_exited_0 == 1);
    CHECK(view.coordinator_si_code == CLD_EXITED);
    CHECK(view.coordinator_si_status == 0);
    CHECK(view.coord_exec_failed == 0);
    /* A healthy coordinator that exits 0 is never signaled. */
    CHECK(view.escalation_term_issued == 0);
    CHECK(view.escalation_kill_issued == 0);
    CHECK(view.group_liveness_checked == 0);
    CHECK(view.shell_lost == 0);
    /* The final proof ran to completion. */
    CHECK(view.proof_passed == 1);
    CHECK(view.proof_reap_echild == 1);
    CHECK(view.proof_adopted_clean == 1);
    CHECK(view.proof_group_clean == 1);
    CHECK(view.proof_streams_eof == 1);
    CHECK(view.proof_broker_clean == 1);
    CHECK(view.proof_registry_clean == 1);

    dealpg4_outer_drain_state(&dout, &derr);
    CHECK(dout != NULL && derr != NULL);
    CHECK(dout->eof == 1 && derr->eof == 1);
    CHECK(dout->failed == 0 && derr->failed == 0);
    CHECK(dout->total_read == 0 && derr->total_read == 0);

    /* The report: header + coordinator facts + proof, no tokens. */
    CHECK(strstr(report, "OUTER final 0 ") != NULL);
    CHECK(strstr(report, "OUTER coord pid=") != NULL);
    CHECK(strstr(report, "OUTER proof ok\n") != NULL);
    CHECK(strstr(report, "OUTER token ") == NULL);

    /* No survivor remains: no children, no socket. */
    check_no_children();
    CHECK(outer_socket_path_count("build") == 0);
    return (g_failures > 0) ? 1 : 0;
}

/* === Group 2: exec failure ============================================= */

static int exec_failure_fn(void)
{
    char *argv[] = {(char *)"/nonexistent/dealpg4-coordinator-helper",
                    NULL};
    char report[1024];
    dealpg4_outer_result view;
    uint64_t before;
    uint64_t after;
    int status;

    before = dealpg4_now_ms();
    status = core_with_report(&SCALED, argv, report, sizeof report);
    after = dealpg4_now_ms();

    CHECK(status == DEALPG4_OUTER_EXIT_GATE_FAILURE);
    CHECK(after - before < 6000);

    memset(&view, 0, sizeof view);
    dealpg4_outer_last_result(&view);
    CHECK(has_token(&view, "COORDINATOR_STARTUP_FAILED"));
    CHECK(view.coord_exec_failed == 1);
    CHECK(view.ready_verified == 0);
    CHECK(view.coordinator_pgid == 0); /* never verified */
    CHECK(view.coordinator_sid == 0);
    CHECK(view.coordinator_reaped == 1);
    CHECK(view.coordinator_si_code == CLD_EXITED);
    CHECK(view.coordinator_si_status == 127);
    /* The unverified group identity is never signaled: no liveness
     * check against the unverified pgid is attempted, the by-pid
     * scope is the only escalation form. */
    CHECK(view.group_liveness_checked == 0);
    if (view.escalation_term_issued)
        CHECK(view.escalation_group_scope == 0);
    /* The unverified-group discharge: reap-to-ECHILD +
     * adopted-descendant scan + stream EOF hold. */
    CHECK(view.proof_passed == 1);
    CHECK(view.proof_reap_echild == 1);
    CHECK(view.proof_adopted_clean == 1);
    CHECK(view.proof_group_clean == 1);
    CHECK(view.proof_streams_eof == 1);

    CHECK(strstr(report, "OUTER final 1 ") != NULL);
    CHECK(strstr(report, "OUTER token COORDINATOR_STARTUP_FAILED\n")
          != NULL);
    CHECK(strstr(report, "OUTER proof ok\n") != NULL);

    check_no_children();
    CHECK(outer_socket_path_count("build") == 0);
    return (g_failures > 0) ? 1 : 0;
}

/* === Group 3: readiness bound + by-pid escalation ====================== */

static const char *const delay_sites[] = {
    DEALPG4_FI_DELAY_OUTER_PRE_COORD_FORK,
    DEALPG4_FI_DELAY_COORD_POST_FORK,
    DEALPG4_FI_DELAY_COORD_PRE_READY_WRITE,
};

static const int fail_sites[] = {
    FI_OUTER_SUBREAPER, FI_OUTER_TIMERFD, FI_OUTER_SIGNALFD,
    FI_OUTER_NONCE, FI_OUTER_PIPE, FI_COORD_READY_MISMATCH
};

static const dealpg4_fi_catalog catalog = {
    delay_sites, 3, fail_sites, 6, NULL, 0, NULL, 0
};

static int readiness_bound_fn(void)
{
    /* The readiness deadline (T0o + 500 under SCALED) bounds the
     * COORD_READY wait: the injected 8000 ms pre-ready-write delay
     * keeps the child in the bootstrap past the deadline, so the
     * outer decides COORDINATOR_STARTUP_FAILED and TERMs the child by
     * pid — the unverified group identity is never used. */
    dealpg4_fi_script_delay delays[1];
    dealpg4_fi_script script;
    char *argv[] = {(char *)"/bin/true", NULL};
    char report[1024];
    dealpg4_outer_result view;
    uint64_t before;
    uint64_t after;
    int status;

    delays[0].site = DEALPG4_FI_DELAY_COORD_PRE_READY_WRITE;
    delays[0].duration_ms = 8000;
    delays[0].oneshot = 1;
    script.delays = delays;
    script.ndelays = 1;
    script.fails = NULL;
    script.nfails = 0;
    script.congests = NULL;
    script.ncongests = 0;
    CHECK(dealpg4_fi_install_overrides(&script, &catalog) == 0);

    before = dealpg4_now_ms();
    status = core_with_report(&SCALED, argv, report, sizeof report);
    after = dealpg4_now_ms();
    dealpg4_fi_restore_defaults();

    CHECK(status == DEALPG4_OUTER_EXIT_GATE_FAILURE);
    /* The readiness deadline fired (~500 ms) and the run completed
     * well inside the scaled budget. */
    CHECK(after - before >= 400);
    CHECK(after - before < 6000);

    memset(&view, 0, sizeof view);
    dealpg4_outer_last_result(&view);
    CHECK(has_token(&view, "COORDINATOR_STARTUP_FAILED"));
    CHECK(view.readiness_fired == 1);
    CHECK(view.ready_verified == 0);
    CHECK(view.coordinator_pgid == 0);
    CHECK(view.escalation_term_issued == 1);
    CHECK(view.escalation_group_scope == 0); /* by pid only */
    CHECK(view.group_liveness_checked == 0);
    CHECK(view.escalation_term_ms >= 350); /* at the readiness deadline */
    CHECK(view.coordinator_reaped == 1);
    CHECK(view.proof_passed == 1);
    CHECK(view.proof_group_clean == 1); /* unverified-group discharge */
    CHECK(view.proof_streams_eof == 1);

    CHECK(dealpg4_fi_hooks.delay_ms(0, DEALPG4_FI_DELAY_COORD_PRE_READY_WRITE)
          == 0); /* production defaults restored */

    check_no_children();
    CHECK(outer_socket_path_count("build") == 0);
    return (g_failures > 0) ? 1 : 0;
}

/* === Group 4: COORDINATOR_HANG escalation deadline ===================== */

static int hang_escalation_fn(void)
{
    char *argv[4];

    argv[0] = (char *)g_suite_argv0;
    argv[1] = (char *)"--hang-peer";
    argv[2] = NULL;
    argv[3] = NULL;
    char report[1024];
    dealpg4_outer_result view;
    uint64_t before;
    uint64_t after;
    int status;

    before = dealpg4_now_ms();
    status = core_with_report(&SCALED, argv, report, sizeof report);
    after = dealpg4_now_ms();

    CHECK(status == DEALPG4_OUTER_EXIT_GATE_FAILURE);
    /* The pinned trigger (post-readiness hang): escalation at
     * totalDeadline - 5000 (T0o + 5000 under SCALED), grace 2000,
     * then KILL — all inside the 10000 ms budget. */
    CHECK(after - before >= 6900);
    CHECK(after - before < 9500);

    memset(&view, 0, sizeof view);
    dealpg4_outer_last_result(&view);
    CHECK(has_token(&view, "COORDINATOR_HANG"));
    CHECK(!has_token(&view, "COORDINATOR_LOST"));
    CHECK(view.ready_verified == 1);
    CHECK(view.coordinator_pgid == view.coordinator_pid);
    /* Step 1: the liveness check on the verified group, then TERM
     * -pgid (the outer verified it is not a member). */
    CHECK(view.escalation_term_issued == 1);
    CHECK(view.escalation_term_sent == 1);
    CHECK(view.escalation_group_scope == 1);
    CHECK(view.group_liveness_checked == 1);
    CHECK(view.escalation_term_ms >= 4900);
    CHECK(view.escalation_term_ms < 7000);
    /* Step 2: the grace passed and the re-verified KILL -pgid
     * dispatched. */
    CHECK(view.escalation_kill_issued == 1);
    CHECK(view.escalation_kill_sent == 1);
    CHECK(view.escalation_kill_ms >= view.escalation_term_ms + 1900);
    /* Reaped to ECHILD; the adopted scan clean; the proof passed. */
    CHECK(view.coordinator_reaped == 1);
    CHECK(view.coordinator_si_code == CLD_KILLED);
    CHECK(view.coordinator_si_status == SIGKILL);
    CHECK(view.proof_passed == 1);
    CHECK(view.proof_reap_echild == 1);
    CHECK(view.proof_adopted_clean == 1);
    CHECK(view.proof_group_clean == 1);
    CHECK(view.proof_streams_eof == 1);
    CHECK(view.total_fired == 0);

    CHECK(strstr(report, "OUTER token COORDINATOR_HANG\n") != NULL);

    check_no_children();
    CHECK(outer_socket_path_count("build") == 0);
    return (g_failures > 0) ? 1 : 0;
}

/* === Group 5: output flood ============================================= */

static int output_flood_fn(void)
{
    char *argv[] = {(char *)"/bin/sh", (char *)"-c",
                    (char *)"head -c 3000000 /dev/zero; "
                            "head -c 3000000 /dev/zero >&2; sleep 100",
                    NULL};
    char report[1024];
    dealpg4_outer_result view;
    const dealpg4_drain_ctx *dout = NULL;
    const dealpg4_drain_ctx *derr = NULL;
    uint64_t before;
    uint64_t after;
    int status;

    before = dealpg4_now_ms();
    status = core_with_report(&SCALED, argv, report, sizeof report);
    after = dealpg4_now_ms();

    CHECK(status == DEALPG4_OUTER_EXIT_GATE_FAILURE);
    /* The readiness deadline fired on schedule at T0o + 500 while
     * 3 MiB per stream flowed — the drains never blocked and never
     * suspended a native deadline (ISSUE-0299: a coordinator that
     * never completes FEATURE_READY by the readiness deadline is
     * READINESS_TIMEOUT with the immediate full-group escalation).
     * The flood script's sh has the default TERM disposition, so the
     * escalation TERM kills the coordinator (the TERM-death path)
     * and the run completes at ~0.5 s — before the 2000 ms grace
     * expiry and the KILL step. */
    CHECK(after - before >= 400);
    CHECK(after - before < 1500);

    memset(&view, 0, sizeof view);
    dealpg4_outer_last_result(&view);
    CHECK(has_token(&view, "READINESS_TIMEOUT"));
    CHECK(view.readiness_timeout_fired == 1);
    CHECK(view.escalation_term_issued == 1);
    CHECK(view.escalation_term_sent == 1);
    CHECK(view.escalation_group_scope == 1);
    CHECK(view.group_liveness_checked == 1);
    CHECK(view.escalation_term_ms >= 400);
    CHECK(view.escalation_term_ms < 1500);
    /* The TERM killed the untrapped sh; the run completed before the
     * grace expiry, so no KILL step ran (the re-verified KILL was
     * never reached). */
    CHECK(view.escalation_kill_issued == 0);
    CHECK(view.escalation_kill_sent == 0);
    CHECK(view.coordinator_reaped == 1);
    CHECK(view.coordinator_si_code == CLD_KILLED);
    CHECK(view.coordinator_si_status == SIGTERM);
    CHECK(view.total_fired == 0);
    CHECK(view.proof_passed == 1);
    CHECK(view.proof_group_clean == 1);
    CHECK(view.proof_streams_eof == 1);

    /* Each drain retained exactly the 1 MiB cap plus the truncation
     * marker, read everything to EOF, and never failed. */
    dealpg4_outer_drain_state(&dout, &derr);
    CHECK(dout != NULL && derr != NULL);
    CHECK(dout->total_read == 3000000);
    CHECK(derr->total_read == 3000000);
    CHECK(dout->truncated == 1);
    CHECK(derr->truncated == 1);
    CHECK(dout->retained_len
          == DEALPG4_DRAIN_CAP_BYTES + DEALPG4_DRAIN_TRUNCATION_MARKER_LEN);
    CHECK(derr->retained_len
          == DEALPG4_DRAIN_CAP_BYTES + DEALPG4_DRAIN_TRUNCATION_MARKER_LEN);
    CHECK(memcmp(dout->retained + dout->retained_len
                     - DEALPG4_DRAIN_TRUNCATION_MARKER_LEN,
                 DEALPG4_DRAIN_TRUNCATION_MARKER,
                 DEALPG4_DRAIN_TRUNCATION_MARKER_LEN) == 0);
    CHECK(memcmp(derr->retained + derr->retained_len
                     - DEALPG4_DRAIN_TRUNCATION_MARKER_LEN,
                 DEALPG4_DRAIN_TRUNCATION_MARKER,
                 DEALPG4_DRAIN_TRUNCATION_MARKER_LEN) == 0);
    CHECK(dout->eof == 1 && derr->eof == 1);
    CHECK(dout->failed == 0 && derr->failed == 0);

    check_no_children();
    CHECK(outer_socket_path_count("build") == 0);
    return (g_failures > 0) ? 1 : 0;
}

/* === Group 6: shell loss — closed report stdout ======================== */

static int shell_loss_report_fn(void)
{
    char *argv[] = {(char *)"/bin/true", NULL};
    int pipefd[2];
    dealpg4_outer_result view;
    uint64_t before;
    uint64_t after;
    int status;

    CHECK(pipe(pipefd) == 0);
    /* The read end is closed before the run: the final report write
     * fails with EPIPE — the total-cancel trigger (parent D1). */
    close(pipefd[0]);
    before = dealpg4_now_ms();
    status = dealpg4_outer_core(&SCALED, NONCE, argv, "build",
                                pipefd[1], NULL);
    after = dealpg4_now_ms();
    close(pipefd[1]);

    CHECK(status == DEALPG4_OUTER_EXIT_GATE_FAILURE);
    CHECK(after - before < 8000);

    memset(&view, 0, sizeof view);
    dealpg4_outer_last_result(&view);
    CHECK(view.shell_lost == 1);
    CHECK(has_token(&view, "SHELL_LOST"));
    /* The coordinator facts were clean; the gate flipped on the
     * trigger. */
    CHECK(view.coordinator_exited_0 == 1);
    CHECK(view.proof_passed == 1);
    CHECK(view.exit_status == 1);

    check_no_children();
    CHECK(outer_socket_path_count("build") == 0);
    return (g_failures > 0) ? 1 : 0;
}

/* === Group 7: shell loss — orphaned outer ============================== */

/* The grandchild: runs the core against a hanging coordinator while
 * its parent exits mid-run; writes one result line to result_fd and
 * exits. */
static int orphan_grandchild_fn(int result_fd)
{
    char *argv[] = {(char *)"/bin/sh", (char *)"-c", (char *)"sleep 100",
                    NULL};
    dealpg4_outer_result view;
    char line[256];
    int devnull;
    int n;
    int status;

    devnull = open("/dev/null", O_WRONLY);
    if (devnull == -1)
        return 125;
    status = dealpg4_outer_core(&SCALED, NONCE, argv, "build", devnull,
                                NULL);
    close(devnull);

    memset(&view, 0, sizeof view);
    dealpg4_outer_last_result(&view);
    n = snprintf(line, sizeof line,
                 "status=%d shell=%d token=%d proof=%d reaped=%d "
                 "group=%d term=%d\n",
                 status, view.shell_lost, has_token(&view, "SHELL_LOST"),
                 view.proof_passed, view.coordinator_reaped,
                 view.escalation_group_scope, view.escalation_term_sent);
    if (n > 0 && (size_t)n < sizeof line) {
        ssize_t r = write(result_fd, line, (size_t)n);

        (void)r;
    }
    return status;
}

/* The parent-side helper (runs in a capture child): forks the
 * grandchild, lets it enter the core, then exits so the grandchild
 * becomes an orphan — the periodic getppid() recheck detects the
 * mismatch. The grandchild's result line travels over the done pipe. */
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
        /* Give the parent a beat to exit after the core records
         * shellPid; the 500 ms parent-side sleep below guarantees the
         * ordering without a second synchronization channel. */
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
    return 0; /* the capture child exits; the grandchild is orphaned */
}

static int shell_loss_orphan_case(void)
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
        /* Reap the helper; then read the grandchild's result line
         * (the grandchild reparented away, so the done pipe's EOF
         * ends the read). */
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

    /* The trigger fired deterministically: gate nonzero, SHELL_LOST,
     * the hanging coordinator escalated by the verified group and
     * reaped, the proof passed. */
    CHECK(strstr(buf, "status=1 ") != NULL);
    CHECK(strstr(buf, "shell=1 ") != NULL);
    CHECK(strstr(buf, "token=1 ") != NULL);
    CHECK(strstr(buf, "proof=1 ") != NULL);
    CHECK(strstr(buf, "reaped=1 ") != NULL);
    CHECK(strstr(buf, "group=1 ") != NULL);
    CHECK(strstr(buf, "term=1\n") != NULL);
    return 0;
}

/* === Group 8: FI_COORD_READY_MISMATCH ================================== */

static int ready_mismatch_fn(void)
{
    dealpg4_fi_script_fail fails[1];
    dealpg4_fi_script script;
    char *argv[] = {(char *)"/bin/true", NULL};
    char report[1024];
    dealpg4_outer_result view;
    uint64_t before;
    uint64_t after;
    int status;

    fails[0].site = FI_COORD_READY_MISMATCH;
    fails[0].value = 1; /* the reported pgid/sid shift by 1 */
    fails[0].oneshot = 1;
    script.delays = NULL;
    script.ndelays = 0;
    script.fails = fails;
    script.nfails = 1;
    script.congests = NULL;
    script.ncongests = 0;
    CHECK(dealpg4_fi_install_overrides(&script, &catalog) == 0);

    before = dealpg4_now_ms();
    status = core_with_report(&SCALED, argv, report, sizeof report);
    after = dealpg4_now_ms();
    dealpg4_fi_restore_defaults();

    CHECK(status == DEALPG4_OUTER_EXIT_GATE_FAILURE);
    CHECK(after - before < 6000);

    memset(&view, 0, sizeof view);
    dealpg4_outer_last_result(&view);
    CHECK(has_token(&view, "COORDINATOR_STARTUP_FAILED"));
    /* The lying report never verifies: pgid/sid stay unverified. */
    CHECK(view.ready_verified == 0);
    CHECK(view.coordinator_pgid == 0);
    CHECK(view.coordinator_sid == 0);
    CHECK(view.coordinator_reaped == 1);
    CHECK(view.group_liveness_checked == 0);
    CHECK(view.proof_passed == 1);

    CHECK(dealpg4_fi_hooks.fail(FI_COORD_READY_MISMATCH) == 0);

    check_no_children();
    CHECK(outer_socket_path_count("build") == 0);
    return (g_failures > 0) ? 1 : 0;
}

/* === Group 9: FI_OUTER_PIPE ============================================ */

static int outer_pipe_failure_fn(void)
{
    dealpg4_fi_script_fail fails[1];
    dealpg4_fi_script script;
    char *argv[] = {(char *)"/bin/true", NULL};
    char report[1024];
    dealpg4_outer_result view;
    uint64_t before;
    uint64_t after;
    int status;

    fails[0].site = FI_OUTER_PIPE;
    fails[0].value = EPERM;
    fails[0].oneshot = 1;
    script.delays = NULL;
    script.ndelays = 0;
    script.fails = fails;
    script.nfails = 1;
    script.congests = NULL;
    script.ncongests = 0;
    CHECK(dealpg4_fi_install_overrides(&script, &catalog) == 0);

    before = dealpg4_now_ms();
    status = core_with_report(&SCALED, argv, report, sizeof report);
    after = dealpg4_now_ms();
    dealpg4_fi_restore_defaults();

    CHECK(status == DEALPG4_OUTER_EXIT_GATE_FAILURE);
    CHECK(after - before < 3000);

    memset(&view, 0, sizeof view);
    dealpg4_outer_last_result(&view);
    CHECK(has_token(&view, "COORDINATOR_STARTUP_FAILED"));
    /* No fork: the coordinator pid was never attached, the unverified-
     * group discharge holds trivially, the proof still passes. */
    CHECK(view.coordinator_pid == -1);
    CHECK(view.coordinator_reaped == 0);
    CHECK(view.proof_passed == 1);
    CHECK(view.proof_reap_echild == 1);
    CHECK(view.proof_adopted_clean == 1);
    CHECK(view.proof_group_clean == 1);

    CHECK(strstr(report, "OUTER final 1 ") != NULL);
    CHECK(strstr(report, "OUTER token COORDINATOR_STARTUP_FAILED\n")
          != NULL);

    check_no_children();
    CHECK(outer_socket_path_count("build") == 0);
    return (g_failures > 0) ? 1 : 0;
}

/* === Group 4 peer: the post-readiness hang ============================ */

/* The hang peer (group 4 re-execs the suite binary as the
 * coordinator): completes the readiness handshake over the broker,
 * then traps TERM and hangs — the COORDINATOR_HANG trigger escalates
 * it at the pinned deadline (TERM -pgid trapped -> grace -> KILL
 * -pgid). */
static int hang_peer_entry(void)
{
    const char *path = getenv("DEALPG4_BROKER_PATH");
    const char *nonce = getenv("DEALPG4_NONCE");
    struct sockaddr_un sun;
    char line[256];
    char cmd[128];
    int fd;
    int n;

    if (path == NULL || nonce == NULL)
        return 42;
    fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0)
        return 42;
    memset(&sun, 0, sizeof sun);
    sun.sun_family = AF_UNIX;
    if (strlen(path) >= sizeof sun.sun_path) {
        close(fd);
        return 42;
    }
    strcpy(sun.sun_path, path);
    if (connect(fd, (struct sockaddr *)&sun, sizeof sun) != 0) {
        close(fd);
        return 42;
    }
    n = snprintf(cmd, sizeof cmd, "DEALPG4 HELLO %s\n", nonce);
    if (n <= 0 || (size_t)n >= sizeof cmd
        || write(fd, cmd, (size_t)n) != (ssize_t)n)
        return 42;
    {
        size_t off = 0;

        for (;;) {
            ssize_t r = read(fd, line + off, sizeof line - off - 1);

            if (r <= 0)
                return 42;
            off += (size_t)r;
            line[off] = '\0';
            if (memchr(line, '\n', off) != NULL)
                break;
            if (off >= sizeof line - 1)
                return 42;
        }
    }
    if (memcmp(line, "DEALPG4 HELLO_OK 4 63", 21) != 0)
        return 42;
    n = snprintf(cmd, sizeof cmd, "DEALPG4 FEATURE_READY %s\n", nonce);
    if (n <= 0 || (size_t)n >= sizeof cmd
        || write(fd, cmd, (size_t)n) != (ssize_t)n)
        return 42;
    {
        size_t off = 0;

        for (;;) {
            ssize_t r = read(fd, line + off, sizeof line - off - 1);

            if (r <= 0)
                return 42;
            off += (size_t)r;
            line[off] = '\0';
            if (memchr(line, '\n', off) != NULL)
                break;
            if (off >= sizeof line - 1)
                return 42;
        }
    }
    if (memcmp(line, "DEALPG4 READY_ACK ", 18) != 0)
        return 42;
    close(fd);
    /* Trapped TERM, then hang: the escalation TERM -pgid is ignored,
     * the grace passes, the re-verified KILL -pgid reaps this peer. */
    (void)signal(SIGTERM, SIG_IGN);
    for (;;)
        pause();
}

/* === Group 10: drain-pipe blocking semantics =========================== */

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

/* The suite binary re-execs itself as the coordinator probe: the
 * probe reports the F_GETFL flag words of its fds 1/2 (the line lands
 * in the drain the outer retains) and exits nonzero when either
 * carries O_NONBLOCK — the regression trigger for the pinned
 * stream-topology contract (the coordinator's stdout/stderr write
 * ends keep ordinary blocking semantics; only the drain read ends are
 * O_NONBLOCK). */
static int pipe_flags_probe_fn(void)
{
    char *argv[3];
    char report[1024];
    dealpg4_outer_result view;
    const dealpg4_drain_ctx *dout = NULL;
    const dealpg4_drain_ctx *derr = NULL;
    const char *line;
    char probe[128];
    size_t avail;
    int f1 = -1;
    int f2 = -1;
    uint64_t before;
    uint64_t after;
    int status;

    argv[0] = (char *)g_suite_argv0;
    argv[1] = (char *)"--coord-pipe-flags-probe";
    argv[2] = NULL;

    before = dealpg4_now_ms();
    status = core_with_report(&SCALED, argv, report, sizeof report);
    after = dealpg4_now_ms();

    /* The probe exits 0 iff fds 1/2 are blocking: the clean exit
     * holds only on the pinned topology. */
    CHECK(status == 0);
    CHECK(after - before < 8000);

    memset(&view, 0, sizeof view);
    dealpg4_outer_last_result(&view);
    CHECK(view.gate_failure == 0);
    CHECK(view.coordinator_exited_0 == 1);
    CHECK(view.coordinator_si_status == 0);
    CHECK(view.proof_passed == 1);
    CHECK(view.escalation_term_issued == 0); /* never signaled */

    /* Parse the flag words back out of the retained stdout and
     * assert the blocking semantics directly (the structural
     * regression check). */
    dealpg4_outer_drain_state(&dout, &derr);
    CHECK(dout != NULL && derr != NULL);
    CHECK(dout->eof == 1 && derr->eof == 1);
    CHECK(dout->failed == 0 && derr->failed == 0);
    line = find_in_retained(dout, "PROBE stdout_flags=");
    CHECK(line != NULL);
    if (line != NULL) {
        avail = dout->retained_len
                - (size_t)(line - (const char *)dout->retained);
        if (avail < sizeof probe) {
            memcpy(probe, line, avail);
            probe[avail] = '\0';
            CHECK(sscanf(probe,
                         "PROBE stdout_flags=%d stderr_flags=%d",
                         &f1, &f2) == 2);
            CHECK(f1 >= 0 && f2 >= 0);
            CHECK((f1 & O_NONBLOCK) == 0);
            CHECK((f2 & O_NONBLOCK) == 0);
        } else {
            CHECK(0); /* the probe line outgrew the view */
        }
    }
    /* stderr stayed empty (no failure output). */
    CHECK(derr->total_read == 0);

    check_no_children();
    CHECK(outer_socket_path_count("build") == 0);
    return (g_failures > 0) ? 1 : 0;
}

/* === Main ============================================================== */

int main(int argc, char **argv)
{
    char errbuf[1024];
    int status;

    g_suite_argv0 = argv[0];
    if (argc >= 2 && strcmp(argv[1], "--hang-peer") == 0)
        return hang_peer_entry();
    if (argc >= 2 && strcmp(argv[1], "--coord-pipe-flags-probe") == 0) {
        /* Coordinator probe (group 10 re-execs the suite binary as
         * the scripted coordinator): report the F_GETFL flag words of
         * fds 1/2 — the line lands in the outer's retained drain —
         * and exit nonzero when either fd is O_NONBLOCK (the
         * stream-topology regression trigger: the coordinator's
         * stdout/stderr must keep ordinary blocking semantics). */
        int f1 = fcntl(STDOUT_FILENO, F_GETFL);
        int f2 = fcntl(STDERR_FILENO, F_GETFL);

        printf("PROBE stdout_flags=%d stderr_flags=%d\n", f1, f2);
        fflush(stdout);
        if (f1 == -1 || f2 == -1 || (f1 & O_NONBLOCK)
            || (f2 & O_NONBLOCK))
            return 42;
        return 0;
    }

    /* Group 1: success bootstrap. */
    errbuf[0] = '\0';
    if (run_capture_child(success_bootstrap_fn, errbuf, sizeof errbuf,
                          &status) != 0) {
        fprintf(stderr, "FAIL: helper child machinery broke\n");
        return 1;
    }
    gate_group("success bootstrap", errbuf, status);

    /* Group 2: exec failure. */
    errbuf[0] = '\0';
    if (run_capture_child(exec_failure_fn, errbuf, sizeof errbuf,
                          &status) != 0) {
        fprintf(stderr, "FAIL: helper child machinery broke\n");
        return 1;
    }
    gate_group("exec failure", errbuf, status);

    /* Group 3: readiness bound + by-pid escalation (~2.5 s). */
    errbuf[0] = '\0';
    if (run_capture_child(readiness_bound_fn, errbuf, sizeof errbuf,
                          &status) != 0) {
        fprintf(stderr, "FAIL: helper child machinery broke\n");
        return 1;
    }
    gate_group("readiness bound", errbuf, status);

    /* Group 4: COORDINATOR_HANG escalation (~7.2 s). */
    errbuf[0] = '\0';
    if (run_capture_child(hang_escalation_fn, errbuf, sizeof errbuf,
                          &status) != 0) {
        fprintf(stderr, "FAIL: helper child machinery broke\n");
        return 1;
    }
    gate_group("COORDINATOR_HANG escalation", errbuf, status);

    /* Group 5: output flood (~5.1 s; the TERM-death path). */
    errbuf[0] = '\0';
    if (run_capture_child(output_flood_fn, errbuf, sizeof errbuf,
                          &status) != 0) {
        fprintf(stderr, "FAIL: helper child machinery broke\n");
        return 1;
    }
    gate_group("output flood", errbuf, status);

    /* Group 6: shell loss — closed report stdout. */
    errbuf[0] = '\0';
    if (run_capture_child(shell_loss_report_fn, errbuf, sizeof errbuf,
                          &status) != 0) {
        fprintf(stderr, "FAIL: helper child machinery broke\n");
        return 1;
    }
    gate_group("shell loss (closed report stdout)", errbuf, status);

    /* Group 7: shell loss — orphaned outer (the grandchild result
     * line travels over a pipe; the orphaned grandchild reparents
     * away and is reaped by its new parent). Its assertions run in
     * the parent and count against the final gate directly. */
    if (shell_loss_orphan_case() != 0) {
        fprintf(stderr, "FAIL: orphan case machinery broke\n");
        return 1;
    }

    /* Group 8: FI_COORD_READY_MISMATCH. */
    errbuf[0] = '\0';
    if (run_capture_child(ready_mismatch_fn, errbuf, sizeof errbuf,
                          &status) != 0) {
        fprintf(stderr, "FAIL: helper child machinery broke\n");
        return 1;
    }
    gate_group("FI_COORD_READY_MISMATCH", errbuf, status);

    /* Group 9: FI_OUTER_PIPE. */
    errbuf[0] = '\0';
    if (run_capture_child(outer_pipe_failure_fn, errbuf, sizeof errbuf,
                          &status) != 0) {
        fprintf(stderr, "FAIL: helper child machinery broke\n");
        return 1;
    }
    gate_group("FI_OUTER_PIPE", errbuf, status);

    /* Group 10: drain-pipe blocking semantics (the re-exec probe). */
    errbuf[0] = '\0';
    if (run_capture_child(pipe_flags_probe_fn, errbuf, sizeof errbuf,
                          &status) != 0) {
        fprintf(stderr, "FAIL: helper child machinery broke\n");
        return 1;
    }
    gate_group("drain-pipe blocking semantics", errbuf, status);

    if (g_failures > 0) {
        fprintf(stderr, "outer-coordinator-tests: %d/%d checks failed\n",
                g_failures, g_checks);
        return 1;
    }
    printf("outer-coordinator-tests: %d checks passed\n", g_checks);
    return 0;
}
