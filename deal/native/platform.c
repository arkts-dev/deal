/*
 * deal/native/platform.c — the `platform` service group implementation (R1).
 *
 * This is the only translation unit of the runtime that calls the operating
 * system. It owns the buffered standard streams, the clocks, the environment,
 * process exit, raw page allocation, the one fault helper and the completion
 * queue: one thread-safe queue, any number of producers, exactly one consumer,
 * release publication and acquire acquisition, a wake descriptor polled with
 * `poll()` and a bounded wait.
 */
#define _POSIX_C_SOURCE 200809L
#define _DEFAULT_SOURCE 1

#include "deal/native/platform.h"

#include <fcntl.h>
#include <limits.h>
#include <poll.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <threads.h>
#include <time.h>
#include <unistd.h>

/* =========================================================================
 * The single fault helper (R10)
 * ========================================================================= */

static void deal_platform_write_all(int descriptor, const void *bytes,
                                    size_t length) {
    const unsigned char *cursor = bytes;
    while (length > 0) {
        ssize_t written = write(descriptor, cursor, length);
        if (written < 0) {
            return;
        }
        cursor += (size_t)written;
        length -= (size_t)written;
    }
}

static void deal_platform_raw_write_error(const char *text) {
    deal_platform_write_all(2, text, strlen(text));
}

_Noreturn void deal_platform_fault(const char *reason) {
    deal_platform_raw_write_error("DEAL_RUNTIME_FAULT: ");
    deal_platform_raw_write_error(reason != NULL ? reason : "unspecified");
    deal_platform_raw_write_error("\n");
    deal_platform_flush_stdout();
    deal_platform_flush_stderr();
    exit(2);
}

/* =========================================================================
 * Buffered standard streams
 * ========================================================================= */

#define DEAL_STREAM_BUFFER_SIZE 8192

static unsigned char deal_stdout_buffer[DEAL_STREAM_BUFFER_SIZE];
static size_t deal_stdout_length;
static unsigned char deal_stderr_buffer[DEAL_STREAM_BUFFER_SIZE];
static size_t deal_stderr_length;

static void deal_platform_stream_write(unsigned char *buffer, size_t *used,
                                       int descriptor, const void *bytes,
                                       size_t length) {
    const unsigned char *cursor = bytes;
    while (length > 0) {
        size_t available = DEAL_STREAM_BUFFER_SIZE - *used;
        if (available == 0) {
            deal_platform_write_all(descriptor, buffer, *used);
            *used = 0;
            available = DEAL_STREAM_BUFFER_SIZE;
        }
        size_t take = length < available ? length : available;
        memcpy(buffer + *used, cursor, take);
        *used += take;
        cursor += take;
        length -= take;
    }
}

static void deal_platform_stream_flush(unsigned char *buffer, size_t *used,
                                       int descriptor) {
    if (*used > 0) {
        deal_platform_write_all(descriptor, buffer, *used);
        *used = 0;
    }
}

void deal_platform_write_stdout(const void *bytes, size_t length) {
    if (bytes == NULL && length != 0) {
        deal_platform_fault("stdout write: null bytes with non-zero length");
    }
    deal_platform_stream_write(deal_stdout_buffer, &deal_stdout_length, 1,
                               bytes, length);
}

void deal_platform_write_stderr(const void *bytes, size_t length) {
    if (bytes == NULL && length != 0) {
        deal_platform_fault("stderr write: null bytes with non-zero length");
    }
    deal_platform_stream_write(deal_stderr_buffer, &deal_stderr_length, 2,
                               bytes, length);
}

void deal_platform_flush_stdout(void) {
    deal_platform_stream_flush(deal_stdout_buffer, &deal_stdout_length, 1);
}

void deal_platform_flush_stderr(void) {
    deal_platform_stream_flush(deal_stderr_buffer, &deal_stderr_length, 2);
}

/* =========================================================================
 * Environment and process
 * ========================================================================= */

