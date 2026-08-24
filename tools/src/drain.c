/*
 * DEALPG4 bounded drain utility.
 * Implementation of the capped non-blocking drain; see drain.h for the
 * contracts (dealpg4-time-stream-utilities D4/D5).
 */
#define _POSIX_C_SOURCE 200809L

#include "drain.h"

#include <errno.h>
#include <fcntl.h>
#include <string.h>
#include <unistd.h>

void dealpg4_drain_init(dealpg4_drain_ctx *ctx)
{
    ctx->retained_len = 0;
    ctx->total_read = 0;
    ctx->truncated = 0;
    ctx->eof = 0;
    ctx->failed = 0;
    ctx->error = 0;
}

/* Retain up to the cap and append the marker exactly once at the cut
 * point — before the first byte past the cap is retained; the rest of the
 * chunk is discarded and never buffered. */
static void dealpg4_drain_accept(dealpg4_drain_ctx *ctx,
                                 const unsigned char *data, size_t len)
{
    if (!ctx->truncated) {
        size_t room = DEALPG4_DRAIN_CAP_BYTES - ctx->retained_len;

        if (len <= room) {
            memcpy(ctx->retained + ctx->retained_len, data, len);
            ctx->retained_len += len;
        } else {
            memcpy(ctx->retained + ctx->retained_len, data, room);
            ctx->retained_len += room; /* == DEALPG4_DRAIN_CAP_BYTES */
            memcpy(ctx->retained + ctx->retained_len,
                   DEALPG4_DRAIN_TRUNCATION_MARKER,
                   DEALPG4_DRAIN_TRUNCATION_MARKER_LEN);
            ctx->retained_len += DEALPG4_DRAIN_TRUNCATION_MARKER_LEN;
            ctx->truncated = 1;
        }
    }
    /* The len - min(len, room) bytes past the cut point are discarded:
     * never buffered, never retained. */
}

dealpg4_drain_status dealpg4_drain_pump(dealpg4_drain_ctx *ctx, int fd)
{
    unsigned char chunk[DEALPG4_DRAIN_READ_CHUNK_BYTES];
    int flags;

    if (ctx->failed)
        return DEALPG4_DRAIN_ERROR;
    if (ctx->eof)
        return DEALPG4_DRAIN_EOF;

    /* Structural no-block guarantee: the owner's contract is an O_NONBLOCK
     * read fd; refuse a blocking fd so no read call here can ever
     * suspend a deadline. */
    flags = fcntl(fd, F_GETFL);
    if (flags < 0) {
        ctx->failed = 1;
        ctx->error = errno;
        return DEALPG4_DRAIN_ERROR;
    }
    if ((flags & O_NONBLOCK) == 0) {
        ctx->failed = 1;
        ctx->error = EINVAL;
        return DEALPG4_DRAIN_ERROR;
    }

    for (;;) {
        ssize_t r = read(fd, chunk, sizeof(chunk));

        if (r > 0) {
            dealpg4_drain_accept(ctx, chunk, (size_t)r);
            ctx->total_read += (uint64_t)r;
            continue;
        }
        if (r == 0) {
            ctx->eof = 1;
            return DEALPG4_DRAIN_EOF;
        }
        if (errno == EINTR)
            continue;
        if (errno == EAGAIN || errno == EWOULDBLOCK)
            return DEALPG4_DRAIN_AGAIN;
        ctx->failed = 1;
        ctx->error = errno;
        return DEALPG4_DRAIN_ERROR;
    }
}

int dealpg4_drain_eof(const dealpg4_drain_ctx *ctx)
{
    return ctx->eof;
}

int dealpg4_drain_truncated(const dealpg4_drain_ctx *ctx)
{
    return ctx->truncated;
}

int dealpg4_drain_error(const dealpg4_drain_ctx *ctx)
{
    return ctx->failed ? ctx->error : 0;
}
