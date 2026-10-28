/*
 * deal/native/json.c — the `json` service group implementation.
 *
 * The strict RFC-8259 scanner, the decoder, the stack-safe encoder and the two
 * emitted class walks of `json.h` (`llvm-native-runtime-value-model` R6, R10;
 * `llvm-native-memory-management` M5).
 *
 * Numeric conversion. Two pure C library conversions are used and nothing
 * else from the C library: `strtod` turns one grammar-validated numeric token
 * into the binary64 value nearest to it (round to nearest, ties to even,
 * preserving signed zero, overflow to infinity and underflow to zero or a
 * subnormal), and `snprintf` renders the shortest round-tripping decimal
 * spelling. Neither is an operating-system access — no file descriptor, clock,
 * environment entry, process state or allocator is reached — so the
 * `platform` group stays the only group that calls the operating system. The
 * one further C library call pair (`newlocale`/`uselocale`) only selects the
 * fixed `C` locale object of this translation unit for the calling thread and
 * restores the caller's; it reads no environment entry (the locale name is a
 * literal, not the ambient one), opens no file and allocates nothing the
 * runtime owns.
 *
 * Both conversions are also locale-independent (R6). `strtod` and the decimal
 * conversion of `snprintf` read the *current* locale's decimal point, and a host
 * may have selected a comma-decimal locale before calling in: `strtod` would
 * then stop at the '.' of `1.5` and `[1.5]` would decode silently as `1`, while
 * an encoder would emit `1,5`. The group therefore runs both conversions with
 * one private C locale selected for the calling thread (`uselocale`), restoring
 * the caller's locale afterwards, and it verifies that the decoder consumed the
 * whole grammar-validated token instead of trusting a partial conversion. A
 * numeric observation therefore never depends on the ambient locale.
 *
 * Rooting (M5). Every managed object this group creates is held in one scoped
 * root set for the whole invocation, so no partially built graph can be
 * reclaimed at a later allocation's safepoint; the scope is popped on every
 * exit path and the answered value is the caller's to root.
 */
/* The private numeric locale needs the POSIX 2008 thread-locale interface
 * (`newlocale`, `uselocale`), which the pinned `-std=c11` flag list hides
 * unless it is requested before the first system header. */
#define _POSIX_C_SOURCE 200809L

#include "deal/native/json.h"

#include <locale.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "deal/native/container.h"
#include "deal/native/descriptor.h"
#include "deal/native/error.h"
#include "deal/native/memory.h"
#include "deal/native/platform.h"
#include "deal/native/text.h"
#include "deal/native/value.h"

/* =========================================================================
 * The private numeric locale (R6)
 * ========================================================================= */

/* The one C locale both numeric conversions run under. It is created on first
 * use and never released: the runtime has one configuration for its whole
 * process lifetime, and a fault exits rather than leaking a half-initialized
 * conversion path. */
static locale_t deal_json_numeric_locale(void) {
    static locale_t cached;
    static int created;
    if (created == 0) {
        cached = newlocale(LC_ALL_MASK, "C", (locale_t)0);
        if (cached == (locale_t)0) {
            deal_platform_fault("json: numeric locale unavailable");
        }
        created = 1;
    }
    return cached;
}

/*
 * Decodes one grammar-validated numeric token to binary64 under the private C
 * locale. The conversion must consume the whole token: a token the scanner
 * accepted but the conversion stopped inside (the comma-decimal symptom) is a
 * producer defect, never a silently truncated number.
 */
static double deal_json_decode_number(const unsigned char *token,
                                      size_t length) {
    locale_t previous = uselocale(deal_json_numeric_locale());
    char *end = NULL;
    double value = strtod((const char *)token, &end);
    (void)uselocale(previous);
    if (end != (const char *)token + length) {
        deal_platform_fault("json: numeric conversion stopped inside the token");
    }
    return value;
}

/* Renders one magnitude with `%.*e` under the private C locale. */
static int deal_json_format_exponent(char *buffer, size_t size, int precision,
                                     double magnitude) {
    locale_t previous = uselocale(deal_json_numeric_locale());
    int written = snprintf(buffer, size, "%.*e", precision, magnitude);
    (void)uselocale(previous);
    if (written <= 0 || (size_t)written >= size) {
        deal_platform_fault("json: number text overflow");
    }
    return written;
}

/* =========================================================================
 * Invocation-local scratch (R6: no partial output, no shared state)
 * ========================================================================= */

/* One growable byte buffer in platform storage. */
typedef struct {
    unsigned char *bytes;
    size_t length;
    size_t capacity;
} deal_json_buffer;

static void deal_json_buffer_reserve(deal_json_buffer *buffer,
                                     size_t additional) {
    if (additional > SIZE_MAX - buffer->length) {
        deal_platform_fault("json: scratch size overflow");
    }
    size_t required = buffer->length + additional;
    if (required <= buffer->capacity) {
        return;
    }
    size_t capacity = buffer->capacity == 0u ? 64u : buffer->capacity;
    while (capacity < required) {
        if (capacity > SIZE_MAX / 2u) {
            deal_platform_fault("json: scratch size overflow");
        }
        capacity *= 2u;
    }
    unsigned char *grown = deal_platform_allocate(capacity);
    if (grown == NULL) {
        deal_platform_fault("json: scratch allocation failed");
    }
    if (buffer->bytes != NULL) {
        memcpy(grown, buffer->bytes, buffer->length);
        deal_platform_release(buffer->bytes);
    }
    buffer->bytes = grown;
    buffer->capacity = capacity;
}

static void deal_json_buffer_append(deal_json_buffer *buffer,
                                    const void *bytes, size_t length) {
    if (length == 0u) {
        return;
    }
    deal_json_buffer_reserve(buffer, length);
    memcpy(buffer->bytes + buffer->length, bytes, length);
    buffer->length += length;
}

static void deal_json_buffer_byte(deal_json_buffer *buffer,
                                  unsigned char byte) {
    deal_json_buffer_reserve(buffer, 1u);
    buffer->bytes[buffer->length] = byte;
    buffer->length += 1u;
}

/* Appends one Unicode scalar value's UTF-8 encoding (a scalar sequence). */
static void deal_json_buffer_scalar(deal_json_buffer *buffer,
                                    uint32_t scalar) {
    unsigned char bytes[4];
    size_t length;
    if (scalar < 0x80u) {
        bytes[0] = (unsigned char)scalar;
        length = 1u;
    } else if (scalar < 0x800u) {
        bytes[0] = (unsigned char)(0xC0u | (scalar >> 6));
        bytes[1] = (unsigned char)(0x80u | (scalar & 0x3Fu));
        length = 2u;
    } else if (scalar < 0x10000u) {
        bytes[0] = (unsigned char)(0xE0u | (scalar >> 12));
        bytes[1] = (unsigned char)(0x80u | ((scalar >> 6) & 0x3Fu));
        bytes[2] = (unsigned char)(0x80u | (scalar & 0x3Fu));
        length = 3u;
    } else {
        bytes[0] = (unsigned char)(0xF0u | (scalar >> 18));
        bytes[1] = (unsigned char)(0x80u | ((scalar >> 12) & 0x3Fu));
        bytes[2] = (unsigned char)(0x80u | ((scalar >> 6) & 0x3Fu));
        bytes[3] = (unsigned char)(0x80u | (scalar & 0x3Fu));
        length = 4u;
    }
    deal_json_buffer_append(buffer, bytes, length);
}

static void deal_json_buffer_release(deal_json_buffer *buffer) {
    deal_platform_release(buffer->bytes);
    buffer->bytes = NULL;
    buffer->length = 0u;
    buffer->capacity = 0u;
}

/* The declared-type admission of the undefined datum (defined with the
 * encoder's descriptor helpers below). */
static int deal_json_admits_undefined(deal_descriptor *descriptor);

/* Holds one freshly created managed object for the invocation (M5). */
static void deal_json_hold(void *referent) {
    (void)deal_root_scope_hold(referent);
}

/* =========================================================================
 * The closed parse-defect reasons (the shared scanner's own texts)
 * ========================================================================= */

static const char *const deal_json_reason_unexpected_character =
    "unexpected character";
static const char *const deal_json_reason_unterminated_string =
    "unterminated string";
static const char *const deal_json_reason_unterminated_object =
    "unterminated object";
static const char *const deal_json_reason_unterminated_array =
    "unterminated array";
static const char *const deal_json_reason_invalid_escape = "invalid escape";
static const char *const deal_json_reason_unpaired_surrogate =
    "unpaired surrogate escape";
static const char *const deal_json_reason_invalid_number = "invalid number";
static const char *const deal_json_reason_leading_zero = "leading zero";
static const char *const deal_json_reason_missing_key = "missing key";
static const char *const deal_json_reason_missing_colon = "missing colon";
static const char *const deal_json_reason_missing_comma = "missing comma";
static const char *const deal_json_reason_trailing_content =
    "trailing content";
static const char *const deal_json_reason_unexpected_end =
    "unexpected end of input";
static const char *const deal_json_reason_duplicate_key = "duplicate key";

/* One parse defect: the closed reason and the one-based UTF-8 byte offset. */
typedef struct {
    int failed;
    const char *reason;
    size_t byte_offset;
} deal_json_failure;

static int deal_json_fail(deal_json_failure *failure, const char *reason,
                          size_t byte_offset) {
    if (!failure->failed) {
        failure->failed = 1;
        failure->reason = reason;
        failure->byte_offset = byte_offset;
    }
    return 1;
}

/* =========================================================================
 * The scanner cursor (the shared scanner's own operations)
 * ========================================================================= */

/*
 * The scan position over one decoded input: the code points, the one-based
 * UTF-8 byte offset of each of them and the total byte length. Positions are
 * always code-point aligned, so a reported offset never lands inside a
 * multi-byte encoding and a multi-byte scalar counts its full UTF-8 length.
 */
typedef struct {
    const uint32_t *code_points;
    const size_t *byte_offsets;
    size_t count;
    size_t total_bytes;
    size_t position;
} deal_json_cursor;

static int deal_json_at_end(const deal_json_cursor *cursor) {
    return cursor->position >= cursor->count;
}

static int deal_json_peek(const deal_json_cursor *cursor) {
    return deal_json_at_end(cursor) ? -1 : (int)cursor->code_points[cursor->position];
}

static void deal_json_advance(deal_json_cursor *cursor) {
    if (!deal_json_at_end(cursor)) {
        cursor->position += 1u;
    }
}

static size_t deal_json_offset_of_current(const deal_json_cursor *cursor) {
    if (cursor->position >= cursor->count) {
        return cursor->total_bytes + 1u;
    }
    return cursor->byte_offsets[cursor->position] + 1u;
}

static int deal_json_is_ws(int code_point) {
    return code_point == 0x20 || code_point == 0x09 || code_point == 0x0A
        || code_point == 0x0D;
}

static int deal_json_is_digit(int code_point) {
    return code_point >= '0' && code_point <= '9';
}

static int deal_json_hex_digit(int code_point) {
    if (code_point >= '0' && code_point <= '9') {
        return code_point - '0';
    }
    if (code_point >= 'a' && code_point <= 'f') {
        return code_point - 'a' + 10;
    }
    if (code_point >= 'A' && code_point <= 'F') {
        return code_point - 'A' + 10;
    }
    return -1;
}

static void deal_json_skip_ws(deal_json_cursor *cursor) {
    while (!deal_json_at_end(cursor) && deal_json_is_ws(deal_json_peek(cursor))) {
        deal_json_advance(cursor);
    }
}

/* Exactly four hex digits; a non-hex digit or the end of input is an invalid
 * escape. */
static int deal_json_parse_hex4(deal_json_cursor *cursor,
                                deal_json_failure *failure, int *out) {
    int value = 0;
    for (int digit_index = 0; digit_index < 4; digit_index += 1) {
        if (deal_json_at_end(cursor)) {
            return deal_json_fail(failure, deal_json_reason_invalid_escape,
                                  deal_json_offset_of_current(cursor));
        }
        int digit = deal_json_hex_digit(deal_json_peek(cursor));
        if (digit < 0) {
            return deal_json_fail(failure, deal_json_reason_invalid_escape,
                                  deal_json_offset_of_current(cursor));
        }
        deal_json_advance(cursor);
        value = value * 16 + digit;
    }
    *out = value;
    return 0;
}