const char *deal_platform_environment(const char *name) {
    if (name == NULL) {
        return NULL;
    }
    return getenv(name);
}

void deal_platform_exit(int status) {
    deal_platform_flush_stdout();
    deal_platform_flush_stderr();
    exit(status);
}

/* =========================================================================
 * Clocks
 * ========================================================================= */

static int64_t deal_platform_clock_ns(clockid_t clock) {
    struct timespec now;
    if (clock_gettime(clock, &now) != 0) {
        deal_platform_fault("clock read failed");
    }
    return (int64_t)now.tv_sec * INT64_C(1000000000) + (int64_t)now.tv_nsec;
}

int64_t deal_platform_monotonic_ns(void) {
    return deal_platform_clock_ns(CLOCK_MONOTONIC);
}

int64_t deal_platform_realtime_ns(void) {
    return deal_platform_clock_ns(CLOCK_REALTIME);
}

/* =========================================================================
 * Checked size arithmetic (R10)
 * ========================================================================= */

/*
 * The sizes that reach an allocation, an alignment or a page round are sums,
 * products and round-ups of untrusted lengths (a completion payload, a managed
 * payload and its header, a table capacity and its stride). Each one is
 * computed here instead of modulo the address space, so an impossible size is
 * the R10 invariant fault before any storage exists, never a short region that
 * a later store or memset overruns.
 */
size_t deal_platform_checked_add(size_t left, size_t right) {
    if (right > SIZE_MAX - left) {
        deal_platform_fault("size arithmetic: addition overflow");
    }
    return left + right;
}

size_t deal_platform_checked_multiply(size_t left, size_t right) {
    if (left != 0u && right > SIZE_MAX / left) {
        deal_platform_fault("size arithmetic: multiplication overflow");
    }
    return left * right;
}

size_t deal_platform_checked_round(size_t bytes, size_t multiple) {
    if (multiple == 0u) {
        deal_platform_fault("size arithmetic: zero rounding multiple");
    }
    size_t bounded = deal_platform_checked_add(bytes, multiple - 1u);
    size_t rounded = (bounded / multiple) * multiple;
    return rounded == 0u ? multiple : rounded;
}

/* =========================================================================
 * Platform memory
 * ========================================================================= */

static size_t deal_platform_page_size(void) {
    long size = sysconf(_SC_PAGESIZE);
    if (size <= 0) {
        return 4096;
    }
    return (size_t)size;
}

static size_t deal_platform_page_round(size_t bytes) {
    return deal_platform_checked_round(bytes, deal_platform_page_size());
}

