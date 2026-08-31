#!/usr/bin/env python3
# Regression harness: deadline-escalation catch-up across a stalled
# supervisor (ISSUE-0436 remediation — ISSUE-0180 acceptance review
# finding, the T2->T4 hop skip).
#
# Trigger: budget 15000 (T1=T2=T0+5000, T3=T0+7000, T4=T0+12000,
# T5=T0+15000). The target is a TERM-immune python pair (root + forked
# background child in the same group/session, both sleeping past T5) —
# only SIGKILL can remove them. The harness ACKs and releases normally,
# then SIGSTOPs the supervisor before T2 and SIGCONTs it after the
# deadline window:
#   scenario T4: CONT at T0+12500 (now in [T4, T5)) — the catch-up must
#     issue the T2 TERM and the T3 KILL, then run the T4 proof-deadline
#     classification;
#   scenario T5: CONT at T0+15500 (now >= T5) — the catch-up must issue
#     the TERM/KILL escalation, reap the killed tree, and complete the
#     proof before the supervisor exits.
#
# Pre-fix, the descending T5->T4->T3->T2->T1 checks with early returns
# skip the escalation when the loop resumes past T4: REPORT records
# OVERALL_TIMEOUT with termMs/killMs = 0 and the supervisor exits while
# the background child (which survived the root's PDEATHSIG-only death)
# is still alive in the target group — the zero-survivor post-state is
# violated. Post-fix every deadline boundary crossed in one hop is
# applied in ascending order, the REPORT carries the recorded
# termMs/killMs, and no process remains in the target group/session
# after the supervisor exits.
#
# Assertions per scenario:
#   - exactly one REPORT with failureToken per the landing (T4: one of
#     the T4 proof-deadline tokens, never OVERALL_TIMEOUT; T5:
#     OVERALL_TIMEOUT), drainEof 0, termMs != 0, killMs != 0, and the
#     reaped-status convention (exitCode = 128 + termSignal);
#   - exactly one terminal FAILED <id> <same token> after REPORT;
#   - serve exit 2 and control-channel EOF;
#   - zero survivors: no /proc task with the target pgid or session
#     after the supervisor exits (the harness is a subreaper and reaps
#     every reparented child, so the check is deterministic).
#
# Runs from the repository root: tools/test/escalation-catchup-regression.py.
import ctypes
import os
import select
import signal
import socket
import sys
import time

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(
    os.path.abspath(__file__))))
LAUNCHER = os.path.join(REPO_ROOT,
                        "tools/deal-process-launcher-linux-x86_64")
PYTHON = "/usr/bin/python3"
NONCE = "cd" * 16
INV_ID = 9
BUDGET_MS = 15000          # T1=T2=T0+5000, T3=T0+7000, T4=T0+12000, T5=T0+15000
STOP_AT = 4.0              # after STARTED, before T2
CONT_AT_T4 = 12.5          # in [T4, T5)
CONT_AT_T5 = 15.5          # past T5
HARD_BOUND = 40.0
SURVIVOR_GRACE = 5.0

T4_TOKENS = {
    "PROOF_TIMEOUT", "GROUP_SURVIVOR", "SESSION_SURVIVOR",
    "ADOPTED_SURVIVOR", "ZOMBIE_SURVIVOR", "DRAIN_FAILED",
}

TARGET = r'''
import os, signal, time
signal.signal(signal.SIGTERM, signal.SIG_IGN)
pid = os.fork()
if pid == 0:
    while True:
        time.sleep(30)
else:
    while True:
        time.sleep(30)
'''


class LineReader:
    def __init__(self, sock):
        self.sock = sock
        self.buf = b""

    def feed(self, chunk):
        self.buf += chunk

    def take(self):
        lines = []
        while b"\n" in self.buf:
            line, self.buf = self.buf.split(b"\n", 1)
            lines.append(line.decode("utf-8", "replace"))
        return lines


def fail(msg):
    print("REGRESSION FAIL:", msg, file=sys.stderr)
    return 1


def proc_stat_identity(pid):
    try:
        with open("/proc/%d/stat" % pid, "rb") as f:
            data = f.read().decode("utf-8", "replace")
    except OSError:
        return None
    close = data.rfind(")")
    if close < 0:
        return None
    parts = data[close + 1:].split()
    if len(parts) < 4:
        return None
    # parts[0]=state, parts[1]=ppid, parts[2]=pgrp, parts[3]=session
    return parts[0], int(parts[1]), int(parts[2]), int(parts[3])


def supervisor_stopped(pid):
    r = proc_stat_identity(pid)
    return r is not None and r[0] == "T"


def scan_survivors(pgid, sid):
    found = []
    try:
        entries = os.listdir("/proc")
    except OSError:
        return found
    for ent in entries:
        if not ent.isdigit():
            continue
        pid = int(ent)
        if pid == os.getpid():
            continue
        r = proc_stat_identity(pid)
        if r is None:
            continue
        if r[2] == pgid or r[3] == sid:
            found.append(pid)
    return found