/*
 * Scans one JSON string token into a scalar-valid `deal_string`: the escapes
 * decode to their scalar values, a high surrogate escape requires its paired
 * low surrogate escape, a lone surrogate escape and an unknown escape are the
 * closed defect reasons, and a raw control scalar is an unexpected character.
 */
static int deal_json_parse_string(deal_json_cursor *cursor,
                                  deal_json_failure *failure,
                                  deal_string **out) {
    *out = NULL;
    deal_json_buffer text;
    memset(&text, 0, sizeof(text));
    deal_json_advance(cursor);   /* the opening quote */
    while (1) {
        if (deal_json_at_end(cursor)) {
            deal_json_buffer_release(&text);
            return deal_json_fail(failure, deal_json_reason_unterminated_string,
                                  deal_json_offset_of_current(cursor));
        }
        int code_point = deal_json_peek(cursor);
        if (code_point == '"') {
            deal_json_advance(cursor);
            break;
        }
        if (code_point == '\\') {
            deal_json_advance(cursor);
            if (deal_json_at_end(cursor)) {
                deal_json_buffer_release(&text);
                return deal_json_fail(failure,
                                      deal_json_reason_unterminated_string,
                                      deal_json_offset_of_current(cursor));
            }
            int escaped = deal_json_peek(cursor);
            switch (escaped) {
            case '"': deal_json_advance(cursor);
                      deal_json_buffer_byte(&text, '"'); continue;
            case '\\': deal_json_advance(cursor);
                       deal_json_buffer_byte(&text, '\\'); continue;
            case '/': deal_json_advance(cursor);
                      deal_json_buffer_byte(&text, '/'); continue;
            case 'b': deal_json_advance(cursor);
                      deal_json_buffer_byte(&text, '\b'); continue;
            case 'f': deal_json_advance(cursor);
                      deal_json_buffer_byte(&text, '\f'); continue;
            case 'n': deal_json_advance(cursor);
                      deal_json_buffer_byte(&text, '\n'); continue;
            case 'r': deal_json_advance(cursor);
                      deal_json_buffer_byte(&text, '\r'); continue;
            case 't': deal_json_advance(cursor);
                      deal_json_buffer_byte(&text, '\t'); continue;
            case 'u': {
                deal_json_advance(cursor);
                int first = 0;
                if (deal_json_parse_hex4(cursor, failure, &first)) {
                    deal_json_buffer_release(&text);
                    return 1;
                }
                if (first >= 0xD800 && first <= 0xDBFF) {
                    if (deal_json_at_end(cursor) || deal_json_peek(cursor) != '\\') {
                        deal_json_buffer_release(&text);
                        return deal_json_fail(failure,
                            deal_json_reason_unpaired_surrogate,
                            deal_json_offset_of_current(cursor));
                    }
                    deal_json_advance(cursor);
                    if (deal_json_at_end(cursor) || deal_json_peek(cursor) != 'u') {
                        deal_json_buffer_release(&text);
                        return deal_json_fail(failure,
                            deal_json_reason_unpaired_surrogate,
                            deal_json_offset_of_current(cursor));
                    }
                    deal_json_advance(cursor);
                    int second = 0;
                    if (deal_json_parse_hex4(cursor, failure, &second)) {
                        deal_json_buffer_release(&text);
                        return 1;
                    }
                    if (second < 0xDC00 || second > 0xDFFF) {
                        deal_json_buffer_release(&text);
                        return deal_json_fail(failure,
                            deal_json_reason_unpaired_surrogate,
                            deal_json_offset_of_current(cursor));
                    }
                    uint32_t scalar = 0x10000u
                        + (((uint32_t)first - 0xD800u) << 10)
                        + ((uint32_t)second - 0xDC00u);
                    deal_json_buffer_scalar(&text, scalar);
                    continue;
                }
                if (first >= 0xDC00 && first <= 0xDFFF) {
                    deal_json_buffer_release(&text);
                    return deal_json_fail(failure,
                        deal_json_reason_unpaired_surrogate,
                        deal_json_offset_of_current(cursor));
                }
                deal_json_buffer_scalar(&text, (uint32_t)first);
                continue;
            }
            default:
                deal_json_buffer_release(&text);
                return deal_json_fail(failure, deal_json_reason_invalid_escape,
                                      deal_json_offset_of_current(cursor));
            }
        }
        if (code_point < 0x20) {
            deal_json_buffer_release(&text);
            return deal_json_fail(failure,
                                  deal_json_reason_unexpected_character,
                                  deal_json_offset_of_current(cursor));
        }
        deal_json_advance(cursor);
        deal_json_buffer_scalar(&text, (uint32_t)code_point);
    }
    deal_string *string = deal_text_new(text.bytes, text.length);
    deal_json_buffer_release(&text);
    deal_json_hold(string);
    *out = string;
    return 0;
}

/* One scanned numeric token and its binary64 value. */
static int deal_json_parse_number(deal_json_cursor *cursor,
                                  deal_json_failure *failure, double *out) {
    deal_json_buffer token;
    memset(&token, 0, sizeof(token));
    int status = 0;
    if (deal_json_peek(cursor) == '-') {
        deal_json_buffer_byte(&token, '-');
        deal_json_advance(cursor);
    }
    if (deal_json_at_end(cursor)) {
        status = deal_json_fail(failure, deal_json_reason_invalid_number,
                                deal_json_offset_of_current(cursor));
        goto done;
    }
    int code_point = deal_json_peek(cursor);
    if (!deal_json_is_digit(code_point)) {
        status = deal_json_fail(failure, deal_json_reason_invalid_number,
                                deal_json_offset_of_current(cursor));
        goto done;
    }
    if (code_point == '0') {
        deal_json_buffer_byte(&token, '0');
        deal_json_advance(cursor);
        if (!deal_json_at_end(cursor) && deal_json_is_digit(deal_json_peek(cursor))) {
            status = deal_json_fail(failure, deal_json_reason_leading_zero,
                                    deal_json_offset_of_current(cursor));
            goto done;
        }
    } else {
        while (!deal_json_at_end(cursor) && deal_json_is_digit(deal_json_peek(cursor))) {
            deal_json_buffer_byte(&token, (unsigned char)deal_json_peek(cursor));
            deal_json_advance(cursor);
        }
    }
    if (!deal_json_at_end(cursor) && deal_json_peek(cursor) == '.') {
        deal_json_buffer_byte(&token, '.');
        deal_json_advance(cursor);
        if (deal_json_at_end(cursor) || !deal_json_is_digit(deal_json_peek(cursor))) {
            status = deal_json_fail(failure, deal_json_reason_invalid_number,
                                    deal_json_offset_of_current(cursor));
            goto done;
        }
        while (!deal_json_at_end(cursor) && deal_json_is_digit(deal_json_peek(cursor))) {
            deal_json_buffer_byte(&token, (unsigned char)deal_json_peek(cursor));
            deal_json_advance(cursor);
        }
    }
    if (!deal_json_at_end(cursor)
            && (deal_json_peek(cursor) == 'e' || deal_json_peek(cursor) == 'E')) {
        deal_json_buffer_byte(&token, (unsigned char)deal_json_peek(cursor));
        deal_json_advance(cursor);
        if (!deal_json_at_end(cursor)
                && (deal_json_peek(cursor) == '+' || deal_json_peek(cursor) == '-')) {
            deal_json_buffer_byte(&token, (unsigned char)deal_json_peek(cursor));
            deal_json_advance(cursor);
        }
        if (deal_json_at_end(cursor) || !deal_json_is_digit(deal_json_peek(cursor))) {
            status = deal_json_fail(failure, deal_json_reason_invalid_number,
                                    deal_json_offset_of_current(cursor));
            goto done;
        }
        while (!deal_json_at_end(cursor) && deal_json_is_digit(deal_json_peek(cursor))) {
            deal_json_buffer_byte(&token, (unsigned char)deal_json_peek(cursor));
            deal_json_advance(cursor);
        }
    }
    deal_json_buffer_byte(&token, 0);
    *out = deal_json_decode_number(token.bytes, token.length - 1u);
done:
    deal_json_buffer_release(&token);
    return status;
}

/* One literal token (`true`, `false`, `null`). */
static int deal_json_parse_literal(deal_json_cursor *cursor,
                                   deal_json_failure *failure,
                                   const char *word) {
    for (size_t index = 0; word[index] != 0; index += 1u) {
        if (deal_json_at_end(cursor)) {
            return deal_json_fail(failure, deal_json_reason_unexpected_end,
                                  deal_json_offset_of_current(cursor));
        }
        if (deal_json_peek(cursor) != (int)(unsigned char)word[index]) {
            return deal_json_fail(failure, deal_json_reason_unexpected_character,
                                  deal_json_offset_of_current(cursor));
        }
        deal_json_advance(cursor);
    }
    return 0;
}

/* =========================================================================
 * The stack-safe decoder (R6)
 * ========================================================================= */

/* One open container of the decoder's explicit frame stack. */
typedef struct {
    int is_object;
    deal_table *table;              /* object frame */
    deal_array *array;              /* array frame: decoded datum slots */
    deal_string **keys;             /* object frame: decoded keys so far */
    size_t key_count;
    size_t key_capacity;
    deal_string *pending_key;
    uint32_t state;
} deal_json_frame;

/* The parser's cursor and frame stack. */
typedef struct {
    deal_json_cursor cursor;
    deal_json_frame *frames;
    size_t frame_count;
    size_t frame_capacity;
    deal_descriptor *unknown_element;
} deal_json_parser;

#define DEAL_JSON_STATE_OBJECT_FIRST 0u
#define DEAL_JSON_STATE_OBJECT_KEY   1u
#define DEAL_JSON_STATE_VALUE        2u
#define DEAL_JSON_STATE_OBJECT_AFTER 3u
#define DEAL_JSON_STATE_ARRAY_FIRST  4u
#define DEAL_JSON_STATE_ARRAY_AFTER  5u

static void deal_json_parser_push(deal_json_parser *parser) {
    if (parser->frame_count == parser->frame_capacity) {
        size_t capacity = parser->frame_capacity == 0u
            ? 16u : parser->frame_capacity * 2u;
        deal_json_frame *grown =
            deal_platform_allocate(capacity * sizeof(*grown));
        if (grown == NULL) {
            deal_platform_fault("json: frame stack allocation failed");
        }
        if (parser->frames != NULL) {
            memcpy(grown, parser->frames,
                   parser->frame_count * sizeof(*grown));
            deal_platform_release(parser->frames);
        }
        parser->frames = grown;
        parser->frame_capacity = capacity;
    }
    parser->frame_count += 1u;
}

static deal_json_frame *deal_json_parser_top(deal_json_parser *parser) {
    return &parser->frames[parser->frame_count - 1u];
}

/* 1 when the frame's decoded key list already carries `key` (R6 duplicates use
 * decoded equality, so an escaped spelling of the same key is a duplicate). */
static int deal_json_frame_has_key(deal_json_frame *frame, deal_string *key) {
    for (size_t index = 0; index < frame->key_count; index += 1u) {
        if (deal_text_compare(frame->keys[index], key) == 0) {
            return 1;
        }
    }
    return 0;
}

static void deal_json_frame_add_key(deal_json_frame *frame, deal_string *key) {
    if (frame->key_count == frame->key_capacity) {
        size_t capacity = frame->key_capacity == 0u
            ? 8u : frame->key_capacity * 2u;
        deal_string **grown =
            deal_platform_allocate(capacity * sizeof(*grown));
        if (grown == NULL) {
            deal_platform_fault("json: key list allocation failed");
        }
        if (frame->keys != NULL) {
            memcpy(grown, frame->keys, frame->key_count * sizeof(*grown));
            deal_platform_release(frame->keys);
        }
        frame->keys = grown;
        frame->key_capacity = capacity;
    }
    frame->keys[frame->key_count] = key;
    frame->key_count += 1u;
}

static void deal_json_frame_release(deal_json_frame *frame) {
    deal_platform_release(frame->keys);
    frame->keys = NULL;
    frame->key_count = 0u;
    frame->key_capacity = 0u;
}

