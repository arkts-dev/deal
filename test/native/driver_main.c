/*
 * test/native/driver_main.c — a driver-shape process entry stand-in (H6, G3).
 *
 * The driver build omits `deal/native/main.c` and supplies its own entry: this
 * unit writes `load:start`, calls `deal_project_start()`, writes `load:done`,
 * and only then drives the entry invoke with `main:start`/`main:done`. A
 * carried failure is projected through the error accessors into the retained
 * `DEAL_ERROR_CODE:` and `DEAL_ERROR_SNAPSHOT:` lines with exit 1, exactly as
 * the conformance lane's generated driver does.
 *
 * The snapshot projection here is the driver's own composition from the
 * accessors; it is then compared byte for byte with the runtime serializer, so
 * a field the accessors fail to carry is caught here as well.
 */
#include "deal/native/entry.h"
#include "deal/native/error.h"
#include "deal/native/platform.h"

#include <string.h>

/* A small platform-memory text buffer for the driver's own projection. */
typedef struct {
    char bytes[4096];
    size_t length;
} driver_buffer;

static void driver_append(driver_buffer *buffer, const char *bytes,
                          size_t length) {
    if (buffer->length + length >= sizeof(buffer->bytes)) {
        deal_platform_fault("driver projection overflow");
    }
    memcpy(buffer->bytes + buffer->length, bytes, length);
    buffer->length += length;
}

static void driver_append_string(driver_buffer *buffer, const char *text) {
    static const char hex[] = "0123456789ABCDEF";
    driver_append(buffer, "\"", 1);
    if (text != NULL) {
        for (const unsigned char *cursor = (const unsigned char *)text;
                *cursor != 0; cursor += 1) {
            unsigned char byte = *cursor;
            switch (byte) {
                case '"': driver_append(buffer, "\\\"", 2); break;
                case '\\': driver_append(buffer, "\\\\", 2); break;
                case '\b': driver_append(buffer, "\\b", 2); break;
                case '\t': driver_append(buffer, "\\t", 2); break;
                case '\n': driver_append(buffer, "\\n", 2); break;
                case '\f': driver_append(buffer, "\\f", 2); break;
                case '\r': driver_append(buffer, "\\r", 2); break;
                default:
                    if (byte < 0x20U) {
                        char escaped[6];
                        escaped[0] = '\\';
                        escaped[1] = 'u';
                        escaped[2] = '0';
                        escaped[3] = '0';
                        escaped[4] = hex[(byte >> 4) & 0x0FU];
                        escaped[5] = hex[byte & 0x0FU];
                        driver_append(buffer, escaped, sizeof(escaped));
                    } else {
                        driver_append(buffer, (const char *)cursor, 1);
                    }
                    break;
            }
        }
    }
    driver_append(buffer, "\"", 1);
}

static void driver_append_integer(driver_buffer *buffer, int32_t value) {
    char digits[16];
    size_t length = 0;
    uint32_t magnitude = value < 0 ? (uint32_t)(-(int64_t)value) : (uint32_t)value;
    if (magnitude == 0) {
        digits[length++] = '0';
    } else {
        while (magnitude > 0) {
            digits[length++] = (char)('0' + (int)(magnitude % 10U));
            magnitude /= 10U;
        }
    }
    if (value < 0) {
        driver_append(buffer, "-", 1);
    }
    while (length > 0) {
        driver_append(buffer, &digits[--length], 1);
    }
}

static void driver_key(driver_buffer *buffer, const char *key, int *first) {
    if (!*first) {
        driver_append(buffer, ",", 1);
    }
    *first = 0;
    driver_append_string(buffer, key);
    driver_append(buffer, ":", 1);
}

/* The driver's projection of the carried Error from the accessors. */
static void driver_snapshot(deal_error *error, driver_buffer *buffer) {
    const char *code = NULL;
    const char *message = NULL;
    const char *source_file = NULL;
    const char *expected = NULL;
    const char *actual = NULL;
    const char *cause = NULL;
    int32_t line = 0;
    int32_t column = 0;
    int32_t frames = 0;
    int has_code = deal_error_code(error, &code);
    int has_message = deal_error_message(error, &message);
    int has_file = deal_error_source_file(error, &source_file);
    int has_line = deal_error_line(error, &line);
    int has_column = deal_error_column(error, &column);
    int has_frames = deal_error_frame_count(error, &frames);
    int first = 1;
    driver_append(buffer, "{", 1);
    driver_key(buffer, "code", &first);
    driver_append_string(buffer, has_code ? code : "");
    driver_key(buffer, "message", &first);
    driver_append_string(buffer, has_message ? message : "");
    if (has_file && has_line && has_column) {
        driver_key(buffer, "sourceFile", &first);
        driver_append_string(buffer, source_file);
        driver_key(buffer, "line", &first);
        driver_append_integer(buffer, line);
        driver_key(buffer, "column", &first);
        driver_append_integer(buffer, column);
    }
    if (deal_error_expected(error, &expected)) {
        driver_key(buffer, "expected", &first);
        driver_append_string(buffer, expected);
    }
    if (deal_error_actual(error, &actual)) {
        driver_key(buffer, "actual", &first);
        driver_append_string(buffer, actual);
    }
    if (has_frames) {
        driver_key(buffer, "frames", &first);
        driver_append_integer(buffer, frames);
    }
    if (deal_error_cause_code(error, &cause)) {
        driver_key(buffer, "cause", &first);
        driver_append_string(buffer, cause);
    }
    driver_append(buffer, "}", 1);
    buffer->bytes[buffer->length] = 0;
}

/* The retained terminal projection, from the accessors (R9, H6). */
static int driver_terminal(deal_error *error) {
    driver_buffer buffer;
    const char *code = NULL;
    buffer.length = 0;
    buffer.bytes[0] = 0;
    if (!deal_error_code(error, &code)) {
        code = "";
    }
    driver_snapshot(error, &buffer);
    char *runtime_snapshot = deal_error_snapshot(error);
    if (strcmp(runtime_snapshot, buffer.bytes) != 0) {
        deal_error_snapshot_release(runtime_snapshot);
        deal_platform_fault("driver projection disagrees with the runtime "
                            "snapshot");
    }
    deal_error_snapshot_release(runtime_snapshot);
    deal_platform_write_stdout("DEAL_ERROR_CODE: ", 17);
    deal_platform_write_stdout(code, strlen(code));
    deal_platform_write_stdout("\n", 1);
    deal_platform_write_stdout("DEAL_ERROR_SNAPSHOT: ", 21);
    deal_platform_write_stdout(buffer.bytes, buffer.length);
    deal_platform_write_stdout("\n", 1);
    deal_platform_flush_stdout();
    deal_platform_exit(1);
    return 1;
}

int main(void) {
    deal_platform_write_stdout("load:start\n", strlen("load:start\n"));
    deal_outcome started = deal_project_start();
    if (started.error != NULL) {
        return driver_terminal(started.error);
    }
    deal_platform_write_stdout("load:done\n", strlen("load:done\n"));
    deal_platform_write_stdout("main:start\n", strlen("main:start\n"));
    deal_outcome invoked = deal_project_run_main();
    if (invoked.error != NULL) {
        return driver_terminal(invoked.error);
    }
    deal_platform_write_stdout("main:done\n", strlen("main:done\n"));
    deal_platform_flush_stdout();
    return 0;
}