def reap_round_safe():
    try:
        while True:
            try:
                pid, _ = os.waitpid(-1, os.WNOHANG)
            except ChildProcessError:
                return
            if pid == 0:
                return
    except OSError:
        return


def run_scenario(cont_at, label):
    ctrl_parent, ctrl_child = socket.socketpair()
    ctrl_parent.setblocking(False)
    os.set_inheritable(ctrl_child.fileno(), True)

    env = dict(os.environ)
    env["DEALPG4_BUDGET_MS"] = str(BUDGET_MS)
    env["DEALPG4_NONCE"] = NONCE
    env["DEALPG4_INVOCATION_ID"] = str(INV_ID)

    devnull = os.open(os.devnull, os.O_RDWR)
    actions = [
        (os.POSIX_SPAWN_DUP2, ctrl_child.fileno(), 0),
        (os.POSIX_SPAWN_DUP2, devnull, 1),
        (os.POSIX_SPAWN_DUP2, devnull, 2),
        (os.POSIX_SPAWN_CLOSE, ctrl_child.fileno()),
        (os.POSIX_SPAWN_CLOSE, devnull),
    ]

    t0 = time.monotonic()
    pid = os.posix_spawn(
        LAUNCHER,
        [LAUNCHER, "serve", ".", "--", PYTHON, "-c", TARGET],
        env, file_actions=actions)
    os.close(devnull)
    ctrl_child.close()

    reader = LineReader(ctrl_parent)
    records = []
    acked = False
    stopped = False
    continued = False
    eof = False
    keep = t0 + HARD_BOUND
    stub_pgid = None
    stub_sid = None

    while True:
        now = time.monotonic()
        if now >= keep:
            os.kill(pid, signal.SIGKILL)
            os.waitpid(pid, 0)
            return fail("[%s] hard bound hit (harness hung)" % label)
        r, _, _ = select.select([ctrl_parent], [], [], 0.05)
        if ctrl_parent in r:
            try:
                chunk = ctrl_parent.recv(65536)
            except BlockingIOError:
                chunk = None
            if chunk:
                reader.feed(chunk)
            else:
                eof = True
                break
        records.extend(reader.take())

        if not acked and any(r.startswith("DEALPG4 STUB_READY ")
                             for r in records):
            for r in records:
                if r.startswith("DEALPG4 STUB_READY "):
                    parts = r.split(" ")
                    # STUB_READY <id> <stubPid> <pgid> <sid> <nonce>
                    if len(parts) == 7:
                        stub_pgid = int(parts[4])
                        stub_sid = int(parts[5])
            ctrl_parent.sendall(
                ("DEALPG4 ACK %d %s\n" % (INV_ID, NONCE)).encode())
            acked = True
        if not stopped and any(r.startswith("DEALPG4 STARTED ")
                               for r in records) and now >= t0 + STOP_AT:
            if now > t0 + STOP_AT + 0.3:
                return fail("[%s] SIGSTOP missed its window (harness "
                            "lag)" % label)
            os.kill(pid, signal.SIGSTOP)
            stopped = True
            for _ in range(100):
                if supervisor_stopped(pid):
                    break
                time.sleep(0.01)
            if not supervisor_stopped(pid):
                os.kill(pid, signal.SIGKILL)
                os.waitpid(pid, 0)
                return fail("[%s] supervisor did not stop" % label)
        if stopped and not continued and now >= t0 + cont_at:
            os.kill(pid, signal.SIGCONT)
            continued = True

    if not stopped or not continued:
        if pid > 0:
            os.kill(pid, signal.SIGKILL)
            os.waitpid(pid, 0)
        return fail("[%s] harness lost the stop/continue window" % label)
    _, status = os.waitpid(pid, 0)
    exit_code = os.waitstatus_to_exitcode(status)

    if stub_pgid is None:
        return fail("[%s] no STUB_READY pgid parsed" % label)
    if not eof:
        return fail("[%s] control channel never reached EOF" % label)
    if exit_code != 2:
        return fail("[%s] serve exit %d, expected 2" % (label, exit_code))

    report_idx = None
    terminal_idx = None
    for i, r in enumerate(records):
        if r.startswith("DEALPG4 REPORT "):
            if report_idx is not None:
                return fail("[%s] more than one REPORT" % label)
            report_idx = i
        if r.startswith("DEALPG4 FAILED ") or r.startswith("DEALPG4 CLEAN "):
            terminal_idx = i
    if report_idx is None:
        return fail("[%s] no REPORT observed" % label)
    if terminal_idx is None or terminal_idx <= report_idx:
        return fail("[%s] no terminal record after REPORT" % label)

    # REPORT fields: DEALPG4 REPORT exitCode(2) termSignal(3)
    # elapsedMs(4) startupMs(5) execMs(6) termMs(7) killMs(8) proofMs(9)
    # finalMs(10) reapCount(11) adoptCount(12) stdoutBytes(13)
    # stderrBytes(14) stdoutTruncated(15) stderrTruncated(16)
    # groupProof(17) sessionProof(18) drainEof(19) failureToken(20).
    rparts = records[report_idx].split(" ")
    if len(rparts) != 21:
        return fail("[%s] REPORT field count %d, expected 19"
                    % (label, len(rparts) - 2))
    token = rparts[20]
    term_ms = int(rparts[7])
    kill_ms = int(rparts[8])
    elapsed_ms = int(rparts[4])
    exit_code_field = int(rparts[2])
    term_signal = int(rparts[3])

    if label == "T5":
        if token != "OVERALL_TIMEOUT":
            return fail("[T5] REPORT failureToken %s, expected "
                        "OVERALL_TIMEOUT" % token)
        if elapsed_ms < 15000:
            return fail("[T5] REPORT elapsedMs %d, expected >= 15000"
                        % elapsed_ms)
    else:
        if token not in T4_TOKENS:
            return fail("[T4] REPORT failureToken %s, expected one of %s"
                        % (token, sorted(T4_TOKENS)))
        if elapsed_ms < 12000 or elapsed_ms >= 15000:
            return fail("[T4] REPORT elapsedMs %d, expected in [12000, "
                        "15000)" % elapsed_ms)
    if term_ms <= 0:
        return fail("[%s] REPORT termMs %d, expected > 0 (the T2 TERM "
                    "must have been caught up)" % (label, term_ms))
    if kill_ms <= 0:
        return fail("[%s] REPORT killMs %d, expected > 0 (the T3 KILL "
                    "must have been caught up)" % (label, kill_ms))
    if kill_ms < term_ms:
        return fail("[%s] REPORT killMs %d < termMs %d"
                    % (label, kill_ms, term_ms))
    if rparts[19] != "0":
        return fail("[%s] REPORT drainEof %s, expected 0" % (label,
                                                             rparts[19]))
    if term_signal not in (9, 15):
        return fail("[%s] REPORT termSignal %d, expected 9 or 15"
                    % (label, term_signal))
    if exit_code_field != 128 + term_signal:
        return fail("[%s] REPORT exitCode %d != 128 + termSignal %d"
                    % (label, exit_code_field, term_signal))
    if label == "T5":
        if rparts[17] != "1" or rparts[18] != "1":
            return fail("[T5] REPORT groupProof/sessionProof %s/%s, "
                        "expected 1/1 (the proof must complete before "
                        "the exit)" % (rparts[17], rparts[18]))

    terminal = records[terminal_idx]
    if not terminal.startswith("DEALPG4 FAILED "):
        return fail("[%s] terminal record %r, expected FAILED"
                    % (label, terminal))
    if not terminal.endswith(" " + token):
        return fail("[%s] terminal record %r, expected token %s"
                    % (label, terminal, token))

    # Zero survivors: after the supervisor exits, no task may remain in
    # the target group/session. The harness is a subreaper, so every
    # reparented child is reaped here and the scan is deterministic.
    deadline = time.monotonic() + SURVIVOR_GRACE
    survivors = scan_survivors(stub_pgid, stub_sid)
    while survivors and time.monotonic() < deadline:
        reap_round_safe()
        time.sleep(0.05)
        survivors = scan_survivors(stub_pgid, stub_sid)
    reap_round_safe()
    if survivors:
        try:
            os.killpg(stub_pgid, signal.SIGKILL)
        except (ProcessLookupError, PermissionError):
            pass
        reap_round_safe()
        return fail("[%s] target group/session survivors after the "
                    "supervisor exited: %r" % (label, survivors))

    print("REGRESSION PASS [%s]: escalation caught up — REPORT %s with "
          "termMs %d killMs %d, drainEof 0, serve exit 2, zero "
          "survivors" % (label, token, term_ms, kill_ms))
    return 0


def main():
    if not os.path.isfile(LAUNCHER):
        return fail("launcher binary missing at %s (build it first)"
                    % LAUNCHER)
    if not os.path.isfile(PYTHON):
        return fail("%s missing" % PYTHON)

    # The harness becomes a subreaper so every process reparented when
    # the supervisor exits is adopted here and deterministically
    # reaped (PR_SET_CHILD_SUBREAPER = 36 on linux-x86_64).
    libc = ctypes.CDLL("libc.so.6", use_errno=True)
    if libc.prctl(36, 1) != 0:
        return fail("harness prctl(PR_SET_CHILD_SUBREAPER) failed")

    rc = run_scenario(CONT_AT_T4, "T4")
    if rc != 0:
        return rc
    rc = run_scenario(CONT_AT_T5, "T5")
    if rc != 0:
        return rc
    return 0


if __name__ == "__main__":
    sys.exit(main())