static void deal_json_parser_release(deal_json_parser *parser) {
    for (size_t index = 0; index < parser->frame_count; index += 1u) {
        deal_json_frame_release(&parser->frames[index]);
    }
    deal_platform_release(parser->frames);
    parser->frames = NULL;
    parser->frame_count = 0u;
    parser->frame_capacity = 0u;
}

/* The decoded value of one open container. */
static deal_data deal_json_frame_value(deal_json_frame *frame) {
    if (frame->is_object) {
        return deal_data_of_reference(DEAL_DATA_TABLE, frame->table);
    }
    return deal_data_of_reference(DEAL_DATA_ARRAY, frame->array);
}

/*
 * Parses one value at the cursor: a scalar answers its datum and a container
 * opens one frame (answering nothing). The caller then drives the open
 * container through the frame loop.
 */
static int deal_json_scan_value(deal_json_parser *parser,
                                deal_json_failure *failure, deal_data *out,
                                int *opened) {
    deal_json_cursor *cursor = &parser->cursor;
    *opened = 0;
    if (deal_json_at_end(cursor)) {
        return deal_json_fail(failure, deal_json_reason_unexpected_end,
                              deal_json_offset_of_current(cursor));
    }
    int code_point = deal_json_peek(cursor);
    switch (code_point) {
    case '{': {
        deal_json_advance(cursor);
        deal_json_parser_push(parser);
        deal_json_frame *frame = deal_json_parser_top(parser);
        memset(frame, 0, sizeof(*frame));
        frame->is_object = 1;
        frame->table = deal_container_table_new();
        deal_json_hold(frame->table);
        frame->state = DEAL_JSON_STATE_OBJECT_FIRST;
        *opened = 1;
        return 0;
    }
    case '[': {
        deal_json_advance(cursor);
        deal_json_parser_push(parser);
        deal_json_frame *frame = deal_json_parser_top(parser);
        memset(frame, 0, sizeof(*frame));
        frame->is_object = 0;
        frame->array = deal_container_array_new_typed(
            DEAL_LAYOUT_ELEMENT_DATUM,
            deal_descriptor_reference(parser->unknown_element), 0u);
        deal_json_hold(frame->array);
        frame->state = DEAL_JSON_STATE_ARRAY_FIRST;
        *opened = 1;
        return 0;
    }
    case '"': {
        deal_string *string = NULL;
        if (deal_json_parse_string(cursor, failure, &string)) {
            return 1;
        }
        *out = deal_data_of_reference(DEAL_DATA_STRING, string);
        return 0;
    }
    case 't': {
        if (deal_json_parse_literal(cursor, failure, "true")) {
            return 1;
        }
        *out = deal_data_of_boolean(1);
        return 0;
    }
    case 'f': {
        if (deal_json_parse_literal(cursor, failure, "false")) {
            return 1;
        }
        *out = deal_data_of_boolean(0);
        return 0;
    }
    case 'n': {
        if (deal_json_parse_literal(cursor, failure, "null")) {
            return 1;
        }
        *out = deal_data_of_null();
        return 0;
    }
    default:
        break;
    }
    if (code_point == '-' || deal_json_is_digit(code_point)) {
        double number = 0.0;
        if (deal_json_parse_number(cursor, failure, &number)) {
            return 1;
        }
        *out = deal_data_of_number(number);
        return 0;
    }
    return deal_json_fail(failure, deal_json_reason_unexpected_character,
                          deal_json_offset_of_current(cursor));
}

/*
 * The stack-safe parse of one complete value: an explicit frame stack replaces
 * the recursive descent, so a finite nested document has no nesting cap and no
 * C stack bound. Every transition, defect reason and one-based UTF-8 byte
 * offset is the shared scanner's.
 */
static int deal_json_parse_document(deal_json_parser *parser,
                                    deal_json_failure *failure,
                                    deal_data *out) {
    deal_json_cursor *cursor = &parser->cursor;
    deal_data completed;
    memset(&completed, 0, sizeof(completed));
    int have_completed = 0;
    while (1) {
        if (have_completed) {
            if (parser->frame_count == 0u) {
                *out = completed;
                return 0;
            }
            deal_json_frame *parent = deal_json_parser_top(parser);
            if (parent->is_object) {
                deal_container_table_write(parent->table, parent->pending_key,
                                           &completed);
                parent->pending_key = NULL;
                parent->state = DEAL_JSON_STATE_OBJECT_AFTER;
            } else {
                deal_container_array_append(parent->array, &completed);
                parent->state = DEAL_JSON_STATE_ARRAY_AFTER;
            }
            have_completed = 0;
            continue;
        }
        if (parser->frame_count == 0u) {
            int opened = 0;
            if (deal_json_scan_value(parser, failure, &completed, &opened)) {
                return 1;
            }
            have_completed = !opened;
            continue;
        }
        deal_json_frame *frame = deal_json_parser_top(parser);
        switch (frame->state) {
        case DEAL_JSON_STATE_OBJECT_FIRST: {
            deal_json_skip_ws(cursor);
            if (!deal_json_at_end(cursor) && deal_json_peek(cursor) == '}') {
                deal_json_advance(cursor);
                completed = deal_json_frame_value(frame);
                deal_json_frame_release(frame);
                parser->frame_count -= 1u;
                have_completed = 1;
            } else {
                frame->state = DEAL_JSON_STATE_OBJECT_KEY;
            }
            continue;
        }
        case DEAL_JSON_STATE_OBJECT_KEY: {
            deal_json_skip_ws(cursor);
            if (deal_json_at_end(cursor)) {
                return deal_json_fail(failure, deal_json_reason_unexpected_end,
                                      deal_json_offset_of_current(cursor));
            }
            if (deal_json_peek(cursor) != '"') {
                return deal_json_fail(failure, deal_json_reason_missing_key,
                                      deal_json_offset_of_current(cursor));
            }
            /* The duplicate defect's position is the second key's opening
             * quote (a one-based UTF-8 byte offset), and decoded equality
             * makes an escaped spelling of the same key a duplicate too. */
            size_t key_offset = deal_json_offset_of_current(cursor);
            deal_string *key = NULL;
            if (deal_json_parse_string(cursor, failure, &key)) {
                return 1;
            }
            if (deal_json_frame_has_key(frame, key)) {
                return deal_json_fail(failure, deal_json_reason_duplicate_key,
                                      key_offset);
            }
            deal_json_frame_add_key(frame, key);
            deal_json_skip_ws(cursor);
            if (deal_json_at_end(cursor) || deal_json_peek(cursor) != ':') {
                return deal_json_fail(failure, deal_json_reason_missing_colon,
                                      deal_json_offset_of_current(cursor));
            }
            deal_json_advance(cursor);
            frame->pending_key = key;
            frame->state = DEAL_JSON_STATE_VALUE;
            continue;
        }
        case DEAL_JSON_STATE_VALUE: {
            deal_json_skip_ws(cursor);
            if (deal_json_at_end(cursor)) {
                return deal_json_fail(failure, deal_json_reason_unexpected_end,
                                      deal_json_offset_of_current(cursor));
            }
            int opened = 0;
            if (deal_json_scan_value(parser, failure, &completed, &opened)) {
                return 1;
            }
            have_completed = !opened;
            continue;
        }
        case DEAL_JSON_STATE_OBJECT_AFTER: {
            deal_json_skip_ws(cursor);
            if (deal_json_at_end(cursor)) {
                return deal_json_fail(failure,
                                      deal_json_reason_unterminated_object,
                                      deal_json_offset_of_current(cursor));
            }
            int code_point = deal_json_peek(cursor);
            if (code_point == ',') {
                deal_json_advance(cursor);
                frame->state = DEAL_JSON_STATE_OBJECT_KEY;
            } else if (code_point == '}') {
                deal_json_advance(cursor);
                completed = deal_json_frame_value(frame);
                deal_json_frame_release(frame);
                parser->frame_count -= 1u;
                have_completed = 1;
            } else {
                return deal_json_fail(failure, deal_json_reason_missing_comma,
                                      deal_json_offset_of_current(cursor));
            }
            continue;
        }
        case DEAL_JSON_STATE_ARRAY_FIRST: {
            deal_json_skip_ws(cursor);
            if (!deal_json_at_end(cursor) && deal_json_peek(cursor) == ']') {
                deal_json_advance(cursor);
                completed = deal_json_frame_value(frame);
                deal_json_frame_release(frame);
                parser->frame_count -= 1u;
                have_completed = 1;
            } else {
                frame->state = DEAL_JSON_STATE_VALUE;
            }
            continue;
        }
        case DEAL_JSON_STATE_ARRAY_AFTER: {
            deal_json_skip_ws(cursor);
            if (deal_json_at_end(cursor)) {
                return deal_json_fail(failure,
                                      deal_json_reason_unterminated_array,
                                      deal_json_offset_of_current(cursor));
            }
            int code_point = deal_json_peek(cursor);
            if (code_point == ',') {
                deal_json_advance(cursor);
                frame->state = DEAL_JSON_STATE_VALUE;
            } else if (code_point == ']') {
                deal_json_advance(cursor);
                completed = deal_json_frame_value(frame);
                deal_json_frame_release(frame);
                parser->frame_count -= 1u;
                have_completed = 1;
            } else {
                return deal_json_fail(failure, deal_json_reason_missing_comma,
                                      deal_json_offset_of_current(cursor));
            }
            continue;
        }
        default:
            deal_platform_fault("json: decoder frame state outside the closed set");
        }
    }
}

/* Decodes one string's bytes into its code points and their byte offsets. */
static void deal_json_decode_input(deal_string *text, uint32_t **code_points,
                                   size_t **byte_offsets, size_t *count,
                                   size_t *total_bytes) {
    size_t scalar_count = deal_text_scalar_length(text);
    const unsigned char *bytes = deal_text_bytes(text);
    size_t byte_length = deal_text_byte_length(text);
    uint32_t *points = NULL;
    size_t *offsets = NULL;
    if (scalar_count != 0u) {
        points = deal_platform_allocate(scalar_count * sizeof(*points));
        offsets = deal_platform_allocate(scalar_count * sizeof(*offsets));
        if (points == NULL || offsets == NULL) {
            deal_platform_fault("json: input decoding allocation failed");
        }
    }
    size_t index = 0u;
    size_t scalar = 0u;
    while (index < byte_length) {
        unsigned char lead = bytes[index];
        uint32_t value;
        size_t advance;
        if (lead < 0x80u) {
            value = lead;
            advance = 1u;
        } else if (lead < 0xE0u) {
            value = ((uint32_t)(lead & 0x1Fu) << 6)
                | (uint32_t)(bytes[index + 1u] & 0x3Fu);
            advance = 2u;
        } else if (lead < 0xF0u) {
            value = ((uint32_t)(lead & 0x0Fu) << 12)
                | ((uint32_t)(bytes[index + 1u] & 0x3Fu) << 6)
                | (uint32_t)(bytes[index + 2u] & 0x3Fu);
            advance = 3u;
        } else {
            value = ((uint32_t)(lead & 0x07u) << 18)
                | ((uint32_t)(bytes[index + 1u] & 0x3Fu) << 12)
                | ((uint32_t)(bytes[index + 2u] & 0x3Fu) << 6)
                | (uint32_t)(bytes[index + 3u] & 0x3Fu);
            advance = 4u;
        }
        points[scalar] = value;
        offsets[scalar] = index;
        index += advance;
        scalar += 1u;
    }
    *code_points = points;
    *byte_offsets = offsets;
    *count = scalar;
    *total_bytes = byte_length;
}

/* =========================================================================
 * The parse entry (R6)
 * ========================================================================= */

