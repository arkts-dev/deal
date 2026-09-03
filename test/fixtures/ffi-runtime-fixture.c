/*
 * ffi-runtime-fixture.c — minimal non-DEAL C99 support library for the
 * LuaJIT FFI runtime unit battery (luajit-ffi-six-code-atomicity-battery
 * B2). Compiled at battery start by the test bootstrap with GCC to
 * build/ffi-runtime-fixture.so (B3); the battery loads it exclusively
 * through the production __rt.load_ffi path.
 *
 * The battery's generated-shape cdef bundle declares private types for
 * every symbol below (adopted D2): this source never declares a real
 * target-function prototype shared with DEAL.
 *
 * Includes only ISO C99 standard headers — no DEAL headers, no
 * generated support. The constructor/destructor pair appends exactly one
 * "open" line (constructor) and one "close" line (destructor) to the
 * event file whose absolute path is supplied at compile time via
 * FIXTURE_EVENTS_PATH, so the battery can prove open/close counts
 * through the production loader. Every counter-incrementing function
 * increments the in-process counter on entry, so the battery can prove
 * whether a native call ran or never ran.
 */

#include <stdint.h>
#include <stdio.h>
#include <stddef.h>
#include <math.h>

#ifndef FIXTURE_EVENTS_PATH
#error "FIXTURE_EVENTS_PATH must be defined by the test bootstrap compile"
#endif

/* In-process call counter: readable via fixture_call_count(), zeroable
 * via fixture_reset_counter(). */
static int fixture_counter = 0;

/* Static echo buffer for fixture_echo_string (the returned string is
 * C-owned; DEAL copies it and never frees it). */
static char fixture_echo_buffer[64];

/* Static storage whose address fixture_static_pointer returns. */
static int fixture_static = 7;

/* Append one line to the compile-time event file. Best-effort: the file
 * lives under the battery-owned build/ directory created by the
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
    return a + b;
}

double fixture_add_number(double a, double b)
{
    return a + b;
}

/* _Bool 0/1 roundtrip: b == 0 returns 1, b != 0 returns 0. */
int fixture_not(int b)
{
    return b ? 0 : 1;
}

const char *fixture_echo_string(const char *s)
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

int fixture_bytes_sum(const uint8_t *p, int32_t n)
{
    int32_t i;
    int sum = 0;
    for (i = 0; i < n; i++) {
        sum += (int)p[i];
    }
    return sum;
}

const char *fixture_null_string(void)
{
    fixture_counter++;
    return NULL;
}

const char *fixture_bad_utf8(void)
{
    fixture_counter++;
    return "\xFF\xFE";
}

void *fixture_null_pointer(void)
{
    fixture_counter++;
    return NULL;
}

void *fixture_static_pointer(void)
{
    fixture_counter++;
    return (void *)&fixture_static;
}

/* Private ABI-layout struct definitions for the pinned by-value struct
 * functions below: the battery's cdef bundle keeps declaring its own
 * private types, so these definitions only guarantee ABI layout. */
typedef struct {
    int32_t deal_f0;
    double deal_f1;
} deal_ffi_0435_pair_t;

typedef struct {
    void *deal_f0;
} deal_ffi_0435_ptr_box_t;

/* Returns {x, y} by value; no counter effect. */
deal_ffi_0435_pair_t fixture_make_pair(int32_t x, double y)
{
    deal_ffi_0435_pair_t r;
    r.deal_f0 = x;
    r.deal_f1 = y;
    return r;
}

/* Returns {p} by value; no counter effect. */
deal_ffi_0435_ptr_box_t fixture_make_ptr_box(void *p)
{
    deal_ffi_0435_ptr_box_t r;
    r.deal_f0 = p;
    return r;
}

/* Increments the in-process counter on entry, then returns {NULL} by
 * value: the counter proves the native call ran even though the
 * inbound conversion fails on the NULL pointer field. */
deal_ffi_0435_ptr_box_t fixture_make_null_ptr_box(void)
{
    deal_ffi_0435_ptr_box_t r;
    fixture_counter++;
    r.deal_f0 = NULL;
    return r;
}

/* ===== ISSUE-0163 D-series additions =====
 *
 * The ISSUE-0163 acceptance matrix (cache identity variants, the full
 * cdef certainty matrix, dual-library handle scoping, and the exact
 * D4-D5 ABI rows). Every symbol stays behind the battery's own private
 * cdef declarations; no shared target prototype exists here. */

