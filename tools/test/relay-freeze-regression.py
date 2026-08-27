#!/usr/bin/env python3
# Regression harness: serve-mode stream relay freezes at the terminal
# classification (ISSUE-0244, review finding 1).
#
# Trigger: budget 15000 (T4=T0+12000, T5=T0+15000); the target writes
# > 1 MiB to stdout, donates its stdout write end via SCM_RIGHTS to the
# harness parent (a non-descendant peer — never a group/session member,
# never an adopted descendant), then exits 0. The control peer sends the
# matching ACK and then stops reading the control channel after STARTED
# (the write-stall keeps the per-record queue non-empty, so the
# supervisor stays alive in TERMINAL past T4). At T4 the stdout drain
# has not reached read-side EOF -> DRAIN_FAILED: REPORT + FAILED queue
# at the terminal classification without the stdout stream's OUT_END.
# The peer closes the donated write end at t~13s (after T4, before T5):
# the late stream EOF must NOT queue OUT chunks or the previously
# withheld OUT_END behind the already-queued REPORT and terminal
# record. Asserts:
#   - exactly one REPORT with failureToken DRAIN_FAILED, drainEof 0,
#     stdoutTruncated 1 (the drain-cap marker), stdoutBytes >= 1 MiB;
#   - exactly one terminal FAILED <id> DRAIN_FAILED after REPORT;
#   - no OUT/OUT_END record ever received after REPORT;
#   - serve exit 2 and control-channel EOF.
#
# Pre-fix, the buggy relay queues "OUT_END <id> out" after the FAILED
# record (the late EOF pump). Post-fix the relayed chunk sequence for
# the incomplete stream simply ends at the terminal classification.
#
# Runs from the repository root: tools/test/relay-freeze-regression.py.
import os
import select
import socket
import struct
import sys
import time

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(
    os.path.abspath(__file__))))
LAUNCHER = os.path.join(REPO_ROOT,
                        "tools/deal-process-launcher-linux-x86_64")
PYTHON = "/usr/bin/python3"
NONCE = "ab" * 16
INV_ID = 7
BUDGET_MS = 15000          # T4 = T0 + 12000, T5 = T0 + 15000
CLOSE_DONATED_AT = 13.0    # after T4, before T5
STALL_UNTIL = 13.15        # resume control-channel reads after the close
HARD_BOUND = 30.0