deal_outcome_data deal_json_parse(deal_string *text, const deal_origin *origin) {
    deal_outcome_data outcome;
    memset(&outcome, 0, sizeof(outcome));
    if (text == NULL) {
        deal_platform_fault("json: null parse input");
    }
    if (origin == NULL) {
        deal_platform_fault("json: missing parse origin");
    }
    deal_json_parser parser;
    memset(&parser, 0, sizeof(parser));
    parser.unknown_element = deal_descriptor_intern("unknown");
    /* The decoded roots are this helper's own intermediates until they are
     * answered, and the scope is popped on the failure path too (M5). */
    deal_root_scope_push();
    uint32_t *code_points = NULL;
    size_t *byte_offsets = NULL;
    size_t count = 0u;
    size_t total_bytes = 0u;
    deal_json_decode_input(text, &code_points, &byte_offsets, &count,
                           &total_bytes);
    parser.cursor.code_points = code_points;
    parser.cursor.byte_offsets = byte_offsets;
    parser.cursor.count = count;
    parser.cursor.total_bytes = total_bytes;
    parser.cursor.position = 0u;
    deal_json_failure failure;
    failure.failed = 0;
    failure.reason = NULL;
    failure.byte_offset = 0u;
    deal_data value;
    memset(&value, 0, sizeof(value));
    int failed = 0;
    deal_json_skip_ws(&parser.cursor);
    failed = deal_json_parse_document(&parser, &failure, &value);
    if (!failed) {
        deal_json_skip_ws(&parser.cursor);
        if (!deal_json_at_end(&parser.cursor)) {
            failed = deal_json_fail(&failure,
                                    deal_json_reason_trailing_content,
                                    deal_json_offset_of_current(&parser.cursor));
        }
    }
    /* A failed parse publishes nothing: the frame stack and the input decoding
     * are released and only the arm is answered. */
    deal_json_parser_release(&parser);
    deal_platform_release(code_points);
    deal_platform_release(byte_offsets);
    if (failed) {
        /* The arm's declared `{oneBasedByteOffset}` parameter: the canonical
         * decimal spelling of the defect's one-based UTF-8 byte offset. */
        char offset_text[24];
        int written = snprintf(offset_text, sizeof(offset_text), "%llu",
                               (unsigned long long)failure.byte_offset);
        if (written <= 0 || (size_t)written >= sizeof(offset_text)) {
            deal_platform_fault("json: defect offset text overflow");
        }
        deal_arm_parameter parameters[2];
        parameters[0].name = "oneBasedByteOffset";
        parameters[0].value = offset_text;
        parameters[1].name = "reason";
        parameters[1].value = failure.reason;
        outcome.error = deal_error_materialize(DEAL_ARM_ID_JSON_PARSE_ERROR,
            parameters, 2u, NULL, NULL, origin, NULL);
        deal_root_scope_pop();
        return outcome;
    }
    outcome.value = value;
    deal_root_scope_pop();
    return outcome;
}

/* =========================================================================
 * The encoder's shared pieces (R6)
 * ========================================================================= */

/* The invocation's path-local cycle-detection stack (R6): the identities of
 * the containers currently open on the walk. */
typedef struct {
    const void **items;
    size_t count;
    size_t capacity;
} deal_json_active;

/* One walk position: the frame it hangs off plus the member name or element
 * index it appends. A frame keeps this link and its own segment instead of a
 * concatenated path, and the dotted/indexed spelling is materialized only when
 * a failure projects it, so the encoder's scratch stays linear in nesting depth
 * (R6 stack-safe traversal, M5 invocation-local scratch). */
typedef struct {
    size_t base;                       /* parent frame index, SIZE_MAX at the root */
    uint32_t kind;                     /* 0 = root, 1 = member, 2 = element */
    const unsigned char *name;         /* member: the key's bytes, never owned */
    size_t name_length;
    size_t element;                    /* element: the array index */
} deal_json_walk_position;

/* One open container of the encoder's work stack. */
typedef struct {
    uint32_t kind;                     /* 0 = object, 1 = array, 2 = class */
    deal_table *table;                 /* object frame */
    deal_string **keys;                /* object frame: own-key order */
    size_t key_count;
    deal_array *array;                 /* array frame */
    deal_descriptor *element_descriptor;
    const deal_json_class_plan *plan;  /* class frame */
    deal_data instance;
    const void *identity;
    size_t index;
    size_t emitted;
    deal_json_walk_position position;  /* the frame's own walk position */
} deal_json_encode_frame;

/* The encoder: its invocation-local text, the work stack, the cycle path and
 * the emitted module's plan lookup. */
typedef struct {
    deal_json_buffer text;
    const deal_origin *origin;
    int walk_failures;                 /* 1 = the class walk (JSON_TO_WALK) */
    deal_json_active active;
    deal_json_encode_frame *frames;
    size_t frame_count;
    size_t frame_capacity;
    deal_json_plan_lookup lookup;
    void *lookup_context;
    deal_error *error;                 /* the rendered declaration-order failure */
} deal_json_encoder;

/* One value's position in the walk: its check presentation and, in a declared
 * position, the descriptor the walk validates it against. */
typedef struct {
    deal_check_input input;
    deal_descriptor *descriptor;
} deal_json_position;

/* 1 when the v1.5 rules omit this value: a present `undefined` datum at a
 * declared position whose type admits `undefined` (a nullish member or a
 * result-only `void`). Function values are omitted at descriptor-less object
 * members and encode as `null` array elements; a declared position that admits
 * neither reaches the check instead. */
static int deal_json_omitted(deal_json_position position) {
    if (position.input.presentation != DEAL_PRESENT_DATUM) {
        return 0;
    }
    if (position.input.datum.tag != DEAL_DATA_UNDEFINED) {
        return 0;
    }
    return position.descriptor != NULL
        && deal_json_admits_undefined(position.descriptor);
}

static deal_error *deal_json_cycle_error(deal_json_encoder *encoder);
static deal_error *deal_json_walk_error(deal_json_encoder *encoder,
                                        const char *path, const char *token);
static deal_error *deal_json_unsupported_error(deal_json_encoder *encoder,
                                               const char *path,
                                               const deal_check_input *input);

static void deal_json_active_release(deal_json_active *active) {
    deal_platform_release(active->items);
    active->items = NULL;
    active->count = 0u;
    active->capacity = 0u;
}

/* Pushes one open container's identity; answers 1 when it is already open (a
 * path-local re-entry, which is the closed cycle failure). */
static int deal_json_active_push(deal_json_active *active, const void *identity) {
    for (size_t index = 0; index < active->count; index += 1u) {
        if (active->items[index] == identity) {
            return 1;
        }
    }
    if (active->count == active->capacity) {
        size_t capacity = active->capacity == 0u
            ? 8u : active->capacity * 2u;
        const void **grown = deal_platform_allocate(capacity * sizeof(*grown));
        if (grown == NULL) {
            deal_platform_fault("json: cycle path allocation failed");
        }
        if (active->items != NULL) {
            memcpy(grown, active->items, active->count * sizeof(*grown));
            deal_platform_release(active->items);
        }
        active->items = grown;
        active->capacity = capacity;
    }
    active->items[active->count] = identity;
    active->count += 1u;
    return 0;
}

static void deal_json_active_pop(deal_json_active *active) {
    if (active->count == 0u) {
        deal_platform_fault("json: cycle path underflow");
    }
    active->count -= 1u;
}

/* 1 when a segment appended at frame `base` is separated from its parent by
 * the member dot, i.e. when the parent's own spelling is not the empty root. */
static int deal_json_walk_dot(size_t base) {
    return base != SIZE_MAX && base != 0u;
}

/* The byte span one segment contributes: `.name`, `[index]` or nothing. */
static size_t deal_json_walk_span(const deal_json_walk_position *position,
                                  int dot) {
    switch (position->kind) {
    case 1u:
        return position->name_length + (dot ? 1u : 0u);
    case 2u: {
        size_t digits = 1u;
        size_t value = position->element;
        while (value >= 10u) {
            value /= 10u;
            digits += 1u;
        }
        return digits + 2u;
    }
    default:
        return 0u;
    }
}

/* Writes one segment's bytes at `out`, without its terminator. */
static void deal_json_walk_write(unsigned char *out,
                                 const deal_json_walk_position *position,
                                 int dot) {
    size_t at = 0u;
    if (position->kind == 1u) {
        if (dot) {
            out[at] = '.';
            at += 1u;
        }
        if (position->name_length != 0u) {
            memcpy(out + at, position->name, position->name_length);
        }
        return;
    }
    if (position->kind == 2u) {
        unsigned char digits[24];
        size_t digits_length = 0u;
        size_t value = position->element;
        do {
            digits[digits_length] = (unsigned char)('0' + (int)(value % 10u));
            digits_length += 1u;
            value /= 10u;
        } while (value != 0u);
        out[at] = '[';
        at += 1u;
        for (size_t index = digits_length; index > 0u; index -= 1u) {
            out[at] = digits[index - 1u];
            at += 1u;
        }
        out[at] = ']';
    }
}

/* Materializes one walk position's dotted/indexed spelling. Only a failure
 * projection calls this, and its cost is bounded by the failing position: the
 * ancestor chain is counted once, one buffer is allocated, and the segments are
 * written leaf-first from its end, so no prefix is ever copied twice. */
static char *deal_json_path_render(deal_json_encoder *encoder,
                                   const deal_json_walk_position *position) {
    size_t length = 0u;
    size_t base = position->base;
    const deal_json_walk_position *segment = position;
    for (;;) {
        size_t span = deal_json_walk_span(segment, deal_json_walk_dot(base));
        if (span > SIZE_MAX - length) {
            deal_platform_fault("json: walk position size overflow");
        }
        length += span;
        if (base == SIZE_MAX) {
            break;
        }
        segment = &encoder->frames[base].position;
        base = segment->base;
    }
    char *path = deal_platform_allocate(length + 1u);
    if (path == NULL) {
        deal_platform_fault("json: walk position allocation failed");
    }
    size_t at = length;
    base = position->base;
    segment = position;
    for (;;) {
        int dot = deal_json_walk_dot(base);
        at -= deal_json_walk_span(segment, dot);
        deal_json_walk_write((unsigned char *)path + at, segment, dot);
        if (base == SIZE_MAX) {
            break;
        }
        segment = &encoder->frames[base].position;
        base = segment->base;
    }
    path[length] = 0;
    return path;
}

/* Appends one string's RFC-8259 body: quote and backslash escaped, the named
 * short escapes, every other control scalar as `\u00XX`, every other scalar
 * emitted as its own UTF-8 bytes. */
static void deal_json_escape_bytes(deal_json_buffer *out,
                                   const unsigned char *bytes, size_t length) {
    static const char hex_digits[] = "0123456789abcdef";
    deal_json_buffer_byte(out, '"');
    for (size_t index = 0; index < length; index += 1u) {
        unsigned char byte = bytes[index];
        switch (byte) {
        case '"':
            deal_json_buffer_append(out, "\\\"", 2u);
            break;
        case '\\':
            deal_json_buffer_append(out, "\\\\", 2u);
            break;
        case '\b':
            deal_json_buffer_append(out, "\\b", 2u);
            break;
        case '\f':
            deal_json_buffer_append(out, "\\f", 2u);
            break;
        case '\n':
            deal_json_buffer_append(out, "\\n", 2u);
            break;
        case '\r':
            deal_json_buffer_append(out, "\\r", 2u);
            break;
        case '\t':
            deal_json_buffer_append(out, "\\t", 2u);
            break;
        default:
            if (byte < 0x20u) {
                char escape[6];
                escape[0] = '\\';
                escape[1] = 'u';
                escape[2] = '0';
                escape[3] = '0';
                escape[4] = hex_digits[(byte >> 4) & 0x0Fu];
                escape[5] = hex_digits[byte & 0x0Fu];
                deal_json_buffer_append(out, escape, sizeof(escape));
            } else {
                deal_json_buffer_byte(out, byte);
            }
            break;
        }
    }
    deal_json_buffer_byte(out, '"');
}

/* Appends one string value's RFC-8259 body. */
static void deal_json_escape(deal_json_buffer *out, const deal_string *text) {
    deal_json_escape_bytes(out, deal_text_bytes(text),
                           deal_text_byte_length(text));
}

/*
 * Appends one finite binary64 value's canonical text: the shortest decimal
 * digit string that rounds back to the value, in plain notation for
 * `1e-3 <= |value| < 1e7` and in `d.dddE<exponent>` form otherwise (the
 * `Double.toString` spelling the shared model's `Value.Number` row carries).
 * The caller handles zero, infinities and NaN.
 */