/* int32 sign/bounds roundtrips: every inbound int32 bit pattern
 * sign-preserves without error. */
int32_t fixture_identity_int(int32_t v)
{
    return v;
}

int32_t fixture_extreme_int(int32_t which)
{
    if (which == 0) {
        return INT32_MIN;
    }
    if (which == 1) {
        return INT32_MAX;
    }
    return -1;
}

/* IEEE-754 doubles: every inbound pattern is total (NaN, +/-Inf,
 * +/-0.0). */
double fixture_number_special(int32_t which)
{
    switch (which) {
    case 0: return NAN;
    case 1: return INFINITY;
    case 2: return -INFINITY;
    case 3: return 0.0;
    case 4: return -0.0;
    default: return 1.0;
    }
}

/* Inbound boolean rows: zero false, nonzero true without error. */
int32_t fixture_nonzero_bool(void)
{
    return 7;
}

int32_t fixture_zero_bool(void)
{
    return 0;
}

/* Long first-NUL string returns: valid strings have no DEAL-visible
 * length cap; the static buffer is C-owned and DEAL copies it. */
#define FIXTURE_LONG_STRING_LEN 8192
static char fixture_long_string_buffer[FIXTURE_LONG_STRING_LEN];
static char fixture_long_echo_buffer[FIXTURE_LONG_STRING_LEN];
static int fixture_long_string_ready = 0;

const char *fixture_long_string(void)
{
    int i;
    if (!fixture_long_string_ready) {
        for (i = 0; i < FIXTURE_LONG_STRING_LEN - 1; i++) {
            fixture_long_string_buffer[i] = (char)('a' + (i % 26));
        }
        fixture_long_string_buffer[FIXTURE_LONG_STRING_LEN - 1] = '\0';
        fixture_long_string_ready = 1;
    }
    return fixture_long_string_buffer;
}

const char *fixture_echo_long(const char *s)
{
    int i = 0;
    while (i < FIXTURE_LONG_STRING_LEN - 1 && s[i] != '\0') {
        fixture_long_echo_buffer[i] = s[i];
        i++;
    }
    fixture_long_echo_buffer[i] = '\0';
    return fixture_long_echo_buffer;
}

/* Two bytes parameters at distinct source positions: each borrows the
 * buffer pointer immediately followed by its signed-int32 length. */
int32_t fixture_bytes_sum2(const uint8_t *p, int32_t n,
                           const uint8_t *q, int32_t m)
{
    int32_t i;
    int32_t s = 0;
    for (i = 0; i < n; i++) {
        s += (int32_t)p[i];
    }
    for (i = 0; i < m; i++) {
        s += (int32_t)q[i];
    }
    return s;
}

/* By-value struct parameters: C receives one copy of the converted
 * temporary; mutations of the copy never touch the DEAL class. */
deal_ffi_0435_pair_t fixture_echo_pair(deal_ffi_0435_pair_t p)
{
    return p;
}

deal_ffi_0435_pair_t fixture_bump_pair(deal_ffi_0435_pair_t p)
{
    deal_ffi_0435_pair_t r;
    r.deal_f0 = p.deal_f0 + 1;
    r.deal_f1 = p.deal_f1 + 1.0;
    return r;
}

int32_t fixture_sum_pair(deal_ffi_0435_pair_t p)
{
    return p.deal_f0 + (int32_t)p.deal_f1;
}

/* Source-order deal_fN members whose DEAL field names are C keywords:
 * the private cdef never sees the DEAL names, so they cannot poison it. */
typedef struct {
    int32_t deal_f0;
    int32_t deal_f1;
    int32_t deal_f2;
} deal_ffi_0163_kw_t;

deal_ffi_0163_kw_t fixture_make_kw(int32_t a, int32_t b, int32_t c)
{
    deal_ffi_0163_kw_t r;
    r.deal_f0 = a;
    r.deal_f1 = b;
    r.deal_f2 = c;
    return r;
}

/* Non-null pointer-field check through a by-value PtrBox parameter. */
int32_t fixture_ptrbox_nonnull(deal_ffi_0435_ptr_box_t b)
{
    return b.deal_f0 != NULL ? 1 : 0;
}
