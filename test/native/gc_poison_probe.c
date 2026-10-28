/*
 * test/native/gc_poison_probe.c — the negative control of the collector's
 * sanitizer poison (M1; ISSUE-0906).
 *
 * The published collector poisons a reclaimed payload in an AddressSanitizer
 * build and unpoisons the capacity it hands out again, so a stale read into a
 * reused arena slot is a reported use-after-poison instead of a silent read of a
 * still-mapped address. An ordinary AddressSanitizer build cannot see reuse
 * inside one mapping, so without that poison this fixture would print a stale
 * value and exit zero.
 *
 * The fixture therefore has to observe both halves:
 *
 *   1. an object is allocated, dropped and reclaimed, and the statistics show it
 *      is gone — so the payload really is a reclaimed slot and not a live one;
 *   2. reading the payload through the dropped pointer is a sanitizer finding:
 *      the probe prints nothing and the sanitizer runtime reports
 *      "use-after-poison" on stderr with a nonzero exit.
 *
 * It refuses to compile without AddressSanitizer, so an uninstrumented build can
 * never pass as this evidence, and it is compiled only by the supplementary
 * poison case — never by a production build.
 */
#if defined(__has_feature)
#  if __has_feature(address_sanitizer)
#    define DEAL_POISON_PROBE_ASAN 1
#  endif
#endif
#if !defined(DEAL_POISON_PROBE_ASAN) && defined(__SANITIZE_ADDRESS__)
#  define DEAL_POISON_PROBE_ASAN 1
#endif
#if !defined(DEAL_POISON_PROBE_ASAN)
#error "gc_poison_probe.c is an AddressSanitizer-only fixture"
#endif

#include "deal/native/memory.h"
#include "deal/native/platform.h"

#include <stdio.h>

/* Emitted layout identities (the module's per-layout metadata starts above the
 * runtime-reserved ids). */
#define TEST_LAYOUT_NODE 1024u
#define TEST_NODE_BYTES 16u

static const uint32_t test_node_offsets[] = {0u};

static const deal_layout test_layouts[] = {
    {TEST_LAYOUT_NODE, 0u, TEST_NODE_BYTES, test_node_offsets, 1u}
};

int main(void) {
    (void)setvbuf(stdout, NULL, _IONBF, 0);
    deal_layout_install(test_layouts,
                        sizeof(test_layouts) / sizeof(test_layouts[0]));
    deal_root_frame_enter(1u);
    void *node = deal_alloc(TEST_LAYOUT_NODE, TEST_NODE_BYTES);
    *(uint64_t *)(void *)node = 0x1122334455667788ull;
    deal_root_frame_write(0u, node);
    deal_root_frame_write(0u, NULL);

    /* The object is unreachable: the collection reclaims it and poisons its
     * payload, which the exact statistics confirm before the read below. */
    deal_gc_collect_full();
    deal_gc_statistics_record statistics;
    deal_gc_statistics(&statistics);
    if (statistics.object_count != 0u) {
        printf("poison probe: the dropped object survived\n");
        return 3;
    }
    if (statistics.swept_objects != 1u) {
        printf("poison probe: the dropped object was not swept\n");
        return 4;
    }
    /* A stale read of the reclaimed payload. The sanitizer build reports the
     * use-after-poison here; an uninstrumented or unpoisoned build would print
     * the old bytes and exit zero, which is exactly the defect this fixture
     * exists to rule out. */
    volatile uint64_t *reclaimed = (volatile uint64_t *)node;
    printf("poison probe reached a reclaimed payload: %llu\n",
           (unsigned long long)*reclaimed);
    deal_platform_flush_stdout();
    return 0;
}
