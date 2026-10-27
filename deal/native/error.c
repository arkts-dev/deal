/*
 * deal/native/error.c — the `error` service group implementation.
 *
 * Owns the `deal_error` record, the R9 presence-returning accessors, the
 * call-frame stack, the failure-arm-table install/store/structural validation
 * and the canonical snapshot serializer of `error.h`.
 */
#include "deal/native/error.h"
#include "deal/native/entry.h"
#include "deal/native/platform.h"

#include <stddef.h>
#include <string.h>

/* =========================================================================
 * The call-frame stack (`CallFrameContract`)
 * ========================================================================= */

#define DEAL_CALL_FRAME_MAX 4096U

static const char *deal_call_frame_labels[DEAL_CALL_FRAME_MAX];
static uint32_t deal_call_frame_depth_value;

void deal_call_frame_push(const char *label) {
    if (label == NULL) {
        deal_platform_fault("call frame: null frame label");
    }
    if (deal_call_frame_depth_value == DEAL_CALL_FRAME_MAX) {
        deal_platform_fault("call frame: depth limit exceeded");
    }
    deal_call_frame_labels[deal_call_frame_depth_value] = label;
    deal_call_frame_depth_value += 1U;
}

void deal_call_frame_pop(void) {
    if (deal_call_frame_depth_value == 0) {
        deal_platform_fault("call frame: pop with no active frame");
    }
    deal_call_frame_depth_value -= 1U;
}

uint32_t deal_call_frame_depth(void) {
    return deal_call_frame_depth_value;
}

/* The active label at `index`, counting innermost-first. */
static const char *deal_call_frame_label(uint32_t index) {
    return deal_call_frame_labels[deal_call_frame_depth_value - 1U - index];
}

/* =========================================================================
 * The failure-arm-table transport (R5)
 * ========================================================================= */

static const deal_arm_table *deal_installed_arm_table;

const deal_arm_table *deal_arm_table_installed(void) {
    return deal_installed_arm_table;
}

static void deal_arm_table_validate(const deal_arm_table *table) {
    if (table->version != DEAL_ARM_TABLE_VERSION) {
        deal_platform_fault("arm table: unsupported version");
    }
    if (table->count == 0) {
        if (table->records != NULL) {
            deal_platform_fault("arm table: count and records disagree");
        }
        return;
    }
    if (table->records == NULL) {
        deal_platform_fault("arm table: count and records disagree");
    }
    for (uint32_t index = 0; index < table->count; index += 1U) {
        const deal_arm_record *record = &table->records[index];
        if (record->code == NULL || record->template_text == NULL) {
            deal_platform_fault("arm table: null record text");
        }
        if (record->render_scope != DEAL_ARM_SCOPE_TOP_LEVEL
                && record->render_scope != DEAL_ARM_SCOPE_INNER_ONLY) {
            deal_platform_fault("arm table: render scope outside the closed set");
        }
        for (uint32_t other = 0; other < index; other += 1U) {
            if (table->records[other].id == record->id) {
                deal_platform_fault("arm table: duplicate arm id");
            }
        }
    }
}

int deal_arm_table_install(const deal_arm_table *table) {
    if (table == NULL) {
        deal_platform_fault("arm table: null table");
    }
    if (deal_installed_arm_table != NULL) {
        if (deal_installed_arm_table != table) {
            deal_platform_fault("arm table: replaced after install");
        }
        return 1;
    }
    deal_arm_table_validate(table);
    deal_installed_arm_table = table;
    return 1;
}

/* =========================================================================
 * The Error record (R5, R9)
 * ========================================================================= */

struct deal_error {
    char *code;
    char *message;
    char *source_file;
    int32_t line;
    int32_t column;
    int has_origin;
    char *expected;
    char *actual;
    char **frames;
    uint32_t frame_count;
    deal_error *cause;
};

/*
 * The Error instance is one managed object of its own runtime layout (M1): the
 * runtime's text fields reach only platform-owned text, while the cause is a
 * managed `deal_error *`, so the layout declares exactly that one
 * managed-reference slot. A carrier, a root slot or a record member that holds
 * an Error traces it like any other managed value (M5), and a cause reachable
 * only through its parent Error stays marked (M1, `RootFrame`).
 */
static const uint32_t deal_error_reference_offsets[] = {
    (uint32_t)offsetof(struct deal_error, cause)
};

static const deal_layout deal_error_layout = {
    DEAL_LAYOUT_ERROR, 0u, sizeof(deal_error),
    deal_error_reference_offsets, 1u
};

