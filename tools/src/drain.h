/*
 * DEALPG4 bounded drain utility.
 *
 * Owns (dealpg4-launcher-core D2, dealpg4-time-stream-utilities D4/D5):
 *  - the single 1048576-byte cap constant, which is both the per-stream
 *    retention cap and the named relay-queue cap the supervisor/outer
 *    event loops consume (compile-time single constant: queued bytes
 *    never exceed retained bytes),
 *  - the non-blocking capped drain: retain the first 1048576 bytes,
 *    insert the byte-stable truncation marker at the cut point when the
 *    stream exceeds the cap, read and discard the remainder, and require
 *    read-side EOF for completion (no drain success before EOF).
 *
 * The drain performs no dynamic allocation: the only storage is the fixed
 * retained window inside the context (exactly cap + marker bytes, the
 * maximum possible retained stream) plus a transient fixed-size read chunk
 * on the pump stack frame. The discard tail is never buffered anywhere.
 *
 * The drain never blocks: it reads only an O_NONBLOCK fd (verified on
 * every pump; a blocking fd is refused), returns on EAGAIN, and the owner
 * interleaves draining with its ppoll set so no read can suspend a
 * deadline.
 */
#ifndef DEALPG4_DRAIN_H
#define DEALPG4_DRAIN_H

#include <stddef.h>
#include <stdint.h>

/* === The one cap constant ==============================================
 * 1 MiB, owned here. It is both the per-stream retention cap and the
 * relay-queue cap (dealpg4-time-stream-utilities D5): the consuming event
 * loops' per-record pending queues never exceed the retained bytes, so
 * relay flow is never larger than the drain budget.
 */
#define DEALPG4_DRAIN_CAP_BYTES 1048576
#define DEALPG4_RELAY_QUEUE_CAP_BYTES DEALPG4_DRAIN_CAP_BYTES

/* Compile-time identity: the relay-queue cap is the retention cap. */
_Static_assert(DEALPG4_RELAY_QUEUE_CAP_BYTES == DEALPG4_DRAIN_CAP_BYTES,
               "DEALPG4 relay-queue cap must equal the drain retention cap");

/* === Truncation marker ==================================================
 * Byte-stable marker appended to the retained stream exactly at the cut
 * point (immediately after retained byte 1048576) when the stream exceeds
 * the cap (dealpg4-time-stream-utilities D4). Fixed text, part of the
 * captured payload — never a protocol field.
 */
#define DEALPG4_DRAIN_TRUNCATION_MARKER "\n[STREAM TRUNCATED at 1 MiB]\n"
#define DEALPG4_DRAIN_TRUNCATION_MARKER_LEN \
    (sizeof(DEALPG4_DRAIN_TRUNCATION_MARKER) - 1)

/* Transient read window used by dealpg4_drain_pump: a fixed-size stack
 * chunk, not a retained buffer; discarded bytes never accumulate. */
#define DEALPG4_DRAIN_READ_CHUNK_BYTES 4096

/* === Drain context ======================================================
 * One context per stream. The retained window is embedded, so the only
 * allocation a drain ever occupies is exactly cap + marker bytes (plus
 * the transient pump chunk); nothing else is ever buffered.
 */
typedef struct dealpg4_drain_ctx {
    /* Retained stream: at most DEALPG4_DRAIN_CAP_BYTES data bytes plus the
     * truncation marker appended at the cut point. */
    unsigned char retained[DEALPG4_DRAIN_CAP_BYTES
                           + DEALPG4_DRAIN_TRUNCATION_MARKER_LEN];
    size_t retained_len;  /* bytes stored in retained (data + marker) */
    uint64_t total_read;  /* everything read from the fd, incl. discard tail */
    int truncated;        /* 1 once the marker was inserted at the cut point */
    int eof;              /* 1 once read-side EOF was observed */
    int failed;           /* 1 once a non-EAGAIN read error failed the drain */
    int error;            /* errno of the failure while failed */
} dealpg4_drain_ctx;

typedef enum dealpg4_drain_status {
    DEALPG4_DRAIN_AGAIN = 0,  /* EAGAIN before EOF; owner waits for POLLIN */
    DEALPG4_DRAIN_EOF = 1,    /* read-side EOF observed; drain complete */
    DEALPG4_DRAIN_ERROR = -1  /* read error other than EAGAIN/EINTR, or a
                                 blocking fd (ctx->error holds errno) */
} dealpg4_drain_status;

/* Reset the context to empty. */
void dealpg4_drain_init(dealpg4_drain_ctx *ctx);

/* Pump the non-blocking fd: repeated read(2) until EAGAIN (or EOF/error).
 * Retains the first DEALPG4_DRAIN_CAP_BYTES bytes; when the stream
 * exceeds the cap, appends the truncation marker exactly at the cut point
 * and reads/discards the remainder. EOF is mandatory: DEALPG4_DRAIN_EOF is
 * returned only after a read returned 0. Pumping after a terminal status
 * is a no-op returning the same status; after a failure, ctx->failed is 1
 * and ctx->error holds the errno. */
dealpg4_drain_status dealpg4_drain_pump(dealpg4_drain_ctx *ctx, int fd);

/* 1 once read-side EOF was observed (drain complete). */
int dealpg4_drain_eof(const dealpg4_drain_ctx *ctx);

/* 1 once the stream exceeded the cap and the marker was inserted. */
int dealpg4_drain_truncated(const dealpg4_drain_ctx *ctx);

/* Failure errno, or 0 when the drain has not failed. */
int dealpg4_drain_error(const dealpg4_drain_ctx *ctx);

#endif
