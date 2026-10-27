/*
 * deal/native/platform.h — the `platform` service group.
 *
 * The only group that may call the operating system
 * (`llvm-native-runtime-value-model` R1): monotonic and realtime clocks,
 * buffered stdout/stderr writes with explicit flush, environment lookup,
 * process exit, raw page allocation for the collector allocator, the one
 * resource/invariant fault helper of R10, and the completion queue: one
 * thread-safe, single-consumer queue with exact producer -> queue -> consumer
 * ownership transfer.
 *
 * Completion frame contract (R1):
 *   - `deal_platform_frame_alloc` returns exclusive producer ownership of a
 *     frame whose storage is the platform's thread-safe system allocator, never
 *     the single-mutator page allocator.
 *   - While the frame is unpublished only its producer may fill it, reserve or
 *     fill its payload or free it. `deal_platform_post` transfers ownership to
 *     the queue and may be called from any thread; the producer must never
 *     touch the frame again. `deal_platform_take` transfers ownership to the
 *     sole consumer (the waiting thread), which releases the frame and its
 *     payload exactly once, including after a rejected delivery.
 *   - Publication is a release operation on the queue head and acquisition an
 *     acquire exchange, so every byte of a taken frame is visible. Posting
 *     signals the waiting thread through a wake descriptor; a full wake
 *     descriptor is not an error because the consumer re-reads the queue head
 *     after draining it. `deal_platform_wait_ns` blocks for at most the given
 *     interval and never busy-spins.
 *   - A frame holds copied host-native bytes. It never holds a managed pointer
 *     and never holds a DEAL-origin failure token, so the queue can be used by
 *     threads that never touch the managed heap.
 *   - A take by a thread other than the established consumer, and releasing a
 *     frame that is still queued (the exactly-once ownership violation), are
 *     invariant faults: one deterministic stderr line and exit 2.
 *
 * Every operating-system access of the whole runtime goes through this header;
 * no other group includes an operating-system header for I/O, time, environment,
 * process or allocation.
 */
#ifndef DEAL_NATIVE_PLATFORM_H
#define DEAL_NATIVE_PLATFORM_H

#include "deal/native/deal_rt.h"

/* An opaque completion frame; its layout exists only in platform.c. */
typedef struct deal_completion_frame deal_completion_frame;

/* -- the completion queue (R1) ------------------------------------------- */

/* A caller-owned frame in platform memory; exclusive producer ownership. */
deal_completion_frame *deal_platform_frame_alloc(void);

/* Releases an unpublished or consumed frame and its payload exactly once. */
void deal_platform_frame_free(deal_completion_frame *frame);

/* Any thread: publishes the frame and releases it not before consumer take. */
void deal_platform_post(deal_completion_frame *frame);

/* The waiting thread only: acquires the next queued frame, or NULL. */
deal_completion_frame *deal_platform_take(void);

/* The waiting thread only: bounded block (never a busy spin). */
void deal_platform_wait_ns(int64_t interval_ns);

/*
 * Reserves the frame's copied payload storage: the copy-in record knows its
 * total size before it fills the frame, so the storage is usually reserved
 * once. The returned base stays valid until the frame is freed or its storage
 * grows; a second reservation for the same size returns the same base, any
 * other size is an invariant fault.
 */
unsigned char *deal_platform_frame_payload(deal_completion_frame *frame, size_t size);

/*
 * Grows the payload by `additional` bytes and returns the base of the appended
 * region, so a variable-length copy-in (a record followed by its copied text)
 * appends instead of computing one total size up front. Earlier bytes keep
 * their offsets; because the storage may relocate, a caller re-fetches it
 * through `deal_platform_frame_bytes` after an append instead of holding an
 * earlier pointer.
 */
unsigned char *deal_platform_frame_append(deal_completion_frame *frame,
                                          size_t additional);

/* The reserved payload size (0 before the first reservation). */
size_t deal_platform_frame_payload_size(const deal_completion_frame *frame);

/*
 * The reserved payload bytes of a frame, read-only. A producer reads them
 * before publication; the consumer reads them after take and before the
 * exactly-once release. No owner mutates a frame it does not currently own.
 */
const unsigned char *deal_platform_frame_bytes(const deal_completion_frame *frame);

/* -- clocks (R1, R7) ------------------------------------------------------ */

/* Monotonic nanoseconds; the pump slice and the queue bound use this clock. */
int64_t deal_platform_monotonic_ns(void);

/* Realtime nanoseconds since the Unix epoch; `std/time.nowMillis` reads it. */
int64_t deal_platform_realtime_ns(void);

/* -- buffered standard streams (R1, R9) ---------------------------------- */

/* Buffered writes; the terminal flushes explicitly before it exits. */
void deal_platform_write_stdout(const void *bytes, size_t length);
void deal_platform_write_stderr(const void *bytes, size_t length);
void deal_platform_flush_stdout(void);
void deal_platform_flush_stderr(void);

/* -- environment and process (R1, D5, H6) -------------------------------- */

/* The environment value, or NULL when the variable is absent. */
const char *deal_platform_environment(const char *name);

/* Flushes both streams and exits with the given status. */
void deal_platform_exit(int status);

/* -- platform memory (R1, R8, R10) --------------------------------------- */

/* Page-aligned, zero-initialized raw pages for the collector allocator. */
void *deal_platform_page_alloc(size_t bytes);

/* Releases exactly the region a matching page allocation returned. */
void deal_platform_page_free(void *base, size_t bytes);

/* Thread-safe system-allocator storage for non-managed platform data. */
void *deal_platform_allocate(size_t bytes);

/* Releases storage obtained from deal_platform_allocate/duplicate (NULL ok). */
void deal_platform_release(void *memory);

/* A copy of `text` in platform memory (NULL input yields NULL). */
char *deal_platform_duplicate(const char *text);

/* -- the one fault helper (R10) ----------------------------------------- */

/*
 * The single spelling of a resource or invariant fault: writes exactly one
 * deterministic line, "DEAL_RUNTIME_FAULT: <reason>\n", to stderr, flushes
 * stdout, and exits 2 without returning to the caller. Every group reuses this
 * entry; no second spelling exists. A DEAL `Error` is never fabricated here
 * (R10: resource faults are not language-visible failures).
 */
_Noreturn void deal_platform_fault(const char *reason);

#endif /* DEAL_NATIVE_PLATFORM_H */