deal_error *deal_error_create(const char *code, const char *message,
                              const deal_origin *origin) {
    if (code == NULL) {
        deal_platform_fault("error record: absent required code");
    }
    if (message == NULL) {
        deal_platform_fault("error record: absent required message");
    }
    deal_layout_define(&deal_error_layout);
    deal_error *error = deal_alloc(DEAL_LAYOUT_ERROR, sizeof(*error));
    error->code = deal_platform_duplicate(code);
    error->message = deal_platform_duplicate(message);
    error->source_file = NULL;
    error->line = 0;
    error->column = 0;
    error->has_origin = 0;
    error->expected = NULL;
    error->actual = NULL;
    error->frames = NULL;
    error->frame_count = 0;
    error->cause = NULL;
    if (origin != NULL) {
        error->source_file = deal_platform_duplicate(origin->source_file);
        error->line = origin->line;
        error->column = origin->column;
        error->has_origin = 1;
    }
    uint32_t count = deal_call_frame_depth();
    if (count > 0) {
        error->frames = deal_platform_allocate((size_t)count * sizeof(char *));
        for (uint32_t index = 0; index < count; index += 1U) {
            error->frames[index] =
                deal_platform_duplicate(deal_call_frame_label(index));
        }
        error->frame_count = count;
    }
    return error;
}

void deal_error_set_expected(deal_error *error, const char *expected) {
    if (error == NULL) {
        deal_platform_fault("error record: null error for the expected field");
    }
    deal_platform_release(error->expected);
    error->expected = deal_platform_duplicate(expected);
}

void deal_error_set_actual(deal_error *error, const char *actual) {
    if (error == NULL) {
        deal_platform_fault("error record: null error for the actual field");
    }
    deal_platform_release(error->actual);
    error->actual = deal_platform_duplicate(actual);
}

void deal_error_set_cause(deal_error *error, deal_error *cause) {
    if (error == NULL) {
        deal_platform_fault("error record: null error for the cause field");
    }
    /* The cause slot is a managed-reference slot of this record, so the store
     * runs the insertion barrier (M2). An absent cause and a nullish singleton
     * are not managed payloads and never shade anything. */
    if (cause != NULL && !deal_value_is_null(cause)
            && !deal_value_is_undefined(cause)) {
        deal_store_barrier(error, cause);
    }
    error->cause = cause;
}

/* =========================================================================
 * The R9 accessors (declared by entry.h)
 * ========================================================================= */

static int deal_error_text_present(const char *text, const char **out) {
    if (text == NULL) {
        return 0;
    }
    if (out != NULL) {
        *out = text;
    }
    return 1;
}

int deal_error_code(deal_error *error, const char **out) {
    if (error == NULL) {
        return 0;
    }
    return deal_error_text_present(error->code, out);
}

int deal_error_message(deal_error *error, const char **out) {
    if (error == NULL) {
        return 0;
    }
    return deal_error_text_present(error->message, out);
}

int deal_error_source_file(deal_error *error, const char **out) {
    if (error == NULL || !error->has_origin) {
        return 0;
    }
    return deal_error_text_present(error->source_file, out);
}

int deal_error_line(deal_error *error, int32_t *out) {
    if (error == NULL || !error->has_origin) {
        return 0;
    }
    if (out != NULL) {
        *out = error->line;
    }
    return 1;
}

int deal_error_column(deal_error *error, int32_t *out) {
    if (error == NULL || !error->has_origin) {
        return 0;
    }
    if (out != NULL) {
        *out = error->column;
    }
    return 1;
}

int deal_error_expected(deal_error *error, const char **out) {
    if (error == NULL) {
        return 0;
    }
    return deal_error_text_present(error->expected, out);
}

int deal_error_actual(deal_error *error, const char **out) {
    if (error == NULL) {
        return 0;
    }
    return deal_error_text_present(error->actual, out);
}

int deal_error_frame_count(deal_error *error, int32_t *out) {
    if (error == NULL) {
        return 0;
    }
    if (out != NULL) {
        *out = (int32_t)error->frame_count;
    }
    return 1;
}

int deal_error_frame(deal_error *error, int32_t index, const char **out) {
    if (error == NULL || index < 0 || (uint32_t)index >= error->frame_count) {
        return 0;
    }
    return deal_error_text_present(error->frames[index], out);
}

int deal_error_cause_code(deal_error *error, const char **out) {
    if (error == NULL || error->cause == NULL) {
        return 0;
    }
    return deal_error_text_present(error->cause->code, out);
}

/* =========================================================================
 * The canonical snapshot serializer
 * ========================================================================= */

typedef struct {
    char *bytes;
    size_t length;
    size_t capacity;
} deal_snapshot_buffer;

static void deal_snapshot_reserve(deal_snapshot_buffer *buffer,
                                 size_t additional) {
    size_t required = buffer->length + additional;
    if (required <= buffer->capacity) {
        return;
    }
    size_t capacity = buffer->capacity == 0 ? 128U : buffer->capacity;
    while (capacity < required) {
        capacity *= 2U;
    }
    char *grown = deal_platform_allocate(capacity);
    if (buffer->bytes != NULL) {
        memcpy(grown, buffer->bytes, buffer->length);
        deal_platform_release(buffer->bytes);
    }
    buffer->bytes = grown;
    buffer->capacity = capacity;
}

static void deal_snapshot_append(deal_snapshot_buffer *buffer,
                                const char *bytes, size_t length) {
    if (length == 0) {
        return;
    }
    deal_snapshot_reserve(buffer, length);
    memcpy(buffer->bytes + buffer->length, bytes, length);
    buffer->length += length;
}

