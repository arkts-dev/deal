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

/*
 * The compiled closed arm repertoire (R5): one entry per DEAL-visible arm in
 * the canonical arm order, carrying the arm's identity, its declared named
 * parameters, its render scope and its declared expected/actual fields. The
 * table carries structure only — no code, no template and no message — so the
 * installed table stays the one text authority and a record's text is never
 * compared against a copy of itself here.
 */
typedef struct {
    uint32_t id;
    const char *const *parameters;
    uint32_t parameter_count;
    uint8_t render_scope;
    uint8_t has_expected;
    uint8_t has_actual;
} deal_arm_repertoire_entry;

static const char *const deal_arm_parameters_none[] = {NULL};
static const char *const deal_arm_parameters_0[] = {"kind"};
static const char *const deal_arm_parameters_2[] = {"expected", "actual"};
static const char *const deal_arm_parameters_5[] = {"oneBasedIndex"};
static const char *const deal_arm_parameters_10[] = {"index", "inner"};
static const char *const deal_arm_parameters_11[] = {"expected"};
static const char *const deal_arm_parameters_12[] = {"inner"};
static const char *const deal_arm_parameters_13[] = {"expected"};
static const char *const deal_arm_parameters_14[] = {"actual"};
static const char *const deal_arm_parameters_15[] = {"module", "reason"};
static const char *const deal_arm_parameters_17[] = {"module"};
static const char *const deal_arm_parameters_18[] = {"name", "module"};
static const char *const deal_arm_parameters_19[] =
    {"name", "module", "expected", "actual"};
static const char *const deal_arm_parameters_21[] =
    {"name", "module", "defaults|fields"};
static const char *const deal_arm_parameters_22[] = {"field", "classId"};
static const char *const deal_arm_parameters_23[] =
    {"oneBasedByteOffset", "reason"};
static const char *const deal_arm_parameters_24[] = {"fieldPath", "actual"};
static const char *const deal_arm_parameters_25[] = {"actual"};