void *deal_platform_page_alloc(size_t bytes) {
    size_t size = deal_platform_page_round(bytes);
    void *base = mmap(NULL, size, PROT_READ | PROT_WRITE,
                      MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (base == MAP_FAILED) {
        deal_platform_fault("page allocation failed");
    }
    return base;
}

void deal_platform_page_free(void *base, size_t bytes) {
    if (base == NULL) {
        return;
    }
    if (munmap(base, deal_platform_page_round(bytes)) != 0) {
        deal_platform_fault("page release failed");
    }
}

void *deal_platform_allocate(size_t bytes) {
    size_t size = bytes == 0 ? 1 : bytes;
    void *memory = malloc(size);
    if (memory == NULL) {
        deal_platform_fault("platform allocation failed");
    }
    return memory;
}

void deal_platform_release(void *memory) {
    free(memory);
}

char *deal_platform_duplicate(const char *text) {
    if (text == NULL) {
        return NULL;
    }
    size_t length = strlen(text);
    char *copy = deal_platform_allocate(length + 1);
    memcpy(copy, text, length + 1);
    return copy;
}

/* =========================================================================
 * The completion queue (R1)
 * ========================================================================= */

enum {
    DEAL_FRAME_UNPUBLISHED = 0,
    DEAL_FRAME_QUEUED = 1,
    DEAL_FRAME_CONSUMED = 2
};

struct deal_completion_frame {
    struct deal_completion_frame *next;
    unsigned char *payload;
    size_t payload_size;
    int state;
};

static _Atomic(deal_completion_frame *) deal_queue_head = NULL;
static int deal_queue_wake_read = -1;
static int deal_queue_wake_write = -1;
static once_flag deal_queue_once = ONCE_FLAG_INIT;
static thrd_t deal_queue_consumer;
static int deal_queue_consumer_known;

static void deal_queue_create(void) {
    int descriptors[2];
    if (pipe(descriptors) != 0) {
        deal_platform_fault("completion wake descriptor creation failed");
    }
    /* Both ends are non-blocking: a burst of posts must never block its poster
     * once the descriptor is full (the queue head carries the frames, the byte
     * is only the wake hint), and the consumer's drain must never block. */
    if (fcntl(descriptors[0], F_SETFL, O_NONBLOCK) != 0
            || fcntl(descriptors[1], F_SETFL, O_NONBLOCK) != 0) {
        deal_platform_fault("completion wake descriptor configuration failed");
    }
    deal_queue_wake_read = descriptors[0];
    deal_queue_wake_write = descriptors[1];
}

static void deal_queue_ensure(void) {
    call_once(&deal_queue_once, deal_queue_create);
}

/* 1 when the queue still holds a frame: the authority the wake bytes hint at. */
static int deal_queue_pending(void) {
    return atomic_load_explicit(&deal_queue_head, memory_order_acquire) != NULL
        ? 1 : 0;
}

static void deal_queue_drain_wake(void) {
    if (deal_queue_wake_read < 0) {
        return;
    }
    unsigned char discarded[64];
    for (;;) {
        ssize_t taken = read(deal_queue_wake_read, discarded,
                             sizeof(discarded));
        if (taken > 0) {
            continue;
        }
        return;
    }
}

static void deal_queue_establish_consumer(void) {
    thrd_t current = thrd_current();
    if (!deal_queue_consumer_known) {
        deal_queue_consumer = current;
        deal_queue_consumer_known = 1;
        return;
    }
    if (!thrd_equal(deal_queue_consumer, current)) {
        deal_platform_fault("completion take from a thread that is not the "
                            "waiting thread");
    }
}

deal_completion_frame *deal_platform_frame_alloc(void) {
    deal_completion_frame *frame = deal_platform_allocate(sizeof(*frame));
    frame->next = NULL;
    frame->payload = NULL;
    frame->payload_size = 0;
    frame->state = DEAL_FRAME_UNPUBLISHED;
    return frame;
}

void deal_platform_frame_free(deal_completion_frame *frame) {
    if (frame == NULL) {
        return;
    }
    if (frame->state == DEAL_FRAME_QUEUED) {
        deal_platform_fault("completion frame released while queued");
    }
    deal_platform_release(frame->payload);
    frame->payload = NULL;
    frame->payload_size = 0;
    deal_platform_release(frame);
}

unsigned char *deal_platform_frame_payload(deal_completion_frame *frame,
                                           size_t size) {
    if (frame == NULL) {
        deal_platform_fault("completion frame payload: null frame");
    }
    if (frame->state != DEAL_FRAME_UNPUBLISHED) {
        deal_platform_fault("completion frame payload reserved after "
                            "publication");
    }
    if (frame->payload != NULL) {
        if (frame->payload_size != size) {
            deal_platform_fault("completion frame payload reserved twice with "
                                "different sizes");
        }
        return frame->payload;
    }
    frame->payload_size = size;
    if (size == 0) {
        return NULL;
    }
    frame->payload = deal_platform_allocate(size);
    return frame->payload;
}

size_t deal_platform_frame_payload_size(const deal_completion_frame *frame) {
    if (frame == NULL) {
        return 0;
    }
    return frame->payload_size;
}

unsigned char *deal_platform_frame_append(deal_completion_frame *frame,
                                          size_t additional) {
    if (frame == NULL) {
        deal_platform_fault("completion frame append: null frame");
    }
    if (frame->state != DEAL_FRAME_UNPUBLISHED) {
        deal_platform_fault("completion frame append after publication");
    }
    /* A burst copy-in appends a record and its copied text: the total is the
     * overflow-checked sum, so an impossible payload is the R10 fault before
     * any storage is reserved. */
    size_t grown = deal_platform_checked_add(frame->payload_size, additional);
    if (additional == 0) {
        return frame->payload == NULL ? NULL
            : frame->payload + frame->payload_size;
    }
    unsigned char *storage = deal_platform_allocate(grown);
    if (frame->payload != NULL) {
        memcpy(storage, frame->payload, frame->payload_size);
        deal_platform_release(frame->payload);
    }
    unsigned char *appended = storage + frame->payload_size;
    memset(appended, 0, additional);
    frame->payload = storage;
    frame->payload_size = grown;
    return appended;
}

const unsigned char *deal_platform_frame_bytes(
        const deal_completion_frame *frame) {
    if (frame == NULL) {
        return NULL;
    }
    return frame->payload;
}

void deal_platform_post(deal_completion_frame *frame) {
    if (frame == NULL) {
        deal_platform_fault("completion post: null frame");
    }
    if (frame->state != DEAL_FRAME_UNPUBLISHED) {
        deal_platform_fault("completion post: frame is not unpublished");
    }
    deal_queue_ensure();
    frame->state = DEAL_FRAME_QUEUED;
    deal_completion_frame *head =
        atomic_load_explicit(&deal_queue_head, memory_order_relaxed);
    for (;;) {
        frame->next = head;
        if (atomic_compare_exchange_weak_explicit(&deal_queue_head, &head,
                                                  frame, memory_order_release,
                                                  memory_order_relaxed)) {
            break;
        }
    }
    /* The wake write is a hint, never a transfer: a full descriptor answers
     * EAGAIN and the frame is already published for the next take. */
    unsigned char signal = 1;
    ssize_t ignored = write(deal_queue_wake_write, &signal, 1);
    (void)ignored;
}

deal_completion_frame *deal_platform_take(void) {
    deal_queue_establish_consumer();
    deal_queue_ensure();
    deal_completion_frame *head =
        atomic_load_explicit(&deal_queue_head, memory_order_acquire);
    while (head != NULL) {
        deal_completion_frame *next = head->next;
        if (atomic_compare_exchange_weak_explicit(&deal_queue_head, &head, next,
                                                  memory_order_acquire,
                                                  memory_order_acquire)) {
            head->next = NULL;
            head->state = DEAL_FRAME_CONSUMED;
            deal_queue_drain_wake();
            return head;
        }
    }
    deal_queue_drain_wake();
    return NULL;
}

void deal_platform_wait_ns(int64_t interval_ns) {
    deal_queue_establish_consumer();
    deal_queue_ensure();
    int64_t slice_ns = interval_ns < 0 ? 0 : interval_ns;
    int64_t slice_ms = (slice_ns + INT64_C(999999)) / INT64_C(1000000);
    if (slice_ms > (int64_t)INT_MAX) {
        slice_ms = (int64_t)INT_MAX;
    }
    struct pollfd descriptor;
    descriptor.fd = deal_queue_wake_read;
    descriptor.events = POLLIN;
    descriptor.revents = 0;
    /* A queued frame is the authority, and an earlier take may already have
     * drained every wake byte. Sleeping here would sleep past a delivered
     * frame, so the head is observed before the descriptor blocks. */
    if (deal_queue_pending() != 0) {
        return;
    }
    int ready = poll(&descriptor, 1, (int)slice_ms);
    if (ready < 0) {
        deal_queue_drain_wake();
        return;
    }
    if (ready > 0) {
        deal_queue_drain_wake();
    }
}
