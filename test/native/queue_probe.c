/*
 * test/native/queue_probe.c — the R1 completion queue.
 *
 * Case 0 runs concurrent producers and the single waiting-thread consumer:
 * every producer allocates its own frame, copies an independent payload into
 * platform-owned frame storage, publishes the frame and then overwrites its own
 * source buffer; the consumer takes every frame exactly once, observes the
 * original bytes and the original token, releases each frame exactly once, and
 * checks that `deal_platform_wait_ns` returns within its bound. Cases 1 and 2
 * are the deterministic ownership faults: a take from a thread that is not the
 * waiting thread, and releasing a frame that is still queued.
 */
#include "deal/native/platform.h"

#include <stdatomic.h>
#include <stdio.h>
#include <string.h>
#include <threads.h>

#ifndef DEAL_QUEUE_CASE
#define DEAL_QUEUE_CASE 0
#endif

#define DEAL_PRODUCER_COUNT 4
#define DEAL_FRAMES_PER_PRODUCER 64

typedef struct {
    uint32_t token;
    char text[28];
} queue_record;

#if DEAL_QUEUE_CASE == 0
static atomic_int produced;
#endif

#if DEAL_QUEUE_CASE == 0

static void queue_record_init(queue_record *record, uint32_t token) {
    record->token = token;
    for (int index = 0; index < 27; index += 1) {
        record->text[index] = (char)('a' + (int)((token + (uint32_t)index) % 26U));
    }
    record->text[27] = 0;
}

static int producer(void *argument) {
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
    return 0;
}