static void deal_json_number_text(deal_json_buffer *out, double value) {
    char buffer[64];
    double magnitude = value < 0.0 ? -value : value;
    /* The shortest significant-digit count whose decimal rounds back to the
     * value: at most 17 digits always suffice for a binary64 value. */
    size_t precision = 1u;
    for (; precision <= 17u; precision += 1u) {
        int written = deal_json_format_exponent(buffer, sizeof(buffer),
                                                (int)(precision - 1u),
                                                magnitude);
        if (deal_json_decode_number((const unsigned char *)buffer,
                                    (size_t)written) == magnitude) {
            break;
        }
    }
    char digits[24];
    size_t digit_count = 0u;
    size_t position = 0u;
    while (buffer[position] != 0 && buffer[position] != 'e'
            && buffer[position] != 'E') {
        if (buffer[position] >= '0' && buffer[position] <= '9') {
            if (digit_count + 1u >= sizeof(digits)) {
                deal_platform_fault("json: digit count overflow");
            }
            digits[digit_count] = buffer[position];
            digit_count += 1u;
        }
        position += 1u;
    }
    while (digit_count > 1u && digits[digit_count - 1u] == '0') {
        digit_count -= 1u;
    }
    int exponent = 0;
    if (buffer[position] == 'e' || buffer[position] == 'E') {
        position += 1u;
        int negative = 0;
        if (buffer[position] == '+' || buffer[position] == '-') {
            negative = buffer[position] == '-';
            position += 1u;
        }
        while (buffer[position] >= '0' && buffer[position] <= '9') {
            exponent = exponent * 10 + (buffer[position] - '0');
            position += 1u;
        }
        if (negative) {
            exponent = -exponent;
        }
    }
    if (value < 0.0) {
        deal_json_buffer_byte(out, '-');
    }
    if (exponent >= -3 && exponent < 7) {
        if (exponent >= 0) {
            size_t integer_digits = (size_t)exponent + 1u;
            for (size_t index = 0; index < integer_digits; index += 1u) {
                deal_json_buffer_byte(out,
                    (unsigned char)(index < digit_count ? digits[index] : '0'));
            }
            deal_json_buffer_byte(out, '.');
            if (integer_digits >= digit_count) {
                deal_json_buffer_byte(out, '0');
            } else {
                deal_json_buffer_append(out, digits + integer_digits,
                                        digit_count - integer_digits);
            }
        } else {
            deal_json_buffer_byte(out, '0');
            deal_json_buffer_byte(out, '.');
            for (int index = 0; index < -exponent - 1; index += 1) {
                deal_json_buffer_byte(out, '0');
            }
            deal_json_buffer_append(out, digits, digit_count);
        }
        return;
    }
    deal_json_buffer_byte(out, (unsigned char)digits[0]);
    deal_json_buffer_byte(out, '.');
    if (digit_count > 1u) {
        deal_json_buffer_append(out, digits + 1u, digit_count - 1u);
    } else {
        deal_json_buffer_byte(out, '0');
    }
    deal_json_buffer_byte(out, 'E');
    char exponent_text[16];
    size_t exponent_length = 0u;
    int magnitude_exponent = exponent;
    if (magnitude_exponent < 0) {
        deal_json_buffer_byte(out, '-');
        magnitude_exponent = -magnitude_exponent;
    }
    do {
        exponent_text[exponent_length] =
            (char)('0' + (magnitude_exponent % 10));
        exponent_length += 1u;
        magnitude_exponent /= 10;
    } while (magnitude_exponent != 0 && exponent_length < sizeof(exponent_text));
    for (size_t index = exponent_length; index > 0u; index -= 1u) {
        deal_json_buffer_byte(out, (unsigned char)exponent_text[index - 1u]);
    }
}

/* 1 when the descriptor admits the undefined datum (a nullish descriptor with
 * the undefined member, or the `undefined`/`void` kinds). */
static int deal_json_admits_undefined(deal_descriptor *descriptor) {
    switch (deal_descriptor_type(descriptor)) {
    case DEAL_DESCRIPTOR_UNDEFINED:
    case DEAL_DESCRIPTOR_VOID:
        return 1;
    case DEAL_DESCRIPTOR_NULLISH:
        return deal_descriptor_admits_undefined(descriptor);
    default:
        return 0;
    }
}

/* 1 when the key is a canonical array-index key (an integer in `0..2^32-2`
 * with no leading zero), writing its value. */
static int deal_json_array_index_key(const deal_string *key,
                                     unsigned long long *out) {
    const unsigned char *bytes = deal_text_bytes(key);
    size_t length = deal_text_byte_length(key);
    if (length == 0u || length > 10u) {
        return 0;
    }
    if (bytes[0] == '0' && length != 1u) {
        return 0;
    }
    unsigned long long value = 0u;
    for (size_t index = 0; index < length; index += 1u) {
        if (bytes[index] < '0' || bytes[index] > '9') {
            return 0;
        }
        value = value * 10u + (unsigned long long)(bytes[index] - '0');
    }
    if (value > 4294967294ull) {
        return 0;
    }
    *out = value;
    return 1;
}

/*
 * The own-key order of one record (R6): array-index keys ascending first, then
 * the remaining keys in creation order.
 */
static deal_string **deal_json_ordered_keys(deal_table *table, size_t *out_count) {
    size_t count = deal_container_table_count(table);
    *out_count = 0u;
    if (count == 0u) {
        return NULL;
    }
    deal_string **ordered = deal_platform_allocate(count * sizeof(*ordered));
    if (ordered == NULL) {
        deal_platform_fault("json: key order allocation failed");
    }
    size_t ordered_count = 0u;
    /* The array-index keys, inserted in ascending order. */
    for (size_t index = 0; index < count; index += 1u) {
        deal_string *key = deal_container_table_key_at(table, index);
        unsigned long long value = 0u;
        if (!deal_json_array_index_key(key, &value)) {
            continue;
        }
        size_t at = ordered_count;
        while (at > 0u) {
            unsigned long long previous = 0u;
            (void)deal_json_array_index_key(ordered[at - 1u], &previous);
            if (previous <= value) {
                break;
            }
            ordered[at] = ordered[at - 1u];
            at -= 1u;
        }
        ordered[at] = key;
        ordered_count += 1u;
    }
    /* Then every other key in creation order. */
    for (size_t index = 0; index < count; index += 1u) {
        deal_string *key = deal_container_table_key_at(table, index);
        unsigned long long value = 0u;
        if (deal_json_array_index_key(key, &value)) {
            continue;
        }
        ordered[ordered_count] = key;
        ordered_count += 1u;
    }
    *out_count = ordered_count;
    return ordered;
}

/* The persistent datum tag of one managed reference element, from the array's
 * recorded element descriptor (its base kind for a nullish element type). */
static deal_data_tag deal_json_reference_tag(deal_descriptor *element_descriptor) {
    deal_descriptor *base = element_descriptor;
    if (base != NULL && deal_descriptor_type(base) == DEAL_DESCRIPTOR_NULLISH) {
        base = deal_descriptor_inner(base);
    }
    if (base == NULL) {
        deal_platform_fault("json: reference element without its descriptor");
    }
    switch (deal_descriptor_type(base)) {
    case DEAL_DESCRIPTOR_STRING:
        return DEAL_DATA_STRING;
    case DEAL_DESCRIPTOR_ARRAY:
        return DEAL_DATA_ARRAY;
    case DEAL_DESCRIPTOR_RECORD:
        return DEAL_DATA_TABLE;
    case DEAL_DESCRIPTOR_CLASS:
        return DEAL_DATA_CLASS;
    case DEAL_DESCRIPTOR_NUMBER:
    case DEAL_DESCRIPTOR_BOOLEAN:
        break;
    default:
        break;
    }
    deal_platform_fault("json: element descriptor outside the reference set");
}

/* The carried class atom of a class (or nullish class) descriptor, or NULL. */
static const char *deal_json_class_atom_of(deal_descriptor *descriptor) {
    deal_descriptor *base = descriptor;
    if (base != NULL && deal_descriptor_type(base) == DEAL_DESCRIPTOR_NULLISH) {
        base = deal_descriptor_inner(base);
    }
    if (base == NULL || deal_descriptor_type(base) != DEAL_DESCRIPTOR_CLASS) {
        return NULL;
    }
    return deal_descriptor_class_atom(base);
}

/* One array element as its closed check presentation. The storage kind is the
 * element layout's and the element's type is the array's recorded persistent
 * element descriptor (C3, R4). */
static void deal_json_element_at(deal_array *array, size_t index,
                                 deal_descriptor *element_descriptor,
                                 deal_check_input *input) {
    uint32_t layout = deal_container_array_element_layout(array);
    const void *slot = deal_container_array_element(array, index);
    memset(input, 0, sizeof(*input));
    input->presentation = DEAL_PRESENT_DATUM;
    input->unicode_defect = DEAL_UNICODE_MALFORMED;
    switch (layout) {
    case DEAL_LAYOUT_ELEMENT_DATUM:
        input->datum = *(const deal_data *)slot;
        break;
    case DEAL_LAYOUT_ELEMENT_REFERENCE: {
        void *referent = *(void *const *)slot;
        if (deal_value_is_null(referent)) {
            input->datum = deal_data_of_null();
        } else if (deal_value_is_undefined(referent)) {
            input->datum = deal_data_of_undefined();
        } else {
            input->datum = deal_data_of_reference(
                deal_json_reference_tag(element_descriptor), referent);
        }
        break;
    }
    case DEAL_LAYOUT_ELEMENT_NUMBER:
        input->datum = deal_data_of_number(*(const double *)slot);
        break;
    case DEAL_LAYOUT_ELEMENT_BOOLEAN:
        input->datum = deal_data_of_boolean(*(const uint8_t *)slot != 0u);
        break;
    default:
        deal_platform_fault("json: array element layout outside the runtime boxed set");
    }
    if (input->datum.tag == DEAL_DATA_CLASS) {
        input->class_atom = deal_json_class_atom_of(element_descriptor);
    }
}

/* The walk token of one descriptor-less position: the typed-boundary token, and
 * the `table` carrier projection for a class instance whose atom a dynamic
 * record member does not carry. */
static const char *deal_json_walk_token(const deal_check_input *input) {
    if (input->presentation == DEAL_PRESENT_DATUM
            && input->datum.tag == DEAL_DATA_CLASS
            && input->class_atom == NULL) {
        return "table";
    }
    if (input->presentation == DEAL_PRESENT_DATUM
            && input->datum.tag == DEAL_DATA_FUNCTION
            && input->signature == NULL) {
        return "function";
    }
    return deal_check_token(input);
}

/* The generic encoder's carrier-kind token of one value (the std/json row's
 * closed vocabulary). */
static const char *deal_json_carrier_token(const deal_check_input *input) {
    if (input->presentation == DEAL_PRESENT_MISSING) {
        return "nil";
    }
    if (input->presentation != DEAL_PRESENT_DATUM) {
        return "table";
    }
    switch ((deal_data_tag)input->datum.tag) {
    case DEAL_DATA_UNDEFINED:
        return "undefined";
    case DEAL_DATA_BOOLEAN:
        return "boolean";
    case DEAL_DATA_NUMBER:
        return "number";
    case DEAL_DATA_STRING:
        return "string";
    case DEAL_DATA_FUNCTION:
        return "function";
    case DEAL_DATA_NULL:
    case DEAL_DATA_ARRAY:
    case DEAL_DATA_TABLE:
    case DEAL_DATA_CLASS:
    case DEAL_DATA_ERROR:
        return "table";
    }
    deal_platform_fault("json: datum tag outside the closed set");
}

/* The pinned expected field of the std/json rejection arm (its own pinned
 * text, mirrored by the parity suite from the failure-arm authority). */
static const char *const deal_json_stringify_expected =
    "string, number, boolean, or table";

/* =========================================================================
 * The stack-safe encoder core (R6)
 * ========================================================================= */

static void deal_json_encoder_release(deal_json_encoder *encoder) {
    for (size_t index = 0; index < encoder->frame_count; index += 1u) {
        deal_platform_release(encoder->frames[index].keys);
    }
    deal_platform_release(encoder->frames);
    encoder->frames = NULL;
    encoder->frame_count = 0u;
    encoder->frame_capacity = 0u;
    deal_json_active_release(&encoder->active);
    deal_json_buffer_release(&encoder->text);
}

