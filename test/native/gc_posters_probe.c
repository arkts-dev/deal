/*
 * test/native/gc_posters_probe.c — the supplementary instrumented
 * poster/collector probe (M5 `QueuedCompletionLifetime`, M3, R1 Verification 9).
 *
 * The published runtime and this probe are compiled into one product with
 * `-fsanitize=thread -g`: several posters allocate, copy into, post and reuse
 * their own platform frames while the mutator allocates managed objects, enters
 * root frames and runs complete collections. The probe asserts that no managed
 * object is reachable from a queued frame, that every copied payload arrives
 * byte-exact and exactly once, and that the sanitizer reports no data race
 * between the posters and the collector.
 *
 * The translation unit refuses to compile without the sanitizer, and the
 * producers are POSIX threads because the sanitizer does not intercept the
 * internal pthread path glibc's `thrd_create` takes (the same host property the
 * completion-queue race probe documents). This fixture is never part of a
 * production build.
 */
#include "deal/native/entry.h"
#include "deal/native/memory.h"
#include "deal/native/platform.h"

#include <pthread.h>
#include <stdio.h>
#include <string.h>

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
#error "the poster/collector probe is a ThreadSanitizer-only fixture"
#endif

/* An emitted layout identity: a 1024-byte payload with no managed slot. */
#define TEST_LAYOUT_BULK 1025u
#define TEST_BULK_BYTES 1024u

#define POSTER_COUNT 3
#define FRAMES_PER_POSTER 32
#define MUTATOR_ROUNDS 64

typedef struct {
    uint32_t token;
    char text[28];
} poster_record;

static const deal_layout test_layouts[] = {
    {TEST_LAYOUT_BULK, 0u, TEST_BULK_BYTES, NULL, 0u}
};

static void poster_record_init(poster_record *record, uint32_t token) {
    record->token = token;
    for (int index = 0; index < 27; index += 1) {
        record->text[index] =
            (char)('A' + (int)((token + (uint32_t)index) % 26U));
    }
    record->text[27] = 0;
}

static void *poster(void *argument) {
    uint32_t base = *(const uint32_t *)argument;
    for (uint32_t index = 0; index < FRAMES_PER_POSTER; index += 1) {
        uint32_t token = base * FRAMES_PER_POSTER + index;
        poster_record source;
        poster_record_init(&source, token);
        deal_completion_frame *frame = deal_platform_frame_alloc();
        unsigned char *payload =
            deal_platform_frame_payload(frame, sizeof(source));
        memcpy(payload, &source, sizeof(source));
        deal_platform_post(frame);
        /* The posting caller reuses its own input immediately after post. */
        memset(&source, 0, sizeof(source));
    }
    return NULL;
}

int main(void) {
    int failures = 0;
    int seen[POSTER_COUNT * FRAMES_PER_POSTER];
    pthread_t posters[POSTER_COUNT];
    uint32_t bases[POSTER_COUNT];

    memset(seen, 0, sizeof(seen));
    deal_layout_install(test_layouts,
                        sizeof(test_layouts) / sizeof(test_layouts[0]));

    /* The waiting thread establishes the consumer and creates the queue before
     * any poster exists: the consumer is the mutator, and the queue's wake
     * descriptor is initialized once on it. */
    if (deal_platform_take() != NULL) {
        printf("FAIL: queue is not empty before the posters start\n");
        return 1;
    }

    for (int index = 0; index < POSTER_COUNT; index += 1) {
        bases[index] = (uint32_t)index;
        if (pthread_create(&posters[index], NULL, poster, &bases[index]) != 0) {
            printf("FAIL: poster thread start\n");
            return 1;
        }
    }

    /* The mutator allocates, roots and collects while the posters run: a queued
     * frame holds copied platform bytes only, so the collector never reaches one
     * and the posters never reach the managed heap. */
    for (int round = 0; round < MUTATOR_ROUNDS; round += 1) {
        deal_root_frame_enter(1u);
        void *object = deal_alloc(TEST_LAYOUT_BULK, TEST_BULK_BYTES);
        deal_root_frame_write(0u, object);
        deal_gc_collect_full();
        deal_root_frame_leave();
    }

    for (int index = 0; index < POSTER_COUNT; index += 1) {
        if (pthread_join(posters[index], NULL) != 0) {
            printf("FAIL: poster thread join\n");
            failures += 1;
        }
    }

    size_t delivered = 0u;
    for (;;) {
        deal_completion_frame *frame = deal_platform_take();
        if (frame == NULL) {
            break;
        }
        if (deal_platform_frame_payload_size(frame) != sizeof(poster_record)) {
            printf("FAIL: copied payload size\n");
            failures += 1;
        } else {
            poster_record record;
            memcpy(&record, deal_platform_frame_bytes(frame), sizeof(record));
            poster_record expected;
            poster_record_init(&expected, record.token);
            if (strcmp(record.text, expected.text) != 0) {
                printf("FAIL: copied payload bytes\n");
                failures += 1;
            }
            if (record.token
                    >= (uint32_t)(POSTER_COUNT * FRAMES_PER_POSTER)) {
                printf("FAIL: delivered token\n");
                failures += 1;
            } else if (seen[record.token] != 0) {
                printf("FAIL: duplicate delivery\n");
                failures += 1;
            } else {
                seen[record.token] = 1;
            }
        }
        delivered += 1u;
        deal_platform_frame_free(frame);
    }
    if (delivered != (size_t)(POSTER_COUNT * FRAMES_PER_POSTER)) {
        printf("FAIL: delivered frame count\n");
        failures += 1;
    }
    for (size_t index = 0;
            index < (size_t)(POSTER_COUNT * FRAMES_PER_POSTER); index += 1) {
        if (seen[index] != 1) {
            printf("FAIL: missing delivery\n");
            failures += 1;
        }
    }
    if (deal_root_frame_depth() != 0u || deal_root_scope_depth() != 0u) {
        printf("FAIL: root state\n");
        failures += 1;
    }
    if (failures != 0) {
        return 1;
    }
    printf("gc posters ok\n");
    return 0;
}
