/*
 * ffi-runtime-dual-b.c — second half of the ISSUE-0163 dual-library
 * battery (D11): a real Linux shared object exporting the symbol
 * fixture_dual_combine with the double signature
 * (double, double) -> double. The battery loads it through the
 * production __rt.load_ffi path with a module-private cdef typedef and
 * private cast; no shared target prototype exists. The paired library
 * ffi-runtime-dual-a.c exports the same symbol name with an int32
 * signature, and both libraries must remain loadable and callable in
 * one process through their retained exact handles.
 *
 * The constructor/destructor pair appends exactly one "open" line and
 * one "close" line to the event file whose absolute path is supplied at
 * compile time via FIXTURE_EVENTS_PATH, so the battery can prove
 * open/close counts for scenarios that use this library as their first
 * load in the process.
 */

#include <stdio.h>

#ifndef FIXTURE_EVENTS_PATH
#error "FIXTURE_EVENTS_PATH must be defined by the test bootstrap compile"
#endif

static void dual_b_event_line(const char *line)
{
    FILE *f = fopen(FIXTURE_EVENTS_PATH, "a");
    if (f == NULL) {
        return;
    }
    fputs(line, f);
    fputc('\n', f);
    fclose(f);
}

__attribute__((constructor))
static void dual_b_open_event(void)
{
    dual_b_event_line("open");
}

__attribute__((destructor))
static void dual_b_close_event(void)
{
    dual_b_event_line("close");
}

double fixture_dual_combine(double a, double b)
{
    return a * b;
}