static deal_json_encode_frame *deal_json_encoder_push(deal_json_encoder *encoder) {
    if (encoder->frame_count == encoder->frame_capacity) {
        size_t capacity = encoder->frame_capacity == 0u
            ? 8u : encoder->frame_capacity * 2u;
        deal_json_encode_frame *grown =
            deal_platform_allocate(capacity * sizeof(*grown));
        if (grown == NULL) {
            deal_platform_fault("json: encode frame allocation failed");
        }
        if (encoder->frames != NULL) {
            memcpy(grown, encoder->frames,
                   encoder->frame_count * sizeof(*grown));
            deal_platform_release(encoder->frames);
        }
        encoder->frames = grown;
        encoder->frame_capacity = capacity;
    }
    deal_json_encode_frame *frame = &encoder->frames[encoder->frame_count];
    memset(frame, 0, sizeof(*frame));
    encoder->frame_count += 1u;
    return frame;
}

/* Closes the top frame: its own terminator, its cycle-path entry and its
 * storage. */
static void deal_json_encoder_close(deal_json_encoder *encoder) {
    deal_json_encode_frame *frame =
        &encoder->frames[encoder->frame_count - 1u];
    deal_json_buffer_byte(&encoder->text, frame->kind == 1u ? ']' : '}');
    if (frame->identity != NULL) {
        deal_json_active_pop(&encoder->active);
    }
    deal_platform_release(frame->keys);
    frame->keys = NULL;
    encoder->frame_count -= 1u;
}

/* Opens one container value: the path-local cycle check first, then the frame
 * and its opening text. Answers 1 on the closed cycle failure. */
static int deal_json_open_container(deal_json_encoder *encoder,
                                    const deal_check_input *input,
                                    deal_descriptor *descriptor,
                                    const deal_json_walk_position *walk) {
    void *referent = input->datum.reference;
    if (referent == NULL) {
        deal_platform_fault("json: container datum without its referent");
    }
    if (deal_json_active_push(&encoder->active, referent)) {
        return 1;
    }
    deal_json_encode_frame *frame = deal_json_encoder_push(encoder);
    frame->identity = referent;
    frame->position = *walk;
    switch ((deal_data_tag)input->datum.tag) {
    case DEAL_DATA_ARRAY: {
        frame->kind = 1u;
        frame->array = (deal_array *)referent;
        uint32_t reference = deal_container_array_element_descriptor(frame->array);
        frame->element_descriptor = reference == 0u
            ? NULL : deal_descriptor_of_reference(reference);
        deal_json_buffer_byte(&encoder->text, '[');
        return 0;
    }
    case DEAL_DATA_TABLE: {
        frame->kind = 0u;
        frame->table = (deal_table *)referent;
        frame->keys = deal_json_ordered_keys(frame->table, &frame->key_count);
        deal_json_buffer_byte(&encoder->text, '{');
        return 0;
    }
    case DEAL_DATA_CLASS: {
        const char *atom = input->class_atom;
        if (atom == NULL) {
            atom = deal_json_class_atom_of(descriptor);
        }
        if (atom == NULL) {
            deal_platform_fault("json: class value without its canonical atom");
        }
        const deal_json_class_plan *plan =
            encoder->lookup == NULL ? NULL : encoder->lookup(atom,
                                                             encoder->lookup_context);
        if (plan == NULL) {
            deal_platform_fault("json: no emitted plan for the class value");
        }
        frame->kind = 2u;
        frame->plan = plan;
        frame->instance = input->datum;
        deal_json_buffer_byte(&encoder->text, '{');
        return 0;
    }
    default:
        break;
    }
    deal_platform_fault("json: container tag outside the closed set");
}

/* Emits one position: `0` emitted, `1` omitted (no JSON representation at an
 * object member), `2` the closed cycle failure, `3` the unsupported failure.
 * The encoder's text is cleared by a failure and the arm is rendered only by
 * the caller that owns the invocation. */
static int deal_json_emit(deal_json_encoder *encoder,
                          deal_json_position position,
                          const deal_json_walk_position *walk) {
    if (position.input.presentation != DEAL_PRESENT_DATUM) {
        return 3;
    }
    deal_data_tag tag = (deal_data_tag)position.input.datum.tag;
    if (tag == DEAL_DATA_UNDEFINED || tag == DEAL_DATA_FUNCTION) {
        /* The v1.5 rules: an undefined/function-valued object member is
         * omitted and such an array element encodes as `null`. A declared
         * position that does not admit `undefined` reaches the check below
         * instead. */
        if (position.descriptor != NULL
                && tag == DEAL_DATA_UNDEFINED
                && deal_json_admits_undefined(position.descriptor)) {
            return 1;
        }
        if (position.descriptor != NULL) {
            return 3;
        }
        if (tag == DEAL_DATA_UNDEFINED || tag == DEAL_DATA_FUNCTION) {
            return 1;
        }
    }
    if (position.descriptor != NULL) {
        deal_check_context context;
        context.origin = encoder->origin;
        context.parameter_index = 0;
        deal_outcome outcome = deal_check_value(position.descriptor,
            position.input, DEAL_BOUNDARY_TYPE_DESCRIPTOR, &context);
        if (outcome.error != NULL) {
            return 3;
        }
    }
    switch (tag) {
    case DEAL_DATA_NULL:
        deal_json_buffer_append(&encoder->text, "null", 4u);
        return 0;
    case DEAL_DATA_BOOLEAN:
        if (position.input.datum.number != 0.0) {
            deal_json_buffer_append(&encoder->text, "true", 4u);
        } else {
            deal_json_buffer_append(&encoder->text, "false", 5u);
        }
        return 0;
    case DEAL_DATA_NUMBER: {
        double number = position.input.datum.number;
        if (!(number == number) || number > 1.7976931348623157e308
                || number < -1.7976931348623157e308) {
            deal_json_buffer_append(&encoder->text, "null", 4u);
            return 0;
        }
        if (number == 0.0) {
            deal_json_buffer_byte(&encoder->text, '0');
            return 0;
        }
        deal_json_number_text(&encoder->text, number);
        return 0;
    }
    case DEAL_DATA_STRING:
        deal_json_escape(&encoder->text, (deal_string *)position.input.datum.reference);
        return 0;
    case DEAL_DATA_ARRAY:
    case DEAL_DATA_TABLE:
        return deal_json_open_container(encoder, &position.input,
                                        position.descriptor, walk) ? 2 : 0;
    case DEAL_DATA_CLASS:
        if (position.descriptor == NULL) {
            return 3;
        }
        return deal_json_open_container(encoder, &position.input,
                                        position.descriptor, walk) ? 2 : 0;
    case DEAL_DATA_ERROR:
        return 3;
    case DEAL_DATA_UNDEFINED:
    case DEAL_DATA_FUNCTION:
        break;
    }
    return 3;
}

/*
 * Walks every open frame to its end. Each frame emits its own entries in own-key
 * order (a record), declaration order (a class) or index order (an array), and a
 * child container becomes the new top frame, so a finite nested input has no C
 * stack bound.
 */
static int deal_json_encode_frames(deal_json_encoder *encoder) {
    while (encoder->frame_count > 0u) {
        deal_json_encode_frame *frame =
            &encoder->frames[encoder->frame_count - 1u];
        if (frame->kind == 0u) {
            if (frame->index >= frame->key_count) {
                deal_json_encoder_close(encoder);
                continue;
            }
            deal_string *key = frame->keys[frame->index];
            frame->index += 1u;
            deal_json_position position;
            memset(&position, 0, sizeof(position));
            position.input.presentation = DEAL_PRESENT_DATUM;
            position.input.datum = deal_container_table_read(frame->table, key);
            if (position.input.datum.tag == DEAL_DATA_UNDEFINED
                    || position.input.datum.tag == DEAL_DATA_FUNCTION) {
                continue;
            }
            const unsigned char *name = deal_text_bytes(key);
            size_t name_length = deal_text_byte_length(key);
            deal_json_walk_position walk;
            memset(&walk, 0, sizeof(walk));
            walk.base = encoder->frame_count - 1u;
            walk.kind = 1u;
            walk.name = name;
            walk.name_length = name_length;
            if (frame->emitted > 0u) {
                deal_json_buffer_byte(&encoder->text, ',');
            }
            frame->emitted += 1u;
            deal_json_escape(&encoder->text, key);
            deal_json_buffer_byte(&encoder->text, ':');
            int status = deal_json_emit(encoder, position, &walk);
            if (status == 2) {
                encoder->error = deal_json_cycle_error(encoder);
                return 1;
            }
            if (status == 3) {
                char *path = deal_json_path_render(encoder, &walk);
                encoder->error = deal_json_unsupported_error(encoder, path,
                                                             &position.input);
                deal_platform_release(path);
                return 1;
            }
            continue;
        }
        if (frame->kind == 1u) {
            if (frame->index >= deal_container_array_count(frame->array)) {
                deal_json_encoder_close(encoder);
                continue;
            }
            size_t element = frame->index;
            frame->index += 1u;
            deal_json_position position;
            deal_json_element_at(frame->array, element,
                                 frame->element_descriptor, &position.input);
            position.descriptor = encoder->walk_failures
                ? frame->element_descriptor : NULL;
            deal_json_walk_position walk;
            memset(&walk, 0, sizeof(walk));
            walk.base = encoder->frame_count - 1u;
            walk.kind = 2u;
            walk.element = element;
            if (element > 0u) {
                deal_json_buffer_byte(&encoder->text, ',');
            }
            int status = deal_json_emit(encoder, position, &walk);
            if (status == 1) {
                /* An undefined/function array element encodes as `null`. */
                deal_json_buffer_append(&encoder->text, "null", 4u);
                continue;
            }
            if (status == 2) {
                encoder->error = deal_json_cycle_error(encoder);
                return 1;
            }
            if (status == 3) {
                char *path = deal_json_path_render(encoder, &walk);
                encoder->error = deal_json_unsupported_error(encoder, path,
                                                             &position.input);
                deal_platform_release(path);
                return 1;
            }
            continue;
        }
        const deal_class_layout *layout = frame->plan->layout;
        if (frame->index >= layout->field_count) {
            deal_json_encoder_close(encoder);
            continue;
        }
        size_t field_index = frame->index;
        frame->index += 1u;
        const deal_class_field *field = &layout->fields[field_index];
        if (frame->plan->read_field == NULL) {
            deal_platform_fault("json: class plan without its field seam");
        }
        deal_check_input input;
        uint8_t presence = 0u;
        memset(&input, 0, sizeof(input));
        input.presentation = DEAL_PRESENT_DATUM;
        if (!frame->plan->read_field(&frame->instance, field_index, &input,
                                     &presence, frame->plan->context)) {
            deal_platform_fault("json: instance does not belong to its plan");
        }
        deal_json_walk_position walk;
        memset(&walk, 0, sizeof(walk));
        walk.base = encoder->frame_count - 1u;
        walk.kind = 1u;
        walk.name = (const unsigned char *)field->name;
        walk.name_length = strlen(field->name);
        if (!presence) {
            if (field->optional) {
                continue;
            }
            /* A missing required value is the walk arm with the absent
             * projection, never a silent omission. */
            char *path = deal_json_path_render(encoder, &walk);
            encoder->error = deal_json_walk_error(encoder, path, "nil");
            deal_platform_release(path);
            return 1;
        }
        deal_json_position position;
        memset(&position, 0, sizeof(position));
        position.input = input;
        position.descriptor = field->descriptor;
        if (deal_json_omitted(position)) {
            /* A present undefined-valued field of an undefined-admitting type
             * is omitted (v1.5): the key never reaches the text. */
            continue;
        }
        if (frame->emitted > 0u) {
            deal_json_buffer_byte(&encoder->text, ',');
        }
        frame->emitted += 1u;
        deal_json_escape_bytes(&encoder->text,
            (const unsigned char *)field->name, strlen(field->name));
        deal_json_buffer_byte(&encoder->text, ':');
        int status = deal_json_emit(encoder, position, &walk);
        if (status == 2) {
            encoder->error = deal_json_cycle_error(encoder);
            return 1;
        }
        if (status == 3) {
            char *path = deal_json_path_render(encoder, &walk);
            encoder->error = deal_json_unsupported_error(encoder, path,
                                                         &position.input);
            deal_platform_release(path);
            return 1;
        }
    }
    return 0;
}

/* The walk's first declaration-order failure: the JSON_TO_WALK arm at the
 * failing position with the value's typed-boundary token. */
