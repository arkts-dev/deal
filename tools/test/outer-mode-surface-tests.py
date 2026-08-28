#!/usr/bin/env python3
# Outer mode-surface usage tests (ISSUE-0293 Verification).
#
# Drives the committed launcher binary at the process level: every argv
# shape error — a missing "--", an empty coordinator argv, and
# malformed nonces (wrong length, non-hex, uppercase) — exits 2 with
# the usage message on stderr, writes nothing to stdout, creates no
# .dealpg4-broker-* socket path under build/, and returns immediately
# (a usage error forks nothing and waits for nothing; the elapsed-time
# bound is the observable). The valid shape is exercised by the
# component suite under scaled limits — at the process level the valid
# shape runs the embedded 15-minute stage, which is out of scope here.
#
# Runs from tools/ (the runner script re-locates): the launcher CWD is
# the repository root so the socket-dir pin ("build") matches the real
# mode surface.
import glob
import os
import subprocess
import sys
import time

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(
    os.path.abspath(__file__))))
LAUNCHER = os.path.join(REPO_ROOT,
                        "tools/deal-process-launcher-linux-x86_64")

NONCE = "ab" * 16
CASES = [
    (["outer"], "missing nonce"),
    (["outer", NONCE], "missing --"),
    (["outer", NONCE, "/bin/true"], "argv[3] is not --"),
    (["outer", NONCE, "--"], "empty coordinator argv"),
    (["outer", "a" * 31, "--", "/bin/true"], "31-char nonce"),
    (["outer", "a" * 33, "--", "/bin/true"], "33-char nonce"),
    (["outer", "z" * 32, "--", "/bin/true"], "non-hex nonce"),
    (["outer", "A" * 32, "--", "/bin/true"], "uppercase nonce"),
]


def fail(msg):
    print("OUTER-MODE-SURFACE FAIL:", msg, file=sys.stderr)
    return 1


def socket_paths():
    return glob.glob(os.path.join(REPO_ROOT, "build",
                                  ".dealpg4-broker-*"))


def main():
    if not os.path.isfile(LAUNCHER):
        return fail("launcher binary missing at %s (build it first)"
                    % LAUNCHER)
    before = socket_paths()
    if before:
        return fail("pre-existing broker socket paths: %r" % before)

    for argv, label in CASES:
        t0 = time.monotonic()
        try:
            proc = subprocess.run(
                [LAUNCHER] + argv,
                stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                cwd=REPO_ROOT, timeout=30)
        except subprocess.TimeoutExpired:
            return fail("%r hung past the process-level bound (%s)"
                        % (argv, label))
        elapsed = time.monotonic() - t0

        if proc.returncode != 2:
            return fail("%r exit %d, expected 2 (%s)"
                        % (argv, proc.returncode, label))
        if b"usage:" not in proc.stderr:
            return fail("%r missing the usage message on stderr (%s)"
                        % (argv, label))
        if proc.stdout:
            return fail("%r wrote to stdout (%s)" % (argv, label))
        if elapsed > 5.0:
            return fail("%r took %.2fs — a usage error must return "
                        "immediately (%s)" % (argv, elapsed, label))

    after = socket_paths()
    if after:
        return fail("broker socket paths appeared after usage errors: %r"
                    % after)

    print("OUTER-MODE-SURFACE PASS: %d usage cases — exit 2, usage "
          "message on stderr, no socket path, immediate return"
          % len(CASES))
    return 0


if __name__ == "__main__":
    sys.exit(main())