#define DEAL_ARM_ENTRY(id, parameters, count, scope, expected, actual) \
    {DEAL_ARM_ID_##id, parameters, count, scope, expected, actual}

static const deal_arm_repertoire_entry deal_arm_repertoire[] = {
    DEAL_ARM_ENTRY(TYPED_BOUNDARY_KIND, deal_arm_parameters_0, 1U,
        DEAL_ARM_SCOPE_TOP_LEVEL, 1U, 1U),
    DEAL_ARM_ENTRY(TYPED_BOUNDARY_INVALID_UNICODE, deal_arm_parameters_none, 0U,
        DEAL_ARM_SCOPE_TOP_LEVEL, 1U, 1U),
    DEAL_ARM_ENTRY(CLASS_IDENTITY, deal_arm_parameters_2, 2U,
        DEAL_ARM_SCOPE_TOP_LEVEL, 1U, 1U),
    DEAL_ARM_ENTRY(HOST_STRING_INVALID_UTF8, deal_arm_parameters_none, 0U,
        DEAL_ARM_SCOPE_INNER_ONLY, 0U, 0U),
    DEAL_ARM_ENTRY(HOST_STRING_SURROGATE, deal_arm_parameters_none, 0U,
        DEAL_ARM_SCOPE_INNER_ONLY, 0U, 0U),
    DEAL_ARM_ENTRY(ARRAY_ELEMENT_KIND, deal_arm_parameters_5, 1U,
        DEAL_ARM_SCOPE_TOP_LEVEL, 1U, 1U),
    DEAL_ARM_ENTRY(ARRAY_READ_BOUNDS, deal_arm_parameters_none, 0U,
        DEAL_ARM_SCOPE_TOP_LEVEL, 0U, 0U),
    DEAL_ARM_ENTRY(ARRAY_WRITE_BOUNDS, deal_arm_parameters_none, 0U,
        DEAL_ARM_SCOPE_TOP_LEVEL, 0U, 0U),
    DEAL_ARM_ENTRY(ARRAY_DELETE_BOUNDS, deal_arm_parameters_none, 0U,
        DEAL_ARM_SCOPE_TOP_LEVEL, 0U, 0U),
    DEAL_ARM_ENTRY(FUNCTION_SIGNATURE_MISMATCH, deal_arm_parameters_2, 2U,
        DEAL_ARM_SCOPE_TOP_LEVEL, 1U, 1U),
    DEAL_ARM_ENTRY(HOST_PARAMETER_CELL, deal_arm_parameters_10, 2U,
        DEAL_ARM_SCOPE_TOP_LEVEL, 1U, 1U),
    DEAL_ARM_ENTRY(HOST_SYNC_RETURN_NOTHING, deal_arm_parameters_11, 1U,
        DEAL_ARM_SCOPE_TOP_LEVEL, 1U, 1U),
    DEAL_ARM_ENTRY(HOST_SYNC_RETURN_CELL, deal_arm_parameters_12, 1U,
        DEAL_ARM_SCOPE_TOP_LEVEL, 1U, 1U),
    DEAL_ARM_ENTRY(ASYNC_COMPLETION_KIND, deal_arm_parameters_13, 1U,
        DEAL_ARM_SCOPE_TOP_LEVEL, 1U, 1U),
    DEAL_ARM_ENTRY(ASYNC_SHAPE, deal_arm_parameters_14, 1U,
        DEAL_ARM_SCOPE_TOP_LEVEL, 1U, 1U),
    DEAL_ARM_ENTRY(HOST_LOAD_FAILED, deal_arm_parameters_15, 2U,
        DEAL_ARM_SCOPE_TOP_LEVEL, 0U, 0U),
    DEAL_ARM_ENTRY(HOST_LOAD_RAW_SHAPE, deal_arm_parameters_none, 0U,
        DEAL_ARM_SCOPE_TOP_LEVEL, 1U, 1U),
    DEAL_ARM_ENTRY(HOST_LOAD_NOT_A_MODULE, deal_arm_parameters_17, 1U,
        DEAL_ARM_SCOPE_TOP_LEVEL, 0U, 0U),
    DEAL_ARM_ENTRY(HOST_LOAD_MISSING_EXPORT, deal_arm_parameters_18, 2U,
        DEAL_ARM_SCOPE_TOP_LEVEL, 0U, 0U),
    DEAL_ARM_ENTRY(HOST_LOAD_SIGNATURE_MISMATCH, deal_arm_parameters_19, 4U,
        DEAL_ARM_SCOPE_TOP_LEVEL, 0U, 0U),
    DEAL_ARM_ENTRY(HOST_LOAD_IDENTITY_MISMATCH, deal_arm_parameters_19, 4U,
        DEAL_ARM_SCOPE_TOP_LEVEL, 0U, 0U),
    DEAL_ARM_ENTRY(HOST_LOAD_INVALID_METADATA, deal_arm_parameters_21, 3U,
        DEAL_ARM_SCOPE_TOP_LEVEL, 0U, 0U),
    DEAL_ARM_ENTRY(CLASS_EXTRA_FIELD, deal_arm_parameters_22, 2U,
        DEAL_ARM_SCOPE_TOP_LEVEL, 0U, 0U),
    DEAL_ARM_ENTRY(JSON_PARSE_ERROR, deal_arm_parameters_23, 2U,
        DEAL_ARM_SCOPE_TOP_LEVEL, 0U, 0U),
    DEAL_ARM_ENTRY(JSON_TO_WALK, deal_arm_parameters_24, 2U,
        DEAL_ARM_SCOPE_TOP_LEVEL, 0U, 1U),
    DEAL_ARM_ENTRY(JSON_STRINGIFY_UNSUPPORTED, deal_arm_parameters_25, 1U,
        DEAL_ARM_SCOPE_TOP_LEVEL, 1U, 1U),
    DEAL_ARM_ENTRY(JSON_TO_WALK_CYCLE, deal_arm_parameters_none, 0U,
        DEAL_ARM_SCOPE_TOP_LEVEL, 0U, 0U),
    DEAL_ARM_ENTRY(SUBSTRING_BOUNDS, deal_arm_parameters_none, 0U,
        DEAL_ARM_SCOPE_TOP_LEVEL, 0U, 0U),
    DEAL_ARM_ENTRY(SQRT_NEGATIVE, deal_arm_parameters_none, 0U,
        DEAL_ARM_SCOPE_TOP_LEVEL, 0U, 1U)
};

/* The compiled repertoire entry of one arm, or NULL for an unknown arm id. */
static const deal_arm_repertoire_entry *deal_arm_repertoire_of(uint32_t arm_id) {
    if (arm_id >= DEAL_ARM_REPERTOIRE_COUNT) {
        return NULL;
    }
    if (deal_arm_repertoire[arm_id].id != arm_id) {
        return NULL;
    }
    return &deal_arm_repertoire[arm_id];
}

/* The installed record of one arm, or NULL when the arm is absent. */
static const deal_arm_record *deal_arm_record_of(uint32_t arm_id) {
    if (deal_installed_arm_table == NULL) {
        return NULL;
    }
    for (uint32_t index = 0; index < deal_installed_arm_table->count;
            index += 1u) {
        if (deal_installed_arm_table->records[index].id == arm_id) {
            return &deal_installed_arm_table->records[index];
        }
    }
    return NULL;
}

const deal_arm_table *deal_arm_table_installed(void) {
    return deal_installed_arm_table;
}

/*
 * The repertoire validation of one installed table (R5): every record names a
 * closed arm, declares that arm's parameter shape, render scope and
 * expected/actual fields, and every closed arm is present exactly once. An
 * unknown arm, a missing arm and a shape mismatch are deterministic producer
 * defects, reported before any user code runs.
 */
static void deal_arm_table_validate_repertoire(const deal_arm_table *table) {
    uint8_t seen[DEAL_ARM_REPERTOIRE_COUNT];
    for (uint32_t index = 0; index < DEAL_ARM_REPERTOIRE_COUNT; index += 1u) {
        seen[index] = 0u;
    }
    for (uint32_t index = 0; index < table->count; index += 1u) {
        const deal_arm_record *record = &table->records[index];
        const deal_arm_repertoire_entry *entry =
            deal_arm_repertoire_of(record->id);
        if (entry == NULL) {
            deal_platform_fault("arm table: unknown arm id");
        }
        if (seen[record->id] != 0u) {
            deal_platform_fault("arm table: duplicate arm id");
        }
        seen[record->id] = 1u;
        if (record->parameter_count != entry->parameter_count) {
            deal_platform_fault("arm table: parameter shape mismatch");
        }
        if (record->parameter_count != 0u) {
            if (record->parameter_names == NULL) {
                deal_platform_fault("arm table: parameter shape mismatch");
            }
            for (uint32_t slot = 0; slot < record->parameter_count; slot += 1u) {
                if (record->parameter_names[slot] == NULL) {
                    deal_platform_fault("arm table: null parameter name");
                }
                if (strcmp(record->parameter_names[slot],
                        entry->parameters[slot]) != 0) {
                    deal_platform_fault("arm table: parameter shape mismatch");
                }
            }
        }
        if (record->render_scope != entry->render_scope) {
            deal_platform_fault("arm table: render scope outside the closed set");
        }
        if (record->has_expected != entry->has_expected
                || record->has_actual != entry->has_actual) {
            deal_platform_fault("arm table: declared field shape mismatch");
        }
    }
    for (uint32_t index = 0; index < DEAL_ARM_REPERTOIRE_COUNT; index += 1u) {
        if (seen[index] == 0u) {
            deal_platform_fault("arm table: missing arm");
        }
    }
}

/* The delivered structural rules of the install entry (version, count, null
 * text, render scope, duplicate id), unchanged. */
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
    deal_arm_table_validate_repertoire(table);
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

/*
 * The record's platform-owned members (M1). The managed payload is the
 * collector's, but the copied text fields, the frames array and its duplicated
 * labels are platform storage that nothing else can reach once the record is
 * gone, so this group registers one reclamation hook
 * (`deal_layout_release_register`, memory.h) and the sweep runs it while the
 * payload is still intact. Every released field is cleared first, so a second
 * run of the hook (a reused payload that was never re-initialized, or a
 * defensive repeat) releases nothing twice; the cause is a managed reference
 * the collector reclaims on its own and is never released here.
 */
static void deal_error_release(void *object) {
    deal_error *error = object;
    deal_platform_release(error->code);
    error->code = NULL;
    deal_platform_release(error->message);
    error->message = NULL;
    deal_platform_release(error->source_file);
    error->source_file = NULL;
    deal_platform_release(error->expected);
    error->expected = NULL;
    deal_platform_release(error->actual);
    error->actual = NULL;
    if (error->frames != NULL) {
        for (uint32_t index = 0; index < error->frame_count; index += 1U) {
            deal_platform_release(error->frames[index]);
            error->frames[index] = NULL;
        }
        deal_platform_release(error->frames);
        error->frames = NULL;
    }
    error->frame_count = 0;
    error->cause = NULL;
}

/* One Error record allocation. `snapshot_frames` is 0 for the host-authored
 * text channel, whose record carries no DEAL frames at all (R5/H2). */
static deal_error *deal_error_allocate(const char *code, const char *message,
                                       const deal_origin *origin,
                                       int snapshot_frames) {
    if (code == NULL) {
        deal_platform_fault("error record: absent required code");
    }
    if (message == NULL) {
        deal_platform_fault("error record: absent required message");
    }
    deal_layout_define(&deal_error_layout);
    deal_layout_release_register(DEAL_LAYOUT_ERROR, deal_error_release);
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
    if (!snapshot_frames) {
        return error;
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

deal_error *deal_error_create(const char *code, const char *message,
                              const deal_origin *origin) {
    return deal_error_allocate(code, message, origin, 1);
}

/*
 * Replaces one optional text field of a record (R5, R9). The copy is taken
 * before the previous value is released: the caller may pass a pointer the
 * record itself owns — the accessor-returned `expected`/`actual` text — or a
 * pointer interior to it, so releasing first would read the freed text. A NULL
 * replacement clears the field, which stays absent and never becomes an empty
 * string.
 */
static void deal_error_replace_text(char **field, const char *text) {
    char *copy = deal_platform_duplicate(text);
    deal_platform_release(*field);
    *field = copy;
}

void deal_error_set_expected(deal_error *error, const char *expected) {
    if (error == NULL) {
        deal_platform_fault("error record: null error for the expected field");
    }
    deal_error_replace_text(&error->expected, expected);
}

void deal_error_set_actual(deal_error *error, const char *actual) {
    if (error == NULL) {
        deal_platform_fault("error record: null error for the actual field");
    }
    deal_error_replace_text(&error->actual, actual);
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

/* =========================================================================
 * The arm materialization (R5)
 * ========================================================================= */

/* One growing platform text buffer (the snapshot serializer's shape). */
typedef struct {
    char *bytes;
    size_t length;
    size_t capacity;
} deal_arm_buffer;

static void deal_arm_buffer_reserve(deal_arm_buffer *buffer, size_t additional) {
    size_t required = buffer->length + additional;
    if (required <= buffer->capacity) {
        return;
    }
    size_t capacity = buffer->capacity == 0u ? 64u : buffer->capacity;
    while (capacity < required) {
        capacity *= 2u;
    }
    char *grown = deal_platform_allocate(capacity);
    if (buffer->bytes != NULL) {
        memcpy(grown, buffer->bytes, buffer->length);
        deal_platform_release(buffer->bytes);
    }
    buffer->bytes = grown;
    buffer->capacity = capacity;
}

static void deal_arm_buffer_append(deal_arm_buffer *buffer, const char *bytes,
                                   size_t length) {
    if (length == 0u) {
        return;
    }
    deal_arm_buffer_reserve(buffer, length);
    memcpy(buffer->bytes + buffer->length, bytes, length);
    buffer->length += length;
}

/* The supplied value of one declared parameter, or NULL when it is absent. */
static const char *deal_arm_parameter_value(const deal_arm_parameter *parameters,
                                            size_t parameter_count,
                                            const char *name, size_t length) {
    for (size_t index = 0; index < parameter_count; index += 1u) {
        if (parameters[index].name == NULL) {
            deal_platform_fault("arm render: parameter without a name");
        }
        if (strlen(parameters[index].name) == length
                && strncmp(parameters[index].name, name, length) == 0) {
            return parameters[index].value;
        }
    }
    return NULL;
}

/*
 * Instantiates one arm's own template: every `{name}` placeholder is replaced
 * by the supplied value of that declared parameter, and every byte outside a
 * placeholder is emitted unchanged. A parameter the arm declares but the
 * render does not supply, a supplied parameter the arm does not declare, a
 * parameter without a value and an unclosed placeholder are malformed
 * parameter data — a deterministic producer defect, never a fallback text.
 */
static char *deal_arm_instantiate(const deal_arm_record *record,
                                  const deal_arm_parameter *parameters,
                                  size_t parameter_count) {
    for (size_t index = 0; index < parameter_count; index += 1u) {
        if (parameters[index].name == NULL || parameters[index].value == NULL) {
            deal_platform_fault("arm render: malformed parameter data");
        }
        int declared = 0;
        for (uint32_t slot = 0; slot < record->parameter_count; slot += 1u) {
            if (strcmp(record->parameter_names[slot], parameters[index].name) == 0) {
                declared = 1;
            }
        }
        if (!declared) {
            deal_platform_fault("arm render: undeclared parameter");
        }
    }
    for (uint32_t slot = 0; slot < record->parameter_count; slot += 1u) {
        if (deal_arm_parameter_value(parameters, parameter_count,
                record->parameter_names[slot],
                strlen(record->parameter_names[slot])) == NULL) {
            deal_platform_fault("arm render: missing declared parameter");
        }
    }
    deal_arm_buffer buffer;
    buffer.bytes = NULL;
    buffer.length = 0u;
    buffer.capacity = 0u;
    const char *text = record->template_text;
    size_t cursor = 0u;
    while (text[cursor] != 0) {
        if (text[cursor] != '{') {
            deal_arm_buffer_append(&buffer, text + cursor, 1u);
            cursor += 1u;
            continue;
        }
        size_t close = cursor + 1u;
        while (text[close] != 0 && text[close] != '}') {
            close += 1u;
        }
        if (text[close] != '}') {
            deal_platform_fault("arm render: unclosed placeholder");
        }
        const char *value = deal_arm_parameter_value(parameters, parameter_count,
            text + cursor + 1u, close - cursor - 1u);
        if (value == NULL) {
            deal_platform_fault("arm render: placeholder outside the declaration");
        }
        deal_arm_buffer_append(&buffer, value, strlen(value));
        cursor = close + 1u;
    }
    deal_arm_buffer_reserve(&buffer, 1u);
    buffer.bytes[buffer.length] = 0;
    return buffer.bytes;
}

char *deal_arm_message(uint32_t arm_id, const deal_arm_parameter *parameters,
                       size_t parameter_count) {
    const deal_arm_record *record = deal_arm_record_of(arm_id);
    if (record == NULL) {
        deal_platform_fault("arm render: unknown arm id");
    }
    if (parameter_count != 0u && parameters == NULL) {
        deal_platform_fault("arm render: malformed parameter data");
    }
    return deal_arm_instantiate(record, parameters, parameter_count);
}

void deal_arm_text_release(char *text) {
    deal_platform_release(text);
}

deal_error *deal_error_materialize(uint32_t arm_id,
                                   const deal_arm_parameter *parameters,
                                   size_t parameter_count, const char *expected,
                                   const char *actual, const deal_origin *origin,
                                   deal_error *cause) {
    if (deal_installed_arm_table == NULL) {
        deal_platform_fault("arm render: no installed arm table");
    }
    const deal_arm_record *record = deal_arm_record_of(arm_id);
    if (record == NULL) {
        deal_platform_fault("arm render: unknown arm id");
    }
    if (record->render_scope != DEAL_ARM_SCOPE_TOP_LEVEL) {
        deal_platform_fault("arm render: inner-only arm rendered at a site");
    }
    if ((record->has_expected != 0u) != (expected != NULL)) {
        deal_platform_fault("arm render: declared field shape mismatch");
    }
    if ((record->has_actual != 0u) != (actual != NULL)) {
        deal_platform_fault("arm render: declared field shape mismatch");
    }
    if (parameter_count != 0u && parameters == NULL) {
        deal_platform_fault("arm render: malformed parameter data");
    }
    char *message = deal_arm_instantiate(record, parameters, parameter_count);
    /* The cause is a runtime intermediate across the record's own allocation,
     * so one scoped root keeps it alive at that allocation safepoint (M5). */
    if (cause != NULL) {
        deal_root_scope_push();
        uint32_t slot = deal_root_scope_hold(cause);
        deal_error *error = deal_error_allocate(record->code, message, origin, 1);
        deal_error_set_expected(error, expected);
        deal_error_set_actual(error, actual);
        deal_error_set_cause(error, cause);
        deal_root_scope_release(slot);
        deal_root_scope_pop();
        deal_arm_text_release(message);
        return error;
    }
    deal_error *error = deal_error_allocate(record->code, message, origin, 1);
    deal_error_set_expected(error, expected);
    deal_error_set_actual(error, actual);
    deal_arm_text_release(message);
    return error;
}

deal_error *deal_error_array_position(uint32_t arm_id, const deal_origin *origin) {
    if (origin == NULL) {
        deal_platform_fault("array position: missing origin");
    }
    if (arm_id != DEAL_ARM_ID_ARRAY_READ_BOUNDS
            && arm_id != DEAL_ARM_ID_ARRAY_WRITE_BOUNDS
            && arm_id != DEAL_ARM_ID_ARRAY_DELETE_BOUNDS) {
        deal_platform_fault("array position: not a bounds arm");
    }
    return deal_error_materialize(arm_id, NULL, 0u, NULL, NULL, origin, NULL);
}

/* =========================================================================
 * The canonical floating-point text of one arm actual (R5)
 * ========================================================================= */

const char *deal_arm_number_text(double value, char *buffer, size_t capacity) {
    if (buffer == NULL || capacity < DEAL_ARM_NUMBER_TEXT_CAPACITY) {
        deal_platform_fault("arm render: number buffer too small");
    }
    uint64_t bits = 0u;
    memcpy(&bits, &value, sizeof(bits));
    int negative = (bits >> 63) != 0u;
    uint32_t exponent_bits = (uint32_t)((bits >> 52) & 0x7FFu);
    uint64_t fraction = bits & UINT64_C(0xFFFFFFFFFFFFF);
    size_t length = 0u;
    if (negative) {
        buffer[length] = '-';
        length += 1u;
    }
    if (exponent_bits == 0x7FFu) {
        const char *spelling = fraction == 0u ? "Infinity" : "NaN";
        if (fraction != 0u) {
            /* NaN carries no sign in the canonical spelling. */
            length = 0u;
        }
        size_t spelling_length = strlen(spelling);
        memcpy(buffer + length, spelling, spelling_length);
        buffer[length + spelling_length] = 0;
        return buffer;
    }
    if (exponent_bits == 0u && fraction == 0u) {
        /* Both zeros spell the zero significand with a zero exponent. */
        const char *spelling = "-0x0.0p0";
        if (!negative) {
            spelling = "0x0.0p0";
        }
        length = strlen(spelling);
        memcpy(buffer, spelling, length + 1u);
        return buffer;
    }
    static const char hex[] = "0123456789abcdef";
    char digits[14];
    uint64_t remaining = fraction;
    for (int index = 12; index >= 0; index -= 1u) {
        digits[index] = hex[(size_t)(remaining & 0xFu)];
        remaining >>= 4;
    }
    size_t digit_count = 13u;
    while (digit_count > 1u && digits[digit_count - 1u] == '0') {
        digit_count -= 1u;
    }
    int exponent;
    if (exponent_bits == 0u) {
        /* A subnormal keeps the fixed-exponent spelling: `0x0.<digits>p-1022`. */
        exponent = -1022;
        memcpy(buffer + length, "0x0.", 4u);
        length += 4u;
    } else {
        exponent = (int)exponent_bits - 1023;
        memcpy(buffer + length, "0x1.", 4u);
        length += 4u;
    }
    memcpy(buffer + length, digits, digit_count);
    length += digit_count;
    buffer[length] = 'p';
    length += 1u;
    if (exponent < 0) {
        buffer[length] = '-';
        length += 1u;
        exponent = -exponent;
    }
    char digits_exp[8];
    size_t exp_length = 0u;
    do {
        digits_exp[exp_length] = (char)('0' + (exponent % 10));
        exp_length += 1u;
        exponent /= 10;
    } while (exponent > 0);
    for (size_t index = 0; index < exp_length; index += 1u) {
        buffer[length] = digits_exp[exp_length - 1u - index];
        length += 1u;
    }
    buffer[length] = 0;
    return buffer;
}

/* =========================================================================
 * Failure admission (R5, `FailureAdmission`)
 * ========================================================================= */

deal_error *deal_error_host_text(const char *code, const char *message,
                                 const char *expected, const char *actual) {
    /* The host's own text: no arm, no origin, no frames and no cause (R5). */
    deal_error *error = deal_error_allocate(code, message, NULL, 0);
    deal_error_set_expected(error, expected);
    deal_error_set_actual(error, actual);
    return error;
}

deal_error *deal_failure_admit(const deal_failure_admission *admission) {
    if (admission == NULL) {
        deal_platform_fault("failure admission: null admission");
    }
    switch ((deal_failure_kind)admission->kind) {
    case DEAL_FAILURE_HOST_TEXT:
        if (admission->error != NULL) {
            deal_platform_fault("failure admission: host text beside a relayed Error");
        }
        return deal_error_host_text(admission->code, admission->message,
            admission->expected, admission->actual);
    case DEAL_FAILURE_DEAL_ERROR:
        if (admission->error == NULL) {
            deal_platform_fault("failure admission: relayed channel without an Error");
        }
        if (admission->code != NULL || admission->message != NULL
                || admission->expected != NULL || admission->actual != NULL) {
            deal_platform_fault("failure admission: relayed Error beside host text");
        }
        /* The very object the DEAL-origin failure names: no replacement, no
         * added field, no refreshed origin, no new frames and no new cause. */
        return admission->error;
    }
    deal_platform_fault("failure admission: kind outside the closed set");
}