static deal_error *deal_json_walk_error(deal_json_encoder *encoder,
                                        const char *path, const char *token) {
    deal_arm_parameter parameters[2];
    parameters[0].name = "fieldPath";
    parameters[0].value = path;
    parameters[1].name = "actual";
    parameters[1].value = token;
    return deal_error_materialize(DEAL_ARM_ID_JSON_TO_WALK, parameters, 2u,
                                  NULL, token, encoder->origin, NULL);
}

/* The rendered failure of one unsupported value (the closed arm of the mode). */
static deal_error *deal_json_unsupported_error(deal_json_encoder *encoder,
                                               const char *path,
                                               const deal_check_input *input) {
    if (encoder->walk_failures) {
        return deal_json_walk_error(encoder, path,
                                    deal_json_walk_token(input));
    }
    const char *token = deal_json_carrier_token(input);
    deal_arm_parameter parameters[1];
    parameters[0].name = "actual";
    parameters[0].value = token;
    return deal_error_materialize(DEAL_ARM_ID_JSON_STRINGIFY_UNSUPPORTED,
        parameters, 1u, deal_json_stringify_expected, token, encoder->origin,
        NULL);
}

/* The closed cycle failure of the walk family (no parameters, no declared
 * fields). */
static deal_error *deal_json_cycle_error(deal_json_encoder *encoder) {
    return deal_error_materialize(DEAL_ARM_ID_JSON_TO_WALK_CYCLE, NULL, 0u,
                                  NULL, NULL, encoder->origin, NULL);
}

/* =========================================================================
 * The class decode walk (`C$fromJson`, R6)
 * ========================================================================= */

/*
 * One open position of the decode walk's explicit frame stack (R6). The C11
 * stack-safe rule covers the class walk exactly as it covers the parser and the
 * encoder: a `@jsonable` class graph may be recursive (`class Node { child?:
 * Node }`), so the *input* nesting depth decides the walk depth, and a
 * recursive C descent would bind that depth to the C stack. One frame holds one
 * open class construction or one open array position plus the position in the
 * finished output its value is published into; the caller's loop owns the
 * descent order, so no depth is ever a C frame.
 */
typedef enum {
    DEAL_JSON_DECODE_CLASS = 0,
    DEAL_JSON_DECODE_ARRAY = 1
} deal_json_decode_kind;

/* One class frame's phases: its declared fields, then the unknown-key scan,
 * then the one construction that publishes the instance. */
#define DEAL_JSON_DECODE_FIELDS    0u
#define DEAL_JSON_DECODE_UNKNOWN   1u
#define DEAL_JSON_DECODE_CONSTRUCT 2u

/* The answers of one walk step. */
#define DEAL_JSON_DECODE_OK       0 /* the position's value is in its destination */
#define DEAL_JSON_DECODE_FAIL     1 /* the position does not admit the value */
#define DEAL_JSON_DECODE_DESCEND  2 /* one frame was opened */
#define DEAL_JSON_DECODE_CHILD    3 /* one child position is ready */
#define DEAL_JSON_DECODE_COMPLETE 4 /* the top frame published its value */

typedef struct deal_json_decode_frame {
    deal_json_decode_kind kind;
    /* Where this frame's finished value is published (stable platform or
     * caller storage, never inside the frame stack). */
    deal_data *destination;
    /* One open class construction. */
    deal_table *document;
    const deal_json_class_plan *plan;
    const deal_class_layout *layout;
    deal_data *values;
    uint8_t *presence;
    size_t field_index;
    size_t field_count;
    uint32_t phase;
    /* One open array position (the declared element type and the source array's
     * recorded element descriptor are both fixed when the frame opens). */
    deal_array *source;
    deal_array *decoded;
    deal_descriptor *source_element;
    deal_descriptor *element;
    size_t index;
    size_t count;
    /* 1 while the position at `field_index` / `index` is being decoded. */
    int child_open;
} deal_json_decode_frame;

/* Releases one frame's invocation-local scratch (M5: no publication). */
static void deal_json_decode_release_frame(deal_json_decode_frame *frame) {
    deal_platform_release(frame->values);
    frame->values = NULL;
    deal_platform_release(frame->presence);
    frame->presence = NULL;
}

/* Opens one frame on the explicit stack, growing it by doubling (R10 on
 * exhaustion). */
static deal_json_decode_frame *deal_json_decode_open(
        deal_json_decode_frame **frames, size_t *depth, size_t *capacity) {
    if (*depth == *capacity) {
        size_t grown = *capacity == 0u ? 16u : *capacity * 2u;
        if (grown <= *capacity) {
            deal_platform_fault("json: decode frame stack overflow");
        }
        size_t bytes = deal_platform_checked_multiply(grown, sizeof(**frames));
        deal_json_decode_frame *table = deal_platform_allocate(bytes);
        if (table == NULL) {
            deal_platform_fault("json: decode frame stack allocation failed");
        }
        if (*frames != NULL) {
            memcpy(table, *frames, *depth * sizeof(**frames));
            deal_platform_release(*frames);
        }
        *frames = table;
        *capacity = grown;
    }
    deal_json_decode_frame *frame = &(*frames)[*depth];
    memset(frame, 0, sizeof(*frame));
    *depth += 1u;
    return frame;
}

/* Opens one class construction frame for one document and one emitted plan. */
static void deal_json_decode_open_class(deal_json_decode_frame **frames,
                                        size_t *depth, size_t *capacity,
                                        deal_table *document,
                                        const deal_json_class_plan *plan,
                                        deal_data *destination) {
    if (plan == NULL || plan->layout == NULL || plan->construct == NULL) {
        deal_platform_fault("json: incomplete class plan");
    }
    deal_json_decode_frame *frame =
        deal_json_decode_open(frames, depth, capacity);
    frame->kind = DEAL_JSON_DECODE_CLASS;
    frame->destination = destination;
    frame->document = document;
    frame->plan = plan;
    frame->layout = plan->layout;
    frame->field_count = plan->layout->field_count;
    frame->phase = DEAL_JSON_DECODE_FIELDS;
    if (frame->field_count != 0u) {
        frame->values = deal_platform_allocate(deal_platform_checked_multiply(
            frame->field_count, sizeof(*frame->values)));
        frame->presence = deal_platform_allocate(frame->field_count);
        if (frame->values == NULL || frame->presence == NULL) {
            deal_platform_fault("json: decode scratch allocation failed");
        }
        memset(frame->values, 0, frame->field_count * sizeof(*frame->values));
        memset(frame->presence, 0, frame->field_count);
    }
}

/* Opens one array position: the declared element descriptor is the rebuilt
 * array's recorded one and the source's recorded descriptor types its elements
 * (spec v1.5 `T[]`: the fields receive `T` descriptors after validation). */
static void deal_json_decode_open_array(deal_json_decode_frame **frames,
                                        size_t *depth, size_t *capacity,
                                        deal_array *source,
                                        deal_descriptor *element,
                                        deal_data *destination) {
    uint32_t recorded = deal_container_array_element_descriptor(source);
    deal_array *decoded = deal_container_array_new_typed(
        DEAL_LAYOUT_ELEMENT_DATUM, deal_descriptor_reference(element), 0u);
    deal_json_hold(decoded);
    deal_json_decode_frame *frame =
        deal_json_decode_open(frames, depth, capacity);
    frame->kind = DEAL_JSON_DECODE_ARRAY;
    frame->destination = destination;
    frame->source = source;
    frame->decoded = decoded;
    frame->source_element =
        recorded == 0u ? NULL : deal_descriptor_of_reference(recorded);
    frame->element = element;
    frame->count = deal_container_array_count(source);
}

/*
 * Resolves one position: a record, an unknown value or a scalar is written into
 * `destination` and answers OK (a scalar only after the delivered
 * `deal_check_value` at the `JSON_FROM_FIELD` boundary, whose swallowed failure
 * is the `JSON_FROM_NULL` null), a class or an array opens one frame and
 * answers DESCEND, and a position that does not admit the value answers FAIL.
 * A nullish descriptor admits its own null/undefined value and otherwise
 * settles the same position against its base descriptor: a `?T` position adds
 * exactly the nullish members and no nesting level of its own.
 */
static int deal_json_settle(deal_data raw, deal_descriptor *descriptor,
                            deal_json_plan_lookup lookup, void *context,
                            const deal_origin *origin, deal_data *destination,
                            deal_json_decode_frame **frames, size_t *depth,
                            size_t *capacity) {
    if (descriptor == NULL) {
        deal_platform_fault("json: decode position without its descriptor");
    }
    while (deal_descriptor_type(descriptor) == DEAL_DESCRIPTOR_NULLISH) {
        if (raw.tag == DEAL_DATA_NULL && deal_descriptor_admits_null(descriptor)) {
            *destination = raw;
            return DEAL_JSON_DECODE_OK;
        }
        if (raw.tag == DEAL_DATA_UNDEFINED
                && deal_descriptor_admits_undefined(descriptor)) {
            *destination = raw;
            return DEAL_JSON_DECODE_OK;
        }
        descriptor = deal_descriptor_inner(descriptor);
    }
    deal_descriptor_kind kind = deal_descriptor_type(descriptor);
    if (kind == DEAL_DESCRIPTOR_CLASS) {
        if (raw.tag != DEAL_DATA_TABLE) {
            return DEAL_JSON_DECODE_FAIL;
        }
        const char *atom = deal_descriptor_class_atom(descriptor);
        const deal_json_class_plan *plan =
            lookup == NULL ? NULL : lookup(atom, context);
        if (plan == NULL) {
            deal_platform_fault("json: no emitted plan for a declared class");
        }
        deal_json_decode_open_class(frames, depth, capacity,
                                    (deal_table *)raw.reference, plan,
                                    destination);
        return DEAL_JSON_DECODE_DESCEND;
    }
    if (kind == DEAL_DESCRIPTOR_ARRAY) {
        if (raw.tag != DEAL_DATA_ARRAY) {
            return DEAL_JSON_DECODE_FAIL;
        }
        deal_json_decode_open_array(frames, depth, capacity,
                                    (deal_array *)raw.reference,
                                    deal_descriptor_element(descriptor),
                                    destination);
        return DEAL_JSON_DECODE_DESCEND;
    }
    if (kind == DEAL_DESCRIPTOR_RECORD) {
        if (raw.tag != DEAL_DATA_TABLE) {
            return DEAL_JSON_DECODE_FAIL;
        }
        *destination = raw;
        return DEAL_JSON_DECODE_OK;
    }
    if (kind == DEAL_DESCRIPTOR_UNKNOWN) {
        *destination = raw;
        return DEAL_JSON_DECODE_OK;
    }
    /* Every scalar position runs the delivered check at the JSON_FROM_FIELD
     * boundary; the swallowed failure never escapes (JSON_FROM_NULL). */
    deal_check_input input;
    memset(&input, 0, sizeof(input));
    input.presentation = DEAL_PRESENT_DATUM;
    input.unicode_defect = DEAL_UNICODE_MALFORMED;
    input.datum = raw;
    if (raw.tag == DEAL_DATA_CLASS) {
        input.class_atom = deal_json_class_atom_of(descriptor);
    }
    deal_check_context check_context;
    check_context.origin = origin;
    check_context.parameter_index = 0;
    if (deal_check_value(descriptor, input, DEAL_BOUNDARY_TYPE_DESCRIPTOR,
                         &check_context).error != NULL) {
        return DEAL_JSON_DECODE_FAIL;
    }
    *destination = raw;
    return DEAL_JSON_DECODE_OK;
}

/* The destination the current child position of one frame decodes into. An
 * array element lands in the caller's loop storage because the frame stack may
 * grow while the child descends; a class field lands in the frame's own scratch
 * array, which never moves. */
static deal_data *deal_json_decode_child_destination(
        deal_json_decode_frame *frame, deal_data *element) {
    if (frame->kind == DEAL_JSON_DECODE_ARRAY) {
        memset(element, 0, sizeof(*element));
        return element;
    }
    return &frame->values[frame->field_index];
}

/*
 * Advances the top frame: it answers CHILD when one child position is ready
 * (written into the caller's position and descriptor), COMPLETE after it
 * published its own value into its destination, and FAIL when a required field
 * is absent, an unknown key is present or the construction failed.
 */