int main(void) {
    thrd_t producers[DEAL_PRODUCER_COUNT];
    uint32_t bases[DEAL_PRODUCER_COUNT];
    int failures = 0;
    atomic_init(&produced, 0);

    /* One frame taken by the waiting thread establishes the consumer, so the
     * off-thread take below is the deterministic ownership violation. */
    deal_completion_frame *first = deal_platform_frame_alloc();
    uint32_t first_token = 7777U;
    memcpy(deal_platform_frame_payload(first, sizeof(first_token)), &first_token,
           sizeof(first_token));
    deal_platform_post(first);
    uint64_t wait_start = (uint64_t)deal_platform_monotonic_ns();
    deal_completion_frame *taken = NULL;
    while ((taken = deal_platform_take()) == NULL) {
        deal_platform_wait_ns(1000000);
        if ((uint64_t)deal_platform_monotonic_ns() - wait_start > 2000000000ULL) {
            printf("FAIL: a posted frame was never taken\n");
            return 1;
        }
    }
    uint64_t wait_elapsed = (uint64_t)deal_platform_monotonic_ns() - wait_start;
    if (wait_elapsed > 200000000ULL) {
        printf("FAIL: a posted frame was taken outside its bound (%llu ns)\n",
               (unsigned long long)wait_elapsed);
        failures += 1;
    }
    uint32_t observed_token = 0;
    if (deal_platform_frame_payload_size(taken) != sizeof(first_token)) {
        printf("FAIL: the taken payload size changed\n");
        failures += 1;
    }
    memcpy(&observed_token, deal_platform_frame_bytes(taken),
           sizeof(observed_token));
    if (observed_token != first_token) {
        printf("FAIL: the taken payload token changed\n");
        failures += 1;
    }
    deal_platform_frame_free(taken);

    /* Growing the payload keeps every earlier byte at its offset. */
    deal_completion_frame *grown = deal_platform_frame_alloc();
    unsigned char *head = deal_platform_frame_payload(grown, 4);
    memcpy(head, "abcd", 4);
    unsigned char *tail = deal_platform_frame_append(grown, 4);
    memcpy(tail, "efgh", 4);
    if (deal_platform_frame_payload_size(grown) != 8
            || memcmp(deal_platform_frame_bytes(grown), "abcdefgh", 8) != 0) {
        printf("FAIL: a grown payload lost an appended region\n");
        failures += 1;
    }
    deal_platform_frame_free(grown);

    /* The copied-completion probe: a caller-owned H2 completion record with the
     * appended undefined arm is copied into frame storage, the caller then
     * overwrites its own record, and the consumer still observes the original
     * discriminant. The undefined arm reads no union member, so the stale union
     * payload of the source never becomes completion data. */
    {
        deal_host_completion source;
        memset(&source, 0, sizeof(source));
        source.kind = DEAL_COMPLETION_UNDEFINED;
        source.as.number = -0.0;
        deal_completion_frame *frame = deal_platform_frame_alloc();
        memcpy(deal_platform_frame_payload(frame, sizeof(source)), &source,
               sizeof(source));
        deal_platform_post(frame);
        memset(&source, 0, sizeof(source));
        deal_completion_frame *received = NULL;
        uint64_t copy_start = (uint64_t)deal_platform_monotonic_ns();
        while ((received = deal_platform_take()) == NULL) {
            deal_platform_wait_ns(1000000);
            if ((uint64_t)deal_platform_monotonic_ns() - copy_start
                    > 2000000000ULL) {
                printf("FAIL: the copied completion frame was never taken\n");
                return 1;
            }
        }
        if (deal_platform_frame_payload_size(received) != sizeof(source)) {
            printf("FAIL: the copied completion record changed size\n");
            failures += 1;
        } else {
            const deal_host_completion *copied =
                (const deal_host_completion *)deal_platform_frame_bytes(received);
            if (copied->kind != DEAL_COMPLETION_UNDEFINED) {
                printf("FAIL: the copied undefined completion arm changed\n");
                failures += 1;
            }
        }
        deal_platform_frame_free(received);
    }

    /* The bounded wait returns within its bound when nothing arrives. */
    uint64_t empty_start = (uint64_t)deal_platform_monotonic_ns();
    deal_platform_wait_ns(20000000);
    uint64_t empty_elapsed = (uint64_t)deal_platform_monotonic_ns() - empty_start;
    if (empty_elapsed > 200000000ULL) {
        printf("FAIL: the bounded wait exceeded its bound (%llu ns)\n",
               (unsigned long long)empty_elapsed);
        failures += 1;
    }

    for (int index = 0; index < DEAL_PRODUCER_COUNT; index += 1) {
        bases[index] = (uint32_t)index + 1U;
        if (thrd_create(&producers[index], producer, &bases[index]) != thrd_success) {
            printf("FAIL: producer thread creation failed\n");
            return 1;
        }
    }

    unsigned char seen[DEAL_PRODUCER_COUNT * DEAL_FRAMES_PER_PRODUCER];
    memset(seen, 0, sizeof(seen));
    int delivered = 0;
    uint64_t deadline = (uint64_t)deal_platform_monotonic_ns() + 10000000000ULL;
    while (delivered < DEAL_PRODUCER_COUNT * DEAL_FRAMES_PER_PRODUCER) {
        deal_completion_frame *frame = deal_platform_take();
        if (frame == NULL) {
            if ((uint64_t)deal_platform_monotonic_ns() > deadline) {
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
            uint32_t expected_token;
            queue_record expected;
            if (copy.token >= (uint32_t)sizeof(seen)) {
                printf("FAIL: a delivered token is outside the produced range\n");
                failures += 1;
            } else {
                expected_token = copy.token;
                queue_record_init(&expected, expected_token);
                if (memcmp(&expected, &copy, sizeof(copy)) != 0) {
                    printf("FAIL: a delivered payload differs from its input\n");
                    failures += 1;
                }
                if (seen[expected_token] != 0) {
                    printf("FAIL: a token was delivered twice\n");
                    failures += 1;
                }
                seen[expected_token] = 1;
                delivered += 1;
            }
        }
        deal_platform_frame_free(frame);
    }

    for (int index = 0; index < DEAL_PRODUCER_COUNT; index += 1) {
        int outcome = 1;
        thrd_join(producers[index], &outcome);
        if (outcome != 0) {
            printf("FAIL: a producer reported a failure\n");
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

#elif DEAL_QUEUE_CASE == 1

static int off_thread_take(void *argument) {
    (void)argument;
    (void)deal_platform_take();
    return 0;
}

int main(void) {
    /* Establishes this thread as the waiting thread. */
    (void)deal_platform_take();
    thrd_t thread;
    if (thrd_create(&thread, off_thread_take, NULL) != thrd_success) {
        printf("FAIL: thread creation failed\n");
        return 1;
    }
    int outcome = 1;
    thrd_join(thread, &outcome);
    printf("unreachable\n");
    return 0;
}

#else

int main(void) {
    deal_completion_frame *frame = deal_platform_frame_alloc();
    deal_platform_post(frame);
    /* Releasing a published frame is the exactly-once ownership violation. */
    deal_platform_frame_free(frame);
    printf("unreachable\n");
    return 0;
}

#endif
