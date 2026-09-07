/*
 * ffigen-integration-fixture.c — minimal non-DEAL C99 support library
 * for the LuaJIT FFIGEN boundary integration
 * (luajit-ffigen-boundary-integration D4). Compiled at driver bootstrap
 * by the test bootstrap with GCC to build/ffigen-integration-fixture.so;
 * the integration loads it exclusively through the production
 * __rt.load_ffi path.
 *
 * The generated cdef bundle declares private types for every symbol
 * below (adopted D2): this source never declares a real target-function
 * prototype shared with DEAL.
 *
 * Includes only ISO C99 standard headers — no DEAL headers, no
 * generated support. The constructor/destructor pair appends exactly one
 * "open" line (constructor) and one "close" line (destructor) to the
 * event file whose absolute path is supplied at compile time via
 * FIXTURE_EVENTS_PATH, so the integration can prove open/close counts
 * through the production loader. Every exported function except
 * fixture_call_count (read-only) and fixture_reset_counter (reset)
 * increments the in-process counter on entry — including the pure
 * arithmetic functions — so exactly one native call per wrapper call is
 * observable as exactly one counter increment.
 */

#include <stdint.h>
#include <stdio.h>

#ifndef FIXTURE_EVENTS_PATH
#error "FIXTURE_EVENTS_PATH must be defined by the test bootstrap compile"
#endif

/* In-process call counter: readable via fixture_call_count(), zeroable
 * via fixture_reset_counter(). */
static int fixture_counter = 0;

/* Static echo buffer for fixture_echo_string (the returned string is
 * C-owned; DEAL copies it and never frees it). */
static char fixture_echo_buffer[64];

/* Append one line to the compile-time event file. Best-effort: the file
 * lives under the integration-owned build/ directory created by the
 * bootstrap step before any dlopen. */
static void fixture_event_line(const char *line)
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
static void fixture_open_event(void)
{
    fixture_event_line("open");
}

__attribute__((destructor))
static void fixture_close_event(void)
{
    fixture_event_line("close");
}

void fixture_count_call(void)
{
    fixture_counter++;
}

/* Increments AND returns the counter (the Probe default channel: any
 * load-time or replay-time default evaluation is observable as a counter
 * reading >= 1). */
int fixture_count_call_int(void)
{
    fixture_counter++;
    return fixture_counter;
}

int fixture_call_count(void)
{
    return fixture_counter;
}

void fixture_reset_counter(void)
{
    fixture_counter = 0;
}

int fixture_add_int(int a, int b)
{
    fixture_counter++;
    return a + b;
}

/* Left-to-right argument order observable on success: (7, 2) returns 5
 * while (2, 7) returns -5. */
int fixture_sub_int(int a, int b)
{
    fixture_counter++;
    return a - b;
}

double fixture_add_number(double a, double b)
{
    fixture_counter++;
    return a + b;
}

/* _Bool 0/1 roundtrip: b == 0 returns 1, b != 0 returns 0. */
int fixture_not(int b)
{
    fixture_counter++;
    return b ? 0 : 1;
}

const char* fixture_echo_string(const char* s)
{
    int i = 0;
    fixture_counter++;
    while (i < 63 && s[i] != '\0') {
        fixture_echo_buffer[i] = s[i];
        i++;
    }
    fixture_echo_buffer[i] = '\0';
    return fixture_echo_buffer;
}

int fixture_bytes_sum(const uint8_t* p, int32_t n)
{
    int32_t i;
    int sum = 0;
    fixture_counter++;
    for (i = 0; i < n; i++) {
        sum += (int)p[i];
    }
    return sum;
}

const char* fixture_null_string(void)
{
    fixture_counter++;
    return NULL;
}