static int deal_json_decode_advance(deal_json_decode_frame *frame,
                                    deal_data *child_value, deal_data *raw_out,
                                    deal_descriptor **descriptor_out) {
    if (frame->kind == DEAL_JSON_DECODE_ARRAY) {
        if (frame->child_open) {
            /* The element is an intermediate of the whole rebuilt array, so it
             * is rooted across the append's own allocation (M5). */
            (void)deal_root_scope_hold_data(child_value);
            deal_container_array_append(frame->decoded, child_value);
            memset(child_value, 0, sizeof(*child_value));
            frame->child_open = 0;
            frame->index += 1u;
        }
        if (frame->index >= frame->count) {
            *frame->destination =
                deal_data_of_reference(DEAL_DATA_ARRAY, frame->decoded);
            return DEAL_JSON_DECODE_COMPLETE;
        }
        deal_check_input input;
        deal_json_element_at(frame->source, frame->index,
                             frame->source_element, &input);
        *raw_out = input.datum;
        *descriptor_out = frame->element;
        frame->child_open = 1;
        return DEAL_JSON_DECODE_CHILD;
    }
    if (frame->child_open) {
        /* A decoded field is an intermediate of the whole construction, so it
         * is rooted until the instance that stores it exists (M5). */
        (void)deal_root_scope_hold_data(&frame->values[frame->field_index]);
        frame->child_open = 0;
        frame->field_index += 1u;
    }
    while (1) {
        if (frame->phase == DEAL_JSON_DECODE_FIELDS) {
            if (frame->field_index >= frame->field_count) {
                frame->phase = DEAL_JSON_DECODE_UNKNOWN;
                continue;
            }
            const deal_class_field *field =
                &frame->layout->fields[frame->field_index];
            if (field->name == NULL || field->descriptor == NULL) {
                deal_platform_fault("json: incomplete class field");
            }
            deal_string *key = deal_text_new(field->name, strlen(field->name));
            deal_json_hold(key);
            if (!deal_container_table_has(frame->document, key)) {
                if (!field->optional) {
                    return DEAL_JSON_DECODE_FAIL;
                }
                frame->field_index += 1u;
                continue;
            }
            frame->presence[frame->field_index] = 1u;
            *raw_out = deal_container_table_read(frame->document, key);
            *descriptor_out = field->descriptor;
            frame->child_open = 1;
            return DEAL_JSON_DECODE_CHILD;
        }
        if (frame->phase == DEAL_JSON_DECODE_UNKNOWN) {
            /* An unknown key is rejected: every present key must name a
             * declared field (spec v1.5 `@jsonable` fromJson). */
            size_t present = deal_container_table_count(frame->document);
            for (size_t index = 0; index < present; index += 1u) {
                deal_string *key =
                    deal_container_table_key_at(frame->document, index);
                int declared = 0;
                for (size_t field_index = 0;
                        field_index < frame->field_count; field_index += 1u) {
                    deal_string *name = deal_text_new(
                        frame->layout->fields[field_index].name,
                        strlen(frame->layout->fields[field_index].name));
                    deal_json_hold(name);
                    if (deal_text_compare(name, key) == 0) {
                        declared = 1;
                        break;
                    }
                }
                if (!declared) {
                    return DEAL_JSON_DECODE_FAIL;
                }
            }
            frame->phase = DEAL_JSON_DECODE_CONSTRUCT;
            continue;
        }
        /* One construction, only after every field and key validated, so a
         * rejected document publishes no instance (R2, R4). */
        deal_outcome_data constructed = frame->plan->construct(
            frame->layout, frame->values, frame->presence, frame->plan->context);
        if (constructed.error != NULL) {
            return DEAL_JSON_DECODE_FAIL;
        }
        *frame->destination = constructed.value;
        return DEAL_JSON_DECODE_COMPLETE;
    }
}

/*
 * Runs the whole decode walk of one parsed document against one class plan
 * through the explicit frame stack (R6): the declared fields in declaration
 * order, every required-present key present, every unknown key rejected, and
 * the instance built and tagged only after every field validated. Answers 1 on
 * any failure, publishing nothing.
 */
static int deal_json_decode_class(deal_table *document,
                                  const deal_json_class_plan *plan,
                                  deal_json_plan_lookup lookup, void *context,
                                  const deal_origin *origin, deal_data *out) {
    deal_json_decode_frame *frames = NULL;
    size_t depth = 0u;
    size_t capacity = 0u;
    deal_data child_value;
    memset(&child_value, 0, sizeof(child_value));
    deal_data raw = deal_data_of_reference(DEAL_DATA_TABLE, document);
    deal_descriptor *descriptor = NULL;
    deal_data *destination = out;
    int failed = 0;
    int advancing = 0;

    deal_json_decode_open_class(&frames, &depth, &capacity, document, plan,
                                out);
    advancing = 1;
    while (1) {
        if (!advancing) {
            int settled = deal_json_settle(raw, descriptor, lookup, context,
                                           origin, destination, &frames, &depth,
                                           &capacity);
            if (settled == DEAL_JSON_DECODE_FAIL) {
                failed = 1;
                break;
            }
            if (settled == DEAL_JSON_DECODE_OK && depth == 0u) {
                break;
            }
            /* OK publishes one frame's child; DESCEND opened a new top frame. */
            advancing = 1;
            continue;
        }
        if (depth == 0u) {
            break;
        }
        deal_json_decode_frame *frame = &frames[depth - 1u];
        int stepped = deal_json_decode_advance(frame, &child_value, &raw,
                                               &descriptor);
        if (stepped == DEAL_JSON_DECODE_FAIL) {
            failed = 1;
            break;
        }
        if (stepped == DEAL_JSON_DECODE_CHILD) {
            destination = deal_json_decode_child_destination(frame,
                                                             &child_value);
            advancing = 0;
            continue;
        }
        deal_json_decode_release_frame(frame);
        depth -= 1u;
        if (depth == 0u) {
            break;
        }
    }
    for (size_t index = 0; index < depth; index += 1u) {
        deal_json_decode_release_frame(&frames[index]);
    }
    deal_platform_release(frames);
    return failed;
}

deal_outcome_data deal_json_from_class(deal_string *text,
                                       const deal_json_class_plan *plan,
                                       deal_json_plan_lookup lookup,
                                       void *lookup_context,
                                       const deal_origin *origin) {
    deal_outcome_data outcome;
    memset(&outcome, 0, sizeof(outcome));
    if (text == NULL) {
        deal_platform_fault("json: null fromJson argument");
    }
    if (plan == NULL) {
        deal_platform_fault("json: missing fromJson plan");
    }
    if (origin == NULL) {
        deal_platform_fault("json: missing fromJson origin");
    }
    /* The parsed document is this helper's intermediate across the whole
     * decode walk, so it is rooted from the moment the parse answers it (M5);
     * a parse failure answers the language null and publishes nothing. */
    deal_root_scope_push();
    deal_outcome_data parsed = deal_json_parse(text, origin);
    if (parsed.error != NULL) {
        /* JSON_FROM_NULL: a parse failure is the language null. */
        outcome.value = deal_data_of_null();
        deal_root_scope_pop();
        return outcome;
    }
    (void)deal_root_scope_hold_data(&parsed.value);
    if (parsed.value.tag != DEAL_DATA_TABLE) {
        outcome.value = deal_data_of_null();
        deal_root_scope_pop();
        return outcome;
    }
    deal_data instance;
    memset(&instance, 0, sizeof(instance));
    int failed = deal_json_decode_class((deal_table *)parsed.value.reference,
                                        plan, lookup, lookup_context, origin,
                                        &instance);
    deal_root_scope_pop();
    outcome.value = failed ? deal_data_of_null() : instance;
    return outcome;
}

/* =========================================================================
 * The generic encoder entry (`std/json.stringify`, R6)
 * ========================================================================= */

deal_outcome_data deal_json_stringify(deal_data value,
                                      const deal_origin *origin) {
    deal_outcome_data outcome;
    memset(&outcome, 0, sizeof(outcome));
    if (origin == NULL) {
        deal_platform_fault("json: missing stringify origin");
    }
    /* The v1.5 omission rule at the root: an undefined or function-valued root
     * yields undefined and no text at all. */
    if (value.tag == DEAL_DATA_UNDEFINED || value.tag == DEAL_DATA_FUNCTION) {
        outcome.value = deal_data_of_undefined();
        return outcome;
    }
    deal_json_encoder encoder;
    memset(&encoder, 0, sizeof(encoder));
    encoder.origin = origin;
    encoder.walk_failures = 0;
    deal_json_position position;
    memset(&position, 0, sizeof(position));
    position.input.presentation = DEAL_PRESENT_DATUM;
    position.input.unicode_defect = DEAL_UNICODE_MALFORMED;
    position.input.datum = value;
    /* A class instance has no generic representation: the walk answers the
     * closed std/json rejection arm with its carrier-kind token, so no class
     * atom is needed here. */
    position.input.class_atom = NULL;
    deal_root_scope_push();
    deal_json_walk_position root;
    memset(&root, 0, sizeof(root));
    root.base = SIZE_MAX;
    int status = deal_json_emit(&encoder, position, &root);
    if (status == 2) {
        encoder.error = deal_json_cycle_error(&encoder);
    } else if (status == 3) {
        char *path = deal_json_path_render(&encoder, &root);
        encoder.error = deal_json_unsupported_error(&encoder, path,
                                                     &position.input);
        deal_platform_release(path);
    }
    if (status == 0) {
        (void)deal_json_encode_frames(&encoder);
    }
    if (encoder.error != NULL) {
        outcome.error = encoder.error;
        deal_json_encoder_release(&encoder);
        deal_root_scope_pop();
        return outcome;
    }
    deal_string *text = deal_text_new(encoder.text.bytes, encoder.text.length);
    deal_json_encoder_release(&encoder);
    deal_root_scope_pop();
    outcome.value = deal_data_of_reference(DEAL_DATA_STRING, text);
    return outcome;
}

/* =========================================================================
 * The class encode walk (`C$toJson`, R6)
 * ========================================================================= */

deal_outcome_string deal_json_to_class(const deal_json_class_plan *plan,
                                       const deal_data *instance,
                                       deal_json_plan_lookup lookup,
                                       void *lookup_context,
                                       const deal_origin *origin) {
    deal_outcome_string outcome;
    memset(&outcome, 0, sizeof(outcome));
    if (plan == NULL || plan->layout == NULL) {
        deal_platform_fault("json: incomplete toJson plan");
    }
    if (instance == NULL) {
        deal_platform_fault("json: null toJson instance");
    }
    if (origin == NULL) {
        deal_platform_fault("json: missing toJson origin");
    }
    if (plan->layout->class_atom == NULL) {
        deal_platform_fault("json: class plan without its canonical atom");
    }
    deal_json_encoder encoder;
    memset(&encoder, 0, sizeof(encoder));
    encoder.origin = origin;
    encoder.walk_failures = 1;
    encoder.lookup = lookup;
    encoder.lookup_context = lookup_context;
    deal_json_position position;
    memset(&position, 0, sizeof(position));
    position.input.presentation = DEAL_PRESENT_DATUM;
    position.input.unicode_defect = DEAL_UNICODE_MALFORMED;
    position.input.datum = *instance;
    position.input.class_atom = plan->layout->class_atom;
    position.descriptor = deal_descriptor_intern(plan->layout->class_atom);
    deal_root_scope_push();
    deal_json_walk_position root;
    memset(&root, 0, sizeof(root));
    root.base = SIZE_MAX;
    int status = deal_json_emit(&encoder, position, &root);
    if (status == 2) {
        encoder.error = deal_json_cycle_error(&encoder);
    } else if (status == 3) {
        char *path = deal_json_path_render(&encoder, &root);
        encoder.error = deal_json_unsupported_error(&encoder, path,
                                                     &position.input);
        deal_platform_release(path);
    }
    if (status == 0) {
        (void)deal_json_encode_frames(&encoder);
    }
    if (encoder.error != NULL) {
        outcome.error = encoder.error;
        deal_json_encoder_release(&encoder);
        deal_root_scope_pop();
        return outcome;
    }
    outcome.value = deal_text_new(encoder.text.bytes, encoder.text.length);
    deal_json_encoder_release(&encoder);
    deal_root_scope_pop();
    return outcome;
}
