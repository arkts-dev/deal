/*
 * DEALPG4 launcher entry point: mode dispatch.
 *
 * Owns (dealpg4-launcher-core D1/D3, mode dispatch contract):
 *  - recognition of exactly the five mode names (probe|run|serve|outer|
 *    selftest); no mode or any other mode name is an invocation error
 *    (usage message on stderr, nonzero exit — not an integrity token),
 *  - dispatch-owned argument validation: probe/selftest accept at most
 *    the optional --limit-ms N shape with N a decimal integer >=
 *    DEALPG4_PROBE_SELFTEST_LIMIT_MS_FLOOR; unknown flags, non-integer
 *    N, a missing value, a repeated option, or N below the floor is a
 *    usage error on stderr with a nonzero exit. --limit-ms is the only
 *    parsed option in the entire binary; run/serve/outer argument shapes
 *    are defined by the supervisor/outer children, so dispatch performs
 *    no argument interpretation for them and passes argc/argv through
 *    unchanged,
 *  - the mode-entry embedded-limits ordering validation: before
 *    delegating to ANY mode, dispatch runs the monotonic validator; a
 *    violation prints CONFIG_INVALID to stderr and exits nonzero before
 *    any mode code runs (no mode output, no fork) — for every one of the
 *    five mode names, and
 *  - delegation to the per-mode entry functions: dispatch owns no
 *    operational behavior, creates no process/session/channel, and
 *    performs no wait (single-shot, stateless).
 */
#include <stdint.h>
#include <stdio.h>
#include <string.h>

#include "monotonic.h"
#include "outer.h"
#include "selftest.h"
#include "supervisor.h"

/* Dispatch exit statuses. The mode entries own their own statuses; the
 * usage status covers the dispatch-owned invocation failures, and the
 * CONFIG_INVALID status (selftest.h) is shared with the probe entry's
 * mode-entry limits re-check. Neither value is an integrity token. */
#define DEALPG4_EXIT_USAGE 2

static void dealpg4_print_usage(FILE *out)
{
    fprintf(out,
            "usage: tools/deal-process-launcher-linux-x86_64 <mode> [args]\n"
            "modes: probe | run | serve | outer | selftest\n"
            "probe/selftest: at most one optional --limit-ms N "
            "(integer N >= 15000)\n");
}

enum dealpg4_mode {
    DEALPG4_MODE_PROBE,
    DEALPG4_MODE_RUN,
    DEALPG4_MODE_SERVE,
    DEALPG4_MODE_OUTER,
    DEALPG4_MODE_SELFTEST,
    DEALPG4_MODE_UNKNOWN
};

/* Recognize exactly the five canonical mode names; everything else is
 * unknown. */
static enum dealpg4_mode dealpg4_recognize_mode(const char *name)
{
    if (strcmp(name, "probe") == 0)
        return DEALPG4_MODE_PROBE;
    if (strcmp(name, "run") == 0)
        return DEALPG4_MODE_RUN;
    if (strcmp(name, "serve") == 0)
        return DEALPG4_MODE_SERVE;
    if (strcmp(name, "outer") == 0)
        return DEALPG4_MODE_OUTER;
    if (strcmp(name, "selftest") == 0)
        return DEALPG4_MODE_SELFTEST;
    return DEALPG4_MODE_UNKNOWN;
}

/* Parse the probe/selftest argument shape: at most one optional
 * "--limit-ms N" with N a decimal integer (ASCII digits only, no sign)
 * that fits int64 and is >= DEALPG4_PROBE_SELFTEST_LIMIT_MS_FLOOR.
 * argv[0] is the program path and argv[1] the mode name, so the shape is
 * scanned from argv[2]. On success *limit_ms holds the parsed value (or
 * -1 when the option is absent). Returns 0 on success, -1 on any usage
 * error (unknown flag/positional, missing value, repeated option,
 * non-integer, overflow, or N below the floor). */
static int dealpg4_parse_limit_override(int argc, char **argv,
                                        int64_t *limit_ms)
{
    int i;

    *limit_ms = -1;
    for (i = 2; i < argc; i++) {
        const char *arg = argv[i];

        if (strcmp(arg, "--limit-ms") != 0)
            return -1;
        if (i + 1 >= argc || *limit_ms != -1)
            return -1;
        {
            const char *p = argv[i + 1];
            int64_t value = 0;

            for (; *p != '\0'; p++) {
                int digit = *p - '0';

                if (digit < 0 || digit > 9)
                    return -1;
                if (value > (INT64_MAX - digit) / 10)
                    return -1;
                value = value * 10 + digit;
            }
            if (value < DEALPG4_PROBE_SELFTEST_LIMIT_MS_FLOOR)
                return -1;
            *limit_ms = value;
        }
        i++; /* consume the value token */
    }
    return 0;
}

int main(int argc, char **argv)
{
    enum dealpg4_mode mode;
    int64_t limit_ms = -1;

    if (argc < 2) {
        dealpg4_print_usage(stderr);
        return DEALPG4_EXIT_USAGE;
    }

    /* Capture the process argv[0] for the outer's serve surface
     * (engine D3): the serve argv[0] is the outer's own argv[0] -- the
     * exact string the shell used -- noted here at the single process
     * entry so every mode (including the selftest battery's forked
     * scenario processes) delivers it to the outer core. Dispatch
     * itself creates no process/session/channel and performs no wait. */
    dealpg4_outer_note_process_argv0(argv[0]);

    mode = dealpg4_recognize_mode(argv[1]);
    if (mode == DEALPG4_MODE_UNKNOWN) {
        dealpg4_print_usage(stderr);
        return DEALPG4_EXIT_USAGE;
    }

    /* Dispatch-owned argument validation. probe/selftest get the
     * --limit-ms shape check; run/serve/outer arguments are passed
     * through without any interpretation (their shapes belong to the
     * supervisor/outer children). */
    if (mode == DEALPG4_MODE_PROBE || mode == DEALPG4_MODE_SELFTEST) {
        if (dealpg4_parse_limit_override(argc, argv, &limit_ms) != 0) {
            dealpg4_print_usage(stderr);
            return DEALPG4_EXIT_USAGE;
        }
    }

    /* Mode-entry embedded-limits ordering validation, before any mode
     * code: a violating artifact fails CONFIG_INVALID for every one of
     * the five mode names, with no mode output and no side effects. */
    if (dealpg4_embedded_limits_ordering_check() != DEALPG4_LIMITS_OK) {
        fprintf(stderr, "CONFIG_INVALID\n");
        return DEALPG4_EXIT_CONFIG_INVALID;
    }

    /* Delegate to the mode entry. Dispatch creates no process, session,
     * or channel and performs no wait. */
    switch (mode) {
    case DEALPG4_MODE_PROBE:
        return dealpg4_probe_entry(limit_ms);
    case DEALPG4_MODE_SELFTEST:
        return dealpg4_selftest_entry(limit_ms);
    case DEALPG4_MODE_RUN:
        return dealpg4_run_entry(argc, argv);
    case DEALPG4_MODE_SERVE:
        return dealpg4_serve_entry(argc, argv);
    case DEALPG4_MODE_OUTER:
        return dealpg4_outer_entry(argc, argv);
    case DEALPG4_MODE_UNKNOWN:
        break; /* unreachable: filtered above */
    }
    dealpg4_print_usage(stderr);
    return DEALPG4_EXIT_USAGE;
}
