/*
 * test/native/queue_race_probe.c — the supplementary instrumented queue probe
 * (R1, Verification 9).
 *
 * This is the ThreadSanitizer variant of the concurrent queue workload that
 * `queue_probe.c` case 0 runs in the pinned strict-C11 production build. It is
 * never part of a production build: the ABI suite compiles the published
 * `deal/native/platform.c` together with this driver under
 * `-fsanitize=thread -g`, so the release publication / acquire exchange on the
 * queue head, the `call_once` wake-descriptor setup, the producer -> queue ->
 * consumer ownership handoff and the exactly-once payload/frame release are
 * observed by an instrumented run rather than only by a non-instrumented one.
 *
 * Producer threads are created with `pthread_create` instead of `thrd_create`.
 * That is a property of the sanitizer, not of the queue: glibc's `thrd_create`
 * enters pthread creation through the internal alias, which the sanitizer does
 * not intercept, so a C11-created thread runs without a registered shadow
 * stack and the instrumented process aborts inside the thread start. The queue
 * sees the same threads either way, and the production probes keep using
 * `threads.h` on both pinned optimization levels.
 *
 * The workload is the production one: every producer owns its frame, copies an
 * independent payload, publishes it and overwrites its own source buffer; the
 * single waiting-thread consumer takes every frame exactly once, checks the
 * copied bytes and the unique token, and releases each frame exactly once.
 * `queue ok` on stdout with an empty stderr is the acceptance.
 */
#include "deal/native/platform.h"

/* Clang reports instrumentation through __has_feature, GCC through the
 * __SANITIZE_THREAD__ macro; either one is the accepted spelling. */
#if defined(__has_feature)
#if __has_feature(thread_sanitizer)
#define DEAL_PROBE_INSTRUMENTED 1
#endif
#endif
#if defined(__SANITIZE_THREAD__)
#define DEAL_PROBE_INSTRUMENTED 1
#endif
#if !defined(DEAL_PROBE_INSTRUMENTED)
#error "the supplementary queue probe is only ever built with -fsanitize=thread"
#endif

#include <pthread.h>
#include <stdatomic.h>
#include <stdio.h>
#include <string.h>

#define DEAL_PRODUCER_COUNT 4
#define DEAL_FRAMES_PER_PRODUCER 64
#define DEAL_RACE_PROBE_TIMEOUT_NS 10000000000LL

typedef struct {
    uint32_t token;
    char text[28];
} queue_record;

static atomic_int produced;

static void queue_record_init(queue_record *record, uint32_t token) {
    record->token = token;
    for (int index = 0; index < 27; index += 1) {
        record->text[index] = (char)('a' + (int)((token + (uint32_t)index) % 26U));
    }
    record->text[27] = 0;
}

static void *producer(void *argument) {
    uint32_t base = *(const uint32_t *)argument;
    for (uint32_t index = 0; index < DEAL_FRAMES_PER_PRODUCER; index += 1) {
        uint32_t token = (base - 1U) * DEAL_FRAMES_PER_PRODUCER + index;
        queue_record source;
        queue_record_init(&source, token);
        deal_completion_frame *frame = deal_platform_frame_alloc();
        unsigned char *payload =
            deal_platform_frame_payload(frame, sizeof(source));
        memcpy(payload, &source, sizeof(source));
        deal_platform_post(frame);
        /* The posting caller owns its input until post returns; it may reuse it
         * afterwards without affecting the copied frame bytes. */
        memset(&source, 0, sizeof(source));
        atomic_fetch_add(&produced, 1);
    }
    return NULL;
}

int main(void) {
    pthread_t producers[DEAL_PRODUCER_COUNT];
    uint32_t bases[DEAL_PRODUCER_COUNT];
    int failures = 0;
    atomic_init(&produced, 0);

    /* Establishes this thread as the queue's waiting thread before any producer
     * exists, exactly as the production probe does. */
    (void)deal_platform_take();

    for (int index = 0; index < DEAL_PRODUCER_COUNT; index += 1) {
        bases[index] = (uint32_t)index + 1U;
        if (pthread_create(&producers[index], NULL, producer, &bases[index]) != 0) {
            printf("FAIL: producer thread creation failed\n");
            return 1;
        }
    }

    unsigned char seen[DEAL_PRODUCER_COUNT * DEAL_FRAMES_PER_PRODUCER];
    memset(seen, 0, sizeof(seen));
    int delivered = 0;
    int64_t deadline = deal_platform_monotonic_ns() + DEAL_RACE_PROBE_TIMEOUT_NS;
    while (delivered < DEAL_PRODUCER_COUNT * DEAL_FRAMES_PER_PRODUCER) {
        deal_completion_frame *frame = deal_platform_take();
        if (frame == NULL) {
            if (deal_platform_monotonic_ns() > deadline) {
                printf("FAIL: queued frames were never delivered (%d of %d)\n",
                       delivered, DEAL_PRODUCER_COUNT * DEAL_FRAMES_PER_PRODUCER);
                return 1;
            }
            deal_platform_wait_ns(1000000);
            continue;
        }
        if (deal_platform_frame_payload_size(frame) != sizeof(queue_record)) {
            printf("FAIL: a delivered payload lost its size\n");
            failures += 1;
        } else {
            queue_record copy;
            memcpy(&copy, deal_platform_frame_bytes(frame), sizeof(copy));
            if (copy.token >= (uint32_t)sizeof(seen)) {
                printf("FAIL: a delivered token is outside the produced range\n");
                failures += 1;
            } else {
                queue_record expected;
                queue_record_init(&expected, copy.token);
                if (memcmp(&expected, &copy, sizeof(copy)) != 0) {
                    printf("FAIL: a delivered payload differs from its input\n");
                    failures += 1;
                }
                if (seen[copy.token] != 0) {
                    printf("FAIL: a token was delivered twice\n");
                    failures += 1;
                }
                seen[copy.token] = 1;
                delivered += 1;
            }
        }
        deal_platform_frame_free(frame);
    }

    for (int index = 0; index < DEAL_PRODUCER_COUNT; index += 1) {
        if (pthread_join(producers[index], NULL) != 0) {
            printf("FAIL: a producer could not be joined\n");
            failures += 1;
        }
    }
    if (atomic_load(&produced) != DEAL_PRODUCER_COUNT * DEAL_FRAMES_PER_PRODUCER) {
        printf("FAIL: the producer count is wrong\n");
        failures += 1;
    }
    for (int index = 0; index < DEAL_PRODUCER_COUNT * DEAL_FRAMES_PER_PRODUCER;
            index += 1) {
        if (seen[index] == 0) {
            printf("FAIL: token %d was never delivered\n", index);
            failures += 1;
            break;
        }
    }
    if (deal_platform_take() != NULL) {
        printf("FAIL: the queue still holds a frame after delivery\n");
        failures += 1;
    }
    if (failures != 0) {
        printf("queue FAILED: %d\n", failures);
        return 1;
    }
    printf("queue ok\n");
    deal_platform_flush_stdout();
    return 0;
}