/* Minimal RFC 8259 escaping with raw UTF-8 non-ASCII (the shared canonical
 * serialization: `ErrorSnapshot.canonicalJson`). */
static void deal_snapshot_append_string(deal_snapshot_buffer *buffer,
                                       const char *text) {
    static const char hex[] = "0123456789ABCDEF";
    deal_snapshot_append(buffer, "\"", 1);
    if (text != NULL) {
        const unsigned char *cursor = (const unsigned char *)text;
        while (*cursor != 0) {
            unsigned char byte = *cursor;
            switch (byte) {
                case '"': deal_snapshot_append(buffer, "\\\"", 2); break;
                case '\\': deal_snapshot_append(buffer, "\\\\", 2); break;
                case '\b': deal_snapshot_append(buffer, "\\b", 2); break;
                case '\t': deal_snapshot_append(buffer, "\\t", 2); break;
                case '\n': deal_snapshot_append(buffer, "\\n", 2); break;
                case '\f': deal_snapshot_append(buffer, "\\f", 2); break;
                case '\r': deal_snapshot_append(buffer, "\\r", 2); break;
                default:
                    if (byte < 0x20U) {
                        char escaped[6];
                        escaped[0] = '\\';
                        escaped[1] = 'u';
                        escaped[2] = '0';
                        escaped[3] = '0';
                        escaped[4] = hex[(byte >> 4) & 0x0FU];
                        escaped[5] = hex[byte & 0x0FU];
                        deal_snapshot_append(buffer, escaped, sizeof(escaped));
                    } else {
                        deal_snapshot_append(buffer, (const char *)cursor, 1);
                    }
                    break;
            }
            cursor += 1;
        }
    }
    deal_snapshot_append(buffer, "\"", 1);
}

/* Canonical decimal integer: no leading zeros, sign or exponent. */
static void deal_snapshot_append_integer(deal_snapshot_buffer *buffer,
                                        int64_t value) {
    char digits[24];
    size_t length = 0;
    uint64_t magnitude;
    int negative = value < 0;
    magnitude = negative ? (uint64_t)(-(value + 1)) + 1U : (uint64_t)value;
    if (magnitude == 0) {
        digits[length] = '0';
        length += 1;
    } else {
        while (magnitude > 0) {
            digits[length] = (char)('0' + (int)(magnitude % 10U));
            length += 1;
            magnitude /= 10U;
        }
    }
    if (negative) {
        deal_snapshot_append(buffer, "-", 1);
    }
    while (length > 0) {
        length -= 1;
        deal_snapshot_append(buffer, &digits[length], 1);
    }
}

static void deal_snapshot_append_key(deal_snapshot_buffer *buffer,
                                    const char *key, int *first) {
    if (!*first) {
        deal_snapshot_append(buffer, ",", 1);
    }
    *first = 0;
    deal_snapshot_append_string(buffer, key);
    deal_snapshot_append(buffer, ":", 1);
}

char *deal_error_snapshot(const deal_error *error) {
    if (error == NULL) {
        deal_platform_fault("error snapshot: null error");
    }
    deal_snapshot_buffer buffer;
    buffer.bytes = NULL;
    buffer.length = 0;
    buffer.capacity = 0;
    int first = 1;
    deal_snapshot_append(&buffer, "{", 1);
    deal_snapshot_append_key(&buffer, "code", &first);
    deal_snapshot_append_string(&buffer, error->code);
    deal_snapshot_append_key(&buffer, "message", &first);
    deal_snapshot_append_string(&buffer, error->message);
    if (error->has_origin) {
        deal_snapshot_append_key(&buffer, "sourceFile", &first);
        deal_snapshot_append_string(&buffer, error->source_file);
        deal_snapshot_append_key(&buffer, "line", &first);
        deal_snapshot_append_integer(&buffer, error->line);
        deal_snapshot_append_key(&buffer, "column", &first);
        deal_snapshot_append_integer(&buffer, error->column);
    }
    if (error->expected != NULL) {
        deal_snapshot_append_key(&buffer, "expected", &first);
        deal_snapshot_append_string(&buffer, error->expected);
    }
    if (error->actual != NULL) {
        deal_snapshot_append_key(&buffer, "actual", &first);
        deal_snapshot_append_string(&buffer, error->actual);
    }
    deal_snapshot_append_key(&buffer, "frames", &first);
    deal_snapshot_append_integer(&buffer, (int64_t)error->frame_count);
    if (error->cause != NULL) {
        deal_snapshot_append_key(&buffer, "cause", &first);
        deal_snapshot_append_string(&buffer, error->cause->code);
    }
    deal_snapshot_append(&buffer, "}", 1);
    deal_snapshot_reserve(&buffer, 1);
    buffer.bytes[buffer.length] = 0;
    return buffer.bytes;
}

void deal_error_snapshot_release(char *text) {
    deal_platform_release(text);
}