TARGET = r'''
import socket, struct, sys
chunk = b"x" * 32768
for _ in range(33):
    sys.stdout.buffer.write(chunk)
    sys.stdout.buffer.flush()
sock = socket.socket(fileno=3)
sock.sendmsg([b"fd"], [(socket.SOL_SOCKET, socket.SCM_RIGHTS,
                        struct.pack("i", 1))])
sys.exit(0)
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


def main():
    if not os.path.isfile(LAUNCHER):
        return fail("launcher binary missing at %s (build it first)"
                    % LAUNCHER)
    if not os.path.isfile(PYTHON):
        return fail("%s missing" % PYTHON)

    ctrl_parent, ctrl_child = socket.socketpair()
    don_parent, don_child = socket.socketpair()
    ctrl_parent.setblocking(False)
    don_parent.setblocking(False)
    os.set_inheritable(ctrl_child.fileno(), True)
    os.set_inheritable(don_child.fileno(), True)

    env = dict(os.environ)
    env["DEALPG4_BUDGET_MS"] = str(BUDGET_MS)
    env["DEALPG4_NONCE"] = NONCE
    env["DEALPG4_INVOCATION_ID"] = str(INV_ID)

    devnull = os.open(os.devnull, os.O_RDWR)
    actions = [
        (os.POSIX_SPAWN_DUP2, ctrl_child.fileno(), 0),
        (os.POSIX_SPAWN_DUP2, don_child.fileno(), 3),
        (os.POSIX_SPAWN_DUP2, devnull, 1),
        (os.POSIX_SPAWN_DUP2, devnull, 2),
        (os.POSIX_SPAWN_CLOSE, ctrl_child.fileno()),
        (os.POSIX_SPAWN_CLOSE, don_child.fileno()),
        (os.POSIX_SPAWN_CLOSE, devnull),
    ]

    t0 = time.monotonic()
    pid = os.posix_spawn(
        LAUNCHER,
        [LAUNCHER, "serve", ".", "--", PYTHON, "-c", TARGET],
        env, file_actions=actions)
    os.close(devnull)
    ctrl_child.close()
    don_child.close()

    reader = LineReader(ctrl_parent)
    records = []
    donated = None
    acked = False
    stalled_until = None
    eof = False
    close_donated_at = None
    keep = t0 + HARD_BOUND

    while True:
        now = time.monotonic()
        if now >= keep:
            os.kill(pid, 9)
            os.waitpid(pid, 0)
            return fail("hard bound hit (harness hung)")
        rd = [don_parent]
        if stalled_until is None or now >= stalled_until:
            rd.append(ctrl_parent)
        r, _, _ = select.select(rd, [], [], 0.05)

        if don_parent in r:
            data, anc, _, _ = don_parent.recvmsg(4096, socket.CMSG_LEN(4))
            for level, ctype, cdata in anc:
                if level == socket.SOL_SOCKET and ctype == socket.SCM_RIGHTS:
                    fd = struct.unpack("i", cdata)[0]
                    os.set_blocking(fd, False)
                    donated = fd
                    close_donated_at = t0 + CLOSE_DONATED_AT

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
            ctrl_parent.sendall(
                ("DEALPG4 ACK %d %s\n" % (INV_ID, NONCE)).encode())
            acked = True
        if stalled_until is None and any(r.startswith("DEALPG4 STARTED ")
                                         for r in records):
            stalled_until = t0 + STALL_UNTIL
        if close_donated_at is not None and donated is not None \
                and now >= close_donated_at:
            os.close(donated)
            donated = None

    _, status = os.waitpid(pid, 0)
    exit_code = os.waitstatus_to_exitcode(status)

    report_idx = None
    failed_idx = None
    for i, r in enumerate(records):
        if r.startswith("DEALPG4 REPORT "):
            report_idx = i
        if r.startswith("DEALPG4 FAILED ") or r.startswith("DEALPG4 CLEAN "):
            failed_idx = i

    if close_donated_at is None:
        return fail("target never donated its stdout write end")
    if report_idx is None:
        return fail("no REPORT observed")
    if failed_idx is None or failed_idx <= report_idx:
        return fail("no terminal record after REPORT")
    if not eof:
        return fail("control channel never reached EOF")
    if exit_code != 2:
        return fail("serve exit %d, expected 2" % exit_code)

    # The terminal record must be the DRAIN_FAILED FAILED record.
    terminal = records[failed_idx]
    if not (terminal.startswith("DEALPG4 FAILED ")
            and terminal.endswith(" DRAIN_FAILED")):
        return fail("terminal record %r, expected FAILED DRAIN_FAILED"
                    % terminal)

    # REPORT: 19 fields, failureToken DRAIN_FAILED, drainEof 0,
    # stdoutTruncated 1 (drain-cap marker), stdoutBytes >= 1 MiB.
    # Field layout: DEALPG4 REPORT exitCode termSignal elapsedMs
    # startupMs execMs termMs killMs proofMs finalMs reapCount adoptCount
    # stdoutBytes stderrBytes stdoutTruncated stderrTruncated groupProof
    # sessionProof drainEof failureToken  (19 fields).
    rparts = records[report_idx].split(" ")
    if len(rparts) != 21:
        return fail("REPORT field count %d, expected 19"
                    % (len(rparts) - 2))
    if rparts[20] != "DRAIN_FAILED":
        return fail("REPORT failureToken %s, expected DRAIN_FAILED"
                    % rparts[20])
    if rparts[19] != "0":
        return fail("REPORT drainEof %s, expected 0" % rparts[19])
    if int(rparts[13]) < 1048576:
        return fail("REPORT stdoutBytes %s, expected >= 1048576"
                    % rparts[13])
    if rparts[15] != "1":
        return fail("REPORT stdoutTruncated %s, expected 1" % rparts[15])

    # No OUT/OUT_END may ever follow REPORT (the freeze under test).
    late = [r for r in records[report_idx + 1:]
            if r.startswith(("DEALPG4 OUT ", "DEALPG4 OUT_END "))]
    if late:
        return fail("stream records queued after REPORT: %r" % late)

    # The stderr stream reached EOF at target exit, so its OUT_END must
    # precede REPORT; the incomplete stdout stream must have none.
    if not any(r.startswith("DEALPG4 OUT_END %d err" % INV_ID)
               for r in records[:report_idx]):
        return fail("OUT_END err missing before REPORT")
    if any(r.startswith("DEALPG4 OUT_END %d out" % INV_ID)
           for r in records):
        return fail("OUT_END out present despite the incomplete stream")

    print("REGRESSION PASS: relay frozen at the terminal classification —"
          " REPORT/FAILED DRAIN_FAILED with no stream record after REPORT,"
          " serve exit 2, drainEof 0, stdoutTruncated 1")
    return 0


if __name__ == "__main__":
    sys.exit(main())
